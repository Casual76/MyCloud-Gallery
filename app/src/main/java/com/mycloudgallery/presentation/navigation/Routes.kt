package com.mycloudgallery.presentation.navigation

import kotlinx.serialization.Serializable

/** Route di navigazione type-safe via kotlinx.serialization */

@Serializable
object LoginRoute

@Serializable
object GalleryRoute

@Serializable
data class ViewerRoute(val mediaId: String, val initialIndex: Int = 0)

@Serializable
object AlbumsRoute

@Serializable
data class AlbumDetailRoute(val albumId: String, val isFavorites: Boolean = false)

@Serializable
object SearchRoute

@Serializable
object MapRoute

@Serializable
object SettingsRoute

@Serializable
object TrashRoute

@Serializable
object DuplicatesRoute
