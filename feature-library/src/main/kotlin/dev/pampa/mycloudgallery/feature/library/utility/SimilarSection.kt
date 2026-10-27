package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import java.time.LocalDate

// Fase 7, P8 (D25, E-U3): la scheda "Simili". Scatti diversi ma quasi uguali, dello stesso giorno e a meno di 10 minuti. Di ogni gruppo la migliore e'
// da tenere; l'utente tocca uno scatto per tenerlo o per eliminarlo. Le preferite e le foto in un album non sono mai preselezionate per
// l'eliminazione, e in un gruppo almeno uno scatto resta.

/** Le azioni della scheda "Simili". */
internal class SimilarActions(
  val onClean: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onToggle: (String, MediaId) -> Unit = { _, _ -> },
  val onReset: (String) -> Unit = {},
)

internal fun ClusterView.listKey(): String = "c:${cluster.key}"

internal fun LazyListScope.similarItems(
  view: SimilarView,
  busy: Boolean,
  actions: SimilarActions,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
) {
  if (view.failed) {
    item(key = "similar-failed") {
      PageMessage(
        title = stringResource(R.string.duplicates_failed_title),
        detail = stringResource(R.string.duplicates_failed_detail),
        tone = FluidTone.Warning,
        actionLabel = stringResource(R.string.duplicates_retry),
        onAction = actions.onRetry,
      )
    }
  }
  if (view.clusters.isNotEmpty()) item(key = "similar-summary") { SimilarSummary(view, busy, actions.onClean) }
  // DP-4: le firme degli scatti di oggi si fanno di notte, a telefono in carica: una scheda vuota o corta lo dice invece di restare muta.
  if (view.signingLater && !view.scanning) {
    item(key = "similar-later") {
      Text(
        text = stringResource(R.string.duplicates_similar_later),
        modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (view.undecided > 0 && !view.scanning) {
    item(key = "similar-undecided") {
      Text(
        text = pluralStringResource(R.plurals.duplicates_similar_undecided, view.undecided, GalleryNumbers.format(view.undecided)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  items(view.clusters, key = { it.listKey() }) { cluster -> ClusterCard(cluster, actions, loader, refOf) }
  when {
    view.scanning -> item(key = "similar-scanning") { ScanningRow(stringResource(R.string.duplicates_similar_searching)) }
    view.isEmpty -> item(key = "similar-empty") {
      CollectionEmptyState(
        icon = Icons.Rounded.CleaningServices,
        title = stringResource(R.string.duplicates_similar_empty_title),
        detail = stringResource(R.string.duplicates_similar_empty_detail),
      )
    }
  }
}

@Composable
private fun SimilarSummary(view: SimilarView, busy: Boolean, onClean: () -> Unit) {
  FluidCard(modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap)) {
    Text(
      text = pluralStringResource(R.plurals.duplicates_similar_summary_title, view.clusters.size, GalleryNumbers.format(view.clusters.size)),
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      text = stringResource(R.string.duplicates_similar_summary_detail),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (view.toRemove > 0) {
      FluidButton(
        text = pluralStringResource(R.plurals.duplicates_similar_action, view.toRemove, GalleryNumbers.format(view.toRemove)),
        onClick = onClean,
        size = FluidButtonSize.Large,
        fillWidth = true,
        enabled = !busy,
      )
    }
  }
}

/** Un gruppo: la data, quanti scatti, e gli scatti in fila (scorre di lato se sono tanti). */
@Composable
private fun ClusterCard(view: ClusterView, actions: SimilarActions, loader: ThumbnailLoader, refOf: (MediaId) -> MediaRef?) {
  val cluster = view.cluster
  val day = cluster.shots.first().localDay
  val date = runCatching { GalleryDates.date(LocalDate.of(day / 10_000, day / 100 % 100, day % 100)) }.getOrDefault("")
  FluidCard(modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap / 2)) {
    Text(
      text = pluralStringResource(R.plurals.duplicates_cluster_title, cluster.shots.size, GalleryNumbers.format(cluster.shots.size), date),
      style = MaterialTheme.typography.titleSmall,
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(UtilityDimens.ThumbGap),
    ) {
      cluster.shots.forEachIndexed { index, shot ->
        ShotTile(
          media = shot.media,
          index = index + 1,
          total = cluster.shots.size,
          removed = shot.media in view.remove,
          tag = tagOf(view, shot.media),
          loader = loader,
          refOf = refOf,
          onToggle = { actions.onToggle(cluster.key, shot.media) },
        )
      }
    }
    if (view.protectedChosen.isNotEmpty()) {
      Text(
        text = stringResource(R.string.duplicates_cluster_protected_chosen),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (view.remove != cluster.removeByDefault) {
      FluidButton(
        text = stringResource(R.string.duplicates_cluster_reset),
        onClick = { actions.onReset(cluster.key) },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
      )
    }
  }
}

/** Il segno in alto di uno scatto: preferita, in un album o la migliore. */
@Composable
private fun tagOf(view: ClusterView, media: MediaId): String? {
  val details = view.cluster.details[media]
  return when {
    details?.favorite == true -> stringResource(R.string.duplicates_shot_favorite)
    details?.inAlbum == true -> stringResource(R.string.duplicates_shot_album)
    media == view.cluster.best -> stringResource(R.string.duplicates_shot_best)
    else -> null
  }
}

/**
 * Uno scatto: la miniatura con, sopra, un'etichetta se e' la migliore, una preferita o in un album, e in basso il segno di cosa ne sara' (la spunta
 * o il cestino); sotto la miniatura la **parola** ("Da tenere" / "Da eliminare"), mai il solo segno o il solo colore. Si tocca per cambiare (tutta la
 * colonna e' il bersaglio); TalkBack lo legge come una casella.
 */
@Composable
private fun ShotTile(
  media: MediaId,
  index: Int,
  total: Int,
  removed: Boolean,
  tag: String?,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
  onToggle: () -> Unit,
) {
  val state = stringResource(if (removed) R.string.duplicates_shot_remove else R.string.duplicates_shot_keep)
  val photo = stringResource(R.string.duplicates_shot_photo, GalleryNumbers.format(index), GalleryNumbers.format(total))
  val description = listOfNotNull(photo, state, tag).joinToString(", ")
  Column(
    modifier = Modifier
      .width(UtilityDimens.ThumbLarge)
      .toggleable(value = removed, role = Role.Checkbox, onValueChange = { onToggle() })
      .semantics { contentDescription = description },
    verticalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap / 2),
  ) {
    Box(modifier = Modifier.size(UtilityDimens.ThumbLarge)) {
      MediaThumb(media, loader, refOf, UtilityDimens.ThumbLarge)
      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(CollectionsDimens.PillGap)
          .size(UtilityDimens.ShotBadge)
          .background(GalleryOnPhoto.scrim(), FluidCapsuleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (removed) Icons.Rounded.Delete else Icons.Rounded.Check,
          contentDescription = null,
          modifier = Modifier.size(UtilityDimens.BadgeGlyph),
          tint = GalleryOnPhoto.Content,
        )
      }
      if (tag != null) Pill(text = tag, modifier = Modifier.align(Alignment.TopStart).padding(CollectionsDimens.PillGap))
    }
    Text(
      text = state,
      modifier = Modifier.fillMaxWidth(),
      style = MaterialTheme.typography.labelMedium,
      color = if (removed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/** Una pillola scura sopra la foto (la coppia fissa "sopra una foto" del design system): l'etichetta di uno scatto. */
@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = CollectionsDimens.DaysPillHorizontal, vertical = CollectionsDimens.DaysPillVertical),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = GalleryOnPhoto.Content, maxLines = 1)
  }
}
