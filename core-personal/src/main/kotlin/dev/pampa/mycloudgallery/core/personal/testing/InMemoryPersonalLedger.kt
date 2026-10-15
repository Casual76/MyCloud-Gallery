package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import java.io.IOException

// Il registro locale in memoria per i test di P13, P14, P16 e :app. Mai nella release (tools/check.ps1).

/** In memoria (test e fake). [failNextSave] simula un guasto del disco: la scrittura successiva lancia e non cambia nulla. */
class InMemoryPersonalLedger(initial: LedgerSnapshot = LedgerSnapshot.EMPTY) : PersonalLedger {
  @Volatile var snapshot: LedgerSnapshot = initial
    private set

  @Volatile var saves: Int = 0
    private set

  @Volatile var failNextSave: Boolean = false

  override suspend fun load(): LedgerSnapshot = snapshot

  override suspend fun save(snapshot: LedgerSnapshot) {
    if (failNextSave) {
      failNextSave = false
      throw IOException("disco di prova pieno")
    }
    this.snapshot = snapshot
    saves++
  }
}
