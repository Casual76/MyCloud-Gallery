package dev.pampa.mycloudgallery.core.ai.faces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.HostileImages
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8 (P2, "Fatto quando": ingressi ostili che non arrivano mai al decodificatore, con un contatore come in P4 della Fase 6). `ModelInputs.forFaces`
 * con il decodificatore vero della piattaforma: un ingresso onesto diventa un `FaceImage` di pixel sRGB raddrizzati e ridotti; uno ostile da' `null`
 * **e il contatore delle decodifiche non sale** (non e' mai arrivato a `ImageDecoder`). Le intestazioni ostili in dettaglio (e 30.000 mutazioni) sono
 * provate sulla JVM (`ImageHeadersTest`); qui la catena intera sul dispositivo. Non servono modelli ne' volti: immagini disegnate dalla prova.
 */
@RunWith(AndroidJUnit4::class)
class FaceInputsDeviceTest {

  private val cacheDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

  // --- Ingressi onesti ------------------------------------------------------------------------------------------------------------

  @Test
  fun unaAnteprimaDa1024DiventaUnFaceImageConIPixelGiusti() {
    val before = ModelInputs.decodeAttempts.get()
    val image = ModelInputs.forFaces(jpeg(twoHalves(1024, 768), 92), FaceEngineLimits.DETECT_SIDE)!!
    assertEquals(before + 1, ModelInputs.decodeAttempts.get())
    assertEquals(1024, image.width)
    assertEquals(768, image.height)
    assertEquals(0, image.frame)
    val left = image.pixelAt(200, 300)
    val right = image.pixelAt(800, 300)
    assertTrue("sinistra rossa: ${Integer.toHexString(left)}", Color.red(left) > 235 && Color.green(left) < 25 && Color.blue(left) < 25)
    assertTrue("destra blu: ${Integer.toHexString(right)}", Color.blue(right) > 235 && Color.red(right) < 25 && Color.green(right) < 25)
    assertEquals("opaca", 0xFF, left ushr 24)
    assertEquals("FaceImage(1024x768)", image.toString())
  }

  @Test
  fun unHqPiuGrandeSiRiduceAlTettoEUnaImmagineGiaPiccolaNonSIngrandisce() {
    val reduced = ModelInputs.forFaces(jpeg(twoHalves(3000, 2000), 80), FaceEngineLimits.DETECT_SIDE)!!
    assertEquals(1024, reduced.width)
    assertEquals(683, reduced.height)
    val hq = ModelInputs.forFaces(webp(twoHalves(2400, 1800), lossless = false), FaceEngineLimits.HQ_SIDE)!!
    assertEquals(2048, hq.width)
    assertEquals(1536, hq.height)
    val small = ModelInputs.forFaces(jpeg(twoHalves(400, 300), 90), FaceEngineLimits.HQ_SIDE)!!
    assertEquals(400, small.width)
    assertEquals(300, small.height)
    // Un tetto oltre l'HQ si porta a 2048; uno assurdo non e' un ingresso.
    val capped = ModelInputs.forFaces(jpeg(twoHalves(4000, 3000), 60), 5000)!!
    assertEquals(2048, capped.width)
    assertNull(ModelInputs.forFaces(jpeg(twoHalves(400, 300), 90), 0))
    assertNull(ModelInputs.forFaces(jpeg(twoHalves(400, 300), 90), -5))
  }

  @Test
  fun lOrientamentoExifSiApplicaELaTrasparenzaDiventaBianca() {
    val file = File(cacheDir, "p2-exif.jpg")
    try {
      file.writeBytes(jpeg(twoHalves(800, 400), 92))
      ExifInterface(file.path).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        saveAttributes()
      }
      val rotated = ModelInputs.forFaces(file.readBytes(), FaceEngineLimits.DETECT_SIDE)!!
      assertEquals("una foto 800x400 ruotata di 90 gradi e' alta 800 e larga 400", 400, rotated.width)
      assertEquals(800, rotated.height)
    } finally {
      file.delete()
    }
    val source = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
    try {
      source.eraseColor(Color.TRANSPARENT)
      Canvas(source).drawRect(100f, 100f, 600f, 500f, Paint().apply { color = Color.BLACK })
      val image = ModelInputs.forFaces(webp(source, lossless = true), FaceEngineLimits.HQ_SIDE)!!
      val corner = image.pixelAt(1100, 850)
      assertTrue("l'angolo trasparente e' bianco: ${Integer.toHexString(corner)}", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
      assertTrue("il rettangolo nero resta nero", Color.red(image.pixelAt(300, 300)) < 20)
    } finally {
      source.recycle()
    }
  }

  @Test
  fun ilFotogrammaDiUnVideoSiPortaDietroEFuoriIntervalloNonEUnIngresso() {
    val bytes = jpeg(twoHalves(640, 360), 85)
    for (frame in 0..FaceEngineLimits.MAX_FRAME) assertEquals(frame, ModelInputs.forFaces(bytes, FaceEngineLimits.DETECT_SIDE, frame)!!.frame)
    assertNull(ModelInputs.forFaces(bytes, FaceEngineLimits.DETECT_SIDE, FaceEngineLimits.MAX_FRAME + 1))
    assertNull(ModelInputs.forFaces(bytes, FaceEngineLimits.DETECT_SIDE, -1))
  }

  @Test
  fun soloJpegEWebpSonoIngressiValidi() {
    val png = ByteArrayOutputStream().also { twoHalves(300, 200).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    val before = ModelInputs.decodeAttempts.get()
    assertNull("un PNG non e' un'anteprima ne' un HQ", ModelInputs.forFaces(png, FaceEngineLimits.DETECT_SIDE))
    assertEquals(before, ModelInputs.decodeAttempts.get())
    assertNotNull(ModelInputs.forFaces(webp(twoHalves(300, 200), lossless = true), FaceEngineLimits.DETECT_SIDE))
  }

  // --- Ingressi ostili --------------------------------------------------------------------------------------------------------------

  @Test
  fun gliIngressiOstiliNonArrivanoMaiAllaDecodifica() {
    val hostile: Map<String, ByteArray> = HostileImages.all()
    val before = ModelInputs.decodeAttempts.get()
    for ((name, bytes) in hostile) {
      assertNull("forFaces($name)", ModelInputs.forFaces(bytes, FaceEngineLimits.DETECT_SIDE))
      assertNull("forFaces($name) all'HQ", ModelInputs.forFaces(bytes, FaceEngineLimits.HQ_SIDE, 3))
    }
    assertEquals("un ingresso ostile e' arrivato al decodificatore", before, ModelInputs.decodeAttempts.get())
    assertTrue("la prova ha almeno 20 ingressi ostili", hostile.size >= 20)
  }

  @Test
  fun unaListaDiScartiNonCostaDecodifiche() {
    val before = ModelInputs.decodeAttempts.get()
    repeat(3_000) { assertNull(ModelInputs.forFaces(HostileImages.jpegHeaderOnly(60000, 60000), FaceEngineLimits.DETECT_SIDE)) }
    assertEquals(before, ModelInputs.decodeAttempts.get())
  }

  @Test
  fun unJpegCheMenteSulleDimensioniOTroncatoNonFaMale() {
    val real = jpeg(twoHalves(400, 300), 90)
    val patched = HostileImages.patchJpegSize(real, 500, 300)
    val out = runCatching { ModelInputs.forFaces(patched, FaceEngineLimits.DETECT_SIDE) }
    assertTrue("non deve lanciare: ${out.exceptionOrNull()}", out.isSuccess)
    // O null (dati incompleti) o un'immagine valida: mai una con dimensioni diverse da quelle dichiarate.
    out.getOrNull()?.let { assertEquals(500, it.width) }
    assertNull("troncato a meta'", ModelInputs.forFaces(real.copyOfRange(0, real.size / 2), FaceEngineLimits.DETECT_SIDE))
    assertNotNull("seguito da spazzatura si legge", ModelInputs.forFaces(real + ByteArray(1000) { 0x41 }, FaceEngineLimits.DETECT_SIDE))
  }

  @Test
  fun milleMutazioniCasualiDiUnJpegVeroNonFannoMaiEccezioniEDannoImmaginiDentroITetti() {
    val real = jpeg(twoHalves(256, 192), 80)
    val random = Random(20261028)
    var accepted = 0
    repeat(1_000) {
      val copy = real.copyOf()
      repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
      val image = runCatching { ModelInputs.forFaces(copy, FaceEngineLimits.DETECT_SIDE) }.getOrElse { throw AssertionError("eccezione su un ingresso mutato: $it") }
      if (image != null) {
        accepted++
        assertTrue(image.width in 1..FaceEngineLimits.HQ_SIDE && image.height in 1..FaceEngineLimits.HQ_SIDE)
      }
    }
    assertTrue("qualche mutazione resta un'immagine ($accepted)", accepted > 0)
  }

  // --- Costruttori -----------------------------------------------------------------------------------------------------------------

  /** Meta' sinistra rossa e meta' destra blu: colori puri, che il JPEG conserva. */
  private fun twoHalves(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = Color.rgb(255, 0, 0)
    canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
    paint.color = Color.rgb(0, 0, 255)
    canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
    return bitmap
  }

  private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()

  @Suppress("DEPRECATION")
  private fun webp(bitmap: Bitmap, lossless: Boolean): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= 30) {
      if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
    } else {
      Bitmap.CompressFormat.WEBP
    }
    return ByteArrayOutputStream().also { check(bitmap.compress(format, if (lossless) 100 else 80, it)) }.toByteArray()
  }
}
