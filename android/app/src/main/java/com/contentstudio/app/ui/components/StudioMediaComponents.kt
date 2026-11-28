package com.contentstudio.app.ui.components

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

private val MediaCanvas = Color(0xFF0B0C0E)
private val MediaSurface = Color(0xFF15171C)
private val MediaActive = Color(0xFF1E2128)
private val MediaPrimary = Color(0xFFF2F4F8)
private val MediaMuted = Color(0xFF8A919E)
private val MediaDanger = Color(0xFFD96B6B)

private fun mediaCategoryLabel(category: String): String = when (category) {
    "render" -> "RENDER"
    "scene" -> "SCENE"
    "asset" -> "ASSET"
    "audio" -> "AUDIO"
    "doc" -> "DOC"
    else -> "FILE"
}

@Composable
fun StudioMediaThumbnail(
    media: com.contentstudio.app.runtime.StudioMediaFile,
    fullUrl: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (media.type) {
            "image", "video" -> {
                AsyncImage(
                    model = fullUrl,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MediaActive)
                )
            }
            "audio" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MediaActive),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = media.name,
                        tint = MediaMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MediaActive),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = media.name, color = MediaMuted, fontSize = 10.sp, maxLines = 2)
                }
            }
        }

        if (media.type == "video") {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .background(Color(0x55000000), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = "Play",
                    tint = MediaPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = "MP4",
                color = MediaPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE6050608))
                    )
                )
                .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 5.dp)
        ) {
            Column {
                Text(
                    text = media.name,
                    color = MediaPrimary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = mediaCategoryLabel(media.category),
                    color = MediaMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun StudioMediaPlayerDialog(
    media: com.contentstudio.app.runtime.StudioMediaFile,
    fullUrl: String,
    onDismiss: () -> Unit,
    onDelete: ((com.contentstudio.app.runtime.StudioMediaFile) -> Unit)? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val deleteAction = onDelete

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MediaCanvas)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close", tint = MediaPrimary)
            }

            when (media.type) {
                "video" -> {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(fullUrl))
                                setOnPreparedListener { mp -> mp.isLooping = false; start() }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                    )
                }
                "image" -> {
                    AsyncImage(
                        model = fullUrl,
                        contentDescription = media.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .align(Alignment.Center)
                    )
                }
                "audio" -> {
                    var isPlaying by remember { mutableStateOf(false) }
                    val mediaPlayer = remember { MediaPlayer() }
                    DisposableEffect(fullUrl) {
                        try {
                            mediaPlayer.reset()
                            mediaPlayer.setDataSource(fullUrl)
                            mediaPlayer.prepareAsync()
                            mediaPlayer.setOnPreparedListener { mp -> mp.start(); isPlaying = true }
                            mediaPlayer.setOnCompletionListener { isPlaying = false }
                        } catch (_: Exception) {}
                        onDispose {
                            try { mediaPlayer.release() } catch (_: Exception) {}
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = media.name, color = MediaPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(16.dp))
                        IconButton(onClick = {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = MediaPrimary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text(text = media.name, color = MediaPrimary, modifier = Modifier.align(Alignment.Center))
                }
            }

            if (onDelete != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(MediaDanger, RoundedCornerShape(8.dp))
                            .clickable { showDeleteConfirm = true }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Delete",
                            color = MediaCanvas,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && deleteAction != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MediaSurface,
            shape = RoundedCornerShape(10.dp),
            title = {
                Text(text = "Hapus file ini?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MediaPrimary)
            },
            text = {
                Text(text = media.name, fontSize = 12.sp, color = MediaMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .background(MediaDanger, RoundedCornerShape(6.dp))
                        .clickable {
                            showDeleteConfirm = false
                            deleteAction.invoke(media)
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(text = "Hapus", color = MediaCanvas, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .background(MediaActive, RoundedCornerShape(6.dp))
                        .clickable { showDeleteConfirm = false }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(text = "Batal", color = MediaPrimary, fontSize = 12.sp)
                }
            }
        )
    }
}
