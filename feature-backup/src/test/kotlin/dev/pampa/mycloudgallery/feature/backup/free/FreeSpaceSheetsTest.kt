package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTest
import org.junit.Test

/**
 * I fogli di conferma di "Libera spazio" (D21, C13, R6): l'avviso forte c'e' sempre; "Libera spazio" si conferma con **un** tocco, "Libera subito"
 * con **due gesti distinti** (il secondo in un altro punto, spento per 600 ms, e un doppio tocco non conta); una conferma nasce solo dal tocco.
 */
class FreeSpaceSheetsTest : ComposeBackupTest() {

  private var now = 10_000L
  private val confirmations = ArrayList<PhoneConfirmation>()
  private var cancelled = 0

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun top(text: String): Float = button(text).fetchSemanticsNode().boundsInRoot.top

  /** Fa passare il tempo dei test (l'orologio monotono dei tocchi e quello della composizione). */
  private fun elapse(ms: Long) {
    now += ms
    composeRule.mainClock.advanceTimeBy(ms)
    composeRule.waitForIdle()
  }

  // --- "Libera spazio" -----------------------------------------------------------------------------------------------------------

  @Test
  fun ilFoglioDiLiberaSpazioHaLAvvisoForteEDiceDoveVanno() {
    val plan = FreeSpaceSamples.plan(items = 3, itemSize = 4_000_000L)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    composeRule.onNodeWithText("Liberare spazio?").assertExists()
    composeRule.onNodeWithText("Tolgo dal telefono 3 elementi (12 MB). Restano sul NAS.").assertExists()
    composeRule.onNodeWithText("Il NAS non ha un backup esterno").assertExists()
    composeRule.onNodeWithText("Nel cestino di Android").assertExists()
    composeRule.onNodeWithText("Prima ricontrollo ogni copia").assertExists()
    // Libera spazio non e' "per sempre": il secondo avviso non c'e'.
    assertThat(composeRule.onAllNodes(hasText("Libera subito non si può annullare")).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unSoloTocco_EUnaSolaConferma() {
    val plan = FreeSpaceSamples.plan(items = 3)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    assertThat(confirmations).isEmpty()
    elapse(50)
    button("Sì, libera spazio").assertIsEnabled().performClick()
    assertThat(confirmations).hasSize(1)
    assertThat(confirmations.single().confirmedSteps).isEqualTo(1)
    assertThat(confirmations.single().planHash).isEqualTo(plan.hash)
  }

  @Test
  fun annullareNonConfermaNiente() {
    val plan = FreeSpaceSamples.plan(items = 3)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    button("Annulla").performClick()
    assertThat(cancelled).isEqualTo(1)
    assertThat(confirmations).isEmpty()
  }

  @Test
  fun senzaNienteDaLiberareNonSiConfermaNiente() {
    val plan = FreeSpaceSamples.plan(items = 0)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    composeRule.onNodeWithText("Non c’è niente da liberare con questa scelta.").assertExists()
    button("Sì, libera spazio").assertIsNotEnabled()
  }

  @Test
  fun senzaTocchiDiConfermaIlPulsanteEspento() {
    val plan = FreeSpaceSamples.plan(items = 3)
    show { FreeConfirmContent(plan, taps = null, onConfirm = { confirmations += it }, onCancel = {}) }
    button("Sì, libera spazio").assertIsNotEnabled()
  }

  // --- "Libera subito" --------------------------------------------------------------------------------------------------------------

  @Test
  fun liberaSubitoHaTuttiEDueGliAvvisiEIlPrimoPannello() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmNowContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    composeRule.onNodeWithText("Liberare subito?").assertExists()
    composeRule.onNodeWithText("Il NAS non ha un backup esterno").assertExists()
    composeRule.onNodeWithText("Libera subito non si può annullare").assertExists()
    button("Libera subito").assertExists()
    button("Annulla").assertExists()
  }

  @Test
  fun ilPrimoPulsanteSiAccendeDopoUnAttimo_UnTocco_NonBasta() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmNowContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    button("Libera subito").assertIsNotEnabled()
    elapse(PhoneConfirmationRules.MIN_TAP_GAP_MS + 50)
    button("Libera subito").assertIsEnabled().performClick()
    // Il primo gesto cambia il pannello: ancora nessuna conferma.
    assertThat(confirmations).isEmpty()
    composeRule.onNodeWithText("Ultima conferma").assertExists()
    composeRule.onNodeWithText("Dopo questo non si torna indietro.", substring = true).assertExists()
  }

  @Test
  fun ilSecondoGestoStaInUnAltroPuntoESiAccendeDopo600Ms() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmNowContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    elapse(PhoneConfirmationRules.MIN_TAP_GAP_MS + 50)
    val firstGestureTop = top("Libera subito")
    button("Libera subito").performClick()
    composeRule.waitForIdle()

    // Nel secondo pannello: rinunciare e' in alto, eliminare sta sotto, e non nello stesso punto del primo gesto.
    assertThat(top("Sì, elimina per sempre")).isGreaterThan(top("No, tienili"))
    assertThat(top("Sì, elimina per sempre")).isNotWithin(1f).of(firstGestureTop)
    button("Sì, elimina per sempre").assertIsNotEnabled()

    // Un tocco subito dopo (un doppio tocco) non fa niente.
    button("Sì, elimina per sempre").performClick()
    assertThat(confirmations).isEmpty()

    elapse(PhoneConfirmationRules.MIN_TAP_GAP_MS + 50)
    button("Sì, elimina per sempre").assertIsEnabled().performClick()
    assertThat(confirmations).hasSize(1)
    assertThat(confirmations.single().confirmedSteps).isEqualTo(2)
    assertThat(confirmations.single().planHash).isEqualTo(plan.hash)
  }

  @Test
  fun rinunciareNelSecondoPannelloNonConfermaNiente() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    val taps = ConfirmTaps(plan) { now }
    show { FreeConfirmNowContent(plan, taps, { confirmations += it }, { cancelled++ }) }
    elapse(PhoneConfirmationRules.MIN_TAP_GAP_MS + 50)
    button("Libera subito").performClick()
    composeRule.waitForIdle()
    button("No, tienili").performClick()
    assertThat(cancelled).isEqualTo(1)
    assertThat(confirmations).isEmpty()
    assertThat(taps.taps).isEqualTo(0)
  }

  @Test
  fun liberaSubitoVuoleDueTocchiNelContratto() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    val normal = FreeSpaceSamples.plan(items = 3, permanent = false)
    assertThat(normal.requiredConfirmations).isEqualTo(1)
  }
}
