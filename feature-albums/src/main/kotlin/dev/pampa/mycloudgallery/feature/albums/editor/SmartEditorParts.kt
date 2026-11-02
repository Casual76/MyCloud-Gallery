package dev.pampa.mycloudgallery.feature.albums.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.page.CancelButton
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumCoverThumb
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote
import kotlin.math.roundToInt

// LE SEZIONI DELL'EDITOR DELLE REGOLE (Fase 7, P10): ognuna e' un gruppo lista con la sua intestazione, e dice cosa vale adesso con le parole e non con
// un controllo muto. Nessun controllo e' sopra un altro: le foto d'esempio sono miniature da toccare per toglierle, non miniature con una "x" sopra.

@Composable
internal fun PeriodSection(period: SmartPeriod?, series: List<LocalDayRange>, handlers: SmartEditorHandlers, onEditDates: () -> Unit) {
  val resources = LocalContext.current.resources
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_period))
    FluidListGroup {
      when (period) {
        null -> FluidListRow(
          title = stringResource(R.string.editor_period_none), subtitle = stringResource(R.string.editor_period_none_detail), disclosure = false,
          leading = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        )
        is SmartPeriod.Ranges -> {
          // Piu' serie (E-AL12): ognuna si tiene o si lascia; se ce n'e' una sola, e' il periodo.
          if (series.size > 1) {
            Text(stringResource(R.string.editor_period_series_header), modifier = Modifier.padding(AlbumDimens.Inset), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            series.forEachIndexed { index, range ->
              if (index > 0) FluidListDivider()
              val kept = range in period.ranges
              FluidListRow(
                title = rangeText(range), subtitle = resources.getQuantityString(R.plurals.editor_period_days, range.days.toInt(), range.days.toInt()), disclosure = false,
                badge = { FluidSwitch(checked = kept, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                  value = kept, role = Role.Switch,
                  onValueChange = { on -> handlers.onSeries(if (on) period.ranges.toSet() + range else period.ranges.toSet() - range) },
                ),
              )
            }
            FluidListDivider()
            SectionNote(stringResource(R.string.editor_period_series_note))
          } else {
            val only = period.ranges.first()
            FluidListRow(
              title = rangeText(only), subtitle = resources.getQuantityString(R.plurals.editor_period_days, only.days.toInt(), only.days.toInt()), disclosure = false,
              leading = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
            )
          }
        }
        is SmartPeriod.EveryYear -> FluidListRow(
          title = resources.getString(R.string.editor_period_every_year, "${period.from.dayOfMonth}/${period.from.monthValue}", "${period.to.dayOfMonth}/${period.to.monthValue}"),
          subtitle = "", disclosure = false, leading = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        )
        is SmartPeriod.Days -> FluidListRow(
          title = stringResource(R.string.editor_period_days_set), subtitle = resources.getQuantityString(R.plurals.editor_period_days, period.days.size, period.days.size), disclosure = false,
          leading = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        )
      }
      FluidListDivider()
      FluidListRow(title = stringResource(R.string.editor_period_edit), subtitle = "", tone = FluidTone.Primary, onClick = onEditDates, leading = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) })
      if (period != null) {
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.editor_period_remove), subtitle = "", tone = FluidTone.Danger, disclosure = false, onClick = handlers::onRemovePeriod, leading = { Icon(Icons.Rounded.Close, contentDescription = null) })
      }
    }
  }
}

@Composable
private fun rangeText(range: LocalDayRange): String =
  if (range.from == range.to) stringResource(R.string.editor_period_day, AlbumText.day(range.from)) else stringResource(R.string.editor_period_range, AlbumText.shortDay(range.from), AlbumText.shortDay(range.to))

@Composable
internal fun PlaceSection(place: PlaceRule?, alternatives: List<PlaceAlternative>, handlers: SmartEditorHandlers) {
  val resources = LocalContext.current.resources
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_place))
    FluidListGroup {
      if (place == null) {
        FluidListRow(
          title = stringResource(R.string.editor_place_none), subtitle = stringResource(R.string.editor_place_none_detail), disclosure = false,
          leading = { Icon(Icons.Rounded.Place, contentDescription = null) },
        )
      } else {
        val text = when (place.scope) {
          PlaceScope.AROUND -> resources.getString(R.string.editor_place_around, place.label, place.radiusKm)
          PlaceScope.REGION -> resources.getString(R.string.editor_place_region, place.label)
          PlaceScope.COUNTRY -> resources.getString(R.string.editor_place_country, place.label)
        }
        FluidListRow(title = text, subtitle = "", disclosure = false, leading = { Icon(Icons.Rounded.Place, contentDescription = null) })
        if (place.scope == PlaceScope.AROUND) {
          FluidListDivider()
          Column(modifier = Modifier.padding(horizontal = AlbumDimens.Inset, vertical = AlbumDimens.TightGap)) {
            Text(stringResource(R.string.editor_place_radius), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            FluidSlider(
              value = place.radiusKm.toFloat(),
              onValueChange = { handlers.onRadius(it.roundToInt()) },
              valueRange = SmartLimits.MIN_RADIUS_KM.toFloat()..SmartLimits.MAX_RADIUS_KM.toFloat(),
              contentDescription = stringResource(R.string.editor_place_radius),
              stateDescription = resources.getString(R.string.editor_place_radius_value, place.radiusKm),
            )
          }
        }
        if (alternatives.size > 1) {
          FluidListDivider()
          Text(stringResource(R.string.editor_place_alternatives), modifier = Modifier.padding(AlbumDimens.Inset), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
          alternatives.forEachIndexed { index, alternative ->
            if (index > 0) FluidListDivider()
            OptionRow(
              title = alternative.rule.label,
              detail = resources.getQuantityString(R.plurals.editor_place_alt_count, alternative.mediaCount, alternative.mediaCount),
              selected = alternative.rule == place,
              onSelect = { handlers.onPlace(alternative) },
            )
          }
        }
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.editor_place_remove), subtitle = "", tone = FluidTone.Danger, disclosure = false, onClick = handlers::onRemovePlace, leading = { Icon(Icons.Rounded.Close, contentDescription = null) })
      }
    }
  }
}

@Composable
internal fun TextSection(text: String, noSearch: Boolean, handlers: SmartEditorHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_text))
    FluidTextField(value = text, onValueChange = handlers::onText, placeholder = stringResource(R.string.editor_text_hint), modifier = Modifier.fillMaxWidth())
    // E-AL9, D15: senza il motore di ricerca la descrizione non agisce. Lo dice qui, dove si scrive, e le altre regole lavorano lo stesso.
    if (noSearch) SectionNote(stringResource(R.string.editor_text_no_search))
  }
}

@Composable
internal fun ExamplesSection(examples: List<MediaId>, thumbnails: AlbumThumbnails, handlers: SmartEditorHandlers) {
  val resources = LocalContext.current.resources
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_examples))
    FluidListGroup {
      if (examples.isNotEmpty()) {
        LazyRow(
          modifier = Modifier.fillMaxWidth().padding(AlbumDimens.Inset),
          horizontalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap),
        ) {
          items(examples.size, key = { examples[it].value }) { index ->
            val media = examples[index]
            AlbumCoverThumb(
              media = media, thumbnails = thumbnails,
              modifier = Modifier
                .size(AlbumDimens.ExampleThumb)
                .semantics { contentDescription = resources.getString(R.string.editor_example_photo, index + 1, examples.size) }
                .clickable(onClickLabel = resources.getString(R.string.editor_example_remove), role = Role.Button) { handlers.onRemoveExample(media) },
              shape = ContinuousCornerShape(FluidRadius.Control),
            )
          }
        }
        FluidListDivider()
        SectionNote(resources.getQuantityString(R.plurals.editor_examples_detail, examples.size, examples.size))
      } else {
        SectionNote(stringResource(R.string.editor_examples_none))
      }
      if (examples.size < SmartLimits.MAX_EXAMPLES) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.editor_examples_add), subtitle = "", tone = FluidTone.Primary, onClick = handlers::onAddExamples,
          leading = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null) },
        )
      }
    }
  }
}

@Composable
internal fun SourcesSection(sources: Set<SmartSource>, handlers: SmartEditorHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_sources))
    FluidListGroup {
      val rows = listOf(
        SmartSource.MY_PHONE to R.string.editor_source_phone, SmartSource.MY_PRIVATE to R.string.editor_source_private,
        SmartSource.COMMON to R.string.editor_source_common, SmartSource.FAMILY to R.string.editor_source_family,
      )
      rows.forEachIndexed { index, (source, label) ->
        if (index > 0) FluidListDivider()
        SwitchRow(stringResource(label), source in sources) { handlers.onSource(source) }
      }
    }
  }
}

@Composable
internal fun TypesSection(types: Set<SmartMediaType>, handlers: SmartEditorHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_types))
    FluidListGroup {
      SwitchRow(stringResource(R.string.editor_type_photo), SmartMediaType.PHOTO in types) { handlers.onType(SmartMediaType.PHOTO) }
      FluidListDivider()
      SwitchRow(stringResource(R.string.editor_type_video), SmartMediaType.VIDEO in types) { handlers.onType(SmartMediaType.VIDEO) }
      FluidListDivider()
      SectionNote(stringResource(R.string.editor_screenshots_note))
    }
  }
}

/** Una riga con un interruttore: tutta la riga (48 dp) e' un interruttore per il lettore di schermo, il pomello e' solo il segno. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
  FluidListRow(
    title = title, subtitle = "", disclosure = false,
    badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
    modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
  )
}

@Composable
internal fun PreviewSection(ui: SmartEditorUi, thumbnails: AlbumThumbnails) {
  val preview = ui.preview
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    GallerySectionHeader(title = stringResource(R.string.editor_preview))
    FluidListGroup {
      if (preview == null || ui.previewing) {
        SectionNote(stringResource(R.string.editor_preview_working))
      } else if (!preview.peopleAvailable) {
        SectionNote(stringResource(R.string.editor_preview_people_off))
      } else if (preview.sample.isEmpty()) {
        SectionNote(stringResource(R.string.editor_preview_none))
      } else {
        SectionNote(stringResource(R.string.editor_preview_sample))
        LazyRow(modifier = Modifier.fillMaxWidth().padding(AlbumDimens.Inset), horizontalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
          items(preview.sample.size, key = { "sample-$it" }) { index ->
            AlbumCoverThumb(preview.sample[index], thumbnails, Modifier.size(AlbumDimens.SampleThumb), ContinuousCornerShape(FluidRadius.Small))
          }
        }
      }
    }
  }
}

/** "Le regole sono troppo larghe" (E-AL6): lo dice una scheda in evidenza e propone come restringere, con le parole e senza bottoni finti. */
@Composable
internal fun BroadCard(state: SmartEditorState.TooBroad) {
  FluidCard(highlighted = true) {
    Text(stringResource(R.string.editor_broad_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(stringResource(R.string.editor_broad_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (hint in state.hints) {
      Text(
        text = "· " + stringResource(
          when (hint) {
            BroadHint.NARROW_PERIOD -> R.string.editor_broad_hint_period
            BroadHint.SMALLER_RADIUS -> R.string.editor_broad_hint_radius
            BroadHint.ADD_PLACE -> R.string.editor_broad_hint_place
          },
        ),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

/** Il foglio delle date scritte a mano: due campi "gg/mm/aaaa" e un bottone che si accende solo con due giorni veri. */
@Composable
internal fun DatesSheet(visible: Boolean, current: LocalDayRange?, onApply: (LocalDayRange) -> Unit, onDismiss: () -> Unit) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.editor_period_edit)) {
    var from by rememberSaveable(current) { mutableStateOf(current?.from?.let(DateInput::show).orEmpty()) }
    var to by rememberSaveable(current) { mutableStateOf(current?.to?.let(DateInput::show).orEmpty()) }
    val range = DateInput.range(from, to)
    GallerySheetScaffold(
      icon = Icons.Rounded.CalendarMonth,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.editor_period_edit),
      message = stringResource(R.string.editor_period_format),
      body = {
        FluidTextField(value = from, onValueChange = { from = it }, label = stringResource(R.string.editor_period_from), placeholder = stringResource(R.string.editor_period_format), modifier = Modifier.fillMaxWidth())
        FluidTextField(
          value = to, onValueChange = { to = it }, label = stringResource(R.string.editor_period_to), placeholder = stringResource(R.string.editor_period_format),
          isError = from.isNotBlank() && to.isNotBlank() && range == null, supportingText = if (from.isNotBlank() && to.isNotBlank() && range == null) stringResource(R.string.editor_period_invalid) else null,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      actions = {
        FluidButton(text = stringResource(R.string.editor_period_apply), onClick = { range?.let(onApply) }, enabled = range != null, size = FluidButtonSize.Large, fillWidth = true)
        CancelButton(onDismiss, label = stringResource(R.string.editor_period_cancel))
      },
    )
  }
}
