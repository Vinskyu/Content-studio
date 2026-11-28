package com.contentstudio.app.ui.screens

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.contentstudio.app.runtime.StockMediaConfig
import com.contentstudio.app.runtime.StockMediaItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.contentstudio.app.runtime.ChannelDna
import com.contentstudio.app.runtime.ChannelProfile
import com.contentstudio.app.runtime.LibraryAssetItem
import androidx.activity.compose.BackHandler
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.components.StudioMediaThumbnail
import com.contentstudio.app.ui.components.StudioMediaPlayerDialog
import com.contentstudio.app.runtime.PatternBeat
import com.contentstudio.app.runtime.PatternItem
import com.contentstudio.app.runtime.WorkspaceSummary
import com.contentstudio.app.ui.theme.ActionSolidBg
import com.contentstudio.app.ui.theme.ActionSolidText
import com.contentstudio.app.ui.theme.BadgeGreenText
import com.contentstudio.app.ui.theme.ErrorCardBg
import com.contentstudio.app.ui.theme.ErrorCardText
import com.contentstudio.app.ui.theme.StudioDangerButton
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioPrimaryButton
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.StudioSecondaryButton
import com.contentstudio.app.ui.theme.StudioTextField
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibMatteBg = Color(0xFF0B0C0E)
private val LibMatteCard = Color(0xFF15171C)
private val LibMatteElevated = Color(0xFF1E2128)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf("media") } // "media" (Galeri), "channels", "templates"

    // Media state & 2-level Project Gallery
    var galleryTree by remember { mutableStateOf<com.contentstudio.app.runtime.GalleryTreeResult?>(null) }
    var selectedProjectFolder by remember { mutableStateOf<com.contentstudio.app.runtime.GalleryProjectFolder?>(null) }
    var selectedFolderTab by remember { mutableStateOf("finished") }
    var previewGalleryItem by remember { mutableStateOf<com.contentstudio.app.runtime.GalleryFileItem?>(null) }

    fun refreshGalleryTree() {
        scope.launch(Dispatchers.IO) {
            val tree = LinuxRuntimeManager.fetchGalleryTree()
            val list = LinuxRuntimeManager.fetchStudioMediaIndex()
            withContext(Dispatchers.Main) {
                galleryTree = tree
                studioMedia.clear()
                studioMedia.addAll(list)
                selectedProjectFolder?.let { cur ->
                    selectedProjectFolder = tree.projects.find { it.projectId == cur.projectId }
                }
            }
        }
    }

    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    val studioMedia = remember { mutableStateListOf<com.contentstudio.app.runtime.StudioMediaFile>() }
    var selectedMediaFilter by remember { mutableStateOf("All") }
    var selectedStudioMedia by remember { mutableStateOf<com.contentstudio.app.runtime.StudioMediaFile?>(null) }
    var mediaSelectionMode by remember { mutableStateOf(false) }
    val selectedMediaPaths = remember { mutableStateListOf<String>() }
    var pendingMediaDelete by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(activeTab) {
        if (activeTab == "media") {
            refreshGalleryTree()
        } else if (activeTab == "channels") {
            refreshChannels()
        } else if (activeTab == "templates") {
            refreshTemplates()
        }
    }

    fun exitMediaSelection() {
        mediaSelectionMode = false
        selectedMediaPaths.clear()
    }

    val assets = remember { mutableStateListOf<LibraryAssetItem>() }
    val workspaces = remember { mutableStateListOf<WorkspaceSummary>() }
    var assetToUse by remember { mutableStateOf<LibraryAssetItem?>(null) }
    var assetToDelete by remember { mutableStateOf<LibraryAssetItem?>(null) }

    // Stock Media & BYOK state
    var showStockSheet by remember { mutableStateOf(false) }

    val libHasOverlay = showStockSheet || assetToDelete != null || assetToUse != null ||
        selectedStudioMedia != null || mediaSelectionMode || previewGalleryItem != null || selectedProjectFolder != null
    BackHandler(enabled = libHasOverlay) {
        when {
            previewGalleryItem != null -> previewGalleryItem = null
            selectedProjectFolder != null -> selectedProjectFolder = null
            showStockSheet -> showStockSheet = false
            assetToDelete != null -> assetToDelete = null
            assetToUse != null -> assetToUse = null
            selectedStudioMedia != null -> selectedStudioMedia = null
            mediaSelectionMode -> exitMediaSelection()
        }
    }
    var stockSheetTab by remember { mutableIntStateOf(0) } // 0: Search, 1: URL Download
    var stockQuery by remember { mutableStateOf("") }
    var stockSource by remember { mutableStateOf("auto") } // "auto", "ytdlp", "wikimedia", "pexels", "pixabay"
    var stockType by remember { mutableStateOf("video") } // "video", "photo", "audio"
    val stockResults = remember { mutableStateListOf<StockMediaItem>() }
    var isSearchingStock by remember { mutableStateOf(false) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }
    var urlDownloadInput by remember { mutableStateOf("") }
    var isAudioOnlyDownload by remember { mutableStateOf(false) }
    var startTimestampInput by remember { mutableStateOf("") }
    var endTimestampInput by remember { mutableStateOf("") }
    var isDownloadingUrl by remember { mutableStateOf(false) }
    var mediaConfig by remember { mutableStateOf<StockMediaConfig?>(null) }

    // Channels state
    val channels = remember { mutableStateListOf<ChannelProfile>() }
    var expandedChannelId by remember { mutableStateOf<String?>(null) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var channelNameInput by remember { mutableStateOf("") }
    var channelAspectInput by remember { mutableStateOf("9:16") }
    var channelError by remember { mutableStateOf<String?>(null) }
    var channelToDelete by remember { mutableStateOf<ChannelProfile?>(null) }
    var channelToInspectDna by remember { mutableStateOf<ChannelProfile?>(null) }

    // Templates (Patterns) state
    val templates = remember { mutableStateListOf<PatternItem>() }
    var showCreateTemplateDialog by remember { mutableStateOf(false) }
    var newTemplateName by remember { mutableStateOf("") }
    var newTemplateAspect by remember { mutableStateOf("9:16") }
    var templateCreateError by remember { mutableStateOf<String?>(null) }
    var templateToDelete by remember { mutableStateOf<PatternItem?>(null) }
    var applyTemplateTarget by remember { mutableStateOf<PatternItem?>(null) }

    var isBusy by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf<String?>(null) }

    fun deleteStudioMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val ok = LinuxRuntimeManager.deleteStudioMediaFiles(paths)
            val list = LinuxRuntimeManager.fetchStudioMediaIndex()
            withContext(Dispatchers.Main) {
                studioMedia.clear()
                studioMedia.addAll(list)
                exitMediaSelection()
                actionStatus = if (ok) "${paths.size} file dihapus" else "Sebagian file gagal dihapus"
            }
        }
    }

    fun loadMediaConfig() {
        scope.launch(Dispatchers.IO) {
            val cfg = LinuxRuntimeManager.getMediaConfig()
            withContext(Dispatchers.Main) {
                mediaConfig = cfg
            }
        }
    }

    fun performStockSearch() {
        val q = stockQuery.trim()
        if (q.isBlank()) return
        isSearchingStock = true
        actionStatus = "Mencari media stock..."
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.searchMedia(
                query = q,
                source = stockSource,
                type = stockType
            )
            withContext(Dispatchers.Main) {
                stockResults.clear()
                stockResults.addAll(res)
                isSearchingStock = false
                actionStatus = if (res.isEmpty()) "Tidak ada hasil untuk '$q'" else "Ditemukan ${res.size} hasil"
            }
        }
    }

    fun refreshAssets() {
        scope.launch(Dispatchers.IO) {
            val catParam = if (selectedCategory == "all") null else selectedCategory
            val res = LinuxRuntimeManager.listLibraryAssets(catParam)
            val arr = res.optJSONArray("assets") ?: return@launch
            val list = mutableListOf<LibraryAssetItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
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

    fun downloadStockItem(item: StockMediaItem) {
        downloadingUrl = item.downloadUrl
        actionStatus = "Mengunduh ${item.title.take(30)}..."
        scope.launch(Dispatchers.IO) {
            val ok = LinuxRuntimeManager.downloadMedia(
                url = item.downloadUrl,
                filename = item.title.take(40),
                project = activeWorkspace,
                audioOnly = (item.type == "audio")
            )
            withContext(Dispatchers.Main) {
                downloadingUrl = null
                actionStatus = if (ok) "Media berhasil diunduh ke library & proyek!" else "Gagal mengunduh media"
                val catParam = if (selectedCategory == "all") null else selectedCategory
                val res = LinuxRuntimeManager.listLibraryAssets(catParam)
                val arr = res.optJSONArray("assets")
                if (arr != null) {
                    val list = mutableListOf<LibraryAssetItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
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
                    assets.clear()
                    assets.addAll(list)
                }
            }
        }
    }

    fun downloadDirectUrl() {
        val url = urlDownloadInput.trim()
        if (url.isBlank()) return
        isDownloadingUrl = true
        actionStatus = "Mengunduh media dari URL..."
        scope.launch(Dispatchers.IO) {
            val ok = LinuxRuntimeManager.downloadMedia(
                url = url,
                filename = "",
                project = activeWorkspace,
                audioOnly = isAudioOnlyDownload,
                start = startTimestampInput.trim(),
                end = endTimestampInput.trim()
            )
            withContext(Dispatchers.Main) {
                isDownloadingUrl = false
                actionStatus = if (ok) "Media berhasil diunduh ke library & proyek!" else "Gagal mengunduh media dari URL"
                if (ok) {
                    urlDownloadInput = ""
                    startTimestampInput = ""
                    endTimestampInput = ""
                    showStockSheet = false
                }
                refreshAssets()
            }
        }
    }

    fun refreshChannels() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listChannels()
            val arr = res.optJSONArray("channels") ?: return@launch
            val list = mutableListOf<ChannelProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val dnaObj = obj.optJSONObject("dna")
                val traitsArr = dnaObj?.optJSONArray("learned_traits")
                val traits = mutableListOf<String>()
                if (traitsArr != null) {
                    for (j in 0 until traitsArr.length()) {
                        traits.add(traitsArr.optString(j, ""))
                    }
                }
                list.add(
                    ChannelProfile(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        aspectRatio = obj.optString("aspect_ratio", "9:16"),
                        dna = ChannelDna(
                            avgSceneDuration = dnaObj?.optDouble("avg_scene_duration", 3.5)?.toFloat() ?: 3.5f,
                            pacingWpm = dnaObj?.optInt("pacing_wpm", 145) ?: 145,
                            preferredVoice = dnaObj?.optString("preferred_voice", "id-ID-ArdiNeural") ?: "id-ID-ArdiNeural",
                            visualRules = dnaObj?.optString("visual_rules", "") ?: "",
                            subtitleStyle = dnaObj?.optString("subtitle_style", "bottom_bold") ?: "bottom_bold",
                            styleSummary = dnaObj?.optString("style_summary", "") ?: "",
                            referenceNotes = dnaObj?.optString("reference_notes", "") ?: "",
                            learnedTraits = traits
                        )
                    )
                )
            }
            withContext(Dispatchers.Main) {
                channels.clear()
                channels.addAll(list)
            }
        }
    }

    fun refreshTemplates() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listPatterns()
            val arr = res.optJSONArray("patterns") ?: return@launch
            val list = mutableListOf<PatternItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val beatsArr = obj.optJSONArray("beats")
                val beats = mutableListOf<PatternBeat>()
                if (beatsArr != null) {
                    for (j in 0 until beatsArr.length()) {
                        val b = beatsArr.optJSONObject(j) ?: continue
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
                        name = obj.optString("name", ""),
                        aspectRatio = obj.optString("aspect_ratio", "9:16"),
                        beats = beats
                    )
                )
            }
            withContext(Dispatchers.Main) {
                templates.clear()
                templates.addAll(list)
            }
        }
    }

    fun loadWorkspaces() {
        scope.launch(Dispatchers.IO) {
            val res = LinuxRuntimeManager.listWorkspaces()
            val arr = res.optJSONArray("workspaces") ?: return@launch
            val list = mutableListOf<WorkspaceSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
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

    LaunchedEffect(Unit) {
        refreshChannels()
        refreshTemplates()
        loadWorkspaces()
        loadMediaConfig()
    }

    // Auto-refresh on UI sync event
    val uiSyncVersion by LinuxRuntimeManager.uiSyncVersion.collectAsState()
    LaunchedEffect(uiSyncVersion) {
        if (uiSyncVersion > 0L) {
            refreshChannels()
            refreshTemplates()
            loadWorkspaces()
            refreshAssets()
        }
    }

    LaunchedEffect(selectedCategory) {
        refreshAssets()
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
                    actionStatus = "Asset deleted: ${asset.name}"
                    refreshAssets()
                } else {
                    actionStatus = "Failed to delete: ${res.optString("error", "Error")}"
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
            .background(LibMatteBg)
    ) {
        // 1. TOP 3-TAB BAR: Channels | Templates | Media (matte, no 1dp border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LibMatteCard)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("media" to "Galeri", "channels" to "Channel DNA", "templates" to "Pola Ritme").forEach { (key, label) ->
                    val selected = activeTab == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) LibMatteElevated else Color.Transparent)
                            .clickable { activeTab = key }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) TextPrimary else TextMuted
                        )
                    }
                }
            }

            // Contextual primary action button(s)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeTab == "media") {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(LibMatteElevated)
                            .clickable { showStockSheet = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Fetch / Stock",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF2F4F8))
                        .clickable {
                            when (activeTab) {
                                "channels" -> {
                                    channelNameInput = ""
                                    channelAspectInput = "9:16"
                                    channelError = null
                                    showChannelDialog = true
                                }
                                "templates" -> {
                                    newTemplateName = ""
                                    newTemplateAspect = "9:16"
                                    templateCreateError = null
                                    showCreateTemplateDialog = true
                                }
                                else -> importLauncher.launch("*/*")
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = Color(0xFF0B0C0E),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when (activeTab) {
                                "channels" -> "Channel"
                                "templates" -> "Template"
                                else -> "Import"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0B0C0E)
                        )
                    }
                }
            }
        }

        // STATUS BANNER
        if (actionStatus != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LibMatteElevated)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionStatus ?: "",
                    fontSize = 11.sp,
                    color = TextPrimary
                )
            }
        }

        // TAB BODY
        when (activeTab) {
            "channels" -> {
                if (channels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No channels created yet. Tap + Channel to set up a profile.",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(channels) { chan ->
                            val isExpanded = (expandedChannelId == chan.id)
                            val dna = chan.dna
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LibMatteCard)
                                    .clickable {
                                        expandedChannelId = if (isExpanded) null else chan.id
                                    }
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = chan.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "${chan.aspectRatio} | Scene Avg ${dna.avgSceneDuration}s | ${dna.pacingWpm} WPM",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(LibMatteElevated)
                                                .clickable { channelToInspectDna = chan }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "DNA",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = TextPrimary
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { channelToDelete = chan },
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

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(LibMatteElevated)
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Voice: ${dna.preferredVoice} | Subtitles: ${dna.subtitleStyle}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        if (dna.learnedTraits.isNotEmpty()) {
                                            dna.learnedTraits.forEach { trait ->
                                                Text(
                                                    text = "• $trait",
                                                    fontSize = 11.sp,
                                                    color = BadgeGreenText
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Learned Traits: None yet (evolves from your edits)",
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
            }
            "templates" -> {
                if (templates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No templates created yet. Tap + Template to add a rhythm template.",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(templates) { tpl ->
                            val totalDur = tpl.beats.map { it.duration }.sum()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LibMatteCard)
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tpl.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "${tpl.aspectRatio} | ${tpl.beats.size} beats | ${String.format(Locale.US, "%.1f", totalDur)}s",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFF2F4F8))
                                                .clickable { applyTemplateTarget = tpl }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Apply",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF0B0C0E)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { templateToDelete = tpl },
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

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = tpl.beats.joinToString(" -> ") { "${it.role} (${it.duration}s)" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                // ── 2-LEVEL PROJECT-ROOTED MEDIA GALLERY ──
                if (selectedProjectFolder == null) {
                    // LEVEL 1: FOLDER PROYEK
                    val projects = galleryTree?.projects.orEmpty()
                    val gen = galleryTree?.general

                    if (projects.isEmpty() && (gen == null || gen.count == 0)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Belum ada folder proyek",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                        ) {
                            items(projects, key = { it.projectId }) { folder ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(LibMatteCard)
                                        .clickable {
                                            selectedProjectFolder = folder
                                            selectedFolderTab = "finished"
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.6f)
                                            .background(LibMatteElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (folder.coverThumb.isNotBlank()) {
                                            AsyncImage(
                                                model = LinuxRuntimeManager.getFullMediaUrl(folder.coverThumb),
                                                contentDescription = folder.projectName,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.VideoLibrary,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = folder.projectName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${folder.finishedCount} Jadi · ${folder.rawCount} Bahan",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }

                            if (gen != null && gen.count > 0) {
                                item(key = "general_folder") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LibMatteCard)
                                            .clickable {
                                                selectedProjectFolder = com.contentstudio.app.runtime.GalleryProjectFolder(
                                                    projectId = "_general",
                                                    projectName = gen.name,
                                                    coverThumb = gen.items.firstOrNull { it.thumbUrl.isNotBlank() }?.thumbUrl ?: "",
                                                    finishedCount = 0,
                                                    rawCount = gen.count,
                                                    finished = emptyList(),
                                                    raw = gen.items
                                                )
                                                selectedFolderTab = "raw"
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1.6f)
                                                .background(LibMatteElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.CollectionsBookmark,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = gen.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${gen.count} File umum",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // LEVEL 2: DETAIL FOLDER PROYEK
                    val folder = selectedProjectFolder!!
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // Header dengan tombol Back
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LibMatteElevated)
                                    .clickable { selectedProjectFolder = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Kembali ke Folder",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.projectName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${folder.finishedCount} Jadi · ${folder.rawCount} Bahan",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // Segmented Tab 2 Pilihan: Hasil Jadi vs Bahan Mentah
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LibMatteCard)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                "finished" to "Hasil Jadi (${folder.finishedCount})",
                                "raw" to "Bahan Mentah (${folder.rawCount})"
                            ).forEach { (tKey, label) ->
                                val isSel = selectedFolderTab == tKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) LibMatteElevated else Color.Transparent)
                                        .clickable { selectedFolderTab = tKey }
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSel) TextPrimary else TextMuted
                                    )
                                }
                            }
                        }

                        val itemsToShow = if (selectedFolderTab == "finished") folder.finished else folder.raw
                        if (itemsToShow.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (selectedFolderTab == "finished") "Belum ada hasil render final" else "Belum ada aset bahan mentah",
                                    fontSize = 13.sp,
                                    color = TextMuted
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                            ) {
                                items(itemsToShow, key = { it.id }) { item ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LibMatteCard)
                                            .clickable { previewGalleryItem = item }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1.2f)
                                                .background(LibMatteElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            when (item.type) {
                                                "image", "video" -> {
                                                    val url = LinuxRuntimeManager.getFullMediaUrl(item.thumbUrl.ifBlank { item.streamUrl })
                                                    AsyncImage(
                                                        model = url,
                                                        contentDescription = item.name,
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                                else -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.GraphicEq,
                                                        contentDescription = item.name,
                                                        tint = TextMuted,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }

                                            if (item.type == "video") {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0x88000000)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.PlayArrow,
                                                        contentDescription = "Play",
                                                        tint = TextPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = item.name,
                                                fontSize = 12.sp,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${(item.size / 1024L).coerceAtLeast(1L)} KB · ${item.type.uppercase()}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = TextMuted
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

        // Preview modal untuk file galeri terpilih
        if (previewGalleryItem != null) {
            val item = previewGalleryItem!!
            val fullUrl = LinuxRuntimeManager.getFullMediaUrl(item.streamUrl)
            Dialog(
                onDismissRequest = { previewGalleryItem = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LibMatteBg)
                ) {
                    IconButton(
                        onClick = { previewGalleryItem = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close", tint = TextPrimary)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 420.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LibMatteElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            when (item.type) {
                                "video" -> {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                setVideoURI(Uri.parse(fullUrl))
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = true
                                                    mp.start()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                "image" -> {
                                    AsyncImage(
                                        model = fullUrl,
                                        contentDescription = item.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Outlined.GraphicEq,
                                        contentDescription = item.name,
                                        tint = TextMuted,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = item.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.path} · ${(item.size / 1024L).coerceAtLeast(1L)} KB",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StudioDangerButton(
                                text = "Hapus Permanen",
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val ok = LinuxRuntimeManager.deleteMediaFiles(listOf(item.path))
                                        withContext(Dispatchers.Main) {
                                            previewGalleryItem = null
                                            refreshGalleryTree()
                                            actionStatus = if (ok) "File dihapus permanen" else "Gagal menghapus file"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            StudioPrimaryButton(
                                text = "Simpan ke Unduhan",
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val sourceFile = File(item.fullPath)
                                        val res = LinuxRuntimeManager.exportFileToDownloads(context, sourceFile)
                                        withContext(Dispatchers.Main) {
                                            actionStatus = if (res.isSuccess) "Tersimpan di ${res.getOrNull()}" else "Gagal mengekspor file"
                                        }
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

    // ── DIALOGS FOR CHANNELS ─────────────────────────────────────────────────
    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showChannelDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = {
                Text("New Channel", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Set up a channel identity. Channel DNA evolves automatically from project edits.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    StudioTextField(
                        value = channelNameInput,
                        onValueChange = { channelNameInput = it },
                        placeholder = "Channel name",
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("9:16", "16:9").forEach { ratio ->
                            val isSel = channelAspectInput == ratio
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) LibMatteElevated else LibMatteCard)
                                    .clickable { channelAspectInput = ratio }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = ratio,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (isSel) TextPrimary else TextMuted
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
                StudioPrimaryButton(
                    text = "Create",
                    onClick = {
                        val name = channelNameInput.trim()
                        if (name.isBlank()) {
                            channelError = "Channel name cannot be empty"
                            return@StudioPrimaryButton
                        }
                        isBusy = true
                        channelError = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createChannel(name = name, aspectRatio = channelAspectInput)
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
                    }
                )
            },
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { showChannelDialog = false })
            }
        )
    }

    if (channelToInspectDna != null) {
        val chan = channelToInspectDna!!
        AlertDialog(
            onDismissRequest = { channelToInspectDna = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = {
                Text("DNA Profile: ${chan.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tempo: ${String.format(Locale.US, "%.1f", chan.dna.avgSceneDuration)}s / scene | ${chan.dna.pacingWpm} WPM",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = chan.dna.styleSummary.ifBlank { "No style summary yet" },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    if (chan.dna.learnedTraits.isNotEmpty()) {
                        chan.dna.learnedTraits.forEach { trait ->
                            Text(text = "• $trait", fontSize = 11.sp, color = BadgeGreenText)
                        }
                    }
                }
            },
            confirmButton = {
                StudioPrimaryButton(text = "Close", onClick = { channelToInspectDna = null })
            }
        )
    }

    if (channelToDelete != null) {
        val targetChan = channelToDelete!!
        AlertDialog(
            onDismissRequest = { channelToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Delete Channel", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ErrorCardText) },
            text = { Text("Delete channel \"${targetChan.name}\" permanently?", fontSize = 13.sp, color = TextPrimary) },
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
                                    actionStatus = "Failed to delete channel"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorCardBg, contentColor = ErrorCardText)
                ) {
                    Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { channelToDelete = null })
            }
        )
    }

    // ── DIALOGS FOR TEMPLATES ────────────────────────────────────────────────
    if (showCreateTemplateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showCreateTemplateDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("New Rhythm Template", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Create a reusable pacing template initialized with 4 standard beats.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    StudioTextField(
                        value = newTemplateName,
                        onValueChange = { newTemplateName = it },
                        placeholder = "Template name",
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("9:16", "16:9").forEach { ratio ->
                            val isSel = newTemplateAspect == ratio
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) LibMatteElevated else LibMatteCard)
                                    .clickable { newTemplateAspect = ratio }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = ratio,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (isSel) TextPrimary else TextMuted
                                )
                            }
                        }
                    }
                    if (!templateCreateError.isNullOrBlank()) {
                        Text(text = templateCreateError ?: "", color = ErrorCardText, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                StudioPrimaryButton(
                    text = "Create",
                    onClick = {
                        val name = newTemplateName.trim()
                        if (name.isBlank()) {
                            templateCreateError = "Template name cannot be empty"
                            return@StudioPrimaryButton
                        }
                        isBusy = true
                        templateCreateError = null
                        val defaultBeats = JSONArray().apply {
                            put(JSONObject().apply { put("role", "Hook"); put("duration", 2.5) })
                            put(JSONObject().apply { put("role", "Setup"); put("duration", 4.0) })
                            put(JSONObject().apply { put("role", "Twist"); put("duration", 4.0) })
                            put(JSONObject().apply { put("role", "Close"); put("duration", 2.5) })
                        }
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.createPattern(name = name, aspectRatio = newTemplateAspect, beatsJsonArray = defaultBeats)
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                if (res.optString("status") == "ok") {
                                    showCreateTemplateDialog = false
                                    refreshTemplates()
                                    actionStatus = "Template '$name' created"
                                } else {
                                    templateCreateError = res.optString("error", "Failed to create template")
                                }
                            }
                        }
                    }
                )
            },
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { showCreateTemplateDialog = false })
            }
        )
    }

    if (templateToDelete != null) {
        val targetTpl = templateToDelete!!
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Delete Template", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ErrorCardText) },
            text = { Text("Delete template \"${targetTpl.name}\" permanently?", fontSize = 13.sp, color = TextPrimary) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = targetTpl.id
                        val delName = targetTpl.name
                        templateToDelete = null
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.deletePattern(toDel)
                            withContext(Dispatchers.Main) {
                                if (res.optString("status") == "ok") {
                                    actionStatus = "Template '$delName' deleted"
                                    refreshTemplates()
                                } else {
                                    actionStatus = "Failed to delete template"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorCardBg, contentColor = ErrorCardText)
                ) {
                    Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { templateToDelete = null })
            }
        )
    }

    if (applyTemplateTarget != null) {
        val targetTpl = applyTemplateTarget!!
        AlertDialog(
            onDismissRequest = { applyTemplateTarget = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Apply Template", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a project for \"${targetTpl.name}\":", fontSize = 12.sp, color = TextSecondary)
                    if (workspaces.isEmpty()) {
                        Text("No projects available.", fontSize = 12.sp, color = ErrorCardText)
                    } else {
                        workspaces.forEach { ws ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LibMatteElevated)
                                    .clickable {
                                        applyTemplateTarget = null
                                        scope.launch(Dispatchers.IO) {
                                            val res = LinuxRuntimeManager.applyPattern(ws.name, targetTpl.id)
                                            withContext(Dispatchers.Main) {
                                                actionStatus = if (res.isSuccess) {
                                                    "Template applied to ${ws.name}"
                                                } else {
                                                    "Failed to apply template"
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(ws.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { applyTemplateTarget = null })
            }
        )
    }

    // ── DIALOGS FOR MEDIA ASSETS ─────────────────────────────────────────────
    if (assetToUse != null) {
        val asset = assetToUse!!
        AlertDialog(
            onDismissRequest = { if (!isBusy) assetToUse = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Copy Asset to Project", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copy \"${asset.name}\" to project:", fontSize = 12.sp, color = TextSecondary)
                    if (workspaces.isEmpty()) {
                        Text("No projects available. Create a project first.", fontSize = 12.sp, color = ErrorCardText)
                    } else {
                        workspaces.forEach { ws ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LibMatteElevated)
                                    .clickable { copyAssetToTargetWorkspace(ws.name) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = ws.name,
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
                StudioSecondaryButton(text = "Cancel", onClick = { assetToUse = null })
            }
        )
    }

    if (assetToDelete != null) {
        val asset = assetToDelete!!
        AlertDialog(
            onDismissRequest = { assetToDelete = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Delete Asset", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ErrorCardText) },
            text = { Text("Delete \"${asset.name}\" permanently from library?", fontSize = 13.sp, color = TextPrimary) },
            confirmButton = {
                Button(
                    onClick = { confirmDeleteAsset() },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorCardBg, contentColor = ErrorCardText)
                ) {
                    Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                StudioSecondaryButton(text = "Cancel", onClick = { assetToDelete = null })
            }
        )
    }

    if (selectedStudioMedia != null) {
        val media = selectedStudioMedia!!
        val fullUrl = LinuxRuntimeManager.getFullMediaUrl(media.streamUrl)
        StudioMediaPlayerDialog(
            media = media,
            fullUrl = fullUrl,
            onDismiss = { selectedStudioMedia = null },
            onDelete = { target ->
                selectedStudioMedia = null
                deleteStudioMedia(listOf(target.relativePath))
            }
        )
    }

    if (pendingMediaDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingMediaDelete = emptyList() },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(
                    text = "Hapus ${pendingMediaDelete.size} file?",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "File akan dihapus permanen dari penyimpanan proyek.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioDangerRed)
                        .clickable {
                            val targets = pendingMediaDelete
                            pendingMediaDelete = emptyList()
                            deleteStudioMedia(targets)
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(text = "Hapus", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LibMatteBg)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(LibMatteElevated)
                        .clickable { pendingMediaDelete = emptyList() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(text = "Batal", fontSize = 12.sp, color = TextPrimary)
                }
            }
        )
    }

    // ── MODAL BOTTOM SHEET: FETCH / STOCK MEDIA ──────────────────────────────
    if (showStockSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStockSheet = false },
            containerColor = LibMatteCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header + sub-tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Fetch / Stock Media",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LibMatteElevated)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        listOf(0 to "Search", 1 to "URL Download").forEach { (idx, label) ->
                            val isSel = stockSheetTab == idx
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSel) Color(0xFF262A33) else Color.Transparent)
                                    .clickable { stockSheetTab = idx }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) TextPrimary else TextMuted
                                )
                            }
                        }
                    }
                }

                if (stockSheetTab == 0) {
                    // Search tab
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LibMatteElevated)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            BasicTextField(
                                value = stockQuery,
                                onValueChange = { stockQuery = it },
                                textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                                cursorBrush = SolidColor(TextPrimary),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (stockQuery.isEmpty()) {
                                        Text(text = "Cari video / foto / audio...", fontSize = 13.sp, color = TextMuted)
                                    }
                                    inner()
                                }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF2F4F8))
                                .clickable(enabled = !isSearchingStock && stockQuery.isNotBlank()) {
                                    performStockSearch()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (isSearchingStock) "..." else "Search",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0B0C0E)
                            )
                        }
                    }

                    // Source and type chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("video" to "Video", "photo" to "Photo", "audio" to "Audio").forEach { (tKey, tLabel) ->
                            val isSel = stockType == tKey
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) Color(0xFF262A33) else LibMatteElevated)
                                    .clickable { stockType = tKey; if (stockQuery.isNotBlank()) performStockSearch() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = tLabel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSel) TextPrimary else TextMuted
                                )
                            }
                        }
                    }

                    // Stock results grid (2 columns)
                    if (stockResults.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSearchingStock) "Mencari media stock..." else "Ketik kata kunci dan tekan Search.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(stockResults) { item ->
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(LibMatteElevated)
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (item.thumbnailUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = item.thumbnailUrl,
                                            contentDescription = item.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(75.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF0B0C0E))
                                        )
                                    }
                                    Text(
                                        text = item.title.ifBlank { item.id },
                                        fontSize = 11.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.provider.uppercase(),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            color = TextMuted
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFF2F4F8))
                                                .clickable(enabled = downloadingUrl != item.downloadUrl) {
                                                    downloadStockItem(item)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (downloadingUrl == item.downloadUrl) "..." else "Save",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0B0C0E)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // URL Download tab
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Media URL (YouTube, Vimeo, direct link)", fontSize = 11.sp, color = TextMuted)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(LibMatteElevated)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            BasicTextField(
                                value = urlDownloadInput,
                                onValueChange = { urlDownloadInput = it },
                                textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                                cursorBrush = SolidColor(TextPrimary),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (urlDownloadInput.isEmpty()) {
                                        Text(text = "https://...", fontSize = 13.sp, color = TextMuted)
                                    }
                                    inner()
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LibMatteElevated)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                BasicTextField(
                                    value = startTimestampInput,
                                    onValueChange = { startTimestampInput = it },
                                    textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary, fontFamily = FontFamily.Monospace),
                                    cursorBrush = SolidColor(TextPrimary),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        if (startTimestampInput.isEmpty()) {
                                            Text(text = "Start (00:00:10)", fontSize = 11.sp, color = TextMuted)
                                        }
                                        inner()
                                    }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LibMatteElevated)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                BasicTextField(
                                    value = endTimestampInput,
                                    onValueChange = { endTimestampInput = it },
                                    textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary, fontFamily = FontFamily.Monospace),
                                    cursorBrush = SolidColor(TextPrimary),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        if (endTimestampInput.isEmpty()) {
                                            Text(text = "End (00:00:25)", fontSize = 11.sp, color = TextMuted)
                                        }
                                        inner()
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isAudioOnlyDownload) Color(0xFF262A33) else LibMatteElevated)
                                    .clickable { isAudioOnlyDownload = !isAudioOnlyDownload }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isAudioOnlyDownload) "Audio Only: ON" else "Audio Only: OFF",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isAudioOnlyDownload) StudioSage else TextMuted
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFF2F4F8))
                                    .clickable(enabled = !isDownloadingUrl && urlDownloadInput.isNotBlank()) {
                                        downloadDirectUrl()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = if (isDownloadingUrl) "Mengunduh..." else "Download",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
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
}
