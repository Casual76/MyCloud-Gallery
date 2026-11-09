package dev.pampa.mycloudgallery.feature.albums.choose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.page.CancelButton
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText

// "AGGIUNGI AD ALBUM" (Fase 7, P10; docs/fase7-progetto.md D35, D12, D22): il foglio con cui si sceglie in quale album mettere dei media. Mostra quanti
// sono (mai le miniature: i media da aggiungere non compaiono in nessun foglio), i miei album e i condivisi dove si puo' aggiungere, e le due strade
// per un album nuovo. Un album condiviso apre sempre il foglio di conferma (D12), e per questo questo foglio si fa da parte mentre quello e' aperto.

interface AddToAlbumHandlers {
  fun onChoose(album: Album)

  fun onNewAlbum(title: String)

  fun onNewSharedAlbum(title: String)

  fun onOpenAlbum(album: AlbumId)

  fun onDismiss()

  fun onDismissMessage()
}

private enum class NewKind { MANUAL, SHARED }

/** Il foglio per lo stato dato; [hidden] lo nasconde (il foglio di conferma e' aperto sopra) senza perdere cio' che l'utente stava scrivendo. */
@Composable
fun AddToAlbumSheet(ui: AddToAlbumUi, hidden: Boolean, handlers: AddToAlbumHandlers) {
  GalleryConfirmSheet(visible = ui.ready && !hidden, onDismissRequest = handlers::onDismiss, paneTitle = stringResource(R.string.add_title)) {
    var newKind by rememberSaveable { mutableStateOf<NewKind?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val resources = LocalContext.current.resources
    val result = ui.result
    GallerySheetScaffold(
      icon = Icons.Rounded.AddPhotoAlternate,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.add_title),
      message = if (ui.count == 0) stringResource(R.string.add_nothing_title) else resources.getQuantityString(R.plurals.add_count, ui.count, ui.count),
      body = {
        ui.message?.let { FluidInlineMessage(message = messageOf(it), title = stringResource(R.string.add_title), tone = FluidTone.Warning, onDismiss = handlers::onDismissMessage) }
        when {
          ui.count == 0 -> Text(stringResource(R.string.add_nothing_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          result is AddResult.Added -> {
            Text(resources.getQuantityString(R.plurals.add_done, result.added, result.added), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (result.alreadyThere > 0) {
              Text(stringResource(R.string.add_done_already, result.alreadyThere), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          result is AddResult.Sharing -> Text(stringResource(R.string.add_sharing_started), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
          newKind != null -> NewAlbumFields(newKind == NewKind.SHARED, name, { name = it }, ui, handlers)
          else -> Choices(ui, handlers) { newKind = it }
        }
      },
      actions = {
        when {
          result is AddResult.Added -> {
            FluidButton(text = stringResource(R.string.add_done_open), onClick = { handlers.onOpenAlbum(result.album) }, size = FluidButtonSize.Large, fillWidth = true)
            CancelButton(handlers::onDismiss, label = stringResource(R.string.add_done_close))
          }
          result is AddResult.Sharing -> {
            FluidButton(text = stringResource(R.string.add_done_open), onClick = { handlers.onOpenAlbum(result.album) }, size = FluidButtonSize.Large, fillWidth = true)
            CancelButton(handlers::onDismiss, label = stringResource(R.string.add_done_close))
          }
          newKind != null -> {
            val shared = newKind == NewKind.SHARED
            FluidButton(
              text = stringResource(if (shared) R.string.add_name_create_shared else R.string.add_name_create),
              onClick = { if (shared) handlers.onNewSharedAlbum(name) else handlers.onNewAlbum(name) },
              enabled = name.isNotBlank() && !ui.busy, loading = ui.busy, size = FluidButtonSize.Large, fillWidth = true,
            )
            FluidButton(text = stringResource(R.string.albums_create_cancel), onClick = { newKind = null }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
          }
          else -> CancelButton(handlers::onDismiss)
        }
      },
    )
  }
}

@Composable
private fun Choices(ui: AddToAlbumUi, handlers: AddToAlbumHandlers, onNew: (NewKind) -> Unit) {
  val resources = LocalContext.current.resources
  FluidListGroup {
    ui.choices.forEachIndexed { index, album ->
      if (index > 0) FluidListDivider()
      val shared = album as? Album.Shared
      FluidListRow(
        title = album.title,
        subtitle = AlbumText.items(resources, album.count),
        tone = if (shared != null) FluidTone.Info else FluidTone.Primary,
        leading = { Icon(if (shared != null) Icons.Rounded.Group else Icons.Rounded.PhotoAlbum, contentDescription = null) },
        labels = if (shared != null) ({ FluidStatusBadge(label = stringResource(R.string.albums_badge_shared), tone = FluidTone.Info) }) else null,
        onClick = { handlers.onChoose(album) },
      )
    }
    if (ui.choices.isEmpty()) {
      Text(stringResource(R.string.add_no_albums), modifier = Modifier.fillMaxWidth().padding(AlbumDimens.Inset), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.add_new_album), subtitle = "", tone = FluidTone.Primary, onClick = { onNew(NewKind.MANUAL) },
      leading = { Icon(Icons.Rounded.PhotoAlbum, contentDescription = null) },
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.add_new_shared), subtitle = stringResource(R.string.add_new_shared_detail), tone = FluidTone.Info, onClick = { onNew(NewKind.SHARED) },
      leading = { Icon(Icons.Rounded.Group, contentDescription = null) },
    )
  }
}

@Composable
private fun NewAlbumFields(shared: Boolean, name: String, onName: (String) -> Unit, ui: AddToAlbumUi, handlers: AddToAlbumHandlers) {
  FluidTextField(
    value = name, onValueChange = onName, placeholder = stringResource(R.string.add_name_hint), modifier = Modifier.fillMaxWidth(),
    isError = ui.titleProblem != null, supportingText = ui.titleProblem?.let { stringResource(AlbumText.titleProblem(it)) },
  )
  if (shared) Text(stringResource(R.string.add_new_shared_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun messageOf(message: AlbumMessage): String {
  val resources = LocalContext.current.resources
  return when (message) {
    is AlbumMessage.NothingToAdd -> resources.getString(R.string.album_message_nothing_to_add, message.excluded.entries.joinToString(", ") { (reason, n) -> AlbumText.excluded(resources, reason, n) })
    AlbumMessage.NotAllowed -> stringResource(R.string.album_message_not_allowed)
    AlbumMessage.NotFound -> stringResource(R.string.album_message_not_found)
    is AlbumMessage.Blocked -> stringResource(AlbumText.blocked(message.why))
    // R-1 (review generale): l'aggiunta non e' riuscita (memoria piena): prima questo messaggio cadeva nel `else` e diceva "non e' nell'album".
    AlbumMessage.NotDone -> stringResource(R.string.album_message_not_done)
    else -> stringResource(R.string.album_message_not_in_album)
  }
}
