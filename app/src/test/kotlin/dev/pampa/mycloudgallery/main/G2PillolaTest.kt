package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.TakeGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.status.GlobalStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * G2 (fase grafica, come in Foto di Apple): la pillola **Libreria · Album · Raccolte**, Cerca nel cerchio accanto alla pillola, Indietro che
 * chiude la ricerca e torna alla scheda di prima, e la barra delle azioni della selezione al posto della pillola (B3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class G2PillolaTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private var tab by mutableStateOf(GalleryTab.Library)
  private var searchOpen by mutableStateOf(false)
  private var paneSelecting by mutableStateOf(false)
  private val events = ArrayList<String>()

  private fun show(state: LibraryTabState = syntheticLibraryTabState()) {
    compose.setContent {
      MyCloudGalleryTheme {
        MainScaffold(
          status = GlobalStatus.HOME_CONNECTED,
          selectedTab = tab,
          onSelectTab = {
            events += "scheda:${it.route}"
            tab = it
          },
          onStatusClick = {},
          onOpenSettings = {},
          libraryState = state,
          // Una scheda con una griglia che, in selezione, prende il posto della pillola (come il pannello delle Raccolte sul tablet).
          collections = { _ ->
            Text("Raccolte di prova")
            TakeGalleryTabBarSlot(active = paneSelecting)
          },
          search = { _ -> Text("Ricerca di prova") },
          searchOpen = searchOpen,
          onOpenSearch = {
            events += "apri-cerca"
            searchOpen = true
          },
          onCloseSearch = {
            events += "chiudi-cerca"
            searchOpen = false
          },
        )
      }
    }
    compose.waitForIdle()
  }

  private fun circleCount() = compose.onAllNodesWithContentDescription("Cerca").fetchSemanticsNodes().size

  @Test
  fun laPillolaHaLibreriaAlbumRaccolteECercaENelCerchio() {
    show()
    compose.onNodeWithText("Libreria").assertExists()
    compose.onNodeWithText("Album").assertExists()
    compose.onNodeWithText("Raccolte").assertExists()
    // Cerca non e' piu' una scheda con l'etichetta: e' il cerchio, con il nome per TalkBack (e per i benchmark).
    compose.onAllNodesWithText("Cerca").assertCountEquals(0)
    assertThat(circleCount()).isEqualTo(1)
  }

  @Test
  fun ilCerchioApreLaRicercaEIndietroTornaAllaSchedaDiPrima() {
    tab = GalleryTab.Collections
    show()
    compose.onNodeWithContentDescription("Cerca").performClick()
    compose.waitForIdle()
    assertThat(events).containsExactly("apri-cerca")
    compose.onNodeWithText("Ricerca di prova").assertExists()
    // Il cerchio dice di essere quello scelto; la pillola resta sulla scheda di prima.
    compose.onNodeWithContentDescription("Cerca").assertIsSelected()

    compose.activity.onBackPressedDispatcher.onBackPressed()
    compose.waitForIdle()
    assertThat(events).containsExactly("apri-cerca", "chiudi-cerca").inOrder()
    assertThat(tab).isEqualTo(GalleryTab.Collections)
    compose.onNodeWithText("Raccolte di prova").assertExists()
  }

  @Test
  fun conCercaApertaUnaSchedaChiudeLaRicercaEPortaLi() {
    searchOpen = true
    show()
    compose.onNodeWithText("Album").performClick()
    // La pillola parte subito e la pagina aspetta che arrivi (`TabPuckLeadMillis`): si aspetta la scheda.
    compose.waitUntil(5_000) { tab == GalleryTab.Albums }
    assertThat(events).contains("chiudi-cerca")
    assertThat(searchOpen).isFalse()
  }

  /**
   * La scheda sotto la ricerca ha sopra di se' la lente della pillola, che nella prova riceve il tocco simulato: si usa l'azione di clic della
   * scheda (quella di TalkBack), che prova il collegamento. Il tocco vero sulla lente va provato sul telefono.
   */
  @Test
  fun conCercaApertaLaSchedaSottoLaRicercaLaChiude() {
    searchOpen = true
    show()
    compose.onNodeWithText("Libreria").performSemanticsAction(SemanticsActions.OnClick)
    compose.waitForIdle()
    assertThat(searchOpen).isFalse()
    assertThat(tab).isEqualTo(GalleryTab.Library)
  }

  /** B3: in selezione nella Libreria la pillola e il cerchio escono; la barra delle azioni prende il loro posto. */
  @Test
  fun inSelezioneLaPillolaEIlCerchioLascianoIlPostoAllaBarra() {
    val state = syntheticLibraryTabState()
    show(state)
    assertThat(circleCount()).isEqualTo(1)
    compose.runOnIdle { state.grid.startSelecting() }
    compose.waitForIdle()
    assertThat(circleCount()).isEqualTo(0)
    compose.onAllNodesWithText("Raccolte").assertCountEquals(0)
    compose.runOnIdle { state.grid.stopSelecting() }
    compose.waitForIdle()
    assertThat(circleCount()).isEqualTo(1)
  }

  /** B3: una griglia dentro una scheda (il pannello delle Raccolte sul tablet) prende il posto della pillola finche' mostra la sua barra. */
  @Test
  fun unaGrigliaDentroUnaSchedaPrendeIlPostoDellaPillola() {
    tab = GalleryTab.Collections
    show()
    assertThat(circleCount()).isEqualTo(1)
    paneSelecting = true
    compose.waitForIdle()
    assertThat(circleCount()).isEqualTo(0)
    paneSelecting = false
    compose.waitForIdle()
    assertThat(circleCount()).isEqualTo(1)
  }
}
