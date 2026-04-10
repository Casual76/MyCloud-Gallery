package com.mycloudgallery.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mycloudgallery.desktop.auth.DesktopLoginScreen
import com.mycloudgallery.desktop.di.DesktopDependencies
import com.mycloudgallery.desktop.navigation.DesktopDestination
import com.mycloudgallery.desktop.navigation.DesktopNavigation

@Composable
fun DesktopApp(deps: DesktopDependencies) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var isLoggedIn by remember { mutableStateOf(deps.authRepository.isLoggedIn()) }

            if (!isLoggedIn) {
                DesktopLoginScreen(
                    authRepository = deps.authRepository,
                    onLoginSuccess = { isLoggedIn = true },
                )
            } else {
                var currentDestination by remember { mutableStateOf(DesktopDestination.GALLERY) }
                var selectedMediaId by remember { mutableStateOf<String?>(null) }

                Row(modifier = Modifier.fillMaxSize()) {
                    DesktopNavigation(
                        currentDestination = currentDestination,
                        onDestinationSelected = { currentDestination = it },
                        selectedMediaId = selectedMediaId,
                        onMediaSelected = { selectedMediaId = it },
                        onMediaClosed = { selectedMediaId = null },
                        deps = deps,
                    )
                }
            }
        }
    }
}
