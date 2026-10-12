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
  @JvmField val ACT_JOB_STOPPED = DiagCode("ACT_JOB_STOPPED", setOf("E-P4", "E-P8", "E-P14"))

  // --- Ultimi esiti ed errori ----------------------------------------------
  @JvmField val EVT_GENERIC = DiagCode("EVT_GENERIC")
  @JvmField val EVT_QR_REJECTED = DiagCode("EVT_QR_REJECTED", setOf("E-O5"))
  @JvmField val EVT_CONFIG_SUPERSEDED = DiagCode("EVT_CONFIG_SUPERSEDED", setOf("E-O6"))
  @JvmField val EVT_SELF_WIPE = DiagCode("EVT_SELF_WIPE", setOf("E-S2", "C15"))

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
