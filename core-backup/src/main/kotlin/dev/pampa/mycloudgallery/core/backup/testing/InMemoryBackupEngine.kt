package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
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
import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
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
import java.security.MessageDigest
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * L'**esecutore di riferimento** del backup (Fase 5A, P0): la macchina del §2.2 sopra `NasBackupOps` (il NAS finto) e [PhoneBytes] (il
 * telefono finto), con cui P6 costruisce il banco delle 500 interruzioni prima che arrivino P2 e P3. Piu' semplice di quello vero (un
 * elemento alla volta, niente preparazione ne' indice, la deduplica senza `qh1` letto qui, il guardiano senza ripiego dal telefono) ma
 * con le **stesse regole di sicurezza**, che i test (`EveryCallTest`) e il verificatore degli invarianti ([BackupInvariants]) provano:
 *
 * - **il diario si scrive prima di ogni effetto**: il temporaneo e il punto di ripresa 0 prima di `openTemp`, il punto di ripresa solo
 *   dopo che lo scrittore ha confermato la lunghezza sullo stesso handle, il nome finale prima di `publishTemp`, "da capo" prima di
 *   togliere un temporaneo che si rifa';
 * - **nessun file visibile non verificato**: si rilegge per intero il temporaneo (SHA-256 uguale a quello dei byte del telefono) prima
 *   di pubblicarlo; dopo, basta il timbro (dimensione, data, `fileId`); un timbro diverso = rilettura completa;
 * - **mai una sostituzione**: `publishTemp` non sostituisce; nome occupato da un file **identico** = collegato (`LINKED_EXISTING`), da
 *   uno diverso = "nome (2)" scritto nel diario prima di riprovare (E-B3);
 * - **nessun doppione**: una copia privata identica gia' nota al catalogo si collega dopo la rilettura (D8), la risposta persa della
 *   pubblicazione si riconosce (temporaneo assente + finale con i byte giusti = pubblicato, D3);
 * - **ripresa** dall'ultimo punto (D5): `reopenTemp(keep = punto)`; temporaneo assente o corto = da zero;
 * - riga del telefono sparita prima che il temporaneo sia completo = `GONE` (E-B4, con `LOST_BEFORE_BACKUP`; con l'`_ID` vecchio, E-P12, solo
 *   quando il catalogo ha riletto il telefono e la riga non c'e' piu', F1), timbro cambiato = `GONE`
 *   senza problema (E-B15: la versione nuova ha il suo elemento); verifica fallita tre volte = `FAILED` con `VERIFY_FAILED` (E-B18);
 * - un errore del NAS ferma il giro, non l'elemento; il cancello si controlla prima di ogni elemento (D32).
 *
 * Non cattura mai `Error` (la "morte del processo" dei test passa): solo gli errori di I/O della lettura del telefono.
 */
class InMemoryBackupEngine(
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
  private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
  private val checkpointBytes: Long = DEFAULT_CHECKPOINT_BYTES,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : BackupEngine {

  init {
    require(chunkBytes > 0 && checkpointBytes >= chunkBytes) { "Misure di scrittura non valide." }
    require(ops.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  private val state = MutableStateFlow<BackupStatus>(BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)))

  override val status: StateFlow<BackupStatus> = state

  @Volatile private var stopRequested: StopBy? = null

  /** I conteggi di un giro. */
  private class Tally {
    var done = 0
    var linked = 0
    var gone = 0
    var failed = 0
    var uploaded = 0L
    var reread = 0L
  }

  /** L'esito di un passo. */
  private sealed interface Step {
    /** Si va avanti con l'elemento riscritto (eventualmente terminale). */
    class Next(val item: BackupItem) : Step

    /** L'elemento aspetta il prossimo giro (file aperto, ora del NAS mancante, riga del telefono da ritrovare). */
    data object Wait : Step

    /** Il giro si ferma. */
    class Stop(val stop: RunStop, val problem: BackupProblemCode? = null) : Step
  }

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult {
    require(trigger != BackupTrigger.INCOMING) { "I caricamenti in arrivo sono della 5B." }
    stopRequested = null
    val gateState = gate.stateOf(share)
    if (!gateState.open) return finish(Tally(), RunStop.GATE, gateState.problem, waiting = 0)
    val device = folder()
    if (device == null) {
      state.value = BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER))
      return BackupRunResult.nothing(RunStop.GATE)
    }
    val fresh = planner.next(kind, PLAN_PAGE).filter { store.openFor(it.versionId) == null }
    if (fresh.isNotEmpty()) {
      store.create(
        fresh.map { c ->
          NewBackupItem(
            kind = UploadKind.NEW_COPY, mediaId = c.mediaId, versionId = c.versionId, size = c.size, phone = c.phone, expected = c.stamp,
            share = share, planned = planner.destination(c, device), quickHash = c.quickHash,
          )
        },
      )
    }
    return resume(budget, kind)
  }

  override suspend fun resumeAll(budget: Duration): BackupRunResult {
    stopRequested = null
    return resume(budget, kind = null)
  }

  override suspend fun stop(by: StopBy) {
    stopRequested = by
  }

  private suspend fun resume(budget: Duration, kind: BackupKind?): BackupRunResult {
    val deadline = clock.monotonicMs() + budget.inWholeMilliseconds
    val tally = Tally()
    val waiting = HashSet<BackupItemId>()
    while (true) {
      if (stopRequested != null) return finish(tally, RunStop.STOPPED, null, waiting.size)
      if (clock.monotonicMs() >= deadline) return finish(tally, RunStop.BUDGET, null, waiting.size)
      val item = store.itemsToResume(Int.MAX_VALUE).firstOrNull { it.id !in waiting } ?: return finish(tally, RunStop.COMPLETE, null, waiting.size)
      val gateState = gate.stateOf(item.share)
      if (!gateState.open) return finish(tally, RunStop.GATE, gateState.problem, waiting.size)
      state.value = BackupStatus.Running(
        kind = kind ?: BackupKind.PHOTOS, done = tally.done + tally.linked, total = tally.done + tally.linked + 1,
        bytesDone = tally.uploaded, bytesTotal = tally.uploaded + item.size, etaMillis = null, trigger = BackupTrigger.SCHEDULED,
      )
      when (val step = drive(item, deadline, tally)) {
        Step.Wait -> waiting += item.id
        is Step.Stop -> return finish(tally, step.stop, step.problem, waiting.size)
        is Step.Next -> Unit
      }
    }
  }

  private fun finish(tally: Tally, stop: RunStop, problem: BackupProblemCode?, waiting: Int): BackupRunResult {
    state.value = when {
      problem != null -> BackupStatus.Problem(problem, 0)
      stop == RunStop.STOPPED -> BackupStatus.Stopped(stopRequested ?: StopBy.USER)
      waiting > 0 -> BackupStatus.Waiting(waiting, 0, WaitReason.NEXT_RUN)
      else -> BackupStatus.UpToDate(tally.done + tally.linked, clock.nasNowMs())
    }
    return BackupRunResult(tally.done, tally.linked, tally.gone, tally.failed, waiting, tally.uploaded, tally.reread, stop, problem)
  }

  /** Porta avanti un elemento finche' non e' terminale, aspetta o ferma il giro. */
  private suspend fun drive(start: BackupItem, deadline: Long, tally: Tally): Step {
    var item = start
    while (true) {
      val step = advance(item, deadline, tally)
      if (step !is Step.Next) return step
      item = step.item
      if (item.state.terminal) {
        when (item.state) {
          UploadState.DONE -> tally.done++
          UploadState.LINKED_EXISTING -> tally.linked++
          UploadState.GONE -> tally.gone++
          UploadState.FAILED -> tally.failed++
          else -> Unit
        }
        return step
      }
    }
  }

  private suspend fun advance(item: BackupItem, deadline: Long, tally: Tally): Step = when (item.kind) {
    UploadKind.NEW_COPY -> when (item.state) {
      UploadState.PLANNED -> hash(item)
      UploadState.HASHED -> dedupOrStart(item, tally)
      UploadState.UPLOADING -> upload(item, deadline, tally)
      UploadState.UPLOADED -> verify(item, tally, back = UploadState.UPLOADING)
      UploadState.VERIFIED -> toPublishing(item)
      UploadState.PUBLISHING -> publish(item, tally, back = UploadState.UPLOADING)
      UploadState.PUBLISHED -> link(item, tally, back = UploadState.UPLOADING)
      UploadState.LINKED -> save(item.copy(state = UploadState.DONE))
      else -> error("Stato ${item.state.code} fuori dal cammino della copia nuova.")
    }
    UploadKind.GUARD_COPY -> when (item.state) {
      UploadState.PLANNED -> save(item.copy(state = UploadState.COPYING, temp = NasBackupPaths.tempFor(deviceId, item.id.value)))
      UploadState.COPYING -> guardCopy(item)
      UploadState.COPIED -> verify(item, tally, back = UploadState.COPYING)
      UploadState.VERIFIED -> toPublishing(item)
      UploadState.PUBLISHING -> publish(item, tally, back = UploadState.COPYING)
      UploadState.PUBLISHED -> link(item, tally, back = UploadState.COPYING)
      UploadState.LINKED -> save(item.copy(state = UploadState.DONE))
      else -> error("Stato ${item.state.code} fuori dal cammino del guardiano.")
    }
    // La 5B non si esegue qui (il diario non li accetta comunque).
    UploadKind.VERSION, UploadKind.SWAP, UploadKind.INCOMING -> Step.Wait
  }

  private suspend fun saveItem(item: BackupItem): BackupItem {
    store.update(item)
    return item
  }

  private suspend fun save(item: BackupItem): Step = Step.Next(saveItem(item))

  // --- PLANNED: lettura completa dal telefono ---------------------------------------------------------------------------------

  private suspend fun hash(item: BackupItem): Step {
    val ref = item.phone ?: return Step.Wait
    val file = when (val opened = phone.open(ref)) {
      PhoneOpen.Stale -> return refreshPhone(item)
      PhoneOpen.Gone -> return gone(item, lost = true)
      is PhoneOpen.Denied -> return Step.Stop(RunStop.PHONE, opened.reason.problem)
      is PhoneOpen.Opened -> opened.file
    }
    return file.use {
      if (file.stamp != item.expected) return gone(item, lost = false)
      val sha = try {
        sha256(file.bytes, item.size)
      } catch (_: IOException) {
        return afterPhoneFailure(item)
      } ?: return afterPhoneFailure(item)
      // Il timbro uguale prima e dopo la lettura (§2.2): un file riscritto a meta' non e' la versione.
      when (val after = phone.lookup(ref)) {
        is PhoneLookup.Present -> if (after.stamp != item.expected) return gone(item, lost = false)
        PhoneLookup.Gone -> return gone(item, lost = true)
        PhoneLookup.Stale -> return refreshPhone(item)
        is PhoneLookup.Denied -> return Step.Stop(RunStop.PHONE, after.reason.problem)
      }
      when (catalog.recordFullHash(item.mediaId, item.versionId, sha)) {
        HashRecord.RECORDED, HashRecord.SAME -> Unit
        // I byte letti non sono quelli della versione (o la versione non c'e' piu'): questa versione non si salva da qui.
        HashRecord.CONFLICT, HashRecord.UNKNOWN_VERSION -> return gone(item, lost = false)
      }
      return save(item.copy(state = UploadState.HASHED, sha256 = sha))
    }
  }

  /**
   * La versione del MediaStore e' cambiata (E-P12): la riga nuova dal catalogo; la stessa (catalogo in ritardo) o la lettura del telefono
   * non ancora finita = si aspetta; assente dopo una rilettura completa = `GONE` (F1, docs/prova-500.md §B6), "persa prima del backup"
   * solo se il media non e' piu' da nessuna parte.
   */
  private suspend fun refreshPhone(item: BackupItem): Step {
    val stale = item.phone ?: return Step.Wait
    return when (val now = catalog.phoneRowOf(item.mediaId, item.versionId, stale)) {
      is PhoneRowNow.At -> if (now.ref != stale) save(item.copy(phone = now.ref)) else Step.Wait
      PhoneRowNow.Rereading -> Step.Wait
      PhoneRowNow.Absent -> gone(item, lost = catalog.mediaSafety(item.mediaId)?.status == SafetyStatus.LOST)
    }
  }

  /** Una lettura del telefono e' fallita a meta': si guarda la riga e si decide. */
  private suspend fun afterPhoneFailure(item: BackupItem): Step {
    val ref = item.phone ?: return Step.Wait
    return when (val now = phone.lookup(ref)) {
      PhoneLookup.Gone -> gone(item, lost = true)
      is PhoneLookup.Present -> if (now.stamp != item.expected) gone(item, lost = false) else Step.Wait
      PhoneLookup.Stale -> refreshPhone(item)
      is PhoneLookup.Denied -> Step.Stop(RunStop.PHONE, now.reason.problem)
    }
  }

  /** `GONE`: prima si toglie il proprio temporaneo incompleto (citato dal diario finche' l'elemento e' aperto), poi l'uscita. */
  private suspend fun gone(item: BackupItem, lost: Boolean): Step {
    val temp = item.temp
    if (temp != null && (item.state == UploadState.UPLOADING || item.state == UploadState.COPYING)) {
      when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
        is NasResult.Err -> return stopFor(d.error)
        is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) return Step.Wait
      }
    }
    if (lost) catalog.markProblem(item.mediaId, BackupProblemCode.LOST_BEFORE_BACKUP)
    return save(item.copy(state = UploadState.GONE, problem = if (lost) BackupProblemCode.LOST_BEFORE_BACKUP else null))
  }

  // --- HASHED: deduplica (D8) o inizio del caricamento ----------------------------------------------------------------------

  private suspend fun dedupOrStart(item: BackupItem, tally: Tally): Step {
    val sha = item.sha256 ?: return Step.Wait
    for (copy in catalog.privateCopies(item.share, item.size, item.quickHash, DEDUP_LIMIT)) {
      if (!copy.share.equals(item.share, ignoreCase = true)) continue
      when (val read = readSha(copy.share, copy.path, tally)) {
        Read.Missing -> continue
        is Read.Fail -> return stopFor(read.error)
        is Read.Ok -> if (read.sha == sha && read.stamp.size == item.size) {
          val at = clock.nasNowMs() ?: return Step.Wait
          catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, copy.share, copy.path, read.stamp, at)))
          return save(item.copy(state = UploadState.LINKED_EXISTING, finalPath = copy.path, finalStamp = read.stamp))
        }
      }
    }
    // §2.2 "Spazio": prima di un file oltre 1 GB lo spazio deve bastare con 1 GiB di margine (tocca i dischi: solo qui).
    if (item.size > SPACE_CHECK_BYTES) {
      when (val space = ops.space(item.share, purpose)) {
        is NasResult.Err -> return stopFor(space.error)
        is NasResult.Ok -> if (space.value.callerFreeBytes < item.size + SPACE_MARGIN_BYTES) {
          return Step.Stop(RunStop.NAS, if (space.value.quotaLimited) BackupProblemCode.QUOTA else BackupProblemCode.NAS_FULL)
        }
      }
    }
    return save(item.copy(state = UploadState.UPLOADING, temp = NasBackupPaths.tempFor(deviceId, item.id.value), resumeAt = 0))
  }

  // --- UPLOADING: scrittura del temporaneo con il punto di ripresa (D5) --------------------------------------------------------

  private suspend fun upload(start: BackupItem, deadline: Long, tally: Tally): Step {
    val temp = start.temp ?: return save(start.copy(temp = NasBackupPaths.tempFor(deviceId, start.id.value), resumeAt = 0))
    val ref = start.phone ?: return Step.Wait
    val file = when (val opened = phone.open(ref)) {
      PhoneOpen.Stale -> return refreshPhone(start)
      PhoneOpen.Gone -> return gone(start, lost = true)
      is PhoneOpen.Denied -> return Step.Stop(RunStop.PHONE, opened.reason.problem)
      is PhoneOpen.Opened -> opened.file
    }
    return file.use {
      if (file.stamp != start.expected) return gone(start, lost = false)
      var item = start
      val writer: BackupTempWriter
      var offset: Long
      if (item.resumeAt > 0) {
        // R3 (revisione 5A): senza l'identita' del temporaneo al punto di ripresa non si accorcia niente.
        val tempId = item.resumeId ?: return save(item.copy(resumeAt = 0))
        when (val r = ops.reopenTemp(item.share, temp, item.resumeAt, tempId, purpose)) {
          is NasResult.Err -> return stopFor(r.error)
          is NasResult.Ok -> when (val o = r.value) {
            is ReopenOutcome.Reopened -> {
              writer = o.writer
              offset = item.resumeAt
            }
            // Assente, piu' corto del punto salvato o un altro file al suo nome (R3): da capo ("da capo" nel diario prima di tutto).
            ReopenOutcome.Missing, is ReopenOutcome.TooShort, ReopenOutcome.Foreign -> return save(item.copy(resumeAt = 0, resumeId = null))
            ReopenOutcome.Locked -> return Step.Wait
          }
        }
      } else {
        when (val r = ops.openTemp(item.share, temp, purpose)) {
          is NasResult.Ok -> {
            writer = r.value
            offset = 0
          }
          is NasResult.Err -> {
            if (r.error != NasError.Exists) return stopFor(r.error)
            // Il diario dice "da capo": il temporaneo rimasto (senza punto di ripresa) e' solo nostro e si toglie.
            return when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
              is NasResult.Err -> stopFor(d.error)
              is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) Step.Wait else Step.Next(item)
            }
          }
        }
      }
      writer.use {
        var lastPoint = offset
        while (offset < item.size) {
          if (stopRequested != null) return Step.Stop(RunStop.STOPPED)
          if (clock.monotonicMs() >= deadline) return Step.Stop(RunStop.BUDGET)
          val n = minOf(chunkBytes.toLong(), item.size - offset).toInt()
          val bytes = try {
            file.bytes.read(offset until offset + n)
          } catch (_: IOException) {
            return afterPhoneFailure(item)
          }
          if (bytes.size != n) return afterPhoneFailure(item)
          when (val w = writer.write(offset, bytes, 0, n)) {
            is NasResult.Err -> return stopFor(w.error)
            is NasResult.Ok -> Unit
          }
          tally.uploaded += n
          offset += n
          if (offset - lastPoint >= checkpointBytes && offset < item.size) {
            when (val cp = writer.checkpoint()) {
              is NasResult.Err -> return stopFor(cp.error)
              is NasResult.Ok -> if (cp.value.length == offset) {
                // Il punto di ripresa solo dopo la lunghezza confermata sullo stesso handle, con l'identita' letta li' (R3).
                item = saveItem(item.copy(resumeAt = offset, resumeId = cp.value.fileId))
                lastPoint = offset
              } else {
                saveItem(item.copy(resumeAt = 0))
                return Step.Wait
              }
            }
          }
        }
        when (val f = writer.finish(item.size)) {
          is NasResult.Err -> return stopFor(f.error)
          is NasResult.Ok -> if (f.value is TempFinish.SizeMismatch) {
            saveItem(item.copy(resumeAt = 0))
            return Step.Wait
          }
        }
      }
      // Il file del telefono non e' cambiato durante il caricamento (E-B15). Se e' sparito, il temporaneo completo si verifica comunque.
      when (val after = phone.lookup(ref)) {
        is PhoneLookup.Present -> if (after.stamp != item.expected) return gone(item, lost = false)
        else -> Unit
      }
      return save(item.copy(state = UploadState.UPLOADED, resumeAt = item.size))
    }
  }

  // --- GUARD_COPY: copia lato server dall'area comune (D9) ---------------------------------------------------------------------

  private suspend fun guardCopy(item: BackupItem): Step {
    val temp = item.temp ?: return Step.Wait
    val source = item.guardSource ?: return Step.Wait
    return when (val r = ops.copyToTemp(source.share, source.path, item.share, temp, source.stamp, purpose)) {
      is NasResult.Err -> stopFor(r.error)
      is NasResult.Ok -> when (val o = r.value) {
        is CopyOutcome.Copied -> if (o.bytes == item.size) {
          save(item.copy(state = UploadState.COPIED, resumeAt = item.size))
        } else {
          when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
            is NasResult.Err -> stopFor(d.error)
            is NasResult.Ok -> Step.Wait
          }
        }
        CopyOutcome.SourceMissing, is CopyOutcome.SourceChanged -> gone(item, lost = false)
        CopyOutcome.TempExists -> when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
          is NasResult.Err -> stopFor(d.error)
          is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) Step.Wait else Step.Next(item)
        }
        // Il ripiego dal telefono (E-D12) e' di P3.
        CopyOutcome.NotSupported, CopyOutcome.Locked -> Step.Wait
      }
    }
  }

  // --- UPLOADED / COPIED: verifica completa del temporaneo (D4) ----------------------------------------------------------------

  private suspend fun verify(item: BackupItem, tally: Tally, back: UploadState): Step {
    val temp = item.temp ?: return Step.Wait
    val expected = item.sha256 ?: return Step.Wait
    return when (val read = readSha(item.share, temp, tally)) {
      // Il temporaneo non c'e' piu': si rifa', senza contare un tentativo.
      Read.Missing -> save(item.copy(state = back, resumeAt = 0))
      is Read.Fail -> stopFor(read.error)
      is Read.Ok -> if (read.sha == expected && read.stamp.size == item.size) {
        save(item.copy(state = UploadState.VERIFIED, tempStamp = read.stamp))
      } else {
        val attempts = item.verifyAttempts + 1
        if (attempts >= UploadStateMachine.MAX_VERIFY_ATTEMPTS) {
          val counted = saveItem(item.copy(verifyAttempts = attempts))
          when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
            is NasResult.Err -> return stopFor(d.error)
            is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) return Step.Wait
          }
          catalog.markProblem(item.mediaId, BackupProblemCode.VERIFY_FAILED)
          save(counted.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED))
        } else {
          // "Da capo" nel diario prima di togliere il temporaneo che non torna.
          val again = saveItem(item.copy(state = back, resumeAt = 0, verifyAttempts = attempts))
          when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
            is NasResult.Err -> stopFor(d.error)
            is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) Step.Wait else Step.Next(again)
          }
        }
      }
    }
  }

  // --- VERIFIED / PUBLISHING: pubblicazione senza sostituire (D6, E-B3) -------------------------------------------------------

  private suspend fun toPublishing(item: BackupItem): Step =
    save(item.copy(state = UploadState.PUBLISHING, finalPath = item.finalPath ?: nameFor(item.planned, item.nameAttempt)))

  private suspend fun publish(item: BackupItem, tally: Tally, back: UploadState): Step {
    val temp = item.temp ?: return Step.Wait
    val final = item.finalPath ?: return toPublishing(item)
    val r = ops.publishTemp(item.share, temp, final, purpose)
    if (r is NasResult.Err) return stopFor(r.error)
    return when (val o = (r as NasResult.Ok).value) {
      is PublishOutcome.Published -> {
        val stamp = o.stamp ?: when (val st = stat(item.share, final)) {
          is Stat.Fail -> return stopFor(st.error)
          Stat.Missing -> return Step.Wait
          is Stat.Found -> st.stamp
        }
        save(item.copy(state = UploadState.PUBLISHED, finalStamp = stamp))
      }
      PublishOutcome.Locked -> Step.Wait
      PublishOutcome.Collision -> when (val existing = readSha(item.share, final, tally)) {
        Read.Missing -> Step.Next(item)
        is Read.Fail -> stopFor(existing.error)
        is Read.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
          // Lo stesso file c'era gia' (ripresa, reinstallazione, copia dal PC): si collega e il proprio temporaneo si toglie.
          when (val d = ops.deleteOwnTemp(item.share, temp, purpose)) {
            is NasResult.Err -> return stopFor(d.error)
            is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) return Step.Wait
          }
          val at = clock.nasNowMs() ?: return Step.Wait
          catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, item.share, final, existing.stamp, at)))
          save(item.copy(state = UploadState.LINKED_EXISTING, finalStamp = existing.stamp))
        } else {
          // Un altro file con lo stesso nome: "nome (n)" nel diario prima di riprovare. Mai una sostituzione.
          val attempt = item.nameAttempt + 1
          save(item.copy(nameAttempt = attempt, finalPath = nameFor(item.planned, attempt)))
        }
      }
      // La risposta persa (D3): temporaneo assente + finale con dimensione e SHA-256 giusti = pubblicato.
      PublishOutcome.TempMissing -> when (val existing = readSha(item.share, final, tally)) {
        Read.Missing -> save(item.copy(state = back, resumeAt = 0))
        is Read.Fail -> stopFor(existing.error)
        is Read.Ok -> if (existing.sha == item.sha256 && existing.stamp.size == item.size) {
          save(item.copy(state = UploadState.PUBLISHED, finalStamp = existing.stamp))
        } else {
          save(item.copy(state = back, resumeAt = 0))
        }
      }
    }
  }

  // --- PUBLISHED: timbro del finale, poi il catalogo ----------------------------------------------------------------------------

  private suspend fun link(item: BackupItem, tally: Tally, back: UploadState): Step {
    val final = item.finalPath ?: return Step.Wait
    val stamp = when (val st = stat(item.share, final)) {
      is Stat.Fail -> return stopFor(st.error)
      // Sparito dal PC fra la pubblicazione e il collegamento: si ricarica.
      Stat.Missing -> return save(item.copy(state = back, resumeAt = 0))
      is Stat.Found -> st.stamp
    }
    val same = item.tempStamp?.let { stamp.sameAs(it) } == true
    if (!same) {
      // Toccato dopo la rinomina: si rilegge tutto; byte diversi = non sono i nostri, si ricarica con un altro nome.
      when (val read = readSha(item.share, final, tally)) {
        Read.Missing -> return save(item.copy(state = back, resumeAt = 0))
        is Read.Fail -> return stopFor(read.error)
        is Read.Ok -> if (read.sha != item.sha256 || read.stamp.size != item.size) {
          val attempt = item.nameAttempt + 1
          return save(item.copy(state = back, resumeAt = 0, nameAttempt = attempt, finalPath = nameFor(item.planned, attempt)))
        }
      }
    }
    val at = clock.nasNowMs() ?: return Step.Wait
    catalog.attachVerifiedCopies(listOf(VerifiedCopy(item.mediaId, item.versionId, item.share, final, stamp, at)))
    return save(item.copy(state = UploadState.LINKED, finalStamp = stamp))
  }

  // --- Letture e nomi ---------------------------------------------------------------------------------------------------------

  private sealed interface Read {
    class Ok(val sha: Sha256, val stamp: NasStamp) : Read

    data object Missing : Read

    class Fail(val error: NasError) : Read
  }

  /** Rilettura completa dal NAS (mai una cache): SHA-256 e timbro dell'apertura. */
  private suspend fun readSha(share: String, path: SharePath, tally: Tally): Read {
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) Read.Missing else Read.Fail(opened.error)
      is NasResult.Ok -> opened.value
    }
    return file.use { f ->
      when (val r = reader.readAll(f, { _, n -> tally.reread += n }, null)) {
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
    is NasResult.Err -> Stat.Fail(r.error)
    is NasResult.Ok -> r.value?.takeIf { !it.isDirectory }?.let { Stat.Found(NasStamp(it.size, it.modifiedAt, it.fileId)) } ?: Stat.Missing
  }

  /** Lo SHA-256 dei byte del telefono, a pezzi (mai interi in memoria); null se il file e' piu' corto del previsto. */
  private suspend fun sha256(bytes: MediaBytes, size: Long): Sha256? {
    val md = MessageDigest.getInstance("SHA-256")
    var at = 0L
    while (at < size) {
      val n = minOf(chunkBytes.toLong(), size - at)
      val part = bytes.read(at until at + n)
      if (part.size.toLong() != n) return null
      md.update(part)
      at += n
    }
    return Sha256(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
  }

  private fun stopFor(error: NasError): Step = Step.Stop(RunStop.NAS, problemFor(error))

  companion object {
    /** Scritture da 1 MiB (§2.2). */
    const val DEFAULT_CHUNK_BYTES: Int = 1 shl 20

    /** Punto di ripresa ogni 64 MiB (D5). */
    const val DEFAULT_CHECKPOINT_BYTES: Long = 64L shl 20

    /** Oltre 1 GB si controlla lo spazio prima di scrivere (§2.2). */
    const val SPACE_CHECK_BYTES: Long = 1_000_000_000L

    const val SPACE_MARGIN_BYTES: Long = 1L shl 30

    private const val PLAN_PAGE = 500
    private const val DEDUP_LIMIT = 20

    /** Il problema del giro per un errore del NAS (§2.2 "Rete", §2.6). */
    fun problemFor(error: NasError): BackupProblemCode = when (error) {
      NasError.NasFull -> BackupProblemCode.NAS_FULL
      NasError.QuotaExceeded -> BackupProblemCode.QUOTA
      is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing -> BackupProblemCode.AUTH
      NasError.PermissionDenied -> BackupProblemCode.READ_ONLY_AREA
      else -> BackupProblemCode.NAS_UNREACHABLE
    }

    /**
     * Il nome "nome (n)" del tentativo [attempt] (0 = il nome previsto, 1 = "(2)"...), nella stessa cartella: l'estensione resta
     * (E-B3, come i nomi alternativi della 4B). La regola e' quella del motore vero: [BackupPaths.alternateName] (R1, revisione 5A).
     */
    fun nameFor(planned: SharePath, attempt: Int): SharePath = BackupPaths.alternateName(planned, attempt)
  }
}
