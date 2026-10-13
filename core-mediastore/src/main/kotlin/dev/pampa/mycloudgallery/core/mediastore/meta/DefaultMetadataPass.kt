package dev.pampa.mycloudgallery.core.mediastore.meta

import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.sync.MediaStoreRowLookup
import dev.pampa.mycloudgallery.core.mediastore.sync.OriginalBytesUnavailableException
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneCatalogReads
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncConfig
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.GroupCandidate
import dev.pampa.mycloudgallery.core.metadata.contract.GroupingRules
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import java.io.IOException
import java.time.ZoneId
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La fase META per i media del telefono gia' nel catalogo (docs/fase3-progetto.md §2.1 "Dopo il commit", §2.2, D11):
 * metadati, luogo, tipo definitivo, ThumbHash (tutto dall'estrattore di `core-metadata`) e gruppi proposti. **Prima i
 * visibili** ([MetaPriority]), poi i piu' recenti, poi i falliti da riprovare.
 *
 * **Si leggono solo i byte della versione corrente.** La riga del MediaStore si rilegge per `_ID` ([MediaStoreRowLookup])
 * e deve avere lo stesso posto e lo stesso timbro della posizione nel catalogo: se il file e' cambiato, prima la
 * sincronizzazione crea la versione nuova, poi META la legge. Un `_ID` di un'altra versione del MediaStore non si usa.
 *
 * **Le foto aspettano il permesso della posizione** (D9, E-P21): senza, l'EXIF arriva oscurato e il luogo sarebbe
 * sbagliato. I video proseguono.
 *
 * Esiti: letto → metadati e tipo nel catalogo, fase fatta; corrotto → tentativo fallito (dopo 5 la cella e' "non
 * leggibile", E-M10); non un media → arreso subito (E-M12); formato non supportato → "non supportato".
 */
class DefaultMetadataPass(
  private val source: MediaStoreSource,
  private val lookup: MediaStoreRowLookup,
  private val access: PhoneAccessReader,
  private val catalog: MediaCatalog,
  private val reads: PhoneCatalogReads,
  private val extractor: MetadataExtractor,
  private val zone: () -> ZoneId = ZoneId::systemDefault,
  private val clock: () -> Long = System::currentTimeMillis,
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val config: PhoneSyncConfig = PhoneSyncConfig(),
  private val stats: PhoneLibraryStats? = null,
) : MetadataPass {

  private val lock = Mutex()

  override suspend fun run(budget: Duration, priority: MetaPriority): RunResult = lock.withLock {
    val deadline = monotonicMs() + budget.inWholeMilliseconds
    withContext(dispatchers.io) { Pass(deadline, priority).execute() }
  }

  private enum class Outcome { DONE, SKIPPED }

  private inner class Pass(private val deadline: Long, private val priority: MetaPriority) {
    private val tried = HashSet<MediaId>()
    private val groupCandidates = ArrayList<GroupCandidate>()
    private val storeVersions = HashMap<String, String?>()
    private var mediaLocation = false
    private var waitingForLocation = 0
    private var done = 0L
    private var failed = 0L

    suspend fun execute(): RunResult {
      access.refresh()
      val now = access.state.value
      if (now.media == MediaAccess.NONE) return RunResult.Paused(PauseReason.NO_PERMISSION)
      mediaLocation = now.mediaLocation
      try {
        while (true) {
          if (overBudget()) return finish(RunResult.BudgetExhausted(remaining = null))
          val next = nextBatch()
          if (next.isEmpty()) break
          for (id in next) {
            if (overBudget()) break
            tried += id
            processOne(id)
          }
          if (tried.size >= config.metaMaxSkips + done + failed) break
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        return finish(RunResult.Paused(PauseReason.NO_PERMISSION))
      }
      return finish(if (waitingForLocation > 0) RunResult.Paused(PauseReason.MEDIA_LOCATION_DENIED) else RunResult.Done)
    }

    private suspend fun finish(result: RunResult): RunResult {
      proposeGroups()
      stats?.update { s ->
        s.copy(metaDone = s.metaDone + done, metaFailed = s.metaFailed + failed, metaWaitingForLocation = waitingForLocation)
      }
      return result
    }

    /** Prima i visibili, poi la coda dai piu' recenti, poi i falliti da riprovare. Mai due volte lo stesso in un giro. */
    private suspend fun nextBatch(): List<MediaId> {
      val visible = ArrayList<MediaId>()
      for (id in priority.visible.value) {
        if (visible.size >= config.metaBatch) break
        if (id in tried) continue
        if (isMetaToDo(id)) visible += id
      }
      if (visible.isNotEmpty()) return visible
      val limit = tried.size + config.metaBatch
      val pending = reads.pendingMeta(limit).filter { it !in tried }.take(config.metaBatch)
      if (pending.isNotEmpty()) return pending
      val retryBefore = clock() - config.metaRetryAfter.inWholeMilliseconds
      return reads.retryableMeta(retryBefore, limit).filter { it !in tried }.take(config.metaBatch)
    }

    private suspend fun isMetaToDo(id: MediaId): Boolean {
      val state = catalog.workStates(id).firstOrNull { it.stage == WorkStage.META } ?: return false
      return state.status == WorkStatus.PENDING || state.status == WorkStatus.IN_PROGRESS ||
        (state.status == WorkStatus.FAILED && state.updatedAtMillis <= clock() - config.metaRetryAfter.inWholeMilliseconds)
    }

    private suspend fun processOne(id: MediaId): Outcome {
      val media = catalog.media(id) ?: return Outcome.SKIPPED
      val state = catalog.workStates(media.id).firstOrNull { it.stage == WorkStage.META } ?: return Outcome.SKIPPED
      if (state.status == WorkStatus.DONE || state.status == WorkStatus.UNSUPPORTED || state.status == WorkStatus.GAVE_UP) {
        return Outcome.SKIPPED
      }
      val version = media.currentVersion
      val known = media.locations.firstOrNull {
        it.versionId == version.id && it.isPresent && (it.location as? Location.Phone)?.mediaStoreId != null
      } ?: return Outcome.SKIPPED
      val phone = known.location as Location.Phone
      val current = storeVersions.getOrPut(phone.volume) { catalog.mediaStoreCursor(phone.volume)?.msVersion }
      // Un _ID letto con un'altra versione del MediaStore puo' essere di un altro file (E-P22).
      if (current == null || phone.mediaStoreVersion != current) return Outcome.SKIPPED
      val row = lookup.rowsById(phone.volume, listOf(checkNotNull(phone.mediaStoreId))).firstOrNull() ?: return Outcome.SKIPPED
      val rowLocation = try {
        row.phoneLocation(current)
      } catch (e: IllegalArgumentException) {
        return Outcome.SKIPPED
      }
      // Stesso posto e stessi byte della versione: altrimenti prima la sincronizzazione crea la versione nuova.
      if (rowLocation.key != phone.key || !row.stamp().looksUnchangedFrom(known.stamp)) return Outcome.SKIPPED
      if (row.type == RowType.IMAGE && !mediaLocation) {
        waitingForLocation++
        return Outcome.SKIPPED
      }
      val bytes = try {
        source.open(row)
      } catch (e: OriginalBytesUnavailableException) {
        mediaLocation = false
        waitingForLocation++
        return Outcome.SKIPPED
      } catch (e: IOException) {
        fail(media.id, state, version.id, READ_FAILED)
        return Outcome.DONE
      }
      val extraction = try {
        extractor.extract(bytes, row.toExtractHints(zone()))
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        null
      } finally {
        try {
          (bytes as? AutoCloseable)?.close()
        } catch (_: Exception) {
          // ignorata: la lettura e' finita
        }
      }
      val now = clock()
      when (extraction) {
        null -> fail(media.id, state, version.id, READ_FAILED)
        is Extraction.Read -> {
          catalog.updateMetadata(version.id, extraction.metadata, extraction.kind)
          catalog.updateWork(media.id, WorkRules.succeeded(state, version.id, now))
          done++
          groupCandidates += GroupCandidate(
            id = media.id,
            folderKey = phone.folderKey,
            displayName = phone.displayName,
            mime = extraction.metadata.mimeType ?: row.mime,
            kind = extraction.kind,
            takenAtMs = extraction.metadata.captureTime?.utcMillis ?: row.dateTakenMs ?: row.dateModifiedMs,
            hint = extraction.groupHint,
          )
        }
        is Extraction.NotMedia -> {
          // Non e' un media (E-M12): inutile riprovare.
          catalog.updateWork(
            media.id,
            state.copy(
              status = WorkStatus.GAVE_UP,
              versionId = version.id,
              attempts = WorkRules.MAX_ATTEMPTS,
              lastError = WorkErrorCode(extraction.code.name),
              updatedAtMillis = now,
            ),
          )
          failed++
        }
        is Extraction.Corrupt -> fail(media.id, state, version.id, extraction.code.name)
        is Extraction.Unsupported -> catalog.updateWork(media.id, WorkRules.unsupported(state.copy(versionId = version.id), now))
      }
      return Outcome.DONE
    }

    private suspend fun fail(id: MediaId, state: WorkState, versionId: VersionId, code: String) {
      catalog.updateWork(id, WorkRules.failed(state.copy(versionId = versionId), WorkErrorCode(code), clock()))
      failed++
    }

    /** Raffiche e RAW+JPEG fra i media letti in questo giro (D20: nel dubbio non si raggruppa). */
    private suspend fun proposeGroups() {
      if (groupCandidates.size < 2) return
      val proposals = GroupingRules.propose(groupCandidates.toList())
      groupCandidates.clear()
      for (proposal in proposals) {
        val alreadyGrouped = proposal.members.any { catalog.media(it)?.groupId != null }
        if (alreadyGrouped) continue
        try {
          catalog.group(proposal.kind, proposal.members, proposal.cover)
        } catch (e: IllegalArgumentException) {
          GalleryLog.w(TAG) { "gruppo proposto non valido" }
        } catch (e: IllegalStateException) {
          GalleryLog.w(TAG) { "gruppo proposto su un media sparito" }
        }
      }
    }

    private fun overBudget(): Boolean = monotonicMs() >= deadline
  }

  private companion object {
    const val TAG = "PhoneMeta"
    const val READ_FAILED = "READ_FAILED"
  }
}
