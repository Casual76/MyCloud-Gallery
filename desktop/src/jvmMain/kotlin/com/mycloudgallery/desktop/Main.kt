package com.mycloudgallery.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.mycloudgallery.desktop.di.DesktopDependencies

fun main() = application {
    val deps = DesktopDependencies()

    Window(
        onCloseRequest = ::exitApplication,
        title = "MyCloud Gallery",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        DesktopApp(deps = deps)
    }
}
