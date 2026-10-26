package dev.pampa.mycloudgallery.feature.albums.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.page.CancelButton
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow

// "NUOVO ALBUM" (Fase 7, P10; docs/fase7-progetto.md §2.1 "Creazione"): il nome e che album. Un album a mano nasce subito e si apre; uno smart passa
// dall'editor delle regole, che parte dal nome ("Oslo 2026" diventa periodo e luogo). Un album condiviso non nasce da qui: nasce condividendo un album o
// scegliendo "Nuovo album condiviso" quando si aggiungono delle foto, perche' lo spostamento delle foto vuole il suo foglio di conferma.

@Composable
fun CreateAlbumSheet(
  visible: Boolean,
  problem: TitleProblem?,
  unavailable: Boolean,
  onCreate: (title: String, kind: AlbumKind) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.albums_create_title)) {
    var title by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(AlbumKind.MANUAL) }
    GallerySheetScaffold(
      icon = Icons.Rounded.PhotoAlbum,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.albums_create_title),
      message = if (unavailable) stringResource(R.string.albums_create_unavailable) else null,
      body = {
        FluidTextField(
          value = title, onValueChange = { title = it }, placeholder = stringResource(R.string.albums_create_name), modifier = Modifier.fillMaxWidth(),
          isError = problem != null, supportingText = problem?.let { stringResource(AlbumText.titleProblem(it)) },
        )
        FluidListGroup {
          OptionRow(
            title = stringResource(R.string.albums_create_kind_manual), detail = stringResource(R.string.albums_create_kind_manual_detail),
            selected = kind == AlbumKind.MANUAL, onSelect = { kind = AlbumKind.MANUAL },
          )
          FluidListDivider()
          OptionRow(
            title = stringResource(R.string.albums_create_kind_smart), detail = stringResource(R.string.albums_create_kind_smart_detail),
            selected = kind == AlbumKind.SMART, onSelect = { kind = AlbumKind.SMART },
          )
        }
      },
      actions = {
        FluidButton(
          text = stringResource(if (kind == AlbumKind.SMART) R.string.albums_create_next else R.string.albums_create_confirm),
          onClick = { onCreate(title, kind) }, enabled = title.isNotBlank(), size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelButton(onDismiss, label = stringResource(R.string.albums_create_cancel))
      },
    )
  }
}
