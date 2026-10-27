# Android 15/16 e One UI 7/8: comportamenti che possono rompere backup e indicizzazione in background

Fase 0.5 del piano MyCloud Gallery. Redatto il 12/10/2026.

Per ogni comportamento di Android e di One UI che può fermare, ritardare o falsare il backup sul NAS, la sincronizzazione dell'indice e il calcolo AI notturno, questo documento dice che cosa succede davvero (con fonte e versione), che cosa si è visto sul Tab S9, che cosa deve fare l'app e quali edge case del piano (§6) tocca.

---

## 0. Come leggere questo documento

### Etichette di affidabilità

| Etichetta | Significato |
|---|---|
| [DOC] | Documentazione ufficiale Android o Samsung letta il 12/10/2026. |
| [AOSP] | Codice sorgente AOSP o AndroidX letto direttamente. |
| [TAB] | Verificato sul Galaxy Tab S9 (Android 16) con comandi adb di sola lettura. |
| [A17] | Verificato sul Galaxy S25 (Android 17) con gli stessi comandi di sola lettura. |
| [INF] | Inferenza ragionata, non confermata da una fonte. |
| [??] | Incerto: va provato con l'app di prova (sezione finale). |

Le fonti sono numerate [S1], [S2]… e l'elenco con gli indirizzi è nell'Appendice B. Le fonti non ufficiali (forum, stampa, mirror) sono dichiarate come tali.

### Dispositivi

- **Tab S9** (SM-X710, Wi-Fi): Android 16 (SDK 36), One UI 8.5, patch di sicurezza 2026-09-05, cifratura per file (FBE). Samsung Gallery 15.8.00.88, MediaProvider Mainline "17" (versionCode 37), SDK extension S/T/U/V = 23. Durante le misure era sulla rete di casa, in carica, con l'utente 0 e la Secure Folder (id 150) e nessun profilo secondario.
- **Galaxy S25** (SM-S931B), letto dopo il tablet: **Android 17** (SDK 37.0), One UI 9.0, patch di sicurezza 2026-09-05, cifratura per file (FBE). Samsung Gallery 15.9.01.7 (compilata per target 37), MediaProvider Mainline "17", provider multimediale Samsung 17.0.12.0, SDK extension S/T/U/V/B/C = 23. Utenti: 0 (primario), un profilo di lavoro (id 10) e la Secure Folder (id 150), entrambi fermi in modalità silenziosa. Il telefono non supporta utenti commutabili.
- **Android 17** (API 37): per la stampa è stabile su Pixel dal 16/06/2026 e One UI 9 è in arrivo sugli S25 da fine settembre [S56]; le pagine ufficiali risultano aggiornate al 01/10/2026 [S9, S53]. Il Tab S9 è ancora su Android 16, l'S25 è già su Android 17: ogni tema ha quindi anche un confronto 16 contro 17. Le novità di Android 17 che toccano il piano sono raccolte nella sezione "Novità di Android 17 rilevanti", dopo il tema 7.
- Il tablet non era collegato durante le letture dell'S25: il confronto usa i valori già salvati.

### Metodo

Letture adb: `dumpsys` (jobscheduler, deviceidle, battery, power, thermalservice, usagestats, connectivity, user, activity), `settings list`, `device_config list`, `cmd package query-activities`, `content query` su MediaStore. Nessuna scrittura, nessuna installazione, nessun riavvio. Nel documento non compaiono SSID, indirizzi di rete, MAC, numeri di serie, nomi di file o foto: gli output sono ridotti a conteggi, costanti di sistema e nomi di pacchetto.

### Limiti noti

- Sul tablet c'è un solo utente (più la Secure Folder) e l'S25 non supporta utenti commutabili (ha solo un profilo di lavoro fermo e la Secure Folder): il tema 6 (profili) resta in gran parte teorico finché non si crea un utente di prova sul tablet.
- Nessun comportamento dinamico di Samsung Gallery (cestino, modifica, ripristino) è stato osservato in azione: si vede la struttura sul disco, non il flusso.
- Le pagine di riferimento delle API (`developer.android.com/reference/...`) non sono leggibili con lo strumento usato (pagine troppo grandi): per le classi di rete e per `MediaStore.DeletedFiles` ci si basa sul sorgente AOSP e sul comportamento osservato sul tablet.

---

## 1. Le scoperte che cambiano il piano

| # | Scoperta | Impatto sul piano | Test |
|---|---|---|---|
| 1 | **"In carica" ha tre significati diversi.** JobScheduler = alimentazione collegata. Il tracker interno di WorkManager = stato CHARGING o FULL. La Protezione batteria Samsung ferma la carica con il cavo collegato (stato NOT_CHARGING). Tab S9 (soglia 90%) e S25 (soglia 80%, Android 17): `status=4` con `ac:true`, ma JobScheduler dice "Considered charging: true" su entrambi. | 1.6 deve definire "in carica" come alimentazione collegata (`EXTRA_PLUGGED`). Il vincolo "charging" di WorkManager può non far partire, o fermare a metà, il lavoro notturno. | T-01 |
| 2 | **Non esiste un servizio in primo piano notturno "gratis".** `dataSync` non può partire da BOOT_COMPLETED (Android 15); `mediaProcessing` non è nell'elenco dei vietati ma non è nell'allowlist di Tab S9 e S25 (maschera identica); avviare un servizio da un job in background richiede un'esenzione, e per i job non ce n'è. L'unica realistica è "ottimizzazione batteria disattivata" (Samsung: Illimitata). | Il passo 10 dell'onboarding diventa condizione per le sessioni lunghe. Senza esenzione si lavora solo a tappe di job normali. | T-02 |
| 3 | **Budget dei servizi in primo piano: 6 h su 24 h per tipo.** `dataSync` e `mediaProcessing` hanno budget separati e condivisi da tutti i servizi dell'app di quel tipo. Tab S9 e S25: 6 h + 6 h; `shortService` 3 minuti. | Usare il tipo giusto per attività (upload e sync indice = `dataSync`; calcolo AI e preparazione = `mediaProcessing`). Salvare e fermarsi in `onTimeout`. | T-03 |
| 4 | **Android 16: i job che girano insieme a un servizio in primo piano consumano la quota del bucket** (prima era illimitata). Eccezioni: in carica, app "Illimitata" (quota generosa), UIDT, app visibile. **Android 17 non cambia nulla di documentato su job e servizi, e le costanti dell'S25 sono identiche a quelle del tablet.** | "Mettere un servizio in primo piano" non basta. "Fai ora" va fatto come UIDT (Tab S9 e S25: garanzia 6 h, limite 12 h per job, 24 h cumulate). | T-04, T-05 |
| 5 | **Tre sospensioni indipendenti**: hibernation di Android (90 giorni, controllo ogni 15: uguale su Tab S9 e S25), Sleeping/Deep sleeping di Samsung (circa 3 e 16 giorni), bucket Restricted (8 giorni senza interazione). Il bucket segue l'uso: la Samsung Gallery è RARE sul tablet, ACTIVE sull'S25; OneDrive è RESTRICTED su entrambi con `RUN_ANY_IN_BACKGROUND=ignore`. | La Diagnostica deve usare un "battito" (ultimo successo per attività) più i segnali di sistema. Tre schermate da guidare nell'onboarding. | T-06, T-07 |
| 6 | **Riavvii che riportano il dispositivo "prima del primo sblocco".** Riavvio automatico Samsung (schermo spento, batteria ≥30%), Riavvio per inattività (One UI 8+: 72 h bloccato, attivo di default) e analogo di Google Play Services (3 giorni). **Sull'S25 il Riavvio per inattività è leggibile: `inactivity_restart=1` (acceso)**; sul Tab S9 la chiave non esiste. Fino al primo sblocco lo storage condiviso e il database dell'app non sono leggibili. | Stato "In attesa di sblocco" e stime che lo considerano. Per un tablet condiviso da scrivania è un caso reale. | T-08 |
| 7 | **L'`_ID` di MediaStore non è un'identità, e sui due dispositivi ha due numerazioni diverse.** Tab S9: tutti gli `_ID` ≥ 1.000.000.000 (database ricreato con la numerazione "a miliardo" degli URI stabili). S25: tutti < 70.000 (1.506–69.450), numerazione storica, con gli stessi flag degli URI stabili attivi. `getVersion()` è diverso per ogni app dal target 36 e un cambio impone il resync completo. `MediaStore.DeletedFiles` elenca gli ID cancellati (extension S ≥ 23, presente su entrambi). | 1.3 e 3.1: identità per impronta, `_ID` solo come cache. Nessuna ipotesi sull'intervallo degli ID. | T-10 |
| 8 | **Il cestino di Samsung Gallery non usa `IS_TRASHED`.** I file vanno in `Android/.Trash/com.sec.android.gallery3d/` (con `.nomedia`), fuori dal MediaStore: stessa struttura su Tab S9 e S25. Una foto cestinata appare come cancellata. | E-P11 non è riconoscibile dalle colonne. Stato "Assente dal telefono", mai "persa" subito, finestra di tolleranza e copia sul NAS al più presto. | T-09 |
| 9 | **Senza `ACCESS_MEDIA_LOCATION` il GPS viene oscurato nei byte letti** [DOC + INF]. La copia sul NAS perderebbe la posizione e il suo hash non coinciderebbe con l'originale. Sull'S25 anche WhatsApp e Telegram non hanno quel permesso. | Il backup richiede `ACCESS_MEDIA_LOCATION` e la lettura con `setRequireOriginal`. Cambia anche la verifica "byte per byte". | T-11 |
| 10 | **Il selettore di sistema è chiuso.** Cloud Media Provider: programma pilota per app nominate dagli OEM (max 3 per OEM, lista lato server). Tab S9 e S25: `allowed_cloud_providers` contiene solo Google Foto; `take_over_get_content=false`. WhatsApp e Telegram hanno un selettore proprio (permessi `READ_MEDIA_*` dichiarati); Gmail no (dedotto dai permessi). | 5.7: l'app non può "comparire" nel Photo Picker. Vie praticabili: handler GET_CONTENT/PICK, DocumentsProvider, Condividi. | T-14 |
| 11 | **Samsung Gallery non risponde ad ACTION_EDIT** né su Tab S9 (Android 16) né su S25 (Android 17): rispondono Google Foto e OneDrive. Il "Salva" di Samsung sovrascrive (l'originale resta nascosto), "Salva copia" crea un file nuovo. | 5.4: l'esito di "Modifica con…" si legge dal MediaStore più che dal risultato dell'intent. | T-16 |
| 12 | **Permesso di rete locale (Android 17).** Obbligatorio solo per le app con **target ≥ 37**. Letto sull'S25: le app con target 36 e 35 hanno `ACCESS_LOCAL_NETWORK` già concesso in modo implicito, mentre Google Foto (target 37) lo ha negato finché non lo chiede; la restrizione opt-in `RESTRICT_LOCAL_NETWORK` è disattivata di default. Quindi con target 36 su Android 17 non c'è blocco oggi, ma la concessione implicita è dichiarata "temporanea". Negato = timeout (TCP) o EPERM (UDP): SMB 445 e Wake-on-LAN sono colpiti. | Con target 36 nulla si rompe ora; il flusso del permesso va costruito già adesso e il passaggio a target 37 pianificato. | T-19, T-24 |
| 13 | **WireGuard per Android non usa `allowBypass()`**: la VPN risulta non bypassabile e `Network.bindSocket()` non esce dal tunnel [DOC + AOSP + INF]. La rete Wi-Fi sottostante resta osservabile. Per riconoscere casa senza posizione, il prefisso ULA IPv6 della FRITZ!Box è l'impronta più forte; `fritz.box` e il gateway sono uguali in tutte le FRITZ!Box. Android 17 aggiunge la schermata di sistema `android.settings.VPN_APP_EXCLUSION_SETTINGS` (risolve sull'S25). | 2.1 e 2.5: spegnere il tunnel in casa invece di aggirarlo; escludere l'app dal tunnel (anche dalla nuova schermata) non va bene perché varrebbe anche fuori casa. Riconoscimento casa a più indizi. | T-17, T-18 |
| 14 | **Profili secondari.** Il telefono non supporta utenti commutabili; sul Tab S9 si può avere fino a 8 utenti. Sull'S25 i profili fermi (lavoro e Secure Folder) hanno i job registrati ma **non pronti**: `Ready: false (… user=false …)` per tutti. La VPN è per utente [DOC]. Tab S9: al massimo 4 utenti in esecuzione e un parametro "utente in background considerato sacrificabile" a 26 h (uguale sull'S25). | 2.8: il lavoro dei profili non attivi è da misurare solo sul tablet; la deduzione "utente non avviato = job fermi" ora è osservata. | T-20 |
| 15 | **Backup Android e trasferimento da dispositivo a dispositivo.** Nessuna novità di Android 17 nelle pagine ufficiali lette; confermata la base: con target ≥ 12, `allowBackup="false"` può disattivare il backup cloud ma non il trasferimento tra dispositivi su alcuni produttori, e senza sezione `<device-transfer>` quella modalità è del tutto abilitata [DOC]. | 1.5 e E-O8: `dataExtractionRules` con esclusione esplicita in `<device-transfer>`; comportamento di Smart Switch da provare. | T-25 |
| 16 | **Altre novità di Android 17** (S25 già su 17): limiti di memoria per app (sull'S25 il limitatore risulta disabilitato), URI impliciti delle condivisioni (stop in Android 18), `getPendingJobReasonStats()`, librerie native in sola lettura e schermi grandi non più opt-out (solo target 37). | Sezione "Novità di Android 17 rilevanti"; decisione sul passaggio a target 37. | T-23, T-24 |

---

## Tema 1 — WorkManager, JobScheduler, servizi in primo piano

### 1.1 Comportamento

**Quote per standby bucket.** Valori "approssimati" della documentazione [S12], validi dal bucket introdotto in Android 9:

| Bucket | Job regolari | Job expedited | Allarmi | Rete |
|---|---|---|---|---|
| Active | 20 min in 60 min | 30 min in 24 h | nessun limite | libera |
| Working set | 10 min in 4 h | 15 min in 24 h | 10 l'ora | libera |
| Frequent | 10 min in 12 h | 10 min in 24 h | 2 l'ora | libera |
| Rare | 10 min in 24 h | 10 min in 24 h | 1 l'ora | disabilitata |
| Restricted | una volta al giorno, fino a 10 min | 5 min in 24 h | uno al giorno | disabilitata |

Regole generali [S11, S12]:

- Le restrizioni dei bucket valgono "a batteria". **In carica** non c'è limite di esecuzione per i job, tranne nel bucket Restricted, dove i limiti si allentano solo con dispositivo in carica, inattivo e su rete non a consumo.
- Si entra in **Restricted** dopo 8 giorni senza interazione (Android 13+; 45 giorni in Android 12/12L) o per eccesso di broadcast e binding. Sono esentate, tra le altre: app con widget attivi, app che l'utente ha messo "illimitate", app VPN, app companion.
- App in primo piano o visibile: nessun limite di esecuzione. Utente che ha messo l'app "Illimitata": quota "generosa" (da Android 16; prima nessun limite).

**Android 16 e i servizi in primo piano (risposta alla domanda sulle quote)** [S7, S12, S14]. Da Android 16 la quota di runtime dei job viene applicata anche a:

1. job avviati mentre l'app era visibile e proseguiti dopo che è diventata invisibile;
2. job che girano insieme a un servizio in primo piano (prima nessun limite);
3. il bucket Active ha una quota "generosa" invece di nessun limite.

Coinvolti: WorkManager, JobScheduler, DownloadManager. La pagina di WorkManager lo dice esplicitamente: "i long-running worker (che usano servizi in primo piano) possono esaurire la quota dei job" e suggerisce UIDT [S14]. Effetto per noi: un worker lungo con `setForeground()` non è un modo per aggirare il bucket. Restano le eccezioni documentate: carica collegata, app Illimitata, UIDT, app visibile.

**Durata di un singolo job.** La documentazione ufficiale non dà un massimo generale per job [S12]. I valori in uso sono costanti di sistema (sezione 1.2). WorkManager dichiara solo che i long-running worker "possono girare oltre 10 minuti" [S14].

**Servizi in primo piano `dataSync` e `mediaProcessing`** (app con target ≥ 35) [S1, S2, S4]:

- 6 ore su 24 per tipo. Il budget è **per app e per tipo**: lo condividono tutti i servizi di quel tipo. Si azzera quando l'utente porta l'app in primo piano.
- Alla scadenza il sistema chiama `Service.onTimeout(int, int)`; il servizio ha "pochi secondi" per `stopSelf()`, altrimenti `RemoteServiceException` (fatale) [S1, S2]. La pagina dei tipi parla di ANR per `mediaProcessing` [S4]: le pagine non concordano, si tratta come fatale.
- A budget esaurito, un nuovo avvio lancia `ForegroundServiceStartNotAllowedException` ("Time limit already exhausted for foreground service type dataSync") [S1, S2].
- I due budget sono indipendenti dal bucket di standby.
- Gli usi previsti: `dataSync` comprende upload, backup e ripristino, "local file processing" [S4]; `mediaProcessing` è per "operazioni lunghe sui media, come conversione di formato" [S4]. Non esiste un tipo per l'inferenza ML: il calcolo AI sulle anteprime è assimilabile a `mediaProcessing` o `dataSync` [INF].

**BOOT_COMPLETED.** Con target ≥ 35 un ricevitore di BOOT_COMPLETED non può avviare servizi di tipo `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`; l'eccezione è `ForegroundServiceStartNotAllowedException` [S2]. `mediaProcessing` **non è nell'elenco** [S2]. L'avvio dopo `ACTION_BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` resta una delle esenzioni generali, con queste restrizioni di tipo [S3].

**Avvio di un servizio in primo piano da background (Android 12+).** Esenzioni elencate [S3, S5]: transizione da stato visibile; azione dell'utente su un elemento UI dell'app (notifica, widget, bubble); allarme esatto per completare un'azione richiesta dall'utente; BOOT_COMPLETED (con le restrizioni sopra); **"l'utente disattiva le ottimizzazioni batteria per l'app"**; `SYSTEM_ALERT_WINDOW` (da Android 15 serve anche una finestra visibile); ruoli di sistema. **Nessuna esenzione per "sono dentro un job"** [S3, S5]. WorkManager lo ammette: `setForeground()` "might throw an exception if the launch was restricted" e va protetto con try/catch [S15].

**UIDT (user-initiated data transfer)** [S6]:

- Android 14+, `JobInfo.Builder.setUserInitiated(true)`, permesso `RUN_USER_INITIATED_JOBS`, `JobService` dedicato.
- Notifica obbligatoria impostata con `setNotification()` in `onStartJob()`; compare nel Task Manager.
- Vincoli ammessi: rete, carica, batteria non scarica, storage non scarso. Non quelli di inattività.
- Si può schedulare solo con l'app visibile o in una condizione equivalente a quelle che permettono di avviare attività dal background (avere un'attività nei Recenti non conta); altrimenti il job fallisce.
- "Non soggetto alle quote dei bucket". Nessun limite di tempo fisso: dipende dalla stima dei byte e dalle condizioni di sistema. Termica, memoria bassa e vincoli persi possono fermarlo.
- Se l'utente lo ferma dal Task Manager: il processo muore subito, senza `onStopJob()`, e il job non viene rischedulato.
- Nessuna libreria Jetpack lo supporta. Sotto Android 14: WorkManager con servizio in primo piano.

**WorkManager e i timeout dei servizi** [S16]: motivo di stop `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` (2.10.0-alpha04); fix dell'ANR per worker foreground di tipo short/data sync su API 34-35 (2.10.0-alpha03); 2.11.1 (28/01/2026) corregge un vincolo di rete che risultava soddisfatto con la connettività bloccata su Android 15+; ultima stabile 2.12.0 (23/09/2026), di cui non ho letto le note. Dal 14 il tipo di servizio è obbligatorio per i worker lunghi [S14].

**Rilevare i nuovi media senza polling.** Sul Tab S9 Google Foto usa un job con trigger di contenuto su `content://media/external/images/media` e `.../video/media` [TAB]. È l'unico job del tablet con "Has media backup exemption=true" [TAB]. L'esenzione è legata al ruolo di Cloud Media Provider (JobScheduler elenca `Media Cloud Providers: {0=com.google.android.apps.photos}`) e ai flag `update_media_backup_exemption_policy` e `allow_cmp_exemption_for_restricted_bucket`, entrambi attivi [TAB]. Dalla descrizione di un commit AOSP (letta solo in sintesi, pagina non raggiungibile) l'esenzione limita il bucket dei job di backup della galleria di sistema al WORKING_SET [INF]. Un'app di terze parti può usare lo stesso trigger di contenuto, ma senza l'esenzione.

**Salvaguardie di esecuzione (Android 14+).** `enable_execution_safeguards_udc=true`, con contatori per 24 ore [TAB]: 3 timeout di job regolari, 2 di job user-initiated, 5 di expedited, 10 in totale, e 3 ANR in 6 ore. L'effetto esatto al superamento non è stato letto [??].

**Doze** [S13]: scatta solo a dispositivo scollegato, fermo e a schermo spento; collegare il caricabatterie la fa terminare; in Doze WorkManager non gira. Nel nostro scenario ("notte, in carica") Doze non è il problema: lo sono bucket, sospensioni e riavvii.

### 1.2 Verificato sul Tab S9

Valori letti da `dumpsys jobscheduler`, `dumpsys activity settings`, `device_config` [TAB]. Sono costanti di sistema di questa build, non garanzie documentate.

| Parametro | Valore |
|---|---|
| Quota job regolari per bucket | Active 20 min / 1 h; Working 10 min / 4 h; Frequent 10 min / 12 h; Rare 10 min / 24 h; Restricted 10 min / 24 h; Exempted 20 min / 40 min |
| Sessioni / numero job per finestra | Rare: 3 sessioni, 48 job; Restricted: 1 sessione, 10 job; Working: 10 sessioni, 120 job; Frequent: 8 sessioni, 200 job |
| Tetto cumulativo di esecuzione in 24 h | 4 h (`qc_max_execution_time_ms`) |
| Limiti expedited per bucket (24 h) | Active 30 min; Working 15; Frequent 10; Rare 10; Restricted 5 |
| Runtime di un job regolare | garantiti 10 min, fino a 30 min con quota (`runtime_free_quota_max_limit_ms`) |
| Runtime di un expedited | garantiti 3 min |
| Runtime UIDT e user-initiated | garantiti 6 h (UIDT: moltiplicatore 1,35 sulla stima), limite 12 h per job, 24 h cumulate |
| Salvaguardie per 24 h | 3 timeout regolari, 2 user-initiated, 5 expedited, 10 totali; 3 ANR in 6 h |
| Timeout dei servizi in primo piano | `dataSync` 6 h; `mediaProcessing` 6 h; `shortService` 3 min |
| Allowlist dei tipi avviabili da BOOT_COMPLETED | maschera 1073743640 = `location`, `connectedDevice`, `health`, `remoteMessaging`, `systemExempted`, `specialUse`. Non contiene `dataSync`, `mediaProcessing`, `shortService`, `fileManagement`, `mediaPlayback`, camera, microfono, phoneCall, mediaProjection |
| Flag attivi | `enforce_quota_policy_to_top_started_jobs`, `thermal_restrictions_to_fgs_jobs`, `ignore_important_while_foreground`, `adjust_quota_default_constants` |
| Flexibility controller | `fc_applied_constraints=0 (nothing)`: nessun vincolo flessibile applicato |
| Stato termico | 0 (nessuna limitazione), pelle del dispositivo circa 30 °C |
| Bucket delle app (comando `am get-standby-bucket`) | Samsung Gallery 40 (RARE, assegnato il 11/10/2026 per "predizione"); Google Foto 20 (WORKING_SET); Samsung Cloud 5 (EXEMPTED); OneDrive 45 (RESTRICTED, con `RUN_ANY_IN_BACKGROUND: ignore`) |

Note:

- L'allowlist di BOOT_COMPLETED esclude `mediaProcessing`, mentre la documentazione non lo elenca tra i vietati [S2]. La documentazione e la build non concordano: si assume il comportamento più restrittivo.
- Il flag che applica la quota ai job con servizio in primo piano non compare tra i `device_config` letti. La documentazione dice che vale per tutte le app su Android 16 [S7]; con target 36 si assume applicato.

### 1.2 bis Verificato sull'S25 (Android 17)

Letture [A17] da `dumpsys jobscheduler`, `dumpsys activity settings`, `device_config`, `dumpsys platform_compat`, `dumpsys usagestats`.

- **Costanti identiche a quelle del Tab S9** in ogni riga della tabella 1.2: quote per bucket, sessioni e numero di job, tetto di 4 h, limiti expedited, runtime di 10 minuti garantiti e 30 con quota, runtime UIDT 6 h / 12 h / 24 h con moltiplicatore 1,35, salvaguardie (3 timeout regolari, 2 user-initiated, 5 expedited, 10 totali, 3 ANR in 6 h), `dataSync` 6 h, `mediaProcessing` 6 h, `shortService` 3 min, maschera BOOT_COMPLETED 1073743640 (stessi sei tipi ammessi, `mediaProcessing` escluso), Flexibility controller senza vincoli. **Android 17 non ha cambiato i valori.**
- **Flag**: i flag dei job che sul Tab S9 compaiono nei `device_config` e nel dump (`thermal_restrictions_to_fgs_jobs`, `enforce_quota_policy_to_top_started_jobs`, `update_media_backup_exemption_policy`, `allow_cmp_exemption_for_restricted_bucket`, `adjust_quota_default_constants`) sull'S25 non compaiono più: verosimilmente sono diventati comportamento fisso [INF]. Restano, e uno è nuovo, `get_pending_job_reason_stats_api`, più `get_pending_job_reasons_api`, `get_pending_job_reasons_history_api`, `job_debug_info_apis`, `handle_abandoned_jobs`, `enforce_proxied_jobs_limit`.
- **Modifiche di compatibilità** (dump `platform_compat`): `FGS_INTRODUCE_TIME_LIMITS` e `FGS_BOOT_COMPLETED_RESTRICTIONS` si attivano da target 35; `OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS` e `OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS` esistono come override disattivati (servono a forzare la regola su app che non la subirebbero); `LOCKDOWN_MEDIASTORE_VERSION` da target 36.
- **Bucket**: Samsung Gallery ACTIVE (10); la cronologia della giornata mostra passaggi 10 ↔ 20 con motivi "interazione dell'utente" e "predizione". Google Foto e WhatsApp ACTIVE; Samsung Cloud EXEMPTED (5); OneDrive RESTRICTED (45), ultimo uso 17/07/2026, `RUN_ANY_IN_BACKGROUND: ignore`. Sul Tab S9 la stessa Gallery era RARE: il bucket segue l'uso, e la nostra app sarà ACTIVE solo mentre viene usata.
- **Job con trigger di contenuto sul MediaStore**: tre soli job, del MediaProvider Samsung (servizio di sincronizzazione), di Google Foto (un "media monitor", bucket ACTIVE, nessuna esenzione) e di Maps. Nessun job con "media backup exemption" (sul Tab S9 ce n'era uno, il backup di Foto): l'esenzione compare solo quando quel job è pianificato.
- **Termica**: stato 0 con sensore AP a circa 56 °C e pelle a circa 38 °C (dispositivo sotto carico, load average intorno a 13). Stato 0 non significa dispositivo freddo: va letto anche l'headroom.
- Uptime 5 giorni e 10 ore, `boot_count` 33.


### 1.3 Strategia per l'app

1. **Lavoro a tappe, non job lunghi.** Ogni tappa è un job WorkManager che elabora un lotto con checkpoint persistente per elemento e termina entro circa 8 minuti (margine sui 10 garantiti). Allo stop (`onStopped`, motivo di stop) salva e esce. Le tappe si accodano a catena finché il motore delle regole (1.6) dice "continua". In carica i limiti dei bucket cadono; a batteria e in bucket Rare/Restricted restano 10 minuti per finestra.
2. **Escalation opzionale a servizio in primo piano**, solo se `isIgnoringBatteryOptimizations` è vero (unica esenzione documentata applicabile) e dopo aver provato che l'avvio riesce. Tipi: upload e sync dell'indice = `dataSync`; calcolo AI e preparazione = `mediaProcessing`. L'app tiene un contatore locale del budget di 6 ore per tipo. In `onTimeout`: salva, `stopSelf()` subito, aggiorna la stima, riprendi la notte dopo (E-P14). Se l'avvio lancia `ForegroundServiceStartNotAllowedException`: cattura, torna alle tappe, scrivi in Diagnostica "Servizio lungo non consentito: impostare la batteria su Illimitata". Mai ritentare in ciclo.
3. **Mai servizi in primo piano da BOOT_COMPLETED o LOCKED_BOOT_COMPLETED.** Dopo il riavvio basta che WorkManager ripianifichi i job (lo fa da solo).
4. **"Fai ora" = UIDT** (Android 14+), schedulato con l'app visibile. Notifica con progresso e azione "Ferma" che salva il checkpoint. Stima dei byte fornita e aggiornata. Sui dati mobili si chiede conferma prima di schedulare (proposta 10). Se l'utente ferma dal Task Manager il processo muore senza callback: la ripresa dal checkpoint non è un errore. Per Android 10-13 (minSdk 29) il ripiego è WorkManager con servizio `dataSync` ad app visibile; sui due dispositivi target (Android 16) non serve.
5. **Rilevare i nuovi media**: `ContentObserver` mentre il processo vive; job con trigger di contenuto su immagini e video quando non vive; ritardo di accorpamento (`setTriggerContentUpdateDelay`) per non scattare a ogni foto di una raffica. A ogni risveglio, scansione incrementale per generazione (tema 4). Niente polling.
6. **Bucket**: leggere `getAppStandbyBucket` all'avvio e a ogni tappa, mostrarlo in Diagnostica, non tentare di manipolarlo [S11]. Se è Restricted: avviso con guida. Un widget attivo evita Restricted [S11] (Fase 10), ma non è una ragione per anticiparlo.
7. **Motivi di attesa leggibili** (2.7): registrare in locale l'ultimo `WorkInfo.getStopReason()` e, su Android 16, `JobScheduler.getPendingJobReasonsHistory()` [S7]; su Android 17 anche `JobDebugInfo.getPendingJobReasonStats()`, che dà la durata cumulata per motivo [S50] (S25: flag attivo).
8. **Salvaguardie**: tappe corte, sempre chiuse con esito esplicito; se compaiono timeout ripetuti, ridurre automaticamente la dimensione del lotto.
9. **Un solo servizio in primo piano per tipo alla volta** (il budget è condiviso).
10. **Vincolo di rete di WorkManager**: `UNMETERED` riguarda la rete di sistema, non "Wi-Fi di casa". La scelta casa, VPN e tetti sta nel motore delle regole (tema 7). Usare WorkManager ≥ 2.11.1 per il bug del vincolo di rete su Android 15+ [S16].

### 1.4 Edge case collegati

E-P4, E-P8, E-P10, E-P13, E-P14, E-P15, E-R2, E-R14, E-B1, E-B2, E-A5, E-A6, E-I2.

---

## Tema 2 — Batteria: "in carica", "batteria non scarica", risparmio energetico, termica

### 2.1 Comportamento

**JobScheduler** [S19]. Il "charging" dei job normali è la "stable power": `isBatteryCharging() && isBatteryNotLow()`. Per i job avviati con l'app in primo piano basta l'alimentazione collegata (`isPowerConnected()`). La condizione "batteria non scarica" è tracciata a parte (`CONSTRAINT_BATTERY_NOT_LOW`).

**WorkManager** [S17, S18]. Il tracker interno `BatteryChargingTracker` legge `EXTRA_STATUS` di `ACTION_BATTERY_CHANGED` e considera "in carica" solo `BATTERY_STATUS_CHARGING` o `BATTERY_STATUS_FULL`. Non legge mai `EXTRA_PLUGGED`. Ascolta `ACTION_CHARGING`/`ACTION_DISCHARGING`. Lo `GreedyScheduler` (attivo quando il processo è vivo) usa questo tracker per i lavori con vincoli e, se un vincolo smette di essere soddisfatto mentre il lavoro gira, **lo ferma** (`stopWorkWithReason`) [S18]. WorkManager registra in parallelo anche un job di sistema con lo stesso vincolo (`SystemJobScheduler`), che segue la semantica di JobScheduler [INF: dal modello noto di WorkManager, non letto qui].

**BatteryService** [S20]. `ACTION_POWER_CONNECTED/DISCONNECTED` dipendono dal tipo di alimentazione, non dallo stato. `BatteryManager.isCharging()` delega a `IBatteryStats.isCharging()`; la logica interna non è leggibile da quel file. La documentazione dice "collegato e con potenza sufficiente a far salire il livello, oppure batteria piena" [S20]: la semantica con carica sospesa non è garantita [??].

**Protezione batteria Samsung (One UI 8.0)** [S27]:

- *Base*: la carica si ferma al 100% e riprende a 95%.
- *Adattiva*: per chi ha un orario di sonno regolare; di notte la carica si ferma intorno all'80% e riprende fino al 100% poco prima della sveglia.
- *Massima*: il livello resta tra 80% e 95% (soglia a scelta).

La pagina non dice come appare lo stato di carica durante la pausa. Il caso peggiore per noi è l'*Adattiva*: la pausa all'80% copre proprio la fascia notte [INF].

**Risparmio energetico.** Doze e App Standby sono descritti in [S13]; la pagina non copre il Battery Saver. Su Samsung il "Risparmio energetico" ha le sue regole [??].

**Termica.** L'API ufficiale [S21]: `PowerManager.getCurrentThermalStatus()` e `addThermalStatusListener()` (NONE, LIGHT, MODERATE = "qualche limitazione, nessun impatto significativo"; SEVERE, CRITICAL, EMERGENCY, SHUTDOWN = "limitazione significativa"); `getThermalHeadroom(secondi)` da 0.0 a 1.0 (1.0 = SEVERE), con soglie euristiche 0,85 / 0,95 / 1,0, al massimo una chiamata ogni 10 secondi, `NaN` se non supportata. Su alcuni dispositivi lo stato resta NONE anche sotto carico: va confrontato con l'headroom. Il framework limita anche i job sotto stress termico [S22]; per UIDT è esplicito che lo stato termico può fermarli [S6].

### 2.2 Verificato sul Tab S9

- Impostazioni Samsung [TAB]: `protect_battery=1`, `battery_protection_threshold=90`, `battery_protection_recharge_level=95`.
- Storico dei broadcast `ACTION_BATTERY_CHANGED` letto da `dumpsys battery` [TAB]: il livello sale da 81% a 89% con `status:2` (CHARGING) e `ac:true`; a 90% passa a **`status:4` (NOT_CHARGING) con `ac:true`**. Dopo un cambio di cavo si vede `ac:false, usb:true, status:4` (e per un istante `status:3`). Il dispositivo è sempre alimentato.
- `dumpsys battery` [TAB]: `USB powered: true`, `status: 4`, `level: 90`.
- JobScheduler [TAB]: "Power connected: true / Battery charging: true / **Considered charging: true** / Battery level: 90 / Battery not low: true". I job con vincolo CHARGING risultano "Satisfied constraints: CHARGING".
- `dumpsys power` [TAB]: `mIsPowered=true`, `mPlugType=2`, "Battery Saver is currently: OFF". `settings get global low_power` = 0. `low_power_back_data_off=1`.
- Doze [TAB]: `mCharging=true`, stato ACTIVE. La cronologia mostra i rientri a "normal" con motivo "(charging)".
- Termica [TAB]: stato 0, sensori SKIN circa 30 °C, BAT circa 29 °C, HAL pronto. JobScheduler riporta "Thermal status: 0".

### 2.2 bis Verificato sull'S25 (Android 17)

- **Protezione batteria** [A17]: `protect_battery=1`, `battery_protection_threshold=80`, `battery_protection_recharge_level=95`. Sul Tab S9 la soglia era 90: le due soglie vanno entrambe nelle prove (T-01).
- **Storico di `ACTION_BATTERY_CHANGED`** [A17]: il livello sale da 77% a 79% con `status:2` e `ac:true`; a **80% passa a `status:4` (NOT_CHARGING) con `ac:true`** e ci resta. Durante un cambio di cavo si vede per circa 5 secondi `ac:false, usb:false, status:3` (alimentazione assente), poi `usb:true` con lo stato che alterna 4 e 2 a ridosso della soglia (79-80%). Il distacco apparente dura pochi secondi: giustifica la tolleranza prevista in 2.3, punto 2.
- **JobScheduler** [A17]: "Power connected: true / Battery charging: true / **Considered charging: true** / Battery level: 79-80 / Battery not low: true" anche con stato NOT_CHARGING. `deviceidle`: `mCharging=true`, stato ACTIVE, rientro a "normal" con motivo "(charging)".
- **`dumpsys battery`** [A17]: `USB powered: true`, `status: 4`, `level: 80`, "Charging state: 0", "Charging policy: 0". La proprietà standard "charging policy" (API 34) resta 0 con la protezione attiva, come sul Tab S9: l'API standard non sembra riflettere la Protezione Samsung [INF].
- **Risparmio energetico** [A17]: Battery Saver OFF, `low_power=0`, `low_power_back_data_off=1`.
- **Termica** [A17]: stato 0, AP circa 56 °C, pelle circa 38 °C, batteria circa 33 °C, HAL pronto.
- **Stampa secondaria su One UI 9** [S54]: tre livelli di protezione, con *Massima* a scelta tra 80, 85, 90 e 95%. Coerente con le due soglie viste (S25 80%, tablet 90%). Nessuna pagina ufficiale Samsung per One UI 9 trovata.


### 2.3 Strategia per l'app

1. **Definizione unica**: "in carica" = alimentazione collegata, letta da `EXTRA_PLUGGED != 0` del broadcast sticky di batteria (AC, USB, wireless, dock). Mai `BatteryManager.isCharging()` né `EXTRA_STATUS` per decidere. Nella UI, con alimentazione collegata e stato NOT_CHARGING: "Collegato — carica sospesa dalla Protezione batteria (X%)". Mai "scarica" (proposta 4 e E-P16).
2. **Vincolo di carica**: non affidare il lavoro notturno al solo `setRequiresCharging(true)` di WorkManager finché T-01 non ha chiarito il comportamento dello `GreedyScheduler` con stato NOT_CHARGING. Opzione consigliata, da confermare: un job nativo di JobScheduler (carica, rete non a consumo, persistito) fa da "sveglia" e avvia la catena di tappe WorkManager *senza* vincolo di carica; durante l'esecuzione il motore delle regole controlla `EXTRA_PLUGGED` e ascolta `ACTION_POWER_DISCONNECTED` (registrato dinamicamente) per fermarsi entro pochi secondi, con una breve tolleranza contro i falsi distacchi. Alternativa se T-01 è pulito: tenere il vincolo WorkManager.
3. **Soglia di batteria**: la soglia dell'app (20%, modificabile) è più severa del "batteria non scarica" di sistema. Si usa quella dell'app; `setRequiresBatteryNotLow(true)` resta come rete di sicurezza. Il valore di sistema (tipicamente 15% in AOSP) non è stato letto sul Tab S9 [??].
4. **Risparmio energetico**: leggere `PowerManager.isPowerSaveMode()` e `ACTION_POWER_SAVE_MODE_CHANGED`. Attivo = attività in background in pausa, uso interattivo normale (E-P10); motivo scritto in Diagnostica. Se T-22 mostra che il Risparmio energetico Samsung resta attivo anche in carica, il preset Notte dovrà dirlo all'utente.
5. **Termica**: ascoltare `addThermalStatusListener`. Proposta: a LIGHT/MODERATE ridurre il ritmo del calcolo AI (pause tra i lotti); a SEVERE o headroom ≥ 0,95 sospendere; riprendere dopo alcuni minuti sotto LIGHT (isteresi). `getThermalHeadroom` al massimo ogni 10 s; se restituisce `NaN` usare solo lo stato. Il carico sulla NPU potrebbe non riflettersi nello stato termico [INF]: T-21.
6. **Registrare l'evento "carica sospesa"** (livello, ora) per l'App: serve alle stime (4.6) e alla Diagnostica. Su un telefono con Protezione *Adattiva* la fascia notte potrebbe avere molte ore in questo stato.

### 2.4 Edge case collegati

E-P10, E-P16, E-A5, E-A6, E-R12 (la regola segue la rete effettiva, non quella del lavoro precedente).

---

## Tema 3 — Samsung: sospensione, permessi ritirati, riavvii, Direct Boot

### 3.1 Comportamento

**App in sospensione, sospensione profonda, Mai in sospensione** [S26, ufficiale Samsung]:

- *App in sospensione*: app in background non usate da circa 3 giorni e che pesano sulla salute del sistema. Vanno in un bucket ristretto che limita job, allarmi e servizi in primo piano.
- *Sospensione profonda*: app non usate per "attualmente 16 giorni, valore soggetto a cambiamenti secondo le policy Samsung". Restano inattive fino a quando l'utente le apre; non fanno nulla, nemmeno notifiche o aggiornamenti.
- *Mai in sospensione*: elenco di esenzione dell'utente (Impostazioni → Batteria → Limiti di utilizzo in background). Gli sviluppatori possono aprire la schermata con l'azione `com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY` (pacchetto `com.samsung.android.lool`, `activity_type` 0 = sospensione, 1 = sospensione profonda, 2 = mai in sospensione). È un collegamento alla schermata, non un modo per esentare l'app da codice [S26].
- Dal One UI 6 (Android 14) Samsung garantisce che i servizi in primo piano di app con target 14 funzionino "come previsto" se rispettano la policy Android [S26].
- Profili batteria per app: *Unrestricted*, *Optimized*, *Restricted* (Impostazioni → App → app → Batteria); le etichette italiane vanno rilevate sul dispositivo [S29, fonte secondaria]. Il "Metti in sospensione le app inutilizzate" e l'"Adaptive battery" sono descritti da fonti non ufficiali [S30]: non confermati da Samsung.

**Bucket e permessi di Android** [S11, S25]:

- *Hibernation* (target ≥ 30): dopo "alcuni mesi" senza interazione il sistema ritira i permessi sensibili concessi, impedisce job e avvisi in background, rimuove la cache. Esce dallo stato quando l'utente interagisce di nuovo, **ma non ripristina i permessi né i job pianificati**. Contano come uso: aprire l'app, interagire con widget o notifica (scartarla non conta). Non contano: job, broadcast implicite, allarmi. L'utente può disattivarla per app (Android 13+: "Pausa attività app se non utilizzata", etichetta italiana da rilevare) [S25].
- API: `PackageManagerCompat.getUnusedAppRestrictionsStatus()` per sapere se l'app è soggetta; `IntentCompat.createManageUnusedAppRestrictionsIntent()` per portare l'utente alla schermata [S25].

**Riavvii**:

- *Riavvio automatico* (Samsung: Impostazioni → Device care → Auto optimization → Auto restart; etichette italiane da rilevare): "Riavvia se necessario" e "Riavvia secondo programma". Parte solo a dispositivo non in uso: schermo spento e, per impostazione predefinita, batteria ≥ 30%. Può partire anche con entrambe le opzioni spente [S28].
- *Riavvio per inattività* (One UI 8.0+): riavvia un dispositivo bloccato e inutilizzato dopo **72 ore**, riportandolo da AFU a BFU. **Attivo di default**; si spegne in Impostazioni → Sicurezza e privacy → Altre impostazioni di sicurezza [S28]. Stampa e fonti secondarie riportano una funzione analoga di Google Play Services dalla v25.14 (3 giorni consecutivi bloccato) [S31]. Il conto parte dal blocco e si azzera a ogni sblocco (SamMobile, secondaria).
- Gli aggiornamenti di sicurezza urgenti possono riavviare anche con aggiornamenti automatici spenti [S28].

**Direct Boot** [S23]. Dopo un riavvio, finché l'utente non sblocca per la prima volta, è disponibile solo lo storage protetto del dispositivo (DE). Lo storage predefinito dell'app (CE) resta chiuso fino al primo sblocco. Le app non girano in Direct Boot a meno di componenti `directBootAware`. `ACTION_LOCKED_BOOT_COMPLETED` arriva prima dello sblocco; `ACTION_BOOT_COMPLETED` dopo. Lo storage condiviso (`/data/media/<utente>`, quindi MediaStore) è anch'esso CE: nel documento Direct Boot non è citato [INF, confidenza alta]. WorkManager dichiara i suoi componenti non direct-boot-aware perché il suo database sta nello storage cifrato; questo risulta da un thread dell'issue tracker (112665532, stato "Fixed") che non è leggibile direttamente e che conosco solo da una sintesi di ricerca [S24, ??].

### 3.2 Verificato sul Tab S9

- **Hibernation** [TAB]: `app_hibernation_enabled=true`, soglia `auto_revoke_unused_threshold_millis2=7776000000` (90 giorni), frequenza controllo `1296000000` (15 giorni). `app_standby_enabled=1`.
- **Ottimizzazione batteria** [TAB]: la lista "Whitelist user apps" di Doze contiene 4 pacchetti (tutti Samsung). L'intento `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` si risolve in `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`. Se questa lista coincida con "Illimitata" di One UI non è stato verificato [??].
- **Deep link Samsung** [TAB]: `com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY` si risolve in `com.samsung.android.lool/.sm.battery.ui.usage.CheckableAppListActivity`.
- **Bucket reali** [TAB]: Samsung Gallery RARE (assegnato per "predizione"), OneDrive RESTRICTED (ultimo uso novembre 2025, `RUN_ANY_IN_BACKGROUND: ignore`), Google Foto WORKING_SET. Mostra quanto in fretta un'app usata di rado scenda di bucket anche per una galleria di sistema.
- **Cifratura** [TAB]: `ro.crypto.state=encrypted`, `ro.crypto.type=file` (FBE). `mDelayUserDataLocking:false`. `boot_count` = 44, uptime 2 giorni e 23 ore.
- **Non leggibile da adb** [TAB]: lo stato di Riavvio automatico e di Riavvio per inattività, le liste Sleeping/Deep sleeping/Mai in sospensione e l'impostazione "Metti in sospensione le app inutilizzate" non compaiono in `settings`, `device_config` né nei dump: sono gestiti dall'app Device Care di Samsung.

### 3.2 bis Verificato sull'S25 (Android 17)

- **Hibernation** [A17]: `app_hibernation_enabled=true`, soglia 7776000000 ms (90 giorni), controllo ogni 1296000000 ms (15 giorni): identici al Tab S9. `cmd app_hibernation get-state` per OneDrive (ultimo uso 17/07/2026, cioè 87 giorni fa) restituisce false: non ancora in hibernation. **Osservazione gratuita**: OneDrive supera i 90 giorni il 15/10/2026; rileggere lo stato dopo il prossimo controllo (ogni 15 giorni) mostra che cosa fa il sistema a un'app inutilizzata.
- **Riavvio per inattività** [A17]: `Settings.Secure inactivity_restart=1` (acceso). La chiave non esiste sul Tab S9 (One UI 8.5) [TAB]. Uptime 5 giorni e 10 ore, `boot_count` 33: nessun riavvio forzato in 5 giorni di uso normale. Il Riavvio automatico non ha chiavi leggibili.
- **Doze e ottimizzazione batteria** [A17]: la lista "Whitelist user apps" ha 15 voci (sul Tab S9 erano 4); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` si risolve in `RequestIgnoreBatteryOptimizations`; il deep link Samsung `ACTION_OPEN_CHECKABLE_LISTACTIVITY` si risolve in `CheckableAppListActivity`; `ACTION_REQUEST_MANAGE_MEDIA` in `AppMediaManagementAppsActivity`. `android.settings.action.MANAGE_UNUSED_APPS` non ha gestori.
- **Cifratura** [A17]: per file (FBE). `mDelayUserDataLocking:false`.
- Le liste Sleeping/Deep sleeping/Mai in sospensione e il Riavvio automatico non sono leggibili da adb, come sul tablet.


### 3.3 Strategia per l'app

1. **L'onboarding passo 10 diventa "Salute del background"**, con **tre** azioni distinte e ciascuna con verifica dove possibile:
   - *Batteria: Illimitata* (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, risolvibile). Verifica: `isIgnoringBatteryOptimizations`. È anche l'esenzione per i servizi lunghi (tema 1).
   - *Mai in sospensione* (deep link `activity_type=2`). Verifica diretta impossibile: proxy = bucket diverso da RESTRICTED + battito recente.
   - *Pausa attività se inutilizzata: spenta* (`createManageUnusedAppRestrictionsIntent`). Verifica: `getUnusedAppRestrictionsStatus`.
2. **Battito locale**: per ogni attività, ora dell'ultimo successo e dell'ultimo tentativo. Con attività ferma da N giorni (4.6, E-P15) la Diagnostica incrocia battito, bucket, `isIgnoringBatteryOptimizations`, stato hibernation, permessi `READ_MEDIA_*` e dice quale passo manca.
3. **A ogni avvio con interfaccia**: ricontrollare permessi, ripianificare i job (dopo l'hibernation non sono ripristinati [S25]), leggere il bucket.
4. **Permessi ritirati**: lo stato dei permessi non si memorizza, si legge ogni volta [S33]. Se `READ_MEDIA_*` risultano revocati: backup e indicizzazione in pausa con motivo "Permessi ritirati dal sistema", mai interpretati come cancellazioni (E-P1).
5. **Riavvio e BFU**: non serve alcun componente direct-boot (il backup richiede storage CE). Utile solo, se T-08 lo conferma, un ricevitore `LOCKED_BOOT_COMPLETED` che scriva in storage DE "riavviato alle HH:MM" per spiegare poi il ritardo (E-P13). Al primo sblocco: `ACTION_USER_UNLOCKED`/`BOOT_COMPLETED` e ripresa. Diagnostica: stato "In attesa di sblocco" con ora dell'ultimo riavvio (`Settings.Global.BOOT_COUNT`, `UserManager.isUserUnlocked`).
6. **Guida all'utente per il tablet condiviso**: sbloccarlo almeno una volta ogni 3 giorni; impostare l'orario del Riavvio automatico fuori dalla fascia notte oppure spegnerlo. Non si consiglia di disattivare il Riavvio per inattività (è una difesa di sicurezza voluta): l'app lo spiega, non lo chiede.
7. **Stime**: includere i giorni di BFU nelle statistiche di consumo e nei tempi di completamento.
8. **Non leggere le impostazioni Samsung**: sull'S25 il Riavvio per inattività è visibile come `inactivity_restart` in `Settings.Secure`, ma è una chiave non documentata e assente sul Tab S9: l'app non deve dipenderne. I riavvii si deducono da `BOOT_COUNT` e dall'uptime.

### 3.4 Edge case collegati

E-P2, E-P4, E-P13, E-P15, E-O4, E-S9 (la chiave Keystore delle credenziali resta usabile in background, ma i dati cifrati stanno in CE).

---

## Tema 4 — MediaStore

### 4.1 Comportamento

**Stabilità degli `_ID`.**

- La documentazione non promette che `_ID` e URI sopravvivano a una nuova scansione: dice solo che `getVersion()` "cambia quando il MediaStore cambia in modo sostanziale", che va letta all'avvio del processo e che, se cambia, "si deve rifare la scansione e la sincronizzazione" [S32]. Non ci si deve appoggiare a dettagli del formato della versione.
- Android 16, app con target 36: `getVersion()` restituisce un valore **diverso per ogni app** [S8].
- `getGeneration(volume)` cresce in modo monotono finché la versione non cambia; è più robusto di `DATE_ADDED`/`DATE_MODIFIED`. Prima di fidarsene si controlla la versione e, se è cambiata, si fa una sincronizzazione completa [S32].
- Il MediaProvider ha una funzione "URI stabili" [S37, S38]: la classe `DatabaseBackupAndRecovery` copia `_ID`, percorso, `IS_FAVORITE`, `IS_PENDING`, `IS_TRASHED`, tipo, `DATE_EXPIRES`, proprietario e `GENERATION_MODIFIED` in un LevelDB in `/data/media/<utente>/.transforms/recovery` e li ripristina se il database viene perso o riportato indietro. È *best-effort* (righe "dirty" saltate, inserimenti che possono fallire). In `DatabaseHelper` il contatore delle nuove righe riparte da `NEXT_ROW_ID_DEFAULT_BILLION_VALUE = 10^9` e viene salvato in un attributo esteso per non riusare gli ID.
- Cosa NON copre: un altro dispositivo. Dopo Smart Switch o un ripristino da cloud i file hanno nuovi `_ID` [INF].
- `MediaStore.DeletedFiles` (tabella "deleted_files", estensione SDK S ≥ 23): per ogni file eliminato `deleted_generation`, `_id`, `media_type`, `volume_name` [S39, TAB]. Serve a scoprire le cancellazioni senza dover confrontare tutto. Non dice *perché* è sparito il file né per quanto tempo conserva lo storico [??].

**Colonne di stato.**

- `IS_PENDING` (Android 10+): finché è 1, il file è visibile solo a chi lo sta scrivendo [S32]. Per noi: un file con `IS_PENDING=1` creato da un'altra app (fotocamera, Quick Share) non è ancora un media da salvare.
- `IS_TRASHED`/`DATE_EXPIRES` e `createTrashRequest()` (Android 11+): il file va nel cestino di sistema "per un periodo definito dal sistema" [S32]; il valore predefinito AOSP è 30 giorni [INF, non letto qui]. Le app che non sono la galleria preinstallata dell'OEM devono mostrare una conferma di sistema per ogni richiesta di modifica o cancellazione; la galleria preinstallata può impostare `IS_TRASHED=1` direttamente [S32].
- `createDeleteRequest()`: cancellazione immediata, senza cestino. `createWriteRequest()`: modifica del file. Entrambe con dialogo di sistema [S32].
- `MANAGE_MEDIA` (Android 12+, "Gestione file multimediali" in Impostazioni → Accesso speciale): toglie il dialogo per scrittura, cestino e cancellazione [S32]. La schermata si risolve sul Tab S9: `android.settings.REQUEST_MANAGE_MEDIA` → `Settings$AppMediaManagementAppsActivity` [TAB].
- Per leggere le righe cestinate serve `QUERY_ARG_MATCH_TRASHED` (non coperto dalla pagina letta) [??].

**Cestino di Samsung Gallery.**

- [TAB] Esiste `Android/.Trash/com.sec.android.gallery3d/`, con un file `.nomedia` e una sottocartella (vuota al momento della lettura). Accanto c'è `Android/.Trash/com.sec.android.app.myfiles/` (cestino di I miei file). Nel MediaStore le 22 righe sotto `Android/.Trash/` hanno tutte `media_type=0` (non media) e `is_trashed=0`; non esiste alcun file `.trashed-*` sul volume.
- Conclusione [INF, alta confidenza]: la Galleria Samsung sposta i file in una cartella propria, esclusa dall'indicizzazione grazie a `.nomedia`. Per il MediaStore la foto *sparisce* (riga rimossa) e non risulta "cestinata". Samsung riporta 30 o 60 giorni di conservazione a seconda di modello e versione, e dice che il cestino va attivato in anticipo (pagine di supporto, lette solo come sintesi di ricerca).
- Non osservato in azione: se la riga viene cancellata o aggiornata, se il ripristino riusa l'`_ID`, e se la Galleria mostra nel suo cestino i file cestinati via `createTrashRequest` [??]: T-09.
- **Prove sul campo, blocco A (S25, One UI 9, 20/10/2026; `docs/prove-campo-esiti.md` D-3).** Osservato: `createTrashRequest` confermato **non** usa il cestino di sistema. Le 5 foto di prova escono da `DCIM/mcg-prova/` (nessun `.trashed-…`), la prova non le ritrova con `MATCH_TRASHED = INCLUDE` filtrando per `RELATIVE_PATH`, e il Cestino di Galleria le mostra (ripristinate da lì dall'utente). Dopo il ripristino da Galleria le 5 righe sono di nuovo in `DCIM/mcg-prova/` con **lo stesso `_ID`** e la stessa `generation_added` della semina, `is_trashed=0`, `generation_modified` salita; nel MediaStore nessuna riga ha `is_trashed=1` e sotto `Android/.Trash/` ci sono solo le righe delle cartelle (`media_type=0`). Il fornitore `com.samsung.android.providers.trash` tiene i suoi metadati (log "local meta: `_ID`, generazione") e al ripristino cancella le sue voci. Dedotto (non osservato durante il cestino): la riga resta con lo stesso `_ID` sotto il Cestino di Galleria, fuori da ogni query dell'app; per l'app e' "sparita" (riconciliazione, niente in `deleted_files`).
- Cosa fa l'app (D-3): "Libera spazio" prende per buono l'esito del dialogo (nessuna rilettura delle righe); le schermate su Samsung dicono "Cestino di Galleria" e "si ripristina da Galleria → Cestino" (`PhoneTrash`, `LocalPhoneTrash`), mai "Eliminati di recente"; "Apri il cestino di Galleria" apre Galleria; un file ripristinato torna al suo media dalle impronte (E-P11), stesso `_ID` o nuovo, senza ricaricarlo. La prova T-09 misura dove finiscono le righe (`t09.trash_out_of_folders`, `t09.trash_in_gallery_trash`, `t09.trash_gone`) e "Ripristina quelle foto" lo dice invece di "nessuna foto adatta" (`t09.untrash_in_gallery`, `t09.untrash_back_same_id`).

**Accesso parziale (Android 14+, target ≥ 34)** [S33]:

- Si dichiarano `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` e `READ_MEDIA_VISUAL_USER_SELECTED` e si richiedono insieme; l'utente può concedere "foto selezionate". Le query restituiscono solo la selezione. `QUERY_ARG_LATEST_SELECTION_ONLY` limita all'ultima selezione.
- Lo stato dei permessi "può cambiare in qualsiasi momento" (reset dei permessi, hibernation, impostazioni, app in background): non va memorizzato, ma riletto con `checkSelfPermission` e riletto in `onResume`. La nuova richiesta deve partire da un elemento UI toccato dall'utente.
- Se l'app non dichiara il nuovo permesso, il sistema usa una modalità di compatibilità in cui l'accesso è temporaneo (durata della sessione dell'app) [S33].
- Android 16, target 36: le foto che l'app possiede compaiono già selezionate nel selettore e deselezionarle revoca l'accesso [S8, S32].
- [TAB] WhatsApp e Telegram dichiarano `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` e `READ_MEDIA_VISUAL_USER_SELECTED` (e WhatsApp `ACCESS_MEDIA_LOCATION`), tutti concessi: usano un selettore proprio sul MediaStore.

**`ACCESS_MEDIA_LOCATION`** [S32]: serve per leggere i metadati EXIF *non oscurati* delle foto. Sotto scoped storage la posizione è nascosta di default; si usa `MediaStore.setRequireOriginal(uri)` e il permesso. Nessuna garanzia senza consenso esplicito. I video non hanno questa restrizione (`MediaMetadataRetriever`). Conseguenza non scritta dalla documentazione ma ovvia dalla definizione di "oscurato" [INF]: i byte letti da un'app senza il permesso hanno il GPS cancellato, quindi *la copia sul NAS e il suo hash differirebbero dall'originale*. Da misurare (T-11).

**Sincronizzazioni di Samsung.** La sincronizzazione della Galleria passa da OneDrive; l'opzione "Libera spazio sul telefono" elimina localmente i file già caricati [S41]. Per l'app è una cancellazione senza intervento dell'utente. Sul Tab S9 sono installati OneDrive 7.63, Samsung Cloud 5.7.07.9 e la cartella `smartswitch` nei dati della Galleria [TAB].

### 4.2 Verificato sul Tab S9

- **Numeri** [TAB]: 834 immagini e 56 video nel MediaStore. **Tutti** gli `_ID` sono ≥ 1.000.000.000 (minimo 1000000353, massimo 1000024239). 557 delle 834 immagini stanno in cartelle `Android/media/<pacchetto>/…` (media di altre app, tra cui la messaggistica), 173 in `DCIM/Screenshots`, 11 in `DCIM/Camera`.
- **Colonne** [TAB]: `generation_added`, `generation_modified`, `is_pending`, `is_trashed`, `is_favorite`, `owner_package_name`, `volume_name` esistono e sono valorizzate.
- **Tabella `deleted_files`** [TAB]: `content://media/external_primary/deleted_files` risponde con 2.084 righe, `deleted_generation` da 132703 a 155824 (la generazione corrente è circa 155800). Per media type: 1.824 "none" (cartelle e simili), 242 immagini, 11 video, 5 audio, 2 documenti. Lo storico conservato copre quindi decine di migliaia di generazioni, ma senza garanzia di durata.
- **Estensioni** [TAB]: `build.version.extensions.s/t/u/v = 23`; `enable_deleted_files_api=true`, `enable_stable_uris_for_external_primary_volume=true`, `enable_generation_number_recovery=true`, `enable_trash_and_restore_by_file_path_api=true`, `enable_backup_and_restore=true`.
- **Cestino Samsung** [TAB]: struttura descritta in 4.1.
- **Ruolo "gestione media"** [TAB]: la schermata esiste e si risolve.

### 4.2 bis Verificato sull'S25 (Android 17)

- **Numeri** [A17]: 6.274 immagini e 384 video nel MediaStore. **Tutti gli `_ID` sono < 1.000.000.000** (da 1506 a 69450): numerazione storica, contrariamente al Tab S9 (tutti ≥ 1.000.000.000). I flag degli URI stabili (`enable_stable_uris_for_external_primary_volume`, `..._for_public_volume`, `enable_generation_number_recovery`, `enable_backup_and_restore`) sono attivi anche qui. Quindi l'attivazione della funzione non rinumera gli ID esistenti; la numerazione "a miliardo" compare quando il database viene ricreato [INF]. Il passaggio di versione a Android 17 non ha ricreato il database dell'S25, a differenza di quanto è evidentemente successo sul tablet in passato.
- **Dove stanno** [A17]: 2.105 immagini in cartelle `Android/media/<pacchetto>/…` (media di altre app, tra cui la messaggistica), 1.088 in una sottocartella di `DCIM`, 915 in `DCIM/Screenshots`, 866 in `DCIM/Camera`, 654 in `Download/Quick Share`, 224 in `Pictures`.
- **Tabella `deleted_files`** [A17]: 2.880 righe con `deleted_generation` da 353168 a 384281 (generazione corrente circa 384271); per tipo: 2.163 "none" (cartelle e simili), 438 immagini, 244 video, 18 audio, 17 documenti. Quindi lo storico copre decine di migliaia di generazioni, senza garanzie di durata.
- **Colonne** [A17]: `generation_added`, `generation_modified`, `is_pending`, `is_trashed`, `is_favorite`, `volume_name` presenti e valorizzate. SDK extension S/T/U/V/B/C = 23.
- **Cestino Samsung** [A17]: `Android/.Trash/` contiene `com.sec.android.gallery3d` (al momento solo il file `.nomedia`, cestino vuoto), `com.sec.android.app.myfiles` e una cartella nuova rispetto al tablet, `com.samsung.android.agent.storage`. Le righe MediaStore sotto `Android/.Trash/` sono 4, tutte `media_type=0` e `is_trashed=0`; nessun file `.trashed-*`. La cartella dati della Galleria ha `.album`, `.clippedImages`, `.share` (nuova), `smartswitch`, `tmp`. Stessa struttura del Tab S9: conferma che il cestino Samsung è una cartella propria.
- **Versioni**: Samsung Gallery 15.9.01.7, MediaProvider "17", provider Samsung 17.0.12.0, OneDrive 7.61 (Beta 1), Google Foto 7.95.0.
- **Permessi** [A17]: WhatsApp e Telegram hanno `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` e `READ_MEDIA_VISUAL_USER_SELECTED` concessi e `ACCESS_MEDIA_LOCATION` **non concesso**; sul Tab S9 WhatsApp lo aveva concesso.


### 4.3 Strategia per l'app

1. **Identità**: l'identità del media nasce dalle impronte del piano (1.3): dimensione, hash rapido, hash completo, più data di scatto EXIF e (volume, percorso relativo, nome) come indizi. L'`_ID` (con volume e generazione) è solo una chiave di cache per ritrovare in fretta la riga. Mai un riferimento di album, preferito o vettore costruito sull'`_ID`, e nessuna ipotesi sul suo intervallo (Tab S9 ≥ 1.000.000.000, S25 < 70.000): come cursore si usa la generazione, mai "l'ID più alto visto".
2. **Versione**: all'avvio di ogni processo (non a ogni query) leggere `getVersion()`; se diversa da quella salvata: rifare l'enumerazione completa, riassociare per impronta e *non* marcare come assente nulla fino al termine di una scansione completa. Il valore è proprio dell'app (target 36): si confronta solo con ciò che l'app ha salvato.
3. **Sincronizzazione incrementale**: confronto con `generation_modified` e `getGeneration(volume)` memorizzati, per volume (la microSD ha il suo, E-P6). Cancellazioni: dove l'estensione S ≥ 23 c'è, leggere `DeletedFiles` dalla generazione salvata; altrimenti confronto dell'insieme degli ID una volta al giorno. In ogni caso `DeletedFiles` si usa come *accelerazione*, non come verità: mai cancellare o dare per perso solo per questo.
4. **Stati prima di "persa"**. Una riga sparita non è una cancellazione dell'utente (cestino Samsung, "Libera spazio" di OneDrive, accesso parziale, permessi ritirati, volume smontato). Regola: l'item passa a "Assente dal telefono" senza toccare il NAS; la ricomparsa di una riga con la stessa impronta lo ricollega (E-P11, E-L2). Una foto non ancora al sicuro che sparisce riceve il messaggio "Recuperala dal cestino di Galleria" (E-B4) e una finestra di tolleranza di almeno 60 giorni (il massimo riportato da Samsung). Il sistema non offre alcun modo di leggere il cestino di Samsung: non se ne cerca uno (richiederebbe l'accesso a tutti i file e dipende da un dettaglio interno).
5. **Accesso parziale = modalità limitata dichiarata** (E-P1). Backup e indicizzazione sono completi solo con "Consenti tutto". In modalità parziale: nessuna deduzione di cancellazione dall'assenza, messaggio chiaro e pulsante di richiesta avviato dall'utente. Il lavoro in background deve rileggere il permesso a ogni esecuzione.
6. **Posizione e originali**: richiedere `ACCESS_MEDIA_LOCATION` insieme ai permessi media (onboarding passo 9) e leggere gli originali con `setRequireOriginal`. Se negata: avviso "Le copie sul NAS non conterranno la posizione" e marcatura dell'item (verrà riletto se il permesso arriva). L'hash di riferimento è quello dei byte originali: il confronto "identico byte per byte" (proposta 5) vale solo con lettura non oscurata.
7. **Scrittura e cancellazione dal telefono** (5.4, 5.5): usare `createTrashRequest` (cestino) e `createWriteRequest` con il dialogo di sistema; una richiesta in blocco ha una sola conferma. Non chiedere `MANAGE_MEDIA` all'inizio (minimo privilegio, il dialogo è una seconda conferma gratuita); riconsiderare solo se T-09 mostra limiti sul numero di URI per richiesta. "Libera subito" usa `createDeleteRequest`.
8. **Nuovi file da altre app**: ignorare `IS_PENDING=1`; trattare `Android/media/<pacchetto>/` come classe "media di app" (messaggistica) con filtro rapido; Secure Folder e Spazio privato sono profili/utenti diversi e restano invisibili (E-P7, dichiarato).
9. **Date e fusi**: EXIF con fuso quando c'è, altrimenti nome del file, altrimenti `DATE_TAKEN`/data file marcata "stimata" (E-M8, E-M14, E-P5). L'orologio di riferimento è quello del NAS (E-P9).

### 4.4 Edge case collegati

E-P1, E-P5, E-P6, E-P7, E-P9, E-P11, E-P12, E-P17, E-B4, E-B13, E-D7, E-E2, E-L2, E-M8, E-M14.

---

## Tema 5 — Integrazioni con le altre app

### 5.1 Comportamento

**Photo Picker e Cloud Media Provider.**

- Il Photo Picker di sistema è disponibile dove il dispositivo riceve gli aggiornamenti Mainline (Android 11+) e mostra la libreria locale; sui dispositivi senza picker, `PickVisualMedia` ripiega su `ACTION_OPEN_DOCUMENT` [S34].
- **Cloud Media Provider**: "è in corso un programma pilota per consentire ad app *nominate dagli OEM* di diventare cloud media provider; sono idonee solo quelle nominate; ogni OEM ne può proporre fino a 3"; l'elenco idoneo è lato server, valido sui dispositivi GMS; può esserci un solo provider attivo per profilo [S35]. **Un'app qualunque non può registrarsi.**
- Il takeover di `ACTION_GET_CONTENT` da parte del picker (per i tipi `image/*` e `video/*`) era un rollout graduale controllato da un flag (`take_over_get_content`); per il caso con cloud il post parla di supporto "senza modifiche per gli sviluppatori" [S36]. La pagina non copre il pulsante "Sfoglia" né `ACTION_OPEN_DOCUMENT`.

**Condivisioni.**

- `ACTION_SEND` e `ACTION_SEND_MULTIPLE`: Android 17 annuncia che dalla versione 18 il sistema smetterà di concedere automaticamente i permessi di lettura sugli URI implicitamente condivisi; i *mittenti* dovranno aggiungere `FLAG_GRANT_READ_URI_PERMISSION` [S9]. Per noi vale come mittente (Condividi dal viewer) e come ricevente (non basarsi su concessioni implicite durature).
- Il permesso di lettura concesso da una condivisione in arrivo dura finché vive il task/attività ricevente [INF].

**Modifica.** Samsung Gallery: il "Salva" predefinito sovrascrive il file e l'originale viene nascosto (conservato altrove) per consentire "Ripristina originale"; il menu offre "Salva come copia" [S40 + forum Samsung Community, non ufficiali]. La pagina ufficiale descrive solo i passi per ripristinare l'originale.

### 5.2 Verificato sul Tab S9

Interrogazioni `cmd package query-activities` [TAB]:

| Intento | Gestori trovati |
|---|---|
| `GET_CONTENT` `image/*` | 9: DocumentsUI (trampolino e `PickActivity`), Photopicker del modulo Mainline (2), **I miei file**, **Samsung Gallery** (`GalleryExternalActivity`), OneDrive, Google Drive, Google Foto (`ExternalPickerActivity`) |
| `PICK` `image/*` | Samsung Gallery e Google Foto |
| `OPEN_DOCUMENT` `image/*` | solo DocumentsUI (`PickActivity`) |
| `ACTION_PICK_IMAGES` | solo i due moduli Photopicker |
| `SEND` `image/jpeg` | 40 (condivisione); `SEND_MULTIPLE` `image/*`: 31 |
| `EDIT` `image/*` | OneDrive, Google Foto (con URI `content://`: anche ChatGPT). **Samsung Gallery e Photo Editor Samsung non compaiono** |
| `VIEW` URI MediaStore | Samsung Gallery tra gli altri |

Altre rilevazioni [TAB]:

- `take_over_get_content` (in `storage_native_boot`) = **false**.
- `mediaprovider/allowed_cloud_providers` = solo `com.google.android.apps.photos.cloudpicker`. JobScheduler elenca `Media Cloud Providers: {0=com.google.android.apps.photos}`.
- Samsung Gallery gestisce anche `CROP`, `REVIEW`, `QUICK_VIEW`, `ITEM_PICK`, `TRASH_VIEW` e altre azioni proprie; il pacchetto Photo Editor non ha filtri `EDIT`.
- Permessi dichiarati: WhatsApp e Telegram `READ_MEDIA_*` + `READ_MEDIA_VISUAL_USER_SELECTED` concessi; Gmail solo `READ_EXTERNAL_STORAGE` (non concesso); Google Messages `READ_MEDIA_*` concessi di default. Da qui [INF]: WhatsApp e Telegram mostrano nel proprio selettore il contenuto del MediaStore; Gmail usa il selettore di sistema (SAF).

### 5.2 bis Verificato sull'S25 (Android 17)

Interrogazioni `cmd package query-activities` [A17]:

| Intento | Gestori trovati |
|---|---|
| `GET_CONTENT` `image/*` | 10: gli stessi 9 del Tab S9 (DocumentsUI, Photopicker, I miei file, Samsung Gallery, OneDrive, Google Drive, Google Foto) più un'app di personalizzazione grafica dell'utente che dichiara il filtro |
| `PICK` `image/*` | 3: Samsung Gallery, Google Foto e la stessa app di personalizzazione |
| `OPEN_DOCUMENT` `image/*` | solo DocumentsUI |
| `ACTION_PICK_IMAGES` | solo i due moduli Photopicker |
| `EDIT` `image/*` | Google Foto e OneDrive (con URI `content://`: anche ChatGPT). **Samsung Gallery e Photo Editor Samsung non compaiono** |
| `VIEW` URI MediaStore | 9, tra cui Samsung Gallery e Google Foto |
| `SEND` `image/jpeg` / `SEND_MULTIPLE` `image/*` | 60 / 45 |

- L'ultima riga della tabella mostra che **qualunque app che dichiara il filtro compare** tra i gestori di `GET_CONTENT` e `PICK`: è la via praticabile anche per MyCloud Gallery.
- Samsung Gallery 15.9.01.7 dichiara `GET_CONTENT`, `PICK`, `VIEW`, `com.android.camera.action.CROP` e `REVIEW`, **nessun `EDIT`**. Photo Editor Samsung 3.9.08.16: solo `VIEW`.
- Flag [A17]: `take_over_get_content=false`; `allowed_cloud_providers` = solo `com.google.android.apps.photos.cloudpicker`; JobScheduler: `Media Cloud Providers: {0=com.google.android.apps.photos}`; moduli Photopicker e DocumentsUI alla versione 17.
- Permessi: WhatsApp e Telegram con `READ_MEDIA_*` concessi; Gmail solo `READ_EXTERNAL_STORAGE`, non concesso.


### 5.3 Strategia per l'app

1. **Nessuna promessa sul Photo Picker.** Non esistono vie per comparire nel picker di sistema (Cloud Media Provider chiuso). La Fase 5.7 e le Impostazioni lo dicono: "dove l'app di destinazione usa solo il selettore di sistema, usa Condividi".
2. **Offrirsi come sorgente per i flussi che lo consentono**:
   - un *handler* di `ACTION_GET_CONTENT` e `ACTION_PICK` per `image/*`, `video/*` (come fanno Samsung Gallery, OneDrive e I miei file), con selezione multipla (`EXTRA_ALLOW_MULTIPLE`);
   - un *DocumentsProvider* con radice "MyCloud Gallery" (Recenti, Album, Cartelle NAS): compare nel selettore documenti usato da Gmail e dall'allegato "Documento" di WhatsApp e Telegram.
3. **Media solo sul NAS**: restituire un URI di un nostro provider che apre il file in streaming dal NAS (descrittore di file su richiesta) oppure, se T-14 lo richiede, scaricare prima in cache con progresso e restituire solo a file pronto. Se il NAS non risponde: `RESULT_CANCELED` con messaggio chiaro, mai attesa infinita (E-X1). Annullando la selezione si annulla il download e si pulisce (E-X4).
4. **WhatsApp e Telegram dalla galleria interna**: irraggiungibili. Resta il pulsante *Condividi* nel viewer (`ACTION_SEND`/`SEND_MULTIPLE` con `ClipData` e flag di lettura espliciti, anche per Android 17/18); posizione secondo l'impostazione (E-X5, E-V7).
5. **Ricevere condivisioni**: filtri per `image/*`, `video/*` e, con cautela, `*/*`; leggere `EXTRA_STREAM` e `ClipData`; *aprire i flussi subito* e copiare in un'area privata dell'app prima di caricare (il permesso non è garantito dopo la fine dell'attività); file grandi: avviare un trasferimento mentre l'attività è visibile (UIDT) e tenere il flusso aperto. Limiti di dimensione, tipo verificato dai byte, rifiuto di URI che puntano a file dell'app stessa (E-X2). Valutare le *sharing shortcuts* per "Salva sul NAS" e "Aggiungi ad album" nel foglio di condivisione.
6. **"Modifica con…"**:
   - *Media presente nel MediaStore*: aprire in Samsung Gallery con `ACTION_VIEW` e pacchetto esplicito (sul Tab S9 la Galleria non risponde a `ACTION_EDIT`; sull'S25 da verificare, T-16); l'utente modifica dentro la Galleria. L'esito si legge dal MediaStore: stessa riga con `generation_modified`, dimensione e hash cambiati = "Salva" (nuova versione, originale conservato dal NAS); riga nuova ravvicinata nella stessa cartella con stessa data di scatto = "Salva copia" (E-X3); ritorno all'hash dell'originale = "Ripristina originale" (E-E2).
   - *Media solo sul NAS*: copia in cache servita da un `FileProvider` con permesso di lettura *e scrittura*, `ACTION_EDIT` (compaiono Google Foto e OneDrive). Al ritorno si confrontano i byte: cambiati = nuova versione (E-E3); file nuovo altrove = ricollegamento per impronta o nuovo media.
   - Controllare nei risultati se `ORIGINAL_DOCUMENT_ID`, `DOCUMENT_ID` o `INSTANCE_ID` del MediaStore vengono valorizzati dagli editor [??]: T-16.
7. **Mai** fare affidamento sull'URI restituito da un editor per decidere di sovrascrivere o cancellare: il backup resta di sola aggiunta (principio dei dati).

### 5.4 Edge case collegati

E-X1, E-X2, E-X3, E-X4, E-X5, E-E2, E-E3, E-E7, E-V6, E-V7, E-S11, E-P17.

---

## Tema 6 — Profili utente secondari (tablet condiviso)

### 6.1 Comportamento

- Ogni utente ha il proprio spazio: la documentazione AOSP sul multiutente descrive utenti secondari che possono "continuare a girare in background con connettività di rete", e il sistema può fermarli per recuperare memoria [S48]. La pagina non copre job, allarmi, VPN e storage per utente.
- JobScheduler tiene un elenco di utenti avviati (nel dump del Tab S9: "Started users: [0]") [TAB]; che per un utente non avviato i job non girino è una deduzione [INF]. Un utente secondario avviato ma non sbloccato dopo un riavvio è in BFU: le sue app non leggono lo storage (tema 3) [INF].
- **VPN per utente**: "ogni utente (o profilo di lavoro) può eseguire un'app VPN diversa" [S45]. Il consenso `VpnService.prepare()` va dato nella sessione dell'utente [INF].
- Ogni utente ha il proprio storage condiviso e il proprio MediaStore (`/storage/emulated/<id>`) [INF]; Samsung Gallery gira per utente.
- Android 17: il traffico di loopback tra profili diversi non è più consentito per impostazione predefinita [S9] (marginale per noi).

### 6.2 Verificato sul Tab S9

- `pm list users`: utente 0 (primario, in esecuzione) e **Secure Folder id 150** (profilo del primario). Nessun utente secondario. `mCurrentProfileIds:[0, 150]`.
- `dumpsys activity users` [TAB]: `mMaxRunningUsers:4`, `mDelayUserDataLocking:false`, `mStopUserOnSwitch:-1` (predefinito), **`mBackgroundUserConsideredDispensableTimeSecs:93600`** (26 ore). Il nome suggerisce che un utente in background da più di 26 ore sia "sacrificabile" quando serve fermarne uno; la semantica esatta non è stata verificata [??]. Il sistema dichiara "Max switchable users: 8 (valore integrato: 4)".
- Servizi VPN dichiarati sul tablet [TAB]: Wi-Fi protetto di Samsung e un'app VPN di terze parti; WireGuard non è installato.
- Dall'utente 0 la shell non può elencare i pacchetti dell'utente 150: la Secure Folder è invisibile alle app dell'utente 0.

### 6.2 bis Verificato sull'S25 (Android 17)

- **Utenti** [A17]: `pm list users` mostra l'utente 0 (in esecuzione), l'id 10 e l'id 150. Tipo dell'id 10: `profile.MANAGED` (profilo di lavoro) con flag `QUIET_MODE`; tipo dell'id 150: Secure Folder, anch'esso `QUIET_MODE`. Entrambi hanno stato -1 (non avviati).
- **Il telefono non supporta utenti commutabili**: "Supports switchable users: false; Max switchable users: 1 (valore integrato: 4) (limit reached)". Gli utenti secondari con account propri esistono quindi solo sul tablet (fino a 8).
- **JobScheduler** [A17]: "Started users: [0]". I job dei profili fermi restano registrati (14 per l'id 10, 64 per l'id 150) e **tutti risultano `Ready: false (… user=false …)`**: JobScheduler li considera non eseguibili perché l'utente non è avviato. Questo conferma la deduzione di 6.1 per un utente non avviato.
- **Parametri** [A17]: `mMaxRunningUsers:4`, `mBackgroundUserConsideredDispensableTimeSecs:93600` (26 h), `mStopUserOnSwitch:-1`: uguali al Tab S9.
- Una VPN per utente non è osservabile sull'S25 (nessun altro utente avviato).


### 6.3 Strategia per l'app

1. Ogni profilo = un dispositivo a sé (piano 2.8): installazione, permessi, battito, chiave e approvazione propri. Nessun dato condiviso tra profili salvo il NAS.
2. **Stima del lavoro dei profili non attivi**: finché non è misurato (T-20), la Diagnostica dichiara "Il profilo può restare fermo mentre un altro è in uso" e il motore delle regole non promette orari. All'apertura del profilo, recupero prioritario (E-P19).
3. Mostrare in Diagnostica, per profilo: se l'utente è sbloccato, ore dall'ultimo battito, e (se leggibile) lo stato di esecuzione.
4. Guida per il tablet condiviso: ogni profilo ha il proprio WireGuard e il proprio consenso VPN (E-P20); ogni profilo concede i propri permessi (media, notifiche, batteria Illimitata).
5. Secure Folder e Spazio privato: ignorati e dichiarato (E-P7).
6. Sul telefono non esistono utenti commutabili (solo profili): l'architettura "un profilo = un dispositivo" riguarda il tablet; sul telefono un profilo di lavoro o la Secure Folder restano fuori dal perimetro.

### 6.4 Edge case collegati

E-P18, E-P19, E-P20, E-O7, E-O8, E-P7.

---

## Tema 7 — Rete: riconoscere casa senza posizione, VPN, rete locale

### 7.1 Comportamento

**Cosa si può leggere senza permesso di posizione.**

- `LinkProperties` (sorgente AOSP) [S46]: `getDomains()` ("percorso di ricerca dei domini DNS"), `getDhcpServerAddress()`, `getRoutes()` (gateway predefinito), `getDnsServers()`, `getLinkAddresses()` (incluso il prefisso IPv6), `getPrivateDnsServerName()`. Nessuna di queste ha `@SystemApi` o `@hide`. Servono solo i permessi di stato rete.
- `NetworkCapabilities` [S47]: `TRANSPORT_VPN`, `NET_CAPABILITY_NOT_VPN`, `NOT_METERED`, `TEMPORARILY_NOT_METERED`, `NOT_ROAMING`, `NOT_BANDWIDTH_CONSTRAINED` e `LOCAL_NETWORK` (queste ultime due sotto flag API). Il `TransportInfo` Wi-Fi nasconde SSID e BSSID senza posizione. **`getUnderlyingNetworks()` è `@SystemApi`: le app non possono usarlo.**
- La rete Wi-Fi *sotto* la VPN è a sua volta una rete `NOT_VPN`: le richieste di sistema sul Tab S9 usano `TRANSPORT_WIFI` con `NOT_VPN` e vedono il Wi-Fi [TAB]. Una richiesta di un'app con `TRANSPORT_WIFI` dovrebbe quindi vedere le proprietà del Wi-Fi anche con la VPN attiva (il costruttore predefinito di `NetworkRequest` parte da `NOT_VPN`, `TRUSTED`, `NOT_RESTRICTED`) [INF; da riconfermare con T-17].

**VPN e `Network.bindSocket`.**

- La documentazione di `VpnService.Builder.allowBypass()`: "Permette a tutte le app di aggirare questa VPN. Se è chiamato, le app possono usare metodi come `ConnectivityManager#bindProcessToNetwork` per inviare e ricevere direttamente sulla rete sottostante o su qualunque altra rete cui abbiano accesso" [S42]. Senza `allowBypass()` la VPN risulta "secure" (non bypassabile): la corrispondenza è dedotta dalla documentazione e dal codice di netd, non letta in `ConnectivityService` [INF].
- Nelle regole di routing di netd [S43], il traffico delle app comprese nella VPN secure ha priorità **13000**; la scelta esplicita di rete (socket legato con `bindSocket`) **16000**; una VPN bypassabile solo **24000** o **27000**. Numero più basso = valutato prima: con una VPN non bypassabile, il socket legato al Wi-Fi viene comunque catturato dal tunnel. Fanno eccezione i socket "protetti" (`VpnService.protect`), riservati all'app VPN stessa [S42].
- **WireGuard per Android non chiama `allowBypass()`** [S44]: usa `addAllowedApplication`/`addDisallowedApplication` (app incluse/escluse per tunnel), `setBlocking(true)`, `setMetered(false)`, `setMtu` (predefinito 1280), `protect` sui propri socket. Quindi: un'app *dentro* il tunnel non può uscirne con `bindSocket`; un'app *esclusa* nella configurazione del tunnel esce direttamente.
- WireGuard accetta controllo da altre app solo se l'utente lo abilita nelle sue impostazioni (come da piano 2.5) e ogni utente Android ha il proprio [S45].

**Permesso di rete locale** [S8, S9, S10]:

- Android 16: opt-in per app tramite `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pacchetto>` + riavvio; per riottenere l'accesso serve `NEARBY_WIFI_DEVICES`.
- Android 17, app con target 37: **`ACCESS_LOCAL_NETWORK`** è un permesso a runtime, nel gruppo `NEARBY_DEVICES`; senza di esso il traffico locale è bloccato. Richiede permesso: TCP in uscita e in ingresso, UDP unicast, multicast e broadcast in invio e ricezione, per tutte le API di rete (socket, OkHttp, Cronet). Esenti: DNS verso un server locale (porta 53), connessioni a indirizzi ottenuti con il selettore di sistema di `NsdManager`, Output Switcher. Con negazione: i TCP tendono ad andare in *timeout*, gli UDP danno `EPERM`; in NDK `android_getnetworkblockedreason()` riporta `ANDROID_NETWORK_BLOCKED_REASON_LNP`. Una definizione precisa di "indirizzo locale" è in una guida separata che non ho letto (RFC1918, link-local e CGNAT per IPv4 secondo [S8]). Non è documentato se il traffico verso un indirizzo privato *dentro un tunnel VPN* conti come locale [??].
- Con target 36 oggi l'app non è colpita (concessione implicita, verificata sull'S25: vedi 7.2 bis e la sezione "Novità di Android 17 rilevanti"). Il piano usa SMB (TCP 445) e Wake-on-LAN (UDP broadcast): entrambi rientrano.

**Altro.**

- Rete a consumo: `NOT_METERED` assente e/o `TEMPORARILY_NOT_METERED`; il Wi-Fi può essere segnato a consumo dall'utente, e `WifiInfo` ha un campo "metered hint" [TAB: `false` sul Wi-Fi di casa]. Gli hotspot di altri telefoni sono trattati come a consumo dal sistema [INF, non verificato qui].
- Pagina di accesso (captive portal): `NET_CAPABILITY_VALIDATED` assente / `CAPTIVE_PORTAL` presente [INF].
- Il Wi-Fi usa un MAC casuale per rete (`useRandomizedMac=true`): il MAC del tablet non identifica il dispositivo né serve per il NAS [TAB].
- L'SSID richiede il permesso di posizione e non si usa (scelta del piano).

### 7.2 Verificato sul Tab S9

Con il tablet sul Wi-Fi di casa, nessuna VPN attiva [TAB]:

- Capacità: `NOT_METERED`, `INTERNET`, `NOT_VPN`, `VALIDATED`, `NOT_ROAMING`, `NOT_CONGESTED`, `NOT_SUSPENDED`, `NOT_BANDWIDTH_CONSTRAINED`; trasporto `WIFI`; punteggio "IS_UNMETERED".
- `LinkProperties`: interfaccia Wi-Fi, **dominio di ricerca `fritz.box`**, server DHCP presente, gateway predefinito IPv4, DNS (IPv4 del gateway più indirizzi IPv6), MTU 1500, indirizzi IPv6 con prefisso globale dell'operatore e con **prefisso ULA** (`fd…/64`, valore non riportato qui), rotte corrispondenti.
- Servizi VPN: nessuno attivo (vedi tema 6).
- `private_dns_mode` non valorizzato; la restrizione dei dati in background è disattivata.
- I listener di rete di sistema usano `TRANSPORT_WIFI` con `NOT_VPN`: la richiesta per il Wi-Fi sottostante è praticabile.

### 7.2 bis Verificato sull'S25 (Android 17)

Con il telefono sul Wi-Fi di casa, nessuna VPN attiva [A17]:

- **Wi-Fi**: stesse capacità del tablet (`NOT_METERED`, `NOT_VPN`, `VALIDATED`, `NOT_ROAMING`, `NOT_CONGESTED`, `NOT_SUSPENDED`, `NOT_BANDWIDTH_CONSTRAINED`), dominio di ricerca `fritz.box`, server DHCP presente, indirizzo IPv6 globale e prefisso ULA, MTU 1500.
- **Cellulare**: è presente solo la rete con capacità IMS/MMTEL, senza `INTERNET`; MTU 1280. Non c'è una rete cellulare con accesso a internet al momento della lettura.
- **VPN**: nessuna attiva. Servizi dichiarati: Wi-Fi protetto di Samsung e un'app VPN di terze parti, come sul tablet.
- **DNS privato**: `private_dns_mode=opportunistic` (sul Tab S9 non valorizzato). Restrizione dei dati in background disattivata. Posizione attiva (non usata).
- **Android 17**: `android.settings.VPN_APP_EXCLUSION_SETTINGS` si risolve in `com.android.settings/.vpn2.AppExclusionActivity`: schermata di sistema per escludere app dalla VPN.
- **Permesso di rete locale** [A17], dal dump di `platform_compat` e da `dumpsys package`:
  - `RESTRICT_LOCAL_NETWORK`: **disabled** di default (si attiva solo con `am compat enable`);
  - `USE_NSD_PICKER_WHEN_NO_LOCAL_NET_PERMISSION`: da target 37;
  - app con target 36 (WhatsApp, OneDrive) e 35 (app WD): `ACCESS_LOCAL_NETWORK: granted=true`, flag `REVOKE_WHEN_REQUESTED` (concessione implicita);
  - Google Foto (target 37): `granted=false`.


### 7.3 Strategia per l'app

1. **Riconoscere casa con più indizi, senza posizione**: a partire dalla richiesta di rete Wi-Fi (non VPN) leggere dominio di ricerca, server DHCP, gateway, prefissi IPv6. Impronta della rete:
   - *forte*: **prefisso ULA IPv6** della FRITZ!Box (generato a caso dal router, quindi diverso da una FRITZ!Box all'altra; se la funzione è attiva e stabile nel tempo [??]);
   - *debole*: dominio `fritz.box`, gateway e subnet predefiniti: uguali in tutte le FRITZ!Box e quindi **inutili da soli** per E-S10;
   - *a conferma, prima di qualunque autenticazione*: l'identità del NAS dalla negoziazione SMB (GUID del server) o altri dati pubblici, che non sono un'autenticazione ma scartano il caso "host qualunque allo stesso IP" [INF].
   L'app memorizza l'impronta alla configurazione (con consenso) e la ricontrolla a ogni cambio di rete. Nessuna SMB e nessun invio di password se l'impronta non coincide (E-S10).
2. **VPN a casa**: non contare su `bindSocket` per raggiungere il NAS in LAN con la VPN accesa. Strategia:
   - casa riconosciuta e controllo di WireGuard abilitato dall'utente: l'app chiede a WireGuard di spegnere il tunnel (intento di controllo) e verifica che il NAS risponda; fuori casa lo riaccende;
   - controllo non abilitato: l'app usa il NAS attraverso il tunnel (più lento), lo dichiara ("VPN accesa in casa: NAS raggiunto via tunnel") e mostra il pulsante per aprire WireGuard (piano 2.5);
   - escludere l'app dal tunnel (elenco delle app escluse nella configurazione) farebbe uscire il traffico direttamente, ma varrebbe anche fuori casa, dove il tunnel serve: non è una soluzione. Android consente una sola VPN attiva per utente, quindi non si possono tenere due configurazioni accese insieme;
   - limitare le `AllowedIPs` alla sola subnet di casa non evita il passaggio dal tunnel quando si è già in quella subnet: effetto da misurare (T-18) [??].
   La velocità in casa con il tunnel è una misura della Fase 0.2.
3. **Classificazione dello stato di rete** (2.1): `TRANSPORT_VPN` presente = "via VPN", con il trasporto sottostante letto dal Wi-Fi/cellulare osservati a parte; `NOT_METERED` e `NOT_ROAMING` per dati mobili e roaming (proposta 2); `NOT_VALIDATED`/captive portal = "Offline" o dati mobili (E-R13); due reti contemporanee: regole sulla rete *effettivamente usata* dal socket, non su quella di sistema (E-R12).
4. **Permesso di rete locale**: nuovo passo dell'onboarding ("Accesso alla rete locale") e nuovo stato in Diagnostica ("Rete locale non consentita"). Con target 36: su Android 17 la concessione di `ACCESS_LOCAL_NETWORK` è implicita e la restrizione è opt-in (verificato sull'S25); dichiarare `NEARBY_WIFI_DEVICES` (serve a riottenere l'accesso con la restrizione attiva) e provare con `RESTRICT_LOCAL_NETWORK` abilitato. Con target 37: richiedere `ACCESS_LOCAL_NETWORK` a runtime. Rilevare la revoca: leggere il permesso prima di ogni tentativo; se un TCP verso il NAS va in timeout con rete e DNS validi, controllare il permesso *prima* di dire "NAS spento" (E-R5). Il Wake-on-LAN (UDP broadcast) segue la stessa regola: con target 37 è bloccato senza permesso. Vedi la sezione "Novità di Android 17 rilevanti".
5. **Reti a consumo e captive portal**: trattati come dati mobili o offline (E-R13); nessuna autenticazione SMB su reti non riconosciute.
6. **Traffico che risveglia i dischi**: i controlli di raggiungibilità si limitano alla finestra prevista e usano la porta più leggera (E-R15, piano).

### 7.4 Edge case collegati

E-R3, E-R4, E-R5, E-R6, E-R7, E-R11, E-R12, E-R13, E-R14, E-R15, E-S10, E-P20.

---

## Novità di Android 17 rilevanti

Stato: l'S25 è su Android 17 (SDK 37.0) con One UI 9.0; il Tab S9 è su Android 16. Samsung Gallery (15.9.01.7) e Google Foto sono già compilate per target 37. Le pagine ufficiali lette [S9, S49, S50, S51, S53] dicono poco su job, servizi in primo piano, MediaStore, Photo Picker e backup: dove non c'è nulla lo scrivo. Ciò che non è cambiato è confermato dalle letture sull'S25.

### A. Permesso di rete locale: a chi si applica e cosa succede con target 36

| Caso | Comportamento |
|---|---|
| Android 17, app con **target 37** | Il traffico verso indirizzi locali è bloccato di default. Serve `ACCESS_LOCAL_NETWORK` dichiarato nel manifest e richiesto a runtime (gruppo Dispositivi nelle vicinanze: chi ha già un permesso del gruppo non viene richiesto di nuovo) [S10, S9]. |
| Android 17, app con **target 36 o inferiore** | Nessun blocco: se l'app ha `INTERNET` riceve una concessione implicita, definita "temporanea" [S10]. Non deve richiedere `ACCESS_LOCAL_NETWORK` prima di alzare il target a 37. Sull'S25: WhatsApp e OneDrive (36) e l'app WD (35) hanno il permesso `granted=true` con il flag `REVOKE_WHEN_REQUESTED`; Google Foto (37) `granted=false` [A17]. |
| Android 16, qualunque target | Opt-in: la restrizione si attiva solo con `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pacchetto>` (e riavvio); si ripristina l'accesso con `NEARBY_WIFI_DEVICES` [S8, S10]. |

- Quindi si applica **obbligatoriamente solo alle app con target ≥ 37**; le altre sono esenti per ora grazie alla concessione implicita. Che l'utente possa togliere quella concessione dalle impostazioni, e che cosa succeda in quel caso, non è documentato [??].
- Traffico coperto [S10]: TCP in uscita e in ingresso, UDP unicast, multicast e broadcast in invio e ricezione, per tutte le API di rete. Esenti: DNS verso un server locale (porta 53), indirizzi ottenuti con il selettore di sistema di `NsdManager`, Output Switcher. A permesso negato i TCP vanno in timeout e gli UDP danno `EPERM`; in NDK `android_getnetworkblockedreason()` restituisce `ANDROID_NETWORK_BLOCKED_REASON_LNP`. Per MyCloud Gallery: SMB (TCP 445) e Wake-on-LAN (UDP broadcast) sono coperti, il DNS verso la FRITZ!Box no.
- **Cosa fare** (collegato a 2.1, 2.2 e 1.1):
  1. Costruire ora il passo di onboarding "Accesso alla rete locale" e lo stato "Rete locale non consentita" in Diagnostica, con un ramo attivo da target 37.
  2. Tenere target 36 finché i test T-19 e T-24 non passano.
  3. Pianificare il passaggio a target 37 come tappa, non come urgenza: la concessione implicita è "temporanea" e Samsung Gallery e Google Foto sono già a 37.
  4. Provare subito sull'S25 la restrizione con `am compat enable RESTRICT_LOCAL_NETWORK` sull'app di prova a target 36.

### B. Altre novità di Android 17

| Novità | Si applica a | Fonte | Sull'S25 | Impatto sul piano |
|---|---|---|---|---|
| **Limiti di memoria per app** basati sulla RAM totale; chiusure con `REASON_OTHER` e descrizione `MemoryLimiter:AnonSwap`; `am memory-limiter`; `ProfilingTrigger.TRIGGER_TYPE_ANOMALY` per le heap dump prima del limite | Tutte le app su Android 17 | [S9, S51] | `am memory-limiter status` risponde "disabled"; i flag `memory_limiter_enable`, `..._default_app_limits`, `..._swap`, `..._trigger` sono `true` nei `device_config` | Il calcolo AI con modello in RAM (0.3) potrebbe essere chiuso dal sistema se il limitatore viene attivato: registrare `ApplicationExitInfo` in Diagnostica e provare il limitatore (T-23). |
| **URI impliciti di `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_IMAGE_CAPTURE`**: da Android 18 il sistema non concede più i permessi in automatico; strumenti di rilevamento (StrictMode `detectImplicitUriPermissionGrant`) | Tutte le app (rilevamento con compat `DETECT_IMPLICIT_URI_PERMISSION_GRANT` da target 37) | [S9] | Compat id presente | In invio (Condividi): aggiungere sempre `FLAG_GRANT_READ_URI_PERMISSION`. In ricezione: copiare/aprire i flussi subito (5.3, punto 5). |
| **`JobDebugInfo.getPendingJobReasonStats()`**: motivi di attesa con durata cumulata, in una sola chiamata | Android 17 | [S50] | Flag `get_pending_job_reason_stats_api=true` | Alimenta i messaggi "In attesa: …" (1.6, 2.7) con il tempo trascorso per motivo. |
| Job, quote, servizi in primo piano, BOOT_COMPLETED, UIDT | — | [S49, S51] | Costanti identiche a quelle di Android 16 | Nessuna novità documentata: il tema 1 resta valido. |
| `AlarmManager.setExactAndAllowWhileIdle` con `OnAlarmListener` | Android 17 | [S51] | — | Non usato dal piano. | *Fase 9 (27/10, `docs/fase9-progetto.md` D20): la notifica dei ricordi all'ora scelta usa WorkManager (`OneTimeWork` unico `memories-notify` con il ritardo fino all'ora, ripianificato a ogni corsa, a ogni cambio delle impostazioni e a ogni avvio): finestra di consegna da H a H+2 h, Doze e risparmio la spostano, oltre H+3 h quel giorno si salta (L263). Nessun allarme esatto (`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` fuori dalla lista dei permessi). Piano B se F115 mostra piu' del 10% di ritardi: `AlarmManager.setWindow` non esatto, senza permessi, con un ricevitore **non** esportato.*
| **Background audio hardening**: audio, focus e volume da background falliscono senza un servizio in primo piano con capacità while-in-use | Target 37 (alcune parti per tutti) | [S9, S49] | — | Il viewer riproduce in primo piano: nessun impatto. La picture-in-picture (idea 10.6) avrà bisogno di un servizio adatto. |
| **Schermata di sistema per escludere app dalla VPN** (`android.settings.VPN_APP_EXCLUSION_SETTINGS`) | Android 17 | [S51] | Risolve in `AppExclusionActivity` | L'utente può escludere l'app dal tunnel: la Diagnostica deve riconoscere "NAS irraggiungibile fuori casa" e suggerirlo come causa. Non risolve il caso "bypass in casa" (statica). |
| Traffico loopback tra profili diversi bloccato di default | Tutte le app su Android 17 | [S9] | Il dump di compat lega `ENFORCE_LOOPBACK_RESTRICTIONS` al target 38: pagina e build non concordano | Marginale. |
| **Photo Picker**: `PhotoPickerUiCustomizationParams` (griglia 9:16), `PhotoPickerSelectionParams` | Android 17 | [S51, S55] | — | Nessun impatto: non usiamo il picker come sorgente. Nessuna novità documentata per Cloud Media Provider e MediaStore. |
| **API con versioni minori**: le API possono arrivare con le release minori (QPR); `MediaStore.DeletedFiles` risulta "37.1 / S Extensions 23" | Android 17 | [S39] | SDK extension S/T/U/V/B/C = 23 su S25 e Tab S9 | Proteggere le chiamate con il controllo dell'estensione SDK, non solo con `SDK_INT`. |
| **Backup e trasferimento tra dispositivi**: nessuna novità di Android 17 trovata. Base: `dataExtractionRules`; con target ≥ 12 `allowBackup="false"` può non fermare il trasferimento tra dispositivi su alcuni produttori; senza sezione `<device-transfer>` la modalità è abilitata; da Android 16 QPR2 esiste `<cross-platform-transfer platform="ios">` | Android 12+ | [S52] | — | 1.5 e E-O8: esclusione esplicita in `<device-transfer>` (T-25). |
| **Altro per chi alza il target a 37**: librerie native caricate con `System.load()` devono essere in sola lettura (altrimenti `UnsatisfiedLinkError`); campi `static final` non modificabili per riflessione; Certificate Transparency ed ECH attivi; limite di 50.000 chiavi nel Keystore; orientamento e ridimensionamento non più disattivabili su schermi con larghezza minima ≥ 600 dp; password nascoste con tastiera fisica; `setContentCaptureEnabled(false)` non vale (serve `FLAG_SECURE`) | Target 37 | [S9, S49] | Samsung Gallery e Google Foto sono già a target 37 | Runtime ML con librerie native estratte: verificare la sola lettura. Tablet: la UI adattiva deve reggere finestre libere (10.2). `FLAG_SECURE` è già nel piano (1.5). |
| **Samsung One UI 9** (stampa, non ufficiale): livelli di Protezione batteria con *Massima* a 80, 85, 90 o 95%; aggiornamento di Good Guardians con risparmio di rete | Dispositivi Samsung | [S54] | `inactivity_restart=1` leggibile; soglia 80% | Nessuna pagina ufficiale Samsung per sviluppatori su One UI 9 trovata: le regole di sospensione restano quelle di [S26]. |

### C. Conseguenze sul piano

- **1.1**: target 36 con ramo 37 pronto; test su S25 (Android 17) e tablet (Android 16).
- **1.5**: `dataExtractionRules` con `<device-transfer>` esplicito; verifica con Smart Switch.
- **2.1 e 2.2**: passo "Accesso alla rete locale" e stato in Diagnostica.
- **2.7**: motivi di attesa con `getPendingJobReasonStats()`; motivo di chiusura del processo da `ApplicationExitInfo` (limiti di memoria).
- **5.7**: flag di lettura sugli URI condivisi; copia immediata dei flussi in ricezione.
- Tutto il resto dei temi 1-7 vale su entrambe le versioni di Android.

---

## Appendice A — Segnali che l'app può leggere (per Diagnostica e motore delle regole)

| Informazione | Segnale | Note |
|---|---|---|
| Alimentazione collegata | `EXTRA_PLUGGED` del broadcast sticky di batteria | Unica definizione di "in carica" (tema 2) |
| Carica sospesa | `EXTRA_STATUS` = NOT_CHARGING con `EXTRA_PLUGGED` ≠ 0 | Mostrare "carica sospesa", non "scarica" |
| Livello batteria | `EXTRA_LEVEL` / `EXTRA_SCALE` | Soglia dell'app (20%) |
| Risparmio energetico | `PowerManager.isPowerSaveMode()`, `ACTION_POWER_SAVE_MODE_CHANGED` | Background in pausa |
| Stato termico | `getCurrentThermalStatus()`, listener, `getThermalHeadroom()` (≥ 10 s tra due chiamate) | `NaN` = non supportata |
| Esenzione ottimizzazione batteria | `PowerManager.isIgnoringBatteryOptimizations()` | Condizione per i servizi lunghi |
| Limitazione in background | `ActivityManager.isBackgroundRestricted()` | Profilo "Limitata" (da riconfermare in compilazione) |
| Bucket | `UsageStatsManager.getAppStandbyBucket()` | RESTRICTED = guida; Samsung "sospensione" equivale a RESTRICTED [S26] |
| Hibernation | `PackageManagerCompat.getUnusedAppRestrictionsStatus()` | Più `createManageUnusedAppRestrictionsIntent()` |
| Permessi media | `checkSelfPermission` su `READ_MEDIA_*`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION` | Mai memorizzare lo stato |
| Utente sbloccato | `UserManager.isUserUnlocked()` | BFU = in attesa di sblocco |
| Riavvii | `Settings.Global.BOOT_COUNT`, `elapsedRealtime()` | Spiegare i ritardi |
| Esito dei job | `WorkInfo.getStopReason()`, `JobParameters.getStopReason()` | Android 16: `JobScheduler.getPendingJobReason(s)History` |
| Uscite del processo | `ApplicationExitInfo` | Motivo delle chiusure |
| MediaStore | `getVersion()`, `getGeneration(volume)`, `DeletedFiles` (extension S ≥ 23) | Tema 4 |
| Rete | `NetworkRequest` Wi-Fi (non VPN) con `LinkProperties` e `NetworkCapabilities` | Tema 7 |
| Rete locale | `checkSelfPermission` `NEARBY_WIFI_DEVICES` (target 36) / `ACCESS_LOCAL_NETWORK` (target 37) | Prima di dire "NAS spento" |

---

## Appendice B — Fonti

Pagine ufficiali (lette il 12/10/2026):

- [S1] Timeout dei servizi in primo piano: https://developer.android.com/develop/background-work/services/fgs/timeout
- [S2] Cambiamenti di comportamento Android 15: https://developer.android.com/about/versions/15/behavior-changes-15
- [S3] Restrizioni all'avvio da background dei servizi in primo piano: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- [S4] Tipi di servizio in primo piano: https://developer.android.com/develop/background-work/services/fgs/service-types
- [S5] Restrizioni Android 12 sui servizi in primo piano: https://developer.android.com/about/versions/12/foreground-services
- [S6] User-initiated data transfer: https://developer.android.com/develop/background-work/background-tasks/uidt
- [S7] Android 16, tutte le app: https://developer.android.com/about/versions/16/behavior-changes-all
- [S8] Android 16, app con target 36: https://developer.android.com/about/versions/16/behavior-changes-16
- [S9] Android 17, tutte le app: https://developer.android.com/about/versions/17/behavior-changes-all
- [S10] Permesso di rete locale: https://developer.android.com/privacy-and-security/local-network-permission
- [S11] App Standby Bucket: https://developer.android.com/topic/performance/appstandby
- [S12] Limiti di risorse per la gestione energetica: https://developer.android.com/topic/performance/power/power-details
- [S13] Doze e App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- [S14] WorkManager, lavori lunghi: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- [S15] WorkManager, definire il lavoro (expedited, vincoli): https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- [S16] Note di rilascio WorkManager: https://developer.android.com/jetpack/androidx/releases/work
- [S17] Sorgente `BatteryChargingTracker.kt`: https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/java/androidx/work/impl/constraints/trackers/BatteryChargingTracker.kt
- [S18] Sorgente `GreedyScheduler.java`: https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/java/androidx/work/impl/background/greedy/GreedyScheduler.java
- [S19] Sorgente `BatteryController.java` di JobScheduler: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/apex/jobscheduler/service/java/com/android/server/job/controllers/BatteryController.java
- [S20] Sorgenti `BatteryManager.java` e `BatteryService.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryManager.java e https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/BatteryService.java
- [S21] API termiche (ADPF): https://developer.android.com/games/optimize/adpf/thermal
- [S22] Mitigazione termica (AOSP): https://source.android.com/docs/core/power/thermal-mitigation
- [S23] Direct Boot: https://developer.android.com/privacy-and-security/direct-boot
- [S24] Issue tracker WorkManager e Direct Boot (non leggibile; solo sintesi di ricerca): https://issuetracker.google.com/issues/112665532
- [S25] App hibernation: https://developer.android.com/topic/performance/app-hibernation
- [S26] Samsung Developers, gestione delle app (Sleeping/Deep sleeping/Never sleeping): https://developer.samsung.com/mobile/app-management.html
- [S27] Samsung, opzioni di protezione batteria One UI 8: https://www.samsung.com/ru/support/mobile-devices/what-are-specific-differences-of-battery-protection-options
- [S28] Samsung, Riavvio automatico e Riavvio per inattività: https://www.samsung.com/us/support/troubleshoot/TSG10013116/
- [S29] SamMobile, profili batteria per app (secondaria): https://www.sammobile.com/news/prevent-one-ui-from-halting-background-apps-and-games/
- [S30] Don't kill my app, Samsung (secondaria, anche vecchia): https://dontkillmyapp.com/samsung
- [S31] Riavvio dopo 3 giorni da Google Play Services e Samsung (stampa, secondaria): https://bleepingcomputer.com/news/security/google-adds-android-auto-reboot-to-block-forensic-data-extractions e https://www.sammobile.com/news/inactivity-restart-security-feature-is-reaching-more-phones-galaxy-s26/
- [S32] Accesso ai file multimediali condivisi (MediaStore): https://developer.android.com/training/data-storage/shared/media
- [S33] Accesso parziale a foto e video: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- [S34] Photo Picker: https://developer.android.com/training/data-storage/shared/photopicker
- [S35] Cloud media provider: https://developer.android.com/guide/topics/providers/cloud-media-provider
- [S36] Blog, "Photo picker everywhere": https://android-developers.googleblog.com/2023/04/photo-picker-everywhere.html
- [S37] Sorgente `DatabaseBackupAndRecovery.java` (MediaProvider): https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/DatabaseBackupAndRecovery.java
- [S38] Sorgente `DatabaseHelper.java` (MediaProvider): https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/DatabaseHelper.java
- [S39] Riferimento `MediaStore.DeletedFiles` (pagina non leggibile; colonne e URI da ricerca e da prova sul tablet): https://developer.android.com/reference/android/provider/MediaStore.DeletedFiles
- [S40] Samsung, ripristino dell'originale di una foto modificata: https://www.samsung.com/in/support/mobile-devices/how-to-get-original-version-of-edited-image-on-samsung-devices
- [S41] Samsung, sincronizzazione della Galleria: https://www.samsung.com/ae/support/mobile-devices/samsung-gallery-sync-photos-across-devices/
- [S42] Sorgente `VpnService.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/VpnService.java
- [S43] Sorgente `RouteController.h` di netd: https://android.googlesource.com/platform/system/netd/+/refs/heads/main/server/RouteController.h
- [S44] Sorgente `GoBackend.java` di WireGuard per Android: https://raw.githubusercontent.com/WireGuard/wireguard-android/master/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java
- [S45] Guida VPN di Android: https://developer.android.com/develop/connectivity/vpn
- [S46] Sorgente `LinkProperties.java`: https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/LinkProperties.java
- [S47] Sorgente `NetworkCapabilities.java`: https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkCapabilities.java
- [S48] Multiutente (AOSP): https://source.android.com/docs/devices/admin/multi-user
- [S49] Android 17, app con target 37: https://developer.android.com/about/versions/17/behavior-changes-17
- [S50] Android 17, funzionalità e API: https://developer.android.com/about/versions/17/features
- [S51] Android 17, note di rilascio (beta 1-4.1): https://developer.android.com/about/versions/17/release-notes
- [S52] Backup automatico e regole di estrazione dati: https://developer.android.com/identity/data/autobackup
- [S53] Android 17, panoramica: https://developer.android.com/about/versions/17
- [S54] One UI 9, batteria (stampa, non ufficiale): https://www.sammyfans.com/2026/10/01/top-3-battery-settings-to-change-after-one-ui-9-0-update/amp/ e https://www.sammyfans.com/2026/08/09/5-one-ui-9-settings-to-change-on-your-galaxy-z-fold-8/amp/ (lette solo come sintesi di ricerca)
- [S55] API diff Android 17, Photo Picker: https://developer.android.com/sdk/api_diff/37/changes/pkg_android.widget.photopicker
- [S56] Rollout di One UI 9 e Android 17 (stampa, non ufficiale): https://androidauthority.com/samsung-one-ui-9-galaxy-s25-fold-7-flip-7-stable-3716021

Forum e community Samsung (non ufficiali, solo per indicare un comportamento osservato dagli utenti): discussioni sul "Salva" della Galleria che nasconde l'originale e sul ripristino.

---

## Appendice C — Cosa resta incerto

- **Valori [A17] letti il 12/10/2026 sull'S25** (Android 17). Il confronto con il Tab S9 è fatto; il tablet è stato letto con Android 16 e non è stato riletto dopo.
- **WorkManager con stato NOT_CHARGING** (T-01): la lettura del sorgente dice che il tracker interno diverge da JobScheduler, ma l'effetto sul lavoro che gira non è stato osservato.
- **Servizio in primo piano da job in background con app "Illimitata"** (T-02): l'esenzione è documentata come tale, ma non provata su Samsung.
- **Salvaguardie di esecuzione.** Contatori noti, conseguenze no.
- **WorkManager 2.12.0** (23/09/2026): note non esaminate.
- **Cestino Samsung**: struttura osservata, flusso no. Pagine di supporto Samsung con 30 o 60 giorni secondo il modello.
- **Riavvio automatico e Riavvio per inattività**: non leggibili da adb; comportamento descritto da Samsung e stampa.
- **Profili secondari**: nessun test; il parametro dei 26 h è solo il nome di un campo.
- **Rete locale e VPN**: non è documentato se il traffico verso un indirizzo privato dentro un tunnel conti come "locale".
- **Impronta ULA della FRITZ!Box**: stabilità nel tempo (reset del router, aggiornamenti) non verificata.
- **Concessione implicita di `ACCESS_LOCAL_NETWORK`** per le app con target < 37: definita "temporanea" da [S10]; la durata e la possibilità per l'utente di toglierla non sono documentate.
- **Limitatore di memoria di Android 17**: sull'S25 risulta disabilitato; non si sa se One UI lo attiverà o con quali soglie, né se il Tab S9 su Android 17 si comporterà allo stesso modo.
- **Materiale One UI 9**: solo stampa; nessuna pagina ufficiale Samsung per sviluppatori letta.
- **Intervallo degli `_ID` del MediaStore**: la numerazione "a miliardo" è osservata solo sul Tab S9; non si sa quando o perché il suo database sia stato ricreato.

---

## Da verificare con l'app di prova

Test che solo un'app installata può fare. Per ciascuno: obiettivo, passi essenziali, esito atteso e decisione che sblocca. I comandi `adb` che *scrivono* impostazioni o flag (`device_config put`, `am compat enable`, `am set-standby-bucket`) sono pensati per la fase di test sul dispositivo dedicato, a cura dell'utente: in questa fase 0.5 non sono stati eseguiti.

### Batteria e lavori lunghi

**T-01 — Carica sospesa dalla Protezione batteria.**
Obiettivo: capire se il lavoro notturno parte e prosegue con alimentazione collegata e stato NOT_CHARGING.
Passi: Tab S9 al 90% (soglia attuale) collegato; S25 all'80% (soglia attuale, Android 17); poi con Protezione *Massima* a 85% e con *Adattiva*. Tre varianti per lo stesso lotto da 30 minuti: (a) `OneTimeWorkRequest` con `setRequiresCharging(true)` mentre il processo è vivo (servizio in primo piano di prova); (b) lo stesso, con processo morto e avvio da JobScheduler; (c) `JobInfo` nativo con `setRequiresCharging(true)`. Registrare `EXTRA_STATUS`, `EXTRA_PLUGGED`, `BatteryManager.isCharging()`, broadcast `ACTION_CHARGING/DISCHARGING/POWER_*`, esito, `WorkInfo.getStopReason()` (atteso `STOP_REASON_CONSTRAINT_CHARGING` se il tracker interno ferma), `dumpsys jobscheduler`.
Decisione: scegliere tra "vincolo WorkManager" e "sveglia nativa + verifica `EXTRA_PLUGGED`" (2.3, punto 2).

**T-02 — Avvio di un servizio in primo piano da un job in background.**
Passi: lavoro periodico (carica, rete non a consumo) che chiama `setForeground()` di tipo `dataSync` e `mediaProcessing`, ad app non visibile, schermo spento, in tre profili batteria (Ottimizzata, Illimitata, Limitata); e come job expedited.
Esito atteso: riuscito solo con Illimitata (esenzione). Registrare `ForegroundServiceStartNotAllowedException`.
Decisione: se l'esenzione basta, il passo 10 resta requisito; altrimenti solo tappe (1.3).

**T-03 — Timeout dei servizi.**
Passi: ridurre la soglia di test (`am compat enable FGS_INTRODUCE_TIME_LIMITS <pacchetto>` e `device_config put activity_manager data_sync_fgs_timeout_duration <ms>`), osservare `onTimeout`, reazione di WorkManager (versione 2.12.0), `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, ripresa la sera dopo; verificare che dataSync e mediaProcessing abbiano budget separati e come si comporta l'azzeramento quando l'app va in primo piano.
Decisione: forma dell'orchestratore (1.3).

**T-04 — Durata reale delle tappe e quote.**
Passi: tappe da 5, 9, 12, 20, 35 minuti nei bucket impostati con `am set-standby-bucket` (Active, Working set, Frequent, Rare, Restricted), a batteria e in carica, e con servizio in primo piano; uso dei flag `OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS` e `..._TO_TOP_STARTED_JOBS`. Registrare dove scatta `STOP_REASON_TIMEOUT`/`QUOTA` e i contatori delle salvaguardie.
Decisione: durata massima di una tappa (proposta: 8 minuti) e ruolo del servizio in primo piano.

**T-05 — "Fai ora" come UIDT.**
Passi: UIDT con app visibile, poi schermo spento e app in secondo piano, da Samsung; notifica e pulsante di arresto; arresto dal Task Manager; vincoli di rete su Wi-Fi e VPN; durata su 2 ore; termica.
Decisione: forma di "Fai ora" (1.3, punto 4).

**T-13 — Trigger di contenuto sul MediaStore.**
Passi: job con trigger su immagini e video (con discendenti) nei bucket e dopo l'ingresso in "Sospensione"; confronto con `ContentObserver` in un servizio; foto scattate, screenshot, file di WhatsApp in `Android/media`. Misurare la latenza e le perdite in una raffica di 50 foto.
Decisione: rilevamento dei nuovi media (1.3, punto 5).

**T-21 — Termica con carico NPU.**
Passi: calcolo AI continuo per 2 ore su NPU (0.3) a 20-25 °C, con custodia, al sole; registrare stato termico, headroom e throughput.
Decisione: soglie e isteresi della pausa termica.

**T-22 — Risparmio energetico Samsung.**
Passi: Risparmio energetico acceso in carica durante la notte, anche con "limita dati in background"; verificare se resta acceso collegato, se i job partono, `isPowerSaveMode`.
Decisione: testo e comportamento del preset Notte.

### Sospensione, permessi, riavvii

**T-06 — Sleeping, Deep sleeping, Mai in sospensione.**
Passi: aggiungere l'app di prova alle liste con la schermata Samsung; osservare bucket, `isBackgroundRestricted`, esecuzione dei job periodici, notifiche; verificare il deep link `activity_type` 0/1/2 e se "Mai in sospensione" basta con batteria Ottimizzata.
Decisione: contenuto del passo 10 e segnali di Diagnostica.

**T-07 — Hibernation.**
Passi: abbassare `auto_revoke_unused_threshold_millis2` e lanciare il job del Permission Controller (procedura della documentazione [S25]); in aggiunta rileggere dopo il 15/10/2026 lo stato di OneDrive sull'S25 (supera i 90 giorni); osservare quali permessi vengono ritirati (`READ_MEDIA_*`, `ACCESS_MEDIA_LOCATION`, notifiche), il destino dei job, `getUnusedAppRestrictionsStatus`, il testo italiano della voce Samsung.
Decisione: ripianificazione all'avvio e testo della guida.

**T-08 — Riavvio, primo sblocco, riavvio per inattività.**
Passi: riavviare con PIN impostato senza sbloccare; alle 03:00 verificare che nulla giri; ricevitore `LOCKED_BOOT_COMPLETED` con storage DE: può scrivere? può postare una notifica?; sbloccare e misurare quanto impiega WorkManager a riprendere; programmare il Riavvio automatico dentro la fascia notte; controllare la voce "Riavvio per inattività" (sull'S25 `inactivity_restart=1`; sul Tab S9 la chiave non esiste, verificare nell'interfaccia) (non accelerabile: osservazione in 3 giorni).
Decisione: stato "In attesa di sblocco", componenti Direct Boot (probabilmente nessuno).

### MediaStore

**T-09 — Cestino Samsung Gallery e cestino MediaStore.**
Passi con foto di prova: (a) cestinare in Samsung Gallery e osservare `ContentObserver`, riga in `files`, voce in `deleted_files`, contenuto di `Android/.Trash`, file `.trashed-*`; (b) ripristinare dalla Galleria (stesso `_ID`? `generation_added`? percorso?); (c) svuotare il cestino; (d) cestinare con `createTrashRequest` e vedere se compare nel cestino della Galleria e se si ripristina da lì; (e) `IS_TRASHED`/`DATE_EXPIRES` e durata reale; (f) richiesta con 500 URI.
Decisione: finestra di tolleranza, testo di E-P11, necessità di `MANAGE_MEDIA`.

**T-10 — Stabilità di `_ID` e versione.**
Passi: salvare (`_ID`, generazione, `getVersion()`) per 200 media; riavvio; aggiornamento Mainline/OTA quando capita; **Smart Switch** verso l'S25 (cosa cambia: `_ID`, `DATE_ADDED`, generazione, `owner_package_name`); OneDrive "Libera spazio" e ri-scaricamento; modifica *in place* con "Salva" nella Galleria (stesso `_ID`? dimensione, `DATE_MODIFIED`, `generation_modified`), "Salva copia", "Ripristina originale".
Decisione: algoritmo di riassociazione (4.3, punti 1-3).

**T-11 — Oscuramento EXIF in lettura.**
Passi: foto con GPS; calcolare SHA-256 sul dispositivo (`adb shell sha256sum`) e nell'app leggendo (a) `openInputStream(uri)` senza permesso, (b) con `ACCESS_MEDIA_LOCATION` senza `setRequireOriginal`, (c) con entrambi, (d) con accesso parziale; confronto byte e metadati.
Decisione: lettura obbligatoria con permesso + `setRequireOriginal`; definizione dell'hash di riferimento.

**T-12 — Accesso parziale.**
Passi: concedere "foto selezionate"; far girare il job notturno; terminare l'app; riavviare; attendere 24 h; verificare cosa vede il job e che nessun item venga marcato come cancellato; `QUERY_ARG_LATEST_SELECTION_ONLY`; effetto della preselezione di Android 16.
Decisione: testo della modalità limitata e blocco delle deduzioni di cancellazione.

### Integrazioni

**T-14 — Handler GET_CONTENT/PICK e DocumentsProvider.**
Passi: dichiarare handler e provider; verificare la comparsa in Gmail ("Allega file"), WhatsApp ("Documento", e "Galleria" che non dovrebbe), Telegram, Google Messages, Samsung Messages e nel selettore di sistema; provare `take_over_get_content=true` come simulazione del takeover; URI in streaming contro download in cache; tempi di lettura da parte di Gmail/WhatsApp; ripetere su S25.
Decisione: forma della risposta per media solo NAS (5.3, punto 3).

**T-15 — Ricezione di condivisioni.**
Passi: `SEND`/`SEND_MULTIPLE` da Samsung Gallery, I miei file, Quick Share, Chrome, WhatsApp; 100 immagini e un video da 1 GB; durata del permesso di lettura (attività chiusa, processo ucciso); simulare la restrizione di Android 17/18 sui permessi impliciti.
Decisione: strategia di copia/streaming (5.3, punto 5).

**T-16 — "Modifica con…".**
Passi: (a) `ACTION_VIEW` su Samsung Gallery con URI MediaStore, modifica e "Salva"/"Salva copia"/"Ripristina originale", osservare il MediaStore; (b) `ACTION_EDIT` con `FileProvider` (lettura+scrittura) verso Google Foto e OneDrive; (c) verificare le colonne `ORIGINAL_DOCUMENT_ID`/`DOCUMENT_ID`/`INSTANCE_ID`; ripetere su S25 (dove Samsung Photo Editor potrebbe rispondere ad `ACTION_EDIT`).
Decisione: algoritmo di rilevamento delle versioni (5.3, punto 6).

### Rete e profili

**T-17 — Lettura di `LinkProperties` senza posizione.**
Passi: app con solo `ACCESS_NETWORK_STATE`; registrare dominio, server DHCP, gateway, DNS e prefissi IPv6 su: Wi-Fi di casa; Wi-Fi di casa con WireGuard acceso; Wi-Fi di un amico con FRITZ!Box; hotspot di un altro telefono; dati mobili con VPN; dopo un riavvio della FRITZ!Box e dopo un aggiornamento del suo firmware (il prefisso ULA resta?). Controllare che il Wi-Fi sottostante sia visibile con la VPN attiva.
Decisione: formula dell'impronta di casa (7.3, punto 1).

**T-18 — VPN non bypassabile.**
Passi: tunnel WireGuard attivo in casa: (a) connessione al NAS con `Network.bindSocket(wifi)`, misurando se il traffico passa dal tunnel (latenza, contatori WireGuard, FRITZ!Box); (b) app esclusa dal tunnel; (c) spegnimento e accensione con l'intento di controllo di WireGuard (permessi, impostazione da abilitare, latenza); (d) comportamento al cambio rete.
Decisione: gestione VPN (2.5) e testo della guida.

**T-19 — Permesso di rete locale.**
Passi (eseguibili direttamente sull'S25, già su Android 17, e sul Tab S9 per il confronto con Android 16): app di prova con target 36 e 37; confronto fra concessione implicita (target 36) e richiesta a runtime (target 37); `am compat enable RESTRICT_LOCAL_NETWORK`; TCP 445 verso il NAS, UDP broadcast Wake-on-LAN, mDNS, DNS verso la FRITZ!Box (esente); con e senza `NEARBY_WIFI_DEVICES`/`ACCESS_LOCAL_NETWORK`; stesso traffico *dentro* il tunnel WireGuard verso un indirizzo privato; messaggi d'errore e tempi di timeout.
Decisione: passo di onboarding, stato di Diagnostica, passaggio a target 37.

**T-20 — Profili secondari.**
Passi (solo sul Tab S9: l'S25 non supporta utenti commutabili e ha solo profili fermi): installare l'app in due utenti, con NAS di prova; (a) utente 0 in primo piano, backup nell'utente 10: gira? per quanto tempo dopo il cambio utente?; (b) riavvio: l'utente 10 resta in BFU finché non inserisce il PIN?; (c) `dumpsys activity users` prima/dopo; (d) WireGuard in entrambi, consenso VPN per utente; (e) MediaStore e Samsung Gallery per utente; (f) effetto del parametro dei 26 h.
Decisione: stime e testi per i profili non attivi (2.8).

### Android 17 e trasferimento dati

**T-23 — Limiti di memoria di Android 17.**
Passi: sull'S25 caricare il modello di 0.3 e far girare il calcolo AI per 30 minuti; `adb shell am memory-limiter status`; con il limitatore attivato in prova (`am memory-limiter manual <pid> <limite>`) registrare `ApplicationExitInfo` (descrizione `MemoryLimiter:AnonSwap`) e il comportamento di recupero; `ProfilingTrigger.TRIGGER_TYPE_ANOMALY` per le heap dump.
Decisione: tetto di RAM del calcolo AI e ripresa dopo una chiusura.

**T-24 — App di prova con target 37.**
Passi: stessa app compilata con target 36 e 37 sull'S25: `ACCESS_LOCAL_NETWORK` a runtime e comportamento di SMB 445, Wake-on-LAN e DNS; caricamento di librerie native estratte (sola lettura obbligatoria); campi `static final`; PendingIntent con `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`; finestre libere su schermi larghi (sul tablet con Android 16 il comportamento del target 37 si può solo anticipare); `StrictMode.detectImplicitUriPermissionGrant`.
Decisione: quando e come alzare il target a 37.

**T-25 — Trasferimento dispositivo a dispositivo e Smart Switch.**
Passi: app di prova con dati e chiave Keystore, con `allowBackup="false"` e con `dataExtractionRules` che escludono tutto in `<device-transfer>`; Smart Switch dal Tab S9 all'S25 e viceversa; verificare quali file, preferenze e database arrivano e se le credenziali restano.
Decisione: configurazione del manifest (1.5) e testo per E-O8.

### Resoconto delle prove guidate (Fase 5B, P0-B)

**Stato: da fare con il telefono.** Le prove T-09, T-14, T-15 e T-16 sono pronte come **schermata di debug** dell'app (non eseguite: P0-B non usa il telefono). Si trova **solo nella build debug**: *Impostazioni → Sviluppo → «Prove guidate T-09, T-14, T-15, T-16»* (classi `FieldProbe*` in `app/src/debug/.../debugtools/probes/`, componenti nel manifest di debug; nella release non esistono, né nel manifest né nel dex: lo controlla `tools/check.ps1`). Nota: la richiesta parlava di «Diagnostica → Sviluppo», ma la voce «Sviluppo» sta già sotto Impostazioni (`DeveloperTools`): la schermata è lì.

*Come si fa.* Sul telefono (S25, poi Tab S9):
1. `tools\chaos\s25.ps1 -Mode backup -Step seed` per riempire `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (servono almeno 500 righe per la prova (f) di T-09: di serie la semina ne produce circa 300, quindi `-Photos 520`; vedi i punti aperti in `docs/decisioni.md`, «Fase 5B: P0-B»). Lo script accetta solo `-Mode move|backup`: `-Mode seed` non esiste (D-5 delle prove sul campo).
2. Installare la build debug (a cura dell'utente, non da una sessione di Claude) e dare i permessi sui media.
3. Aprire la schermata e seguire i passi numerati di ogni sezione. Prima «Controlla l'ambiente».
4. In fondo: «Aggiorna», poi «Copia» (appunti) o «Salva su file» (`field-probe-report.txt` nella cartella `files` dell'app, da prendere con `adb pull`) e incollare il resoconto qui sotto.

**Recinzione.** Le prove leggono e toccano **solo** `DCIM/mcg-prova/` e `Pictures/mcg-prova/`: una sola funzione legge il MediaStore (`FieldProbePhone.rows()`, selezione per percorso uguale e controllo doppio sul risultato) e nessuna richiesta di sistema nomina una riga fuori da lì (`FieldProbeFlowTest`). Niente si cancella per sempre: il cestino di Android tiene 30 giorni e «Ripristina» rimette le foto. La riscrittura di T-09 usa gli **stessi byte** della foto. La modifica di T-16 (b) lavora su una **copia in cache**.

**Come si legge il resoconto.** Una riga `chiave=numero` per misura, **solo numeri**: mai nomi di file, percorsi, share, account, id di media o impronte. Le app che chiamano sono un codice: 1 Gmail, 2 WhatsApp, 3 Telegram, 4 Google Messaggi, 5 Samsung Messaggi, 6 OneDrive, 7 Google Drive, 8 I miei file, 9 Chrome, 10 Samsung Gallery, 11 Google Foto, 12 Quick Share, 13 DocumentsUI, 99 altra. Le misure ripetute hanno un indice finale (`t09.diff_missing.2` è il secondo «Confronta»). Una riga con una chiave sconosciuta o con un valore che non è un numero non entra mai (`FieldProbeReportTest`).

| Chiave | Cosa dice (e cosa decide) |
|---|---|
| `env.sdk`, `env.rows`, `env.rows_images`, `env.rows_videos` | Versione di Android e righe di prova. Con meno di 500 righe la prova (f) di T-09 non è piena. |
| `t09.base_rows` | Righe nella fotografia di riferimento. |
| `t09.diff_missing.N`, `t09.diff_new.N`, `t09.diff_trashed.N`, `t09.diff_same_id_present.N`, `t09.diff_other_id_back.N` | Dopo cestinare, ripristinare o svuotare in Samsung Gallery: righe sparite, righe con `_ID` nuovo, righe cestinate (`IS_TRASHED`), righe rimaste con lo stesso `_ID` e la stessa impronta, e file **tornati con un `_ID` diverso**. Decide la finestra di tolleranza e il testo di E-P11 (T-09 a, b, c). |
| `t09.diff_generation_delta.N`, `t09.diff_deleted_files.N` | Di quanto è salita la generazione del volume e quante voci di `deleted_files` riguardano le righe del riferimento (-1 = tabella non leggibile). |
| `t09.trash_requested`, `t09.trash_result`, `t09.trash_is_trashed`, `t09.trash_visible_default`, `t09.trash_expires_days_min`, `t09.trash_expires_days_max`, `t09.trash_in_samsung` | `createTrashRequest` su 5 foto: esito del dialogo (`-1` accettato, `0` rifiutato), quante risultano cestinate, quante restano visibili senza `MATCH_TRASHED`, **giorni alla scadenza** (atteso 30) e se il cestino di Samsung Gallery le mostra (risposta dell'utente, 1 o 0). T-09 d, e. |
| `t09.untrash_requested`, `t09.untrash_result`, `t09.untrash_is_trashed`, `t09.untrash_same_ids` | Il ripristino con `createTrashRequest(trash = false)`: le righe tornano con gli stessi `_ID`? |
| `t09.trash_out_of_folders`, `t09.trash_in_gallery_trash`, `t09.trash_elsewhere_is_trashed`, `t09.trash_gone` | D-3: dopo il cestino dell'app, quante righe sono uscite dalle cartelle di prova; di quelle quante l'app vede ancora con lo stesso `_ID` sotto `Android/.Trash/`, quante con `IS_TRASHED = 1` fuori posto, quante non vede piu'. |
| `t09.untrash_in_gallery`, `t09.untrash_back_same_id` | D-3: «Ripristina» con le foto nel Cestino di Galleria (l'app non le puo' ripristinare: lo dice) e quante sono gia' tornate al loro posto con lo stesso `_ID` dopo Galleria → Cestino → Ripristina. |
| `t09.many_requested`, `t09.many_result`, `t09.many_is_trashed` | La richiesta con tutte le righe di prova (al massimo 500): esito e quante risultano cestinate. T-09 f. Decide se serve `MANAGE_MEDIA` (atteso di no). |
| `t09.write_dialog`, `t09.write_code`, `t09.write_size_before`, `t09.write_size_after`, `t09.write_generation_delta`, `t09.write_ms` | `createWriteRequest` su una foto e riscrittura con gli stessi byte: dialogo (-1 o 0), codice (0 riscritta e riletta, 1 nessun permesso, 2 sparita, 3 file diverso, 4 fallita e ripristinata, 5 fallita **non** ripristinata, 6 foto non leggibile), dimensioni, generazione, millisecondi. D19, E-E10. |
| `t14.handlers_get_content`, `t14.handlers_pick`, `t14.own_handler` | Quante app gestiscono «scegli un file» (con le `<queries>` del manifest di debug) e quante sono le nostre. |
| `t14.picker_launched`, `t14.picker_multiple`, `t14.picker_mime`, `t14.picker_caller`, `t14.picker_action`, `t14.form`, `t14.latency_ms`, `t14.count` | Chi ha aperto il selettore di prova e con quale azione (1 `GET_CONTENT`, 2 `PICK`), che tipo chiedeva (1 immagini, 2 video, 3 qualunque, 9 altro), se in selezione multipla, e con quali impostazioni abbiamo risposto (forma 1 diretto dal MediaStore, 2 a richiesta, 3 copia in cache prima). |
| `t14.serve_opens`, `t14.serve_reads`, `t14.serve_bytes`, `t14.serve_first_read_ms`, `t14.serve_duration_ms`, `t14.serve_backward_seeks`, `t14.serve_reached_eof`, `t14.serve_copy_ms`, `t14.serve_queries`, `t14.serve_type_queries` | Come l'altra app **legge** il file consegnato: aperture, letture, byte, tempo alla prima lettura, durata, salti all'indietro, se arriva in fondo, durata della copia (forma 3), interrogazioni di nome e dimensione e di tipo. Decide streaming contro «scarica prima» (5.3, punto 3). |
| `t14.docs_root_queries`, `t14.docs_child_queries`, `t14.docs_doc_queries`, `t14.docs_opens`, `t14.docs_caller` | Il provider di documenti di prova: quante volte DocumentsUI chiede radici, figli, documenti, aperture, e chi. Dice se Gmail, WhatsApp e Telegram mostrano la radice «MCG prova». |
| `t15.handlers_send`, `t15.handlers_send_multiple`, `t15.own_handler` | Quante app ricevono `SEND` e `SEND_MULTIPLE` di immagini, e se la nostra c'è. |
| `t15.received`, `t15.action`, `t15.streams`, `t15.clip_items`, `t15.grant_flag`, `t15.scheme_content`, `t15.scheme_file`, `t15.scheme_other`, `t15.perm_granted`, `t15.caller` | Cosa arriva: quante condivisioni, con quale azione (1 `SEND`, 2 `SEND_MULTIPLE`), quanti flussi, quanti elementi nella `ClipData`, se il sistema ha dato il permesso di lettura, lo schema degli indirizzi (`content`, `file`, altro), se il permesso risulta concesso e chi ha condiviso. Decide il rifiuto di `file://` e la strategia di copia (C18, 5.3 punto 5). |
| `t15.open_ok`, `t15.open_fail`, `t15.bytes`, `t15.open_ms_avg`, `t15.read_ms_total`, `t15.closed_first` | Aperture e letture subito alla ricezione (con «Legge subito») o nessuna (con «Chiude prima»). |
| `t15.reopen_ok`, `t15.reopen_denied`, `t15.reopen_age_s`, `t15.reopen_after_restart` | **Il permesso di lettura dura?** Riprovando dopo aver chiuso l'activity (e dopo la morte del processo): quante condivisioni si aprono ancora e quante no, età della più vecchia, e se il processo è ripartito dalla ricezione (1). Decide se la copia deve essere **subito** (D24). |
| `t16.handlers_edit`, `t16.handlers_view` | Quante app gestiscono `ACTION_EDIT` e `ACTION_VIEW` di immagini. Atteso: Samsung Gallery non risponde a `EDIT`. |
| `t16.view_launched`, `t16.view_same_id.N`, `t16.view_row_changed.N`, `t16.view_rows_new.N`, `t16.view_new_same_taken.N`, `t16.view_size_delta.N`, `t16.view_generation_delta.N` | Dopo «Salva», «Salva copia» e «Ripristina originale» in Samsung Gallery: la stessa riga c'è e ha cambiato impronta? Ci sono righe nuove con la **stessa data di scatto** (E-X3)? Di quanto sono cambiate dimensione e generazione? Decide l'algoritmo di rilevamento delle versioni (5.3, punto 6). |
| `t16.col_original_document_id.N`, `t16.col_document_id.N`, `t16.col_instance_id.N` | Quante righe hanno valorizzate le tre colonne dopo la modifica (T-16 c). |
| `t16.edit_launched`, `t16.edit_result_code`, `t16.edit_result_has_data`, `t16.edit_bytes_changed`, `t16.edit_size_before`, `t16.edit_size_after`, `t16.edit_new_files` | `ACTION_EDIT` con una copia del `FileProvider` in lettura e scrittura (Google Foto, OneDrive): esito, se c'è un risultato, se i **byte della copia sono cambiati**, dimensioni, file nuovi accanto. |

**Il resoconto.** Incollare qui, con data e telefono, un blocco per ogni giro di prove:

```
(nessuna prova ancora eseguita)
```

**Esiti da riportare** nelle tabelle di 4.2 bis, 5.2 bis e nelle decisioni di 4.3 e 5.3 dopo le prove: finestra di tolleranza di E-P11 (T-09 a, b, c), necessità di `MANAGE_MEDIA` (T-09 f), forma della risposta per i media solo-NAS (T-14), strategia di copia delle condivisioni (T-15), algoritmo delle versioni (T-16).

### Fase 6: ricerca (da misurare, 21/10/2026)

**Stato: da misurare.** Tre prove della Fase 6 (`docs/fase6-progetto.md` §5.6, §7) che solo un'app installata sull'S25 e sul Tab S9
può fare. **Nessun esito è stato raccolto e qui non se ne scrive nessuno**: per ciascuna prova stanno scritti cosa si misura, dove
si scrive l'esito e chi la fa. Valgono le regole del resto della sezione: i comandi `adb` che *scrivono* flag o impostazioni sono
per il dispositivo di prova e a cura dell'utente.

**F62 — Limitatore di memoria di Android 17 durante il calcolo AI** (estende T-23, E-A16).
- *Cosa si misura.* Sull'S25 (Android 17), con la build debug e l'account di prova: il calcolo AI per 30 minuti con il 440M su GPU
  (PSS stimato circa 850 MB; il motore delle query in Cerca, 270M su CPU, circa 330 MB) e `adb shell am memory-limiter status`; poi,
  con il limitatore attivato in prova (`am memory-limiter manual <pid> <limite>`), il motivo registrato da `ApplicationExitInfo`
  (descrizione `MemoryLimiter:AnonSwap`) e il comportamento dopo la chiusura: ripresa dal media successivo senza ricalcolo e codice
  `AI_KILLED_MEMORY` in Diagnostica. Sul Tab S9 (Android 16) lo stesso calcolo, per il confronto della memoria.
- *Cosa decide.* Il tetto di RAM del calcolo AI e la ripresa dopo una chiusura; se il limitatore interrompe troppo, le uscite del
  progetto: lotti più piccoli su CPU oppure un processo separato `:ai` (componente nuovo, da approvare).
- *Dove si scrive l'esito.* Qui, accanto a T-23 (sezione «Android 17 e trasferimento dati»), e in `docs/decisioni.md` con i numeri;
  la riga F62 di `docs/fase6-progetto.md` §5.6 si segna come superata.
- *Chi la fa.* L'utente, sull'S25 di prova (P5 prepara il calcolo e la ripresa; P10 la prova delle interruzioni). Esito: **da misurare**.

**GPU e OpenCL su S25 e Tab S9** (D3 del progetto della Fase 6, E-A12).
- *Cosa si misura.* Che la dichiarazione facoltativa delle due librerie native di sistema (`uses-native-library` per `libOpenCL.so` e
  `libvndksupport.so`, `required="false"`) basti a far partire la GPU di LiteRT-LM sull'S25 (Android 17) e sul Tab S9 (Android 16):
  backend GPU inizializzato; coseno GPU–CPU di almeno 0,99 su 20 immagini e 20 testi sintetici; foto al secondo (p50 e p95), PSS,
  avvio a freddo e a caldo, calo col caldo; e, togliendo o rendendo inutilizzabili le librerie in prova, che l'installazione riesca e
  il ripiego su CPU funzioni (`AI_GPU_FALLBACK`). Sul Tab S9 non c'è nessuna misura della Fase 0: il confronto salvato 270M contro
  440M va fatto anche lì.
- *Cosa decide.* Se le due `uses-native-library` restano come sono o servono altre; i valori di partenza di `aiItemsPerSecond` e
  `ocrSecondsPerItem` in `docs/regole-attivita.md` §6.
- *Dove si scrive l'esito.* Qui e in `docs/decisioni.md` (misure di P4: foto/s, PSS, avvio, verifica dell'hash); F52 del progetto.
- *Chi la fa.* P4 scrive i test strumentati (emulatore, S25, Tab S9, `core-ai/src/androidTest`); l'esecuzione sui due dispositivi è
  dell'utente (sul tablet, il profilo Prova). Esito: **da misurare**.

**Traffico di ML Kit con la lettura del testo spenta, accesa e di nuovo spenta** (F56 e F69, D41; L49 di `docs/sicurezza.md`).
- *Cosa si misura.* Con PCAPdroid sull'S25 (WireGuard spento): (a) installazione nuova con la lettura **spenta**, un'ora d'uso e
  d'indicizzazione: nessun pacchetto verso host di Google, nessuna classe di ML Kit caricata, nessun lavoro di DataTransport
  pianificato (`dumpsys jobscheduler`); (b) lettura **attivata** dopo la pagina di consenso, un'ora di indicizzazione: l'elenco degli
  host e la frequenza degli invii, da confrontare con il testo della pagina (se dice di meno, la pagina si corregge e la versione del
  testo sale); (c) lettura **spenta di nuovo** e telefono riavviato, un'ora: come (a), con la coda di DataTransport vuota
  (`TEXT_READING_OFF_CLEANUP` in Diagnostica). Da ripetere a ogni aggiornamento di ML Kit.
- *Cosa decide.* Se il testo della pagina di attivazione è completo; se serve togliere un altro componente dal manifest fuso; se
  L49 e il registro delle decisioni (`docs/sicurezza.md` §8) restano come scritti.
- *Dove si scrive l'esito.* Qui, in `docs/sicurezza.md` (§8 e checklist della Fase 6) e nel report della prova sul campo; il
  testo approvato della pagina porta una versione.
- *Chi la fa.* L'utente (attiva la lettura sull'S25 di prova e installa PCAPdroid); P4 prepara il test strumentato per la parte (a) e
  (c). Esito: **da misurare**.

Resoconto (da compilare, con data e telefono):

```
(nessuna misura ancora eseguita)
```
