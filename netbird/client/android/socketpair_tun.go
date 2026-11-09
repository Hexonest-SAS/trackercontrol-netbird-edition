//go:build android

package android

import "github.com/netbirdio/netbird/client/iface/device"

// SetSocketpairTunWriteFd makes the next interface creation (Run) or RenewTun
// use TrackerControl's split pair: the fd returned by
// TunAdapter.ConfigureInterface is read as the outbound socketpair end, and
// inbound packets are written to tunWriteFd, the VpnService TUN fd. The engine
// takes ownership of both fds, so pass detached dups. Call it again before
// every RenewTun; a negative value restores the upstream behaviour.
func SetSocketpairTunWriteFd(tunWriteFd int) {
	device.SetSocketpairTunWriteFD(tunWriteFd)
}
