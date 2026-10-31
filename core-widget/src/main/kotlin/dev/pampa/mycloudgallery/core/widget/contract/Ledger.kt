package dev.pampa.mycloudgallery.core.widget.contract

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId

// IL REGISTRO E IL RIDISEGNO (docs/fase10-progetto.md §1.2 "Registro", "Ridisegno", D5, D9, D42). Contratti di P0; `FileWidgetLedger` (file
// atomico in `noBackupFilesDir/widget/`) e' di P2, il `WidgetRefresher` vero (Glance) di P3.

/** Che cosa mostra una cella registrata: il media e, per la copertina di un ricordo, il ricordo (il tocco apre la sua pagina, D9). */
data class WidgetCellRef(val media: MediaId, val memory: MemoryId?) {
  override fun toString(): String = "WidgetCellRef(ricordo=${memory != null})"
}

/**
 * Il registro locale di cio' che ogni widget mostra (D9): serve al tocco (la cella esiste?) e allo svuotamento (D5: quali widget mostrano un
 * media appena nascosto). [record] si chiama **prima** di consegnare il disegno (§2.1, passo 7). Un file rovinato = registro vuoto e ridisegno,
 * mai un crollo; una morte fra scrittura e rinomina lascia il registro di prima (§4.3 "Stabilita'").
 */
interface WidgetLedger {
  /** Registra [plan] per il suo slot (sostituisce il precedente). Un piano con un'epoca piu' vecchia di quella registrata si ignora. */
  suspend fun record(plan: WidgetPlan)

  /** La cella [index] di [slot] nel piano registrato, o null (slot sconosciuto, indice fuori, messaggio senza celle). */
  suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef?

  /** I widget che mostrano almeno uno di [ids] (D5: si svuotano prima del ridisegno). */
  suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot>

  /** I widget che mostrano il ricordo [id] ("Non mostrare", "Mai nei ricordi", ricordo potato: D5, punto 4). */
  suspend fun showingMemory(id: MemoryId): Set<WidgetSlot>

  /** Gli slot registrati. */
  suspend fun slots(): Set<WidgetSlot>

  /** Il widget [slot] e' stato tolto dalla Home (`onDeleted`). */
  suspend fun forget(slot: WidgetSlot)

  /** Azzeramento (D42): il registro sparisce. */
  suspend fun clear()
}

/**
 * Chi chiede un ridisegno (§2.1 "Quando si ridisegna"). Contratto qui, implementazione in feature-widget (P3); il null-object non fa niente
 * (nessun widget). Le chiamate non aspettano il disegno, tranne [blankAll] (l'azzeramento aspetta che i widget siano vuoti **prima** di
 * cancellare, D42).
 */
interface WidgetRefresher {
  /** Nascosti su questo dispositivo o arrivati da un altro (giro dei dati personali): svuota subito i widget che li mostrano, poi ridisegna. */
  fun hiddenChanged(ids: Set<MediaId>)

  /** Un ricordo scartato, escluso con "Mai" o potato dallo scaffale: svuota i widget che lo mostrano, poi ridisegna (E-W19). */
  fun memoryDropped(id: MemoryId)

  /** Il ricordo del giorno e' cambiato (generazione notturna o passo leggero). */
  fun shelfChanged()

  /** Tema, accento o modalita' scura cambiati (E-W10). */
  fun appearanceChanged()

  /** L'app e' andata in secondo piano: dopo 2 s di calma, al piu' una volta ogni 15 minuti ([WidgetScheduleRules]). */
  fun appBackgrounded()

  /** Il blocco dell'app si e' acceso o spento (Q3 (c), E-W6). */
  fun lockChanged()

  /** Tutti i widget a [reason] **senza immagini**, e il registro dimentica ogni cella (D42, E-W9). Ritorna quando il disegno e' consegnato. */
  suspend fun blankAll(reason: WidgetEmpty)
}
