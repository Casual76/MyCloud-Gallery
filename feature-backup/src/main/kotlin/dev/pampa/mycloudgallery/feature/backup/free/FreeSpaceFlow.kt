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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `FreeSpaceState`, §2.5, D21; contratto `FreeSpace` di P0-B, logica di P9): la MACCHINA di "Libera
// spazio" lato UI. Porta il giro del contratto (piano → conferma → guardiano → verifica sul momento → lotti → dialogo del sistema) negli stati
// della pagina. **Non decide niente**: ogni regola (cosa entra nel piano, la validita' di 6 ore, il controllo del timbro prima di ogni dialogo, i
// lotti da 2.000) e' del servizio; qui si chiama il servizio nell'ordine del §2.5 e si racconta cio' che risponde. Nessun file si tocca da qui:
// l'unico effetto e' il dialogo del sistema, che parte solo da [free], cioe' da un tocco, e che il servizio ha gia' ricontrollato.
//
// E' una classe a parte (non del ViewModel) perche' non sa niente della UI: `:app` (P14) la puo' far girare anche in un lavoro avviato
// dall'utente con la notifica "Pronto: N elementi, X GB", con lo stesso servizio e gli stessi stati.

internal class FreeSpaceFlow(
  private val service: FreeSpace,
  private val scope: CoroutineScope,
  private val systemDialogs: Boolean,
  private val stepBudget: Duration = 20.seconds,
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

  // Per raccontare la fine: quanti passavano ai lotti e quanti ne erano gia' usciti, al primo "pronto".
  private var readyAtStart = 0
  private var droppedAtStart = 0

  private fun filter(kind: FreeSpaceKind) = FreeSpaceFilter(kind = kind, excludeRecentDays = excludeRecentDays, selection = selection)

  /** Conta cosa si puo' liberare con il filtro di adesso (dal catalogo locale: nessuna lettura del NAS, E-S11). */
  fun load() {
    job?.cancel()
    run = null
    job = scope.launch {
      flow.value = FreeSpaceUi.Idle
      if (!systemDialogs) {
        flow.value = FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG)
        return@launch
      }
      flow.value = FreeSpaceUi.Summary(service.plan(filter(kind), permanent = false), kind)
    }
  }

  /** Cambia il filtro e ricalcola. */
  fun choose(kind: FreeSpaceKind) {
    if (flow.value !is FreeSpaceUi.Summary) return
    this.kind = kind
    load()
  }

  /** Il piano di adesso per un modo (normale o "Libera subito"): e' quello che il foglio mostra e che la conferma firma. */
  suspend fun planNow(permanent: Boolean): FreeSpacePlan = service.plan(filter(kind), permanent)

  /**
   * Il tocco di conferma del foglio: [confirmation] e' nata dal foglio per [plan]. Il servizio la controlla contro il piano ricalcolato adesso,
   * fa il guardiano e la verifica sul momento a tappe di [stepBudget], e la pagina racconta l'avanzamento finche' non e' pronto.
   */
  fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation) {
    job?.cancel()
    job = scope.launch {
      flow.value = if (plan.needsGuardian > 0) FreeSpaceUi.Guarding(0, plan.needsGuardian) else FreeSpaceUi.Verifying(0, plan.itemCount, 0L)
      var current = service.verify(plan, confirmation, stepBudget)
      while (true) {
        publish(current)
        if (current.state != FreeSpaceRunState.GUARDING && current.state != FreeSpaceRunState.VERIFYING) break
        current = service.resume(current, stepBudget)
      }
    }
  }

  /**
   * "Libera ora": un lotto per volta. Il servizio ricontrolla il timbro di ogni copia e consegna il lotto; [dialog] lo mostra al sistema; la
   * risposta torna al servizio. Con piu' lotti la pagina torna a "pronto" e il prossimo dialogo vuole un altro tocco.
   */
  fun free(dialog: RemoveDialog) {
    val start = run ?: return
    job?.cancel()
    job = scope.launch {
      val batch = service.nextBatch(start)
      if (batch == null) {
        // Niente piu' lotti (finito, o la verifica e' scaduta mentre si aspettava): lo dice il giro.
        publish(service.resume(start, Duration.ZERO))
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
        flow.value = FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG)
      } else {
        publish(next)
      }
    }
  }

  /** "Annulla" durante la verifica: si smette di rileggere e si torna al riepilogo (un giro nuovo chiude quello di prima). */
  fun cancel() {
    job?.cancel()
    load()
  }

  /** Ferma ogni lavoro (la pagina si chiude). */
  fun stop() {
    job?.cancel()
  }

  /** Racconta il giro com'e': lo stato del contratto diventa lo stato della pagina. */
  private fun publish(current: FreeSpaceRun) {
    run = current
    flow.value = when (current.state) {
      FreeSpaceRunState.GUARDING -> FreeSpaceUi.Guarding(current.guardedDone, current.guardedTotal)
      FreeSpaceRunState.VERIFYING -> FreeSpaceUi.Verifying(current.verifiedDone, current.verifiedTotal, current.verifiedBytes)
      FreeSpaceRunState.READY -> {
        if (readyAtStart == 0) {
          readyAtStart = current.readyItems
          droppedAtStart = current.dropped.values.sum()
        }
        FreeSpaceUi.Ready(
          batchesDone = current.batchesDone,
          batchesTotal = current.batchesTotal,
          items = current.readyItems,
          bytes = current.readyBytes,
          permanent = current.permanent,
          validUntilMonotonicMs = current.validUntilMonotonicMs,
        )
      }
      FreeSpaceRunState.DONE, FreeSpaceRunState.CANCELLED -> finished(current)
      FreeSpaceRunState.EXPIRED -> FreeSpaceUi.Blocked(FreeSpaceBlockedBy.EXPIRED)
      FreeSpaceRunState.BLOCKED -> FreeSpaceUi.Blocked(FreeSpaceBlockedBy.of(current.blocked))
    }
    if (current.state.terminal) {
      readyAtStart = 0
      droppedAtStart = 0
    }
  }

  private fun finished(current: FreeSpaceRun): FreeSpaceUi.Done {
    val droppedLater = (current.dropped.values.sum() - droppedAtStart).coerceAtLeast(0)
    // Rimasti sul telefono perche' non confermati: i pronti di partenza meno i liberati e meno quelli usciti al controllo finale.
    val kept = (readyAtStart - current.freedItems - droppedLater).coerceAtLeast(0)
    return FreeSpaceUi.Done(
      items = current.freedItems,
      freedBytes = current.freedBytes,
      toAndroidTrash = !current.permanent,
      kept = kept,
      dropped = current.dropped.values.sum(),
      cancelled = current.state == FreeSpaceRunState.CANCELLED,
    )
  }
}
