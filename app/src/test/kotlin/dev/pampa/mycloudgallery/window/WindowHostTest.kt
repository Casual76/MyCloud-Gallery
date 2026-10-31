package dev.pampa.mycloudgallery.window

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L'ospite di una finestra per gli oggetti del processo che ne hanno uno solo (Fase 10, P4, D18): le azioni del backup e i comandi della Libreria agiscono sulla finestra che ha
 * il fuoco, non sull'ultima che si e' composta. Due finestre finte nella stessa composizione, ognuna col suo fuoco.
 */
@RunWith(RobolectricTestRunner::class)
class WindowHostTest {

  private val rule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(rule)

  private class Process {
    var host: String? = null
  }

  private class FakeWindowInfo(override val isWindowFocused: Boolean) : WindowInfo

  @androidx.compose.runtime.Composable
  private fun Window(name: String, focused: Boolean, process: Process) {
    CompositionLocalProvider(LocalWindowInfo provides FakeWindowInfo(focused)) {
      WindowHost(host = name, key = process, current = { process.host }, set = { process.host = it })
    }
  }

  @Test
  fun unaFinestraSola_siRegistraSubito_ancheSenzaFuoco() {
    val process = Process()
    rule.setContent { Window("A", focused = false, process = process) }
    rule.waitForIdle()
    // Un dialogo di sistema le toglie il fuoco: l'ospite resta, e le azioni continuano ad avere una finestra.
    assertThat(process.host).isEqualTo("A")
  }

  @Test
  fun laSecondaFinestraNonSiPrendeLOspiteSenzaIlFuoco_eLoPrendeQuandoLoHa() {
    val process = Process()
    var focusedB by mutableStateOf(false)
    rule.setContent {
      Column {
        Window("A", focused = true, process = process)
        Window("B", focused = focusedB, process = process)
      }
    }
    rule.waitForIdle()
    // B e' l'ultima composta ma non ha il fuoco: l'ospite e' A (dove l'utente ha toccato).
    assertThat(process.host).isEqualTo("A")

    rule.runOnUiThread { focusedB = true }
    rule.waitForIdle()
    assertThat(process.host).isEqualTo("B")
  }

  @Test
  fun chiudendoLaFinestraDelLOspite_siLiberaEdIlFuocoDellAltraLoRiprende() {
    val process = Process()
    var showA by mutableStateOf(true)
    var focusedB by mutableStateOf(false)
    rule.setContent {
      Column {
        if (showA) Window("A", focused = true, process = process)
        Window("B", focused = focusedB, process = process)
      }
    }
    rule.waitForIdle()
    assertThat(process.host).isEqualTo("A")

    // A si chiude: l'ospite si libera (nessuna azione resta legata a una finestra che non c'e' piu').
    rule.runOnUiThread { showA = false }
    rule.waitForIdle()
    assertThat(process.host).isNull()

    // B, che ora e' quella davanti, ha il fuoco: diventa l'ospite.
    rule.runOnUiThread { focusedB = true }
    rule.waitForIdle()
    assertThat(process.host).isEqualTo("B")
  }

  @Test
  fun chiudereUnaFinestraCheNonEL_ospite_nonLoTocca() {
    val process = Process()
    var showB by mutableStateOf(true)
    rule.setContent {
      Column {
        Window("A", focused = true, process = process)
        if (showB) Window("B", focused = false, process = process)
      }
    }
    rule.waitForIdle()
    assertThat(process.host).isEqualTo("A")

    rule.runOnUiThread { showB = false }
    rule.waitForIdle()
    assertThat(process.host).isEqualTo("A")
  }
}
