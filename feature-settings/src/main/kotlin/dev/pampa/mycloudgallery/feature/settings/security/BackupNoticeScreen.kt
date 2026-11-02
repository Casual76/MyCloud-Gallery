package dev.pampa.mycloudgallery.feature.settings.security

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowNumber
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

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** La pagina con il suo ViewModel: il tasto della checklist solo se il passo 13 e' nel piano (ON-7). */
@Composable
internal fun BackupNoticeRoute(onBack: () -> Unit, onReviewChecklist: () -> Unit) {
  val viewModel: BackupNoticeViewModel = hiltViewModel()
  val canReview by viewModel.canReview.collectAsStateWithLifecycle()
  BackupNoticeScreen(onBack = onBack, onReviewChecklist = onReviewChecklist.takeIf { canReview })
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
internal fun BackupNoticeScreen(onBack: () -> Unit, onReviewChecklist: (() -> Unit)?) {
  FluidScreen(
    title = stringResource(R.string.backup_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = FluidHeroTone.Tertiary,
        motif = FluidHeroMotif.Cards,
        eyebrow = stringResource(R.string.backup_eyebrow),
        value = stringResource(R.string.backup_value),
        title = stringResource(R.string.backup_hero_title),
        description = stringResource(R.string.backup_hero_detail),
        icon = Icons.Rounded.Backup,
      )
    }
    item {
      Note(
        title = stringResource(R.string.backup_raid_title),
        message = stringResource(R.string.backup_raid_message),
        tone = FluidTone.Warning,
      )
    }

    item {
      GallerySectionHeader(
        title = stringResource(R.string.backup_section_checklist),
        detail = stringResource(R.string.backup_section_checklist_detail),
      )
    }
    item {
      FluidListGroup {
        CHECKLIST.forEachIndexed { index, (title, detail) ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = stringResource(title),
            subtitle = stringResource(detail),
            tone = if (index == 0) FluidTone.Warning else FluidTone.Neutral,
            leading = { RowNumber(index + 1) },
            disclosure = false,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.backup_footnote)) }
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

/** Le sei voci, nell'ordine di rischio (`sicurezza.md` §1): la copia esterna e' seconda solo alla rete, ma qui viene prima perche' e' l'unica difesa dalla perdita totale. */
private val CHECKLIST: List<Pair<Int, Int>> = listOf(
  R.string.backup_item_copy to R.string.backup_item_copy_detail,
  R.string.backup_item_public to R.string.backup_item_public_detail,
  R.string.backup_item_wifi to R.string.backup_item_wifi_detail,
  R.string.backup_item_guest to R.string.backup_item_guest_detail,
  R.string.backup_item_cloud to R.string.backup_item_cloud_detail,
  R.string.backup_item_dhcp to R.string.backup_item_dhcp_detail,
)
