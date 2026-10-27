package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.EraseRoundReport
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseLimits
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LA CANCELLAZIONE STRETTA DEI DATI DEI VOLTI SUL NAS (Fase 8, P8; docs/fase8-progetto.md §2.8 passi 8-9, D29, D31, D35, E-F34; formato §9
 * punto 6-bis). L'unica cancellazione guidata da un dato remoto oltre l'auto-azzeramento, quindi stretta in ogni punto:
 *
 * - **solo l'area privata dichiarata dell'account** ([privateShare]), mai l'area comune del registro ([commonShare]), mai un'altra share:
 *   [FacesEraseStop.NOT_PRIVATE_AREA] prima di qualunque richiesta;
 * - **solo dietro il cancello** ([gate]: congelamento, emergenza, salto dell'ora, fiducia, sola lettura, recinzione D34);
 * - **solo la regola di D31**: mai la generazione corrente ([facts]); nel dubbio (stato non fresco, voce sconosciuta) niente
 *   ([FacesEraseStop.UNSURE]); la cartella di un dispositivo della generazione corrente solo se il registro accettato lo dice revocato o perso;
 * - **solo file dei volti**: la visita stretta di [FacesNasTree] (forma esatta, generazione giusta, nessun collegamento, nessun ciclo); un file
 *   il cui `fileId` il catalogo conosce come media ([presence]) non si tocca mai, anche se sta sotto `faces/` (un collegamento fisico);
 * - **un file per volta, con un solo handle** (`deleteIfUnchanged` con il timbro appena letto): un file cambiato o aperto resta per il giro dopo;
 * - al piu' [maxFilesPerRound] file e [budget] di tempo per giro: il resto al giro dopo ([FacesEraseStop.BUDGET]).
 *
 * **Idempotente**: togliere cio' che non c'e' e' [FacesEraseStop.DONE]. Le cartelle rimaste vuote **restano** (le primitive di core-nas tolgono
 * cartelle solo fuori da `.mycloudgallery/` o nel proprio cestino: limite dichiarato in `docs/sicurezza.md`); "presente" vuol dire "c'e' ancora
 * un file dei volti". Mai nomi di file, generazioni o id nei log (D37): solo numeri.
 */
class NasFacesEraser internal constructor(
  private val fileOps: NasFileOps,
  reader: NasMediaReader,
  private val facts: FacesEraseFacts,
  /** L'area privata dichiarata dell'account (null = nessuna: niente si cancella). */
  private val privateShare: () -> String?,
  /** L'area comune del registro accettato (null = non si sa). */
  private val commonShare: () -> String?,
  private val gate: suspend (String) -> FacesEraseStop?,
  private val clocks: OpClocks,
  private val presence: CatalogPresence = CatalogPresence.NONE,
  private val maxFilesPerRound: Int = FacesEraseLimits.MAX_FILES_PER_ROUND,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : FacesNasEraser {

  init {
    require(maxFilesPerRound >= 1) { "Almeno un file per giro." }
  }

  private val tree = FacesNasTree(reader, purpose)
  private val lock = Mutex()

  override suspend fun listGenerations(share: String): FacesGenerations = lock.withLock {
    preflight(share)?.let { return@withLock FacesGenerations.Stopped(it) }
    try {
      val (generations, ignored, truncated) = tree.generations(share, TrustLimits.MAX_LIST_FACES_GENERATIONS)
      FacesGenerations.Listed(generations, ignored, truncated)
    } catch (_: FacesTreeUnreachable) {
      FacesGenerations.Stopped(FacesEraseStop.UNREACHABLE)
    }
  }

  override suspend fun eraseGeneration(share: String, generation: FaceGeneration, reason: FacesEraseReason, budget: Duration): EraseRoundReport =
    lock.withLock {
      preflight(share)?.let { return@withLock stopped(it) }
      // L'intera generazione solo per una cancellazione confermata o per una generazione non corrente: mai per una revoca (D31 (b)).
      if (reason == FacesEraseReason.DEVICE_REVOKED) return@withLock stopped(FacesEraseStop.NOT_ALLOWED)
      when (val current = currentOrNull()) {
        null -> return@withLock stopped(FacesEraseStop.UNSURE)
        is FacesCurrent.Known -> if (current.generation == generation) return@withLock stopped(FacesEraseStop.NOT_ALLOWED)
        FacesCurrent.Unknown -> return@withLock stopped(FacesEraseStop.UNSURE)
      }
      erase(share, generation, deviceOnly = null, budget)
    }

  override suspend fun eraseDevice(
    share: String,
    generation: FaceGeneration,
    deviceId: String,
    reason: FacesEraseReason,
    budget: Duration,
  ): EraseRoundReport = lock.withLock {
    preflight(share)?.let { return@withLock stopped(it) }
    if (!TrustIds.isValid(deviceId)) return@withLock stopped(FacesEraseStop.NOT_ALLOWED)
    val current = when (val c = currentOrNull()) {
      is FacesCurrent.Known -> c
      FacesCurrent.Unknown, null -> return@withLock stopped(FacesEraseStop.UNSURE)
    }
    val allowed = when (reason) {
      FacesEraseReason.DEVICE_REVOKED -> try {
        facts.revokedOrLost(deviceId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        return@withLock stopped(FacesEraseStop.UNSURE)
      }
      FacesEraseReason.NOT_CURRENT, FacesEraseReason.USER_CONFIRMED -> current.generation != generation
    }
    if (!allowed) return@withLock stopped(FacesEraseStop.NOT_ALLOWED)
    erase(share, generation, deviceOnly = deviceId, budget)
  }

  override suspend fun verify(share: String, generation: FaceGeneration): FacesEraseCheck = lock.withLock {
    if (!isPrivateArea(share)) return@withLock FacesEraseCheck.Stopped(FacesEraseStop.NOT_PRIVATE_AREA)
    try {
      val walk = tree.walk(share, generation, deviceOnly = null, max = VERIFY_SAMPLE)
      val present = walk.files.isNotEmpty() && mediaIds(share, walk.files).let { media -> walk.files.any { it.entry.fileId !in media } }
      val bin = FacesEraseLimits.RECYCLE_BIN_DIRS.any { tree.recycleBinHolds(share, it) }
      FacesEraseCheck.Checked(generationPresent = present, recycleBinCopy = bin)
    } catch (_: FacesTreeUnreachable) {
      FacesEraseCheck.Stopped(FacesEraseStop.UNREACHABLE)
    } catch (_: PresenceUnavailable) {
      FacesEraseCheck.Stopped(FacesEraseStop.UNREACHABLE)
    }
  }

  // --- implementazione --------------------------------------------------------------------------------------------------------

  /** Il catalogo non ha risposto: senza sapere quali file sono media non si cancella niente. */
  private class PresenceUnavailable : RuntimeException(null, null, false, false)

  private fun isPrivateArea(share: String): Boolean {
    val private = privateShare() ?: return false
    if (!share.equals(private, ignoreCase = true)) return false
    val common = commonShare() ?: return false
    return !share.equals(common, ignoreCase = true)
  }

  private suspend fun preflight(share: String): FacesEraseStop? {
    if (!isPrivateArea(share)) return FacesEraseStop.NOT_PRIVATE_AREA
    return try {
      gate(share)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Un cancello che non risponde e' un cancello chiuso.
      FacesEraseStop.NOT_TRUSTED
    }
  }

  private suspend fun currentOrNull(): FacesCurrent? = try {
    facts.current()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  /** I `fileId` di [files] che il catalogo conosce come media (presenti in qualche cartella): mai cancellati. */
  private suspend fun mediaIds(share: String, files: List<FacesFound>): Set<Long> {
    val ids = files.mapNotNull { it.entry.fileId }
    if (ids.isEmpty()) return emptySet()
    val present = try {
      presence.presentPaths(share, ids)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      throw PresenceUnavailable()
    }
    return present.filterValues { it.isNotEmpty() }.keys
  }

  private suspend fun erase(share: String, generation: FaceGeneration, deviceOnly: String?, budget: Duration): EraseRoundReport {
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds.coerceAtLeast(0)
    val walk = try {
      tree.walk(share, generation, deviceOnly, max = maxFilesPerRound)
    } catch (_: FacesTreeUnreachable) {
      return stopped(FacesEraseStop.UNREACHABLE)
    }
    val media = try {
      mediaIds(share, walk.files)
    } catch (_: PresenceUnavailable) {
      return stopped(FacesEraseStop.UNREACHABLE)
    }
    var removed = 0
    var retry = 0
    var denied = 0
    var skippedMedia = 0
    var stop: FacesEraseStop? = null
    for (found in walk.files) {
      val id = found.entry.fileId
      if (id != null && id in media) {
        skippedMedia++
        continue
      }
      if (clocks.monotonicMs() >= deadline) {
        stop = FacesEraseStop.BUDGET
        break
      }
      when (val r = fileOps.deleteIfUnchanged(share, found.file.path, found.entry.size, purpose, found.entry.stamp)) {
        is NasResult.Err -> if (r.error == NasError.PermissionDenied) {
          denied++
        } else {
          stop = FacesEraseStop.UNREACHABLE
          break
        }
        is NasResult.Ok -> when (r.value) {
          DeleteOutcome.Deleted, DeleteOutcome.Missing -> removed++
          // Cambiato dall'elenco, una cartella al posto di un file, o aperto da un altro: resta, al giro dopo si rivede.
          is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile, DeleteOutcome.Locked -> retry++
        }
      }
    }
    if (skippedMedia > 0) GalleryLog.w(TAG) { "cancellazione dei volti: $skippedMedia file che il catalogo conosce come media, non toccati" }
    val left = walk.files.size - removed - skippedMedia
    val finalStop = stop ?: when {
      walk.incomplete || retry > 0 -> FacesEraseStop.BUDGET
      denied > 0 && left == denied -> FacesEraseStop.READ_ONLY
      left > 0 -> FacesEraseStop.BUDGET
      else -> FacesEraseStop.DONE
    }
    if (removed > 0) GalleryLog.i(TAG) { "cancellazione dei volti: $removed file tolti" }
    return EraseRoundReport(removedFiles = removed, removedDirs = 0, remainingFiles = if (walk.incomplete) null else left, stoppedBy = finalStop)
  }

  private fun stopped(stop: FacesEraseStop) = EraseRoundReport(0, 0, null, stop)

  override fun toString(): String = "NasFacesEraser"

  private companion object {
    const val TAG = "FacesNasEraser"

    /** I file guardati dalla verifica: basta sapere se ce n'e' almeno uno che non e' un media. */
    const val VERIFY_SAMPLE = 64
  }
}
