package dev.pampa.mycloudgallery.feature.viewer.testing

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceAction
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPersonChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fase 8 (P0): le persone finte del viewer, per i test della riga "Persone" e del foglio "Chi e'?" (P9). I chip per media si impostano con
 * [show]; le azioni si registrano in [actions] e, se [applyActions] e' vero, cambiano i chip come farebbe `People` (un "E' Marco" mette il
 * nome, "Non e'" e "Ignora" tolgono il chip). Nessuna copertina: [crop] restituisce null (il chip mostra il segnaposto).
 */
class FakeViewerPeople(private val applyActions: Boolean = true) : ViewerPeople {
  private val chips = MutableStateFlow<Map<MediaId, List<ViewerFaceChip>>>(emptyMap())
  private val names = LinkedHashMap<PersonId, String>()

  val actions: MutableList<Triple<MediaId, ViewerFaceRef, ViewerFaceAction>> = mutableListOf()
  val opened: MutableList<PersonId> = mutableListOf()

  fun show(media: MediaId, faces: List<ViewerFaceChip>) {
    chips.value = chips.value + (media to faces)
    faces.filterIsInstance<ViewerFaceChip.Named>().forEach { names[it.person] = it.name }
  }

  fun person(id: PersonId, name: String): FakeViewerPeople {
    names[id] = name
    return this
  }

  override fun faces(media: MediaId): Flow<List<ViewerFaceChip>> = chips.map { it[media].orEmpty() }

  override suspend fun crop(media: MediaId, face: ViewerFaceRef, sizePx: Int): Bitmap? = null

  override suspend fun choices(media: MediaId, face: ViewerFaceRef): List<ViewerPersonChoice> = names.map { ViewerPersonChoice(it.key, it.value) }

  override suspend fun act(media: MediaId, face: ViewerFaceRef, action: ViewerFaceAction): ViewerFaceOutcome {
    actions += Triple(media, face, action)
    if (action is ViewerFaceAction.IsNew && (action.name.isBlank() || action.name.length > MAX_NAME)) return ViewerFaceOutcome.INVALID_NAME
    val current = chips.value[media].orEmpty()
    if (current.none { refOf(it) == face }) return ViewerFaceOutcome.NOT_FOUND
    if (!applyActions) return ViewerFaceOutcome.DONE
    val next = current.mapNotNull { chip ->
      if (refOf(chip) != face) return@mapNotNull chip
      when (action) {
        is ViewerFaceAction.Is -> ViewerFaceChip.Named(face, action.person, names[action.person] ?: return ViewerFaceOutcome.NOT_FOUND)
        is ViewerFaceAction.IsNew -> PersonId.random().let { id -> names[id] = action.name.trim(); ViewerFaceChip.Named(face, id, action.name.trim()) }
        is ViewerFaceAction.IsNot -> ViewerFaceChip.Unnamed(face, null)
        ViewerFaceAction.Ignore -> null
      }
    }
    chips.value = chips.value + (media to next)
    return ViewerFaceOutcome.DONE
  }

  override fun openPerson(person: PersonId) {
    opened += person
  }

  private fun refOf(chip: ViewerFaceChip): ViewerFaceRef? = when (chip) {
    is ViewerFaceChip.Named -> chip.face
    is ViewerFaceChip.Unnamed -> chip.face
    is ViewerFaceChip.Unrecognizable -> null
  }

  private companion object {
    const val MAX_NAME = 64
  }
}
