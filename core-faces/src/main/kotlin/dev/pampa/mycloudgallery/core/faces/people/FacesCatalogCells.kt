package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * [PeopleCells] sopra il catalogo dei volti che :app lega comunque (docs/fase8-progetto.md §1.5: nessuna query nuova sul catalogo). Per
 * [FacesCatalog.mediaWith] vale la convenzione di `FacesCatalog.cellKeys`: risponde con le **celle** che contengono quei contenuti (per un
 * membro di una raffica o di una coppia RAW+JPEG, la copertina del gruppo), non con i membri: una persona compare nella cella che li
 * contiene (D26). Chi lega il catalogo vero (P13) la rispetta; una cella che la Libreria non ha non si mostra comunque (`PeopleVisibility`).
 */
class FacesCatalogCells(private val catalog: FacesCatalog) : PeopleCells {
  override suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = if (keys.isEmpty()) emptyMap() else catalog.mediaWith(keys)

  override suspend fun keysOf(media: MediaId): Set<ContentKey> = catalog.cellKeys(listOf(media))[media].orEmpty()
}
