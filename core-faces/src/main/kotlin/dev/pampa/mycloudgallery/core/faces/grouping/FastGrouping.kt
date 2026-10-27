package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupingEngine
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOutcome
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource

/**
 * IL NUCLEO VERO DEL RAGGRUPPAMENTO (docs/fase8-progetto.md §2.5, D20-D23; riga P5 del §4.4). Puro e deterministico: stessi ingressi,
 * stessi gruppi e stessi id su ogni macchina (ogni spareggio per riferimento o per id; niente orologio, niente casualita' fuori da
 * [PersonIdSource]). Le regole e l'ordine dei passi sono quelli di `testing/ReferenceGrouping` (con le regole F1-F6 portate in tutti e
 * due, `docs/decisioni.md` "Fase 8 P5"); la differenza e' il costo:
 * - i coseni a blocchi vettorizzati con gli stessi bit del riferimento ([FaceMatrix], [ColumnBlocks]): assegnazioni, espulsioni, esemplari
 *   e unioni identiche bit per bit;
 * - il legame medio delle nascite sulle sole coppie che possono unirsi e con le somme dei vettori ([AverageLinkage]): nessuna matrice
 *   n x n; oltre [BirthGroups.EXACT_POOL] volti le nascite vanno a lotti ([BirthGroups]);
 * - le correzioni indicizzate per foto ([CorrectionIndex]) e i volti della stessa foto per indice ([FaceTable]);
 * - l'abbinamento del ricalcolo per componenti ([OptimalMatching]).
 *
 * I passi:
 * - [incremental]: volti non piu' correnti, correzioni, assegnazione, nascite se i non assegnati CORE sono almeno `birthsEvery` in piu'
 *   dell'ultima volta;
 * - [provisional]: volti non piu' correnti, correzioni, assegnazione (mai nascite ne' unioni; lo stato non avanza di numero);
 * - [nightly]: contenuti spariti, volti non piu' correnti, correzioni, espulsioni, esemplari, unioni e proposte, esemplari, assegnazione,
 *   nascite, esemplari;
 * - [recluster]: nascite da capo, abbinamento alle persone vecchie, correzioni, esemplari, assegnazione, esemplari.
 */
class FastGrouping : GroupingEngine {

  override fun incremental(input: GroupingInput, ids: PersonIdSource): GroupingOutcome = GroupingPass(input, ids).run {
    dropStale()
    applyCorrections()
    assignUnassigned()
    if (unassignedCore() - input.state.unassignedAtBirths >= input.params.birthsEvery) births()
    finish(publish = true)
  }

  override fun provisional(input: GroupingInput): GroupingOutcome = GroupingPass(input, NO_IDS).run {
    dropStale()
    applyCorrections()
    assignUnassigned()
    finish(publish = false)
  }

  override fun nightly(input: GroupingInput, ids: PersonIdSource): GroupingOutcome = GroupingPass(input, ids).run {
    removeGone(input.gone)
    dropStale()
    applyCorrections()
    eject()
    refreshExemplars()
    merges()
    refreshExemplars()
    assignUnassigned()
    births()
    refreshExemplars()
    finish(publish = true)
  }

  override fun recluster(input: GroupingInput, previous: GroupingState, ids: PersonIdSource): GroupingOutcome =
    GroupingPass(input.copy(state = previous), ids).run {
      rebuildFromScratch()
      applyCorrections()
      refreshExemplars()
      assignUnassigned()
      refreshExemplars()
      finish(publish = true)
    }

  private companion object {
    /** Il provvisorio non fa nascere persone: un id chiesto qui e' un errore. */
    val NO_IDS = PersonIdSource { error("Il raggruppamento provvisorio non crea persone.") }
  }
}
