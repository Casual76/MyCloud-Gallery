package dev.pampa.mycloudgallery.feature.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `FreeSpaceState`, §2.5, D21; contratto `FreeSpace` di P0-B, logica di P9): la MACCHINA di "Libera
// spazio" lato UI. Porta il giro del contratto (piano → conferma → guardiano → verifica sul momento → lotti → dialogo del sistema) negli stati
// della pagina. **Non decide niente**: ogni regola (cosa entra nel piano, la validita' di 6 ore, il controllo del timbro prima di ogni dialogo, i
// lotti da 2.000) e' del servizio; qui si chiama il servizio nell'ordine del §2.5 e si racconta cio' che risponde. Nessun file si tocca da qui:
// l'unico effetto e' il dialogo del sistema, che parte solo da [free], cioe' da un tocco, e che il servizio ha gia' ricontrollato.
//
// Correzioni 5B (20/10, LS-2): guardiano e verifica non girano piu' qui ma nel giro di [FreeSpaceRuns], che vive fuori dalla pagina (in `:app` un
// lavoro avviato dall'utente con la notifica "Pronto", ripreso dal diario dopo la morte del processo). La pagina lo segue, lo ritrova
// all'apertura, e porta i lotti al dialogo del sistema (che vuole un'activity viva).

internal class FreeSpaceFlow(
  private val service: FreeSpace,
  private val scope: CoroutineScope,
  private val systemDialogs: Boolean,
  /** Il giro fuori dalla pagina; senza porta lo porta avanti la pagina stessa (nel suo scope). */
  private val runs: FreeSpaceRuns = FreeSpaceDriver(service, scope),
  /** I media scelti dall'utente ("Libera spazio" sulla selezione, "Elimina solo dal telefono"): al massimo questi, null = tutto il sicuro. */
  private val selection: Set<MediaId>? = null,
  /** I giorni recenti che restano sul telefono: 30 di default (Q9), 0 per una scelta fatta a mano dall'utente (D22). */
  private val excludeRecentDays: Int = FreeSpaceRules.DEFAULT_EXCLUDE_RECENT_DAYS,
) {

  private val flow = MutableStateFlow<FreeSpaceUi>(FreeSpaceUi.Idle)

  val state: StateFlow<FreeSpaceUi> = flow

  /** Il filtro scelto dall'utente: tutto il sicuro, solo i video, piu' vecchi di un anno. */
  var kind: FreeSpaceKind = FreeSpaceKind.ALL
    private set

  private var run: FreeSpaceRun? = null
  private var job: Job? = null

  /** Il lotto e' dal servizio o nel dialogo: un secondo tocco su "Libera ora" non fa niente (LS-12: prima annullava il primo a meta'). */
  private var freeing: Job? = null

  /** La pagina segue il giro di [runs] (dopo una conferma, o un giro ritrovato all'apertura). */
  private var following = false

  /** LS-15: il piano del riepilogo, che il foglio di conferma riusa (prima si ricalcolava, senza indicatore, all'apertura del foglio). */
  private var summaryPlan: FreeSpacePlan? = null

  /** LS-13: quanti media l'utente ha confermato (il giro ne prende solo quelli ancora uguali: gli altri si dicono alla fine). */
  private var confirmedItems = 0

  init {
    // Il giro va avanti fuori dalla pagina: la pagina racconta ogni suo passo finche' lo segue.
    scope.launch {
      runs.current.collect { current -> if (following && current != null) publish(current) }
    }
    // LS-8: il guardiano aspetta il turno del backup (o lo riprende): la scheda lo dice anche se il giro non e' cambiato.
    scope.launch {
      runs.waitingForBackup.drop(1).collect { if (following) run?.let(::publish) }
    }
    // LS-10, LS-11: le parti del backup arrivano, l'ora del NAS diventa nota: il riepilogo (o l'attesa) si ricalcola da solo.
    scope.launch {
      combine(runs.ready, runs.nasTimeKnown) { ready, known -> ready to known }.distinctUntilChanged().drop(1).collect {
        val now = flow.value
        if (now is FreeSpaceUi.Summary || now == FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE) || now == FreeSpaceUi.Idle) load()
      }
    }
  }

  private fun filter(kind: FreeSpaceKind) = FreeSpaceFilter(kind = kind, excludeRecentDays = excludeRecentDays, selection = selection)

  /**
   * Apre la pagina: il giro rimasto aperto, se c'e' (LS-2: anche dopo la morte del processo, nei limiti di validita'); altrimenti conta cosa si
   * puo' liberare con il filtro di adesso (dal catalogo locale: nessuna lettura del NAS, E-S11).
   */
  fun load() {
    job?.cancel()
    run = null
    following = false
    summaryPlan = null
    job = scope.launch {
      flow.value = FreeSpaceUi.Idle
      if (!systemDialogs) {
        flow.value = FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG)
        return@launch
      }
      // LS-10: prima che il backup parta il piano sarebbe vuoto ("niente da liberare"): si dice che non e' ancora pronto, e si ricalcola dopo.
      if (!runs.ready.value) {
        flow.value = FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE)
        return@launch
      }
      runs.prepare()
      val active = runs.resumeActive()
      if (active != null) {
        following = true
        publish(active)
        return@launch
      }
      val plan = service.plan(filter(kind), permanent = false)
      summaryPlan = plan
      flow.value = FreeSpaceUi.Summary(plan, kind, nasTimeKnown = runs.nasTimeKnown.value)
    }
  }

  /** Cambia il filtro e ricalcola. */
  fun choose(kind: FreeSpaceKind) {
    if (flow.value !is FreeSpaceUi.Summary) return
    this.kind = kind
    load()
  }

  /**
   * Il piano che il foglio mostra e che la conferma firma. LS-15: quello del riepilogo, se e' ancora lui sullo schermo (per "Libera subito" lo
   * stesso piano, definitivo); la conferma vale comunque solo per gli elementi che il servizio trova ancora uguali (LS-13).
   */
  suspend fun planNow(permanent: Boolean): FreeSpacePlan {
    val shown = summaryPlan?.takeIf { flow.value is FreeSpaceUi.Summary && it.filter == filter(kind) }
    return when {
      shown == null -> service.plan(filter(kind), permanent)
      shown.permanent == permanent -> shown
      else -> FreeSpacePlan(shown.filter, permanent, shown.items, shown.excluded, shown.needsGuardian)
    }
  }

  /**
   * Il tocco di conferma del foglio: [confirmation] e' nata dal foglio per [plan]. Il giro comincia (guardiano e verifica sul momento) e va avanti
   * anche fuori dalla pagina; la pagina racconta l'avanzamento finche' non e' pronto.
   */
  fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation) {
    job?.cancel()
    confirmedItems = plan.itemCount
    following = true
    flow.value = if (plan.needsGuardian > 0) FreeSpaceUi.Guarding(0, plan.needsGuardian) else FreeSpaceUi.Verifying(0, plan.itemCount, 0L)
    runs.start(plan, confirmation)
  }

  /**
   * "Libera ora": un lotto per volta. Il servizio ricontrolla il timbro di ogni copia e consegna il lotto; [dialog] lo mostra al sistema; la
   * risposta torna al servizio. Con piu' lotti la pagina torna a "pronto" e il prossimo dialogo vuole un altro tocco.
   */
  fun free(dialog: RemoveDialog) {
    val start = run ?: return
    if (freeing?.isActive == true) return
    job?.cancel()
    val launched = scope.launch {
      val batch = service.nextBatch(start)
      if (batch == null) {
        // Niente piu' lotti (finito, o la verifica e' scaduta mentre si aspettava): lo dice il giro.
        report(service.resume(start, kotlin.time.Duration.ZERO))
        return@launch
      }
      flow.value = FreeSpaceUi.Confirming(batch.index, batch.of, permanent = batch.mode == TrashMode.DELETE_NOW)
      val answer = dialog.ask(batch)
      val outcome = when (answer) {
        DialogAnswer.ALLOWED -> TrashOutcome.Confirmed(batch.items.mapTo(LinkedHashSet()) { it.phone })
        DialogAnswer.REFUSED -> TrashOutcome.Refused
        // Nessun dialogo e' comparso: il lotto era gia' consegnato, quindi si lascia decidere alla prova del telefono, mai a un'ipotesi.
        DialogAnswer.NOT_AVAILABLE -> TrashOutcome.Unknown
      }
      val next = service.recordOutcome(batch, outcome)
      if (answer == DialogAnswer.NOT_AVAILABLE) {
        run = next
        // La pagina dice che il dialogo non c'e' (non il giro): smette di seguirlo, ma il giro lo sa (la notifica si toglie).
        following = false
        runs.update(next)
        flow.value = FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG)
      } else {
        report(next)
      }
    }
    freeing = launched
    job = launched
  }

  /** Il giro portato avanti dalla pagina: lo sa anche [runs] (e con lui la notifica), e la pagina lo racconta. */
  private fun report(next: FreeSpaceRun) {
    runs.update(next)
    publish(next)
  }

  /** "Annulla" durante la verifica: il giro si chiude (non riparte piu') e si torna al riepilogo. */
  fun cancel() {
    job?.cancel()
    following = false
    job = scope.launch {
      runs.cancel()
      load()
    }
  }

  /** La pagina si chiude: il giro, se c'e', va avanti fuori (LS-2); qui si smette solo di guardarlo. */
  fun stop() {
    job?.cancel()
    following = false
  }

  /** Racconta il giro com'e': lo stato del contratto diventa lo stato della pagina. */
  private fun publish(current: FreeSpaceRun) {
    run = current
    flow.value = when (current.state) {
      FreeSpaceRunState.GUARDING -> FreeSpaceUi.Guarding(current.guardedDone, current.guardedTotal, runs.waitingForBackup.value)
      FreeSpaceRunState.VERIFYING -> FreeSpaceUi.Verifying(current.verifiedDone, current.verifiedTotal, current.verifiedBytes)
      FreeSpaceRunState.READY -> FreeSpaceUi.Ready(
        batchesDone = current.batchesDone,
        batchesTotal = current.batchesTotal,
        items = current.readyItems,
        bytes = current.readyBytes,
        permanent = current.permanent,
        validUntilMonotonicMs = current.validUntilMonotonicMs,
      )
      FreeSpaceRunState.DONE, FreeSpaceRunState.CANCELLED -> finished(current)
      FreeSpaceRunState.EXPIRED -> FreeSpaceUi.Blocked(FreeSpaceBlockedBy.EXPIRED)
      FreeSpaceRunState.BLOCKED -> FreeSpaceUi.Blocked(FreeSpaceBlockedBy.of(current.blocked))
    }
  }

  private fun finished(current: FreeSpaceRun): FreeSpaceUi.Done = FreeSpaceUi.Done(
    items = current.freedItems,
    freedBytes = current.freedBytes,
    toAndroidTrash = !current.permanent,
    // Rimasti sul telefono: i lotti non confermati e, dopo un "no", quelli che non sono piu' stati proposti.
    kept = current.kept + current.readyItems,
    dropped = current.dropped.values.sum(),
    cancelled = current.state == FreeSpaceRunState.CANCELLED,
    droppedBy = current.dropped,
    changedBeforeStart = (confirmedItems - current.verifiedTotal).coerceAtLeast(0).takeIf { confirmedItems > 0 } ?: 0,
    inDoubt = current.inDoubt,
  )
}
