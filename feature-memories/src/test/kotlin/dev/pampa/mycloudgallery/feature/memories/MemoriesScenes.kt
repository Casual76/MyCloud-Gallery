package dev.pampa.mycloudgallery.feature.memories

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLicense
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.AllMemoriesUi
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryHeader
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.contract.MusicTrackRow
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerIntro
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerMusic
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

/**
 * Le scene delle schermate dei ricordi (Fase 9, P9): **ogni stato del §1.5** sopra la Libreria sintetica del dispositivo finto (`MemoriesRig`: solo date relative a
 * oggi, nessun dato personale). I titoli, i luoghi e i nomi sono inventati ("Citta' di prova", "Persona di prova").
 */
internal class MemoriesScenes(val rig: MemoriesRig = MemoriesRig()) {

  init {
    runBlocking { rig.refresh() }
  }

  val person: PersonId = PersonId.fromBytes(ByteArray(16) { (it + 11).toByte() })
  val snapshot: TimelineSnapshot get() = rig.lib.snapshot()
  val cards: List<MemoryCard> get() = rig.cards()
  val otd: MemoryCard get() = rig.card(MemoryKind.ON_THIS_DAY, 1)
  private val members: List<MediaId> by lazy { runBlocking { rig.members(otd.id) } }

  fun view(count: Int = members.size): OrderedTimelineView = IndexOrderedTimelineView.of(snapshot, members.take(count))

  // --- La sezione ----------------------------------------------------------------------------------------------------------------

  val sectionReady = MemoriesSectionStates.section(rig.memories.shelf.value)
  val sectionEmpty: MemoriesSectionState = MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS)
  val sectionLibrary: MemoriesSectionState = MemoriesSectionState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY)
  val sectionWorking: MemoriesSectionState = MemoriesSectionState.Working

  // --- La pagina -----------------------------------------------------------------------------------------------------------------

  private fun header(kind: MemoryKind, title: MemoryTitle, name: String? = null, places: List<String> = listOf("Citta' di prova")) =
    MemoryHeader(otd.id, kind, title, name, places)

  private val tripTitle = MemoryTitle(2, DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6)), listOf(3_000_002), "Citta' di prova, marzo 2025", null, null)
  private val personTitle = MemoryTitle(null, DayRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), emptyList(), null, person, null)

  private fun ready(header: MemoryHeader, actions: Set<MemoryAction>, origin: MemoryOrigin? = null) =
    MemoryPageState.Ready(header, view(), members.first(), actions, origin)

  val pageOtd = MemoryPageUi(ready(header(MemoryKind.ON_THIS_DAY, otd.title), setOf(MemoryAction.PLAY, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS)))
  val pageTrip = MemoryPageUi(
    ready(
      header(MemoryKind.TRIP, tripTitle), setOf(MemoryAction.PLAY, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS, MemoryAction.OPEN_ALBUM),
      MemoryOrigin.TripAlbum(AlbumId("00000000-0000-4000-8000-000000000099")),
    ),
  )
  val pagePerson = MemoryPageUi(
    ready(
      header(MemoryKind.PERSON, personTitle, name = "Persona di prova", places = emptyList()),
      setOf(MemoryAction.PLAY, MemoryAction.LESS_PERSON, MemoryAction.NEVER_DAYS, MemoryAction.DISMISS),
    ),
  )
  val pageLoading = MemoryPageUi(MemoryPageState.Loading, preview = otd)
  val pageLoadingBare = MemoryPageUi(MemoryPageState.Loading)
  val pageGone = MemoryPageUi(MemoryPageState.Gone)
  val pageNotFound = MemoryPageUi(MemoryPageState.NotFound)
  val pageUndo = MemoryPageUi(MemoryPageState.Gone, undo = UndoOffer(UndoKind.DISMISS))
  val pageUndoLess = pagePerson.copy(undo = UndoOffer(UndoKind.LESS_PERSON))
  val sheetPerson = pagePerson.copy(less = LessSheetState.Person(person, "Persona di prova", LessLevel.LESS))
  val sheetPersonUnnamed = pagePerson.copy(less = LessSheetState.Person(person, null, null))
  val sheetDays = pageOtd.copy(less = LessSheetState.Days(DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6))))

  // --- Tutti i ricordi -----------------------------------------------------------------------------------------------------------

  val listReady = AllMemoriesUi(MemoriesSectionStates.list(cards), snapshot)
  val listNoCovers = AllMemoriesUi(MemoriesSectionStates.list(cards), null)
  val listEmpty = AllMemoriesUi(MemoriesListState.Empty)

  // --- La presentazione ----------------------------------------------------------------------------------------------------------

  private val items: List<PlayerItem> by lazy { members.mapIndexed { i, id -> if (i == VIDEO_AT) PlayerItem.Video(id, ClipSource("clip-finto", 8_000)) else PlayerItem.Photo(id) } }
  private val head get() = header(MemoryKind.ON_THIS_DAY, otd.title)

  private fun player(state: PlayerState, controls: Boolean = false, intro: Boolean = false, musicMissing: Boolean = false) =
    PlayerUi(state, head, if (intro) PlayerIntro(head, items.first()) else null, items, snapshot, controls, musicMissing)

  private fun playing(index: Int, rm: Boolean = false, music: PlayerMusic = PlayerMusic.MUTED) =
    PlayerState.Playing(index, items[index], items.drop(index + 1).take(2), music, rm, talkBack = false)

  val playerLoading = PlayerUi()
  val playerIntro get() = player(playing(0), intro = true)
  val playerPhoto get() = player(playing(3))
  val playerVideo get() = player(playing(VIDEO_AT))
  val playerPaused get() = player(PlayerState.Paused(3, items[3], false, false), controls = true)
  val playerReduced get() = player(playing(3, rm = true))
  val playerTalkBack get() = player(PlayerState.Paused(3, items[3], false, talkBack = true), controls = true)
  val playerTalkBackIntro get() = player(PlayerState.Paused(0, items[0], false, talkBack = true), controls = true, intro = true)
  val playerMusicMissing get() = player(playing(3, music = PlayerMusic.MISSING), musicMissing = true)
  val playerEnded get() = player(PlayerState.Ended(items.size, false, false), controls = true)
  val playerGone = PlayerUi(gone = true)

  // --- Le impostazioni -----------------------------------------------------------------------------------------------------------

  private val choices = listOf(
    LessChoice(LessTarget.Person(person), LessLevel.LESS, "Persona di prova"),
    LessChoice(LessTarget.Person(PersonId.fromBytes(ByteArray(16) { (it + 40).toByte() })), LessLevel.NEVER, null),
    LessChoice(LessTarget.Days(DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6))), LessLevel.NEVER, null),
  )

  private fun settings(
    people: PeopleMemoriesRow = PeopleMemoriesRow.ON,
    prefs: NotifyPrefs = NotifyPrefs(),
    permission: NotifyPermission = NotifyPermission.GRANTED,
    less: List<LessChoice> = emptyList(),
    music: MusicChoice = MusicChoice.None,
    tracks: List<MusicTrackRow> = emptyList(),
  ) = MemoriesSettingsUi(MemoriesSettingsState(people, prefs, permission, less, music, tracks.associate { it.pin.id to it.state }), tracks)

  private fun pin(id: String, title: String) = MusicTrackPin(id, title, "Autore di prova", MusicLicense.CC0, 3_000_000, "b".repeat(64))

  val settingsDefault = settings()
  val settingsFull = settings(
    prefs = NotifyPrefs(hour = LocalTime.of(20, 30), frequency = NotifyFrequency.DAILY, lockScreen = LockScreenMode.TITLE_AND_PHOTO), less = choices,
    music = MusicChoice.OwnSong("riferimento"),
  )
  val settingsPeopleHidden = settings(people = PeopleMemoriesRow.HIDDEN)
  val settingsPeopleOff = settings(people = PeopleMemoriesRow.OFF, less = choices.takeLast(1))
  val settingsNotifyOff = settings(prefs = NotifyPrefs(enabled = false))
  val settingsAskable = settings(permission = NotifyPermission.ASKABLE)
  val settingsDenied = settings(permission = NotifyPermission.DENIED)
  val settingsChannelOff = settings(permission = NotifyPermission.CHANNEL_OFF)
  val settingsTracks = settings(
    music = MusicChoice.FreeTrack("brano_due"),
    tracks = listOf(
      MusicTrackRow(pin("brano_uno", "Brano di prova uno"), MusicTrackState.NotInstalled, false),
      MusicTrackRow(pin("brano_due", "Brano di prova due"), MusicTrackState.Ready, true),
      MusicTrackRow(pin("brano_tre", "Brano di prova tre"), MusicTrackState.Installing(42), false),
      MusicTrackRow(pin("brano_quattro", "Brano di prova quattro"), MusicTrackState.Failed("hash"), false),
    ),
  )

  private companion object {
    /** Il video della presentazione: il quinto elemento. */
    const val VIDEO_AT = 4
  }
}
