package dev.pampa.mycloudgallery.core.mediastore

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncConfig
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'adattatore sul MediaProvider **vero** dell'emulatore (`Medium_Phone`, API 37.1): la grammatica stretta del provider
 * accetta selezione a chiave, ordine e `QUERY_ARG_MATCH_*`; `getVersion`/`getGeneration` per volume; `setRequireOriginal`;
 * `deleted_files` e cestino di sistema. Le fixture sono JPEG creati qui, in `Pictures/MCG-prova-P2/`, e cancellati alla fine:
 * nessun dato dell'utente.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreSourceDeviceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val resolver = context.contentResolver
  private val inserted = ArrayList<Uri>()

  @Before
  fun grant() {
    listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
      // Gia' concessi da `adb install -g`: UiAutomation (una sola connessione per volta) solo se serve.
      .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
      .forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
  }

  @After
  fun cleanUp() {
    // Le fixture, anche se finite nel cestino di sistema durante la prova.
    val anyState = Bundle().apply {
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }
    inserted.forEach { uri -> runCatching { resolver.delete(uri, anyState) } }
  }

  private fun jpeg(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      out.toByteArray()
    }
  }

  /** Una foto scritta come fa una fotocamera: `IS_PENDING` durante la scrittura, poi pubblicata. */
  private fun insert(name: String, bytes: ByteArray): Uri {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
    inserted += uri
    checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    return uri
  }

  private fun List<MediaStoreRow>.ours(): List<MediaStoreRow> = filter { it.relativePath == FOLDER }

  @Test
  fun query_ripresa_apertura_e_cancellazioni_sul_mediaProvider_vero() = runBlocking<Unit> {
    val source = AndroidMediaStoreSource(context)
    assertTrue(source.volumes().contains(VOLUME))
    val version = source.version(VOLUME)
    assertTrue(version.isNotEmpty())
    val g0 = checkNotNull(source.generation(VOLUME))

    val bytes = listOf(jpeg(Color.RED), jpeg(Color.GREEN), jpeg(Color.BLUE))
    val uris = bytes.mapIndexed { i, b -> insert("mcg-p2-$i.jpg", b) }
    val g1 = checkNotNull(source.generation(VOLUME))
    assertTrue(g1 > g0)

    // Incrementale: le tre righe nuove, dalle piu' recenti, con il timbro giusto.
    val rows = source.query(VOLUME, sinceGeneration = g0, afterId = null, limit = 500).ours()
    assertEquals(3, rows.size)
    rows.forEach { row ->
      assertNotNull(row.generationModified)
      assertTrue(row.generationModified!! > g0)
      assertEquals(bytes[row.displayName.removePrefix("mcg-p2-").removeSuffix(".jpg").toInt()].size.toLong(), row.size)
    }

    // Ripresa a chiave dopo la prima riga: la grammatica stretta del provider accetta la selezione.
    val all = source.query(VOLUME, sinceGeneration = g0, afterId = null, limit = 500)
    val resumed = source.query(VOLUME, sinceGeneration = g0, afterId = all.first().id, limit = 500)
    assertEquals(all.drop(1).map { it.id }, resumed.map { it.id })
    // Lo stesso da un processo "nuovo" (la chiave si rilegge dal MediaStore).
    val fresh = AndroidMediaStoreSource(context).query(VOLUME, sinceGeneration = g0, afterId = all.first().id, limit = 500)
    assertEquals(resumed.map { it.id }, fresh.map { it.id })

    // Apertura dell'originale e letture a intervalli.
    val first = rows.first()
    val expected = bytes[first.displayName.removePrefix("mcg-p2-").removeSuffix(".jpg").toInt()]
    val opened = source.open(first)
    try {
      assertEquals(expected.size.toLong(), opened.size)
      assertArrayEquals(expected.copyOfRange(0, 16), opened.read(0L until 16L))
      assertArrayEquals(expected.copyOfRange(expected.size - 8, expected.size), opened.read((expected.size - 8L) until expected.size.toLong()))
    } finally {
      (opened as AutoCloseable).close()
    }

    assertTrue(source.allIds(VOLUME).toSet().containsAll(rows.map { it.id }))
    assertEquals(rows.map { it.id }.toSet(), source.rowsById(VOLUME, rows.map { it.id }).map { it.id }.toSet())

    // Una cancellata: la query di deleted_files e' accettata dal provider (risposta non nulla). Il contenuto non si
    // controlla: sull'emulatore API 37 la tabella risponde VUOTA alle app (dalla shell elenca le righe), per questo la
    // sincronizzazione non ne dipende e riconcilia gli _ID quando la generazione sale senza spiegazione.
    val victim = rows.last()
    val victimUri = uris[victim.displayName.removePrefix("mcg-p2-").removeSuffix(".jpg").toInt()]
    assertEquals(1, resolver.delete(victimUri, null, null))
    inserted -= victimUri
    assertNotNull(source.deletedSince(VOLUME, g1))
    assertTrue(checkNotNull(source.generation(VOLUME)) > g1)
    assertFalse(source.allIds(VOLUME).contains(victim.id))
  }

  @Test
  fun cestinoDiSistema_eliminati_di_recente_e_righe_tolte() = runBlocking<Unit> {
    val source = AndroidMediaStoreSource(context)
    val uri = insert("mcg-p2-cestino.jpg", jpeg(Color.YELLOW))
    val row = source.query(VOLUME, null, null, 500).ours().single { it.displayName == "mcg-p2-cestino.jpg" }
    val before = checkNotNull(source.generation(VOLUME))

    // Il proprietario della riga puo' cestinarla da se' (l'app lo fa sempre col dialogo di sistema, D14).
    val trashed = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
    org.junit.Assume.assumeTrue("Cestino diretto non permesso su questa immagine", trashed == 1)

    assertFalse(source.query(VOLUME, null, null, 500).any { it.id == row.id })
    assertTrue(checkNotNull(source.deletedSince(VOLUME, before)).any { it.id == row.id })
    val item = AndroidRecentlyDeletedSource(context).list().first { it.ref.mediaStoreId == row.id }
    assertEquals(VOLUME, item.ref.volume)
    assertTrue(item.expiresAtMs > System.currentTimeMillis())
  }

  @Test
  fun sincronizzazione_completa_incrementale_e_cancellazione_sul_mediaProvider_vero() = runBlocking<Unit> {
    val db = GalleryDatabase.build(context, name = null)
    try {
      val catalog = MediaCatalog(db, AccountId.of("prova"))
      val reads = RoomPhoneCatalogReads(db)
      val sync = DefaultPhoneLibrarySync(
        source = AndroidMediaStoreSource(context),
        access = AndroidPhoneAccessReader(context),
        catalog = catalog,
        reads = reads,
        // Riconciliazione rapida sempre ammessa: qui i giri sono a pochi secondi di distanza.
        config = PhoneSyncConfig(batchSize = 20, quickReconcileEvery = Duration.ZERO),
      )
      // Una fixture che resta: un MediaStore che dopo la cancellazione risponde vuoto e' trattato come sospetto (nessuna
      // assenza), e su questo emulatore l'app di prova vede solo i propri file.
      insert("mcg-p2-resta.jpg", jpeg(Color.CYAN))
      val first = sync.syncNow(SyncReason.APP_START)
      assertTrue(first.completed)
      assertTrue(first.fullScan)

      // Una foto nuova: l'incrementale per generazione la trova e la registra.
      val uri = insert("mcg-p2-sync.jpg", jpeg(Color.MAGENTA))
      val added = sync.syncNow(SyncReason.CONTENT_CHANGE)
      assertTrue(added.completed)
      assertFalse(added.fullScan)
      val place = Location.Phone(VOLUME, FOLDER, "mcg-p2-sync.jpg")
      val mediaId = checkNotNull(reads.mediaIdAt(place.key))
      assertEquals(LocationState.PRESENT.code, db.locationDao().byKey(place.key.value)!!.state)

      // Cancellata: anche se deleted_files risponde vuota, la riconciliazione rapida la rende assente.
      assertEquals(1, resolver.delete(uri, null, null))
      inserted -= uri
      val gone = sync.syncNow(SyncReason.CONTENT_CHANGE)
      assertTrue(gone.completed)
      assertEquals(1, gone.vanished)
      assertEquals(LocationState.ABSENT.code, db.locationDao().byKey(place.key.value)!!.state)
      assertEquals(mediaId, reads.mediaIdAt(place.key))
    } finally {
      db.close()
    }
  }

  private companion object {
    const val VOLUME = MediaStore.VOLUME_EXTERNAL_PRIMARY
    const val FOLDER = "Pictures/MCG-prova-P2/"
  }
}
