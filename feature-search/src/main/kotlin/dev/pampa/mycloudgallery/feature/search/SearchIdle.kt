package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.KeepInstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.designsystem.instant.onFirstDraw
import dev.pampa.mycloudgallery.core.designsystem.instant.rememberInstantShape
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// A RIPOSO e MENTRE SI SCRIVE (Fase 6, P8, §2.6): ricerche recenti (fino a 10, "Cancella" e "Cancella tutte"), luoghi piu' frequenti (fino a 8, mai da
// nascosti), tipi; poi i completamenti di luoghi, mesi, feste, tipi e ricerche recenti. **Mai** parole del testo letto (D29). Righe e pillole di Fluid.

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchIdleContent(
  state: SearchScreenState.Idle,
  readiness: SearchReadiness,
  queryModel: ModelState?,
  handlers: SearchHandlers,
  modifier: Modifier = Modifier,
) {
  val notices = SearchNoticeItems.of(emptyList(), SearchCoverage.Complete, readiness, queryModel)
  val typeChips = state.suggestions.types.map { it to typeLabel(it) }
  val empty = state.recents.isEmpty() && state.suggestions.people.isEmpty() && state.suggestions.places.isEmpty() && typeChips.isEmpty()
  // Avvio istantaneo: al primo ingresso, finche' i suggerimenti non sono letti, lo scheletro con la forma dell'ultima volta (mai una rotella).
  val skeleton = rememberInstantShape(SearchInstant.CODE, SearchInstant.DEFAULT)
  if (state.known) KeepInstantShape(SearchInstant.CODE, SearchInstant.shapeOf(state, typeChips.isNotEmpty()))
  LazyColumn(
    modifier = modifier.onFirstDraw { AvvioTrace.pageDrawn(AvvioTrace.PAGE_SEARCH) },
    contentPadding = PaddingValues(bottom = SearchDimens.ListBottom),
    verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap),
  ) {
    if (notices.isNotEmpty()) item(key = "notices") { SearchNotices(notices, handlers) }
    if (!state.known) {
      instantSkeleton(skeleton, keyPrefix = "instant-search")
      return@LazyColumn
    }
    if (state.recents.isNotEmpty()) {
      item(key = "recents-title") {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(stringResource(R.string.search_recents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
          FluidButton(
            text = stringResource(R.string.search_recents_clear_all),
            onClick = handlers::onClearRecents,
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
          )
        }
      }
      item(key = "recents") {
        FluidListGroup(modifier = Modifier.padding(horizontal = GalleryDimens.Gutter)) {
          state.recents.forEachIndexed { index, entry ->
            if (index > 0) FluidListDivider()
            RecentRow(entry, handlers)
          }
        }
      }
    }
    // Fase 8 (§2.7): la riga "Persone", fino a 6 nomi, le persone piu' fotografate. Una pillola da 48 dp per nome, come i luoghi.
    if (state.suggestions.people.isNotEmpty()) {
      item(key = "people") {
        ChipsSection(stringResource(R.string.search_people)) {
          state.suggestions.people.forEach { person ->
            val open = stringResource(R.string.search_person_open, person.label)
            FluidChip(
              label = person.label,
              selected = false,
              onClick = { handlers.onPerson(person) },
              modifier = Modifier.semantics { contentDescription = open },
              leading = { Icon(Icons.Rounded.Face, null) },
            )
          }
        }
      }
    }
    if (state.suggestions.places.isNotEmpty()) {
      item(key = "places") {
        ChipsSection(stringResource(R.string.search_places)) {
          state.suggestions.places.forEach { place ->
            FluidChip(label = place.label, selected = false, onClick = { handlers.onPlace(place) }, leading = { Icon(Icons.Rounded.Place, null) })
          }
        }
      }
    }
    if (typeChips.isNotEmpty()) {
      item(key = "types") {
        val favorites = stringResource(R.string.search_type_favorites)
        ChipsSection(stringResource(R.string.search_types)) {
          typeChips.forEach { (type, label) -> FluidChip(label = label, selected = false, onClick = { handlers.onType(type, label) }) }
          FluidChip(label = favorites, selected = false, onClick = { handlers.onCompletion(Suggestion.Term(favorites)) })
        }
      }
    }
    if (empty && notices.isEmpty()) {
      item(key = "hint") {
        Text(
          text = stringResource(R.string.search_idle_hint),
          modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun RecentRow(entry: HistoryEntry, handlers: SearchHandlers) {
  val open = stringResource(R.string.search_recent_open, entry.text)
  val delete = stringResource(R.string.search_recent_delete, entry.text)
  FluidListRow(
    title = entry.text,
    subtitle = entry.chipLabels.joinToString(" · "),
    tone = FluidTone.Neutral,
    leading = { Icon(Icons.Rounded.History, null) },
    disclosure = false,
    titleMaxLines = 2,
    onClick = { handlers.onRecent(entry) },
    modifier = Modifier.semantics { contentDescription = open },
    badge = {
      IconButton(onClick = { handlers.onDeleteRecent(entry) }, modifier = Modifier.size(GalleryDimens.MinTouchTarget)) { Icon(Icons.Rounded.Close, contentDescription = delete) }
    },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsSection(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap)) {
    Text(
      text = title,
      modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    FlowRow(
      modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
      horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
    ) { content() }
  }
}

/** Mentre si scrive: la riga "Cerca «testo»" e i completamenti. Mai parole del testo letto. */
@Composable
internal fun SearchTypingContent(state: SearchScreenState.Typing, handlers: SearchHandlers, modifier: Modifier = Modifier) {
  LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = SearchDimens.ListBottom), verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap)) {
    item(key = "typing") {
      FluidListGroup(modifier = Modifier.padding(horizontal = GalleryDimens.Gutter)) {
        FluidListRow(
          title = state.text.trim(),
          subtitle = "",
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Search, null) },
          disclosure = false,
          titleMaxLines = 2,
          onClick = handlers::onSubmit,
        )
        state.completions.forEach { suggestion ->
          FluidListDivider()
          FluidListRow(
            title = suggestion.label,
            subtitle = "",
            tone = FluidTone.Neutral,
            leading = { Icon(glyphOf(suggestion), null) },
            disclosure = false,
            titleMaxLines = 2,
            onClick = { handlers.onCompletion(suggestion) },
          )
        }
      }
    }
  }
}

private fun glyphOf(suggestion: Suggestion) = when (suggestion) {
  is Suggestion.Recent -> Icons.Rounded.History
  is Suggestion.PlaceSuggestion -> Icons.Rounded.Place
  is Suggestion.Type -> Icons.Rounded.PhotoLibrary
  is Suggestion.Term -> Icons.Rounded.CalendarMonth
  is Suggestion.PersonSuggestion -> Icons.Rounded.Face
}

@Composable
private fun typeLabel(type: SearchMediaType): String = stringResource(
  when (type) {
    SearchMediaType.PHOTO -> R.string.search_type_photo
    SearchMediaType.VIDEO -> R.string.search_type_video
    SearchMediaType.SCREENSHOT -> R.string.search_type_screenshot
    SearchMediaType.DOCUMENT -> R.string.search_type_document
    SearchMediaType.PANORAMA -> R.string.search_type_panorama
    SearchMediaType.MOTION_PHOTO -> R.string.search_type_motion
  },
)

/** La forma di Cerca a riposo per lo scheletro (Avvio istantaneo): le recenti come righe, ogni gruppo di pillole come una riga. Solo numeri. */
internal object SearchInstant {
  const val CODE: String = "cerca"

  val DEFAULT: InstantShape = InstantShape.of(InstantBlock.Header, InstantBlock.Rows(3), InstantBlock.Header, InstantBlock.Rows(1))

  fun shapeOf(state: SearchScreenState.Idle, types: Boolean): InstantShape {
    val blocks = ArrayList<InstantBlock>()
    if (state.recents.isNotEmpty()) {
      blocks += InstantBlock.Header
      blocks += InstantBlock.Rows(state.recents.size)
    }
    val chipGroups = listOf(state.suggestions.people.isNotEmpty(), state.suggestions.places.isNotEmpty(), types).count { it }
    repeat(chipGroups) {
      blocks += InstantBlock.Header
      blocks += InstantBlock.Rows(1)
    }
    return InstantShape.of(*blocks.toTypedArray())
  }
}
