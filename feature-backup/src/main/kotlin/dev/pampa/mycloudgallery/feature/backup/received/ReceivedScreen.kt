package dev.pampa.mycloudgallery.feature.backup.received

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCard
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone

// "Ricevuti in coda" (correzioni 5B, IN-7 della verifica funzionale; docs/fase5-progetto.md §2.7, D24, E-X8): l'elenco semplice dei file che un'altra
// app ha condiviso e che non sono ancora sul NAS. Per ognuno: la miniatura dalla copia sul telefono, la cartella di destinazione, lo stato in una
// frase chiara e due tasti, "Riprova" e "Scarta" (con una conferma). Solo componenti G2 (la chrome e le schede della pagina Backup), nessun colore
// scritto a mano; i bersagli sono da 50 dp (`BackupButton`). Il nome del file non si mostra: la cartella e' un dato dell'utente e si vede a schermo,
// ma non va in nessun log.

/** Le uscite dell'elenco: tutte partono da un tocco. [thumbnail] legge la copia locale (fuori dal thread principale). */
@Immutable
internal class ReceivedActions(
  val retry: (ReceivedItem) -> Unit = {},
  val askDiscard: (ReceivedItem) -> Unit = {},
  val confirmDiscard: () -> Unit = {},
  val cancelDiscard: () -> Unit = {},
  val thumbnail: suspend (ReceivedItem, Int) -> ImageBitmap? = { _, _ -> null },
)

/** L'elenco con il suo ViewModel. [onBack] e' null nel pannello di dettaglio del tablet. */
@Composable
fun ReceivedRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: ReceivedViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ReceivedScreen(
    state = state,
    actions = ReceivedActions(
      retry = viewModel::retry,
      askDiscard = viewModel::askDiscard,
      confirmDiscard = viewModel::confirmDiscard,
      cancelDiscard = viewModel::cancelDiscard,
      thumbnail = viewModel::thumbnail,
    ),
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

/** L'elenco per uno [state]: nessun effetto. Lo provano i test e gli screenshot. */
@Composable
internal fun ReceivedScreen(
  state: ReceivedUi,
  actions: ReceivedActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  BackupPage(
    label = BackupLabel(
      title = stringResource(R.string.backup_received_title),
      subtitle = if (state.items.isEmpty()) null else pluralStringResource(R.plurals.backup_received_summary, state.items.size, BackupText.count(state.items.size)),
    ),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = BackupDimens.PageMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
      ) {
        state.notice?.let { notice -> item(key = "notice") { NoticeCard(notice) } }
        if (state.items.isEmpty()) {
          item(key = "empty") {
            BackupCard(
              tone = BackupTone.Waiting,
              glyph = Icons.Rounded.MoveToInbox,
              title = stringResource(R.string.backup_received_empty_title),
              detail = stringResource(R.string.backup_received_empty_detail),
            )
          }
        } else {
          state.items.forEach { received ->
            item(key = "received-${received.id.value}") { ReceivedCard(received, actions) }
          }
          item(key = "note") { BackupNote(stringResource(R.string.backup_received_note)) }
        }
      }
    }
  }
  GalleryConfirmSheet(
    visible = state.discarding != null,
    onDismissRequest = actions.cancelDiscard,
    paneTitle = stringResource(R.string.backup_received_discard_title),
  ) {
    DiscardSheetContent(onConfirm = actions.confirmDiscard, onCancel = actions.cancelDiscard)
  }
}

/** Una scheda per file: la miniatura, la cartella, lo stato e i tasti che hanno senso adesso. */
@Composable
private fun ReceivedCard(item: ReceivedItem, actions: ReceivedActions) {
  val folder = folderText(item.folder)
  val kind = stringResource(if (item.isVideo) R.string.backup_received_kind_video else R.string.backup_received_kind_photo)
  val detail = stringResource(statusText(item.status)) + "\n" + stringResource(R.string.backup_received_kind_size, kind, BackupText.size(item.size))
  val buttons: (@Composable ColumnScope.() -> Unit)? = if (!item.canRetry && !item.canDiscard) null else ({ ReceivedButtons(item, folder, actions) })
  BackupCard(
    tone = when (item.phase) {
      ReceivedPhase.PROBLEM -> BackupTone.Attention
      ReceivedPhase.UPLOADING -> BackupTone.Working
      ReceivedPhase.WAITING -> BackupTone.Waiting
    },
    glyph = Icons.Rounded.MoveToInbox,
    title = folder,
    detail = detail,
    leading = { ReceivedThumbnail(item, actions.thumbnail) },
    actions = buttons,
  )
}

/**
 * "Riprova" e "Scarta": affiancati se entrano interi, uno sotto l'altro se no (a 360 dp, accanto alla miniatura, la colonna e' di ~200 dp e "Riprova"
 * andava a capo a meta' parola). Il tasto sta alla sua larghezza naturale e si allarga a dividere la riga ([weight]); per TalkBack ognuno dice di
 * quale cartella parla (nell'elenco i tasti sono tanti e uguali).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivedButtons(item: ReceivedItem, folder: String, actions: ReceivedActions) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
    verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (item.canRetry) {
      val retryName = stringResource(R.string.backup_received_retry_for, folder)
      // "Riprova" e' il tasto pieno di un problema; per un file che aspetta e' una spinta in piu', quindi piu' quieta.
      BackupButton(
        label = stringResource(R.string.backup_received_retry),
        onClick = { actions.retry(item) },
        modifier = Modifier.weight(1f).semantics { contentDescription = retryName },
        style = if (item.phase == ReceivedPhase.PROBLEM) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
        fillWidth = false,
      )
    }
    if (item.canDiscard) {
      val discardName = stringResource(R.string.backup_received_discard_for, folder)
      BackupButton(
        label = stringResource(R.string.backup_received_discard),
        onClick = { actions.askDiscard(item) },
        modifier = Modifier.weight(1f).semantics { contentDescription = discardName },
        style = FluidButtonStyle.Tinted,
        fillWidth = false,
      )
    }
  }
}

/** La miniatura dalla copia sul telefono; finche' non c'e' (o se non si legge) un segnaposto con il glifo del tipo. */
@Composable
private fun ReceivedThumbnail(item: ReceivedItem, load: suspend (ReceivedItem, Int) -> ImageBitmap?) {
  val side = BackupDimens.ReceivedThumb
  val px = with(LocalDensity.current) { side.roundToPx() }
  val image by produceState<ImageBitmap?>(initialValue = null, item.id, px) { value = load(item, px) }
  Box(
    modifier = Modifier
      .size(side)
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = image
    if (bitmap != null) {
      Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(
        imageVector = if (item.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(BackupDimens.RowGlyph),
      )
    }
  }
}

/** La frase dopo un tocco su "Riprova" o "Scarta". */
@Composable
private fun NoticeCard(notice: ReceivedNotice) {
  val text = when (notice) {
    ReceivedNotice.Requeued -> stringResource(R.string.backup_received_notice_queued)
    is ReceivedNotice.Blocked -> stringResource(R.string.backup_received_notice_blocked, stringResource(statusText(notice.by)))
    ReceivedNotice.NoCopy -> stringResource(R.string.backup_received_notice_no_copy)
    ReceivedNotice.AlreadySaved -> stringResource(R.string.backup_received_notice_saved)
    ReceivedNotice.Discarded -> stringResource(R.string.backup_received_notice_discarded)
    ReceivedNotice.Uploading -> stringResource(R.string.backup_received_notice_uploading)
    ReceivedNotice.Unknown -> stringResource(R.string.backup_received_notice_unknown)
  }
  val tone = when (notice) {
    ReceivedNotice.Requeued -> BackupTone.Working
    ReceivedNotice.Discarded, ReceivedNotice.AlreadySaved -> BackupTone.Calm
    is ReceivedNotice.Blocked, ReceivedNotice.NoCopy, ReceivedNotice.Uploading, ReceivedNotice.Unknown -> BackupTone.Attention
  }
  // TalkBack dice com'e' andata appena la frase compare: l'utente ha toccato un tasto e non vede dove cambia qualcosa.
  BackupCard(
    tone = tone,
    glyph = Icons.Rounded.MoveToInbox,
    title = text,
    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
  )
}

/** Il foglio di conferma di "Scarta": dice cosa si perde (la copia sul telefono) e che il NAS non cambia. */
@Composable
internal fun DiscardSheetContent(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  GallerySheetScaffold(
    icon = Icons.Rounded.Delete,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.backup_received_discard_title),
    message = stringResource(R.string.backup_received_discard_message),
    modifier = modifier,
    actions = {
      BackupButton(label = stringResource(R.string.backup_received_discard_confirm), onClick = onConfirm)
      BackupButton(label = stringResource(R.string.backup_received_discard_cancel), onClick = onCancel, style = FluidButtonStyle.Plain)
    },
  )
}

/** Il riquadro con cui la pagina Backup porta all'elenco: compare solo con almeno un file, e conta quelli in attesa, in caricamento e con un problema. */
@Composable
fun ReceivedEntryCard(counts: ReceivedCounts, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  if (counts.total <= 0) return
  val parts = ArrayList<String>(3)
  if (counts.waiting > 0) parts += stringResource(R.string.backup_received_entry_waiting, BackupText.count(counts.waiting))
  if (counts.uploading > 0) parts += stringResource(R.string.backup_received_entry_uploading, BackupText.count(counts.uploading))
  if (counts.problems > 0) parts += pluralStringResource(R.plurals.backup_received_entry_problems, counts.problems, BackupText.count(counts.problems))
  BackupCard(
    tone = when {
      counts.problems > 0 -> BackupTone.Attention
      counts.uploading > 0 -> BackupTone.Working
      else -> BackupTone.Waiting
    },
    glyph = Icons.Rounded.MoveToInbox,
    title = stringResource(R.string.backup_received_entry_title),
    detail = parts.joinToString(" · "),
    modifier = modifier,
    actions = { BackupButton(label = stringResource(R.string.backup_received_entry_action), onClick = onOpen, style = FluidButtonStyle.Tinted) },
  )
}

/** "Nella cartella Viaggi", o "Nella cartella principale" se la cartella e' la radice dell'area. */
@Composable
private fun folderText(folder: String): String {
  val name = folder.trim('/').takeIf { it.isNotEmpty() }
  return if (name == null) stringResource(R.string.backup_received_folder_root) else stringResource(R.string.backup_received_folder, name)
}

/** La frase di ogni stato: un `when` esaustivo, cosi' uno stato nuovo non compila finche' non ha il suo testo. */
@StringRes
internal fun statusText(status: ReceivedStatus): Int = when (status) {
  ReceivedStatus.QUEUED -> R.string.backup_received_status_queued
  ReceivedStatus.NETWORK -> R.string.backup_received_status_network
  ReceivedStatus.TRIAL -> R.string.backup_received_status_trial
  ReceivedStatus.FOLDER_LOCKED -> R.string.backup_received_status_folder_locked
  ReceivedStatus.NAS_FULL -> R.string.backup_received_status_nas_full
  ReceivedStatus.QUOTA -> R.string.backup_received_status_quota
  ReceivedStatus.PASSWORD -> R.string.backup_received_status_password
  ReceivedStatus.FROZEN -> R.string.backup_received_status_frozen
  ReceivedStatus.PAUSED -> R.string.backup_received_status_paused
  ReceivedStatus.UNAVAILABLE -> R.string.backup_received_status_unavailable
  ReceivedStatus.UPLOADING -> R.string.backup_received_status_uploading
  ReceivedStatus.VERIFYING -> R.string.backup_received_status_verifying
  ReceivedStatus.COPY_CHANGED -> R.string.backup_received_status_copy_changed
  ReceivedStatus.NOT_INTACT -> R.string.backup_received_status_not_intact
  ReceivedStatus.FOLDER_REFUSED -> R.string.backup_received_status_folder_refused
  ReceivedStatus.COPY_MISSING -> R.string.backup_received_status_copy_missing
  ReceivedStatus.FAILED -> R.string.backup_received_status_failed
}
