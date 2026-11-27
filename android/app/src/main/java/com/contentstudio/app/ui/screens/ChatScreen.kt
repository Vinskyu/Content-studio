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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.WorkspaceSummary
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

@Composable
fun MarkdownFormattedLine(line: String, modifier: Modifier = Modifier) {
    val annotated = remember(line) {
        buildAnnotatedString {
            var i = 0
            while (i < line.length) {
                if (line.startsWith("**", i)) {
                    val next = line.indexOf("**", i + 2)
                    if (next != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary))
                        append(line.substring(i + 2, next))
                        pop()
                        i = next + 2
                        continue
                    }
                } else if (line.startsWith("`", i)) {
                    val next = line.indexOf("`", i + 1)
                    if (next != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = CardHighlight,
                                color = TextPrimary
                            )
                        )
                        append(" ${line.substring(i + 1, next)} ")
                        pop()
                        i = next + 1
                        continue
                    }
                }
                append(line[i])
                i++
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = TextPrimary,
        modifier = modifier
    )
}

@Composable
fun MarkdownContent(
    content: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) { content.split("\n") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var inCodeBlock = false
        val codeBuffer = StringBuilder()
        var codeLang = ""

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeLang = line.removePrefix("```").trim()
                    codeBuffer.clear()
                } else {
                    inCodeBlock = false
                    val codeContent = codeBuffer.toString().trimEnd()
                    val langLabel = codeLang.ifBlank { "code" }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BgDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardHighlight)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = langLabel.uppercase(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { onCopyCode(codeContent) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Copy",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                        Text(
                            text = codeContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = TextPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    codeBuffer.clear()
                    codeLang = ""
                }
                continue
            }

            if (inCodeBlock) {
                codeBuffer.append(rawLine).append("\n")
                continue
            }

            if (line.startsWith("### ")) {
                Text(
                    text = line.removePrefix("### ").trim(),
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            } else if (line.startsWith("## ")) {
                Text(
                    text = line.removePrefix("## ").trim(),
                    fontFamily = FontFamily.Default,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            } else if (line.startsWith("# ")) {
                Text(
                    text = line.removePrefix("# ").trim(),
                    fontFamily = FontFamily.Default,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "•", color = TextSecondary, fontSize = 14.sp)
                    MarkdownFormattedLine(line.substring(2).trim(), modifier = Modifier.weight(1f))
                }
            } else if (line.matches(Regex("^\\d+\\.\\s.*"))) {
                val prefix = line.substringBefore(".") + "."
                val rest = line.substringAfter(".").trim()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = prefix, color = TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    MarkdownFormattedLine(rest, modifier = Modifier.weight(1f))
                }
            } else if (line.isBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            } else {
                MarkdownFormattedLine(line)
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            val unclosed = codeBuffer.toString().trimEnd()
            Text(
                text = unclosed,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDark)
                    .padding(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages by ChatController.messages.collectAsState()
    val isStreaming by ChatController.isStreaming.collectAsState()
    val activeSessionId by ChatController.activeSessionId.collectAsState()
    val sessions by ChatController.sessions.collectAsState()
    val liveStatus by ChatController.liveStatus.collectAsState()
    val errorMessage by ChatController.errorMessage.collectAsState()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }

    var inputText by remember { mutableStateOf("") }
    val draftPrompt by ChatController.draftPrompt.collectAsState()
    LaunchedEffect(draftPrompt) {
        if (draftPrompt.isNotBlank()) {
            inputText = draftPrompt
            ChatController.consumeDraftPrompt()
        }
    }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var copiedMsgId by remember { mutableStateOf<String?>(null) }
    var selectModalText by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) {
        ChatController.ensureInitialized()
        refreshWorkspacesList()
    }

    LaunchedEffect(copiedMsgId) {
        if (copiedMsgId != null) {
            kotlinx.coroutines.delay(1500)
            copiedMsgId = null
        }
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
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Content Studio",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BadgeGreenBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Antigravity",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BadgeGreenText
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
                        .clickable { ChatController.startNewSession(activeWorkspace) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable {
                            ChatController.refreshSessions()
                            showHistorySheet = true
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "History",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // SUB-BAR: PROJECT SELECTOR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (activeWorkspace.isNotBlank()) "Project: $activeWorkspace" else "No active project",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (activeWorkspace.isNotBlank()) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable {
                            refreshWorkspacesList()
                            showWorkspaceSheet = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Switch",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            if (isStreaming && liveStatus.isNotBlank()) {
                Text(
                    text = liveStatus,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = BadgeGreenText,
                    maxLines = 1,
                    softWrap = false
                )
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
                        text = "Content Studio Agent",
                        fontFamily = FontFamily.Default,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter production instructions or script briefs below.",
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        if (msg.isUser) {
                            // USER MESSAGE BUBBLE
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 310.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(UserBubbleBg)
                                        .border(1.dp, UserBubbleBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 13.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        } else {
                            // ASSISTANT MESSAGE CARD
                            val isPureError = msg.text.trim().startsWith("Error:") || msg.text.trim().startsWith("ERR:")

                            if (isPureError) {
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
                                            text = "System Error",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ErrorCardText
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(SurfaceCard)
                                                .clickable {
                                                    clipboardManager.setText(AnnotatedString(msg.text))
                                                    copiedMsgId = msg.id
                                                }
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (copiedMsgId == msg.id) "Copied" else "Copy",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                    Text(
                                        text = msg.text,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        color = ErrorCardText
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Antigravity",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BadgeGreenText
                                        )
                                    }

                                    MarkdownContent(
                                        content = msg.text.ifBlank { "..." },
                                        onCopyCode = { code ->
                                            clipboardManager.setText(AnnotatedString(code))
                                        }
                                    )

                                    if (msg.isStreaming) {
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(2.dp)
                                                .background(ProgressBarTrack)
                                        ) {
                                            val w = maxWidth
                                            val pillWidth = w * 0.35f
                                            val pillOffset = (w * loadingPillFraction).coerceIn(0.dp, w - pillWidth)
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = pillOffset)
                                                    .width(pillWidth)
                                                    .fillMaxHeight()
                                                    .background(ProgressBarFill)
                                            )
                                        }
                                    }

                                    // Action buttons row: Copy and Select
                                    if (!msg.isStreaming && msg.text.isNotBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(CardHighlight)
                                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            clipboardManager.setText(AnnotatedString(msg.text))
                                                            copiedMsgId = msg.id
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = if (copiedMsgId == msg.id) "Copied" else "Copy",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(CardHighlight)
                                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                                        .clickable { selectModalText = msg.text }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = "Select",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
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
            }
        }

        // ERROR BANNER
        if (!errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ErrorCardBg)
                    .border(1.dp, ErrorCardBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage ?: "",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = ErrorCardText,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clickable { ChatController.clearError() }
                            .padding(4.dp)
                    ) {
                        Text(text = "Dismiss", color = ErrorCardText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // INPUT BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(InputContainerBg)
                    .border(1.dp, InputContainerBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = "Type message or instruction...",
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
                        color = TextPrimary
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isStreaming && inputText.isNotBlank()) {
                                val toSend = inputText
                                inputText = ""
                                ChatController.sendMessage(toSend, activeWorkspace)
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardHighlight)
                        .border(1.dp, ErrorCardBorder, RoundedCornerShape(6.dp))
                        .clickable { ChatController.stopStreaming() }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = "Stop",
                            tint = ErrorCardText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Stop",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorCardText
                        )
                    }
                }
            } else {
                val canSend = inputText.isNotBlank()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (canSend) ActionSolidBg else CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .clickable(enabled = canSend) {
                            val toSend = inputText
                            inputText = ""
                            ChatController.sendMessage(toSend, activeWorkspace)
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Send,
                            contentDescription = "Send",
                            tint = if (canSend) ActionSolidText else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Send",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canSend) ActionSolidText else TextMuted
                        )
                    }
                }
            }
        }
    }

    // MODAL DIALOG: SELECT AND HIGHLIGHT EXACT TEXT
    if (selectModalText != null) {
        val modalContent = selectModalText!!
        var modalCopied by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { selectModalText = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Select Text",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(InputContainerBg)
                        .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                        .padding(12.dp)
                ) {
                    val scroll = rememberScrollState()
                    SelectionContainer {
                        Text(
                            text = modalContent,
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = TextPrimary,
                            modifier = Modifier.verticalScroll(scroll)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(modalContent))
                        modalCopied = true
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    )
                ) {
                    Text(text = if (modalCopied) "Copied" else "Copy All", color = ActionSolidText)
                }
            },
            dismissButton = {
                Button(
                    onClick = { selectModalText = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    )
                ) {
                    Text(text = "Close", color = TextPrimary)
                }
            }
        )
    }

    // BOTTOM SHEET: SESSIONS HISTORY
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = SurfaceCard,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Session History",
                        fontFamily = FontFamily.Default,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ActionSolidBg)
                            .clickable {
                                showHistorySheet = false
                                ChatController.startNewSession(activeWorkspace)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "+ New Session",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActionSolidText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "No history found.", color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions) { ses ->
                            val isCurrent = ses.id == activeSessionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isCurrent) CardHighlight else BgDark)
                                    .border(1.dp, if (isCurrent) TextPrimary else BorderSubtle, RoundedCornerShape(6.dp))
                                    .clickable {
                                        showHistorySheet = false
                                        ChatController.loadSession(ses.id)
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
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val dateStr = if (ses.updatedAt > 0) {
                                        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(ses.updatedAt))
                                    } else ""
                                    Text(
                                        text = "${ses.messageCount} messages" + if (dateStr.isNotBlank()) " • $dateStr" else "",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { ChatController.deleteSession(ses.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Delete",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
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

    // BOTTOM SHEET: SWITCH WORKSPACE
    if (showWorkspaceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWorkspaceSheet = false },
            containerColor = SurfaceCard,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Text(
                    text = "Select Project",
                    fontFamily = FontFamily.Default,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (workspaces.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "No projects found.", color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(workspaces) { ws ->
                            val isSel = ws.name == activeWorkspace
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) CardHighlight else BgDark)
                                    .border(1.dp, if (isSel) TextPrimary else BorderSubtle, RoundedCornerShape(6.dp))
                                    .clickable {
                                        LinuxRuntimeManager.setActiveWorkspace(ws.name)
                                        showWorkspaceSheet = false
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ws.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                if (isSel) {
                                    Text(
                                        text = "Active",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = BadgeGreenText,
                                        fontWeight = FontWeight.Bold
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
