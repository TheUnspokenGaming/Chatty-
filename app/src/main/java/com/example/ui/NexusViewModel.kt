package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.NexusVoiceEngine
import com.example.crypto.NexusCryptoEngine
import com.example.data.ChatRoom
import com.example.data.DecryptedDirectMessageItem
import com.example.data.DecryptedMessageItem
import com.example.data.DirectMessage
import com.example.data.DirectThread
import com.example.data.EncryptedMessage
import com.example.data.MediaDrop
import com.example.data.NetworkMonitor
import com.example.data.NexusAdvancedRepository
import com.example.data.NexusLimits
import com.example.data.NexusRepository
import com.example.data.PendingMediaSelection
import com.example.data.ReplyTarget
import com.example.data.RoomChannel
import com.example.data.UiState
import com.example.data.UserIdentity
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NexusViewModel(
    private val appContext: Context,
    private val repository: NexusRepository,
    private val advancedRepository: NexusAdvancedRepository,
    val currentUserId: String
) : ViewModel() {

    private val voiceEngine = NexusVoiceEngine(appContext.applicationContext, viewModelScope)

    val isOnline: StateFlow<Boolean> = NetworkMonitor.observeIsOnline(appContext.applicationContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), true)

    val isRecordingVoice: StateFlow<Boolean> = voiceEngine.isRecording
    val recordingDurationMs: StateFlow<Long> = voiceEngine.recordingDurationMs
    val micAmplitude: StateFlow<Float> = voiceEngine.micAmplitude
    val playingMessageId: StateFlow<String?> = voiceEngine.playingMessageId
    val playbackProgress: StateFlow<Float> = voiceEngine.playbackProgress

    private val _selectedRoomId = MutableStateFlow<String?>(null)
    val selectedRoomId: StateFlow<String?> = _selectedRoomId.asStateFlow()

    private val _selectedChannelId = MutableStateFlow("general")
    val selectedChannelId: StateFlow<String> = _selectedChannelId.asStateFlow()

    private val _selectedDirectThreadId = MutableStateFlow<String?>(null)
    val selectedDirectThreadId: StateFlow<String?> = _selectedDirectThreadId.asStateFlow()

    private var directTypingClearJob: Job? = null
    private var roomTypingClearJob: Job? = null
    private var lastRoomMessageSentAtMs: Long = 0L

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isActionBusy = MutableStateFlow(false)
    val isActionBusy: StateFlow<Boolean> = _isActionBusy.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _inspectRawCipher = MutableStateFlow(false)
    val inspectRawCipher: StateFlow<Boolean> = _inspectRawCipher.asStateFlow()

    val identityState: StateFlow<UiState<UserIdentity?>> = repository.observeUserIdentity(currentUserId)
        .map<UserIdentity?, UiState<UserIdentity?>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing user identity", firestoreError ?: error)
            emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load identity"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val roomsState: StateFlow<UiState<List<ChatRoom>>> = repository.observeRooms(currentUserId)
        .map<List<ChatRoom>, UiState<List<ChatRoom>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing rooms", firestoreError ?: error)
            emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load encrypted rooms"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val mediaDropsState: StateFlow<UiState<List<MediaDrop>>> = repository.observeMediaDrops(currentUserId)
        .map<List<MediaDrop>, UiState<List<MediaDrop>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing media drops", firestoreError ?: error)
            emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load vault drops"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val activeRoom: StateFlow<ChatRoom?> = combine(roomsState, _selectedRoomId) { state, roomId ->
        if (roomId == null) null
        else (state as? UiState.Success)?.data?.firstOrNull { it.id == roomId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val activeRoomChannels: StateFlow<List<RoomChannel>> = _selectedRoomId.flatMapLatest { roomId ->
        if (roomId == null) {
            flowOf(emptyList())
        } else {
            advancedRepository.observeRoomChannels(roomId, currentUserId)
                .map { channels ->
                    channels.ifEmpty {
                        listOf(
                            RoomChannel(
                                id = "general",
                                roomId = roomId,
                                name = "general",
                                channelType = "text",
                                topic = "Primary encrypted card stream"
                            )
                        )
                    }
                }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing room channels for $roomId", firestoreError ?: error)
                    emit(
                        listOf(
                            RoomChannel(id = "general", roomId = roomId, name = "general", channelType = "text")
                        )
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val activeChannel: StateFlow<RoomChannel?> = combine(activeRoomChannels, _selectedChannelId) { list, chId ->
        list.firstOrNull { it.id == chId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val activeRoomMessagesState: StateFlow<UiState<List<DecryptedMessageItem>>> =
        combine(_selectedRoomId, activeRoom, mediaDropsState) { roomId, room, dropsState ->
            Triple(roomId, room, (dropsState as? UiState.Success)?.data.orEmpty())
        }.flatMapLatest { (roomId, room, allDrops) ->
            if (roomId == null || room == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeRoomMessages(roomId, currentUserId)
                    .map<List<EncryptedMessage>, UiState<List<DecryptedMessageItem>>> { messages ->
                        val dropMap = allDrops.associateBy { it.id }
                        val decrypted = messages.map { msg -> decryptRoomMessageItem(msg, room, dropMap) }
                        UiState.Success(decrypted)
                    }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Error observing messages for room $roomId", firestoreError ?: error)
                        emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load room messages"))
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val directThreadsState: StateFlow<UiState<List<DirectThread>>> = repository.observeDirectThreads(currentUserId)
        .map<List<DirectThread>, UiState<List<DirectThread>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing direct threads", firestoreError ?: error)
            emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load direct messages"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val activeDirectThread: StateFlow<DirectThread?> = combine(directThreadsState, _selectedDirectThreadId) { state, threadId ->
        if (threadId == null) null
        else (state as? UiState.Success)?.data?.firstOrNull { it.id == threadId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val activeDirectMessagesState: StateFlow<UiState<List<DecryptedDirectMessageItem>>> =
        combine(_selectedDirectThreadId, activeDirectThread, mediaDropsState) { threadId, thread, dropsState ->
            Triple(threadId, thread, (dropsState as? UiState.Success)?.data.orEmpty())
        }.flatMapLatest { (threadId, thread, allDrops) ->
            if (threadId == null || thread == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeDirectMessages(threadId, currentUserId)
                    .map<List<DirectMessage>, UiState<List<DecryptedDirectMessageItem>>> { messages ->
                        val dropMap = allDrops.associateBy { it.id }
                        val decrypted = messages.map { msg -> decryptDirectMessageItem(msg, thread, dropMap) }
                        UiState.Success(decrypted)
                    }
                    .catch { error ->
                        val firestoreError = unwrapFirestoreError(error)
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Error observing direct messages for $threadId", firestoreError ?: error)
                        emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load direct messages"))
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    init {
        viewModelScope.launch {
            repository.ensureUserIdentity()
        }
    }

    override fun onCleared() {
        voiceEngine.release()
        super.onCleared()
    }

    // --- NAVIGATION & CHANNEL SELECTION ---

    fun openRoom(roomId: String) {
        _selectedRoomId.value = roomId
        _selectedChannelId.value = "general"
        val room = (roomsState.value as? UiState.Success)?.data?.firstOrNull { it.id == roomId }
        if (room != null && room.isOwnerOrAdmin(currentUserId)) {
            viewModelScope.launch {
                advancedRepository.ensureDefaultChannelsForRoom(room)
            }
        }
    }

    fun selectChannel(channelId: String) {
        _selectedChannelId.value = channelId
    }

    fun closeRoom() {
        activeRoom.value?.let { clearRoomTyping(it) }
        voiceEngine.cancelRecording()
        voiceEngine.stopPlayback()
        _selectedRoomId.value = null
        _inspectRawCipher.value = false
    }

    fun openDirectThread(threadId: String) {
        _selectedDirectThreadId.value = threadId
    }

    fun closeDirectThread() {
        activeDirectThread.value?.let { clearDirectTyping(it) }
        voiceEngine.cancelRecording()
        voiceEngine.stopPlayback()
        _selectedDirectThreadId.value = null
        _inspectRawCipher.value = false
    }

    // --- PRESENCE STATUS ---

    fun updatePresenceStatus(status: String, statusText: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = advancedRepository.updatePresenceStatus(status, statusText)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { _actionMessage.value = "Presence updated to ${NexusLimits.formatPresenceLabel(status)}." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to update presence." }
            )
        }
    }

    // --- ROOM CHANNELS & VOICE LOUNGE ---

    fun createChannel(room: ChatRoom, name: String, channelType: String, topic: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = advancedRepository.createChannel(room, name, channelType, topic)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { newId ->
                    _selectedChannelId.value = newId
                    _actionMessage.value = "Channel \"$name\" created."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to create channel." }
            )
        }
    }

    fun joinVoiceChannel(roomId: String, channel: RoomChannel) {
        viewModelScope.launch {
            advancedRepository.joinVoiceChannel(roomId, channel).fold(
                onSuccess = { _actionMessage.value = "Joined encrypted Voice Lounge: ${channel.name}." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to join voice channel." }
            )
        }
    }

    fun leaveVoiceChannel(roomId: String, channel: RoomChannel) {
        voiceEngine.cancelRecording()
        voiceEngine.stopPlayback()
        viewModelScope.launch {
            advancedRepository.leaveVoiceChannel(roomId, channel).fold(
                onSuccess = { _actionMessage.value = "Left Voice Lounge." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to leave voice channel." }
            )
        }
    }

    fun toggleVoiceMute(roomId: String, channel: RoomChannel) {
        viewModelScope.launch {
            advancedRepository.toggleVoiceMute(roomId, channel)
        }
    }

    fun toggleVoiceRaiseHand(roomId: String, channel: RoomChannel) {
        viewModelScope.launch {
            advancedRepository.toggleVoiceRaiseHand(roomId, channel)
        }
    }

    fun startVoiceRecording(roomId: String? = null, voiceChannelId: String? = null) {
        voiceEngine.startRecording().fold(
            onSuccess = {
                if (roomId != null && voiceChannelId != null) {
                    viewModelScope.launch {
                        advancedRepository.setActiveVoiceSpeaker(roomId, voiceChannelId, speaking = true)
                    }
                }
            },
            onFailure = { err ->
                _actionMessage.value = err.message ?: "Microphone unavailable."
            }
        )
    }

    fun cancelVoiceRecording(roomId: String? = null, voiceChannelId: String? = null) {
        voiceEngine.cancelRecording()
        if (roomId != null && voiceChannelId != null) {
            viewModelScope.launch {
                advancedRepository.setActiveVoiceSpeaker(roomId, voiceChannelId, speaking = false)
            }
        }
    }

    fun stopAndSendRoomVoice(room: ChatRoom, channelId: String, isVoiceLounge: Boolean = false) {
        val clipResult = voiceEngine.stopRecording()
        if (isVoiceLounge) {
            viewModelScope.launch {
                advancedRepository.setActiveVoiceSpeaker(room.id, channelId, speaking = false)
            }
        }
        clipResult.fold(
            onSuccess = { clip ->
                viewModelScope.launch {
                    _isActionBusy.value = true
                    val res = advancedRepository.sendEncryptedVoiceToRoom(room, channelId, clip.audioBytes, clip.durationMs)
                    _isActionBusy.value = false
                    res.fold(
                        onSuccess = { _actionMessage.value = "Encrypted voice burst sent (${NexusLimits.formatDurationMs(clip.durationMs)})." },
                        onFailure = { err -> _actionMessage.value = err.message ?: "Failed to send voice clip." }
                    )
                }
            },
            onFailure = { err ->
                _actionMessage.value = err.message ?: "Voice clip too short."
            }
        )
    }

    fun stopAndSendDirectVoice(thread: DirectThread) {
        voiceEngine.stopRecording().fold(
            onSuccess = { clip ->
                viewModelScope.launch {
                    _isActionBusy.value = true
                    val res = advancedRepository.sendEncryptedVoiceToDirectThread(thread, clip.audioBytes, clip.durationMs)
                    _isActionBusy.value = false
                    res.fold(
                        onSuccess = { _actionMessage.value = "Encrypted voice note sent." },
                        onFailure = { err -> _actionMessage.value = err.message ?: "Failed to send voice note." }
                    )
                }
            },
            onFailure = { err ->
                _actionMessage.value = err.message ?: "Voice note too short."
            }
        )
    }

    fun togglePlayEncryptedAudio(messageId: String, encryptedAudioBase64: String?, audioIvBase64: String?, keyBase64: String) {
        if (playingMessageId.value == messageId) {
            voiceEngine.stopPlayback()
            return
        }
        if (encryptedAudioBase64.isNullOrBlank() || audioIvBase64.isNullOrBlank()) {
            _actionMessage.value = "Encrypted audio payload unavailable."
            return
        }
        val decrypted = runCatching {
            NexusCryptoEngine.decryptBytes(encryptedAudioBase64, audioIvBase64, keyBase64)
        }.getOrElse {
            _actionMessage.value = "Unable to decrypt voice clip with current key."
            return
        }
        voiceEngine.togglePlayDecryptedAudio(messageId, decrypted).onFailure { err ->
            _actionMessage.value = err.message ?: "Unable to play voice clip."
        }
    }

    // --- ROOM TYPING, ROLES, SLOW-MODE, POLLS, REACTIONS, PINS, EDITS ---

    fun onRoomTypingChanged(room: ChatRoom, draft: String) {
        roomTypingClearJob?.cancel()
        if (draft.isBlank()) {
            clearRoomTyping(room)
            return
        }
        viewModelScope.launch {
            advancedRepository.setRoomTypingState(room, isTyping = true)
        }
        roomTypingClearJob = viewModelScope.launch {
            delay(3500L)
            val latest = activeRoom.value ?: room
            advancedRepository.setRoomTypingState(latest, isTyping = false)
        }
    }

    fun clearRoomTyping(room: ChatRoom) {
        roomTypingClearJob?.cancel()
        if (currentUserId !in room.typingUserIds.orEmpty()) return
        viewModelScope.launch {
            advancedRepository.setRoomTypingState(room, isTyping = false)
        }
    }

    fun sendRichRoomMessage(room: ChatRoom, channelId: String, text: String, replyTo: ReplyTarget?) {
        val slowSec = room.slowModeSeconds ?: 0L
        if (slowSec > 0L && !room.isOwnerOrAdmin(currentUserId)) {
            val elapsedMs = System.currentTimeMillis() - lastRoomMessageSentAtMs
            val waitMs = (slowSec * 1000L) - elapsedMs
            if (waitMs > 0L) {
                _actionMessage.value = "Slow-mode active: wait ${(waitMs / 1000L) + 1}s before sending."
                return
            }
        }
        roomTypingClearJob?.cancel()
        viewModelScope.launch {
            val result = advancedRepository.sendRichRoomMessage(room, channelId, text, replyTo)
            result.fold(
                onSuccess = { lastRoomMessageSentAtMs = System.currentTimeMillis() },
                onFailure = { err -> _actionMessage.value = err.message ?: "Failed to send encrypted message." }
            )
        }
    }

    fun createEncryptedPoll(room: ChatRoom, channelId: String, question: String, options: List<String>) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val res = advancedRepository.createEncryptedPoll(room, channelId, question, options)
            _isActionBusy.value = false
            res.fold(
                onSuccess = { _actionMessage.value = "Encrypted poll posted." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to create poll." }
            )
        }
    }

    fun voteInPoll(roomId: String, message: EncryptedMessage, optionIndex: Int) {
        viewModelScope.launch {
            advancedRepository.voteInPoll(roomId, message, optionIndex).onFailure { err ->
                _actionMessage.value = err.message ?: "Could not record vote."
            }
        }
    }

    fun toggleRoomReaction(roomId: String, message: EncryptedMessage, emoji: String) {
        viewModelScope.launch {
            advancedRepository.toggleRoomReaction(roomId, message, emoji)
        }
    }

    fun toggleRoomPin(roomId: String, message: EncryptedMessage) {
        viewModelScope.launch {
            advancedRepository.toggleRoomPin(roomId, message).fold(
                onSuccess = {
                    val nowPinned = !(message.pinned ?: false)
                    _actionMessage.value = if (nowPinned) "Message card pinned." else "Message card unpinned."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not update pin." }
            )
        }
    }

    fun editRoomMessage(room: ChatRoom, message: EncryptedMessage, newText: String) {
        viewModelScope.launch {
            advancedRepository.editRoomMessage(room, message, newText).fold(
                onSuccess = { _actionMessage.value = "Message re-encrypted & updated." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not edit message." }
            )
        }
    }

    fun updateMemberRole(room: ChatRoom, targetUid: String, role: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val res = advancedRepository.updateMemberRole(room, targetUid, role)
            _isActionBusy.value = false
            res.fold(
                onSuccess = { _actionMessage.value = "Role updated to ${role.uppercase()}." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to update role." }
            )
        }
    }

    fun kickRoomMember(room: ChatRoom, targetUid: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val res = advancedRepository.kickRoomMember(room, targetUid)
            _isActionBusy.value = false
            res.fold(
                onSuccess = { _actionMessage.value = "Member removed from room." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to remove member." }
            )
        }
    }

    fun updateSlowMode(room: ChatRoom, seconds: Int) {
        viewModelScope.launch {
            advancedRepository.updateSlowMode(room, seconds).fold(
                onSuccess = {
                    _actionMessage.value = if (seconds == 0) "Slow-mode disabled." else "Slow-mode set to ${seconds}s."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to update slow-mode." }
            )
        }
    }

    // --- DIRECT MESSAGING (TYPING, READ RECEIPTS, REPLIES, REACTIONS, EDITS, PINS, 4GB DROPS) ---

    fun startDirectThread(contactCode: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.startDirectThreadByContactCode(contactCode)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { threadId ->
                    _selectedDirectThreadId.value = threadId
                    _actionMessage.value = "End-to-end encrypted direct thread ready."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to start direct message." }
            )
        }
    }

    fun sendRichDirectMessage(thread: DirectThread, plaintext: String, replyTo: ReplyTarget?) {
        directTypingClearJob?.cancel()
        viewModelScope.launch {
            val result = advancedRepository.sendRichDirectMessage(thread, plaintext, replyTo)
            result.onFailure { err ->
                _actionMessage.value = err.message ?: "Failed to send direct message."
            }
        }
    }

    fun toggleDirectReaction(threadId: String, message: DirectMessage, emoji: String) {
        viewModelScope.launch {
            advancedRepository.toggleDirectReaction(threadId, message, emoji)
        }
    }

    fun toggleDirectPin(threadId: String, message: DirectMessage) {
        viewModelScope.launch {
            advancedRepository.toggleDirectPin(threadId, message).fold(
                onSuccess = {
                    val nowPinned = !(message.pinned ?: false)
                    _actionMessage.value = if (nowPinned) "Direct card pinned." else "Direct card unpinned."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not update pin." }
            )
        }
    }

    fun editDirectMessage(thread: DirectThread, message: DirectMessage, newText: String) {
        viewModelScope.launch {
            advancedRepository.editDirectMessage(thread, message, newText).fold(
                onSuccess = { _actionMessage.value = "Direct message re-encrypted & updated." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not edit message." }
            )
        }
    }

    fun shareEncryptedDirectMediaDrop(
        context: Context,
        thread: DirectThread,
        selection: PendingMediaSelection,
        ttlMinutes: Int,
        burnAfterView: Boolean,
        caption: String,
        onComplete: () -> Unit
    ) {
        if (selection.exceedsLimit || selection.fileSizeBytes > NexusLimits.MAX_FILE_SIZE_BYTES) {
            _actionMessage.value = "File exceeds the 4.00 GB maximum limit for optimal performance."
            return
        }
        if (selection.fileSizeBytes <= 0L) {
            _actionMessage.value = "Cannot encrypt an empty file."
            return
        }
        viewModelScope.launch {
            _isActionBusy.value = true
            _uploadProgress.value = 0.02f
            try {
                val dropId = repository.allocateDropId()
                val encrypted = NexusCryptoEngine.encryptMediaStream(
                    context = context.applicationContext,
                    sourceUri = Uri.parse(selection.uriString),
                    dropId = dropId,
                    mimeType = selection.mimeType,
                    expectedSizeBytes = selection.fileSizeBytes,
                    roomKeyBase64 = thread.threadKeyBase64,
                    onProgress = { progress -> _uploadProgress.value = progress }
                )
                val effectiveSize = encrypted.totalBytesProcessed
                if (effectiveSize > NexusLimits.MAX_FILE_SIZE_BYTES) {
                    NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, dropId)
                    _actionMessage.value = "File exceeds the 4.00 GB maximum limit."
                    return@launch
                }
                val result = advancedRepository.publishEncryptedDirectMediaDrop(
                    dropId = dropId,
                    thread = thread,
                    fileName = selection.fileName,
                    mimeType = selection.mimeType,
                    fileSizeBytes = effectiveSize,
                    sha256Hash = encrypted.sha256Hash,
                    ivBase64 = encrypted.ivBase64,
                    encryptedPreviewBase64 = encrypted.encryptedPreviewBase64,
                    previewIvBase64 = encrypted.previewIvBase64,
                    ttlMinutes = ttlMinutes,
                    burnAfterView = burnAfterView,
                    caption = caption
                )
                result.fold(
                    onSuccess = {
                        _actionMessage.value = "Encrypted DM drop sent (${NexusLimits.formatBytes(effectiveSize)})."
                        onComplete()
                    },
                    onFailure = { err ->
                        NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, dropId)
                        _actionMessage.value = err.message ?: "Failed to publish temporary drop."
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionMessage.value = e.localizedMessage ?: "Encryption error while processing media."
            } finally {
                _uploadProgress.value = null
                _isActionBusy.value = false
            }
        }
    }

    fun onDirectTypingChanged(thread: DirectThread, draft: String) {
        if (thread.isSelfThread(currentUserId)) return
        directTypingClearJob?.cancel()
        if (draft.isBlank()) {
            clearDirectTyping(thread)
            return
        }
        viewModelScope.launch {
            repository.setDirectTypingState(thread, isTyping = true)
        }
        directTypingClearJob = viewModelScope.launch {
            delay(3500L)
            val latest = activeDirectThread.value ?: thread
            repository.setDirectTypingState(latest, isTyping = false)
        }
    }

    fun clearDirectTyping(thread: DirectThread) {
        directTypingClearJob?.cancel()
        if (currentUserId !in thread.typingUserIds) return
        viewModelScope.launch {
            repository.setDirectTypingState(thread, isTyping = false)
        }
    }

    fun markDirectMessagesRead(thread: DirectThread, unreadMessages: List<DirectMessage>) {
        if (unreadMessages.isEmpty() && currentUserId in thread.lastReadByUids) return
        viewModelScope.launch {
            repository.markDirectMessagesRead(thread, unreadMessages)
        }
    }

    fun rotateDirectThreadKey(thread: DirectThread) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.rotateDirectThreadKey(thread)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { _actionMessage.value = "Direct session key rotated to v${thread.keyVersion + 1}." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to rotate session key." }
            )
        }
    }

    fun burnDirectMessage(threadId: String, message: DirectMessage) {
        viewModelScope.launch {
            val result = repository.burnDirectMessage(threadId, message)
            result.fold(
                onSuccess = { _actionMessage.value = "Direct message shredded." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to shred message." }
            )
        }
    }

    fun deleteDirectThread(thread: DirectThread) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.deleteDirectThread(thread)
            _isActionBusy.value = false
            result.fold(
                onSuccess = {
                    _selectedDirectThreadId.value = null
                    _actionMessage.value = "Direct conversation deleted."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to delete conversation." }
            )
        }
    }

    fun decryptDirectPreview(thread: DirectThread): String {
        val cipher = thread.lastMessageCipher
        val iv = thread.lastMessageIv
        if (cipher.isNullOrBlank() || iv.isNullOrBlank()) {
            return "AES-256-GCM session initialized"
        }
        return runCatching {
            NexusCryptoEngine.decryptText(cipher, iv, thread.threadKeyBase64)
        }.getOrElse {
            "Encrypted direct message (Key v${thread.keyVersion})"
        }
    }

    // --- BASE ROOM & VAULT ACTIONS ---

    fun toggleInspectRawCipher() {
        _inspectRawCipher.value = !_inspectRawCipher.value
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun createRoom(name: String, topic: String, accentTag: String, defaultTtlMinutes: Int, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.createRoom(name, topic, accentTag, defaultTtlMinutes)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { newRoomId ->
                    _actionMessage.value = "Encrypted space created with #general, #announcements & Voice Lounge."
                    _selectedRoomId.value = newRoomId
                    _selectedChannelId.value = "general"
                    onCreated(newRoomId)
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to create room." }
            )
        }
    }

    fun joinRoomByCode(code: String, onJoined: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.joinRoomByInviteCode(code)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { roomId ->
                    _actionMessage.value = "Joined encrypted space securely."
                    _selectedRoomId.value = roomId
                    _selectedChannelId.value = "general"
                    onJoined(roomId)
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not join room." }
            )
        }
    }

    fun burnMessage(roomId: String, message: EncryptedMessage) {
        viewModelScope.launch {
            val result = repository.burnMessage(roomId, message)
            result.fold(
                onSuccess = { _actionMessage.value = "Message card shredded." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Could not shred message." }
            )
        }
    }

    fun rotateRoomKey(room: ChatRoom) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.rotateRoomKey(room)
            _isActionBusy.value = false
            result.fold(
                onSuccess = { _actionMessage.value = "Room AES-256 key rotated to v${room.keyVersion + 1}." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Key rotation failed." }
            )
        }
    }

    fun rotateIdentityKey() {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.rotateIdentityKey()
            _isActionBusy.value = false
            result.fold(
                onSuccess = { _actionMessage.value = "New EC P-256 identity keypair generated." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to rotate identity key." }
            )
        }
    }

    fun leaveRoom(room: ChatRoom) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.leaveRoom(room)
            _isActionBusy.value = false
            result.fold(
                onSuccess = {
                    _selectedRoomId.value = null
                    _actionMessage.value = "Left ${room.name}."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to leave room." }
            )
        }
    }

    fun deleteRoom(room: ChatRoom) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.deleteRoom(room)
            _isActionBusy.value = false
            result.fold(
                onSuccess = {
                    _selectedRoomId.value = null
                    _actionMessage.value = "Room ${room.name} deleted."
                },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to delete room." }
            )
        }
    }

    fun inspectSelectedMedia(context: Context, uri: Uri): PendingMediaSelection {
        var displayName = "media_file"
        var sizeBytes = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
        if (sizeBytes <= 0L) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) sizeBytes = afd.length
                }
            }
        }
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return PendingMediaSelection(
            uriString = uri.toString(),
            fileName = displayName,
            mimeType = mimeType,
            fileSizeBytes = sizeBytes,
            exceedsLimit = sizeBytes > NexusLimits.MAX_FILE_SIZE_BYTES
        )
    }

    fun shareEncryptedMediaDrop(
        context: Context,
        room: ChatRoom,
        channelId: String,
        selection: PendingMediaSelection,
        ttlMinutes: Int,
        burnAfterView: Boolean,
        caption: String,
        onComplete: () -> Unit
    ) {
        if (selection.exceedsLimit || selection.fileSizeBytes > NexusLimits.MAX_FILE_SIZE_BYTES) {
            _actionMessage.value = "File exceeds the 4.00 GB maximum limit for optimal performance."
            return
        }
        if (selection.fileSizeBytes <= 0L) {
            _actionMessage.value = "Cannot encrypt an empty file."
            return
        }
        viewModelScope.launch {
            _isActionBusy.value = true
            _uploadProgress.value = 0.02f
            try {
                val dropId = repository.allocateDropId()
                val encrypted = NexusCryptoEngine.encryptMediaStream(
                    context = context.applicationContext,
                    sourceUri = Uri.parse(selection.uriString),
                    dropId = dropId,
                    mimeType = selection.mimeType,
                    expectedSizeBytes = selection.fileSizeBytes,
                    roomKeyBase64 = room.roomKeyBase64,
                    onProgress = { progress -> _uploadProgress.value = progress }
                )
                val effectiveSize = encrypted.totalBytesProcessed
                if (effectiveSize > NexusLimits.MAX_FILE_SIZE_BYTES) {
                    NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, dropId)
                    _actionMessage.value = "File exceeds the 4.00 GB maximum limit."
                    return@launch
                }
                val result = repository.publishEncryptedMediaDrop(
                    dropId = dropId,
                    room = room,
                    channelId = channelId,
                    fileName = selection.fileName,
                    mimeType = selection.mimeType,
                    fileSizeBytes = effectiveSize,
                    sha256Hash = encrypted.sha256Hash,
                    ivBase64 = encrypted.ivBase64,
                    encryptedPreviewBase64 = encrypted.encryptedPreviewBase64,
                    previewIvBase64 = encrypted.previewIvBase64,
                    ttlMinutes = ttlMinutes,
                    burnAfterView = burnAfterView,
                    caption = caption
                )
                result.fold(
                    onSuccess = {
                        _actionMessage.value = "Encrypted drop published (${NexusLimits.formatBytes(effectiveSize)} • ${NexusLimits.formatTtlLabel(ttlMinutes)} TTL)."
                        onComplete()
                    },
                    onFailure = { err ->
                        NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, dropId)
                        _actionMessage.value = err.message ?: "Failed to publish temporary media drop."
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionMessage.value = e.localizedMessage ?: "Encryption error while processing media."
            } finally {
                _uploadProgress.value = null
                _isActionBusy.value = false
            }
        }
    }

    fun markDropViewed(context: Context, drop: MediaDrop) {
        viewModelScope.launch {
            val result = repository.recordMediaDropViewed(drop)
            if (result.isSuccess && drop.burnAfterView) {
                NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, drop.id)
                _actionMessage.value = "Burn-after-view triggered: media shredded."
            }
        }
    }

    fun burnMediaDrop(context: Context, drop: MediaDrop) {
        viewModelScope.launch {
            NexusCryptoEngine.shredLocalVaultFile(context.applicationContext, drop.id)
            val result = repository.burnMediaDrop(drop)
            result.fold(
                onSuccess = { _actionMessage.value = "Temporary media cryptographically shredded." },
                onFailure = { err -> _actionMessage.value = err.message ?: "Unable to shred media drop." }
            )
        }
    }

    fun decryptRoomPreview(room: ChatRoom): String {
        val cipher = room.lastActivityPreviewCipher
        val iv = room.lastActivityIv
        if (cipher.isNullOrBlank() || iv.isNullOrBlank()) {
            return room.topic.ifBlank { "AES-256-GCM channel ready" }
        }
        return runCatching {
            NexusCryptoEngine.decryptText(cipher, iv, room.roomKeyBase64)
        }.getOrElse {
            "Encrypted activity (Key v${room.keyVersion})"
        }
    }

    fun decryptDropPreviewBytes(drop: MediaDrop, keyBase64: String?): ByteArray? {
        val cipher = drop.encryptedPreviewBase64
        val iv = drop.previewIvBase64
        if (cipher.isNullOrBlank() || iv.isNullOrBlank() || keyBase64.isNullOrBlank() || drop.isBurned()) {
            return null
        }
        return runCatching {
            NexusCryptoEngine.decryptBytes(cipher, iv, keyBase64)
        }.getOrNull()
    }

    private fun decryptRoomMessageItem(
        msg: EncryptedMessage,
        room: ChatRoom,
        dropMap: Map<String, MediaDrop>
    ): DecryptedMessageItem {
        if (msg.status == "burned") {
            return DecryptedMessageItem(
                message = msg,
                decryptedText = "Message card cryptographically shredded.",
                decryptionVerified = true,
                linkedDrop = msg.mediaDropId?.let { dropMap[it] }
            )
        }
        val (text, ok) = runCatching {
            NexusCryptoEngine.decryptText(msg.ciphertextBase64, msg.ivBase64, room.roomKeyBase64) to true
        }.getOrElse {
            "Encrypted with prior room key (v${msg.keyVersion})." to false
        }
        val replySnippet = if (!msg.replyToSnippetCipher.isNullOrBlank() && !msg.replyToSnippetIv.isNullOrBlank()) {
            runCatching {
                NexusCryptoEngine.decryptText(msg.replyToSnippetCipher, msg.replyToSnippetIv, room.roomKeyBase64)
            }.getOrNull()
        } else null
        val poll = if (msg.messageType == "poll" && ok) NexusAdvancedRepository.parsePollJson(text) else null
        return DecryptedMessageItem(
            message = msg,
            decryptedText = poll?.question ?: text,
            decryptedReplySnippet = replySnippet,
            decryptedPoll = poll,
            decryptionVerified = ok,
            linkedDrop = msg.mediaDropId?.let { dropMap[it] }
        )
    }

    private fun decryptDirectMessageItem(
        msg: DirectMessage,
        thread: DirectThread,
        dropMap: Map<String, MediaDrop>
    ): DecryptedDirectMessageItem {
        if (msg.status == "burned") {
            return DecryptedDirectMessageItem(
                message = msg,
                decryptedText = "Direct message cryptographically shredded.",
                decryptionVerified = true,
                linkedDrop = msg.mediaDropId?.let { dropMap[it] }
            )
        }
        val (text, ok) = runCatching {
            NexusCryptoEngine.decryptText(msg.ciphertextBase64, msg.ivBase64, thread.threadKeyBase64) to true
        }.getOrElse {
            "Encrypted with prior session key (v${msg.keyVersion})." to false
        }
        val replySnippet = if (!msg.replyToSnippetCipher.isNullOrBlank() && !msg.replyToSnippetIv.isNullOrBlank()) {
            runCatching {
                NexusCryptoEngine.decryptText(msg.replyToSnippetCipher, msg.replyToSnippetIv, thread.threadKeyBase64)
            }.getOrNull()
        } else null
        return DecryptedDirectMessageItem(
            message = msg,
            decryptedText = text,
            decryptedReplySnippet = replySnippet,
            decryptionVerified = ok,
            linkedDrop = msg.mediaDropId?.let { dropMap[it] }
        )
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    private companion object {
        const val TAG = "NexusVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
