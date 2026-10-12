package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/**
 * Passo 1: benvenuto e privacy, e la scelta del percorso (§2.1).
 *
 * Niente barra dei tasti: la scelta e' la pagina. Il percorso piu' comune (creare la famiglia) apre il
 * gruppo, gli altri sono raggruppati per quello che l'utente sta cercando di fare (entrare, recuperare).
 * Con un profilo secondario del tablet (E-P19) una scheda spiega che ogni profilo e' un dispositivo a se'.
 */
@Composable
internal fun WelcomeScreen(
  chrome: StepChrome,
  isSystemUser: Boolean,
  busy: Boolean,
  problem: OnboardingProblem?,
  onChoose: (OnboardingPath) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  fun choose(path: OnboardingPath): (() -> Unit)? = if (busy) null else ({ onChoose(path) })
  StepScaffold(
    title = stringResource(R.string.welcome_title),
    subtitle = stringResource(R.string.welcome_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.WELCOME),
    modifier = modifier,
  ) {
    item(key = "hero") {
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        contentAlignment = Alignment.Center,
      ) {
        GalleryIllustration(icon = Icons.Rounded.PhotoLibrary, tone = GalleryIllustrationTone.Brand)
      }
    }
    item(key = "promises") {
      FluidListGroup {
        PromiseRow(Icons.Rounded.VisibilityOff, R.string.welcome_promise_telemetry, R.string.welcome_promise_telemetry_detail)
        FluidListDivider()
        PromiseRow(Icons.Rounded.CloudOff, R.string.welcome_promise_cloud, R.string.welcome_promise_cloud_detail)
        FluidListDivider()
        PromiseRow(Icons.Rounded.Storage, R.string.welcome_promise_home, R.string.welcome_promise_home_detail)
      }
    }
    if (!isSystemUser) {
      item(key = "profile") {
        NoticeCard(
          tone = NoticeTone.Info,
          icon = Icons.Rounded.TabletAndroid,
          title = stringResource(R.string.welcome_profile_title),
          body = stringResource(R.string.welcome_profile_detail),
        )
      }
    }
    if (problem != null) {
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "header-start") { FluidSectionHeader(title = stringResource(R.string.welcome_section_start)) }
    item(key = "found") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.welcome_path_found),
          subtitle = stringResource(R.string.welcome_path_found_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Groups, contentDescription = null) },
          onClick = choose(OnboardingPath.FOUND_FAMILY),
        )
      }
    }
    item(key = "header-join") { FluidSectionHeader(title = stringResource(R.string.welcome_section_join)) }
    item(key = "join") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.welcome_path_join_qr),
          subtitle = stringResource(R.string.welcome_path_join_qr_detail),
          tone = FluidTone.Info,
          leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
          onClick = choose(OnboardingPath.JOIN_QR),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.welcome_path_join_manual),
          subtitle = stringResource(R.string.welcome_path_join_manual_detail),
          tone = FluidTone.Info,
          leading = { Icon(Icons.Rounded.Edit, contentDescription = null) },
          onClick = choose(OnboardingPath.JOIN_MANUAL),
        )
      }
    }
    item(key = "header-admin") { FluidSectionHeader(title = stringResource(R.string.welcome_section_admin)) }
    item(key = "admin") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.welcome_path_recover),
          subtitle = stringResource(R.string.welcome_path_recover_detail),
          tone = FluidTone.Warning,
          leading = { Icon(Icons.Rounded.Key, contentDescription = null) },
          onClick = choose(OnboardingPath.RECOVER_ADMIN),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.welcome_path_second_admin),
          subtitle = stringResource(R.string.welcome_path_second_admin_detail),
          tone = FluidTone.Warning,
          leading = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
          onClick = choose(OnboardingPath.SECOND_ADMIN),
        )
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.welcome_footnote)) }
  }
}

@Composable
private fun PromiseRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, detail: Int) {
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(detail),
    tone = FluidTone.Primary,
    leading = { Icon(icon, contentDescription = null) },
    disclosure = false,
  )
}
