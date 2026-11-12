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

/** Un posto del telefono come lo scrive il MediaStore: percorso relativo e nome. Dati personali: niente nel `toString`. */
class PhonePlace(val relativePath: String, val displayName: String) {
  override fun equals(other: Any?): Boolean = other is PhonePlace && other.relativePath == relativePath && other.displayName == displayName

  override fun hashCode(): Int = 31 * relativePath.hashCode() + displayName.hashCode()

  override fun toString(): String = "PhonePlace"
}

/**
 * Pacchetto F (FS-1): una posizione del telefono per il confronto degli `_ID`: [present] o assente, l'`_ID` in cache con la versione del
 * MediaStore con cui e' stato letto ([msVersion]: un `_ID` di un'altra versione non dice niente, E-P22) e il posto.
 */
class PhonePosition(
  val key: LocationKey,
  val present: Boolean,
  val mediaStoreId: Long?,
  val msVersion: String?,
  val place: PhonePlace,
) {
  /** L'`_ID` in cache, se vale per la versione [current] del MediaStore. */
  fun idFor(current: String): Long? = mediaStoreId?.takeIf { msVersion == current }

  override fun toString(): String = "PhonePosition(present=$present)"
}

/**
 * Le letture del catalogo che la sincronizzazione del telefono fa **senza** passare da `MediaCatalog`, che non le
 * espone: dall'`_ID` alla posizione (cancellazioni di `deleted_files`, riconciliazione), il timbro conosciuto di un posto
 * e le code della fase META. Solo letture: ogni scrittura passa da `MediaCatalog`, nelle sue transazioni.
 *
 * Un'interfaccia per tenere la logica provabile; l'implementazione vera e' [RoomPhoneCatalogReads].
 */
interface PhoneCatalogReads {

  /**
   * Le posizioni **presenti** che hanno in cache l'`_ID` [id] del volume, **letto con [msVersion]**: un `_ID` di un'altra versione del
   * MediaStore puo' essere di un altro file (E-P22). Pacchetto F (FS2-1): le cancellazioni di `deleted_files` passano da qui alla stessa
   * conferma delle altre assenze.
   */
  suspend fun positionsAtMediaStoreRow(volume: String, id: Long, msVersion: String): List<PhonePosition>

  /**
   * Pacchetto F (FS-1): le posizioni del volume presenti e assenti (non le non raggiungibili), con l'`_ID` e la versione in cache e il posto,
   * per il confronto degli `_ID` con il MediaStore (riconciliazione).
   */
  suspend fun phonePositions(volume: String): List<PhonePosition>

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

  override suspend fun positionsAtMediaStoreRow(volume: String, id: Long, msVersion: String): List<PhonePosition> =
    locations.byMediaStoreId(volume, id)
      .filter { it.locationType == LocationType.PHONE.code && it.mediaStoreVersion == msVersion && it.state == LocationState.PRESENT.code }
      .map {
        PhonePosition(
          key = LocationKey(it.locationKey),
          present = true,
          mediaStoreId = it.mediaStoreId,
          msVersion = it.mediaStoreVersion,
          place = PhonePlace(it.relativePath.orEmpty(), it.displayName.orEmpty()),
        )
      }

  override suspend fun phonePositions(volume: String): List<PhonePosition> =
    locations.phonePositionsOnVolume(volume).map { row ->
      PhonePosition(
        key = LocationKey(row.locationKey),
        present = row.state == LocationState.PRESENT.code,
        mediaStoreId = row.mediaStoreId,
        msVersion = row.mediaStoreVersion,
        place = PhonePlace(row.relativePath.orEmpty(), row.displayName.orEmpty()),
      )
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

/** Una riga trovata dal controllo puntuale. [pending]: `IS_PENDING`, un file in scrittura: c'e', non e' una cancellazione. */
class PointRow(val row: MediaStoreRow, val pending: Boolean) {
  override fun toString(): String = "PointRow(pending=$pending)"
}

/**
 * Pacchetto F (FS-1): il **controllo puntuale** prima di segnare assente una posizione che il MediaStore non mostra piu'. Una lista di `_ID`
 * puo' arrivare parziale (un provider in ricostruzione dopo un riavvio o un aggiornamento) e un `_ID` di un'altra versione non dice niente:
 * qui si chiede riga per riga, per `_ID` e per posto (percorso e nome), **anche le righe in scrittura** (`IS_PENDING`: una riga che diventa
 * pending non e' una cancellazione), mai quelle cestinate (il cestino e' una cancellazione voluta). Una sorgente senza questo controllo non
 * fa dedurre nessuna assenza: meglio un fantasma in piu' che una foto vera in meno.
 */
interface MediaStorePointCheck {

  /** Le righe del volume con uno di questi `_ID` o in uno di questi posti, anche `IS_PENDING`, mai cestinate. */
  suspend fun stillThere(volume: String, ids: Collection<Long>, places: Collection<PhonePlace>): List<PointRow>
}

/**
 * Pacchetto F (FS2-2): la **prova sul disco**. Il MediaStore puo' omettere righe (un provider in ricostruzione, un indice a meta'), ma un file
 * che il disco vede ancora non e' sparito; uno che il disco non vede piu', con il disco attendibile (i file di alcune foto vive ci sono), e' la
 * prova positiva della cancellazione (anche un file nel cestino ha un altro nome o un'altra cartella). Chi la usa ricontrolla comunque accesso
 * e volume dopo l'ultima lettura.
 */
interface PhoneFileProbe {

  /** true: il file c'e'; false: il disco leggibile non lo vede; null: non si sa (Android 10, volume non leggibile, errore). */
  suspend fun exists(volume: String, place: PhonePlace): Boolean?
}
