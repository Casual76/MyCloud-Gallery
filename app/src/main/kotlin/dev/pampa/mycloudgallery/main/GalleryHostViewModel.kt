package dev.pampa.mycloudgallery.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.AppLibraryStates
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import javax.inject.Inject

/**
 * Cio' che la navigazione della galleria chiede al grafo: i servizi del viewer (quelli veri, o quelli della Libreria
 * sintetica in debug) e la rilettura dei permessi dopo un dialogo di sistema. Nessuno stato proprio.
 */
@HiltViewModel
class GalleryHostViewModel @Inject constructor(
  private val gallery: GalleryGraph,
  private val states: AppLibraryStates,
) : ViewModel() {

  /** Il viewer delle Raccolte, dei Nascosti e delle griglie filtrate: il grafo vero. */
  val viewerServices: ViewerServices get() = gallery.viewerServices

  /** Il viewer della Libreria per lo stato mostrato. */
  fun viewerServicesFor(state: LibraryTabState): ViewerServices = states.viewerServicesFor(state)

  /** Dopo un dialogo dei permessi o del cestino: l'accesso si rilegge subito. */
  fun refreshAccess() = gallery.refreshAccess()
}
