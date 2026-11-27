package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.contentstudio.app.runtime.ChannelDna
import com.contentstudio.app.runtime.ChannelProfile
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.PatternBeat
import com.contentstudio.app.runtime.PatternItem
import com.contentstudio.app.runtime.WorkspaceFileItem
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
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import com.contentstudio.app.ui.theme.StudioBadge
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioSage
import java.util.Locale

@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier,
    onNavigateToVoice: (() -> Unit)? = null,
    onNavigateToAgent: (() -> Unit)? = null,
    onOpenProject: ((String) -> Unit)? = null,
    onOpenQuickVoice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    var selectedProjectForBoard by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf("projects") } // "projects", "channels", or "patterns"

    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }
    val channels = remember { mutableStateListOf<ChannelProfile>() }
    val patterns = remember { mutableStateListOf<PatternItem>() }
    val currentFiles = remember { mutableStateListOf<WorkspaceFileItem>() }

    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var selectedChannelForProject by remember { mutableStateOf("") }
    var projectCreateError by remember { mutableStateOf<String?>(null) }

    var showChannelDialog by remember { mutableStateOf(false) }
    var channelNameInput by remember { mutableStateOf("") }
    var channelAspectInput by remember { mutableStateOf("9:16") }
    var channelError by remember { mutableStateOf<String?>(null) }

    var channelForDnaRef by remember { mutableStateOf<ChannelProfile?>(null) }
    var dnaRefTextInput by remember { mutableStateOf("") }
    var channelToInspectDna by remember { mutableStateOf<ChannelProfile?>(null) }

    var showCreatePatternDialog by remember { mutableStateOf(false) }
    var newPatternName by remember { mutableStateOf("") }
    var newPatternAspect by remember { mutableStateOf("9:16") }
    var patternCreateError by remember { mutableStateOf<String?>(null) }
    var patternToDelete by remember { mutableStateOf<PatternItem?>(null) }

    var channelToDelete by remember { mutableStateOf<ChannelProfile?>(null) }
    var workspaceToDelete by remember { mutableStateOf<WorkspaceSummary?>(null) }
    var fileToDelete by remember { mutableStateOf<WorkspaceFileItem?>(null) }

    var previewTitle by remember { mutableStateOf("") }
    var previewContent by remember { mutableStateOf("") }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var copiedPreview by remember { mutableStateOf(false) }

    var actionStatus by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    fun refreshChannels() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listChannels()
            val arr = res.optJSONArray("channels") ?: return@launch
            val list = mutableListOf<ChannelProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dnaObj = obj.optJSONObject("dna")
                val traitsList = mutableListOf<String>()
                if (dnaObj != null) {
                    val traitsArr = dnaObj.optJSONArray("learned_traits")
                    if (traitsArr != null) {
                        for (k in 0 until traitsArr.length()) {
                            traitsList.add(traitsArr.optString(k))
                        }
                    }
                }
                val dna = ChannelDna(
                    learnedTraits = traitsList,
                    referenceNotes = dnaObj?.optString("reference_notes", "") ?: "",
                    avgSceneDuration = dnaObj?.optDouble("avg_scene_duration", 3.5)?.toFloat() ?: 3.5f,
                    styleSummary = dnaObj?.optString("style_summary", "") ?: "",
                )
                list.add(
                    ChannelProfile(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        niche = obj.optString("niche", ""),
                        targetAudience = obj.optString("target_audience", ""),
                        aspectRatio = obj.optString("aspect_ratio", "9:16"),
                        tone = obj.optString("tone", ""),
                        visualRules = obj.optString("visual_rules", ""),
                        subtitleStyle = obj.optString("subtitle_style", ""),
                        styleNotes = obj.optString("style_notes", ""),
                        createdAt = obj.optLong("created_at", 0L),
                        dna = dna,
                    )
                )
            }
            withContext(Dispatchers.Main) {
                channels.clear()
                channels.addAll(list)
            }
        }
    }

    fun refreshPatterns() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listPatterns()
            val arr = res.optJSONArray("patterns") ?: return@launch
            val list = mutableListOf<PatternItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val beatsArr = obj.optJSONArray("beats")
                val beats = mutableListOf<PatternBeat>()
                if (beatsArr != null) {
                    for (j in 0 until beatsArr.length()) {
                        val b = beatsArr.getJSONObject(j)
                        beats.add(
                            PatternBeat(
                                role = b.optString("role", "Beat"),
                                duration = b.optDouble("duration", 3.0).toFloat()
                            )
                        )
                    }
                }
                list.add(
                    PatternItem(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", "Pola Ritme"),
                        aspectRatio = obj.optString("aspect_ratio", "9:16"),
                        beatsCount = obj.optInt("beats_count", beats.size),
                        totalDuration = obj.optDouble("total_duration", 0.0).toFloat(),
                        beats = beats
                    )
                )
            }
            withContext(Dispatchers.Main) {
                patterns.clear()
                patterns.addAll(list)
            }
        }
    }

    fun refreshWorkspaces(autoSelectFirst: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listWorkspaces()
            val arr = res.optJSONArray("workspaces") ?: return@launch
            val list = mutableListOf<WorkspaceSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val proj = obj.optJSONObject("project")
                val chanId = proj?.optString("channel_id", "") ?: ""
                val aspect = proj?.optString("aspect_ratio", "9:16") ?: "9:16"
                val stage = proj?.optString("stage", "script") ?: "script"
                list.add(
                    WorkspaceSummary(
                        name = obj.optString("name", ""),
                        path = obj.optString("path", ""),
                        filesCount = obj.optInt("files_count", 0),
                        updatedAt = obj.optLong("updated_at", 0L),
                        channelId = chanId,
                        aspectRatio = aspect,
                        stage = stage,
                    )
                )
            }
            withContext(Dispatchers.Main) {
                workspaces.clear()
                workspaces.addAll(list)
                if (autoSelectFirst && activeWorkspace.isBlank() && list.isNotEmpty()) {
                    LinuxRuntimeManager.setActiveWorkspace(list.first().name)
                }
            }
        }
    }

    fun loadWorkspaceFiles(name: String) {
        if (name.isBlank()) {
            currentFiles.clear()
            return
        }
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.getWorkspaceFiles(name)
            val arr = res.optJSONArray("files") ?: return@launch
            val list = mutableListOf<WorkspaceFileItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    WorkspaceFileItem(
                        name = obj.optString("name"),
                        relPath = obj.optString("rel_path"),
                        size = obj.optLong("size", 0L),
                        category = obj.optString("category", "assets"),
                        updatedAt = obj.optLong("updated_at", 0L)
                    )
                )
            }
            withContext(Dispatchers.Main) {
                currentFiles.clear()
                currentFiles.addAll(list)
            }
        }
    }

    fun isTextFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("md", "txt", "json", "srt", "py", "sh", "lrc")
    }

    fun isMediaFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "jpg", "jpeg", "png", "webp", "m4a", "mp3", "wav", "aac")
    }

    fun openMediaFile(file: WorkspaceFileItem) {
        try {
            val targetFile = LinuxRuntimeManager.getWorkspaceFile(context, activeWorkspace, file.relPath)
            if (!targetFile.exists() || !targetFile.isFile) {
                actionStatus = "File tidak ditemukan di storage: ${file.name}"
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
            val mimeType = LinuxRuntimeManager.getMimeType(targetFile.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            actionStatus = "Gagal membuka media: ${e.message}"
        }
    }

    fun previewTextFile(file: WorkspaceFileItem) {
        val res = LinuxRuntimeManager.readWorkspaceTextFile(context, activeWorkspace, file.relPath)
        if (res.isSuccess) {
            previewTitle = file.name
            previewContent = res.getOrThrow()
            copiedPreview = false
            showPreviewDialog = true
        } else {
            actionStatus = "Gagal membaca file: ${res.exceptionOrNull()?.message}"
        }
    }

    fun exportFile(file: WorkspaceFileItem) {
        actionStatus = "Mengekspor ${file.name} ke folder Download..."
        scope.launch(Dispatchers.IO) {
            val src = LinuxRuntimeManager.getWorkspaceFile(context, activeWorkspace, file.relPath)
            val exportRes = LinuxRuntimeManager.exportFileToDownloads(context, src)
            withContext(Dispatchers.Main) {
                if (exportRes.isSuccess) {
                    actionStatus = "Tersimpan di ${exportRes.getOrThrow()}"
                } else {
                    actionStatus = "Gagal menyimpan: ${exportRes.exceptionOrNull()?.message}"
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || activeWorkspace.isBlank()) return@rememberLauncherForActivityResult
        isBusy = true
        actionStatus = "Mengimpor aset..."
        scope.launch(Dispatchers.IO) {
            val result = LinuxRuntimeManager.importAssetToWorkspace(context, activeWorkspace, uri)
            withContext(Dispatchers.Main) {
                isBusy = false
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    actionStatus = "Aset berhasil diimpor: ${file.name}"
                    loadWorkspaceFiles(activeWorkspace)
                    refreshWorkspaces()
                } else {
                    actionStatus = "Gagal impor aset: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshChannels()
        refreshPatterns()
        refreshWorkspaces(autoSelectFirst = true)
    }

    LaunchedEffect(activeWorkspace) {
        loadWorkspaceFiles(activeWorkspace)
    }

    if (selectedProjectForBoard != null) {
        ProjectBoardScreen(
            workspaceName = selectedProjectForBoard!!,
            onBack = {
                selectedProjectForBoard = null
                refreshWorkspaces()
            },
            onNavigateToVoice = onNavigateToVoice,
            onNavigateToAgent = onNavigateToAgent
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // ACTION BAR: + New Project & Quick Voice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0C0E))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF2F4F8))
                    .clickable {
                        newProjectName = ""
                        selectedChannelForProject = ""
                        projectCreateError = null
                        showCreateProjectDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = Color(0xFF0B0C0E),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "New Project",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0B0C0E)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF15171C))
                    .clickable {
                        onOpenQuickVoice?.invoke() ?: onNavigateToVoice?.invoke()
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFFF2F4F8),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Quick Voice",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFF2F4F8)
                    )
                }
            }
        }

        // STATUS BANNER
        if (!actionStatus.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionStatus ?: "",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // CONTENT SECTION (100% Pure Projects)
        if (workspaces.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF15171C)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VideoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF8A919E),
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "No projects yet",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF2F4F8)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap + New Project or ask Agent to start.",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = Color(0xFF8A919E)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(workspaces) { ws ->
                    val isActive = ws.name == activeWorkspace
                    val matchedChannel = channels.find { it.id == ws.channelId }
                    val stageIdx = when (ws.stage.lowercase(Locale.US)) {
                        "voice", "audio" -> 1
                        "visual", "scenes" -> 2
                        "export", "render", "done" -> 3
                        else -> 0
                    }
                    val stageNames = listOf("Script", "Voice", "Visual", "Export")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isActive) Color(0xFF1E2128) else Color(0xFF15171C))
                            .clickable {
                                LinuxRuntimeManager.setActiveWorkspace(ws.name)
                                onOpenProject?.invoke(ws.name) ?: run {
                                    selectedProjectForBoard = ws.name
                                }
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Text(
                                    text = ws.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF2F4F8)
                                )
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BadgeGreenBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Active",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BadgeGreenText
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StudioBadge(text = ws.aspectRatio, isAccent = true)
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { workspaceToDelete = ws },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Delete",
                                        tint = StudioDangerRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            stageNames.forEachIndexed { idx, stageName ->
                                val isDoneOrActive = idx <= stageIdx
                                val dotColor = if (isDoneOrActive) StudioSage else Color(0xFF8A919E)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Text(
                                        text = stageName,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = if (isDoneOrActive) FontWeight.Medium else FontWeight.Normal,
                                        color = if (isDoneOrActive) Color(0xFFF2F4F8) else Color(0xFF8A919E)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (matchedChannel != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF1E2128))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = matchedChannel.name,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            } else if (ws.channelId.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF1E2128))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Unlinked",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 10.sp,
                                        color = Color(0xFF8A919E)
                                    )
                                }
                            }

                            Text(
                                text = "${ws.filesCount} files",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFF8A919E)
                            )
                        }
                    }
                }
            }
        }
    }

    // DIALOG: CREATE NEW PROJECT
    if (showCreateProjectDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isBusy) showCreateProjectDialog = false
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "New Project",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Project folder name:",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(InputContainerBg)
                            .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (newProjectName.isEmpty()) {
                            Text(
                                text = "e.g. promo_video_01",
                                color = TextMuted,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        BasicTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it.replace(" ", "_") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(TextPrimary),
                            singleLine = true
                        )
                    }

                    if (channels.isNotEmpty()) {
                        Text(
                            text = "Link to Channel (optional):",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selectedChannelForProject.isEmpty()) CardHighlight else BgDark)
                                    .border(1.dp, if (selectedChannelForProject.isEmpty()) TextPrimary else BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { selectedChannelForProject = "" }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "None",
                                    fontSize = 11.sp,
                                    color = if (selectedChannelForProject.isEmpty()) TextPrimary else TextSecondary
                                )
                            }
                            channels.forEach { ch ->
                                val isChSel = selectedChannelForProject == ch.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isChSel) CardHighlight else BgDark)
                                        .border(1.dp, if (isChSel) TextPrimary else BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { selectedChannelForProject = ch.id }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = ch.name,
                                        fontSize = 11.sp,
                                        color = if (isChSel) TextPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    if (!projectCreateError.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(ErrorCardBg)
                                .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = projectCreateError ?: "",
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                color = ErrorCardText
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newProjectName.trim()
                        if (clean.isBlank()) {
                            projectCreateError = "Project name cannot be empty"
                            return@Button
                        }
                        isBusy = true
                        projectCreateError = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createWorkspace(
                                clean,
                                selectedChannelForProject.ifBlank { null }
                            )
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    showCreateProjectDialog = false
                                    refreshWorkspaces()
                                    LinuxRuntimeManager.setActiveWorkspace(clean)
                                    actionStatus = "Project '$clean' created"
                                } else {
                                    projectCreateError = res.optString("error", "Failed to create project")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Create",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCreateProjectDialog = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG: ZERO-FORM NEW CHANNEL
    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isBusy) showChannelDialog = false
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "New Channel",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Set up a channel identity. Channel DNA will evolve automatically from references and project renders.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Text("Channel Name:", fontSize = 11.sp, color = TextSecondary)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(InputContainerBg)
                            .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = channelNameInput,
                            onValueChange = { channelNameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Default),
                            cursorBrush = SolidColor(TextPrimary),
                            singleLine = true
                        )
                    }

                    Text("Aspect Ratio:", fontSize = 11.sp, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("9:16", "16:9").forEach { ratio ->
                            val isSel = channelAspectInput == ratio
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSel) CardHighlight else BgDark)
                                    .border(1.dp, if (isSel) TextPrimary else BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { channelAspectInput = ratio }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = ratio,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) TextPrimary else TextSecondary
                                )
                            }
                        }
                    }

                    if (!channelError.isNullOrBlank()) {
                        Text(text = channelError ?: "", color = ErrorCardText, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = channelNameInput.trim()
                        if (name.isBlank()) {
                            channelError = "Channel name cannot be empty"
                            return@Button
                        }
                        isBusy = true
                        channelError = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createChannel(
                                name = name,
                                aspectRatio = channelAspectInput
                            )
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    showChannelDialog = false
                                    refreshChannels()
                                    actionStatus = "Channel '$name' created"
                                } else {
                                    channelError = res.optString("error", "Failed to create channel")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Create",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showChannelDialog = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG: EXTRACT DNA FROM REFERENCE
    if (channelForDnaRef != null) {
        val targetChan = channelForDnaRef!!
        AlertDialog(
            onDismissRequest = { if (!isBusy) channelForDnaRef = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Extract DNA: ${targetChan.name}",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Paste video scripts, transcript excerpts, or stylistic guidelines to extract visual pacing and rules into channel DNA.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(InputContainerBg)
                            .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                            .padding(10.dp)
                    ) {
                        BasicTextField(
                            value = dnaRefTextInput,
                            onValueChange = { dnaRefTextInput = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Default),
                            cursorBrush = SolidColor(TextPrimary)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = dnaRefTextInput.trim()
                        if (text.isBlank()) return@Button
                        isBusy = true
                        actionStatus = "Extracting channel DNA..."
                        val cid = targetChan.id
                        channelForDnaRef = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.extractChannelDna(cid, text)
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    actionStatus = "DNA extracted successfully for channel"
                                    refreshChannels()
                                } else {
                                    actionStatus = "Failed to extract DNA: ${res.optString("error")}"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Extract DNA",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { channelForDnaRef = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG: INSPECT CHANNEL DNA
    if (channelToInspectDna != null) {
        val chan = channelToInspectDna!!
        AlertDialog(
            onDismissRequest = { channelToInspectDna = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "DNA Profile: ${chan.name}",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "AVERAGE TEMPO",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", chan.dna.avgSceneDuration)}s per scene",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = TextPrimary
                    )

                    Text(
                        text = "STYLE SUMMARY",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    Text(
                        text = chan.dna.styleSummary.ifBlank { "Belum ada ringkasan gaya" },
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )

                    Text(
                        text = "LEARNED TRAITS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    if (chan.dna.learnedTraits.isEmpty()) {
                        Text(
                            text = "Belum ada trait tersimpan",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        chan.dna.learnedTraits.forEach { trait ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(BgDark)
                                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "• $trait",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    if (chan.dna.referenceNotes.isNotBlank()) {
                        Text(
                            text = "REFERENCE EXCERPT",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Text(
                            text = chan.dna.referenceNotes,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { channelToInspectDna = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Close",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {}
        )
    }

    // DIALOG: CREATE PATTERN
    if (showCreatePatternDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showCreatePatternDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "New Living Pattern",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Create a universal rhythm template without niche. Initialized with default 4 beats.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Text("Pattern Name:", fontSize = 11.sp, color = TextSecondary)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(InputContainerBg)
                            .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = newPatternName,
                            onValueChange = { newPatternName = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Default),
                            cursorBrush = SolidColor(TextPrimary),
                            singleLine = true
                        )
                    }

                    Text("Aspect Ratio:", fontSize = 11.sp, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("9:16", "16:9").forEach { ratio ->
                            val isSel = newPatternAspect == ratio
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSel) CardHighlight else BgDark)
                                    .border(1.dp, if (isSel) TextPrimary else BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { newPatternAspect = ratio }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = ratio,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) TextPrimary else TextSecondary
                                )
                            }
                        }
                    }

                    if (!patternCreateError.isNullOrBlank()) {
                        Text(text = patternCreateError ?: "", color = ErrorCardText, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPatternName.trim()
                        if (name.isBlank()) {
                            patternCreateError = "Pattern name cannot be empty"
                            return@Button
                        }
                        isBusy = true
                        patternCreateError = null
                        val defaultBeats = org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("role", "Hook"); put("duration", 2.5) })
                            put(org.json.JSONObject().apply { put("role", "Setup"); put("duration", 4.0) })
                            put(org.json.JSONObject().apply { put("role", "Twist"); put("duration", 4.0) })
                            put(org.json.JSONObject().apply { put("role", "Close"); put("duration", 2.5) })
                        }
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createPattern(
                                name = name,
                                aspectRatio = newPatternAspect,
                                beatsJsonArray = defaultBeats
                            )
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    showCreatePatternDialog = false
                                    refreshPatterns()
                                    actionStatus = "Pattern '$name' created"
                                } else {
                                    patternCreateError = res.optString("error", "Failed to create pattern")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Create",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCreatePatternDialog = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG: CONFIRM DELETE PATTERN
    if (patternToDelete != null) {
        val targetPat = patternToDelete!!
        AlertDialog(
            onDismissRequest = { patternToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Delete Pattern",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Delete pattern \"${targetPat.name}\" permanently?",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = targetPat.id
                        val delName = targetPat.name
                        patternToDelete = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.deletePattern(toDel)
                            withContext(Dispatchers.Main) {
                                if (res.optString("status") == "ok") {
                                    actionStatus = "Pattern '$delName' deleted"
                                    refreshPatterns()
                                } else {
                                    actionStatus = "Failed to delete pattern: ${res.optString("error")}"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorCardBg,
                        contentColor = ErrorCardText
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { patternToDelete = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG: CONFIRM DELETE CHANNEL
    if (channelToDelete != null) {
        val targetChan = channelToDelete!!
        AlertDialog(
            onDismissRequest = { channelToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Delete Channel",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Delete channel \"${targetChan.name}\" permanently?",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = targetChan.id
                        val delName = targetChan.name
                        channelToDelete = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.deleteChannel(toDel)
                            withContext(Dispatchers.Main) {
                                if (res.optString("status") == "ok") {
                                    actionStatus = "Channel '$delName' deleted"
                                    refreshChannels()
                                } else {
                                    actionStatus = "Failed to delete channel: ${res.optString("error", "Error")}"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorCardBg,
                        contentColor = ErrorCardText
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { channelToDelete = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG PREVIEW NASKAH / FILE TEKS
    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = previewTitle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
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
                    val scrollState = rememberScrollState()
                    SelectionContainer {
                        Text(
                            text = previewContent.ifBlank { "(File kosong)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 18.sp,
                            modifier = Modifier.verticalScroll(scrollState)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(previewContent))
                        copiedPreview = true
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (copiedPreview) "Copied" else "Copy",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showPreviewDialog = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Close",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG KONFIRMASI HAPUS FILE
    if (fileToDelete != null) {
        val target = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Delete File",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Delete file \"${target.name}\" permanently from project?",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = LinuxRuntimeManager.deleteWorkspaceFile(context, activeWorkspace, target.relPath)
                        fileToDelete = null
                        if (deleted) {
                            actionStatus = "File '${target.name}' deleted"
                            loadWorkspaceFiles(activeWorkspace)
                            refreshWorkspaces()
                        } else {
                            actionStatus = "Failed to delete file '${target.name}'"
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorCardBg,
                        contentColor = ErrorCardText
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { fileToDelete = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // DIALOG KONFIRMASI HAPUS WORKSPACE
    if (workspaceToDelete != null) {
        val targetWs = workspaceToDelete!!
        AlertDialog(
            onDismissRequest = { workspaceToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "Delete Project",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Delete project \"${targetWs.name}\" and all its files permanently?",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = targetWs.name
                        workspaceToDelete = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.deleteWorkspace(toDel)
                            withContext(Dispatchers.Main) {
                                if (res.optString("status") == "ok") {
                                    actionStatus = "Project '$toDel' deleted"
                                    refreshWorkspaces()
                                } else {
                                    actionStatus = "Failed to delete project: ${res.optString("error", "Error")}"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorCardBg,
                        contentColor = ErrorCardText
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { workspaceToDelete = null },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
}
