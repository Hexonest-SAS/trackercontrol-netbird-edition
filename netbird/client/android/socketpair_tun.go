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

// DNSInspector sees each inbound DNS response of the socketpair device before
// it is written to the TUN. InspectInboundDNS returns the packet to write, or
// null to keep the original.
type DNSInspector interface {
	InspectInboundDNS(packet []byte) []byte
}

// SetInboundDNSInspector installs the inspector, or removes it when nil.
func SetInboundDNSInspector(inspector DNSInspector) {
	if inspector == nil {
		device.SetInboundDNSInspector(nil)
		return
	}
	device.SetInboundDNSInspector(inspector.InspectInboundDNS)
}
