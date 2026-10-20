package dev.pampa.mycloudgallery.feature.integrations.picker

import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.feature.integrations.provider.DeliveryPreparer
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.NoIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.NoPreparation

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

  /**
   * IN-10: perche' l'ultimo [unlock] non e' riuscito, se lo si sa (annullato dall'utente, telefono senza blocco schermo, altro). Null = riuscito, o
   * non si sa. Serve alla schermata per non dire "Non sono riuscito" a chi ha solo annullato, e per dire cosa fare a chi non ha una credenziale.
   */
  fun lastFailure(): UnlockFailure? = null
}

/** Perche' uno sblocco con la credenziale di sistema non e' riuscito (IN-10). Solo codici: nessun dato. */
enum class UnlockFailure {
  /** L'utente ha chiuso la schermata di sistema: niente da dire, il tasto resta. */
  CANCELLED,

  /** Il telefono non ha nessun blocco schermo (ne' biometria): non c'e' modo di sbloccare da qui. */
  NO_SCREEN_LOCK,

  /** Altro (troppi tentativi, schermata chiusa dal sistema, hardware che non risponde): si riprova. */
  OTHER,
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

  /**
   * Chi prepara le copie senza posizione ("Rimuovi la posizione", decisione del 20/10/2026): con l'impostazione accesa il selettore consegna solo
   * quelle. Di serie nessuna impostazione: si consegnano gli originali come prima.
   */
  @Volatile
  var preparer: DeliveryPreparer = NoPreparation
    private set

  /** Installa la Libreria, lo sblocco e la preparazione veri; `null` rimette il null-object. */
  fun install(library: IntegrationLibrary?, unlock: EntryUnlock?, preparer: DeliveryPreparer? = null) {
    this.library = library ?: NoIntegrationLibrary
    this.unlock = unlock ?: NoEntryUnlock
    this.preparer = preparer ?: NoPreparation
  }

  /** Rimette tutto ai null-object (azzeramento dell'app, test). */
  fun reset() = install(null, null)
}
