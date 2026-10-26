package dev.pampa.mycloudgallery.feature.library.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import java.util.Optional
import javax.inject.Inject

/**
 * Il ViewModel degli Eliminati di recente: tiene il [RecentlyDeletedController] (la logica) legato alla destinazione. Il
 * cancello e' lo stesso singleton dei Nascosti: un minuto di tolleranza condiviso fra le due schermate.
 *
 * Il cestino del NAS (Fase 7, D26) e' una porta **opzionale** ([NasTrashPort], `OptionalUtilityBindingsModule`): finche' `:app` non la lega la
 * pagina mostra solo il cestino del telefono, come prima.
 */
@HiltViewModel
class RecentlyDeletedViewModel @Inject constructor(
  gate: HiddenAccessGate,
  source: RecentlyDeletedSource,
  mask: TrashHiddenMask,
  intents: PhoneMediaIntents,
  val environment: GridEnvironment,
  private val lockController: AppLockController,
  nasTrash: Optional<NasTrashPort>,
) : ViewModel() {

  val controller: RecentlyDeletedController = RecentlyDeletedController(gate, source, mask, intents, viewModelScope, nasTrash.orElse(NoNasTrash))

  /** Se, uscendo, i recenti devono restare oscurati: lo sono gia' con il blocco dell'app attivo (vedi `HiddenViewModel`). */
  fun keepRecentsHidden(): Boolean = lockController.settings.value?.isEnabled == true

  override fun onCleared() {
    controller.close()
  }
}
