package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
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
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile

/**
 * Il kit di recupero spiegato (piano 2.4, E-S13): cos'e', dove sta, quando serve e cosa fare se manca.
 *
 * E' una pagina di sola lettura, **di proposito**: il codice non si puo' rivedere (non e' salvato da nessuna
 * parte, nemmeno sul telefono) e la rotazione del kit e' fuori dal minimo della Fase 2. Qui l'utente trova
 * cosa serve per usarlo e dove andare ([onSecondAdmin], [onReplaceAdmin]) se il dispositivo non e' admin.
 */
@Composable
internal fun RecoveryKitScreen(
  isAdmin: Boolean,
  onBack: () -> Unit,
  onSecondAdmin: () -> Unit,
  onReplaceAdmin: () -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.kit_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Alert, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = FluidHeroTone.TertiaryToPrimary,
        motif = FluidHeroMotif.Ticks,
        eyebrow = stringResource(R.string.kit_eyebrow),
        value = stringResource(R.string.kit_value),
        title = stringResource(R.string.kit_hero_title),
        description = stringResource(R.string.kit_hero_detail),
        icon = Icons.Rounded.Key,
      )
    }

    item { GallerySectionHeader(title = stringResource(R.string.kit_section_what)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.kit_what_code),
          subtitle = stringResource(R.string.kit_what_code_detail),
          tone = FluidTone.Warning,
          leading = { RowTile(Icons.Rounded.Pin) },
          leadingTile = false,
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.kit_what_file),
          subtitle = stringResource(R.string.kit_what_file_detail),
          tone = FluidTone.Info,
          leading = { RowTile(Icons.Rounded.Description) },
          leadingTile = false,
          disclosure = false,
        )
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.kit_section_when)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.kit_when_lost),
          subtitle = stringResource(R.string.kit_when_lost_detail),
          tone = FluidTone.Danger,
          leading = { RowTile(Icons.Rounded.PhonelinkErase) },
          leadingTile = false,
          onClick = if (isAdmin) null else onReplaceAdmin,
          disclosure = !isAdmin,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.kit_when_second),
          subtitle = stringResource(R.string.kit_when_second_detail),
          tone = FluidTone.Primary,
          leading = { RowTile(Icons.Rounded.AdminPanelSettings) },
          leadingTile = false,
          onClick = if (isAdmin) null else onSecondAdmin,
          disclosure = !isAdmin,
        )
      }
    }
    if (isAdmin) {
      item { FluidSectionFootnote(text = stringResource(R.string.kit_admin_footnote)) }
    }

    item {
      Note(
        title = stringResource(R.string.kit_keep_title),
        message = stringResource(R.string.kit_keep_message),
        tone = FluidTone.Warning,
      )
    }
    item {
      Note(
        title = stringResource(R.string.kit_lost_title),
        message = stringResource(R.string.kit_lost_message),
        tone = FluidTone.Info,
      )
    }
  }
}
