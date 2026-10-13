package dev.pampa.mycloudgallery.core.library

import android.content.Context
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.domain.Observation
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il repository sul catalogo vero (Room in memoria, Robolectric): costruzione dal `Cursor`, patch dal flusso dei
 * cambiamenti, preferiti e nascosti con [CatalogLocalMediaActions] (E-H1: mai nell'indice ne' nel file), copertine dei
 * gruppi, riferimenti ai byte, avvio a freddo dall'istantanea.
 */
@RunWith(AndroidJUnit4::class)
class CatalogIntegrationTest {

  private lateinit var db: GalleryDatabase
  private lateinit var catalog: MediaCatalog
  private lateinit var scope: CoroutineScope
  private lateinit var dir: File
  private var now = T0

  @Before
  fun open() {
    db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { now }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    dir = Files.createTempDirectory("catalog").toFile()
    runBlocking { catalog.saveMediaStoreCursor(MediaStoreCursor(VOLUME, msVersion = STORE)) }
  }

  @After
  fun close() {
    scope.cancel()
    db.close()
    dir.deleteRecursively()
  }

  private fun observation(k: Int): Observation {
    val content = Random(k).nextBytes(2_048)
    val fingerprint = Fingerprint.ofContent(content)
    val location = Location.Phone(VOLUME, "DCIM/Camera/", "IMG_$k.jpg", mediaStoreId = 1_000L + k, mediaStoreVersion = STORE)
    return Observation(location, FileStamp(fingerprint.size, T0 - k * 1_000L), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, T0 - k * 3_600_000L)
  }

  private suspend fun record(range: IntRange): List<MediaId> =
    catalog.recordAll(range.map { observation(it) }, MediaCatalog.WhenUndecided.KEEP_SEPARATE).map { it.mediaId!! }

  private fun repository(writeDelayMs: Long = 50): Pair<CatalogTimelineRepository, SnapshotStore> {
    val store = SnapshotStore(dir)
    return CatalogTimelineRepository(CatalogTimelineSource(db, catalog), store, scope, writeDelayMs = writeDelayMs) to store
  }

  private suspend fun CatalogTimelineRepository.caughtUp() = withTimeout(10_000) { awaitSeq(catalog.changeSeq()) }

  private suspend fun fresh() = CatalogTimelineSource(db, catalog).buildIndex()

  /** Aspetta che l'istantanea su file sia definitiva e al seq del catalogo (la scrittura e' accorpata). */
  private suspend fun SnapshotStore.awaitCurrent() = withTimeout(5_000) {
    while (read()?.let { it.provisional || it.snapshot.seq != catalog.changeSeq() } != false) delay(20)
  }

  @Test
  fun dalCursorAllePatchConPreferitiNascostiEGruppi() = runBlocking {
    val ids = record(0 until 300)
    val (repo, store) = repository()
    val source = CatalogTimelineSource(db, catalog)
    val actions = CatalogLocalMediaActions(catalog, source, repo)
    repo.start()
    withTimeout(10_000) { repo.reconciled.first { it } }
    val index = repo.snapshot.value!!
    assertThat(index.size).isEqualTo(300)
    assertThat((1 until index.size).all { index.sortTimeAt(it - 1) >= index.sortTimeAt(it) }).isTrue()
    // I riferimenti ai byte vengono dal catalogo (stessa versione del MediaStore).
    val items = TimelineViewerItems(repo, LibraryFilter.ALL)
    assertThat(items.refOrNull(ids[7])).isEqualTo(MediaRef.Phone(VOLUME, 1_007, null))

    // Preferiti: una transazione, un cambiamento, il flag nella cella e nel filtro.
    actions.setFavorite(setOf(ids[1], ids[2]), on = true)
    repo.caughtUp()
    val favorites = repo.view(LibraryFilter.FAVORITES).value
    assertThat(favorites.size).isEqualTo(2)
    assertThat(repo.snapshot.value!!.let { s -> s.hasFlag(s.indexOf(ids[1]), CellFlags.FAVORITE) }).isTrue()

    // Nascosti (E-H1): fuori dall'indice, dalle viste e dal file.
    actions.setHidden(setOf(ids[3], ids[4]), on = true)
    repo.caughtUp()
    assertThat(listOf(ids[3], ids[4]).all { repo.snapshot.value!!.indexOf(it) < 0 }).isTrue()
    assertThat(repo.view(LibraryFilter.ALL).value.viewIndexOf(ids[3])).isEqualTo(-1)
    store.awaitCurrent()
    assertThat(store.read()!!.snapshot.indexOf(ids[3])).isEqualTo(-1)
    assertSameIndex(repo.snapshot.value!!, fresh())

    // Un gruppo: resta la copertina, con il numero dei membri.
    catalog.group(MediaGroupKind.BURST, listOf(ids[10], ids[11], ids[12]), cover = ids[11])
    repo.caughtUp()
    var s = repo.snapshot.value!!
    assertThat(s.indexOf(ids[10])).isEqualTo(-1)
    assertThat(s.indexOf(ids[12])).isEqualTo(-1)
    assertThat(s.groupSizeAt(s.indexOf(ids[11]))).isEqualTo(3)
    assertThat(s.hasFlag(s.indexOf(ids[11]), CellFlags.GROUP_COVER)).isTrue()
    // Nascondere la cella nasconde il gruppo intero: non compare un altro membro al posto della copertina.
    actions.setHidden(setOf(ids[11]), on = true)
    repo.caughtUp()
    s = repo.snapshot.value!!
    assertThat(listOf(ids[10], ids[11], ids[12]).all { s.indexOf(it) < 0 }).isTrue()
    actions.setHidden(setOf(ids[11]), on = false)
    repo.caughtUp()
    s = repo.snapshot.value!!
    assertThat(s.groupSizeAt(s.indexOf(ids[11]))).isEqualTo(3)
    assertSameIndex(repo.snapshot.value!!, fresh())

    // META: data, proporzione, luogo e segnaposto cambiano la riga (e l'ordine).
    val thumb = ThumbHash.of(ByteArray(25) { (it * 3).toByte() })
    val version = catalog.media(ids[200])!!.currentVersionId
    catalog.updateMetadata(
      version,
      MediaMetadata(
        mimeType = "image/jpeg", width = 4000, height = 3000, orientationDegrees = 0,
        captureTime = CaptureTime(T0 + 86_400_000L, 60, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
        thumbHash = thumb,
      ),
    )
    repo.caughtUp()
    s = repo.snapshot.value!!
    assertThat(s.idAt(0)).isEqualTo(ids[200])
    assertThat(s.aspectMilliAt(0)).isEqualTo(1333)
    assertThat(s.thumbHashAt(0)).isEqualTo(thumb)
    // Media nuovi: entrano al loro posto.
    record(300 until 320)
    repo.caughtUp()
    assertSameIndex(repo.snapshot.value!!, fresh())
    assertThat(repo.rebuilds).isEqualTo(1)
    assertThat(repo.patches).isAtLeast(5)

    // Avvio a freddo: un altro repository parte dal file e non ricostruisce.
    repo.flush()
    store.awaitCurrent()
    val (cold, _) = repository()
    cold.start()
    withTimeout(10_000) { cold.reconciled.first { it } }
    assertThat(cold.rebuilds).isEqualTo(0)
    assertSameIndex(cold.snapshot.value!!, repo.snapshot.value!!)
  }

  @Test
  fun centoMilaRigheDalCursorEntroIlBudget() {
    val library = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT)
    val columns = arrayOf("media_id", "sort_time", "local_day", "kind", "source", "cell_flags", "aspect_milli", "duration_ms", "place_id", "thumb_hash", "group_id")
    val cursor = MatrixCursor(columns, library.count)
    for (row in library.indexRows()) {
      cursor.addRow(arrayOf<Any?>(row.mediaId, row.sortTime, row.localDay, row.kind, row.source, row.cellFlags, row.aspectMilli, row.durationMs, row.placeId, row.thumbHash, row.groupId))
    }
    val sizes = library.groupMembers.groupingBy { it.groupId!!.value }.eachCount().mapValues { it.value + 1 }
    var built: dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot? = null
    val ms = TimelineIndexTest.timeMs { cursor.use { built = TimelineIndexReader.read(it, 1, sizes) } }
    println("CURSOR_BUDGET: 100k righe dal Cursor (MatrixCursor, Robolectric) = ${ms}ms")
    assertSameIndex(built!!, library.snapshot)
    assertThat(ms).isLessThan(10_000)
  }

  private companion object {
    const val T0 = 1_760_000_000_000L
    const val VOLUME = "external_primary"
    const val STORE = "store-v1"
  }
}
