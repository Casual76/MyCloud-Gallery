package dev.pampa.mycloudgallery.feature.library.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.IosShare
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
import androidx.compose.runtime.snapshotFlow
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
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridNotice
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateKind
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateView
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.ActionNoticeCard
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.attempt
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.familyNoticeAfterHide
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.SavedGridPosition
import dev.pampa.mycloudgallery.feature.library.grid.SavedSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// I pezzi della Libreria: stati, avvisi, piede, azioni della selezione, fogli, effetti e salvataggio della posizione.
// LibraryScreen.kt li dispone.

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
 * Nasconde [ids] e, **solo se la scrittura e' riuscita**, chiude la selezione (R8). Se non riesce (disco pieno, database in
 * errore) la Libreria lo dice e la selezione resta aperta: non si fa credere all'utente che le foto private siano
 * nascoste quando tornano in griglia. Se riesce ma i familiari vedono ancora qualcuno degli elementi (area comune, album condivisi)
 * lo dice (E-H2, D23): Nascosti protegge solo dentro l'app.
 */
internal suspend fun LibraryTabState.hide(ids: Set<MediaId>) {
  if (attempt { actions.setHidden(ids, true) }) {
    grid.stopSelecting()
    familyNoticeAfterHide(actions, ids)?.let { notice = it }
  } else {
    notice = ActionNotice.HideFailed
  }
}

/**
 * Condividi: oltre il tetto del selettore di sistema si avvisa senza provarci (I8); se Android non lo apre, lo si dice. Si
 * condivide solo cio' che ha una copia sul telefono (GA-8): se la selezione e' tutta sul NAS si dice come fare, se e' mista
 * parte il selettore con quelli del telefono e si dice quanti non ci sono.
 */
internal fun LibraryTabState.shareSelection(ids: Set<MediaId>) {
  if (ids.isEmpty()) return
  val limit = commands.shareLimit
  val onPhone = commands.onPhone(ids)
  when {
    onPhone.isEmpty() -> notice = ActionNotice.ShareNasOnly
    onPhone.size > limit -> notice = ActionNotice.ShareTooMany(limit)
    !commands.share(onPhone) -> notice = ActionNotice.ShareFailed
    onPhone.size < ids.size -> notice = ActionNotice.SharePartial(ids.size - onPhone.size)
  }
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
  // Prima si legge, poi si scrive: con due effetti separati la scrittura del valore iniziale (la Libreria del telefono,
  // P11) poteva arrivare prima della lettura e cancellare la scelta fatta da `adb`.
  LaunchedEffect(hooks) {
    val wanted = withContext(Dispatchers.IO) { debugMarker(context)?.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() }
    if (wanted != null && wanted > 0) hooks.select(wanted)
    snapshotFlow { hooks.current }.collect { size ->
      withContext(Dispatchers.IO) { runCatching { debugMarker(context)?.writeText(size.toString()) } }
    }
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

/**
 * Tiene la selezione salvata finche' la vista ha degli elementi, poi la consegna alla griglia una volta (Fase 10, P4, D14, E-T8). Aspetta la vista perche' `GridEffects` toglie dalla
 * selezione chi non e' nella vista: rimessa a vista ancora vuota, sparirebbe subito.
 */
@Stable
internal class SelectionKeeper(private var saved: SavedSelection?) {
  suspend fun restoreWhenReady(grid: MediaGridState) {
    val selection = saved ?: return
    snapshotFlow { grid.view.size }.first { it > 0 }
    saved = null
    grid.restoreSelection(selection)
  }
}

/**
 * Il salvataggio della selezione (E-T8): la modalita' selezione e gli id, fino a [SavedSelection.MAX_IDS]; oltre, o con tutta la vista selezionata, il solo "tutti". Una prima
 * voce booleana, poi gli id come testo.
 */
internal fun selectionSaver(grid: MediaGridState): Saver<SelectionKeeper, Any> = Saver(
  save = { _ -> grid.saveSelection()?.let { saved -> ArrayList<Any>(saved.ids.size + 1).apply { add(saved.all); addAll(saved.ids) } } },
  restore = { value ->
    val list = value as? List<*>
    val all = list?.getOrNull(0) as? Boolean
    if (list == null || all == null) {
      SelectionKeeper(null)
    } else {
      SelectionKeeper(SavedSelection(ids = list.drop(1).filterIsInstance<String>(), all = all))
    }
  },
)

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

/**
 * I filtri rapidi del menu della vista, nell'ordine in cui compaiono: tutti, "Documenti" compreso (Fase 6, P8, D34). Fase 7 (P0, D24):
 * "Modificate" ([LibraryFilter.EDITED]) e' una pagina dell'Utility, non un chip della Libreria: resta fuori (lo decide P8).
 */
internal val QuickFilters: List<LibraryFilter> = LibraryFilter.entries.filter { it != LibraryFilter.EDITED }

@Composable
internal fun filterLabel(filter: LibraryFilter): String = stringResource(
  when (filter) {
    LibraryFilter.ALL -> R.string.library_filter_all
    LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP -> R.string.library_filter_no_screenshots_whatsapp
    LibraryFilter.SCREENSHOTS -> R.string.library_filter_screenshots
    LibraryFilter.WHATSAPP -> R.string.library_filter_whatsapp
    LibraryFilter.VIDEOS -> R.string.library_filter_videos
    LibraryFilter.FAVORITES -> R.string.library_filter_favorites
    LibraryFilter.DOCUMENTS -> R.string.library_filter_documents
    LibraryFilter.EDITED -> R.string.library_filter_edited
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
      LibraryFilter.DOCUMENTS -> R.string.library_filter_empty_documents
      LibraryFilter.EDITED -> R.string.library_filter_empty_edited
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
        text = stringResource(R.string.library_first_read_progress, GalleryNumbers.format(scanning.done), GalleryNumbers.format(total)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(
        text = countText(R.plurals.library_first_read_progress_unknown, scanning.done),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * In fondo alla lista, sotto i piu' recenti: il riepilogo, l'avanzamento della prima lettura, e lo stato della rete e del
 * NAS ([status], `LibraryStatusBlock`).
 */
@Composable
internal fun LibraryFooter(state: LibraryTabState, phone: LibraryPhoneStatus, status: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = FooterPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ProgressGap),
  ) {
    Text(
      text = photoVideoSummary(state.photoCount, state.videoCount),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    phone.firstRead?.let { Column(modifier = Modifier.widthIn(max = StateTextMaxWidth)) { FirstReadProgress(it) } }
    status()
  }
}

/** Se [LibraryNotices] mostra qualcosa: serve a chi misura la loro altezza per sapere quando e' tornata a zero. */
internal fun hasNotices(phone: LibraryPhoneStatus, notice: ActionNotice?, share: LibraryShare = LibraryShare.Idle): Boolean =
  phone.access == MediaAccess.PARTIAL || phone.pendingLocation > 0 || notice != null || share is LibraryShare.Preparing

/**
 * Gli avvisi sotto la barra: accesso parziale (E-P1), foto in attesa del permesso della posizione (E-P21) e, in cima, l'azione
 * dell'utente che non e' riuscita o non e' partita ([notice], R8 e I8) e la condivisione senza posizione che si prepara ([share], R4).
 */
@Composable
internal fun LibraryNotices(
  phone: LibraryPhoneStatus,
  commands: LibraryCommands,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  notice: ActionNotice? = null,
  onDismissNotice: () -> Unit = {},
  share: LibraryShare = LibraryShare.Idle,
) {
  if (!hasNotices(phone, notice, share)) return
  val partial = phone.access == MediaAccess.PARTIAL
  val pending = phone.pendingLocation > 0
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NoticeSpacing), horizontalAlignment = Alignment.CenterHorizontally) {
    if (share is LibraryShare.Preparing) LibraryShareNotice(share = share, backdrop = backdrop, onCancel = commands::cancelShare)
    if (notice != null) ActionNoticeCard(notice = notice, backdrop = backdrop, onDismiss = onDismissNotice)
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
        title = countText(R.plurals.library_pending_title, phone.pendingLocation),
        message = stringResource(R.string.library_pending_message),
        backdrop = backdrop,
        actionLabel = stringResource(R.string.library_pending_action),
        onAction = { commands.requestMediaLocation() },
      )
    }
  }
}

/**
 * Le azioni della selezione, nella barra al posto della pillola (§2.4, D13): Condividi, Preferito, Nascondi, Elimina dal
 * telefono (D14, solo dove il cestino di sistema c'e'). Con niente di selezionato le azioni sono spente.
 */
@Composable
internal fun selectionActions(
  state: LibraryTabState,
  onDelete: () -> Unit,
  onHideGroups: () -> Unit,
): List<GalleryBarAction> {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  val selected by grid.selection.selected.collectAsState()
  val any = !selected.isEmpty
  // Anche la vista e' una chiave: un preferito cambiato ripubblica l'indice e l'azione deve dire l'opposto.
  val view = grid.view
  val allFavorite = remember(selected, view) { selected.ids.isNotEmpty() && selected.ids.all { grid.isFavorite(it) } }
  val actions = buildList {
    add(GalleryBarAction(Icons.Rounded.IosShare, stringResource(R.string.library_action_share), { state.shareSelection(selected.ids) }, enabled = any))
    add(
      GalleryBarAction(
        icon = if (allFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = stringResource(if (allFavorite) R.string.library_action_unfavorite else R.string.library_action_favorite),
        onClick = {
          val ids = selected.ids
          scope.launch { if (!attempt { state.actions.setFavorite(ids, !allFavorite) }) state.notice = ActionNotice.FavoriteFailed }
        },
        enabled = any,
      ),
    )
    add(
      GalleryBarAction(
        icon = Icons.Rounded.VisibilityOff,
        contentDescription = stringResource(R.string.library_action_hide),
        onClick = {
          // P5/P11: nascondere la copertina di un gruppo nasconde tutto il gruppo; prima lo si dice (foglio).
          if (grid.hiddenWithGroups(selected.ids) > selected.size) {
            onHideGroups()
          } else {
            val ids = selected.ids
            scope.launch { state.hide(ids) }
          }
        },
        enabled = any,
      ),
    )
    // Fase 7 (P10, P13, D35): "Aggiungi ad album" per i media scelti; la scelta dell'album (e, per un condiviso, la conferma col conteggio) la
    // mostra `:app` sopra la Libreria. Un album personale non sposta niente; i nascosti non sono nella Libreria.
    if (state.commands.canAddToAlbum) {
      add(
        GalleryBarAction(
          Icons.Rounded.AddPhotoAlternate,
          stringResource(R.string.library_action_add_to_album),
          {
            state.commands.addToAlbum(selected.ids)
            grid.stopSelecting()
          },
          enabled = any,
        ),
      )
    }
    // Correzioni F (TS-4, D30): "Trova simili" dalla selezione. Cerca i media vicini al centroide di quelli scelti e apre i risultati in Cerca; niente si scrive.
    // Oltre il tetto lo dice senza partire; se nessuna foto scelta e' ancora stata analizzata lo dice, invece di aprire una ricerca vuota.
    if (state.commands.canFindSimilar) {
      add(
        GalleryBarAction(
          Icons.Rounded.ImageSearch,
          stringResource(R.string.library_action_find_similar),
          {
            val limit = state.commands.findSimilarLimit
            if (selected.size > limit) {
              state.notice = ActionNotice.SimilarTooMany(limit)
            } else {
              when (state.commands.findSimilar(selected.ids)) {
                SimilarSelectionOutcome.STARTED -> grid.stopSelecting()
                SimilarSelectionOutcome.NOT_ANALYZED -> state.notice = ActionNotice.SimilarNotAnalyzed
              }
            }
          },
          enabled = any,
        ),
      )
    }
    // Fase 5B (P14, D21): "Libera spazio" per i media scelti, solo quelli con una copia sul telefono; la pagina verifica e conferma.
    if (state.commands.canFreeSpace) {
      add(
        GalleryBarAction(
          Icons.Rounded.CleaningServices,
          stringResource(R.string.library_action_free_space),
          {
            val onPhone = state.commands.onPhone(selected.ids)
            if (onPhone.isEmpty()) {
              state.notice = ActionNotice.DeleteNasOnly
            } else {
              state.commands.freeSpace(onPhone)
              grid.stopSelecting()
            }
          },
          enabled = any,
        ),
      )
    }
    if (state.commands.canDeleteFromPhone) {
      add(
        GalleryBarAction(
          Icons.Rounded.DeleteOutline,
          stringResource(R.string.library_action_delete),
          // Oltre il tetto di una richiesta di sistema non si apre nemmeno il foglio: si dice subito quanti se ne possono (I8). Se non c'e'
          // niente sul telefono nemmeno (GA-8): da qui non c'e' niente da eliminare.
          {
            val onPhone = state.commands.onPhone(selected.ids)
            when {
              onPhone.isEmpty() -> state.notice = ActionNotice.DeleteNasOnly
              onPhone.size > state.commands.deleteLimit -> state.notice = ActionNotice.DeleteTooMany(state.commands.deleteLimit)
              else -> onDelete()
            }
          },
          destructive = true,
          enabled = any,
        ),
      )
    }
  }
  return actions
}

/**
 * Il foglio "Elimina dal telefono" (D14): un posto solo, il telefono, e l'avviso che sul NAS non c'e' ancora una copia **solo per i media che
 * non sono al sicuro** (D-B1: prima l'avviso era fisso e diceva il falso per foto gia' al sicuro). Con una selezione mista lo dice con i numeri.
 */
@Composable
internal fun LibraryDeleteSheet(visible: Boolean, state: LibraryTabState, onDismiss: () -> Unit) {
  val grid = state.grid
  val selected by grid.selection.selected.collectAsState()
  var scope by remember { mutableStateOf(GalleryDeleteScope.PhoneOnly) }
  // GA-8: il foglio conta e tocca solo i media che hanno una copia sul telefono; gli altri, solo sul NAS, restano dove sono e lo dice.
  val onPhone = remember(selected) { state.commands.onPhone(selected.ids) }
  val nasLeft = selected.size - onPhone.size
  // D-B1: la vista e' una chiave, come per i preferiti: un media che diventa "al sicuro" a foglio aperto cambia l'avviso.
  val view = grid.view
  val unsafe = remember(onPhone, view) { grid.unsafeAmong(onPhone) }
  val phoneDetail = deletePhoneDetail(total = onPhone.size, unsafe = unsafe)
  val nasLeftDetail = if (nasLeft > 0) pluralStringResource(R.plurals.library_delete_nas_left, nasLeft, GalleryNumbers.format(nasLeft)) else null
  GalleryDeleteSheet(
    visible = visible,
    count = onPhone.size.coerceAtLeast(1),
    scope = scope,
    onScopeChange = { scope = it },
    locations = listOf(
      GalleryDeleteLocation(
        title = stringResource(R.string.library_delete_location_phone),
        kind = GalleryDeleteLocationKind.Phone,
        detail = if (nasLeftDetail != null) "$phoneDetail $nasLeftDetail" else phoneDetail,
      ),
    ),
    onConfirm = {
      // Se la richiesta di sistema non parte la selezione resta aperta e lo si dice (I8).
      val started = onPhone.isNotEmpty() && state.commands.deleteFromPhone(onPhone)
      onDismiss()
      if (started) grid.stopSelecting() else state.notice = ActionNotice.DeleteFailed
    },
    onDismissRequest = onDismiss,
    scopes = listOf(GalleryDeleteScope.PhoneOnly),
  )
}

/**
 * D-B1: quanti fra [ids] (media con una copia sul telefono) **non** sono al sicuro sul NAS, dai flag dell'indice (`CellFlags.SAFE`, lo stesso
 * segno della cella: copia nell'area privata riletta e verificata qui, D8). Un id che non e' nell'indice corrente conta come non al sicuro: nel
 * dubbio l'avviso c'e', mai il contrario.
 */
internal fun MediaGridState.unsafeAmong(ids: Set<MediaId>): Int {
  val snapshot = view.snapshot
  return ids.count { id ->
    val index = indexOf(id).takeIf { it in 0 until snapshot.size } ?: snapshot.indexOf(id)
    index < 0 || !CellFlags.has(snapshot.flagsAt(index), CellFlags.SAFE)
  }
}

/**
 * La frase del posto "Telefono" nel foglio "Elimina dal telefono" (D-B1, E-D14): [total] media sul telefono, [unsafe] senza una copia al sicuro.
 * Tutti al sicuro: lo dice e basta. Nessuno al sicuro: l'avviso di sempre, con "prima mettilo al sicuro". Misti: con i numeri ("2 di 5").
 */
@Composable
internal fun deletePhoneDetail(total: Int, unsafe: Int): String = when {
  unsafe <= 0 -> pluralStringResource(R.plurals.library_delete_phone_safe, total)
  unsafe >= total -> pluralStringResource(R.plurals.library_delete_phone_unsafe_all, total)
  else -> pluralStringResource(R.plurals.library_delete_phone_unsafe_some, unsafe, GalleryNumbers.format(unsafe), GalleryNumbers.format(total))
}

/**
 * Il foglio "Nascondi" quando la selezione contiene la copertina di un gruppo (raffica, RAW+JPEG): nel catalogo il
 * nascosto vale per tutto il gruppo (E-H1, nota di P5), quindi il foglio dice quanti elementi spariscono davvero.
 */
@Composable
internal fun LibraryHideGroupSheet(visible: Boolean, state: LibraryTabState, onDismiss: () -> Unit) {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  val selected by grid.selection.selected.collectAsState()
  val total = remember(selected, grid.view) { grid.hiddenWithGroups(selected.ids) }
  val title = countText(R.plurals.library_hide_group_title, total)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.VisibilityOff,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.library_hide_group_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.library_action_hide),
          onClick = {
            val ids = selected.ids
            onDismiss()
            scope.launch { state.hide(ids) }
          },
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.library_hide_group_cancel),
          onClick = onDismiss,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}

/**
 * Quanti media nasconde davvero nascondere [ids]: una copertina porta con se' tutto il suo gruppo (`groupSizeAt`).
 * Un id che non e' nell'indice corrente conta per uno.
 */
internal fun MediaGridState.hiddenWithGroups(ids: Set<MediaId>): Int {
  val snapshot = view.snapshot
  return ids.sumOf { id ->
    val index = indexOf(id).takeIf { it in 0 until snapshot.size } ?: snapshot.indexOf(id)
    if (index >= 0) snapshot.groupSizeAt(index).coerceAtLeast(1) else 1
  }
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
