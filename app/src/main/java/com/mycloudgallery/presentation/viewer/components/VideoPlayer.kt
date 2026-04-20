package com.mycloudgallery.presentation.viewer.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.mycloudgallery.core.network.media3.SmbDataSourceFactory
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VideoPlayerEntryPoint {
    fun smbDataSourceFactory(): SmbDataSourceFactory
}

/**
 * Player video fullscreen con ExoPlayer (Media3).
 * Supporta streaming SMB per video dal NAS.
 */
@Composable
fun VideoPlayer(
    uri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, VideoPlayerEntryPoint::class.java)
    }
    val smbDataSourceFactory = entryPoint.smbDataSourceFactory()

    val exoPlayer = remember {
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(smbDataSourceFactory)

        // Aggressive Buffering for High-Latency NAS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,   // Min buffer: 50s
                120_000,  // Max buffer: 120s
                5_000,    // Playback start buffer: 5s
                10_000    // Re-buffer duration: 10s
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
