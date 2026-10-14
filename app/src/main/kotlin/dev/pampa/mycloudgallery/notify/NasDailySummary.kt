package dev.pampa.mycloudgallery.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.HeartbeatStore
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.work.NasStages
import java.io.File

/**
 * Il **riepilogo giornaliero dei problemi** del NAS nella Libreria (proposta 12 del piano; docs/fase4-progetto.md §1.4).
 * Una notifica al giorno, sul canale "Problemi del NAS", solo se c'e' qualcosa da dire, **mai** con nomi di share, cartelle
 * o file (solo conteggi):
 *
 * - [Problem.Frozen]: aree congelate per cambiamenti di massa (D10). Per la risposta Q6 dell'utente il congelamento non ha
 *   una notifica a se': e' **qui**, e in evidenza nella Libreria e in Diagnostica;
 * - [Problem.NasSilent]: il NAS non ha risposto a nessuna verifica nelle ultime 26 ore (E-R5, "non ha risposto stanotte");
 * - [Problem.Stalled]: la verifica o la sincronizzazione dell'indice non finiscono un giro da [STALL_DAYS] giorni (E-P15).
 *
 * La regola e' pura ([problems]); il lavoro ([NasDailySummaryWorker]) legge gli ingressi e notifica.
 */
object NasDailySummary {

  sealed interface Problem {
    data class Frozen(val areas: Int) : Problem

    data object NasSilent : Problem

    data class Stalled(val days: Int) : Problem
  }

  const val STALL_DAYS: Int = 3
  private const val DAY_MS: Long = 24L * 60 * 60 * 1000
  private const val SILENT_MS: Long = 26L * 60 * 60 * 1000

  /**
   * @param configuredSinceMs da quando il NAS e' configurato in questo dispositivo (ora del telefono; null = non configurato):
   *   un dispositivo appena configurato non ha ancora battiti e non e' "fermo".
   */
  fun problems(frozenAreas: Int, lastScanAt: Long?, lastSyncAt: Long?, configuredSinceMs: Long?, now: Long): List<Problem> {
    if (configuredSinceMs == null) return emptyList()
    val out = ArrayList<Problem>()
    if (frozenAreas > 0) out += Problem.Frozen(frozenAreas)
    val scanAge = now - (lastScanAt ?: configuredSinceMs)
    if (scanAge > SILENT_MS) out += Problem.NasSilent
    val oldest = listOf(lastScanAt ?: configuredSinceMs, lastSyncAt ?: configuredSinceMs).min()
    val days = ((now - oldest) / DAY_MS).toInt()
    if (days >= STALL_DAYS) out += Problem.Stalled(days)
    return out
  }
}

@HiltWorker
class NasDailySummaryWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val notifications: AppNotifications,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val nas = gallery.nas ?: return Result.success()
    if (!gallery.backgroundWorkAllowed || nas.runtime == null && !nas.start()) return Result.success()
    val noBackup = applicationContext.noBackupFilesDir
    val now = graph.platform.deviceNow()
    val problems = NasDailySummary.problems(
      frozenAreas = nas.frozen.value.size,
      lastScanAt = HeartbeatStore(File(noBackup, NasStages.HEARTBEAT_SCAN)).lastBeatAt(),
      lastSyncAt = HeartbeatStore(File(noBackup, NasStages.HEARTBEAT_SYNC)).lastBeatAt(),
      configuredSinceMs = graph.onboardingStore.snapshotOrNull()?.finishedAt,
      now = now,
    )
    notifications.notifyDailySummary(problems)
    return Result.success()
  }
}
