package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.LinuxRuntimeManager
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
import java.util.Locale

@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }
    val currentFiles = remember { mutableStateListOf<WorkspaceFileItem>() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newWorkspaceName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    var previewTitle by remember { mutableStateOf("") }
    var previewContent by remember { mutableStateOf("") }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var copiedPreview by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<WorkspaceFileItem?>(null) }
    var workspaceToDelete by remember { mutableStateOf<WorkspaceSummary?>(null) }

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

    fun refreshWorkspaces(autoSelectFirst: Boolean = false) {
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
        refreshWorkspaces(autoSelectFirst = true)
    }

    LaunchedEffect(activeWorkspace) {
        loadWorkspaceFiles(activeWorkspace)
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
                    text = "PROYEK STUDIO",
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
                        text = "${workspaces.size} PROYEK",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BadgeGreenText
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ActionSolidBg)
                    .clickable {
                        newWorkspaceName = ""
                        createError = null
                        showCreateDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "+ PROYEK BARU",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActionSolidText
                )
            }
        }

        // STATUS / ALERT BANNER
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION: DAFTAR PROYEK
            item {
                Text(
                    text = "DAFTAR WORKSPACE",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            if (workspaces.isEmpty()) {
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
                            text = "Belum ada proyek. Ketuk [+ PROYEK BARU] untuk memulai.",
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                items(workspaces) { ws ->
                    val isActive = ws.name == activeWorkspace
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) CardHighlight else SurfaceCard)
                            .border(1.dp, if (isActive) TextPrimary else BorderSubtle, RoundedCornerShape(6.dp))
                            .clickable {
                                LinuxRuntimeManager.setActiveWorkspace(ws.name)
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ws.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BadgeGreenBg)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "AKTIF",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BadgeGreenText
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${ws.filesCount} file total",
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isActive) ActionSolidBg else CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable {
                                        LinuxRuntimeManager.setActiveWorkspace(ws.name)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isActive) "DIPILIH" else "PILIH",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) ActionSolidText else TextPrimary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { workspaceToDelete = ws }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
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

            // SECTION: FILE DALAM PROYEK AKTIF
            if (activeWorkspace.isNotBlank()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FILE: $activeWorkspace",
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
                                .clickable {
                                    importLauncher.launch("*/*")
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "[IMPORT ASET]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                if (currentFiles.isEmpty()) {
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
                                text = "Belum ada file di proyek ini. Impor media atau mulai perintah di tab AGEN.",
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    items(currentFiles) { file ->
                        val categoryBadgeBg = when (file.category) {
                            "scripts" -> CardHighlight
                            "audio" -> BadgeGreenBg
                            "output" -> ActionSolidBg
                            else -> SurfaceCard
                        }
                        val categoryBadgeText = when (file.category) {
                            "scripts" -> TextPrimary
                            "audio" -> BadgeGreenText
                            "output" -> ActionSolidText
                            else -> TextSecondary
                        }
                        val sizeKb = (file.size / 1024.0)
                        val sizeStr = if (sizeKb >= 1024) String.format(Locale.US, "%.1f MB", sizeKb / 1024.0) else String.format(Locale.US, "%.0f KB", sizeKb)

                        val isText = isTextFile(file.name)
                        val isMedia = isMediaFile(file.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceCard)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (isText) previewTextFile(file)
                                        else if (isMedia) openMediaFile(file)
                                    }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = file.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(categoryBadgeBg)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = file.category.uppercase(),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = categoryBadgeText
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${file.relPath} • $sizeStr",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isText) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CardHighlight)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                            .clickable { previewTextFile(file) }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "BACA",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }
                                } else if (isMedia) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CardHighlight)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                            .clickable { openMediaFile(file) }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "BUKA",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { exportFile(file) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "SIMPAN",
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
                                        .clickable { fileToDelete = file }
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
    }

    // DIALOG PROYEK BARU
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isBusy) showCreateDialog = false
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = "PROYEK BARU",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Masukkan nama proyek (folder workspace):",
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
                        if (newWorkspaceName.isEmpty()) {
                            Text(
                                text = "misal: video_promo_01",
                                color = TextMuted,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        BasicTextField(
                            value = newWorkspaceName,
                            onValueChange = { newWorkspaceName = it.replace(" ", "_") },
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

                    if (!createError.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(ErrorCardBg)
                                .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = createError ?: "",
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
                        val clean = newWorkspaceName.trim()
                        if (clean.isBlank()) {
                            createError = "Nama proyek tidak boleh kosong"
                            return@Button
                        }
                        isBusy = true
                        createError = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createWorkspace(clean)
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    showCreateDialog = false
                                    refreshWorkspaces()
                                    LinuxRuntimeManager.setActiveWorkspace(clean)
                                    actionStatus = "Proyek '$clean' berhasil dibuat"
                                } else {
                                    createError = res.optString("error", "Gagal membuat proyek")
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
                        text = "BUAT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCreateDialog = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardHighlight,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
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
                        text = if (copiedPreview) "TERSALIN" else "SALIN",
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
                        text = "TUTUP",
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
                    text = "HAPUS FILE",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Hapus file \"${target.name}\" secara permanen dari proyek?",
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
                            actionStatus = "File '${target.name}' telah dihapus"
                            loadWorkspaceFiles(activeWorkspace)
                            refreshWorkspaces()
                        } else {
                            actionStatus = "Gagal menghapus file '${target.name}'"
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
                        text = "HAPUS",
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
                        text = "BATAL",
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
                    text = "HAPUS PROYEK",
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorCardText
                )
            },
            text = {
                Text(
                    text = "Hapus proyek \"${targetWs.name}\" beserta seluruh file di dalamnya secara permanen?",
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
                                    actionStatus = "Proyek '$toDel' telah dihapus"
                                    refreshWorkspaces()
                                } else {
                                    actionStatus = "Gagal menghapus proyek: ${res.optString("error", "Error")}"
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
                        text = "HAPUS",
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
