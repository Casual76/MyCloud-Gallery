package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.noop.NoFaceEnginesFactory
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import dev.pampa.mycloudgallery.core.ai.testing.FakeFaceEngines
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticFace
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentity
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticScene
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0, §5.2): i motori dei volti finti passano la suite di contratto dei motori veri e sono fedeli dove conta per i pacchetti dopo
 * (P4 calcolo, P5 raggruppamento, P11 valutazione): identita' separabili, gemelli vicini, seconda passata, livelli di D19, guasti.
 */
class FakeFaceEnginesTest {

  private val identities = SyntheticIdentities(seed = 21).define(SyntheticIdentity(id = 2, twinOf = 1, twinCosine = 0.75f))
  private val fake = FakeFaceEngines(identities)

  private fun portrait(id: Int, identity: Int, size: Float = 0.4f, yaw: Int = 0, sharpness: Int = 200) =
    SyntheticScene(id, 640, 480, listOf(SyntheticFace(identity, 0.3f, 0.2f, 0.3f + size * 0.75f, 0.2f + size, yaw = yaw, sharpness = sharpness)))

  private val group = SyntheticScene(
    100, 1024, 768,
    listOf(
      SyntheticFace(1, 0.10f, 0.10f, 0.30f, 0.40f),
      SyntheticFace(3, 0.40f, 0.10f, 0.60f, 0.40f),
      // Un volto piccolo (9x8 px a 1024): solo la seconda passata sull'HQ lo trova (D17).
      SyntheticFace(4, 0.80f, 0.80f, 0.809f, 0.81f),
    ),
  )

  @Test
  fun iMotoriFintiPassanoLaSuiteDiContratto() = runTest {
    val images = listOf(fake.image(portrait(1, 1)), fake.image(portrait(2, 3)), fake.image(group))
    val empty = SyntheticScene(3, 320, 240, emptyList())
    FaceEnginesContract.verify(fake.engines, images, withoutFaces = listOf(fake.image(empty)))
    assertThat(fake.maxConcurrent.get()).isEqualTo(1)
  }

  @Test
  fun stessaIdentitaPiuVicinaDiPersoneDiverse_eIGemelliInMezzo() {
    val same = (0 until 50).map { SyntheticIdentities.cosine(identities.vector(1, it.toLong(), 0.9f), identities.vector(1, it + 1000L, 0.9f)) }
    val strangers = (0 until 50).map { SyntheticIdentities.cosine(identities.vector(1, it.toLong(), 0.9f), identities.vector(10 + it, it.toLong(), 0.9f)) }
    val twins = (0 until 50).map { SyntheticIdentities.cosine(identities.vector(1, it.toLong(), 0.9f), identities.vector(2, it.toLong(), 0.9f)) }
    assertThat(same.average()).isGreaterThan(0.45)
    assertThat(strangers.average()).isLessThan(0.1)
    assertThat(twins.average()).isGreaterThan(strangers.average() + 0.2)
    assertThat(twins.average()).isLessThan(same.average())
    // Deterministici e passati per float16 con norma 1.
    FaceEnginesContract.checkVector(identities.vector(1, 7, 0.9f))
    assertThat(identities.vector(1, 7, 0.9f)).isEqualTo(SyntheticIdentities(seed = 21).define(SyntheticIdentity(2, twinOf = 1, twinCosine = 0.75f)).vector(1, 7, 0.9f))
  }

  @Test
  fun laSecondaPassataTrovaIVoltiPiccoli() = runTest {
    val preview = fake.detector.detect(fake.image(group), DetectPass.PREVIEW)
    val hq = fake.detector.detect(fake.image(group, longSide = 2048), DetectPass.HQ_TILES)
    assertThat(preview).hasSize(2)
    assertThat(hq).hasSize(3)
  }

  @Test
  fun iLivelliSeguonoD19SenzaAttributi() = runTest {
    suspend fun tierOf(scene: SyntheticScene): Pair<FaceTier, Int> {
      val image = fake.image(scene)
      val face = fake.detector.detect(image, DetectPass.HQ_TILES).single()
      val verdict = fake.quality.assess(face, face.box.shortSide.toInt(), fake.aligner.align(image, face.landmarks))
      return verdict.tier to verdict.reasons
    }
    assertThat(tierOf(portrait(10, 1)).first).isEqualTo(FaceTier.CORE)
    assertThat(tierOf(portrait(11, 1, size = 0.08f)).first).isEqualTo(FaceTier.WEAK)
    assertThat(tierOf(portrait(12, 1, size = 0.04f)).first).isEqualTo(FaceTier.NONE)
    assertThat(tierOf(portrait(13, 1, yaw = 50)).let { it.first to (it.second and FaceReasons.PROFILE) }).isEqualTo(FaceTier.WEAK to FaceReasons.PROFILE)
    assertThat(tierOf(portrait(14, 1, yaw = 80)).first).isEqualTo(FaceTier.NONE)
    assertThat(tierOf(portrait(15, 1, sharpness = 20)).let { it.first to (it.second and FaceReasons.BLURRY) }).isEqualTo(FaceTier.WEAK to FaceReasons.BLURRY)
  }

  @Test
  fun iGuastiSonoCodiciEUnVoltoSenzaVettoreNonFermaGliAltri() = runTest {
    val image = fake.image(group)
    val faces = fake.detector.detect(image, DetectPass.PREVIEW)
    val aligned = faces.map { fake.aligner.align(image, it.landmarks) }
    fake.invalidVectors += Triple(group.id, 0, 1)
    val vectors = fake.embedder.embed(aligned)
    assertThat(vectors.count { it == null }).isEqualTo(1)
    assertThat(vectors.count { it != null }).isEqualTo(1)
    fake.detectFailures[group.id] = AiEngineError.OUT_OF_MEMORY
    val error = runCatching { fake.detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull()
    assertThat((error as AiEngineException).error).isEqualTo(AiEngineError.OUT_OF_MEMORY)
    fake.embedderDown = AiEngineError.INIT_FAILED
    assertThat(runCatching { fake.embedder.embed(aligned) }.exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    // In parallelo si servono in fila.
    fake.embedderDown = null
    fake.detectFailures.clear()
    coroutineScope { (0 until 8).map { async { fake.detector.detect(image, DetectPass.PREVIEW) } }.awaitAll() }
    assertThat(fake.maxConcurrent.get()).isEqualTo(1)
  }

  /** I null-object falliscono chiusi: nessun "analizzato, nessun volto" scritto senza motore. */
  @Test
  fun senzaMotoriNienteGiraENienteSiDichiara() = runTest {
    val engines = NoFaceEnginesFactory.ENGINES
    val image = fake.image(group)
    val detect = runCatching { engines.detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException
    assertThat(detect.error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
    assertThrows(AiEngineException::class.java) { engines.aligner.align(image, FloatArray(10)) }
    assertThat(BufferKeepers.NONE.stillWanted(listOf(ContentKey("ab".repeat(32))))).isEmpty()
  }
}
