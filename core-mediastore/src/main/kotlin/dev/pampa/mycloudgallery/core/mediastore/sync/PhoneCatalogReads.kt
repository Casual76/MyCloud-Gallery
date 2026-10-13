package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus

/** Una posizione del telefono con l'`_ID` in cache (letto con la versione attuale del MediaStore). */
data class CachedPhoneRow(val mediaStoreId: Long, val key: LocationKey)

/**
 * Le letture del catalogo che la sincronizzazione del telefono fa **senza** passare da `MediaCatalog`, che non le
 * espone: dall'`_ID` alla posizione (cancellazioni di `deleted_files`, riconciliazione), il timbro conosciuto di un posto
 * e le code della fase META. Solo letture: ogni scrittura passa da `MediaCatalog`, nelle sue transazioni.
 *
 * Un'interfaccia per tenere la logica provabile; l'implementazione vera e' [RoomPhoneCatalogReads].
 */
interface PhoneCatalogReads {

  /**
   * I posti (presenti o non raggiungibili) che hanno in cache l'`_ID` [id] del volume, **letto con [msVersion]**: un
   * `_ID` di un'altra versione del MediaStore puo' essere di un altro file (E-P22).
   */
  suspend fun liveAtMediaStoreRow(volume: String, id: Long, msVersion: String): List<LocationKey>

  /** Le posizioni presenti del volume con un `_ID` in cache letto con [msVersion] (riconciliazione quotidiana). */
  suspend fun presentWithCache(volume: String, msVersion: String): List<CachedPhoneRow>

  /** Il timbro conosciuto del posto [key], se e' conosciuto e non assente. */
  suspend fun storedStamp(key: LocationKey): FileStamp?

  /** Il media che ha una posizione nel posto [key]. */
  suspend fun mediaIdAt(key: LocationKey): MediaId?

  /** I media con META da fare, dal piu' recente. */
  suspend fun pendingMeta(limit: Int): List<MediaId>

  /** I media con META fallita prima di [beforeMs], da riprovare. */
  suspend fun retryableMeta(beforeMs: Long, limit: Int): List<MediaId>
}

/** Le letture sul database vero, con i DAO pubblici di `core-database`. */
class RoomPhoneCatalogReads(private val database: GalleryDatabase) : PhoneCatalogReads {

  private val locations get() = database.locationDao()
  private val work get() = database.workStateDao()

  override suspend fun liveAtMediaStoreRow(volume: String, id: Long, msVersion: String): List<LocationKey> =
    locations.byMediaStoreId(volume, id)
      .filter { it.locationType == LocationType.PHONE.code && it.mediaStoreVersion == msVersion && it.state != LocationState.ABSENT.code }
      .map { LocationKey(it.locationKey) }

  override suspend fun presentWithCache(volume: String, msVersion: String): List<CachedPhoneRow> =
    locations.presentOnVolume(volume).mapNotNull { entity ->
      val id = entity.mediaStoreId ?: return@mapNotNull null
      if (entity.mediaStoreVersion != msVersion) null else CachedPhoneRow(id, LocationKey(entity.locationKey))
    }

  override suspend fun storedStamp(key: LocationKey): FileStamp? {
    val entity = locations.byKey(key.value) ?: return null
    if (entity.state == LocationState.ABSENT.code) return null
    return FileStamp(entity.size, entity.modifiedAt, entity.generation)
  }

  override suspend fun mediaIdAt(key: LocationKey): MediaId? {
    val entity = locations.byKey(key.value) ?: return null
    return locations.mediaIdOf(entity.locationId)?.let(::MediaId)
  }

  override suspend fun pendingMeta(limit: Int): List<MediaId> =
    work.nextPending(WorkStage.META.code, limit).map { MediaId(it.mediaId) }

  override suspend fun retryableMeta(beforeMs: Long, limit: Int): List<MediaId> =
    work.retryable(WorkStage.META.code, beforeMs, limit).filter { it.status == WorkStatus.FAILED.code }.map { MediaId(it.mediaId) }
}

/**
 * Le righe del MediaStore per `_ID`: serve alla fase META, che parte da un media del catalogo e ha bisogno degli indizi
 * del MediaStore (`DATE_TAKEN`, dimensioni, durata) e del timbro attuale per non leggere byte diversi da quelli della
 * versione. Non sta in `MediaStoreSource` per non toccare il contratto di P0: l'adattatore vero e il finto le
 * implementano entrambe.
 */
interface MediaStoreRowLookup {

  /** Le righe visibili (non `IS_PENDING`, non cestinate) di [ids] nel volume; quelle che mancano non ci sono piu'. */
  suspend fun rowsById(volume: String, ids: Collection<Long>): List<MediaStoreRow>
}
