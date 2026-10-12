package dev.pampa.mycloudgallery.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbientSurface
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.qr.QrScanner
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinApprovedPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinManualPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNameActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNamePage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinSummaryActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinSummaryPage
import dev.pampa.mycloudgallery.feature.onboarding.join.ManualJoinActions
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanMode
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanPage
import dev.pampa.mycloudgallery.feature.onboarding.join.ScannerSlot
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingDestination
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingUiState
import dev.pampa.mycloudgallery.feature.onboarding.model.activeProblem
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.platform.OnboardingPlatform
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionKind
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionState
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryActions
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryCodePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryDonePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryFatePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryKitsPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.BackupSourcesScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryAction
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyIntroPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyRetypePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyShowCodePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyVerifyingPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.NasAddressScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.PermissionsActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.PermissionsScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.PresetScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.RoleAdvice
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.SecurityNoticeScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.SummaryScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.WelcomeScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepProgressInfo
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepSidebar
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Il riquadro dello scanner di produzione: la fotocamera dentro l'app (`core-qr`, nessun ML Kit). */
internal val ProductionScanner: ScannerSlot = { accept, onCancel, modifier ->
  QrScanner(accept = accept, onCancel = onCancel, modifier = modifier)
}

/**
 * Tutta la configurazione iniziale: sceglie la pagina da mostrare ([OnboardingViewModel.destination]), la fa
 * entrare di lato con le molle di `FluidMotion` (una pagina in arrivo non sfuma mai: e' opaca e laterale, avanti da
 * destra e indietro da sinistra) e collega i gesti ai ViewModel e ad Android ([OnboardingPlatform]).
 *
 * Su uno schermo largo i passi stanno a sinistra, in una barra laterale, e la pagina a destra: un fondale solo per
 * la finestra. Su un telefono c'e' solo la pagina, con la barra del cammino in testa.
 *
 * L'intera configurazione e' sotto `OverlayShield`: ogni passo prende una decisione che conta (casa, account, aree,
 * chiave, kit), quindi sovrapposizioni di altre app nascoste e tocchi "oscurati" scartati. Le pagine con segreti
 * aggiungono `FLAG_SECURE`.
 *
 * @param resumeStep il passo riaperto da "Da completare": quando finisce si esce ([onDone]) invece di andare avanti.
 * @param onPause "Continua piu' tardi" (E-O1): lascia la configurazione cosi' com'e', salvata.
 */
@Composable
internal fun OnboardingHost(
  viewModel: OnboardingViewModel,
  platform: OnboardingPlatform,
  resumeStep: OnboardingStep?,
  onDone: () -> Unit,
  onPause: () -> Unit,
  modifier: Modifier = Modifier,
  scanner: ScannerSlot = ProductionScanner,
) {
  OverlayShield()
  val progress by viewModel.progress.collectAsStateWithLifecycle()
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        OnboardingEvent.Finished -> onDone()
      }
    }
  }
  val destination = viewModel.destination(progress, ui)
  // Si sente cio' che si vede: un problema che compare fa "errore", una conferma fa "riuscito" (vocabolario di Fluid).
  val haptics = rememberFluidHaptics()
  val problem = ui.activeProblem(destination)
  LaunchedEffect(problem) {
    if (problem != null) haptics.play(FluidHapticEvent.Error)
  }
  LaunchedEffect(ui.banner) {
    if (ui.banner != null) haptics.play(FluidHapticEvent.Success)
  }
  val goBack = viewModel.canGoBack(progress, ui)
  // Da "Da completare" il passo e' l'unico: "indietro" esce, il resto del percorso non c'entra.
  val resuming = resumeStep != null && destination == OnboardingDestination.Step(resumeStep)
  // E quando il passo riaperto e' finito (fatto o saltato di nuovo) il coordinatore va avanti: si esce, non si prosegue.
  var reopened by remember { mutableStateOf(false) }
  LaunchedEffect(progress.current, resumeStep) {
    if (resumeStep != null) {
      if (progress.current == resumeStep) reopened = true else if (reopened) onDone()
    }
  }
  BackHandler(enabled = goBack || resuming) {
    if (resuming) onDone() else viewModel.back()
  }
  val env = HostEnv(viewModel, platform, progress, ui, resumeStep, onDone, onPause, scanner, goBack, resuming)

  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  OnboardingFrame(
    progress = progress,
    sidebarStep = sidebarStep(destination, progress),
    ambientStep = (destination as? OnboardingDestination.Step)?.step,
    modifier = modifier,
  ) {
    AnimatedContent(
      targetState = destination,
      modifier = Modifier.fillMaxSize(),
      transitionSpec = { pageTransition(reducedMotion, initialState.order, targetState.order) },
      label = "onboarding page",
    ) { page -> PageContent(page, env) }
  }
}

/**
 * La cornice di ogni pagina. Su un telefono e' la pagina e basta (con la barra del cammino in testa); su uno schermo
 * largo ([OnboardingDimens.WideBreakpoint]) sono due pannelli, la barra laterale dei passi e la pagina, **sopra un
 * fondale solo** per l'intera finestra. [wide] a null decide dalla larghezza; i test lo forzano.
 */
@Composable
internal fun OnboardingFrame(
  progress: OnboardingProgress,
  sidebarStep: OnboardingStep,
  ambientStep: OnboardingStep?,
  modifier: Modifier = Modifier,
  wide: Boolean? = null,
  content: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val twoPanes = wide ?: (maxWidth >= OnboardingDimens.WideBreakpoint)
    if (twoPanes) {
      FluidAmbientSurface(ambient = stepAmbient(ambientStep)) {
        Row(modifier = Modifier.fillMaxSize()) {
          StepSidebar(progress = progress, currentStep = sidebarStep)
          Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
        }
      }
    } else {
      content()
    }
  }
}

/** Il passo evidenziato nella barra laterale: le pagine di "entra" e di "recupera" stanno dove stanno indirizzo e account. */
private fun sidebarStep(destination: OnboardingDestination, progress: OnboardingProgress): OnboardingStep = when (destination) {
  is OnboardingDestination.Step -> destination.step
  is OnboardingDestination.Join ->
    if (destination.page == JoinPage.Password || destination.page == JoinPage.Name) OnboardingStep.ACCOUNT else OnboardingStep.NAS_ADDRESS
  is OnboardingDestination.Recovery -> if (destination.page == RecoveryPage.Done) progress.current else OnboardingStep.ACCOUNT
}

/** Avanti: la nuova pagina entra da destra e la vecchia scivola un po' a sinistra. Indietro: il contrario, con la vecchia sopra. */
private fun pageTransition(reducedMotion: Boolean, from: Float, to: Float): ContentTransform {
  if (reducedMotion) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
  val forward = to >= from
  val enter = slideInHorizontally(FluidMotion.intOffset()) { width -> if (forward) width else -(width * FluidMotion.PeerSlideFraction).toInt() }
  val exit = slideOutHorizontally(FluidMotion.intOffset()) { width -> if (forward) -(width * FluidMotion.PeerSlideFraction).toInt() else width }
  return ContentTransform(
    targetContentEnter = enter,
    initialContentExit = exit,
    targetContentZIndex = if (forward) 1f else -1f,
    sizeTransform = null,
  )
}

/** Tutto cio' che una pagina puo' aver bisogno di sapere, in un posto solo (evita liste di parametri lunghe un metro). */
internal class HostEnv(
  val viewModel: OnboardingViewModel,
  val platform: OnboardingPlatform,
  val progress: OnboardingProgress,
  val ui: OnboardingUiState,
  val resumeStep: OnboardingStep?,
  val onDone: () -> Unit,
  val onPause: () -> Unit,
  val scanner: ScannerSlot,
  val canGoBack: Boolean,
  val resuming: Boolean,
) {
  val forms: OnboardingForms get() = viewModel.forms

  /** La freccia di ritorno: solo dove si puo' tornare. Da "Da completare" il ritorno esce. */
  val onBack: (() -> Unit)? get() = when {
    resuming -> onDone
    canGoBack -> viewModel::back
    else -> null
  }

  fun chrome(destination: OnboardingDestination): StepChrome {
    val step = (destination as? OnboardingDestination.Step)?.step
    return StepChrome(
      progress = progressInfo(destination),
      onBack = onBack,
      banner = ui.banner?.takeIf { it.step == step },
      onDismissBanner = viewModel::dismissBanner,
      warnings = warningsFor(destination),
      onDismissWarning = viewModel::dismissWarning,
    )
  }

  /** "Passo 3 di 14": la posizione nel piano. Il benvenuto e le pagine finali non hanno barra. */
  private fun progressInfo(destination: OnboardingDestination): StepProgressInfo? {
    val step = when (destination) {
      is OnboardingDestination.Step -> destination.step.takeIf { it != OnboardingStep.WELCOME }
      is OnboardingDestination.Join ->
        if (destination.page == JoinPage.Awaiting || destination.page == JoinPage.Approved) null
        else if (destination.page == JoinPage.Password || destination.page == JoinPage.Name) OnboardingStep.ACCOUNT
        else OnboardingStep.NAS_ADDRESS
      is OnboardingDestination.Recovery -> if (destination.page == RecoveryPage.Done) null else OnboardingStep.ACCOUNT
    } ?: return null
    val index = progress.plan.indexOf(step)
    if (index < 0) return null
    return StepProgressInfo(step = index + 1, total = progress.plan.size)
  }

  private fun warningsFor(destination: OnboardingDestination): List<OnboardingWarning> {
    val wanted = when (destination) {
      is OnboardingDestination.Step -> when (destination.step) {
        OnboardingStep.AREAS -> setOf(OnboardingWarning.SHORT_PASSWORD, OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES)
        OnboardingStep.FOLDERS -> setOf(OnboardingWarning.SHARE_IS_PUBLIC)
        OnboardingStep.SUMMARY -> setOf(OnboardingWarning.NO_SCREEN_LOCK)
        else -> emptySet()
      }
      is OnboardingDestination.Join ->
        if (destination.page == JoinPage.Password || destination.page == JoinPage.Name || destination.page == JoinPage.Awaiting) {
          setOf(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
        } else {
          emptySet()
        }
      is OnboardingDestination.Recovery -> emptySet()
    }
    return OnboardingWarning.entries.filter { it in wanted && it in ui.warnings }
  }

  /** Cosa fa ogni bottone di un problema; "Riprova" lo decide la pagina che lo mostra. */
  fun onProblemAction(action: ProblemAction, retry: () -> Unit = {}) {
    when (action) {
      ProblemAction.RETRY -> retry()
      ProblemAction.CONTINUE_LATER -> onPause()
      ProblemAction.OPEN_WIFI_SETTINGS -> platform.openWifiSettings()
      ProblemAction.OPEN_WIREGUARD -> viewModel.wireGuardIntent()?.let { platform.open(it) }
      ProblemAction.OPEN_APP_SETTINGS -> platform.openAppSettings()
      ProblemAction.CHANGE_ADDRESS -> viewModel.clearProblem(OnboardingStep.NAS_ADDRESS)
      ProblemAction.RECOVER_ADMIN -> viewModel.choosePath(OnboardingPath.RECOVER_ADMIN)
      ProblemAction.JOIN_AS_MEMBER -> viewModel.choosePath(OnboardingPath.JOIN_QR)
      ProblemAction.SCAN_AGAIN -> viewModel.joinScanInvite()
      ProblemAction.ENTER_MANUALLY -> viewModel.joinEnterManually()
      ProblemAction.BACK_TO_CODE -> viewModel.showCodeAgain()
    }
  }
}

@Composable
private fun PageContent(page: OnboardingDestination, env: HostEnv) {
  when (page) {
    is OnboardingDestination.Step -> StepContent(page, env)
    is OnboardingDestination.Join -> JoinContent(page, env)
    is OnboardingDestination.Recovery -> RecoveryContent(page, env)
  }
}

@Composable
private fun StepContent(page: OnboardingDestination.Step, env: HostEnv) {
  val vm = env.viewModel
  val ui = env.ui
  val forms = env.forms
  val chrome = env.chrome(page)
  when (page.step) {
    OnboardingStep.WELCOME -> WelcomeScreen(
      chrome = chrome,
      isSystemUser = ui.isSystemUser,
      busy = ui.welcomeBusy,
      problem = ui.welcomeProblem,
      onChoose = vm::choosePath,
      onProblemAction = { env.onProblemAction(it) },
    )

    OnboardingStep.NAS_ADDRESS -> {
      LaunchedEffect(Unit) { vm.enterAddressStep() }
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Si torna dalle impostazioni Wi-Fi o di WireGuard (E-O9): la rete si rilegge.
        if (ui.address.home !is dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi.Confirmed) vm.enterAddressStep()
      }
      NasAddressScreen(
        chrome = chrome,
        ui = ui.address,
        form = forms.address,
        actions = AddressActions(
          onConfirmHome = vm::confirmHome,
          onRefreshHome = vm::enterAddressStep,
          onCheck = vm::checkNas,
          onProblemAction = { action ->
            env.onProblemAction(action) { forms.address.address()?.let(vm::checkNas) ?: vm.enterAddressStep() }
          },
        ),
        title = stringResource(
          if (env.progress.path == OnboardingPath.FOUND_FAMILY || env.progress.path == null) R.string.address_title else R.string.address_title_admin,
        ),
      )
    }

    OnboardingStep.ACCOUNT -> {
      DisposableEffect(Unit) { onDispose { forms.account.password = "" } }
      AccountScreen(
        chrome = chrome,
        ui = ui.account,
        form = forms.account,
        actions = AccountActions(
          onSubmit = vm::submitAccount,
          onProblemAction = { env.onProblemAction(it) },
        ),
        title = stringResource(
          if (env.progress.path == OnboardingPath.FOUND_FAMILY || env.progress.path == null) R.string.account_title else R.string.account_title_admin,
        ),
      )
    }

    OnboardingStep.AREAS -> {
      LaunchedEffect(Unit) { vm.enterAreasStep() }
      AreasScreen(
        chrome = chrome,
        ui = ui.areas,
        form = forms.areas,
        actions = AreasActions(
          onSubmit = vm::submitAreas,
          onAddShare = vm::addShare,
          onRefresh = vm::refreshAreas,
          onProblemAction = { env.onProblemAction(it, retry = vm::refreshAreas) },
        ),
      )
    }

    OnboardingStep.FOLDERS -> {
      LaunchedEffect(Unit) { vm.enterFoldersStep() }
      FoldersScreen(
        chrome = chrome,
        ui = ui.folders,
        form = forms.folders,
        actions = FoldersActions(
          onSubmit = vm::submitFolders,
          onSkip = { vm.skip(OnboardingStep.FOLDERS) },
          onProblemAction = { env.onProblemAction(it, retry = vm::enterFoldersStep) },
        ),
      )
    }

    OnboardingStep.BACKUP_SOURCES -> BackupSourcesScreen(
      chrome = chrome,
      ui = ui.backupSources,
      form = forms.backupSources,
      onSubmit = vm::submitBackupSources,
      onProblemAction = { env.onProblemAction(it) },
    )

    OnboardingStep.ROLES -> {
      val advice = RoleAdvice.forDevice(env.platform.isTablet())
      LaunchedEffect(advice) { forms.roles.seed(advice) }
      RolesScreen(
        chrome = chrome,
        ui = ui.roles,
        form = forms.roles,
        advice = advice,
        onSubmit = vm::submitRoles,
        onProblemAction = { env.onProblemAction(it) },
      )
    }

    OnboardingStep.PRESET -> PresetScreen(
      chrome = chrome,
      ui = ui.preset,
      form = forms.preset,
      onSubmit = vm::submitPreset,
      onProblemAction = { env.onProblemAction(it) },
    )

    OnboardingStep.PERMISSIONS -> {
      val localNetwork = vm.localNetworkAccess()
      val snapshot = env.platform.permissions().let { base ->
        base.copy(
          states = base.states + (PermissionKind.LOCAL_NETWORK to
            if (localNetwork == LocalNetworkAccess.DENIED) PermissionState.DENIED else PermissionState.NOT_NEEDED),
        )
      }
      PermissionsScreen(
        chrome = chrome,
        ui = ui.permissions,
        snapshot = snapshot,
        actions = PermissionsActions(
          onRequest = env.platform::requestPermission,
          onOpenAppSettings = { env.platform.openAppSettings() },
          onOpenSecuritySettings = { env.platform.openSecuritySettings() },
          onContinue = vm::submitPermissionsReviewed,
          onProblemAction = { env.onProblemAction(it) },
        ),
      )
    }

    OnboardingStep.BATTERY -> {
      LaunchedEffect(Unit) { vm.refreshBattery() }
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBattery() }
      BatteryScreen(
        chrome = chrome,
        ui = ui.battery,
        form = forms.battery,
        actions = BatteryActions(
          onOpen = { action ->
            when (action) {
              BatteryAction.UNRESTRICTED -> env.platform.openBatteryExemption()
              BatteryAction.NEVER_SLEEP -> env.platform.openBackgroundLimits()
              BatteryAction.UNUSED_PAUSE -> env.platform.openUnusedAppSettings()
            }
          },
          onRecheck = vm::refreshBattery,
          onContinue = vm::submitBatteryReviewed,
          onSkip = { vm.skip(OnboardingStep.BATTERY) },
          onProblemAction = { env.onProblemAction(it) },
        ),
      )
    }

    OnboardingStep.VPN -> {
      LaunchedEffect(Unit) { vm.enterVpnStep() }
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.enterVpnStep() }
      VpnScreen(
        chrome = chrome,
        ui = ui.vpn,
        form = forms.vpn,
        actions = VpnActions(
          onOpenWireGuard = { vm.wireGuardIntent()?.let { env.platform.open(it) } },
          onRunTest = { vm.runVpnTest() },
          onContinue = vm::submitVpn,
          onSkip = { vm.skip(OnboardingStep.VPN) },
          onProblemAction = { env.onProblemAction(it) },
        ),
      )
    }

    OnboardingStep.FAMILY_KEY -> KeyContent(page, env, chrome)

    OnboardingStep.SECURITY_NOTICE -> SecurityNoticeScreen(
      chrome = chrome,
      ui = ui.securityNotice,
      form = forms.securityNotice,
      onSubmit = vm::submitSecurityNotice,
      onProblemAction = { env.onProblemAction(it) },
    )

    OnboardingStep.SUMMARY -> SummaryScreen(
      chrome = chrome,
      ui = ui.summary,
      progress = env.progress,
      elapsedSeconds = env.progress.startedAt?.let { ((System.currentTimeMillis() - it) / MILLIS_PER_SECOND).coerceAtLeast(0L) },
      onFinish = vm::finish,
      onProblemAction = { env.onProblemAction(it, retry = vm::finish) },
    )
  }
}

private const val MILLIS_PER_SECOND = 1_000L

@Composable
private fun KeyContent(page: OnboardingDestination.Step, env: HostEnv, chrome: StepChrome) {
  val vm = env.viewModel
  val ui = env.ui
  val form = env.forms.key
  val actions = KeyActions(
    onGenerate = vm::generateKey,
    onCodeWritten = vm::codeWritten,
    onBackToCode = vm::showCodeAgain,
    onSubmitCode = vm::submitRecoveryCode,
    onProblemAction = { action ->
      env.onProblemAction(action) { vm.submitRecoveryCode(form.typed.toCharArray()) }
    },
  )
  // Il codice scritto non resta in memoria oltre il passo: all'uscita si svuota.
  DisposableEffect(Unit) {
    onDispose {
      form.typed = ""
      form.acknowledged = false
    }
  }
  when (KeyPage.entries[page.sub.coerceIn(0, KeyPage.entries.lastIndex)]) {
    KeyPage.Intro -> KeyIntroPage(chrome = chrome.copy(onBack = env.onBack), ui = ui.key, actions = actions)
    KeyPage.ShowCode -> KeyShowCodePage(chrome = chrome.copy(onBack = null), ui = ui.key, form = form, actions = actions)
    KeyPage.Retype -> KeyRetypePage(chrome = chrome.copy(onBack = vm::showCodeAgain), ui = ui.key, form = form, actions = actions)
    KeyPage.Verifying -> KeyVerifyingPage(chrome = chrome.copy(onBack = null))
  }
}

@Composable
private fun JoinContent(page: OnboardingDestination.Join, env: HostEnv) {
  val vm = env.viewModel
  val join = env.ui.join ?: return
  val forms = env.forms
  val chrome = env.chrome(page)
  when (page.page) {
    JoinPage.Scan -> ScanPage(
      chrome = chrome,
      mode = if (join.scanningFamilyState) ScanMode.FamilyState else ScanMode.Invite,
      rejection = join.rejection,
      scanner = env.scanner,
      onScanned = vm::onScanned,
      onManual = if (join.scanningFamilyState) null else vm::joinEnterManually,
      onCancel = vm::cancelFamilyStateScan,
    )

    JoinPage.Manual -> JoinManualPage(
      chrome = chrome,
      ui = join,
      form = forms.manualJoin,
      actions = ManualJoinActions(
        onSubmit = { form ->
          vm.submitManualJoin(form.ip, form.user, form.familyId, form.fingerprint, form.deviceName)
        },
        onScanFamilyState = vm::scanFamilyState,
        onProblemAction = { env.onProblemAction(it) },
      ),
    )

    JoinPage.Summary -> join.invite?.let { invite ->
      JoinSummaryPage(
        chrome = chrome,
        ui = join,
        invite = invite,
        expiresLabel = expiresLabel(invite.expiresAtNas),
        actions = JoinSummaryActions(
          onContinue = vm::continueJoin,
          onRescan = vm::joinScanInvite,
          onProblemAction = { env.onProblemAction(it, retry = vm::continueJoin) },
        ),
      )
    }

    JoinPage.Password -> {
      DisposableEffect(Unit) { onDispose { forms.joinPassword.password = "" } }
      JoinPasswordPage(
        chrome = chrome,
        ui = join,
        account = join.invite?.nasUser.orEmpty(),
        form = forms.joinPassword,
        actions = JoinPasswordActions(
          onSubmit = vm::submitJoinPassword,
          onProblemAction = { env.onProblemAction(it) },
        ),
      )
    }

    JoinPage.Name -> {
      LaunchedEffect(join.suggestedName) {
        if (forms.joinName.name.isBlank()) forms.joinName.name = join.suggestedName
      }
      JoinNamePage(
        chrome = chrome,
        ui = join,
        form = forms.joinName,
        actions = JoinNameActions(
          onSubmit = vm::submitJoinName,
          onProblemAction = { env.onProblemAction(it) },
        ),
      )
    }

    JoinPage.Awaiting -> AwaitingApprovalScreen(code = join.code.orEmpty(), deviceKeyQr = join.deviceKeyQr)

    JoinPage.Approved -> JoinApprovedPage(chrome = chrome, onFinish = vm::finishJoin)
  }
}

/** "fino a ..." dell'invito: la scadenza e' nell'ora del NAS, e mostrarla e' solo un'informazione per l'utente. */
private fun expiresLabel(expiresAtNas: Long): String? {
  if (expiresAtNas == Long.MAX_VALUE || expiresAtNas <= 0L) return null
  val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.ITALY)
  return format.format(java.util.Date(expiresAtNas))
}

@Composable
private fun RecoveryContent(page: OnboardingDestination.Recovery, env: HostEnv) {
  val vm = env.viewModel
  val recovery = env.ui.recovery ?: return
  val forms = env.forms
  val chrome = env.chrome(page)
  val actions = RecoveryActions(
    onSelectKit = vm::selectKit,
    onKitsContinue = vm::kitsContinue,
    onRetryKits = { vm.searchKits(forms.recovery.share.ifBlank { recovery.privateShare }) },
    onSearchKits = vm::searchKits,
    onChooseFate = vm::chooseFate,
    onSubmitCode = vm::submitRecoveryCodeForKit,
    onScanFamilyState = vm::scanFamilyState,
    onFinish = vm::finishRecovery,
    onProblemAction = { env.onProblemAction(it) },
  )
  if (recovery.scanningFamilyState) {
    ScanPage(
      chrome = chrome,
      mode = ScanMode.FamilyState,
      rejection = recovery.rejection,
      scanner = env.scanner,
      onScanned = vm::onScanned,
      onManual = null,
      onCancel = vm::cancelFamilyStateScan,
    )
    return
  }
  when (page.page) {
    RecoveryPage.Kits -> RecoveryKitsPage(chrome = chrome, ui = recovery, form = forms.recovery, actions = actions)
    RecoveryPage.Fate -> RecoveryFatePage(chrome = chrome, form = forms.recovery, actions = actions)
    RecoveryPage.Code -> {
      DisposableEffect(Unit) { onDispose { forms.recovery.code = "" } }
      RecoveryCodePage(chrome = chrome, ui = recovery, form = forms.recovery, actions = actions)
    }
    RecoveryPage.Done -> RecoveryDonePage(chrome = chrome, ui = recovery, fate = forms.recovery.fate, actions = actions)
  }
}
