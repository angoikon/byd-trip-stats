package com.byd.tripstats.data.notify

/**
 * How a charging session came to be recorded, which is the difference between a measurement and
 * an inference and belongs beside the numbers rather than being left for a consumer to guess.
 *
 * Not every charge is watched. A charge taken while the car is off can be reconstructed rather
 * than recorded, depending on whether the head unit stays awake through it: some keep the app
 * running for the whole session, others force-stop it at ignition-off and only find the charge at
 * the next start. Which of the three a car gives most is a property of that car, not of the app.
 *
 * [LIVE] was watched from plug to unplug. [RECOVERED] was watched and then abandoned when the app
 * was killed mid-charge, so its samples are real but stop before the charge did; it is finalised
 * on the next start. [SOC_ESTIMATE] was never watched at all: the session is inferred from the
 * state of charge the app saw at shutdown against the one it finds at wake-up.
 */
enum class ChargeOrigin(val wireName: String) {
    LIVE("live"),
    RECOVERED("recovered"),
    SOC_ESTIMATE("soc_estimate"),
    ;

    /**
     * Whether this close is news. Only a charge that ended just now is: the other two are
     * finalised hours after the fact, at the next ignition, and a phone buzzing "charging
     * complete" about last night is a statement in the present tense that is no longer true.
     *
     * The machine-readable channel ignores this and takes all three, which is the one place the
     * channels disagree.
     */
    val announced: Boolean get() = this == LIVE
}
