package com.byd.tripstats.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure halves of [Di5MobileDataGate] — the `dumpsys netpolicy` parser
 * (both Android 11 dump shapes, plus BYD's custom labels) and the gate decisions. The
 * shell/settings interactions are field-tested on a car; these pin the logic that decides
 * what gets written and when.
 */
class Di5MobileDataGateTest {

    private val uid = 10042

    // The per-uid block shape Android 11 prints (labels in brackets are BYD's own —
    // `DIABLE_METERED` is their typo'd string, kept verbatim from the field report).
    private val blockDump = """
        System restrictions:
          restrict background: false
        Policy-based rules:
          UID=1000:
            policy=0
          UID=$uid:
            policy=9 (DIABLE_METERED)
            state=4 (fg svc)
            rules=4 (REJECT_METERED)
          UID=10100:
            policy=0
        Active rules:
          UID=$uid: rules=4 (REJECT_METERED)
    """.trimIndent()

    // Everything on one line, lower-case, no labels.
    private val inlineDump = """
        Policy-based rules:
          uid=$uid policy=1 rules=4
    """.trimIndent()

    @Test
    fun `block dump - policy 9 with BYD label is parsed`() {
        assertEquals(9, Di5MobileDataGate.parseUidPolicy(blockDump, uid))
    }

    @Test
    fun `block dump - rules are parsed too`() {
        assertEquals(4, Di5MobileDataGate.parseUidRules(blockDump, uid))
    }

    @Test
    fun `inline dump shape is parsed`() {
        assertEquals(1, Di5MobileDataGate.parseUidPolicy(inlineDump, uid))
        assertEquals(4, Di5MobileDataGate.parseUidRules(inlineDump, uid))
    }

    @Test
    fun `a uid absent from the dump yields null`() {
        assertNull(Di5MobileDataGate.parseUidPolicy(blockDump, 19999))
        assertNull(Di5MobileDataGate.parseUidRules(blockDump, 19999))
    }

    @Test
    fun `the rules-only line at the end does not mask a missing policy`() {
        // No policy line anywhere — only the trailing "Active rules" entry for our uid.
        val rulesOnly = "Active rules:\n  UID=$uid: rules=4 (REJECT_METERED)\n"
        assertNull(Di5MobileDataGate.parseUidPolicy(rulesOnly, uid))
        assertEquals(4, Di5MobileDataGate.parseUidRules(rulesOnly, uid))
    }

    @Test
    fun `a similar but different uid is not matched`() {
        // 10042 is a prefix of 100429; the \b in the uid pattern must keep them apart.
        val prefixOnly = "UID=100429:\n  policy=9 (DIABLE_METERED)\n"
        assertNull(Di5MobileDataGate.parseUidPolicy(prefixOnly, uid))
        assertEquals(9, Di5MobileDataGate.parseUidPolicy(prefixOnly, 100429))

        val bothPresent = "UID=100429:\n  policy=9\nUID=$uid:\n  policy=4\n"
        assertEquals(4, Di5MobileDataGate.parseUidPolicy(bothPresent, uid))
        assertEquals(9, Di5MobileDataGate.parseUidPolicy(bothPresent, 100429))
    }

    @Test
    fun `one uid's policy is never read from another's block`() {
        // Our uid's block has no policy line; the next block's must not leak in.
        val dump = "UID=$uid:\n  state=4 (fg svc)\nUID=10100:\n  policy=0\n"
        assertNull(Di5MobileDataGate.parseUidPolicy(dump, uid))
    }

    @Test
    fun `a later section still yields the policy when the first occurrence has none`() {
        // First hit is the rules line, the real policy lives in a second section below.
        val dump = "Active rules:\n  UID=$uid: rules=4 (REJECT_METERED)\n" +
            "Policy-based rules:\n  UID=$uid:\n    policy=9 (DIABLE_METERED)\n"
        assertEquals(9, Di5MobileDataGate.parseUidPolicy(dump, uid))
    }

    // ── Gate decisions ──────────────────────────────────────────────────────────────────

    @Test
    fun `whitelist is needed for BYD's policy 9, a plain restrict, and no entry at all`() {
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(9))   // BYD bit 1+8
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(1))   // stock restrict-background
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(0))   // default, not whitelisted
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(null)) // absent from the dump
    }

    @Test
    fun `whitelist is not needed once the allow-metered policy is in place`() {
        assertFalse(Di5MobileDataGate.netPolicyNeedsWhitelist(Di5MobileDataGate.POLICY_ALLOW_METERED_BACKGROUND))
    }

    @Test
    fun `roaming is written when off or absent, never when already on`() {
        assertTrue(Di5MobileDataGate.shouldEnableDataRoaming(0))   // off
        assertTrue(Di5MobileDataGate.shouldEnableDataRoaming(-1))  // setting row absent
        assertFalse(Di5MobileDataGate.shouldEnableDataRoaming(1))  // already on
    }
}
