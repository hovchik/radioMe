package com.radiome.feature.transceiver.link

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.i18n.tr
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.feature.transceiver.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** How often a scheduled message goes out again after its first time. */
enum class Repeat(val periodMs: Long) {
    ONCE(0),
    HOURLY(60 * 60_000L),
    DAILY(24 * 60 * 60_000L),
}

/**
 * A message that goes out by itself at [dueAt]. [payload] is null for a position: the fix is read when it is due, so
 * the other stations get where this phone is then, not where it was when the schedule was made.
 */
data class ScheduledMessage(
    val id: Long,
    val label: String,
    val payload: Payload?,
    /** Addressee callsign; blank goes to every station. */
    val to: String,
    /** Null picks the default profile (or Auto speed's) when it goes out. */
    val profile: ModemProfile?,
    val dueAt: Long,
    val repeat: Repeat = Repeat.ONCE,
) {
    val isPosition: Boolean get() = payload == null

    /** The next time after [now] a repeating message is due, skipping the ones missed while the radio was off. */
    fun nextAfter(now: Long): Long? {
        if (repeat == Repeat.ONCE) return null
        val p = repeat.periodMs
        val missed = ((now - dueAt) / p).coerceAtLeast(0) + 1
        return dueAt + missed * p
    }
}

/** Where scheduled messages are kept so they survive the app being closed. */
interface ScheduleStore {
    suspend fun load(): List<ScheduledMessage>
    suspend fun save(items: List<ScheduledMessage>)
}

class InMemoryScheduleStore : ScheduleStore {
    @Volatile var items: List<ScheduledMessage> = emptyList()
    override suspend fun load() = items
    override suspend fun save(items: List<ScheduledMessage>) { this.items = items }
}

/** A small binary file in the app's private storage. */
class FileScheduleStore(private val file: File) : ScheduleStore {
    override suspend fun load(): List<ScheduledMessage> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { ScheduleCodec.decode(file.readBytes()) }.getOrDefault(emptyList())
    }

    override suspend fun save(items: List<ScheduledMessage>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(ScheduleCodec.encode(items))
        if (!tmp.renameTo(file)) {
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        Unit
    }
}

internal object ScheduleCodec {
    private const val VERSION = 1

    fun encode(items: List<ScheduledMessage>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeInt(items.size)
            for (m in items) {
                out.writeLong(m.id)
                out.writeUTF(m.label)
                out.writeUTF(m.to)
                out.writeUTF(m.profile?.name ?: "")
                out.writeLong(m.dueAt)
                out.writeUTF(m.repeat.name)
                val p = m.payload
                out.writeInt(p?.type?.code ?: 0)
                if (p != null) {
                    out.writeUTF(p.name)
                    out.writeInt(p.bytes.size)
                    out.write(p.bytes)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<ScheduledMessage> = DataInputStream(ByteArrayInputStream(data)).use { input ->
        require(input.readInt() == VERSION) { "unknown schedule file version" }
        List(input.readInt()) {
            val id = input.readLong()
            val label = input.readUTF()
            val to = input.readUTF()
            val profile = input.readUTF().takeIf { it.isNotEmpty() }?.let { n -> ModemProfile.entries.firstOrNull { it.name == n } }
            val dueAt = input.readLong()
            val repeatName = input.readUTF()
            val repeat = Repeat.entries.firstOrNull { it.name == repeatName } ?: Repeat.ONCE
            val code = input.readInt()
            val payload = if (code == 0) null else {
                val name = input.readUTF()
                val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
                Payload(requireNotNull(ContentType.fromCode(code)), bytes, name)
            }
            ScheduledMessage(id, label, payload, to, profile, dueAt, repeat)
        }
    }
}

/**
 * Sends scheduled messages when they are due. It lives as long as the app process (the radio's foreground service
 * keeps that alive), not as long as a screen, and its list is saved so a restart keeps it.
 *
 * A message due while the radio is off goes out as soon as the radio is turned on. Like the queue, it waits until
 * none of our own transfers is still on the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSender(
    private val client: TransceiverClient,
    private val settings: SettingsRepository,
    private val keys: GroupKeyProvider,
    private val beacon: PositionBeacon,
    private val store: ScheduleStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _items = MutableStateFlow<List<ScheduledMessage>>(emptyList())
    /** Waiting messages, soonest first. */
    val items: StateFlow<List<ScheduledMessage>> = _items.asStateFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)
    /** What happened to messages that went out (or could not), for the snackbar. */
    val notices: Flow<String> = _notices.receiveAsFlow()

    /** Auto speed's current profile, set by the screen; used for messages without a profile of their own. */
    @Volatile var autoProfile: ModemProfile? = null

    private val lock = Mutex()
    private val loaded = MutableStateFlow(false)
    private var job: Job? = null

    /** Load the saved list and start the timer (idempotent). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            val saved = runCatching { store.load() }.getOrDefault(emptyList())
            lock.withLock { _items.update { (saved + it).distinctBy { m -> m.id }.sortedBy { m -> m.dueAt } } }
            loaded.value = true
            run()
        }
    }

    fun add(
        label: String,
        payload: Payload?,
        to: String,
        profile: ModemProfile?,
        dueAt: Long,
        repeat: Repeat = Repeat.ONCE,
    ): ScheduledMessage {
        val m = ScheduledMessage(newId(), label, payload, to.trim().uppercase(), profile, dueAt, repeat)
        change { it + m }
        return m
    }

    fun remove(id: Long) = change { list -> list.filterNot { it.id == id } }

    /** Make it due right away (it still waits for the radio and for our other transfers). */
    fun sendNow(id: Long) = change { list -> list.map { if (it.id == id) it.copy(dueAt = clock()) else it } }

    private var lastId = 0L

    private fun newId(): Long = synchronized(this) {
        lastId = maxOf(lastId + 1, clock(), (_items.value.maxOfOrNull { it.id } ?: 0) + 1)
        lastId
    }

    private fun change(f: (List<ScheduledMessage>) -> List<ScheduledMessage>) {
        _items.update { f(it).sortedBy { m -> m.dueAt } }
        scope.launch { persist() }
    }

    private suspend fun persist() {
        loaded.first { it } // never overwrite the saved list before it was read
        lock.withLock { runCatching { store.save(_items.value) } }
    }

    private suspend fun run() {
        combine(_items, client.running) { list, on -> if (on) list.firstOrNull() else null }
            .collectLatest { next ->
                next ?: return@collectLatest
                val wait = next.dueAt - clock()
                if (wait > 0) delay(wait)
                // One at a time with the queue and the other schedules: wait for our own transfers to finish.
                client.transfers.first { m -> m.values.none { it.direction == Direction.OUTGOING && !Presenters.isFinished(it) } }
                // Not cancelled half way by a list change: a message sent but not taken off would go out twice.
                withContext(NonCancellable) { fire(next) }
            }
    }

    /** Sends [m] and takes it off the list (or moves a repeating one to its next time). */
    private suspend fun fire(m: ScheduledMessage) {
        val outcome = deliver(m) ?: return // radio went off: try again when it is back
        _notices.trySend(outcome)
        val now = clock()
        change { list ->
            list.mapNotNull {
                when {
                    it.id != m.id -> it
                    else -> it.nextAfter(now)?.let { next -> it.copy(dueAt = next) }
                }
            }
        }
    }

    /** Null when the radio was off; otherwise what to tell the user. */
    private suspend fun deliver(m: ScheduledMessage): String? {
        val payload = m.payload
        if (payload == null) {
            return when (val r = beacon.sendNow()) {
                is PositionBeacon.Result.RadioOff -> null
                else -> tr("Scheduled position: %s", Presenters.beaconMessage(r))
            }
        }
        val s = settings.settings.value
        var options = s.transferOptions(m.profile ?: autoProfile ?: s.defaultProfile, m.to)
        if (payload.type != ContentType.TEXT && payload.type != ContentType.BINARY) options = options.copy(compression = CompressionMode.NEVER)
        if (options.encrypt && keys.key.value == null) {
            return tr("Did not send scheduled \"%s\": encryption is on but no group key is set.", m.label)
        }
        val sid = try {
            client.send(payload, options)
        } catch (e: IllegalStateException) {
            return tr("Could not send \"%s\": %s", m.label, e.message ?: "")
        }
        return if (sid == null) null else tr("Scheduled message \"%s\" is going out.", m.label)
    }
}
