package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGraph
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasShareEntity
import dev.pampa.mycloudgallery.core.database.entity.TimelineItem
import dev.pampa.mycloudgallery.core.database.entity.VersionMetadataEntity
import dev.pampa.mycloudgallery.core.database.entity.WorkStateEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.VectorPrecision
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.enumByCode

// Traduzioni tra righe e tipi del dominio. Rileggere una riga passa dai costruttori del dominio, quindi
// dai loro invarianti: un database incoerente fallisce qui, a voce alta, invece di propagarsi.

internal fun MediaGraph.toDomain(): Media = Media(
  id = MediaId(media.mediaId),
  kind = enumByCode<MediaKind>(media.kind),
  source = enumByCode<MediaSource>(media.source),
  owner = media.ownerAccount?.let(::AccountId),
  createdAtMillis = media.createdAt,
  currentVersionId = VersionId(media.currentVersionId),
  versions = versions.map { it.version.toDomain() },
  locations = versions.flatMap { v -> v.locations.map { it.toDomain() } },
  groupId = media.groupId?.let(::MediaGroupId),
)

internal fun MediaVersionEntity.toDomain(): MediaVersion = MediaVersion(
  id = VersionId(versionId),
  kind = enumByCode<VersionKind>(kind),
  parentId = parentVersionId?.let(::VersionId),
  fingerprint = Fingerprint(size, QuickHash(quickHash), fullHash?.let(::Sha256)),
  createdAtMillis = createdAt,
  corrections = corrections.split(',').filter { it.isNotEmpty() }.map { enumByCode<CorrectionField>(it) }.toSet(),
)

internal fun MediaVersion.toEntity(mediaId: MediaId): MediaVersionEntity = MediaVersionEntity(
  versionId = id.value,
  mediaId = mediaId.value,
  kind = kind.code,
  parentVersionId = parentId?.value,
  size = fingerprint.size,
  quickHash = fingerprint.quickHash.hex,
  fullHash = fingerprint.fullHash?.hex,
  corrections = corrections.map { it.code }.sorted().joinToString(","),
  createdAt = createdAtMillis,
)

internal fun MediaLocationEntity.toDomain(): MediaLocation {
  val location = when (enumByCode<LocationType>(locationType)) {
    LocationType.PHONE -> Location.Phone(
      volume = checkNotNull(volume) { "Posizione del telefono senza volume." },
      relativePath = checkNotNull(relativePath) { "Posizione del telefono senza percorso." },
      displayName = checkNotNull(displayName) { "Posizione del telefono senza nome." },
      mediaStoreId = mediaStoreId,
      mediaStoreVersion = mediaStoreVersion,
    )
    LocationType.NAS -> Location.Nas(
      account = AccountId(checkNotNull(accountId) { "Posizione NAS senza account." }),
      share = checkNotNull(shareName) { "Posizione NAS senza share." },
      path = NasPath.of(checkNotNull(nasPath) { "Posizione NAS senza percorso." }),
    )
  }
  check(location.key.value == locationKey) { "La chiave salvata non corrisponde alla posizione." }
  return MediaLocation(
    id = LocationId(locationId),
    versionId = VersionId(versionId),
    location = location,
    stamp = FileStamp(size, modifiedAt, generation),
    state = enumByCode<LocationState>(state),
    absentSinceMillis = absentSince,
    verifiedAtMillis = verifiedAt,
    lastSeenAtMillis = lastSeenAt,
  )
}

/** Una riga di posizione da un tipo del dominio. [id] 0 = riga nuova (la numera SQLite). */
internal fun locationEntity(
  id: Long,
  versionId: VersionId,
  location: Location,
  stamp: FileStamp,
  state: LocationState,
  absentSince: Long?,
  verifiedAt: Long?,
  lastSeenAt: Long,
): MediaLocationEntity {
  val phone = location as? Location.Phone
  val nas = location as? Location.Nas
  return MediaLocationEntity(
    locationId = id,
    versionId = versionId.value,
    locationType = location.type.code,
    locationKey = location.key.value,
    folderKey = location.folderKey,
    state = state.code,
    volume = phone?.volume,
    relativePath = phone?.relativePath,
    displayName = phone?.displayName,
    mediaStoreId = phone?.mediaStoreId,
    mediaStoreVersion = phone?.mediaStoreVersion,
    generation = stamp.generation,
    accountId = nas?.account?.value,
    shareName = nas?.share,
    shareKey = nas?.shareKey,
    nasPath = nas?.path?.value,
    size = stamp.size,
    modifiedAt = stamp.modifiedAtMillis,
    absentSince = absentSince,
    verifiedAt = verifiedAt,
    lastSeenAt = lastSeenAt,
  )
}

internal fun MediaLocation.toEntity(): MediaLocationEntity =
  locationEntity(id.value, versionId, location, stamp, state, absentSinceMillis, verifiedAtMillis, lastSeenAtMillis)

internal fun VersionMetadataEntity.toDomain(): MediaMetadata = MediaMetadata(
  mimeType = mimeType,
  width = width,
  height = height,
  orientationDegrees = orientation,
  durationMillis = durationMs,
  captureTime = if (takenAtUtc != null && takenOffsetMinutes != null && takenConfidence != null && takenSource != null) {
    CaptureTime(takenAtUtc, takenOffsetMinutes, enumByCode(takenConfidence), enumByCode(takenSource))
  } else {
    null
  },
  location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude, altitude) else null,
  mirrored = mirrored,
  traits = MediaTrait.fromBits(traits),
  place = if (placeId != null && placeDataset != null) PlaceRef(placeId, placeDataset) else null,
  thumbHash = thumbHash?.let(ThumbHash::of),
)

internal fun MediaMetadata.toEntity(versionId: VersionId, now: Long): VersionMetadataEntity = VersionMetadataEntity(
  versionId = versionId.value,
  mimeType = mimeType,
  width = width,
  height = height,
  orientation = orientationDegrees,
  durationMs = durationMillis,
  takenAtUtc = captureTime?.utcMillis,
  takenOffsetMinutes = captureTime?.offsetMinutes,
  takenConfidence = captureTime?.confidence?.code,
  takenSource = captureTime?.source?.code,
  latitude = location?.latitude,
  longitude = location?.longitude,
  altitude = location?.altitudeMeters,
  updatedAt = now,
  mirrored = mirrored,
  traits = MediaTrait.toBits(traits),
  placeId = place?.geonameId,
  placeDataset = place?.dataset,
  thumbHash = thumbHash?.bytes(),
)

/** La data di scatto come indizio per il collegamento: solo se non e' una stima. */
internal fun VersionMetadataEntity.captureTimeHint(): Long? =
  if (takenAtUtc != null && takenConfidence != CaptureTimeConfidence.ESTIMATED.code) takenAtUtc else null

internal fun WorkStateEntity.toDomain(): WorkState = WorkState(
  stage = enumByCode<WorkStage>(stage),
  status = enumByCode<WorkStatus>(status),
  versionId = versionId?.let(::VersionId),
  attempts = attempts,
  lastError = lastError?.let(::WorkErrorCode),
  updatedAtMillis = updatedAt,
)

internal fun WorkState.toEntity(mediaId: MediaId): WorkStateEntity = WorkStateEntity(
  mediaId = mediaId.value,
  stage = stage.code,
  status = status.code,
  versionId = versionId?.value,
  attempts = attempts,
  lastError = lastError?.value,
  updatedAt = updatedAtMillis,
)

internal fun EmbeddingEntity.toDomain(): EmbeddingVector = EmbeddingVector(
  space = EmbeddingSpace(spaceId, dimension, enumByCode<VectorPrecision>(precision)),
  index = vectorIndex,
  bytes = vector,
  computedFrom = versionId?.let(::VersionId),
)

internal fun EmbeddingVector.toEntity(mediaId: MediaId, now: Long): EmbeddingEntity = EmbeddingEntity(
  mediaId = mediaId.value,
  spaceId = space.id,
  dimension = space.dimension,
  precision = space.precision.code,
  vectorIndex = index,
  vector = bytes,
  versionId = computedFrom?.value,
  createdAt = now,
)

internal fun EmbeddingEntity.withMedia(mediaId: String): EmbeddingEntity =
  EmbeddingEntity(mediaId, spaceId, dimension, precision, vectorIndex, vector, versionId, createdAt)

internal fun MediaPersonalEntity.toDomain(): PersonalFlags = PersonalFlags(favorite = favorite, hidden = hidden)

internal fun NasShareEntity.toDomain(): NasShare = NasShare(AccountId(accountId), shareName, enumByCode<NasArea>(area))

internal fun NasShare.toEntity(): NasShareEntity = NasShareEntity(account.value, shareKey, name, area.code)

/** Una cella della timeline con i tipi del dominio. */
data class TimelineEntry(
  val mediaId: MediaId,
  val sortTimeMillis: Long,
  val kind: MediaKind,
  val source: MediaSource,
  val presence: Presence,
  val safety: SafetyStatus,
  val groupId: MediaGroupId?,
  val favorite: Boolean,
)

fun TimelineItem.toEntry(): TimelineEntry = TimelineEntry(
  mediaId = MediaId(mediaId),
  sortTimeMillis = sortTime,
  kind = enumByCode(kind),
  source = enumByCode(source),
  presence = enumByCode(presence),
  safety = enumByCode(safety),
  groupId = groupId?.let(::MediaGroupId),
  favorite = favorite,
)
