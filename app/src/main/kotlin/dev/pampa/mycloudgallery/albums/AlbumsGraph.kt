package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.personal.PersonalAlbumCopies
import dev.pampa.mycloudgallery.core.albums.upkeep.ChangeBatcher
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * GLI ALBUM NELL'APP (Fase 7, P13; docs/fase7-progetto.md §1.4). Nasce con il processo e monta [AlbumsLive] quando la famiglia e' pronta: catalogo
 * della galleria e dati personali (che nascono con il motore del NAS, anche senza il NAS raggiungibile). Da li' tiene le cose al passo:
 *
 * - **catalogo cambiato** (scansioni, unioni, lettura del telefono, nascosti): proiezione degli album personali (`onCatalogChanged`), attestazione
 *   degli album condivisi (`recompute`) e ricalcolo degli album smart una volta per gruppo di novita' (accorpate, D16);
 * - **dati personali cambiati** (regole, esclusioni, congelamento, anche da un altro dispositivo): ricalcolo intero degli smart toccati; un nascosto
 *   arrivato da un altro dispositivo ricalcola gli elenchi degli album condivisi (copertina e conteggio, P9);
 * - **modifiche ai miei album condivisi**: la pubblicazione accorpata (30 s, poi al piu' una testa ogni 5 minuti, D6).
 *
 * I giri lunghi li fanno i lavori (`AlbumWork`), con le regole delle attivita': [syncRound] nel giro dell'indice, [shareRound] per la condivisione,
 * [nightly] per viaggi e ricalcolo di sicurezza. [stop] e' il primo passo dell'azzeramento (§1.4): niente parte piu' e i giri in corso si fermano.
 * Mai titoli, cartelle, luoghi o id nei log (D37): solo numeri.
 */
class AlbumsGraph internal constructor(
  private val parent: CoroutineScope,
  private val build: suspend (CoroutineScope) -> AlbumsLive?,
  private val ready: StateFlow<Boolean>,
  /** Dopo l'avvio di una condivisione o con operazioni aperte: il lavoro della condivisione (WorkManager in produzione). */
  private val shareWork: () -> Unit = {},
) {
  private val job = SupervisorJob(parent.coroutineContext[Job])
  private val scope = CoroutineScope(parent.coroutineContext + job)

  /** Gli album montati, uno solo per processo; lo stop aspetta un montaggio in corso (I11). */
  private val mount = Mount(scope, build) { live -> watch(live) }

  /** Gli album di adesso: null finche' la famiglia non e' pronta, e dopo [stop]. */
  val live: StateFlow<AlbumsLive?> get() = mount.flow

  /**
   * AP-1: gli album sono montati, o non lo saranno a breve (dopo [LOADING_GRACE] senza famiglia pronta: nessun NAS, nessuna famiglia). Prima di allora un
   * elenco vuoto vuol dire "non ho ancora letto", non "nessun album": le schermate dicono "sto leggendo".
   */
  val settled: StateFlow<Boolean> get() = gaveUp.asStateFlow()
  private val gaveUp = MutableStateFlow(false)

  /** L'ultimo giro della replica (Diagnostica: solo numeri). */
  val lastSync = MutableStateFlow<AlbumSyncReport?>(null)

  /** R2: i byte letti dal NAS dall'ultimo [syncRound] (0 se il giro non c'e' stato): il conto del traffico del lavoro dell'indice. */
  @Volatile var lastRoundBytes: Long = 0
    private set

  /** L'ultima rilevazione dei viaggi (Diagnostica). */
  val lastTrips = MutableStateFlow<TripReport?>(null)

  /**
   * P13 (P10-10): "Aggiungi ad album" dalla condivisione in arrivo. [incoming] e' la porta che la ricezione riceve (`ReceivePorts`); [afterCatalog] mantiene
   * le sue promesse dopo un cambio del catalogo, all'avvio e a ogni giro (i file ricevuti sono diventati media). Li lega `AlbumsModule`.
   */
  @Volatile var incoming: dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbums? = null

  @Volatile var afterCatalog: suspend () -> Unit = {}

  /** E-SH16 (P13): le cartelle nuove degli album di cui sono il creatore o l'erede, dall'ultima scansione del catalogo. */
  val folderMoves = MutableStateFlow<Map<AlbumId, FolderMove>>(emptyMap())

  /** E-SH20 (P13): quanti elementi di un mio dispositivo revocato si possono ripristinare, per album. */
  val revokedCounts = MutableStateFlow<Map<AlbumId, Int>>(emptyMap())

  /** Revisione B, SH-3 (D39): quanti file aggiunti dal PC nella cartella di ogni album in cui posso adottarli (`FolderAdoptions.pending`). */
  val pcFiles = MutableStateFlow<Map<AlbumId, Int>>(emptyMap())

  /** Gli avvisi chiusi dall'utente ("Non mostrare piu'"), riletti a ogni scansione e dopo una chiusura. */
  val dismissedNotices = MutableStateFlow<Set<String>>(emptySet())

  /** Errori isolati dei giri degli album (non fermano niente, si contano). */
  @Volatile var roundErrors: Int = 0
    private set

  /** Parte (idempotente): appena la famiglia e' pronta monta gli album. */
  fun start() {
    scope.launch { ready.collect { if (it) ensureLive() } }
    scope.launch {
      delay(LOADING_GRACE)
      gaveUp.value = true
    }
  }

  /** Gli album, montati adesso se si puo' (i lavori li chiedono prima di un giro). */
  suspend fun ensureLive(): AlbumsLive? = mount.ensure()

  /** Un errore di un giro (anche dei motori degli smart e dei viaggi): si conta, mai il messaggio. */
  internal fun noteError(e: Exception) {
    roundErrors++
    GalleryLog.w(TAG) { "giro degli album: ${e.javaClass.simpleName}" }
  }

  /** Il resoconto di una ricerca dei viaggi (Diagnostica: solo numeri). */
  internal fun noteTrips(report: TripReport) {
    lastTrips.value = report
  }

  // --- Al passo ------------------------------------------------------------------------------------------------------------------------

  private fun watch(live: AlbumsLive) {
    live.scope.launch {
      guarded { live.albums.reconcileAll() }
      guarded { live.shared.replica.recompute() }
      guarded { resume(live, START_BUDGET) }
      guarded { afterCatalog() }
      guarded { scanNotices(live) }
      // Revisione B, SC-2: le modifiche dei miei album condivisi rimaste in coda da un processo di prima (una rinomina, una chiusura, una voce) si
      // pubblicano adesso, non al giro dell'indice dopo: il contatore del pubblicatore riparte da zero e prima nessuno le guardava.
      if (guarded { hasStagedChanges(live) } == true) {
        flushAll(live, FlushReason.RESUME)
        if (guarded { hasStagedChanges(live) } == true) {
          flushUntilEmpty(live)
          shareWork()
        }
      }
      if (guarded { live.shared.diary.openOps().isNotEmpty() } == true) shareWork()
      // AS-5: gli smart arrivati prima del montaggio (da un altro dispositivo, o le cui novita' sono passate mentre gli album non c'erano) si valutano
      // adesso, non alla notte; e cio' che resta a meta' si riprende.
      guarded { live.smartUpkeep.resumeAll() }
      guarded { live.smartUpkeep.drain() }
    }
    live.scope.launch { watchCatalog(live) }
    live.scope.launch { watchPersonal(live) }
    live.scope.launch { watchPending(live) }
    live.scope.launch { watchSearch(live) }
    // VG-1: i viaggi si cercano anche dopo la prima scansione, non solo di notte (quando il catalogo e' fermo).
    live.tripUpkeep.poke()
  }

  /** Le novita' del catalogo, accorpate: un giro per gruppo, non per file. Cio' che arriva durante un giro ne fa partire un altro (AS-3). */
  private suspend fun watchCatalog(live: AlbumsLive) {
    val batches = ChangeBatcher<MediaId>(live.scope, CATALOG_QUIET) { ids -> onCatalogChanged(live, ids) }
    live.catalog.changes.collect { change -> batches.add(change.ids) }
  }

  /** Dopo una scansione con novita', un'unione d'identita', un nascosto: proiezione, attestazione, smart (D5, D9, D16). */
  internal suspend fun onCatalogChanged(live: AlbumsLive, ids: Collection<MediaId>) {
    guarded { live.albums.onCatalogChanged(ids) }
    guarded { live.shared.replica.recompute() }
    // Un media che cambia giorno (data corretta) puo' uscire da uno smart che lo aveva: quegli album si rivalutano interi (il giorno di prima e' il loro).
    // Poi l'incrementale; alla fine elenco e pagine si rileggono (AS-1: il motore scrive solo su Room e nessuno lo diceva a chi mostra).
    guarded { live.smartUpkeep.catalogChanged(ids) }
    guarded { afterCatalog() }
    guarded { scanNotices(live) }
    live.tripUpkeep.poke()
    // AS-5: i giorni che il budget ha lasciato e i calcoli falliti si riprendono, a distanza, senza tenere fermo questo giro.
    if (live.smartUpkeep.hasUnfinished) live.scope.launch { guarded { live.smartUpkeep.drain() } }
  }

  /** E-SH16, E-SH20: le cartelle nuove e gli elementi da ripristinare, per gli avvisi delle pagine degli album (solo lettura del catalogo). */
  internal suspend fun scanNotices(live: AlbumsLive) {
    val share = live.shared.ports.family.commonShare()
    folderMoves.value = if (share == null) {
      emptyMap()
    } else {
      AlbumFolderWatch.scan(live.shared.replica.views.value.values, live.shared.ports.family.me()) { media -> live.shared.folders.foldersOf(share, media) }
    }
    revokedCounts.value = live.shared.revoked.candidates()
    // Revisione B, SH-3 (D39): "N file nuovi nella cartella: aggiungili" sugli album che vedo (prima non lo calcolava nessuno).
    pcFiles.value = guarded { pcFilesOf(live) } ?: pcFiles.value
    dismissedNotices.value = live.shared.choices.dismissed()
  }

  /** I file aggiunti dal PC nella cartella di ogni album attivo che vedo e in cui posso adottarli (solo lettura del catalogo e della replica). */
  private suspend fun pcFilesOf(live: AlbumsLive): Map<AlbumId, Int> {
    val out = LinkedHashMap<AlbumId, Int>()
    for (album in live.shared.albums.visible.value) {
      val n = live.shared.adoptions.pending(album.id).size
      if (n > 0) out[album.id] = n
    }
    return out
  }

  /** Regole, esclusioni, accettazioni, congelamento (anche da un altro dispositivo) e nascosti arrivati con i dati personali. */
  private suspend fun watchPersonal(live: AlbumsLive) {
    var smartBefore: Map<String, Int>? = null
    var hiddenBefore: Int? = null
    live.personal.effectiveEntries.collect { entries ->
      val smartNow = smartSignatures(entries)
      val hiddenNow = hiddenSignature(entries)
      val changed = smartBefore?.let { before -> smartNow.keys.filter { smartNow[it] != before[it] } }.orEmpty()
      val hiddenChanged = hiddenBefore != null && hiddenBefore != hiddenNow
      smartBefore = smartNow
      hiddenBefore = hiddenNow
      if (changed.isNotEmpty()) {
        live.scope.launch {
          // AS-9: la proiezione dei dati personali va prima del motore (legge `album_rule`). Non si indovina con un'attesa quanto ci mette (con molti
          // album il motore leggeva `album_rule` vuota): si chiede alla proiezione di finire, se la sta facendo un altro collettore si aspetta.
          guarded { live.albums.reconcileNow() }
          for (raw in changed) runCatching { AlbumId(raw) }.getOrNull()?.let { guarded { live.smartUpkeep.rulesChanged(it) } }
          if (live.smartUpkeep.hasUnfinished) guarded { live.smartUpkeep.drain() }
        }
      }
      if (hiddenChanged) live.scope.launch { guarded { live.shared.albums.refresh() } }
    }
  }

  /**
   * AS-4: la ricerca per contenuto diventa pronta (il modello si carica dopo l'avvio). Le regole "descrizione" ed "esempi" degli smart, che fin li'
   * tacevano (D15), agiscono da adesso: gli smart che le hanno si rivalutano, senza aspettare un evento sul loro periodo o la notte. Il primo valore
   * (gia' pronta al montaggio) non rifa' niente: ci pensa [AlbumsGraph.watch] con `resumeAll`.
   */
  private suspend fun watchSearch(live: AlbumsLive) {
    var before: Boolean? = null
    live.content.readyChanges.distinctUntilChanged().collect { ready ->
      val wasReady = before
      before = ready
      if (ready && wasReady == false) {
        guarded { live.smartUpkeep.searchReady() }
        if (live.smartUpkeep.hasUnfinished) guarded { live.smartUpkeep.drain() }
      }
    }
  }

  /** Un nascosto fatto qui (`GalleryGraph.setHidden`): copertine e conteggi degli album condivisi senza aspettare il giro dopo (P9). */
  fun hiddenChanged() {
    val live = mount.current ?: return
    live.scope.launch { guarded { live.shared.albums.refresh() } }
  }

  /** Le modifiche dei miei album condivisi in coda: pubblicate dopo 30 s di calma (D6), poi riprovate finche' non escono o si fermano. */
  private suspend fun watchPending(live: AlbumsLive) {
    var flusher: Job? = null
    live.shared.publisher.pending.collect { pending ->
      if (pending > 0 && flusher?.isActive != true) {
        flusher = live.scope.launch {
          delay(PublishRules.DEBOUNCE_MS.milliseconds + 1.seconds)
          var attempts = 0
          while (live.shared.publisher.pending.value > 0 && attempts < MAX_FLUSH_ATTEMPTS) {
            attempts++
            val retry = flushAll(live, FlushReason.DEBOUNCE) ?: break
            delay(retry.coerceAtLeast(MIN_RETRY_MS).milliseconds)
          }
        }
      }
    }
  }

  /**
   * Revisione B, SC-2: le modifiche rimaste in coda da un processo di prima, riprovate finche' escono ("troppo presto": 5 minuti fra due teste). Il
   * contatore del pubblicatore qui non serve (riparte da zero): si guarda il deposito. Se il processo muore ancora, ci pensa il lavoro della
   * condivisione ([shareRound] resta aperto finche' ci sono modifiche in coda).
   */
  private fun flushUntilEmpty(live: AlbumsLive) {
    live.scope.launch {
      var attempts = 0
      while (guarded { hasStagedChanges(live) } == true && attempts < MAX_FLUSH_ATTEMPTS) {
        attempts++
        val retry = flushAll(live, FlushReason.SYNC_ROUND) ?: break
        delay(retry.coerceAtLeast(MIN_RETRY_MS).milliseconds)
      }
    }
  }

  /** Revisione B, SC-2: c'e' almeno una modifica dei miei album condivisi in coda nel deposito del pubblicatore (anche di un processo di prima). */
  private suspend fun hasStagedChanges(live: AlbumsLive): Boolean {
    val store = live.shared.publishStore
    return store.albums().any { store.load(it)?.staged?.isNotEmpty() == true }
  }

  /**
   * Prova a pubblicare gli album con modifiche in coda. Restituisce fra quanto riprovare (ms), o null se non serve (tutto pubblicato) o non ha
   * senso adesso (bloccato dal cancello, NAS che non risponde: ci pensa il giro dopo).
   */
  private suspend fun flushAll(live: AlbumsLive, reason: FlushReason): Long? {
    var retry: Long? = null
    for (album in live.shared.publishStore.albums()) {
      val outcome = guarded { live.shared.publisher.flush(album, reason) } ?: continue
      when (outcome) {
        is PublishOutcome.TooSoon -> retry = minOf(retry ?: Long.MAX_VALUE, outcome.retryAfterMs)
        is PublishOutcome.Published -> guarded { live.shared.replica.recompute(listOf(album)) }
        PublishOutcome.NothingToPublish, is PublishOutcome.Blocked, PublishOutcome.Failed -> Unit
      }
    }
    return retry
  }

  // --- I giri dei lavori -----------------------------------------------------------------------------------------------------------------

  /**
   * Il giro degli album condivisi nel giro dell'indice (§1.4, P2): replica (al piu' [budget]), pubblicazione del mio contributo, copie personali
   * degli album chiusi con "ognuno tiene una copia" (Q5), e un passo della condivisione. Vero se il giro e' finito.
   */
  suspend fun syncRound(budget: Duration): Boolean {
    lastRoundBytes = 0
    val live = ensureLive() ?: return true
    val report = guarded { live.shared.sync.sync(budget) }
    if (report != null) lastSync.value = report
    lastRoundBytes = report?.bytesRead ?: 0
    flushAll(live, FlushReason.SYNC_ROUND)
    guarded { keepClosedCopies(live) }
    guarded { resume(live, SHARE_SLICE) }
    // R9: i registri dei trasferimenti tengono solo le chiavi delle operazioni ancora aperte (non crescono verso il tetto del file).
    guarded { live.shared.pruneTransfers() }
    guarded { afterCatalog() }
    guarded { scanNotices(live) }
    // Un'operazione rimasta aperta (processo morto, NAS spento, regole): il suo lavoro (se ce n'e' gia' uno, resta quello).
    if (guarded { live.shared.diary.openOps().isNotEmpty() } == true) shareWork()
    return roundDone(report)
  }

  /**
   * R6 (revisione di sicurezza della Fase 7, A7): le teste d'album di [device] da congelare quando l'admin lo dismette, cioe' quelle che la
   * replica di questo dispositivo ha accettato, dopo un giro breve della replica (per non congelare una testa vecchia). Senza album montati
   * (famiglia non pronta, recinzione D30) nessuna. La lega `AlbumsWiring` a `RegistryWriter.retireHeads`.
   */
  suspend fun frozenAlbumHeads(device: String): List<String> {
    val live = ensureLive() ?: return emptyList()
    guarded { live.shared.sync.sync(RETIRE_SYNC) }
    return dev.pampa.mycloudgallery.core.albums.shared.RetireAlbumHeads.of(
      live.shared.replicaStore, device, dev.pampa.mycloudgallery.core.trust.TrustLimits.MAX_FROZEN_HEADS,
    )
  }

  /**
   * Le copie personali degli album chiusi (Q5 b, D31): con `PersonalAlbumCopies.keep` (impronta sugli elementi, P3); mai un file toccato. R1 della
   * revisione di sicurezza della Fase 7: la replica le propone solo per album visti attivi ed entro i tetti; ognuna si scrive **una volta** (poi
   * `copyKept`: gli elementi restano quelli della prima scrittura), e quelle nate da una chiusura firmata da un dispositivo poi revocato o perso
   * si ritirano.
   */
  internal suspend fun keepClosedCopies(live: AlbumsLive): Int {
    var written = 0
    for (copy in live.shared.albums.keptCopies()) {
      // Il creatore dopo "Smetti" che aveva condiviso un suo album personale ritrova quello (voce `shared`): nessuna copia (e nessun elemento contato).
      if (copy.mine && live.keptStore.personalSharedAs(copy.shared) != null) {
        live.shared.albums.copyKept(copy.copy(items = emptyList()))
        continue
      }
      when (live.copies.keep(copy.copy, copy.shared, copy.title, copy.items)) {
        PersonalAlbumCopies.Result.CREATED, PersonalAlbumCopies.Result.UPDATED -> written++
        PersonalAlbumCopies.Result.UNCHANGED, PersonalAlbumCopies.Result.DELETED_BY_USER -> Unit
      }
      live.shared.albums.copyKept(copy)
    }
    for (copy in live.shared.albums.copiesToWithdraw()) {
      live.copies.withdraw(copy.copy, copy.shared)
      live.shared.albums.copyWithdrawn(copy.shared)
    }
    return written
  }

  /**
   * Un giro della condivisione (lavoro dedicato): avanza le operazioni dal diario e pubblica le voci pronte. Vero se resta qualcosa di aperto:
   * un'operazione o, revisione B (SC-2), una modifica dei miei album condivisi ancora in coda (il lavoro si riaccoda e la pubblica senza aspettare
   * il giro dell'indice, anche dopo la morte del processo).
   */
  suspend fun shareRound(budget: Duration): Boolean {
    val live = ensureLive() ?: return false
    guarded { resume(live, budget) }
    flushAll(live, FlushReason.DEBOUNCE)
    guarded { resume(live, SHARE_SLICE) }
    val open = guarded { live.shared.diary.openOps().isNotEmpty() } ?: false
    return open || guarded { hasStagedChanges(live) } == true
  }

  private suspend fun resume(live: AlbumsLive, budget: Duration): ShareRunResult = live.shared.orchestrator.resumeAll(budget)

  /**
   * Un album e' appena stato chiuso da me ("Smetti di condividere", "Elimina album"; correzioni F, L231): il giro della condivisione parte subito e ferma cio' che
   * non era ancora partito (l'orchestratore lo fa a ogni ripresa: se il processo muore adesso lo fa il lavoro in coda).
   */
  fun albumClosed() {
    val live = mount.current ?: return
    live.scope.launch { guarded { resume(live, START_BUDGET) } }
    shareWork()
  }

  /** Una condivisione e' appena partita (dal foglio): il primo passo subito, nel processo (la conferma vive solo in memoria, L73). */
  fun shareStarted() {
    val live = mount.current ?: return
    live.scope.launch { guarded { resume(live, START_BUDGET) } }
    shareWork()
  }

  /** La notte (in carica): rilevazione dei viaggi (D19, una volta per notte) e ricalcolo di sicurezza di tutti gli album smart. */
  suspend fun nightly(budget: Duration) {
    val live = ensureLive() ?: return
    guarded { live.trips.detect(budget) }?.let { lastTrips.value = it }
    guarded { live.smartUpkeep.nightly() }
    // Cio' che il budget ha lasciato a meta' lo riprende il processo, non il lavoro (che ha il suo tempo).
    if (live.smartUpkeep.hasUnfinished) live.scope.launch { guarded { live.smartUpkeep.drain() } }
  }

  // --- Fine --------------------------------------------------------------------------------------------------------------------------

  /** L'azzeramento (§1.4): per primo. Niente parte piu', i giri in corso si fermano e si aspettano; le tabelle le toglie la cancellazione del database. */
  suspend fun stop() {
    // I11: un montaggio in corso finisce (o si accorge dello stop e butta cio' che ha costruito) prima di annullare tutto.
    mount.stop()
    gaveUp.value = true
    job.cancelAndJoin()
  }

  private suspend fun <T> guarded(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    noteError(e)
    null
  }

  internal companion object {
    private const val TAG = "Albums"
    val CATALOG_QUIET: Duration = 5.seconds

    /** AP-1: per quanto, senza una famiglia pronta, le schermate dicono ancora "sto leggendo" prima di "nessun album". */
    val LOADING_GRACE: Duration = 15.seconds
    val SHARE_SLICE: Duration = 20.seconds
    val START_BUDGET: Duration = 10.seconds

    /** R6: il giro della replica prima di dire quali teste congelare (un'azione dell'admin: breve). */
    val RETIRE_SYNC: Duration = 20.seconds
    const val MIN_RETRY_MS: Long = 5_000L
    const val MAX_FLUSH_ATTEMPTS: Int = 20

    /** Per album, un'impronta dei campi che decidono gli elementi di uno smart (regole, esclusioni, accettazioni, congelamento, tipo). */
    fun smartSignatures(entries: Map<PersonalKey, PersonalEntry>): Map<String, Int> {
      val out = HashMap<String, Int>()
      for ((key, entry) in entries) {
        if (key.kind != PersonalKind.ALBUM) continue
        val f = key.field
        val relevant = f.startsWith("smart:") || f.startsWith("exclude:") || f.startsWith("accept:") ||
          f == PersonalFields.ALBUM_FROZEN || f == PersonalFields.ALBUM_KIND || f == PersonalFields.ALBUM_DEFINITION
        if (!relevant) continue
        // Una somma: non dipende dall'ordine in cui la mappa elenca le voci.
        out[key.subject] = (out[key.subject] ?: 0) + (f.hashCode() * 31 + entry.value.hashCode())
      }
      return out
    }

    /**
     * Revisione B, SG-2: il giro degli album e' "finito" per la tappa se la replica ha finito, oppure se non e' potuta andare avanti: niente registro,
     * niente ora del NAS fidata, l'elenco di `albums/` non letto (nessun contributo letto ne' scartato). Allora si riprova al giro dell'indice dopo,
     * invece di ripetere subito, a vuoto, fino al tetto della tappa (con un passo della condivisione e la scansione degli avvisi a ogni ripetizione).
     * Un giro fermato dal budget dopo aver letto qualcosa resta "da continuare".
     */
    fun roundDone(report: AlbumSyncReport?): Boolean {
      if (report == null || !report.incomplete) return true
      return report.contributionsRead == 0 && report.contributionsRejected == 0
    }

    /** Un'impronta dei nascosti dei dati personali (cambia se un nascosto arriva o se ne va). */
    fun hiddenSignature(entries: Map<PersonalKey, PersonalEntry>): Int {
      var hash = 0
      for ((key, entry) in entries) {
        if (key.kind != PersonalKind.MEDIA_FLAG || key.field != PersonalFields.HIDDEN) continue
        hash += key.subject.hashCode() * 31 + entry.value.hashCode()
      }
      return hash
    }
  }
}
