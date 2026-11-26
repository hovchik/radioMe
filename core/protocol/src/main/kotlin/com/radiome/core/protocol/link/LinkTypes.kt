package com.radiome.core.protocol.link

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.ImagePreview
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.ReceiverSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Audio I/O seen by the link. Phase 3 implements it with AudioRecord / AudioTrack; tests implement it with a
 * simulated radio channel.
 */
interface AudioPort {
    /** Continuous 48 kHz mono capture blocks. The link ignores blocks that arrive while it transmits. */
    val input: Flow<FloatArray>

    /** Play [samples] and suspend until they have fully left the speaker / cable. */
    suspend fun play(samples: FloatArray)
}

/** Persistence hook for partial transfers (Room implementation in Phase 3). */
interface SessionStore {
    fun save(snapshot: ReceiverSnapshot)
    fun load(sessionId: Int): ReceiverSnapshot?
    fun all(): List<ReceiverSnapshot>
}

class InMemorySessionStore : SessionStore {
    private val map = LinkedHashMap<Int, ReceiverSnapshot>()
    override fun save(snapshot: ReceiverSnapshot) { map[snapshot.sessionId] = snapshot }
    override fun load(sessionId: Int) = map[sessionId]
    override fun all() = map.values.toList()
}

enum class LinkState { LISTENING, RECEIVING, TRANSMITTING }

enum class Direction { OUTGOING, INCOMING }

data class TransferTelemetry(
    val sessionId: Int,
    val direction: Direction,
    /** Phase name of the sender/receiver state machine. */
    val phase: String,
    val name: String,
    val totalChunks: Int,
    val chunkStates: List<ChunkState>,
    val receivedChunks: Int,
    val fecRepairedChunks: Int,
    val fecCorrectedBytes: Int,
    val missing: List<Int>,
    val lossPercent: Double,
    val repairRounds: Int,
    val snrDb: Double,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    /** The other station: the sender of an incoming transfer, the addressee of an outgoing one (null = all). */
    val peer: String? = null,
    /** Callsigns that confirmed an outgoing transfer. */
    val ackedBy: List<String> = emptyList(),
    /** What can be shown so far of an incoming progressive image. */
    val preview: ImagePreview? = null,
    /** Incoming: the sender's STATION frame addressed it to every station. */
    val broadcast: Boolean = false,
)

sealed interface LinkEvent {
    val sessionId: Int

    data class ReceiveStarted(override val sessionId: Int, val totalChunks: Int) : LinkEvent
    data class CorrectionsNeeded(override val sessionId: Int, val missing: List<Int>, val lossPercent: Double) : LinkEvent
    data class NackSent(override val sessionId: Int, val missing: List<Int>) : LinkEvent
    data class NackReceived(override val sessionId: Int, val requested: List<Int>) : LinkEvent
    data class Transmitted(override val sessionId: Int, val chunks: List<Int>, val profile: ModemProfile, val kind: Kind) : LinkEvent {
        enum class Kind { INITIAL, REPAIR, POLL }
    }
    /** [from] and [to] come from the sender's STATION frame (null when it sent none; [to] null = all stations). */
    data class MessageReceived(
        override val sessionId: Int,
        val payload: Payload,
        val from: String? = null,
        val to: String? = null,
        /** It came encrypted with a group key. With [unreadable] set, [payload] is still the sealed envelope. */
        val encrypted: Boolean = false,
        /** Why an encrypted message could not be opened (no key, another group's key, failed check). */
        val unreadable: String? = null,
    ) : LinkEvent
    data class AckSent(override val sessionId: Int) : LinkEvent
    /** A broadcast receiver confirmed; the session stays open for the others. */
    data class AckReceived(override val sessionId: Int, val by: String?) : LinkEvent
    /** [by] is the confirming station's callsign, when it identified itself. */
    data class Delivered(override val sessionId: Int, val by: String? = null) : LinkEvent
    /** A STATION frame was decoded: [callsign] is on the air. */
    data class StationHeard(override val sessionId: Int, val callsign: String, val snrDb: Double) : LinkEvent
    data class LinkTestStarted(override val sessionId: Int) : LinkEvent
    /**
     * Link test measurements. [measuredHere] is true on the station that received the probes (it measured them and
     * sends the report back), false on the station that ran the test (it heard the report from [peer]).
     */
    data class LinkTestReported(
        override val sessionId: Int,
        val report: LinkTestReport,
        val peer: String?,
        val measuredHere: Boolean,
    ) : LinkEvent
    data class LinkTestFailed(override val sessionId: Int, val reason: String) : LinkEvent
    data class Failed(override val sessionId: Int, val reason: String) : LinkEvent
    /**
     * Playing a transmission threw. [sessionId] is [LinkController.TEST_TONE_SESSION] for a test tone.
     * [transferFailed] is true when it ended an outgoing transfer, which then also gets a [Failed] event; false for
     * a test tone or a receiver's NACK / ACK.
     */
    data class AudioOutputFailed(override val sessionId: Int, val reason: String, val transferFailed: Boolean = false) : LinkEvent
}
