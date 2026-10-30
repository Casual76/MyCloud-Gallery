package dev.pampa.mycloudgallery.feature.settings.memories

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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

// LA VOCE "RICORDI E NOTIFICHE" DELLE IMPOSTAZIONI (Fase 9, P9, docs/fase9-progetto.md §1.5 `MemoriesSettingsState`, §2.6, D15, D18; riga P9 del §4.4): una riga con lo stato
// delle notifiche dei ricordi in una frase e un tocco che porta alla pagina Impostazioni -> Ricordi e notifiche. La pagina e' di feature-memories, che le Impostazioni non
// conoscono (i feature non si conoscono): il tocco e' una callback scritta da `:app` (`SettingsLinks.openMemories`, P13); finche' `:app` non la scrive, la voce non compare
// (nessuna riga che non porta da nessuna parte, D13). Righe di Fluid come tutta la pagina: nessuno stile nuovo (D36). D33: la voce dice **come** si e' scelto di ricevere le
// notifiche di questo dispositivo e basta, mai un titolo, un luogo, un nome, un'ora o un numero di ricordi.

/** Come sono le notifiche dei ricordi su questo dispositivo, come le dice la voce. `:app` lo lega a `NotifySettings`. */
enum class MemoriesEntryState {
  /** Di serie (Q4): poche a settimana. */
  FEW_PER_WEEK,

  /** Al piu' una al giorno, quando c'e' un bel ricordo. */
  DAILY,

  /** Solo gli anniversari di viaggi ed eventi. */
  ANNIVERSARIES,

  /** Spente da qui. */
  OFF,
}

/**
 * Cio' che la voce dice: lo stato e se qualcosa non va ([needsAttention]: notifiche accese ma il permesso di Android o il canale "Ricordi" le bloccano, D18: la voce lo
 * segnala con una pillola, senza chiedere niente da sola).
 */
data class MemoriesEntrySummary(val state: MemoriesEntryState, val needsAttention: Boolean = false)

/** Lo stato per la voce. `:app` lo lega a `NotifySettings` e al permesso. **Opzionale per Dagger** ([MemoriesEntryOptionalModule]). */
interface MemoriesEntryStatus {
  val summary: StateFlow<MemoriesEntrySummary>
}

/** Il modulo Hilt delle porte opzionali della voce "Ricordi e notifiche". */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoriesEntryOptionalModule {

  @BindsOptionalOf
  abstract fun memoriesEntryStatus(): MemoriesEntryStatus
}

@HiltViewModel
class MemoriesEntryViewModel @Inject constructor(
  entry: Optional<MemoriesEntryStatus>,
) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato i ricordi. */
  val summary: StateFlow<MemoriesEntrySummary?> = entry.orElse(null)?.summary ?: MutableStateFlow<MemoriesEntrySummary?>(null)
}

/** La riga "Ricordi e notifiche": il titolo, lo stato in una frase e, se serve attenzione, una pillola. [summary] null = non e' ancora legata: la frase dice a cosa serve. */
@Composable
internal fun MemoriesEntryRow(summary: MemoriesEntrySummary?, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(R.string.settings_memories),
    subtitle = MemoriesEntryTexts.subtitleOf(LocalContext.current, summary),
    tone = memoriesEntryTone(summary),
    leading = { RowGlyph(Icons.Rounded.AutoAwesome) },
    // La pillola sta in `labels` (va sotto il titolo se ruberebbe la riga), non in `badge`: a testo grande la frase restava in una colonna di sillabe.
    labels = if (summary?.needsAttention == true) {
      { StatusPill(stringResource(R.string.settings_memories_pill_attention), FluidTone.Warning) }
    } else {
      null
    },
    onClick = onClick,
  )
}

/** Quanto la riga chiede attenzione: un permesso o un canale che bloccano in giallo, notifiche accese in verde, spente in neutro. */
internal fun memoriesEntryTone(summary: MemoriesEntrySummary?): FluidTone = when {
  summary == null -> FluidTone.Primary
  summary.needsAttention -> FluidTone.Warning
  summary.state == MemoriesEntryState.OFF -> FluidTone.Neutral
  else -> FluidTone.Success
}

/** Le frasi della voce, da funzioni pure (si provano senza comporre). */
internal object MemoriesEntryTexts {

  fun subtitleOf(context: Context, summary: MemoriesEntrySummary?): String = context.resources.getString(
    when (summary?.state) {
      null -> R.string.settings_memories_detail
      MemoriesEntryState.FEW_PER_WEEK -> R.string.settings_memories_few
      MemoriesEntryState.DAILY -> R.string.settings_memories_daily
      MemoriesEntryState.ANNIVERSARIES -> R.string.settings_memories_anniversaries
      MemoriesEntryState.OFF -> R.string.settings_memories_off
    },
  )
}
