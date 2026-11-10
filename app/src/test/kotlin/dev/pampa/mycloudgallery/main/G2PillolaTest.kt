package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.TakeGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.SHOT_PHONE
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
@Config(application = Application::class, qualifiers = SHOT_PHONE)
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
    compose.waitUntil(Attese.TETTO_MS) { tab == GalleryTab.Albums }
    assertThat(events).contains("chiudi-cerca")
    assertThat(searchOpen).isFalse()
  }

  /**
   * GS-2 (K3a): la scheda sotto la ricerca ha sopra di se' la lente della pillola, che prende il tocco e cambia scheda solo dopo un trascinamento.
   * Il tocco vero (non l'azione di TalkBack) chiude la ricerca e torna li'. Prima di K3a questa prova era rossa: `searchOpen` restava vero.
   */
  @Test
  fun conCercaApertaIlToccoSullaSchedaSottoLaLenteChiudeLaRicerca() {
    searchOpen = true
    show()
    compose.onNodeWithText("Libreria").performClick()
    compose.waitForIdle()
    assertThat(searchOpen).isFalse()
    assertThat(tab).isEqualTo(GalleryTab.Library)
    assertThat(events).containsExactly("chiudi-cerca", "scheda:library").inOrder()
    compose.onNodeWithText("Ricerca di prova").assertDoesNotExist()
  }

  /** Lo stesso dalla scheda Raccolte, e con un tocco che si muove di un pixel: la lente lo rilascia sulla scheda, ma la ricerca si chiude una volta sola. */
  @Test
  fun unToccoCheSiMuoveDiPocoChiudeLaRicercaUnaVoltaSola() {
    tab = GalleryTab.Collections
    searchOpen = true
    show()
    compose.onNodeWithText("Raccolte").performTouchInput {
      down(center)
      moveBy(Offset(2f, 0f))
      up()
    }
    compose.waitForIdle()
    assertThat(searchOpen).isFalse()
    assertThat(tab).isEqualTo(GalleryTab.Collections)
    assertThat(events).containsExactly("chiudi-cerca", "scheda:collections").inOrder()
    compose.onNodeWithText("Raccolte di prova").assertExists()
  }

  /** L'azione di clic di TalkBack sulla scheda sotto la ricerca fa lo stesso (non passa dalla lente). */
  @Test
  fun conCercaApertaTalkBackSullaSchedaSottoLaRicercaLaChiude() {
    searchOpen = true
    show()
    compose.onNodeWithText("Libreria").performSemanticsAction(SemanticsActions.OnClick)
    compose.waitForIdle()
    assertThat(searchOpen).isFalse()
    assertThat(tab).isEqualTo(GalleryTab.Library)
  }

  /**
   * Senza Cerca, il tocco fermo sulla scheda scelta (sotto la lente) e' la riselezione ("torna in cima"; per la Libreria, in fondo). Prima lo
   * prendeva la lente e non succedeva niente; una sola chiamata anche con il dito che si muove di un pixel.
   */
  @Test
  fun senzaCercaIlToccoSullaSchedaSceltaLaRiseleziona() {
    compose.setContent {
      MyCloudGalleryTheme {
        Box(Modifier.fillMaxSize()) {
          GalleryTabBar(
            selected = GalleryTab.Albums,
            onSelect = { events += "scheda:${it.route}" },
            onReselect = { events += "di-nuovo:${it.route}" },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText("Album").performClick()
    compose.waitForIdle()
    assertThat(events).containsExactly("di-nuovo:albums")
    compose.onNodeWithText("Album").performTouchInput {
      down(center)
      moveBy(Offset(2f, 0f))
      up()
    }
    compose.waitForIdle()
    assertThat(events).containsExactly("di-nuovo:albums", "di-nuovo:albums")
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
