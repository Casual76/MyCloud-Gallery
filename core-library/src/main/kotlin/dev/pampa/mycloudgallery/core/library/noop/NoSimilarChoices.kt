package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.library.similar.SeparatedPairs
import dev.pampa.mycloudgallery.core.library.similar.SimilarChoices
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Fase 5A (P15): senza dati personali (nessuna area privata, prove senza sincronizzazione) l'utente non ha scelte salvate e quelle che
 * fa non si ricordano. Un "Separa" resta valido finche' il catalogo non rifa' il gruppo: usare il vero `SimilarCopiesChoices` di
 * core-personal in `:app`.
 */
object NoSimilarChoices : SimilarChoices {
  override suspend fun chosenCover(members: Collection<MediaId>): MediaId? = null

  override suspend fun separatedPairs(ids: Collection<MediaId>): SeparatedPairs = SeparatedPairs.NONE

  override suspend fun saveCover(chosen: MediaId, others: Collection<MediaId>) = Unit

  override suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>) = Unit
}
