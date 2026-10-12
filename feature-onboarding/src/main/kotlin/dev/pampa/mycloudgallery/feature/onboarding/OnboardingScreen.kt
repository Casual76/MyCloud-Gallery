package dev.pampa.mycloudgallery.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep

/** La rotta con il suo ViewModel (Hilt). [resumeStep] e' il passo da riaprire, se si arriva da "Da completare". */
@Composable
internal fun OnboardingRoute(resumeStep: OnboardingStep?, onDone: () -> Unit) {
  val viewModel: OnboardingViewModel = hiltViewModel()
  val progress by viewModel.progress.collectAsStateWithLifecycle()
  LaunchedEffect(resumeStep) {
    if (resumeStep != null) viewModel.resume(resumeStep)
  }
  OnboardingScreen(progress = progress, onContinue = onDone)
}

/**
 * Scheletro P0 dell'onboarding: il passo corrente del coordinatore e un tasto per proseguire. Le schermate
 * vere dei 14 passi (e di ogni stato d'errore) sono di P6.
 */
@Composable
fun OnboardingScreen(progress: OnboardingProgress, onContinue: () -> Unit) {
  val step = progress.current
  FluidScreen(
    title = stringResource(R.string.onboarding_title),
    subtitle = stringResource(R.string.onboarding_subtitle),
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.onboarding_section_progress)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(step.titleRes()),
          subtitle = stringResource(R.string.onboarding_step_of, step.number, OnboardingStep.entries.size),
          disclosure = false,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.onboarding_placeholder_note)) }
    item {
      FluidButton(
        text = stringResource(R.string.onboarding_continue),
        onClick = onContinue,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
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
