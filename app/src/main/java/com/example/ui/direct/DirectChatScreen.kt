package com.example.ui.direct

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.crypto.NexusCryptoEngine
import com.example.data.DecryptedDirectMessageItem
import com.example.data.DirectMessage
import com.example.data.DirectThread
import com.example.data.MediaDrop
import com.example.data.PendingMediaSelection
import com.example.data.ReplyTarget
import com.example.data.UiState
import com.example.ui.components.DecryptedMediaInspectDialog
import com.example.ui.components.EmbeddedMediaDropCard
import com.example.ui.components.EncryptedMediaDropDialog
import com.example.ui.components.EncryptedVoicePlayerCard
import com.example.ui.components.MessageEditDialog
import com.example.ui.components.MessageReactionBar
import com.example.ui.components.OfflineQueueBanner
import com.example.ui.components.ReplyQuoteSnippet
import com.example.ui.components.RoomEmblem
import com.example.ui.components.VoiceRecordingBar
import com.example.ui.components.formatExactClock
import kotlinx.coroutines.delay

@Composable
fun DirectChatScreen(
    thread: DirectThread,
    currentUserId: String,
    messagesState: UiState<List<DecryptedDirectMessageItem>>,
    isOnline: Boolean,
    inspectRawCipher: Boolean,
    isActionBusy: Boolean,
    uploadProgress: Float?,
    isRecordingVoice: Boolean,
    recordingDurationMs: Long,
    micAmplitude: Float,
    playingMessageId: String?,
    playbackProgress: Float,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String, ReplyTarget?) -> Unit,
    onStartVoiceRecord: () -> Unit,
    onStopAndSendVoice: () -> Unit,
    onCancelVoiceRecord: () -> Unit,
    onTogglePlayVoice: (DirectMessage) -> Unit,
    onToggleReaction: (DirectMessage, String) -> Unit,
    onTogglePin: (DirectMessage) -> Unit,
    onEditMessage: (DirectMessage, String) -> Unit,
    onInspectMediaUri: (Uri) -> PendingMediaSelection,
    onPublishMediaDrop: (PendingMediaSelection, Int, Boolean, String, () -> Unit) -> Unit,
    onDecryptPreview: (MediaDrop) -> ByteArray?,
    onMarkDropViewed: (MediaDrop) -> Unit,
    onBurnDrop: (MediaDrop) -> Unit,
    onTypingChanged: (String) -> Unit,
    onClearTyping: () -> Unit,
    onMarkMessagesRead: (List<DirectMessage>) -> Unit,
    onBurnMessage: (DirectMessage) -> Unit,
    onToggleInspectCipher: () -> Unit,
    onRotateKey: () -> Unit,
    onDeleteThread: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = showBackButton) {
        onBack()
    }

    val context = LocalContext.current
    val listState = rememberLazyListState()
    var draftText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var showPinnedDialog by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<ReplyTarget?>(null) }
    var editingItem by remember { mutableStateOf<DecryptedDirectMessageItem?>(null) }
    var pendingSelection by remember { mutableStateOf<PendingMediaSelection?>(null) }
    var inspectingDrop by remember { mutableStateOf<MediaDrop?>(null) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val peerName = thread.peerDisplayName(currentUserId)
    val isSelfThread = thread.isSelfThread(currentUserId)
    val isPeerTyping = thread.isPeerTyping(currentUserId, nowMillis)

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    DisposableEffect(thread.id) {
        onDispose { onClearTyping() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoiceRecord()
    }

    fun requestOrStartRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStartVoiceRecord()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingSelection = onInspectMediaUri(uri) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingSelection = onInspectMediaUri(uri) }

    val messageList = (messagesState as? UiState.Success)?.data.orEmpty()
    val pinnedMessages = remember(messageList) {
        messageList.filter { it.message.pinned == true && it.message.status == "active" }
    }
    val filteredMessages = remember(messageList, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) messageList
        else messageList.filter { it.decryptedText.contains(q, ignoreCase = true) }
    }

    // Automatically record read receipts for incoming peer messages while conversation is open
    LaunchedEffect(thread.id, messageList) {
        val unreadPeerMessages = messageList
            .map { it.message }
            .filter { it.status == "active" && it.senderId != currentUserId && currentUserId !in it.readByUids }
        if (unreadPeerMessages.isNotEmpty() || thread.hasUnreadFor(currentUserId)) {
            onMarkMessagesRead(unreadPeerMessages)
        }
    }

    LaunchedEffect(filteredMessages.size, isPeerTyping) {
        val targetIndex = filteredMessages.lastIndex + if (isPeerTyping) 1 else 0
        if (targetIndex >= 0 && (filteredMessages.isNotEmpty() || isPeerTyping) && searchQuery.isBlank()) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Minimalist DM Top Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showBackButton) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("dm_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
                RoomEmblem(
                    roomName = peerName,
                    accentTag = if (isSelfThread) "cyan" else "emerald",
                    size = 38.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                        Text(
                            text = if (isPeerTyping) "$peerName is typing…" else "AES-256 Direct • Key v${thread.keyVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar) searchQuery = ""
                }) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.cd_search),
                        tint = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showPinnedDialog = true }) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = stringResource(R.string.cd_pinned),
                        tint = if (pinnedMessages.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { showSecurityDialog = true },
                    modifier = Modifier.testTag("dm_security_button")
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = stringResource(R.string.cd_dm_security), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        OfflineQueueBanner(isOnline = isOnline)

        AnimatedVisibility(visible = showSearchBar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.label_search_messages), style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(visible = inspectRawCipher) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CIPHER INSPECTOR ACTIVE — SHOWING E2EE WIRE PAYLOAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = onToggleInspectCipher) {
                        Text("Hide", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Direct Message Card Stream
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (messagesState) {
                is UiState.Loading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center))
                }

                is UiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        Text(
                            text = messagesState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                is UiState.Success -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (filteredMessages.isEmpty()) {
                            item(key = "dm_e2ee_banner") {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) "No matches for \"$searchQuery\"" else "Private AES-256-GCM Direct Channel",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Supports encrypted text, voice notes, 4.00 GB temporary drops, reactions, replies, live typing indicators, and verified read receipts.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Safety Fingerprint: ${thread.keyFingerprint}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        items(filteredMessages, key = { it.message.id }) { item ->
                            DirectMessageCard(
                                item = item,
                                isSelf = item.message.senderId == currentUserId,
                                currentUserId = currentUserId,
                                isSelfThread = isSelfThread,
                                inspectRawCipher = inspectRawCipher,
                                nowMillis = nowMillis,
                                isPlayingVoice = playingMessageId == item.message.id,
                                playbackProgress = playbackProgress,
                                onTogglePlayVoice = { onTogglePlayVoice(item.message) },
                                onReply = {
                                    replyTarget = ReplyTarget(
                                        messageId = item.message.id,
                                        senderName = item.message.senderName,
                                        previewText = item.decryptedText
                                    )
                                },
                                onToggleReaction = { emoji -> onToggleReaction(item.message, emoji) },
                                onTogglePin = { onTogglePin(item.message) },
                                onEdit = { editingItem = item },
                                onBurnMessage = { onBurnMessage(item.message) },
                                onOpenMediaDrop = { drop ->
                                    inspectingDrop = drop
                                    onMarkDropViewed(drop)
                                },
                                onBurnDrop = onBurnDrop
                            )
                        }

                        if (isPeerTyping) {
                            item(key = "peer_typing_indicator") {
                                TypingIndicatorCard(peerName = peerName)
                            }
                        }
                    }
                }
            }
        }

        // Composer Bar with 4GB Drops, Voice Notes & Replies
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                replyTarget?.let { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReplyQuoteSnippet(
                            senderName = target.senderName,
                            snippetText = target.previewText,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (isRecordingVoice) {
                    VoiceRecordingBar(
                        durationMs = recordingDurationMs,
                        amplitude = micAmplitude,
                        onCancel = onCancelVoiceRecord,
                        onSend = onStopAndSendVoice
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = stringResource(R.string.cd_attach_photo), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    }
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.cd_attach_file), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(21.dp))
                    }
                    IconButton(
                        onClick = {
                            if (isRecordingVoice) onStopAndSendVoice()
                            else requestOrStartRecording()
                        },
                        modifier = Modifier.testTag("dm_record_voice_button")
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.cd_voice_record),
                            tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("E2EE • 4GB cap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { updated ->
                            if (updated.length <= 4000) {
                                draftText = updated
                                onTypingChanged(updated)
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.label_dm_placeholder),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dm_message_input")
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (draftText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val toSend = draftText.trim()
                                if (toSend.isNotEmpty()) {
                                    onSendMessage(toSend, replyTarget)
                                    draftText = ""
                                    replyTarget = null
                                }
                            },
                            enabled = draftText.isNotBlank(),
                            modifier = Modifier.testTag("send_dm_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.cd_send),
                                tint = if (draftText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    pendingSelection?.let { selection ->
        EncryptedMediaDropDialog(
            selection = selection,
            defaultTtlMinutes = 60,
            isBusy = isActionBusy,
            uploadProgress = uploadProgress,
            onDismiss = { if (!isActionBusy) pendingSelection = null },
            onConfirm = { ttl, burnAfterView, caption ->
                onPublishMediaDrop(selection, ttl, burnAfterView, caption) { pendingSelection = null }
            }
        )
    }

    editingItem?.let { target ->
        MessageEditDialog(
            initialText = target.decryptedText,
            onDismiss = { editingItem = null },
            onConfirm = { updated ->
                onEditMessage(target.message, updated)
                editingItem = null
            }
        )
    }

    if (showPinnedDialog) {
        AlertDialog(
            onDismissRequest = { showPinnedDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.label_pinned_cards), style = MaterialTheme.typography.titleLarge) },
            text = {
                if (pinnedMessages.isEmpty()) {
                    Text("No pinned direct cards yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 320.dp)) {
                        items(pinnedMessages, key = { it.message.id }) { item ->
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("${item.message.senderName} • ${formatExactClock(item.message.createdAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        TextButton(onClick = { onTogglePin(item.message) }) { Text("Unpin", style = MaterialTheme.typography.labelSmall) }
                                    }
                                    Text(item.decryptedText, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showPinnedDialog = false }, shape = RoundedCornerShape(12.dp)) { Text("Close") } }
        )
    }

    if (showSecurityDialog) {
        DirectSecurityDialog(
            thread = thread,
            peerName = peerName,
            inspectRawCipher = inspectRawCipher,
            isBusy = isActionBusy,
            onToggleInspectCipher = onToggleInspectCipher,
            onRotateKey = onRotateKey,
            onDeleteThread = {
                showSecurityDialog = false
                onDeleteThread()
            },
            onDismiss = { showSecurityDialog = false }
        )
    }

    inspectingDrop?.let { drop ->
        val previewBytes = remember(drop.id, drop.status) { onDecryptPreview(drop) }
        val hasLocal = remember(drop.id) { NexusCryptoEngine.hasLocalEncryptedVaultFile(context, drop.id) }
        DecryptedMediaInspectDialog(
            drop = drop,
            previewBytes = previewBytes,
            hasLocalVaultCopy = hasLocal,
            nowMillis = nowMillis,
            onDismiss = { inspectingDrop = null },
            onBurnNow = { onBurnDrop(drop) }
        )
    }
}

@Composable
private fun DirectMessageCard(
    item: DecryptedDirectMessageItem,
    isSelf: Boolean,
    currentUserId: String,
    isSelfThread: Boolean,
    inspectRawCipher: Boolean,
    nowMillis: Long,
    isPlayingVoice: Boolean,
    playbackProgress: Float,
    onTogglePlayVoice: () -> Unit,
    onReply: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onBurnMessage: () -> Unit,
    onOpenMediaDrop: (MediaDrop) -> Unit,
    onBurnDrop: (MediaDrop) -> Unit
) {
    val msg = item.message
    val isBurned = msg.status == "burned"
    val isReadByPeer = isSelfThread || msg.isReadByPeer(msg.senderId)
    var showReactionPicker by remember(msg.id) { mutableStateOf(false) }

    val cardColor = when {
        isBurned -> MaterialTheme.colorScheme.surfaceContainerLow
        isSelf -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }
    val cardBorder = when {
        isBurned -> MaterialTheme.colorScheme.outlineVariant
        msg.pinned == true -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
        isSelf -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dm_message_card_${msg.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isSelf) "${msg.senderName} (You)" else msg.senderName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (msg.pinned == true) {
                        Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        text = formatExactClock(msg.createdAt) + if (msg.edited == true) " • edited" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!isBurned) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onReply, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = stringResource(R.string.action_reply), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { showReactionPicker = !showReactionPicker }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.AddReaction, contentDescription = stringResource(R.string.action_react), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onTogglePin, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = stringResource(if (msg.pinned == true) R.string.action_unpin else R.string.action_pin),
                                tint = if (msg.pinned == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        if (isSelf && msg.effectiveType() == "text") {
                            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            }
                        }
                        if (isSelf) {
                            IconButton(onClick = onBurnMessage, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.LocalFireDepartment, contentDescription = stringResource(R.string.action_burn_now), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }

            if (!isBurned && !msg.replyToSenderName.isNullOrBlank()) {
                ReplyQuoteSnippet(
                    senderName = msg.replyToSenderName,
                    snippetText = item.decryptedReplySnippet ?: "Encrypted message"
                )
            }

            Text(
                text = item.decryptedText,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isBurned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            if (!isBurned && msg.effectiveType() == "voice" && !msg.encryptedAudioBase64.isNullOrBlank()) {
                EncryptedVoicePlayerCard(
                    durationMs = msg.audioDurationMs ?: 1000L,
                    isPlaying = isPlayingVoice,
                    progress = playbackProgress,
                    onTogglePlay = onTogglePlayVoice
                )
            }

            item.linkedDrop?.let { drop ->
                EmbeddedMediaDropCard(
                    drop = drop,
                    isSelf = isSelf,
                    nowMillis = nowMillis,
                    onDecryptClick = { onOpenMediaDrop(drop) },
                    onBurnClick = { onBurnDrop(drop) }
                )
            }

            if (!isBurned) {
                MessageReactionBar(
                    reactions = msg.reactions,
                    currentUserId = currentUserId,
                    showPicker = showReactionPicker,
                    onTogglePicker = { showReactionPicker = !showReactionPicker },
                    onToggleEmoji = onToggleReaction
                )
            }

            // Read Receipt Footer for Outgoing Messages
            if (isSelf && !isBurned) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isReadByPeer) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                        contentDescription = if (isReadByPeer) "Read receipt confirmed" else "Sent",
                        tint = if (isReadByPeer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when {
                            isSelfThread -> "Encrypted locally"
                            isReadByPeer && msg.readAt != null -> "Read ${formatExactClock(msg.readAt)}"
                            isReadByPeer -> "Read"
                            else -> "Sent • Encrypted"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReadByPeer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (inspectRawCipher) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("IV (96b): ${msg.ivBase64}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Text("CT (AES-256-GCM): ${msg.ciphertextBase64.take(72)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicatorCard(peerName: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "dm_typing_dots")
    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550, delayMillis = 0), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550, delayMillis = 160), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550, delayMillis = 320), RepeatMode.Reverse),
        label = "dot3"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.testTag("dm_typing_indicator_card")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(dot1, dot2, dot3).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(alpha)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Text("$peerName is typing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DirectSecurityDialog(
    thread: DirectThread,
    peerName: String,
    inspectRawCipher: Boolean,
    isBusy: Boolean,
    onToggleInspectCipher: () -> Unit,
    onRotateKey: () -> Unit,
    onDeleteThread: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Direct Encryption Vault", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("PAIRWISE SAFETY FINGERPRINT (v${thread.keyVersion})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(thread.keyFingerprint, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Peer: $peerName • AES-256-GCM + 96-bit IV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_inspect_cipher), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = inspectRawCipher, onCheckedChange = { onToggleInspectCipher() })
                }
                OutlinedButton(onClick = onRotateKey, enabled = !isBusy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rotate Session Key")
                }
                TextButton(
                    onClick = onDeleteThread,
                    enabled = !isBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete Direct Thread")
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Close") } }
    )
}
