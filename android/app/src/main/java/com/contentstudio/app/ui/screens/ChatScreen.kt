package com.contentstudio.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatMessageItem
import com.contentstudio.app.runtime.ChatSessionSummary
import com.contentstudio.app.runtime.WorkspaceSummary
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.ActionSolidBg
import com.contentstudio.app.ui.theme.ActionSolidText
import com.contentstudio.app.ui.theme.BadgeGreenBg
import com.contentstudio.app.ui.theme.BadgeGreenText
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.BorderSubtle
import com.contentstudio.app.ui.theme.CardHighlight
import com.contentstudio.app.ui.theme.ErrorCardBg
import com.contentstudio.app.ui.theme.ErrorCardBorder
import com.contentstudio.app.ui.theme.ErrorCardText
import com.contentstudio.app.ui.theme.InputContainerBg
import com.contentstudio.app.ui.theme.InputContainerBorder
import com.contentstudio.app.ui.theme.ProgressBarFill
import com.contentstudio.app.ui.theme.ProgressBarTrack
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import com.contentstudio.app.ui.theme.UserBubbleBg
import com.contentstudio.app.ui.theme.UserBubbleBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val activeSessionId by LinuxRuntimeManager.activeSessionId.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val messages = remember { mutableStateListOf<ChatMessageItem>() }
    val sessions = remember { mutableStateListOf<ChatSessionSummary>() }
    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }

    var inputText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var copiedMsgId by remember { mutableStateOf<String?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "loading_line")
    val loadingPillFraction by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loading_pill"
    )

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

    fun refreshWorkspacesList() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listWorkspaces()
            val arr = res.optJSONArray("workspaces") ?: return@launch
            val list = mutableListOf<WorkspaceSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    WorkspaceSummary(
                        name = obj.optString("name"),
                        path = obj.optString("path"),
                        filesCount = obj.optInt("files_count", 0),
                        updatedAt = obj.optLong("updated_at", 0L)
                    )
                )
            }
            withContext(Dispatchers.Main) {
                workspaces.clear()
                workspaces.addAll(list)
            }
        }
    }

    fun startNewSession() {
        if (isStreaming) return
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.createSession()
            val newId = res.optJSONObject("session")?.optString("id", "") ?: ""
            withContext(Dispatchers.Main) {
                if (newId.isNotBlank()) {
                    messages.clear()
                }
            }
            refreshSessionsList()
        }
    }

    fun sendMessage(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() || isStreaming) return

        inputText = ""
        isStreaming = true
        liveStatus = "Mengirim ke Antigravity..."

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
                workspace = activeWorkspace.ifBlank { null },
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
        refreshWorkspacesList()
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
        // TOP BAR (Flat dengan hairline bawah 1.dp #26262B)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
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
                        .clip(RoundedCornerShape(4.dp))
                        .background(BadgeGreenBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "[ANTIGRAVITY]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BadgeGreenText
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (activeWorkspace.isNotBlank()) CardHighlight else SurfaceCard)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable {
                            refreshWorkspacesList()
                            showWorkspaceSheet = true
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (activeWorkspace.isNotBlank()) "[WS: $activeWorkspace]" else "[PILIH WS]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeWorkspace.isNotBlank()) TextPrimary else TextSecondary
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable { startNewSession() }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "[BARU]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable {
                            refreshSessionsList()
                            showHistorySheet = true
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "[RIWAYAT]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }

        // CHAT THREAD
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Content Studio",
                        fontFamily = FontFamily.Default,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tulis instruksi atau brief produksi konten di bawah.",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        if (msg.isUser) {
                            // User Message Bubble (rata kanan, surface #1F1F24, border #2E2E35, radius 6.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 310.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(UserBubbleBg)
                                        .border(1.dp, UserBubbleBorder, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 13.dp, vertical = 10.dp)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = msg.text,
                                            fontFamily = FontFamily.Default,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        } else {
                            // AI Message: Flat editorial block rata kiri (penuh, nyaman dibaca, line-height 22.sp)
                            val hasError = msg.text.contains("[Error]")
                            val isPureError = msg.text.trim().startsWith("[Error]")

                            if (isPureError) {
                                // Blok pesan error: card matte dark-red (#221212 bg, #522323 border, #FCA5A5 teks) + [SALIN]
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ErrorCardBg)
                                        .border(1.dp, ErrorCardBorder, RoundedCornerShape(6.dp))
                                        .padding(13.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ERROR SISTEM",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ErrorCardText
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(CardHighlight)
                                                .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                                                .clickable {
                                                    clipboardManager.setText(AnnotatedString(msg.text))
                                                    copiedMsgId = msg.id
                                                }
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (copiedMsgId == msg.id) "[TERSALIN]" else "[SALIN]",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ErrorCardText
                                            )
                                        }
                                    }
                                    SelectionContainer {
                                        Text(
                                            text = msg.text,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = ErrorCardText
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SurfaceCard)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "[ANTIGRAVITY]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextSecondary
                                        )

                                        if (msg.text.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(CardHighlight)
                                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        clipboardManager.setText(AnnotatedString(msg.text))
                                                        copiedMsgId = msg.id
                                                    }
                                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = if (copiedMsgId == msg.id) "[TERSALIN]" else "[SALIN]",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                            }
                                        }
                                    }

                                    SelectionContainer {
                                        Text(
                                            text = if (msg.text.isBlank() && msg.isStreaming) "Memproses..." else msg.text,
                                            fontFamily = FontFamily.Default,
                                            fontSize = 14.sp,
                                            lineHeight = 22.sp,
                                            color = TextPrimary
                                        )
                                    }

                                    if (hasError && !isPureError) {
                                        val errorPart = msg.text.substringAfter("[Error]").trim()
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ErrorCardBg)
                                                .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "ERROR TAMBAHAN",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ErrorCardText
                                            )
                                            Text(
                                                text = errorPart,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = ErrorCardText
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

        // INDIKATOR LOADING HALUS (Saat streaming)
        if (isStreaming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (liveStatus.isNotBlank()) liveStatus else "Memproses...",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardHighlight)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                LinuxRuntimeManager.stopChat()
                                isStreaming = false
                                liveStatus = ""
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "[BERHENTI]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                // Garis tipis animasi (2.dp)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ProgressBarTrack)
                ) {
                    val trackWidth = maxWidth
                    val pillWidth = trackWidth * 0.35f
                    val offset = trackWidth * loadingPillFraction
                    Box(
                        modifier = Modifier
                            .offset(x = offset)
                            .width(pillWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(ProgressBarFill)
                    )
                }
            }
        }

        // INPUT BAR BAWAH (Container matte #161619, border #2A2A30, radius 6.dp, multiline hingga 4 baris)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(InputContainerBg)
                    .border(1.dp, InputContainerBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 5.dp)
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Tulis instruksi untuk Antigravity...",
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(TextPrimary),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val hasInput = inputText.isNotBlank() && !isStreaming
                Button(
                    onClick = { sendMessage(inputText) },
                    enabled = hasInput,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasInput) ActionSolidBg else CardHighlight,
                        contentColor = if (hasInput) ActionSolidText else TextMuted,
                        disabledContainerColor = CardHighlight,
                        disabledContentColor = TextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "KIRIM",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // BOTTOM SHEET RIWAYAT SESI
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = SurfaceCard,
            contentColor = TextPrimary,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Button(
                        onClick = {
                            startNewSession()
                            showHistorySheet = false
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActionSolidBg,
                            contentColor = ActionSolidText
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "[+ BARU]",
                            fontFamily = FontFamily.Monospace,
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isActive) CardHighlight else BgDark)
                                .border(1.dp, if (isActive) TextSecondary else BorderSubtle, RoundedCornerShape(6.dp))
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
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
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
                                    fontFamily = FontFamily.Monospace,
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

        if (showWorkspaceSheet) {
            ModalBottomSheet(
                onDismissRequest = { showWorkspaceSheet = false },
                containerColor = SurfaceCard,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "PILIH WORKSPACE AKTIF",
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (workspaces.isEmpty()) {
                        Text(
                            text = "Belum ada workspace. Buat workspace baru di tab PROYEK.",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 350.dp)
                        ) {
                            items(workspaces) { ws ->
                                val isSelected = ws.name == activeWorkspace
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) CardHighlight else SurfaceCard)
                                        .border(1.dp, if (isSelected) TextPrimary else BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable {
                                            LinuxRuntimeManager.setActiveWorkspace(ws.name)
                                            showWorkspaceSheet = false
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ws.name,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${ws.filesCount} file",
                                            fontFamily = FontFamily.Default,
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            text = "[AKTIF]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BadgeGreenText
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

/**
 * Kompatibilitas dengan MainActivity lama yang mengimpor AgentScreen
 */
@Composable
fun AgentScreen(modifier: Modifier = Modifier) = ChatScreen(modifier)
