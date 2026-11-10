package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopy
import dev.pampa.mycloudgallery.core.library.testing.FakeSimilarCopies
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSidePanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberSimilarCopiesPanel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La sezione "N copie" montata come la monta il pannello (Fase 5A, P15; §2.12): il punto d'ingresso di P7 (`rememberSimilarCopiesPanel`),
 * il tocco su una copia, "Mostra questa come principale", "Separa" con la sua conferma leggera, e gli screenshot in
 * `docs/screenshots/5-P15/` (chiaro e scuro, telefono e tablet, testo a 1,3x).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class SimilarCopiesPanelTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val phone = MediaId.random()
  private val cloud = MediaId.random()
  private val old = MediaId.random()
  private val viewed = ArrayList<MediaId>()

  private fun copy(id: MediaId, places: Set<CopyPlace>, w: Int, h: Int, bytes: Long, mime: String, original: Boolean = false) =
    SimilarCopy(id, MediaKind.PHOTO, places, w, h, bytes, mime, null, original)

  private fun fake(hidden: Boolean = false, cover: MediaId = phone): FakeSimilarCopies = FakeSimilarCopies().also {
    it.put(
      SimilarCopiesGroup(
        MediaGroupId.random(), cover,
        listOf(
          copy(phone, setOf(CopyPlace.PHONE, CopyPlace.NAS_PRIVATE), 4000, 3000, 3_400_000, "image/jpeg", original = true),
          copy(cloud, setOf(CopyPlace.NAS_PRIVATE), 2000, 1500, 600_000, "image/jpeg"),
          copy(old, setOf(CopyPlace.NAS_COMMON), 4000, 3000, 2_100_000, "image/heic"),
        ),
        coverChosenByUser = false, hidden = hidden,
      ),
    )
  }

  @Composable
  private fun Host(source: FakeSimilarCopies?, current: MediaId, side: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
      val panel = rememberSimilarCopiesPanel(source, current, active = true, onView = { viewed += it })
      if (side) {
        InfoSidePanel(progress = { 1f }, width = 360.dp, title = "Informazioni", onClose = {}) { InfoSections(panel.sections) }
      } else {
        InfoSheet(progress = { 1f }, height = 560.dp, title = "Informazioni", onClose = {}, onHeaderDrag = {}, onHeaderDragEnd = {}) {
          InfoSections(panel.sections)
        }
      }
      panel.confirmSheet()
    }
  }

  private fun show(source: FakeSimilarCopies?, current: MediaId = cloud, dark: Boolean = false, side: Boolean = false) {
    composeRule.setContent { ViewerTestHost(dark = dark) { Host(source, current, side) } }
    composeRule.waitForIdle()
  }

  private fun shot(name: String, dark: Boolean, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/5-P15/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  @Test
  fun laSezioneMostraLeCopieEUnTocco_LaApre() {
    show(fake())
    composeRule.onNodeWithText("3 copie", ignoreCase = true).assertExists()
    composeRule.onNodeWithText("Principale").assertExists()
    composeRule.onNodeWithText("Mostra questa come principale").assertExists()
    composeRule.onAllNodesWithText("Separa").fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }
    // Tocco sulla copia del telefono.
    composeRule.onAllNodesWithText("Telefono e NAS, area privata", substring = true)[0].performClick()
    assertThat(viewed).containsExactly(phone)
  }

  @Test
  fun senzaIlRilevatoreLaSezioneNonCe() {
    show(null)
    composeRule.onNodeWithText("Informazioni").assertExists()
    assertThat(composeRule.onAllNodesWithText("copie", substring = true, ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unMediaSenzaCopieNonHaLaSezione() {
    show(fake(), current = MediaId.random())
    composeRule.onNodeWithText("Informazioni").assertExists()
    assertThat(composeRule.onAllNodesWithText("copie", substring = true, ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun mostraQuestaComePrincipaleLaApplicaELaRigaSparisce() {
    val source = fake()
    show(source)
    composeRule.onNodeWithText("Mostra questa come principale").performClick()
    composeRule.waitForIdle()
    assertThat(source.chosenCovers).containsExactly(cloud)
    // Ora la copia in vista e' la principale: l'azione non serve piu'.
    assertThat(composeRule.onAllNodesWithText("Mostra questa come principale").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun separaChiedeUnaConfermaLeggeraEPoiSepara() {
    val source = fake()
    show(source)
    composeRule.onAllNodesWithText("Separa").fetchSemanticsNodes().let { assertThat(it).hasSize(1) }
    composeRule.onNodeWithText("Separa").performClick()
    composeRule.waitForIdle()
    // Il foglio c'e' e non ha ancora separato.
    composeRule.onNodeWithText("Separare questa copia?").assertExists()
    assertThat(source.separated).isEmpty()
    // Annulla: niente.
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(source.separated).isEmpty()
    assertThat(composeRule.onAllNodesWithText("Separare questa copia?").fetchSemanticsNodes()).isEmpty()
    // Di nuovo, e questa volta si conferma.
    composeRule.onNodeWithText("Separa").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Separa").fetchSemanticsNodes().let { assertThat(it).hasSize(2) } // la riga e il bottone del foglio
    composeRule.onAllNodesWithText("Separa")[1].performClick()
    composeRule.waitForIdle()
    assertThat(source.separated).containsExactly(cloud)
    // La copia non ha piu' compagne: la sezione sparisce.
    assertThat(composeRule.onAllNodesWithText("copie", substring = true, ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilGruppoNascostoLoDiceNelPannello() {
    show(fake(hidden = true))
    composeRule.onNodeWithText("Nascoste insieme").assertExists()
  }

  // --- Screenshot ---------------------------------------------------------------------------------------------------------------------

  private fun copies(dark: Boolean, device: String = "telefono", side: Boolean = false, name: String = "copie", source: FakeSimilarCopies = fake()) {
    show(source, dark = dark, side = side)
    composeRule.onNodeWithText("3 copie", ignoreCase = true).assertExists()
    shot(name, dark = dark, device = device)
  }

  @Test
  fun screenshotTelefonoChiaro() = copies(dark = false)

  @Test
  fun screenshotTelefonoScuro() = copies(dark = true)

  @Test
  fun screenshotNascoste() = copies(dark = true, name = "copie-nascoste", source = fake(hidden = true, cover = cloud))

  @Test
  fun screenshotTestoGrandeChiaro() {
    composeRule.activity.resources.configuration.fontScale = 1.3f
    copies(dark = false, name = "copie-testo-grande")
  }

  @Test
  fun screenshotTestoGrandeScuro() {
    composeRule.activity.resources.configuration.fontScale = 1.3f
    copies(dark = true, name = "copie-testo-grande")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun screenshotTabletChiaro() = copies(dark = false, device = "tablet", side = true)

  @Config(qualifiers = TABLET)
  @Test
  fun screenshotTabletScuro() = copies(dark = true, device = "tablet", side = true)

  @Test
  fun screenshotConfermaDiSeparaChiaro() = separateSheet(dark = false)

  @Test
  fun screenshotConfermaDiSeparaScuro() = separateSheet(dark = true)

  private fun separateSheet(dark: Boolean) {
    show(fake(), dark = dark)
    composeRule.onNodeWithText("Separa").performClick()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.onNodeWithText("Separare questa copia?").assertExists()
    shot("copie-separa", dark = dark)
  }
}
