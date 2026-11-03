package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheet
import org.junit.Test

/**
 * R11 della revisione 4B, scelta dell'utente del 15/10: nel foglio di uno spostamento nell'area comune con dei media **nascosti** ci sono
 * due scelte, "Lascia i N nascosti dove sono" (di partenza, la prudente) e "Sposta tutto (N nascosti diventano visibili ai familiari)".
 * Sono due piani con due impronte: la conferma nasce dal piano scelto e non vale per l'altro.
 */
class NasHiddenChoiceTest : ComposeNasTest() {

  private val areas = FakeNasAreas.defaultAreas()
  private val confirmed = mutableListOf<Pair<OpPlan, UserConfirmation>>()
  private var clock = 5_000L

  private val everything = NasFixtures.moveToCommonPlan()
  private val leaving = NasFixtures.moveToCommonLeavingHiddenPlan()

  private val leaveText = "Lascia i 2 nascosti dove sono"
  private val allText = "Sposta tutto (2 nascosti diventano visibili ai familiari)"
  private val moveText = "Sposta nell'area comune"

  /**
   * La riga di una scelta: il nodo che dice di essere un radio (scelto o no) e che contiene il testo. Fino al Fluid Engine 2.13 il testo stava in un figlio;
   * dalla 3.0 `FluidListRow` e' un nodo solo (il modificatore sta sul `ListItem`) e il testo e' nel nodo stesso: il cercatore accetta le due forme.
   */
  private fun radio(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and (hasText(text) or hasAnyDescendant(hasText(text)))

  private fun showSheet(plan: OpPlan = everything, hidden: Int = 2, leavePlan: OpPlan? = leaving) {
    show {
      NasConfirmSheet(
        plan = plan,
        areas = areas,
        monotonicMs = { clock },
        onConfirmed = { p, c -> confirmed += p to c },
        onDismiss = {},
        hidden = hidden,
        leavePlan = leavePlan,
      )
    }
  }

  @Test
  fun senzaNascostiIlFoglioNonHaScelte() {
    showSheet(hidden = 0, leavePlan = null)
    composeRule.onNodeWithText("Spostare 312 elementi?").assertIsDisplayed()
    composeRule.onNodeWithText(leaveText).assertDoesNotExist()
    composeRule.onNodeWithText(allText).assertDoesNotExist()
    composeRule.onNodeWithText(moveText).performClick()
    composeRule.waitForIdle()
    // Come prima: un tocco, il piano del foglio.
    assertThat(confirmed.single().first).isSameInstanceAs(everything)
  }

  @Test
  fun conINascostiCiSonoDueScelte_ELaPrudenteEPreselezionata() {
    showSheet()
    composeRule.onNode(radio(leaveText)).assertIsDisplayed().assertIsSelected()
    composeRule.onNode(radio(allText)).assertIsDisplayed().assertIsNotSelected()
    // Ogni scelta dice cosa succede ai nascosti.
    composeRule.onNodeWithText("Restano nella cartella d'origine, nella tua area privata. Si sposta il resto.").assertExists()
    composeRule.onNodeWithText("Finiscono nell'area comune insieme al resto.").assertExists()
    // Quello che resta dov'e' lo dice anche il riepilogo: sono gli esclusi del piano.
    composeRule.onNodeWithText("Restano dove sono").assertExists()
    composeRule.onNode(hasText("nascosti da te (2)", substring = true)).assertExists()
    // Il vecchio avviso "ci sono elementi che hai nascosto" non c'e' piu': la scelta lo sostituisce.
    composeRule.onNodeWithText("Ci sono elementi che hai nascosto").assertDoesNotExist()
    // L'area comune resta detta.
    composeRule.onNodeWithText("Le vedranno tutti i familiari").assertExists()
  }

  @Test
  fun senzaToccareLeScelteLaConfermaEDelPianoCheLasciaINascosti() {
    showSheet()
    composeRule.onNodeWithText("Spostare 310 elementi?").assertIsDisplayed()
    composeRule.onNodeWithText(moveText).performClick()
    composeRule.waitForIdle()
    val (plan, confirmation) = confirmed.single()
    assertThat(plan).isSameInstanceAs(leaving)
    assertThat(ConfirmationRules.check(leaving, confirmation, clock)).isEqualTo(ConfirmationCheck.Valid)
    // Quella conferma non vale per "sposta tutto": i nascosti non finiscono nell'area comune per un tocco dato a un altro piano.
    assertThat(ConfirmationRules.check(everything, confirmation, clock)).isEqualTo(ConfirmationCheck.WrongPlan)
  }

  @Test
  fun scegliendoSpostaTuttoLaConfermaEDelPianoCheSpostaTutto() {
    showSheet()
    composeRule.onNode(radio(allText)).performClick()
    composeRule.waitForIdle()
    composeRule.onNode(radio(allText)).assertIsSelected()
    composeRule.onNode(radio(leaveText)).assertIsNotSelected()
    // Il foglio segue la scelta: titolo e numeri sono quelli del piano che sposta tutto.
    composeRule.onNodeWithText("Spostare 312 elementi?").assertIsDisplayed()
    composeRule.onNodeWithText(moveText).performClick()
    composeRule.waitForIdle()
    val (plan, confirmation) = confirmed.single()
    assertThat(plan).isSameInstanceAs(everything)
    assertThat(ConfirmationRules.check(everything, confirmation, clock)).isEqualTo(ConfirmationCheck.Valid)
    assertThat(ConfirmationRules.check(leaving, confirmation, clock)).isEqualTo(ConfirmationCheck.WrongPlan)
  }

  @Test
  fun ripensandosiSiTornaAlPianoPrudente() {
    showSheet()
    composeRule.onNode(radio(allText)).performClick()
    composeRule.onNode(radio(leaveText)).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText(moveText).performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(leaving)
  }

  @Test
  fun seTuttoENascostoLaScelteCeMaLasciandoliNonCeNienteDaConfermare() {
    showSheet(leavePlan = null)
    composeRule.onNode(radio(leaveText)).assertIsSelected()
    composeRule.onNodeWithText("Non resterebbe niente da spostare: sono tutti nascosti.").assertExists()
    // Il pulsante lo dice e non fa niente: nessuna conferma nasce.
    composeRule.onNodeWithText("Niente da spostare").assertIsNotEnabled()
    composeRule.onNodeWithText("Niente da spostare").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()
    // Scegliere di spostare tutto, sapendolo, e' possibile.
    composeRule.onNode(radio(allText)).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText(moveText).assertIsEnabled()
    composeRule.onNodeWithText(moveText).performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(everything)
  }

  @Test
  fun conUnSoloNascostoSiLeggeAlSingolare() {
    showSheet(hidden = 1)
    composeRule.onNode(radio("Lascia 1 nascosto dove sta")).assertIsSelected()
    composeRule.onNode(radio("Sposta tutto (1 nascosto diventa visibile ai familiari)")).assertIsNotSelected()
  }

  @Test
  fun spostandoNellaStessaAreaNonCeNessunaScelta() {
    // Dentro la stessa area i nascosti non diventano visibili a nessuno in piu': nessuna scelta, il piano del foglio.
    val sameArea = NasFixtures.moveSameAreaPlan()
    show {
      NasConfirmSheet(
        plan = sameArea,
        areas = areas,
        monotonicMs = { clock },
        onConfirmed = { p, c -> confirmed += p to c },
        onDismiss = {},
        hidden = 3,
        leavePlan = leaving,
      )
    }
    composeRule.onAllNodes(hasText("nascost", substring = true)).assertCountEquals(0)
    composeRule.onNodeWithText("Sposta").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().first).isSameInstanceAs(sameArea)
  }
}
