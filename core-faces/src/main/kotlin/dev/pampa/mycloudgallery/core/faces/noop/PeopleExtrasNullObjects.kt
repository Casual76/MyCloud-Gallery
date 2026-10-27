package dev.pampa.mycloudgallery.core.faces.noop

import dev.pampa.mycloudgallery.core.faces.contract.FaceChip
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.MediaFaces
import dev.pampa.mycloudgallery.core.faces.contract.PeopleArchiveSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFace
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// I null-object delle aggiunte di P9 (`contract/PeopleExtras.kt`): falliscono CHIUSI come quelli di P0. Finche' :app (P13) non lega i pezzi
// veri, nessuna persona nascosta, nessun volto nei media, nessuna copertina, nessun archivio.

object NoHiddenPeople : HiddenPeople {
  override fun hidden(): Flow<List<PersonCard>> = flowOf(emptyList())
}

/** Nessuna copertina e nessun ritaglio: il cancello non si apre mai. */
object NoFaceCropGate : FaceCropGate {
  override suspend fun mayShow(face: FaceLocator): Boolean = false
}

object NoMediaFaces : MediaFaces {
  override fun faces(media: MediaId): Flow<List<FaceChip>> = flowOf(emptyList())

  override suspend fun choices(): List<PersonCard> = emptyList()

  override suspend fun belongsTo(media: MediaId, face: FaceLocator): Boolean = false
}

object NoPersonFaces : PersonFaces {
  override suspend fun reviewFaces(person: PersonId): List<PersonFace> = emptyList()

  override suspend fun coverMedia(persons: Collection<PersonId>): Map<PersonId, MediaId> = emptyMap()
}

object NoPeopleArchiveSource : PeopleArchiveSource {
  override suspend fun current(): FacesArchive? = null
}

object NoPeopleCells : PeopleCells {
  override suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = emptyMap()

  override suspend fun keysOf(media: MediaId): Set<ContentKey> = emptySet()
}
