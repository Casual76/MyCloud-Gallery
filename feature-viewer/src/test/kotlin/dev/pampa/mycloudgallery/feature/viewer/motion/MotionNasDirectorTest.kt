package dev.pampa.mycloudgallery.feature.viewer.motion

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.feature.viewer.MotionTestFiles
import dev.pampa.mycloudgallery.feature.viewer.video.MotionMode
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackClip
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le foto in movimento solo-NAS nel viewer (D51, E-V15): l'animazione parte solo con la rete aperta e se la parte video e' pronta entro 1,5 s
 * (orologio finto), sui dati mobili non si legge niente e il badge chiede conferma, con la VPN spenta o senza rete il badge dice "non disponibile ora".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MotionNasDirectorTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val photo = MotionTestFiles.google(MotionTestFiles.mp4(durationMs = 4_000L), declaredUs = 1_500_000L)

  /** Cinque media del NAS (le posizioni da 1 a 5) con la stessa foto in movimento, e un direttore che li conosce. */
  private data class Rig(val nas: MotionNasRig, val h: MotionHarness)

  private fun TestScope.rig(): Rig {
    val nas = MotionNasRig(tmp.root, testScheduler)
    val h = MotionHarness(backgroundScope, count = 5, phone = false, nasMotion = nas.source)
    h.ids.indices.forEach { nas.add(it + 1L, photo) }
    return Rig(nas, h)
  }

  private fun TestScope.watch(h: MotionHarness): MutableSharedFlow<PageSettle> {
    val pages = MutableSharedFlow<PageSettle>(extraBufferCapacity = 16)
    backgroundScope.launch { h.director.watch(pages) }
    runCurrent()
    return pages
  }

  @Test
  fun conLaReteApertaEPronteInTempoLAnimazioneParte() = runTest {
    val (nas, h) = rig()
    nas.reader.openDelayMs = 1_000L
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    // La coda non e' ancora arrivata: niente animazione e nessuna attesa visibile.
    assertThat(h.player.calls).isEmpty()
    advanceTimeBy(1_100L)
    runCurrent()

    val load = h.player.loads.single()
    // Il tratto e' tagliato dall'istante della foto (1,5 s) e muto; la sorgente e' il file della cache.
    assertThat(load.clip).isEqualTo(PlaybackClip(startMs = 300L, endMs = 1_500L))
    assertThat(load.muted).isTrue()
    assertThat(load.source).isInstanceOf(PlaybackSource.Whole::class.java)
    assertThat(load.source.uri.scheme).isEqualTo("file")
    assertThat(h.created).isEqualTo(1)
  }

  @Test
  fun oltreUnVirgolaCinqueSecondiNientePartenza_MaLaLetturaContinuaPerIlProssimoArrivo() = runTest {
    val (nas, h) = rig()
    nas.reader.openDelayMs = 2_000L
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + MotionDirector.READY_WITHIN_MS + 1)
    runCurrent()
    assertThat(h.player.calls).isEmpty()

    // Finisce di leggere in background: la coda e' in cache.
    advanceTimeBy(1_000L)
    runCurrent()
    assertThat(h.player.calls).isEmpty()
    assertThat(nas.cacheFiles).isNotEmpty()

    // Un secondo arrivo sulla stessa pagina usa la cache: parte subito (la cache non apre il NAS).
    val opens = nas.reader.opens.get()
    pages.emit(PageSettle(h.ids[0], moving = true))
    runCurrent()
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    assertThat(h.player.loads).hasSize(1)
    assertThat(nas.reader.opens.get()).isEqualTo(opens)
  }

  @Test
  fun oltre16MBNienteAnimazione() = runTest {
    val nas = MotionNasRig(tmp.root, testScheduler)
    val h = MotionHarness(backgroundScope, count = 1, phone = false, nasMotion = nas.source)
    val length = NasMotionSource.AUTOMATIC_MAX_BYTES + 1
    val head = MotionTestFiles.googleHead(length, declaredUs = 1_500_000L)
    nas.addVirtual(1, head, size = head.size + length)
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    assertThat(h.player.calls).isEmpty()
    assertThat(nas.cacheFiles).isEmpty()
    // Il badge lo riproduce lo stesso (e' un tuo tocco): il video intero, dalla cache.
    h.director.playFull(h.ids[0])
    advanceTimeBy(5_000L)
    runCurrent()
    assertThat(h.player.loads.map { it.muted }).containsExactly(false)
  }

  @Test
  fun coiDatiMobili_NessunaLetturaNeAnimazione_IlBadgeChiedeConferma() = runTest {
    val (nas, h) = rig()
    nas.link.value = NasNetwork.METERED
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[1], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    // Nemmeno il precarico delle vicine: nessuna lettura (contatore del NAS finto).
    assertThat(h.player.calls).isEmpty()
    assertThat(nas.reader.opens.get()).isEqualTo(0)
    assertThat(nas.reader.reads.get()).isEqualTo(0)

    h.director.playFull(h.ids[1])
    runCurrent()
    val notice = h.director.state.notice as MotionNotice.NeedsMobileData
    assertThat(notice.id).isEqualTo(h.ids[1])
    assertThat(notice.sizeBytes).isEqualTo(photo.size.toLong())
    assertThat(nas.reader.opens.get()).isEqualTo(0)
    assertThat(h.player.calls).isEmpty()

    // Il si dell'utente: `:app` registra la conferma e il badge ripete, ora a consumo e con l'audio.
    nas.consent.confirm(h.refs.getValue(h.ids[1]))
    h.director.playFull(h.ids[1])
    runCurrent()
    assertThat(h.player.loads.single().muted).isFalse()
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.FULL)
    assertThat(nas.traffic.meteredBytes).isGreaterThan(0L)
  }

  @Test
  fun conLaVpnSpentaOSenzaRete_IlBadgeDiceNonDisponibileOra_PoiSiRiprovaQuandoLaReteCambia() = runTest {
    val (nas, h) = rig()
    for (network in listOf(NasNetwork.VPN_OFF, NasNetwork.OFFLINE)) {
      nas.link.value = network
      h.director.playFull(h.ids[0])
      runCurrent()
      assertThat(h.director.state.notice).isEqualTo(MotionNotice.Unavailable(h.ids[0]))
      assertThat(h.director.state.isUnavailable(h.ids[0])).isTrue()
    }
    assertThat(nas.reader.opens.get()).isEqualTo(0)
    assertThat(h.player.calls).isEmpty()

    // La rete torna: "non disponibile ora" non e' piu' vero e il badge riparte.
    nas.link.value = NasNetwork.OPEN
    h.director.networkChanged()
    assertThat(h.director.state.isUnavailable(h.ids[0])).isFalse()
    assertThat(h.director.state.notice).isNull()
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.player.loads.single().muted).isFalse()
    assertThat(h.director.state.isUnavailable(h.ids[0])).isFalse()
  }

  @Test
  fun unNasSpentoDiceNonDisponibileOra_ELaFotoRestaFerma() = runTest {
    val (nas, h) = rig()
    nas.reader.openError = NasError.Timeout
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    assertThat(h.player.calls).isEmpty()
    h.director.playFull(h.ids[0])
    runCurrent()
    assertThat(h.director.state.notice).isEqualTo(MotionNotice.Unavailable(h.ids[0]))
  }

  @Test
  fun ilPrecaricoDelleVicineLeggeSoloIByteSulWifi_MaiUnSecondoDecoder() = runTest {
    val (nas, h) = rig()
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[2], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    advanceTimeBy(500L)
    runCurrent()
    // La corrente e le due vicine sono in cache; il lettore e' uno e ha avviato solo la corrente.
    assertThat(nas.cacheFiles).hasSize(3)
    assertThat(h.player.loads).hasSize(1)
    assertThat(h.created).isEqualTo(1)
  }

  @Test
  fun leRetiNonAmmesseNonLeggonoMai_PerOgniMotivo() = runTest {
    for (network in listOf(NasNetwork.VPN_OFF, NasNetwork.OFFLINE, NasNetwork.NOT_ALLOWED, NasNetwork.METERED)) {
      val (nas, h) = rig()
      nas.link.value = network
      val pages = watch(h)
      pages.emit(PageSettle(h.ids[0], moving = false))
      advanceTimeBy(MotionDirector.SETTLE_MS + MotionDirector.READY_WITHIN_MS + 1)
      runCurrent()
      assertThat(nas.reader.opens.get()).isEqualTo(0)
      assertThat(h.player.calls).isEmpty()
    }
  }

  /**
   * VM-1: un solo-NAS aspetta fino a 1,5 s la coda video; un tocco in quell'attesa vale. Prima nessun segno lo ricordava e l'animazione partiva sulla foto
   * toccata (la foto a trasparenza zero e il video a pagina intera per circa un secondo).
   */
  @Test
  fun unToccoDuranteLAttesaDellaCodaVideoImpedisceLAnimazione_VM1() = runTest {
    val (nas, h) = rig()
    nas.reader.openDelayMs = 1_000L
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    advanceTimeBy(MotionDirector.SETTLE_MS + 1)
    runCurrent()
    assertThat(h.player.calls).isEmpty()
    h.director.interruptOpening()
    advanceTimeBy(1_100L)
    runCurrent()
    assertThat(h.player.loads).isEmpty()
    assertThat(h.created).isEqualTo(0)
    assertThat(h.playback.motionFor).isNull()
  }

  /**
   * VM-3: mentre il badge legge il video dal NAS (fino a 64 MB, col NAS lento anche secondi) lo dice, e un secondo tocco non annulla la lettura per
   * ricominciare da capo (prima non c'era nessun segno e ogni tocco apriva il file di nuovo).
   */
  @Test
  fun ilBadgeDiceCheStaLeggendoEUnSecondoToccoNonRicomincia_VM3() = runTest {
    val (nas, h) = rig()
    nas.reader.openDelayMs = 2_000L
    val id = h.ids[0]
    h.director.playFull(id)
    advanceTimeBy(500L)
    runCurrent()
    assertThat(h.director.state.isLoading(id)).isTrue()
    assertThat(nas.reader.opens.get()).isEqualTo(1)

    // Il secondo tocco, e il terzo: ignorati finche' la lettura non ha finito.
    h.director.playFull(id)
    advanceTimeBy(300L)
    runCurrent()
    h.director.playFull(id)
    assertThat(nas.reader.opens.get()).isEqualTo(1)

    advanceTimeBy(5_000L)
    runCurrent()
    assertThat(h.director.state.isLoading(id)).isFalse()
    assertThat(nas.reader.opens.get()).isEqualTo(1)
    assertThat(h.player.loads.single().muted).isFalse()
    assertThat(h.playback.motionMode).isEqualTo(MotionMode.FULL)
  }

  /** VM-3: cambiando pagina la lettura si ferma e il badge non resta "in caricamento". */
  @Test
  fun cambiandoPaginaIlCaricamentoFinisce_VM3() = runTest {
    val (nas, h) = rig()
    nas.reader.openDelayMs = 5_000L
    val pages = watch(h)
    pages.emit(PageSettle(h.ids[0], moving = false))
    runCurrent()
    h.director.playFull(h.ids[0])
    advanceTimeBy(500L)
    runCurrent()
    assertThat(h.director.state.isLoading(h.ids[0])).isTrue()
    pages.emit(PageSettle(h.ids[1], moving = true))
    runCurrent()
    assertThat(h.director.state.isLoading(h.ids[0])).isFalse()
  }
}
