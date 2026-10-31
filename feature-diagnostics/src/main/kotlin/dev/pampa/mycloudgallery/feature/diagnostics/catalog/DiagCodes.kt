package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagCodes
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagCodes

/**
 * Gli identificativi canonici della Diagnostica (P8): il catalogo di tutti i [DiagCode] che l'app puo' mostrare,
 * ciascuno con gli edge case di Fase 2 che copre (docs/fase2-progetto.md §2.6: «un test di catalogo verifica che
 * ogni edge case di Fase 2 che produce un avviso abbia il suo DiagCode»).
 *
 * **Come si usa.** `core-network`, `core-nas` e `core-family` non vedono questo modulo (il grafo va dalle feature
 * ai core), quindi i loro contributori scrivono gli stessi identificativi nel proprio sorgente. Questo e' il
 * riferimento: [DiagCatalog] li raccoglie con titolo, gravita' tipica e «cosa fare», e `DiagCatalogTest` verifica
 * che ogni edge case di Fase 2 che produce un avviso sia coperto. Un contributore che usa un codice fuori da qui
 * viene mostrato lo stesso (con il proprio titolo e senza la guida): il collegamento in `:app` (P9) controlla
 * con `DiagCatalog.contains` che non ce ne siano.
 *
 * **Codici = sorgente, mai dati.** Un identificativo finisce nell'esportazione cosi' com'e': non si costruisce
 * mai da un nome, un indirizzo o un percorso.
 *
 * (Generato una volta dalla tabella del catalogo; da qui si modifica a mano, insieme a [DiagCatalog].)
 */
object DiagCodes {

  // --- Rete e NAS ----------------------------------------------------------
  @JvmField val NET_PLACE = DiagCode("NET_PLACE")
  @JvmField val NET_VPN_ON_AT_HOME = DiagCode("NET_VPN_ON_AT_HOME")
  @JvmField val NET_ROAMING = DiagCode("NET_ROAMING")
  @JvmField val NET_VPN_OFF_AWAY = DiagCode("NET_VPN_OFF_AWAY", setOf("E-R3"))
  @JvmField val NET_UNTRUSTED = DiagCode("NET_UNTRUSTED", setOf("E-S10"))
  @JvmField val NET_FOREIGN_VPN = DiagCode("NET_FOREIGN_VPN", setOf("E-S16"))
  @JvmField val NET_OFFLINE = DiagCode("NET_OFFLINE")
  @JvmField val NET_CAPTIVE_PORTAL = DiagCode("NET_CAPTIVE_PORTAL", setOf("E-R13"))
  @JvmField val NET_METERED = DiagCode("NET_METERED", setOf("E-R13"))
  @JvmField val NET_POOR = DiagCode("NET_POOR", setOf("E-R4", "E-R14"))
  @JvmField val NET_IPV4_ONLY_VPN = DiagCode("NET_IPV4_ONLY_VPN", setOf("E-R11", "E-R17", "L12"))
  @JvmField val NET_HOME_CHANGED = DiagCode("NET_HOME_CHANGED", setOf("E-R16"))
  @JvmField val NET_NOT_CONFIGURED = DiagCode("NET_NOT_CONFIGURED", setOf("E-O1"))
  @JvmField val NET_LOCAL_NETWORK_DENIED = DiagCode("NET_LOCAL_NETWORK_DENIED", setOf("T-19"))
  @JvmField val VPN_WIREGUARD_MISSING = DiagCode("VPN_WIREGUARD_MISSING", setOf("E-R3", "E-P20"))
  @JvmField val VPN_PERMISSION_MISSING = DiagCode("VPN_PERMISSION_MISSING", setOf("E-R3", "E-P20"))
  @JvmField val VPN_NOT_CONFIGURED = DiagCode("VPN_NOT_CONFIGURED", setOf("E-P20"))
  @JvmField val VPN_NOT_CONTROLLABLE = DiagCode("VPN_NOT_CONTROLLABLE", setOf("E-R3"))
  @JvmField val VPN_LOCKDOWN_SUSPECTED = DiagCode("VPN_LOCKDOWN_SUSPECTED", setOf("E-R3"))
  @JvmField val WOL_NO_EFFECT = DiagCode("WOL_NO_EFFECT", setOf("E-R6"))
  @JvmField val NAS_SESSION_OK = DiagCode("NAS_SESSION_OK")
  @JvmField val NAS_AUTH_BLOCKED = DiagCode("NAS_AUTH_BLOCKED", setOf("C5", "E-R8", "E-S12"))
  @JvmField val NAS_AUTH_FAILED = DiagCode("NAS_AUTH_FAILED", setOf("C5", "E-R8"))
  @JvmField val NAS_ACCOUNT_LOCKED = DiagCode("NAS_ACCOUNT_LOCKED", setOf("C5", "E-S12"))
  @JvmField val NAS_ACCOUNT_DISABLED = DiagCode("NAS_ACCOUNT_DISABLED", setOf("C5", "E-S12"))
  @JvmField val NAS_PASSWORD_EXPIRED = DiagCode("NAS_PASSWORD_EXPIRED", setOf("C5", "E-S12"))
  @JvmField val NAS_ACCOUNT_RESTRICTED = DiagCode("NAS_ACCOUNT_RESTRICTED", setOf("C5", "E-S12"))
  @JvmField val NAS_GUEST_REFUSED = DiagCode("NAS_GUEST_REFUSED", setOf("C3"))
  @JvmField val NAS_NOT_ENCRYPTED = DiagCode("NAS_NOT_ENCRYPTED", setOf("C3"))
  @JvmField val NAS_DIALECT_REFUSED = DiagCode("NAS_DIALECT_REFUSED", setOf("C3"))
  @JvmField val NAS_SIGNING_MISSING = DiagCode("NAS_SIGNING_MISSING", setOf("C3"))
  @JvmField val NAS_IDENTITY_MISMATCH = DiagCode("NAS_IDENTITY_MISMATCH", setOf("E-R7", "E-S10"))
  @JvmField val NAS_ADDRESS_CHANGED = DiagCode("NAS_ADDRESS_CHANGED", setOf("E-R7"))
  @JvmField val NAS_PERMISSION_DENIED = DiagCode("NAS_PERMISSION_DENIED", setOf("E-R10", "E-N9"))
  @JvmField val NAS_SHARE_MISSING = DiagCode("NAS_SHARE_MISSING", setOf("E-R10"))
  @JvmField val NAS_QUOTA_EXCEEDED = DiagCode("NAS_QUOTA_EXCEEDED", setOf("E-N13"))
  @JvmField val NAS_FULL = DiagCode("NAS_FULL", setOf("E-R9"))
  @JvmField val NAS_FILE_BUSY = DiagCode("NAS_FILE_BUSY", setOf("E-N13"))
  @JvmField val NAS_SERVICE_DOWN = DiagCode("NAS_SERVICE_DOWN")
  @JvmField val NAS_TIMEOUT = DiagCode("NAS_TIMEOUT", setOf("E-R1"))
  @JvmField val NAS_WAKING_UP = DiagCode("NAS_WAKING_UP", setOf("E-R1"))
  @JvmField val NAS_NOT_ALLOWED_HERE = DiagCode("NAS_NOT_ALLOWED_HERE", setOf("E-S10", "E-R3", "E-S16"))
  @JvmField val NAS_STALLED = DiagCode("NAS_STALLED", setOf("E-R14"))
  @JvmField val NAS_CREDENTIALS_MISSING = DiagCode("NAS_CREDENTIALS_MISSING", setOf("E-S9"))
  @JvmField val NAS_NOT_FOUND = DiagCode("NAS_NOT_FOUND")
  @JvmField val NAS_SESSION_EXPIRED = DiagCode("NAS_SESSION_EXPIRED")
  @JvmField val NAS_UNEXPECTED = DiagCode("NAS_UNEXPECTED")
  @JvmField val NAS_CLOCK_SKEW = DiagCode("NAS_CLOCK_SKEW", setOf("E-P9"))
  @JvmField val NAS_NO_SHARES = DiagCode("NAS_NO_SHARES", setOf("E-O2"))
  @JvmField val NAS_COMMON_AREA_UNWRITABLE = DiagCode("NAS_COMMON_AREA_UNWRITABLE", setOf("E-O3"))

  // --- Test di connessione -------------------------------------------------
  @JvmField val TEST_STAGE = DiagCode("TEST_STAGE")
  @JvmField val TEST_PASSED = DiagCode("TEST_PASSED")
  @JvmField val TEST_FAILED = DiagCode("TEST_FAILED")
  @JvmField val TEST_NOT_CONFIGURED = DiagCode("TEST_NOT_CONFIGURED", setOf("E-O1"))
  @JvmField val TEST_AUTH_SKIPPED = DiagCode("TEST_AUTH_SKIPPED", setOf("C5", "E-S12"))

  // --- Attività ------------------------------------------------------------
  @JvmField val ACT_RUNNING = DiagCode("ACT_RUNNING")
  @JvmField val ACT_LOCAL_BUFFER_ONLY = DiagCode("ACT_LOCAL_BUFFER_ONLY", setOf("E-A6"))
  @JvmField val ACT_REDUCED_PACE = DiagCode("ACT_REDUCED_PACE", setOf("E-A5"))
  @JvmField val ACT_PROFILE_INACTIVE = DiagCode("ACT_PROFILE_INACTIVE", setOf("E-P19"))
  @JvmField val ACT_ROLE_DISABLED = DiagCode("ACT_ROLE_DISABLED")
  @JvmField val ACT_DISABLED = DiagCode("ACT_DISABLED")
  @JvmField val ACT_APP_NOT_VISIBLE = DiagCode("ACT_APP_NOT_VISIBLE")
  @JvmField val ACT_ROAMING = DiagCode("ACT_ROAMING")
  @JvmField val ACT_OFFLINE = DiagCode("ACT_OFFLINE")
  @JvmField val ACT_NETWORK_NOT_TRUSTED = DiagCode("ACT_NETWORK_NOT_TRUSTED", setOf("E-S10"))
  @JvmField val ACT_POOR_NETWORK = DiagCode("ACT_POOR_NETWORK", setOf("E-R4"))
  @JvmField val ACT_OUTSIDE_WINDOW = DiagCode("ACT_OUTSIDE_WINDOW")
  @JvmField val ACT_NEEDS_POWER = DiagCode("ACT_NEEDS_POWER", setOf("E-P16"))
  @JvmField val ACT_BATTERY_LOW = DiagCode("ACT_BATTERY_LOW")
  @JvmField val ACT_POWER_SAVER = DiagCode("ACT_POWER_SAVER", setOf("E-P10"))
  @JvmField val ACT_THERMAL = DiagCode("ACT_THERMAL", setOf("E-A5"))
  @JvmField val ACT_NETWORK_NOT_ALLOWED = DiagCode("ACT_NETWORK_NOT_ALLOWED", setOf("E-R13"))
  @JvmField val ACT_HEAVY_NEEDS_HOME = DiagCode("ACT_HEAVY_NEEDS_HOME", setOf("E-R12"))
  @JvmField val ACT_CAP_REACHED = DiagCode("ACT_CAP_REACHED", setOf("E-B10"))
  @JvmField val ACT_ANDROID_LIMIT = DiagCode("ACT_ANDROID_LIMIT", setOf("E-P14"))
  @JvmField val ACT_NAS_UNREACHABLE = DiagCode("ACT_NAS_UNREACHABLE", setOf("E-R5", "E-A6"))
  @JvmField val ACT_CONFIRMATION_REQUIRED = DiagCode("ACT_CONFIRMATION_REQUIRED")
  @JvmField val ACT_FROZEN = DiagCode("ACT_FROZEN", setOf("E-S11"))
  @JvmField val ACT_JOB_STOPPED = DiagCode("ACT_JOB_STOPPED", setOf("E-P4", "E-P8", "E-P14"))

  // --- Ultimi esiti ed errori ----------------------------------------------
  @JvmField val EVT_GENERIC = DiagCode("EVT_GENERIC")
  @JvmField val EVT_QR_REJECTED = DiagCode("EVT_QR_REJECTED", setOf("E-O5"))
  @JvmField val EVT_CONFIG_SUPERSEDED = DiagCode("EVT_CONFIG_SUPERSEDED", setOf("E-O6"))
  @JvmField val EVT_SELF_WIPE = DiagCode("EVT_SELF_WIPE", setOf("E-S2", "C15"))

  // Verifica funzionale, RE-4: gli esiti dei lavori, scritti nell'anello da `DiagnosticsRecorder` (prima nessuno ci scriveva).
  @JvmField val EVT_JOB_RESULT = DiagCode("EVT_JOB_RESULT")
  @JvmField val EVT_JOB_FAILED = DiagCode("EVT_JOB_FAILED", setOf("R1"))
  @JvmField val EVT_START_FAILED = DiagCode("EVT_START_FAILED")

  // --- Spazio --------------------------------------------------------------
  @JvmField val STORAGE_USAGE = DiagCode("STORAGE_USAGE")
  @JvmField val STORAGE_PHONE_LOW = DiagCode("STORAGE_PHONE_LOW", setOf("E-P3"))

  // --- Consumi del mese ----------------------------------------------------
  @JvmField val TRAFFIC_MONTH = DiagCode("TRAFFIC_MONTH")
  @JvmField val TRAFFIC_ACTIVITY = DiagCode("TRAFFIC_ACTIVITY")
  @JvmField val TRAFFIC_ROAMING = DiagCode("TRAFFIC_ROAMING")

  // --- Avvisi di Android ---------------------------------------------------
  @JvmField val AND_BATTERY_UNRESTRICTED = DiagCode("AND_BATTERY_UNRESTRICTED")
  @JvmField val AND_BATTERY_OPTIMIZED = DiagCode("AND_BATTERY_OPTIMIZED", setOf("E-P2", "E-P15"))
  @JvmField val AND_BUCKET_OK = DiagCode("AND_BUCKET_OK")
  @JvmField val AND_BUCKET_RARE = DiagCode("AND_BUCKET_RARE", setOf("E-P15"))
  @JvmField val AND_BUCKET_RESTRICTED = DiagCode("AND_BUCKET_RESTRICTED", setOf("E-P2", "E-P15"))
  @JvmField val AND_BACKGROUND_RESTRICTED = DiagCode("AND_BACKGROUND_RESTRICTED", setOf("E-P2"))
  @JvmField val AND_HIBERNATION_ON = DiagCode("AND_HIBERNATION_ON", setOf("E-P15"))
  @JvmField val AND_HIBERNATION_OFF = DiagCode("AND_HIBERNATION_OFF")
  @JvmField val AND_NOTIFICATIONS_DENIED = DiagCode("AND_NOTIFICATIONS_DENIED", setOf("E-O4"))
  @JvmField val AND_MEDIA_DENIED = DiagCode("AND_MEDIA_DENIED", setOf("E-O4", "E-P1", "E-P15"))
  @JvmField val AND_MEDIA_PARTIAL = DiagCode("AND_MEDIA_PARTIAL", setOf("E-P1"))
  @JvmField val AND_MEDIA_LOCATION_DENIED = DiagCode("AND_MEDIA_LOCATION_DENIED", setOf("E-O4"))
  @JvmField val AND_CAMERA_DENIED = DiagCode("AND_CAMERA_DENIED", setOf("E-O4"))
  @JvmField val AND_NO_SCREEN_LOCK = DiagCode("AND_NO_SCREEN_LOCK", setOf("E-S3", "E-O4"))
  @JvmField val AND_USER_LOCKED = DiagCode("AND_USER_LOCKED", setOf("E-P13"))
  @JvmField val AND_RECENT_REBOOT = DiagCode("AND_RECENT_REBOOT", setOf("E-P13", "E-P4"))
  @JvmField val AND_EXIT_CRASH = DiagCode("AND_EXIT_CRASH")
  @JvmField val AND_EXIT_ANR = DiagCode("AND_EXIT_ANR")
  @JvmField val AND_EXIT_MEMORY = DiagCode("AND_EXIT_MEMORY", setOf("E-P3"))
  @JvmField val AND_EXIT_FORCE_STOP = DiagCode("AND_EXIT_FORCE_STOP", setOf("E-P2"))
  @JvmField val AND_EXIT_FREEZER = DiagCode("AND_EXIT_FREEZER")
  @JvmField val AND_EXIT_PERMISSION_CHANGE = DiagCode("AND_EXIT_PERMISSION_CHANGE", setOf("E-P1", "E-P15"))
  @JvmField val AND_BACKGROUND_STALLED = DiagCode("AND_BACKGROUND_STALLED", setOf("E-P15"))

  // --- Fiducia -------------------------------------------------------------
  @JvmField val TRUST_APPROVED = DiagCode("TRUST_APPROVED")
  @JvmField val TRUST_PENDING = DiagCode("TRUST_PENDING", setOf("E-S14"))
  @JvmField val TRUST_RETIRED = DiagCode("TRUST_RETIRED")
  @JvmField val TRUST_REVOKED = DiagCode("TRUST_REVOKED")
  @JvmField val TRUST_LOST = DiagCode("TRUST_LOST", setOf("E-S1", "E-S2", "C15"))
  @JvmField val TRUST_NOT_CONFIGURED = DiagCode("TRUST_NOT_CONFIGURED")
  @JvmField val TRUST_REGISTRY_STALE = DiagCode("TRUST_REGISTRY_STALE")
  @JvmField val TRUST_NEWER_UNVERIFIED = DiagCode("TRUST_NEWER_UNVERIFIED")
  @JvmField val TRUST_LISTING_TRUNCATED = DiagCode("TRUST_LISTING_TRUNCATED", setOf("M1"))
  @JvmField val TRUST_NEEDS_APP_UPDATE = DiagCode("TRUST_NEEDS_APP_UPDATE", setOf("E-I3"))
  @JvmField val TRUST_EQUIVOCATION = DiagCode("TRUST_EQUIVOCATION")
  @JvmField val TRUST_REGISTRY_FORK = DiagCode("TRUST_REGISTRY_FORK")
  @JvmField val TRUST_WIPE_IN_PROGRESS = DiagCode("TRUST_WIPE_IN_PROGRESS", setOf("E-S2", "C15"))
  @JvmField val TRUST_WIPED = DiagCode("TRUST_WIPED", setOf("E-S2"))
  @JvmField val TRUST_REJECTIONS = DiagCode("TRUST_REJECTIONS")
  @JvmField val TRUST_ANCHOR_WEAK = DiagCode("TRUST_ANCHOR_WEAK", setOf("B9", "M7"))
  @JvmField val TRUST_LOOKALIKE = DiagCode("TRUST_LOOKALIKE", setOf("E-O7", "E-O8"))
  @JvmField val TRUST_PENDING_REQUESTS = DiagCode("TRUST_PENDING_REQUESTS", setOf("E-S8", "E-S14"))
  @JvmField val TRUST_NO_ADMIN = DiagCode("TRUST_NO_ADMIN", setOf("E-S13"))
  @JvmField val TRUST_OLD_RECOVERY_KIT = DiagCode("TRUST_OLD_RECOVERY_KIT", setOf("M4"))
  @JvmField val TRUST_LOST_NOT_SEEN = DiagCode("TRUST_LOST_NOT_SEEN", setOf("E-S1"))

  // --- Profili -------------------------------------------------------------
  @JvmField val PROFILE_PRIMARY = DiagCode("PROFILE_PRIMARY")
  @JvmField val PROFILE_SECONDARY = DiagCode("PROFILE_SECONDARY", setOf("E-P19"))
  @JvmField val PROFILE_OWN_VPN = DiagCode("PROFILE_OWN_VPN", setOf("E-P20"))
  @JvmField val PROFILE_HEARTBEAT_OK = DiagCode("PROFILE_HEARTBEAT_OK")
  @JvmField val PROFILE_HEARTBEAT_STALE = DiagCode("PROFILE_HEARTBEAT_STALE", setOf("E-P19"))

  // --- Rete e NAS ----------------------------------------------------------
  @JvmField val DIAG_SECTION_UNAVAILABLE = DiagCode("DIAG_SECTION_UNAVAILABLE")

  // --- Libreria del telefono (Fase 3) --------------------------------------------------------------------------
  // `PHONE_LIBRARY_STATE` e' il codice di stato che P0 ha messo per tenere viva la sezione; lo teniamo (nessun
  // contributore lo produce, ma e' innocuo e il suo titolo serve alle voci senza un codice piu' preciso).
  @JvmField val PHONE_LIBRARY_STATE = DiagCode("PHONE_LIBRARY_STATE")

  // --- Il NAS nella Libreria (Fase 4) ----------------------------------------------------------------------------
  // P0 aggiunge solo il codice di stato che tiene viva la sezione NAS_LIBRARY (un test vuole almeno una voce per
  // sezione). I codici veri della 4A (IndexDiag, NasMediaDiag, PrepDiag nei contratti dei moduli nuovi) li cataloga P11.
  @JvmField val NAS_LIBRARY_STATE = DiagCode("NAS_LIBRARY_STATE")

  // P11: i codici della 4A. Quelli dei moduli (`IndexDiag` di core-index, `NasMediaDiag` di core-nasmedia, `PrepDiag` di
  // core-prepare) sono scritti come letterali con gli STESSI id ed edge case: questo modulo non vede quei moduli. Un test
  // di :app (`DiagnosticsWiringTest`) verifica l'uguaglianza, `Fase4ACatalogTest` qui la tabella e gli edge case.
  // Quelli del contributore di :app (`NasLibraryDiagnostics`): l'emergenza e le aree non raggiungibili.
  @JvmField val NAS_WRITES_STOPPED = DiagCode("NAS_WRITES_STOPPED")

  // Revisione di fine Fase 7 (R11, pacchetto C): l'emergenza «Ferma le scritture» che non si e' potuta salvare su disco vale solo finche' l'app resta aperta.
  @JvmField val NAS_WRITES_STOP_NOT_SAVED = DiagCode("NAS_WRITES_STOP_NOT_SAVED")
  @JvmField val NAS_AREA_UNREACHABLE = DiagCode("NAS_AREA_UNREACHABLE", setOf("E-L4"))

  // Scansione (NasMediaDiag).
  @JvmField val NAS_SCAN_STATE = DiagCode("NAS_SCAN_STATE", setOf("E-N5"))
  @JvmField val NAS_SCAN_FROZEN = DiagCode("NAS_SCAN_FROZEN", setOf("C11", "E-S11"))
  @JvmField val NAS_SCAN_SKIPPED_NAMES = DiagCode("NAS_SCAN_SKIPPED_NAMES", setOf("E-N6"))
  @JvmField val NAS_SCAN_ALBUM_COVERS = DiagCode("NAS_SCAN_ALBUM_COVERS", setOf("E-N14"))
  @JvmField val NAS_SCAN_NOT_MEDIA = DiagCode("NAS_SCAN_NOT_MEDIA", setOf("E-M12"))
  @JvmField val NAS_SCAN_ARRIVING = DiagCode("NAS_SCAN_ARRIVING", setOf("E-N4"))
  @JvmField val NAS_SCAN_BUSY = DiagCode("NAS_SCAN_BUSY", setOf("E-N13"))
  @JvmField val NAS_SCAN_LOOPS = DiagCode("NAS_SCAN_LOOPS", setOf("E-N10"))
  @JvmField val NAS_SCAN_TRUNCATED = DiagCode("NAS_SCAN_TRUNCATED", setOf("E-N5"))
  @JvmField val NAS_SCAN_AWAY_CAP = DiagCode("NAS_SCAN_AWAY_CAP", setOf("D22"))

  // Indice condiviso (IndexDiag).
  @JvmField val INDEX_REJECTED = DiagCode("INDEX_REJECTED", setOf("E-I5", "E-I14", "C9"))
  @JvmField val INDEX_ROLLBACK = DiagCode("INDEX_ROLLBACK", setOf("E-I5"))
  @JvmField val INDEX_NEWER_FORMAT = DiagCode("INDEX_NEWER_FORMAT", setOf("E-I3"))
  @JvmField val INDEX_IDENTITY_CONFLICT = DiagCode("INDEX_IDENTITY_CONFLICT", setOf("E-I18"))
  @JvmField val INDEX_OUTBOX_PENDING = DiagCode("INDEX_OUTBOX_PENDING", setOf("E-I6"))
  @JvmField val INDEX_LOCAL_ONLY = DiagCode("INDEX_LOCAL_ONLY", setOf("D31"))
  @JvmField val INDEX_CLOCK_JUMP = DiagCode("INDEX_CLOCK_JUMP", setOf("E-N17", "D32"))
  @JvmField val INDEX_AREA_READ_ONLY = DiagCode("INDEX_AREA_READ_ONLY", setOf("E-I19"))
  @JvmField val INDEX_SHARE_DROPPED = DiagCode("INDEX_SHARE_DROPPED", setOf("E-I8", "E-R10"))
  @JvmField val INDEX_NO_PREPARERS = DiagCode("INDEX_NO_PREPARERS", setOf("E-I10", "E-I12"))
  @JvmField val INDEX_REPLICA_STATE = DiagCode("INDEX_REPLICA_STATE", setOf("E-L6"))
  @JvmField val INDEX_LEASES_CONTENDED = DiagCode("INDEX_LEASES_CONTENDED", setOf("E-I1"))

  // Preparazione (PrepDiag).
  @JvmField val PREP_STATE = DiagCode("PREP_STATE")
  @JvmField val PREP_DECODER_MISSING = DiagCode("PREP_DECODER_MISSING", setOf("E-M13"))
  @JvmField val PREP_UNREADABLE = DiagCode("PREP_UNREADABLE", setOf("E-M10"))
  @JvmField val PREP_TOO_LARGE = DiagCode("PREP_TOO_LARGE", setOf("E-M3"))

  // P16: i codici della 4B (organizzare il NAS) del contributore di :app (`NasOpsDiagnostics`, `NasOpsDiagCodes`): operazioni sui
  // file, cestino del NAS, manutenzione dell'indice, dati personali. Stessi id ed edge case, scritti come letterali (questo modulo non
  // vede :app); `DiagnosticsWiringTest` di :app verifica l'uguaglianza, `Fase4BCatalogTest` qui la tabella e gli edge case.
  @JvmField val NAS_OPS_STATE = DiagCode("NAS_OPS_STATE", setOf("E-D6"))
  @JvmField val NAS_OPS_LOCKED = DiagCode("NAS_OPS_LOCKED", setOf("E-N13"))
  @JvmField val NAS_OPS_FAILED = DiagCode("NAS_OPS_FAILED", setOf("E-D5", "E-D9", "E-D13", "E-N9", "E-N11"))
  @JvmField val NAS_TRASH_STATE = DiagCode("NAS_TRASH_STATE", setOf("E-D2"))
  @JvmField val NAS_TRASH_PURGE = DiagCode("NAS_TRASH_PURGE", setOf("E-D4", "E-S11"))
  @JvmField val NAS_INDEX_MAINTENANCE = DiagCode("NAS_INDEX_MAINTENANCE", setOf("M6"))
  @JvmField val NAS_PERSONAL_STATE = DiagCode("NAS_PERSONAL_STATE", setOf("E-H4"))
  @JvmField val NAS_PERSONAL_REJECTED = DiagCode("NAS_PERSONAL_REJECTED", setOf("E-F5", "D26"))

  // --- Il backup (Fase 5A) -----------------------------------------------------------------------------------------
  // P0 aggiunge solo il codice di stato che tiene viva la sezione BACKUP (un test vuole almeno una voce per sezione), come per
  // NAS_LIBRARY nella Fase 4. I codici del §2.6 (`BackupProblemCode` di core-backup) sono qui sotto (P7).
  @JvmField val BACKUP_STATE = DiagCode("BACKUP_STATE")

  // P7: i codici del §2.6 (`BackupProblemCode` di core-backup, un DiagCode per riga della tabella: "BACKUP_" + il nome del codice) e due voci
  // di stato del contributore di :app (`BackupDiagnostics`). Stessi id ed edge case, scritti come letterali (questo modulo non vede
  // core-backup); `DiagnosticsWiringTest` di :app verifica l'uguaglianza con `BackupProblemCode`, `Fase5ACatalogTest` qui la tabella.
  @JvmField val BACKUP_NAS_UNREACHABLE = DiagCode("BACKUP_NAS_UNREACHABLE", setOf("E-R5"))
  @JvmField val BACKUP_NAS_FULL = DiagCode("BACKUP_NAS_FULL", setOf("E-R9"))
  @JvmField val BACKUP_QUOTA = DiagCode("BACKUP_QUOTA", setOf("E-N13"))
  @JvmField val BACKUP_AUTH = DiagCode("BACKUP_AUTH", setOf("E-R8"))
  @JvmField val BACKUP_READ_ONLY_AREA = DiagCode("BACKUP_READ_ONLY_AREA", setOf("E-N9", "E-I19"))
  @JvmField val BACKUP_NO_PRIVATE_AREA = DiagCode("BACKUP_NO_PRIVATE_AREA", setOf("E-O2"))
  @JvmField val BACKUP_MEDIA_PERMISSION = DiagCode("BACKUP_MEDIA_PERMISSION", setOf("E-P15", "E-O4"))
  @JvmField val BACKUP_MEDIA_LOCATION = DiagCode("BACKUP_MEDIA_LOCATION", setOf("E-P21"))
  @JvmField val BACKUP_PARTIAL_ACCESS = DiagCode("BACKUP_PARTIAL_ACCESS", setOf("E-P1"))
  @JvmField val BACKUP_CAP_REACHED = DiagCode("BACKUP_CAP_REACHED", setOf("E-B10"))
  @JvmField val BACKUP_FROZEN = DiagCode("BACKUP_FROZEN", setOf("E-B19", "C11"))
  @JvmField val BACKUP_WRITES_STOPPED = DiagCode("BACKUP_WRITES_STOPPED", setOf("E-B19"))
  @JvmField val BACKUP_TEST_ONLY = DiagCode("BACKUP_TEST_ONLY", setOf("D12"))
  @JvmField val BACKUP_LOST_BEFORE_BACKUP = DiagCode("BACKUP_LOST_BEFORE_BACKUP", setOf("E-B4"))
  @JvmField val BACKUP_VERIFY_FAILED = DiagCode("BACKUP_VERIFY_FAILED", setOf("E-B18", "E-B8"))
  @JvmField val BACKUP_UNREADABLE = DiagCode("BACKUP_UNREADABLE", setOf("D15", "E-M12"))
  @JvmField val BACKUP_COMMON_COPY_LOST = DiagCode("BACKUP_COMMON_COPY_LOST", setOf("E-B13", "L44"))
  @JvmField val BACKUP_UNEXPECTED = DiagCode("BACKUP_UNEXPECTED", setOf("R1"))

  /** Fase 5B (P14): delle versioni aspettano perche' il file sul NAS e' stato cambiato dal PC (D17, E-E6). */
  @JvmField val BACKUP_SOURCE_CHANGED = DiagCode("BACKUP_SOURCE_CHANGED", setOf("E-E6"))
  @JvmField val BACKUP_PHONE_STORAGE_LOW = DiagCode("BACKUP_PHONE_STORAGE_LOW", setOf("E-P3"))
  @JvmField val BACKUP_STALE = DiagCode("BACKUP_STALE", setOf("E-P15", "E-P2"))
  @JvmField val BACKUP_WAITING_UNLOCK = DiagCode("BACKUP_WAITING_UNLOCK", setOf("E-P13"))
  @JvmField val BACKUP_PROFILE_INACTIVE = DiagCode("BACKUP_PROFILE_INACTIVE", setOf("E-P19"))
  @JvmField val BACKUP_LAST_ROUND = DiagCode("BACKUP_LAST_ROUND")
  @JvmField val BACKUP_SIMILAR_COPIES = DiagCode("BACKUP_SIMILAR_COPIES")

  /** Fase 5B (P14): i contatori delle integrazioni (selettore, documenti, ricevuti, modifica, condivisioni), solo codici e numeri. */
  @JvmField val INTEGRATIONS_COUNTERS = DiagCode("INTEGRATIONS_COUNTERS", setOf("C17", "D23", "D24"))

  // --- La ricerca (Fase 6) -------------------------------------------------------------------------------------------
  // P0 aggiunge solo il codice di stato che tiene viva la sezione SEARCH (un test vuole almeno una voce per sezione), come per BACKUP
  // nella Fase 5. I codici del §2.6 di docs/fase6-progetto.md (AI_MODEL_*, AI_*, OCR_FAILED, SEARCH_INDEX_REBUILT, CUTOUT_*...) li
  // aggiunge P11.
  @JvmField val SEARCH_STATE = DiagCode("SEARCH_STATE")

  // --- Gli album (Fase 7) --------------------------------------------------------------------------------------------
  // P0 aggiunge solo il codice di stato che tiene viva la sezione ALBUMS (un test vuole almeno una voce per sezione), come per SEARCH
  // nella Fase 6. I codici del §2.8 di docs/fase7-progetto.md (ALBUM_*, SHARE_*, SMART_*, TRIP_*: `AlbumDiag` di core-albums) li
  // aggiunge P13.
  @JvmField val ALBUMS_STATE = DiagCode("ALBUMS_STATE")

  // P13: i codici del §2.8 (stessi id e stessi edge case di `AlbumDiag` di core-albums, scritti come letterali perche' questo modulo non vede
  // core-albums). `Phase7DiagCatalogTest` di :app verifica che ogni codice del contratto sia qui, con lo stesso id e gli stessi edge case.
  @JvmField val ALBUM_CONTRIB_REJECTED = DiagCode("ALBUM_CONTRIB_REJECTED", setOf("E-SH14", "C24"))
  @JvmField val ALBUM_ENTRY_DENIED = DiagCode("ALBUM_ENTRY_DENIED", setOf("E-SH13", "C25"))
  @JvmField val ALBUM_ITEM_UNATTESTED = DiagCode("ALBUM_ITEM_UNATTESTED", setOf("E-SH15", "E-SH17"))
  @JvmField val SHARE_PAUSED_SPACE = DiagCode("SHARE_PAUSED_SPACE", setOf("E-SH8"))
  @JvmField val SHARE_PAUSED_FROZEN = DiagCode("SHARE_PAUSED_FROZEN", setOf("E-SH19"))
  @JvmField val SHARE_WAITING_LOCKED = DiagCode("SHARE_WAITING_LOCKED")
  @JvmField val SHARE_SOURCE_CHANGED = DiagCode("SHARE_SOURCE_CHANGED", setOf("E-SH18"))
  @JvmField val SHARE_SAFE_COPY_QUEUED = DiagCode("SHARE_SAFE_COPY_QUEUED", setOf("E-B13", "E-B14"))
  @JvmField val SMART_TOO_BROAD = DiagCode("SMART_TOO_BROAD", setOf("E-AL6"))
  @JvmField val SMART_NO_SEARCH = DiagCode("SMART_NO_SEARCH", setOf("E-AL9"))
  @JvmField val TRIP_NO_GPS = DiagCode("TRIP_NO_GPS", setOf("E-AL16"))
  @JvmField val ALBUM_PROJECTION_PENDING = DiagCode("ALBUM_PROJECTION_PENDING")

  // Revisione di fine Fase 7 (DG-2): la manutenzione dell'Utility (Doppioni), che girava senza lasciare traccia. Stessi id di `AlbumDiag`.
  @JvmField val UTILITY_FULL_HASH = DiagCode("UTILITY_FULL_HASH", setOf("E-U4"))
  @JvmField val UTILITY_SIGNING = DiagCode("UTILITY_SIGNING")

  // Revisione di fine Fase 7 (R22, pacchetti A e B): i registri della condivisione illeggibili, i contributi tenuti da parte, la pausa «catalogo» della porta di
  // completamento. Stessi id ed edge case di `AlbumDiag`.
  @JvmField val ALBUM_REGISTRY_UNREADABLE = DiagCode("ALBUM_REGISTRY_UNREADABLE")
  @JvmField val ALBUM_CONTRIB_HELD = DiagCode("ALBUM_CONTRIB_HELD")
  @JvmField val SHARE_PAUSED_CATALOG = DiagCode("SHARE_PAUSED_CATALOG", setOf("C26"))

  // Correzioni F (27/10): i contatori del pacchetto A senza codice (R1, R2, R12, R19) e gli smart in fila per il budget delle ricerche per contenuto. Stessi id
  // di `AlbumDiag`. Solo numeri.
  @JvmField val ALBUM_COPIES_CAPPED = DiagCode("ALBUM_COPIES_CAPPED")
  @JvmField val ALBUM_REOPEN_REFUSED = DiagCode("ALBUM_REOPEN_REFUSED")
  @JvmField val ALBUM_ADOPTION_REFUSED = DiagCode("ALBUM_ADOPTION_REFUSED")
  @JvmField val ALBUM_SEGMENTS_RESTORED = DiagCode("ALBUM_SEGMENTS_RESTORED")
  @JvmField val ALBUM_OVER_CAP = DiagCode("ALBUM_OVER_CAP")
  @JvmField val SMART_SEARCH_WAITING = DiagCode("SMART_SEARCH_WAITING")

  // --- Le persone (Fase 8) -------------------------------------------------------------------------------------------
  // P0 aggiunge solo il codice di stato che tiene viva la sezione PEOPLE (un test vuole almeno una voce per sezione), come per ALBUMS nella
  // Fase 7. I codici del §2.10 di docs/fase8-progetto.md (FACES_*, PEOPLE_*: `FacesDiag` di core-faces) li aggiunge P13, con le chiavi
  // nell'elenco chiuso dell'esportazione. Mai nomi, id, chiavi, riquadri, vettori, conteggi per persona o generazione (D37).
  @JvmField val PEOPLE_STATE = DiagCode("PEOPLE_STATE")

  // P13: i codici del §2.10 di docs/fase8-progetto.md (stessi id di `FacesDiag` di core-faces, scritti come letterali perche' questo modulo non vede
  // core-faces); `Phase8DiagCatalogTest` di :app verifica che ogni codice di `FacesDiag.ALL` sia qui. Solo numeri e codici: mai nomi, id, chiavi, riquadri,
  // vettori, conteggi per persona o generazione (D37). `PRE_PUBLISH_FAILED` e' il codice del gancio dei dati personali (P8).
  @JvmField val FACES_MODEL_MISMATCH = DiagCode("FACES_MODEL_MISMATCH", setOf("E-A11"))
  @JvmField val FACES_ENGINE_FAILED = DiagCode("FACES_ENGINE_FAILED")
  @JvmField val FACES_VECTOR_INVALID = DiagCode("FACES_VECTOR_INVALID", setOf("E-A12"))
  @JvmField val FACES_NO_INDEXER = DiagCode("FACES_NO_INDEXER", setOf("E-F10"))
  @JvmField val FACES_NO_PRIVATE_AREA = DiagCode("FACES_NO_PRIVATE_AREA", setOf("E-F10"))
  @JvmField val FACES_WAITING_PREVIEWS = DiagCode("FACES_WAITING_PREVIEWS", setOf("E-F18"))
  @JvmField val FACES_SEGMENT_REJECTED = DiagCode("FACES_SEGMENT_REJECTED", setOf("E-F41"))
  @JvmField val FACES_HEAD_REJECTED = DiagCode("FACES_HEAD_REJECTED", setOf("E-F41"))
  @JvmField val FACES_FOREIGN_GENERATION = DiagCode("FACES_FOREIGN_GENERATION", setOf("E-F33"))
  @JvmField val FACES_HOLDER_REBASED = DiagCode("FACES_HOLDER_REBASED", setOf("E-F26"))
  @JvmField val FACES_KEY_LOST = DiagCode("FACES_KEY_LOST", setOf("E-F19"))
  @JvmField val FACES_ERASE_LOCAL_DONE = DiagCode("FACES_ERASE_LOCAL_DONE", setOf("E-F30"))
  @JvmField val FACES_ERASE_NAS_PENDING = DiagCode("FACES_ERASE_NAS_PENDING", setOf("E-F30"))
  @JvmField val FACES_ERASE_NAS_DONE = DiagCode("FACES_ERASE_NAS_DONE")
  @JvmField val FACES_ERASE_WAITING_DEVICES = DiagCode("FACES_ERASE_WAITING_DEVICES", setOf("E-F32"))
  @JvmField val FACES_RECYCLE_BIN = DiagCode("FACES_RECYCLE_BIN", setOf("E-F34"))
  @JvmField val FACES_NEEDS_UPDATE = DiagCode("FACES_NEEDS_UPDATE", setOf("E-F12"))
  @JvmField val FACES_ERASE_PENDING = DiagCode("FACES_ERASE_PENDING", setOf("E-F32"))
  @JvmField val PRE_PUBLISH_FAILED = DiagCode("PRE_PUBLISH_FAILED")
  @JvmField val FACES_KILLED_MEMORY = DiagCode("FACES_KILLED_MEMORY", setOf("E-A16"))
  @JvmField val FACES_MOUNT_FAILED = DiagCode("FACES_MOUNT_FAILED")

  // Correzioni E (L320, L343, C232, C234): quattro conteggi con il loro codice. Solo numeri.
  @JvmField val FACES_ERASE_FILES_HELD = DiagCode("FACES_ERASE_FILES_HELD", setOf("E-F30"))
  @JvmField val FACES_GROUPING_SKIPPED = DiagCode("FACES_GROUPING_SKIPPED")
  @JvmField val FACES_SEGMENT_WAITING = DiagCode("FACES_SEGMENT_WAITING", setOf("E-F41"))
  @JvmField val FACES_ARCHIVE_FULL = DiagCode("FACES_ARCHIVE_FULL", setOf("E-F41"))

  // Correzioni C (CA-7, C280): l'archivio dei volti di questo dispositivo non si apre adesso. Solo un numero, non il motivo.
  @JvmField val FACES_STORE_UNAVAILABLE = DiagCode("FACES_STORE_UNAVAILABLE")

  // Correzioni D (R2, L190 esteso): la cronologia delle ricerche potrebbe tenere ancora un nome.
  @JvmField val FACES_HISTORY_KEPT = DiagCode("FACES_HISTORY_KEPT", setOf("E-F6"))

  // --- Ricordi e mappa (Fase 9) --------------------------------------------------------------------------------------
  // P0 aggiunge solo i due codici di stato che tengono vive le sezioni MEMORIES e MAP (un test vuole almeno una voce per sezione), come
  // PEOPLE_STATE nella Fase 8. Gli altri codici del §2.12 di docs/fase9-progetto.md (MEMORY_*, NOTIFY_*, MAP_*: `MemoriesDiag` di
  // core-memories e `MapDiag` di core-map) li aggiunge P13, con le chiavi nell'elenco chiuso dell'esportazione. Mai titoli, luoghi, date,
  // id, nomi, coordinate, riquadri, tessere, nomi delle regioni o l'ora scelta (D33).
  @JvmField val MEMORY_STATE = DiagCode("MEMORY_STATE")
  @JvmField val MAP_STATE = DiagCode("MAP_STATE")

  // P13: gli altri codici del §2.12 (stessi id di `MemoriesDiag` di core-memories e `MapDiag` di core-map, scritti come letterali perche' questo modulo
  // non vede i due core; `Phase9DiagCatalogTest` di :app verifica che ogni codice dei contratti sia qui). Solo conteggi per motivo.
  @JvmField val MEMORY_SHELF_RESET = DiagCode("MEMORY_SHELF_RESET")
  @JvmField val MEMORY_NO_VECTORS = DiagCode("MEMORY_NO_VECTORS", setOf("E-MEM16"))
  @JvmField val MEMORY_DROPPED = DiagCode("MEMORY_DROPPED")
  @JvmField val NOTIFY_SKIPPED = DiagCode("NOTIFY_SKIPPED", setOf("E-NT1", "E-NT2", "E-NT6"))
  @JvmField val NOTIFY_LATE = DiagCode("NOTIFY_LATE", setOf("E-NT3"))
  @JvmField val NOTIFY_REVOKED = DiagCode("NOTIFY_REVOKED", setOf("E-NT7", "E-H8"))
  @JvmField val MAP_REQUESTS = DiagCode("MAP_REQUESTS")
  @JvmField val MAP_REFUSED = DiagCode("MAP_REFUSED", setOf("E-MAP1", "E-MAP4"))
  @JvmField val MAP_REGION_PARTIAL = DiagCode("MAP_REGION_PARTIAL")
  @JvmField val MAP_REGION_SPACE = DiagCode("MAP_REGION_SPACE")
  @JvmField val MAP_INVALID_POINTS = DiagCode("MAP_INVALID_POINTS", setOf("E-MAP7"))

  // Correzioni mappa e rete (31/10): R2 (la scelta "mappa dettagliata spenta" fallisce chiusa), R5 (il database di MapLibre fuori da `map/`), MA-1 (l'indice dei punti non si legge).
  // Stessi id di `MapDiag` di core-map (`Phase9WiringTest` di :app li incrocia).
  @JvmField val MAP_SETTINGS_UNREADABLE = DiagCode("MAP_SETTINGS_UNREADABLE")
  @JvmField val MAP_SETTINGS_DAMAGED = DiagCode("MAP_SETTINGS_DAMAGED")
  @JvmField val MAP_CACHE_UNMOVED = DiagCode("MAP_CACHE_UNMOVED")
  @JvmField val MAP_INDEX_FAILED = DiagCode("MAP_INDEX_FAILED")

  // P11: i codici del §2.6 (stessi id e stessi edge case di `ModelDiag`, `AiDiag`, `AiIndexDiag` e `SearchDiag`, scritti come letterali
  // perche' questo modulo non vede core-models, core-ai, core-index ne' core-search). `Phase6DiagCatalogTest` di :app verifica che ogni
  // codice dei contratti sia qui, con lo stesso id e gli stessi edge case.
  @JvmField val AI_MODEL_MISSING = DiagCode("AI_MODEL_MISSING", setOf("E-Q11"))
  @JvmField val AI_MODEL_FETCH_FAILED = DiagCode("AI_MODEL_FETCH_FAILED", setOf("E-A1"))
  @JvmField val AI_MODEL_MISMATCH = DiagCode("AI_MODEL_MISMATCH", setOf("E-A2", "E-S16"))
  @JvmField val AI_MODEL_NO_SPACE = DiagCode("AI_MODEL_NO_SPACE", setOf("E-A3"))
  @JvmField val AI_ENGINE_INIT_FAILED = DiagCode("AI_ENGINE_INIT_FAILED")
  @JvmField val AI_GPU_FALLBACK = DiagCode("AI_GPU_FALLBACK", setOf("E-A12"))
  @JvmField val AI_VECTOR_INVALID = DiagCode("AI_VECTOR_INVALID", setOf("E-A12"))
  @JvmField val AI_NO_INDEXER_AREA = DiagCode("AI_NO_INDEXER_AREA", setOf("E-I12"))
  @JvmField val AI_STORAGE_LOW = DiagCode("AI_STORAGE_LOW", setOf("E-A17"))
  @JvmField val AI_KILLED_MEMORY = DiagCode("AI_KILLED_MEMORY", setOf("E-A16"))
  @JvmField val AI_PENDING_PUBLISH = DiagCode("AI_PENDING_PUBLISH", setOf("E-A15"))
  @JvmField val OCR_FAILED = DiagCode("OCR_FAILED")
  @JvmField val TEXT_READING_OFF_CLEANUP = DiagCode("TEXT_READING_OFF_CLEANUP", setOf("E-A18"))
  @JvmField val CUTOUT_FAILED = DiagCode("CUTOUT_FAILED")
  @JvmField val CUTOUT_SAVE_FAILED = DiagCode("CUTOUT_SAVE_FAILED")
  @JvmField val AI_SEGMENT_REJECTED = DiagCode("AI_SEGMENT_REJECTED", setOf("E-I20", "C9"))
  @JvmField val AI_VECTOR_DISAGREE = DiagCode("AI_VECTOR_DISAGREE", setOf("E-A14"))
  @JvmField val AI_INDEX_STATE = DiagCode("AI_INDEX_STATE", setOf("E-A15"))
  @JvmField val SEARCH_INDEX_REBUILT = DiagCode("SEARCH_INDEX_REBUILT")

  // I codici di `PhoneLibraryDiagnostics` (core-mediastore, P2): stessi id e stessi edge case di
  // `PhoneLibraryDiagCodes`, scritti come letterali perche' questo modulo non dipende da core-mediastore.
  // `Fase3CatalogTest` verifica la tabella qui sotto e un test di :app (P11) verifica l'uguaglianza con
  // `PhoneLibraryDiagCodes`: un refuso in un id farebbe mostrare la voce senza titolo ne' "cosa fare".
  @JvmField val PHONE_ACCESS = DiagCode("PHONE_ACCESS", setOf("E-P1", "E-P15"))
  @JvmField val PHONE_MEDIA_LOCATION = DiagCode("PHONE_MEDIA_LOCATION", setOf("E-P21"))
  @JvmField val PHONE_SYNC = DiagCode("PHONE_SYNC", setOf("E-P4", "E-P8", "E-P12", "E-P22"))
  @JvmField val PHONE_DISK_FULL = DiagCode("PHONE_DISK_FULL", setOf("E-P3"))
  @JvmField val PHONE_STORAGE_LOCKED = DiagCode("PHONE_STORAGE_LOCKED", setOf("E-P2", "E-P10", "E-P13"))
  @JvmField val PHONE_VOLUMES = DiagCode("PHONE_VOLUMES", setOf("E-P6"))
  @JvmField val PHONE_IGNORED = DiagCode("PHONE_IGNORED", setOf("E-M12"))
  @JvmField val PHONE_ABSENCES_BLOCKED = DiagCode("PHONE_ABSENCES_BLOCKED", setOf("E-P1", "E-P15"))
  @JvmField val PHONE_OTHER_PROFILES = DiagCode("PHONE_OTHER_PROFILES", setOf("E-P7", "E-P17"))

  // I codici del contributore di `:app` (LibraryDiagnostics, P11): luoghi offline, indice della Libreria, miniature.
  // PHONE_PLACES: solo se il dataset dei luoghi c'e' o no (nessun edge case: manca per scelta finche' i dati GeoNames non
  // sono inclusi, §6 del progetto). PHONE_INDEX: dimensione, ricostruzioni, patch e scritture dell'istantanea
  // (E-H1 riguarda i nascosti, che non entrano mai nell'indice: non e' un avviso e non si dichiara qui).
  // PHONE_THUMBNAILS: i contatori della cache delle miniature e delle decodifiche in volo (E-V5).
  @JvmField val PHONE_PLACES = DiagCode("PHONE_PLACES")
  @JvmField val PHONE_INDEX = DiagCode("PHONE_INDEX")
  @JvmField val PHONE_THUMBNAILS = DiagCode("PHONE_THUMBNAILS", setOf("E-V5"))

  // --- I codici scritti dai contributori di core-network e core-family (P9) ----------------------------------
  // P2 e P3 hanno scelto i propri identificativi mentre P8 scriveva il catalogo: qui sono gli STESSI oggetti dei
  // moduli (stessi id, stessi edge case), cosi' ogni voce che quei contributori producono ha titolo, "cosa fare" e
  // azione dal catalogo. `DiagnosticsWiringTest` (in :app) verifica che non ne manchi nessuno.
  @JvmField val NET_UNTRUSTED_NETWORK: DiagCode = NetworkDiagCodes.UNTRUSTED_NETWORK
  @JvmField val NET_LOCAL_NETWORK: DiagCode = NetworkDiagCodes.LOCAL_NETWORK
  @JvmField val NET_VPN_AT_HOME: DiagCode = NetworkDiagCodes.VPN_AT_HOME
  @JvmField val NET_IPV4_ONLY: DiagCode = NetworkDiagCodes.IPV4_ONLY
  @JvmField val NET_VPN_CONTROL: DiagCode = NetworkDiagCodes.VPN_CONTROL
  @JvmField val NET_VPN_LOCKDOWN: DiagCode = NetworkDiagCodes.VPN_LOCKDOWN
  @JvmField val NET_VPN_NOT_CONTROLLABLE: DiagCode = NetworkDiagCodes.VPN_NOT_CONTROLLABLE
  @JvmField val NET_QUALITY_POOR: DiagCode = NetworkDiagCodes.QUALITY_POOR
  @JvmField val NET_HOME_FINGERPRINT: DiagCode = NetworkDiagCodes.HOME_FINGERPRINT
  @JvmField val NET_NAS_TCP: DiagCode = NetworkDiagCodes.NAS_TCP
  @JvmField val TRUST_STATE: DiagCode = TrustDiagCodes.STATE
  @JvmField val TRUST_REJECTED_FILES: DiagCode = TrustDiagCodes.REJECTED
  @JvmField val TRUST_VERSION_REPLACED: DiagCode = TrustDiagCodes.VERSION_REPLACED
  @JvmField val TRUST_SYNC_INCOMPLETE: DiagCode = TrustDiagCodes.INCOMPLETE
  @JvmField val TRUST_REGISTRY_BEHIND: DiagCode = TrustDiagCodes.REGISTRY_BEHIND
  @JvmField val TRUST_NO_MIN_VERSION: DiagCode = TrustDiagCodes.NO_MIN_VERSION
  @JvmField val TRUST_NOT_REFRESHED: DiagCode = TrustDiagCodes.NOT_REFRESHED
  @JvmField val TRUST_LOCAL_STATE_UNTRUSTED: DiagCode = TrustDiagCodes.LOCAL_STATE_UNTRUSTED
  @JvmField val TRUST_WIPE: DiagCode = TrustDiagCodes.WIPE
  @JvmField val TRUST_LOST_REPORT_REJECTED: DiagCode = TrustDiagCodes.LOST_REPORT_REJECTED
  @JvmField val TRUST_PUBLISH_UNVERIFIED: DiagCode = TrustDiagCodes.PUBLISH_UNVERIFIED
  @JvmField val TRUST_PUBLISH_BLOCKED: DiagCode = TrustDiagCodes.PUBLISH_BLOCKED
  @JvmField val TRUST_STATUS_REJECTED: DiagCode = TrustDiagCodes.STATUS_REJECTED
}
