package com.contentstudio.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ExtensionMcpItem
import com.contentstudio.app.runtime.ExtensionSkillItem
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ExtBg = Color(0xFF0B0C0E)
private val ExtCard = Color(0xFF15171C)
private val ExtElevated = Color(0xFF1E2128)

@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
    onMenuClick: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Skills, 1: MCP, 2: Media Engine
    var isLoading by remember { mutableStateOf(false) }

    val skills = remember { mutableStateListOf<ExtensionSkillItem>() }
    val mcpServers = remember { mutableStateListOf<ExtensionMcpItem>() }

    // Media Engine BYOK state
    var pexelsKey by remember { mutableStateOf("") }
    var pixabayKey by remember { mutableStateOf("") }
    var isSavingKeys by remember { mutableStateOf(false) }
    var isUpdatingYtdlp by remember { mutableStateOf(false) }
    var ytdlpReady by remember { mutableStateOf(false) }
    var pexelsConfigured by remember { mutableStateOf(false) }
    var pixabayConfigured by remember { mutableStateOf(false) }
    var mediaStatusMsg by remember { mutableStateOf<String?>(null) }

    fun refreshData() {
        isLoading = true
        coroutineScope.launch {
            val s = withContext(Dispatchers.IO) { LinuxRuntimeManager.listExtensionSkills() }
            skills.clear()
            skills.addAll(s)
            val m = withContext(Dispatchers.IO) { LinuxRuntimeManager.listExtensionMcp() }
            mcpServers.clear()
            mcpServers.addAll(m)
            isLoading = false
        }
    }

    fun refreshMediaConfig() {
        coroutineScope.launch {
            val cfg = withContext(Dispatchers.IO) { LinuxRuntimeManager.getMediaConfig() }
            if (cfg != null) {
                ytdlpReady = cfg.ytdlpReady
                pexelsConfigured = cfg.pexelsConfigured
                pixabayConfigured = cfg.pixabayConfigured
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
        refreshMediaConfig()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ExtBg)
    ) {
        // ── TAB SWITCHER ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExtCard)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0 to "Skills", 1 to "MCP", 2 to "Media").forEach { (idx, label) ->
                    val tabBg by animateColorAsState(
                        if (selectedTab == idx) ExtElevated else Color.Transparent,
                        label = "tab${idx}Bg"
                    )
                    val tabText by animateColorAsState(
                        if (selectedTab == idx) TextPrimary else TextSecondary,
                        label = "tab${idx}Text"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tabBg)
                            .clickable { selectedTab = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == idx) FontWeight.SemiBold else FontWeight.Medium,
                            color = tabText
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExtCard)
                    .clickable { refreshData(); refreshMediaConfig() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── CONTENT ────────────────────────────────────────────────────────────
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
            },
            label = "extTabTransition",
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { tab ->
            when (tab) {
                // ── SKILLS ─────────────────────────────────────────────────────
                0 -> {
                    if (skills.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "Tidak ada custom skills aktif", fontSize = 13.sp, color = TextMuted)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(skills) { skill ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ExtCard)
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Extension,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = skill.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(StudioSage))
                                            Text(
                                                text = "Active",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = StudioSage
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = skill.description,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(ExtElevated)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = skill.path,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── MCP SERVERS ────────────────────────────────────────────────
                1 -> {
                    if (mcpServers.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "Tidak ada server MCP terkonfigurasi", fontSize = 13.sp, color = TextMuted)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(mcpServers) { srv ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ExtCard)
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = srv.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ExtElevated)
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = srv.type,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (srv.target.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ExtElevated)
                                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = srv.target,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── MEDIA ENGINE & API KEYS ────────────────────────────────────
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Engine status card
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ExtCard)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Media Engine & API Keys",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                listOf(
                                    "Full Studio CLI" to true,
                                    "Wikimedia Commons" to true,
                                    "yt-dlp" to ytdlpReady,
                                    "Pexels API" to pexelsConfigured,
                                    "Pixabay API" to pixabayConfigured
                                ).forEach { (label, active) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = label, fontSize = 13.sp, color = TextSecondary)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.size(6.dp).clip(CircleShape)
                                                    .background(if (active) StudioSage else Color(0xFF8A919E))
                                            )
                                            Text(
                                                text = if (active) "Active" else "Not configured",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = if (active) StudioSage else TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // yt-dlp update row
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ExtCard)
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = "yt-dlp", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    Text(text = "Media downloader engine", fontSize = 11.sp, color = TextMuted)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ExtElevated)
                                        .clickable(enabled = !isUpdatingYtdlp) {
                                            isUpdatingYtdlp = true
                                            mediaStatusMsg = null
                                            coroutineScope.launch {
                                                val ok = withContext(Dispatchers.IO) { LinuxRuntimeManager.updateYtdlp() }
                                                isUpdatingYtdlp = false
                                                mediaStatusMsg = if (ok) "yt-dlp berhasil diperbarui" else "Gagal memperbarui yt-dlp"
                                                refreshMediaConfig()
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (isUpdatingYtdlp) "Memperbarui..." else "Update yt-dlp",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isUpdatingYtdlp) TextMuted else TextPrimary
                                    )
                                }
                            }
                        }

                        // BYOK API Keys
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ExtCard)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "BYOK API Keys",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )

                                // Pexels Key
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = "Pexels API Key", fontSize = 11.sp, color = TextMuted)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ExtElevated)
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        BasicTextField(
                                            value = pexelsKey,
                                            onValueChange = { pexelsKey = it },
                                            textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary, fontFamily = FontFamily.Monospace),
                                            cursorBrush = SolidColor(TextPrimary),
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            decorationBox = { inner ->
                                                if (pexelsKey.isEmpty()) {
                                                    Text(
                                                        text = if (pexelsConfigured) "Sudah dikonfigurasi" else "Masukkan Pexels API key...",
                                                        fontSize = 13.sp,
                                                        color = if (pexelsConfigured) StudioSage else TextMuted
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                    }
                                }

                                // Pixabay Key
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = "Pixabay API Key", fontSize = 11.sp, color = TextMuted)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ExtElevated)
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        BasicTextField(
                                            value = pixabayKey,
                                            onValueChange = { pixabayKey = it },
                                            textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary, fontFamily = FontFamily.Monospace),
                                            cursorBrush = SolidColor(TextPrimary),
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            decorationBox = { inner ->
                                                if (pixabayKey.isEmpty()) {
                                                    Text(
                                                        text = if (pixabayConfigured) "Sudah dikonfigurasi" else "Masukkan Pixabay API key...",
                                                        fontSize = 13.sp,
                                                        color = if (pixabayConfigured) StudioSage else TextMuted
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                    }
                                }

                                if (mediaStatusMsg != null) {
                                    Text(
                                        text = mediaStatusMsg!!,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (mediaStatusMsg!!.startsWith("Gagal")) StudioDangerRed else StudioSage
                                    )
                                }

                                // Save button
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ExtElevated)
                                        .clickable(enabled = !isSavingKeys && (pexelsKey.isNotBlank() || pixabayKey.isNotBlank())) {
                                            isSavingKeys = true
                                            mediaStatusMsg = null
                                            coroutineScope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    LinuxRuntimeManager.saveMediaConfig(pexelsKey, pixabayKey)
                                                }
                                                isSavingKeys = false
                                                mediaStatusMsg = if (ok) "API keys berhasil disimpan" else "Gagal menyimpan API keys"
                                                if (ok) { pexelsKey = ""; pixabayKey = ""; refreshMediaConfig() }
                                            }
                                        }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isSavingKeys) "Menyimpan..." else "Save Keys",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSavingKeys) TextMuted else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
