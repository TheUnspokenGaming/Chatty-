package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ChatRoom
import com.example.data.DirectThread
import com.example.data.NexusAdvancedRepository
import com.example.data.NexusRepository
import com.example.data.ThemePreferences
import com.example.data.UiState
import com.example.ui.NexusViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutNexus
import com.example.ui.chat.RoomChatScreen
import com.example.ui.direct.DirectChatScreen
import com.example.ui.direct.DirectMessagesScreen
import com.example.ui.identity.IdentityScreen
import com.example.ui.rooms.RoomsScreen
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.NexusThemeMode
import com.example.ui.vault.VaultScreen
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val themePrefs = remember(context) { ThemePreferences(context) }
            val themeMode by themePrefs.themeModeFlow.collectAsStateWithLifecycle(initialValue = NexusThemeMode.OBSIDIAN)
            val scope = rememberCoroutineScope()

            NexusTheme(themeMode = themeMode) {
                NexusRootNavigation(
                    themeMode = themeMode,
                    onSelectThemeMode = { mode ->
                        scope.launch { themePrefs.setThemeMode(mode) }
                    }
                )
            }
        }
    }
}

private enum class NexusDestination(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTagName: String
) {
    ROOMS(R.string.nav_rooms, Icons.Rounded.Forum, Icons.Outlined.Forum, "nav_rooms"),
    DIRECT(R.string.nav_direct, Icons.Rounded.MarkChatUnread, Icons.Outlined.MarkChatUnread, "nav_direct"),
    VAULT(R.string.nav_vault, Icons.Rounded.Shield, Icons.Outlined.Shield, "nav_vault"),
    IDENTITY(R.string.nav_identity, Icons.Rounded.Fingerprint, Icons.Outlined.Fingerprint, "nav_identity")
}

@Composable
fun NexusRootNavigation(
    themeMode: NexusThemeMode,
    onSelectThemeMode: (NexusThemeMode) -> Unit,
    auth: FirebaseAuth = Firebase.auth
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            NexusAuthenticatedShell(
                user = user,
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode
            )
        }
    }
}

@Composable
private fun NexusAuthenticatedShell(
    user: FirebaseUser,
    themeMode: NexusThemeMode,
    onSelectThemeMode: (NexusThemeMode) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: NexusViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                NexusViewModel(
                    appContext = app.applicationContext,
                    repository = NexusRepository(db),
                    advancedRepository = NexusAdvancedRepository(db),
                    currentUserId = user.uid
                )
            }
        }
    )

    val roomsState by viewModel.roomsState.collectAsStateWithLifecycle()
    val mediaDropsState by viewModel.mediaDropsState.collectAsStateWithLifecycle()
    val identityState by viewModel.identityState.collectAsStateWithLifecycle()
    val activeRoom by viewModel.activeRoom.collectAsStateWithLifecycle()
    val activeRoomChannels by viewModel.activeRoomChannels.collectAsStateWithLifecycle()
    val activeChannel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeRoomMessagesState.collectAsStateWithLifecycle()
    val selectedRoomId by viewModel.selectedRoomId.collectAsStateWithLifecycle()

    val directThreadsState by viewModel.directThreadsState.collectAsStateWithLifecycle()
    val activeDirectThread by viewModel.activeDirectThread.collectAsStateWithLifecycle()
    val activeDirectMessagesState by viewModel.activeDirectMessagesState.collectAsStateWithLifecycle()
    val selectedDirectThreadId by viewModel.selectedDirectThreadId.collectAsStateWithLifecycle()

    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val isActionBusy by viewModel.isActionBusy.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val inspectRawCipher by viewModel.inspectRawCipher.collectAsStateWithLifecycle()

    val isRecordingVoice by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val playingMessageId by viewModel.playingMessageId.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(NexusDestination.ROOMS) }

    LaunchedEffect(actionMessage) {
        val msg = actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearActionMessage()
    }

    val roomsById = remember(roomsState) {
        (roomsState as? UiState.Success)?.data?.associateBy { it.id }.orEmpty()
    }
    val directThreadsById = remember(directThreadsState) {
        (directThreadsState as? UiState.Success)?.data?.associateBy { it.id }.orEmpty()
    }
    val unreadDirectCount = remember(directThreadsState, user.uid) {
        (directThreadsState as? UiState.Success)?.data?.count { it.hasUnreadFor(user.uid) } ?: 0
    }
    val activeDropCount = remember(mediaDropsState) {
        val now = System.currentTimeMillis()
        (mediaDropsState as? UiState.Success)?.data?.count { !it.isBurned() && !it.isExpiredAt(now) } ?: 0
    }
    val myContactCode = (identityState as? UiState.Success)?.data?.contactCode

    @Composable
    fun RenderRoomChat(room: ChatRoom, showBack: Boolean, modifier: Modifier = Modifier) {
        val channelId = activeChannel?.id ?: "general"
        RoomChatScreen(
            room = room,
            channels = activeRoomChannels,
            activeChannel = activeChannel,
            currentUserId = user.uid,
            messagesState = activeMessagesState,
            isOnline = isOnline,
            inspectRawCipher = inspectRawCipher,
            isActionBusy = isActionBusy,
            uploadProgress = uploadProgress,
            isRecordingVoice = isRecordingVoice,
            recordingDurationMs = recordingDurationMs,
            micAmplitude = micAmplitude,
            playingMessageId = playingMessageId,
            playbackProgress = playbackProgress,
            showBackButton = showBack,
            onBack = { viewModel.closeRoom() },
            onSelectChannel = { viewModel.selectChannel(it) },
            onCreateChannel = { name, type, topic -> viewModel.createChannel(room, name, type, topic) },
            onJoinVoice = { ch -> viewModel.joinVoiceChannel(room.id, ch) },
            onLeaveVoice = { ch -> viewModel.leaveVoiceChannel(room.id, ch) },
            onToggleVoiceMute = { ch -> viewModel.toggleVoiceMute(room.id, ch) },
            onToggleVoiceRaiseHand = { ch -> viewModel.toggleVoiceRaiseHand(room.id, ch) },
            onStartVoiceRecord = { isLounge ->
                viewModel.startVoiceRecording(
                    roomId = if (isLounge) room.id else null,
                    voiceChannelId = if (isLounge) channelId else null
                )
            },
            onStopAndSendVoice = { isLounge ->
                viewModel.stopAndSendRoomVoice(room, channelId, isVoiceLounge = isLounge)
            },
            onCancelVoiceRecord = { isLounge ->
                viewModel.cancelVoiceRecording(
                    roomId = if (isLounge) room.id else null,
                    voiceChannelId = if (isLounge) channelId else null
                )
            },
            onTogglePlayVoice = { msg ->
                viewModel.togglePlayEncryptedAudio(
                    messageId = msg.id,
                    encryptedAudioBase64 = msg.encryptedAudioBase64,
                    audioIvBase64 = msg.audioIvBase64,
                    keyBase64 = room.roomKeyBase64
                )
            },
            onSendMessage = { text, replyTo -> viewModel.sendRichRoomMessage(room, channelId, text, replyTo) },
            onTypingChanged = { draft -> viewModel.onRoomTypingChanged(room, draft) },
            onClearTyping = { viewModel.clearRoomTyping(room) },
            onCreatePoll = { q, opts -> viewModel.createEncryptedPoll(room, channelId, q, opts) },
            onVotePoll = { msg, idx -> viewModel.voteInPoll(room.id, msg, idx) },
            onToggleReaction = { msg, emoji -> viewModel.toggleRoomReaction(room.id, msg, emoji) },
            onTogglePin = { msg -> viewModel.toggleRoomPin(room.id, msg) },
            onEditMessage = { msg, newText -> viewModel.editRoomMessage(room, msg, newText) },
            onBurnMessage = { msg -> viewModel.burnMessage(room.id, msg) },
            onInspectMediaUri = { uri -> viewModel.inspectSelectedMedia(context, uri) },
            onPublishMediaDrop = { selection, ttl, burn, caption, onDone ->
                viewModel.shareEncryptedMediaDrop(context, room, channelId, selection, ttl, burn, caption, onDone)
            },
            onDecryptPreview = { drop -> viewModel.decryptDropPreviewBytes(drop, room.roomKeyBase64) },
            onMarkDropViewed = { drop -> viewModel.markDropViewed(context, drop) },
            onBurnDrop = { drop -> viewModel.burnMediaDrop(context, drop) },
            onToggleInspectCipher = { viewModel.toggleInspectRawCipher() },
            onUpdateMemberRole = { targetUid, role -> viewModel.updateMemberRole(room, targetUid, role) },
            onKickMember = { targetUid -> viewModel.kickRoomMember(room, targetUid) },
            onUpdateSlowMode = { secs -> viewModel.updateSlowMode(room, secs) },
            onRotateRoomKey = { viewModel.rotateRoomKey(room) },
            onLeaveRoom = { viewModel.leaveRoom(room) },
            onDeleteRoom = { viewModel.deleteRoom(room) },
            modifier = modifier
        )
    }

    @Composable
    fun RenderDirectChat(thread: DirectThread, showBack: Boolean, modifier: Modifier = Modifier) {
        DirectChatScreen(
            thread = thread,
            currentUserId = user.uid,
            messagesState = activeDirectMessagesState,
            isOnline = isOnline,
            inspectRawCipher = inspectRawCipher,
            isActionBusy = isActionBusy,
            uploadProgress = uploadProgress,
            isRecordingVoice = isRecordingVoice,
            recordingDurationMs = recordingDurationMs,
            micAmplitude = micAmplitude,
            playingMessageId = playingMessageId,
            playbackProgress = playbackProgress,
            showBackButton = showBack,
            onBack = { viewModel.closeDirectThread() },
            onSendMessage = { text, replyTo -> viewModel.sendRichDirectMessage(thread, text, replyTo) },
            onStartVoiceRecord = { viewModel.startVoiceRecording() },
            onStopAndSendVoice = { viewModel.stopAndSendDirectVoice(thread) },
            onCancelVoiceRecord = { viewModel.cancelVoiceRecording() },
            onTogglePlayVoice = { msg ->
                viewModel.togglePlayEncryptedAudio(
                    messageId = msg.id,
                    encryptedAudioBase64 = msg.encryptedAudioBase64,
                    audioIvBase64 = msg.audioIvBase64,
                    keyBase64 = thread.threadKeyBase64
                )
            },
            onToggleReaction = { msg, emoji -> viewModel.toggleDirectReaction(thread.id, msg, emoji) },
            onTogglePin = { msg -> viewModel.toggleDirectPin(thread.id, msg) },
            onEditMessage = { msg, newText -> viewModel.editDirectMessage(thread, msg, newText) },
            onInspectMediaUri = { uri -> viewModel.inspectSelectedMedia(context, uri) },
            onPublishMediaDrop = { selection, ttl, burn, caption, onDone ->
                viewModel.shareEncryptedDirectMediaDrop(context, thread, selection, ttl, burn, caption, onDone)
            },
            onDecryptPreview = { drop -> viewModel.decryptDropPreviewBytes(drop, thread.threadKeyBase64) },
            onMarkDropViewed = { drop -> viewModel.markDropViewed(context, drop) },
            onBurnDrop = { drop -> viewModel.burnMediaDrop(context, drop) },
            onTypingChanged = { draft -> viewModel.onDirectTypingChanged(thread, draft) },
            onClearTyping = { viewModel.clearDirectTyping(thread) },
            onMarkMessagesRead = { unread -> viewModel.markDirectMessagesRead(thread, unread) },
            onBurnMessage = { msg -> viewModel.burnDirectMessage(thread.id, msg) },
            onToggleInspectCipher = { viewModel.toggleInspectRawCipher() },
            onRotateKey = { viewModel.rotateDirectThreadKey(thread) },
            onDeleteThread = { viewModel.deleteDirectThread(thread) },
            modifier = modifier
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 720.dp

        // Compact phone full-screen active room chat
        if (!isWideScreen && currentTab == NexusDestination.ROOMS && activeRoom != null) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                RenderRoomChat(
                    room = activeRoom!!,
                    showBack = true,
                    modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
                )
            }
            return@BoxWithConstraints
        }

        // Compact phone full-screen active direct chat
        if (!isWideScreen && currentTab == NexusDestination.DIRECT && activeDirectThread != null) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                RenderDirectChat(
                    thread = activeDirectThread!!,
                    showBack = true,
                    modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
                )
            }
            return@BoxWithConstraints
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NexusDestination.entries.forEach { dest ->
                            val selected = currentTab == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = dest },
                                icon = {
                                    DestinationIcon(
                                        dest = dest,
                                        selected = selected,
                                        unreadDirectCount = unreadDirectCount
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelLarge) },
                                modifier = Modifier.testTag(dest.testTagName)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NexusDestination.entries.forEach { dest ->
                            val selected = currentTab == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = dest },
                                icon = {
                                    DestinationIcon(
                                        dest = dest,
                                        selected = selected,
                                        unreadDirectCount = unreadDirectCount
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTagName)
                            )
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                when (currentTab) {
                    NexusDestination.ROOMS -> {
                        if (isWideScreen) {
                            RoomsScreen(
                                roomsState = roomsState,
                                activeDropCount = activeDropCount,
                                selectedRoomId = selectedRoomId,
                                isOnline = isOnline,
                                isActionBusy = isActionBusy,
                                decryptPreview = { viewModel.decryptRoomPreview(it) },
                                onRoomClick = { viewModel.openRoom(it) },
                                onCreateRoom = { name, topic, accent, ttl ->
                                    viewModel.createRoom(name, topic, accent, ttl)
                                },
                                onJoinRoomByCode = { viewModel.joinRoomByCode(it) },
                                modifier = Modifier
                                    .width(360.dp)
                                    .fillMaxHeight()
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                val room = activeRoom
                                if (room != null) {
                                    RenderRoomChat(room = room, showBack = false)
                                } else {
                                    Text(
                                        text = "Select an encrypted space to open its channels & voice lounge",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                        } else {
                            RoomsScreen(
                                roomsState = roomsState,
                                activeDropCount = activeDropCount,
                                selectedRoomId = selectedRoomId,
                                isOnline = isOnline,
                                isActionBusy = isActionBusy,
                                decryptPreview = { viewModel.decryptRoomPreview(it) },
                                onRoomClick = { viewModel.openRoom(it) },
                                onCreateRoom = { name, topic, accent, ttl ->
                                    viewModel.createRoom(name, topic, accent, ttl)
                                },
                                onJoinRoomByCode = { viewModel.joinRoomByCode(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    NexusDestination.DIRECT -> {
                        if (isWideScreen) {
                            DirectMessagesScreen(
                                threadsState = directThreadsState,
                                currentUserId = user.uid,
                                myContactCode = myContactCode,
                                selectedThreadId = selectedDirectThreadId,
                                isOnline = isOnline,
                                isActionBusy = isActionBusy,
                                decryptPreview = { viewModel.decryptDirectPreview(it) },
                                onThreadClick = { viewModel.openDirectThread(it) },
                                onStartDirectThread = { viewModel.startDirectThread(it) },
                                modifier = Modifier
                                    .width(360.dp)
                                    .fillMaxHeight()
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                val thread = activeDirectThread
                                if (thread != null) {
                                    RenderDirectChat(thread = thread, showBack = false)
                                } else {
                                    Text(
                                        text = "Select a direct thread or enter a Contact Code to chat privately",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                        } else {
                            DirectMessagesScreen(
                                threadsState = directThreadsState,
                                currentUserId = user.uid,
                                myContactCode = myContactCode,
                                selectedThreadId = selectedDirectThreadId,
                                isOnline = isOnline,
                                isActionBusy = isActionBusy,
                                decryptPreview = { viewModel.decryptDirectPreview(it) },
                                onThreadClick = { viewModel.openDirectThread(it) },
                                onStartDirectThread = { viewModel.startDirectThread(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    NexusDestination.VAULT -> {
                        VaultScreen(
                            dropsState = mediaDropsState,
                            currentUserId = user.uid,
                            onDecryptPreview = { drop ->
                                val key = roomsById[drop.roomId]?.roomKeyBase64
                                    ?: directThreadsById[drop.roomId]?.threadKeyBase64
                                viewModel.decryptDropPreviewBytes(drop, key)
                            },
                            onMarkDropViewed = { drop -> viewModel.markDropViewed(context, drop) },
                            onBurnDrop = { drop -> viewModel.burnMediaDrop(context, drop) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    NexusDestination.IDENTITY -> {
                        IdentityScreen(
                            identityState = identityState,
                            userEmail = user.email,
                            fallbackDisplayName = user.displayName ?: user.email?.substringBefore("@") ?: "Chatty Member",
                            fallbackPhotoUrl = user.photoUrl?.toString(),
                            themeMode = themeMode,
                            isBusy = isActionBusy,
                            onSelectThemeMode = onSelectThemeMode,
                            onUpdatePresence = { status, statusText ->
                                viewModel.updatePresenceStatus(status, statusText)
                            },
                            onRotateIdentityKey = { viewModel.rotateIdentityKey() },
                            onSignOut = {
                                signOutNexus(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(
    dest: NexusDestination,
    selected: Boolean,
    unreadDirectCount: Int
) {
    val icon = if (selected) dest.selectedIcon else dest.unselectedIcon
    if (dest == NexusDestination.DIRECT && unreadDirectCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(if (unreadDirectCount > 9) "9+" else unreadDirectCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = stringResource(dest.labelRes))
        }
    } else {
        Icon(imageVector = icon, contentDescription = stringResource(dest.labelRes))
    }
}
