package com.contentstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.data.PythonHost
import com.contentstudio.app.service.AgentService
import com.contentstudio.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class ProjectsSubTab(val title: String) {
    WORKSPACES("Akun"),
    ASSETS("Aset")
}

data class WorkspaceItem(
    val name: String,
    val status: String,
    val niche: String,
    val skillPath: String
)

data class AssetItem(
    val id: String,
    val name: String,
    val type: String,
    val filename: String,
    val path: String
)

@Composable
fun ProjectsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSubTab by remember { mutableStateOf(ProjectsSubTab.WORKSPACES) }

    val workspaces = remember { mutableStateListOf<WorkspaceItem>() }
    val assets = remember { mutableStateListOf<AssetItem>() }
    var selectedWsDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshWorkspaces() {
        scope.launch(Dispatchers.IO) {
            try {
                val res = PythonHost.listWorkspaces(context)
                val wsArray = res.optJSONArray("workspaces") ?: return@launch
                val list = mutableListOf<WorkspaceItem>()
                for (i in 0 until wsArray.length()) {
                    val obj = wsArray.getJSONObject(i)
                    list.add(
                        WorkspaceItem(
                            name = obj.optString("workspace"),
                            status = obj.optString("status").uppercase(),
                            niche = obj.optString("niche", "-"),
                            skillPath = obj.optString("skill_path", "")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    workspaces.clear()
                    workspaces.addAll(list)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun refreshAssets() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val res = PythonHost.listAssets(context)
                val arr = res.optJSONArray("assets") ?: return@launch
                val list = mutableListOf<AssetItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AssetItem(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            type = obj.optString("type").uppercase(),
                            filename = obj.optString("filename"),
                            path = obj.optString("path")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    assets.clear()
                    assets.addAll(list)
                }
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshWorkspaces()
        refreshAssets()
    }

    // Asset import launcher
    val importAssetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val rootDir = File(context.filesDir, "ContentStudio/assets/stickers")
                    if (!rootDir.exists()) rootDir.mkdirs()
                    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_asset.png"
                    val destFile = File(rootDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    PythonHost.scanAssets(context)
                    refreshAssets()
                } catch (_: Exception) {
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // HEADER & SUBTAB TOGGLE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROYEK & ASET",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ProjectsSubTab.values().forEach { tab ->
                    val isSel = tab == selectedSubTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSel) CardHighlight else SurfaceCard)
                            .clickable { selectedSubTab = tab }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = tab.title.uppercase(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) TextPrimary else TextSecondary
                        )
                    }
                }
            }
        }

        // KONTEN SUBTAB
        when (selectedSubTab) {
            ProjectsSubTab.WORKSPACES -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(workspaces) { ws ->
                        WorkspaceCard(
                            item = ws,
                            onSelect = {
                                AgentService.setWorkspace(ws.name)
                                AgentService.executeCommand(context, "/use ${ws.name}")
                            },
                            onViewDoc = { docTitle, docContent ->
                                selectedWsDoc = docTitle to docContent
                            }
                        )
                    }
                }
            }

            ProjectsSubTab.ASSETS -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    PythonHost.scanAssets(context)
                                    refreshAssets()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActionSolidBg,
                                contentColor = ActionSolidText
                            )
                        ) {
                            Text(
                                text = "PINDAI ULANG",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                importAssetLauncher.launch(arrayOf("image/*", "audio/*"))
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
                        ) {
                            Text(
                                text = "IMPOR ASET",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(assets) { asset ->
                            AssetCard(asset = asset)
                        }
                    }
                }
            }
        }
    }

    // Modal penampil dokumen workspace
    if (selectedWsDoc != null) {
        val (title, content) = selectedWsDoc!!
        AlertDialog(
            onDismissRequest = { selectedWsDoc = null },
            confirmButton = {
                Button(
                    onClick = { selectedWsDoc = null },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    )
                ) {
                    Text("TUTUP", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            title = {
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(BgDark)
                        .padding(8.dp)
                ) {
                    Text(
                        text = content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(6.dp)
        )
    }
}

@Composable
fun WorkspaceCard(
    item: WorkspaceItem,
    onSelect: () -> Unit,
    onViewDoc: (String, String) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "[${item.status}]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (item.status == "READY") TextPrimary else TextSecondary
                )
            }
        }

        Text(
            text = item.niche,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "AKTIFKAN",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            listOf("niche.md", "style.md", "laws.md").forEach { docName ->
                OutlinedButton(
                    onClick = {
                        val file = File(context.filesDir, "ContentStudio/workspaces/${item.name}/$docName")
                        val content = if (file.exists()) file.readText() else "File belum dibuat."
                        onViewDoc("${item.name}/$docName", content)
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = docName.replace(".md", "").uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun AssetCard(asset: AssetItem) {
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
            Text(
                text = asset.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = asset.path,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                color = TextSecondary
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
                text = "[${asset.type}]",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextPrimary
            )
        }
    }
}
