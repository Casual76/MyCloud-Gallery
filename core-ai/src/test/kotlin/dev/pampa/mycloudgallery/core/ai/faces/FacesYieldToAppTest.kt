package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.rules.run.ThreadPriorities
import dev.pampa.mycloudgallery.core.rules.run.YieldToApp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * Review generale, OB-3: i motori dei volti dentro un lavoro di fondo che si fa da parte ([YieldToApp]). I thread di inferenza di un
 * interprete LiteRT si scelgono all'apertura e non si cambiano dopo, e il suo pool nativo nasce con la priorita' del thread che lo apre:
 * quando lo stato dell'app cambia a meta' tappa (l'utente apre l'app mentre il calcolo gira, o la chiude) la sessione si **riapre**, una
 * volta sola, e non si riapre a ogni richiesta. Fuori da un lavoro di fondo niente cambia. Sessioni finte (`FakeFaceSessions`): quanti
 * thread passano all'interprete lo prova `FaceEnginesFactoryTest`.
 */
class FacesYieldToAppTest {

  private val sessions = FakeFaceSessions()
  private val inUse = AtomicBoolean(false)

  /** Le priorita' dei thread dei motori (per nome), come le lascia l'elemento mentre il motore gira. */
  private class Recorded : ThreadPriorities {
    val seen = ConcurrentHashMap<String, Int>()

    override fun current(): Int? = 0

    override fun set(priority: Int) {
      // Senza il segno che le coroutine aggiungono al nome del thread nei test (" @coroutine#12").
      if (priority != 0) seen[Thread.currentThread().name.substringBefore(" @")] = priority
    }
  }

  private val priorities = Recorded()

  private fun work() = YieldToApp({ inUse.get() }, priorities)

  private val image = PixelFaceWorld.render(PixelScene(640, 480, listOf(PixelFace(1, 100, 100, 120, 150))))

  private fun face(identity: Int): AlignedFace {
    val c = PixelFaceWorld.colorOf(identity)
    val side = FaceEngineLimits.ALIGNED_SIDE
    return AlignedFace(IntArray(side * side) { i -> (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or (c[2] + (i % 5) - 2) })
  }

  @Test
  fun seLAppEntraInUsoAMetaTappaIlRilevatoreSiRiapreUnaVolta_eDiNuovoQuandoTornaInSecondoPiano() = runTest {
    val detector = YuNetDetector(sessions.detectorOpener)
    withContext(work()) {
      repeat(3) { assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1) }
      assertThat(sessions.detectorOpens.get()).isEqualTo(1)

      // L'utente apre l'app: la sessione di prima (tutti i thread, priorita' piena) si chiude e se ne apre una che si fa da parte.
      inUse.set(true)
      repeat(3) { assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1) }
      assertThat(sessions.detectorOpens.get()).isEqualTo(2)
      assertThat(sessions.detectorCloses.get()).isEqualTo(1)
      // Il thread del motore gira a priorita' di fondo (e il pool nativo dell'interprete, che nasce li', la eredita).
      assertThat(priorities.seen["mcg-face-detect"]).isEqualTo(YieldToApp.YIELDING_PRIORITY)

      // L'utente chiude l'app: si torna a una sessione a ritmo pieno.
      inUse.set(false)
      repeat(3) { assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1) }
      assertThat(sessions.detectorOpens.get()).isEqualTo(3)
      assertThat(sessions.detectorCloses.get()).isEqualTo(2)
    }
    detector.release()
    assertThat(sessions.detectorCloses.get()).isEqualTo(3)
  }

  @Test
  fun loStessoPerLEmbedder() = runTest {
    val embedder = SFaceEmbedder(sessions.embedderOpener)
    withContext(work()) {
      repeat(2) { assertThat(embedder.embed(listOf(face(1), face(2))).filterNotNull()).hasSize(2) }
      assertThat(sessions.embedderOpens.get()).isEqualTo(1)
      inUse.set(true)
      repeat(2) { assertThat(embedder.embed(listOf(face(1))).filterNotNull()).hasSize(1) }
      assertThat(sessions.embedderOpens.get()).isEqualTo(2)
      assertThat(sessions.embedderCloses.get()).isEqualTo(1)
      assertThat(priorities.seen["mcg-face-embed"]).isEqualTo(YieldToApp.YIELDING_PRIORITY)
      inUse.set(false)
      embedder.embed(listOf(face(1)))
      assertThat(sessions.embedderOpens.get()).isEqualTo(3)
    }
    embedder.release()
    assertThat(sessions.embedderCloses.get()).isEqualTo(3)
  }

  @Test
  fun fuoriDaUnLavoroDiFondoIMotoriNonSiRiapronoMai_ancheConLAppInUso() = runTest {
    // La ricerca per volto o una pagina che chiede un volto adesso: cio' che l'utente aspetta non si fa da parte.
    val detector = YuNetDetector(sessions.detectorOpener)
    val embedder = SFaceEmbedder(sessions.embedderOpener)
    for (state in listOf(true, false, true)) {
      inUse.set(state)
      detector.detect(image, DetectPass.PREVIEW)
      embedder.embed(listOf(face(1)))
    }
    assertThat(sessions.detectorOpens.get()).isEqualTo(1)
    assertThat(sessions.embedderOpens.get()).isEqualTo(1)
    assertThat(priorities.seen).isEmpty()
    detector.release()
    embedder.release()
  }
}
