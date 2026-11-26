package com.contentstudio.app.ui.screens

import android.content.Intent
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.PatternBeat
import com.contentstudio.app.runtime.PatternItem
import com.contentstudio.app.runtime.WorkspaceFileItem
import com.contentstudio.app.ui.theme.StudioAccent
import com.contentstudio.app.ui.theme.StudioBadge
import com.contentstudio.app.ui.theme.StudioBadgeGreenBg
import com.contentstudio.app.ui.theme.StudioBadgeGreenText
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioBorder
import com.contentstudio.app.ui.theme.StudioCard
import com.contentstudio.app.ui.theme.StudioDangerBg
import com.contentstudio.app.ui.theme.StudioDangerText
import com.contentstudio.app.ui.theme.StudioPrimary
import com.contentstudio.app.ui.theme.StudioPrimaryButton
import com.contentstudio.app.ui.theme.StudioSecondaryButton
import com.contentstudio.app.ui.theme.StudioSectionHeader
import com.contentstudio.app.ui.theme.StudioSegmentedControl
import com.contentstudio.app.ui.theme.StudioSmallButton
import com.contentstudio.app.ui.theme.StudioSurface
import com.contentstudio.app.ui.theme.StudioSurfaceElevated
import com.contentstudio.app.ui.theme.StudioTextField
import com.contentstudio.app.ui.theme.StudioTopBar
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SceneItem(
    var id: String,
    var narration: String,
    var prompt: String,
    var visual: String,
    var duration: Float,
    var beatRole: String = "Beat",
)

@Composable
fun ProjectBoardScreen(
    workspaceName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToVoice: (() -> Unit)? = null,
    onNavigateToAgent: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var selectedBoardTab by remember { mutableIntStateOf(0) } // 0: Script, 1: Scenes, 2: Audio, 3: Render
    val boardTabs = listOf("Script", "Scenes", "Audio", "Render")

    // Project state
    var aspectRatio by remember { mutableStateOf("9:16") }
    var projectStage by remember { mutableStateOf("script") }
    var scriptText by remember { mutableStateOf("") }
    val scenesList = remember { mutableStateListOf<SceneItem>() }

    // Audio state
    var voFile by remember { mutableStateOf<String?>(null) }
    var bgmFile by remember { mutableStateOf<String?>(null) }
    var bgmVolume by remember { mutableFloatStateOf(0.18f) }
    var autoDuck by remember { mutableStateOf(true) }

    // Render state
    var burnSubtitles by remember { mutableStateOf(true) }
    var gentleMotion by remember { mutableStateOf(true) }
    var resolutionIndex by remember { mutableIntStateOf(0) } // 0: 720p, 1: 1080p
    val resolutionOptions = listOf("720p Fast", "1080p HD")

    var isRendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableIntStateOf(0) }
    var renderStep by remember { mutableStateOf("Idle") }
    var outputFile by remember { mutableStateOf<String?>(null) }
    var outputFileSize by remember { mutableStateOf(0L) }
    var renderError by remember { mutableStateOf<String?>(null) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Modals
    var showFilesModal by remember { mutableStateOf(false) }
    var showLibraryPickerForScene by remember { mutableStateOf<Int?>(null) }
    var activeAudioPickingType by remember { mutableStateOf<String?>(null) } // "vo" or "bgm"
    var showApplyPatternDialog by remember { mutableStateOf(false) }
    val availablePatterns = remember { mutableStateListOf<PatternItem>() }
    var showSavePatternDialog by remember { mutableStateOf(false) }
    var newPatternNameInput by remember { mutableStateOf("") }

    val libraryAssets = remember { mutableStateListOf<Pair<String, String>>() } // cat, name
    val rawFiles = remember { mutableStateListOf<WorkspaceFileItem>() }
    val projectAudioFiles = remember { mutableStateListOf<String>() }

    // Audio Player
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingAudioPath by remember { mutableStateOf<String?>(null) }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_) {}
        mediaPlayer = null
        playingAudioPath = null
    }

    fun playAudio(file: File) {
        if (!file.exists()) return
        if (playingAudioPath == file.absolutePath) {
            stopAudio()
            return
        }
        stopAudio()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { stopAudio() }
            mediaPlayer = mp
            playingAudioPath = file.absolutePath
        } catch (_) {
            stopAudio()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopAudio() }
    }

    // Auto-clear notification timer
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(3000)
            statusMessage = null
        }
    }

    fun loadProject() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.getProject(workspaceName)
            res.onSuccess { proj ->
                withContext(Dispatchers.Main) {
                    aspectRatio = proj.optString("aspect_ratio", "9:16")
                    projectStage = proj.optString("stage", "script")
                    outputFile = if (proj.has("output_file") && !proj.isNull("output_file")) proj.optString("output_file") else null

                    // Audio
                    val audioObj = proj.optJSONObject("audio")
                    if (audioObj != null) {
                        voFile = if (audioObj.has("vo_file") && !audioObj.isNull("vo_file")) audioObj.optString("vo_file") else null
                        bgmFile = if (audioObj.has("bgm_file") && !audioObj.isNull("bgm_file")) audioObj.optString("bgm_file") else null
                        bgmVolume = audioObj.optDouble("bgm_volume", 0.18).toFloat()
                        autoDuck = audioObj.optBoolean("auto_duck", true)
                    }

                    // Scenes
                    val arr = proj.optJSONArray("scenes")
                    scenesList.clear()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val sc = arr.getJSONObject(i)
                            scenesList.add(
                                SceneItem(
                                    id = sc.optString("id", "scene_${i + 1}"),
                                    narration = sc.optString("narration", sc.optString("text", "")),
                                    prompt = sc.optString("prompt", ""),
                                    visual = sc.optString("visual", ""),
                                    duration = sc.optDouble("duration", 4.0).toFloat(),
                                    beatRole = sc.optString("beat_role", sc.optString("title", "Beat"))
                                )
                            )
                        }
                    }

                    // Check output file size if exists
                    if (!outputFile.isNullOrBlank()) {
                        val outFile = LinuxRuntimeManager.getWorkspaceFile(workspaceName, outputFile!!)
                        if (outFile.exists()) {
                            outputFileSize = outFile.length()
                        }
                    }
                }
            }

            // Load main script text
            val scriptRes = LinuxRuntimeManager.readWorkspaceTextFile(workspaceName, "scripts/main.txt")
            scriptRes.onSuccess { text ->
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) scriptText = text
                }
            }

            // Scan audio directory
            val wsAudioDir = File(LinuxRuntimeManager.getWorkspaceDir(workspaceName), "audio")
            val audioList = mutableListOf<String>()
            if (wsAudioDir.exists() && wsAudioDir.isDirectory) {
                wsAudioDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.extension.lowercase(Locale.ROOT) in listOf("mp3", "m4a", "wav", "aac", "ogg")) {
                        audioList.add(f.name)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                projectAudioFiles.clear()
                projectAudioFiles.addAll(audioList.sorted())
            }
        }
    }

    LaunchedEffect(workspaceName) {
        loadProject()
    }

    fun saveProjectData(onDone: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val projObj = JSONObject().apply {
                put("name", workspaceName)
                put("aspect_ratio", aspectRatio)
                put("stage", projectStage)
                if (outputFile != null) put("output_file", outputFile)
                put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()))

                val audioObj = JSONObject().apply {
                    put("vo_file", voFile)
                    put("bgm_file", bgmFile)
                    put("bgm_volume", bgmVolume.toDouble())
                    put("auto_duck", autoDuck)
                }
                put("audio", audioObj)

                val scenesArr = JSONArray()
                scenesList.forEach { sc ->
                    val scObj = JSONObject().apply {
                        put("id", sc.id)
                        put("title", sc.beatRole)
                        put("beat_role", sc.beatRole)
                        put("narration", sc.narration)
                        put("prompt", sc.prompt)
                        put("visual", sc.visual)
                        put("duration", sc.duration.toDouble())
                    }
                    scenesArr.put(scObj)
                }
                put("scenes", scenesArr)
            }

            val res = LinuxRuntimeManager.saveProject(workspaceName, projObj)
            withContext(Dispatchers.Main) {
                res.onSuccess {
                    statusMessage = "Project board saved"
                    onDone?.invoke()
                }.onFailure { err ->
                    errorMessage = err.message ?: "Failed to save project"
                }
            }
        }
    }

    fun saveScriptContent() {
        scope.launch(Dispatchers.IO) {
            val scriptsDir = File(LinuxRuntimeManager.getWorkspaceDir(workspaceName), "scripts")
            if (!scriptsDir.exists()) scriptsDir.mkdirs()
            val file = File(scriptsDir, "main.txt")
            file.writeText(scriptText)
            withContext(Dispatchers.Main) {
                statusMessage = "Script saved to scripts/main.txt"
            }
        }
    }

    fun splitScriptToScenes() {
        if (scriptText.isBlank()) {
            errorMessage = "Script is empty"
            return
        }

        val rawBlocks = scriptText.split(Regex("(?:\r?\n){2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val generatedScenes = mutableListOf<SceneItem>()

        rawBlocks.forEachIndexed { index, block ->
            val wordCount = block.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            val estDur = (wordCount * (60f / 130f)).coerceIn(3.0f, 10.0f)
            val roundedDur = (Math.round(estDur * 2.0) / 2.0).toFloat()

            val sceneId = "scene_${String.format(Locale.US, "%02d", index + 1)}"
            generatedScenes.add(
                SceneItem(
                    id = sceneId,
                    narration = block,
                    prompt = "Cinematic b-roll visualization: $block",
                    visual = "",
                    duration = roundedDur
                )
            )
        }

        scenesList.clear()
        scenesList.addAll(generatedScenes)
        saveProjectData {
            selectedBoardTab = 1 // Switch to Scenes tab
            statusMessage = "Generated ${generatedScenes.size} scenes from script"
        }
    }

    // Visual Import Launcher
    var activeSceneIndexForImport by remember { mutableStateOf<Int?>(null) }
    val visualImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && activeSceneIndexForImport != null) {
            val sceneIdx = activeSceneIndexForImport!!
            scope.launch(Dispatchers.IO) {
                val res = LinuxRuntimeManager.importFileToWorkspaceAssets(context, workspaceName, uri)
                withContext(Dispatchers.Main) {
                    res.onSuccess { relPath ->
                        if (sceneIdx in scenesList.indices) {
                            scenesList[sceneIdx] = scenesList[sceneIdx].copy(visual = relPath)
                            saveProjectData()
                            statusMessage = "Visual imported to Scene ${sceneIdx + 1}"
                        }
                    }.onFailure { err ->
                        errorMessage = "Import failed: ${err.message}"
                    }
                }
            }
        }
    }

    // Polling render status
    LaunchedEffect(isRendering) {
        while (isRendering) {
            delay(1000)
            val res = LinuxRuntimeManager.getRenderStatus(workspaceName)
            res.onSuccess { statusObj ->
                val st = statusObj.optString("status", "idle")
                renderProgress = statusObj.optInt("progress", 0)
                renderStep = statusObj.optString("step", "")

                if (st == "done") {
                    isRendering = false
                    outputFile = statusObj.optString("output_file", "")
                    outputFileSize = statusObj.optLong("size", 0L)
                    renderProgress = 100
                    statusMessage = "Render completed successfully"
                    loadProject()
                } else if (st == "error") {
                    isRendering = false
                    renderError = statusObj.optString("error", "Render failed")
                    errorMessage = renderError
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        // TOP BAR
        StudioTopBar(
            title = workspaceName,
            subtitle = "Project Production Board",
            onBack = onBack,
            actions = {
                StudioBadge(text = aspectRatio, isAccent = true)
                Spacer(modifier = Modifier.width(4.dp))
                StudioSmallButton(
                    text = "Refresh",
                    onClick = {
                        loadProject()
                        statusMessage = "Project refreshed from disk"
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                StudioSmallButton(
                    text = "Files",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.getWorkspaceFiles(workspaceName)
                            val arr = res.optJSONArray("files")
                            val list = mutableListOf<WorkspaceFileItem>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    list.add(
                                        WorkspaceFileItem(
                                            name = obj.optString("name", ""),
                                            relPath = obj.optString("rel_path", ""),
                                            size = obj.optLong("size", 0),
                                            updatedAt = obj.optString("updated_at", ""),
                                            isDirectory = obj.optBoolean("is_directory", false)
                                        )
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                rawFiles.clear()
                                rawFiles.addAll(list)
                                showFilesModal = true
                            }
                        }
                    }
                )
            }
        )

        // STATUS BAR (MESSAGES)
        if (statusMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioBadgeGreenBg)
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = statusMessage!!,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = StudioBadgeGreenText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioDangerBg)
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = errorMessage!!,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = StudioDangerText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 4 SEGMENTED PRODUCTION TABS
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            StudioSegmentedControl(
                options = boardTabs,
                selectedIndex = selectedBoardTab,
                onSelect = { selectedBoardTab = it }
            )
        }

        // TAB CONTENT
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedBoardTab) {
                0 -> {
                    // TAB 1: SCRIPT
                    val words = scriptText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    val estMin = (words / 130f)
                    val estSec = Math.round(estMin * 60)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StudioCard {
                            StudioSectionHeader(title = "Naskah Utama")
                            Text(
                                text = "Tulis naskah konten lengkap di bawah ini. Naskah otomatis tersimpan di scripts/main.txt.",
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            StudioTextField(
                                value = scriptText,
                                onValueChange = { scriptText = it },
                                placeholder = "Tulis atau tempel naskah konten di sini...",
                                singleLine = false,
                                minLines = 8,
                                maxLines = 14,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$words words • ~$estSec sec ($estMin min)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StudioSmallButton(
                                        text = "Ask Agent",
                                        onClick = {
                                            ChatController.setDraftPrompt("Bantu saya perbaiki naskah untuk project '$workspaceName':\n\n$scriptText")
                                            onNavigateToAgent?.invoke()
                                        }
                                    )
                                    StudioSecondaryButton(
                                        text = "Save Script",
                                        onClick = { saveScriptContent() },
                                        height = 36.dp
                                    )
                                    StudioPrimaryButton(
                                        text = "Split to Scenes",
                                        onClick = { splitScriptToScenes() },
                                        height = 36.dp
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 2: SCENES (STORYBOARD)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StudioSmallButton(
                                text = "Apply Pattern",
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val res = LinuxRuntimeManager.listPatterns()
                                        val arr = res.optJSONArray("patterns")
                                        val list = mutableListOf<PatternItem>()
                                        if (arr != null) {
                                            for (i in 0 until arr.length()) {
                                                val obj = arr.getJSONObject(i)
                                                val bArr = obj.optJSONArray("beats")
                                                val beats = mutableListOf<PatternBeat>()
                                                if (bArr != null) {
                                                    for (j in 0 until bArr.length()) {
                                                        val b = bArr.getJSONObject(j)
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
                                        }
                                        withContext(Dispatchers.Main) {
                                            availablePatterns.clear()
                                            availablePatterns.addAll(list)
                                            showApplyPatternDialog = true
                                        }
                                    }
                                }
                            )
                            StudioSmallButton(
                                text = "Save as Pattern",
                                enabled = scenesList.isNotEmpty(),
                                onClick = {
                                    newPatternNameInput = "$workspaceName Pattern"
                                    showSavePatternDialog = true
                                }
                            )
                            StudioSmallButton(
                                text = "Match VO",
                                enabled = !voFile.isNullOrBlank(),
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val res = LinuxRuntimeManager.syncVoDuration(workspaceName)
                                        withContext(Dispatchers.Main) {
                                            res.onSuccess {
                                                statusMessage = "Scene duration synchronized with VO audio"
                                                loadProject()
                                            }.onFailure { err ->
                                                errorMessage = err.message ?: "Sync VO failed"
                                            }
                                        }
                                    }
                                }
                            )
                            StudioSmallButton(
                                text = "Ask Agent",
                                onClick = {
                                    ChatController.setDraftPrompt("Bantu susun ulang adegan dan beat_role untuk project '$workspaceName': ")
                                    onNavigateToAgent?.invoke()
                                }
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            if (scenesList.isEmpty()) {
                                item {
                                    StudioCard {
                                        Text(
                                            text = "Belum ada adegan. Buka tab Script dan klik 'Split to Scenes' atau tambah adegan manual di bawah.",
                                            fontFamily = FontFamily.Default,
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(scenesList) { index, scene ->
                                    StudioCard {
                                        // Header: Scene 01 + Duration + Actions
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                StudioBadge(
                                                    text = "Scene ${String.format(Locale.US, "%02d", index + 1)}",
                                                    isAccent = true
                                                )

                                                StudioTextField(
                                                    value = scene.beatRole,
                                                    onValueChange = { scene.beatRole = it },
                                                    placeholder = "Role",
                                                    singleLine = true,
                                                    modifier = Modifier.width(90.dp)
                                                )

                                                // Duration stepper
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    StudioSmallButton(
                                                        text = "-0.5s",
                                                        onClick = {
                                                            val newDur = (scene.duration - 0.5f).coerceAtLeast(1.0f)
                                                            scenesList[index] = scene.copy(duration = newDur)
                                                        }
                                                    )
                                                    Text(
                                                        text = "${String.format(Locale.US, "%.1f", scene.duration)}s",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        color = TextPrimary
                                                    )
                                                    StudioSmallButton(
                                                        text = "+0.5s",
                                                        onClick = {
                                                            val newDur = (scene.duration + 0.5f).coerceAtMost(30.0f)
                                                            scenesList[index] = scene.copy(duration = newDur)
                                                        }
                                                    )
                                                }
                                            }

                                            // Reorder & Delete
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (index > 0) {
                                                    StudioSmallButton(
                                                        text = "Up",
                                                        onClick = {
                                                            val prev = scenesList[index - 1]
                                                            scenesList[index - 1] = scene
                                                            scenesList[index] = prev
                                                        }
                                                    )
                                                }
                                                if (index < scenesList.size - 1) {
                                                    StudioSmallButton(
                                                        text = "Down",
                                                        onClick = {
                                                            val next = scenesList[index + 1]
                                                            scenesList[index + 1] = scene
                                                            scenesList[index] = next
                                                        }
                                                    )
                                                }
                                                StudioSmallButton(
                                                    text = "Delete",
                                                    isDanger = true,
                                                    onClick = {
                                                        scenesList.removeAt(index)
                                                    }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Narration Input
                                        Text(
                                            text = "NARASI",
                                            fontFamily = FontFamily.Default,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextMuted
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        StudioTextField(
                                            value = scene.narration,
                                            onValueChange = {
                                                scenesList[index] = scene.copy(narration = it)
                                            },
                                            placeholder = "Teks narasi untuk adegan ini...",
                                            singleLine = false,
                                            minLines = 2,
                                            maxLines = 4
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Prompt Input + Copy Prompt
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "PROMPT VISUAL",
                                                fontFamily = FontFamily.Default,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMuted
                                            )
                                            if (scene.prompt.isNotBlank()) {
                                                StudioSmallButton(
                                                    text = "Copy Prompt",
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(scene.prompt))
                                                        statusMessage = "Prompt copied to clipboard"
                                                    }
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        StudioTextField(
                                            value = scene.prompt,
                                            onValueChange = {
                                                scenesList[index] = scene.copy(prompt = it)
                                            },
                                            placeholder = "Deskripsi gambar / prompt AI visual...",
                                            singleLine = false,
                                            minLines = 2,
                                            maxLines = 3
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Visual Slot
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(StudioSurfaceElevated)
                                                .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "VISUAL SLOT",
                                                    fontFamily = FontFamily.Default,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = if (scene.visual.isNotBlank()) scene.visual else "Empty Visual",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = if (scene.visual.isNotBlank()) StudioAccent else TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                StudioSmallButton(
                                                    text = "From Library",
                                                    onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            val res = LinuxRuntimeManager.listLibraryAssets()
                                                            val arr = res.optJSONArray("assets")
                                                            val list = mutableListOf<Pair<String, String>>()
                                                            if (arr != null) {
                                                                for (i in 0 until arr.length()) {
                                                                    val obj = arr.getJSONObject(i)
                                                                    val cat = obj.optString("category", "")
                                                                    val name = obj.optString("name", "")
                                                                    if (name.isNotBlank()) list.add(cat to name)
                                                                }
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                libraryAssets.clear()
                                                                libraryAssets.addAll(list)
                                                                showLibraryPickerForScene = index
                                                            }
                                                        }
                                                    }
                                                )
                                                StudioSmallButton(
                                                    text = "Import",
                                                    onClick = {
                                                        activeSceneIndexForImport = index
                                                        visualImportLauncher.launch("*/*")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Actions: + Add Scene & Save Board
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StudioSecondaryButton(
                                text = "+ Add Scene",
                                onClick = {
                                    val nextNum = scenesList.size + 1
                                    scenesList.add(
                                        SceneItem(
                                            id = "scene_${String.format(Locale.US, "%02d", nextNum)}",
                                            narration = "",
                                            prompt = "",
                                            visual = "",
                                            duration = 4.0f
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                            StudioPrimaryButton(
                                text = "Save Board",
                                onClick = { saveProjectData() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                2 -> {
                    // TAB 3: AUDIO
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // VOICEOVER TRACK
                        StudioCard {
                            StudioSectionHeader(
                                title = "Voiceover Track",
                                action = {
                                    if (onNavigateToVoice != null) {
                                        StudioSmallButton(
                                            text = "Open Voice Studio",
                                            onClick = onNavigateToVoice
                                        )
                                    }
                                }
                            )
                            Text(
                                text = "Pilih rekaman suara atau hasil TTS dari folder audio/ proyek.",
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TRACK VO",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = voFile ?: "No VO attached",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = if (voFile != null) StudioBadgeGreenText else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (voFile != null) {
                                        val targetFile = LinuxRuntimeManager.getWorkspaceFile(workspaceName, "audio/$voFile")
                                        val isPlaying = playingAudioPath == targetFile.absolutePath
                                        StudioSmallButton(
                                            text = if (isPlaying) "Stop" else "Play",
                                            onClick = { playAudio(targetFile) }
                                        )
                                    }
                                    StudioSmallButton(
                                        text = "Select",
                                        onClick = { activeAudioPickingType = "vo" }
                                    )
                                    if (voFile != null) {
                                        StudioSmallButton(
                                            text = "Clear",
                                            isDanger = true,
                                            onClick = {
                                                voFile = null
                                                saveProjectData()
                                            }
                                        )
                                    }
                                }
                            }

                            if (voFile != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                StudioSmallButton(
                                    text = "Match VO Duration to Scenes",
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val res = LinuxRuntimeManager.syncVoDuration(workspaceName)
                                            withContext(Dispatchers.Main) {
                                                res.onSuccess {
                                                    statusMessage = "Scene duration synchronized with VO audio"
                                                    loadProject()
                                                }.onFailure { err ->
                                                    errorMessage = err.message ?: "Sync VO failed"
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // BACKGROUND MUSIC (BGM)
                        StudioCard {
                            StudioSectionHeader(title = "Background Music (BGM)")
                            Text(
                                text = "Pilih musik latar dari folder audio/ atau pustaka aset.",
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TRACK BGM",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = bgmFile ?: "No BGM attached",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = if (bgmFile != null) StudioAccent else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (bgmFile != null) {
                                        val targetFile = LinuxRuntimeManager.getWorkspaceFile(workspaceName, "audio/$bgmFile")
                                        val isPlaying = playingAudioPath == targetFile.absolutePath
                                        StudioSmallButton(
                                            text = if (isPlaying) "Stop" else "Play",
                                            onClick = { playAudio(targetFile) }
                                        )
                                    }
                                    StudioSmallButton(
                                        text = "Select",
                                        onClick = { activeAudioPickingType = "bgm" }
                                    )
                                    if (bgmFile != null) {
                                        StudioSmallButton(
                                            text = "Clear",
                                            isDanger = true,
                                            onClick = {
                                                bgmFile = null
                                                saveProjectData()
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Volume presets
                            Text(
                                text = "VOLUME BGM: ${Math.round(bgmVolume * 100)}%",
                                fontFamily = FontFamily.Default,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0.10f to "10%", 0.18f to "18%", 0.25f to "25%", 0.35f to "35%").forEach { (vol, label) ->
                                    val isSelected = Math.abs(bgmVolume - vol) < 0.02f
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) StudioPrimary else StudioSurfaceElevated)
                                            .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                bgmVolume = vol
                                                saveProjectData()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) StudioBg else TextPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Auto-Ducking toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Auto-Ducking",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Turunkan volume BGM secara otomatis saat ada suara VO.",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = autoDuck,
                                    onCheckedChange = {
                                        autoDuck = it
                                        saveProjectData()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = StudioPrimary,
                                        checkedTrackColor = StudioAccent,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = StudioSurfaceElevated
                                    )
                                )
                            }
                        }
                    }
                }

                3 -> {
                    // TAB 4: RENDER
                    val visualsCount = scenesList.count { it.visual.isNotBlank() }
                    val totalScenes = scenesList.size
                    val totalDuration = scenesList.sumOf { it.duration.toDouble() }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // READINESS CARD
                        StudioCard {
                            StudioSectionHeader(title = "Kesiapan Produksi")
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = "Total Durasi", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", totalDuration)}s",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                Column {
                                    Text(text = "Adegan", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = "$totalScenes Adegan",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                Column {
                                    Text(text = "Visual Terpasang", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = "$visualsCount / $totalScenes",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (visualsCount == totalScenes && totalScenes > 0) StudioBadgeGreenText else TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = "Track VO", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = if (voFile != null) "Siap" else "Kosong",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (voFile != null) StudioBadgeGreenText else TextMuted
                                    )
                                }
                                Column {
                                    Text(text = "Track BGM", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = if (bgmFile != null) "Aktif (${Math.round(bgmVolume * 100)}%)" else "Tidak Aktif",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (bgmFile != null) StudioAccent else TextMuted
                                    )
                                }
                                Column {
                                    Text(text = "Rasio Target", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = aspectRatio,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = StudioAccent
                                    )
                                }
                            }
                        }

                        // RENDER OPTIONS
                        StudioCard {
                            StudioSectionHeader(title = "Opsi Render Video")
                            Spacer(modifier = Modifier.height(6.dp))

                            // Burn-in Subtitles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Burn-in Subtitles",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Tempel subtitle otomatis dari teks narasi ke video.",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = burnSubtitles,
                                    onCheckedChange = { burnSubtitles = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = StudioPrimary,
                                        checkedTrackColor = StudioAccent,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = StudioSurfaceElevated
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Gentle Motion
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Gentle Motion (Images)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Efek slow zoom halus pada visual gambar statis.",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = gentleMotion,
                                    onCheckedChange = { gentleMotion = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = StudioPrimary,
                                        checkedTrackColor = StudioAccent,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = StudioSurfaceElevated
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Resolution control
                            Text(
                                text = "RESOLUSI OUTPUT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            StudioSegmentedControl(
                                options = resolutionOptions,
                                selectedIndex = resolutionIndex,
                                onSelect = { resolutionIndex = it }
                            )
                        }

                        StudioSecondaryButton(
                            text = "Promote to DNA",
                            enabled = scenesList.isNotEmpty(),
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val res = LinuxRuntimeManager.promoteWorkspaceDna(workspaceName)
                                    withContext(Dispatchers.Main) {
                                        if (res.optString("status") == "ok") {
                                            val trait = res.optString("learned_trait", "")
                                            statusMessage = "Promoted to Channel DNA: $trait"
                                        } else {
                                            errorMessage = res.optString("error", "Failed to promote to DNA")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // PROGRESS BAR / ACTION BUTTON
                        if (isRendering) {
                            StudioCard {
                                StudioSectionHeader(title = "Proses Render Berjalan")
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { renderProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = StudioAccent,
                                    trackColor = StudioSurfaceElevated
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = renderStep,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 12.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$renderProgress%",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StudioAccent
                                    )
                                }
                            }
                        } else {
                            StudioPrimaryButton(
                                text = "Render Video",
                                enabled = scenesList.isNotEmpty(),
                                onClick = {
                                    isRendering = true
                                    renderProgress = 5
                                    renderStep = "Mengirim tugas render ke FFmpeg..."
                                    renderError = null
                                    val resStr = if (resolutionIndex == 1) "1080p" else "720p"
                                    scope.launch(Dispatchers.IO) {
                                        val res = LinuxRuntimeManager.startRender(
                                            workspace = workspaceName,
                                            burnSubtitles = burnSubtitles,
                                            motion = gentleMotion,
                                            resolution = resStr
                                        )
                                        withContext(Dispatchers.Main) {
                                            res.onFailure { err ->
                                                isRendering = false
                                                errorMessage = err.message ?: "Failed to start render"
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        // RESULT CARD
                        if (!outputFile.isNullOrBlank()) {
                            val targetFile = LinuxRuntimeManager.getWorkspaceFile(workspaceName, outputFile!!)
                            if (targetFile.exists()) {
                                val sizeMb = String.format(Locale.US, "%.1f MB", targetFile.length() / (1024.0 * 1024.0))
                                StudioCard {
                                    StudioSectionHeader(title = "Hasil Render Terkini")
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = targetFile.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "$sizeMb • $aspectRatio • ${targetFile.parentFile?.name}/${targetFile.name}",
                                        fontFamily = FontFamily.Default,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        StudioSecondaryButton(
                                            text = "Play",
                                            onClick = {
                                                try {
                                                    val uri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        targetFile
                                                    )
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "video/mp4")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    errorMessage = "Cannot open player: ${e.message}"
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        StudioPrimaryButton(
                                            text = "Export",
                                            onClick = {
                                                val expRes = LinuxRuntimeManager.exportFileToDownloads(context, targetFile)
                                                expRes.onSuccess { path ->
                                                    statusMessage = "Exported: $path"
                                                }.onFailure { err ->
                                                    errorMessage = "Export failed: ${err.message}"
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
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

    // ── MODAL: RAW FILES MANAGER ─────────────────────────────────────────────
    if (showFilesModal) {
        AlertDialog(
            onDismissRequest = { showFilesModal = false },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = "Workspace Files: $workspaceName",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (rawFiles.isEmpty()) {
                        Text(
                            text = "Tidak ada file di folder proyek.",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        rawFiles.forEach { fileItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileItem.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = fileItem.relPath,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                val f = LinuxRuntimeManager.getWorkspaceFile(workspaceName, fileItem.relPath)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StudioSmallButton(
                                        text = "Export",
                                        onClick = {
                                            val exp = LinuxRuntimeManager.exportFileToDownloads(context, f)
                                            exp.onSuccess { statusMessage = "Exported: $it" }
                                                .onFailure { errorMessage = "Export failed: ${it.message}" }
                                        }
                                    )
                                    StudioSmallButton(
                                        text = "Delete",
                                        isDanger = true,
                                        onClick = {
                                            f.delete()
                                            rawFiles.remove(fileItem)
                                            statusMessage = "File deleted"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                StudioSecondaryButton(
                    text = "Close",
                    onClick = { showFilesModal = false },
                    height = 36.dp
                )
            }
        )
    }

    // ── MODAL: LIBRARY ASSET PICKER FOR SCENE ─────────────────────────────────
    if (showLibraryPickerForScene != null) {
        val targetSceneIdx = showLibraryPickerForScene!!
        AlertDialog(
            onDismissRequest = { showLibraryPickerForScene = null },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = "Select Asset from Global Library",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (libraryAssets.isEmpty()) {
                        Text(
                            text = "Pustaka aset kosong. Impor aset di tab Library terlebih dahulu.",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        libraryAssets.forEach { (cat, assetName) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            val res = LinuxRuntimeManager.copyLibraryAssetToWorkspace(cat, assetName, workspaceName)
                                            withContext(Dispatchers.Main) {
                                                if (res.optString("status") == "ok") {
                                                    val finalCopiedName = res.optString("copied", res.optString("filename", assetName))
                                                    val rel = "assets/$finalCopiedName"
                                                    if (targetSceneIdx in scenesList.indices) {
                                                        scenesList[targetSceneIdx] = scenesList[targetSceneIdx].copy(visual = rel)
                                                        saveProjectData()
                                                        statusMessage = "Attached $finalCopiedName to Scene ${targetSceneIdx + 1}"
                                                    }
                                                }
                                                showLibraryPickerForScene = null
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = assetName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                StudioBadge(text = cat.uppercase())
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                StudioSecondaryButton(
                    text = "Cancel",
                    onClick = { showLibraryPickerForScene = null },
                    height = 36.dp
                )
            }
        )
    }

    // ── MODAL: AUDIO TRACK SELECTOR (VO or BGM) ───────────────────────────────
    if (activeAudioPickingType != null) {
        val pickingType = activeAudioPickingType!!
        AlertDialog(
            onDismissRequest = { activeAudioPickingType = null },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = if (pickingType == "vo") "Pilih File Voiceover" else "Pilih File BGM",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (projectAudioFiles.isEmpty()) {
                        Text(
                            text = "Tidak ada file audio di folder audio/ proyek. Rekam VO atau impor lagu terlebih dahulu.",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        projectAudioFiles.forEach { audioFileName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (pickingType == "vo") {
                                            voFile = audioFileName
                                        } else {
                                            bgmFile = audioFileName
                                        }
                                        saveProjectData()
                                        activeAudioPickingType = null
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = audioFileName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                StudioSecondaryButton(
                    text = "Cancel",
                    onClick = { activeAudioPickingType = null },
                    height = 36.dp
                )
            }
        )
    }

    // DIALOG: APPLY PATTERN
    if (showApplyPatternDialog) {
        AlertDialog(
            onDismissRequest = { showApplyPatternDialog = false },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(8.dp),
            title = {
                Text(
                    text = "Apply Living Pattern",
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
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availablePatterns.isEmpty()) {
                        Text(
                            text = "No patterns available",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    } else {
                        availablePatterns.forEach { pat ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StudioSurfaceElevated)
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        showApplyPatternDialog = false
                                        scope.launch(Dispatchers.IO) {
                                            val res = LinuxRuntimeManager.applyPattern(workspaceName, pat.id)
                                            withContext(Dispatchers.Main) {
                                                res.onSuccess {
                                                    statusMessage = "Pattern '${pat.name}' applied"
                                                    loadProject()
                                                }.onFailure { err ->
                                                    errorMessage = err.message ?: "Failed to apply pattern"
                                                }
                                            }
                                        }
                                    }
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pat.name,
                                            fontFamily = FontFamily.Default,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        StudioBadge(text = pat.aspectRatio)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${pat.beatsCount} Beats • ${pat.totalDuration}s",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = pat.beats.joinToString(" → ") { "${it.role} (${it.duration}s)" },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                StudioSmallButton(
                    text = "Close",
                    onClick = { showApplyPatternDialog = false }
                )
            }
        )
    }

    // DIALOG: SAVE AS PATTERN
    if (showSavePatternDialog) {
        AlertDialog(
            onDismissRequest = { showSavePatternDialog = false },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(8.dp),
            title = {
                Text(
                    text = "Save as Pattern",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Extract rhythm and beats from this project into a reusable living pattern (narration and visuals will be stripped).",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    StudioTextField(
                        value = newPatternNameInput,
                        onValueChange = { newPatternNameInput = it },
                        placeholder = "Pattern Name",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                StudioPrimaryButton(
                    text = "Save",
                    height = 36.dp,
                    onClick = {
                        val name = newPatternNameInput.trim()
                        if (name.isBlank()) return@StudioPrimaryButton
                        showSavePatternDialog = false
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.saveWorkspaceAsPattern(workspaceName, name)
                            withContext(Dispatchers.Main) {
                                if (res.optString("status") == "ok") {
                                    statusMessage = "Pattern '$name' saved successfully"
                                } else {
                                    errorMessage = res.optString("error", "Failed to save pattern")
                                }
                            }
                        }
                    }
                )
            },
            dismissButton = {
                StudioSmallButton(
                    text = "Cancel",
                    onClick = { showSavePatternDialog = false }
                )
            }
        )
    }
}
