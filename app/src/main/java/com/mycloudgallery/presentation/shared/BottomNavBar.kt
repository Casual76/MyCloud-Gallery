package com.mycloudgallery.presentation.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.mycloudgallery.presentation.navigation.AlbumsRoute
import com.mycloudgallery.presentation.navigation.GalleryRoute
import com.mycloudgallery.presentation.navigation.SearchRoute
import com.mycloudgallery.presentation.navigation.SettingsRoute

data class BottomNavItem<T : Any>(
    val route: T,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(GalleryRoute, "Galleria", Icons.Filled.Collections, Icons.Outlined.Collections),
    BottomNavItem(AlbumsRoute, "Album", Icons.Filled.PhotoAlbum, Icons.Outlined.PhotoAlbum),
    BottomNavItem(SearchRoute, "Cerca", Icons.Filled.Search, Icons.Outlined.Search),
    BottomNavItem(SettingsRoute, "Impostazioni", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppBottomNavBar(
    navController: NavController,
    viewModel: BottomNavViewModel = hiltViewModel(),
) {
    val currentEntry = navController.currentBackStackEntryAsState().value
    val pendingRequestsCount by viewModel.pendingRequestsCount.collectAsStateWithLifecycle()

    NavigationBar {
        bottomNavItems.forEach { item ->
            val selected = currentEntry?.destination?.hasRoute(item.route::class) == true
            val showBadge = item.route is AlbumsRoute && pendingRequestsCount > 0

            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    BadgedBox(
                        badge = {
                            if (showBadge) {
                                Badge { Text(pendingRequestsCount.toString()) }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                        )
                    }
                },
                label = { Text(item.label) },
            )
        }
    }
}
