package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P11 per "Salva sul NAS" (copia in corso, pronta con e senza area comune, con i rifiuti, bloccata, non pronta, niente da salvare, in
 * coda con e senza NAS, errore): chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo grande (1,3x).
 *
 * `gradlew :feature-integrations:recordRoborazziDebug --tests "*ReceiveScreenshot*"` scrive i PNG in `docs/screenshots/5-P11/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class ReceiveScreenshotBase(private val dark: Boolean, private val device: String) : ReceiveComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  @Composable
  private fun Screen(ui: ReceiveUi) = ReceiveScreen(ui = ui, actions = ReceiveActions())

  @Test fun copiaInCorso() = shoot("copia-in-corso") { Screen(ReceiveUiSamples.staging()) }

  @Test fun prontaAreaPrivata() = shoot("pronta-area-privata") { Screen(ReceiveUiSamples.ready()) }

  @Test fun prontaAreaPrivataTestoGrande() = shoot("pronta-area-privata", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.ready()) }

  @Test fun prontaAreaComuneConAvviso() = shoot("pronta-area-comune") { Screen(ReceiveUiSamples.ready(common = true)) }

  @Test fun prontaAreaComuneConAvvisoTestoGrande() = shoot("pronta-area-comune", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.ready(common = true)) }

  @Test fun prontaConIRifiuti() = shoot("pronta-con-rifiuti") { Screen(ReceiveUiSamples.ready(rejected = ReceiveUiSamples.manyRejected)) }

  @Test fun prontaConIRifiutiTestoGrande() = shoot("pronta-con-rifiuti", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.ready(rejected = ReceiveUiSamples.manyRejected)) }

  @Test fun prontaInUnaSottocartella() = shoot("pronta-sottocartella") { Screen(ReceiveUiSamples.ready(deep = true)) }

  @Test fun bloccata() = shoot("bloccata") { Screen(ReceiveUiSamples.locked()) }

  @Test fun bloccataTestoGrande() = shoot("bloccata", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.locked()) }

  @Test fun bloccataSbloccoNonRiuscito() = shoot("bloccata-sblocco-non-riuscito") { Screen(ReceiveUiSamples.locked(failed = true)) }

  @Test fun nonPronta() = shoot("non-pronta") { Screen(ReceiveUiSamples.notReady()) }

  @Test fun nienteDaSalvare() = shoot("niente-da-salvare") { Screen(ReceiveUiSamples.nothing()) }

  @Test fun nienteDaSalvareTestoGrande() = shoot("niente-da-salvare", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.nothing()) }

  @Test fun inCoda() = shoot("in-coda") { Screen(ReceiveUiSamples.done()) }

  @Test fun inCodaConIlNasCheNonRisponde() = shoot("in-coda-nas-non-risponde") { Screen(ReceiveUiSamples.done(offline = true)) }

  @Test fun inCodaConIlNasCheNonRispondeTestoGrande() = shoot("in-coda-nas-non-risponde", fontScale = RECEIVE_LARGE_TEXT) { Screen(ReceiveUiSamples.done(offline = true)) }

  @Test fun errore() = shoot("errore") { Screen(ReceiveUiSamples.failed()) }

  companion object {
    const val DIR = "../docs/screenshots/5-P11"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_SHOT_PHONE)
class ReceivePhoneScreenshotTest(dark: Boolean) : ReceiveScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_TABLET)
class ReceiveTabletScreenshotTest(dark: Boolean) : ReceiveScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

/**
 * Fase 5B, P11 (D30, §1.5): la schermata e' accessibile e non ha controlli impilati o sovrapposti, anche con il testo a 1,3x. Ogni controllo ha un nome
 * per TalkBack e un bersaglio da 48 dp; nessun controllo esce dallo schermo; il bottone "Salva sul NAS" si vede sempre; l'avviso dell'area comune non
 * copre niente. Una prova per schermata: `setContent` si chiama una volta per prova.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_PHONE)
class ReceiveLayoutTest : ReceiveComposeBase() {

  private fun check(ui: ReceiveUi, fontScale: Float?) {
    show(fontScale = fontScale) { ReceiveScreen(ui = ui, actions = ReceiveActions()) }
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val nodes = clickable()
    assertThat(nodes).isNotEmpty()
    val minTarget = 48f * 2.0f // 48 dp a xhdpi (densita' 2)
    for (node in nodes) {
      val b = node.boundsInRoot
      assertWithMessage("controllo senza nome").that(node.name()).isNotEmpty()
      // Il bersaglio e' la misura del controllo, non la parte che si vede: in una lista che scorre un controllo puo' stare a meta' fuori dallo schermo.
      assertWithMessage("${node.name()}: bersaglio sotto i 48 dp").that(node.size.width.toFloat()).isAtLeast(minTarget - 1)
      assertWithMessage("${node.name()}: bersaglio sotto i 48 dp").that(node.size.height.toFloat()).isAtLeast(minTarget - 1)
      val scrolledAway = b.height < node.size.height - 1 || b.width < node.size.width - 1
      if (!scrolledAway) {
        assertWithMessage("${node.name()}: fuori dallo schermo").that(Rect(root.left - 1, root.top - 1, root.right + 1, root.bottom + 1).contains(b.center)).isTrue()
      }
    }
    // I controlli cliccabili non si sovrappongono fra loro (ne' impilati: nessun bottone ne copre un altro).
    val boxes = nodes.map { it.name() to it.boundsInRoot }
    for (i in boxes.indices) for (j in i + 1 until boxes.size) {
      val overlap = boxes[i].second.intersect(boxes[j].second)
      assertWithMessage("${boxes[i].first} e ${boxes[j].first} si sovrappongono").that(overlap.width > 1f && overlap.height > 1f).isFalse()
    }
  }

  @Test fun prontaTestoNormale() = check(ReceiveUiSamples.ready(), null)

  @Test fun prontaTestoGrande() = check(ReceiveUiSamples.ready(), RECEIVE_LARGE_TEXT)

  @Test fun prontaAreaComuneConRifiutiTestoGrande() = check(ReceiveUiSamples.ready(common = true, rejected = ReceiveUiSamples.manyRejected), RECEIVE_LARGE_TEXT)

  @Test fun prontaInSottocartellaTestoGrande() = check(ReceiveUiSamples.ready(deep = true), RECEIVE_LARGE_TEXT)

  @Test fun bloccataTestoGrande() = check(ReceiveUiSamples.locked(), RECEIVE_LARGE_TEXT)

  @Test fun nonProntaTestoGrande() = check(ReceiveUiSamples.notReady(), RECEIVE_LARGE_TEXT)

  @Test fun nienteDaSalvareTestoGrande() = check(ReceiveUiSamples.nothing(), RECEIVE_LARGE_TEXT)

  @Test fun inCodaTestoGrande() = check(ReceiveUiSamples.done(offline = true), RECEIVE_LARGE_TEXT)

  @Test fun erroreTestoGrande() = check(ReceiveUiSamples.failed(), RECEIVE_LARGE_TEXT)

  @Test
  fun ilBottoneSalvaSiVedeSempreEL_AvvisoDellAreaComuneCE() {
    show(fontScale = RECEIVE_LARGE_TEXT) { ReceiveScreen(ui = ReceiveUiSamples.ready(common = true, rejected = ReceiveUiSamples.manyRejected), actions = ReceiveActions()) }
    val save = clickable().first { it.name() == "Salva sul NAS" }
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    assertThat(save.boundsInRoot.bottom).isAtMost(root.bottom + 1)
    assertThat(save.boundsInRoot.top).isAtLeast(root.top)
    composeRule.onNodeWithText("Lo vedranno tutti i familiari.").assertExists()
  }
}
