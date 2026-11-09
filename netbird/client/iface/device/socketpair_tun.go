//go:build linux

package device

// TrackerControl integration: a tun.Device that does not own a TUN interface.
//
// TrackerControl (fork of NetGuard) owns the VpnService TUN. Its C layer
// filters every outbound packet and writes the ones destined to the tunnel
// into a SOCK_DGRAM socketpair; the transport engine reads them from the
// other end. Decrypted inbound packets are written straight to the TUN fd.
// CreateUnmonitoredTUNFromFD cannot be used on such a split pair (it issues
// TUNGETIFF on the fd, which fails on a socket), hence this device.

import (
	"errors"
	"fmt"
	"os"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"

	"github.com/netbirdio/netbird/client/iface/wgbackend"
)

const socketpairTUNName = "tc-netbird"

// pendingTunWriteFD holds the TUN fd that the next device created from a
// TunAdapter fd must write inbound packets to, or -1 to use the fd as a real
// TUN (upstream behaviour). See SetSocketpairTunWriteFD.
var pendingTunWriteFD atomic.Int64

func init() {
	pendingTunWriteFD.Store(-1)
}

// SetSocketpairTunWriteFD switches the next Create or RenewTun to the
// socketpair mode: the fd returned by TunAdapter.ConfigureInterface (or given
// to RenewTun) is then read as the outbound socketpair end, and inbound
// packets are written to tunWriteFD. The device takes ownership of both fds,
// so the caller passes dups. A negative value restores the upstream mode.
// The value is consumed by the next device creation.
func SetSocketpairTunWriteFD(tunWriteFD int) {
	pendingTunWriteFD.Store(int64(tunWriteFD))
}

// createTUNFromAdapterFD wraps the fd obtained from the TunAdapter, either as
// a socketpair device (TrackerControl) or as a real TUN (upstream).
func createTUNFromAdapterFD(fd int, mtu int) (wgbackend.TUN, string, error) {
	tx := int(pendingTunWriteFD.Swap(-1))
	if tx < 0 {
		return wgbackend.CreateUnmonitoredTUNFromFD(fd)
	}
	dev, err := NewSocketpairTUN(fd, tx, mtu)
	if err != nil {
		_ = unix.Close(tx)
		return nil, "", err
	}
	return dev, socketpairTUNName, nil
}

// SocketpairTUN reads outbound IP packets, one per datagram, from rxFD and
// writes inbound IP packets to txFD.
type SocketpairTUN struct {
	rx     *os.File
	txFD   int
	mtu    int
	events chan wgbackend.TUNEvent

	writeMu   sync.Mutex
	closeOnce sync.Once
	closed    atomic.Bool
}

// NewSocketpairTUN takes ownership of rxFD (socketpair end, set non-blocking
// and served by the Go poller) and txFD (TUN fd, written with blocking writes:
// its file description is shared with TrackerControl's C layer, which needs it
// blocking, so its flags are left untouched).
func NewSocketpairTUN(rxFD, txFD, mtu int) (*SocketpairTUN, error) {
	if rxFD < 0 || txFD < 0 {
		return nil, fmt.Errorf("socketpair tun: invalid fds rx=%d tx=%d", rxFD, txFD)
	}
	if err := unix.SetNonblock(rxFD, true); err != nil {
		return nil, fmt.Errorf("socketpair tun: set non-blocking: %w", err)
	}
	return &SocketpairTUN{
		rx:     os.NewFile(uintptr(rxFD), "tc-socketpair"),
		txFD:   txFD,
		mtu:    mtu,
		events: make(chan wgbackend.TUNEvent, 1),
	}, nil
}

func (t *SocketpairTUN) File() *os.File { return t.rx }

// Read fills up to len(bufs) packets: it waits for the first datagram, then
// takes the ones already queued without waiting.
func (t *SocketpairTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, nil
	}
	rc, err := t.rx.SyscallConn()
	if err != nil {
		return 0, os.ErrClosed
	}
	n := 0
	var readErr error
	err = rc.Read(func(fd uintptr) bool {
		for n < len(bufs) {
			m, e := unix.Read(int(fd), bufs[n][offset:])
			if e == unix.EINTR {
				continue
			}
			if e == unix.EAGAIN {
				// Nothing more queued: wait if nothing read yet.
				return n > 0
			}
			if e != nil {
				if n == 0 {
					readErr = e
				}
				return true
			}
			if m == 0 {
				if n == 0 {
					readErr = os.ErrClosed
				}
				return true
			}
			sizes[n] = m
			n++
		}
		return true
	})
	if n > 0 {
		return n, nil
	}
	// The poller reports a concurrent Close as an internal "use of closed
	// file" error, while wireguard-go stops reading only on os.ErrClosed.
	if t.closed.Load() {
		return 0, os.ErrClosed
	}
	if err != nil {
		return 0, mapClosed(err)
	}
	return 0, mapClosed(readErr)
}

// Write writes every packet. A failed packet (e.g. ENOBUFS under load) is
// dropped and the others still go through; the first error is returned.
func (t *SocketpairTUN) Write(bufs [][]byte, offset int) (int, error) {
	t.writeMu.Lock()
	defer t.writeMu.Unlock()
	if t.closed.Load() {
		return 0, os.ErrClosed
	}
	written := 0
	var firstErr error
	for _, buf := range bufs {
		pkt := buf[offset:]
		for {
			m, err := unix.Write(t.txFD, pkt)
			if err == unix.EINTR {
				continue
			}
			if err == nil && m != len(pkt) {
				err = fmt.Errorf("short tun write %d/%d", m, len(pkt))
			}
			if err != nil {
				if firstErr == nil {
					firstErr = mapClosed(err)
				}
			} else {
				written++
			}
			break
		}
	}
	return written, firstErr
}

func (t *SocketpairTUN) MTU() (int, error) { return t.mtu, nil }

func (t *SocketpairTUN) Name() (string, error) { return socketpairTUNName, nil }

func (t *SocketpairTUN) Events() <-chan wgbackend.TUNEvent { return t.events }

func (t *SocketpairTUN) BatchSize() int { return 1 }

func (t *SocketpairTUN) Close() error {
	var err error
	t.closeOnce.Do(func() {
		t.closed.Store(true)
		// Closing the os.File wakes a Read blocked in the poller.
		err = t.rx.Close()
		t.writeMu.Lock()
		if e := unix.Close(t.txFD); e != nil && err == nil {
			err = e
		}
		t.txFD = -1
		t.writeMu.Unlock()
		close(t.events)
	})
	return err
}

func mapClosed(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, unix.EBADF) || errors.Is(err, os.ErrClosed) {
		return os.ErrClosed
	}
	return err
}
