package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.feature.backup.R

// Fase 5A (docs/fase5-progetto.md §2.6, "Ogni problema ha un messaggio e un'azione"): cosa dice la pagina Backup di ogni problema e di ogni
// azione. Due `when` **esaustivi** sugli enum del contratto (`BackupProblemCode`, `BackupAction`): un codice o un'azione nuovi non
// compilano finche' non hanno il loro testo. `BackupMessagesTest` legge l'elenco dal contratto (`BackupProblemCode.entries`) e controlla
// che ognuno abbia messaggio e azione.

/** Quanto una scheda chiede attenzione: sono i toni del piede della Libreria, nello stesso ordine (G2, `GalleryFooterTone`). */
internal enum class BackupTone {
  /** Tutto a posto. */
  Calm,

  /** Un lavoro in corso. */
  Working,

  /** Fermo per una ragione normale (a riposo, in prova). */
  Waiting,

  /** Da guardare, senza colpa (il NAS non risponde). */
  Attention,

  /** Serve l'utente, o qualcosa si e' fermato. */
  Alarm,
}

/** I colori di un tono: il riempimento, il testo e il glifo. Le stesse coppie dell'indicatore di stato globale e del piede. */
@Immutable
internal data class BackupToneStyle(val container: Color, val text: Color, val glyph: Color)

/** Il tono risolto da uno schema (pura: il contrasto di ogni coppia si verifica senza comporre, `BackupContrastTest`). */
internal fun backupToneStyle(tone: BackupTone, s: ColorScheme): BackupToneStyle = when (tone) {
  BackupTone.Calm -> BackupToneStyle(s.primaryContainer, s.onPrimaryContainer, s.onPrimaryContainer)
  BackupTone.Working -> BackupToneStyle(s.secondaryContainer, s.onSecondaryContainer, s.onSecondaryContainer)
  BackupTone.Waiting -> BackupToneStyle(s.surfaceContainerHighest, s.onSurface, s.onSurfaceVariant)
  BackupTone.Attention -> BackupToneStyle(s.tertiaryContainer, s.onTertiaryContainer, s.onTertiaryContainer)
  BackupTone.Alarm -> BackupToneStyle(s.errorContainer, s.onErrorContainer, s.error)
}

/**
 * Il testo di un problema.
 *
 * @property title il messaggio del §2.6.
 * @property titleCount lo stesso messaggio con il numero dei media riguardati ("3 copie non integre"), per i problemi di un media.
 * @property detail una frase che spiega, sotto il messaggio; null se il messaggio dice gia' tutto.
 */
@Immutable
internal class ProblemText(
  val tone: BackupTone,
  val glyph: ImageVector,
  @StringRes val title: Int,
  @StringRes val detail: Int?,
  @PluralsRes val titleCount: Int? = null,
)

/**
 * Il messaggio di ogni problema del §2.6, nell'ordine della tabella. Chiavi = il **codice stabile** (`BackupProblemCode.code`), non
 * l'enumerazione: un codice che il contratto aggiunge dopo (P2 ne aggiunge mentre P5 scrive) si lega scrivendo una riga qui e il suo
 * testo nelle stringhe; senza la riga la pagina dice un messaggio generico (mai vuoto) e `BackupMessagesTest` fallisce, perche' legge
 * l'elenco da `BackupProblemCode.entries`. Un codice con la riga e senza ancora il suo caso nel contratto (`common_copy_lost`) e' innocuo.
 */
internal object BackupProblemTexts {

  /** Il messaggio quando il contratto ha un codice che qui non c'e' ancora: dice che c'e' un problema e porta alla Diagnostica. */
  val generic: ProblemText =
    ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_generic_title, R.string.backup_problem_generic_detail)

  /** Vero se il codice ha il suo messaggio (e non il generico). */
  fun knows(code: BackupProblemCode): Boolean = code.code in byCode

  fun of(code: BackupProblemCode): ProblemText = byCode[code.code] ?: generic

  private val byCode: Map<String, ProblemText> = mapOf(
    "nas_unreachable" to ProblemText(BackupTone.Attention, Icons.Rounded.CloudOff, R.string.backup_problem_nas_unreachable_title, R.string.backup_problem_nas_unreachable_detail),
    "nas_full" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_nas_full_title, R.string.backup_problem_nas_full_detail),
    "quota" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_quota_title, R.string.backup_problem_quota_detail),
    "auth" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_auth_title, R.string.backup_problem_auth_detail),
    "read_only_area" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_read_only_area_title, R.string.backup_problem_read_only_area_detail),
    "no_private_area" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_no_private_area_title, R.string.backup_problem_no_private_area_detail),
    "media_permission" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_media_permission_title, R.string.backup_problem_media_permission_detail),
    "media_location" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_media_location_title, R.string.backup_problem_media_location_detail),
    "partial_access" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_partial_access_title, R.string.backup_problem_partial_access_detail),
    "cap_reached" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_cap_reached_title, R.string.backup_problem_cap_reached_detail),
    "frozen" to ProblemText(BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_frozen_title, R.string.backup_problem_frozen_detail),
    "writes_stopped" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_writes_stopped_title, R.string.backup_problem_writes_stopped_detail),
    "test_only" to ProblemText(BackupTone.Waiting, Icons.Rounded.Science, R.string.backup_problem_test_only_title, R.string.backup_problem_test_only_detail),
    "lost_before_backup" to ProblemText(
      BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_lost_before_backup_title, R.string.backup_problem_lost_before_backup_detail,
      titleCount = R.plurals.backup_problem_lost_before_backup_title_count,
    ),
    "verify_failed" to ProblemText(
      BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_verify_failed_title, R.string.backup_problem_verify_failed_detail,
      titleCount = R.plurals.backup_problem_verify_failed_title_count,
    ),
    "unreadable" to ProblemText(
      BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_unreadable_title, R.string.backup_problem_unreadable_detail,
      titleCount = R.plurals.backup_problem_unreadable_title_count,
    ),
    "phone_storage_low" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_phone_storage_low_title, R.string.backup_problem_phone_storage_low_detail),
    "stale" to ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_stale_title, R.string.backup_problem_stale_detail),
    "waiting_unlock" to ProblemText(BackupTone.Waiting, Icons.Rounded.Schedule, R.string.backup_problem_waiting_unlock_title, R.string.backup_problem_waiting_unlock_detail),
    "profile_inactive" to ProblemText(BackupTone.Waiting, Icons.Rounded.Schedule, R.string.backup_problem_profile_inactive_title, R.string.backup_problem_profile_inactive_detail),
    // P2 (in arrivo nel contratto): un media la cui unica copia era nell'area comune e' sparito, senza copia privata ne' del telefono. Serve l'utente:
    // il cestino del NAS tiene i file 30 giorni.
    "common_copy_lost" to ProblemText(
      BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_problem_common_copy_lost_title, R.string.backup_problem_common_copy_lost_detail,
      titleCount = R.plurals.backup_problem_common_copy_lost_title_count,
    ),
    // R1 della revisione 5A: un errore che nessuna regola prevedeva. L'elemento e' parcheggiato, gli altri vanno avanti; "Riprova" lo rilegge.
    "unexpected" to ProblemText(
      BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_unexpected_title, R.string.backup_problem_unexpected_detail,
      titleCount = R.plurals.backup_problem_unexpected_title_count,
    ),
  )
}

/**
 * Come si mostra un'azione di un problema (la colonna "Azione" del §2.6).
 *
 * @property isButton un tasto che chiama `BackupActions.perform`; falso per [BackupAction.ASK_ADMIN], che e' solo una frase ("Chiedi
 *   all'admin del NAS"): non c'e' niente da toccare.
 */
@Immutable
internal class ActionText(@StringRes val label: Int, val isButton: Boolean = true)

/**
 * Il testo di ogni azione del contratto, per **codice stabile**. Un'azione nuova (`COMMON_COPY_LOST` ne porta una: aprire il cestino del
 * NAS) si lega con una riga qui; senza, il tasto dice "Apri" e `BackupMessagesTest` fallisce (legge `BackupAction.entries`).
 */
internal object BackupActionTexts {

  /** Il testo quando il contratto ha un'azione che qui non c'e' ancora. */
  val generic: ActionText = ActionText(R.string.backup_action_generic)

  /** Vero se l'azione ha il suo testo (e non il generico). */
  fun knows(action: BackupAction): Boolean = action.code in byCode

  fun of(action: BackupAction): ActionText = byCode[action.code] ?: generic

  private val byCode: Map<String, ActionText> = mapOf(
    "retry" to ActionText(R.string.backup_action_retry),
    "diagnostics" to ActionText(R.string.backup_action_diagnostics),
    "nas_space" to ActionText(R.string.backup_action_nas_space),
    "ask_admin" to ActionText(R.string.backup_action_ask_admin, isButton = false),
    "enter_password" to ActionText(R.string.backup_action_enter_password),
    "areas_settings" to ActionText(R.string.backup_action_areas_settings),
    "grant_media" to ActionText(R.string.backup_action_grant_media),
    "grant_media_location" to ActionText(R.string.backup_action_grant_media_location),
    "grant_all_media" to ActionText(R.string.backup_action_grant_all_media),
    "do_now_confirm" to ActionText(R.string.backup_action_do_now_confirm),
    "open_gallery_trash" to ActionText(R.string.backup_action_open_gallery_trash),
    "free_space" to ActionText(R.string.backup_action_free_space),
    "battery_guide" to ActionText(R.string.backup_action_battery_guide),
    // "Recuperalo dal cestino del NAS" (30 giorni): l'azione di `COMMON_COPY_LOST`. Il nome dell'azione nel contratto e' di P2 (di solito
    // `open_nas_trash`): si lega qui, in una riga, quando c'e'.
    "open_nas_trash" to ActionText(R.string.backup_action_open_nas_trash),
  )
}
