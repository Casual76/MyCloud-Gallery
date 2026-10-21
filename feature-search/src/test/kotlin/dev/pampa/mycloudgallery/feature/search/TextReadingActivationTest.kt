package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReadingSwitch
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationScreen
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationText
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina di attivazione della lettura del testo (Fase 6, P8, §2.6, D41; Q4, Q13): il testo del progetto, la versione del testo salvata col consenso,
 * "Attiva" e "Non ora" con lo stesso peso. ML Kit non parte finche' la pagina non da' il consenso.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class TextReadingActivationTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private val text: String get() = TextReadingActivationText.paragraphs(resources).joinToString("\n") { it.text }

  @Test
  fun ilTestoDiceCosaFaMlKitECosaManda_Q4() {
    assertThat(TextReadingActivationText.paragraphs(resources)).hasSize(4)
    assertThat(text).contains("l’app usa ML Kit di Google, che lavora sul telefono: le foto e il testo non escono mai dal telefono.")
    assertThat(text).contains("ML Kit manda a Google statistiche tecniche: marca e modello del telefono, versione di Android, nome e versione dell’app")
    assertThat(text).contains("un codice di questa installazione, tempi ed eventuali errori")
    assertThat(text).contains("Lo decide Google e non si può spegnere: per questo la funzione è spenta finché non la attivi.")
    assertThat(text).contains("Vale per i dispositivi del tuo account; su ognuno puoi dire «Non su questo dispositivo»")
    assertThat(text).contains("La spegni quando vuoi: da quel momento ML Kit non parte più.")
    assertThat(text).contains("Anche spenta, puoi cercare e copiare il testo che un altro dispositivo della famiglia ha già letto.")
  }

  @Test
  fun ilTestoNonPromettePiuDiQuelloCheE_NienteFotoNeTestoVersoGoogle() {
    // Mai «le foto o il testo vengono inviati»: dice il contrario, e solo statistiche tecniche partono.
    assertThat(text).doesNotContain("inviat")
    assertThat(text.lowercase()).doesNotContain("gratis")
    assertThat(text).contains("non escono mai dal telefono")
  }

  @Test
  fun ilGrassettoCoprePropriol_ECheConta() {
    val bold = TextReadingActivationText.paragraphs(resources).flatMap { p -> p.spanStyles.map { p.text.substring(it.start, it.end) } }
    assertThat(bold).containsExactly("le foto e il testo non escono mai dal telefono", "statistiche tecniche", "«Non su questo dispositivo»").inOrder()
  }

  @Test
  fun attivaDaIlConsensoConLaVersioneDelTestoELIstanteInCuiLaPaginaEraSuSchermo() {
    var consent: TextReadingConsent? = null
    var closed = 0
    rule.setSearchContent { TextReadingActivationScreen(onActivate = { consent = it }, onNotNow = { closed++ }, onBack = {}) }
    val before = System.nanoTime()
    rule.onNodeWithText("Attiva").performClick()
    assertThat(consent).isNotNull()
    assertThat(consent!!.textVersion).isEqualTo(TextReadingSwitch.CURRENT_TEXT_VERSION)
    // L'istante e' quello della pagina sullo schermo (monotono), prima del tocco.
    assertThat(consent!!.shownAtMonotonicNanos).isAtMost(before)
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun nonOraNonDaConsensoENonCambiaNiente() {
    var consent: TextReadingConsent? = null
    var notNow = 0
    rule.setSearchContent { TextReadingActivationScreen(onActivate = { consent = it }, onNotNow = { notNow++ }, onBack = {}) }
    rule.onNodeWithText("Non ora").performClick()
    assertThat(consent).isNull()
    assertThat(notNow).isEqualTo(1)
  }

  @Test
  fun iDuePulsantiHannoLoStessoPeso() {
    rule.setSearchContent { TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {}) }
    val yes = rule.onNodeWithText("Attiva").fetchSemanticsNode().boundsInRoot
    val no = rule.onNodeWithText("Non ora").fetchSemanticsNode().boundsInRoot
    assertThat(yes.width).isWithin(1f).of(no.width)
    assertThat(yes.height).isWithin(1f).of(no.height)
    assertThat(yes.top).isWithin(1f).of(no.top)
    val density = rule.density.density
    assertThat(yes.height / density).isAtLeast(48f)
  }

  @Test
  fun seIlTestoECambiatoLaPaginaDiceDiRileggerlo() {
    rule.setSearchContent { TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {}, versionChanged = true) }
    rule.onNodeWithText("Il testo di questa pagina è cambiato dall’ultima volta che l’hai letto. Per continuare a usare la funzione serve la tua conferma.").assertIsDisplayed()
  }

  @Test
  fun senzaIlCambioDiTestoNonCeLaNota() {
    rule.setSearchContent { TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {}) }
    assertThat(rule.onAllNodesWithText("è cambiato", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilTestoA13xSiLeggeEIPulsantiRestanoDa48Dp() {
    rule.setSearchContent(fontScale = 1.3f) { TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {}) }
    rule.onNodeWithText("Attiva").assertIsDisplayed()
    val density = rule.density.density
    // I due pulsanti della pagina (la barra del titolo e' dell'engine).
    for (label in listOf("Attiva", "Non ora")) {
      assertThat(rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height / density).isAtLeast(47.5f)
    }
  }

  @Test
  fun ilConsensoFinisceNelCommutatoreConLaVersione_FakeCome_VeroCommutatore() {
    val switch = FakeTextReadingSwitch()
    runBlocking { switch.enableForAccount(TextReadingConsent(TextReadingSwitch.CURRENT_TEXT_VERSION, 1L)) }
    assertThat(switch.consents.single().textVersion).isEqualTo(TextReadingSwitch.CURRENT_TEXT_VERSION)
    assertThat(switch.state.value.activeHere).isTrue()
  }
}
