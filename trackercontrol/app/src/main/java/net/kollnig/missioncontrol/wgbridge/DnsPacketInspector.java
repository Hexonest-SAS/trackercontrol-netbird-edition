package net.kollnig.missioncontrol.wgbridge;

/**
 * DNS policy for inbound packets of a tunnel not run by gotatun (the NetBird
 * engine): the inspector the WireGuard transport applies before writing to the
 * TUN, which records name to address mappings for tracker attribution and
 * rewrites responses where the policy says so. One instance per tunnel: TCP
 * rewriting keeps framing state across segments.
 */
public final class DnsPacketInspector implements AutoCloseable {
    static {
        System.loadLibrary("wgbridge");
    }

    private long handle;

    public DnsPacketInspector(WgDnsRecorder recorder) {
        handle = nativeNew(recorder);
    }

    /**
     * @return the packet to write in place of {@code packet}, or null to keep it
     */
    public synchronized byte[] inspect(byte[] packet) {
        if (handle == 0)
            return null;
        return nativeInspect(handle, packet);
    }

    @Override
    public synchronized void close() {
        long h = handle;
        handle = 0;
        nativeFree(h);
    }

    private static native long nativeNew(WgDnsRecorder recorder);

    private static native byte[] nativeInspect(long handle, byte[] packet);

    private static native void nativeFree(long handle);
}
