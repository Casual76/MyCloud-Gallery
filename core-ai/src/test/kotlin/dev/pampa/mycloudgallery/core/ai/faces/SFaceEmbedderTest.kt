package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P2): l'embedder vero (`SFaceEmbedder`) sopra la sessione finta: ingresso RGB 0-255 nell'ordine giusto, vettori normalizzati in float32,
 * un vettore non valido e' null, un volto che fa lanciare l'interprete non fa perdere gli altri (§2.2), un motore che non va piu' e' un codice,
 * richieste in fila, rilascio e riapertura.
 */
class SFaceEmbedderTest {

  private val sessions = FakeFaceSessions()
  private val embedder = SFaceEmbedder(sessions.embedderOpener)

  /** Un volto allineato tutto del colore dell'identita' (con un po' di grana). */
  private fun face(identity: Int): AlignedFace {
    val c = PixelFaceWorld.colorOf(identity)
    val side = FaceEngineLimits.ALIGNED_SIDE
    return AlignedFace(IntArray(side * side) { i -> (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or (c[2] + (i % 5) - 2) })
  }

  @Test
  fun losSpazioEQuelloCorrenteEIVettoriSonoNormalizzatiEDeterministici() = runTest {
    assertThat(embedder.space).isEqualTo(FaceSpaceCode.CURRENT)
    val faces = listOf(face(3), face(4), face(3))
    val vectors = embedder.embed(faces)
    assertThat(vectors).hasSize(3)
    for (v in vectors) FaceEnginesContract.checkVector(v!!)
    // Lo stesso volto dà lo stesso vettore; due identita' diverse, vettori lontani.
    assertThat(SyntheticIdentities.cosine(vectors[0]!!, vectors[2]!!)).isAtLeast(0.999f)
    assertThat(SyntheticIdentities.cosine(vectors[0]!!, vectors[1]!!)).isLessThan(0.5f)
    assertThat(embedder.embed(emptyList())).isEmpty()
    assertThat(sessions.embedderOpens.get()).isEqualTo(1)
  }

  @Test
  fun lIngressoEInRgbNellOrdineDelleRigheEDeiCanali() {
    val side = FaceEngineLimits.ALIGNED_SIDE
    val pixels = IntArray(side * side) { 0xFF000000.toInt() }
    pixels[0] = 0xFF010203.toInt() // R=1, G=2, B=3
    pixels[1] = 0xFFFA0B0C.toInt()
    pixels[side] = 0xFF112233.toInt()
    val input = FaceVectors.input(AlignedFace(pixels))
    assertThat(input).hasLength(side * side * 3)
    assertThat(input.copyOfRange(0, 6).toList()).containsExactly(1f, 2f, 3f, 250f, 11f, 12f).inOrder()
    assertThat(input.copyOfRange(side * 3, side * 3 + 3).toList()).containsExactly(17f, 34f, 51f).inOrder()
    assertThat(input.copyOfRange(6, 9).toList()).containsExactly(0f, 0f, 0f).inOrder()
  }

  @Test
  fun ilVettoreGrezzoSiNormalizzaEUnVettoreCheNonVaENull() {
    val raw = FloatArray(128) { if (it % 2 == 0) 1f else -2f }
    val v = FaceVectors.normalizedOrNull(raw)!!
    FaceEnginesContract.checkVector(v)
    assertThat(v[0] / v[1]).isWithin(1e-5f).of(-0.5f)
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128))).isNull() // tutti zeri
    assertThat(FaceVectors.normalizedOrNull(FloatArray(127) { 1f })).isNull() // dimensione sbagliata
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128) { if (it == 5) Float.NaN else 1f })).isNull()
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128) { if (it == 5) Float.POSITIVE_INFINITY else 1f })).isNull()
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128) { 1e6f })).isNull() // norma assurda
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128) { 1e-6f })).isNull() // norma quasi nulla
    // Un'uscita ordinaria (norma 11-12) passa.
    assertThat(FaceVectors.normalizedOrNull(FloatArray(128) { 1.05f })).isNotNull()
  }

  @Test
  fun unVettoreNonFinitoELasciaIlVoltoSenzaVettore_GliAltriNo() = runTest {
    sessions.nanIdentities += 4
    val vectors = embedder.embed(listOf(face(3), face(4), face(5)))
    assertThat(vectors.map { it != null }).containsExactly(true, false, true).inOrder()
    assertThat(embedder.invalid).isEqualTo(1)
  }

  @Test
  fun unInterpreteCheLanciaSuUnVoltoNonFaPerdereGliAltri() = runTest {
    sessions.embedderFailOnRuns += 2
    val vectors = embedder.embed(listOf(face(3), face(4), face(5), face(6)))
    assertThat(vectors.map { it != null }).containsExactly(true, false, true, true).inOrder()
    assertThat(embedder.thrown).isEqualTo(1)
    assertThat(sessions.embedderRuns.get()).isEqualTo(4) // un volto alla volta: quattro corse, mai un lotto
    assertThat(sessions.embedderCloses.get()).isEqualTo(0) // la sessione e' ancora buona
    // E i vettori buoni sono quelli che sarebbero stati senza il guasto.
    val clean = SFaceEmbedder(FakeFaceSessions(sessions.identities).embedderOpener).embed(listOf(face(3), face(4), face(5), face(6)))
    for (i in listOf(0, 2, 3)) assertThat(SyntheticIdentities.cosine(vectors[i]!!, clean[i]!!)).isAtLeast(0.999f)
  }

  @Test
  fun treGuastiDiFilaSonoUnMotoreCheNonVaPiu_SiChiudeEQuelloDopoSiRiapre() = runTest {
    sessions.embedderFailOnRuns += setOf(1, 2, 3)
    val error = runCatching { embedder.embed(listOf(face(3), face(4), face(5), face(6))) }.exceptionOrNull() as AiEngineException
    assertThat(error.error).isEqualTo(AiEngineError.BAD_INPUT)
    assertThat(sessions.embedderCloses.get()).isEqualTo(1)
    assertThat(embedder.embed(listOf(face(3))).single()).isNotNull()
    assertThat(sessions.embedderOpens.get()).isEqualTo(2)
  }

  @Test
  fun unaCorsaBuonaInMezzoAzzeraLaConta() = runTest {
    sessions.embedderFailOnRuns += setOf(1, 2, 4, 5)
    // Due guasti, un buono, due guasti, un buono: mai tre di fila.
    val vectors = embedder.embed(listOf(face(3), face(4), face(5), face(6), face(7), face(8)))
    assertThat(vectors.map { it != null }).containsExactly(false, false, true, false, false, true).inOrder()
    assertThat(sessions.embedderCloses.get()).isEqualTo(0)
  }

  @Test
  fun laMemoriaFinitaELaLibreriaCheNonCaricaSonoDelMotoreIntero() = runTest {
    sessions.failure = { OutOfMemoryError() }
    sessions.embedderFailOnRuns += 2
    val error = runCatching { embedder.embed(listOf(face(3), face(4), face(5))) }.exceptionOrNull() as AiEngineException
    assertThat(error.error).isEqualTo(AiEngineError.OUT_OF_MEMORY)
    assertThat(sessions.embedderCloses.get()).isEqualTo(1)
    sessions.failure = { UnsatisfiedLinkError() }
    sessions.embedderFailOnRuns += 3 // la prima corsa della sessione nuova e' la numero 3
    assertThat((runCatching { embedder.embed(listOf(face(3))) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.INIT_FAILED)
  }

  @Test
  fun unModelloCheNonSiApreEUnCodice_ECheTornaSiRiprova() = runTest {
    sessions.embedderOpenFailure = AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    assertThat((runCatching { embedder.embed(listOf(face(3))) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
    sessions.embedderOpenFailure = null
    assertThat(embedder.embed(listOf(face(3))).single()).isNotNull()
  }

  @Test
  fun leRichiesteInParallelaSiMettonoInFilaEIlRilascioEIdempotente() = runTest {
    coroutineScope { (0 until 10).map { i -> async { embedder.embed(listOf(face(i), face(i + 1))).size } }.awaitAll() }
    assertThat(sessions.maxConcurrent.get()).isEqualTo(1)
    assertThat(sessions.embedderOpens.get()).isEqualTo(1)
    embedder.release()
    embedder.release()
    assertThat(sessions.embedderCloses.get()).isEqualTo(1)
    assertThat(embedder.embed(listOf(face(3))).single()).isNotNull()
    assertThat(sessions.embedderOpens.get()).isEqualTo(2)
  }
}
