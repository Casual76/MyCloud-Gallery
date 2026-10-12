package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.ExcludedFolder
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ogni percorso, con i fake, dall'inizio alla fine, **toccando le schermate vere**: si trova un bottone dal suo
 * testo, si scrive nei campi, si controlla cosa arriva al coordinatore, all'ingresso e al recupero.
 *
 * Il coordinatore finto avanza da solo a ogni input; dove un input non fa avanzare il passo (la conferma di "casa")
 * si mette in coda l'esito con `scripted`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE_TALL)
internal class OnboardingFlowTest : OnboardingComposeBase() {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

  private val nas = NasAddress("192.168.178.58", "WDMyCloudEX2Ultra")
  private val strongPassword = "una-password-molto-lunga"

  private class Harness(val env: TestEnv, val scanner: FakeScanner = FakeScanner()) {
    var done = 0
    var paused = 0
    val viewModel = env.viewModel()
  }

  private fun start(env: TestEnv = TestEnv(), resumeStep: OnboardingStep? = null): Harness {
    val harness = Harness(env)
    show {
      OnboardingHost(
        viewModel = harness.viewModel,
        platform = env.platform,
        resumeStep = resumeStep,
        onDone = { harness.done++ },
        onPause = { harness.paused++ },
        scanner = harness.scanner.slot,
      )
    }
    return harness
  }

  /** Un bottone: il nodo che ha il testo e un'azione di tocco. */
  private fun click(text: String) {
    composeRule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    composeRule.waitForIdle()
  }

  /** Una riga di un elenco: il tocco va sul suo testo (il gesto arriva alla riga che lo contiene). */
  private fun clickRow(text: String) {
    composeRule.onAllNodes(hasText(text)).onFirst().performClick()
    composeRule.waitForIdle()
  }

  private fun type(index: Int, text: String) {
    composeRule.onAllNodes(hasSetTextAction())[index].performTextInput(text)
    composeRule.waitForIdle()
  }

  /** Il testo e' sulla pagina (le intestazioni di sezione sono in maiuscolo, e il titolo compare anche nella barra). */
  private fun assertVisible(text: String) {
    composeRule.onAllNodes(hasText(text, ignoreCase = true)).onFirst().assertIsDisplayed()
  }

  private fun home() = HomeCandidate("wifi-1", setOf("fd12:3456:789a::/48"), "192.168.178.1", "fritz.box", "192.168.178.0/24")

  private fun envAtStep(step: OnboardingStep, path: OnboardingPath = OnboardingPath.FOUND_FAMILY): TestEnv {
    val plan = dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator.planFor(path)
    return TestEnv(OnboardingProgress.START.copy(path = path, plan = plan, current = step))
  }

  // --- Crea la famiglia -----------------------------------------------------------------------------

  @Test
  fun creaLaFamigliaDaiPrimiPassiAlRiepilogo() {
    val env = TestEnv()
    env.coordinator.candidate = home()
    env.coordinator.discovered = listOf(DiscoveredNas("WDMyCloudEX2Ultra", nas))
    env.coordinator.areas = listOf(
      AreaCandidate("marta", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = false),
      AreaCandidate("Public", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = true, hasFamily = false),
    )
    env.coordinator.folders = FolderProposal(listOf(ExcludedFolder("Musica", ExclusionReason.MUSIC)), scanned = 100, truncated = false)
    env.health.health = BackgroundHealth.UNKNOWN.copy(ignoringBatteryOpt = false)
    val h = start(env)

    // 1. Benvenuto
    assertVisible(str(R.string.welcome_title))
    clickRow(str(R.string.welcome_path_found))
    // 2. Indirizzo del NAS: casa, poi il NAS trovato
    assertVisible(str(R.string.address_home_ask))
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
    click(str(R.string.address_home_yes))
    clickRow("WDMyCloudEX2Ultra")
    click(str(R.string.address_check))
    // 3. Account
    assertVisible(str(R.string.account_one_attempt_title))
    type(0, "marta")
    type(1, strongPassword)
    click(str(R.string.account_submit))
    // 4. Aree
    assertVisible(str(R.string.areas_section_common))
    composeRule.onAllNodesWithText("Public")[0].performClick()
    composeRule.onAllNodesWithText("marta")[1].performClick()
    composeRule.waitForIdle()
    click(str(R.string.action_continue))
    // 5. Cartelle: si salta
    assertVisible(str(R.string.folders_section))
    click(str(R.string.action_skip_step))
    // 6-8. Sorgenti, ruoli, preset
    assertVisible(str(R.string.sources_section))
    click(str(R.string.action_continue))
    assertVisible(str(R.string.roles_section))
    click(str(R.string.action_continue))
    assertVisible(str(R.string.preset_section))
    click(str(R.string.action_continue))
    // 9. Permessi
    assertVisible(str(R.string.permissions_section))
    click(str(R.string.action_continue))
    // 10-11. Batteria e VPN: si saltano
    assertVisible(str(R.string.battery_section))
    click(str(R.string.action_skip_step))
    assertVisible(str(R.string.vpn_guide_section))
    click(str(R.string.action_skip_step))
    // 12. Chiave e kit
    assertVisible(str(R.string.key_what_key))
    click(str(R.string.key_generate))
    assertVisible(str(R.string.key_code_paper_title))
    clickRow(str(R.string.key_code_ack))
    click(str(R.string.key_code_written))
    type(0, "ABCD".repeat(7))
    click(str(R.string.key_retype_submit))
    // 13. Avviso sul backup esterno, con la conferma del kit
    assertVisible(str(R.string.banner_kit_verified_title))
    click(str(R.string.action_continue))
    // 14. Riepilogo
    assertVisible(str(R.string.summary_ready))
    assertVisible(str(R.string.summary_section_pending))
    click(str(R.string.summary_finish))

    assertThat(h.done).isEqualTo(1)
    assertThat(env.coordinator.inputs).containsExactly(
      "ChoosePath",
      "ConfirmHome",
      "NasAddressChosen",
      "Credentials",
      "Areas",
      "BackupSources",
      "Roles",
      "PresetChoice",
      "PermissionsReviewed",
      "RecoveryCodeTyped",
      "SecurityNotice",
      "Finish",
    ).inOrder()
    assertThat(env.coordinator.progress.value.skipped).containsAtLeast(OnboardingStep.FOLDERS, OnboardingStep.BATTERY, OnboardingStep.VPN)
  }

  @Test
  fun unaPasswordSbagliataSiFermaESiRiscrive() {
    val env = envAtStep(OnboardingStep.ACCOUNT)
    val h = start(env)
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.AUTH_FAILED)
    type(0, "marta")
    type(1, strongPassword)
    click(str(R.string.account_submit))
    assertVisible(str(R.string.problem_auth_failed_title))
    // un solo tentativo: il bottone resta spento finche' la password non e' riscritta
    composeRule.onNode(hasText(str(R.string.account_submit)) and hasClickAction()).assertIsNotEnabled()
    type(1, strongPassword)
    composeRule.onNode(hasText(str(R.string.account_submit)) and hasClickAction()).assertIsEnabled()
    assertThat(env.coordinator.inputs).containsExactly("Credentials")
    assertThat(h.done).isEqualTo(0)
  }

  @Test
  fun lAccountAdminNonParteNemmenoCome() {
    val env = envAtStep(OnboardingStep.ACCOUNT)
    start(env)
    type(0, "Admin")
    type(1, strongPassword)
    assertVisible(str(R.string.account_user_admin))
    composeRule.onNode(hasText(str(R.string.account_submit)) and hasClickAction()).assertIsNotEnabled()
    assertThat(env.coordinator.inputs).isEmpty()
  }

  @Test
  fun ilNasSpentoPermetteDiContinuarePiuTardi() {
    val env = envAtStep(OnboardingStep.NAS_ADDRESS)
    env.coordinator.candidate = home()
    val h = start(env)
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
    click(str(R.string.address_home_yes))
    type(0, "192.168.178.99")
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.NAS_NOT_FOUND)
    click(str(R.string.address_check))
    assertVisible(str(R.string.problem_nas_not_found_title))
    click(str(R.string.action_continue_later))
    assertThat(h.paused).isEqualTo(1)
  }

  @Test
  fun senzaWifiSiSpiegaEChiedeDiRiprovare() {
    val env = envAtStep(OnboardingStep.NAS_ADDRESS)
    env.coordinator.candidate = null
    start(env)
    assertVisible(str(R.string.problem_not_on_wifi_title))
    env.coordinator.candidate = home()
    click(str(R.string.action_retry))
    assertVisible(str(R.string.address_home_ask))
  }

  @Test
  fun inUnaReteSenzaUlaNonSiPuoConfermareCasa() {
    val env = envAtStep(OnboardingStep.NAS_ADDRESS)
    env.coordinator.candidate = home().copy(ulaPrefixes = emptySet())
    start(env)
    assertVisible(str(R.string.problem_no_ula_title))
    assertThat(composeRule.onAllNodesWithText(str(R.string.address_home_yes)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun quiCeGiaUnaFamigliaProponeDiEntrareODiRecuperare() {
    val env = envAtStep(OnboardingStep.AREAS)
    env.coordinator.areas = listOf(
      AreaCandidate("marta", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = false),
      AreaCandidate("Public", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = true),
    )
    start(env)
    composeRule.onAllNodesWithText("Public")[0].performClick()
    composeRule.onAllNodesWithText("marta")[1].performClick()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.FAMILY_ALREADY_EXISTS)
    click(str(R.string.action_continue))
    assertVisible(str(R.string.problem_family_exists_title))
    click(str(R.string.action_recover_admin))
    assertThat(env.coordinator.progress.value.path).isEqualTo(OnboardingPath.RECOVER_ADMIN)
  }

  @Test
  fun senzaNessunaShareAccessibileSiDiceCosaChiedere() {
    val env = envAtStep(OnboardingStep.AREAS)
    env.coordinator.areas = listOf(AreaCandidate("marta", NasResult.Ok(ShareAccess.DENIED), isPublic = false, hasFamily = false))
    start(env)
    assertVisible(str(R.string.problem_no_share_title))
  }

  @Test
  fun unCodiceDiRecuperoSbagliatoSiPuoRivedereERiscrivere() {
    val env = envAtStep(OnboardingStep.FAMILY_KEY)
    start(env)
    click(str(R.string.key_generate))
    clickRow(str(R.string.key_code_ack))
    click(str(R.string.key_code_written))
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.RECOVERY_CODE_MISMATCH)
    type(0, "ABCD".repeat(7))
    click(str(R.string.key_retype_submit))
    assertVisible(str(R.string.problem_code_mismatch_title))
    click(str(R.string.action_back_to_code))
    assertVisible(str(R.string.key_code_paper_title))
  }

  @Test
  fun daDaCompletareSiRiapreUnSoloPassoESiEsce() {
    val skipped = OnboardingProgress.START.copy(current = OnboardingStep.SUMMARY, skipped = setOf(OnboardingStep.BATTERY))
    val env = TestEnv(skipped)
    val h = start(env, resumeStep = OnboardingStep.BATTERY)
    h.viewModel.resume(OnboardingStep.BATTERY)
    composeRule.waitForIdle()
    assertVisible(str(R.string.battery_section))
    click(str(R.string.action_continue))
    assertThat(env.coordinator.inputs).containsExactly("BatteryReviewed")
    // finito il passo riaperto si esce: non si prosegue con quello dopo
    assertThat(h.done).isEqualTo(1)
  }

  // --- Entra con il QR e senza QR -------------------------------------------------------------------

  @Test
  fun entraConIlQrFinoAllAttesaDellApprovazione() {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.NeedsPassword
    env.join.steps += JoinStep.NeedsName("Tablet — profilo Mamma")
    env.join.steps += JoinStep.AwaitingApproval("K7QD-2M9X-FA4T", "MCGK1:chiave")
    val h = start(env)
    h.scanner.next = "MCG1:valido"
    clickRow(str(R.string.welcome_path_join_qr))
    assertVisible(str(R.string.scan_tip_title))
    click(SCAN_BUTTON)
    // riepilogo dell'invito, prima di toccare la rete
    assertVisible("marta")
    assertVisible("Tablet — profilo Mamma")
    click(str(R.string.action_continue))
    // password: l'unico campo
    assertVisible(str(R.string.join_password_title))
    type(0, strongPassword)
    click(str(R.string.join_password_submit))
    // nome del dispositivo, proposto
    assertVisible(str(R.string.join_name_title))
    composeRule.onNode(hasSetTextAction()).assertTextContains("Tablet — profilo Mamma")
    click(str(R.string.join_name_submit))
    // codice grande e QR della chiave
    assertVisible(str(R.string.awaiting_title))
    composeRule.onNodeWithContentDescription(str(R.string.code_group_spoken, 1, 3, "K, 7, Q, D")).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(str(R.string.awaiting_qr_description)).assertIsDisplayed()
    assertThat(env.join.calls).containsExactly("start", "authenticate", "register").inOrder()
  }

  @Test
  fun unQrDiWireGuardEUnaPasswordNonEUnInvito() {
    val env = TestEnv()
    val h = start(env)
    h.scanner.next = "[Interface]\nPrivateKey = segreta"
    clickRow(str(R.string.welcome_path_join_qr))
    click(SCAN_BUTTON)
    assertVisible(str(R.string.scan_reject_wireguard_title))
  }

  @Test
  fun unInvitoDiUnAltraFamigliaSiRifiuta() {
    val env = TestEnv()
    val h = start(env)
    h.scanner.next = "MCG1:rovinato"
    clickRow(str(R.string.welcome_path_join_qr))
    click(SCAN_BUTTON)
    assertVisible(str(R.string.scan_reject_malformed_title))
  }

  @Test
  fun nonACasaNonChiedeLaPassword() {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.Failed(OnboardingProblem.NOT_AT_HOME)
    val h = start(env)
    h.scanner.next = "MCG1:valido"
    clickRow(str(R.string.welcome_path_join_qr))
    click(SCAN_BUTTON)
    click(str(R.string.action_continue))
    assertVisible(str(R.string.problem_not_at_home_title))
    assertThat(composeRule.onAllNodesWithText(str(R.string.join_password_title)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun entraSenzaQrScrivendoIDati() {
    val env = TestEnv()
    env.join.steps += JoinStep.NeedsPassword
    start(env)
    clickRow(str(R.string.welcome_path_join_manual))
    assertVisible(str(R.string.manual_intro_title))
    // indirizzo, utente, famiglia, impronta, nome
    type(0, "192.168.178.58")
    type(1, "marta")
    type(2, "famiglia-rossi")
    type(3, "K7QD2M9XFA4T")
    type(4, "Tablet")
    click(str(R.string.action_continue))
    assertVisible(str(R.string.join_password_title))
    // l'avviso sulla versione minima del registro compare, e dice di aggiungere lo stato della famiglia
    assertVisible(str(R.string.warning_no_min_version_title))
  }

  @Test
  fun lAdminApprovaEIlDispositivoEntra() {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.Approved
    val h = start(env)
    h.scanner.next = "MCG1:valido"
    clickRow(str(R.string.welcome_path_join_qr))
    click(SCAN_BUTTON)
    click(str(R.string.action_continue))
    assertVisible(str(R.string.approved_title))
    click(str(R.string.summary_finish))
    assertThat(h.done).isEqualTo(1)
  }

  // --- Recupera l'admin e secondo admin -------------------------------------------------------------

  private fun recoveryEnv(): TestEnv {
    val env = TestEnv()
    env.coordinator.candidate = home()
    env.coordinator.discovered = listOf(DiscoveredNas("WDMyCloudEX2Ultra", nas))
    env.recovery.kits = NasResult.Ok(listOf(RecoveryKitRef("marta", "kit-2026-10-12", createdAt = 1_760_227_200_000L)))
    return env
  }

  private fun toRecoveryKits(path: String, env: TestEnv) {
    start(env)
    clickRow(path)
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
    click(str(R.string.address_home_yes))
    clickRow("WDMyCloudEX2Ultra")
    click(str(R.string.address_check))
    type(0, "marta")
    type(1, strongPassword)
    click(str(R.string.account_submit))
  }

  @Test
  fun recuperaLAdminChiedeIlVecchioAdminPoiIlCodice() {
    val env = recoveryEnv()
    env.recovery.outcome = RecoveryOutcome.Recovered
    toRecoveryKits(str(R.string.welcome_path_recover), env)
    assertVisible(str(R.string.recovery_kits_section))
    click(str(R.string.action_continue))
    // cosa fare del vecchio admin: nessuna scelta preselezionata
    assertVisible(str(R.string.recovery_fate_section))
    composeRule.onNode(hasText(str(R.string.action_continue)) and hasClickAction()).assertIsNotEnabled()
    clickRow(str(R.string.recovery_fate_lost))
    click(str(R.string.action_continue))
    // il codice
    assertVisible(str(R.string.recovery_code_hint))
    type(0, "K7QD2M9XFA4T")
    click(str(R.string.recovery_code_submit))
    assertVisible(str(R.string.recovery_done_key))
    assertVisible(str(R.string.recovery_done_old_lost))
  }

  @Test
  fun unCodiceSbagliatoDelKitSiRiprova() {
    val env = recoveryEnv()
    env.recovery.outcome = RecoveryOutcome.WrongCode
    toRecoveryKits(str(R.string.welcome_path_recover), env)
    click(str(R.string.action_continue))
    clickRow(str(R.string.recovery_fate_revoked))
    click(str(R.string.action_continue))
    type(0, "SBAGLIATO123")
    click(str(R.string.recovery_code_submit))
    assertVisible(str(R.string.recovery_wrong_title))
    env.recovery.outcome = RecoveryOutcome.Recovered
    click(str(R.string.recovery_code_submit))
    assertVisible(str(R.string.recovery_done_title))
  }

  @Test
  fun ilSecondoAdminSaltaLaScelta() {
    val env = recoveryEnv()
    env.recovery.outcome = RecoveryOutcome.Recovered
    toRecoveryKits(str(R.string.welcome_path_second_admin), env)
    click(str(R.string.action_continue))
    assertVisible(str(R.string.recovery_code_hint))
    type(0, "K7QD2M9XFA4T")
    click(str(R.string.recovery_code_submit))
    assertVisible(str(R.string.recovery_done_title_second))
  }

  @Test
  fun senzaKitSiDiceECheNonCeNeSono() {
    val env = recoveryEnv()
    env.recovery.kits = NasResult.Ok(emptyList())
    toRecoveryKits(str(R.string.welcome_path_recover), env)
    assertVisible(str(R.string.recovery_none_title))
    composeRule.onNode(hasText(str(R.string.action_continue)) and hasClickAction()).assertIsNotEnabled()
  }
}
