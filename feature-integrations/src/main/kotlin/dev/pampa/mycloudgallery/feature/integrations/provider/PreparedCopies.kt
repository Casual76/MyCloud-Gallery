package dev.pampa.mycloudgallery.feature.integrations.provider

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLimits
import dev.pampa.mycloudgallery.feature.integrations.share.SharePreparation
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Decisione dell'utente (20/10/2026), "Correzioni 5B: integrazioni" in docs/decisioni.md: **"Rimuovi la posizione" vale anche per il selettore e per il
// provider dei documenti** (prima solo per "Condividi": L41). Con l'impostazione accesa cio' che un'altra app riceve dal selettore o apre dal provider e'
// una **copia senza posizione**, mai l'originale, fatta con lo stesso stripper di "Condividi" (`LocationFreeShare`: ricostruzione per elenco di cio' che
// si tiene, controllo indipendente `LocationTraces`, almeno due lettori) e non con un secondo.
//
//  - **Si fallisce chiusi.** Se la posizione non si puo' togliere (formato non trattabile, posizione rimasta, guasto, niente spazio, NAS spento) quell'elemento
//    non si consegna: l'altra app riceve un errore e l'utente vede un messaggio chiaro. Mai l'originale al suo posto, nemmeno per ripiego.
//  - **Le copie stanno nella cache delle integrazioni** (`cacheDir/share/`, la stessa di "Condividi", con i suoi tetti: 100 elementi per volta,
//    2 GiB in tutto, almeno 1 GiB libero dopo la copia). Si tolgono quando il loro id scade o si revoca (stessa durata dell'id: 30 minuti a scorrimento per
//    lo sfoglio, 6 ore per gli id consegnati dal selettore), con l'azzeramento dell'app, e all'avvio successivo del processo (`ShareCache.sweepAtStart`).
//  - **Mai tutto in memoria.** Il media si copia a blocchi di 1 MiB in un file di lavoro (con l'avanzamento a byte e annullabile), si ricostruisce da file a file
//    e il file di lavoro con la posizione si cancella subito.

/** Perche' non e' stata fatta la copia senza posizione di un media. Per l'altra app e' un errore di apertura; per l'utente un messaggio. */
class LocationRemovalRefused(val stop: ShareStop) : IOException("Senza posizione: ${stop.code}")

/** L'esito di [DeliveryPreparer.prepare]. */
sealed interface PreparedBatch {

  /** Tutte le copie senza posizione ci sono, nell'ordine chiesto. */
  class Ready(val ids: List<MediaId>) : PreparedBatch

  /** Nessuna consegna: la preparazione si e' fermata per [stop] al media [id] (null se non e' di un solo media: spazio, tetti). Niente resta in cache. */
  class Refused(val stop: ShareStop, val id: MediaId?) : PreparedBatch
}

/** Nome, tipo e dimensione veri di una copia pronta (un HEIC e' diventato JPEG e il file e' piu' piccolo). */
class PreparedInfo(val displayName: String, val mime: String, val size: Long) {
  override fun toString(): String = "PreparedInfo($mime, $size B)"
}

/**
 * Chi prepara le copie senza posizione per il selettore e per il provider. `:app` lo lega a [LocationFreeShare] e all'impostazione; di serie
 * ([NoPreparation]) non c'e' impostazione e si consegnano gli originali, come prima.
 */
interface DeliveryPreparer {

  /** "Rimuovi la posizione" e' acceso adesso? Se si', niente esce se non una copia fatta da qui. */
  fun removeLocation(): Boolean

  /**
   * Prepara (o ritrova) le copie senza posizione di [ids]. [delivered]: le consegna il selettore (durano come un id consegnato) e non lo sfoglio.
   * Sospende anche a lungo (un video di qualche GiB solo sul NAS): [onItem] riceve quanti media sono fatti, [onBytes] i byte del media in corso.
   * Una cancellazione toglie le copie fatte in questa chiamata.
   */
  suspend fun prepare(
    ids: List<MediaId>,
    delivered: Boolean,
    onItem: (done: Int, total: Int) -> Unit = { _, _ -> },
    onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
  ): PreparedBatch

  /** Nome, tipo e dimensione della copia pronta di [id], o null se non c'e' (non ancora preparata, scaduta, tolta). */
  fun infoOf(id: MediaId): PreparedInfo?

  /** Apre la copia pronta di [id] in lettura a intervalli, o null se non c'e'. Lo chiude chi lo usa. */
  fun open(id: MediaId): DocumentReader?

  /** Toglie le copie di [ids] (l'id e' stato revocato, il media e' stato nascosto). */
  fun release(ids: Collection<MediaId>)

  /** Toglie tutte le copie (azzeramento dell'app, famiglia che cambia). */
  fun releaseAll()
}

/** Nessuna impostazione legata: si consegnano gli originali (come prima della decisione del 20/10). Nelle build vere `:app` installa [PreparedCopies]. */
object NoPreparation : DeliveryPreparer {
  override fun removeLocation(): Boolean = false

  override suspend fun prepare(ids: List<MediaId>, delivered: Boolean, onItem: (Int, Int) -> Unit, onBytes: (Long, Long) -> Unit): PreparedBatch =
    PreparedBatch.Refused(ShareStop.FAILED, null)

  override fun infoOf(id: MediaId): PreparedInfo? = null

  override fun open(id: MediaId): DocumentReader? = null

  override fun release(ids: Collection<MediaId>) = Unit

  override fun releaseAll() = Unit
}

/**
 * Le copie senza posizione vive, per media. Una per volta (un solo [Mutex]: due preparazioni insieme non riempiono la cache), con un tetto sul totale
 * ([maxBytes]) e una scadenza a scorrimento: chi apre o consegna di nuovo il media la rinnova. Le thread-safe: le chiamano i thread del binder, il
 * selettore e il custode che toglie le scadute.
 *
 * @param scope la vita del processo: serve solo al custode delle scadenze e alla cancellazione delle copie (mai alla preparazione, che e' di chi chiama).
 */
class PreparedCopies(
  private val share: LocationFreeShare,
  private val removeLocationNow: () -> Boolean,
  private val scope: CoroutineScope,
  private val clock: DocumentClock = DocumentClock.Monotonic,
  private val browseTtlMs: Long = DocumentPolicy.BROWSE_ID_TTL_MS,
  private val deliveredTtlMs: Long = DocumentPolicy.DELIVERED_ID_TTL_MS,
  private val maxBytes: Long = ShareLimits.MAX_TOTAL_BYTES,
  private val keeperPeriodMs: Long = KEEPER_PERIOD_MS,
  private val io: CoroutineContext = Dispatchers.IO,
) : DeliveryPreparer {

  /** Le copie nate da una stessa preparazione stanno nella stessa cartella e si tolgono insieme, quando l'ultima esce. */
  private class Group(val lease: ShareLease) {
    var live = 0
  }

  private class Entry(
    val id: MediaId,
    val file: File,
    val mime: String,
    val size: Long,
    val group: Group,
    var expiresAt: Long,
    var delivered: Boolean,
  )

  private val lock = Any()
  private val entries = HashMap<MediaId, Entry>()
  private val one = Mutex()
  private var keeper: Job? = null

  /** Quante copie ci sono (per i test e la Diagnostica: solo un numero). */
  val count: Int get() = synchronized(lock) { entries.size }

  /** Quanti byte occupano (per i test). */
  val bytes: Long get() = synchronized(lock) { entries.values.sumOf { it.size } }

  override fun removeLocation(): Boolean = removeLocationNow()

  override suspend fun prepare(
    ids: List<MediaId>,
    delivered: Boolean,
    onItem: (done: Int, total: Int) -> Unit,
    onBytes: (done: Long, total: Long) -> Unit,
  ): PreparedBatch {
    if (ids.isEmpty()) return PreparedBatch.Refused(ShareStop.NOTHING, null)
    val distinct = ids.distinct()
    var missing = synchronized(lock) { distinct.filterNot { touch(it, delivered) } }
    if (missing.isNotEmpty()) {
      one.withLock {
        // Una preparazione arrivata mentre si aspettava il turno: non si rifa'.
        missing = synchronized(lock) { missing.filterNot { touch(it, delivered) } }
        if (missing.isNotEmpty()) {
          val already = distinct.size - missing.size
          onItem(already, distinct.size)
          val result = share.prepare(
            items = missing,
            removeLocation = true,
            onProgress = { done, _ -> onItem(already + done, distinct.size) },
            onBytes = onBytes,
          )
          when (result) {
            // I contatori della preparazione (copie senza posizione, fermate per motivo) li tiene gia' `LocationFreeShare` (`FlowCounters.share`).
            is SharePreparation.Stopped -> return PreparedBatch.Refused(result.stop, missing.getOrNull(result.index).takeIf { result.stop.isAboutOneMedia })
            is SharePreparation.Ready -> if (!register(missing, result, delivered)) return PreparedBatch.Refused(ShareStop.TOO_BIG, null)
          }
        }
      }
    }
    onItem(distinct.size, distinct.size)
    return PreparedBatch.Ready(ids)
  }

  override fun infoOf(id: MediaId): PreparedInfo? = synchronized(lock) {
    val entry = live(id) ?: return null
    PreparedInfo(entry.file.name, entry.mime, entry.size)
  }

  override fun open(id: MediaId): DocumentReader? {
    val file = synchronized(lock) {
      val entry = live(id) ?: return null
      entry.expiresAt = clock.elapsedMs() + ttlOf(entry.delivered)
      entry.file
    }
    return try {
      FileDocumentReader(file)
    } catch (_: IOException) {
      null
    }
  }

  override fun release(ids: Collection<MediaId>) {
    val gone = synchronized(lock) { ids.mapNotNull { drop(it) } }
    gone.forEach(::delete)
  }

  override fun releaseAll() {
    val gone = synchronized(lock) { entries.keys.toList().mapNotNull { drop(it) } }
    gone.forEach(::delete)
  }

  // --- Dentro (sempre sotto [lock]) -----------------------------------------------------------------------------------------

  private fun ttlOf(delivered: Boolean): Long = if (delivered) deliveredTtlMs else browseTtlMs

  /** La copia di [id] se c'e' e vale ancora; una scaduta o sparita dal disco si toglie. */
  private fun live(id: MediaId): Entry? {
    val entry = entries[id] ?: return null
    if (clock.elapsedMs() >= entry.expiresAt || !entry.file.isFile) {
      drop(id)?.let { delete(it) }
      return null
    }
    return entry
  }

  /** Rinnova la copia di [id] (e la fa "consegnata" se lo e' adesso); falso se non c'e'. */
  private fun touch(id: MediaId, delivered: Boolean): Boolean {
    val entry = live(id) ?: return false
    if (delivered) entry.delivered = true
    entry.expiresAt = clock.elapsedMs() + ttlOf(entry.delivered)
    return true
  }

  /** Cio' che va cancellato dal disco quando una copia esce dal registro: il suo file, e tutto il gruppo se era l'ultima del gruppo. */
  private class Dropped(val file: File, val group: Group?)

  /** Toglie [id] dal registro. */
  private fun drop(id: MediaId): Dropped? {
    val entry = entries.remove(id) ?: return null
    entry.group.live--
    return Dropped(entry.file, if (entry.group.live <= 0) entry.group else null)
  }

  /**
   * Mette nel registro le copie di [ready] (nell'ordine di [ids]). Se il totale supera [maxBytes] fa posto togliendo prima le copie dello sfoglio meno
   * usate (si rifanno al bisogno), mai quelle consegnate; se non basta, le nuove si tolgono e falso.
   */
  private fun register(ids: List<MediaId>, ready: SharePreparation.Ready, delivered: Boolean): Boolean {
    val group = Group(ready.lease)
    val now = clock.elapsedMs()
    val toDelete = ArrayList<Dropped>()
    val ok = synchronized(lock) {
      // Prima le scadute.
      for (id in entries.keys.toList()) live(id)
      val incoming = ready.files.sumOf { it.size }
      var total = entries.values.sumOf { it.size } + incoming
      if (total > maxBytes) {
        val evictable = entries.values.filter { !it.delivered }.sortedBy { it.expiresAt }
        for (candidate in evictable) {
          if (total <= maxBytes) break
          total -= candidate.size
          drop(candidate.id)?.let { toDelete += it }
        }
      }
      if (total > maxBytes) {
        false
      } else {
        for ((index, shared) in ready.files.withIndex()) {
          val file = shared.file ?: continue
          entries[ids[index]] = Entry(ids[index], file, shared.mime, shared.size, group, now + ttlOf(delivered), delivered)
          group.live++
        }
        group.live > 0
      }
    }
    toDelete.forEach(::delete)
    if (!ok) {
      release(group)
      return false
    }
    startKeeper()
    return true
  }

  private fun delete(dropped: Dropped) {
    val group = dropped.group
    if (group != null) {
      release(group)
    } else {
      // Un media del gruppo esce mentre gli altri restano: se ne va il suo file (e la sua cartella, se e' vuota), subito.
      scope.launch(io) {
        withContext(NonCancellable) {
          dropped.file.delete()
          dropped.file.parentFile?.delete()
        }
      }
    }
  }

  private fun release(group: Group) {
    // Il rilascio cancella i file (anche di qualche GiB): mai sul thread di chi chiama, e non si interrompe.
    scope.launch(io) { withContext(NonCancellable) { group.lease.release() } }
  }

  /** Il custode: ogni tanto toglie le scadute (anche se nessuno le chiede piu') e si ferma quando non resta niente. */
  private fun startKeeper() {
    synchronized(lock) {
      if (keeper?.isActive == true) return
      keeper = scope.launch {
        while (true) {
          delay(keeperPeriodMs)
          // `live` toglie le scadute (e quelle sparite dal disco): quando non resta niente il custode si ferma.
          val left = synchronized(lock) {
            for (id in entries.keys.toList()) live(id)
            entries.size
          }
          if (left == 0) break
        }
      }
    }
  }

  companion object {
    /** Ogni quanto il custode guarda le scadenze. */
    const val KEEPER_PERIOD_MS: Long = 60_000L
  }
}

/** Una copia pronta, letta a intervalli (il descrittore a richiesta del provider la serve come un solo-NAS). */
internal class FileDocumentReader(file: File) : DocumentReader {
  private val raf = RandomAccessFile(file, "r")
  override val size: Long = raf.length()
  private val lock = Any()

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
    if (len <= 0 || offset >= size) return 0
    return withContext(Dispatchers.IO) {
      synchronized(lock) {
        raf.seek(offset)
        val n = raf.read(buf, off, minOf(len.toLong(), size - offset).toInt())
        if (n < 0) 0 else n
      }
    }
  }

  override fun close() = raf.close()
}

/** Il motivo e' di un solo media (si puo' togliere dalla scelta) e non della preparazione tutta (spazio, tetti, NAS spento, guasto). */
internal val ShareStop.isAboutOneMedia: Boolean
  get() = this == ShareStop.CANNOT_REMOVE_LOCATION || this == ShareStop.LOCATION_REMAINS || this == ShareStop.UNAVAILABLE
