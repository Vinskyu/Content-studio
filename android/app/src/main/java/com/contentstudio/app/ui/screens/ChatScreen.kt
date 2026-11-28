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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import android.content.Intent
import android.media.MediaPlayer
import android.speech.RecognizerIntent
import android.widget.VideoView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import coil.compose.AsyncImage
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ActionPlanCardData
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.ChatMessageItem
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.components.StudioMarkdownText
import androidx.activity.compose.BackHandler
import com.contentstudio.app.runtime.MemoryEntry
import com.contentstudio.app.runtime.StockMediaItem
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
import com.contentstudio.app.runtime.ChatSessionSummary
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
    onOpenBoard: () -> Unit = {},
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
    val chatMode by ChatController.chatMode.collectAsState()
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
    var showMentionPicker by remember { mutableStateOf(false) }
    var previewMediaTarget by remember { mutableStateOf<StockMediaItem?>(null) }
    val projectMemories by ChatController.projectMemories.collectAsState()
    val globalMemories by ChatController.globalMemories.collectAsState()
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var newMemoryNote by remember { mutableStateOf("") }
    var newMemoryCategory by remember { mutableStateOf("decision") }
    val contextItems by produceState(
        initialValue = listOf("@script", "@scenes", "@channel_dna", "@memory"),
        key1 = activeWorkspace
    ) {
        value = LinuxRuntimeManager.getProjectContextItems(context, activeWorkspace)
    }

    LaunchedEffect(activeWorkspace) {
        ChatController.refreshMemories(activeWorkspace)
    }

    val listState = rememberLazyListState()

    val chatHasOverlay = showProjectSheet || showHistorySheet || showToolsSheet || showMentionPicker || showAddMemoryDialog || editMessageTarget != null || previewMediaTarget != null
    BackHandler(enabled = chatHasOverlay) {
        when {
            showProjectSheet -> showProjectSheet = false
            showHistorySheet -> showHistorySheet = false
            showToolsSheet -> showToolsSheet = false
            showMentionPicker -> showMentionPicker = false
            showAddMemoryDialog -> showAddMemoryDialog = false
            editMessageTarget != null -> editMessageTarget = null
            previewMediaTarget != null -> previewMediaTarget = null
        }
    }

    val haptic = LocalHapticFeedback.current
    var historySearchQuery by remember { mutableStateOf("") }
    var sessionToRename by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var renameSessionInput by remember { mutableStateOf("") }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val current = ChatController.draftText.value
                val updated = if (current.isBlank()) spoken else "$current $spoken"
                ChatController.updateDraft(updated)
            }
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val localPath = LinuxRuntimeManager.copyUriToAttachmentCache(context, uri)
            if (localPath != null) {
                ChatController.addAttachment(localPath)
            } else {
                val label = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { uri.toString() } ?: uri.toString()
                ChatController.addAttachment(label)
            }
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

    LaunchedEffect(Unit) {
        ChatController.uiSyncEvents.collect {
            withContext(Dispatchers.IO) {
                val loaded = parseWorkspacesJson(LinuxRuntimeManager.listWorkspaces())
                withContext(Dispatchers.Main) {
                    projectsList.clear()
                    projectsList.addAll(loaded)
                }
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
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val sent = if (text.startsWith("/")) {
            ChatController.dispatchSlashCommand(text, activeWorkspace)
        } else {
            ChatController.sendMessage(text, activeWorkspace)
        }
        if (sent) {
            ChatController.updateDraft("")
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
            // Left: pil proyek aktif + shortcut Board 1 ketuk
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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

            // Shortcut 1 ketuk: buka Project Board proyek aktif tanpa pindah tab
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenBoard() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = "Board",
                    tint = if (activeWorkspace.isNotBlank()) TextPrimary else TextMuted,
                    modifier = Modifier.size(20.dp)
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

        // Pinned messages strip
        val pinnedMessages = messages.filter { it.isPinned }
        if (pinnedMessages.isNotEmpty()) {
            val firstPinned = pinnedMessages.first()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MatteSurface)
                    .clickable {
                        scope.launch {
                            val idx = messages.indexOfFirst { it.id == firstPinned.id }
                            if (idx != -1) listState.animateScrollToItem(idx)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    tint = StudioSage,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Pinned: " + firstPinned.text.replace("\n", " ").take(60),
                    fontSize = 11.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pinnedMessages.size > 1) {
                    Text(
                        text = "+${pinnedMessages.size - 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }
        }

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
                                },
                                onApprovePlan = {
                                    ChatController.approveActionPlan(index, activeWorkspace)
                                },
                                onDismissPlan = {
                                    ChatController.dismissActionPlan(index)
                                },
                                onTogglePin = {
                                    ChatController.togglePinMessage(index)
                                },
                                onSuggestionClick = { prompt ->
                                    ChatController.sendMessage(prompt, activeWorkspace)
                                },
                                onPreviewMedia = { media ->
                                    previewMediaTarget = media
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

        // 3. MODERN COMPOSER PRO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Floating Slash Command Menu
                val isSlash = draftText.startsWith("/")
                val slashOptions = listOf(
                    "/script" to "Tulis naskah narasi",
                    "/scenes" to "Pecah jadi adegan",
                    "/media" to "Cari stok footage",
                    "/dl" to "Download media dari URL",
                    "/hook" to "Rancang hook viral",
                    "/publish" to "Siapkan publish kit",
                    "/clear" to "Bersihkan riwayat chat"
                ).filter { (cmd, _) ->
                    if (draftText.length > 1) cmd.startsWith(draftText.trim().lowercase()) else true
                }

                if (isSlash && slashOptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MatteSurface)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        slashOptions.forEach { (cmd, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        ChatController.updateDraft("$cmd ")
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cmd,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = StudioPrimaryText
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Floating @ Context Mention Picker
                val hasAt = draftText.contains("@")
                val atQuery = if (hasAt) draftText.substringAfterLast("@").lowercase() else ""
                val shouldShowMentions = showMentionPicker || (hasAt && !atQuery.contains(" "))
                val mentionOptions = if (shouldShowMentions) {
                    contextItems.filter {
                        if (atQuery.isNotBlank()) it.lowercase().contains(atQuery) else true
                    }
                } else emptyList()

                if (shouldShowMentions && mentionOptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MatteSurface)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Konteks Proyek (@)",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { showMentionPicker = false }
                            )
                        }
                        mentionOptions.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val newText = if (hasAt) {
                                            draftText.substringBeforeLast("@") + item + " "
                                        } else {
                                            if (draftText.isBlank()) "$item " else "$draftText $item "
                                        }
                                        ChatController.updateDraft(newText)
                                        showMentionPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = item,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = StudioSage
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

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
                                        text = file.substringAfterLast('/'),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
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
                                        text = "Tulis pesan, ketik '/', atau '@'...",
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
                        // Left: Attach, Slash '/', Mention '@', Mode & Tools
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                    .clickable {
                                        if (!draftText.startsWith("/")) {
                                            ChatController.updateDraft("/$draftText")
                                        }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "/",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (showMentionPicker) StudioSage.copy(alpha = 0.2f) else MatteElevated)
                                    .clickable {
                                        showMentionPicker = !showMentionPicker
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "@",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showMentionPicker) StudioSage else TextSecondary
                                )
                            }

                            // Mode & Tools Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MatteElevated)
                                    .clickable { showToolsSheet = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${chatMode.replaceFirstChar { it.uppercase() }} ⌄",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        // Right: Voice Input + Send/Stop
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MatteElevated)
                                    .clickable {
                                        try {
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bicara sekarang...")
                                            }
                                            speechLauncher.launch(intent)
                                        } catch (_: Exception) {}
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MicNone,
                                    contentDescription = "Voice Input",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            if (isStreaming) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(StudioDangerBg)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ChatController.stopStreaming()
                                        },
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
                Spacer(modifier = Modifier.height(10.dp))

                // Search chats bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        BasicTextField(
                            value = historySearchQuery,
                            onValueChange = { historySearchQuery = it },
                            textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                            cursorBrush = SolidColor(TextPrimary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (historySearchQuery.isEmpty()) {
                                    Text(text = "Search chats...", fontSize = 13.sp, color = TextMuted)
                                }
                                inner()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                val filteredSessions = sessions.filter {
                    historySearchQuery.isBlank() || it.title.contains(historySearchQuery.trim(), ignoreCase = true)
                }

                if (filteredSessions.isEmpty()) {
                    Text(
                        text = if (historySearchQuery.isBlank()) "Belum ada riwayat sesi tersimpan." else "Tidak ada sesi yang cocok.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    filteredSessions.forEach { sess ->
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            sessionToRename = sess
                                            renameSessionInput = sess.title
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Rename Session",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
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
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Rename Session Dialog
    if (sessionToRename != null) {
        val targetSess = sessionToRename!!
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            containerColor = MatteSurface,
            title = { Text(text = "Ubah Nama Sesi", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteElevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = renameSessionInput,
                        onValueChange = { renameSessionInput = it },
                        textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                        cursorBrush = SolidColor(TextPrimary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF2F4F8))
                        .clickable {
                            val newName = renameSessionInput.trim()
                            if (newName.isNotBlank()) {
                                ChatController.renameSession(targetSess.id, newName)
                            }
                            sessionToRename = null
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(text = "Simpan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0B0C0E))
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { sessionToRename = null }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(text = "Batal", fontSize = 12.sp, color = TextMuted)
                }
            }
        )
    }

    // ── MODAL SHEET: AGENT TOOLS STATUS & PROJECT MEMORY ──────────────────────
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            containerColor = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Kemampuan & Engine Agent",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                ToolStatusRow("Project & Script Engine", "Active", StudioSage)
                ToolStatusRow("Voice & Render Engine", "Active", StudioSage)
                ToolStatusRow("Stock Media Search", "Active", StudioSage)
                ToolStatusRow(
                    "Cross-Session Memory",
                    if (activeWorkspace.isNotBlank()) "Attached: $activeWorkspace" else "Global Only",
                    StudioSage
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Project Memory & Rules section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeWorkspace.isNotBlank()) "Project Memory & Rules (${projectMemories.size})" else "Global Preferences (${globalMemories.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (activeWorkspace.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MatteElevated)
                                .clickable {
                                    newMemoryNote = ""
                                    newMemoryCategory = "decision"
                                    showAddMemoryDialog = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = "Add Rule",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Add Rule",
                                    fontSize = 11.sp,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                if (activeWorkspace.isBlank()) {
                    Text(
                        text = "Pilih proyek di menu atas untuk melihat dan mengelola memory proyek spesifik.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                } else if (projectMemories.isEmpty()) {
                    Text(
                        text = "Belum ada rule atau preferensi tersimpan untuk proyek ini. Agent akan mencatat keputusan penting otomatis atau Anda bisa menambahkannya manual.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        projectMemories.forEach { mem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MatteElevated)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = mem.note,
                                        fontSize = 12.sp,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = mem.category.uppercase(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = TextMuted
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Hapus Memory",
                                    tint = TextMuted,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            ChatController.deleteMemory(activeWorkspace, mem.id)
                                        }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                StudioPrimaryButton(
                    text = "Buka Skills & MCP",
                    onClick = {
                        showToolsSheet = false
                        onNavigateToExtensions()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ── DIALOG: ADD MEMORY RULE ──────────────────────────────────────────────
    if (showAddMemoryDialog && activeWorkspace.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = "Tambah Project Rule / Memory",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioTextField(
                        value = newMemoryNote,
                        onValueChange = { newMemoryNote = it },
                        placeholder = "Catatan atau aturan untuk agent...",
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 120.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("decision", "style", "rejected", "fact").forEach { cat ->
                            val isSelected = newMemoryCategory == cat
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) MatteElevated else MatteSurface)
                                    .clickable { newMemoryCategory = cat }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = cat,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) TextPrimary else TextMuted
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                StudioPrimaryButton(
                    text = "Simpan",
                    onClick = {
                        val note = newMemoryNote.trim()
                        if (note.isNotBlank()) {
                            ChatController.addMemory(activeWorkspace, note, newMemoryCategory)
                        }
                        showAddMemoryDialog = false
                    }
                )
            },
            dismissButton = {
                StudioSecondaryButton(
                    text = "Batal",
                    onClick = { showAddMemoryDialog = false }
                )
            }
        )
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

    // ── DIALOG: INLINE MEDIA PREVIEW ─────────────────────────────────────────
    val previewTarget = previewMediaTarget
    if (previewTarget != null) {
        InlineMediaPreviewDialog(
            media = previewTarget,
            onDismiss = { previewMediaTarget = null },
            onSave = {
                val targetUrl = previewTarget.downloadUrl
                previewMediaTarget = null
                ChatController.sendMessage("Unduh media: $targetUrl", activeWorkspace)
            },
            onAddToProject = {
                val targetUrl = previewTarget.downloadUrl
                previewMediaTarget = null
                ChatController.sendMessage(
                    "Unduh dan pasangkan media ke scene proyek: $targetUrl",
                    activeWorkspace
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
    onUseAsScript: (String) -> Unit,
    onApprovePlan: () -> Unit = {},
    onDismissPlan: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onPreviewMedia: (StockMediaItem) -> Unit = {}
) {
    var copied by remember { mutableStateOf(false) }
    var traceExpanded by remember { mutableStateOf(false) }

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
        // Action Plan Card if present
        val plan = message.actionPlan
        if (plan != null) {
            ActionPlanCard(
                plan = plan,
                onApprove = onApprovePlan,
                onDismiss = onDismissPlan
            )
        }

        if (!message.isStreaming && message.processSteps.isNotEmpty()) {
            val secStr = String.format(Locale.US, "%.1fs", message.processDurationMs / 1000.0)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MatteSurface)
                    .clickable { traceExpanded = !traceExpanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Processed in $secStr · ${message.processSteps.size} steps",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Icon(
                        imageVector = if (traceExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Toggle Process Steps",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                AnimatedVisibility(visible = traceExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        message.processSteps.forEach { step ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = StudioSage,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = step,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        SelectionContainer {
            StudioMarkdownText(
                markdown = message.text,
                onCopyCodeBlock = onCopy
            )
        }

        // Inline Media Cards
        if (message.mediaItems.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(message.mediaItems) { media ->
                    Column(
                        modifier = Modifier
                            .width(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MatteSurface)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (media.thumbnailUrl.isNotBlank()) {
                            AsyncImage(
                                model = media.thumbnailUrl,
                                contentDescription = media.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0B0C0E))
                            )
                        }
                        Text(
                            text = media.title.ifBlank { media.id },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = media.provider.uppercase(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = TextMuted
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MatteElevated)
                                        .clickable { onPreviewMedia(media) }
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = "Preview",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            text = "Play",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MatteElevated)
                                        .clickable {
                                            ChatController.sendMessage(
                                                "Unduh media: ${media.downloadUrl}",
                                                ""
                                            )
                                        }
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Save",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFF2F4F8))
                                        .clickable {
                                            ChatController.sendMessage(
                                                "Unduh dan pasangkan media ke scene proyek: ${media.downloadUrl}",
                                                ""
                                            )
                                        }
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "+ Project",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0B0C0E)
                                    )
                                }
                            }
                        }
                    }
                }
            }
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable { onTogglePin() }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = if (message.isPinned) "Unpin" else "Pin",
                        tint = if (message.isPinned) StudioSage else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (message.isPinned) "Pinned" else "Pin",
                        fontSize = 11.sp,
                        color = if (message.isPinned) StudioSage else TextMuted
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

        // Smart next-step chips
        if (isLastAgentMessage && !message.isStreaming && message.suggestions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(message.suggestions) { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MatteElevated)
                            .clickable { onSuggestionClick(suggestion) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = suggestion,
                            fontSize = 12.sp,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPlanCard(
    plan: ActionPlanCardData,
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MatteSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = plan.title.ifBlank { "Action Plan" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            val (badgeText, badgeColor) = when (plan.status) {
                "approved" -> "APPROVED" to StudioSage
                "dismissed" -> "DISMISSED" to StudioDangerRed
                else -> "PLAN" to StudioAccent
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MatteElevated)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }
        }

        if (plan.goal.isNotBlank()) {
            Text(
                text = plan.goal,
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 17.sp
            )
        }

        if (plan.steps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                plan.steps.forEachIndexed { i, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MatteElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${i + 1}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = step,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        when (plan.status) {
            "pending" -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MatteElevated)
                            .clickable { onDismiss() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tolak",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSage)
                            .clickable { onApprove() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Jalankan Plan",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0B0C0E)
                        )
                    }
                }
            }
            "approved" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = StudioSage,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Plan disetujui & sedang dijalankan",
                        fontSize = 11.sp,
                        color = StudioSage
                    )
                }
            }
            "dismissed" -> {
                Text(
                    text = "Plan ditolak",
                    fontSize = 11.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun InlineMediaPreviewDialog(
    media: StockMediaItem,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onAddToProject: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MatteSurface)
                    .clickable(enabled = false) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title.ifBlank { media.id },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = media.provider.uppercase(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = StudioAccent
                            )
                            if (media.durationSec > 0) {
                                Text(
                                    text = "· ${media.durationSec}s",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                            if (media.width > 0 && media.height > 0) {
                                Text(
                                    text = "· ${media.width}x${media.height}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MatteElevated)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Media Preview
                val isVideo = media.type == "video" ||
                        media.downloadUrl.endsWith(".mp4", ignoreCase = true) ||
                        media.downloadUrl.endsWith(".webm", ignoreCase = true) ||
                        media.downloadUrl.endsWith(".mov", ignoreCase = true) ||
                        media.provider == "ytdlp"

                val isAudio = media.type == "audio" ||
                        media.downloadUrl.endsWith(".mp3", ignoreCase = true) ||
                        media.downloadUrl.endsWith(".wav", ignoreCase = true)

                if (isVideo) {
                    val videoUri = media.downloadUrl.ifBlank { media.thumbnailUrl }
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val mc = android.widget.MediaController(ctx)
                                mc.setAnchorView(this)
                                setMediaController(mc)
                                setVideoURI(Uri.parse(videoUri))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    )
                } else if (isAudio) {
                    var isPlaying by remember { mutableStateOf(false) }
                    val mediaPlayer = remember {
                        MediaPlayer().apply {
                            try {
                                setDataSource(media.downloadUrl)
                                prepareAsync()
                                setOnPreparedListener {
                                    start()
                                    isPlaying = true
                                }
                                setOnCompletionListener {
                                    isPlaying = false
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            try {
                                mediaPlayer.stop()
                                mediaPlayer.release()
                            } catch (_: Exception) {}
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MatteElevated)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(StudioAccent)
                                .clickable {
                                    if (mediaPlayer.isPlaying) {
                                        mediaPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        mediaPlayer.start()
                                        isPlaying = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color(0xFF0B0C0E),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isPlaying) "Playing audio..." else "Paused",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                } else {
                    AsyncImage(
                        model = media.downloadUrl.ifBlank { media.thumbnailUrl },
                        contentDescription = media.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MatteElevated)
                            .clickable { onSave() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Save Media",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF2F4F8))
                            .clickable { onAddToProject() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+ Add to Project",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0B0C0E)
                        )
                    }
                }
            }
        }
    }
}

@Composable
}

@Composable
private fun LiveThinkingCard(
    startTimeMs: Long?,
    liveStatus: String,
    steps: List<String>
) {
    var expanded by remember { mutableStateOf(true) }
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
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
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
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Toggle Steps",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded && steps.isNotEmpty()) {
            val stepsScrollState = rememberScrollState()
            LaunchedEffect(steps.size) {
                stepsScrollState.animateScrollTo(Int.MAX_VALUE)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(stepsScrollState)
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEachIndexed { idx, step ->
                    val isLatest = (idx == steps.lastIndex)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLatest) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .alpha(dotAlpha)
                                        .clip(CircleShape)
                                        .background(StudioAccent)
                                )
                            }
                            Text(
                                text = step,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = StudioSage,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = step,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
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
