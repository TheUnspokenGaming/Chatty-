package com.example.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.NexusCryptoEngine
import com.example.data.ChatRoom
import com.example.data.MediaDrop
import com.example.data.NexusLimits
import com.example.data.UiState
import com.example.ui.components.DecryptedMediaInspectDialog
import com.example.ui.components.FileCapacityBar
import com.example.ui.components.NexusPillBadge
import com.example.ui.components.iconForMimeType
import kotlinx.coroutines.delay

private enum class VaultFilter(val label: String) {
    ACTIVE("Active"),
    SHREDDED("Shredded"),
    ALL("All")
}

@Composable
fun VaultScreen(
    dropsState: UiState<List<MediaDrop>>,
    currentUserId: String,
    onDecryptPreview: (MediaDrop) -> ByteArray?,
    onMarkDropViewed: (MediaDrop) -> Unit,
    onBurnDrop: (MediaDrop) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(VaultFilter.ACTIVE) }
    var inspectingDrop by remember { mutableStateOf<MediaDrop?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            Text(
                text = "Media Vault",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "TEMPORARY AES-256-GCM TRANSFERS • 4.00 GB LIMIT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Architectural Vault Banner Card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_nexus_vault_banner_1794975569687),
                    contentDescription = stringResource(R.string.cd_vault_banner),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NexusPillBadge(text = "4.00 GB MAX PER FILE", icon = Icons.Rounded.Lock)
                    Text(
                        text = "Ephemeral Encrypted Drops",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Streaming 64 KB chunks with SHA-256 verification & auto-shred.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Filter Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            VaultFilter.entries.forEach { tab ->
                FilterChip(
                    selected = filter == tab,
                    onClick = { filter = tab },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("vault_filter_${tab.name.lowercase()}")
                )
            }
        }

        when (dropsState) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            is UiState.Error -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = dropsState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            is UiState.Success -> {
                val filtered = dropsState.data.filter { drop ->
                    val active = !drop.isBurned() && !drop.isExpiredAt(nowMillis)
                    when (filter) {
                        VaultFilter.ACTIVE -> active
                        VaultFilter.SHREDDED -> !active
                        VaultFilter.ALL -> true
                    }
                }
                if (filtered.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = when (filter) {
                                    VaultFilter.ACTIVE -> "No active temporary media drops"
                                    VaultFilter.SHREDDED -> "No shredded drops recorded"
                                    VaultFilter.ALL -> "Your encrypted media vault is empty"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Open any encrypted room and attach a photo, video, or file up to 4.00 GB to create a self-destructing drop.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(filtered, key = { it.id }) { drop ->
                            VaultDropCard(
                                drop = drop,
                                isSender = drop.senderId == currentUserId,
                                nowMillis = nowMillis,
                                onInspect = {
                                    inspectingDrop = drop
                                    onMarkDropViewed(drop)
                                },
                                onBurn = { onBurnDrop(drop) }
                            )
                        }
                    }
                }
            }
        }
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
private fun VaultDropCard(
    drop: MediaDrop,
    isSender: Boolean,
    nowMillis: Long,
    onInspect: () -> Unit,
    onBurn: () -> Unit
) {
    val expired = drop.isExpiredAt(nowMillis)
    val shredded = drop.isBurned() || expired

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (shredded) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vault_drop_card_${drop.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (shredded) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (shredded) Icons.Rounded.LocalFireDepartment else iconForMimeType(drop.mimeType),
                        contentDescription = null,
                        tint = if (shredded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = drop.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${drop.roomName} • by ${drop.senderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FileCapacityBar(fileSizeBytes = drop.fileSizeBytes)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NexusPillBadge(
                    text = if (shredded) {
                        if (drop.isBurned()) "SHREDDED" else "EXPIRED"
                    } else {
                        NexusLimits.formatRemainingCountdown(drop.expiresAt, nowMillis)
                    },
                    containerColor = if (shredded) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (shredded) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = if (shredded) Icons.Rounded.LocalFireDepartment else Icons.Rounded.Timer
                )
                NexusPillBadge(
                    text = "SHA ${drop.sha256Hash.take(8)}",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (drop.burnAfterView && !shredded) {
                    NexusPillBadge(
                        text = "1x VIEW",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!shredded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onInspect,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_decrypt_view))
                    }
                    if (isSender) {
                        OutlinedButton(
                            onClick = onBurn,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Shred")
                        }
                    }
                }
            }
        }
    }
}
