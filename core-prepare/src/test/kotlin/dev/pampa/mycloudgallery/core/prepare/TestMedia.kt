package dev.pampa.mycloudgallery.core.prepare

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import androidx.exifinterface.media.ExifInterface
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.engine.PrepProbe
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.core.prepare.video.MediaMetadataFrameGrabbers
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabberFactory
import kotlinx.coroutines.Dispatchers
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.fixtures.PrepareFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.runBlocking

/**
 * Le immagini di prova della preparazione, costruite dai test (nessun file nel repository): quattro quadranti di colori
 * molto diversi, cosi' dopo qualunque rotazione o specchio si vede dove e' finito ogni angolo. Girano su Robolectric con la
 * grafica nativa (`GraphicsMode.NATIVE`: Skia vero).
 */
internal object TestMedia {

  const val TL: Int = Color.RED
  const val TR: Int = Color.GREEN
  const val BL: Int = Color.BLUE
  const val BR: Int = Color.YELLOW

  private val SOURCE = listOf(TL, TR, BL, BR)

  /** Dove deve finire ciascun angolo della sorgente (landscape) dopo l'orientamento EXIF: scritta a mano, indipendente dal codice. */
  val EXPECTED_CORNERS: Map<Int, List<String>> = mapOf(
    1 to listOf("TL", "TR", "BL", "BR"),
    2 to listOf("TR", "TL", "BR", "BL"),
    3 to listOf("BR", "BL", "TR", "TL"),
    4 to listOf("BL", "BR", "TL", "TR"),
    5 to listOf("TL", "BL", "TR", "BR"),
    6 to listOf("TR", "BR", "TL", "BL"),
    7 to listOf("BR", "TR", "BL", "TL"),
    8 to listOf("BL", "TL", "BR", "TR"),
  )

  fun quadrants(width: Int, height: Int, colorSpace: ColorSpace? = null): Bitmap {
    val bitmap = if (colorSpace != null) {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, colorSpace)
    } else {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    val row = IntArray(width)
    for (y in 0 until height) {
      val top = y < height / 2
      for (x in 0 until width) {
        val left = x < width / 2
        row[x] = if (top) (if (left) TL else TR) else (if (left) BL else BR)
      }
      bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bitmap
  }

  fun jpeg(bitmap: Bitmap, quality: Int = 92): ByteArray = compress(bitmap, Bitmap.CompressFormat.JPEG, quality)

  fun png(bitmap: Bitmap): ByteArray = compress(bitmap, Bitmap.CompressFormat.PNG, 100)

  fun webp(bitmap: Bitmap): ByteArray = compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)

  private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    check(bitmap.compress(format, quality, out)) { "Codifica non riuscita." }
    return out.toByteArray()
  }

  /** Un JPEG dei quattro quadranti con il tag EXIF `Orientation` = [orientation] (null = nessun tag). */
  fun jpegWithOrientation(orientation: Int?, width: Int = 320, height: Int = 160): ByteArray {
    val bytes = jpeg(quadrants(width, height))
    if (orientation == null) return bytes
    val file = File.createTempFile("orientation", ".jpg")
    try {
      file.writeBytes(bytes)
      val exif = ExifInterface(file)
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      exif.saveAttributes()
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  /** Decodifica byte di prova (le uscite della preparazione) in una bitmap software. */
  fun decode(bytes: ByteArray): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

  /** Il colore al centro del quadrante `TL`, `TR`, `BL` o `BR`. */
  fun quadrantColor(bitmap: Bitmap, quadrant: String): Int {
    val x = if (quadrant.endsWith("L")) bitmap.width / 4 else bitmap.width * 3 / 4
    val y = if (quadrant.startsWith("T")) bitmap.height / 4 else bitmap.height * 3 / 4
    return bitmap.getPixel(x, y)
  }

  /**
   * Verifica che [bitmap] sia la sorgente a quattro quadranti con l'[orientation] EXIF applicato: il colore di ogni
   * quadrante di arrivo e' quello atteso (con la tolleranza della compressione). Null se tutto torna, altrimenti il perche'.
   */
  fun checkOrientation(bitmap: Bitmap, orientation: Int, tolerance: Int = 70): String? {
    val destinations = EXPECTED_CORNERS.getValue(orientation)
    for ((i, destination) in destinations.withIndex()) {
      val actual = quadrantColor(bitmap, destination)
      if (!near(actual, SOURCE[i], tolerance)) {
        return "il quadrante ${listOf("TL", "TR", "BL", "BR")[i]} doveva finire in $destination (colore atteso ${hex(SOURCE[i])}, trovato ${hex(actual)})"
      }
    }
    return null
  }

  fun near(a: Int, b: Int, tolerance: Int): Boolean =
    abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

  private fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

  /** Rende unica la bitmap: un quadratino colorato dal [seed], cosi' due fotogrammi di prova non danno lo stesso JPEG. */
  fun marked(bitmap: Bitmap, seed: Long): Bitmap {
    val color = Color.rgb((seed and 0xFF).toInt(), ((seed shr 8) and 0xFF).toInt(), 77)
    for (y in 4 until 8) for (x in 4 until 8) bitmap.setPixel(x, y, color)
    return bitmap
  }

  /** Una foto "da telefono": gradienti morbidi, qualche cerchio e una grana leggera; si comprime come una foto vera, non come il rumore. */
  fun photoLike(width: Int, height: Int, seed: Long = 7): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val random = java.util.Random(seed)
    val row = IntArray(width)
    for (y in 0 until height) {
      val fy = y.toDouble() / height
      for (x in 0 until width) {
        val fx = x.toDouble() / width
        val r = 120 + 90 * Math.sin(fx * 6.0 + fy * 2.0) + random.nextInt(7)
        val g = 110 + 80 * Math.sin(fy * 5.0 - fx * 1.5 + 1.0) + random.nextInt(7)
        val b = 100 + 70 * Math.cos(fx * 3.0 + fy * 4.0) + random.nextInt(7)
        row[x] = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
      }
      bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bitmap
  }

  /** Una foto con un po' di grana: non si comprime a niente, come un file vero. */
  fun noisy(width: Int, height: Int, seed: Long = 1): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val random = java.util.Random(seed)
    val row = IntArray(width)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val n = random.nextInt(32)
        val r = (x * 255 / width + n).coerceIn(0, 255)
        val g = (y * 255 / height + n).coerceIn(0, 255)
        val b = ((x + y) * 255 / (width + height) + n).coerceIn(0, 255)
        row[x] = Color.rgb(r, g, b)
      }
      bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bitmap
  }
}

/** Un [MediaBytes] su un array che conta quanto e come si legge: i byte chiesti e le letture, anche quelle di `dataSource()`. */
internal class CountingBytes(private val data: ByteArray) : MediaBytes {
  private val total = AtomicLong()
  val reads = java.util.concurrent.CopyOnWriteArrayList<LongRange>()

  /** I byte restituiti da `read`. */
  val bytesRead: Long get() = total.get()

  override val size: Long get() = data.size.toLong()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= data.size) return ByteArray(0)
    val from = maxOf(range.first, 0L).toInt()
    val to = minOf(range.last, data.size - 1L).toInt()
    reads += range
    total.addAndGet((to - from + 1).toLong())
    return data.copyOfRange(from, to + 1)
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= data.size) return -1
      val part = runBlocking { read(position until minOf(position + size, data.size.toLong())) }
      System.arraycopy(part, 0, buffer, offset, part.size)
      return part.size
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
  }
}

/** Un video "virtuale" (struttura vera, payload calcolato): un file da gigabyte che non occupa memoria. */
internal class VirtualBytes(val mp4: PrepareFixtures.VirtualMp4) : MediaBytes {
  private val total = AtomicLong()
  val reads = java.util.concurrent.CopyOnWriteArrayList<LongRange>()

  val bytesRead: Long get() = total.get()

  override val size: Long get() = mp4.size()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= mp4.size()) return ByteArray(0)
    val from = maxOf(range.first, 0L)
    val length = (minOf(range.last, mp4.size() - 1) - from + 1).toInt()
    reads += range
    total.addAndGet(length.toLong())
    return mp4.read(from, length)
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= mp4.size()) return -1
      val part = mp4.read(position, size)
      System.arraycopy(part, 0, buffer, offset, part.size)
      return part.size
    }

    override fun getSize(): Long = mp4.size()

    override fun close() = Unit
  }
}


/** Un file su disco come [MediaBytes] (per il JPEG da 200 MP, che non sta in un array di test): conta i byte letti. */
internal class FileBytes(private val file: File) : MediaBytes {
  private val total = AtomicLong()

  val bytesRead: Long get() = total.get()

  override val size: Long get() = file.length()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= file.length()) return ByteArray(0)
    java.io.RandomAccessFile(file, "r").use { raf ->
      val to = minOf(range.last, file.length() - 1)
      val out = ByteArray((to - range.first + 1).toInt())
      raf.seek(range.first)
      raf.readFully(out)
      total.addAndGet(out.size.toLong())
      return out
    }
  }

  override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
}


/**
 * Il preparatore dei test. Robolectric non sa decodificare da un `File` (`ImageDecoder.createSource(File)`: "Only supported on
 * Android"), quindi i file locali e i temporanei si decodificano da un `ByteBuffer`: il percorso (lettura a blocchi, hash,
 * temporaneo, pulizia) e' quello vero, la sola chiamata di piattaforma e' sostituita. Sul telefono e' `createSource(File)` (F6).
 */
internal fun testPreparer(
  budget: PrepBudget = PrepBudget(memoryClassMb = 256, cores = 8),
  tempDir: File,
  codecs: CodecAvailability = FakeCodecs.everything(),
  probe: PrepProbe = PrepProbe.NONE,
  frames: VideoFrameGrabberFactory = MediaMetadataFrameGrabbers,
) = DefaultPreparer(budget, tempDir, codecs, "c2_test", frames, Dispatchers.Default, probe) { file -> ByteArrayImageBytes(file.readBytes()) }
