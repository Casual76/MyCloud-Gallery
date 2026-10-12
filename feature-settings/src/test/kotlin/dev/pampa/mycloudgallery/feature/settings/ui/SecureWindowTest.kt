package dev.pampa.mycloudgallery.feature.settings.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.account.PasswordActions
import dev.pampa.mycloudgallery.feature.settings.account.PasswordScreen
import dev.pampa.mycloudgallery.feature.settings.account.PasswordUiState
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalActions
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalUiState
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceMapper
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailActions
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailScreen
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailUiState
import dev.pampa.mycloudgallery.feature.settings.devices.LostStage
import dev.pampa.mycloudgallery.feature.settings.family.AdminRecoveryScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyResetScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyStateScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyStateUi
import dev.pampa.mycloudgallery.feature.settings.family.InviteActions
import dev.pampa.mycloudgallery.feature.settings.family.InviteScreen
import dev.pampa.mycloudgallery.feature.settings.family.InviteSummary
import dev.pampa.mycloudgallery.feature.settings.family.InviteUiState
import dev.pampa.mycloudgallery.feature.settings.family.InvitePhase
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryActions
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryMode
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryUiState
import dev.pampa.mycloudgallery.feature.settings.family.ResetStage
import dev.pampa.mycloudgallery.feature.settings.family.ResetUiState
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * `FLAG_SECURE` dove il piano lo chiede (`docs/fase2-progetto.md` §2.2, brief P7): sul **QR d'invito**, sul **QR
 * dello stato della famiglia**, sul **codice** (approvazione e recupero) e sulle schermate della **password**;
 * e la protezione dalle sovrapposizioni sulle conferme distruttive.
 *
 * Il test guarda la finestra vera dell'Activity (Robolectric): la flag c'e' mentre la schermata e' in
 * composizione e **sparisce quando la schermata esce** (il modulo dell'invito, senza QR, non e' riservato).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SecureWindowTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun isSecure(): Boolean =
    (rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  private fun overlaysFiltered(): Boolean = rule.activity.window.decorView.filterTouchesWhenObscured

  private val invite = InviteSummary("anna", "Telefono di Anna", "anna", hoursLeft = 24)

  @Test
  fun ilQrDInvitoHaFlagSecureEIlModuloSenzaQrNo() {
    var state by mutableStateOf(InviteUiState(isAdmin = true))
    rule.setContent { MyCloudGalleryTheme { InviteScreen(state, InviteActions.None) } }
    rule.waitForIdle()
    assertThat(isSecure()).isFalse()

    state = InviteUiState(isAdmin = true, phase = InvitePhase.SHOWING, qrText = "MCG1:PROVA", summary = invite)
    rule.waitForIdle()
    assertThat(isSecure()).isTrue()
    assertThat(overlaysFiltered()).isTrue()

    // Chiuso il QR (si torna al modulo) la protezione si toglie da sola.
    state = InviteUiState(isAdmin = true)
    rule.waitForIdle()
    assertThat(isSecure()).isFalse()
  }

  @Test
  fun ilQrDelloStatoDellaFamigliaHaFlagSecure() {
    var state: FamilyStateUi by mutableStateOf(FamilyStateUi.Loading)
    rule.setContent { MyCloudGalleryTheme { FamilyStateScreen(state, onBack = {}) } }
    rule.waitForIdle()
    assertThat(isSecure()).isFalse()

    state = FamilyStateUi.Ready("MCGS1:PROVA", registryVersion = 12)
    rule.waitForIdle()
    assertThat(isSecure()).isTrue()
  }

  @Test
  fun laPaginaDiApprovazioneProteggeIlCodiceELaFinestra() {
    val request = DeviceMapper.requests(listOf(Samples.request), Samples.familyAsAdmin(), Samples.NOW).single()
    rule.setContent {
      MyCloudGalleryTheme { ApprovalScreen(ApprovalUiState(request = request, isAdmin = true), ApprovalActions.None) }
    }
    rule.waitForIdle()
    assertThat(isSecure()).isTrue()
    assertThat(overlaysFiltered()).isTrue()
  }

  @Test
  fun laPaginaDellaPasswordEProtetta() {
    rule.setContent {
      MyCloudGalleryTheme { PasswordScreen(PasswordUiState(available = true, user = "test2"), PasswordActions.None) }
    }
    rule.waitForIdle()
    assertThat(isSecure()).isTrue()
    assertThat(overlaysFiltered()).isTrue()
  }

  @Test
  fun ilRecuperoDellAdminProteggeIlCodice() {
    rule.setContent {
      MyCloudGalleryTheme {
        AdminRecoveryScreen(RecoveryUiState(mode = RecoveryMode.SECOND_ADMIN, available = true), RecoveryActions.None)
      }
    }
    rule.waitForIdle()
    assertThat(isSecure()).isTrue()
  }

  @Test
  fun ilResetProtegge_dalleSovrapposizioni_laParolaDiConferma() {
    var state by mutableStateOf(ResetUiState(isAdmin = true))
    rule.setContent {
      MyCloudGalleryTheme {
        FamilyResetScreen(state, onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
      }
    }
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isFalse()

    state = ResetUiState(isAdmin = true, stage = ResetStage.TYPE_WORD)
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isTrue()
  }

  @Test
  fun leConfermeDistruttiveProteggonoDalleSovrapposizioniSoloMentreSonoAperte() {
    val tablet = DeviceMapper.devices(listOf(Samples.tabletMamma()), Samples.NOW).single()
    var stage by mutableStateOf(LostStage.NONE)
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsHost {
          DeviceDetailScreen(
            state = DeviceDetailUiState(
              device = tablet,
              trust = TrustStatus.Approved(admin = true),
              canManage = true,
              canReportOwn = false,
              lostStage = stage,
            ),
            actions = DeviceDetailActions.None,
          )
        }
      }
    }
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isFalse()

    stage = LostStage.FIRST_CONFIRM
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isTrue()

    stage = LostStage.SECOND_CONFIRM
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isTrue()

    stage = LostStage.NONE
    rule.waitForIdle()
    assertThat(overlaysFiltered()).isFalse()
  }
}
