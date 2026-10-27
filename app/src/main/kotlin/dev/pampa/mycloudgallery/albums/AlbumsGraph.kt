package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.personal.PersonalAlbumCopies
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  private val liveFlow = MutableStateFlow<AlbumsLive?>(null)
  private val building = Mutex()
  private val stopped = AtomicBoolean(false)

  /** Gli album di adesso: null finche' la famiglia non e' pronta, e dopo [stop]. */
  val live: StateFlow<AlbumsLive?> = liveFlow.asStateFlow()

  /** L'ultimo giro della replica (Diagnostica: solo numeri). */
  val lastSync = MutableStateFlow<AlbumSyncReport?>(null)

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

  /** Gli avvisi chiusi dall'utente ("Non mostrare piu'"), riletti a ogni scansione e dopo una chiusura. */
  val dismissedNotices = MutableStateFlow<Set<String>>(emptySet())

  /** Errori isolati dei giri degli album (non fermano niente, si contano). */
  @Volatile var roundErrors: Int = 0
    private set

  /** Parte (idempotente): appena la famiglia e' pronta monta gli album. */
  fun start() {
    scope.launch { ready.collect { if (it) ensureLive() } }
  }

  /** Gli album, montati adesso se si puo' (i lavori li chiedono prima di un giro). */
  suspend fun ensureLive(): AlbumsLive? {
    liveFlow.value?.let { return it }
    if (stopped.get()) return null
    return building.withLock {
      liveFlow.value ?: run {
        if (stopped.get()) return@withLock null
        val liveScope = CoroutineScope(scope.coroutineContext + SupervisorJob(job))
        val built = try {
          build(liveScope)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "album non montati: ${e.javaClass.simpleName}" }
          null
        } ?: return@withLock null
        liveFlow.value = built
        watch(built)
        built
      }
    }
  }

  // --- Al passo ------------------------------------------------------------------------------------------------------------------------

  private fun watch(live: AlbumsLive) {
    live.scope.launch {
      guarded { live.albums.reconcileAll() }
      guarded { live.shared.replica.recompute() }
      guarded { resume(live, START_BUDGET) }
      guarded { afterCatalog() }
      guarded { scanNotices(live) }
      if (guarded { live.shared.diary.openOps().isNotEmpty() } == true) shareWork()
    }
    live.scope.launch { watchCatalog(live) }
    live.scope.launch { watchPersonal(live) }
    live.scope.launch { watchPending(live) }
  }

  /** Le novita' del catalogo, accorpate: un giro per gruppo, non per file. */
  private suspend fun watchCatalog(live: AlbumsLive) {
    val touched = LinkedHashSet<MediaId>()
    val lock = Any()
    var pending: Job? = null
    live.catalog.changes.collect { change ->
      synchronized(lock) { touched += change.ids }
      if (pending?.isActive != true) {
        pending = live.scope.launch {
          delay(CATALOG_QUIET)
          val ids = synchronized(lock) { touched.toList().also { touched.clear() } }
          onCatalogChanged(live, ids)
        }
      }
    }
  }

  /** Dopo una scansione con novita', un'unione d'identita', un nascosto: proiezione, attestazione, smart (D5, D9, D16). */
  internal suspend fun onCatalogChanged(live: AlbumsLive, ids: Collection<MediaId>) {
    guarded { live.albums.onCatalogChanged(ids) }
    guarded { live.shared.replica.recompute() }
    // Un media che cambia giorno (data corretta) puo' uscire da uno smart che lo aveva: quegli album si rivalutano interi (il giorno di prima e' il loro).
    guarded {
      for (album in smartAlbumsWith(live, ids)) live.recompute.onRulesChanged(album, SMART_BUDGET)
      live.recompute.onCatalogChanged(ids, emptyList(), SMART_BUDGET)
    }
    guarded { afterCatalog() }
    guarded { scanNotices(live) }
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
    dismissedNotices.value = live.shared.choices.dismissed()
  }

  /** Gli album smart che hanno righe del motore (elementi automatici o suggeriti) su [ids]. */
  private suspend fun smartAlbumsWith(live: AlbumsLive, ids: Collection<MediaId>): Set<AlbumId> {
    if (ids.isEmpty()) return emptySet()
    val out = LinkedHashSet<AlbumId>()
    for (album in live.smartRows.smartAlbums()) {
      val state = live.smartRows.state(album) ?: continue
      if (ids.any { it in state.auto || it in state.suggestions }) out += album
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
          // La proiezione dei dati personali va prima del motore (legge `album_rule`): la segue `CatalogAlbums` da sola, qui si aspetta un attimo.
          delay(RULES_QUIET)
          for (raw in changed) runCatching { AlbumId(raw) }.getOrNull()?.let { guarded { live.recompute.onRulesChanged(it, SMART_BUDGET) } }
        }
      }
      if (hiddenChanged) live.scope.launch { guarded { live.shared.albums.refresh() } }
    }
  }

  /** Un nascosto fatto qui (`GalleryGraph.setHidden`): copertine e conteggi degli album condivisi senza aspettare il giro dopo (P9). */
  fun hiddenChanged() {
    val live = liveFlow.value ?: return
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
    val live = ensureLive() ?: return true
    val report = guarded { live.shared.sync.sync(budget) }
    if (report != null) lastSync.value = report
    flushAll(live, FlushReason.SYNC_ROUND)
    guarded { keepClosedCopies(live) }
    guarded { resume(live, SHARE_SLICE) }
    guarded { afterCatalog() }
    guarded { scanNotices(live) }
    // Un'operazione rimasta aperta (processo morto, NAS spento, regole): il suo lavoro (se ce n'e' gia' uno, resta quello).
    if (guarded { live.shared.diary.openOps().isNotEmpty() } == true) shareWork()
    return report?.incomplete != true
  }

  /** Le copie personali degli album chiusi (Q5 b, D31): con `PersonalAlbumCopies.keep` (impronta sugli elementi, P3); mai un file toccato. */
  internal suspend fun keepClosedCopies(live: AlbumsLive): Int {
    var written = 0
    for (copy in live.shared.albums.keptCopies()) {
      // Il creatore dopo "Smetti" che aveva condiviso un suo album personale ritrova quello (voce `shared`): nessuna copia.
      if (copy.mine && live.keptStore.personalSharedAs(copy.shared) != null) continue
      when (live.copies.keep(copy.copy, copy.shared, copy.title, copy.items)) {
        PersonalAlbumCopies.Result.CREATED, PersonalAlbumCopies.Result.UPDATED -> written++
        PersonalAlbumCopies.Result.UNCHANGED, PersonalAlbumCopies.Result.DELETED_BY_USER -> Unit
      }
    }
    return written
  }

  /** Un giro della condivisione (lavoro dedicato): avanza le operazioni dal diario e pubblica le voci pronte. Vero se resta qualcosa di aperto. */
  suspend fun shareRound(budget: Duration): Boolean {
    val live = ensureLive() ?: return false
    guarded { resume(live, budget) }
    flushAll(live, FlushReason.DEBOUNCE)
    guarded { resume(live, SHARE_SLICE) }
    return guarded { live.shared.diary.openOps().isNotEmpty() } ?: false
  }

  private suspend fun resume(live: AlbumsLive, budget: Duration): ShareRunResult = live.shared.orchestrator.resumeAll(budget)

  /** Una condivisione e' appena partita (dal foglio): il primo passo subito, nel processo (la conferma vive solo in memoria, L73). */
  fun shareStarted() {
    val live = liveFlow.value ?: return
    live.scope.launch { guarded { resume(live, START_BUDGET) } }
    shareWork()
  }

  /** La notte (in carica): rilevazione dei viaggi (D19, una volta per notte) e ricalcolo di sicurezza di tutti gli album smart. */
  suspend fun nightly(budget: Duration) {
    val live = ensureLive() ?: return
    guarded { live.trips.detect(budget) }?.let { lastTrips.value = it }
    guarded { for (album in live.smartRows.smartAlbums()) live.recompute.onRulesChanged(album, SMART_BUDGET) }
  }

  // --- Fine --------------------------------------------------------------------------------------------------------------------------

  /** L'azzeramento (§1.4): per primo. Niente parte piu', i giri in corso si fermano e si aspettano; le tabelle le toglie la cancellazione del database. */
  suspend fun stop() {
    stopped.set(true)
    liveFlow.value = null
    job.cancelAndJoin()
  }

  private suspend fun <T> guarded(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    roundErrors++
    GalleryLog.w(TAG) { "giro degli album: ${e.javaClass.simpleName}" }
    null
  }

  internal companion object {
    private const val TAG = "Albums"
    val CATALOG_QUIET: Duration = 5.seconds
    val RULES_QUIET: Duration = 2.seconds
    val SMART_BUDGET: Duration = 10.seconds
    val SHARE_SLICE: Duration = 20.seconds
    val START_BUDGET: Duration = 10.seconds
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
