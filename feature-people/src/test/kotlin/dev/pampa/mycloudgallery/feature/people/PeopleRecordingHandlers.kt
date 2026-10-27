package dev.pampa.mycloudgallery.feature.people

import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleHandlers
import dev.pampa.mycloudgallery.feature.people.list.OthersHandlers
import dev.pampa.mycloudgallery.feature.people.list.PeopleListHandlers
import dev.pampa.mycloudgallery.feature.people.page.PersonPageHandlers
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewHandlers
import dev.pampa.mycloudgallery.feature.people.sheets.MergeHandlers

// I gestori dei test delle schermate (Fase 8, P9): ricordano cosa e' stato chiesto. Le schermate non fanno niente da sole, quindi cio' che hanno
// chiesto e' tutto cio' che c'e' da provare.

/** I passi dell'unione, ricordati (la parte comune dei gestori che la usano). */
internal class MergeLog {
  val toggled = mutableListOf<PersonId>()
  val picked = mutableListOf<PersonId>()
  var continuedFromChoose = 0
  var continuedFromName = 0
  var confirmed = 0
  var rejected = 0
  var dismissed = 0
}

/** La parte dell'unione, comune a tutti i gestori che la usano. */
internal abstract class MergeRecorder(val log: MergeLog = MergeLog()) : MergeHandlers {
  override fun onToggleCandidate(id: PersonId) {
    log.toggled += id
  }

  override fun onContinueFromChoose() {
    log.continuedFromChoose++
  }

  override fun onPick(id: PersonId) {
    log.picked += id
  }

  override fun onContinueFromName() {
    log.continuedFromName++
  }

  override fun onConfirmMerge() {
    log.confirmed++
  }

  override fun onRejectProposal() {
    log.rejected++
  }

  override fun onDismissMerge() {
    log.dismissed++
  }
}

internal class RecordingList : MergeRecorder(), PeopleListHandlers {
  val opened = mutableListOf<PersonId>()
  val started = mutableListOf<PersonId>()
  val toggled = mutableListOf<PersonId>()
  val selecting = mutableListOf<Boolean>()
  val proposals = mutableListOf<MergeProposal>()
  var others = 0
  var hidden = 0
  var mergeSelected = 0
  var backs = 0
  var messageDismissed = 0

  override fun onBack() {
    backs++
  }

  override fun onOpenPerson(id: PersonId) {
    opened += id
  }

  override fun onOpenOthers() {
    others++
  }

  override fun onOpenHidden() {
    hidden++
  }

  override fun onSetSelecting(on: Boolean) {
    selecting += on
  }

  override fun onStartWith(id: PersonId) {
    started += id
  }

  override fun onToggle(id: PersonId) {
    toggled += id
  }

  override fun onMergeSelected() {
    mergeSelected++
  }

  override fun onOpenProposal(proposal: MergeProposal) {
    proposals += proposal
  }

  override fun onDismissMessage() {
    messageDismissed++
  }
}

internal class RecordingOthers : MergeRecorder(), OthersHandlers {
  var backs = 0
  var messageDismissed = 0

  override fun onBack() {
    backs++
  }

  override fun onDismissMessage() {
    messageDismissed++
  }

  val opened = mutableListOf<PersonId>()
  val started = mutableListOf<PersonId>()
  val toggled = mutableListOf<PersonId>()
  val selecting = mutableListOf<Boolean>()
  var mergeSelected = 0
  var askedForget = 0
  var confirmedForget = 0
  var dismissedForget = 0

  override fun onOpenPerson(id: PersonId) {
    opened += id
  }

  override fun onSetSelecting(on: Boolean) {
    selecting += on
  }

  override fun onStartWith(id: PersonId) {
    started += id
  }

  override fun onToggle(id: PersonId) {
    toggled += id
  }

  override fun onMergeSelected() {
    mergeSelected++
  }

  override fun onAskForget() {
    askedForget++
  }

  override fun onConfirmForget() {
    confirmedForget++
  }

  override fun onDismissForget() {
    dismissedForget++
  }
}

internal class RecordingPage : MergeRecorder(), PersonPageHandlers {
  var messageDismissed = 0

  override fun onDismissMessage() {
    messageDismissed++
  }

  val actions = mutableListOf<PersonAction>()
  val photos = mutableListOf<MediaId>()
  val proposals = mutableListOf<MergeProposal>()
  val names = mutableListOf<String>()
  val covers = mutableListOf<FaceLocator?>()
  var removedName = 0
  var dismissedName = 0
  var confirmedForget = 0
  var dismissedForget = 0
  var dismissedCovers = 0
  var backs = 0

  override fun onBack() {
    backs++
  }

  override fun onAction(action: PersonAction) {
    actions += action
  }

  override fun onOpenPhoto(media: MediaId) {
    photos += media
  }

  override fun onOpenProposal(proposal: MergeProposal) {
    proposals += proposal
  }

  override fun onSaveName(text: String) {
    names += text
  }

  override fun onRemoveName() {
    removedName++
  }

  override fun onDismissName() {
    dismissedName++
  }

  override fun onConfirmForget() {
    confirmedForget++
  }

  override fun onDismissForget() {
    dismissedForget++
  }

  override fun onPickCover(face: FaceLocator?) {
    covers += face
  }

  override fun onDismissCovers() {
    dismissedCovers++
  }
}

internal class RecordingReview : FaceReviewHandlers {
  val toggled = mutableListOf<FaceLocator>()
  val rejectedOne = mutableListOf<FaceLocator>()
  var rejected = 0
  var ignored = 0
  var split = 0
  var backs = 0

  override fun onBack() {
    backs++
  }

  override fun onToggle(face: FaceLocator) {
    toggled += face
  }

  override fun onReject() {
    rejected++
  }

  override fun onRejectOne(face: FaceLocator) {
    rejectedOne += face
  }

  override fun onIgnore() {
    ignored++
  }

  override fun onSplit() {
    split++
  }

  override fun onDismissMessage() = Unit
}

internal class RecordingHidden : HiddenPeopleHandlers {
  val shown = mutableListOf<PersonId>()
  var backs = 0
  var noticeDismissed = 0

  override fun onBack() {
    backs++
  }

  override fun onShow(id: PersonId) {
    shown += id
  }

  override fun onDismissNotice() {
    noticeDismissed++
  }
}
