package dev.pampa.mycloudgallery.core.rules.platform.health

import android.app.ApplicationExitInfo
import androidx.core.content.UnusedAppRestrictionsConstants

/**
 * Le traduzioni da Android al vocabolario di [BackgroundHealth], separate dalle letture cosi' si provano su JVM
 * senza un telefono (come `AndroidMappers` per le regole). Nessuna chiama Android: sono solo tabelle.
 *
 * Regola comune: un valore che non si riconosce diventa `UNKNOWN`/`OTHER`, mai una supposizione. Un avviso
 * sbagliato insegna a ignorare gli avvisi (KDoc di [BackgroundHealth]).
 */
internal object HealthMappers {

  /**
   * Il bucket di standby da `UsageStatsManager.getAppStandbyBucket()`.
   *
   * **Per intervalli, non per uguaglianza**: la documentazione di Android dice che il sistema puo' restituire
   * valori intermedi fra le costanti, e che le app devono confrontarli per fasce. `STANDBY_BUCKET_EXEMPTED` (5,
   * Android 11) entra in `ACTIVE`: per i lavori e' libero quanto l'attivo. `STANDBY_BUCKET_NEVER` (50, mai
   * avviata) e lo 0 (sconosciuto) non sono un bucket su cui avvisare: restano `UNKNOWN`.
   */
  fun standbyBucket(raw: Int): StandbyBucket = when (raw) {
    in 1..STANDBY_ACTIVE -> StandbyBucket.ACTIVE
    in (STANDBY_ACTIVE + 1)..STANDBY_WORKING_SET -> StandbyBucket.WORKING_SET
    in (STANDBY_WORKING_SET + 1)..STANDBY_FREQUENT -> StandbyBucket.FREQUENT
    in (STANDBY_FREQUENT + 1)..STANDBY_RARE -> StandbyBucket.RARE
    in (STANDBY_RARE + 1)..STANDBY_RESTRICTED -> StandbyBucket.RESTRICTED
    else -> StandbyBucket.UNKNOWN
  }

  /**
   * Lo stato di "Pausa se inutilizzata" da `PackageManagerCompat.getUnusedAppRestrictionsStatus`. I tre
   * `API_30*`/`API_31` dicono tutti la stessa cosa (la funzione e' accesa per questa app); `DISABLED` e'
   * l'unico stato buono.
   */
  fun unusedAppRestrictions(raw: Int): UnusedAppRestrictions = when (raw) {
    UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> UnusedAppRestrictions.NOT_AVAILABLE
    UnusedAppRestrictionsConstants.DISABLED -> UnusedAppRestrictions.DISABLED
    UnusedAppRestrictionsConstants.API_30_BACKPORT,
    UnusedAppRestrictionsConstants.API_30,
    UnusedAppRestrictionsConstants.API_31,
    -> UnusedAppRestrictions.ENABLED
    else -> UnusedAppRestrictions.UNKNOWN // ERROR e qualunque valore che una versione futura aggiunga
  }

  /** Il motivo di un'uscita del processo (`ApplicationExitInfo.reason`) ridotto a quelli che la Diagnostica spiega. */
  fun exitKind(raw: Int): ExitReasonKind = when (raw) {
    ApplicationExitInfo.REASON_ANR -> ExitReasonKind.ANR
    ApplicationExitInfo.REASON_CRASH -> ExitReasonKind.CRASH
    ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReasonKind.CRASH_NATIVE
    ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReasonKind.LOW_MEMORY
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitReasonKind.EXCESSIVE_RESOURCE_USAGE
    ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReasonKind.USER_REQUESTED
    ApplicationExitInfo.REASON_USER_STOPPED -> ExitReasonKind.USER_STOPPED
    ApplicationExitInfo.REASON_FREEZER -> ExitReasonKind.FREEZER
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ExitReasonKind.PERMISSION_CHANGE
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ExitReasonKind.DEPENDENCY_DIED
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitReasonKind.INITIALIZATION_FAILURE
    ApplicationExitInfo.REASON_SIGNALED -> ExitReasonKind.SIGNALED
    ApplicationExitInfo.REASON_EXIT_SELF -> ExitReasonKind.EXIT_SELF
    ApplicationExitInfo.REASON_UNKNOWN -> ExitReasonKind.UNKNOWN
    // REASON_OTHER, PACKAGE_STATE_CHANGE, PACKAGE_UPDATED (Android 13: l'app e' stata aggiornata o disattivata)
    // e quelli che arriveranno: tutti "altro".
    else -> ExitReasonKind.OTHER
  }

  // Le costanti di UsageStatsManager.STANDBY_BUCKET_*: sono pubbliche ma EXEMPTED e NEVER sono piu' recenti
  // del minSdk, e qui servono solo i confini delle fasce.
  private const val STANDBY_ACTIVE = 10
  private const val STANDBY_WORKING_SET = 20
  private const val STANDBY_FREQUENT = 30
  private const val STANDBY_RARE = 40
  private const val STANDBY_RESTRICTED = 45
}
