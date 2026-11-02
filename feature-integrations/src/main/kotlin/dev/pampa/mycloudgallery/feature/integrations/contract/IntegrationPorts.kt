package dev.pampa.mycloudgallery.feature.integrations.contract

import dev.pampa.mycloudgallery.feature.integrations.noop.NoEntryGate
import dev.pampa.mycloudgallery.feature.integrations.noop.NoMediaDocuments
import java.io.Closeable

// Fase 5B (docs/fase5-progetto.md §1.1, §1.5, §2.7, D23, C17, E-H1, E-X1, E-X7; riga P0-B del §4.4): le PORTE che i componenti d'ingresso delle
// integrazioni leggono e che `:app` lega (P14). Sono di questo modulo perche' i `feature-*` non vedono `core-nas`: il provider legge i media del
// NAS da una porta che `:app` lega a `NasMediaReader` (§1.1). Firme indicative: P10 (selettore e provider) e P11 (arrivi, "Modifica con…",
// senza posizione) le affinano nei loro pacchetti, senza toccare il manifest.
//
// I componenti li crea il **sistema** (un'altra app avvia un'activity, DocumentsUI interroga un provider): non si iniettano dal costruttore.
// Come per `ShareFences` e `LibraryDebugHooks`, le porte si installano una volta all'avvio dell'app ([IntegrationPorts.install]); finche' nessuno
// le installa valgono i null-object (`noop/`), che falliscono chiusi: nessuna radice, nessun documento, nessun ingresso.

/** Se un'activity d'ingresso puo' mostrare qualcosa adesso. */
enum class EntryDecision {
  /** La famiglia e' pronta e il blocco dell'app (se acceso) e' sbloccato. */
  ALLOWED,

  /** Il blocco dell'app e' acceso: prima lo sblocco con la credenziale di sistema (E-X7). Niente si mostra prima. */
  NEEDS_UNLOCK,

  /** L'app non e' configurata (onboarding, famiglia non pronta, dispositivo revocato): non si offre niente. */
  NOT_READY,

  /**
   * IN-3 della verifica funzionale 5B: l'app sta ancora **leggendo il suo stato** (la configurazione salvata, le impostazioni del blocco). A processo
   * freddo, quando un'altra app ci chiama, e' il caso normale per qualche istante: non si decide (prima si rispondeva "Prima configura MyCloud
   * Gallery" o "chiusa" per una corsa). Non si mostra niente; chi decide aspetta ([EntryGate.decideAfterLoading]).
   */
  LOADING,

  /**
   * CA-2 e R1 delle revisioni della Fase 10 (E-REL6): **questa versione dell'app e' stata fermata** dal manifest firmato (blocco `ALL`). Selettore, provider dei
   * documenti, ricezione e rilasci si chiudono e lo dicono ("aggiorna l'app"); niente si mostra, niente si consegna, nemmeno un documento gia' consegnato.
   */
  STOPPED,
}

/**
 * Il cancello delle porte d'ingresso (C17, E-X7): `:app` lo lega al blocco dell'app (`AppLockGate`, esteso alle activity d'ingresso in P14) e
 * allo stato della famiglia. Un'altra app che chiede una foto con il blocco acceso vede prima lo sblocco, mai la Libreria.
 */
fun interface EntryGate {
  fun decide(): EntryDecision

  /**
   * IN-3: come [decide], ma se l'app sta ancora leggendo il suo stato ([EntryDecision.LOADING]) aspetta che lo abbia letto, al massimo [timeoutMs]
   * (orologio monotono, mai l'ora del telefono). Dopo l'attesa puo' ancora rispondere [EntryDecision.LOADING]: chi la chiama non offre niente.
   * Il default non aspetta (i cancelli che non hanno uno stato da caricare).
   */
  suspend fun decideAfterLoading(timeoutMs: Long = LOAD_WAIT_MS): EntryDecision = decide()

  companion object {
    /** Quanto un'activity d'ingresso aspetta che l'app legga il suo stato prima di non offrire niente. */
    const val LOAD_WAIT_MS: Long = 10_000L
  }
}

/**
 * L'id opaco di un documento del provider (D23): mai un percorso, mai un nome, mai un id del MediaStore o del NAS in chiaro. Lo emette il
 * provider (token da una tabella tenuta in memoria dal processo, o da un HMAC) e vale solo per questo avvio: un id che arriva da fuori si
 * riconosce, non si fida. Solo lettere, cifre, `_` e `-`: nessun separatore di percorso, nessun `.` (niente `..`).
 */
@JvmInline
value class DocumentId(val value: String) {
  init {
    require(DocumentIds.isOpaque(value)) { "Un id di documento opaco." }
  }

  override fun toString(): String = "DocumentId"
}

object DocumentIds {
  private val OPAQUE = Regex("^[A-Za-z0-9_-]{8,64}$")

  /** Vero se [value] ha la forma di un id opaco (lo stesso test per gli id che arrivano dall'esterno, prima di cercarli). */
  fun isOpaque(value: String): Boolean = OPAQUE.matches(value)
}

/** Una radice del provider ("MyCloud Gallery": Recenti, Cartelle NAS). Il titolo e' una stringa dell'app, non un dato. */
data class DocumentRoot(val rootId: String, val rootDocument: DocumentId)

/**
 * Un documento o una cartella del provider. Il nome e' un dato dell'utente: mai nei log. [size] e' [UNKNOWN_SIZE] quando non si sa: con
 * "Rimuovi la posizione" acceso la copia senza posizione e' piu' piccola dell'originale e si fa solo quando serve, quindi finche' non c'e' non si
 * dichiara nessuna dimensione (un'altra app che si fidasse di una dimensione sbagliata leggerebbe un file troncato).
 */
data class DocumentEntry(
  val id: DocumentId,
  val displayName: String,
  val mime: String,
  val size: Long,
  val modifiedAtMillis: Long?,
  val isFolder: Boolean,
) {
  init {
    require(size >= UNKNOWN_SIZE) { "Dimensione negativa." }
  }

  companion object {
    const val UNKNOWN_SIZE: Long = -1L
  }

  override fun toString(): String = "DocumentEntry(${if (isFolder) "cartella" else mime}, $size B)"
}

/**
 * Un documento aperto in lettura a intervalli (un descrittore a richiesta sopra `NasMediaReader`, con scadenze: E-X1). Lo chiude chi lo usa.
 */
interface DocumentReader : Closeable {
  val size: Long

  /** Legge fino a [len] byte da [offset]; restituisce quanti, 0 a fine file. Su un NAS che non risponde fallisce subito, mai un'attesa infinita. */
  suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int
}

/**
 * I media che il provider offre (D23): solo media del catalogo, **mai i nascosti** (E-H1), id opachi. `:app` la lega alla Libreria (copie del
 * telefono) e a `NasMediaReader` (copie solo-NAS). Il filtro dei nascosti e' di chi la implementa e lo prova il test su entrambe le vie.
 */
interface MediaDocuments {
  suspend fun roots(): List<DocumentRoot>

  suspend fun children(parent: DocumentId, limit: Int): List<DocumentEntry>

  suspend fun entry(id: DocumentId): DocumentEntry?

  /**
   * Apre [id] in lettura, o null se non c'e' (piu'), non e' leggibile adesso o il NAS non risponde entro la scadenza.
   *
   * Con "Rimuovi la posizione" acceso apre **solo la copia senza posizione** (mai l'originale): se non c'e' la prepara, e se non si riesce a toglierla
   * lancia una [dev.pampa.mycloudgallery.feature.integrations.provider.LocationRemovalRefused] (il provider la rende all'altra app come errore con
   * il motivo). Non lancia altro per i guasti: quelli sono `null`.
   */
  suspend fun open(id: DocumentId): DocumentReader?

  /**
   * Vero se [open] puo' dover preparare una copia (togliere la posizione): puo' durare molto piu' di una lettura, e il provider gli da' il tempo
   * di [dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy.PREPARE_DEADLINE_MS] invece di quello di una lettura.
   */
  fun preparesOnOpen(): Boolean = false
}

/**
 * Il punto in cui `:app` installa le porte (P14). I componenti creati dal sistema le leggono da qui; senza installazione valgono i null-object.
 */
object IntegrationPorts {

  @Volatile
  var entryGate: EntryGate = NoEntryGate
    private set

  @Volatile
  var documents: MediaDocuments = NoMediaDocuments
    private set

  /** Installa le porte vere ([entryGate] e [documents]); `null` rimette il null-object. */
  fun install(entryGate: EntryGate?, documents: MediaDocuments?) {
    this.entryGate = entryGate ?: NoEntryGate
    this.documents = documents ?: NoMediaDocuments
  }

  /** Rimette tutto ai null-object (azzeramento dell'app, test). */
  fun reset() = install(null, null)
}
