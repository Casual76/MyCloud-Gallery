package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.ActivityReason
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagStopCause

/**
 * «Ultimi esiti ed errori» (docs/fase2-progetto.md §2.6): gli ultimi eventi dell'anello [DiagnosticsLog], dal piu'
 * recente. Ogni evento e' solo un codice, una gravita' e dei valori tipizzati (`DiagEvent` non ha testo libero),
 * quindi si puo' mostrare ed esportare cosi' com'e'.
 *
 * **L'ora si esporta come eta'** (`ageMs`, quanto tempo fa): sapere «tre ore fa» basta a leggere cosa e' successo,
 * e un'ora esatta del telefono e' un dato in piu' che non serve a nessuno.
 *
 * Il titolo viene dal [DiagCatalog] (un evento non porta testo); un codice che il catalogo non conosce si mostra
 * come «Evento» con il suo identificativo. La chiave `ageMs` e' riservata: un valore dell'evento con quel nome
 * viene sovrascritto, non il contrario.
 *
 * Gli eventi dei lavori (verifica funzionale, RE-4) portano il lavoro e l'esito come costanti di enum: per lo **schermo** diventano una
 * riga leggibile («Backup delle foto: in attesa · Nessuna connessione»). Quel testo sta solo nel dettaglio, che l'esportazione non
 * contiene mai; [text] legge le risorse (nei test non serve: il dettaglio resta vuoto).
 */
class RecentEventsContributor(
  private val log: DiagnosticsLog,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val limit: Int = DEFAULT_LIMIT,
  private val text: (Int) -> String = { "" },
) : DiagnosticsContributor {

  init {
    require(limit > 0) { "limite non valido" }
  }

  override val section: DiagSection = DiagSection.RECENT_EVENTS

  override suspend fun collect(): List<DiagItem> {
    val now = nowMillis()
    return log.snapshot().asReversed().take(limit).map { event -> itemFor(event, now) }
  }

  private fun itemFor(event: DiagEvent, now: Long): DiagItem {
    val titleRes = DiagCatalog.titleOf(event.code).takeIf { it != 0 } ?: DiagCatalog.titleOf(DiagCodes.EVT_GENERIC)
    return DiagItem(
      code = event.code,
      section = DiagSection.RECENT_EVENTS,
      severity = event.severity,
      titleRes = titleRes,
      detail = detailOf(event),
      export = event.values + (AGE_KEY to ExportValue.Dur((now - event.at).coerceAtLeast(0L))),
    )
  }

  /** La riga che dice quale lavoro e com'e' andato (solo schermo). Null per gli eventi senza lavoro. */
  private fun detailOf(event: DiagEvent): DiagText? {
    val job = event.enumOf<DiagJob>("job") ?: return null
    val jobText = text(jobRes(job))
    return when (event.code) {
      DiagCodes.EVT_JOB_RESULT, DiagCodes.EVT_JOB_FAILED -> {
        val result = event.enumOf<DiagJobResult>("result") ?: return null
        val reason = event.enumOf<ActivityReason>("reason")
        if (reason == null) {
          DiagText(R.string.diagnostics_evt_job_detail, listOf(jobText, text(resultRes(result))))
        } else {
          DiagText(R.string.diagnostics_evt_job_detail_reason, listOf(jobText, text(resultRes(result)), text(DiagCatalog.titleOf(reason.code))))
        }
      }
      DiagCodes.ACT_JOB_STOPPED -> {
        val cause = event.enumOf<DiagStopCause>("cause") ?: return null
        DiagText(R.string.diagnostics_evt_stop_detail, listOf(jobText, text(causeRes(cause))))
      }
      else -> null
    }
  }

  private inline fun <reified E : Enum<E>> DiagEvent.enumOf(key: String): E? {
    val value = values[key] as? ExportValue.Enum ?: return null
    return enumValues<E>().firstOrNull { it.name == value.constant && it.javaClass.simpleName == value.type }
  }

  companion object {
    const val DEFAULT_LIMIT = 50

    /** La chiave con cui l'eta' dell'evento entra nelle voci (e quindi nell'esportazione). */
    const val AGE_KEY = "ageMs"

    @StringRes
    internal fun jobRes(job: DiagJob): Int = when (job) {
      DiagJob.NAS_SCAN -> R.string.diagnostics_job_nas_scan
      DiagJob.INDEX_SYNC -> R.string.diagnostics_job_index_sync
      DiagJob.PREPARATION -> R.string.diagnostics_job_preparation
      DiagJob.HQ_PREVIEWS -> R.string.diagnostics_job_hq_previews
      DiagJob.TRASH_PURGE -> R.string.diagnostics_job_trash_purge
      DiagJob.INDEX_MAINTENANCE -> R.string.diagnostics_job_index_maintenance
      DiagJob.PERSONAL_SYNC -> R.string.diagnostics_job_personal_sync
      DiagJob.PHOTO_BACKUP -> R.string.diagnostics_job_photo_backup
      DiagJob.VIDEO_BACKUP -> R.string.diagnostics_job_video_backup
      DiagJob.QUICK_PHOTOS -> R.string.diagnostics_job_quick_photos
      DiagJob.BACKUP_SAFETY -> R.string.diagnostics_job_backup_safety
      DiagJob.BACKUP_USER -> R.string.diagnostics_job_backup_user
      DiagJob.TRUST_SYNC -> R.string.diagnostics_job_trust_sync
      DiagJob.BACKUP_INCOMING -> R.string.diagnostics_job_backup_incoming
      DiagJob.MODEL_FETCH -> R.string.diagnostics_job_model_fetch
      DiagJob.AI_COMPUTE -> R.string.diagnostics_job_ai_compute
      DiagJob.ALBUM_SHARE -> R.string.diagnostics_job_album_share
      DiagJob.LIBRARY_MAINTENANCE -> R.string.diagnostics_job_library_maintenance
      DiagJob.FACES_COMPUTE -> R.string.diagnostics_job_faces_compute
      DiagJob.FACES_ERASE -> R.string.diagnostics_job_faces_erase
      DiagJob.FACES_NIGHTLY -> R.string.diagnostics_job_faces_nightly
      DiagJob.MEMORIES_GENERATE -> R.string.diagnostics_job_memories_generate
      DiagJob.MEMORIES_NOTIFY -> R.string.diagnostics_job_memories_notify
      DiagJob.MAP_REGION -> R.string.diagnostics_job_map_region
    }

    @StringRes
    internal fun resultRes(result: DiagJobResult): Int = when (result) {
      DiagJobResult.FINISHED -> R.string.diagnostics_job_result_finished
      DiagJobResult.YIELDED -> R.string.diagnostics_job_result_yielded
      DiagJobResult.STOPPED -> R.string.diagnostics_job_result_stopped
      DiagJobResult.WAITING -> R.string.diagnostics_job_result_waiting
      DiagJobResult.NEEDS_CONFIRMATION -> R.string.diagnostics_job_result_needs_confirmation
      DiagJobResult.FAILED -> R.string.diagnostics_job_result_failed
    }

    @StringRes
    internal fun causeRes(cause: DiagStopCause): Int = when (cause) {
      DiagStopCause.QUOTA -> R.string.diagnostics_stop_quota
      DiagStopCause.RESTRICTED -> R.string.diagnostics_stop_restricted
      DiagStopCause.TIMEOUT -> R.string.diagnostics_stop_timeout
      DiagStopCause.CONSTRAINT -> R.string.diagnostics_stop_constraint
      DiagStopCause.USER -> R.string.diagnostics_stop_user
      DiagStopCause.SYSTEM -> R.string.diagnostics_stop_system
    }
  }
}
