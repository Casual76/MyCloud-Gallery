package dev.pampa.mycloudgallery.core.index.work

import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.publish.Nas
import dev.pampa.mycloudgallery.core.index.publish.nasCall
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AcquireResult
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.LeaseManager
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Le PORTE delle unita' di lavoro (D13): il catalogo locale (P11 sopra i DAO di P2), cio' che gli altri hanno gia'
// pubblicato (sopra RemoteIndex, oppure la replica di P4) e le prenotazioni (LeaseManager di core-trust).

/**
 * I media di una share secondo il catalogo locale, per le unita' di lavoro. Le parti si calcolano su **tutti** i media del
 * mese (preparati o no), cosi' finire un'unita' non sposta i confini delle altre: due dispositivi che si guardano dopo
 * ore chiamano ancora con lo stesso nome lo stesso gruppo di media (D13, lavoro doppio ≤ 1%).
 */
interface WorkSource {
  /** I mesi (`aaaa-mm`, `0000-00` per i media senza data) con almeno un media a cui manca qualcosa, dal piu' recente. */
  suspend fun months(share: String, pass: WorkPass): List<String>

  /** I mesi che contengono una delle chiavi visibili sullo schermo ([visible]: `ContentKey.short`). */
  suspend fun monthsOf(share: String, pass: WorkPass, visible: Set<Long>): List<String>

  /**
   * **Tutti** i media del mese [month] in [share], con [WorkItem.missing] = le classi che mancano secondo il catalogo
   * locale (vuoto se non manca niente). L'ordine non conta: si ordinano per chiave di contenuto.
   */
  suspend fun monthItems(share: String, pass: WorkPass, month: String): List<WorkItem>
}

/** Cio' che i dispositivi hanno pubblicato per una chiave di contenuto (D8: vale per coordinare il lavoro). */
data class PublishedProgress(val classes: Set<SegmentClass>, val failedDecoders: Set<String>) {
  companion object {
    val NONE = PublishedProgress(emptySet(), emptySet())
  }
}

/** Cio' che e' gia' pubblicato nell'indice di una share, da rileggere all'inizio di ogni unita' (D13). */
interface PublishedClasses {
  /** Rilegge le teste di [share] (per nome: di solito una richiesta per dispositivo) e i segmenti `meta` nuovi. */
  suspend fun refresh(share: String): RemoteResult<Unit>

  /** Per ogni chiave di [keys], le classi pubblicate e le famiglie di decoder che hanno fallito (D14), dall'ultimo [refresh]. */
  fun progress(share: String, keys: Collection<ContentKey>): Map<ContentKey, PublishedProgress>
}

/**
 * [PublishedClasses] sopra [RemoteIndex]: tiene in memoria, per dispositivo, l'ultima testa accettata e i record `meta`
 * dei suoi segmenti (D5: per percorso vince il `seq` piu' alto; le lapidi tolgono). Una testa rifiutata (revocato,
 * perso, firma: E-I15) toglie i dati di quel dispositivo: i suoi media tornano "da preparare".
 */
class RemotePublishedClasses(
  private val remote: RemoteIndex,
  /** Chiamata a ogni [refresh]: un giro nuovo per il budget delle letture (`NasRemoteIndex.startRound`). */
  private val startRound: () -> Unit = {},
  /**
   * Byte di segmenti `meta` scaricati al massimo per [refresh]: un dispositivo compromesso che elenca migliaia di
   * segmenti enormi costa al piu' questo a ogni giro (i suoi dati restano quelli di prima, il resto al giro dopo).
   */
  private val maxBytesPerRefresh: Long = DEFAULT_MAX_BYTES_PER_REFRESH,
) : PublishedClasses {

  companion object {
    const val DEFAULT_MAX_BYTES_PER_REFRESH: Long = 64L * 1024 * 1024
  }

  private class DeviceState(var mark: HeadMark?, val segments: HashMap<String, List<MetaRecord>> = HashMap()) {
    var progress: Map<ContentKey, PublishedProgress> = emptyMap()
  }

  private val lock = Any()
  private val shares = HashMap<String, HashMap<String, DeviceState>>()

  /** Segmenti `meta` scaricati (per i test: quante letture). */
  @Volatile var segmentReads: Int = 0
    private set

  override suspend fun refresh(share: String): RemoteResult<Unit> {
    startRound()
    val devices = when (val r = remote.devices(share)) {
      is RemoteResult.Ok -> r.value
      is RemoteResult.Failed -> return r
    }
    val known = synchronized(lock) { shares.getOrPut(key(share)) { HashMap() } }
    synchronized(lock) { known.keys.retainAll(devices.toSet()) }
    val budget = longArrayOf(maxBytesPerRefresh)
    for (device in devices) {
      val state = synchronized(lock) { known.getOrPut(device) { DeviceState(null) } }
      when (val r = remote.latestHead(share, device, state.mark)) {
        is RemoteResult.Failed -> return r
        is RemoteResult.Ok -> when (val fetch = r.value) {
          HeadFetch.NoChange, HeadFetch.None -> Unit
          is HeadFetch.Rejected -> synchronized(lock) { known.remove(device) }
          is HeadFetch.Accepted -> {
            val failure = apply(fetch.head, state, budget)
            if (failure != null) return RemoteResult.Failed(failure)
          }
        }
      }
    }
    return RemoteResult.Ok(Unit)
  }

  /** Applica la testa nuova; se il budget non basta a scaricarne i segmenti nuovi, si tiene lo stato di prima (null). */
  private suspend fun apply(head: IndexHeadView, state: DeviceState, budget: LongArray): RemoteFailure? {
    val metas = head.segments.filter { it.cls == SegmentClass.META }
    val missing = metas.filter { synchronized(lock) { it.ref.hash !in state.segments } }.sumOf { it.ref.size }
    if (missing > budget[0]) return null
    budget[0] -= missing
    val fresh = HashMap<String, List<MetaRecord>>()
    for (segment in metas) {
      val cached = synchronized(lock) { state.segments[segment.ref.hash] }
      if (cached != null) {
        fresh[segment.ref.hash] = cached
        continue
      }
      segmentReads++
      when (val r = remote.segment(head, segment)) {
        is RemoteResult.Failed -> return r.failure
        is RemoteResult.Ok -> when (val fetch = r.value) {
          is SegmentFetch.Ok -> fresh[segment.ref.hash] = (fetch.content as? SegmentContent.Meta)?.records.orEmpty()
          // E-I17 e segmenti rifiutati: i loro media restano "da preparare" (nessun dato inventato).
          SegmentFetch.Missing, is SegmentFetch.Rejected -> fresh[segment.ref.hash] = emptyList()
        }
      }
    }
    val byPath = HashMap<String, MetaRecord>()
    for (record in fresh.values.flatten()) {
      val k = IndexKeys.metaHex(record.path)
      val previous = byPath[k]
      if (previous == null || previous.seq < record.seq) byPath[k] = record
    }
    val progress = HashMap<ContentKey, PublishedProgress>()
    for (record in byPath.values) {
      if (record !is MetaRecord.Present) continue
      val file = record.file
      val contentKey = file.contentKey // calcolata a ogni accesso (SHA-256): una volta sola
      val old = progress[contentKey] ?: PublishedProgress.NONE
      progress[contentKey] = PublishedProgress(
        old.classes + file.preparedClasses,
        old.failedDecoders + listOfNotNull(file.failure?.decoder),
      )
    }
    synchronized(lock) {
      state.mark = head.mark
      state.segments.clear()
      state.segments.putAll(fresh)
      state.progress = progress
    }
    return null
  }

  override fun progress(share: String, keys: Collection<ContentKey>): Map<ContentKey, PublishedProgress> = synchronized(lock) {
    val devices = shares[key(share)]?.values.orEmpty()
    keys.associateWith { k ->
      devices.fold(PublishedProgress.NONE) { acc, d ->
        val p = d.progress[k] ?: return@fold acc
        PublishedProgress(acc.classes + p.classes, acc.failedDecoders + p.failedDecoders)
      }
    }
  }

  private fun key(share: String) = share.lowercase(Locale.ROOT)
}

/** Le prenotazioni delle unita' (§5.6, §6.2): durata 1 ora, una generazione nuova a ogni presa o rinnovo. */
interface WorkLeases {
  suspend fun acquire(share: String, resource: String): AcquireResult

  suspend fun release(share: String, resource: String)
}

/**
 * [WorkLeases] sopra `LeaseManager` di core-trust: firme, generazioni a gruppi, solo l'ora del NAS. Un errore del NAS o
 * un registro che non ci fa scrivere danno [AcquireResult.Contended] (si lavora senza prenotazione e si conta).
 */
class TrustWorkLeases(
  private val fileSystem: (String) -> NasFileSystem,
  private val signer: Signer,
  private val clock: NasClock,
  private val registry: suspend () -> RegistryState?,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkLeases {

  private val me = TrustIds.deviceIdOf(signer.publicKey)

  override suspend fun acquire(share: String, resource: String): AcquireResult {
    val state = registry()?.takeIf { it.canWrite(me) } ?: return AcquireResult.Contended
    val manager = LeaseManager(fileSystem(share), signer, clock)
    return when (val r = nasCall(io) { manager.tryAcquire(state, resource, TrustLimits.MAX_LEASE_MILLIS) }) {
      is Nas.Ok -> r.value
      is Nas.Failed -> AcquireResult.Contended
    }
  }

  override suspend fun release(share: String, resource: String) {
    val state = registry()?.takeIf { it.canWrite(me) } ?: return
    val manager = LeaseManager(fileSystem(share), signer, clock)
    nasCall(io) { manager.release(state, resource) }
  }
}
