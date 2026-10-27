package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.DateMediaCount
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import kotlin.coroutines.cancellation.CancellationException

// IL FILTRO "PERSONA" del motore (Fase 8, P10, D26, §2.7): un chip `Person` non si giudica sulla cella (`ChipMatching` lo esclude) ma sulle
// celle che la porta `PeopleLookup` da' per quelle persone. Lo condividono il motore vero e quello di riferimento, cosi' la parita' confronta i
// punteggi e non due letture diverse del chip. **Nascosti mai** (D28): le celle della porta passano per l'istantanea della Libreria (che non
// li ha) e poi per il filtro finale dei nascosti di `SearchCore.finish`; una cella fuori dall'istantanea non e' mai un candidato.

/**
 * Le celle che passano i chip "Persona" di una richiesta. Piu' chip si combinano in "e" (come tutti i filtri): una cella passa se sta, per ogni chip,
 * fra le celle "sicure" o fra quelle "forse"; e' **sicura** solo se sta fra le sicure di ogni chip, altrimenti e' "meno probabile" ([isSure]).
 */
internal class PeopleGate private constructor(private val likely: List<Set<MediaId>>, private val sure: List<Set<MediaId>>) {

  fun accepts(id: MediaId): Boolean = likely.all { id in it }

  fun isSure(id: MediaId): Boolean = sure.all { id in it }

  companion object {
    /**
     * Il filtro per i chip "Persona" di [chips], o null se non ce ne sono. Una porta che sbaglia o che dice che le persone non ci sono qui
     * (Persone spenta, in attesa dell'avviso, dispositivo escluso: E-F37) non da' celle: i risultati sono vuoti, mai quelli di altre persone.
     */
    suspend fun of(chips: List<SearchChip>, lookup: PeopleLookup): PeopleGate? {
      val persons = chips.filterIsInstance<SearchChip.Person>()
      if (persons.isEmpty()) return null
      val here = lookup.available.value
      val sure = ArrayList<Set<MediaId>>(persons.size)
      val likely = ArrayList<Set<MediaId>>(persons.size)
      for (chip in persons) {
        val s = if (here) guarded { lookup.cells(chip.option, includeLikely = false) } else emptySet()
        val l = if (here) guarded { lookup.cells(chip.option, includeLikely = true) } else emptySet()
        sure += s
        likely += s + l
      }
      return PeopleGate(likely, sure)
    }

    private suspend fun guarded(block: suspend () -> Set<MediaId>): Set<MediaId> = try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptySet()
    }
  }
}

/** Quante celle dell'istantanea (e' senza nascosti) coprirebbe una festa o un mese: serve solo a un nome che e' anche una data (E-F36). */
internal class SnapshotDateMedia(private val snapshot: TimelineSnapshot) : DateMediaCount {
  override fun count(chip: SearchChip): Int {
    var n = 0
    for (i in 0 until snapshot.size) if (ChipMatching.accepts(chip, snapshot, i)) n++
    return n
  }
}

/** Il dizionario con il peso delle date dell'istantanea; vuoto resta vuoto (nessun costo). */
internal fun PeopleDictionary.weighedBy(snapshot: TimelineSnapshot): PeopleDictionary =
  if (isEmpty) this else PeopleDictionary(entries, SnapshotDateMedia(snapshot))
