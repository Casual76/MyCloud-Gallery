package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchFlag
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// La BARRA DEI CHIP (Fase 6, P8, §1.5, §2.6, E-Q2): l'interpretazione scelta dall'analizzatore, sempre visibile e modificabile. Ogni chip e' un
// bersaglio da 48 dp e TalkBack lo legge intero ("Filtro data: ieri, 20 ottobre. Tocca per modificare"). Il tocco apre un foglio: le alternative
// di un luogo, "Cerca come parola" (E-Q10) e "Togli il filtro". La riga scorre di lato, anche a testo 1,3x.

/** La frase di TalkBack di un chip: cosa filtra e che si tocca per modificarlo. Funzione pura sulle risorse. */
internal fun chipDescription(resources: android.content.res.Resources, chip: SearchChip): String = when (chip) {
  is SearchChip.DateRange, is SearchChip.DateSet -> resources.getString(R.string.search_chip_date, chip.label)
  is SearchChip.Place -> resources.getString(R.string.search_chip_place, chip.label)
  is SearchChip.MediaType -> resources.getString(R.string.search_chip_type, chip.label)
  is SearchChip.Source -> resources.getString(R.string.search_chip_source, chip.label)
  is SearchChip.Presence -> resources.getString(R.string.search_chip_presence, chip.label)
  is SearchChip.Flag -> resources.getString(R.string.search_chip_flag, chip.label)
  is SearchChip.Word -> resources.getString(R.string.search_chip_word, chip.label)
  is SearchChip.Similar -> resources.getString(R.string.search_chip_similar)
  // Fase 8 (P0): il chip "Persona" (D26); l'alternativa e i suggerimenti sono di P10.
  is SearchChip.Person -> resources.getString(R.string.search_chip_person, chip.label)
}

/** La lettura di TalkBack di un chip, come modificatore. */
internal fun Modifier.chipSemantics(resources: android.content.res.Resources, chip: SearchChip): Modifier =
  semantics { contentDescription = chipDescription(resources, chip) }

private fun glyphOf(chip: SearchChip): ImageVector = when (chip) {
  is SearchChip.DateRange, is SearchChip.DateSet -> Icons.Rounded.CalendarMonth
  is SearchChip.Place -> Icons.Rounded.Place
  is SearchChip.MediaType -> Icons.Rounded.PhotoLibrary
  is SearchChip.Source -> Icons.Rounded.Folder
  is SearchChip.Presence -> Icons.Rounded.CloudQueue
  is SearchChip.Flag -> if (chip.flag == SearchFlag.FAVORITE) Icons.Rounded.Favorite else Icons.Rounded.Tune
  is SearchChip.Word -> Icons.Rounded.Search
  is SearchChip.Similar -> Icons.Rounded.ImageSearch
  is SearchChip.Person -> Icons.Rounded.Face
}

@Composable
internal fun SearchChipBar(
  chips: List<SearchChip>,
  snapshot: TimelineSnapshot?,
  thumbnails: SearchThumbnails,
  handlers: SearchHandlers,
  modifier: Modifier = Modifier,
) {
  if (chips.isEmpty()) return
  var editing by remember { mutableStateOf<SearchChip?>(null) }
  val resources = LocalContext.current.resources
  val barLabel = stringResource(R.string.search_chips_bar)
  LazyRow(
    modifier = modifier.semantics { contentDescription = barLabel },
    contentPadding = PaddingValues(horizontal = GalleryDimens.Gutter),
    horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) {
    items(chips, key = { chipKey(it) }) { chip ->
      FluidChip(
        label = if (chip is SearchChip.Similar) stringResource(R.string.search_chip_similar_label) else chip.label,
        selected = true,
        onClick = { editing = chip },
        modifier = Modifier.semantics { contentDescription = chipDescription(resources, chip) },
        leading = { ChipGlyph(chip, snapshot, thumbnails) },
      )
    }
  }
  ChipSheet(chip = editing, handlers = handlers, onDismiss = { editing = null })
}

private fun chipKey(chip: SearchChip): String = "${chip::class.simpleName}-${chip.label}"

/** Le richieste di miniatura dei chip "Simili a" (D28): il media del chip non e' fra i risultati, quindi la griglia non la chiederebbe (TS-5). */
internal fun similarChipRequests(chips: List<SearchChip>, snapshot: TimelineSnapshot, thumbnails: SearchThumbnails): List<ThumbRequest> =
  chips.filterIsInstance<SearchChip.Similar>().mapNotNull { chip ->
    val index = snapshot.indexOf(chip.media)
    if (index >= 0) thumbRequestOf(thumbnails, snapshot, index) else null
  }

/** Il glifo del chip; per "Simili a" la miniatura del media (solo cio' che e' sul dispositivo, D28), se c'e'. */
@Composable
private fun ChipGlyph(chip: SearchChip, snapshot: TimelineSnapshot?, thumbnails: SearchThumbnails) {
  if (chip is SearchChip.Similar && snapshot != null) {
    val index = snapshot.indexOf(chip.media)
    val request = if (index >= 0) thumbRequestOf(thumbnails, snapshot, index) else null
    val bitmap = request?.let { thumbnails.loader.image(it.key, it.ref).value }
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(SearchDimens.SimilarThumb).clip(ContinuousCornerShape(FluidRadius.Small)),
      )
      return
    }
  }
  Icon(imageVector = glyphOf(chip), contentDescription = null)
}

/** Il foglio di un chip: sceglie un'alternativa, lo trasforma in parola o lo toglie. Chiuderlo non cambia niente. */
@Composable
private fun ChipSheet(chip: SearchChip?, handlers: SearchHandlers, onDismiss: () -> Unit) {
  // L'ultimo chip aperto resta in memoria per la durata dell'uscita del foglio: niente testo che sparisce a meta' animazione.
  var shown by remember { mutableStateOf<SearchChip?>(null) }
  if (chip != null) shown = chip
  val current = shown
  GalleryConfirmSheet(visible = chip != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.search_chip_sheet_title)) {
    if (current != null) ChipSheetContent(current, handlers, onDismiss)
  }
}

/**
 * Il contenuto del foglio di un chip (separato dalla finestra, cosi' gli screenshot lo mostrano dentro la pagina). Un chip "Persona" propone le altre letture
 * della sua parola (la data, il luogo: una riga ciascuna, che dice cosa cerca) e "Cerca come parola"; una data o un luogo con una persona dello stesso nome
 * propone "Cerca la persona" (Fase 8, E-F36).
 */
@Composable
internal fun ChipSheetContent(current: SearchChip, handlers: SearchHandlers, onDismiss: () -> Unit) {
  val title = stringResource(R.string.search_chip_sheet_title)
  GallerySheetScaffold(
    icon = glyphOf(current),
    tone = GallerySheetTone.Brand,
    title = title,
    message = stringResource(R.string.search_chip_sheet_message, if (current is SearchChip.Similar) stringResource(R.string.search_chip_similar_label) else current.label),
    body = {
      if (current is SearchChip.Place && current.alternatives.isNotEmpty()) {
        FluidListGroup {
          current.alternatives.forEach { option ->
            FluidListRow(
              title = option.label,
              subtitle = stringResource(R.string.search_chip_alternatives, current.word),
              tone = FluidTone.Primary,
              onClick = { handlers.onChooseAlternative(current, option); onDismiss() },
              disclosure = false,
              titleMaxLines = 3,
            )
          }
        }
      }
      if (current is SearchChip.Person) {
        val readings = current.alternatives.filter { it.chip !is SearchChip.Word }
        if (readings.isNotEmpty()) {
          FluidListGroup {
            readings.forEach { reading ->
              FluidListRow(
                title = reading.label,
                subtitle = stringResource(if (reading.chip is SearchChip.Place) R.string.search_chip_alt_place else R.string.search_chip_alt_date),
                tone = FluidTone.Primary,
                onClick = { handlers.onSwitchReading(current, ChipReadings.switchTo(current, reading)); onDismiss() },
                disclosure = false,
                titleMaxLines = 3,
              )
            }
          }
        }
      }
      ChipReadings.personOf(current)?.let { person ->
        FluidListGroup {
          FluidListRow(
            title = person.label,
            subtitle = stringResource(R.string.search_chip_alt_person),
            tone = FluidTone.Primary,
            onClick = { ChipReadings.backToPerson(current)?.let { back -> handlers.onSwitchReading(current, back) }; onDismiss() },
            disclosure = false,
            titleMaxLines = 3,
          )
        }
      }
    },
    actions = {
      if (current is SearchChip.Place) {
        FluidButton(
          text = stringResource(R.string.search_chip_as_word),
          onClick = { handlers.onPlaceAsWord(current); onDismiss() },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      if (current is SearchChip.Person) {
        current.alternatives.firstOrNull { it.chip is SearchChip.Word }?.let { word ->
          FluidButton(
            text = stringResource(R.string.search_chip_as_word),
            onClick = { handlers.onSwitchReading(current, word.chip); onDismiss() },
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
        }
      }
      FluidButton(
        text = stringResource(R.string.search_chip_remove),
        onClick = { handlers.onRemoveChip(current); onDismiss() },
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.search_sheet_close),
        onClick = onDismiss,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}
