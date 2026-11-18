package com.example.data

import com.example.crypto.NexusCryptoEngine
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Modular repository for Nexus's advanced capabilities:
 * - Multi-channel spaces (Text, Announcement, and E2EE Voice Lounges)
 * - Live Voice Lounge presence (join/leave/mute/raise-hand/active-speaker)
 * - E2EE Voice Notes (Rooms & Direct Messages)
 * - Interactive Encrypted Polls, Emoji Reactions, Inline Replies, Edits, Pins
 * - Member Roles, Slow-Mode, Moderation & Live Presence Status
 * - Direct Message 4.00 GB Temporary Media Drops
 */
class NexusAdvancedRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun currentDisplayName(): String {
        val raw = auth.currentUser?.displayName?.trim()
            ?: auth.currentUser?.email?.substringBefore("@")?.trim()
            ?: "Chatty Member"
        return raw.take(60).ifBlank { "Chatty Member" }
    }

    // --- LIVE PRESENCE STATUS ---

    suspend fun updatePresenceStatus(status: String, statusText: String): Result<Unit> {
        val safeStatus = if (status in NexusLimits.PRESENCE_STATUSES) status else "online"
        val cleanText = statusText.trim().take(80)
        val uid = auth.currentUser?.uid
        var docPath = "users/${uid ?: "unauthenticated"}"
        return try {
            val verifiedUid = requireUserId()
            val userDoc = db.collection("users").document(verifiedUid)
            val snap = userDoc.get().await()
            val contactCode = snap.getString("contactCode")
            userDoc.update(
                mapOf(
                    "presenceStatus" to safeStatus,
                    "statusText" to cleanText,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            if (!contactCode.isNullOrBlank()) {
                docPath = "contact_directory/$contactCode"
                db.collection("contact_directory").document(contactCode).update(
                    mapOf(
                        "presenceStatus" to safeStatus,
                        "statusText" to cleanText,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- MULTI-CHANNEL ROOMS & VOICE LOUNGES ---

    fun observeRoomChannels(roomId: String, userId: String): Flow<List<RoomChannel>> = flow {
        require(roomId.isNotBlank() && userId.isNotBlank()) { "roomId and userId must not be blank" }
        val path = "rooms/$roomId/channels"
        emitAll(
            db.collection("rooms").document(roomId).collection("channels")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(RoomChannel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(
                            compareBy<RoomChannel> {
                                when (it.id) {
                                    "general" -> 0
                                    "announcements" -> 1
                                    "voice_lounge" -> 2
                                    else -> 3
                                }
                            }.thenBy { it.createdAt ?: Timestamp(0, 0) }
                        )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun ensureDefaultChannelsForRoom(room: ChatRoom): Result<Unit> {
        val docPath = "rooms/${room.id}/channels"
        return try {
            val uid = requireUserId()
            if (!room.isOwnerOrAdmin(uid)) return Result.success(Unit)
            val channelsCol = db.collection("rooms").document(room.id).collection("channels")
            val existing = channelsCol.get().await()
            if (!existing.isEmpty) return Result.success(Unit)

            val defaults = listOf(
                Triple("general", "general", "text" to "Primary encrypted card stream"),
                Triple("announcements", "announcements", "announcement" to "Key updates & pinned room dispatches"),
                Triple("voice_lounge", "Voice Lounge", "voice" to "E2EE Push-to-Talk voice stage")
            )
            defaults.forEach { (id, name, typeAndTopic) ->
                channelsCol.document(id).set(
                    mapOf(
                        "roomId" to room.id,
                        "ownerId" to room.ownerId,
                        "name" to name,
                        "channelType" to typeAndTopic.first,
                        "topic" to typeAndTopic.second,
                        "voiceParticipantUids" to emptyList<String>(),
                        "voiceParticipantNames" to emptyMap<String, String>(),
                        "mutedUids" to emptyList<String>(),
                        "raisedHandUids" to emptyList<String>(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createChannel(
        room: ChatRoom,
        rawName: String,
        channelType: String,
        topic: String
    ): Result<String> {
        val cleanName = rawName.trim().take(36)
        if (cleanName.length < 2) {
            return Result.failure(IllegalArgumentException("Channel name must be at least 2 characters."))
        }
        val safeType = if (channelType in listOf("text", "voice", "announcement")) channelType else "text"
        val cleanTopic = topic.trim().take(140)
        var docPath = "rooms/${room.id}/channels"
        return try {
            val uid = requireUserId()
            check(room.isOwnerOrAdmin(uid)) { "Only room owners or admins can create channels." }
            val ref = db.collection("rooms").document(room.id).collection("channels").document()
            docPath = ref.path
            ref.set(
                mapOf(
                    "roomId" to room.id,
                    "ownerId" to room.ownerId,
                    "name" to cleanName,
                    "channelType" to safeType,
                    "topic" to cleanTopic,
                    "voiceParticipantUids" to emptyList<String>(),
                    "voiceParticipantNames" to emptyMap<String, String>(),
                    "mutedUids" to emptyList<String>(),
                    "raisedHandUids" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun joinVoiceChannel(roomId: String, channel: RoomChannel): Result<Unit> {
        val docPath = "rooms/$roomId/channels/${channel.id}"
        return try {
            val uid = requireUserId()
            val name = currentDisplayName()
            val nextUids = (channel.voiceParticipantUids + uid).distinct().take(25)
            val nextNames = channel.voiceParticipantNames.toMutableMap().apply { put(uid, name) }
            db.collection("rooms").document(roomId).collection("channels").document(channel.id).update(
                mapOf(
                    "voiceParticipantUids" to nextUids,
                    "voiceParticipantNames" to nextNames,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun leaveVoiceChannel(roomId: String, channel: RoomChannel): Result<Unit> {
        val docPath = "rooms/$roomId/channels/${channel.id}"
        return try {
            val uid = requireUserId()
            val nextUids = channel.voiceParticipantUids.filter { it != uid }
            val nextNames = channel.voiceParticipantNames.toMutableMap().apply { remove(uid) }
            val nextMuted = channel.mutedUids.filter { it != uid }
            val nextHands = channel.raisedHandUids.filter { it != uid }
            val nextSpeaker = if (channel.activeSpeakerUid == uid) "" else (channel.activeSpeakerUid ?: "")
            db.collection("rooms").document(roomId).collection("channels").document(channel.id).update(
                mapOf(
                    "voiceParticipantUids" to nextUids,
                    "voiceParticipantNames" to nextNames,
                    "mutedUids" to nextMuted,
                    "raisedHandUids" to nextHands,
                    "activeSpeakerUid" to nextSpeaker,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleVoiceMute(roomId: String, channel: RoomChannel): Result<Unit> {
        val docPath = "rooms/$roomId/channels/${channel.id}"
        return try {
            val uid = requireUserId()
            val nextMuted = if (uid in channel.mutedUids) {
                channel.mutedUids.filter { it != uid }
            } else {
                (channel.mutedUids + uid).distinct()
            }
            db.collection("rooms").document(roomId).collection("channels").document(channel.id).update(
                mapOf(
                    "mutedUids" to nextMuted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleVoiceRaiseHand(roomId: String, channel: RoomChannel): Result<Unit> {
        val docPath = "rooms/$roomId/channels/${channel.id}"
        return try {
            val uid = requireUserId()
            val nextHands = if (uid in channel.raisedHandUids) {
                channel.raisedHandUids.filter { it != uid }
            } else {
                (channel.raisedHandUids + uid).distinct()
            }
            db.collection("rooms").document(roomId).collection("channels").document(channel.id).update(
                mapOf(
                    "raisedHandUids" to nextHands,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun setActiveVoiceSpeaker(roomId: String, channelId: String, speaking: Boolean): Result<Unit> {
        val docPath = "rooms/$roomId/channels/$channelId"
        return try {
            val uid = requireUserId()
            db.collection("rooms").document(roomId).collection("channels").document(channelId).update(
                mapOf(
                    "activeSpeakerUid" to if (speaking) uid else "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- ROOM TYPING, ROLES, SLOW-MODE & MODERATION ---

    suspend fun setRoomTypingState(room: ChatRoom, isTyping: Boolean): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            val current = room.typingUserIds.orEmpty()
            val already = uid in current
            if (isTyping == already) return Result.success(Unit)
            val next = if (isTyping) (current + uid).distinct().take(10) else current.filter { it != uid }
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "typingUserIds" to next,
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(room: ChatRoom, targetUid: String, newRole: String): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            check(room.ownerId == uid) { "Only the room owner can manage roles." }
            check(targetUid != room.ownerId) { "Owner role cannot be changed." }
            val safeRole = if (newRole in listOf("admin", "moderator", "member")) newRole else "member"
            val updatedRoles = (room.memberRoles ?: emptyMap()).toMutableMap().apply {
                put(room.ownerId, "owner")
                put(targetUid, safeRole)
            }
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "memberRoles" to updatedRoles,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun kickRoomMember(room: ChatRoom, targetUid: String): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            check(room.ownerId == uid) { "Only the room owner can remove members." }
            check(targetUid != room.ownerId) { "Owner cannot be removed." }
            val nextMembers = room.memberUids.filter { it != targetUid }
            val nextRoles = (room.memberRoles ?: emptyMap()).toMutableMap().apply { remove(targetUid) }
            val nextNames = (room.memberNames ?: emptyMap()).toMutableMap().apply { remove(targetUid) }
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "memberUids" to nextMembers,
                    "memberRoles" to nextRoles,
                    "memberNames" to nextNames,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateSlowMode(room: ChatRoom, slowModeSeconds: Int): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            check(room.ownerId == uid) { "Only the room owner can change slow-mode." }
            val safeSlow = if (slowModeSeconds in NexusLimits.ALLOWED_SLOW_MODE_SECONDS) slowModeSeconds else 0
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "slowModeSeconds" to safeSlow,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- RICH ROOM MESSAGES: REPLIES, VOICE CLIPS, POLLS, REACTIONS, EDITS, PINS ---

    suspend fun sendRichRoomMessage(
        room: ChatRoom,
        channelId: String,
        plaintext: String,
        replyTo: ReplyTarget?
    ): Result<Unit> {
        val cleanText = plaintext.trim()
        if (cleanText.isBlank()) return Result.failure(IllegalArgumentException("Message cannot be empty."))
        if (cleanText.length > 4000) return Result.failure(IllegalArgumentException("Message exceeds 4,000 character limit."))

        var docPath = "rooms/${room.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val encrypted = NexusCryptoEngine.encryptText(cleanText, room.roomKeyBase64)
            val previewCipher = NexusCryptoEngine.encryptText(cleanText.take(160), room.roomKeyBase64)
            val replyEnc = replyTo?.let {
                NexusCryptoEngine.encryptText(it.previewText.take(120), room.roomKeyBase64)
            }

            val roomRef = db.collection("rooms").document(room.id)
            val msgRef = roomRef.collection("messages").document()
            docPath = msgRef.path

            val payload = mapOf<String, Any?>(
                "roomId" to room.id,
                "channelId" to channelId.ifBlank { "general" },
                "senderId" to uid,
                "senderName" to senderName,
                "memberUids" to room.memberUids,
                "messageType" to "text",
                "ciphertextBase64" to encrypted.ciphertextBase64,
                "ivBase64" to encrypted.ivBase64,
                "keyVersion" to room.keyVersion,
                "replyToMessageId" to replyTo?.messageId,
                "replyToSenderName" to replyTo?.senderName?.take(60),
                "replyToSnippetCipher" to replyEnc?.ciphertextBase64,
                "replyToSnippetIv" to replyEnc?.ivBase64,
                "reactions" to emptyMap<String, List<String>>(),
                "pinned" to false,
                "edited" to false,
                "status" to "active",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            msgRef.set(payload).await()

            docPath = roomRef.path
            val nextTyping = room.typingUserIds.orEmpty().filter { it != uid }
            roomRef.update(
                mapOf(
                    "lastActivityPreviewCipher" to previewCipher.ciphertextBase64,
                    "lastActivityIv" to previewCipher.ivBase64,
                    "lastSenderName" to senderName,
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "typingUserIds" to nextTyping,
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendEncryptedVoiceToRoom(
        room: ChatRoom,
        channelId: String,
        audioBytes: ByteArray,
        durationMs: Long
    ): Result<Unit> {
        var docPath = "rooms/${room.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val encAudio = NexusCryptoEngine.encryptBytes(audioBytes, room.roomKeyBase64)
            if (encAudio.ciphertextBase64.length > 390_000) {
                return Result.failure(IllegalArgumentException("Voice clip exceeds encrypted burst size limit."))
            }
            val caption = "Encrypted voice clip (${NexusLimits.formatDurationMs(durationMs)})"
            val encText = NexusCryptoEngine.encryptText(caption, room.roomKeyBase64)
            val previewEnc = NexusCryptoEngine.encryptText("Voice clip • ${NexusLimits.formatDurationMs(durationMs)}", room.roomKeyBase64)

            val roomRef = db.collection("rooms").document(room.id)
            val msgRef = roomRef.collection("messages").document()
            docPath = msgRef.path
            msgRef.set(
                mapOf(
                    "roomId" to room.id,
                    "channelId" to channelId.ifBlank { "general" },
                    "senderId" to uid,
                    "senderName" to senderName,
                    "memberUids" to room.memberUids,
                    "messageType" to "voice",
                    "ciphertextBase64" to encText.ciphertextBase64,
                    "ivBase64" to encText.ivBase64,
                    "keyVersion" to room.keyVersion,
                    "encryptedAudioBase64" to encAudio.ciphertextBase64,
                    "audioIvBase64" to encAudio.ivBase64,
                    "audioDurationMs" to durationMs.coerceIn(300L, 180_000L),
                    "reactions" to emptyMap<String, List<String>>(),
                    "pinned" to false,
                    "edited" to false,
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            docPath = roomRef.path
            roomRef.update(
                mapOf(
                    "lastActivityPreviewCipher" to previewEnc.ciphertextBase64,
                    "lastActivityIv" to previewEnc.ivBase64,
                    "lastSenderName" to senderName,
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createEncryptedPoll(
        room: ChatRoom,
        channelId: String,
        question: String,
        options: List<String>
    ): Result<Unit> {
        val cleanQuestion = question.trim().take(240)
        val cleanOptions = options.map { it.trim().take(100) }.filter { it.isNotBlank() }.take(5)
        if (cleanQuestion.length < 3) return Result.failure(IllegalArgumentException("Enter a poll question."))
        if (cleanOptions.size < 2) return Result.failure(IllegalArgumentException("Add at least 2 poll options."))

        var docPath = "rooms/${room.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val pollJson = JSONObject().apply {
                put("q", cleanQuestion)
                put("o", JSONArray(cleanOptions))
            }.toString()
            val encPoll = NexusCryptoEngine.encryptText(pollJson, room.roomKeyBase64)
            val previewEnc = NexusCryptoEngine.encryptText("Poll: ${cleanQuestion.take(120)}", room.roomKeyBase64)

            val roomRef = db.collection("rooms").document(room.id)
            val msgRef = roomRef.collection("messages").document()
            docPath = msgRef.path
            msgRef.set(
                mapOf(
                    "roomId" to room.id,
                    "channelId" to channelId.ifBlank { "general" },
                    "senderId" to uid,
                    "senderName" to senderName,
                    "memberUids" to room.memberUids,
                    "messageType" to "poll",
                    "ciphertextBase64" to encPoll.ciphertextBase64,
                    "ivBase64" to encPoll.ivBase64,
                    "keyVersion" to room.keyVersion,
                    "pollOptionCount" to cleanOptions.size,
                    "pollVotes" to emptyMap<String, String>(),
                    "reactions" to emptyMap<String, List<String>>(),
                    "pinned" to false,
                    "edited" to false,
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            docPath = roomRef.path
            roomRef.update(
                mapOf(
                    "lastActivityPreviewCipher" to previewEnc.ciphertextBase64,
                    "lastActivityIv" to previewEnc.ivBase64,
                    "lastSenderName" to senderName,
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun voteInPoll(roomId: String, message: EncryptedMessage, optionIndex: Int): Result<Unit> {
        val docPath = "rooms/$roomId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            val nextVotes = (message.pollVotes ?: emptyMap()).toMutableMap()
            nextVotes[uid] = optionIndex.toString()
            db.collection("rooms").document(roomId).collection("messages").document(message.id).update(
                mapOf(
                    "pollVotes" to nextVotes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleRoomReaction(roomId: String, message: EncryptedMessage, emoji: String): Result<Unit> {
        val docPath = "rooms/$roomId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            val updated = buildToggledReactions(message.reactions, emoji, uid)
            db.collection("rooms").document(roomId).collection("messages").document(message.id).update(
                mapOf(
                    "reactions" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleRoomPin(roomId: String, message: EncryptedMessage): Result<Unit> {
        val docPath = "rooms/$roomId/messages/${message.id}"
        return try {
            requireUserId()
            val nextPinned = !(message.pinned ?: false)
            db.collection("rooms").document(roomId).collection("messages").document(message.id).update(
                mapOf(
                    "pinned" to nextPinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun editRoomMessage(room: ChatRoom, message: EncryptedMessage, newPlaintext: String): Result<Unit> {
        val clean = newPlaintext.trim()
        if (clean.isBlank() || clean.length > 4000) {
            return Result.failure(IllegalArgumentException("Edited text must be 1–4,000 characters."))
        }
        val docPath = "rooms/${room.id}/messages/${message.id}"
        return try {
            val uid = requireUserId()
            check(message.senderId == uid) { "Only the author can edit this message." }
            val enc = NexusCryptoEngine.encryptText(clean, room.roomKeyBase64)
            db.collection("rooms").document(room.id).collection("messages").document(message.id).update(
                mapOf(
                    "ciphertextBase64" to enc.ciphertextBase64,
                    "ivBase64" to enc.ivBase64,
                    "edited" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- RICH DIRECT MESSAGES: REPLIES, VOICE NOTES, 4GB DROPS, REACTIONS, EDITS, PINS ---

    suspend fun sendRichDirectMessage(
        thread: DirectThread,
        plaintext: String,
        replyTo: ReplyTarget?
    ): Result<Unit> {
        val cleanText = plaintext.trim()
        if (cleanText.isBlank()) return Result.failure(IllegalArgumentException("Message cannot be empty."))
        if (cleanText.length > 4000) return Result.failure(IllegalArgumentException("Message exceeds 4,000 character limit."))

        var docPath = "direct_threads/${thread.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val encrypted = NexusCryptoEngine.encryptText(cleanText, thread.threadKeyBase64)
            val previewEnc = NexusCryptoEngine.encryptText(cleanText.take(160), thread.threadKeyBase64)
            val replyEnc = replyTo?.let {
                NexusCryptoEngine.encryptText(it.previewText.take(120), thread.threadKeyBase64)
            }

            val threadRef = db.collection("direct_threads").document(thread.id)
            val msgRef = threadRef.collection("messages").document()
            docPath = msgRef.path

            val payload = mapOf<String, Any?>(
                "threadId" to thread.id,
                "senderId" to uid,
                "senderName" to senderName,
                "participantUids" to thread.participantUids,
                "messageType" to "text",
                "ciphertextBase64" to encrypted.ciphertextBase64,
                "ivBase64" to encrypted.ivBase64,
                "keyVersion" to thread.keyVersion,
                "replyToMessageId" to replyTo?.messageId,
                "replyToSenderName" to replyTo?.senderName?.take(60),
                "replyToSnippetCipher" to replyEnc?.ciphertextBase64,
                "replyToSnippetIv" to replyEnc?.ivBase64,
                "reactions" to emptyMap<String, List<String>>(),
                "pinned" to false,
                "edited" to false,
                "readByUids" to listOf(uid),
                "status" to "active",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            msgRef.set(payload).await()

            docPath = threadRef.path
            val nextTyping = thread.typingUserIds.filter { it != uid }
            threadRef.update(
                mapOf(
                    "lastMessageCipher" to previewEnc.ciphertextBase64,
                    "lastMessageIv" to previewEnc.ivBase64,
                    "lastMessageSenderId" to uid,
                    "lastReadByUids" to listOf(uid),
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "typingUserIds" to nextTyping,
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendEncryptedVoiceToDirectThread(
        thread: DirectThread,
        audioBytes: ByteArray,
        durationMs: Long
    ): Result<Unit> {
        var docPath = "direct_threads/${thread.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val encAudio = NexusCryptoEngine.encryptBytes(audioBytes, thread.threadKeyBase64)
            if (encAudio.ciphertextBase64.length > 390_000) {
                return Result.failure(IllegalArgumentException("Voice note exceeds encrypted burst size limit."))
            }
            val caption = "Encrypted voice note (${NexusLimits.formatDurationMs(durationMs)})"
            val encText = NexusCryptoEngine.encryptText(caption, thread.threadKeyBase64)
            val previewEnc = NexusCryptoEngine.encryptText("Voice note • ${NexusLimits.formatDurationMs(durationMs)}", thread.threadKeyBase64)

            val threadRef = db.collection("direct_threads").document(thread.id)
            val msgRef = threadRef.collection("messages").document()
            docPath = msgRef.path
            msgRef.set(
                mapOf(
                    "threadId" to thread.id,
                    "senderId" to uid,
                    "senderName" to senderName,
                    "participantUids" to thread.participantUids,
                    "messageType" to "voice",
                    "ciphertextBase64" to encText.ciphertextBase64,
                    "ivBase64" to encText.ivBase64,
                    "keyVersion" to thread.keyVersion,
                    "encryptedAudioBase64" to encAudio.ciphertextBase64,
                    "audioIvBase64" to encAudio.ivBase64,
                    "audioDurationMs" to durationMs.coerceIn(300L, 180_000L),
                    "reactions" to emptyMap<String, List<String>>(),
                    "pinned" to false,
                    "edited" to false,
                    "readByUids" to listOf(uid),
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            docPath = threadRef.path
            threadRef.update(
                mapOf(
                    "lastMessageCipher" to previewEnc.ciphertextBase64,
                    "lastMessageIv" to previewEnc.ivBase64,
                    "lastMessageSenderId" to uid,
                    "lastReadByUids" to listOf(uid),
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "typingUserIds" to thread.typingUserIds.filter { it != uid },
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun publishEncryptedDirectMediaDrop(
        dropId: String,
        thread: DirectThread,
        fileName: String,
        mimeType: String,
        fileSizeBytes: Long,
        sha256Hash: String,
        ivBase64: String,
        encryptedPreviewBase64: String?,
        previewIvBase64: String?,
        ttlMinutes: Int,
        burnAfterView: Boolean,
        caption: String
    ): Result<Unit> {
        if (fileSizeBytes < 1L || fileSizeBytes > NexusLimits.MAX_FILE_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("File exceeds the 4.00 GB maximum limit."))
        }
        val safeTtl = if (ttlMinutes in NexusLimits.ALLOWED_TTL_MINUTES) ttlMinutes else 60
        val sanitizedName = fileName.trim().take(200).ifBlank { "encrypted_drop.bin" }
        val sanitizedMime = mimeType.trim().take(100).ifBlank { "application/octet-stream" }

        var docPath = "media_drops/$dropId"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val expiresAtDate = Date(System.currentTimeMillis() + safeTtl * 60_000L)
            val peerLabel = "DM • ${thread.peerDisplayName(uid)}".take(60)

            val dropPayload = mapOf<String, Any?>(
                "roomId" to thread.id,
                "roomName" to peerLabel,
                "senderId" to uid,
                "senderName" to senderName,
                "memberUids" to thread.participantUids,
                "fileName" to sanitizedName,
                "mimeType" to sanitizedMime,
                "fileSizeBytes" to fileSizeBytes,
                "sha256Hash" to sha256Hash,
                "cipherAlgorithm" to "AES-256-GCM",
                "ivBase64" to ivBase64,
                "encryptedPreviewBase64" to encryptedPreviewBase64,
                "previewIvBase64" to previewIvBase64,
                "ttlMinutes" to safeTtl,
                "burnAfterView" to burnAfterView,
                "status" to "active",
                "viewedByUids" to emptyList<String>(),
                "expiresAt" to Timestamp(expiresAtDate),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            db.collection("media_drops").document(dropId).set(dropPayload).await()

            val summaryText = buildString {
                append("Shared encrypted drop: $sanitizedName (${NexusLimits.formatBytes(fileSizeBytes)})")
                if (caption.isNotBlank()) append("\n${caption.trim().take(500)}")
            }
            val encMsg = NexusCryptoEngine.encryptText(summaryText, thread.threadKeyBase64)
            val previewEnc = NexusCryptoEngine.encryptText(
                "Drop • $sanitizedName (${NexusLimits.formatBytes(fileSizeBytes)})",
                thread.threadKeyBase64
            )

            val threadRef = db.collection("direct_threads").document(thread.id)
            val msgRef = threadRef.collection("messages").document()
            docPath = msgRef.path
            msgRef.set(
                mapOf(
                    "threadId" to thread.id,
                    "senderId" to uid,
                    "senderName" to senderName,
                    "participantUids" to thread.participantUids,
                    "messageType" to "media",
                    "ciphertextBase64" to encMsg.ciphertextBase64,
                    "ivBase64" to encMsg.ivBase64,
                    "keyVersion" to thread.keyVersion,
                    "mediaDropId" to dropId,
                    "reactions" to emptyMap<String, List<String>>(),
                    "pinned" to false,
                    "edited" to false,
                    "readByUids" to listOf(uid),
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            docPath = threadRef.path
            threadRef.update(
                mapOf(
                    "lastMessageCipher" to previewEnc.ciphertextBase64,
                    "lastMessageIv" to previewEnc.ivBase64,
                    "lastMessageSenderId" to uid,
                    "lastReadByUids" to listOf(uid),
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "typingUserIds" to thread.typingUserIds.filter { it != uid },
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleDirectReaction(threadId: String, message: DirectMessage, emoji: String): Result<Unit> {
        val docPath = "direct_threads/$threadId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            val updated = buildToggledReactions(message.reactions, emoji, uid)
            db.collection("direct_threads").document(threadId).collection("messages").document(message.id).update(
                mapOf(
                    "reactions" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleDirectPin(threadId: String, message: DirectMessage): Result<Unit> {
        val docPath = "direct_threads/$threadId/messages/${message.id}"
        return try {
            requireUserId()
            val nextPinned = !(message.pinned ?: false)
            db.collection("direct_threads").document(threadId).collection("messages").document(message.id).update(
                mapOf(
                    "pinned" to nextPinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun editDirectMessage(thread: DirectThread, message: DirectMessage, newPlaintext: String): Result<Unit> {
        val clean = newPlaintext.trim()
        if (clean.isBlank() || clean.length > 4000) {
            return Result.failure(IllegalArgumentException("Edited text must be 1–4,000 characters."))
        }
        val docPath = "direct_threads/${thread.id}/messages/${message.id}"
        return try {
            val uid = requireUserId()
            check(message.senderId == uid) { "Only the author can edit this direct message." }
            val enc = NexusCryptoEngine.encryptText(clean, thread.threadKeyBase64)
            db.collection("direct_threads").document(thread.id).collection("messages").document(message.id).update(
                mapOf(
                    "ciphertextBase64" to enc.ciphertextBase64,
                    "ivBase64" to enc.ivBase64,
                    "edited" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun buildToggledReactions(
        existing: Map<String, List<String>>?,
        emoji: String,
        uid: String
    ): Map<String, List<String>> {
        val mutable = existing.orEmpty().mapValues { it.value.toMutableList() }.toMutableMap()
        val list = mutable[emoji] ?: mutableListOf()
        if (uid in list) {
            list.remove(uid)
            if (list.isEmpty()) mutable.remove(emoji) else mutable[emoji] = list
        } else {
            if (mutable.size < 16 || mutable.containsKey(emoji)) {
                list.add(uid)
                mutable[emoji] = list
            }
        }
        return mutable.mapValues { it.value.toList() }
    }

    private fun logStreamErrorIfActive(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }

    companion object {
        fun parsePollJson(decryptedJson: String): DecryptedPollData? {
            return runCatching {
                val obj = JSONObject(decryptedJson)
                val q = obj.getString("q")
                val arr = obj.getJSONArray("o")
                val opts = buildList {
                    for (i in 0 until arr.length()) {
                        add(arr.getString(i))
                    }
                }
                if (q.isNotBlank() && opts.size >= 2) DecryptedPollData(q, opts) else null
            }.getOrNull()
        }
    }
}
