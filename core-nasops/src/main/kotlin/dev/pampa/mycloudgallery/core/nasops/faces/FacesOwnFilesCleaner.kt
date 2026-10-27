package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.CleanReport
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHeadLocation
import dev.pampa.mycloudgallery.core.trust.data.FacesOwnFile
import dev.pampa.mycloudgallery.core.trust.data.FacesOwnFileFacts
import dev.pampa.mycloudgallery.core.trust.data.FacesOwnFilesPolicy
import dev.pampa.mycloudgallery.core.trust.data.Leases
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LA PULIZIA DEI PROPRI FILE DEI VOLTI (Fase 8, P8; formato §2.3 riga "Teste, segmenti e prenotazioni propri dei volti", A8; M6). Gira nella
 * stessa esecuzione di `OwnFilesCleaner` (la compone `:app`, P13) e con le stesse regole: **decide** `FacesOwnFilesPolicy` di core-trust
 * (ultime 3 teste, segmenti elencati da loro o nel diario del pubblicatore, 7 giorni di grazia, la prenotazione piu' alta resta), file per
 * file, con prove raccolte qui; **toglie** `deleteIfUnchanged` con il timbro appena letto, al piu' `MAX_OWN_FILES_PER_ROUND` file per giro.
 *
 * Solo nella **generazione corrente** dell'account ([facts], stato fresco) e solo nell'area privata dichiarata: le generazioni non correnti si
 * tolgono intere con [NasFacesEraser] (D31), mai file per file da qui. Le prove non vengono mai da un dato remoto "di fiducia": una testa e'
 * mia solo se la firma e' mia **e** il posto e' mio ([FacesDocs.decodeHead]); i segmenti si giudicano con le mie teste valide e con il diario
 * locale del pubblicatore ([publishedSegments]); una prenotazione e' mia solo se la firma lo dice. Nel dubbio (registro non pronto, nessuna testa
 * valida, ora del NAS assente, cancello chiuso) si tiene tutto.
 */
class FacesOwnFilesCleaner internal constructor(
  private val deviceId: String,
  /** L'account del NAS di questo dispositivo (le teste dei volti valgono solo per lui). */
  private val accountId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val facts: FacesEraseFacts,
  private val registry: () -> RegistryState?,
  /** Il diario del pubblicatore dei volti (`FacesPublishState.diary`): i segmenti che questo dispositivo ha scritto nella generazione. */
  private val publishedSegments: suspend (FaceGeneration) -> Set<String>,
  private val privateShare: () -> String?,
  private val commonShare: () -> String?,
  private val gate: suspend (String) -> FacesEraseStop?,
  private val clocks: OpClocks,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : OwnFilesCleaning {

  private val tree = FacesNasTree(reader, purpose)
  private val lock = Mutex()

  /** Pulisce i propri file dei volti nell'area privata, se e' fra [shares]. */
  override suspend fun clean(shares: List<String>, budget: Duration): CleanReport = lock.withLock {
    val share = privateShare()?.takeIf { private -> shares.any { it.equals(private, ignoreCase = true) } }
      ?: return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.COMPLETE)
    val common = commonShare()
    if (common == null || common.equals(share, ignoreCase = true)) return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.BLOCKED)
    if (blocked(share)) return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.BLOCKED)
    val nasNow = clocks.nasNowMs() ?: return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.NO_NAS_TIME)
    val generation = current() ?: return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.COMPLETE)
    val state = registry() ?: return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.BLOCKED)
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds.coerceAtLeast(0)
    val collected = try {
      collect(share, generation, state)
    } catch (_: FacesTreeUnreachable) {
      return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.NAS_UNREACHABLE)
    } catch (_: Unreachable) {
      return@withLock CleanReport(0, emptyMap(), 0, PurgeStop.NAS_UNREACHABLE)
    }
    val plan = FacesOwnFilesPolicy(deviceId, generation).plan(collected.facts, nasNow, suspended = false)
    var removed = 0
    var failed = 0
    var stop = PurgeStop.COMPLETE
    for (path in plan.remove) {
      if (clocks.monotonicMs() >= deadline) {
        stop = PurgeStop.BUDGET
        break
      }
      val entry = collected.entries[path] ?: continue
      when (val r = fileOps.deleteIfUnchanged(share, path, entry.size, purpose, entry.stamp)) {
        is NasResult.Err -> {
          failed++
          if (r.error != NasError.PermissionDenied) {
            stop = PurgeStop.NAS_UNREACHABLE
            break
          }
        }
        is NasResult.Ok -> when (r.value) {
          DeleteOutcome.Deleted, DeleteOutcome.Missing -> removed++
          is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile, DeleteOutcome.Locked -> failed++
        }
      }
    }
    if (removed > 0) GalleryLog.i(TAG) { "pulizia dei propri file dei volti: $removed tolti" }
    CleanReport(removed, plan.kept, failed, stop)
  }

  // --- implementazione --------------------------------------------------------------------------------------------------------

  private class Unreachable : RuntimeException(null, null, false, false)

  private class Collected(val facts: List<FacesOwnFileFacts>, val entries: Map<SharePath, NasEntry>)

  private suspend fun blocked(share: String): Boolean = try {
    gate(share) != null
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    true
  }

  /** La generazione corrente dell'account, o null (spenta, non si sa, guasto): senza, niente si pulisce. */
  private suspend fun current(): FaceGeneration? = try {
    (facts.current() as? FacesCurrent.Known)?.generation
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  private suspend fun collect(share: String, generation: FaceGeneration, state: RegistryState): Collected {
    val walk = tree.walk(share, generation, deviceOnly = null, max = MAX_WALKED)
    val facts = ArrayList<FacesOwnFileFacts>()
    val entries = walk.files.associate { it.file.path to it.entry }
    // Teste proprie: la firma si verifica, il posto si ricontrolla (FacesDocs). Le piu' recenti per prime, con un tetto di letture.
    val heads = walk.files.filter { (it.file as? FacesOwnFile.Head)?.deviceId == deviceId }
      .sortedByDescending { (it.file as FacesOwnFile.Head).version }
    val valid = HashMap<Long, Set<String>>()
    val headFacts = ArrayList<Pair<FacesOwnFile.Head, Boolean>>()
    for ((read, found) in heads.withIndex()) {
      val file = found.file as FacesOwnFile.Head
      if (read >= MAX_HEADS_READ) {
        headFacts += file to false
        continue
      }
      val bytes = readSmall(share, found, DocType.FACES_HEAD.maxFileBytes)
      val verdict = bytes?.let {
        FacesDocs.decodeHead(it, state, FacesHeadLocation(generation, deviceId, file.version), accountId, previous = null)
      }
      val mine = verdict is Verdict.Accepted && verdict.value.signer.id == deviceId
      if (mine) valid[file.version] = (verdict as Verdict.Accepted).value.head.segments.mapTo(HashSet()) { it.ref.hash }
      headFacts += file to mine
    }
    for ((file, mine) in headFacts) facts += FacesOwnFileFacts.HeadFacts(file, signedByMe = mine, ownVersions = valid.keys)
    // Senza nessuna testa valida non si sa che cosa e' elencato: i segmenti restano tutti.
    if (valid.isNotEmpty()) {
      val recent = valid.keys.sortedDescending().take(TrustLimits.OWN_FILES_KEEP_HEADS).flatMapTo(HashSet()) { valid.getValue(it) }
      val diary = publishedSegments(generation)
      for (found in walk.files) {
        val file = found.file as? FacesOwnFile.Segment ?: continue
        if (file.deviceId != deviceId) continue
        facts += FacesOwnFileFacts.SegmentFacts(file, listedByRecentHeads = file.hash in recent, inDiary = file.hash in diary, modifiedAtNas = found.entry.modifiedAt)
      }
    }
    // Prenotazioni: la piu' alta di ogni risorsa resta sempre; delle superate si legge la firma (con un tetto per giro).
    val leases = walk.files.filter { it.file is FacesOwnFile.LeaseGeneration }.groupBy { (it.file as FacesOwnFile.LeaseGeneration).resourceHash }
    var reads = 0
    for ((_, group) in leases) {
      val highest = group.maxOf { (it.file as FacesOwnFile.LeaseGeneration).number }
      for (found in group) {
        val file = found.file as FacesOwnFile.LeaseGeneration
        if (file.number >= highest || reads >= MAX_LEASE_READS) continue
        reads++
        val bytes = readSmall(share, found, DocType.LEASE.maxFileBytes)
        val verdict = bytes?.let { Leases.verifyAt(it, state, share, file.resourceHash, file.number) }
        val mine = verdict is Verdict.Accepted && verdict.value.deviceId == deviceId
        facts += FacesOwnFileFacts.LeaseFacts(file, signedByMe = mine, highestNumber = highest)
      }
    }
    return Collected(facts, entries)
  }

  /** I byte di un file firmato piccolo (al massimo [max]); null se e' troppo grande, sparito o cambiato. */
  private suspend fun readSmall(share: String, found: FacesFound, max: Int): ByteArray? {
    val size = found.entry.size
    if (size <= 0 || size > max) return null
    val file = when (val opened = reader.open(share, found.file.path, purpose)) {
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

  override fun toString(): String = "FacesOwnFilesCleaner"

  private companion object {
    const val TAG = "FacesOwnFiles"

    /** File dei volti guardati per giro (la propria cartella e le prenotazioni della generazione). */
    const val MAX_WALKED = 60_000

    /** Teste lette per giro (le piu' recenti). */
    const val MAX_HEADS_READ = 64

    /** Prenotazioni superate lette per giro. */
    const val MAX_LEASE_READS = 1_000
  }
}
