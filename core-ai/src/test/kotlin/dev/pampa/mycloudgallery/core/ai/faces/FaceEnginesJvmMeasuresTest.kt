package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import java.io.File
import org.junit.Test

/**
 * Fase 8 (P2): le misure JVM delle parti di codice nostro dei motori dei volti (docs/decisioni.md, "Fase 8 P2"): preparazione del quadro, decodifica
 * e NMS, allineamento, nitidezza, ingresso e vettore di SFace. **Non** sono i tempi del telefono (la JVM di un PC condiviso con altri agenti): sono il
 * costo del codice che non e' il modello, per sapere che non domina (il modello su CPU costa decine di millisecondi) e per accorgersi di una
 * regressione. Nessuna soglia sul tempo (un PC carico non deve far cadere un test): si scrivono in `build/p2-misure-jvm.txt` e si controlla solo che
 * ogni parte giri e dia risultati sensati. Le misure del dispositivo sono di `FaceMeasurementsDeviceTest` (androidTest).
 */
class FaceEnginesJvmMeasuresTest {

  private class Stat(val name: String, val samples: List<Double>) {
    val p50: Double = percentileOf(samples, 0.50)
    val p95: Double = percentileOf(samples, 0.95)

    override fun toString(): String = "%s: p50 %.2f ms, p95 %.2f ms (%d giri)".format(java.util.Locale.ROOT, name, p50, p95, samples.size)
  }

  private inline fun measure(name: String, warmup: Int = 8, runs: Int = 40, block: () -> Unit): Stat {
    repeat(warmup) { block() }
    val samples = ArrayList<Double>(runs)
    repeat(runs) {
      val t0 = System.nanoTime()
      block()
      samples += (System.nanoTime() - t0) / 1e6
    }
    return Stat(name, samples)
  }

  @Test
  fun leMisureDelleParti() {
    val scene = PixelScene(1024, 768, listOf(PixelFace(1, 100, 100, 160, 190), PixelFace(2, 500, 200, 120, 140)))
    val image = PixelFaceWorld.render(scene)
    val pixels = image.raw()
    val big = PixelFaceWorld.render(PixelScene(2048, 1536, scene.faces))
    val bigPixels = big.raw()
    val builder = FrameBuilder()
    val results = ArrayList<Stat>()

    results += measure("quadro 1024 da un'anteprima 1024x768") { builder.build(pixels, 1024, FaceRegion(0, 0, 1024, 768)) }
    results += measure("quadro 1024 da un HQ 2048x1536 intero (riduzione a meta')") { builder.build(bigPixels, 2048, FaceRegion(0, 0, 2048, 1536)) }
    results += measure("quadro 1024 da un tassello 1088x832 dell'HQ") { builder.build(bigPixels, 2048, FaceRegion(960, 704, 1088, 832)) }

    // Decodifica e NMS su uscite vere nella forma (21.504 celle) con i candidati di una foto di gruppo.
    val outputs = FaceFixtures.tensorScenes().first { it.name == "composizione-b-img-002" }.outputs()
    var found = 0
    results += measure("decodifica + NMS (21.504 celle, 8 volti)") { found = YuNetDecoder.decode(outputs, 0.6f, 0.3f).size }
    assertThat(found).isEqualTo(8)
    val empty = YuNetOutputs.allocate()
    results += measure("decodifica + NMS (21.504 celle vuote)") { YuNetDecoder.decode(empty, 0.6f, 0.3f) }

    val landmarks = TensorSynth.faceLandmarks(floatArrayOf(100f, 100f, 160f, 190f))
    var aligned: AlignedFace? = null
    results += measure("allineamento 112x112 (bilineare)") { aligned = FaceAligner.align(image, landmarks) }
    results += measure("nitidezza (laplaciano) del volto allineato") { FaceQuality.sharpnessOf(aligned!!) }
    val verdictFace = dev.pampa.mycloudgallery.core.ai.contract.DetectedFace(dev.pampa.mycloudgallery.core.ai.contract.FloatBox(100f, 100f, 260f, 290f), landmarks, 0.95f)
    results += measure("giudizio di qualita' (con nitidezza)") { FaceQuality.assess(verdictFace, 160, aligned) }

    val input = FloatArray(FaceVectors.INPUT_VALUES)
    results += measure("ingresso di SFace (RGB float, 112x112x3)") { FaceVectors.input(aligned!!, input) }
    val raw = FloatArray(128) { 1.05f + (it % 7) * 0.01f }
    results += measure("normalizzazione del vettore (128)") { FaceVectors.normalizedOrNull(raw) }

    val report = results.joinToString("\n") + "\n"
    File("build").mkdirs()
    File("build/p2-misure-jvm.txt").writeText(report)
    println(report)
    assertThat(results.all { it.p50 >= 0.0 && it.p95 >= it.p50 }).isTrue()
  }
}

private fun percentileOf(samples: List<Double>, q: Double): Double {
  val sorted = samples.sorted()
  return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
}
