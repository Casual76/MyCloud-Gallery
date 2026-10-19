package dev.pampa.mycloudgallery.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.platform.rememberAndroidPlatform
import dev.pampa.mycloudgallery.feature.onboarding.steps.WelcomeScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome

/**
 * La rotta con il suo ViewModel (Hilt) e la piattaforma Android (permessi, impostazioni di sistema).
 * [resumeStep] e' il passo da riaprire, se si arriva da "Da completare" (Impostazioni): quando si finisce, si esce.
 * [renew] e' "Rifai la richiesta" dalla pagina d'attesa (ON-2): finita la richiesta (o lasciata), si esce.
 * Senza ne' l'uno ne' l'altro e' la pagina della radice: un onboarding gia' finito vuol dire un dispositivo fuori dalla
 * famiglia (ON-4).
 * [onPause] e' "Continua piu' tardi" (E-O1): lascia la configurazione cosi' com'e', salvata.
 */
@Composable
internal fun OnboardingRoute(resumeStep: OnboardingStep?, onDone: () -> Unit, onPause: () -> Unit, renew: Boolean = false) {
  val viewModel: OnboardingViewModel = hiltViewModel()
  val platform = rememberAndroidPlatform()
  LaunchedEffect(resumeStep, renew) {
    when {
      resumeStep != null -> viewModel.resume(resumeStep)
      renew -> viewModel.startRenewal()
      else -> viewModel.enterAtRoot()
    }
  }
  OnboardingHost(
    viewModel = viewModel,
    platform = platform,
    resumeStep = resumeStep,
    onDone = onDone,
    onPause = onPause,
  )
}

/**
 * La pagina di benvenuto da sola, senza ViewModel: e' la firma che lo scaffold di `:app` (P0, test di screenshot) gia'
 * usa. La configurazione vera passa da [onboardingGraph] (che usa [OnboardingRoute]); [progress] qui non cambia
 * niente, e ogni scelta chiama [onContinue].
 */
@Composable
fun OnboardingScreen(progress: OnboardingProgress, onContinue: () -> Unit) {
  WelcomeScreen(
    chrome = StepChrome(),
    isSystemUser = true,
    busy = false,
    problem = null,
    onChoose = { onContinue() },
    onProblemAction = {},
  )
}

/** Il titolo di ogni passo (§2.1), in italiano. */
@StringRes
fun OnboardingStep.titleRes(): Int = when (this) {
  OnboardingStep.WELCOME -> R.string.onboarding_step_welcome
  OnboardingStep.NAS_ADDRESS -> R.string.onboarding_step_nas_address
  OnboardingStep.ACCOUNT -> R.string.onboarding_step_account
  OnboardingStep.AREAS -> R.string.onboarding_step_areas
  OnboardingStep.FOLDERS -> R.string.onboarding_step_folders
  OnboardingStep.BACKUP_SOURCES -> R.string.onboarding_step_backup_sources
  OnboardingStep.ROLES -> R.string.onboarding_step_roles
  OnboardingStep.PRESET -> R.string.onboarding_step_preset
  OnboardingStep.PERMISSIONS -> R.string.onboarding_step_permissions
  OnboardingStep.BATTERY -> R.string.onboarding_step_battery
  OnboardingStep.VPN -> R.string.onboarding_step_vpn
  OnboardingStep.FAMILY_KEY -> R.string.onboarding_step_family_key
  OnboardingStep.SECURITY_NOTICE -> R.string.onboarding_step_security_notice
  OnboardingStep.SUMMARY -> R.string.onboarding_step_summary
}
