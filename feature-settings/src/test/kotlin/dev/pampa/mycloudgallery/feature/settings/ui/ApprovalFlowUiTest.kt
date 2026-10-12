package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalActions
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalViewModel
import dev.pampa.mycloudgallery.feature.settings.devices.ProofMode
import dev.pampa.mycloudgallery.feature.settings.support.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.getValue

/**
 * L'approvazione dalla schermata vera, con i fake: **confronto del codice** e **scansione del QR**. La
 * schermata e il ViewModel sono collegati come fa `ApprovalRoute`, senza Hilt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1800dp-xhdpi")
class ApprovalFlowUiTest {

  @get:Rule
  val main = MainDispatcherRule()

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val registry = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin(), listOf(Samples.request))
  private val codec = FakeQrPayloadCodec<DeviceKeyQr>()
  private val authenticator = FakeAuthenticator()

  private val viewModel = ApprovalViewModel(
    handle = SavedStateHandle(mapOf(ApprovalViewModel.REQUEST_ID to Samples.REQUEST_ID)),
    registry = registry,
    deviceKeyCodec = codec,
    sessions = FakeNasSessionManager(),
    clock = Samples.clock,
  )

  private fun show() {
    rule.setContent {
      MyCloudGalleryTheme {
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        ApprovalScreen(
          state = state,
          actions = ApprovalActions(
            onBack = {},
            onMode = viewModel::setMode,
            onCode = viewModel::setCode,
            onAccountConfirmed = viewModel::setAccountConfirmed,
            onScanned = viewModel::onScanned,
            onRescan = viewModel::rescan,
            onApprove = { scope.launch { viewModel.approve { authenticator.authenticate(AuthPromptText("Approvare?")) } } },
          ),
        )
      }
    }
    rule.waitForIdle()
  }

  @Test
  fun conIlCodiceDigitatoELAccountConfermatoApprovaDopoLoSblocco() {
    show()

    // Senza account confermato e senza codice il tasto non fa niente.
    rule.onNodeWithText("Approva questo dispositivo").performScrollTo().performClick()
    rule.waitForIdle()
    assertThat(registry.approvals).isEmpty()
    assertThat(authenticator.prompts).isEqualTo(0)

    rule.onNodeWithText("L’account «nonna» è giusto").performScrollTo().performClick()
    rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("k7qd 2m9x fa4t 1b8c")
    rule.waitForIdle()
    rule.onNodeWithText("K7QD-2M9X-FA4T-1B8C").assertExists()

    rule.onNodeWithText("Approva questo dispositivo").performScrollTo().performClick()
    rule.waitForIdle()

    assertThat(authenticator.prompts).isEqualTo(1)
    assertThat(registry.approvals).containsExactly(Samples.REQUEST_ID)
    rule.onNodeWithText("Il dispositivo fa parte della famiglia").assertExists()
  }

  @Test
  fun conLaScansioneDelQrDellaChiaveApprovaSenzaDigitareNiente() {
    val key = Samples.publicKey()
    codec.known["MCGK1:chiave"] = DeviceKeyQr("famiglia", Samples.REQUEST_ID, key.encoded)
    show()

    rule.onNodeWithText("Scansiona il QR").performScrollTo().performClick()
    rule.waitForIdle()
    // Il lettore vero e' di core-qr (P5): qui il QR arriva come se lo scanner l'avesse letto.
    rule.runOnIdle { viewModel.onScanned("MCGK1:chiave") }
    rule.waitForIdle()
    rule.onNodeWithText("QR di questo dispositivo").assertExists()

    rule.onNodeWithText("L’account «nonna» è giusto").performScrollTo().performClick()
    rule.onNodeWithText("Approva questo dispositivo").performScrollTo().performClick()
    rule.waitForIdle()

    assertThat(authenticator.prompts).isEqualTo(1)
    assertThat(registry.approvals).containsExactly(Samples.REQUEST_ID)
    assertThat(viewModel.state.value.mode).isEqualTo(ProofMode.SCAN)
  }

  @Test
  fun unQrDiUnAltroDispositivoMostraLAvvisoENonApprova() {
    codec.known["MCGK1:altro"] = DeviceKeyQr("famiglia", "id-di-un-altro", Samples.publicKey().encoded)
    show()

    rule.onNodeWithText("Scansiona il QR").performScrollTo().performClick()
    rule.runOnIdle { viewModel.onScanned("MCGK1:altro") }
    rule.waitForIdle()
    rule.onNodeWithText("QR di un altro dispositivo").assertExists()

    rule.onNodeWithText("L’account «nonna» è giusto").performScrollTo().performClick()
    rule.onNodeWithText("Approva questo dispositivo").performScrollTo().performClick()
    rule.waitForIdle()
    assertThat(registry.approvals).isEmpty()
    assertThat(authenticator.prompts).isEqualTo(0)
  }
}
