package dev.pampa.mycloudgallery.feature.library.library

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.feature.library.R

// Fase 5A (docs/fase5-progetto.md §2.6, D16): cosa dice il piede di ogni problema del backup. La Libreria non vede core-backup (§1.1):
// il problema le arriva come **codice stabile** (`LibraryBackupStatus.Problem.code`, il `code` di `BackupProblemCode`) e qui diventa
// una frase breve per la capsula e, sotto, cosa fare. Una riga per codice, con le stringhe `backup_footer_<codice>_label` e
// `_detail` (`strings_backup_footer.xml`): `BackupFooterProblemsTest` confronta questa tabella con il file delle stringhe, e
// `BackupMessagesTest` di feature-backup con l'elenco `BackupProblemCode.entries` del contratto.

/**
 * Il testo e il tono di un problema nel piede.
 *
 * @property label la frase breve della capsula (senza numero).
 * @property labelCount la stessa frase con il numero dei media riguardati ("3 copie non integre"), per i problemi di un media.
 */
internal class FooterProblem(
  val code: String,
  val tone: GalleryFooterTone,
  val glyph: ImageVector,
  @StringRes val label: Int,
  @StringRes val detail: Int,
  @PluralsRes val labelCount: Int? = null,
)

/** I problemi del §2.6 come li dice il piede, e il ripiego per un codice che non conosce. */
internal object BackupFooterProblems {

  /**
   * Il tono dice quanto serve l'utente: [GalleryFooterTone.Alarm] se senza di lui il backup non riparte (password, permessi, NAS
   * pieno, quota), [GalleryFooterTone.Attention] se c'e' da guardare senza colpa (il NAS non risponde, copie non integre), [Waiting]
   * se si riprende da solo (riavvio, profilo, prova). Sono i toni di `GalleryFooterStatusTest`: ogni accento, chiaro e scuro.
   */
  val all: List<FooterProblem> = listOf(
    problem("nas_unreachable", GalleryFooterTone.Attention, Icons.Rounded.CloudOff, R.string.backup_footer_nas_unreachable_label, R.string.backup_footer_nas_unreachable_detail),
    problem("nas_full", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_nas_full_label, R.string.backup_footer_nas_full_detail),
    problem("quota", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_quota_label, R.string.backup_footer_quota_detail),
    problem("auth", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_auth_label, R.string.backup_footer_auth_detail),
    problem("read_only_area", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_read_only_area_label, R.string.backup_footer_read_only_area_detail),
    problem("no_private_area", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_no_private_area_label, R.string.backup_footer_no_private_area_detail),
    problem("media_permission", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_media_permission_label, R.string.backup_footer_media_permission_detail),
    problem("media_location", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_media_location_label, R.string.backup_footer_media_location_detail),
    problem("partial_access", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_partial_access_label, R.string.backup_footer_partial_access_detail),
    problem("cap_reached", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_cap_reached_label, R.string.backup_footer_cap_reached_detail),
    problem("frozen", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_frozen_label, R.string.backup_footer_frozen_detail),
    problem("writes_stopped", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_writes_stopped_label, R.string.backup_footer_writes_stopped_detail),
    problem("test_only", GalleryFooterTone.Waiting, Icons.Rounded.Science, R.string.backup_footer_test_only_label, R.string.backup_footer_test_only_detail),
    problem(
      "lost_before_backup", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_lost_before_backup_label,
      R.string.backup_footer_lost_before_backup_detail, R.plurals.backup_footer_lost_before_backup_label_count,
    ),
    problem(
      "verify_failed", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_verify_failed_label,
      R.string.backup_footer_verify_failed_detail, R.plurals.backup_footer_verify_failed_label_count,
    ),
    problem(
      "common_copy_lost", GalleryFooterTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_footer_common_copy_lost_label,
      R.string.backup_footer_common_copy_lost_detail, R.plurals.backup_footer_common_copy_lost_label_count,
    ),
    problem(
      "unreadable", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_unreadable_label,
      R.string.backup_footer_unreadable_detail, R.plurals.backup_footer_unreadable_label_count,
    ),
    problem("phone_storage_low", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_phone_storage_low_label, R.string.backup_footer_phone_storage_low_detail),
    problem("stale", GalleryFooterTone.Attention, Icons.Rounded.Warning, R.string.backup_footer_stale_label, R.string.backup_footer_stale_detail),
    problem("waiting_unlock", GalleryFooterTone.Waiting, Icons.Rounded.Schedule, R.string.backup_footer_waiting_unlock_label, R.string.backup_footer_waiting_unlock_detail),
    problem("profile_inactive", GalleryFooterTone.Waiting, Icons.Rounded.Schedule, R.string.backup_footer_profile_inactive_label, R.string.backup_footer_profile_inactive_detail),
  )

  private val byCode: Map<String, FooterProblem> = all.associateBy { it.code }

  /** Il ripiego per un codice nuovo che questa tabella non conosce: dice che c'e' un problema e porta alla pagina Backup. */
  val generic: FooterProblem = FooterProblem(
    code = "generic",
    tone = GalleryFooterTone.Attention,
    glyph = Icons.Rounded.Warning,
    label = R.string.backup_footer_problem_generic,
    detail = R.string.backup_footer_problem_generic_detail,
  )

  /** Il testo per [code]; un codice sconosciuto non lascia il piede muto: [generic]. */
  fun of(code: String): FooterProblem = byCode[code] ?: generic

  private fun problem(
    code: String,
    tone: GalleryFooterTone,
    glyph: ImageVector,
    @StringRes label: Int,
    @StringRes detail: Int,
    @PluralsRes labelCount: Int? = null,
  ): FooterProblem = FooterProblem(code, tone, glyph, label, detail, labelCount)
}
