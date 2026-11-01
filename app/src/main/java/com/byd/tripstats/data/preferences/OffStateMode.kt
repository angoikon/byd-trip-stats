package com.byd.tripstats.data.preferences

enum class OffStateMode { ENABLED, DISABLED, DEEP_SLEEP }

/**
 * The mode the app runs in, given the one the user picked ([stored]).
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
fun effectiveOffStateMode(stored: OffStateMode, isDiLink5: Boolean): OffStateMode =
    if (isDiLink5) OffStateMode.ENABLED else stored
