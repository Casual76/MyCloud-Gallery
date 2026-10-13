package dev.pampa.mycloudgallery.feature.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.drawToBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.robolectric.Robolectric

/**
 * I tasselli (telephoto) su un file vero: E-V3 ed E-M15, **gli otto orientamenti EXIF**, anche quelli specchiati (2, 4,
 * 5, 7), si vedono sempre dritti. Il file e' un JPEG di quattro quadranti di colore diverso (rosso, verde, blu, giallo),
 * con l'orientamento scritto nell'EXIF; il provider finto lo serve come farebbe il MediaStore (`openFile`).
 *
 * Se questo ambiente non sa decodificare per regioni (`BitmapRegionDecoder`), la prova si salta e il controllo resta
 * al campo (F12): la proiezione per regioni non e' una cosa che Robolectric garantisca.
 */
class ViewerTilesTest : ViewerTestBase() {

  /** Un provider che serve i file di prova come un provider di media. */
  class TestMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      val file = files[uri.lastPathSegment] ?: throw java.io.FileNotFoundException(uri.toString())
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
      val files: MutableMap<String, File> = HashMap()
    }
  }

  private val red = Color.rgb(230, 30, 30)
  private val green = Color.rgb(30, 200, 30)
  private val blue = Color.rgb(30, 30, 230)
  private val yellow = Color.rgb(240, 220, 20)

  /** 64 x 48: rosso in alto a sinistra, verde in alto a destra, blu in basso a sinistra, giallo in basso a destra. */
  private fun quadrants(): Bitmap {
    val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = red
    canvas.drawRect(0f, 0f, 32f, 24f, paint)
    paint.color = green
    canvas.drawRect(32f, 0f, 64f, 24f, paint)
    paint.color = blue
    canvas.drawRect(0f, 24f, 32f, 48f, paint)
    paint.color = yellow
    canvas.drawRect(32f, 24f, 64f, 48f, paint)
    return bitmap
  }

  /** Come si vede un file con l'orientamento EXIF [orientation]: le stesse matrici di ogni libreria di immagini. */
  private fun upright(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      2 -> matrix.setScale(-1f, 1f)
      3 -> matrix.setRotate(180f)
      4 -> {
        matrix.setRotate(180f)
        matrix.postScale(-1f, 1f)
      }
      5 -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      6 -> matrix.setRotate(90f)
      7 -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      8 -> matrix.setRotate(-90f)
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
  }

  private fun write(orientation: Int): File {
    val file = File.createTempFile("orientamento$orientation", ".jpg")
    file.deleteOnExit()
    file.outputStream().use { quadrants().compress(Bitmap.CompressFormat.JPEG, 100, it) }
    ExifInterface(file.absolutePath).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      saveAttributes()
    }
    return file
  }

  private fun close(a: Int, b: Int): Boolean {
    fun d(x: Int, y: Int) = kotlin.math.abs(x - y)
    return d(Color.red(a), Color.red(b)) < 70 && d(Color.green(a), Color.green(b)) < 70 && d(Color.blue(a), Color.blue(b)) < 70
  }

  @Test
  fun gliOttoOrientamentiSiVedonoSempreDritti() {
    Robolectric.setupContentProvider(TestMediaProvider::class.java, "media")
    var current by mutableIntStateOf(1)
    // Le dimensioni che la sorgente dichiara sono quelle del file **dopo** l'orientamento, come farebbe quella vera.
    val tiledImages = PhoneAwareImages(
      mapper = { ref ->
        if (current in 5..8) MediaRef.Synthetic(ref.mediaStoreId, 48, 64) else MediaRef.Synthetic(ref.mediaStoreId, 64, 48)
      },
    ).apply { tiled = true }
    val index = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
    val request = fixture.request(startIndex = index)
    val services = services(imageSource = tiledImages)
    // Il file c'e' prima che il viewer si apra: una lettura che fallisce fa ripiegare la pagina sulla bitmap nitida.
    TestMediaProvider.files["${1000L + index}"] = write(1)
    composeRule.setContent {
      ViewerTestHost { key(current) { ViewerOverlay(request = request, onClosed = {}, services = services) } }
    }
    val failures = mutableListOf<String>()
    var checked = 0
    for (orientation in 1..8) {
      TestMediaProvider.files["${1000L + index}"] = write(orientation)
      composeRule.runOnUiThread { current = orientation }
      composeRule.waitForIdle()
      // Senza barre: il vetro (RuntimeShader) non si disegna su una Canvas software, che e' come si legge lo schermo qui.
      composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { click(center) }
      composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes().isEmpty() }
      composeRule.mainClock.advanceTimeBy(700)
      val expected = upright(quadrants(), orientation)
      var sample: Map<String, Int>? = null
      try {
        // Si aspetta che i tasselli veri siano disegnati: sotto c'e' il gradiente dell'anteprima sintetica, che qui non conta.
        composeRule.waitUntil(15_000L) {
          val image = composeRule.activity.window.decorView.drawToBitmap()
          val top = image.height * 38 / 100
          val bottom = image.height * 62 / 100
          val colors = mapOf(
            "TL" to image.getPixel(image.width * 25 / 100, top),
            "TR" to image.getPixel(image.width * 75 / 100, top),
            "BL" to image.getPixel(image.width * 25 / 100, bottom),
            "BR" to image.getPixel(image.width * 75 / 100, bottom),
          )
          sample = colors
          colors.values.all { c -> listOf(red, green, blue, yellow).any { close(it, c) } }
        }
      } catch (_: ComposeTimeoutException) {
        // Resta l'ultimo campione per il messaggio.
      }
      val colors = sample ?: continue
      val wanted = mapOf(
        "TL" to expected.getPixel(expected.width / 4, expected.height / 4),
        "TR" to expected.getPixel(expected.width * 3 / 4, expected.height / 4),
        "BL" to expected.getPixel(expected.width / 4, expected.height * 3 / 4),
        "BR" to expected.getPixel(expected.width * 3 / 4, expected.height * 3 / 4),
      )
      checked++
      for ((k, v) in wanted) {
        if (!close(v, colors.getValue(k))) failures += "orientamento $orientation, $k: atteso ${Integer.toHexString(v)}, visto ${Integer.toHexString(colors.getValue(k))}"
      }
    }
    assumeTrue("L'ambiente non decodifica per regioni: si prova sul campo (F12)", checked > 0)
    assertThat(failures).isEmpty()
  }
}
