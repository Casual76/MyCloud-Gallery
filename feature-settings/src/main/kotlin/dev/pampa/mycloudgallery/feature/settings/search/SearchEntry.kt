package dev.pampa.mycloudgallery.feature.settings.search

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
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

// La voce "Ricerca e modelli" delle Impostazioni (Fase 6, P8, docs/fase6-progetto.md §2.6, D15, D41): una riga con lo stato in una frase e un tocco che porta
// alla pagina Ricerca e modelli. La pagina e' di feature-search, che le Impostazioni non conoscono (i feature non si conoscono): il tocco e' una callback scritta
// da `:app` (`SettingsLinks.openSearch`, P11); finche' `:app` non la scrive, la voce non compare (nessuna riga che non porta da nessuna parte, D13).
// Righe di Fluid come tutta la pagina: nessuno stile nuovo (D36).

/** Cio' che la voce dice, in numeri e stati: mai testi, luoghi o nomi (D40). */
data class SearchEntrySummary(
  /** Questo dispositivo analizza le foto per tutti (D15). */
  val indexer: Boolean,
  /** La lettura del testo nelle foto e' attiva qui (D41): attiva per l'account e non esclusa. */
  val textReadingHere: Boolean,
  /** Manca un modello che serve: lo dice una pillola. */
  val modelMissing: Boolean,
  /**
   * La lettura del testo e' accesa per l'account ma questo dispositivo non ha ancora visto l'avviso (LT-2, R8): ML Kit qui non parte finche'
   * non si conferma o ci si esclude. La voce lo segnala, cosi' l'avviso si vede senza cercarlo.
   */
  val readingNeedsNotice: Boolean = false,
)

/** Lo stato per la voce. `:app` lo lega a `IndexerControl`, `TextReadingSwitch` e `ModelManager`. **Opzionale per Dagger** ([SearchEntryOptionalModule]). */
interface SearchEntryStatus {
  val summary: StateFlow<SearchEntrySummary>
}

/** Il modulo Hilt delle porte opzionali della voce "Ricerca e modelli". */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchEntryOptionalModule {

  @BindsOptionalOf
  abstract fun searchEntryStatus(): SearchEntryStatus
}

@HiltViewModel
class SearchEntryViewModel @Inject constructor(
  entry: Optional<SearchEntryStatus>,
) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato la ricerca. */
  val summary: StateFlow<SearchEntrySummary?> = entry.orElse(null)?.summary ?: MutableStateFlow<SearchEntrySummary?>(null)
}

/** La riga "Ricerca e modelli": il titolo, lo stato in una frase e, se serve attenzione, una pillola. [summary] null = non e' ancora legata: la frase dice a cosa serve. */
@Composable
internal fun SearchEntryRow(summary: SearchEntrySummary?, onClick: () -> Unit) {
  val tone = searchEntryTone(summary)
  FluidListRow(
    title = stringResource(R.string.settings_search),
    subtitle = SearchEntryTexts.subtitleOf(LocalContext.current, summary),
    tone = tone,
    leading = { RowGlyph(Icons.Rounded.ImageSearch) },
    badge = when {
      summary?.modelMissing == true -> {
        { StatusPill(stringResource(R.string.settings_search_pill_model), FluidTone.Warning) }
      }
      summary?.readingNeedsNotice == true -> {
        { StatusPill(stringResource(R.string.settings_search_pill_notice), FluidTone.Warning) }
      }
      else -> null
    },
    onClick = onClick,
  )
}

/** Quanto la riga chiede attenzione: un modello mancante in giallo, un dispositivo che analizza in azzurro, il resto in neutro. */
internal fun searchEntryTone(summary: SearchEntrySummary?): FluidTone = when {
  summary == null -> FluidTone.Primary
  summary.modelMissing || summary.readingNeedsNotice -> FluidTone.Warning
  summary.indexer -> FluidTone.Info
  else -> FluidTone.Neutral
}

/** Le frasi della voce, da funzioni pure (si provano senza comporre). */
internal object SearchEntryTexts {

  fun subtitleOf(context: Context, summary: SearchEntrySummary?): String {
    val res = context.resources
    if (summary == null) return res.getString(R.string.settings_search_detail)
    val role = res.getString(if (summary.indexer) R.string.settings_search_indexer_on else R.string.settings_search_indexer_off)
    val reading = res.getString(
      when {
        summary.textReadingHere -> R.string.settings_search_reading_on
        summary.readingNeedsNotice -> R.string.settings_search_reading_notice
        else -> R.string.settings_search_reading_off
      },
    )
    return res.getString(R.string.settings_search_summary, role, reading)
  }
}
