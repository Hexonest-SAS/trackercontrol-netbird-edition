//go:build linux

package device

import (
	"errors"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/sys/unix"
)

// newPair returns both ends of an AF_UNIX SOCK_DGRAM socketpair, the kind
// TrackerControl's C layer hands to the transport.
func newPair(t *testing.T) (int, int) {
	t.Helper()
	sv, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	require.NoError(t, err)
	return sv[0], sv[1]
}

// newTestTUN wires a SocketpairTUN like TrackerControl does: cWrite is the C
// side of the outbound socketpair, tunRead receives what the device writes to
// the "TUN" (a second socketpair standing in for the VpnService fd).
func newTestTUN(t *testing.T) (dev *SocketpairTUN, cWrite, tunRead int) {
	t.Helper()
	cWrite, rx := newPair(t)
	tx, tunRead := newPair(t)
	dev, err := NewSocketpairTUN(rx, tx, 1280)
	require.NoError(t, err)
	t.Cleanup(func() {
		_ = dev.Close()
		_ = unix.Close(cWrite)
		_ = unix.Close(tunRead)
	})
	return dev, cWrite, tunRead
}

func TestSocketpairTUN_ReadBatchesQueuedDatagrams(t *testing.T) {
	dev, cWrite, _ := newTestTUN(t)

	pkts := [][]byte{{0x45, 1}, {0x45, 2, 2}, {0x45, 3, 3, 3}}
	for _, p := range pkts {
		_, err := unix.Write(cWrite, p)
		require.NoError(t, err)
	}

	const offset = 16
	bufs := make([][]byte, 4)
	for i := range bufs {
		bufs[i] = make([]byte, offset+1500)
	}
	sizes := make([]int, 4)

	n, err := dev.Read(bufs, sizes, offset)
	require.NoError(t, err)
	require.Equal(t, 3, n, "all queued datagrams should be read in one call")
	for i, p := range pkts {
		assert.Equal(t, len(p), sizes[i], "size of packet %d", i)
		assert.Equal(t, p, bufs[i][offset:offset+sizes[i]], "content of packet %d", i)
	}
}

func TestSocketpairTUN_ReadWaitsThenCloseUnblocks(t *testing.T) {
	dev, cWrite, _ := newTestTUN(t)

	type result struct {
		n   int
		err error
	}
	read := func() <-chan result {
		ch := make(chan result, 1)
		go func() {
			bufs := [][]byte{make([]byte, 1500)}
			n, err := dev.Read(bufs, make([]int, 1), 0)
			ch <- result{n, err}
		}()
		return ch
	}

	first := read()
	select {
	case r := <-first:
		t.Fatalf("read returned before any datagram: %+v", r)
	case <-time.After(100 * time.Millisecond):
	}
	_, err := unix.Write(cWrite, []byte{0x45, 9})
	require.NoError(t, err)
	r := <-first
	require.NoError(t, r.err)
	assert.Equal(t, 1, r.n, "the waiting read should get the datagram")

	blocked := read()
	time.Sleep(50 * time.Millisecond)
	require.NoError(t, dev.Close())
	select {
	case r := <-blocked:
		assert.True(t, errors.Is(r.err, os.ErrClosed), "close should end a pending read, got %v", r.err)
	case <-time.After(2 * time.Second):
		t.Fatal("Close did not unblock a pending Read")
	}
}

func TestSocketpairTUN_WriteGoesToTUNAndKeepsItBlocking(t *testing.T) {
	dev, _, tunRead := newTestTUN(t)

	flagsBefore, err := unix.FcntlInt(uintptr(dev.txFD), unix.F_GETFL, 0)
	require.NoError(t, err)
	require.Zero(t, flagsBefore&unix.O_NONBLOCK, "test precondition: tun fd starts blocking")

	const offset = 32
	pkts := [][]byte{{0x45, 0xa}, {0x60, 0xb, 0xb}}
	bufs := make([][]byte, len(pkts))
	for i, p := range pkts {
		bufs[i] = append(make([]byte, offset), p...)
	}
	n, err := dev.Write(bufs, offset)
	require.NoError(t, err)
	assert.Equal(t, 2, n, "both packets should be written")

	got := make([]byte, 1500)
	for i, p := range pkts {
		m, err := unix.Read(tunRead, got)
		require.NoError(t, err)
		assert.Equal(t, p, got[:m], "packet %d on the TUN, without the offset", i)
	}

	flagsAfter, err := unix.FcntlInt(uintptr(dev.txFD), unix.F_GETFL, 0)
	require.NoError(t, err)
	assert.Zero(t, flagsAfter&unix.O_NONBLOCK, "the shared TUN description must stay blocking")

	require.NoError(t, dev.Close())
	_, err = dev.Write(bufs, offset)
	assert.True(t, errors.Is(err, os.ErrClosed), "write after close, got %v", err)
	assert.NoError(t, dev.Close(), "Close must be idempotent")
}

func TestSocketpairTUN_MetadataAndEvents(t *testing.T) {
	dev, _, _ := newTestTUN(t)

	mtu, err := dev.MTU()
	require.NoError(t, err)
	assert.Equal(t, 1280, mtu)
	name, err := dev.Name()
	require.NoError(t, err)
	assert.Equal(t, socketpairTUNName, name)
	assert.Equal(t, 1, dev.BatchSize())

	require.NoError(t, dev.Close())
	_, open := <-dev.Events()
	assert.False(t, open, "Close should close the event channel")
}

func TestCreateTUNFromAdapterFD_ConsumesPendingWriteFD(t *testing.T) {
	cWrite, rx := newPair(t)
	tx, tunRead := newPair(t)
	t.Cleanup(func() {
		_ = unix.Close(cWrite)
		_ = unix.Close(tunRead)
	})

	SetSocketpairTunWriteFD(tx)
	dev, name, err := createTUNFromAdapterFD(rx, 1280)
	require.NoError(t, err)
	t.Cleanup(func() { _ = dev.Close() })
	assert.Equal(t, socketpairTUNName, name)
	_, ok := dev.(*SocketpairTUN)
	assert.True(t, ok, "a pending write fd should select the socketpair device, got %T", dev)

	// The pending fd is consumed: the next creation takes the upstream path,
	// whose TUNGETIFF fails on a socket. This is the failure the socketpair
	// device exists to avoid.
	other, otherPeer := newPair(t)
	t.Cleanup(func() {
		_ = unix.Close(other)
		_ = unix.Close(otherPeer)
	})
	_, _, err = createTUNFromAdapterFD(other, 1280)
	assert.Error(t, err, "without a pending write fd the fd is opened as a real TUN")
}
