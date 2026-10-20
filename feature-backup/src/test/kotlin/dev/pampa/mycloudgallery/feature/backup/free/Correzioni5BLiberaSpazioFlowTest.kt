package dev.pampa.mycloudgallery.feature.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.NasStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni di "Libera spazio" della verifica funzionale 5B lato pagina (`docs/review/funzionale-5b.md`: LS-2, LS-5, LS-7, LS-10, LS-11,
 * LS-12, LS-13, LS-15): la macchina della pagina con il giro fuori dalla pagina ([FreeSpaceDriver]) sul servizio di riferimento di core-backup,
 * allineato al vero (verifica a tappe, conferma sul piano visto). Un dispatcher standard: le tappe si fanno avanzare a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Correzioni5BLiberaSpazioFlowTest {

  private val clock = FakeBackupClock()
  private val dispatcher = StandardTestDispatcher()

  /** Lo scope di una "pagina": si chiude come si chiude la pagina. */
  private fun TestScope.page(): CoroutineScope = CoroutineScope(dispatcher + Job())

  /** Lo scope del "processo", dove il giro va avanti fuori dalla pagina (non `backgroundScope`: `advanceUntilIdle` non lo aspetterebbe). */
  private val process: CoroutineScope = CoroutineScope(dispatcher + Job())

  private fun allow() = RemoveDialog { DialogAnswer.ALLOWED }

  private suspend fun FreeSpaceFlow.confirmNormal() {
    val plan = planNow(permanent = false)
    start(plan, TestConfirmations.confirm(plan, clock.monotonicMs()))
  }

  // --- LS-2 -------------------------------------------------------------------------------------------------------------------

  /**
   * LS-2: la verifica va avanti anche dopo che la pagina si e' chiusa (prima viveva nel `viewModelScope` e moriva con lei), e la pagina riaperta
   * la ritrova a meta' e poi pronta.
   */
  @Test
  fun ls2_laVerificaContinuaFuoriDallaPagina_eAllaRiaperturaSiRitrova() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock).apply {
      verifySteps = 3
      stepDelayMs = 1_000
    }
    val driver = FreeSpaceDriver(space, process)
    val first = page()
    val flow = FreeSpaceFlow(space, first, systemDialogs = true, runs = driver)
    flow.load()
    runCurrent()
    flow.confirmNormal()
    runCurrent()
    assertThat(flow.state.value).isInstanceOf(FreeSpaceUi.Verifying::class.java)
    // La pagina si chiude a meta' verifica.
    first.cancel()
    advanceTimeBy(1_500)
    assertThat(driver.current.value!!.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    assertThat(driver.current.value!!.verifiedDone).isGreaterThan(0)
    // Riaperta: ritrova il giro, e lo vede arrivare a "pronto".
    val again = FreeSpaceFlow(space, page(), systemDialogs = true, runs = driver)
    again.load()
    runCurrent()
    assertThat(again.state.value).isInstanceOf(FreeSpaceUi.Verifying::class.java)
    advanceUntilIdle()
    assertThat((again.state.value as FreeSpaceUi.Ready).items).isEqualTo(3)
  }

  /** LS-2: un giro rimasto aperto nel diario (un processo morto) si ritrova all'apertura e riparte da dove era. */
  @Test
  fun ls2_unGiroRimastoNelDiarioRiparteAllApertura() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 2, clock = clock).apply { verifySteps = 2 }
    val plan = space.plan(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter(), false)
    // Il processo di prima: il giro e' partito e il processo e' morto.
    assertThat(space.verify(plan, TestConfirmations.confirm(plan, clock.monotonicMs()), kotlin.time.Duration.ZERO).state)
      .isEqualTo(FreeSpaceRunState.VERIFYING)
    val driver = FreeSpaceDriver(space, process, activeRun = { space.active() })
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = driver)
    flow.load()
    advanceUntilIdle()
    assertThat((flow.state.value as FreeSpaceUi.Ready).items).isEqualTo(2)
  }

  /** LS-2: "Annulla" chiude il giro: riaprendo la pagina non riparte, si vede il riepilogo. */
  @Test
  fun ls2_annullaChiudeIlGiroEAllaRiaperturaCeIlRiepilogo() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 2, clock = clock).apply {
      verifySteps = 5
      stepDelayMs = 1_000
    }
    val driver = FreeSpaceDriver(space, process, activeRun = { space.active() }, abandonRun = { id -> space.abandon(id) })
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = driver)
    flow.load()
    runCurrent()
    flow.confirmNormal()
    advanceTimeBy(1_500)
    flow.cancel()
    advanceUntilIdle()
    assertThat(flow.state.value).isInstanceOf(FreeSpaceUi.Summary::class.java)
    assertThat(driver.current.value!!.state).isEqualTo(FreeSpaceRunState.CANCELLED)
    val again = FreeSpaceFlow(space, page(), systemDialogs = true, runs = driver)
    again.load()
    advanceUntilIdle()
    assertThat(again.state.value).isInstanceOf(FreeSpaceUi.Summary::class.java)
  }

  // --- LS-5 -------------------------------------------------------------------------------------------------------------------

  /**
   * LS-5: le parti del backup si tolgono a meta' verifica e il servizio restituisce il giro com'e' (il null-object). Prima il ciclo girava
   * all'infinito sul thread principale (ANR); adesso il giro si ferma "non disponibile" dopo una sola tappa.
   */
  @Test
  fun ls5_seIlServizioNonFaNiente_ilGiroSiFermaENonGiraAllInfinito() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 2, clock = clock).apply {
      verifySteps = 2
      stuck = true
    }
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process))
    flow.load()
    runCurrent()
    flow.confirmNormal()
    advanceUntilIdle()
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE))
    assertThat(space.resumes).isEqualTo(1)
  }

  // --- LS-7 -------------------------------------------------------------------------------------------------------------------

  /** LS-7: un "no" al secondo lotto: la fine dice quanti sono gia' stati liberati e quanti restano (non "nessun file e' stato tolto"). */
  @Test
  fun ls7_unRifiutoAlSecondoLotto_diceQuantiSonoGiaLiberati() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4_500, size = 1_000L, clock = clock)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process))
    flow.load()
    runCurrent()
    flow.confirmNormal()
    advanceUntilIdle()
    flow.free(allow())
    advanceUntilIdle()
    flow.free(RemoveDialog { DialogAnswer.REFUSED })
    advanceUntilIdle()
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.cancelled).isTrue()
    assertThat(done.items).isEqualTo(2_000)
    // Il lotto rifiutato e quello mai proposto restano sul telefono.
    assertThat(done.kept).isEqualTo(2_500)
  }

  // --- LS-10, LS-11 --------------------------------------------------------------------------------------------------------------

  /** LS-10: prima che il backup parta la pagina non mostra "niente da liberare": aspetta, e si ricalcola da sola quando le parti arrivano. */
  @Test
  fun ls10_primaCheIlBackupPartaLaPaginaAspettaESiRicalcolaDaSola() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val ready = MutableStateFlow(false)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process, ready = ready))
    flow.load()
    runCurrent()
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE))
    assertThat(space.plans).isEqualTo(0)
    ready.value = true
    runCurrent()
    assertThat((flow.state.value as FreeSpaceUi.Summary).plan.itemCount).isEqualTo(3)
  }

  /** LS-11: senza l'ora del NAS il riepilogo lo dice (i media datati restano sul telefono) e si ricalcola quando l'ora arriva. */
  @Test
  fun ls11_senzaLOraDelNasIlRiepilogoLoDiceESiRicalcola() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val known = MutableStateFlow(false)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process, nasTimeKnown = known))
    flow.load()
    runCurrent()
    assertThat((flow.state.value as FreeSpaceUi.Summary).nasTimeKnown).isFalse()
    known.value = true
    runCurrent()
    assertThat((flow.state.value as FreeSpaceUi.Summary).nasTimeKnown).isTrue()
    assertThat(space.plans).isEqualTo(2)
  }

  // --- LS-12 ------------------------------------------------------------------------------------------------------------------

  /** LS-12: un secondo tocco su "Libera ora" mentre il primo lotto e' in corso non lo annulla e non apre un secondo dialogo. */
  @Test
  fun ls12_unSecondoToccoSuLiberaOraNonAnnullaIlPrimo() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process))
    flow.load()
    runCurrent()
    flow.confirmNormal()
    advanceUntilIdle()
    val answer = CompletableDeferred<DialogAnswer>()
    val asked = ArrayList<TrashBatch>()
    flow.free(RemoveDialog { batch -> asked += batch; answer.await() })
    runCurrent()
    flow.free(allow())
    runCurrent()
    answer.complete(DialogAnswer.ALLOWED)
    advanceUntilIdle()
    assertThat(asked).hasSize(1)
    assertThat(space.presented).hasSize(1)
    assertThat((flow.state.value as FreeSpaceUi.Done).items).isEqualTo(3)
  }

  // --- LS-13 ------------------------------------------------------------------------------------------------------------------

  /** LS-13: un media confermato cambia prima che il giro parta: resta sul telefono, gli altri vanno, e la fine lo dice. */
  @Test
  fun ls13_unMediaCambiatoDopoLaConferma_restaELaFineLoDice() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process))
    flow.load()
    runCurrent()
    val plan = flow.planNow(permanent = false)
    // La copia del secondo media ha un timbro nuovo fra il foglio e il tocco.
    val old = space.candidates[1]
    space.candidates[1] = dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem(
      old.mediaId, old.versionId, old.phone, old.mime, old.size, NasStamp(old.size, 1L, 1L), old.isVideo, old.localDay,
    )
    flow.start(plan, TestConfirmations.confirm(plan, clock.monotonicMs()))
    advanceUntilIdle()
    flow.free(allow())
    advanceUntilIdle()
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(2)
    assertThat(done.changedBeforeStart).isEqualTo(1)
  }

  // --- LS-15 ------------------------------------------------------------------------------------------------------------------

  /** LS-15: il foglio di conferma riusa il piano del riepilogo (anche per "Libera subito"): niente ricalcolo senza indicatore. */
  @Test
  fun ls15_ilFoglioRiusaIlPianoDelRiepilogo() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val flow = FreeSpaceFlow(space, page(), systemDialogs = true, runs = FreeSpaceDriver(space, process))
    flow.load()
    runCurrent()
    assertThat(space.plans).isEqualTo(1)
    val normal = flow.planNow(permanent = false)
    val now = flow.planNow(permanent = true)
    assertThat(space.plans).isEqualTo(1)
    assertThat(normal.itemCount).isEqualTo(3)
    assertThat(now.permanent).isTrue()
    assertThat(now.requiredConfirmations).isEqualTo(2)
    assertThat(now.items.map { it.mediaId }).containsExactlyElementsIn(normal.items.map { it.mediaId })
  }
}
