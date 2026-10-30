package dev.pampa.mycloudgallery.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagActions
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.settings.SettingsNetworkRoute
import org.junit.Test

/**
 * Le azioni della Diagnostica che portano fuori dalla Diagnostica (verifica funzionale, RE-10): «Ripeti il riconoscimento» finiva in un
 * ramo che non faceva niente, e senza etichetta non aveva nemmeno il tasto. Ogni azione che `:app` dichiara di eseguire ha un'etichetta e
 * una destinazione.
 */
class DiagnosticsRoutesTest {

  /** Le azioni che `:app` esegue con un gestore proprio (non con una rotta): l'emergenza e lo scongelamento del NAS. */
  private val handledInline = setOf(DiagnosticsActions.NAS_STOP_WRITES, DiagnosticsActions.NAS_RESUME_WRITES, DiagnosticsActions.NAS_RELEASE_FREEZE)

  @Test
  fun ogniAzioneDichiarataHaUnTastoEUnaDestinazione() {
    for (action in DIAGNOSTICS_APP_ACTIONS) {
      assertWithMessage(action.id).that(DiagnosticsActions.labelRes(action)).isNotEqualTo(0)
      if (action !in handledInline) assertWithMessage(action.id).that(diagnosticsRouteOf(action)).isNotNull()
    }
  }

  @Test
  fun ripetereIlRiconoscimentoEdAttivareIlControlloPortanoAReteEVpn() {
    assertThat(diagnosticsRouteOf(NetworkDiagActions.RECAPTURE_HOME)).isEqualTo(SettingsNetworkRoute)
    assertThat(diagnosticsRouteOf(NetworkDiagActions.OPEN_VPN_CONTROL)).isEqualTo(SettingsNetworkRoute)
    assertThat(NetworkDiagActions.ALL).containsExactlyElementsIn(DIAGNOSTICS_APP_ACTIONS.filter { it in NetworkDiagActions.ALL })
  }

  /** DG-2 (correzioni E): "Fallo su questo dispositivo" di `FACES_NO_INDEXER` porta a Ricerca e modelli, dove si accende il ruolo di Indicizzatore. */
  @Test
  fun dg2_falloSuQuestoDispositivoPortaARicercaEModelli() {
    assertThat(DiagnosticsActions.OPEN_INDEXER_ROLE).isIn(DIAGNOSTICS_APP_ACTIONS)
    assertThat(diagnosticsRouteOf(DiagnosticsActions.OPEN_INDEXER_ROLE)).isEqualTo(SearchSettingsPageRoute)
    assertThat(DiagnosticsActions.labelRes(DiagnosticsActions.OPEN_INDEXER_ROLE)).isNotEqualTo(0)
  }

  @Test
  fun unAzioneSconosciutaNonPortaDaNessunaParte() {
    assertThat(diagnosticsRouteOf(DiagAction("azione_inventata"))).isNull()
  }
}
