package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPlanner
import java.time.LocalDate

/**
 * Il generatore dei ricordi (docs/fase9-progetto.md §2.1 passo 4, D3, D5, D9, D11): **puro e deterministico**, con la tabella a colonne e il
 * suo indice per giorno (`CellTable.onDay`): ogni tipo cerca solo i giorni che gli servono, nessuna scansione della Libreria per tipo
 * (100 mila celle: meno di 50 ms oltre alla tabella). Da' gli stessi candidati, con le stesse chiavi, celle e priorita', del riferimento a forza
 * bruta (`ReferencePlanner`) su ogni ingresso: lo prova `PlannerParityTest`.
 *
 * I tre tipi di questo pacchetto sono "N anni fa oggi" ([OnThisDayPlanning]), viaggi ed eventi ([TripPlanning]) e il meglio del mese
 * ([MonthPlanning]). **Il generatore delle persone e' di P8** (`plan/people/`): e' un altro [MemoryPlanner] che da' **solo** candidati di
 * tipo [MemoryKind.PERSON] (con `MemoryQuiet` per le chiavi che non si propongono), e si innesta qui in [people]; di serie nessuno
 * ([NoMemoryPlanner]: i ricordi di persone tacciono). Dal suo risultato si tengono solo i candidati `PERSON`: un innesto non puo' aggiungere un
 * altro tipo.
 *
 * L'ordine del risultato e' quello del contratto: priorita' (anniversario, "anni fa" recente, persona, riscoperta, "anni fa" vecchio, mese),
 * poi il testo della chiave.
 */
class IndexedPlanner(private val people: MemoryPlanner = NoMemoryPlanner) : MemoryPlanner {

  override fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val out = ArrayList<MemoryCandidate>()
    out += OnThisDayPlanning.plan(input, today)
    out += TripPlanning.plan(input, today)
    out += people.plan(input, today).filter { it.key.kind == MemoryKind.PERSON }
    out += MonthPlanning.plan(input, today)
    return out.sortedWith(compareBy<MemoryCandidate> { it.priority }.thenBy { it.key.text })
  }
}
