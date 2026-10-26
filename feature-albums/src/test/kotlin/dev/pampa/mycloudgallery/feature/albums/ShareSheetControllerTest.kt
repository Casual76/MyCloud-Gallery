package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationCheck
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetNotice
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetController
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il foglio di condivisione (Fase 7, P10; D12, D30, D35, C10, C26, E-SH9): **la conferma la emette solo il tocco sul foglio**, un tocco troppo
 * vicino alla comparsa non conta (R6 della 4B), il piano si rifa' quando cambia una scelta, e un piano che non vale piu' non parte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareSheetControllerTest {

  private val day = LocalDate.of(2026, 10, 21)
  private val clock = FixedAlbumScreensClock(day, nowMs = 10_000_000L)
  private val newAlbum = ShareRequest.NewAlbum("Oslo 2026", ShareLevel.ADD, (1..26).map(ShareFixtures::media))

  private fun runner(planOutcome: (ShareRequest) -> SharePlanOutcome = { SharePlanOutcome.Ready(ShareFixtures.newAlbumPlan(it)) }) = ScriptedRunner(planOutcome)

  @Test
  fun chiedereUnPianoNonConfermaNienteENonFaPartireNiente() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    assertThat(sheet.state.value).isInstanceOf(ShareSheetState.Plan::class.java)
    // Il piano e' dal catalogo locale: nessuno `start`, qualunque altra cosa si faccia al foglio.
    sheet.setHiddenChoice(HiddenChoice.SHARE_TOO)
    sheet.setLevel(ShareLevel.VIEW)
    sheet.dismiss()
    assertThat(runner.starts).isEmpty()
  }

  @Test
  fun unTocco_TroppoVicinoAllaComparsaDelFoglioNonConferma() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS - 1
    sheet.confirm()
    assertThat(runner.starts).isEmpty()
    assertThat(sheet.state.value).isInstanceOf(ShareSheetState.Plan::class.java)
  }

  @Test
  fun ilTocco_DopoLaPausaConfermaUnaVoltaEDaLaConfermaDiQuestoPiano() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    val shown = (sheet.state.value as ShareSheetState.Plan).plan
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    assertThat(runner.starts).hasSize(1)
    val (plan, confirmation) = runner.starts.single()
    assertThat(plan).isSameInstanceAs(shown)
    assertThat(ShareConfirmationRules.check(plan, confirmation, clock.nowMs)).isEqualTo(ShareConfirmationCheck.Valid)
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.Started(dev.pampa.mycloudgallery.core.albums.contract.ShareOpId("op-1")))
    assertThat(sheet.startedAlbum).isEqualTo(shown.album)
    // Un secondo tocco non rifa' partire niente: il foglio e' gia' partito.
    sheet.confirm()
    assertThat(runner.starts).hasSize(1)
  }

  @Test
  fun ilCallbackDiChiHaChiestoIlPianoGiraSoloDopoLoStart() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    var ran = 0
    sheet.request(ShareRequest.BringBack(ShareFixtures.ALBUM, listOf(ShareFixtures.media(1)))) { ran++ }
    sheet.dismiss()
    assertThat(ran).isEqualTo(0)
    sheet.request(ShareRequest.BringBack(ShareFixtures.ALBUM, listOf(ShareFixtures.media(1)))) { ran++ }
    assertThat(ran).isEqualTo(0)
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    assertThat(ran).isEqualTo(1)
  }

  @Test
  fun unPianoCheNonVaPiuSiRifaConLAvvisoENonParte() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    runner.startOutcome = { _, _ -> ShareStartOutcome.PlanChanged }
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    val again = sheet.state.value as ShareSheetState.Plan
    assertThat(again.notice).isEqualTo(ShareSheetNotice.CHANGED)
    assertThat(runner.starts).hasSize(1)
    // Il foglio nuovo vuole un tocco nuovo.
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    runner.startOutcome = { _, _ -> ShareStartOutcome.Started(dev.pampa.mycloudgallery.core.albums.contract.ShareOpId("op-2")) }
    sheet.confirm()
    assertThat(runner.starts).hasSize(2)
  }

  @Test
  fun unaConfermaScadutaFaRifareIlFoglioConLAvvisoDiScadenza() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    runner.startOutcome = { _, _ -> ShareStartOutcome.ConfirmationRejected(ShareConfirmationCheck.Expired) }
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    assertThat((sheet.state.value as ShareSheetState.Plan).notice).isEqualTo(ShareSheetNotice.EXPIRED)
  }

  @Test
  fun laRecinzioneDiceArrivaDopoLeProveENonApreLaConferma() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner { SharePlanOutcome.Blocked(ShareBlock.TEST_SHARES_ONLY) }
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.Blocked(ShareBlock.TEST_SHARES_ONLY))
    sheet.confirm()
    assertThat(runner.starts).isEmpty()
  }

  @Test
  fun gliEsitiSenzaPianoSiMostranoENonSiConfermano() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner { SharePlanOutcome.NothingToShare(mapOf(ShareExclusion.ALREADY_IN_ALBUM to 3)) }
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.NothingToShare(mapOf(ShareExclusion.ALREADY_IN_ALBUM to 3)))
    runner.planOutcome = { SharePlanOutcome.NotAllowed(AlbumOp.ADD_MEDIA) }
    sheet.request(newAlbum)
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.Refused(AlbumOp.ADD_MEDIA))
    runner.planOutcome = { SharePlanOutcome.InvalidTitle(dev.pampa.mycloudgallery.core.albums.contract.TitleProblem.EMPTY) }
    sheet.request(newAlbum)
    assertThat(sheet.state.value).isInstanceOf(ShareSheetState.InvalidTitle::class.java)
    sheet.confirm()
    assertThat(runner.starts).isEmpty()
  }

  @Test
  fun lasciareFuoriOCondividereINascostiRifaIlPianoConLaScelta() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner { request ->
      val hidden = (request as ShareRequest.NewAlbum).hidden
      SharePlanOutcome.Ready(ShareFixtures.newAlbumPlan(request, hidden, hiddenLeftOut = if (hidden == HiddenChoice.LEAVE_OUT) 3 else 0, hiddenIn = if (hidden == HiddenChoice.SHARE_TOO) 3 else 0))
    }
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    // «Lascia fuori i N nascosti» e' quella di partenza (Q3 a).
    val first = sheet.state.value as ShareSheetState.Plan
    assertThat(first.hiddenChoice).isEqualTo(HiddenChoice.LEAVE_OUT)
    assertThat(first.plan.hiddenLeftOut).isEqualTo(3)
    assertThat(first.warnHiddenBecomeVisible).isFalse()
    sheet.setHiddenChoice(HiddenChoice.SHARE_TOO)
    // «Condividi anche i nascosti»: il piano li comprende e il foglio avvisa (E-SH9).
    val second = sheet.state.value as ShareSheetState.Plan
    assertThat(second.hiddenChoice).isEqualTo(HiddenChoice.SHARE_TOO)
    assertThat(second.plan.hiddenIncluded).isEqualTo(3)
    assertThat(second.warnHiddenBecomeVisible).isTrue()
    assertThat((runner.plans.last() as ShareRequest.NewAlbum).hidden).isEqualTo(HiddenChoice.SHARE_TOO)
    // La conferma vale per il piano che si vede adesso, non per quello di prima.
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    assertThat(runner.starts.single().first.hash).isEqualTo(second.plan.hash)
    assertThat(runner.starts.single().first.hash).isNotEqualTo(first.plan.hash)
  }

  @Test
  fun ilLivelloDiUnAlbumNuovoSiScegliENelFoglioEPartireDaGliAltriAggiungono() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    assertThat((sheet.state.value as ShareSheetState.Plan).level).isEqualTo(ShareLevel.ADD)
    sheet.setLevel(ShareLevel.VIEW)
    assertThat((runner.plans.last() as ShareRequest.NewAlbum).level).isEqualTo(ShareLevel.VIEW)
  }

  @Test
  fun unAggiungiSuUnAlbumCheCeGiaNonHaLivelloEUnoSmartSiCambiaSoloNeiNascosti() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.onAddOutcome(AddOutcome.NeedsShareConfirmation(ShareFixtures.addPlan()))
    val plan = sheet.state.value as ShareSheetState.Plan
    assertThat(plan.level).isNull()
    sheet.setLevel(ShareLevel.ALL)
    assertThat(runner.plans).isEmpty()
  }

  @Test
  fun glieEsitiDiUnaggiuntaDiventanoUnFoglioONiente() = runTest(UnconfinedTestDispatcher()) {
    val sheet = ShareSheetController(runner(), clock, backgroundScope)
    sheet.onAddOutcome(AddOutcome.Blocked(ShareBlock.READ_ONLY))
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.Blocked(ShareBlock.READ_ONLY))
    sheet.dismiss()
    sheet.onAddOutcome(AddOutcome.Added(2, 0))
    assertThat(sheet.state.value).isNull()
    sheet.onAddOutcome(AddOutcome.NotAllowed(AlbumOp.ADD_MEDIA))
    assertThat(sheet.state.value).isEqualTo(ShareSheetState.Refused(AlbumOp.ADD_MEDIA))
  }

  @Test
  fun chiudereIlFoglioNonLasciaNienteInSospeso() = runTest(UnconfinedTestDispatcher()) {
    val runner = runner()
    val sheet = ShareSheetController(runner, clock, backgroundScope)
    sheet.request(newAlbum)
    sheet.dismiss()
    assertThat(sheet.state.value).isNull()
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    sheet.confirm()
    assertThat(runner.starts).isEmpty()
  }
}
