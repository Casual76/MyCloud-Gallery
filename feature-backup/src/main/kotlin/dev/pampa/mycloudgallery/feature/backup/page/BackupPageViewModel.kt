package dev.pampa.mycloudgallery.feature.backup.page

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupActions
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupOverview
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedCounts
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedItem
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedQueue
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// La pagina Backup (docs/fase5-progetto.md §1.5, §2.6, D16, D30): lo stato per sorgente, il problema piu' grave con la sua azione,
// "Fai ora", "Ferma", la cartella del dispositivo e il riepilogo del primo backup. Il ViewModel **non decide niente**: legge lo stato dalle
// porte (`BackupOverview`, `BackupSettings`) e inoltra i tocchi alle azioni di `:app` (`BackupActions`); le azioni dei problemi e i
// comandi partono solo da un tocco.

/** Tutto cio' che la pagina mostra, in un valore solo. */
@Immutable
internal data class BackupPageUi(
  val status: BackupStatus,
  val sources: List<SourceStatus>,
  /** La cartella del dispositivo scelta (D7), o null finche' il primo backup non l'ha scelta. */
  val deviceFolder: DeviceFolder?,
  /** Il foglio del primo backup e' aperto. */
  val sheetOpen: Boolean,
  /** 5B (P14): quante attivita' "Da decidere" sono aperte (la scheda d'ingresso compare solo con almeno una). */
  val pendingTasks: Int = 0,
  /** 5B (IN-7): i file ricevuti da un'altra app che non sono ancora sul NAS (la scheda "Ricevuti in coda" compare solo con almeno uno). */
  val received: ReceivedCounts = ReceivedCounts.NONE,
)

@HiltViewModel
class BackupPageViewModel @Inject constructor(
  overview: Optional<BackupOverview>,
  settings: Optional<BackupSettings>,
  actions: Optional<BackupActions>,
  tasks: Optional<PendingTasks> = Optional.empty(),
  received: Optional<ReceivedQueue> = Optional.empty(),
) : ViewModel() {

  private val port: BackupOverview = overview.orElse(NoBackupOverview)
  private val folder: StateFlow<DeviceFolder?> = settings.orElse(null)?.deviceFolder ?: MutableStateFlow<DeviceFolder?>(null)
  private val acts: BackupActions = actions.orElse(NoBackupActions)
  private val sheet = MutableStateFlow(false)

  private val pending: StateFlow<List<PendingTask>> = tasks.orElse(null)?.tasks ?: MutableStateFlow(emptyList())

  private val receivedItems: StateFlow<List<ReceivedItem>> = received.orElse(null)?.items ?: MutableStateFlow(emptyList())

  private val base: Flow<BackupPageUi> = combine(port.status, port.sources, folder, sheet, pending) { status, sources, deviceFolder, open, open5b ->
    BackupPageUi(status = status, sources = sources, deviceFolder = deviceFolder, sheetOpen = open, pendingTasks = open5b.size)
  }

  internal val state: StateFlow<BackupPageUi> = combine(base, receivedItems) { ui, arrived -> ui.copy(received = ReceivedCounts.of(arrived)) }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
    initialValue = BackupPageUi(port.status.value, port.sources.value, folder.value, sheet.value, pending.value.size, ReceivedCounts.of(receivedItems.value)),
  )

  /** Il riepilogo del primo backup (§2.11): il foglio si apre da un tocco, o da solo quando si arriva dalla rotta `backup/first`. */
  fun openFirstBackup() {
    sheet.value = true
  }

  fun closeFirstBackup() {
    sheet.value = false
  }

  /** "Stanotte" ([tonight]) o "Inizia ora": la scelta del foglio; il foglio si chiude e il resto lo fa `:app` (UIDT, preset). */
  fun startFirstBackup(tonight: Boolean) {
    sheet.value = false
    acts.startFirstBackup(tonight)
  }

  /** Il tasto di un problema (§2.6, colonna "Azione"): `:app` apre la Diagnostica, chiede il permesso, ecc. */
  fun perform(action: BackupAction) = acts.perform(action)

  /** "Fai ora" / "Riprendi". */
  fun backupNow() = acts.backupNow()

  /** "Ferma": prima del file successivo, ogni temporaneo resta con il suo punto di ripresa. */
  fun stop() = acts.stop()

  /** La pagina e' di nuovo davanti all'utente: lo stato si ricalcola e un problema sistemato fuori dalla pagina non resta (BK-3, BK-13). */
  fun onResumed() = acts.onPageResumed()

  /** Un'altra schermata del backup (le impostazioni, la scelta della cartella del dispositivo). */
  fun open(screen: BackupScreen) = acts.open(screen)

  private companion object {
    /** Il flusso resta vivo qualche secondo senza chi lo guarda: una rotazione dello schermo non lo rifa'. */
    const val StopTimeoutMillis = 5_000L
  }
}
