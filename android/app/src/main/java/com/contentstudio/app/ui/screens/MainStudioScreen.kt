package com.contentstudio.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioDesignSystem
import com.contentstudio.app.ui.theme.studioLobbyBackTransition
import com.contentstudio.app.ui.theme.studioLobbyTransition

enum class StudioRoom(
    val id: String,
    val title: String,
    val icon: ImageVector
) {
    CHAT("chat", "Chat", Icons.Outlined.AutoAwesome),
    PROJECTS("projects", "Proyek", Icons.Outlined.VideoLibrary),
    GALLERY("gallery", "Galeri", Icons.Outlined.CollectionsBookmark),
    SETTINGS("settings", "Pengaturan", Icons.Outlined.Settings)
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

    val isStreaming by ChatController.isStreaming.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()

    val isInProject = activeProject != null || isQuickVoiceOpen
    val isImeVisible = WindowInsets.isImeVisible

    // Sembunyikan bottom nav saat berada di dalam proyek detail atau keyboard terbuka di ruang Chat
    val showBottomNav = !isInProject && !(activeRoom == StudioRoom.CHAT && isImeVisible)

    var lastBackPressMs by remember { mutableStateOf(0L) }

    BackHandler {
        when {
            isQuickVoiceOpen -> isQuickVoiceOpen = false
            activeProject != null -> activeProject = null
            activeRoom != StudioRoom.CHAT -> activeRoom = StudioRoom.CHAT
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
            .background(StudioDesignSystem.BgCanvas)
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
                    .background(StudioDesignSystem.BgCanvas)
            ) { (proj, quickVo) ->
                when {
                    proj != null -> {
                        // LAPISAN 2: RUANG PRODUKSI PROYEK
                        ProjectBoardScreen(
                            workspaceName = proj,
                            onBack = { activeProject = null },
                            onNavigateToVoice = {
                                // Direct voice navigate
                            },
                            onNavigateToAgent = {
                                activeProject = null
                                activeRoom = StudioRoom.CHAT
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
                        // LAPISAN 1: LOBI UTAMA 4 TAB
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(StudioDesignSystem.BgCanvas)
                        ) {
                            // Top Bar indikator jika ada agent streaming di latar
                            if (activeRoom != StudioRoom.CHAT && isStreaming) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .background(StudioDesignSystem.BgSurface)
                                        .clickable { activeRoom = StudioRoom.CHAT }
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(StudioDesignSystem.AccentBlue)
                                    )
                                    Text(
                                        text = "AI Agent sedang memproses tugas... Ketuk untuk melihat",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = StudioDesignSystem.TextPrimary
                                    )
                                }
                            }

                            // ROOM SWITCHING
                            AnimatedContent(
                                targetState = activeRoom,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
                                },
                                label = "lobbyRoomTransition",
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) { room ->
                                when (room) {
                                    StudioRoom.CHAT -> ChatScreen(
                                        onOpenBoard = {
                                            val ws = activeWorkspace
                                            if (ws.isNotBlank()) {
                                                activeProject = ws
                                            } else {
                                                activeRoom = StudioRoom.PROJECTS
                                            }
                                        },
                                        onNavigateToExtensions = { activeRoom = StudioRoom.SETTINGS },
                                        onOpenSettings = { activeRoom = StudioRoom.SETTINGS }
                                    )
                                    StudioRoom.PROJECTS -> WorkspaceScreen(
                                        onOpenProject = { activeProject = it },
                                        onOpenQuickVoice = { isQuickVoiceOpen = true }
                                    )
                                    StudioRoom.GALLERY -> LibraryScreen()
                                    StudioRoom.SETTINGS -> SettingsScreen()
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
}

@Composable
private fun StudioBottomBar(
    activeRoom: StudioRoom,
    onSelectRoom: (StudioRoom) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioDesignSystem.BgCanvas)
    ) {
        // Divider 0.5.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(StudioDesignSystem.DividerSubtle)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StudioRoom.values().forEach { room ->
                val isActive = activeRoom == room
                val contentColor = if (isActive) StudioDesignSystem.TextPrimary else StudioDesignSystem.TextMuted

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(StudioDesignSystem.ShapeChip)
                        .clickable { onSelectRoom(room) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(StudioDesignSystem.ShapeChip)
                            .background(if (isActive) StudioDesignSystem.BgElevated else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = room.icon,
                            contentDescription = room.title,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = room.title,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = contentColor
                    )
                }
            }
        }
    }
}
