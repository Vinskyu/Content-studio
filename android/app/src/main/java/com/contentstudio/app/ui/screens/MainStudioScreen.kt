package com.contentstudio.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import android.widget.Toast
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioAccent
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.StudioSmallButton
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.studioLobbyBackTransition
import com.contentstudio.app.ui.theme.studioLobbyTransition

// Flat Matte Dark palette (V3.4.2)
private val MatteBar = Color(0xFF15171C)
private val MatteElevated = Color(0xFF1E2128)

enum class StudioRoom(
    val id: String,
    val title: String,
    val icon: ImageVector
) {
    AGENT("agent", "Agent", Icons.Outlined.AutoAwesome),
    PROJECTS("projects", "Projects", Icons.Outlined.VideoLibrary),
    LIBRARY("library", "Library", Icons.Outlined.CollectionsBookmark),
    EXTENSIONS("extensions", "Tools", Icons.Outlined.Extension)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainStudioScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var activeRoom by remember { mutableStateOf(StudioRoom.PROJECTS) }
    var activeProject by remember { mutableStateOf<String?>(null) }
    var isQuickVoiceOpen by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val isStreaming by ChatController.isStreaming.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val isServerRunning by LinuxRuntimeManager.isServerRunning.collectAsState()

    val isInProject = activeProject != null || isQuickVoiceOpen
    val isImeVisible = WindowInsets.isImeVisible

    // Bottom nav auto-hide: masuk ruang proyek atau keyboard terbuka di ruang Agent.
    val showBottomNav = !isInProject && !(activeRoom == StudioRoom.AGENT && isImeVisible)

    var lastBackPressMs by remember { mutableStateOf(0L) }

    BackHandler {
        when {
            showSettingsSheet -> showSettingsSheet = false
            isQuickVoiceOpen -> isQuickVoiceOpen = false
            activeProject != null -> activeProject = null
            activeRoom != StudioRoom.AGENT -> activeRoom = StudioRoom.AGENT
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000L) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = (activeProject to isQuickVoiceOpen),
                transitionSpec = {
                    if (targetState.first != null || targetState.second) {
                        studioLobbyTransition()
                    } else {
                        studioLobbyBackTransition()
                    }
                },
                label = "layerTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .background(StudioBg)
            ) { (proj, quickVo) ->
                when {
                    proj != null -> {
                        // LAPISAN 2: RUANG PRODUKSI PROYEK
                        ProjectBoardScreen(
                            workspaceName = proj,
                            onBack = { activeProject = null },
                            onNavigateToVoice = {
                                // Handled inside ProjectBoardScreen pipeline or direct switch
                            },
                            onNavigateToAgent = {
                                activeProject = null
                                activeRoom = StudioRoom.AGENT
                            }
                        )
                    }
                    quickVo -> {
                        // STANDALONE QUICK VOICE
                        VoiceoverScreen(
                            onBack = { isQuickVoiceOpen = false }
                        )
                    }
                    else -> {
                        // LAPISAN 1: LOBI UTAMA SATU TANGAN
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(StudioBg)
                        ) {
                            // SLIM 1-ROW TOP BAR (52.dp) — hidden in AGENT room because ChatScreen provides its own single 52dp top bar
                            if (activeRoom != StudioRoom.AGENT) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .background(StudioBg)
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = activeRoom.title,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (isStreaming) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MatteElevated)
                                                    .clickable { activeRoom = StudioRoom.AGENT }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(StudioAccent)
                                                    )
                                                    Text(
                                                        text = "Agent Busy",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        color = TextPrimary
                                                    )
                                                }
                                            }
                                        }

                                        // Settings 1 ketuk: status engine + restart/stop (dulu di footer sidebar).
                                        if (activeRoom == StudioRoom.EXTENSIONS) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { showSettingsSheet = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Settings,
                                                    contentDescription = "Settings",
                                                    tint = TextPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // ROOM SWITCHING ANIMATION
                            AnimatedContent(
                                targetState = activeRoom,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
                                },
                                label = "lobbyRoomTransition",
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) { room ->
                                when (room) {
                                    StudioRoom.PROJECTS -> WorkspaceScreen(
                                        onOpenProject = { activeProject = it },
                                        onOpenQuickVoice = { isQuickVoiceOpen = true }
                                    )
                                    StudioRoom.AGENT -> ChatScreen(
                                        onOpenBoard = {
                                            val ws = activeWorkspace
                                            if (ws.isNotBlank()) {
                                                activeProject = ws
                                            } else {
                                                activeRoom = StudioRoom.PROJECTS
                                            }
                                        },
                                        onNavigateToExtensions = { activeRoom = StudioRoom.EXTENSIONS }
                                    )
                                    StudioRoom.LIBRARY -> LibraryScreen()
                                    StudioRoom.EXTENSIONS -> ExtensionsScreen()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showBottomNav) {
            StudioBottomBar(
                activeRoom = activeRoom,
                onSelectRoom = {
                    activeRoom = it
                    activeProject = null
                    isQuickVoiceOpen = false
                }
            )
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = MatteBar
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Settings",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = TextPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isServerRunning) StudioSage else StudioDangerRed)
                    )
                    Text(
                        text = if (isServerRunning) "Engine Online" else "Engine Stopped",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isServerRunning) StudioSage else StudioDangerRed
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StudioSmallButton(
                        text = "Restart",
                        onClick = { LinuxRuntimeManager.restartServer(context) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSmallButton(
                        text = "Stop",
                        onClick = { LinuxRuntimeManager.stopServer() },
                        isDanger = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StudioBottomBar(
    activeRoom: StudioRoom,
    onSelectRoom: (StudioRoom) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MatteBar)
            .navigationBarsPadding()
            .height(62.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StudioRoom.values().forEach { room ->
            val isActive = activeRoom == room
            val tint = if (isActive) TextPrimary else TextMuted

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectRoom(room) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) MatteElevated else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = room.icon,
                        contentDescription = room.title,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = room.title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = tint
                )
            }
        }
    }
}
