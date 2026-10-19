package dev.pampa.mycloudgallery.core.nasmedia.scan

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeVerdict
import dev.pampa.mycloudgallery.core.nasmedia.contract.KnownNasFile
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetadataApply
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMove
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanner
import dev.pampa.mycloudgallery.core.nasmedia.contract.OwnRemovals
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasSeen
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanSettings
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanWindow
import dev.pampa.mycloudgallery.core.nasmedia.detect.Detection
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.nasmedia.detect.ReadBudget
import dev.pampa.mycloudgallery.core.nasmedia.exclude.Exclusions
import dev.pampa.mycloudgallery.core.nasmedia.exclude.FileClass
import dev.pampa.mycloudgallery.core.nasmedia.exclude.MediaTypes
import dev.pampa.mycloudgallery.core.nasmedia.exclude.SystemNames
import dev.pampa.mycloudgallery.core.nasmedia.exclude.TemporaryNames
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeAcceptances
import dev.pampa.mycloudgallery.core.nasmedia.freeze.CursorKeeper
import dev.pampa.mycloudgallery.core.nasmedia.freeze.InMemoryFreezeAcceptances
import dev.pampa.mycloudgallery.core.nasmedia.freeze.MassChangeGuard
import dev.pampa.mycloudgallery.core.nasmedia.freeze.StoreFreezeState
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * La scansione di **una** share con l'account di questo dispositivo (docs/fase4-progetto.md §2.3; D9, D10, D11, D22).
 *
 * **Il giro.** Si elencano **tutte** le cartelle incluse, a pagine (E-N5), dalle piu' recenti (proposta 11: [FolderOrder],
 * per data di modifica se affidabile, altrimenti per nome con anni e mesi in ordine numerico decrescente), ognuna prima delle
 * sue sottocartelle: il cursore (`NasScanCursor.resumeAfter` = l'ultima cartella completata) permette di riprendere dopo un
 * riavvio o dopo la fine del tempo senza rifare le cartelle gia' fatte (E-P4). In ogni cartella i file piu' recenti si
 * trattano per primi. Per ogni file:
 *
 * 1. stesso posto, stessa dimensione e data del catalogo: **nessuna lettura**, solo "visto a" (D9);
 * 2. nuovo o cambiato ma ancora in arrivo (E-N4, ora del NAS): ignorato finche' e' stabile;
 * 3. nuovo, ma gia' noto altrove con lo stesso `fileId`, dimensione e data (rinominato o spostato dal PC, E-N1): la
 *    posizione cambia posto, **nessuna lettura**, identita' e preparazione restano;
 * 4. nuovo o cambiato con un record dell'indice dello stesso timbro: adottato senza leggere il file (D6, D7);
 * 5. altrimenti si **rileva**: `qh1` da testa e coda, metadati, miniatura EXIF (D17), registrazione nel catalogo.
 *
 * **La fine.** Solo un giro **completo** (arrivato in fondo, senza cartelle illeggibili) segna le assenze, e solo dopo il
 * giudizio del congelamento (D10): se spariti, cambiati o "non piu' media" arrivano alla soglia, non si segna nessuna
 * assenza e la share risulta congelata ([freezeState]). Una scansione non cancella ne' sposta nulla sul NAS (E-S11): cambia
 * solo lo stato del catalogo locale.
 *
 * Un `DefaultNasScanner` per share, uno solo alla volta (le chiamate di [scan] si mettono in fila).
 *
 * @param settings le esclusioni e il tetto di rilevamento, rilette a ogni giro (la configurazione firmata puo' cambiare).
 * @param clock **l'ora del NAS** (`SessionNasClock`): "visto a", stabilita' dei file, congelamento. Mai l'ora del telefono.
 * @param monotonicMs l'orologio dei tempi (budget), che non salta.
 */
class DefaultNasScanner(
  private val account: AccountId,
  private val share: String,
  private val reader: NasMediaReader,
  private val store: NasScanStore,
  private val detector: NasDetector,
  private val replica: ReplicaRecords,
  private val provisional: ProvisionalThumbnails,
  private val clock: NasClock,
  private val settings: () -> ScanSettings = { ScanSettings() },
  private val config: ScanConfig = ScanConfig(),
  private val arrivals: ArrivalWatch = ArrivalWatch(),
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  /** R8 (revisione 4B): cio' che l'app stessa ha tolto dalla share (diario locale): non conta come cambiamento anomalo. */
  private val ownRemovals: OwnRemovals = OwnRemovals.NONE,
  /** NA-4: dove si ricorda la franchigia di "Ho controllato, continua" fra un processo e l'altro. */
  acceptances: FreezeAcceptances = InMemoryFreezeAcceptances(),
) : NasScanner {

  private val keeper = CursorKeeper(store, account, share)

  /** Lo stato di congelamento di questa share: lo legge chi decide se scrivere sul NAS, la Libreria, la Diagnostica. */
  val freezeState: StoreFreezeState = StoreFreezeState(keeper, acceptances) { runCatching { clock.nowMillis() }.getOrNull() }

  private val statusFlow = MutableStateFlow<NasScanStatus>(NasScanStatus.Idle)
  override val status: StateFlow<NasScanStatus> = statusFlow.asStateFlow()

  private val runLock = Mutex()
  private var round: Round? = null

  /** L'opzione "Mostra le copertine degli album" del giro di prima (R9: solo per riconoscere il gesto dell'utente). */
  private var lastShowCovers: Boolean? = null

  /**
   * R9: l'utente ha appena spento "Mostra le copertine degli album": le copertine che escono fino alla fine del giro sono una
   * sua scelta (E-N14), non un cambiamento di massa del NAS, e non contano per il congelamento. Le altre copertine nuove (un
   * file audio comparso in una cartella di foto) contano.
   */
  private var coversHiddenByUser = false
  private var freezeLoaded = false
  private var lastEpoch = Long.MIN_VALUE

  /**
   * I file gia' riconosciuti come "non media" (E-M12) con il timbro con cui li abbiamo letti: finche' il timbro non cambia
   * non si rileggono a ogni giro (un `.jpg` che dentro e' testo costerebbe 128-192 KiB di lettura ogni volta). Solo del
   * processo e con un tetto: dopo un riavvio si rileggono una volta.
   */
  private val notMediaMemory = object : LinkedHashMap<LocationKey, NasStamp>(64, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocationKey, NasStamp>?): Boolean = size > MAX_NOT_MEDIA_MEMORY
  }

  override suspend fun scan(mode: ScanMode, budget: Duration): ScanReport = runLock.withLock {
    if (!freezeLoaded) {
      freezeState.load()
      freezeLoaded = true
    }
    Run(mode, budget).execute()
  }

  // --- Tipi interni ---------------------------------------------------------------------------------------------------

  /** Il giro completo in corso (puo' durare piu' tappe): i conteggi che servono al congelamento. */
  private class Round(val epoch: Long, val knownPresent: Int, val window: ScanWindow? = null, val first: Boolean = false) {
    var changed = 0
    var notMedia = 0
    var blocked = 0
    var suspect = false

    /**
     * Falso se il giro e' stato ripreso da un altro processo: l'ordine delle cartelle di prima non si conosce piu' e va
     * ricostruito dagli elenchi di adesso. Un giro cosi' non segna assenze (vedi `finishRound`).
     */
    var trusted = true

    /**
     * L'ordine in cui sono state visitate le sottocartelle di ogni cartella (nomi piegati), fissato la prima volta che la
     * cartella e' stata elencata in questo giro: la ripresa nello stesso processo usa questo e non l'ordine di adesso, che
     * puo' essere cambiato (una data di modifica che sale, una cartella nuova o rinominata).
     */
    val orders = HashMap<String, List<String>>()
  }

  /** Un file candidato (immagine o video) di una cartella. */
  private class Cand(val entry: NasEntry, val path: SharePath, val nas: NasPath, val key: LocationKey, val known: KnownNasFile?) {
    val name: String get() = entry.name
  }

  /** Interrompe il giro: il motivo e, se il NAS non risponde, il codice. Senza traccia dello stack (e' un segnale). */
  private class Stop(val reason: ScanStop, val code: String? = null) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
  }

  /** L'esito del rilevamento di un candidato dentro un lotto. */
  private sealed interface Probe {
    class Done(val cand: Cand, val detection: Detection) : Probe

    data class Busy(val cand: Cand) : Probe

    data class Gone(val cand: Cand) : Probe

    data class Failed(val cand: Cand, val error: NasError) : Probe

    data class Skipped(val cand: Cand) : Probe
  }

  // --- Un giro ---------------------------------------------------------------------------------------------------------

  private inner class Run(private val mode: ScanMode, budget: Duration) {
    private val deadline = monotonicMs() + budget.inWholeMilliseconds
    private val current = settings().also { s ->
      if (lastShowCovers == true && !s.showAlbumCovers) coversHiddenByUser = true
      lastShowCovers = s.showAlbumCovers
    }
    private val exclusions = Exclusions.build(current.excluded, emptyList(), current.showAlbumCovers)
    private val readBudget = ReadBudget(current.detectionByteCap)
    private val purpose = if (mode is ScanMode.Folder) Purpose.INTERACTIVE else Purpose.BACKGROUND
    private val isRound = mode !is ScanMode.Folder
    private val visited = HashSet<Long>()
    private val sem = Semaphore(config.detectConcurrency)

    private var nasNow = 0L
    private var seenAt = 0L
    private var resumed = false
    private lateinit var activeRound: Round

    // Conteggi del report.
    private var folders = 0
    private var files = 0
    private var unchanged = 0
    private var adopted = 0
    private var detected = 0
    private var arriving = 0
    private var skippedNames = 0
    private var skippedSystem = 0
    private var albumCovers = 0
    private var notMedia = 0
    private var busy = 0
    private var vanished = 0
    private var moved = 0
    private var changed = 0
    private var excludedFolders = 0
    private var blockedFolders = 0
    private var identityRefused = 0

    private var stoppedBy: ScanStop? = null
    private var reportedFreeze: Freeze? = null
    private var complete = false
    private var foldersSinceSave = 0
    private var pendingResume: NasPath? = null

    /** Un rilevamento ha trovato finito il tetto di lettura del giro: gli altri non aprono piu' file (D22). */
    @Volatile private var capSeen = false

    /** "Non piu' media" visti dal rilevamento nella cartella in corso (un file noto che ora non e' piu' un media). */
    private var folderNotMediaRead = 0

    suspend fun execute(): ScanReport {
      nasNow = clock.nowMillis()
      try {
        startRound()
        val reachedEnd = when (mode) {
          is ScanMode.Folder -> {
            visit(mode.path, chain = null, depth = mode.path.components.size)
            true
          }
          else -> {
            val resumeAfter = keeper.read().resumeAfter
            visit(null, chain = resumeAfter?.value?.split('/'), depth = 0)
            true
          }
        }
        flushCursor()
        if (!isRound) complete = true
        if (reachedEnd && isRound) finishRound()
      } catch (stop: Stop) {
        stoppedBy = stop.reason
        flushCursor()
        if (stop.reason == ScanStop.UNAVAILABLE) statusFlow.value = NasScanStatus.Unavailable(stop.code ?: "NAS_ERROR")
      } catch (e: CancellationException) {
        statusFlow.value = NasScanStatus.Idle
        throw e
      }
      val report = report()
      statusFlow.value = when {
        stoppedBy == ScanStop.UNAVAILABLE -> statusFlow.value
        reportedFreeze != null -> NasScanStatus.Frozen(reportedFreeze!!)
        else -> NasScanStatus.Finished(report)
      }
      GalleryLog.d(TAG) {
        "giro ${mode::class.simpleName}: cartelle=$folders file=$files invariati=$unchanged spostati=$moved adottati=$adopted " +
          "rilevati=$detected spariti=$vanished completo=$complete stop=$stoppedBy"
      }
      return report
    }

    private fun report() = ScanReport(
      mode = mode,
      folders = folders,
      files = files,
      unchanged = unchanged,
      adopted = adopted,
      detected = detected,
      arriving = arriving,
      skippedNames = skippedNames,
      skippedSystem = skippedSystem,
      albumCovers = albumCovers,
      notMedia = notMedia,
      busy = busy,
      vanished = vanished,
      bytesRead = readBudget.taken,
      complete = complete,
      resumed = resumed,
      stoppedBy = stoppedBy,
      freeze = reportedFreeze,
      moved = moved,
      changed = changed,
      excludedFolders = excludedFolders,
      blockedFolders = blockedFolders,
      identityRefused = identityRefused,
    )

    // --- Inizio e fine del giro ----------------------------------------------------------------------------------------

    private suspend fun startRound() {
      if (!isRound) {
        seenAt = nasNow
        activeRound = Round(nasNow, 0)
        return
      }
      val cursor = keeper.read()
      val epoch = cursor.epoch
      if (epoch == null) {
        // Un giro nuovo ha un'`epoch` strettamente maggiore di quella dell'ultimo (anche se l'ora del NAS e' tornata indietro
        // o due giri partono nello stesso millisecondo): i "visti a" del giro prima restano sempre piu' vecchi.
        val floor = maxOf(cursor.lastFullScanAt ?: Long.MIN_VALUE, lastEpoch)
        val fresh = keeper.update { it.copy(epoch = if (floor == Long.MIN_VALUE) nasNow else maxOf(nasNow, floor + 1), resumeAfter = null) }
        lastEpoch = checkNotNull(fresh.epoch)
        activeRound = Round(checkNotNull(fresh.epoch), store.countPresent(account, share), liveWindow(fresh.window), first = cursor.lastFullScanAt == null)
      } else {
        resumed = true
        val kept = round?.takeIf { it.epoch == epoch }
        activeRound = kept ?: Round(epoch, store.countPresent(account, share), liveWindow(cursor.window), first = cursor.lastFullScanAt == null)
          .also { it.trusted = false }
      }
      round = activeRound
      seenAt = activeRound.epoch
      statusFlow.value = scanning()
    }

    private suspend fun finishRound() {
      val r = activeRound
      coversHiddenByUser = false
      if (r.blocked > 0 || blockedFolders > 0) {
        // Cartelle non lette: non si puo' dire che un file sia sparito da dove non si e' guardato (nessuna assenza).
        keeper.update { it.copy(epoch = null, resumeAfter = null) }
        round = null
        complete = false
        return
      }
      val unseen = store.countUnseen(account, share, r.epoch)
      if (!r.trusted && unseen > 0) {
        // Giro ripreso da un altro processo: l'ordine delle cartelle e' stato ricostruito e una cartella cambiata nel frattempo
        // potrebbe essere stata saltata. Con file non rivisti non si segna nessuna assenza; il giro dopo, intero, decide.
        keeper.update { it.copy(epoch = null, resumeAfter = null) }
        round = null
        complete = false
        return
      }
      // R8: i file che l'app stessa ha tolto (cestino del NAS, spostamenti fuori dalla share), riconosciuti per `fileId` dal diario locale,
      // spariscono come tutti (le assenze si segnano) ma non sono "spariti" per il congelamento.
      val diff = ScanDiff(r.knownPresent, (unseen - ownRemovedUnseen(r.epoch)).coerceAtLeast(0), r.changed, r.notMedia)
      // R9 (revisione 4A): dopo il gesto valgono solo i conteggi che l'utente ha confermato (franchigia), non "tutto"; senza
      // gesto i conteggi dei giri accettati negli ultimi 7 giorni si sommano a questo (finestra), cosi' le gocce sotto la
      // soglia a ogni giro arrivano comunque alla soglia.
      val allowance = freezeState.acceptance()
      val window = if (allowance == null) r.window else null
      val judged = window?.let { w ->
        ScanDiff(maxOf(w.knownPresent, diff.knownPresent), w.vanished + diff.vanished, w.changed + diff.changed, w.notMedia + diff.notMedia)
      } ?: diff
      val verdict = MassChangeGuard.judge(judged, allowance)
      val nextWindow = if (allowance != null) {
        ScanWindow(
          r.epoch,
          diff.knownPresent,
          maxOf(0, diff.vanished - allowance.vanished),
          maxOf(0, diff.changed - allowance.changed),
          maxOf(0, diff.notMedia - allowance.notMedia),
        )
      } else {
        ScanWindow(window?.startNasMillis ?: r.epoch, judged.knownPresent, judged.vanished, judged.changed, judged.notMedia)
      }
      when (verdict) {
        is FreezeVerdict.Freeze -> {
          val before = keeper.read().freeze
          val freeze = Freeze(verdict.reason, before?.sinceNasMillis ?: nasNow, verdict.diff)
          keeper.update { it.copy(epoch = null, resumeAfter = null, lastFullScanAt = nasNow, freeze = freeze) }
          freezeState.publish(freeze)
          // NA-4: la franchigia del gesto di prima e' servita a questo giro; i conteggi di adesso sono un congelamento nuovo.
          freezeState.consumeAcceptance()
          reportedFreeze = freeze
          stoppedBy = ScanStop.FROZEN
          round = null
          complete = true
        }
        FreezeVerdict.Proceed -> {
          // Prima si toglie il congelamento (se c'era: la situazione e' tornata normale o l'utente l'ha accettata), perche' il
          // catalogo non segna assenze su una share congelata; poi le assenze, a tetti; poi si chiude il giro.
          keeper.update { it.copy(freeze = null) }
          freezeState.publish(null)
          markVanished(r.epoch)
          freezeState.consumeAcceptance()
          keeper.update { it.copy(epoch = null, resumeAfter = null, lastFullScanAt = nasNow, window = nextWindow) }
          round = null
          complete = true
        }
      }
    }

    /** R8: quante posizioni non viste in questo giro sono file che l'app stessa ha tolto dalla share (stesso `fileId` del diario). */
    private suspend fun ownRemovedUnseen(epoch: Long): Int {
      val ids = try {
        ownRemovals.fileIds(share)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return 0
      }
      if (ids.isEmpty()) return 0
      var count = 0
      for (chunk in ids.chunked(OWN_REMOVALS_CHUNK)) {
        count += store.findByFileId(account, share, chunk).values.count { known ->
          known.state != LocationState.ABSENT && (known.seenAt == null || known.seenAt < epoch)
        }
      }
      return count
    }

    /** R9: la finestra salvata, se e' ancora aperta (meno di 7 giorni di ora del NAS). */
    private fun liveWindow(window: ScanWindow?): ScanWindow? =
      window?.takeIf { nasNow - it.startNasMillis in 0 until MassChangeRule.WINDOW_MILLIS }

    /** Le assenze, a tetti (§2.3): si ripete finche' il catalogo ne segna quante il tetto; il tempo si controlla a ogni giro. */
    private suspend fun markVanished(epoch: Long) {
      while (true) {
        checkBudget()
        val n = store.markVanishedInShare(account, share, epoch, config.vanishCap)
        vanished += n
        if (n < config.vanishCap) return
      }
    }

    // --- L'albero ------------------------------------------------------------------------------------------------------

    /**
     * Visita [dir] (`null` = radice). Con [chain] `null` la cartella e' da fare; con una catena (il percorso dell'ultima
     * cartella completata, dopo un riavvio) le cartelle lungo la catena hanno gia' fatto i loro file: se ne rilegge solo
     * l'elenco delle sottocartelle, si saltano quelle gia' fatte e si continua da quella della catena.
     *
     * Le sottocartelle vanno dalla piu' recente ([FolderOrder]). "Gia' fatta" vuol dire: viene prima di quella della catena
     * nell'ordine **di quando il giro e' cominciato** (la fotografia in [Round.orders], se questo processo l'ha fatta); senza
     * fotografia (giro ripreso da un altro processo) solo se la cartella non e' stata toccata dopo l'inizio del giro. Una
     * cartella nuova, rinominata o toccata dopo si visita sempre: rifare una cartella gia' fatta costa solo un elenco.
     */
    private suspend fun visit(dir: SharePath?, chain: List<String>?, depth: Int) {
      checkBudget()
      val subs = if (chain == null) processFolder(dir, depth) else listSubfolders(dir, depth)
      val (ordered, exact) = orderChildren(dir, subs)
      val next = chain?.firstOrNull()
      val nextIndex = if (next == null) -1 else ordered.indexOfFirst { foldName(it.name) == foldName(next) }
      for ((index, sub) in ordered.withIndex()) {
        val childChain: List<String>? = when {
          chain == null || next == null || nextIndex < 0 -> null // anche se la cartella della catena e' sparita: si rifa' tutto
          index < nextIndex -> if (exact || sub.modifiedAt < activeRound.epoch) continue else null
          index == nextIndex -> chain.drop(1)
          else -> null
        }
        if (sub.fileId != null && !visited.add(sub.fileId)) {
          skippedSystem++
          continue
        }
        visit(sub.path, childChain, depth + 1)
      }
    }

    /** Le sottocartelle nell'ordine di visita, e se questo e' l'ordine fotografato all'inizio del giro. */
    private fun orderChildren(dir: SharePath?, subs: List<ScanFolder>): Pair<List<ScanFolder>, Boolean> {
      val computed = FolderOrder.order(subs, if (isRound) activeRound.epoch else nasNow)
      if (!isRound) return computed to false
      val key = dirKey(dir)
      val snapshot = activeRound.orders[key]
      if (snapshot == null) {
        activeRound.orders[key] = computed.map { foldName(it.name) }
        return computed to false
      }
      val position = HashMap<String, Int>(snapshot.size * 2)
      snapshot.forEachIndexed { i, name -> position[name] = i }
      val old = computed.filter { foldName(it.name) in position }.sortedBy { position.getValue(foldName(it.name)) }
      val added = computed.filter { foldName(it.name) !in position }
      return (old + added) to true
    }

    private fun dirKey(dir: SharePath?): String = dir?.components?.joinToString("/") { foldName(it) } ?: ""

    /** Solo le sottocartelle di [dir] (i suoi file li ha gia' fatti il giro che si riprende). */
    private suspend fun listSubfolders(dir: SharePath?, depth: Int): List<ScanFolder> {
      val subs = ArrayList<ScanFolder>()
      val outcome = reader.list(share, dir, purpose, config.pageSize) { page ->
        for (e in page) if (e.isDirectory) classifyDirectory(e, dir, depth, subs)
      }
      handleListFailure(outcome, dir)
      return subs
    }

    private fun classifyDirectory(e: NasEntry, parent: SharePath?, depth: Int, out: MutableList<ScanFolder>, excludedHere: MutableList<NasPath>? = null) {
      val name = e.name
      if (SystemNames.isSystemFolder(name) || e.isHidden || e.isSystem) {
        skippedSystem++
        return
      }
      if (e.isReparsePoint || depth >= config.maxDepth) {
        // Collegamenti mai seguiti (E-N10), e nessuna profondita' oltre 64.
        skippedSystem++
        return
      }
      val path = try {
        if (parent == null) SharePath.of(name) else parent.child(name)
      } catch (_: Exception) {
        skippedNames++
        return
      }
      if (exclusions.excludes(path)) {
        excludedFolders++
        NasPath.parseOrNull(path.value)?.let { excludedHere?.add(it) }
        return
      }
      out += ScanFolder(name, path, e.fileId, e.modifiedAt)
    }

    // --- Una cartella ----------------------------------------------------------------------------------------------------

    private suspend fun processFolder(dir: SharePath?, depth: Int): List<ScanFolder> {
      checkBudget()
      folders++
      val dirNas = dir?.let { NasPath.parseOrNull(it.value) }
      if (dir != null && dirNas == null) {
        skippedNames++
        return emptyList()
      }
      folderNotMediaRead = 0
      val known = store.knownIn(account, share, dirNas)

      val subs = ArrayList<ScanFolder>()
      val excludedHere = ArrayList<NasPath>()
      val media = ArrayList<NasEntry>()
      val nonMedia = ArrayList<NasEntry>()
      var hasAudio = false
      val outcome = reader.list(share, dir, purpose, config.pageSize) { page ->
        for (e in page) {
          if (e.isDirectory) {
            classifyDirectory(e, dir, depth, subs, excludedHere)
            continue
          }
          val name = e.name
          if (SystemNames.isSystemFile(name) || e.isHidden || e.isSystem) {
            skippedSystem++
            continue
          }
          if (TemporaryNames.isArrivingMedia(name)) {
            arriving++
            continue
          }
          when (MediaTypes.classOf(name)) {
            FileClass.IMAGE, FileClass.VIDEO -> media += e
            FileClass.AUDIO -> hasAudio = true
            null -> if (known.isNotEmpty()) nonMedia += e
          }
        }
      }
      if (!handleListFailure(outcome, dir)) return emptyList()

      // Candidati: nomi validi per SharePath (E-N6), senza doppioni a meno delle maiuscole (E-N7), senza copertine (D11).
      val cands = ArrayList<Cand>(media.size)
      val keys = HashSet<LocationKey>(media.size * 2)
      val coverKeys = ArrayList<LocationKey>()
      val coverSeen = ArrayList<NasSeen>()
      for (e in media) {
        val path = try {
          if (dir == null) SharePath.of(e.name) else dir.child(e.name)
        } catch (_: Exception) {
          skippedNames++
          continue
        }
        val nas = NasPath.parseOrNull(path.value)
        if (nas == null) {
          skippedNames++
          continue
        }
        val key = Location.Nas(account, share, nas).key
        if (exclusions.isCover(e, hasAudio)) {
          albumCovers++
          known[key]?.let { k ->
            coverKeys += key
            coverSeen += NasSeen(Location.Nas(account, share, nas, k.stamp.fileId), k.stamp)
          }
          continue
        }
        if (!keys.add(key)) {
          skippedNames++
          continue
        }
        cands += Cand(e, path, nas, key, known[key])
      }

      // Classificazione: invariati, cambiati, nuovi, in arrivo.
      val unchangedList = ArrayList<Cand>()
      val touchList = ArrayList<Cand>()
      val resolveList = ArrayList<Cand>()
      val adoptedList = ArrayList<Cand>()
      var folderChanged = 0
      for (c in cands) {
        files++
        val k = c.known
        val stamp = c.entry.stamp
        if (k != null && k.stamp.size == stamp.size && k.stamp.modifiedAtMillis == stamp.modifiedAtMillis) {
          if (k.adoptedQuickHash != null) adoptedList += c else unchangedList += c
          continue
        }
        if (k != null) {
          folderChanged++
          changed++
        }
        if (arrivals.isStable(c.key, c.name, stamp, nasNow)) {
          resolveList += c
        } else {
          arriving++
          if (k != null) touchList += c
        }
      }

      // R6 (revisione 4A): un file invariato la cui impronta era stata adottata da un record dell'indice resta tale solo se un
      // record valido con la stessa impronta lo sostiene ancora; se no (autore revocato o dimenticato, record cambiato) si
      // rileva qui. Non e' un "cambiato" (il file non e' cambiato): non conta per il congelamento.
      val redetect = if (adoptedList.isEmpty()) emptyList() else unsupportedAdoptions(adoptedList, unchangedList)

      // "Non piu' media" (D10): file noti di questa cartella che non ci sono piu' e hanno un nome non-media con lo stesso
      // fileId o lo stesso nome piu' un'estensione in coda (`foto.jpg.locked`). R9: anche i file noti che da questo elenco
      // risultano copertine (un file audio comparso nella cartella) escono dalla Libreria: contano come "non piu' media".
      val folderNotMedia = if (isRound) notMediaTransitions(known, keys, coverKeys, nonMedia) + (if (coversHiddenByUser) 0 else coverKeys.size) else 0

      if (isRound) {
        val r = activeRound
        val allowance = freezeState.acceptance()
        val w = if (allowance == null) r.window else null
        val threshold = MassChangeRule.effectiveThreshold(maxOf(r.knownPresent, w?.knownPresent ?: 0))
        val changedSoFar = (w?.changed ?: 0) + r.changed + folderChanged - (allowance?.changed ?: 0)
        val notMediaSoFar = (w?.notMedia ?: 0) + r.notMedia + folderNotMedia - (allowance?.notMedia ?: 0)
        if (changedSoFar >= threshold || notMediaSoFar >= threshold) r.suspect = true
      }

      // R9: niente esce dalla Libreria da una share sospetta o congelata (il catalogo lo rifiuta comunque, D10). Le copertine
      // che restano sono state viste adesso: non contano anche come sparite.
      if (!(isRound && activeRound.suspect) && freezeState.frozen.value == null) {
        if (excludedHere.isNotEmpty() || coverKeys.isNotEmpty()) {
          store.retireExcluded(account, share, excludedHere, coverKeys)
        }
      } else if (coverSeen.isNotEmpty()) {
        store.confirmAllPresent(coverSeen, seenAt, null)
      }

      // Scritture: sempre "visto a" per i file noti; la registrazione di cose nuove si ferma se la share e' sospetta.
      resolveList += confirm(unchangedList, touchList).filter { arrivals.isStable(it.key, it.name, it.entry.stamp, nasNow) }
      if (redetect.isNotEmpty()) {
        // Visti adesso con il timbro noto (non spariti), poi riletti qui se la share non e' sospetta.
        confirmKnownStamp(redetect)
        if (!(isRound && activeRound.suspect)) detect(dir, redetect)
      }
      if (!(isRound && activeRound.suspect)) {
        resolve(dir, resolveList)
      } else {
        // Sospetta: si guarda e si conta, ma i file cambiati non si registrano (non si "assorbe" il danno) e quelli noti
        // si tengono vivi, cosi' le assenze del giro restano quelle vere.
        val keep = resolveList.filter { it.known != null }
        confirmKnownStamp(keep)
      }

      if (isRound) {
        activeRound.changed += folderChanged
        activeRound.notMedia += folderNotMedia + folderNotMediaRead
      }
      markFolderDone(dirNas)
      statusFlow.value = scanning()
      return subs
    }

    /** Quanti file noti della cartella sono diventati "non media" in questo elenco (rinominati con un'estensione nuova). */
    private fun notMediaTransitions(
      known: Map<LocationKey, KnownNasFile>,
      seen: Set<LocationKey>,
      coverKeys: List<LocationKey>,
      nonMedia: List<NasEntry>,
    ): Int {
      if (known.isEmpty() || nonMedia.isEmpty()) return 0
      val unseen = known.keys.filter { it !in seen && it !in coverKeys }
      if (unseen.isEmpty()) return 0
      val ids = HashSet<Long>()
      val bases = HashSet<String>()
      for (e in nonMedia) {
        e.fileId?.let { ids += it }
        var base = foldName(e.name)
        var guard = 0
        while (guard++ < 4) {
          val dot = base.lastIndexOf('.')
          if (dot <= 0) break
          base = base.substring(0, dot)
          bases += base
        }
      }
      var count = 0
      for (key in unseen) {
        val k = known.getValue(key)
        val name = key.value.substringAfterLast('/')
        if ((k.stamp.fileId != null && k.stamp.fileId in ids) || name in bases) count++
      }
      return count
    }

    private suspend fun markFolderDone(dirNas: NasPath?) {
      if (!isRound) return
      if (dirNas != null) pendingResume = dirNas
      foldersSinceSave++
      if (foldersSinceSave >= config.cursorSaveEvery) flushCursor()
    }

    private suspend fun flushCursor() {
      val resume = pendingResume ?: return
      keeper.update { c -> if (c.epoch != null) c.copy(resumeAfter = resume) else c }
      pendingResume = null
      foldersSinceSave = 0
    }

    // --- Scritture sul catalogo ------------------------------------------------------------------------------------------

    private fun locationOf(c: Cand, fileId: Long? = c.entry.fileId) = Location.Nas(account, share, c.nas, fileId)

    /**
     * I file invariati e quelli noti che non si possono trattare adesso (in arrivo): solo "visto a", nessuna lettura (D9).
     * Restituisce gli invariati che il catalogo ha rifiutato (il posto e' cambiato nel frattempo): vanno rivisti.
     */
    private suspend fun confirm(unchangedList: List<Cand>, touchList: List<Cand>): List<Cand> {
      val rejected = ArrayList<Cand>()
      for (chunk in unchangedList.chunked(config.batchSize)) {
        // Il timbro porta dimensione e data; il fileId letto adesso sta nella posizione. Un fileId diverso con la stessa
        // dimensione e la stessa data (NAS riavviato) non e' un file cambiato: la conferma vale e lo aggiorna.
        val seen = chunk.map { c -> NasSeen(locationOf(c), NasStamp(c.entry.size, c.entry.modifiedAt, null)) }
        val ok = store.confirmAllPresent(seen, seenAt, null)
        chunk.forEachIndexed { i, c -> if (ok[i]) unchanged++ else rejected += c }
      }
      confirmKnownStamp(touchList)
      return rejected
    }

    private suspend fun confirmKnownStamp(list: List<Cand>) {
      for (chunk in list.chunked(config.batchSize)) {
        val seen = chunk.mapNotNull { c -> c.known?.let { k -> NasSeen(locationOf(c, k.stamp.fileId), k.stamp) } }
        if (seen.isNotEmpty()) store.confirmAllPresent(seen, seenAt, null)
      }
    }

    /** Nuovi e cambiati: spostati, adottati dall'indice o rilevati, dai piu' recenti. */
    private suspend fun resolve(dir: SharePath?, resolveList: List<Cand>) {
      if (resolveList.isEmpty()) return
      val pending = resolveList.sortedWith(compareByDescending<Cand> { it.entry.modifiedAt }.thenBy { it.key.value }).toMutableList()
      // Zero byte, o gia' letto e risultato "non media" con questo stesso timbro (E-M12): non serve aprirlo.
      val settled = pending.filter { it.entry.size == 0L || isRememberedNotMedia(it) }
      if (settled.isNotEmpty()) {
        pending.removeAll(settled.toSet())
        notMedia += settled.size
        folderNotMediaRead += settled.count { it.known != null }
      }
      relocate(pending)
      adopt(pending)
      detect(dir, pending)
    }

    /**
     * R6: dei file invariati con l'impronta adottata, quelli che un record valido (stesso timbro, stessa impronta) sostiene
     * ancora tornano fra gli invariati ([unchanged]); gli altri si restituiscono, da rilevare qui.
     */
    private suspend fun unsupportedAdoptions(adopted: List<Cand>, unchanged: MutableList<Cand>): List<Cand> {
      val out = ArrayList<Cand>()
      for (chunk in adopted.chunked(config.batchSize)) {
        val stamps = LinkedHashMap<SharePath, NasStamp>(chunk.size * 2)
        chunk.forEach { stamps[it.path] = it.entry.stamp }
        val records = replica.matching(share, stamps)
        for (c in chunk) {
          val file = records[c.path]?.file
          if (file != null && file.stamp.sameAs(c.entry.stamp) && file.quickHash == c.known?.adoptedQuickHash) unchanged += c else out += c
        }
      }
      return out
    }

    // --- E-N1: spostati senza leggere ---------------------------------------------------------------------------------

    private fun isRememberedNotMedia(c: Cand): Boolean = synchronized(notMediaMemory) {
      notMediaMemory[c.key]?.let { it.size == c.entry.size && it.modifiedAtMillis == c.entry.modifiedAt } == true
    }

    private suspend fun relocate(pending: MutableList<Cand>) {
      if (activeRound.knownPresent == 0 && isRound) return
      val withId = pending.filter { it.known == null && it.entry.fileId != null }
      if (withId.isEmpty()) return
      val done = HashSet<Cand>()
      for (chunk in withId.chunked(config.batchSize)) {
        val found = store.findByFileId(account, share, chunk.map { checkNotNull(it.entry.fileId) })
        val usedLocations = HashSet<LocationId>()
        val moves = ArrayList<NasMove>()
        val movedCands = ArrayList<Cand>()
        for (c in chunk) {
          val f = found[c.entry.fileId] ?: continue
          val e = c.entry
          if (f.location.key == c.key) continue
          if (f.stamp.size != e.size || f.stamp.modifiedAtMillis != e.modifiedAt || f.stamp.fileId != e.fileId) continue
          // Il vecchio posto e' ancora al suo posto in questo giro: non e' uno spostamento (due percorsi, un solo file).
          if (f.seenAt != null && f.seenAt >= seenAt && isRound) continue
          if (!usedLocations.add(f.locationId)) continue
          moves += NasMove(f.locationId, locationOf(c), e.stamp)
          movedCands += c
        }
        if (moves.isEmpty()) continue
        val ok = store.relocateAll(moves, seenAt, null)
        ok.forEachIndexed { i, success ->
          if (success) {
            moved++
            done += movedCands[i]
            arrivals.forget(movedCands[i].key)
          }
        }
      }
      pending.removeAll(done)
    }

    // --- D6, D7: adottati dall'indice senza leggere ----------------------------------------------------------------------

    private suspend fun adopt(pending: MutableList<Cand>) {
      if (pending.isEmpty()) return
      val done = HashSet<Cand>()
      for (chunk in pending.chunked(config.batchSize)) {
        val stamps = LinkedHashMap<SharePath, NasStamp>(chunk.size * 2)
        chunk.forEach { stamps[it.path] = it.entry.stamp }
        val records = replica.matching(share, stamps)
        if (records.isEmpty()) continue
        val hits = ArrayList<Pair<Cand, MetaFile>>()
        for (c in chunk) {
          val file = records[c.path]?.file ?: continue
          // Un record vale solo con il timbro dell'ultimo elenco (D6, E-N12): la replica lo garantisce, qui si ricontrolla.
          if (!file.stamp.sameAs(c.entry.stamp)) continue
          hits += c to file
        }
        if (hits.isEmpty()) continue
        val observations = hits.map { (c, file) ->
          // L'hash completo dell'indice non si usa: puo' venire dall'indice solo dopo un qh1 letto qui (D8).
          Observation(
            location = locationOf(c),
            stamp = c.entry.stamp.toFileStamp(),
            fingerprint = Fingerprint(c.entry.size, file.quickHash),
            kind = file.kind,
            source = file.source,
            captureTimeHintMillis = file.capture?.takeIf { !it.isEstimated }?.utcMillis,
          )
        }
        // R6: l'impronta e' del record, non letta qui: niente legami per contenuto e posizione segnata come adottata.
        val outcomes = store.recordAll(observations, MediaCatalog.WhenUndecided.KEEP_SEPARATE, null, seenAt, fingerprintsAdopted = true)
        val metas = ArrayList<NasMetadataApply>()
        hits.forEachIndexed { i, (c, file) ->
          val outcome = outcomes[i]
          val mediaId = outcome.mediaId
          val locationId = outcome.locationId
          if (mediaId != null && locationId != null) {
            metas += NasMetadataApply(mediaId, locationId, NasMetaResult.Read(metadataOf(file), file.kind))
            if (!store.adoptIdentity(mediaId, file.mediaId, file.versionId)) identityRefused++
          }
          adopted++
          done += c
          arrivals.forget(c.key)
        }
        if (metas.isNotEmpty()) store.applyMetadata(metas)
      }
      pending.removeAll(done)
    }

    // --- Rilevamento -------------------------------------------------------------------------------------------------------

    private suspend fun detect(dir: SharePath?, pending: List<Cand>) {
      if (pending.isEmpty()) return
      val folder = dir?.value
      for (chunk in pending.chunked(config.batchSize)) {
        checkBudget()
        if (capSeen || readBudget.remainingBelow(MIN_DETECTION_BYTES)) throw Stop(ScanStop.BYTE_CAP)
        val probes = coroutineScope {
          chunk.map { c -> async { sem.withPermit { probe(c, folder) } } }.awaitAll()
        }
        val outcome = record(probes)
        statusFlow.value = scanning()
        if (outcome != null) throw outcome
      }
    }

    private suspend fun probe(c: Cand, folder: String?): Probe {
      // Il tetto del giro e' finito (o non basta piu' per un rilevamento): non si apre nemmeno il file.
      if (capSeen || readBudget.remainingBelow(MIN_DETECTION_BYTES)) return Probe.Skipped(c)
      val opened = reader.open(share, c.path, purpose)
      val file = when (opened) {
        is NasResult.Ok -> opened.value
        is NasResult.Err -> return when (opened.error) {
          NasError.FileBusy, NasError.PermissionDenied -> Probe.Busy(c)
          NasError.NotFound -> Probe.Gone(c)
          else -> Probe.Failed(c, opened.error)
        }
      }
      return try {
        val detection = detector.detect(file, c.name, folder, c.entry.modifiedAt, readBudget)
        if (detection is Detection.CapReached) capSeen = true
        Probe.Done(c, detection)
      } finally {
        file.close()
      }
    }

    /** Registra i risultati di un lotto (in ordine) e restituisce il motivo per fermarsi, se c'e'. */
    private suspend fun record(probes: List<Probe>): Stop? {
      val observations = ArrayList<Observation>()
      val sources = ArrayList<Pair<Cand, Detection.Media>>()
      var stop: Stop? = null
      for (p in probes) {
        when (p) {
          is Probe.Skipped -> stop = stop ?: Stop(ScanStop.BYTE_CAP)
          is Probe.Busy -> {
            busy++
            keepAlive(p.cand)
          }
          is Probe.Gone -> Unit
          is Probe.Failed -> stop = stop ?: Stop(ScanStop.UNAVAILABLE, scanFailureCode(p.error))
          is Probe.Done -> when (val d = p.detection) {
            is Detection.Media -> {
              detected++
              val c = p.cand
              if (!arrivals.isStable(c.key, c.name, d.stamp, nasNow)) {
                // Cambiato fra l'elenco e l'apertura: ancora in scrittura.
                arriving++
                keepAlive(c)
              } else {
                observations += observationOf(c, d)
                sources += c to d
              }
            }
            is Detection.NotMedia -> {
              detected++
              notMedia++
              if (p.cand.known != null) folderNotMediaRead++
              synchronized(notMediaMemory) { notMediaMemory[p.cand.key] = p.cand.entry.stamp }
            }
            is Detection.Unreadable -> {
              detected++
              busy++
              keepAlive(p.cand)
            }
            is Detection.CapReached -> {
              detected++
              stop = stop ?: Stop(ScanStop.BYTE_CAP)
            }
          }
        }
      }
      if (observations.isNotEmpty()) {
        val outcomes = store.recordAll(observations, MediaCatalog.WhenUndecided.KEEP_SEPARATE, null, seenAt)
        val metas = ArrayList<NasMetadataApply>()
        sources.forEachIndexed { i, (c, d) ->
          val outcome = outcomes[i]
          val mediaId = outcome.mediaId
          val locationId = outcome.locationId
          if (mediaId != null && locationId != null) {
            d.meta?.let { metas += NasMetadataApply(mediaId, locationId, it) }
          }
          d.provisionalJpeg?.let { jpeg -> provisional.put(ContentKey.of(d.fingerprint), jpeg) }
          arrivals.forget(c.key)
        }
        if (metas.isNotEmpty()) store.applyMetadata(metas)
      }
      return stop
    }

    private fun observationOf(c: Cand, d: Detection.Media): Observation {
      val kind = (d.meta as? NasMetaResult.Read)?.kind ?: MediaTypes.kindOf(c.name) ?: MediaKind.PHOTO
      val mime = (d.meta as? NasMetaResult.Read)?.metadata?.mimeType ?: MediaTypes.mimeOf(c.name)
      val relative = c.path.parent()?.value?.let { "$it/" } ?: ""
      val source = SourceClassifier.classify("nas", relative, c.name, null, mime).source
      val hint = (d.meta as? NasMetaResult.Read)?.metadata?.captureTime?.takeIf { !it.isEstimated }?.utcMillis
      return Observation(
        location = locationOf(c, d.stamp.fileId ?: c.entry.fileId),
        stamp = d.stamp.toFileStamp(),
        fingerprint = d.fingerprint,
        kind = kind,
        source = source,
        captureTimeHintMillis = hint,
      )
    }

    /** Un file noto che non si puo' trattare adesso (occupato, in scrittura): resta vivo con il suo timbro di prima. */
    private suspend fun keepAlive(c: Cand) {
      val k = c.known ?: return
      store.confirmAllPresent(listOf(NasSeen(locationOf(c, k.stamp.fileId), k.stamp)), seenAt, null)
    }

    // --- Errori, tempo, stato ----------------------------------------------------------------------------------------------

    /**
     * Vero se l'elenco e' andato bene (o la cartella non c'e' piu'). Un errore del NAS ferma il giro; un permesso negato su
     * una sottocartella la salta e impedisce di segnare assenze (E-N9 non c'entra: qui e' una cartella che non si legge).
     */
    private suspend fun handleListFailure(outcome: NasResult<ListOutcome>, dir: SharePath?): Boolean {
      when (outcome) {
        is NasResult.Ok -> {
          if (outcome.value.cappedAt != null) {
            blockedFolders++
            if (isRound) activeRound.blocked++
          }
          return true
        }
        is NasResult.Err -> when (val error = outcome.error) {
          NasError.NotFound -> {
            if (dir == null) throw Stop(ScanStop.UNAVAILABLE, "SHARE_MISSING")
            return false
          }
          NasError.PermissionDenied -> {
            if (dir == null) {
              store.markShareUnavailable(account, share)
              throw Stop(ScanStop.UNAVAILABLE, scanFailureCode(error))
            }
            blockedFolders++
            if (isRound) activeRound.blocked++
            return false
          }
          is NasError.ShareMissing -> {
            store.markShareUnavailable(account, share)
            throw Stop(ScanStop.UNAVAILABLE, scanFailureCode(error))
          }
          else -> throw Stop(ScanStop.UNAVAILABLE, scanFailureCode(error))
        }
      }
    }

    private fun checkBudget() {
      if (monotonicMs() >= deadline) throw Stop(ScanStop.BUDGET)
    }

    private fun scanning(): NasScanStatus.Scanning {
      val total = activeRound.knownPresent.takeIf { isRound && it > 0 }
      return NasScanStatus.Scanning(mode, folders, files, detected, total, first = activeRound.first)
    }
  }

  private companion object {
    const val TAG = "NasScan"
    const val MAX_NOT_MEDIA_MEMORY = 50_000

    /** R8: i `fileId` tolti dall'app si cercano nel catalogo a blocchi (una query per blocco). */
    const val OWN_REMOVALS_CHUNK = 500

    /** Testa (64 KiB) piu' coda (fino a 128 KiB): sotto questo, il tetto del giro non basta per rilevare un altro file. */
    const val MIN_DETECTION_BYTES = 192L * 1024
  }
}
