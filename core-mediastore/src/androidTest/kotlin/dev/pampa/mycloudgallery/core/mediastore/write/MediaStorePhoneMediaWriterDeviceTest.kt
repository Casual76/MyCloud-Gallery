package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Scarica sul telefono" sul MediaProvider **vero** (emulatore `Medium_Phone` o telefono; docs/fase4-progetto.md §5.1:
 * "Strumentati su emulatore: `IS_PENDING`", F11). Le prove di JVM (`MediaStorePhoneMediaWriterTest`) usano un provider finto
 * che ricalca il MediaProvider; qui si controlla che il vero faccia lo stesso: la riga in attesa non si vede, il nome in
 * conflitto, la posizione scritta, la ripulitura delle righe lasciate da un processo morto.
 *
 * Le fixture vanno in `Pictures/MCG-prova-P8/` (non nella cartella vera dell'app) e si cancellano alla fine: nessun dato
 * dell'utente. Per lanciarle: `gradlew :core-mediastore:connectedDebugAndroidTest` con un dispositivo collegato.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhoneMediaWriterDeviceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val resolver: ContentResolver = context.contentResolver
  private val folder = "Pictures/MCG-prova-P8/"
  private val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

  private val content = ByteArray(300_000) { (it % 251).toByte() }
  private val request get() = SaveRequest("prova.jpg", "image/jpeg", content.size.toLong())

  private fun newWriter() = MediaStorePhoneMediaWriter(context, relativePath = folder)

  private fun source(bytes: ByteArray = content, between: suspend () -> Unit = {}) = PhoneSaveSource { sink ->
    var offset = 0
    while (offset < bytes.size) {
      val n = minOf(65_536, bytes.size - offset)
      sink(bytes.copyOfRange(offset, offset + n), n)
      offset += n
      between()
    }
  }

  @Before
  @After
  fun cleanUp() {
    // Le righe di prova, in attesa e cestinate comprese.
    val anyState = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ?")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder))
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    runCatching { resolver.delete(files, anyState) }
  }

  /** Le righe della cartella di prova, con la visibilita' scelta: `(nome, dimensione)`. */
  private fun rows(pending: Int): List<Pair<String, Long>> {
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ?")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder))
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, pending)
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    val out = ArrayList<Pair<String, Long>>()
    resolver.query(files, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), args, null)!!.use {
      while (it.moveToNext()) out += it.getString(0) to it.getLong(1)
    }
    return out
  }

  @Test
  fun laRigaInAttesaNonSiVedeEIlFileCompareSoloAllaFine() = runBlocking<Unit> {
    var visibleWhileCopying = -1
    var pendingWhileCopying = -1
    var visibleAtLink = -1

    val result = newWriter().saveToPictures(
      request = request,
      source = source(between = {
        visibleWhileCopying = rows(MediaStore.MATCH_EXCLUDE).size
        pendingWhileCopying = rows(MediaStore.MATCH_ONLY).size
      }),
      expectedSha256 = Sha256.of(content),
      beforePublish = { visibleAtLink = rows(MediaStore.MATCH_EXCLUDE).size },
    )

    result as SaveResult.Saved
    assertEquals(0, visibleWhileCopying)
    assertEquals(1, pendingWhileCopying)
    assertEquals(0, visibleAtLink)
    assertEquals(listOf("prova.jpg" to content.size.toLong()), rows(MediaStore.MATCH_EXCLUDE))
    assertEquals(0, rows(MediaStore.MATCH_ONLY).size)
    val location = result.copy.location
    assertEquals("external_primary", location.volume)
    assertEquals(folder, location.relativePath)
    assertEquals("prova.jpg", location.displayName)
    assertEquals(MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY), location.mediaStoreVersion)
    // I byte scritti sono quelli arrivati.
    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), location.mediaStoreId!!)
    assertArrayEquals(content, resolver.openInputStream(uri)!!.use { it.readBytes() })
  }

  @Test
  fun unNomeInConflittoDiventaConDueEIlProviderNonLoRinomina() = runBlocking<Unit> {
    val writer = newWriter()

    val first = writer.saveToPictures(request, source()) as SaveResult.Saved
    val second = writer.saveToPictures(request, source()) as SaveResult.Saved

    assertEquals("prova.jpg", first.copy.location.displayName)
    assertEquals("prova (2).jpg", second.copy.location.displayName)
    assertEquals(setOf("prova.jpg", "prova (2).jpg"), rows(MediaStore.MATCH_EXCLUDE).map { it.first }.toSet())
  }

  @Test
  fun unaSorgenteInterrottaOAnnullataNonLasciaNiente() = runBlocking<Unit> {
    val broken = PhoneSaveSource { sink ->
      sink(content, 100_000)
      throw java.io.IOException("NAS sparito")
    }
    assertEquals(SaveResult.Failed("SOURCE_FAILED"), newWriter().saveToPictures(request, broken))
    assertEquals(0, rows(MediaStore.MATCH_INCLUDE).size)

    val halfway = CompletableDeferred<Unit>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      newWriter().saveToPictures(
        request,
        PhoneSaveSource { sink ->
          sink(content, 100_000)
          halfway.complete(Unit)
          awaitCancellation()
        },
      )
    }
    halfway.await()
    job.cancelAndJoin()
    assertEquals(0, rows(MediaStore.MATCH_INCLUDE).size)
  }

  @Test
  fun unHashDiversoNonLasciaNienteDiVisibile() = runBlocking<Unit> {
    val result = newWriter().saveToPictures(request, source(), expectedSha256 = Sha256.of(ByteArray(3)))

    assertEquals(SaveResult.Failed("HASH_MISMATCH"), result)
    assertEquals(0, rows(MediaStore.MATCH_INCLUDE).size)
  }

  @Test
  fun leRigheInAttesaDiUnProcessoMortoSiRipulisconoAlGiroDopo() = runBlocking<Unit> {
    // Un processo morto a meta' lascia questo: una riga IS_PENDING con dei byte, mai pubblicata.
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, "orfano.jpg")
      put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val orphan = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
    resolver.openOutputStream(orphan, "w")!!.use { it.write(content, 0, 1_000) }
    assertEquals(1, rows(MediaStore.MATCH_ONLY).size)
    assertEquals(0, rows(MediaStore.MATCH_EXCLUDE).size)

    val removed = newWriter().purgeOrphans()

    assertEquals(1, removed)
    assertEquals(0, rows(MediaStore.MATCH_INCLUDE).size)
    assertFalse(rows(MediaStore.MATCH_INCLUDE).any { it.first == "orfano.jpg" })
    assertTrue(removed >= 1)
  }
}
