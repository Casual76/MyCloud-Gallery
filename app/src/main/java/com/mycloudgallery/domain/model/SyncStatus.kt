package com.mycloudgallery.domain.model

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Syncing(
        val totalFiles: Int,
        val processedFiles: Int,
        val currentFileName: String?,
    ) : SyncStatus
    data class Error(val message: String) : SyncStatus
    data object Completed : SyncStatus
}
