package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import dev.pampa.mycloudgallery.feature.library.utility.UtilityDimens
import dev.pampa.mycloudgallery.feature.library.utility.UtilityText

// Fase 7, P8 (D26): la parte del NAS degli Eliminati di recente. Righe, non celle: una voce del cestino del NAS e' un nome, una cartella d'origine e un
// peso (le miniature dei file nel cestino non ci sono). Sono dentro la stessa griglia del telefono, a tutta larghezza.

/** Da quanti giorni rimasti in giu' la riga chiede attenzione. */
private const val SOON_DAYS = 3

/**
 * La parte del NAS: l'intestazione con quante voci, gli avvisi (NAS che non risponde, sola lettura, "Elimina subito" non disponibile) e le righe.
 * Con [NasTrashSection.None] non scrive niente: la pagina e' quella di prima.
 *
 * @param purgeBlocked fra le voci scelte ce n'e' una che non si puo' eliminare subito: la frase dice perche'.
 */
internal fun LazyGridScope.nasTrashSection(
  section: NasTrashSection,
  selection: Set<MediaId>,
  purgeBlocked: Boolean,
  onToggle: (MediaId) -> Unit,
  onRetry: () -> Unit,
) {
  if (section is NasTrashSection.None) return
  item(key = "nas-header", span = { GridItemSpan(maxLineSpan) }) {
    val rows = (section as? NasTrashSection.Rows)?.entries
    TrashSectionHeader(
      title = stringResource(R.string.trash_nas_section),
      count = rows?.size,
    )
  }
  when (section) {
    NasTrashSection.None -> Unit
    NasTrashSection.Loading -> item(key = "nas-loading", span = { GridItemSpan(maxLineSpan) }) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(UtilityDimens.RowGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap),
      ) {
        FluidLoading()
        Text(
          text = stringResource(R.string.trash_nas_loading),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    NasTrashSection.Failed -> item(key = "nas-failed", span = { GridItemSpan(maxLineSpan) }) {
      TrashSectionMessage(
        title = stringResource(R.string.trash_nas_failed_title),
        detail = stringResource(R.string.trash_nas_failed_detail),
        tone = FluidTone.Warning,
        actionLabel = stringResource(R.string.trash_retry),
        onAction = onRetry,
      )
    }
    NasTrashSection.Denied -> item(key = "nas-denied", span = { GridItemSpan(maxLineSpan) }) {
      TrashSectionMessage(
        title = stringResource(R.string.trash_nas_denied_title),
        detail = stringResource(R.string.trash_nas_denied_detail),
        tone = FluidTone.Warning,
      )
    }
    is NasTrashSection.Rows -> {
      if (section.offline) {
        item(key = "nas-offline", span = { GridItemSpan(maxLineSpan) }) {
          TrashSectionMessage(
            title = stringResource(R.string.trash_nas_offline_title),
            detail = stringResource(R.string.trash_nas_offline_detail),
            tone = FluidTone.Warning,
            actionLabel = stringResource(R.string.trash_retry),
            onAction = onRetry,
          )
        }
      } else if (!section.writable && section.entries.isNotEmpty()) {
        item(key = "nas-readonly", span = { GridItemSpan(maxLineSpan) }) {
          TrashSectionMessage(
            title = stringResource(R.string.trash_nas_readonly_title),
            detail = stringResource(R.string.trash_nas_readonly_detail),
            tone = FluidTone.Neutral,
          )
        }
      }
      if (purgeBlocked) {
        item(key = "nas-only-own", span = { GridItemSpan(maxLineSpan) }) {
          TrashSectionMessage(
            title = stringResource(R.string.trash_purge_only_own_title),
            detail = stringResource(R.string.trash_purge_only_own),
            tone = FluidTone.Neutral,
          )
        }
      }
      if (section.entries.isEmpty()) {
        item(key = "nas-empty", span = { GridItemSpan(maxLineSpan) }) {
          Text(
            text = stringResource(R.string.trash_nas_empty),
            modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        items(section.entries.size, key = { "nas-${section.entries[it].id.value}" }, span = { GridItemSpan(maxLineSpan) }) { index ->
          val entry = section.entries[index]
          NasTrashRowItem(
            entry = entry,
            selecting = selection.isNotEmpty(),
            selected = entry.id in selection,
            onToggle = { onToggle(entry.id) },
          )
        }
        // CU-3: «Senza record» non sta piu' su ogni riga (il segno del problema sulla piastrella); il perche' una volta, dietro la «i».
        if (section.entries.any { it.row.withoutValidRecord }) {
          item(key = "nas-invalid", span = { GridItemSpan(maxLineSpan) }) {
            TrashSectionMessage(
              title = stringResource(R.string.trash_nas_invalid_title),
              detail = stringResource(R.string.trash_nas_invalid_detail),
              tone = FluidTone.Warning,
            )
          }
        }
      }
    }
  }
}

/** L'intestazione di una sezione della pagina ("Dal telefono", "Dal NAS"), con quante voci ci sono se si sanno. */
@Composable
internal fun TrashSectionHeader(title: String, count: Int?, modifier: Modifier = Modifier) {
  Text(
    text = if (count != null) title + " · " + GalleryNumbers.format(count) else title,
    modifier = modifier
      .fillMaxWidth()
      .padding(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = UtilityDimens.SectionTop, bottom = UtilityDimens.SectionBottom)
      .semantics { heading() },
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * Un avviso della sezione (il NAS che non risponde, la sola lettura, «Elimina subito» solo per le proprie voci): il segno, due parole e la «i» con il
 * perche' sotto (CU-3, fase I: niente riquadri di frasi), e l'azione se c'e'.
 */
@Composable
private fun TrashSectionMessage(
  title: String,
  detail: String,
  tone: FluidTone,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap)) {
    TrashInfoLine(
      glyph = if (tone == FluidTone.Warning || tone == FluidTone.Danger) Icons.Rounded.Warning else Icons.Rounded.Info,
      label = title,
      detail = detail,
      tint = if (tone == FluidTone.Warning || tone == FluidTone.Danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (actionLabel != null && onAction != null) {
      FluidButton(text = actionLabel, onClick = onAction, style = FluidButtonStyle.Tinted, grip = FluidPressGrip.YieldsToScroll)
    }
  }
}

/** Una riga che si tocca: il segno, due parole, la «i»; la frase sotto quando e' aperta. TalkBack la sente intera, senza tocco. */
@Composable
internal fun TrashInfoLine(glyph: ImageVector, label: String, detail: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  var open by rememberSaveable(label) { mutableStateOf(false) }
  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .fluidPressable(onClick = { open = !open }, role = Role.Button)
        .clearAndSetSemantics { contentDescription = "$label. $detail" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap),
    ) {
      Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(CollectionsDimens.PillGlyph * 2))
      Text(text = label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      GalleryInfoGlyph(open = open)
    }
    GalleryInfoDetail(text = detail, visible = open, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Una voce del cestino del NAS: il nome, da dove veniva e quanto pesa, i giorni rimasti e chi l'ha eliminata. */
@Composable
internal fun NasTrashRowItem(entry: NasTrashEntry, selecting: Boolean, selected: Boolean, onToggle: () -> Unit) {
  val row = entry.row
  val name = row.name ?: stringResource(R.string.trash_nas_no_name)
  val icon = when {
    row.isFolder -> Icons.Rounded.Folder
    row.isVideo -> Icons.Rounded.Movie
    row.name == null -> Icons.Rounded.InsertDriveFile
    else -> Icons.Rounded.Image
  }
  // CU-3, CU-8 (fase I): la piastrella colorata del tipo, come in Cartelle NAS (la cartella del NAS, la foto, il video, un file), con il segno del
  // problema (record non valido) o dell'attenzione (scade fra pochi giorni) nell'angolo. Niente piastrelle grigie.
  val family = when {
    row.isFolder -> GalleryIconFamily.Backup
    row.isVideo -> GalleryIconFamily.Memories
    row.name == null -> GalleryIconFamily.System
    else -> GalleryIconFamily.Photos
  }
  val mark = when {
    row.withoutValidRecord -> GalleryIconStatus.Problem
    row.daysLeft != null && row.daysLeft <= SOON_DAYS -> GalleryIconStatus.Attention
    else -> null
  }
  val size = if (row.isFolder && row.fileCount != null) {
    stringResource(
      R.string.trash_nas_folder_files,
      pluralStringResource(R.plurals.trash_nas_file_count, row.fileCount, GalleryNumbers.format(row.fileCount)),
      UtilityText.size(row.sizeBytes),
    )
  } else {
    UtilityText.size(row.sizeBytes)
  }
  // A schermo solo il peso; da dove veniva e chi l'ha eliminata li dice TalkBack (e la «i» della sezione per i record non validi).
  val origin = when {
    row.withoutValidRecord -> stringResource(R.string.trash_nas_invalid_detail)
    row.folder != null -> stringResource(R.string.trash_nas_from, row.folder, size)
    else -> stringResource(R.string.trash_nas_from_root, size)
  }
  val left = daysLeftText(row.daysLeft)
  val by = when {
    row.withoutValidRecord -> null
    row.deletableNow -> stringResource(R.string.trash_nas_by_me)
    row.author != null -> stringResource(R.string.trash_nas_by, row.author)
    else -> null
  }
  val description = listOfNotNull(
    name,
    if (row.isFolder) stringResource(R.string.trash_nas_folder_word) else null,
    origin,
    left,
    by,
    if (row.withoutValidRecord) stringResource(R.string.trash_nas_invalid_title) else null,
  ).joinToString(", ")
  val stateText = stringResource(if (selected) R.string.trash_nas_state_selected else R.string.trash_nas_state_not_selected)
  FluidListRow(
    title = name,
    subtitle = size,
    tone = FluidTone.Neutral,
    leading = { GalleryIconTile(icon = icon, family = family, status = mark) },
    leadingTile = false,
    labels = { DaysMark(row.daysLeft) },
    badge = { SelectDot(selected = selected, visible = selecting) },
    disclosure = false,
    titleMaxLines = 1,
    onClick = onToggle,
    onLongClick = if (!selecting) onToggle else null,
    modifier = Modifier
      .padding(horizontal = GalleryDimens.Gutter)
      .semantics(mergeDescendants = true) {
        contentDescription = description
        if (selecting) {
          this.selected = selected
          stateDescription = stateText
        }
      },
  )
}

/** Il pallino di selezione: pieno col segno se scelto, un anello se no. Il significato lo legge TalkBack dalla riga, non dal disegno. */
@Composable
private fun SelectDot(selected: Boolean, visible: Boolean, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(UtilityDimens.SelectDot), contentAlignment = Alignment.Center) {
    if (!visible) return@Box
    val scheme = MaterialTheme.colorScheme
    Box(
      modifier = Modifier
        .size(UtilityDimens.SelectDot)
        .clip(FluidCapsuleShape)
        .then(
          if (selected) {
            Modifier.background(scheme.primary)
          } else {
            Modifier.border(width = UtilityDimens.SelectRing, color = scheme.onSurfaceVariant, shape = FluidCapsuleShape)
          },
        )
        .clearAndSetSemantics { },
      contentAlignment = Alignment.Center,
    ) {
      if (selected) Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(UtilityDimens.SelectGlyph))
    }
  }
}

/** "Restano 29 giorni", "Scade oggi"; la scadenza non nota si dice. */
@Composable
private fun daysLeftText(days: Int?): String = when {
  days == null -> stringResource(R.string.trash_nas_days_unknown)
  days <= 0 -> stringResource(R.string.trash_nas_expires_today)
  else -> pluralStringResource(R.plurals.trash_nas_days_left, days, GalleryNumbers.format(days))
}

/** CU-3: i giorni che restano come un segno piccolo e il numero («29»), «Oggi» l'ultimo giorno; niente se la scadenza non si sa (lo dice TalkBack). */
@Composable
private fun DaysMark(days: Int?) {
  if (days == null) return
  val soon = days <= SOON_DAYS
  val ink = if (soon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    modifier = Modifier.clearAndSetSemantics {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.PillGap),
  ) {
    Icon(imageVector = Icons.Rounded.Schedule, contentDescription = null, tint = ink, modifier = Modifier.size(CollectionsDimens.PillGlyph))
    Text(
      text = if (days <= 0) stringResource(R.string.trash_nas_today_short) else GalleryNumbers.format(days),
      style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
      color = ink,
      maxLines = 1,
    )
  }
}
