package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Test

/**
 * Segmenti OSTILI (riga P3 del §4.4, E-I14, C9, F8): un dispositivo compromesso o chiunque scriva via SMB. Il riferimento
 * si calcola sempre sui byte ostili (come se un dispositivo approvato li elencasse nella sua testa firmata), cosi' si
 * arriva davvero al contenuto. Ogni caso: rifiuto con il motivo giusto, nessuna eccezione, nessuna allocazione dalle
 * lunghezze dichiarate.
 */
class HostileSegmentTest {

  private val key = ByteArray(32) { 1 }
  private fun keyOf(b: Int) = ByteArray(32) { b.toByte() }

  private fun reason(raw: RawSegment, expected: SegmentClass = SegmentClass.META): RejectReason? = raw.decode(expected).rejectionOrNull()

  private fun metaBody(body: ByteArray): RejectReason? = reason(RawSegment().add(key, body))

  private fun image(body: ByteArray, cls: SegmentClass = SegmentClass.THUMB_S): RejectReason? =
    reason(RawSegment(cls = cls.code).add(key, body), cls)

  // --- Contenitore, impronta, versione, classe ---------------------------------------------------------------------

  @Test
  fun impronteEDimensioniDiverseDallaTesta() {
    val good = IndexSegments.meta(listOf(TestRecords.present("Foto/a.jpg", 1)))
    val otherHash = SegmentRef(Hashes.sha256Name(byteArrayOf(9)), good.ref.size)

    assertThat(IndexSegments.decode(good.bytes, otherHash, SegmentClass.META).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(IndexSegments.decode(good.bytes.copyOf(good.bytes.size - 1), good.ref, SegmentClass.META).rejectionOrNull())
      .isEqualTo(RejectReason.HASH_MISMATCH)
    val altered = good.bytes.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
    assertThat(IndexSegments.decode(altered, good.ref, SegmentClass.META).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
  }

  @Test
  fun contenitoreVersioneEClasseSbagliati() {
    val valid = { RawSegment.metaOf(TestRecords.present("Foto/a.jpg", 1)) }

    assertThat(reason(valid().apply { magic = "MCGS".toByteArray() })).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(valid().apply { version = 2 })).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(reason(valid().apply { version = 0 })).isEqualTo(RejectReason.MALFORMED)
    // Classe del contenuto diversa da quella della testa: anche un segmento "buono" di un'altra classe.
    assertThat(reason(valid(), SegmentClass.THUMB_S)).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(reason(valid().apply { cls = 7 })).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(reason(valid().apply { cls = 16 }, SegmentClass.PERSONAL)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(valid().apply { reserved = byteArrayOf(0, 1, 0) })).isEqualTo(RejectReason.MALFORMED)
    // Contenitore vuoto o troncato.
    assertThat(IndexSegments.decode("MCGB".toByteArray() + 1, ref("MCGB".toByteArray() + 1), SegmentClass.META).rejectionOrNull())
      .isEqualTo(RejectReason.MALFORMED)
  }

  // --- Tabella -------------------------------------------------------------------------------------------------------

  @Test
  fun tabellaIncoerente() {
    val body = MetaRecords.encode(TestRecords.present("Foto/a.jpg", 1))
    val k = IndexKeys.meta(TestRecords.present("Foto/a.jpg", 1).path)

    assertThat(reason(RawSegment(declaredCount = 0))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(RawSegment(declaredCount = TrustLimits.MAX_SEGMENT_ENTRIES + 1).add(k, body))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(RawSegment(declaredCount = -1).add(k, body))).isEqualTo(RejectReason.TOO_LARGE)
    // N grande, file piccolo: tabella "oltre la fine", nessuna allocazione da N.
    assertThat(reason(RawSegment(declaredCount = 4000).add(k, body))).isEqualTo(RejectReason.MALFORMED)
    // Somma delle lunghezze diversa dal resto del file: un byte in piu', un byte in meno.
    assertThat(reason(RawSegment(trailing = byteArrayOf(0)).add(k, body))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(RawSegment().add(k, body, declaredLength = body.size + 1L))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(RawSegment().add(k, body, declaredLength = body.size - 1L).apply { trailing = byteArrayOf() })).isEqualTo(RejectReason.MALFORMED)
    // Lunghezza zero e lunghezza enorme (oltre il tetto della classe, e oltre 2^31).
    assertThat(reason(RawSegment().add(k, body).add(keyOf(0x7F), ByteArray(0)))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(RawSegment().add(k, body, declaredLength = 0xFFFFFFFFL))).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun chiaviNonCrescenti() {
    val a = TestRecords.present("Foto/a.jpg", 1)
    val b = TestRecords.present("Foto/b.jpg", 2)
    val (low, high) = listOf(a, b).sortedWith { x, y -> IndexKeys.compare(IndexKeys.meta(x.path), IndexKeys.meta(y.path)) }

    val decreasing = RawSegment().add(IndexKeys.meta(high.path), MetaRecords.encode(high)).add(IndexKeys.meta(low.path), MetaRecords.encode(low))
    assertThat(reason(decreasing)).isEqualTo(RejectReason.MALFORMED)
    val repeated = RawSegment(cls = SegmentClass.THUMB_S.code).add(key, TestImages.jpeg(10, 10)).add(key, TestImages.jpeg(10, 10))
    assertThat(reason(repeated, SegmentClass.THUMB_S)).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun unRecordSottoLaChiaveDiUnAltroPercorso() {
    val record = TestRecords.present("Foto/a.jpg", 1)

    assertThat(metaBody(MetaRecords.encode(record))).isEqualTo(RejectReason.INVALID_FIELD) // chiave = 0x01...
    assertThat(reason(RawSegment().add(IndexKeys.meta(record.path), MetaRecords.encode(record)))).isNull()
  }

  // --- Corpi oltre i tetti ---------------------------------------------------------------------------------------------

  @Test
  fun corpiOltreITetti() {
    assertThat(metaBody(ByteArray(TrustLimits.MAX_META_RECORD_BYTES + 1) { ' '.code.toByte() })).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(TestImages.jpeg(100, 100, filler = TrustLimits.MAX_BLOB_BYTES))).isEqualTo(RejectReason.TOO_LARGE)
    val bigFrames = ByteBuffer.allocate(1 + 3 * (8 + 800_000)).apply {
      put(3)
      repeat(3) { putInt(0).putInt(800_000).put(TestImages.jpeg(10, 10, filler = 800_000 - 47)) }
    }.array()
    assertThat(image(bigFrames, SegmentClass.FRAMES)).isEqualTo(RejectReason.TOO_LARGE)
  }

  // --- JSON non canonico e campi fuori forma -----------------------------------------------------------------------

  @Test
  fun jsonNonCanonicoOFuoriForma() {
    val cases = mapOf(
      "spazi" to TestRecords.recordJson { it.replace(",", ", ") } to RejectReason.MALFORMED,
      "chiavi fuori ordine" to TestRecords.recordJson { it.replace("{", "{\"zz\":1,") } to RejectReason.NOT_CANONICAL,
      "escape superfluo" to TestRecords.recordJson { it.replace("Foto/a.jpg", "Foto\\/a.jpg") } to RejectReason.NOT_CANONICAL,
      "chiave ripetuta" to TestRecords.recordJson { it.replace("{", "{\"kind\":\"photo\",") } to RejectReason.MALFORMED,
      "null" to TestRecords.recordJson { it.replace("}", ",\"w\":null}") } to RejectReason.MALFORMED,
      "virgola" to TestRecords.recordJson { it.replace("}", ",\"w\":1.5}") } to RejectReason.MALFORMED,
      "zeri iniziali" to TestRecords.recordJson { it.replace("\"seq\":1", "\"seq\":01") } to RejectReason.MALFORMED,
      "campo sconosciuto" to TestRecords.recordJson { it.replace("{", "{\"aaa\":1,") } to RejectReason.MALFORMED,
      "senza qh" to TestRecords.recordJson { it.replace(Regex("\"qh\":\"[0-9a-z]+\","), "") } to RejectReason.MALFORMED,
      "seq zero" to TestRecords.recordJson { it.replace("\"seq\":1", "\"seq\":0") } to RejectReason.INVALID_FIELD,
      "seq oltre il tetto" to TestRecords.recordJson { it.replace("\"seq\":1", "\"seq\":1000000001") } to RejectReason.INVALID_FIELD,
      "qh corto" to TestRecords.recordJson { it.replace(Regex("\"qh\":\"[0-9a-z]+\""), "\"qh\":\"abc\"") } to RejectReason.INVALID_FIELD,
      "tipo sconosciuto" to TestRecords.recordJson { it.replace(Regex("\"kind\":\"[a-z_]+\""), "\"kind\":\"hologram\"") } to RejectReason.INVALID_FIELD,
      "prep con meta" to TestRecords.recordJson { it.replace("\"qh\":", "\"prep\":2,\"qh\":") } to RejectReason.INVALID_FIELD,
      "data di scatto a meta'" to TestRecords.recordJson { it.replace("\"path\":", "\"offset\":60,\"path\":") } to RejectReason.INVALID_FIELD,
      "lapide falsa" to TestRecords.json("{\"gone\":false,\"path\":\"Foto/a.jpg\",\"seq\":3}") to RejectReason.NOT_CANONICAL,
      "lapide con campi" to TestRecords.json("{\"gone\":true,\"path\":\"Foto/a.jpg\",\"seq\":3,\"size\":1}") to RejectReason.INVALID_FIELD,
      "non un oggetto" to TestRecords.json("[1,2]") to RejectReason.MALFORMED,
      "UTF-8 non valido" to (TestRecords.recordJson().copyOf() + byteArrayOf(0xC3.toByte())) to RejectReason.MALFORMED,
      "troppo profondo" to TestRecords.json("{\"a\":" + "[".repeat(20) + "]".repeat(20) + "}") to RejectReason.TOO_LARGE,
    )
    for ((case, expected) in cases) {
      val (name, body) = case
      assertWithMessage(name).that(metaBody(body)).isEqualTo(expected)
    }
  }

  // --- Percorsi ostili -----------------------------------------------------------------------------------------------

  @Test
  fun percorsiOstiliOFuoriDalleAreeDeiMedia() {
    val hostile = listOf(
      "../x.jpg", "Foto/../x.jpg", "./x.jpg", "/assoluto.jpg", "\\\\\\\\server\\\\x.jpg", "Foto\\\\x.jpg", "C:/x.jpg",
      ".mycloudgallery/family/registry/00000000000000000001", ".MyCloudGallery/x", "\uFF0Emycloudgallery/x",
      "CON.jpg", "Foto/nul", "a\u202Eb.jpg", "Foto/a.jpg ", "Foto//a.jpg", "Foto/", "\uFF0E\uFF0E/x.jpg", "a\u0000b.jpg",
    )
    for (path in hostile) {
      val body = TestRecords.recordJson { it.replace("\"path\":\"Foto/a.jpg\"", "\"path\":\"${path.replace("\u0000", "\\u0000")}\"") }
      assertWithMessage(path).that(metaBody(body)).isEqualTo(RejectReason.HOSTILE_PATH)
    }
    val empty = TestRecords.recordJson { it.replace("\"path\":\"Foto/a.jpg\"", "\"path\":\"\"") }
    assertThat(metaBody(empty)).isEqualTo(RejectReason.HOSTILE_PATH)
  }

  // --- Immagini -------------------------------------------------------------------------------------------------------

  @Test
  fun immaginiConDimensioniEnormiOFormatiNonAmmessi() {
    assertThat(image(TestImages.jpeg(65535, 65535))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(TestImages.jpeg(16385, 1))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(TestImages.jpeg(4097, 4097))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(TestImages.jpeg(100, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(TestImages.webpLossless(16384, 16384))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(TestImages.webpExtended(1 shl 24, 1 shl 24))).isEqualTo(RejectReason.TOO_LARGE)
    // Tela piccola e immagine dentro enorme: chi decodifica non deve credere alla tela.
    assertThat(image(TestImages.webpExtended(100, 100, innerW = 16000, innerH = 16000))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(TestImages.webpExtended(100, 100, animated = true))).isEqualTo(RejectReason.INVALID_FIELD)
    // Formati non ammessi e intestazioni rovinate.
    assertThat(image(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(40))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image("GIF89a".toByteArray() + ByteArray(20))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))).isEqualTo(RejectReason.MALFORMED)
    val sosFirst = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 8) + ByteArray(20)
    assertThat(image(sosFirst)).isEqualTo(RejectReason.MALFORMED)
    val segmentBeyondEnd = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x7F) + ByteArray(20)
    assertThat(image(segmentBeyondEnd)).isEqualTo(RejectReason.MALFORMED)
    val webp = TestImages.webp(100, 100)
    assertThat(image(webp + byteArrayOf(0, 0))).isEqualTo(RejectReason.MALFORMED) // RIFF piu' corto del corpo
    assertThat(image(webp.copyOf(webp.size - 2))).isEqualTo(RejectReason.MALFORMED)
    val unknownVariant = TestImages.riff(TestImages.chunk("VP9 ", ByteArray(10)))
    assertThat(image(unknownVariant)).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun fotogrammiFuoriForma() {
    fun frames(count: Int, vararg frames: Pair<Int, ByteArray>, declared: List<Int>? = null, trailing: Int = 0): ByteArray {
      val out = java.io.ByteArrayOutputStream()
      out.write(count)
      frames.forEachIndexed { i, (at, jpeg) ->
        out.write(ByteBuffer.allocate(8).putInt(at).putInt(declared?.get(i) ?: jpeg.size).array())
        out.write(jpeg)
      }
      out.write(ByteArray(trailing))
      return out.toByteArray()
    }
    val jpeg = TestImages.jpeg(64, 36)

    assertThat(image(frames(1, 0 to jpeg), SegmentClass.FRAMES)).isNull()
    assertThat(image(frames(0), SegmentClass.FRAMES)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(frames(10, *Array(10) { it to jpeg }), SegmentClass.FRAMES)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(image(frames(1, -5 to jpeg), SegmentClass.FRAMES)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(frames(2, 0 to jpeg), SegmentClass.FRAMES)).isEqualTo(RejectReason.MALFORMED)
    assertThat(image(frames(1, 0 to jpeg, declared = listOf(jpeg.size + 5)), SegmentClass.FRAMES)).isEqualTo(RejectReason.MALFORMED)
    assertThat(image(frames(1, 0 to jpeg, trailing = 3), SegmentClass.FRAMES)).isEqualTo(RejectReason.MALFORMED)
    assertThat(image(frames(1, 0 to TestImages.webp(64, 36)), SegmentClass.FRAMES)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(image(frames(1, 0 to TestImages.jpeg(5000, 5000)), SegmentClass.FRAMES)).isEqualTo(RejectReason.TOO_LARGE)
  }

  // --- Nessun crash, qualunque byte ----------------------------------------------------------------------------------

  /**
   * 3.000 mutazioni casuali (byte cambiati, troncamenti, inserimenti, lunghezze della tabella riscritte) di segmenti
   * validi delle tre forme: il decoder risponde sempre con un `Verdict`, mai con un'eccezione.
   */
  @Test
  fun mutazioniCasuali_sempreUnVerdettoMaiUnEccezione() {
    val random = Random(20261014)
    val seeds = listOf(
      SegmentClass.META to IndexSegments.meta((1..20).map { TestRecords.present("Foto/f$it.jpg", it.toLong(), full = it % 2 == 0) }).bytes,
      SegmentClass.THUMB_S to IndexSegments.blobs(SegmentClass.THUMB_S, (1..6).map { TestRecords.key(it) to TestImages.webp(200, 150) }).bytes,
      SegmentClass.FRAMES to IndexSegments.blobs(SegmentClass.FRAMES, listOf(TestRecords.key(1) to FramesBody.encode(listOf(0 to TestImages.jpeg(64, 36))))).bytes,
    )
    var rejected = 0
    repeat(3000) { round ->
      val (cls, seed) = seeds[round % seeds.size]
      val bytes = mutate(seed, random)
      val verdict = try {
        IndexSegments.decode(bytes, ref(bytes), cls)
      } catch (e: Throwable) {
        throw AssertionError("eccezione dal decoder al giro $round: $e", e)
      }
      if (verdict is Verdict.Rejected) rejected++
    }
    assertThat(rejected).isGreaterThan(2000)
  }

  private fun mutate(seed: ByteArray, random: Random): ByteArray {
    val bytes = seed.copyOf()
    return when (random.nextInt(5)) {
      0 -> bytes.also { repeat(1 + random.nextInt(8)) { _ -> it[random.nextInt(it.size)] = random.nextInt().toByte() } }
      1 -> bytes.copyOf(random.nextInt(bytes.size))
      2 -> {
        val at = random.nextInt(bytes.size)
        bytes.copyOfRange(0, at) + randomBytes(random, 1 + random.nextInt(64)) + bytes.copyOfRange(at, bytes.size)
      }
      3 -> bytes.also {
        // Una lunghezza della tabella riscritta (la prima riga e' dopo 13 byte di intestazione).
        val rows = (it.size - 13) / 36
        if (rows > 0) ByteBuffer.wrap(it).putInt(13 + 32 + 36 * random.nextInt(minOf(rows, 4)), random.nextInt())
      }
      else -> bytes.also { ByteBuffer.wrap(it).putInt(9, random.nextInt()) } // N riscritto
    }
  }

  private fun ref(bytes: ByteArray) = SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong().coerceAtLeast(1))
}
