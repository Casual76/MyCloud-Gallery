package dev.pampa.mycloudgallery.feature.integrations.picker

import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.NoIntegrationLibrary

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, E-X7): le porte del selettore che `IntegrationPorts` (P0-B) non ha: la Libreria da
// mostrare e lo sblocco. Stanno qui, nel perimetro di P10, perche' sono nate con il selettore; `:app` le installa insieme alle altre (P14):
//
//   val documents = LibraryMediaDocuments(library, labels)                       // il provider E chi consegna i solo-NAS
//   IntegrationPorts.install(entryGate, documents)
//   PickerPorts.install(library, unlock)
//
// Come per `IntegrationPorts`, finche' nessuno le installa valgono i null-object, che falliscono chiusi: nessuna Libreria, nessuno sblocco.

/**
 * Lo sblocco dell'app per un'activity d'ingresso (E-X7): `:app` lo lega al blocco esistente (`AppLockController` +
 * `BiometricPromptAuthenticator`, la credenziale di sistema). Il selettore lo chiama quando [dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate]
 * risponde `NEEDS_UNLOCK`; dopo un `true` il cancello deve rispondere `ALLOWED`.
 *
 * Serve una `FragmentActivity` (la schermata di sistema delle credenziali la vuole): per questo `PickerActivity` la estende, come la `MainActivity`.
 * Annullabile: se l'activity si chiude la coroutine si annulla.
 */
fun interface EntryUnlock {
  suspend fun unlock(activity: FragmentActivity): Boolean
}

/** Nessuno sblocco: non si sblocca mai (l'app resta chiusa). */
object NoEntryUnlock : EntryUnlock {
  override suspend fun unlock(activity: FragmentActivity): Boolean = false
}

/** Il punto in cui `:app` installa le porte del selettore (P14). */
object PickerPorts {

  @Volatile
  var library: IntegrationLibrary = NoIntegrationLibrary
    private set

  @Volatile
  var unlock: EntryUnlock = NoEntryUnlock
    private set

  /** Installa la Libreria e lo sblocco veri; `null` rimette il null-object. */
  fun install(library: IntegrationLibrary?, unlock: EntryUnlock?) {
    this.library = library ?: NoIntegrationLibrary
    this.unlock = unlock ?: NoEntryUnlock
  }

  /** Rimette tutto ai null-object (azzeramento dell'app, test). */
  fun reset() = install(null, null)
}
