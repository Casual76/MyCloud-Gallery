package dev.pampa.mycloudgallery.feature.people.noop

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops

/** Fase 8 (P0): nessuna azione (finche' :app non lega le schermate, P13). Non apre niente. */
object NoPeopleActions : PeopleActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) = Unit

  override fun openPerson(id: PersonId) = Unit

  override fun openAllPeople() = Unit

  override fun openOthers() = Unit

  override fun openActivation() = Unit

  override fun openPeopleSettings() = Unit

  override fun openDiagnostics() = Unit
}

/** Fase 8 (P0): nessuna miniatura (un segnaposto). Mai un ritaglio su disco. */
object NoPeopleCrops : PeopleCrops {
  override suspend fun crop(face: FaceLocator, sizePx: Int): android.graphics.Bitmap? = null
}
