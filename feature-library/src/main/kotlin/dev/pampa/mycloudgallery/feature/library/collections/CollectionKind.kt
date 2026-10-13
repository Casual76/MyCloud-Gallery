package dev.pampa.mycloudgallery.feature.library.collections

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.library.R

/**
 * Le Raccolte minime della Fase 3 (D17): quattro **filtri** della Libreria che hanno una pagina loro, piu' i due
 * posti che non sono filtri: i Nascosti (dietro la credenziale di sistema) e gli Eliminati di recente (il cestino di
 * Android). La Fase 7 completa le Raccolte con gli album.
 *
 * @property code il nome stabile nella rotta e nello stato salvato: non cambia mai (e' in una `@Serializable`).
 * @property filter il filtro rapido della Libreria che questa raccolta mostra con lo **stesso** `MediaGrid` e lo
 *   stesso viewer; null per i Nascosti e gli Eliminati di recente, che non passano dall'indice.
 */
enum class CollectionKind(val code: String, val filter: LibraryFilter?) {
  FAVORITES("favorites", LibraryFilter.FAVORITES),
  VIDEOS("videos", LibraryFilter.VIDEOS),
  SCREENSHOTS("screenshots", LibraryFilter.SCREENSHOTS),
  WHATSAPP("whatsapp", LibraryFilter.WHATSAPP),
  HIDDEN("hidden", null),
  RECENTLY_DELETED("recently_deleted", null),
  ;

  /** Vero per le quattro raccolte che sono un filtro dell'indice. */
  val isFilter: Boolean get() = filter != null

  companion object {
    /** Le quattro raccolte-filtro, nell'ordine dell'elenco. */
    val filters: List<CollectionKind> = entries.filter { it.isFilter }

    /** La raccolta per codice salvato; sconosciuto = null (una versione piu' nuova dell'app ne ha altre). */
    fun fromCode(code: String?): CollectionKind? = entries.firstOrNull { it.code == code }
  }
}

/** Il nome della raccolta, com'e' scritto nell'elenco e nella barra della pagina. */
@get:StringRes
val CollectionKind.titleRes: Int
  get() = when (this) {
    CollectionKind.FAVORITES -> R.string.collection_favorites
    CollectionKind.VIDEOS -> R.string.collection_videos
    CollectionKind.SCREENSHOTS -> R.string.collection_screenshots
    CollectionKind.WHATSAPP -> R.string.collection_whatsapp
    CollectionKind.HIDDEN -> R.string.collection_hidden
    CollectionKind.RECENTLY_DELETED -> R.string.collection_recently_deleted
  }

/** L'icona sulla piastrella della riga. */
val CollectionKind.icon: ImageVector
  get() = when (this) {
    CollectionKind.FAVORITES -> Icons.Rounded.Favorite
    CollectionKind.VIDEOS -> Icons.Rounded.Videocam
    CollectionKind.SCREENSHOTS -> Icons.Rounded.Screenshot
    CollectionKind.WHATSAPP -> Icons.Rounded.Chat
    CollectionKind.HIDDEN -> Icons.Rounded.Lock
    CollectionKind.RECENTLY_DELETED -> Icons.Rounded.Delete
  }

/** Il tono della piastrella dell'icona (mai quello della riga: regola 2 del design system). */
val CollectionKind.tone: FluidTone
  get() = when (this) {
    CollectionKind.FAVORITES -> FluidTone.Danger
    CollectionKind.VIDEOS -> FluidTone.Info
    CollectionKind.SCREENSHOTS -> FluidTone.Primary
    CollectionKind.WHATSAPP -> FluidTone.Success
    CollectionKind.HIDDEN -> FluidTone.Neutral
    CollectionKind.RECENTLY_DELETED -> FluidTone.Warning
  }

/** Il titolo dello stato vuoto di una raccolta-filtro. */
@get:StringRes
internal val CollectionKind.emptyTitleRes: Int
  get() = when (this) {
    CollectionKind.FAVORITES -> R.string.collection_empty_favorites_title
    CollectionKind.VIDEOS -> R.string.collection_empty_videos_title
    CollectionKind.SCREENSHOTS -> R.string.collection_empty_screenshots_title
    CollectionKind.WHATSAPP -> R.string.collection_empty_whatsapp_title
    CollectionKind.HIDDEN -> R.string.hidden_empty_title
    CollectionKind.RECENTLY_DELETED -> R.string.trash_empty_title
  }

/** La frase dello stato vuoto di una raccolta-filtro. */
@get:StringRes
internal val CollectionKind.emptyDetailRes: Int
  get() = when (this) {
    CollectionKind.FAVORITES -> R.string.collection_empty_favorites_detail
    CollectionKind.VIDEOS -> R.string.collection_empty_videos_detail
    CollectionKind.SCREENSHOTS -> R.string.collection_empty_screenshots_detail
    CollectionKind.WHATSAPP -> R.string.collection_empty_whatsapp_detail
    CollectionKind.HIDDEN -> R.string.hidden_empty_detail
    CollectionKind.RECENTLY_DELETED -> R.string.trash_empty_detail
  }
