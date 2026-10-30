package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
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
import dev.pampa.mycloudgallery.core.faces.contract.FacesTotals
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.FacesSnapshotDoc
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
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
 * a meta') ferma il giro con [FacesSyncStop.NOT_FRESH]. Correzioni della Fase 8 (pacchetto "formato e replica dei volti"): le voci `people`
 * entrano con la loro provenienza e mai a nome mio da un altro (R3); un file che non si legge o che manca aspetta con attese crescenti e il giro va
 * avanti (R5, R7), con un tetto alle richieste; i corpi degli altri hanno tetti per autore e in tutto (R6). Solo numeri e codici nel resoconto (D37). Lo stato e' in [store] (in memoria finche'
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
) : FacesSync, FacesSnapshotSource, ClusterSnapshots {

  private val lock = Mutex()
  private val guard = FacesSyncGuard(identity, switch, { remote.share }, flags)

  /** Da quale dispositivo comincia il prossimo giro (rotazione: tutti avanzano anche quando i tetti si toccano). */
  private var rotation = 0

  /** Segmenti con voci "dal futuro": non si riscaricano prima dell'ora del NAS indicata (stessa regola dei dati personali). */
  private val futureHold = HashMap<Pair<String, String>, Long>()

  /** R5: le teste che non si leggono (il NAS risponde, quel file no): per dispositivo, con attese crescenti. In memoria: costa una richiesta. */
  private val headHold = HashMap<String, SegmentWait>()

  override suspend fun sync(budget: Duration): FacesSyncReport = lock.withLock {
    val tally = SyncTally()
    try {
      round(budget, tally)
    } catch (e: CancellationException) {
      throw e
    } catch (closed: IllegalStateException) {
      // L'archivio si e' chiuso a meta' (generazione cambiata, chiave distrutta): niente altro si scrive in questo giro.
      tally.report(FacesSyncStop.NOT_FRESH)
    } catch (e: Exception) {
      // O1: un errore del database (applicare i corpi letti, salvare lo stato della replica) non e' un'eccezione per chi chiama: il giro non e' finito. Niente si perde: lo stato
      // della replica non e' avanzato e i segmenti si rileggono (applicarli e' idempotente) al giro dopo.
      tally.report(FacesSyncStop.UNAVAILABLE)
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
    val caps = BodyCaps(archive, limits)
    val count = ReadCount()
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
      val held = headHold[device]?.takeIf { !it.due(nasNow) }
      val read = if (held != null) FacesHeadRead.NoChange else {
        FacesHeadSeries.readLatest(remote, open.registry, open.generation, device, previous.mark, open.account, heads, nasNow)
      }
      when (read) {
        is FacesHeadRead.Accepted -> {
          headHold.remove(device)
          tally.headsAccepted++
          read.skipped.forEach { tally.head(it.name) }
          current = adopt(previous, read.head.head, read.head.mark)
        }
        is FacesHeadRead.Rejected -> tally.head(read.reason.name)
        is FacesHeadRead.Failed -> if (isFatal(read.failure)) {
          // R5: una testa che non si legge ferma il giro solo se il NAS non risponde nemmeno a un elenco; altrimenti aspetta e si va avanti
          // (con i segmenti della testa gia' nota).
          if (!nasAnswers(open, count)) {
            stop = FacesSyncStop.UNAVAILABLE
            break
          }
          tally.head(CODE_UNREADABLE)
          val attempts = (headHold[device]?.attempts ?: 0) + 1
          headHold[device] = SegmentWait(attempts, nasNow + SegmentWait.delayAfter(attempts))
        }
        FacesHeadRead.OutOfBudget -> {
          stop = FacesSyncStop.BUDGET
          break
        }
        FacesHeadRead.NoChange, FacesHeadRead.None -> Unit
      }
      val step = readSegments(open, archive, device, current, rules, tally, overBudget, caps, count)
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
    val devices = loaded.devices.filterKeys { it !in barred }.mapValues { (_, d) -> d.copy(done = emptySet(), counts = emptyMap(), checked = null, waits = emptyMap()) }
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
      waits = previous.waits.filterKeys { it in hashes },
    )
  }

  // --- 3. Segmenti -----------------------------------------------------------------------------------------------------------------

  private class Step(val replica: DeviceReplica, val stop: FacesSyncStop?)

  /** R5: le richieste di segmenti di questo giro (tutti i dispositivi insieme). */
  private class ReadCount(var reads: Int = 0)

  /**
   * R6: i tetti dei corpi che arrivano dagli altri dispositivi ([FacesSyncLimits], di serie `FacesArchiveLimits`): i conti dell'archivio si leggono una volta per giro (in
   * tutto) e per dispositivo, poi si tengono a mano con quello che si scrive. Un corpo che sostituisce quello dello stesso autore e contenuto
   * conta solo per la differenza.
   */
  private class BodyCaps(private val archive: FacesArchive, private val limits: FacesSyncLimits) {
    private var total: FacesTotals? = null
    private val authors = HashMap<String, FacesTotals>()

    /** Null se [delta] di [author] sta nei tetti; altrimenti il codice del tetto superato. */
    suspend fun refusal(author: String, delta: FacesTotals): String? {
      val mine = authors[author] ?: archive.bodyTotals(author).also { authors[author] = it }
      val all = total ?: archive.bodyTotals(null).also { total = it }
      val nextMine = mine + delta
      val nextAll = all + delta
      return when {
        nextMine.bodies > limits.maxBodiesPerAuthor || nextMine.formatBytes > limits.maxBytesPerAuthor -> CODE_AUTHOR_FULL
        nextAll.bodies > limits.maxBodiesTotal || nextAll.formatBytes > limits.maxBytesTotal -> CODE_ARCHIVE_FULL
        else -> null
      }
    }

    fun add(author: String, delta: FacesTotals) {
      authors[author] = (authors[author] ?: FacesTotals.ZERO) + delta
      total = (total ?: FacesTotals.ZERO) + delta
    }
  }

  /**
   * I segmenti non ancora fatti della testa di [device]: al piu' [FacesSyncLimits.maxBytesPerDevice] byte e
   * [FacesSyncLimits.maxSegmentReadsPerDevice] richieste per dispositivo (il resto al giro dopo), [FacesSyncLimits.maxBytesPerRound] byte e
   * [FacesSyncLimits.maxSegmentReadsPerRound] richieste per giro. Un segmento rifiutato per il contenuto (forma, tetti) e' fatto per sempre: un
   * segmento e' il suo contenuto, riscaricarlo darebbe lo stesso rifiuto. R5 e R7: uno assente, illeggibile o letto male (impronta o dimensione
   * sbagliate: una lettura corta, un file sostituito dal PC) **aspetta** con attese crescenti ([SegmentWait], durevole) e il giro va avanti; letto
   * male tre volte, e' rifiutato per sempre. Un errore del NAS su un file ferma il giro solo se il NAS non risponde nemmeno a un elenco.
   */
  private suspend fun readSegments(
    open: FacesGate.Open,
    archive: FacesArchive,
    device: String,
    start: DeviceReplica,
    rules: PeopleRules,
    tally: SyncTally,
    overBudget: () -> Boolean,
    caps: BodyCaps,
    count: ReadCount,
  ): Step {
    var replica = start
    var deviceBytes = 0L
    var deviceReads = 0
    val nasNow = rules.nasNow
    for (segment in start.segments) {
      val hash = segment.ref.hash
      if (hash in replica.done) continue
      if (overBudget()) return Step(evaluate(replica, tally), FacesSyncStop.BUDGET)
      // La generazione che cambia a meta' ferma anche la lettura dei segmenti: niente nell'archivio di una generazione non corrente.
      if (!guard.stillOpen(open)) return Step(replica, FacesSyncStop.NOT_FRESH)
      val hold = futureHold[device to hash]
      if (hold != null && nasNow < hold) continue
      if (replica.waits[hash]?.due(nasNow) == false) continue
      val size = segment.ref.size
      if (deviceBytes + size > limits.maxBytesPerDevice || deviceReads >= limits.maxSegmentReadsPerDevice) break
      if (tally.bytesRead + size > limits.maxBytesPerRound || count.reads >= limits.maxSegmentReadsPerRound) {
        return Step(evaluate(replica, tally), FacesSyncStop.BUDGET)
      }
      deviceReads++
      count.reads++
      val fetched = when (val read = remote.readSegment(open.generation, device, hash, size.toInt())) {
        is RemoteResult.Failed -> {
          if (!isFatal(read.failure)) continue
          // R5: un file che non si legge (un'ACL, un file aperto in esclusiva dal PC) non ferma il giro se il NAS risponde ancora.
          if (!nasAnswers(open, count)) return Step(evaluate(replica, tally), FacesSyncStop.UNAVAILABLE)
          tally.segment(CODE_UNREADABLE)
          replica = replica.waiting(hash, nasNow)
          continue
        }
        is RemoteResult.Ok -> read.value
      }
      if (!guard.stillOpen(open)) return Step(replica, FacesSyncStop.NOT_FRESH)
      replica = when (fetched) {
        FacesRead.NotFound -> {
          // R5: elencato e assente. Si richiede con attese crescenti (la sua testa resta quella), mai a ogni giro.
          tally.segment(CODE_MISSING)
          replica.waiting(hash, nasNow)
        }
        FacesRead.TooLarge -> {
          tally.segment(RejectReason.HASH_MISMATCH.name)
          replica.badRead(hash, nasNow)
        }
        is FacesRead.Data -> {
          deviceBytes += size
          tally.bytesRead += size
          when (val verdict = FacesDocs.decodeSegment(fetched.bytes, segment.ref, segment.cls)) {
            is Verdict.Rejected -> {
              tally.segment(verdict.reason.name)
              // R7: impronta o dimensione sbagliate possono essere una lettura corta o un file sostituito e poi rimesso: si riprova.
              if (verdict.reason == RejectReason.HASH_MISMATCH) replica.badRead(hash, nasNow) else replica.markDone(hash, 0)
            }
            is Verdict.Accepted -> apply(archive, device, replica, hash, verdict.value, rules, tally, caps)
          }
        }
      }
    }
    return Step(evaluate(replica, tally), null)
  }

  /** R5: dopo un errore su un file, il NAS risponde ancora a un elenco? (Una richiesta in piu', contata nel tetto del giro.) */
  private suspend fun nasAnswers(open: FacesGate.Open, count: ReadCount): Boolean {
    count.reads++
    return remote.devices(open.generation) is RemoteResult.Ok
  }

  /**
   * Il contenuto di un segmento accettato. Corpi: nell'archivio con l'autore = il firmatario della testa, nei tetti per autore e in tutto (R6).
   * Voci `people`: solo quelle con un autore dell'account non revocato e un'ora non nel futuro; entrano con la **provenienza** = [device] (R3),
   * nei tetti dell'archivio, e una copia a nome mio portata da un altro non sostituisce mai una mia scelta. Voci `cluster`: tenute per l'istantanea del
   * dispositivo. Tetti per dispositivo: un segmento oltre e' rifiutato (`TOO_LARGE`).
   */
  private suspend fun apply(
    archive: FacesArchive,
    device: String,
    replica: DeviceReplica,
    hash: String,
    content: FacesSegmentContent,
    rules: PeopleRules,
    tally: SyncTally,
    caps: BodyCaps,
  ): DeviceReplica {
    val clusterHashes = replica.segments.filter { it.cls == SegmentClass.CLUSTER }.mapTo(HashSet()) { it.ref.hash }
    val used = replica.counts.filterKeys { it !in clusterHashes }.values.sum()
    val mineHere = device == identity.deviceId
    return when (content) {
      is FacesSegmentContent.Bodies -> {
        if (used + content.size > limits.maxEntriesPerDevice) {
          tally.segment(RejectReason.TOO_LARGE.name)
          return replica.markDone(hash, 0)
        }
        val keys = content.entries.map { it.contentKey }
        if (!mineHere) {
          // R6: i tetti contano la differenza con i corpi che lo stesso autore ha gia' per quei contenuti.
          val incoming = FacesTotals(content.entries.size, content.entries.sumOf { it.body.faces.size.toLong() })
          val delta = incoming - archive.bodyTotals(device, keys)
          when (val refused = caps.refusal(device, delta)) {
            null -> caps.add(device, delta)
            // Il dispositivo ha gia' i suoi tetti pieni: il segmento e' rifiutato (una testa onesta non ci arriva mai).
            CODE_AUTHOR_FULL -> {
              tally.segment(refused)
              return replica.markDone(hash, 0)
            }
            // L'archivio e' pieno: si riprova piu' tardi (una revoca o un contenuto tolto fanno posto).
            else -> {
              tally.segment(refused)
              return replica.waiting(hash, rules.nasNow)
            }
          }
        }
        // I corpi della mia testa: l'archivio di qui e' la fonte (un ricalcolo non ancora pubblicato e' piu' nuovo). Si riprendono solo
        // quelli che qui mancano (dopo la perdita dello stato locale), mai sopra un corpo mio.
        val mine = if (mineHere) archive.bodiesOf(device, keys).keys else emptySet()
        for (entry in content.entries) {
          if (entry.contentKey in mine) continue
          archive.saveBody(FacesWire.body(entry.contentKey, device, entry.body, entry.digest))
        }
        if (mineHere) (publisher as? NasFacesPublisher)?.ownBodies(archive.generation, keys)
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
        if (valid.isNotEmpty()) {
          // R3: una voce a nome mio portata da un altro (la copia che un dispositivo onesto ripubblica, o una falsa) non sostituisce mai una mia
          // scelta: io resto la fonte che, dopo una revoca, rimette il valore vero. Se la voce qui non c'e' (stato locale perso), torna da loro.
          val refused = archive.addPeopleFrom(device, valid, own = identity.deviceId)
          if (refused > 0) repeat(refused) { tally.segment(CODE_PEOPLE_FULL) }
        }
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

  private fun DeviceReplica.markDone(hash: String, entries: Int): DeviceReplica =
    copy(done = done + hash, counts = counts + (hash to entries), waits = waits - hash)

  /** R5: il segmento aspetta (assente, illeggibile, archivio pieno): un tentativo in piu', l'attesa raddoppia. */
  private fun DeviceReplica.waiting(hash: String, nasNow: Long): DeviceReplica {
    val attempts = (waits[hash]?.attempts ?: 0) + 1
    return copy(waits = waits + (hash to SegmentWait(attempts, nasNow + SegmentWait.delayAfter(attempts))))
  }

  /** R7: una lettura sbagliata (impronta o dimensione): si riprova con attese crescenti, e dopo [SegmentWait.MAX_BAD_READS] e' rifiutato per sempre. */
  private fun DeviceReplica.badRead(hash: String, nasNow: Long): DeviceReplica {
    val attempts = (waits[hash]?.attempts ?: 0) + 1
    return if (attempts >= SegmentWait.MAX_BAD_READS) markDone(hash, 0) else waiting(hash, nasNow)
  }

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

  // --- Le istantanee lette (P5) -----------------------------------------------------------------------------------------------------

  /** Le istantanee valide dei dispositivi dell'account nello stato della replica, con il loro candidato (seq, dispositivo, testa). */
  private suspend fun candidates(generation: FaceGeneration): Pair<FacesArchive, List<Pair<SnapshotCandidate, FacesSnapshotDoc>>>? {
    val state = switch.currentState() as? FacesState.On ?: return null
    if (state.generation != generation || state.here != HereState.ACTIVE) return null
    val registry = identity.registry() ?: return null
    val account = registry.registry.device(identity.deviceId)?.nasUser ?: return null
    val archive = archives.open(generation)?.takeIf { it.generation == generation } ?: return null
    val allowed = FacesSyncGuard.accountDevices(registry, account).toSet()
    val list = store.load(generation).devices.filterKeys { it in allowed }.mapNotNull { (device, d) ->
      val doc = d.snapshot ?: return@mapNotNull null
      val head = d.snapshotHead ?: return@mapNotNull null
      SnapshotCandidate(device, doc.mark, head.version, head.contentHash) to doc
    }
    return archive to list
  }

  /**
   * `ClusterSnapshots` di P5: per ogni dispositivo **dell'account** (anche questo) l'ultima istantanea valida che ha pubblicato nella
   * generazione [generation], risolta sui corpi vincenti di qui (un'assegnazione il cui riferimento non torna con il corpo vincente manca: il
   * volto e' "nuovo" per il custode). Vuoto se [generation] non e' la corrente attiva qui. Legge solo lo stato della replica: nessuna
   * richiesta al NAS (le regole di chi legge sono gia' passate: firmatario dell'account, generazione, anti-rollback, tetti, coerenza). Non
   * aspetta il giro in corso: legge l'ultimo stato salvato (ogni salvataggio della replica e' atomico), e i dispositivi revocati nel registro
   * accettato di adesso sono gia' fuori anche prima che il giro li tolga. Un archivio chiuso a meta' (generazione cambiata, chiave
   * distrutta) da' la lista vuota, mai un'eccezione.
   */
  override suspend fun latest(generation: FaceGeneration): List<ClusterSnapshot> = try {
    val (archive, list) = candidates(generation) ?: return emptyList()
    val winners = archive.winners(list.flatMap { (_, doc) -> doc.assignments.map { it.face.contentKey } }.toSet())
    list.sortedBy { it.first.device }.map { (candidate, doc) -> ClusterSnapshot(candidate.device, FacesWire.resolve(candidate.device, doc, winners).state) }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // I5: un archivio chiuso a meta' o un errore del database (O1) danno la lista vuota, mai un'eccezione per chi chiama.
    emptyList()
  }

  /**
   * L'istantanea con la `seq` piu' alta fra quelle dei dispositivi dell'account (a pari, il dispositivo minore, D21; lo stesso ordine di
   * `ClusterSnapshot.winner`), risolta sui corpi vincenti di qui, con i centroidi e il numero delle assegnazioni non risolte. Come [latest],
   * senza aspettare il giro in corso e senza eccezioni (archivio chiuso: null).
   */
  override suspend fun winningSnapshot(): RemoteSnapshot? = try {
    val generation = (switch.currentState() as? FacesState.On)?.generation ?: return null
    val (archive, list) = candidates(generation) ?: return null
    val winner = FacesFusion.winner(list.map { it.first }) ?: return null
    val doc = list.first { it.first == winner }.second
    FacesWire.resolve(winner.device, doc, archive.winners(doc.assignments.mapTo(HashSet()) { it.face.contentKey }))
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // I5: come [latest].
    null
  }

  private fun isFatal(failure: RemoteFailure): Boolean = when (failure) {
    RemoteFailure.UNREACHABLE, RemoteFailure.ACCESS_DENIED, RemoteFailure.SHARE_MISSING, RemoteFailure.NOT_ALLOWED -> true
    else -> false
  }

  private companion object {
    const val CODE_MISSING = "MISSING"

    /** R5: un file (testa o segmento) che il NAS non da' mentre risponde a un elenco: aspetta. */
    const val CODE_UNREADABLE = "UNREADABLE"

    /** R6: i corpi di un autore oltre i suoi tetti (segmento rifiutato) o l'archivio pieno (segmento rimandato). */
    const val CODE_AUTHOR_FULL = "AUTHOR_FULL"
    const val CODE_ARCHIVE_FULL = "ARCHIVE_FULL"

    /** R3: voci `people` che non entrano nell'archivio per i tetti (in tutto o per provenienza). */
    const val CODE_PEOPLE_FULL = "PEOPLE_FULL"
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
