package dev.pampa.mycloudgallery.feature.settings.update

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LA VOCE "AGGIORNAMENTI" DELLE IMPOSTAZIONI (pacchetto U, 12/11/2026). La pagina e' di feature-update, che le Impostazioni non conoscono (i feature non si
// conoscono): il tocco e' una callback scritta da `:app` (`SettingsLinks.openUpdates`); finche' `:app` non la scrive la voce non compare. Quando c'e' una
// versione nuova la voce ha il segno (il punto esclamativo sulla piastrella) e la versione a destra: e' «il segno sulla voce» dell'avviso all'apertura.

/** Che cosa dice la voce: la versione nuova trovata, o null (niente di nuovo, o non si sa). */
data class UpdateEntrySummary(val available: String?)

/** Lo stato per la voce. `:app` lo lega ad `AppUpdates`. **Opzionale per Dagger** ([UpdateEntryOptionalModule]). */
interface UpdateEntryStatus {
  val summary: StateFlow<UpdateEntrySummary>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateEntryOptionalModule {

  @BindsOptionalOf
  abstract fun updateEntryStatus(): UpdateEntryStatus
}

@HiltViewModel
class UpdateEntryViewModel @Inject constructor(entry: Optional<UpdateEntryStatus>) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato l'aggiornamento. */
  val summary: StateFlow<UpdateEntrySummary?> = entry.orElse(null)?.summary ?: MutableStateFlow<UpdateEntrySummary?>(null)
}

/** La riga "Aggiornamenti": la piastrella dell'app (con il segno se c'e' una versione nuova) e la versione nuova a destra. */
@Composable
internal fun UpdateEntryRow(summary: UpdateEntrySummary?, onClick: () -> Unit) {
  val title = stringResource(R.string.settings_updates)
  val available = summary?.available
  val news = available?.let { stringResource(R.string.settings_updates_available, it) }
  FluidListRow(
    title = title,
    subtitle = "",
    badge = if (available != null) ({ RowValue(available) }) else null,
    leading = { RowTile(Icons.Rounded.SystemUpdate, status = if (available != null) GalleryIconStatus.Attention else null, family = GalleryIconFamily.Photos) },
    leadingTile = false,
    onClick = onClick,
    modifier = Modifier.semantics { contentDescription = if (news != null) "$title, $news" else title },
  )
}
