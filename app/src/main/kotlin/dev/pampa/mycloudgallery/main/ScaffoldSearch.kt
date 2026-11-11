package dev.pampa.mycloudgallery.main

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchLift
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.library.LibrarySearchLayer
import dev.pampa.mycloudgallery.feature.search.SearchHandlers
import dev.pampa.mycloudgallery.feature.search.SearchLineAction
import dev.pampa.mycloudgallery.feature.search.SearchResults
import dev.pampa.mycloudgallery.feature.search.SearchSource
import dev.pampa.mycloudgallery.feature.search.SearchSuggestions
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchSelection
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.rememberSearchLine
import dev.pampa.mycloudgallery.feature.search.showsResults

/**
 * La ricerca dentro la Libreria come la vede lo scaffold (H4 della fase H, 11/11): cio' che mostra ([source], letto pezzo per pezzo), chi risponde al
 * campo e ai suggerimenti ([handlers]), le miniature (la cache della Libreria, solo locali) e le uscite ([actions]: viewer, pagine). [onResultOpened]: un
 * risultato toccato (la ricerca entra fra le recenti).
 */
@Stable
class ScaffoldSearch(
  val source: SearchSource,
  val handlers: SearchHandlers,
  val thumbnails: SearchThumbnails,
  val actions: SearchActions,
  val onResultOpened: () -> Unit = {},
) {
  /** Il tocco sulla riga sopra la barra. */
  fun runLine(action: SearchLineAction) = when (action) {
    SearchLineAction.OPEN_SEARCH_SETTINGS -> actions.openSearchSettings()
    SearchLineAction.OPEN_TEXT_READING -> actions.openTextReadingActivation()
    SearchLineAction.ALLOW_ACCESS -> actions.requestMediaAccess()
    SearchLineAction.RETRY -> handlers.onRetry()
  }
}

/**
 * La ricerca come la vede la Libreria ([LibrarySearchLayer]): la barra dei suggerimenti al posto dei pezzi in alto e i risultati al posto della griglia,
 * con la selezione della griglia della Libreria ([selection]) e la stessa salita con la tastiera della barra di ricerca ([lift]).
 */
internal class ScaffoldLibrarySearch(
  private val search: ScaffoldSearch,
  private val open: State<Boolean>,
  private val selection: SearchSelection,
  private val lift: GallerySearchLift,
  private val onOpen: (view: OrderedTimelineView, media: MediaId, anchor: ViewerAnchor) -> Unit,
  private val onUserScroll: () -> Unit,
) : LibrarySearchLayer {
  // Cambia solo quando i risultati arrivano o se ne vanno, non a ogni lista nuova: la Libreria non si ricompone mentre si scrive.
  private val results = derivedStateOf { search.source.state.showsResults() }

  override val active: Boolean get() = open.value

  override val showsResults: Boolean get() = results.value

  @Composable
  override fun Top(backdrop: GlassBackdropState, modifier: Modifier) {
    SearchSuggestions(source = search.source, handlers = search.handlers, backdrop = backdrop, modifier = modifier)
  }

  @Composable
  override fun Results(contentPadding: PaddingValues, modifier: Modifier) {
    val line = rememberSearchLine(search.source)
    SearchResults(
      source = search.source,
      thumbnails = search.thumbnails,
      onOpen = onOpen,
      contentPadding = contentPadding,
      modifier = modifier,
      selection = selection,
      lift = { lift.px().toFloat() },
      bottomExtra = if (line != null) GallerySearchBarDefaults.LineInset else null,
      onUserScroll = onUserScroll,
    )
  }
}

/**
 * **Indietro con la tastiera aperta chiude la ricerca in un passo solo** (H4): di solito il primo Indietro lo prende la tastiera, che si abbassa, e serve
 * un secondo Indietro. Da Android 13 la tastiera riceve l'Indietro attraverso la finestra dell'app, con la priorita' di serie; mentre la ricerca e' aperta
 * e la tastiera e' su, qui si registra una richiamata con la priorita' delle sovrapposizioni (`PRIORITY_OVERLAY`, quella dei menu), che passa prima:
 * [onBack] chiude la ricerca e la tastiera scende con la barra. Sotto Android 13 resta il comportamento di sistema (due passi).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchImeBack(enabled: Boolean, onBack: () -> Unit) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  val imeVisible = WindowInsets.isImeVisible
  if (!enabled || !imeVisible) return
  val view = LocalView.current
  val currentOnBack by rememberUpdatedState(onBack)
  val callback = remember { ImeBackCallback { currentOnBack() } }
  DisposableEffect(view, callback) {
    val dispatcher = view.findOnBackInvokedDispatcher()
    dispatcher?.let { callback.register(it) }
    onDispose { dispatcher?.let { callback.unregister(it) } }
  }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ImeBackCallback(private val onBack: () -> Unit) {
  private val callback = OnBackInvokedCallback { onBack() }

  fun register(dispatcher: OnBackInvokedDispatcher) = dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)

  fun unregister(dispatcher: OnBackInvokedDispatcher) = dispatcher.unregisterOnBackInvokedCallback(callback)
}
