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
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
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

/**
 * Cio' che i dispositivi hanno pubblicato per una chiave di contenuto (D8: vale per coordinare il lavoro).
 *
 * @property failedBy per ogni famiglia di decoder fallita, i dispositivi che lo dichiarano (R5: chi prepara non crede a un
 *   altro dispositivo che dice "la tua famiglia ha fallito", vedi `LeasedWorkUnits`).
 */
data class PublishedProgress(
  val classes: Set<SegmentClass>,
  val failedDecoders: Set<String>,
  val failedBy: Map<String, Set<String>> = emptyMap(),
) {
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
 *
 * **Classi verificate** (R5 della revisione 4A). La maschera `prep` di un record e' solo cio' che il dispositivo dichiara: un
 * dispositivo compromesso che dichiara "tutto preparato" senza pubblicare un blob fermerebbe per sempre la preparazione di
 * tutta la share. Le classi di un record contano solo se **quel** dispositivo ha davvero pubblicato la miniatura S di quella
 * chiave in un segmento `thumbS` elencato dalla sua testa: la preparazione produce tutte le classi in un passaggio solo (D12),
 * quindi chi non ha la S non ha preparato niente. Le chiavi dei segmenti `thumbS` si prendono dalla replica locale
 * ([localBlobKeys], senza rete) o, se la replica non li ha ancora, dalla tabella del segmento scaricato (nel budget del giro,
 * una volta per segmento: il nome e' l'impronta). Finche' una S non e' verificata il media resta "da preparare": al peggio
 * lavoro doppio, mai lavoro perso.
 */
class RemotePublishedClasses(
  private val remote: RemoteIndex,
  /** Chiamata a ogni [refresh]: un giro nuovo per il budget delle letture (`NasRemoteIndex.startRound`). */
  private val startRound: () -> Unit = {},
  /**
   * Byte di segmenti scaricati al massimo per [refresh] (`meta` e tabelle `thumbS`): un dispositivo compromesso che elenca
   * migliaia di segmenti enormi costa al piu' questo a ogni giro (i suoi dati restano quelli di prima, il resto al giro dopo).
   */
  private val maxBytesPerRefresh: Long = DEFAULT_MAX_BYTES_PER_REFRESH,
  /**
   * Le chiavi (attestate) di un segmento d'immagini che la replica locale ha gia' applicato: (share, dispositivo, impronta,
   * classe) → chiavi, o null se non lo conosce. `:app` la lega a `LocalReplica.blobKeysOfSegment`.
   */
  private val localBlobKeys: (suspend (share: String, device: String, segment: String, cls: SegmentClass) -> Set<ContentKey>?)? = null,
) : PublishedClasses {

  companion object {
    const val DEFAULT_MAX_BYTES_PER_REFRESH: Long = 64L * 1024 * 1024

    /** La classe che prova che un dispositivo ha preparato davvero una chiave (D12: un passaggio solo, la S c'e' sempre). */
    val EVIDENCE_CLASS: SegmentClass = SegmentClass.THUMB_S
  }

  private class DeviceState(val device: String, var mark: HeadMark?, val segments: HashMap<String, List<MetaRecord>> = HashMap()) {
    var progress: Map<ContentKey, PublishedProgress> = emptyMap()

    /** L'ultima testa accettata (per riscaricare una tabella `thumbS` senza rileggerla). */
    var head: IndexHeadView? = null

    /** I segmenti `thumbS` elencati dalla testa corrente. */
    var thumbs: List<ClassedSegment> = emptyList()

    /** Le chiavi verificate di ogni segmento `thumbS` (per impronta: un segmento non cambia mai). */
    val thumbKeys: HashMap<String, Set<ContentKey>> = HashMap()

    val unverified: Boolean get() = thumbs.any { it.ref.hash !in thumbKeys }
  }

  private val lock = Any()
  private val shares = HashMap<String, HashMap<String, DeviceState>>()

  /** Segmenti `meta` scaricati (per i test: quante letture). */
  @Volatile var segmentReads: Int = 0
    private set

  /** Tabelle `thumbS` scaricate per verificare le classi (R5). */
  @Volatile var evidenceReads: Int = 0
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
      val state = synchronized(lock) { known.getOrPut(device) { DeviceState(device, null) } }
      when (val r = remote.latestHead(share, device, state.mark)) {
        is RemoteResult.Failed -> return r
        is RemoteResult.Ok -> when (val fetch = r.value) {
          HeadFetch.NoChange, HeadFetch.None -> {
            // Una testa gia' nota con tabelle `thumbS` non ancora verificate (budget finito o replica indietro): si riprova.
            if (synchronized(lock) { state.unverified }) {
              val failure = verify(share, device, state, budget)
              recompute(state)
              if (failure != null) return RemoteResult.Failed(failure)
            }
          }
          is HeadFetch.Rejected -> synchronized(lock) { known.remove(device) }
          is HeadFetch.Accepted -> {
            val failure = apply(share, fetch.head, state, budget)
            if (failure != null) return RemoteResult.Failed(failure)
          }
        }
      }
    }
    return RemoteResult.Ok(Unit)
  }

  /** Applica la testa nuova; se il budget non basta a scaricarne i segmenti `meta` nuovi, si tiene lo stato di prima (null). */
  private suspend fun apply(share: String, head: IndexHeadView, state: DeviceState, budget: LongArray): RemoteFailure? {
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
    val thumbs = head.segments.filter { it.cls == EVIDENCE_CLASS }
    synchronized(lock) {
      state.mark = head.mark
      state.head = head
      state.segments.clear()
      state.segments.putAll(fresh)
      state.thumbs = thumbs
      state.thumbKeys.keys.retainAll(thumbs.mapTo(HashSet()) { it.ref.hash })
    }
    val failure = verify(share, head.deviceId, state, budget)
    recompute(state)
    return failure
  }

  /**
   * R5: le chiavi dei segmenti `thumbS` della testa corrente, dalla replica locale o dalla tabella del segmento (nel
   * budget). Un segmento assente o rifiutato non verifica niente.
   */
  private suspend fun verify(share: String, device: String, state: DeviceState, budget: LongArray): RemoteFailure? {
    val (head, todo) = synchronized(lock) { state.head to state.thumbs.filter { it.ref.hash !in state.thumbKeys } }
    if (head == null) return null
    for (segment in todo) {
      val local = localBlobKeys?.invoke(key(share), device, segment.ref.hash, EVIDENCE_CLASS)
      if (local != null) {
        synchronized(lock) { state.thumbKeys[segment.ref.hash] = local }
        continue
      }
      if (segment.ref.size > budget[0]) continue
      budget[0] -= segment.ref.size
      evidenceReads++
      val keys = when (val r = remote.segment(head, segment)) {
        is RemoteResult.Failed -> return r.failure
        is RemoteResult.Ok -> when (val fetch = r.value) {
          is SegmentFetch.Ok ->
            (fetch.content as? SegmentContent.Blobs)?.takeIf { it.cls == EVIDENCE_CLASS }?.entries?.mapTo(HashSet()) { it.contentKey }.orEmpty()
          SegmentFetch.Missing, is SegmentFetch.Rejected -> emptySet()
        }
      }
      synchronized(lock) { state.thumbKeys[segment.ref.hash] = keys }
    }
    return null
  }

  /** I progressi del dispositivo dai suoi record correnti: classi solo per le chiavi con la S verificata (R5). */
  private fun recompute(state: DeviceState) {
    synchronized(lock) {
      val byPath = HashMap<String, MetaRecord>()
      for (record in state.segments.values.flatten()) {
        val k = IndexKeys.metaHex(record.path)
        val previous = byPath[k]
        if (previous == null || previous.seq < record.seq) byPath[k] = record
      }
      val verified = HashSet<ContentKey>()
      state.thumbKeys.values.forEach { verified += it }
      val progress = HashMap<ContentKey, PublishedProgress>()
      for (record in byPath.values) {
        if (record !is MetaRecord.Present) continue
        val file = record.file
        val contentKey = file.contentKey // calcolata a ogni accesso (SHA-256): una volta sola
        val old = progress[contentKey] ?: PublishedProgress.NONE
        val classes = if (contentKey in verified) file.preparedClasses else emptySet()
        val failed = file.failure?.decoder
        progress[contentKey] = PublishedProgress(
          old.classes + classes,
          old.failedDecoders + listOfNotNull(failed),
          if (failed == null) old.failedBy else old.failedBy + (failed to setOf(state.device)),
        )
      }
      state.progress = progress
    }
  }

  override fun progress(share: String, keys: Collection<ContentKey>): Map<ContentKey, PublishedProgress> = synchronized(lock) {
    val devices = shares[key(share)]?.values.orEmpty()
    keys.associateWith { k ->
      devices.fold(PublishedProgress.NONE) { acc, d ->
        val p = d.progress[k] ?: return@fold acc
        val by = HashMap(acc.failedBy)
        p.failedBy.forEach { (family, devices) -> by[family] = by[family].orEmpty() + devices }
        PublishedProgress(acc.classes + p.classes, acc.failedDecoders + p.failedDecoders, by)
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
