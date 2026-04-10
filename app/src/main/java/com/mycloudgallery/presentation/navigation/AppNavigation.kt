package com.mycloudgallery.presentation.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.mycloudgallery.presentation.album.AlbumDetailScreen
import com.mycloudgallery.presentation.album.AlbumsScreen
import com.mycloudgallery.presentation.auth.LoginScreen
import com.mycloudgallery.presentation.duplicates.DuplicatesScreen
import com.mycloudgallery.presentation.gallery.GalleryScreen
import com.mycloudgallery.presentation.map.MapScreen
import com.mycloudgallery.presentation.search.SearchScreen
import com.mycloudgallery.presentation.settings.SettingsScreen
import com.mycloudgallery.presentation.shared.AppBottomNavBar
import com.mycloudgallery.presentation.trash.TrashScreen
import com.mycloudgallery.presentation.viewer.ViewerScreen

private val bottomNavRoutes = setOf(
    GalleryRoute::class,
    AlbumsRoute::class,
    SearchRoute::class,
    SettingsRoute::class,
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()

    val showBottomNav = currentEntry?.destination?.let { dest ->
        bottomNavRoutes.any { dest.hasRoute(it) }
    } ?: false

    Scaffold(
        bottomBar = {
            if (showBottomNav) AppBottomNavBar(navController)
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LoginRoute,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut() },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn() },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() },
        ) {
            composable<LoginRoute> {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(GalleryRoute) {
                            popUpTo(LoginRoute) { inclusive = true }
                        }
                    },
                )
            }

            composable<GalleryRoute> {
                GalleryScreen(
                    onMediaClick = { mediaId -> navController.navigate(ViewerRoute(mediaId = mediaId)) },
                    onSearchClick = { navController.navigate(SearchRoute) },
                    onSettingsClick = { navController.navigate(SettingsRoute) },
                )
            }

            composable<ViewerRoute> {
                ViewerScreen(onBack = { navController.popBackStack() })
            }

            composable<AlbumsRoute> {
                AlbumsScreen(
                    onAlbumClick = { albumId, isFavorites ->
                        navController.navigate(AlbumDetailRoute(albumId = albumId, isFavorites = isFavorites))
                    },
                )
            }

            composable<AlbumDetailRoute> {
                AlbumDetailScreen(
                    onBack = { navController.popBackStack() },
                    onMediaClick = { mediaId -> navController.navigate(ViewerRoute(mediaId = mediaId)) },
                )
            }

            composable<SearchRoute> {
                SearchScreen(
                    onMediaClick = { mediaId -> navController.navigate(ViewerRoute(mediaId = mediaId)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable<MapRoute> {
                MapScreen(
                    onMediaClick = { mediaId -> navController.navigate(ViewerRoute(mediaId = mediaId)) },
                )
            }

            composable<SettingsRoute> {
                SettingsScreen()
            }

            composable<TrashRoute> {
                TrashScreen(onBack = { navController.popBackStack() })
            }

            composable<DuplicatesRoute> {
                DuplicatesScreen(
                    onBack = { navController.popBackStack() },
                    onMediaClick = { mediaId -> navController.navigate(ViewerRoute(mediaId = mediaId)) },
                )
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = name)
    }
}
