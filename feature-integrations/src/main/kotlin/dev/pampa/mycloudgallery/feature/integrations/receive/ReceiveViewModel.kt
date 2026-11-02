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
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.drag.DropHint
import dev.pampa.mycloudgallery.feature.integrations.picker.UnlockFailure
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
//      coda. Mai niente da solo (E-S11). Annullare o chiudere toglie le copie;
//   5. una **condivisione nuova** mentre la schermata e' aperta (`onNewIntent`, campo A, D1 e CA-2): prende il posto della vecchia **nella stessa schermata**
//      ([replaceRequest]). Prima la schermata lanciava la nuova in un task suo e chiudeva la vecchia: se Android bloccava l'avvio in silenzio la nuova si
//      perdeva e la vecchia spariva comunque. Adesso la nuova si copia per prima (il permesso di lettura che porta `onNewIntent` vale per questa activity);
//      la vecchia, se non era salvata, si toglie solo dopo che la nuova e' al sicuro, e una gia' in coda resta del diario.

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

  /**
   * IN-6 della verifica funzionale 5B: il backup e' ancora in prova (D12, R2): in questa versione "Salva sul NAS" non scrive da nessuna parte.
   * Si dice **prima di copiare** (prima si copiavano fino a 4 GiB e poi si diceva "In quella cartella non si puo' scrivere" per ogni cartella).
   */
  TEST_ONLY,

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

  /** IN-6: il backup e' ancora in prova (D12, R2): sul NAS non si scrive. Non e' colpa della cartella scelta. */
  TEST_ONLY,
}

/** IN-11: perche' i file in coda non partono subito, se il NAS non risponde: spento/fuori casa, oppure dati mobili senza consenso. */
enum class NasWait {
  /** Il NAS risponde: la coda parte da sola. */
  NONE,

  /** Il NAS non risponde adesso. */
  OFFLINE,

  /** Dati mobili: i file aspettano il Wi-Fi di casa (non e' che il NAS non risponda). */
  METERED,
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
  /** IN-11: perche' la coda non parte subito, detto dopo "In coda" (la prova del NAS non fa piu' aspettare la schermata). */
  val nasWait: NasWait = NasWait.NONE,
  val failure: ReceiveFailure? = null,
  val unlocking: Boolean = false,
  val unlockFailed: Boolean = false,
  /** IN-10: perche' lo sblocco non e' riuscito, se lo si sa. */
  val unlockFailure: UnlockFailure? = null,
  /** IN-11: dei file copiati non c'e' nessuna cartella dove salvarli (anche se altri file sono stati rifiutati). */
  val noFolder: Boolean = false,
  /** IN-12: file copiati che al tocco non si leggevano piu' e non sono in coda. */
  val notQueued: Int = 0,
  /** IN-11: la cartella scelta non ha altre cartelle (che la Libreria conosca): "Apri la cartella scelta" lo dice invece di mostrare una lista vuota. */
  val noSubfolders: Boolean = false,
  /** Fase 7 (P10, D35): gli album dove "Aggiungi ad album" puo' mettere i file; vuoto = la voce non compare. */
  val albums: List<IncomingAlbumChoice> = emptyList(),
  /** La scelta dell'album e' aperta al posto della scelta della cartella. */
  val albumPick: Boolean = false,
  val selectedAlbum: IncomingAlbumChoice? = null,
  /** Dopo "In coda": l'album in cui andranno i file appena sono nel catalogo (D35), o null se non e' una richiesta di album. */
  val addedToAlbum: IncomingAlbumChoice? = null,
  /** "Aggiungi ad album": la promessa non si e' potuta registrare (i file sono comunque in coda). */
  val albumNotRegistered: Boolean = false,
  /** AA-2: dalla scelta dell'album si puo' creare un «Nuovo album» (anche senza album: la voce "Aggiungi ad album" c'e' lo stesso). */
  val canCreateAlbum: Boolean = false,
  val creatingAlbum: Boolean = false,
  /** AA-2: perche' «Nuovo album» non e' riuscito, o null. */
  val newAlbumProblem: NewAlbumProblem? = null,
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
    nasWait: NasWait = this.nasWait,
    failure: ReceiveFailure? = this.failure,
    unlocking: Boolean = this.unlocking,
    unlockFailed: Boolean = this.unlockFailed,
    unlockFailure: UnlockFailure? = this.unlockFailure,
    noFolder: Boolean = this.noFolder,
    notQueued: Int = this.notQueued,
    noSubfolders: Boolean = this.noSubfolders,
    albums: List<IncomingAlbumChoice> = this.albums,
    albumPick: Boolean = this.albumPick,
    selectedAlbum: IncomingAlbumChoice? = this.selectedAlbum,
    addedToAlbum: IncomingAlbumChoice? = this.addedToAlbum,
    albumNotRegistered: Boolean = this.albumNotRegistered,
    canCreateAlbum: Boolean = this.canCreateAlbum,
    creatingAlbum: Boolean = this.creatingAlbum,
    newAlbumProblem: NewAlbumProblem? = this.newAlbumProblem,
  ): ReceiveUi = ReceiveUi(
    screen, stage, photos, videos, bytes, rejected, trail, choices, selected, queued, nasWait, failure, unlocking, unlockFailed, unlockFailure, noFolder,
    notQueued, noSubfolders, albums, albumPick, selectedAlbum, addedToAlbum, albumNotRegistered, canCreateAlbum, creatingAlbum, newAlbumProblem,
  )

  /** Il foglio avvisa "Lo vedranno tutti i familiari" quando la cartella scelta e' dell'area comune (D24). */
  val commonNotice: Boolean get() = selected?.destination?.area == DestinationArea.COMMON

  override fun toString(): String = "ReceiveUi($screen, $photos foto, $videos video)"
}

/** AA-2: perche' «Nuovo album» dalla ricezione non e' riuscito. */
enum class NewAlbumProblem {
  /** Il nome non va bene (vuoto, troppo lungo, caratteri vietati). */
  INVALID_TITLE,

  /** Gli album non sono pronti (nessuna famiglia): non si crea adesso. */
  UNAVAILABLE,
}

/** Come finisce la schermata: l'activity si chiude. */
enum class ReceiveOutcome { FINISHED }

class ReceiveViewModel(
  /** Da dove vengono i flussi: l'activity li costruisce dall'intento (`IncomingStreams`), la prova da una lista. */
  streams: suspend () -> List<IncomingStream>,
  overflow: Int,
  private val gate: EntryGate,
  private val uploads: IncomingUploads,
  private val destinations: IncomingDestinations,
  /** Orologio monotono per la conferma (`SystemClock.elapsedRealtime`); mai l'ora del telefono. */
  private val monotonicMs: () -> Long,
  scope: CoroutineScope? = null,
  /** "Aggiungi ad album" (Fase 7, P10, D35): di serie nessun album, quindi nessuna voce. */
  private val albums: IncomingAlbums = NoIncomingAlbums,
  /** Fase 10 (P6, D21): il trascinamento in arrivo dice da dove comincia il foglio (un album, una cartella). Mai dall'intento: lo porta `DropHandoff`. */
  hint: DropHint? = null,
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

  /** I flussi della condivisione di adesso: cambiano solo con [replaceRequest]. */
  private var streams: suspend () -> List<IncomingStream> = streams
  private var overflow: Int = overflow

  private var started = false
  private var saved = false
  private var stagingJob: Job? = null
  private var savingJob: Job? = null
  private var albumsJob: Job? = null
  private var loadingJob: Job? = null

  /** Fase 10 (P6): dove il rilascio vuole che il foglio cominci; si usa una volta, appena la copia e' fatta ([applyHint]). */
  private var hint: DropHint? = hint

  /** L'album del rilascio, se l'elenco degli album non lo conosceva ancora: lo si sceglie appena compare (AA-1). */
  private var albumHint: String? = null

  /** Una condivisione nuova arrivata mentre c'era gia' lavoro in corso (CA-2): parte appena la copia o il salvataggio di prima finiscono. */
  private class Request(val streams: suspend () -> List<IncomingStream>, val overflow: Int)

  private var pending: Request? = null

  @Volatile private var staging = false

  @Volatile private var saving = false

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
    apply(gate.decide())
  }

  private fun apply(decision: EntryDecision) {
    when (decision) {
      EntryDecision.ALLOWED -> {
        reveal()
        begin()
        tryRunPending()
      }
      EntryDecision.NEEDS_UNLOCK -> cover(ReceiveScreenState.LOCKED, ReceiveCounter.LOCKED)
      EntryDecision.NOT_READY -> cover(ReceiveScreenState.NOT_READY, ReceiveCounter.NOT_READY)
      EntryDecision.LOADING -> awaitLoaded()
    }
  }

  /**
   * IN-3: a processo freddo l'app sta ancora leggendo il suo stato (configurazione, impostazioni del blocco): non si decide e non si mostra niente
   * (la schermata resta vuota), si aspetta che l'abbia letto e allora si decide. Prima si rispondeva "Prima configura MyCloud Gallery" o si apriva
   * lo sblocco, e nessuno ricontrollava. Se l'attesa finisce senza risposta non si offre niente (fallire chiusi); il prossimo ritorno in primo
   * piano riprova.
   */
  private fun awaitLoaded() {
    if (loadingJob?.isActive == true) return
    loadingJob = scope.launch {
      val decided = gate.decideAfterLoading()
      if (_outcome.value == null) apply(if (decided == EntryDecision.LOADING) EntryDecision.NOT_READY else decided)
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
    _ui.update { it.copy(unlocking = true, unlockFailed = false, unlockFailure = null) }
    return true
  }

  fun endUnlock(unlocked: Boolean, failure: UnlockFailure? = null) {
    _ui.update { it.copy(unlocking = false, unlockFailed = !unlocked, unlockFailure = if (unlocked) null else failure) }
    if (unlocked) refreshGate()
  }

  // --- La copia immediata -------------------------------------------------------------------------------------------------

  private fun begin() {
    if (started) return
    started = true
    mutate { it.copy(screen = ReceiveScreenState.STAGING, unlockFailed = false) }
    startStaging(streams, overflow, previous = null, previousSaved = false)
  }

  /**
   * La copia di una condivisione ([source], [extra] indirizzi oltre il tetto). [previous] e' la condivisione che questa sostituisce (CA-2): si toglie **dopo**,
   * quando la nuova e' al sicuro (copiata, o rifiutata con il suo motivo), e solo se non era gia' in coda ([previousSaved]: quella e' del diario).
   */
  private fun startStaging(source: suspend () -> List<IncomingStream>, extra: Int, previous: IncomingStage?, previousSaved: Boolean) {
    staging = true
    stagingJob = scope.launch {
      try {
        stageRequest(source, extra)
      } finally {
        if (previous != null && !previousSaved) withContext(NonCancellable) { uploads.discard(previous.id) }
        staging = false
        tryRunPending()
      }
    }
  }

  private suspend fun stageRequest(source: suspend () -> List<IncomingStream>, extra: Int) {
    // IN-6: il backup in prova non scrive sul NAS: si dice subito, senza copiare niente (prima: fino a 4 GiB copiati, poi un messaggio sbagliato).
    if (blockedByTrial()) {
      mutate { it.copy(screen = ReceiveScreenState.TEST_ONLY) }
      return
    }
    val stage = try {
      uploads.stage(source())
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    if (stage == null) {
      mutate { it.copy(screen = ReceiveScreenState.NOTHING, rejected = mapOf(IncomingReject.UNREADABLE to 1)) }
      return
    }
    // Gli indirizzi oltre il tetto non si sono nemmeno guardati: si contano fra i "troppi".
    val rejected = if (extra > 0) stage.rejected + (IncomingReject.TOO_MANY to ((stage.rejected[IncomingReject.TOO_MANY] ?: 0) + extra)) else stage.rejected
    val photos = stage.accepted.count { it.mime.startsWith("image/") }
    val roots = if (stage.accepted.isEmpty()) emptyList() else destinations.roots()
    if (stage.accepted.isEmpty() || roots.isEmpty()) {
      // Niente di buono, o nessuna cartella dove salvare: le copie, se ci sono, si tolgono subito.
      val noFolder = stage.accepted.isNotEmpty()
      if (noFolder) uploads.discard(stage.id)
      mutate { it.copy(screen = ReceiveScreenState.NOTHING, rejected = rejected, stage = null, noFolder = noFolder) }
      return
    }
    // D35: gli album dove si puo' aggiungere. Una porta che non risponde vale come nessun album (D13: niente voce morta); il salvataggio non ne dipende.
    val offered = try {
      albums.albums()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptyList()
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
        albums = offered,
        albumPick = false,
        selectedAlbum = null,
        addedToAlbum = null,
        albumNotRegistered = false,
        canCreateAlbum = albums.canCreate,
        creatingAlbum = false,
        newAlbumProblem = null,
      )
    }
    applyHint()
    followAlbums()
  }

  /** Un altro rilascio mentre il foglio e' aperto (`onNewIntent`): la sua destinazione vale per la copia che parte adesso ([replaceRequest]). */
  fun hintForNext(value: DropHint?) {
    synchronized(this) { hint = value }
  }

  /**
   * Fase 10 (P6, D21, E-T16): la destinazione del rilascio come **scelta iniziale** del foglio, che l'utente cambia come vuole; mai un salvataggio.
   *  - un album: se e' fra quelli dove si puo' aggiungere, "Aggiungi ad album" si apre con lui scelto (altrimenti il foglio di sempre: un album dove "guardano" soltanto non si offre);
   *  - una cartella: se sta nell'albero delle cartelle dove "Salva sul NAS" scrive, la scelta parte da li'; fuori da quell'albero il foglio di sempre.
   */
  private suspend fun applyHint() {
    val given = synchronized(this) { hint.also { hint = null } } ?: return
    when (given) {
      DropHint.Library -> Unit
      is DropHint.Album -> {
        val known = core().albums.firstOrNull { it.id == given.albumId }
        if (known == null) {
          albumHint = given.albumId
        } else {
          mutate { if (it.screen == ReceiveScreenState.READY) it.copy(albumPick = true, selectedAlbum = known) else it }
        }
      }
      is DropHint.Folder -> {
        val place = try {
          resolveFolder(given)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        } ?: return
        mutate { if (it.screen == ReceiveScreenState.READY) it.copy(trail = place.trail, choices = place.choices, selected = place.selected, noSubfolders = false) else it }
      }
    }
  }

  private class FolderPlace(val trail: List<DestinationChoice>, val choices: List<DestinationChoice>, val selected: DestinationChoice)

  /** La cartella [hint] dentro l'albero delle destinazioni: scende dalle cartelle di partenza di una cartella alla volta. Null se non ci sta. */
  private suspend fun resolveFolder(hint: DropHint.Folder): FolderPlace? {
    var choices = destinations.roots()
    var trail = emptyList<DestinationChoice>()
    repeat(MAX_HINT_DEPTH) {
      choices.firstOrNull { it.destination.share == hint.share && it.destination.folder.value == hint.path }?.let { return FolderPlace(trail, choices, it) }
      val next = choices.firstOrNull {
        it.destination.share == hint.share && hint.path.startsWith(it.destination.folder.value + "/")
      } ?: return null
      choices = destinations.children(next).ifEmpty { return null }
      trail = trail + next
    }
    return null
  }

  /**
   * AA-1: gli album **dopo** la prima lettura. Con l'app chiusa gli album si montano alcuni secondi dopo l'arrivo dei file; la lettura di sopra puo' essere
   * vuota e senza questo "Aggiungi ad album" non compariva piu'. L'elenco segue la porta finche' la schermata vive; una scelta che non c'e' piu' si lascia.
   */
  private fun followAlbums() {
    albumsJob?.cancel()
    albumsJob = scope.launch {
      try {
        albums.choices().collect { offered ->
          mutate { ui ->
            if (ui.albums == offered) {
              ui
            } else {
              val keep = ui.selectedAlbum?.takeIf { it in offered }
              val wanted = albumHint?.let { id -> offered.firstOrNull { it.id == id } }
              if (wanted != null && ui.screen == ReceiveScreenState.READY) {
                albumHint = null
                ui.copy(albums = offered, albumPick = true, selectedAlbum = wanted)
              } else {
                ui.copy(albums = offered, selectedAlbum = keep ?: if (ui.albumPick) offered.firstOrNull() else null)
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Una porta che non risponde vale come nessun album in piu': il salvataggio non ne dipende (D13).
      }
    }
  }

  // --- Una condivisione nuova a schermata aperta (CA-2) -----------------------------------------------------------------------------

  /**
   * Una condivisione nuova (`onNewIntent`, `singleTop`) prende il posto di quella di adesso **in questa schermata**. Cio' che fa dipende da dove si e':
   *  - il cancello non ha ancora aperto (controllo, sblocco, app non pronta): i flussi vecchi non sono mai stati letti, si cambiano e basta;
   *  - il cancello si e' richiuso dopo la partenza (E-X7): niente si copia finche' non si riapre; poi la nuova parte;
   *  - una copia o un salvataggio sono in corso: finiscono prima (un salvataggio non si interrompe a meta'), poi parte la nuova;
   *  - altrimenti (scelta della cartella, errore, "In coda"): parte subito. La vecchia, se non era in coda, si toglie quando la nuova e' al sicuro.
   * Due condivisioni nuove di fila: vale l'ultima.
   */
  fun replaceRequest(source: suspend () -> List<IncomingStream>, extra: Int) {
    if (_outcome.value != null) return
    synchronized(this) { pending = Request(source, extra) }
    tryRunPending()
  }

  private fun tryRunPending() {
    val request = synchronized(this) {
      val next = pending ?: return
      if (_outcome.value != null) {
        pending = null
        return
      }
      if (!started) {
        // Mai letta niente: la condivisione nuova prende semplicemente il posto della vecchia.
        streams = next.streams
        overflow = next.overflow
        pending = null
        return
      }
      if (hiddenBy != null || staging || saving) return
      pending = null
      next
    }
    startReplacement(request)
  }

  private fun startReplacement(request: Request) {
    val previous = core().stage
    val previousSaved = saved
    saved = false
    savingJob = null
    streams = request.streams
    overflow = request.overflow
    // La schermata riparte dalla copia della nuova; lo stato della vecchia non si vede piu'.
    mutate { ReceiveUi(screen = ReceiveScreenState.STAGING) }
    FlowCounters.receive.add(ReceiveCounter.OPENED)
    startStaging(request.streams, request.overflow, previous, previousSaved)
  }

  // --- La cartella ---------------------------------------------------------------------------------------------------------

  /** Sceglie [choice] come cartella di destinazione. */
  fun select(choice: DestinationChoice) {
    if (_ui.value.screen != ReceiveScreenState.READY) return
    mutate { it.copy(selected = choice, noSubfolders = false) }
  }

  /** Apre [choice] per vederne le sottocartelle; la scelta passa a lei. */
  fun open(choice: DestinationChoice) {
    if (_ui.value.screen != ReceiveScreenState.READY) return
    scope.launch {
      val children = destinations.children(choice)
      mutate { current ->
        when {
          current.screen != ReceiveScreenState.READY -> current
          // IN-11: nessuna sottocartella nota alla Libreria: non si apre una lista vuota, lo si dice sotto il tasto.
          children.isEmpty() -> current.copy(selected = choice, noSubfolders = true)
          else -> current.copy(trail = current.trail + choice, choices = children, selected = choice, noSubfolders = false)
        }
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
        if (current.screen != ReceiveScreenState.READY) current else current.copy(trail = trail, choices = choices, selected = trail.lastOrNull() ?: choices.firstOrNull(), noSubfolders = false)
      }
    }
  }

  // --- "Aggiungi ad album" (Fase 7, P10, D35) ------------------------------------------------------------------------------------

  /** Apre la scelta dell'album al posto della scelta della cartella (c'e' solo se `:app` ha degli album). */
  fun openAlbumPicker() {
    if (_ui.value.screen != ReceiveScreenState.READY || (_ui.value.albums.isEmpty() && !_ui.value.canCreateAlbum)) return
    mutate { it.copy(albumPick = true, newAlbumProblem = null, selectedAlbum = it.selectedAlbum ?: it.albums.firstOrNull()) }
  }

  fun closeAlbumPicker() {
    if (_ui.value.screen != ReceiveScreenState.READY) return
    mutate { it.copy(albumPick = false) }
  }

  /**
   * AA-2: «Nuovo album» dalla scelta dell'album. Crea un album a mano (nessun file si sposta, nessuna conferma da chiedere) e lo sceglie: il salvataggio
   * resta quello di sempre, "Salva sul NAS" nella cartella scelta con la sua conferma, piu' la promessa dell'album. Il nome e' un dato dell'utente: mai nei log.
   */
  fun createAlbum(title: String) {
    val current = _ui.value
    if (current.screen != ReceiveScreenState.READY || !current.albumPick || !current.canCreateAlbum || current.creatingAlbum) return
    mutate { it.copy(creatingAlbum = true, newAlbumProblem = null) }
    scope.launch {
      val outcome = try {
        albums.create(title)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        IncomingAlbumCreate.Unavailable
      }
      mutate { ui ->
        when (outcome) {
          is IncomingAlbumCreate.Created ->
            ui.copy(creatingAlbum = false, albums = ui.albums.filterNot { it.id == outcome.choice.id } + outcome.choice, selectedAlbum = outcome.choice, newAlbumProblem = null)
          IncomingAlbumCreate.InvalidTitle -> ui.copy(creatingAlbum = false, newAlbumProblem = NewAlbumProblem.INVALID_TITLE)
          IncomingAlbumCreate.Unavailable -> ui.copy(creatingAlbum = false, newAlbumProblem = NewAlbumProblem.UNAVAILABLE)
        }
      }
    }
  }

  fun selectAlbum(choice: IncomingAlbumChoice) {
    if (_ui.value.screen != ReceiveScreenState.READY || !_ui.value.albumPick) return
    mutate { it.copy(selectedAlbum = choice) }
  }

  /**
   * Il tocco su "Salva e aggiungi all'album": e' **"Salva sul NAS"** nella cartella scelta, con la sua conferma che nasce dal tocco (E-S11), piu' la promessa
   * che i file andranno nell'album. La promessa si registra solo se il salvataggio e' andato in coda; senza album scelto non si salva.
   */
  fun saveToAlbum() {
    val album = _ui.value.selectedAlbum ?: return
    if (!_ui.value.albumPick) return
    save(album)
  }

  // --- "Salva sul NAS" (E-S11) --------------------------------------------------------------------------------------------

  /** Il tocco dell'utente su "Salva sul NAS": la conferma nasce **qui**, dal piano di adesso, e solo da questo gesto. */
  fun save() = save(null)

  private fun save(album: IncomingAlbumChoice?) {
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
    saving = true
    savingJob = scope.launch {
      try {
        runSave(stage, destination, album)
      } finally {
        saving = false
        tryRunPending()
      }
    }
  }

  private suspend fun runSave(stage: IncomingStage, destination: NasDestination, album: IncomingAlbumChoice?) {
    run {
      val plan = IncomingSavePlan(stage, destination)
      val confirmation = PhoneConfirmationSheet(plan).tap(monotonicMs())
      val outcome = if (confirmation == null) {
        IncomingSaveOutcome.Unavailable
      } else {
        try {
          uploads.save(stage, destination, confirmation)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // IN-12: non solo `IOException`. Un errore del database (disco pieno: `SQLiteException`) chiudeva l'app; qui e' "non disponibile".
          IncomingSaveOutcome.Unavailable
        }
      }
      when (outcome) {
        is IncomingSaveOutcome.Queued -> {
          saved = true
          // D35: la promessa dell'album si registra solo ora che i file sono in coda; se non si registra i file restano in coda e la schermata lo dice.
          val registered = album != null && registerAlbum(stage, album)
          // IN-11: "In coda" subito; la prova del NAS (fino a 5 s col NAS spento) non fa piu' aspettare la schermata, corregge la frase dopo.
          mutate {
            it.copy(
              screen = ReceiveScreenState.DONE, queued = outcome.items, notQueued = (stage.accepted.size - outcome.items).coerceAtLeast(0),
              addedToAlbum = album.takeIf { registered }, albumNotRegistered = album != null && !registered, albumPick = false,
            )
          }
          checkNasWait()
        }
        is IncomingSaveOutcome.Rejected -> fail(ReceiveFailure.CONFIRMATION)
        IncomingSaveOutcome.DestinationNotAllowed -> fail(if (blockedByTrial()) ReceiveFailure.TEST_ONLY else ReceiveFailure.DESTINATION)
        IncomingSaveOutcome.StageGone -> fail(ReceiveFailure.GONE)
        IncomingSaveOutcome.Unavailable -> fail(ReceiveFailure.UNAVAILABLE)
      }
    }
  }

  /** La promessa "questi file andranno in [album]": falso se la porta non risponde o rifiuta (i file sono comunque in coda). */
  private suspend fun registerAlbum(stage: IncomingStage, album: IncomingAlbumChoice): Boolean = try {
    albums.willAdd(stage.id, album)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
  }

  /** Il backup in prova (D12, R2) non scrive da nessuna parte; un errore della porta non lo dice (si prova a salvare e basta). */
  private suspend fun blockedByTrial(): Boolean = try {
    destinations.savingBlockedByTrial()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
  }

  /** IN-11: dopo "In coda", perche' non parte subito se il NAS non risponde; la schermata e' gia' "In coda". */
  private fun checkNasWait() {
    scope.launch {
      val wait = try {
        destinations.nasWait()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        NasWait.OFFLINE
      }
      mutate { current -> if (current.screen == ReceiveScreenState.DONE) current.copy(nasWait = wait) else current }
    }
  }

  private fun fail(reason: ReceiveFailure) {
    mutate { it.copy(screen = ReceiveScreenState.FAILED, failure = reason) }
  }

  /**
   * Dopo un errore si torna alla scelta della cartella (le copie ci sono ancora), se ci sono. Con i file spariti (o il backup in prova, o senza
   * niente da riprovare) non c'e' da riprovare: la schermata finisce (e il tasto dice "Chiudi", IN-11).
   */
  fun retry() {
    val current = _ui.value
    if (current.screen != ReceiveScreenState.FAILED) return
    if (current.failure == ReceiveFailure.GONE || current.failure == ReceiveFailure.TEST_ONLY || current.stage == null) {
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
    loadingJob?.cancel()
    albumsJob?.cancel()
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

    /** Quante cartelle al piu' si scendono per trovare quella del rilascio. */
    const val MAX_HINT_DEPTH = 16
  }
}
