package dev.pampa.mycloudgallery.feature.viewer.livetext

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import org.junit.Test

/**
 * Revisione A, VT-2 (Fase 6): il testo dell'indice che cambia **a foto aperta** si vede sul viewer vero. Il pulsante "Testo" compare
 * quando il testo arriva (da un altro dispositivo, o dal calcolo di qui) e sparisce, con le sue righe, quando il testo si ritira.
 */
class ViewerLiveTextChangesTest : LiveTextViewerBase() {

  @Test
  fun vt2_ilPulsanteTestoCompareQuandoIlTestoArrivaAFotoAperta_eSparisceQuandoSiRitira() {
    val services = textServices(layout = null)
    val source = services.textSource as FakeViewerTextSource
    showText(services)
    assertThat(hasNode(BUTTON)).isFalse()

    source.layouts[shown] = LiveTextTestLines.multiline
    composeRule.runOnIdle { source.changed() }
    composeRule.waitForIdle()
    assertThat(hasNode(BUTTON)).isTrue()

    source.layouts.remove(shown)
    composeRule.runOnIdle { source.changed() }
    composeRule.waitForIdle()
    assertThat(hasNode(BUTTON)).isFalse()
  }

  private companion object {
    /** La descrizione del pulsante "Testo" (D43, D46). */
    const val BUTTON = "Testo nella foto"
  }
}
