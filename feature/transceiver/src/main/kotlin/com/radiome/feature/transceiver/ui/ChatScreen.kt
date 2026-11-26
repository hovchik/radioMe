package com.radiome.feature.transceiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.Chat
import com.radiome.feature.transceiver.ChatThread
import com.radiome.feature.transceiver.ComposeUi
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.QueuedItem
import com.radiome.feature.transceiver.TransferCard
import com.radiome.feature.transceiver.i18n.tr
import java.text.DateFormat
import java.util.Date

/** What the chat needs besides the shared compose actions. */
class ChatActions(
    val onOpen: (String?) -> Unit,
    val onRead: (String?) -> Unit,
    val onStartRadio: () -> Unit,
    val onDraftChange: (String) -> Unit,
    val onSendText: () -> Unit,
    val onQuickMessage: (String) -> Unit,
    val onPickImage: () -> Unit,
    val onImagePreset: (ImagePreset) -> Unit,
    val onClearImage: () -> Unit,
    val onSendImage: () -> Unit,
    val onResend: (TransferRecord) -> Unit,
    val onRemoveQueued: (Long) -> Unit,
)

/**
 * Conversations: a list of stations (plus "All stations"), and for each a thread of message bubbles with a
 * composer at the bottom. Sending goes through the same compose state as the Radio tab, addressed to the
 * conversation's station.
 */
@Composable
fun ChatScreen(
    threads: List<ChatThread>,
    records: List<TransferRecord>,
    transfers: List<TransferCard>,
    queue: List<QueuedItem>,
    compose: ComposeUi,
    running: Boolean,
    quickMessages: List<String>,
    now: Long,
    actions: ChatActions,
    queueActions: QueueActions,
    fileActions: FileActions,
    voiceActions: VoiceActions,
    historyActions: HistoryActions,
    modifier: Modifier = Modifier,
    /** A conversation another screen asked to open, such as the map's "Message" button. */
    openPeer: String? = null,
    onOpenPeerHandled: () -> Unit = {},
) {
    // "" is "All stations"; null is the list.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openPeer) {
        if (openPeer != null) {
            open = Chat.key(openPeer)
            onOpenPeerHandled()
        }
    }
    val current = open
    if (current == null) {
        ChatList(threads, running, now, actions, onOpen = { peer -> open = Chat.key(peer) }, modifier = modifier)
        return
    }
    BackHandler { open = null }
    val peer = current.ifEmpty { null }
    // Typing in another tab's "To" field moves where the composer sends; follow the conversation instead.
    LaunchedEffect(peer) { actions.onOpen(peer) }
    val messages = remember(records, peer) { Chat.messages(records, peer) }
    val waiting = remember(queue, peer) { Chat.queued(queue, peer) }
    val thread = threads.firstOrNull { it.key == current }
    Conversation(
        peer, thread, messages, transfers, waiting, compose, running, quickMessages, now,
        actions, queueActions, fileActions, voiceActions, historyActions,
        onBack = { open = null },
        modifier = modifier,
    )
}

@Composable
private fun ChatList(
    threads: List<ChatThread>,
    running: Boolean,
    now: Long,
    actions: ChatActions,
    onOpen: (String?) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (!running) item { RadioOffBanner(actions.onStartRadio, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        item { NewChat(onOpen) }
        items(threads, key = { it.key }) { t ->
            ThreadRow(t, now) { onOpen(t.peer) }
            HorizontalDivider(Modifier.padding(start = 72.dp))
        }
        if (threads.size <= 1) {
            item {
                Text(
                    tr("Stations you hear or exchange messages with show up here."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioOffBanner(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("The radio is off. Messages you write wait in the queue."), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onStart) { Text(tr("Turn on")) }
        }
    }
}

@Composable
private fun NewChat(onOpen: (String?) -> Unit) {
    var callsign by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' || c == '-' }.take(10) },
            label = { Text(tr("New chat with (callsign)")) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onOpen(callsign); callsign = "" }, enabled = callsign.isNotBlank()) { Text(tr("Open")) }
    }
}

@Composable
private fun ThreadRow(t: ChatThread, now: Long, onClick: () -> Unit) {
    val title = t.peer ?: tr("All stations")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(t.peer)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (t.unread > 0) FontWeight.Bold else null,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val at = t.last?.updatedAt ?: t.lastHeardMs
                if (at != null) {
                    Text(Presenters.formatAge(now - at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val line = t.last?.let(Chat::preview) ?: when {
                    t.peer == null -> tr("Messages to and from every station")
                    else -> tr("Heard on the air, no messages yet")
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (t.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(Modifier.semantics { contentDescription = tr("%d unread", t.unread) }) { Text("${t.unread}") }
                }
            }
        }
    }
}

@Composable
private fun Avatar(peer: String?) {
    Box(
        Modifier.size(40.dp).background(
            if (peer == null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            peer?.take(2) ?: "ALL",
            style = MaterialTheme.typography.labelLarge,
            color = if (peer == null) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun Conversation(
    peer: String?,
    thread: ChatThread?,
    messages: List<TransferRecord>,
    transfers: List<TransferCard>,
    waiting: List<QueuedItem>,
    compose: ComposeUi,
    running: Boolean,
    quickMessages: List<String>,
    now: Long,
    actions: ChatActions,
    queueActions: QueueActions,
    fileActions: FileActions,
    voiceActions: VoiceActions,
    historyActions: HistoryActions,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    // Keep the newest message in view and mark it read while the conversation is open.
    // Completing a transfer updates its row in place, so watch the newest update time as well as the count.
    val newest = messages.maxOfOrNull { it.updatedAt } ?: 0L
    LaunchedEffect(newest) { actions.onRead(peer) }
    LaunchedEffect(messages.size, waiting.size) {
        val count = messages.size + waiting.size
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    val live = remember(transfers) { transfers.associateBy { it.sessionId to it.direction } }
    Column(modifier.fillMaxSize().imePadding()) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) }
                Avatar(peer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer ?: tr("All stations"), style = MaterialTheme.typography.titleMedium)
                    val sub = thread?.lastHeardMs?.let { tr("Heard %s", Presenters.formatAge(now - it)) }
                        ?: if (peer == null) tr("Everyone on this frequency") else tr("Not heard yet")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty() && waiting.isEmpty()) {
                item {
                    Text(
                        tr("No messages yet. Write the first one below."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { r ->
                Bubble(r, live[r.sessionId to r.direction], showSender = peer == null, actions, historyActions)
            }
            items(waiting, key = { "q${it.id}" }) { q -> QueuedBubble(q, actions.onRemoveQueued) }
        }
        if (!running) RadioOffBanner(actions.onStartRadio, Modifier.padding(horizontal = 8.dp))
        Composer(compose, running, quickMessages, actions, queueActions, fileActions, voiceActions)
    }
}

@Composable
private fun Bubble(
    r: TransferRecord,
    live: TransferCard?,
    showSender: Boolean,
    actions: ChatActions,
    historyActions: HistoryActions,
) {
    val mine = r.direction == Direction.OUTGOING
    var menu by remember { mutableStateOf(false) }
    val time = remember(r.createdAt) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.createdAt)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp),
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showSender && !mine) {
                        Text(r.peer ?: tr("Unknown station"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    BubbleBody(r, live, historyActions)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val parts = listOfNotNull(time, tr("encrypted").takeIf { r.encrypted }, Chat.receipt(r))
                        val failed = r.status == TransferStatus.FAILED
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                failed -> RadioColors.missing
                                r.status == TransferStatus.DELIVERED -> RadioColors.ok
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (Chat.canResend(r)) {
                        TextButton(onClick = { actions.onResend(r) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Send again"))
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (r.payload != null && r.contentType?.isImage == true) {
                    DropdownMenuItem(text = { Text(tr("Save to gallery")) }, onClick = { menu = false; historyActions.onSaveToGallery(r) })
                }
                if (r.payload != null && (r.contentType?.isImage == true || r.contentType == ContentType.BINARY)) {
                    DropdownMenuItem(text = { Text(tr("Save as file")) }, onClick = { menu = false; historyActions.onSave(r) })
                }
                DropdownMenuItem(text = { Text(tr("Delete")) }, onClick = { menu = false; historyActions.onDelete(r.id) })
            }
        }
    }
}

@Composable
private fun BubbleBody(r: TransferRecord, live: TransferCard?, historyActions: HistoryActions) {
    val arriving = r.direction == Direction.INCOMING && (r.status == TransferStatus.RECEIVING || r.status == TransferStatus.INCOMPLETE)
    if (arriving) {
        live?.preview?.let { ProgressivePreview(it, Modifier.size(200.dp)) }
        val total = live?.totalChunks ?: r.totalChunks
        val got = live?.receivedChunks ?: r.receivedChunks
        Text(
            if (r.status == TransferStatus.INCOMPLETE) tr("Incomplete, %d of %d parts. Waiting for repairs.", got, total)
            else tr("Receiving %d of %d parts...", got, total),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (total > 0) LinearProgressIndicator(progress = { got.toFloat() / total }, modifier = Modifier.width(200.dp))
        return
    }
    if (r.direction == Direction.OUTGOING && live != null && !live.finished && live.totalChunks > 0) {
        // Still on air: show how far the transmission got.
        LinearProgressIndicator(progress = { live.progress.coerceAtLeast(0.02f) }, modifier = Modifier.width(160.dp))
    }
    val bytes = r.payload
    when {
        r.contentType == ContentType.TEXT -> Text(r.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        r.contentType?.isImage == true && bytes != null ->
            PayloadImage(displayImageBytes(r.contentType, bytes), tr("Picture"), Modifier.widthIn(max = 260.dp).heightIn(max = 260.dp))
        r.contentType == ContentType.VOICE && bytes != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { historyActions.onPlayVoice(r) }) { Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play")) }
            Text(tr("Voice note %d s", Chat.voiceSeconds(r)), style = MaterialTheme.typography.bodyLarge)
        }
        r.contentType == ContentType.BINARY && bytes != null -> Column {
            Text(r.name.ifBlank { tr("without name") }, style = MaterialTheme.typography.titleSmall)
            Text(Presenters.formatBytes(bytes.size), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { historyActions.onSave(r) }, contentPadding = PaddingValues(0.dp)) { Text(tr("Save as file")) }
        }
        else -> Text(r.text ?: r.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QueuedBubble(q: QueuedItem, onRemove: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val body = if (q.payload.type == ContentType.TEXT) q.payload.asText() else q.label
                Text(body, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Waiting in the queue"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onRemove(q.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("Remove")) }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    compose: ComposeUi,
    running: Boolean,
    quickMessages: List<String>,
    actions: ChatActions,
    queueActions: QueueActions,
    fileActions: FileActions,
    voiceActions: VoiceActions,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // An attachment being prepared takes the composer's place until it is sent, queued or removed.
            val image = compose.image
            val file = compose.file
            val voice = compose.voice
            when {
                compose.recording.active -> Recording(compose.recording, voiceActions)
                voice != null -> VoiceDraft(voice, compose.sending, running, voiceActions)
                file != null -> FileDraft(file, compose.sending, running, fileActions)
                image != null -> Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ImageDraft(image, compose.sending, running, actions.onImagePreset, actions.onClearImage, actions.onSendImage, queueActions.onQueueImage)
                }
                else -> TextComposer(compose, running, quickMessages, actions, queueActions, fileActions, voiceActions)
            }
        }
    }
}

@Composable
private fun TextComposer(
    compose: ComposeUi,
    running: Boolean,
    quickMessages: List<String>,
    actions: ChatActions,
    queueActions: QueueActions,
    fileActions: FileActions,
    voiceActions: VoiceActions,
) {
    if (quickMessages.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quickMessages.forEach { q ->
                SuggestionChip(onClick = { actions.onQuickMessage(q) }, enabled = running && !compose.sending, label = { Text(q) })
            }
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        var attach by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { attach = true }) { Icon(Icons.Filled.Add, contentDescription = tr("Attach")) }
            DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                DropdownMenuItem(text = { Text(tr("Image")) }, onClick = { attach = false; actions.onPickImage() })
                DropdownMenuItem(text = { Text(tr("File")) }, onClick = { attach = false; fileActions.onPick() })
                if (compose.voiceAvailable) {
                    DropdownMenuItem(text = { Text(tr("Voice")) }, onClick = { attach = false; voiceActions.onRecord() })
                }
            }
        }
        OutlinedTextField(
            value = compose.draft,
            onValueChange = actions.onDraftChange,
            placeholder = { Text(tr("Message")) },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        // With the radio off, the button queues the message so it goes out once the radio is on.
        val canSend = compose.draft.isNotBlank() && !compose.sending
        IconButton(
            onClick = { if (running) actions.onSendText() else queueActions.onQueueText() },
            enabled = canSend,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (running) tr("Send") else tr("Queue"))
        }
    }
    compose.textEstimate?.let {
        Text(
            tr("%s · %s · about %s on air", if (compose.auto) tr("Auto") else profileLabel(compose.profile), Presenters.formatBytes(it.wireBytes), Presenters.formatDuration(it.airtimeMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp),
        )
    }
}
