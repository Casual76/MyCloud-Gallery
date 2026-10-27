package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode

/**
 * I codici della Diagnostica degli album (docs/fase7-progetto.md §2.8, D37): solo numeri e codici, mai titoli, cartelle, luoghi, casa,
 * coordinate, descrizioni, id di media o account. Il catalogo di feature-diagnostics li raccoglie in P13 (sezione `DiagSection.ALBUMS`),
 * con lo stesso id e gli stessi edge case.
 */
object AlbumDiag {
  val ALBUM_CONTRIB_REJECTED = DiagCode("ALBUM_CONTRIB_REJECTED", setOf("E-SH14", "C24"))
  val ALBUM_ENTRY_DENIED = DiagCode("ALBUM_ENTRY_DENIED", setOf("E-SH13", "C25"))
  val ALBUM_ITEM_UNATTESTED = DiagCode("ALBUM_ITEM_UNATTESTED", setOf("E-SH15", "E-SH17"))
  val SHARE_PAUSED_SPACE = DiagCode("SHARE_PAUSED_SPACE", setOf("E-SH8"))
  val SHARE_PAUSED_FROZEN = DiagCode("SHARE_PAUSED_FROZEN", setOf("E-SH19"))
  val SHARE_WAITING_LOCKED = DiagCode("SHARE_WAITING_LOCKED")
  val SHARE_SOURCE_CHANGED = DiagCode("SHARE_SOURCE_CHANGED", setOf("E-SH18"))
  val SHARE_SAFE_COPY_QUEUED = DiagCode("SHARE_SAFE_COPY_QUEUED", setOf("E-B13", "E-B14"))
  val SMART_TOO_BROAD = DiagCode("SMART_TOO_BROAD", setOf("E-AL6"))
  val SMART_NO_SEARCH = DiagCode("SMART_NO_SEARCH", setOf("E-AL9"))
  val TRIP_NO_GPS = DiagCode("TRIP_NO_GPS", setOf("E-AL16"))
  val ALBUM_PROJECTION_PENDING = DiagCode("ALBUM_PROJECTION_PENDING")

  /** DG-2: il controllo notturno dei video del NAS (hash completo, D27) e la firma dei Doppioni simili (D25): l'ultimo giro, solo numeri. */
  val UTILITY_FULL_HASH = DiagCode("UTILITY_FULL_HASH", setOf("E-U4"))
  val UTILITY_SIGNING = DiagCode("UTILITY_SIGNING")

  val ALL: List<DiagCode> = listOf(
    ALBUM_CONTRIB_REJECTED, ALBUM_ENTRY_DENIED, ALBUM_ITEM_UNATTESTED, SHARE_PAUSED_SPACE, SHARE_PAUSED_FROZEN, SHARE_WAITING_LOCKED,
    SHARE_SOURCE_CHANGED, SHARE_SAFE_COPY_QUEUED, SMART_TOO_BROAD, SMART_NO_SEARCH, TRIP_NO_GPS, ALBUM_PROJECTION_PENDING, UTILITY_FULL_HASH, UTILITY_SIGNING,
  )
}
