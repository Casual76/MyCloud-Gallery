package dev.pampa.mycloudgallery.feature.integrations.receive

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.picker.UnlockFailure

// Fase 5B (P11, docs/fase5-progetto.md §1.5 `IncomingState`, §2.7, D24, D30; E-X2, E-X7, E-X8, E-S11): la schermata di "Salva sul NAS". Funzionale, solo
// con i componenti che il design system ha gia' (D30): le righe e i bottoni di Fluid, `GalleryIllustration`, `GalleryChromeIconButton`, i colori e le
// forme del tema. **Niente vetro e niente sovrapposizioni**: la riga in alto e la barra in basso sono in colonna con il corpo, non sopra (il filone
// grafico rifinisce).
//
// **Con l'app bloccata non c'e' niente da vedere** (E-X7): lo stato LOCKED mostra solo lo sblocco. La schermata non mostra mai il nome di un file:
// numeri, tipi e la cartella scelta. L'avviso dell'area comune ("Lo vedranno tutti i familiari") sta sopra il bottone, mai nascosto.

private object ReceiveDimens {
  val MessageMaxWidth = 420.dp
  val Padding = 16.dp
  val NoticeRadius = FluidRadius.Card
}

/** Le azioni della schermata. */
class ReceiveActions(
  val onSelect: (DestinationChoice) -> Unit = {},
  val onOpen: (DestinationChoice) -> Unit = {},
  val onUp: () -> Unit = {},
  val onSave: () -> Unit = {},
  val onCancel: () -> Unit = {},
  val onClose: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onUnlock: () -> Unit = {},
  /** Fase 7 (P10, D35): "Aggiungi ad album" e la scelta dell'album. */
  val onPickAlbum: () -> Unit = {},
  val onSelectAlbum: (IncomingAlbumChoice) -> Unit = {},
  val onSaveToAlbum: () -> Unit = {},
  val onBackFromAlbums: () -> Unit = {},
  /** AA-2: «Nuovo album» dalla scelta dell'album. */
  val onCreateAlbum: (String) -> Unit = {},
)

@Composable
internal fun ReceiveScreen(ui: ReceiveUi, actions: ReceiveActions, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    ReceiveTopBar(onCancel = if (ui.screen == ReceiveScreenState.DONE) actions.onClose else actions.onCancel)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (ui.screen) {
        ReceiveScreenState.CHECKING -> Unit
        ReceiveScreenState.LOCKED -> ReceiveMessage(
          icon = Icons.Rounded.Lock,
          tone = GalleryIllustrationTone.Brand,
          title = stringResource(R.string.integrations_receive_locked_title),
          detail = stringResource(R.string.integrations_receive_locked_detail),
          action = stringResource(R.string.integrations_receive_unlock),
          onAction = actions.onUnlock,
          loading = ui.unlocking,
          footnote = unlockFootnote(ui),
        )
        ReceiveScreenState.NOT_READY -> ReceiveMessage(
          icon = Icons.Rounded.Settings,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_receive_not_ready_title),
          detail = stringResource(R.string.integrations_receive_not_ready_detail),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onCancel,
        )
        ReceiveScreenState.STAGING -> ReceiveBusy(R.string.integrations_receive_staging_title, R.string.integrations_receive_staging_detail)
        ReceiveScreenState.SAVING -> ReceiveBusy(R.string.integrations_receive_saving_title, R.string.integrations_receive_saving_detail)
        ReceiveScreenState.READY -> ReceiveReady(ui, actions)
        ReceiveScreenState.TEST_ONLY -> ReceiveMessage(
          icon = Icons.Rounded.Settings,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_receive_trial_title),
          detail = stringResource(R.string.integrations_receive_trial_detail),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onCancel,
        )
        ReceiveScreenState.STOPPED -> ReceiveMessage(
          icon = Icons.Rounded.Settings,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_stopped_title),
          detail = stringResource(R.string.integrations_receive_stopped_detail),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onCancel,
        )
        ReceiveScreenState.READ_ONLY_VERSION -> ReceiveMessage(
          icon = Icons.Rounded.Settings,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_receive_read_only_title),
          detail = stringResource(R.string.integrations_receive_read_only_detail),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onCancel,
        )
        ReceiveScreenState.NOTHING -> ReceiveMessage(
          icon = Icons.Rounded.PhotoLibrary,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_receive_nothing_title),
          // IN-11: se dei file buoni non hanno una cartella dove andare, lo si dice anche quando altri file sono stati rifiutati.
          detail = stringResource(if (ui.noFolder || ui.rejected.isEmpty()) R.string.integrations_receive_nothing_no_folder else R.string.integrations_receive_nothing_detail),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onCancel,
          footnote = rejectedLines(ui.rejected).takeIf { it.isNotEmpty() }?.joinToString("\n"),
          footnoteIsError = false,
        )
        ReceiveScreenState.DONE -> ReceiveMessage(
          // CU-39 (fase I): i file sono in coda, non ancora sul NAS: la clessidra dell'attesa, non la spunta.
          icon = Icons.Rounded.HourglassTop,
          tone = GalleryIllustrationTone.Brand,
          title = pluralStringResource(R.plurals.integrations_receive_done_title, ui.queued, ui.queued),
          detail = doneDetail(ui),
          action = stringResource(R.string.integrations_receive_close),
          onAction = actions.onClose,
        )
        ReceiveScreenState.FAILED -> ReceiveMessage(
          icon = Icons.Rounded.ErrorOutline,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_receive_failed_title),
          detail = stringResource(failureText(ui.failure)),
          // IN-11: con i file spariti (o il backup in prova) non c'e' da riprovare: il tasto chiude, e lo dice.
          action = stringResource(if (ui.failure == ReceiveFailure.GONE || ui.failure == ReceiveFailure.TEST_ONLY) R.string.integrations_receive_close else R.string.integrations_receive_retry),
          onAction = actions.onRetry,
        )
      }
    }
  }
}

// --- Riga in alto -----------------------------------------------------------------------------------------------------------

@Composable
private fun ReceiveTopBar(onCancel: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GalleryChromeIconButton(
      icon = Icons.Rounded.Close,
      contentDescription = stringResource(R.string.integrations_receive_cancel),
      onClick = onCancel,
    )
    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() }) {
      Text(
        text = stringResource(R.string.integrations_receive_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      Text(
        text = stringResource(R.string.integrations_receive_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

// --- Il foglio: riepilogo, avvisi, cartella, bottone ------------------------------------------------------------------------------

@Composable
private fun ReceiveReady(ui: ReceiveUi, actions: ReceiveActions) {
  Column(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(ReceiveDimens.Padding),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ReceiveSummary(ui)
      val lines = rejectedLines(ui.rejected)
      if (lines.isNotEmpty()) ReceiveRejected(lines)
      // D35: la scelta dell'album prende il posto di quella della cartella (la cartella scelta resta quella dove si salva).
      if (ui.albumPick) ReceiveAlbums(ui, actions) else ReceiveDestinations(ui, actions)
    }
    ReceiveBottomBar(ui, actions)
  }
}

@Composable
private fun ReceiveSummary(ui: ReceiveUi) {
  val context = LocalContext.current
  val size = Formatter.formatShortFileSize(context, ui.bytes)
  val photos = if (ui.photos > 0) pluralStringResource(R.plurals.integrations_receive_photos, ui.photos, ui.photos) else null
  val videos = if (ui.videos > 0) pluralStringResource(R.plurals.integrations_receive_videos, ui.videos, ui.videos) else null
  val text = when {
    photos != null && videos != null -> stringResource(R.string.integrations_receive_summary_both, photos, videos, size)
    else -> stringResource(R.string.integrations_receive_summary_one, photos ?: videos.orEmpty(), size)
  }
  // I4: il conto da solo, con il segno delle foto che arrivano; «Da salvare» non serve (il titolo della pagina lo dice).
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    GalleryIconTile(icon = Icons.Rounded.AddPhotoAlternate, family = GalleryIconFamily.Photos, size = GalleryIconTileDefaults.Medium)
    Text(text = text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun ReceiveRejected(lines: List<String>) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(scheme.tertiaryContainer, ContinuousCornerShape(ReceiveDimens.NoticeRadius))
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(text = stringResource(R.string.integrations_receive_rejected_title), style = MaterialTheme.typography.titleSmall, color = scheme.onTertiaryContainer)
    for (line in lines) Text(text = line, style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer)
  }
}

@Composable
private fun ReceiveDestinations(ui: ReceiveUi, actions: ReceiveActions) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (ui.trail.isNotEmpty()) {
        GalleryChromeIconButton(
          icon = Icons.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.integrations_receive_up),
          onClick = actions.onUp,
        )
      }
      Text(
        text = stringResource(R.string.integrations_receive_where),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    FluidListGroup {
      ui.choices.forEachIndexed { index, choice ->
        if (index > 0) FluidListDivider()
        // I4 (fase I): l'area si riconosce dal segno (la persona per la tua, il gruppo per quella di tutti); la parola la dice TalkBack.
        val common = choice.destination.area == DestinationArea.COMMON
        val area = stringResource(if (common) R.string.integrations_receive_area_common else R.string.integrations_receive_area_private)
        FluidListRow(
          title = choice.name,
          subtitle = "",
          leading = {
            GalleryIconTile(
              icon = if (common) Icons.Rounded.Groups else Icons.Rounded.Person,
              family = if (common) GalleryIconFamily.People else GalleryIconFamily.Security,
            )
          },
          leadingTile = false,
          selected = choice == ui.selected,
          disclosure = false,
          onClick = { actions.onSelect(choice) },
          modifier = Modifier.semantics { stateDescription = area },
        )
      }
    }
    // Un solo bottone per le sottocartelle, sotto la lista e non dentro le righe: nessun controllo ne contiene un altro.
    val selected = ui.selected
    if (selected != null) {
      FluidButton(
        text = stringResource(R.string.integrations_receive_open_folder),
        onClick = { actions.onOpen(selected) },
        size = FluidButtonSize.Large,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
      )
      if (ui.noSubfolders) {
        Text(
          text = stringResource(R.string.integrations_receive_no_subfolders),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** La scelta dell'album (Fase 7, P10, D35): le righe sono album, la scelta e' una sola; il nome di un album e' un dato dell'utente e sta solo qui. */
@Composable
private fun ReceiveAlbums(ui: ReceiveUi, actions: ReceiveActions) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      GalleryChromeIconButton(
        icon = Icons.Rounded.ArrowBack,
        contentDescription = stringResource(R.string.integrations_receive_album_back),
        onClick = actions.onBackFromAlbums,
      )
      Text(
        text = stringResource(R.string.integrations_receive_album_where),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    FluidListGroup {
      ui.albums.forEachIndexed { index, choice ->
        if (index > 0) FluidListDivider()
        val kind = stringResource(if (choice.shared) R.string.integrations_receive_album_shared else R.string.integrations_receive_album_mine)
        FluidListRow(
          title = choice.name,
          subtitle = "",
          leading = {
            GalleryIconTile(
              icon = if (choice.shared) Icons.Rounded.Groups else Icons.Rounded.PhotoAlbum,
              family = if (choice.shared) GalleryIconFamily.People else GalleryIconFamily.Photos,
            )
          },
          leadingTile = false,
          selected = choice == ui.selectedAlbum,
          disclosure = false,
          onClick = { actions.onSelectAlbum(choice) },
          modifier = Modifier.semantics { stateDescription = kind },
        )
      }
    }
    // AA-2: «Nuovo album» (un album a mano: nessun file si sposta, nessuna conferma da chiedere). Anche quando non c'e' ancora nessun album.
    if (ui.canCreateAlbum) NewAlbum(ui, actions)
    Text(
      text = stringResource(R.string.integrations_receive_album_hint),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun NewAlbum(ui: ReceiveUi, actions: ReceiveActions) {
  var title by rememberSaveable { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = stringResource(R.string.integrations_receive_album_new),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    FluidTextField(
      value = title,
      onValueChange = { title = it },
      placeholder = stringResource(R.string.integrations_receive_album_new_name),
      modifier = Modifier.fillMaxWidth(),
      isError = ui.newAlbumProblem != null,
      supportingText = when (ui.newAlbumProblem) {
        NewAlbumProblem.INVALID_TITLE -> stringResource(R.string.integrations_receive_album_new_invalid)
        NewAlbumProblem.UNAVAILABLE -> stringResource(R.string.integrations_receive_album_new_unavailable)
        null -> null
      },
    )
    FluidButton(
      text = stringResource(R.string.integrations_receive_album_new_create),
      onClick = { actions.onCreateAlbum(title) },
      size = FluidButtonSize.Large,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      enabled = title.isNotBlank() && !ui.creatingAlbum,
    )
  }
}

@Composable
private fun ReceiveBottomBar(ui: ReceiveUi, actions: ReceiveActions) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(scheme.surfaceContainer)
      .padding(ReceiveDimens.Padding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // D24: nell'area comune il foglio lo dice, qui sopra il bottone, sempre visibile.
    if (ui.commonNotice) {
      Text(
        text = stringResource(R.string.integrations_receive_common_notice),
        style = MaterialTheme.typography.titleSmall,
        color = scheme.error,
      )
    }
    if (ui.albumPick) {
      // D35: salvare e aggiungere all'album e' "Salva sul NAS" nella cartella scelta piu' la promessa dell'album: il tocco e' uno solo e la conferma nasce da li'.
      FluidButton(
        text = stringResource(R.string.integrations_receive_album_save),
        onClick = actions.onSaveToAlbum,
        size = FluidButtonSize.Large,
        style = FluidButtonStyle.Filled,
        fillWidth = true,
        enabled = ui.selected != null && ui.selectedAlbum != null,
      )
    } else {
      FluidButton(
        text = stringResource(R.string.integrations_receive_save),
        onClick = actions.onSave,
        size = FluidButtonSize.Large,
        style = FluidButtonStyle.Filled,
        fillWidth = true,
        enabled = ui.selected != null,
      )
      // D13: la voce c'e' solo se ci sono album dove aggiungere.
      if (ui.albums.isNotEmpty() || ui.canCreateAlbum) {
        FluidButton(
          text = stringResource(R.string.integrations_receive_album_open),
          onClick = actions.onPickAlbum,
          size = FluidButtonSize.Large,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          enabled = ui.selected != null,
        )
      }
    }
    FluidButton(
      text = stringResource(R.string.integrations_receive_cancel),
      onClick = actions.onCancel,
      size = FluidButtonSize.Large,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
    )
  }
}

// --- Stati senza corpo --------------------------------------------------------------------------------------------------------

@Composable
private fun ReceiveBusy(title: Int, detail: Int) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    FluidSpinner(size = 32.dp)
    Text(
      text = stringResource(title),
      modifier = Modifier.padding(top = 16.dp).widthIn(max = ReceiveDimens.MessageMaxWidth),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(detail),
      modifier = Modifier.padding(top = 4.dp).widthIn(max = ReceiveDimens.MessageMaxWidth),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ReceiveMessage(
  icon: ImageVector,
  tone: GalleryIllustrationTone,
  title: String,
  detail: String,
  action: String?,
  onAction: (() -> Unit)?,
  loading: Boolean = false,
  footnote: String? = null,
  footnoteIsError: Boolean = true,
) {
  // CS-26 (fase I): la forma comune dello stato (figura e titolo); la frase resta aperta (e' un'istruzione o un errore) e cosi' la nota.
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    GalleryFigureState(icon = icon, title = title, tone = tone, fill = false) {
      Text(
        text = detail,
        modifier = Modifier.widthIn(max = ReceiveDimens.MessageMaxWidth),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (action != null && onAction != null) {
        FluidButton(text = action, onClick = onAction, size = FluidButtonSize.Large, style = FluidButtonStyle.Filled, loading = loading, enabled = !loading)
      }
      if (footnote != null) {
        Text(
          text = footnote,
          modifier = Modifier.widthIn(max = ReceiveDimens.MessageMaxWidth),
          style = MaterialTheme.typography.bodyMedium,
          color = if (footnoteIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

// --- Testi --------------------------------------------------------------------------------------------------------------------

/** IN-10: la frase sotto "Sblocca": niente se l'utente ha solo annullato, cosa fare se il telefono non ha un blocco schermo, altrimenti "riprova". */
@Composable
private fun unlockFootnote(ui: ReceiveUi): String? = when {
  !ui.unlockFailed -> null
  ui.unlockFailure == UnlockFailure.CANCELLED -> null
  ui.unlockFailure == UnlockFailure.NO_SCREEN_LOCK -> stringResource(R.string.integrations_receive_unlock_no_screen_lock)
  else -> stringResource(R.string.integrations_receive_unlock_failed)
}

/** Il dettaglio di "In coda": la frase di sempre; il NAS spento o i dati mobili (IN-11) lo dicono come sono; i file non letti (IN-12) si contano. */
@Composable
private fun doneDetail(ui: ReceiveUi): String {
  val base = stringResource(
    when (ui.nasWait) {
      NasWait.NONE -> R.string.integrations_receive_done_detail
      NasWait.OFFLINE -> R.string.integrations_receive_done_offline
      NasWait.METERED -> R.string.integrations_receive_done_metered
    },
  )
  val withAlbum = when {
    ui.addedToAlbum != null -> base + "\n" + stringResource(if (ui.addedToAlbum.shared) R.string.integrations_receive_done_album_shared else R.string.integrations_receive_done_album, ui.addedToAlbum.name)
    ui.albumNotRegistered -> base + "\n" + stringResource(R.string.integrations_receive_done_album_failed)
    else -> base
  }
  return if (ui.notQueued > 0) withAlbum + "\n" + pluralStringResource(R.plurals.integrations_receive_done_not_queued, ui.notQueued, ui.notQueued) else withAlbum
}

private fun failureText(failure: ReceiveFailure?): Int = when (failure) {
  ReceiveFailure.CONFIRMATION -> R.string.integrations_receive_failed_confirmation
  ReceiveFailure.DESTINATION -> R.string.integrations_receive_failed_destination
  ReceiveFailure.TEST_ONLY -> R.string.integrations_receive_trial_detail
  ReceiveFailure.GONE -> R.string.integrations_receive_failed_gone
  ReceiveFailure.UNAVAILABLE, null -> R.string.integrations_receive_failed_unavailable
}

/** Le righe dei file rifiutati: una per motivo, con il numero. Solo numeri e tetti: nessun nome. */
@Composable
internal fun rejectedLines(rejected: Map<IncomingReject, Int>): List<String> {
  val context = LocalContext.current
  return IncomingReject.entries.mapNotNull { reason ->
    val count = rejected[reason] ?: 0
    if (count <= 0) null else rejectedLine(context, reason, count)
  }
}

internal fun rejectedLine(context: android.content.Context, reason: IncomingReject, count: Int): String {
  fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
  return when (reason) {
    IncomingReject.FILE_SCHEME -> context.getString(R.string.integrations_receive_rejected_file_scheme, count)
    IncomingReject.OWN_PROVIDER -> context.getString(R.string.integrations_receive_rejected_own_provider, count)
    IncomingReject.TOO_LARGE -> context.getString(R.string.integrations_receive_rejected_too_large, count, size(IncomingLimits.MAX_FILE_BYTES))
    IncomingReject.TOO_MANY -> context.getString(R.string.integrations_receive_rejected_too_many, count, IncomingLimits.MAX_ITEMS)
    IncomingReject.STAGE_TOO_BIG -> context.getString(R.string.integrations_receive_rejected_stage_too_big, count, size(IncomingLimits.MAX_STAGE_BYTES))
    IncomingReject.NO_SPACE -> context.getString(R.string.integrations_receive_rejected_no_space, count, size(IncomingLimits.MIN_FREE_AFTER_BYTES))
    IncomingReject.NOT_MEDIA -> context.getString(R.string.integrations_receive_rejected_not_media, count)
    IncomingReject.UNREADABLE -> context.getString(R.string.integrations_receive_rejected_unreadable, count)
    IncomingReject.NOT_AVAILABLE -> context.getString(R.string.integrations_receive_rejected_not_available, count)
  }
}
