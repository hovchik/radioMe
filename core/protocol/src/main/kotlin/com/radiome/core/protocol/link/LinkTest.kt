package com.radiome.core.protocol.link

import com.radiome.core.dsp.fec.ReedSolomon
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.modem.ModemProfile
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Two-station link test. The tester sends one short burst per profile, each holding [LinkTest.probeCount]
 * PROBE frames of known length at the minimum parity (20%), so the receiver can count lost frames and the bytes
 * Reed-Solomon had to fix. After the turnaround the receiver answers with one PROBE_REPORT frame and both sides
 * show the measurements and a suggested profile and parity.
 *
 * PROBE payload: `profile id u8 | index u8 | count u8 | filler`.
 * PROBE_REPORT payload: `version u8 (= 1) | entries u8 | entries x (profile id u8, sent u8, received u8,
 * repaired u8, corrected bytes u16, SNR in 0.5 dB steps i8, 127 = not heard)`.
 */
object LinkTest {
    /** Probe frames per profile: about 6-8 s of air each, about 25 s for the whole test. */
    fun probeCount(p: ModemProfile): Int = when (p) {
        ModemProfile.FAST -> 6
        ModemProfile.STANDARD -> 4
        ModemProfile.ROBUST -> 2
    }
    const val PROBE_PARITY = 0.20
    const val PAYLOAD = FrameCodec.DEFAULT_PAYLOAD
    private const val REPORT_VERSION = 1
    private const val SNR_UNKNOWN: Byte = 127

    /** Order of the probe bursts on air: fastest first, so a long ROBUST burst does not delay the others. */
    val order = listOf(ModemProfile.FAST, ModemProfile.STANDARD, ModemProfile.ROBUST)

    fun probeFrames(sessionId: Int, profile: ModemProfile, count: Int = probeCount(profile)): List<Frame> = List(count) { i ->
        val filler = Random(sessionId * 31 + profile.id * 7 + i).nextBytes(PAYLOAD - 3)
        Frame(FrameType.PROBE, sessionId, i, count, byteArrayOf(profile.id.toByte(), i.toByte(), count.toByte()) + filler)
    }

    fun encodeReport(report: LinkTestReport): ByteArray {
        val out = ByteArray(2 + report.results.size * 7)
        out[0] = REPORT_VERSION.toByte()
        out[1] = report.results.size.toByte()
        report.results.forEachIndexed { n, r ->
            val at = 2 + n * 7
            out[at] = r.profile.id.toByte()
            out[at + 1] = r.sent.coerceIn(0, 255).toByte()
            out[at + 2] = r.received.coerceIn(0, 255).toByte()
            out[at + 3] = r.repaired.coerceIn(0, 255).toByte()
            val c = r.correctedBytes.coerceIn(0, 0xFFFF)
            out[at + 4] = (c ushr 8).toByte()
            out[at + 5] = c.toByte()
            out[at + 6] = if (r.snrDb.isNaN()) SNR_UNKNOWN else (r.snrDb * 2).roundToInt().coerceIn(-128, 126).toByte()
        }
        return out
    }

    fun decodeReport(b: ByteArray): LinkTestReport? {
        if (b.size < 2 || b[0].toInt() != REPORT_VERSION) return null
        val n = b[1].toInt() and 0xFF
        if (b.size < 2 + n * 7) return null
        val results = (0 until n).mapNotNull { k ->
            val at = 2 + k * 7
            val profile = ModemProfile.fromId(b[at].toInt() and 0xFF) ?: return@mapNotNull null
            val snr = b[at + 6]
            ProbeResult(
                profile = profile,
                sent = b[at + 1].toInt() and 0xFF,
                received = b[at + 2].toInt() and 0xFF,
                repaired = b[at + 3].toInt() and 0xFF,
                correctedBytes = ((b[at + 4].toInt() and 0xFF) shl 8) or (b[at + 5].toInt() and 0xFF),
                snrDb = if (snr == SNR_UNKNOWN) Double.NaN else snr / 2.0,
            )
        }
        return LinkTestReport(results)
    }

    /** Lowest SNR (dB, 2500 Hz) at which a profile is suggested; from the measured margins in docs/PROTOCOL.md. */
    fun minSnrDb(p: ModemProfile): Double = when (p) {
        ModemProfile.FAST -> 6.0
        ModemProfile.STANDARD -> 0.0
        ModemProfile.ROBUST -> Double.NEGATIVE_INFINITY
    }
}

/** What the receiver measured for one profile's probe burst. */
data class ProbeResult(
    val profile: ModemProfile,
    val sent: Int,
    val received: Int,
    val repaired: Int,
    val correctedBytes: Int,
    /** NaN when the burst was not heard at all. */
    val snrDb: Double,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)
    val heard: Boolean get() = received > 0 || !snrDb.isNaN()
    /** Average bytes Reed-Solomon fixed per received frame. */
    val errorsPerFrame: Double get() = if (received == 0) Double.NaN else correctedBytes.toDouble() / received
}

data class LinkRecommendation(val profile: ModemProfile, val parityPercent: Int, val reason: String)

data class LinkTestReport(val results: List<ProbeResult>) {
    fun result(p: ModemProfile): ProbeResult? = results.firstOrNull { it.profile == p }

    /** Best SNR any probe burst was heard with. */
    val snrDb: Double get() = results.map { it.snrDb }.filterNot { it.isNaN() }.maxOrNull() ?: Double.NaN

    /**
     * The fastest profile whose probes all arrived at a usable SNR, with enough parity for twice the error rate
     * seen (plus one byte), rounded up to 5% and kept within 20..50%. Falls back to Robust at 50%.
     */
    fun recommend(): LinkRecommendation {
        val k = FrameCodec.frameLength(LinkTest.PAYLOAD)
        for (p in LinkTest.order) {
            val r = result(p) ?: continue
            if (r.received == 0 || r.lost > 0 || r.snrDb < LinkTest.minSnrDb(p)) continue
            val parityBytes = 2 * ceil(2 * r.errorsPerFrame + 1)
            val percent = roundUpTo5(parityBytes * 100 / k).coerceIn(MIN_PARITY, MAX_PARITY)
            if (parityBytes / k > MAX_PARITY / 100.0) continue
            val why = if (r.correctedBytes == 0) "every probe arrived clean" else
                "every probe arrived; FEC fixed %.1f bytes per frame".format(r.errorsPerFrame)
            return LinkRecommendation(p, percent, "${label(p)}: $why at %.1f dB SNR".format(r.snrDb))
        }
        val robust = result(ModemProfile.ROBUST)
        val reason = when {
            results.none { it.heard } -> "Nothing was heard. Check volume, VOX and that both radios are on the same channel."
            robust == null || robust.received == 0 -> "Only parts of the test arrived. The link is marginal; use Robust with full parity."
            else -> "Faster profiles lost frames. Use Robust with full parity."
        }
        return LinkRecommendation(ModemProfile.ROBUST, MAX_PARITY, reason)
    }

    private fun roundUpTo5(v: Double): Int = (ceil(v / 5.0) * 5).toInt()

    private fun label(p: ModemProfile) = p.name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        const val MIN_PARITY = 20
        const val MAX_PARITY = 50
    }
}

/** Receiver-side accumulation of one link test's probe bursts. */
internal class ProbeCollector {
    private class Acc(var sent: Int, var received: Int = 0, var repaired: Int = 0, var corrected: Int = 0, var snr: Double = Double.NaN)

    private val acc = LinkedHashMap<ModemProfile, Acc>()

    fun add(profile: ModemProfile, frame: Frame, status: CodewordStatus, correctedBytes: Int, snrDb: Double) {
        val a = acc.getOrPut(profile) { Acc(LinkTest.probeCount(profile)) }
        if (frame.payload.size >= 3) a.sent = frame.payload[2].toInt() and 0xFF
        a.received++
        if (status == CodewordStatus.FEC_REPAIRED) a.repaired++
        a.corrected += correctedBytes
        a.snr = snrDb
    }

    fun report(): LinkTestReport = LinkTestReport(
        LinkTest.order.map { p ->
            val a = acc[p]
            if (a == null) ProbeResult(p, LinkTest.probeCount(p), 0, 0, 0, Double.NaN)
            // SNR in the 0.5 dB steps the report carries, so both stations show the same numbers.
            else ProbeResult(p, a.sent, minOf(a.received, a.sent), a.repaired, a.corrected, (a.snr * 2).roundToInt() / 2.0)
        },
    )
}

/** Parity bytes the probes use, exposed for tests. */
internal fun probeParityBytes(): Int = ReedSolomon.parityFor(FrameCodec.frameLength(LinkTest.PAYLOAD), LinkTest.PROBE_PARITY)
