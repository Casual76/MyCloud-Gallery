package com.mycloudgallery.presentation.gallery.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType

@Composable
fun MediaThumbnail(
    mediaItem: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "borderColor",
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = borderColor,
                shape = shape,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        // Thumbnail
        AsyncImage(
            model = mediaItem.thumbnailCachePath ?: mediaItem.webDavPath,
            contentDescription = mediaItem.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Badge video (in basso a sinistra)
        if (mediaItem.mediaType == MediaType.VIDEO) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircleFilled,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                mediaItem.videoDuration?.let { duration ->
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatDuration(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // Badge preferito (in alto a destra)
        if (mediaItem.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }

        // Checkbox selezione
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selezionato",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        "%d:%02d:%02d".format(hours, mins, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
