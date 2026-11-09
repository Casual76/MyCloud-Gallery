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
    // Il registro locale dei crash (C472, BackgroundHealthContributor): l'eta', la famiglia del thread, la versione, se e' il processo principale e, come elenchi di
    // identificativi del codice (`ExportValue.Trace`), la catena delle classi e le cornici nostre di ogni eccezione. Mai un messaggio di eccezione.
    "crashAge", "crashThread", "crashVersion", "crashMainProcess", "crashChain", "crashFrames0", "crashFrames1", "crashFrames2", "crashFrames3",
    // Spazio (StorageContributor) e consumi (TrafficContributor)
    "databaseBytes", "cacheBytes", "noBackupBytes", "filesBytes", "freeBytes", "totalBytes", "source", "mobileBytes",
    "wifiBytes", "roamingBytes",
    // La ricerca (Fase 6, AiDiagnostics di :app) e lo spazio che occupa (DG-5 della verifica funzionale): solo numeri, flag ed enumerati; mai query, testo
    // letto, luoghi, vettori, chiavi o share. Prima queste chiavi non erano nell'elenco e l'esportazione le lasciava fuori in silenzio.
    "queryModel", "indexModel", "indexer", "textReadingHere", "vectors", "texts", "bufferBytes", "models", "outbox", "held",
    "modelsBytes", "searchBytes", "aiBufferBytes",
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
    // Organizzare il NAS (Fase 4B: NasOpsDiagnostics di :app). Solo conteggi, byte ed enumerati: mai share, cartelle, file, account,
    // id di operazioni, di media o di dispositivi. I motivi per codice usano la famiglia `reason.` qui sotto.
    "opsActive", "opsRunning", "opsPaused", "opsLocked", "opsClosed", "expired", "purged", "alreadyGone", "deferred", "compacted",
    "skipped", "removed", "published", "applied", "stillUndated", "unresolved", "rejectedSegments", "rejectedHeads",
    // Il backup (Fase 5A: BackupDiagnostics di :app). Solo conteggi, byte, flag ed enumerati: mai share, cartelle, file, account, id di
    // media o di elementi del diario, impronte. I problemi per codice usano la famiglia `problem.` qui sotto.
    "safe", "queued", "unverified", "onlyCommonCopy", "noNasCopy", "sources", "folderChosen", "gate", "backupStatus", "done", "linked",
    "gone", "waiting", "bytesUploaded", "bytesReread", "diaryOpen", "diaryBytesOpen", "pending", "similarRunning", "available",
    "lastProblem", "startedAgeMs", "lastCompleteAgeMs", "beatAgeMs",
    // Le integrazioni (Fase 5B: `INTEGRATIONS_COUNTERS` di :app). I due flag; i contatori vengono subito dopo. Solo flag e numeri:
    // mai un nome di file, una share, un account, un id di media, un pacchetto di un'altra app.
    "entryAllowed", "receiveReady",
    // I contatori delle integrazioni (LA-1 della verifica funzionale della 5B): i codici fissi di `IntegrationCounter` (selettore, documenti,
    // nascosti fermati) e di `FlowCounters` (ricezione, "Modifica con…", condivisione senza posizione), uno per uno come ogni altra chiave:
    // niente famiglia, cosi' un contatore nuovo si esporta solo dopo essere stato aggiunto qui (lo ricorda `IntegrationsCountersExportTest`).
    "hidden_stopped",
    "picker_cancelled", "picker_delivered", "picker_delivered_nas_only", "picker_locked", "picker_nas_unreachable", "picker_not_ready",
    "picker_opened", "picker_refused_request", "picker_skipped", "picker_too_many",
    "provider_expired", "provider_locked", "provider_open_refused", "provider_opened", "provider_queries", "provider_read_cancelled",
    "provider_read_errors", "provider_read_timeouts", "provider_strangers_refused",
    "receive_discarded", "receive_locked", "receive_not_ready", "receive_opened", "receive_refused_request", "receive_rejected_file_scheme",
    "receive_rejected_no_space", "receive_rejected_not_media", "receive_rejected_own_provider", "receive_rejected_stage_too_big",
    "receive_rejected_too_large", "receive_rejected_too_many", "receive_rejected_unreadable", "receive_save_rejected", "receive_saved",
    "receive_staged_items",
    "edit_begin_unavailable", "edit_begun", "edit_expired", "edit_result_refused", "edit_settled_from_copy", "edit_settled_new_media",
    "edit_settled_new_version", "edit_settled_restored", "edit_settled_unchanged", "edit_unreadable",
    "share_items_direct", "share_items_stripped", "share_prepared", "share_released", "share_stopped_failed", "share_stopped_format",
    "share_stopped_limits", "share_stopped_location_remains", "share_stopped_unavailable",
    // Gli album (Fase 7, `AlbumsDiagnostics` di :app; R22 della revisione di fine fase: prima l'esportazione aveva solo `mounted` e `count`, e le prove
    // sul campo F72-F93 leggono la Diagnostica esportata). Solo numeri e flag: mai titoli, cartelle, luoghi, casa, coordinate o id. `mounted` e `count`
    // sono gia' sopra.
    "manual", "smart", "shared", "openShares", "contributions", "syncIncomplete", "roundErrors", "tripsSearched", "tripsHomeKnown", "searchReady",
    // La manutenzione dell'Utility (DG-2): firme dei Doppioni simili e controllo notturno dei video del NAS. `queued`, `waiting` e `skipped` sono gia' sopra.
    "enabled", "scanned", "withdrawn", "signed", "finished",
    // Le persone (Fase 8, `PeopleDiagnostics` di :app, D37): solo numeri, flag e costanti di enum; mai nomi, id di persone o di volti, chiavi di contenuto,
    // riquadri, vettori, conteggi per persona o generazione. `mounted`, `enabled`, `count`, `roundErrors`, `rejectedHeads` e `rejectedSegments` sono gia' sopra.
    "hereState", "awaitingNotice", "erasing", "fresh", "syncPending", "runStatus", "holder", "facesSaved", "indexers", "devicesRead",
    "headsAccepted", "holdActive", "eraseStep", "eraseLocal", "eraseNas", "eraseWaiting",
    // Correzioni E (C234): i segmenti in attesa (non sono rifiuti: `rejectedSegments` conta solo i veri rifiuti).
    "waitingSegments",
    // I ricordi e le notifiche (Fase 9, P13, `MemoriesDiagnostics` di :app, §2.12, D33): solo numeri, flag e costanti; mai titoli, luoghi, date, id dei ricordi,
    // nomi o id di persone, l'ora scelta. Gli esclusi e le notifiche saltate per motivo usano le famiglie `dropped.` e `skip.` qui sotto. `mounted`, `status`,
    // `roundErrors`, `failed` e `count` sono gia' sopra.
    "onShelf", "unseen", "lastRoundDays", "roundMs", "late", "delivered",
    // La mappa (Fase 9, P13, `MapDiagnostics` di :app): solo numeri, flag e costanti; mai coordinate, riquadri, tessere, nomi delle regioni o dei luoghi, la camera.
    // I rifiutati per motivo usano la famiglia `reason.` (le costanti di `RefuseReason`). `allowed`, `bytes` e `available` sono gia' sopra.
    "online", "byDefault", "opened", "points", "withoutPosition", "invalid", "regions", "errors", "paused", "withoutVersion", "tileJson",
    // Le zone senza Internet (Fase 9, P5): solo conti, byte e flag; mai quali zone ne' dove. `peakInFlight` e' il massimo di richieste insieme verso l'host delle tessere (al piu' 4).
    "regionsReady", "regionsPartial", "regionsBytes", "regionsLimit", "downloading", "peakInFlight", "freeBytes",
    // Il widget (Fase 10, P13, `WidgetDiagnostics` di :app, §2.13, D39): widget per misura, minuti dall'ultimo aggiornamento, frequenza scelta, foto saltate, svuotamenti per
    // causa, giri in ritardo, l'ultimo errore (costante). Mai id di media o di ricordi, titoli, luoghi, date.
    "widgetsSmall", "widgetsWide", "widgetsLarge", "minutesSinceUpdate", "period", "noThumb", "locked", "blankHidden", "blankMemory", "blankReset", "lateRuns",
    "lastError",
    // Il manifest firmato (Fase 10, P13, `ReleaseDiagnostics` di :app): versione, canale, `seq`, giorni dall'emissione, stato, indirizzi aggiuntivi (quanti), il messaggio
    // del catalogo (costante). I rifiutati per motivo usano la famiglia `rejected.`, le offerte per esito `offer.`. Mai il contenuto del manifest, mai URL.
    "versionCode", "channel", "seq", "issuedDays", "extraUrls", "loaded", "releaseMessage",
    // Le finestre (Fase 10, P13, `WindowDiagnostics` di :app): quante (al piu' due) e la classe di larghezza della finestra dell'app.
    "windows", "widthClass",
    // Il trascinamento fra app (Fase 10, P6 e P13): i codici fissi di `DragCounter` (feature-integrations). Mai un nome, un id, un percorso.
    "drag_out_ready", "drag_out_items", "drag_out_nas_only", "drag_out_stripped", "drag_out_discarded", "drag_out_released", "drag_out_refused_too_many",
    "drag_out_refused_too_large", "drag_out_refused_hidden", "drag_out_refused_location", "drag_out_refused_nas", "drag_out_refused_locked",
    "drag_out_refused_unavailable", "drag_in_dropped", "drag_in_refused", "drag_in_own_ignored", "drag_temp_cleaned",
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
    // I problemi del backup per codice (`problem.` + il codice di `BackupProblemCode`, in minuscolo): costanti del codice.
    Regex("problem\\.[a-z][a-z_]{2,31}"),
    // Fase 9 (P13): gli esclusi dei ricordi per motivo (`dropped.` + il codice di `DropReason`) e le notifiche saltate (`skip.` + il codice di `NotifySkip`).
    Regex("dropped\\.[a-z][a-z_]{2,31}"),
    Regex("skip\\.[a-z][a-z_]{2,31}"),
    // Fase 10 (P13): le offerte del manifest per esito (`offer.` + il codice di `OfferResult`), costanti del codice.
    Regex("offer\\.[a-z][a-z_]{2,31}"),
  )

  fun isAllowed(key: String): Boolean = key in ALLOWED || FAMILIES.any { it.matches(key) }
}
