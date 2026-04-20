package com.mycloudgallery.presentation.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

import coil3.ImageLoader

/** ViewModel leggero usato dalla BottomNavBar per osservare il badge delle richieste pendenti. */
@HiltViewModel
class BottomNavViewModel @Inject constructor(
    sharedAlbumRepository: SharedAlbumRepository,
    val imageLoader: ImageLoader,
) : ViewModel() {

    val pendingRequestsCount: StateFlow<Int> = sharedAlbumRepository
        .getPendingRequestsCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}
