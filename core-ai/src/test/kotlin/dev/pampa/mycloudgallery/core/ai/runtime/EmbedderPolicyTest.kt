package dev.pampa.mycloudgallery.core.ai.runtime

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.EmbedderContract
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (P4, §5.2): la POLITICA dei due motori dei vettori con sessioni finte, sulla JVM: la stessa suite di contratto
 * (`EmbedderContract`) che passano i fake e il motore vero (`EmbedderContractDeviceTest`), una richiesta alla volta, errori come
 * codici, ripiego da GPU a CPU (E-A12) in ogni sua forma, rilascio idempotente, cancellazione. Il motore nativo vero si prova sul
 * dispositivo; qui si prova tutto cio' che lo circonda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbedderPolicyTest {

  // --- Testo ------------------------------------------------------------------------------------------------------------------

  private fun textEmbedder(factory: FakeSessionFactory, diags: MutableList<DiagCode> = mutableListOf(), clock: () -> Long = System::nanoTime) =
    LiteRtLmTextEmbedder(factory, AiRecipe.CURRENT, clock) { diags += it }

  @Test
  fun ilMotoreDelleQueryPassaLaSuiteDiContratto() = runTest {
    val factory = FakeSessionFactory(latencyMs = 5)
    EmbedderContract.verifyText(textEmbedder(factory))
    // Il contratto chiama release() due volte e poi embed(): il motore si e' riaperto, sempre su CPU.
    assertThat(factory.attempts).containsExactly(AiBackend.CPU, AiBackend.CPU)
  }

  @Test
  fun leRichiesteDelTestoNonSiSovrappongonoMai() = runTest {
    val factory = FakeSessionFactory(latencyMs = 30)
    val embedder = textEmbedder(factory)
    List(8) { async { embedder.embed("testo $it") } }.awaitAll()
    assertThat(factory.maxConcurrent).isEqualTo(1)
    assertThat(factory.opened).hasSize(1)
  }

  @Test
  fun ilTestoDellaQueryEPreparatoPrimaDiArrivareAlMotore() = runTest {
    val factory = FakeSessionFactory()
    val embedder = textEmbedder(factory)
    embedder.embed("a".repeat(500))
    assertThat(factory.texts.last()).hasLength(AiRecipe.CURRENT.maxQueryChars)
    // Una coppia di surrogati sul confine non si spezza: il surrogato alto solo si butta.
    embedder.embed("b".repeat(AiRecipe.CURRENT.maxQueryChars - 1) + "\uD83D\uDC36 cane")
    assertThat(factory.texts.last()).hasLength(AiRecipe.CURRENT.maxQueryChars - 1)
    assertThat(Character.isHighSurrogate(factory.texts.last().last())).isFalse()
    // Controlli ISO e separatori di riga diventano spazi; ai bordi si tolgono.
    embedder.embed("\u0000cane\u0007\u2028gatto\n")
    assertThat(factory.texts.last()).isEqualTo("cane  gatto")
    // Gli accenti, l'euro e le emoji intere passano.
    embedder.embed("caff\u00E8 \u20AC \uD83D\uDC36")
    assertThat(factory.texts.last()).isEqualTo("caff\u00E8 \u20AC \uD83D\uDC36")
  }

  @Test
  fun unTestoVuotoNonArrivaAlMotore() = runTest {
    val factory = FakeSessionFactory()
    val embedder = textEmbedder(factory)
    for (blank in listOf("", "   ", "\n\t", "\u0000\u0001")) {
      assertThat(engineError { embedder.embed(blank) }).isEqualTo(AiEngineError.BAD_INPUT)
    }
    assertThat(factory.opened).isEmpty()
    assertThat(factory.texts).isEmpty()
  }

  @Test
  fun unModelloMancanteESoloIlCodiceDelModello() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.CPU] = AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    val diags = mutableListOf<DiagCode>()
    val embedder = textEmbedder(factory, diags)
    assertThat(embedder.warmUp()).isEqualTo(WarmUpResult.Failed(AiEngineError.MODEL_UNAVAILABLE))
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
    assertThat(diags).isEmpty() // lo dice la pagina dei modelli (E-Q11), non la Diagnostica del motore
  }

  @Test
  fun unaInizializzazioneCheFallisceEUnCodiceEUnaVoceDiDiagnostica() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.CPU] = IllegalStateException("libreria nativa")
    val diags = mutableListOf<DiagCode>()
    val embedder = textEmbedder(factory, diags)
    assertThat(embedder.warmUp()).isEqualTo(WarmUpResult.Failed(AiEngineError.INIT_FAILED))
    assertThat(diags).containsExactly(AiDiag.AI_ENGINE_INIT_FAILED)
    // Un guasto dell'avvio non resta: la volta dopo si riprova e, se il guasto e' passato, funziona.
    factory.openFailure.clear()
    assertThat(embedder.warmUp()).isInstanceOf(WarmUpResult.Ready::class.java)
    assertThat(embedder.embed("cane").values).hasLength(768)
  }

  @Test
  fun memoriaEsauritaEUnLinkageErrorSonoCodici() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.CPU] = OutOfMemoryError()
    assertThat(textEmbedder(factory).warmUp()).isEqualTo(WarmUpResult.Failed(AiEngineError.OUT_OF_MEMORY))
    factory.openFailure[AiBackend.CPU] = UnsatisfiedLinkError("liblitertlm_jni.so")
    assertThat(textEmbedder(factory).warmUp()).isEqualTo(WarmUpResult.Failed(AiEngineError.INIT_FAILED))
  }

  @Test
  fun unVettoreSporcoNonEscePerNessunaStrada() = runTest {
    val factory = FakeSessionFactory()
    val embedder = textEmbedder(factory)
    factory.onText = { _, _ -> FloatArray(768) { Float.NaN } }
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.NON_FINITE)
    factory.onText = { _, _ -> FloatArray(768) { 0.5f } } // norma 13,8
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.NON_FINITE)
    factory.onText = { _, _ -> FakeSessionFactory.unitVector("x", 512) } // dimensione sbagliata
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.NON_FINITE)
    factory.onText = { _, _ -> FloatArray(768) { if (it == 3) Float.POSITIVE_INFINITY else 0f } }
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.NON_FINITE)
    factory.onText = null
    assertThat(embedder.embed("cane").values).hasLength(768) // il motore resta usabile
  }

  @Test
  fun unaInferenzaCheLanciaEBadInputEIlMotoreResta() = runTest {
    val factory = FakeSessionFactory()
    val embedder = textEmbedder(factory)
    factory.onText = { _, _ -> throw IllegalStateException("errore interno della libreria") }
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.BAD_INPUT)
    factory.onText = { _, _ -> throw OutOfMemoryError() }
    assertThat(engineError { embedder.embed("cane") }).isEqualTo(AiEngineError.OUT_OF_MEMORY)
    factory.onText = null
    embedder.embed("cane")
    assertThat(factory.opened).hasSize(1)
  }

  @Test
  fun laCancellazioneNonEUnGuastoENonRompeIlMotore() = runTest {
    val factory = FakeSessionFactory(latencyMs = 1_000)
    val embedder = textEmbedder(factory)
    val job = launch { embedder.embed("cane") }
    testScheduler.advanceTimeBy(100)
    job.cancelAndJoin()
    assertThat(job.isCancelled).isTrue()
    factory.latencyMs = 0
    assertThat(embedder.embed("gatto").values).hasLength(768)
    assertThat(factory.opened).hasSize(1)
  }

  @Test
  fun unaCancellazioneDuranteLAperturaNonDiventaUnGuasto() = runTest {
    val gate = CompletableDeferred<Unit>()
    val factory = SessionFactory { gate.await().let { error("non si arriva qui") } }
    val embedder = LiteRtLmTextEmbedder(factory, AiRecipe.CURRENT, System::nanoTime) {}
    val seen = CompletableDeferred<Throwable>()
    val job = launch {
      try {
        embedder.warmUp()
      } catch (t: Throwable) {
        seen.complete(t)
        throw t
      }
    }
    testScheduler.advanceUntilIdle()
    job.cancel()
    job.join()
    // warmUp non l'ha trasformata in Failed(...): e' passata come cancellazione.
    assertThat(seen.isCompleted).isTrue()
    assertThat(seen.await()).isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun ilRilascioEIdempotenteEIlMotoreSiRiapre() = runTest {
    val factory = FakeSessionFactory()
    val embedder = textEmbedder(factory)
    embedder.release() // senza niente di aperto
    embedder.warmUp()
    embedder.release()
    embedder.release()
    assertThat(factory.closed).hasSize(1)
    embedder.embed("cane")
    assertThat(factory.opened).hasSize(2)
  }

  @Test
  fun ilTempoDiAvvioEQuelloDellApertura() = runTest {
    var now = 0L
    val factory = object : SessionFactory {
      override suspend fun open(backend: AiBackend): VectorSession {
        now += 3_600_000_000L // 3,6 s
        return FakeSession(backend, FakeSessionFactory())
      }
    }
    val embedder = LiteRtLmTextEmbedder(factory, AiRecipe.CURRENT, { now }) {}
    assertThat(embedder.warmUp()).isEqualTo(WarmUpResult.Ready(3_600))
    assertThat(embedder.warmUp()).isEqualTo(WarmUpResult.Ready(0)) // gia' caldo
  }

  // --- Immagini ---------------------------------------------------------------------------------------------------------------

  private fun imageEmbedder(factory: FakeSessionFactory, diags: MutableList<DiagCode> = mutableListOf()) =
    LiteRtLmImageEmbedder(factory) { diags += it }

  private val images = listOf(AiTestInputs.image("cane spiaggia"), AiTestInputs.image("scontrino"), AiTestInputs.image("tramonto"))

  @Test
  fun ilMotoreDelleImmaginiPassaLaSuiteDiContratto() = runTest {
    val factory = FakeSessionFactory()
    val embedder = imageEmbedder(factory)
    EmbedderContract.verifyImages(embedder, images)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.GPU)
    assertThat(factory.attempts.toSet()).containsExactly(AiBackend.GPU)
  }

  @Test
  fun unVettorePerImmagineNelLOrdineDato() = runTest {
    val factory = FakeSessionFactory()
    val out = imageEmbedder(factory).embed(images)
    assertThat(out).hasSize(3)
    val again = imageEmbedder(FakeSessionFactory()).embed(images.reversed())
    assertThat(EmbedderContract.cosine(out[0], again[2])).isGreaterThan(0.999f)
    assertThat(imageEmbedder(FakeSessionFactory()).embed(emptyList())).isEmpty()
  }

  @Test
  fun laGpuCheNonPartePassaSuCpuPerLaSessione() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.GPU] = IllegalStateException("OpenCL non disponibile")
    val diags = mutableListOf<DiagCode>()
    val embedder = imageEmbedder(factory, diags)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.GPU)
    assertThat(embedder.embed(images)).hasSize(3)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.CPU)
    assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK)
    // Per la sessione: dopo un rilascio non si riprova la GPU.
    embedder.release()
    embedder.embed(images)
    assertThat(factory.attempts).containsExactly(AiBackend.GPU, AiBackend.CPU, AiBackend.CPU).inOrder()
    assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK)
  }

  @Test
  fun unaInferenzaGpuCheLanciaSiRipeteSuCpuSulleStessaImmagine() = runTest {
    val factory = FakeSessionFactory()
    var gpuCalls = 0
    factory.onImage = { session, jpeg ->
      if (session.backend == AiBackend.GPU) {
        gpuCalls++
        if (gpuCalls == 2) throw IllegalStateException("errore sulla GPU")
      }
      FakeSessionFactory.unitVector("i:" + jpeg.contentHashCode())
    }
    val diags = mutableListOf<DiagCode>()
    val embedder = imageEmbedder(factory, diags)
    val out = embedder.embed(images)
    assertThat(out).hasSize(3)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.CPU)
    assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK)
    // La prima immagine l'aveva fatta la GPU, la seconda e la terza la CPU: i vettori sono quelli giusti, nell'ordine giusto.
    val expected = images.map { FakeSessionFactory.unitVector("i:" + it.jpeg.contentHashCode()) }
    for (i in 0..2) assertThat(EmbedderContract.cosine(out[i], expected[i])).isGreaterThan(0.9999f)
    assertThat(factory.closed.map { it.backend }).containsExactly(AiBackend.GPU)
  }

  @Test
  fun unVettoreGpuNonValidoSiRipeteSuCpu() = runTest {
    val bad = listOf(
      FloatArray(768) { Float.NaN },
      FloatArray(768) { 0.1f }, // norma 2,77
      FloatArray(768), // norma 0
      FakeSessionFactory.unitVector("x", 767),
    )
    for (vector in bad) {
      val f = FakeSessionFactory()
      f.onImage = { session, jpeg -> if (session.backend == AiBackend.GPU) vector else FakeSessionFactory.unitVector("i:" + jpeg.contentHashCode()) }
      val diags = mutableListOf<DiagCode>()
      val embedder = imageEmbedder(f, diags)
      val out = embedder.embed(listOf(images[0]))
      assertThat(out.single()).hasLength(768)
      assertThat(out.single().all { it.isFinite() }).isTrue()
      assertThat(embedder.backend.value).isEqualTo(AiBackend.CPU)
      assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK)
    }
  }

  @Test
  fun unaNormaAppenaSbagliataDellaGpuSiRinormalizzaSenzaRipiego() = runTest {
    val factory = FakeSessionFactory()
    factory.onImage = { _, jpeg -> FakeSessionFactory.unitVector("i:" + jpeg.contentHashCode()).map { it * 0.985f }.toFloatArray() }
    val diags = mutableListOf<DiagCode>()
    val embedder = imageEmbedder(factory, diags)
    val out = embedder.embed(images)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.GPU) // nessun ripiego per qualche millesimo
    assertThat(diags).isEmpty()
    out.forEach { assertThat(VectorChecks.isValid(it, 768)).isTrue() }
    // Il testo: stessa cosa.
    val textFactory = FakeSessionFactory()
    textFactory.onText = { _, t -> FakeSessionFactory.unitVector("t:$t").map { it * 1.03f }.toFloatArray() }
    assertThat(VectorChecks.isValid(textEmbedder(textFactory).embed("cane").values, 768)).isTrue()
  }

  @Test
  fun suCpuUnVettoreNonValidoENonFiniteEdIlMotoreResta() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.GPU] = IllegalStateException("niente GPU")
    val embedder = imageEmbedder(factory)
    factory.onImage = { _, _ -> FloatArray(768) { Float.NaN } }
    assertThat(engineError { embedder.embed(images) }).isEqualTo(AiEngineError.NON_FINITE)
    factory.onImage = { _, _ -> throw IllegalStateException("errore sulla CPU") }
    assertThat(engineError { embedder.embed(images) }).isEqualTo(AiEngineError.BAD_INPUT)
    factory.onImage = null
    assertThat(embedder.embed(images)).hasSize(3)
  }

  @Test
  fun senzaIlModelloNonSiProvaLaCpuEdIlRipiegoNonSiSegna() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.GPU] = AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    val diags = mutableListOf<DiagCode>()
    val embedder = imageEmbedder(factory, diags)
    assertThat(engineError { embedder.embed(images) }).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
    assertThat(factory.attempts).containsExactly(AiBackend.GPU)
    assertThat(diags).isEmpty()
    assertThat(embedder.backend.value).isEqualTo(AiBackend.GPU)
    // Il modello torna: la GPU si prova ancora.
    factory.openFailure.clear()
    embedder.embed(images)
    assertThat(embedder.backend.value).isEqualTo(AiBackend.GPU)
  }

  @Test
  fun gpuEcpuNonPartonoMaiLInizializzazioneEUnCodice() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.GPU] = IllegalStateException("gpu")
    factory.openFailure[AiBackend.CPU] = IllegalStateException("cpu")
    val diags = mutableListOf<DiagCode>()
    val embedder = imageEmbedder(factory, diags)
    assertThat(engineError { embedder.embed(images) }).isEqualTo(AiEngineError.INIT_FAILED)
    assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK, AiDiag.AI_ENGINE_INIT_FAILED).inOrder()
  }

  @Test
  fun leRichiesteDelleImmaginiNonSiSovrappongonoMai() = runTest {
    val factory = FakeSessionFactory(latencyMs = 40)
    val embedder = imageEmbedder(factory)
    List(5) { async { embedder.embed(images) } }.awaitAll()
    assertThat(factory.maxConcurrent).isEqualTo(1)
    assertThat(factory.opened).hasSize(1)
  }

  @Test
  fun ilRilascioDelleImmaginiEIdempotente() = runTest {
    val factory = FakeSessionFactory()
    val embedder = imageEmbedder(factory)
    embedder.release()
    embedder.embed(images)
    embedder.release()
    embedder.release()
    assertThat(factory.closed).hasSize(1)
    embedder.embed(images)
    assertThat(factory.opened).hasSize(2)
  }
}
