package dev.pampa.mycloudgallery.feature.viewer.motion

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.testing.PlayerCall
import dev.pampa.mycloudgallery.feature.viewer.video.MotionMode
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackClip
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'animazione d'apertura e il video dal badge con il lettore finto e un pager finto, a tempo virtuale (D50, D51, E-V14, E-V16, E-V17).
 * Il lettore e' uno solo: ogni prova guarda cosa e' stato chiesto al `FakeViewerPlayer`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MotionDirectorTest {

  /** Un pager finto: il test emette dove sta e se si muove; il direttore lo segue come segue il pager vero. */
  private class Pager(val flow: MutableSharedFlow<PageSettle> = MutableSharedFlow(extraBufferCapacity = 64))

  private fun TestScope.start(h: MotionHarness): Pager {
    val pager = Pager()
    backgroundScope.launch { h.director.watch(pager.flow) }
    runCurrent()
    return pager
  }

  private suspend fun Pager.settle(h: MotionHarness, index: Int) = flow.emit(PageSettle(h.ids[index], moving = false))

  private suspend fun Pager.move(h: MotionHarness, index: Int) = flow.emit(PageSettle(h.ids[index], moving = true))

  private fun TestScope.arrive(h: MotionHarness) {
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
  }

  @Test
  fun iltrattoSiTagliaDaIstanteMenoUnVirgolaDueSecondiAIstante_Muto_UnaPartenzaPerArrivo() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    // Prima di 150 ms di pager fermo non parte niente.
    advanceTimeBy(MotionDirector.SETTLE_MS - 1)
    runCurrent()
    assertThat(h.player.loads).isEmpty()
    arrive(h)

    val load = h.player.loads.single()
    // L'istante della foto e' a 2 s: il tratto e' [800 ms, 2.000 ms].
    assertThat(load.clip).isEqualTo(PlaybackClip(startMs = 800L, endMs = 2_000L))
    assertThat(load.muted).isTrue()
    assertThat(load.autoplay).isTrue()
    assertThat(load.source).isEqualTo(PlaybackSource.Range(h.uriOf(0), start = 1_000L, length = 4_000L))
    assertThat(h.playback.motionFor).isEqualTo(h.ids[0])
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.OPENING)

    // Si va via e si torna: una partenza per ogni arrivo sulla pagina ferma.
    pager.move(h, 0)
    runCurrent()
    assertThat(h.playback.motionFor).isNull()
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.player.loads).hasSize(2)
  }

  @Test
  fun lAnimazioneNonRipartePerLaStessaPaginaFerma_E_V14() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    // Chiudere le info o tornare dallo zoom non cambia (pagina, movimento): il flusso non emette e non riparte.
    advanceTimeBy(5_000)
    runCurrent()
    assertThat(h.player.loads).hasSize(1)
  }

  @Test
  fun nessunaPartenzaSulleCartellePagineAttraversateDaUnoScorrimentoVeloce_E_V14() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    advanceTimeBy(100)
    runCurrent()
    // Si scorre via prima dei 150 ms, attraversando altre pagine senza mai fermarsi.
    for (page in 1..9) {
      pager.move(h, page)
      advanceTimeBy(40)
      runCurrent()
    }
    assertThat(h.player.loads).isEmpty()
    assertThat(h.playback.created).isEqualTo(0)
    pager.settle(h, 9)
    arrive(h)
    assertThat(h.player.loads.map { it.source }).containsExactly(PlaybackSource.Range(h.uriOf(9), 1_000L, 4_000L))
    assertThat(h.created).isEqualTo(1)
  }

  @Test
  fun unTocco_UnPizzico_UnDoppioTocco_FermanoSubitoLAnimazione() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.playback.motionFor).isEqualTo(h.ids[0])

    // Il controller chiama questo a ogni primo dito giu': vale per il tocco, per il pizzico e per il doppio tocco.
    h.director.interruptOpening()
    assertThat(h.playback.motionFor).isNull()
    assertThat(h.player.calls.last()).isEqualTo(PlayerCall.Stop)
    // Il decoder e' libero ma il lettore e' lo stesso: nessuno nuovo.
    assertThat(h.created).isEqualTo(1)
  }

  @Test
  fun ilTocco_NonFermaIlVideoIntero_DelBadge() = runTest {
    val h = MotionHarness(backgroundScope)
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.FULL)
    h.director.interruptOpening()
    assertThat(h.playback.motionFor).isEqualTo(h.ids[0])
  }

  @Test
  fun unaPaginaCheCambiaFermaTutto_AncheIlVideoDelBadge() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.playback.motionFor).isEqualTo(h.ids[0])
    pager.move(h, 1)
    runCurrent()
    assertThat(h.playback.motionFor).isNull()
  }

  @Test
  fun quandoLAnimazioneNonParte_NonCEUnaChiamataAlLettore() = runTest {
    for (case in listOf("interruttore spento", "rimuovi animazioni", "risparmio energetico")) {
      val h = MotionHarness(backgroundScope)
      when (case) {
        "interruttore spento" -> h.settingOn = false
        "rimuovi animazioni" -> h.reducedMotion = true
        else -> h.powerSaving = true
      }
      val pager = start(h)
      pager.settle(h, 0)
      arrive(h)
      assertWithMessage(case).that(h.player.calls).isEmpty()
      assertWithMessage(case).that(h.created).isEqualTo(0)
      // Il badge funziona sempre, anche con le tre impostazioni: il video intero parte.
      h.director.playFull(h.ids[0])
      runCurrent()
      assertThat(h.player.loads.single().muted).isFalse()
    }
  }

  @Test
  fun leProporzioniDiverseOltreIlTrePerCentoFermanoLAnimazione_E_V17() = runTest {
    val h = MotionHarness(backgroundScope)
    h.photoAspect = 1.5f
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    // Il lettore dice la proporzione del video (gia' con la rotazione della traccia): 1,6 contro 1,5 = 6,7%.
    h.player.emit { it.copy(videoWidth = 1_600, videoHeight = 1_000) }
    runCurrent()
    assertThat(h.playback.motionFor).isNull()

    // Entro il 3% (1,54 contro 1,5 = 2,7%) l'animazione continua.
    pager.move(h, 1)
    runCurrent()
    pager.settle(h, 1)
    arrive(h)
    h.player.emit { it.copy(videoWidth = 1_540, videoHeight = 1_000) }
    runCurrent()
    assertThat(h.playback.motionFor).isEqualTo(h.ids[1])
  }

  @Test
  fun aFineTrattoLaFotoTornaConLaDissolvenzaEIlDecoderSiLibera() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    h.player.emit { it.copy(videoWidth = 1_500, videoHeight = 1_000) }
    runCurrent()
    h.player.emit { it.copy(ended = true, playing = false) }
    runCurrent()
    assertThat(h.playback.motionFading).isTrue()
    assertThat(h.playback.motionFor).isEqualTo(h.ids[0])
    advanceTimeBy(MotionDirector.FADE_MS + 1)
    runCurrent()
    assertThat(h.playback.motionFor).isNull()
    assertThat(h.playback.motionFading).isFalse()
  }

  @Test
  fun unSoloDecoderDopoCinquantaPagine_IlPrecaricoNonNeCrea() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    for (page in 0 until 50) {
      pager.settle(h, page)
      arrive(h)
      pager.move(h, minOf(page + 1, 59))
      runCurrent()
    }
    assertThat(h.player.loads).hasSize(50)
    assertThat(h.created).isEqualTo(1)
    assertThat(h.playback.created).isEqualTo(1)
    assertThat(h.player.released).isFalse()
    // Il precarico ha cercato intervallo e istante delle vicine (solo byte), senza avviare niente per loro.
    assertThat(h.locator.requests.toSet().size).isAtLeast(50)
  }

  @Test
  fun unaFotoNonInMovimentoNonFaNiente() = runTest {
    val h = MotionHarness(backgroundScope)
    h.kinds[h.ids[0]] = MediaKind.PHOTO
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.player.calls).isEmpty()
    assertThat(h.locator.requests).isEmpty()
  }

  @Test
  fun e_M16_formatoNonRiconosciuto_NienteAnimazioneNeBadge() = runTest {
    val h = MotionHarness(backgroundScope, recognized = false)
    val unknown = h.ids[0]
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.player.calls).isEmpty()
    assertThat(h.unrecognized).containsExactly(unknown)
    // Nemmeno dal badge: la nota sta nelle informazioni, il video non c'e'.
    h.director.playFull(unknown)
    runCurrent()
    assertThat(h.player.calls).isEmpty()
  }

  @Test
  fun senzaIstanteDellaFoto_NienteAnimazioneMaIlBadgeFunziona() = runTest {
    val h = MotionHarness(
      backgroundScope,
      found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = null),
    )
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.player.calls).isEmpty()
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.player.loads).hasSize(1)
  }

  // --- Il video intero dal badge (D51) ----------------------------------------------------------------------------------

  @Test
  fun ilBadgeRiproduceIlVideoIntero_ConLAudio_UnaVolta_PoiTornaLaFoto() = runTest {
    val h = MotionHarness(backgroundScope)
    h.director.playFull(h.ids[0])
    runCurrent()
    val load = h.player.loads.single()
    // Audio acceso, nessun taglio: il video intero. Il focus audio lo prende il lettore (non muto, traccia attiva, contenuti multimediali).
    assertThat(load.muted).isFalse()
    assertThat(load.clip).isNull()
    assertThat(load.source).isEqualTo(PlaybackSource.Range(h.uriOf(0), start = 1_000L, length = 4_000L))
    assertThat(h.player.state.value.hasAudio).isTrue()
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.FULL)

    h.player.emit { it.copy(ended = true, playing = false) }
    runCurrent()
    assertThat(h.playback.motionFading).isTrue()
    advanceTimeBy(MotionDirector.FADE_MS + 1)
    runCurrent()
    assertThat(h.playback.motionFor).isNull()
    // Una volta sola: non riparte da sola.
    advanceTimeBy(10_000)
    runCurrent()
    assertThat(h.player.loads).hasSize(1)
  }

  @Test
  fun unAltroTocco_SulBadge_LoFerma() = runTest {
    val h = MotionHarness(backgroundScope)
    h.director.playFull(h.ids[0])
    runCurrent()
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.playback.motionFor).isNull()
    assertThat(h.player.calls.last()).isEqualTo(PlayerCall.Stop)
    assertThat(h.player.loads).hasSize(1)
  }

  @Test
  fun ilBadgeDuranteLAnimazione_LaSostituisceConIlVideoIntero() = runTest {
    val h = MotionHarness(backgroundScope)
    val pager = start(h)
    pager.settle(h, 0)
    arrive(h)
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.OPENING)
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.FULL)
    assertThat(h.player.loads.map { it.muted }).containsExactly(true, false).inOrder()
    assertThat(h.created).isEqualTo(1)
  }
}
