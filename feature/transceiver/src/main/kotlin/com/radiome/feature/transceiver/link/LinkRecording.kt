package com.radiome.feature.transceiver.link

import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.data.PositionRepository
import com.radiome.data.TransferRecorder
import com.radiome.data.TransferRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Ties the Room history to whichever [LinkController] the service is running. The service calls [attach] and
 * [detach] (through its dependencies); the UI sends through [send] so outgoing transfers are recorded.
 * All calls happen on the link thread.
 */
class LinkRecording(private val repository: TransferRepository, private val positions: PositionRepository? = null) {
    private class Active(val link: LinkController, val recorder: TransferRecorder)

    @Volatile private var active: Active? = null

    fun attach(link: LinkController, scope: CoroutineScope) {
        val recorder = TransferRecorder(link, repository, positions)
        recorder.start(scope)
        active = Active(link, recorder)
    }

    fun detach(link: LinkController) {
        if (active?.link === link) active = null
    }

    fun send(link: LinkController, payload: Payload, options: TransferOptions): Int {
        val a = active
        return if (a != null && a.link === link) a.recorder.send(payload, options) else link.send(payload, options)
    }
}
