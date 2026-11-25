package com.contentstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatMessageItem
import com.contentstudio.app.runtime.ChatSessionSummary
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val activeSessionId by LinuxRuntimeManager.activeSessionId.collectAsState()
    val messages = remember { mutableStateListOf<ChatMessageItem>() }
    val sessions = remember { mutableStateListOf<ChatSessionSummary>() }

    var inputText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }

    fun loadSessionMessages(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.getSession(sessionId)
            val sesObj = res.optJSONObject("session") ?: return@launch
            val arr = sesObj.optJSONArray("messages")
            val loaded = mutableListOf<ChatMessageItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    loaded.add(
                        ChatMessageItem(
                            isUser = m.optString("role") == "user",
                            text = m.optString("content", "")
                        )
                    )
                }
            }
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(loaded)
            }
        }
    }

    fun refreshSessionsList() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listSessions()
            val arr = res.optJSONArray("sessions") ?: return@launch
            val list = mutableListOf<ChatSessionSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ChatSessionSummary(
                        id = obj.optString("id"),
                        title = obj.optString("title", "Sesi Baru"),
                        updatedAt = obj.optLong("updated_at", 0L),
                        messageCount = obj.optInt("message_count", 0)
                    )
                )
            }
            withContext(Dispatchers.Main) {
                sessions.clear()
                sessions.addAll(list)
            }
        }
    }

    fun sendMessage(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() || isStreaming) return

        inputText = ""
        isStreaming = true
        liveStatus = "Mengirim ke Gemini CLI..."

        messages.add(ChatMessageItem(isUser = true, text = cleanPrompt))
        val agentId = java.util.UUID.randomUUID().toString()
        messages.add(ChatMessageItem(id = agentId, isUser = false, text = "", isStreaming = true))

        scope.launch(Dispatchers.IO) {
            var sid = activeSessionId
            if (sid.isBlank()) {
                val created = LinuxRuntimeManager.createSession()
                sid = created.optJSONObject("session")?.optString("id", "") ?: ""
            }

            val acc = StringBuilder()
            LinuxRuntimeManager.sendChatStream(
                sessionId = sid,
                message = cleanPrompt,
                onStatus = { st ->
                    scope.launch(Dispatchers.Main) { liveStatus = st }
                },
                onDelta = { delta ->
                    acc.append(delta)
                    val currentTxt = acc.toString()
                    scope.launch(Dispatchers.Main) {
                        val idx = messages.indexOfFirst { it.id == agentId }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(text = currentTxt)
                        }
                    }
                },
                onDone = { finalTxt ->
                    scope.launch(Dispatchers.Main) {
                        val idx = messages.indexOfFirst { it.id == agentId }
                        if (idx >= 0) {
                            val resolved = if (finalTxt.isNotBlank()) finalTxt else acc.toString()
                            messages[idx] = messages[idx].copy(text = resolved, isStreaming = false)
                        }
                        isStreaming = false
                        liveStatus = ""
                    }
                    refreshSessionsList()
                },
                onError = { errTxt ->
                    scope.launch(Dispatchers.Main) {
                        val idx = messages.indexOfFirst { it.id == agentId }
                        if (idx >= 0) {
                            val existing = acc.toString()
                            val combined = if (existing.isNotBlank()) "$existing\n\n[Error] $errTxt" else "[Error] $errTxt"
                            messages[idx] = messages[idx].copy(text = combined, isStreaming = false)
                        }
                        isStreaming = false
                        liveStatus = ""
                    }
                }
            )
        }
    }

    LaunchedEffect(activeSessionId) {
        if (activeSessionId.isNotBlank()) {
            loadSessionMessages(activeSessionId)
        }
        refreshSessionsList()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // TOP BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTENT STUDIO",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BadgeGreenBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "[GEMINI CLI]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BadgeGreenText
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .clickable {
                        refreshSessionsList()
                        showHistorySheet = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "[RIWAYAT]",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }

        // THREAD CHAT
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Siap Membantu Produksi Konten",
                        fontFamily = FontFamily.Default,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Terhubung langsung ke Gemini CLI via akun Google Anda.",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    val quickChips = listOf(
                        "Buat 3 ide hook Shorts 3 detik yang memancing rasa penasaran",
                        "Susun struktur naskah video vertikal 45 detik dengan pacing cepat",
                        "Rancang konsep visual & transisi untuk video edukasi singkat"
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickChips.forEach { chipText ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { sendMessage(chipText) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = chipText,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        if (msg.isUser) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 300.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(UserBubbleBg)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceCard)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = if (msg.text.isBlank() && msg.isStreaming) "Memproses..." else msg.text,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // LIVE PROGRESS & STOP
        if (isStreaming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = liveStatus.ifBlank { "Gemini CLI sedang memproses..." },
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardHighlight)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .clickable {
                                LinuxRuntimeManager.stopChat()
                                isStreaming = false
                                liveStatus = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "[STOP]",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = ActionSolidBg,
                    trackColor = BorderSubtle
                )
            }
        }

        // COMPOSER INPUT
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = "Tulis pesan untuk Gemini CLI...",
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        color = TextPrimary
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { sendMessage(inputText) },
                enabled = !isStreaming && inputText.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText,
                    disabledContainerColor = CardHighlight,
                    disabledContentColor = TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "KIRIM",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // BOTTOM SHEET RIWAYAT SESI
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = SurfaceCard,
            contentColor = TextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RIWAYAT PERCAKAPAN",
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val res = LinuxRuntimeManager.createSession()
                                val newId = res.optJSONObject("session")?.optString("id", "") ?: ""
                                withContext(Dispatchers.Main) {
                                    if (newId.isNotBlank()) {
                                        messages.clear()
                                    }
                                    showHistorySheet = false
                                }
                                refreshSessionsList()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActionSolidBg,
                            contentColor = ActionSolidText
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "[+ CHAT BARU]",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { ses ->
                        val isActive = ses.id == activeSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) CardHighlight else BgDark)
                                .border(1.dp, if (isActive) TextSecondary else BorderSubtle, RoundedCornerShape(8.dp))
                                .clickable {
                                    loadSessionMessages(ses.id)
                                    showHistorySheet = false
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ses.title,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${ses.messageCount} pesan",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            LinuxRuntimeManager.deleteSession(ses.id)
                                            refreshSessionsList()
                                            if (ses.id == activeSessionId) {
                                                val created = LinuxRuntimeManager.createSession()
                                                withContext(Dispatchers.Main) {
                                                    if (created.optString("status") == "ok") {
                                                        messages.clear()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "HAPUS",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
