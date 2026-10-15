package dev.pampa.mycloudgallery.feature.viewer.menu

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.viewer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// La macchina che porta una voce del menu ("Sposta", "Rinomina", "Elimina…") a un'operazione sul NAS (Fase 4B, P14, D24): chiede il
// piano a `NasOperations`, tiene lo stato del foglio aperto e, **solo quando le arriva una conferma costruita dal foglio dopo un tocco
// dell'utente**, avvia. Non costruisce mai una conferma (il foglio lo fa in `NasActionsSheets`), non scrive mai sul NAS da sola e non
// tiene una conferma fra uno stato e l'altro.

/** Lo stato del foglio aperto. */
internal sealed interface NasActionsState {
  data object Idle : NasActionsState

  /** "Elimina…": [plan] e' il piano per il cestino del NAS (null se non si puo' scrivere o e' ancora in calcolo). */
  data class Delete(
    val target: NasActionTarget,
    val scope: GalleryDeleteScope,
    val plan: OpPlan?,
    val planning: Boolean,
  ) : NasActionsState

  data class Rename(val target: NasActionTarget, val text: String, val check: RenameCheck) : NasActionsState

  data class PickDestination(val target: NasActionTarget) : NasActionsState

  data class ConfirmMove(val target: NasActionTarget, val destination: NasMoveDestination, val plan: OpPlan) : NasActionsState
}

/** L'esito del controllo del nuovo nome mentre si scrive (E-D9). */
internal sealed interface RenameCheck {
  data object Empty : RenameCheck

  data object Checking : RenameCheck

  data class Ready(val plan: OpPlan) : RenameCheck

  data class Invalid(val suggestion: String?) : RenameCheck

  data object Exists : RenameCheck

  data object Same : RenameCheck

  data class Refused(@StringRes val text: Int) : RenameCheck
}

/** Una frase per il viewer dopo un'operazione (una notifica o una riga di stato): [text] e' una risorsa, mai un testo del NAS. */
class NasActionNotice(@StringRes val text: Int, val started: Boolean) {
  override fun toString(): String = "NasActionNotice(started=$started)"
}

/**
 * La macchina delle voci del menu per il media che il viewer mostra. Il viewer chiama [bind] quando cambia pagina, legge [target] per
 * costruire il menu e [requestDelete] / [requestRename] / [requestMove] quando l'utente sceglie una voce; i fogli
 * (`NasActionSheets`) leggono lo stato e restituiscono i gesti.
 *
 * @param scope lo scope che vive quanto il viewer: se si chiude a meta' di un piano, non resta niente di avviato (un'operazione parte
 *   solo da [confirmed], cioe' da un tocco).
 * @param releaseOwnHandles R10 (revisione 4B): il viewer chiude i propri handle sul file (il lettore del video) prima che l'operazione lo
 *   rinomini, lo sposti o lo metta nel cestino: altrimenti l'operazione aspetterebbe il viewer stesso come un "file aperto dal PC".
 *   Vero se ha chiuso qualcosa.
 */
class NasActionsController(
  private val actions: NasMediaActions,
  private val scope: CoroutineScope,
  private val releaseOwnHandles: () -> Boolean = { false },
) {

  private val targetFlow = MutableStateFlow<NasActionTarget?>(null)

  /** Il file del NAS dietro il media di adesso, o null (non e' del NAS, o non si risolve). */
  val target: StateFlow<NasActionTarget?> = targetFlow

  private val stateFlow = MutableStateFlow<NasActionsState>(NasActionsState.Idle)
  internal val state: StateFlow<NasActionsState> = stateFlow

  private val noticeFlow = MutableStateFlow<NasActionNotice?>(null)

  /** Cosa dire dopo un'operazione; il viewer la mostra e poi chiama [dismissNotice]. */
  val notice: StateFlow<NasActionNotice?> = noticeFlow

  private var bindJob: Job? = null
  private var planJob: Job? = null
  private var nameJob: Job? = null

  /** L'orologio con cui il foglio data una conferma. */
  fun monotonicMs(): Long = actions.monotonicMs()

  /**
   * Il viewer e' passato a [ref] (null = un media senza copia leggibile): si risolve il file e cosa ci si puo' fare. Un media mostrato
   * dalla copia del telefono ([MediaRef.Phone]) con copie anche sul NAS ([id]) ha "Elimina…" con la scelta del luogo (E-D1).
   */
  fun bind(ref: MediaRef?, id: MediaId? = null) {
    bindJob?.cancel()
    dismiss()
    when {
      ref is MediaRef.Nas -> bindJob = scope.launch { targetFlow.value = actions.target(ref) }
      ref is MediaRef.Phone && id != null -> {
        targetFlow.value = null
        bindJob = scope.launch { targetFlow.value = actions.targetForPhoneCopy(id) }
      }
      else -> targetFlow.value = null
    }
  }

  // --- Elimina… ---------------------------------------------------------------------------------------------------------------

  /** "Elimina…": il foglio con la scelta del luogo, e il piano per il cestino del NAS in calcolo. */
  fun requestDelete() {
    val target = targetFlow.value ?: return
    // In un'area in sola lettura le voci del menu sono spente (E-N9): nemmeno forzando il gesto si apre un foglio che non puo' agire.
    if (!target.writable) return
    val scopeChoice = if (target.onPhone) GalleryDeleteScope.PhoneOnly else GalleryDeleteScope.Everywhere
    stateFlow.value = NasActionsState.Delete(target, scopeChoice, plan = null, planning = true)
    planJob?.cancel()
    planJob = scope.launch {
      // "Ovunque": tutte le copie del NAS dove si hanno i diritti (E-D11); il piano salta il resto e il foglio lo elenca.
      val outcome = actions.operations.plan(OpRequest.Trash(target.everywhereItems))
      stateFlow.update { current ->
        if (current is NasActionsState.Delete && current.target === target) {
          when (outcome) {
            is PlanOutcome.Ready -> current.copy(plan = outcome.plan, planning = false)
            is PlanOutcome.Rejected -> {
              noticeFlow.value = NasActionNotice(rejectionText(outcome.reason), started = false)
              current.copy(plan = null, planning = false)
            }
          }
        } else {
          current
        }
      }
    }
  }

  internal fun setScope(scopeChoice: GalleryDeleteScope) = stateFlow.update { if (it is NasActionsState.Delete) it.copy(scope = scopeChoice) else it }

  // --- Rinomina ---------------------------------------------------------------------------------------------------------------

  fun requestRename() {
    val target = targetFlow.value ?: return
    if (!target.writable) return
    stateFlow.value = NasActionsState.Rename(target, text = target.item.path.name, check = RenameCheck.Same)
  }

  /** Il testo del nome cambia: si controlla subito il nome, poi (dopo una breve pausa) si chiede il piano al NAS. */
  internal fun onRenameText(text: String) {
    val current = stateFlow.value as? NasActionsState.Rename ?: return
    nameJob?.cancel()
    when {
      text.isEmpty() -> stateFlow.value = current.copy(text = text, check = RenameCheck.Empty)
      text == current.target.item.path.name -> stateFlow.value = current.copy(text = text, check = RenameCheck.Same)
      else -> {
        val local = NameRules.check(text)
        if (local is NameRules.Check.Invalid) {
          stateFlow.value = current.copy(text = text, check = RenameCheck.Invalid(local.suggestion))
          return
        }
        stateFlow.value = current.copy(text = text, check = RenameCheck.Checking)
        nameJob = scope.launch {
          delay(NAME_CHECK_DELAY_MS)
          val check = when (val outcome = actions.operations.plan(OpRequest.Rename(current.target.item, text))) {
            is PlanOutcome.Ready -> RenameCheck.Ready(outcome.plan)
            is PlanOutcome.Rejected -> when (outcome.reason) {
              PlanRejection.NAME_EXISTS -> RenameCheck.Exists
              PlanRejection.SAME_LOCATION -> RenameCheck.Same
              PlanRejection.INVALID_NAME -> RenameCheck.Invalid(NameRules.suggest(text))
              else -> RenameCheck.Refused(rejectionText(outcome.reason))
            }
          }
          stateFlow.update { latest -> if (latest is NasActionsState.Rename && latest.text == text) latest.copy(check = check) else latest }
        }
      }
    }
  }

  // --- Sposta ---------------------------------------------------------------------------------------------------------------

  fun requestMove() {
    val target = targetFlow.value ?: return
    if (!target.writable || target.destinations.isEmpty()) return
    stateFlow.value = NasActionsState.PickDestination(target)
  }

  /** L'utente ha scelto dove: si chiede il piano e, se c'e', il foglio di conferma. */
  internal fun pick(destination: NasMoveDestination) {
    val current = stateFlow.value as? NasActionsState.PickDestination ?: return
    planJob?.cancel()
    planJob = scope.launch {
      when (val outcome = actions.operations.plan(OpRequest.Move(listOf(current.target.item), destination.share, destination.folder))) {
        is PlanOutcome.Ready -> stateFlow.value = NasActionsState.ConfirmMove(current.target, destination, outcome.plan)
        is PlanOutcome.Rejected -> {
          stateFlow.value = NasActionsState.Idle
          noticeFlow.value = NasActionNotice(rejectionText(outcome.reason), started = false)
        }
      }
    }
  }

  // --- La conferma e la fine --------------------------------------------------------------------------------------------------

  /**
   * L'utente ha toccato il pulsante di conferma di un foglio: [confirmation] nasce dal foglio (`ConfirmationSheet`), qui si
   * verifica contro il piano e l'operazione parte. Una conferma di un altro piano, scaduta o incompleta non avvia niente.
   */
  internal fun confirmed(plan: OpPlan, confirmation: UserConfirmation) {
    stateFlow.value = NasActionsState.Idle
    // R10: prima di toccare il file, il viewer chiude il suo (il lettore del video): l'operazione non aspetta il viewer stesso.
    val released = releaseOwnHandles()
    scope.launch {
      if (released) delay(RELEASE_SETTLE_MS)
      val outcome = actions.operations.start(plan, confirmation)
      noticeFlow.value = noticeFor(outcome)
    }
  }

  /** "Solo dal telefono": niente del NAS: chiude il foglio, il viewer lancia la sua eliminazione dal telefono. */
  internal fun phoneOnlyDone() {
    stateFlow.value = NasActionsState.Idle
  }

  fun dismiss() {
    planJob?.cancel()
    nameJob?.cancel()
    stateFlow.value = NasActionsState.Idle
  }

  fun dismissNotice() {
    noticeFlow.value = null
  }

  private fun noticeFor(outcome: StartOutcome): NasActionNotice = when (outcome) {
    is StartOutcome.Started, is StartOutcome.AlreadyStarted -> NasActionNotice(R.string.viewer_nas_done_started, started = true)
    is StartOutcome.ConfirmationRejected -> NasActionNotice(
      if (outcome.check == ConfirmationCheck.Expired) R.string.viewer_nas_done_expired else R.string.viewer_nas_done_refused,
      started = false,
    )
    is StartOutcome.PlanInvalid -> NasActionNotice(R.string.viewer_nas_done_refused, started = false)
    StartOutcome.WritesBlocked -> NasActionNotice(R.string.viewer_nas_done_blocked, started = false)
  }

  @StringRes
  private fun rejectionText(reason: PlanRejection): Int = when (reason) {
    PlanRejection.READ_ONLY -> R.string.viewer_nas_reject_read_only
    PlanRejection.SOURCE_MISSING -> R.string.viewer_nas_reject_source_missing
    PlanRejection.NOT_ENOUGH_SPACE -> R.string.viewer_nas_reject_not_enough_space
    PlanRejection.NAS_UNREACHABLE -> R.string.viewer_nas_reject_unreachable
    PlanRejection.WRITES_BLOCKED -> R.string.viewer_nas_done_blocked
    else -> R.string.viewer_nas_reject_other
  }

  private companion object {
    /** Quanto si aspetta dopo l'ultimo carattere prima di chiedere il piano: non si interroga il NAS a ogni lettera. */
    const val NAME_CHECK_DELAY_MS = 250L

    /** R10: il lettore chiude il suo handle sul NAS in modo asincrono; un attimo prima di rinominare o cestinare. */
    const val RELEASE_SETTLE_MS = 500L
  }
}
