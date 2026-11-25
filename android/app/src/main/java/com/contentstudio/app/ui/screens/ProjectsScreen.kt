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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
    ACCOUNTS("Akun"),
    ASSETS("Aset"),
    SKILLS_MCP("Skill/MCP")
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

data class SkillItem(
    val slug: String,
    val name: String,
    val description: String,
    val isDefault: Boolean
)

data class McpServerItem(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: String
)

@Composable
fun ProjectsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSubTab by remember { mutableStateOf(ProjectsSubTab.ACCOUNTS) }

    val workspaces = remember { mutableStateListOf<WorkspaceItem>() }
    val assets = remember { mutableStateListOf<AssetItem>() }
    val skills = remember { mutableStateListOf<SkillItem>() }
    val mcpServers = remember { mutableStateListOf<McpServerItem>() }

    val activeWorkspace by AgentService.currentWorkspace.collectAsState()

    var workspaceError by remember { mutableStateOf<String?>(null) }
    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var dnaEditorWs by remember { mutableStateOf<String?>(null) }
    var deleteWorkspaceTarget by remember { mutableStateOf<String?>(null) }

    var selectedAssetCategory by remember { mutableStateOf("SEMUA") }
    var isLoading by remember { mutableStateOf(false) }

    var skillInstallUrl by remember { mutableStateOf("") }
    var skillInstallStatus by remember { mutableStateOf<String?>(null) }

    var showAddMcpForm by remember { mutableStateOf(false) }
    var mcpNameInput by remember { mutableStateOf("") }
    var mcpUrlInput by remember { mutableStateOf("") }
    var mcpHeadersInput by remember { mutableStateOf("{}") }
    var mcpStatus by remember { mutableStateOf<String?>(null) }

    fun refreshWorkspaces() {
        scope.launch(Dispatchers.IO) {
            try {
                val res = PythonHost.listWorkspaces(context)
                if (res.optString("status") == "error") {
                    val err = res.optString("message", "Gagal membaca daftar akun.")
                    withContext(Dispatchers.Main) {
                        workspaceError = err
                    }
                    return@launch
                }
                val wsArray = res.optJSONArray("workspaces")
                if (wsArray == null) {
                    withContext(Dispatchers.Main) {
                        workspaceError = "Format JSON workspaces tidak valid."
                    }
                    return@launch
                }
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
                    workspaceError = null
                    workspaces.clear()
                    workspaces.addAll(list)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    workspaceError = "Gagal memuat akun: ${e.message ?: "Error tidak diketahui"}"
                }
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

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            try {
                val res = PythonHost.listSkills(context)
                val arr = res.optJSONArray("skills") ?: return@launch
                val list = mutableListOf<SkillItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        SkillItem(
                            slug = obj.optString("slug", obj.optString("name")),
                            name = obj.optString("name"),
                            description = obj.optString("description", "-"),
                            isDefault = obj.optBoolean("is_default", false)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    skills.clear()
                    skills.addAll(list)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun refreshMcpServers() {
        scope.launch(Dispatchers.IO) {
            try {
                val res = PythonHost.listMcpServers(context)
                val arr = res.optJSONArray("servers") ?: return@launch
                val list = mutableListOf<McpServerItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        McpServerItem(
                            name = obj.optString("name"),
                            url = obj.optString("url"),
                            enabled = obj.optBoolean("enabled", true),
                            headers = obj.optJSONObject("headers")?.toString() ?: "{}"
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    mcpServers.clear()
                    mcpServers.addAll(list)
                }
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshWorkspaces()
        refreshAssets()
        refreshSkills()
        refreshMcpServers()
    }

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
                text = "MANAJER KONTEN",
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
                            .clickable {
                                selectedSubTab = tab
                                if (tab == ProjectsSubTab.SKILLS_MCP) {
                                    refreshSkills()
                                    refreshMcpServers()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
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
            ProjectsSubTab.ACCOUNTS -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DAFTAR AKUN (${workspaces.size})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )

                        Button(
                            onClick = { showCreateAccountDialog = true },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActionSolidBg,
                                contentColor = ActionSolidText
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "+ AKUN",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (workspaceError != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardHighlight)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "[ERR] $workspaceError",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(workspaces) { ws ->
                            val isCurrentlyActive = ws.name.equals(activeWorkspace, ignoreCase = true)
                            WorkspaceCard(
                                item = ws,
                                isActive = isCurrentlyActive,
                                onSelect = {
                                    scope.launch(Dispatchers.IO) {
                                        PythonHost.switchWorkspace(context, ws.name)
                                        AgentService.setWorkspace(ws.name)
                                        AgentService.clearMessages()
                                        AgentService.refreshSessionState(context)
                                        refreshWorkspaces()
                                    }
                                },
                                onDnaEditor = {
                                    dnaEditorWs = ws.name
                                },
                                onDelete = {
                                    deleteWorkspaceTarget = ws.name
                                }
                            )
                        }
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

                    // KATEGORI FILTER CHIPS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("SEMUA", "STIKER", "MEME", "SFX", "BG").forEach { cat ->
                            val isSel = cat == selectedAssetCategory
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSel) CardHighlight else SurfaceCard)
                                    .border(1.dp, if (isSel) TextSecondary else BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { selectedAssetCategory = cat }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = cat,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) TextPrimary else TextSecondary
                                )
                            }
                        }
                    }

                    val filteredAssets = assets.filter { asset ->
                        when (selectedAssetCategory) {
                            "SEMUA" -> true
                            "STIKER" -> asset.type.contains("STICKER") || asset.type.contains("STIKER")
                            "MEME" -> asset.type.contains("MEME")
                            "SFX" -> asset.type.contains("SFX") || asset.type.contains("AUDIO")
                            "BG" -> asset.type.contains("BG") || asset.type.contains("BACKGROUND") || asset.type.contains("CLIP")
                            else -> true
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredAssets) { asset ->
                            AssetCard(asset = asset)
                        }
                    }
                }
            }

            ProjectsSubTab.SKILLS_MCP -> {
                val subScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(subScrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SEKSI 1: SKILL TERPASANG
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "SKILL TERPASANG (${skills.size})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BgDark)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                BasicTextField(
                                    value = skillInstallUrl,
                                    onValueChange = { skillInstallUrl = it },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextPrimary
                                    ),
                                    cursorBrush = SolidColor(TextPrimary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (skillInstallUrl.isEmpty()) {
                                    Text(
                                        text = "URL GitHub / ZIP Skill...",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (skillInstallUrl.isNotBlank()) {
                                        scope.launch(Dispatchers.IO) {
                                            val res = PythonHost.installSkill(context, skillInstallUrl.trim())
                                            val st = res.optString("status")
                                            val msg = res.optString("msg", res.optString("error", "Selesai"))
                                            withContext(Dispatchers.Main) {
                                                skillInstallStatus = "[$st] $msg"
                                                skillInstallUrl = ""
                                            }
                                            refreshSkills()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActionSolidBg,
                                    contentColor = ActionSolidText
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "INSTAL SKILL",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (skillInstallStatus != null) {
                            Text(
                                text = skillInstallStatus!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }

                        skills.forEach { sk ->
                            SkillCard(
                                skill = sk,
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        PythonHost.deleteSkill(context, sk.slug)
                                        refreshSkills()
                                    }
                                }
                            )
                        }
                    }

                    // SEKSI 2: REMOTE MCP SERVERS
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
                                text = "REMOTE MCP SERVERS (${mcpServers.size})",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                    .clickable { showAddMcpForm = !showAddMcpForm }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (showAddMcpForm) "[TUTUP]" else "[+ SERVER MCP]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextPrimary
                                )
                            }
                        }

                        if (showAddMcpForm) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BgDark)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "TAMBAH MCP SERVER BARU",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )

                                PlainInputField(
                                    label = "NAMA SERVER:",
                                    value = mcpNameInput,
                                    onValueChange = { mcpNameInput = it }
                                )

                                PlainInputField(
                                    label = "URL (SSE / HTTP):",
                                    value = mcpUrlInput,
                                    onValueChange = { mcpUrlInput = it }
                                )

                                PlainInputField(
                                    label = "HEADER AUTH (JSON):",
                                    value = mcpHeadersInput,
                                    onValueChange = { mcpHeadersInput = it }
                                )

                                Button(
                                    onClick = {
                                        if (mcpNameInput.isNotBlank() && mcpUrlInput.isNotBlank()) {
                                            scope.launch(Dispatchers.IO) {
                                                PythonHost.saveMcpServer(
                                                    context,
                                                    mcpNameInput.trim(),
                                                    mcpUrlInput.trim(),
                                                    mcpHeadersInput.trim(),
                                                    true
                                                )
                                                withContext(Dispatchers.Main) {
                                                    mcpNameInput = ""
                                                    mcpUrlInput = ""
                                                    mcpHeadersInput = "{}"
                                                    showAddMcpForm = false
                                                    mcpStatus = "[OK] Server MCP berhasil disimpan."
                                                }
                                                refreshMcpServers()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActionSolidBg,
                                        contentColor = ActionSolidText
                                    )
                                ) {
                                    Text(
                                        text = "SIMPAN SERVER MCP",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (mcpStatus != null) {
                            Text(
                                text = mcpStatus!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }

                        mcpServers.forEach { srv ->
                            McpCard(
                                server = srv,
                                onToggle = {
                                    scope.launch(Dispatchers.IO) {
                                        PythonHost.saveMcpServer(
                                            context,
                                            srv.name,
                                            srv.url,
                                            srv.headers,
                                            !srv.enabled
                                        )
                                        refreshMcpServers()
                                    }
                                },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        PythonHost.deleteMcpServer(context, srv.name)
                                        refreshMcpServers()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // DIALOG TAMBAH AKUN / WORKSPACE
    if (showCreateAccountDialog) {
        CreateAccountDialog(
            onDismiss = { showCreateAccountDialog = false },
            onCreate = { name, niche, style, laws ->
                scope.launch(Dispatchers.IO) {
                    PythonHost.createWorkspace(context, name, niche, style, laws)
                    withContext(Dispatchers.Main) {
                        showCreateAccountDialog = false
                    }
                    refreshWorkspaces()
                }
            }
        )
    }

    // DIALOG DNA EDITOR
    if (dnaEditorWs != null) {
        DnaEditorDialog(
            workspaceName = dnaEditorWs!!,
            onDismiss = { dnaEditorWs = null }
        )
    }

    // DIALOG KONFIRMASI HAPUS WORKSPACE
    if (deleteWorkspaceTarget != null) {
        val target = deleteWorkspaceTarget!!
        AlertDialog(
            onDismissRequest = { deleteWorkspaceTarget = null },
            title = {
                Text(
                    text = "KONFIRMASI HAPUS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Hapus workspace '$target' secara permanen beserta semua konfigurasi dan DNA di dalamnya?",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            PythonHost.deleteWorkspace(context, target)
                            withContext(Dispatchers.Main) {
                                deleteWorkspaceTarget = null
                            }
                            refreshWorkspaces()
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    )
                ) {
                    Text("HAPUS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteWorkspaceTarget = null },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
                ) {
                    Text("BATAL", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
    isActive: Boolean,
    onSelect: () -> Unit,
    onDnaEditor: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceCard)
            .border(1.dp, if (isActive) TextSecondary else BorderSubtle, RoundedCornerShape(6.dp))
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
                    text = if (isActive) "[AKTIF]" else "[${item.status}]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (isActive) TextPrimary else TextSecondary
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
                enabled = !isActive,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) CardHighlight else ActionSolidBg,
                    contentColor = if (isActive) TextSecondary else ActionSolidText
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isActive) "AKTIF" else "PAKAI",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onDnaEditor,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "DNA EDITOR",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "HAPUS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun CreateAccountDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, niche: String, style: String, laws: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var niche by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var laws by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "BUAT AKUN / WORKSPACE BARU",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlainInputField(label = "NAMA AKUN (cth: bola_update):", value = name, onValueChange = { name = it })
                PlainInputField(label = "NICHE (cth: Berita Sepakbola Shorts):", value = niche, onValueChange = { niche = it })
                PlainInputField(label = "ATURAN GAYA / STYLE (Opsional):", value = style, onValueChange = { style = it })
                PlainInputField(label = "HUKUM AKUN / LAWS (Opsional):", value = laws, onValueChange = { laws = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name.trim().lowercase().replace(" ", "_"), niche.trim(), style.trim(), laws.trim())
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                )
            ) {
                Text("BUAT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
            ) {
                Text("BATAL", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(6.dp)
    )
}

@Composable
fun DnaEditorDialog(
    workspaceName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs = listOf("niche.md", "style.md", "laws.md", "SKILL.md")
    var selectedDoc by remember { mutableStateOf("niche.md") }
    var editorContent by remember { mutableStateOf("") }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var isLoadingDoc by remember { mutableStateOf(false) }

    fun loadDoc(docName: String) {
        scope.launch(Dispatchers.IO) {
            isLoadingDoc = true
            try {
                val res = PythonHost.readWorkspaceFile(context, workspaceName, docName)
                val text = res.optString("content", "")
                withContext(Dispatchers.Main) {
                    editorContent = text
                    saveStatus = null
                }
            } catch (_: Exception) {
            } finally {
                isLoadingDoc = false
            }
        }
    }

    LaunchedEffect(selectedDoc) {
        loadDoc(selectedDoc)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "DNA EDITOR: ${workspaceName.uppercase()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // TAB PILIHAN DOKUMEN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    docs.forEach { doc ->
                        val isSel = doc == selectedDoc
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) CardHighlight else SurfaceCard)
                                .border(1.dp, if (isSel) TextSecondary else BorderSubtle, RoundedCornerShape(4.dp))
                                .clickable { selectedDoc = doc }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = doc.replace(".md", "").uppercase(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) TextPrimary else TextSecondary
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = editorContent,
                        onValueChange = { editorContent = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }

                if (saveStatus != null) {
                    Text(
                        text = saveStatus!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val res = PythonHost.saveWorkspaceFile(context, workspaceName, selectedDoc, editorContent)
                        val st = res.optString("status")
                        withContext(Dispatchers.Main) {
                            saveStatus = if (st == "ok") "[OK] $selectedDoc tersimpan." else "[GAGAL] Simpan gagal."
                        }
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                )
            ) {
                Text("SIMPAN", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
            ) {
                Text("TUTUP", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(6.dp)
    )
}

@Composable
fun SkillCard(
    skill: SkillItem,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BgDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = skill.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = if (skill.isDefault) "[DEFAULT]" else "[${skill.slug}]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            Text(
                text = skill.description,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        if (!skill.isDefault) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "HAPUS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
fun McpCard(
    server: McpServerItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BgDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = server.url,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { onToggle() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (server.enabled) "AKTIF" else "NONAKTIF",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (server.enabled) TextPrimary else TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "HAPUS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = TextPrimary
                )
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
