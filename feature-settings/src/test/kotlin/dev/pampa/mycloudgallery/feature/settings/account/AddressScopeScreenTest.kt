package dev.pampa.mycloudgallery.feature.settings.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.network.SaveScope
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingComposeBase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il cambio d'indirizzo dice fin dove vale (verifica funzionale, RE-5): per tutta la famiglia se e' l'admin e la firma e' riuscita, solo per
 * questo telefono se non e' admin, e con un "Riprova" se la firma dell'admin non e' riuscita.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class AddressScopeScreenTest : SharingComposeBase() {

  private fun done(saved: SaveScope?, admin: Boolean = false) =
    AddressUiState(available = true, currentIp = "192.168.178.99", phase = AddressPhase.DONE, admin = admin, saved = saved)

  @Test
  fun l_adminVedeCheIlNuovoIndirizzoVaAncheNeiNuoviInviti() {
    show { AddressScreen(done(SaveScope.FAMILY, admin = true), AddressActions.None) }

    assertThat(has(string(R.string.address_scope_family))).isTrue()
    assertThat(has(string(R.string.address_retry_share))).isFalse()
  }

  @Test
  fun unFamiliareNonAdminVedeCheVaSoloSuQuestoTelefono() {
    show { AddressScreen(done(SaveScope.THIS_PHONE), AddressActions.None) }

    assertThat(has(string(R.string.address_scope_phone))).isTrue()
    assertThat(has(string(R.string.address_scope_family))).isFalse()
  }

  @Test
  fun seLaFirmaNonRiesceSiPuoRiprovare() {
    var retried = 0
    val actions = AddressActions(
      onBack = {}, onTyped = {}, onVerify = {}, onAskConfirm = {}, onCancelConfirm = {}, onEdit = {}, onConfirm = {},
      onRetryShare = { retried++ },
    )
    show { AddressScreen(done(SaveScope.FAMILY_FAILED, admin = true), actions) }

    assertThat(has(string(R.string.address_scope_failed))).isTrue()
    rule.onNodeWithText(string(R.string.address_retry_share)).assertIsDisplayed().performClick()
    assertThat(retried).isEqualTo(1)
  }

  @Test
  fun primaDiConfermareChiNonEAdminLeggeCheCambiaSoloQui() {
    val verified = AddressUiState(available = true, currentIp = "192.168.178.58", typed = "192.168.178.99", phase = AddressPhase.VERIFIED)
    show { AddressScreen(verified, AddressActions.None) }

    assertThat(has(string(R.string.address_footnote))).isTrue()
    assertThat(has(string(R.string.address_footnote_admin))).isFalse()
  }

  @Test
  fun primaDiConfermareL_adminLeggeCheVaAncheAiNuoviInviti() {
    val verified = AddressUiState(
      available = true, currentIp = "192.168.178.58", typed = "192.168.178.99", phase = AddressPhase.VERIFIED, admin = true,
    )
    show { AddressScreen(verified, AddressActions.None) }

    assertThat(has(string(R.string.address_footnote_admin))).isTrue()
  }
}
