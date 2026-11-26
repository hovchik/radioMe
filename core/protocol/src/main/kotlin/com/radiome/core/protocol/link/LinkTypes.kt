package com.radiome.core.protocol.link

import com.radiome.core.dsp.modem.ModemProfile
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
    data class MessageReceived(override val sessionId: Int, val payload: Payload) : LinkEvent
    data class AckSent(override val sessionId: Int) : LinkEvent
    data class Delivered(override val sessionId: Int) : LinkEvent
    data class Failed(override val sessionId: Int, val reason: String) : LinkEvent
    /**
     * Playing a transmission threw. [sessionId] is [LinkController.TEST_TONE_SESSION] for a test tone.
     * [transferFailed] is true when it ended an outgoing transfer, which then also gets a [Failed] event; false for
     * a test tone or a receiver's NACK / ACK.
     */
    data class AudioOutputFailed(override val sessionId: Int, val reason: String, val transferFailed: Boolean = false) : LinkEvent
}
