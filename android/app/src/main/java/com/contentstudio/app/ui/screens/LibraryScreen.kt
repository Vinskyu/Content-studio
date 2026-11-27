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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.contentstudio.app.runtime.LibraryAssetItem
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.WorkspaceSummary
import com.contentstudio.app.ui.theme.StudioTopBar
import com.contentstudio.app.ui.theme.StudioSegmentedControl
import com.contentstudio.app.ui.theme.StudioTextField
import com.contentstudio.app.ui.theme.StudioPrimaryButton
import com.contentstudio.app.ui.theme.ActionSolidBg
import com.contentstudio.app.ui.theme.ActionSolidText
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.BorderSubtle
import com.contentstudio.app.ui.theme.CardHighlight
import com.contentstudio.app.ui.theme.ErrorCardText
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    val assets = remember { mutableStateListOf<LibraryAssetItem>() }
    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }

    var isBusy by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var assetToUse by remember { mutableStateOf<LibraryAssetItem?>(null) }
    var assetToDelete by remember { mutableStateOf<LibraryAssetItem?>(null) }

    fun refreshAssets() {
        scope.launch(Dispatchers.IO) {
            val catParam = if (selectedCategory == "all") null else selectedCategory
            val res = LinuxRuntimeManager.listLibraryAssets(catParam)
            val arr = res.optJSONArray("assets") ?: return@launch
            val list = mutableListOf<LibraryAssetItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LibraryAssetItem(
                        name = obj.optString("name", ""),
                        category = obj.optString("category", "other"),
                        size = obj.optLong("size", 0L),
                        mtime = obj.optLong("mtime", 0L),
                        path = obj.optString("path", ""),
                        relPath = obj.optString("rel_path", ""),
                    )
                )
            }
            withContext(Dispatchers.Main) {
                assets.clear()
                assets.addAll(list)
            }
        }
    }

    fun loadWorkspaces() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listWorkspaces()
            val arr = res.optJSONArray("workspaces") ?: return@launch
            val list = mutableListOf<WorkspaceSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    WorkspaceSummary(
                        name = obj.optString("name", ""),
                        path = obj.optString("path", ""),
                        filesCount = obj.optInt("files_count", 0),
                        updatedAt = obj.optLong("updated_at", 0L),
                    )
                )
            }
            withContext(Dispatchers.Main) {
                workspaces.clear()
                workspaces.addAll(list)
            }
        }
    }

    LaunchedEffect(selectedCategory) {
        refreshAssets()
        loadWorkspaces()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        actionStatus = "Importing asset to library..."
        scope.launch(Dispatchers.IO) {
            val targetCategory = if (selectedCategory == "all") null else selectedCategory
            val result = LinuxRuntimeManager.importToLibrary(context, uri, targetCategory)
            withContext(Dispatchers.Main) {
                isBusy = false
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    actionStatus = "Asset imported: ${file.name}"
                    refreshAssets()
                } else {
                    actionStatus = "Import failed: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    fun openAssetFile(asset: LibraryAssetItem) {
        try {
            val targetFile = File(asset.path)
            if (!targetFile.exists()) {
                actionStatus = "File not found"
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
            actionStatus = "Failed to open file: ${e.message}"
        }
    }

    fun copyAssetToActiveWorkspace(asset: LibraryAssetItem, targetWorkspace: String) {
        isBusy = true
        actionStatus = "Copying ${asset.name} to $targetWorkspace..."
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.copyLibraryAssetToWorkspace(
                category = asset.category,
                name = asset.name,
                workspace = targetWorkspace
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                if (res.optString("status") == "ok") {
                    val rel = res.optString("rel_path", asset.name)
                    actionStatus = "Copied to $targetWorkspace/$rel"
                } else {
                    actionStatus = "Failed to copy: ${res.optString("error", "Error")}"
                }
            }
        }
    }

    fun copyAssetToTargetWorkspace(targetWorkspace: String) {
        val asset = assetToUse ?: return
        isBusy = true
        actionStatus = "Copying ${asset.name} to $targetWorkspace..."
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.copyLibraryAssetToWorkspace(
                category = asset.category,
                name = asset.name,
                workspace = targetWorkspace
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                assetToUse = null
                if (res.optString("status") == "ok") {
                    val rel = res.optString("rel_path", asset.name)
                    actionStatus = "Copied to $targetWorkspace/$rel"
                } else {
                    actionStatus = "Failed to copy: ${res.optString("error", "Error")}"
                }
            }
        }
    }

    fun confirmDeleteAsset() {
        val asset = assetToDelete ?: return
        isBusy = true
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.deleteLibraryAsset(asset.category, asset.name)
            withContext(Dispatchers.Main) {
                isBusy = false
                assetToDelete = null
                if (res.optString("status") == "ok") {
                    actionStatus = "Aset berhasil dihapus: ${asset.name}"
                    refreshAssets()
                } else {
                    actionStatus = "Gagal menghapus: ${res.optString("error", "Error")}"
                }
            }
        }
    }

    val filteredAssets = assets.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery.trim(), ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // CATEGORY SEGMENTED CONTROL, SEARCH & IMPORT
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val categories = listOf("All", "Video", "Audio", "Image")
            val catKeys = listOf("all", "video", "audio", "image")
            val selectedIdx = catKeys.indexOf(selectedCategory).coerceAtLeast(0)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    StudioSegmentedControl(
                        options = categories,
                        selectedIndex = selectedIdx,
                        onSelect = { selectedCategory = catKeys[it] }
                    )
                }
                StudioPrimaryButton(
                    text = "Import",
                    onClick = { importLauncher.launch("*/*") },
                    height = 36.dp
                )
            }

            StudioTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search assets...",
                singleLine = true
            )
        }

        // STATUS BANNER
        if (actionStatus != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardHighlight)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionStatus ?: "",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
            }
        }

        // ASSET LIST
        if (filteredAssets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No assets found in Library.",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(filteredAssets) { asset ->
                    val ext = asset.name.substringAfterLast('.', "").uppercase().take(4)
                    val sizeKb = asset.size / 1024
                    val sizeStr = if (sizeKb > 1024) String.format(Locale.US, "%.1f MB", sizeKb / 1024.0) else "$sizeKb KB"
                    val dateStr = if (asset.mtime > 0) {
                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(asset.mtime))
                    } else ""

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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CardHighlight)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = ext.ifBlank { asset.category.uppercase() },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    text = asset.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${asset.category.uppercase()} • $sizeStr • $dateStr",
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
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
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { openAssetFile(asset) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Open",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ActionSolidBg)
                                    .clickable {
                                        if (activeWorkspace.isNotBlank()) {
                                            copyAssetToActiveWorkspace(asset, activeWorkspace)
                                        } else {
                                            actionStatus = "No active project. Select a project in Studio first."
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Use",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ActionSolidText
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { assetToDelete = asset },
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
                }
            }
        }
    }

    // DIALOG: SELECT WORKSPACE TO USE ASSET
    if (assetToUse != null) {
        val asset = assetToUse!!
        AlertDialog(
            onDismissRequest = { assetToUse = null },
            title = {
                Text(
                    text = "Use Asset in Project",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Select project to copy \"${asset.name}\":",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (workspaces.isEmpty()) {
                        Text(
                            text = "No projects found. Create a project in Projects tab first.",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = ErrorCardText
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            workspaces.forEach { ws ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SurfaceCard)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                        .clickable { copyAssetToTargetWorkspace(ws.name) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = ws.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = { assetToUse = null },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = BgDark,
            tonalElevation = 6.dp
        )
    }

    // DIALOG: CONFIRM DELETE
    if (assetToDelete != null) {
        val asset = assetToDelete!!
        AlertDialog(
            onDismissRequest = { assetToDelete = null },
            title = {
                Text(
                    text = "Delete Asset",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${asset.name}\" from the Library?",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmDeleteAsset() },
                    colors = ButtonDefaults.buttonColors(containerColor = CardHighlight)
                ) {
                    Text("Delete", color = ErrorCardText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = { assetToDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = BgDark,
            tonalElevation = 6.dp
        )
    }
}
