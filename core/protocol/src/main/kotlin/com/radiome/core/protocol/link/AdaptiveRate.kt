package com.radiome.core.protocol.link

import com.radiome.core.dsp.modem.ModemProfile

/**
 * Picks the profile for the next transfer from how the last ones went ("Auto" speed).
 *
 * - A failed transfer, or more than [stepDownLossPercent] of chunks lost in the first over: one step slower.
 * - [stepUpAfter] transfers in a row with no loss: one step faster.
 * - Anything in between keeps the current profile.
 *
 * Only first-over loss counts: repairs go out in Robust anyway and say little about the original profile.
 */
class AdaptiveRate(
    start: ModemProfile = ModemProfile.STANDARD,
    private val stepDownLossPercent: Double = 15.0,
    private val stepUpAfter: Int = 2,
) {
    var current: ModemProfile = start
        private set
    private var cleanRuns = 0

    /** Order from slowest to fastest. */
    private val ladder = listOf(ModemProfile.ROBUST, ModemProfile.STANDARD, ModemProfile.FAST)

    /** A transfer sent with [profile] ended. [firstOverLossPercent] is the share of chunks the receiver first asked for. */
    fun onOutcome(profile: ModemProfile, firstOverLossPercent: Double, failed: Boolean): ModemProfile {
        // An outcome for a profile we already left says nothing about the current one, except a failure.
        if (profile != current && !failed) return current
        when {
            failed || firstOverLossPercent > stepDownLossPercent -> {
                current = step(-1)
                cleanRuns = 0
            }
            firstOverLossPercent <= 0.0 -> {
                cleanRuns++
                if (cleanRuns >= stepUpAfter) {
                    current = step(+1)
                    cleanRuns = 0
                }
            }
            else -> cleanRuns = 0
        }
        return current
    }

    /** Start from a measured value, for example a link test's suggestion. */
    fun reset(profile: ModemProfile) {
        current = profile
        cleanRuns = 0
    }

    private fun step(d: Int): ModemProfile = ladder[(ladder.indexOf(current) + d).coerceIn(0, ladder.lastIndex)]
}
