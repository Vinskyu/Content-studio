package com.contentstudio.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
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
import com.contentstudio.app.ui.theme.ProgressBarError
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

@Composable
fun VoiceoverScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val recordings = remember { mutableStateListOf<AudioRecordingItem>() }

    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var currentRecordFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

    var playingFile by remember { mutableStateOf<File?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshRecordings() {
        if (activeWorkspace.isBlank()) {
            recordings.clear()
            return
        }
        scope.launch(Dispatchers.IO) {
            val audioDir = LinuxRuntimeManager.getWorkspaceAudioDir(context, activeWorkspace)
            val files = audioDir.listFiles { f -> f.isFile && (f.name.endsWith(".m4a") || f.name.endsWith(".aac") || f.name.endsWith(".mp3") || f.name.endsWith(".wav")) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

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
        val saved = currentRecordFile
        currentRecordFile = null
        if (saved != null && saved.exists() && saved.length() > 0) {
            statusMessage = "Rekaman tersimpan: ${saved.name}"
            refreshRecordings()
        }
    }

    fun startRecordingInternal() {
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
            startRecordingInternal()
        } else {
            errorMessage = "Izin mikrofon (RECORD_AUDIO) ditolak pengguna"
        }
    }

    fun onRecordToggle() {
        if (isRecording) {
            stopRecordingInternal()
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startRecordingInternal()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Timer effect
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    LaunchedEffect(activeWorkspace) {
        refreshRecordings()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPlayback()
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
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PEREKAM SUARA (VO)",
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
                        text = if (activeWorkspace.isNotBlank()) "[WS: $activeWorkspace]" else "[TANPA WS]",
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

        // KOTAK KONTROL PEREKAM (MATTE DARK CARD)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceCard)
                .border(1.dp, if (isRecording) ProgressBarError else BorderSubtle, RoundedCornerShape(6.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isRecording) "SEDANG MEREKAM" else "SIAP MEREKAM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRecording) ProgressBarError else TextSecondary,
                    letterSpacing = 2.sp
                )

                Text(
                    text = formattedTime,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onRecordToggle() },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) ProgressBarError else ActionSolidBg,
                            contentColor = if (isRecording) TextPrimary else ActionSolidText
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isRecording) "STOP REKAM" else "MULAI REKAM",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Output otomatis disimpan di folder audio/ workspace aktif",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        // ERROR BANNER
        if (!errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
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
            Spacer(modifier = Modifier.height(12.dp))
        }

        // STATUS BANNER
        if (!statusMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
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
            Spacer(modifier = Modifier.height(12.dp))
        }

        // DAFTAR REKAMAN
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "DAFTAR REKAMAN (${recordings.size})",
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (recordings.isEmpty()) {
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
                            "Pilih workspace di tab PROYEK untuk melihat rekaman audio."
                        } else {
                            "Belum ada rekaman di proyek ini. Tekan [MULAI REKAM] di atas."
                        },
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings) { item ->
                        val isPlaying = playingFile == item.file
                        val sizeKb = (item.size / 1024.0)
                        val sizeStr = if (sizeKb >= 1024) String.format(Locale.US, "%.1f MB", sizeKb / 1024.0) else String.format(Locale.US, "%.0f KB", sizeKb)
                        val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(item.lastModified))

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
                                Text(
                                    text = item.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$sizeStr • $dateStr",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isPlaying) ActionSolidBg else CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { playAudio(item.file) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
                                            if (playingFile == item.file) {
                                                stopPlayback()
                                            }
                                            item.file.delete()
                                            refreshRecordings()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "HAPUS",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
