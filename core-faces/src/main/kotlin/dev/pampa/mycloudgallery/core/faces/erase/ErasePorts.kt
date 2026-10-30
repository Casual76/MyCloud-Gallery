package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.store.ExistingStore
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.store.LocalErase
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration

// LE PORTE DI "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §2.8, D29-D31, §1.4). Oltre a quelle del contratto di P0 (`FacesEraseOps`,
// `SmartAlbumsPeople`, `SearchHistoryPurge`, `FacesClock`, `FacesLocalStore`) e di core-personal (`FacesPersonalSettings`, `PersonalHeadsView`):
// chi sono, il giro dei dati personali che porta la voce `off` agli altri dispositivi, il lavoro dei volti da fermare, i resti locali oltre al
// database (cache, modelli estratti, segni dei lavori) e i dati locali dei volti. Le lega `:app` (P13); i null-object sono qui.

/** Chi sono per la cancellazione: l'id di questo dispositivo e il registro di famiglia accettato (null = famiglia non pronta). */
interface FacesEraseIdentity {
  val deviceId: String

  fun registry(): RegistryState?
}

/** L'esito del giro dei dati personali chiesto dal passo 7 (§2.8, "Annuncio"). */
sealed interface AnnounceResult {
  /** Il giro e' finito per intero (lettura e, se si poteva, pubblicazione). */
  data object Completed : AnnounceResult

  /** Il giro non e' finito: il motivo (NAS, congelamento, salto dell'ora, sola lettura, fiducia). */
  data class Waiting(val reason: EraseWait) : AnnounceResult
}

/**
 * Il passo 7 (§2.8): un giro dei dati personali che pubblica la voce `off|G`. `:app` lo lega a `PersonalSync.sync` (con il gancio di D30) e, se il
 * giro finisce per intero, a `FacesSwitch.onPersonalRoundCompleted` (lo stato torna fresco). Chi cancella non si fida dell'esito: guarda la propria
 * testa nella `PersonalHeadsView`.
 */
fun interface EraseAnnouncer {
  suspend fun announce(budget: Duration): AnnounceResult
}

/** Il passo 2 (§2.8): i lavori dei volti annullati e i motori rilasciati (`:app`: WorkManager e il grafo dei volti). Al meglio. */
fun interface EraseStopWork {
  suspend fun stop()

  companion object {
    /** Nessun lavoro da fermare (il calcolo e la pubblicazione si fermano da soli sullo stato `Erasing`). */
    val NONE: EraseStopWork = EraseStopWork { }
  }
}

/**
 * I resti locali dei volti oltre al database e alla chiave (passo 6 del §2.8): le cache in memoria (copertine ritagliate, viste delle persone), i
 * modelli estratti in `models/<sha256>` e i segni dei lavori dei volti. Al meglio: il database e la chiave sono cio' che conta (la chiave distrutta
 * rende illeggibile ogni resto); un modello che non si toglie si ritenta alla cancellazione dopo.
 */
interface FacesLocalLeftovers {
  suspend fun clearMemory()

  /** Vero se non resta nessun modello estratto dei volti. */
  suspend fun removeModels(): Boolean

  /** Vero se non resta nessun segno dei lavori dei volti. */
  suspend fun clearWorkMarks(): Boolean

  companion object {
    val NONE: FacesLocalLeftovers = object : FacesLocalLeftovers {
      override suspend fun clearMemory() = Unit

      override suspend fun removeModels(): Boolean = true

      override suspend fun clearWorkMarks(): Boolean = true
    }
  }
}

/**
 * I dati dei volti di QUESTO dispositivo come li vede la cancellazione: quali generazioni hanno qualcosa sul disco, le voci `people` di una
 * generazione (per i nomi da togliere dalla cronologia, prima di cancellare) e la cancellazione con la chiave per prima. **Non crea mai** un
 * negozio nuovo: leggere una generazione che non ha file non la fa nascere.
 */
interface FacesLocalData {
  suspend fun generationsOnDisk(): List<FaceGeneration>

  suspend fun hasData(generation: FaceGeneration): Boolean

  /** Le voci `people` di [generation]; vuoto se non ci sono dati; null se adesso non si leggono (Keystore che non risponde). */
  suspend fun people(generation: FaceGeneration): Map<PeopleKey, PeopleRecord>?

  /** La chiave del Keystore per prima, poi `faces-<G>.db` con i compagni. Ripetibile. */
  suspend fun erase(generation: FaceGeneration): LocalErase
}

/** I dati locali veri: il negozio di P6 (`FacesStores`, l'unica porta del database e della chiave dei volti). */
class StoresLocalData(private val stores: FacesStores) : FacesLocalData {
  override suspend fun generationsOnDisk(): List<FaceGeneration> = stores.generationsOnDisk()

  override suspend fun hasData(generation: FaceGeneration): Boolean = stores.hasFiles(generation)

  override suspend fun people(generation: FaceGeneration): Map<PeopleKey, PeopleRecord>? {
    // Nessun file = nessun dato: si risponde senza aprire (aprire creerebbe una chiave e un database nuovi).
    if (!stores.hasFiles(generation)) return emptyMap()
    // I6: si apre solo cio' che c'e' e si legge. Una chiave gia' distrutta (morte fra la chiave e i file) vale "nessun nome": niente si rifa', niente
    // `FACES_KEY_LOST`; i file li toglie la cancellazione che segue.
    return when (val opened = stores.openExisting(generation)) {
      is ExistingStore.Ready -> opened.store.archive.people()
      ExistingStore.Unreadable -> emptyMap()
      ExistingStore.Unknown -> null
    }
  }

  override suspend fun erase(generation: FaceGeneration): LocalErase = stores.eraseLocal(generation)

  override fun toString(): String = "StoresLocalData"
}
