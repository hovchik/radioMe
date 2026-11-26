package com.radiome.core.protocol.arq

import com.radiome.core.dsp.modem.ModemProfile

/** Half-duplex selective-repeat ARQ settings. All times are measured on the audio clock (input samples). */
data class ArqConfig(
    /** Receiver sends a NACK on its own after the sender's over ends. Off = the UI shows "Send correction request". */
    val autoNack: Boolean = true,
    /** Quiet time after the sender's last burst before the receiver keys up (radio turnaround). */
    val turnaroundMs: Int = 2000,
    /** Quiet time a sender waits for a NACK/ACK before polling with its HEADER frame. */
    val responseTimeoutMs: Int = 6000,
    /** NACK rounds a sender will serve before giving up. */
    val maxRepairRounds: Int = 5,
    /** Header polls after a silent response window before giving up. */
    val maxPolls: Int = 2,
    /** Send repair bursts with the ROBUST profile regardless of the original profile. */
    val stepDownToRobust: Boolean = true,
    /** Receiver confirms a complete transfer with an ACK burst. */
    val sendAck: Boolean = true,
    /** Profile used for NACK, ACK and header polls. */
    val controlProfile: ModemProfile = ModemProfile.ROBUST,
) {
    init {
        require(turnaroundMs in 0..10_000)
        require(responseTimeoutMs in 1_000..60_000)
        require(maxRepairRounds in 0..50 && maxPolls in 0..10)
    }
}
