package dev.pampa.mycloudgallery.core.model

import kotlin.random.Random

/** Dati di prova scritti a mano: le regole sono pure, non serve altro. */
object Fixtures {

  val ME: AccountId = AccountId.of("alessio")
  val OTHER: AccountId = AccountId.of("pampa")

  /** Configurazione di famiglia di prova: `alessio` privata, `NAS` comune. */
  val AREAS: NasAreaLookup = NasAreaLookup.of(
    listOf(
      NasShare(ME, "alessio", NasArea.PRIVATE),
      NasShare(ME, "NAS", NasArea.COMMON),
    ),
  )

  const val STORE_VERSION: String = "store-v1"
  const val T0: Long = 1_760_000_000_000L
  const val DAY: Long = 24L * 60 * 60 * 1000

  /** Contenuto deterministico di 300 KB: abbastanza per avere testa e coda distinte. */
  fun content(seed: Int, size: Int = 300_000): ByteArray = Random(seed).nextBytes(size)

  /** Stessa dimensione, stessa testa e stessa coda di [content], ma un byte diverso nel mezzo. */
  fun contentChangedInTheMiddle(seed: Int, size: Int = 300_000): ByteArray =
    content(seed, size).also { it[size / 2] = (it[size / 2] + 1).toByte() }

  /** Impronta completa (con hash completo). */
  fun full(seed: Int): Fingerprint = Fingerprint.ofContent(content(seed))

  /** Impronta con il solo hash rapido. */
  fun quick(seed: Int): Fingerprint = full(seed).copy(fullHash = null)

  fun phone(
    name: String,
    mediaStoreId: Long? = null,
    path: String = "DCIM/Camera/",
    storeVersion: String? = STORE_VERSION,
    volume: String = "external_primary",
  ): Location.Phone = Location.Phone(volume, path, name, mediaStoreId, if (mediaStoreId == null) null else storeVersion)

  fun nas(path: String, share: String = "alessio", account: AccountId = ME): Location.Nas =
    Location.Nas(account, share, NasPath.of(path))

  fun stamp(fingerprint: Fingerprint, modifiedAt: Long = T0, generation: Long? = null): FileStamp =
    FileStamp(fingerprint.size, modifiedAt, generation)

  fun original(fingerprint: Fingerprint, createdAt: Long = T0, id: VersionId = VersionId.random()): MediaVersion =
    MediaVersion(id, VersionKind.ORIGINAL, null, fingerprint, createdAt)

  fun located(
    id: Long,
    version: MediaVersion,
    location: Location,
    state: LocationState = LocationState.PRESENT,
    verifiedAt: Long? = null,
    modifiedAt: Long = T0,
  ): MediaLocation = MediaLocation(
    id = LocationId(id),
    versionId = version.id,
    location = location,
    stamp = stamp(version.fingerprint, modifiedAt),
    state = state,
    absentSinceMillis = if (state == LocationState.ABSENT) T0 else null,
    verifiedAtMillis = verifiedAt,
    lastSeenAtMillis = T0,
  )

  fun media(
    versions: List<MediaVersion>,
    locations: List<MediaLocation>,
    owner: AccountId? = ME,
    current: VersionId = versions.last().id,
    id: MediaId = MediaId.random(),
    source: MediaSource = MediaSource.CAMERA,
  ): Media = Media(
    id = id,
    kind = MediaKind.PHOTO,
    source = source,
    owner = owner,
    createdAtMillis = T0,
    currentVersionId = current,
    versions = versions,
    locations = locations,
  )

  /** Un media con una sola versione in un solo posto. */
  fun single(
    fingerprint: Fingerprint,
    location: Location,
    locationId: Long = 1,
    owner: AccountId? = ME,
    state: LocationState = LocationState.PRESENT,
    verifiedAt: Long? = null,
    id: MediaId = MediaId.random(),
  ): Media {
    val v = original(fingerprint)
    return media(listOf(v), listOf(located(locationId, v, location, state, verifiedAt)), owner, id = id)
  }

  fun observe(
    location: Location,
    fingerprint: Fingerprint,
    modifiedAt: Long = T0,
    captureTime: Long? = null,
    generation: Long? = null,
  ) = dev.pampa.mycloudgallery.core.model.domain.Observation(
    location = location,
    stamp = stamp(fingerprint, modifiedAt, generation),
    fingerprint = fingerprint,
    kind = MediaKind.PHOTO,
    source = MediaSource.CAMERA,
    captureTimeHintMillis = captureTime,
  )
}
