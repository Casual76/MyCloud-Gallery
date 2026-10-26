package dev.pampa.mycloudgallery.feature.integrations.receive

import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.noop.NoIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.picker.UnlockFailure

// Fase 5B (P11, docs/fase5-progetto.md §1.1, §2.7, D24, C17, E-X7, E-X8): le PORTE di `ShareReceiverActivity` che `IntegrationPorts` (P0-B) non ha. La
// activity la crea il sistema (un'altra app condivide un file), quindi non si inietta dal costruttore: `:app` le installa una volta all'avvio (P14),
//
//   val stager = IncomingStager(File(noBackupFilesDir, "incoming"))
//   ReceivePorts.install(DefaultIncomingUploads(stager, queue, clock), destinations, unlock)
//   stager.sweep(keep = <gli stage_id degli incoming_item aperti>)
//
// e finche' nessuno le installa valgono i null-object, che falliscono chiusi: niente si copia in coda, nessuna cartella, nessuno sblocco.

/** Una cartella del NAS dove "Salva sul NAS" puo' mettere i file: la destinazione e il nome da mostrare. Il nome e' un dato dell'utente: mai nei log. */
class DestinationChoice(val destination: NasDestination, val name: String) {
  override fun equals(other: Any?): Boolean = other is DestinationChoice && other.destination == destination && other.name == name

  override fun hashCode(): Int = destination.hashCode() * 31 + name.hashCode()

  override fun toString(): String = "DestinationChoice(${destination.area.code})"
}

/** Dove si puo' salvare (D24): le cartelle del NAS dalle Cartelle NAS, l'area comune con il suo avviso, dalla Libreria locale (funziona senza NAS, E-X8). */
interface IncomingDestinations {

  /** Le cartelle di partenza: di solito la cartella delle ricevute dell'area privata, poi le altre aree. */
  suspend fun roots(): List<DestinationChoice>

  /** Le sottocartelle (note alla Libreria) di [parent]; vuoto se non ne ha. */
  suspend fun children(parent: DestinationChoice): List<DestinationChoice>

  /** Il NAS risponde adesso? Serve solo a dire "lo salvo appena risponde" invece di "salvato" (E-X8). */
  suspend fun nasReachable(): Boolean

  /**
   * IN-11: come [nasReachable], ma dice anche perche' non risponde: sui dati mobili senza consenso il NAS "non risponde" per scelta
   * dell'utente, e la frase giusta e' "aspetto il Wi-Fi di casa". Il default distingue solo raggiungibile / non raggiungibile.
   */
  suspend fun nasWait(): NasWait = if (nasReachable()) NasWait.NONE else NasWait.OFFLINE

  /**
   * IN-6: il backup e' ancora in prova (D12, R2) e "Salva sul NAS" non scrive da nessuna parte? Si chiede **prima di copiare** i file. Il default
   * dice di no (i test e le varianti senza cancello).
   */
  suspend fun savingBlockedByTrial(): Boolean = false
}

/** Nessuna cartella: la schermata dice che l'app non e' pronta. */
object NoIncomingDestinations : IncomingDestinations {
  override suspend fun roots(): List<DestinationChoice> = emptyList()

  override suspend fun children(parent: DestinationChoice): List<DestinationChoice> = emptyList()

  override suspend fun nasReachable(): Boolean = false
}

/**
 * Lo sblocco dell'app per questa activity (E-X7): `:app` lo lega al blocco esistente (`AppLockController` + `BiometricPromptAuthenticator`, la
 * credenziale di sistema). Serve una `FragmentActivity` (la schermata di sistema delle credenziali la vuole). Dopo un `true` il cancello d'ingresso
 * (`IntegrationPorts.entryGate`) deve rispondere `ALLOWED`.
 */
fun interface ReceiveUnlock {
  suspend fun unlock(activity: FragmentActivity): Boolean

  /** IN-10: perche' l'ultimo [unlock] non e' riuscito, se lo si sa. Null = riuscito, o non si sa. */
  fun lastFailure(): UnlockFailure? = null
}

/** Nessuno sblocco: non si sblocca mai (l'app resta chiusa). */
object NoReceiveUnlock : ReceiveUnlock {
  override suspend fun unlock(activity: FragmentActivity): Boolean = false
}

/**
 * Un album dove "Aggiungi ad album" puo' mettere i file ricevuti (Fase 7, P10, D35, F5-D24). L'[id] e' opaco (lo conosce `:app`: il viewer, le Raccolte e la
 * ricezione non conoscono core-albums, i feature non si conoscono); il [name] e' un dato dell'utente, mai nei log. [shared] = un album condiviso: i file ci
 * entrano solo dopo la conferma col conteggio che `:app` chiede quando sono arrivati sul NAS (D12, mai un'aggiunta diretta).
 */
class IncomingAlbumChoice(val id: String, val name: String, val shared: Boolean) {
  override fun equals(other: Any?): Boolean = other is IncomingAlbumChoice && other.id == id && other.name == name && other.shared == shared

  override fun hashCode(): Int = (id.hashCode() * 31 + name.hashCode()) * 31 + shared.hashCode()

  override fun toString(): String = "IncomingAlbumChoice(shared=$shared)"
}

/**
 * "Aggiungi ad album" dalla condivisione in arrivo (Fase 7, P10, D35): al posto di "Salva sul NAS" e basta, i file ricevuti si salvano nella cartella scelta
 * **e** si aggiungono a un album appena sono nel catalogo. Il file ricevuto non ha ancora un `MediaId`: nasce con il salvataggio e con la scansione, e allora
 * `:app` lo mette nell'album (per un album condiviso, dopo il foglio di conferma di D12). Qui c'e' solo la scelta e la promessa: **niente si sposta e niente
 * si aggiunge da questa porta**; chi salva e' sempre "Salva sul NAS" con la sua conferma.
 */
interface IncomingAlbums {
  /** Gli album dove si puo' aggiungere (i miei manuali e i condivisi dove la tabella lo permette). Vuoto = la voce non compare (D13). */
  suspend fun albums(): List<IncomingAlbumChoice>

  /**
   * Il salvataggio dello stage [stage] e' in coda: quando i suoi file saranno nel catalogo, vanno in [album]. Registra la promessa in modo durevole; falso se non
   * si e' potuta registrare (la schermata lo dice, i file sono comunque in coda).
   */
  suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean
}

/** Nessun album: la voce "Aggiungi ad album" non compare. */
object NoIncomingAlbums : IncomingAlbums {
  override suspend fun albums(): List<IncomingAlbumChoice> = emptyList()

  override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean = false
}

/** Il punto in cui `:app` installa le porte della ricezione (P14). */
object ReceivePorts {

  @Volatile
  var uploads: IncomingUploads = NoIncomingUploads
    private set

  @Volatile
  var destinations: IncomingDestinations = NoIncomingDestinations
    private set

  @Volatile
  var unlock: ReceiveUnlock = NoReceiveUnlock
    private set

  @Volatile
  var albums: IncomingAlbums = NoIncomingAlbums
    private set

  /** Installa le porte vere; `null` rimette il null-object. [albums] (Fase 7, P10) e' facoltativa: senza, "Aggiungi ad album" non compare. */
  fun install(uploads: IncomingUploads?, destinations: IncomingDestinations?, unlock: ReceiveUnlock?, albums: IncomingAlbums? = null) {
    this.uploads = uploads ?: NoIncomingUploads
    this.destinations = destinations ?: NoIncomingDestinations
    this.unlock = unlock ?: NoReceiveUnlock
    this.albums = albums ?: NoIncomingAlbums
  }

  /** Rimette tutto ai null-object (azzeramento dell'app, test). */
  fun reset() = install(null, null, null)
}
