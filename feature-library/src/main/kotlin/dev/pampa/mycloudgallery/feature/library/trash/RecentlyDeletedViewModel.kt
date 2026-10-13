package dev.pampa.mycloudgallery.feature.library.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import javax.inject.Inject

/**
 * Il ViewModel degli Eliminati di recente: tiene il [RecentlyDeletedController] (la logica) legato alla destinazione. Il
 * cancello e' lo stesso singleton dei Nascosti: un minuto di tolleranza condiviso fra le due schermate.
 */
@HiltViewModel
class RecentlyDeletedViewModel @Inject constructor(
  gate: HiddenAccessGate,
  source: RecentlyDeletedSource,
  mask: TrashHiddenMask,
  intents: PhoneMediaIntents,
  val environment: GridEnvironment,
  private val lockController: AppLockController,
) : ViewModel() {

  val controller: RecentlyDeletedController = RecentlyDeletedController(gate, source, mask, intents, viewModelScope)

  /** Se, uscendo, i recenti devono restare oscurati: lo sono gia' con il blocco dell'app attivo (vedi `HiddenViewModel`). */
  fun keepRecentsHidden(): Boolean = lockController.settings.value?.isEnabled == true

  override fun onCleared() {
    controller.close()
  }
}
