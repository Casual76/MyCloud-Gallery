package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
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
    // I4 (fase I, «l'app parla troppo»): le tre promesse sono tre segni con due parole sotto, in fila; la frase intera la dice TalkBack.
    item(key = "promises") {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Promise(Icons.Rounded.VisibilityOff, GalleryIconFamily.Security, R.string.welcome_promise_telemetry, R.string.welcome_promise_telemetry_detail, Modifier.weight(1f))
        Promise(Icons.Rounded.CloudOff, GalleryIconFamily.Network, R.string.welcome_promise_cloud, R.string.welcome_promise_cloud_detail, Modifier.weight(1f))
        Promise(Icons.Rounded.Storage, GalleryIconFamily.Backup, R.string.welcome_promise_home, R.string.welcome_promise_home_detail, Modifier.weight(1f))
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
    item(key = "header-start") { GallerySectionHeader(title = stringResource(R.string.welcome_section_start)) }
    item(key = "found") {
      FluidListGroup {
        PathRow(Icons.Rounded.Groups, GalleryIconFamily.People, R.string.welcome_path_found, R.string.welcome_path_found_detail, choose(OnboardingPath.FOUND_FAMILY))
      }
    }
    item(key = "header-join") { GallerySectionHeader(title = stringResource(R.string.welcome_section_join)) }
    item(key = "join") {
      FluidListGroup {
        PathRow(Icons.Rounded.QrCodeScanner, GalleryIconFamily.People, R.string.welcome_path_join_qr, R.string.welcome_path_join_qr_detail, choose(OnboardingPath.JOIN_QR))
        FluidListDivider()
        PathRow(Icons.Rounded.Edit, GalleryIconFamily.People, R.string.welcome_path_join_manual, R.string.welcome_path_join_manual_detail, choose(OnboardingPath.JOIN_MANUAL))
      }
    }
    item(key = "header-admin") { GallerySectionHeader(title = stringResource(R.string.welcome_section_admin)) }
    item(key = "admin") {
      FluidListGroup {
        PathRow(Icons.Rounded.Key, GalleryIconFamily.Security, R.string.welcome_path_recover, R.string.welcome_path_recover_detail, choose(OnboardingPath.RECOVER_ADMIN))
        FluidListDivider()
        PathRow(Icons.Rounded.AdminPanelSettings, GalleryIconFamily.Security, R.string.welcome_path_second_admin, R.string.welcome_path_second_admin_detail, choose(OnboardingPath.SECOND_ADMIN))
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.welcome_footnote)) }
  }
}

/** Una promessa: la piastrella colorata e due parole sotto. TalkBack dice anche la frase che spiega. */
@Composable
private fun Promise(icon: ImageVector, family: GalleryIconFamily, title: Int, detail: Int, modifier: Modifier = Modifier) {
  val spoken = stringResource(title) + ". " + stringResource(detail)
  Column(
    modifier = modifier.padding(horizontal = PromiseGap).clearAndSetSemantics { contentDescription = spoken },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PromiseGap),
  ) {
    GalleryIconTile(icon = icon, family = family, size = GalleryIconTileDefaults.Medium)
    Text(
      text = stringResource(title),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
  }
}

/** Un percorso: la piastrella colorata e il titolo. La frase che lo spiega la dice TalkBack dopo il titolo. */
@Composable
private fun PathRow(icon: ImageVector, family: GalleryIconFamily, title: Int, detail: Int, onClick: (() -> Unit)?) {
  val spoken = stringResource(detail)
  FluidListRow(
    title = stringResource(title),
    subtitle = "",
    leading = { GalleryIconTile(icon = icon, family = family) },
    leadingTile = false,
    onClick = onClick,
    modifier = Modifier.semantics { stateDescription = spoken },
  )
}

private val PromiseGap = 8.dp
