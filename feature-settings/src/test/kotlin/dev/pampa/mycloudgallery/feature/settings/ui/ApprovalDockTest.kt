package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalActions
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalUiState
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceMapper
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B6 (G3): la pagina "Nuovo dispositivo" ha il tasto "Approva" **fermo in fondo e sopra la tastiera**, con la pagina che gli scorre sotto. Prima era l'ultimo elemento della lista: sotto la
 * piega, e coperto dalla tastiera quando si scrive il codice. La prova e' su un telefono basso (360 x 640 dp): il peggio che l'app deve reggere.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ApprovalDockTest {

  @get:Rule
  val main = MainDispatcherRule()

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val request = DeviceMapper.requests(listOf(Samples.request), Samples.familyAsAdmin(), Samples.NOW).single()

  private val approve get() = rule.onNodeWithText("Approva questo dispositivo")

  private fun show(fontScale: Float? = null) {
    rule.setContent {
      MyCloudGalleryTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
          SettingsHost { ApprovalScreen(ApprovalUiState(request, isAdmin = true), ApprovalActions.None) }
        }
      }
    }
    rule.waitForIdle()
  }

  /** La tastiera aperta: il sistema dice che l'IME occupa [heightDp] dal fondo (con `enableEdgeToEdge` la finestra non si ridimensiona da sola). */
  private fun openKeyboard(heightDp: Int) {
    val px = (heightDp * rule.density.density).toInt()
    rule.runOnUiThread { rule.activity.openKeyboard(px) }
    rule.waitForIdle()
  }

  private fun windowBottom(): Float = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

  private fun approveBounds() = approve.fetchSemanticsNode().boundsInRoot

  @Test
  fun ilTastoEInFondoAllaPaginaSenzaScorrere() {
    show()
    approve.assertIsDisplayed()
    val bounds = approveBounds()
    // E' appoggiato in fondo (sopra la barra di sistema, che qui non c'e'): il suo bordo basso sta vicino a quello della finestra.
    assertThat(windowBottom() - bounds.bottom).isAtMost(rule.density.density * 40f)
    assertThat(bounds.bottom).isAtMost(windowBottom())
  }

  @Test
  fun lapaginaScorreSottoIlTasto_cheNonSiMuove() {
    show()
    val before = approveBounds()
    rule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
    rule.waitForIdle()
    assertThat(approveBounds()).isEqualTo(before)
    approve.assertIsDisplayed()
  }

  @Test
  fun conLaTastieraApertaIlTastoStaSopraLaTastiera() {
    show()
    val closedBounds = approveBounds()
    val keyboardDp = 280
    openKeyboard(keyboardDp)
    val keyboardTop = windowBottom() - keyboardDp * rule.density.density
    val open = approveBounds()
    assertThat(open.bottom).isAtMost(keyboardTop)
    // Si e' alzato di quanto la tastiera sale (meno la barra di sistema, che qui non c'e').
    assertThat(closedBounds.top - open.top).isAtLeast(keyboardDp * rule.density.density - 2f)
    approve.assertIsDisplayed()
  }

  @Test
  fun inFondoALlaPaginaLUltimaNotaStaSopraIlTasto() {
    // La lista finisce dove comincia la barra: scorsa fino in fondo, l'ultima riga non resta sotto il tasto.
    show()
    rule.onNode(hasScrollAction()).performScrollToNode(hasText("Prima di approvare", substring = true))
    rule.waitForIdle()
    val note = rule.onNodeWithText("Prima di approvare", substring = true).fetchSemanticsNode().boundsInRoot
    assertThat(note.bottom).isAtMost(approveBounds().top)
  }

  @Test
  fun conIlTestoGrandeIlTastoSiVedeSenzaScorrere() {
    show(fontScale = 2.0f)
    approve.assertIsDisplayed()
    assertThat(approveBounds().bottom).isAtMost(windowBottom())
  }
}
