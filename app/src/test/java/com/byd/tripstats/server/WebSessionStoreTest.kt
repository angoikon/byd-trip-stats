package com.byd.tripstats.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WebSessionStore] — web companion logins that outlive an app restart — and for
 * [webClientAddress], which counts wrong PINs per device behind `tailscale serve`.
 */
class WebSessionStoreTest {

    private class MemoryStorage : WebSessionStore.Storage {
        var saved: WebSessionStore.Saved? = null
        override fun read() = saved
        override fun write(saved: WebSessionStore.Saved) { this.saved = saved }
    }

    private val day = 24 * 3600_000L
    private val maxAge = 30 * day
    private var clock = 1_790_000_000_000L
    private val storage = MemoryStorage()

    private fun store(pin: String = "1234") = WebSessionStore(storage, pin, maxAge) { clock }

    @Test
    fun `a login survives an app restart`() {
        store().add("token-a")
        assertTrue(store().contains("token-a"))   // a new store over the same storage = a restart
    }

    @Test
    fun `an unknown token is not a login`() {
        store().add("token-a")
        assertFalse(store().contains("token-b"))
    }

    @Test
    fun `changing the PIN logs every device out`() {
        store("1234").add("token-a")
        assertFalse(store("9999").contains("token-a"))
        // ...and for good: going back to the old PIN doesn't bring the old logins back.
        assertFalse(store("1234").contains("token-a"))
    }

    @Test
    fun `a login lasts as long as its cookie`() {
        store().add("token-a")
        clock += maxAge - 1
        assertTrue(store().contains("token-a"))
        clock += 1
        assertFalse(store().contains("token-a"))
    }

    @Test
    fun `logging out ends the login across a restart`() {
        val s = store()
        s.add("token-a")
        s.remove("token-a")
        assertFalse(store().contains("token-a"))
    }

    @Test
    fun `only a hash of the token is stored`() {
        store().add("token-a")
        val keys = storage.saved!!.sessions.keys
        assertEquals(1, keys.size)
        assertFalse(keys.any { it.contains("token-a") })
    }

    @Test
    fun `only the newest logins are kept`() {
        val s = store()
        repeat(WebSessionStore.MAX_SESSIONS + 3) { i ->
            clock += 1_000L
            s.add("token-$i")
        }
        assertEquals(WebSessionStore.MAX_SESSIONS, storage.saved!!.sessions.size)
        assertFalse(store().contains("token-0"))
        assertTrue(store().contains("token-${WebSessionStore.MAX_SESSIONS + 2}"))
    }

    // ── Client address ────────────────────────────────────────────────────────

    @Test
    fun `a direct client is counted by its own address`() {
        assertEquals("192.168.178.20", webClientAddress("192.168.178.20", null))
    }

    @Test
    fun `behind tailscale serve each device is counted by its tailnet address`() {
        assertEquals("100.101.102.103", webClientAddress("127.0.0.1", "100.101.102.103"))
        assertEquals("fd7a:115c:a1e0::1", webClientAddress("::1", "fd7a:115c:a1e0::1"))
    }

    @Test
    fun `a forwarded address sent straight from the LAN is ignored`() {
        assertEquals("192.168.178.20", webClientAddress("192.168.178.20", "100.64.0.9"))
    }

    @Test
    fun `the loopback address stands when no proxy names a client`() {
        assertEquals("127.0.0.1", webClientAddress("127.0.0.1", null))
        assertEquals("127.0.0.1", webClientAddress("127.0.0.1", " "))
        assertEquals("unknown", webClientAddress(null, "100.64.0.9"))
    }
}
