package com.contentstudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.HubProviderInfo
import com.contentstudio.app.runtime.HubSettingsSnapshot
import com.contentstudio.app.runtime.LinuxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PickerCanvas = Color(0xFF0B0C0E)
private val PickerSurface = Color(0xFF15171C)
private val PickerActive = Color(0xFF1E2128)
private val PickerAccent = Color(0xFF2A2F3A)
private val PickerText = Color(0xFFF2F4F8)
private val PickerMuted = Color(0xFF8A919E)
private val PickerSafe = Color(0xFF34D399)
private val PickerWarn = Color(0xFFFBBF24)
private val PickerDanger = Color(0xFFF87171)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioModelPickerSheet(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<HubSettingsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf("") }
    var localEffort by remember { mutableStateOf(ChatController.hubEffort.value) }
    var localFallback by remember { mutableStateOf(true) }
    val activeProvider = ChatController.hubProvider.value
    val activeModel = ChatController.hubModel.value

    fun reload() {
        scope.launch {
            loading = true
            val snap = withContext(Dispatchers.IO) { LinuxRuntimeManager.getHubModels() }
            if (snap != null) {
                snapshot = snap
                localEffort = snap.effort
                localFallback = snap.autoFallback
                val selectedQuota = if (snap.activeProvider == "auto") {
                    snap.quota.values.firstOrNull { it.status == "exhausted" }
                        ?: snap.quota.values.firstOrNull { it.status == "warning" }
                        ?: snap.quota.values.firstOrNull()
                } else {
                    snap.quota[snap.activeProvider]
                }
                ChatController.setHubQuotaStatus(selectedQuota?.status ?: "ok")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun persist(provider: String, model: String, effort: String, autoFallback: Boolean) {
        ChatController.setHubSelection(provider, model, effort)
        val snap = snapshot
        if (snap != null) {
            val selectedQuota = if (provider == "auto") {
                snap.quota.values.firstOrNull { it.status == "exhausted" }
                    ?: snap.quota.values.firstOrNull { it.status == "warning" }
                    ?: snap.quota.values.firstOrNull()
            } else {
                snap.quota[provider]
            }
            ChatController.setHubQuotaStatus(selectedQuota?.status ?: "ok")
        }
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("active_provider", provider)
                put("active_model", model)
                put("reasoning_effort", effort)
                put("auto_fallback", autoFallback)
            }
            LinuxRuntimeManager.saveHubSettings(payload)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PickerSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Pilih Model",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PickerText
                    )
                    Text(
                        text = "Model AI aktif & verifikasi BYOK",
                        fontSize = 11.sp,
                        color = PickerMuted
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PickerActive)
                        .clickable {
                            if (syncing) return@clickable
                            syncing = true
                            scope.launch {
                                withContext(Dispatchers.IO) { LinuxRuntimeManager.refreshHubCatalog() }
                                reload()
                                syncing = false
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Sync Live",
                        tint = if (syncing) PickerWarn else PickerMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (syncing) "Sync..." else "Sync Live",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (syncing) PickerWarn else PickerMuted
                    )
                }
            }

            // Kartu Auto (Smart Router)
            val autoActive = activeProvider == "auto"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (autoActive) PickerActive else PickerCanvas)
                    .clickable {
                        persist("auto", "smart-auto", localEffort, localFallback)
                    }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Auto (Smart Router)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PickerText
                            )
                            if (autoActive) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Aktif",
                                    tint = PickerSafe,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Pilih model gratis terbaik otomatis sesuai tugas: Visual, Riset, Produksi, Cepat.",
                            fontSize = 11.sp,
                            color = PickerMuted
                        )
                    }
                    Switch(
                        checked = localFallback,
                        onCheckedChange = { checked ->
                            localFallback = checked
                            persist(ChatController.hubProvider.value, ChatController.hubModel.value, localEffort, checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PickerCanvas,
                            checkedTrackColor = PickerSafe,
                            uncheckedThumbColor = PickerMuted,
                            uncheckedTrackColor = PickerAccent
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Auto-Pindah",
                        fontSize = 11.sp,
                        color = PickerMuted
                    )
                    Text(
                        text = "429 lalu provider cadangan",
                        fontSize = 9.sp,
                        color = PickerAccent
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "Effort", fontSize = 11.sp, color = PickerMuted)
                    listOf("low", "medium", "high").forEach { level ->
                        val selected = localEffort == level
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) PickerAccent else PickerCanvas)
                                .clickable {
                                    localEffort = level
                                    persist(
                                        ChatController.hubProvider.value,
                                        ChatController.hubModel.value,
                                        level,
                                        localFallback
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = level.replaceFirstChar { it.uppercase() },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) PickerText else PickerMuted
                            )
                        }
                    }
                }
            }

            if (loading) {
                Text(
                    text = "Memuat katalog provider...",
                    fontSize = 11.sp,
                    color = PickerMuted,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            snapshot?.providers?.filter { it.id == "antigravity" || it.configured || it.models.isNotEmpty() }?.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    activeProvider = activeProvider,
                    activeModel = activeModel,
                    quota = snapshot?.quota?.get(provider.id),
                    expanded = expandedProvider == provider.id,
                    onToggleExpand = {
                        expandedProvider = if (expandedProvider == provider.id) "" else provider.id
                    },
                    onSelect = { modelId ->
                        persist(provider.id, modelId, localEffort, localFallback)
                        onDismiss()
                    },
                    onOpenSettings = {
                        onDismiss()
                        onOpenSettings()
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(PickerActive)
                    .clickable {
                        onDismiss()
                        onOpenSettings()
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Atur API Key di Pengaturan",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = PickerText
                )
            }

            Text(
                text = "Kunci API disimpan lokal di perangkat dan tidak pernah ditampilkan penuh.",
                fontSize = 10.sp,
                color = PickerMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: HubProviderInfo,
    activeProvider: String,
    activeModel: String,
    quota: com.contentstudio.app.runtime.HubQuotaInfo?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isActive = activeProvider == provider.id
    val status = quota?.status ?: "ok"
    val dotColor = when (status) {
        "exhausted" -> PickerDanger
        "warning" -> PickerWarn
        else -> PickerSafe
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) PickerActive else PickerCanvas)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor)
            )
            Text(
                text = provider.label.ifBlank { provider.id },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = PickerText,
                modifier = Modifier.weight(1f)
            )
            if (provider.kind == "cli") {
                Text(
                    text = "CLI",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = PickerMuted
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Tutup" else "Buka model",
                tint = PickerMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        if (provider.models.any { it.multimodal } || provider.models.any { it.tools } || provider.models.any { it.reasoning }) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (provider.models.any { it.multimodal }) Badge("Vision")
                if (provider.models.any { it.tools }) Badge("Tools")
                if (provider.models.any { it.reasoning }) Badge("Reasoning")
                if (provider.models.any { it.coding }) Badge("Coding")
            }
        }

        if (!provider.configured && provider.kind != "cli") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(PickerSurface)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "API key belum diisi",
                    fontSize = 10.sp,
                    color = PickerWarn
                )
                Text(
                    text = "Buka Settings",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PickerText
                )
            }
        }

        if (quota != null) {
            val fraction = (quota.percent / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PickerAccent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(dotColor)
                )
            }
            Text(
                text = quota.label.ifBlank { "Kuota dinamis" },
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = PickerMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(PickerSurface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val models = provider.models.take(40)
                models.forEach { model ->
                    val selected = isActive && model.id == activeModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selected) PickerActive else Color.Transparent)
                            .clickable { onSelect(model.id) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = model.label.ifBlank { model.id },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (selected) PickerText else PickerMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (model.multimodal) MiniTag("Vision")
                            if (model.tools) MiniTag("Tools")
                            if (model.reasoning) MiniTag("Reason")
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Aktif",
                                    tint = PickerSafe,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
                if (models.isEmpty()) {
                    Text(
                        text = "Belum ada model. Sync Live atau isi API key.",
                        fontSize = 10.sp,
                        color = PickerMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(PickerActive)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = PickerMuted
        )
    }
}

@Composable
private fun MiniTag(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = PickerAccent
    )
}
