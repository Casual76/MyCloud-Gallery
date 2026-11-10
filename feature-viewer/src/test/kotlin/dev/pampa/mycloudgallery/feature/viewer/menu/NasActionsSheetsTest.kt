package dev.pampa.mycloudgallery.feature.viewer.menu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I fogli di "Elimina…", "Rinomina" e "Sposta" del viewer: cosa dicono (Solo dal telefono / Ovunque, le vedranno tutti i familiari),
 * che cosa fa il pulsante e, soprattutto, **da dove nasce la conferma**: da un tocco sul pulsante del foglio, mai da una composizione
 * (`ConfirmationIssuer` chiamato solo da un tocco, riga P14 del §4.4).
 */
class NasActionsSheetsTest : NasActionsPhoneTest() {

  private val kit = NasOpsTestKit()
  private val confirmed = mutableListOf<Pair<OpPlan, UserConfirmation>>()
  private var phoneOnly = 0
  private var everywhereDone = 0

  private fun prepare(): NasActionTarget {
    kit.putFile("test", "Viaggi/IMG_1.jpg", byteArrayOf(1, 2, 3, 4))
    kit.putDir("test", "Archivio")
    kit.putDir("test-comune", "Famiglia")
    return runBlocking { KitNasMediaActions(kit).target(KitNasMediaActions.REF)!! }
  }

  private fun plan(request: OpRequest, operations: NasOperations = kit.operations): OpPlan =
    (runBlocking { operations.plan(request) } as PlanOutcome.Ready).plan

  private fun showState(state: NasActionsState) {
    show {
      NasActionSheets(
        state = state,
        monotonicMs = { 5_000L },
        onScope = {},
        onRenameText = {},
        onPick = {},
        onConfirmed = { p, c -> confirmed += p to c },
        onPhoneOnly = { phoneOnly++ },
        onEverywhereDone = { everywhereDone++ },
        onDismiss = {},
      )
    }
  }

  // --- Elimina… --------------------------------------------------------------------------------------------------------------

  @Test
  fun eliminareSulTelefonoESulNasOffreSoloDalTelefonoOvunque() {
    val target = prepare()
    showState(NasActionsState.Delete(target, GalleryDeleteScope.PhoneOnly, plan(OpRequest.Trash(listOf(target.item))), planning = false))
    composeRule.onNodeWithText("Solo dal telefono").assertIsDisplayed()
    composeRule.onNodeWithText("Ovunque").assertIsDisplayed()
    composeRule.onNodeWithText("NAS · La mia area").assertExists()
    // Solo dal telefono: il pulsante non tocca il NAS e non crea nessuna conferma.
    composeRule.onNodeWithText("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
    assertThat(phoneOnly).isEqualTo(1)
    assertThat(confirmed).isEmpty()
  }

  @Test
  fun unVideoSulTelefonoESulNasHaIlTitoloDelVideo_unaFotoQuelloDellaFoto() {
    val base = prepare()
    val request = plan(OpRequest.Trash(listOf(base.item)))
    val video = NasActionTarget(base.item, base.scope, writable = true, onPhone = true, isVideo = true, destinations = base.destinations)
    showState(NasActionsState.Delete(video, GalleryDeleteScope.PhoneOnly, request, planning = false))
    composeRule.onNodeWithText("Eliminare questo video?").assertIsDisplayed()
    composeRule.onNodeWithText("Eliminare questa foto?").assertDoesNotExistCompat()
    composeRule.onNodeWithText("Solo dal telefono").assertIsDisplayed()
  }

  @Test
  fun unaFotoSulTelefonoESulNasTieneIlTitoloDiSerie() {
    val base = prepare()
    val photo = NasActionTarget(base.item, base.scope, writable = true, onPhone = true, isVideo = false, destinations = base.destinations)
    showState(NasActionsState.Delete(photo, GalleryDeleteScope.PhoneOnly, plan(OpRequest.Trash(listOf(base.item))), planning = false))
    composeRule.onNodeWithText("Eliminare questa foto?").assertIsDisplayed()
    composeRule.onNodeWithText("Eliminare questo video?").assertDoesNotExistCompat()
  }

  @Test
  fun ovunqueConfermaIlCestinoDelNasConUnTocco_EPoiToccaIlTelefono() {
    val target = prepare()
    val plan = plan(OpRequest.Trash(listOf(target.item)))
    showState(NasActionsState.Delete(target, GalleryDeleteScope.Everywhere, plan, planning = false))
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).hasSize(1)
    assertThat(confirmed.single().first).isSameInstanceAs(plan)
    assertThat(confirmed.single().second.confirmedSteps).isEqualTo(1)
    assertThat(everywhereDone).isEqualTo(1)
    assertThat(phoneOnly).isEqualTo(0)
  }

  @Test
  fun mentreIlPianoEInCalcoloUnTocco_suOvunqueNonFaNiente() {
    val target = prepare()
    showState(NasActionsState.Delete(target, GalleryDeleteScope.Everywhere, plan = null, planning = true))
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()
    assertThat(everywhereDone).isEqualTo(0)
    assertThat(phoneOnly).isEqualTo(0)
  }

  @Test
  fun unaCopiaInSolaLetturaSiDiceENonSiTocca() {
    val target = prepare()
    val withReadOnly = NasActionTarget(
      target.item, target.scope, writable = true, onPhone = true, isVideo = false,
      others = listOf(NasLocationInfo(NasLocationInfo.Kind.NAS_COMMON, writable = false)),
      destinations = target.destinations,
    )
    showState(NasActionsState.Delete(withReadOnly, GalleryDeleteScope.Everywhere, plan(OpRequest.Trash(listOf(target.item))), planning = false))
    composeRule.onNodeWithText("NAS · Area comune").assertExists()
    composeRule.onNodeWithText("Non hai il permesso di eliminare qui.").assertExists()
  }

  @Test
  fun soloSulNasNonCeLaSceltaDelLuogo() {
    val target = NasActionTarget(prepare().item, NasAreaScope.PRIVATE, writable = true, onPhone = false, isVideo = true)
    val plan = plan(OpRequest.Trash(listOf(target.item)))
    showState(NasActionsState.Delete(target, GalleryDeleteScope.Everywhere, plan, planning = false))
    composeRule.onNodeWithText("Eliminare questo video dal NAS?").assertIsDisplayed()
    composeRule.onNodeWithText("Solo dal telefono").assertDoesNotExistCompat()
    composeRule.onNodeWithText("Elimina dal NAS").assertIsEnabled().performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(plan)
  }

  // --- Rinomina --------------------------------------------------------------------------------------------------------------

  @Test
  fun rinominareConfermaSoloConUnTocco() {
    val target = prepare()
    val ready = plan(OpRequest.Rename(target.item, "Tramonto.jpg"))
    showState(NasActionsState.Rename(target, "Tramonto.jpg", RenameCheck.Ready(ready)))
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()
    composeRule.onNodeWithText("Il nome va bene.").assertExists()
    composeRule.renameButton().assertIsEnabled().performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(ready)
  }

  @Test
  fun unNomeNonValidoSpegneIlPulsanteEProponeLaCorrezione() {
    val target = prepare()
    showState(NasActionsState.Rename(target, "A:B ", RenameCheck.Invalid("A_B")))
    composeRule.onNodeWithText("Prova con «A_B»").assertIsDisplayed()
    composeRule.renameButton().assertIsNotEnabled()
    assertThat(confirmed).isEmpty()
  }

  // --- Sposta ----------------------------------------------------------------------------------------------------------------

  @Test
  fun spostareNellAreaComuneDiceCheLeVedrannoTuttiIFamiliari() {
    val target = prepare()
    val common = target.destinations.first { it.scope == NasAreaScope.COMMON }
    val plan = plan(OpRequest.Move(listOf(target.item), common.share, common.folder))
    showState(NasActionsState.ConfirmMove(target, common, plan))
    composeRule.onNodeWithText("Spostare nell'area comune?").assertIsDisplayed()
    composeRule.onNodeWithText("La foto verrà spostata nell'area comune e la vedranno tutti i familiari.").assertExists()
    composeRule.onNodeWithText("Le vedranno tutti i familiari").assertExists()
    composeRule.onNodeWithText("Prima copio, poi controllo").assertExists()
    assertThat(confirmed).isEmpty()
    composeRule.onNodeWithText("Sposta nell'area comune").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(plan)
    assertThat(confirmed.single().second.confirmedSteps).isEqualTo(1)
  }

  @Test
  fun laScelteDellaDestinazioneElencaLeAreeEChiedeIlPianoSoloAlTocco() {
    val target = prepare()
    var picked: NasMoveDestination? = null
    show {
      NasActionSheets(
        state = NasActionsState.PickDestination(target),
        monotonicMs = { 0L }, onScope = {}, onRenameText = {}, onPick = { picked = it },
        onConfirmed = { _, _ -> error("Scegliere la destinazione non conferma niente") }, onPhoneOnly = {}, onEverywhereDone = {}, onDismiss = {},
      )
    }
    composeRule.onNodeWithText("Area comune · Famiglia").assertIsDisplayed()
    composeRule.onNodeWithText("La mia area · Archivio").assertIsDisplayed()
    assertThat(picked).isNull()
    composeRule.onNodeWithText("Area comune · Famiglia").performClick()
    composeRule.waitForIdle()
    assertThat(picked?.scope).isEqualTo(NasAreaScope.COMMON)
  }

  // --- Il codice -------------------------------------------------------------------------------------------------------------

  private val sources: List<File> by lazy {
    File("src/main/kotlin/dev/pampa/mycloudgallery/feature/viewer/menu").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  /** Il codice senza i commenti (quelli di blocco e di riga): possono nominare cio' che qui si vieta. */
  private fun code(file: File): String = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString(separator = System.lineSeparator()) { it.substringBefore("//") }

  @Test
  fun lConfermaNasceSoloDaUnTocco_nelFoglio() {
    assertThat(sources.map { it.name }).containsAtLeast("NasActions.kt", "NasActionsController.kt", "NasActionsSheets.kt")
    for (file in sources) {
      assertWithMessage("${file.name}: ConfirmationIssuer").that(Regex("""\bConfirmationIssuer\b""").containsMatchIn(code(file))).isFalse()
      assertWithMessage("${file.name}: costruttori di UserConfirmation o AcceptedPlan").that(Regex("""\b(UserConfirmation|AcceptedPlan)\s*\(""").containsMatchIn(code(file))).isFalse()
      if (file.name != "NasActionsSheets.kt") {
        assertWithMessage("${file.name}: ConfirmationSheet o tap").that(Regex("""\bConfirmationSheet\b|\.tap\s*\(""").containsMatchIn(code(file))).isFalse()
      }
    }
    // Ogni `.tap(` del foglio sta dentro un gestore `onConfirm = {`.
    val lines = sources.single { it.name == "NasActionsSheets.kt" }.readLines()
    var taps = 0
    lines.forEachIndexed { index, line ->
      if (Regex("""\.tap\s*\(""").containsMatchIn(line.substringBefore("//"))) {
        taps++
        val before = lines.subList(maxOf(0, index - 14), index + 1).joinToString("\n")
        assertWithMessage("NasActionsSheets.kt:${index + 1}: tap fuori dal gestore del pulsante").that(before).contains("onConfirm = {")
      }
    }
    assertThat(taps).isEqualTo(3)
  }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

/** Il pulsante "Rinomina" del foglio (il titolo ha lo stesso testo ma non si tocca). */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.renameButton() =
  onAllNodes(androidx.compose.ui.test.hasText("Rinomina") and androidx.compose.ui.test.hasClickAction()).onFirst()
