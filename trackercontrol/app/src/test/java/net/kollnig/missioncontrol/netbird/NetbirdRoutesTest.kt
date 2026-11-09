package net.kollnig.missioncontrol.netbird

import eu.faircode.netguard.IPUtil
import eu.faircode.netguard.VpnRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class NetbirdRoutesTest {

    private fun toLong(a: InetAddress): Long = a.address.fold(0L) { r, b -> (r shl 8) or (b.toLong() and 0xFF) }

    private fun isRouted(routes: List<IPUtil.CIDR>, ip: String): Boolean {
        val t = toLong(InetAddress.getByName(ip))
        return routes.any { t >= toLong(it.start) && t <= toLong(it.end) }
    }

    @Test
    fun networkOfMasksTheHostBits() {
        assertEquals("100.102.0.0/16", NetbirdEgress.networkOf("100.102.136.18/16"))
        assertEquals("fd91:d072:4c7f:3163:0:0:0:0/64",
            NetbirdEgress.networkOf("fd91:d072:4c7f:3163:18f0:2856:7c2d:e588/64"))
        assertNull("host prefix carries no network", NetbirdEgress.networkOf("100.102.136.18/32"))
        assertNull(NetbirdEgress.networkOf(""))
        assertNull(NetbirdEgress.networkOf("100.102.136.18"))
        assertNull("no DNS lookup for names", NetbirdEgress.networkOf("example.com/16"))
    }

    @Test
    fun overlayNetworkIsRoutedEvenWhenTheEngineAnnouncesNoRoute() {
        // What the phone received: no network route, address with its overlay prefix.
        val routes = NetbirdEgress.effectiveRoutes("", "100.102.136.18/16",
            "fd91:d072:4c7f:3163:18f0:2856:7c2d:e588/64")
        assertTrue(routes.contains("100.102.0.0/16"))
        assertTrue(routes.contains("fd91:d072:4c7f:3163:0:0:0:0/64"))

        val vpn = VpnRoutes.getRoutes(routes)
        assertTrue("the NetBird peer goes through the VPN", isRouted(vpn, "100.102.79.78"))
        assertTrue(!isRouted(vpn, "100.64.0.1"))
    }

    @Test
    fun announcedRoutesAreKeptAndNotDuplicated() {
        val routes = NetbirdEgress.effectiveRoutes("10.20.0.0/24;100.102.0.0/16", "100.102.136.18/16", "")
        assertEquals(listOf("10.20.0.0/24", "100.102.0.0/16"), routes)
    }
}
