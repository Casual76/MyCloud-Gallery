package dev.pampa.mycloudgallery.feature.library.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridJumpButton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridLevelBar
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridNotice
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheet
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateKind
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateView
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.SavedGridPosition
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// I pezzi della Libreria: stati, avvisi, piede, chrome in basso, foglio di eliminazione, effetti e salvataggio della
// posizione. LibraryTab.kt li dispone.

// ---------------------------------------------------------------------------------------------
// Scelte ricordate (D4, D16): filtro, stile, livello
// ---------------------------------------------------------------------------------------------

/** Sceglie il filtro rapido e lo ricorda (D16). La griglia resta ancorata allo stesso elemento, se c'e' ancora. */
internal suspend fun LibraryTabState.chooseFilter(filter: LibraryFilter) {
  this.filter = filter
  preferences.setFilter(filter)
}

/** Sceglie lo stile delle celle e lo ricorda (D4). Il cambio resta ancorato. */
internal suspend fun LibraryTabState.chooseStyle(style: GridStyle) {
  grid.style = style
  preferences.setStyle(style)
}

/**
 * Gli effetti della Libreria: le preferenze ricordate entrano nella griglia, il filtro sceglie la vista, i conteggi
 * seguono la vista; in debug, la dimensione della Libreria sintetica scelta per M1.
 */
@Composable
internal fun LibraryEffects(state: LibraryTabState) {
  val grid = state.grid
  LaunchedEffect(state) {
    launch { state.preferences.level.collect { level -> if (!grid.pinching && level != grid.level) grid.level = level } }
    launch { state.preferences.style.collect { style -> grid.style = style } }
    launch { state.preferences.filter.collect { filter -> state.filter = filter } }
  }
  // Il filtro sceglie la vista dalla timeline: le viste con lo stesso filtro condividono il lavoro del repository.
  val timeline = state.timeline
  LaunchedEffect(state, timeline, state.filter) {
    timeline?.view(state.filter)?.collect { view -> grid.view = view }
  }
  // Il riepilogo in fondo conta la vista mostrata (fuori dal thread principale: 100 mila elementi).
  LaunchedEffect(state, grid.view) {
    val view = grid.view
    val videos = withContext(Dispatchers.Default) {
      var count = 0
      for (i in 0 until view.size) if (view.snapshot.kindAt(view.snapshotIndex(i)) == MediaKind.VIDEO) count++
      count
    }
    state.videoCount = videos
    state.photoCount = view.size - videos
  }
  state.debug?.let { DebugSyntheticSize(it) }
}

/**
 * Solo debug (M1): la dimensione della Libreria sintetica si legge da un file nella cartella dei file esterni dell'app
 * (`files/m1-synthetic-count`, un numero), cosi' P9 la puo' impostare da `adb` senza toccare l'interfaccia; e scegliendola
 * dal menu si scrive li'. Vedi `docs/bench/M1.md`.
 */
@Composable
private fun DebugSyntheticSize(hooks: LibraryDebugHooks) {
  val context = LocalContext.current
  LaunchedEffect(hooks) {
    val wanted = withContext(Dispatchers.IO) { debugMarker(context)?.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() }
    if (wanted != null && wanted > 0) hooks.select(wanted)
  }
  LaunchedEffect(hooks, hooks.current) {
    val size = hooks.current
    withContext(Dispatchers.IO) { runCatching { debugMarker(context)?.writeText(size.toString()) } }
  }
}

private fun debugMarker(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, DebugMarkerFile) }

/** Il file con cui `adb` sceglie la dimensione della Libreria sintetica in debug (M1). */
const val DebugMarkerFile: String = "m1-synthetic-count"

// ---------------------------------------------------------------------------------------------
// Posizione salvata (E-P18)
// ---------------------------------------------------------------------------------------------

/** Tiene la posizione salvata fino al primo layout dopo una ricreazione, poi la consegna alla griglia una volta. */
@Stable
internal class PositionKeeper(private var saved: SavedGridPosition?) {
  fun restoreOnce(grid: MediaGridState) {
    val position = saved ?: return
    saved = null
    grid.restorePosition(position)
  }
}

/** Il salvataggio della posizione come elemento ancorato + scostamento (mai come indice). */
internal fun positionSaver(grid: MediaGridState): Saver<PositionKeeper, Any> = Saver(
  save = { _ ->
    grid.savePosition()?.let { arrayListOf(it.mediaId, it.fy, it.screenFraction, it.atNewest) }
  },
  restore = { value ->
    val list = value as? List<*> ?: return@Saver PositionKeeper(null)
    val id = list.getOrNull(0) as? String
    val fy = list.getOrNull(1) as? Float
    val fraction = list.getOrNull(2) as? Float
    val atNewest = list.getOrNull(3) as? Boolean
    if (id == null || fy == null || fraction == null || atNewest == null) PositionKeeper(null) else PositionKeeper(SavedGridPosition(id, fy, fraction, atNewest))
  },
)

// ---------------------------------------------------------------------------------------------
// Stati (§2.4)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun filterLabel(filter: LibraryFilter): String = stringResource(
  when (filter) {
    LibraryFilter.ALL -> R.string.library_filter_all
    LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP -> R.string.library_filter_no_screenshots_whatsapp
    LibraryFilter.SCREENSHOTS -> R.string.library_filter_screenshots
    LibraryFilter.WHATSAPP -> R.string.library_filter_whatsapp
    LibraryFilter.VIDEOS -> R.string.library_filter_videos
    LibraryFilter.FAVORITES -> R.string.library_filter_favorites
  },
)

/** Nessun permesso: perche' la Libreria e' vuota e il tasto che lo risolve (la richiesta parte solo da un tocco). */
@Composable
internal fun LibraryPermissionState(onAllow: () -> Unit, modifier: Modifier = Modifier, footer: @Composable () -> Unit) {
  CenteredState(modifier) {
    GalleryStateView(
      kind = GalleryStateKind.PermissionDenied,
      actionLabel = stringResource(R.string.library_permission_action),
      onAction = onAllow,
    )
    footer()
  }
}

/** La Libreria vuota (accesso completo, niente da mostrare). */
@Composable
internal fun LibraryEmptyState(modifier: Modifier = Modifier, footer: @Composable () -> Unit) {
  CenteredState(modifier) {
    GalleryStateView(kind = GalleryStateKind.LibraryEmpty)
    footer()
  }
}

/** La prima lettura con la Libreria ancora vuota: cosa succede e a che punto si e'. */
@Composable
internal fun LibraryFirstReadState(progress: SyncStatus.Scanning?, modifier: Modifier = Modifier, footer: @Composable () -> Unit) {
  CenteredState(modifier) {
    Column(
      modifier = Modifier.widthIn(max = StateTextMaxWidth).padding(horizontal = GalleryDimens.Gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(StateGap),
    ) {
      GalleryIllustration(icon = Icons.Rounded.PhotoLibrary)
      Text(
        text = stringResource(R.string.library_first_read_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text = stringResource(R.string.library_first_read_detail),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      progress?.let { FirstReadProgress(it) }
    }
    footer()
  }
}

/** Un filtro senza risultati: lo dice e offre "Mostra tutto" (§2.4, D16). */
@Composable
internal fun LibraryFilterEmptyState(filter: LibraryFilter, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
  val title = stringResource(
    when (filter) {
      LibraryFilter.SCREENSHOTS -> R.string.library_filter_empty_screenshots
      LibraryFilter.WHATSAPP -> R.string.library_filter_empty_whatsapp
      LibraryFilter.VIDEOS -> R.string.library_filter_empty_videos
      LibraryFilter.FAVORITES -> R.string.library_filter_empty_favorites
      LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP, LibraryFilter.ALL -> R.string.library_filter_empty_no_screenshots_whatsapp
    },
  )
  CenteredState(modifier) {
    Column(
      modifier = Modifier.widthIn(max = StateTextMaxWidth).padding(horizontal = GalleryDimens.Gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(StateGap),
    ) {
      GalleryIllustration(icon = Icons.Rounded.FilterAltOff)
      Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
      Text(
        text = stringResource(R.string.library_filter_empty_detail),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      FluidButton(text = stringResource(R.string.library_filter_show_all), onClick = onShowAll, size = FluidButtonSize.Large, style = FluidButtonStyle.Tinted)
    }
  }
}

@Composable
private fun CenteredState(modifier: Modifier, content: @Composable () -> Unit) {
  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) { content() }
}

@Composable
private fun FirstReadProgress(scanning: SyncStatus.Scanning) {
  val total = scanning.total
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ProgressGap),
  ) {
    if (total != null && total > 0) {
      FluidProgressBar(progress = { scanning.done.toFloat() / total })
      Text(
        text = stringResource(R.string.library_first_read_progress, scanning.done, total),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(
        text = stringResource(R.string.library_first_read_progress_unknown, scanning.done),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** In fondo alla lista, sotto i piu' recenti: il riepilogo, l'avanzamento della prima lettura, lo stato globale. */
@Composable
internal fun LibraryFooter(state: LibraryTabState, phone: LibraryPhoneStatus, statusFooter: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = FooterPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ProgressGap),
  ) {
    Text(
      text = stringResource(R.string.library_summary, state.photoCount, state.videoCount),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    phone.firstRead?.let { Column(modifier = Modifier.widthIn(max = StateTextMaxWidth)) { FirstReadProgress(it) } }
    statusFooter()
  }
}

/** Gli avvisi sotto la barra: accesso parziale (E-P1) e foto in attesa del permesso della posizione (E-P21). */
@Composable
internal fun LibraryNotices(phone: LibraryPhoneStatus, commands: LibraryCommands, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  val partial = phone.access == MediaAccess.PARTIAL
  val pending = phone.pendingLocation > 0
  if (!partial && !pending) return
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NoticeSpacing), horizontalAlignment = Alignment.CenterHorizontally) {
    if (partial) {
      GalleryGridNotice(
        icon = Icons.Rounded.PhotoLibrary,
        title = stringResource(R.string.library_partial_title),
        message = stringResource(R.string.library_partial_message),
        backdrop = backdrop,
        actionLabel = stringResource(R.string.library_partial_action),
        onAction = { commands.requestMediaAccess() },
      )
    }
    if (pending) {
      GalleryGridNotice(
        icon = Icons.Rounded.LocationOn,
        title = pluralStringResource(R.plurals.library_pending_title, phone.pendingLocation, phone.pendingLocation),
        message = stringResource(R.string.library_pending_message),
        backdrop = backdrop,
        actionLabel = stringResource(R.string.library_pending_action),
        onAction = { commands.requestMediaLocation() },
      )
    }
  }
}

@Composable
internal fun LibraryTodayButton(backdrop: GlassBackdropState, onClick: () -> Unit) {
  GalleryGridJumpButton(
    label = stringResource(R.string.library_jump_today),
    icon = Icons.Rounded.KeyboardDoubleArrowDown,
    onClick = onClick,
    backdrop = backdrop,
    contentDescription = stringResource(R.string.library_jump_today_description),
  )
}

/** I segmenti dei livelli in basso: Anni · Mesi · Giorni · Tutte. */
@Composable
internal fun LibraryLevelBar(level: GridLevel, backdrop: GlassBackdropState, onSelect: (GridLevel) -> Unit) {
  val labels = listOf(
    stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_years),
    stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_months),
    stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_days),
    stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_all),
  )
  GalleryGridLevelBar(labels = labels, selected = level.ordinal, onSelect = { onSelect(GridLevel.entries[it]) }, backdrop = backdrop)
}

/**
 * La barra delle azioni della selezione, in vetro (§2.4, D13): Condividi, Preferito, Nascondi, Elimina dal telefono
 * (D14, solo dove il cestino di sistema c'e'). Con niente di selezionato le azioni sono spente.
 */
@Composable
internal fun LibrarySelectionBar(state: LibraryTabState, backdrop: GlassBackdropState, onDelete: () -> Unit) {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  val selected by grid.selection.selected.collectAsState()
  val any = !selected.isEmpty
  // Anche la vista e' una chiave: un preferito cambiato ripubblica l'indice e l'azione deve dire l'opposto.
  val view = grid.view
  val allFavorite = remember(selected, view) { selected.ids.isNotEmpty() && selected.ids.all { grid.isFavorite(it) } }
  val actions = buildList {
    add(GalleryBarAction(Icons.Rounded.IosShare, stringResource(R.string.library_action_share), { state.commands.share(selected.ids) }, enabled = any))
    add(
      GalleryBarAction(
        icon = if (allFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = stringResource(if (allFavorite) R.string.library_action_unfavorite else R.string.library_action_favorite),
        onClick = { scope.launch { state.actions.setFavorite(selected.ids, !allFavorite) } },
        enabled = any,
      ),
    )
    add(
      GalleryBarAction(
        icon = Icons.Rounded.VisibilityOff,
        contentDescription = stringResource(R.string.library_action_hide),
        onClick = {
          val ids = selected.ids
          scope.launch { state.actions.setHidden(ids, true) }
          grid.stopSelecting()
        },
        enabled = any,
      ),
    )
    if (state.commands.canDeleteFromPhone) {
      add(GalleryBarAction(Icons.Rounded.DeleteOutline, stringResource(R.string.library_action_delete), onDelete, destructive = true, enabled = any))
    }
  }
  GalleryActionBar(actions = actions, backdrop = backdrop)
}

/** Il foglio "Elimina dal telefono" (D14): un posto solo, il telefono, e l'avviso che sul NAS non c'e' ancora una copia. */
@Composable
internal fun LibraryDeleteSheet(visible: Boolean, state: LibraryTabState, onDismiss: () -> Unit) {
  val grid = state.grid
  val selected by grid.selection.selected.collectAsState()
  var scope by remember { mutableStateOf(GalleryDeleteScope.PhoneOnly) }
  GalleryDeleteSheet(
    visible = visible,
    count = selected.size.coerceAtLeast(1),
    scope = scope,
    onScopeChange = { scope = it },
    locations = listOf(
      GalleryDeleteLocation(
        title = stringResource(R.string.library_delete_location_phone),
        kind = GalleryDeleteLocationKind.Phone,
        detail = stringResource(R.string.library_delete_location_phone_detail),
      ),
    ),
    onConfirm = {
      state.commands.deleteFromPhone(selected.ids)
      onDismiss()
      grid.stopSelecting()
    },
    onDismissRequest = onDismiss,
    scopes = listOf(GalleryDeleteScope.PhoneOnly),
  )
}

/** Se [id] e' un preferito (dai flag dell'indice). */
internal fun MediaGridState.isFavorite(id: MediaId): Boolean {
  // L'indice piu' recente (la vista corrente): un preferito appena cambiato ripubblica l'indice con lo stesso ordine.
  val snapshot = view.snapshot
  val index = indexOf(id).takeIf { it in 0 until snapshot.size } ?: snapshot.indexOf(id)
  return index >= 0 && CellFlags.has(snapshot.flagsAt(index), CellFlags.FAVORITE)
}

private val StateTextMaxWidth = 340.dp
private val StateGap = 12.dp
private val ProgressGap = 6.dp
private val FooterPadding = 16.dp
private val NoticeSpacing = 8.dp
