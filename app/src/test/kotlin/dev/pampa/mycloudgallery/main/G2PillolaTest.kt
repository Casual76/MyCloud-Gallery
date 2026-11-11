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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.FixedSearchSource
import dev.pampa.mycloudgallery.feature.search.NoSearchHandlers
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.noop.NoSearchActions
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
 * G2 (fase grafica, come in Foto di Apple): la pillola **Libreria · Album · Raccolte**, Cerca nel cerchio accanto alla pillola, e la barra delle azioni
 * della selezione al posto della pillola (B3). H4 (11/11): il cerchio diventa la barra di ricerca, la pillola esce, dietro c'e' la Libreria anche da
 * un'altra scheda, Indietro chiude in un passo e riporta la pillola e la scheda di partenza; in selezione la barra di ricerca lascia il posto.
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
          search = ScaffoldSearch(
            source = FixedSearchSource(suggestions = listOf(Suggestion.Term("Ferragosto"))),
            handlers = NoSearchHandlers,
            thumbnails = SearchThumbnails(state.grid.loader, state.grid.placeholders, SearchRefs { _, _ -> null }),
            actions = NoSearchActions,
          ),
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

  private companion object {
    /** Il segnaposto del campo di ricerca. */
    const val FIELD_HINT = "Foto, luoghi, date, parole…"

    /** La X della barra di ricerca. */
    const val CLOSE = "Chiudi la ricerca"

    /** Il menu della vista della Libreria, nella capsula dei comandi in alto. */
    const val VIEW_MENU = "Vista"
  }

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

  /** H4: il cerchio apre la barra di ricerca (il campo con la X), la pillola esce; Indietro chiude e la pillola torna. */
  @Test
  fun ilCerchioDiventaLaBarraDiRicercaEIndietroRiportaLaPillola() {
    show()
    compose.onNodeWithContentDescription("Cerca").performClick()
    compose.waitForIdle()
    assertThat(events).containsExactly("apri-cerca")
    compose.onNodeWithText(FIELD_HINT).assertExists()
    compose.onNodeWithContentDescription(CLOSE).assertExists()
    // La pillola non c'e' piu': ne' le schede ne' il cerchio.
    compose.onAllNodesWithText("Album").assertCountEquals(0)
    assertThat(circleCount()).isEqualTo(0)
    // In alto, al posto dei pezzi della Libreria, la barra dei suggerimenti.
    compose.onNodeWithText("Ferragosto").assertExists()
    compose.onAllNodesWithContentDescription(VIEW_MENU).assertCountEquals(0)

    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
    assertThat(events).containsExactly("apri-cerca", "chiudi-cerca").inOrder()
    compose.onNodeWithText(FIELD_HINT).assertDoesNotExist()
    compose.onNodeWithText("Album").assertExists()
    assertThat(circleCount()).isEqualTo(1)
    compose.onNodeWithContentDescription(VIEW_MENU).assertExists()
  }

  /** H4: la X della barra chiude la ricerca (con il campo vuoto: la crocetta dentro il campo svuota il testo, come in Foto di Apple). */
  @Test
  fun laXChiudeLaRicerca() {
    searchOpen = true
    show()
    compose.onNodeWithContentDescription(CLOSE).performClick()
    compose.waitForIdle()
    assertThat(events).containsExactly("chiudi-cerca")
    assertThat(circleCount()).isEqualTo(1)
  }

  /** H4: da un'altra scheda la ricerca mostra la Libreria dietro; chiudendo si torna alla scheda di partenza, e la pillola la segna ancora scelta. */
  @Test
  fun daUnAltraSchedaDietroCeLaLibreriaEChiudendoSiTornaLi() {
    tab = GalleryTab.Collections
    show()
    compose.onNodeWithText("Raccolte di prova").assertExists()
    compose.onNodeWithContentDescription("Cerca").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Raccolte di prova").assertDoesNotExist()
    compose.onNodeWithText("Ferragosto").assertExists()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
    assertThat(tab).isEqualTo(GalleryTab.Collections)
    compose.onNodeWithText("Raccolte di prova").assertExists()
  }

  /** H4: in selezione (anche sui risultati: e' la selezione della Libreria) la barra di ricerca lascia il posto alla barra delle azioni, e Indietro esce prima dalla selezione. */
  @Test
  fun conLaRicercaApertaLaSelezionePrendeIlPostoDellaBarraEIndietroLaChiudePrima() {
    val state = syntheticLibraryTabState()
    searchOpen = true
    show(state)
    compose.onNodeWithText(FIELD_HINT).assertExists()
    compose.runOnIdle { state.grid.startSelecting() }
    compose.waitForIdle()
    compose.onNodeWithText(FIELD_HINT).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
    assertThat(state.grid.selecting).isFalse()
    assertThat(searchOpen).isTrue()
    compose.onNodeWithText(FIELD_HINT).assertExists()
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
