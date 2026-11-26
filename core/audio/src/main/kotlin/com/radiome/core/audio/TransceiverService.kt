package com.radiome.core.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.link.InMemorySessionStore
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.link.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the audio engine and the [LinkController], so long transfers keep running with the
 * screen off. UI binds to it and reads [link]. Declared with the `microphone|mediaPlayback` types.
 *
 * The app supplies settings and persistence by making its Application implement [DependenciesProvider]
 * (wired through Hilt in Phase 4). Without one the service falls back to defaults and in-memory sessions.
 *
 * Android 14+: a microphone foreground service may only be started while the app is visible, so start it from the
 * UI (for example when the user enables listening), not from the background.
 */
class TransceiverService : Service() {
    interface Dependencies {
        val sessionStore: SessionStore
        val modemConfig: ModemConfig
        val arqConfig: ArqConfig
        val audioConfig: AudioEngineConfig
        /** Activity to open when the notification is tapped. */
        val launchIntent: Intent?
    }

    interface DependenciesProvider {
        fun transceiverDependencies(): Dependencies
    }

    inner class LocalBinder : Binder() {
        val service: TransceiverService get() = this@TransceiverService
    }

    private val binder = LocalBinder()

    /** Single-threaded: the link controller requires confined state. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var engine: AndroidAudioEngine? = null
    private var linkJob: Job? = null
    private var routeMonitor: AudioRouteMonitor? = null

    private val _link = MutableStateFlow<LinkController?>(null)
    val link: StateFlow<LinkController?> = _link.asStateFlow()

    val audioEngine: AndroidAudioEngine? get() = engine
    val routes: AudioRouteMonitor? get() = routeMonitor

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Radio transceiver", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while RadioMe listens to or transmits over the radio"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(notification("Listening"))
        startLink()
        return START_STICKY
    }

    private fun startLink() {
        if (_link.value != null) return
        val deps = (application as? DependenciesProvider)?.transceiverDependencies() ?: DefaultDependencies
        val audio = AndroidAudioEngine(this, deps.audioConfig)
        val monitor = AudioRouteMonitor(this).also { it.start() }
        audio.preferredInput = monitor.route.value.input
        audio.preferredOutput = monitor.route.value.output
        audio.startCapture()
        val controller = LinkController(audio, scope, deps.arqConfig, MfskModemEngine(deps.modemConfig), deps.sessionStore)
        engine = audio
        routeMonitor = monitor
        linkJob = controller.start()
        _link.value = controller
        launchIntent = deps.launchIntent

        scope.launch {
            controller.state.collect { s ->
                val text = when (s) {
                    LinkState.LISTENING -> "Listening"
                    LinkState.RECEIVING -> "Receiving"
                    LinkState.TRANSMITTING -> "Transmitting"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
        scope.launch {
            monitor.route.collect {
                audio.preferredInput = it.input
                audio.preferredOutput = it.output
            }
        }
    }

    private var launchIntent: Intent? = null

    private fun notification(status: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("RadioMe")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        launchIntent?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onDestroy() {
        linkJob?.cancel()
        scope.cancel()
        routeMonitor?.stop()
        engine?.close()
        engine = null
        _link.value = null
        super.onDestroy()
    }

    private object DefaultDependencies : Dependencies {
        override val sessionStore: SessionStore = InMemorySessionStore()
        override val modemConfig = ModemConfig()
        override val arqConfig = ArqConfig()
        override val audioConfig = AudioEngineConfig()
        override val launchIntent: Intent? = null
    }

    companion object {
        const val CHANNEL_ID = "radiome.transceiver"
        const val NOTIFICATION_ID = 0x5A
        const val ACTION_START = "com.radiome.core.audio.START"
        const val ACTION_STOP = "com.radiome.core.audio.STOP"

        /** Call from the visible UI. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransceiverService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransceiverService::class.java).setAction(ACTION_STOP))
        }
    }
}
