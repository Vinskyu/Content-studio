package com.contentstudio.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioAccent
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioBorder
import com.contentstudio.app.ui.theme.StudioDangerRed
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.StudioSecondary
import com.contentstudio.app.ui.theme.StudioSmallButton
import com.contentstudio.app.ui.theme.StudioSurface
import com.contentstudio.app.ui.theme.StudioSurfaceElevated
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import com.contentstudio.app.ui.theme.studioLobbyBackTransition
import com.contentstudio.app.ui.theme.studioLobbyTransition
import kotlinx.coroutines.launch

enum class StudioRoom(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val isPrimaryGroup: Boolean = true
) {
    PROJECTS("projects", "Projects", "Video Productions", Icons.Outlined.Folder, true),
    AGENT("agent", "Agent", "AI Director & Scripting", Icons.Outlined.AutoAwesome, true),
    LIBRARY("library", "Library", "Channels, Templates & Media", Icons.Outlined.CollectionsBookmark, true),
    EXTENSIONS("extensions", "Skills & MCP", "Tools & Extensions", Icons.Outlined.Extension, false)
}

@Composable
fun MainStudioScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var activeRoom by remember { mutableStateOf(StudioRoom.PROJECTS) }
    var activeProject by remember { mutableStateOf<String?>(null) }
    var isQuickVoiceOpen by remember { mutableStateOf(false) }

    val isStreaming by ChatController.isStreaming.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val isServerRunning by LinuxRuntimeManager.isServerRunning.collectAsState()

    val isInProject = activeProject != null || isQuickVoiceOpen

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isInProject,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = StudioBg,
                drawerShape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .width(272.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    // Drawer Header
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "CONTENT STUDIO",
                            fontFamily = FontFamily.Default,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "V3.2",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Group 1: Projects, Agent, Library
                    Text(
                        text = "CORE STUDIOS",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )

                    StudioRoom.values().filter { it.isPrimaryGroup }.forEach { room ->
                        val isSelected = activeRoom == room && !isInProject
                        val isAgentRoom = room == StudioRoom.AGENT
                        val showStreamDot = isAgentRoom && isStreaming

                        val itemBg by animateColorAsState(
                            if (isSelected) StudioSurfaceElevated else Color.Transparent,
                            animationSpec = tween(180),
                            label = "roomBg"
                        )
                        val iconColor by animateColorAsState(
                            if (isSelected) TextPrimary else TextSecondary,
                            animationSpec = tween(180),
                            label = "roomIcon"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(itemBg)
                                .border(
                                    width = if (isSelected) 0.5.dp else 0.dp,
                                    color = if (isSelected) StudioBorder else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    activeRoom = room
                                    activeProject = null
                                    isQuickVoiceOpen = false
                                    coroutineScope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = room.icon,
                                    contentDescription = room.title,
                                    tint = iconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = room.title,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) TextPrimary else TextSecondary
                                    )
                                    Text(
                                        text = room.subtitle,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            if (showStreamDot) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(StudioAccent)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Thin Divider between primary rooms and extensions
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(StudioBorder)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Group 2: Skills & MCP
                    Text(
                        text = "EXTENSIONS",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )

                    StudioRoom.values().filter { !it.isPrimaryGroup }.forEach { room ->
                        val isSelected = activeRoom == room && !isInProject

                        val itemBg by animateColorAsState(
                            if (isSelected) StudioSurfaceElevated else Color.Transparent,
                            animationSpec = tween(180),
                            label = "roomBgExt"
                        )
                        val iconColor by animateColorAsState(
                            if (isSelected) TextPrimary else TextSecondary,
                            animationSpec = tween(180),
                            label = "roomIconExt"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(itemBg)
                                .border(
                                    width = if (isSelected) 0.5.dp else 0.dp,
                                    color = if (isSelected) StudioBorder else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    activeRoom = room
                                    activeProject = null
                                    isQuickVoiceOpen = false
                                    coroutineScope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = room.icon,
                                contentDescription = room.title,
                                tint = iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = room.title,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) TextPrimary else TextSecondary
                                )
                                Text(
                                    text = room.subtitle,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Drawer Footer: Server Status & Controls
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurface)
                            .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    text = if (isServerRunning) "Engine Online" else "Stopped",
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
                        }
                    }
                }
            }
        }
    ) {
        // CONTENT AREA (LOBBY vs RUANG PRODUKSI PROYEK)
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
            modifier = modifier
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
                    // LAPISAN 1: LOBI UTAMA (Projects, Agent, Library, Skills & MCP)
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
                                    .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp))
                                    .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(StudioSecondary)
                                        .clickable { coroutineScope.launch { drawerState.open() } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Menu,
                                        contentDescription = "Menu",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Text(
                                    text = activeRoom.title,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            if (isStreaming && activeRoom != StudioRoom.AGENT) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(StudioSurfaceElevated)
                                        .border(0.5.dp, StudioBorder, RoundedCornerShape(4.dp))
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
                                            fontFamily = FontFamily.Default,
                                            fontSize = 11.sp,
                                            color = TextPrimary
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
                                    onOpenSidebar = { coroutineScope.launch { drawerState.open() } },
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
}
