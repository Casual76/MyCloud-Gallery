package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.GridDays
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.ShownDate
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Fase H (R2, scelta dell'utente dell'11/11): toccando la data in alto si apre un calendario vero, mese per mese, ancorato all'etichetta
// (il pop-up della chrome, composto solo dalla prima apertura). I giorni con foto sono pieni e gli unici toccabili; un tocco porta la
// Libreria a quel giorno. Il titolo del mese apre la scelta rapida di anno e mese (una libreria copre molti anni).

/**
 * Il calendario del salto a una data. I giorni con foto ([GridDays]) si contano **all'apertura**, fuori dal thread principale, dalla vista
 * della griglia; finche' non ci sono si vede il posto del calendario con l'indicatore dell'engine (pochi millisecondi). [shown] e' cio' che
 * l'etichetta diceva al tocco: il calendario parte dal suo mese, e se era un giorno quel giorno e' segnato. [onPick] riceve il giorno scelto.
 */
@Composable
internal fun LibraryDateCalendar(
  grid: MediaGridState,
  shown: ShownDate?,
  today: LocalDate,
  onPick: (GridDays, LocalDate) -> Unit,
  modifier: Modifier = Modifier,
) {
  val days by produceState<GridDays?>(null, grid) { value = withContext(Dispatchers.Default) { GridDays.of(grid.view) } }
  Column(
    modifier = modifier
      .widthIn(min = CalendarMinWidth, max = CalendarMaxWidth)
      .fillMaxWidth()
      .padding(horizontal = CalendarPaddingH, vertical = CalendarPaddingV)
      .testTag(CalendarTag),
  ) {
    val ready = days
    if (ready == null || ready.isEmpty) {
      Box(modifier = Modifier.fillMaxWidth().height(LoadingHeight), contentAlignment = Alignment.Center) {
        if (ready == null) FluidSpinner()
      }
    } else {
      CalendarContent(ready, shown, today, onPick)
    }
  }
}

@Composable
private fun CalendarContent(days: GridDays, shown: ShownDate?, today: LocalDate, onPick: (GridDays, LocalDate) -> Unit) {
  val months = days.months
  val start = remember(days) { days.startMonth(shown?.date)?.let(months::indexOf)?.coerceAtLeast(0) ?: months.lastIndex }
  // Il mese da cui il pager parte: quello dell'etichetta, poi quello scelto nella scelta rapida. Cambiarlo rifa' il pager gia' su quel mese
  // (nessun fotogramma del mese di prima, e nessuna richiesta a un pager che non e' in scena).
  var anchor by remember(days) { mutableIntStateOf(start) }
  var picking by remember { mutableStateOf(false) }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val locale = GalleryDates.LOCALE
  val selected = shown?.takeIf { it.exactDay }?.date
  key(anchor) {
    val pager = rememberPagerState(initialPage = anchor) { months.size }
    val scope = rememberCoroutineScope()

    fun go(page: Int) {
      val target = page.coerceIn(0, months.lastIndex)
      scope.launch {
        if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = FluidMotion.pixels(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy))
      }
    }

    val month = months[pager.currentPage.coerceIn(0, months.lastIndex)]
    if (picking) {
      MonthPicker(
        days = days,
        current = month,
        locale = locale,
        onBack = { picking = false },
        onPick = { chosen ->
          val page = months.indexOf(chosen)
          picking = false
          if (page >= 0) anchor = page
        },
      )
    } else {
      CalendarHeader(
        month = month,
        canPrevious = pager.currentPage > 0,
        canNext = pager.currentPage < months.lastIndex,
        onPrevious = { go(pager.currentPage - 1) },
        onNext = { go(pager.currentPage + 1) },
        onTitle = { picking = true },
      )
      WeekdayRow(locale)
      MonthPager(pager, months, days, today, selected, locale) { date -> onPick(days, date) }
    }
  }
}

/** Il titolo del mese (tocco: scelta rapida di anno e mese) fra le due frecce. Un mese senza foto non c'e': le frecce saltano ai mesi con foto. */
@Composable
private fun CalendarHeader(month: YearMonth, canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onTitle: () -> Unit) {
  val ink = MaterialTheme.colorScheme.onSurface
  Row(modifier = Modifier.fillMaxWidth().heightIn(min = HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
    GalleryChromeIconButton(
      icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
      contentDescription = stringResource(R.string.library_calendar_previous),
      onClick = onPrevious,
      enabled = canPrevious,
      tint = ink,
      modifier = Modifier.testTag(PreviousTag),
    )
    val title = GalleryDates.monthYear(month)
    val choose = stringResource(R.string.library_calendar_choose)
    Row(
      modifier = Modifier
        .weight(1f)
        .heightIn(min = HeaderHeight)
        .semantics(mergeDescendants = true) { contentDescription = "$title. $choose" }
        .fluidPressable(onClick = onTitle, role = Role.Button, focusShape = FluidCapsuleShape)
        .testTag(TitleTag),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
      Spacer(Modifier.width(TitleGlyphGap))
      Icon(imageVector = Icons.Rounded.ExpandMore, contentDescription = null, tint = ink, modifier = Modifier.size(TitleGlyph))
    }
    GalleryChromeIconButton(
      icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
      contentDescription = stringResource(R.string.library_calendar_next),
      onClick = onNext,
      enabled = canNext,
      tint = ink,
      modifier = Modifier.testTag(NextTag),
    )
  }
}

/** I nomi dei giorni, dal lunedi' (la settimana italiana), nella lingua dell'app (`GalleryDates.LOCALE`, come le date delle intestazioni). Per TalkBack non dicono niente: ogni giorno dice la sua data. */
@Composable
private fun WeekdayRow(locale: Locale) {
  val names = remember(locale) { WeekOrder.map { it.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { c -> c.titlecase(locale) } } }
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = WeekdayPaddingV).clearAndSetSemantics { }) {
    for (name in names) {
      Text(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/** I mesi con foto, uno per pagina: si scorrono di lato col dito. Ogni pagina ha sempre sei settimane, cosi' il pannello non cambia altezza. */
@Composable
private fun MonthPager(
  pager: PagerState,
  months: List<YearMonth>,
  days: GridDays,
  today: LocalDate,
  selected: LocalDate?,
  locale: Locale,
  onPick: (LocalDate) -> Unit,
) {
  HorizontalPager(state = pager, key = { months[it].toString() }, modifier = Modifier.fillMaxWidth().testTag(PagerTag)) { page ->
    MonthGrid(months[page], days, today, selected, locale, onPick)
  }
}

@Composable
private fun MonthGrid(month: YearMonth, days: GridDays, today: LocalDate, selected: LocalDate?, locale: Locale, onPick: (LocalDate) -> Unit) {
  val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
  val length = month.lengthOfMonth()
  val full = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
  Column(modifier = Modifier.fillMaxWidth()) {
    for (week in 0 until Weeks) {
      Row(modifier = Modifier.fillMaxWidth()) {
        for (column in 0 until DaysInWeek) {
          val day = week * DaysInWeek + column - lead + 1
          if (day in 1..length) {
            val date = month.atDay(day)
            DayCell(
              date = date,
              hasPhotos = days.has(date),
              isToday = date == today,
              isSelected = date == selected,
              description = date.format(full),
              onPick = onPick,
              modifier = Modifier.weight(1f),
            )
          } else {
            Spacer(Modifier.weight(1f).heightIn(min = CellHeight))
          }
        }
      }
    }
  }
}

/**
 * Un giorno: con foto il numero e' pieno, con un punto discreto sotto, e si tocca; senza foto e' spento e non si tocca. Oggi ha il numero
 * nel colore d'accento; il giorno che l'etichetta diceva ha il disco d'accento.
 */
@Composable
private fun DayCell(
  date: LocalDate,
  hasPhotos: Boolean,
  isToday: Boolean,
  isSelected: Boolean,
  description: String,
  onPick: (LocalDate) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val photos = stringResource(R.string.library_calendar_day_photos, description)
  val empty = stringResource(R.string.library_calendar_day_empty, description)
  val todayWord = stringResource(R.string.library_calendar_today)
  val ink = when {
    isSelected -> scheme.onPrimary
    isToday -> scheme.primary
    else -> scheme.onSurface
  }
  Box(
    modifier = modifier
      // Il tag prima della semantica: un giorno spento la sostituisce (clearAndSetSemantics), e il tag deve restare.
      .testTag(dayTag(date))
      .heightIn(min = CellHeight)
      .then(
        if (hasPhotos) {
          Modifier
            .semantics(mergeDescendants = true) {
              contentDescription = photos
              if (isToday) stateDescription = todayWord
              selected = isSelected
            }
            .fluidPressable(onClick = { onPick(date) }, role = Role.Button, focusShape = FluidCapsuleShape)
        } else {
          Modifier.clearAndSetSemantics {
            contentDescription = empty
            if (isToday) stateDescription = todayWord
            disabled()
          }
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .defaultMinSize(minWidth = DayDisc, minHeight = DayDisc)
        .background(if (isSelected) scheme.primary else Color.Transparent, FluidCapsuleShape)
        .alpha(if (hasPhotos || isSelected) 1f else EmptyDayAlpha),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = date.dayOfMonth.toString(),
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (hasPhotos || isToday) FontWeight.SemiBold else FontWeight.Normal),
          color = ink,
          maxLines = 1,
        )
        // Il segno dei giorni con foto: un punto discreto sotto il numero (sul disco del giorno scelto, nel colore del numero).
        Box(
          modifier = Modifier
            .padding(top = DotGap)
            .size(Dot)
            .background(if (hasPhotos) (if (isSelected) scheme.onPrimary else scheme.primary) else Color.Transparent, FluidCapsuleShape),
        )
      }
    }
  }
}

/**
 * La scelta rapida di anno e mese: gli anni con foto, dal piu' recente, e sotto ognuno i dodici mesi; i mesi senza foto sono spenti. Il mese
 * che il calendario mostrava e' segnato, e il suo anno si porta in vista all'apertura.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthPicker(days: GridDays, current: YearMonth, locale: Locale, onBack: () -> Unit, onPick: (YearMonth) -> Unit) {
  val ink = MaterialTheme.colorScheme.onSurface
  Row(modifier = Modifier.fillMaxWidth().heightIn(min = HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
    val title = stringResource(R.string.library_calendar_choose)
    val back = stringResource(R.string.library_calendar_back)
    Row(
      modifier = Modifier
        .weight(1f)
        .heightIn(min = HeaderHeight)
        .semantics(mergeDescendants = true) { contentDescription = back }
        .fluidPressable(onClick = onBack, role = Role.Button, focusShape = FluidCapsuleShape)
        .testTag(TitleTag),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
      Spacer(Modifier.width(TitleGlyphGap))
      Icon(imageVector = Icons.Rounded.ExpandLess, contentDescription = null, tint = ink, modifier = Modifier.size(TitleGlyph))
    }
  }
  val names = remember(locale) { Month.values().map { it.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { c -> c.titlecase(locale) } } }
  for (year in days.yearsNewestFirst) {
    val requester = remember { BringIntoViewRequester() }
    if (year == current.year) LaunchedEffect(Unit) { requester.bringIntoView() }
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).padding(top = YearGap)) {
      Text(
        text = year.toString(),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = ink,
        modifier = Modifier.padding(horizontal = YearTitlePaddingH, vertical = YearTitlePaddingV).semantics { heading() },
      )
      for (half in 0 until 2) {
        Row(modifier = Modifier.fillMaxWidth()) {
          for (index in 0 until MonthsPerRow) {
            val month = YearMonth.of(year, half * MonthsPerRow + index + 1)
            MonthCell(
              month = month,
              name = names[month.monthValue - 1],
              enabled = days.has(month),
              isCurrent = month == current,
              onPick = onPick,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MonthCell(month: YearMonth, name: String, enabled: Boolean, isCurrent: Boolean, onPick: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val spoken = GalleryDates.monthYear(month)
  val empty = stringResource(R.string.library_calendar_month_empty, spoken)
  Box(
    modifier = modifier
      // Il tag prima della semantica: un giorno spento la sostituisce (clearAndSetSemantics), e il tag deve restare.
      .testTag(monthTag(month))
      .heightIn(min = CellHeight)
      .then(
        if (enabled) {
          Modifier
            .semantics(mergeDescendants = true) {
              contentDescription = spoken
              selected = isCurrent
            }
            .fluidPressable(onClick = { onPick(month) }, role = Role.Button, focusShape = FluidCapsuleShape)
        } else {
          Modifier.clearAndSetSemantics {
            contentDescription = empty
            disabled()
          }
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .defaultMinSize(minWidth = MonthChipMinWidth, minHeight = DayDisc)
        .background(if (isCurrent) scheme.primary else Color.Transparent, FluidCapsuleShape)
        .padding(horizontal = MonthChipPaddingH)
        .alpha(if (enabled || isCurrent) 1f else EmptyDayAlpha),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal),
        color = if (isCurrent) scheme.onPrimary else scheme.onSurface,
        maxLines = 1,
      )
    }
  }
}

/** La settimana dal lunedi'. */
private val WeekOrder: List<DayOfWeek> = listOf(
  DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

internal const val CalendarTag = "calendario"
internal const val PagerTag = "calendario-mesi"
internal const val TitleTag = "calendario-titolo"
internal const val PreviousTag = "calendario-precedente"
internal const val NextTag = "calendario-successivo"

internal fun dayTag(date: LocalDate): String = "calendario-giorno-$date"

internal fun monthTag(month: YearMonth): String = "calendario-mese-$month"

private const val Weeks = 6
private const val DaysInWeek = 7
private const val MonthsPerRow = 6

/** Un giorno senza foto: spento come un tasto disattivato. */
private const val EmptyDayAlpha = 0.38f

// Le misure del calendario. Il pop-up della chrome e' largo quanto serve fino a 360 dp (mai oltre lo schermo meno 16 dp per lato): sette
// colonne di almeno 44 dp entrano anche a 360 dp. Con il testo grande le celle crescono (altezze minime, mai fisse).
private val CalendarMinWidth: Dp = 300.dp
private val CalendarMaxWidth: Dp = 360.dp
private val CalendarPaddingH: Dp = 8.dp
private val CalendarPaddingV: Dp = 10.dp
private val HeaderHeight: Dp = 48.dp
private val CellHeight: Dp = 44.dp
private val DayDisc: Dp = 38.dp
private val Dot: Dp = 4.dp
private val DotGap: Dp = 1.dp
private val TitleGlyph: Dp = 20.dp
private val TitleGlyphGap: Dp = 4.dp
private val WeekdayPaddingV: Dp = 6.dp
private val YearGap: Dp = 6.dp
private val YearTitlePaddingH: Dp = 8.dp
private val YearTitlePaddingV: Dp = 4.dp
private val MonthChipMinWidth: Dp = 44.dp
private val MonthChipPaddingH: Dp = 6.dp
private val LoadingHeight: Dp = HeaderHeight + CellHeight * (Weeks + 1)
