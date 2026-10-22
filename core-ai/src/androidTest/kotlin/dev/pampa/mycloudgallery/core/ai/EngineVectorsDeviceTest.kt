package dev.pampa.mycloudgallery.core.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.runtime.EngineHooks
import dev.pampa.mycloudgallery.core.ai.runtime.FdOwnership
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmImageEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmSessions
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.ModelAccess
import dev.pampa.mycloudgallery.core.ai.runtime.ProbeImageSet
import dev.pampa.mycloudgallery.core.ai.runtime.VectorChecks
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.Float16
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, D2, D3, D4, E-A12; "Fatto quando" di docs/fase6-progetto.md §4.4): i VETTORI dei motori veri su 20 immagini e 20 testi
 * sintetici (nessun dato personale: le scene di `ProbeImages` e venti frasi scritte qui).
 *
 *  - finiti, 768 componenti, norma 1 (la stessa regola con cui il codec `vec` accetta un corpo);
 *  - **coseno GPU-CPU >= 0,99** sullo stesso modello (440M) per le 20 immagini e i 20 testi (si salta dove la GPU non c'e': l'emulatore;
 *    la misura vera e' sull'S25 e sul Tab S9);
 *  - **vettori di riferimento salvati** (`src/androidTest/assets/golden/`): a ogni versione della libreria o del modello i vettori di
 *    oggi devono avere coseno >= 0,99 con quelli salvati (registrati su CPU; sulla GPU e' lo stesso confronto GPU-CPU);
 *  - **ripiego su CPU** in ogni forma (GPU che non parte, GPU che da' valori non finiti, GPU che da' una norma sbagliata) con il motore
 *    vero, e nessun descrittore perso.
 *
 * Per rifare i vettori di riferimento (dopo un cambio voluto: libreria, modello, scene, ricetta):
 * `gradlew :core-ai:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.mcgRecordGolden=true
 *  -Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.core.ai.EngineVectorsDeviceTest`, poi `adb pull` dei file
 * scritti in `files/golden/` dell'APK di prova (`adb exec-out run-as dev.pampa.mycloudgallery.core.ai.test cat files/golden/<nome>`) dentro `core-ai/src/androidTest/assets/golden/` (il log `P4Vectors` dice dove).
 */
@RunWith(AndroidJUnit4::class)
class EngineVectorsDeviceTest {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val recipe = AiRecipe.CURRENT

  // --- Finiti e normalizzati ----------------------------------------------------------------------------------------------------

  @Test
  fun leVenteImmaginiDanno768ValoriFinitiENormalizzati() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val embedder = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val vectors = embedder.embed(ProbeImageSet.modelImages())
      assertEquals(20, vectors.size)
      vectors.forEachIndexed { i, v -> assertEquals("immagine $i: ${VectorChecks.verdict(v, 768)}", VectorChecks.Verdict.OK, VectorChecks.verdict(v, 768)) }
      Log.i(TAG, "20 immagini su ${embedder.backend.value}: tutte valide")
    } finally {
      embedder.release()
    }
  }

  @Test
  fun leVenteFrasiDanno768ValoriFinitiENormalizzati270() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val vectors = TEXTS.map { embedder.embed(it).values }
      vectors.forEachIndexed { i, v -> assertEquals("testo $i", VectorChecks.Verdict.OK, VectorChecks.verdict(v, 768)) }
      // Frasi diverse, vettori diversi (il motore non e' degenerato).
      val offDiagonal = TEXTS.indices.flatMap { i -> TEXTS.indices.filter { it > i }.map { j -> VectorChecks.cosine(vectors[i], vectors[j]) } }
      assertTrue("coseno massimo fra frasi diverse ${offDiagonal.max()}", offDiagonal.max() < 0.95f)
    } finally {
      embedder.release()
    }
  }

  // --- GPU contro CPU --------------------------------------------------------------------------------------------------------------

  /** Il 440M su GPU e su CPU, immagini e testi: coseno >= 0,99. Si salta se la GPU non c'e' (emulatore). */
  @Test
  fun gpuECpuConcordanoSuVenteImmaginiEVenteTesti() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val cpu = vectorsOf(AiBackend.CPU)!!
    val gpu = vectorsOf(AiBackend.GPU)
    assumeTrue("GPU non disponibile su questo dispositivo: il confronto GPU-CPU si fa sull'S25 e sul Tab S9", gpu != null)
    val images = cpu.images.indices.map { VectorChecks.cosine(cpu.images[it], gpu!!.images[it]) }
    val texts = cpu.texts.indices.map { VectorChecks.cosine(cpu.texts[it], gpu!!.texts[it]) }
    Log.i(TAG, "GPU-CPU immagini: min ${images.min()} media ${images.average()}; testi: min ${texts.min()} media ${texts.average()}")
    assertTrue("coseno minimo GPU-CPU sulle immagini ${images.min()}", images.min() >= 0.99f)
    assertTrue("coseno minimo GPU-CPU sui testi ${texts.min()}", texts.min() >= 0.99f)
  }

  // --- Vettori di riferimento ----------------------------------------------------------------------------------------------------

  @Test
  fun leImmaginiCorrispondonoAiVettoriDiRiferimento() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val embedder = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val current = embedder.embed(ProbeImageSet.modelImages())
      compareWithGolden("images-440m", current, "immagini (${embedder.backend.value})")
    } finally {
      embedder.release()
    }
  }

  @Test
  fun leFrasiCorrispondonoAiVettoriDiRiferimento270() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      compareWithGolden("texts-270m", TEXTS.map { embedder.embed(it).values }, "testi 270M")
    } finally {
      embedder.release()
    }
  }

  @Test
  fun leFrasiDel440CorrispondonoAiVettoriDiRiferimento() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val sessions = LiteRtLmSessions(TestModels.files, TestModels.index440m, TestModels.cacheRoot, recipe, withVision = true)
    val session = sessions.open(AiBackend.CPU)
    try {
      compareWithGolden("texts-440m", TEXTS.map { session.embedText(it) }, "testi 440M")
    } finally {
      session.close()
    }
  }

  /** Le query (270M) e le immagini (440M) stanno nello stesso spazio: la scena giusta e' fra le prime. */
  @Test
  fun leQueryDel270TrovanoLeScenePiuAssociate() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    TestModels.require(TestModels.index440m)
    val images = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot)
    val text = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val sceneVectors = images.embed(ProbeImageSet.modelImages())
      val queries = listOf(
        "un tramonto" to 0, "una spiaggia con il mare" to 1, "un cielo stellato di notte" to 2, "un prato verde con le nuvole" to 3,
        "montagne" to 4, "una scacchiera in bianco e nero" to 8, "un arcobaleno" to 9, "una città con dei palazzi" to 11,
        "uno scontrino" to 15, "dei fiori" to 16,
      )
      val ranks = queries.map { (query, expected) ->
        val q = text.embed(query).values
        val order = sceneVectors.indices.sortedByDescending { VectorChecks.cosine(q, sceneVectors[it]) }
        order.indexOf(expected) + 1
      }
      Log.i(TAG, "posizione della scena attesa (su 20) per ${queries.map { it.first }}: $ranks")
      assertTrue("la scena attesa deve stare fra le prime 5 per almeno 7 query su 10: $ranks", ranks.count { it <= 5 } >= 7)
    } finally {
      images.release()
      text.release()
    }
  }

  // --- Ripiego su CPU ----------------------------------------------------------------------------------------------------------------

  @Test
  fun laGpuCheNonPartePassaSuCpuEIVettoriSonoQuelliGiusti() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val diags = mutableListOf<DiagCode>()
    val failGpu = object : EngineHooks {
      override fun beforeInit(backend: AiBackend) {
        if (backend == AiBackend.GPU) throw IllegalStateException("GPU assente (prova)")
      }
    }
    val sessions = LiteRtLmSessions(TestModels.files, TestModels.index440m, TestModels.cacheRoot, recipe, withVision = true, hooks = failGpu)
    val embedder = LiteRtLmImageEmbedder(sessions) { diags += it }
    try {
      val images = ProbeImageSet.modelImages().take(3)
      val out = embedder.embed(images)
      assertEquals(AiBackend.CPU, embedder.backend.value)
      assertEquals(listOf(AiDiag.AI_GPU_FALLBACK), diags)
      out.forEach { assertTrue(VectorChecks.isValid(it, 768)) }
      // Gli stessi vettori di un motore che e' partito subito su CPU.
      val cpu = LiteRtLmSessions(TestModels.files, TestModels.index440m, TestModels.cacheRoot, recipe, withVision = true).open(AiBackend.CPU)
      try {
        out.indices.forEach { assertTrue(VectorChecks.cosine(out[it], cpu.embedImage(images[it].jpeg)) > 0.999f) }
      } finally {
        cpu.close()
      }
    } finally {
      embedder.release()
    }
  }

  @Test
  fun unaGpuCheDaValoriNonFinitiONormaSbagliataSiRipeteSuCpu() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    for (mode in listOf("nan", "norma")) {
      val diags = mutableListOf<DiagCode>()
      val hooks = object : EngineHooks {
        // La sessione "GPU" gira davvero su CPU (la GPU puo' non esserci) ma si comporta come una GPU rotta.
        override fun nativeBackend(requested: AiBackend): AiBackend = AiBackend.CPU

        override fun afterEmbed(backend: AiBackend, vector: FloatArray): FloatArray =
          if (backend != AiBackend.GPU) vector else if (mode == "nan") FloatArray(vector.size) { Float.NaN } else FloatArray(vector.size) { vector[it] * 3f }
      }
      val sessions = LiteRtLmSessions(TestModels.files, TestModels.index440m, TestModels.cacheRoot, recipe, withVision = true, hooks = hooks)
      val embedder = LiteRtLmImageEmbedder(sessions) { diags += it }
      try {
        val out = embedder.embed(ProbeImageSet.modelImages().take(2))
        assertEquals(mode, AiBackend.CPU, embedder.backend.value)
        assertEquals(mode, listOf(AiDiag.AI_GPU_FALLBACK), diags)
        out.forEach { assertTrue(mode, VectorChecks.isValid(it, 768)) }
        // Per la sessione: dopo un rilascio non si riprova la GPU.
        embedder.release()
        embedder.embed(ProbeImageSet.modelImages().take(1))
        assertEquals(mode, listOf(AiDiag.AI_GPU_FALLBACK), diags)
      } finally {
        embedder.release()
      }
    }
  }

  // --- Descrittori e memoria -----------------------------------------------------------------------------------------------------

  /** Ogni caricamento duplica il descrittore del modello e lo chiude alla fine: cinque cicli non lasciano descrittori aperti. */
  @Test
  fun aprireERilasciareNonPerdeDescrittori() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot, access = ModelAccess.DESCRIPTOR) // la copia c'e' solo cosi'
    embedder.warmUp()
    embedder.embed("cane")
    embedder.release()
    val baseline = openDescriptors()
    repeat(5) {
      embedder.warmUp()
      embedder.embed("gatto")
      embedder.release()
    }
    val after = openDescriptors()
    Log.i(
      TAG,
      "descrittori aperti: prima $baseline, dopo cinque cicli $after; copie chiuse da noi ${FdOwnership.closedByUs.get()}, " +
        "gia' chiuse dalla libreria ${FdOwnership.closedByLibrary.get()}",
    )
    assertTrue("descrittori in piu': $baseline -> $after", after <= baseline + 2)
  }

  // --- Arnesi -------------------------------------------------------------------------------------------------------------------------

  private class Vectors(val images: List<FloatArray>, val texts: List<FloatArray>)

  /** I vettori del 440M su un backend: null se non parte. */
  private suspend fun vectorsOf(backend: AiBackend): Vectors? {
    val sessions = LiteRtLmSessions(TestModels.files, TestModels.index440m, TestModels.cacheRoot, recipe, withVision = true)
    val session = try {
      sessions.open(backend)
    } catch (t: Throwable) {
      Log.i(TAG, "440M su $backend non parte: ${t.javaClass.simpleName}")
      return null
    }
    try {
      val images = ProbeImageSet.modelImages().map { session.embedImage(it.jpeg) }
      val texts = TEXTS.map { session.embedText(it) }
      return Vectors(images, texts)
    } finally {
      session.close()
    }
  }

  private fun openDescriptors(): Int = File("/proc/self/fd").list()?.size ?: -1

  private fun compareWithGolden(name: String, current: List<FloatArray>, label: String) {
    val args = InstrumentationRegistry.getArguments()
    if (args.getString("mcgRecordGolden") == "true") {
      val out = Golden.save(context, name, current)
      Log.i(TAG, "RECORDED $name -> ${out.absolutePath}")
      return
    }
    val reference = Golden.load(context, name)
    assertNotNull("vettori di riferimento $name assenti: si registrano con -Pandroid.testInstrumentationRunnerArguments.mcgRecordGolden=true", reference)
    assertEquals("numero di vettori di riferimento $name", reference!!.size, current.size)
    val cosines = current.indices.map { VectorChecks.cosine(reference[it], current[it]) }
    Log.i(TAG, "$label contro i riferimenti: min ${cosines.min()} media ${cosines.average()}")
    assertTrue("$label: coseno minimo con i riferimenti ${cosines.min()}", cosines.min() >= 0.99f)
    assertFalse("riferimenti non validi", reference.any { !VectorChecks.isValid(it, 768) })
  }

  private companion object {
    const val TAG = "P4Vectors"

    val TEXTS: List<String> = listOf(
      "un cane che corre sulla spiaggia",
      "tramonto sul mare",
      "uno scontrino del supermercato",
      "una torta di compleanno con le candeline",
      "bambini che giocano nella neve",
      "un gatto sul divano",
      "montagne innevate al mattino",
      "una pizza margherita",
      "il Duomo di Firenze",
      "una bicicletta rossa appoggiata a un muro",
      "fiori di campo in un prato verde",
      "una macchina da corsa gialla",
      "screenshot di una conversazione",
      "cena di Natale con la famiglia",
      "un aereo nel cielo azzurro",
      "una strada di città di notte",
      "il mare in tempesta",
      "documento con tabella e firma",
      "autoritratto allo specchio",
      "caffè e cornetto al bar",
    )
  }
}

/**
 * I vettori di riferimento: un file per insieme in `androidTest/assets/golden/`, `MCGG | versione(4) | n(4) | dimensione(4) |
 * n x dimensione componenti float16 big endian`. Si registrano su CPU; contengono solo numeri (le scene e le frasi sono sintetiche).
 */
internal object Golden {
  private const val MAGIC = 0x4D434747 // "MCGG"
  const val VERSION = 1

  fun file(name: String): String = "golden/$name-v$VERSION.f16"

  fun load(context: Context, name: String): List<FloatArray>? =
    try {
      context.assets.open(file(name)).use { raw ->
        val input = DataInputStream(raw.buffered())
        check(input.readInt() == MAGIC) { "riferimento $name: magic" }
        check(input.readInt() == VERSION) { "riferimento $name: versione" }
        val count = input.readInt()
        val dimension = input.readInt()
        List(count) {
          val half = ShortArray(dimension) { input.readShort() }
          Float16.decode(half)
        }
      }
    } catch (_: java.io.FileNotFoundException) {
      null
    }

  fun save(context: Context, name: String, vectors: List<FloatArray>): File {
    val dir = File(context.filesDir, "golden").apply { mkdirs() }
    val out = File(dir, "$name-v$VERSION.f16")
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { data ->
      data.writeInt(MAGIC)
      data.writeInt(VERSION)
      data.writeInt(vectors.size)
      data.writeInt(vectors.first().size)
      for (v in vectors) for (h in Float16.encode(v)) data.writeShort(h.toInt())
    }
    out.writeBytes(bytes.toByteArray())
    return out
  }
}
