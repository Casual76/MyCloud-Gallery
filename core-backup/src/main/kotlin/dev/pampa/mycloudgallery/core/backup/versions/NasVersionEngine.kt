package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.upload.InMemoryMediaBytes
import dev.pampa.mycloudgallery.core.backup.upload.PhoneReading
import dev.pampa.mycloudgallery.core.backup.upload.UploadControl
import dev.pampa.mycloudgallery.core.backup.upload.UploadNames
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.time.ZoneId
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Il **motore delle versioni** (Fase 5B, P8; docs/fase5-progetto.md §2.4, D17, D18, D20, D32, D35; §5.4 invarianti 1-3, 6-13): esegue i
 * lavori `VERSION` e `SWAP` del [VersionJournal] sopra le primitive in sola aggiunta ([NasBackupOps]: temporanei, pubblicazione senza
 * sostituire, `archiveVersion`, `restoreVersion`), la lettura del NAS, il telefono, i file preparati e il catalogo ([VersionCatalog]).
 *
 * **`VERSION`** (`PLANNED → HASHED → UPLOADING → UPLOADED → VERIFIED → ARCHIVING → ARCHIVED → PUBLISHING → PUBLISHED → LINKED → DONE`):
 * - la versione nuova si legge per intero (hash), si scrive in un **proprio temporaneo** con i punti di ripresa (D5, R3) e si **rilegge**
 *   dal NAS (D4) come nella 5A: nessun file visibile non verificato;
 * - solo dopo la verifica il piano **si ricalcola** dal catalogo: se la versione e' ancora l'attuale, l'attuale di prima va in
 *   `Versioni/` (il nome si scrive prima, la cartella si crea prima con `mkdirs`, il timbro e' obbligatorio); se nel frattempo il PC ha
 *   modificato il file (E-E6), niente si sposta: `SOURCE_CHANGED`, la versione resta nel temporaneo finche' la scansione non registra la
 *   versione del PC, poi va in `Versioni/` (non e' piu' l'attuale) o al suo posto;
 * - la versione nuova si **pubblica al solito percorso senza sostituire**: fra `ARCHIVED` e `PUBLISHED` il solito percorso e' vuoto per un
 *   istante e la ripresa pubblica; un file di altri apparso li' non si tocca mai e la versione va in `Versioni/`;
 * - dopo il collegamento nel catalogo (la versione e' "al sicuro"), un video che ha spostato il suo originale apre l'attivita' in sospeso
 *   di D20; un file preparato si toglie.
 *
 * **`SWAP`** ("Ripristina originale", `PLANNED → ARCHIVING → ARCHIVED → RESTORING → RESTORED → LINKED → DONE`): due rinomine, nessun
 * byte caricato. Se l'attuale non e' piu' quello del piano niente si sposta e lo scambio finisce (`SOURCE_CHANGED`); se l'originale non si
 * puo' riportare, l'attuale **torna al suo posto** (`ROLLED_BACK`): a lavoro finito il solito percorso non resta vuoto.
 *
 * Il cancello ([BackupGate]: congelamento, "Ferma le scritture", D12, area) si guarda prima di ogni passo (D32). Un errore del NAS ferma il
 * giro; un errore inatteso di un lavoro lo parcheggia (`FAILED`) solo **prima** di spostare qualcosa: dopo, il lavoro resta aperto e si
 * riprende (mai un solito percorso lasciato vuoto da un `FAILED`). Mai nei log nomi, percorsi, share, id o impronte.
 */
class NasVersionEngine(
  private val deviceId: String,
  private val share: String,
  private val ops: NasBackupOps,
  private val reader: NasMediaReader,
  private val journal: VersionJournal,
  private val catalog: VersionCatalog,
  private val phone: PhoneBytes,
  private val staged: StagedVersionFiles,
  private val gate: BackupGate,
  private val clock: BackupClock,
  private val tasks: VideoOriginalTasks = VideoOriginalTasks.NONE,
  private val config: VersionConfig = VersionConfig(),
  /** Il fuso in cui si scrive l'ora nei nomi delle versioni ("versione del …"): l'ora resta quella del NAS. */
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val purpose: Purpose = Purpose.BACKGROUND,
  /** Le attese crescenti di un file aperto dal PC (E-N13): nei test l'orologio finto. */
  private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

  init {
    require(ops.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  @Volatile private var stopRequested = false

  /** "Ferma": il giro si ferma prima del passo successivo; ogni lavoro resta nel diario. */
  fun stop() {
    stopRequested = true
  }

  // --- Il piano: solo dati locali (E-S11) ---------------------------------------------------------------------------------------

  /**
   * Le versioni del telefono da salvare (candidati del backup con `original = false`, o un originale rimasto solo sul telefono, E-E6) che
   * hanno gia' un solito percorso nell'area privata diventano lavori `VERSION`; le altre restano alla 5A. Una versione con un elemento gia'
   * aperto (della 5A o delle versioni) non ne riceve un altro. Restituisce i lavori creati o ritrovati.
   */
  suspend fun planPhoneVersions(candidates: List<BackupCandidate>): List<VersionJob> {
    val out = ArrayList<VersionJob>()
    for (c in candidates) {
      if (journal.busy(c.versionId)) continue
      val media = catalog.media(c.mediaId) ?: continue
      val new = VersionPlanning.phoneVersion(media, share, c.versionId, c.phone, c.stamp) ?: continue
      if (!fenced(new.usualPath)) continue
      out += journal.create(new)
    }
    return out
  }

  /** D13 (P14): con la recinzione di debug ([VersionConfig.fenceRoot]) solo i media sotto la radice di prova hanno versioni; senza, tutti. */
  private fun fenced(usualPath: SharePath): Boolean {
    val root = config.fenceRoot ?: return true
    val c = usualPath.components
    val r = root.components
    return c.size > r.size && r.indices.all { c[it].equals(r[it], ignoreCase = true) }
  }

  /** Un file preparato (una correzione, un editor esterno) della versione [versionId] diventa un lavoro `VERSION`. Null se non si puo'. */
  suspend fun planStaged(mediaId: MediaId, versionId: VersionId, sha256: Sha256, size: Long): VersionJob? {
    val media = catalog.media(mediaId) ?: return null
    val new = VersionPlanning.stagedVersion(media, share, versionId, VersionSource.Staged(sha256), size) ?: return null
    if (!fenced(new.usualPath)) return null
    return journal.create(new)
  }

  /** "Ripristina originale" (D18): il lavoro `SWAP` del media, o null se non si puo' (originale o attuale non verificati, nessuna versione). */
  suspend fun planSwap(mediaId: MediaId): VersionJob? {
    val media = catalog.media(mediaId) ?: return null
    val new = VersionPlanning.swap(media, share) ?: return null
    if (!fenced(new.usualPath)) return null
    if (journal.forMedia(mediaId).any { !it.state.terminal && it.kind == UploadKind.VERSION }) return null
    return journal.create(new)
  }

  /**
   * E-E2: il telefono e' tornato a una versione nota (Samsung "Ripristina originale"; la lettura del telefono l'ha riconosciuto,
   * `RevertedToVersion`) e il catalogo dice gia' che quella e' l'attuale: sul NAS le due versioni si **scambiano** (`SWAP`), senza caricare
   * niente e senza conferma (non cancella e non sostituisce niente: due rinomine dei file del media stesso). Null se non serve o non si puo'.
   */
  suspend fun planRealign(mediaId: MediaId): VersionJob? {
    val media = catalog.media(mediaId) ?: return null
    if (journal.forMedia(mediaId).any { !it.state.terminal }) return null
    val new = VersionPlanning.realign(media, share) ?: return null
    if (!fenced(new.usualPath)) return null
    return journal.create(new)
  }

  /**
   * La ripresa dei file preparati (una correzione interrotta da una morte del processo): un file preparato senza lavoro aperto torna al suo
   * lavoro se la sua versione e' nel catalogo e non e' ancora sul NAS; se la correzione non e' mai arrivata al catalogo (o la versione e'
   * gia' al sicuro) il file si toglie. Solo dati locali.
   */
  suspend fun recoverStaged() {
    val entries = staged.pending()
    if (entries.isEmpty()) return
    val cited = journal.open(config.openLimit).mapNotNullTo(HashSet()) { (it.source as? VersionSource.Staged)?.sha256 }
    for (entry in entries) {
      if (entry.sha256 in cited) continue
      val media = catalog.media(entry.mediaId)
      val version = media?.versions?.firstOrNull { it.fullHash == entry.sha256 }
      when {
        media == null || version == null -> staged.discard(entry.sha256)
        version.nas.any { VersionPlanning.isOwnPrivate(it, share) } -> staged.discard(entry.sha256)
        // Un lavoro di questo file gia' fallito (verifica non tornata tre volte, file illeggibile): non si ritenta da solo a ogni giro; il
        // media porta il segno del problema e il file resta per "Riprova".
        journal.forMedia(entry.mediaId).any { it.state == UploadState.FAILED && (it.source as? VersionSource.Staged)?.sha256 == entry.sha256 } -> Unit
        else -> VersionPlanning.stagedVersion(media, share, version.versionId, VersionSource.Staged(entry.sha256), entry.size)
          ?.takeIf { fenced(it.usualPath) }?.let { journal.create(it) }
      }
    }
  }

  // --- I giri ----------------------------------------------------------------------------------------------------------------

  /** Un giro: i lavori aperti del diario di questa share, dal piu' vecchio, entro [budget]. */
  suspend fun run(budget: Duration, control: UploadControl = UploadControl.FREE): VersionRunResult {
    stopRequested = false
    val round = Round(clock.monotonicMs() + budget.inWholeMilliseconds, control)
    val gateState = gate.stateOf(share)
    if (!gateState.open) return round.finish(gateState)
    recoverStaged()
    val jobs = journal.open(config.openLimit)
    for (job in jobs) {
      if (!job.share.equals(share, ignoreCase = true) || !fenced(job.usualPath)) {
        // R7: un lavoro di un'altra share aspetta; niente si scrive nella share di prima. D13: nemmeno fuori dalla radice di prova.
        round.waiting++
        continue
      }
      if (round.halt != null || !round.mayContinue()) {
        round.waiting++
        continue
      }
      drive(round, job)
    }
    return round.finish(null)
  }

  /** Porta avanti un solo lavoro (un "Ripristina originale" o una correzione appena confermati). Il lavoro com'e' dopo, o null se non c'e'. */
  suspend fun runJob(id: VersionJobId, budget: Duration, control: UploadControl = UploadControl.FREE): VersionJob? {
    stopRequested = false
    val job = journal.job(id) ?: return null
    if (job.state.terminal || !job.share.equals(share, ignoreCase = true) || !fenced(job.usualPath)) return job
    val round = Round(clock.monotonicMs() + budget.inWholeMilliseconds, control)
    if (gate.stateOf(share).open) drive(round, job)
    return journal.job(id)
  }

  private sealed interface Step {
    class Next(val job: VersionJob) : Step

    /** Il lavoro aspetta: [retryAfterMs] null = il prossimo giro; [held] = fermo per una ragione del lavoro (`SOURCE_CHANGED`). */
    class Wait(val retryAfterMs: Long? = null, val held: Boolean = false) : Step

    class Stop(val stop: RunStop, val problem: BackupProblemCode?) : Step
  }

  private suspend fun drive(round: Round, start: VersionJob) {
    var job = start
    var steps = 0
    while (true) {
      val gateState = gate.stateOf(job.share)
      if (!gateState.open) {
        round.stopForGate(gateState)
        round.waiting++
        return
      }
      val step = try {
        advance(round, job)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onError(round, job, e)
        return
      }
      when (step) {
        is Step.Next -> {
          job = step.job
          if (job.state.terminal) {
            round.count(job)
            return
          }
        }
        is Step.Wait -> {
          val retry = step.retryAfterMs
          if (retry == null || clock.monotonicMs() + retry >= round.deadline) {
            round.waiting++
            if (step.held) round.held++
            return
          }
          sleep(retry)
        }
        is Step.Stop -> {
          round.stopWith(step.stop, step.problem)
          round.waiting++
          return
        }
      }
      if (++steps >= config.maxStepsPerJob) {
        GalleryLog.w(TAG) { "lavoro fermo dopo $steps passi nello stesso giro" }
        round.waiting++
        return
      }
      if (!round.mayContinue()) {
        round.waiting++
        return
      }
    }
  }

  /**
   * R1 della revisione 5A, con una regola in piu': un errore inatteso **prima** che il lavoro abbia spostato qualcosa lo parcheggia
   * (`FAILED` con [BackupProblemCode.UNEXPECTED], il proprio temporaneo tolto); **dopo** (`ARCHIVING` e oltre, o uno scambio iniziato) il
   * lavoro resta aperto e si riprende al prossimo giro: un `FAILED` lascerebbe il solito percorso vuoto o una versione nel temporaneo.
   */
  private suspend fun onError(round: Round, job: VersionJob, error: Exception) {
    GalleryLog.e(TAG) { "errore inatteso (${error::class.java.simpleName}) in ${job.state.code}" }
    val fresh = journal.job(job.id) ?: job
    if (fresh.state.terminal) {
      round.count(fresh)
      return
    }
    markQuietly(fresh.mediaId, BackupProblemCode.UNEXPECTED)
    val beforeMoving = fresh.state in BEFORE_MOVING && (fresh.kind == UploadKind.VERSION || fresh.state == UploadState.PLANNED)
    if (!beforeMoving) {
      round.waiting++
      return
    }
    dropTempQuietly(fresh)
    journal.update(fresh.copy(state = UploadState.FAILED, problem = BackupProblemCode.UNEXPECTED))
    round.failed++
  }

  private suspend fun advance(round: Round, job: VersionJob): Step = when (job.kind) {
    UploadKind.VERSION -> when (job.state) {
      UploadState.PLANNED -> readSource(round, job)
      UploadState.HASHED -> toUploading(job)
      UploadState.UPLOADING -> upload(round, job)
      UploadState.UPLOADED -> verify(round, job)
      UploadState.VERIFIED -> afterVerified(job)
      UploadState.ARCHIVING -> archive(round, job)
      UploadState.ARCHIVED -> afterArchived(job)
      UploadState.PUBLISHING -> publish(round, job)
      UploadState.PUBLISHED -> link(round, job)
      UploadState.LINKED -> finishLinked(job)
      else -> error("Stato ${job.state.code} fuori dal cammino della versione.")
    }
    UploadKind.SWAP -> when (job.state) {
      UploadState.PLANNED -> startSwap(job)
      UploadState.ARCHIVING -> archive(round, job)
      UploadState.ARCHIVED -> afterArchivedSwap(job)
      UploadState.RESTORING -> restore(round, job)
      UploadState.RESTORED -> afterRestored(job)
      UploadState.LINKED -> save(job.copy(state = if (job.hold == VersionHold.ROLLED_BACK) UploadState.FAILED else UploadState.DONE))
      else -> error("Stato ${job.state.code} fuori dal cammino dello scambio.")
    }
    else -> error("Il motore delle versioni non esegue ${job.kind.code}.")
  }

  private suspend fun saveJob(job: VersionJob): VersionJob {
    journal.update(job)
    return job
  }

  private suspend fun save(job: VersionJob): Step = Step.Next(saveJob(job))

  private fun tempOf(job: VersionJob): SharePath = NasBackupPaths.tempFor(deviceId, job.id.value)

  // --- VERSION, PLANNED: lettura completa dei byte della versione (hash, D10) -------------------------------------------------------

  private suspend fun readSource(round: Round, job: VersionJob): Step = when (val src = job.source) {
    is VersionSource.Phone -> readPhone(round, job, src)
    is VersionSource.Staged -> readStaged(round, job, src)
    is VersionSource.InVersions -> error("Una versione nuova non viene da Versioni/.")
  }

  private suspend fun readPhone(round: Round, job: VersionJob, src: VersionSource.Phone): Step {
    // E-M12: un file vuoto non e' una versione da salvare.
    if (job.size == 0L) return fail(job, BackupProblemCode.UNREADABLE)
    val file = when (val opened = phone.open(src.ref)) {
      PhoneOpen.Stale -> return refreshPhone(job, src)
      PhoneOpen.Gone -> return gone(job)
      is PhoneOpen.Denied -> return denied(opened.reason)
      is PhoneOpen.Opened -> opened.file
    }
    file.use {
      // E-B15: la versione e' cambiata prima della lettura; quella nuova avra' il suo lavoro.
      if (file.stamp != src.expected) return gone(job)
      val read = try {
        PhoneReading.read(file.bytes, job.size, config.chunkBytes, keepInMemory = job.size <= config.inMemoryMaxBytes)
      } catch (_: IOException) {
        null
      } ?: return afterPhoneFailure(round, job, src)
      // R6: il file continua oltre la dimensione del MediaStore: i byte letti sono un prefisso, non la versione.
      if (!read.endsAtSize) return gone(job)
      when (val after = phone.lookup(src.ref)) {
        is PhoneLookup.Present -> if (after.stamp != src.expected) return gone(job)
        PhoneLookup.Gone -> return gone(job)
        PhoneLookup.Stale -> return refreshPhone(job, src)
        is PhoneLookup.Denied -> return denied(after.reason)
      }
      when (catalog.recordFullHash(job.mediaId, job.versionId, read.sha256)) {
        HashRecord.RECORDED, HashRecord.SAME -> Unit
        HashRecord.CONFLICT, HashRecord.UNKNOWN_VERSION -> return gone(job)
      }
      val hashed = save(job.copy(state = UploadState.HASHED, sha256 = read.sha256, quickHash = read.quickHash))
      read.memory?.let { round.keepRaw(job, read.sha256, it) }
      return hashed
    }
  }

  private suspend fun readStaged(round: Round, job: VersionJob, src: VersionSource.Staged): Step {
    // Il file preparato non c'e' piu' (dati dell'app azzerati): questa versione non si salva da qui, mai "al sicuro".
    val bytes = staged.open(src.sha256) ?: return fail(job, BackupProblemCode.UNREADABLE)
    bytes.use {
      if (bytes.size != job.size) return fail(job, BackupProblemCode.UNREADABLE)
      val read = try {
        PhoneReading.read(bytes, job.size, config.chunkBytes, keepInMemory = job.size <= config.inMemoryMaxBytes)
      } catch (_: IOException) {
        null
      }
      if (read == null || !read.endsAtSize || read.sha256 != src.sha256) return fail(job, BackupProblemCode.UNREADABLE)
      when (catalog.recordFullHash(job.mediaId, job.versionId, read.sha256)) {
        HashRecord.RECORDED, HashRecord.SAME -> Unit
        HashRecord.CONFLICT, HashRecord.UNKNOWN_VERSION -> return fail(job, BackupProblemCode.UNEXPECTED)
      }
      val hashed = save(job.copy(state = UploadState.HASHED, sha256 = read.sha256, quickHash = read.quickHash))
      read.memory?.let { round.keepRaw(job, read.sha256, it) }
      return hashed
    }
  }

  /**
   * E-P12: la riga del telefono si ritrova dal catalogo (stessa versione, stesso timbro). Finche' il catalogo ha ancora la riga di prima
   * (presente, con l'`_ID` e la versione del MediaStore del lavoro) la lettura del telefono non ha visto il cambio: si aspetta. Se l'ha visto e
   * la versione non ha piu' una riga presente con quel timbro, la versione del lavoro non e' piu' sul telefono com'era: `GONE` (F1 della prova
   * della 5B, `docs/prova-500.md` §C6; la stessa regola del caricamento della 5A, F1 della Parte B). Mai un lavoro appeso a una riga che non
   * tornera': se la versione e' ancora sul telefono con un'altra riga o un altro timbro, il piano del giro dopo ne fa un lavoro nuovo.
   */
  private suspend fun refreshPhone(job: VersionJob, src: VersionSource.Phone): Step {
    val rows = catalog.media(job.mediaId)?.version(job.versionId)?.phone.orEmpty().filter { it.present }
    rows.firstOrNull { it.stamp == src.expected && it.ref != src.ref }?.let { return save(job.copy(source = src.copy(ref = it.ref))) }
    if (rows.any { it.ref == src.ref }) return Step.Wait()
    return gone(job)
  }

  private suspend fun afterPhoneFailure(round: Round, job: VersionJob, src: VersionSource.Phone): Step =
    when (val now = phone.lookup(src.ref)) {
      PhoneLookup.Gone -> gone(job)
      is PhoneLookup.Present -> when {
        now.stamp != src.expected -> gone(job)
        round.readFailed(job) >= config.phoneReadRetries -> fail(job, BackupProblemCode.UNREADABLE)
        else -> Step.Next(job)
      }
      PhoneLookup.Stale -> refreshPhone(job, src)
      is PhoneLookup.Denied -> denied(now.reason)
    }

  /** R5: "foto selezionate" riguarda una riga: il lavoro aspetta. I rifiuti globali fermano il giro. */
  private fun denied(reason: PhoneDenied): Step =
    if (reason == PhoneDenied.NOT_SELECTED) Step.Wait() else Step.Stop(RunStop.PHONE, reason.problem)

  /** `GONE` (E-B4, E-B15): la versione non e' piu' sul telefono; il proprio temporaneo incompleto si toglie prima. */
  private suspend fun gone(job: VersionJob): Step {
    dropTempIfAny(job)?.let { return it }
    return save(job.copy(state = UploadState.GONE))
  }

  /** Un lavoro che non si puo' fare (mai prima di aver spostato qualcosa): il temporaneo si toglie, il media porta il segno. */
  private suspend fun fail(job: VersionJob, problem: BackupProblemCode): Step {
    dropTempIfAny(job)?.let { return it }
    catalog.markProblem(job.mediaId, problem)
    return save(job.copy(state = UploadState.FAILED, problem = problem))
  }

  // --- VERSION, HASHED / UPLOADING: il temporaneo con i punti di ripresa (D5, R3) ---------------------------------------------------

  private suspend fun toUploading(job: VersionJob): Step {
    // §2.2 "Spazio": prima di un file oltre 1 GB lo spazio deve bastare con 1 GiB di margine.
    if (job.size > config.spaceCheckBytes) {
      when (val space = ops.space(job.share, purpose)) {
        is NasResult.Err -> return stopFor(job, space.error)
        is NasResult.Ok -> if (space.value.callerFreeBytes < job.size + config.spaceMarginBytes) {
          return Step.Stop(RunStop.NAS, if (space.value.quotaLimited) BackupProblemCode.QUOTA else BackupProblemCode.NAS_FULL)
        }
      }
    }
    return save(job.copy(state = UploadState.UPLOADING, temp = tempOf(job), resumeAt = 0, resumeId = null))
  }

  private sealed interface Pumped {
    class Done(val job: VersionJob) : Pumped

    class Then(val step: Step) : Pumped

    class SourceFailed(val job: VersionJob) : Pumped

    class DiskFull(val job: VersionJob, val error: NasError) : Pumped
  }

  private suspend fun upload(round: Round, job: VersionJob): Step {
    if (job.verifyAttempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) return verifyFailed(job)
    val temp = job.temp ?: return save(job.copy(temp = tempOf(job), resumeAt = 0))
    // I byte letti per l'hash sono ancora in memoria: si caricano quelli (sono la versione).
    round.rawOf(job)?.let { raw -> return afterPump(round, writeTemp(round, job, temp, InMemoryMediaBytes(raw))) }
    return when (val src = job.source) {
      is VersionSource.Phone -> {
        val file = when (val opened = phone.open(src.ref)) {
          PhoneOpen.Stale -> return refreshPhone(job, src)
          PhoneOpen.Gone -> return gone(job)
          is PhoneOpen.Denied -> return denied(opened.reason)
          is PhoneOpen.Opened -> opened.file
        }
        val pumped = file.use {
          // E-B15: cambiato prima che il temporaneo fosse completo: questi byte non sono piu' la versione.
          if (file.stamp != src.expected || file.bytes.size != job.size) return gone(job)
          writeTemp(round, job, temp, file.bytes)
        }
        afterPump(round, pumped)
      }
      is VersionSource.Staged -> {
        val bytes = staged.open(src.sha256) ?: return fail(job, BackupProblemCode.UNREADABLE)
        val pumped = bytes.use { writeTemp(round, job, temp, bytes) }
        afterPump(round, pumped)
      }
      is VersionSource.InVersions -> error("Una versione nuova non viene da Versioni/.")
    }
  }

  private suspend fun afterPump(round: Round, pumped: Pumped): Step = when (pumped) {
    is Pumped.Then -> pumped.step
    is Pumped.SourceFailed -> when (val src = pumped.job.source) {
      is VersionSource.Phone -> afterPhoneFailure(round, pumped.job, src)
      else -> fail(pumped.job, BackupProblemCode.UNREADABLE)
    }
    is Pumped.DiskFull -> diskFull(pumped.job, pumped.error)
    // Temporaneo completo: decide la verifica (D4), come nella 5A.
    is Pumped.Done -> save(pumped.job.copy(state = UploadState.UPLOADED, resumeAt = pumped.job.size))
  }

  private suspend fun writeTemp(round: Round, start: VersionJob, temp: SharePath, source: MediaBytes): Pumped {
    val writer: BackupTempWriter
    val from: Long
    if (start.resumeAt > 0) {
      // R3: senza l'identita' del temporaneo al punto di ripresa non si accorcia niente: da capo.
      val tempId = start.resumeId ?: return Pumped.Then(save(start.copy(resumeAt = 0)))
      when (val r = ops.reopenTemp(start.share, temp, start.resumeAt, tempId, purpose)) {
        is NasResult.Err -> return Pumped.Then(
          if (r.error is NasError.Unexpected) save(start.copy(resumeAt = 0, resumeId = null)) else stopFor(start, r.error),
        )
        is NasResult.Ok -> when (val o = r.value) {
          is ReopenOutcome.Reopened -> {
            writer = o.writer
            from = start.resumeAt
          }
          ReopenOutcome.Missing, is ReopenOutcome.TooShort -> return Pumped.Then(save(start.copy(resumeAt = 0, resumeId = null)))
          ReopenOutcome.Locked -> return Pumped.Then(lockedWait(round, start))
          ReopenOutcome.Foreign -> {
            GalleryLog.w(TAG) { "al nome del temporaneo c'e' un altro file: la ripresa ricomincia da capo" }
            return Pumped.Then(save(start.copy(resumeAt = 0, resumeId = null)))
          }
        }
      }
    } else {
      when (val r = ops.openTemp(start.share, temp, purpose)) {
        is NasResult.Ok -> {
          writer = r.value
          from = 0
        }
        is NasResult.Err -> {
          if (r.error != NasError.Exists) return Pumped.Then(stopFor(start, r.error))
          // "Da capo" nel diario: il temporaneo rimasto e' solo nostro e si toglie, poi si ricrea.
          return Pumped.Then(dropTemp(start, temp) ?: Step.Next(start))
        }
      }
    }
    return writer.use { pump(round, start, writer, from, source) }
  }

  /** Scritture a blocchi e un punto di ripresa ogni [VersionConfig.checkpointBytes] (D5); il traffico si registra prima del punto. */
  private suspend fun pump(round: Round, start: VersionJob, writer: BackupTempWriter, from: Long, source: MediaBytes): Pumped {
    var job = start
    var offset = from
    var lastPoint = from
    var unrecorded = 0L
    try {
      while (offset < job.size) {
        if (!round.mayContinue()) {
          if (offset > lastPoint) {
            val cp = writer.checkpoint()
            if (cp is NasResult.Ok && cp.value.length == offset) {
              round.traffic(unrecorded)
              unrecorded = 0
              job = saveJob(job.copy(resumeAt = offset, resumeId = cp.value.fileId))
            }
          }
          return Pumped.Then(Step.Wait())
        }
        val n = minOf(config.chunkBytes.toLong(), job.size - offset).toInt()
        val bytes = try {
          source.read(offset until offset + n)
        } catch (_: IOException) {
          return Pumped.SourceFailed(job)
        }
        if (bytes.size != n) return Pumped.SourceFailed(job)
        when (val w = writer.write(offset, bytes, 0, n)) {
          is NasResult.Err -> return failedWrite(job, w.error)
          is NasResult.Ok -> Unit
        }
        round.uploaded += n
        unrecorded += n
        offset += n
        if (offset - lastPoint >= config.checkpointBytes && offset < job.size) {
          when (val cp = writer.checkpoint()) {
            is NasResult.Err -> return failedWrite(job, cp.error)
            is NasResult.Ok -> {
              round.traffic(unrecorded)
              unrecorded = 0
              if (cp.value.length != offset) return Pumped.Then(save(job.copy(resumeAt = 0, resumeId = null)))
              job = saveJob(job.copy(resumeAt = offset, resumeId = cp.value.fileId))
              lastPoint = offset
            }
          }
        }
      }
      return when (val f = writer.finish(job.size)) {
        is NasResult.Err -> failedWrite(job, f.error)
        is NasResult.Ok -> when (f.value) {
          is TempFinish.Complete -> Pumped.Done(job)
          is TempFinish.SizeMismatch -> Pumped.Then(save(job.copy(resumeAt = 0, resumeId = null)))
        }
      }
    } finally {
      if (unrecorded > 0 && currentCoroutineContext().isActive) round.traffic(unrecorded)
    }
  }

  private fun failedWrite(job: VersionJob, error: NasError): Pumped =
    if (error == NasError.NasFull || error == NasError.QuotaExceeded) Pumped.DiskFull(job, error) else Pumped.Then(stopFor(job, error))

  /** E-R9: disco o quota pieni a meta'. "Da capo" nel diario, il proprio temporaneo si toglie, il giro si ferma. */
  private suspend fun diskFull(job: VersionJob, error: NasError): Step {
    val again = saveJob(job.copy(resumeAt = 0, resumeId = null))
    again.temp?.let { temp ->
      if (ops.deleteOwnTemp(again.share, temp, purpose) !is NasResult.Ok) GalleryLog.w(TAG) { "temporaneo non tolto dopo il disco pieno" }
    }
    return Step.Stop(RunStop.NAS, UploadNames.problemFor(error))
  }

  // --- VERSION, UPLOADED: verifica completa del temporaneo (D4) -------------------------------------------------------------------

  private suspend fun verify(round: Round, job: VersionJob): Step {
    if (job.verifyAttempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) return verifyFailed(job)
    val temp = job.temp ?: return save(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
    val expected = job.sha256 ?: return Step.Wait()
    return when (val read = readSha(round, job.share, temp)) {
      Read.Missing -> save(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
      is Read.Fail -> stopFor(job, read.error)
      is Read.Ok -> if (read.sha == expected && read.stamp.size == job.size) {
        round.dropRaw(job)
        save(job.copy(state = UploadState.VERIFIED, tempStamp = read.stamp))
      } else {
        val attempts = job.verifyAttempts + 1
        GalleryLog.w(TAG) { "verifica non tornata, tentativo $attempts di ${UploadStateMachine.MAX_VERIFY_ATTEMPTS}" }
        if (attempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) {
          verifyFailed(saveJob(job.copy(verifyAttempts = attempts)))
        } else {
          val again = saveJob(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null, verifyAttempts = attempts))
          dropTemp(again, temp) ?: Step.Next(again)
        }
      }
    }
  }

  private suspend fun verifyFailed(job: VersionJob): Step = fail(job, BackupProblemCode.VERIFY_FAILED)

  // --- VERSION, VERIFIED: il piano si ricalcola, poi ARCHIVING (il nome in Versioni/ si scrive prima) o PUBLISHING ------------------------

  private suspend fun afterVerified(job: VersionJob): Step {
    if (job.current == null) {
      // VERSION_DIRECT: nessun attuale da spostare; il nome finale si scrive prima di pubblicare.
      val final = job.finalPath ?: finalFor(job)
      return save(job.copy(state = UploadState.PUBLISHING, finalPath = final))
    }
    // Un ritorno al caricamento dopo la pubblicazione (il nome finale e' gia' scelto, l'attuale gia' archiviato o niente da archiviare):
    // niente si ricalcola e niente si sposta una seconda volta.
    if (job.archivedStamp != null || job.finalPath != null) return save(job.copy(state = UploadState.ARCHIVING))
    val plan = replan(job)
    return save(plan.job.copy(state = UploadState.ARCHIVING, archivePath = plan.archiveName, archiveAttempt = 0, hold = null))
  }

  /** Il piano ricalcolato: il lavoro (destinazione, attuale) e il nome in `Versioni/` dell'attuale da spostare (null = niente da spostare). */
  private class Replan(val job: VersionJob, val archiveName: SharePath?)

  /**
   * "Il piano si ricalcola" (§2.4) dal catalogo locale: se la versione non e' piu' l'attuale (una modifica piu' nuova, quella del PC
   * registrata dalla scansione, E-E6) va in `Versioni/` e il solito percorso non si tocca; se lo e', si sposta in `Versioni/` il file che il
   * catalogo conosce al solito percorso (della **stessa** media, mai un file di altri) con il suo timbro di adesso.
   */
  private suspend fun replan(job: VersionJob): Replan {
    val media = catalog.media(job.mediaId) ?: return Replan(job, job.current?.let { archiveNameFor(job.usualPath, it) })
    if (media.currentVersionId != job.versionId) return Replan(job.copy(target = VersionTarget.VERSIONS_FOLDER), null)
    val atUsual = media.versions.firstNotNullOfOrNull { v ->
      v.nas.firstOrNull { VersionPlanning.isOwnPrivate(it, job.share) && it.path.value.equals(job.usualPath.value, ignoreCase = true) }
        ?.let { v to it }
    }
    if (atUsual == null || atUsual.first.versionId == job.versionId) {
      // Il solito percorso e' libero per il catalogo (o ha gia' questa versione): si pubblica li', senza spostare niente.
      return Replan(job.copy(target = VersionTarget.USUAL_PATH), null)
    }
    val (version, copy) = atUsual
    val fresh = CurrentFile(version.versionId, copy.locationId, copy.stamp, original = version.kind == VersionKind.ORIGINAL)
    return Replan(job.copy(target = VersionTarget.USUAL_PATH, current = fresh), archiveNameFor(job.usualPath, fresh))
  }

  private fun archiveNameFor(usualPath: SharePath, current: CurrentFile): SharePath =
    VersionNames.inVersions(usualPath, if (current.original) VersionNames.ORIGINAL_LABEL else VersionNames.versionLabel(current.stamp.modifiedAtMillis, zone))

  /** Dove si pubblica la versione nuova: il solito percorso, o il suo nome in `Versioni/` ("(originale)" o "(versione del …)"). */
  private suspend fun finalFor(job: VersionJob): SharePath = when (job.target) {
    VersionTarget.USUAL_PATH -> job.usualPath
    VersionTarget.VERSIONS_FOLDER -> versionsNameFor(job, 0)
  }

  private suspend fun versionsNameFor(job: VersionJob, attempt: Int): SharePath {
    val original = catalog.media(job.mediaId)?.version(job.versionId)?.kind == VersionKind.ORIGINAL
    val at = job.tempStamp?.modifiedAtMillis ?: clock.nasNowMs() ?: 0L
    val base = VersionNames.inVersions(job.usualPath, if (original) VersionNames.ORIGINAL_LABEL else VersionNames.versionLabel(at, zone))
    return VersionNames.withAttempt(base, attempt)
  }

  // --- ARCHIVING: l'attuale in Versioni/ (VERSION e SWAP) -----------------------------------------------------------------------------

  private suspend fun archive(round: Round, job: VersionJob): Step {
    if (job.archivedStamp != null) return save(job.copy(state = UploadState.ARCHIVED))
    if (job.hold == VersionHold.SOURCE_CHANGED) return recalculateHeld(job)
    val archivePath = job.archivePath ?: return save(job.copy(state = UploadState.ARCHIVED, archivedStamp = null))
    val current = job.current ?: return save(job.copy(state = UploadState.ARCHIVED, archivedStamp = null))
    // F2 della prova della 5B (`docs/prova-500.md` §C6): il file da spostare puo' essere la copia di un altro elemento ancora aperto della sua
    // versione (il guardiano o il caricamento che l'ha gia' attaccata al catalogo e non ha ancora scritto `LINKED`, un processo morto in mezzo).
    // Spostarla adesso gli farebbe trovare il nome vuoto o con altri byte e rifare la copia: un doppione. Si aspetta che quell'elemento finisca.
    if (journal.busy(current.versionId)) return Step.Wait()
    // D17, P0-B: la cartella in Versioni/ si crea prima (la rinomina non crea cartelle). Idempotente.
    archivePath.parent()?.let { dir ->
      when (val made = ops.mkdirs(job.share, dir, purpose)) {
        is NasResult.Err -> return if (made.error == NasError.Exists) Step.Wait() else stopFor(job, made.error)
        is NasResult.Ok -> Unit
      }
    }
    val r = ops.archiveVersion(job.share, job.usualPath, archivePath, current.stamp, purpose)
    if (r is NasResult.Err) return if (r.error == NasError.NotFound) Step.Next(job) else stopFor(job, r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is RenameOutcome.Renamed -> archived(job, o.stamp ?: current.stamp)
      RenameOutcome.Collision -> when (val st = stat(job.share, archivePath)) {
        is Stat.Fail -> stopFor(job, st.error)
        // Sparito nel frattempo: si riprova con lo stesso nome.
        Stat.Missing -> Step.Next(job)
        is Stat.Found -> {
          // Un altro file con quel nome in Versioni/: mai sostituito, il nome dopo (scritto prima di riprovare).
          val attempt = job.archiveAttempt + 1
          if (attempt > config.maxNameAttempts) {
            Step.Wait()
          } else {
            save(job.copy(archiveAttempt = attempt, archivePath = VersionNames.withAttempt(archiveNameFor(job.usualPath, current), attempt)))
          }
        }
      }
      // La risposta persa (D3, §2.4): sorgente assente e a destinazione il file del lavoro (identita' e timbro atteso, o una rilettura: F3) = fatto.
      RenameOutcome.SourceMissing -> when (val st = stat(job.share, archivePath)) {
        is Stat.Fail -> stopFor(job, st.error)
        is Stat.Found -> when (val moved = movedFile(round, job, archivePath, st.stamp, current.stamp, current.versionId)) {
          is Moved.Mine -> archived(job, moved.stamp)
          Moved.NotMine -> sourceChanged(job)
          is Moved.Exit -> moved.step
        }
        Stat.Missing -> sourceChanged(job)
      }
      // D17: l'attuale e' stato modificato (o sostituito) dal PC: niente si sposta.
      is RenameOutcome.SourceChanged -> sourceChanged(job)
      RenameOutcome.Locked, RenameOutcome.FolderInUse -> lockedWait(round, job)
    }
  }

  /**
   * L'attuale e' in `Versioni/`: la sua posizione nel catalogo lo segue **subito** (idempotente: la ripresa la rifa'), poi il diario dice
   * `ARCHIVED`. Cosi' anche un giro fermato qui (congelamento, fine della tappa) lascia il catalogo d'accordo con il NAS.
   */
  private suspend fun archived(job: VersionJob, stamp: NasStamp): Step {
    val moved = job.copy(archivedStamp = stamp, hold = null)
    relocateArchived(moved)
    return save(moved.copy(state = UploadState.ARCHIVED))
  }

  /** `SOURCE_CHANGED`: niente e' stato spostato. Una versione aspetta il piano ricalcolato; uno scambio finisce (il piano confermato non vale). */
  private suspend fun sourceChanged(job: VersionJob): Step {
    GalleryLog.w(TAG) { "il file al solito percorso non e' quello del diario: niente spostato (${job.kind.code})" }
    if (job.kind == UploadKind.SWAP) return save(job.copy(state = UploadState.FAILED, hold = VersionHold.SOURCE_CHANGED))
    saveJob(job.copy(hold = VersionHold.SOURCE_CHANGED))
    return Step.Wait(held = true)
  }

  /**
   * Un lavoro fermo con `SOURCE_CHANGED` (l'ultima risposta del NAS ha detto che niente si e' spostato): si ricalcola il piano dal catalogo.
   * Finche' il catalogo non ha registrato il file del PC (la scansione), il piano e' lo stesso e si aspetta.
   */
  private suspend fun recalculateHeld(job: VersionJob): Step {
    val plan = replan(job)
    val same = plan.job.target == job.target && plan.job.current == job.current && plan.archiveName != null
    if (same) return Step.Wait(held = true)
    return save(plan.job.copy(archivePath = plan.archiveName, archiveAttempt = 0, hold = null))
  }

  // --- VERSION, ARCHIVED: il catalogo segue il file, poi il nome finale --------------------------------------------------------------

  private suspend fun afterArchived(job: VersionJob): Step {
    relocateArchived(job)
    val final = job.finalPath ?: finalFor(job)
    return save(job.copy(state = UploadState.PUBLISHING, finalPath = final))
  }

  /** La posizione dell'attuale segue il file in `Versioni/` (stessa posizione, la verifica resta). Idempotente. */
  private suspend fun relocateArchived(job: VersionJob) {
    val stamp = job.archivedStamp ?: return
    val path = job.archivePath ?: return
    val location = job.current?.locationId ?: return
    catalog.relocate(location, job.share, path, stamp)
  }

  // --- VERSION, PUBLISHING: pubblicazione senza sostituire ---------------------------------------------------------------------------

  private suspend fun publish(round: Round, job: VersionJob): Step {
    val temp = job.temp ?: return Step.Wait()
    val final = job.finalPath ?: return save(job.copy(finalPath = finalFor(job)))
    // Difesa: si pubblica solo al solito percorso del media o in Versioni/ della stessa share.
    if (!final.value.equals(job.usualPath.value, ignoreCase = true) && !VersionPaths.isInVersions(final)) error("Destinazione fuori posto.")
    val r = ops.publishTemp(job.share, temp, final, purpose)
    if (r is NasResult.Err) return if (r.error == NasError.Exists) Step.Wait() else stopFor(job, r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is PublishOutcome.Published -> {
        val stamp = o.stamp ?: when (val st = stat(job.share, final)) {
          is Stat.Fail -> return stopFor(job, st.error)
          Stat.Missing -> return Step.Wait()
          is Stat.Found -> st.stamp
        }
        save(job.copy(state = UploadState.PUBLISHED, finalStamp = stamp))
      }
      PublishOutcome.Locked -> lockedWait(round, job)
      PublishOutcome.Collision -> collision(round, job, temp, final)
      // La risposta persa: temporaneo assente + finale con dimensione e SHA-256 giusti = pubblicato.
      PublishOutcome.TempMissing -> when (val existing = readSha(round, job.share, final)) {
        Read.Missing -> save(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
        is Read.Fail -> stopFor(job, existing.error)
        is Read.Ok -> if (existing.sha == job.sha256 && existing.stamp.size == job.size) {
          save(job.copy(state = UploadState.PUBLISHED, finalStamp = existing.stamp))
        } else {
          save(elsewhere(job, final).copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
        }
      }
    }
  }

  /**
   * Il nome finale e' occupato. Si rilegge il file che c'e': identico = la versione c'era gia', si collega; diverso = **mai sostituito**:
   * se era il solito percorso (un file di altri, messo li' dal PC) la versione va in `Versioni/`, altrimenti il nome dopo.
   */
  private suspend fun collision(round: Round, job: VersionJob, temp: SharePath, final: SharePath): Step =
    when (val existing = readSha(round, job.share, final)) {
      Read.Missing -> Step.Next(job)
      is Read.Fail -> stopFor(job, existing.error)
      is Read.Ok -> if (existing.sha == job.sha256 && existing.stamp.size == job.size) {
        dropTemp(job, temp) ?: run {
          val at = clock.nasNowMs() ?: return@run Step.Wait()
          if (!catalog.attachVerifiedCopy(VerifiedCopy(job.mediaId, job.versionId, job.share, final, existing.stamp, at))) return@run Step.Wait()
          afterLinked(job)
          save(job.copy(state = UploadState.LINKED_EXISTING, finalStamp = existing.stamp))
        }
      } else {
        val moved = elsewhere(job, final)
        if (moved.nameAttempt > config.maxNameAttempts) Step.Wait() else save(moved)
      }
    }

  /** La versione non va piu' a [final] (occupato da un altro file): in `Versioni/`, al primo nome libero (scritto prima di riprovare). */
  private suspend fun elsewhere(job: VersionJob, final: SharePath): VersionJob =
    if (final.value.equals(job.usualPath.value, ignoreCase = true)) {
      GalleryLog.w(TAG) { "il solito percorso e' occupato da un altro file: la versione va in Versioni/" }
      val moved = job.copy(target = VersionTarget.VERSIONS_FOLDER, nameAttempt = 0, hold = VersionHold.USUAL_PATH_TAKEN)
      moved.copy(finalPath = versionsNameFor(moved, 0))
    } else {
      val attempt = job.nameAttempt + 1
      job.copy(nameAttempt = attempt, finalPath = versionsNameFor(job, attempt))
    }

  // --- VERSION, PUBLISHED / LINKED: timbro, catalogo ("al sicuro"), attivita' in sospeso --------------------------------------------

  private suspend fun link(round: Round, job: VersionJob): Step {
    val final = job.finalPath ?: return Step.Wait()
    var stamp = when (val st = stat(job.share, final)) {
      is Stat.Fail -> return stopFor(job, st.error)
      Stat.Missing -> return save(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
      is Stat.Found -> st.stamp
    }
    if (job.tempStamp?.sameAs(stamp) != true) {
      // Toccato dopo la pubblicazione: si rilegge tutto; byte diversi = non sono i nostri, mai toccati: la versione si ricarica altrove.
      when (val read = readSha(round, job.share, final)) {
        Read.Missing -> return save(job.copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null))
        is Read.Fail -> return stopFor(job, read.error)
        is Read.Ok -> {
          if (read.sha != job.sha256 || read.stamp.size != job.size) {
            return save(elsewhere(job, final).copy(state = UploadState.UPLOADING, resumeAt = 0, resumeId = null, finalStamp = null))
          }
          stamp = read.stamp
        }
      }
    }
    val at = clock.nasNowMs() ?: return Step.Wait()
    if (!catalog.attachVerifiedCopy(VerifiedCopy(job.mediaId, job.versionId, job.share, final, stamp, at))) return Step.Wait()
    return save(job.copy(state = UploadState.LINKED, finalStamp = stamp))
  }

  private suspend fun finishLinked(job: VersionJob): Step {
    afterLinked(job)
    return save(job.copy(state = UploadState.DONE))
  }

  /**
   * Dopo il collegamento (idempotente: si rifa' dopo una morte): D20, un video che ha spostato il suo **originale** in `Versioni/` apre
   * l'attivita' in sospeso "L'originale occupa X sul NAS"; un file preparato non serve piu' e si toglie.
   */
  private suspend fun afterLinked(job: VersionJob) {
    val current = job.current
    val archived = job.archivedStamp
    if (current != null && archived != null && current.original && job.target == VersionTarget.USUAL_PATH) {
      if (catalog.media(job.mediaId)?.kind?.isVideo == true) tasks.originalKept(job.mediaId, current.versionId, archived.size)
    }
    (job.source as? VersionSource.Staged)?.let { staged.discard(it.sha256) }
  }

  // --- SWAP ------------------------------------------------------------------------------------------------------------------------

  private suspend fun startSwap(job: VersionJob): Step {
    val current = job.current ?: return save(job.copy(state = UploadState.FAILED, problem = BackupProblemCode.UNEXPECTED))
    return save(job.copy(state = UploadState.ARCHIVING, archivePath = archiveNameFor(job.usualPath, current), archiveAttempt = 0))
  }

  private suspend fun afterArchivedSwap(job: VersionJob): Step {
    relocateArchived(job)
    return save(job.copy(state = UploadState.RESTORING, finalPath = job.usualPath))
  }

  private suspend fun restore(round: Round, job: VersionJob): Step {
    val src = job.source as? VersionSource.InVersions ?: error("Uno scambio riporta una versione da Versioni/.")
    // La cartella del solito percorso puo' essere stata tolta dal PC: si crea prima (idempotente), la rinomina non crea cartelle.
    job.usualPath.parent()?.let { dir ->
      when (val made = ops.mkdirs(job.share, dir, purpose)) {
        is NasResult.Err -> return if (made.error == NasError.Exists) rollbackOrFail(job, VersionHold.USUAL_PATH_TAKEN) else stopFor(job, made.error)
        is NasResult.Ok -> Unit
      }
    }
    val r = ops.restoreVersion(job.share, src.path, job.usualPath, src.stamp, purpose)
    if (r is NasResult.Err) return if (r.error == NasError.NotFound) Step.Next(job) else stopFor(job, r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is RenameOutcome.Renamed -> restored(job, src, o.stamp ?: src.stamp)
      // Il solito percorso e' occupato: o e' il file appena riportato (risposta persa), o e' di altri e non si tocca.
      RenameOutcome.Collision -> when (val st = stat(job.share, job.usualPath)) {
        is Stat.Fail -> stopFor(job, st.error)
        Stat.Missing -> Step.Next(job)
        is Stat.Found -> atUsualAfterLostRename(round, job, src, st.stamp)
      }
      RenameOutcome.SourceMissing -> when (val st = stat(job.share, job.usualPath)) {
        is Stat.Fail -> stopFor(job, st.error)
        is Stat.Found -> atUsualAfterLostRename(round, job, src, st.stamp)
        Stat.Missing -> rollbackOrFail(job, VersionHold.SOURCE_CHANGED)
      }
      is RenameOutcome.SourceChanged -> rollbackOrFail(job, VersionHold.SOURCE_CHANGED)
      RenameOutcome.Locked, RenameOutcome.FolderInUse -> lockedWait(round, job)
    }
  }

  /**
   * Al solito percorso c'e' un file dopo una rinomina di ripristino la cui risposta e' andata persa: se e' quello riportato (F3: identita' e timbro
   * atteso, o una rilettura) lo scambio continua; altrimenti e' di altri (o il PC l'ha cambiato dopo la rinomina) e non si tocca: lo scambio
   * finisce senza che il catalogo lo segua (la scansione registrera' il file del PC e dichiarera' assente la copia che non c'e' piu', D10).
   */
  private suspend fun atUsualAfterLostRename(round: Round, job: VersionJob, src: VersionSource.InVersions, found: NasStamp): Step {
    // Dopo un ritorno indietro la sorgente dello scambio e' l'attuale archiviato, non la versione del lavoro.
    val versionId = if (job.hold == VersionHold.ROLLED_BACK) job.current?.versionId else job.versionId
    return when (val moved = movedFile(round, job, job.usualPath, found, src.stamp, versionId)) {
      is Moved.Mine -> restored(job, src, moved.stamp)
      Moved.NotMine -> save(job.copy(state = UploadState.FAILED, hold = VersionHold.USUAL_PATH_TAKEN))
      is Moved.Exit -> moved.step
    }
  }

  /** La versione e' tornata al solito percorso: il catalogo la segue subito (idempotente), poi `RESTORED`. */
  private suspend fun restored(job: VersionJob, src: VersionSource.InVersions, stamp: NasStamp): Step {
    src.locationId?.let { catalog.relocate(it, job.share, job.usualPath, stamp) }
    return save(job.copy(state = UploadState.RESTORED, finalStamp = stamp))
  }

  /**
   * L'originale non si puo' riportare: l'attuale **torna al suo posto** (la sorgente dello scambio diventa lui, scritto prima della
   * rinomina) e lo scambio finisce `FAILED` con `ROLLED_BACK`. Se nemmeno l'attuale si puo' riportare il lavoro finisce: entrambi i file
   * restano in `Versioni/`, nessun byte perso.
   */
  private suspend fun rollbackOrFail(job: VersionJob, reason: VersionHold): Step {
    val archived = job.archivedStamp
    val path = job.archivePath
    if (job.hold == VersionHold.ROLLED_BACK || archived == null || path == null) {
      GalleryLog.w(TAG) { "scambio non riuscito e attuale non riportabile: i file restano in Versioni/" }
      return save(job.copy(state = UploadState.FAILED, hold = reason))
    }
    GalleryLog.w(TAG) { "originale non riportabile: l'attuale torna al suo posto" }
    return save(job.copy(source = VersionSource.InVersions(path, archived, job.current?.locationId), hold = VersionHold.ROLLED_BACK))
  }

  private suspend fun afterRestored(job: VersionJob): Step {
    val stamp = job.finalStamp ?: return Step.Wait()
    val src = job.source as VersionSource.InVersions
    src.locationId?.let { catalog.relocate(it, job.share, job.usualPath, stamp) }
    // "Ripristina originale" dall'app: il catalogo rende attuale l'originale. Un ritorno fatto sul telefono (E-E2) l'ha gia' fatto.
    if (job.hold != VersionHold.ROLLED_BACK && catalog.media(job.mediaId)?.original?.versionId == job.versionId) catalog.restoreOriginal(job.mediaId)
    return save(job.copy(state = UploadState.LINKED))
  }

  // --- Temporanei, letture, attese ----------------------------------------------------------------------------------------------

  private suspend fun dropTempIfAny(job: VersionJob): Step? {
    val temp = job.temp ?: return null
    if (job.state !in TEMP_STATES) return null
    return dropTemp(job, temp)
  }

  private suspend fun dropTemp(job: VersionJob, temp: SharePath): Step? = when (val d = ops.deleteOwnTemp(job.share, temp, purpose)) {
    is NasResult.Err -> stopFor(job, d.error)
    is NasResult.Ok -> when (d.value) {
      DeleteOutcome.Deleted, DeleteOutcome.Missing -> null
      DeleteOutcome.Locked -> Step.Wait()
      DeleteOutcome.NotAFile, is DeleteOutcome.SizeChanged -> Step.Wait()
    }
  }

  private suspend fun dropTempQuietly(job: VersionJob) {
    val temp = job.temp ?: return
    if (job.state !in TEMP_STATES) return
    try {
      ops.deleteOwnTemp(job.share, temp, purpose)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      GalleryLog.w(TAG) { "temporaneo non tolto dopo l'errore: lo togliera' la pulizia" }
    }
  }

  private suspend fun markQuietly(mediaId: MediaId, problem: BackupProblemCode) {
    try {
      catalog.markProblem(mediaId, problem)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      GalleryLog.w(TAG) { "segno del problema non scritto" }
    }
  }

  private fun lockedWait(round: Round, job: VersionJob): Step {
    val n = round.lockedWaits(job)
    return if (n >= config.lockedRetries) Step.Wait() else Step.Wait(config.lockedFirstWaitMs shl n)
  }

  private fun stopFor(job: VersionJob, error: NasError): Step =
    if (error == NasError.FileBusy) Step.Wait() else Step.Stop(RunStop.NAS, UploadNames.problemFor(error))

  /** Lo stesso file (la risposta persa di una rinomina): stesso `fileId` e stessa dimensione; senza identita', dimensione e data. */
  private fun sameIdentity(found: NasStamp, expected: NasStamp): Boolean {
    val a = found.fileId
    val b = expected.fileId
    return if (a != null && b != null) a == b && found.size == expected.size else found.sameAs(expected)
  }

  /** Il file che una rinomina con la risposta persa ha lasciato a destinazione. */
  private sealed interface Moved {
    /** E' il file del lavoro: [stamp] e' il timbro da scrivere nel catalogo (quello atteso, o quello dei byte riletti qui). */
    class Mine(val stamp: NasStamp) : Moved

    /** Non e' il file del lavoro (un altro file, o il suo cambiato dal PC dopo la rinomina): niente del catalogo lo segue. */
    data object NotMine : Moved

    class Exit(val step: Step) : Moved
  }

  /**
   * F3 della prova della 5B (`docs/prova-500.md` §C6; F4-D8, L28): "al sicuro" nasce solo da una rilettura fatta qui o da un timbro **atteso** che
   * coincide, mai da un timbro trovato dopo un'interruzione. Il file a destinazione [path] e' quello del lavoro se ha la sua identita' **e** il
   * timbro atteso [expected] (dimensione, data e `fileId`: la rinomina non li cambia). Se torna solo l'identita' (stesso `fileId` e stessa
   * dimensione, un'altra data: il PC l'ha toccato fra la rinomina e la ripresa, magari riscrivendo un byte) vale solo una **rilettura completa**
   * con lo SHA-256 della versione [versionId]: byte uguali = e' il suo, con il timbro letto; diversi, o l'impronta della versione non nota = non
   * e' il suo, e il chiamante lo tratta come un file cambiato dal PC (lo stesso esito di una modifica di un'altra dimensione, O1).
   */
  private suspend fun movedFile(round: Round, job: VersionJob, path: SharePath, found: NasStamp, expected: NasStamp, versionId: VersionId?): Moved {
    if (!sameIdentity(found, expected)) return Moved.NotMine
    if (found.sameAs(expected)) return Moved.Mine(found)
    val sha = versionId?.let { catalog.media(job.mediaId)?.version(it)?.fullHash } ?: return Moved.NotMine
    GalleryLog.w(TAG) { "il file spostato ha un'altra data: si rilegge prima di seguirlo (${job.kind.code})" }
    return when (val read = readSha(round, job.share, path)) {
      // Sparito fra il timbro e la lettura: si rifa' il passo, che lo vedra' assente.
      Read.Missing -> Moved.Exit(Step.Next(job))
      is Read.Fail -> Moved.Exit(stopFor(job, read.error))
      is Read.Ok -> if (read.sha == sha && sameIdentity(read.stamp, expected)) Moved.Mine(read.stamp) else Moved.NotMine
    }
  }

  private sealed interface Read {
    class Ok(val sha: Sha256, val stamp: NasStamp) : Read

    data object Missing : Read

    class Fail(val error: NasError) : Read
  }

  /** Rilettura completa dal NAS (D4: `readAll`, mai una cache); il traffico si conta. */
  private suspend fun readSha(round: Round, share: String, path: SharePath): Read {
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) Read.Missing else Read.Fail(opened.error)
      is NasResult.Ok -> opened.value
    }
    return file.use { f ->
      var bytes = 0L
      val r = reader.readAll(f, { _, n -> bytes += n }, null)
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

  private suspend fun stat(share: String, path: SharePath): Stat = when (val r = reader.stat(share, path, purpose)) {
    is NasResult.Err -> if (r.error == NasError.NotFound) Stat.Missing else Stat.Fail(r.error)
    is NasResult.Ok -> r.value?.takeIf { !it.isDirectory }?.let { Stat.Found(NasStamp(it.size, it.modifiedAt, it.fileId)) } ?: Stat.Missing
  }

  // --- Il giro ---------------------------------------------------------------------------------------------------------------

  private inner class Round(val deadline: Long, private val control: UploadControl) {
    var done = 0
    var gone = 0
    var failed = 0
    var waiting = 0
    var held = 0
    var uploaded = 0L
    var halt: Pair<RunStop, BackupProblemCode?>? = null
    private val locked = HashMap<Long, Int>()
    private val readFailures = HashMap<Long, Int>()
    private val raw = HashMap<Long, Pair<Sha256, ByteArray>>()

    fun stopWith(stop: RunStop, problem: BackupProblemCode?) {
      if (halt == null) halt = stop to problem
    }

    fun stopForGate(state: BackupGateState) {
      if (state == BackupGateState.PAUSED) stopWith(RunStop.STOPPED, null) else stopWith(RunStop.GATE, state.problem)
    }

    suspend fun mayContinue(): Boolean {
      if (halt != null) return false
      if (stopRequested) {
        stopWith(RunStop.STOPPED, null)
        return false
      }
      if (clock.monotonicMs() >= deadline || !control.proceed()) {
        stopWith(RunStop.BUDGET, null)
        return false
      }
      return true
    }

    suspend fun traffic(bytes: Long) {
      if (bytes > 0) control.traffic(bytes)
    }

    fun count(job: VersionJob) {
      when (job.state) {
        UploadState.DONE, UploadState.LINKED_EXISTING -> done++
        UploadState.GONE -> gone++
        UploadState.FAILED -> failed++
        else -> Unit
      }
      raw.remove(job.id.value)
    }

    fun lockedWaits(job: VersionJob): Int {
      val n = locked[job.id.value] ?: 0
      locked[job.id.value] = n + 1
      return n
    }

    fun readFailed(job: VersionJob): Int {
      val n = (readFailures[job.id.value] ?: 0) + 1
      readFailures[job.id.value] = n
      return n
    }

    fun keepRaw(job: VersionJob, sha256: Sha256, bytes: ByteArray) {
      if (bytes.size.toLong() == job.size) raw[job.id.value] = sha256 to bytes
    }

    fun rawOf(job: VersionJob): ByteArray? = raw[job.id.value]?.takeIf { it.first == job.sha256 && it.second.size.toLong() == job.size }?.second

    fun dropRaw(job: VersionJob) {
      raw.remove(job.id.value)
    }

    fun finish(gateState: BackupGateState?): VersionRunResult {
      gateState?.let { stopForGate(it) }
      val (stop, problem) = halt ?: (RunStop.COMPLETE to null)
      return VersionRunResult(done, gone, failed, waiting, held, uploaded, stop, problem)
    }
  }

  private companion object {
    const val TAG = "BackupVersions"

    /** Gli stati in cui il proprio temporaneo puo' esistere (si toglie prima di un'uscita). */
    val TEMP_STATES = setOf(UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED)

    /** Prima di questi stati il lavoro non ha spostato niente: un errore inatteso lo puo' parcheggiare. */
    val BEFORE_MOVING = setOf(UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED)
  }
}
