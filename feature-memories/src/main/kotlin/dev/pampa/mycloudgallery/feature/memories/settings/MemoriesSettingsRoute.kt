package dev.pampa.mycloudgallery.feature.memories.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions
import dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesHost
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

// LA ROTTA DI IMPOSTAZIONI -> RICORDI E NOTIFICHE (Fase 9, P9): lega il controllore e le uscite verso `:app` ("Consenti", il canale "Ricordi", il
// selettore di sistema per la canzone propria). Quando la pagina torna in primo piano (magari dalle impostazioni di Android) si rilegge il permesso.

@HiltViewModel
class MemoriesSettingsViewModel @Inject constructor(deps: MemoriesDependencies) : ViewModel() {
  val controller: MemoriesSettingsController = MemoriesSettingsController(
    memories = deps.memories,
    notify = deps.notify,
    access = deps.access,
    musicSettings = deps.musicSettings,
    library = deps.musicLibrary,
    // Persone attiva qui: senza la porta delle persone (o con Persone spenta) la riga "Ricordi con le persone" non c'e'.
    peopleAvailable = deps.people?.available ?: MutableStateFlow(false),
    scope = viewModelScope,
  )
}

@Composable
fun MemoriesSettingsRoute(
  actions: MemoriesActions,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MemoriesSettingsViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshAccess() }
  val handlers = remember(controller, actions, onBack) {
    object : MemoriesSettingsHandlers {
      override fun onBack() = onBack()

      override fun onSetPeople(on: Boolean) = controller.setPeople(on)

      override fun onSetNotify(on: Boolean) = controller.setNotifyEnabled(on)

      override fun onShiftHour(quarters: Int) = controller.shiftHour(quarters)

      override fun onFrequency(frequency: NotifyFrequency) = controller.setFrequency(frequency)

      override fun onLockScreen(mode: LockScreenMode) = controller.setLockScreen(mode)

      // "Consenti" da un tocco: se Android lo puo' ancora chiedere lo chiede, altrimenti apre le impostazioni dell'app (D18).
      override fun onAllow() = actions.requestNotificationPermission()

      override fun onOpenChannel() = actions.openNotificationChannel()

      override fun onRemoveLess(choice: LessChoice) = controller.removeLess(choice)

      override fun onNoMusic() = controller.chooseNoMusic()

      // Il selettore di sistema per la canzone propria: la scelta la salva `:app` in `MusicSettings` e la pagina la vede da sola.
      override fun onPickSong() = actions.pickOwnSong()

      override fun onInstallTrack(trackId: String) = controller.installTrack(trackId)

      override fun onUseTrack(trackId: String) = controller.useTrack(trackId)

      override fun onDismissMessage() = controller.dismissMessage()
    }
  }
  MemoriesHost { MemoriesSettingsScreen(ui, handlers, modifier) }
}
