package dev.pampa.mycloudgallery.feature.diagnostics.actions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagActions
import org.junit.Test

/**
 * Le azioni che la Diagnostica di rete propone (verifica funzionale, RE-10): «Ripeti il riconoscimento» non aveva un'etichetta, quindi non
 * aveva nemmeno il tasto. Ogni azione che `core-network` dichiara ha un'etichetta, e si propone solo se `:app` dichiara di eseguirla.
 */
class NetworkActionsTest {

  @Test
  fun ogniAzioneDiReteHaUnEtichetta() {
    for (action in NetworkDiagActions.ALL) {
      assertWithMessage(action.id).that(DiagnosticsActions.labelRes(action)).isNotEqualTo(0)
    }
  }

  @Test
  fun siProponeSoloSeLAppLaEsegue() {
    for (action in NetworkDiagActions.ALL) {
      assertThat(DiagnosticsActions.isAvailable(action, appActions = emptySet())).isFalse()
      assertThat(DiagnosticsActions.isAvailable(action, appActions = setOf(action))).isTrue()
    }
  }
}
