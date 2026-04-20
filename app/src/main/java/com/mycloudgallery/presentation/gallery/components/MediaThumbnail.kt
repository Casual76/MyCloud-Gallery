package com.mycloudgallery.presentation.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.presentation.navigation.LocalSharedTransitionScope

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MediaThumbnail(
    mediaItem: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    
    // Expressive shape: larger corners, and slightly more rounded when selected
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 24.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "cornerRadius",
    )
    val shape = RoundedCornerShape(cornerRadius)
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "scale",
    )
    
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "borderColor",
    )

    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .scale(scale)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = borderColor,
                shape = shape,
            )
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        tonalElevation = if (isSelected) 8.dp else 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Shared Element Image
            with(sharedTransitionScope) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(mediaItem.thumbnailCachePath ?: "smb://${mediaItem.webDavPath}")
                        .crossfade(true)
                        .build(),
                    placeholder = ColorPainter(placeholderColor),
                    error = painterResource(android.R.drawable.ic_menu_report_image),
                    contentDescription = mediaItem.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (this != null && animatedVisibilityScope != null) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "media_${mediaItem.id}"),
                                    animatedVisibilityScope = animatedVisibilityScope!!
                                )
                            } else Modifier
                        ),
                )
            }

            // Video Badge (Expressive pill)
            if (mediaItem.mediaType == MediaType.VIDEO) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    shape = CircleShape,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        mediaItem.videoDuration?.let { duration ->
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = formatDuration(duration),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // Favorite Badge
            if (mediaItem.isFavorite) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(14.dp),
                    )
                }
            }

            // Selection Checkbox with Animation
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary 
                            else Color.Black.copy(alpha = 0.3f),
                    shape = CircleShape,
                    border = if (!isSelected) border(1.dp, Color.White) else null,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selezionato",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun border(width: androidx.compose.ui.unit.Dp, color: Color) = 
    androidx.compose.foundation.BorderStroke(width, color)

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
