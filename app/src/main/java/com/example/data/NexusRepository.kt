package com.example.data

import android.content.Context
import com.example.R
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date

class NexusRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

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

    // --- USER IDENTITY ---

    fun observeUserIdentity(userId: String): Flow<UserIdentity?> = flow {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(UserIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else {
                        null
                    }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, userId)
                    throw error
                }
        )
    }

    private fun generateContactCode(): String {
        val partA = NexusCryptoEngine.generateInviteCode(4)
        val partB = NexusCryptoEngine.generateInviteCode(4)
        return "NX-$partA-$partB"
    }

    suspend fun ensureUserIdentity(): Result<UserIdentity> {
        val uid = auth.currentUser?.uid
        val path = "users/${uid ?: "unauthenticated"}"
        return try {
            val verifiedUid = requireUserId()
            val docRef = db.collection("users").document(verifiedUid)
            val existing = docRef.get().await()
            val displayName = currentDisplayName()
            val photoUrl = auth.currentUser?.photoUrl?.toString()?.take(500)

            if (existing.exists()) {
                val model = existing.toObject(UserIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                if (model != null) {
                    val existingCode = model.contactCode?.takeIf { it.isNotBlank() }
                    val resolvedCode = existingCode ?: generateContactCode()
                    val resolvedPresence = model.presenceStatus?.takeIf { it in NexusLimits.PRESENCE_STATUSES } ?: "online"
                    val resolvedStatusText = model.statusText ?: ""
                    if (existingCode == null || model.presenceStatus == null) {
                        docRef.update(
                            mapOf(
                                "contactCode" to resolvedCode,
                                "presenceStatus" to resolvedPresence,
                                "statusText" to resolvedStatusText,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                    ensureContactDirectoryEntry(
                        contactCode = resolvedCode,
                        uid = verifiedUid,
                        displayName = model.displayName.ifBlank { displayName },
                        fingerprint = model.publicKeyFingerprint,
                        presenceStatus = resolvedPresence,
                        statusText = resolvedStatusText,
                        photoUrl = model.photoUrl ?: photoUrl
                    )
                    return Result.success(
                        model.copy(
                            contactCode = resolvedCode,
                            presenceStatus = resolvedPresence,
                            statusText = resolvedStatusText
                        )
                    )
                }
            }

            val keyMaterial = NexusCryptoEngine.generateIdentityKeyPair()
            val contactCode = generateContactCode()
            val payload = mapOf<String, Any?>(
                "userId" to verifiedUid,
                "displayName" to displayName,
                "contactCode" to contactCode,
                "presenceStatus" to "online",
                "statusText" to "",
                "publicKeyFingerprint" to keyMaterial.fingerprint,
                "publicKeyBase64" to keyMaterial.publicKeyBase64,
                "photoUrl" to photoUrl,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            docRef.set(payload).await()

            ensureContactDirectoryEntry(
                contactCode = contactCode,
                uid = verifiedUid,
                displayName = displayName,
                fingerprint = keyMaterial.fingerprint,
                presenceStatus = "online",
                statusText = "",
                photoUrl = photoUrl
            )

            val created = docRef.get().await()
                .toObject(UserIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserIdentity(
                    id = verifiedUid,
                    userId = verifiedUid,
                    displayName = displayName,
                    contactCode = contactCode,
                    presenceStatus = "online",
                    statusText = "",
                    publicKeyFingerprint = keyMaterial.fingerprint,
                    publicKeyBase64 = keyMaterial.publicKeyBase64,
                    photoUrl = photoUrl
                )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private suspend fun ensureContactDirectoryEntry(
        contactCode: String,
        uid: String,
        displayName: String,
        fingerprint: String,
        presenceStatus: String,
        statusText: String,
        photoUrl: String?
    ) {
        val dirRef = db.collection("contact_directory").document(contactCode)
        val snap = runCatching { dirRef.get().await() }.getOrNull()
        if (snap == null || !snap.exists()) {
            val payload = mapOf<String, Any?>(
                "userId" to uid,
                "displayName" to displayName.take(60),
                "contactCode" to contactCode,
                "presenceStatus" to presenceStatus,
                "statusText" to statusText.take(80),
                "publicKeyFingerprint" to fingerprint,
                "photoUrl" to photoUrl,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            dirRef.set(payload).await()
        }
    }

    suspend fun rotateIdentityKey(): Result<Unit> {
        val uid = auth.currentUser?.uid
        val path = "users/${uid ?: "unauthenticated"}"
        return try {
            val verifiedUid = requireUserId()
            val keyMaterial = NexusCryptoEngine.generateIdentityKeyPair()
            val userDoc = db.collection("users").document(verifiedUid)
            val snap = userDoc.get().await()
            val contactCode = snap.getString("contactCode")
            userDoc.update(
                mapOf(
                    "publicKeyFingerprint" to keyMaterial.fingerprint,
                    "publicKeyBase64" to keyMaterial.publicKeyBase64,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            if (!contactCode.isNullOrBlank()) {
                runCatching {
                    db.collection("contact_directory").document(contactCode).update(
                        mapOf(
                            "publicKeyFingerprint" to keyMaterial.fingerprint,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- ENCRYPTED GROUP CHAT ROOMS ---

    fun observeRooms(userId: String): Flow<List<ChatRoom>> = flow {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val path = "rooms"
        emitAll(
            db.collection("rooms")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ChatRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.lastActivityAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun createRoom(
        name: String,
        topic: String,
        accentTag: String,
        defaultTtlMinutes: Int
    ): Result<String> {
        val trimmedName = name.trim()
        val trimmedTopic = topic.trim().take(180)
        if (trimmedName.length < 2 || trimmedName.length > 60) {
            return Result.failure(IllegalArgumentException("Room name must be between 2 and 60 characters."))
        }
        val safeAccent = if (accentTag in NexusLimits.ACCENT_TAGS) accentTag else "emerald"
        val safeTtl = if (defaultTtlMinutes in NexusLimits.ALLOWED_TTL_MINUTES) defaultTtlMinutes else 60

        var docPath = "rooms"
        return try {
            val uid = requireUserId()
            val roomRef = db.collection("rooms").document()
            docPath = roomRef.path

            val keyBundle = NexusCryptoEngine.generateRoomKey()
            val inviteCode = NexusCryptoEngine.generateInviteCode(8)
            val senderName = currentDisplayName()
            val welcomeEncrypted = NexusCryptoEngine.encryptText(
                "End-to-end encrypted room initialized with AES-256-GCM. Files up to 4.00 GB supported.",
                keyBundle.roomKeyBase64
            )

            val roomPayload = mapOf(
                "ownerId" to uid,
                "name" to trimmedName,
                "topic" to trimmedTopic,
                "inviteCode" to inviteCode,
                "accentTag" to safeAccent,
                "roomKeyBase64" to keyBundle.roomKeyBase64,
                "keyFingerprint" to keyBundle.keyFingerprint,
                "keyVersion" to 1,
                "memberUids" to listOf(uid),
                "memberNames" to mapOf(uid to senderName),
                "memberRoles" to mapOf(uid to "owner"),
                "typingUserIds" to emptyList<String>(),
                "typingUpdatedAt" to FieldValue.serverTimestamp(),
                "slowModeSeconds" to 0,
                "defaultTtlMinutes" to safeTtl,
                "maxFileSizeBytes" to NexusLimits.MAX_FILE_SIZE_BYTES,
                "lastActivityPreviewCipher" to welcomeEncrypted.ciphertextBase64,
                "lastActivityIv" to welcomeEncrypted.ivBase64,
                "lastSenderName" to senderName,
                "lastActivityAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            roomRef.set(roomPayload).await()

            // Register direct lookup invite code document
            val inviteRef = db.collection("room_invites").document(inviteCode)
            docPath = inviteRef.path
            inviteRef.set(
                mapOf(
                    "roomId" to roomRef.id,
                    "roomName" to trimmedName,
                    "ownerId" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // Bootstrap default channels (#general, #announcements, Voice Lounge)
            val defaultChannels = listOf(
                Triple("general", "general", "text" to "Primary encrypted card stream"),
                Triple("announcements", "announcements", "announcement" to "Pinned updates & key dispatches"),
                Triple("voice_lounge", "Voice Lounge", "voice" to "E2EE Push-to-Talk voice stage")
            )
            defaultChannels.forEach { (chId, chName, typeAndTopic) ->
                val chRef = roomRef.collection("channels").document(chId)
                docPath = chRef.path
                chRef.set(
                    mapOf(
                        "roomId" to roomRef.id,
                        "ownerId" to uid,
                        "name" to chName,
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

            // Post initial encrypted system message card in #general
            val msgRef = roomRef.collection("messages").document()
            docPath = msgRef.path
            msgRef.set(
                mapOf(
                    "roomId" to roomRef.id,
                    "channelId" to "general",
                    "senderId" to uid,
                    "senderName" to senderName,
                    "memberUids" to listOf(uid),
                    "messageType" to "system",
                    "ciphertextBase64" to welcomeEncrypted.ciphertextBase64,
                    "ivBase64" to welcomeEncrypted.ivBase64,
                    "keyVersion" to 1,
                    "reactions" to emptyMap<String, List<String>>(),
                    "pinned" to false,
                    "edited" to false,
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(roomRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun joinRoomByInviteCode(rawCode: String): Result<String> {
        val code = rawCode.trim().uppercase()
        if (code.length < 6 || code.length > 16 || !code.matches(Regex("^[A-Z0-9]+$"))) {
            return Result.failure(IllegalArgumentException("Enter a valid 8-character invite code (letters & numbers)."))
        }
        var docPath = "room_invites/$code"
        return try {
            val uid = requireUserId()
            val inviteSnap = db.collection("room_invites").document(code).get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(IllegalArgumentException("Invite code \"$code\" was not found."))
            }
            val roomId = inviteSnap.getString("roomId")
                ?: return Result.failure(IllegalStateException("Invite record is missing a target room."))

            val roomRef = db.collection("rooms").document(roomId)
            docPath = roomRef.path
            val joinerName = currentDisplayName()
            roomRef.update(
                mapOf(
                    "memberUids" to FieldValue.arrayUnion(uid),
                    "memberNames.$uid" to joinerName,
                    "memberRoles.$uid" to "member",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun rotateRoomKey(room: ChatRoom): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            require(room.id.isNotBlank()) { "Room ID is required" }
            check(room.ownerId == uid) { "Only the room creator can rotate the room encryption key." }
            val nextBundle = NexusCryptoEngine.generateRoomKey()
            val nextVersion = room.keyVersion + 1L
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "roomKeyBase64" to nextBundle.roomKeyBase64,
                    "keyFingerprint" to nextBundle.keyFingerprint,
                    "keyVersion" to nextVersion,
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

    suspend fun leaveRoom(room: ChatRoom): Result<Unit> {
        val docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            require(room.id.isNotBlank()) { "Room ID is required" }
            check(room.ownerId != uid) { "Room creators cannot leave; delete the room instead." }
            db.collection("rooms").document(room.id).update(
                mapOf(
                    "memberUids" to FieldValue.arrayRemove(uid),
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

    suspend fun deleteRoom(room: ChatRoom): Result<Unit> {
        var docPath = "rooms/${room.id}"
        return try {
            val uid = requireUserId()
            check(room.ownerId == uid) { "Only the room creator can delete this room." }
            if (room.inviteCode.isNotBlank()) {
                docPath = "room_invites/${room.inviteCode}"
                runCatching { db.collection("room_invites").document(room.inviteCode).delete().await() }
            }
            docPath = "rooms/${room.id}"
            db.collection("rooms").document(room.id).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- ENCRYPTED ROOM MESSAGES ---

    fun observeRoomMessages(roomId: String, userId: String): Flow<List<EncryptedMessage>> = flow {
        require(roomId.isNotBlank() && userId.isNotBlank()) { "roomId and userId must not be blank" }
        val path = "rooms/$roomId/messages"
        emitAll(
            db.collection("rooms").document(roomId).collection("messages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(EncryptedMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun sendEncryptedMessage(room: ChatRoom, plaintext: String): Result<Unit> {
        val cleanText = plaintext.trim()
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        if (cleanText.length > 4000) {
            return Result.failure(IllegalArgumentException("Message exceeds 4,000 character limit."))
        }
        var docPath = "rooms/${room.id}/messages"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val encrypted = NexusCryptoEngine.encryptText(cleanText, room.roomKeyBase64)
            val previewCipher = NexusCryptoEngine.encryptText(cleanText.take(160), room.roomKeyBase64)

            val roomRef = db.collection("rooms").document(room.id)
            val msgRef = roomRef.collection("messages").document()
            docPath = msgRef.path

            val msgPayload = mapOf(
                "roomId" to room.id,
                "senderId" to uid,
                "senderName" to senderName,
                "memberUids" to room.memberUids,
                "messageType" to "text",
                "ciphertextBase64" to encrypted.ciphertextBase64,
                "ivBase64" to encrypted.ivBase64,
                "keyVersion" to room.keyVersion,
                "status" to "active",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(msgPayload).await()

            docPath = roomRef.path
            roomRef.update(
                mapOf(
                    "lastActivityPreviewCipher" to previewCipher.ciphertextBase64,
                    "lastActivityIv" to previewCipher.ivBase64,
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

    suspend fun burnMessage(roomId: String, message: EncryptedMessage): Result<Unit> {
        val docPath = "rooms/$roomId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            check(message.senderId == uid) { "Only the message author can shred this card." }
            db.collection("rooms").document(roomId).collection("messages").document(message.id)
                .update(
                    mapOf(
                        "status" to "burned",
                        "ciphertextBase64" to "U0hSRUREREQ=",
                        "ivBase64" to message.ivBase64,
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

    // --- TEMPORARY ENCRYPTED MEDIA DROPS (UP TO 4.00 GB) ---

    fun observeMediaDrops(userId: String): Flow<List<MediaDrop>> = flow {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val path = "media_drops"
        emitAll(
            db.collection("media_drops")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(MediaDrop::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun allocateDropId(): String = db.collection("media_drops").document().id

    suspend fun publishEncryptedMediaDrop(
        dropId: String,
        room: ChatRoom,
        channelId: String = "general",
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
            return Result.failure(
                IllegalArgumentException("File size must be between 1 byte and 4.00 GB (4,294,967,296 bytes).")
            )
        }
        val safeTtl = if (ttlMinutes in NexusLimits.ALLOWED_TTL_MINUTES) ttlMinutes else 60
        val sanitizedName = fileName.trim().take(200).ifBlank { "encrypted_drop.bin" }
        val sanitizedMime = mimeType.trim().take(100).ifBlank { "application/octet-stream" }

        var docPath = "media_drops/$dropId"
        return try {
            val uid = requireUserId()
            val senderName = currentDisplayName()
            val expiresAtDate = Date(System.currentTimeMillis() + safeTtl * 60_000L)

            val dropPayload = mapOf<String, Any?>(
                "roomId" to room.id,
                "roomName" to room.name,
                "senderId" to uid,
                "senderName" to senderName,
                "memberUids" to room.memberUids,
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
                append("Shared encrypted media: $sanitizedName (${NexusLimits.formatBytes(fileSizeBytes)})")
                if (caption.isNotBlank()) {
                    append("\n${caption.trim().take(500)}")
                }
            }
            val encMsg = NexusCryptoEngine.encryptText(summaryText, room.roomKeyBase64)
            val previewCipher = NexusCryptoEngine.encryptText(
                "Encrypted Drop • $sanitizedName (${NexusLimits.formatBytes(fileSizeBytes)})",
                room.roomKeyBase64
            )

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
                    "messageType" to "media",
                    "ciphertextBase64" to encMsg.ciphertextBase64,
                    "ivBase64" to encMsg.ivBase64,
                    "keyVersion" to room.keyVersion,
                    "mediaDropId" to dropId,
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
                    "lastActivityPreviewCipher" to previewCipher.ciphertextBase64,
                    "lastActivityIv" to previewCipher.ivBase64,
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

    suspend fun recordMediaDropViewed(drop: MediaDrop): Result<Unit> {
        val docPath = "media_drops/${drop.id}"
        return try {
            val uid = requireUserId()
            if (drop.status != "active") return Result.success(Unit)
            val nextViewers = if (uid in drop.viewedByUids) drop.viewedByUids else drop.viewedByUids + uid
            val updateMap = if (drop.burnAfterView) {
                mapOf(
                    "viewedByUids" to nextViewers,
                    "status" to "burned",
                    "encryptedPreviewBase64" to "",
                    "previewIvBase64" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            } else {
                mapOf(
                    "viewedByUids" to nextViewers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            }
            db.collection("media_drops").document(drop.id).update(updateMap).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun burnMediaDrop(drop: MediaDrop): Result<Unit> {
        val docPath = "media_drops/${drop.id}"
        return try {
            requireUserId()
            if (drop.status != "active") return Result.success(Unit)
            db.collection("media_drops").document(drop.id).update(
                mapOf(
                    "status" to "burned",
                    "encryptedPreviewBase64" to "",
                    "previewIvBase64" to "",
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

    // --- END-TO-END ENCRYPTED DIRECT MESSAGING ---

    fun observeDirectThreads(userId: String): Flow<List<DirectThread>> = flow {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val path = "direct_threads"
        emitAll(
            db.collection("direct_threads")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(DirectThread::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.lastActivityAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeDirectMessages(threadId: String, userId: String): Flow<List<DirectMessage>> = flow {
        require(threadId.isNotBlank() && userId.isNotBlank()) { "threadId and userId must not be blank" }
        val path = "direct_threads/$threadId/messages"
        emitAll(
            db.collection("direct_threads").document(threadId).collection("messages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(DirectMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun startDirectThreadByContactCode(rawContactCode: String): Result<String> {
        val code = rawContactCode.trim().uppercase()
        if (code.length !in 6..24 || !code.matches(Regex("^[A-Z0-9\\-]+$"))) {
            return Result.failure(IllegalArgumentException("Enter a valid Contact Code (e.g. NX-7K9P-2M4Q)."))
        }
        var docPath = "contact_directory/$code"
        return try {
            val myUid = requireUserId()
            val myName = currentDisplayName()
            val myIdentitySnap = db.collection("users").document(myUid).get().await()
            val myFingerprint = myIdentitySnap.getString("publicKeyFingerprint") ?: "EC-P256-VERIFIED"

            val contactSnap = db.collection("contact_directory").document(code).get().await()
            if (!contactSnap.exists()) {
                return Result.failure(IllegalArgumentException("Contact Code \"$code\" was not found."))
            }
            val peerUid = contactSnap.getString("userId")
                ?: return Result.failure(IllegalStateException("Contact directory record is incomplete."))
            val peerName = contactSnap.getString("displayName")?.take(60)?.ifBlank { "Chatty Peer" } ?: "Chatty Peer"
            val peerFingerprint = contactSnap.getString("publicKeyFingerprint") ?: "EC-P256-PEER"

            val isSelf = peerUid == myUid
            val participantUids = if (isSelf) listOf(myUid) else listOf(myUid, peerUid).sorted()
            val threadId = if (isSelf) "dm_self_$myUid" else "dm_${participantUids[0]}_${participantUids[1]}"

            val threadRef = db.collection("direct_threads").document(threadId)
            docPath = threadRef.path
            val existing = threadRef.get().await()
            if (existing.exists()) {
                return Result.success(threadId)
            }

            val keyBundle = NexusCryptoEngine.generateRoomKey()
            val participantNames = if (isSelf) {
                mapOf(myUid to myName)
            } else {
                mapOf(myUid to myName, peerUid to peerName)
            }
            val participantFingerprints = if (isSelf) {
                mapOf(myUid to myFingerprint)
            } else {
                mapOf(myUid to myFingerprint, peerUid to peerFingerprint)
            }

            val initEnc = NexusCryptoEngine.encryptText(
                if (isSelf) "Private AES-256-GCM Saved Notes initialized."
                else "End-to-end encrypted direct session established with AES-256-GCM.",
                keyBundle.roomKeyBase64
            )

            threadRef.set(
                mapOf(
                    "participantUids" to participantUids,
                    "participantNames" to participantNames,
                    "participantFingerprints" to participantFingerprints,
                    "threadKeyBase64" to keyBundle.roomKeyBase64,
                    "keyFingerprint" to keyBundle.keyFingerprint,
                    "keyVersion" to 1,
                    "lastMessageCipher" to initEnc.ciphertextBase64,
                    "lastMessageIv" to initEnc.ivBase64,
                    "lastMessageSenderId" to myUid,
                    "lastReadByUids" to listOf(myUid),
                    "typingUserIds" to emptyList<String>(),
                    "typingUpdatedAt" to FieldValue.serverTimestamp(),
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(threadId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun setDirectTypingState(thread: DirectThread, isTyping: Boolean): Result<Unit> {
        val docPath = "direct_threads/${thread.id}"
        return try {
            val uid = requireUserId()
            val already = uid in thread.typingUserIds
            if (isTyping == already) return Result.success(Unit)
            val nextTyping = if (isTyping) {
                (thread.typingUserIds + uid).distinct()
            } else {
                thread.typingUserIds.filter { it != uid }
            }
            db.collection("direct_threads").document(thread.id).update(
                mapOf(
                    "typingUserIds" to nextTyping,
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

    suspend fun markDirectMessagesRead(thread: DirectThread, unreadMessages: List<DirectMessage>): Result<Unit> {
        var docPath = "direct_threads/${thread.id}"
        return try {
            val uid = requireUserId()
            val threadRef = db.collection("direct_threads").document(thread.id)

            if (uid !in thread.lastReadByUids) {
                val nextLastRead = (thread.lastReadByUids + uid).distinct()
                threadRef.update(
                    mapOf(
                        "lastReadByUids" to nextLastRead,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            for (msg in unreadMessages.take(30)) {
                if (msg.senderId != uid && uid !in msg.readByUids && msg.status == "active") {
                    val msgRef = threadRef.collection("messages").document(msg.id)
                    docPath = msgRef.path
                    val nextReadBy = (msg.readByUids + uid).distinct()
                    msgRef.update(
                        mapOf(
                            "readByUids" to nextReadBy,
                            "readAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun rotateDirectThreadKey(thread: DirectThread): Result<Unit> {
        val docPath = "direct_threads/${thread.id}"
        return try {
            requireUserId()
            val nextBundle = NexusCryptoEngine.generateRoomKey()
            db.collection("direct_threads").document(thread.id).update(
                mapOf(
                    "threadKeyBase64" to nextBundle.roomKeyBase64,
                    "keyFingerprint" to nextBundle.keyFingerprint,
                    "keyVersion" to (thread.keyVersion + 1L),
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

    suspend fun burnDirectMessage(threadId: String, message: DirectMessage): Result<Unit> {
        val docPath = "direct_threads/$threadId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            check(message.senderId == uid) { "Only the author can shred this direct message." }
            db.collection("direct_threads").document(threadId).collection("messages").document(message.id)
                .update(
                    mapOf(
                        "status" to "burned",
                        "ciphertextBase64" to "U0hSRUREREQ=",
                        "ivBase64" to message.ivBase64,
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

    suspend fun deleteDirectThread(thread: DirectThread): Result<Unit> {
        val docPath = "direct_threads/${thread.id}"
        return try {
            requireUserId()
            db.collection("direct_threads").document(thread.id).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
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
}
