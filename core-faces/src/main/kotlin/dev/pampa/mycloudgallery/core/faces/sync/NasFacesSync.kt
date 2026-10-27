package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesSync
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncReport
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** L'istantanea vincente fra quelle pubblicate dai dispositivi dell'account (D21), per il raggruppamento (P5). */
interface FacesSnapshotSource {
  suspend fun winningSnapshot(): RemoteSnapshot?
}

/**
 * LA REPLICA DEI DATI DEI VOLTI DELL'ACCOUNT (Fase 8, P3; docs/fase8-progetto.md §2.4, D11-D13, D21, D30, D31, E-F41, E-F42; formato §5.11).
 * Un giro ([sync]):
 *
 * 1. **Prima di leggere** ([FacesSyncGuard]): registro, dispositivo approvato, area privata diversa dalla comune (L23), recinzione delle
 *    share vere (D34), Persone attiva qui e stato **fresco** (D30). Senza, non parte nessuna richiesta: la generazione non e' certa.
 * 2. **Revoche**: i dispositivi revocati o persi escono dall'archivio (corpi e voci, L99) e le teste degli altri si rileggono.
 * 3. **Lettura**: si elencano le generazioni (le non correnti si contano, D31: le toglie P8) e le cartelle della generazione corrente; si
 *    leggono **solo** i dispositivi approvati o dismessi **dell'account** (le cartelle di altri account, di sconosciuti e di revocati si
 *    contano e non si leggono mai: D11, E-F41). Per ognuno la testa piu' recente per nome ([FacesHeadSeries]: anti-rollback ed
 *    equivocazione per generazione e dispositivo), poi i soli segmenti nuovi, con i tetti per dispositivo e per giro (un dispositivo enorme o
 *    ostile non ferma gli altri: a rotazione, il resto al giro dopo). Corpi → archivio con l'autore = il firmatario; voci `people` valide →
 *    archivio (l'ultima scrittura vince); voci `cluster` → l'istantanea del dispositivo, se completa e coerente.
 * 4. **Pubblicazione** del proprio contributo ([publisher], dopo la lettura: la generazione e' fresca), se il giro e' completo e il cancello
 *    delle scritture e' aperto.
 *
 * Ogni errore scarta la testa o il segmento e non ferma gli altri dispositivi; un'eccezione dell'archivio (chiuso: la generazione e' cambiata
 * a meta') ferma il giro con [FacesSyncStop.NOT_FRESH]. Solo numeri e codici nel resoconto (D37). Lo stato e' in [store] (in memoria finche'
 * P6 non lega il database dei volti).
 */
class NasFacesSync(
  private val identity: FacesSyncIdentity,
  private val switch: FacesSwitch,
  private val remote: FacesRemote,
  private val archives: FacesArchives,
  private val store: FacesReplicaStore,
  private val gate: FacesWriteGate,
  private val clock: FacesClock,
  private val publisher: FacesPublisher? = null,
  private val flags: FeatureFlags = FeatureFlags.RELEASE,
  private val limits: FacesSyncLimits = FacesSyncLimits(),
) : FacesSync, FacesSnapshotSource {

  private val lock = Mutex()
  private val guard = FacesSyncGuard(identity, switch, { remote.share }, flags)

  /** Da quale dispositivo comincia il prossimo giro (rotazione: tutti avanzano anche quando i tetti si toccano). */
  private var rotation = 0

  /** Segmenti con voci "dal futuro": non si riscaricano prima dell'ora del NAS indicata (stessa regola dei dati personali). */
  private val futureHold = HashMap<Pair<String, String>, Long>()

  override suspend fun sync(budget: Duration): FacesSyncReport = lock.withLock {
    val tally = SyncTally()
    try {
      round(budget, tally)
    } catch (closed: IllegalStateException) {
      // L'archivio si e' chiuso a meta' (generazione cambiata, chiave distrutta): niente altro si scrive in questo giro.
      tally.report(FacesSyncStop.NOT_FRESH)
    }
  }

  private suspend fun round(budget: Duration, tally: SyncTally): FacesSyncReport {
    val started = clock.monotonicNanos()
    val overBudget = { clock.monotonicNanos() - started >= budget.inWholeNanoseconds }

    // --- 1. Prima di leggere -------------------------------------------------------------------------------------------------
    val open = when (val checked = guard.check()) {
      is FacesGate.Closed -> return tally.report(checked.stop)
      is FacesGate.Open -> checked
    }
    val block = gate.blockFor(remote.share)
    when (block) {
      FacesWriteBlock.TEST_SHARES_ONLY, FacesWriteBlock.FENCE -> return tally.report(FacesSyncStop.LOCAL_ONLY)
      FacesWriteBlock.NO_PRIVATE_AREA, FacesWriteBlock.NO_NAS -> return tally.report(FacesSyncStop.UNAVAILABLE)
      else -> Unit
    }
    val nasNow = clock.nasNowMillis() ?: return tally.report(FacesSyncStop.UNAVAILABLE)
    val archive = archives.open(open.generation)?.takeIf { it.generation == open.generation } ?: return tally.report(FacesSyncStop.UNAVAILABLE)
    var state = purgeRevoked(open, archive, store.load(open.generation))

    // --- 3. Lettura ----------------------------------------------------------------------------------------------------------
    when (val generations = remote.generations()) {
      is RemoteResult.Failed -> if (isFatal(generations.failure)) return tally.report(FacesSyncStop.UNAVAILABLE)
      is RemoteResult.Ok -> tally.foreignGenerations = generations.value.items.count { it != open.generation }
    }
    val listing = when (val result = remote.devices(open.generation)) {
      is RemoteResult.Failed -> return tally.report(if (isFatal(result.failure)) FacesSyncStop.UNAVAILABLE else FacesSyncStop.BUDGET)
      is RemoteResult.Ok -> result.value
    }
    val mine = FacesSyncGuard.accountDevices(open.registry, open.account)
    countUnread(open.registry, open.account, listing.items, tally)
    val listed = if (listing.truncated) mine else mine.filter { it in listing.items }
    val order = rotate(listed).take(limits.maxDevices)
    val heads = FacesReadBudget(limits.maxHeadReads)
    val rules = PeopleRules(open.registry, open.account, nasNow)
    var stop = FacesSyncStop.COMPLETE
    for (device in order) {
      if (overBudget()) {
        stop = FacesSyncStop.BUDGET
        break
      }
      // La generazione che cambia a meta' ferma la lettura: nessun dato si scrive nell'archivio di una generazione non corrente.
      if (!guard.stillOpen(open)) {
        stop = FacesSyncStop.NOT_FRESH
        break
      }
      tally.devicesRead++
      val previous = state.devices[device] ?: DeviceReplica()
      var current = previous
      when (val read = FacesHeadSeries.readLatest(remote, open.registry, open.generation, device, previous.mark, open.account, heads, nasNow)) {
        is FacesHeadRead.Accepted -> {
          tally.headsAccepted++
          read.skipped.forEach { tally.head(it.name) }
          current = adopt(previous, read.head.head, read.head.mark)
        }
        is FacesHeadRead.Rejected -> tally.head(read.reason.name)
        is FacesHeadRead.Failed -> if (isFatal(read.failure)) {
          stop = FacesSyncStop.UNAVAILABLE
          break
        }
        FacesHeadRead.OutOfBudget -> {
          stop = FacesSyncStop.BUDGET
          break
        }
        FacesHeadRead.NoChange, FacesHeadRead.None -> Unit
      }
      val step = readSegments(open, archive, device, current, rules, tally, overBudget)
      if (step.stop == FacesSyncStop.NOT_FRESH) {
        // La generazione non e' piu' la corrente: niente si salva per lei.
        stop = FacesSyncStop.NOT_FRESH
        break
      }
      current = step.replica
      if (current != previous) {
        state = state.copy(devices = state.devices + (device to current))
        store.save(open.generation, state)
      }
      if (step.stop != null) {
        stop = step.stop
        break
      }
    }
    rotation++

    // --- 4. Pubblicazione --------------------------------------------------------------------------------------------------------
    val published: FacesPublishOutcome? = if (stop == FacesSyncStop.COMPLETE && block == null) publisher?.flush(FlushReason.SYNC_ROUND) else null
    return tally.report(stop, published)
  }

  // --- 2. Revoche ----------------------------------------------------------------------------------------------------------------

  /**
   * L99, formato §4.6: i dispositivi revocati o persi dall'ultima volta escono dall'archivio (i loro corpi non vincono piu', i loro nomi non
   * si mostrano piu') e dalla replica; le teste degli altri dispositivi si rileggono intere, cosi' le voci valide che avevano tornano.
   */
  private suspend fun purgeRevoked(open: FacesGate.Open, archive: FacesArchive, loaded: FacesReplicaState): FacesReplicaState {
    val barred = FacesSyncGuard.barred(open.registry)
    val fresh = barred - loaded.revoked
    if (fresh.isEmpty()) return loaded
    archive.dropAuthors(fresh)
    val devices = loaded.devices.filterKeys { it !in barred }.mapValues { (_, d) -> d.copy(done = emptySet(), counts = emptyMap(), checked = null) }
    val state = FacesReplicaState(devices, loaded.revoked + fresh)
    store.save(open.generation, state)
    return state
  }

  /** Le cartelle che non si leggono mai (D11, E-F41): di un altro account, di un dispositivo sconosciuto, di un revocato o perso. */
  private fun countUnread(registry: RegistryState, account: String, names: List<String>, tally: SyncTally) {
    for (name in names) {
      val entry = registry.registry.device(name)
      when {
        entry == null -> tally.head(RejectReason.SIGNER_NOT_APPROVED.name)
        !TextRules.sameAccount(entry.nasUser, account) -> tally.head(RejectReason.NOT_OWNER.name)
        entry.status.isBarred -> tally.head(RejectReason.SIGNER_REVOKED.name)
        entry.status != DeviceStatus.APPROVED && entry.status != DeviceStatus.RETIRED -> tally.head(RejectReason.SIGNER_NOT_APPROVED.name)
        else -> Unit
      }
    }
  }

  private fun rotate(devices: List<String>): List<String> {
    if (devices.isEmpty()) return devices
    val k = rotation % devices.size
    return devices.drop(k) + devices.take(k)
  }

  /** Una testa nuova del dispositivo: i segmenti gia' applicati che elenca ancora restano fatti (un segmento e' il suo contenuto). */
  private fun adopt(previous: DeviceReplica, head: FacesHead, mark: HeadMark): DeviceReplica {
    val hashes = head.segments.mapTo(HashSet()) { it.ref.hash }
    val cluster = head.segments.filter { it.cls == SegmentClass.CLUSTER }.mapTo(HashSet()) { it.ref.hash }
    return previous.copy(
      mark = mark,
      segments = head.segments,
      snapshotMark = head.snapshot,
      done = previous.done intersect hashes,
      counts = previous.counts.filterKeys { it in hashes },
      cluster = previous.cluster.filterKeys { it in cluster },
      snapshot = if (head.snapshot == null) null else previous.snapshot,
      snapshotHead = if (head.snapshot == null) null else previous.snapshotHead,
    )
  }

  // --- 3. Segmenti -----------------------------------------------------------------------------------------------------------------

  private class Step(val replica: DeviceReplica, val stop: FacesSyncStop?)

  /**
   * I segmenti non ancora fatti della testa di [device]: al piu' [FacesSyncLimits.maxBytesPerDevice] per dispositivo (il resto al giro dopo)
   * e [FacesSyncLimits.maxBytesPerRound] per giro. Un segmento rifiutato (impronta, forma, tetti) e' fatto per sempre: un segmento e' il suo
   * contenuto, riscaricarlo darebbe lo stesso rifiuto. Uno che manca o che il NAS non da' si riprova.
   */
  private suspend fun readSegments(
    open: FacesGate.Open,
    archive: FacesArchive,
    device: String,
    start: DeviceReplica,
    rules: PeopleRules,
    tally: SyncTally,
    overBudget: () -> Boolean,
  ): Step {
    var replica = start
    var deviceBytes = 0L
    for (segment in start.segments) {
      val hash = segment.ref.hash
      if (hash in replica.done) continue
      if (overBudget()) return Step(evaluate(replica, tally), FacesSyncStop.BUDGET)
      // La generazione che cambia a meta' ferma anche la lettura dei segmenti: niente nell'archivio di una generazione non corrente.
      if (!guard.stillOpen(open)) return Step(replica, FacesSyncStop.NOT_FRESH)
      val hold = futureHold[device to hash]
      if (hold != null && rules.nasNow < hold) continue
      val size = segment.ref.size
      if (deviceBytes + size > limits.maxBytesPerDevice) break
      if (tally.bytesRead + size > limits.maxBytesPerRound) return Step(evaluate(replica, tally), FacesSyncStop.BUDGET)
      val fetched = when (val read = remote.readSegment(open.generation, device, hash, size.toInt())) {
        is RemoteResult.Failed -> if (isFatal(read.failure)) return Step(evaluate(replica, tally), FacesSyncStop.UNAVAILABLE) else continue
        is RemoteResult.Ok -> read.value
      }
      if (!guard.stillOpen(open)) return Step(replica, FacesSyncStop.NOT_FRESH)
      replica = when (fetched) {
        FacesRead.NotFound -> {
          tally.segment(CODE_MISSING)
          continue
        }
        FacesRead.TooLarge -> {
          tally.segment(RejectReason.HASH_MISMATCH.name)
          replica.markDone(hash, 0)
        }
        is FacesRead.Data -> {
          deviceBytes += size
          tally.bytesRead += size
          when (val verdict = FacesDocs.decodeSegment(fetched.bytes, segment.ref, segment.cls)) {
            is Verdict.Rejected -> {
              tally.segment(verdict.reason.name)
              replica.markDone(hash, 0)
            }
            is Verdict.Accepted -> apply(archive, device, replica, hash, verdict.value, rules, tally)
          }
        }
      }
    }
    return Step(evaluate(replica, tally), null)
  }

  /**
   * Il contenuto di un segmento accettato. Corpi: nell'archivio con l'autore = il firmatario della testa. Voci `people`: solo quelle con un
   * autore dell'account non revocato e un'ora non nel futuro (le altre si contano; una voce "dal futuro" fa riprovare il segmento piu' tardi).
   * Voci `cluster`: tenute per l'istantanea del dispositivo. Tetti per dispositivo: un segmento oltre e' rifiutato (`TOO_LARGE`).
   */
  private suspend fun apply(
    archive: FacesArchive,
    device: String,
    replica: DeviceReplica,
    hash: String,
    content: FacesSegmentContent,
    rules: PeopleRules,
    tally: SyncTally,
  ): DeviceReplica {
    val clusterHashes = replica.segments.filter { it.cls == SegmentClass.CLUSTER }.mapTo(HashSet()) { it.ref.hash }
    val used = replica.counts.filterKeys { it !in clusterHashes }.values.sum()
    return when (content) {
      is FacesSegmentContent.Bodies -> {
        if (used + content.size > limits.maxEntriesPerDevice) {
          tally.segment(RejectReason.TOO_LARGE.name)
          return replica.markDone(hash, 0)
        }
        // I corpi della mia testa: l'archivio di qui e' la fonte (un ricalcolo non ancora pubblicato e' piu' nuovo). Si riprendono solo
        // quelli che qui mancano (dopo la perdita dello stato locale), mai sopra un corpo mio.
        val mine = if (device == identity.deviceId) {
          archive.bodiesOf(device, content.entries.map { it.contentKey }).keys
        } else {
          emptySet()
        }
        for (entry in content.entries) {
          if (entry.contentKey in mine) continue
          archive.saveBody(FacesWire.body(entry.contentKey, device, entry.body, entry.digest))
        }
        if (device == identity.deviceId) (publisher as? NasFacesPublisher)?.ownBodies(archive.generation, content.entries.map { it.contentKey })
        replica.markDone(hash, content.size)
      }
      is FacesSegmentContent.People -> {
        if (used + content.size > limits.maxEntriesPerDevice) {
          tally.segment(RejectReason.TOO_LARGE.name)
          return replica.markDone(hash, 0)
        }
        val valid = ArrayList<PeopleRecord>(content.size)
        var retryAt: Long? = null
        for (doc in content.entries) {
          when (val verdict = rules.verdict(doc.author, doc.at)) {
            PeopleVerdict.VALID -> valid += FacesWire.record(doc)
            PeopleVerdict.FROM_FUTURE -> {
              tally.segment(verdict.code)
              val at = doc.at - TrustLimits.MAX_FUTURE_SKEW_MILLIS
              retryAt = retryAt?.let { minOf(it, at) } ?: at
            }
            else -> tally.segment(verdict.code)
          }
        }
        if (valid.isNotEmpty()) archive.addPeople(valid)
        if (retryAt != null) {
          futureHold[device to hash] = retryAt
          replica
        } else {
          futureHold.remove(device to hash)
          replica.markDone(hash, content.size)
        }
      }
      is FacesSegmentContent.Cluster -> {
        val clustered = replica.counts.filterKeys { it in clusterHashes }.values.sum()
        if (clustered + content.size > TrustLimits.MAX_SNAPSHOT_PERSONS + TrustLimits.MAX_SNAPSHOT_ASSIGNMENTS) {
          tally.segment(RejectReason.TOO_LARGE.name)
          return replica.markDone(hash, 0)
        }
        replica.copy(cluster = replica.cluster + (hash to content.entries)).markDone(hash, content.size)
      }
    }
  }

  private fun DeviceReplica.markDone(hash: String, entries: Int): DeviceReplica = copy(done = done + hash, counts = counts + (hash to entries))

  /**
   * L'istantanea della testa del dispositivo, quando tutti i suoi segmenti `cluster` sono fatti (D21): tutte le voci insieme, con le regole
   * di [FacesDocs.checkSnapshot]. Un'istantanea incompleta (un segmento rifiutato) o incoerente non vale; resta l'ultima valida dello stesso
   * dispositivo, se c'era. Una testa senza istantanea toglie quella del dispositivo.
   */
  private fun evaluate(replica: DeviceReplica, tally: SyncTally): DeviceReplica {
    val mark = replica.snapshotMark ?: return replica
    val head = replica.mark ?: return replica
    if (replica.checked == head) return replica
    val segments = replica.segments.filter { it.cls == SegmentClass.CLUSTER }
    if (segments.any { it.ref.hash !in replica.done }) return replica
    val checked = replica.copy(checked = head)
    if (segments.any { it.ref.hash !in replica.cluster }) {
      tally.segment(CODE_SNAPSHOT_INCOMPLETE)
      return checked
    }
    val entries = segments.flatMap { replica.cluster.getValue(it.ref.hash) }
    return when (val verdict = FacesDocs.checkSnapshot(mark, entries)) {
      is Verdict.Accepted -> checked.copy(snapshot = verdict.value, snapshotHead = head)
      is Verdict.Rejected -> {
        tally.segment("$CODE_SNAPSHOT_PREFIX${verdict.reason.name}")
        checked
      }
    }
  }

  // --- L'istantanea vincente ---------------------------------------------------------------------------------------------------------

  /**
   * L'istantanea con la `seq` piu' alta fra quelle dei dispositivi **dell'account** (a pari, il dispositivo minore, D21), risolta sui corpi
   * vincenti di qui. Legge solo lo stato locale della replica (nessuna richiesta al NAS): basta Persone attiva qui nella generazione corrente.
   */
  override suspend fun winningSnapshot(): RemoteSnapshot? = lock.withLock {
    val state = switch.currentState() as? FacesState.On ?: return@withLock null
    if (state.here != HereState.ACTIVE) return@withLock null
    val registry = identity.registry() ?: return@withLock null
    val account = registry.registry.device(identity.deviceId)?.nasUser ?: return@withLock null
    val archive = archives.open(state.generation)?.takeIf { it.generation == state.generation } ?: return@withLock null
    val replica = store.load(state.generation)
    val allowed = FacesSyncGuard.accountDevices(registry, account).toSet()
    val candidates = replica.devices.filterKeys { it in allowed }.mapNotNull { (device, d) ->
      val doc = d.snapshot ?: return@mapNotNull null
      val head = d.snapshotHead ?: return@mapNotNull null
      SnapshotCandidate(device, doc.mark, head.version, head.contentHash)
    }
    val winner = FacesFusion.winner(candidates) ?: return@withLock null
    val doc = replica.devices.getValue(winner.device).snapshot!!
    FacesWire.resolve(winner.device, doc, archive.winners(doc.assignments.mapTo(HashSet()) { it.face.contentKey }))
  }

  private fun isFatal(failure: RemoteFailure): Boolean = when (failure) {
    RemoteFailure.UNREACHABLE, RemoteFailure.ACCESS_DENIED, RemoteFailure.SHARE_MISSING, RemoteFailure.NOT_ALLOWED -> true
    else -> false
  }

  private companion object {
    const val CODE_MISSING = "MISSING"
    const val CODE_SNAPSHOT_INCOMPLETE = "SNAPSHOT_INCOMPLETE"
    const val CODE_SNAPSHOT_PREFIX = "SNAPSHOT_"
  }
}

/** I conti di un giro della replica (solo numeri e codici, D37). */
internal class SyncTally {
  var devicesRead = 0
  var headsAccepted = 0
  var bytesRead = 0L
  var foreignGenerations = 0
  private val heads = LinkedHashMap<String, Int>()
  private val segments = LinkedHashMap<String, Int>()

  fun head(code: String) {
    heads.merge(code, 1, Int::plus)
  }

  fun segment(code: String) {
    segments.merge(code, 1, Int::plus)
  }

  fun report(stop: FacesSyncStop, published: FacesPublishOutcome? = null): FacesSyncReport =
    FacesSyncReport(devicesRead, headsAccepted, heads.toMap(), segments.toMap(), bytesRead, foreignGenerations, published, stop)
}
