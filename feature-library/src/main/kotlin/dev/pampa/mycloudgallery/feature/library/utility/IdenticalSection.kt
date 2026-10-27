package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.CopyPart
import dev.pampa.mycloudgallery.core.library.duplicates.GroupKey
import dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal
import dev.pampa.mycloudgallery.core.library.duplicates.IdentCopy
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState

// Fase 7, P8 (D25): la scheda "Identici" dei Doppioni. Il riepilogo con "Pulizia guidata", i gruppi con una copia da tenere per parte (le altre
// preselezionate), "Rivedi" per cambiare la copia da tenere, le copie che le regole lasciano dove sono e i gruppi "da verificare" (D27).

/** Le azioni della scheda "Identici". */
internal class IdenticalActions(
  val onCleanup: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onToggleReview: (GroupKey) -> Unit = {},
  val onKeep: (GroupKey, LocationId) -> Unit = { _, _ -> },
  val onReset: (GroupKey) -> Unit = {},
)

/** Il testo di un gruppo per la chiave della lista. */
internal fun GroupProposal.listKey(): String = "g:${group.media.value}:${group.version.value}:${group.part.code}"

internal fun LazyListScope.identicalItems(
  view: IdenticalView,
  expanded: Set<GroupKey>,
  busy: Boolean,
  actions: IdenticalActions,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
  familyName: (String) -> String?,
) {
  when {
    view.failed -> item(key = "identical-failed") {
      PageMessage(
        title = stringResource(R.string.duplicates_failed_title),
        detail = stringResource(R.string.duplicates_failed_detail),
        tone = FluidTone.Warning,
        actionLabel = stringResource(R.string.duplicates_retry),
        onAction = actions.onRetry,
      )
    }
    view.scanning && view.groups.isEmpty() -> item(key = "identical-scanning") { ScanningRow(stringResource(R.string.duplicates_scanning)) }
    // UT-1: con le sole foto selezionate (o prima che la Libreria sia letta) non si cerca niente, e lo si dice invece di dire "nessun doppione".
    view.restricted -> item(key = "identical-restricted") {
      CollectionEmptyState(
        icon = Icons.Rounded.CleaningServices,
        title = stringResource(R.string.duplicates_identical_restricted_title),
        detail = stringResource(R.string.duplicates_identical_restricted_detail),
      )
    }
    view.isEmpty -> item(key = "identical-empty") {
      CollectionEmptyState(
        icon = Icons.Rounded.CleaningServices,
        title = stringResource(R.string.duplicates_identical_empty_title),
        detail = stringResource(R.string.duplicates_identical_empty_detail),
      )
    }
    else -> {
      // DP-1: file del NAS che sembrano uguali e aspettano di essere letti per intero: ancora non sono doppioni provati.
      if (view.verifying > 0) {
        item(key = "identical-verifying") {
          Text(
            text = pluralStringResource(R.plurals.duplicates_identical_verifying, view.verifying, GalleryNumbers.format(view.verifying)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (view.cleanable.isNotEmpty()) {
        item(key = "identical-summary") { IdenticalSummary(view = view, busy = busy, onCleanup = actions.onCleanup) }
        items(view.cleanable, key = { it.listKey() }) { proposal ->
          GroupCard(proposal, proposal.group.key in expanded, actions, loader, refOf, familyName)
        }
      }
      if (view.inOthersArea > 0) {
        item(key = "identical-others") {
          Text(
            text = pluralStringResource(R.plurals.duplicates_others_area_note, view.inOthersArea, GalleryNumbers.format(view.inOthersArea)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (view.settled.isNotEmpty()) {
        item(key = "identical-settled-title") {
          SectionTitle(stringResource(R.string.duplicates_settled_title), stringResource(R.string.duplicates_settled_detail))
        }
        items(view.settled, key = { it.listKey() }) { proposal ->
          GroupCard(proposal, proposal.group.key in expanded, actions, loader, refOf, familyName)
        }
      }
      if (view.toVerify.isNotEmpty()) {
        item(key = "identical-toverify-title") {
          SectionTitle(stringResource(R.string.duplicates_toverify_title), stringResource(R.string.duplicates_toverify_detail))
        }
        items(view.toVerify, key = { it.listKey() }) { proposal ->
          GroupCard(proposal, expanded = false, actions = actions, loader = loader, refOf = refOf, familyName = familyName)
        }
      }
      if (view.scanning) item(key = "identical-scanning-more") { ScanningRow(stringResource(R.string.duplicates_scanning)) }
    }
  }
}

@Composable
internal fun ScanningRow(text: String) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(UtilityDimens.CardPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap),
  ) {
    FluidLoading()
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun IdenticalSummary(view: IdenticalView, busy: Boolean, onCleanup: () -> Unit) {
  FluidCard(modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap)) {
    Text(
      text = pluralStringResource(R.plurals.duplicates_identical_summary_title, view.cleanable.size, GalleryNumbers.format(view.cleanable.size)),
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      text = pluralStringResource(
        R.plurals.duplicates_identical_summary_detail,
        view.copiesToRemove,
        GalleryNumbers.format(view.copiesToRemove),
        UtilityText.size(view.reclaimableBytes),
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FluidButton(
      text = stringResource(R.string.duplicates_cleanup_action),
      onClick = onCleanup,
      size = FluidButtonSize.Large,
      fillWidth = true,
      enabled = !busy,
    )
  }
}

/** La scheda di un gruppo: miniatura, titolo, le copie con il loro ruolo e "Rivedi". */
@Composable
private fun GroupCard(
  proposal: GroupProposal,
  expanded: Boolean,
  actions: IdenticalActions,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
  familyName: (String) -> String?,
) {
  val group = proposal.group
  val key = group.key
  val kind = stringResource(if (group.isVideo) R.string.duplicates_group_video else R.string.duplicates_group_photo)
  val where = stringResource(if (group.part == CopyPart.PHONE) R.string.duplicates_group_phone else R.string.duplicates_group_nas)
  val title = stringResource(R.string.duplicates_group_title, kind, copiesText(group.copies.size), where)
  FluidCard(modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap / 2)) {
    Row(horizontalArrangement = Arrangement.spacedBy(UtilityDimens.CardPadding), verticalAlignment = Alignment.Top) {
      MediaThumb(group.media, loader, refOf, UtilityDimens.Thumb)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap / 2)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        when (proposal.status) {
          IdenticalStatus.CLEANABLE -> Text(
            text = stringResource(R.string.duplicates_group_save, UtilityText.size(proposal.reclaimableBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          IdenticalStatus.TO_VERIFY -> RolePill(stringResource(R.string.duplicates_toverify_badge), GalleryFooterTone.Attention)
          IdenticalStatus.NOTHING_TO_REMOVE -> Unit
        }
      }
    }
    proposal.keep?.let { CopyLine(it, stringResource(R.string.duplicates_copy_keep), GalleryFooterTone.Calm, familyName) }
    proposal.remove.forEach { CopyLine(it, stringResource(R.string.duplicates_copy_remove), GalleryFooterTone.Attention, familyName) }
    proposal.blocked.forEach { CopyLine(it.copy, blockedText(it.reason), GalleryFooterTone.Waiting, familyName) }
    if (proposal.status != IdenticalStatus.TO_VERIFY && proposal.group.copies.count { it.area != CopyArea.OTHERS } > 1) {
      FluidButton(
        text = stringResource(if (expanded) R.string.duplicates_review_done else R.string.duplicates_review),
        onClick = { actions.onToggleReview(key) },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
      )
    }
    if (expanded) ReviewRows(proposal, actions, familyName)
  }
}

/** Una copia: dov'e', il percorso e che cosa ne sara'. La parola del ruolo c'e' sempre. */
@Composable
private fun CopyLine(copy: IdentCopy, role: String, tone: GalleryFooterTone, familyName: (String) -> String?) {
  val area = areaLabel(copy, familyName)
  val verified = if (copy.isSafeCopy) " · " + stringResource(R.string.duplicates_copy_verified) else ""
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) { contentDescription = "$area, ${copy.path}$verified, $role" },
    horizontalArrangement = Arrangement.spacedBy(UtilityDimens.RowGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = area + verified, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(text = copy.path, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
    RolePill(role, tone)
  }
}

/** "Rivedi": ogni copia con il permesso, per scegliere quale tenere. Le "mai" valgono lo stesso: la proposta si rifa con la stessa regola. */
@Composable
private fun ReviewRows(proposal: GroupProposal, actions: IdenticalActions, familyName: (String) -> String?) {
  val key = proposal.group.key
  proposal.group.copies.filter { it.area != CopyArea.OTHERS }.forEach { copy ->
    val isKeep = proposal.keep?.location == copy.location
    FluidListRow(
      title = areaLabel(copy, familyName),
      subtitle = copy.path,
      leading = if (isKeep) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
      selected = isKeep,
      disclosure = false,
      onClick = { actions.onKeep(key, copy.location) },
      meta = if (isKeep) stringResource(R.string.duplicates_copy_keep) else stringResource(R.string.duplicates_keep_this),
    )
  }
  FluidButton(
    text = stringResource(R.string.duplicates_review_reset),
    onClick = { actions.onReset(key) },
    style = FluidButtonStyle.Tinted,
  )
}
