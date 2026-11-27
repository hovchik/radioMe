package com.radiome.app

import android.app.Application
import android.content.Intent
import com.radiome.core.audio.AudioEngineConfig
import com.radiome.core.audio.RoutePreference
import com.radiome.core.audio.TransceiverService
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.SessionStore
import com.radiome.data.store.RoomSessionStore
import com.radiome.feature.transceiver.link.LinkRecording
import com.radiome.feature.transceiver.link.PositionBeacon
import com.radiome.feature.transceiver.link.ScheduledSender
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.feature.transceiver.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltAndroidApp
class RadioMeApp : Application(), TransceiverService.DependenciesProvider {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sessionStore: RoomSessionStore
    @Inject lateinit var recording: LinkRecording
    @Inject lateinit var keys: GroupKeyProvider
    @Inject lateinit var beacon: PositionBeacon
    @Inject lateinit var scheduler: ScheduledSender

    override fun onCreate() {
        super.onCreate()
        beacon.start()
        // Scheduled messages go out even when the radio was started from the widget and no screen is open.
        scheduler.start()
        // A new process means the radio is off, even if the last one was killed without stopping the link (Task
        // Manager, low memory): put the widget and the tile back to "off" so they offer Start again.
        RadioControls.onRunningChanged(this, false)
    }

    /** The service asks for this each time the link starts, so new settings apply on the next start or restart. */
    override fun transceiverDependencies(): TransceiverService.Dependencies {
        val s = settings.current()
        val store = sessionStore
        val rec = recording
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return object : TransceiverService.Dependencies {
            override val sessionStore: SessionStore = store
            override val modemConfig: ModemConfig = s.modemConfig()
            override val arqConfig: ArqConfig = s.arqConfig()
            override val audioConfig: AudioEngineConfig = s.audioConfig()
            override val launchIntent: Intent = launch
            override val routePreference: RoutePreference = s.routePreference
            override val callsign: String = s.callsign
            override val groupKey: ByteArray? = keys.current()?.encoded()

            override fun onLinkStarted(link: LinkController, scope: CoroutineScope) {
                rec.attach(link, scope)
                RadioControls.onRunningChanged(this@RadioMeApp, true)
            }

            override fun onLinkStopped(link: LinkController) {
                rec.detach(link)
                RadioControls.onRunningChanged(this@RadioMeApp, false)
            }
        }
    }
}
