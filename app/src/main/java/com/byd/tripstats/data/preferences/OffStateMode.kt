package com.byd.tripstats.data.preferences

/**
 * What the app does while the car is switched off: [ENABLED] is Always On, [DEEP_SLEEP] stops the
 * service 5 min after switch-off.
 *
 * [DISABLED] was Minimal: Deep Sleep plus a 90-min alarm to look at charging. Removed in 2.21.0 —
 * on DiLink-3 that alarm can never fire while the car is off (BYD's AlarmManagerService drops every
 * app alarm while `sys.quickboot.enable=1`, bar a fixed list of system packages; armed 5×, fired
 * 0× on the dev Seal), so Minimal was Deep Sleep under another name. The value stays so that a stored
 * choice and an old settings backup still parse; [effectiveOffStateMode] runs it as Deep Sleep.
 */
enum class OffStateMode { ENABLED, DISABLED, DEEP_SLEEP }

/**
 * The mode the app runs in, given the one the user picked ([stored]).
 *
 * Minimal ([OffStateMode.DISABLED]) runs as Deep Sleep: it no longer exists (see [OffStateMode]),
 * and Deep Sleep is what it did on DiLink-3.
 *
 * DiLink-5 always runs Always On. There the car decides when the head unit sleeps: BYD's
 * CarPowerService has no sleep timer of its own and puts the unit to sleep on the car's command
 * (2–7 min after switch-off on the Sealion 7s measured), so stopping the service early saves
 * nothing, and the switch-off force-stop has the supervisor revive it in every mode anyway. And
 * DiLink-5 sends apps no ACC_ON, so a service that stopped itself stayed stopped through the next
 * drive: only opening the app, a reboot or Minimal's 90-min alarm brought it back, and then its
 * loop slept 5 min before looking again (a Sealion 7 on 2.18.0 missed three drives that way).
 * The stored choice is left as it was, unused.
 */
fun effectiveOffStateMode(stored: OffStateMode, isDiLink5: Boolean): OffStateMode = when {
    isDiLink5 -> OffStateMode.ENABLED
    stored == OffStateMode.DISABLED -> OffStateMode.DEEP_SLEEP
    else -> stored
}
