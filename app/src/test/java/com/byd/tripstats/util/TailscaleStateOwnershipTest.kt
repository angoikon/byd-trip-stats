package com.byd.tripstats.util

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for [tailscaleStateIsOurs] — which decides whether the old shared `.tailscale` state is
 * this app's (copied across, then removed so Overdrive's Tailscale can't pick up a copy) or someone
 * else's (left exactly where it is). The state's shape is Tailscale's: a JSON object whose values
 * are base64, with each `profile-…` entry decoding to that profile's prefs.
 */
class TailscaleStateOwnershipTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun state(vararg profiles: Pair<String, String>): String {
        val root = JSONObject()
        root.put("_machinekey", b64("privkey:not-a-real-key"))
        root.put("_current-profile", b64(profiles.firstOrNull()?.first ?: ""))
        profiles.forEach { (key, host) -> root.put(key, b64("""{"ControlURL":"x","Hostname":"$host","RouteAll":false}""")) }
        return root.toString()
    }

    @Test
    fun `a profile signed in by this app is ours`() {
        assertTrue(tailscaleStateIsOurs(state("profile-a1b2" to "seal"), "seal"))
    }

    @Test
    fun `the hostname is compared without case`() {
        assertTrue(tailscaleStateIsOurs(state("profile-a1b2" to "Seal"), "seal"))
    }

    @Test
    fun `overdrive's profile is not ours`() {
        // The Sealion 7 that shared the folder (2026-10-14): Overdrive had signed in first.
        assertFalse(tailscaleStateIsOurs(state("profile-d62b" to "overdrive"), "sealion-7"))
    }

    @Test
    fun `a state shared with another app is not ours`() {
        assertFalse(tailscaleStateIsOurs(state("profile-a1b2" to "seal", "profile-d62b" to "overdrive"), "seal"))
    }

    @Test
    fun `a state with no profile left is not ours to delete`() {
        // A logged-out state keeps only its machine key: nothing in it says whose it is.
        assertFalse(tailscaleStateIsOurs(state(), "seal"))
    }

    @Test
    fun `anything unreadable is not ours`() {
        assertFalse(tailscaleStateIsOurs("", "seal"))
        assertFalse(tailscaleStateIsOurs("not json", "seal"))
        assertFalse(tailscaleStateIsOurs("""{"profile-a1b2":"%%% not base64 %%%"}""", "seal"))
    }
}
