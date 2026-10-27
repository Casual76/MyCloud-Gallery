package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead

/**
 * Lo scaffale in memoria (Fase 9, P0, D10, §5.2): un "file" che c'e' o non c'e', con la **morte simulata** fra scrittura e rinomina
 * ([dieDuringNextWrite]: la scrittura lancia e il file di prima resta intero, come con il temporaneo e la rinomina atomica) e il file
 * **rovinato** ([corrupt]: la prossima lettura risponde [ShelfRead.Corrupt], finche' una scrittura non lo sostituisce). Passa
 * `ShelfStoreContract`, che `FileShelfStore` di P6 passa su file veri (troncati e rovinati).
 */
class InMemoryShelfStore(initial: ShelfFile? = null) : MemoryShelfStore {
  private var file: ShelfFile? = initial
  private var corrupted = false
  private var dieNext = false

  /** Quante scritture riuscite (un test controlla che un giro senza novita' non riscriva). */
  var writes: Int = 0
    private set

  fun dieDuringNextWrite() {
    dieNext = true
  }

  fun corrupt() {
    corrupted = true
  }

  /** Il contenuto di adesso, senza passare dalla lettura (per le prove). */
  fun peek(): ShelfFile? = if (corrupted) null else file

  override suspend fun read(): ShelfRead = when {
    corrupted -> ShelfRead.Corrupt
    file == null -> ShelfRead.Missing
    else -> ShelfRead.Ready(file!!)
  }

  override suspend fun write(file: ShelfFile) {
    if (dieNext) {
      dieNext = false
      throw IllegalStateException("morte simulata fra la scrittura del temporaneo e la rinomina")
    }
    this.file = file
    corrupted = false
    writes++
  }

  override suspend fun clear() {
    file = null
    corrupted = false
  }
}
