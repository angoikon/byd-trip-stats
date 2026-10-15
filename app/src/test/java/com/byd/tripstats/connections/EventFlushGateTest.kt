package com.byd.tripstats.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that keep the MQTT event flush from either duplicating an event or going quiet:
 * one flush at a time, a flush that never settles being abandoned, and a refusing broker being
 * retried with a back-off rather than on every state tick.
 */
class EventFlushGateTest {

    private fun gate() = EventFlushGate(staleAfterMs = 30_000, minBackoffMs = 5_000, maxBackoffMs = 60_000)

    @Test
    fun `a second flush cannot start while the first is still settling`() {
        val gate = gate()
        assertNotNull(gate.tryStart(0))
        assertNull(gate.tryStart(1_000))
    }

    @Test
    fun `a flush that sent everything reopens the gate straight away`() {
        val gate = gate()
        val token = gate.tryStart(0)!!
        gate.finish(token, allSent = true, nowMs = 100)
        assertNotNull(gate.tryStart(200))
    }

    @Test
    fun `a flush that never settles is abandoned, so events cannot stop for good`() {
        val gate = gate()
        gate.tryStart(0)
        assertNull("still within the window", gate.tryStart(29_999))
        assertNotNull("past the window", gate.tryStart(30_000))
    }

    @Test
    fun `an abandoned flush settling late does not reopen the gate under its replacement`() {
        val gate = gate()
        val hung = gate.tryStart(0)!!
        val replacement = gate.tryStart(40_000)!!
        gate.finish(hung, allSent = true, nowMs = 41_000)
        assertNull("the replacement is still running", gate.tryStart(41_500))
        gate.finish(replacement, allSent = true, nowMs = 42_000)
        assertNotNull(gate.tryStart(42_500))
    }

    @Test
    fun `a failed flush waits before the next attempt`() {
        val gate = gate()
        gate.finish(gate.tryStart(0)!!, allSent = false, nowMs = 0)
        assertNull(gate.tryStart(4_999))
        assertNotNull(gate.tryStart(5_000))
    }

    @Test
    fun `the wait doubles on repeated failures and stops at the cap`() {
        val gate = gate()
        var now = 0L
        for (wait in listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)) {
            gate.finish(gate.tryStart(now)!!, allSent = false, nowMs = now)
            assertNull("still waiting at ${wait - 1} ms", gate.tryStart(now + wait - 1))
            now += wait
        }
    }

    @Test
    fun `a flush that sends everything clears the back-off`() {
        val gate = gate()
        gate.finish(gate.tryStart(0)!!, allSent = false, nowMs = 0)
        gate.finish(gate.tryStart(5_000)!!, allSent = true, nowMs = 5_000)
        val next = gate.tryStart(5_001)
        assertNotNull("no wait after a clean flush", next)
        gate.finish(next!!, allSent = false, nowMs = 5_001)
        assertNull("back to the shortest wait, not a longer one", gate.tryStart(5_001 + 4_999))
        assertNotNull(gate.tryStart(5_001 + 5_000))
    }

    @Test
    fun `an abandoned flush counts as a failure for the back-off`() {
        val gate = gate()
        gate.tryStart(0)
        // The hang is the first failure; the replacement failing as well makes it the second,
        // so the wait is 10 s and not the 5 s a single failure would give.
        val replacement = gate.tryStart(30_000)!!
        gate.finish(replacement, allSent = false, nowMs = 30_100)
        assertNull(gate.tryStart(40_099))
        assertNotNull(gate.tryStart(40_100))
    }
}

/** The queue's storage rules, apart from the preferences file they are kept in. */
class PendingEventQueueTest {

    @Test
    fun `a missing, blank or damaged stored queue reads as empty`() {
        assertEquals(emptyList<String>(), PendingEventQueue.parse(null))
        assertEquals(emptyList<String>(), PendingEventQueue.parse(""))
        assertEquals(emptyList<String>(), PendingEventQueue.parse("not json"))
        assertEquals(emptyList<String>(), PendingEventQueue.parse("""{"a":1}"""))
    }

    @Test
    fun `what is stored reads back unchanged and in order`() {
        val queue = listOf("""{"kind":"trip","payload":{"trip_id":1}}""", "second", "third")
        assertEquals(queue, PendingEventQueue.parse(PendingEventQueue.encode(queue)))
    }

    @Test
    fun `an empty queue is stored as nothing, not as an empty array`() {
        assertNull(PendingEventQueue.encode(emptyList()))
    }

    @Test
    fun `the oldest events go first once the queue is full`() {
        val queue = mutableListOf<String>()
        for (i in 1..5) PendingEventQueue.append(queue, "e$i", max = 3)
        assertEquals(listOf("e3", "e4", "e5"), queue)
    }

    @Test
    fun `what the cap drops is reported, oldest first`() {
        val queue = mutableListOf("e1", "e2", "e3")
        assertEquals(emptyList<String>(), PendingEventQueue.append(mutableListOf("e1"), "e2", max = 3))
        assertEquals(listOf("e1"), PendingEventQueue.append(queue, "e4", max = 3))
        assertEquals(listOf("e2", "e3", "e4"), queue)
    }

    // ── One per kind per flush ──────────────────────────────────────────────────────────

    private fun kindOf(entry: String): String? = entry.substringBefore(':', "").takeIf { it.isNotEmpty() }

    @Test
    fun `a flush sends only the oldest event of each kind`() {
        // A trip, a recovered charge and a reconstructed charge, queued at one DiLink-5 cold start.
        val queue = listOf("trip:1", "charge_session:2", "charge_session:3", "trip:4")
        assertEquals(listOf("trip:1", "charge_session:2"), PendingEventQueue.oldestOfEachKind(queue, ::kindOf))
    }

    @Test
    fun `the next of a kind goes only once the older one has left the queue`() {
        // charge_session:2 was acknowledged and removed; 3 is now the oldest of its kind.
        val queue = listOf("trip:1", "charge_session:3")
        assertEquals(queue, PendingEventQueue.oldestOfEachKind(queue, ::kindOf))
    }

    @Test
    fun `unreadable entries are all handed to the flush, which discards them`() {
        val queue = listOf("garbage", "trip:1", "more garbage", "trip:2")
        assertEquals(listOf("garbage", "trip:1", "more garbage"), PendingEventQueue.oldestOfEachKind(queue, ::kindOf))
    }

    @Test
    fun `the envelope's kind is read from the stored JSON`() {
        assertEquals("charge_session", MqttConnectionStore.eventKindOf("""{"kind":"charge_session","payload":{}}"""))
        assertNull(MqttConnectionStore.eventKindOf("not json"))
    }
}
