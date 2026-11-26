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
import android.util.Log
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.crypto.GroupKey
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
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the audio engine and the [LinkController], so long transfers keep running with the
 * screen off. The UI binds to it, reads [link], and calls into the link through [onLink]. Declared with the
 * `microphone|mediaPlayback` types.
 *
 * Binding does not start the radio: [start] (ACTION_START) does, and [stop] (ACTION_STOP, also the notification's
 * Stop action) shuts the link down even while the UI stays bound.
 *
 * The app supplies settings and persistence by making its Application implement [DependenciesProvider]. Without one
 * the service falls back to defaults and in-memory sessions.
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
        val routePreference: RoutePreference get() = RoutePreference.AUTO
        /** Station callsign sent at the start of every burst; blank for none. */
        val callsign: String get() = ""
        /** Raw 32-byte group key for end-to-end encryption, or null for none. */
        val groupKey: ByteArray? get() = null

        /**
         * Runs on the link thread right after the controller is created, before the UI can reach it. Coroutines
         * launched in [scope] run on the link thread and stop with the link.
         */
        fun onLinkStarted(link: LinkController, scope: CoroutineScope) {}

        fun onLinkStopped(link: LinkController) {}
    }

    interface DependenciesProvider {
        fun transceiverDependencies(): Dependencies
    }

    inner class LocalBinder : Binder() {
        val service: TransceiverService get() = this@TransceiverService
    }

    private val binder = LocalBinder()

    /** Single-threaded: the link controller requires confined state. Everything that touches it runs here. */
    private val linkDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + linkDispatcher)

    // Main-thread state.
    private var linkScope: CoroutineScope? = null
    @Volatile private var lastStatus = "Listening"
    private var dependencies: Dependencies? = null
    private var engine: AndroidAudioEngine? = null
    private var routeMonitor: AudioRouteMonitor? = null
    private var launchIntent: Intent? = null

    private val _link = MutableStateFlow<LinkController?>(null)
    /** The running link, or null while the radio is off. */
    val link: StateFlow<LinkController?> = _link.asStateFlow()

    private val _route = MutableStateFlow<AudioRoute?>(null)
    val route: StateFlow<AudioRoute?> = _route.asStateFlow()

    val audioEngine: AndroidAudioEngine? get() = engine

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
            shutDown()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!startInForeground(notification("Listening"))) {
            shutDown()
            return START_NOT_STICKY
        }
        startLink()
        // Not sticky: Android 14+ refuses to restart a microphone service from the background.
        return START_NOT_STICKY
    }

    /** Run [block] on the link thread. Returns null when the radio is off. */
    suspend fun <T> onLink(block: (LinkController) -> T): T? = withContext(linkDispatcher) { _link.value?.let(block) }

    /** Stop and start the link so new modem / ARQ / audio settings take effect. Partial transfers persist. */
    fun restartLink() {
        if (linkScope == null) return
        stopLink()
        startLink()
    }

    /** Turn the radio off and leave the foreground. A bound UI keeps the (idle) service alive. Main thread. */
    fun stopRadio() = shutDown()

    fun setRoutePreference(preference: RoutePreference) {
        routeMonitor?.preference = preference
    }

    fun setOutputGain(gain: Float) {
        engine?.outputGain = gain
    }

    /** New callsign for the running link (the next start reads it from the dependencies). */
    fun setCallsign(callsign: String) {
        scope.launch { _link.value?.callsign = callsign }
    }

    /**
     * Location was just granted while the radio runs: enter the foreground again with the location type, so position
     * beacons keep getting fixes with the screen off. Call from the visible UI (main thread).
     */
    fun refreshForegroundTypes() {
        if (linkScope == null) return
        startInForeground(notification(lastStatus))
    }

    fun setGroupKey(raw: ByteArray?) {
        scope.launch { _link.value?.groupKey = raw?.let(::GroupKey) }
    }

    private fun startLink() {
        if (linkScope != null) return
        val deps = (application as? DependenciesProvider)?.transceiverDependencies() ?: DefaultDependencies
        val ls = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val audio = AndroidAudioEngine(this, deps.audioConfig)
        val monitor = AudioRouteMonitor(this)
        monitor.preference = deps.routePreference
        monitor.start()
        audio.preferredInput = monitor.route.value.input
        audio.preferredOutput = monitor.route.value.output
        audio.startCapture()
        val controller = LinkController(
            audio, ls, deps.arqConfig, MfskModemEngine(deps.modemConfig), deps.sessionStore, callsign = deps.callsign,
        )
        controller.groupKey = deps.groupKey?.let(::GroupKey)

        linkScope = ls
        dependencies = deps
        engine = audio
        routeMonitor = monitor
        launchIntent = deps.launchIntent

        ls.launch {
            deps.onLinkStarted(controller, ls)
            controller.start()
            _link.value = controller
        }
        ls.launch {
            controller.state.collect { s ->
                val text = when (s) {
                    LinkState.LISTENING -> "Listening"
                    LinkState.RECEIVING -> "Receiving"
                    LinkState.TRANSMITTING -> "Transmitting"
                }
                lastStatus = text
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
        ls.launch {
            monitor.route.collect {
                audio.preferredInput = it.input
                audio.preferredOutput = it.output
                _route.value = it
            }
        }
    }

    private fun stopLink() {
        val ls = linkScope ?: return
        val controller = _link.value
        _link.value = null
        _route.value = null
        ls.cancel()
        linkScope = null
        routeMonitor?.stop()
        routeMonitor = null
        engine?.close()
        engine = null
        if (controller != null) dependencies?.onLinkStopped(controller)
        dependencies = null
    }

    private fun shutDown() {
        stopLink()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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

    private fun startInForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            // With location granted, position beacons keep working with the screen off.
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, n, types)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException / SecurityException when started from the background.
        Log.w(TAG, "could not enter the foreground", e)
        false
    }

    override fun onDestroy() {
        stopLink()
        scope.cancel()
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
        private const val TAG = "TransceiverService"
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
