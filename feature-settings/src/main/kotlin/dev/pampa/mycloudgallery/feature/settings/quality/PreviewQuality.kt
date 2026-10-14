package dev.pampa.mycloudgallery.feature.settings.quality

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// "Qualita' delle anteprime" (Fase 4A, P11; docs/fase4-progetto.md D16, E-L6, §1.5 `ReplicaQualityState`) e "Mostra le
// copertine degli album" (D11, E-N14). La Fase 4 decide cosa dire (livello, stima per livello, spazio occupato, se il
// telefono sta convergendo); la composable e' funzionale (componenti di engine-ui gia' usati qui, nessuno stile nuovo).

/** I livelli di qualita' (D16): Risparmio = miniature S; Standard = S + M; Alta = S + M + HQ (viewer nitido anche offline). */
enum class PreviewLevel { SAVER, STANDARD, HIGH }

/** Una stima di spazio per un livello: byte e numero di anteprime (dai byte delle classi nelle teste, E-L6). */
@Immutable
data class PreviewEstimate(val bytes: Long, val items: Int)

/**
 * Lo stato della qualita' per le Impostazioni.
 *
 * @property currentBytes lo spazio occupato adesso dalle anteprime sul telefono.
 * @property converging il telefono sta ancora scaricando o liberando spazio per il livello scelto (piano, al massimo 200 MB
 *   tolti per giro).
 */
@Immutable
data class PreviewQualityState(
  val level: PreviewLevel,
  val estimates: Map<PreviewLevel, PreviewEstimate>,
  val currentBytes: Long,
  val converging: Boolean,
  val showAlbumCovers: Boolean,
)

/**
 * La porta che `:app` lega al grafo del NAS (`NasGraph`: `ReplicaQuality` di core-index e le impostazioni della scansione).
 * Opzionale per Dagger: senza NAS la pagina dice che le anteprime del NAS non sono attive.
 */
interface PreviewQualitySettings {
  /** Null finche' il NAS nella Libreria non e' partito (nessun NAS configurato). */
  val state: StateFlow<PreviewQualityState?>

  suspend fun choose(level: PreviewLevel)

  suspend fun showAlbumCovers(show: Boolean)
}

@HiltViewModel
class PreviewQualityViewModel @Inject constructor(
  settings: Optional<PreviewQualitySettings>,
) : ViewModel() {
  private val port: PreviewQualitySettings? = settings.orElse(null)

  val state: StateFlow<PreviewQualityState?> = port?.state ?: MutableStateFlow(null)

  fun choose(level: PreviewLevel) {
    val p = port ?: return
    viewModelScope.launch { p.choose(level) }
  }

  fun showAlbumCovers(show: Boolean) {
    val p = port ?: return
    viewModelScope.launch { p.showAlbumCovers(show) }
  }
}

@Composable
internal fun PreviewQualityRoute(onBack: () -> Unit, viewModel: PreviewQualityViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  PreviewQualityScreen(state = state, onBack = onBack, onChoose = viewModel::choose, onShowCovers = viewModel::showAlbumCovers)
}

/** La pagina: i tre livelli con la loro stima, lo spazio di adesso, le copertine degli album. */
@Composable
fun PreviewQualityScreen(
  state: PreviewQualityState?,
  onBack: () -> Unit,
  onChoose: (PreviewLevel) -> Unit,
  onShowCovers: (Boolean) -> Unit,
) {
  val context = LocalContext.current
  fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
  FluidScreen(title = stringResource(R.string.quality_title), onBack = onBack) {
    if (state == null) {
      item { FluidSectionFootnote(text = stringResource(R.string.quality_not_active)) }
      return@FluidScreen
    }
    item { FluidSectionHeader(title = stringResource(R.string.quality_section_level)) }
    item {
      FluidListGroup {
        PreviewLevel.entries.forEachIndexed { index, level ->
          if (index > 0) FluidListDivider()
          val estimate = state.estimates[level]
          FluidListRow(
            title = stringResource(titleOf(level)),
            subtitle = stringResource(detailOf(level)),
            meta = estimate?.let { stringResource(R.string.quality_estimate, size(it.bytes)) },
            tone = if (level == state.level) FluidTone.Primary else FluidTone.Neutral,
            leading = { RowGlyph(iconOf(level)) },
            badge = { FluidSwitch(checked = level == state.level, onCheckedChange = null) },
            selected = level == state.level,
            onClick = { if (level != state.level) onChoose(level) },
          )
        }
      }
    }
    item {
      FluidSectionFootnote(
        text = if (state.converging) {
          stringResource(R.string.quality_converging, size(state.currentBytes))
        } else {
          stringResource(R.string.quality_current, size(state.currentBytes))
        },
      )
    }
    item { FluidSectionHeader(title = stringResource(R.string.quality_section_covers)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.quality_covers),
          subtitle = stringResource(R.string.quality_covers_detail),
          tone = FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Album) },
          badge = { FluidSwitch(checked = state.showAlbumCovers, onCheckedChange = null) },
          onClick = { onShowCovers(!state.showAlbumCovers) },
        )
      }
    }
    item {
      val counts = state.estimates[state.level]?.items ?: 0
      FluidSectionFootnote(text = pluralStringResource(R.plurals.quality_items, counts, counts))
    }
  }
}

private fun titleOf(level: PreviewLevel): Int = when (level) {
  PreviewLevel.SAVER -> R.string.quality_saver
  PreviewLevel.STANDARD -> R.string.quality_standard
  PreviewLevel.HIGH -> R.string.quality_high
}

private fun detailOf(level: PreviewLevel): Int = when (level) {
  PreviewLevel.SAVER -> R.string.quality_saver_detail
  PreviewLevel.STANDARD -> R.string.quality_standard_detail
  PreviewLevel.HIGH -> R.string.quality_high_detail
}

private fun iconOf(level: PreviewLevel) = when (level) {
  PreviewLevel.SAVER -> Icons.Rounded.PhotoSizeSelectSmall
  PreviewLevel.STANDARD -> Icons.Rounded.Photo
  PreviewLevel.HIGH -> Icons.Rounded.HighQuality
}
