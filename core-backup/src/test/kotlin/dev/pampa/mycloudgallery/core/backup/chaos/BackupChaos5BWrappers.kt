package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.free.FreeItemRecord
import dev.pampa.mycloudgallery.core.backup.free.FreeRunRecord
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCursor
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceMedia
import dev.pampa.mycloudgallery.core.backup.free.NewFreeRun
import dev.pampa.mycloudgallery.core.backup.versions.NewVersionJob
import dev.pampa.mycloudgallery.core.backup.versions.VersionCatalog
import dev.pampa.mycloudgallery.core.backup.versions.VersionJob
import dev.pampa.mycloudgallery.core.backup.versions.VersionJobId
import dev.pampa.mycloudgallery.core.backup.versions.VersionJournal
import dev.pampa.mycloudgallery.core.backup.versions.VersionedMedia
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File

// I decoratori della 5B (P13): portano i colpi (BackupChaosFaults.kt) dentro le porte delle versioni e di "Libera spazio", come
// BackupChaosWrappers.kt fa per il caricamento. La morte del processo cade prima o dopo ogni scrittura del diario delle versioni, del catalogo
// e del diario di "Libera spazio", e prima o dopo la riscrittura del telefono; le letture passano (dopo la morte si fermano).

/** Il diario delle versioni con la morte del processo prima o dopo ogni scrittura. */
class ChaosVersionJournal(
  private val inner: VersionJournal,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : VersionJournal {
  override suspend fun create(job: NewVersionJob): VersionJob = injector.store(Primitive.VERSION_CREATE, incarnation) { inner.create(job) }

  override suspend fun job(id: VersionJobId): VersionJob? {
    incarnation.checkAlive()
    return inner.job(id)
  }

  override suspend fun open(limit: Int): List<VersionJob> {
    incarnation.checkAlive()
    return inner.open(limit)
  }

  override suspend fun update(job: VersionJob) {
    injector.store(Primitive.VERSION_UPDATE, incarnation) { inner.update(job) }
  }

  override suspend fun busy(versionId: VersionId): Boolean {
    incarnation.checkAlive()
    return inner.busy(versionId)
  }

  override suspend fun forMedia(mediaId: MediaId): List<VersionJob> {
    incarnation.checkAlive()
    return inner.forMedia(mediaId)
  }

  override suspend fun inShare(share: String): List<VersionJob> {
    incarnation.checkAlive()
    return inner.inShare(share)
  }

  override suspend fun citedTemps(): Set<SharePath> {
    incarnation.checkAlive()
    return inner.citedTemps()
  }
}

/** Il catalogo delle versioni con la morte del processo prima o dopo ogni scrittura. */
class ChaosVersionCatalog(
  private val inner: VersionCatalog,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : VersionCatalog {
  override suspend fun media(mediaId: MediaId): VersionedMedia? {
    incarnation.checkAlive()
    return inner.media(mediaId)
  }

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    injector.store(Primitive.CATALOG_RECORD_HASH, incarnation) { inner.recordFullHash(mediaId, versionId, sha256) }

  override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean =
    injector.store(Primitive.CATALOG_ATTACH, incarnation) { inner.attachVerifiedCopy(copy) }

  override suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp) {
    injector.store(Primitive.CATALOG_RELOCATE, incarnation) { inner.relocate(locationId, share, path, stamp) }
  }

  override suspend fun restoreOriginal(mediaId: MediaId) {
    injector.store(Primitive.CATALOG_RESTORE_ORIGINAL, incarnation) { inner.restoreOriginal(mediaId) }
  }

  override suspend fun correct(mediaId: MediaId, fields: Set<CorrectionField>, size: Long, quickHash: QuickHash, sha256: Sha256): VersionId? =
    injector.store(Primitive.CATALOG_CORRECT, incarnation) { inner.correct(mediaId, fields, size, quickHash, sha256) }

  override suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean =
    injector.store(Primitive.CATALOG_CORRECT, incarnation) { inner.correctInApp(mediaId, versionId, change) }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    injector.store(Primitive.CATALOG_MARK_PROBLEM, incarnation) { inner.markProblem(mediaId, problem) }
  }
}

/** Il diario di "Libera spazio" con la morte del processo prima o dopo ogni scrittura. */
class ChaosFreeDiary(
  private val inner: FreeSpaceDiary,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : FreeSpaceDiary {
  override suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord =
    injector.store(Primitive.FREE_CREATE, incarnation) { inner.create(run, items) }

  override suspend fun run(id: FreeSpaceRunId): FreeRunRecord? {
    incarnation.checkAlive()
    return inner.run(id)
  }

  override suspend fun items(id: FreeSpaceRunId): List<FreeItemRecord> {
    incarnation.checkAlive()
    return inner.items(id)
  }

  override suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord>) {
    injector.store(Primitive.FREE_UPDATE, incarnation) { inner.update(run, items) }
  }

  override suspend fun runs(): List<FreeRunRecord> {
    incarnation.checkAlive()
    return inner.runs()
  }

  override suspend fun handedPhoneLocations(): Set<LocationId> {
    incarnation.checkAlive()
    return inner.handedPhoneLocations()
  }
}

/** Il catalogo di "Libera spazio": la verifica sul momento e le righe liberate sono scritture dove cade la morte. */
class ChaosFreeCatalog(
  private val inner: FreeSpaceCatalog,
  private val injector: Injector,
  private val incarnation: Incarnation,
  /** Il banco guarda ogni rilettura che il catalogo riceve (la posizione e se e' tornata): l'invariante 14 la confronta con l'ora del lotto. */
  private val probe: ((LocationId, Boolean) -> Unit)? = null,
) : FreeSpaceCatalog {
  override suspend fun phoneMedia(after: FreeSpaceCursor?, limit: Int): List<FreeSpaceMedia> {
    incarnation.checkAlive()
    return inner.phoneMedia(after, limit)
  }

  override suspend fun media(ids: Collection<MediaId>): List<FreeSpaceMedia> {
    incarnation.checkAlive()
    return inner.media(ids)
  }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    injector.store(Primitive.FREE_MARK_VERIFIED, incarnation) {
      inner.markVerified(location, readBack, atMillis).also { ok -> probe?.invoke(location, ok) }
    }

  override suspend fun freedFromPhone(locations: List<LocationId>) {
    injector.store(Primitive.FREE_FREED, incarnation) { inner.freedFromPhone(locations) }
  }
}

/** La riscrittura del telefono (E-E10) con la morte del processo prima o dopo: dopo, il telefono ha l'originale e nessuno lo ha registrato. */
class ChaosRewriter(
  private val inner: PhoneRewriter,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : PhoneRewriter {
  override suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256, currentSha256: Sha256): PhoneRewrite =
    injector.phone(Primitive.PHONE_REWRITE, incarnation) { inner.replace(ref, mime, file, expectedSha256, currentSha256) }
}
