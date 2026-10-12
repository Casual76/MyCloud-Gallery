package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar

/** Il menu ⋮: quello del viewer (completo, tre gruppi e la voce che cancella) e uno breve. */
internal fun menuSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_menu_viewer, R.string.sec_menu_viewer_detail) { ViewerMenu() },
  CatalogSection(R.string.sec_menu_short, R.string.sec_menu_short_detail) { ShortMenu() },
)

/** Le voci del menu ⋮ del viewer (piano, 3.4), in gruppi: correggere, spostare, e infine eliminare. */
@Composable
internal fun sampleViewerMenu(): List<GalleryMenuEntry> = listOf(
  GalleryMenuEntry.Item(stringResource(R.string.menu_view_original), {}, Icons.Rounded.History),
  GalleryMenuEntry.Item(stringResource(R.string.menu_fix_date), {}, Icons.Rounded.EditCalendar),
  GalleryMenuEntry.Item(stringResource(R.string.menu_rotate), {}, Icons.Rounded.RotateRight),
  GalleryMenuEntry.GroupBreak,
  GalleryMenuEntry.Item(stringResource(R.string.menu_download), {}, Icons.Rounded.Download),
  GalleryMenuEntry.Item(stringResource(R.string.menu_share), {}, Icons.Rounded.IosShare),
  GalleryMenuEntry.Item(stringResource(R.string.menu_edit_with), {}, Icons.Rounded.Edit),
  GalleryMenuEntry.GroupBreak,
  GalleryMenuEntry.Item(stringResource(R.string.menu_move), {}, Icons.AutoMirrored.Rounded.DriveFileMove),
  GalleryMenuEntry.Item(stringResource(R.string.menu_rename), {}, Icons.Rounded.DriveFileRenameOutline),
  GalleryMenuEntry.Item(stringResource(R.string.menu_hide), {}, Icons.Rounded.VisibilityOff),
  GalleryMenuEntry.Item(stringResource(R.string.menu_details), {}, Icons.Rounded.Info),
  GalleryMenuEntry.GroupBreak,
  GalleryMenuEntry.Item(stringResource(R.string.menu_delete), {}, Icons.Rounded.Delete, destructive = true),
)

/** Il menu ⋮ della Libreria: poche voci. */
@Composable
internal fun sampleLibraryMenu(): List<GalleryMenuEntry> = listOf(
  GalleryMenuEntry.Item(stringResource(R.string.menu_library_size), {}, Icons.Rounded.Palette),
  GalleryMenuEntry.Item(stringResource(R.string.menu_library_settings), {}, Icons.Rounded.Settings),
)

@Composable
private fun ViewerMenu() {
  CatalogPhotoStage(scene = SampleScene.Meadow, height = ViewerMenuStageHeight, scrim = PopupScrim) { backdrop ->
    GalleryTopBar(
      title = stringResource(R.string.sample_date_long),
      subtitle = stringResource(R.string.sample_time_place),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter),
      onPhoto = true,
      onNavigate = {},
      navigationIcon = GalleryNavigationIcon.Back,
      actions = {
        GalleryBarButton(
          icon = Icons.Rounded.Info,
          contentDescription = stringResource(R.string.bar_action_info),
          onClick = {},
        )
        // Il tasto ⋮ resta com'e': aperto, il menu e' quello che sta sotto.
        GalleryMenuButton(entries = sampleViewerMenu(), backdrop = backdrop)
      },
    )
    GalleryMenuSurface(
      backdrop = backdrop,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = MenuTopOffset, end = MenuEndOffset),
    ) {
      GalleryMenuPanel(entries = sampleViewerMenu())
    }
  }
}

@Composable
private fun ShortMenu() {
  CatalogPhotoStage(scene = SampleScene.Sunset, height = ShortMenuStageHeight, scrim = PopupScrim) { backdrop ->
    Box(
      modifier = Modifier
        .align(Alignment.Center)
        .padding(horizontal = 12.dp),
    ) {
      GalleryMenuSurface(backdrop = backdrop) {
        GalleryMenuPanel(
          entries = listOf(
            GalleryMenuEntry.Item(stringResource(R.string.menu_share), {}, Icons.Rounded.IosShare),
            GalleryMenuEntry.Item(
              label = stringResource(R.string.menu_download),
              onClick = {},
              icon = Icons.Rounded.Download,
              enabled = false,
            ),
            GalleryMenuEntry.GroupBreak,
            GalleryMenuEntry.Item(stringResource(R.string.menu_delete), {}, Icons.Rounded.Delete, destructive = true),
          ),
        )
      }
    }
  }
}

private val ViewerMenuStageHeight = 660.dp
private val ShortMenuStageHeight = 300.dp
private val MenuTopOffset = 56.dp
private val MenuEndOffset = 12.dp

/** Il velo che il pop-up vero mette sulla pagina: leggero, perche il pannello si separa da solo. */
private const val PopupScrim = 0.14f
