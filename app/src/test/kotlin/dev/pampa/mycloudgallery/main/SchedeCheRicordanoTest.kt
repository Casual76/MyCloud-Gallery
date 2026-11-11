package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.SHOT_TABLET
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
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
 * GF-7 (K3a): una scheda coperta (da Cerca o da un'altra scheda) ritrova cio' che ricordava. Prima usciva dalla composizione e ripartiva da capo:
 * aprire Cerca o passare alla Libreria e tornare riportava gli Album in cima. Con lo scaffold delle prove, cioe' la scheda Album vera su uno stato
 * dato, sul telefono dell'utente. Sul tablet, il pannello delle Raccolte ritrova la voce scelta dopo Cerca. La Libreria ha la sua prova:
 * `LibreriaAlRitornoTest` (K6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class SchedeCheRicordanoTest {

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

  private var tab by mutableStateOf(GalleryTab.Albums)
  private var searchOpen by mutableStateOf(false)

  private fun show(scenes: ScaffoldScenes, collectionsEntryDetail: (@Composable (CollectionEntry) -> Unit)? = null) {
    compose.setContent {
      MyCloudGalleryTheme {
        GalleryScaffold(
          scenes = scenes,
          tab = tab,
          searchOpen = searchOpen,
          onSelectTab = { tab = it },
          onOpenSearch = { searchOpen = true },
          onCloseSearch = { searchOpen = false },
          collectionsEntryDetail = collectionsEntryDetail,
        )
      }
    }
    compose.waitForIdle()
  }

  /** Un album in fondo alla scheda: per vederlo bisogna scorrere, e in cima non si vede. */
  private fun farAlbum(scenes: ScaffoldScenes): String = scenes.albums.entries.filterIsInstance<AlbumsTabEntry.Item>().last().album.title

  /** La scheda Album fatta scorrere fino a [title]: si vede quello, e non piu' "Preferiti", la prima voce. */
  private fun scrollTo(title: String) {
    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
    compose.waitForIdle()
    assertScrolledTo(title)
  }

  private fun assertScrolledTo(title: String) {
    compose.onNodeWithText(title).assertIsDisplayed()
    assertThat(compose.onAllNodesWithText(FIRST_ENTRY).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun gliAlbumRestanoDoveEranoDopoAverApertoEChiusoCerca() {
    val scenes = ScaffoldScenes(albumCount = MANY)
    show(scenes)
    compose.onNodeWithText(FIRST_ENTRY).assertIsDisplayed()
    val far = farAlbum(scenes)
    scrollTo(far)

    searchOpen = true
    compose.waitForIdle()
    compose.onNodeWithText(far).assertDoesNotExist()

    searchOpen = false
    compose.waitForIdle()
    assertScrolledTo(far)
  }

  @Test
  fun gliAlbumRestanoDoveEranoPassandoAllaLibreriaETornando() {
    val scenes = ScaffoldScenes(albumCount = MANY)
    show(scenes)
    val far = farAlbum(scenes)
    scrollTo(far)

    tab = GalleryTab.Library
    compose.waitForIdle()
    compose.onNodeWithText(far).assertDoesNotExist()

    tab = GalleryTab.Collections
    compose.waitForIdle()
    tab = GalleryTab.Albums
    compose.waitForIdle()
    assertScrolledTo(far)
  }

  /**
   * Sul tablet il pannello delle Raccolte ritrova la sua scelta dopo Cerca (K3a: `savedEntry` di `CollectionsScreen` nel `SaveableStateHolder`; la
   * verifica mancante segnalata con GS-10). Il pannello mostra la pagina di una voce come in produzione (`entryDetail`): qui una riga col suo codice.
   */
  @Test
  @Config(qualifiers = SHOT_TABLET)
  fun sulTabletIlPannelloDelleRaccolteRitrovaLaSceltaDopoCerca() {
    tab = GalleryTab.Collections
    show(ScaffoldScenes()) { entry -> Text(PANEL + entry.code) }
    // Nessuna voce scelta: il pannello invita a sceglierne una.
    compose.onNodeWithText(PANEL, substring = true).assertDoesNotExist()
    compose.onNodeWithText(TRIP_TITLE).performClick()
    compose.waitForIdle()
    compose.onNodeWithText(PANEL + TRIP_CODE).assertIsDisplayed()

    searchOpen = true
    compose.waitForIdle()
    compose.onNodeWithText(PANEL, substring = true).assertDoesNotExist()

    searchOpen = false
    compose.waitForIdle()
    compose.onNodeWithText(PANEL + TRIP_CODE).assertIsDisplayed()
  }

  private companion object {
    /** Il testo della pagina di prova nel pannello di dettaglio delle Raccolte, seguito dal codice della voce. */
    const val PANEL = "Pannello: "

    /**
     * La seconda voce dei Viaggi delle Raccolte dello scaffold: non e' una scelta predefinita. Dal giro 1 di H5 (CU-9) lo scaffold usa le sezioni
     * come le consegna la produzione (`CollectionsSectionSamples.production`), con il prefisso vero dei viaggi (`AlbumEntryTarget.TRIP_PREFIX`,
     * "trip/") e non piu' quello degli esempi vecchi ("trips/").
     */
    const val TRIP_TITLE = "Sabato 12 aprile 2025"
    const val TRIP_CODE = "trip/t2"

    /** Abbastanza album da riempire piu' schermate del telefono (3 colonne). */
    const val MANY = 40

    /** La prima voce della scheda Album, sempre presente. */
    const val FIRST_ENTRY = "Preferiti"
  }
}
