package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
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
  /** I3 (fase I): la famiglia della piastrella del problema (il glifo dice la cosa, il colore di che cosa si parla: il NAS, le foto, la sicurezza). */
  val family: GalleryIconFamily = GalleryIconFamily.Backup,
) {
  /** I3: il segno di stato sulla piastrella, dal tono (mai solo il colore: ogni segno ha la sua forma). */
  val mark: GalleryIconStatus get() = backupToneMark(tone)
}

/** I3: il segno di stato di un tono delle schede del backup. */
internal fun backupToneMark(tone: BackupTone): GalleryIconStatus = when (tone) {
  BackupTone.Calm -> GalleryIconStatus.Done
  BackupTone.Working -> GalleryIconStatus.Running
  BackupTone.Waiting -> GalleryIconStatus.Paused
  BackupTone.Attention -> GalleryIconStatus.Attention
  BackupTone.Alarm -> GalleryIconStatus.Problem
}

/**
 * Il messaggio di ogni problema del §2.6, nell'ordine della tabella. Chiavi = il **codice stabile** (`BackupProblemCode.code`), non
 * l'enumerazione: un codice che il contratto aggiunge dopo (P2 ne aggiunge mentre P5 scrive) si lega scrivendo una riga qui e il suo
 * testo nelle stringhe; senza la riga la pagina dice un messaggio generico (mai vuoto) e `BackupMessagesTest` fallisce, perche' legge
 * l'elenco da `BackupProblemCode.entries`. Un codice con la riga e senza ancora il suo caso nel contratto (`common_copy_lost`) e' innocuo.
 */
internal object BackupProblemTexts {

  /** Il messaggio quando il contratto ha un codice che qui non c'e' ancora: dice che c'e' un problema e porta alla Diagnostica. */
  val generic: ProblemText =
    ProblemText(BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_problem_generic_title, R.string.backup_problem_generic_detail, family = GalleryIconFamily.System)

  /** Vero se il codice ha il suo messaggio (e non il generico). */
  fun knows(code: BackupProblemCode): Boolean = code.code in byCode

  /**
   * Il messaggio di [code]. CA-1 (revisione funzionale della Fase 10): con [heldByRelease] (le scritture le ferma il manifest firmato, non l'utente) "scritture
   * ferme" ha la sua frase: la causa ("questa versione va aggiornata") e cosa fare, mai "il backup riprende quando le riattivi".
   */
  fun of(code: BackupProblemCode, heldByRelease: Boolean = false): ProblemText =
    if (heldByRelease && code == BackupProblemCode.WRITES_STOPPED) releaseReadOnly else byCode[code.code] ?: generic

  /** CA-1: "scritture ferme" perche' questa versione e' in sola lettura per il manifest firmato. */
  val releaseReadOnly: ProblemText =
    ProblemText(BackupTone.Attention, Icons.Rounded.SystemUpdate, R.string.backup_problem_release_read_only_title, R.string.backup_problem_release_read_only_detail, family = GalleryIconFamily.System)

  private val byCode: Map<String, ProblemText> = mapOf(
    "nas_unreachable" to ProblemText(BackupTone.Attention, Icons.Rounded.CloudOff, R.string.backup_problem_nas_unreachable_title, R.string.backup_problem_nas_unreachable_detail, family = GalleryIconFamily.Backup),
    "nas_full" to ProblemText(BackupTone.Alarm, Icons.Rounded.Storage, R.string.backup_problem_nas_full_title, R.string.backup_problem_nas_full_detail, family = GalleryIconFamily.Backup),
    "quota" to ProblemText(BackupTone.Alarm, Icons.Rounded.Storage, R.string.backup_problem_quota_title, R.string.backup_problem_quota_detail, family = GalleryIconFamily.Backup),
    "auth" to ProblemText(BackupTone.Alarm, Icons.Rounded.Key, R.string.backup_problem_auth_title, R.string.backup_problem_auth_detail, family = GalleryIconFamily.Security),
    "read_only_area" to ProblemText(BackupTone.Alarm, Icons.Rounded.Lock, R.string.backup_problem_read_only_area_title, R.string.backup_problem_read_only_area_detail, family = GalleryIconFamily.Security),
    "no_private_area" to ProblemText(BackupTone.Alarm, Icons.Rounded.FolderOff, R.string.backup_problem_no_private_area_title, R.string.backup_problem_no_private_area_detail, family = GalleryIconFamily.Backup),
    "media_permission" to ProblemText(BackupTone.Alarm, Icons.Rounded.PhotoLibrary, R.string.backup_problem_media_permission_title, R.string.backup_problem_media_permission_detail, family = GalleryIconFamily.Photos),
    "media_location" to ProblemText(BackupTone.Attention, Icons.Rounded.LocationOff, R.string.backup_problem_media_location_title, R.string.backup_problem_media_location_detail, family = GalleryIconFamily.Places),
    "partial_access" to ProblemText(BackupTone.Attention, Icons.Rounded.PhotoLibrary, R.string.backup_problem_partial_access_title, R.string.backup_problem_partial_access_detail, family = GalleryIconFamily.Photos),
    "cap_reached" to ProblemText(BackupTone.Attention, Icons.Rounded.SignalCellularAlt, R.string.backup_problem_cap_reached_title, R.string.backup_problem_cap_reached_detail, family = GalleryIconFamily.Network),
    "frozen" to ProblemText(BackupTone.Alarm, Icons.Rounded.AcUnit, R.string.backup_problem_frozen_title, R.string.backup_problem_frozen_detail, family = GalleryIconFamily.Security),
    "writes_stopped" to ProblemText(BackupTone.Attention, Icons.Rounded.EditOff, R.string.backup_problem_writes_stopped_title, R.string.backup_problem_writes_stopped_detail, family = GalleryIconFamily.System),
    "test_only" to ProblemText(BackupTone.Waiting, Icons.Rounded.PauseCircle, R.string.backup_problem_test_only_title, R.string.backup_problem_test_only_detail, family = GalleryIconFamily.Backup),
    "lost_before_backup" to ProblemText(
      BackupTone.Attention, Icons.Rounded.Delete, R.string.backup_problem_lost_before_backup_title, R.string.backup_problem_lost_before_backup_detail,
      titleCount = R.plurals.backup_problem_lost_before_backup_title_count, family = GalleryIconFamily.Photos,
    ),
    "verify_failed" to ProblemText(
      BackupTone.Alarm, Icons.Rounded.BrokenImage, R.string.backup_problem_verify_failed_title, R.string.backup_problem_verify_failed_detail,
      titleCount = R.plurals.backup_problem_verify_failed_title_count, family = GalleryIconFamily.Backup,
    ),
    "unreadable" to ProblemText(
      BackupTone.Attention, Icons.Rounded.BrokenImage, R.string.backup_problem_unreadable_title, R.string.backup_problem_unreadable_detail,
      titleCount = R.plurals.backup_problem_unreadable_title_count, family = GalleryIconFamily.Photos,
    ),
    "phone_storage_low" to ProblemText(BackupTone.Attention, Icons.Rounded.PhoneAndroid, R.string.backup_problem_phone_storage_low_title, R.string.backup_problem_phone_storage_low_detail, family = GalleryIconFamily.System),
    "stale" to ProblemText(BackupTone.Attention, Icons.Rounded.History, R.string.backup_problem_stale_title, R.string.backup_problem_stale_detail, family = GalleryIconFamily.Backup),
    "waiting_unlock" to ProblemText(BackupTone.Waiting, Icons.Rounded.Lock, R.string.backup_problem_waiting_unlock_title, R.string.backup_problem_waiting_unlock_detail, family = GalleryIconFamily.Security),
    "profile_inactive" to ProblemText(BackupTone.Waiting, Icons.Rounded.PersonOff, R.string.backup_problem_profile_inactive_title, R.string.backup_problem_profile_inactive_detail, family = GalleryIconFamily.People),
    // P2 (in arrivo nel contratto): un media la cui unica copia era nell'area comune e' sparito, senza copia privata ne' del telefono. Serve l'utente:
    // il cestino del NAS tiene i file 30 giorni.
    "common_copy_lost" to ProblemText(
      BackupTone.Alarm, Icons.Rounded.Delete, R.string.backup_problem_common_copy_lost_title, R.string.backup_problem_common_copy_lost_detail,
      titleCount = R.plurals.backup_problem_common_copy_lost_title_count, family = GalleryIconFamily.Danger,
    ),
    // R1 della revisione 5A: un errore che nessuna regola prevedeva. L'elemento e' parcheggiato, gli altri vanno avanti; "Riprova" lo rilegge.
    "unexpected" to ProblemText(
      BackupTone.Attention, Icons.Rounded.ErrorOutline, R.string.backup_problem_unexpected_title, R.string.backup_problem_unexpected_detail,
      titleCount = R.plurals.backup_problem_unexpected_title_count, family = GalleryIconFamily.System,
    ),
    // P14 (5B, D17, E-E6): il file sul NAS l'ha cambiato il PC mentre una versione nuova aspettava. Niente si sposta; riparte da solo.
    "source_changed" to ProblemText(
      BackupTone.Waiting, Icons.Rounded.Sync, R.string.backup_problem_source_changed_title, R.string.backup_problem_source_changed_detail,
      titleCount = R.plurals.backup_problem_source_changed_title_count, family = GalleryIconFamily.Backup,
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
internal class ActionText(@StringRes val label: Int, val isButton: Boolean = true, val icon: ImageVector = Icons.Rounded.OpenInNew)

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
    "retry" to ActionText(R.string.backup_action_retry, icon = Icons.Rounded.Refresh),
    "diagnostics" to ActionText(R.string.backup_action_diagnostics, icon = Icons.Rounded.MonitorHeart),
    "nas_space" to ActionText(R.string.backup_action_nas_space, icon = Icons.Rounded.Storage),
    "ask_admin" to ActionText(R.string.backup_action_ask_admin, isButton = false),
    "enter_password" to ActionText(R.string.backup_action_enter_password, icon = Icons.Rounded.Key),
    "areas_settings" to ActionText(R.string.backup_action_areas_settings, icon = Icons.Rounded.Tune),
    "grant_media" to ActionText(R.string.backup_action_grant_media, icon = Icons.Rounded.PhotoLibrary),
    "grant_media_location" to ActionText(R.string.backup_action_grant_media_location, icon = Icons.Rounded.LocationOn),
    "grant_all_media" to ActionText(R.string.backup_action_grant_all_media, icon = Icons.Rounded.PhotoLibrary),
    "do_now_confirm" to ActionText(R.string.backup_action_do_now_confirm, icon = Icons.Rounded.CloudUpload),
    "open_gallery_trash" to ActionText(R.string.backup_action_open_gallery_trash, icon = Icons.Rounded.Delete),
    "free_space" to ActionText(R.string.backup_action_free_space, icon = Icons.Rounded.CleaningServices),
    "battery_guide" to ActionText(R.string.backup_action_battery_guide, icon = Icons.Rounded.BatteryChargingFull),
    // "Recuperalo dal cestino del NAS" (30 giorni): l'azione di `COMMON_COPY_LOST`. Il nome dell'azione nel contratto e' di P2 (di solito
    // `open_nas_trash`): si lega qui, in una riga, quando c'e'.
    "open_nas_trash" to ActionText(R.string.backup_action_open_nas_trash, icon = Icons.Rounded.RestoreFromTrash),
  )
}
