package dev.pampa.mycloudgallery.feature.backup.free

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `FreeSpaceState`, §2.5, D21, D22, R6 della 4B): la pagina "Libera spazio" e i suoi fogli di conferma.
// Il ViewModel **non decide niente**: tiene la macchina dei passi ([FreeSpaceFlow]), apre i fogli e passa le conferme che i fogli hanno fatto
// nascere dal tocco dell'utente. Non scrive sul NAS ne' sul telefono; toglie file solo con il dialogo del sistema (`PhoneSystemDialogs`),
// dopo che il servizio ha ricontrollato ogni copia. Niente nasce da un dato letto dal NAS (E-S11).

@HiltViewModel
class FreeSpaceViewModel @Inject constructor(
  service: Optional<FreeSpace>,
  dialogs: Optional<PhoneSystemDialogs>,
  clock: Optional<BackupClock>,
  actions: Optional<BackupActions>,
  runs: Optional<FreeSpaceRuns>,
) : ViewModel() {

  private val freeSpace: FreeSpace? = service.orElse(null)

  /** LS-2: il giro fuori dalla pagina (`:app`: lavoro avviato dall'utente, notifica, ripresa); senza, la pagina lo porta avanti da se'. */
  private val outsideRuns: FreeSpaceRuns? = runs.orElse(null)
  private val systemDialogs: PhoneSystemDialogs = dialogs.orElse(NoPhoneSystemDialogs)
  private val monotonic: BackupClock? = clock.orElse(null)
  private val outside: BackupActions? = actions.orElse(null)

  /** Il ponte verso il dialogo del sistema: la schermata lo collega (`BindDialogBridge`). */
  internal val bridge = DialogBridge()

  private var machine: FreeSpaceFlow? = null
  private val flow = MutableStateFlow<FreeSpaceUi>(unavailableOr(FreeSpaceUi.Idle))
  private val sheetFlow = MutableStateFlow<FreeSheet>(FreeSheet.None)
  private var observing: kotlinx.coroutines.Job? = null

  /** Lo stato della pagina. */
  internal val state: StateFlow<FreeSpaceUi> = flow

  /** Il foglio di conferma aperto. */
  internal val sheet: StateFlow<FreeSheet> = sheetFlow

  private fun unavailableOr(other: FreeSpaceUi): FreeSpaceUi =
    if (freeSpace == null || monotonic == null) FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE) else other

  /** L'orologio **monotono** con cui si datano i tocchi di conferma e si conta quanto vale ancora la verifica. */
  fun monotonicMs(): Long = monotonic?.monotonicMs() ?: 0L

  /**
   * Apre la pagina. [selection] sono i media scelti dall'utente ("Libera spazio" sulla selezione, "Elimina solo dal telefono"): al massimo
   * quelli; null = tutto il sicuro. Con una scelta fatta a mano non si escludono gli ultimi giorni (D22). Una seconda chiamata con gli stessi
   * dati non rifa' niente.
   */
  fun start(selection: Set<MediaId>?, excludeRecentDays: Int? = null) {
    val service = freeSpace ?: return
    if (monotonic == null) return
    val recent = excludeRecentDays ?: if (selection != null) 0 else FreeSpaceRules.DEFAULT_EXCLUDE_RECENT_DAYS
    if (machine != null && startedWith == selection to recent) return
    startedWith = selection to recent
    machine?.stop()
    // Ogni macchina ha il suo scope (figlio della pagina): quella di prima smette davvero di ascoltare.
    machineScope?.cancel()
    val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    machineScope = scope
    val next = FreeSpaceFlow(
      service = service,
      scope = scope,
      systemDialogs = systemDialogs.available,
      runs = outsideRuns ?: FreeSpaceDriver(service, scope),
      selection = selection,
      excludeRecentDays = recent,
    )
    machine = next
    observing?.cancel()
    observing = viewModelScope.launch { next.state.collect { flow.value = it } }
    next.load()
  }

  private var startedWith: Pair<Set<MediaId>?, Int>? = null
  private var machineScope: CoroutineScope? = null

  /** I filtri: tutto il sicuro, solo i video, piu' vecchi di un anno. */
  fun choose(kind: FreeSpaceKind) {
    machine?.choose(kind)
  }

  /** Ricalcola dal piano (dopo "Riprova", "Ricomincia" o una conferma rifiutata). */
  fun reload() {
    sheetFlow.value = FreeSheet.None
    machine?.load()
  }

  /** "Libera spazio": il foglio con il piano di adesso e un tocco di conferma. */
  fun openConfirm() = openSheet(permanent = false)

  /** "Libera subito…": il foglio con il piano definitivo e i due gesti distinti. */
  fun openConfirmNow() = openSheet(permanent = true)

  private fun openSheet(permanent: Boolean) {
    val current = machine ?: return
    viewModelScope.launch {
      val plan = current.planNow(permanent)
      sheetFlow.value = if (permanent) FreeSheet.ConfirmNow(plan) else FreeSheet.Confirm(plan)
    }
  }

  fun closeSheet() {
    sheetFlow.value = FreeSheet.None
  }

  /** La conferma nata dal tocco sul foglio: parte il guardiano e la verifica. */
  fun confirm(confirmation: PhoneConfirmation) {
    val plan = when (val open = sheetFlow.value) {
      FreeSheet.None -> return
      is FreeSheet.Confirm -> open.plan
      is FreeSheet.ConfirmNow -> open.plan
    }
    sheetFlow.value = FreeSheet.None
    machine?.start(plan, confirmation)
  }

  /** "Annulla" durante la verifica. */
  fun cancelVerification() {
    machine?.cancel()
  }

  /** "Libera ora" (o il lotto successivo): il tocco che fa partire il dialogo del sistema per il prossimo lotto. */
  fun free() {
    machine?.free(
      RemoveDialog { batch ->
        val sender = systemDialogs.removeRequest(batch) ?: return@RemoveDialog DialogAnswer.NOT_AVAILABLE
        bridge.ask(sender)
      },
    )
  }

  /** Il tasto di un blocco con la sua azione ("Apri la Diagnostica"): `:app` la esegue. */
  fun perform(action: BackupAction) {
    outside?.perform(action)
  }

  override fun onCleared() {
    machine?.stop()
  }
}
