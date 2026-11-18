package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.crypto.NexusCryptoEngine
import com.example.data.ChatRoom
import com.example.data.DecryptedMessageItem
import com.example.data.EncryptedMessage
import com.example.data.MediaDrop
import com.example.data.PendingMediaSelection
import com.example.data.ReplyTarget
import com.example.data.RoomChannel
import com.example.data.UiState
import com.example.ui.components.DecryptedMediaInspectDialog
import com.example.ui.components.EmbeddedMediaDropCard
import com.example.ui.components.EncryptedMediaDropDialog
import com.example.ui.components.EncryptedPollCard
import com.example.ui.components.EncryptedVoicePlayerCard
import com.example.ui.components.MessageEditDialog
import com.example.ui.components.MessageReactionBar
import com.example.ui.components.NexusPillBadge
import com.example.ui.components.OfflineQueueBanner
import com.example.ui.components.ReplyQuoteSnippet
import com.example.ui.components.RoomEmblem
import com.example.ui.components.VoiceRecordingBar
import com.example.ui.components.formatExactClock
import kotlinx.coroutines.delay

@Composable
fun RoomChatScreen(
    room: ChatRoom,
    channels: List<RoomChannel>,
    activeChannel: RoomChannel?,
    currentUserId: String,
    messagesState: UiState<List<DecryptedMessageItem>>,
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
    onSelectChannel: (String) -> Unit,
    onCreateChannel: (name: String, type: String, topic: String) -> Unit,
    onJoinVoice: (RoomChannel) -> Unit,
    onLeaveVoice: (RoomChannel) -> Unit,
    onToggleVoiceMute: (RoomChannel) -> Unit,
    onToggleVoiceRaiseHand: (RoomChannel) -> Unit,
    onStartVoiceRecord: (isVoiceLounge: Boolean) -> Unit,
    onStopAndSendVoice: (isVoiceLounge: Boolean) -> Unit,
    onCancelVoiceRecord: (isVoiceLounge: Boolean) -> Unit,
    onTogglePlayVoice: (EncryptedMessage) -> Unit,
    onSendMessage: (text: String, replyTo: ReplyTarget?) -> Unit,
    onTypingChanged: (String) -> Unit,
    onClearTyping: () -> Unit,
    onCreatePoll: (question: String, options: List<String>) -> Unit,
    onVotePoll: (EncryptedMessage, Int) -> Unit,
    onToggleReaction: (EncryptedMessage, String) -> Unit,
    onTogglePin: (EncryptedMessage) -> Unit,
    onEditMessage: (EncryptedMessage, String) -> Unit,
    onBurnMessage: (EncryptedMessage) -> Unit,
    onInspectMediaUri: (Uri) -> PendingMediaSelection,
    onPublishMediaDrop: (PendingMediaSelection, Int, Boolean, String, () -> Unit) -> Unit,
    onDecryptPreview: (MediaDrop) -> ByteArray?,
    onMarkDropViewed: (MediaDrop) -> Unit,
    onBurnDrop: (MediaDrop) -> Unit,
    onToggleInspectCipher: () -> Unit,
    onUpdateMemberRole: (targetUid: String, role: String) -> Unit,
    onKickMember: (targetUid: String) -> Unit,
    onUpdateSlowMode: (Int) -> Unit,
    onRotateRoomKey: () -> Unit,
    onLeaveRoom: () -> Unit,
    onDeleteRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = showBackButton) {
        onBack()
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val effectiveChannelId = activeChannel?.id ?: "general"
    val isVoiceLounge = activeChannel?.isVoice() == true
    val isOwnerOrAdmin = room.isOwnerOrAdmin(currentUserId)

    var draftText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<ReplyTarget?>(null) }
    var editingItem by remember { mutableStateOf<DecryptedMessageItem?>(null) }

    var showSecurityDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showPinnedDialog by remember { mutableStateOf(false) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var showCreatePollDialog by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<PendingMediaSelection?>(null) }
    var inspectingDrop by remember { mutableStateOf<MediaDrop?>(null) }
    var pendingVoiceIsLounge by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    DisposableEffect(room.id) {
        onDispose { onClearTyping() }
    }

    // Runtime RECORD_AUDIO permission request for hardware E2EE voice
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceRecord(pendingVoiceIsLounge)
        }
    }

    fun requestOrStartRecording(isLounge: Boolean) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            onStartVoiceRecord(isLounge)
        } else {
            pendingVoiceIsLounge = isLounge
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingSelection = onInspectMediaUri(uri) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingSelection = onInspectMediaUri(uri) }

    val allMessages = (messagesState as? UiState.Success)?.data.orEmpty()
    val channelMessages = remember(allMessages, effectiveChannelId) {
        allMessages.filter { it.message.effectiveChannelId() == effectiveChannelId }
    }
    val pinnedMessages = remember(channelMessages) {
        channelMessages.filter { it.message.pinned == true && it.message.status == "active" }
    }
    val filteredMessages = remember(channelMessages, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) channelMessages
        else channelMessages.filter {
            it.decryptedText.contains(q, ignoreCase = true) ||
                it.message.senderName.contains(q, ignoreCase = true)
        }
    }

    val typingPeers = room.activeTypingPeers(currentUserId, nowMillis)

    LaunchedEffect(filteredMessages.size, effectiveChannelId) {
        if (filteredMessages.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(filteredMessages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Minimalist Architectural Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("chat_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                    RoomEmblem(roomName = room.name, accentTag = room.accentTag, size = 38.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                            val slowSuffix = if ((room.slowModeSeconds ?: 0L) > 0L) " • ${room.slowModeSeconds}s slow" else ""
                            Text(
                                text = "AES-256 • ${room.memberUids.size} members$slowSuffix",
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
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showPinnedDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.cd_pinned),
                            tint = if (pinnedMessages.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showMembersDialog = true },
                        modifier = Modifier.testTag("room_members_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Group,
                            contentDescription = stringResource(R.string.cd_members),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showSecurityDialog = true },
                        modifier = Modifier.testTag("room_security_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = stringResource(R.string.cd_room_security),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Multi-Channel Card Pill Deck (replaces Discord's cluttered drawer)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(channels, key = { it.id }) { ch ->
                        val selected = ch.id == effectiveChannelId
                        val chIcon = when {
                            ch.isVoice() -> Icons.Rounded.Headphones
                            ch.isAnnouncement() -> Icons.Rounded.Campaign
                            else -> Icons.Rounded.Tag
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectChannel(ch.id) }
                                .testTag("channel_pill_${ch.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = chIcon,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = ch.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (ch.isVoice() && ch.voiceParticipantUids.isNotEmpty()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text(
                                            text = ch.voiceParticipantUids.size.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isOwnerOrAdmin) {
                        item(key = "add_channel_chip") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showCreateChannelDialog = true }
                                    .testTag("add_channel_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_new_channel), modifier = Modifier.size(14.dp))
                                    Text("Channel", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        OfflineQueueBanner(isOnline = isOnline)

        // Collapsible Instant Client-Side Decrypted Search Bar
        AnimatedVisibility(visible = showSearchBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                        .testTag("room_search_input")
                )
            }
        }

        // Cipher Inspection Banner
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
                        text = "CIPHER INSPECTOR ACTIVE — SHOWING WIRE PAYLOAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = onToggleInspectCipher) {
                        Text("Hide", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Voice Lounge Stage Card when viewing a Voice Channel
        if (isVoiceLounge && activeChannel != null) {
            VoiceLoungePanel(
                channel = activeChannel,
                currentUserId = currentUserId,
                isRecording = isRecordingVoice,
                recordingDurationMs = recordingDurationMs,
                micAmplitude = micAmplitude,
                onJoinVoice = { onJoinVoice(activeChannel) },
                onLeaveVoice = { onLeaveVoice(activeChannel) },
                onToggleMute = { onToggleVoiceMute(activeChannel) },
                onToggleRaiseHand = { onToggleVoiceRaiseHand(activeChannel) },
                onStartPushToTalk = { requestOrStartRecording(isLounge = true) },
                onStopAndBroadcastVoice = { onStopAndSendVoice(true) },
                onCancelVoice = { onCancelVoiceRecord(true) }
            )
        }

        // Card-Based Message Stream
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (messagesState) {
                is UiState.Loading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
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
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (filteredMessages.isEmpty()) {
                            item(key = "empty_channel_state") {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) {
                                            "No decrypted messages match \"$searchQuery\"."
                                        } else if (isVoiceLounge) {
                                            "Voice bursts broadcast in this lounge will appear here for instant encrypted playback."
                                        } else {
                                            "Channel #${activeChannel?.name ?: "general"} is encrypted with AES-256-GCM. Start the conversation, launch a poll, or share files up to 4.00 GB."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }

                        items(filteredMessages, key = { it.message.id }) { item ->
                            MessageCard(
                                item = item,
                                senderRole = room.roleOf(item.message.senderId),
                                isSelf = item.message.senderId == currentUserId,
                                currentUserId = currentUserId,
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
                                onVotePoll = { idx -> onVotePoll(item.message, idx) },
                                onBurnMessage = { onBurnMessage(item.message) },
                                onOpenMediaDrop = { drop ->
                                    inspectingDrop = drop
                                    onMarkDropViewed(drop)
                                },
                                onBurnDrop = onBurnDrop
                            )
                        }

                        if (typingPeers.isNotEmpty()) {
                            item(key = "room_typing_indicator") {
                                val names = typingPeers.map { uid -> room.memberNames?.get(uid) ?: "Someone" }
                                val label = if (names.size == 1) "${names.first()} is typing…" else "${names.joinToString(", ")} are typing…"
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Minimalist Card Composer Bar
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

                if (isRecordingVoice && !isVoiceLounge) {
                    VoiceRecordingBar(
                        durationMs = recordingDurationMs,
                        amplitude = micAmplitude,
                        onCancel = { onCancelVoiceRecord(false) },
                        onSend = { onStopAndSendVoice(false) }
                    )
                }

                // Quick Attachment & Feature Toolbar
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
                        },
                        modifier = Modifier.testTag("attach_photo_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = stringResource(R.string.cd_attach_photo), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    }
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("attach_file_button")
                    ) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.cd_attach_file), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(21.dp))
                    }
                    IconButton(
                        onClick = { showCreatePollDialog = true },
                        modifier = Modifier.testTag("create_poll_button")
                    ) {
                        Icon(Icons.Rounded.Poll, contentDescription = stringResource(R.string.cd_create_poll), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(21.dp))
                    }
                    IconButton(
                        onClick = {
                            if (isRecordingVoice) onStopAndSendVoice(isVoiceLounge)
                            else requestOrStartRecording(isLounge = isVoiceLounge)
                        },
                        modifier = Modifier.testTag("record_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.cd_voice_record),
                            tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "#${activeChannel?.name ?: "general"} • 4GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                text = stringResource(R.string.label_message_placeholder),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
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
                            modifier = Modifier.testTag("send_message_button")
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

    // Dialogs
    pendingSelection?.let { selection ->
        EncryptedMediaDropDialog(
            selection = selection,
            defaultTtlMinutes = room.defaultTtlMinutes.toInt(),
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
            onConfirm = { updatedText ->
                onEditMessage(target.message, updatedText)
                editingItem = null
            }
        )
    }

    if (showCreateChannelDialog) {
        CreateChannelDialog(
            isBusy = isActionBusy,
            onDismiss = { showCreateChannelDialog = false },
            onConfirm = { name, type, topic ->
                onCreateChannel(name, type, topic)
                showCreateChannelDialog = false
            }
        )
    }

    if (showCreatePollDialog) {
        CreatePollDialog(
            isBusy = isActionBusy,
            onDismiss = { showCreatePollDialog = false },
            onConfirm = { question, options ->
                onCreatePoll(question, options)
                showCreatePollDialog = false
            }
        )
    }

    if (showPinnedDialog) {
        PinnedMessagesDialog(
            pinnedItems = pinnedMessages,
            onUnpin = { onTogglePin(it.message) },
            onDismiss = { showPinnedDialog = false }
        )
    }

    if (showMembersDialog) {
        MembersAndRolesDialog(
            room = room,
            currentUserId = currentUserId,
            isBusy = isActionBusy,
            onUpdateRole = onUpdateMemberRole,
            onKickMember = onKickMember,
            onUpdateSlowMode = onUpdateSlowMode,
            onDismiss = { showMembersDialog = false }
        )
    }

    if (showSecurityDialog) {
        RoomSecurityDialog(
            room = room,
            isOwner = room.ownerId == currentUserId,
            inspectRawCipher = inspectRawCipher,
            isBusy = isActionBusy,
            onCopyInviteCode = { clipboard.setText(AnnotatedString(room.inviteCode)) },
            onToggleInspectCipher = onToggleInspectCipher,
            onRotateKey = onRotateRoomKey,
            onLeaveRoom = {
                showSecurityDialog = false
                onLeaveRoom()
            },
            onDeleteRoom = {
                showSecurityDialog = false
                onDeleteRoom()
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
private fun MessageCard(
    item: DecryptedMessageItem,
    senderRole: String,
    isSelf: Boolean,
    currentUserId: String,
    inspectRawCipher: Boolean,
    nowMillis: Long,
    isPlayingVoice: Boolean,
    playbackProgress: Float,
    onTogglePlayVoice: () -> Unit,
    onReply: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onVotePoll: (Int) -> Unit,
    onBurnMessage: () -> Unit,
    onOpenMediaDrop: (MediaDrop) -> Unit,
    onBurnDrop: (MediaDrop) -> Unit
) {
    val msg = item.message
    val isSystem = msg.messageType == "system"
    val isBurned = msg.status == "burned"
    var showReactionPicker by remember(msg.id) { mutableStateOf(false) }

    if (isSystem) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.EnhancedEncryption, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(item.decryptedText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
    }

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
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
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
                    if (senderRole != "member") {
                        NexusPillBadge(text = senderRole.uppercase())
                    }
                    if (msg.pinned == true) {
                        Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        text = formatExactClock(msg.createdAt) + if (msg.edited == true) " • edited" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Compact Action Strip
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
                        if (isSelf && msg.messageType == "text") {
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

            // Reply Quote
            if (!isBurned && !msg.replyToSenderName.isNullOrBlank()) {
                ReplyQuoteSnippet(
                    senderName = msg.replyToSenderName,
                    snippetText = item.decryptedReplySnippet ?: "Encrypted message"
                )
            }

            // Body Content
            Text(
                text = item.decryptedText,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isBurned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            // Interactive Poll
            if (!isBurned && item.decryptedPoll != null) {
                EncryptedPollCard(
                    poll = item.decryptedPoll,
                    votes = msg.pollVotes,
                    currentUserId = currentUserId,
                    onVote = onVotePoll
                )
            }

            // Encrypted Voice Clip Player
            if (!isBurned && msg.messageType == "voice" && !msg.encryptedAudioBase64.isNullOrBlank()) {
                EncryptedVoicePlayerCard(
                    durationMs = msg.audioDurationMs ?: 1000L,
                    isPlaying = isPlayingVoice,
                    progress = playbackProgress,
                    onTogglePlay = onTogglePlayVoice
                )
            }

            // Embedded Temporary Media Drop (4GB)
            item.linkedDrop?.let { drop ->
                EmbeddedMediaDropCard(
                    drop = drop,
                    isSelf = isSelf,
                    nowMillis = nowMillis,
                    onDecryptClick = { onOpenMediaDrop(drop) },
                    onBurnClick = { onBurnDrop(drop) }
                )
            }

            // Emoji Reactions
            if (!isBurned) {
                MessageReactionBar(
                    reactions = msg.reactions,
                    currentUserId = currentUserId,
                    showPicker = showReactionPicker,
                    onTogglePicker = { showReactionPicker = !showReactionPicker },
                    onToggleEmoji = onToggleReaction
                )
            }

            // Cipher Inspector
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
