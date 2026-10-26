package dev.pampa.mycloudgallery.core.search.noop

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fase 8 (P0, D26): nessuna persona. Il dizionario e' vuoto, nessun chip "Persona" nasce e un nome resta testo per il contenuto; un chip
 * arrivato da prima (la cronologia) non trova celle. E' il default dove Persone e' spenta, in attesa dell'avviso o esclusa (E-F37).
 */
object NoPeopleLookup : PeopleLookup {
  override val available: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun dictionary(): PeopleDictionary = PeopleDictionary.EMPTY

  override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = emptySet()
}
