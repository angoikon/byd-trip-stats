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

    /**
     * Appends, then drops from the front — the oldest goes first — until it fits [max].
     *
     * @return what was dropped, oldest first, so the caller can say so: a summary evicted here is
     * gone from every channel, and before this the queue gave no sign it had happened.
     */
    fun append(queue: MutableList<String>, payload: String, max: Int): List<String> {
        queue.add(payload)
        val dropped = mutableListOf<String>()
        while (queue.size > max) dropped += queue.removeAt(0)
        return dropped
    }

    /**
     * What one flush may send: the oldest queued event of each kind, plus every entry whose kind
     * can't be read ([kindOf] returns null), which the flush discards.
     *
     * The event topics are retained, so the last message to land on a topic is the one a
     * subscriber is left with. Sent all at once, an older `charge_session` that failed while a newer
     * one succeeded would be sent again on a later flush — and land last, leaving the older session
     * as the retained value. One per kind per flush keeps each topic in queue order: the next one
     * goes only after the previous has been acknowledged and removed. The same rule as the Telegram
     * outbox, and the cost is one flush per queued event of a kind, which the cap keeps small.
     */
    fun oldestOfEachKind(queue: List<String>, kindOf: (String) -> String?): List<String> {
        val seen = HashSet<String>()
        return queue.filter { entry ->
            val kind = kindOf(entry)
            kind == null || seen.add(kind)
        }
    }
}
