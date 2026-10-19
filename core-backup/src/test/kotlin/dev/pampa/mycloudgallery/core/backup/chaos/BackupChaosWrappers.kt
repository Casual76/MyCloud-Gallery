package dev.pampa.mycloudgallery.core.backup.chaos

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.GuardCandidate
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.MediaSafety
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.TempPoint
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// I decoratori che portano i colpi (BackupChaosFaults.kt) dentro le porte del codice sotto prova: le primitive in sola aggiunta del NAS
// (NasBackupOps), la lettura (NasMediaReader), il diario (BackupStore), il catalogo (BackupCatalog) e il telefono (PhoneBytes). Sono tutti
// sottili: contano la chiamata, lasciano che il colpo scatti PRIMA o DOPO, poi passano al vero. Il codice sotto prova non li distingue da
// un NAS che cade; il NAS (finto, simulato o vero) non li vede. Valgono per qualunque motore: quello di riferimento e quello vero.

/** Le primitive del backup con i colpi. */
class ChaosBackupOps(
  private val inner: NasBackupOps,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasBackupOps {
  override val ownerDeviceId: String get() = inner.ownerDeviceId

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> =
    injector.nas(Primitive.MKDIRS, incarnation) { inner.mkdirs(share, dir, purpose) }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
    when (val opened = injector.nas(Primitive.OPEN_TEMP, incarnation) { inner.openTemp(share, temp, purpose) }) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(ChaosBackupWriter(opened.value, injector, incarnation))
    }

  override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> =
    when (val reopened = injector.nas(Primitive.REOPEN_TEMP, incarnation, locked = ReopenOutcome.Locked as ReopenOutcome) { inner.reopenTemp(share, temp, keepBytes, tempId, purpose) }) {
      is NasResult.Err -> reopened
      is NasResult.Ok -> when (val outcome = reopened.value) {
        is ReopenOutcome.Reopened -> NasResult.Ok(ReopenOutcome.Reopened(ChaosBackupWriter(outcome.writer, injector, incarnation)))
        else -> reopened
      }
    }

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> = injector.nas(Primitive.COPY_TO_TEMP, incarnation) { inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose) }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
    injector.nas(Primitive.PUBLISH_TEMP, incarnation, locked = PublishOutcome.Locked as PublishOutcome) { inner.publishTemp(share, temp, final, purpose) }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
    injector.nas(Primitive.DELETE_OWN_TEMP, incarnation, locked = DeleteOutcome.Locked as DeleteOutcome) { inner.deleteOwnTemp(share, temp, purpose) }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> =
    injector.nas(Primitive.SPACE, incarnation) { inner.space(share, purpose) }
}

/** Lo scrittore di un temporaneo: ogni scrittura, punto di ripresa e chiusura del file sono punti dove cade un colpo (e una scrittura puo' alterare un byte). */
class ChaosBackupWriter(
  private val inner: BackupTempWriter,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : BackupTempWriter {
  override val temp: SharePath get() = inner.temp

  override val length: Long get() = inner.length

  override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> =
    injector.nasWrite(Primitive.WRITE_TEMP, incarnation) { corrupt ->
      if (corrupt && len > 0) {
        // Il NAS restituisce byte diversi da quelli scritti (E-B18): una copia con un byte cambiato, senza nessun errore.
        val altered = buf.copyOfRange(off, off + len)
        altered[len / 2] = (altered[len / 2].toInt() xor 0x5A).toByte()
        inner.write(offset, altered, 0, len)
      } else {
        inner.write(offset, buf, off, len)
      }
    }

  override suspend fun checkpoint(): NasResult<TempPoint> = injector.nas(Primitive.CHECKPOINT, incarnation) { inner.checkpoint() }

  override suspend fun finish(expectedSize: Long): NasResult<TempFinish> =
    injector.nas(Primitive.FINISH_TEMP, incarnation) { inner.finish(expectedSize) }

  /** Chiudere non tocca il NAS: dopo la morte non fa niente (l'handle lo chiude il sistema con la connessione, come sul telefono). */
  override fun close() {
    if (!incarnation.dead) inner.close()
  }
}

/** La lettura con i colpi. Il file aperto resta avvolto: le sue letture a flusso sono punti dove la connessione puo' cadere. */
class ChaosReader(
  private val inner: NasMediaReader,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasMediaReader {

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> = injector.nas(Primitive.LIST, incarnation) { inner.list(share, dir, purpose, pageSize, onPage) }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> =
    injector.nas(Primitive.STAT, incarnation) { inner.stat(share, path, purpose) }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> =
    when (val opened = injector.nas(Primitive.OPEN, incarnation) { inner.open(share, path, purpose) }) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(ChaosRandomFile(opened.value, injector, incarnation))
    }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    // Il lettore vero riconosce solo i suoi file aperti: gli si restituisce quello che ha dato.
    val real = (file as? ChaosRandomFile)?.inner ?: file
    return injector.nas(Primitive.READ_ALL, incarnation) { inner.readAll(real, sink, progress) }
  }
}

class ChaosRandomFile(
  val inner: NasRandomFile,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasRandomFile {
  override val size: Long get() = inner.size
  override val stamp: NasStamp get() = inner.stamp

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
    injector.read(incarnation) { inner.read(offset, buf, off, len) }

  override suspend fun readRange(range: LongRange): ByteArray {
    // Una lettura a intervalli (`qh1` della deduplica): e' un punto dove la connessione puo' cadere come ogni lettura a flusso.
    var bytes = ByteArray(0)
    injector.read(incarnation) {
      bytes = inner.readRange(range)
      bytes.size
    }
    return bytes
  }

  override fun close() {
    if (!incarnation.dead) inner.close()
  }
}

/** Una transizione scritta nel diario: (elemento, da, a), per il controllo del diario (invariante 8) anche con un diario che non le registra. */
class DiaryTransition(val item: BackupItemId, val from: UploadState, val to: UploadState)

/**
 * Il diario con la morte del processo prima o dopo ogni scrittura, e un controllo in piu': ogni passo di un elemento deve essere una
 * transizione della macchina a stati ([UploadStateMachine]), anche se il diario sotto non lo controllasse. [log] racconta le transizioni
 * scritte, [violations] quelle che non sono ammesse. Le letture passano senza colpi: se il processo muore dopo una lettura e' come se
 * morisse prima della scrittura successiva.
 */
class ChaosDiary(
  private val inner: BackupStore,
  private val injector: Injector,
  private val incarnation: Incarnation,
  private val log: MutableList<DiaryTransition>,
  private val violations: MutableList<String>,
) : BackupStore {

  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> =
    injector.store(Primitive.DIARY_CREATE, incarnation) {
      val created = inner.create(items)
      for (item in created) if (log.none { it.item == item.id }) log += DiaryTransition(item.id, UploadState.PLANNED, UploadState.PLANNED)
      created
    }

  override suspend fun item(id: BackupItemId): BackupItem? {
    incarnation.checkAlive()
    return inner.item(id)
  }

  override suspend fun itemsToResume(limit: Int): List<BackupItem> {
    incarnation.checkAlive()
    return inner.itemsToResume(limit)
  }

  override suspend fun update(item: BackupItem) {
    injector.store(Primitive.DIARY_UPDATE, incarnation) {
      val old = inner.item(item.id)
      if (old != null) {
        if (!UploadStateMachine.canTransition(item.flow, old.state, item.state)) {
          violations += "transizione non ammessa dal diario: ${old.state.code} -> ${item.state.code} (${item.flow.code}, elemento #${item.id})"
        }
        log += DiaryTransition(item.id, old.state, item.state)
      }
      inner.update(item)
    }
  }

  override suspend fun openFor(versionId: VersionId): BackupItem? {
    incarnation.checkAlive()
    return inner.openFor(versionId)
  }

  override suspend fun citedTemps(): Set<SharePath> {
    incarnation.checkAlive()
    return inner.citedTemps()
  }

  override suspend fun counts(): BackupDiaryCounts {
    incarnation.checkAlive()
    return inner.counts()
  }
}

/** Il catalogo con la morte del processo prima o dopo ogni scrittura del backup. Le letture passano (dopo la morte si fermano). */
class ChaosCatalog(
  private val inner: BackupCatalog,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : BackupCatalog {

  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> {
    incarnation.checkAlive()
    return inner.candidates(query)
  }

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts {
    incarnation.checkAlive()
    return inner.safetyCounts(sources)
  }

  override suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef? {
    incarnation.checkAlive()
    return inner.phoneRefOf(mediaId, versionId)
  }

  override suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow {
    incarnation.checkAlive()
    return inner.phoneRowOf(mediaId, versionId, stale)
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    injector.store(Primitive.CATALOG_RECORD_HASH, incarnation) { inner.recordFullHash(mediaId, versionId, sha256) }

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int =
    injector.store(Primitive.CATALOG_ATTACH, incarnation) { inner.attachVerifiedCopies(copies) }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    injector.store(Primitive.CATALOG_MARK_PROBLEM, incarnation) { inner.markProblem(mediaId, problem) }
  }

  override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> {
    incarnation.checkAlive()
    return inner.privateCopies(share, size, quickHash, limit)
  }

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> {
    incarnation.checkAlive()
    return inner.verifyCandidates(scope, limit)
  }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    injector.store(Primitive.CATALOG_MARK_VERIFIED, incarnation) { inner.markVerified(location, readBack, atMillis) }

  override suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate> {
    incarnation.checkAlive()
    return inner.onlyCommonCopies(scope, limit)
  }

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? {
    incarnation.checkAlive()
    return inner.mediaSafety(mediaId)
  }
}

/** Il telefono con i colpi: la morte prima o dopo ogni chiamata e, sulla lettura, un errore di I/O con la riga e il timbro invariati. */
class ChaosPhone(
  private val inner: PhoneBytes,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : PhoneBytes {

  override suspend fun lookup(ref: PhoneRef): PhoneLookup = injector.phone(Primitive.PHONE_LOOKUP, incarnation) { inner.lookup(ref) }

  override suspend fun open(ref: PhoneRef): PhoneOpen =
    when (val opened = injector.phone(Primitive.PHONE_OPEN, incarnation) { inner.open(ref) }) {
      is PhoneOpen.Opened -> {
        val file = opened.file
        PhoneOpen.Opened(PhoneFile(ChaosMediaBytes(file.bytes, injector, incarnation), file.stamp) { file.close() })
      }
      else -> opened
    }
}

class ChaosMediaBytes(
  private val inner: MediaBytes,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : MediaBytes {
  override val size: Long get() = inner.size

  override suspend fun read(range: LongRange): ByteArray = injector.phone(Primitive.PHONE_READ, incarnation) { inner.read(range) }

  override fun dataSource(): MediaDataSource = inner.dataSource()
}
