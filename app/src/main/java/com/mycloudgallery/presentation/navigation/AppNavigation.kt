package com.mycloudgallery.presentation.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()

    val showBottomNav = currentEntry?.destination?.let { dest ->
        bottomNavRoutes.any { dest.hasRoute(it) }
    } ?: false

    // Physics-based spring specs for "Apple-level" fluidity
    val springSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val fadeSpec = spring<Float>(
        stiffness = Spring.StiffnessMediumLow
    )

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            Scaffold(
                bottomBar = {
                    if (showBottomNav) AppBottomNavBar(navController)
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = LoginRoute,
                    modifier = Modifier.padding(innerPadding),
                    enterTransition = {
                        slideInHorizontally(animationSpec = springSpec) { it } + 
                        fadeIn(animationSpec = fadeSpec)
                    },
                    exitTransition = {
                        slideOutHorizontally(animationSpec = springSpec) { -it / 3 } + 
                        fadeOut(animationSpec = fadeSpec)
                    },
                    popEnterTransition = {
                        slideInHorizontally(animationSpec = springSpec) { -it / 3 } + 
                        fadeIn(animationSpec = fadeSpec)
                    },
                    popExitTransition = {
                        slideOutHorizontally(animationSpec = springSpec) { it } + 
                        fadeOut(animationSpec = fadeSpec)
                    },
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
                            animatedVisibilityScope = this@composable,
                            onMediaClick = { mediaId -> 
                                navController.navigate(ViewerRoute(mediaId = mediaId)) 
                            },
                            onSearchClick = { navController.navigate(SearchRoute) },
                            onSettingsClick = { navController.navigate(SettingsRoute) },
                        )
                    }

                    composable<ViewerRoute>(
                        enterTransition = {
                            fadeIn(animationSpec = fadeSpec) + scaleIn(initialScale = 0.92f, animationSpec = fadeSpec)
                        },
                        exitTransition = {
                            fadeOut(animationSpec = fadeSpec) + scaleOut(targetScale = 0.92f, animationSpec = fadeSpec)
                        }
                    ) {
                        ViewerScreen(
                            animatedVisibilityScope = this@composable,
                            onBack = { navController.popBackStack() }
                        )
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
                            animatedVisibilityScope = this@composable,
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
    }
}
