package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Locale

object NexusLimits {
    /** Exact 4.00 GiB maximum file size limit (4,294,967,296 bytes) enforced across UI & Firestore rules. */
    const val MAX_FILE_SIZE_BYTES: Long = 4_294_967_296L
    const val MAX_ROOM_MEMBERS: Int = 50
    val ALLOWED_TTL_MINUTES: List<Int> = listOf(5, 60, 1440)
    val ALLOWED_SLOW_MODE_SECONDS: List<Int> = listOf(0, 5, 15, 60)
    val ACCENT_TAGS: List<String> = listOf("emerald", "cyan", "amber", "slate", "violet")
    val PRESENCE_STATUSES: List<String> = listOf("online", "focus", "dnd", "invisible")
    val QUICK_REACTIONS: List<String> = listOf("👍", "🔥", "🔒", "🚀", "❤️", "👀")

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatTtlLabel(ttlMinutes: Int): String = when (ttlMinutes) {
        5 -> "5 min"
        60 -> "1 hour"
        1440 -> "24 hours"
        else -> "${ttlMinutes}m"
    }

    fun formatDurationMs(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    fun formatPresenceLabel(status: String?): String = when (status) {
        "focus" -> "Focus"
        "dnd" -> "Do Not Disturb"
        "invisible" -> "Invisible"
        else -> "Online"
    }

    fun formatRemainingCountdown(expiresAt: Timestamp?, nowMillis: Long): String {
        val expMs = expiresAt?.toDate()?.time ?: return "Expired"
        val diff = expMs - nowMillis
        if (diff <= 0L) return "Expired"
        val totalSeconds = diff / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02dm %02ds", minutes, seconds)
        }
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ReplyTarget(
    val messageId: String,
    val senderName: String,
    val previewText: String
)

data class DecryptedPollData(
    val question: String,
    val options: List<String>
)

@Keep
@IgnoreExtraProperties
data class UserIdentity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val contactCode: String? = null,
    val presenceStatus: String? = "online",
    val statusText: String? = "",
    val publicKeyFingerprint: String = "",
    val publicKeyBase64: String = "",
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ContactDirectoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val contactCode: String = "",
    val presenceStatus: String? = "online",
    val statusText: String? = "",
    val publicKeyFingerprint: String = "",
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatRoom(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val name: String = "",
    val topic: String = "",
    val inviteCode: String = "",
    val accentTag: String = "emerald",
    val roomKeyBase64: String = "",
    val keyFingerprint: String = "",
    val keyVersion: Long = 1L,
    val memberUids: List<String> = emptyList(),
    val memberNames: Map<String, String>? = null,
    val memberRoles: Map<String, String>? = null,
    val typingUserIds: List<String>? = null,
    val typingUpdatedAt: Timestamp? = null,
    val slowModeSeconds: Long? = 0L,
    val defaultTtlMinutes: Long = 60L,
    val maxFileSizeBytes: Long = NexusLimits.MAX_FILE_SIZE_BYTES,
    val lastActivityPreviewCipher: String? = null,
    val lastActivityIv: String? = null,
    val lastSenderName: String? = null,
    @ServerTimestamp val lastActivityAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun roleOf(uid: String): String {
        if (uid == ownerId) return "owner"
        return memberRoles?.get(uid) ?: "member"
    }

    fun isOwnerOrAdmin(uid: String): Boolean {
        val role = roleOf(uid)
        return role == "owner" || role == "admin"
    }

    fun activeTypingPeers(currentUserId: String, nowMillis: Long): List<String> {
        val updatedMs = typingUpdatedAt?.toDate()?.time ?: return emptyList()
        if (nowMillis - updatedMs > 12_000L) return emptyList()
        return typingUserIds.orEmpty()
            .filter { it != currentUserId }
            .map { uid -> memberNames?.get(uid) ?: "Member" }
    }
}

@Keep
@IgnoreExtraProperties
data class RoomChannel(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val ownerId: String = "",
    val name: String = "general",
    val channelType: String = "text",
    val topic: String = "",
    val voiceParticipantUids: List<String> = emptyList(),
    val voiceParticipantNames: Map<String, String> = emptyMap(),
    val mutedUids: List<String> = emptyList(),
    val raisedHandUids: List<String> = emptyList(),
    val activeSpeakerUid: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isVoice(): Boolean = channelType == "voice"
    fun isAnnouncement(): Boolean = channelType == "announcement"
}

@Keep
@IgnoreExtraProperties
data class RoomInvite(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val roomName: String = "",
    val ownerId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class EncryptedMessage(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val channelId: String? = "general",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val messageType: String = "text",
    val ciphertextBase64: String = "",
    val ivBase64: String = "",
    val keyVersion: Long = 1L,
    val mediaDropId: String? = null,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToSnippetCipher: String? = null,
    val replyToSnippetIv: String? = null,
    val reactions: Map<String, List<String>>? = null,
    val pinned: Boolean? = false,
    val edited: Boolean? = false,
    val encryptedAudioBase64: String? = null,
    val audioIvBase64: String? = null,
    val audioDurationMs: Long? = null,
    val pollOptionCount: Long? = null,
    val pollVotes: Map<String, String>? = null,
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveChannelId(): String = channelId?.takeIf { it.isNotBlank() } ?: "general"
}

@Keep
@IgnoreExtraProperties
data class DirectThread(
    @DocumentId val id: String = "",
    val participantUids: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantFingerprints: Map<String, String> = emptyMap(),
    val threadKeyBase64: String = "",
    val keyFingerprint: String = "",
    val keyVersion: Long = 1L,
    val lastMessageCipher: String? = null,
    val lastMessageIv: String? = null,
    val lastMessageSenderId: String? = null,
    val lastReadByUids: List<String> = emptyList(),
    val typingUserIds: List<String> = emptyList(),
    val typingUpdatedAt: Timestamp? = null,
    @ServerTimestamp val lastActivityAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isSelfThread(currentUserId: String): Boolean {
        return participantUids.size == 1 && participantUids.firstOrNull() == currentUserId
    }

    fun peerUid(currentUserId: String): String {
        return participantUids.firstOrNull { it != currentUserId } ?: currentUserId
    }

    fun peerDisplayName(currentUserId: String): String {
        if (isSelfThread(currentUserId)) return "Saved Notes (Self)"
        val peer = peerUid(currentUserId)
        return participantNames[peer]?.takeIf { it.isNotBlank() } ?: "Chatty Peer"
    }

    fun peerFingerprint(currentUserId: String): String {
        val peer = peerUid(currentUserId)
        return participantFingerprints[peer]?.takeIf { it.isNotBlank() } ?: keyFingerprint
    }

    fun isPeerTyping(currentUserId: String, nowMillis: Long): Boolean {
        val updatedMs = typingUpdatedAt?.toDate()?.time ?: return false
        if (nowMillis - updatedMs > 12_000L) return false
        return typingUserIds.any { it != currentUserId }
    }

    fun hasUnreadFor(currentUserId: String): Boolean {
        if (lastMessageSenderId.isNullOrBlank()) return false
        if (lastMessageSenderId == currentUserId) return false
        return currentUserId !in lastReadByUids
    }
}

@Keep
@IgnoreExtraProperties
data class DirectMessage(
    @DocumentId val id: String = "",
    val threadId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val participantUids: List<String> = emptyList(),
    val messageType: String? = "text",
    val ciphertextBase64: String = "",
    val ivBase64: String = "",
    val keyVersion: Long = 1L,
    val mediaDropId: String? = null,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToSnippetCipher: String? = null,
    val replyToSnippetIv: String? = null,
    val reactions: Map<String, List<String>>? = null,
    val pinned: Boolean? = false,
    val edited: Boolean? = false,
    val encryptedAudioBase64: String? = null,
    val audioIvBase64: String? = null,
    val audioDurationMs: Long? = null,
    val readByUids: List<String> = emptyList(),
    val readAt: Timestamp? = null,
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveType(): String = messageType?.takeIf { it.isNotBlank() } ?: "text"

    fun isReadByPeer(senderUid: String): Boolean {
        return readByUids.any { it != senderUid }
    }
}

@Keep
@IgnoreExtraProperties
data class MediaDrop(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val roomName: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val fileSizeBytes: Long = 0L,
    val sha256Hash: String = "",
    val cipherAlgorithm: String = "AES-256-GCM",
    val ivBase64: String = "",
    val encryptedPreviewBase64: String? = null,
    val previewIvBase64: String? = null,
    val ttlMinutes: Long = 60L,
    val burnAfterView: Boolean = false,
    val status: String = "active",
    val viewedByUids: List<String> = emptyList(),
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isExpiredAt(nowMillis: Long): Boolean {
        val exp = expiresAt?.toDate()?.time ?: return true
        return nowMillis >= exp
    }

    fun isBurned(): Boolean = status == "burned"
}

data class DecryptedMessageItem(
    val message: EncryptedMessage,
    val decryptedText: String,
    val decryptedReplySnippet: String? = null,
    val decryptedPoll: DecryptedPollData? = null,
    val decryptionVerified: Boolean,
    val linkedDrop: MediaDrop? = null
)

data class DecryptedDirectMessageItem(
    val message: DirectMessage,
    val decryptedText: String,
    val decryptedReplySnippet: String? = null,
    val decryptionVerified: Boolean,
    val linkedDrop: MediaDrop? = null
)

data class PendingMediaSelection(
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val exceedsLimit: Boolean
)
