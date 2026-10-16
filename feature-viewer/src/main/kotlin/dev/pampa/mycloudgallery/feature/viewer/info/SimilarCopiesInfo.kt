package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Videocam
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopy
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, D36, E-L10...E-L13; riga P15 del §4.4): la sezione "N copie" del pannello informazioni.
// Una riga per copia (dove sta, risoluzione, dimensione, formato; si tocca per guardarla), poi le due azioni "Mostra questa come
// principale" e "Separa". Solo le righe del design system (`GalleryInfoRow`): nessuno stile nuovo (D30). La sezione e' una funzione
// pura di uno stato: si prova senza schermo. Come si aggancia al pannello: `rememberSimilarCopiesPanel` (SimilarCopiesPanel.kt).

/**
 * Le tre cose che l'utente puo' fare dalla sezione.
 *
 * @property onView guarda un'altra copia del gruppo (il viewer ci va: P7 lo lega alla sua navigazione).
 * @property onMakeMain "Mostra questa come principale" per la copia che si sta guardando.
 * @property onSeparate "Separa" la copia che si sta guardando: apre il foglio di conferma, non separa subito.
 */
internal class SimilarCopiesActions(
  val onView: (MediaId) -> Unit,
  val onMakeMain: () -> Unit,
  val onSeparate: () -> Unit,
)

/**
 * La sezione del pannello per [group], vista dalla copia [viewing]. Mai due volte la stessa cosa: la copia che si guarda non si tocca
 * (non c'e' dove andare), "Mostra questa come principale" non c'e' se la copia in vista e' gia' la principale.
 */
internal fun similarCopiesInfoSection(
  format: ViewerFormat,
  group: SimilarCopiesGroup,
  viewing: MediaId,
  actions: SimilarCopiesActions,
  busy: Boolean = false,
): InfoSectionModel {
  val res = format.resources
  val rows = ArrayList<GalleryInfoRow>()
  for (copy in group.copies) {
    rows += copyRow(format, copy, isCover = copy.mediaId == group.cover, isCurrent = copy.mediaId == viewing, onView = actions.onView, enabled = !busy)
  }
  if (group.hidden) {
    // E-L11: nascondere dalla cella nasconde tutte le copie; il pannello lo dice.
    rows += GalleryInfoRow(
      title = res.getString(R.string.viewer_similar_hidden),
      caption = res.getString(R.string.viewer_similar_hidden_caption),
      icon = Icons.Rounded.VisibilityOff,
    )
  }
  if (group.cover != viewing && group.copies.any { it.mediaId == viewing }) {
    rows += GalleryInfoRow(
      title = res.getString(R.string.viewer_similar_make_main),
      caption = res.getString(R.string.viewer_similar_make_main_caption),
      icon = Icons.Rounded.Star,
      onClick = actions.onMakeMain.takeUnless { busy },
    )
  }
  if (group.copies.any { it.mediaId == viewing }) {
    rows += GalleryInfoRow(
      title = res.getString(R.string.viewer_similar_separate),
      caption = res.getString(R.string.viewer_similar_separate_caption),
      icon = Icons.Rounded.CallSplit,
      tone = FluidTone.Warning,
      onClick = actions.onSeparate.takeUnless { busy },
    )
  }
  return InfoSectionModel(
    header = res.getQuantityString(R.plurals.viewer_similar_header, group.copies.size, group.copies.size),
    rows = rows,
  )
}

/** La riga di una copia: "4000 × 3000 · 3,2 MB" e sotto "Telefono · JPEG". */
private fun copyRow(
  format: ViewerFormat,
  copy: SimilarCopy,
  isCover: Boolean,
  isCurrent: Boolean,
  onView: (MediaId) -> Unit,
  enabled: Boolean,
): GalleryInfoRow {
  val res = format.resources
  val size = format.fileSize(copy.bytes)
  val title = if (copy.width > 0 && copy.height > 0) {
    res.getString(R.string.viewer_similar_pair, res.getString(R.string.viewer_dimensions, copy.width, copy.height), size)
  } else {
    size
  }
  val where = placesText(format, copy.places)
  val kind = format.formatName(copy.mime)
  val caption = buildString {
    append(if (kind != null) res.getString(R.string.viewer_similar_pair, where, kind) else where)
    if (isCurrent) {
      append('\n').append(res.getString(R.string.viewer_similar_current))
    } else if (copy.cameraOriginal) {
      append('\n').append(res.getString(R.string.viewer_similar_original))
    }
  }
  return GalleryInfoRow(
    title = title,
    caption = caption,
    icon = iconOf(copy),
    tone = if (isCurrent) FluidTone.Primary else FluidTone.Neutral,
    pill = if (isCover) res.getString(R.string.viewer_similar_main_pill) else null,
    pillTone = FluidTone.Success,
    onClick = if (isCurrent || !enabled) null else ({ onView(copy.mediaId) }),
  )
}

/** "Telefono", "NAS, area privata", "Telefono e NAS, area privata". */
internal fun placesText(format: ViewerFormat, places: Set<CopyPlace>): String {
  val res = format.resources
  val phone = CopyPlace.PHONE in places
  val nas = listOfNotNull(
    res.getString(R.string.viewer_similar_place_nas_private).takeIf { CopyPlace.NAS_PRIVATE in places },
    res.getString(R.string.viewer_similar_place_nas_common).takeIf { CopyPlace.NAS_COMMON in places },
  )
  val nasText = when (nas.size) {
    0 -> null
    1 -> nas.single()
    else -> res.getString(R.string.viewer_similar_places_join, nas[0], nas[1])
  }
  return when {
    phone && nasText != null -> res.getString(R.string.viewer_similar_places_join, res.getString(R.string.viewer_similar_place_phone), nasText)
    phone -> res.getString(R.string.viewer_similar_place_phone)
    nasText != null -> nasText
    else -> res.getString(R.string.viewer_similar_place_unknown)
  }
}

private fun iconOf(copy: SimilarCopy) = when {
  CopyPlace.PHONE in copy.places -> Icons.Rounded.PhoneAndroid
  CopyPlace.NAS_PRIVATE in copy.places -> Icons.Rounded.Storage
  CopyPlace.NAS_COMMON in copy.places -> Icons.Rounded.Group
  copy.kind == MediaKind.VIDEO -> Icons.Rounded.Videocam
  else -> Icons.Rounded.Image
}
