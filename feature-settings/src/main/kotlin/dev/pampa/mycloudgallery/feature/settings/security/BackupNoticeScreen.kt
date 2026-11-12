package dev.pampa.mycloudgallery.feature.settings.security

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PublicOff
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsStatusHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import androidx.compose.material.icons.rounded.SdStorage
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Rivedere la checklist ha senso solo dove il passo 13 c'e': nel piano di chi ha creato la famiglia. Chi e' entrato con
 * un invito o ha recuperato l'admin non ce l'ha, e "Rivedi la checklist" lo portava sul riepilogo dell'onboarding senza
 * via d'uscita (verifica funzionale, ON-7).
 */
@HiltViewModel
internal class BackupNoticeViewModel @Inject constructor(coordinator: OnboardingCoordinator) : ViewModel() {
  val canReview: StateFlow<Boolean> = coordinator.progress
    .map { OnboardingStep.SECURITY_NOTICE in it.plan }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnboardingStep.SECURITY_NOTICE in coordinator.progress.value.plan)

  /** Giro 1 della fase I (CS-7, CU-10): la famiglia non ha ancora deciso (il passo 13 e' fra quelli da completare). */
  val undecided: StateFlow<Boolean> = coordinator.progress
    .map { OnboardingStep.SECURITY_NOTICE in it.pending }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnboardingStep.SECURITY_NOTICE in coordinator.progress.value.pending)

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** La pagina con il suo ViewModel: il tasto della checklist solo se il passo 13 e' nel piano (ON-7). */
@Composable
internal fun BackupNoticeRoute(onBack: () -> Unit, onReviewChecklist: () -> Unit) {
  val viewModel: BackupNoticeViewModel = hiltViewModel()
  val canReview by viewModel.canReview.collectAsStateWithLifecycle()
  val undecided by viewModel.undecided.collectAsStateWithLifecycle()
  BackupNoticeScreen(onBack = onBack, onReviewChecklist = onReviewChecklist.takeIf { canReview }, undecided = undecided)
}

/**
 * L'avviso sul backup esterno (`sicurezza.md` §1 e §9, passo 13 dell'onboarding, C13): il NAS ha due dischi che
 * si copiano a vicenda, **non e' un backup**. L'avviso resta in Impostazioni finche' la famiglia non ha deciso.
 *
 * E' una pagina di sola lettura con la checklist: la scelta "fatto/dopo" si fa nel passo 13 dell'onboarding,
 * che [onReviewChecklist] riapre (null: il passo 13 non e' nel piano di questo dispositivo, il tasto non c'e'). Qui non si
 * segna niente di cio' che l'app non puo' verificare (un disco USB attaccato al NAS o una voce del FRITZ!Box non si leggono).
 */
@Composable
internal fun BackupNoticeScreen(onBack: () -> Unit, onReviewChecklist: (() -> Unit)?, undecided: Boolean = true) {
  FluidScreen(
    title = stringResource(R.string.backup_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Glow),
  ) {
    // Giro 1 della fase I (CS-7, CU-10): «Il NAS non e' un backup» resta il titolo; «Senza copia» e il segno di attenzione solo finche' la famiglia
    // non ha deciso. La figura non porta segni fissi: l'app non vede un disco staccato.
    item {
      SettingsStatusHeader(
        icon = Icons.Rounded.SdStorage,
        family = GalleryIconFamily.Backup,
        value = stringResource(R.string.backup_hero_title),
        label = if (undecided) stringResource(R.string.backup_value) else null,
        status = if (undecided) GalleryIconStatus.Attention else null,
      )
    }
    item { RaidFigure() }
    item {
      SettingsInfo(
        label = stringResource(R.string.backup_raid_title),
        text = stringResource(R.string.backup_hero_detail) + " " + stringResource(R.string.backup_raid_message),
      )
    }

    item { GallerySectionHeader(title = stringResource(R.string.backup_section_checklist)) }
    item {
      FluidListGroup {
        CHECKLIST.forEachIndexed { index, item ->
          if (index > 0) FluidListDivider()
          ChecklistRow(item = item, important = undecided && index < IMPORTANT)
        }
      }
    }
    item { SettingsInfo(label = stringResource(R.string.backup_footnote_info), text = stringResource(R.string.backup_footnote)) }
    onReviewChecklist?.let { review ->
      item {
        FluidButton(
          text = stringResource(R.string.backup_review),
          onClick = review,
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { androidx.compose.material3.Icon(Icons.Rounded.Checklist, contentDescription = null) },
        )
      }
    }
    item {
      FluidButton(
        text = stringResource(R.string.backup_close),
        onClick = onBack,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
}

/** Una voce della lista: il glifo, il titolo, il come. */
private class ChecklistItem(val icon: ImageVector, val family: GalleryIconFamily, val title: Int, val detail: Int)

/** Le sei voci, nell'ordine di rischio (`sicurezza.md` §1): la copia esterna e' seconda solo alla rete, ma qui viene prima perche' e' l'unica difesa dalla perdita totale. */
private val CHECKLIST: List<ChecklistItem> = listOf(
  ChecklistItem(GalleryIcons.ExternalCopy.icon, GalleryIcons.ExternalCopy.family, R.string.backup_item_copy, R.string.backup_item_copy_detail),
  ChecklistItem(Icons.Rounded.PublicOff, GalleryIconFamily.Security, R.string.backup_item_public, R.string.backup_item_public_detail),
  ChecklistItem(Icons.Rounded.Wifi, GalleryIconFamily.Network, R.string.backup_item_wifi, R.string.backup_item_wifi_detail),
  ChecklistItem(Icons.Rounded.Devices, GalleryIconFamily.Network, R.string.backup_item_guest, R.string.backup_item_guest_detail),
  ChecklistItem(Icons.Rounded.CloudOff, GalleryIconFamily.Security, R.string.backup_item_cloud, R.string.backup_item_cloud_detail),
  ChecklistItem(Icons.Rounded.Router, GalleryIconFamily.Network, R.string.backup_item_dhcp, R.string.backup_item_dhcp_detail),
)

/** Le prime due voci contano di piu' (prima «Le prime due contano di piu'»): hanno il segno di attenzione. */
private const val IMPORTANT = 2

/** Una voce: la piastrella (con il segno di attenzione se e' fra le piu' importanti) e il titolo; un tocco apre il come sotto. */
@Composable
private fun ChecklistRow(item: ChecklistItem, important: Boolean) {
  var open by rememberSaveable(item.title) { mutableStateOf(false) }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val openState = stringResource(R.string.info_open)
  val closedState = stringResource(R.string.info_closed)
  Column {
    FluidListRow(
      title = stringResource(item.title),
      subtitle = "",
      leading = { RowTile(item.icon, status = if (important) GalleryIconStatus.Attention else null, family = item.family) },
      leadingTile = false,
      disclosure = false,
      onClick = { open = !open },
      modifier = Modifier.semantics { stateDescription = if (open) openState else closedState },
    )
    AnimatedVisibility(
      visible = open,
      enter = if (reduced) EnterTransition.None else expandVertically(FluidMotion.intSize()),
      exit = if (reduced) ExitTransition.None else shrinkVertically(FluidMotion.intSize()),
    ) {
      Text(
        text = stringResource(item.detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = SettingsDimens.CardPadding, end = SettingsDimens.CardPadding, bottom = SettingsDimens.CardPadding),
      )
    }
  }
}

/**
 * La figura: i due dischi del NAS che si copiano a vicenda (una freccia doppia fra loro) e, a parte, la copia staccata con la spunta. Per TalkBack una
 * frase sola.
 */
@Composable
private fun RaidFigure() {
  val description = stringResource(R.string.backup_raid_figure_description)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .padding(SettingsDimens.CardPadding)
      .clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsDimens.TightGap)) {
        GalleryIconTile(icon = Icons.Rounded.Storage, family = GalleryIconFamily.Backup, size = SettingsDimens.ChoiceTile)
        Icon(Icons.Rounded.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        GalleryIconTile(icon = Icons.Rounded.Storage, family = GalleryIconFamily.Backup, size = SettingsDimens.ChoiceTile)
      }
      Text(text = stringResource(R.string.backup_raid_nas), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap)) {
      GalleryIconTile(icon = GalleryIcons.ExternalCopy.icon, family = GalleryIcons.ExternalCopy.family, size = SettingsDimens.ChoiceTile)
      Text(text = stringResource(R.string.backup_raid_copy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
