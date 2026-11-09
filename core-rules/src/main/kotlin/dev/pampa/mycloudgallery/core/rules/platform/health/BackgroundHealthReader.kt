package dev.pampa.mycloudgallery.core.rules.platform.health

import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashRecord

/**
 * La salute del lavoro in background su questo telefono, come la vede Android (Fase 2: contratto P0,
 * implementazione P8; docs/fase2-progetto.md §2.6 "Avvisi Android" e passo 10 dell'onboarding).
 *
 * Serve a due cose: dire all'utente, con parole sue, perche' il backup notturno potrebbe non partire
 * (batteria non "Illimitata", app in sospensione, "Pausa se inutilizzata", profilo del tablet fermo) e
 * verificare, dopo che l'utente ha cambiato un'impostazione, che il cambio sia davvero avvenuto.
 *
 * Tutti i campi si leggono senza permessi speciali. Un valore che il sistema non da' (API troppo vecchia,
 * errore) e' `UNKNOWN`/`null`, mai indovinato: un avviso sbagliato insegna a ignorare gli avvisi.
 *
 * @property ignoringBatteryOpt l'app e' esclusa dall'ottimizzazione della batteria ("Illimitata" su One UI).
 * @property standbyBucket il bucket di standby (`UsageStatsManager.getAppStandbyBucket`).
 * @property backgroundRestricted l'utente ha limitato l'app in background (`isBackgroundRestricted`).
 * @property unusedAppRestrictions "Pausa se inutilizzata" / ibernazione (androidx.core).
 * @property permissions lo stato dei permessi che contano per il lavoro dell'app.
 * @property userUnlocked il telefono e' stato sbloccato almeno una volta dall'accensione: prima (BFU) le
 *   credenziali cifrate non si leggono e il lavoro notturno aspetta.
 * @property bootCount `Settings.Global.BOOT_COUNT`: un riavvio si riconosce dal numero che cresce.
 * @property lastExitReasons le ultime uscite del processo (`ApplicationExitInfo`), dalla piu' recente.
 * @property isSystemUser l'app gira nel profilo principale (falso in un profilo secondario del tablet, E-P19).
 * @property recentCrashes i crash e gli ANR recenti con **dove** si sono rotti (C472, OA-2 / OB-7): il registro locale dei crash piu' le tracce degli ANR che Android
 *   conserva, dal piu' recente. Solo classi e cornici del nostro codice, mai un messaggio. Vuota se non c'e' niente o se non si e' potuto leggere.
 */
data class BackgroundHealth(
  val ignoringBatteryOpt: Boolean?,
  val standbyBucket: StandbyBucket,
  val backgroundRestricted: Boolean?,
  val unusedAppRestrictions: UnusedAppRestrictions,
  val permissions: Map<HealthPermission, PermissionGrant>,
  val userUnlocked: Boolean,
  val bootCount: Int?,
  val lastExitReasons: List<ExitRecord>,
  val isSystemUser: Boolean,
  val recentCrashes: List<CrashRecord> = emptyList(),
) {
  companion object {
    /** Niente di letto: il valore di partenza e quello del null-object. */
    val UNKNOWN = BackgroundHealth(
      ignoringBatteryOpt = null,
      standbyBucket = StandbyBucket.UNKNOWN,
      backgroundRestricted = null,
      unusedAppRestrictions = UnusedAppRestrictions.UNKNOWN,
      permissions = emptyMap(),
      userUnlocked = true,
      bootCount = null,
      lastExitReasons = emptyList(),
      isSystemUser = true,
    )
  }
}

/** I bucket di standby di Android, dal piu' libero al piu' limitato. */
enum class StandbyBucket { ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED, UNKNOWN }

/** "Pausa se inutilizzata" (`PackageManagerCompat.getUnusedAppRestrictionsStatus`). */
enum class UnusedAppRestrictions {
  /** Non letto, o errore. */
  UNKNOWN,

  /** La funzione non esiste su questo telefono. */
  NOT_AVAILABLE,

  /** Spenta per questa app: bene. */
  DISABLED,

  /** Accesa: dopo mesi senza aperture Android toglie i permessi o iberna l'app. */
  ENABLED,
}

/** I permessi che contano per il lavoro dell'app (docs/sicurezza.md 4-bis). */
enum class HealthPermission {
  NOTIFICATIONS,
  MEDIA_IMAGES,
  MEDIA_VIDEO,

  /** "Foto selezionate" (`READ_MEDIA_VISUAL_USER_SELECTED`): modalita' limitata, E-P1. */
  MEDIA_PARTIAL,
  MEDIA_LOCATION,
  CAMERA,
  WIREGUARD_CONTROL,
}

enum class PermissionGrant { GRANTED, DENIED, NOT_APPLICABLE }

/** Il motivo di un'uscita del processo, ridotto a quello che la Diagnostica sa spiegare. */
enum class ExitReasonKind {
  ANR,
  CRASH,
  CRASH_NATIVE,
  LOW_MEMORY,
  EXCESSIVE_RESOURCE_USAGE,
  USER_REQUESTED,
  USER_STOPPED,
  FREEZER,
  PERMISSION_CHANGE,
  DEPENDENCY_DIED,
  INITIALIZATION_FAILURE,
  SIGNALED,
  EXIT_SELF,
  OTHER,
  UNKNOWN,
}

/** Un'uscita del processo: motivo e ora del dispositivo (ms dall'epoca). Niente descrizione testuale. */
data class ExitRecord(val reason: ExitReasonKind, val atMillis: Long)

/**
 * Chi legge [BackgroundHealth]. L'implementazione Android e' di P8 (Robolectric); la lettura e' veloce e
 * locale, ma tocca diversi servizi di sistema: si chiama da una coroutine, non dal disegno.
 */
interface BackgroundHealthReader {
  suspend fun read(): BackgroundHealth
}

/** Il null-object: non legge niente e risponde [BackgroundHealth.UNKNOWN]. */
object UnknownBackgroundHealthReader : BackgroundHealthReader {
  override suspend fun read(): BackgroundHealth = BackgroundHealth.UNKNOWN
}
