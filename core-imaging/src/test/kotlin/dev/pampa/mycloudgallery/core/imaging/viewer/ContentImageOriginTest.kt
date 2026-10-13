package dev.pampa.mycloudgallery.core.imaging.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * `ContentImageOrigin` (il telefono, dal MediaStore) con un provider `media` finto che serve file veri. La lettura
 * passa dal `ContentResolver` vero; la decodifica con `ImageDecoder.createSource(resolver, uri)` non si prova qui
 * (Robolectric su Windows non decodifica da file descriptor): la coprono gli strumentati e T-26.
 */
@RunWith(RobolectricTestRunner::class)
class ContentImageOriginTest {

  class FileMediaProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      val file = files[uri.toString()] ?: throw FileNotFoundException("finto")
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
      val files = ConcurrentHashMap<String, File>()
    }
  }

  private val temp = mutableListOf<File>()

  @Before
  fun setUp() {
    FileMediaProvider.files.clear()
    Robolectric.buildContentProvider(FileMediaProvider::class.java).create("media")
  }

  @After
  fun tearDown() {
    temp.forEach { it.delete() }
  }

  private fun serve(uri: String, bytes: ByteArray) {
    val file = File.createTempFile("origin", ".bin").also { it.writeBytes(bytes); temp += it }
    FileMediaProvider.files[uri] = file
  }

  private val origin: ContentImageOrigin
    get() = ContentImageOrigin(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

  @Test
  fun unMediaDelTelefonoSiApreESiLeggeAFlusso() = runTest {
    val jpeg = TestImages.jpeg(TestImages.quadrants())
    serve("content://media/external_primary/images/media/7", jpeg)
    val opened = origin.open(MediaRef.Phone("external_primary", 7, "image/jpeg"))
    opened as OriginResult.Opened
    opened.bytes.use { bytes ->
      assertThat(bytes.sizeBytes).isEqualTo(jpeg.size.toLong())
      val head = bytes.openStream().use { it.readNBytes(4) }
      assertThat(head).isEqualTo(jpeg.copyOf(4))
      // Ogni apertura e' nuova: si puo' rileggere dall'inizio.
      assertThat(bytes.openStream().use { it.readBytes() }).isEqualTo(jpeg)
    }
  }

  @Test
  fun unVideoUsaIlCatalogoDeiVideo() = runTest {
    val data = ByteArray(32) { it.toByte() }
    serve("content://media/external_primary/video/media/9", data)
    val opened = origin.open(MediaRef.Phone("external_primary", 9, "video/mp4")) as OriginResult.Opened
    assertThat(opened.bytes.openStream().use { it.readBytes() }).isEqualTo(data)
  }

  @Test
  fun unMediaSparitoOUnPermessoRitiratoDannoMancanteSenzaEccezioni() = runTest {
    assertThat(origin.open(MediaRef.Phone("external_primary", 404, "image/jpeg"))).isEqualTo(OriginResult.Missing)
    // Un riferimento che non e' del telefono non ha byte da aprire qui.
    assertThat(origin.open(MediaRef.Synthetic(1, 10, 10))).isEqualTo(OriginResult.Missing)
    assertThat(origin.openLocal(MediaRef.Synthetic(1, 10, 10))).isNull()
  }

  @Test
  fun unaMicroSdUsaIlSuoVolume() = runTest {
    serve("content://media/0123-4567/images/media/3", byteArrayOf(1, 2, 3))
    val opened = origin.open(MediaRef.Phone("0123-4567", 3, "image/png")) as OriginResult.Opened
    assertThat(opened.bytes.openStream().use { it.readBytes() }).isEqualTo(byteArrayOf(1, 2, 3))
  }
}
