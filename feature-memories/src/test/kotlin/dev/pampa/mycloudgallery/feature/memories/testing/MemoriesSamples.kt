package dev.pampa.mycloudgallery.feature.memories.testing

import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMonth
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryHeader
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerMusic
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import java.time.LocalDate
import java.time.YearMonth

/**
 * Stati di esempio delle schermate dei ricordi (Fase 9, P0): un esempio per ogni caso del §1.5, per gli screenshot e i test di P9 e P10 prima
 * che ci siano i ViewModel. **Nessun dato personale**: luoghi e viaggi inventati, nomi inventati, id deterministici, nessuna immagine.
 */
object MemoriesSamples {
  private val empty = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))

  fun media(n: Int): MediaId = MediaId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

  val person: PersonId = PersonId.fromBytes(ByteArray(16) { (it + 11).toByte() })

  private val otdKey = MemoryKey.OnThisDay(LocalDate.of(2025, 10, 27))
  private val tripKey = MemoryKey.Trip(DayRange(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 6)))
  private val personKey = MemoryKey.Person(person, PersonWindow.Year(2024))
  private val monthKey = MemoryKey.Month(YearMonth.of(2026, 9))

  private val otdTitle = MemoryTitle(1, DayRange.of(otdKey.day), listOf(3_000_001), null, null, null)
  private val tripTitle = MemoryTitle(2, tripKey.days, listOf(3_000_002), "Citta' di prova, marzo 2024", null, null)
  private val personTitle = MemoryTitle(null, DayRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)), emptyList(), null, person, null)
  private val monthTitle = MemoryTitle(null, DayRange(monthKey.month.atDay(1), monthKey.month.atEndOfMonth()), emptyList(), null, null, monthKey.month)

  val otd = MemoryCard(MemoryIds.of(otdKey), MemoryKind.ON_THIS_DAY, otdTitle, media(1), 18, 20261027, isNew = true)
  val trip = MemoryCard(MemoryIds.of(tripKey), MemoryKind.TRIP, tripTitle, media(2), 36, 20261027, isNew = true)
  val personCard = MemoryCard(MemoryIds.of(personKey), MemoryKind.PERSON, personTitle, media(3), 24, 20261026, isNew = false)
  val month = MemoryCard(MemoryIds.of(monthKey), MemoryKind.MONTH, monthTitle, media(4), 28, 20261003, isNew = false)

  val sections: List<MemoriesSectionState> = listOf(
    MemoriesSectionState.Ready(listOf(otd, trip, personCard, month), unseen = 2),
    MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS),
    MemoriesSectionState.Working,
  )

  val pages: List<MemoryPageState> = listOf(
    MemoryPageState.Loading,
    MemoryPageState.Ready(
      MemoryHeader(trip.id, MemoryKind.TRIP, tripTitle, null, listOf("Citta' di prova")),
      empty,
      media(2),
      setOf(MemoryAction.PLAY, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS, MemoryAction.OPEN_ALBUM),
      MemoryOrigin.TripAlbum(AlbumId("00000000-0000-4000-8000-000000000099")),
    ),
    MemoryPageState.Ready(
      MemoryHeader(personCard.id, MemoryKind.PERSON, personTitle, "Persona di prova", emptyList()),
      empty,
      media(3),
      setOf(MemoryAction.PLAY, MemoryAction.LESS_PERSON, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS),
      null,
    ),
    MemoryPageState.Gone,
    MemoryPageState.NotFound,
  )

  val lists: List<MemoriesListState> = listOf(
    MemoriesListState.Ready(listOf(MemoriesMonth(YearMonth.of(2026, 10), listOf(otd, trip, personCard)), MemoriesMonth(YearMonth.of(2026, 9), listOf(month)))),
    MemoriesListState.Empty,
  )

  val players: List<PlayerState> = listOf(
    PlayerState.Playing(0, PlayerItem.Photo(media(1)), listOf(PlayerItem.Photo(media(5)), PlayerItem.Video(media(6), ClipSource("clip", 8_000))), PlayerMusic.MUTED, reducedMotion = false, talkBack = false),
    PlayerState.Playing(3, PlayerItem.Video(media(6), null), emptyList(), PlayerMusic.PLAYING, reducedMotion = true, talkBack = false),
    PlayerState.Paused(2, PlayerItem.Photo(media(5)), reducedMotion = false, talkBack = true),
    PlayerState.Ended(18, reducedMotion = false, talkBack = false),
  )

  val sheets: List<LessSheetState> = listOf(
    LessSheetState.Person(person, "Persona di prova", null),
    LessSheetState.Days(tripKey.days),
    LessSheetState.Done(LessChoice(LessTarget.Person(person), LessLevel.LESS, "Persona di prova")),
    LessSheetState.Undone,
  )

  val settings: List<MemoriesSettingsState> = listOf(
    MemoriesSettingsState(PeopleMemoriesRow.ON, NotifyPrefs(), NotifyPermission.GRANTED, emptyList(), MusicChoice.None, emptyMap()),
    MemoriesSettingsState(
      PeopleMemoriesRow.HIDDEN, NotifyPrefs(enabled = false), NotifyPermission.ASKABLE,
      listOf(LessChoice(LessTarget.Days(tripKey.days), LessLevel.NEVER, null)), MusicChoice.OwnSong("content://prova/canzone"), emptyMap(),
    ),
    MemoriesSettingsState(PeopleMemoriesRow.OFF, NotifyPrefs(), NotifyPermission.CHANNEL_OFF, emptyList(), MusicChoice.None, emptyMap()),
    MemoriesSettingsState(PeopleMemoriesRow.ON, NotifyPrefs(), NotifyPermission.DENIED, emptyList(), MusicChoice.None, emptyMap()),
  )
}
