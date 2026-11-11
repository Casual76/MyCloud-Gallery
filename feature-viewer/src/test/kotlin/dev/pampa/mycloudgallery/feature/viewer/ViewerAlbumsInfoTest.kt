package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerAlbumEntry
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerMediaAlbums
import dev.pampa.mycloudgallery.feature.viewer.info.rememberAlbumsPanel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H2, giro di correzione 1: gli album in cui sta l'elemento, nel pannello informazioni. Una voce per album, che apre l'album; senza album (o
 * senza la porta, o a pannello chiuso) la sezione non c'e' e la porta non si legge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class ViewerAlbumsInfoTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val photo = MediaId.random()

  private class FakeAlbums(private val entries: List<ViewerAlbumEntry>) : ViewerMediaAlbums {
    val asked = mutableListOf<MediaId>()
    val opened = mutableListOf<ViewerAlbumEntry>()

    override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> {
      asked += media
      return flowOf(entries)
    }

    override fun open(album: ViewerAlbumEntry) {
      opened += album
    }
  }

  private fun show(source: ViewerMediaAlbums?, active: Boolean = true) {
    composeRule.setContent {
      ViewerTestHost(dark = false) {
        Box(Modifier.fillMaxSize()) {
          val panel = rememberAlbumsPanel(source, photo, active)
          OpenInfoSheet { InfoSections(panel.sections) }
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun exists(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun gliAlbumDellElementoSiVedonoEUnToccoApreLAlbum() {
    val vacanze = ViewerAlbumEntry(key = "a", title = "Vacanze 2024", count = 120)
    val source = FakeAlbums(listOf(vacanze, ViewerAlbumEntry(key = "b", title = "Famiglia", count = 40)))
    show(source)
    assertThat(exists("Album")).isTrue()
    assertThat(exists("Vacanze 2024")).isTrue()
    assertThat(exists("Famiglia")).isTrue()
    composeRule.onNodeWithText("Vacanze 2024").performClick()
    composeRule.waitForIdle()
    assertThat(source.opened).containsExactly(vacanze)
  }

  @Test
  fun unAlbumCondivisoHaLaSuaCapsula_conIlSegnoCheECondiviso() {
    // Album condivisi nel pannello (H2, giro 2): la capsula c'e' come le altre e dice che e' condiviso; un tocco la apre come le altre.
    val famiglia = ViewerAlbumEntry(key = "s", title = "Famiglia", count = 40, shared = true)
    val source = FakeAlbums(listOf(ViewerAlbumEntry(key = "a", title = "Vacanze 2024", count = 120), famiglia))
    show(source)
    composeRule.onNode(hasText("Famiglia") and hasStateDescription("condiviso")).assertExists()
    composeRule.onNode(hasText("Vacanze 2024") and hasStateDescription("condiviso")).assertDoesNotExist()
    composeRule.onNodeWithText("Famiglia").performClick()
    composeRule.waitForIdle()
    assertThat(source.opened).containsExactly(famiglia)
  }

  @Test
  fun senzaAlbumSenzaPortaOAPannelloChiusoLaSezioneNonCe() {
    show(FakeAlbums(emptyList()))
    assertThat(exists("Album")).isFalse()
  }

  @Test
  fun aPannelloChiusoLaPortaNonSiLegge() {
    val source = FakeAlbums(listOf(ViewerAlbumEntry(key = "a", title = "Vacanze 2024", count = 1)))
    show(source, active = false)
    assertThat(exists("Vacanze 2024")).isFalse()
    assertThat(source.asked).isEmpty()
  }
}
