package net.kollnig.missioncontrol.netbird

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetbirdReconnectTest {

    @Test
    fun sessionErrorsNeedRelogin() {
        assertTrue(NetbirdReconnect.needsRelogin("NetBird: rpc error: code = PermissionDenied desc = peer not found"))
        assertTrue(NetbirdReconnect.needsRelogin("NetBird: Unauthenticated"))
    }

    @Test
    fun otherErrorsAreRetried() {
        assertFalse(NetbirdReconnect.needsRelogin(null))
        assertFalse(NetbirdReconnect.needsRelogin(""))
        assertFalse(NetbirdReconnect.needsRelogin("NetBird: connection refused"))
        // The engine wraps network failures in "login failed": that is a retry, not a relogin
        // (the engine itself only flags NeedsLogin on PermissionDenied).
        assertFalse(NetbirdReconnect.needsRelogin("login failed: rpc error: code = Unavailable desc = connection refused"))
        assertFalse(NetbirdReconnect.needsRelogin("NetBird: failed to get token info: context deadline exceeded"))
        assertFalse(NetbirdReconnect.needsRelogin("NetBird: context deadline exceeded"))
    }

    @Test
    fun backoffDoublesUpToFiveMinutes() {
        val got = (0..7).map { NetbirdReconnect.backoffMs(it) / 1000 }
        assertEquals(listOf(30L, 60L, 120L, 240L, 300L, 300L, 300L, 300L), got)
        assertEquals(30_000L, NetbirdReconnect.backoffMs(-1))
        assertEquals(300_000L, NetbirdReconnect.backoffMs(Int.MAX_VALUE))
    }
}
