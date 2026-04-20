package com.mycloudgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.presentation.navigation.AppNavigation
import com.mycloudgallery.ui.theme.MyCloudGalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var networkDetector: NetworkDetector
    
    @Inject
    lateinit var imageLoader: ImageLoader

    @OptIn(DelicateCoilApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force the singleton ImageLoader to use our custom one with SmbFetcher
        SingletonImageLoader.setUnsafe(imageLoader)
        
        networkDetector.start()
        enableEdgeToEdge()
        setContent {
            MyCloudGalleryTheme {
                AppNavigation()
            }
        }
    }
}
