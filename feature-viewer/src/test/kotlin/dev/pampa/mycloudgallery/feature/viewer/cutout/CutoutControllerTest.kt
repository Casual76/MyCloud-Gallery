package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeCutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerSubjectCutter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il controller del ritaglio (Fase 6, P13; D44, D45, D46, D47; E-V10, E-V11, E-V12, E-H1): la logica che sta sotto i gesti, provata senza schermo.
 * Qui si fissano le regole: un "niente" con il dito e' silenzioso, il ritaglio di una foto non compare su un'altra, i cinque punti di prova,
 * il salvataggio dai Nascosti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class CutoutControllerTest {

  private val a = MediaId("00000000-0000-4000-8000-00000000000a")
  private val b = MediaId("00000000-0000-4000-8000-00000000000b")
  private val ref = MediaRef.Synthetic(seed = 1, width = 1600, height = 900)

  private inner class Rig(
    scope: CoroutineScope,
    testScope: TestScope,
    val cutter: FakeViewerSubjectCutter? = FakeViewerSubjectCutter(),
    val saver: FakeCutoutSaver? = FakeCutoutSaver(),
    val kind: MediaKind = MediaKind.PHOTO,
    val hidden: Boolean = false,
  ) {
    var current: MediaId = a
    val messages = mutableListOf<ViewerMessage>()
    private val services = ViewerServices(subjectCutter = cutter, cutoutSaver = saver)
    val controller = CutoutController(
      scope = scope,
      host = CutoutHost(
        services = { services },
        refOf = { ref },
        kindOf = { kind },
        currentId = { current },
        announce = { messages += it },
        hidden = { hidden },
        work = { StandardTestDispatcher(testScope.testScheduler) },
      ),
    )
  }

  private fun TestScope.rig(
    cutter: FakeViewerSubjectCutter? = FakeViewerSubjectCutter(),
    saver: FakeCutoutSaver? = FakeCutoutSaver(),
    kind: MediaKind = MediaKind.PHOTO,
    hidden: Boolean = false,
  ) = Rig(this, this, cutter, saver, kind, hidden)

  private fun Rig.lifted(): CutoutState.Lifted = controller.state as CutoutState.Lifted

  private fun TestScope.press(rig: Rig, x: Float = 0.5f, y: Float = 0.5f) {
    rig.controller.onPress(rig.current, x, y)
    runCurrent()
  }

  // --- Dal dito al soggetto --------------------------------------------------------------------------------------------------------

  @Test
  fun unaPressioneMetteIlVeloEPoiStaccaIlSoggetto() = runTest {
    val rig = rig(cutter = FakeViewerSubjectCutter(latencyMs = 300))
    rig.controller.onPress(a, 0.4f, 0.6f)
    runCurrent()
    // Mentre il modello lavora: il velo (e dove e' caduto il dito).
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifting::class.java)
    assertThat(rig.controller.lifting).isTrue()
    assertThat(rig.controller.owner).isEqualTo(a)
    assertThat(rig.controller.origin).isEqualTo(0.4f to 0.6f)
    assertThat(rig.controller.active).isFalse()
    advanceTimeBy(400)
    runCurrent()
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.controller.art).isNotNull()
    assertThat(rig.controller.active).isTrue()
    assertThat(rig.controller.outline).isFalse()
    assertThat(rig.cutter!!.calls).containsExactly(0.4f to 0.6f)
  }

  @Test
  fun nessunSoggettoConIlDitoESilenzioso_E_V10() = runTest {
    val rig = rig(cutter = FakeViewerSubjectCutter(noSubject = true))
    press(rig)
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(rig.controller.owner).isNull()
    assertThat(rig.controller.art).isNull()
    assertThat(rig.messages).isEmpty()
  }

  @Test
  fun chiestoDalMenuUnNienteSiDice() = runTest {
    val rig = rig(cutter = FakeViewerSubjectCutter(noSubject = true))
    rig.controller.cutLargest(a)
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutNone)
    // Cinque punti di prova, tutti senza soggetto.
    assertThat(rig.cutter!!.calls).hasSize(5)
  }

  @Test
  fun unGuastoOUnaEccezioneDicononRiuscito() = runTest {
    val failed = rig(cutter = FakeViewerSubjectCutter(result = { _, _ -> ViewerCutout.Failed("CUTOUT_FAILED") }))
    press(failed)
    assertThat(failed.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(failed.messages).containsExactly(ViewerMessage.CutoutFailed)
    val thrown = rig(cutter = FakeViewerSubjectCutter(failWith = IllegalStateException("dentro il motore")))
    press(thrown)
    assertThat(thrown.messages).containsExactly(ViewerMessage.CutoutFailed)
  }

  @Test
  fun senzaRitagliatoreONonFaNienteSuUnVideo_D13() = runTest {
    val none = rig(cutter = null)
    assertThat(none.controller.available).isFalse()
    press(none)
    none.controller.cutLargest(a)
    runCurrent()
    assertThat(none.controller.state).isEqualTo(CutoutState.Idle)
    val video = rig(kind = MediaKind.VIDEO)
    press(video)
    video.controller.cutLargest(a)
    runCurrent()
    assertThat(video.cutter!!.calls).isEmpty()
    assertThat(video.controller.state).isEqualTo(CutoutState.Idle)
  }

  @Test
  fun leFotoInMovimentoSiRitaglianoComeLeAltre_E_V12() = runTest {
    val rig = rig(kind = MediaKind.MOTION_PHOTO)
    press(rig)
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
  }

  // --- Un media alla volta (E-H1) ------------------------------------------------------------------------------------------------

  @Test
  fun ilRisultatoDiUnaFotoNonSiMostraSuUnAltra() = runTest {
    val gate = CompletableDeferred<Unit>()
    val rig = rig(cutter = FakeViewerSubjectCutter(gate = gate))
    rig.controller.onPress(a, 0.5f, 0.5f)
    runCurrent()
    // Si e' passati a un altro media prima della risposta del modello.
    rig.current = b
    rig.controller.bind(b)
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    gate.complete(Unit)
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(rig.controller.owner).isNull()
    assertThat(rig.controller.art).isNull()
  }

  @Test
  fun cambiandoMediaIlSoggettoStaccatoSiChiude() = runTest {
    val rig = rig()
    press(rig)
    assertThat(rig.controller.owner).isEqualTo(a)
    rig.controller.bind(a)
    assertThat(rig.controller.active).isTrue()
    rig.current = b
    rig.controller.bind(b)
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(rig.controller.owner).isNull()
    assertThat(rig.controller.art).isNull()
  }

  @Test
  fun unaNuovaPressioneAnnullaLaPrecedente() = runTest {
    val first = FakeViewerSubjectCutter(latencyMs = 500)
    val rig = rig(cutter = first)
    rig.controller.onPress(a, 0.1f, 0.1f)
    runCurrent()
    advanceTimeBy(100)
    first.latencyMs = 0
    rig.controller.onPress(a, 0.9f, 0.9f)
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.controller.origin).isEqualTo(0.9f to 0.9f)
  }

  // --- I cinque punti di prova (D46) ------------------------------------------------------------------------------------------------

  private fun subject(left: Float, top: Float, right: Float, bottom: Float): ViewerCutout.Subject {
    val w = 64
    val h = 64
    val alpha = ByteArray(w * h) { i ->
      val x = (i % w + 0.5f) / w
      val y = (i / w + 0.5f) / h
      if (x in left..right && y in top..bottom) 0xff.toByte() else 0
    }
    return ViewerCutout.Subject(ViewerMask(w, h, alpha), ViewerRect(left, top, right, bottom))
  }

  @Test
  fun ilSoggettoPiuGrandeFraCinquePuntiEQuelloCheSiStacca() = runTest {
    // Una cosa piccola a sinistra e una grande a destra: il punto a sinistra trova la piccola, quelli a destra la grande.
    val small = subject(0.05f, 0.05f, 0.25f, 0.45f)
    val big = subject(0.55f, 0.1f, 0.95f, 0.9f)
    val cutter = FakeViewerSubjectCutter(
      result = { x, _ ->
        when {
          big.contains(x, 0.5f) && x > 0.5f -> big
          x < 0.4f -> small
          else -> ViewerCutout.NoSubject
        }
      },
    )
    val rig = rig(cutter = cutter)
    rig.controller.cutLargest(a)
    runCurrent()
    assertThat(rig.lifted().cutout).isSameInstanceAs(big)
  }

  @Test
  fun unPuntoCheCadeSuUnSoggettoGiaTrovatoNonSiProva() = runTest {
    // Un soggetto che copre tutto il centro: dopo il primo punto (il centro) gli altri quattro cadono dentro e non si provano.
    val whole = subject(0.1f, 0.1f, 0.9f, 0.9f)
    val cutter = FakeViewerSubjectCutter(result = { _, _ -> whole })
    val rig = rig(cutter = cutter)
    rig.controller.cutLargest(a)
    runCurrent()
    assertThat(cutter.calls).hasSize(1)
    assertThat(cutter.calls.single()).isEqualTo(0.5f to 0.5f)
    assertThat(rig.lifted().cutout).isSameInstanceAs(whole)
  }

  @Test
  fun iCinquePuntiSonoIlCentroEIQuattroATerzo() {
    assertThat(CutoutController.PROBES).hasSize(5)
    assertThat(CutoutController.PROBES.first()).isEqualTo(0.5f to 0.5f)
    assertThat(CutoutController.PROBES.drop(1).map { it.first }.toSet()).containsExactly(1f / 3f, 2f / 3f)
    assertThat(CutoutController.PROBES.drop(1).map { it.second }.toSet()).containsExactly(1f / 3f, 2f / 3f)
  }

  // --- La barra ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilContornoSiAccendeESiSpegneSoloConIlSoggettoStaccato() = runTest {
    val rig = rig()
    rig.controller.toggleOutline()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    press(rig)
    rig.controller.toggleOutline()
    assertThat(rig.controller.outline).isTrue()
    rig.controller.toggleOutline()
    assertThat(rig.controller.outline).isFalse()
  }

  @Test
  fun copiaChiudeSeRiesceERestaSeNo() = runTest {
    val rig = rig()
    press(rig)
    rig.controller.toggleOutline()
    rig.controller.copy()
    runCurrent()
    assertThat(rig.saver!!.copies).containsExactly(a)
    assertThat(rig.saver.outlines).containsExactly(true)
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutCopied)
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)

    val failing = rig(saver = FakeCutoutSaver().apply { copyFails = true })
    press(failing)
    failing.controller.copy()
    runCurrent()
    assertThat(failing.messages).containsExactly(ViewerMessage.ActionFailed)
    assertThat(failing.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(failing.controller.working).isFalse()
  }

  @Test
  fun condividiLasciaIlSoggettoEDiceSeNonRiesce() = runTest {
    val rig = rig()
    press(rig)
    rig.controller.share()
    runCurrent()
    assertThat(rig.saver!!.shares).containsExactly(a)
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.messages).isEmpty()
    rig.saver.shareFails = true
    rig.controller.share()
    runCurrent()
    assertThat(rig.messages).containsExactly(ViewerMessage.ActionFailed)
  }

  @Test
  fun salvaPassaPerSavingESavedEPoiSiChiude() = runTest {
    val rig = rig(saver = FakeCutoutSaver().apply { latencyMs = 200 })
    press(rig)
    rig.controller.save()
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Saving)
    assertThat(rig.controller.working).isTrue()
    // Un salvataggio in corso non si interrompe: ne' dal tocco fuori ne' da un'altra azione.
    rig.controller.dismiss()
    rig.controller.copy()
    rig.controller.toggleOutline()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Saving)
    advanceTimeBy(250)
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Saved)
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutSaved)
    assertThat(rig.saver!!.saved).containsExactly(a to false)
    assertThat(rig.saver.copies).isEmpty()
    advanceTimeBy(CutoutController.SAVED_MILLIS + 100)
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
  }

  @Test
  fun dalNascostoLaFotoNuovaNasceNascosta_E_V11() = runTest {
    val rig = rig(hidden = true)
    press(rig)
    rig.controller.save()
    runCurrent()
    // I6: dai Nascosti prima si chiede conferma (il ritaglio nel telefono e' una foto pubblica); solo il "Salva" del foglio salva.
    assertThat(rig.saver!!.saved).isEmpty()
    assertThat(rig.controller.confirmingHiddenSave).isTrue()
    rig.controller.confirmHiddenSave()
    runCurrent()
    assertThat(rig.controller.confirmingHiddenSave).isFalse()
    assertThat(rig.saver.saved).containsExactly(a to true)
  }

  @Test
  fun unSalvataggioFallitoTornaAlSoggettoStaccato() = runTest {
    val rig = rig(saver = FakeCutoutSaver().apply { saveFails = true })
    press(rig)
    rig.controller.save()
    runCurrent()
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutSaveFailed)
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.controller.working).isFalse()
    rig.saver!!.saveFails = false
    rig.controller.save()
    runCurrent()
    assertThat(rig.saver.saved).containsExactly(a to false)
  }

  @Test
  fun senzaSalvataggioLeAzioniNonFannoNiente() = runTest {
    val rig = rig(saver = null)
    press(rig)
    rig.controller.copy()
    rig.controller.share()
    rig.controller.save()
    runCurrent()
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.messages).isEmpty()
  }

  @Test
  fun leAzioniNonSiSovrappongono() = runTest {
    val rig = rig(saver = FakeCutoutSaver().apply { latencyMs = 100 })
    press(rig)
    rig.controller.copy()
    rig.controller.share()
    rig.controller.save()
    runCurrent()
    advanceTimeBy(300)
    runCurrent()
    // Solo la prima azione e' partita.
    assertThat(rig.saver!!.copies).containsExactly(a)
    assertThat(rig.saver.shares).isEmpty()
    assertThat(rig.saver.saved).isEmpty()
  }

  @Test
  fun dismissChiudeTuttoETogliLeFigure() = runTest {
    val rig = rig()
    press(rig)
    assertThat(rig.controller.canDismiss).isTrue()
    rig.controller.dismiss()
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)
    assertThat(rig.controller.art).isNull()
    assertThat(rig.controller.canDismiss).isFalse()
  }

  // --- Correzioni dopo le revisioni, pacchetto C -----------------------------------------------------------------------------------

  /** I6: "Annulla" nel foglio dei Nascosti non salva niente e il soggetto resta staccato; dalla Libreria il salvataggio parte subito, senza foglio. */
  @Test
  fun dalNascostoIlFoglioAnnullaENonSalvaNiente_I6() = runTest {
    val hiddenRig = rig(hidden = true)
    press(hiddenRig)
    hiddenRig.controller.save()
    assertThat(hiddenRig.controller.confirmingHiddenSave).isTrue()
    hiddenRig.controller.cancelHiddenSave()
    runCurrent()
    assertThat(hiddenRig.controller.confirmingHiddenSave).isFalse()
    assertThat(hiddenRig.saver!!.saved).isEmpty()
    assertThat(hiddenRig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    // Chiudere il ritaglio (tocco fuori) chiude anche il foglio.
    hiddenRig.controller.save()
    hiddenRig.controller.dismiss()
    assertThat(hiddenRig.controller.confirmingHiddenSave).isFalse()

    val plainRig = rig(hidden = false)
    press(plainRig)
    plainRig.controller.save()
    runCurrent()
    assertThat(plainRig.controller.confirmingHiddenSave).isFalse()
    assertThat(plainRig.saver!!.saved).containsExactly(a to false)
  }

  /** R20: la memoria esaurita nel ritaglio non fa cadere l'app (un `Error` non e' un'`Exception`): il ritaglio non riesce e lo dice. */
  @Test
  fun laMemoriaEsauritaNelRitaglioNonFaCadereLApp_R20() = runTest {
    val rig = rig(cutter = FakeViewerSubjectCutter(failWith = OutOfMemoryError("test")))
    press(rig)
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutLowMemory)
    assertThat(rig.controller.state).isEqualTo(CutoutState.Idle)

    // Un guasto che dice di piu': poco spazio per il modello (VR-2) e poca memoria dichiarata dal ritagliatore.
    val space = rig(cutter = FakeViewerSubjectCutter(result = { _, _ -> ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.NO_SPACE) }))
    press(space)
    assertThat(space.messages).containsExactly(ViewerMessage.CutoutNoSpace)
    val memory = rig(cutter = FakeViewerSubjectCutter(result = { _, _ -> ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.LOW_MEMORY) }))
    press(memory)
    assertThat(memory.messages).containsExactly(ViewerMessage.CutoutLowMemory)
    // Un guasto senza motivo resta il "riprova" di sempre.
    val plain = rig(cutter = FakeViewerSubjectCutter(result = { _, _ -> ViewerCutout.Failed("CUTOUT_FAILED") }))
    press(plain)
    assertThat(plain.messages).containsExactly(ViewerMessage.CutoutFailed)
  }

  /** R20: anche Copia, Condividi e Salva (il PNG e le sue copie) reggono la memoria esaurita: lo dicono, il soggetto resta e si puo' riprovare. */
  @Test
  fun copiaCondividiESalvaReggonoLaMemoriaEsauritaEDiconoDiChiudereQualcosa_R20() = runTest {
    val saver = FakeCutoutSaver().apply { failWith = OutOfMemoryError("test") }
    val rig = rig(saver = saver)
    press(rig)
    rig.controller.copy()
    runCurrent()
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutLowMemory)
    assertThat(rig.controller.working).isFalse()
    rig.controller.share()
    runCurrent()
    assertThat(rig.messages).containsExactly(ViewerMessage.CutoutLowMemory, ViewerMessage.CutoutLowMemory)
    rig.controller.save()
    runCurrent()
    assertThat(rig.messages.last()).isEqualTo(ViewerMessage.CutoutLowMemory)
    assertThat(rig.controller.state).isInstanceOf(CutoutState.Lifted::class.java)
    assertThat(rig.controller.working).isFalse()
    // Con piu' memoria lo stesso soggetto si copia.
    saver.failWith = null
    rig.controller.copy()
    runCurrent()
    assertThat(saver.copies).containsExactly(a)
  }
}
