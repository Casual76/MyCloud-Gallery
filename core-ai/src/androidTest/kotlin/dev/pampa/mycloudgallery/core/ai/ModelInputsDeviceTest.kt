package dev.pampa.mycloudgallery.core.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.OcrSource
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, D5, "Fatto quando": `ModelInputs` rifiuta JPEG e WebP ostili prima di decodificare). Con il decodificatore vero della
 * piattaforma: un ingresso onesto diventa un JPEG q95 a 768 px, uno ostile da' `null` **e il contatore delle decodifiche non sale**
 * (non e' mai arrivato a `ImageDecoder`). Le intestazioni ostili in dettaglio (e 30.000 mutazioni) sono provate sulla JVM
 * (`ImageHeadersTest`); qui la catena intera sul dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class ModelInputsDeviceTest {

  private val cacheDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

  // --- Ingressi onesti ------------------------------------------------------------------------------------------------------

  @Test
  fun unaAnteprimaDiAnalisiDiventaUnJpegA768() {
    val source = scene(1024, 768)
    val before = ModelInputs.decodeAttempts.get()
    val image = ModelInputs.fromAnalysis(jpeg(source, 90))!!
    assertEquals(before + 1, ModelInputs.decodeAttempts.get())
    assertEquals(AiRecipe.CURRENT.longSide, maxOf(image.width, image.height))
    assertEquals(768, image.width)
    assertEquals(576, image.height)
    val decoded = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size)
    assertNotNull("il JPEG che esce deve essere decodificabile", decoded)
    assertEquals(768, decoded.width)
    assertEquals(576, decoded.height)
    assertTrue(ImageFormat.isJpeg(image.jpeg))
  }

  @Test
  fun unaImmagineGiaPiccolaNonSIngrandisce() {
    val image = ModelInputs.fromAnalysis(jpeg(scene(400, 300), 90))!!
    assertEquals(400, image.width)
    assertEquals(300, image.height)
  }

  @Test
  fun unaRicettaConUnAltroLatoLungoLoRispetta() {
    val image = ModelInputs.fromAnalysis(jpeg(scene(1024, 768), 90), AiRecipe(longSide = 512))!!
    assertEquals(512, image.width)
    assertEquals(384, image.height)
  }

  @Test
  fun lOrientamentoExifSiApplicaELeDimensioniTornano() {
    val file = File(cacheDir, "p4-exif.jpg")
    try {
      file.writeBytes(jpeg(scene(800, 400), 90))
      ExifInterface(file.path).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        saveAttributes()
      }
      val image = ModelInputs.fromAnalysis(file.readBytes())!!
      // Una foto 800x400 ruotata di 90 gradi e' alta 800 e larga 400: l'immagine che esce e' verticale.
      assertTrue("attesa verticale, avuta ${image.width}x${image.height}", image.height > image.width)
      assertEquals(768, image.height)
    } finally {
      file.delete()
    }
  }

  @Test
  fun unJpegAlTettoDeiPixelDiImageBoundsSiDecodificaRidotto() {
    // 4096 x 4096 = 16,7 milioni di pixel: esattamente il tetto. Si decodifica gia' ridotto (mai a piena risoluzione).
    val big = Bitmap.createBitmap(4096, 4096, Bitmap.Config.ARGB_8888)
    try {
      Canvas(big).drawColor(Color.rgb(30, 120, 200))
      val bytes = jpeg(big, 50)
      assertTrue("il file di prova pesa ${bytes.size} byte", bytes.size < ModelInputs.MAX_INPUT_BYTES)
      val image = ModelInputs.fromAnalysis(bytes)!!
      assertEquals(768, image.width)
      assertEquals(768, image.height)
    } finally {
      big.recycle()
    }
  }

  @Test
  fun lHqWebpPerLOcrResta2048ELaTrasparenzaDiventaBianca() {
    val source = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
    try {
      source.eraseColor(Color.TRANSPARENT)
      Canvas(source).drawRect(100f, 100f, 1000f, 600f, Paint().apply { color = Color.BLACK })
      val bytes = webp(source, lossless = true)
      val image = ModelInputs.forOcr(bytes, OcrSource.HQ)!!
      assertEquals(2048, image.width)
      assertEquals(1536, image.height)
      assertEquals(OcrSource.HQ, image.source)
      val decoded = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size)
      // L'angolo trasparente e' bianco, non nero; il rettangolo nero resta nero.
      val corner = decoded.getPixel(2000, 1500)
      assertTrue("trasparenza non sul bianco: ${Integer.toHexString(corner)}", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
      val inside = decoded.getPixel(500, 300)
      assertTrue(Color.red(inside) < 20)
    } finally {
      source.recycle()
    }
  }

  @Test
  fun lOcrNonRiduceUnAnteprimaDa1024() {
    val image = ModelInputs.forOcr(jpeg(scene(1024, 768), 90), OcrSource.ANALYSIS)!!
    assertEquals(1024, image.width)
    assertEquals(768, image.height)
    // Oltre 2048 si riduce.
    val huge = ModelInputs.forOcr(jpeg(scene(3000, 2000), 80), OcrSource.PHONE_ORIGINAL)!!
    assertEquals(2048, huge.width)
  }

  @Test
  fun unWebpNonEUnAnteprimaDiAnalisi() {
    val webp = webp(scene(640, 480), lossless = false)
    val before = ModelInputs.decodeAttempts.get()
    assertNull(ModelInputs.fromAnalysis(webp)) // le anteprime sono JPEG
    assertNotNull(ModelInputs.forOcr(webp, OcrSource.HQ)) // l'HQ e' WebP
    assertEquals(before + 1, ModelInputs.decodeAttempts.get())
  }

  @Test
  fun iFotogrammiDiUnVideoSiPrendonoFinoANoveESalvanoLIstante() {
    val frames = List(12) { VideoFrame(it * 30_000, jpeg(scene(1024, 576, seed = it), 75)) }
    val out = ModelInputs.fromFrames(frames)
    assertEquals(9, out.size)
    assertEquals((0 until 9).map { it * 30_000 }, out.map { it.frameMs })
    assertTrue(out.all { it.width == 768 && it.height == 432 })
  }

  @Test
  fun unFotogrammaCattivoSiSaltaEUnoDopoPrendeIlSuoPosto() {
    val good = jpeg(scene(1024, 576), 75)
    val frames = (0 until 12).map { i -> VideoFrame(i * 1000, if (i == 3) HostileImages.jpegHeaderOnly(60000, 60000) else good) }
    val before = ModelInputs.decodeAttempts.get()
    val out = ModelInputs.fromFrames(frames)
    assertEquals(9, out.size)
    assertEquals(listOf(0, 1000, 2000, 4000, 5000, 6000, 7000, 8000, 9000), out.map { it.frameMs })
    assertEquals("solo i nove buoni sono arrivati alla decodifica", before + 9, ModelInputs.decodeAttempts.get())
  }

  @Test
  fun unaListaDiScartiNonCostaTroppo() {
    val frames = List(5_000) { VideoFrame(it, HostileImages.jpegHeaderOnly(60000, 60000)) }
    val before = ModelInputs.decodeAttempts.get()
    assertTrue(ModelInputs.fromFrames(frames).isEmpty())
    assertEquals(before, ModelInputs.decodeAttempts.get())
  }

  // --- Ingressi ostili --------------------------------------------------------------------------------------------------------

  @Test
  fun gliIngressiOstiliNonArrivanoMaiAllaDecodifica() {
    val hostile: Map<String, ByteArray> = HostileImages.all()
    val before = ModelInputs.decodeAttempts.get()
    for ((name, bytes) in hostile) {
      assertNull("fromAnalysis($name)", ModelInputs.fromAnalysis(bytes))
      assertNull("forOcr($name)", ModelInputs.forOcr(bytes, OcrSource.ANALYSIS))
    }
    assertEquals("un ingresso ostile e' arrivato al decodificatore", before, ModelInputs.decodeAttempts.get())
  }

  @Test
  fun unJpegCheMenteSulleDimensioniNonFaMale() {
    // Intestazione di 500x300 con i dati di una 400x300: passa l'intestazione, ma la decodifica non da' una bitmap sana.
    val real = jpeg(scene(400, 300), 90)
    val patched = HostileImages.patchJpegSize(real, 500, 300)
    val out = runCatching { ModelInputs.fromAnalysis(patched) }
    assertTrue("non deve lanciare: ${out.exceptionOrNull()}", out.isSuccess)
    val image = out.getOrNull()
    // O null (dati incompleti) o un'immagine valida: mai una bitmap con altre dimensioni da quelle dichiarate.
    if (image != null) assertEquals(500, image.width)
  }

  @Test
  fun unJpegValidoSeguitoDaSpazzaturaSiLegge() {
    val withTrailer = jpeg(scene(640, 480), 90) + ByteArray(1000) { 0x41 }
    assertNotNull(ModelInputs.fromAnalysis(withTrailer))
  }

  @Test
  fun unJpegTroncatoANetaNonSiDecodifica() {
    val good = jpeg(scene(640, 480), 90)
    assertNull(ModelInputs.fromAnalysis(good.copyOfRange(0, good.size / 2))) // troncato a meta': la decodifica fallisce, null
  }

  // --- Costruttori --------------------------------------------------------------------------------------------------------

  /** Una scena non uniforme (gradiente e forme): la compressione non la riduce a niente. */
  private fun scene(width: Int, height: Int, seed: Int = 0): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (y in 0 until height step 4) {
      paint.color = Color.rgb((y * 255 / height + seed * 20) % 256, 120, 255 - y * 255 / height)
      canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 4).toFloat(), paint)
    }
    paint.color = Color.rgb(250, 220, 40)
    canvas.drawCircle(width * 0.7f, height * 0.3f, height * 0.18f, paint)
    paint.color = Color.rgb(20, 20, 20)
    canvas.drawRect(width * 0.1f, height * 0.6f, width * 0.4f, height * 0.9f, paint)
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

  private object ImageFormat {
    fun isJpeg(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
  }
}

/** Ingressi ostili scritti a mano. Nessun dato personale: solo intestazioni. */
internal object HostileImages {

  fun all(): Map<String, ByteArray> {
    val real = ByteArray(0)
    return linkedMapOf(
      "vuoto" to real,
      "un byte" to byteArrayOf(0xFF.toByte()),
      "testo" to "non e' un'immagine".toByteArray(),
      "zeri" to ByteArray(4096),
      "png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64),
      "gif" to "GIF89a".toByteArray() + ByteArray(64),
      "bmp" to "BM".toByteArray() + ByteArray(64),
      "jpeg 60000x60000" to jpegHeaderOnly(60000, 60000),
      "jpeg 16384x16384" to jpegHeaderOnly(16384, 16384),
      "jpeg 4097x4096" to jpegHeaderOnly(4097, 4096),
      "jpeg 0x0" to jpegHeaderOnly(0, 0),
      "jpeg aritmetico" to jpegHeaderOnly(100, 100, sof = 0xC9),
      "jpeg senza perdita" to jpegHeaderOnly(100, 100, sof = 0xC3),
      "jpeg cmyk" to jpegHeaderOnly(100, 100, components = 4),
      "jpeg a 12 bit" to jpegHeaderOnly(100, 100, precision = 12),
      "jpeg lunghezza che mente" to jpegHeaderOnly(100, 100).also { it[4] = 0x7F },
      "jpeg troncato prima del SOF" to jpegHeaderOnly(100, 100).copyOfRange(0, 6),
      "jpeg oltre il tetto dei byte" to (byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xFF.toByte()) + ByteArray(5 * 1024 * 1024)),
      "webp con RIFF che mente" to riff(declared = 100_000, body = "WEBPVP8 ".toByteArray() + ByteArray(40)),
      "webp animato" to webpExtended(animated = true),
      "webp gigante" to webpLossless(16000, 16000),
      "webp senza immagine" to webpExtended(animated = false),
    )
  }

  private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

  /** SOI, SOF con le dimensioni date e basta (dopo non c'e' niente da decodificare). */
  fun jpegHeaderOnly(width: Int, height: Int, sof: Int = 0xC0, components: Int = 3, precision: Int = 8): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    out.write(byteArrayOf(0xFF.toByte(), sof.toByte()))
    out.write(u16(8 + 3 * components))
    out.write(precision)
    out.write(u16(height))
    out.write(u16(width))
    out.write(components)
    for (c in 0 until components) out.write(byteArrayOf((c + 1).toByte(), 0x11, 0))
    out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0))
    out.write(ByteArray(16) { 0x55 })
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  /** Cambia le dimensioni dichiarate nel SOF di un JPEG vero. */
  fun patchJpegSize(jpeg: ByteArray, width: Int, height: Int): ByteArray {
    val copy = jpeg.copyOf()
    var i = 2
    while (i + 9 < copy.size) {
      check(copy[i] == 0xFF.toByte())
      val marker = copy[i + 1].toInt() and 0xFF
      val length = ((copy[i + 2].toInt() and 0xFF) shl 8) or (copy[i + 3].toInt() and 0xFF)
      if (marker == 0xC0 || marker == 0xC2) {
        u16(height).copyInto(copy, i + 5)
        u16(width).copyInto(copy, i + 7)
        return copy
      }
      i += 2 + length
    }
    error("SOF non trovato")
  }

  private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

  private fun riff(declared: Long, body: ByteArray): ByteArray = "RIFF".toByteArray() + le32(declared) + body

  private fun webpExtended(animated: Boolean): ByteArray {
    val flags = byteArrayOf(if (animated) 0x02 else 0x00, 0, 0, 0, 99, 0, 0, 99, 0, 0)
    val vp8x = "VP8X".toByteArray() + le32(10) + flags
    return riff(4L + vp8x.size, "WEBP".toByteArray() + vp8x)
  }

  private fun webpLossless(width: Int, height: Int): ByteArray {
    val bits = (width - 1).toLong() or ((height - 1).toLong() shl 14)
    val payload = byteArrayOf(0x2F) + le32(bits)
    val chunk = "VP8L".toByteArray() + le32(payload.size.toLong()) + payload + byteArrayOf(0)
    return riff(4L + chunk.size, "WEBP".toByteArray() + chunk)
  }
}

/** Piccola prova che i costruttori ostili sono quelli che dicono di essere (altrimenti una prova "passa" per il motivo sbagliato). */
@RunWith(AndroidJUnit4::class)
class HostileImagesSanityDeviceTest {
  @Test
  fun leIntestazioniOstiliSonoLeggibiliComeDichiarano() {
    val header = dev.pampa.mycloudgallery.core.ai.runtime.ImageHeaders
    assertEquals(60000, HostileImages.jpegHeaderOnly(60000, 60000).let { (((it[7].toInt() and 0xFF) shl 8) or (it[8].toInt() and 0xFF)) })
    assertNull(header.read(HostileImages.jpegHeaderOnly(60000, 60000)))
    // Con dimensioni accettabili la stessa intestazione si legge: il rifiuto di sopra e' per le dimensioni, non per un errore di costruzione.
    assertNotNull(header.read(HostileImages.jpegHeaderOnly(1000, 1000)))
    assertNotNull(header.read(HostileImages.jpegHeaderOnly(4096, 4096)))
  }
}
