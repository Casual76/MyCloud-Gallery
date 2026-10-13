package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.IsoBmff
import dev.pampa.mycloudgallery.core.metadata.format.JpegScanner
import dev.pampa.mycloudgallery.core.metadata.format.PngScanner
import dev.pampa.mycloudgallery.core.metadata.format.SefTrailer
import dev.pampa.mycloudgallery.core.metadata.format.ThumbHashAlgorithm
import dev.pampa.mycloudgallery.core.metadata.format.TiffParser
import dev.pampa.mycloudgallery.core.metadata.format.WebpScanner
import dev.pampa.mycloudgallery.core.metadata.format.XmpFacts
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3 (docs/review/3-fase3.md): prove avversariali sui lettori di contenitori di
 * `core-metadata`, che leggono file potenzialmente ostili (per esempio una foto ricevuta su WhatsApp).
 *
 * L'obiettivo e' che **un file di pochi KB non possa mai** far allocare memoria senza limite, girare a lungo, lanciare
 * un'eccezione non gestita o esaurire lo stack. Dove un caso grave manca di una difesa, il test lo **dimostra** (fallisce
 * finche' il codice di produzione non lo corregge): e' il caso di [R1_ilocConMoltiExtentNonDeveGirareSenzaLimite].
 *
 * Nessuna modifica al codice di produzione: questi test leggono soltanto.
 */
class SecurityReviewFuzzTest {

  // ---- Costruttori di byte ------------------------------------------------------------------------------------------

  private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply(block).toByteArray()

  private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

  private fun ByteArrayOutputStream.u16be(v: Int) {
    u8(v shr 8); u8(v)
  }

  private fun ByteArrayOutputStream.u32be(v: Long) {
    u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt())
  }

  private fun ByteArrayOutputStream.ascii(s: String) = s.forEach { u8(it.code) }

  /** Un riquadro ISO-BMFF `[size][type][payload]` a 32 bit. */
  private fun box(type: String, payload: ByteArray): ByteArray = bytes {
    u32be((payload.size + 8).toLong())
    ascii(type)
    write(payload)
  }

  // ---- R1: iloc con moltissimi extent (DoS da allocazione) ----------------------------------------------------------

  /**
   * Un HEIF di ~40 KB il cui riquadro `meta` contiene un `iloc` con molte voci, ognuna con `extent_count` = 65535 e
   * `offset_size`/`length_size`/`index_size` tutti a zero: ogni extent non consuma byte, quindi il numero di extent
   * (e di oggetti [IsoBmff] allocati) non e' piu' legato alla dimensione del file. Il metodo di costruzione e' `1`,
   * cosi' gli extent non vengono nemmeno conservati: resta la pura allocazione/CPU, che sola basta a bloccare la
   * sincronizzazione su una foto ostile.
   *
   * `MAX_META_BYTES` (8 MiB) limita solo il *corpo* del meta, non il prodotto voci x extent: con un meta da 8 MiB le
   * allocazioni sfiorano 1e11 e diventano `OutOfMemoryError`.
   *
   * Qui si usano circa 5.000 voci (prodotto ~3,3e8): memoria contenuta (gli extent sono spazzatura subito), tempo che
   * su codice non corretto supera abbondantemente il budget. La prova gira su un thread a parte con una scadenza, cosi'
   * il caso patologico non blocca l'intera suite.
   */
  @Test
  fun R1_ilocConMoltiExtentNonDeveGirareSenzaLimite() {
    val file = heifWithIloc(items = 30_000)

    val error = AtomicReference<Throwable?>()
    val worker = Thread {
      try {
        runBlocking { IsoBmff.scanHeif(ArrayAccess(file)) }
      } catch (t: Throwable) {
        error.set(t)
      }
    }
    worker.isDaemon = true
    worker.start()
    worker.join(BUDGET_MS)

    val finishedInTime = !worker.isAlive
    assertWithMessage(
      "R1: un HEIF di ${file.size} byte con un iloc dai molti extent ha superato ${BUDGET_MS} ms di elaborazione " +
        "(extent non limitati in IsoBmff.parseIloc): una foto ostile puo' bloccare o far esaurire la memoria alla " +
        "sincronizzazione. Atteso un tetto su extent_count/voci dell'iloc.",
    ).that(finishedInTime).isTrue()
    assertWithMessage("La scansione non deve lanciare").that(error.get()).isNull()
  }

  /** `ftyp`(heic) + `meta` con un solo `iloc`: [items] voci, ognuna con extent_count 65535 e taglie a zero. */
  private fun heifWithIloc(items: Int): ByteArray {
    val ftyp = box("ftyp", bytes { ascii("heic"); u32be(0); ascii("heic"); ascii("mif1") })
    val iloc = box(
      "iloc",
      bytes {
        u8(1); u8(0); u8(0); u8(0) // version 1, flags 0
        u8(0x00) // offset_size = 0, length_size = 0
        u8(0x00) // base_offset_size = 0, index_size = 0
        u16be(0xFFFF) // item_count dichiarato: il massimo (il vero limite dovrebbe essere p < end)
        repeat(items) {
          u16be(it and 0xFFFF) // item_ID
          u16be(0x0001) // construction_method = 1 (gli extent non si conservano: resta la sola allocazione)
          u16be(0) // data_reference_index
          u16be(0xFFFF) // extent_count = 65535, con taglie a zero: nessun byte consumato
        }
      },
    )
    val meta = box("meta", bytes { u32be(0); write(iloc) }) // FullBox: 4 byte di version/flags, poi i figli
    return ftyp + meta
  }

  // ---- Robustezza generale: nessun lettore deve lanciare o girare troppo -------------------------------------------

  /** Tutti i lettori dei contenitori, su file troncati/ostili, finiscono entro il budget senza eccezioni. */
  @Test
  fun iLettoriDeiContenitoriNonLancianoMaiSuInputOstili() = withinBudget("lettori dei contenitori") {
    runBlocking {
      val random = Random(1234)
      for (sample in hostileImages()) {
        val ra = ArrayAccess(sample)
        // Ognuno deve tornare un risultato (anche "malformato"/"troncato"), mai un'eccezione.
        JpegScanner.scan(ra)
        PngScanner.scan(ra)
        WebpScanner.scan(ra)
        IsoBmff.scanHeif(ra)
        IsoBmff.walkTopLevel(ra)
        TiffParser.parse(ra)
      }
      // Byte del tutto casuali con intestazioni plausibili.
      repeat(300) {
        val n = random.nextInt(512)
        val b = ByteArray(n).also(random::nextBytes)
        val ra = ArrayAccess(b)
        JpegScanner.scan(ra); PngScanner.scan(ra); WebpScanner.scan(ra); IsoBmff.scanHeif(ra); TiffParser.parse(ra)
      }
    }
  }

  /** L'estrattore completo, su immagini ostili, torna sempre un esito (mai un'eccezione) ed entro il budget. */
  @Test
  fun lEstrattoreCompletoReggeLeImmaginiOstili() = runTest {
    withinBudget("estrattore") {
      runBlocking {
        val extractor = testExtractor()
        for ((name, mime, b) in named(hostileImages())) {
          val hints = ExtractHints(mime, name, "DCIM/Camera/", null, null, null, null, null, ROME)
          // extract non lancia mai (tranne l'annullamento): un esito e' sempre atteso.
          extractor.extract(ByteArrayMediaBytes(b), hints)
        }
      }
    }
  }

  /** ThumbHash: hash cortissimi, lunghi, casuali; dimensioni richieste assurde. Mai eccezioni. */
  @Test
  fun thumbHashDecodeReggeHashArbitrari() = withinBudget("thumbhash") {
    val random = Random(7)
    val hashes = buildList {
      add(ByteArray(0))
      add(ByteArray(1))
      add(ByteArray(4))
      add(ByteArray(5))
      repeat(200) { add(ByteArray(random.nextInt(40)).also(random::nextBytes)) }
    }
    for (h in hashes) {
      ThumbHashAlgorithm.decode(h, 1, 1)
      ThumbHashAlgorithm.decode(h, 32, 32)
      ThumbHashAlgorithm.aspectRatio(h)
    }
  }

  /** XMP ostile: pacchetti enormi, molti elementi, numeri fuori scala. Nessun blocco, nessuna eccezione. */
  @Test
  fun xmpFactsReggonoPacchettiOstili() = withinBudget("xmp") {
    val many = buildString {
      append("<x:xmpmeta xmlns:x='adobe:ns:meta/'>")
      repeat(5_000) { append("<Container:Item Item:Semantic='MotionPhoto' Item:Length='999999999999'/>") }
      append("</x:xmpmeta>")
    }
    XmpFacts.parse(many)
    XmpFacts.parse("<x:xmpmeta>" + "A".repeat(2_000_000) + "</x:xmpmeta>")
    XmpFacts.parse("GCamera:MicroVideoOffset=\"-1\" GCamera:MotionPhoto=\"1\"")
  }

  /** Trailer SEF di Samsung con conteggio e distanze assurdi: nessuna lettura fuori dai byte, nessuna eccezione. */
  @Test
  fun sefTrailerReggeDirectoryOstili() = withinBudget("sef") {
    val random = Random(99)
    repeat(300) {
      val tail = ByteArray(random.nextInt(4096)).also(random::nextBytes)
      SefTrailer.parse(tail, size = 10_000_000L)
    }
    // Una directory con l'intestazione giusta ma un conteggio enorme.
    val crafted = bytes {
      ascii("SEFH"); u8(0); u8(0); u8(0); u8(0) // firma + versione
      u8(0xFF); u8(0xFF); u8(0xFF); u8(0x7F) // count enorme (little-endian 0x7FFFFFFF)
      repeat(16) { u8(0) }
      u8(0); u8(0); u8(0); u8(0) // lunghezza (u32 le) del blocco-directory, riempita sotto
      ascii("SEFT")
    }
    SefTrailer.parse(crafted, size = crafted.size.toLong())
  }

  // ---- Catalogo degli input ostili ---------------------------------------------------------------------------------

  private fun named(list: List<ByteArray>): List<Triple<String, String, ByteArray>> =
    list.mapIndexed { i, b -> Triple("ostile$i.bin", "image/heic", b) }

  /** Immagini dei vari formati con strutture patologiche: dimensioni enormi, riquadri profondi, cicli di IFD. */
  private fun hostileImages(): List<ByteArray> = buildList {
    // PNG che dichiara dimensioni enormi (bomba di decompressione per dimensione dichiarata).
    add(png(width = Int.MAX_VALUE, height = Int.MAX_VALUE))
    add(png(width = 100_000, height = 100_000))
    // WebP VP8X con dimensioni al massimo del campo.
    add(webpVp8x(widthMinus1 = 0xFFFFFF, heightMinus1 = 0xFFFFFF))
    // TIFF con un IFD che punta a se stesso (ciclo) e un numero di voci al massimo.
    add(tiffSelfReferencingIfd())
    // ISO-BMFF con una catena di riquadri contigui minimi (verifica il tetto dei riquadri di primo livello).
    add(isoManyBoxes(count = 100_000))
    // HEIF con un meta che dichiara dimensioni enormi ma nessun dato.
    add(heifWithIloc(items = 1))
    // JPEG con moltissimi marcatori minimi (verifica MAX_SEGMENTS).
    add(jpegManyMarkers(count = 100_000))
  }

  private fun png(width: Int, height: Int): ByteArray = bytes {
    write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = bytes { u32be(width.toLong()); u32be(height.toLong()); u8(8); u8(2); u8(0); u8(0); u8(0) }
    u32be(ihdr.size.toLong()); ascii("IHDR"); write(ihdr); u32be(0) // CRC fittizio (i lettori non lo verificano)
    u32be(0); ascii("IEND"); u32be(0)
  }

  private fun webpVp8x(widthMinus1: Int, heightMinus1: Int): ByteArray {
    val chunk = bytes {
      u8(0); u8(0); u8(0); u8(0) // flag
      u8(widthMinus1 and 0xFF); u8((widthMinus1 shr 8) and 0xFF); u8((widthMinus1 shr 16) and 0xFF)
      u8(heightMinus1 and 0xFF); u8((heightMinus1 shr 8) and 0xFF); u8((heightMinus1 shr 16) and 0xFF)
    }
    val body = bytes { ascii("WEBP"); ascii("VP8X"); u32leLen(chunk.size); write(chunk) }
    return bytes { ascii("RIFF"); u32leLen(body.size); write(body) }
  }

  private fun ByteArrayOutputStream.u32leLen(v: Int) {
    u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24)
  }

  private fun tiffSelfReferencingIfd(): ByteArray = bytes {
    ascii("II"); u8(0x2A); u8(0x00) // little-endian, magic 42
    u8(0x08); u8(0); u8(0); u8(0) // offset dell'IFD0 = 8
    u16beLe(1) // una voce
    // voce: tag 0x8769 (ExifIFD), tipo LONG, count 1, valore = 8 (se stessa)
    u16beLe(0x8769); u16beLe(4); write(byteArrayOf(1, 0, 0, 0)); write(byteArrayOf(8, 0, 0, 0))
    write(byteArrayOf(8, 0, 0, 0)) // next IFD = 8 (se stessa)
  }

  private fun ByteArrayOutputStream.u16beLe(v: Int) {
    u8(v); u8(v shr 8)
  }

  private fun isoManyBoxes(count: Int): ByteArray = bytes {
    repeat(count) { u32be(8); ascii("free") }
  }

  private fun jpegManyMarkers(count: Int): ByteArray = bytes {
    u8(0xFF); u8(0xD8) // SOI
    repeat(count) { u8(0xFF); u8(0x01) } // marcatori TEM (nessun payload)
    u8(0xFF); u8(0xD9) // EOI
  }

  // ---- Guardia di tempo -------------------------------------------------------------------------------------------

  private fun withinBudget(what: String, block: () -> Unit) {
    val error = AtomicReference<Throwable?>()
    val worker = Thread {
      try {
        block()
      } catch (t: Throwable) {
        error.set(t)
      }
    }
    worker.isDaemon = true
    worker.start()
    worker.join(BUDGET_MS)
    assertWithMessage("$what: superato il budget di $BUDGET_MS ms (possibile ciclo o allocazione senza limite)")
      .that(worker.isAlive).isFalse()
    error.get()?.let { throw AssertionError("$what: eccezione non gestita su input ostile", it) }
  }

  private companion object {
    const val BUDGET_MS: Long = 4_000L
  }
}
