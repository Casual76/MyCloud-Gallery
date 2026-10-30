package dev.pampa.mycloudgallery.work

import android.content.Context
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.mediastore.sync.PlacesRecompute
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * La pianificazione dei lavori della galleria del telefono (D10, docs/fase3-progetto.md §1.4, §2.1). **Fuori dal motore
 * delle regole**: e' lavoro locale e leggero, senza rete.
 *
 * - [PhoneSyncWorker]: un lavoro singolo con **trigger di contenuto** sulle immagini e sui video del MediaStore (con i
 *   discendenti; ritardo 5 s, al massimo 60 s), batteria non scarica. Quando il processo non vive e' cio' che porta le foto
 *   nuove nel catalogo (T-13). Un trigger di contenuto vale una volta: il lavoro si **riaccoda da solo** dopo ogni giro.
 * - [LibraryMaintenanceWorker]: una volta al giorno, **in carica**: META residua, hash completi rimandati, riconciliazione
 *   degli `_ID`, pulizia delle assenze con tetto.
 *
 * Idempotente: [ensure] non sposta lavori gia' pianificati. Si chiama quando la galleria parte (famiglia pronta). Nella
 * variante di misura non si pianifica niente (il dataset e' sintetico).
 */
object GalleryWorkScheduling {

  const val PHONE_SYNC_WORK = "gallery-phone-sync"
  const val MAINTENANCE_WORK = "gallery-maintenance"
  const val TAG = "gallery"

  /** Il ritardo del trigger di contenuto: le raffiche diventano un giro solo. */
  val TRIGGER_DELAY: Duration = 5.seconds

  /** Il ritardo massimo: una foto nuova entra nel catalogo al piu' un minuto dopo (con il processo morto). */
  val TRIGGER_MAX_DELAY: Duration = 60.seconds

  val MAINTENANCE_PERIOD: Duration = 24.hours

  fun ensure(context: Context) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    workManager.enqueueUniqueWork(PHONE_SYNC_WORK, ExistingWorkPolicy.KEEP, phoneSyncRequest())
    val maintenance = PeriodicWorkRequestBuilder<LibraryMaintenanceWorker>(MAINTENANCE_PERIOD.inWholeHours, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
      .addTag(TAG)
      .build()
    workManager.enqueueUniquePeriodicWork(MAINTENANCE_WORK, ExistingPeriodicWorkPolicy.KEEP, maintenance)
  }

  /** Il lavoro a trigger di contenuto (anche quello che [PhoneSyncWorker] riaccoda dopo di se'). */
  fun phoneSyncRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PhoneSyncWorker>()
    .setConstraints(
      Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        .setTriggerContentUpdateDelay(TRIGGER_DELAY.toJavaDuration())
        .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY.toJavaDuration())
        .build(),
    )
    .addTag(TAG)
    .build()
}

/**
 * La lettura del telefono in background (D10): un giro entro **8 minuti** (le tappe di WorkManager durano al massimo
 * 10), poi META dei media appena arrivati con il tempo che resta (`PhoneSyncRunner`, P2). Un giro interrotto riprende da
 * dove era. Alla fine il lavoro **si riaccoda** (trigger di contenuto: vale una volta sola).
 */
@HiltWorker
class PhoneSyncWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // L372 (correzioni C, come RG-1 e D-7): in un processo nato per questo lavoro lo stato "non configurato" dura un attimo; senza aspettare la famiglia pronta
    // `runIfActive` rispondeva null e il giro andava perso (e, peggio, la catena a trigger di contenuto non si riaccodava).
    gallery.awaitReady(COLD_START_WAIT)
    // `runIfActive` permette all'azzeramento di fermare un giro in corso e di aspettarlo (R7).
    val outcome = gallery.runIfActive { core -> core.phone.runner.run(BUDGET) }
    if (outcome == null) {
      // Famiglia non pronta nemmeno dopo l'attesa (non configurata, in attesa, azzerata) o galleria ferma: niente da leggere adesso, ma il lavoro a trigger di
      // contenuto vale una volta sola: se non si riaccoda, il prossimo cambiamento del MediaStore non trova nessuno fino al prossimo avvio dell'app.
      requeue()
      return Result.success()
    }
    GalleryLog.d(TAG) { "lettura del telefono in background: ${outcome.javaClass.simpleName}" }
    // Fase 5A (D11, E-B13): media nuovi in una sorgente del backup => il lavoro del tipo giusto (lavoro unico, KEEP); la copia del telefono
    // puo' essere sparita mentre la sola copia sul NAS e' nell'area comune => il guardiano. Dal catalogo e dal diario, nessun polling.
    gallery.backup?.let { backup ->
      try {
        // Fase 5B (P14, P9): gli esiti in dubbio di "Libera spazio" (dialogo di sistema di un processo morto) si risolvono con il telefono.
        gallery.runIfActive { backup.afterPhoneRead() }
        BackupWorkScheduling.afterPhoneRead(applicationContext, backup)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "backup dopo la lettura del telefono non chiesto: ${e.javaClass.simpleName}" }
      }
    }
    // Fase 5A (D36): dopo META, un giro del confronto delle copie simili (a tappe, su Default; chi lo chiede non aspetta).
    gallery.similarCopies?.requestRun()
    requeue()
    return Result.success()
  }

  /**
   * Il trigger di contenuto vale una volta: il prossimo cambiamento del MediaStore deve trovarne uno nuovo, accodato dopo questo (APPEND: mai annullare il lavoro in
   * corso).
   */
  private fun requeue() {
    WorkManager.getInstance(applicationContext)
      .enqueueUniqueWork(GalleryWorkScheduling.PHONE_SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, GalleryWorkScheduling.phoneSyncRequest())
  }

  companion object {
    const val TAG = "PhoneSyncWorker"
    val BUDGET: Duration = 8.minutes

    /** L372: quanto aspetta la famiglia pronta in un processo appena nato (come gli altri lavori). */
    val COLD_START_WAIT: Duration = 20.seconds
  }
}

/**
 * La manutenzione quotidiana in carica (§1.4): riconciliazione degli `_ID` e pulizia delle assenze scadute (il giro
 * `MAINTENANCE` della sincronizzazione), META residua, hash completi rimandati. Tutto entro un budget: cio' che resta si fa
 * domani. Il ricalcolo dei luoghi a un dataset nuovo (`PlacesRecompute`, debito del 27/10) e' qui, a tappe.
 */
@HiltWorker
class LibraryMaintenanceWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  /** Fase 6 (P11, §1.4): la compattazione dei file dell'indice di ricerca e i record rovinati da riavere. */
  private val search: dev.pampa.mycloudgallery.search.SearchGraph,
  /** Fase 7 (P13, §1.4, D19): viaggi ed eventi una volta per notte, e il ricalcolo di sicurezza degli album smart. */
  private val albums: dev.pampa.mycloudgallery.albums.AlbumsGraph,
  /** Fase 7 (P13, P8): l'hash completo notturno dei video del NAS (D27) e la firma visiva dei Doppioni simili. */
  private val utility: dev.pampa.mycloudgallery.utility.UtilityGraph,
  /** Fase 7 (DG-2): gli esiti e gli errori della manutenzione nella Diagnostica ("Ultimi esiti ed errori"). */
  private val graph: dev.pampa.mycloudgallery.graph.AppGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // RG-1 (e VG-1 della Fase 7): un lavoro nato in un processo nuovo aspetta la famiglia **pronta**, come tutti gli altri (D-7). Senza questa attesa `runIfActive`
    // rispondeva null (lo stato "non configurato" dura un attimo all'avvio): la manutenzione finiva in qualche decimo di secondo con `success`, senza viaggi, smart
    // ne' Utility, e riprovava solo la notte dopo. Dopo il tempo, un dispositivo davvero non pronto non fa niente (come gli altri lavori).
    gallery.awaitReady(COLD_START_WAIT)
    // DG-2: la manutenzione notturna girava senza lasciare traccia (un errore o un budget finito non arrivavano mai a "Ultimi esiti ed errori").
    // Un errore si registra (solo il nome del lavoro) e poi prosegue come prima: WorkManager lo vede.
    try {
      AlbumJobReports.maintenance(graph.events) { maintain() }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Android ha fermato la manutenzione (quota, vincoli, tempo): il motivo resta anche dopo la morte del processo.
      StopReports.report(graph, dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob.LIBRARY_MAINTENANCE, StopReports.reasonOf(this))
      throw e
    }
    return Result.success()
  }

  /** Vero se ha fatto tutto: la galleria attiva fino in fondo, anche per l'Utility. */
  private suspend fun maintain(): Boolean {
    gallery.runIfActive { search.maintain() }
    val done = gallery.runIfActive { core ->
      val phone = core.phone
      val report = phone.sync.runWithin(SyncReason.MAINTENANCE, SYNC_BUDGET)
      val meta = phone.metadataPass.run(META_BUDGET, NoMetaPriority)
      val hashes = phone.deferredHashes.run(HASH_BUDGET)
      GalleryLog.d(TAG) { "manutenzione: giro ${report.completed}, META ${meta.javaClass.simpleName}, hash ${hashes.hashed}" }
    }
    // Come tutte le scritture dell'istantanea, passa da `flush` della galleria (mai a galleria ferma).
    if (done != null) {
      runCatching { gallery.flush() }
      // Fase 5A (D36): META residua finita, quindi un giro del confronto delle copie simili.
      gallery.similarCopies?.requestRun()
    }
    // Debito del 27/10 (Fase 3 §2.3): se l'app porta un dataset dei luoghi nuovo, i `place_id` si ricalcolano una volta, a tappe, prima che i viaggi li leggano.
    val places = gallery.runIfActive { core -> PlacesRecompute(core.catalog, gallery.places).run(PLACES_BUDGET) }
    // Fase 7 (P13): la rilevazione dei viaggi (la casa e i viaggi si ricalcolano di notte, D18, D19) e il ricalcolo di sicurezza degli smart.
    gallery.runIfActive { albums.nightly(ALBUMS_BUDGET) }
    // Fase 8 (RG-1): il raggruppamento notturno delle persone non e' piu' qui: ha un lavoro suo (`FacesNightlyWorker`, dopo la tappa del calcolo e una volta al
    // giorno), con le regole di `AI_COMPUTE` e la sua finestra di Android. Qui, in coda a sette manutenzioni da 14 minuti di budget, non arrivava mai.
    // Fase 7 (P8): "Controlla di notte i video del NAS" applicato (la coda `FULL_HASH` la prende il lavoro di sicurezza del backup) e le firme
    // visive che mancano ai Doppioni simili.
    val utilityDone = gallery.runIfActive { utility.nightly(UTILITY_BUDGET) }
    // I luoghi a meta' (budget finito) non sono "fatto tutto": la notte dopo riprende dalle versioni che mancano.
    return done != null && utilityDone != null && places?.outcome != PlacesRecompute.Outcome.PARTIAL
  }

  companion object {
    const val TAG = "LibraryMaintenance"

    /** RG-1: quanto aspetta la famiglia pronta in un processo appena nato (come `FacesComputeWorker` e `IndexSyncWorker`). */
    val COLD_START_WAIT: Duration = 20.seconds
    val SYNC_BUDGET: Duration = 3.minutes
    val META_BUDGET: Duration = 3.minutes
    val HASH_BUDGET: Duration = 2.minutes
    val PLACES_BUDGET: Duration = 1.minutes
    val ALBUMS_BUDGET: Duration = 1.minutes
    val UTILITY_BUDGET: Duration = 2.minutes
  }
}
