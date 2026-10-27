package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L'avviso sugli altri dispositivi dell'account (docs/fase8-progetto.md §2.1, D4, E-F7): la logica (compare solo con l'avviso da vedere, "Va bene" e
 * "Non su questo dispositivo" cambiano lo stato dell'interruttore e lo fanno sparire) e la pagina (testo, stesso peso, 48 dp, 1,3x).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class FacesNoticeTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  // --- La logica -----------------------------------------------------------------------------------------------------------------------

  /** Porta in vista la riga con questo testo: la pagina e' una lista pigra, e cio' che sta sotto lo schermo non esiste finche' non ci si arriva scorrendo. */
  private fun node(label: String, substring: Boolean = false): SemanticsNodeInteraction {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label, substring = substring))
    return rule.onNodeWithText(label, substring = substring)
  }

  @Test
  fun compareSoloMentreSiAspettaLAvviso() {
    val awaiting = FacesNoticeController(awaitingSwitch(), null, immediateScope())
    assertThat(awaiting.state.value).isEqualTo(FacesNoticeState.Show(1, null))
    for (switch in listOf(activeSwitch(), activeSwitch(here = HereState.EXCLUDED), dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch())) {
      assertThat(FacesNoticeController(switch, null, immediateScope()).state.value).isEqualTo(FacesNoticeState.Hidden)
    }
  }

  @Test
  fun vaBeneConfermaLAvvisoESparisce() {
    val switch = awaitingSwitch()
    val c = FacesNoticeController(switch, null, immediateScope())
    c.accept()
    assertThat(switch.state.value).isEqualTo(FacesState.On(sampleGeneration, HereState.ACTIVE))
    assertThat(c.state.value).isEqualTo(FacesNoticeState.Hidden)
  }

  @Test
  fun nonSuQuestoDispositivoEscludeESparisce() {
    val switch = awaitingSwitch()
    val c = FacesNoticeController(switch, null, immediateScope())
    c.excludeHere()
    assertThat(switch.state.value).isEqualTo(FacesState.On(sampleGeneration, HereState.EXCLUDED))
    assertThat(c.state.value).isEqualTo(FacesNoticeState.Hidden)
    assertThat(switch.erasedLocally).isEmpty()
  }

  @Test
  fun ilNomeDelDispositivoCheHaAttivatoArrivaSeSi_ESenzaNonSiPerdeLAvviso() {
    val named = FacesNoticeController(awaitingSwitch(), FacesActivatedBy { "Tablet di casa" }, immediateScope())
    named.load()
    assertThat(named.state.value).isEqualTo(FacesNoticeState.Show(1, "Tablet di casa"))
    val unnamed = FacesNoticeController(awaitingSwitch(), FacesActivatedBy { null }, immediateScope())
    unnamed.load()
    assertThat(unnamed.state.value).isEqualTo(FacesNoticeState.Show(1, null))
    val blank = FacesNoticeController(awaitingSwitch(), FacesActivatedBy { "  " }, immediateScope())
    blank.load()
    assertThat(blank.state.value).isEqualTo(FacesNoticeState.Show(1, null))
    val failing = FacesNoticeController(awaitingSwitch(), FacesActivatedBy { error("registro non disponibile") }, immediateScope())
    failing.load()
    assertThat(failing.state.value).isEqualTo(FacesNoticeState.Show(1, null))
  }

  @Test
  fun unAvvisoGiaVistoNonTornaDaSolo() {
    val switch = awaitingSwitch()
    val c = FacesNoticeController(switch, null, immediateScope())
    c.accept()
    c.accept()
    assertThat(c.state.value).isEqualTo(FacesNoticeState.Hidden)
  }

  // --- La pagina -----------------------------------------------------------------------------------------------------------------------

  private class Calls {
    var ok = 0
    var exclude = 0
    var read = 0
  }

  private fun show(state: FacesNoticeState, fontScale: Float? = null, dark: Boolean = false): Calls {
    val calls = Calls()
    rule.setPeopleContent(dark = dark, fontScale = fontScale) {
      FacesNoticeScreen(
        state = state,
        onOk = { calls.ok++ },
        onExclude = { calls.exclude++ },
        onRead = { calls.read++ },
        onBack = {},
      )
    }
    return calls
  }

  @Test
  fun ilTestoDiceChiHaAttivatoEChePrimaDellaSceltaNienteSiTratta() {
    show(FacesNoticeState.Show(1, "Tablet di casa"))
    rule.onNodeWithText("Hai attivato Persone da un altro tuo dispositivo. Qui vedrai le persone e i loro nomi.", substring = true).assertIsDisplayed()
    rule.onNodeWithText("Attivato da Tablet di casa.").assertIsDisplayed()
    rule.onNodeWithText("Finché non scegli, questo dispositivo non scarica, non calcola e non mostra niente.").assertIsDisplayed()
  }

  @Test
  fun senzaIlNomeNonCeLaRigaAttivatoDa() {
    show(FacesNoticeState.Show(1, null))
    assertThat(rule.onAllNodesWithText("Attivato da", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nessunaTracciaDellaFamiglia() {
    show(FacesNoticeState.Show(1, "Tablet di casa"))
    // Q3: l'avviso parla dei dispositivi dello stesso account, mai di altri familiari.
    assertThat(rule.onAllNodesWithText("famigli", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun vaBeneENonSuQuestoDispositivoHannoLoStessoPesoESonoDa48Dp() {
    val calls = show(FacesNoticeState.Show(1, null))
    val ok = node("Va bene").fetchSemanticsNode().boundsInRoot
    val no = rule.onNodeWithText("Non su questo dispositivo").fetchSemanticsNode().boundsInRoot
    assertThat(ok.width).isWithin(1f).of(no.width)
    assertThat(ok.height).isWithin(1f).of(no.height)
    // Uno sotto l'altro, a tutta larghezza: l'etichetta lunga non si taglia nemmeno a testo grande.
    assertThat(no.top).isGreaterThan(ok.top)
    assertThat(ok.left).isWithin(1f).of(no.left)
    assertThat(ok.height / rule.density.density).isAtLeast(47.5f)
    rule.onNodeWithText("Va bene").performClick()
    rule.onNodeWithText("Non su questo dispositivo").performClick()
    assertThat(calls.ok).isEqualTo(1)
    assertThat(calls.exclude).isEqualTo(1)
    assertThat(calls.read).isEqualTo(0)
  }

  @Test
  fun leggiComeFunzionaApreLaPaginaSenzaScegliere() {
    val calls = show(FacesNoticeState.Show(1, null))
    node("Leggi come funziona").performClick()
    assertThat(calls.read).isEqualTo(1)
    assertThat(calls.ok + calls.exclude).isEqualTo(0)
  }

  @Test
  fun iPulsantiHannoUnaDescrizionePerTalkBack() {
    show(FacesNoticeState.Show(1, null))
    rule.onNode(hasContentDescription("Va bene: Persone si attiva anche su questo dispositivo")).assertExists()
    rule.onNode(hasContentDescription("Non su questo dispositivo: Persone non lavora qui e non lascia niente")).assertExists()
  }

  @Test
  fun aTestoGrandeIPulsantiSiRaggiungonoERestanoDa48Dp() {
    show(FacesNoticeState.Show(1, "Tablet di casa"), fontScale = 1.3f)
    val density = rule.density.density
    for (label in listOf("Va bene", "Non su questo dispositivo", "Leggi come funziona")) {
      node(label).assertIsDisplayed()
      assertThat(rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height / density).isAtLeast(47.5f)
    }
  }

  @Test
  fun nascostoNonSiVedeNiente() {
    show(FacesNoticeState.Hidden)
    assertThat(rule.onAllNodesWithText("Va bene").fetchSemanticsNodes()).isEmpty()
  }
}
