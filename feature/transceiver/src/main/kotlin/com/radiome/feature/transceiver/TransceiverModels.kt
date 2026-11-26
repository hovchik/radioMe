package com.radiome.feature.transceiver

import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.RxState
import com.radiome.core.protocol.arq.ReceiverPhase
import com.radiome.core.protocol.arq.SenderPhase
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.ImagePreview
import com.radiome.core.protocol.link.LinkRecommendation
import com.radiome.core.protocol.link.LinkTestReport
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Deflate
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.core.audio.VolumeAdvice
import com.radiome.core.audio.volumeAdvice
import com.radiome.feature.transceiver.link.OutputStatus
import com.radiome.feature.transceiver.link.RouteInfo
import kotlin.math.roundToInt

// Plain models and the pure functions that build them, kept free of Android so they are unit tested on the JVM.

enum class InputLevel { SILENT, LOW, GOOD, HOT }

/** Receive dashboard. */
data class RadioUi(
    val running: Boolean = false,
    val linkState: LinkState = LinkState.LISTENING,
    val route: RouteInfo? = null,
    val levelDbfs: Double = Double.NEGATIVE_INFINITY,
    val inputLevel: InputLevel = InputLevel.SILENT,
    val snrDb: Double = Double.NaN,
    val rxState: RxState = RxState.LISTENING,
    val syncCorrelation: Double = 0.0,
    val burstProgress: Float = 0f,
    val erasedSymbols: Int = 0,
    val volumeAdvice: VolumeAdvice? = null,
    val outputMuted: Boolean = false,
    val outputDevice: String? = null,
    val transfers: List<TransferCard> = emptyList(),
) {
    /** The one most important hint for the status card, or null. One slot, so the card never changes height. */
    val hint: String? get() = when {
        !running -> null
        outputMuted -> "Media volume is muted, so nothing is transmitted. Turn the volume up to about 70%."
        linkState == LinkState.TRANSMITTING -> null
        inputLevel == InputLevel.HOT -> "Input is clipping. Turn the radio volume down or move the phone away."
        volumeAdvice == VolumeAdvice.TOO_LOW -> "Media volume is low. Set it to about 70% so the radio's VOX opens reliably."
        volumeAdvice == VolumeAdvice.CLIPPING_RISK -> "Media volume is near maximum and may distort. About 70% works best."
        else -> null
    }
}

data class TransferCard(
    val sessionId: Int,
    val direction: Direction,
    val phase: String,
    val name: String,
    val totalChunks: Int,
    val receivedChunks: Int,
    val fecRepairedChunks: Int,
    val fecCorrectedBytes: Int,
    val missing: List<Int>,
    val lossPercent: Double,
    val repairRounds: Int,
    val snrDb: Double,
    val chunkStates: List<ChunkState>,
    val elapsedMs: Long,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val canRequestCorrections: Boolean,
    val canCancel: Boolean,
    val finished: Boolean,
    /** Sender of an incoming transfer, addressee of an outgoing one (null = all stations or unknown). */
    val peer: String? = null,
    val ackedBy: List<String> = emptyList(),
    /** Progressive image received so far (incoming only). */
    val preview: ImagePreview? = null,
) {
    val progress: Float get() = if (totalChunks <= 0) 0f else receivedChunks.toFloat() / totalChunks
    val sessionLabel: String get() = "%04X".format(sessionId)
}

data class ComposeEstimate(val bytes: Int, val wireBytes: Int, val chunks: Int, val airtimeMs: Long, val compressed: Boolean)

data class ImageDraftUi(
    val bytes: ByteArray? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Int = 0,
    val airtimeMs: Long = 0,
    val preset: ImagePreset = ImagePreset(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is ImageDraftUi && width == other.width && height == other.height &&
        sizeBytes == other.sizeBytes && airtimeMs == other.airtimeMs && preset == other.preset && busy == other.busy &&
        error == other.error && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (bytes?.contentHashCode() ?: 0) * 31 + preset.hashCode()
}

/** A station whose STATION frame we decoded. */
data class HeardStation(val callsign: String, val lastHeardMs: Long, val snrDb: Double)

/** The link test card. */
data class LinkTestUi(
    val running: Boolean = false,
    val sessionId: Int? = null,
    val report: LinkTestReport? = null,
    val recommendation: LinkRecommendation? = null,
    /** The other station: who answered our test, or who tested us. */
    val peer: String? = null,
    /** True when another station ran the test and we measured it. */
    val measuredHere: Boolean = false,
    val error: String? = null,
) {
    val visible: Boolean get() = running || report != null || error != null
}

data class ComposeUi(
    /** Addressee callsign; blank sends to every station. */
    val to: String = "",
    val draft: String = "",
    val profile: ModemProfile = ModemProfile.STANDARD,
    val textEstimate: ComposeEstimate? = null,
    val image: ImageDraftUi? = null,
    val sending: Boolean = false,
)

object Presenters {
    /** Transfers that ended are kept on the dashboard this long (wall clock). */
    const val FINISHED_VISIBLE_MS = 60_000L

    private val finishedPhases = setOf(
        ReceiverPhase.COMPLETE.name, ReceiverPhase.CORRUPT.name,
        SenderPhase.COMPLETE.name, SenderPhase.FAILED.name, SenderPhase.CANCELLED.name,
    )

    fun inputLevel(dbfs: Double): InputLevel = when {
        dbfs.isNaN() || dbfs < -60 -> InputLevel.SILENT
        dbfs < -40 -> InputLevel.LOW
        dbfs > -3 -> InputLevel.HOT
        else -> InputLevel.GOOD
    }

    /** dBFS mapped onto 0..1 over -60..0 dB for the level bar. */
    fun levelFraction(dbfs: Double): Float =
        if (dbfs.isNaN() || dbfs == Double.NEGATIVE_INFINITY) 0f else ((dbfs + 60) / 60).toFloat().coerceIn(0f, 1f)

    fun radio(
        running: Boolean,
        state: LinkState,
        route: RouteInfo?,
        rx: DemodTelemetry,
        transfers: Map<Int, TransferTelemetry>,
        output: OutputStatus?,
        hidden: Set<Int>,
    ) = RadioUi(
        running = running,
        linkState = state,
        route = route,
        levelDbfs = rx.levelDbfs,
        inputLevel = inputLevel(rx.levelDbfs),
        snrDb = rx.snrDb,
        rxState = rx.state,
        syncCorrelation = rx.syncCorrelation,
        burstProgress = if (rx.symbolsTotal > 0) rx.symbolsDone.toFloat() / rx.symbolsTotal else 0f,
        erasedSymbols = rx.erasedSymbols,
        volumeAdvice = output?.let { volumeAdvice(it.volume) },
        outputMuted = output?.muted == true,
        outputDevice = output?.lastDevice,
        transfers = cards(transfers.values, hidden),
    )

    /** Dashboard cards: running transfers first, newest first. [hidden] are finished ones the dashboard dropped. */
    fun cards(transfers: Collection<TransferTelemetry>, hidden: Set<Int>): List<TransferCard> = transfers
        .filter { it.sessionId !in hidden }
        .map { card(it) }
        .sortedWith(compareBy<TransferCard> { it.finished }.thenByDescending { it.startedAtMs })

    fun isFinished(t: TransferTelemetry): Boolean = t.phase in finishedPhases

    fun card(t: TransferTelemetry): TransferCard {
        val finished = t.phase in finishedPhases
        return TransferCard(
            sessionId = t.sessionId,
            direction = t.direction,
            phase = t.phase,
            name = t.name,
            totalChunks = t.totalChunks,
            receivedChunks = t.receivedChunks,
            fecRepairedChunks = t.fecRepairedChunks,
            fecCorrectedBytes = t.fecCorrectedBytes,
            missing = t.missing,
            lossPercent = t.lossPercent,
            repairRounds = t.repairRounds,
            snrDb = t.snrDb,
            chunkStates = t.chunkStates,
            // Link times run on the audio clock, so measure from the first to the latest activity.
            elapsedMs = t.updatedAtMs - t.startedAtMs,
            startedAtMs = t.startedAtMs,
            updatedAtMs = t.updatedAtMs,
            canRequestCorrections = t.direction == Direction.INCOMING && t.phase == ReceiverPhase.AWAITING_REPAIR.name && t.missing.isNotEmpty(),
            canCancel = t.direction == Direction.OUTGOING && (t.phase == SenderPhase.TRANSMITTING.name || t.phase == SenderPhase.AWAITING_RESPONSE.name),
            finished = finished,
            peer = t.peer,
            ackedBy = t.ackedBy,
            preview = t.preview?.takeIf { t.direction == Direction.INCOMING && !finished },
        )
    }

    /** Size and air time of a text message as it will go out (DEFLATE applied only when it helps). */
    fun textEstimate(text: String, options: TransferOptions, airtime: AirtimeEstimator): ComposeEstimate? {
        if (text.isEmpty()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        val deflated = if (options.compression == CompressionMode.AUTO) Deflate.compress(bytes) else null
        val compressed = deflated != null && deflated.size < bytes.size
        val wire = if (compressed) deflated.size else bytes.size
        val chunks = maxOf(1, (wire + options.chunkPayloadSize - 1) / options.chunkPayloadSize)
        return ComposeEstimate(bytes.size, wire, chunks, airtime.payloadMs(wire, options), compressed)
    }

    /** One-line notice for the snackbar, or null for events the dashboard already shows. */
    fun eventMessage(e: LinkEvent, autoNack: Boolean): String? = when (e) {
        is LinkEvent.MessageReceived -> {
            val from = e.from?.let { " from $it" } ?: ""
            when (e.payload.type) {
                ContentType.TEXT -> "Message$from: " + e.payload.asText().take(60)
                ContentType.IMAGE_WEBP, ContentType.IMAGE_PROGRESSIVE -> "Image received$from (${e.payload.bytes.size} bytes)"
                ContentType.BINARY -> "File received$from (${e.payload.bytes.size} bytes)"
            }
        }
        is LinkEvent.Delivered -> (e.by?.let { "Delivered: $it confirmed transfer %04X" } ?: "Delivered: the other station confirmed transfer %04X")
            .format(e.sessionId)
        is LinkEvent.LinkTestReported -> if (!e.measuredHere) null else
            "Link test from ${e.peer ?: "another station"} measured; the report was sent back."
        is LinkEvent.LinkTestFailed -> "Link test: ${e.reason}."
        is LinkEvent.Failed -> "Transfer %04X failed: %s".format(e.sessionId, e.reason)
        // When it ended a transfer, the Failed event that follows says so.
        is LinkEvent.AudioOutputFailed -> when {
            e.transferFailed -> null
            e.sessionId == LinkController.TEST_TONE_SESSION -> "Test tone failed: ${e.reason}"
            else -> "Could not answer transfer %04X (correction request or confirmation): %s".format(e.sessionId, e.reason)
        }
        is LinkEvent.CorrectionsNeeded -> if (autoNack) null else
            "${e.missing.size} chunks missing from %04X. Send a correction request when the channel is clear.".format(e.sessionId)
        else -> null
    }

    fun formatDuration(ms: Long): String {
        val s = (ms / 1000.0)
        return if (s < 60) "%.1f s".format(s) else "%d min %02d s".format((s / 60).toInt(), (s % 60).roundToInt().coerceAtMost(59))
    }

    /** Short description of a suggestion, for the link test card and its button. */
    fun recommendationLabel(r: LinkRecommendation): String = "${r.profile.name.lowercase().replaceFirstChar { it.uppercase() }}, ${r.parityPercent}% parity"

    /** Merge a STATION frame into the heard list: newest first, at most [max] stations. */
    fun heard(list: List<HeardStation>, callsign: String, now: Long, snrDb: Double, max: Int = 12): List<HeardStation> =
        (listOf(HeardStation(callsign, now, snrDb)) + list.filterNot { it.callsign == callsign }).take(max)

    fun formatBytes(n: Int): String = if (n < 1024) "$n B" else "%.1f KB".format(n / 1024.0)
}
