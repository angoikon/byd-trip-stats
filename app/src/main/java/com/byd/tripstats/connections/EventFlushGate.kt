package com.byd.tripstats.connections

/**
 * Decides when the MQTT event queue may be flushed, and keeps one flush at a time.
 *
 * A queued trip or charge summary stays on disk until the broker acknowledges it, and a send
 * settles after the flush that started it has returned. So two things have to be true for that
 * not to misbehave:
 *
 *  - **One flush at a time**, or the next state tick re-sends an event whose send is still settling.
 *  - **A flush that never settles cannot close the gate for ever.** After [staleAfterMs] a new flush
 *    may start regardless; the late completion of the abandoned one is recognised by its token
 *    and ignored, so it can't reopen the gate under the flush that replaced it. The cost of
 *    abandoning is a possible repeat of an event, which the payload's ids let a consumer drop.
 *
 * And a broker that keeps refusing the topics must not be hammered on every state tick, which can
 * be once a second: after a flush in which any send failed, the next attempt waits [backoffMs],
 * doubling from [minBackoffMs] up to [maxBackoffMs], and a flush that sends everything resets it.
 *
 * Time comes in as a parameter so the rules can be tested without waiting.
 */
class EventFlushGate(
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val minBackoffMs: Long = MIN_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private val lock = Any()
    private var nextToken = 0L
    private var activeToken = IDLE
    private var activeSinceMs = 0L
    private var notBeforeMs = 0L
    private var backoffMs = 0L

    /** A token for the flush that may now start, or null when none may. Pass it to [finish]. */
    fun tryStart(nowMs: Long): Long? = synchronized(lock) {
        if (nowMs < notBeforeMs) return null
        if (activeToken != IDLE && nowMs - activeSinceMs < staleAfterMs) return null
        // A flush still marked active here is past [staleAfterMs]: it hung, which counts as a failure.
        if (activeToken != IDLE) failed(nowMs)
        nextToken += 1
        activeToken = nextToken
        activeSinceMs = nowMs
        activeToken
    }

    /**
     * Ends the flush that [token] was issued for. [allSent] is false when any send failed or the
     * flush had to give up early. A token that has been superseded is ignored.
     */
    fun finish(token: Long, allSent: Boolean, nowMs: Long) = synchronized(lock) {
        if (token != activeToken) return@synchronized
        activeToken = IDLE
        if (allSent) {
            backoffMs = 0L
            notBeforeMs = 0L
        } else {
            failed(nowMs)
        }
    }

    private fun failed(nowMs: Long) {
        backoffMs = if (backoffMs == 0L) minBackoffMs else minOf(backoffMs * 2, maxBackoffMs)
        notBeforeMs = nowMs + backoffMs
    }

    companion object {
        private const val IDLE = 0L

        /** Longer than the state publish waits for its own ack, so a slow broker is not abandoned. */
        const val STALE_AFTER_MS = 30_000L
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
