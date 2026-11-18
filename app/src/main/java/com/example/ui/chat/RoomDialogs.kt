package com.example.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ChatRoom
import com.example.data.DecryptedMessageItem
import com.example.data.NexusLimits
import com.example.ui.components.NexusPillBadge
import com.example.ui.components.formatExactClock

@Composable
fun CreateChannelDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, topic: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("text") }
    val valid = name.trim().length in 2..36

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.action_new_channel), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CHANNEL TYPE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = type == "text",
                        onClick = { type = "text" },
                        leadingIcon = { Icon(Icons.Rounded.Tag, null, Modifier.size(16.dp)) },
                        label = { Text("Text") }
                    )
                    FilterChip(
                        selected = type == "voice",
                        onClick = { type = "voice" },
                        leadingIcon = { Icon(Icons.Rounded.Headphones, null, Modifier.size(16.dp)) },
                        label = { Text("Voice") }
                    )
                    FilterChip(
                        selected = type == "announcement",
                        onClick = { type = "announcement" },
                        leadingIcon = { Icon(Icons.Rounded.Campaign, null, Modifier.size(16.dp)) },
                        label = { Text("Stage") }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 36) name = it },
                    label = { Text("Channel name") },
                    placeholder = { Text(if (type == "voice") "e.g. Ops Standup" else "e.g. design-review") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_channel_name_input")
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { if (it.length <= 140) topic = it },
                    label = { Text("Topic (optional)") },
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), type, topic.trim()) },
                enabled = valid && !isBusy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_create_channel_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CreatePollDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (question: String, options: List<String>) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val validOptions = options.map { it.trim() }.filter { it.isNotBlank() }
    val canSubmit = question.trim().length >= 3 && validOptions.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Poll, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.action_create_poll), style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Question & options are encrypted locally with AES-256-GCM before syncing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= 240) question = it },
                    label = { Text("Poll question") },
                    placeholder = { Text("e.g. Which release cut ships Friday?") },
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("poll_question_input")
                )
                options.forEachIndexed { idx, value ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (it.length <= 100) options[idx] = it },
                        label = { Text("Option ${idx + 1}") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (options.size < 5) {
                    TextButton(onClick = { options.add("") }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Option")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(question.trim(), validOptions) },
                enabled = canSubmit && !isBusy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_create_poll_button")
            ) {
                Text("Post Encrypted Poll")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PinnedMessagesDialog(
    pinnedItems: List<DecryptedMessageItem>,
    onUnpin: (DecryptedMessageItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.label_pinned_cards), style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            if (pinnedItems.isEmpty()) {
                Text(
                    text = "No pinned cards in this channel yet. Tap the pin action on any encrypted message to anchor it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 340.dp)
                ) {
                    items(pinnedItems, key = { it.message.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${item.message.senderName} • ${formatExactClock(item.message.createdAt)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TextButton(onClick = { onUnpin(item) }) {
                                        Text("Unpin", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(
                                    text = item.decryptedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Close") }
        }
    )
}

@Composable
fun MembersAndRolesDialog(
    room: ChatRoom,
    currentUserId: String,
    isBusy: Boolean,
    onUpdateRole: (targetUid: String, role: String) -> Unit,
    onKickMember: (targetUid: String) -> Unit,
    onUpdateSlowMode: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val isOwner = room.ownerId == currentUserId
    val slowMode = (room.slowModeSeconds ?: 0L).toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("${stringResource(R.string.label_members_roles)} (${room.memberUids.size})", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isOwner) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SLOW-MODE COOLDOWN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NexusLimits.ALLOWED_SLOW_MODE_SECONDS.forEach { sec ->
                                FilterChip(
                                    selected = slowMode == sec,
                                    onClick = { onUpdateSlowMode(sec) },
                                    label = { Text(if (sec == 0) "Off" else "${sec}s", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 280.dp)
                ) {
                    items(room.memberUids, key = { it }) { uid ->
                        val name = room.memberNames?.get(uid)
                            ?: if (uid == currentUserId) "You" else "Member ${uid.take(6)}"
                        val role = room.roleOf(uid)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (uid == currentUserId) "$name (You)" else name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    NexusPillBadge(text = role.uppercase())
                                }
                                if (isOwner && uid != room.ownerId) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val nextRole = when (role) {
                                            "member" -> "moderator"
                                            "moderator" -> "admin"
                                            else -> "member"
                                        }
                                        TextButton(
                                            onClick = { onUpdateRole(uid, nextRole) },
                                            enabled = !isBusy
                                        ) {
                                            Text("→ ${nextRole.take(3).uppercase()}", style = MaterialTheme.typography.labelSmall)
                                        }
                                        IconButton(
                                            onClick = { onKickMember(uid) },
                                            enabled = !isBusy,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PersonRemove,
                                                contentDescription = "Remove member",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Done") }
        }
    )
}

@Composable
fun RoomSecurityDialog(
    room: ChatRoom,
    isOwner: Boolean,
    inspectRawCipher: Boolean,
    isBusy: Boolean,
    onCopyInviteCode: () -> Unit,
    onToggleInspectCipher: () -> Unit,
    onRotateKey: () -> Unit,
    onLeaveRoom: () -> Unit,
    onDeleteRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Space Encryption Vault", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("INVITE CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(room.inviteCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(
                                onClick = {
                                    onCopyInviteCode()
                                    copied = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (copied) "Copied" else stringResource(R.string.action_copy_code), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("KEY FINGERPRINT (AES-256-GCM • v${room.keyVersion})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(room.keyFingerprint, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "Max temporary file transfer: ${NexusLimits.formatBytes(room.maxFileSizeBytes)} (No paywall)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.label_inspect_cipher),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = inspectRawCipher, onCheckedChange = { onToggleInspectCipher() })
                }

                if (isOwner) {
                    OutlinedButton(
                        onClick = onRotateKey,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_rotate_key))
                    }
                    TextButton(
                        onClick = onDeleteRoom,
                        enabled = !isBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Space")
                    }
                } else {
                    TextButton(
                        onClick = onLeaveRoom,
                        enabled = !isBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave Space")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Close") }
        }
    )
}
