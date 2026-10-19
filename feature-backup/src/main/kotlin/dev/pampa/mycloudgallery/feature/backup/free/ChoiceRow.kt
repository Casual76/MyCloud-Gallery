package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone

// Fase 5B (P12): una scelta fra piu' (raggio) per i fogli e le pagine della 5B: il filtro di "Libera spazio", il luogo di una correzione, cosa
// fare di un media non ancora al sicuro. La forma e' quella dei fogli del design system (`GalleryDeleteSheetContent`): una riga da 48 dp o piu'
// con la conseguenza scritta sotto, quella scelta evidenziata e il glifo pieno. Per TalkBack e' un raggio: ruolo e stato detti a parole.

/** Una riga-scelta: [title] e [detail] (vuoto = niente sotto), [selected] la evidenzia, [onSelect] e' il tocco. */
@Composable
internal fun BackupChoiceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = detail,
    tone = if (selected) FluidTone.Primary else FluidTone.Neutral,
    leading = { Icon(imageVector = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, contentDescription = null) },
    selected = selected,
    disclosure = false,
    titleMaxLines = 3,
    onClick = onSelect,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
    },
  )
}
