package dev.pampa.mycloudgallery.feature.integrations.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Fase 5B (P11, docs/fase5-progetto.md §1.5 `IncomingState`, §2.7, D24, C17, E-X2, E-X7, E-X8, E-S11): lo stato di "Salva sul NAS". Decide cosa si
// vede e quando qualcosa puo' succedere; non sa niente di Compose.
//
//   1. il **cancello** (`EntryGate`): pronta e sbloccata; bloccata (prima lo sblocco, E-X7: finche' non c'e' non si apre nessun flusso e non si copia
//      niente); non pronta. Se il cancello si richiude dopo che si era partiti, la schermata torna a mostrare solo lo sblocco: il riepilogo e i nomi
//      delle cartelle non restano in vista;
//   2. la **copia immediata**: appena il cancello e' aperto i flussi si copiano nel telefono (il permesso dell'altra app dura quanto l'activity),
//      con i tetti; la schermata mostra solo numeri e tipi, mai i nomi dei file;
//   3. la **scelta della cartella** dalle Cartelle NAS (area comune con l'avviso "lo vedranno tutti i familiari");
//   4. un **tocco** su "Salva sul NAS": solo allora nasce la conferma ([PhoneConfirmationSheet] sul [IncomingSavePlan]) e la condivisione va in
//      coda. Mai niente da solo (E-S11). Annullare o chiudere toglie le copie.

/** Che cosa mostra la schermata. */
enum class ReceiveScreenState {
  /** Non ancora deciso: non si mostra niente. */
  CHECKING,

  /** L'app e' bloccata (E-X7): solo lo sblocco. Niente e' stato letto. */
  LOCKED,

  /** L'app non e' pronta (onboarding, famiglia, dispositivo): non si offre niente. */
  NOT_READY,

  /** I file si stanno copiando. */
  STAGING,

  /** Copiati: si sceglie la cartella e si tocca "Salva sul NAS". */
  READY,

  /** Nessun file e' stato accettato (tutti rifiutati, o nessuna cartella dove salvare). */
  NOTHING,

  /** "Salva sul NAS" e' in corso. */
  SAVING,

  /** In coda: si caricheranno appena si puo'. */
  DONE,

  /** Il salvataggio non e' andato a buon fine. */
  FAILED,
}

/** Perche' il salvataggio non e' andato a buon fine. */
enum class ReceiveFailure {
  /** La conferma non vale (vecchia, di un altro piano). */
  CONFIRMATION,

  /** In quella cartella non si puo' scrivere (sola lettura, area non tua). */
  DESTINATION,

  /** I file copiati non ci sono piu' o non sono piu' quelli. */
  GONE,

  /** Il diario non ha accettato (nessun backup configurato, share di prova). */
  UNAVAILABLE,
}

/** Lo stato della schermata. Nessun nome di file, percorso o id. */
class ReceiveUi(
  val screen: ReceiveScreenState = ReceiveScreenState.CHECKING,
  val stage: IncomingStage? = null,
  val photos: Int = 0,
  val videos: Int = 0,
  val bytes: Long = 0,
  val rejected: Map<IncomingReject, Int> = emptyMap(),
  /** Il percorso di navigazione: le cartelle aperte, dalla prima. Vuoto = le cartelle di partenza. */
  val trail: List<DestinationChoice> = emptyList(),
  val choices: List<DestinationChoice> = emptyList(),
  val selected: DestinationChoice? = null,
  val queued: Int = 0,
  val nasReachable: Boolean = true,
  val failure: ReceiveFailure? = null,
  val unlocking: Boolean = false,
  val unlockFailed: Boolean = false,
) {
  fun copy(
    screen: ReceiveScreenState = this.screen,
    stage: IncomingStage? = this.stage,
    photos: Int = this.photos,
    videos: Int = this.videos,
    bytes: Long = this.bytes,
    rejected: Map<IncomingReject, Int> = this.rejected,
    trail: List<DestinationChoice> = this.trail,
    choices: List<DestinationChoice> = this.choices,
    selected: DestinationChoice? = this.selected,
    queued: Int = this.queued,
    nasReachable: Boolean = this.nasReachable,
    failure: ReceiveFailure? = this.failure,
    unlocking: Boolean = this.unlocking,
    unlockFailed: Boolean = this.unlockFailed,
  ): ReceiveUi = ReceiveUi(screen, stage, photos, videos, bytes, rejected, trail, choices, selected, queued, nasReachable, failure, unlocking, unlockFailed)

  /** Il foglio avvisa "Lo vedranno tutti i familiari" quando la cartella scelta e' dell'area comune (D24). */
  val commonNotice: Boolean get() = selected?.destination?.area == DestinationArea.COMMON

  override fun toString(): String = "ReceiveUi($screen, $photos foto, $videos video)"
}

/** Come finisce la schermata: l'activity si chiude. */
enum class ReceiveOutcome { FINISHED }

class ReceiveViewModel(
  /** Da dove vengono i flussi: l'activity li costruisce dall'intento (`IncomingStreams`), la prova da una lista. */
  private val streams: suspend () -> List<IncomingStream>,
  private val overflow: Int,
  private val gate: EntryGate,
  private val uploads: IncomingUploads,
  private val destinations: IncomingDestinations,
  /** Orologio monotono per la conferma (`SystemClock.elapsedRealtime`); mai l'ora del telefono. */
  private val monotonicMs: () -> Long,
  scope: CoroutineScope? = null,
) : ViewModel() {

  private val scope: CoroutineScope = scope ?: viewModelScope

  private val _ui = MutableStateFlow(ReceiveUi())

  /**
   * Cio' che la schermata mostra. Se il cancello si richiude dopo che si era partiti (il blocco dell'app scatta mentre si e' in un'altra app) mostra
   * solo lo sblocco: il riepilogo e i nomi delle cartelle non restano in vista (E-X7). Il lavoro in corso prosegue; lo stato vero riappare allo sblocco.
   */
  val ui: StateFlow<ReceiveUi> = _ui.asStateFlow()

  private val _outcome = MutableStateFlow<ReceiveOutcome?>(null)

  /** Quando non e' null la schermata ha finito: l'activity si chiude. */
  val outcome: StateFlow<ReceiveOutcome?> = _outcome.asStateFlow()

  private var started = false
  private var saved = false
  private var stagingJob: Job? = null
  private var savingJob: Job? = null

  /** Se il cancello e' chiuso dopo la partenza, che cosa copre la schermata; lo stato vero sta in [hiddenState]. */
  private var hiddenBy: ReceiveScreenState? = null
  private var hiddenState: ReceiveUi = ReceiveUi()

  /** Lo stato vero, sia che la schermata lo mostri sia che sia coperto dal cancello. */
  private fun core(): ReceiveUi = synchronized(this) { if (hiddenBy != null) hiddenState else _ui.value }

  /** Cambia lo stato vero; se e' coperto, cambia quello nascosto e la schermata non si muove. */
  private fun mutate(change: (ReceiveUi) -> ReceiveUi) {
    synchronized(this) {
      if (hiddenBy != null) hiddenState = change(hiddenState) else _ui.update(change)
    }
  }

  // --- Il cancello (E-X7) -----------------------------------------------------------------------------------------------

  /** Rilegge il cancello. Si chiama a ogni ritorno in primo piano: il blocco puo' essersi chiuso mentre si era altrove. */
  fun refreshGate() {
    if (_outcome.value != null) return
    when (gate.decide()) {
      EntryDecision.ALLOWED -> {
        reveal()
        begin()
      }
      EntryDecision.NEEDS_UNLOCK -> cover(ReceiveScreenState.LOCKED, ReceiveCounter.LOCKED)
      EntryDecision.NOT_READY -> cover(ReceiveScreenState.NOT_READY, ReceiveCounter.NOT_READY)
    }
  }

  /** Il cancello e' chiuso: la schermata mostra solo [screen]; se si era partiti, lo stato vero si nasconde. */
  private fun cover(screen: ReceiveScreenState, counter: ReceiveCounter) {
    synchronized(this) {
      if (_ui.value.screen == screen) return
      FlowCounters.receive.add(counter)
      if (started && hiddenBy == null) hiddenState = _ui.value
      if (started) hiddenBy = screen
      _ui.value = ReceiveUi(screen = screen)
    }
  }

  /** Il cancello e' aperto: se qualcosa era coperto, torna in vista. */
  private fun reveal() {
    synchronized(this) {
      if (hiddenBy == null) return
      hiddenBy = null
      _ui.value = hiddenState
    }
  }

  fun beginUnlock(): Boolean {
    if (_ui.value.unlocking || _ui.value.screen != ReceiveScreenState.LOCKED) return false
    _ui.update { it.copy(unlocking = true, unlockFailed = false) }
    return true
  }

  fun endUnlock(unlocked: Boolean) {
    _ui.update { it.copy(unlocking = false, unlockFailed = !unlocked) }
    if (unlocked) refreshGate()
  }

  // --- La copia immediata -------------------------------------------------------------------------------------------------

  private fun begin() {
    if (started) return
    started = true
    mutate { it.copy(screen = ReceiveScreenState.STAGING, unlockFailed = false) }
    stagingJob = scope.launch {
      val stage = try {
        uploads.stage(streams())
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      if (stage == null) {
        mutate { it.copy(screen = ReceiveScreenState.NOTHING, rejected = mapOf(IncomingReject.UNREADABLE to 1)) }
        return@launch
      }
      // Gli indirizzi oltre il tetto non si sono nemmeno guardati: si contano fra i "troppi".
      val rejected = if (overflow > 0) stage.rejected + (IncomingReject.TOO_MANY to ((stage.rejected[IncomingReject.TOO_MANY] ?: 0) + overflow)) else stage.rejected
      val photos = stage.accepted.count { it.mime.startsWith("image/") }
      val roots = if (stage.accepted.isEmpty()) emptyList() else destinations.roots()
      if (stage.accepted.isEmpty() || roots.isEmpty()) {
        // Niente di buono, o nessuna cartella dove salvare: le copie, se ci sono, si tolgono subito.
        if (stage.accepted.isNotEmpty()) uploads.discard(stage.id)
        mutate { it.copy(screen = ReceiveScreenState.NOTHING, rejected = rejected, stage = null) }
        return@launch
      }
      mutate {
        it.copy(
          screen = ReceiveScreenState.READY,
          stage = stage,
          photos = photos,
          videos = stage.accepted.size - photos,
          bytes = stage.totalBytes,
          rejected = rejected,
          choices = roots,
          trail = emptyList(),
          selected = roots.first(),
        )
      }
    }
  }

  // --- La cartella ---------------------------------------------------------------------------------------------------------

  /** Sceglie [choice] come cartella di destinazione. */
  fun select(choice: DestinationChoice) {
    if (_ui.value.screen != ReceiveScreenState.READY) return
    mutate { it.copy(selected = choice) }
  }

  /** Apre [choice] per vederne le sottocartelle; la scelta passa a lei. */
  fun open(choice: DestinationChoice) {
    if (_ui.value.screen != ReceiveScreenState.READY) return
    scope.launch {
      val children = destinations.children(choice)
      mutate { current ->
        if (current.screen != ReceiveScreenState.READY) current else current.copy(trail = current.trail + choice, choices = children, selected = choice)
      }
    }
  }

  /** Torna alla cartella di sopra (o alle cartelle di partenza). */
  fun up() {
    if (_ui.value.screen != ReceiveScreenState.READY || _ui.value.trail.isEmpty()) return
    scope.launch {
      val trail = _ui.value.trail.dropLast(1)
      val choices = if (trail.isEmpty()) destinations.roots() else destinations.children(trail.last())
      mutate { current ->
        if (current.screen != ReceiveScreenState.READY) current else current.copy(trail = trail, choices = choices, selected = trail.lastOrNull() ?: choices.firstOrNull())
      }
    }
  }

  // --- "Salva sul NAS" (E-S11) --------------------------------------------------------------------------------------------

  /** Il tocco dell'utente su "Salva sul NAS": la conferma nasce **qui**, dal piano di adesso, e solo da questo gesto. */
  fun save() {
    val current = _ui.value
    val stage = current.stage ?: return
    val destination = current.selected?.destination ?: return
    // Con il cancello chiuso la schermata non mostra il bottone e niente si salva.
    if (current.screen != ReceiveScreenState.READY || hiddenBy != null || savingJob?.isActive == true) return
    // Il cancello un'ultima volta: se il blocco si e' richiuso mentre si sceglieva, non si salva niente.
    if (gate.decide() != EntryDecision.ALLOWED) {
      refreshGate()
      return
    }
    mutate { it.copy(screen = ReceiveScreenState.SAVING) }
    savingJob = scope.launch {
      val plan = IncomingSavePlan(stage, destination)
      val confirmation = PhoneConfirmationSheet(plan).tap(monotonicMs())
      val outcome = if (confirmation == null) {
        IncomingSaveOutcome.Unavailable
      } else {
        try {
          uploads.save(stage, destination, confirmation)
        } catch (e: CancellationException) {
          throw e
        } catch (_: IOException) {
          IncomingSaveOutcome.Unavailable
        }
      }
      when (outcome) {
        is IncomingSaveOutcome.Queued -> {
          saved = true
          val reachable = try {
            destinations.nasReachable()
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            false
          }
          mutate { it.copy(screen = ReceiveScreenState.DONE, queued = outcome.items, nasReachable = reachable) }
        }
        is IncomingSaveOutcome.Rejected -> fail(ReceiveFailure.CONFIRMATION)
        IncomingSaveOutcome.DestinationNotAllowed -> fail(ReceiveFailure.DESTINATION)
        IncomingSaveOutcome.StageGone -> fail(ReceiveFailure.GONE)
        IncomingSaveOutcome.Unavailable -> fail(ReceiveFailure.UNAVAILABLE)
      }
    }
  }

  private fun fail(reason: ReceiveFailure) {
    mutate { it.copy(screen = ReceiveScreenState.FAILED, failure = reason) }
  }

  /** Dopo un errore si torna alla scelta della cartella (le copie ci sono ancora), se ci sono. */
  fun retry() {
    val current = _ui.value
    if (current.screen != ReceiveScreenState.FAILED) return
    if (current.failure == ReceiveFailure.GONE || current.stage == null) {
      cancel()
    } else {
      mutate { it.copy(screen = ReceiveScreenState.READY, failure = null) }
    }
  }

  // --- Chiudere ------------------------------------------------------------------------------------------------------------

  /** L'utente rinuncia (o chiude): le copie si tolgono e la schermata finisce. */
  fun cancel() {
    // Un salvataggio in corso non si interrompe a meta': o e' in coda o non lo e'.
    if (savingJob?.isActive == true) return
    stagingJob?.cancel()
    discardUnsaved()
    _outcome.value = ReceiveOutcome.FINISHED
  }

  /** Chiude dopo "In coda": le copie sono del diario, non si toccano. */
  fun finish() {
    _outcome.value = ReceiveOutcome.FINISHED
  }

  override fun onCleared() {
    // L'activity e' finita senza una scelta (indietro, chiusa da fuori): le copie non salvate si tolgono.
    discardUnsaved()
    super.onCleared()
  }

  private fun discardUnsaved() {
    val stage = core().stage ?: return
    if (saved || savingJob?.isActive == true) return
    // Una portata a parte: il ViewModel sta morendo e la sua scope con lui; togliere una cartella e' un attimo, ma deve succedere.
    DETACHED.launch { uploads.discard(stage.id) }
    mutate { it.copy(stage = null) }
  }

  private companion object {
    val DETACHED = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}
