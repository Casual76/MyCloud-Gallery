package dev.pampa.mycloudgallery.feature.settings.sharing

import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.feature.settings.ui.toneMark
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.3, §2.7, D26, E-X5, C18, L41): la pagina "Condivisioni" delle Impostazioni, con l'interruttore "Rimuovi la
// posizione" e, accanto, cosa succede davvero (la copia si controlla; se la posizione non si toglie la condivisione si ferma e lo dice; i formati che
// si sanno trattare). Solo componenti di Fluid come le altre pagine, nessuno stile nuovo, nessun dato personale. Il viewer dice "Condividi senza
// posizione" quando e' acceso: e' l'indicatore.

@HiltViewModel
class SharingViewModel @Inject constructor(private val settings: SharingSettings) : ViewModel() {

  /** Acceso: ogni condivisione toglie la posizione. */
  val removeLocation: StateFlow<Boolean> = settings.removeLocation

  fun setRemoveLocation(on: Boolean) {
    viewModelScope.launch { settings.setRemoveLocation(on) }
  }
}

@Composable
internal fun SharingRoute(onBack: () -> Unit, viewModel: SharingViewModel = hiltViewModel()) {
  val on by viewModel.removeLocation.collectAsStateWithLifecycle()
  SharingScreen(removeLocation = on, onBack = onBack, onToggle = viewModel::setRemoveLocation)
}

/** La pagina per uno stato dato: l'interruttore, cosa fa e cosa non si puo' fare. Nessun effetto. */
@Composable
fun SharingScreen(removeLocation: Boolean, onBack: () -> Unit, onToggle: (Boolean) -> Unit) {
  val switchTitle = stringResource(R.string.sharing_remove_location)
  val switchDetail = stringResource(R.string.sharing_remove_location_detail)
  FluidScreen(title = stringResource(R.string.sharing_title), onBack = onBack) {
    item { GallerySectionHeader(title = stringResource(R.string.sharing_section_location)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = switchTitle,
          subtitle = switchDetail,
          tone = if (removeLocation) FluidTone.Success else FluidTone.Neutral,
          leading = { RowTile(Icons.Rounded.LocationOff) },
          leadingTile = false,
          disclosure = false,
          titleMaxLines = 3,
          badge = { FluidSwitch(checked = removeLocation, onCheckedChange = null) },
          onClick = { onToggle(!removeLocation) },
          // Per TalkBack e' un interruttore, con il suo stato e il suo nome: la riga dell'engine fonde il testo in un nodo figlio, che il nodo
          // della riga non include, quindi il nome si scrive qui (senza, il primo punto di lettura sarebbe un "interruttore" senza nome).
          modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(removeLocation)
            contentDescription = "$switchTitle. $switchDetail"
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(if (removeLocation) R.string.sharing_state_on else R.string.sharing_state_off),
          subtitle = stringResource(if (removeLocation) R.string.sharing_state_on_detail else R.string.sharing_state_off_detail),
          tone = if (removeLocation) FluidTone.Success else FluidTone.Warning,
          leading = { RowTile(if (removeLocation) Icons.Rounded.Verified else Icons.Rounded.IosShare) },
          leadingTile = false,
          disclosure = false,
          titleMaxLines = 3,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.sharing_footnote_check)) }
    item { FluidSectionFootnote(text = stringResource(R.string.sharing_footnote_formats)) }
  }
}

/** La riga "Condivisioni" nella pagina principale delle Impostazioni: dice in una frase se la posizione si toglie (tono verde quando si toglie). */
@Composable
internal fun SharingEntryRow(removeLocation: Boolean, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(R.string.sharing_title),
    // I3 (fase I): lo stato e' un valore corto a destra.
    subtitle = "",
    badge = {
      Icon(
        imageVector = if (removeLocation) Icons.Rounded.LocationOff else Icons.Rounded.LocationOn,
        contentDescription = stringResource(if (removeLocation) R.string.sharing_entry_on else R.string.sharing_entry_off),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    tone = if (removeLocation) FluidTone.Success else FluidTone.Neutral,
    leading = { RowTile(Icons.Rounded.IosShare, status = toneMark(if (removeLocation) FluidTone.Success else FluidTone.Neutral)) },
    leadingTile = false,
    onClick = onClick,
  )
}

/** Il valore dell'impostazione per la pagina principale. */
@HiltViewModel
class SharingEntryViewModel @Inject constructor(settings: SharingSettings) : ViewModel() {
  val removeLocation: StateFlow<Boolean> = settings.removeLocation
}
