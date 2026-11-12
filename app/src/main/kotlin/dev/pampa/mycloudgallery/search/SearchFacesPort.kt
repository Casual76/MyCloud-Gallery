package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.feature.library.collections.CollectionFaceCrops
import dev.pampa.mycloudgallery.feature.search.contract.SearchFace
import dev.pampa.mycloudgallery.feature.search.contract.SearchFaces
import kotlinx.coroutines.flow.Flow

/**
 * I2 (fase I): il volto di una persona per l'anteprima di un suggerimento della ricerca. CG-1 (giro 1): **la stessa porta e lo stesso segno** dei volti
 * delle Raccolte, non una copia: il ritaglio viene da [crops] (`AppCollectionFaceCrops`, CU-19: il cancello dei media mostrabili di feature-people, le
 * foto d'origine, la cache in memoria, mai su disco) e le revoche sono le sue ([CollectionFaceCrops.revocations]: cancellazione locale, Persone spenta o
 * rimontata, cache dei ritagli svuotata). Qui si sceglie soltanto **quale** volto ([cover]: quello di copertina, solo per le persone con un nome e non
 * nascoste) e si scarta un ritaglio senza foto d'origine (CS-11: provato in `AppSearchFacesTest`).
 */
class AppSearchFaces(private val cover: (PersonId) -> FaceLocator?, private val crops: CollectionFaceCrops) : SearchFaces {
  override suspend fun face(person: PersonId, sizePx: Int): SearchFace? {
    val face = cover(person) ?: return null
    val crop = crops.crop(face, sizePx) ?: return null
    if (crop.origins.isEmpty()) return null
    return SearchFace(crop.bitmap, crop.origins)
  }

  override val revocations: Flow<Any?> get() = crops.revocations

  override fun toString(): String = "AppSearchFaces"

  companion object {
    /** Sulle persone montate: il volto di copertina delle persone con un nome e non nascoste (l'elenco `named`); prima del montaggio nessuno. */
    fun of(graph: FacesGraph, crops: CollectionFaceCrops): AppSearchFaces =
      AppSearchFaces({ person -> graph.live.value?.people?.list?.value?.named?.firstOrNull { it.id == person }?.cover }, crops)
  }
}
