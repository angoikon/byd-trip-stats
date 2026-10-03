package com.byd.tripstats.server

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The web companion's logins, kept across app restarts.
 *
 * They used to live in memory only, so every restart of the app — an update, a reboot, the car
 * force-stopping it — logged out every device while the browser kept a cookie it believed was good
 * for 30 days. Over Tailscale that looked like a broken page rather than a PIN prompt (2026-10-03).
 *
 * Only a SHA-256 of each token is stored, never the token itself, with the time it was issued. A
 * login lasts [maxAgeMs], like the cookie, and only the newest [MAX_SESSIONS] are kept. The set is
 * tied to the PIN it was issued under: change the PIN and every device logs in again, as before.
 */
internal class WebSessionStore(
    private val storage: Storage,
    pin: String,
    private val maxAgeMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Storage {
        fun read(): Saved?
        fun write(saved: Saved)
    }

    /** What is persisted: a fingerprint of the PIN, and token hash → issue time (epoch ms). */
    data class Saved(val pinMark: String, val sessions: Map<String, Long>)

    private val pinMark = sha256("web-pin:$pin")
    private val sessions = ConcurrentHashMap<String, Long>()
    private val writeLock = Any()

    init {
        val saved = runCatching { storage.read() }.getOrNull()
        val samePin = saved?.pinMark == pinMark
        if (samePin) sessions.putAll(saved!!.sessions)
        if (prune() || !samePin) persist()
    }

    fun add(token: String) {
        sessions[sha256(token)] = now()
        prune()
        persist()
    }

    fun contains(token: String): Boolean {
        val key = sha256(token)
        val issued = sessions[key] ?: return false
        if (now() - issued < maxAgeMs) return true
        sessions.remove(key)
        persist()
        return false
    }

    fun remove(token: String) {
        if (sessions.remove(sha256(token)) != null) persist()
    }

    /** Drops expired logins and all but the newest [MAX_SESSIONS]; true when anything went. */
    private fun prune(): Boolean {
        val t = now()
        var changed = sessions.entries.removeIf { t - it.value >= maxAgeMs }
        val excess = sessions.size - MAX_SESSIONS
        if (excess > 0) {
            sessions.entries.sortedBy { it.value }.take(excess).forEach { sessions.remove(it.key) }
            changed = true
        }
        return changed
    }

    private fun persist() = synchronized(writeLock) {
        runCatching { storage.write(Saved(pinMark, HashMap(sessions))) }
    }

    companion object {
        const val MAX_SESSIONS = 32
        private const val PREFS = "web_sessions"
        private const val KEY_PIN = "pin_mark"
        private const val KEY_SESSIONS = "sessions"

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Stored as "hash:issuedMs" strings in their own preferences file — not in settings backups. */
        fun prefsStorage(context: Context): Storage {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return object : Storage {
                override fun read(): Saved? {
                    val mark = prefs.getString(KEY_PIN, null) ?: return null
                    val entries = prefs.getStringSet(KEY_SESSIONS, null).orEmpty().mapNotNull { e ->
                        val i = e.lastIndexOf(':')
                        if (i <= 0) null else e.substring(i + 1).toLongOrNull()?.let { e.substring(0, i) to it }
                    }.toMap()
                    return Saved(mark, entries)
                }

                override fun write(saved: Saved) {
                    prefs.edit()
                        .putString(KEY_PIN, saved.pinMark)
                        .putStringSet(KEY_SESSIONS, saved.sessions.map { (k, v) -> "$k:$v" }.toSet())
                        .apply()
                }
            }
        }
    }
}
