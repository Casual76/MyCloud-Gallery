package dev.pampa.mycloudgallery.feature.backup.folder

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposal
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.ExistingFolder
import dev.pampa.mycloudgallery.feature.backup.noop.NoDeviceFolderProposals
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// La cartella del dispositivo sul NAS (docs/fase5-progetto.md D7, E-B6, E-B7, E-B16; D30): si sceglie **una volta**, prima del primo backup.
// Se sul NAS c'e' gia' `Backup/<nome>/` (una reinstallazione, un telefono nuovo, un secondo dispositivo con lo stesso nome) si chiede
// "Continua li'" o "Nuova cartella"; i media gia' presenti si collegano senza ricaricare (la deduplica), mai sovrascritti. Rinominare il
// dispositivo non la cambia.

/** Cosa mostra la scelta della cartella del dispositivo. */
internal sealed interface FolderUi {
  /** Si legge cosa c'e' gia' sul NAS. */
  data object Loading : FolderUi

  /** La proposta non e' ancora disponibile (nessun NAS, nessuna area privata): si riprova quando il backup e' pronto. */
  data object Unavailable : FolderUi

  /**
   * Da scegliere. Senza [existing] un solo tasto ("Usa questo nome"); con [existing] due ("Continua li'" nella cartella che c'e', "Nuova
   * cartella" con [alternative]).
   */
  @Immutable
  data class Choose(val suggested: DeviceFolder, val existing: ExistingFolder?, val alternative: DeviceFolder?) : FolderUi

  /** Gia' scelta: non si cambia piu' (E-B7). */
  @Immutable
  data class Chosen(val folder: DeviceFolder) : FolderUi
}

@HiltViewModel
class DeviceFolderViewModel @Inject constructor(
  settings: Optional<BackupSettings>,
  proposals: Optional<DeviceFolderProposals>,
) : ViewModel() {

  private val port: BackupSettings? = settings.orElse(null)
  private val proposing: DeviceFolderProposals = proposals.orElse(NoDeviceFolderProposals)
  private val proposal = MutableStateFlow<FolderUi>(FolderUi.Loading)
  private val noFolder: StateFlow<DeviceFolder?> = MutableStateFlow<DeviceFolder?>(null)

  internal val state: StateFlow<FolderUi> = combine(proposal, port?.deviceFolder ?: noFolder) { proposed, chosen ->
    if (chosen != null) FolderUi.Chosen(chosen) else proposed
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
    initialValue = (port?.deviceFolder ?: noFolder).value?.let { FolderUi.Chosen(it) } ?: FolderUi.Loading,
  )

  init {
    load()
  }

  /** Rilegge la proposta (all'apertura e con "Riprova"). */
  fun load() {
    proposal.value = FolderUi.Loading
    viewModelScope.launch {
      proposal.value = try {
        proposing.propose()?.toUi() ?: FolderUi.Unavailable
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        FolderUi.Unavailable
      }
    }
  }

  /** "Usa questo nome", "Continua li'" o "Nuova cartella": la cartella scelta si salva nelle impostazioni, una volta. */
  fun choose(folder: DeviceFolder) {
    val settings = port ?: return
    viewModelScope.launch { settings.chooseFolder(folder) }
  }

  private fun DeviceFolderProposal.toUi(): FolderUi = FolderUi.Choose(suggested, existing, alternative)

  private companion object {
    const val StopTimeoutMillis = 5_000L
  }
}
