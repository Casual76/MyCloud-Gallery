package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.SessionInfo
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.account.AccountUi
import dev.pampa.mycloudgallery.feature.settings.account.AddressActions
import dev.pampa.mycloudgallery.feature.settings.account.AddressPhase
import dev.pampa.mycloudgallery.feature.settings.account.AddressScreen
import dev.pampa.mycloudgallery.feature.settings.account.AddressUiState
import dev.pampa.mycloudgallery.feature.settings.account.NasAccountLinks
import dev.pampa.mycloudgallery.feature.settings.account.NasAccountScreen
import dev.pampa.mycloudgallery.feature.settings.account.NasAccountUiState
import dev.pampa.mycloudgallery.feature.settings.account.PasswordActions
import dev.pampa.mycloudgallery.feature.settings.account.PasswordPhase
import dev.pampa.mycloudgallery.feature.settings.account.PasswordScreen
import dev.pampa.mycloudgallery.feature.settings.account.PasswordStages
import dev.pampa.mycloudgallery.feature.settings.account.PasswordUiState
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalActions
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalPhase
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalUiState
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailActions
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailScreen
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailUiState
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceMapper
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceUi
import dev.pampa.mycloudgallery.feature.settings.devices.DevicesScreen
import dev.pampa.mycloudgallery.feature.settings.devices.DevicesUiState
import dev.pampa.mycloudgallery.feature.settings.devices.GuideStep
import dev.pampa.mycloudgallery.feature.settings.devices.LostStage
import dev.pampa.mycloudgallery.feature.settings.devices.PendingChange
import dev.pampa.mycloudgallery.feature.settings.devices.ProofMode
import dev.pampa.mycloudgallery.feature.settings.devices.RequestUi
import dev.pampa.mycloudgallery.feature.settings.devices.ScanState
import dev.pampa.mycloudgallery.feature.settings.family.FamilyLinks
import dev.pampa.mycloudgallery.feature.settings.family.FamilyResetScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyStateScreen
import dev.pampa.mycloudgallery.feature.settings.family.FamilyStateUi
import dev.pampa.mycloudgallery.feature.settings.family.FamilyUiState
import dev.pampa.mycloudgallery.feature.settings.family.AdminRecoveryScreen
import dev.pampa.mycloudgallery.feature.settings.family.InviteActions
import dev.pampa.mycloudgallery.feature.settings.family.InvitePhase
import dev.pampa.mycloudgallery.feature.settings.family.InviteScreen
import dev.pampa.mycloudgallery.feature.settings.family.InviteSummary
import dev.pampa.mycloudgallery.feature.settings.family.InviteUiState
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryActions
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryKitScreen
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryMode
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryStage
import dev.pampa.mycloudgallery.feature.settings.family.RecoveryUiState
import dev.pampa.mycloudgallery.feature.settings.family.ResetStage
import dev.pampa.mycloudgallery.feature.settings.family.ResetUiState
import dev.pampa.mycloudgallery.feature.settings.network.CaptureUi
import dev.pampa.mycloudgallery.feature.settings.network.NetworkActions
import dev.pampa.mycloudgallery.feature.settings.network.NetworkScreen
import dev.pampa.mycloudgallery.feature.settings.network.NetworkUiState
import dev.pampa.mycloudgallery.feature.settings.network.RouteUi
import dev.pampa.mycloudgallery.feature.settings.network.VpnCommandUi
import dev.pampa.mycloudgallery.feature.settings.security.BackupNoticeScreen
import dev.pampa.mycloudgallery.feature.settings.security.SecurityActions
import dev.pampa.mycloudgallery.feature.settings.security.SecurityScreen
import dev.pampa.mycloudgallery.feature.settings.security.SecurityUiState
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * Una schermata in un suo stato, pronta da fotografare. [sheet] = c'e' un foglio di conferma aperto (si
 * fotografa in una finestra piu' bassa, perche' il foglio sale dal fondo); [large] = si fotografa anche con il
 * testo grande.
 */
internal class Shot(
  val name: String,
  val sheet: Boolean = false,
  val large: Boolean = false,
  val content: @Composable () -> Unit,
) {
  override fun toString(): String = name
}

/** Tutte le schermate e gli stati delle Impostazioni (P7): la lista da cui nascono i PNG in `docs/screenshots/2-P7/`. */
internal object ShotCatalog {

  private val now = Samples.NOW

  private fun devices(trust: TrustStatus, views: List<dev.pampa.mycloudgallery.core.family.contract.DeviceView>, requests: List<dev.pampa.mycloudgallery.core.family.contract.PendingRequestView> = emptyList()) =
    DeviceMapper.list(trust, views, requests, now)

  private fun ui(id: String, views: List<dev.pampa.mycloudgallery.core.family.contract.DeviceView> = Samples.familyAsAdmin() + Samples.reinstalled): DeviceUi =
    DeviceMapper.devices(views, now).first { it.id == id }

  private fun request(
    r: dev.pampa.mycloudgallery.core.family.contract.PendingRequestView = Samples.request,
    views: List<dev.pampa.mycloudgallery.core.family.contract.DeviceView> = Samples.familyAsAdmin(),
  ): RequestUi = DeviceMapper.requests(listOf(r), views, now).single()

  private fun detail(
    id: String,
    admin: Boolean = true,
    canManage: Boolean = admin,
    canReportOwn: Boolean = false,
    views: List<dev.pampa.mycloudgallery.core.family.contract.DeviceView> = Samples.familyAsAdmin() + Samples.reinstalled,
    stage: LostStage = LostStage.NONE,
    block: (DeviceDetailUiState) -> DeviceDetailUiState = { it },
  ) = block(
    DeviceDetailUiState(
      device = ui(id, views),
      trust = TrustStatus.Approved(admin),
      canManage = canManage,
      canReportOwn = canReportOwn,
      lostStage = stage,
    ),
  )

  private fun nasSession(via: Via = Via.HOME_LAN, encrypted: Boolean = true) = NasConnState.Ready(
    SessionInfo(SmbDialect.SMB_3_1_1, encrypted, "AES-128-GCM", signingRequired = true, serverGuid = "guid", nasTimeOffsetMs = 0L, via = via),
  )

  private val account = AccountUi(user = "test2", address = "192.168.178.58", serverName = "wdmycloud", guidPinned = true)
  private val knownHome = HomeFingerprint(setOf("fd12:3456:789a::/48"), "192.168.178.1", "fritz.box", capturedAt = now - 30.days.inWholeMilliseconds)

  private fun network(
    snapshot: dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot = Snapshots.home(),
    route: RouteUi = RouteUi.Allowed(Via.HOME_LAN),
    fingerprint: HomeFingerprint? = knownHome,
    capability: VpnCapability = VpnCapability.READY,
    automation: VpnAutomationStatus = VpnAutomationStatus.Idle,
    capture: CaptureUi = CaptureUi.Idle,
    command: VpnCommandUi = VpnCommandUi.Idle,
    canOpenWireGuard: Boolean = true,
  ) = NetworkUiState(snapshot, route, fingerprint, capability, automation, capture, command, canOpenWireGuard, now)

  private val stagesOk = PasswordStages.sorted().map { StageResult(it, StageOutcome.PASSED, 5.milliseconds) }
  private val stagesFailed = listOf(
    StageResult(Stage.ROUTE, StageOutcome.PASSED, 1.milliseconds),
    StageResult(Stage.TCP, StageOutcome.PASSED, 3.milliseconds),
    StageResult(Stage.NEGOTIATE, StageOutcome.PASSED, 9.milliseconds),
    StageResult(Stage.GUID, StageOutcome.PASSED, 1.milliseconds),
    StageResult(Stage.AUTHENTICATE, StageOutcome.FAILED, 40.milliseconds, NasError.Auth(AuthBlockReason.AUTH_FAILED)),
    StageResult(Stage.SECURITY_CHECK, StageOutcome.NOT_RUN, 0.milliseconds),
  )

  private val kits = listOf(
    RecoveryKitRef("alessio", "recovery-2026-03.mcgkit", createdAt = null),
    RecoveryKitRef("alessio", "recovery-2026-09.mcgkit", createdAt = null),
  )

  val all: List<Shot> = listOf(
    // --- Pagina principale ---------------------------------------------------------------------------
    Shot("home-admin", large = true) {
      SettingsScreen(
        SettingsUiState(TrustStatus.Approved(admin = true), deviceCount = 4, pendingRequests = 1, pendingSteps = listOf(OnboardingStep.BATTERY, OnboardingStep.VPN)),
        onBack = {}, openDiagnostics = {}, resumeStep = {},
      )
    },
    Shot("home-familiare") {
      SettingsScreen(SettingsUiState(TrustStatus.Approved(admin = false), 4, 0, emptyList()), onBack = {}, openDiagnostics = {}, resumeStep = {})
    },
    Shot("home-registro-vecchio") {
      SettingsScreen(
        SettingsUiState(TrustStatus.Stale(StaleReason.NOT_REFRESHED), 3, 0, listOf(OnboardingStep.FOLDERS)),
        onBack = {}, openDiagnostics = {}, resumeStep = {},
      )
    },
    Shot("home-nessuna-famiglia") {
      SettingsScreen(SettingsUiState(TrustStatus.NotConfigured, 0, 0, emptyList()), onBack = {}, openDiagnostics = {}, resumeStep = {})
    },

    // --- Dispositivi: elenco -------------------------------------------------------------------------
    Shot("dispositivi-admin", large = true) {
      DevicesScreen(
        devices(TrustStatus.Approved(admin = true), Samples.familyAsAdmin(), listOf(Samples.request, Samples.expiredRequest)),
        onBack = {}, onOpenRequest = {}, onOpenDevice = {}, onConvertReports = {},
      )
    },
    Shot("dispositivi-sosia") {
      DevicesScreen(
        devices(TrustStatus.Approved(admin = true), Samples.familyAsAdmin() + Samples.reinstalled, listOf(Samples.lookAlikeRequest))
          .copy(feedback = AdminActionResult.Done.toFeedback()),
        onBack = {}, onOpenRequest = {}, onOpenDevice = {}, onConvertReports = {},
      )
    },
    Shot("dispositivi-familiare") {
      DevicesScreen(devices(TrustStatus.Approved(admin = false), Samples.familyAsMember()), onBack = {}, onOpenRequest = {}, onOpenDevice = {}, onConvertReports = {})
    },
    Shot("dispositivi-registro-vecchio") {
      DevicesScreen(
        devices(TrustStatus.Stale(StaleReason.LISTING_TRUNCATED), Samples.familyAsMember()),
        onBack = {}, onOpenRequest = {}, onOpenDevice = {}, onConvertReports = {},
      )
    },
    Shot("dispositivi-nessuna-famiglia") {
      DevicesScreen(DevicesUiState.EMPTY, onBack = {}, onOpenRequest = {}, onOpenDevice = {}, onConvertReports = {})
    },

    // --- Dispositivo: dettaglio e segnala perso ------------------------------------------------------
    Shot("dispositivo-admin", large = true) {
      DeviceDetailScreen(detail(Samples.TABLET_MAMMA), DeviceDetailActions.None)
    },
    Shot("dispositivo-questo") {
      DeviceDetailScreen(detail(Samples.ADMIN_PHONE, canManage = false), DeviceDetailActions.None)
    },
    Shot("dispositivo-sosia") {
      DeviceDetailScreen(detail(Samples.REINSTALLED), DeviceDetailActions.None)
    },
    Shot("dispositivo-proprietario") {
      DeviceDetailScreen(
        detail(Samples.TABLET_MAMMA, admin = false, canManage = false, canReportOwn = true, views = Samples.familyAsMember()),
        DeviceDetailActions.None,
      )
    },
    Shot("dispositivo-perso") {
      DeviceDetailScreen(detail(Samples.LOST_PHONE, canManage = false), DeviceDetailActions.None)
    },
    Shot("dispositivo-non-trovato") {
      DeviceDetailScreen(DeviceDetailUiState(device = null, trust = TrustStatus.Approved(true), canManage = false, canReportOwn = false), DeviceDetailActions.None)
    },
    Shot("perso-conferma-1", sheet = true) {
      DeviceDetailScreen(detail(Samples.TABLET_MAMMA, stage = LostStage.FIRST_CONFIRM), DeviceDetailActions.None)
    },
    Shot("perso-conferma-2", sheet = true) {
      DeviceDetailScreen(detail(Samples.TABLET_MAMMA, stage = LostStage.SECOND_CONFIRM), DeviceDetailActions.None)
    },
    Shot("perso-conferma-2-presa-d-atto", sheet = true) {
      DeviceDetailScreen(detail(Samples.TABLET_MAMMA, stage = LostStage.SECOND_CONFIRM) { it.copy(acknowledged = true) }, DeviceDetailActions.None)
    },
    Shot("revoca-conferma", sheet = true) {
      DeviceDetailScreen(detail(Samples.PHONE_ANNA) { it.copy(pendingChange = PendingChange.REVOKE) }, DeviceDetailActions.None)
    },
    Shot("dismetti-conferma", sheet = true) {
      DeviceDetailScreen(detail(Samples.PHONE_ANNA) { it.copy(pendingChange = PendingChange.RETIRE) }, DeviceDetailActions.None)
    },
    Shot("perso-guida-passo-1", large = true) {
      DeviceDetailScreen(
        detail(Samples.LOST_PHONE, canManage = false, stage = LostStage.GUIDE) { it.copy(guideDone = setOf(GuideStep.REPORT)) },
        DeviceDetailActions.None,
      )
    },
    Shot("perso-guida-passo-3") {
      DeviceDetailScreen(
        detail(Samples.LOST_PHONE, canManage = false, stage = LostStage.GUIDE) { it.copy(guideDone = setOf(GuideStep.REPORT, GuideStep.REVOKE_PEER)) },
        DeviceDetailActions.None,
      )
    },
    Shot("perso-guida-proprietario") {
      DeviceDetailScreen(
        detail(Samples.TABLET_MAMMA, admin = false, canManage = false, canReportOwn = true, views = Samples.familyAsMember(), stage = LostStage.GUIDE) {
          it.copy(guideDone = setOf(GuideStep.REPORT), reportedByOwner = true)
        },
        DeviceDetailActions.None,
      )
    },

    // --- Approvazione di un dispositivo nuovo --------------------------------------------------------
    Shot("approvazione-vuota", large = true) {
      ApprovalScreen(ApprovalUiState(request(), isAdmin = true), ApprovalActions.None)
    },
    Shot("approvazione-codice-pronto") {
      ApprovalScreen(
        ApprovalUiState(request(), isAdmin = true, code = "K7QD-2M9X-FA4T-1B8C", accountConfirmed = true),
        ApprovalActions.None,
      )
    },
    Shot("approvazione-codice-errato") {
      ApprovalScreen(
        ApprovalUiState(
          request(), isAdmin = true, code = "K7QD-2M9X-FA4T-1B8D", accountConfirmed = true,
          feedback = AdminActionResult.ProofMismatch.toFeedback(),
        ),
        ApprovalActions.None,
      )
    },
    Shot("approvazione-scansione") {
      ApprovalScreen(ApprovalUiState(request(), isAdmin = true, mode = ProofMode.SCAN), ApprovalActions.None)
    },
    Shot("approvazione-scansione-ok") {
      ApprovalScreen(
        ApprovalUiState(request(), isAdmin = true, mode = ProofMode.SCAN, scan = ScanState.MATCHING, accountConfirmed = true),
        ApprovalActions.None,
      )
    },
    Shot("approvazione-scansione-altro-dispositivo") {
      ApprovalScreen(
        ApprovalUiState(request(), isAdmin = true, mode = ProofMode.SCAN, scan = ScanState.WRONG_DEVICE, accountConfirmed = true),
        ApprovalActions.None,
      )
    },
    Shot("approvazione-scaduta") {
      ApprovalScreen(ApprovalUiState(request(Samples.expiredRequest), isAdmin = true), ApprovalActions.None)
    },
    Shot("approvazione-sosia") {
      ApprovalScreen(ApprovalUiState(request(Samples.lookAlikeRequest), isAdmin = true), ApprovalActions.None)
    },
    Shot("approvazione-non-admin") {
      ApprovalScreen(ApprovalUiState(request(), isAdmin = false), ApprovalActions.None)
    },
    Shot("approvazione-registro-cambiato") {
      ApprovalScreen(
        ApprovalUiState(
          request(), isAdmin = true, code = "K7QD-2M9X-FA4T-1B8C", accountConfirmed = true,
          feedback = AdminActionResult.NewerExists.toFeedback(),
        ),
        ApprovalActions.None,
      )
    },
    Shot("approvazione-fatta") {
      ApprovalScreen(ApprovalUiState(request(), isAdmin = true, phase = ApprovalPhase.DONE), ApprovalActions.None)
    },
    Shot("approvazione-non-trovata") {
      ApprovalScreen(ApprovalUiState(request = null, isAdmin = true), ApprovalActions.None)
    },

    // --- Famiglia ------------------------------------------------------------------------------------
    Shot("famiglia-admin", large = true) {
      FamilyScreen(FamilyUiState(TrustStatus.Approved(admin = true), deviceCount = 4, adminCount = 1), FamilyLinks.None)
    },
    Shot("famiglia-familiare") {
      FamilyScreen(FamilyUiState(TrustStatus.Approved(admin = false), deviceCount = 4, adminCount = 1), FamilyLinks.None)
    },
    Shot("famiglia-nessuna") {
      FamilyScreen(FamilyUiState(TrustStatus.NotConfigured, 0, 0), FamilyLinks.None)
    },
    Shot("invito-modulo") {
      InviteScreen(
        InviteUiState(isAdmin = true, user = "anna", deviceName = "Telefono di Anna", preset = Preset.BALANCED),
        InviteActions.None,
      )
    },
    Shot("invito-errori") {
      InviteScreen(
        InviteUiState(
          isAdmin = true, user = "Admin", deviceName = "", privateShare = "../cartella",
          userError = dev.pampa.mycloudgallery.feature.settings.R.string.invite_error_admin,
          nameError = dev.pampa.mycloudgallery.feature.settings.R.string.invite_error_name,
          shareError = dev.pampa.mycloudgallery.feature.settings.R.string.invite_error_share,
        ),
        InviteActions.None,
      )
    },
    Shot("invito-qr", large = true) {
      InviteScreen(
        InviteUiState(
          isAdmin = true, phase = InvitePhase.SHOWING, qrText = "MCG1:ESEMPIO",
          summary = InviteSummary("anna", "Telefono di Anna", "anna", hoursLeft = 24),
        ),
        InviteActions.None,
      )
    },
    Shot("invito-non-admin") {
      InviteScreen(InviteUiState(isAdmin = false), InviteActions.None)
    },
    Shot("kit-admin") {
      RecoveryKitScreen(isAdmin = true, onBack = {}, onSecondAdmin = {}, onReplaceAdmin = {})
    },
    Shot("kit-familiare") {
      RecoveryKitScreen(isAdmin = false, onBack = {}, onSecondAdmin = {}, onReplaceAdmin = {})
    },
    Shot("stato-famiglia-qr") {
      FamilyStateScreen(FamilyStateUi.Ready("MCGS1:ESEMPIO", registryVersion = 12), onBack = {})
    },
    Shot("stato-famiglia-non-disponibile") {
      FamilyStateScreen(FamilyStateUi.Unavailable, onBack = {})
    },
    Shot("secondo-admin-cerca") {
      AdminRecoveryScreen(RecoveryUiState(RecoveryMode.SECOND_ADMIN, available = true, adminUser = "alessio"), RecoveryActions.None)
    },
    Shot("secondo-admin-nessun-kit") {
      AdminRecoveryScreen(
        RecoveryUiState(
          RecoveryMode.SECOND_ADMIN, available = true, adminUser = "alessio",
          feedback = Feedback(
            dev.pampa.mycloudgallery.feature.settings.R.string.recovery_no_kit_title,
            dev.pampa.mycloudgallery.feature.settings.R.string.recovery_no_kit,
            dev.antigravity.fluidengine.ui.theme.FluidTone.Warning,
          ),
        ),
        RecoveryActions.None,
      )
    },
    Shot("secondo-admin-kit-trovati") {
      AdminRecoveryScreen(
        RecoveryUiState(RecoveryMode.SECOND_ADMIN, available = true, adminUser = "alessio", stage = RecoveryStage.PICK, kits = kits),
        RecoveryActions.None,
      )
    },
    Shot("secondo-admin-codice", large = true) {
      AdminRecoveryScreen(
        RecoveryUiState(
          RecoveryMode.SECOND_ADMIN, available = true, adminUser = "alessio", stage = RecoveryStage.CODE,
          kits = kits.take(1), selectedKit = 0, code = "ABCD-EFGH-JKLM-NPQR",
        ),
        RecoveryActions.None,
      )
    },
    Shot("secondo-admin-codice-errato") {
      AdminRecoveryScreen(
        RecoveryUiState(
          RecoveryMode.SECOND_ADMIN, available = true, adminUser = "alessio", stage = RecoveryStage.CODE,
          kits = kits.take(1), selectedKit = 0,
          feedback = Feedback(
            dev.pampa.mycloudgallery.feature.settings.R.string.recovery_wrong_code_title,
            dev.pampa.mycloudgallery.feature.settings.R.string.recovery_wrong_code,
            dev.antigravity.fluidengine.ui.theme.FluidTone.Danger,
          ),
        ),
        RecoveryActions.None,
      )
    },
    Shot("recupero-admin-codice") {
      AdminRecoveryScreen(
        RecoveryUiState(
          RecoveryMode.REPLACE_ADMIN, available = true, adminUser = "alessio", stage = RecoveryStage.CODE,
          kits = kits.take(1), selectedKit = 0, code = "ABCD-EFGH", stateVersion = 12, oldAdmin = OldAdminFate.REVOKED,
        ),
        RecoveryActions.None,
      )
    },
    Shot("recupero-fatto") {
      AdminRecoveryScreen(RecoveryUiState(RecoveryMode.REPLACE_ADMIN, available = true, stage = RecoveryStage.DONE), RecoveryActions.None)
    },
    Shot("recupero-non-collegato") {
      AdminRecoveryScreen(RecoveryUiState(RecoveryMode.SECOND_ADMIN, available = false), RecoveryActions.None)
    },
    Shot("reset-intro", large = true) {
      FamilyResetScreen(ResetUiState(isAdmin = true), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },
    Shot("reset-conferma-1", sheet = true) {
      FamilyResetScreen(ResetUiState(isAdmin = true, stage = ResetStage.FIRST_CONFIRM), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },
    Shot("reset-parola") {
      FamilyResetScreen(ResetUiState(isAdmin = true, stage = ResetStage.TYPE_WORD, typed = "REIMP"), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },
    Shot("reset-parola-giusta") {
      FamilyResetScreen(ResetUiState(isAdmin = true, stage = ResetStage.TYPE_WORD, typed = "REIMPOSTA"), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },
    Shot("reset-fatto") {
      FamilyResetScreen(ResetUiState(isAdmin = true, stage = ResetStage.DONE), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },
    Shot("reset-non-admin") {
      FamilyResetScreen(ResetUiState(isAdmin = false), onBack = {}, onAsk = {}, onCancel = {}, onContinue = {}, onTyped = {}, onConfirm = {}, onInvite = {})
    },

    // --- Account del NAS -----------------------------------------------------------------------------
    Shot("account-collegato", large = true) {
      NasAccountScreen(NasAccountUiState(true, account, nasSession(), BreakerState.Closed(1)), NasAccountLinks.None)
    },
    Shot("account-via-vpn") {
      NasAccountScreen(NasAccountUiState(true, account, nasSession(Via.VPN), BreakerState.Closed(1)), NasAccountLinks.None)
    },
    Shot("account-serve-la-password") {
      NasAccountScreen(
        NasAccountUiState(true, account, NasConnState.AuthBlocked(AuthBlockReason.AUTH_FAILED), BreakerState.Open(AuthBlockReason.AUTH_FAILED, at = now, generation = 1)),
        NasAccountLinks.None,
      )
    },
    Shot("account-non-raggiungibile") {
      NasAccountScreen(NasAccountUiState(true, account, NasConnState.Unavailable(NasError.Timeout), BreakerState.Closed(1)), NasAccountLinks.None)
    },
    Shot("account-non-collegato") {
      NasAccountScreen(NasAccountUiState(false, null, NasConnState.Idle, BreakerState.Closed(1)), NasAccountLinks.None)
    },
    Shot("password-modulo") {
      PasswordScreen(PasswordUiState(available = true, user = "test2"), PasswordActions.None)
    },
    Shot("password-corta") {
      PasswordScreen(PasswordUiState(available = true, user = "test2", password = "corta123"), PasswordActions.None)
    },
    Shot("password-bloccata", large = true) {
      PasswordScreen(PasswordUiState(available = true, user = "test2", blockedBy = AuthBlockReason.AUTH_FAILED), PasswordActions.None)
    },
    Shot("password-in-prova") {
      PasswordScreen(PasswordUiState(available = true, user = "test2", phase = PasswordPhase.TESTING), PasswordActions.None)
    },
    Shot("password-fallita") {
      PasswordScreen(
        PasswordUiState(
          available = true, user = "test2", blockedBy = AuthBlockReason.AUTH_FAILED, stages = stagesFailed,
          feedback = Feedback(
            dev.pampa.mycloudgallery.feature.settings.R.string.password_failed_title,
            dev.pampa.mycloudgallery.feature.settings.R.string.nas_error_auth_failed,
            dev.antigravity.fluidengine.ui.theme.FluidTone.Danger,
          ),
        ),
        PasswordActions.None,
      )
    },
    Shot("password-fatta") {
      PasswordScreen(PasswordUiState(available = true, user = "test2", phase = PasswordPhase.DONE, stages = stagesOk), PasswordActions.None)
    },
    Shot("indirizzo-modulo") {
      AddressScreen(AddressUiState(available = true, currentIp = "192.168.178.58"), AddressActions.None)
    },
    Shot("indirizzo-formato-errato") {
      AddressScreen(AddressUiState(available = true, currentIp = "192.168.178.58", typed = "nas.fritz.box"), AddressActions.None)
    },
    Shot("indirizzo-verificato") {
      AddressScreen(
        AddressUiState(available = true, currentIp = "192.168.178.58", typed = "192.168.178.99", phase = AddressPhase.VERIFIED),
        AddressActions.None,
      )
    },
    Shot("indirizzo-nas-diverso") {
      AddressScreen(
        AddressUiState(available = true, currentIp = "192.168.178.58", typed = "192.168.178.77", phase = AddressPhase.MISMATCH),
        AddressActions.None,
      )
    },
    Shot("indirizzo-conferma", sheet = true) {
      AddressScreen(
        AddressUiState(available = true, currentIp = "192.168.178.58", typed = "192.168.178.99", phase = AddressPhase.CONFIRM),
        AddressActions.None,
      )
    },
    Shot("indirizzo-fatto") {
      AddressScreen(AddressUiState(available = true, currentIp = "192.168.178.99", phase = AddressPhase.DONE), AddressActions.None)
    },

    // --- Rete e VPN ----------------------------------------------------------------------------------
    Shot("rete-casa", large = true) {
      NetworkScreen(network(), NetworkActions.None)
    },
    Shot("rete-fuori-con-vpn") {
      NetworkScreen(
        network(Snapshots.awayVpnWifi(), RouteUi.Allowed(Via.VPN), automation = VpnAutomationStatus.Done(true, VpnCommandResult.Confirmed)),
        NetworkActions.None,
      )
    },
    Shot("rete-vpn-spenta") {
      NetworkScreen(network(Snapshots.vpnOff(), RouteUi.Denied(DenyReason.VPN_OFF_AWAY)), NetworkActions.None)
    },
    Shot("rete-estranea") {
      NetworkScreen(network(Snapshots.untrusted(), RouteUi.Denied(DenyReason.UNTRUSTED_NETWORK)), NetworkActions.None)
    },
    Shot("rete-vpn-estranea") {
      NetworkScreen(network(Snapshots.foreignVpn(), RouteUi.Denied(DenyReason.FOREIGN_VPN)), NetworkActions.None)
    },
    Shot("rete-solo-ipv4") {
      NetworkScreen(
        network(Snapshots.vpnOff().copy(ipv6Global = false), RouteUi.Denied(DenyReason.VPN_OFF_AWAY)),
        NetworkActions.None,
      )
    },
    Shot("rete-rete-locale-negata") {
      NetworkScreen(
        network(Snapshots.home().copy(localNetwork = LocalNetworkAccess.DENIED), RouteUi.Denied(DenyReason.LOCAL_NETWORK_DENIED)),
        NetworkActions.None,
      )
    },
    Shot("rete-wireguard-mancante") {
      NetworkScreen(
        network(Snapshots.vpnOff(), RouteUi.Denied(DenyReason.VPN_OFF_AWAY), capability = VpnCapability.WIREGUARD_MISSING, canOpenWireGuard = false, automation = VpnAutomationStatus.Off),
        NetworkActions.None,
      )
    },
    Shot("rete-wireguard-permesso") {
      NetworkScreen(
        network(Snapshots.vpnOff(), RouteUi.Denied(DenyReason.VPN_OFF_AWAY), capability = VpnCapability.PERMISSION_MISSING, automation = VpnAutomationStatus.Off),
        NetworkActions.None,
      )
    },
    Shot("rete-controllo-sospeso") {
      NetworkScreen(
        network(
          automation = VpnAutomationStatus.Suspended(VpnSuspendReason.LOCKDOWN_SUSPECTED),
          command = VpnCommandUi.Finished(up = false, result = VpnCommandResult.LostConnectivity),
        ),
        NetworkActions.None,
      )
    },
    Shot("rete-impronta-mancante") {
      NetworkScreen(network(fingerprint = null, capture = CaptureUi.NoUla), NetworkActions.None)
    },
    Shot("rete-raccolta-diversa") {
      NetworkScreen(network(capture = CaptureUi.Rejected(identityMismatch = true, error = NasError.IdentityMismatch)), NetworkActions.None)
    },
    Shot("rete-raccolta-conferma", sheet = true) {
      NetworkScreen(network(capture = CaptureUi.Ready(ulaCount = 1, sameAsSaved = false)), NetworkActions.None)
    },

    // --- Sicurezza e backup --------------------------------------------------------------------------
    Shot("sicurezza-spento") {
      SecurityScreen(SecurityUiState(AppLockSettings(), ScreenLockStatus.SECURE), SecurityActions.None)
    },
    Shot("sicurezza-attivo", large = true) {
      SecurityScreen(
        SecurityUiState(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE), ScreenLockStatus.SECURE),
        SecurityActions.None,
      )
    },
    Shot("sicurezza-senza-blocco-schermo") {
      SecurityScreen(SecurityUiState(AppLockSettings(), ScreenLockStatus.NOT_SECURE), SecurityActions.None)
    },
    Shot("backup-avviso", large = true) {
      BackupNoticeScreen(onBack = {}, onReviewChecklist = {})
    },
  )

  /** Gli stati secondari: si fotografano solo sul telefono (il tablet ha la schermata rappresentativa). */
  val phoneOnly: Set<String> = setOf(
    "home-registro-vecchio", "dispositivi-sosia", "dispositivi-registro-vecchio", "dispositivi-nessuna-famiglia",
    "dispositivo-questo", "dispositivo-sosia", "dispositivo-non-trovato", "dismetti-conferma", "perso-guida-passo-3",
    "perso-guida-proprietario", "approvazione-codice-errato", "approvazione-scansione-altro-dispositivo",
    "approvazione-scaduta", "approvazione-sosia", "approvazione-non-admin", "approvazione-registro-cambiato",
    "approvazione-non-trovata", "famiglia-nessuna", "invito-errori", "invito-non-admin", "kit-familiare",
    "stato-famiglia-non-disponibile", "secondo-admin-nessun-kit", "secondo-admin-kit-trovati",
    "secondo-admin-codice-errato", "recupero-fatto", "recupero-non-collegato", "reset-parola", "reset-non-admin",
    "account-via-vpn", "account-non-raggiungibile", "account-non-collegato", "password-corta", "password-in-prova",
    "indirizzo-formato-errato", "indirizzo-nas-diverso", "rete-vpn-estranea", "rete-solo-ipv4",
    "rete-rete-locale-negata", "rete-wireguard-mancante", "rete-wireguard-permesso", "rete-impronta-mancante",
    "rete-raccolta-diversa", "sicurezza-senza-blocco-schermo",
  )

  /** Per i test sui nomi: un nome non si ripete. */
  val names: List<String> get() = all.map { it.name }
}
