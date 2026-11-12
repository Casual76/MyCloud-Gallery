package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged

// La griglia degli Eliminati di recente: il cestino di Android in celle squadrate (come le altre griglie, G2) e, sotto, il cestino del NAS in righe
// (Fase 7, D26). Senza NAS la pagina e' quella di prima: nessuna intestazione, nessuna riga.

/**
 * La frase che distingue questo cestino da quello della Galleria di Samsung (E-P11). D-3 e D-B2 (prove sul campo): su One UI quello che l'app
 * elimina (`createTrashRequest`) compare **qui** (la riga resta nel MediaStore con `IS_TRASHED`, fuori dalla sua cartella) e anche nel Cestino
 * di Galleria: l'app lo elenca e lo ripristina con il dialogo di sistema. Quello che l'utente cestina da Galleria invece l'app non lo vede
 * (il file esce dal MediaStore): la frase lo dice e manda a Galleria → Cestino.
 */
@Composable
internal fun Explainer(hiddenNote: Boolean) {
  // CU-4 (fase I): una riga sola, il segno del tempo e «Restano 30 giorni»; il resto (quale cestino e' questo, la Galleria) dietro la «i».
  val title = stringResource(phoneTrashText(R.string.trash_explainer_title, R.string.trash_explainer_title_gallery))
  val detail = listOfNotNull(
    title + ".",
    stringResource(phoneTrashText(R.string.trash_explainer, R.string.trash_explainer_gallery)),
    if (hiddenNote) stringResource(R.string.trash_hidden_note) else null,
  ).joinToString(" ")
  TrashInfoLine(
    glyph = Icons.Rounded.Schedule,
    label = stringResource(R.string.trash_explainer_short),
    detail = detail,
    modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap),
  )
}

@Composable
internal fun TrashGrid(
  entries: List<TrashEntry>,
  nas: NasTrashSection,
  selection: Set<MediaId>,
  loader: ThumbnailLoader,
  onToggle: (MediaId) -> Unit,
  onRetry: () -> Unit,
  purgeBlocked: Boolean,
  topPadding: Dp,
  bottomPadding: Dp,
) {
  val gridState = rememberLazyGridState()
  val selecting = selection.isNotEmpty()

  // Come la griglia della Libreria: le miniature le chiede la finestra visibile, non la cella (D5).
  LaunchedEffect(entries, loader, gridState) {
    val byKey = entries.associateBy { it.id.value }
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
      .distinctUntilChanged()
      .collect { keys ->
        val visible = keys.mapNotNull { byKey[it] }
        val last = visible.lastOrNull()?.let { entries.indexOf(it) } ?: -1
        val ahead = entries.subList(min(entries.size, last + 1), min(entries.size, last + 1 + AHEAD))
        loader.window(visible.map { it.request() }, ahead.map { it.request() })
      }
  }

  // La parete di foto delle altre griglie (G2): celle squadrate, fessura di 1 dp.
  LazyVerticalGrid(
    columns = GridCells.Adaptive(CollectionsDimens.TrashCellMin),
    modifier = Modifier.fillMaxSize(),
    state = gridState,
    contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
    horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
  ) {
    item(key = "explainer", span = { GridItemSpan(maxLineSpan) }) { Explainer(hiddenNote = entries.any { it.hidden }) }
    if (entries.isNotEmpty()) {
      if (nas !is NasTrashSection.None) {
        item(key = "phone-header", span = { GridItemSpan(maxLineSpan) }) {
          TrashSectionHeader(title = stringResource(R.string.trash_phone_section), count = entries.size)
        }
      }
      items(entries, key = { it.id.value }) { entry ->
        TrashCell(entry = entry, loader = loader, selecting = selecting, selected = entry.id in selection, onToggle = onToggle)
      }
    }
    nasTrashSection(section = nas, selection = selection, purgeBlocked = purgeBlocked, onToggle = onToggle, onRetry = onRetry)
  }
}

@Composable
private fun TrashCell(
  entry: TrashEntry,
  loader: ThumbnailLoader,
  selecting: Boolean,
  selected: Boolean,
  onToggle: (MediaId) -> Unit,
) {
  val image by loader.image(entry.request().key, entry.item.ref)
  val base = when {
    entry.daysLeft <= 0 -> stringResource(if (entry.item.isVideo) R.string.trash_cell_video_today else R.string.trash_cell_photo_today)
    entry.item.isVideo -> pluralStringResource(R.plurals.trash_cell_video, entry.daysLeft, entry.daysLeft)
    else -> pluralStringResource(R.plurals.trash_cell_photo, entry.daysLeft, entry.daysLeft)
  }
  val description = if (entry.hidden) {
    base + ", " + stringResource(if (entry.item.isVideo) R.string.trash_hidden_video else R.string.trash_hidden_photo)
  } else {
    base
  }
  GalleryMediaCell(
    contentDescription = description,
    loaded = image != null,
    selectionMode = selecting,
    selected = selected,
    onClick = { onToggle(entry.id) },
    onLongClick = { onToggle(entry.id) },
    shape = RectangleShape,
    topEnd = if (entry.hidden) ({ HiddenPill() }) else null,
    bottomEnd = { DaysLeftPill(entry.daysLeft) },
  ) {
    image?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
  }
}

/** I giorni che restano, in una pillola scura sopra la foto (la coppia fissa "sopra una foto" del design system). */
@Composable
private fun DaysLeftPill(daysLeft: Int) {
  Text(
    text = if (daysLeft <= 0) stringResource(R.string.trash_days_left_today) else pluralStringResource(R.plurals.trash_days_left, daysLeft, daysLeft),
    modifier = Modifier
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = CollectionsDimens.DaysPillHorizontal, vertical = CollectionsDimens.DaysPillVertical),
    style = MaterialTheme.typography.labelSmall,
    color = GalleryOnPhoto.Content,
    maxLines = 1,
  )
}

/** Il segno "Nascosto" di un elemento nascosto eliminato: un lucchetto e la parola, sulla stessa coppia fissa sopra la foto. */
@Composable
private fun HiddenPill() {
  Row(
    modifier = Modifier
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = CollectionsDimens.DaysPillHorizontal, vertical = CollectionsDimens.DaysPillVertical),
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.PillGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Rounded.Lock,
      contentDescription = null,
      modifier = Modifier.size(CollectionsDimens.PillGlyph),
      tint = GalleryOnPhoto.Content,
    )
    Text(
      text = stringResource(R.string.trash_hidden_badge),
      style = MaterialTheme.typography.labelSmall,
      color = GalleryOnPhoto.Content,
      maxLines = 1,
    )
  }
}

private fun TrashEntry.request(): ThumbRequest =
  ThumbRequest(ThumbKey(mediaId = id, versionKey = item.trashedAtMs, bucket = ThumbBucket.M), item.ref)

/** Quante miniature si chiedono in anticipo oltre quelle visibili. */
private const val AHEAD = 12
