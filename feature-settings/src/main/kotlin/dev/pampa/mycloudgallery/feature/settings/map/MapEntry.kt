package dev.pampa.mycloudgallery.feature.settings.map

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
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
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LA VOCE "MAPPA" DELLE IMPOSTAZIONI (Fase 9, P4, docs/fase9-progetto.md §2.8, D24, §1.5 `MapPrivacyState`, **Q1 (c)**; riga P4 del §4.4): una riga con lo stato della mappa dettagliata di questo dispositivo in una frase e un tocco che
// porta alla pagina Impostazioni -> Mappa (interruttore, nota su cosa esce, "Solo sul Wi-Fi", "Zone senza Internet"). La pagina e' di feature-map, che le Impostazioni non conoscono (i feature non si conoscono): il tocco e'
// una callback scritta da `:app` (`SettingsLinks.openMap`, P13); finche' `:app` non la scrive, la voce non compare (nessuna riga che non porta da nessuna parte, D13). Sta nella sezione "Protezione" perche' decide cosa
// esce dal telefono. Righe di Fluid come tutta la pagina: nessuno stile nuovo (D36). D33: la voce dice **solo** se la mappa dettagliata e' accesa, spenta o assente, mai un luogo, una zona, un nome o un numero.

/** Com'e' la mappa dettagliata su questo dispositivo, come la dice la voce. `:app` lo lega a `MapSettings.online`. */
enum class MapEntryState {
  /** Accesa (di serie, Q1 (c)). */
  ON,

  /** Spenta qui dall'utente: resta la carta semplice dentro l'app. */
  OFF,

  /** L'interruttore di build e' spento: la mappa dettagliata in questa versione non c'e'. */
  UNAVAILABLE,
}

/** Cio' che la voce dice: lo stato. */
data class MapEntrySummary(val state: MapEntryState)

/** Lo stato per la voce. `:app` lo lega a `MapSettings`. **Opzionale per Dagger** ([MapEntryOptionalModule]). */
interface MapEntryStatus {
  val summary: StateFlow<MapEntrySummary>
}

/** Il modulo Hilt delle porte opzionali della voce "Mappa". */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapEntryOptionalModule {

  @BindsOptionalOf
  abstract fun mapEntryStatus(): MapEntryStatus
}

@HiltViewModel
class MapEntryViewModel @Inject constructor(
  entry: Optional<MapEntryStatus>,
) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato la mappa. */
  val summary: StateFlow<MapEntrySummary?> = entry.orElse(null)?.summary ?: MutableStateFlow<MapEntrySummary?>(null)
}

/** La riga "Mappa": il titolo e lo stato in una frase. [summary] null = non e' ancora legata: la frase dice a cosa serve. */
@Composable
internal fun MapEntryRow(summary: MapEntrySummary?, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(R.string.settings_map),
    subtitle = MapEntryTexts.subtitleOf(LocalContext.current, summary),
    tone = mapEntryTone(summary),
    leading = { RowGlyph(Icons.Rounded.Map) },
    onClick = onClick,
  )
}

/** Il tono sulla piastrella: la mappa dettagliata accesa e' un'informazione, non un allarme; spenta o assente e' neutra; senza legame e' quella delle altre voci della sezione. */
internal fun mapEntryTone(summary: MapEntrySummary?): FluidTone = when (summary?.state) {
  null -> FluidTone.Info
  MapEntryState.ON -> FluidTone.Info
  MapEntryState.OFF, MapEntryState.UNAVAILABLE -> FluidTone.Neutral
}

/** Le frasi della voce, da funzioni pure (si provano senza comporre). */
internal object MapEntryTexts {

  fun subtitleOf(context: Context, summary: MapEntrySummary?): String = context.resources.getString(
    when (summary?.state) {
      null -> R.string.settings_map_detail
      MapEntryState.ON -> R.string.settings_map_on
      MapEntryState.OFF -> R.string.settings_map_off
      MapEntryState.UNAVAILABLE -> R.string.settings_map_unavailable
    },
  )
}
