package dev.pampa.mycloudgallery.core.security.screen

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** I composable SecureScreen / OverlayShield / SensitiveScreen: attivi finche' sono in composizione. */
@RunWith(RobolectricTestRunner::class)
class ScreenProtectionTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun grantNormalPermission() {
    // HIDE_OVERLAY_WINDOWS e' un permesso normale: su un telefono vero e' concesso all'installazione.
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
      .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS")
  }

  private fun Window.secure() = (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  private val activityWindow: Window get() = rule.activity.window

  @Test
  fun secureScreen_proteggeFinoAlla_uscitaDellaSchermata() {
    var shown by mutableStateOf(true)
    rule.setContent { if (shown) SecureScreen() }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isTrue()

    rule.runOnUiThread { shown = false }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()
  }

  @Test
  fun secureScreen_disabilitato_nonFaNiente() {
    rule.setContent { SecureScreen(enabled = false) }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()
  }

  @Test
  fun secureScreen_siPuoAccendereESpegnereAlCambiareDelloStato() {
    var enabled by mutableStateOf(false)
    rule.setContent { SecureScreen(enabled) }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()

    rule.runOnUiThread { enabled = true }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isTrue()

    rule.runOnUiThread { enabled = false }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()
  }

  @Test
  fun dueSchermateInsieme_restaProtettoFinoAllUltima() {
    var first by mutableStateOf(true)
    var second by mutableStateOf(true)
    rule.setContent {
      if (first) SecureScreen()
      if (second) SecureScreen()
    }
    rule.waitForIdle()
    rule.runOnUiThread { first = false }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isTrue()
    rule.runOnUiThread { second = false }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()
  }

  @Test
  fun sensitiveScreen_haFlagSecureEAntiSovrapposizioni() {
    var shown by mutableStateOf(true)
    rule.setContent { if (shown) SensitiveScreen() }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isTrue()
    assertThat(activityWindow.decorView.filterTouchesWhenObscured).isTrue()

    rule.runOnUiThread { shown = false }
    rule.waitForIdle()
    assertThat(activityWindow.secure()).isFalse()
    assertThat(activityWindow.decorView.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun dentroUnDialog_lAntiSovrapposizioniVaSullaFinestraDelDialog_enonSuQuellaDellActivity() {
    var dialogWindow: Window? = null
    rule.setContent {
      Dialog(onDismissRequest = {}) {
        dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        OverlayShield()
        BasicText("conferma")
      }
    }
    rule.waitForIdle()

    val dialog = dialogWindow
    assertThat(dialog).isNotNull()
    assertThat(dialog!!.decorView.filterTouchesWhenObscured).isTrue()
    assertThat(activityWindow.decorView.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun unDialogConPoliticaSecureOn_haFlagSecureSullaPropriaFinestra() {
    // Per FLAG_SECURE su un Dialog Compose si usa la sua politica: e' il Dialog a gestire la flag e a rimetterla
    // a ogni aggiornamento dei parametri (un SecureScreen() dentro il dialog verrebbe sovrascritto).
    var dialogWindow: Window? = null
    rule.setContent {
      Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        BasicText("codice QR")
      }
    }
    rule.waitForIdle()

    assertThat(dialogWindow!!.secure()).isTrue()
    assertThat(activityWindow.secure()).isFalse()
  }

  @Test
  fun unDialogConPoliticaPredefinita_ereditaFlagSecureDallaSchermataSotto() {
    var dialogWindow: Window? = null
    var showDialog by mutableStateOf(false)
    rule.setContent {
      SecureScreen() // la schermata sotto e' riservata
      if (showDialog) {
        Dialog(onDismissRequest = {}) {
          dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
          BasicText("dettaglio")
        }
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread { showDialog = true }
    rule.waitForIdle()

    assertThat(activityWindow.secure()).isTrue()
    assertThat(dialogWindow!!.secure()).isTrue()
  }

  @Test
  fun hostWindow_senzaActivity_nonCrasha() {
    // una vista con un contesto che non e' un'activity (es. un'anteprima): nessuna finestra, nessuna eccezione
    val view = android.view.View(rule.activity.applicationContext)
    assertThat(hostWindow(view)).isNull()
  }
}
