package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
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
        item(key = "nas-detail", span = { GridItemSpan(maxLineSpan) }) {
          Text(
            text = stringResource(R.string.trash_nas_section_detail),
            modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
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

@Composable
private fun TrashSectionMessage(
  title: String,
  detail: String,
  tone: FluidTone,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    FluidInlineMessage(
      message = detail,
      title = title,
      modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap),
      tone = tone,
    )
    if (actionLabel != null && onAction != null) {
      FluidButton(
        text = actionLabel,
        onClick = onAction,
        style = FluidButtonStyle.Tinted,
        modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
      )
    }
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
  val size = if (row.isFolder && row.fileCount != null) {
    stringResource(
      R.string.trash_nas_folder_files,
      pluralStringResource(R.plurals.trash_nas_file_count, row.fileCount, GalleryNumbers.format(row.fileCount)),
      UtilityText.size(row.sizeBytes),
    )
  } else {
    UtilityText.size(row.sizeBytes)
  }
  val subtitle = when {
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
  val meta = listOfNotNull(left, by).joinToString(" · ").takeIf { it.isNotEmpty() }
  val pill = urgencyNote(row)
  val description = listOfNotNull(
    name,
    if (row.isFolder) stringResource(R.string.trash_nas_folder_word) else null,
    size,
    left,
    if (row.withoutValidRecord) stringResource(R.string.trash_nas_invalid_pill) else null,
  ).joinToString(", ")
  val stateText = stringResource(if (selected) R.string.trash_nas_state_selected else R.string.trash_nas_state_not_selected)
  FluidListRow(
    title = name,
    subtitle = subtitle,
    meta = meta,
    tone = if (row.withoutValidRecord) FluidTone.Danger else FluidTone.Neutral,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    labels = pill?.let { note -> { GalleryFooterPill(note = note, onClick = null) } },
    badge = { SelectDot(selected = selected, visible = selecting) },
    disclosure = false,
    titleMaxLines = 2,
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

/** La pillola, solo se serve: nessun record valido, scade oggi, scade fra pochi giorni. */
@Composable
private fun urgencyNote(row: NasTrashRow): GalleryFooterNote? {
  val days = row.daysLeft
  return when {
    row.withoutValidRecord -> GalleryFooterNote(GalleryFooterTone.Alarm, stringResource(R.string.trash_nas_invalid_pill))
    days != null && days <= 0 -> GalleryFooterNote(GalleryFooterTone.Attention, stringResource(R.string.trash_nas_expires_today))
    days != null && days <= SOON_DAYS -> GalleryFooterNote(GalleryFooterTone.Attention, stringResource(R.string.trash_nas_pill_soon))
    else -> null
  }
}
