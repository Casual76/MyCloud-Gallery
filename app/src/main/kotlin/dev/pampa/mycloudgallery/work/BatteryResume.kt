package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import kotlinx.coroutines.flow.first

/**
 * **La ripresa dopo una scelta che toglie il blocco della batteria** (pacchetto B, BS-2, critico Sol). Un lavoro fermo per `BatteryLow` si e' riaccodato col vincolo della carica
 * ([RuleWaits.followUpOf]): se l'utente abbassa il livello (o spegne un interruttore) la pagina passa a «in coda», ma quel lavoro aspetta ancora il cavo. Qui la scelta e' collegata allo
 * scheduler: quando un cambio **toglie il blocco** a un'attivita' ([relaxed]) il lavoro gia' accodato in attesa si **sostituisce** con una corsa senza il vecchio vincolo.
 *
 * Tre regole, tutte provate sulla funzione pura:
 * - **Mai una corsa attiva interrotta**: se il lavoro di quel nome sta girando ([WorkInfo.State.RUNNING]) non si tocca ([plan] = [Plan.Leave]): sta gia' rivalutando le regole a ogni lotto e vede da se' il cambio.
 * - **Sostituzione, non `KEEP`**: il lavoro vecchio (con la carica) e' quello che blocca; con `KEEP` WorkManager terrebbe lui e butterebbe la richiesta. `REPLACE` su un nome senza corse attive lo toglie e mette la nuova.
 * - **Se non aspetta niente non si crea niente**: senza un lavoro in attesa ([Plan.Leave]) la ripresa non inventa corse (il giro periodico e gli eventi di sempre restano gli unici trigger).
 *
 * Gli eventi del cursore si accorpano a monte (`AppBatteryGuard`: una sola richiesta dopo la calma). Se dopo la ripresa le regole dicono ancora «aspetta» per la batteria (il livello e' sceso ma non abbastanza),
 * il lavoro rifa' la sua attesa come sempre.
 */
object BatteryResume {

  /** Che cosa fare del lavoro accodato di un nome. */
  enum class Plan { Replace, Leave }

  /**
   * Quali attivita' il cambio da [before] a [after] **libera**: quelle a cui la soglia valeva e ora non vale piu' (interruttore o regola di gruppo spenti), o vale ma a un livello piu' basso. Alzare il livello o accendere
   * un interruttore non libera niente (ferma di piu'): niente da riprendere.
   */
  fun relaxed(before: RuleSettings, after: RuleSettings): Set<BackgroundActivity> {
    val was = before.toRuleSet()
    val now = after.toRuleSet()
    return BackgroundActivity.entries.filterTo(LinkedHashSet()) { activity ->
      was.batteryThresholdApplies(activity) &&
        (!now.batteryThresholdApplies(activity) || after.batteryThresholdPercent < before.batteryThresholdPercent)
    }
  }

  /** Dallo stato dei lavori di un nome: se qualcosa sta girando non si tocca; se c'e' un lavoro in attesa (anche vincolato) si sostituisce; altrimenti niente. */
  fun plan(states: List<WorkInfo.State>): Plan = when {
    states.any { it == WorkInfo.State.RUNNING } -> Plan.Leave
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> Plan.Replace
    else -> Plan.Leave
  }

  /** I lavori accodati da liberare per [relaxed]: nome unico e richiesta senza vincoli di carica. */
  class Target(val name: String, val request: () -> OneTimeWorkRequest)

  fun targets(relaxed: Set<BackgroundActivity>): List<Target> = buildList {
    if (BackgroundActivity.PHOTO_BACKUP in relaxed) {
      add(Target(BackupWorkScheduling.Kind.PHOTOS.workName) { BackupWorkScheduling.request(BackupWorkScheduling.Kind.PHOTOS) })
    }
    if (BackgroundActivity.VIDEO_BACKUP in relaxed) {
      add(Target(BackupWorkScheduling.Kind.VIDEOS.workName) { BackupWorkScheduling.request(BackupWorkScheduling.Kind.VIDEOS) })
    }
    if (BackgroundActivity.AI_COMPUTE in relaxed) {
      add(Target(AiWorkScheduling.COMPUTE_NEXT_WORK) { AiWorkScheduling.resumeRequest() })
      add(Target(FacesWorkScheduling.COMPUTE_NEXT_WORK) { FacesWorkScheduling.resumeRequest() })
    }
  }

  /** Chiede la ripresa dei lavori in attesa per [relaxed]. Una lettura per nome e, dove serve, una sostituzione. */
  suspend fun resume(context: Context, relaxed: Set<BackgroundActivity>) {
    val manager = WorkManager.getInstance(context.applicationContext)
    for (target in targets(relaxed)) {
      val states = manager.getWorkInfosForUniqueWorkFlow(target.name).first().map { it.state }
      if (plan(states) == Plan.Replace) manager.enqueueUniqueWork(target.name, ExistingWorkPolicy.REPLACE, target.request())
    }
  }
}
