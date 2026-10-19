package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.ContentUris
import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.InlineDispatchers
import dev.pampa.mycloudgallery.core.mediastore.sync.OriginalBytesUnavailableException
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncConfig
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import java.io.IOException
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * L'adattatore vero sul MediaStore, su Robolectric con un provider `media` finto (docs/fase3-progetto.md §4.3, P2):
 * query con `Bundle` (selezione, ordine, limite, `QUERY_ARG_MATCH_*`), `call` per versione e generazione, `openFile` con
 * `setRequireOriginal`, `deleted_files` e cestino di sistema. Il SQL che l'adattatore scrive gira su un SQLite vero.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreSourceTest {

  private lateinit var provider: FakeMediaProvider
  private var location = true
  private var deletedFiles = true
  private lateinit var source: AndroidMediaStoreSource
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Before
  fun register() {
    provider = registerMediaProvider()
    provider.versions["external_primary"] = "v1"
    provider.generations["external_primary"] = 40
    source = newSource()
  }

  private fun newSource() = AndroidMediaStoreSource(
    context = context,
    volumeNames = { setOf("external_primary", "NON VALIDO") },
    deletedFilesSupported = { deletedFiles },
    hasMediaLocation = { location },
    changeWindow = 1_500.milliseconds,
  )

  @Test
  fun volumiVersioneEGenerazioneArrivanoDalMediaStore() = runBlocking<Unit> {
    assertThat(source.volumes()).containsExactly("external_primary")
    assertThat(source.version("external_primary")).isEqualTo("v1")
    assertThat(source.generation("external_primary")).isEqualTo(40L)
  }

  @Test
  fun leRigheArrivanoDallePiuRecentiConLaRipresaAChiave() = runBlocking<Unit> {
    provider.insertRow(1, taken = 1_000)
    provider.insertRow(2, taken = 5_000)
    provider.insertRow(3, taken = 3_000, mediaType = 3, name = "VID.mp4")
    provider.insertRow(4, taken = null, name = "senza data.png", mime = "image/png")
    provider.insertRow(5, taken = 5_000)
    provider.insertRow(6, taken = 9_000, pending = true)
    provider.insertRow(7, taken = 8_000, trashed = true)

    val first = source.query("external_primary", sinceGeneration = null, afterId = null, limit = 2)
    assertThat(first.map { it.id }).containsExactly(5L, 2L).inOrder()
    val second = source.query("external_primary", null, afterId = 2, limit = 2)
    assertThat(second.map { it.id }).containsExactly(3L, 1L).inOrder()
    // Le righe senza data in fondo; pending e cestinate mai.
    val third = source.query("external_primary", null, afterId = 1, limit = 2)
    assertThat(third.map { it.id }).containsExactly(4L)
    assertThat(source.query("external_primary", null, afterId = 4, limit = 2)).isEmpty()

    val video = second.first()
    assertThat(video.type).isEqualTo(RowType.VIDEO)
    assertThat(video.durationMs).isEqualTo(5_000L)
    assertThat(video.dateModifiedMs).isEqualTo(1_760_000_000_000L)
    assertThat(video.relativePath).isEqualTo("DCIM/Camera/")
    assertThat(video.generationModified).isEqualTo(3L)
    assertThat(video.size).isEqualTo(16L)
    assertThat(third.single().dateTakenMs).isNull()

    // Il Bundle chiede esplicitamente di escludere pending e cestinate.
    val args = provider.queryArgs.first()
    assertThat(args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING)).isEqualTo(MediaStore.MATCH_EXCLUDE)
    assertThat(args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED)).isEqualTo(MediaStore.MATCH_EXCLUDE)
  }

  @Test
  fun laRipresaDopoUnRiavvioRileggeLaChiaveEDopoUnaRigaSparitaRicominciaDaCapo() = runBlocking<Unit> {
    provider.insertRow(1, taken = 1_000)
    provider.insertRow(2, taken = 2_000)
    provider.insertRow(3, taken = 3_000)
    // Processo nuovo: la chiave della riga 3 non e' in memoria, si chiede al MediaStore.
    assertThat(newSource().query("external_primary", null, afterId = 3, limit = 10).map { it.id }).containsExactly(2L, 1L).inOrder()
    // Riga di ripresa sparita del tutto: si riparte dall'inizio (la scorciatoia del timbro rende economico il ripasso).
    provider.deleteRow(3, generation = 50)
    assertThat(newSource().query("external_primary", null, afterId = 3, limit = 10).map { it.id }).containsExactly(2L, 1L).inOrder()
    assertThat(newSource().query("external_primary", null, afterId = 99, limit = 10).map { it.id }).containsExactly(2L, 1L).inOrder()
  }

  @Test
  fun incrementalePerGenerazione() = runBlocking<Unit> {
    provider.insertRow(1, generation = 10)
    provider.insertRow(2, generation = 20)
    provider.insertRow(3, generation = 30)
    assertThat(source.query("external_primary", sinceGeneration = 15, afterId = null, limit = 10).map { it.id }).containsExactly(3L, 2L)
  }

  @Test
  fun leCancellazioniUnisconoDeletedFilesECestinoDiSistema() = runBlocking<Unit> {
    provider.insertRow(1, generation = 10)
    provider.insertRow(2, generation = 11)
    provider.insertRow(3, generation = 12)
    provider.deleteRow(1, generation = 20)
    provider.deleteRow(9, generation = 5)
    provider.deleteRow(8, generation = 21, mediaType = 0)
    provider.trashRow(2, generation = 22)

    val gone = source.deletedSince("external_primary", 15)!!
    assertThat(gone.map { it.id to it.generationModified }).containsExactly(1L to 20L, 2L to 22L)

    // Senza l'estensione: solo il cestino di sistema.
    deletedFiles = false
    assertThat(source.deletedSince("external_primary", 15)!!.map { it.id }).containsExactly(2L)
    // Estensione dichiarata ma tabella rotta: non e' un errore del giro.
    deletedFiles = true
    provider.deletedFilesBroken = true
    assertThat(source.deletedSince("external_primary", 15)!!.map { it.id }).containsExactly(2L)
  }

  @Test
  fun allIdsERigheperIdEscludonoPendingECestinate() = runBlocking<Unit> {
    provider.insertRow(1)
    provider.insertRow(2, pending = true)
    provider.insertRow(3, trashed = true)
    provider.insertRow(4, mediaType = 3)
    assertThat(source.allIds("external_primary").toList()).containsExactly(1L, 4L)
    assertThat(source.rowsById("external_primary", listOf(1L, 2L, 3L, 4L, 5L)).map { it.id }).containsExactly(1L, 4L)
  }

  @Test
  fun unCursoreNulloEUnErroreNonUnaLibreriaVuota() {
    provider.nullCursor = true
    assertThrows(IOException::class.java) { runBlocking { source.query("external_primary", null, null, 10) } }
    assertThrows(IOException::class.java) { runBlocking { source.allIds("external_primary") } }
  }

  @Test
  fun leFotoSiApronoSoloOriginaliEIVideoSempre() = runBlocking<Unit> {
    val content = FakeMediaContent.jpeg(200_000, seed = 5)
    provider.insertRow(1, content = content)
    provider.insertRow(2, mediaType = 3, content = FakeMediaContent.mp4(1_000, seed = 6))
    val photo = source.query("external_primary", null, null, 10).first { it.id == 1L }
    val video = source.query("external_primary", null, null, 10).first { it.id == 2L }

    // Con ACCESS_MEDIA_LOCATION: setRequireOriginal, letture a intervalli corrette.
    val bytes = source.open(photo)
    try {
      assertThat(bytes.size).isEqualTo(200_000L)
      assertThat(bytes.read(0L until 4L)).isEqualTo(content.copyOfRange(0, 4))
      assertThat(bytes.read(199_990L until 200_100L)).isEqualTo(content.copyOfRange(199_990, 200_000))
      assertThat(bytes.read(300_000L until 300_010L)).isEmpty()
    } finally {
      (bytes as AutoCloseable).close()
    }
    assertThat(MediaStore.getRequireOriginal(provider.openedUris.last())).isTrue()
    assertThat(provider.openedUris.last().pathSegments).contains("images")

    // Senza: la foto non si apre (D9) e il provider non e' nemmeno interpellato; il video si'.
    location = false
    val opened = provider.openedUris.size
    assertThrows(OriginalBytesUnavailableException::class.java) { runBlocking { source.open(photo) } }
    assertThat(provider.openedUris).hasSize(opened)
    (source.open(video) as AutoCloseable).close()
    assertThat(MediaStore.getRequireOriginal(provider.openedUris.last())).isFalse()
    assertThat(provider.openedUris.last().pathSegments).contains("video")

    // Permesso ritirato fra il controllo e l'apertura: e' "in attesa", non un errore di permessi.
    location = true
    provider.locationGranted = false
    assertThrows(OriginalBytesUnavailableException::class.java) { runBlocking { source.open(photo) } }
  }

  /**
   * R6 della revisione 5A: `size` e' la dimensione del momento dell'apertura, `currentSize()` e' un `fstat` di adesso. Un file che cresce
   * dopo l'apertura (e dopo l'ultima lettura del MediaStore) si vede solo da qui: il backup lo confronta con la dimensione del MediaStore
   * dopo aver letto, e una copia che sarebbe un prefisso non diventa mai "al sicuro".
   */
  @Test
  fun r6_laDimensioneDiAdessoVedeUnFileCheCresceDopoLApertura() = runBlocking<Unit> {
    provider.insertRow(1, mediaType = 3, content = FakeMediaContent.mp4(50_000, seed = 7))
    val video = source.query("external_primary", null, null, 10).first { it.id == 1L }
    val bytes = source.open(video) as ContentUriMediaBytes
    try {
      assertThat(bytes.size).isEqualTo(50_000L)
      assertThat(bytes.currentSize()).isEqualTo(50_000L)
      // Il file cresce di 3 KiB mentre e' aperto: la dimensione dell'apertura non cambia, quella di adesso si'.
      provider.fileOf(1L)!!.appendBytes(ByteArray(3_072) { 9 })
      assertThat(bytes.size).isEqualTo(50_000L)
      assertThat(bytes.currentSize()).isEqualTo(53_072L)
      // La lettura resta dentro la dimensione dell'apertura (contratto di `MediaBytes`): oltre, un intervallo vuoto.
      assertThat(bytes.read(50_000L until 50_001L)).isEmpty()
    } finally {
      bytes.close()
    }
  }

  @Test
  fun iCambiamentiSiAccorpano() = runTest {
    val emitted = mutableListOf<Unit>()
    val job = launch { source.changes().toList(emitted) }
    runCurrent()
    // MediaProvider notifica anche sull'alias `external`, con l'_ID in coda: l'osservatore guarda i discendenti.
    repeat(5) { i ->
      context.contentResolver.notifyChange(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri("external"), i.toLong() + 1), null)
    }
    runCurrent()
    assertThat(emitted).isEmpty()
    advanceTimeBy(1_600)
    assertThat(emitted).hasSize(1)
    context.contentResolver.notifyChange(ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri("external"), 9), null)
    advanceTimeBy(1_600)
    assertThat(emitted).hasSize(2)
    job.cancel()
  }

  @Test
  fun laSincronizzazioneVeraSulMediaStoreFinto() = runBlocking<Unit> {
    val db = GalleryDatabase.build(context, name = null)
    try {
      var now = 1_760_000_000_000L
      val catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { now }
      val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf("external_primary")))
      val sync = DefaultPhoneLibrarySync(
        source = source, access = access, catalog = catalog, reads = RoomPhoneCatalogReads(db), clock = { now },
        dispatchers = InlineDispatchers, config = PhoneSyncConfig(batchSize = 2),
      )
      (1L..5L).forEach { provider.insertRow(it, content = FakeMediaContent.jpeg(1_000, seed = it), generation = it) }
      provider.generations["external_primary"] = 5

      val first = sync.syncNow(SyncReason.APP_START)
      assertThat(first.added).isEqualTo(5)
      assertThat(db.mediaDao().count()).isEqualTo(5)

      // Una foto nuova e una cancellata: incrementale e deleted_files.
      provider.insertRow(6, content = FakeMediaContent.jpeg(1_000, seed = 6), generation = 6)
      provider.deleteRow(2, generation = 7)
      provider.generations["external_primary"] = 7
      now += 60_000
      val second = sync.syncNow(SyncReason.CONTENT_CHANGE)
      assertThat(second.fullScan).isFalse()
      assertThat(second.added).isEqualTo(1)
      assertThat(second.vanished).isEqualTo(1)
      val gone = Location.Phone("external_primary", "DCIM/Camera/", "IMG_2.jpg").key.value
      assertThat(db.locationDao().byKey(gone)!!.state).isEqualTo(LocationState.ABSENT.code)
    } finally {
      db.close()
    }
  }

  companion object {
    /** Registra il provider finto per l'autorita' `media`. */
    fun registerMediaProvider(): FakeMediaProvider {
      val info = ProviderInfo().apply { authority = MediaStore.AUTHORITY }
      val controller = Robolectric.buildContentProvider(FakeMediaProvider::class.java).create(info)
      return controller.get()
    }
  }
}
