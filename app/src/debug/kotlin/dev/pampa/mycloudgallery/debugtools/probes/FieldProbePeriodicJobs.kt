package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import dev.pampa.mycloudgallery.work.HqPreviewsWorker
import dev.pampa.mycloudgallery.work.IndexSyncWorker
import dev.pampa.mycloudgallery.work.LanConstraints
import dev.pampa.mycloudgallery.work.NasTrashPurgeWorker
import dev.pampa.mycloudgallery.work.NasWorkScheduling
import dev.pampa.mycloudgallery.work.PreparationWorker

/**
 * I lavori periodici che la prova sul campo vuole far partire subito, uno per uno ([code] e' il nome per `adb`).
 *
 * Prima, per far partire la pulizia del cestino, la convergenza delle anteprime o le scansioni (F20, F12), le prove modificavano il database di
 * WorkManager dell'app (`last_enqueue_time` di 25 ore fa) con l'app ferma: lento e fragile (un `cat >` con `run-as` aveva troncato il file).
 */
internal enum class FieldProbePeriodicJob(val code: String, val writes: Boolean = true) {
  /** La pulizia del cestino scaduto del NAS (`NasTrashPurgeWorker`, giornaliera). */
  TRASH("trash"),

  /** Lo scaricamento delle anteprime HQ (`HqPreviewsWorker`, solo con la qualita' "Alta"): legge soltanto. */
  HQ("hq", writes = false),

  /** La preparazione delle anteprime sul NAS (`PreparationWorker`). */
  PREPARATION("prep"),

  /** La sincronizzazione dell'indice (`IndexSyncWorker`): scarica e rilascia le classi della qualita' scelta, poi album e volti. */
  SYNC("sync"),

  /** La verifica del NAS e, dopo, la sincronizzazione dell'indice e dei dati personali (`NasWorkScheduling.scanNow`). */
  SCAN("scan"),

  /** Il backup: i giri delle foto e dei video e la verifica "al sicuro" (`BackupWorkScheduling`). */
  BACKUP("backup"),
  ;

  companion object {
    /** Tutti, nell'ordine in cui si accodano. */
    val ALL: List<FieldProbePeriodicJob> = entries.toList()
  }
}

/**
 * SOLO build debug (correzioni dal campo, gruppo B, punto 9): **"Fai ora i lavori periodici"**. Accoda subito, uno per uno, i lavori che di norma
 * girano ogni 6 ore o ogni giorno, per le prove sul campo (pulizia del cestino con la ritenzione corta, convergenza delle anteprime, scansioni).
 *
 * Non scavalca niente: accoda gli **stessi lavori** del giro periodico (stessa classe, stessi vincoli di rete, stesso tag), non una copia del loro
 * codice. Quando partono passano da `StagedRun` e dalle regole delle attivita' come sempre (preset, rete, carica, fascia, traffico), dal cancello
 * del backup e dalle recinzioni delle share di prova (D33) e del telefono (D13): se le regole dicono "aspetta", aspettano. Un lavoro unico per
 * tipo: due richieste di fila non ne fanno partire due insieme (si accodano una dopo l'altra, come le "continuazioni").
 *
 * Due strade: [FieldProbePeriodicJobsReceiver] (`adb shell am broadcast`, per gli agenti) e la sezione "Lavori periodici" di "Prove guidate".
 * Solo in `app/src/debug`: la release non lo contiene (`FieldProbe` e `debugtools/probes` sono nei resti di debug cercati da `tools/check.ps1`).
 *
 * PU-3 (passo unico, 12/11/2026): i lavori che scrivono ([FieldProbePeriodicJob.writes]) si accodano solo con un account di prova
 * ([FieldProbeAccounts.refusal]): con l'account vero, dal passo, scriverebbero sulle share vere.
 */
internal object FieldProbePeriodicJobs {

  /** L'azione del broadcast: `adb shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p dev.pampa.mycloudgallery.debug --es jobs all`. */
  const val ACTION: String = "dev.pampa.mycloudgallery.debug.PERIODIC_NOW"

  /** Il parametro dei lavori: `all` o un elenco separato da virgole (`trash,hq,prep,sync,scan,backup`). Senza, tutti. */
  const val EXTRA_JOBS: String = "jobs"

  /**
   * I lavori chiesti da [text]: `all` (o niente) = tutti; altrimenti i codici separati da virgole, senza badare a maiuscole e spazi. Null se un
   * codice non esiste (meglio non fare niente che indovinare).
   */
  fun parse(text: String?): List<FieldProbePeriodicJob>? {
    val wanted = text?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
    if (wanted.isEmpty() || wanted == listOf("all")) return FieldProbePeriodicJob.ALL
    val jobs = LinkedHashSet<FieldProbePeriodicJob>()
    for (code in wanted) {
      jobs += FieldProbePeriodicJob.entries.firstOrNull { it.code == code } ?: return null
    }
    return FieldProbePeriodicJob.ALL.filter { it in jobs }
  }

  /** PU-3: null se [jobs] si possono accodare adesso (nessuno scrive, o l'account attivo e' di prova), altrimenti il motivo. */
  fun refusal(context: Context, jobs: Collection<FieldProbePeriodicJob>): String? =
    if (jobs.none { it.writes }) null else FieldProbeAccounts.refusal(context)

  /**
   * Accoda [jobs] adesso; restituisce i nomi dei lavori unici accodati (per il resoconto). Ogni lavoro e' quello del giro periodico, con i suoi
   * vincoli ([LanConstraints.anyNetwork]); il backup usa le richieste del backup (`BackupWorkScheduling.request`).
   */
  fun enqueue(context: Context, jobs: Collection<FieldProbePeriodicJob>): List<String> {
    val app = context.applicationContext
    val names = ArrayList<String>()
    fun nas(name: String, request: androidx.work.OneTimeWorkRequest) {
      NasWorkScheduling.continueLater(app, name, request)
      names += name
    }
    for (job in jobs) {
      when (job) {
        FieldProbePeriodicJob.TRASH ->
          nas(NasWorkScheduling.TRASH_PURGE_NOW_WORK, OneTimeWorkRequestBuilder<NasTrashPurgeWorker>().setConstraints(LanConstraints.anyNetwork()).addTag(NasWorkScheduling.TAG).build())
        FieldProbePeriodicJob.HQ ->
          nas(NasWorkScheduling.HQ_NOW_WORK, OneTimeWorkRequestBuilder<HqPreviewsWorker>().setConstraints(LanConstraints.anyNetwork()).addTag(NasWorkScheduling.TAG).build())
        FieldProbePeriodicJob.PREPARATION ->
          nas(NasWorkScheduling.PREP_NOW_WORK, OneTimeWorkRequestBuilder<PreparationWorker>().setConstraints(LanConstraints.anyNetwork()).addTag(NasWorkScheduling.TAG).build())
        FieldProbePeriodicJob.SYNC ->
          nas(NasWorkScheduling.SYNC_NOW_WORK, OneTimeWorkRequestBuilder<IndexSyncWorker>().setConstraints(LanConstraints.anyNetwork()).addTag(NasWorkScheduling.TAG).build())
        FieldProbePeriodicJob.SCAN -> {
          NasWorkScheduling.scanNow(app)
          names += NasWorkScheduling.SCAN_NOW_WORK
        }
        FieldProbePeriodicJob.BACKUP -> for (kind in BACKUP_KINDS) {
          BackupWorkScheduling.continueLater(app, kind)
          names += kind.workName
        }
      }
    }
    GalleryLog.i(TAG) { "lavori periodici accodati: ${jobs.joinToString(",") { it.code }}" }
    return names
  }

  /** I giri del backup di un "Fai ora": le foto, i video e la verifica "al sicuro". */
  private val BACKUP_KINDS: List<BackupWorkScheduling.Kind> =
    listOf(BackupWorkScheduling.Kind.PHOTOS, BackupWorkScheduling.Kind.VIDEOS, BackupWorkScheduling.Kind.SAFETY)

  private const val TAG = "FieldProbeJobs"
}

/**
 * SOLO debug: il broadcast `adb shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p dev.pampa.mycloudgallery.debug --es jobs all`.
 * Dichiarato nel manifest di debug **con il permesso `android.permission.DUMP`**: lo possiede la shell di `adb` e nessuna app normale, quindi un'altra
 * app non puo' far partire niente. Risponde (broadcast ordinato, `am` lo stampa) con i lavori accodati, o con un errore se un nome non esiste, o
 * (PU-3) con [FieldProbeAccounts.RESULT_NOT_TEST_ACCOUNT] e il motivo se l'account attivo non e' di prova: niente in coda.
 */
class FieldProbePeriodicJobsReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != FieldProbePeriodicJobs.ACTION) return
    val jobs = FieldProbePeriodicJobs.parse(intent.getStringExtra(FieldProbePeriodicJobs.EXTRA_JOBS))
    if (jobs == null) {
      if (isOrderedBroadcast) setResult(RESULT_BAD_JOB, "lavoro sconosciuto: usa all o ${FieldProbePeriodicJob.ALL.joinToString(",") { it.code }}", null)
      return
    }
    FieldProbePeriodicJobs.refusal(context, jobs)?.let { why ->
      if (isOrderedBroadcast) setResult(FieldProbeAccounts.RESULT_NOT_TEST_ACCOUNT, why, null)
      return
    }
    val queued = FieldProbePeriodicJobs.enqueue(context, jobs)
    if (isOrderedBroadcast) setResult(Activity.RESULT_OK, queued.joinToString(","), null)
  }

  companion object {
    /** Un nome di lavoro che non esiste. */
    const val RESULT_BAD_JOB: Int = 2
  }
}
