package dev.pampa.mycloudgallery.core.memories.contract

// LO SCAFFALE DEI RICORDI (docs/fase9-progetto.md §1.2 "Scaffale", §2.3, D9, D10, E-MEM5-E-MEM7; modello-dati.md §16). Contratto di P0. Lo
// scaffale e' un **file locale** (`noBackupFilesDir/memories/shelf.bin`, mai sul NAS, mai nei backup): scrittura atomica con versione e CRC
// (temporaneo, `fsync`, rinomina, come `StateFiles`), al piu' 60 ricordi e 30 giorni. Un file rovinato vale "scaffale vuoto" e si rigenera
// (`MEMORY_SHELF_RESET`), mai un crollo. L'implementazione vera (`FileShelfStore`, codec del file) e' di P6 (shelf/); il riferimento e'
// `InMemoryShelfStore` (testing/, con la morte simulata fra scrittura e rinomina) e la suite `ShelfStoreContract`.

/**
 * Il contenuto dello scaffale (D9, D10): i ricordi pubblicati (dal piu' recente), il giorno (`aaaammgg`) dell'ultima pubblicazione per tipo
 * (le regole "al piu' uno a settimana" di D3) e dell'ultima riscoperta di un viaggio, il giorno dell'ultimo giro riuscito.
 */
data class ShelfFile(
  val memories: List<Memory>,
  val lastPublished: Map<MemoryKind, Int> = emptyMap(),
  val lastRediscovery: Int? = null,
  val lastRunDay: Int = 0,
) {
  init {
    require(memories.size <= MemoryRules.SHELF_MAX) { "Al piu' ${MemoryRules.SHELF_MAX} ricordi." }
    require(memories.map { it.id }.distinct().size == memories.size) { "Un ricordo compare una volta sola." }
  }

  override fun toString(): String = "ShelfFile(${memories.size})"

  companion object {
    /** La versione del formato del file (P6 la scrive in testa, con il CRC). */
    const val VERSION: Int = 1

    val EMPTY: ShelfFile = ShelfFile(emptyList())
  }
}

/** Cosa si e' letto (D10): nessun file (il primo giro), un file rovinato (si rigenera e lo dice la Diagnostica), uno buono. */
sealed interface ShelfRead {
  data object Missing : ShelfRead

  data object Corrupt : ShelfRead

  data class Ready(val file: ShelfFile) : ShelfRead
}

/**
 * Il deposito dello scaffale (D10). [write] e' **atomica**: chi legge dopo una morte del processo a meta' scrittura trova il file di prima,
 * intero, o quello nuovo, intero; mai uno a meta' (`ShelfStoreContract`). [clear] lo toglie (azzeramento, D37).
 */
interface MemoryShelfStore {
  suspend fun read(): ShelfRead

  suspend fun write(file: ShelfFile)

  suspend fun clear()
}
