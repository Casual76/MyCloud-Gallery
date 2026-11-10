package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
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
 * dato, sul telefono dell'utente.
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

  private fun show(scenes: ScaffoldScenes) {
    compose.setContent {
      MyCloudGalleryTheme {
        GalleryScaffold(
          scenes = scenes,
          tab = tab,
          searchOpen = searchOpen,
          onSelectTab = { tab = it },
          onOpenSearch = { searchOpen = true },
          onCloseSearch = { searchOpen = false },
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

  private companion object {
    /** Abbastanza album da riempire piu' schermate del telefono (3 colonne). */
    const val MANY = 40

    /** La prima voce della scheda Album, sempre presente. */
    const val FIRST_ENTRY = "Preferiti"
  }
}
