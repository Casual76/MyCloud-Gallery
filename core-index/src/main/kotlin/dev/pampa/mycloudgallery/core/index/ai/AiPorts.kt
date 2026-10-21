package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.replica.Fusion
import dev.pampa.mycloudgallery.core.index.replica.PathKeys
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.util.Locale

// Le PORTE dei dati AI dell'indice condiviso (Fase 6, P2) verso cio' che sta fuori da questo pacchetto: il catalogo locale (M3, D8),
// la replica dell'indice (attestazione, D7), il registro del traffico (D10) e il catalogo per le unita' di lavoro (D9). Le lega :app
// (P11) ai DAO dello schema v5 (P1); qui le firme e le implementazioni di riferimento usate dai test.

/**
 * La regola **M3** con le chiavi di contenuto (D8; formato-nas.md §5.5, "Testa `ai`"): una voce `vec` o `ocr` si pubblica nell'indice
 * di una share solo per la chiave di contenuto di un file **di quella share** presente nel catalogo locale, con timbro e `qh1` ancora
 * freschi (l'impronta rapida letta qui per l'ultimo elenco). Niente di un file dell'area privata finisce nell'indice dell'area comune.
 *
 * P11 la lega al catalogo (`Location.Nas` della share con `quick_hash` del timbro corrente → `ContentKey.of(qh1, size)`).
 */
fun interface AiShareContent {
  /** Delle [keys], quelle che sono la chiave di contenuto di un file di [share] nel catalogo locale, con timbro e `qh1` freschi. */
  suspend fun owned(share: String, keys: Collection<ContentKey>): Set<ContentKey>
}

/** [AiShareContent] in memoria (test, simulazioni): share → chiavi. */
class MapAiShareContent : AiShareContent {
  private val lock = Any()
  private val keys = HashMap<String, HashSet<ContentKey>>()

  fun put(share: String, key: ContentKey) {
    synchronized(lock) { keys.getOrPut(share.lowercase(Locale.ROOT)) { HashSet() } += key }
  }

  /** Il file non c'e' piu', o il suo timbro e' cambiato (una scansione l'ha visto diverso). */
  fun remove(share: String, key: ContentKey) {
    synchronized(lock) { keys[share.lowercase(Locale.ROOT)]?.remove(key) }
  }

  override suspend fun owned(share: String, keys: Collection<ContentKey>): Set<ContentKey> = synchronized(lock) {
    val known = this.keys[share.lowercase(Locale.ROOT)] ?: return@synchronized emptySet()
    keys.filterTo(HashSet()) { it in known }
  }
}

/**
 * L'**attestazione** dei dati derivati (D7, E-I21; formato-nas.md §5.5-bis): una voce `vec` o `ocr` vale solo se, **nella stessa
 * share**, c'e' un record `meta` presente, il vincitore della regola D6 della Fase 4 (di qualunque dispositivo approvato), con la
 * **stessa chiave di contenuto intera** (32 byte, non i primi 64 bit). Il dispositivo che pubblica la voce non deve avere un record suo.
 *
 * P11 puo' legarla a una query del catalogo (le chiavi di contenuto delle posizioni `Location.Nas` della share con un vincitore
 * presente); [ReplicaAttestation] e' l'implementazione di riferimento sopra la replica della Fase 4.
 */
fun interface AiAttestation {
  /** Delle [keys], quelle attestate in [share]. */
  suspend fun attested(share: String, keys: Collection<ContentKey>): Set<ContentKey>
}

/**
 * [AiAttestation] sopra la replica dell'indice (D6, D7): per ogni percorso della share il record vincente fra quelli validi
 * ([Fusion.winner], timbro uguale all'ultimo elenco della scansione o, senza elenco, provvisorio), e la sua chiave di contenuto.
 * Legge tutti i record della share a lotti di [batch] percorsi: un giro per chiamata (la replica `ai` la chiama una volta per share e
 * per giro con tutte le chiavi che le servono).
 */
class ReplicaAttestation(
  private val store: ReplicaStore,
  private val catalog: ReplicaCatalog,
  private val batch: Int = 2_000,
) : AiAttestation {
  override suspend fun attested(share: String, keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val wanted = keys.toHashSet()
    val shareKey = PathKeys.share(share)
    val out = HashSet<ContentKey>()
    for (chunk in store.pathKeys(shareKey).sorted().chunked(batch)) {
      val rows = store.records(shareKey, chunk).groupBy { it.pathKey }
      val listing = catalog.listingStamps(shareKey, chunk)
      for (pathKey in chunk) {
        val winner = Fusion.winner(Fusion.latestByDevice(rows[pathKey].orEmpty()), listing[pathKey]) ?: continue
        val key = winner.second.file.contentKey
        if (key in wanted) out += key
      }
    }
    return out
  }
}

/**
 * Il traffico delle anteprime scaricate per il calcolo (D10): :app lo lega al `TrafficLedger` con la categoria del calcolo AI. Solo
 * byte, mai share, percorsi o chiavi.
 */
fun interface AiTraffic {
  fun record(bytes: Long)
}

/**
 * Il catalogo locale per le unita' di lavoro del calcolo AI (D9, [AiWorkSource]). P11 lo lega al catalogo (media NAS della share,
 * mese di scatto) e all'indice locale dei dati AI (P1: `ai_vector`, `ai_text`).
 */
interface AiWorkCatalog {
  /** Tutti i mesi (`aaaa-mm`, `0000-00` per i media senza data) con almeno un media in [share], dal piu' recente. */
  suspend fun months(share: String): List<String>

  /** I mesi che contengono una delle chiavi visibili sullo schermo ([visible]: `ContentKey.short`). */
  suspend fun monthsOf(share: String, visible: Set<Long>): List<String>

  /** **Tutti** i media del mese (il campo `missing` non conta: lo calcola [AiWorkSource]). */
  suspend fun monthMedia(share: String, month: String): List<WorkItem>

  /**
   * Per [keys], le classi AI gia' a posto **qui**: nell'indice locale con la ricetta corrente (calcolate da questo dispositivo o
   * ricevute) oppure non applicabili (formato non decodificabile: "non supportato" nella copertura).
   */
  suspend fun settled(share: String, keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>>
}

/** Com'e' andata la parte `ai` di una share in un giro di sincronizzazione. */
data class AiSyncOutcome(
  /** Il NAS non ha risposto (o ha negato l'accesso): la parte `ai` riprende al giro dopo. */
  val failure: RemoteFailure?,
  /** Falso se il tempo o il budget dei byte sono finiti prima della fine: il resto al giro dopo. */
  val complete: Boolean,
) {
  companion object {
    val DONE = AiSyncOutcome(null, complete = true)
  }
}

/**
 * L'aggancio delle teste `ai` nel giro di `IndexSyncEngine` (§1.4: "teste `ai` lette nello stesso giro, dopo le `index`"). Il motore
 * lo chiama per ogni share dopo aver applicato le teste `index` e rifuso i percorsi, con i dispositivi che ha trovato in `devices/`
 * (gia' filtrati sul registro), e quando una share si toglie (E-I8). [AiShareSync] e' l'implementazione.
 */
interface AiSyncHook {
  suspend fun afterIndex(share: String, devices: List<String>, timeUp: () -> Boolean): AiSyncOutcome

  suspend fun shareDropped(share: String)
}
