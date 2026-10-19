package dev.pampa.mycloudgallery.feature.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryFreeSpace
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La macchina di "Libera spazio" (`docs/fase5-progetto.md` §1.5 `FreeSpaceState`, §2.5): ogni stato del contratto della UI, sul servizio di
 * riferimento di core-backup. La macchina **non decide niente**: questi test provano che racconta cio' che il servizio risponde (anche quando
 * dice di no) e che il dialogo del sistema parte solo da [FreeSpaceFlow.free].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeSpaceFlowTest {

  private val clock = FakeBackupClock()
  private val dispatcher = UnconfinedTestDispatcher()

  private fun kotlinx.coroutines.test.TestScope.flowFor(
    space: InMemoryFreeSpace,
    systemDialogs: Boolean = true,
    selection: Set<dev.pampa.mycloudgallery.core.model.MediaId>? = null,
    recentDays: Int = FreeSpaceRules.DEFAULT_EXCLUDE_RECENT_DAYS,
  ) = FreeSpaceFlow(space, backgroundScope, systemDialogs, selection = selection, excludeRecentDays = recentDays)

  private fun allow() = RemoveDialog { DialogAnswer.ALLOWED }

  private suspend fun FreeSpaceFlow.confirmNormal() {
    val plan = planNow(permanent = false)
    start(plan, TestConfirmations.confirm(plan, clock.monotonicMs()))
  }

  @Test
  fun ilRiepilogoNasceDalPianoLocale() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 6)
    val flow = flowFor(space)
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Idle)
    flow.load()
    val summary = flow.state.value as FreeSpaceUi.Summary
    assertThat(summary.plan.itemCount).isEqualTo(6)
    assertThat(summary.plan.totalBytes).isEqualTo(6 * 4_000_000L)
    assertThat(summary.kind).isEqualTo(FreeSpaceKind.ALL)
  }

  @Test
  fun gliEsclusiSiVedonoConIlLoroMotivoENonEntranoNelPiano() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4)
    space.excludedMedia[FreeSpaceSamples.mediaId(1)] = FreeSpaceExclusion.NOT_VERIFIED
    space.excludedMedia[FreeSpaceSamples.mediaId(2)] = FreeSpaceExclusion.IN_PROGRESS_OR_ERROR
    val flow = flowFor(space)
    flow.load()
    val plan = (flow.state.value as FreeSpaceUi.Summary).plan
    assertThat(plan.itemCount).isEqualTo(2)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1, FreeSpaceExclusion.IN_PROGRESS_OR_ERROR, 1)
  }

  @Test
  fun gliUltimiTrentaGiorniRestanoSulTelefonoDiDefault_ELaSceltaDellUtenteNo() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3)
    space.candidates += FreeSpaceSamples.item(10, day = FreeSpaceSamples.TODAY - 5)
    val standard = flowFor(space)
    standard.load()
    val plan = (standard.state.value as FreeSpaceUi.Summary).plan
    assertThat(plan.itemCount).isEqualTo(3)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.RECENT, 1)

    // D22: i media scelti a mano non perdono i giorni recenti.
    val chosen = flowFor(space, selection = setOf(FreeSpaceSamples.mediaId(10)), recentDays = 0)
    chosen.load()
    assertThat((chosen.state.value as FreeSpaceUi.Summary).plan.itemCount).isEqualTo(1)
  }

  @Test
  fun iFiltriCambianoIlPiano() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 6) // 3 e 6 sono video
    val flow = flowFor(space)
    flow.load()
    flow.choose(FreeSpaceKind.VIDEOS_ONLY)
    val videos = flow.state.value as FreeSpaceUi.Summary
    assertThat(videos.kind).isEqualTo(FreeSpaceKind.VIDEOS_ONLY)
    assertThat(videos.plan.itemCount).isEqualTo(2)
    flow.choose(FreeSpaceKind.OLDER_THAN_A_YEAR)
    assertThat((flow.state.value as FreeSpaceUi.Summary).plan.itemCount).isEqualTo(6)
    assertThat(flow.kind).isEqualTo(FreeSpaceKind.OLDER_THAN_A_YEAR)
  }

  @Test
  fun senzaIlDialogoDelSistemaNonSiLiberaNiente() = runTest(dispatcher) {
    val flow = flowFor(FreeSpaceSamples.space(), systemDialogs = false)
    flow.load()
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG))
  }

  @Test
  fun laVerificaPortaAPronto_EValeSeiOreDiOrologioMonotono() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    val ready = flow.state.value as FreeSpaceUi.Ready
    assertThat(ready.items).isEqualTo(4)
    assertThat(ready.bytes).isEqualTo(16_000_000L)
    assertThat(ready.permanent).isFalse()
    assertThat(ready.validUntilMonotonicMs).isEqualTo(clock.monotonicMs() + FreeSpaceRules.VERIFICATION_VALID_MS)
    // Pronto non e' fatto: nessun dialogo e' stato presentato senza il tocco su "Libera ora".
    assertThat(space.presented).isEmpty()
  }

  @Test
  fun ilDialogoPartePerOgniLottoSoloDalTocco_ELaRispostaTornaAlServizio() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    var asked = 0
    flow.free(
      RemoveDialog { batch ->
        asked++
        assertThat(batch.mode).isEqualTo(TrashMode.ANDROID_TRASH)
        assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Confirming(1, 1, permanent = false))
        DialogAnswer.ALLOWED
      },
    )
    assertThat(asked).isEqualTo(1)
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(4)
    assertThat(done.freedBytes).isEqualTo(16_000_000L)
    assertThat(done.toAndroidTrash).isTrue()
    assertThat(done.kept).isEqualTo(0)
    assertThat(done.cancelled).isFalse()
  }

  @Test
  fun tremilaCinquecentoElementiSonoTreLottiEOgniLottoVuoleUnTocco() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4_500, size = 1_000L)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    assertThat((flow.state.value as FreeSpaceUi.Ready).batchesTotal).isEqualTo(3)

    flow.free(allow())
    val afterFirst = flow.state.value as FreeSpaceUi.Ready
    assertThat(afterFirst.batchesDone).isEqualTo(1)
    assertThat(afterFirst.items).isEqualTo(2_500)
    assertThat(space.presented).hasSize(1)

    flow.free(allow())
    flow.free(allow())
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(4_500)
    assertThat(space.presented.map { it.items.size }).containsExactly(2_000, 2_000, 500).inOrder()
  }

  @Test
  fun ilRifiutoDelDialogoNonCambiaNienteENonSiChiedeAltro() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 4_500, size = 1_000L)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    flow.free(RemoveDialog { DialogAnswer.REFUSED })
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.cancelled).isTrue()
    assertThat(done.items).isEqualTo(0)
    assertThat(space.presented).hasSize(1)
    // Un altro tocco non propone un altro lotto: il giro e' finito.
    flow.free(allow())
    assertThat(space.presented).hasSize(1)
  }

  @Test
  fun senzaDialogoDisponibileNienteSiDaPerLiberato() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    flow.free(RemoveDialog { DialogAnswer.NOT_AVAILABLE })
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG))
  }

  @Test
  fun unaCopiaCheNonTornaAllaVerificaEsceEIlRiepilogoFinaleLoDice() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 5)
    space.failVerification += FreeSpaceSamples.mediaId(2)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    assertThat((flow.state.value as FreeSpaceUi.Ready).items).isEqualTo(4)
    flow.free(allow())
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(4)
    assertThat(done.dropped).isEqualTo(1)
    assertThat(done.kept).isEqualTo(0)
  }

  @Test
  fun unaCopiaCambiataDopoLaVerificaEsceAlControlloFinale() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 5)
    space.changeAfterVerification += FreeSpaceSamples.mediaId(3)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    flow.free(allow())
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(4)
    assertThat(done.dropped).isEqualTo(1)
  }

  @Test
  fun seTuttiGliElementiEscono_NonSiLiberaNienteELoDice() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 2)
    space.failVerification += listOf(FreeSpaceSamples.mediaId(1), FreeSpaceSamples.mediaId(2))
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(0)
    assertThat(done.dropped).isEqualTo(2)
  }

  @Test
  fun laVerificaScadutaDopoSeiOreSiRifaDalPiano() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3, clock = clock)
    val flow = flowFor(space)
    flow.load()
    flow.confirmNormal()
    clock.advance(FreeSpaceRules.VERIFICATION_VALID_MS + 1)
    flow.free(allow())
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.EXPIRED))
    assertThat(space.presented).isEmpty()
  }

  @Test
  fun unGiroBloccatoDicePerche() = runTest(dispatcher) {
    for ((block, expected) in listOf(
      FreeSpaceBlock.AREA_FROZEN to FreeSpaceBlockedBy.AREA_FROZEN,
      FreeSpaceBlock.NAS_UNREACHABLE to FreeSpaceBlockedBy.NAS_UNREACHABLE,
      FreeSpaceBlock.NOT_AVAILABLE to FreeSpaceBlockedBy.NOT_AVAILABLE,
      FreeSpaceBlock.NO_SYSTEM_DIALOG to FreeSpaceBlockedBy.NO_SYSTEM_DIALOG,
    )) {
      val space = FreeSpaceSamples.space(count = 2)
      space.block = block
      val flow = flowFor(space)
      flow.load()
      flow.confirmNormal()
      assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(expected))
    }
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonFaPartireNiente() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3)
    val flow = flowFor(space)
    flow.load()
    val other = FreeSpaceSamples.space(count = 2).plan(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter(), false)
    val plan = flow.planNow(permanent = false)
    flow.start(plan, TestConfirmations.confirmForOtherPlan(other, clock.monotonicMs()))
    assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.CONFIRMATION_REJECTED))
    assertThat(space.presented).isEmpty()
  }

  @Test
  fun liberaSubitoUsaLEliminazioneDefinitivaENonIlCestino() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3)
    val flow = flowFor(space)
    flow.load()
    val plan = flow.planNow(permanent = true)
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    flow.start(plan, TestConfirmations.confirm(plan, clock.monotonicMs()))
    assertThat((flow.state.value as FreeSpaceUi.Ready).permanent).isTrue()
    flow.free(
      RemoveDialog { batch ->
        assertThat(batch.mode).isEqualTo(TrashMode.DELETE_NOW)
        assertThat(flow.state.value).isEqualTo(FreeSpaceUi.Confirming(1, 1, permanent = true))
        DialogAnswer.ALLOWED
      },
    )
    val done = flow.state.value as FreeSpaceUi.Done
    assertThat(done.toAndroidTrash).isFalse()
    assertThat(done.items).isEqualTo(3)
  }

  @Test
  fun annullareDuranteLaVerificaTornaAlRiepilogo() = runTest(dispatcher) {
    val flow = flowFor(FreeSpaceSamples.space(count = 3))
    flow.load()
    flow.confirmNormal()
    flow.cancel()
    assertThat(flow.state.value).isInstanceOf(FreeSpaceUi.Summary::class.java)
  }

  @Test
  fun laListaNonCresceMai_UnaSelezioneNonAggiungeMedia() = runTest(dispatcher) {
    val space = FreeSpaceSamples.space(count = 3)
    // Un media scelto che il catalogo non conosce (E-S11): il piano non lo inventa.
    val flow = flowFor(space, selection = setOf(FreeSpaceSamples.mediaId(1), FreeSpaceSamples.mediaId(99)), recentDays = 0)
    flow.load()
    assertThat((flow.state.value as FreeSpaceUi.Summary).plan.items.map { it.mediaId }).containsExactly(FreeSpaceSamples.mediaId(1))
  }
}
