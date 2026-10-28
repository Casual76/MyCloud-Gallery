package dev.pampa.mycloudgallery.core.imaging.contract

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

// Le miniature della griglia (docs/fase3-progetto.md §1.2, `core-imaging`, e D5). Contratti di P0; pianificatore,
// cache e sorgenti vere sono di P6. Pipeline propria e sottile: niente Coil, niente Glide.

/**
 * Le taglie fisse delle miniature, in pixel sul lato lungo. Una cella chiede la piu' piccola che la copre: una
 * cache a taglie fisse riusa la stessa bitmap fra livelli vicini della griglia e fra la cella e l'espansione nel
 * viewer, e non moltiplica le copie quando il pinch cambia la dimensione.
 */
enum class ThumbBucket(val px: Int) {
  /** I livelli lontani (Anni, Mesi): celle di circa 56-80 dp. */
  S(160),

  /** Il livello Giorni (celle di circa 100 dp) e la griglia giustificata. */
  M(320),

  /** Il livello Tutte (celle di circa 130 dp) e la bitmap da cui parte l'espansione verso il viewer. */
  L(400),
  ;

  companion object {
    /** La taglia piu' piccola che copre [sidePx] pixel; oltre l'ultima, la piu' grande. */
    fun covering(sidePx: Int): ThumbBucket = entries.firstOrNull { it.px >= sidePx } ?: entries.last()
  }
}

/**
 * La chiave di una miniatura in cache.
 *
 * @property versionKey cambia quando i byte cambiano (una modifica, una correzione): la cella si aggiorna da sola
 *   perche' la chiave nuova non e' in cache. Di solito deriva dal timbro del file.
 */
@Immutable
data class ThumbKey(
  val mediaId: MediaId,
  val versionKey: Long,
  val bucket: ThumbBucket,
)

/** Una richiesta di miniatura: la chiave e dove stanno i byte. */
@Immutable
data class ThumbRequest(
  val key: ThumbKey,
  val ref: MediaRef,
)

/**
 * I contatori del caricatore, per la Diagnostica e per i test di budget (E-V5: "al massimo 3 decodifiche in volo,
 * le altre annullate"). Solo numeri.
 */
@Immutable
data class ThumbStats(
  val memoryHits: Long = 0,
  val misses: Long = 0,
  val started: Long = 0,
  val cancelled: Long = 0,
  val failed: Long = 0,
  val inFlight: Int = 0,
  val cachedBytes: Long = 0,
  val budgetBytes: Long = 0,
)

/**
 * Il caricatore delle miniature. **Le celle osservano soltanto**: [image] non avvia niente. Le richieste le fa la
 * griglia, **una volta per cambio di finestra** ([window]): cosi' il lavoro e' guidato da cio' che l'utente vede e da
 * dove sta andando, con priorita' e annullamento, e non da cento celle che chiedono ciascuna per conto suo (D5).
 */
interface ThumbnailLoader {

  /**
   * La miniatura di [key] come stato osservabile: null finche' non e' in cache, poi la bitmap. [ref] serve solo se
   * la miniatura non c'e' ancora e la finestra la chiede. Non avvia nessun caricamento.
   */
  fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?>

  /** La miniatura se e' gia' in memoria, subito e senza stato: l'espansione nel viewer parte da questa. */
  fun peek(key: ThumbKey): ImageBitmap?

  /**
   * Avvio istantaneo (28/10): vero se la cella di [key] ha gia' la sua risposta, cioe' la miniatura in memoria oppure un tentativo finito senza
   * (la cella resta sul segnaposto finche' non riprova). Non conta come lettura della cache. Di serie: la miniatura c'e'.
   */
  fun settled(key: ThumbKey): Boolean = peek(key) != null

  /**
   * La finestra di richieste: [visible] sono le celle sullo schermo (priorita' massima), [ahead] quelle che stanno
   * per entrare, nella direzione dello scorrimento. Ogni richiesta fuori da entrambe e ancora in volo si annulla
   * (`CancellationSignal`). Si chiama quando la finestra cambia, non a ogni fotogramma.
   */
  fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>)

  /** `onTrimMemory`: riduce la cache secondo il livello (`ComponentCallbacks2.TRIM_MEMORY_*`). */
  fun trim(level: Int)

  /**
   * L'app torna in primo piano (GA-3). Da `UI_HIDDEN` in su [trim] scarta anche le miniature visibili e, con l'app nascosta, non le
   * richiede: al ritorno la finestra e' la stessa e nessuno la rimanda, quindi qui si richiedono di nuovo quelle che mancano. Niente da
   * fare se non e' stato scartato niente. Di serie non fa niente (i caricatori che non scartano nulla).
   */
  fun resume() {}

  val stats: StateFlow<ThumbStats>
}

/**
 * Da dove arrivano i pixel di una miniatura. Per il telefono e' `ContentResolver.loadThumbnail` (annullabile, con la
 * cache del MediaProvider, `PhoneThumbnailSource` di P6); per la Libreria sintetica sono gradienti deterministici;
 * dalla Fase 4 le miniature del NAS arrivano dalla replica locale (`NasThumbnailSource`, P7).
 */
interface ThumbnailSource {

  /**
   * Una bitmap con il lato lungo di circa [px] pixel, o null se la sorgente non puo' darla (file illeggibile,
   * riferimento di un altro tipo). Deve rispettare [cancel]. Gira su un thread di lavoro: mai sul principale.
   *
   * E' la richiesta **singola e diretta** (l'anteprima del viewer, un test): una sorgente che distingue il contenuto
   * visibile da quello che sta per entrare (il NAS) la tratta come visibile. Il caricatore delle miniature usa
   * [loadResult].
   */
  suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap?

  /**
   * Fase 4 (P7): come [load], ma con il contesto della richiesta ([ThumbContext]) e un esito piu' ricco ([ThumbResult]).
   * Serve alle sorgenti la cui qualita' dipende da cio' che c'e' adesso (il NAS: replica, poi miniatura provvisoria,
   * poi l'originale **solo per il contenuto visibile**). Il default e' [load]: nessuna sorgente della Fase 3 cambia.
   *
   * @return `null` = non disponibile (un fallimento: il pianificatore riprova con attese crescenti e poi smette);
   *   [ThumbResult.Deferred] = non adesso, riprova alla prossima finestra (non e' un fallimento).
   */
  suspend fun loadResult(ref: MediaRef, px: Int, cancel: CancellationSignal, context: ThumbContext): ThumbResult? =
    load(ref, px, cancel)?.let { ThumbResult.Loaded(it) }

  /**
   * Fase 4 (P7): vero se per [ref] c'e' adesso qualcosa di meglio di una miniatura **provvisoria** gia' data (la replica
   * ha ricevuto il blob). Chiamata bloccante e veloce (indice in memoria). Il caricatore la usa in
   * `PipelineThumbnailLoader.refreshProvisional` per sostituire, senza lampeggi, la provvisoria con quella preparata.
   */
  fun canUpgrade(ref: MediaRef, px: Int): Boolean = false
}

/**
 * Il contesto di una richiesta di miniatura, dato dal caricatore (non dalla cella): per ora solo se il contenuto e'
 * **visibile adesso**. [isVisible] si legge nel momento in cui serve (una richiesta in arrivo puo' diventare visibile
 * mentre e' in coda): una sorgente costosa lo chiede subito prima del lavoro caro, non all'inizio.
 */
class ThumbContext(val isVisible: () -> Boolean) {
  companion object {
    /** Una richiesta diretta: visibile (l'utente la sta guardando). */
    val Visible: ThumbContext = ThumbContext { true }

    /** Una richiesta speculativa (contenuto in arrivo): non visibile. */
    val Ahead: ThumbContext = ThumbContext { false }
  }
}

/** L'esito di [ThumbnailSource.loadResult] (null = non disponibile). */
sealed interface ThumbResult {

  /**
   * La bitmap. [provisional] = viene da una fonte meno buona di quella prevista (la miniatura EXIF letta al
   * rilevamento, o la decodifica dell'originale, D17): il caricatore la ricorda e, quando la replica riceve la
   * miniatura preparata, la sostituisce.
   */
  class Loaded(val bitmap: Bitmap, val provisional: Boolean = false) : ThumbResult

  /**
   * Niente da dare **adesso**, e non per un errore: il contenuto non e' ancora preparato e non e' visibile, quindi
   * leggere l'originale non e' ammesso. Il pianificatore non lo conta come fallimento e lo riprova a ogni cambio di
   * finestra (quando diventa visibile).
   */
  data object Deferred : ThumbResult
}

/**
 * Il segnaposto sfocato di un media: la bitmap 32x32 decodificata dal [ThumbHash] dell'indice. Piccola cache
 * (decodificare costa meno di tenere tutto, ma non va rifatto a ogni ricomposizione). E' cio' che il primo
 * fotogramma della Libreria disegna in ogni cella, prima di qualunque miniatura.
 */
interface PlaceholderCache {
  fun image(h: ThumbHash): ImageBitmap

  /**
   * Vero se [image] si puo' chiamare anche da un thread di lavoro: la griglia allora decodifica in anticipo, fuori dal
   * thread principale, i segnaposto delle celle attorno a dove si e' (misure sull'S25 del 15/10). Falso di serie: una
   * cache che non lo dichiara si usa solo dalla composizione.
   */
  val threadSafe: Boolean get() = false
}
