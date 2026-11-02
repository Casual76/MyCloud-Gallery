package dev.pampa.mycloudgallery.feature.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvideGalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P5, D13 (4), E-T2): le Impostazioni a elenco e pagina secondo la finestra comune, alle larghezze del riquadro (360, 600, 840 e 1280 dp): l'elenco e poi la pagina sotto 840 dp, i due pannelli sopra,
 * senza sovrapposizioni; la pagina scelta resta passando la soglia e tornando; ogni pagina del pannello ha la sua rotta nella navigazione.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h915dp-xhdpi")
class SettingsPanesTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val navigated = mutableListOf<SettingsPage>()
  private var widthDp by mutableStateOf(1280)

  /** Un elenco finto con due voci: sceglie nel pannello da estesa, naviga sotto (come `settingsGraph`). La larghezza della finestra la cambia il test. */
  private fun start(initialWidthDp: Int) {
    widthDp = initialWidthDp
    rule.setContent {
      MyCloudGalleryTheme {
        ProvideGalleryWindow(GalleryWindow.of(widthDp, 900)) {
          Box(Modifier.requiredSize(widthDp.dp, 900.dp)) {
            SettingsPanes(
              list = { twoPane, choose ->
                Column {
                  Text("Dispositivi", Modifier.clickable { if (twoPane) choose(SettingsPage.DEVICES) else navigated += SettingsPage.DEVICES })
                  Text("Famiglia", Modifier.clickable { if (twoPane) choose(SettingsPage.FAMILY) else navigated += SettingsPage.FAMILY })
                }
              },
              detail = { page, _ -> Text("pagina ${page?.code}") },
            )
          }
        }
      }
    }
    rule.waitForIdle()
  }

  private fun resizeTo(dp: Int) {
    widthDp = dp
    rule.waitForIdle()
  }

  @Test
  fun sottoLaSoglia_soloLElenco_eUnaVoceApreLaPagina() {
    start(360)
    for (dp in listOf(360, 600, 839)) {
      resizeTo(dp)
      rule.onAllNodesWithText("pagina", substring = true).assertCountEquals(0)
    }
    rule.onNodeWithText("Famiglia").performClick()
    rule.waitForIdle()
    assertThat(navigated).containsExactly(SettingsPage.FAMILY)
  }

  @Test
  fun daEstesa_elencoEPaginaAffiancati_senzaSovrapporsi() {
    start(840)
    for (dp in listOf(840, 1280)) {
      resizeTo(dp)
      rule.onNodeWithText("pagina null").assertExists()
      val list = rule.onNodeWithText("Famiglia").getUnclippedBoundsInRoot()
      val page = rule.onNodeWithText("pagina null").getUnclippedBoundsInRoot()
      assertThat(list.right).isAtMost(page.left)
    }
    rule.onNodeWithText("Famiglia").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("pagina family").assertExists()
    // Scegliere non porta da nessuna parte: niente navigazione a pagina intera.
    assertThat(navigated).isEmpty()
  }

  @Test
  fun laPaginaSceltaRestaPassandoLaSogliaETornando() {
    var widthDp by mutableStateOf(1280)
    rule.setContent {
      MyCloudGalleryTheme {
        ProvideGalleryWindow(GalleryWindow.of(widthDp, 900)) {
          Box(Modifier.requiredSize(widthDp.dp, 900.dp)) {
            SettingsPanes(
              list = { twoPane, choose -> Text("Dispositivi", Modifier.clickable { if (twoPane) choose(SettingsPage.DEVICES) }) },
              detail = { page, _ -> Text("pagina ${page?.code}") },
            )
          }
        }
      }
    }
    rule.onNodeWithText("Dispositivi").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("pagina devices").assertExists()
    for (dp in listOf(700, 1280, 360, 900)) {
      widthDp = dp
      rule.waitForIdle()
      if (dp >= 840) rule.onNodeWithText("pagina devices").assertExists() else rule.onAllNodesWithText("pagina devices").assertCountEquals(0)
    }
  }

  @Test
  fun ogniPaginaDelPannelloHaLaSuaRotta_eILoCodiceSiRilegge() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
    val controller = NavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
    controller.graph = controller.createGraph(startDestination = SettingsPaneEmptyRoute) {
      composable<SettingsPaneEmptyRoute> {}
      settingsPages(nav = controller, back = {}, resumeStep = {})
    }
    for (page in SettingsPage.entries) {
      assertWithMessage("la pagina ${page.code} non ha una rotta nel pannello").that(controller.graph.findNode(page.route)).isNotNull()
      assertThat(SettingsPage.fromCode(page.code)).isEqualTo(page)
    }
    assertThat(SettingsPage.fromCode("sconosciuta")).isNull()
    assertThat(SettingsPage.fromCode(null)).isNull()
    // I codici sono tutti diversi (si salvano).
    assertThat(SettingsPage.entries.map { it.code }.toSet()).hasSize(SettingsPage.entries.size)
  }

  /**
   * FI-1: l'"Indietro" del pannello. Dalla **prima** pagina non toglie niente: chiama `close` (chi sceglie la riporta a "Scegli una voce") e la pagina resta dov'e' (altrimenti il pannello
   * resterebbe vuoto, o `close` non partirebbe e la stessa voce non si potrebbe riscegliere); da una **seconda** torna alla prima e non chiude.
   */
  @Test
  fun indietroNelPannello_laPrimaPaginaSiChiudeSenzaTogliersi_laSecondaTornaAllaPrima() {
    var closed = 0
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsPaneHost(
          page = SettingsPage.DEVICES,
          close = { closed++ },
          resumeStep = {},
          pages = { nav, back ->
            composable<SettingsDevicesRoute> {
              Column {
                Text("prima pagina")
                Text("Avanti", Modifier.clickable { nav.navigate(SettingsFamilyRoute) })
                Text("Indietro dalla prima", Modifier.clickable { back() })
              }
            }
            composable<SettingsFamilyRoute> {
              Column {
                Text("seconda pagina")
                Text("Indietro dalla seconda", Modifier.clickable { back() })
              }
            }
          },
        )
      }
    }
    rule.waitForIdle()
    rule.onNodeWithText("prima pagina").assertExists()

    rule.onNodeWithText("Avanti").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("seconda pagina").assertExists()
    rule.onNodeWithText("Indietro dalla seconda").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("prima pagina").assertExists()
    assertThat(closed).isEqualTo(0)

    rule.onNodeWithText("Indietro dalla prima").performClick()
    rule.waitForIdle()
    assertThat(closed).isEqualTo(1)
    rule.onNodeWithText("prima pagina").assertExists()
    // Un secondo "Indietro" chiude ancora: la pagina non e' mai stata tolta.
    rule.onNodeWithText("Indietro dalla prima").performClick()
    rule.waitForIdle()
    assertThat(closed).isEqualTo(2)
  }

  /**
   * FI-1, la logica sola: dalla prima pagina `paneBack` chiude e non chiama mai `popBackStack`, **anche se la libreria rispondesse vero** togliendo l'ultima destinazione (con Navigation 2.9 risponde
   * falso e la prova qui sopra passava anche col codice di prima: la correzione non dipende piu' da come la libreria tratta l'ultima destinazione). Da una seconda pagina toglie e non chiude.
   */
  @Test
  fun paneBack_DallaPrimaPaginaChiudeESenzaToglierla_AncheSeLaLibreriaRispondeVero() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
    var popped = 0
    val onlyOne = object : NavController(context) {
      override val previousBackStackEntry: NavBackStackEntry? get() = null

      override fun popBackStack(): Boolean {
        popped++
        return true
      }
    }
    var closed = 0
    paneBack(onlyOne) { closed++ }
    assertThat(closed).isEqualTo(1)
    assertThat(popped).isEqualTo(0)
  }

  private fun assertWithMessage(message: String) = com.google.common.truth.Truth.assertWithMessage(message)
}
