package com.contentstudio.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import com.contentstudio.app.ui.theme.ProgressBarError
import com.contentstudio.app.ui.theme.ProgressBarTrack
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioRecordingItem(
    val name: String,
    val file: File,
    val size: Long,
    val lastModified: Long,
)

data class TtsVoiceOption(
    val label: String,
    val voiceId: String,
)

@Composable
fun VoiceoverScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val recordings = remember { mutableStateListOf<AudioRecordingItem>() }

    // Mode Switcher: 0 = TTS AI, 1 = REKAM MIC
    var activeModeIndex by remember { mutableIntStateOf(0) }

    // State TTS AI
    val ttsVoices = remember {
        listOf(
            TtsVoiceOption("Brian", "en-US-BrianMultilingualNeural"),
            TtsVoiceOption("Andrew", "en-US-AndrewMultilingualNeural"),
            TtsVoiceOption("Remy", "fr-FR-RemyMultilingualNeural"),
            TtsVoiceOption("Emma", "en-US-EmmaMultilingualNeural"),
            TtsVoiceOption("Ava", "en-US-AvaMultilingualNeural")
        )
    }
    var selectedVoiceIndex by remember { mutableIntStateOf(0) }
    val rates = remember { listOf("-10%", "+0%", "+10%") }
    var selectedRateIndex by remember { mutableIntStateOf(1) }
    val pitches = remember { listOf("-5Hz", "+0Hz", "+5Hz") }
    var selectedPitchIndex by remember { mutableIntStateOf(1) }
    var ttsScriptText by remember { mutableStateOf("") }
    var isGeneratingTts by remember { mutableStateOf(false) }

    // State REKAM MIC & TELEPROMPTER
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var currentRecordFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentAmplitude by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // Teleprompter state
    var teleprompterText by remember { mutableStateOf("") }
    var promptFontSizeSp by remember { mutableIntStateOf(16) }
    var isAutoScroll by remember { mutableStateOf(false) }
    val autoScrollSpeeds = remember { listOf(0.5f, 1.0f, 1.5f) }
    var selectedSpeedIndex by remember { mutableIntStateOf(1) }
    val teleprompterScrollState = rememberScrollState()

    // Dialog Muat Naskah
    var showScriptPicker by remember { mutableStateOf(false) }
    var scriptPickerTarget by remember { mutableStateOf("tts") }
    val availableScripts = remember { mutableStateListOf<File>() }

    // Audio Playback & Actions
    var playingFile by remember { mutableStateOf<File?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isMastering by remember { mutableStateOf(false) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshRecordings() {
        if (activeWorkspace.isBlank()) {
            recordings.clear()
            return
        }
        scope.launch(Dispatchers.IO) {
            val audioDir = LinuxRuntimeManager.getWorkspaceAudioDir(context, activeWorkspace)
            val files = audioDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".m4a") || f.name.endsWith(".aac") || f.name.endsWith(".mp3") || f.name.endsWith(".wav"))
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            val items = files.map { f ->
                AudioRecordingItem(
                    name = f.name,
                    file = f,
                    size = f.length(),
                    lastModified = f.lastModified()
                )
            }
            withContext(Dispatchers.Main) {
                recordings.clear()
                recordings.addAll(items)
            }
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        playingFile = null
    }

    fun playAudio(file: File) {
        if (playingFile == file) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopPlayback()
                }
            }
            mediaPlayer = player
            playingFile = file
        } catch (e: Exception) {
            errorMessage = "Gagal memutar audio: ${e.message}"
            stopPlayback()
        }
    }

    fun stopRecordingInternal() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        currentAmplitude = 0
        val saved = currentRecordFile
        currentRecordFile = null
        if (saved != null && saved.exists() && saved.length() > 0) {
            statusMessage = "Rekaman tersimpan: ${saved.name}"
            refreshRecordings()
        }
    }

    fun startRecordingActual() {
        if (activeWorkspace.isBlank()) {
            errorMessage = "Pilih workspace aktif terlebih dahulu di tab PROYEK"
            return
        }
        stopPlayback()
        errorMessage = null
        statusMessage = null

        val audioDir = LinuxRuntimeManager.getWorkspaceAudioDir(context, activeWorkspace)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(audioDir, "vo_$timeStamp.m4a")
        currentRecordFile = outFile

        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            recordingSeconds = 0
            isRecording = true
            statusMessage = "Merekam suara..."
        } catch (e: Exception) {
            errorMessage = "Gagal memulai perekam: ${e.message}"
            isRecording = false
            recorder = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecordingActual()
        } else {
            errorMessage = "Izin mikrofon (RECORD_AUDIO) ditolak pengguna"
        }
    }

    fun startCountdownAndRecord() {
        if (activeWorkspace.isBlank()) {
            errorMessage = "Pilih proyek di tab PROYEK sebelum merekam"
            return
        }
        stopPlayback()
        countdownJob?.cancel()
        countdown = 3
        countdownJob = scope.launch {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            withContext(Dispatchers.Main) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    startRecordingActual()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdown = 0
        statusMessage = "Perekaman dibatalkan"
    }

    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || activeWorkspace.isBlank()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.importAudioToWorkspace(context, activeWorkspace, uri)
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val imported = res.getOrThrow()
                    statusMessage = "Audio berhasil diimpor: ${imported.name}"
                    refreshRecordings()
                } else {
                    errorMessage = "Gagal impor audio: ${res.exceptionOrNull()?.message}"
                }
            }
        }
    }

    fun openScriptPicker(target: String) {
        if (activeWorkspace.isBlank()) {
            errorMessage = "Pilih proyek terlebih dahulu di tab PROYEK"
            return
        }
        scriptPickerTarget = target
        scope.launch(Dispatchers.IO) {
            val scriptsDir = LinuxRuntimeManager.getWorkspaceScriptsDir(context, activeWorkspace)
            val list = scriptsDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".txt") || f.name.endsWith(".md") || f.name.endsWith(".srt") || f.name.endsWith(".json") || f.name.endsWith(".py") || f.name.endsWith(".lrc"))
            }?.sortedBy { it.name } ?: emptyList()

            withContext(Dispatchers.Main) {
                availableScripts.clear()
                availableScripts.addAll(list)
                showScriptPicker = true
            }
        }
    }

    fun loadSelectedScript(file: File) {
        scope.launch(Dispatchers.IO) {
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            withContext(Dispatchers.Main) {
                if (scriptPickerTarget == "tts") {
                    ttsScriptText = content
                } else {
                    teleprompterText = content
                }
                statusMessage = "Naskah ${file.name} dimuat"
                showScriptPicker = false
            }
        }
    }

    // Keep screen on during recording or countdown
    DisposableEffect(isRecording, countdown) {
        view.keepScreenOn = isRecording || countdown > 0
        onDispose {
            view.keepScreenOn = false
        }
    }

    // VU meter polling & timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                try {
                    val amp = recorder?.maxAmplitude ?: 0
                    currentAmplitude = amp
                } catch (_: Exception) {}
                delay(100)
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Teleprompter Auto-Scroll Effect
    LaunchedEffect(isAutoScroll, isRecording, selectedSpeedIndex) {
        if (isAutoScroll && (isRecording || activeModeIndex == 1)) {
            val speedFactor = autoScrollSpeeds[selectedSpeedIndex]
            val scrollDelta = (2.5f * speedFactor)
            while (isActive && isAutoScroll) {
                teleprompterScrollState.scrollBy(scrollDelta)
                delay(40)
            }
        }
    }

    LaunchedEffect(activeWorkspace) {
        refreshRecordings()
    }

    // Wajib release player & recorder saat berpindah tab
    DisposableEffect(Unit) {
        onDispose {
            stopPlayback()
            countdownJob?.cancel()
            if (isRecording) {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
            }
        }
    }

    val formattedTime = String.format(
        Locale.US,
        "%02d:%02d",
        recordingSeconds / 60,
        recordingSeconds % 60
    )

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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AUDIO STUDIO",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (activeWorkspace.isNotBlank()) BadgeGreenBg else SurfaceCard)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (activeWorkspace.isNotBlank()) "[WS: $activeWorkspace]" else "[PILIH WS]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeWorkspace.isNotBlank()) BadgeGreenText else TextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { refreshRecordings() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "SEGAR",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }

        // PERINGATAN JIKA BELUM ADA PROYEK
        if (activeWorkspace.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Buat atau pilih proyek dulu di tab [PROYEK]",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // SWITCHER FLAT 2 MODE: [TTS AI] | [REKAM MIC]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (activeModeIndex == 0) ActionSolidBg else CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { activeModeIndex = 0 }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[TTS AI]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activeModeIndex == 0) ActionSolidText else TextPrimary
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (activeModeIndex == 1) ActionSolidBg else CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { activeModeIndex = 1 }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[REKAM MIC]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activeModeIndex == 1) ActionSolidText else TextPrimary
                )
            }
        }

        // ERROR & STATUS BANNERS
        if (!errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ErrorCardBg)
                    .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = errorMessage ?: "",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = ErrorCardText
                )
            }
        }
        if (!statusMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = statusMessage ?: "",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // MODE 0: TTS AI MULTILINGUAL
            if (activeModeIndex == 0) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "PILIHAN SUARA (MULTILINGUAL NEURAL)",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )

                        // Pills Suara (Brian, Andrew, Remy, Emma, Ava)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ttsVoices.forEachIndexed { idx, opt ->
                                val isSelected = idx == selectedVoiceIndex
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) ActionSolidBg else CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { selectedVoiceIndex = idx }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = opt.label,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) ActionSolidText else TextPrimary
                                    )
                                }
                            }
                        }

                        // Kontrol Cepat Rate & Pitch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "KECEPATAN (RATE)",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rates.forEachIndexed { idx, r ->
                                        val isSel = idx == selectedRateIndex
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) ActionSolidBg else CardHighlight)
                                                .clickable { selectedRateIndex = idx }
                                                .padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = r,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) ActionSolidText else TextPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "NADA (PITCH)",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    pitches.forEachIndexed { idx, p ->
                                        val isSel = idx == selectedPitchIndex
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) ActionSolidBg else CardHighlight)
                                                .clickable { selectedPitchIndex = idx }
                                                .padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = p,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) ActionSolidText else TextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // NASKAH & TOMBOL MUAT
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TEKS NASKAH",
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { openScriptPicker("tts") }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "[MUAT NASKAH]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(InputContainerBg)
                                .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                                .padding(10.dp)
                        ) {
                            if (ttsScriptText.isEmpty()) {
                                Text(
                                    text = "Ketik atau muat naskah yang ingin disuarakan oleh AI di sini...",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                            BasicTextField(
                                value = ttsScriptText,
                                onValueChange = { ttsScriptText = it },
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Default,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = TextPrimary
                                ),
                                cursorBrush = SolidColor(TextPrimary),
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        val canGenerate = activeWorkspace.isNotBlank() && ttsScriptText.isNotBlank() && !isGeneratingTts
                        Button(
                            onClick = {
                                isGeneratingTts = true
                                statusMessage = "Menghasilkan TTS Neural..."
                                errorMessage = null
                                scope.launch(Dispatchers.IO) {
                                    val voice = ttsVoices[selectedVoiceIndex].voiceId
                                    val rate = rates[selectedRateIndex]
                                    val pitch = pitches[selectedPitchIndex]
                                    val res = LinuxRuntimeManager.generateTts(
                                        workspace = activeWorkspace,
                                        text = ttsScriptText,
                                        voice = voice,
                                        rate = rate,
                                        pitch = pitch
                                    )
                                    withContext(Dispatchers.Main) {
                                        isGeneratingTts = false
                                        if (res.optString("status") == "ok") {
                                            val f = res.optJSONObject("file")
                                            val fileName = f?.optString("name", "tts.mp3") ?: "tts.mp3"
                                            statusMessage = "TTS berhasil dibuat: $fileName"
                                            refreshRecordings()
                                        } else {
                                            errorMessage = "Gagal membuat TTS: ${res.optString("error", "Error")}"
                                        }
                                    }
                                }
                            },
                            enabled = canGenerate,
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActionSolidBg,
                                contentColor = ActionSolidText,
                                disabledContainerColor = CardHighlight,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(
                                text = if (isGeneratingTts) "MEMPROSES TTS..." else "GENERATE TTS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // MODE 1: REKAM MIC + TELEPROMPTER
            if (activeModeIndex == 1) {
                item {
                    // PANEL TELEPROMPTER
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
                                text = "TELEPROMPTER",
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable {
                                            if (promptFontSizeSp > 14) promptFontSizeSp -= 2
                                        }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "[A-]",
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
                                        .clickable {
                                            if (promptFontSizeSp < 24) promptFontSizeSp += 2
                                        }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "[A+]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isAutoScroll) ActionSolidBg else CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { isAutoScroll = !isAutoScroll }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = if (isAutoScroll) "[GULIR: ON]" else "[AUTO-SCROLL]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAutoScroll) ActionSolidText else TextPrimary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { openScriptPicker("teleprompter") }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "[MUAT]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }

                        // Pilihan Kecepatan Gulir
                        if (isAutoScroll) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Kecepatan Gulir:",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                autoScrollSpeeds.forEachIndexed { idx, spd ->
                                    val isSel = idx == selectedSpeedIndex
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) ActionSolidBg else CardHighlight)
                                            .clickable { selectedSpeedIndex = idx }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${spd}x",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSel) ActionSolidText else TextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Kotak Baca Teleprompter (TINGGI TETAP 200.dp, bebas crash nested scroll)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(InputContainerBg)
                                .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                                .padding(12.dp)
                        ) {
                            if (teleprompterText.isEmpty()) {
                                Text(
                                    text = "Tulis naskah atau ketuk [MUAT] untuk membaca naskah saat rekaman...",
                                    fontFamily = FontFamily.Default,
                                    fontSize = promptFontSizeSp.sp,
                                    lineHeight = (promptFontSizeSp * 1.5).sp,
                                    color = TextMuted
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(teleprompterScrollState)
                            ) {
                                BasicTextField(
                                    value = teleprompterText,
                                    onValueChange = { teleprompterText = it },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Default,
                                        fontSize = promptFontSizeSp.sp,
                                        lineHeight = (promptFontSizeSp * 1.5).sp,
                                        color = TextPrimary
                                    ),
                                    cursorBrush = SolidColor(TextPrimary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                item {
                    // KOTAK KONTROL PEREKAM & VU METER
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .border(
                                1.dp,
                                if (isRecording) ProgressBarError else BorderSubtle,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = when {
                                    countdown > 0 -> "MEMULAI DALAM $countdown..."
                                    isRecording -> "SEDANG MEREKAM"
                                    else -> "SIAP MEREKAM"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording || countdown > 0) ProgressBarError else TextSecondary,
                                letterSpacing = 2.sp
                            )

                            Text(
                                text = if (countdown > 0) "$countdown" else formattedTime,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            // VU METER REAL-TIME
                            if (isRecording) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val fraction = (currentAmplitude / 32767f).coerceIn(0.02f, 1f)
                                    val isPeak = currentAmplitude > 28000
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(ProgressBarTrack)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(if (isPeak) ProgressBarError else BadgeGreenText)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = "MIC LEVEL", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                                        Text(text = "$currentAmplitude", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = if (isPeak) ProgressBarError else TextMuted)
                                    }
                                }
                            }

                            // TOMBOL AKSI REKAM
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (countdown > 0) {
                                    Button(
                                        onClick = { cancelCountdown() },
                                        shape = RoundedCornerShape(4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CardHighlight,
                                            contentColor = TextPrimary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = "BATAL",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    val canRecord = activeWorkspace.isNotBlank()
                                    Button(
                                        onClick = {
                                            if (isRecording) {
                                                stopRecordingInternal()
                                            } else {
                                                startCountdownAndRecord()
                                            }
                                        },
                                        enabled = canRecord,
                                        shape = RoundedCornerShape(4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRecording) ProgressBarError else ActionSolidBg,
                                            contentColor = if (isRecording) TextPrimary else ActionSolidText,
                                            disabledContainerColor = CardHighlight,
                                            disabledContentColor = TextMuted
                                        ),
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = if (isRecording) "STOP REKAM" else "MULAI REKAM",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                val canImport = activeWorkspace.isNotBlank() && !isRecording && countdown == 0
                                Button(
                                    onClick = { audioImportLauncher.launch("audio/*") },
                                    enabled = canImport,
                                    shape = RoundedCornerShape(4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CardHighlight,
                                        contentColor = TextPrimary,
                                        disabledContainerColor = CardHighlight,
                                        disabledContentColor = TextMuted
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        text = "IMPORT AUDIO",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // DAFTAR AUDIO (TTS & REKAMAN)
            item {
                Text(
                    text = "DAFTAR AUDIO (${recordings.size})",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            if (recordings.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (activeWorkspace.isBlank()) {
                                "Pilih proyek di tab [PROYEK] untuk melihat file audio."
                            } else {
                                "Belum ada file audio di folder audio/ proyek ini."
                            },
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                items(recordings) { item ->
                    val isPlaying = playingFile == item.file
                    val sizeKb = (item.size / 1024.0)
                    val sizeStr = if (sizeKb >= 1024) String.format(Locale.US, "%.1f MB", sizeKb / 1024.0) else String.format(Locale.US, "%.0f KB", sizeKb)
                    val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(item.lastModified))
                    val isMastered = item.name.endsWith("_master.m4a")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPlaying) CardHighlight else SurfaceCard)
                            .border(1.dp, if (isPlaying) TextPrimary else BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                if (isMastered) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BadgeGreenBg)
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "MASTER",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BadgeGreenText
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$sizeStr • $dateStr",
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPlaying) ActionSolidBg else CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { playAudio(item.file) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (isPlaying) "STOP" else "PUTAR",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPlaying) ActionSolidText else TextPrimary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable {
                                        if (!isMastering) {
                                            isMastering = true
                                            statusMessage = "Mastering audio dengan FFmpeg..."
                                            scope.launch(Dispatchers.IO) {
                                                val res = LinuxRuntimeManager.masterAudio(activeWorkspace, item.name)
                                                withContext(Dispatchers.Main) {
                                                    isMastering = false
                                                    if (res.optString("status") == "ok") {
                                                        val f = res.optJSONObject("file")
                                                        val masterName = f?.optString("name", "") ?: ""
                                                        statusMessage = "Mastering selesai: $masterName"
                                                        refreshRecordings()
                                                    } else {
                                                        errorMessage = "Gagal mastering: ${res.optString("error", "Error")}"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "MASTER",
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
                                    .clickable {
                                        if (playingFile == item.file) {
                                            stopPlayback()
                                        }
                                        val name = item.name
                                        item.file.delete()
                                        statusMessage = "File $name telah dihapus"
                                        refreshRecordings()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "HAPUS",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorCardText
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // DIALOG MUAT NASKAH
    if (showScriptPicker) {
        AlertDialog(
            onDismissRequest = { showScriptPicker = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "PILIH NASKAH",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                if (availableScripts.isEmpty()) {
                    Text(
                        text = "Tidak ada file naskah di folder scripts/ workspace.",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableScripts.forEach { scriptFile ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { loadSelectedScript(scriptFile) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = scriptFile.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = { showScriptPicker = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "BATAL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
}
