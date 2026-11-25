package com.contentstudio.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.contentstudio.app.service.ActionCardItem
import com.contentstudio.app.service.AgentService
import com.contentstudio.app.service.AgentStepItem
import com.contentstudio.app.service.MessageBubble
import com.contentstudio.app.ui.theme.*
import com.contentstudio.app.data.PythonHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun AgentScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages by AgentService.messages.collectAsState()
    val isBusy by AgentService.isBusy.collectAsState()
    val activeWorkspace by AgentService.currentWorkspace.collectAsState()
    val apiStatus by AgentService.apiStatus.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val attachedFiles = remember { mutableStateListOf<String>() }

    // VO Recorder state
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var activeVoPath by remember { mutableStateOf<String?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voError by remember { mutableStateOf<String?>(null) }

    fun startVoRecording() {
        try {
            voError = null
            val sdVoDir = File("/sdcard/ContentStudio/vo")
            val voDir = if (sdVoDir.parentFile?.canWrite() == true) {
                sdVoDir
            } else {
                File(context.filesDir, "ContentStudio/.cache/vo")
            }
            if (!voDir.exists()) voDir.mkdirs()

            val outFile = File(voDir, "vo_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            activeVoPath = outFile.absolutePath
            recordSeconds = 0
            isRecording = true
        } catch (e: Exception) {
            voError = "Gagal rekam VO: ${e.message ?: "Error mikrofon"}"
            isRecording = false
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
        }
    }

    fun stopVoRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
            val savedPath = activeVoPath
            activeVoPath = null
            if (savedPath != null && File(savedPath).exists() && File(savedPath).length() > 0L) {
                if (!attachedFiles.contains(savedPath)) {
                    attachedFiles.add(savedPath)
                }
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            startVoRecording()
        } else {
            voError = "Izin mikrofon (RECORD_AUDIO) ditolak."
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            recordSeconds += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {}
            }
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val localPath = copyUriToInbox(context, uri)
            if (localPath != null && !attachedFiles.contains(localPath)) {
                attachedFiles.add(localPath)
            }
        }
    }

    LaunchedEffect(Unit) {
        AgentService.refreshSessionState(context)
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
        // TOP BAR DATAR
        AgentTopBar(
            workspace = activeWorkspace,
            apiStatus = apiStatus,
            onWorkspaceChange = { ws ->
                coroutineScope.launch(Dispatchers.IO) {
                    PythonHost.switchWorkspace(context, ws)
                    AgentService.setWorkspace(ws)
                    AgentService.clearMessages()
                    AgentService.refreshSessionState(context)
                }
            },
            onResetChat = {
                coroutineScope.launch(Dispatchers.IO) {
                    PythonHost.runConsole(context, "/clear")
                    AgentService.clearMessages()
                }
            }
        )

        // THREAD PERCAKAPAN
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "CONTENTSTUDIO AUTONOMOUS AGENT",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Ketik instruksi bahasa bebas atau gunakan perintah instan (/help).",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        if (msg.isUser) {
                            UserMessageBubble(msg)
                        } else {
                            AgentResponseBubble(
                                message = msg,
                                onActionClick = { cmd ->
                                    AgentService.executeCommand(context, cmd)
                                },
                                onOpenFile = { path ->
                                    openVideoFile(context, path)
                                }
                            )
                        }
                    }
                }
            }
        }

        // COMPOSER INPUT + VO RECORDER
        AgentComposer(
            text = inputText,
            onTextChange = { inputText = it },
            attachedFiles = attachedFiles,
            onRemoveFile = { attachedFiles.remove(it) },
            onAddFileClick = {
                filePickerLauncher.launch(arrayOf("video/*", "audio/*", "image/*"))
            },
            isRecording = isRecording,
            recordSeconds = recordSeconds,
            voError = voError,
            onVoToggleClick = {
                if (isRecording) {
                    stopVoRecording()
                } else {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        startVoRecording()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            isBusy = isBusy,
            onSend = {
                if (inputText.isNotBlank() || attachedFiles.isNotEmpty()) {
                    val textToSend = inputText
                    val filesToSend = attachedFiles.toList()
                    inputText = ""
                    attachedFiles.clear()
                    AgentService.executeCommand(context, textToSend, filesToSend)
                }
            }
        )
    }
}

@Composable
fun AgentTopBar(
    workspace: String,
    apiStatus: String,
    onWorkspaceChange: (String) -> Unit,
    onResetChat: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var workspaces by remember { mutableStateOf(listOf("hok_insights", "lirik_vibes")) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val res = PythonHost.listWorkspaces(context)
                val wsArr = res.optJSONArray("workspaces")
                if (wsArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until wsArr.length()) {
                        val wName = wsArr.getJSONObject(i).optString("workspace")
                        if (wName.isNotBlank()) list.add(wName)
                    }
                    if (list.isNotEmpty()) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            workspaces = list
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "CONTENT STUDIO",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextPrimary
            )
            Box {
                Row(
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "WS: $workspace",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "[v]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(SurfaceCard)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                ) {
                    workspaces.forEach { ws ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = ws,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (ws == workspace) TextPrimary else TextSecondary
                                )
                            },
                            onClick = {
                                expanded = false
                                onWorkspaceChange(ws)
                            }
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BgDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { onResetChat() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "[BARU]",
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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = apiStatus,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (apiStatus.contains("READY") || apiStatus.contains("GROQ") || apiStatus.contains("GEMINI") || apiStatus.contains("OPENROUTER")) TextPrimary else TextSecondary
                )
            }
        }
    }
}

@Composable
fun UserMessageBubble(message: MessageBubble) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        if (message.attachedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.attachedFiles.forEach { file ->
                    val name = File(file).name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardHighlight)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "[FILE] $name",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CardHighlight)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = TextPrimary
            )
        }
    }
}

@Composable
fun AgentResponseBubble(
    message: MessageBubble,
    onActionClick: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // COLLAPSIBLE TOOL STEPS
        if (message.steps.isNotEmpty()) {
            CollapsibleToolSteps(steps = message.steps)
        }

        // INLINE PROGRESS BAR
        if (message.isRunning || message.currentProgress in 1..99) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = message.currentStage.ifBlank { "Sedang memproses..." },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "${message.currentProgress}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
                LinearProgressIndicator(
                    progress = { message.currentProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = ActionSolidBg,
                    trackColor = BorderSubtle,
                )
            }
        }

        // TEXT REPLY
        if (message.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = TextPrimary
                )
            }
        }

        // ACTION CARDS
        message.actionCards.forEach { card ->
            ActionCardView(card = card, onActionClick = onActionClick, onOpenFile = onOpenFile)
        }
    }
}

@Composable
fun CollapsibleToolSteps(steps: List<AgentStepItem>) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[AGENT STEPS] ${steps.size} LANGKAH KERJA",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = if (expanded) "[-] TUTUP" else "[+] LIHAT",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEach { step ->
                    val statusTag = if (step.status == "done") "[OK]" else if (step.status == "running") "[..]" else "[ERR]"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusTag,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (step.status == "done") TextPrimary else TextSecondary
                        )
                        Text(
                            text = "${step.tool}: ${step.detail}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActionCardView(
    card: ActionCardItem,
    onActionClick: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CardHighlight)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[${card.type.uppercase()}] ${card.title}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        if (card.hook.isNotBlank() && card.hook != "-") {
            Text(
                text = "Hook: \"${card.hook}\"",
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        if (card.type == "contract") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onActionClick(card.primaryCmd) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    )
                ) {
                    Text(
                        text = "SETUJUI & RENDER 1080P",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { onActionClick("Tolong revisi durasi dan hook rencana video ini") },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
                ) {
                    Text(
                        text = "REVISI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        } else if (card.type == "video_ready") {
            Button(
                onClick = { onOpenFile(card.path) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                )
            ) {
                Text(
                    text = "BUKA VIDEO (1080P)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AgentComposer(
    text: String,
    onTextChange: (String) -> Unit,
    attachedFiles: List<String>,
    onRemoveFile: (String) -> Unit,
    onAddFileClick: () -> Unit,
    isRecording: Boolean,
    recordSeconds: Int,
    voError: String?,
    onVoToggleClick: () -> Unit,
    isBusy: Boolean,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isRecording) {
            val mins = recordSeconds / 60
            val secs = recordSeconds % 60
            val timerStr = String.format("%02d:%02d", mins, secs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REC $timerStr (Merekam VO...)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Tekan [STOP VO] untuk simpan",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        } else if (voError != null) {
            Text(
                text = "[!] $voError",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextPrimary
            )
        }

        if (attachedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachedFiles.forEach { filePath ->
                    val fname = File(filePath).name
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardHighlight)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = fname,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "[x]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            modifier = Modifier.clickable { onRemoveFile(filePath) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onAddFileClick,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardHighlight,
                    contentColor = TextPrimary
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "[+FILE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onVoToggleClick,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) ActionSolidBg else CardHighlight,
                    contentColor = if (isRecording) ActionSolidText else TextPrimary
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isRecording) "[STOP VO]" else "[VO]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Instruksi agen atau /help...",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.sp,
                        color = TextPrimary
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = onSend,
                enabled = !isBusy && !isRecording && (text.isNotBlank() || attachedFiles.isNotEmpty()),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText,
                    disabledContainerColor = CardHighlight,
                    disabledContentColor = TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
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

fun copyUriToInbox(context: Context, uri: Uri): String? {
    return try {
        val rootDir = File(context.filesDir, "ContentStudio/.cache/inbox")
        if (!rootDir.exists()) rootDir.mkdirs()

        val fileName = "input_${System.currentTimeMillis()}_" + (uri.lastPathSegment ?: "file")
        val targetFile = File(rootDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        targetFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

fun openVideoFile(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
