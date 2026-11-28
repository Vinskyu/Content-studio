package com.contentstudio.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
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
import com.contentstudio.app.runtime.HubSettingsSnapshot
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioDesignSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isServerRunning by LinuxRuntimeManager.isServerRunning.collectAsState()

    var snapshot by remember { mutableStateOf<HubSettingsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var effort by remember { mutableStateOf(ChatController.hubEffort.value) }
    var autoFallback by remember { mutableStateOf(true) }

    var isKeysSectionOpen by remember { mutableStateOf(false) }

    // Input state for BYOK
    val keyInputs = remember { mutableStateMapOf<String, String>() }
    val testStatus = remember { mutableStateMapOf<String, String>() }
    val testingProvider = remember { mutableStateMapOf<String, Boolean>() }

    var customBaseUrlInput by remember { mutableStateOf("") }
    var customModelInput by remember { mutableStateOf("") }
    var customKeyInput by remember { mutableStateOf("") }

    fun loadSettings() {
        scope.launch {
            loading = true
            val snap = withContext(Dispatchers.IO) { LinuxRuntimeManager.getHubSettings() }
            if (snap != null) {
                snapshot = snap
                effort = snap.effort
                autoFallback = snap.autoFallback
                customBaseUrlInput = snap.customBaseUrl
                customModelInput = snap.customModel
                keyInputs.clear()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadSettings()
    }

    fun saveRouting(newEffort: String, newFallback: Boolean) {
        effort = newEffort
        autoFallback = newFallback
        ChatController.setHubEffort(newEffort)
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("active_provider", ChatController.hubProvider.value)
                put("active_model", ChatController.hubModel.value)
                put("reasoning_effort", newEffort)
                put("auto_fallback", newFallback)
            }
            LinuxRuntimeManager.saveHubSettings(payload)
        }
    }

    fun testAndSaveProvider(providerId: String) {
        val inputKey = keyInputs[providerId]?.trim() ?: ""
        val existingMasked = snapshot?.keysMasked?.get(providerId) ?: ""
        if (inputKey.isBlank() && existingMasked.isBlank()) {
            testStatus[providerId] = "Masukkan API key terlebih dahulu"
            return
        }

        testingProvider[providerId] = true
        testStatus[providerId] = "Memverifikasi koneksi..."

        scope.launch {
            val keyToTest = if (inputKey.isNotBlank()) inputKey else ""
            val testRes = withContext(Dispatchers.IO) {
                LinuxRuntimeManager.testProviderConnection(providerId, keyToTest)
            }
            val ok = testRes.optBoolean("ok", false)
            if (ok) {
                val modelCount = testRes.optJSONArray("models")?.length() ?: testRes.optInt("count", 0)
                // Save key to providers.json
                if (inputKey.isNotBlank()) {
                    val payload = JSONObject().apply {
                        val keysObj = JSONObject().apply {
                            put(providerId, inputKey)
                        }
                        put("keys", keysObj)
                    }
                    withContext(Dispatchers.IO) {
                        LinuxRuntimeManager.saveHubSettings(payload)
                    }
                }
                testStatus[providerId] = "Terverifikasi ($modelCount model)"
                loadSettings()
            } else {
                val err = testRes.optString("error", "Verifikasi gagal")
                testStatus[providerId] = err
            }
            testingProvider[providerId] = false
        }
    }

    fun testAndSaveCustom() {
        if (customBaseUrlInput.isBlank()) {
            testStatus["custom"] = "Masukkan Base URL endpoint"
            return
        }
        testingProvider["custom"] = true
        testStatus["custom"] = "Memverifikasi endpoint..."

        scope.launch {
            val testRes = withContext(Dispatchers.IO) {
                LinuxRuntimeManager.testProviderConnection("custom", customKeyInput, customBaseUrlInput, customModelInput)
            }
            val ok = testRes.optBoolean("ok", false)
            if (ok) {
                val payload = JSONObject().apply {
                    val custom = JSONObject().apply {
                        put("base_url", customBaseUrlInput.trim())
                        put("model", customModelInput.trim())
                        if (customKeyInput.isNotBlank()) put("api_key", customKeyInput.trim())
                    }
                    put("custom", custom)
                }
                withContext(Dispatchers.IO) {
                    LinuxRuntimeManager.saveHubSettings(payload)
                }
                testStatus["custom"] = "Endpoint terhubung"
                loadSettings()
            } else {
                testStatus["custom"] = testRes.optString("error", "Koneksi endpoint gagal")
            }
            testingProvider["custom"] = false
        }
    }

    // Hitung jumlah provider aktif
    val activeKeysCount = (snapshot?.configured?.count { it.value } ?: 0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioDesignSystem.BgCanvas)
    ) {
        // TOP BAR 52.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(StudioDesignSystem.BgCanvas)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Pengaturan",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = StudioDesignSystem.TextPrimary
            )
            Text(
                text = "v3.8",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = StudioDesignSystem.TextMuted
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(StudioDesignSystem.DividerSubtle)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── GROUP 1: RUNTIME & SISTEM ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StudioDesignSystem.ShapeCard)
                    .background(StudioDesignSystem.BgSurface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Sistem & Engine Studio",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioDesignSystem.TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isServerRunning) StudioDesignSystem.AccentSuccess else StudioDesignSystem.AccentDanger)
                        )
                        Column {
                            Text(
                                text = if (isServerRunning) "Engine Studio Aktif" else "Engine Terhenti",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = StudioDesignSystem.TextPrimary
                            )
                            Text(
                                text = "Port internal 45159 · Native Linux Daemon",
                                fontSize = 11.sp,
                                color = StudioDesignSystem.TextMuted
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(StudioDesignSystem.ShapeButton)
                            .background(StudioDesignSystem.BgElevated)
                            .clickable {
                                LinuxRuntimeManager.restartServer(context)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "Mulai Ulang",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = StudioDesignSystem.TextPrimary
                        )
                    }
                }
            }

            // ── GROUP 2: ROUTING & EFFORT ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StudioDesignSystem.ShapeCard)
                    .background(StudioDesignSystem.BgSurface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Preferensi Model & AI",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioDesignSystem.TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Fallback Otomatis",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = StudioDesignSystem.TextPrimary
                        )
                        Text(
                            text = "Pindah ke cadangan jika kuota provider habis",
                            fontSize = 11.sp,
                            color = StudioDesignSystem.TextMuted
                        )
                    }
                    Switch(
                        checked = autoFallback,
                        onCheckedChange = { saveRouting(effort, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioDesignSystem.BgCanvas,
                            checkedTrackColor = StudioDesignSystem.AccentSuccess,
                            uncheckedThumbColor = StudioDesignSystem.TextMuted,
                            uncheckedTrackColor = StudioDesignSystem.BgElevated
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(StudioDesignSystem.DividerSubtle)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tingkat Berpikir (Effort)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = StudioDesignSystem.TextPrimary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("low" to "Rendah", "medium" to "Sedang", "high" to "Tinggi").forEach { (valKey, label) ->
                            val isSelected = effort == valKey
                            Box(
                                modifier = Modifier
                                    .clip(StudioDesignSystem.ShapeChip)
                                    .background(if (isSelected) StudioDesignSystem.BgElevated else Color.Transparent)
                                    .border(
                                        width = 0.5.dp,
                                        color = if (isSelected) StudioDesignSystem.TextSecondary else StudioDesignSystem.DividerSubtle,
                                        shape = StudioDesignSystem.ShapeChip
                                    )
                                    .clickable { saveRouting(valKey, autoFallback) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) StudioDesignSystem.TextPrimary else StudioDesignSystem.TextMuted
                                )
                            }
                        }
                    }
                }
            }

            // ── GROUP 3: API KEYS (OPSIONAL) - BYOK ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StudioDesignSystem.ShapeCard)
                    .background(StudioDesignSystem.BgSurface)
            ) {
                // Header (collapsible)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isKeysSectionOpen = !isKeysSectionOpen }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "API Keys (Opsional)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioDesignSystem.TextPrimary
                        )
                        Text(
                            text = if (activeKeysCount > 0) "$activeKeysCount provider terverifikasi" else "Gunakan key sendiri untuk model cloud",
                            fontSize = 11.sp,
                            color = StudioDesignSystem.TextMuted
                        )
                    }

                    Icon(
                        imageVector = if (isKeysSectionOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isKeysSectionOpen) "Tutup" else "Buka",
                        tint = StudioDesignSystem.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isKeysSectionOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(StudioDesignSystem.DividerSubtle)
                        )

                        // 1. Google Gemini
                        ProviderCardItem(
                            title = "Google Gemini AI Studio",
                            hint = "AIzaSy...",
                            maskedKey = snapshot?.keysMasked?.get("gemini") ?: "",
                            currentInput = keyInputs["gemini"] ?: "",
                            onInputChange = { keyInputs["gemini"] = it },
                            statusText = testStatus["gemini"],
                            isTesting = testingProvider["gemini"] == true,
                            onTestAndSave = { testAndSaveProvider("gemini") }
                        )

                        // 2. Groq
                        ProviderCardItem(
                            title = "Groq Cloud",
                            hint = "gsk_...",
                            maskedKey = snapshot?.keysMasked?.get("groq") ?: "",
                            currentInput = keyInputs["groq"] ?: "",
                            onInputChange = { keyInputs["groq"] = it },
                            statusText = testStatus["groq"],
                            isTesting = testingProvider["groq"] == true,
                            onTestAndSave = { testAndSaveProvider("groq") }
                        )

                        // 3. OpenRouter
                        ProviderCardItem(
                            title = "OpenRouter",
                            hint = "sk-or-v1-...",
                            maskedKey = snapshot?.keysMasked?.get("openrouter") ?: "",
                            currentInput = keyInputs["openrouter"] ?: "",
                            onInputChange = { keyInputs["openrouter"] = it },
                            statusText = testStatus["openrouter"],
                            isTesting = testingProvider["openrouter"] == true,
                            onTestAndSave = { testAndSaveProvider("openrouter") }
                        )

                        // 4. Custom Endpoint
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(StudioDesignSystem.ShapeCard)
                                .background(StudioDesignSystem.BgElevated)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Custom OpenAI-Compatible Endpoint",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StudioDesignSystem.TextPrimary
                            )

                            InputFieldClean(
                                label = "Base URL",
                                value = customBaseUrlInput,
                                placeholder = "https://api.openai.com/v1",
                                onValueChange = { customBaseUrlInput = it }
                            )

                            InputFieldClean(
                                label = "Model ID",
                                value = customModelInput,
                                placeholder = "gpt-4o-mini",
                                onValueChange = { customModelInput = it }
                            )

                            InputFieldClean(
                                label = "API Key (Opsional)",
                                value = customKeyInput,
                                placeholder = if (snapshot?.customKeyMasked?.isNotBlank() == true) snapshot?.customKeyMasked ?: "" else "sk-...",
                                isPassword = true,
                                onValueChange = { customKeyInput = it }
                            )

                            val cStatus = testStatus["custom"]
                            if (!cStatus.isNullOrBlank()) {
                                Text(
                                    text = cStatus,
                                    fontSize = 11.sp,
                                    color = if (cStatus.contains("gagal") || cStatus.contains("Masukkan")) StudioDesignSystem.AccentDanger else StudioDesignSystem.AccentSuccess
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clip(StudioDesignSystem.ShapeButton)
                                    .background(StudioDesignSystem.BgCanvas)
                                    .border(0.5.dp, StudioDesignSystem.DividerSubtle, StudioDesignSystem.ShapeButton)
                                    .clickable { testAndSaveCustom() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (testingProvider["custom"] == true) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = StudioDesignSystem.TextPrimary)
                                } else {
                                    Text(
                                        text = "Tes & Simpan",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = StudioDesignSystem.TextPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCardItem(
    title: String,
    hint: String,
    maskedKey: String,
    currentInput: String,
    onInputChange: (String) -> Unit,
    statusText: String?,
    isTesting: Boolean,
    onTestAndSave: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioDesignSystem.ShapeCard)
            .background(StudioDesignSystem.BgElevated)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = StudioDesignSystem.TextPrimary
            )
            if (maskedKey.isNotBlank()) {
                Text(
                    text = "Tersimpan",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = StudioDesignSystem.AccentSuccess
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(StudioDesignSystem.ShapeInput)
                .background(StudioDesignSystem.BgCanvas)
                .border(0.5.dp, StudioDesignSystem.DividerSubtle, StudioDesignSystem.ShapeInput)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = currentInput,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = StudioDesignSystem.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(StudioDesignSystem.TextPrimary),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (currentInput.isEmpty()) {
                            Text(
                                text = maskedKey.ifBlank { hint },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = StudioDesignSystem.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                )

                IconButton(
                    onClick = { showPassword = !showPassword },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = "Toggle password",
                        tint = StudioDesignSystem.TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText ?: "",
                fontSize = 11.sp,
                color = if (statusText?.contains("gagal") == true || statusText?.contains("kosong") == true || statusText?.contains("Masukkan") == true) {
                    StudioDesignSystem.AccentDanger
                } else {
                    StudioDesignSystem.AccentSuccess
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .clip(StudioDesignSystem.ShapeButton)
                    .background(StudioDesignSystem.BgCanvas)
                    .border(0.5.dp, StudioDesignSystem.DividerSubtle, StudioDesignSystem.ShapeButton)
                    .clickable { onTestAndSave() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = StudioDesignSystem.TextPrimary)
                } else {
                    Text(
                        text = "Tes & Simpan",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = StudioDesignSystem.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun InputFieldClean(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, fontSize = 11.sp, color = StudioDesignSystem.TextMuted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(StudioDesignSystem.ShapeInput)
                .background(StudioDesignSystem.BgCanvas)
                .border(0.5.dp, StudioDesignSystem.DividerSubtle, StudioDesignSystem.ShapeInput)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = StudioDesignSystem.TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(StudioDesignSystem.TextPrimary),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = StudioDesignSystem.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
fun ProviderSettingsDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioDesignSystem.BgCanvas)
        ) {
            SettingsScreen()
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Tutup",
                    tint = StudioDesignSystem.TextPrimary
                )
            }
        }
    }
}
