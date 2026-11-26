package com.contentstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.ChatController
import com.contentstudio.app.ui.theme.StudioAccent
import com.contentstudio.app.ui.theme.StudioBg
import com.contentstudio.app.ui.theme.StudioBorder
import com.contentstudio.app.ui.theme.StudioPrimary
import com.contentstudio.app.ui.theme.TextMuted

enum class StudioTab(val label: String) {
    STUDIO("Studio"),
    LIBRARY("Library"),
    VOICE("Voice"),
    AGENT("Agent")
}

@Composable
fun MainStudioScreen(
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isStreaming by ChatController.isStreaming.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        // CONTENT AREA (WEIGHT 1)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> WorkspaceScreen(onNavigateToVoice = { selectedTab = 2 })
                1 -> LibraryScreen()
                2 -> VoiceoverScreen()
                3 -> ChatScreen()
            }
        }

        // BOTTOM NAVIGATION BAR (FLAT MATTE DARK, 56.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(StudioBg)
                .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp)),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioTab.values().forEachIndexed { index, tab ->
                val isSelected = selectedTab == index
                val isAgentTab = index == 3
                val showStreamDot = isAgentTab && isStreaming && !isSelected

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clickable { selectedTab = index },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top indicator line 2.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) StudioPrimary else StudioBg)
                    )

                    // Tab Label + Streaming Dot
                    Row(
                        modifier = Modifier.padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tab.label,
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) StudioPrimary else TextMuted,
                            letterSpacing = 0.5.sp
                        )

                        if (showStreamDot) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(StudioAccent)
                            )
                        }
                    }
                }
            }
        }
    }
}
