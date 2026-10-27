package dev.pampa.mycloudgallery.feature.people.testing

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.ActivationRequirements
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.contract.PersonHeader
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.ReviewFace
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose

/**
 * Stati di esempio delle schermate delle persone (Fase 8, P0): un esempio per ogni caso del §1.5, per gli screenshot e i test di P7, P8 e
 * P9 prima che ci siano i ViewModel. **Nessun dato personale**: nomi inventati, id e chiavi deterministici, nessuna immagine di volto.
 */
object PeopleSamples {
  private val empty = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))

  fun person(n: Int): PersonId = PersonId.fromBytes(ByteArray(16) { (n + it).toByte() })

  private fun media(n: Int): MediaId = MediaId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

  private fun key(n: Int): ContentKey = ContentKey(n.toString(16).padStart(2, '0').repeat(32))

  fun face(n: Int): FaceLocator = FaceLocator(key(n), 0, FaceBox(1_000, 1_000, 9_000, 9_000))

  val anna = PersonCard(person(1), "Anna", 120, face(1))
  val marco = PersonCard(person(2), "Marco", 64, face(2))
  val nonna = PersonCard(person(3), "Nonna Lia", 31, null)
  val unnamed = PersonCard(person(4), null, 18, face(4))

  val sections: List<PeopleSectionState> = listOf(
    PeopleSectionState.Off,
    PeopleSectionState.AwaitingNotice,
    PeopleSectionState.Excluded,
    PeopleSectionState.FirstRun(42),
    PeopleSectionState.FirstRun(null),
    PeopleSectionState.Ready(listOf(anna, marco, nonna), total = 3, othersCount = 12),
    PeopleSectionState.LocalOnly(listOf(anna), total = 1, othersCount = 3),
    PeopleSectionState.Unavailable(PeopleUnavailable.NEEDS_UPDATE),
  )

  val lists: List<PeopleListState> = listOf(
    // Q2 (b): solo chi ha un nome, gli altri in "Altri volti".
    PeopleListState.Ready(listOf(anna, marco, nonna), emptyList(), othersCount = 12, hiddenCount = 1),
    PeopleListState.Empty,
    PeopleListState.Working(7),
  )

  private val header = PersonHeader(anna.id, anna.name, anna.photos, anna.cover)

  val pages: List<PersonPageState> = listOf(
    PersonPageState.Loading,
    PersonPageState.Ready(
      header,
      empty,
      setOf(PersonAction.RENAME, PersonAction.MERGE, PersonAction.SPLIT, PersonAction.REVIEW, PersonAction.HIDE, PersonAction.FORGET),
      listOf(MergeProposal(anna, unnamed)),
    ),
    PersonPageState.Forgotten,
    PersonPageState.Hidden(header),
    PersonPageState.NotFound,
  )

  private val reviewFaces = (1..6).map { ReviewFace(face(10 + it), media(10 + it), anna.id) }

  val reviews: List<FaceReviewState> = listOf(
    FaceReviewState.Grid(ReviewPurpose.CHECK, anna.id, reviewFaces, setOf(reviewFaces[2].face)),
    FaceReviewState.Grid(ReviewPurpose.SPLIT, anna.id, reviewFaces, emptySet()),
    FaceReviewState.Grid(ReviewPurpose.OTHERS, null, reviewFaces.map { it.copy(person = unnamed.id) }, emptySet()),
    FaceReviewState.Applying,
    FaceReviewState.Done,
  )

  val merges: List<MergeSheetState> = listOf(
    MergeSheetState.Choose(listOf(marco, nonna, unnamed), setOf(unnamed.id)),
    MergeSheetState.PickName(anna, marco, chosen = null),
    MergeSheetState.Confirm(listOf(anna, unnamed), keepNameOf = anna.id, proposal = MergeProposal(anna, unnamed)),
  )

  private val requirements = ActivationRequirements(
    privateArea = true,
    indexers = 1,
    thisDeviceIndexer = false,
    canBecomeIndexer = true,
    localOnly = true,
  )

  val activations: List<FacesActivationState> = listOf(
    FacesActivationState.Explain(1, requirements),
    FacesActivationState.NeedsIndexer(canBecomeIndexer = true),
    FacesActivationState.NoPrivateArea,
    FacesActivationState.Activating,
    FacesActivationState.Activated(syncPending = true),
    FacesActivationState.Failed(ActivationProblem.STALE_CONSENT),
  )

  val notices: List<FacesNoticeState> = listOf(FacesNoticeState.Show(1, "Tablet di casa"), FacesNoticeState.Show(1, null), FacesNoticeState.Hidden)

  private val tablet = DeviceAck("Tablet di casa", "A".repeat(26), AckState.CONFIRMED, lastContactAtNas = 1_800_000_000_000L, thisDevice = false)
  private val phone = DeviceAck("Questo telefono", "B".repeat(26), AckState.CONFIRMED, lastContactAtNas = null, thisDevice = true)
  private val old = DeviceAck("Telefono vecchio", "C".repeat(26), AckState.WAITING, lastContactAtNas = 1_700_000_000_000L, thisDevice = false)

  private val progress = EraseSheetState.Progress(StepState.DONE, StepState.RUNNING, listOf(phone, tablet, old), waiting = null)

  val erases: List<EraseSheetState> = listOf(
    EraseSheetState.Preview(3, albumsWithPeople = 2, historyWithNames = true, devices = listOf(phone, tablet, old), choice = null, localOnly = false),
    EraseSheetState.Preview(0, albumsWithPeople = 0, historyWithNames = false, devices = listOf(phone), choice = null, localOnly = true),
    EraseSheetState.Confirming,
    EraseSheetState.Started,
    EraseSheetState.Expired,
    progress,
    progress.copy(nas = StepState.WAITING, waiting = EraseWait.NAS),
    EraseSheetState.Done,
    EraseSheetState.WaitingDevices(listOf(old)),
  )

  val settings: List<PeopleSettingsState> = listOf(
    PeopleSettingsState(
      shown = PeopleSwitchShown.ON,
      devices = listOf(ComputingDevice("Questo telefono", thisDevice = true, excluded = false), ComputingDevice("Tablet di casa", false, false)),
      progress = FacesStatus.Running(done = 420, total = 1_000, phase = FacesPhase.DETECT),
      excludedHere = false,
      hiddenPeople = 1,
      models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache-2.0")),
      canErase = true,
      erase = null,
    ),
    PeopleSettingsState(PeopleSwitchShown.OFF, emptyList(), FacesStatus.Off, false, 0, emptyList(), canErase = false, erase = null),
    PeopleSettingsState(PeopleSwitchShown.ERASING, emptyList(), FacesStatus.Off, false, 0, emptyList(), canErase = false, erase = progress),
  )

  /** La scelta per gli album smart con persone, per gli screenshot del foglio. */
  val albumChoices: List<PeopleAlbumsChoice> = PeopleAlbumsChoice.entries
}
