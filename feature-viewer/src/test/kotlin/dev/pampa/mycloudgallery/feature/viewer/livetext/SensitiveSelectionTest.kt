package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.tripleClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R17: il "Copia" della selezione di sistema nel foglio "Testo nella foto" scrive negli appunti **segnando il testo come sensibile**
 * (`EXTRA_IS_SENSITIVE`), come "Copia" e "Copia tutto" dell'app. Un codice o una password fotografati non devono finire nell'anteprima degli appunti
 * ne' nella cronologia della tastiera. Prima la selezione usava gli appunti di Compose senza il segno.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SensitiveSelectionTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private fun clip(): ClipData? = context.getSystemService(ClipboardManager::class.java).primaryClip

  private fun sensitive(clip: ClipData?): Boolean = clip?.description?.extras?.getBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE) == true

  /** Un componente che scrive negli appunti di Compose, come fa il "Copia" della selezione: lo si compone dentro il contenitore e fuori. */
  @Composable
  private fun CopyProbe(text: String) {
    val clipboard = LocalClipboard.current
    LaunchedEffect(Unit) { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }
  }

  @Test
  fun gliAppuntiDiComposeDentroLaSelezioneDelFoglioScrivonoSensibile_R17() {
    rule.setContent { SensitiveSelectionContainer { CopyProbe("123456") } }
    rule.waitForIdle()
    assertThat(clip()?.getItemAt(0)?.text?.toString()).isEqualTo("123456")
    assertThat(sensitive(clip())).isTrue()
  }

  /** La sostituzione vale solo dentro il foglio: fuori, gli appunti di Compose restano quelli di sempre (e non sensibili). */
  @Test
  fun fuoriDalFoglioGliAppuntiDiComposeNonCambiano() {
    rule.setContent { CopyProbe("ciao") }
    rule.waitForIdle()
    assertThat(clip()?.getItemAt(0)?.text?.toString()).isEqualTo("ciao")
    assertThat(sensitive(clip())).isFalse()
  }

  /**
   * La prova vera, con la selezione di sistema: si seleziona la riga di testo del foglio (tre clic del mouse: la pressione prolungata col dito apre la
   * lente d'ingrandimento di sistema, che Robolectric non sa disegnare) e si copia con Ctrl+C, che la `SelectionContainer` gestisce con la stessa funzione
   * del "Copia" del menu. Negli appunti di sistema deve arrivare il testo, segnato sensibile.
   */
  @Test
  fun ilCopiaDellaSelezioneDiSistemaEUnCopiaSensibile_R17() {
    rule.setContent { SensitiveSelectionContainer { Text("codice 123456") } }
    rule.onNodeWithText("codice 123456").performMouseInput { tripleClick() }
    rule.waitForIdle()
    rule.onNodeWithText("codice 123456").performKeyInput {
      keyDown(Key.CtrlLeft)
      pressKey(Key.C)
      keyUp(Key.CtrlLeft)
    }
    rule.waitForIdle()
    val copied = clip()
    assertThat(copied?.getItemAt(0)?.text?.toString()).isNotEmpty()
    assertThat(sensitive(copied)).isTrue()
  }

  @Test
  fun ilSegnoSullaDescrizioneEQuelloDelleChiavi() {
    val clip = ClipData.newPlainText("x", "y")
    SensitiveClipboards.mark(clip)
    assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
  }
}
