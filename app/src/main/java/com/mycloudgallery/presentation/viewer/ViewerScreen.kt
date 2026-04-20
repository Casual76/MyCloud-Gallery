package com.mycloudgallery.presentation.viewer

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.presentation.navigation.LocalSharedTransitionScope
import com.mycloudgallery.presentation.viewer.components.ExifBottomSheet
import com.mycloudgallery.presentation.viewer.components.VideoPlayer
import com.mycloudgallery.presentation.viewer.components.ZoomableImage
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ViewerScreen(
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current

    // Gestural dismissal state
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "viewerOffsetY"
    )
    val backgroundAlpha by animateFloatAsState(
        targetValue = (1f - (abs(offsetY) / 600f)).coerceIn(0f, 1f),
        label = "backgroundAlpha"
    )

    // Auto-hide overlay dopo 3 secondi
    LaunchedEffect(uiState.showOverlay) {
        if (uiState.showOverlay) {
            delay(3000)
            viewModel.toggleOverlay()
        }
    }

    // Torna indietro dopo eliminazione
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        offsetY += dragAmount
                    },
                    onDragEnd = {
                        if (abs(offsetY) > 300) {
                            onBack()
                        } else {
                            offsetY = 0f
                        }
                    }
                )
            }
            .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
            .graphicsLayer {
                val scale = (1f - (abs(offsetY) / 1000f)).coerceAtMost(1f)
                scaleX = scale
                scaleY = scale
            },
    ) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            uiState.error != null -> {
                Text(
                    text = uiState.error!!,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            uiState.currentItem != null -> {
                val item = uiState.currentItem!!

                // Contenuto media
                if (item.mediaType == MediaType.VIDEO) {
                    with(sharedTransitionScope) {
                        VideoPlayer(
                            uri = item.webDavPath,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (this != null) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = "media_${item.id}"),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else Modifier
                                )
                        )
                    }
                } else {
                    ZoomableImage(
                        model = item.thumbnailCachePath ?: "smb://${item.webDavPath}",
                        contentDescription = item.fileName,
                        onTap = { viewModel.toggleOverlay() },
                        modifier = Modifier.fillMaxSize(),
                        sharedTransitionKey = "media_${item.id}",
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }

                // Overlay superiore
                AnimatedVisibility(
                    visible = uiState.showOverlay && abs(offsetY) < 50,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    TopOverlay(
                        title = item.fileName,
                        onBack = onBack,
                        onInfoClick = viewModel::showExifSheet,
                    )
                }

                // Overlay inferiore
                AnimatedVisibility(
                    visible = uiState.showOverlay && abs(offsetY) < 50,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    BottomOverlay(
                        isFavorite = item.isFavorite,
                        onFavoriteClick = viewModel::toggleFavorite,
                        onDeleteClick = viewModel::moveToTrash,
                        onShareClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                val thumbPath = item.thumbnailCachePath
                                if (thumbPath != null) {
                                    val file = java.io.File(thumbPath)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } else {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, item.fileName)
                                }
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Condividi"))
                        },
                    )
                }

                // EXIF Bottom Sheet
                if (uiState.showExifSheet) {
                    ExifBottomSheet(
                        mediaItem = item,
                        onDismiss = viewModel::hideExifSheet,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopOverlay(
    title: String,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                ),
            )
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        var showMenu by remember { mutableStateOf(false) }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, "Menu", tint = Color.White)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Info EXIF") },
                onClick = { showMenu = false; onInfoClick() },
                leadingIcon = { Icon(Icons.Default.Info, null) },
            )
        }
    }
}

@Composable
private fun BottomOverlay(
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, "Elimina", tint = Color.White)
        }
        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Rimuovi preferiti" else "Aggiungi preferiti",
                tint = if (isFavorite) Color.Red else Color.White,
            )
        }
        IconButton(onClick = onShareClick) {
            Icon(Icons.Default.Share, "Condividi", tint = Color.White)
        }
    }
}
