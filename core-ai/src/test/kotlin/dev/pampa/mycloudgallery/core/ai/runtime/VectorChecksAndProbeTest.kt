package dev.pampa.mycloudgallery.core.ai.runtime

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import org.junit.Test

/** Fase 6 (P4): i controlli sui vettori, le 20 immagini della misura breve (D15) e la misura. Tutto puro, sulla JVM. */
class VectorChecksAndProbeTest {

  @Test
  fun preparareLeImmaginiNonOccupaIlThreadDellaPagina() = kotlinx.coroutines.runBlocking {
    val caller = Thread.currentThread()
    var preparation: Thread? = null
    RealDeviceProbe(FakeImageEmbedder(), images = {
      preparation = Thread.currentThread()
      twenty
    }).measure()
    assertThat(preparation).isNotSameInstanceAs(caller)
  }

  @Test
  fun telefonoCaldoNonPreparaNeInferisce() = runTest {
    var prepared = false
    val probe = RealDeviceProbe(FakeImageEmbedder(), images = { prepared = true; twenty }, canMeasure = { false })
    assertThat(runCatching { probe.measure() }.isFailure).isTrue()
    assertThat(prepared).isFalse()
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test fun riaprireLaPaginaNonAccodaMisureAlDriverBloccato() = runTest {
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    var calls = 0
    val single = SingleFlightDeviceProbe(object : dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe {
      override suspend fun measure(): ProbeResult = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        calls++; release.await(); ProbeResult(1.0, AiBackend.CPU, 0)
      }
    })
    val first = backgroundScope.launch { single.measure() }
    try {
      runCurrent(); first.cancel(); runCurrent()
      assertThat(runCatching { single.measure() }.isFailure).isTrue()
      assertThat(calls).isEqualTo(1)
      release.complete(Unit); runCurrent()
      single.measure()
      assertThat(calls).isEqualTo(2)
    } finally {
      release.complete(Unit)
      runCurrent()
    }
  }

  // --- Vettori ------------------------------------------------------------------------------------------------------------------

  @Test
  fun unVettoreNormalizzatoEFinitoPassa() {
    val v = FakeSessionFactory.unitVector("seme")
    assertThat(VectorChecks.verdict(v, 768)).isEqualTo(VectorChecks.Verdict.OK)
    assertThat(VectorChecks.isValid(v, 768)).isTrue()
  }

  @Test
  fun iLimitiDellaNormaSonoQuelliDelCodec() {
    // 1 +- 0,01: gli stessi con cui il codec `vec` rifiuta un corpo (AiLimits), cosi' un vettore che passa qui non viene scartato dopo.
    fun scaled(f: Float) = FakeSessionFactory.unitVector("n").map { it * f }.toFloatArray()
    assertThat(VectorChecks.isValid(scaled(1.0099f), 768)).isTrue()
    assertThat(VectorChecks.isValid(scaled(0.9901f), 768)).isTrue()
    assertThat(VectorChecks.isValid(scaled(1.011f), 768)).isFalse()
    assertThat(VectorChecks.isValid(scaled(0.989f), 768)).isFalse()
  }

  @Test
  fun unaNormaVicinaAUnoSiRinormalizzaLontanaNo() {
    val base = FakeSessionFactory.unitVector("n")
    val slightly = base.map { it * 0.985f }.toFloatArray() // l'errore tipico di un'attivazione float16
    assertThat(VectorChecks.isValid(slightly, 768)).isFalse()
    val fixed = VectorChecks.normalizedOrNull(slightly, 768)!!
    assertThat(VectorChecks.isValid(fixed, 768)).isTrue()
    assertThat(VectorChecks.cosine(fixed, base)).isWithin(1e-6f).of(1f)
    assertThat(VectorChecks.normalizedOrNull(base, 768)).isSameInstanceAs(base) // gia' valido: lo stesso
    assertThat(VectorChecks.normalizedOrNull(base.map { it * 1.09f }.toFloatArray(), 768)).isNotNull()
    assertThat(VectorChecks.normalizedOrNull(base.map { it * 1.2f }.toFloatArray(), 768)).isNull()
    assertThat(VectorChecks.normalizedOrNull(base.map { it * 0.8f }.toFloatArray(), 768)).isNull()
    assertThat(VectorChecks.normalizedOrNull(FloatArray(768), 768)).isNull()
    assertThat(VectorChecks.normalizedOrNull(FloatArray(768) { Float.NaN }, 768)).isNull()
    assertThat(VectorChecks.normalizedOrNull(base, 767)).isNull()
  }

  @Test
  fun ogniFormaDiVettoreSporcoESpiegata() {
    assertThat(VectorChecks.verdict(FloatArray(767), 768)).isEqualTo(VectorChecks.Verdict.WRONG_DIMENSION)
    assertThat(VectorChecks.verdict(FloatArray(0), 768)).isEqualTo(VectorChecks.Verdict.WRONG_DIMENSION)
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
      assertThat(VectorChecks.verdict(FloatArray(768) { if (it == 767) bad else 0.036f }, 768)).isEqualTo(VectorChecks.Verdict.NOT_FINITE)
    }
    assertThat(VectorChecks.verdict(FloatArray(768), 768)).isEqualTo(VectorChecks.Verdict.BAD_NORM)
    assertThat(VectorChecks.verdict(FloatArray(768) { 1f }, 768)).isEqualTo(VectorChecks.Verdict.BAD_NORM)
  }

  @Test
  fun ilCosenoDiVettoriUgualiOppostiEOrtogonali() {
    val a = FakeSessionFactory.unitVector("a")
    assertThat(VectorChecks.cosine(a, a)).isWithin(1e-5f).of(1f)
    assertThat(VectorChecks.cosine(a, a.map { -it }.toFloatArray())).isWithin(1e-5f).of(-1f)
    assertThat(VectorChecks.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))).isWithin(1e-6f).of(0f)
    assertThat(VectorChecks.cosine(a, FloatArray(768))).isEqualTo(0f)
    assertThat(VectorChecks.cosine(a, FloatArray(767))).isEqualTo(0f)
    assertThat(VectorChecks.cosine(FloatArray(0), FloatArray(0))).isEqualTo(0f)
  }

  // --- Le 20 immagini sintetiche ----------------------------------------------------------------------------------------------

  @Test
  fun leScenePerLaMisuraSonoVenteDiversePerCostruzione() {
    assertThat(ProbeImages.COUNT).isEqualTo(20)
    val hashes = (0 until ProbeImages.COUNT).map { index ->
      val px = ProbeImages.pixels(index)
      assertThat(px.size).isEqualTo(ProbeImages.WIDTH * ProbeImages.HEIGHT)
      assertThat(px.all { (it ushr 24) == 0xFF }).isTrue() // opache
      px.contentHashCode()
    }
    assertThat(hashes.toSet()).hasSize(20)
  }

  @Test
  fun leScenePiuStessiPixelADogniChiamata() {
    for (index in 0 until ProbeImages.COUNT) {
      assertThat(ProbeImages.pixels(index).contentEquals(ProbeImages.pixels(index))).isTrue()
    }
  }

  @Test
  fun unaSceneNonEUnTintaUnitaENonTuttePesanoUguale() {
    for (index in 0 until ProbeImages.COUNT) {
      val px = ProbeImages.pixels(index)
      assertThat(px.toSet().size).isAtLeast(2) // ogni scena ha almeno due colori
    }
    // Il pixel in alto a sinistra non e' lo stesso per tutte (non sono la stessa scena con un ritocco).
    assertThat((0 until ProbeImages.COUNT).map { ProbeImages.pixels(it)[0] }.toSet().size).isAtLeast(10)
  }

  @Test(expected = IllegalArgumentException::class)
  fun unaSceneFuoriIntervalloEUnErrore() {
    ProbeImages.pixels(ProbeImages.COUNT)
  }

  // --- La misura breve ----------------------------------------------------------------------------------------------------------

  private class SlowEmbedder(private val clock: Clock, private val msPerImage: Long, override val backend: StateFlow<AiBackend>) : ImageEmbedder {
    var firstCallExtraMs = 0L
    var calls = 0

    override suspend fun embed(images: List<ModelImage>): List<FloatArray> {
      clock.now += (if (calls++ == 0) firstCallExtraMs else 0L) * 1_000_000 + msPerImage * images.size * 1_000_000
      return images.map { FakeSessionFactory.unitVector("x") }
    }

    override suspend fun release() = Unit
  }

  private class Clock {
    var now = 0L
  }

  private val twenty = List(20) { AiTestInputs.image("scena $it") }

  @Test
  fun laMisuraDaFotoAlSecondoBackendEAvvio() = runTest {
    val clock = Clock()
    val embedder = SlowEmbedder(clock, msPerImage = 250, backend = MutableStateFlow(AiBackend.GPU)).apply { firstCallExtraMs = 3_400 }
    val result = RealDeviceProbe(embedder, nanos = { clock.now }, images = { twenty }).measure()
    assertThat(result.imagesPerSecond).isWithin(1e-6).of(4.0) // 20 immagini in 5 s
    assertThat(result.backend).isEqualTo(AiBackend.GPU)
    assertThat(result.coldStartMs).isEqualTo(3_400 + 250)
    // 18.000 media a 4 foto/s = 75 minuti: una notte.
    assertThat(result.nightsFor(18_000)).isEqualTo(1)
  }

  @Test
  fun valeIlGiroPiuVeloceEIlBackendCheAlloraAvevaVinto() = runTest {
    val clock = Clock()
    var perImage = 400L
    val backend = MutableStateFlow(AiBackend.GPU)
    val embedder = object : ImageEmbedder {
      override val backend: StateFlow<AiBackend> = backend
      override suspend fun embed(images: List<ModelImage>): List<FloatArray> {
        clock.now += perImage * images.size * 1_000_000
        perImage = 200L // dopo il primo giro (e dopo l'avvio) va piu' veloce
        return images.map { FloatArray(768) }
      }

      override suspend fun release() = Unit
    }
    val result = RealDeviceProbe(embedder, nanos = { clock.now }, rounds = 3, images = { twenty }).measure()
    assertThat(result.imagesPerSecond).isWithin(1e-6).of(5.0)
  }

  @Test
  fun unGuastoDelMotoreEsceCosiCom() = runTest {
    val probe = RealDeviceProbe(FailingEmbedder, images = { twenty })
    val thrown = runCatching { probe.measure() }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(dev.pampa.mycloudgallery.core.ai.contract.AiEngineException::class.java)
  }

  private object FailingEmbedder : ImageEmbedder {
    override val backend: StateFlow<AiBackend> = MutableStateFlow(AiBackend.CPU)

    override suspend fun embed(images: List<ModelImage>): List<FloatArray> =
      throw dev.pampa.mycloudgallery.core.ai.contract.AiEngineException(dev.pampa.mycloudgallery.core.ai.contract.AiEngineError.MODEL_UNAVAILABLE)

    override suspend fun release() = Unit
  }

  @Test
  fun ilFakeDelleImmaginiFaLaMisuraSenzaCambiarla() = runTest {
    val result = RealDeviceProbe(FakeImageEmbedder(), images = { twenty }).measure()
    assertThat(result.imagesPerSecond).isGreaterThan(0.0)
    assertThat(result.backend).isEqualTo(AiBackend.GPU)
  }
}
