package com.contentstudio.app.ui.screens

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.DrawerValue
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
import com.contentstudio.app.ui.theme.StudioPrimary
import com.contentstudio.app.ui.theme.StudioSecondary
import com.contentstudio.app.ui.theme.StudioSmallButton
import com.contentstudio.app.ui.theme.StudioSurface
import com.contentstudio.app.ui.theme.StudioSurfaceElevated
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class StudioRoom(val id: String, val title: String, val subtitle: String) {
    AGENT("agent", "Agent", "AI Director & Scripting"),
    BOARD("board", "Board", "Timeline & Shot Production"),
    VOICE("voice", "Voice", "Voiceover & Audio Design"),
    LIBRARY("library", "Library", "Footage & Audio Vault")
}

@Composable
fun MainStudioScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var activeRoom by remember { mutableStateOf(StudioRoom.AGENT) }
    val isStreaming by ChatController.isStreaming.collectAsState()
    val activeWorkspace by LinuxRuntimeManager.activeWorkspace.collectAsState()
    val isServerRunning by LinuxRuntimeManager.isServerRunning.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
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
                            text = "Studio V2.2",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Active Workspace Pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioSurfaceElevated)
                            .border(0.5.dp, StudioBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(StudioAccent)
                            )
                            Column {
                                Text(
                                    text = "Active Workspace",
                                    fontFamily = FontFamily.Default,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = activeWorkspace.ifBlank { "default" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Navigation Rooms List
                    Text(
                        text = "ROOMS",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    StudioRoom.values().forEach { room ->
                        val isSelected = activeRoom == room
                        val isAgentRoom = room == StudioRoom.AGENT
                        val showStreamDot = isAgentRoom && isStreaming

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioSurfaceElevated else Color.Transparent)
                                .border(
                                    width = if (isSelected) 0.5.dp else 0.dp,
                                    color = if (isSelected) StudioBorder else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    activeRoom = room
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
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isSelected) StudioAccent else Color.Transparent)
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

                    Spacer(modifier = Modifier.weight(1f))

                    // Drawer Footer: Server Status & Controls
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioSurface)
                            .border(0.5.dp, StudioBorder, RoundedCornerShape(8.dp))
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
                                        .background(if (isServerRunning) Color(0xFF4ADE80) else Color(0xFF71717A))
                                )
                                Text(
                                    text = if (isServerRunning) "Server Online (18950)" else "Server Offline",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (isServerRunning) TextPrimary else TextMuted
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(StudioBg)
        ) {
            // SLIM 1-ROW TOP BAR (52.dp)
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
                    // Minimal 3-Line Hamburger Icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSecondary)
                            .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                            .clickable { coroutineScope.launch { drawerState.open() } }
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(modifier = Modifier.width(14.dp).height(1.5.dp).background(TextPrimary))
                            Box(modifier = Modifier.width(14.dp).height(1.5.dp).background(TextPrimary))
                            Box(modifier = Modifier.width(14.dp).height(1.5.dp).background(TextPrimary))
                        }
                    }

                    Column {
                        Text(
                            text = activeRoom.title,
                            fontFamily = FontFamily.Default,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = activeWorkspace.ifBlank { "default" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }

                // Right Status / Streaming Indicator
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

            // CONTENT AREA WITH ROOM SWITCHING ANIMATION
            AnimatedContent(
                targetState = activeRoom,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
                },
                label = "roomTransition",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { room ->
                when (room) {
                    StudioRoom.AGENT -> ChatScreen()
                    StudioRoom.BOARD -> WorkspaceScreen(
                        onNavigateToVoice = { activeRoom = StudioRoom.VOICE },
                        onNavigateToAgent = { activeRoom = StudioRoom.AGENT }
                    )
                    StudioRoom.VOICE -> VoiceoverScreen()
                    StudioRoom.LIBRARY -> LibraryScreen()
                }
            }
        }
    }
}
