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
    fun `whitelist is needed only where BYD's bit is present`() {
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(9))   // DIABLE_METERED, bits 1+8 (#26, Shark 6)
        assertTrue(Di5MobileDataGate.netPolicyNeedsWhitelist(8))   // BYD's bit alone
    }

    @Test
    fun `an unblocked car is left alone`() {
        // Sealion 7, Australia (2026-10-13): no policy row, rules=0, MQTT publishing over 4G.
        assertFalse(Di5MobileDataGate.netPolicyNeedsWhitelist(null))
        assertFalse(Di5MobileDataGate.netPolicyNeedsWhitelist(0))
    }

    @Test
    fun `the user's own background-data restriction is not overridden`() {
        assertFalse(Di5MobileDataGate.netPolicyNeedsWhitelist(1))  // stock restrict-background only
    }

    @Test
    fun `whitelist is not needed once the allow-metered policy is in place`() {
        assertFalse(Di5MobileDataGate.netPolicyNeedsWhitelist(Di5MobileDataGate.POLICY_ALLOW_METERED_BACKGROUND))
    }

    @Test
    fun `a beta's roaming change is put back to the value it replaced`() {
        assertEquals(0, Di5MobileDataGate.roamingToRestore(saved = 0, current = 1))
    }

    @Test
    fun `a missing roaming row is restored as off`() {
        assertEquals(0, Di5MobileDataGate.roamingToRestore(saved = -1, current = 1))
    }

    @Test
    fun `roaming no beta changed is left alone`() {
        // Already on when a beta first looked (or set by hand, as on the Shark 6): nothing saved.
        assertNull(Di5MobileDataGate.roamingToRestore(saved = null, current = 1))
    }

    @Test
    fun `roaming changed again since a beta wrote it is left alone`() {
        assertNull(Di5MobileDataGate.roamingToRestore(saved = 0, current = 0))
    }

    // ── The blocked watch ───────────────────────────────────────────────────────────────

    @Test
    fun `with no lost lifts the block is lifted after a second`() {
        assertEquals(1_000L, Di5MobileDataGate.liftDelayMs(0))
    }

    @Test
    fun `lost lifts back off from 10 s, doubling to a 5-minute cap`() {
        assertEquals(10_000L, Di5MobileDataGate.liftDelayMs(1))
        assertEquals(20_000L, Di5MobileDataGate.liftDelayMs(2))
        assertEquals(40_000L, Di5MobileDataGate.liftDelayMs(3))
        assertEquals(160_000L, Di5MobileDataGate.liftDelayMs(5))
        assertEquals(300_000L, Di5MobileDataGate.liftDelayMs(6))
        assertEquals(300_000L, Di5MobileDataGate.liftDelayMs(50))
    }

    @Test
    fun `a lift the car undoes within 15 s counts as lost`() {
        assertEquals(1, Di5MobileDataGate.missesAfterHold(0, 3_000L))
        assertEquals(4, Di5MobileDataGate.missesAfterHold(3, Di5MobileDataGate.QUICK_REBLOCK_MS - 1))
    }

    @Test
    fun `a lift that holds resets the count - the German Sealion 7's 30-40 s keeps lifting at once`() {
        assertEquals(0, Di5MobileDataGate.missesAfterHold(4, Di5MobileDataGate.QUICK_REBLOCK_MS))
        assertEquals(0, Di5MobileDataGate.missesAfterHold(0, 35_000L))
    }

    @Test
    fun `lifts 1 to 5 are logged, then every 10th`() {
        val logged = (1..40).filter { Di5MobileDataGate.shouldLogLift(it) }
        assertEquals(listOf(1, 2, 3, 4, 5, 10, 20, 30, 40), logged)
        assertFalse(Di5MobileDataGate.shouldLogLift(0))
    }

    @Test
    fun `durations read as seconds, minutes or hours`() {
        assertEquals("41 s", Di5MobileDataGate.formatDuration(41_900L))
        assertEquals("119 s", Di5MobileDataGate.formatDuration(119_000L))
        assertEquals("15 min", Di5MobileDataGate.formatDuration(15 * 60_000L))
        assertEquals("3 h", Di5MobileDataGate.formatDuration(3 * 3_600_000L + 59 * 60_000L))
        assertEquals("0 s", Di5MobileDataGate.formatDuration(-5L))
    }

    @Test
    fun `the first lift of a run carries no note`() {
        assertEquals("", Di5MobileDataGate.liftNote(1, null, false, 0, 0L, 0L))
    }

    @Test
    fun `an early lift says how long the previous one held`() {
        assertEquals(
            " (lift 3, back 41 s after the previous one)",
            Di5MobileDataGate.liftNote(3, 41_000L, measured = true, count = 1, minHeldMs = 41_000L, maxHeldMs = 41_000L),
        )
    }

    @Test
    fun `a lift found by a check only bounds the hold`() {
        assertEquals(
            " (lift 2, back within 15 min of the previous one)",
            Di5MobileDataGate.liftNote(2, 15 * 60_000L, measured = false, count = 0, minHeldMs = 0L, maxHeldMs = 0L),
        )
    }

    @Test
    fun `every 10th lift summarises the holds since the last logged line`() {
        assertEquals(
            " (lift 20; the last 10 came back after 31–58 s)",
            Di5MobileDataGate.liftNote(20, 40_000L, measured = true, count = 10, minHeldMs = 31_000L, maxHeldMs = 58_000L),
        )
        assertEquals(
            " (lift 10; the last 5 came back after 40 s)",
            Di5MobileDataGate.liftNote(10, 40_000L, measured = true, count = 5, minHeldMs = 40_000L, maxHeldMs = 40_000L),
        )
        assertEquals(
            " (lift 30; the last 10 came back after 50 s–3 min)",
            Di5MobileDataGate.liftNote(30, 40_000L, measured = true, count = 10, minHeldMs = 50_000L, maxHeldMs = 200_000L),
        )
    }

    @Test
    fun `a car that never had the block shows nothing`() {
        assertEquals(
            Di5MobileDataGate.Status.NONE,
            Di5MobileDataGate.statusFor(blockSeen = false, lastCheckSawBydBit = true, blockedForMs = 600_000L, lastLiftFailed = true),
        )
    }

    @Test
    fun `the second between the car's block and the app's lift still reads unlocked`() {
        assertEquals(
            Di5MobileDataGate.Status.UNLOCKED,
            Di5MobileDataGate.statusFor(blockSeen = true, lastCheckSawBydBit = true, blockedForMs = 1_000L, lastLiftFailed = false),
        )
        assertEquals(
            Di5MobileDataGate.Status.UNLOCKED,
            Di5MobileDataGate.statusFor(blockSeen = true, lastCheckSawBydBit = true, blockedForMs = null, lastLiftFailed = false),
        )
    }

    @Test
    fun `a block that lasts a minute or a lift that failed reads blocked`() {
        assertEquals(
            Di5MobileDataGate.Status.BLOCKED,
            Di5MobileDataGate.statusFor(
                blockSeen = true, lastCheckSawBydBit = true,
                blockedForMs = Di5MobileDataGate.BLOCKED_STATUS_AFTER_MS, lastLiftFailed = false,
            ),
        )
        assertEquals(
            Di5MobileDataGate.Status.BLOCKED,
            Di5MobileDataGate.statusFor(blockSeen = true, lastCheckSawBydBit = true, blockedForMs = null, lastLiftFailed = true),
        )
    }

    @Test
    fun `a lasting block the latest dump can't pin on BYD is not called the car's`() {
        // Lifted (policy 4) yet still blocked: Data Saver or a power mode, not BYD's bit.
        assertEquals(
            Di5MobileDataGate.Status.UNLOCKED,
            Di5MobileDataGate.statusFor(blockSeen = true, lastCheckSawBydBit = false, blockedForMs = 600_000L, lastLiftFailed = false),
        )
    }
}
