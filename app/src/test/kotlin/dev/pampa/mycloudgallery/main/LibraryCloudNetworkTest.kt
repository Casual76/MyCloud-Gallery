package dev.pampa.mycloudgallery.main

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.library.LibraryNetworkAction
import dev.pampa.mycloudgallery.feature.library.library.LibraryNetworkSeverity
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.StatusAction
import org.junit.Test

/**
 * B2 della beta: la rete non e' piu' una voce in fondo alla Libreria ma uno stato della nuvola. Ogni stato dell'indicatore globale ha
 * il suo peso (coerente con il tono dell'indicatore: un avviso e' da guardare, un pericolo chiede l'utente), il suo glifo e l'uscita
 * che il tocco sull'indicatore apre.
 */
class LibraryCloudNetworkTest {

  @Test
  fun ilPesoSegueIlTonoDellIndicatore() {
    for (status in GlobalStatus.entries) {
      val severity = libraryNetworkSeverity(status)
      val expected = when {
        status == GlobalStatus.OFFLINE -> LibraryNetworkSeverity.Offline
        status == GlobalStatus.WAKING_NAS -> LibraryNetworkSeverity.Working
        status.tone == FluidTone.Warning -> LibraryNetworkSeverity.Attention
        status.tone == FluidTone.Danger -> LibraryNetworkSeverity.Alarm
        else -> LibraryNetworkSeverity.Fine
      }
      assertWithMessage(status.name).that(severity).isEqualTo(expected)
    }
  }

  @Test
  fun leRichiesteDellaBetaSonoProblemi() {
    // "NAS irraggiungibile, rete assente": la nuvola lo dice.
    assertThat(libraryNetworkSeverity(GlobalStatus.NAS_UNREACHABLE)).isEqualTo(LibraryNetworkSeverity.Attention)
    assertThat(libraryNetworkSeverity(GlobalStatus.OFFLINE)).isEqualTo(LibraryNetworkSeverity.Offline)
    assertThat(libraryNetworkSeverity(GlobalStatus.NEEDS_PASSWORD)).isEqualTo(LibraryNetworkSeverity.Alarm)
    // A casa con il NAS, o fuori in VPN, la rete non ha niente da dire.
    for (fine in listOf(GlobalStatus.HOME_CONNECTED, GlobalStatus.HOME_IDLE, GlobalStatus.AWAY_VPN_WIFI, GlobalStatus.AWAY_VPN_MOBILE)) {
      assertWithMessage(fine.name).that(libraryNetworkSeverity(fine)).isEqualTo(LibraryNetworkSeverity.Fine)
    }
  }

  @Test
  fun ilTastoFaCioCheFaIlToccoSullIndicatore() {
    for (status in GlobalStatus.entries) {
      val expected = when (status.action) {
        StatusAction.OPEN_DIAGNOSTICS -> LibraryNetworkAction.DIAGNOSTICS
        StatusAction.OPEN_WIREGUARD -> LibraryNetworkAction.WIREGUARD
        StatusAction.ENTER_PASSWORD -> LibraryNetworkAction.PASSWORD
      }
      assertWithMessage(status.name).that(libraryNetworkAction(status)).isEqualTo(expected)
    }
    assertThat(libraryNetworkAction(GlobalStatus.VPN_OFF)).isEqualTo(LibraryNetworkAction.WIREGUARD)
    assertThat(libraryNetworkAction(GlobalStatus.NEEDS_PASSWORD)).isEqualTo(LibraryNetworkAction.PASSWORD)
  }

  @Test
  fun ogniStatoTranneIlRisveglioHaUnGlifo() {
    for (status in GlobalStatus.entries) {
      val glyph = libraryNetworkGlyph(status)
      if (status == GlobalStatus.WAKING_NAS) assertThat(glyph).isNull() else assertWithMessage(status.name).that(glyph).isNotNull()
    }
  }
}
