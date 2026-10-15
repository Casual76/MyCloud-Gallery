package dev.pampa.mycloudgallery.core.nasops.clean

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.CleanReport
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
import dev.pampa.mycloudgallery.core.trust.data.Leases
import dev.pampa.mycloudgallery.core.trust.data.OwnFile
import dev.pampa.mycloudgallery.core.trust.data.OwnFileFacts
import dev.pampa.mycloudgallery.core.trust.data.OwnFilesPolicy
import dev.pampa.mycloudgallery.core.trust.data.OwnKeepReason
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration
import kotlinx.coroutines.sync.withLock

/**
 * La pulizia dei **propri** file in `.mycloudgallery/` (formato §2.3, M6; accesa nella 4B con la compattazione): teste superate,
 * segmenti non piu' elencati, generazioni di prenotazione superate, temporanei rimasti. **Decide** `OwnFilesPolicy` di core-trust, file per
 * file, con le prove raccolte qui; **toglie** `deleteIfUnchanged` (un solo handle, la dimensione appena vista), mai piu' di 500 file per
 * giro.
 *
 * Le prove non vengono mai da un dato remoto "di fiducia": la firma si verifica (una testa o una prenotazione e' mia solo se la firma e'
 * mia **e** il posto e' mio), i segmenti si giudicano con le teste proprie verificate e il diario locale dei segmenti pubblicati, i
 * temporanei con il diario delle operazioni (un temporaneo citato da un'operazione non chiusa non si tocca). Nel dubbio (registro non
 * pronto, una testa illeggibile, nessuna testa valida) si tiene tutto. Le stesse condizioni della pulizia del cestino: niente con
 * congelamento, salto dell'ora (D32) o emergenza ([OpsGate]), ne' senza ora del NAS.
 */
class OwnFilesCleaner internal constructor(
  private val deviceId: String,
  /** L'account del NAS di questo dispositivo (le teste dei dati personali valgono solo per lui). */
  private val accountId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val store: OpsStore,
  private val clocks: OpClocks,
  private val gate: OpsGate,
  private val lock: NasOpsLock,
  /** Il registro di famiglia accettato (null = non pronto: nessuna testa ne' prenotazione e' giudicabile). */
  private val registry: () -> RegistryState?,
  /** Il diario dei segmenti pubblicati da questo dispositivo nella share (IndexPublishStore.publishedSegments). */
  private val publishedSegments: suspend (String) -> Set<String> = { emptySet() },
  /** I segmenti elencati dalla testa propria corrente secondo il database (in piu' delle teste lette dal NAS). */
  private val currentSegments: suspend (String) -> Set<String> = { emptySet() },
) : OwnFilesCleaning {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
    require(fileOps.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  private val policy = OwnFilesPolicy(deviceId)
  private val purpose = Purpose.BACKGROUND

  /** Il NAS non risponde: il giro della share si ferma, niente e' deciso. */
  private class Unreachable : RuntimeException(null, null, false, false)

  override suspend fun clean(shares: List<String>, budget: Duration): CleanReport = lock.mutex.withLock {
    val nasNow = clocks.nasNowMs() ?: return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.NO_NAS_TIME)
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    var removed = 0
    var failed = 0
    val kept = LinkedHashMap<OwnKeepReason, Int>()
    var stop = PurgeStop.COMPLETE
    for (share in shares) {
      if (clocks.monotonicMs() >= deadline) {
        stop = PurgeStop.BUDGET
        break
      }
      if (gate.statusOf(share).blocksScheduled) {
        stop = PurgeStop.BLOCKED
        continue
      }
      val collected = try {
        facts(share, nasNow)
      } catch (_: Unreachable) {
        stop = PurgeStop.NAS_UNREACHABLE
        continue
      }
      val plan = policy.plan(collected.facts, nasNow, suspended = false)
      plan.kept.forEach { (reason, n) -> kept.merge(reason, n, Int::plus) }
      for (path in plan.remove) {
        if (clocks.monotonicMs() >= deadline) {
          stop = PurgeStop.BUDGET
          break
        }
        val size = collected.sizes[path] ?: continue
        when (val r = fileOps.deleteIfUnchanged(share, path, size, purpose)) {
          is NasResult.Err -> {
            failed++
            if (r.error != NasError.PermissionDenied) {
              stop = PurgeStop.NAS_UNREACHABLE
              break
            }
          }
          is NasResult.Ok -> when (r.value) {
            DeleteOutcome.Deleted, DeleteOutcome.Missing -> removed++
            // Cambiato dall'elenco (riscritto da qualcuno) o aperto: resta, al giro dopo si rivede.
            is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile, DeleteOutcome.Locked -> failed++
          }
        }
      }
      if (stop == PurgeStop.NAS_UNREACHABLE || stop == PurgeStop.BUDGET) break
    }
    if (removed > 0) GalleryLog.i(TAG) { "pulizia dei propri file: $removed tolti" }
    CleanReport(removed, kept, failed, stop)
  }

  private class Collected(val facts: List<OwnFileFacts>, val sizes: Map<SharePath, Long>)

  private suspend fun facts(share: String, nasNow: Long): Collected {
    val facts = ArrayList<OwnFileFacts>()
    val sizes = HashMap<SharePath, Long>()
    val state = registry()
    // Teste e segmenti propri, per tipo: solo con il registro (la firma si verifica) e con almeno una testa valida.
    if (state != null) {
      for (kind in HeadKind.entries) headsAndSegments(share, kind, state, facts, sizes)
      leases(share, state, facts, sizes)
    }
    temps(share, facts, sizes)
    return Collected(facts, sizes)
  }

  private suspend fun list(share: String, dir: SharePath): List<NasEntry> {
    val out = ArrayList<NasEntry>()
    return when (val r = reader.list(share, dir, purpose) { page -> if (out.size < MAX_LISTED) out += page.take(MAX_LISTED - out.size) }) {
      is NasResult.Ok -> out
      is NasResult.Err -> if (r.error == NasError.NotFound) emptyList() else throw Unreachable()
    }
  }

  /** I byte di un file firmato piccolo (al massimo [max]); null se e' troppo grande o sparito. */
  private suspend fun readSmall(share: String, path: SharePath, size: Long, max: Int): ByteArray? {
    if (size <= 0 || size > max) return null
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) null else throw Unreachable()
      is NasResult.Ok -> opened.value
    }
    return file.use {
      try {
        if (it.size > max) null else it.readRange(0L until it.size)
      } catch (_: NasIoException) {
        throw Unreachable()
      }
    }
  }

  private suspend fun headsAndSegments(share: String, kind: HeadKind, state: RegistryState, facts: MutableList<OwnFileFacts>, sizes: MutableMap<SharePath, Long>) {
    val headDir = NasLayout.headDir(deviceId, kind.wire)
    val heads = list(share, headDir).filter { !it.isDirectory }
    val valid = HashMap<Long, Set<String>>()
    val headFiles = ArrayList<Pair<OwnFile.Head, Boolean>>()
    for (entry in heads.sortedByDescending { it.name }.take(MAX_HEADS_READ)) {
      val path = runCatching { headDir.child(entry.name) }.getOrNull() ?: continue
      val file = OwnFile.classify(path) as? OwnFile.Head ?: continue
      val bytes = readSmall(share, path, entry.size, DocType.DEVICE_HEAD.maxFileBytes)
      val accepted = bytes?.let {
        HeadVerifier.accept(it, state, HeadLocation(share, deviceId, kind, file.version), previous = null, readerAccount = accountId)
      }
      val mine = accepted is Verdict.Accepted && accepted.value.signer.id == deviceId
      if (mine) valid[file.version] = (accepted as Verdict.Accepted).value.head.segments.mapTo(HashSet()) { it.ref.hash }
      headFiles += file to mine
      sizes[path] = entry.size
    }
    for ((file, mine) in headFiles) facts += OwnFileFacts.HeadFacts(file, signedByMe = mine, ownVersions = valid.keys)
    // Senza nessuna testa valida non si sa che cosa e' elencato: i segmenti restano tutti.
    if (valid.isEmpty()) return
    val recent = valid.keys.sortedDescending().take(TrustLimits.OWN_FILES_KEEP_HEADS).flatMapTo(HashSet()) { valid.getValue(it) }
    if (kind == HeadKind.INDEX) recent += currentSegments(share)
    val diary = if (kind == HeadKind.INDEX) publishedSegments(share) else emptySet()
    val segDir = NasLayout.ROOT.child("devices").child(deviceId).child(kind.wire).child("seg")
    for (entry in list(share, segDir)) {
      if (entry.isDirectory) continue
      val path = runCatching { segDir.child(entry.name) }.getOrNull() ?: continue
      val file = OwnFile.classify(path) as? OwnFile.Segment ?: continue
      facts += OwnFileFacts.SegmentFacts(file, listedByRecentHeads = file.hash in recent, inDiary = file.hash in diary, modifiedAtNas = entry.modifiedAt)
      sizes[path] = entry.size
    }
  }

  private suspend fun leases(share: String, state: RegistryState, facts: MutableList<OwnFileFacts>, sizes: MutableMap<SharePath, Long>) {
    val root = NasLayout.ROOT.child("leases")
    var reads = 0
    for (resource in list(share, root)) {
      if (!resource.isDirectory) continue
      val resourceDir = runCatching { root.child(resource.name) }.getOrNull() ?: continue
      val generations = ArrayList<Pair<OwnFile.LeaseGeneration, Long>>()
      for (shard in list(share, resourceDir)) {
        if (!shard.isDirectory) continue
        val shardDir = runCatching { resourceDir.child(shard.name) }.getOrNull() ?: continue
        for (entry in list(share, shardDir)) {
          if (entry.isDirectory) continue
          val path = runCatching { shardDir.child(entry.name) }.getOrNull() ?: continue
          val file = OwnFile.classify(path) as? OwnFile.LeaseGeneration ?: continue
          generations += file to entry.size
        }
      }
      val highest = generations.maxOfOrNull { it.first.generation } ?: continue
      // La generazione piu' alta resta sempre (anche se mia): si leggono solo quelle superate, con un tetto per giro.
      for ((file, size) in generations) {
        if (file.generation >= highest) continue
        if (reads >= MAX_LEASE_READS) return
        reads++
        val bytes = readSmall(share, file.path, size, DocType.LEASE.maxFileBytes)
        val verdict = bytes?.let { Leases.verifyAt(it, state, share, file.resourceHash, file.generation) }
        val mine = verdict is Verdict.Accepted && verdict.value.deviceId == deviceId
        facts += OwnFileFacts.LeaseFacts(file, signedByMe = mine, highestGeneration = highest)
        sizes[file.path] = size
      }
    }
  }

  private suspend fun temps(share: String, facts: MutableList<OwnFileFacts>, sizes: MutableMap<SharePath, Long>) {
    val dir = NasLayout.ROOT.child("tmp")
    val cited = citedTemps()
    for (entry in list(share, dir)) {
      if (entry.isDirectory) continue
      val path = runCatching { dir.child(entry.name) }.getOrNull() ?: continue
      val file = OwnFile.classify(path) as? OwnFile.Temp ?: continue
      if (file.deviceId != deviceId) continue
      facts += OwnFileFacts.TempFacts(file, modifiedAtNas = entry.modifiedAt, citedByDiary = path in cited)
      sizes[path] = entry.size
    }
  }

  /** I temporanei citati da un elemento non chiuso del diario delle operazioni: non si toccano (formato §2.3, M6). */
  private suspend fun citedTemps(): Set<SharePath> {
    val out = HashSet<SharePath>()
    for (op in store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))) {
      store.items(op.id).filter { !it.state.terminal }.mapNotNullTo(out) { it.tempName }
    }
    return out
  }

  private companion object {
    const val TAG = "OwnFiles"

    /** Teste lette per tipo e per giro (le piu' recenti). */
    const val MAX_HEADS_READ = 500

    /** Prenotazioni superate lette per giro. */
    const val MAX_LEASE_READS = 1_000

    /** Voci per cartella dell'area dell'app considerate in un giro. */
    const val MAX_LISTED = 20_000
  }
}
