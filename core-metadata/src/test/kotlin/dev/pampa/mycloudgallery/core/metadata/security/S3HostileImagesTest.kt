package dev.pampa.mycloudgallery.core.metadata.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.ROME
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.testExtractor
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 10 P11, voce **S3 (c)** della revisione finale (docs/fase10-progetto.md §2.11; E-M10, E-M12, C9): le **immagini malformate** che un
 * familiare (o chiunque scriva nell'area comune) puo' mettere sul NAS, passate al lettore dei metadati vero (`DefaultMetadataExtractor`,
 * quello della scansione del NAS e del telefono): PNG che dichiara 50.000 x 50.000, bomba di decompressione (IDAT da pochi KB che si
 * gonfierebbe a centinaia di MB), JPEG con un SOF da 65.535 x 65.535, EXIF con le IFD **in ciclo** e con conteggi enormi, HEIC troncati e
 * con un `meta` che dichiara 2 GB, scatole ISO annidate a migliaia, XMP con la "bomba delle entita'" e con offset del video in movimento
 * fuori dal file, RIFF che dichiara 4 GB. Ognuna: nessun `Throwable`, meno di 10 s, memoria sotto il tetto (256 MB in piu' al massimo).
 * La **decodifica dei pixel** (`BitmapFactory`, `ImageDecoder`, i tetti di `BlobDecoder`) e' Android: la prova e' dell'emulatore e dell'S25
 * (seconda parte), sugli stessi file, che con `MCG_SECURITY_OUT` questo test scrive in `<cartella>/ostili/`.
 */
class S3HostileImagesTest {

  private fun u32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
  private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
  private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
  private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

  private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value
    return u32(data.size.toLong()) + type.toByteArray() + data + u32(crc)
  }

  private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

  private fun ihdr(w: Int, h: Int) = pngChunk("IHDR", u32(w.toLong()) + u32(h.toLong()) + byteArrayOf(8, 6, 0, 0, 0))

  private fun deflated(zeros: Int): ByteArray {
    val deflater = Deflater(9)
    deflater.setInput(ByteArray(zeros))
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    return out.toByteArray()
  }

  /** Un TIFF little-endian con IFD0 che punta a se' stesso come IFD successivo, e i puntatori EXIF e GPS che tornano a IFD0. */
  private fun cyclicTiff(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("II".toByteArray()); out.write(le16(42)); out.write(le32(8))
    out.write(le16(3))
    out.write(le16(0x8769)); out.write(le16(4)); out.write(le32(1)); out.write(le32(8)) // ExifIFD -> IFD0
    out.write(le16(0x8825)); out.write(le16(4)); out.write(le32(1)); out.write(le32(8)) // GPS IFD -> IFD0
    out.write(le16(0x0112)); out.write(le16(3)); out.write(le32(1)); out.write(le32(1))
    out.write(le32(8)) // IFD successivo -> IFD0
    return out.toByteArray()
  }

  /** Un TIFF con una IFD che dichiara 65.535 voci in pochi byte, e una voce con 2^31 valori. */
  private fun hugeCountTiff(): ByteArray = "II".toByteArray() + le16(42) + le32(8) + le16(0xFFFF) + le16(0x010F) + le16(2) + le32(0x7FFFFFFF) + le32(16)

  private fun jpegWithApp1(payload: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + u16(payload.size + 2) + payload + tail + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

  private fun sof(w: Int, h: Int) = byteArrayOf(0xFF.toByte(), 0xC0.toByte()) + u16(17) + byteArrayOf(8) + u16(h) + u16(w) + byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)

  private fun box(type: String, payload: ByteArray) = u32(payload.size + 8L) + type.toByteArray() + payload

  private fun xmpApp1(xmp: String) = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()

  /** I casi: nome del file, tipo MIME, byte. */
  internal fun cases(): List<Triple<String, String, ByteArray>> {
    val entityBomb = buildString {
      append("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a0 \"MCGMCGMCGMCG\">")
      for (i in 1..12) append("<!ENTITY a$i \"" + "&a${i - 1};".repeat(10) + "\">")
      append("]><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
      append("<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:title=\"&a12;\"/></rdf:RDF></x:xmpmeta>")
    }
    val nested = buildString {
      append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
      repeat(20_000) { append("<rdf:Bag><rdf:li>") }
      repeat(20_000) { append("</rdf:li></rdf:Bag>") }
      append("</rdf:RDF></x:xmpmeta>")
    }
    val motion = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
      "<rdf:Description xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" " +
      "GCamera:MicroVideoOffset=\"9223372036854775807\"/></rdf:RDF></x:xmpmeta>"
    var deep = box("free", ByteArray(4))
    repeat(3_000) { deep = box("moov", deep) }
    val heic = IsoFixtures.heic(IsoFixtures.HeicSpec())
    return listOf(
      Triple("png-50000x50000.png", "image/png", pngMagic + ihdr(50_000, 50_000) + pngChunk("IDAT", deflated(1024)) + pngChunk("IEND", ByteArray(0))),
      Triple("png-bomba-zlib.png", "image/png", pngMagic + ihdr(20_000, 20_000) + pngChunk("IDAT", deflated(64 * 1024 * 1024)) + pngChunk("IEND", ByteArray(0))),
      Triple("jpeg-sof-65535.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sof(65_535, 65_535) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())),
      Triple("jpeg-exif-ciclico.jpg", "image/jpeg", jpegWithApp1("Exif\u0000\u0000".toByteArray() + cyclicTiff(), sof(64, 48))),
      Triple("jpeg-exif-conteggi-enormi.jpg", "image/jpeg", jpegWithApp1("Exif\u0000\u0000".toByteArray() + hugeCountTiff(), sof(64, 48))),
      Triple("jpeg-xmp-bomba-entita.jpg", "image/jpeg", jpegWithApp1(xmpApp1(entityBomb), sof(64, 48))),
      Triple("jpeg-xmp-annidato.jpg", "image/jpeg", jpegWithApp1(xmpApp1(nested.take(60_000)), sof(64, 48))),
      Triple("jpeg-motion-offset-fuori.jpg", "image/jpeg", jpegWithApp1(xmpApp1(motion), sof(64, 48))),
      Triple("heic-troncato.heic", "image/heic", heic.copyOf(heic.size / 2)),
      Triple("heic-meta-2gb.heic", "image/heic", box("ftyp", "heic".toByteArray() + u32(0) + "mif1heic".toByteArray()) + u32(0x7FFFFFF0) + "meta".toByteArray() + ByteArray(64)),
      Triple("mp4-scatole-annidate.mp4", "video/mp4", box("ftyp", "isom".toByteArray() + u32(0) + "isom".toByteArray()) + deep),
      Triple("webp-riff-4gb.webp", "image/webp", "RIFF".toByteArray() + le32(0xFFFFFFF0L) + "WEBPVP8X".toByteArray() + le32(10) + ByteArray(10)),
      Triple("jpeg-buono.jpg", "image/jpeg", ImageFixtures.jpeg(64, 48, 0x336699)),
    )
  }

  @Test
  fun leImmaginiMalformateNonFannoCadereIlLettoreDeiMetadati() {
    val extractor = testExtractor()
    val runtime = Runtime.getRuntime()
    var maxMs = 0L
    var maxMb = 0L
    val failures = ArrayList<String>()
    val cases = cases()
    System.getenv("MCG_SECURITY_OUT")?.let { File(it, "ostili").apply { mkdirs() } }?.let { dir ->
      cases.forEach { (name, _, bytes) -> File(dir, name).writeBytes(bytes) }
    }
    for ((name, mime, bytes) in cases) {
      System.gc()
      val before = runtime.totalMemory() - runtime.freeMemory()
      val error = AtomicReference<Throwable?>()
      var peak = before
      val worker = Thread {
        try {
          runBlocking { extractor.extract(ByteArrayMediaBytes(bytes), ExtractHints(mime = mime, displayName = name, relativePath = "mcg-ostile/", scanZone = ROME)) }
        } catch (t: Throwable) {
          error.set(t)
        }
      }
      val started = System.nanoTime()
      worker.start()
      while (worker.isAlive && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10)) {
        worker.join(20)
        peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
      }
      val ms = (System.nanoTime() - started) / 1_000_000
      if (worker.isAlive) {
        failures += "$name: oltre 10 s"
        @Suppress("DEPRECATION") worker.interrupt()
        continue
      }
      error.get()?.let { failures += "$name: ${it.javaClass.simpleName}" }
      val mb = (peak - before) / (1024 * 1024)
      if (mb > 256) failures += "$name: $mb MB"
      maxMs = maxOf(maxMs, ms)
      maxMb = maxOf(maxMb, mb)
    }
    println("MCG-SEC S3c immagini=${cases.size} crolli=${failures.size} ms_max=$maxMs mb_max=$maxMb")
    assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
  }
}
