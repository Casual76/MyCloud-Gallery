package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSectionModel
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPlaceMapPanel
import dev.pampa.mycloudgallery.feature.viewer.info.sectionsWithPlaceMap
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPlaceMap
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La riga "Mostra sulla mappa" e la mini-mappa delle informazioni (Fase 9, P4; docs/fase9-progetto.md §1.5 `MiniMapState`, §2.10, D29, D30, E-MAP16, E-MAP19, E-H9), montate come le monta il pannello
 * (`rememberPlaceMapPanel`) con la mappa finta del viewer. **La riga c'e' solo con una posizione**; la mini-mappa porta il credito della carta; il tocco apre la mappa vera; **mai per un media aperto dai
 * Nascosti: la porta non viene nemmeno chiamata** (la prova della riga, D31, riga `MAP`, superficie `viewer-minimap`); bersagli da 48 dp e testo a 1,3x.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE)
class ViewerPlaceMapInfoTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val photo = MediaId.random()

  @Composable
  private fun Host(source: ViewerPlaceMap?, active: Boolean, secure: Boolean, fontScale: Float?) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
      Box(Modifier.fillMaxSize()) {
        val panel = rememberPlaceMapPanel(source, photo, active, secure)
        InfoSheet(progress = { 1f }, height = 560.dp, title = "Informazioni", onClose = {}, onHeaderDrag = {}, onHeaderDragEnd = {}) { InfoSections(panel.sections) }
      }
    }
  }

  private fun show(source: ViewerPlaceMap?, active: Boolean = true, secure: Boolean = false, dark: Boolean = false, fontScale: Float? = null) {
    composeRule.setContent { ViewerTestHost(dark = dark) { Host(source, active, secure, fontScale) } }
    composeRule.waitForIdle()
  }

  private fun exists(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  private fun spoken(): List<String> =
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }

  private fun located(bitmap: Bitmap? = null) = FakeViewerPlaceMap().locate(photo, ViewerMapPlace.Located(bitmap))

  // --- La riga ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun conUnaPosizioneCeLaRigaMostraSullaMappa_EIlTocco_ApreLaMappaVera() {
    val fake = located()
    show(fake)
    assertThat(exists("MAPPA")).isTrue()
    assertThat(exists("Mostra sulla mappa")).isTrue()
    assertThat(exists("Nella mappa dell’app")).isTrue()
    composeRule.onNodeWithText("Mostra sulla mappa").performClick()
    composeRule.waitForIdle()
    assertThat(fake.shown).containsExactly(photo)
    // Senza la bitmap (la carta di base non e' ancora disponibile) resta la riga, senza immagine e senza credito.
    assertThat(exists(PlacesAttribution.MAP_BASE_SHORT)).isFalse()
  }

  @Test
  fun laMiniMappaPortaIlCreditoDellaCartaEilTocco() {
    val fake = located(Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888))
    show(fake)
    assertThat(exists(PlacesAttribution.MAP_BASE_SHORT)).isTrue()
    val image = "Mini-mappa del posto. Tocca per mostrarlo sulla mappa."
    assertThat(spoken()).contains(image)
    // Nessuna coordinata nella frase di TalkBack.
    assertThat(image).doesNotContainMatch("[0-9]")
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") == image }
      .let { node -> composeRule.runOnUiThread { node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() } }
    composeRule.waitForIdle()
    assertThat(fake.shown).containsExactly(photo)
  }

  @Test
  fun senzaPosizioneOMentreCarica_LaRigaNonCe() {
    show(FakeViewerPlaceMap())
    assertThat(exists("Mostra sulla mappa")).isFalse()
    assertThat(exists("MAPPA")).isFalse()
  }

  @Test
  fun mentreLaPortaLeggeLaPosizioneNonCeNiente() {
    show(FakeViewerPlaceMap().locate(photo, ViewerMapPlace.Loading))
    assertThat(exists("Mostra sulla mappa")).isFalse()
  }

  @Test
  fun senzaLaPortaOPannelloChiuso_LaRigaNonCe_ELaPortaNonSiChiama() {
    show(null)
    assertThat(exists("Mostra sulla mappa")).isFalse()
  }

  @Test
  fun aPannelloChiusoLaPortaNonSiChiamaNemmeno() {
    val fake = located()
    show(fake, active = false)
    assertThat(exists("Mostra sulla mappa")).isFalse()
    assertThat(fake.asked).isEmpty()
  }

  // --- Mai dai Nascosti (E-H9, D30) -------------------------------------------------------------------------------------------------

  @Test
  fun daUnMediaAdertoDaiNascostiNonCeLaRigaELaPortaNonSiChiama() {
    val fake = located(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888))
    show(fake, secure = true)
    assertThat(exists("Mostra sulla mappa")).isFalse()
    assertThat(exists("MAPPA")).isFalse()
    assertThat(fake.asked).isEmpty()
    assertThat(fake.shown).isEmpty()
  }

  // --- Accessibilita' -------------------------------------------------------------------------------------------------------------------

  private fun assertTouchTargets(vararg scenes: String) {
    val density = composeRule.density.density
    for (node in composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
      val b = node.touchBoundsInRoot
      val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "?"
      assertWithMessage("${scenes.joinToString()}: «$label» e' ${b.width / density}x${b.height / density} dp, meno di 48").that(minOf(b.width, b.height) / density).isAtLeast(47.5f)
    }
  }

  @Test
  fun iBersagliSonoDa48Dp() {
    show(located(Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)))
    assertTouchTargets("normale")
  }

  @Test
  fun iBersagliSonoDa48Dp_ATestoGrande() {
    show(located(Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)), fontScale = 1.3f)
    assertTouchTargets("1,3x")
    assertThat(exists("Mostra sulla mappa")).isTrue()
  }

  // --- Screenshot (docs/screenshots/9-P4/): il pannello con la mini-mappa. L'immagine e' una carta piatta finta con il segno sul punto: MapSnapshotter non si disegna in Robolectric -------------------

  private fun fakeMiniMap(): Bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(226, 222, 240))
    val sea = android.graphics.Paint().apply { color = android.graphics.Color.rgb(205, 196, 232) }
    canvas.drawRect(0f, 200f, 360f, 320f, sea)
    val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(153, 102, 204) }
    canvas.drawCircle(320f, 160f, 18f, ring)
    canvas.drawCircle(320f, 160f, 12f, dot)
  }

  @Test
  fun screenshotInfoConMiniMappaChiaro() {
    show(located(fakeMiniMap()))
    assertThat(exists("Mostra sulla mappa")).isTrue()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/9-P4/info-viewer-mini-mappa-chiaro-telefono.png")
  }

  @Test
  fun screenshotInfoConMiniMappaScuro() {
    show(located(fakeMiniMap()), dark = true)
    assertThat(exists("Mostra sulla mappa")).isTrue()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/9-P4/info-viewer-mini-mappa-scuro-telefono.png")
  }

  // --- L'ordine delle sezioni -----------------------------------------------------------------------------------------------------------

  private fun section(header: String?) = InfoSectionModel(header = header, rows = emptyList())

  @Test
  fun laSezioneDellaMappaVaSubitoDopoIlLuogo_OAltrimentiDopoLaData_OInTesta() {
    val map = listOf(section("Mappa"))
    assertThat(sectionsWithPlaceMap(listOf(section(null), section("Luogo"), section("File")), map, "Luogo").map { it.header }).containsExactly(null, "Luogo", "Mappa", "File").inOrder()
    assertThat(sectionsWithPlaceMap(listOf(section(null), section("File")), map, "Luogo").map { it.header }).containsExactly(null, "Mappa", "File").inOrder()
    assertThat(sectionsWithPlaceMap(listOf(section("File")), map, "Luogo").map { it.header }).containsExactly("Mappa", "File").inOrder()
    val base = listOf(section(null), section("Luogo"))
    assertThat(sectionsWithPlaceMap(base, emptyList(), "Luogo")).isSameInstanceAs(base)
  }
}
