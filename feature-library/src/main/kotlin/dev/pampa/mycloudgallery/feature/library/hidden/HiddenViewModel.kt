package dev.pampa.mycloudgallery.feature.library.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import javax.inject.Inject

/**
 * Il ViewModel dei Nascosti: tiene il [HiddenController] (la logica, senza Android) legato alla destinazione.
 *
 * Il cancello ([HiddenAccessGate]) arriva da Hilt come singleton di processo: cosi' la tolleranza di un minuto vale
 * anche uscendo dalla pagina e rientrando (un ViewModel nuovo), e un riavvio del processo riparte sempre chiuso.
 */
@HiltViewModel
class HiddenViewModel @Inject constructor(
  gate: HiddenAccessGate,
  library: HiddenLibrary,
  actions: LocalMediaActions,
  val environment: GridEnvironment,
  private val lockController: AppLockController,
) : ViewModel() {

  val controller: HiddenController = HiddenController(gate, library, actions, viewModelScope)

  /**
   * Se, uscendo dai Nascosti, i recenti devono restare oscurati: lo sono gia' se il blocco dell'app e' attivo, e
   * riaccenderli sarebbe disfare la sua protezione (`RecentsPrivacy` non conta chi l'ha chiesta).
   */
  fun keepRecentsHidden(): Boolean = lockController.settings.value?.isEnabled == true

  override fun onCleared() {
    controller.close()
  }
}
