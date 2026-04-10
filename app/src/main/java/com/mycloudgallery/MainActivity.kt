package com.mycloudgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mycloudgallery.presentation.navigation.AppNavigation
import com.mycloudgallery.ui.theme.MyCloudGalleryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyCloudGalleryTheme {
                AppNavigation()
            }
        }
    }
}
