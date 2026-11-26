package com.radiome.data

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the Room history in step with a [LinkController]: records what we send, marks deliveries and failures,
 * stores completed incoming payloads and copies live counters. Run it in the link's own scope so DAO calls stay
 * off the main thread and in order with the link's state.
 */
class TransferRecorder(
    private val link: LinkController,
    private val repository: TransferRepository,
) {
    /** Send through the link and record the outgoing transfer. */
    fun send(payload: Payload, options: TransferOptions = TransferOptions()): Int {
        val sid = link.send(payload, options)
        val chunks = link.transfers.value[sid]?.totalChunks ?: 0
        repository.recordOutgoing(sid, payload, chunks, options.profile.name)
        return sid
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            link.events.collect { e ->
                when (e) {
                    is LinkEvent.MessageReceived -> repository.markReceived(e.sessionId, e.payload)
                    is LinkEvent.CorrectionsNeeded -> repository.setIncomingStatus(e.sessionId, TransferStatus.INCOMPLETE)
                    is LinkEvent.Delivered -> repository.setOutgoingStatus(e.sessionId, TransferStatus.DELIVERED)
                    is LinkEvent.Failed -> {
                        repository.setOutgoingStatus(e.sessionId, TransferStatus.FAILED)
                        repository.setIncomingStatus(e.sessionId, TransferStatus.FAILED)
                    }
                    else -> Unit
                }
            }
        }
        launch {
            link.transfers.collect { map ->
                for (t in map.values) {
                    if (t.direction == Direction.INCOMING && t.phase == "COMPLETE") continue
                    repository.applyTelemetry(t)
                }
            }
        }
    }
}
