package com.byd.tripstats.connections

import org.json.JSONArray

/**
 * The rules of the persisted event queue, apart from the preferences file it lives in so they can
 * be tested: how it is stored, what a damaged copy turns into, how it is bounded, and how one
 * entry leaves it. [MqttConnectionStore] adds the lock and the disk.
 */
internal object PendingEventQueue {

    /** A stored queue that will not parse is discarded: an unsendable event is not worth a loop. */
    fun parse(stored: String?): MutableList<String> {
        if (stored.isNullOrBlank()) return mutableListOf()
        return try {
            val array = JSONArray(stored)
            MutableList(array.length()) { array.getString(it) }
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    /** Null for an empty queue, so the caller removes the key instead of storing "[]". */
    fun encode(queue: List<String>): String? =
        if (queue.isEmpty()) null else JSONArray(queue).toString()

    /** Appends, then drops from the front — the oldest goes first — until it fits [max]. */
    fun append(queue: MutableList<String>, payload: String, max: Int) {
        queue.add(payload)
        while (queue.size > max) queue.removeAt(0)
    }
}
