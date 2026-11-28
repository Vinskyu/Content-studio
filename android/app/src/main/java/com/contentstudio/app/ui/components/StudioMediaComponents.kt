package com.contentstudio.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val mediaThumbCache = LruCache<String, Bitmap>(24)

@Composable
fun StudioMediaThumbnail(
    media: com.contentstudio.app.runtime.StudioMediaFile,
    fullUrl: String,
    modifier: Modifier = Modifier
) {
    when (media.type) {
        "image" -> {
            AsyncImage(
                model = fullUrl,
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }
        "video" -> {
            var bitmap by remember(fullUrl) { mutableStateOf<Bitmap?>(mediaThumbCache.get(fullUrl)) }
            LaunchedEffect(fullUrl) {
                if (bitmap == null) {
                    val bmp = withContext(Dispatchers.IO) {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(fullUrl, emptyMap())
                            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { r.release() } catch (_: Exception) {}
                        }
                    }
                    if (bmp != null) {
                        mediaThumbCache.put(fullUrl, bmp)
                        bitmap = bmp
                    }
                }
            }
            Box(modifier = modifier) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = media.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E2128)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "MP4", color = Color(0xFF8A919E), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color(0x66000000), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Play",
                        tint = Color(0xFFF2F4F8),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "MP4",
                    color = Color(0xFFF2F4F8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }
        "audio" -> {
            Box(
                modifier = modifier.background(Color(0xFF1E2128)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = media.name,
                        tint = Color(0xFF8A919E),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = media.name,
                        color = Color(0xFFF2F4F8),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
        }
        else -> {
            Box(
                modifier = modifier.background(Color(0xFF1E2128)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = media.name, color = Color(0xFF8A919E), fontSize = 10.sp, maxLines = 2)
            }
        }
    }
}

@Composable
fun StudioMediaPlayerDialog(
    media: com.contentstudio.app.runtime.StudioMediaFile,
    fullUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0C0E))
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFFF2F4F8))
            }
            when (media.type) {
                "video" -> {
                    val context = LocalContext.current
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
                        Text(text = media.name, color = Color(0xFFF2F4F8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                                tint = Color(0xFFF2F4F8),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text(text = media.name, color = Color(0xFFF2F4F8), modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
EOF
echo done