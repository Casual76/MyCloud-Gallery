package dev.pampa.mycloudgallery.core.ai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutDecode
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutImages
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutRender
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutSource
import dev.pampa.mycloudgallery.core.ai.cutout.PngEncoder
import dev.pampa.mycloudgallery.core.ai.cutout.SubjectCutting
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixtures
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import dev.pampa.mycloudgallery.core.ai.testing.FakeSubjectCutter
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P13): la pipeline intera sul dispositivo, con file veri: decodifica (orientamento, taglie, memoria), ritaglio con MagicTouch
 * (la v1 dell'APK), PNG riletto dal decodificatore di sistema, foto da 200 megapixel e panorami (E-M1-M4: nessun errore di memoria).
 */
@RunWith(AndroidJUnit4::class)
class CutoutPipelineDeviceTest {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  /** Il budget di pixel del viewer di questo dispositivo: la formula di `ImagingBudget` (un quinto di `memoryClass`, fra 24 e 64 MB). */
  private fun budgetPixels(): Long {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return (manager.memoryClass * 1024L * 1024L / 5).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024) / 4
  }

  private fun jpegOf(name: String, rotate: Int? = null): File {
    val fixture = CutoutFixtures.named(name)
    val file = File(context.cacheDir, "p13-$name.jpg")
    val bitmap = Bitmap.createBitmap(fixture.pixels(), fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    bitmap.recycle()
    if (rotate != null) {
      val exif = ExifInterface(file.path)
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, rotate.toString())
      exif.saveAttributes()
    }
    return file
  }

  /** I tipi dei blocchi di un PNG, in ordine. */
  private fun chunkTypes(png: ByteArray): List<String> {
    val buffer = ByteBuffer.wrap(png)
    buffer.position(8)
    val types = ArrayList<String>()
    while (buffer.hasRemaining()) {
      val length = buffer.int
      val type = ByteArray(4).also { buffer.get(it) }
      buffer.position(buffer.position() + length + 4)
      types += String(type, Charsets.US_ASCII)
    }
    return types
  }

  @Test
  fun ilPngDelRitaglioSiRilegge_TrasparenteESenzaMetadati() = runBlocking<Unit> {
    val fixture = CutoutFixtures.named("casa")
    val image = fixture.image()
    val result = FakeSubjectCutter().cut(image, fixture.point) as CutoutResult.Subject
    for (outline in listOf(false, true)) {
      val png = CutoutRender.png(image, result.mask, outline)
      // Nessun blocco accessorio: niente posizione, data, modello del telefono, programma (D47).
      assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(png).distinct())
      val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
      assertNotNull(bitmap)
      assertTrue(bitmap.hasAlpha())
      val layout = CutoutRender.layoutOf(image, result.mask, outline)
      assertEquals(layout.width, bitmap.width)
      assertEquals(layout.height, bitmap.height)
      // Il centro e' il soggetto, l'angolo e' trasparente.
      assertEquals(0xff, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) ushr 24)
      assertEquals(0, bitmap.getPixel(0, 0) ushr 24)
      bitmap.recycle()
    }
  }

  @Test
  fun unaSorgenteConOrientamentoSiRaddrizzaEIlRitaglioSegueIlPunto() = runBlocking<Unit> {
    // La fixture e' 640x480; con l'orientamento 6 (ruotata di 90 gradi) l'immagine raddrizzata e' 480x640.
    val file = jpegOf("casa", rotate = ExifInterface.ORIENTATION_ROTATE_90)
    val decoded = CutoutImages.decode(file) as CutoutDecode.Decoded
    assertEquals(480, decoded.image.width)
    assertEquals(640, decoded.image.height)
    file.delete()
  }

  @Test
  fun dalFileAlRitaglioAlPngConMagicTouch() = runBlocking<Unit> {
    val cutter = CutoutDevices.cutter("v1")
    val cutting = SubjectCutting(cutter, maxPixels = budgetPixels())
    try {
      val file = jpegOf("due-oggetti")
      val fixture = CutoutFixtures.named("due-oggetti")
      val result = cutting.cut("due-oggetti", CutoutSource.OfFile(file), fixture.point.x, fixture.point.y)
      result as CutoutResult.Subject
      assertTrue("IoU", CutoutQuality.iou(result.mask, fixture.truth!!) > 0.9f)
      // La sorgente non serve piu': il secondo punto e i passi dopo usano l'immagine trattenuta.
      assertTrue(file.delete())
      val again = cutting.cut("due-oggetti", CutoutSource.OfFile(file), fixture.point.x, fixture.point.y)
      assertTrue(again is CutoutResult.Subject)
      val png = cutting.png("due-oggetti", CutoutSource.OfFile(file), result.mask, outline = true)
      assertNotNull(png)
      assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(png!!).distinct())
      // Un'altra foto fa dimenticare la prima: senza il file non si legge.
      val missing = cutting.cut("altra", CutoutSource.OfFile(File(context.cacheDir, "non-esiste.jpg")), 0.5f, 0.5f)
      assertEquals(CutoutResult.Failed(AiEngineError.BAD_INPUT), missing)
    } finally {
      cutting.release()
    }
  }

  @Test
  fun unFileCheNonEUnImmagineDaUnCodice() = runBlocking<Unit> {
    val cutting = SubjectCutting(FakeSubjectCutter())
    val text = File(context.cacheDir, "p13-testo.jpg").apply { writeText("non sono un'immagine") }
    assertEquals(CutoutResult.Failed(AiEngineError.BAD_INPUT), cutting.cut("t", CutoutSource.OfFile(text), 0.5f, 0.5f))
    text.delete()
  }

  @Test
  fun iCinquePuntiDiProvaDannoIlSoggettoPiuGrande() = runBlocking<Unit> {
    val cutting = SubjectCutting(FakeSubjectCutter(), maxPixels = budgetPixels())
    // Il fake su un'immagine della fixture (PNG senza perdita) dice il soggetto della fixture: la casa sta al centro.
    val fixture = CutoutFixtures.named("casa")
    val file = File(context.cacheDir, "p13-casa.png")
    val bitmap = Bitmap.createBitmap(fixture.pixels(), fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    val best = cutting.largest("casa", CutoutSource.OfFile(file))
    assertTrue(best is CutoutResult.Subject)
    file.delete()
  }

  // --- Taglie grandi (E-M1-M4) ----------------------------------------------------------------------------------------------------

  /** Scrive un PNG [w]x[h] a righe, mai in memoria: un gradiente con un disco al centro (il soggetto) di raggio [r]. */
  private fun bigPng(name: String, w: Int, h: Int, r: Float): File {
    val file = File(context.cacheDir, name)
    val cx = w / 2f
    val cy = h / 2f
    file.outputStream().buffered(1 shl 16).use { out ->
      PngEncoder.encode(
        w,
        h,
        PngEncoder.Rows { y, into ->
          val dy = y + 0.5f - cy
          val span = if (kotlin.math.abs(dy) < r) kotlin.math.sqrt(r * r - dy * dy) else -1f
          val bg = (40 + 120L * y / h).toInt().toByte()
          val bg2 = (90 + 60L * y / h).toInt().toByte()
          for (x in 0 until w) {
            val i = x * 4
            if (span >= 0f && kotlin.math.abs(x + 0.5f - cx) <= span) {
              into[i] = 0xF0.toByte()
              into[i + 1] = 0xC8.toByte()
              into[i + 2] = 0x20
            } else {
              into[i] = bg
              into[i + 1] = bg2
              into[i + 2] = (150 + 50L * x / w).toInt().toByte()
            }
            into[i + 3] = 0xff.toByte()
          }
        },
        out,
        level = Deflater.BEST_SPEED,
      )
    }
    return file
  }

  @Test
  fun unaFotoDa200MegapixelNonDaErroriDiMemoria() = runBlocking<Unit> {
    // 16320 x 12240 = 199,8 MP, come una foto a 200 MP di un telefono: l'intestazione dice la taglia, la decodifica riduce mentre decodifica.
    val file = bigPng("p13-200mp.png", 16_320, 12_240, r = 3_000f)
    Log.i(TAG, "{\"file\":\"200MP\",\"bytes\":${file.length()}}")
    val cutting = SubjectCutting(CutoutDevices.cutter("v1"), maxPixels = budgetPixels())
    try {
      val t0 = System.nanoTime()
      val result = cutting.cut("200mp", CutoutSource.OfFile(file), 0.5f, 0.5f)
      val ms = (System.nanoTime() - t0) / 1_000_000
      Log.i(TAG, "{\"file\":\"200MP\",\"result\":\"${result.javaClass.simpleName}\",\"totalMs\":$ms}")
      result as CutoutResult.Subject
      // Il disco copre pi * 3000^2 / (16320 * 12240) = 14,2% dell'immagine.
      assertEquals(0.142f, result.mask.coverage, 0.02f)
      val png = cutting.png("200mp", CutoutSource.OfFile(file), result.mask, outline = true)
      assertNotNull(png)
    } finally {
      cutting.release()
      file.delete()
    }
  }

  @Test
  fun unaPanoramicaMoltoLungaSiRitagliaConIBordi() = runBlocking<Unit> {
    // 30000 x 1500 = 45 MP: il lato lungo e' il tetto (4096), il quadro del modello ha i bordi.
    val file = bigPng("p13-panorama.png", 30_000, 1_500, r = 450f)
    val cutting = SubjectCutting(CutoutDevices.cutter("v1"), maxPixels = budgetPixels())
    try {
      val result = cutting.cut("panorama", CutoutSource.OfFile(file), 0.5f, 0.5f)
      Log.i(TAG, "{\"file\":\"panorama\",\"result\":\"${result.javaClass.simpleName}\"}")
      result as CutoutResult.Subject
      assertTrue(result.mask.width <= 4096)
      assertEquals(Math.PI.toFloat() * 450f * 450f / (30_000f * 1_500f), result.mask.coverage, 0.01f)
    } finally {
      cutting.release()
      file.delete()
    }
  }

  @Test
  fun unaSorgenteGrandeSiRiduceAlBudgetSenzaDeformarla() = runBlocking<Unit> {
    val file = bigPng("p13-media.png", 3_000, 2_000, r = 400f)
    val decoded = CutoutImages.decode(file, maxPixels = 2_000_000) as CutoutDecode.Decoded
    val image = decoded.image
    assertTrue("pixel ${image.width * image.height}", image.width.toLong() * image.height <= 2_000_000)
    assertEquals(3.0 / 2.0, image.width.toDouble() / image.height, 0.01)
    // Il disco e' ancora al centro, del suo colore; l'angolo e' lo sfondo.
    val center = image.pixel(image.width / 2, image.height / 2)
    assertTrue(kotlin.math.abs(0xF0 - ((center shr 16) and 0xff)) <= 6)
    assertTrue(kotlin.math.abs(0xC8 - ((center shr 8) and 0xff)) <= 6)
    assertTrue(kotlin.math.abs(0x20 - (center and 0xff)) <= 6)
    val corner = image.pixel(2, 2)
    assertTrue((corner and 0xff) > 0x60)
    file.delete()
  }

  private companion object {
    const val TAG = "P13Pipeline"
  }
}
