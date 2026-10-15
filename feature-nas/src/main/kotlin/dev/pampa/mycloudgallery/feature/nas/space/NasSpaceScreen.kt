package dev.pampa.mycloudgallery.feature.nas.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.confirm.areaLabel
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import dev.pampa.mycloudgallery.feature.nas.contract.areaOf
import dev.pampa.mycloudgallery.feature.nas.ui.FileKind
import dev.pampa.mycloudgallery.feature.nas.ui.NasLabel
import dev.pampa.mycloudgallery.feature.nas.ui.NasLoadingBlock
import dev.pampa.mycloudgallery.feature.nas.ui.NasNotice
import dev.pampa.mycloudgallery.feature.nas.ui.NasPage
import dev.pampa.mycloudgallery.feature.nas.ui.NasProgressBar
import dev.pampa.mycloudgallery.feature.nas.ui.NasSectionTitle
import dev.pampa.mycloudgallery.feature.nas.ui.NasSelectDot
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import dev.pampa.mycloudgallery.feature.nas.ui.opErrorText

/**
 * **Spazio NAS**: quanto spazio libero c'e', se il tuo account ha una quota, i file piu' grandi, cosa occupa il cestino e
 * l'indice. Si calcola quando si apre la pagina. Sola lettura.
 *
 * @param onOpenTrash porta a "Eliminati di recente del NAS" dalla riga del cestino; null = la riga non si tocca.
 */
@Composable
fun NasSpaceRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  onOpenTrash: (() -> Unit)? = null,
  viewModel: NasSpaceViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  NasSpaceScreen(
    state = state,
    onSelectArea = viewModel::select,
    onReload = viewModel::reload,
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
    onOpenTrash = onOpenTrash,
  )
}

/** La pagina per uno [state]: nessun effetto oltre a cambiare area e ricalcolare. La provano i test e gli screenshot. */
@Composable
internal fun NasSpaceScreen(
  state: SpaceUi,
  onSelectArea: (String) -> Unit,
  onReload: () -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  onOpenTrash: (() -> Unit)? = null,
  initiallyExpanded: Boolean = false,
) {
  var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
  NasPage(
    label = NasLabel(stringResource(R.string.nas_space_title), state.share?.let { areaLabel(it, state.areas) }),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    commands = spaceCommands(onReload),
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = NasDimens.DashboardMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(NasDimens.BlockGap),
      ) {
        if (state.areas.size > 1) {
          item(key = "area-title") { NasSectionTitle(stringResource(R.string.nas_space_section_area)) }
          item(key = "area-picker") { AreaPicker(state, onSelectArea) }
        }
        when (val space = state.space) {
          NasSpaceState.Loading -> item(key = "loading") { NasLoadingBlock(stringResource(R.string.nas_space_loading)) }
          is NasSpaceState.Ready -> readyItems(space.details, state.areas, expanded, { expanded = !expanded }, onOpenTrash)
          is NasSpaceState.Unavailable -> {
            item(key = "unavailable") {
              NasNotice(
                tone = NasTone.Attention,
                glyph = Icons.Rounded.CloudOff,
                title = stringResource(R.string.nas_space_unavailable_title),
                detail = opErrorText(space.code),
                actionLabel = stringResource(R.string.nas_retry),
                onAction = onReload,
              )
            }
            largestItems(space.largest, expanded) { expanded = !expanded }
          }
        }
      }
    }
  }
}

@Composable
private fun spaceCommands(onReload: () -> Unit): @Composable RowScope.() -> Unit = {
  GalleryChromeIconButton(icon = Icons.Rounded.Refresh, contentDescription = stringResource(R.string.nas_space_refresh), onClick = onReload)
}

// --- Le aree ---------------------------------------------------------------------------------------------------------------

/** La scelta dell'area: due righe da 48 dp o piu', con il cerchio della scelta (come "Solo dal telefono / Ovunque"). */
@Composable
private fun AreaPicker(state: SpaceUi, onSelect: (String) -> Unit) {
  FluidListGroup {
    state.areas.forEachIndexed { index, area ->
      if (index > 0) FluidListDivider()
      val selected = area.share.equals(state.share, ignoreCase = true)
      val title = areaLabel(area.share, state.areas)
      val hint = stringResource(if (area.kind == NasAreaKind.PRIVATE) R.string.nas_area_private_hint else R.string.nas_area_common_hint)
      FluidListRow(
        title = title,
        subtitle = hint,
        tone = if (area.kind == NasAreaKind.PRIVATE) FluidTone.Primary else FluidTone.Info,
        leading = { Icon(imageVector = if (area.kind == NasAreaKind.PRIVATE) Icons.Rounded.Person else Icons.Rounded.Group, contentDescription = null) },
        badge = { NasSelectDot(selected = selected) },
        disclosure = false,
        onClick = { onSelect(area.share) },
        modifier = Modifier.semantics(mergeDescendants = true) {
          // Una scelta fra due: TalkBack dice il nome, la frase, "pulsante di scelta" e se e' quella di adesso.
          contentDescription = "$title, $hint"
          role = Role.RadioButton
          this.selected = selected
        },
      )
    }
  }
}

// --- Lo spazio -------------------------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.readyItems(
  details: NasSpaceDetails,
  areas: List<NasArea>,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  onOpenTrash: (() -> Unit)?,
) {
  item(key = "hero") { FreeSpaceCard(details) }
  details.quotaBytes?.let { quota ->
    item(key = "quota") {
      NasNotice(
        tone = NasTone.Attention,
        glyph = Icons.Rounded.Warning,
        title = stringResource(R.string.nas_space_quota_title),
        detail = stringResource(R.string.nas_space_quota_detail, NasText.size(quota)),
      )
    }
  }
  if (details.wdTrashMayHoldSpace) {
    item(key = "wd") {
      NasNotice(
        tone = NasTone.Waiting,
        glyph = Icons.Rounded.Delete,
        title = stringResource(R.string.nas_space_wd_title),
        detail = stringResource(R.string.nas_space_wd_detail),
      )
    }
  }
  largestItems(details.largest, expanded, onToggleExpanded)
  item(key = "uses-title") { NasSectionTitle(stringResource(R.string.nas_space_section_uses)) }
  item(key = "uses") { UsesGroup(details, onOpenTrash) }
  details.measuredAtNas?.let { at ->
    item(key = "measured") {
      Text(
        text = stringResource(R.string.nas_space_measured_at, NasText.date(at), NasText.time(at)),
        modifier = Modifier.padding(horizontal = NasDimens.BlockGap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Il numero grande: lo spazio libero, quanto e' il totale e una barra di quanto e' usato. Tutto in una scheda piena. */
@Composable
private fun FreeSpaceCard(details: NasSpaceDetails) {
  val total = details.totalBytes
  val used = (total - details.freeBytes).coerceAtLeast(0L)
  val fraction = if (total > 0) (used.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
  val percent = (fraction * PERCENT).toInt()
  val spoken = stringResource(R.string.nas_space_free_description, NasText.size(details.freeBytes), NasText.size(total)) + ". " +
    stringResource(R.string.nas_space_used_line, NasText.size(used), NasText.count(percent))
  FluidListGroup {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(NasDimens.CardPadding)
        .semantics(mergeDescendants = true) { contentDescription = spoken },
      verticalArrangement = Arrangement.spacedBy(NasDimens.CardGap),
    ) {
      Text(text = NasText.size(details.freeBytes), style = FluidTextStyles.largeNumeric, color = MaterialTheme.colorScheme.onSurface)
      Text(
        text = stringResource(R.string.nas_space_free_caption, NasText.size(total)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      NasProgressBar(progress = fraction)
      Text(
        text = stringResource(R.string.nas_space_used_line, NasText.size(used), NasText.count(percent)),
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.largestItems(largest: List<LargestFile>, expanded: Boolean, onToggle: () -> Unit) {
  item(key = "largest-title") { NasSectionTitle(stringResource(R.string.nas_space_section_largest)) }
  if (largest.isEmpty()) {
    item(key = "largest-empty") {
      Text(
        text = stringResource(R.string.nas_space_largest_empty),
        modifier = Modifier.padding(horizontal = NasDimens.BlockGap),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }
  val shown = if (expanded) largest else largest.take(COLLAPSED_ROWS)
  item(key = "largest") {
    FluidListGroup {
      shown.forEachIndexed { index, file ->
        if (index > 0) FluidListDivider()
        LargestRow(file)
      }
      if (largest.size > COLLAPSED_ROWS) {
        FluidListDivider()
        FluidListRow(
          title = if (expanded) stringResource(R.string.nas_space_largest_less) else stringResource(R.string.nas_space_largest_more, NasText.count(largest.size)),
          subtitle = "",
          disclosure = false,
          onClick = onToggle,
        )
      }
    }
  }
  item(key = "largest-note") {
    Text(
      text = stringResource(R.string.nas_space_largest_footnote),
      modifier = Modifier.padding(horizontal = NasDimens.BlockGap),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun LargestRow(file: LargestFile) {
  val name = file.path.name
  val parent = file.path.parent()
  val kind = NasText.kindOf(name)
  FluidListRow(
    title = name,
    subtitle = parent?.let { stringResource(R.string.nas_space_in_folder, NasText.path(it.components)) } ?: stringResource(R.string.nas_space_in_root),
    tone = when (kind) {
      FileKind.IMAGE -> FluidTone.Info
      FileKind.VIDEO -> FluidTone.Success
      FileKind.OTHER -> FluidTone.Neutral
    },
    leading = {
      Icon(
        imageVector = when (kind) {
          FileKind.IMAGE -> Icons.Rounded.Image
          FileKind.VIDEO -> Icons.Rounded.Movie
          FileKind.OTHER -> Icons.Rounded.InsertDriveFile
        },
        contentDescription = null,
      )
    },
    badge = { FluidRowValue(text = NasText.size(file.size)) },
    disclosure = false,
    titleMaxLines = 2,
  )
}

@Composable
private fun UsesGroup(details: NasSpaceDetails, onOpenTrash: (() -> Unit)?) {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.nas_space_trash_title),
      subtitle = stringResource(R.string.nas_space_trash_detail),
      tone = FluidTone.Danger,
      leading = { Icon(imageVector = Icons.Rounded.Delete, contentDescription = null) },
      badge = { FluidRowValue(text = NasText.size(details.trashBytes)) },
      disclosure = onOpenTrash != null,
      onClick = onOpenTrash,
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.nas_space_index_title),
      subtitle = stringResource(R.string.nas_space_index_detail),
      tone = FluidTone.Info,
      leading = { Icon(imageVector = Icons.Rounded.Cloud, contentDescription = null) },
      badge = { FluidRowValue(text = NasText.size(details.indexBytes)) },
      disclosure = false,
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.nas_space_versions_title),
      subtitle = stringResource(R.string.nas_space_versions_detail),
      tone = FluidTone.Neutral,
      leading = { Icon(imageVector = Icons.Rounded.History, contentDescription = null) },
      // Fino alla Fase 5 le versioni non esistono: "—" e non "0 B", che direbbe che non occupano niente.
      badge = { FluidRowValue(text = if (details.versionsBytes > 0) NasText.size(details.versionsBytes) else stringResource(R.string.nas_space_versions_value)) },
      disclosure = false,
    )
  }
}

private const val COLLAPSED_ROWS = 10
private const val PERCENT = 100
