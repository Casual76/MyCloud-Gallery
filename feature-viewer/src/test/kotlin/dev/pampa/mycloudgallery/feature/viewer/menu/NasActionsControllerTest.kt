package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
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

/**
 * Le voci del menu del viewer per un media del NAS, sul NAS finto di core-nasops: cosa compare (e cosa si spegne in un'area in
 * sola lettura, E-N9), il piano per "Elimina…" (E-D1, E-D2), il nome controllato mentre si scrive, lo spostamento nell'area comune e
 * la regola che **niente parte senza una conferma costruita dal foglio dopo un tocco**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NasActionsControllerTest {

  private val kit = NasOpsTestKit()
  private val ref = KitNasMediaActions.REF

  private fun controller(scope: TestScope, actions: KitNasMediaActions = KitNasMediaActions(kit)) =
    NasActionsController(actions, scope.backgroundScope)

  private fun prepare() {
    kit.putFile("test", "Viaggi/IMG_1.jpg", byteArrayOf(1, 2, 3, 4))
    kit.putDir("test", "Archivio")
    kit.putDir("test-comune", "Famiglia")
  }

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  // --- Il menu ---------------------------------------------------------------------------------------------------------------

  private fun entries(capabilities: NasActionCapabilities): NasMenuEntries =
    nasMenuEntries(resources, capabilities, NasActionHandlers(onMove = {}, onRename = {}, onDelete = {}))

  private fun GalleryMenuEntry.item() = this as GalleryMenuEntry.Item

  @Test
  fun unMediaScrivibileHaLeTreVoci_ElEliminaCheCancellaInFondo() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    val menu = entries(NasActionCapabilities.of(c.target.value))
    assertThat(menu.actions.map { it.item().label }).containsExactly("Sposta", "Rinomina").inOrder()
    assertThat(menu.actions.all { it.item().enabled }).isTrue()
    val delete = menu.delete.single().item()
    assertThat(delete.label).isEqualTo("Elimina…")
    assertThat(delete.destructive).isTrue()
    assertThat(delete.enabled).isTrue()
  }

  @Test
  fun senzaDestinazioniNonCompareSposta() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this, KitNasMediaActions(kit, destinations = emptyList()))
    c.bind(ref)
    runCurrent()
    assertThat(entries(NasActionCapabilities.of(c.target.value)).actions.map { it.item().label }).containsExactly("Rinomina")
  }

  @Test
  fun inSolaLetturaLeVociCiSonoMaSpenteConLaSpiegazione() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this, KitNasMediaActions(kit, writable = false))
    c.bind(ref)
    runCurrent()
    val menu = entries(NasActionCapabilities.of(c.target.value))
    assertThat(menu.actions.map { it.item().label }).containsExactly("Area in sola lettura per te", "Sposta", "Rinomina").inOrder()
    // Le tre azioni sono spente; la riga che spiega e' leggibile (una voce spenta si disegna al 38%).
    assertThat((menu.actions + menu.delete).filter { it.item().label != "Area in sola lettura per te" }.all { !it.item().enabled }).isTrue()
    assertThat(menu.actions.first().item().enabled).isTrue()
    // E non parte niente nemmeno forzando i gesti.
    c.requestDelete()
    c.requestRename()
    c.requestMove()
    runCurrent()
    assertThat(c.state.value).isEqualTo(NasActionsState.Idle)
  }

  @Test
  fun unMediaCheNonEDelNasNonHaVoci() = runTest(StandardTestDispatcher()) {
    val c = controller(this)
    c.bind(MediaRef.Phone(volume = "external_primary", mediaStoreId = 1L, mime = "image/jpeg"))
    runCurrent()
    assertThat(c.target.value).isNull()
    assertThat(entries(NasActionCapabilities.of(c.target.value)).isEmpty).isTrue()
  }

  // --- Elimina… --------------------------------------------------------------------------------------------------------------

  @Test
  fun eliminareChiedeIlPianoDelCestinoESoloUnaConfermaDelFoglioLoAvvia() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    c.requestDelete()
    runCurrent()
    val state = c.state.value as NasActionsState.Delete
    // Sul telefono e sul NAS: il luogo di default e' il piu' prudente (solo dal telefono).
    assertThat(state.scope).isEqualTo(GalleryDeleteScope.PhoneOnly)
    val plan = checkNotNull(state.plan)
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isTrue()

    c.setScope(GalleryDeleteScope.Everywhere)
    assertThat((c.state.value as NasActionsState.Delete).scope).isEqualTo(GalleryDeleteScope.Everywhere)
    c.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(c.monotonicMs())))
    runCurrent()
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isFalse()
    assertThat(c.state.value).isEqualTo(NasActionsState.Idle)
    assertThat(c.notice.value?.started).isTrue()
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonAvviaNiente() = runTest(StandardTestDispatcher()) {
    prepare()
    kit.putFile("test", "Viaggi/IMG_2.jpg", byteArrayOf(9))
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    c.requestDelete()
    runCurrent()
    val plan = checkNotNull((c.state.value as NasActionsState.Delete).plan)
    val other = (kit.operations.plan(dev.pampa.mycloudgallery.core.nasops.contract.OpRequest.Trash(listOf(kit.itemOf("test", "Viaggi/IMG_2.jpg")))) as dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome.Ready).plan
    c.confirmed(plan, checkNotNull(ConfirmationSheet(other).tap(c.monotonicMs())))
    runCurrent()
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isTrue()
    assertThat(kit.exists("test", "Viaggi/IMG_2.jpg")).isTrue()
    assertThat(c.notice.value?.started).isFalse()
    val wrong = checkNotNull(ConfirmationSheet(other).tap(c.monotonicMs()))
    assertThat(ConfirmationRules.check(plan, wrong, c.monotonicMs())).isEqualTo(ConfirmationCheck.WrongPlan)
  }

  @Test
  fun soloNelNasNonCeLaSceltaDelLuogo() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this, KitNasMediaActions(kit, onPhone = false))
    c.bind(ref)
    runCurrent()
    c.requestDelete()
    runCurrent()
    val state = c.state.value as NasActionsState.Delete
    assertThat(state.scope).isEqualTo(GalleryDeleteScope.Everywhere)
    assertThat(state.target.onPhone).isFalse()
    assertThat(state.plan).isNotNull()
  }

  @Test
  fun chiudereIlFoglioNonAvviaNiente() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    c.requestDelete()
    runCurrent()
    c.dismiss()
    runCurrent()
    assertThat(c.state.value).isEqualTo(NasActionsState.Idle)
    assertThat(kit.operations.operations.value).isEmpty()
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isTrue()
  }

  // --- Rinomina --------------------------------------------------------------------------------------------------------------

  @Test
  fun ilNuovoNomeSiControllaMentreSiScriveEPoiSiRinomina() = runTest(StandardTestDispatcher()) {
    prepare()
    kit.putFile("test", "Viaggi/IMG_2.jpg", byteArrayOf(9))
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    c.requestRename()
    assertThat((c.state.value as NasActionsState.Rename).text).isEqualTo("IMG_1.jpg")
    assertThat((c.state.value as NasActionsState.Rename).check).isEqualTo(RenameCheck.Same)

    c.onRenameText("Tramonto.jpg")
    assertThat((c.state.value as NasActionsState.Rename).check).isEqualTo(RenameCheck.Checking)
    advanceTimeBy(300)
    runCurrent()
    val plan = ((c.state.value as NasActionsState.Rename).check as RenameCheck.Ready).plan

    c.onRenameText("IMG_2.jpg")
    advanceTimeBy(300)
    runCurrent()
    assertThat((c.state.value as NasActionsState.Rename).check).isEqualTo(RenameCheck.Exists)

    c.onRenameText("A:B")
    assertThat(((c.state.value as NasActionsState.Rename).check as RenameCheck.Invalid).suggestion).isEqualTo("A_B")

    c.onRenameText("Tramonto.jpg")
    advanceTimeBy(300)
    runCurrent()
    val ready = ((c.state.value as NasActionsState.Rename).check as RenameCheck.Ready).plan
    c.confirmed(ready, checkNotNull(ConfirmationSheet(ready).tap(c.monotonicMs())))
    runCurrent()
    assertThat(kit.exists("test", "Viaggi/Tramonto.jpg")).isTrue()
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isFalse()
    assertThat(plan.kind).isEqualTo(OpKind.RENAME)
  }

  // --- Sposta ----------------------------------------------------------------------------------------------------------------

  @Test
  fun spostareNellAreaComuneChiedeIlPianoConLaCopiaFraAreeEPoiLaConferma() = runTest(StandardTestDispatcher()) {
    prepare()
    val c = controller(this)
    c.bind(ref)
    runCurrent()
    c.requestMove()
    val pick = c.state.value as NasActionsState.PickDestination
    val common = pick.target.destinations.first { it.scope == NasAreaScope.COMMON }
    c.pick(common)
    runCurrent()
    val confirm = c.state.value as NasActionsState.ConfirmMove
    assertThat(confirm.plan.kind).isEqualTo(OpKind.MOVE)
    assertThat(confirm.plan.warnings).contains(PlanWarning.CROSS_SHARE_COPY)
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isTrue()

    c.confirmed(confirm.plan, checkNotNull(ConfirmationSheet(confirm.plan).tap(c.monotonicMs())))
    runCurrent()
    assertThat(kit.exists("test-comune", "Famiglia/IMG_1.jpg")).isTrue()
    assertThat(kit.exists("test", "Viaggi/IMG_1.jpg")).isFalse()
  }

  @Test
  fun spostareNelleStesseCartelleERifiutatoConLaSuaFrase() = runTest(StandardTestDispatcher()) {
    prepare()
    val here = NasMoveDestination("test", dev.pampa.mycloudgallery.core.trust.nas.SharePath.of("Viaggi"), NasAreaScope.PRIVATE, "La mia area · Viaggi")
    val c = controller(this, KitNasMediaActions(kit, destinations = listOf(here)))
    c.bind(ref)
    runCurrent()
    c.requestMove()
    c.pick(here)
    runCurrent()
    assertThat(c.state.value).isEqualTo(NasActionsState.Idle)
    assertThat(c.notice.value).isNotNull()
    assertThat(c.notice.value?.started).isFalse()
  }
}
