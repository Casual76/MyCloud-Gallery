package dev.pampa.mycloudgallery.core.mediastore.sync

import android.database.sqlite.SQLiteFullException
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
import dev.pampa.mycloudgallery.core.database.PhoneSuspect
import dev.pampa.mycloudgallery.core.database.PhoneStamp
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.domain.ContentCandidate
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume
import java.util.EnumMap
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La lettura della libreria del telefono verso il catalogo (docs/fase3-progetto.md §2.1, piano 3.1). Un oggetto per
 * processo; i giri sono in fila (un `Mutex`), idempotenti e riprendibili.
 *
 * **Il giro, per volume.**
 * - *Scansione completa* (prima lettura, `getVersion` cambiata, volume tornato, API 29 senza generazioni): dai piu'
 *   recenti, a lotti, con la **scorciatoia del timbro** (stesso posto, dimensione e data di modifica: nessun byte letto)
 *   e la generazione ignorata (E-P22). Il cursore salva l'ultimo `_ID` fatto **dopo** il lotto: dopo un riavvio si
 *   riprende da li' con lo stesso `scan_epoch` (E-P4, E-P8). A fine volume, solo con accesso completo anche dopo l'ultima
 *   lettura, con il volume e la versione di prima e con gli `_ID` che rispondono, la scansione si chiude; le posizioni non
 *   viste **non** diventano assenti qui: le giudica il confronto degli `_ID` (pacchetto F).
 * - *Assenze dedotte* (un `_ID` che manca, una posizione non vista): in due tempi. Alla prima osservazione si annotano; diventano
 *   assenti solo a un confronto successivo, dopo [PhoneSyncConfig.absenceConfirmAfter], se mancano ancora, se il controllo
 *   puntuale ([MediaStorePointCheck], anche le righe `IS_PENDING`) non le trova e se non sono un'ondata sospetta. Una posizione
 *   assente che ricompare con lo stesso `_ID` torna presente anche a generazione ferma.
 * - *Incrementale*: le righe con `GENERATION_MODIFIED` oltre quella salvata; la generazione si legge **prima** della
 *   query e si salva dopo il commit. Poi `deleted_files` come **accelerazione**: ogni riga cancellata (e non ricomparsa)
 *   diventa assente; la verita' resta il confronto degli `_ID`: al ritorno in primo piano, con "Aggiorna", quando la generazione
 *   sale piu' delle righe viste e nella manutenzione quotidiana ([SyncReason.MAINTENANCE]).
 * - *Accesso parziale* (E-P1): si leggono solo le righe selezionate, **mai** un'assenza; il cursore torna a "scansione
 *   completa da fare" per quando l'accesso tornera' completo. [partialSelection] dice alla Libreria cosa e' accessibile.
 *
 * **Righe che non entrano.** `IS_PENDING` e cestinate non arrivano dalla query; zero byte, byte non da media e nomi
 * impossibili si contano ([IgnoreReason], E-M12). Le foto senza `ACCESS_MEDIA_LOCATION` non si aprono: restano "in
 * attesa" (E-P21, D9) e la generazione salvata non le supera, cosi' il conteggio sopravvive a un riavvio e, quando il
 * permesso torna, l'incrementale le ritrova. Se il posto era gia' noto resta vivo con il suo timbro di prima: nessuna
 * assenza per una foto che c'e'.
 *
 * **Permessi.** Senza accesso: `Paused(NO_PERMISSION)` e nessuna scrittura (E-P15). L'accesso si rilegge a ogni giro e
 * a ogni lotto di una scansione completa: se cambia, la scansione si ferma e il giro dopo decide.
 *
 * @param clock l'orologio di riferimento (`scan_epoch`, "visto a").
 * @param monotonicMs l'orologio dei budget, che non salta.
 * @param metadataPass la fase META, lanciata in primo piano dopo ogni giro ([attach]).
 * @param storageReady falso prima del primo sblocco (E-P2, E-P10, E-P13): `Paused(STORAGE_LOCKED)`.
 * @param pointCheck il controllo puntuale prima di un'assenza dedotta (pacchetto F, FS-1); senza, nessuna assenza dedotta.
 * @param fileProbe la prova sul disco (pacchetto F, FS2-2): un file che il disco vede ancora non diventa mai assente; uno che il disco non
 *   vede piu' (con il disco attendibile) e' la prova positiva di un'omissione. Senza, un'omissione si conferma solo dopo un tempo lungo.
 */
class DefaultPhoneLibrarySync(
  private val source: MediaStoreSource,
  private val access: PhoneAccessReader,
  private val catalog: MediaCatalog,
  private val reads: PhoneCatalogReads,
  private val clock: () -> Long = System::currentTimeMillis,
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val config: PhoneSyncConfig = PhoneSyncConfig(),
  val stats: PhoneLibraryStats = PhoneLibraryStats(),
  private val metadataPass: MetadataPass? = null,
  private val priority: MetaPriority = NoMetaPriority,
  private val storageReady: () -> Boolean = { true },
  private val pointCheck: MediaStorePointCheck? = source as? MediaStorePointCheck,
  private val fileProbe: PhoneFileProbe? = source as? PhoneFileProbe,
) : PhoneLibrarySync {

  private val statusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle(atMs = 0))
  override val status: StateFlow<SyncStatus> = statusFlow.asStateFlow()

  private val selectionFlow = MutableStateFlow<Set<MediaId>?>(null)

  /**
   * Con l'accesso parziale (E-P1): i media accessibili adesso, dall'ultima lettura della selezione; con nessun accesso un
   * insieme vuoto; con l'accesso completo null (tutto). La Libreria mostra solo questi, senza dedurre assenze.
   */
  val partialSelection: StateFlow<Set<MediaId>?> = selectionFlow.asStateFlow()

  private val runLock = Mutex()

  /**
   * Pacchetto F (FS-1): le posizioni che il MediaStore non mostra piu', annotate e in attesa di una conferma, per volume. Copia in memoria delle
   * annotazioni su disco ([MediaCatalog.phoneSuspects]), letta la prima volta che il processo incontra il volume: la manutenzione notturna o la
   * prossima apertura riprendono da li'. Solo dentro un giro (sotto [runLock]).
   */
  private val suspects = HashMap<String, HashMap<LocationKey, Suspect>>()

  /** Se su disco possono esserci annotazioni (all'avvio non si sa): evita una scrittura a vuoto a ogni giro con l'accesso ridotto. */
  private var suspectsOnDisk = true

  /** Il numero del giro: una sospetta si conferma solo in un giro dopo quello che l'ha annotata (0 = annotata da un processo precedente). */
  private var runNumber = 0L

  /** Quando matura la prossima annotazione da confermare con la prova sul disco (orologio di riferimento), o null: il giro da solo in primo piano. */
  @Volatile private var nextConfirmAt: Long? = null

  private class Suspect(val firstAt: Long, val run: Long, val msVersion: String)

  private val attachLock = Any()
  private var attached: Job? = null

  override suspend fun syncNow(reason: SyncReason): SyncReport = runWithin(reason, budget = null)

  /** Un giro che si ferma (riprendibile) quando finisce [budget]: il corpo di `PhoneSyncWorker`. */
  suspend fun runWithin(reason: SyncReason, budget: Duration?): SyncReport = runLock.withLock {
    val deadline = budget?.let { monotonicMs() + it.inWholeMilliseconds }
    withContext(dispatchers.io) { Run(reason, deadline).execute() }
  }

  override fun attach(scope: CoroutineScope) {
    synchronized(attachLock) {
      if (attached?.isActive == true) return
      attached = scope.launch { foregroundLoop() }
    }
  }

  override fun detach() {
    synchronized(attachLock) {
      attached?.cancel()
      attached = null
    }
  }

  /**
   * In primo piano: un giro subito, poi uno per ogni cambiamento del MediaStore (gia' accorpato dalla sorgente) e per
   * ogni cambio dei permessi. Le richieste arrivate durante un giro diventano **un** giro dopo (canale accorpato). Dopo
   * ogni giro la fase META lavora finche' non arriva la richiesta successiva.
   */
  private suspend fun foregroundLoop() = coroutineScope {
    val triggers = Channel<SyncReason>(Channel.CONFLATED)
    // Prima ci si iscrive ai segnali, poi si legge: con un `launch` normale gli ascoltatori partivano quando il thread era libero, cioe' dopo
    // l'inizio della prima lettura, e una foto scattata (o un permesso cambiato) in quell'intervallo non lasciava traccia fino al segnale
    // dopo. `UNDISPATCHED` li fa partire qui, subito: quando la prima lettura comincia, qualunque cambiamento arriva in `triggers`.
    launch(start = CoroutineStart.UNDISPATCHED) { source.changes().collect { triggers.trySend(SyncReason.CONTENT_CHANGE) } }
    launch(start = CoroutineStart.UNDISPATCHED) { access.state.drop(1).collect { triggers.trySend(SyncReason.PERMISSION_CHANGED) } }
    triggers.trySend(SyncReason.FOREGROUND)
    var meta: Job? = null
    var recheck: Job? = null
    for (reason in triggers) {
      meta?.cancelAndJoin()
      recheck?.cancel()
      try {
        syncNow(reason)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "giro in primo piano fallito: ${e.javaClass.simpleName}" }
      }
      // Pacchetto F: con assenze annotate che maturano, un giro da solo quando matura la prima (piu' un margine): senza, la conferma aspettava
      // il prossimo ritorno in primo piano o un cambiamento.
      nextConfirmAt?.takeIf { it > clock() }?.let { at ->
        recheck = launch {
          delay((at - clock()).coerceAtLeast(0) + config.absenceRecheckMargin.inWholeMilliseconds)
          triggers.trySend(SyncReason.FOREGROUND)
        }
      }
      val pass = metadataPass ?: continue
      meta = launch {
        try {
          runMetaSlices(pass)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "META in primo piano fallita: ${e.javaClass.simpleName}" }
        }
      }
    }
  }

  /**
   * La fase META in primo piano, a fette (GA-6). Una fetta dura al massimo `foregroundMetaBudget`; se il budget finisce prima del
   * lavoro (nella prima lettura di migliaia di foto: luogo, foto in movimento, raffiche) la fetta dopo parte da sola, dopo una
   * pausa che lascia respirare il telefono. Finisce con `Done` o con una pausa/un fallimento (permessi, memoria): da li' riparte
   * solo il giro seguente, che e' quello che sa se la causa e' passata. Un giro nuovo ferma tutto prima (`cancelAndJoin`).
   */
  private suspend fun runMetaSlices(pass: MetadataPass) {
    while (true) {
      if (pass.run(config.foregroundMetaBudget, priority) !is RunResult.BudgetExhausted) return
      delay(config.foregroundMetaPause)
    }
  }

  private enum class VolumeOutcome { CONTINUE, STOP }

  /** Pacchetto F: perche' una posizione e' candidata a diventare assente. */
  private enum class Evidence {
    /** Il MediaStore l'ha detta cancellata o nel cestino (`deleted_files`): una prova positiva, ma storica. */
    REPORTED,

    /** Il MediaStore non la mostra piu' (manca dagli `_ID`, non vista da una scansione completa): un'omissione, non una prova. */
    OMITTED,
  }

  /** Una riga con il suo posto. */
  private class Item(val row: MediaStoreRow, val location: Location.Phone)

  /** Un'osservazione per cui le regole chiedono l'hash completo. */
  private class Undecided(val item: Item, val observation: Observation, val decision: LinkDecision)

  /** Lo stato di un giro. */
  private inner class Run(private val reason: SyncReason, private val deadline: Long?) {
    private var seen = 0
    private var added = 0
    private var updated = 0
    private var vanished = 0
    private var ignored = 0
    private var pending = 0
    private var fullScan = false
    private var completed = true
    private var pause: PauseReason? = null
    private var failure: SyncFailure? = null
    private var mediaLocation = false
    private var deletedFilesAvailable: Boolean? = null
    private var absencesBlocked = 0
    private var fullScansCompleted = 0
    private var reconcileVanished = 0
    private var purged = 0
    private var selection: MutableSet<MediaId>? = null
    private val ignoredBy = EnumMap<IgnoreReason, Int>(IgnoreReason::class.java)

    suspend fun execute(): SyncReport {
      if (!storageReady()) {
        pause = PauseReason.STORAGE_LOCKED
        completed = false
        return finish()
      }
      access.refresh()
      val start = access.state.value
      runNumber++
      // Pacchetto F: le annotazioni valgono solo finche' l'accesso resta completo; con un accesso ridotto si ricomincia da capo.
      if (start.media != MediaAccess.FULL) forgetAllSuspects()
      if (start.media == MediaAccess.NONE) {
        // E-P15: permessi ritirati non vuol dire "il telefono e' vuoto". Nessuna lettura, nessuna scrittura.
        pause = PauseReason.NO_PERMISSION
        completed = false
        selectionFlow.value = emptySet()
        return finish()
      }
      mediaLocation = start.mediaLocation
      if (start.media == MediaAccess.PARTIAL) selection = HashSet()
      try {
        val mounted = source.volumes().filter(::isValidMediaStoreVolume).sortedWith(PRIMARY_FIRST)
        handleUnmounted(mounted.toSet())
        for (volume in mounted) {
          if (syncVolume(volume, start.media) == VolumeOutcome.STOP) break
        }
        if (reason == SyncReason.MAINTENANCE && completed && start.media == MediaAccess.FULL) maintenance(mounted)
      } catch (e: CancellationException) {
        throw e
      } catch (e: OriginalBytesUnavailableException) {
        // Non dovrebbe risalire fin qui (le foto senza permesso restano in attesa), ma non e' una perdita di permessi.
        completed = false
        failure = SyncFailure.UNEXPECTED
      } catch (e: SecurityException) {
        completed = false
        failure = SyncFailure.PERMISSION_LOST
        access.refresh()
        if (access.state.value.media == MediaAccess.NONE) pause = PauseReason.NO_PERMISSION
      } catch (e: Exception) {
        completed = false
        if (isDiskFull(e)) {
          failure = SyncFailure.DISK_FULL
          pause = PauseReason.DISK_FULL
        } else {
          failure = SyncFailure.MEDIASTORE_ERROR
          GalleryLog.w(TAG) { "giro interrotto: ${e.javaClass.simpleName}" }
        }
      }
      val chosen = selection
      selectionFlow.value = when {
        chosen != null -> chosen.toSet()
        pause == PauseReason.NO_PERMISSION -> emptySet()
        else -> null
      }
      return finish()
    }

    private fun finish(): SyncReport {
      val report = SyncReport(
        seen = seen,
        added = added,
        updated = updated,
        vanished = vanished,
        ignored = ignored,
        pendingMediaLocation = pending,
        fullScan = fullScan,
        completed = completed,
      )
      statusFlow.value = when {
        pause != null -> SyncStatus.Paused(pause!!)
        pending > 0 -> SyncStatus.Paused(PauseReason.MEDIA_LOCATION_DENIED)
        else -> SyncStatus.Idle(clock())
      }
      stats.update { s ->
        val counts = HashMap(s.ignored)
        ignoredBy.forEach { (k, v) -> counts[k] = (counts[k] ?: 0L) + v }
        s.copy(
          lastRunAtMs = clock(),
          lastReason = reason,
          lastReport = report,
          lastFailure = failure,
          ignored = counts,
          deletedFilesAvailable = deletedFilesAvailable ?: s.deletedFilesAvailable,
          absencesBlocked = s.absencesBlocked + absencesBlocked,
          fullScansCompleted = s.fullScansCompleted + fullScansCompleted,
          reconcileVanished = s.reconcileVanished + reconcileVanished,
          purgedAbsences = s.purgedAbsences + purged,
          suspectedAbsences = suspects.values.sumOf { it.size },
        )
      }
      return report
    }

    // --- Volumi ------------------------------------------------------------------------------------------

    /** Volumi conosciuti e non montati (microSD tolta, E-P6): non raggiungibili, mai assenti. Al ritorno, completa. */
    private suspend fun handleUnmounted(mounted: Set<String>) {
      for (cursor in catalog.mediaStoreCursors()) {
        if (cursor.volume in mounted) continue
        forgetVolumeSuspects(cursor.volume)
        catalog.markVolumeUnavailable(cursor.volume)
        if (cursor.generation != null || cursor.deletedGeneration != null || cursor.scanEpoch != null) {
          catalog.saveMediaStoreCursor(cursor.copy(generation = null, deletedGeneration = null, scanEpoch = null, scanResumeAfterId = null))
        }
      }
    }

    private suspend fun syncVolume(volume: String, media: MediaAccess): VolumeOutcome {
      val version = try {
        source.version(volume)
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        // Volume in montaggio o sparito tra la lista e la domanda: lo si rivede al prossimo giro.
        completed = false
        return VolumeOutcome.CONTINUE
      }
      val cursor = catalog.mediaStoreCursor(volume)
      return when {
        media == MediaAccess.PARTIAL -> selectionScan(volume, version, cursor)
        cursor == null || cursor.msVersion != version || cursor.scanEpoch != null || cursor.generation == null ->
          fullScan(volume, version, cursor)
        else -> incremental(volume, version, cursor)
      }
    }

    /** Scansione completa, riprendibile; nessuna assenza scritta qui (pacchetto F): le non viste le giudica il confronto degli _ID. */
    private suspend fun fullScan(volume: String, version: String, previous: MediaStoreCursor?): VolumeOutcome {
      fullScan = true
      var cursor = if (previous != null && previous.scanEpoch != null && previous.msVersion == version) {
        previous
      } else {
        // La generazione si legge PRIMA di leggere le righe: cio' che cambia durante la scansione lo vede l'incrementale.
        val g0 = source.generation(volume)
        MediaStoreCursor(
          volume = volume,
          msVersion = version,
          generation = g0,
          deletedGeneration = g0,
          scanEpoch = clock(),
          scanResumeAfterId = null,
          lastFullScanAt = previous?.lastFullScanAt,
          lastReconcileAt = previous?.lastReconcileAt,
        ).also { catalog.saveMediaStoreCursor(it) }
      }
      val epoch = checkNotNull(cursor.scanEpoch)
      val total = try {
        source.allIds(volume).size
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        null
      }
      statusFlow.value = SyncStatus.Scanning(seen, total, full = true)
      while (true) {
        if (overBudget()) return interrupted()
        if (!stillFullAccess()) return interrupted()
        val rows = source.query(volume, sinceGeneration = null, afterId = cursor.scanResumeAfterId, limit = config.batchSize)
        if (rows.isEmpty()) break
        val next = cursor.copy(scanResumeAfterId = rows.last().id)
        val floor = processBatch(version, rows, seenAt = epoch, ignoreGeneration = true) { f ->
          next.copy(generation = lower(next.generation, f))
        }
        cursor = next.copy(generation = lower(next.generation, floor))
        statusFlow.value = SyncStatus.Scanning(seen, total, full = true)
      }
      // Pacchetto F (FS-1, FS-2, FS-5): la fine della lettura **non scrive assenze**. Prima di chiudere la scansione gli _ID del volume devono
      // rispondere, non vuoti, e DOPO quella lettura l'accesso deve essere ancora completo e il volume montato con la stessa versione; se no
      // la scansione resta aperta (non conclusa: il giro dopo ripete solo questa parte). Le posizioni non viste le giudica il confronto degli
      // _ID, in due tempi: la prima osservazione e' subito dopo.
      val live = liveIdsOrNull(volume)
      if (live != null && live.isEmpty()) absencesBlocked++
      if (live == null || live.isEmpty() || !stillFullAccess() || !stillSameVolume(volume, version)) {
        // FS2-3: la fase finale non si e' conclusa. Il giro dopo rilegge dalla testa, non dopo la vecchia coda: le foto nuove (in testa)
        // entrano anche mentre la scansione resta aperta. Le righe gia' viste passano dalla scorciatoia del timbro.
        catalog.saveMediaStoreCursor(cursor.copy(scanResumeAfterId = null))
        return interrupted()
      }
      catalog.saveMediaStoreCursor(cursor.copy(scanEpoch = null, scanResumeAfterId = null, lastFullScanAt = clock()))
      fullScansCompleted++
      reconcile(volume)
      return VolumeOutcome.CONTINUE
    }

    /** Gli `_ID` del volume, o null se il MediaStore non risponde (un errore non e' "nessuna riga"). */
    private suspend fun liveIdsOrNull(volume: String): LongArray? = try {
      source.allIds(volume)
    } catch (e: CancellationException) {
      throw e
    } catch (e: SecurityException) {
      throw e
    } catch (e: Exception) {
      null
    }

    /** Pacchetto F (FS-1, FS-2): il volume e' ancora montato e con la stessa versione del MediaStore (un errore vale "no"). */
    private suspend fun stillSameVolume(volume: String, version: String): Boolean = try {
      volume in source.volumes() && source.version(volume) == version
    } catch (e: CancellationException) {
      throw e
    } catch (e: SecurityException) {
      throw e
    } catch (e: Exception) {
      false
    }

    /** Le righe cambiate dopo la generazione salvata, poi le cancellazioni come accelerazione. */
    private suspend fun incremental(volume: String, version: String, cursor: MediaStoreCursor): VolumeOutcome {
      val since = checkNotNull(cursor.generation)
      val now = source.generation(volume)
      // Generazioni sparite o tornate indietro senza cambio di versione: non ci si fida, scansione completa.
      if (now == null || now < since) return fullScan(volume, version, cursor.copy(generation = null))
      // Pacchetto F (12/11): al ritorno in primo piano (e con "Aggiorna") gli _ID si confrontano con la cache anche se la generazione e' ferma.
      // Le cancellazioni fatte ad app chiusa possono non lasciare traccia: `deleted_files` puo' rispondere vuoto alle app, la stima "generazione
      // salita piu' delle righe viste" sbaglia quando una transazione porta piu' righe (Quick Share, WhatsApp), e il giro in background puo'
      // aver gia' portato avanti la generazione. Prima restava solo la manutenzione notturna in carica.
      val onReturn = reason in RETURN_REASONS
      if (now == since) {
        if (onReturn && quickReconcileDue(cursor)) reconcile(volume)
        return VolumeOutcome.CONTINUE
      }
      val seenIds = HashSet<Long>()
      var after: Long? = null
      var floor: Long? = null
      var returned = 0L
      statusFlow.value = SyncStatus.Scanning(seen, null, full = false)
      while (true) {
        if (overBudget()) return interrupted()
        val rows = source.query(volume, sinceGeneration = since, afterId = after, limit = config.batchSize)
        if (rows.isEmpty()) break
        rows.forEach { seenIds += it.id }
        returned += rows.size
        val f = processBatch(version, rows, seenAt = clock(), ignoreGeneration = false) { null }
        floor = minOfNullable(floor, f)
        after = rows.last().id
        statusFlow.value = SyncStatus.Scanning(seen, null, full = false)
      }
      val deletedFrom = cursor.deletedGeneration ?: since
      val deleted = try {
        source.deletedSince(volume, deletedFrom)
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        null
      }
      deletedFilesAvailable = deleted != null
      var explained = returned
      val reportedAt = clock()
      var reported: List<PhonePosition> = emptyList()
      if (deleted != null) {
        explained += deleted.count { (it.generationModified ?: Long.MIN_VALUE) in (since + 1)..now }
        // Solo fino alla generazione letta prima della query (le altre al prossimo giro, con le righe che le seguono) e
        // mai una riga che la query ha appena visto viva (cestino svuotato e ripristinato con lo stesso _ID).
        val gone = deleted.asSequence()
          .filter { (it.generationModified ?: Long.MIN_VALUE) <= now && it.id !in seenIds }
          .map { it.id }
          .distinct()
          .toList()
        reported = gone.flatMap { reads.positionsAtMediaStoreRow(volume, it, version) }.distinctBy { it.key }
      }
      // Un provider che ha risposto senza permessi non deve far avanzare la generazione oltre righe mai viste.
      if (!stillFullAccess()) return interrupted()
      catalog.saveMediaStoreCursor(
        cursor.copy(
          generation = minOf(now, floor ?: now),
          deletedGeneration = if (deleted != null) now else cursor.deletedGeneration,
        ),
      )
      // Pacchetto F (FS2-1): una riga che il MediaStore dice cancellata (o finita nel cestino) e' una prova positiva, ma **storica**: puo' essere
      // gia' tornata (ripristinata dopo la lettura del cestino). Diventa assente dalla stessa strada delle altre: controllo puntuale adesso,
      // il disco che non la vede, accesso, volume e versione ricontrollati dopo l'ultima lettura e prima di ogni lotto.
      if (reported.isNotEmpty()) confirmGone(volume, version, reported, Evidence.REPORTED, reportedAt)
      // La generazione e' salita piu' di quanto spieghino le righe viste e quelle tolte: probabilmente cancellazioni che
      // `deleted_files` non dice (sull'emulatore API 37 risponde vuoto alle app). Si confrontano gli _ID con la cache
      // subito, al massimo ogni [PhoneSyncConfig.quickReconcileEvery], invece di aspettare la manutenzione quotidiana.
      if ((now - since > explained || onReturn) && quickReconcileDue(cursor)) reconcile(volume)
      return VolumeOutcome.CONTINUE
    }

    private fun quickReconcileDue(cursor: MediaStoreCursor): Boolean {
      val every = config.quickReconcileEvery ?: return false
      val last = cursor.lastReconcileAt ?: return true
      return clock() - last >= every.inWholeMilliseconds || clock() < last
    }

    /**
     * Accesso parziale (E-P1): solo le righe selezionate, nessuna assenza, nessuna ripresa. Il cursore torna a
     * "scansione completa da fare": con l'accesso completo si riparte da una scansione intera, che e' l'unica a poter
     * dedurre assenze.
     */
    private suspend fun selectionScan(volume: String, version: String, previous: MediaStoreCursor?): VolumeOutcome {
      val reset = MediaStoreCursor(
        volume = volume,
        msVersion = version,
        lastFullScanAt = previous?.lastFullScanAt,
        lastReconcileAt = previous?.lastReconcileAt,
      )
      if (previous != reset) catalog.saveMediaStoreCursor(reset)
      var after: Long? = null
      statusFlow.value = SyncStatus.Scanning(seen, null, full = false)
      while (true) {
        if (overBudget()) return interrupted()
        val rows = source.query(volume, sinceGeneration = null, afterId = after, limit = config.batchSize)
        if (rows.isEmpty()) break
        processBatch(version, rows, seenAt = clock(), ignoreGeneration = true) { null }
        val chosen = checkNotNull(selection)
        for (row in rows) {
          val location = row.locationOrNull(version) ?: continue
          reads.mediaIdAt(location.key)?.let { chosen += it }
        }
        after = rows.last().id
      }
      return VolumeOutcome.CONTINUE
    }

    // --- Manutenzione ------------------------------------------------------------------------------------

    /** La riconciliazione quotidiana degli `_ID` (la verita' dietro `deleted_files`) e la pulizia delle assenze scadute. */
    private suspend fun maintenance(mounted: List<String>) {
      for (volume in mounted) {
        if (overBudget()) return
        reconcile(volume)
      }
      purged += catalog.purgeExpiredAbsences(config.purgeCap)
    }

    /**
     * Il confronto degli `_ID` con la cache (la verita' dietro `deleted_files`), pacchetto F (FS-1, FS2-2):
     * - prima le posizioni del catalogo, poi gli `_ID` del MediaStore (un file salvato dall'app in mezzo non sembra sparito); con una risposta
     *   vuota, un accesso non piu' completo o il volume cambiato DOPO la lettura non si conclude niente;
     * - **recupero**: una posizione assente torna presente, anche a generazione ferma, se il suo `_ID` e' di nuovo vivo o se nel suo posto c'e'
     *   di nuovo una riga (con un altro `_ID`);
     * - una posizione presente con un `_ID` che manca, o con un `_ID` di un'altra versione (non vista dall'ultima scansione completa), si
     *   **annota** (su disco) e basta. Un'omissione non e' una prova: il MediaStore puo' rispondere a meta';
     * - un'annotazione si conferma solo in un giro successivo e dopo [PhoneSyncConfig.absenceConfirmAfter], con la stessa versione, se manca
     *   ancora, se il controllo puntuale (per `_ID` e per posto, anche `IS_PENDING`) non la trova e **se il disco non vede piu' il file**
     *   (prova positiva, con il disco attendibile: i file di alcune foto vive ci sono). Senza la prova del disco, solo dopo
     *   [PhoneSyncConfig.absenceConfirmWithoutProof] e mai in un'ondata. Accesso, volume e versione si ricontrollano dopo l'ultima lettura e
     *   prima di ogni lotto; una posizione rivista dopo la fotografia non si tocca.
     */
    private suspend fun reconcile(volume: String) {
      val cursor = catalog.mediaStoreCursor(volume) ?: return
      if (cursor.scanEpoch != null) return
      // Una scansione completa da fare (dopo l'accesso parziale, un volume tornato) viene prima. Su API 29 la generazione non c'e' mai: li'
      // ogni giro e' una scansione completa, e il confronto viene dopo.
      if (cursor.generation == null && source.generation(volume) != null) return
      val version = source.version(volume)
      if (cursor.msVersion != version) return
      val snapshotAt = clock()
      val positions = reads.phonePositions(volume)
      val live = source.allIds(volume)
      if (live.isEmpty()) {
        absencesBlocked++
        return
      }
      // Un permesso ridotto mentre si leggevano gli _ID ("foto selezionate") li riduce alla selezione; un volume tolto li svuota a meta'.
      if (!stillFullAccess() || !stillSameVolume(volume, version)) {
        completed = false
        return
      }
      val liveSet = HashSet<Long>(live.size * 2)
      live.forEach { liveSet += it }

      recover(volume, version, positions, liveSet)

      val missing = positions.filter { it.present && it.idFor(version).let { id -> id == null || id !in liveSet } }
      val noted = notedFor(volume)
      val missingKeys = missing.mapTo(HashSet()) { it.key }
      val now = clock()
      // Un'annotazione "dal futuro" (l'orologio e' tornato indietro) si rifa' adesso: aspetterebbe all'infinito.
      val dropped = noted.filter { (key, s) -> key !in missingKeys || s.msVersion != version || s.firstAt > now }
      val added = missing.filter { it.key !in noted || noted.getValue(it.key).let { s -> s.msVersion != version || s.firstAt > now } }
      dropped.keys.forEach { noted.remove(it) }
      added.forEach { noted[it.key] = Suspect(now, runNumber, version) }
      if (dropped.isNotEmpty() || added.isNotEmpty()) {
        catalog.updatePhoneSuspects(
          volume,
          put = added.associate { it.key to PhoneSuspect(now, version) },
          remove = dropped.mapValues { (_, s) -> PhoneSuspect(s.firstAt, s.msVersion) },
        )
        if (added.isNotEmpty()) suspectsOnDisk = true
      }
      catalog.saveMediaStoreCursor(cursor.copy(lastReconcileAt = clock()))
      val wait = config.absenceConfirmAfter.inWholeMilliseconds
      val due = missing.filter { p -> noted[p.key]?.let { it.run < runNumber && now >= it.firstAt && now - it.firstAt >= wait } == true }
      if (due.isNotEmpty()) {
        val alive = positions.filter { it.present && it.idFor(version)?.let { id -> id in liveSet } == true }
        val wave = missing.size >= maxOf(config.vanishAlarmMin, live.size)
        confirmGone(volume, version, due, Evidence.OMITTED, snapshotAt, alive, wave)
      }
      scheduleConfirm()
    }

    /**
     * Il prossimo istante in cui matura un'annotazione che la prova sul disco puo' confermare (il giro da solo in primo piano), non prima che
     * un confronto sia di nuovo permesso ([PhoneSyncConfig.quickReconcileEvery]). Solo istanti futuri: un'annotazione gia' matura e non
     * confermata (disco che non sa) aspetta il tempo lungo, senza giri a vuoto.
     */
    private fun scheduleConfirm() {
      val now = clock()
      val wait = config.absenceConfirmAfter.inWholeMilliseconds
      val every = config.quickReconcileEvery?.inWholeMilliseconds
      nextConfirmAt = if (fileProbe == null || every == null) {
        null
      } else {
        suspects.values.asSequence().flatMap { it.values.asSequence() }.map { it.firstAt + wait }.filter { it > now }.minOrNull()
          ?.let { maxOf(it, now + every) }
      }
    }

    /**
     * La conferma, uguale per tutte le strade: il controllo puntuale (chi c'e' si rilegge e resta), la prova sul disco, poi le assenze a lotti,
     * con accesso, volume e versione ricontrollati dopo l'ultima lettura e prima di ogni lotto, e mai su una posizione rivista dopo [snapshotAt].
     */
    private suspend fun confirmGone(
      volume: String,
      version: String,
      candidates: List<PhonePosition>,
      evidence: Evidence,
      snapshotAt: Long,
      alive: List<PhonePosition> = emptyList(),
      wave: Boolean = false,
    ) {
      // Senza controllo puntuale nessuna assenza: meglio un fantasma in piu'.
      val check = pointCheck ?: return
      val found = check.stillThere(volume, candidates.mapNotNull { it.idFor(version) }, candidates.map { it.place })
      val foundIds = found.mapTo(HashSet()) { it.row.id }
      // Il posto si confronta con la chiave del catalogo (maiuscole e Unicode normalizzati), non con le stringhe del MediaStore.
      val foundKeys = found.mapNotNullTo(HashSet()) { it.row.locationOrNull(version)?.key }
      val there = candidates.filterTo(HashSet()) { p -> p.idFor(version)?.let { it in foundIds } == true || p.key in foundKeys }
      forgetSuspects(volume, there.map { it.key })
      // Chi c'e' e non e' in scrittura si rilegge: una posizione con l'_ID di un'altra versione impara quello nuovo.
      val readable = found.filter { !it.pending }.map { it.row }.distinctBy { it.id }
      if (readable.isNotEmpty()) processBatch(version, readable, seenAt = clock(), ignoreGeneration = true) { null }
      var gone = candidates.filter { it !in there }
      if (gone.isEmpty()) return
      gone = when (evidence) {
        Evidence.OMITTED -> provenGone(volume, gone, alive, wave)
        // Anche una cancellazione detta dal MediaStore: un file che il disco vede ancora resta.
        Evidence.REPORTED -> gone.filter { fileProbe?.exists(volume, it.place) != true }
      }
      // Dopo l'ultima lettura (controllo puntuale, disco): accesso completo, stesso volume, stessa versione. Se no il giro non e' concluso,
      // anche quando non c'e' niente da scrivere (un disco che non sa perche' il volume se n'e' andato non e' una risposta).
      if (!stillFullAccess() || !stillSameVolume(volume, version)) {
        completed = false
        return
      }
      if (gone.isEmpty()) return
      for (chunk in gone.chunked(config.vanishCap)) {
        if (overBudget() || !stillFullAccess() || !stillSameVolume(volume, version)) {
          completed = false
          return
        }
        for (p in chunk) {
          if (catalog.markVanishedIfNotSeenSince(p.key, snapshotAt)) {
            vanished++
            if (evidence == Evidence.OMITTED) reconcileVanished++
          }
        }
        forgetSuspects(volume, chunk.map { it.key })
      }
    }

    /**
     * FS2-2: quali omissioni sono provate. Prima il disco deve essere attendibile adesso: i file di alcune foto vive ci sono (di piu' in
     * un'ondata). Poi, per ognuna: il file c'e' → resta; manca → provata; non si sa → solo dopo [PhoneSyncConfig.absenceConfirmWithoutProof]
     * dalla prima annotazione, e mai in un'ondata.
     */
    private suspend fun provenGone(volume: String, gone: List<PhonePosition>, alive: List<PhonePosition>, wave: Boolean): List<PhonePosition> {
      val probe = fileProbe
      // Il campione e' sparso su tutte le posizioni vive (non le prime: possono stare tutte nella stessa cartella).
      val sample = spread(alive, if (wave) config.waveProbeSample else config.probeSample)
      val answers = if (probe == null) emptyList() else sample.map { probe.exists(volume, it.place) }
      val trusted = probe != null && answers.isNotEmpty() && answers.all { it == true }
      // Un disco che risponde ma non vede foto vive (cieco), o un'ondata senza la prova del disco: fermate, e si contano.
      if (answers.any { it == false } || (wave && !trusted)) absencesBlocked++
      val noted = suspects[volume].orEmpty()
      val now = clock()
      val long = config.absenceConfirmWithoutProof.inWholeMilliseconds
      return gone.filter { p ->
        when (if (trusted) probe?.exists(volume, p.place) else null) {
          true -> false
          false -> true
          null -> !wave && noted[p.key]?.let { now >= it.firstAt && now - it.firstAt >= long } == true
        }
      }
    }

    /**
     * Recupero (FS-1, FS2-5): posizioni assenti il cui `_ID` (della stessa versione) e' di nuovo vivo, o nel cui posto c'e' di nuovo una riga
     * con un `_ID` che il catalogo non conosce. Le righe si rileggono e la registrazione le rimette presenti (stesso media, dalle impronte).
     * Al piu' [PhoneSyncConfig.recoverCap] `_ID` sconosciuti per giro.
     */
    private suspend fun recover(volume: String, version: String, positions: List<PhonePosition>, liveSet: Set<Long>) {
      val check = pointCheck ?: return
      val absent = positions.filter { !it.present }
      if (absent.isEmpty()) return
      val sameId = absent.mapNotNullTo(HashSet()) { p -> p.idFor(version)?.takeIf { it in liveSet } }
      val known = positions.mapNotNullTo(HashSet()) { it.idFor(version) }
      // Dai piu' alti: una riga ripristinata e' una riga nuova, con l'_ID piu' alto.
      val unknown = liveSet.filter { it !in known }.sortedDescending().take(config.recoverCap)
      if (sameId.isEmpty() && unknown.isEmpty()) return
      val absentKeys = absent.mapTo(HashSet()) { it.key }
      val rows = check.stillThere(volume, sameId + unknown, emptyList())
        .filter { !it.pending }
        .map { it.row }
        .distinctBy { it.id }
        .filter { it.id in sameId || it.locationOrNull(version)?.key in absentKeys }
      if (rows.isNotEmpty()) processBatch(version, rows, seenAt = clock(), ignoreGeneration = true) { null }
    }

    // --- Annotazioni ------------------------------------------------------------------------------------

    /** Le annotazioni di [volume]: dalla memoria, o dal disco la prima volta che il processo incontra il volume. */
    private suspend fun notedFor(volume: String): HashMap<LocationKey, Suspect> = suspects.getOrPut(volume) {
      HashMap<LocationKey, Suspect>().apply {
        catalog.phoneSuspects(volume).forEach { (key, s) -> put(key, Suspect(s.firstAtMillis, run = 0, msVersion = s.msVersion)) }
      }
    }

    private suspend fun forgetSuspects(volume: String, keys: Collection<LocationKey>) {
      val noted = suspects[volume] ?: return
      val removed = keys.mapNotNull { key -> noted.remove(key)?.let { key to PhoneSuspect(it.firstAt, it.msVersion) } }.toMap()
      if (removed.isNotEmpty()) catalog.updatePhoneSuspects(volume, emptyMap(), removed)
    }

    private suspend fun forgetVolumeSuspects(volume: String) {
      suspects.remove(volume)
      if (suspectsOnDisk) catalog.clearPhoneSuspects(volume)
    }

    private suspend fun forgetAllSuspects() {
      suspects.clear()
      nextConfirmAt = null
      if (!suspectsOnDisk) return
      try {
        catalog.clearPhoneSuspects()
        suspectsOnDisk = false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "annotazioni non tolte: ${e.javaClass.simpleName}" }
      }
    }

    // --- Un lotto ----------------------------------------------------------------------------------------

    /**
     * Un lotto di righe: scorciatoia del timbro, impronte, registrazione. [cursorAfter] costruisce, dato il "pavimento"
     * delle righe vive ma non elaborabili adesso, il cursore da salvare **con l'ultima scrittura** del lotto. Restituisce
     * il pavimento: la generazione salvata non deve superarlo, cosi' quelle righe tornano al prossimo incrementale.
     */
    private suspend fun processBatch(
      version: String,
      rows: List<MediaStoreRow>,
      seenAt: Long,
      ignoreGeneration: Boolean,
      cursorAfter: (floor: Long?) -> MediaStoreCursor?,
    ): Long? {
      seen += rows.size
      val items = ArrayList<Item>(rows.size)
      for (row in rows) {
        val location = row.locationOrNull(version)
        when {
          location == null -> ignore(IgnoreReason.INVALID_NAME)
          row.size <= 0L -> ignore(IgnoreReason.ZERO_BYTES)
          else -> items += Item(row, location)
        }
      }

      // 1. Scorciatoia del timbro: nessun byte letto per cio' che non e' cambiato.
      val confirmed = if (items.isEmpty()) {
        emptyList()
      } else {
        catalog.confirmAllPresent(items.map { PhoneStamp(it.location, it.row.stamp()) }, seenAt, ignoreGeneration)
      }

      // 2. Impronte rapide per il resto. Le foto senza il permesso della posizione non si aprono (D9, E-P21).
      val waiting = ArrayList<Item>()
      val observed = ArrayList<Pair<Item, Observation>>()
      items.forEachIndexed { index, item ->
        if (confirmed[index]) return@forEachIndexed
        if (item.row.type == RowType.IMAGE && !mediaLocation) {
          pending++
          waiting += item
          return@forEachIndexed
        }
        when (val read = PhoneFingerprints.quick(source, item.row)) {
          is QuickRead.Ok -> observed += item to observationOf(item, read.fingerprint)
          QuickRead.ZeroBytes -> ignore(IgnoreReason.ZERO_BYTES)
          QuickRead.NotMedia -> ignore(IgnoreReason.NOT_MEDIA)
          QuickRead.OriginalUnavailable -> {
            // Permesso della posizione ritirato a meta' giro: da qui in poi le foto aspettano.
            mediaLocation = false
            pending++
            waiting += item
          }
          QuickRead.Unreadable -> {
            ignoredBy.merge(IgnoreReason.UNREADABLE, 1) { a, b -> a + b }
            waiting += item
          }
        }
      }

      // 3. I posti conosciuti che non si possono leggere adesso restano vivi con il timbro di prima: mai un'assenza per
      //    un file che c'e' (una foto modificata mentre il permesso della posizione e' negato, un file in scrittura).
      if (waiting.isNotEmpty()) keepAlive(waiting, seenAt)
      val floor = waiting.mapNotNull { it.row.generationModified }.minOrNull()?.minus(1)

      // 4. Registrazione, con il cursore nell'ultima scrittura del lotto.
      record(observed, seenAt, cursorAfter(floor))
      return floor
    }

    private suspend fun keepAlive(waiting: List<Item>, seenAt: Long) {
      val stamps = waiting.mapNotNull { item -> reads.storedStamp(item.location.key)?.let { PhoneStamp(item.location, it) } }
      if (stamps.isNotEmpty()) catalog.confirmAllPresent(stamps, seenAt, ignoreGeneration = true)
    }

    /**
     * Registra le osservazioni. Prima con `WAIT`; a chi resta indeciso (stesso hash rapido di un altro media) si calcola
     * l'hash completo, se il file e' sotto la soglia, e si rilegge per intero anche la copia gia' nota quando e' sul
     * telefono; chi resta ancora indeciso nasce separato (`KEEP_SEPARATE`: nel dubbio non si unisce, si unira' quando le
     * impronte lo proveranno). Il cursore va con l'ultima scrittura: un riavvio a meta' ripete il lotto, senza perderlo.
     */
    private suspend fun record(observed: List<Pair<Item, Observation>>, seenAt: Long, cursor: MediaStoreCursor?) {
      if (observed.isEmpty()) {
        cursor?.let { catalog.saveMediaStoreCursor(it) }
        return
      }
      val first = catalog.recordAll(observed.map { it.second }, MediaCatalog.WhenUndecided.WAIT, seenAt = seenAt)
      val undecided = ArrayList<Undecided>()
      first.forEachIndexed { i, outcome ->
        if (outcome.decision.isUndecided()) {
          undecided += Undecided(observed[i].first, observed[i].second, outcome.decision)
        } else {
          tally(outcome.decision)
        }
      }
      if (undecided.isEmpty()) {
        cursor?.let { catalog.saveMediaStoreCursor(it) }
        return
      }
      val separate = ArrayList<Observation>()
      var retry = ArrayList<Undecided>()
      for (u in undecided) {
        val full = if (u.observation.fingerprint.size <= config.fullHashMaxBytes) {
          PhoneFingerprints.full(source, u.item.row, u.observation.fingerprint)
        } else {
          null
        }
        if (full == null) separate += u.observation else retry += Undecided(u.item, u.observation.copy(fingerprint = full), u.decision)
      }
      var round = 0
      while (retry.isNotEmpty() && round < 2) {
        val outcomes = catalog.recordAll(retry.map { it.observation }, MediaCatalog.WhenUndecided.WAIT, seenAt = seenAt)
        val still = ArrayList<Undecided>()
        outcomes.forEachIndexed { i, outcome ->
          if (outcome.decision.isUndecided()) still += Undecided(retry[i].item, retry[i].observation, outcome.decision) else tally(outcome.decision)
        }
        retry = still
        round++
        // Le copie gia' note non hanno l'hash completo: si rileggono (solo telefono, sotto la soglia) e si riprova.
        if (retry.isEmpty() || round >= 2 || !learnCandidates(retry.flatMap { candidatesOf(it.decision) }, seenAt)) break
      }
      separate += retry.map { it.observation }
      if (separate.isEmpty()) {
        cursor?.let { catalog.saveMediaStoreCursor(it) }
        return
      }
      catalog.recordAll(separate, MediaCatalog.WhenUndecided.KEEP_SEPARATE, cursor = cursor, seenAt = seenAt)
        .forEach { tally(it.decision) }
    }

    /** Rilegge per intero le copie sul telefono dei candidati senza hash completo e lo fa imparare al catalogo. */
    private suspend fun learnCandidates(candidates: List<ContentCandidate>, seenAt: Long): Boolean {
      var learned = false
      var budget = config.maxCandidateHashesPerBatch
      for (candidate in candidates.distinctBy { it.version.id }) {
        if (budget <= 0) break
        val fingerprint = candidate.version.fingerprint
        if (fingerprint.fullHash != null || fingerprint.size > config.fullHashMaxBytes) continue
        val media = catalog.media(candidate.mediaId) ?: continue
        val known = media.locations.firstOrNull {
          it.versionId == candidate.version.id && it.isPresent && (it.location as? Location.Phone)?.mediaStoreId != null
        } ?: continue
        val phone = known.location as Location.Phone
        val current = try {
          source.version(phone.volume)
        } catch (e: CancellationException) {
          throw e
        } catch (e: SecurityException) {
          throw e
        } catch (e: Exception) {
          continue
        }
        if (phone.mediaStoreVersion != current) continue
        val type = if (media.kind.isVideo) RowType.VIDEO else RowType.IMAGE
        if (type == RowType.IMAGE && !mediaLocation) continue
        budget--
        val row = MediaStoreRow(
          volume = phone.volume,
          id = checkNotNull(phone.mediaStoreId),
          type = type,
          displayName = phone.displayName,
          relativePath = phone.relativePath,
          mime = null,
          size = known.stamp.size,
          dateModifiedMs = known.stamp.modifiedAtMillis,
          generationModified = known.stamp.generation,
        )
        val full = PhoneFingerprints.full(source, row, fingerprint) ?: continue
        val outcome = catalog.recordAll(
          listOf(Observation(phone, known.stamp, full, media.kind, media.source)),
          MediaCatalog.WhenUndecided.WAIT,
          seenAt = seenAt,
        ).single()
        if (outcome.decision is LinkDecision.SameFile) learned = true
      }
      return learned
    }

    private fun observationOf(item: Item, fingerprint: Fingerprint): Observation {
      val row = item.row
      val classified = SourceClassifier.classify(row.volume, row.relativePath, row.displayName, row.ownerPackage, row.mime)
      return Observation(
        location = item.location,
        // La dimensione vera e' quella dei byte letti: timbro e impronta devono parlare dello stesso file.
        stamp = FileStamp(fingerprint.size, row.dateModifiedMs, row.generationModified),
        fingerprint = fingerprint,
        kind = provisionalKind(row),
        source = classified.source,
        captureTimeHintMillis = row.dateTakenMs?.takeIf { it > 0 },
      )
    }

    private fun tally(decision: LinkDecision) {
      when (decision) {
        LinkDecision.NewMedia -> added++
        is LinkDecision.Replaced -> if (decision.next == LinkDecision.NewMedia) added++ else updated++
        is LinkDecision.SameFile -> if (decision.reappeared || decision.stampChanged) updated++
        is LinkDecision.NeedsFullHash -> Unit
        else -> updated++
      }
    }

    private fun ignore(reason: IgnoreReason) {
      ignored++
      ignoredBy.merge(reason, 1) { a, b -> a + b }
    }

    private fun overBudget(): Boolean = deadline != null && monotonicMs() >= deadline

    private fun interrupted(): VolumeOutcome {
      completed = false
      if (access.state.value.media == MediaAccess.NONE) pause = PauseReason.NO_PERMISSION
      return VolumeOutcome.STOP
    }

    /** L'accesso si rilegge a ogni lotto di una scansione completa: le assenze si deducono solo con l'accesso completo. */
    private fun stillFullAccess(): Boolean {
      access.refresh()
      val now = access.state.value
      if (!now.mediaLocation) mediaLocation = false
      return now.media == MediaAccess.FULL
    }
  }

  private companion object {
    const val TAG = "PhoneSync"

    /** I giri dopo i quali gli `_ID` si confrontano con la cache anche a generazione ferma: l'app torna davanti all'utente. */
    val RETURN_REASONS: Set<SyncReason> = setOf(SyncReason.APP_START, SyncReason.FOREGROUND, SyncReason.MANUAL)

    val PRIMARY_FIRST: Comparator<String> = compareBy<String> { it != "external_primary" }.thenBy { it }

    private val RAW_MIMES = setOf("image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw")

    /** Al piu' [n] elementi presi a intervalli regolari su tutta la lista. */
    fun <T> spread(list: List<T>, n: Int): List<T> = if (list.size <= n) list else List(n) { list[it * list.size / n] }

    fun MediaStoreRow.locationOrNull(version: String): Location.Phone? = try {
      phoneLocation(version)
    } catch (e: IllegalArgumentException) {
      null
    }

    /** Il tipo provvisorio, prima di META (che decide motion photo e animate). */
    fun provisionalKind(row: MediaStoreRow): MediaKind = when {
      row.type == RowType.VIDEO -> MediaKind.VIDEO
      row.mime?.lowercase(Locale.ROOT)?.let { it in RAW_MIMES } == true -> MediaKind.RAW_PHOTO
      else -> MediaKind.PHOTO
    }

    fun LinkDecision.isUndecided(): Boolean =
      this is LinkDecision.NeedsFullHash || (this is LinkDecision.Replaced && next is LinkDecision.NeedsFullHash)

    fun candidatesOf(decision: LinkDecision): List<ContentCandidate> = when (decision) {
      is LinkDecision.NeedsFullHash -> decision.candidates
      is LinkDecision.Replaced -> (decision.next as? LinkDecision.NeedsFullHash)?.candidates.orEmpty()
      else -> emptyList()
    }

    /** Il pavimento abbassa la generazione salvata; null (API 29, nessuna generazione) resta null. */
    fun lower(generation: Long?, floor: Long?): Long? = when {
      generation == null -> null
      floor == null -> generation
      else -> minOf(generation, floor)
    }

    fun minOfNullable(a: Long?, b: Long?): Long? = when {
      a == null -> b
      b == null -> a
      else -> minOf(a, b)
    }

    fun isDiskFull(e: Throwable): Boolean {
      var t: Throwable? = e
      while (t != null) {
        if (t is SQLiteFullException) return true
        t = t.cause
      }
      return false
    }
  }
}
