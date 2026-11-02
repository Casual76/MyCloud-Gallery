package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, I3 della revisione di sicurezza (C439): un dialogo e' una finestra a se', con una vista che non discende da quella marcata dal tema.
 * Qui si prova che (1) il problema c'e' (un `Dialog` semplice sotto il tema che marca NON e' marcato: se un giorno lo fosse, l'involucro non
 * servirebbe piu'); (2) [GalleryDialog] e [GalleryAlertDialog] marcano la vista del loro dialogo, anche con il tema che non marca (cosi' la
 * prova non dipende dalla radice), e la marcatura sparisce quando il dialogo si chiude.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryDialogTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun unDialogSempliceNonEMarcatoDalTemaDellaFinestra() {
    var root: View? = null
    var dialog: View? = null
    composeRule.setContent {
      MyCloudGalleryTheme {
        root = LocalView.current
        Dialog(onDismissRequest = {}) { dialog = LocalView.current; Text("dialogo") }
      }
    }
    composeRule.waitForIdle()
    assertThat(root!!.isAccessibilityDataSensitive).isTrue()
    assertThat(dialog).isNotNull()
    assertThat(dialog).isNotSameInstanceAs(root)
    assertThat(dialog!!.isAccessibilityDataSensitive).isFalse()
  }

  @Test
  fun galleryDialogMarcaLaVistaDelDialogo_ancheSeLaRadiceNonMarca() {
    var root: View? = null
    var dialog: View? = null
    composeRule.setContent {
      MyCloudGalleryTheme(protectAccessibilityData = false) {
        root = LocalView.current
        GalleryDialog(onDismissRequest = {}) { dialog = LocalView.current; Text("dialogo") }
      }
    }
    composeRule.waitForIdle()
    assertThat(root!!.isAccessibilityDataSensitive).isFalse()
    assertThat(dialog).isNotSameInstanceAs(root)
    assertThat(dialog!!.isAccessibilityDataSensitive).isTrue()
  }

  @Test
  fun galleryAlertDialogMarcaLaVistaDelDialogo_inOgniSlot() {
    var title: View? = null
    var text: View? = null
    var confirm: View? = null
    var dismiss: View? = null
    composeRule.setContent {
      MyCloudGalleryTheme(protectAccessibilityData = false) {
        GalleryAlertDialog(
          onDismissRequest = {},
          confirmButton = { confirm = LocalView.current; Text("Conferma") },
          dismissButton = { dismiss = LocalView.current; Text("Annulla") },
          title = { title = LocalView.current; Text("Titolo") },
          text = { text = LocalView.current; Text("Testo") },
        )
      }
    }
    composeRule.waitForIdle()
    // tutti gli slot stanno nella stessa vista, quella della finestra del dialogo, ed e' marcata
    assertThat(setOf(title, text, confirm, dismiss)).hasSize(1)
    assertThat(title!!.isAccessibilityDataSensitive).isTrue()
  }

  @Test
  fun galleryAlertDialogSenzaTitoloNeTesto_eMarcatoLoStesso() {
    var confirm: View? = null
    composeRule.setContent {
      MyCloudGalleryTheme(protectAccessibilityData = false) {
        GalleryAlertDialog(onDismissRequest = {}, confirmButton = { confirm = LocalView.current; Text("Ok") })
      }
    }
    composeRule.waitForIdle()
    assertThat(confirm!!.isAccessibilityDataSensitive).isTrue()
  }

  @Test
  fun laMarcaturaSiToglieQuandoIlDialogoSiChiude() {
    var dialog: View? = null
    var shown by mutableStateOf(true)
    composeRule.setContent {
      MyCloudGalleryTheme(protectAccessibilityData = false) {
        if (shown) GalleryDialog(onDismissRequest = {}) { dialog = LocalView.current; Text("dialogo") }
      }
    }
    composeRule.waitForIdle()
    val view = dialog!!
    assertThat(view.isAccessibilityDataSensitive).isTrue()
    shown = false
    composeRule.waitForIdle()
    assertThat(view.isAccessibilityDataSensitive).isFalse()
  }
}
