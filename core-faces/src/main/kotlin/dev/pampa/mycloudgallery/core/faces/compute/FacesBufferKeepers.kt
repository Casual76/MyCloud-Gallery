package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlinx.coroutines.CancellationException

/**
 * IL BUFFER TIENE LE ANTEPRIME CHE I VOLTI CHIEDONO ANCORA (docs/fase8-progetto.md D17, §1.1 "BufferKeepers"). Il buffer di analisi
 * (`DiskAiBuffer`, 500 MB) butta un'anteprima appena vettori e testo sono fatti (`ComputeSession.pruneFinished`, a inizio tappa e a fine unita'):
 * attivando Persone dopo, o con il calcolo dei volti dopo quello AI, le anteprime andrebbero riscaricate o rifatte. `ComputeSession` chiede
 * a questo oggetto quali delle chiavi che sta per togliere servono ancora ai volti e quelle le lascia.
 *
 * Servono ai volti le chiavi **da analizzare** (non gli screenshot, i documenti, i ritagli: [FaceScopeRules]) che non hanno ancora un corpo
 * `faces` della ricetta corrente (o "non supportato") nell'archivio. Mai niente se Persone non e' attiva qui, se il dispositivo e' escluso o
 * non e' un indicizzatore (non calcola lui): il buffer si svuota come nella Fase 6. Un guasto qualunque vale "nessuno tiene niente": il
 * buffer non cresce oltre il suo tetto per colpa dei volti. Non apre mai il database: usa solo l'archivio gia' aperto di [archive].
 *
 * Le anteprime tenute le libera la tappa AI dopo, quando i volti hanno finito e [stillWanted] risponde vuoto.
 */
class FacesBufferKeepers(
  private val switch: FacesSwitch,
  private val requirements: FacesRequirements,
  private val lookup: FacesItemLookup,
  /** L'archivio della generazione corrente, se e' aperto; null = nessuno. */
  private val archive: suspend () -> FacesArchive?,
  private val recipe: Int = FacesComputeLimits.RECIPE,
) : BufferKeepers {

  override suspend fun stillWanted(keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val state = switch.currentState()
    if (state !is FacesState.On || state.here == HereState.EXCLUDED) return emptySet()
    return try {
      if (!requirements.indexers().thisDevice) return emptySet()
      val store = archive() ?: return emptySet()
      val asked = keys.toHashSet()
      val wanted = lookup.itemsOf(keys).filter(FaceScopeRules::analyzable).mapTo(LinkedHashSet()) { it.contentKey }.filter { it in asked }
      if (wanted.isEmpty()) emptySet() else wanted.toSet() - store.analyzed(wanted, recipe)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptySet()
    }
  }
}
