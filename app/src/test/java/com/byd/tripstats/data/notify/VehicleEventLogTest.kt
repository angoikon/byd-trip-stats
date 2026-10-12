package com.byd.tripstats.data.notify

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the feed behind the web companion's bell: recording, deleting one entry, clearing the
 * lot, and a deleted entry staying deleted when the same close is replayed.
 *
 * Pure JVM: the log takes a [SharedPreferences], and [MemoryPrefs] is one that never leaves memory.
 */
class VehicleEventLogTest {

    /** Just the string reads and writes the log uses; anything else is a bug in the log. */
    private class MemoryPrefs : SharedPreferences {
        val data = mutableMapOf<String, String?>()

        override fun getString(key: String, defValue: String?): String? = data[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun commit(): Boolean {
                data.putAll(pending)
                return true
            }
            override fun apply() { commit() }
            override fun clear(): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putStringSet(key: String, values: MutableSet<String>?) = throw UnsupportedOperationException()
            override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
            override fun putLong(key: String, value: Long) = throw UnsupportedOperationException()
            override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
            override fun putBoolean(key: String, value: Boolean) = throw UnsupportedOperationException()
        }

        override fun getAll(): MutableMap<String, *> = throw UnsupportedOperationException()
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = throw UnsupportedOperationException()
        override fun getInt(key: String, defValue: Int) = throw UnsupportedOperationException()
        override fun getLong(key: String, defValue: Long) = throw UnsupportedOperationException()
        override fun getFloat(key: String, defValue: Float) = throw UnsupportedOperationException()
        override fun getBoolean(key: String, defValue: Boolean) = throw UnsupportedOperationException()
        override fun contains(key: String) = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
            throw UnsupportedOperationException()
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
            throw UnsupportedOperationException()
    }

    private val prefs = MemoryPrefs()
    private val log = VehicleEventLog(prefs)

    private fun event(key: String) = VehicleEvent(
        type = VehicleEvent.Type.TRIP,
        title = "Trip $key",
        lines = listOf("line"),
        key = key,
    )

    private fun keys(l: VehicleEventLog = log) = l.recent().map { it.key }

    @Test
    fun `recording keeps newest first and ignores a repeated key`() {
        log.record(event("trip-1"))
        log.record(event("trip-2"))
        log.record(event("trip-1"))

        assertEquals(listOf("trip-2", "trip-1"), keys())
    }

    @Test
    fun `remove deletes only that entry`() {
        log.record(event("trip-1"))
        log.record(event("trip-2"))
        log.record(event("trip-3"))

        assertTrue(log.remove("trip-2"))

        assertEquals(listOf("trip-3", "trip-1"), keys())
    }

    @Test
    fun `removing a key that is not in the feed changes nothing`() {
        log.record(event("trip-1"))

        assertFalse(log.remove("trip-9"))
        assertFalse(log.remove(""))

        assertEquals(listOf("trip-1"), keys())
    }

    @Test
    fun `a replayed close does not bring a deleted entry back`() {
        log.record(event("trip-1"))
        log.remove("trip-1")

        log.record(event("trip-1"))

        assertTrue(keys().isEmpty())
    }

    @Test
    fun `clear empties the feed, reports how many, and keeps the cleared ones gone`() {
        log.record(event("trip-1"))
        log.record(event("charge-2"))

        assertEquals(2, log.clear())
        assertTrue(keys().isEmpty())

        log.record(event("trip-1"))
        log.record(event("charge-2"))
        assertTrue("replays of cleared entries must stay gone", keys().isEmpty())

        log.record(event("trip-3"))
        assertEquals("a genuinely new event still arrives", listOf("trip-3"), keys())
    }

    @Test
    fun `clearing an empty feed is a no-op`() {
        assertEquals(0, log.clear())
        assertTrue(keys().isEmpty())
    }

    @Test
    fun `deletes survive an app restart`() {
        log.record(event("trip-1"))
        log.record(event("trip-2"))
        log.remove("trip-1")

        val restarted = VehicleEventLog(prefs)   // a new log over the same storage = a restart

        assertEquals(listOf("trip-2"), keys(restarted))
        restarted.record(event("trip-1"))
        assertEquals(listOf("trip-2"), keys(restarted))
    }

    @Test
    fun `the deleted-keys list is capped and forgets the oldest first`() {
        val total = VehicleEventLog.MAX_DISMISSED + 5
        repeat(total) { i ->
            log.record(event("trip-$i"))
            log.remove("trip-$i")
        }

        // The oldest tombstones have aged out, the newest are still held.
        log.record(event("trip-0"))
        log.record(event("trip-${total - 1}"))

        assertEquals(listOf("trip-0"), keys())
    }
}
