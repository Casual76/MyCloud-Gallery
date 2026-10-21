package dev.pampa.mycloudgallery.core.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.ocr.DataTransportCleanup
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitBootstrap
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.OcrText
import java.text.Normalizer
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, D12, D13, D43; "Fatto quando": OCR sulle fixture sintetiche, errore per carattere a 10-40 px a 1024 e a 2048, soglia
 * del testo fitto; OCR con righe, inclinazione ed estremi delle parole). ML Kit Text Recognition v2 (latino, integrato) VERO sulle
 * fixture di `OcrFixtures`, dietro lo stesso cancello dell'app: il lettore si avvia solo perche' qui la lettura e' "attiva".
 *
 * Ogni test finisce con la pulizia dello spegnimento (`TextReadingShutdown`), come farebbe l'app: lavori di DataTransport annullati e
 * coda tolta, cosi' i test successivi (e `TextReadingOffDeviceTest`) partono da zero.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val active = TextReadingState.OnForAccount(thisDevice = true)
  private val diags = mutableListOf<DiagCode>()
  private val bootstrap = MlKitBootstrap(context) { active }
  private val reader = MlKitTextReader(bootstrap) { diags += it }

  @After
  fun spegnimento() {
    val shutdown = TextReadingShutdown(DataTransportCleanup(context), { reader.release() }, { bootstrap.isInitialized })
    shutdown.onTurnedOff()
    shutdown.atStartup(TextReadingState.Off)
  }

  // --- Errore per carattere, soglia del testo fitto -----------------------------------------------------------------------------------

  private class Cell(val resolution: Int, val fontPx: Int, val cer: Double, val lines: Int, val medianLineHeightPx: Double, val dense: Boolean, val readMs: Long)

  private fun measure(resolution: Int, fontPx: Int): Cell = runBlocking {
    val width = resolution
    val height = resolution * 3 / 4
    val page = OcrFixtures.page(width, height, fontPx.toFloat())
    try {
      val image = if (resolution <= 1024) {
        ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)
      } else {
        ModelInputs.forOcr(OcrFixtures.webp90(page.bitmap), OcrSource.HQ)
      }!!
      assertEquals(width, image.width)
      val start = System.nanoTime()
      val result = reader.read(image)
      val ms = (System.nanoTime() - start) / 1_000_000
      val read = result.lines.joinToString(" ") { it.text }
      val heights = result.lines.map { (it.box.y1 - it.box.y0) * height / 65535.0 }.sorted()
      Cell(resolution, fontPx, OcrFixtures.characterErrorRate(page.lines.joinToString(" "), read), result.lines.size, heights.getOrElse(heights.size / 2) { 0.0 }, result.dense, ms)
    } finally {
      page.bitmap.recycle()
    }
  }

  @Test
  fun erroreDelCarattereAAltezzeEdEdueRisoluzioni() {
    val sizes = listOf(8, 10, 12, 14, 16, 20, 24, 28, 32, 40)
    val grid = mutableListOf<Cell>()
    for (resolution in listOf(1024, 2048)) for (px in sizes) grid += measure(resolution, px)
    // Il pezzo di tabella che serve a fissare la soglia: la stessa pagina letta a 1024 con il carattere alto h e a 2048 con 2h.
    for (cell in grid) {
      Log.i(
        TAG,
        "CER ${cell.resolution}px font ${cell.fontPx}px: ${"%.1f".format(cell.cer * 100)}% righe ${cell.lines} altezza mediana ${"%.1f".format(cell.medianLineHeightPx)}px dense=${cell.dense} ${cell.readMs} ms",
      )
    }
    fun cer(resolution: Int, px: Int) = grid.first { it.resolution == resolution && it.fontPx == px }.cer
    // Misurato il 21/10/2026 (emulatore x86_64, ML Kit 16.0.1): dal carattere da 10 px in su l'errore sulle fixture pulite sta sotto il
    // 3% a entrambe le risoluzioni; a 8 px il 1024 non legge niente e il 2048 sbaglia circa un carattere su cinque. Sono i numeri su cui
    // poggia la soglia del testo fitto (OcrDensity): se una versione nuova di ML Kit li sposta, il test lo dice e la soglia si rimisura.
    for (resolution in listOf(1024, 2048)) for (px in sizes.filter { it >= 10 }) {
      assertTrue("CER ${cer(resolution, px)} a $resolution con il carattere da $px px", cer(resolution, px) <= 0.06)
    }
    assertTrue("a 8 px il 2048 deve ancora leggere in buona parte: ${cer(2048, 8)}", cer(2048, 8) <= 0.40)
    // La seconda passata serve: lo stesso testo, letto a 2048 con il carattere doppio, e' piu' sicuro di quello letto a 1024.
    for (px in listOf(10, 12, 14)) {
      assertTrue(
        "a 2048 (carattere ${2 * px}) l'errore ${cer(2048, 2 * px)} non migliora quello a 1024 (carattere $px) ${cer(1024, px)}",
        cer(2048, 2 * px) <= cer(1024, px) + 0.02,
      )
    }
    // Sotto la soglia di leggibilita' del 1024 la prima passata non vede righe: nessuna riga non e' "fitto" (limite noto, docs/decisioni.md).
    val tiny = grid.first { it.resolution == 1024 && it.fontPx == 8 }
    Log.i(TAG, "limite noto: a 8 px il 1024 vede ${tiny.lines} righe (dense=${tiny.dense}); il 2048 ne vede ${grid.first { it.resolution == 2048 && it.fontPx == 8 }.lines}")
  }

  @Test
  fun ilTestoFittoSiRiconosceDallAltezzaMediana() {
    // Tante righe piccole sotto la soglia: fitto. Poche righe grandi: no. Poche righe piccole: no (servono almeno MIN_LINES).
    val dense = measure(1024, 10)
    val sparseBig = measure(1024, 32)
    Log.i(TAG, "fitto: righe ${dense.lines} altezza ${dense.medianLineHeightPx} dense=${dense.dense}; grande: righe ${sparseBig.lines} altezza ${sparseBig.medianLineHeightPx} dense=${sparseBig.dense}")
    assertTrue("righe lette ${dense.lines}", dense.lines >= 6)
    assertTrue("a 10 px il testo e' fitto", dense.dense)
    assertFalse("a 32 px no", sparseBig.dense)
    val fewSmall = runBlocking {
      val page = OcrFixtures.page(1024, 768, 10f, maxLines = 4)
      val image = ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)!!
      reader.read(image).also { page.bitmap.recycle() }
    }
    assertTrue(fewSmall.lines.size in 1..5)
    assertFalse("meno di sei righe: mai fitto", fewSmall.dense)
  }

  // --- Cosa esce -------------------------------------------------------------------------------------------------------------------

  @Test
  fun leRigheSonoPulitePerIlFormatoEIlCodecLeAccetta() = runBlocking<Unit> {
    val page = OcrFixtures.page(1024, 768, 28f, maxLines = 10)
    try {
      val result = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)!!)
      assertTrue(result.lines.size >= 8)
      for (line in result.lines) {
        assertTrue(OcrText.isAllowed(line.text))
        assertTrue(Normalizer.isNormalized(line.text, Normalizer.Form.NFC))
        assertEquals(line.words.size, line.text.split(' ').size)
        assertTrue(line.text.split(' ').none { it.isEmpty() })
        assertTrue(line.angle in -90..90)
        line.words.zipWithNext().forEach { (a, b) ->
          assertTrue(a.start <= b.start && a.end <= b.end)
        }
      }
      // Gli accenti, l'euro e le cifre arrivano interi.
      val all = result.lines.joinToString("\n") { it.text }
      assertTrue("accenti nel testo letto: $all", all.contains("è") || all.contains("È"))
      assertTrue("euro nel testo letto: $all", all.contains("€"))
      // Il corpo `ocr` che ne esce e' accettato dal codec di chi legge, e si rilegge uguale.
      val body = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS, result.lines)
      assertTrue(AiSegments.admits(body))
      val again = AiSegments.decodeBody(AiClass.OCR, AiSegments.encodeBody(body)).acceptedOrNull()
      assertNotNull("il corpo che esce deve rileggersi", again)
      assertEquals(body, again)
    } finally {
      page.bitmap.recycle()
    }
  }

  @Test
  fun nessunTestoEUnRisultatoNonUnErrore() = runBlocking<Unit> {
    val blank = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
    try {
      blank.eraseColor(Color.rgb(200, 220, 240))
      val result = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(blank), OcrSource.ANALYSIS)!!)
      assertTrue(result.lines.isEmpty())
      assertFalse(result.dense)
    } finally {
      blank.recycle()
    }
  }

  @Test
  fun lAnteprimaELHqDannoLeStessePosizioni() = runBlocking<Unit> {
    // La stessa pagina a 1024 (JPEG) e a 2048 (WebP): le righe stanno negli stessi punti, in frazioni dell'immagine (entro il 2%).
    val small = OcrFixtures.page(1024, 768, 28f, maxLines = 8)
    val large = OcrFixtures.page(2048, 1536, 56f, maxLines = 8)
    try {
      val a = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(small.bitmap), OcrSource.ANALYSIS)!!)
      val b = reader.read(ModelInputs.forOcr(OcrFixtures.webp90(large.bitmap), OcrSource.HQ)!!)
      assertTrue("righe lette: ${a.lines.size} contro ${b.lines.size}", abs(a.lines.size - b.lines.size) <= 1 && a.lines.size >= 6)
      val tolerance = 0.02 * 65535
      var matched = 0
      for (line in a.lines) {
        val other = b.lines.minByOrNull { abs(it.box.y0 - line.box.y0) } ?: continue
        if (abs(line.box.y0 - other.box.y0) < tolerance && abs(line.box.x0 - other.box.x0) < tolerance && abs(line.box.x1 - other.box.x1) < tolerance) matched++
      }
      assertTrue("righe nelle stesse posizioni: $matched su ${a.lines.size}", matched >= a.lines.size - 1)
    } finally {
      small.bitmap.recycle()
      large.bitmap.recycle()
    }
  }

  // --- Inclinazione ed estremi delle parole ----------------------------------------------------------------------------------------

  private class Drawn(val text: String, val angle: Float, val cx: Float, val cy: Float, val font: Float, val bitmap: Bitmap, val wordFractions: List<Pair<Float, Float>>, val lineLengthPx: Float)

  /** Una riga disegnata al centro, ruotata di [angle] gradi (positivi in senso orario sullo schermo), con le posizioni attese delle parole. */
  private fun drawLine(text: String, angle: Float, font: Float = 40f, width: Int = 1024, height: Int = 768): Drawn {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      typeface = Typeface.SANS_SERIF
      textSize = font
    }
    val length = paint.measureText(text)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.rotate(angle, width / 2f, height / 2f)
    canvas.drawText(text, width / 2f - length / 2, height / 2f + font / 3, paint)
    canvas.restore()
    // Le parole: dove iniziano e finiscono lungo la riga, in frazioni della lunghezza della riga.
    val fractions = mutableListOf<Pair<Float, Float>>()
    var offset = 0
    for (word in text.split(' ')) {
      val start = paint.measureText(text.substring(0, offset)) / length
      val end = paint.measureText(text.substring(0, offset + word.length)) / length
      fractions += start to end
      offset += word.length + 1
    }
    return Drawn(text, angle, width / 2f, height / 2f, font, bitmap, fractions, length)
  }

  @Test
  fun lInclinazioneDellaRigaEQuellaDisegnata() = runBlocking<Unit> {
    for (angle in listOf(-20f, -8f, 0f, 8f, 20f)) {
      val drawn = drawLine("Totale complessivo da pagare oggi", angle)
      try {
        val result = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(drawn.bitmap), OcrSource.ANALYSIS)!!)
        assertEquals("righe a $angle gradi: ${result.lines.map { it.text }}", 1, result.lines.size)
        val line = result.lines.single()
        Log.i(TAG, "inclinazione disegnata $angle, letta ${line.angle}, testo '${line.text}'")
        assertTrue("angolo letto ${line.angle} per quello disegnato $angle", abs(line.angle - angle) <= 3f)
      } finally {
        drawn.bitmap.recycle()
      }
    }
  }

  @Test
  fun gliEstremiDelleParoleSonoDovePaiono() = runBlocking<Unit> {
    for (angle in listOf(0f, 12f, -12f)) {
      val drawn = drawLine("Totale complessivo da pagare oggi", angle)
      try {
        val result = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(drawn.bitmap), OcrSource.ANALYSIS)!!)
        val line = result.lines.single()
        assertEquals(drawn.wordFractions.size, line.words.size)
        // §5.2: le posizioni coincidono con la fixture entro il 2% della dimensione dell'immagine.
        val tolerancePx = 0.02f * 1024
        for (i in line.words.indices) {
          val startPx = abs(line.words[i].start / 65535f - drawn.wordFractions[i].first) * drawn.lineLengthPx
          val endPx = abs(line.words[i].end / 65535f - drawn.wordFractions[i].second) * drawn.lineLengthPx
          assertTrue("parola $i a $angle gradi: inizio fuori di $startPx px", startPx <= tolerancePx)
          assertTrue("parola $i a $angle gradi: fine fuori di $endPx px", endPx <= tolerancePx)
        }
        // Il riquadro contiene il centro della riga disegnata.
        val cx = drawn.cx / 1024f * 65535
        val cy = drawn.cy / 768f * 65535
        assertTrue(line.box.x0 <= cx && cx <= line.box.x1)
        assertTrue(line.box.y0 <= cy + 0.05 * 65535 && cy - 0.05 * 65535 <= line.box.y1)
      } finally {
        drawn.bitmap.recycle()
      }
    }
  }

  // --- Il cancello e gli errori -------------------------------------------------------------------------------------------------------

  @Test
  fun daSpentoIlLettoreNonToccaMlKit() = runBlocking<Unit> {
    var state: TextReadingState = TextReadingState.Off
    val gate = MlKitBootstrap(context) { state }
    val startedBefore = gate.isInitialized // e' per processo: i test di prima possono averla gia' avviata
    val gated = GatedTextReader({ state }) { MlKitTextReader(gate) }
    val image = ModelInputs.forOcr(OcrFixtures.jpeg90(OcrFixtures.page(1024, 768, 28f, maxLines = 4).bitmap), OcrSource.ANALYSIS)!!
    val thrown = runCatching { gated.read(image) }.exceptionOrNull()
    assertEquals(AiEngineError.TEXT_READING_OFF, (thrown as AiEngineException).error)
    assertFalse(gated.isEngineCreated)
    assertEquals("da spento la libreria non si avvia", startedBefore, gate.isInitialized)
    // Acceso: legge. Spento di nuovo: non legge piu', anche con il motore gia' creato.
    state = active
    assertTrue(gated.read(image).lines.isNotEmpty())
    assertTrue(gated.isEngineCreated && gate.isInitialized)
    state = TextReadingState.Disabling
    val again = runCatching { gated.read(image) }.exceptionOrNull()
    assertEquals(AiEngineError.TEXT_READING_OFF, (again as AiEngineException).error)
    gated.release()
    assertFalse(gated.isEngineCreated)
  }

  @Test
  fun unaRigaRumorosaOLetteraSolaNonRompeIlFormato() = runBlocking<Unit> {
    val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
    try {
      bitmap.eraseColor(Color.WHITE)
      val canvas = Canvas(bitmap)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f; typeface = Typeface.SANS_SERIF }
      canvas.drawText("A", 100f, 150f, paint)
      canvas.drawText("€", 300f, 150f, paint)
      canvas.drawText("12345", 100f, 300f, paint)
      canvas.drawText("@#%&*", 100f, 450f, paint)
      val result: OcrResult = reader.read(ModelInputs.forOcr(OcrFixtures.jpeg90(bitmap), OcrSource.ANALYSIS)!!)
      // Qualunque cosa legga, ogni riga e' valida per il formato.
      result.lines.forEach { line: OcrLine -> assertTrue(OcrText.isAllowed(line.text)); assertTrue(line.words.isNotEmpty()) }
      assertTrue(AiSegments.admits(OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS, result.lines)))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun unaLetturaCancellataNonLasciaIlLettoreRotto() = runBlocking<Unit> {
    val page = OcrFixtures.page(1024, 768, 28f, maxLines = 4)
    try {
      val image = ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)!!
      val job = CoroutineScope(Dispatchers.Default).launch { reader.read(image) }
      job.cancel()
      job.join()
      assertTrue(reader.read(image).lines.isNotEmpty())
    } finally {
      page.bitmap.recycle()
    }
  }

  private companion object {
    const val TAG = "P4Ocr"
  }
}
