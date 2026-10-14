package dev.pampa.mycloudgallery.feature.diagnostics.export

/**
 * Le chiavi che possono entrare nell'esportazione della Diagnostica: un **elenco chiuso** (revisione 2, F4).
 *
 * `DiagItem.EXPORT_KEY` controlla solo la forma di una chiave: `"share.${nome}"` o `"mario.rossi"` la superano, e
 * anche un `ExportValue.Num` sotto una chiave qualunque puo' portare un dato (un IPv4 e' un numero). Qui ogni chiave
 * e' scritta una per una, con il contributore che la produce; una chiave nuova si esporta solo dopo essere stata
 * aggiunta qui, cioe' dopo che qualcuno ha deciso che cosa porta. Quelle fuori elenco si lasciano fuori in silenzio
 * (la voce si esporta senza di loro). I test dei contributori controllano che nessuna chiave vera manchi.
 */
object ExportKeys {

  /** Le chiavi esatte, per contributore. */
  val ALLOWED: Set<String> = setOf(
    // Rete (core-network, NetworkDiagnosticsContributor)
    "place", "transport", "metered", "roaming", "validated", "captivePortal", "ipv6Global", "vpn", "home", "weakClues",
    "vpnClues", "allowed", "via", "denyReason", "lookAlike", "access", "result", "elapsed", "vpnUp", "nasSilent",
    "capability", "automation", "configured", "ulaCount", "gateway", "domain", "currentUla0", "currentHasUla",
    // Fiducia (core-family, TrustDiagnostics)
    "state", "registryVersion", "devices", "phase", "total", "forks", "statusRejected", "files",
    // Test di connessione (ConnectionTestRunner)
    "timedOut", "stage", "outcome", "error", "ntStatus", "dialect", "encryptionOffered", "clockOffsetMs", "breakerOpen",
    // Attivita' (ActivitiesContributor)
    "activity", "decision", "confirmMobileData", "confirmRoaming", "confirmOverCap", "network", "scope", "pace",
    "remainingBytes", "reason", "alsoBlockedBy", "batteryLevel", "batteryThreshold", "capKind", "usedBytes", "capBytes",
    "thermalStatus", "role", "budget",
    // Salute del background (BackgroundHealthContributor)
    "ignoringBatteryOpt", "backgroundRestricted", "standbyBucket", "unusedAppRestrictions", "screenLock", "notifications",
    "mediaImages", "mediaVideo", "mediaPartial", "mediaLocation", "camera", "userUnlocked", "isSystemUser", "uptime",
    "bootCount", "exitCount", "lastExitAge", "heartbeatAge",
    // Spazio (StorageContributor) e consumi (TrafficContributor)
    "databaseBytes", "cacheBytes", "noBackupBytes", "filesBytes", "freeBytes", "totalBytes", "source", "mobileBytes",
    "wifiBytes", "roamingBytes",
    // Eventi (RecentEventsContributor)
    "ageMs",
    // Libreria del telefono (core-mediastore, PhoneLibraryDiagnostics): solo numeri, flag ed enumerati (mai nomi di
    // file, percorsi, volumi, coordinate o luoghi). "mediaLocation" e "state" sono gia' sopra.
    "media", "volumes", "granted", "pendingPhotos", "metaWaiting", "pauseReason", "lastReason", "lastFailure", "seen",
    "added", "updated", "vanished", "fullScan", "completed", "deletedFiles", "fullScansCompleted", "reconcileVanished",
    "purgedAbsences", "metaDone", "metaFailed", "mounted", "known", "unavailable", "fullScanInProgress",
    "fullScanPending", "zeroBytes", "notMedia", "invalidName", "unreadable", "times",
    // Libreria del telefono (:app, LibraryDiagnostics): anche qui solo numeri (conteggi e byte), flag ed enumerati;
    // mai una stringa con un dato dell'utente (un luogo, un percorso, un nome di file).
    "places", "placesFailure", "dataset", "items", "rebuilds", "patches", "snapshotWrites", "reconciled",
    "cachedBytes", "budgetBytes", "started", "cancelled", "failed", "memoryHits", "misses", "inFlight",
    // NAS nella Libreria (Fase 4A: IndexDiagnostics di core-index, NasLibraryDiagnostics di :app). Solo conteggi, byte,
    // flag ed enumerati: mai share, cartelle, file, account, id di media o di dispositivo.
    "count", "level", "bytes", "converging", "complete", "localOnly", "emergency", "areas", "frozen", "unreachable",
    "status", "mode", "folders", "unchanged", "adopted", "detected", "arriving", "skippedNames", "skippedSystem",
    "albumCovers", "busy", "bytesRead", "resumed", "stoppedBy", "moved", "changed", "excludedFolders", "blockedFolders",
    "identityRefused", "units", "prepared", "handedOver", "records", "blobs", "contended", "busyLeases", "unleased",
    "lost", "clockJump", "headsRead", "headsApplied", "segmentsDownloaded", "segmentsMissing", "recordsApplied",
  )

  /**
   * Le famiglie di chiavi con una parte variabile che non viene da un dato: l'indice delle impronte ULA (al massimo 4),
   * il motivo di scarto delle firme (`rejected.` + una costante di `RejectReason`) e la rete dei consumi (`bytes.` + una
   * costante di `TrafficNetwork`), in minuscolo.
   */
  private val FAMILIES: List<Regex> = listOf(
    Regex("ula[0-3]"),
    Regex("rejected\\.[a-z][a-z_]{2,31}"),
    Regex("bytes\\.[a-z][a-z_]{2,31}"),
    // Il motivo di scarto dei segmenti e delle teste dell'indice (`reason.` + una costante di `RejectReason`, IndexDiagnostics)
    // e il motivo di un congelamento (`reason.` + `FreezeReason`): costanti del codice, in maiuscolo.
    Regex("reason\\.[A-Z][A-Z0-9_]{2,31}"),
  )

  fun isAllowed(key: String): Boolean = key in ALLOWED || FAMILIES.any { it.matches(key) }
}
