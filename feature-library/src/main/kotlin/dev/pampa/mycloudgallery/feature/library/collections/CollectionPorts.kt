package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import javax.inject.Inject

// Le porte di `:feature-library` verso il resto dell'app per le Raccolte, i Nascosti e gli Eliminati di recente.
// Sono interfacce piccole: `:app` (P11) le lega al grafo vero (indice di P5, miniature di P6, MediaStore di P2) e
// i test le fingono (`collections/testing`). Qui non c'e' Android a parte i tipi di Compose.

/**
 * Gli elementi di una **raccolta-filtro** (Preferiti, Video, Screenshot, WhatsApp): la vista della Libreria con quel
 * filtro e come arrivare ai byte di ognuno. E' la stessa cosa che il viewer scorre (`ViewerItems`): una griglia
 * filtrata e il suo viewer guardano **la stessa vista**, quindi scorrere nel viewer e' scorrere la griglia.
 *
 * Viene sempre dall'indice della timeline, che **non contiene mai i nascosti** (E-H1): per costruzione una raccolta
 * non puo' mostrarne uno. Il filtro [LibraryFilter.ALL] e' la Libreria stessa.
 */
fun interface CollectionItemsSource {
  fun items(filter: LibraryFilter): ViewerItems
}

/**
 * Tutto cio' che serve a disegnare una griglia oltre agli elementi: come si caricano le miniature, come si impagina,
 * come si chiamano i luoghi e le preferenze della Libreria (livello e stile, ricordati). Un solo oggetto che i
 * ViewModel di questo modulo ricevono da Hilt e da cui nasce ogni [MediaGridState].
 */
@Immutable
class GridEnvironment @Inject constructor(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val placeNames: PlaceNames,
  val engine: GridLayoutEngine,
  val preferences: LibraryPreferences,
) {

  /**
   * Lo stato della griglia per [view]. Parte dal livello e dallo stile che l'utente ha scelto nella Libreria: una
   * raccolta si legge come la Libreria. I riferimenti ai byte li da' [items] (stesso oggetto del viewer).
   */
  fun gridStateFor(view: TimelineView, items: ViewerItems): MediaGridState = MediaGridState(
    view = view,
    loader = loader,
    placeholders = placeholders,
    placeNames = placeNames,
    engine = engine,
    refOf = items::ref,
    level = preferences.level.value,
    style = preferences.style.value,
  )
}
