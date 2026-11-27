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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ExtensionMcpItem
import com.contentstudio.app.runtime.ExtensionSkillItem
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioBorder
import com.contentstudio.app.ui.theme.StudioCard
import com.contentstudio.app.ui.theme.StudioSage
import com.contentstudio.app.ui.theme.StudioSecondary
import com.contentstudio.app.ui.theme.StudioSurface
import com.contentstudio.app.ui.theme.StudioSurfaceElevated
import com.contentstudio.app.ui.theme.StudioTopBar
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
    onMenuClick: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Skills, 1: MCP
    var isLoading by remember { mutableStateOf(false) }

    val skills = remember { mutableStateListOf<ExtensionSkillItem>() }
    val mcpServers = remember { mutableStateListOf<ExtensionMcpItem>() }

    fun refreshData() {
        isLoading = true
        coroutineScope.launch {
            val s = withContext(Dispatchers.IO) {
                LinuxRuntimeManager.listExtensionSkills()
            }
            skills.clear()
            skills.addAll(s)
            val m = withContext(Dispatchers.IO) {
                LinuxRuntimeManager.listExtensionMcp()
            }
            mcpServers.clear()
            mcpServers.addAll(m)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        // TAB SWITCHER & ACTIONS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioSurface)
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
            val tab0Bg by animateColorAsState(if (selectedTab == 0) StudioSurfaceElevated else Color.Transparent, label = "tab0Bg")
            val tab0Text by animateColorAsState(if (selectedTab == 0) TextPrimary else TextSecondary, label = "tab0Text")

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tab0Bg)
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = "Skills",
                    tint = tab0Text,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Skills (${skills.size})",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = tab0Text
                )
            }

            val tab1Bg by animateColorAsState(if (selectedTab == 1) StudioSurfaceElevated else Color.Transparent, label = "tab1Bg")
            val tab1Text by animateColorAsState(if (selectedTab == 1) TextPrimary else TextSecondary, label = "tab1Text")

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tab1Bg)
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "MCP",
                    tint = tab1Text,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "MCP Servers (${mcpServers.size})",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Medium,
                    color = tab1Text
                )
            }
        }

        Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioSecondary)
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                    .clickable { refreshData() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // CONTENT AREA WITH SMOOTH ANIMATION
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
            },
            label = "extTabTransition",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { tab ->
            when (tab) {
                0 -> {
                    // SKILLS TAB
                    if (skills.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tidak ada custom skills aktif",
                                fontFamily = FontFamily.Default,
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(skills) { skill ->
                                StudioCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Extension,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = skill.name,
                                                fontFamily = FontFamily.Default,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }

                                        // Status Pill
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(StudioSage)
                                            )
                                            Text(
                                                text = "Active",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = StudioSage
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = skill.description,
                                        fontFamily = FontFamily.Default,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(StudioSurfaceElevated)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = skill.path,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // MCP TAB
                    if (mcpServers.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tidak ada server MCP terkonfigurasi",
                                fontFamily = FontFamily.Default,
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(mcpServers) { srv ->
                                StudioCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = srv.name,
                                                fontFamily = FontFamily.Default,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }

                                        // Type Badge
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(StudioSurfaceElevated)
                                                .border(0.5.dp, StudioBorder, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = srv.type,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    if (srv.target.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(StudioSurfaceElevated)
                                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = srv.target,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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
    }
}
