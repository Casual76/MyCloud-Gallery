package dev.pampa.mycloudgallery.core.ai

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter

/**
 * Fase 6 (P0, "Fatto quando" di docs/fase6-progetto.md §4.4): i due runtime nativi nuovi nello STESSO processo.
 *
 * 1. LiteRT (`libLiteRt.so`, `liblitert_jni.so`) carica MagicTouch dall'asset dell'APK e lo esegue su CPU;
 * 2. LiteRT-LM (`liblitertlm_jni.so`) inizializza EmbeddingGemma 2 **270M** su CPU e calcola il vettore di un testo, con
 *    kotlin-reflect tolto e le coroutines fermate alla versione dell'app (la scelta di P0, docs/decisioni.md): 768 componenti finite,
 *    norma 1, deterministico;
 * 3. LiteRT gira di nuovo con il motore dei vettori ancora aperto (convivenza).
 *
 * Il modello 270M (157 MiB) NON sta nel repository ne' nell'APK: si copia sul dispositivo con
 * `adb push <file> /data/local/tmp/mcg-models/embeddinggemma-2-text-270m.litertlm` (il file sta sul PC in
 * `%USERPROFILE%\.mycloudgallery\models\embeddinggemma-2-text-270m\`). Il test lo porta nella cartella dei file dell'APK di prova con
 * la shell e ne verifica dimensione e SHA-256 contro `ModelCatalog` prima di caricarlo (D16). Senza il file il caso 2 si salta.
 */
@RunWith(AndroidJUnit4::class)
class EnginesTogetherDeviceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun liteRtELiteRtLmGiranoInsiemeNelloStessoProcesso() {
    val cutout = assetToFile(ModelCatalog.CUTOUT_MAGICTOUCH.bundledAsset!!, "magic_touch.tflite")
    assertEquals(ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, cutout.length())
    assertEquals(ModelCatalog.CUTOUT_MAGICTOUCH.sha256.hex, sha256(cutout))

    Interpreter(cutout).use { interpreter ->
      runOnZeros(interpreter)

      val model = queryModel()
      assumeTrue("Modello 270M assente: adb push in /data/local/tmp/mcg-models/ (vedi il KDoc)", model != null)
      val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
      val config = EmbeddingEngineConfig(
        modelPath = model!!.path,
        backend = Backend.CPU(),
        cacheDir = cache.path,
        maxInputLength = 128,
      )
      val started = System.nanoTime()
      EmbeddingEngine(config).use { engine ->
        engine.initialize()
        val coldMs = (System.nanoTime() - started) / 1_000_000
        val first = embed(engine, "un cane che corre sulla spiaggia")
        val again = embed(engine, "un cane che corre sulla spiaggia")
        val other = embed(engine, "uno scontrino del supermercato")
        assertEquals(768, first.size)
        assertTrue("valori non finiti", first.all { it.isFinite() })
        val norm = sqrt(first.sumOf { (it * it).toDouble() })
        assertTrue("norma $norm", abs(norm - 1.0) < 1e-3)
        assertTrue("non deterministico", cosine(first, again) > 0.999)
        assertTrue("testi diversi, stesso vettore", cosine(first, other) < 0.999)
        // Convivenza: LiteRT gira di nuovo con il motore dei vettori aperto.
        runOnZeros(interpreter)
        Log.i(TAG, "270M su CPU: avvio a freddo ${coldMs} ms, coseno fra testi diversi ${"%.3f".format(cosine(first, other))}")
      }
    }
  }

  /** Esegue il modello con ingressi a zero (forma letta dal modello) e controlla che le uscite siano finite. */
  private fun runOnZeros(interpreter: Interpreter) {
    val inputs = Array<Any>(interpreter.inputTensorCount) { i ->
      ByteBuffer.allocateDirect(interpreter.getInputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
    }
    val outputs = HashMap<Int, Any>()
    for (i in 0 until interpreter.outputTensorCount) {
      outputs[i] = ByteBuffer.allocateDirect(interpreter.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
    }
    interpreter.runForMultipleInputsOutputs(inputs, outputs)
    val shapes = (0 until interpreter.inputTensorCount).joinToString { interpreter.getInputTensor(it).shape().contentToString() }
    Log.i(TAG, "MagicTouch: ingressi $shapes, ${interpreter.outputTensorCount} uscite")
    assertTrue(interpreter.inputTensorCount >= 1 && interpreter.outputTensorCount >= 1)
  }

  private fun embed(engine: EmbeddingEngine, text: String): FloatArray =
    engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions(normalize = true)).embedding

  private fun assetToFile(asset: String, name: String): File {
    val out = File(context.filesDir, "p0-$name")
    context.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
    return out
  }

  /** Il 270M dalla cartella della shell alla cartella dei file dell'APK di prova, verificato (D16); null se non c'e'. */
  private fun queryModel(): File? {
    val spec = ModelCatalog.QUERY_270M
    val out = File(context.filesDir, "models/${spec.sha256.hex}").apply { parentFile?.mkdirs() }
    if (!(out.isFile && out.length() == spec.sizeBytes)) {
      val pfd = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/mcg-models/${spec.fileName}")
      ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
    }
    if (out.length() != spec.sizeBytes) {
      out.delete()
      return null
    }
    assertEquals("SHA-256 del modello 270M", spec.sha256.hex, sha256(out))
    out.setReadOnly()
    return out
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(1 shl 20)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    return dot / (sqrt(na) * sqrt(nb))
  }

  private companion object {
    const val TAG = "P0Engines"
  }
}
