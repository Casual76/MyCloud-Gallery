package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.testing.PeopleSamples
import org.junit.Test

/** Fase 8 (P0): ogni caso degli stati del §1.5 ha un esempio (P7, P8, P9 partono da qui per screenshot e test), e le regole degli stati tengono. */
class PeopleSamplesTest {

  // I `when` sono esaustivi: un caso nuovo senza esempio non compila qui o fa fallire il conteggio.
  private fun caseOf(state: PeopleSectionState): String = when (state) {
    PeopleSectionState.Off -> "off"
    PeopleSectionState.OffElsewhere -> "off_elsewhere"
    PeopleSectionState.AwaitingNotice -> "awaiting_notice"
    PeopleSectionState.Excluded -> "excluded"
    is PeopleSectionState.FirstRun -> "first_run"
    PeopleSectionState.NotComputedHere -> "not_computed_here"
    is PeopleSectionState.Ready -> "ready"
    is PeopleSectionState.LocalOnly -> "local_only"
    is PeopleSectionState.Unavailable -> "unavailable"
  }

  private fun caseOf(state: PeopleListState): String = when (state) {
    is PeopleListState.Ready -> "ready"
    PeopleListState.Empty -> "empty"
    is PeopleListState.Working -> "working"
  }

  private fun caseOf(state: PersonPageState): String = when (state) {
    PersonPageState.Loading -> "loading"
    is PersonPageState.Ready -> "ready"
    PersonPageState.Forgotten -> "forgotten"
    is PersonPageState.Hidden -> "hidden"
    is PersonPageState.AllHidden -> "all_hidden"
    PersonPageState.NotFound -> "not_found"
  }

  private fun caseOf(state: FaceReviewState): String = when (state) {
    is FaceReviewState.Grid -> "grid_" + state.purpose.code
    FaceReviewState.Applying -> "applying"
    FaceReviewState.Done -> "done"
  }

  private fun caseOf(state: MergeSheetState): String = when (state) {
    is MergeSheetState.Choose -> "choose"
    is MergeSheetState.PickName -> "pick_name"
    is MergeSheetState.Confirm -> "confirm"
  }

  private fun caseOf(state: FacesActivationState): String = when (state) {
    is FacesActivationState.Explain -> "explain"
    is FacesActivationState.NeedsIndexer -> "needs_indexer"
    FacesActivationState.NoPrivateArea -> "no_private_area"
    FacesActivationState.Activating -> "activating"
    is FacesActivationState.Activated -> "activated"
    is FacesActivationState.Failed -> "failed"
  }

  private fun caseOf(state: FacesNoticeState): String = when (state) {
    is FacesNoticeState.Show -> "show"
    FacesNoticeState.Hidden -> "hidden"
  }

  private fun caseOf(state: EraseSheetState): String = when (state) {
    is EraseSheetState.Preview -> "preview"
    EraseSheetState.Confirming -> "confirming"
    EraseSheetState.Started -> "started"
    EraseSheetState.Expired -> "expired"
    is EraseSheetState.Progress -> "progress"
    EraseSheetState.Done -> "done"
    EraseSheetState.DoneHere -> "done_here"
    EraseSheetState.NothingToErase -> "nothing_to_erase"
    is EraseSheetState.WaitingDevices -> "waiting_devices"
  }

  @Test
  fun ogniCasoDegliStatiHaUnEsempio() {
    assertThat(PeopleSamples.sections.map(::caseOf).toSet()).hasSize(9)
    assertThat(PeopleSamples.lists.map(::caseOf).toSet()).hasSize(3)
    assertThat(PeopleSamples.pages.map(::caseOf).toSet()).hasSize(6)
    assertThat(PeopleSamples.reviews.map(::caseOf).toSet()).hasSize(ReviewPurpose.entries.size + 2)
    assertThat(PeopleSamples.merges.map(::caseOf).toSet()).hasSize(3)
    assertThat(PeopleSamples.activations.map(::caseOf).toSet()).hasSize(6)
    assertThat(PeopleSamples.notices.map(::caseOf).toSet()).hasSize(2)
    assertThat(PeopleSamples.erases.map(::caseOf).toSet()).hasSize(9)
    assertThat(PeopleSamples.settings).isNotEmpty()
  }

  /** Q2 (b): in primo piano solo chi ha un nome; i gruppi senza nome sono contati in "Altri volti", mai spariti. */
  @Test
  fun q2b_soloChiHaUnNomeInPrimoPiano() {
    val ready = PeopleSamples.lists.filterIsInstance<PeopleListState.Ready>().single()
    assertThat(ready.named.all { it.name != null }).isTrue()
    assertThat(ready.unnamed).isEmpty()
    assertThat(ready.othersCount).isGreaterThan(0)
    val section = PeopleSamples.sections.filterIsInstance<PeopleSectionState.Ready>().single()
    assertThat(section.top.all { it.name != null }).isTrue()
  }

  /** "Disattiva e cancella": con album smart con persone la conferma resta spenta finche' non si sceglie (nessuna scelta preselezionata). */
  @Test
  fun ilFoglioVuoleLaSceltaPerGliAlbum() {
    val withAlbums = PeopleSamples.erases.filterIsInstance<EraseSheetState.Preview>().first()
    assertThat(withAlbums.canConfirm).isFalse()
    assertThat(withAlbums.copy(choice = PeopleAlbumsChoice.KEEP_PHOTOS).canConfirm).isTrue()
    val without = PeopleSamples.erases.filterIsInstance<EraseSheetState.Preview>().last()
    assertThat(without.canConfirm).isTrue()
  }

  /** Unione con due nomi: si sceglie quale tenere; "Dividi" vuole almeno un volto e non tutti. */
  @Test
  fun unioneEDivisione() {
    val pick = PeopleSamples.merges.filterIsInstance<MergeSheetState.PickName>().single()
    assertThat(pick.canConfirm).isFalse()
    assertThat(pick.copy(chosen = pick.second.id).canConfirm).isTrue()
    val split = PeopleSamples.reviews.filterIsInstance<FaceReviewState.Grid>().first { it.purpose == ReviewPurpose.SPLIT }
    assertThat(split.canApply).isFalse()
    assertThat(split.copy(selection = setOf(split.faces.first().face)).canApply).isTrue()
    assertThat(split.copy(selection = split.faces.map { it.face }.toSet()).canApply).isFalse()
  }

  /** D37: nessun nome nei `toString()` degli stati (possono finire in un log o in un crash). */
  @Test
  fun nessunNomeNeiLog() {
    val all = PeopleSamples.sections + PeopleSamples.lists + PeopleSamples.pages + PeopleSamples.merges + PeopleSamples.erases +
      PeopleSamples.settings + PeopleSamples.notices
    for (state in all) {
      val text = state.toString()
      for (name in listOf("Anna", "Marco", "Nonna", "Tablet", "telefono")) assertThat(text).doesNotContain(name)
    }
  }
}
