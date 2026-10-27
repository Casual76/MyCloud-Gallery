package dev.pampa.mycloudgallery.core.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmImageEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.ModelAccess
import dev.pampa.mycloudgallery.core.ai.runtime.ProbeImageSet
import dev.pampa.mycloudgallery.core.ai.runtime.RealDeviceProbe
import dev.pampa.mycloudgallery.core.ai.testing.EmbedderContract
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, §5.2): la SUITE DI CONTRATTO comune (`EmbedderContract`) sui motori veri. E' la stessa che passano i fake sulla JVM
 * (`FakeEnginesTest`) e la politica con sessioni finte (`EmbedderPolicyTest`): dimensione, norma 1, determinismo con coseno >= 0,999,
 * chiamate in parallelo servite in fila, `warmUp`/`release` idempotenti, un `embed` dopo `release` che riscalda, un guasto sempre come
 * `AiEngineException`. Qui con LiteRT-LM vero: il 270M su CPU per le query, il 440M (GPU o, dove manca, CPU) per le immagini.
 */
@RunWith(AndroidJUnit4::class)
class EmbedderContractDeviceTest {

  @Test
  fun ilMotoreDelleQueryVeroPassaLaSuiteDiContratto() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val diags = mutableListOf<DiagCode>()
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot, diag = { diags += it })
    try {
      EmbedderContract.verifyText(embedder)
    } finally {
      embedder.release()
    }
    assertTrue("nessuna voce di Diagnostica attesa: $diags", diags.isEmpty())
  }

  @Test
  fun ilMotoreDelleImmaginiVeroPassaLaSuiteDiContratto() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val diags = mutableListOf<DiagCode>()
    val embedder = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot, diag = { diags += it })
    try {
      EmbedderContract.verifyImages(embedder, ProbeImageSet.modelImages().take(4))
      Log.i(TAG, "440M: backend ${embedder.backend.value}, diag $diags")
      // GPU o CPU: se ha ripiegato lo dice la Diagnostica, mai un guasto.
      if (embedder.backend.value == AiBackend.CPU) assertTrue(diags.all { it.id == "AI_GPU_FALLBACK" })
    } finally {
      embedder.release()
    }
  }

  /** Una query al tetto dei 200 caratteri ma con piu' di 128 token (emoji, parole rare): il motore o la vettorizza o la rifiuta, mai crolla. */
  @Test
  fun unaQueryCheSforaITokenNonRompeIlMotore() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val dog = String(Character.toChars(0x1F436))
      for (query in listOf(dog.repeat(100), "xqzv".repeat(50), "a b c d e f g h i j k l m n o p q r s t u v w x y z ".repeat(8))) {
        val outcome = runCatching { embedder.embed(query) }
        val error = outcome.exceptionOrNull()
        assertTrue("solo AiEngineException ammessa: $error", error == null || error is AiEngineException)
        outcome.getOrNull()?.let { assertEquals(768, it.values.size) }
      }
      // Dopo un ingresso difficile il motore e' ancora buono.
      assertEquals(768, embedder.embed("un cane che corre sulla spiaggia").values.size)
    } finally {
      embedder.release()
    }
  }

  @Test
  fun senzaIlModelloIMotoriDannoMODELUNAVAILABLE() = runBlocking<Unit> {
    val empty = File(TestModels.root.parentFile, "p4-models-empty").apply { mkdirs() }
    val files = LocalModels(
      root = empty,
      fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      networkGate = ModelNetworkGate { _, _, _ -> ModelNetworkVerdict.Refused("prova") },
    ).files
    val text = LiteRtLmTextEmbedder(files, TestModels.cacheRoot)
    assertEquals(WarmUpResult.Failed(AiEngineError.MODEL_UNAVAILABLE), text.warmUp())
    val thrown = runCatching { text.embed("cane") }.exceptionOrNull()
    assertEquals(AiEngineError.MODEL_UNAVAILABLE, (thrown as AiEngineException).error)

    val images = LiteRtLmImageEmbedder(files, TestModels.cacheRoot)
    val imageThrown = runCatching { images.embed(ProbeImageSet.modelImages().take(1)) }.exceptionOrNull()
    assertEquals(AiEngineError.MODEL_UNAVAILABLE, (imageThrown as AiEngineException).error)
    assertEquals(AiBackend.GPU, images.backend.value) // senza il modello non e' un ripiego
  }

  @Test
  fun unFileAlteratoNonSiCarica() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    // Un byte cambiato in una copia del modello: la verifica a ogni apertura lo rifiuta e il motore non parte (E-A11).
    val spec = TestModels.query270m
    val altered = File(TestModels.root.parentFile, "p4-models-altered").apply { deleteRecursively(); mkdirs() }
    val copy = File(altered, spec.sha256.hex)
    File(TestModels.root, spec.sha256.hex).inputStream().use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 20) } }
    RandomAccessFile(copy, "rw").use { f ->
      f.seek(spec.sizeBytes / 2)
      val b = f.read()
      f.seek(spec.sizeBytes / 2)
      f.write(b xor 0x01)
    }
    val files = LocalModels(
      root = altered,
      fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      networkGate = ModelNetworkGate { _, _, _ -> ModelNetworkVerdict.Refused("prova") },
    ).files
    val text = LiteRtLmTextEmbedder(files, TestModels.cacheRoot)
    assertEquals(WarmUpResult.Failed(AiEngineError.MODEL_UNAVAILABLE), text.warmUp())
    assertFalse("il file alterato va tolto", copy.exists())
    altered.deleteRecursively()
  }

  @Test
  fun ilMotoreLeggeIlDescrittoreVerificatoEMaiRiapreIlFile() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    // Un ModelFiles che consegna un modello vero ma il cui FILE e' stato poi cancellato e sostituito da uno finto: il motore usa il
    // descrittore gia' aperto (quello su cui e' stato calcolato lo SHA-256), non il nome del file.
    val spec = TestModels.query270m
    val real = TestModels.files.open(spec.id)
    assertNotNull(real)
    try {
      val file = real!!.file
      assertNotNull(real.descriptor)
      val parked = File(file.parentFile, file.name + ".parked")
      assertTrue(file.renameTo(parked))
      try {
        file.writeBytes(ByteArray(1024)) // un file qualunque al posto del modello
        val once = object : ModelFiles {
          var used = false
          override suspend fun open(id: ModelId): VerifiedModel? = if (used) null else real.also { used = true }
        }
        val embedder = LiteRtLmTextEmbedder(once, TestModels.cacheRoot, access = ModelAccess.DESCRIPTOR)
        assertTrue(embedder.warmUp() is WarmUpResult.Ready)
        val v = embedder.embed("un cane che corre sulla spiaggia").values
        assertEquals(768, v.size)
        // Il motore e' sopravvissuto alla sostituzione perche' possiede il descrittore: chiuderlo non chiude due volte lo stesso numero.
        embedder.release()
        embedder.release()
      } finally {
        file.delete()
        assertTrue(parked.renameTo(file))
      }
    } finally {
      runCatching { real!!.close() }
    }
  }

  /** Il modello dato come descrittore (D16 alla lettera, senza cache) passa la stessa suite di contratto del predefinito (per percorso). */
  @Test
  fun ilMotoreSuDescrittorePassaLaSuiteDiContratto() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val embedder = LiteRtLmTextEmbedder(TestModels.files, TestModels.cacheRoot, access = ModelAccess.DESCRIPTOR)
    try {
      EmbedderContract.verifyText(embedder)
    } finally {
      embedder.release()
    }
  }

  /** Per percorso, un file rinominato o sostituito fra la verifica e il caricamento e' rifiutato (E-A11): non e' piu' il file verificato. */
  @Test
  fun suPercorsoUnFileSostituitoDopoLaVerificaERifiutato() = runBlocking<Unit> {
    TestModels.require(TestModels.query270m)
    val spec = TestModels.query270m
    val real = TestModels.files.open(spec.id)
    assertNotNull(real)
    try {
      val file = real!!.file
      val parked = File(file.parentFile, file.name + ".parked")
      assertTrue(file.renameTo(parked))
      try {
        parked.copyTo(file) // gli stessi byte, un altro file (un altro inode)
        val once = object : ModelFiles {
          override suspend fun open(id: ModelId): VerifiedModel? = real
        }
        val embedder = LiteRtLmTextEmbedder(once, TestModels.cacheRoot, access = ModelAccess.PATH_CHECKED)
        assertEquals(WarmUpResult.Failed(AiEngineError.MODEL_UNAVAILABLE), embedder.warmUp())
      } finally {
        file.delete()
        assertTrue(parked.renameTo(file))
      }
    } finally {
      runCatching { real!!.close() }
    }
  }

  @Test
  fun laMisuraBreveFaGirareLeVenteImmagini() = runBlocking<Unit> {
    TestModels.require(TestModels.index440m)
    val embedder = LiteRtLmImageEmbedder(TestModels.files, TestModels.cacheRoot)
    try {
      val result = RealDeviceProbe(embedder, rounds = 1).measure()
      Log.i(TAG, "misura breve: ${"%.2f".format(result.imagesPerSecond)} foto/s su ${result.backend}, avvio ${result.coldStartMs} ms, 18.000 media = ${result.nightsFor(18_000)} notti")
      assertTrue(result.imagesPerSecond > 0.0)
      assertTrue(result.coldStartMs >= 0)
      assertTrue(result.nightsFor(18_000) >= 1)
    } finally {
      embedder.release()
    }
  }

  private companion object {
    const val TAG = "P4Contract"
  }
}
