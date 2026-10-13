package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lo stub di P0 del viewer su Robolectric: si apre sull'elemento richiesto, mostra la posizione, si chiude dal tasto, e
 * quando l'immagine non c'e' lo dice invece di restare vuoto (E-V1, E-V2 con sorgenti finte).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class ViewerStubTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
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

  private val library = SyntheticLibrary(seed = 3, count = 50)

  private fun request(startIndex: Int = 4) = ViewerRequest(
    items = library.viewerItems(),
    start = library.snapshot.idAt(startIndex),
    anchor = FakeViewerAnchor(),
    secure = false,
  )

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) { content() }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun siApreSullElementoRichiestoESiChiudeDalTasto() {
    var closed = 0
    show { ViewerOverlay(request = request(startIndex = 4), onClosed = { closed++ }, images = FakeViewerImageSource()) }
    composeRule.onNodeWithText("5 di 50").assertExists()
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    assertThat(closed).isEqualTo(1)
  }

  @Test
  fun senzaRichiestaNonDisegnaNiente() {
    show { ViewerOverlay(request = null, onClosed = {}) }
    composeRule.onNodeWithText("1 di 50").assertDoesNotExist()
  }

  @Test
  fun quandoLImmagineNonCeLoDice() {
    val images = FakeViewerImageSource().apply { unreachable(UnavailableReason.NAS_UNREACHABLE) }
    show { ViewerOverlay(request = request(), onClosed = {}, images = images) }
    composeRule.onNodeWithText("Questo contenuto non è disponibile adesso.").assertExists()
  }

  @Test
  fun conLaListaVuotaSiChiude() {
    var closed = 0
    val empty = SyntheticLibrary(seed = 3, count = 1)
    show {
      ViewerOverlay(
        request = ViewerRequest(
          items = EmptyViewerItems,
          start = empty.snapshot.idAt(0),
          anchor = FakeViewerAnchor(),
          secure = false,
        ),
        onClosed = { closed++ },
      )
    }
    assertThat(closed).isAtLeast(1)
  }
}
