package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/** Dati di prova dei corpi `vec` e `ocr` (Fase 6, P2): vettori unitari deterministici, righe di testo, chiavi di contenuto. */
object AiFx {
  fun key(n: Int): ContentKey {
    val bytes = "contenuto $n".toByteArray()
    return ContentKey.of(QuickHashAlgorithm.ofContent(bytes), bytes.size.toLong())
  }

  /** Un vettore di norma 1 (in float16) deterministico per [seed], moltiplicato per [scale]. */
  fun unit(seed: Int, scale: Float = 1f, dimension: Int = 768): ShortArray {
    val random = Random(seed)
    val values = FloatArray(dimension) { random.nextFloat() - 0.5f }
    val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    return Float16.encode(FloatArray(dimension) { values[it] / norm * scale })
  }

  fun photo(seed: Int, recipe: Int = 1): VecBody = VecBody(SpaceCode.CURRENT, recipe, VecSource.ANALYSIS, listOf(VecItem(null, unit(seed))))

  fun video(seed: Int, frames: Int = 9, recipe: Int = 1): VecBody =
    VecBody(SpaceCode.CURRENT, recipe, VecSource.FRAMES, List(frames) { VecItem(it * 30_000, unit(seed * 31 + it)) })

  fun line(text: String, angle: Int = 0): OcrLine {
    val words = text.split(' ')
    val step = 65_535 / words.size
    return OcrLine(ShortBox(100, 200, 30_000, 4_000), angle, words.indices.map { WordSpan(it * step, it * step + step / 2) }, text)
  }

  fun text(vararg lines: String, recipe: Int = 1): OcrBody =
    OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe, OcrSource.ANALYSIS, lines.map { line(it) })

  /** Un segmento scritto a mano (come un dispositivo compromesso che lo elenca nella sua testa firmata): impronta giusta. */
  fun rawSegment(
    entries: List<Pair<ByteArray, ByteArray>>,
    cls: Int = 7,
    version: Int = TrustFormat.AI_VERSION,
    reserved: Byte = 0,
    declared: Int = entries.size,
  ): Pair<ByteArray, SegmentRef> {
    val out = ByteArrayOutputStream()
    out.write("MCGB".toByteArray())
    out.write(version)
    out.write(cls)
    out.write(byteArrayOf(reserved, 0, 0))
    out.write(ByteBuffer.allocate(4).putInt(declared).array())
    for ((k, body) in entries) {
      out.write(k)
      out.write(ByteBuffer.allocate(4).putInt(body.size).array())
    }
    entries.forEach { out.write(it.second) }
    val bytes = out.toByteArray()
    return bytes to SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())
  }
}

/**
 * Il codec ostile dei dati AI (Fase 6, P2; §2.3, E-I20, C9): ogni campo del §2.3 ha la sua prova con byte ostili. Un errore rifiuta
 * il corpo e quindi il segmento intero; mai un'eccezione verso fuori.
 */
class AiSegmentsTest {

  private fun vecBytes(body: VecBody = AiFx.photo(1)) = AiSegments.encodeBody(body)

  private fun ocrBytes(body: OcrBody = AiFx.text("Via Roma 12", "Scontrino")) = AiSegments.encodeBody(body)

  private fun rejectVec(bytes: ByteArray): RejectReason? = AiSegments.decodeBody(AiClass.VEC, bytes).rejectionOrNull()

  private fun rejectOcr(bytes: ByteArray): RejectReason? = AiSegments.decodeBody(AiClass.OCR, bytes).rejectionOrNull()

  private fun ByteArray.with(at: Int, vararg values: Int): ByteArray = copyOf().also { b -> values.forEachIndexed { i, v -> b[at + i] = v.toByte() } }

  // --- Corpo vec ---------------------------------------------------------------------------------------------------------

  @Test
  fun vec_unaFotoEUnVideoSiRileggonoUguali_eIlCorpoPiuGrandeEDi13868Byte() {
    val photo = AiFx.photo(7)
    assertThat(AiSegments.decodeBody(AiClass.VEC, vecBytes(photo)).acceptedOrNull()).isEqualTo(photo)
    val video = AiFx.video(3)
    val bytes = vecBytes(video)
    assertThat(bytes.size).isEqualTo(13_868)
    assertThat(bytes.size).isAtMost(TrustLimits.MAX_VEC_BODY_BYTES)
    assertThat(AiSegments.decodeBody(AiClass.VEC, bytes).acceptedOrNull()).isEqualTo(video)
  }

  @Test
  fun vec_componenteNaNOInfinitaRifiutata() {
    val bytes = vecBytes()
    // Componenti a partire dal byte 8 (sorgente d'immagine: niente istanti).
    for (bits in listOf(0x7e00, 0x7c00, 0xfc00, 0x7c01, 0xffff)) {
      assertWithMessage("componente %s", "%04x".format(bits)).that(rejectVec(bytes.with(8 + 2 * 100, bits ushr 8, bits and 0xff))).isEqualTo(RejectReason.INVALID_FIELD)
    }
    // Anche nell'ultimo fotogramma di un video.
    val video = vecBytes(AiFx.video(2))
    assertThat(rejectVec(video.with(video.size - 2, 0x7e, 0x00))).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun vec_normaFuoriDa099e101Rifiutata_ancheIlVettoreNullo() {
    for (scale in listOf(0f, 0.5f, 0.985f, 1.015f, 2f)) {
      val body = VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(VecItem(null, AiFx.unit(5, scale))))
      assertWithMessage("scala $scale").that(AiSegments.bodyOrNull(body)).isNull()
      // Lo stesso corpo scritto a mano da un dispositivo compromesso: rifiutato da chi legge.
      val forged = vecBytes().copyOf().also { b ->
        val bits = AiFx.unit(5, scale)
        for (i in bits.indices) {
          b[8 + 2 * i] = (bits[i].toInt() ushr 8).toByte()
          b[9 + 2 * i] = bits[i].toByte()
        }
      }
      assertWithMessage("scala $scale, letto").that(rejectVec(forged)).isEqualTo(RejectReason.INVALID_FIELD)
    }
    // Dentro l'intervallo vale.
    for (scale in listOf(0.995f, 1.005f)) {
      assertThat(AiSegments.bodyOrNull(VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(VecItem(null, AiFx.unit(5, scale)))))).isNotNull()
    }
  }

  @Test
  fun vec_spazioDimensionePrecisioneVersioneSconosciuteRifiutate() {
    val bytes = vecBytes()
    assertThat(rejectVec(bytes.with(0, 2))).isEqualTo(RejectReason.INVALID_FIELD) // versione del corpo
    assertThat(rejectVec(bytes.with(1, 2))).isEqualTo(RejectReason.INVALID_FIELD) // spazio 2
    assertThat(rejectVec(bytes.with(1, 0))).isEqualTo(RejectReason.INVALID_FIELD) // spazio 0
    assertThat(rejectVec(bytes.with(2, 0x02, 0xff))).isEqualTo(RejectReason.INVALID_FIELD) // dimensione 767
    assertThat(rejectVec(bytes.with(2, 0x03, 0x01))).isEqualTo(RejectReason.INVALID_FIELD) // dimensione 769
    assertThat(rejectVec(bytes.with(4, 1))).isEqualTo(RejectReason.INVALID_FIELD) // precisione 1
    assertThat(rejectVec(bytes.with(4, 4))).isEqualTo(RejectReason.INVALID_FIELD) // float32
  }

  @Test
  fun vec_ricettaSorgenteENumeroDiVettoriFuoriIntervalloRifiutati() {
    val bytes = vecBytes()
    assertThat(rejectVec(bytes.with(5, 0))).isEqualTo(RejectReason.INVALID_FIELD) // ricetta 0
    assertThat(AiSegments.decodeBody(AiClass.VEC, bytes.with(5, 255)).acceptedOrNull()!!.recipe).isEqualTo(255)
    assertThat(rejectVec(bytes.with(6, 0))).isEqualTo(RejectReason.INVALID_FIELD) // sorgente 0
    assertThat(rejectVec(bytes.with(6, 4))).isEqualTo(RejectReason.INVALID_FIELD) // sorgente 4
    assertThat(rejectVec(bytes.with(7, 0))).isEqualTo(RejectReason.INVALID_FIELD) // nessun vettore
    assertThat(rejectVec(bytes.with(7, 2))).isEqualTo(RejectReason.INVALID_FIELD) // due vettori per una foto
    assertThat(rejectVec(bytes.with(6, 3).with(7, 2))).isEqualTo(RejectReason.INVALID_FIELD) // due per i byte del telefono
    val video = vecBytes(AiFx.video(4))
    assertThat(rejectVec(video.with(7, 10))).isEqualTo(RejectReason.INVALID_FIELD) // dieci fotogrammi
    // Una foto dichiarata come fotogrammi: gli istanti mancano, la lunghezza non torna.
    assertThat(rejectVec(bytes.with(6, 2))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun vec_istanteNegativoELunghezzaDiversaRifiutati() {
    val video = vecBytes(AiFx.video(9, frames = 3))
    assertThat(rejectVec(video.with(8, 0x80))).isEqualTo(RejectReason.INVALID_FIELD) // istante negativo
    assertThat(rejectVec(video + 0)).isEqualTo(RejectReason.MALFORMED) // un byte in piu'
    assertThat(rejectVec(video.copyOf(video.size - 1))).isEqualTo(RejectReason.MALFORMED) // uno in meno
    assertThat(rejectVec(video.copyOf(7))).isEqualTo(RejectReason.MALFORMED) // intestazione troncata
    assertThat(rejectVec(ByteArray(TrustLimits.MAX_VEC_BODY_BYTES + 1))).isEqualTo(RejectReason.TOO_LARGE)
    // Un video dichiarato con 9 fotogrammi ma con i byte di 3: la lunghezza si controlla prima di allocare.
    assertThat(rejectVec(video.with(7, 9))).isEqualTo(RejectReason.MALFORMED)
  }

  // --- Corpo ocr ---------------------------------------------------------------------------------------------------------

  @Test
  fun ocr_righeConRiquadroInclinazioneEParoleSiRileggonoUguali_nessunTestoEUnRisultato() {
    val body = OcrBody(
      OcrEngineCode.MLKIT_V2_LATIN,
      3,
      OcrSource.HQ,
      listOf(AiFx.line("Totale euro 12,50", angle = -12), AiFx.line("Grazie e arrivederci", angle = 90), AiFx.line("città", angle = -90)),
    )
    assertThat(AiSegments.decodeBody(AiClass.OCR, ocrBytes(body)).acceptedOrNull()).isEqualTo(body)
    val empty = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS, emptyList())
    assertThat(ocrBytes(empty)).hasLength(6)
    assertThat((AiSegments.decodeBody(AiClass.OCR, ocrBytes(empty)).acceptedOrNull() as OcrBody).noText).isTrue()
  }

  /** Il byte di inizio della prima riga e i campi dentro: riquadro (0..7), inclinazione (8), parole (9), estremi, lunghezza, testo. */
  private val first = 6

  @Test
  fun ocr_inclinazioneFuoriIntervalloEParoleInNumeroSbagliatoRifiutate() {
    val bytes = ocrBytes(AiFx.text("Via Roma"))
    assertThat(rejectOcr(bytes.with(first + 8, 91))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejectOcr(bytes.with(first + 8, -91))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejectOcr(bytes.with(first + 8, -128))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejectOcr(bytes.with(first + 9, 0))).isEqualTo(RejectReason.INVALID_FIELD) // nessuna parola
    assertThat(rejectOcr(bytes.with(first + 9, 65))).isEqualTo(RejectReason.INVALID_FIELD) // oltre 64
    // Tre parole dichiarate per un testo che ne ha due: la riga non torna (o finisce troncata).
    assertThat(rejectOcr(bytes.with(first + 9, 3))).isNotNull()
  }

  @Test
  fun ocr_estremiDecrescentiORovesciatiERiquadroRovesciatoRifiutati() {
    val bytes = ocrBytes(AiFx.text("Via Roma"))
    val words = first + 10
    // Seconda parola che comincia prima della prima.
    assertThat(rejectOcr(bytes.with(words, 0x00, 0x10).with(words + 4, 0, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    // Fine della prima parola dopo la fine della seconda.
    assertThat(rejectOcr(bytes.with(words + 2, 0xff, 0xff))).isEqualTo(RejectReason.INVALID_FIELD)
    // Una parola con l'inizio dopo la fine.
    assertThat(rejectOcr(bytes.with(words, 0x7f, 0xff))).isEqualTo(RejectReason.INVALID_FIELD)
    // Riquadro con x0 > x1 e con y0 > y1.
    assertThat(rejectOcr(bytes.with(first, 0xff, 0xff))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejectOcr(bytes.with(first + 2, 0xff, 0xff))).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun ocr_paroleCheNonTornanoConIlTesto_ancheParoleVuote() {
    // Il testo ha tre pezzi ("a", "", "b") per due parole dichiarate, e una parola vuota non si accetta in nessun caso.
    val good = ocrBytes(AiFx.text("ab cd"))
    val textAt = first + 10 + 2 * 4 + 2
    assertThat(rejectOcr(good.with(textAt + 1, ' '.code))).isEqualTo(RejectReason.INVALID_FIELD) // "a  cd": tre pezzi
    assertThat(rejectOcr(good.with(textAt + 2, 'x'.code))).isEqualTo(RejectReason.INVALID_FIELD) // "abxcd": un pezzo
    // Due parole dichiarate, testo " b" (spazio iniziale): due pezzi, ma uno vuoto.
    val spaced = rawOcr(listOf(RawLine(text = " b".toByteArray(), words = 2)))
    assertThat(rejectOcr(spaced)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThrows(IllegalArgumentException::class.java) { AiFx.line(" b") }
    assertThrows(IllegalArgumentException::class.java) { AiFx.line("a  b") }
  }

  @Test
  fun ocr_testoVuotoOltre1024ByteOTroncatoRifiutato() {
    assertThat(rejectOcr(rawOcr(listOf(RawLine(text = ByteArray(0), words = 1))))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejectOcr(rawOcr(listOf(RawLine(text = "x".repeat(1_025).toByteArray()))))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(AiSegments.decodeBody(AiClass.OCR, rawOcr(listOf(RawLine(text = "x".repeat(1_024).toByteArray())))).acceptedOrNull()).isNotNull()
    // Lunghezza dichiarata oltre la fine del corpo.
    val bytes = ocrBytes(AiFx.text("abc"))
    val lengthAt = first + 10 + 4
    assertThat(rejectOcr(bytes.with(lengthAt, 0x00, 0x40))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejectOcr(bytes + 'x'.code.toByte())).isEqualTo(RejectReason.MALFORMED) // byte in piu'
    assertThat(rejectOcr(bytes.copyOf(bytes.size - 1))).isEqualTo(RejectReason.MALFORMED) // troncato
  }

  @Test
  fun ocr_utf8NonValidoRifiutato_formeLungheSurrogatiOltreIlMassimoContinuazioni() {
    val hostile = listOf(
      byteArrayOf(0xc0.toByte(), 0x80.toByte()), // forma lunga di U+0000
      byteArrayOf(0xc1.toByte(), 0xbf.toByte()), // forma lunga
      byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0xaf.toByte()), // forma lunga a 3 byte
      byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0xaf.toByte()), // forma lunga a 4 byte
      byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), // surrogato alto U+D800
      byteArrayOf(0xed.toByte(), 0xbf.toByte(), 0xbf.toByte()), // surrogato basso U+DFFF
      byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // oltre U+10FFFF
      byteArrayOf(0xf5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), // byte di testa impossibile
      byteArrayOf(0x80.toByte()), // continuazione isolata
      byteArrayOf('a'.code.toByte(), 0xc3.toByte()), // sequenza troncata
      byteArrayOf(0xe2.toByte(), 0x28, 0xa1.toByte()), // continuazione sbagliata
      byteArrayOf(0xff.toByte()),
    )
    for (bytes in hostile) {
      assertWithMessage(bytes.joinToString(" ") { "%02x".format(it) })
        .that(rejectOcr(rawOcr(listOf(RawLine(text = bytes)))))
        .isEqualTo(RejectReason.INVALID_FIELD)
    }
    // Lo stesso testo ben formato (a 2, 3 e 4 byte) vale.
    for (ok in listOf("è", "€uro", "😀")) {
      assertThat(AiSegments.decodeBody(AiClass.OCR, rawOcr(listOf(RawLine(text = ok.toByteArray())))).acceptedOrNull()).isNotNull()
    }
  }

  @Test
  fun ocr_caratteriDiControlloDiDirezioneEBomRifiutati_ancheInScrittura() {
    val forbidden = listOf(
      "\u0000", "\u0001", "\u001f", "\u007f", "\u0085", "\u009f", // C0, DEL, C1
      "‎", "‏", "‪", "‫", "‬", "‭", "‮", "⁦", "⁧", "⁨", "⁩", // direzione
      "﻿", "\t", "\n", "\r", " ", // BOM e separatori
    )
    for (c in forbidden) {
      val text = "Tot${c}ale"
      assertWithMessage("U+%s", "%04X".format(c[0].code)).that(rejectOcr(rawOcr(listOf(RawLine(text = text.toByteArray()))))).isEqualTo(RejectReason.INVALID_FIELD)
      assertWithMessage("U+%s in scrittura", "%04X".format(c[0].code)).that(AiSegments.bodyOrNull(AiFx.text(text))).isNull()
    }
  }

  @Test
  fun ocr_testoNonInNfcRifiutato_eLaPuliziaDiUnaParolaLoSistema() {
    val decomposed = "città" // "città" con l'accento combinato
    assertThat(rejectOcr(rawOcr(listOf(RawLine(text = decomposed.toByteArray()))))).isEqualTo(RejectReason.NOT_CANONICAL)
    assertThat(AiSegments.bodyOrNull(AiFx.text(decomposed))).isNull()
    assertThat(OcrText.cleanWord(decomposed)).isEqualTo("città")
    assertThat(OcrText.cleanWord("To‮t ale\u0000")).isEqualTo("Totale")
    assertThat(OcrText.cleanWord("‎ ﻿")).isNull()
    assertThat(OcrText.cleanWord("\uD800x")).isEqualTo("x") // surrogato spaiato
  }

  @Test
  fun ocr_motoreRicettaSorgenteVersioneERigheOltreIlTettoRifiutati() {
    val bytes = ocrBytes()
    assertThat(rejectOcr(bytes.with(0, 2))).isEqualTo(RejectReason.INVALID_FIELD) // versione del corpo
    assertThat(rejectOcr(bytes.with(1, 2))).isEqualTo(RejectReason.INVALID_FIELD) // motore
    assertThat(rejectOcr(bytes.with(2, 0))).isEqualTo(RejectReason.INVALID_FIELD) // ricetta 0
    assertThat(rejectOcr(bytes.with(3, 0))).isEqualTo(RejectReason.INVALID_FIELD) // sorgente 0
    assertThat(rejectOcr(bytes.with(3, 5))).isEqualTo(RejectReason.INVALID_FIELD) // sorgente 5
    assertThat(rejectOcr(bytes.with(4, 0x01, 0x91))).isEqualTo(RejectReason.TOO_LARGE) // 401 righe
    assertThat(rejectOcr(bytes.with(4, 0xff, 0xff))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(rejectOcr(bytes.copyOf(5))).isEqualTo(RejectReason.MALFORMED)
    // 400 righe valgono; il corpo oltre 64 KiB no (righe lunghe).
    val many = rawOcr(List(400) { RawLine(text = "riga".toByteArray()) })
    assertThat(AiSegments.decodeBody(AiClass.OCR, many).acceptedOrNull()).isNotNull()
    val huge = rawOcr(List(70) { RawLine(text = "x".repeat(1_000).toByteArray()) })
    assertThat(huge.size).isGreaterThan(TrustLimits.MAX_OCR_BODY_BYTES)
    assertThat(rejectOcr(huge)).isEqualTo(RejectReason.TOO_LARGE)
  }

  // --- Segmenti ----------------------------------------------------------------------------------------------------------

  @Test
  fun segmento_scrittoInQualunqueOrdineSiRileggeUguale_versione2EChiaviDiContenutoIntere() {
    val items = (1..20).map { AiFx.key(it) to AiFx.photo(it) }
    val a = AiSegments.encode(AiClass.VEC, items)
    val b = AiSegments.encode(AiClass.VEC, items.shuffled(Random(3)))
    assertThat(a.bytes.contentEquals(b.bytes)).isTrue()
    assertThat(a.cls).isEqualTo(SegmentClass.VEC)
    assertThat(a.bytes[4].toInt()).isEqualTo(TrustFormat.AI_VERSION)
    val content = AiSegments.decode(a.bytes, a.ref, AiClass.VEC).acceptedOrNull()!!
    assertThat(content.entries.map { it.contentKey }).containsExactlyElementsIn(items.map { it.first })
    assertThat(content.entries.map { it.contentKey.hex }).isInOrder()
    for (entry in content.entries) {
      assertThat(entry.body).isEqualTo(items.single { it.first == entry.contentKey }.second)
      assertThat(entry.digest).isEqualTo(AiSegments.digest(AiSegments.encodeBody(entry.body)))
    }
    val texts = AiSegments.encode(AiClass.OCR, listOf(AiFx.key(1) to AiFx.text("Ciao")))
    assertThat(AiSegments.decode(texts.bytes, texts.ref, AiClass.OCR).acceptedOrNull()!!.entries.single().body).isEqualTo(AiFx.text("Ciao"))
  }

  @Test
  fun segmento_classeDiversaDaQuellaDellaTestaRifiutata() {
    val vec = AiSegments.encode(AiClass.VEC, listOf(AiFx.key(1) to AiFx.photo(1)))
    assertThat(AiSegments.decode(vec.bytes, vec.ref, AiClass.OCR).rejectionOrNull()).isEqualTo(RejectReason.WRONG_TYPE)
    // Un segmento dell'indice (versione 1) con la classe 7 non e' un segmento ai, e AiSegments non legge un segmento d'immagini.
    val (v1, ref1) = AiFx.rawSegment(listOf(AiFx.key(1).bytes() to vecBytes()), version = 1)
    assertThat(AiSegments.decode(v1, ref1, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    val (thumbs, refT) = AiFx.rawSegment(listOf(AiFx.key(1).bytes() to vecBytes()), cls = SegmentClass.THUMB_S.code)
    assertThat(AiSegments.decode(thumbs, refT, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.WRONG_TYPE)
    // E il codec dell'indice non legge ne' scrive vec e ocr.
    assertThat(IndexSegments.decode(vec.bytes, vec.ref, SegmentClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(IndexSegments.decode(v1, ref1, SegmentClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThrows(IllegalArgumentException::class.java) { IndexSegments.encode(SegmentClass.OCR, listOf(SegmentEntry(AiFx.key(1).bytes(), ocrBytes()))) }
    assertThat(IndexSegments.admits(SegmentClass.VEC, vecBytes())).isFalse()
  }

  @Test
  fun segmento_versionePiuNuovaImprontaDimensioneTabellaEByteRiservatiRifiutati() {
    val good = listOf(AiFx.key(1).bytes() to vecBytes(), AiFx.key(2).bytes() to vecBytes(AiFx.photo(2))).sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }
    val (v3, ref3) = AiFx.rawSegment(good, version = 3)
    assertThat(AiSegments.decode(v3, ref3, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    val (bytes, ref) = AiFx.rawSegment(good)
    assertThat(AiSegments.decode(bytes, ref, AiClass.VEC).acceptedOrNull()).isNotNull()
    assertThat(AiSegments.decode(bytes.copyOf().also { it[it.size - 1] = 1 }, ref, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(AiSegments.decode(bytes + 0, ref, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    val (reserved, refR) = AiFx.rawSegment(good, reserved = 1)
    assertThat(AiSegments.decode(reserved, refR, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    // N dichiarato diverso dalle righe, zero, oltre il tetto.
    for ((declared, reason) in listOf(1 to RejectReason.MALFORMED, 0 to RejectReason.MALFORMED, 4_097 to RejectReason.TOO_LARGE)) {
      val (b, r) = AiFx.rawSegment(good, declared = declared)
      assertWithMessage("N = $declared").that(AiSegments.decode(b, r, AiClass.VEC).rejectionOrNull()).isEqualTo(reason)
    }
    // Tre righe dichiarate per due voci: la terza riga si legge dentro i corpi e non torna (tetto o somma).
    val (three, ref3b) = AiFx.rawSegment(good, declared = 3)
    assertThat(AiSegments.decode(three, ref3b, AiClass.VEC).rejectionOrNull()).isNotNull()
    // Chiavi ripetute o non crescenti.
    val (dup, refD) = AiFx.rawSegment(listOf(good[0], good[0]))
    assertThat(AiSegments.decode(dup, refD, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    val (desc, refDesc) = AiFx.rawSegment(good.reversed())
    assertThat(AiSegments.decode(desc, refDesc, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    // Un corpo oltre il tetto della classe nella tabella: rifiutato prima di leggerlo.
    val (big, refB) = AiFx.rawSegment(listOf(AiFx.key(1).bytes() to ByteArray(TrustLimits.MAX_VEC_BODY_BYTES + 1)))
    assertThat(AiSegments.decode(big, refB, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun segmento_unCorpoOstileScartaIlSegmentoIntero() {
    val bad = vecBytes().with(8, 0x7e, 0x00)
    val entries = listOf(AiFx.key(1).bytes() to vecBytes(), AiFx.key(2).bytes() to bad).sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }
    val (bytes, ref) = AiFx.rawSegment(entries)
    assertThat(AiSegments.decode(bytes, ref, AiClass.VEC).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun scrittura_corpiNonAmmessiOChiaviRipetuteSonoErroriDiChiChiama() {
    assertThat(AiSegments.admits(AiFx.photo(1))).isTrue()
    assertThat(AiSegments.admits(VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(VecItem(null, AiFx.unit(1, 3f)))))).isFalse()
    assertThrows(IllegalArgumentException::class.java) { AiSegments.encode(AiClass.VEC, listOf(AiFx.key(1) to AiFx.photo(1), AiFx.key(1) to AiFx.photo(2))) }
    assertThrows(IllegalArgumentException::class.java) { AiSegments.encode(AiClass.VEC, listOf(AiFx.key(1) to AiFx.text("x"))) }
    assertThrows(IllegalArgumentException::class.java) { AiSegments.encodeBodies(AiClass.OCR, listOf(AiFx.key(1) to vecBytes())) }
    assertThrows(IllegalArgumentException::class.java) { AiSegments.encode(AiClass.VEC, emptyList()) }
  }

  /**
   * "Mai un crash" (E-I14, E-I20): 3.000 mutazioni casuali di segmenti validi, firmati come li firmerebbe un dispositivo compromesso
   * (impronta ricalcolata): ogni lettura finisce in un verdetto, e quando accetta rilegge un contenuto valido.
   */
  @Test
  fun mutazioniCasualiFinisconoSempreInUnVerdetto() {
    val random = Random(2026)
    val vec = AiSegments.encode(AiClass.VEC, (1..3).map { AiFx.key(it) to AiFx.photo(it) } + (AiFx.key(9) to AiFx.video(9, 2))).bytes
    val ocr = AiSegments.encode(AiClass.OCR, (1..4).map { AiFx.key(it) to AiFx.text("Riga $it", "àèìòù €", recipe = it) }).bytes
    var accepted = 0
    repeat(3_000) { round ->
      val (source, cls) = if (round % 2 == 0) vec to AiClass.VEC else ocr to AiClass.OCR
      val bytes = source.copyOf()
      repeat(1 + random.nextInt(4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
      val cut = if (random.nextInt(10) == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes
      val ref = SegmentRef(Hashes.sha256Name(cut), cut.size.toLong().coerceAtLeast(1))
      val verdict = AiSegments.decode(cut, ref, cls)
      if (verdict is Verdict.Accepted) {
        accepted++
        verdict.value.entries.forEach { assertThat(AiSegments.bodyOrNull(it.body)!!.contentEquals(it.bodyBytes)).isTrue() }
      }
    }
    // Molte rifiutate (una mutazione dentro un vettore che lascia la norma nell'intervallo, o una lettera ASCII, puo' valere).
    assertThat(3_000 - accepted).isGreaterThan(500)
  }

  // --- Corpi ocr scritti a mano ------------------------------------------------------------------------------------------

  private class RawLine(val text: ByteArray, val words: Int = String(text, Charsets.UTF_8).split(' ').size.coerceAtLeast(1), val angle: Int = 0)

  private fun rawOcr(lines: List<RawLine>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(1, 1, 1, 1))
    out.write(byteArrayOf((lines.size ushr 8).toByte(), lines.size.toByte()))
    for (line in lines) {
      out.write(byteArrayOf(0, 10, 0, 20, 0x10, 0, 0x02, 0)) // riquadro
      out.write(line.angle)
      out.write(line.words)
      repeat(line.words) { i -> out.write(byteArrayOf(0, (i * 2).toByte(), 0, (i * 2 + 1).toByte())) }
      out.write(byteArrayOf((line.text.size ushr 8).toByte(), line.text.size.toByte()))
      out.write(line.text)
    }
    return out.toByteArray()
  }
}
