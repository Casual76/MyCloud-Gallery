package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import java.io.IOException

// Il registro locale in memoria per i test di P13, P14, P16 e :app. Mai nella release (tools/check.ps1).

/**
 * In memoria (test e fake). [failNextSave] simula un guasto del disco: la scrittura successiva lancia e non cambia nulla. [full] simula la memoria piena: ogni scrittura lancia
 * finche' non si rimette a falso. [failAfterSaves] la fa riempire dopo N salvataggi riusciti (per provare un secondo salvataggio che non riesce).
 */
class InMemoryPersonalLedger(initial: LedgerSnapshot = LedgerSnapshot.EMPTY) : PersonalLedger {
  @Volatile var snapshot: LedgerSnapshot = initial
    private set

  @Volatile var saves: Int = 0
    private set

  @Volatile var failNextSave: Boolean = false

  @Volatile var full: Boolean = false

  @Volatile var failAfterSaves: Int = Int.MAX_VALUE

  override suspend fun load(): LedgerSnapshot = snapshot

  override suspend fun save(snapshot: LedgerSnapshot) {
    if (failNextSave) {
      failNextSave = false
      throw IOException("disco di prova pieno")
    }
    if (full || saves >= failAfterSaves) throw IOException("memoria di prova piena")
    this.snapshot = snapshot
    saves++
  }
}
