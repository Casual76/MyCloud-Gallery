package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.feature.library.R
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Le parole della griglia: titoli delle intestazioni, durate, descrizioni per il lettore di schermo. Tutto in italiano
// (Locale.ITALIAN, come i luoghi), con i formattatori creati una volta sola.

/** Le stringhe della griglia, risolte una volta per composizione della griglia (non per cella). */
@Immutable
internal class GridStrings(
  val today: String,
  val yesterday: String,
  val kindPhoto: String,
  val kindVideo: String,
  val kindMotion: String,
  val kindRaw: String,
  val kindAnimated: String,
  val favoriteFeminine: String,
  val favoriteMasculine: String,
  val editedFeminine: String,
  val editedMasculine: String,
  val unreadable: String,
  val estimated: String,
  val duration: String,
  val group: String,
  val open: String,
  val select: String,
  val deselect: String,
  val scrubber: String,
)

@Composable
internal fun rememberGridStrings(): GridStrings {
  // La configurazione (lingua) e' la chiave: un cambio di lingua ricrea le stringhe.
  val configuration = LocalConfiguration.current
  val values = listOf(
    stringResource(R.string.library_today),
    stringResource(R.string.library_yesterday),
    stringResource(R.string.grid_kind_photo),
    stringResource(R.string.grid_kind_video),
    stringResource(R.string.grid_kind_motion),
    stringResource(R.string.grid_kind_raw),
    stringResource(R.string.grid_kind_animated),
    stringResource(R.string.grid_desc_favorite_f),
    stringResource(R.string.grid_desc_favorite_m),
    stringResource(R.string.grid_desc_edited_f),
    stringResource(R.string.grid_desc_edited_m),
    stringResource(R.string.grid_desc_unreadable),
    stringResource(R.string.grid_desc_estimated),
    stringResource(R.string.grid_desc_duration),
    stringResource(R.string.grid_desc_group),
    stringResource(R.string.grid_action_open),
    stringResource(R.string.grid_action_select),
    stringResource(R.string.grid_action_deselect),
    stringResource(R.string.library_scrubber),
  )
  return remember(configuration, values) {
    GridStrings(
      today = values[0], yesterday = values[1], kindPhoto = values[2], kindVideo = values[3], kindMotion = values[4],
      kindRaw = values[5], kindAnimated = values[6], favoriteFeminine = values[7], favoriteMasculine = values[8],
      editedFeminine = values[9], editedMasculine = values[10], unreadable = values[11], estimated = values[12],
      duration = values[13], group = values[14], open = values[15], select = values[16], deselect = values[17],
      scrubber = values[18],
    )
  }
}

internal object GridText {

  private val Italian: Locale = Locale.ITALIAN
  private val MonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", Italian)
  private val DayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Italian)
  private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Italian)
  private val FullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Italian)
  private val Time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Italian)

  /** `aaaammgg` -> data. */
  fun date(day: Int): LocalDate = LocalDate.of(day / 10_000, (day / 100) % 100, day % 100)

  /** "Marzo 2024". */
  fun month(month: YearMonth): String = month.format(MonthYear).capitalized()

  /**
   * Il titolo di un'intestazione (§2.4): Anni "2024"; Mesi "Marzo 2024"; Giorni "Oggi", "Ieri", "Martedì 12 marzo" (con
   * l'anno se non e' quello in corso). "Tutte" non ha intestazioni: per lei vale il mese.
   */
  fun sectionTitle(section: SectionInfo, today: LocalDate, strings: GridStrings): String = when (section.level) {
    GridLevel.YEARS -> (section.firstDay / 10_000).toString()
    GridLevel.MONTHS, GridLevel.ALL -> month(section.firstMonth)
    GridLevel.DAYS -> {
      val day = date(section.firstDay)
      when {
        day == today -> strings.today
        day == today.minusDays(1) -> strings.yesterday
        day.year == today.year -> day.format(DayMonth).capitalized()
        else -> day.format(DayMonthYear).capitalized()
      }
    }
  }

  /** Il luogo sotto il titolo: per Mesi e Giorni il riassunto dei luoghi della sezione; Anni dice solo l'anno. */
  fun sectionPlace(section: SectionInfo, names: PlaceNames): String? =
    if (section.level == GridLevel.YEARS || section.topPlaces.isEmpty()) null else names.summary(section.topPlaces, section.distinctPlaces).ifBlank { null }

  /** "0:42", "12:05", "1:03:20": le cifre come le scrive Foto, senza `String.format` (si chiama per ogni video in vista). */
  fun duration(durationMs: Long): String {
    val total = (durationMs / 1_000).coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    val builder = StringBuilder(8)
    if (hours > 0) {
      builder.append(hours).append(':')
      if (minutes < 10) builder.append('0')
    }
    builder.append(minutes).append(':')
    if (seconds < 10) builder.append('0')
    return builder.append(seconds).toString()
  }

  /**
   * La descrizione di una cella per il lettore di schermo: "Foto, 12 marzo 2024, 10:15, Oslo, preferita". Si costruisce
   * **solo quando qualcuno la chiede** (la semantica della cella la calcola su richiesta), mai durante lo scorrimento.
   *
   * L'ora e' quella del fuso del telefono: l'indice tiene il giorno nell'ora del posto (E-P5) ma non il fuso. Se le due
   * non concordano sul giorno (uno scatto in viaggio), l'ora si omette invece di contraddire la data.
   */
  fun describe(
    snapshot: TimelineSnapshot,
    index: Int,
    strings: GridStrings,
    places: PlaceNames,
    zone: ZoneId,
  ): String {
    val kind = snapshot.kindAt(index)
    val flags = snapshot.flagsAt(index)
    val feminine = kind != MediaKind.VIDEO
    val day = date(snapshot.localDayAt(index))
    val parts = ArrayList<String>(8)
    parts += when (kind) {
      MediaKind.PHOTO -> strings.kindPhoto
      MediaKind.VIDEO -> strings.kindVideo
      MediaKind.MOTION_PHOTO -> strings.kindMotion
      MediaKind.RAW_PHOTO -> strings.kindRaw
      MediaKind.ANIMATED_IMAGE -> strings.kindAnimated
    }
    parts += day.format(FullDate)
    val instant = Instant.ofEpochMilli(snapshot.sortTimeAt(index)).atZone(zone)
    if (instant.toLocalDate() == day && !CellFlags.has(flags, CellFlags.DATE_ESTIMATED)) parts += instant.format(Time)
    places.labelOfId(snapshot.placeAt(index))?.let { parts += it }
    if (kind == MediaKind.VIDEO) parts += strings.duration.format(duration(snapshot.durationMsAt(index)))
    if (CellFlags.has(flags, CellFlags.FAVORITE)) parts += if (feminine) strings.favoriteFeminine else strings.favoriteMasculine
    if (CellFlags.has(flags, CellFlags.EDITED)) parts += if (feminine) strings.editedFeminine else strings.editedMasculine
    if (CellFlags.has(flags, CellFlags.DATE_ESTIMATED)) parts += strings.estimated
    if (CellFlags.has(flags, CellFlags.UNREADABLE)) parts += strings.unreadable
    val group = snapshot.groupSizeAt(index)
    if (group > 1) parts += strings.group.format(group)
    return parts.joinToString(", ")
  }

  private fun String.capitalized(): String = replaceFirstChar { it.titlecase(Italian) }
}
