package dev.pampa.mycloudgallery.feature.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.CloseSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import java.time.LocalDate

/**
 * Stati di esempio delle schermate degli album (Fase 7, P0): un esempio per ogni caso del §1.5, per gli screenshot e i test di P10 prima
 * che ci siano i ViewModel. Nessun dato personale: titoli e account inventati, nessuna miniatura vera.
 */
object AlbumSamples {
  private val empty = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))
  private val day = LocalDate.of(2026, 3, 14)
  private val cover = CoverRef.Auto(MediaId("00000000-0000-4000-8000-000000000999"))

  private fun album(n: Int): AlbumId = AlbumId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

  val manual = Album.Manual(album(1), "Compleanno di Anna", cover, 42, AlbumSort.NEWEST_FIRST, 1_000, day)

  val smart = Album.Smart(
    album(2), "Oslo 2026", cover, 230, AlbumSort.OLDEST_FIRST, 2_000, day,
    SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(day, day.plusDays(5)))), null, null), frozen = false, suggested = 12, newSinceSeen = 3,
  )

  val sharedMine = Album.Shared(
    album(3), "Prova Oslo", AlbumKind.MANUAL, cover, 23, AlbumSort.NEWEST_FIRST, 3_000, day, ShareLevel.ADD, AccountId("test"), null,
    mine = true, state = SharedState.ACTIVE,
  )

  val sharedByOther = sharedMine.copy(id = album(4), title = "Natale in famiglia", creator = AccountId("test2"), mine = false)

  private val tripDays = LocalDayRange(day, day.plusDays(4))

  val trip = TripProposal(
    TripId.of(TripKind.TRIP, tripDays, listOf(3143244)), TripKind.TRIP, tripDays, "Oslo, marzo 2026", 180,
    MediaId("00000000-0000-4000-8000-000000000998"), listOf(3143244),
  )

  val event = trip.copy(
    id = TripId.of(TripKind.EVENT, LocalDayRange(day, day), emptyList()), kind = TripKind.EVENT, days = LocalDayRange(day, day),
    label = "Sabato 14 marzo 2026", placeIds = emptyList(),
  )

  val lists: List<AlbumListState> = listOf(
    AlbumListState.Loading,
    AlbumListState.Ready(listOf(manual, sharedMine, sharedByOther), listOf(smart), AlbumListSort.RECENT, emptyList()),
    AlbumListState.Ready(
      listOf(manual), emptyList(), AlbumListSort.NAME,
      listOf(SharedAlbumNotice.Closed(album(5), "Gita al lago", CloseMode.STOP, keptCopy = album(6))),
    ),
    AlbumListState.Empty,
  )

  /** Le azioni di un altro familiare a "Gli altri aggiungono", dalla tabella (§2.4). */
  private val permissions = PermissionInfo(
    ShareLevel.ADD, AccountId("test2"), mine = false,
    allowed = AlbumOp.entries.filter { AlbumPermissions.admits(it, ShareLevel.ADD, setOf(AlbumRole.OTHER)) }.toSet(),
  )

  val pages: List<AlbumPageState> = listOf(
    AlbumPageState.Ready(AlbumContent(manual, empty), AlbumSort.NEWEST_FIRST, null, emptyList()),
    AlbumPageState.Ready(
      AlbumContent(sharedByOther, empty), AlbumSort.NEWEST_FIRST, permissions, listOf(AlbumBanner.Incoming(2), AlbumBanner.FilesFromPc(3)),
    ),
    AlbumPageState.Recomputing(smart, 1_200, 4_000),
    AlbumPageState.Closed(SharedAlbumNotice.Closed(album(5), "Gita al lago", CloseMode.DELETE, keptCopy = null)),
    AlbumPageState.Unavailable(AlbumUnavailableReason.NOT_FOUND),
  )

  private val preview = SmartPreview(confident = 230, suggested = 12, tooBroad = false, sample = emptyList(), searchUsed = false)

  val editors: List<SmartEditorState> = listOf(
    SmartEditorState.Interpreting("Oslo 2026"),
    SmartEditorState.Proposal(smart.rules, emptyList(), listOf(LocalDayRange(day, day.plusDays(5))), preview, emptySet()),
    SmartEditorState.TooBroad(smart.rules, preview.copy(confident = 5_000, tooBroad = true), setOf(BroadHint.NARROW_PERIOD)),
    SmartEditorState.NoSearch(SmartRules(null, null, "gatto"), preview.copy(confident = 0, suggested = 0)),
  )

  val suggestions: List<SuggestionsState> = listOf(SuggestionsState.None, SuggestionsState.Some(12, empty), SuggestionsState.ToShare(4, empty))

  val shareSheets: List<ShareSheetState> = listOf(ShareSheetState.Blocked(ShareBlock.TEST_SHARES_ONLY), ShareSheetState.Confirming)

  val closeSheets: List<CloseSheetState> = listOf(
    CloseSheetState(album(3), "Prova Oslo", CloseMode.STOP, forOthers = null, bringBackCount = 20, bringBack = false),
    CloseSheetState(album(3), "Prova Oslo", CloseMode.DELETE, forOthers = OthersAfterClose.KEEP_COPY, bringBackCount = 20, bringBack = true),
  )

  val trips: List<TripsState> = listOf(TripsState.Proposals(listOf(trip, event), hasOlder = true), TripsState.NoGps(listOf(event)), TripsState.Empty, TripsState.Searching)
}
