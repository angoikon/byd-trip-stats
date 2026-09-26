package com.byd.tripstats.data.notify

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.analysis.TripReport
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem

/**
 * Where the app decides that something happened, and what it is called.
 *
 * The repositories and monitors call the `onX` methods here; this class builds the
 * [VehicleEvent] once and hands it to every channel: the [VehicleEventLog] that the web
 * companion's notification feed reads, and [TelegramNotifier] for the push. Adding a channel
 * means touching this file, not the recording code.
 *
 * The *semantics* of an event live here (is this close live? did the charge add anything? is
 * this user entitled to the alert?); the *delivery* rules — is a bot linked, is that push
 * switched on, retry on failure — belong to the channel.
 */
class VehicleEvents private constructor(private val context: Context) {

    private val appPrefs = PreferencesManager(context)
    private val log = VehicleEventLog.getInstance(context)
    private val telegram = TelegramNotifier.getInstance(context)

    /**
     * A state of charge in the source the user reads everywhere else in the app — the instrument
     * panel by default (Settings → Preferences → SoC source), with the BMS value as the fallback
     * when the panel figure is missing, which it is on firmwares that don't report it and on rows
     * recorded before it was stored. A stored 0 means "never set".
     */
    private fun preferredSoc(panel: Double?, bms: Double?): Double? =
        if (appPrefs.getCachedSocSource() == SocSource.PANEL) {
            panel?.takeIf { it > 0.0 } ?: bms
        } else {
            bms ?: panel?.takeIf { it > 0.0 }
        }

    /**
     * Trip summary, fired from `TripRepository.doEndTrip` once the trip row is final and its
     * stats exist. Every value is read back from the stored row — nothing is recomputed here — so
     * the card matches the trip's own screen: [energyRatePerKwh] is the FIFO cost-basis rate from
     * [com.byd.tripstats.data.analysis.CostAttribution] that the app prices trips with (NOT the
     * flat tariff, which would disagree with the app whenever a charge carried its own price),
     * and [fleetAvgEfficiency] is the lifetime average in kWh/100 km, already including this trip
     * since the row is written before the average is queried.
     *
     * **Live closes only**, decided by [liveClose] — the caller's own close path, not a guess
     * from timestamps. A trip is not always closed when the driving stops: the cold-start-recovery
     * path finalises it on the *next* start, and on DiLink-5 that is the normal path, since the
     * OEM force-stops the app at ignition-off. Announcing it anyway would buzz the user's phone
     * with "Trip finished" at the moment they switch the car back on, about the drive before.
     *
     * Note what this does NOT gate on: how long after parking the trip ended. The car-off timeout
     * is user-configurable, so someone who sets 45 minutes has *defined* their trip as ending 45
     * minutes after they park, and the summary correctly arrives then.
     */
    fun onTripFinished(
        trip: TripEntity,
        stats: TripStatsEntity?,
        fleetAvgEfficiency: Double?,
        energyRatePerKwh: Double?,
        liveClose: Boolean,
    ) {
        val distance = trip.distance ?: return
        if (distance < MIN_TRIP_DISTANCE_KM) return
        if (!liveClose) {
            Log.i(TAG, "Trip ${trip.id} closed from a reconstructed state — not announced")
            return
        }
        // Backstop only, for a close that is live by path but ancient by clock (a frozen service
        // coming back, a back-dated override). Never the mechanism above.
        val endedAgoMs = System.currentTimeMillis() - (trip.endTime ?: return)
        if (endedAgoMs > TRIP_MAX_AGE_MS) {
            Log.i(TAG, "Trip ${trip.id} ended ${endedAgoMs / 3_600_000} h ago — not announced")
            return
        }
        val avgSpeed = stats?.avgSpeed?.takeIf { it > 0.0 }
        publish(
            TelegramEventMessages.tripSummary(
                tripId = trip.id,
                distanceKm = distance,
                durationMs = trip.duration,
                energyKwh = trip.energyConsumed,
                efficiencyKwh100km = trip.efficiency,
                fleetAvgKwh100km = fleetAvgEfficiency,
                startSoc = preferredSoc(trip.startSocPanel, trip.startSoc) ?: trip.startSoc,
                endSoc = preferredSoc(trip.endSocPanel, trip.endSoc),
                avgSpeedKmh = avgSpeed,
                tripScore = TripReport.tripScore(trip, avgSpeed),
                energyRatePerKwh = energyRatePerKwh,
                currencySymbol = appPrefs.getCachedCurrencySymbol(),
                imperial = appPrefs.getCachedUnitSystem() == UnitSystem.IMPERIAL,
                startTimeMs = trip.startTime,
                endTimeMs = trip.endTime,
                socFromBms = appPrefs.getCachedSocSource() == SocSource.BMS,
            ),
            pushEnabled = telegram.tripSummaryEnabled.value,
        )
    }

    /**
     * A charging session that has just closed, fired from `ChargingRepository` on the live close
     * path only — the orphan-recovery close finalises a session that ended while the app was
     * dead, and "charging complete" delivered the next morning is a statement about the present
     * tense that isn't true any more.
     *
     * [ratePerKwh] is resolved the charging screen's way (the session's own price, else the
     * global tariff, else null) so the cost line matches the session's own screen.
     *
     * Sessions that added almost nothing are dropped: the BMS can flap in and out of charging on
     * a handshake, and each flap closes a row.
     */
    fun onChargingSessionClosed(session: ChargingSessionEntity, ratePerKwh: Double?) {
        val socEnd = preferredSoc(session.socEndPanel, session.socEnd) ?: return
        if ((session.kwhAdded ?: 0.0) < MIN_CHARGE_KWH) return
        publish(
            TelegramEventMessages.chargingFinished(
                sessionId = session.id,
                socEnd = socEnd,
                socStart = preferredSoc(session.socStartPanel, session.socStart) ?: session.socStart,
                kwhAdded = session.kwhAdded,
                durationMs = session.endTime?.let { it - session.startTime },
                avgKw = session.avgKw,
                peakKw = session.peakKw,
                ratePerKwh = ratePerKwh,
                currencySymbol = appPrefs.getCachedCurrencySymbol(),
                socFromBms = appPrefs.getCachedSocSource() == SocSource.BMS,
                startTimeMs = session.startTime,
                endTimeMs = session.endTime,
            ),
            pushEnabled = telegram.chargingFinishedEnabled.value,
        )
    }

    /**
     * The Pro cell-imbalance alert, already debounced by its evaluator — which also gates it on
     * Pro. The entitlement is re-checked here so neither channel can outlive that gate if the
     * alert ever gains another caller.
     */
    fun onCellImbalance(spreadV: Double, thresholdV: Double, soc: Double) {
        if (!EntitlementManager.isProNow()) return
        publish(
            TelegramEventMessages.cellImbalance(spreadV, thresholdV, soc),
            pushEnabled = telegram.cellImbalanceEnabled.value,
        )
    }

    /**
     * Records the event for every channel. The log always gets it — the companion's feed is the
     * car's own history, not a mirror of what a bot was configured to forward — while the push
     * additionally honours its per-event switch.
     */
    private fun publish(event: VehicleEvent, pushEnabled: Boolean) {
        log.record(event)
        if (pushEnabled) telegram.send(event)
    }

    companion object {
        private const val TAG = "VehicleEvents"

        /** Shorter drives are parking manoeuvres, not trips worth announcing. */
        private const val MIN_TRIP_DISTANCE_KM = 0.5

        /** Below this a "session" is a charger handshake flap, not a charge. */
        private const val MIN_CHARGE_KWH = 0.5

        /** A live-path close whose trip nonetheless ended this long ago is history, not news. */
        private const val TRIP_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        @Volatile private var INSTANCE: VehicleEvents? = null

        fun getInstance(context: Context): VehicleEvents =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleEvents(context.applicationContext).also { INSTANCE = it }
            }
    }
}
