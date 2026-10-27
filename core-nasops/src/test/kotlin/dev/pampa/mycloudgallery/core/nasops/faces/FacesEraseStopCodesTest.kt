package dev.pampa.mycloudgallery.core.nasops.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import org.junit.Test

/**
 * I codici degli arresti e dei motivi della cancellazione dei volti sono un contratto con core-faces (Fase 8, P8): core-faces non vede core-nasops, e
 * `:app` (P13) gli passa il **codice** (`FacesEraseCodes.round` di core-faces legge esattamente questi). Un codice cambiato o aggiunto qui va
 * riportato la' (e in questa prova).
 */
class FacesEraseStopCodesTest {

  @Test
  fun iCodiciSonoQuelliCheCoreFacesLegge() {
    assertThat(FacesEraseStop.entries.map { it.code }).containsExactly(
      "done", "budget", "unreachable", "frozen", "emergency_stop", "clock_jump", "read_only", "not_trusted", "test_shares_only", "not_private_area",
      "not_allowed", "unsure",
    ).inOrder()
    assertThat(FacesEraseReason.entries.map { it.code }).containsExactly("user_confirmed", "not_current", "device_revoked").inOrder()
  }
}
