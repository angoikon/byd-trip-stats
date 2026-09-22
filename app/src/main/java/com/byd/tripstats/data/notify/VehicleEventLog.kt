package com.byd.tripstats.data.notify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last [MAX_EVENTS] vehicle events, kept so a channel that isn't a push can still show them:
 * the web companion reads this through `/api/notifications` and renders a feed.
 *
 * This is deliberately **not** gated on the Telegram switches. Those decide whether a push goes
 * out to a phone; the feed is a passive list the user opens, and hiding a trip that happened
 * because a bot toggle is off would make the companion lie about the car's history.
 *
 * SharedPreferences rather than Room: a ring of a few dozen short strings that nothing queries,
 * joins or migrates. Putting it in the database would mean an entity, a DAO and a migration —
 * and on this app a schema change is the single most dangerous kind of change there is (Room's
 * identity-hash trap wedges already-deployed installs), which is a steep price for a scratch pad.
 */
class VehicleEventLog private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Appends [event], dropping the oldest once the ring is full. Duplicate keys are ignored. */
    @Synchronized
    fun record(event: VehicleEvent) {
        val existing = recent()
        if (existing.any { it.key == event.key }) return
        val updated = (listOf(event) + existing).take(MAX_EVENTS)
        write(updated)
    }

    /** Newest first. */
    @Synchronized
    fun recent(limit: Int = MAX_EVENTS): List<VehicleEvent> = try {
        val raw = prefs.getString(KEY_EVENTS, null)
        if (raw.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val lines = o.optJSONArray("lines")
                VehicleEvent(
                    type = runCatching { VehicleEvent.Type.valueOf(o.optString("type")) }
                        .getOrDefault(VehicleEvent.Type.ALERT),
                    title = o.optString("title"),
                    lines = (0 until (lines?.length() ?: 0)).map { lines!!.getString(it) },
                    key = o.optString("key"),
                    timestamp = o.optLong("ts"),
                )
            }.take(limit)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Event log unreadable, dropping: ${e.message}")
        emptyList()
    }

    @Synchronized
    fun clear() = prefs.edit().remove(KEY_EVENTS).apply()

    private fun write(events: List<VehicleEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("type", e.type.name)
                put("title", e.title)
                put("lines", JSONArray().apply { e.lines.forEach { put(it) } })
                put("key", e.key)
                put("ts", e.timestamp)
            })
        }
        // commit(), not apply(): these are written as the car is parking or powering down,
        // which is exactly when an un-flushed async write is lost.
        prefs.edit().putString(KEY_EVENTS, arr.toString()).commit()
    }

    companion object {
        private const val TAG = "VehicleEventLog"
        private const val PREFS_NAME = "vehicle_event_log"
        private const val KEY_EVENTS = "events"

        /** Enough for the companion's feed to cover a few weeks of ordinary use. */
        const val MAX_EVENTS = 50

        @Volatile private var INSTANCE: VehicleEventLog? = null

        fun getInstance(context: Context): VehicleEventLog =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleEventLog(context.applicationContext).also { INSTANCE = it }
            }
    }
}
