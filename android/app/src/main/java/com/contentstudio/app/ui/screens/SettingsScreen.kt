package com.contentstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.HubProviderInfo
import com.contentstudio.app.runtime.HubQuotaInfo
import com.contentstudio.app.runtime.HubSettingsSnapshot
import com.contentstudio.app.runtime.LinuxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val SetCanvas = Color(0xFF0B0C0E)
private val SetSurface = Color(0xFF15171C)
private val SetActive = Color(0xFF1E2128)
private val SetAccent = Color(0xFF2A2F3A)
private val SetText = Color(0xFFF2F4F8)
private val SetMuted = Color(0xFF8A919E)
private val SetSafe = Color(0xFF34D399)
private val SetWarn = Color(0xFFFBBF24)
private val SetDanger = Color(0xFFF87171)

@Composable
fun ProviderSettingsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<HubSettingsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf(ChatController.hubEffort.value) }
    var autoFallback by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf("") }
    val keyEdits = remember { mutableStateMapOf<String, String>() }
    var customBase by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var customKey by remember { mutableStateOf("") }
    var customMultimodal by remember { mutableStateOf(false) }
    var cloudflareAccount by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            val snap = withContext(Dispatchers.IO) { LinuxRuntimeManager.getHubSettings() }
            if (snap != null) {
                snapshot = snap
                effort = snap.effort
                autoFallback = snap.autoFallback
                customBase = snap.customBaseUrl
                customModel = snap.customModel
                customMultimodal = snap.customMultimodal
                cloudflareAccount = snap.cloudflareAccountId
                keyEdits.clear()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun save() {
        if (saving) return
        saving = true
        statusText = ""
        scope.launch {
            val snap = snapshot
            val payload = JSONObject().apply {
                put("active_provider", ChatController.hubProvider.value)
                put("active_model", ChatController.hubModel.value)
                put("reasoning_effort", effort)
                put("auto_fallback", autoFallback)
                put("cloudflare_account_id", cloudflareAccount)
                val keys = JSONObject()
                keyEdits.forEach { (pid, value) ->
                    if (value.isNotBlank()) keys.put(pid, value)
                }
                put("keys", keys)
                val custom = JSONObject().apply {
                    put("base_url", customBase)
                    put("model", customModel)
                    put("multimodal", customMultimodal)
                    if (customKey.isNotBlank()) put("api_key", customKey)
                }
                put("custom", custom)
            }
            val result = withContext(Dispatchers.IO) {
                LinuxRuntimeManager.saveHubSettings(payload)
            }
            val ok = result.optString("status", "") == "ok"
            statusText = if (ok) "Pengaturan provider tersimpan" else {
                result.optString("error", "Gagal menyimpan pengaturan")
            }
            if (ok) {
                val fresh = withContext(Dispatchers.IO) { LinuxRuntimeManager.getHubSettings() }
                if (fresh != null) {
                    snapshot = fresh
                    ChatController.applyHubSnapshot(fresh.activeProvider, fresh.activeModel, fresh.effort)
                }
                customKey = ""
                keyEdits.clear()
            }
            saving = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SetCanvas)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AI Providers & API Keys",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SetText
                    )
                    Text(
                        text = "12 provider gratis dengan kuota harian dan bulanan",
                        fontSize = 10.sp,
                        color = SetMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(SetSurface)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Tutup",
                        tint = SetMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SetSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Auto-Pindah",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SetText
                            )
                            Text(
                                text = "Pindah provider otomatis saat kuota habis atau 429",
                                fontSize = 10.sp,
                                color = SetMuted
                            )
                        }
                        Switch(
                            checked = autoFallback,
                            onCheckedChange = { autoFallback = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SetCanvas,
                                checkedTrackColor = SetSafe,
                                uncheckedThumbColor = SetMuted,
                                uncheckedTrackColor = SetAccent
                            )
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(text = "Effort default", fontSize = 11.sp, color = SetMuted)
                        listOf("low", "medium", "high").forEach { level ->
                            val selected = effort == level
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) SetAccent else SetSurface)
                                    .clickable { effort = level }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = level.replaceFirstChar { it.uppercase() },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) SetText else SetMuted
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Dashboard Pemakaian",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SetMuted,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }

                val quotaMap = snapshot?.quota ?: emptyMap()
                if (quotaMap.isEmpty()) {
                    item {
                        Text(
                            text = "Belum ada data kuota. Jalankan Sync Live dari pemilih model.",
                            fontSize = 10.sp,
                            color = SetMuted
                        )
                    }
                } else {
                    items(quotaMap.entries.toList(), key = { it.key }) { entry ->
                        QuotaRow(entry.key, entry.value)
                    }
                }

                item {
                    Text(
                        text = "API Keys",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SetMuted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                    )
                }

                val providers = snapshot?.providers ?: emptyList()
                items(providers, key = { it.id }) { provider ->
                    ProviderKeyGroup(
                        provider = provider,
                        expanded = expanded == provider.id,
                        maskedKey = snapshot?.keysMasked?.get(provider.id) ?: "",
                        keyValue = keyEdits[provider.id] ?: "",
                        onKeyChange = { keyEdits[provider.id] = it },
                        onToggle = { expanded = if (expanded == provider.id) "" else provider.id },
                        cloudflareAccount = cloudflareAccount,
                        onCloudflareChange = { cloudflareAccount = it },
                        customBase = customBase,
                        customModel = customModel,
                        customKey = customKey,
                        customKeyMasked = snapshot?.customKeyMasked ?: "",
                        customMultimodal = customMultimodal,
                        onCustomBase = { customBase = it },
                        onCustomModel = { customModel = it },
                        onCustomKey = { customKey = it },
                        onCustomMultimodal = { customMultimodal = it }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SetSurface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        color = if (statusText.contains("tersimpan")) SetSafe else SetDanger
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (saving) SetAccent else SetText)
                        .clickable(enabled = !saving && !loading) { save() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (saving) "Menyimpan..." else "Simpan Pengaturan",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (saving) SetMuted else SetCanvas
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotaRow(providerId: String, quota: HubQuotaInfo) {
    val color = when (quota.status) {
        "exhausted" -> SetDanger
        "warning" -> SetWarn
        else -> SetSafe
    }
    val fraction = (quota.percent / 100f).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(SetSurface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = providerId.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SetText
                )
            }
            Text(
                text = quota.label.ifBlank { "Kuota dinamis" },
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = SetMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SetAccent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(color)
            )
        }
        Text(
            text = "Hari ini ${quota.requestsToday} req · Bulan ${quota.requestsMonth} req",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = SetMuted
        )
    }
}

@Composable
private fun ProviderKeyGroup(
    provider: HubProviderInfo,
    expanded: Boolean,
    maskedKey: String,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    onToggle: () -> Unit,
    cloudflareAccount: String,
    onCloudflareChange: (String) -> Unit,
    customBase: String,
    customModel: String,
    customKey: String,
    customKeyMasked: String,
    customMultimodal: Boolean,
    onCustomBase: (String) -> Unit,
    onCustomModel: (String) -> Unit,
    onCustomKey: (String) -> Unit,
    onCustomMultimodal: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SetSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (provider.configured) SetSafe else SetWarn)
                )
                Text(
                    text = provider.label.ifBlank { provider.id },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = SetText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (provider.kind == "cli") {
                    Text(
                        text = "CLI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = SetMuted
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (provider.configured) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Siap",
                        tint = SetSafe,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Tutup" else "Buka",
                    tint = SetMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (provider.kind == "cli") {
                    Text(
                        text = "Provider CLI memakai login lokal, tidak perlu API key.",
                        fontSize = 10.sp,
                        color = SetMuted
                    )
                } else {
                    KeyField(
                        label = "API key",
                        value = keyValue,
                        placeholder = maskedKey.ifBlank { "Belum diisi" },
                        secret = true,
                        onChange = onKeyChange
                    )
                }

                if (provider.id == "cloudflare") {
                    KeyField(
                        label = "Account ID",
                        value = cloudflareAccount,
                        placeholder = "ID akun Cloudflare",
                        secret = false,
                        onChange = onCloudflareChange
                    )
                }

                if (provider.id == "custom") {
                    KeyField(
                        label = "Base URL",
                        value = customBase,
                        placeholder = "https://contoh.com/v1",
                        secret = false,
                        onChange = onCustomBase
                    )
                    KeyField(
                        label = "Model ID",
                        value = customModel,
                        placeholder = "nama-model",
                        secret = false,
                        onChange = onCustomModel
                    )
                    KeyField(
                        label = "API key",
                        value = customKey,
                        placeholder = customKeyMasked.ifBlank { "Isi key provider kustom" },
                        secret = true,
                        onChange = onCustomKey
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dukungan multimodal",
                            fontSize = 10.sp,
                            color = SetMuted
                        )
                        Switch(
                            checked = customMultimodal,
                            onCheckedChange = onCustomMultimodal,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SetCanvas,
                                checkedTrackColor = SetSafe,
                                uncheckedThumbColor = SetMuted,
                                uncheckedTrackColor = SetAccent
                            )
                        )
                    }
                }

                Text(
                    text = "Kunci disimpan di config lokal engine dan tidak pernah dikirim ke UI dalam bentuk penuh.",
                    fontSize = 9.sp,
                    color = SetMuted
                )
            }
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = SetMuted
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SetCanvas)
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = SetText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(SetText),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SetMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}
