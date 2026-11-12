package dev.pampa.mycloudgallery.core.mediastore.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before

/** Tutti i "thread" nel thread del test: i giri sono deterministici (Room usa comunque i suoi esecutori). */
object InlineDispatchers : DispatcherProvider {
  override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
  override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
  override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/**
 * Un catalogo vero in memoria (Robolectric) davanti a un [FakeMediaStore]: la sincronizzazione si prova per intero, con le
 * regole di collegamento e le transazioni vere.
 */
abstract class PhoneSyncTestBase {

  protected lateinit var db: GalleryDatabase
  protected lateinit var catalog: MediaCatalog
  protected lateinit var reads: RoomPhoneCatalogReads

  /** L'orologio di riferimento. */
  protected var now: Long = T0

  /** L'orologio dei budget. */
  protected var mono: Long = 0

  protected val store = FakeMediaStore().apply { addVolume(PRIMARY) }
  protected val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))

  /** Lotti piccoli: le riprese e i tetti si vedono con poche righe. */
  protected open val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 50)

  @Before
  fun openCatalog() {
    db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    catalog = MediaCatalog(db, ME, zone = { ROME }) { now }
    reads = RoomPhoneCatalogReads(db)
  }

  @After
  fun closeCatalog() {
    db.close()
  }

  /** Una sincronizzazione nuova sullo stesso database: e' anche "il processo dopo un riavvio". */
  protected fun newSync(
    metadataPass: MetadataPass? = null,
    priority: MetaPriority = NoMetaPriority,
    storageReady: () -> Boolean = { true },
  ): DefaultPhoneLibrarySync = DefaultPhoneLibrarySync(
    source = store,
    access = access,
    catalog = catalog,
    reads = reads,
    clock = { now },
    monotonicMs = { mono },
    dispatchers = InlineDispatchers,
    config = config,
    metadataPass = metadataPass,
    priority = priority,
    storageReady = storageReady,
  )

  /** Il tempo passa per tutti e due gli orologi (pacchetto F: le annotazioni delle assenze, su disco, contano l'orologio di riferimento). */
  protected fun advance(ms: Long) {
    now += ms
    mono += ms
  }

  protected fun setAccess(media: MediaAccess, mediaLocation: Boolean = true) {
    access.set(PhoneAccess(media, mediaLocation, setOf(PRIMARY)))
    store.mediaLocationGranted = mediaLocation
  }

  protected suspend fun mediaCount(): Int = db.mediaDao().count()

  protected suspend fun versionCount(): Int = db.mediaDao().allIds().sumOf { db.versionDao().forMedia(it).size }

  /** Il media che sta nel posto di [row] (letto con qualunque versione del MediaStore). */
  protected suspend fun mediaAt(row: MediaStoreRow): MediaId? = reads.mediaIdAt(placeOf(row).key)

  protected suspend fun stateAt(row: MediaStoreRow): LocationState? =
    db.locationDao().byKey(placeOf(row).key.value)?.state?.let { code -> LocationState.entries.first { it.code == code } }

  protected suspend fun presenceOf(id: MediaId): String? = db.mediaDao().get(id.value)?.presence

  companion object {
    const val PRIMARY = "external_primary"
    const val SD = "1a2b-3c4d"
    const val T0: Long = 1_760_000_000_000L
    const val MINUTE: Long = 60_000L
    val ME: AccountId = AccountId.of("alessio")
    val ROME: ZoneId = ZoneId.of("Europe/Rome")

    fun placeOf(row: MediaStoreRow): Location.Phone = Location.Phone(row.volume, row.relativePath, row.displayName)

    fun photo(
      id: Long,
      taken: Long,
      name: String = "IMG_$id.jpg",
      path: String = "DCIM/Camera/",
      size: Long = 4_000,
      modified: Long = taken + 1_000,
      volume: String = PRIMARY,
      mime: String? = "image/jpeg",
    ) = MediaStoreRow(
      volume = volume, id = id, type = RowType.IMAGE, displayName = name, relativePath = path, mime = mime, size = size,
      dateModifiedMs = modified, dateTakenMs = taken,
    )

    fun video(id: Long, taken: Long, name: String = "VID_$id.mp4", size: Long = 6_000, volume: String = PRIMARY) = MediaStoreRow(
      volume = volume, id = id, type = RowType.VIDEO, displayName = name, relativePath = "DCIM/Camera/", mime = "video/mp4",
      size = size, dateModifiedMs = taken + 1_000, dateTakenMs = taken, durationMs = 3_000,
    )

    /** La stessa riga con il file cambiato (altra dimensione e data di modifica: byte diversi). */
    fun edited(row: MediaStoreRow, size: Long = row.size + 100, modified: Long = row.dateModifiedMs + 60_000) = MediaStoreRow(
      volume = row.volume, id = row.id, type = row.type, displayName = row.displayName, relativePath = row.relativePath,
      mime = row.mime, size = size, dateModifiedMs = modified, dateTakenMs = row.dateTakenMs, width = row.width,
      height = row.height, orientation = row.orientation, durationMs = row.durationMs, ownerPackage = row.ownerPackage,
    )
  }
}
