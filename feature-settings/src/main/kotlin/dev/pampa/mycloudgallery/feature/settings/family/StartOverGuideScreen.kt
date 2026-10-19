package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowNumber
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * La cartella della famiglia sul NAS per la guida "ricominciare da zero" (ON-6): l'indirizzo e l'area comune salvati
 * dall'onboarding, nella forma di Esplora file. Null finche' non si legge, o se non si conoscono.
 */
@HiltViewModel
internal class StartOverGuideViewModel @Inject constructor(coordinator: OnboardingCoordinator) : ViewModel() {

  private val _folder = MutableStateFlow<String?>(null)
  val folder: StateFlow<String?> = _folder.asStateFlow()

  init {
    viewModelScope.launch {
      _folder.value = try {
        coordinator.savedNas()?.familyFolderForWindows()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
    }
  }
}

@Composable
internal fun StartOverGuideRoute(onBack: () -> Unit) {
  val viewModel: StartOverGuideViewModel = hiltViewModel()
  val folder by viewModel.folder.collectAsStateWithLifecycle()
  StartOverGuideScreen(familyFolder = folder, onBack = onBack)
}

/**
 * "Ricominciare da zero" (verifica funzionale, ON-6; scelta dell'utente del 19/10: "via dal PC"). L'admin ha perso il
 * telefono e non ha piu' ne' kit ne' codice. Nessun familiare puo' azzerare la famiglia dall'app (resta un potere
 * dell'admin): la pagina spiega come farlo dal PC, con il percorso esatto della cartella da cancellare, e che cosa si
 * perde. L'app non tocca il NAS.
 */
@Composable
internal fun StartOverGuideScreen(familyFolder: String?, onBack: () -> Unit) {
  val parent = familyFolder?.substringBeforeLast('\\')
  FluidScreen(
    title = stringResource(R.string.family_startover_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Alert, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = FluidHeroTone.Alert,
        motif = FluidHeroMotif.Ticks,
        eyebrow = stringResource(R.string.family_startover_eyebrow),
        value = stringResource(R.string.family_startover_value),
        title = stringResource(R.string.family_startover_hero_title),
        description = stringResource(R.string.family_startover_hero_detail),
        icon = Icons.Rounded.RestartAlt,
      )
    }
    item {
      Note(
        title = stringResource(R.string.family_startover_loss_title),
        message = stringResource(R.string.family_startover_loss_detail),
        tone = FluidTone.Danger,
      )
    }
    item {
      Note(
        title = stringResource(R.string.family_startover_why_title),
        message = stringResource(R.string.family_startover_why_detail),
        tone = FluidTone.Info,
      )
    }
    item { FluidSectionHeader(title = stringResource(R.string.family_startover_steps_section)) }
    item {
      FluidCard {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap)) {
          Text(text = stringResource(R.string.family_startover_step_open), style = MaterialTheme.typography.titleSmall)
          Text(
            text = stringResource(R.string.family_startover_step_open_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SelectionContainer {
            Text(
              text = parent ?: stringResource(R.string.family_startover_path_unknown),
              style = MaterialTheme.typography.bodyLarge,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.family_startover_step_hidden),
          subtitle = stringResource(R.string.family_startover_step_hidden_detail),
          leading = { RowNumber(2) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.family_startover_step_delete),
          subtitle = familyFolder?.let { stringResource(R.string.family_startover_step_delete_detail, it) }
            ?: stringResource(R.string.family_startover_step_delete_detail_unknown),
          tone = FluidTone.Danger,
          leading = { RowNumber(3) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.family_startover_step_found),
          subtitle = stringResource(R.string.family_startover_step_found_detail),
          tone = FluidTone.Primary,
          leading = { RowNumber(4) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.family_startover_step_others),
          subtitle = stringResource(R.string.family_startover_step_others_detail),
          leading = { RowNumber(5) },
          disclosure = false,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.family_startover_footnote)) }
    item {
      FluidButton(
        text = stringResource(R.string.family_startover_close),
        onClick = onBack,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
}
