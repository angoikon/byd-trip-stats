package com.byd.tripstats.data.notify

/**
 * One thing the car did that is worth telling the user about, in a form no delivery channel
 * owns: a [title], the [lines] beneath it, and what kind of event it was.
 *
 * Three channels render this today — the Telegram push ([telegramHtml]), the web companion's
 * notification feed ([body]) and MQTT ([facts]) — and they must say the same thing, which is why
 * the wording lives here once rather than in each channel.
 */
data class VehicleEvent(
    val type: Type,
    val title: String,
    val lines: List<String>,
    /** Stable per source row, so a replayed close can't show up twice in the feed. */
    val key: String,
    /**
     * The same event as numbers rather than sentences, for channels read by a machine.
     *
     * Always canonical units — km, kWh, kWh/100 km, kW, percent, epoch millis — whatever the
     * display preference says, since a subscriber cannot see that preference. A value the car did
     * not report is absent rather than zero. Built beside [lines] so the two cannot drift.
     */
    val facts: Map<String, Any?> = emptyMap(),
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
