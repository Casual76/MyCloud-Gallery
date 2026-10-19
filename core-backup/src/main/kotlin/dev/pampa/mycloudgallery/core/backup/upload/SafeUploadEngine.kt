package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Il **motore vero** del caricamento sicuro (Fase 5A, P2; docs/fase5-progetto.md §2.2, D3-D6, D10, D11, D15, D32, D33): la macchina di
 * [UploadStateMachine] sopra le primitive in sola aggiunta ([NasBackupOps]), la lettura del NAS ([NasMediaReader]), il telefono
 * ([PhoneBytes]), il diario ([BackupStore]) e il catalogo ([BackupCatalog]). Le stesse regole di sicurezza dell'esecutore di riferimento
 * (`InMemoryBackupEngine`), che il banco delle interruzioni prova su entrambi:
 *
 * - **il diario si scrive prima di ogni effetto**: il temporaneo `<io>-bk-<id>` e il punto di ripresa 0 prima di `openTemp`, il punto
 *   di ripresa solo dopo che lo scrittore ha confermato la lunghezza **sullo stesso handle** (e dopo il registro del traffico), il nome
 *   finale prima di `publishTemp`, "da capo" prima di togliere un temporaneo che si rifa'; ogni passo ripetuto e' idempotente;
 * - **hash e preparazione nella stessa lettura** dei byte del telefono (D10), con il timbro uguale prima e dopo; la deduplica (D8) passa
 *   dalla porta [ExistingCopyFinder] dopo la lettura e prima del temporaneo;
 * - **nessun file visibile non verificato** (D4): rilettura completa del temporaneo dal NAS (`readAll`, 4 flussi) prima del nome
 *   definitivo; dopo la pubblicazione basta il timbro (dimensione, data, `fileId`), un timbro diverso = rilettura completa; tre verifiche
 *   non tornate = `FAILED` con `VERIFY_FAILED` (E-B18);
 * - **mai una sostituzione** (D2, D6, E-B3): `publishTemp` non sostituisce; nome occupato da un file identico = collegato, da uno diverso
 *   = "nome (2)" scritto nel diario prima di riprovare; la risposta persa si riconosce come in F4-D24;
 * - **ripresa** (D5, E-B1): un punto ogni 64 MiB, con l'identita' del temporaneo letta sull'handle dello scrittore; dopo un'interruzione
 *   `reopenTemp(keep = punto)` riapre il proprio temporaneo senza crearlo e lo accorcia **solo se e' ancora lui** (R3 della revisione 5A:
 *   stessa identita', un nome solo, `tmp/` vera); altrimenti niente si tocca e l'elemento ricomincia da capo con un temporaneo nuovo;
 * - **file del telefono cambiato** (E-B15, L38, decisione dell'orchestratore del 16/10): prima che il temporaneo sia completo, la
 *   versione non c'e' piu' e il temporaneo si toglie; a temporaneo **completo** non si scarta niente: la verifica decide, e se la
 *   rilettura da' lo SHA-256 dei byte letti **prima** del cambiamento quei byte (l'originale, che sul telefono non c'e' piu') si
 *   pubblicano come copia di **quella** versione. Una foto ancora in memoria dalla lettura (fino a 64 MiB, con la preparazione) si
 *   carica dalla memoria: il telefono non serve piu' (anche se nel frattempo la foto e' stata riscritta o cancellata);
 * - **due file in volo** (D11): uno in caricamento ([Round.uploadLane]), l'altro in lettura, preparazione o verifica ([Round.readLane]);
 *   lettura e preparazione a priorita' bassa con l'app in primo piano ([foreground]);
 * - un errore del NAS ferma il giro, non l'elemento; un file aperto dal PC e' un'attesa crescente (E-N13); `DISK_FULL` a meta' toglie il
 *   temporaneo e ferma il giro con `NAS_FULL` o `QUOTA` (E-R9); il cancello si controlla prima di ogni passo (D32);
 * - si pubblica **solo** dentro le radici ammesse ([UploadConfig.allowedRoots], `Backup/`), mai un temporaneo che non sia `<io>-bk-`.
 *
 * - **un errore inatteso di un elemento non ferma gli altri** (R1 della revisione 5A, difesa in profondita'): l'elemento si parcheggia
 *   (`FAILED` con [BackupProblemCode.UNEXPECTED], il media esce dai candidati finche' l'utente non tocca "Riprova") e il giro continua;
 *   un errore del giro intero (il diario che non risponde) chiude lo stato del motore e risale;
 * - **un rifiuto del telefono per una sola riga** (`NOT_SELECTED`, "foto selezionate") fa aspettare quell'elemento, non il giro (R5);
 *   il giro si ferma solo per i rifiuti globali (permessi, posizione delle foto);
 * - **la fine del file del telefono** si controlla dopo la lettura (R6): un file piu' lungo del MediaStore e' cambiato (E-B15), mai un
 *   prefisso "al sicuro";
 * - **l'elemento di un'altra share aspetta** (R7): se l'area privata cambia a motore costruito, niente si scrive nella share di prima;
 * - **"Proteggi subito le foto nuove" guida solo le foto nuove** (BK-1, D34): un giro `QUICK_NEW_PHOTOS` porta avanti solo gli elementi che ha
 *   creato lui, non l'arretrato gia' nel diario (un primo backup a meta' avrebbe fatto passare la foto appena scattata dopo centinaia di vecchie);
 * - **la copia appena pubblicata e poi spostata da un'altra via non si rifa'** (F2, lo schema del guardiano): se al collegamento il file non e' piu'
 *   al suo nome, prima di ricaricare si cerca nell'area privata una copia con gli stessi byte (la deduplica D8, riletta qui per intero) e ci si
 *   collega; senza, si ricarica come sempre.
 *
 * Non cattura mai `Error` (la "morte del processo" dei test passa) ne' l'annullamento: solo gli errori di I/O della lettura del telefono
 * e, per un elemento alla volta, le eccezioni inattese (vedi sopra). I log dicono solo numeri e codici (§4.3): mai nomi, percorsi, share,
 * id o impronte.
 */
class SafeUploadEngine(
  private val deviceId: String,
  private val share: String,
  private val ops: NasBackupOps,
  private val reader: NasMediaReader,
  private val store: BackupStore,
  private val catalog: BackupCatalog,
  private val phone: PhoneBytes,
  private val gate: BackupGate,
  private val clock: BackupClock,
  private val planner: BackupPlanner,
  private val folder: () -> DeviceFolder?,
  private val existing: ExistingCopyFinder,
  private val facts: UploadFacts = UploadFacts.UNKNOWN,
  private val indexing: UploadIndexing = UploadIndexing.NONE,
  private val config: UploadConfig = UploadConfig(),
  /** L'app e' in primo piano: lettura e preparazione dei byte del telefono girano a priorita' bassa (§4.4, riga P2). */
  private val foreground: () -> Boolean = { false },
  /** Dove gira la lettura con l'app in primo piano (di default un thread a priorita' minima). */
  private val lowPriority: () -> CoroutineContext = { LowPriority.dispatcher },
  /** Le attese crescenti di un file aperto dal PC (E-N13): nei test l'orologio finto. */
  private val sleep: suspend (Long) -> Unit = { delay(it) },
) : StagedBackupEngine {

  init {
    require(ops.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  private val state = MutableStateFlow<BackupStatus>(BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)))

  override val status: StateFlow<BackupStatus> = state

  @Volatile private var stopRequested: StopBy? = null

  /** Le uscite della preparazione fra la lettura del telefono e il collegamento (D10), per elemento; poche e con un tetto. */
  private val preparedCache = object : LinkedHashMap<Long, PreparedUpload>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PreparedUpload>?): Boolean =
      size > MAX_PREPARED || values.sumOf { it.bytesInMemory } > MAX_PREPARED_BYTES
  }

  /**
   * I byte del telefono letti per l'hash e ancora in memoria (D10: foto fino a 64 MiB con la preparazione), per elemento, fino alla
   * verifica: il caricamento li usa al posto di una seconda lettura del telefono (E-B15). Al piu' due, come i file in volo.
   */
  private class Raw(val sha256: Sha256, val bytes: ByteArray)

  private val rawCache = object : LinkedHashMap<Long, Raw>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Raw>?): Boolean = size > MAX_RAW
  }

  /** L'esito di un passo. */
  private sealed interface Step {
    /** Si va avanti con l'elemento riscritto (eventualmente terminale). */
    class Next(val item: BackupItem) : Step

    /** L'elemento aspetta: [retryAfterMs] null = il prossimo giro; altrimenti si riprova in questo giro dopo l'attesa (E-N13). */
    class Wait(val retryAfterMs: Long? = null) : Step

    /** Il giro si ferma. */
    class Stop(val stop: RunStop, val problem: BackupProblemCode? = null) : Step
  }

  private class Halt(val stop: RunStop, val problem: BackupProblemCode?)

  // --- I giri --------------------------------------------------------------------------------------------------------------------

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult =
    run(kind, budget, trigger, UploadControl.FREE)

  override suspend fun resumeAll(budget: Duration): BackupRunResult = resumeAll(budget, UploadControl.FREE)

  override suspend fun stop(by: StopBy) {
    stopRequested = by
  }

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger, control: UploadControl): BackupRunResult {
    require(trigger != BackupTrigger.INCOMING) { "I caricamenti in arrivo sono della 5B." }
    stopRequested = null
    val round = Round(kind, trigger, control, clock.monotonicMs() + budget.inWholeMilliseconds)
    val gateState = gate.stateOf(share)
    if (!gateState.open) return finish(round, null, haltFor(gateState))
    val device = folder()
    if (device == null) {
      state.value = BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER))
      return BackupRunResult.nothing(RunStop.GATE)
    }
    return closingOnError {
      val created = plan(kind, trigger, device)
      // BK-1 (D34): "Proteggi subito" porta al sicuro la foto nuova, non l'arretrato che il diario ha gia' aperto (ripresa per `item_id`: le vecchie
      // verrebbero per prime). L'arretrato lo riprendono i giri normali, con le regole del preset.
      drive(round, only = if (trigger == BackupTrigger.QUICK_NEW_PHOTOS) created else null)
    }
  }

  override suspend fun resumeAll(budget: Duration, control: UploadControl): BackupRunResult {
    stopRequested = null
    return closingOnError { drive(Round(null, BackupTrigger.SCHEDULED, control, clock.monotonicMs() + budget.inWholeMilliseconds)) }
  }

  /**
   * Un errore che nemmeno il parcheggio di un elemento ha potuto assorbire (il diario o il piano che non rispondono): lo stato del motore
   * non resta "in corso" per sempre, diventa [BackupProblemCode.UNEXPECTED] e l'eccezione risale a chi guida il lavoro (`:app` salva il
   * problema e non riprogramma all'infinito). L'annullamento e `Error` passano come sono.
   */
  private suspend fun <T> closingOnError(block: suspend () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.e(TAG) { "giro interrotto da un errore inatteso (${e::class.java.simpleName})" }
    state.value = BackupStatus.Problem(BackupProblemCode.UNEXPECTED, 0)
    throw e
  }

  /** Il cancello chiuso come fermata del giro: la pausa dell'utente (R4) e' un "Ferma", ogni altra ragione e' del cancello. */
  private fun haltFor(gateState: BackupGateState): Halt =
    if (gateState == BackupGateState.PAUSED) Halt(RunStop.STOPPED, null) else Halt(RunStop.GATE, gateState.problem)

  /**
   * I candidati nuovi del piano diventano elementi `PLANNED` (D3): solo dati locali, solo destinazioni dentro le radici ammesse. Il piano
   * riceve il trigger del giro (D34: con `QUICK_NEW_PHOTOS` solo le foto nuove della fotocamera).
   */
  private suspend fun plan(kind: BackupKind, trigger: BackupTrigger, device: DeviceFolder): Set<BackupItemId> {
    val fresh = planner.next(kind, config.planPage, trigger).filter { store.openFor(it.versionId) == null }
    if (fresh.isEmpty()) return emptySet()
    var refused = 0
    val items = fresh.mapNotNull { c ->
      val destination = try {
        planner.destination(c, device)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // R1: un media la cui destinazione non si costruisce non ferma il piano degli altri: porta il segno e il piano lo salta.
        GalleryLog.e(TAG) { "destinazione non costruita (${e::class.java.simpleName}): media fermo con ${BackupProblemCode.UNEXPECTED.code}" }
        try {
          catalog.markProblem(c.mediaId, BackupProblemCode.UNEXPECTED)
        } catch (marking: CancellationException) {
          throw marking
        } catch (_: Exception) {
          GalleryLog.w(TAG) { "segno del problema non scritto" }
        }
        return@mapNotNull null
      }
      if (!config.allows(destination)) {
        refused++
        return@mapNotNull null
      }
      NewBackupItem(
        kind = UploadKind.NEW_COPY, mediaId = c.mediaId, versionId = c.versionId, size = c.size, phone = c.phone, expected = c.stamp,
        share = share, planned = destination, quickHash = c.quickHash,
      )
    }
    if (refused > 0) GalleryLog.w(TAG) { "destinazioni fuori dalle radici ammesse scartate: $refused" }
    return if (items.isNotEmpty()) store.create(items).mapTo(HashSet()) { it.id } else emptySet()
  }

  /**
   * Il lavoro di un giro: gli elementi aperti del diario, al piu' [UploadConfig.parallel] in volo. Con [only] solo quelli (BK-1: gli elementi
   * creati da questo giro di "Proteggi subito le foto nuove"), gli altri restano aperti per i giri normali.
   */
  private suspend fun drive(round: Round, only: Set<BackupItemId>? = null): BackupRunResult {
    val open = store.itemsToResume(RESUME_LIMIT)
      .filter { it.kind == UploadKind.NEW_COPY || (config.driveGuardCopies && it.kind == UploadKind.GUARD_COPY) }
      .filter { only == null || it.id in only }
    val ofKind = if (round.kind == null) open else open.filter { item -> round.describe(item)?.let { BackupKind.of(it.kind) == round.kind } ?: true }
    // R7: l'area privata puo' cambiare a motore costruito (l'admin corregge la configurazione). L'elemento di un'altra share aspetta: niente
    // si scrive nella share di prima, ma non ferma neppure gli elementi della share di adesso.
    val (mine, foreign) = ofKind.partition { it.share.equals(share, ignoreCase = true) }
    if (foreign.isNotEmpty()) GalleryLog.w(TAG) { "elementi di un'altra area: ${foreign.size} aspettano (la share non e' piu' quella del motore)" }
    val queue = WorkQueue(round, mine)
    repeat(foreign.size) { queue.park() }
    round.publishRunning(queue)
    coroutineScope {
      repeat(config.parallel) { launch { worker(round, queue) } }
    }
    return finish(round, queue, round.halt.get())
  }

  private fun finish(round: Round, queue: WorkQueue?, halt: Halt?): BackupRunResult {
    val waiting = queue?.waiting() ?: 0
    val stop = halt?.stop ?: RunStop.COMPLETE
    state.value = when {
      halt?.problem != null -> BackupStatus.Problem(halt.problem, 0)
      stop == RunStop.STOPPED -> BackupStatus.Stopped(stopRequested ?: StopBy.USER)
      waiting > 0 -> BackupStatus.Waiting(waiting, 0, WaitReason.NEXT_RUN)
      else -> BackupStatus.UpToDate(round.done.get() + round.linked.get(), clock.nasNowMs())
    }
    return BackupRunResult(
      done = round.done.get(),
      linked = round.linked.get(),
      gone = round.gone.get(),
      failed = round.failed.get(),
      waiting = waiting,
      bytesUploaded = round.uploaded.get(),
      bytesReread = round.reread.get(),
      stoppedBy = stop,
      problem = halt?.problem,
    )
  }

  private suspend fun worker(round: Round, queue: WorkQueue) {
    while (true) {
      if (!round.mayContinue()) return
      val taken = queue.take() ?: return
      val item = store.item(taken.id)?.takeIf { !it.state.terminal } ?: continue
      val gateState = gate.stateOf(item.share)
      if (!gateState.open) {
        round.stopForGate(gateState)
        queue.park()
        return
      }
      driveItem(round, queue, item)
      round.publishRunning(queue)
    }
  }

  /** Porta avanti un elemento finche' non e' terminale, aspetta o ferma il giro. */
  private suspend fun driveItem(round: Round, queue: WorkQueue, start: BackupItem) {
    var item = start
    var steps = 0
    while (true) {
      val step = try {
        advance(round, item)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // R1: un errore inatteso di questo elemento non ferma il giro degli altri.
        parkOnError(round, item, e)
        return
      }
      when (step) {
        is Step.Next -> {
          item = step.item
          if (item.state.terminal) {
            dropRaw(item)
            round.count(item.state)
            return
          }
        }
        is Step.Wait -> {
          queue.defer(item, step.retryAfterMs)
          return
        }
        is Step.Stop -> {
          round.stopWith(step.stop, step.problem)
          queue.park()
          return
        }
      }
      // Difesa contro un ciclo che non avanza: l'elemento aspetta il prossimo giro (nessun effetto in piu').
      if (++steps >= config.maxStepsPerItem) {
        GalleryLog.w(TAG) { "elemento fermo dopo $steps passi nello stesso giro" }
        queue.park()
        return
      }
      // Fra un passo e l'altro: "Ferma", tempo, regole, cancello (D32: si ferma al passo successivo, nessun file a meta').
      if (!round.mayContinue()) {
        queue.park()
        return
      }
      val gateState = gate.stateOf(item.share)
      if (!gateState.open) {
        round.stopForGate(gateState)
        queue.park()
        return
      }
      // L'altro file in volo va avanti (D11).
      yield()
    }
  }

  /**
   * R1 (difesa in profondita'): un'eccezione inattesa di un solo elemento. L'elemento si **parcheggia**: `FAILED` con
   * [BackupProblemCode.UNEXPECTED] e il segno sul media (che esce dai candidati finche' l'utente non tocca "Riprova"); non torna per
   * primo a ogni giro (il diario riprende solo gli elementi aperti) e il giro continua con gli altri. Il proprio temporaneo, se c'e', si
   * toglie senza insistere (comunque la pulizia di D33 lo toglie dopo un giorno). Se nemmeno il diario riesce a scrivere il parcheggio
   * l'errore non e' piu' di un elemento: risale e il giro intero si chiude ([closingOnError]).
   */
  private suspend fun parkOnError(round: Round, item: BackupItem, error: Exception) {
    GalleryLog.e(TAG) { "errore inatteso (${error::class.java.simpleName}): elemento fermo con ${BackupProblemCode.UNEXPECTED.code}" }
    val stored = store.item(item.id)
    if (stored != null && stored.state.terminal) {
      // L'elemento era gia' arrivato a un'uscita: niente da parcheggiare.
      round.count(stored.state)
      return
    }
    val fresh = stored ?: item
    val temp = fresh.temp
    if (temp != null && fresh.state in TEMP_STATES) {
      try {
        ops.deleteOwnTemp(fresh.share, temp, config.purpose)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        GalleryLog.w(TAG) { "temporaneo non tolto dopo l'errore: lo togliera' la pulizia" }
      }
    }
    try {
      catalog.markProblem(fresh.mediaId, BackupProblemCode.UNEXPECTED)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      GalleryLog.w(TAG) { "segno del problema non scritto" }
    }
    dropRaw(fresh)
    store.update(fresh.copy(state = UploadState.FAILED, problem = BackupProblemCode.UNEXPECTED))
    round.count(UploadState.FAILED)
  }

  private suspend fun advance(round: Round, item: BackupItem): Step = when (item.kind) {
    UploadKind.NEW_COPY -> when (item.state) {
      UploadState.PLANNED -> readPhone(round, item)
      UploadState.HASHED -> afterHash(round, item)
      UploadState.UPLOADING -> upload(round, item)
      UploadState.UPLOADED -> verify(round, item, back = UploadState.UPLOADING)
      UploadState.VERIFIED -> toPublishing(round, item)
      UploadState.PUBLISHING -> publish(round, item, back = UploadState.UPLOADING)
      UploadState.PUBLISHED -> link(round, item, back = UploadState.UPLOADING)
      UploadState.LINKED -> finishLinked(round, item)
      else -> error("Stato ${item.state.code} fuori dal cammino della copia nuova.")
    }
    UploadKind.GUARD_COPY -> when (item.state) {
      UploadState.PLANNED -> save(item.copy(state = UploadState.COPYING, temp = tempOf(item), resumeAt = 0))
      UploadState.COPYING -> guardCopy(round, item)
      UploadState.COPIED -> verify(round, item, back = UploadState.COPYING)
      UploadState.VERIFIED -> toPublishing(round, item)
      UploadState.PUBLISHING -> publish(round, item, back = UploadState.COPYING)
      UploadState.PUBLISHED -> link(round, item, back = UploadState.COPYING)
      UploadState.LINKED -> save(item.copy(state = UploadState.DONE))
      else -> error("Stato ${item.state.code} fuori dal cammino del guardiano.")
    }
    // La 5B non si esegue qui (il diario della 5A non li accetta comunque).
    UploadKind.VERSION, UploadKind.SWAP, UploadKind.INCOMING -> Step.Wait()
  }

  private suspend fun saveItem(item: BackupItem): BackupItem {
    store.update(item)
    return item
  }

  private suspend fun save(item: BackupItem): Step = Step.Next(saveItem(item))

  private fun tempOf(item: BackupItem): SharePath = NasBackupPaths.tempFor(deviceId, item.id.value)

  // --- PLANNED: lettura completa dal telefono, hash e preparazione dagli stessi byte (D10) ------------------------------------------

  private class PhoneRead(val sha256: Sha256, val quickHash: QuickHash, val prepared: PreparedUpload?, val memory: ByteArray?, val endsAtSize: Boolean)

  private suspend fun readPhone(round: Round, item: BackupItem): Step {
    val ref = item.phone ?: return Step.Wait()
    val expected = item.expected ?: return Step.Wait()
    // E-M12: un file vuoto non e' un media da salvare (la lettura del telefono non lo mette nemmeno nel catalogo).
    if (item.size == 0L) return unreadable(round, item)
    val description = round.describe(item)
    val file = when (val opened = phone.open(ref)) {
      PhoneOpen.Stale -> return refreshPhone(round, item)
      PhoneOpen.Gone -> return gone(round, item, lost = true)
      is PhoneOpen.Denied -> return denied(opened.reason)
      is PhoneOpen.Opened -> opened.file
    }
    file.use {
      // La versione e' cambiata prima della lettura (Samsung "Salva", E-B15): non e' piu' sul telefono, la nuova ha il suo elemento.
      if (file.stamp != expected) return gone(round, item, lost = false)
      val read = round.readLane.withPermit { withContext(readContext()) { readAndPrepare(item, file, description) } }
        ?: return afterPhoneFailure(round, item)
      // R6: il file continua oltre la dimensione del MediaStore (rimasto indietro): i byte letti sono un prefisso, non la versione. Come un
      // file riscritto (E-B15): la versione non si salva da qui, quella nuova avra' il suo elemento quando il MediaStore l'avra' vista.
      if (!read.endsAtSize) return gone(round, item, lost = false)
      // Il timbro uguale prima e dopo la lettura (§2.2): un file riscritto a meta' non e' la versione.
      when (val after = phone.lookup(ref)) {
        is PhoneLookup.Present -> if (after.stamp != expected) return gone(round, item, lost = false)
        PhoneLookup.Gone -> return gone(round, item, lost = true)
        PhoneLookup.Stale -> return refreshPhone(round, item)
        is PhoneLookup.Denied -> return denied(after.reason)
      }
      when (catalog.recordFullHash(item.mediaId, item.versionId, read.sha256)) {
        HashRecord.RECORDED, HashRecord.SAME -> Unit
        // I byte letti non sono quelli della versione (o la versione non c'e' piu'): questa versione non si salva da qui.
        HashRecord.CONFLICT, HashRecord.UNKNOWN_VERSION -> return gone(round, item, lost = false)
      }
      read.prepared?.let { keepPrepared(item, it) }
      val hashed = save(item.copy(state = UploadState.HASHED, sha256 = read.sha256, quickHash = read.quickHash))
      read.memory?.let { keepRaw(item, Raw(read.sha256, it)) }
      return hashed
    }
  }

  /** Una lettura e, con la porta dell'indice, la preparazione dagli stessi byte; null se la lettura non e' riuscita. */
  private suspend fun readAndPrepare(item: BackupItem, file: PhoneFile, description: UploadDescription?): PhoneRead? {
    val prepare = indexing.enabled && description != null
    val read = try {
      PhoneReading.read(file.bytes, item.size, config.chunkBytes, keepInMemory = prepare && item.size <= config.inMemoryMaxBytes)
    } catch (_: IOException) {
      null
    } ?: return null
    var prepared: PreparedUpload? = null
    // Un file che continua oltre la dimensione del MediaStore (R6) non e' la versione: niente si prepara dai suoi byte.
    if (prepare && read.endsAtSize) {
      val subject = UploadSubject(item.mediaId, item.versionId, item.size, read.sha256, read.quickHash, description)
      prepared = indexing.prepare(subject, read.memory?.let { InMemoryMediaBytes(it) } ?: file.bytes)
    }
    return PhoneRead(read.sha256, read.quickHash, prepared, read.memory, read.endsAtSize)
  }

  private fun readContext(): CoroutineContext = if (foreground()) lowPriority() else EmptyCoroutineContext

  /**
   * La versione del MediaStore e' cambiata (E-P12): l'`_ID` dell'elemento non vale piu' e la riga si chiede al catalogo. Riga nuova = si
   * riprende da li'; la stessa riga (catalogo in ritardo) o la lettura del telefono non ancora finita = si aspetta, mai un `GONE` in
   * anticipo; assente dopo una rilettura completa = `GONE` (F1, docs/prova-500.md §B6), "persa prima del backup" solo se il media non e'
   * piu' da nessuna parte (un file riscritto ha la sua versione nuova, E-B15, e il segno toglierebbe il media dal piano).
   */
  private suspend fun refreshPhone(round: Round, item: BackupItem): Step {
    val stale = item.phone ?: return Step.Wait()
    return when (val now = catalog.phoneRowOf(item.mediaId, item.versionId, stale)) {
      is PhoneRowNow.At -> if (now.ref != stale) save(item.copy(phone = now.ref)) else Step.Wait()
      PhoneRowNow.Rereading -> Step.Wait()
      PhoneRowNow.Absent -> gone(round, item, lost = catalog.mediaSafety(item.mediaId)?.status == SafetyStatus.LOST)
    }
  }

  /** Una lettura del telefono e' fallita a meta': si guarda la riga e si decide (E-B4, E-B15, D15). */
  private suspend fun afterPhoneFailure(round: Round, item: BackupItem): Step {
    val ref = item.phone ?: return Step.Wait()
    return when (val now = phone.lookup(ref)) {
      PhoneLookup.Gone -> gone(round, item, lost = true)
      is PhoneLookup.Present -> when {
        now.stamp != item.expected -> gone(round, item, lost = false)
        round.readFailed(item) >= config.phoneReadRetries -> unreadable(round, item)
        else -> Step.Next(item)
      }
      PhoneLookup.Stale -> refreshPhone(round, item)
      is PhoneLookup.Denied -> denied(now.reason)
    }
  }

  /**
   * Il telefono nega i byte (R5). `NOT_SELECTED` ("foto selezionate", E-P1) riguarda **una riga sola**: quell'elemento aspetta (il diario
   * tiene le righe che non vede piu', il piano le propone ancora) e le foto scelte vanno avanti. I rifiuti globali (i permessi dei media,
   * la posizione delle foto) fermano il giro: nessun altro elemento andrebbe meglio.
   */
  private fun denied(reason: PhoneDenied): Step {
    if (reason == PhoneDenied.NOT_SELECTED) {
      GalleryLog.d(TAG) { "riga non fra le foto selezionate: l'elemento aspetta" }
      return Step.Wait()
    }
    return Step.Stop(RunStop.PHONE, reason.problem)
  }

  /** `GONE` (E-B4, E-B15): prima il proprio temporaneo incompleto (citato dal diario finche' l'elemento e' aperto), poi l'uscita. */
  private suspend fun gone(round: Round, item: BackupItem, lost: Boolean): Step {
    dropTempIfAny(round, item)?.let { return it }
    if (lost) catalog.markProblem(item.mediaId, BackupProblemCode.LOST_BEFORE_BACKUP)
    return save(item.copy(state = UploadState.GONE, problem = if (lost) BackupProblemCode.LOST_BEFORE_BACKUP else null))
  }

  /** D15: un file che non si legge (o vuoto, E-M12): mai caricato, mai "al sicuro", segno del problema sul media. */
  private suspend fun unreadable(round: Round, item: BackupItem): Step {
    dropTempIfAny(round, item)?.let { return it }
    catalog.markProblem(item.mediaId, BackupProblemCode.UNREADABLE)
    GalleryLog.w(TAG) { "file del telefono illeggibile: elemento fermo con ${BackupProblemCode.UNREADABLE.code}" }
    return save(item.copy(state = UploadState.FAILED, problem = BackupProblemCode.UNREADABLE))
  }

  // --- HASHED: deduplica (D8) o inizio del caricamento ------------------------------------------------------------------------

  private suspend fun afterHash(round: Round, item: BackupItem): Step {
    val sha = item.sha256 ?: return Step.Wait()
    when (val found = round.readLane.withPermit { findExisting(round, item, sha) }) {
      is ExistingCopyResult.Found -> {
        val copy = found.copy
        // Solo una copia di questo media e versione nell'area privata del proprietario, con la dimensione giusta: altrimenti si carica.
        if (sameCopy(item, copy)) {
          if (!attach(copy)) return Step.Wait()
          return save(item.copy(state = UploadState.LINKED_EXISTING, finalPath = copy.path, finalStamp = copy.stamp))
        }
        GalleryLog.w(TAG) { "copia proposta dalla deduplica scartata: si carica" }
      }
      ExistingCopyResult.None -> Unit
      is ExistingCopyResult.Interrupted -> return Step.Stop(RunStop.NAS, found.problem ?: BackupProblemCode.NAS_UNREACHABLE)
    }
    // §2.2 "Spazio": prima di un file oltre 1 GB lo spazio deve bastare con 1 GiB di margine (tocca i dischi: solo qui).
    if (item.size > config.spaceCheckBytes) {
      when (val space = ops.space(item.share, config.purpose)) {
        is NasResult.Err -> return stopFor(round, item, space.error)
        is NasResult.Ok -> if (space.value.callerFreeBytes < item.size + config.spaceMarginBytes) {
          return Step.Stop(RunStop.NAS, if (space.value.quotaLimited) BackupProblemCode.QUOTA else BackupProblemCode.NAS_FULL)
        }
      }
    }
    return save(item.copy(state = UploadState.UPLOADING, temp = tempOf(item), resumeAt = 0))
  }

  /** La copia proposta e' di questo media e di questa versione, nell'area privata dell'elemento, con la sua dimensione (altrimenti non e' sua). */
  private fun sameCopy(item: BackupItem, copy: VerifiedCopy): Boolean =
    copy.share.equals(item.share, ignoreCase = true) && copy.mediaId == item.mediaId && copy.versionId == item.versionId && copy.stamp.size == item.size

  /**
   * **F2 (lo schema del guardiano di P3)**: la copia appena pubblicata non e' piu' al suo nome (o ci e' un altro file) e il collegamento non e'
   * ancora avvenuto. Un'altra strada puo' averla spostata nel frattempo (una versione che la porta in `Versioni/`, "Cartelle NAS", il PC): rifarla
   * lascerebbe due copie degli stessi byte nell'area privata. Prima di ricaricare si cerca allora una copia con gli stessi byte
   * ([ExistingCopyFinder], la deduplica D8: **riletta qui per intero**, mai un hash o un timbro dell'indice): se c'e', la si collega dov'e' (`LINKED`:
   * da `PUBLISHED` e' il passo successivo del cammino, il nome finale diventa il suo); altrimenti si ricarica come prima ([redo]).
   * "Al sicuro" nasce sempre da una rilettura fatta qui (F4-D8): l'aggancio al catalogo (`attach`) viene prima del diario, come in [link].
   */
  private suspend fun movedOrRedo(round: Round, item: BackupItem, redo: BackupItem): Step {
    val sha = item.sha256
    if (sha != null) {
      when (val found = round.readLane.withPermit { findExisting(round, item, sha) }) {
        is ExistingCopyResult.Found -> {
          val copy = found.copy
          if (sameCopy(item, copy)) {
            if (!attach(copy)) return Step.Wait()
            GalleryLog.d(TAG) { "la copia pubblicata e' stata spostata: collegata dov'e'" }
            return save(item.copy(state = UploadState.LINKED, finalPath = copy.path, finalStamp = copy.stamp))
          }
        }
        ExistingCopyResult.None -> Unit
        is ExistingCopyResult.Interrupted -> return Step.Stop(RunStop.NAS, found.problem ?: BackupProblemCode.NAS_UNREACHABLE)
      }
    }
    return save(redo)
  }

  /** La porta della deduplica (P3) con il candidato ricostruito dal diario: dimensione, `qh1` letto qui, media e versione. */
  private suspend fun findExisting(round: Round, item: BackupItem, sha: Sha256): ExistingCopyResult {
    val ref = item.phone ?: return ExistingCopyResult.None
    val stamp = item.expected ?: return ExistingCopyResult.None
    val candidate = BackupCandidate(
      mediaId = item.mediaId, versionId = item.versionId, phone = ref, stamp = stamp, size = item.size,
      kind = round.describe(item)?.kind ?: MediaKind.PHOTO, source = BackupSource.Camera, localDay = null, dateEstimated = false,
      fileName = item.planned.name, sortTime = 0L, quickHash = item.quickHash,
    )
    return existing.find(candidate, sha, item.share)
  }

  // --- UPLOADING: scrittura del temporaneo con il punto di ripresa (D5) ----------------------------------------------------------

  /** Come e' finita la scrittura (lo scrittore e' gia' chiuso quando si decide: un handle esclusivo non lascerebbe togliere il file). */
  private sealed interface Pumped {
    class Done(val item: BackupItem) : Pumped

    class Then(val step: Step) : Pumped

    class PhoneFailed(val item: BackupItem) : Pumped

    class DiskFull(val item: BackupItem, val error: NasError) : Pumped
  }

  private suspend fun upload(round: Round, item: BackupItem): Step {
    if (item.verifyAttempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) return verifyFailed(round, item)
    val temp = item.temp ?: return save(item.copy(temp = tempOf(item), resumeAt = 0))
    // E-B15, D10: i byte letti per l'hash sono ancora in memoria: si caricano quelli (sono la versione, qualunque cosa sia successa al
    // file del telefono nel frattempo).
    rawOf(item)?.let { raw ->
      return afterPump(round, round.uploadLane.withPermit { writeTemp(round, item, temp, InMemoryMediaBytes(raw.bytes)) })
    }
    val ref = item.phone ?: return Step.Wait()
    val file = when (val opened = phone.open(ref)) {
      PhoneOpen.Stale -> return refreshPhone(round, item)
      PhoneOpen.Gone -> return gone(round, item, lost = true)
      is PhoneOpen.Denied -> return denied(opened.reason)
      is PhoneOpen.Opened -> opened.file
    }
    val pumped = file.use {
      // D5, E-B15: il file del telefono e' cambiato prima che il temporaneo fosse completo: si ricomincia da zero, con l'elemento della
      // versione nuova (questi byte non sono piu' la versione). Anche se e' piu' lungo di cio' che dice il MediaStore (R6).
      if (file.stamp != item.expected || file.bytes.size != item.size) return gone(round, item, lost = false)
      round.uploadLane.withPermit { writeTemp(round, item, temp, file.bytes) }
    }
    return afterPump(round, pumped)
  }

  private suspend fun afterPump(round: Round, pumped: Pumped): Step = when (pumped) {
    is Pumped.Then -> pumped.step
    is Pumped.PhoneFailed -> afterPhoneFailure(round, pumped.item)
    is Pumped.DiskFull -> diskFull(round, pumped.item, pumped.error)
    // Temporaneo completo: non si scarta piu' niente, nemmeno se il file del telefono e' cambiato dopo la lettura (E-B15, L38). Decide
    // la verifica: byte misti = rilettura diversa = si rifa' (e il telefono cambiato chiude con GONE); byte giusti = l'originale e' salvo.
    is Pumped.Done -> save(pumped.item.copy(state = UploadState.UPLOADED, resumeAt = pumped.item.size))
  }

  private suspend fun writeTemp(round: Round, start: BackupItem, temp: SharePath, source: MediaBytes): Pumped {
    val writer: BackupTempWriter
    val from: Long
    if (start.resumeAt > 0) {
      // R3 (revisione 5A): senza l'identita' del temporaneo al punto di ripresa non si accorcia niente: da capo.
      val tempId = start.resumeId ?: return Pumped.Then(save(start.copy(resumeAt = 0)))
      when (val r = ops.reopenTemp(start.share, temp, start.resumeAt, tempId, config.purpose)) {
        is NasResult.Err -> return Pumped.Then(
          // Un temporaneo che non si puo' riprendere (il server non accorcia, non e' un file): "da capo" nel diario, mai un errore che
          // si ripete a ogni giro. Gli errori di rete fermano il giro come sempre.
          if (r.error is NasError.Unexpected) save(start.copy(resumeAt = 0)) else stopFor(round, start, r.error),
        )
        is NasResult.Ok -> when (val o = r.value) {
          is ReopenOutcome.Reopened -> {
            writer = o.writer
            from = start.resumeAt
          }
          // Assente o piu' corto del punto salvato: da capo ("da capo" nel diario prima di tutto).
          ReopenOutcome.Missing, is ReopenOutcome.TooShort -> return Pumped.Then(save(start.copy(resumeAt = 0)))
          ReopenOutcome.Locked -> return Pumped.Then(lockedWait(round, start))
          // R3: al nome del temporaneo c'e' un altro file (un collegamento, un'altra identita') e niente e' stato toccato. Da capo: il passo
          // dopo trova il nome occupato e lo toglie con deleteOwnTemp, che toglie solo il NOME (un collegamento fisico lascia il file al suo
          // altro nome, uno simbolico la sua destinazione; un punto di rianalisi non si toglie), poi crea un temporaneo nuovo.
          ReopenOutcome.Foreign -> {
            GalleryLog.w(TAG) { "al nome del temporaneo c'e' un altro file: la ripresa ricomincia da capo" }
            return Pumped.Then(save(start.copy(resumeAt = 0, resumeId = null)))
          }
        }
      }
    } else {
      when (val r = ops.openTemp(start.share, temp, config.purpose)) {
        is NasResult.Ok -> {
          writer = r.value
          from = 0
        }
        is NasResult.Err -> {
          if (r.error != NasError.Exists) return Pumped.Then(stopFor(round, start, r.error))
          // Il diario dice "da capo": il temporaneo rimasto (senza punto di ripresa) e' solo nostro e si toglie, poi si ricrea.
          return Pumped.Then(dropTemp(round, start, temp) ?: Step.Next(start))
        }
      }
    }
    return writer.use { pump(round, start, writer, from, source) }
  }

  /** Le scritture da 1 MiB e i punti di ripresa ogni 64 MiB (D5); traffico registrato prima di ogni punto (§2.2 "Traffico"). */
  private suspend fun pump(round: Round, start: BackupItem, writer: BackupTempWriter, from: Long, source: MediaBytes): Pumped {
    var item = start
    var offset = from
    var lastPoint = from
    var unrecorded = 0L
    val buffer = config.chunkBytes.toLong()
    try {
      while (offset < item.size) {
        if (!round.mayContinue()) {
          // "Ferma", fine della tappa o regole: un punto di ripresa dove si e' arrivati (se lo scrittore lo conferma), poi si esce.
          if (offset > lastPoint) {
            val cp = writer.checkpoint()
            if (cp is NasResult.Ok && cp.value.length == offset) {
              round.traffic(unrecorded)
              unrecorded = 0
              item = saveItem(item.copy(resumeAt = offset, resumeId = cp.value.fileId))
            }
          }
          return Pumped.Then(Step.Wait())
        }
        val n = minOf(buffer, item.size - offset).toInt()
        val bytes = try {
          source.read(offset until offset + n)
        } catch (_: IOException) {
          return Pumped.PhoneFailed(item)
        }
        if (bytes.size != n) return Pumped.PhoneFailed(item)
        when (val w = writer.write(offset, bytes, 0, n)) {
          is NasResult.Err -> return failedWrite(round, item, w.error)
          is NasResult.Ok -> Unit
        }
        round.uploaded.addAndGet(n.toLong())
        unrecorded += n
        offset += n
        if (offset - lastPoint >= config.checkpointBytes && offset < item.size) {
          when (val cp = writer.checkpoint()) {
            is NasResult.Err -> return failedWrite(round, item, cp.error)
            is NasResult.Ok -> {
              // Prima il registro del traffico, poi il punto di ripresa (StagedRun): mai il contrario.
              round.traffic(unrecorded)
              unrecorded = 0
              if (cp.value.length != offset) return Pumped.Then(save(item.copy(resumeAt = 0)))
              // Con il punto, l'identita' letta sullo stesso handle (R3): la riapertura accorcera' solo questo file.
              item = saveItem(item.copy(resumeAt = offset, resumeId = cp.value.fileId))
              lastPoint = offset
            }
          }
        }
      }
      return when (val f = writer.finish(item.size)) {
        is NasResult.Err -> failedWrite(round, item, f.error)
        is NasResult.Ok -> when (f.value) {
          is TempFinish.Complete -> Pumped.Done(item)
          is TempFinish.SizeMismatch -> Pumped.Then(save(item.copy(resumeAt = 0)))
        }
      }
    } finally {
      // Il traffico gia' passato si conta anche se la scrittura si e' interrotta (non durante un annullamento: il processo se ne va).
      if (unrecorded > 0 && currentCoroutineContext().isActive) round.traffic(unrecorded)
    }
  }

  private fun failedWrite(round: Round, item: BackupItem, error: NasError): Pumped =
    if (error == NasError.NasFull || error == NasError.QuotaExceeded) Pumped.DiskFull(item, error) else Pumped.Then(stopFor(round, item, error))

  /**
   * E-R9, E-N13: `DISK_FULL` a meta' (NAS pieno o quota esaurita). "Da capo" nel diario, poi il proprio temporaneo si toglie (libera
   * lo spazio, come nella 4B) e il giro si ferma con il codice giusto; riprende quando c'e' spazio.
   */
  private suspend fun diskFull(round: Round, item: BackupItem, error: NasError): Step {
    val again = saveItem(item.copy(resumeAt = 0))
    again.temp?.let { temp ->
      val dropped = ops.deleteOwnTemp(again.share, temp, config.purpose)
      if (dropped !is NasResult.Ok) GalleryLog.w(TAG) { "temporaneo non tolto dopo il disco pieno: si riprova al prossimo giro" }
    }
    GalleryLog.w(TAG) { "disco o quota pieni a meta' caricamento" }
    return Step.Stop(RunStop.NAS, UploadNames.problemFor(error))
  }

  // --- GUARD_COPY: copia lato server dall'area comune (D9), se il motore guida anche il guardiano ------------------------------

  private suspend fun guardCopy(round: Round, item: BackupItem): Step {
    val temp = item.temp ?: return save(item.copy(temp = tempOf(item), resumeAt = 0))
    val source = item.guardSource ?: return Step.Wait()
    return when (val r = ops.copyToTemp(source.share, source.path, item.share, temp, source.stamp, config.purpose)) {
      is NasResult.Err -> stopFor(round, item, r.error)
      is NasResult.Ok -> when (val o = r.value) {
        is CopyOutcome.Copied -> if (o.bytes == item.size) {
          save(item.copy(state = UploadState.COPIED, resumeAt = item.size))
        } else {
          dropTemp(round, item, temp) ?: Step.Wait()
        }
        CopyOutcome.SourceMissing, is CopyOutcome.SourceChanged -> gone(round, item, lost = false)
        CopyOutcome.TempExists -> dropTemp(round, item, temp) ?: Step.Next(item)
        // Il ripiego dal telefono (E-D12) e' del guardiano (P3).
        CopyOutcome.NotSupported -> Step.Wait()
        CopyOutcome.Locked -> lockedWait(round, item)
      }
    }
  }

  // --- UPLOADED / COPIED: verifica completa del temporaneo (D4) ---------------------------------------------------------------

  private suspend fun verify(round: Round, item: BackupItem, back: UploadState): Step {
    if (item.verifyAttempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) return verifyFailed(round, item)
    val temp = item.temp ?: return save(item.copy(state = back, resumeAt = 0))
    val expected = item.sha256 ?: return Step.Wait()
    return when (val read = round.readLane.withPermit { readSha(round, item.share, temp) }) {
      // Il temporaneo non c'e' piu': si rifa', senza contare un tentativo.
      Read.Missing -> save(item.copy(state = back, resumeAt = 0))
      is Read.Fail -> stopFor(round, item, read.error)
      is Read.Ok -> if (read.sha == expected && read.stamp.size == item.size) {
        save(item.copy(state = UploadState.VERIFIED, tempStamp = read.stamp)).also { dropRaw(item) }
      } else {
        mismatch(round, item, back, temp)
      }
    }
  }

  /** D4, E-B18: la verifica non torna. "Da capo" (tentativo + 1) nel diario prima di togliere il temporaneo; al terzo `FAILED`. */
  private suspend fun mismatch(round: Round, item: BackupItem, back: UploadState, temp: SharePath): Step {
    val attempts = item.verifyAttempts + 1
    GalleryLog.w(TAG) { "verifica non tornata, tentativo $attempts di ${UploadStateMachine.MAX_VERIFY_ATTEMPTS}" }
    if (attempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) return verifyFailed(round, saveItem(item.copy(verifyAttempts = attempts)))
    val again = saveItem(item.copy(state = back, resumeAt = 0, verifyAttempts = attempts))
    return dropTemp(round, again, temp) ?: Step.Next(again)
  }

  private suspend fun verifyFailed(round: Round, item: BackupItem): Step {
    dropTempIfAny(round, item)?.let { return it }
    catalog.markProblem(item.mediaId, BackupProblemCode.VERIFY_FAILED)
    return save(item.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED))
  }

  // --- VERIFIED / PUBLISHING: pubblicazione senza sostituire (D6, E-B3, F4-D24) -----------------------------------------------

  private suspend fun toPublishing(round: Round, item: BackupItem): Step {
    val final = item.finalPath ?: UploadNames.nameFor(item.planned, item.nameAttempt)
    if (!config.allows(final)) return refuse(round, item)
    return save(item.copy(state = UploadState.PUBLISHING, finalPath = final))
  }

  /** Una destinazione fuori dalle radici ammesse (un elemento non pianificato da qui): mai pubblicata; il temporaneo si toglie. */
  private suspend fun refuse(round: Round, item: BackupItem): Step {
    GalleryLog.e(TAG) { "destinazione fuori dalle radici ammesse: elemento fermo, niente pubblicato" }
    dropTempIfAny(round, item)?.let { return it }
    return save(item.copy(state = UploadState.FAILED))
  }

  private suspend fun publish(round: Round, item: BackupItem, back: UploadState): Step {
    val temp = item.temp ?: return Step.Wait()
    val final = item.finalPath ?: return toPublishing(round, item)
    if (!config.allows(final)) return refuse(round, item)
    val r = ops.publishTemp(item.share, temp, final, config.purpose)
    if (r is NasResult.Err) {
      // Un file al posto di una cartella della destinazione: non si tocca niente, l'elemento aspetta (gli altri vanno avanti).
      return if (r.error == NasError.Exists) Step.Wait() else stopFor(round, item, r.error)
    }
    return when (val o = (r as NasResult.Ok).value) {
      is PublishOutcome.Published -> {
        val stamp = o.stamp ?: when (val st = stat(item.share, final)) {
          is Stat.Fail -> return stopFor(round, item, st.error)
          Stat.Missing -> return Step.Wait()
          is Stat.Found -> st.stamp
        }
        save(item.copy(state = UploadState.PUBLISHED, finalStamp = stamp))
      }
      // E-N13: aperto dal PC: attese crescenti.
      PublishOutcome.Locked -> lockedWait(round, item)
      PublishOutcome.Collision -> collision(round, item, temp, final)
      // La risposta persa (D3, F4-D24): temporaneo assente + finale con dimensione e SHA-256 giusti = pubblicato.
      PublishOutcome.TempMissing -> when (val existing = round.readLane.withPermit { readSha(round, item.share, final) }) {
        // Pubblicato (risposta persa) e poi spostato prima che ce ne accorgessimo, oppure mai pubblicato: prima si cerca la copia (F2).
        Read.Missing -> movedOrRedo(round, item, redo = item.copy(state = back, resumeAt = 0))
        is Read.Fail -> stopFor(round, item, existing.error)
        is Read.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
          save(item.copy(state = UploadState.PUBLISHED, finalStamp = existing.stamp))
        } else {
          movedOrRedo(round, item, redo = item.copy(state = back, resumeAt = 0))
        }
      }
    }
  }

  /** E-B3, E-B16: il nome e' occupato. Si rilegge il file che c'e': identico = collegato; diverso = "nome (n)" nel diario. Mai sostituito. */
  private suspend fun collision(round: Round, item: BackupItem, temp: SharePath, final: SharePath): Step =
    when (val existing = round.readLane.withPermit { readSha(round, item.share, final) }) {
      // Sparito nel frattempo: si riprova la pubblicazione con lo stesso nome.
      Read.Missing -> Step.Next(item)
      is Read.Fail -> stopFor(round, item, existing.error)
      is Read.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
        // Lo stesso file c'era gia' (ripresa, reinstallazione, copia dal PC): il proprio temporaneo si toglie e si collega.
        dropTemp(round, item, temp) ?: run {
          val at = clock.nasNowMs() ?: return@run Step.Wait()
          if (!attach(VerifiedCopy(item.mediaId, item.versionId, item.share, final, existing.stamp, at))) return@run Step.Wait()
          save(item.copy(state = UploadState.LINKED_EXISTING, finalStamp = existing.stamp))
        }
      } else {
        val attempt = item.nameAttempt + 1
        if (attempt > config.maxNameAttempts) {
          GalleryLog.w(TAG) { "troppi nomi occupati: l'elemento aspetta il prossimo giro" }
          Step.Wait()
        } else {
          // Un altro file con lo stesso nome: "nome (n)" nel diario prima di riprovare.
          save(item.copy(nameAttempt = attempt, finalPath = UploadNames.nameFor(item.planned, attempt)))
        }
      }
    }

  // --- PUBLISHED: timbro del finale, poi il catalogo (il media e' "al sicuro") ------------------------------------------------

  private suspend fun link(round: Round, item: BackupItem, back: UploadState): Step {
    val final = item.finalPath ?: return Step.Wait()
    var stamp = when (val st = stat(item.share, final)) {
      is Stat.Fail -> return stopFor(round, item, st.error)
      // Sparito (dal PC o spostato da un'altra via) fra la pubblicazione e il collegamento: prima si cerca altrove (F2), poi si ricarica.
      Stat.Missing -> return movedOrRedo(round, item, redo = item.copy(state = back, resumeAt = 0))
      is Stat.Found -> st.stamp
    }
    val same = item.tempStamp?.let { stamp.sameAs(it) } == true
    if (!same) {
      // Toccato dopo la rinomina: si rilegge tutto; byte diversi = non sono i nostri, si ricarica con un altro nome (mai toccarlo).
      when (val read = round.readLane.withPermit { readSha(round, item.share, final) }) {
        Read.Missing -> return movedOrRedo(round, item, redo = item.copy(state = back, resumeAt = 0))
        is Read.Fail -> return stopFor(round, item, read.error)
        is Read.Ok -> {
          if (read.sha != item.sha256 || read.stamp.size != item.size) {
            val attempt = item.nameAttempt + 1
            return movedOrRedo(
              round, item,
              redo = item.copy(state = back, resumeAt = 0, nameAttempt = attempt, finalPath = UploadNames.nameFor(item.planned, attempt)),
            )
          }
          stamp = read.stamp
        }
      }
    }
    val at = clock.nasNowMs() ?: return Step.Wait()
    if (!attach(VerifiedCopy(item.mediaId, item.versionId, item.share, final, stamp, at))) return Step.Wait()
    return save(item.copy(state = UploadState.LINKED, finalStamp = stamp))
  }

  /**
   * La copia riletta qui diventa "al sicuro" nel catalogo, **prima** del passo del diario che la cita. Il catalogo vero rifiuta (e annulla
   * il lotto) una copia con una dimensione diversa dalla versione o senza l'hash completo della versione: il motore non ci arriva (la
   * dimensione e' controllata a ogni rilettura, l'hash e' registrato alla lettura del telefono), ma se succedesse l'elemento aspetta il
   * prossimo giro con il diario intatto, senza fermare gli altri e senza nessun "al sicuro".
   */
  private suspend fun attach(copy: VerifiedCopy): Boolean = try {
    catalog.attachVerifiedCopies(listOf(copy))
    true
  } catch (e: IllegalArgumentException) {
    GalleryLog.e(TAG) { "il catalogo ha rifiutato una copia verificata: l'elemento aspetta il prossimo giro" }
    false
  }

  // --- LINKED: record e immagini all'indice (D10), poi DONE -----------------------------------------------------------------

  private suspend fun finishLinked(round: Round, item: BackupItem): Step {
    if (indexing.enabled) publishIndex(round, item)
    return save(item.copy(state = UploadState.DONE))
  }

  private suspend fun publishIndex(round: Round, item: BackupItem) {
    val final = item.finalPath ?: return
    val stamp = item.finalStamp ?: return
    val sha = item.sha256 ?: return
    val quick = item.quickHash ?: return
    val subject = UploadSubject(item.mediaId, item.versionId, item.size, sha, quick, round.describe(item))
    try {
      val prepared = takePrepared(item) ?: reprepare(round, item, subject)
      indexing.publish(subject, item.share, final, stamp, prepared)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // L'indice non decide niente della sicurezza: un errore qui non ferma il caricamento (la preparazione lo rifara').
      GalleryLog.w(TAG) { "pubblicazione nell'indice non riuscita (${e::class.java.simpleName})" }
    }
  }

  /** Dopo un riavvio le uscite non sono piu' in memoria: si rileggono dal telefono, se la riga e' ancora quella (D10). */
  private suspend fun reprepare(round: Round, item: BackupItem, subject: UploadSubject): PreparedUpload? {
    if (subject.description == null) return null
    val ref = item.phone ?: return null
    val file = (phone.open(ref) as? PhoneOpen.Opened)?.file ?: return null
    return file.use {
      if (file.stamp != item.expected) return@use null
      round.readLane.withPermit {
        withContext(readContext()) {
          val read = try {
            PhoneReading.read(file.bytes, item.size, config.chunkBytes, keepInMemory = item.size <= config.inMemoryMaxBytes)
          } catch (_: IOException) {
            null
          }
          if (read == null || !read.endsAtSize || read.sha256 != subject.sha256) null else indexing.prepare(subject, read.memory?.let { InMemoryMediaBytes(it) } ?: file.bytes)
        }
      }
    }
  }

  private fun keepRaw(item: BackupItem, raw: Raw) {
    if (raw.bytes.size.toLong() != item.size) return
    synchronized(rawCache) { rawCache[item.id.value] = raw }
  }

  /** I byte in memoria di [item], solo se sono esattamente quelli del suo hash. */
  private fun rawOf(item: BackupItem): Raw? = synchronized(rawCache) { rawCache[item.id.value] }
    ?.takeIf { it.sha256 == item.sha256 && it.bytes.size.toLong() == item.size }

  private fun dropRaw(item: BackupItem) {
    synchronized(rawCache) { rawCache.remove(item.id.value) }
  }

  private fun keepPrepared(item: BackupItem, prepared: PreparedUpload) {
    synchronized(preparedCache) { preparedCache[item.id.value] = prepared }
  }

  private fun takePrepared(item: BackupItem): PreparedUpload? = synchronized(preparedCache) { preparedCache.remove(item.id.value) }

  // --- Temporanei, attese, errori ----------------------------------------------------------------------------------------------

  /** Il proprio temporaneo, se l'elemento e' in uno stato in cui puo' esistere; null = tolto (o non c'era). */
  private suspend fun dropTempIfAny(round: Round, item: BackupItem): Step? {
    val temp = item.temp ?: return null
    if (item.state !in TEMP_STATES) return null
    return dropTemp(round, item, temp)
  }

  /** Toglie il **proprio** temporaneo del backup; null = tolto o assente. Un nome che non e' un nostro file non si tocca: si aspetta. */
  private suspend fun dropTemp(round: Round, item: BackupItem, temp: SharePath): Step? =
    when (val d = ops.deleteOwnTemp(item.share, temp, config.purpose)) {
      is NasResult.Err -> stopFor(round, item, d.error)
      is NasResult.Ok -> when (d.value) {
        DeleteOutcome.Deleted, DeleteOutcome.Missing -> null
        DeleteOutcome.Locked -> lockedWait(round, item)
        DeleteOutcome.NotAFile, is DeleteOutcome.SizeChanged -> {
          GalleryLog.w(TAG) { "al nome del temporaneo c'e' altro: l'elemento aspetta" }
          Step.Wait()
        }
      }
    }

  /** E-N13: un file aperto da un altro programma. Attese crescenti nello stesso giro, poi il prossimo giro. */
  private fun lockedWait(round: Round, item: BackupItem): Step {
    val n = round.lockedWaits(item)
    return if (n >= config.lockedRetries) Step.Wait() else Step.Wait(config.lockedFirstWaitMs shl n)
  }

  private fun stopFor(round: Round, item: BackupItem, error: NasError): Step =
    if (error == NasError.FileBusy) lockedWait(round, item) else Step.Stop(RunStop.NAS, UploadNames.problemFor(error))

  private sealed interface Read {
    class Ok(val sha: Sha256, val stamp: NasStamp) : Read

    data object Missing : Read

    class Fail(val error: NasError) : Read
  }

  /** Rilettura completa dal NAS (D4: `readAll` a 4 flussi, mai una cache): SHA-256 e timbro dell'apertura; il traffico si conta. */
  private suspend fun readSha(round: Round, share: String, path: SharePath): Read {
    val file = when (val opened = reader.open(share, path, config.purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) Read.Missing else Read.Fail(opened.error)
      is NasResult.Ok -> opened.value
    }
    return file.use { f ->
      var bytes = 0L
      val r = reader.readAll(f, { _, n -> bytes += n }, null)
      round.reread.addAndGet(bytes)
      round.traffic(bytes)
      when (r) {
        is NasResult.Ok -> Read.Ok(r.value, f.stamp)
        is NasResult.Err -> if (r.error == NasError.NotFound) Read.Missing else Read.Fail(r.error)
      }
    }
  }

  private sealed interface Stat {
    class Found(val stamp: NasStamp) : Stat

    data object Missing : Stat

    class Fail(val error: NasError) : Stat
  }

  private suspend fun stat(share: String, path: SharePath): Stat = when (val r = reader.stat(share, path, config.purpose)) {
    is NasResult.Err -> if (r.error == NasError.NotFound) Stat.Missing else Stat.Fail(r.error)
    is NasResult.Ok -> r.value?.takeIf { !it.isDirectory }?.let { Stat.Found(NasStamp(it.size, it.modifiedAt, it.fileId)) } ?: Stat.Missing
  }

  // --- Il giro ----------------------------------------------------------------------------------------------------------------

  /** Lo stato di un giro: conteggi, corsie, attese, la ragione dell'arresto. Vive quanto il giro (nessun dato dopo la morte). */
  private inner class Round(
    val kind: BackupKind?,
    val trigger: BackupTrigger,
    private val control: UploadControl,
    val deadline: Long,
  ) {
    val done = AtomicInteger()
    val linked = AtomicInteger()
    val gone = AtomicInteger()
    val failed = AtomicInteger()
    val uploaded = AtomicLong()
    val reread = AtomicLong()
    val halt = AtomicReference<Halt?>(null)

    /** D11: un file in caricamento, uno in lettura, preparazione o verifica. */
    val uploadLane = Semaphore(1)
    val readLane = Semaphore(1)

    private val controlLock = Mutex()
    private val lock = Any()
    private val locked = HashMap<Long, Int>()
    private val readFailures = HashMap<Long, Int>()
    private val descriptions = HashMap<Pair<MediaId, VersionId>, UploadDescription?>()

    fun stopWith(stop: RunStop, problem: BackupProblemCode?) {
      halt.compareAndSet(null, Halt(stop, problem))
    }

    /** Il cancello si e' chiuso: la ragione di [gateState] ferma il giro ([haltFor]). */
    fun stopForGate(gateState: BackupGateState) {
      val h = haltFor(gateState)
      stopWith(h.stop, h.problem)
    }

    /** Si puo' continuare? "Ferma", tempo del giro e regole della tappa; la prima ragione vince. */
    suspend fun mayContinue(): Boolean {
      if (halt.get() != null) return false
      stopRequested?.let {
        stopWith(RunStop.STOPPED, null)
        return false
      }
      if (clock.monotonicMs() >= deadline) {
        stopWith(RunStop.BUDGET, null)
        return false
      }
      if (!controlLock.withLock { control.proceed() }) {
        stopWith(RunStop.BUDGET, null)
        return false
      }
      return true
    }

    suspend fun traffic(bytes: Long) {
      if (bytes > 0) controlLock.withLock { control.traffic(bytes) }
    }

    fun count(state: UploadState) {
      when (state) {
        UploadState.DONE -> done.incrementAndGet()
        UploadState.LINKED_EXISTING -> linked.incrementAndGet()
        UploadState.GONE -> gone.incrementAndGet()
        UploadState.FAILED -> failed.incrementAndGet()
        else -> Unit
      }
    }

    fun lockedWaits(item: BackupItem): Int = synchronized(lock) {
      val n = locked[item.id.value] ?: 0
      locked[item.id.value] = n + 1
      n
    }

    fun readFailed(item: BackupItem): Int = synchronized(lock) {
      val n = (readFailures[item.id.value] ?: 0) + 1
      readFailures[item.id.value] = n
      n
    }

    suspend fun describe(item: BackupItem): UploadDescription? {
      val key = item.mediaId to item.versionId
      synchronized(lock) { if (key in descriptions) return descriptions[key] }
      val found = facts.describe(item.mediaId, item.versionId)
      synchronized(lock) { descriptions[key] = found }
      return found
    }

    fun publishRunning(queue: WorkQueue) {
      if (halt.get() != null) return
      state.value = BackupStatus.Running(
        kind = kind ?: BackupKind.PHOTOS,
        done = done.get() + linked.get(),
        total = queue.total,
        bytesDone = uploaded.get(),
        bytesTotal = queue.totalBytes,
        etaMillis = null,
        trigger = trigger,
      )
    }
  }

  /** Gli elementi del giro: pronti, rimandati di poco (E-N13) o al prossimo giro. */
  private inner class WorkQueue(private val round: Round, items: List<BackupItem>) {
    private val mutex = Mutex()
    private val ready = ArrayDeque(items)
    private val later = ArrayList<Pair<BackupItem, Long>>()
    private val parked = AtomicInteger()
    val total: Int = items.size
    val totalBytes: Long = items.sumOf { it.size }

    /** Il prossimo elemento; se restano solo attese brevi, si aspetta la prima (se c'e' tempo nel giro). Null = finito. */
    suspend fun take(): BackupItem? {
      while (true) {
        val next = mutex.withLock {
          ready.removeFirstOrNull()?.let { return it }
          val first = later.minByOrNull { it.second } ?: return null
          later.remove(first)
          first
        }
        val wait = (next.second - clock.monotonicMs()).coerceAtLeast(0)
        if (clock.monotonicMs() + wait >= round.deadline) {
          parked.incrementAndGet()
          continue
        }
        if (wait > 0) sleep(wait)
        return next.first
      }
    }

    suspend fun defer(item: BackupItem, retryAfterMs: Long?) {
      if (retryAfterMs == null) {
        parked.incrementAndGet()
      } else {
        mutex.withLock { later += item to (clock.monotonicMs() + retryAfterMs) }
      }
    }

    fun park() {
      parked.incrementAndGet()
    }

    /** Gli elementi che aspettano il prossimo giro (o che il giro fermato non ha toccato). */
    fun waiting(): Int = parked.get() + ready.size + later.size
  }

  companion object {
    private const val TAG = "BackupUpload"

    /** Gli elementi aperti letti a ogni giro (il piano ne crea al piu' una pagina per giro). */
    private const val RESUME_LIMIT = 10_000

    /** Uscite della preparazione tenute in memoria al massimo (D10). */
    private const val MAX_PREPARED = 8
    private const val MAX_PREPARED_BYTES = 64L shl 20

    /** File letti tenuti in memoria al piu' (uno in caricamento, uno appena letto: D11). */
    private const val MAX_RAW = 2

    /** Gli stati in cui il proprio temporaneo puo' esistere (si toglie prima di un'uscita). */
    private val TEMP_STATES = setOf(
      UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED, UploadState.PUBLISHING, UploadState.COPYING, UploadState.COPIED,
    )
  }
}

/** Il thread a priorita' minima della lettura con l'app in primo piano (§4.4, riga P2): uno solo, creato al primo uso. */
internal object LowPriority {
  val dispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "mcg-backup-lettura").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }.asCoroutineDispatcher()
  }
}
