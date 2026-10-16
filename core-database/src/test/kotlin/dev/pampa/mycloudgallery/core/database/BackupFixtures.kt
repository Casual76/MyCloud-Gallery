package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.database.Db.ME
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// Il materiale comune dei test della Fase 5A (P1): backup, diario, copie simili. Impronte piccole (2 KB): i test non leggono MB.

/** Un'impronta con hash completo, piccola. */
internal fun smallFull(seed: Int): Fingerprint = Fingerprint.ofContent(Db.content(seed, size = 2_000))

/** La stessa impronta senza hash completo (come la scansione del NAS con `KEEP_SEPARATE`). */
internal fun smallQuick(seed: Int): Fingerprint = smallFull(seed).copy(fullHash = null)

/** Raccoglie i cambiamenti annunciati dal catalogo: iscritto prima della prima scrittura. */
internal class Changes(scope: CoroutineScope, catalog: MediaCatalog) {
  val channel = Channel<CatalogChange>(Channel.UNLIMITED)
  val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { catalog.changes.collect { channel.send(it) } }

  suspend fun next(): CatalogChange = withTimeout(5_000) { channel.receive() }

  suspend fun none(): Boolean = withTimeoutOrNull(200) { channel.receive() } == null
}

internal fun nasLocation(path: String, share: String = "alessio", fileId: Long? = null): Location.Nas =
  Location.Nas(ME, share, NasPath.of(path), fileId)

/** Un file del telefono in `DCIM/Camera/` (o in [path]), con la data di scatto provvisoria [capture] (di norma `T0 - n`). */
internal fun phoneObservation(
  name: String,
  id: Long,
  fingerprint: Fingerprint,
  capture: Long,
  path: String = "DCIM/Camera/",
  source: MediaSource = MediaSource.CAMERA,
  kind: MediaKind = MediaKind.PHOTO,
  storeVersion: String = Db.STORE_V1,
): Observation =
  Observation(phone(name, id, path, storeVersion), FileStamp(fingerprint.size, T0, 7), fingerprint, kind, source, capture)

internal fun nasObservation(
  path: String,
  fingerprint: Fingerprint,
  share: String = "alessio",
  kind: MediaKind = MediaKind.PHOTO,
  modifiedAt: Long = T0,
  fileId: Long? = null,
): Observation =
  Observation(nasLocation(path, share, fileId), FileStamp(fingerprint.size, modifiedAt), fingerprint, kind, MediaSource.OTHER)

/** I metadati letti da META con una data di scatto EXIF e misure, per le copie simili. */
internal fun exifMetadata(
  takenAtUtc: Long,
  width: Int = 4000,
  height: Int = 3000,
  source: CaptureTimeSource = CaptureTimeSource.EXIF_WITH_OFFSET,
  confidence: CaptureTimeConfidence = CaptureTimeConfidence.EXACT,
  durationMillis: Long? = null,
  mimeType: String? = "image/jpeg",
): MediaMetadata = MediaMetadata(
  mimeType = mimeType,
  width = width,
  height = height,
  durationMillis = durationMillis,
  captureTime = CaptureTime(takenAtUtc, 120, confidence, source),
)

internal suspend fun MediaCatalog.versionOf(id: MediaId): VersionId = media(id)!!.currentVersionId

/** La posizione del NAS (la prima) di un media. */
internal suspend fun MediaCatalog.nasLocationIdOf(id: MediaId): LocationId =
  media(id)!!.locations.first { it.isNas }.id

/** Un elemento del diario: i campi del telefono e della destinazione come li scriverebbe il piano (`is_open` volutamente sbagliato). */
internal fun backupItemEntity(
  media: MediaId,
  version: VersionId,
  state: String = "planned",
  kind: String = "new_copy",
  temp: String? = null,
): BackupItemEntity = BackupItemEntity(
  kind = kind,
  state = state,
  isOpen = false,
  mediaId = media.value,
  versionId = version.value,
  size = 2_000,
  phoneVolume = "external_primary",
  phoneMediaStoreId = 100,
  phoneMediaStoreVersion = Db.STORE_V1,
  expectedSize = 2_000,
  expectedModifiedAt = T0,
  expectedGeneration = 7,
  share = "alessio",
  plannedPath = "Backup/S25/2025/10/IMG.jpg",
  finalPath = null,
  nameAttempt = 0,
  tempPath = temp,
  resumeAt = 0,
  sha256 = null,
  quickHash = null,
  tempSize = null,
  tempModifiedAt = null,
  tempFileId = null,
  finalSize = null,
  finalModifiedAt = null,
  finalFileId = null,
  verifyAttempts = 0,
  guardShare = null,
  guardPath = null,
  guardSize = null,
  guardModifiedAt = null,
  guardFileId = null,
  problem = null,
  createdAt = T0,
  updatedAt = T0,
)
