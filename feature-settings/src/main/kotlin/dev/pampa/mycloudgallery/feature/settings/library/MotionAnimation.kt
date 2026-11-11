package dev.pampa.mycloudgallery.feature.settings.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 6 (P14, docs/fase6-progetto.md D50, risposta Q16 (b), §2.11): l'interruttore "Animazione delle foto in movimento" nelle Impostazioni della
// Libreria. E' un'impostazione personale sincronizzata fra i dispositivi dell'utente, accesa di default: la pagina non sa dove sta il valore,
// parla con questa porta. `:app` (P11) la lega a `MotionPersonalSettings` di core-personal (implementato da `SyncedMotionSettings`), che questo
// modulo non vede. Senza il collegamento la sezione non compare (D13): niente interruttore che non fa niente.

/** L'interruttore "Animazione delle foto in movimento": acceso di default, lo stesso valore del viewer e di tutti i dispositivi dell'utente. */
interface MotionAnimationSettings {

  /** Acceso: all'apertura di una foto in movimento si riproduce il breve tratto che precede la foto. Spento: mai (il badge "Movimento" funziona sempre). */
  val enabled: StateFlow<Boolean>

  suspend fun setEnabled(on: Boolean)
}

@HiltViewModel
class MotionAnimationViewModel @Inject constructor(
  settings: Optional<MotionAnimationSettings>,
) : ViewModel() {
  private val port: MotionAnimationSettings? = settings.orElse(null)

  /** Null se `:app` non ha collegato l'impostazione: la sezione non si disegna. */
  val enabled: StateFlow<Boolean?> = port?.enabled ?: MutableStateFlow<Boolean?>(null)

  fun set(on: Boolean) {
    val p = port ?: return
    viewModelScope.launch { p.setEnabled(on) }
  }
}

/**
 * La riga dell'interruttore: per TalkBack e' un interruttore con il suo nome (titolo e spiegazione) e il suo stato; la spiegazione dice che il
 * badge "Movimento" resta sempre, anche con l'animazione spenta.
 */
@Composable
internal fun MotionAnimationRow(on: Boolean, onToggle: (Boolean) -> Unit) {
  val title = stringResource(R.string.library_motion_animation)
  val detail = stringResource(R.string.library_motion_animation_detail)
  FluidListRow(
    title = title,
    // I3 (fase I): la spiegazione non e' uno stato che cambia: in pagina c'e' solo il titolo con l'interruttore; TalkBack la legge nel nome.
    subtitle = "",
    tone = if (on) FluidTone.Primary else FluidTone.Neutral,
    leading = { RowTile(Icons.Rounded.MotionPhotosOn) },
    leadingTile = false,
    disclosure = false,
    titleMaxLines = 3,
    badge = { FluidSwitch(checked = on, onCheckedChange = null) },
    onClick = { onToggle(!on) },
    // La riga dell'engine fonde il testo in un nodo figlio che il nodo della riga non include: il nome si scrive qui, come in "Condivisioni".
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      toggleableState = ToggleableState(on)
      contentDescription = "$title. $detail"
    },
  )
}
