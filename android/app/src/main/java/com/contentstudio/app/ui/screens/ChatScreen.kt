package com.contentstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.ChatMessageItem
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.WorkspaceSummary
import com.contentstudio.app.ui.theme.StudioAccent
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioBorder
import com.contentstudio.app.ui.theme.StudioDangerBg
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioDangerText
import com.contentstudio.app.ui.theme.StudioPrimary
import com.contentstudio.app.ui.theme.StudioPrimaryButton
import com.contentstudio.app.ui.theme.StudioPrimaryText
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.StudioSecondaryButton
import com.contentstudio.app.ui.theme.StudioSurface
import com.contentstudio.app.ui.theme.StudioSurfaceElevated
import com.contentstudio.app.ui.theme.StudioTextField
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MatteCanvas = Color(0xFF0B0C0E)
private val MatteSurface = Color(0xFF15171C)
private val MatteElevated = Color(0xFF1E2128)
private val MatteDivider = Color(0xFF262A33)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    onOpenSidebar: () -> Unit = {},
    onNavigateToExtensions: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messages by ChatController.messages.collectAsState()
    val isStreaming by ChatController.isStreaming.collectAsState()
    val sessions by ChatController.sessions.collectAsState()
    val activeSessionId by ChatController.activeSessionId.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val lastError by ChatController.lastError.collectAsState()
    val liveStatus by ChatController.liveStatus.collectAsState()
    val thinkingStartMs by ChatController.thinkingStartTimeMs.collectAsState()
    val thinkingSteps by ChatController.thinkingSteps.collectAsState()
    val draftText by ChatController.draftText.collectAsState()
    val pendingAttachments by ChatController.pendingAttachments.collectAsState()
    val draftPrompt by ChatController.draftPrompt.collectAsState()

    val projectsList = remember { mutableStateListOf<WorkspaceSummary>() }
    var showProjectSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var editMessageTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var editDialogText by remember { mutableStateOf("") }
    var scriptSavedToast by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val label = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { uri.toString() } ?: uri.toString()
            ChatController.addAttachment(label)
        }
    }

    LaunchedEffect(Unit) {
        ChatController.ensureInitialized()
        withContext(Dispatchers.IO) {
            val loaded = parseWorkspacesJson(LinuxRuntimeManager.listWorkspaces())
            withContext(Dispatchers.Main) {
                projectsList.clear()
                projectsList.addAll(loaded)
            }
        }
    }

    LaunchedEffect(draftPrompt) {
        if (draftPrompt.isNotBlank()) {
            val consumed = ChatController.consumeDraftPrompt()
            if (consumed.isNotBlank()) {
                ChatController.updateDraft(consumed)
            }
        }
    }

    LaunchedEffect(scriptSavedToast) {
        if (scriptSavedToast != null) {
            delay(2200)
            scriptSavedToast = null
        }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total <= 1) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= total - 2
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, isStreaming) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun sendCurrentInput() {
        val text = draftText.trim()
        if (text.isEmpty() || isStreaming) return
        val attachmentsPrefix = if (pendingAttachments.isNotEmpty()) {
            "Lampiran: " + pendingAttachments.joinToString(", ") + "\n\n"
        } else {
            ""
        }
        val sent = ChatController.sendMessage(attachmentsPrefix + text, activeWorkspace)
        if (sent) {
            ChatController.updateDraft("")
            ChatController.clearAttachments()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MatteCanvas)
            .imePadding()
    ) {
        // 1. SINGLE SLIM TOP BAR (52.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(MatteCanvas)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Sidebar Menu (no border box)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenSidebar() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Menu",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Center: Active Project Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteSurface)
                    .clickable {
                        scope.launch(Dispatchers.IO) {
                            val loaded = parseWorkspacesJson(LinuxRuntimeManager.listWorkspaces())
                            withContext(Dispatchers.Main) {
                                projectsList.clear()
                                projectsList.addAll(loaded)
                                showProjectSheet = true
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (activeWorkspace.isNotBlank()) StudioAccent else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = activeWorkspace.ifBlank { "No Project" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (activeWorkspace.isNotBlank()) TextPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp)
                    )
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = "Switch Project",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Right: New Chat & History (no border box)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            ChatController.startNewSession(activeWorkspace)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New Chat",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            ChatController.refreshSessions()
                            showHistorySheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "History",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Thin 1dp divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MatteDivider)
        )

        // Toast confirmation for script saved
        AnimatedVisibility(visible = scriptSavedToast != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MatteSurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = scriptSavedToast ?: "",
                    fontSize = 12.sp,
                    color = StudioSage
                )
            }
        }

        // 2. CHAT BODY (Messages or Empty State)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && !isStreaming) {
                GuidedEmptyState(
                    onSelectAction = { prompt ->
                        ChatController.sendMessage(prompt, activeWorkspace)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    itemsIndexed(
                        items = messages,
                        key = { index, item -> "${item.id}_$index" }
                    ) { index, msg ->
                        if (msg.isUser) {
                            val lastUserIdx = messages.indexOfLast { it.isUser }
                            UserMessageBubble(
                                message = msg,
                                isLastUserMessage = (index == lastUserIdx),
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(msg.text))
                                },
                                onEdit = {
                                    editMessageTarget = Pair(index, msg.text)
                                    editDialogText = msg.text
                                },
                                onUndo = {
                                    ChatController.undoLastUserMessage()
                                }
                            )
                        } else {
                            val lastAgentIdx = messages.indexOfLast { !it.isUser }
                            AgentMessageView(
                                message = msg,
                                isLastAgentMessage = (index == lastAgentIdx),
                                hasActiveProject = activeWorkspace.isNotBlank(),
                                onCopy = { textToCopy ->
                                    clipboardManager.setText(AnnotatedString(textToCopy))
                                },
                                onRegenerate = {
                                    ChatController.regenerateLastResponse(activeWorkspace)
                                },
                                onUseAsScript = { scriptText ->
                                    if (activeWorkspace.isNotBlank()) {
                                        scope.launch(Dispatchers.IO) {
                                            val ok = try {
                                                val dir = LinuxRuntimeManager.getWorkspaceScriptsDir(context, activeWorkspace)
                                                java.io.File(dir, "main.txt").writeText(scriptText)
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                            withContext(Dispatchers.Main) {
                                                scriptSavedToast = if (ok) {
                                                    "Naskah disimpan ke proyek $activeWorkspace"
                                                } else {
                                                    "Gagal menyimpan naskah ke proyek"
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    if (isStreaming) {
                        item(key = "live_thinking_card") {
                            LiveThinkingCard(
                                startTimeMs = thinkingStartMs,
                                liveStatus = liveStatus,
                                steps = thinkingSteps
                            )
                        }
                    }

                    if (lastError != null && !isStreaming) {
                        item(key = "inline_error_card") {
                            InlineErrorCard(
                                errorText = lastError ?: "Terjadi kesalahan koneksi",
                                onRetry = {
                                    ChatController.retryLastFailed(activeWorkspace)
                                }
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }

            // Floating Jump-to-Bottom Button
            androidx.compose.animation.AnimatedVisibility(
                visible = messages.isNotEmpty() && !isNearBottom,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MatteElevated)
                        .clickable {
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3. MODERN COMPOSER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MatteSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Pending attachment chips
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pendingAttachments.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MatteElevated)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = file,
                                    fontSize = 11.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp)
                                )
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove attachment",
                                    tint = TextMuted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { ChatController.removeAttachment(file) }
                                )
                            }
                        }
                    }
                }

                // Auto-expanding input field
                BasicTextField(
                    value = draftText,
                    onValueChange = { ChatController.updateDraft(it) },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp, max = 110.dp),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (draftText.isEmpty()) {
                                Text(
                                    text = "Tulis pesan untuk AI Director...",
                                    fontSize = 14.sp,
                                    color = TextMuted
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Toolbar Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Attach & Tools
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MatteElevated)
                                .clickable { attachLauncher.launch(arrayOf("*/*")) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = "Attach File",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MatteElevated)
                                .clickable { showToolsSheet = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Build,
                                    contentDescription = "Tools",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Tools",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    // Right: Send or Stop
                    if (isStreaming) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioDangerBg)
                                .clickable { ChatController.stopStreaming() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = "Stop",
                                tint = StudioDangerRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        val canSend = draftText.isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (canSend) StudioPrimary else MatteElevated)
                                .clickable(enabled = canSend) { sendCurrentInput() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Send,
                                contentDescription = "Send",
                                tint = if (canSend) StudioPrimaryText else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── MODAL SHEET: PROJECT SWITCHER ────────────────────────────────────────
    if (showProjectSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProjectSheet = false },
            containerColor = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Pilih Proyek Aktif",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Option: No Project
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeWorkspace.isBlank()) MatteElevated else Color.Transparent)
                        .clickable {
                            LinuxRuntimeManager.setActiveWorkspace("")
                            showProjectSheet = false
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "No Project",
                        fontSize = 13.sp,
                        color = if (activeWorkspace.isBlank()) TextPrimary else TextSecondary
                    )
                    if (activeWorkspace.isBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = StudioSage,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                projectsList.forEach { proj ->
                    val isSelected = proj.name == activeWorkspace
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MatteElevated else Color.Transparent)
                            .clickable {
                                LinuxRuntimeManager.setActiveWorkspace(proj.name)
                                showProjectSheet = false
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${proj.aspectRatio} | ${proj.stage}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = StudioSage,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ── MODAL SHEET: SESSION HISTORY ─────────────────────────────────────────
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Riwayat Sesi Chat",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${sessions.size} sesi",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (sessions.isEmpty()) {
                    Text(
                        text = "Belum ada riwayat sesi tersimpan.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    sessions.forEach { sess ->
                        val isCurrent = sess.id == activeSessionId
                        val dateStr = remember(sess.updatedAt) {
                            if (sess.updatedAt > 0L) {
                                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(sess.updatedAt))
                            } else {
                                ""
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) MatteElevated else Color.Transparent)
                                .clickable {
                                    ChatController.loadSession(sess.id)
                                    showHistorySheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sess.title.ifBlank { "Sesi Baru" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${sess.messageCount} pesan" + if (dateStr.isNotEmpty()) " | $dateStr" else "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { ChatController.deleteSession(sess.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete Session",
                                    tint = StudioDangerRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ── MODAL SHEET: AGENT TOOLS STATUS ──────────────────────────────────────
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            containerColor = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Kemampuan & Engine Agent",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                ToolStatusRow("Project & Script Engine", "Active", StudioSage)
                ToolStatusRow("Voice & Render Engine", "Active", StudioSage)
                ToolStatusRow("Stock Media Search", "Phase 2 / MCP", TextMuted)

                Spacer(modifier = Modifier.height(6.dp))
                StudioPrimaryButton(
                    text = "Buka Skills & MCP",
                    onClick = {
                        showToolsSheet = false
                        onNavigateToExtensions()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // ── DIALOG: EDIT & RESEND ────────────────────────────────────────────────
    val editTarget = editMessageTarget
    if (editTarget != null) {
        AlertDialog(
            onDismissRequest = { editMessageTarget = null },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = "Edit & Kirim Ulang",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                StudioTextField(
                    value = editDialogText,
                    onValueChange = { editDialogText = it },
                    placeholder = "Ubah pesan...",
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 200.dp)
                )
            },
            confirmButton = {
                StudioPrimaryButton(
                    text = "Kirim Ulang",
                    onClick = {
                        val idx = editTarget.first
                        val updated = editDialogText
                        editMessageTarget = null
                        ChatController.editAndResend(idx, updated, activeWorkspace)
                    }
                )
            },
            dismissButton = {
                StudioSecondaryButton(
                    text = "Batal",
                    onClick = { editMessageTarget = null }
                )
            }
        )
    }
}

@Composable
private fun ToolStatusRow(name: String, status: String, statusColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MatteElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            color = TextPrimary
        )
        Text(
            text = status,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = statusColor
        )
    }
}

@Composable
private fun GuidedEmptyState(
    onSelectAction: (String) -> Unit
) {
    val actions = listOf(
        "Riset Ide & Niche" to "Bantu saya riset ide konten dan niche yang potensial untuk video pendek.",
        "Buat Project Baru" to "Buatkan project baru rasio 9:16 dan siapkan struktur project.json.",
        "Tulis / Revisi Naskah" to "Tuliskan naskah voiceover singkat 30-45 detik dengan hook kuat di 3 detik pertama.",
        "Siapkan Visual & Scene" to "Pecah naskah menjadi daftar scene lengkap dengan prompt visual yang jelas."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "What are we creating?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Pilih langkah cepat atau tulis instruksi langsung di bawah.",
            fontSize = 13.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.forEach { (title, prompt) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MatteSurface)
                        .clickable { onSelectAction(prompt) }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessageItem,
    isLastUserMessage: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onUndo: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.85f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MatteSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (copied) StudioSage else TextMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            copied = true
                            onCopy()
                        }
                )
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onEdit() }
                )
                if (isLastUserMessage) {
                    Icon(
                        imageVector = Icons.Outlined.Undo,
                        contentDescription = "Undo",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onUndo() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMessageView(
    message: ChatMessageItem,
    isLastAgentMessage: Boolean,
    hasActiveProject: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onUseAsScript: (String) -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectionContainer {
            LightweightMarkdownBody(
                text = message.text,
                onCopyCodeBlock = onCopy
            )
        }

        if (!message.isStreaming && message.text.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        copied = true
                        onCopy(message.text)
                    }
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) StudioSage else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 11.sp,
                        color = if (copied) StudioSage else TextMuted
                    )
                }

                if (isLastAgentMessage) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onRegenerate() }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Regenerate",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Regenerate",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                if (hasActiveProject) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onUseAsScript(message.text) }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "Use as Script",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Use as Script",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LightweightMarkdownBody(
    text: String,
    onCopyCodeBlock: (String) -> Unit
) {
    val lines = remember(text) { text.lines() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                idx++
                while (idx < lines.size && !lines[idx].trim().startsWith("```")) {
                    codeLines.add(lines[idx])
                    idx++
                }
                val codeContent = codeLines.joinToString("\n")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteSurface)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onCopyCodeBlock(codeContent) }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = codeContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = TextPrimary
                    )
                }
                idx++
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineBold(trimmed.removePrefix("### ")),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseInlineBold(trimmed.removePrefix("## ")),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseInlineBold(trimmed.removePrefix("# ")),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "•",
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                        Text(
                            text = parseInlineBold(trimmed.drop(2)),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = TextPrimary
                        )
                    }
                }
                trimmed.isEmpty() -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                else -> {
                    Text(
                        text = parseInlineBold(line),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary
                    )
                }
            }
            idx++
        }
    }
}

private fun parseInlineBold(line: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            val start = line.indexOf("**", i)
            if (start == -1) {
                append(line.substring(i))
                break
            }
            append(line.substring(i, start))
            val end = line.indexOf("**", start + 2)
            if (end == -1) {
                append(line.substring(start))
                break
            }
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextPrimary))
            append(line.substring(start + 2, end))
            pop()
            i = end + 2
        }
    }
}

@Composable
private fun LiveThinkingCard(
    startTimeMs: Long?,
    liveStatus: String,
    steps: List<String>
) {
    var expanded by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(startTimeMs) {
        if (startTimeMs == null) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
            delay(100)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "thinkingDotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val timerText = String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MatteSurface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(StudioAccent)
                )
                Text(
                    text = liveStatus.ifBlank { "Memproses..." },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = timerText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextMuted
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = "Toggle Steps",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded && steps.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEach { step ->
                    Text(
                        text = "• $step",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineErrorCard(
    errorText: String,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(StudioDangerBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = errorText,
            fontSize = 12.sp,
            color = StudioDangerText,
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MatteElevated)
                .clickable { onRetry() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Retry",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}

private fun parseWorkspacesJson(res: org.json.JSONObject): List<WorkspaceSummary> {
    if (res.optString("status") != "ok") return emptyList()
    val arr = res.optJSONArray("workspaces") ?: return emptyList()
    val list = mutableListOf<WorkspaceSummary>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        list.add(
            WorkspaceSummary(
                name = obj.optString("name", ""),
                path = obj.optString("path", ""),
                filesCount = obj.optInt("files_count", 0),
                updatedAt = obj.optLong("updated_at", 0L),
                channelId = obj.optString("channel_id", ""),
                aspectRatio = obj.optString("aspect_ratio", "9:16"),
                stage = obj.optString("stage", "script")
            )
        )
    }
    return list
}
