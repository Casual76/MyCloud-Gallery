package dev.pampa.mycloudgallery.feature.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationCheck
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetNotice
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// IL FOGLIO DI CONDIVISIONE (Fase 7, P10; docs/fase7-progetto.md D12, D35, §1.5 `ShareSheetState`, §2.5 passi 1-2; C10, C26, E-SH9, E-SH13, E-SH19).
//
// **La regola piu' importante della fase**: uno spostamento nell'area comune parte solo da un'azione locale confermata. La conferma
// ([dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation], costruttore `internal`) la emette **soltanto** [confirm], cioe' il tocco sul
// bottone del foglio, attraverso [ShareConfirmationSheet] (che scarta un tocco arrivato meno di 600 ms dopo la comparsa del foglio: il secondo
// colpo di un doppio tocco sulla voce del menu non conferma niente, R6 della 4B). Nessun altro metodo di questa classe, nessun dato letto dal NAS
// e nessuna ripresa del processo puo' produrre una conferma: il piano viene da `ShareRunner.plan` (solo dal catalogo locale) e lo `start`
// ricontrolla la conferma contro il piano (impronta, eta', futuro).
//
// Il foglio e' uno solo per ogni genere di piano: album nuovo, "Condividi" su un personale, "Aggiungi ad album" (anche "Da condividere"),
// "Riporta nelle mie cartelle" e adozione dei file aggiunti dal PC. Un cambio di scelta (i nascosti, il livello) rifa' il piano: la conferma
// vale per **quello che si vede**.

class ShareSheetController(
  private val runner: ShareRunner,
  private val clock: AlbumScreensClock,
  private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow<ShareSheetState?>(null)

  /** Il foglio di adesso, o null se non e' aperto. */
  val state: StateFlow<ShareSheetState?> = _state.asStateFlow()

  private var sheet: ShareConfirmationSheet? = null

  @Volatile private var refreshing = false

  private var after: (suspend () -> Unit)? = null

  /** L'album a cui e' stato avviato l'ultimo piano (per portare l'utente alla sua pagina), dopo [ShareSheetState.Started]. */
  var startedAlbum: AlbumId? = null
    private set

  /**
   * Prepara il foglio per [request]: il piano si chiede al catalogo locale (niente si scrive) e, se c'e', il foglio si apre. [then] gira solo
   * dopo uno `start` riuscito (il foglio di chiusura lo usa per chiudere l'album dopo "Riporta"); se il foglio si chiude senza partire, non gira.
   */
  fun request(request: ShareRequest, then: (suspend () -> Unit)? = null) {
    after = then
    scope.launch { open(runner.plan(request), notice = null) }
  }

  /** Il piano gia' pronto di `Albums.add` su un album condiviso (D12, D35): [AddOutcome.NeedsShareConfirmation]. Altri esiti non aprono il foglio. */
  fun onAddOutcome(outcome: AddOutcome) {
    after = null
    when (outcome) {
      is AddOutcome.NeedsShareConfirmation -> show(outcome.plan)
      is AddOutcome.Blocked -> _state.value = ShareSheetState.Blocked(outcome.reason)
      is AddOutcome.NothingToAdd -> _state.value = ShareSheetState.NothingToShare(outcome.excluded)
      is AddOutcome.NotAllowed -> _state.value = ShareSheetState.Refused(outcome.op)
      is AddOutcome.Added, AddOutcome.NotFound -> Unit
    }
  }

  /** Un esito di piano gia' avuto da `SharedAlbums.create` (o da chi ha chiesto il piano per conto suo): pronto, bloccato, niente da condividere... */
  fun onSharePlan(outcome: SharePlanOutcome) {
    after = null
    open(outcome, notice = null)
  }

  /** Mostra [plan] (gia' pronto). Il foglio comincia a contare i 600 ms da adesso. */
  fun show(plan: SharePlan, notice: ShareSheetNotice? = null) {
    sheet = ShareConfirmationSheet(plan, shownAtMonotonicMs = clock.monotonicMs())
    _state.value = ShareSheetState.Plan(plan, plan.hidden, plan.level, notice)
  }

  /** «Lascia fuori i N nascosti» o «Condividi anche i nascosti» (E-SH9): il piano si rifa' e la conferma vale per il nuovo. */
  fun setHiddenChoice(choice: HiddenChoice) {
    val current = _state.value as? ShareSheetState.Plan ?: return
    if (choice == current.plan.hidden) return
    val changed = when (val r = current.plan.request) {
      is ShareRequest.NewAlbum -> r.copy(hidden = choice)
      is ShareRequest.FromPersonal -> r.copy(hidden = choice)
      is ShareRequest.AddToShared -> r.copy(hidden = choice)
      is ShareRequest.BringBack, is ShareRequest.FolderAdoption -> return
    }
    replan(changed)
  }

  /** Il livello di un album nuovo (Q2: di partenza "Gli altri aggiungono"): il piano si rifa'. */
  fun setLevel(level: ShareLevel) {
    val current = _state.value as? ShareSheetState.Plan ?: return
    if (current.plan.level == level) return
    val changed = when (val r = current.plan.request) {
      is ShareRequest.NewAlbum -> r.copy(level = level)
      is ShareRequest.FromPersonal -> r.copy(level = level)
      else -> return
    }
    replan(changed)
  }

  private fun replan(request: ShareRequest) {
    refreshing = true
    scope.launch {
      try {
        open(runner.plan(request), notice = null)
      } finally {
        refreshing = false
      }
    }
  }

  private fun open(outcome: SharePlanOutcome, notice: ShareSheetNotice?) {
    when (outcome) {
      is SharePlanOutcome.Ready -> show(outcome.plan, notice)
      is SharePlanOutcome.Blocked -> _state.value = ShareSheetState.Blocked(outcome.reason)
      is SharePlanOutcome.NothingToShare -> _state.value = ShareSheetState.NothingToShare(outcome.excluded)
      is SharePlanOutcome.NotAllowed -> _state.value = ShareSheetState.Refused(outcome.op)
      is SharePlanOutcome.InvalidTitle -> _state.value = ShareSheetState.InvalidTitle(outcome.problem)
    }
  }

  /**
   * IL TOCCO DI CONFERMA. Solo da qui nasce una conferma. Un tocco troppo vicino alla comparsa del foglio, un tocco mentre il piano si sta
   * rifacendo o mentre la richiesta e' gia' partita non conta. Se il piano non vale piu' (il catalogo e' cambiato, o e' scaduto) il foglio si
   * rifa' con l'avviso e **non** parte niente: l'utente rilegge i conteggi e conferma di nuovo.
   */
  fun confirm() {
    val current = _state.value as? ShareSheetState.Plan ?: return
    if (refreshing) return
    val confirmation = sheet?.tap(clock.monotonicMs()) ?: return
    _state.value = ShareSheetState.Confirming
    scope.launch {
      when (val out = runner.start(current.plan, confirmation)) {
        is ShareStartOutcome.Started -> started(current.plan, out.op)
        is ShareStartOutcome.AlreadyStarted -> started(current.plan, out.op)
        is ShareStartOutcome.PlanChanged -> replanFrom(current, ShareSheetNotice.CHANGED)
        is ShareStartOutcome.ConfirmationRejected -> when (out.check) {
          ShareConfirmationCheck.Valid -> replanFrom(current, ShareSheetNotice.CHANGED)
          ShareConfirmationCheck.Expired, ShareConfirmationCheck.FromTheFuture, ShareConfirmationCheck.WrongPlan -> replanFrom(current, ShareSheetNotice.EXPIRED)
        }
        is ShareStartOutcome.Blocked -> _state.value = ShareSheetState.Blocked(out.reason)
      }
    }
  }

  private suspend fun started(plan: SharePlan, op: ShareOpId) {
    startedAlbum = plan.album
    _state.value = ShareSheetState.Started(op)
    val next = after
    after = null
    sheet = null
    next?.invoke()
  }

  private suspend fun replanFrom(previous: ShareSheetState.Plan, notice: ShareSheetNotice) {
    // Per un album nuovo il piano rifatto ha un'identita' nuova (nonce): e' un foglio nuovo, con la sua impronta.
    open(runner.plan(previous.plan.request), notice)
  }

  /** Il foglio si chiude senza partire (o dopo `Started`): niente resta in sospeso. */
  fun dismiss() {
    _state.value = null
    sheet = null
    after = null
  }
}
