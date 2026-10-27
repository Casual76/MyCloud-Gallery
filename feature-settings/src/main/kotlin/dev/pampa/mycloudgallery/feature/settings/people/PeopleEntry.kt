package dev.pampa.mycloudgallery.feature.settings.people

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LA VOCE "PERSONE" DELLE IMPOSTAZIONI (Fase 8, P7, docs/fase8-progetto.md §1.3 "feature-settings", §2.1, D3, D4): una riga con lo stato di Persone su questo
// dispositivo in una frase e un tocco che porta alla pagina Impostazioni -> Persone. La pagina e' di feature-people, che le Impostazioni non conoscono (i
// feature non si conoscono): il tocco e' una callback scritta da `:app` (`SettingsLinks.openPeople`, P13); finche' `:app` non la scrive, la voce non
// compare (nessuna riga che non porta da nessuna parte, D13). Righe di Fluid come tutta la pagina: nessuno stile nuovo (D36).
// D37 e Q3: la voce dice lo stato di QUESTO dispositivo e basta, mai nomi, id o conteggi di persone, e nessuna parola sulla famiglia.

/** Lo stato di Persone su questo dispositivo, come lo dice la voce. `:app` lo lega allo stato dell'interruttore dei volti. */
enum class PeopleEntryState {
  /** Spenta per l'account: la voce spiega cosa fa. */
  OFF,

  /** Attiva per l'account e qui. */
  ON,

  /** Attiva qui, ma ogni dispositivo ha le sue persone (la recinzione delle cartelle vere, D34). */
  LOCAL_ONLY,

  /** L'account l'ha attivata altrove e qui l'avviso non e' ancora stato visto: qui non lavora finche' l'utente non sceglie. */
  AWAITING_NOTICE,

  /** "Non su questo dispositivo". */
  EXCLUDED_HERE,

  /** "Disattiva e cancella" in corso. */
  ERASING,

  /** La voce dell'account e' di un'app piu' nuova. */
  NEEDS_UPDATE,
}

/** Cio' che la voce dice: lo stato, e basta (D37). */
data class PeopleEntrySummary(val state: PeopleEntryState)

/** Lo stato per la voce. `:app` lo lega a `FacesSwitch`. **Opzionale per Dagger** ([PeopleEntryOptionalModule]). */
interface PeopleEntryStatus {
  val summary: StateFlow<PeopleEntrySummary>
}

/** Il modulo Hilt delle porte opzionali della voce "Persone". */
@Module
@InstallIn(SingletonComponent::class)
abstract class PeopleEntryOptionalModule {

  @BindsOptionalOf
  abstract fun peopleEntryStatus(): PeopleEntryStatus
}

@HiltViewModel
class PeopleEntryViewModel @Inject constructor(
  entry: Optional<PeopleEntryStatus>,
) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato Persone. */
  val summary: StateFlow<PeopleEntrySummary?> = entry.orElse(null)?.summary ?: MutableStateFlow<PeopleEntrySummary?>(null)
}

/** La riga "Persone": il titolo, lo stato in una frase e, se serve attenzione, una pillola. [summary] null = non e' ancora legata: la frase dice a cosa serve. */
@Composable
internal fun PeopleEntryRow(summary: PeopleEntrySummary?, onClick: () -> Unit) {
  val pill = when (summary?.state) {
    PeopleEntryState.AWAITING_NOTICE -> R.string.settings_people_pill_notice
    PeopleEntryState.NEEDS_UPDATE -> R.string.settings_people_pill_update
    else -> null
  }
  FluidListRow(
    title = stringResource(R.string.settings_people),
    subtitle = PeopleEntryTexts.subtitleOf(LocalContext.current, summary),
    tone = peopleEntryTone(summary),
    leading = { RowGlyph(Icons.Rounded.Face) },
    // Le pillole stanno in `labels` (vanno sotto il titolo se ruberebbero la riga), non in `badge`: a testo grande la frase restava in una colonna di sillabe.
    labels = pill?.let { label -> { StatusPill(stringResource(label), FluidTone.Warning) } },
    onClick = onClick,
  )
}

/** Quanto la riga chiede attenzione: l'avviso da vedere o l'app da aggiornare in giallo, una funzione accesa in verde o in azzurro, il resto in neutro. */
internal fun peopleEntryTone(summary: PeopleEntrySummary?): FluidTone = when (summary?.state) {
  null, PeopleEntryState.OFF -> FluidTone.Primary
  PeopleEntryState.ON -> FluidTone.Success
  PeopleEntryState.LOCAL_ONLY -> FluidTone.Info
  PeopleEntryState.AWAITING_NOTICE, PeopleEntryState.NEEDS_UPDATE, PeopleEntryState.ERASING -> FluidTone.Warning
  PeopleEntryState.EXCLUDED_HERE -> FluidTone.Neutral
}

/** Le frasi della voce, da funzioni pure (si provano senza comporre). */
internal object PeopleEntryTexts {

  fun subtitleOf(context: Context, summary: PeopleEntrySummary?): String = context.resources.getString(
    when (summary?.state) {
      null, PeopleEntryState.OFF -> R.string.settings_people_detail
      PeopleEntryState.ON -> R.string.settings_people_on
      PeopleEntryState.LOCAL_ONLY -> R.string.settings_people_local
      PeopleEntryState.AWAITING_NOTICE -> R.string.settings_people_awaiting
      PeopleEntryState.EXCLUDED_HERE -> R.string.settings_people_excluded
      PeopleEntryState.ERASING -> R.string.settings_people_erasing
      PeopleEntryState.NEEDS_UPDATE -> R.string.settings_people_update
    },
  )
}
