package com.byd.tripstats.data.notify

/**
 * One thing the car did that is worth telling the user about, in a form no delivery channel
 * owns: a [title], the [lines] beneath it, and what kind of event it was.
 *
 * Two channels render this today — the Telegram push ([telegramHtml]) and the web companion's
 * notification feed ([body], as JSON from `/api/notifications`) — and they must say the same
 * thing, which is why the wording lives here once rather than in each channel.
 */
data class VehicleEvent(
    val type: Type,
    val title: String,
    val lines: List<String>,
    /** Stable per source row, so a replayed close can't show up twice in the feed. */
    val key: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Type { TRIP, CHARGING, ALERT }

    /**
     * Telegram body: bold title, then the lines, sent with `parse_mode=HTML`. Escaping happens
     * here rather than in the builder — a `&lt;` baked into the text by a user's currency symbol
     * would otherwise leak, as literal markup, into every channel that isn't HTML.
     */
    val telegramHtml: String
        get() = (listOf("<b>${esc(title)}</b>") + lines.map(::esc)).joinToString("\n")
            .take(TelegramEventMessages.MAX_LENGTH)

    /** Plain text for channels without markup — the web companion feed. */
    val body: String get() = lines.joinToString("\n")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
