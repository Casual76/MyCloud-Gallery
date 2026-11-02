package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D24, E-AC10): la finestra dell'app e' marcata "dati sensibili per l'accessibilita'" dal tema, e solo finche' il tema c'e'. Che un
 * servizio che non e' uno strumento di accessibilita' non legga niente lo prova il servizio di prova **solo debug** su un emulatore
 * (`docs/decisioni.md`, Fase 10 P8): qui si prova la marcatura.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryDataSensitiveTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun ilTemaMarcaLaVistaDellaFinestra() {
    var view: View? = null
    composeRule.setContent { MyCloudGalleryTheme { view = LocalView.current } }
    composeRule.waitForIdle()
    assertThat(AccessibilityDataSensitiveSupported).isTrue()
    assertThat(view!!.isAccessibilityDataSensitive).isTrue()
  }

  @Test
  fun nelleAnteprimeSiPuoSpegnere() {
    var view: View? = null
    composeRule.setContent { MyCloudGalleryTheme(protectAccessibilityData = false) { view = LocalView.current } }
    composeRule.waitForIdle()
    assertThat(view!!.isAccessibilityDataSensitive).isFalse()
  }

  @Test
  fun siToglieQuandoIlTemaSparisce() {
    var view: View? = null
    var themed by mutableStateOf(true)
    composeRule.setContent {
      view = LocalView.current
      if (themed) MyCloudGalleryTheme { }
    }
    composeRule.waitForIdle()
    assertThat(view!!.isAccessibilityDataSensitive).isTrue()
    themed = false
    composeRule.waitForIdle()
    assertThat(view!!.isAccessibilityDataSensitive).isFalse()
  }
}
