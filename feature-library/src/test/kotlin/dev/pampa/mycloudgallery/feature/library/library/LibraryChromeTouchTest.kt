package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase H, D2 (11/11, l'utente sull'S25): «toccare la data in alto apre la foto che sta dietro». L'etichetta non aveva nessun gestore di
 * tocco, quindi l'hit test di Compose la saltava e il tocco arrivava alla griglia. Ora i pezzi della chrome in alto trattengono i tocchi che
 * cadono su di loro: l'etichetta e il bordo della capsula dei comandi (fuori dai tasti). Un tocco sulla griglia libera apre ancora.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class LibraryChromeTouchTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val opened = mutableListOf<ViewerRequest>()

  private fun show() {
    val state = syntheticLibraryState(count = 600)
    composeRule.showThemed {
      LibraryTab(
        contentPadding = PaddingValues(bottom = 96.dp),
        backdropOut = {},
        onOpenViewer = { opened += it },
        onOpenSettings = {},
        state = state,
      )
    }
    composeRule.waitForIdle()
  }

  @Test
  fun toccareLaDataInAltoNonApreLaFotoDietro() {
    show()
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performTouchInput {
      down(center)
      up()
    }
    composeRule.waitForIdle()
    assertThat(opened).isEmpty()
  }

  @Test
  fun ilBordoDellaCapsulaDeiComandiNonApreLaFotoDietro() {
    show()
    // Il bordo sinistro della capsula, fuori dai tasti (2 dp di respiro) e la striscia sopra i tasti (la capsula e' piu' alta di 48 dp).
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "comandi").performTouchInput {
      down(Offset(1f, centerY))
      up()
    }
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "comandi").performTouchInput {
      down(Offset(centerX, 1f))
      up()
    }
    composeRule.waitForIdle()
    assertThat(opened).isEmpty()
  }

  @Test
  fun unToccoSullaGrigliaApreAncora() {
    show()
    // Il centro puo' cadere su un'intestazione: si prova qualche punto a meta' schermo, uno di questi e' una cella.
    for (fraction in listOf(0.45f, 0.5f, 0.55f, 0.6f, 0.65f)) {
      if (opened.isNotEmpty()) break
      composeRule.onRoot().performTouchInput {
        down(Offset(width * 0.3f, height * fraction))
        up()
      }
      composeRule.waitForIdle()
    }
    assertThat(opened).hasSize(1)
  }
}
