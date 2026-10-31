package dev.pampa.mycloudgallery.core.map.contract

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// I LIMITI DI OPENFREEMAP PER GLI SCARICAMENTI OFFLINE (Fase 9, P5; decisione dell'utente del 31/10: **restare dentro**). Le condizioni d'uso del servizio (lette il
// 30/10, docs/decisioni.md "Fase 9 P1") ammettono uno scaricamento offline solo se ognuno e' al piu' di 3.000 tessere **e** di 50 MB (vale il piu' stretto), con al piu'
// 4 richieste insieme e un User-Agent che identifichi l'app. Oltre bisognerebbe scrivere a info@openfreemap.org, e non lo facciamo. Tutto cio' che segue e' in `contract/`
// perche' lo vedono il cancello (`TilePolicy`, `feature-map/.../net/`) e il gestore delle regioni (`core.map.regions`): una definizione sola.

/** I tre numeri delle condizioni d'uso di OpenFreeMap e il margine con cui li usiamo. */
object OpenFreeMapLimits {
  /** Al piu' tante tessere per scaricamento. */
  const val MAX_TILES: Int = 3_000

  /**
   * Al piu' tanti byte per scaricamento: "50 MB" letto in modo **stretto** (50.000.000 e non 52.428.800), cosi' le al piu' 4 tessere gia' in volo quando lo scaricamento si ferma
   * (qualche centinaio di KB, mai oltre 2 MiB l'una) restano dentro i 50 MiB anche nel caso peggiore. Sono i byte **sul filo** (compressi), misurati da `MapWireGuard`.
   */
  const val MAX_BYTES: Long = 50_000_000L

  /** Al piu' tante richieste insieme verso l'host delle tessere (`MapTileHttp`: `maxRequestsPerHost`). */
  const val MAX_PARALLEL: Int = 4

  /**
   * La stima si usa con questo fattore prima di partire: la stima e' entro il 30% dei campioni veri (docs/decisioni.md "Fase 9 P5"), quindi una zona si offre solo se
   * `stima x 1,3` sta nei 50 MB. Lo scaricamento poi si ferma comunque ai byte veri.
   */
  const val ESTIMATE_SAFETY: Double = 1.3
}

/** Perche' un [DownloadBudget] e' esaurito. */
enum class BudgetLimit {
  TILES,
  BYTES,
}

/**
 * Il conto di **uno** scaricamento di una regione, dentro `RegionDownloadScope` (§2.8, D26): lo tengono il cancello (che ammette una tessera solo se c'e' posto: [tryAdmit]) e
 * l'intercettore di rete (che conta i byte veri: [received]). Quando il tetto e' raggiunto ([exhausted]) la politica rifiuta le tessere della regione con `RefuseReason.LIMIT` e il
 * gestore ferma lo scaricamento: la regione resta "parziale" con il motivo e **non riparte da sola**. Una ripresa dopo la morte del processo riparte dal conto di prima
 * ([startTiles], [startBytes]): una regione e' **uno** scaricamento, mai spezzato in pezzi piccoli per aggirare il limite.
 *
 * Sicuro fra thread (le richieste arrivano da quattro thread di OkHttp). Nessuna tessera, nessuna zona: solo numeri.
 */
class DownloadBudget(
  val maxTiles: Int = OpenFreeMapLimits.MAX_TILES,
  val maxBytes: Long = OpenFreeMapLimits.MAX_BYTES,
  startTiles: Int = 0,
  startBytes: Long = 0,
) {
  init {
    require(maxTiles > 0 && maxBytes > 0) { "Tetti non validi." }
    require(startTiles >= 0 && startBytes >= 0) { "Conto di partenza non valido." }
  }

  private val done = AtomicInteger(startTiles)
  private val flying = AtomicInteger(0)
  private val peak = AtomicInteger(0)
  private val received = AtomicLong(startBytes)
  private val fired = AtomicBoolean(false)

  /**
   * Chiamata **una volta sola**, da un thread della rete, quando uno dei due tetti e' raggiunto (il gestore delle regioni ferma allora lo scaricamento). Si imposta prima di
   * pubblicare il conto nel `RegionDownloadScope`; non deve bloccare.
   */
  @Volatile
  var onExhausted: ((BudgetLimit) -> Unit)? = null

  /** Tessere arrivate (anche quelle del conto di partenza). */
  val tiles: Int get() = done.get()

  /** Byte arrivati sul filo (anche quelli del conto di partenza). */
  val bytes: Long get() = received.get()

  /** Quante tessere sono in volo adesso, e al massimo quante lo sono state insieme (la prova di "al piu' 4 richieste insieme"). */
  val inFlight: Int get() = flying.get()
  val peakInFlight: Int get() = peak.get()

  /**
   * Il tetto raggiunto, o null (i byte per primi: sono quelli che si superano). Con le tessere in volo il conto puo' superare di poco il tetto dei byte (al piu'
   * [OpenFreeMapLimits.MAX_PARALLEL] tessere); le tessere arrivate non superano mai il loro tetto, perche' [tryAdmit] conta anche quelle in volo.
   */
  val limit: BudgetLimit?
    get() = when {
      received.get() >= maxBytes -> BudgetLimit.BYTES
      done.get() >= maxTiles -> BudgetLimit.TILES
      else -> null
    }

  /** Vero se non c'e' piu' posto per una tessera nuova (tessere arrivate o in volo oltre il tetto, o byte oltre il tetto). */
  val exhausted: Boolean get() = received.get() >= maxBytes || done.get() + flying.get() >= maxTiles

  /** Chiede posto per una tessera nuova: se c'e' la mette "in volo" e dice si'. Dopo, [arrived] o [failed]. */
  fun tryAdmit(): Boolean {
    while (true) {
      if (received.get() >= maxBytes) return false
      val inAir = flying.get()
      val total = done.get()
      if (total + inAir >= maxTiles) return false
      if (flying.compareAndSet(inAir, inAir + 1)) {
        peak.accumulateAndGet(inAir + 1) { a, b -> maxOf(a, b) }
        return true
      }
    }
  }

  /** La tessera in volo e' arrivata (anche se vuota). */
  fun arrived() {
    done.incrementAndGet()
    flying.decrementAndGet()
    notifyIfExhausted()
  }

  /** La tessera in volo non e' arrivata (errore, rifiuto): non conta come scaricata, e si potra' richiedere. */
  fun failed() {
    flying.decrementAndGet()
  }

  /** [count] byte arrivati sul filo per una tessera di questo scaricamento. */
  fun received(count: Long) {
    if (count > 0) received.addAndGet(count)
    notifyIfExhausted()
  }

  private fun notifyIfExhausted() {
    val reached = limit ?: return
    if (fired.compareAndSet(false, true)) onExhausted?.invoke(reached)
  }

  override fun toString(): String = "DownloadBudget(${done.get()}/$maxTiles, ${received.get()}/$maxBytes)"
}
