package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import kotlin.time.Duration
import kotlinx.coroutines.flow.first

/**
 * **La ripresa dopo una scelta che toglie il blocco della batteria** (pacchetto B, BS-2 e BS2-1). Un lavoro fermo per `BatteryLow` si e' riaccodato col vincolo della carica
 * ([RuleWaits.followUpOf]): se l'utente abbassa il livello (o spegne un interruttore) la pagina passa a «in coda», ma quel lavoro aspetta ancora il cavo. Qui la scelta e' collegata allo
 * scheduler: quando un cambio **toglie il blocco** a un'attivita' ([relaxed]) il lavoro gia' accodato col vincolo della carica si **sostituisce** con una corsa senza quel vincolo.
 *
 * - **Tutti i nomi che usano un'attivita' liberata** ([targets]): le foto e i video del backup, **«Proteggi subito le foto nuove»** (`Kind.QUICK`), il calcolo AI e i volti, e le riprese `-power` dei lavori del NAS
 *   (scansione, sincronizzazione dell'indice, cestino, manutenzione, dati personali: `INDEX_SYNC`; preparazione; anteprime in alta qualita').
 * - **Solo la carica si toglie**: la richiesta nuova ha lo stesso lavoro, nessun ritardo e solo il vincolo base della rete; **non** parte niente che le altre regole (rete, fascia, tetto, caldo) vietano:
 *   il lavoro rivaluta le regole come sempre e, se un altro motivo lo ferma, si riaccoda come sempre. Le richieste fatte a mano («Fai ora», «Salva sul NAS», la ripresa dell'utente) non sono in elenco e
 *   non diventano mai richieste di sottofondo.
 * - **Si sostituisce solo cio' che aspetta la carica** ([plan]): un lavoro in attesa per la rete o per la fascia non si tocca, e una corsa che gira ([WorkInfo.State.RUNNING]) non si sostituisce.
 *   `REPLACE` e non `KEEP`: con `KEEP` il vecchio lavoro vincolato resterebbe e butterebbe la richiesta.
 *
 * Gli eventi del cursore si accorpano a monte (`AppBatteryGuard`). **Limiti noti**: tra la lettura dello stato dei lavori e la sostituzione c'e' una finestra in cui una corsa potrebbe partire, e
 * `REPLACE` la cancellerebbe: e' raro e il lavoro riprende dal suo punto (checkpoint e risultati salvati), ma puo' ripetere il media in corso; la cancellazione non e' uno stop cooperativo al confine
 * sicuro. La ripresa non distingue la carica chiesta dalla batteria da quella chiesta dalla regola del preset («Notte»): in quel caso la corsa rivaluta e il lavoro si riaccoda come prima.
 */
object BatteryResume {

  /** Che cosa fare dei lavori accodati di un nome. */
  enum class Plan { Replace, Leave }

  /** Lo stato di un lavoro di un nome: quello che serve a decidere. */
  data class Queued(val state: WorkInfo.State, val requiresCharging: Boolean)

  /**
   * Quali attivita' il cambio da [before] a [after] **libera**: quelle a cui la soglia valeva e ora non vale piu' (interruttore o regola di gruppo spenti), o vale ma a un livello piu' basso. Alzare il livello o
   * accendere un interruttore non libera niente (ferma di piu'): niente da riprendere.
   */
  fun relaxed(before: RuleSettings, after: RuleSettings): Set<BackgroundActivity> {
    val was = before.toRuleSet()
    val now = after.toRuleSet()
    return BackgroundActivity.entries.filterTo(LinkedHashSet()) { activity ->
      was.batteryThresholdApplies(activity) &&
        (!now.batteryThresholdApplies(activity) || after.batteryThresholdPercent < before.batteryThresholdPercent)
    }
  }

  /** Dallo stato dei lavori di un nome: una corsa attiva non si sostituisce; un lavoro in attesa **della carica** si sostituisce; il resto (attese per rete, fascia, tetto) non si tocca. */
  fun plan(queued: List<Queued>): Plan = when {
    queued.any { it.state == WorkInfo.State.RUNNING } -> Plan.Leave
    queued.any { (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) && it.requiresCharging } -> Plan.Replace
    else -> Plan.Leave
  }

  /** I lavori accodati da liberare per [relaxed]: nome unico e richiesta senza vincoli di carica. */
  class Target(val name: String, val request: () -> OneTimeWorkRequest)

  private fun nas(base: String, worker: Class<out ListenableWorker>) =
    NasWorkScheduling.waitName(base, WaitReason.NeedsPower).let { name ->
      Target(name) { NasWorkScheduling.waitRequest(worker, RuleWaits.FollowUp(delay = Duration.ZERO), name) }
    }

  fun targets(relaxed: Set<BackgroundActivity>): List<Target> = buildList {
    if (BackgroundActivity.PHOTO_BACKUP in relaxed) {
      add(Target(BackupWorkScheduling.Kind.PHOTOS.workName) { BackupWorkScheduling.request(BackupWorkScheduling.Kind.PHOTOS) })
      // «Proteggi subito le foto nuove»: usa la stessa attivita' (PHOTO_BACKUP) con la sua regola; la sua attesa per la batteria si riaccoda col vincolo della carica come le altre.
      add(Target(BackupWorkScheduling.Kind.QUICK.workName) { BackupWorkScheduling.request(BackupWorkScheduling.Kind.QUICK) })
    }
    if (BackgroundActivity.VIDEO_BACKUP in relaxed) {
      add(Target(BackupWorkScheduling.Kind.VIDEOS.workName) { BackupWorkScheduling.request(BackupWorkScheduling.Kind.VIDEOS) })
    }
    if (BackgroundActivity.AI_COMPUTE in relaxed) {
      add(Target(AiWorkScheduling.COMPUTE_NEXT_WORK) { AiWorkScheduling.resumeRequest() })
      add(Target(FacesWorkScheduling.COMPUTE_NEXT_WORK) { FacesWorkScheduling.resumeRequest() })
    }
    if (BackgroundActivity.INDEX_SYNC in relaxed) {
      add(nas(NasWorkScheduling.SCAN_WAIT_WORK, NasScanWorker::class.java))
      add(nas(NasWorkScheduling.SYNC_WAIT_WORK, IndexSyncWorker::class.java))
      add(nas(NasWorkScheduling.TRASH_PURGE_WAIT_WORK, NasTrashPurgeWorker::class.java))
      add(nas(NasWorkScheduling.MAINTENANCE_WAIT_WORK, IndexMaintenanceWorker::class.java))
      add(nas(NasWorkScheduling.PERSONAL_WAIT_WORK, PersonalSyncWorker::class.java))
    }
    if (BackgroundActivity.PREPARATION in relaxed) add(nas(NasWorkScheduling.PREP_WAIT_WORK, PreparationWorker::class.java))
    if (BackgroundActivity.HQ_PREVIEWS in relaxed) add(nas(NasWorkScheduling.HQ_WAIT_WORK, HqPreviewsWorker::class.java))
  }

  /** Chiede la ripresa dei lavori in attesa per [relaxed]. Una lettura per nome e, dove serve, una sostituzione. */
  suspend fun resume(context: Context, relaxed: Set<BackgroundActivity>) {
    val manager = WorkManager.getInstance(context.applicationContext)
    for (target in targets(relaxed)) {
      val queued = manager.getWorkInfosForUniqueWorkFlow(target.name).first().map { Queued(it.state, it.constraints.requiresCharging()) }
      if (plan(queued) == Plan.Replace) manager.enqueueUniqueWork(target.name, ExistingWorkPolicy.REPLACE, target.request())
    }
  }
}
