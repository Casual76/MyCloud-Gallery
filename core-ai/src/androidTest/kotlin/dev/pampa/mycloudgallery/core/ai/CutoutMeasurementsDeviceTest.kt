package dev.pampa.mycloudgallery.core.ai

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutDecode
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutImages
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutRender
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixture
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixtures
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import dev.pampa.mycloudgallery.core.ai.testing.SubjectCutterContract
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P13): le MISURE di MagicTouch v1 e v2 sul dispositivo (docs/fase6-progetto.md §4.4 P13: latenza p50 e p95, PSS, IoU sulle
 * fixture). Una riga JSON per misura nel log `P13Measure`; il resoconto sta in docs/decisioni.md.
 *
 * Cosa si misura, per ogni versione:
 *  - apertura del modello a freddo (verifica esclusa: la fa `ModelFiles`) e a caldo, PSS prima, a modello caricato, dopo i ritagli e dopo
 *    il rilascio;
 *  - il ritaglio **sulle fixture** (immagini da 640x480): quadro + modello + maschera, p50 e p95 su [RUNS] giri a motore caldo;
 *  - il ritaglio **su una foto da 12 megapixel** (4096x3072, la taglia massima della sorgente, D44): gli stessi tre passi, piu' la
 *    decodifica del JPEG, la scrittura del PNG con e senza contorno e il tempo totale "pressione prolungata -> soggetto staccato";
 *  - la qualita': IoU medio e minimo sulle fixture, vuoti respinti (la stessa suite di contratto).
 *
 * Il modello v2 si prova solo se il pacchetto e' in `/data/local/tmp/mcg-models/` (vedi `CutoutDevices`).
 */
@RunWith(AndroidJUnit4::class)
class CutoutMeasurementsDeviceTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val runs get() = InstrumentationRegistry.getArguments().getString("mcgRuns")?.toIntOrNull() ?: RUNS

  @Test
  fun misureV1() = measure("v1")

  @Test
  fun misureV2() {
    assumeTrue("Pacchetto v2 assente (adb push in /data/local/tmp/mcg-models/)", CutoutDevices.v2Available())
    measure("v2")
  }

  private fun measure(version: String) = runBlocking {
    val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.first()}"
    emit(version, "device", "\"info\":\"$device\",\"cores\":${Runtime.getRuntime().availableProcessors()},\"threads\":${CutoutDevices.threads()}")
    val cutter = CutoutDevices.cutter(version)
    try {
      val pssBefore = Probes.pssMb()
      val fixtures = CutoutFixtures.withSubject
      val first = fixtures.first()

      // A freddo: la prima chiamata apre il modello.
      cutter.cut(first.image(), first.point)
      val open = cutter.lastTimings!!
      val pssLoaded = Probes.pssMb()
      emit(version, "open", "\"openMs\":${open.openedMs},\"firstCutMs\":${open.totalMs},\"pssBeforeMb\":${f(pssBefore)},\"pssLoadedMb\":${f(pssLoaded)}")

      // Riscaldamento: due giri su tutte le fixture (il JIT e le cache del processore), non contano.
      repeat(2) { for (fixture in fixtures) cutter.cut(fixture.image(), fixture.point) }

      // Sulle fixture: quadro + modello + maschera, a caldo.
      val frame = ArrayList<Long>()
      val model = ArrayList<Long>()
      val mask = ArrayList<Long>()
      val total = ArrayList<Long>()
      repeat(runs) {
        for (fixture in fixtures) {
          val image = fixture.image()
          cutter.cut(image, fixture.point)
          val t = cutter.lastTimings ?: continue
          frame += t.frameMs
          model += t.modelMs
          mask += t.maskMs
          total += t.totalMs
        }
      }
      emit(version, "latencyFixtures", "\"size\":\"640x480\",\"n\":${total.size},${percentiles("frame", frame)},${percentiles("model", model)},${percentiles("mask", mask)},${percentiles("total", total)}")
      val pssWorking = Probes.pssMb()

      // Su una foto da 12 MP (o quanto il budget di memoria del dispositivo permette: `ImagingBudget`).
      val big = bigScene(CutoutFixtures.named("casa"))
      var decoded: dev.pampa.mycloudgallery.core.ai.contract.CutoutImage? = (CutoutImages.decode(big.file, maxPixels = big.maxPixels) as CutoutDecode.Decoded).image
      val bigSize = "${decoded!!.width}x${decoded.height}"
      val bigFrame = ArrayList<Long>()
      val bigModel = ArrayList<Long>()
      val bigMask = ArrayList<Long>()
      val bigTotal = ArrayList<Long>()
      var bigIou = 0f
      repeat(BIG_WARMUP + runs.coerceAtMost(BIG_RUNS)) { n ->
        val result = cutter.cut(decoded!!, big.point)
        val t = cutter.lastTimings
        if (n >= BIG_WARMUP && t != null) {
          bigFrame += t.frameMs
          bigModel += t.modelMs
          bigMask += t.maskMs
          bigTotal += t.totalMs
        }
        if (result is CutoutResult.Subject) bigIou = big.iou(result.mask)
      }
      val pssBig = Probes.pssMb()
      decoded = null
      System.gc()
      emit(version, "latencyBig", "\"size\":\"$bigSize\",\"n\":${bigTotal.size},${percentiles("frame", bigFrame)},${percentiles("model", bigModel)},${percentiles("mask", bigMask)},${percentiles("total", bigTotal)},\"iou\":${f(bigIou)},\"pssMb\":${f(pssBig)}")

      // La pipeline intera su un JPEG da 12 MP: decodifica, ritaglio, PNG.
      val decodeMs = ArrayList<Long>()
      val pressMs = ArrayList<Long>()
      val pngPlainMs = ArrayList<Long>()
      val pngOutlineMs = ArrayList<Long>()
      var pngBytes = 0
      var outlineBytes = 0
      repeat(3 + 5) { n ->
        val t0 = System.nanoTime()
        val image = (CutoutImages.decode(big.file, maxPixels = big.maxPixels) as CutoutDecode.Decoded).image
        val t1 = System.nanoTime()
        val result = cutter.cut(image, big.point) as CutoutResult.Subject
        val t2 = System.nanoTime()
        val plain = CutoutRender.png(image, result.mask, outline = false)
        val t3 = System.nanoTime()
        val outlined = CutoutRender.png(image, result.mask, outline = true)
        val t4 = System.nanoTime()
        if (n >= 3) {
          decodeMs += (t1 - t0) / 1_000_000
          pressMs += (t2 - t0) / 1_000_000
          pngPlainMs += (t3 - t2) / 1_000_000
          pngOutlineMs += (t4 - t3) / 1_000_000
          pngBytes = plain.size
          outlineBytes = outlined.size
        }
      }
      emit(version, "pipeline", "\"size\":\"$bigSize\",${percentiles("decode", decodeMs)},${percentiles("press", pressMs)},${percentiles("pngPlain", pngPlainMs)},${percentiles("pngOutline", pngOutlineMs)},\"pngBytes\":$pngBytes,\"pngOutlineBytes\":$outlineBytes")
      big.file.delete()

      // La qualita' sulle fixture (la suite di contratto).
      val report = SubjectCutterContract.run(cutter)
      val ious = report.lines.filter { it.hasSubject }.map { it.iou ?: 0f }
      emit(version, "quality", "\"iouMean\":${f(report.meanIou)},\"iouMin\":${f(ious.minOrNull() ?: 0f)},\"emptyRejected\":${f(report.emptyRejected)},\"problems\":${report.problems().size}")

      cutter.release()
      System.gc()
      Thread.sleep(500)
      val pssAfter = Probes.pssMb()
      emit(version, "pss", "\"beforeMb\":${f(pssBefore)},\"loadedMb\":${f(pssLoaded)},\"workingMb\":${f(pssWorking)},\"bigMb\":${f(pssBig)},\"afterReleaseMb\":${f(pssAfter)}")

      // Riapertura a caldo (il modello e' gia' nella cache di sistema).
      val reopen = CutoutDevices.cutter(version)
      reopen.cut(first.image(), first.point)
      emit(version, "reopen", "\"openMs\":${reopen.lastTimings!!.openedMs}")
      reopen.release()
    } finally {
      cutter.release()
    }
  }

  /** Una fixture portata alla taglia massima della sorgente (4096x3072, o meno col budget del dispositivo), come file JPEG. */
  private inner class Big(val file: File, val maxPixels: Long, private val truth: BooleanArray, private val fixture: CutoutFixture) {
    val point: NormalizedPoint = fixture.point

    /** L'IoU della maschera grande con la verita' della fixture (la maschera si riporta alla taglia della fixture, punto piu' vicino). */
    fun iou(mask: dev.pampa.mycloudgallery.core.ai.contract.AlphaMask): Float {
      val alpha = mask.raw()
      val small = ByteArray(fixture.width * fixture.height) { i ->
        alpha[(i / fixture.width * mask.height / fixture.height) * mask.width + (i % fixture.width) * mask.width / fixture.width]
      }
      return CutoutQuality.iou(dev.pampa.mycloudgallery.core.ai.contract.AlphaMask(fixture.width, fixture.height, small), truth)
    }
  }

  /** Il budget di pixel del viewer di questo dispositivo: la formula di `ImagingBudget` (un quinto di `memoryClass`, fra 24 e 64 MB). */
  private fun budgetPixels(): Long {
    val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val bytes = (manager.memoryClass * 1024L * 1024L / 5).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
    return bytes / 4
  }

  private fun bigScene(fixture: CutoutFixture): Big {
    val maxPixels = budgetPixels()
    val (w, h) = checkNotNull(CutoutImages.targetSize(4096, 3072, maxPixels = maxPixels))
    val file = File(context.cacheDir, "p13-big.jpg")
    val small = Bitmap.createBitmap(fixture.pixels(), fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
    val scaled = Bitmap.createScaledBitmap(small, w, h, true)
    file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    scaled.recycle()
    small.recycle()
    return Big(file, maxPixels, fixture.truth!!, fixture)
  }

  private fun percentiles(name: String, values: List<Long>): String {
    if (values.isEmpty()) return "\"${name}P50\":null,\"${name}P95\":null"
    val sorted = values.sorted()
    fun at(p: Double) = sorted[((sorted.size - 1) * p).let { Math.ceil(it).toInt() }.coerceIn(0, sorted.size - 1)]
    return "\"${name}P50\":${at(0.5)},\"${name}P95\":${at(0.95)}"
  }

  private fun f(v: Float) = "%.3f".format(v)

  private fun f(v: Double) = "%.1f".format(v)

  private fun emit(version: String, kind: String, body: String) {
    Log.i(TAG, "{\"model\":\"$version\",\"kind\":\"$kind\",$body}")
  }

  private companion object {
    const val TAG = "P13Measure"
    const val RUNS = 3
    const val BIG_RUNS = 20
    const val BIG_WARMUP = 2
  }
}
