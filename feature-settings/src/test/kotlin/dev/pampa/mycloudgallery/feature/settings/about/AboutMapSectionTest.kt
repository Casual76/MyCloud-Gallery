package dev.pampa.mycloudgallery.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La sezione "Mappa" di Informazioni e licenze (Fase 9, P2; docs/fase9-progetto.md §2.7): OpenStreetMap, OpenMapTiles e OpenFreeMap per la mappa online,
 * Natural Earth e GeoNames per la carta di base, stile, caratteri e icone. Testo da leggere, mai un collegamento (la pagina non apre niente).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class AboutMapSectionTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show() {
    rule.setContent { MyCloudGalleryTheme { AboutScreen(versionName = "1.0.0", onBack = {}) } }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  private fun scrollTo(text: String) {
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun mostraLeAttribuzioniDellaMappaOnlineEDellaCartaDiBase() {
    show()

    for (id in listOf(R.string.about_map_online_attribution, R.string.about_map_base_attribution, R.string.about_map_style_attribution)) {
      val text = string(id)
      scrollTo(text)
      rule.onNodeWithText(text).assertIsDisplayed().assertHasNoClickAction()
    }
    assertThat(string(R.string.about_map_online_attribution)).contains("© OpenStreetMap contributors")
    assertThat(string(R.string.about_map_base_attribution)).contains("Natural Earth")
    assertThat(string(R.string.about_map_base_attribution)).contains("GeoNames")
  }

  @Test
  fun scriveLaLicenzaOdblComeTestoSenzaTocco() {
    show()

    scrollTo(string(R.string.about_license_odbl))
    rule.onNodeWithText("Open Database License 1.0 (ODbL)").assertIsDisplayed()
    rule.onNodeWithText("https://opendatacommons.org/licenses/odbl/1-0/").assertIsDisplayed().assertHasNoClickAction()
  }

  @Test
  fun laSezioneDiceCheNienteDelleTueFotoVaAQuestiServizi() {
    show()

    scrollTo(string(R.string.about_map_note))
    rule.onNodeWithText(string(R.string.about_map_note)).assertIsDisplayed()
    assertThat(string(R.string.about_map_note)).contains("Nessuna foto")
  }
}
