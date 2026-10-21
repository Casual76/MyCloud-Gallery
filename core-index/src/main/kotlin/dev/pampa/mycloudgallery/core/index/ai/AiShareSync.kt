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
) : AiSyncHook {

  private val mutex = Mutex()

  override suspend fun afterIndex(share: String, devices: List<String>, timeUp: () -> Boolean): AiSyncOutcome =
    mutex.withLock { syncLocked(share, devices, timeUp) }

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
    val outcome = mutex.withLock { syncLocked(share, devices) { false } }
    return outcome.failure?.let { RemoteResult.Failed(it) } ?: RemoteResult.Ok(Unit)
  }

  /** E-I8: la share e' stata tolta dalla replica dell'indice: via anche i suoi dati AI, qui e nell'indice locale. */
  override suspend fun shareDropped(share: String) {
    mutex.withLock {
      store.forgetShare(share.lowercase(Locale.ROOT))
      sink.forgetShare(share)
    }
  }

  /** Solo la fusione (senza NAS): dopo un cambiamento locale dei record `meta` (per esempio un file nuovo visto qui). */
  suspend fun reattest(share: String) {
    mutex.withLock { fuse(share, share.lowercase(Locale.ROOT), emptySet()) }
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
          is HeadFetch.Accepted -> apply(round, fetch.head)
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
    if (known && (reason == RejectReason.SIGNER_REVOKED || reason == RejectReason.SIGNER_NOT_APPROVED)) forget(round, device)
  }

  private suspend fun forget(round: Round, device: String) {
    round.touched += store.forgetDevice(round.key, device)
    counters.devicesForgotten.incrementAndGet()
  }

  /** Una testa nuova: i segmenti elencati non ancora applicati si scaricano nel budget, gli altri restano in attesa. */
  private suspend fun apply(round: Round, head: IndexHeadView) {
    val listed = head.segments.filter { AiClass.of(it.cls) != null }.distinctBy { it.ref.hash }
    if (listed.sumOf { it.ref.size } > maxBytesPerDevice) {
      // Oltre il tetto per dispositivo: la testa non si applica (si tiene lo stato di prima), come un record di troppo (M9).
      counters.reject(RejectReason.TOO_LARGE)
      return
    }
    val previous = store.segments(round.key, head.deviceId).associateBy { it.hash }
    val rows = listed.map { segment ->
      val before = previous[segment.ref.hash]
      if (before?.state == SegmentState.APPLIED) before else AiSegmentRow(head.deviceId, segment.ref.hash, AiClass.of(segment.cls)!!, segment.ref.size, SegmentState.PENDING)
    }
    download(round, headRow(round.key, head), head, rows, retryUnavailable = true)
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
    download(round, head, view, rows, retryUnavailable = false)
  }

  /**
   * Scarica i segmenti [rows] in attesa di [view] (con [retryUnavailable] anche quelli assenti o rifiutati) nel budget e applica in
   * una transazione. Il tetto delle voci per dispositivo conta quelle dei segmenti che restano applicati piu' quelle appena scaricate.
   */
  private suspend fun download(round: Round, row: HeadRow, view: IndexHeadView, rows: List<AiSegmentRow>, retryUnavailable: Boolean) {
    var count = rows.filter { it.state == SegmentState.APPLIED }.sumOf { it.entries.toLong() }
    val out = ArrayList<AiSegmentRow>(rows.size)
    val entries = HashMap<String, List<AiReplicaEntry>>()
    var stop = false
    for (segment in rows.sortedBy { it.hash }) {
      if (segment.state == SegmentState.APPLIED || (!retryUnavailable && segment.state != SegmentState.PENDING)) {
        out += segment
        continue
      }
      if (stop || round.timeUp() || segment.size > round.bytesLeft) {
        round.complete = false
        out += segment.copy(state = SegmentState.PENDING)
        continue
      }
      val classed = ClassedSegment(segment.cls.segmentClass, SegmentRef(segment.hash, segment.size))
      when (val read = remote.segment(view, classed)) {
        is RemoteResult.Failed -> {
          round.failure = read.failure
          round.complete = false
          stop = true
          out += segment.copy(state = SegmentState.PENDING)
        }
        is RemoteResult.Ok -> when (val fetch = read.value) {
          is AiSegmentFetch.Ok -> {
            round.bytesLeft -= segment.size
            val content = fetch.content
            if (content.cls != segment.cls || count + content.entries.size > maxEntriesPerDevice) {
              counters.reject(if (content.cls != segment.cls) RejectReason.WRONG_TYPE else RejectReason.TOO_LARGE)
              out += segment.copy(state = SegmentState.REJECTED)
            } else {
              count += content.entries.size
              entries[segment.hash] = content.entries.map {
                AiReplicaEntry(view.deviceId, segment.hash, AiKey(it.contentKey, content.cls), it.body.recipe, it.digest, it.bodyBytes)
              }
              out += segment.copy(state = SegmentState.APPLIED, entries = content.entries.size)
            }
          }
          AiSegmentFetch.Missing -> {
            counters.segmentsMissing.incrementAndGet()
            out += segment.copy(state = SegmentState.MISSING)
          }
          is AiSegmentFetch.Rejected -> {
            counters.reject(fetch.reason)
            out += segment.copy(state = SegmentState.REJECTED)
          }
        }
      }
    }
    round.touched += store.applyHead(AiHeadApplication(row, out, entries))
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
      val toWithdraw = ArrayList<AiKey>()
      val marks = ArrayList<AiAppliedMark>()
      for (k in chunk) {
        val list = candidates[k].orEmpty()
        val old = applied[k]
        val winner = AiFusion.winner(list, k.contentKey in attested)
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
        val body = bodyOf(key, winner)
        if (body == null) {
          // Mai, salvo un database rovinato: niente vincitore e la voce esce.
          if (old != null) toWithdraw += k
          continue
        }
        if (countDisagreement) countDisagreements(key, winner, body as VecBody, list)
        marks += mark
        if (mark != old) toApply += AiWinner(share, entryOf(k, body), winner.recipe, winner.deviceId)
      }
      if (toApply.isNotEmpty()) sink.apply(share, toApply)
      if (toWithdraw.isNotEmpty()) sink.withdraw(share, toWithdraw)
      store.commitApplied(key, chunk, marks)
    }
  }

  private suspend fun bodyOf(key: String, candidate: AiCandidate): AiBody? =
    store.body(key, candidate)?.let { AiSegments.decodeBody(candidate.key.cls, it).acceptedOrNull() }

  private suspend fun countDisagreements(key: String, winner: AiCandidate, body: VecBody, candidates: List<AiCandidate>) {
    for (rival in AiFusion.rivals(winner, candidates)) {
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
  }
}
