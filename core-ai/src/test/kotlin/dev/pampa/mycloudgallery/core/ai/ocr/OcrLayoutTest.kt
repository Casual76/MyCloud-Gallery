package dev.pampa.mycloudgallery.core.ai.ocr

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.OcrText
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * Fase 6 (P4, D12, D13, D43): dalle righe di ML Kit (qui scritte a mano, senza la libreria) alle righe del formato `ocr`: riquadro
 * normalizzato, inclinazione da -90 a 90, estremi delle parole non decrescenti, parole mai vuote, testo in NFC senza caratteri
 * vietati, tetti. Ogni riga che esce e' una `OcrLine` valida (il suo costruttore controlla la forma, `OcrText` il testo).
 */
class OcrLayoutTest {

  private val width = 1000
  private val height = 500

  /** Una riga dritta con le parole date, ognuna larga come i suoi caratteri, da [x] a destra. */
  private fun straight(words: List<String>, x: Float = 100f, y: Float = 200f, textHeight: Float = 20f, perChar: Float = 10f): RawLine {
    var at = x
    val raw = words.map { w ->
      val x0 = at
      val x1 = at + w.length * perChar
      at = x1 + perChar
      RawWord(w, quad(x0, y, x1, y + textHeight))
    }
    val last = raw.last().corners[1].x
    return RawLine(words.joinToString(" "), quad(x, y, last, y + textHeight), RawBox(x, y, last, y + textHeight), 0f, raw)
  }

  private fun quad(x0: Float, y0: Float, x1: Float, y1: Float) = listOf(RawPoint(x0, y0), RawPoint(x1, y0), RawPoint(x1, y1), RawPoint(x0, y1))

  // --- Riga dritta ------------------------------------------------------------------------------------------------------------

  @Test
  fun unaRigaDrittaDaRiquadroAngoloEEstremiDelleParole() {
    val result = OcrLayout.convert(listOf(straight(listOf("Totale", "euro", "24,50"))), width, height)
    val line = result.lines.single()
    assertThat(line.text).isEqualTo("Totale euro 24,50")
    assertThat(line.angle).isEqualTo(0)
    // Riquadro: x da 100 a 100+6*10+10+4*10+10+5*10 = 270 di 1000; y da 200 a 220 di 500.
    assertThat(line.box.x0).isEqualTo(Math.floor(100.0 / 1000 * 65535).toInt())
    assertThat(line.box.x1).isEqualTo(Math.ceil(270.0 / 1000 * 65535).toInt())
    assertThat(line.box.y0).isEqualTo(Math.floor(200.0 / 500 * 65535).toInt())
    assertThat(line.box.y1).isEqualTo(Math.ceil(220.0 / 500 * 65535).toInt())
    // Le parole stanno nella riga: "Totale" da 0 a 60/170, "euro" da 70/170 a 110/170, "24,50" da 120/170 a 1.
    val unit = 65535.0
    assertThat(line.words[0].start / unit).isWithin(2e-4).of(0.0)
    assertThat(line.words[1].start / unit).isWithin(2e-4).of(70.0 / 170)
    assertThat(line.words[2].start / unit).isWithin(2e-4).of(120.0 / 170)
    assertThat(line.words[0].end / unit).isWithin(2e-4).of(60.0 / 170)
    assertThat(line.words[2].end / unit).isWithin(2e-4).of(1.0)
    assertThat(result.textHeightsPx).containsExactly(20f)
  }

  @Test
  fun leParoleSonoTanteQuantoIlTestoEMaiVuote() {
    val raw = RawLine(
      "  Ciao   mondo  ",
      quad(0f, 0f, 100f, 10f),
      words = listOf(RawWord("Ciao", quad(0f, 0f, 40f, 10f)), RawWord("", quad(40f, 0f, 45f, 10f)), RawWord("mondo", quad(50f, 0f, 100f, 10f))),
    )
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.text).isEqualTo("Ciao mondo")
    assertThat(line.words).hasSize(2)
  }

  // --- Inclinazione -----------------------------------------------------------------------------------------------------------

  private fun rotated(degrees: Double, length: Float = 300f, thickness: Float = 20f, cx: Float = 500f, cy: Float = 250f): RawLine {
    val a = Math.toRadians(degrees)
    val ux = cos(a).toFloat()
    val uy = sin(a).toFloat()
    // perpendicolare "in basso" del testo
    val vx = -uy
    val vy = ux
    fun p(s: Float, t: Float) = RawPoint(cx + ux * s + vx * t, cy + uy * s + vy * t)
    val corners = listOf(p(-length / 2, -thickness / 2), p(length / 2, -thickness / 2), p(length / 2, thickness / 2), p(-length / 2, thickness / 2))
    val words = listOf(
      RawWord("uno", listOf(p(-length / 2, -thickness / 2), p(-length / 2 + 60, -thickness / 2), p(-length / 2 + 60, thickness / 2), p(-length / 2, thickness / 2))),
      RawWord("due", listOf(p(0f, -thickness / 2), p(60f, -thickness / 2), p(60f, thickness / 2), p(0f, thickness / 2))),
    )
    return RawLine("uno due", corners, null, null, words)
  }

  @Test
  fun l_inclinazioneVieneDagliAngoliDellaRiga() {
    for (degrees in listOf(-60.0, -30.0, -10.0, 0.0, 5.0, 15.0, 45.0, 80.0)) {
      val line = OcrLayout.convert(listOf(rotated(degrees)), width, height).lines.single()
      assertThat(line.angle.toDouble()).isWithin(1.0).of(degrees)
    }
  }

  @Test
  fun gliEstremiDelleParoleSeguonoLaDirezioneDellaRigaInclinata() {
    val line = OcrLayout.convert(listOf(rotated(25.0)), width, height).lines.single()
    // "uno" dall'inizio a 60/300, "due" da 150/300 (il centro) a 210/300.
    val unit = 65535.0
    assertThat(line.words[0].start / unit).isWithin(2e-3).of(0.0)
    assertThat(line.words[0].end / unit).isWithin(2e-3).of(60.0 / 300)
    assertThat(line.words[1].start / unit).isWithin(2e-3).of(150.0 / 300)
    assertThat(line.words[1].end / unit).isWithin(2e-3).of(210.0 / 300)
  }

  @Test
  fun senzaAngoliValeLAngoloDichiaratoEIlRiquadro() {
    val raw = RawLine("riga storta", box = RawBox(100f, 100f, 300f, 140f), angleDegrees = -12.4f)
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.angle).isEqualTo(-12)
    assertThat(line.words).hasSize(2)
    // Senza angoli per parola gli estremi sono in proporzione ai caratteri: "riga" (4) e "storta" (6) con uno spazio, 11 in tutto.
    assertThat(line.words[0].end / 65535.0).isWithin(1e-3).of(4.0 / 11)
    assertThat(line.words[1].start / 65535.0).isWithin(1e-3).of(5.0 / 11)
  }

  @Test
  fun l_angoloSiRiportaFraMenoNovantaENovanta() {
    assertThat(OcrLayout.foldAngle(0f)).isEqualTo(0)
    assertThat(OcrLayout.foldAngle(90f)).isEqualTo(90)
    assertThat(OcrLayout.foldAngle(-90f)).isEqualTo(-90)
    assertThat(OcrLayout.foldAngle(179f)).isEqualTo(-1)
    assertThat(OcrLayout.foldAngle(180f)).isEqualTo(0)
    assertThat(OcrLayout.foldAngle(-170f)).isEqualTo(10)
    assertThat(OcrLayout.foldAngle(135f)).isEqualTo(-45)
    assertThat(OcrLayout.foldAngle(1e9f)).isIn(-90..90)
    assertThat(OcrLayout.foldAngle(Float.NaN)).isEqualTo(0)
    assertThat(OcrLayout.foldAngle(Float.POSITIVE_INFINITY)).isEqualTo(0)
  }

  @Test
  fun unTestoCapovoltoHaGliEstremiNellOrdineDiLettura() {
    // Testo ruotato di 180 gradi: la direzione di lettura va da destra a sinistra. Gli estremi restano non decrescenti
    // nell'ordine delle parole (quello che il formato richiede); l'angolo si riporta a 0.
    val line = OcrLayout.convert(listOf(rotated(180.0)), width, height).lines.single()
    assertThat(line.angle).isEqualTo(0)
    assertThat(line.words[0].start).isAtMost(line.words[1].start)
    assertThat(line.words[0].end).isAtMost(line.words[1].end)
  }

  // --- Testo -------------------------------------------------------------------------------------------------------------------

  @Test
  fun iCaratteriVietatiSiTolgonoEIlTestoEInNfc() {
    val decomposed = "e\u0301" // e + accento acuto combinante: in NFC diventa \u00E9
    val raw = straight(listOf("caff${decomposed}", "\u202Edirezione", "con\u0007trollo", "\u200Binvisibile", "Esselunga\u00AD"))
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.text).isEqualTo("caff\u00E9 direzione controllo invisibile Esselunga")
    assertThat(OcrText.isAllowed(line.text)).isTrue()
    assertThat(java.text.Normalizer.isNormalized(line.text, java.text.Normalizer.Form.NFC)).isTrue()
  }

  @Test
  fun unaParolaFattaSoloDiCaratteriVietatiSiButtaConISuoiEstremi() {
    val raw = straight(listOf("uno", "\u200E\u200F", "due"))
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.text).isEqualTo("uno due")
    assertThat(line.words).hasSize(2)
  }

  @Test
  fun unaRigaSenzaTestoUtileOSenzaGeometriaSiButtaGliAltriRestano() {
    val empty = RawLine("\u200B \u202A", quad(0f, 0f, 10f, 10f))
    val noGeometry = RawLine("senza riquadro")
    val good = straight(listOf("buona"))
    val result = OcrLayout.convert(listOf(empty, noGeometry, good), width, height)
    assertThat(result.lines.map { it.text }).containsExactly("buona")
    assertThat(result.textHeightsPx).hasSize(1)
  }

  @Test
  fun unElementoConGliSpaziDiventaPiuParole() {
    val raw = RawLine("a b", quad(0f, 0f, 100f, 10f), words = listOf(RawWord("alfa beta", quad(0f, 0f, 100f, 10f))))
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.text).isEqualTo("alfa beta")
    assertThat(line.words).hasSize(2)
    assertThat(line.words[0].end).isAtMost(line.words[1].start)
  }

  @Test
  fun senzaElementiLeParoleSonoQuelleDelTestoDellaRiga() {
    val raw = RawLine("L'Esselunga \u20AC 3,50", quad(10f, 10f, 210f, 40f))
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.text).isEqualTo("L'Esselunga \u20AC 3,50")
    assertThat(line.words).hasSize(3)
  }

  // --- Tetti ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaRigaDiMilleParoleSiTagliaAlleSessantaquattro() {
    val words = List(1000) { "p$it" }
    val raw = straight(words, perChar = 0.1f)
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    assertThat(line.words).hasSize(AiLimits.MAX_WORDS_PER_LINE)
    assertThat(line.text.toByteArray().size).isAtMost(AiLimits.MAX_LINE_BYTES)
  }

  @Test
  fun unaRigaLunghissimaDiByteSiTagliaAiMilleVentiquattro() {
    val words = List(40) { "x".repeat(60) }
    val line = OcrLayout.convert(listOf(straight(words, perChar = 0.2f)), width, height).lines.single()
    assertThat(line.text.toByteArray().size).isAtMost(AiLimits.MAX_LINE_BYTES)
    assertThat(line.words.size).isEqualTo(line.text.split(' ').size)
    assertThat(line.words.size).isAtLeast(15)
  }

  @Test
  fun lePiuDiQuattrocentoRigheSiTagliano() {
    val lines = List(1000) { straight(listOf("riga", "$it"), y = (it % 400).toFloat()) }
    val result = OcrLayout.convert(lines, width, height)
    assertThat(result.lines).hasSize(AiLimits.MAX_OCR_LINES)
    assertThat(result.textHeightsPx).hasSize(AiLimits.MAX_OCR_LINES)
  }

  @Test
  fun lettereMultibyteContanoPerICaratteriUtf8() {
    val word = "\u00E8".repeat(300) // 600 byte
    val line = OcrLayout.convert(listOf(straight(listOf(word, word, word), perChar = 0.1f)), width, height).lines.single()
    assertThat(line.text.toByteArray(Charsets.UTF_8).size).isAtMost(AiLimits.MAX_LINE_BYTES)
    assertThat(line.words).hasSize(1) // la seconda non ci sta
  }

  // --- Geometria ostile -------------------------------------------------------------------------------------------------------

  @Test
  fun riquadriFuoriDallImmagineENonFinitiNonRompono() {
    val outside = RawLine("fuori", quad(-500f, -500f, 5000f, 5000f))
    val nan = RawLine("nan", listOf(RawPoint(Float.NaN, 1f), RawPoint(2f, 3f), RawPoint(4f, 5f), RawPoint(6f, 7f)), RawBox(1f, 2f, 3f, 4f))
    val inf = RawLine("inf", box = RawBox(Float.NEGATIVE_INFINITY, 0f, Float.POSITIVE_INFINITY, 10f))
    val result = OcrLayout.convert(listOf(outside, nan, inf), width, height)
    for (line in result.lines) {
      assertThat(line.box.x0).isAtLeast(0)
      assertThat(line.box.x1).isAtMost(AiLimits.UNIT)
      assertThat(line.box.x0).isAtMost(line.box.x1)
    }
    assertThat(result.lines.map { it.text }).contains("fuori")
  }

  @Test
  fun ilRiquadroNonEMaiRovesciatoEdIEstremiNonDecrescono() {
    // Parole fuori ordine nella geometria (ML Kit puo' sbagliare): gli estremi si correggono, il formato lo pretende.
    val raw = RawLine(
      "a b c",
      quad(0f, 0f, 300f, 10f),
      words = listOf(RawWord("a", quad(200f, 0f, 220f, 10f)), RawWord("b", quad(100f, 0f, 120f, 10f)), RawWord("c", quad(0f, 0f, 20f, 10f))),
    )
    val line = OcrLayout.convert(listOf(raw), width, height).lines.single()
    line.words.zipWithNext().forEach { (a, b) ->
      assertThat(a.start).isAtMost(b.start)
      assertThat(a.end).isAtMost(b.end)
    }
    assertThat(line.words.all { it.start <= it.end }).isTrue()
  }

  @Test
  fun unaRigaSenzaNienteEUnaListaVuota() {
    val result = OcrLayout.convert(emptyList(), width, height)
    assertThat(result.lines).isEmpty() // "analizzata, nessun testo" e' un risultato
    assertThat(OcrDensity.isDense(result.textHeightsPx)).isFalse()
  }

  @Test(expected = IllegalArgumentException::class)
  fun immagineSenzaDimensioniEUnErroreDiProgrammazione() {
    OcrLayout.convert(emptyList(), 0, 100)
  }

  // --- Testo fitto -----------------------------------------------------------------------------------------------------------

  /** I valori misurati da P4 (docs/decisioni.md, "Fase 6 P4"): cambiarli e' una decisione, non un ritocco. */
  @Test
  fun laSogliaDelTestoFittoELaMisurata() {
    assertThat(OcrDensity.MIN_LINES).isEqualTo(6)
    assertThat(OcrDensity.MAX_MEDIAN_HEIGHT_PX).isEqualTo(16f)
  }

  @Test
  fun ilTestoFittoServeSeiRigheEUnaMedianaSottoLaSoglia() {
    val under = OcrDensity.MAX_MEDIAN_HEIGHT_PX - 1
    val over = OcrDensity.MAX_MEDIAN_HEIGHT_PX + 1
    assertThat(OcrDensity.isDense(List(OcrDensity.MIN_LINES) { under })).isTrue()
    assertThat(OcrDensity.isDense(List(OcrDensity.MIN_LINES - 1) { under })).isFalse() // troppo poche righe
    assertThat(OcrDensity.isDense(List(OcrDensity.MIN_LINES) { over })).isFalse()
    // La mediana, non la media: tre righe grosse e sei sottili => mediana sottile.
    assertThat(OcrDensity.isDense(List(3) { 60f } + List(6) { under })).isTrue()
    assertThat(OcrDensity.isDense(List(6) { 60f } + List(3) { under })).isFalse()
    // Mediana di un numero pari di righe: la media dei due centrali.
    assertThat(OcrDensity.isDense(listOf(5f, 5f, 5f, 10f, 40f, 40f))).isTrue() // (5+10)/2 sotto 16
    assertThat(OcrDensity.isDense(listOf(5f, 5f, 12f, 40f, 40f, 40f))).isFalse() // (12+40)/2 sopra
  }

  @Test
  fun leRigheConvertiteSonoSempreValidePerIlFormato() {
    val random = java.util.Random(11)
    repeat(2_000) {
      val n = 1 + random.nextInt(8)
      val words = List(n) { i -> RawWord("p${random.nextInt(99999)}\u0301".take(random.nextInt(1, 8)), quad(random.nextFloat() * 1200 - 100, random.nextFloat() * 600 - 50, random.nextFloat() * 1200 - 100, random.nextFloat() * 600 - 50)) }
      val raw = RawLine(words.joinToString(" ") { it.text }, quad(random.nextFloat() * 900, random.nextFloat() * 400, random.nextFloat() * 900, random.nextFloat() * 400), null, (random.nextFloat() - 0.5f) * 720f, words)
      val result = OcrLayout.convert(listOf(raw), width, height)
      for (line: OcrLine in result.lines) {
        assertThat(line.angle).isIn(-90..90)
        assertThat(line.words.size).isEqualTo(line.text.split(' ').size)
        line.words.zipWithNext().forEach { (a, b) ->
          assertThat(a.start).isAtMost(b.start)
          assertThat(a.end).isAtMost(b.end)
        }
      }
    }
  }
}
