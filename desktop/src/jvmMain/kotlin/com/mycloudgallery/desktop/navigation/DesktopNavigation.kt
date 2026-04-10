package com.mycloudgallery.desktop.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.mycloudgallery.desktop.di.DesktopDependencies
import com.mycloudgallery.desktop.gallery.DesktopGalleryScreen
import com.mycloudgallery.desktop.search.DesktopSearchScreen
import com.mycloudgallery.desktop.settings.DesktopSettingsScreen
import com.mycloudgallery.desktop.viewer.DesktopViewerScreen

@Composable
fun DesktopNavigation(
    currentDestination: DesktopDestination,
    onDestinationSelected: (DesktopDestination) -> Unit,
    selectedMediaId: String?,
    onMediaSelected: (String) -> Unit,
    onMediaClosed: () -> Unit,
    deps: DesktopDependencies,
) {
    // Barra di navigazione laterale permanente
    NavigationRail(modifier = Modifier.fillMaxHeight()) {
        DesktopDestination.entries.forEach { dest ->
            NavigationRailItem(
                selected = currentDestination == dest,
                onClick = { onDestinationSelected(dest) },
                icon = { Text(dest.icon) },
                label = { Text(dest.label) },
            )
        }
    }

    // Contenuto principale
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    // Escape — chiudi viewer
                    event.key == Key.Escape && selectedMediaId != null -> {
                        onMediaClosed(); true
                    }
                    else -> false
                }
            }
    ) {
        if (selectedMediaId != null) {
            DesktopViewerScreen(
                mediaId = selectedMediaId,
                mediaRepository = deps.mediaRepository,
                onClose = onMediaClosed,
            )
        } else {
            when (currentDestination) {
                DesktopDestination.GALLERY -> DesktopGalleryScreen(
                    mediaRepository = deps.mediaRepository,
                    onMediaClick = onMediaSelected,
                )
                DesktopDestination.ALBUMS -> DesktopAlbumsScreen(deps = deps)
                DesktopDestination.SEARCH -> DesktopSearchScreen(
                    mediaRepository = deps.mediaRepository,
                    onMediaClick = onMediaSelected,
                )
                DesktopDestination.SETTINGS -> DesktopSettingsScreen(
                    settingsRepository = deps.settingsRepository,
                )
            }
        }
    }
}

@Composable
private fun DesktopAlbumsScreen(deps: DesktopDependencies) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("Album — Fase 3 desktop (TODO)")
    }
}
