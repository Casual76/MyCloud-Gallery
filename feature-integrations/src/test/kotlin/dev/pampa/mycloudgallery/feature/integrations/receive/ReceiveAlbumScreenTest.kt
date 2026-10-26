package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Gli stati della condivisione in arrivo con "Aggiungi ad album" (Fase 7, P10, D35): la voce, la scelta dell'album, "In coda" con l'album. */
object ReceiveAlbumSamples {
  val mine = IncomingAlbumChoice("a1", "Compleanno di Anna", shared = false)
  val family = IncomingAlbumChoice("a2", "Natale in famiglia", shared = true)
  val trip = IncomingAlbumChoice("a3", "Gita al lago", shared = false)
  private val albums = listOf(mine, family, trip)

  fun ready(): ReceiveUi = ReceiveUiSamples.ready().copy(albums = albums)

  fun picking(selected: IncomingAlbumChoice = mine): ReceiveUi = ready().copy(albumPick = true, selectedAlbum = selected)

  fun done(album: IncomingAlbumChoice = family): ReceiveUi = ReceiveUiSamples.done().copy(addedToAlbum = album)

  fun doneNotRegistered(): ReceiveUi = ReceiveUiSamples.done().copy(albumNotRegistered = true)
}

/**
 * Gli screenshot di "Aggiungi ad album" dalla condivisione in arrivo: chiaro e scuro, telefono e tablet, piu' il testo grande. I PNG vanno in
 * `docs/screenshots/7-P10/` (`gradlew :feature-integrations:recordRoborazziDebug --tests "*ReceiveAlbumScreenshot*"`).
 */
abstract class ReceiveAlbumScreenshotBase(private val dark: Boolean, private val device: String) : ReceiveComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: String, fontScale: Float? = null, ui: ReceiveUi) {
    show(dark = dark, fontScale = fontScale) { ReceiveScreen(ui = ui, actions = ReceiveActions()) }
    assertWithMessage("manca «$expect» nella scena $name").that(composeRule.onAllNodesWithText(expect, substring = true).fetchSemanticsNodes()).isNotEmpty()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    // Il telefono ha tutte le scene; il tablet solo la scelta dell'album (le immagini sono grandi); il testo grande solo sul telefono.
    val capture = device == "telefono" || (fontScale == null && name == "scelta-album")
    if (capture) composeRule.onRoot().captureRoboImage("../docs/screenshots/7-P10/ricezione-$name-$look-$device$suffix.png")
  }

  @Test fun prontaConLaVoceAggiungiAdAlbum() = shoot("pronta", "Aggiungi ad album", ui = ReceiveAlbumSamples.ready())

  @Test fun prontaConLaVoceAggiungiAdAlbumTestoGrande() = shoot("pronta", "Aggiungi ad album", RECEIVE_LARGE_TEXT, ReceiveAlbumSamples.ready())

  @Test fun sceltaDellAlbum() = shoot("scelta-album", "In quale album", ui = ReceiveAlbumSamples.picking())

  @Test fun sceltaDellAlbumTestoGrande() = shoot("scelta-album", "In quale album", RECEIVE_LARGE_TEXT, ReceiveAlbumSamples.picking(ReceiveAlbumSamples.family))

  @Test fun inCodaConLAlbum() = shoot("in-coda-album", "Natale in famiglia", ui = ReceiveAlbumSamples.done())

  @Test fun inCodaMaLaPromessaNonSiEScritta() = shoot("in-coda-album-non-segnato", "Non sono riuscito", ui = ReceiveAlbumSamples.doneNotRegistered())

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_PHONE)
class ReceiveAlbumPhoneScreenshotTest(dark: Boolean) : ReceiveAlbumScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_TABLET)
class ReceiveAlbumTabletScreenshotTest(dark: Boolean) : ReceiveAlbumScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

/** L'accessibilita' di "Aggiungi ad album" in arrivo (come `ReceiveLayoutTest`): nomi, bersagli da 48 dp, niente sovrapposizioni a 1,3x, i due bottoni si vedono. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_PHONE)
class ReceiveAlbumLayoutTest : ReceiveComposeBase() {

  private fun check(ui: ReceiveUi, fontScale: Float?) {
    show(fontScale = fontScale) { ReceiveScreen(ui = ui, actions = ReceiveActions()) }
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val nodes = clickable()
    assertThat(nodes).isNotEmpty()
    val minTarget = 48f * 2.0f
    for (node in nodes) {
      assertWithMessage("controllo senza nome").that(node.name()).isNotEmpty()
      assertWithMessage("${node.name()}: bersaglio sotto i 48 dp").that(node.size.width.toFloat()).isAtLeast(minTarget - 1)
      assertWithMessage("${node.name()}: bersaglio sotto i 48 dp").that(node.size.height.toFloat()).isAtLeast(minTarget - 1)
      val b = node.boundsInRoot
      val scrolledAway = b.height < node.size.height - 1 || b.width < node.size.width - 1
      if (!scrolledAway) {
        assertWithMessage("${node.name()}: fuori dallo schermo").that(Rect(root.left - 1, root.top - 1, root.right + 1, root.bottom + 1).contains(b.center)).isTrue()
      }
    }
    val boxes = nodes.map { it.name() to it.boundsInRoot }
    for (i in boxes.indices) for (j in i + 1 until boxes.size) {
      val overlap = boxes[i].second.intersect(boxes[j].second)
      assertWithMessage("${boxes[i].first} e ${boxes[j].first} si sovrappongono").that(overlap.width > 1f && overlap.height > 1f).isFalse()
    }
  }

  @Test fun prontaTestoGrande() = check(ReceiveAlbumSamples.ready(), RECEIVE_LARGE_TEXT)

  @Test fun sceltaTestoGrande() = check(ReceiveAlbumSamples.picking(), RECEIVE_LARGE_TEXT)

  @Test fun inCodaTestoGrande() = check(ReceiveAlbumSamples.done(), RECEIVE_LARGE_TEXT)

  @Test
  fun senzaAlbumLaVoceAggiungiAdAlbumNonCe() {
    show { ReceiveScreen(ui = ReceiveUiSamples.ready(), actions = ReceiveActions()) }
    assertThat(composeRule.onAllNodesWithText("Aggiungi ad album", substring = true).fetchSemanticsNodes()).isEmpty()
    composeRule.onNode(hasText("Salva sul NAS") and hasClickAction()).assertExists()
  }

  @Test
  fun conGliAlbumLaVoceStaInsiemeASalvaSulNasEIlTocco_ApreLaScelta() {
    var opened = 0
    show { ReceiveScreen(ui = ReceiveAlbumSamples.ready(), actions = ReceiveActions(onPickAlbum = { opened++ })) }
    composeRule.onNode(hasText("Salva sul NAS") and hasClickAction()).assertExists()
    composeRule.onNodeWithText("Aggiungi ad album…").performClick()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun nellaSceltaUnTocco_ScegliEUnAltro_Salva_EIlBottoneDellaCartellaNonCePiu() {
    var picked: IncomingAlbumChoice? = null
    var saved = 0
    show { ReceiveScreen(ui = ReceiveAlbumSamples.picking(), actions = ReceiveActions(onSelectAlbum = { picked = it }, onSaveToAlbum = { saved++ })) }
    composeRule.onNodeWithText("Natale in famiglia", substring = true).performClick()
    assertThat(picked).isEqualTo(ReceiveAlbumSamples.family)
    assertThat(saved).isEqualTo(0)
    composeRule.onNodeWithText("Salva e aggiungi all’album").performClick()
    assertThat(saved).isEqualTo(1)
    assertThat(composeRule.onAllNodes(hasText("Salva sul NAS") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
  }
}
