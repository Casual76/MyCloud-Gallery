package dev.pampa.mycloudgallery.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.AppLibraryStates
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

  // --- Fase 4A (§1.4): le azioni della Diagnostica sul NAS nella Libreria ---------------------------------------------

  /** "Ferma le scritture sul NAS" / "Riattiva le scritture": l'emergenza dell'utente (D31 resta comunque). */
  fun setNasWritesStopped(stopped: Boolean) {
    gallery.nas?.emergency?.set(stopped)
  }

  /** Le aree congelate adesso (D10): per il foglio "Ho controllato, continua". */
  val frozenAreas: StateFlow<Map<String, Freeze>> = gallery.nas?.frozen ?: MutableStateFlow(emptyMap())

  /**
   * "Ho controllato, continua" (D10, doppia conferma): scongela le aree il cui conteggio coincide con quello che l'utente
   * ha riscritto. Vero se almeno una e' stata scongelata. Il gesto viene da qui (un tocco), mai da un dato del NAS.
   */
  fun releaseFreeze(confirmedCount: Int, done: (Boolean) -> Unit) {
    val nas = gallery.nas ?: return done(false)
    viewModelScope.launch {
      var released = false
      for ((share, freeze) in nas.frozen.value) {
        if (freeze.headlineCount == confirmedCount && nas.release(share, UserGesture(confirmedCount))) released = true
      }
      done(released)
    }
  }
}
