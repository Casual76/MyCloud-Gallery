package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.sync.DeviceStanding
import dev.pampa.mycloudgallery.core.index.sync.DeviceStandings
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La REPLICA dei dati AI (Fase 6, P2; D7; formato-nas.md §5.5-bis "Attestazione e fusione"): legge le teste `ai` di ogni dispositivo
 * della share, scarica i segmenti nuovi verificati, li tiene per (share, dispositivo, segmento) in [store], attesta e fonde, e consegna
 * all'indice locale ([sink]) **esattamente** i vincitori correnti. Una per processo; le chiamate si serializzano.
 *
 * Si usa in due modi:
 * - nel giro di `IndexSyncEngine`, dopo le teste `index` di ogni share ([afterIndex], aggancio del §1.4): stessi dispositivi, stesso
 *   budget di tempo;
 * - all'inizio di ogni unita' di lavoro ([refresh], da [AiPublishedClasses], D9): si rileggono le teste `ai` della share per saltare
 *   cio' che altri hanno gia' pubblicato.
 *
 * **Regole**:
 * - teste per nome con lo stesso anti-rollback delle teste `index` (per (share, dispositivo, tipo)); una testa rifiutata non cambia
 *   niente; revocati, persi o sconosciuti: dimenticati (E-I15), dismessi: si tiene cio' che c'era;
 * - un segmento assente o rifiutato (E-I17, E-I20) non ferma gli altri: le sue voci mancano finche' il dispositivo non ripubblica;
 * - al piu' [maxBytesPerShare] byte di segmenti per share e per chiamata (il resto resta "in attesa" e si riprende), al piu'
 *   [maxBytesPerDevice] byte elencati e [maxEntriesPerDevice] voci per dispositivo e share (un dispositivo compromesso non riempie
 *   il telefono);
 * - **riattestazione a ogni giro**: una voce vale solo con un record `meta` vincitore della stessa share e della stessa chiave intera
 *   ([attestation]); quando l'attestazione cambia (un file nuovo, uno sparito, un dispositivo dimenticato) la voce entra o esce;
 * - fusione con [AiFusion]: ricetta piu' alta, poi id del dispositivo minore; disaccordi contati solo per le voci appena arrivate.
 *
 * **Ripresa**: ogni testa si applica in una transazione che segna "da rifondere" le chiavi toccate; la consegna all'indice locale si
 * conferma dopo ([AiReplicaStore.commitApplied]): una morte del processo in mezzo si recupera al giro dopo (le chiamate di [sink] sono
 * idempotenti).
 *
 * **Revisione A della Fase 6:**
 * - R2: un errore qualunque (non un annullamento) resta qui: si conta (`syncErrors`) e la share risulta incompleta, ma il giro
 *   dell'indice, le miniature e le tappe del calcolo vanno avanti; le consegne all'indice locale vanno a pezzi da al piu'
 *   [APPLY_BATCH_BYTES] byte di corpi, e ogni segmento scaricato si salva da solo (in memoria al piu' un segmento);
 * - E-I17 per le teste `ai` (R14): i segmenti che una testa nuova non elenca piu' (compattati) restano finche' quelli nuovi non sono
 *   scaricati tutti: chi legge non perde le voci a meta' strada;
 * - R16: le ricette oltre [knownRecipe] valgono come quella nella fusione;
 * - I10: una testa rifiutata perche' di un altro account (area privata) toglie cio' che quel dispositivo aveva qui;
 * - R5: un dispositivo revocato, perso o sconosciuto si dimentica anche nell'indice locale ([AiReplicaSink.forgetDevice]): via anche cio'
 *   che questo dispositivo ha calcolato sulle sue anteprime.
 */
class AiShareSync(
  private val remote: AiRemoteIndex,
  private val store: AiReplicaStore,
  private val attestation: AiAttestation,
  private val sink: AiReplicaSink,
  private val standings: DeviceStandings,
  val counters: AiIndexCounters = AiIndexCounters(),
  /** I dispositivi di `devices/` della share, per [refresh] fuori dal giro (`NasRemoteIndex.devices`). */
  private val listDevices: (suspend (String) -> RemoteResult<List<String>>)? = null,
  /** Chiamata all'inizio di ogni share: un giro nuovo per il budget delle letture delle teste (`NasAiRemoteIndex.startRound`). */
  private val startRound: () -> Unit = {},
  private val maxBytesPerShare: Long = DEFAULT_MAX_BYTES_PER_SHARE,
  private val maxBytesPerDevice: Long = MAX_BYTES_PER_DEVICE_SHARE,
  private val maxEntriesPerDevice: Int = MAX_ENTRIES_PER_DEVICE_SHARE,
  /** R16: la ricetta piu' alta che quest'app conosce per ogni classe; di serie nessun tetto. */
  private val knownRecipe: (AiClass) -> Int = { AiFusion.ANY_RECIPE },
) : AiSyncHook {

  private val mutex = Mutex()

  override suspend fun afterIndex(share: String, devices: List<String>, timeUp: () -> Boolean): AiSyncOutcome =
    mutex.withLock { guarded { syncLocked(share, devices, timeUp) } ?: AiSyncOutcome(null, complete = false) }

  /**
   * R2: un errore che non e' un annullamento (indice locale che non accetta, database, un dato che non ci si aspettava) non esce di qui:
   * si conta e il giro prosegue con le altre share, le miniature e il calcolo; le chiavi restano "da rifondere" e si riprovano al giro
   * dopo. Null = errore.
   */
  private inline fun <T> guarded(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    counters.syncErrors.incrementAndGet()
    null
  } catch (e: OutOfMemoryError) {
    counters.syncErrors.incrementAndGet()
    null
  }

  /**
   * Rilegge le teste `ai` di [share] (di solito una richiesta per dispositivo) e consegna i vincitori: per [AiPublishedClasses]
   * all'inizio di un'unita' di lavoro. Senza [listDevices] usa i dispositivi gia' noti.
   */
  suspend fun refresh(share: String): RemoteResult<Unit> {
    val devices = when (val list = listDevices) {
      null -> store.heads(share.lowercase(Locale.ROOT)).map { it.deviceId }
      else -> when (val r = list(share)) {
        is RemoteResult.Ok -> r.value
        is RemoteResult.Failed -> return r
      }
    }
    val outcome = mutex.withLock { guarded { syncLocked(share, devices) { false } } } ?: return RemoteResult.Failed(RemoteFailure.OTHER)
    return outcome.failure?.let { RemoteResult.Failed(it) } ?: RemoteResult.Ok(Unit)
  }

  /** E-I8: la share e' stata tolta dalla replica dell'indice: via anche i suoi dati AI, qui e nell'indice locale. */
  override suspend fun shareDropped(share: String) {
    mutex.withLock {
      guarded {
        store.forgetShare(share.lowercase(Locale.ROOT))
        sink.forgetShare(share)
      }
    }
  }

  /** Solo la fusione (senza NAS): dopo un cambiamento locale dei record `meta` (per esempio un file nuovo visto qui). */
  suspend fun reattest(share: String) {
    mutex.withLock { guarded { fuse(share, share.lowercase(Locale.ROOT), emptySet()) } }
  }

  /**
   * R15: l'indice locale ha perso [keys] (record rovinato, registro rotto): il segno "consegnato" di quelle chiavi si toglie in ogni share,
   * cosi' la fusione del giro dopo le riconsegna anche se il vincitore e' lo stesso di prima (prima passava oltre e il media restava fuori
   * dalla ricerca). Null = tutte le chiavi.
   */
  suspend fun redeliver(keys: Collection<dev.pampa.mycloudgallery.core.model.ContentKey>?) {
    mutex.withLock { AiRedelivery(store).redeliver(keys) }
  }

  // --- Teste --------------------------------------------------------------------------------------------------------------

  private class Round(val share: String, val key: String, val timeUp: () -> Boolean, budget: Long) {
    var bytesLeft = budget
    var failure: RemoteFailure? = null
    var complete = true
    val touched = HashSet<AiKey>()
  }

  private suspend fun syncLocked(share: String, devices: List<String>, timeUp: () -> Boolean): AiSyncOutcome {
    startRound()
    val round = Round(share, share.lowercase(Locale.ROOT), timeUp, maxBytesPerShare)
    val known = store.heads(round.key).associateBy { it.deviceId }
    val listed = devices.distinct().sorted().take(TrustLimits.MAX_INDEX_DEVICES_PER_SHARE)
    for (device in listed) {
      if (round.timeUp()) {
        round.complete = false
        break
      }
      val head = known[device]
      when (val read = remote.latestHead(share, device, head?.mark)) {
        is RemoteResult.Failed -> {
          round.failure = read.failure
          round.complete = false
          if (read.failure == RemoteFailure.UNREACHABLE) break
        }
        is RemoteResult.Ok -> when (val fetch = read.value) {
          HeadFetch.None -> Unit
          HeadFetch.NoChange -> if (head != null) resume(round, head)
          is HeadFetch.Rejected -> onRejected(round, device, fetch.reason, known = head != null)
          is HeadFetch.Accepted -> apply(round, fetch.head, head)
        }
      }
      if (round.failure == RemoteFailure.UNREACHABLE) break
    }
    // Dispositivi che hanno dati qui ma non compaiono piu' in `devices/`: approvati o dismessi = la replica resta (E-I5);
    // revocati, persi o sconosciuti = i loro dati escono (E-I15).
    if (round.failure != RemoteFailure.UNREACHABLE) {
      val present = listed.toHashSet()
      for (device in known.keys) {
        if (device in present) continue
        when (standings.standing(device)) {
          DeviceStanding.APPROVED, DeviceStanding.RETIRED -> Unit
          DeviceStanding.REVOKED, DeviceStanding.LOST, DeviceStanding.UNKNOWN -> forget(round, device)
        }
      }
    }
    fuse(share, round.key, round.touched)
    return AiSyncOutcome(round.failure, round.complete)
  }

  private suspend fun onRejected(round: Round, device: String, reason: RejectReason, known: Boolean) {
    when (reason) {
      RejectReason.ROLLBACK, RejectReason.EQUIVOCATION -> counters.rollbacks.incrementAndGet()
      else -> counters.reject(reason)
    }
    if (!known) return
    when (reason) {
      RejectReason.SIGNER_REVOKED, RejectReason.SIGNER_NOT_APPROVED -> forget(round, device)
      // I10: una testa `ai` di un altro account in un'area privata: via i suoi dati da questa share (solo qui: altrove puo' valere).
      RejectReason.NOT_OWNER -> forget(round, device, everywhere = false)
      else -> Unit
    }
  }

  /**
   * I dati di [device] in questa share escono (le chiavi toccate si rifondono). Con [everywhere] (revocato, perso, sconosciuto: R5) il
   * dispositivo si dimentica anche nell'indice locale, con cio' che questo dispositivo ha calcolato sulle sue anteprime.
   */
  private suspend fun forget(round: Round, device: String, everywhere: Boolean = true) {
    round.touched += store.forgetDevice(round.key, device)
    counters.devicesForgotten.incrementAndGet()
    if (everywhere) sink.forgetDevice(device)
  }

  /** Una testa nuova: i segmenti elencati non ancora applicati si scaricano nel budget, gli altri restano in attesa. */
  private suspend fun apply(round: Round, head: IndexHeadView, known: HeadRow?) {
    val listed = head.segments.filter { AiClass.of(it.cls) != null }.distinctBy { it.ref.hash }
    if (listed.sumOf { it.ref.size } > maxBytesPerDevice) {
      // Oltre il tetto per dispositivo: la testa non si applica (si tiene lo stato di prima), come un record di troppo (M9).
      counters.reject(RejectReason.TOO_LARGE)
      return
    }
    val previous = store.segments(round.key, head.deviceId).associateBy { it.hash }
    val hashes = listed.mapTo(HashSet()) { it.ref.hash }
    val rows = listed.map { segment ->
      val before = previous[segment.ref.hash]
      if (before?.state == SegmentState.APPLIED) before else AiSegmentRow(head.deviceId, segment.ref.hash, AiClass.of(segment.cls)!!, segment.ref.size, SegmentState.PENDING)
    }
    // E-I17 (R14): i segmenti applicati che la testa nuova non elenca piu' (una compattazione) restano finche' i nuovi non ci sono tutti.
    val retired = previous.values.filter { it.hash !in hashes && it.state == SegmentState.APPLIED }
    download(round, headRow(round.key, head), known, head, rows, retired, retryUnavailable = true)
  }

  /**
   * Nessuna testa nuova: si riprendono i segmenti rimasti in attesa (budget finito al giro prima). Quelli assenti o rifiutati no: si
   * riprovano solo con una testa nuova del dispositivo (E-I17, come le teste `index`).
   */
  private suspend fun resume(round: Round, head: HeadRow) {
    val rows = store.segments(round.key, head.deviceId)
    if (rows.none { it.state == SegmentState.PENDING }) return
    val view = IndexHeadView(
      share = round.share,
      deviceId = head.deviceId,
      version = head.version,
      registryVersion = head.registryVersion,
      createdAtNasMillis = head.createdAtNasMillis,
      mark = head.mark,
      segments = rows.map { ClassedSegment(it.cls.segmentClass, SegmentRef(it.hash, it.size)) }.sortedBy { it.ref.hash },
      registryBehind = false,
    )
    download(round, head, head, view, rows, emptyList(), retryUnavailable = false)
  }

  /**
   * Scarica i segmenti [rows] in attesa di [view] (con [retryUnavailable] anche quelli assenti o rifiutati) nel budget. Ogni segmento
   * scaricato si salva subito con le sue voci (R3: in memoria al piu' un segmento); finche' qualcuno resta in attesa, la testa salvata
   * resta [known] (quella di prima) e i segmenti [retired] restano con le loro voci (E-I17). Il tetto delle voci per dispositivo conta
   * quelle dei segmenti che restano applicati piu' quelle appena scaricate.
   */
  private suspend fun download(
    round: Round,
    row: HeadRow,
    known: HeadRow?,
    view: IndexHeadView,
    rows: List<AiSegmentRow>,
    retired: List<AiSegmentRow>,
    retryUnavailable: Boolean,
  ) {
    var count = rows.filter { it.state == SegmentState.APPLIED }.sumOf { it.entries.toLong() }
    val states = LinkedHashMap<String, AiSegmentRow>()
    rows.forEach { states[it.hash] = it }
    // La testa da salvare finche' qualcosa resta in attesa: quella di prima, se c'era (il giro dopo rilegge la nuova e riprende).
    val provisional = known ?: row
    fun listedNow(): List<AiSegmentRow> = states.values.toList() + retired
    var stop = false
    for (segment in rows.sortedBy { it.hash }) {
      if (segment.state == SegmentState.APPLIED || (!retryUnavailable && segment.state != SegmentState.PENDING)) continue
      if (stop || round.timeUp() || segment.size > round.bytesLeft) {
        round.complete = false
        states[segment.hash] = segment.copy(state = SegmentState.PENDING)
        continue
      }
      val classed = ClassedSegment(segment.cls.segmentClass, SegmentRef(segment.hash, segment.size))
      when (val read = remote.segment(view, classed)) {
        is RemoteResult.Failed -> {
          round.failure = read.failure
          round.complete = false
          stop = true
          states[segment.hash] = segment.copy(state = SegmentState.PENDING)
        }
        is RemoteResult.Ok -> when (val fetch = read.value) {
          is AiSegmentFetch.Ok -> {
            round.bytesLeft -= segment.size
            val content = fetch.content
            if (content.cls != segment.cls || count + content.entries.size > maxEntriesPerDevice) {
              counters.reject(if (content.cls != segment.cls) RejectReason.WRONG_TYPE else RejectReason.TOO_LARGE)
              states[segment.hash] = segment.copy(state = SegmentState.REJECTED)
            } else {
              count += content.entries.size
              val entries = content.entries.map {
                AiReplicaEntry(view.deviceId, segment.hash, AiKey(it.contentKey, content.cls), it.body.recipe, it.digest, it.bodyBytes)
              }
              states[segment.hash] = segment.copy(state = SegmentState.APPLIED, entries = content.entries.size)
              round.touched += store.applyHead(AiHeadApplication(provisional, listedNow(), mapOf(segment.hash to entries)))
            }
          }
          AiSegmentFetch.Missing -> {
            counters.segmentsMissing.incrementAndGet()
            states[segment.hash] = segment.copy(state = SegmentState.MISSING)
          }
          is AiSegmentFetch.Rejected -> {
            counters.reject(fetch.reason)
            states[segment.hash] = segment.copy(state = SegmentState.REJECTED)
          }
        }
      }
    }
    val done = states.values.none { it.state == SegmentState.PENDING }
    round.touched += if (done || known == null) {
      store.applyHead(AiHeadApplication(row, states.values.toList(), emptyMap()))
    } else {
      store.applyHead(AiHeadApplication(provisional, listedNow(), emptyMap()))
    }
  }

  private fun headRow(key: String, head: IndexHeadView) =
    HeadRow(key, head.deviceId, head.version, head.mark.contentHash, head.registryVersion, head.createdAtNasMillis)

  // --- Attestazione e fusione ---------------------------------------------------------------------------------------------

  /**
   * Rifonde le chiavi da rifondere e quelle la cui attestazione e' cambiata rispetto a cio' che l'indice locale ha, e consegna i
   * cambiamenti. [touchedByHeads]: le chiavi con voci appena arrivate (solo per loro si contano i disaccordi).
   */
  private suspend fun fuse(share: String, key: String, touchedByHeads: Set<AiKey>) {
    val all = store.keys(key)
    val applied = store.allApplied(key).associateBy { it.key }
    val contentKeys = HashSet<dev.pampa.mycloudgallery.core.model.ContentKey>()
    all.forEach { contentKeys += it.contentKey }
    applied.keys.forEach { contentKeys += it.contentKey }
    val attested = if (contentKeys.isEmpty()) emptySet() else attestation.attested(share, contentKeys)
    val todo = HashSet<AiKey>(store.dirty(key))
    for (k in all) if ((k.contentKey in attested) != (k in applied)) todo += k
    for (k in applied.keys) if (k.contentKey !in attested || k !in all) todo += k
    if (todo.isEmpty()) return
    val ordered = todo.sortedWith(compareBy<AiKey> { it.contentKey.hex }.thenBy { it.cls.code })
    for (chunk in ordered.chunked(FUSION_BATCH)) {
      val candidates = store.candidates(key, chunk).groupBy { it.key }
      val toApply = ArrayList<AiWinner>()
      var applyBytes = 0L
      val toWithdraw = ArrayList<AiKey>()
      val marks = ArrayList<AiAppliedMark>()
      for (k in chunk) {
        val list = candidates[k].orEmpty()
        val old = applied[k]
        val known = knownRecipe(k.cls)
        val winner = AiFusion.winner(list, k.contentKey in attested, known)
        if (winner == null) {
          if (old != null) toWithdraw += k
          continue
        }
        val mark = AiAppliedMark(k, winner.deviceId, winner.recipe, winner.digest)
        val countDisagreement = k.cls == AiClass.VEC && k in touchedByHeads
        if (mark == old && !countDisagreement) {
          marks += mark
          continue
        }
        val bytes = store.body(key, winner)
        val body = bytes?.let { AiSegments.decodeBody(winner.key.cls, it).acceptedOrNull() }
        if (body == null) {
          // Mai, salvo un database rovinato: niente vincitore e la voce esce.
          if (old != null) toWithdraw += k
          continue
        }
        if (countDisagreement) countDisagreements(key, winner, body as VecBody, list, known)
        marks += mark
        if (mark != old) {
          toApply += AiWinner(share, entryOf(k, body), winner.recipe, winner.deviceId)
          applyBytes += bytes.size
          // R2: all'indice locale a pezzi di al piu' APPLY_BATCH_BYTES di corpi (mai un blocco di testi da centinaia di MB).
          if (applyBytes >= APPLY_BATCH_BYTES) {
            sink.apply(share, toApply.toList())
            toApply.clear()
            applyBytes = 0
          }
        }
      }
      if (toApply.isNotEmpty()) sink.apply(share, toApply)
      if (toWithdraw.isNotEmpty()) sink.withdraw(share, toWithdraw)
      store.commitApplied(key, chunk, marks)
    }
  }

  private suspend fun bodyOf(key: String, candidate: AiCandidate): AiBody? =
    store.body(key, candidate)?.let { AiSegments.decodeBody(candidate.key.cls, it).acceptedOrNull() }

  private suspend fun countDisagreements(key: String, winner: AiCandidate, body: VecBody, candidates: List<AiCandidate>, known: Int) {
    for (rival in AiFusion.rivals(winner, candidates, known)) {
      val other = bodyOf(key, rival) as? VecBody ?: continue
      if (AiFusion.disagree(body, other)) counters.disagree.incrementAndGet()
    }
  }

  private fun entryOf(key: AiKey, body: AiBody): AiEntry = when (body) {
    is VecBody -> AiEntry.Vector(key.contentKey, body)
    is OcrBody -> AiEntry.Text(key.contentKey, body)
  }

  companion object {
    /** Byte di segmenti `ai` scaricati al massimo per share e per chiamata: il resto al giro dopo. */
    const val DEFAULT_MAX_BYTES_PER_SHARE: Long = 128L * 1024 * 1024

    /**
     * Byte di segmenti `ai` elencati al massimo da un dispositivo in una share: 262.144 file (il tetto dei record, M9) con un vettore
     * e un testo ciascuno stanno sotto 1 GiB; oltre, la testa non si applica.
     */
    const val MAX_BYTES_PER_DEVICE_SHARE: Long = 2L * 1024 * 1024 * 1024

    /** Voci al massimo per dispositivo e share: un `vec` e un `ocr` per ognuno dei 262.144 record possibili (M9). */
    const val MAX_ENTRIES_PER_DEVICE_SHARE: Int = 2 * TrustLimits.MAX_RECORDS_PER_DEVICE_SHARE

    /** Chiavi rifuse per transazione. */
    const val FUSION_BATCH: Int = 2_000

    /** R2: byte di corpi al piu' per consegna all'indice locale (un pezzo di una transazione della fusione). */
    const val APPLY_BATCH_BYTES: Long = 4L * 1024 * 1024
  }
}

/**
 * R15: chi ripara l'indice locale chiede di riavere delle chiavi. La fusione consegna solo cio' che cambia rispetto al segno "consegnato"
 * (`ai_replica_applied`): se il vincitore e' lo stesso di prima non consegnerebbe niente e il dato perso non tornerebbe piu'. Qui si
 * toglie il segno (e il "da rifondere": la chiave attestata senza segno si rifonde comunque) in ogni share: il giro dopo riconsegna.
 */
class AiRedelivery(private val store: AiReplicaStore) {
  /** [keys] null = tutte le chiavi di ogni share (il registro dell'indice e' andato perso). */
  suspend fun redeliver(keys: Collection<dev.pampa.mycloudgallery.core.model.ContentKey>?) {
    for (share in store.shares()) {
      val wanted = if (keys == null) {
        store.allApplied(share).map { it.key }
      } else {
        keys.flatMap { k -> AiClass.entries.map { AiKey(k, it) } }
      }
      if (wanted.isEmpty()) continue
      for (chunk in wanted.chunked(AiShareSync.FUSION_BATCH)) store.commitApplied(share, chunk, emptyList())
    }
  }
}
