package dev.pampa.mycloudgallery.core.ai

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.ai.ocr.DataTransportCleanup
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitBootstrap
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmImageEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmSessions
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.ModelAccess
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.ai.runtime.OpenTimings
import dev.pampa.mycloudgallery.core.ai.runtime.ProbeImageSet
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le MISURE dei motori (Fase 6, P4; docs/fase6-progetto.md D3, D12, D15, F52): foto al secondo, millisecondi di OCR, PSS, avvio a
 * freddo e a caldo, verifica dell'impronta. Non asserisce numeri (dipendono dal dispositivo): li scrive nel log `P4Measure` (una riga
 * JSON per misura) e in `files/p4-measures.jsonl`, e il resoconto di P4 li riporta in `docs/decisioni.md` con il dispositivo e la data.
 * Le soglie vere stanno in `check.ps1 -Bench` (P9) e nelle regole di attivita'.
 *
 * "Avvio a freddo" = nessuna cache dei programmi compilati del motore (cartella `cache/<sha256>-<backend>` tolta); "a caldo" = la
 * stessa dopo un rilascio, con la cache piena e il file del modello gia' nella cache del sistema.
 */
@RunWith(AndroidJUnit4::class)
class EngineMeasurementsDeviceTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun record(name: String, build: JSONObject.() -> Unit) {
    val json = JSONObject().put("misura", name).put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
    json.build()
    Log.i(TAG, json.toString())
    File(context.filesDir, "p4-measures.jsonl").appendText(json.toString() + "\n")
  }

  private fun percentile(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

  private fun ms(nanos: Long): Double = nanos / 1e6

  // --- Verifica dell'impronta -----------------------------------------------------------------------------------------------------

  @Test
  fun verificaDellImprontaDiOgniApertura() = runBlocking<Unit> {
    for (spec in listOf(TestModels.query270m, TestModels.index440m)) {
      if (!TestModels.available(spec)) continue
      val times = (1..5).map {
        val start = System.nanoTime()
        val model = TestModels.files.open(spec.id)
        val elapsed = System.nanoTime() - start
        checkNotNull(model) { "il modello ${spec.id} non passa la verifica" }.close()
        ms(elapsed)
      }.sorted()
      record("verifica_sha256_${spec.id}") {
        put("dimensione_mib", spec.sizeBytes / 1048576.0)
        put("ms_p50", percentile(times, 0.5))
        put("ms_max", times.last())
        put("ms_min", times.first())
        put("mib_al_secondo", spec.sizeBytes / 1048576.0 / (percentile(times, 0.5) / 1000.0))
      }
    }
  }

  // --- 270M su CPU (le query) ----------------------------------------------------------------------------------------------------

  @Test
  fun motoreDelleQuery270SuCpu() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    for (access in ModelAccess.entries) measureQuery(access)
  }

  private suspend fun measureQuery(access: ModelAccess) {
    val spec = TestModels.query270m
    val cacheDir = File(TestModels.cacheRoot, "${spec.sha256.hex}-cpu")
    cacheDir.deleteRecursively()
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot, access = access)
    val pssBefore = Probes.pssMb()
    val cold = (embedder.warmUp() as WarmUpResult.Ready).coldStartMs
    val pssLoaded = Probes.pssMb()
    embedder.release()
    val pssReleased = Probes.pssMb()
    val warm = (embedder.warmUp() as WarmUpResult.Ready).coldStartMs
    val latencies = (1..50).map { i ->
      val start = System.nanoTime()
      embedder.embed("una foto numero $i di un cane sulla spiaggia al tramonto")
      ms(System.nanoTime() - start)
    }.sorted()
    embedder.release()
    record("query_270m_cpu_${access.name.lowercase()}") {
      put("avvio_a_freddo_ms", cold)
      put("avvio_a_caldo_ms", warm)
      put("embed_ms_p50", percentile(latencies, 0.5))
      put("embed_ms_p95", percentile(latencies, 0.95))
      put("pss_mb_prima", pssBefore)
      put("pss_mb_caricato", pssLoaded)
      put("pss_mb_dopo_rilascio", pssReleased)
      put("cache_programmi_kib", cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024)
    }
  }

  // --- 440M (le immagini) --------------------------------------------------------------------------------------------------------

  private suspend fun measureImages(spec: ModelSpec, backend: AiBackend, access: ModelAccess) {
    val cacheDir = File(TestModels.cacheRoot, "${spec.sha256.hex}-${backend.name.lowercase()}")
    val timings = OpenTimings()
    val sessions = LiteRtLmSessions(TestModels.files, spec, TestModels.cacheRoot, AiRecipe.CURRENT, withVision = true, timings = timings, access = access)
    val images = ProbeImageSet.modelImages()

    cacheDir.deleteRecursively()
    val pssBefore = Probes.pssMb()
    val coldStart = System.nanoTime()
    val session = try {
      sessions.open(backend)
    } catch (t: Throwable) {
      record("immagini_440m_${backend.name.lowercase()}_${access.name.lowercase()}") { put("disponibile", false); put("errore", t.javaClass.simpleName) }
      return
    }
    val coldMs = ms(System.nanoTime() - coldStart)
    val coldVerify = timings.verifyMs
    val coldInit = timings.initMs
    val pssLoaded = Probes.pssMb()
    val perImage = ArrayList<Double>()
    val rounds = ArrayList<Double>()
    val norms = ArrayList<Double>() // la norma GREZZA dei vettori, prima di ogni controllo: dice se la GPU sta nella banda 1 +- 0,01
    repeat(3) {
      val roundStart = System.nanoTime()
      for (image in images) {
        val start = System.nanoTime()
        val raw = session.embedImage(image.jpeg)
        perImage += ms(System.nanoTime() - start)
        norms += Math.sqrt(raw.sumOf { it.toDouble() * it })
      }
      rounds += images.size / ((System.nanoTime() - roundStart) / 1e9)
    }
    val pssWorking = Probes.pssMb()
    session.close()
    val pssReleased = Probes.pssMb()

    val warmStart = System.nanoTime()
    val warm = sessions.open(backend)
    val warmMs = ms(System.nanoTime() - warmStart)
    val warmVerify = timings.verifyMs
    val warmInit = timings.initMs
    warm.close()

    val sorted = perImage.sorted()
    record("immagini_440m_${backend.name.lowercase()}_${access.name.lowercase()}") {
      put("disponibile", true)
      put("avvio_a_freddo_ms", coldMs)
      put("avvio_a_freddo_verifica_ms", coldVerify)
      put("avvio_a_freddo_init_ms", coldInit)
      put("avvio_a_caldo_ms", warmMs)
      put("avvio_a_caldo_verifica_ms", warmVerify)
      put("avvio_a_caldo_init_ms", warmInit)
      put("foto_al_secondo_giri", org.json.JSONArray(rounds.map { it }))
      put("foto_al_secondo_mediana", rounds.sorted()[rounds.size / 2])
      put("immagine_ms_p50", percentile(sorted, 0.5))
      put("immagine_ms_p95", percentile(sorted, 0.95))
      put("norma_grezza_min", norms.min())
      put("norma_grezza_max", norms.max())
      put("pss_mb_prima", pssBefore)
      put("pss_mb_caricato", pssLoaded)
      put("pss_mb_in_lavoro", pssWorking)
      put("pss_mb_dopo_rilascio", pssReleased)
      put("cache_programmi_kib", cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024)
    }
  }

  @Test
  fun motoreDelleImmagini440SuCpu() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    for (access in ModelAccess.entries) measureImages(TestModels.index440m, AiBackend.CPU, access)
  }

  @Test
  fun motoreDelleImmagini440SuGpu() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    for (access in ModelAccess.entries) measureImages(TestModels.index440m, AiBackend.GPU, access)
  }

  @Test
  fun misuraBreveDelDispositivo() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val embedder = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val result = dev.pampa.mycloudgallery.core.ai.runtime.RealDeviceProbe(embedder).measure()
      record("misura_breve_D15") {
        put("foto_al_secondo", result.imagesPerSecond)
        put("backend", result.backend.name)
        put("avvio_ms", result.coldStartMs)
        put("notti_per_18000_media", result.nightsFor(18_000))
      }
    } finally {
      embedder.release()
    }
  }

  // --- OCR ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun ocrDiMlKit() = runBlocking<Unit> {
    val active = TextReadingState.OnForAccount(thisDevice = true)
    val bootstrap = MlKitBootstrap(context) { active }
    val reader = MlKitTextReader(bootstrap)
    try {
      val pssBefore = Probes.pssMb()
      val results = mutableMapOf<String, List<Double>>()
      var coldMs = 0.0
      for ((resolution, px) in listOf(1024 to 28f, 2048 to 56f)) {
        val page = OcrFixtures.page(resolution, resolution * 3 / 4, px)
        val image = if (resolution == 1024) {
          ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)!!
        } else {
          ModelInputs.forOcr(OcrFixtures.webp90(page.bitmap), OcrSource.HQ)!!
        }
        page.bitmap.recycle()
        val times = ArrayList<Double>()
        repeat(12) { i ->
          val start = System.nanoTime()
          reader.read(image)
          val elapsed = ms(System.nanoTime() - start)
          if (i == 0 && resolution == 1024) coldMs = elapsed else times += elapsed
        }
        results["${resolution}px"] = times.sorted()
      }
      val pssAfter = Probes.pssMb()
      record("ocr_mlkit") {
        put("prima_lettura_ms_include_avvio_di_mlkit", coldMs)
        for ((name, times) in results) {
          put("ms_p50_$name", percentile(times, 0.5))
          put("ms_p95_$name", percentile(times, 0.95))
        }
        put("pss_mb_prima", pssBefore)
        put("pss_mb_dopo", pssAfter)
      }
    } finally {
      TextReadingShutdown(DataTransportCleanup(context), { reader.release() }, { bootstrap.isInitialized }).also {
        it.onTurnedOff()
        it.atStartup(TextReadingState.Off)
      }
    }
  }

  private companion object {
    const val TAG = "P4Measure"
  }
}
