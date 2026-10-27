package dev.pampa.mycloudgallery.feature.people

import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.CoverChoicesUi
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewUi
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.MergeUi
import dev.pampa.mycloudgallery.feature.people.contract.NamingUi
import dev.pampa.mycloudgallery.feature.people.contract.OthersUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.list.OthersController
import dev.pampa.mycloudgallery.feature.people.list.PeopleListController
import dev.pampa.mycloudgallery.feature.people.page.PersonPageController
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Le scene dei test delle schermate e degli screenshot (Fase 8, P9): un mondo **sintetico** pieno (nove gruppi di volti, quattro con un nome, uno nascosto,
 * uno solo di media nascosti, l'ultima foto di Anna nascosta), costruito con [PeopleFixture]. Gli stati delle schermate vengono dai controllori veri, sul
 * riferimento `InMemoryPeople`: sono gli stessi che la rotta darebbe, non copie scritte a mano. Nessun volto vero: i ritagli sono sagome astratte.
 */
class PeopleScenes(namedOnly: Boolean = true) {
  val world = PeopleFixture(namedOnly = namedOnly, identities = 9)
  val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  val crops = SyntheticCrops()
  val thumbs = TestPeopleThumbnails()

  val giulia: PersonId = world.extras[0]
  val luca: PersonId = world.extras[1]
  val senzaNomeUno: PersonId = world.extras[2]
  val senzaNomeDue: PersonId = world.extras[3]
  val nascosta: PersonId = world.extras[4]

  init {
    runBlocking {
      world.people.rename(world.anna, "Anna")
      world.people.rename(world.marco, "Marco")
      world.people.rename(giulia, "Giulia")
      world.people.rename(luca, "Luca")
      world.people.rename(nascosta, "Nonna Rosa")
      world.people.setHidden(nascosta, true)
      // Le scene partono da zero voci scritte: chi guarda i test delle azioni parte da qui.
      world.people.edits.clear()
    }
  }

  fun close() = scope.cancel()

  fun card(id: PersonId): PersonCard = runBlocking { world.people.person(id).first()!!.card }

  // --- Tutte le persone -------------------------------------------------------------------------------------------------------------

  fun list(): PeopleListUi = PeopleListController(world.people, scope).ui.value

  fun listSelecting(): PeopleListUi = PeopleListController(world.people, scope).also {
    it.startWith(world.anna)
    it.toggle(world.marco)
  }.ui.value

  fun listWorking(percent: Int? = 35) = PeopleListUi(PeopleListState.Working(percent))

  fun listEmpty() = PeopleListUi(PeopleListState.Empty)

  fun listWithMessage(message: PeopleMessage = PeopleMessage.NOT_ACTIVE) = list().copy(message = message)

  fun listMergeChoosePickName(): PeopleListUi {
    val controller = PeopleListController(world.people, scope)
    controller.startWith(world.anna)
    controller.toggle(world.marco)
    controller.mergeSelected()
    return controller.ui.value
  }

  /** Una proposta fra due persone **con un nome**: prima si sceglie quale tenere, e il foglio ha anche "No, sono diverse". */
  fun listProposalNames(): PeopleListUi {
    val controller = PeopleListController(world.people, scope)
    controller.openProposal(controller.ui.value.proposals.first())
    return controller.ui.value
  }

  /** Una proposta fra due gruppi senza nome: i due volti, "Si'" e "No". */
  fun listProposalConfirm(): PeopleListUi {
    val a = card(senzaNomeUno)
    val b = card(senzaNomeDue)
    return list().copy(merge = MergeUi(MergeSheetState.Confirm(listOf(a, b), null, MergeProposal(a, b))))
  }

  // --- Altri volti ---------------------------------------------------------------------------------------------------------------------

  fun others(): OthersUi = OthersController(world.people, world.personFaces, scope).ui.value

  fun othersSelecting(): OthersUi = OthersController(world.people, world.personFaces, scope).also {
    it.startWith(world.bruno)
    it.toggle(senzaNomeUno)
  }.ui.value

  fun othersForgetting(): OthersUi = OthersController(world.people, world.personFaces, scope).also {
    it.startWith(world.bruno)
    it.askForget()
  }.ui.value

  fun othersMerge(): OthersUi = OthersController(world.people, world.personFaces, scope).also {
    it.startWith(world.bruno)
    it.toggle(senzaNomeUno)
    it.mergeSelected()
  }.ui.value

  fun othersEmpty() = OthersUi(FaceReviewState.Grid(ReviewPurpose.OTHERS, null, emptyList(), emptySet()))

  // --- La pagina di una persona ----------------------------------------------------------------------------------------------------

  private fun page(id: PersonId): PersonPageController = PersonPageController(id, world.people, world.personFaces, scope)

  fun pageUi(id: PersonId = world.anna): PersonPageUi = page(id).ui.value

  fun pageUnnamed(): PersonPageUi = pageUi(world.bruno)

  fun pageHidden(): PersonPageUi = pageUi(nascosta)

  fun pageForgotten() = PersonPageUi(PersonPageState.Forgotten)

  fun pageNotFound() = PersonPageUi(PersonPageState.NotFound)

  fun pageLoading() = PersonPageUi(PersonPageState.Loading)

  fun pageNaming(invalid: Boolean = false): PersonPageUi =
    pageUnnamed().copy(naming = NamingUi(world.bruno, null, invalid = invalid))

  fun pageRenaming(): PersonPageUi = pageUi(world.anna).copy(naming = NamingUi(world.anna, "Anna"))

  fun pageForgetting(): PersonPageUi = pageUi(world.anna).copy(confirmForget = true)

  fun pageCovers(): PersonPageUi {
    val controller = page(world.anna)
    controller.openCovers()
    return controller.ui.value
  }

  fun pageMergeChoose(): PersonPageUi {
    val controller = page(world.anna)
    controller.startMerge()
    controller.toggleCandidate(giulia)
    return controller.ui.value
  }

  fun pageMergePickName(): PersonPageUi {
    val controller = page(world.anna)
    controller.startMerge()
    controller.toggleCandidate(world.marco)
    controller.continueFromChoose()
    return controller.ui.value
  }

  fun pageMergeConfirm(): PersonPageUi {
    val controller = page(world.bruno)
    controller.startMerge()
    controller.toggleCandidate(senzaNomeUno)
    controller.continueFromChoose()
    return controller.ui.value
  }

  fun pageProposal(): PersonPageUi {
    val controller = page(world.anna)
    val proposal: MergeProposal = (controller.ui.value.page as PersonPageState.Ready).proposals.first()
    controller.openProposal(proposal)
    return controller.ui.value
  }

  fun pageWithMessage(message: PeopleMessage = PeopleMessage.INVALID_NAME): PersonPageUi = pageUi(world.anna).copy(message = message)

  // --- Controlla le foto e Dividi ----------------------------------------------------------------------------------------------------

  private fun review(purpose: ReviewPurpose, id: PersonId = world.anna) = FaceReviewController(id, purpose, world.people, world.personFaces, scope)

  fun check(): FaceReviewUi = review(ReviewPurpose.CHECK).ui.value

  fun checkSelecting(): FaceReviewUi = review(ReviewPurpose.CHECK).also { c ->
    val faces = (c.ui.value.state as FaceReviewState.Grid).faces
    c.toggle(faces[0].face)
    c.toggle(faces[2].face)
  }.ui.value

  fun split(): FaceReviewUi = review(ReviewPurpose.SPLIT).ui.value

  fun splitSelecting(): FaceReviewUi = review(ReviewPurpose.SPLIT).also { c ->
    val faces = (c.ui.value.state as FaceReviewState.Grid).faces
    c.toggle(faces[1].face)
    c.toggle(faces[3].face)
  }.ui.value

  fun reviewApplying() = FaceReviewUi(FaceReviewState.Applying)

  fun reviewDone(changed: Int = 2) = FaceReviewUi(FaceReviewState.Done, changed = changed)

  fun reviewEmpty() = FaceReviewUi(FaceReviewState.Grid(ReviewPurpose.CHECK, world.anna, emptyList(), emptySet()))

  fun reviewWithMessage() = check().copy(message = PeopleMessage.NOT_ACTIVE)

  // --- Persone nascoste ------------------------------------------------------------------------------------------------------------

  fun hiddenReady() = HiddenPeopleState.Ready(listOf(card(nascosta)))

  fun hiddenMany() = HiddenPeopleState.Ready(listOf(card(nascosta), card(world.bruno), card(senzaNomeUno)))

  fun coverChoices(): CoverChoicesUi = pageCovers().covers!!

  fun mergeUi(sheet: MergeSheetState) = MergeUi(sheet)
}
