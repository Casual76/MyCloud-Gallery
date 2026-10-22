package dev.pampa.mycloudgallery.core.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.cutout.Framing
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixture
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixtures
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import dev.pampa.mycloudgallery.core.ai.testing.SubjectCutterContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P13): MagicTouch vero sulle fixture sintetiche (`CutoutFixtures`) con la suite di contratto del ritagliatore
 * (`SubjectCutterContract`: le stesse soglie di `CutoutQuality`, fissate PRIMA di misurare, che passa anche il fake sulla JVM). Una riga
 * JSON per fixture nel log `P13Fix`; le immagini dei risultati (originale, maschera, ritaglio su scacchiera) in
 * `/sdcard/Android/data/<pacchetto di prova>/files/lab/`. Con `-e mcgLab true` non si confronta con le soglie (esplorazione).
 *
 * Le due versioni: `v1` dall'asset dell'APK; `v2` dal pacchetto copiato in `/data/local/tmp/mcg-models/` (senza, il test si salta).
 */
@RunWith(AndroidJUnit4::class)
class CutoutFixturesDeviceTest {

  private val args = InstrumentationRegistry.getArguments()
  private val lab = args.getString("mcgLab") == "true"
  private val raw = args.getString("mcgRaw") == "true"

  @Test
  fun v1SulleFixture() = measure("v1")

  @Test
  fun v2SulleFixture() {
    assumeTrue("Pacchetto v2 assente: adb push in /data/local/tmp/mcg-models/ (vedi CutoutDevices)", CutoutDevices.v2Available())
    measure("v2")
  }

  private fun measure(version: String) = runBlocking {
    val tuning = CutoutDevices.tuning()
    val cutter = CutoutDevices.cutter(version, tuning)
    val report = try {
      SubjectCutterContract.run(cutter) { fixture, result ->
        if (raw) runBlocking { logRaw(version, fixture) }
        log(version, fixture, result, cutter)
      }
    } finally {
      cutter.release()
    }
    Log.i(
      TAG,
      "{\"model\":\"$version\",\"summary\":true,\"iouMean\":${"%.3f".format(report.meanIou)},\"emptyRejected\":${"%.2f".format(report.emptyRejected)}," +
        "\"failures\":${report.failures},\"cfg\":\"${args.getString("mcgCfg") ?: ""}\"}",
    )
    report.problems().forEach { Log.i(TAG, "manca: $it") }
    if (!lab) assertEquals("Il contratto del ritagliatore", emptyList<String>(), report.problems())
  }

  private fun log(version: String, fixture: CutoutFixture, result: CutoutResult, cutter: dev.pampa.mycloudgallery.core.ai.cutout.MagicTouchCutter) {
    val json = StringBuilder("{\"model\":\"$version\",\"fixture\":\"${fixture.name}\",\"size\":\"${fixture.width}x${fixture.height}\"")
    var mask: dev.pampa.mycloudgallery.core.ai.contract.AlphaMask? = null
    when (result) {
      is CutoutResult.Subject -> {
        mask = result.mask
        val iou = fixture.truth?.let { CutoutQuality.iou(result.mask, it) }
        json.append(",\"result\":\"subject\",\"iou\":${iou?.let { "%.3f".format(it) } ?: "null"},\"coverage\":${"%.3f".format(result.mask.coverage)}")
      }
      CutoutResult.NoClearSubject -> json.append(",\"result\":\"none\"")
      is CutoutResult.Failed -> json.append(",\"result\":\"failed\",\"error\":\"${result.error}\"")
    }
    cutter.lastStats?.stats?.let {
      json.append(",\"cov\":${"%.3f".format(it.coverage)},\"conf\":${"%.3f".format(it.confidence)},\"contrast\":${"%.3f".format(it.contrast)}")
    }
    cutter.lastStats?.reason?.let { json.append(",\"reason\":\"$it\"") }
    cutter.lastTimings?.let {
      json.append(",\"frameMs\":${it.frameMs},\"modelMs\":${it.modelMs},\"maskMs\":${it.maskMs},\"totalMs\":${it.totalMs},\"openMs\":${it.openedMs}")
    }
    json.append("}")
    Log.i(TAG, json.toString())
    if (result !is CutoutResult.Failed) CutoutDevices.montage(fixture, mask, "$version-${fixture.name}")
  }

  /** La mappa grezza del modello su una fixture: minimo, massimo, media e il valore sul punto (per capire l'ingresso e l'uscita). */
  private suspend fun logRaw(version: String, fixture: CutoutFixture) {
    val tuning = CutoutDevices.tuning()
    val opener = if (version == "v2") CutoutDevices.v2Opener(tuning) else CutoutDevices.v1Opener(tuning)
    val session = opener.open()
    try {
      val letterbox = Framing.useLetterbox(fixture.width, fixture.height, tuning)
      val frame = Framing.frame(fixture.pixels(), fixture.width, fixture.height, session.side, letterbox)
      val values = session.run(frame, frame.frameX(fixture.point.x), frame.frameY(fixture.point.y), null)
      var lo = Float.MAX_VALUE
      var hi = -Float.MAX_VALUE
      var sum = 0.0
      var above = 0
      for (v in values) {
        if (v < lo) lo = v
        if (v > hi) hi = v
        sum += v
        if (v >= 0.5f) above++
      }
      val side = session.side
      val px = (frame.frameX(fixture.point.x) * side).toInt().coerceIn(0, side - 1)
      val py = (frame.frameY(fixture.point.y) * side).toInt().coerceIn(0, side - 1)
      Log.i(
        TAG,
        "{\"model\":\"$version\",\"raw\":\"${fixture.name}\",\"min\":${"%.3f".format(lo)},\"max\":${"%.3f".format(hi)},\"mean\":${"%.3f".format(sum / values.size)}," +
          "\"above\":${"%.3f".format(above.toFloat() / values.size)},\"atPoint\":${"%.3f".format(values[py * side + px])}}",
      )
    } finally {
      session.close()
    }
  }

  private companion object {
    const val TAG = "P13Fix"
  }
}
