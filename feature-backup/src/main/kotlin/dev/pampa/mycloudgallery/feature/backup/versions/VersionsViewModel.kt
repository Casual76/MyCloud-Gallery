package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.free.DialogAnswer
import dev.pampa.mycloudgallery.feature.backup.free.DialogBridge
import dev.pampa.mycloudgallery.feature.backup.free.NoPhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `VersionsState`, §2.4 "SWAP", D17, D18, E-E2, E-E10; contratto `VersionKeeper` di P0-B, logica
// di P8): le VERSIONI di un media e "Ripristina originale". Il ViewModel **non decide niente**: chiede il piano al custode, mostra il
// riepilogo e passa la conferma che il foglio ha fatto nascere dal tocco dell'utente. Non scrive sul NAS ne' sul telefono: lo scambio e' un
// elemento del diario del backup (nessun byte caricato, nessun file tolto); la riga del telefono si riscrive solo con il permesso del
// dialogo del sistema, chiesto qui e dato dall'utente. Niente nasce da un dato letto dal NAS.

@HiltViewModel
class VersionsViewModel @Inject constructor(
  keeper: Optional<VersionKeeper>,
  dialogs: Optional<PhoneSystemDialogs>,
  clock: Optional<BackupClock>,
  actions: Optional<VersionActions>,
) : ViewModel() {

  private val port: VersionKeeper? = keeper.orElse(null)
  private val systemDialogs: PhoneSystemDialogs = dialogs.orElse(NoPhoneSystemDialogs)
  private val monotonic: BackupClock? = clock.orElse(null)
  private val outside: VersionActions = actions.orElse(NoVersionActions)

  /** Il ponte verso il dialogo del sistema: la schermata lo collega (`BindDialogBridge`). */
  internal val bridge = DialogBridge()

  private val flow = MutableStateFlow<VersionsUi>(if (port == null || monotonic == null) VersionsUi.Unavailable else VersionsUi.Loading)

  internal val state: StateFlow<VersionsUi> = flow

  private var media: MediaId? = null
  private var job: Job? = null

  /** L'orologio **monotono** con cui il foglio data i tocchi di conferma (lo stesso con cui il custode li controlla). */
  fun monotonicMs(): Long = monotonic?.monotonicMs() ?: 0L

  /** Apre il foglio per [mediaId]. Chiamarla di nuovo per lo stesso media non rifa' niente. */
  fun bind(mediaId: MediaId, start: VersionsStart) {
    if (port == null || monotonic == null) return
    if (media == mediaId) return
    media = mediaId
    load(start)
  }

  /** Chiude il foglio: niente resta in giro (il prossimo `bind` riparte da capo). */
  fun reset() {
    job?.cancel()
    media = null
    flow.value = if (port == null || monotonic == null) VersionsUi.Unavailable else VersionsUi.Loading
  }

  private fun load(start: VersionsStart, message: VersionsMessage? = null) {
    val keeper = port ?: return
    val id = media ?: return
    job?.cancel()
    job = viewModelScope.launch {
      val plan = keeper.plan(id)
      flow.value = if (start == VersionsStart.RESTORE && plan.restore == RestoreAvailability.AVAILABLE) {
        VersionsUi.ConfirmRestore(plan, message)
      } else {
        listing(plan, message)
      }
    }
  }

  private fun listing(plan: NasVersionPlan, message: VersionsMessage? = null) = VersionsUi.Listing(
    plan = plan,
    canView = outside.canView,
    canDeleteIntermediates = outside.canDeleteIntermediates && plan.intermediates.isNotEmpty(),
    message = message,
  )

  /** "Ripristina originale": dalla lista al riepilogo da confermare, con il piano di adesso. */
  fun askRestore() = load(VersionsStart.RESTORE)

  /** "Annulla" nel riepilogo: torna alla lista. */
  fun backToList() = load(VersionsStart.LIST)

  /** "Vedi": apre la versione nel viewer (lo fa `:app`). */
  fun view(version: VersionId) {
    val id = media ?: return
    outside.view(id, version)
  }

  /** "Elimina le intermedie…": apre il foglio di feature-nas (lo fa `:app`); qui non si toglie niente. */
  fun deleteIntermediates() {
    val id = media ?: return
    val shown = (flow.value as? VersionsUi.Listing)?.plan ?: return
    outside.deleteIntermediates(id, shown.intermediates.map { it.versionId })
  }

  /**
   * Il tocco su "Ripristina originale" nel riepilogo: [confirmation] e' nata dal foglio. Il custode la controlla contro il piano
   * ricalcolato adesso. Se la copia del telefono e' la versione modificata (E-E10) serve il permesso del sistema: se l'utente lo nega,
   * l'originale torna comunque sul NAS e il telefono tiene la modifica (`PhoneWriteGrant` che non copre le righe).
   */
  fun restore(confirmation: PhoneConfirmation) {
    val keeper = port ?: return
    val id = media ?: return
    val plan = (flow.value as? VersionsUi.ConfirmRestore)?.plan ?: return
    job?.cancel()
    job = viewModelScope.launch {
      flow.value = VersionsUi.Restoring(plan)
      var outcome = keeper.restoreOriginal(id, confirmation, null)
      var keptEdited = false
      if (outcome is RestoreOutcome.NeedsPhoneWrite) {
        val rows = outcome.rows
        flow.value = VersionsUi.AwaitingSystemDialog(plan, rows.size)
        outcome = when (askWrite(rows)) {
          DialogAnswer.ALLOWED -> keeper.restoreOriginal(id, confirmation, PhoneWriteGrant(rows.toSet()))
          DialogAnswer.REFUSED -> {
            keptEdited = true
            // Il permesso che non copre le righe: lo scambio sul NAS si fa, il telefono resta com'e' (E-E10).
            keeper.restoreOriginal(id, confirmation, PhoneWriteGrant(emptySet()))
          }
          DialogAnswer.NOT_AVAILABLE -> {
            load(VersionsStart.LIST, VersionsMessage.NO_SYSTEM_DIALOG)
            return@launch
          }
        }
      }
      when (val result = outcome) {
        RestoreOutcome.Queued -> flow.value = VersionsUi.Restored(plan, phoneKeptEdited = keptEdited)
        // Non si e' fatto niente: il telefono voleva il permesso e non c'e' stato modo di scambiare senza.
        is RestoreOutcome.NeedsPhoneWrite -> load(VersionsStart.LIST, VersionsMessage.PHONE_PERMISSION_MISSING)
        is RestoreOutcome.Rejected -> load(
          VersionsStart.RESTORE,
          if (result.check == PhoneConfirmationCheck.Expired) VersionsMessage.CONFIRMATION_EXPIRED else VersionsMessage.PLAN_CHANGED,
        )
        // Il piano non permette il ripristino (il NAS non risponde, area ferma...): la lista lo spiega con il piano ricalcolato.
        is RestoreOutcome.Unavailable -> load(VersionsStart.LIST)
      }
    }
  }

  private suspend fun askWrite(rows: List<PhoneRef>): DialogAnswer {
    val sender = systemDialogs.writeRequest(rows) ?: return DialogAnswer.NOT_AVAILABLE
    return bridge.ask(sender)
  }
}
