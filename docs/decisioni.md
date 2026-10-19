# Decisioni della Fase 0 (bozza viva)

> Fonti: `inventario-nas.md`, `android.md`, `research/*.md`, misure delle app di prova in `spike/results/`.
> Ogni voce dice **cosa si è deciso**, **perché** e **cosa resta aperto**.

## Rete e NAS
| Tema | Decisione | Perché |
|---|---|---|
| Dialetto SMB | **solo SMB 3.1.1** | negoziato senza problemi dal NAS; smbj non valida la negoziazione per 3.0/3.0.2 (`research/smb-vpn.md`) |
| Firma | **obbligatoria lato client** | il NAS la abilita ma non la richiede (`inventario-nas.md` §3.8) |
| Cifratura SMB3 | **sempre attiva** (AES-128-GCM), verificando a runtime `shouldEncryptData`; se il server non la concede, connessione rifiutata | scelta dell'utente dopo le misure: −25% in lettura, −6% in scrittura |
| Indirizzo NAS | nome `MyCloudEX2Ultra.fritz.box` con IP memorizzato; mai cambiato in automatico (E-R7) | DHCP del FRITZ!Box; prenotazione da verificare |
| Flussi paralleli | **4 flussi** per i trasferimenti di massa | 2,2 volte il singolo flusso; il limite è la CPU del NAS |
| Copia lato server | **sì, anche tra share** (124–148 MB/s, hash verificato); tra share serve FSCTL_SRV_COPYCHUNK diretto | spostamenti e condivisioni senza far passare i byte dal telefono |
| Orario NAS | **sempre acceso** (12/10: l'utente ha disattivato la pianificazione dell'alimentazione; prima era 08:00–22:00) | fascia "notte" del preset Notte: 23–07 di default, derivata da `nasPower` nella configurazione di famiglia |
| Wake-on-LAN | **non verificabile** con il NAS sempre acceso: implementato dietro `FeatureFlags.wol = false` (Fase 2.6), prova rimandata | report contrastanti nei forum |
| Accesso da fuori | **WireGuard del FRITZ!Box solo in IPv6** (CGNAT su IPv4, accettato dall'utente) | l'app deve spiegare il caso "rete solo IPv4" (nuovo edge case) |
| VPN in casa | **spenta dall'app quando riconosce casa** (controllo WireGuard opzionale) | WireGuard non è aggirabile (`allowBypass` assente) |
| Riconoscere "casa" senza posizione | più indizi: **prefisso ULA IPv6 del FRITZ!Box** + gateway + dominio `fritz.box` + NAS raggiungibile | `fritz.box` e il gateway sono uguali in tutte le FRITZ!Box; l'ULA è specifico |

## Android / One UI (da `android.md`)
| Tema | Decisione |
|---|---|
| "In carica" | alimentazione collegata (`EXTRA_PLUGGED`), non lo stato di carica (Protezione batteria) |
| Lavori lunghi | a tappe di job normali; "Fai ora" come **user-initiated data transfer job**; FGS `dataSync`/`mediaProcessing` solo con la batteria "Illimitata" |
| Identità dei media del telefono | per impronta; `_ID` MediaStore solo come cache |
| Lettura foto per il backup | **`ACCESS_MEDIA_LOCATION` + `setRequireOriginal` obbligatori**, altrimenti GPS oscurato e hash diverso |
| Cestino Samsung Gallery | non riconoscibile da `IS_TRASHED`: stato "assente dal telefono" con tolleranza, mai "persa" subito |
| Integrazione "scegli foto" | il selettore di sistema è chiuso (solo Google Foto): handler `GET_CONTENT`/`PICK` + DocumentsProvider + Condividi |
| "Modifica con…" | Samsung Gallery non risponde ad `ACTION_EDIT`: apertura con `ACTION_VIEW` e rilevamento dell'esito dal MediaStore |
| Android 17 | l'S25 è già su Android 17 (SDK 37). **targetSdk 36 per ora**: in questo caso `ACCESS_LOCAL_NETWORK` è concesso implicitamente. Onboarding e Diagnostica del permesso vanno costruiti subito, per il passaggio a target 37 |
| Backup e trasferimento | `allowBackup=false` **più** esclusione esplicita in `dataExtractionRules` → `<device-transfer>` (altrimenti Smart Switch potrebbe copiare i dati) |
| Rete in background | il NAS Probe sull'S25 ha perso la connessione (`connection abort`) appena è finito in background: i trasferimenti vanno **solo** dentro lavori gestiti (WorkManager/UIDT/FGS), mai in coroutine legate a una schermata |

## AI (da `research/embeddinggemma2.md` e `research/ocr-volti.md`; *da confermare con Embed Probe*)
| Tema | Orientamento |
|---|---|
| Runtime | `litertlm-android` (`EmbeddingEngine`), senza MediaPipe |
| Modelli | **440M testo+immagini** per gli indicizzatori; **270M solo testo** per chi fa solo query, se i vettori coincidono |
| NPU (misurata 12/10, S25) | **Funziona**: immagine 79 ms (70 token, ~12 foto/s), testo 6,6 ms, mezzo core CPU; GPU ~156 ms, CPU ~680 ms. Configurazione: testo e visione su NPU, `qnn-runtime` 2.50.0, **senza** `libLiteRtCompilerPlugin_Qualcomm.so` (il JIT rompeva il lookup testuale). **I vettori NPU differiscono da quelli CPU/GPU** (coseno 0,78–0,89), ma l'ordine dei risultati resta coerente nei test sintetici. La compatibilità tra indici e query di backend diversi è **in misura** (confronto incrociato): decide come funziona l'indice condiviso tra dispositivi con chip diversi |
| Acceleratore | **Superato dalla decisione "spazio standard float" (vedi sotto): GPU per indicizzare, CPU per le query.** Storico: orientamento verso la NPU. Sull'S25 il pacchetto 440M generico dichiara la parte visiva **solo su CPU**: 1,9 foto/s complessive, −40% dopo 150 foto per il calore, ~1 GB di RAM. L'utente preferisce la NPU (più veloce, meno impatto sull'uso del telefono, come in Edge Gallery). Misura NPU in corso con Embed Probe v2 |
| minSdk | **29**, confermato: la NPU è esclusa, quindi non serve 31 (applicato nella Fase 1.1) |
| Anteprima di analisi | ~768 px per gli embedding; l'OCR potrebbe chiederne una più grande: *da misurare* |
| OCR | ML Kit Text Recognition v2; **dubbio telemetria** (metriche d'uso verso Google anche nella versione bundled), alternativa PaddleOCR (Apache-2.0): *da misurare* |
| Volti | rilevamento YuNet (MIT) + embedding SFace (Apache-2.0); esclusi InsightFace/EdgeFace (non commerciali) |

## EmbeddingGemma 2: prova con 700 foto reali sull'S25 (12/10, scollegato dal caricatore, 70 token, 768 px)
| | NPU (440M precompilato SM8750) | GPU (440M generico) |
|---|---|---|
| Solo embedding | **11,0 foto/s** (p50 91 ms) | 4,4 foto/s (p50 183 ms) |
| Totale con decodifica dagli originali | 5,3 foto/s | 2,8 foto/s |
| Stato termico max / temperatura batteria | 1 / +1 °C | **2 / +5 °C** |
| Calo di velocità per il calore | 3–15% | 26% |
| Batteria ogni 1000 foto | **~1,3–2%** | ~4% |
| RAM (PSS di picco) | 510–600 MB | 850 MB |
| Query di testo | 7 ms | 29 ms (CPU 440M 47 ms, CPU 270M 56 ms) |

**Compatibilità tra backend (30 query, sovrapposizione dei primi 10 risultati rispetto a "stesso backend"):**
- **Indice GPU + query CPU 440M oppure CPU 270M: 98% (Spearman 1,00).** Lo spazio "float" è unico: qualunque dispositivo, anche senza NPU, può cercare con il piccolo 270M su CPU.
- Indice NPU + query GPU/CPU/270M: 65% (minimo 40%). Indice GPU + query NPU: 65%.
- Pipeline NPU contro pipeline GPU: solo il **47–53%** dei primi 10/20 risultati in comune. La NPU produce classifiche sensibilmente diverse dal modello di riferimento float; non è ancora dimostrato se peggiori.

**Implicazione:** l'indice condiviso deve usare **un solo spazio vettoriale per tutta la famiglia**. Lo spazio float (GPU/CPU) è portabile su tutti i dispositivi. Lo spazio NPU è legato al modello compilato per uno specifico chip.

### ✅ DECISO (utente, 12/10): spazio **standard float**
- **Indicizzatori:** 440M generico (370 MiB) su **GPU** (ripiego CPU). ~2,8 foto/s con la decodifica, quindi ~2 ore per ~20 mila foto la prima volta, di notte in carica; poi pochi secondi al giorno.
- **Ricerca su qualunque dispositivo:** 270M solo testo (157 MiB) su **CPU** (~55 ms per query), equivalente al 440M (98% degli stessi risultati).
- **Niente NPU né librerie Qualcomm nell'app**: APK più leggero e nessuna licenza Qualcomm. La NPU resta un'opzione futura solo se arriverà una variante compatibile con lo spazio float (es. il servizio ML Kit annunciato).
- Ogni vettore porta l'etichetta dello spazio (`embeddinggemma2-float-v1` + dimensione + precisione). I vettori di spazi diversi non si mescolano mai (E-I4).
- **Token visivi: 70** (usati in tutte le misure), da rivalutare con 140 nella Fase 6 se la qualità lo richiede.

## Ricerca: requisito emerso dalle prove (utente, 12/10)
- Sulle 200 foto di prova i punteggi sono molto piatti (0,65–0,70) e i formati di prefisso della query non cambiano il risultato. Le probabili cause sono pochi soggetti nel campione (es. 2 cani su 200) e la parte visiva eseguita su CPU nel pacchetto generico: va riverificato con 700 foto e con la NPU.
- **Requisito UX (da integrare nella Fase 6.3/6.4):** la ricerca mostra di default **solo i risultati di cui è davvero convinta** (soglia calibrata, relativa alla distribuzione dei punteggi della query) e offre un pulsante **"Mostra anche risultati meno probabili"**. I dettagli si definiscono nella Fase 6.

## Sicurezza della rete di casa (consigliate, **non ancora decise dall'utente**)
1. `NAS` e `Public`: "Pubblico: OFF".
2. `test`: senza accesso a `NAS`/`Public`, eliminato a fine Fase 0.
3. Wi-Fi: WPA2+WPA3, WPS spento.
4. IoT nella rete ospiti.
5. Accesso cloud WD spento.
6. Cambiare la password admin del NAS (è passata in chat).
7. Backup esterno del NAS (app WD "USB Backups"): non previsto per scelta.

## Dubbi grafici per l'utente (Fase 2)
- P6: intensità dello sfondo colorato di ogni passo; stessa illustrazione per benvenuto ed errori a pagina intera; stime del preset su una libreria tipica (15.000 foto + 300 video); consiglio "tablet solo Preparatore".
- P7: su tablet la colonna è a 760 dp su 1280 (pulita ma vuota ai lati): vuoi due colonne? Il testo del segmento selezionato è lilla chiaro e poco leggibile (viene dall'engine). Il testo sul pulsante Ametista pieno è scuro.

## Da confermare con l'utente a fine Fase 1 (dalla 1.6, motore delle regole)
1. Il calcolo AI è un'attività "pesante". Senza NAS o rete lavora comunque sul buffer locale.
2. Tetto dati mobili predefinito: 1 GB al mese (10 GB con "Massimo").
3. Con l'app in primo piano la sync dell'indice è consentita anche sui dati mobili, entro il tetto.
4. Termica: SEVERE sospende il background, CRITICAL ferma anche "Fai ora".
5. Le stime di consumo radio e di batteria per GB sono ipotesi, da calibrare con l'uso reale.
6. Fascia "notte" predefinita 23–07: con il NAS acceso solo 08–22, i lavori notturni che servono il NAS non partirebbero. Va ricavata dall'orario del NAS (proposta 14) nella Fase 2, oppure l'utente allunga l'orario del NAS.

## Da confermare con l'utente a fine Fase 1 (dalla 1.3, modello dei dati)
1. Tolleranza prima di considerare sparita una posizione: 60 giorni sul telefono, 30 sul NAS.
2. Il "Salva" di Samsung Gallery che crea una riga MediaStore nuova si riconosce dalla stessa data di scatto (da verificare sul dispositivo, T-16).
3. I file dell'area comune senza autore noto non hanno proprietario, quindi per loro non c'è l'obbligo di una copia privata.

## Da confermare con l'utente a fine Fase 1 (dalla 1.4, fiducia: `docs/formato-nas.md` §13; la prima opzione è la consigliata)
1. Kit di recupero: file sul NAS + codice su carta / anche il file come QR su un altro foglio / tutto in cassaforte.
2. PIN o impronta a ogni uso della chiave di famiglia (sì/no).
3. Segnalazione "perso" anche dal proprietario del dispositivo (come nel piano) / solo dall'admin.
4. Dati dei dispositivi revocati: scartati / conservati (sconsigliato).
5. Durata massima delle prenotazioni: 1 ora.
6. Attestazione hardware della chiave al momento dell'approvazione (sì/no).
7. Runner dei test strumentali: `androidx.test:runner` (aggiunto dalla 1.5, quindi risolto).

## Residui della revisione di `core-trust` da chiudere nel pacchetto P3 della Fase 2 (che riapre `core-trust`)
- **N2 parziale**: con la cartella del registro inondata si può ripubblicare in un buco senza allarme.
- **R2 (basso)**: un ramo che contiene una rotazione non si unisce né si rifiuta, e l'admin resta bloccato fino al reset.
- **R3 (basso)**: un dispositivo approvato in due rami con account diversi diventa irrevocabile senza reset.
- **R4 (basso)**: un commit legge fino a 1025 file (~256 MiB). Serve un tetto più stretto.
- **M3**: dipende dalla decisione 2 dell'utente (PIN o impronta a ogni uso della chiave di famiglia).

## Note per il pacchetto P9 (integrazione) dalla Fase 2 — checklist chiusa dal P9 (13/10)
Le implementazioni vere sono collegate in `:app` da `graph/AppGraph.kt` (un grafo per processo, lo stesso in debug, in
release e nei test d'integrazione) esposto a Hilt da `di/GraphModule.kt`; i null-object e lo stato finto della build debug
non sono più legati. Le modifiche ai contratti degli altri moduli sono elencate in fondo.
- [x] P3: porte di registro e ciclo di vita collegate (`TrustEngine`, `NasRegistryService`, `RegistryWriter`, `DeviceStatusBook`,
  `DefaultTrustGuard`, `DeviceSelfWipe` con `FileWipeJournal`, `KitAdminRecovery`, `NasFamilyReset`, `NasLostReporting`,
  `TrustDiagnostics`). `LocalDataEraser` = `graph/AppLocalDataEraser`: svuota onboarding, account, impronta di casa, controllo
  VPN, battito (attraverso le loro API: DataStore non tollera file tolti da sotto), cache, database, miniature; **non** tocca
  `wipe-journal` (verificato dal test d'integrazione). `recoveryCodeForDisplay` ora è nel contratto `FamilyReset` e la pagina
  Reimposta (P7) mostra il codice del kit nuovo e lo fa riscrivere. Recupero senza QR di stato: l'ancora viene dal nome del file e
  la Diagnostica lo dice (`TRUST_NO_MIN_VERSION`, ora con titolo e "cosa fare" del catalogo).
- [x] P5: `QrCodec` = `ZxingQrCodec`; codec di invito, chiave e stato = `InviteCodec`, `DeviceKeyCodec`, `FamilyStateCodec`.
- [x] P1: `SmbNasSessionManager` (account dall'archivio di P4, `deviceId` dalla chiave, candidato di casa dell'onboarding),
  `SmbConnectionTester`, `SmbShareProbe`, `SmbNasHousekeeping`, `NasStatusMonitor`, interruttore persistente
  (`DataStoreBreakerStore` + `AuthBreakerRegistry`, con `AccountAuthBreaker` per chi non conosce l'account), `CredentialVault` →
  `NasCredentialSource`. Il cancello di P2 nega già il percorso LAN con la rete locale negata. `invalidate(PROCESS_STOPPED)`
  quando l'app va in background senza operazioni in corso (`process/ProcessWatcher`). **Da fare sul campo**: F11 (release con R8)
  e l'NTP del NAS: la NEGOTIATE ripetuta dal P9 il 13/10 dà l'orologio del NAS **58,5 s avanti** (53 s il 12/10, quindi
  ~5 s al giorno: l'NTP non lavora); GUID invariato, 3.1.1 con AES-128-GCM offerto.
- [x] P8: `AndroidBackgroundHealthReader`, `ActivityRulesEngine` (con `ComposedSystemStateProvider` sopra
  `EnvironmentDeviceStateSource`, `CombinedNetworkQuality`, `NasStatusMonitor`, ruoli dal passo 7, `AppContextSignal` del
  processo), `TrafficLedger` (DataStore), `ConnectionTestInputSource` (account e area comune), `HeartbeatSource` (battito di
  `TrustSyncWorker`). I codici dei contributori di rete e fiducia non erano nel catalogo di P8 (23 su 34): aggiunti a `DiagCodes`
  come gli **stessi oggetti** dei moduli, con voci che riusano titolo e "cosa fare" equivalenti (10 testi nuovi);
  `DiagnosticsWiringTest` verifica che non ne manchi nessuno. `ExportValue.Enum.restore` accetta solo la forma di un enum
  dell'app (tipo `UpperCamel`, costante `MAIUSCOLO`): un evento manomesso si scarta già alla rilettura.
- [x] P4: `NasAccountStore` collegato alla sessione (l'account salvato al passo 3 è quello della sessione), `OnboardingPorts`,
  `DefaultOnboardingCoordinator`, `DefaultJoinFlow`, `DefaultFamilySetupRepository` (dietro un cancello `Loading` finché all'avvio
  non sono letti il diario dell'azzeramento e lo stato fidato: anche offline lo stato è giusto), `ApprovalWatcher` (la pagina
  d'attesa della radice lo raccoglie solo mentre è visibile, `repeatOnLifecycle(STARTED)`).
- [x] P6: "Continua più tardi" (E-O1) chiede al coordinatore di rimandare il passo corrente con tutti quelli del NAS (ora anche
  ai passi 3, 4 e 12, non solo al 2) e prosegue con i passi locali; il riepilogo, finché restano passi del NAS, li riprende
  ("Riprendi i passi del NAS"); senza niente da rimandare (ingresso, recupero) l'app va in secondo piano. Ingresso senza QR: lo
  gestisce `DefaultJoinFlow` (P4) con la configurazione firmata dopo l'autenticazione; non provato dal P9 da capo a fondo.
  Ricerca dei kit: `KitAdminRecovery` usa la sessione (o la password digitata), mai il `credentialSlot` del parametro.
- [x] P7: `NasAccountSettings` = `graph/AppNasAccountSettings` (account dall'archivio, password nel Keystore, indirizzo nuovo con
  lo stesso GUID). `AdminRecovery.findKits` riceve la password dell'admin quando l'admin non è l'account del dispositivo: una
  sessione usa e getta (`graph/OneShotForeignShares`, stessi controlli del §3.1, interruttore di quell'account) legge i kit e si
  chiude; il recupero usa i byte già letti. La pagina "Secondo admin" chiede la password solo in quel caso e la dimentica dopo
  una ricerca.
- [x] P2: `EnvironmentDeviceStateSource` per la rete del motore delle regole; `VpnControlConfigStore` per la scelta del passo 11
  (`AppOnboardingChoices`) e per la prova di WireGuard dell'onboarding (che prima non poteva riuscire: il tunnel si accende per
  nome e il nome non era ancora salvato); `NasSessionActivity` = sessione con operazioni in corso; `VpnAutomation.attach` sul
  processo; `onPermissionsChanged` e `refresh` di WireGuard al ritorno in primo piano.
- [x] Residui N2, R2, R3, R4: chiusi dal P3. M3 resta in attesa della decisione 2.

**Modifiche ai contratti e alle implementazioni di altri moduli fatte dal P9** (ognuna con il suo test):
1. `core-family`: `AdminRecovery.findKits(account, privateShare, password = null)` + porta `ForeignShareAccess` di
   `KitAdminRecovery` (`ForeignKitSearchTest`); la pagina Secondo admin di `feature-settings` chiede la password
   (`AdminRecoveryViewModelTest`).
2. `core-family`: `FamilyReset.recoveryCodeForDisplay()` / `confirmTypedCode()` (con default nel contratto); la pagina Reimposta
   di `feature-settings` li usa.
3. `core-family`: `OnboardingPorts.allowUserRetry`. La password nuova al passo 3 o all'ingresso riapre l'interruttore
   **dell'account digitato**; prima si riapriva quello dell'account salvato, che al passo 3 non c'è: dopo una password sbagliata
   l'onboarding restava bloccato per sempre, perché l'interruttore è persistente (`P9ContractFixesTest`).
4. `core-family`: "Continua più tardi" anche dopo il passo 2, e senza rimbalzi da un passo del NAS all'altro quando lo si
   riprende con il NAS ancora spento (`P9ContractFixesTest`).
5. `core-family`: `SetupResolver`, un azzeramento finito con il motore che dice ancora "perso" è `Wiped` (prima, nello stesso
   processo dell'azzeramento, restava "azzeramento in corso") (`P9ContractFixesTest`).
6. `core-family`: `DataStoreOnboardingStateStore.update` pubblica subito lo snapshot scritto (prima due input dello stesso passo
   uno dietro l'altro potevano leggere l'avanzamento vecchio) (`OnboardingStorageTest`).
7. `core-common`: `ExportValue.Enum.restore` più stretto (`DiagnosticsTest`; `DiagnosticsExporterTest` aggiornato).
8. `feature-diagnostics`: catalogo con i 23 codici dei contributori di rete e fiducia (`DiagCatalogTest`, `DiagnosticsWiringTest`).
9. `feature-onboarding`: `continueLater`; riepilogo con i passi del NAS da riprendere; prova della VPN con il nome scritto;
   "Intanto finisci la configurazione" dalla pagina d'attesa dell'ingresso (prima l'ingresso restava sulla pagina d'attesa per
   sempre: dentro l'onboarding nessuno guardava l'approvazione); dopo la richiesta, la ripresa riparte dai passi locali e non
   dalla scansione (`OnboardingViewModelTest`).

## Fase 3: integrazione nell'app (P11, 13/10)
La Libreria del telefono, il viewer e le Raccolte girano nell'app vera. Il collegamento sta in `app/.../gallery/`
(`GalleryGraph`, `PhoneLibrary`, `LazyPlaces`, `PartialAccessTimeline`, `CatalogPorts`, `LibraryStates`, `LibraryDiagnostics`,
`SyntheticLibraryStack`), esposto a Hilt da `di/GalleryModule.kt`; i lavori in `work/GalleryWork.kt`; la radice dei modali e
del viewer in `main/GalleryRoot.kt`; le richieste dei permessi in `main/LibraryCommandHostEffect.kt`.

**Decisioni**
1. **Due tempi.** La parte che la UI usa subito (database, miniature, segnaposto, luoghi, layout, preferenze, repository della
   timeline) nasce con il processo; il catalogo e la lettura del telefono (`core()`) nascono solo con la famiglia `Ready`,
   perche' `MediaCatalog` vuole l'account proprietario (letto da `NasAccountStore`). Il repository e' **uno per processo** e
   parte quando c'e' il catalogo (nota di P5); prima la Libreria mostra l'istantanea salvata o la prima lettura.
2. **Lavori.** `PhoneSyncWorker`: trigger sui contenuti di immagini e video (ritardo 5 s, massimo 60 s, batteria non scarica),
   giro con budget di 8 minuti, poi si rimette in coda da solo (`APPEND_OR_REPLACE`). `LibraryMaintenanceWorker`: una volta al
   giorno in carica (riconciliazione, META 3 min, impronte complete rimandate 2 min, `flush` dell'istantanea). Sono lavoro
   locale e leggero, **fuori dal motore delle regole** (D10: la lettura del telefono non passa dal motore; nessuna rete, nessun
   NAS): si pianificano appena la famiglia e' pronta (`GalleryWorkScheduling.ensure`) con i soli vincoli di WorkManager
   (batteria non scarica; la manutenzione anche in carica), mai nella variante di misura (la sincronizzazione e' quella
   ferma). Se la famiglia non e' pronta adesso, o la galleria e' stata fermata, il lavoro finisce senza fare niente
   (`runIfActive`, vedi il punto 14).
3. **Impronte complete rimandate** (P2): una query nuova `LocationDao.deferredFullHashes` **senza cambiare lo schema** e la classe
   `DeferredFullHashes` di `core-mediastore` (solo con accesso completo; le foto solo con `ACCESS_MEDIA_LOCATION`).
4. **Priorita' di META** = la finestra visibile della griglia (`WindowMetaPriority`).
5. **Luoghi.** L'asset `places.mcgp1` (GeoNames, 4,98 MB, SHA-256 fissato in `PlacesDatasetPin`) e' nell'APK. `LazyPlaces` lo
   apre su IO; finche' non e' pronto, o se non si apre (`NotBundled` = build senza asset, `Failed` = asset alterato o
   illeggibile), le intestazioni restano senza luogo (nessun testo vuoto) e la Diagnostica lo dice (`PHONE_PLACES`: "non
   trovati nell'app", non "non inclusi in questa versione"). Licenze e attribuzione in Impostazioni → Informazioni e licenze.
6. **Date sempre in italiano**, da un solo punto: `core-library/.../text/GalleryDates.kt` (`Locale.ITALIAN`), usato da griglia e
   viewer; provato con il dispositivo in inglese (Robolectric `en-rUS` ed emulatore in inglese).
7. **Permessi solo da un tocco** nella Libreria ("Consenti accesso", "Consenti tutto", "Consenti" per la posizione delle foto);
   nessun permesso nuovo. `ACCESS_MEDIA_LOCATION` si chiede **anche insieme ai media** da "Consenti accesso" (come
   l'onboarding), non solo dall'avviso delle foto in attesa: e' il comportamento del codice (`LibraryCommandHostEffect`);
   `docs/sicurezza.md` §4-bis dice solo "dall'avviso" e va allineato a questa riga.
8. **Nascondere una copertina nasconde il gruppo**: nella selezione della Libreria, se fra gli scelti c'e' una copertina, prima
   un foglio "Nascondere N elementi?" con il numero vero (`LibraryHideGroupSheet`).
9. **Preferiti e nascosti: l'errore non chiude l'app e non e' ingoiato.** Un errore del catalogo (lanciato dallo scope della
   composizione) chiudeva l'app (trovato sull'emulatore, P11); la prima correzione lo ingoiava, ma cosi' "Nascondi" che
   falliva sembrava riuscito (R8). Ora `GuardedMediaActions` registra il tipo dell'eccezione e la **rilancia**, e ogni punto
   che lancia l'azione (selezione della Libreria, viewer, Raccolte, Nascosti) la cattura e mostra un avviso ("Non e' stato
   possibile nascondere"); la selezione resta aperta, il viewer non annuncia "Nascosto" e la stella torna com'era.
10. **Variante di misura**: `applicationIdSuffix '.bench'` per `benchmarkRelease` e `nonMinifiedRelease` (non sostituisce piu'
    la release sul telefono); `nonMinifiedRelease` prende anche `src/benchmarkRelease`. **Niente `ServiceLoader`** (R6): le
    due classi della misura (famiglia "pronta", lettura ferma) e il nome del database arrivano da un binding Hilt esplicito
    (`MeasurementHooks`, un `Optional` vuoto fuori dalle varianti di misura) che sta solo in `src/benchmarkRelease`
    (`BenchHooksModule`); `src/main` non nomina nessuna classe di misura e `tools/check.ps1` fallisce se l'APK di release ha
    un `META-INF/services/dev.pampa.*`. Il nome del database segue le hooks, non il tipo di build (I3: prima
    `nonMinifiedRelease` apriva `gallery.db`, vuoto). `reportFullyDrawn` e la sezione `MCG:viewerOpen` ci sono.
11. **Libreria sintetica (solo debug)**: database `gallery-synthetic.db` e istantanea separati, scelta da Vista; segnaposto
    ThumbHash veri. Le Raccolte e il viewer restano sul catalogo vero / senza dettagli del catalogo.
12. **Accesso parziale: chiuso finche' non si sa** (R5). `null` in `partialSelection` voleva dire sia "accesso completo" sia
    "la prima lettura non ha ancora risposto": all'avvio a freddo la Libreria mostrava per qualche secondo l'intero catalogo
    (griglia, conteggi, scrubber, segnaposto). Ora `PartialAccessTimeline` conosce l'accesso (che il sistema dice subito):
    con accesso parziale, o senza accesso, e senza selezione la vista e' **vuota**, e la Libreria mostra "Sto leggendo la
    Libreria" (`LibraryPhoneStatus.selectionPending`).
13. **Conteggi all'italiana** (come le date): un solo punto, `core-library/.../text/GalleryNumbers.kt` ("92.019", il punto dalle
    migliaia in su, qualunque lingua abbia il telefono, senza `NumberFormat`). Le risorse prendono un `%1$s` e un `<plurals>`
    sceglie la forma dal numero vero (`countText`, `photoVideoSummary` in `feature-library`; la posizione del viewer). Fuori:
    `feature-settings` e `feature-diagnostics` non vedono `core-library` (nessuna dipendenza nuova) e i loro numeri sono
    piccoli; il titolo "Eliminare N foto?" e' di `core-designsystem`.
14. **L'azzeramento ferma la galleria viva** (R7). `GalleryGraph.shutdown()`, chiamato da `AppLocalDataEraser` **prima** di
    cancellare `replica/` e i database: annulla i lavori in corso (`runIfActive`) e li aspetta, stacca la sincronizzazione,
    annulla repository e scrittore dell'istantanea e li aspetta, chiude la pipeline delle immagini e il database. Se non si
    ferma, i file non si cancellano e la fase si riprova. `core()`/`coreIfReady()` guardano lo stato della famiglia anche con
    il catalogo in memoria (un blocco passeggero sospende, non ferma). **Limite:** dopo un azzeramento la galleria non si
    riavvia nello stesso processo (il repository parte una volta sola): una riconfigurazione fatta subito dopo la ritrova al
    prossimo avvio. Una galleria mai partita non si ritira.
15. **Condividere e cestinare molti elementi** (I8). Tetto di 500 per "Condividi" (gli URI viaggiano tre volte nella stessa
    transazione Binder) e di 2.000 per il cestino (il limite di `createTrashRequest`): oltre, la Libreria avvisa ("Puoi
    condividere al massimo 500 elementi alla volta") senza provarci; se Android rifiuta comunque l'avvio (anche la
    `RuntimeException` di una `TransactionTooLargeException`) lo dice ("Non e' stato possibile condividere").
16. **Informazioni e licenze** (I6): tutte le librerie dell'APK di release (dall'albero `releaseRuntimeClasspath`, licenze dai POM e
    dai jar: Apache 2.0, MIT per mbassador e SLF4J, Bouncy Castle con la sua riga di copyright), le opere del Fluid Engine
    (`FluidEngineLicenses`: AndroidLiquidGlass, Inter) e il nome e l'indirizzo di ogni licenza, tutto **testo, mai un
    collegamento**. Un test confronta le versioni con `versions-app.gradle`.
17. **Barriere automatiche anche per `:app`** (I7): `NoExceptionTextInLogsTest` su tutto `:app`, `NoNetworkSourcesTest` sui file
    della galleria (`gallery/`, `main/`, `GalleryWork.kt`, `GalleryModule.kt`, `MeasurementHooks.kt`, `src/benchmarkRelease`).

**Scostamenti**
- `core-database`, fuori dal perimetro (solo la query delle impronte): `TimelineIndexDao.phoneRefs` con `+l.state`. Senza
  statistiche SQLite partiva da tutte le posizioni presenti per ogni blocco di 500 media: 265 s a blocco su 100 mila, ora
  0,13 s. Il test controlla il piano con e senza `ANALYZE`.
- **Solo debug**: la famiglia risulta pronta se esiste `files/debug-family-ready.marker` (lo crea solo `adb`), per lo smoke
  sull'emulatore senza NAS. In release la funzione non esiste (`DeveloperTools` di release).
- Non fatti: il ricalcolo dei luoghi quando cambia il dataset (serve una query nuova: Fase 4), le date dedotte dai vicini
  (`NeighborOffsetsProvider` non collegato), il ripristino del viewer dopo la ricreazione (E-V8) fuori dalla Libreria, il foglio
  del gruppo nel viewer e nelle raccolte (lì si nasconde senza avviso).
- Il dataset della misura scrive `quick_hash` come `bench-%08x`, non 64 cifre esadecimali (nota di P9): non tocca la Libreria,
  da sistemare se una prova lo rilegge come `QuickHash`.

**Dubbi grafici per l'utente**
- ~~I numeri non hanno il separatore delle migliaia~~ (risolto: "92.019 foto, 7.981 video", `GalleryNumbers`).
- La maniglia dello scorrimento rapido si sovrappone al pulsante "Oggi".
- Cambiando livello in fondo alla griglia, il piede ("N foto, N video") resta sotto i segmenti finche' non si scorre.
- Entrando nella selezione la griglia si sposta in basso (la pillola sparisce e il margine cambia).
- Il pizzico non si fa con `adb` (un dito solo): sull'emulatore l'ha fatto lo scenario `pinch` di `:benchmark` (gira, la
  griglia cambia livello); da guardare a occhio sul telefono vero.

**Modifiche ad altri moduli fatte dal P11** (firme pubbliche invariate, ognuna con il suo test)
1. `core-database`: `LocationDao.deferredFullHashes`; `TimelineIndexDao.PHONE_REFS_SQL` con `+l.state`.
2. `core-mediastore`: `sync/DeferredFullHashes` (nuova); test di P2 instabile corretto (`RunnerAndForegroundTest`).
3. `core-library`: `text/GalleryDates` (nuova).
4. `feature-library`: `LibraryStateSource` e `LibraryViewModel` iniettabile; `GridText` con `GalleryDates`; voce "Libreria del
   telefono" nel menu di debug; foglio "Nascondi" per i gruppi; lettura e scrittura del marcatore di debug in ordine (prima la
   scrittura del valore iniziale poteva cancellare la scelta fatta da `adb`).
5. `feature-viewer`: `ViewerFormat` in italiano; R2 della revisione (`ImageDecoderAnimatedLoader` con lato e pixel massimi,
   oltre il tetto il primo fotogramma fermo, `AnimatedSizing`).
6. `feature-diagnostics`: codici della Fase 3 nel catalogo e chiavi dell'esportazione (solo numeri ed enum).
7. `feature-settings`: pagina Informazioni e licenze (GeoNames CC BY 4.0, telephoto, Media3, ExifInterface).

## Ambiente di prova sul NAS (14/10)
- Utenti di prova `test` e `test2`, creati dall'utente, non amministratori. Le password non sono nel repository. Sono **deboli**:
  vanno eliminati (o rafforzati) a fine prove.
- Share private create dall'orchestratore su richiesta dell'utente, con accesso negato a `admin`, `alessio` e `pampa`:
  - `test-comune`: lettura e scrittura per `test` e `test2` (area comune di prova);
  - `test2`: lettura e scrittura solo per `test2` (area privata di prova).
- La share `test` esisteva già (163 MB di foto di prova della Fase 0): lettura e scrittura per `test` e `alessio`.
- **`NAS` e `Public` sono pubbliche**, quindi anche gli account di prova le vedono e ci scrivono. Non si cambia perché servono
  alla famiglia. Le prove automatiche devono rifiutare da codice qualunque percorso fuori dalle share di prova.
- Nessuna share di prova ha il cestino del NAS attivo ("Abilita cestino" OFF, come `Public`); `NAS` lo ha attivo.

## Fase 4: il NAS nella Libreria (progetto 14/10, P0 14/10)
Fonte: `docs/fase4-progetto.md` (§0.1 decisioni, §9 domande, §9.1 risposte). Le decisioni valgono come scritte lì; qui il
riassunto e lo stato. Le modifiche al formato (M1–M12) sono in `docs/formato-nas.md`, quelle di sicurezza in
`docs/sicurezza.md` (C10, C11, C19, §4-bis, L26–L32, checklist della Fase 4, §8).

**Risposte dell'utente alle domande del §9 (14/10)**
| # | Domanda | Risposta | Effetto |
|---|---|---|---|
| Q1 | Dividere la fase | (a) **4A** sola lettura, indice e Libreria; **4B** operazioni sui file, dati personali, 500 interruzioni | D1 confermata |
| Q2 | Qualità e HQ | (a) Risparmio = S, **Standard** = S + M, Alta = S + M + HQ; HQ 2048 px preparata **per tutti** | D12, D16 confermate |
| Q3 | Sorgente di uno spostamento tra share | (a) **nel cestino** per 30 giorni | D24 confermata |
| Q4 | Scansione dell'archivio vero già a M1 | (a) **Sì, in sola lettura**, APK di release con l'account vero (F2) | M1 comprende F2 |
| Q5 | Video uguali non ancora verificati | (a) **una cella** "da verificare" | D21 confermata |
| Q6 | Cambiamenti anomali di massa | **(b) nel riepilogo giornaliero**, non con una notifica immediata | **D10 cambia**: congelamento immediato, avviso nel riepilogo e in evidenza in Libreria e Diagnostica |
| Q7 | Dati mobili con l'app aperta | (a) solo ciò che è sullo schermo | D23 confermata |
| Q8 | "Elimina subito" negli Eliminati di recente del NAS | (a) sì, doppia conferma e avviso sul backup esterno | D25 (4B) |
| Q9, Q10 | Dati personali, hash completo dei video | non chieste: valgono le raccomandazioni (a) | D26, L32 |

**Decisioni D1–D33** (stato: *deciso* = confermato o senza alternative aperte; *proposta* = vale se non contestata)
| # | In breve | Stato |
|---|---|---|
| D1 | Fase in due metà: 4A non tocca mai un media sul NAS (scrive solo indice e prenotazioni in `.mycloudgallery/`), 4B porta le operazioni distruttive con la prova delle 500 interruzioni | Deciso (Q1) |
| D2 | Interfacce separate in `core-nas`: `NasMediaReader` (4A) e `NasFileOps` (4B, solo `core-nasops`); `NasFileSystem` resta a tre operazioni | Deciso; contratto e test di architettura in P0 |
| D3 | Sorvegliante a progresso: scadenza per blocco (15/60 s) e soglia di 32 KiB/s su 20 s, nessun limite al totale | Deciso (P1) |
| D4 | Indice in segmenti per dispositivo, share e classe; record in JSON canonico, immagini per chiave di contenuto | Deciso; formato §5.5-bis |
| D5 | Stato di un dispositivo = funzione della sua testa corrente; `seq` crescente, lapidi | Deciso |
| D6 | Fusione per percorso e timbro; fra record validi più classi preparate, poi id minore | Deciso |
| D7 | Identità condivise, convergenza al minore, adozione rifiutata sui conflitti | Deciso |
| D8 | Fiducia nei dati dell'indice: mostrare e coordinare, mai unire senza impronta letta qui, mai "al sicuro", mai azioni | Deciso |
| D9 | Scansione: elenco di tutte le cartelle incluse, rilevamento con testa e coda da 64 KiB, stabilità con l'ora del NAS | Deciso |
| D10 | Congelamento per cambiamenti di massa ≥ max(50, 5%); avviso nel riepilogo giornaliero | Deciso (Q6 (b)) |
| D11 | Esclusioni: configurazione firmata, scelte locali, copertine degli album, cartelle di sistema e col punto | Deciso |
| D12 | Preparazione in un passaggio: S 200, M 400 (lato corto, WebP), analisi 1024 (JPEG), HQ 2048 (WebP), fotogrammi ogni 30 s; video mai scaricati | Deciso (Q2) |
| D13 | Unità di lavoro di 200 media per mese, prenotazione di 1 ora rinnovata ogni 20 minuti | Deciso |
| D14 | Formati non decodificabili passano di mano | Deciso |
| D15 | Replica locale a pacchi mappati, nessun file per miniatura | Deciso |
| D16 | Livelli Risparmio/Standard/Alta | Deciso (Q2) |
| D17 | Miniature provvisorie solo locali | Deciso |
| D18 | `MCGT2`, presenza nei flag della cella (`ON_PHONE`, `ON_NAS`, `SAFE`; in P0 anche `TO_VERIFY`) | Deciso; P0 aggiunge il bit `TO_VERIFY` |
| D19 | Viewer: HQ dalla replica, originale a intervalli, video con un `DataSource` nostro | Deciso |
| D20 | "Scarica sul telefono" con `IS_PENDING` e posizione collegata prima della pubblicazione | Deciso |
| D21 | Video uguali in attesa dell'hash completo: una cella "da verificare" (`PROBABLE_DUPLICATE`) | Deciso (Q5) |
| D22 | Regole delle attività applicate ai lavori veri; tappe da 8 minuti; rilevamento fuori casa ≤ 50 MB | Deciso |
| D23 | Dati mobili con l'app aperta: solo ciò che è sullo schermo | Deciso (Q7) |
| D24 | Operazioni con diario locale e macchina a stati; sorgente nel cestino | Deciso (Q3), 4B |
| D25 | Cestino del NAS dell'app con record firmati; "Elimina subito" | Deciso (Q8), 4B |
| D26 | Dati personali come stato unito nell'area privata | Proposta (Q9 non chiesta), 4B |
| D27 | UI della Fase 4 come stati e contratti; l'aspetto è del filone grafico | Deciso |
| D28 | Nessuna dipendenza nuova; nessun modulo nuovo apre socket | Deciso; verificato in P0 |
| D29 | Nessun permesso nella 4A; UIDT nella 4B (`RUN_USER_INITIATED_JOBS` e `NasOpsJobService`) | **Deciso: sì dell'utente il 15/10**; in manifest e in policy dal P0-B |
| D30 | Area di prova vera dietro una guardia (`mcg-it/<giro>/`, share di prova scritte nel codice) | Deciso (P1) |
| D31 | Pubblicazione solo sulle share di prova finché l'utente non chiude la 4A (`FeatureFlags.indexPublishTestSharesOnly`) | Deciso; flag in P0, `true` anche in release |
| D32 | Ora del NAS per tutto; salto oltre 5 minuti sospende prenotazioni, cestino, compattazione e pulizie | Deciso; costanti in P0 |
| D33 | Recinzione sul telefono in debug con gli account di prova (`ShareFence`) | Deciso (P1, legata da P11 solo in debug) |

**Scelte di P0 da confermare** (scostamenti dal progetto, tutti piccoli): bit `TO_VERIFY` nei flag della cella;
`LibraryNasStatus` in `core-library` invece che in `feature-library` (il filone grafico sta cambiando quei file);
`core-index` vede `core-database` (outbox e replica stanno nel database); `NasScanner` uno per share (`scan(mode, budget)`).

## Fase 4A: integrazione nell'app (P11, M1, 14/10)
I pacchetti P0–P10 sono collegati nel grafo vero (`app/.../nas/NasGraph.kt`, costruito da `GalleryGraph`; ingressi da
`AppGraph.nasInputs`): sessione → scansione (P5) → catalogo v3 (P2) → pubblicazione dell'indice (P3) e replica/sincronizzazione
(P4) → preparazione (P6) → Libreria (P7) → viewer e "Scarica" (P8); lavori a tappe nelle regole (P9, `work/NasWork.kt`);
notifiche come nella proposta 12 con il congelamento nel **riepilogo giornaliero** (Q6). La prova d'integrazione su NAS finto
(`app/src/test/.../integration/NasLibraryIntegrationTest.kt`) fa tutto il giro: scansione → Libreria ("solo NAS", data,
riferimento al NAS) → preparazione e pubblicazione → secondo dispositivo che sincronizza e vede le miniature senza preparare →
viewer → spegnimento.

**Scostamenti dal progetto (da confermare)**
- **Schema v3 riconciliato** (la v3 non era rilasciata: `3.json` aggiornato, nessuna migrazione nuova): le tabelle dell'indice e
  della replica sono quelle delle porte di P3/P4 (`index_publish_state`, `index_own_segment/record/blob`,
  `index_outbox_record/blob`, `index_head`, `index_segment`, `index_record`, `replica_dirty/pack/blob/share_access/setting/
  provisional`); `index_sync_state` tolta. Adattatori Room in `core-index/.../store` (`RoomIndexPublishStore`,
  `RoomReplicaStore`, `RoomProvisionalStore`), provati facendo girare **le stesse suite** di P3 e P4 anche su Room.
- **`FeatureFlags.RELEASE.nasLibrary = true`**: il NAS nella Libreria e' acceso anche in release (serve a F2 e F19), con D31
  (`indexPublishTestSharesOnly`) sempre acceso: fuori dalle share di prova l'indice resta "solo locale".
- **Posizioni "solo dall'indice"**: i record di altri dispositivi per file che la nostra scansione non ha ancora visto diventano
  posizioni NAS con `last_seen_at = 0` (`NasLocationInfo.indexOnly`), senza colonne nuove; la scansione le conferma o le ritira.
- `nasRefs` comprende le posizioni `unavailable` (E-L4) e preferisce quelle presenti; `CatalogWorkSource` tiene il quadro di una
  share per 10 minuti (orologio monotono) invece di rileggere il catalogo a ogni unita'.
- **Riepilogo giornaliero** (`notify/NasDailySummary.kt`): aree congelate, NAS muto da piu' di 26 ore, verifiche ferme da 3 giorni;
  solo numeri. L'emergenza "Ferma le scritture sul NAS" e' un file in `noBackupFilesDir` (sopravvive al riavvio) e un'azione della
  Diagnostica; "Ho controllato, continua" chiede di riscrivere il numero dei file (dialogo funzionale).
- **Azzeramento**: il NAS si ferma per primo (`GalleryGraph.shutdown` → `NasGraph.stop`); si cancellano outbox, pacchi (nel
  database e in `noBackupFilesDir/replica`), tentativi, emergenza, interruttore delle copertine e battiti.
- Causa dei due test rossi di partenza: (1) dopo "dimentica" di un dispositivo segnato perso, `DefaultFamilySetupRepository`
  mostrava per un istante `Blocked(WIPE_IN_PROGRESS)` (il diario finito e lo stato "perso" letti in due momenti): ora un
  azzeramento finito e dimenticato vale `NotConfigured`, e `forgetCompletedWipe` rilegge lo stato prima di pulire l'onboarding;
  (2) lo screenshot P9 lasciava aperto il DataStore dell'interruttore mentre la cartella temporanea spariva: i test chiudono lo
  scope e ne aspettano la fine (`closeScope`).
- **Non disegnati** (sono del filone grafico, §1.5): il badge "solo NAS" (il dato c'e': presenza nell'indice e nella cella;
  `GalleryGridCellBadges` non ha la presenza) e il piede del NAS (`LibraryTabState.nas` collegato, la Libreria non lo mostra).
- **D32**: `core-nas` non ha un gesto per "accettare" un salto dell'ora: le scritture restano ferme finche' l'ultimo salto
  osservato supera i 5 minuti (residuo per la revisione). *Superato dalla revisione della 4A (R7, sotto): il salto si salva fra
  i processi e finisce con il gesto o dopo 24 ore di ora stabile.*
- "Anteprime del NAS" in Impostazioni (`feature-settings/.../quality`): livello con la stima, spazio occupato, convergenza,
  "Mostra le copertine degli album" (salvato come file). Pagina funzionale: l'aspetto e' del filone grafico.
- P10: `MeasurementHooks.replicaBlobs` e `BenchReplicaBlobs` (le miniature solo-NAS del dataset passano da `NasThumbnailSource`),
  `testTagsAsResourceId` alla radice.
- **M1 sull'emulatore** (`Medium_Phone`, 14/10) con il **NAS sintetico** di debug (Impostazioni → Sviluppo): 20 mila file su un NAS
  finto in memoria (fotocamera, WhatsApp, schermate, vecchie scansioni nella share privata; foto di famiglia e 250 album di musica
  nella comune), scansione vera, database separato. Esito: **19.000 foto** nella Libreria come media solo sul NAS (16.000 + 3.000),
  250 copertine escluse (E-N14), tracce ignorate, date dai nomi e dalle date dei file, miniature EXIF provvisorie; prima scansione
  in circa 4 minuti, la Libreria si riempie mentre procede; il viewer mostra l'anteprima e "Il NAS non risponde" (E-L4); nessun
  errore nel log. Screenshot in `docs/screenshots/4-P11/` (scelta in `selezione/4-P11/`).
- Correzione fuori perimetro: `feature-viewer` (P8) non passava il lint (`RememberReturnType` in `ViewerOverlay`: un `remember`
  che restituiva `Unit`); ora restituisce il servizio degli scaricamenti, stesso comportamento.

**Cosa resta per le prove sul campo** (`docs/fase4-progetto.md` §5.8): F1 (prima scansione dell'area di prova sull'S25, tempo),
**F2** (con il consenso e l'APK di release con l'account vero: scansione **in sola lettura** di `alessio` con D31 acceso; dal PC
nessuna `.mycloudgallery` nuova), F3 (verifiche senza novita' e dischi), F4–F5 (rinomina, modifica, cancellazione dal PC), F6
(preparazione dell'area di prova, tablet `test2` che vede senza preparare: M2), F7 (due dispositivi per 3 ore), F8 (indice ostile
dal PC), F9 (500 `.locked`: congelamento e scongelamento col gesto), F10–F11 (viewer e "Scarica" sul NAS vero: l'androidTest di
P8 non e' ancora stato eseguito), F12 (convergenza della qualita'), F13 (`check.ps1 -Bench` su S25 e Tab S9), F14 (Twonky, Plex,
Foto di Windows). HEIC, DNG e video veri si provano solo sul telefono (P6).

**Per la revisione di sicurezza della 4A**: la verifica della firma finale di SESSION_SETUP aggiunta a `SmbjTransport` (P1); le
unioni della replica che restano dopo una revoca (P4); l'outbox che sotto D31 cresce senza tetto e la prenotazione non
rilasciata da `finish()` con lavoro nell'outbox (P3); il residuo D32 qui sopra; `FeatureFlags.RELEASE.nasLibrary` acceso; la
recinzione D33 legata solo in debug (verificata da `tools\check.ps1` sul dex di release).

### Revisione di sicurezza della 4A: correzioni (14/10)
Fonte: `docs/review/4a-fase4a.md` (R1-R11 corretti, ognuno con il suo test; i sei `SecurityReview4aTest` verdi senza
modifiche). Le scelte che l'utente vede, tutte le **piu' prudenti** fra quelle possibili (da confermare):

- **R2, l'outbox sotto D31.** Politica: (a) su una share **non pubblicabile** (D31 su una share vera, area in sola lettura
  E-I19) la preparazione lavora **solo in locale**: niente outbox, ne' record ne' immagini; la replica locale tiene le classi
  del livello di qualita' scelto (mai l'anteprima di analisi ne' i fotogrammi; l'HQ solo ad Alta), quindi la Libreria ha le
  sue miniature e lo spazio e' quello della stima mostrata in Impostazioni; (b) con un blocco **temporaneo** (congelamento,
  salto dell'ora, emergenza, fiducia) l'outbox aspetta con un **tetto di 512 MiB** di immagini; oltre, le immagini nuove restano
  solo nella replica (gli altri dispositivi le preparano da se', R5); (c) all'avvio si toglie l'outbox delle share non piu'
  configurate o non pubblicabili, anche l'arretrato accumulato prima; (d) la preparazione **aspetta sotto 1 GiB libero** sul
  telefono (la fotocamera deve poter salvare). **Effetto per l'utente:** quando D31 si spegnera' (chiusura della 4A) non si
  pubblica un arretrato vecchio di mesi: la preparazione rifa' cio' che gli altri non trovano nell'indice (lavoro in piu', una
  volta).
- **R7, il salto dell'ora del NAS (D32).** Il salto si misura anche fra un processo e l'altro (l'ultima ancora salvata in
  `noBackupFilesDir/replica/nas-clock`) e la sospensione di indice e prenotazioni **resta dopo un riavvio dell'app**, finche'
  l'utente non accetta l'ora o per **24 ore di ora del NAS stabile**. Fra due riavvii del telefono l'unico metro e' l'orologio
  del telefono, con una tolleranza larga (5 minuti piu' ~9 s al giorno): un orologio del telefono spostato a mano puo'
  sospendere le scritture per un giorno, mai il contrario. **Manca il pulsante** "L'ora del NAS e' giusta, continua" in
  Diagnostica: il gesto c'e' (`NasGraph.acceptClockJump`) ma la voce va aggiunta in `feature-diagnostics` (`DiagnosticsActions`)
  e in `navigation/AppNavHost.kt`, fuori dal perimetro di queste correzioni; finche' non c'e' vale solo l'attesa di 24 ore.
- **R9, il congelamento (D10).** (1) "Ho controllato, continua" accetta **solo i conteggi che l'utente ha visto**: se il giro
  dopo trova di piu' (oltre la soglia) si congela di nuovo; (2) i file noti che diventano **copertine** (un file audio comparso
  nella cartella) contano come "non piu' media" e non escono da una share sospetta o congelata; fa eccezione solo l'utente che
  ha appena spento "Mostra le copertine degli album" (E-N14, nello stesso processo); (3) i conteggi dei giri si **sommano per
  7 giorni** di ora del NAS: una cancellazione "a gocce" sotto la soglia di ogni giro arriva comunque alla soglia; (4) le
  **share sotto i 100 file** si congelano anche al 50% dei file (almeno 2): un elenco vuoto della radice non le svuota piu' in
  silenzio. **Effetto per l'utente:** piu' congelamenti "di troppo" possibili (pulizie dal PC di decine di file nella stessa
  settimana, una share piccola dimezzata, l'opzione delle copertine spenta e l'app riavviata prima della verifica), sempre
  con il numero da confermare.
- **Altre scelte senza effetto visibile:** le miniature e l'HQ si cercano solo nelle share del media e con la sua chiave intera
  (R1); una classe pubblicata vale solo con la miniatura S del dispositivo (R5), e un altro dispositivo che dichiara fallita la
  famiglia di decoder di questo non lo ferma; le impronte adottate dall'indice non legano niente e si rileggono quando l'autore
  sparisce (R6, tabella `nas_adopted_fingerprint`, colonne `window_*` di `nas_scan_state`: schema v3 non rilasciato, `3.json`
  aggiornato); "Scarica" controlla timbro, `qh1` e hash completo noto (R8); un'area persa toglie posizioni e miniature
  provvisorie (R10); la recinzione D33 vale per tutto il processo (`ShareFences`) e `check.ps1` legge gli esiti di R8 (R11).
- **Residui:** fra dispositivi della **stessa** share con la stessa chiave intera vale ancora l'id minore e un record con la
  maschera `prep` gonfiata vince D6 (L27); `NasDownloader` (`feature-viewer`) non confronta da se' il timbro; gli informativi
  I1-I4 aperti, I5-I6 in parte (vedi la revisione).

## Fase 4B: fondamenta e contratti (P0-B, 15/10)
Fonte: `docs/fase4-progetto.md` (§1.1, §1.2 per la 4B, §1.5, §2.7, §2.9, D24, D25, D26, D29, D32, D33; riga P0-B del §4.4). Consegna i moduli,
i contratti e i fake su cui partono in parallelo P12 (operazioni, cestino, spazio), P13 (dati personali), P14 (UI) e P15 (prova delle 500
interruzioni). Nessuna logica di produzione: i moduli hanno `contract/`, `noop/` e `testing/`, `NoNetworkSourcesTest`,
`NoWallClockSourcesTest` e test sui fake.

**Risposte dell'utente (15/10).** D29: sì al permesso `RUN_USER_INITIATED_JOBS` e al `JobService` per le operazioni lunghe avviate
dall'utente. Q3, Q8, Q9 come al 14/10 (sorgente nel cestino per 30 giorni; "Elimina subito" con doppia conferma; dati personali (a)). La 4A si
prova sul telefono insieme alla 4B (chiusura unica sul campo).

**Moduli e contratti**
| Dove | Cosa | Per chi |
|---|---|---|
| `core-nas/ops` | `NasFileOps` (mkdirs, rename, copyToTemp, openTemp + `NasTempWriter`, publishTemp, trashMove, deleteIfUnchanged, deleteOwnTemp, rmdirIfEmpty, space) con gli esiti sigillati, `NasOpsPaths`, `NoNasFileOps` (noop) e il fake funzionante `InMemoryNasMedia.fileOps(dispositivo)` con `FaultPlan` (`opsFaults`: caduta prima o dopo l'applicazione), copia lato server rifiutabile, spazio e quota, share in sola lettura | P12 (implementazione su smbj dietro `SmbTransport`), P15 |
| `core-nasops/contract` | `OpRequest`, `OpPlan`/`PlanHash`/`PlannedItem`, `PlanRules`, `UserConfirmation`/`ConfirmationIssuer`/`ConfirmationSheet`/`ConfirmationGate`/`AcceptedPlan`, diario (`OpsStore`, `OpRecord`, `OpItemRecord`, `LocalTrashRecord`), macchina a stati (`OpItemState`, `ItemFlow`, `OpStateMachine`), `NasOperations`, `NasOperationState`, `NasTrash`/`NasTrashState`, `NasSpace`/`NasSpaceState`, `NasFolders`/`NasFolderState`, `OwnFilesCleaning`, `OpClocks`, `OpsGate`/`GateStatus`, `NameRules`, `LockedBackoff`, `OpTemps` | P12, P14, P15, P16 |
| `core-nasops/testing` | `NasOpsTestKit` (NAS finto + diario + orologi + operazioni, cestino, spazio e cartelle collegati; `restart()` simula la morte del processo), `InMemoryNasOperations` (esecutore di riferimento di tutte le macchine a stati), `ScriptedNasOperations` (per la UI), `InMemoryOpsStore` (rifiuta le transizioni fuori dalla macchina), `InMemoryNasTrash`, `InMemoryNasSpace`, `InMemoryNasFolders`, `FakeOpClocks`, `FakeOpsGate` | tutti |
| `core-personal/contract` | `PersonalKey`/`PersonalEntry`/`PersonalValue`, `PersonalMerge` (D26: vince `(ora del NAS, autore minore)`, commutativa e idempotente), `PersonalValidity` (stesso account, area privata dichiarata, non revocato, non nel futuro), `PersonalSync`, `PersonalStore`, `PersonalSettings`; fake: `InMemoryPersonalStore`, `FakePersonalCloud` + `FakePersonalSync` (convergenza fra dispositivi dello stesso account, un altro account non vede niente) | P13, P14, P16 |
| `feature-nas` | modulo Compose + Hilt pronto, `NasScreen` (rotte e quali schermate scrivono) | P14, P16 |
| `:app` | `RUN_USER_INITIATED_JOBS`, `NasOpsJobService` (non esportato, `BIND_JOB_SERVICE`, scheletro che non fa niente) | P16 |

**Come e' garantito che niente di distruttivo parta da dati letti dal NAS**
- `NasFileOps` richiede un'adesione `@RequiresOptIn` (ERRORE) al marcatore `DestructiveNasAccess`: l'argomento del compilatore lo passano solo
  `core-nas` e `core-nasops`. `NasSessionManager` **non** espone le primitive (come espone `mediaReader()`): P12 le consegna a `core-nasops` con
  una factory dentro `core-nas`.
- `UserConfirmation` ha il costruttore `internal`, lo costruisce solo `ConfirmationIssuer` (stesso file); vale per un piano (impronta), 10 minuti di
  orologio monotono, e i tocchi richiesti (2 per "Elimina subito"). `OpsStore.create` vuole un `AcceptedPlan`, che esce solo da
  `ConfirmationGate.accept`. `start` rifiuta anche con una conferma valida un piano che `PlanRules` non passa (area dell'app, cestino di un
  altro dispositivo, cammino sbagliato).
- `Phase4ArchitectureTest` (`core-common`) verifica: primitive e marcatore solo in `core-nas`/`core-nasops`, chi nomina emittente e foglio di
  conferma, i costruttori, `feature-nas` cieca a `core-nas`, `core-nasops` invisibile a chi legge il NAS, tutti i moduli in `galleryModules`.
  **Provato il 15/10 reintroducendo ogni violazione** (il test corrispondente fallisce, poi si toglie e torna verde): le primitive di scrittura
  citate in `core-index`; il marcatore in `core-index`; `core-personal` che dipende da `core-nasops`; `feature-nas` che dipende da `core-nas`;
  l'emittente delle conferme in `feature-library` e in un file di esecuzione di `core-nasops`; il costruttore di `UserConfirmation` chiamato da
  `feature-nas` o reso pubblico; `OpsStore.create` che accetta un piano qualunque; `feature-nas` fuori da `galleryModules` o senza
  `NoWallClockSourcesTest`. A livello di compilatore: `core-index` che nomina le primitive, o ne riceve un'istanza per inferenza
  (`InMemoryNasMedia.fileOps(...).mkdirs(...)`), non compila. E una mutazione dell'esecutore di riferimento (il controllo della "risposta
  persa") fa fallire `FaultMatrixTest`.
- L'eliminazione definitiva (`deleteIfUnchanged`, `deleteOwnTemp`) vale solo dentro `.mycloudgallery/`: un media va solo nel cestino. Il fake lo
  impone con un errore di programmazione; `rmdirIfEmpty` toglie cartelle vuote fuori dall'area dell'app e nel **proprio** cestino (cartelle cestinate).

**Scostamenti dal progetto (da confermare; tutti piccoli, nessuna dipendenza nuova)**
1. **`NasFileOps`**: `copyToTemp`/`publishTemp`/`deleteOwnTemp` prendono il **percorso** del temporaneo (`OpTemps.tempFor`, scritto nel diario
   prima della chiamata) invece di un `tag`; `ownerDeviceId` fa parte del contratto (temporanei e cestino sono del dispositivo); ogni metodo ha il
   `Purpose`; `expect` confronta dimensione, data di modifica e `fileId`; il ripiego E-D12 e' `openTemp` + `NasTempWriter` (scritture a offset,
   `finish` controlla la lunghezza sullo stesso handle) invece di `writeToTemp`. Il fake **rifiuta come `Collision` la rinomina solo di maiuscole in un
   colpo**, per obbligare al passaggio dal nome temporaneo (E-N7): F15 dira' se Samba la accetta e si potra' allentare.
2. **`NasOperations`**: `plan` risponde `PlanOutcome` (piano o `PlanRejection`), `start` `StartOutcome` (`Started`, `AlreadyStarted` sull'impronta del
   piano, `ConfirmationRejected`, `PlanInvalid`, `WritesBlocked`); `NasTrash` ha anche `refresh()`. L'attesa di un file bloccato (E-N13) e' una
   **condizione** dell'elemento (passo non terminale + `nextAttemptAtNas` futuro + `LOCKED`), non uno stato `WAITING_LOCKED`: cosi' la ripresa sa a
   che passo tornare senza colonne nuove nello schema v3. Annullare e' ammesso solo prima di `PUBLISHED` (poi si porta a termine: niente doppioni visibili).
3. **`core-nasops`** dipende da `core-nas` in `implementation` (nessun tipo di `core-nas` nelle firme pubbliche); `core-database` e `core-index` non ci sono
   ancora: li aggiunge P12 (sono nel grafo del §1.1). Stessa cosa per `core-personal`.
4. **`NasFolders` (Cartelle NAS, lettura)**: contratto e fake in `core-nasops`, ma il §4.4 non ne assegna la realizzazione. Proposta: P12, in
   `core-nasops/.../folders`, sopra `NasMediaReader` e il catalogo.
5. **`TrashRecord` di core-trust** non ha ancora `reason`, `folder` e `count` (M5 e' solo documentato): nessun pacchetto lo ha in perimetro. Va esteso
   (`core-trust/.../data/Trash.kt`, con il suo test) da P12; il contratto di `core-nasops` ha gia' `TrashReason` e `NasTrashItem.fileCount`.
6. **`feature-nas`** vede anche `core-library` e `core-imaging` (§1.1), che servono pure a far risolvere alla sua configurazione KSP le stesse
   versioni di AndroidX di `feature-library` (altrimenti la verifica dei checksum rifiuta due `.module`). Non ha `noop/` ne' `testing/`: i fake per i suoi
   ViewModel sono in `core-nasops/testing` (`NasOpsTestKit`, `ScriptedNasOperations`).
7. **`InMemoryNasMedia`** (file di P1) esteso con `fileOps()`, `opsFaults`, `serverCopyMode`, `spaceOf`, `setReadOnly`, `opCount`, `filePaths`; `FakeNasOp`
   ha i valori delle nuove primitive.
8. **`PersonalMerge` e `PersonalValidity`** sono gia' scritti in `core-personal/contract` (sono la regola D26 in poche righe, servono al fake); P13 puo'
   estenderli. La chiave `PersonalKey.digest()` e' `SHA-256("mcg-pers\0" ‖ tipo ‖ \0 ‖ soggetto ‖ \0 ‖ campo)`; il corpo della classe `personal` (JSON da 4 KiB) e'
   di P13.
9. **L'esecutore di riferimento** (`InMemoryNasOperations`) e' piu' semplice di quello vero: `start` esegue fino in fondo (o alla prima pausa), una
   cartella spostata tra share si espande in file e le cartelle sorgente restano vuote, `fileCount` delle cartelle cestinate non e' compilato, l'identita'
   di una cartella cestinata non si confronta (non ha timbro). P12 e' libero di riscriverlo; i test (`OperationsTest`, `FaultMatrixTest`) valgono per
   entrambi.

**Prove di P0-B.** `FaultMatrixTest`: per sei operazioni (sposta tra share, elimina, ripristina, rinomina, rinomina solo di maiuscole, elimina subito di
una cartella) e per **ciascuna** primitiva di scrittura che fanno, un guasto prima e dopo, morte del processo, ripresa dal diario: nessun file a meta' o
doppio, nessuna perdita, nessun temporaneo (il numero di primitive si conta con un giro pulito; ogni guasto deve scattare davvero). P15 lo estende a
migliaia di giri casuali.

## Fase 4B: integrazione nell'app (P16, 15/10)
P12 (operazioni, cestino, spazio, cartelle, pulizie), P13 (dati personali), P14 (schermate) e P15 (banco delle interruzioni) sono
collegati nel grafo vero. `NasGraph` porta un `nas/NasOrganizer` che parte con il motore del NAS (`attach` in `NasGraph.start`) e
lega `NasOpsWiring` (diario `RoomOpsStore`, cancello `OpsGate` = emergenza, congelamento, salto dell'ora; compattazione accesa con
lo stesso lucchetto per share della pubblicazione) e `NasPersonalSync` (registro `FilePersonalLedger` in `noBackupFilesDir/personal/`,
proiezione sul catalogo, solo area privata). Hilt espone `NasOperations`, `NasTrash`, `NasSpace`, `NasFolders`, `OpClocks`, `NasAreas`
(null-object senza NAS). Raccolte ha il gruppo "Sul NAS" (`CollectionsTab.extraGroups`, voci come dati); le quattro pagine sono rotte
(`nasGraph`) sul telefono e pannello di dettaglio sul tablet; il viewer ha "Sposta", "Rinomina", "Elimina…" (`ViewerServices.nasActions`).
Le prove d'integrazione sul NAS finto (`app/src/test/.../integration/NasOrganizeIntegrationTest.kt`): Cartelle NAS → sposta tra
share (UIDT chiesto) → elimina → "Eliminati di recente" → ripristina → Libreria coerente dopo la verifica → Diagnostica → pulizia con
l'ora finta (+31 giorni: solo la voce propria scaduta; il ripristinato e un finto cestino di un altro dispositivo restano) →
azzeramento; e preferito e nascosto che viaggiano fra due dispositivi dello stesso account (niente dati personali nell'area comune).
`tools\check.ps1` verde il 15/10: 6 min 43 s (passo Gradle 374 s, con la cache di Gradle), 1 min 51 s rifatto dopo l'ultima modifica;
APK debug e release prodotti. Nessun emulatore usato.

**Scostamenti dal progetto (da confermare)**
1. **L'avvio sopravvive alla schermata.** `NasOpsEngine.start` esegue l'operazione fino in fondo prima di rispondere: la facciata di
   `:app` (`NasOrganizer.AppNasOperations`) lo fa girare nello scope della galleria (dentro `runIfActive`: l'azzeramento lo ferma e lo
   aspetta) e risponde `Started` appena l'operazione e' nel diario (stessa impronta del piano, id nuovo). Nessuna modifica all'esecutore.
2. **UIDT solo per le operazioni lunghe**: spostamenti tra share o da 10 elementi in su (`NasOpsJobs.isLong`); una rinomina o tre file
   nel cestino finiscono prima che l'utente esca. La **ripresa** dal diario (all'avvio con operazioni aperte, dopo un "NAS sparito", per
   un file aperto dal PC alla scadenza dell'attesa) e' un lavoro normale dello stesso `NasOpsJobService` (si puo' chiedere dal secondo
   piano, UIDT no). Sotto Android 14 anche l'operazione lunga e' un lavoro normale, con la notifica postata dal servizio. Due id fissi di
   `JobScheduler` (1.000.101 e 1.000.102); WorkManager resta nell'intervallo 0–999.999 (`setJobSchedulerJobIdRange`).
3. **"Ferma" della notifica**: un broadcast immutabile ed esplicito sul pacchetto verso un ricevitore **registrato a runtime mentre il
   lavoro gira e non esportato** (`RECEIVER_NOT_EXPORTED`): nessun componente nuovo nel manifest. E' l'unica azione su una notifica
   dell'app (eccezione dichiarata a C17: ferma, non distrugge). Lo "Stop" del Task Manager di Android vale come "Ferma".
4. **E-N9 (sola lettura)**: nessuna prova preventiva per share (`ShareAccessView.ALL_WRITABLE`): la dice il NAS quando si scrive
   (l'elemento si ferma con `PERMISSION_DENIED`/`READ_ONLY`, contato in Diagnostica). Una prova per area resta per il campo (F15).
5. **Viewer**: un media del NAS ha il riferimento del NAS solo se una copia leggibile sul telefono non c'e' (`thumbRefAt`): il bersaglio
   ha `onPhone = false` e "Solo dal telefono / Ovunque" (E-D1) dal viewer non e' raggiungibile per un media su telefono e NAS (che si
   apre con il riferimento del telefono). Il piano e l'esecutore lo coprono (P12); la voce nel viewer e' un residuo per il filone grafico.
6. **Dati personali dalla Libreria**: preferiti e nascosti continuano a passare dalle azioni della Libreria (E-H1: prima l'indice, poi il
   catalogo); la sincronizzazione li adotta (`adoptLocal`) al giro dopo, accorpato a 30 s con l'app aperta, e `reproject()` gira dopo
   ogni scansione e ogni sincronizzazione dell'indice con novita'. "Mostra le copertine degli album" diventa un'impostazione personale
   (il file locale resta come specchio, la scelta fatta prima della 4B diventa la prima voce); le esclusioni dell'area privata vengono
   dall'impostazione personale.
7. **Un'operazione chiusa fa riverificare il NAS** (`NasWorkScheduling.scanNow`): la Libreria segue spostamenti e cestino senza
   aspettare il giro delle 6 ore.
8. **Cartelle NAS → viewer**: un file toccato si apre nel viewer della Libreria sul suo media, solo se la Libreria lo mostra (un nascosto,
   escluso o non ancora scansionato non si apre).
9. **Diagnostica**: otto codici nella sezione "NAS nella Libreria" (nessuna sezione nuova: `DiagSection` e' in core-common, fuori
   perimetro): `NAS_OPS_STATE` (E-D6), `NAS_OPS_LOCKED` (E-N13), `NAS_OPS_FAILED` (E-D5, E-D9, E-D13, E-N9, E-N11), `NAS_TRASH_STATE`
   (E-D2), `NAS_TRASH_PURGE` (E-D4, E-S11), `NAS_INDEX_MAINTENANCE` (M6), `NAS_PERSONAL_STATE` (E-H4), `NAS_PERSONAL_REJECTED`
   (E-F5, D26). `Fase4BCatalogTest` legge §2.7 e §2.9 dal documento: E-D1, E-D3, E-D7, E-D8, E-D10, E-D11, E-D12, E-N7, C19 ed E-O7
   non sono avvisi (motivo scritto). E-N11 esce dalle eccezioni della 4A.
10. **Chrome G2 nel design system**: `GalleryTopChrome`, `GalleryTopEdgeScrim`, lo stile adattivo e l'interfaccia `GalleryChromeContent`
    (cio' che passa sotto: la griglia la implementa con un adattatore) stanno in `core-designsystem/design/GalleryTopChrome.kt`;
    `feature-library` li usa con gli stessi nomi interni, `feature-nas` al posto della sua copia. Verificato registrando gli screenshot
    prima e dopo: le 188 immagini delle griglie (3-P7, 3-P10) sono **identiche byte per byte**; le 240 della 4B differiscono al
    massimo di 2/255 su un canale, solo nei pezzi della riga in alto (il velo ora e' calcolato pezzo per pezzo come nelle griglie).
11. **Banco delle interruzioni**: tolte le esclusioni del fake (D1–D4 corretti da P12): 2.000 giri sulle operazioni vere e 2.000 sul fake,
    zero violazioni; `ChaosKnownDefectsTest` diventa una regressione.

**Correzioni in moduli di altri pacchetti** (firme pubbliche invariate: solo parametri nuovi con un default)
- `core-nasops` (solo test): `FakeChaosStackFactory` senza esclusioni, `ChaosKnownDefectsTest` come regressione.
- `feature-viewer`: `ViewerServices.nasActions`, `viewerMenuEntries(nasActions, nasDelete)`, aggancio in `ViewerOverlay`, `NasNoticeView`.
- `feature-library`: `CollectionsTab(extraGroups, onOpenEntry, entryDetail)`; `GridPageChrome` delega al design system.
- `feature-nas`: `NasPage` usa la chrome condivisa (tolti `NasTopChrome`, `NasTopEdge`, `NasDimens.TopEdgeFade`, interni).
- `feature-diagnostics`: codici, voci, testi ed elenco delle chiavi della 4B; `Fase4ACatalogTest` aggiornato (sezione condivisa, E-N11).

**Cosa resta per le prove sul campo** (oltre a F1–F14 della 4A, che si provano insieme): **F15** (crea, rinomina anche solo di
maiuscole, sposta nella share e tra share, elimina, ripristina, file aperto in un editor sul PC, nome in conflitto, cartella intera;
anche la sola lettura di un'area, scostamento 4), **F16** (runner dell'area di prova ≥ 400 interruzioni, quando il file delle
credenziali sara' aggiornato: al P16 il login di prova e' fallito con `AUTH_FAILED` e non si e' riprovato; script del telefono ≥ 100),
**F17** (preferito e nascosto sull'S25 → profilo del tablet con lo stesso account, non `test2`), **F18** (una settimana con Notte e una
con Bilanciato), **F19** (APK di release: operazioni, UIDT e "Ferma" con R8), **F20** (pulizia con la ritenzione accorciata **solo in
debug**: oggi la ritenzione e' una costante di `TrustLimits`, serve un aggancio di debug in `core-nasops`/`DeveloperTools` prima della
prova). Da guardare sull'S25: la notifica UIDT (Android 14+), "Ferma" dalla notifica e dal Task Manager, la ripresa dopo `am kill`.

**Per la revisione di sicurezza della 4B**: il ricevitore di "Ferma" (runtime, non esportato) e l'eccezione a C17; gli id fissi di
`JobScheduler`; `:app` che non costruisce conferme e i lavori che chiamano solo `resumeAll` e la pulizia; i punti di P12 (rinomina che
attende se il viewer ha il file aperto; sorgente bloccata dopo la pubblicazione → doppione visibile fino a 24 ore; "Ferma" perso con la
morte del processo, O3; record del cestino saltato senza identita' di firma) e di P13 (L29: autore non autenticato per voce; una revoca
non ripristina il valore sovrascritto; id dei media in chiaro nel registro locale dei dati personali).

**Dubbi grafici** (per il filone grafico): nomi lunghi che vanno a capo a meta' parola con il testo a 1,3× (P14); sul tablet la capsula
dei comandi all'estremo destro (P14); il gruppo "Sul NAS" in Raccolte con le righe di Impostazioni (funzionale); sul tablet "Vedi" dopo
un'operazione apre "Operazioni in corso" a schermo intero invece di cambiare il pannello accanto; l'esito di "Sposta"/"Elimina…" nel
viewer usa l'avviso breve in alto; la notifica delle operazioni (solo numeri, una barra).

### Revisione di sicurezza della 4B: correzioni (15/10)
Fonte: `docs/review/4b-fase4b.md` (R1-R11 corretti, ognuno con il suo test; i cinque `SecurityReview4bTest` verdi senza modifiche;
I1, I4, I6, I10 corretti). Regola di tutte le scelte: **ogni correzione riduce cio' che si puo' cancellare, mai il contrario**. Quelle
che l'utente vede (da confermare):

- **R1, R5: i 30 giorni del cestino.** Contano da quando il file e' entrato **davvero** nel cestino (non dalla voce scritta prima: dopo
  una pausa di un mese il file ha comunque 30 giorni) e mai da prima della fine dell'ultimo salto dell'ora del NAS accettato (24 ore
  stabili o il gesto): dopo un salto il cestino riparte con 30 giorni pieni. Con il salto in corso "Elimina" sposta i file nel
  cestino ma l'operazione resta **in pausa "ora del NAS"** finche' l'ora torna fidata; poi si chiude da sola alla prima ripresa.
- **R2: eliminare una cartella.** Il foglio dice quanti file e quanti byte contiene davvero (contati prima del foglio, scendendo in
  tutte le sottocartelle) e, oltre 500 file, una riga in evidenza "Sono molti file". Una cartella che non si riesce a contare (oltre
  100.000 file, elenco troncato) **non si elimina in un colpo**: il foglio dice "troppi elementi". Se fra il foglio e il tocco la
  cartella e' cresciuta, non si sposta niente (l'elemento si ferma con "il file e' cambiato").
- **R3: la pulizia delle cartelle cestinate** cancella solo cio' che l'app ha visto dentro la cartella prima di cestinarla (inventario,
  per identita' e timbro), non scende in cartelle che non tornano e non attraversa un collegamento riconoscibile (L35). Qualunque dubbio:
  la cartella resta nel cestino, anche dopo 30 giorni, ed "Elimina subito" la rifiuta; si toglie dal PC o si ripristina (L36).
- **R4: al massimo 200 file e 20 GiB eliminati per giorno** dalla pulizia automatica (contano anche "Elimina subito" e le voci gia'
  sparite: contare di piu' e' la direzione sicura); un cestino grande si svuota in piu' giorni. La prima voce del giorno passa sempre.
- **R6: "Elimina subito" vuole due gesti distinti** (almeno 600 ms fra i due tocchi): un doppio tocco sul primo pannello non elimina.
- **R7: ogni foglio di conferma** (anche quelli della Libreria) nasconde le sovrapposizioni delle altre app mentre e' aperto (un filtro
  della luce blu sparisce per quel momento).
- **R8: le operazioni dell'app non fanno scattare il congelamento** (eliminare o spostare fuori dalla share 60 foto in una settimana):
  vale solo per i file che il diario di questo telefono ricorda con la loro identita'; tutto il resto conta come prima.
- **R9: "Ferma" vale anche se l'app muore subito dopo**: alla riapertura l'operazione si annulla (si porta a termine solo cio' che e'
  oltre il punto di non ritorno), non riparte.
- **R10:** rinominare, spostare o eliminare dal viewer il video che si sta guardando ferma la riproduzione un attimo prima.
- **R11, con la scelta dell'utente del 15/10 («Lascia i nascosti dove sono», qui sotto):** spostando nell'area comune elementi che
  l'utente ha nascosto, il foglio dice quanti sono e chiede cosa farne; nel viewer, per un media solo, resta "L'hai nascosto".
- **P16, "Elimina…" nel viewer per un media sul telefono e sul NAS**: c'e' un solo "Elimina…" con "Solo dal telefono" (di default) e
  "Ovunque" (cestino di sistema del telefono e cestino del NAS per ogni copia del NAS dove si hanno i diritti; le aree in sola lettura
  restano come sono, il foglio le elenca). Da li' niente "Sposta" ne' "Rinomina" (sono della copia del NAS, nel viewer del NAS).
- **Solo debug, prova F20:** `adb shell` scrive un numero di minuti in
  `/sdcard/Android/data/<id>/files/debug-trash-retention-minutes.txt` e il cestino del NAS tiene le voci quei minuti (1 minuto - 30
  giorni). Nella release non esiste (`check.ps1` lo cerca nel dex).
- **Residui:** L35 (collegamento gia' presente, non riconoscibile senza la voce `..` e senza media nel catalogo: da provare sul WD),
  L36; I2, I3, I5, I7, I8, I9 come scritti nella revisione; il doppione oltre 24 ore (I10, prima parte).

### ✅ DECISO (utente, 15/10): R11, «Lascia i nascosti dove sono»
Fonte: `docs/review/4b-fase4b.md`, R11 ("corretto, con scelta"). Spostando nell'**area comune**, da Cartelle NAS, una cartella (o dei file)
che contiene media che l'utente ha **nascosto**, i familiari li vedrebbero: l'utente ha voluto poter scegliere di lasciarli dove sono.

- **Il foglio.** Quando ci sono nascosti il foglio dello spostamento nell'area comune ha **due scelte**, due righe da 48 dp con la
  conseguenza scritta sotto (la forma del foglio "Elimina" della Libreria, linguaggio G2): **«Lascia i N nascosti dove sono»**,
  **preselezionata** perche' e' la piu' prudente, e **«Sposta tutto (N nascosti diventano visibili ai familiari)»**. Il foglio segue la
  scelta: titolo, numeri e pulsante sono quelli del piano scelto, e il riepilogo elenca cio' che resta ("Restano dove sono: nascosti da te
  (N)"). Spostando dentro la stessa area non c'e' nessuna scelta: i nascosti non diventano visibili a nessuno in piu'.
- **Con «lascia»** i nascosti restano dove sono, nell'area privata, anche quelli dentro una cartella spostata. **La cartella d'origine non
  si toglie e non si svuota**: resta con i soli nascosti. Una sottocartella che resterebbe vuota solo perche' aveva soli nascosti non si
  ricrea nell'area comune (non ci arriva nemmeno il suo nome); una cartella davvero vuota si sposta come sempre.
- **Sono due piani, con due impronte.** L'impronta del piano include l'elenco degli esclusi: la conferma data per «lascia» **non puo'
  avviare «sposta tutto»**, ne' il contrario (R2 della 4B), e un piano che elenca un file fra i lasciati e lo sposta comunque non parte
  (`PlanViolation.LEFT_BEHIND_MOVED`). I lasciati non sono nel diario: una ripresa dopo un'interruzione non li tocca mai.
- **Il piano che lascia si ricontrolla.** La schermata chiede di nuovo al catalogo se nel piano senza i nascosti ne e' rimasto qualcuno
  (un file diventato nascosto fra i due piani) e, se si', ripianifica (al massimo 3 volte); se non riesce a controllare o a toglierli
  tutti non mostra nessun foglio, ma una frase ("Nel frattempo e' cambiato qualcosa").
- **Se tutto cio' che si sposta e' nascosto**, lasciandolo non resta niente da spostare: la scelta c'e', il pulsante dice «Niente da
  spostare» ed e' spento; «Sposta tutto» resta possibile, sapendolo.
- **Chi dice quali sono i nascosti** e' `:app` (catalogo e dati personali), mai il NAS; `core-nasops` riceve solo percorsi da escludere
  (`OpRequest.Move.leaveBehind`) e non vede dati personali. `NasAreas.hiddenItems(piano)` ha un'implementazione di serie che ricava
  gli elementi da `hiddenAmong` su pezzi del piano (ricerca a meta': pochi nascosti, poche domande), quindi `:app` non e' cambiata;
  potra' rispondere con una sola ricerca nel catalogo.
- **Fuori da questo lavoro:** il foglio "Sposta" del viewer (un media solo: dice "L'hai nascosto", nessuna scelta); le immagini dei
  fogli nuovi in `docs/screenshots/4-P14/` (si registrano con `recordRoborazziDebug`; i test `foglioSpostaNellAreaComune*Nascosti*`
  verificano per ora solo che compongano); la prova sul campo (sotto, in `4b-fase4b.md`).

### Prova F16 sul NAS vero: operazioni ferme "in attesa del PC" senza un PC (15/10)
Fonte: `docs/prova-500.md` §6bis. Il primo giro lungo della prova delle interruzioni sul WD (493 giri) ha avuto 13 giri con operazioni
rimaste in pausa per sempre (e, di conseguenza, copie arrivate con la sorgente ancora al suo posto e temporanei non pubblicati). Nessun file
perso o rovinato.

- **Causa.** Il file "aperto da un altro programma" lo teneva aperto **il telefono stesso**. Un nostro handle di lettura ancora aperto sul
  NAS (nel banco: quello di un processo morto a meta' di una lettura, perche' il banco passava la connessione del processo morto a quello
  nuovo; nella vita vera: una chiusura partita su un altro thread e non ancora arrivata, la Libreria che legge, una connessione persa che il
  NAS tiene ancora per minuti) non condivideva la cancellazione: Samba rifiutava la nostra rinomina di quel file (`SHARING_VIOLATION`) e
  l'operazione aspettava "il PC" con attese da 1 minuto in su. In piu', il motivo della pausa non era nel diario (`motivo=nessuno`): dopo un
  riavvio la schermata diceva "condizioni". Due giri senza nessuna interruzione erano la corsa fra la chiusura del temporaneo (su un altro
  thread) e la sua pubblicazione. Provato rigiocando un giro rosso sul NAS vero e con il simulatore che ora tiene gli handle come Samba.
- **Correzione.** (1) La lettura **condivide la cancellazione**: un nostro handle di lettura non blocca mai la rinomina o il cestino di quel
  file (provato sul WD). Le protezioni non cambiano: la rinomina condivide solo la lettura (nessuno scrive fra il controllo del timbro e la
  rinomina), la cancellazione con il controllo della dimensione resta esclusiva, la sorgente va nel cestino solo dopo la verifica completa.
  (2) **Chiusure ordinate**: ogni richiesta al NAS parte dopo le chiusure chieste prima di lei sulla stessa connessione, aspettandole al
  massimo 2 s ciascuna (sul WD una chiusura in volo mentre la connessione si chiude non torna mai: smbj la aspetta 60 s, e senza tetto ogni
  richiesta dopo restava ferma con lei). (3) Un'apertura annullata mentre il NAS
  risponde chiude il suo handle. (4) **Il motivo della pausa sta nel diario** (stessa colonna `error_code`, codici distinti) e nella
  Diagnostica (`NAS_OPS_STATE`: pause per motivo; una pausa senza motivo conta come `UNKNOWN` e alza la gravita'). Le pause restano quelle di
  prima e riprendono da sole quando la condizione passa (lavoro di ripresa programmato; un file davvero aperto dal PC si aspetta fino a 24
  ore, poi l'elemento si ferma e lo dice).
- **Banco e simulatore.** La morte del processo chiude la sua connessione, come fa il sistema sul telefono. Il simulatore SMB tiene gli
  handle dei client con le stesse condivisioni del trasporto vero, le connessioni perse dal telefono ma non ancora dal NAS e le cartelle che
  Samba non rinomina; con il codice di prima il banco sul simulatore ha 49 giri rossi su 600 con la stessa firma del WD.
- **Esito sul NAS vero (15/10, sera):** area pulita, giro corto 27 giri / 20 interruzioni / 0 violazioni, poi **537 giri, 400
  interruzioni (264 morti del processo, 22 durante una lettura), 0 violazioni**, 142 minuti, 58 GB riletti con SHA-256; area di nuovo
  pulita e verificata con entrambi gli account.
- **Osservazione (decisa il 15/10, vedi «Cartella in uso dall'app» qui sotto).** Sul WD una cartella con un file aperto **dalla stessa
  connessione** non si rinomina (`ACCESS_DENIED`, Samba `strict rename = no`; un file aperto dal PC invece non conta). Le chiusure ordinate
  tolgono il caso dell'inventario prima del cestino (che con le chiusure in volo faceva fallire l'elemento "permesso negato"); restava la
  Libreria che sta leggendo un file dentro una cartella mentre la si sposta o la si elimina: l'elemento si fermava con "permesso negato"
  (motivo visibile, niente perso).

### ✅ DECISO (utente, 15/10): «Cartella in uso dall'app» e' un'attesa breve, non un rifiuto
Fonte: l'osservazione qui sopra (prova F16 sul NAS vero). Se la Libreria sta leggendo un file (una miniatura, un'anteprima) dentro una cartella
che si sposta, si rinomina o si elimina, Samba risponde `ACCESS_DENIED` come per un permesso negato vero. L'utente ha deciso di trattarlo come
**un'attesa breve**: l'app riprova da sola dopo qualche secondo invece di fermare l'elemento.

- **La regola e la prova.** Vale **solo** quando la cartella ha sotto un handle aperto **dal nostro stesso processo**. La prova e' il registro
  degli handle propri (`OwnHandles`, `core-nas/session`): ogni lettura di media e ogni elenco aperti dalla sessione ci stanno dall'apertura alla
  `CLOSE` arrivata al NAS (una chiusura in volo conta ancora), per share e percorso, uno per sessione (Samba controlla gli handle della stessa
  connessione). Un `ACCESS_DENIED` su una **cartella** con almeno un handle nostro sotto non e' un permesso negato; **senza** (share in sola
  lettura, permessi dell'account, handle fuori dalla cartella, un file invece di una cartella, un rifiuto che resta anche dopo il rilascio)
  resta `PermissionDenied` e l'elemento si ferma come prima (E-N9). Un handle che il registro non conosce fa solo tornare al comportamento di
  prima: mai il contrario.
- **Che cosa fa.** (1) Le primitive (`SmbNasFileOps.rename` e `trashMove`, quindi anche la rinomina di sole maiuscole e il ripristino)
  **rilasciano** i nostri handle di lettura sotto la cartella, come il viewer per i file (R10): `SmbRandomFile.release` chiude l'handle sul NAS,
  la lettura dopo lo riapre da sola e confronta il timbro; se una lettura lo sta usando lo chiude l'ultima che lo lascia, mai una sotto i piedi
  dell'altra. Poi aspettano che le `CLOSE` arrivino al NAS e riprovano, fino a 3 volte. (2) Se qualcosa e' ancora aperto (un elenco a meta', una
  lettura che si e' riaperta) rispondono `RenameOutcome.FolderInUse`: niente e' cambiato. (3) L'esecutore (`NasOpsEngine`) riprova **dopo 1, 2,
  4, 8, 16 e 32 secondi** (`OwnFolderBackoff`, 63 s in tutto), senza uscire dal giro e tenendo il lucchetto delle scritture; il tetto vale **per
  giro** (con molte cartelle bloccate non si aspetta un minuto per ognuna) e l'attesa si interrompe con «Ferma», con le scritture ferme o a fine
  del tempo della ripresa. (4) Se dopo l'ultima prova la cartella e' ancora bloccata diventa un'**attesa normale** (`WAITING_LOCKED`, come un
  file aperto dal PC: prima prova dopo 1 minuto, poi le attese crescenti fino a 24 ore): mai un errore definitivo.
- **Il motivo.** Nel diario e in Diagnostica l'elemento in attesa risulta `locked`, come per un file aperto: nessun valore nuovo in
  `OpErrorCode` o `PauseReason`, perche' `feature-nas` li legge con un `when` esaustivo (fuori dal perimetro di questo lavoro). Le attese brevi
  sono nei log (`NasOps`: solo codici e millisecondi). Una frase dedicata («Cartella in uso dall'app, riprovo») e una voce di Diagnostica
  separata chiedono un valore nuovo in quei due contratti e le righe in `feature-nas` (`NasMessages.kt`, stringhe) e in `app`
  (`NasOpsDiagnostics`): da fare insieme, quando si tocca la UI.
- **Guasti e morte del processo durante l'attesa.** L'attesa breve non scrive niente nel diario: il passo e' quello gia' scritto (`RENAMING`,
  `RECORDED`) e la ripresa riparte da li' su una connessione nuova (il NAS chiude gli handle del processo morto); se la rinomina era gia'
  avvenuta con la risposta persa la riconosce (sorgente assente, destinazione con la stessa identita').
- **Prove sul simulatore** (`InMemorySmbBackend`, che rifiuta gia' la rinomina di una cartella con un handle dello stesso client sotto):
  `OwnFolderHandlesTest` e `OwnHandlesRegistryTest` (core-nas: miniatura aperta, piu' letture, elenco a meta', lettura in corso, rilascio e
  rilettura, cestino, i `ACCESS_DENIED` veri), `OwnFolderWaitTest` (core-nasops, esecutore vero: rinomina, maiuscole, cestino, attese 1-32 s,
  attesa normale, tetto per giro, scritture ferme, permesso negato vero, share in sola lettura, guasti `FaultPlan` e morte del processo
  durante l'attesa). Il banco delle interruzioni sul simulatore SMB (600 giri, 2.172 interruzioni, 0 violazioni) tiene ora, nel 40% dei passi
  su una cartella, una lettura della Libreria aperta sotto di lei (233 letture in 600 giri); senza il rilascio i giri rossi sono 26 [8].

## Fase 5: progetto (15–16/10)
Fonte: `docs/fase5-progetto.md` (§0.1 decisioni, §9 domande, §9.1 risposte). Le decisioni valgono come scritte lì; qui il
riassunto in una riga ciascuna, con il rimando alla sezione. Le modifiche agli altri documenti (§0.3 del progetto) sono in
`docs/formato-nas.md` (N1–N5), `docs/sicurezza.md` (C10, C12, C13, C17, C18, §4-bis, L37–L44, checklist, §8),
`docs/regole-attivita.md` (§3, §5, §6, §8), `docs/fase4-progetto.md` (§1.4, §2.11), `docs/grafica-progetto.md` (§12.11) e
`README.md` (moduli). Lo schema v4 (`docs/modello-dati.md` §12) lo scrive P1.

Diciotto pacchetti in due metà: **5A** "Il backup al sicuro" (P0–P7 e P15 copie simili), **5B** "Versioni, Libera spazio,
integrazioni" (P0-B, P8–P14 e P16 riordino); tappe M1, M2 (5A) e M3 (5B) (§4.2, §4.4).

**Risposte dell'utente alle domande del §9 (15/10) e del §2.11 (16/10)**
| # | Domanda | Risposta | Effetto |
|---|---|---|---|
| Q1 | Dividere la fase in due metà | (a) **5A** e poi **5B** | D1 confermata |
| Q2 | Dove conservare sul NAS le versioni precedenti | (a) cartella **`Versioni/` alla radice di ogni share** | D17; punto aperto 5.4 del piano chiuso |
| Q3 | Quando il primo backup vero dell'S25 sull'area privata dell'utente | **(b) solo a fine 5B** | D12 resta acceso fino alla fine della 5B; la stima ±30% (5.1) si misura allora |
| Q4 | Copia privata dei media spostati nell'area comune | **(b) solo quando sparisce la copia del telefono** (lettera del piano 5.3) | Niente copia privata subito dopo lo spostamento; resta la protezione dei 30 giorni del cestino, dichiarata come limite (L44) |
| Q5 | Componenti nuovi (§6.4) | (a) **sì a tutti**: `BackupJobService`; `PickerActivity`, `ShareReceiverActivity`, `MediaDocumentsProvider`, `FileProvider` | D29 approvata; §4-bis scritto con P0; componenti e policy del manifest in P7 (5A) e P14 (5B) |
| Q6 | Correzioni su HEIC, RAW e video | non chiesta: vale (a), nel file solo per JPEG, PNG e WebP | L40 |
| Q7 | "Proteggi subito le foto nuove" anche con Notte | (a) **sì** | D34 approvata |
| Q8 | Screenshot e cartelle aggiuntive sul NAS | non chiesta: vale (a), in sottocartelle col loro nome | D6 |
| Q9 | Cosa propone di default "Libera spazio" | (a) tutto il sicuro **tranne gli ultimi 30 giorni**, con i filtri | D21 |
| Q10 | Riverifica a rotazione delle copie private | (a) **sì**, ogni copia almeno una volta l'anno | D14 |
| Q11 (16/10) | Copie sul NAS "quasi uguali" ma non identiche | **nuova opzione**: si caricano sempre, ma si uniscono in una cella come in Google Foto e Samsung Gallery; tutte le copie si possono vedere, c'è una funzione per separarle, di default si mostra quella a risoluzione maggiore o compressione minore | D36, §2.12, P15 |
| Q12 (16/10) | Dove stanno i backup già fatti e con quale strumento | Nell'area privata dell'utente; fatti con l'app di WD e con un'altra app o un cloud | Ci si aspettano copie identiche e simili; i numeri veri dalla scansione F2 |
| Q13 (16/10) | Le copie già presenti restano o si riordinano | **(b) riordino proposto dopo**, con conferma | D37, §2.13, P16 |

**Aperto**: Q14 (da fare all'avvio di P16, con i numeri della scansione vera): dove vanno i file dei vecchi backup che non sono
più su nessun dispositivo (telefoni precedenti).

**Decisioni D1–D37** (rimando: `docs/fase5-progetto.md`, sezione indicata; stato: *deciso* = confermato o senza alternative aperte)
| # | In breve | Rimando | Stato |
|---|---|---|---|
| D1 | Fase in due metà: 5A (backup, "al sicuro", stato; sul NAS solo file nuovi, dal telefono non si toglie niente) e 5B (versioni, correzioni, "Libera spazio", integrazioni) | §0.1, §4.2 | Deciso (Q1) |
| D2 | `core-backup` vede il NAS solo con `NasBackupOps`, una vista in sola aggiunta (`@AppendOnlyNasAccess`): niente cestino, rinomina libera o cancellazione | §0.1, §1.1–§1.2 | Deciso; contratto e test di architettura in P0 |
| D3 | Caricamento con diario (`backup_item`) e macchina a stati; valori scritti prima dell'uso, passi idempotenti | §0.1, §2.2 | Deciso |
| D4 | Verifica = rilettura completa del temporaneo dal NAS prima del nome definitivo; dopo, solo il timbro; tre verifiche fallite = `VERIFY_FAILED` | §0.1, §2.2 | Deciso |
| D5 | Ripresa dei file grandi: punto di ripresa ogni 64 MiB, `reopenTemp`, verifica completa alla fine | §0.1, §2.2 | Deciso |
| D6 | `Backup/<dispositivo>/AAAA/MM/` per data di scatto, `Senza data/`; screenshot e cartelle extra in `<Nome>/AAAA/MM/`; mai una sostituzione | §0.1, §2.1 | Deciso (Q8) |
| D7 | Cartella del dispositivo scelta una volta al primo backup; un nome già presente chiede; reinstallazione: "continua lì" | §0.1, §2.1 | Deciso |
| D8 | Deduplica byte per byte: solo area privata del proprietario, candidato riletto per intero; l'indice non basta | §0.1, §2.3 | Deciso |
| D9 | Guardiano delle copie private: copia lato server dall'area comune, verifica completa, `Backup/Area comune/AAAA/MM/`; non subito dopo lo spostamento | §0.1, §2.3 | Deciso (Q4 (b)) |
| D10 | Chi carica prepara: miniature e anteprime dai byte del telefono, nella stessa lettura dell'hash | §0.1, §2.2 | Deciso |
| D11 | Foto prima dei video, dal più recente; due file in volo; tappe da 8 minuti; primo backup e "Fai ora" come lavori avviati dall'utente | §0.1, §2.8 | Deciso |
| D12 | Backup solo sulle share di prova (`backupTestSharesOnly`, anche in release e non spegnibile) | §0.1, §5.3 | Deciso; acceso fino alla fine della 5B (Q3 (b)) |
| D13 | Recinzione del telefono in debug con gli account di prova: sorgenti `mcg-prova`, destinazione `mcg-campo/Backup/`; assente dalla release | §0.1, §5.3 | Deciso |
| D14 | Verifiche e hash completi in `core-backup` (`SafetyVerifier`): non verificate, `FULL_HASH`, sul momento, riverifica a rotazione | §0.1, §2.3 | Deciso (Q10); chiude ciò che la Fase 4 non ha realizzato |
| D15 | Segni per media: `PHONE_ONLY` e `SAFE` come prima, nuovo `BACKUP_PROBLEM` (bit 14); "in coda", "in caricamento", "in verifica" solo nel viewer e nella pagina | §0.1, §1.5 | Deciso |
| D16 | `BackupStatus` come contratto: terza voce del piede, pagina Backup, riga nel viewer; notifiche solo per primo backup e "Fai ora" | §0.1, §2.6 | Deciso |
| D17 | Versioni: l'attuale al solito percorso, le precedenti in `<share>/Versioni/…`; verifica, archiviazione, pubblicazione; nessuna cancellazione automatica | §0.1, §2.4 | Deciso (Q2 (a)), 5B |
| D18 | Modifiche fatte sul telefono → versione; "Ripristina originale" = scambio sul NAS senza caricare; "Salva copia" = media nuovo | §0.1, §2.4 | Deciso, 5B |
| D19 | Correzioni scritte nel file (JPEG, PNG, WebP con `ExifInterface`); sul telefono solo se già al sicuro e con `createWriteRequest` | §0.1, §2.4 | Deciso (Q6), 5B |
| D20 | Video modificato e salvato sopra: versione sul NAS e attività in sospeso "elimina l'originale?" dal foglio di `feature-nas` | §0.1, §2.4 | Deciso, 5B |
| D21 | "Libera spazio": avviso forte, guardiano, verifica sul momento (6 ore), controllo del timbro, cestino di Android a lotti, "Libera subito" con due gesti | §0.1, §2.5 | Deciso (Q9), 5B |
| D22 | "Elimina solo dal telefono" di un media non al sicuro propone "Prima mettilo al sicuro"; "Ovunque" comprende le copie di tutte le versioni | §0.1, §2.5 | Deciso, 5B |
| D23 | Scegliere foto per le altre app: `PickerActivity` e `MediaDocumentsProvider`; solo-NAS con descrittore a richiesta; mai i nascosti | §0.1, §2.7 | Deciso, 5B; chiude il punto aperto sul selettore del piano |
| D24 | Ricevere condivisioni: `ShareReceiverActivity`, copia subito con tetti e tipo dai byte, poi "Salva sul NAS" | §0.1, §2.7 | Deciso, 5B |
| D25 | "Modifica con…": sessione di modifica (Samsung Gallery) o copia in cache servita da `FileProvider` (solo-NAS) | §0.1, §2.7 | Deciso, 5B |
| D26 | Condividere senza posizione: copia in cache senza GPS, ricontrollata con due lettori | §0.1, §2.7 | Deciso, 5B |
| D27 | Schema v4 in una sola migrazione (3→4 mai distruttiva), anche con le tabelle della 5B | §0.1, §1.3 | Deciso |
| D28 | Nessuna dipendenza nuova | §0.1 | Deciso |
| D29 | `BackupJobService` (5A); `PickerActivity`, `ShareReceiverActivity`, `MediaDocumentsProvider`, `FileProvider` (5B); nessun permesso nuovo | §0.1, §6.4 | **Approvata dall'utente (Q5 (a))** |
| D30 | UI come stati e contratti; composable solo con i componenti G2 esistenti | §0.1, §1.5 | Deciso |
| D31 | Ora: nessuna scadenza nel backup; validità di verifica e conferme con l'orologio monotono; attività in sospeso con l'ora del NAS | §0.1 | Deciso |
| D32 | Congelamento ed emergenza fermano backup, guardiano, versioni e "Libera spazio"; i movimenti delle versioni non fanno scattare il congelamento | §0.1 | Deciso |
| D33 | Temporanei del backup `.mycloudgallery/tmp/<io>-bk-<id>`; la pulizia dei propri file rispetta quelli citati da entrambi i diari (`CitedTemps`) | §0.1, §1.4 | Deciso |
| D34 | "Proteggi subito le foto nuove": con qualunque preset la foto appena scattata si carica appena c'è il Wi-Fi di casa | §0.1, §2.8 | **Approvata dall'utente (Q7 (a))** |
| D35 | Versioni di media non propri: area comune accanto all'originale dopo un foglio; area in sola lettura = media nuovo in `Backup/<dispositivo>/Modificate/` | §0.1, §2.4 | Deciso, 5B |
| D36 | Copie simili (stesso scatto, byte diversi): si caricano sempre e si uniscono in una cella (`SIMILAR_COPIES`); copertina = più pixel, poi meno compressione, poi l'originale della fotocamera; "Mostra questa come principale" e "Separa" | §0.1, §2.12 | Deciso (Q11), P15 |
| D37 | Riordino dei vecchi backup: proposto dopo il primo backup vero, come piano di spostamenti della 4B con conferma | §0.1, §2.13 | Deciso (Q13), P16 |

**D36 cambia la proposta 5 del piano** (§3): le copie dello stesso scatto con byte diversi (ricompresse, EXIF riscritto, HEIC
convertito) si **uniscono in una cella** in Libreria; Utility → Doppioni resta per gli scatti simili ma diversi. I file identici
byte per byte restano "una volta sola" con tutte le posizioni, come prima.

**Dubbi grafici** (per il filone grafico): la terza voce del piede (Backup), la pagina Backup, il foglio "Libera spazio" con
l'avviso forte, il segno `PROBLEM`, il segno "copie" e la striscia delle copie simili del viewer: in `docs/grafica-progetto.md` §12.11.

## Fase 5A: integrazione nell'app (P7, 16/10)
Il backup (P2 motore, P3 verifiche e guardiano, P4 piano e impostazioni, P5 schermate) e le copie simili (P15) sono collegati nel grafo
vero. Tutte le prove sono sul NAS finto di `core-nas` e su un MediaStore finto con i byte delle foto: nessun NAS vero, nessun telefono,
nessun emulatore. **Il backup in release non scrive niente su una share vera** (D12): e' la prima cosa che si controlla.

**Che cosa c'e'** (`app/.../backup/`, `work/`, `notify/`, `di/BackupModule.kt`, `main/BackupHostEffect.kt`, `navigation/BackupNavigation.kt`)
- **`backup/BackupGraph`**, dentro `GalleryGraph` dopo `NasGraph` e con la stessa vita. Due tempi, come il NAS: il costruttore non
  fa I/O (diario `RoomBackupStore`, impostazioni su DataStore in `noBackupFilesDir`, cancello, orologi, stati per le schermate); `start`, con
  il motore del NAS partito, apre l'area privata, costruisce motore, verificatore e guardiano. `:app` non nomina mai le primitive del backup
  (`BackupEngineFactory`, `BackupSafetyFactory`: lo verifica `Phase5ArchitectureTest`). Motore, verifiche e guardiano girano **uno alla
  volta** (`exclusive`).
- **Il cancello** `AppBackupGate` (§1.4, D12, D32), nell'ordine: famiglia non pronta o senza permesso di scrittura → area privata assente →
  **share non di prova (`FeatureFlags.backupMayWriteTo`) → `TestOnly`** → emergenza → congelamento → area in sola lettura → aperto. Il
  motore lo guarda a ogni elemento; verifiche e guardiano si fermano con le prime tre ragioni, non con emergenza, congelamento o sola
  lettura (leggere non fa male). **Come funziona D12**: `backupTestSharesOnly` e' vero in release e `FeatureFlags.forBuild` non lo spegne; una
  share che non e' di prova da' `TestOnly`, e allora il giro, il primo backup (UIDT), le verifiche, il guardiano e le tappe di D34 si
  fermano al cancello prima di toccare il NAS (nessun file, nessuna cartella `Backup/`, nessun temporaneo); lo stato diventa "Backup in
  prova" nella pagina, nel piede e in Diagnostica. La classe del cancello e' tenuta da R8 (`app/proguard-rules.pro`, `-keep` su `stateOf`):
  senza, R8 potrebbe fondere o inlineare la regola.
- **D13**: `DeveloperTools.backupFence()` (solo `app/src/debug`) restringe, con un account di prova, le sorgenti (`DCIM/mcg-prova/`,
  `Pictures/mcg-prova/`), le righe che il telefono apre (`MediaStorePhoneBytes`: una riga fuori recinzione risponde `NOT_SELECTED`, si
  fallisce chiusi), la radice di scrittura (`mcg-campo/Backup/`, `UploadConfig.allowedRoots`) e l'elenco delle cartelle da scegliere. In
  release `backupFence()` risponde null e la classe non esiste (passo "strumenti di debug nel dex" di `tools/check.ps1`).
- **Lavori** (`work/BackupWork.kt`, `BackupStages.kt`, `BackupJobService.kt`, `BackupJobs.kt`): `PhotoBackupWorker`, `VideoBackupWorker`
  (pesante: solo Wi-Fi di casa), `QuickPhotoWorker` (D34: regole sue, nessuna fascia, anche a batteria sopra la soglia, solo Wi-Fi di casa;
  spenta la regola non parte), `SafetyVerifyWorker` (guardiano, copie non verificate, `FULL_HASH`, rotazione annuale). Tutti a tappe
  (`StagedRun`, otto minuti) dentro `GalleryGraph.runIfActive`. Periodici: foto e video ogni 6 ore, verifiche una volta al giorno. **Nessun
  polling**: a fine lettura del telefono, `BackupWorkScheduling.afterPhoneRead` chiede (lavoro unico, `KEEP`) il tipo di lavoro che il
  piano dice che serve (`BackupPlanner.next`: D34, foto, video) e le verifiche se il guardiano ha da fare (E-B13); scansione del NAS e
  lettura dell'indice fanno partire il confronto delle copie simili. `BackupJobService` (UIDT su Android 14+, lavoro normale a una tappa
  sotto; id di JobScheduler 1.000.103, dopo 101 e 102 della 4B): il primo backup e "Fai ora", fino a 15 tappe per tipo (foto poi video), con
  "Ferma", al cancello chiuso e ai permessi del telefono si chiude, con un errore del NAS si fa riprogrammare, e con un'attesa delle
  regole o una conferma da chiedere finisce (lo dice la pagina, non un lavoro che ritenta). Notifica di avanzamento con soli numeri e
  "Ferma" (broadcast immutabile ed esplicito verso un ricevitore registrato solo mentre il lavoro gira, `RECEIVER_NOT_EXPORTED`).
- **D33**: `BackupGraph.citedTemps()` (i temporanei dei diari del backup non finiti) → `GalleryGraph.citedTemps` → `NasOpsConfig.citedTemps`
  di `OwnFilesCleaner`.
- **Notifiche**: canale "Backup" (importanza bassa, privato; notifica 1005) per l'avanzamento; il **riepilogo giornaliero** del NAS cresce di sei
  voci (backup fermo da N giorni con qualcosa in coda, quota, NAS pieno, N perse prima del backup, N copie non integre, N con la sola copia
  comune persa), sempre una notifica al giorno e soli numeri.
- **Diagnostica**: i 21 codici del §2.6 (`BACKUP_` + il codice di `BackupProblemCode`) e due di stato (`BACKUP_LAST_ROUND`,
  `BACKUP_SIMILAR_COPIES`), con voce di catalogo, testi e chiavi nell'elenco chiuso di `ExportKeys` (la famiglia `problem.<codice>` per i
  problemi); `BackupDiagnostics` raccoglie soli conteggi, byte, flag e codici.
- **Schermate**: le porte di feature-backup, feature-settings e feature-viewer (`BackupOverview`, `PhoneFolders`, `BackupRules`,
  `DeviceFolderProposals`, `BackupActions`, `BackupSettings`, `MediaBackupStates`, `BackupEntryStatus`) legate in `di/BackupModule` con
  null-object quando il backup non c'e'; la rotta Backup; il piede della Libreria (`BackupStatus` ridotto a `LibraryBackupStatus`); il tocco
  sul piede apre la pagina.
- **Copie simili (D36)**: `backup/SimilarCopiesGraph` lega `CatalogSimilarCopies` alla sorgente delle firme (pixel dal telefono, miniature
  del NAS, fotogrammi dei video) e alle scelte dell'utente su `SimilarCopiesChoices` di core-personal. Una sola istanza per il viewer
  (sezione "N copie", "Mostra come principale", "Separa", e `ViewerOutsideCopy` per aprire una copia che non sta nell'indice), la Diagnostica e il
  riepilogo del primo backup. Il confronto gira dopo la lettura dei metadati, dopo la sincronizzazione dei dati personali e dopo
  quella dell'indice.
- **F1**: `BackupCatalog.phoneRowOf` e' implementato nell'adattatore di `:app` (`CatalogBackupCatalog`, con la ricetta della porta), quindi
  il difetto F1 e' chiuso anche in produzione.
- **Azzeramento**: `GalleryGraph.shutdown` ferma il backup **per primo** (toglie i lavori in coda, aspetta il giro in corso), poi
  `AppLocalDataEraser`, che toglie anche le impostazioni del backup e la memoria dei giri (`BackupRunMemory`).
- **`tools/check.ps1`, passo D12**: oltre al campo e alla decisione nel dex e in `mapping.txt`, legge il bytecode della release con
  `dexdump` (build-tools, accanto ad aapt2) e pretende che `AppBackupGate.stateOf` chiami `FeatureFlags.backupMayWriteTo` e risponda
  `BackupGateState.TEST_ONLY` e che `BackupGraph` istanzi il cancello. Se `dexdump` non c'e' o non si legge la MainActivity il passo fallisce
  (non salta). **Prova che il passo morde**: tolta la chiamata a `backupMayWriteTo` dal cancello, ricostruita la release, il controllo
  dice "stateOf non chiama FeatureFlags.backupMayWriteTo"; rimessa, passa.

**Prove** (tutte nel modulo `:app`, NAS e telefono finti)
- `BackupIntegrationTest`: flusso completo (due foto nuove → un giro → copie verificate in `Backup/<dispositivo>/AAAA/MM/` byte per byte,
  nessun temporaneo rimasto, `SAFE` e piede "tutto al sicuro" → secondo dispositivo dello stesso account senza doppioni sul NAS → Diagnostica
  senza nomi → spegnimento: niente parte piu', lavori tolti); **D12** (account e share con nomi non di prova: `TestOnly`, nessun file, nessuna
  cartella, diario vuoto, anche per primo backup, verifiche e guardiano); **E-B15** (la foto appena scattata si carica subito con D34 e la
  modifica di Samsung "Salva" non tocca l'originale verificato); **D33** (il temporaneo citato dal diario sopravvive alla pulizia dei propri
  file, anche vecchio di tre giorni, e si toglie quando l'elemento si chiude).
- `BackupFenceIntegrationTest` (in `src/testDebug`): **D13**, con una foto vera della famiglia in `DCIM/Camera/` che non viene letta, caricata
  ne' messa nel diario e nulla in `Backup/` della radice.
- `SimilarCopiesIntegrationTest`: l'originale salvato dal backup e la stessa foto ricompressa nell'area comune sono **una cella sola** (la
  copertina e' l'originale; l'altra copia si ritrova dal catalogo per il viewer); "Separa" le rimette in due celle per sempre; la scelta
  viaggia come dato personale e il secondo dispositivo la riceve valida (vedi il limite sotto).
- `AppBackupGateTest` (ordine delle ragioni, D12 con la configurazione di release, chiusura al volo), `BackupPiecesTest` (piede, riepilogo del
  primo backup, motivi dell'attesa, verifiche leggere e pesanti, pagine filtrate, riepilogo giornaliero), `BackupWorkTest` (seguito di una
  tappa, vincoli e ritardo di WorkManager, il lavoro dell'utente a tappe e il suo tetto, errori del NAS), `BackupNotificationTest`,
  `LocalDataEraserTest` e `DiagnosticsWiringTest` estesi, e in `feature-diagnostics` `Fase5ACatalogTest` e `Fase5ACodeCatalogTest` (un test per
  codice: voce nel catalogo, testo, edge case, chiavi di esportazione).
- `tools\check.ps1` verde il 16/10: 3 min 9 s (con la cache di Gradle), dopo una prima esecuzione che aveva trovato un colore nominato a mano
  in `ViewerOutsideCopy` (`NoHandWrittenStyleTest` di feature-viewer), corretto con `MaterialTheme.colorScheme.scrim` e rifatto. APK debug e
  release prodotti; nel passo D12 il cancello e' provato nel bytecode ("stateOf -> backupMayWriteTo -> TEST_ONLY, istanziato da
  `BackupGraph`"). `:app:testDebugUnitTest`: 255 test, nessuno fallito. Nessun emulatore, `adb`, NAS o telefono usato.

**Scostamenti dal progetto (da confermare)**
1. **Verifiche per peso, non per tipo.** Le query del catalogo non portano il tipo del media (e toccarle rovinerebbe i piani delle query
   di P1): `SizeFilteredCatalog` divide le copie da rileggere a 64 MiB. Sotto, la regola `PHOTO_BACKUP` (leggera); da 64 MiB in su,
   `VIDEO_BACKUP` (pesante, solo Wi-Fi di casa). Una foto enorme o un video piccolo vanno nella regola "sbagliata" ma sempre in una delle due.
2. **Tappa delle verifiche di 60 secondi** (non otto minuti): il budget si controlla tra un file e l'altro (un file iniziato si rilegge
   fino in fondo, lo SHA-256 non si riprende) e il lavoro segue con altre tappe (`BackupFollowUp.Continue`); il motore invece fa tappe da
   otto minuti (D11).
3. **`UnitPriority`**: l'unione fra le chiavi visibili e le miniature attese dal confronto (`awaitingThumbnails`) e' oggi solo la priorita'
   del confronto (`similarCopies.priority`), perche' la Preparazione del NAS non riceve ancora una priorita' dalla Libreria.
4. **"Oltre domani" (E-B17) resta nel guardiano com'e'**: la nota chiedeva di usare `BackupPaths.isCredibleDayAt`; il guardiano ha gia' la
   stessa regola (ora del NAS piu' un giorno di margine, riga 253) e cambiarla avrebbe toccato un comportamento gia' provato.
5. **Un buco di produzione chiuso: `nas_share`.** La tabella che da' l'area (privata o comune) ai file del NAS non era popolata da nessun
   codice di produzione (nei test di 4A/4B bastava il costruttore); senza, un file copiato dal backup risultava `ONLY_COMMON_COPY` e mai
   `SAFE`. `NasGraph.start` ora registra le share (`registerShare`) prima della prima scansione.
6. **Id fissi di JobScheduler**: 1.000.103 per il backup, accanto a 101 e 102 della 4B; WorkManager resta in 0–999.999.
7. **Il backup non parte da un dato letto dal NAS** (E-S11): il piano, le regole e l'elenco delle cartelle vengono solo dal catalogo locale,
   dalle impostazioni e dai tocchi dell'utente.

**Aggiunte compatibili ai moduli esterni** (nessuna firma esistente cambia, nessun comportamento provato cambia)
- `core-backup`: `VerifyReport.problem` e `GuardReport.problem` (con valore di serie null: il motivo di un arresto per NAS pieno o quota,
  che prima era "NAS" e basta); `BackupSafetyFactory` (nuovo: costruisce verificatore e guardiano senza che `:app` nomini le primitive);
  lo stesso `problem` portato da `BackupSafetyVerifier` e `BackupPrivateCopyGuardian`; `BackupServices.kt` (due righe di contratto).
- `feature-viewer`: `ViewerServices.similarCopies` e `ViewerServices.outsideCopy` (nulli di serie), `ViewerOutsideCopy.kt` (la copia fuori
  indice, a parte), il collegamento in `ViewerOverlay` e le stringhe `viewer_outside_copy_*`.
- `feature-diagnostics`: i 23 codici, le voci del catalogo, i testi, le chiavi di `ExportKeys` e la famiglia `problem\.[a-z][a-z_]{2,31}`;
  in `Fase3CatalogTest` tolta l'eccezione di E-B4 (ora coperto da `BACKUP_LOST_BEFORE_BACKUP`).
- `gradle/verify-release-manifest.gradle`: solo un commento (il permesso UIDT serve anche al backup).

**Da provare sul campo (F21–F31, F31-bis, con l'S25 in debug e l'account `test`)**
Il backup in debug scrive solo sotto `test/mcg-campo/Backup/` e legge solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (D13); sulle share vere
non scrive nulla (D12). Semina del telefono con `tools\chaos\s25.ps1 -Mode backup -Step seed`. F21 (una foto, "Fai ora", SHA-256 uguale a quello
del telefono, scudo in Libreria, "Al sicuro sul NAS" nel viewer), F22 (primo backup di `mcg-prova`, riepilogo, notifica, "Ferma" e
"Riprendi", stima ±30%, Libreria fluida), F23 (Wi-Fi spento a meta' di un video), F24 (`am kill` durante caricamento e verifica), F25 (nome
uguale con file diverso e poi identico), F26 (cancellata in Samsung Gallery e ripristinata), F27 (rinomina del dispositivo, secondo
dispositivo), F28 (hotspot con Bilanciato), F29 (spostamento in `test-comune` e cancellazione dal PC), F30 (500 interruzioni), F31 (quota),
F31-bis (copie identiche, ricompresse, EXIF riscritto, HEIC e raffica; "Separa" sul telefono e sul tablet). In piu' per il P7: che la
notifica di avanzamento di un primo backup lungo resti viva con l'app chiusa (UIDT) e che "Ferma" fermi prima del file dopo; che il piede
dica "Backup in prova" con un account non di prova.

**Rischi e punti aperti**
- **"Separa" fra due dispositivi dipende da identita' uguali.** La scelta nomina i due media con gli id del dispositivo che l'ha fatta (voce
  `similar_apart:<id>`); l'altro dispositivo risolve il primo anche per impronta, il secondo solo per id. Le identita' dei file del NAS
  convergono al minore (D7) e un media con la copia sul telefono tiene la sua (D8): fra due dispositivi gli id di una stessa foto possono
  restare diversi, e allora la voce resta "in attesa" (`unresolved`) e le copie restano unite sull'altro dispositivo finche' non le separa
  anche li. Nel test di integrazione i due id sono casuali: il ramo "stessi id" e il ramo "id diversi" si alternano, entrambi con le loro
  verifiche; il caso "stessi id" e' provato a parte da `SimilarCopiesProjectionTest` di core-personal (`unSeparaSulTelefonoArrivaSulTabletEScioglieIlGruppo`). Da guardare in F31-bis; una correzione (portare nella voce anche
  l'impronta del secondo media) tocca il formato dei dati personali e non e' del P7.
- **Verifica di un video molto grande** (copia gia' sul NAS da un vecchio backup, rotazione annuale, rilettura finale di un caricamento
  notturno): lo SHA-256 non si riprende e un file iniziato si rilegge fino in fondo; oltre circa dieci minuti di lettura (da ~7 GB in su sulla
  rete di casa) il sistema puo' fermare il lavoro e la rilettura riparte da zero a ogni giro (nota di P3, non cambiata dal P7). Il
  `BackupJobService` non esegue le verifiche. Da misurare in F22/F32.
- **Raffiche e fuso**: raffiche senza sottosecondi e senza `BURST` possono unirsi (< 0,5% stimato); foto con EXIF senza fuso scattate in
  un altro fuso non sono candidate (nota di P15). Le soglie della firma visiva si tarano con F31-bis (HEIC vero, profili colore).
- **Costo per file**: ~0,19 s contro 0,12 del §2.9, da ricalibrare dopo F22; il motore carica un file alla volta.
- **Batteria del confronto** delle copie simili non misurata.
- Dubbi grafici gia' elencati in `docs/grafica-progetto.md` §12.11 (disco `PROBLEM`, segno "copie", foglio del primo backup, tasto pieno).
- Non spuntate nella checklist di `docs/sicurezza.md` perche' non provate dal P7: `core-backup` non toglie ne' sostituisce (prova "reintroducendo
  la violazione"), nessun file visibile non verificato (prova delle 500 interruzioni e F21–F24 sul campo), deduplica e guardiano non leggono aree
  private altrui.

## Fase 5A: correzioni della revisione (19/10)
Fonte: `docs/review/5a-fase5a.md` (R1, R2, R4, R5, R6, R7 corrette, ognuna con il suo test; **R3** corretta dopo, in un'altra sessione:
ultima voce qui sotto). Regola di tutte le scelte: **ogni correzione riduce cio' che il backup scrive, mai il contrario**. Quelle che
l'utente vede (da confermare):

- **R2: la release con un account di prova non scrive da nessuna parte.** Presa la strada "piu' semplice" della revisione: con
  `FeatureFlags.backupBlocksTestAccounts` (vero in `RELEASE`, falso nelle build di debug dove la recinzione D13 c'e') il cancello risponde
  `TEST_ONLY` per **ogni** share se l'account e' di prova (`test`, `test2`); un account non noto si tratta come di prova (si fallisce chiusi).
  Fino a Q3 l'APK di release su un account di prova mostra "Backup in prova" e non carica niente. In debug non cambia nulla (D12 e D13).
  `tools\check.ps1` lo prova nel bytecode di release (`AppBackupGate.stateOf` chiama anche `FeatureFlags.backupMayWriteAs`).
- **R4: "Ferma" e' una pausa.** Il segno (`backup-user-paused` in `noBackupFilesDir`) dura fra un giro e l'altro e dopo la morte del processo,
  finche' l'utente non tocca **"Riprendi"** o **"Fai ora"** (o "Inizia ora"/"Stanotte" del primo backup) o non azzera l'app. Il motore e il
  guardiano non scrivono (nuovo stato del cancello `PAUSED`, che non e' un problema: nessun messaggio d'allarme); le **verifiche**, che
  leggono soltanto, continuano. Il lavoro dopo la lettura del telefono e il giro delle sei ore non la scavalcano. Il tasto resta "Ferma"; lo stato
  dice **"In pausa"** nella pagina Backup, nella voce delle Impostazioni e nella notifica ("In pausa: riprende quando tocchi «Riprendi»
  nell'app"; subito dopo il tocco: "Metto in pausa prima del prossimo elemento..."). "Android ha fermato il lavoro" (Task Manager di sistema,
  limiti) non e' una pausa dell'utente: riprende da solo e dice ancora "Backup fermo".
- **R5: "foto selezionate".** Una foto non scelta aspetta, le scelte vanno avanti (e il diario si tiene la riga non scelta, che torna
  quando l'utente dara' l'accesso a tutte). Il giro si ferma solo per i rifiuti globali: accesso ai media ritirato, posizione delle foto.
- **R1: un nome " (n)" oltre i 255 byte.** "nome (2).jpg" si ottiene accorciando la radice del nome (a un confine di carattere, mai una coppia
  surrogata a meta') perche' tutto stia in 255 byte UTF-8; lo stesso file ha sempre lo stesso nome. Nuovo **problema `UNEXPECTED`**
  ("N media con un errore inatteso", azioni Riprova e Diagnostica): un errore che nessuna regola prevedeva ferma **quel file** (mai "al
  sicuro"), non il backup degli altri; "Riprova" lo toglie. Un lavoro che finisce con un errore inatteso non e' piu' un "successo" muto
  ne' una ripianificazione infinita: salva lo stesso problema, la pagina non resta "in corso", il lavoro si chiude (WorkManager `failure`,
  `JobScheduler` senza ripianificare). Le verifiche girano anche se il guardiano fallisce.
- **R6: un file piu' lungo di cio' che dice il MediaStore** (cresciuto senza che il MediaStore lo sappia) non diventa mai "al sicuro"
  troncato: dopo aver letto `size` byte il backup guarda la dimensione **vera** del descrittore aperto (`fstat` di adesso) e se un byte in
  piu' esiste; se non torna, il file e' cambiato (come E-B15: quella versione non si salva da li', la nuova avra' il suo elemento).
  Costo noto: finche' il MediaStore resta indietro, ogni giro rilegge quel file e lo scarta (si risolve da solo quando il MediaStore lo vede).
- **R7: la share di un elemento.** Il cancello vale solo per l'area privata **di adesso** (stessa share, senza maiuscole, altrimenti
  `NO_PRIVATE_AREA`): se l'admin cambia la configurazione a processo vivo il motore non scrive piu' nella share di prima e le parti del
  backup si ricostruiscono con la share nuova al giro dopo. Un elemento del diario di un'altra share aspetta (e il log lo dice); non
  ferma gli altri. **Residuo:** finche' aspetta, il suo media non riceve un elemento nuovo per la share di adesso; si risolve tenendo
  l'area privata com'era o con "Azzera".
- **R3: la ripresa di un file grande accorcia solo il proprio temporaneo.** Con il punto di ripresa il diario scrive anche l'identita' del
  temporaneo letta sull'handle dello scrittore; alla riapertura, sullo stesso handle e prima di accorciare, un altro file, un file con due
  nomi (collegamento fisico), un punto di rianalisi o una `tmp/` che non e' la cartella vera **non si toccano**: l'elemento ricomincia da
  capo (al piu' 64 MiB di traffico in piu', mai un byte cambiato) e il nome occupato si toglie con `deleteOwnTemp`, che toglie solo il nome.
  Un server che non dice l'identita' non riprende mai. Provato sul WD (19/10): ripresa con accorciamento riuscita; un collegamento fisico
  via SMB2 il WD lo crea e lo dice (`NumberOfLinks` 2), e la riapertura lo rifiuta lasciando il file intero (visto una volta: la prova
  usava smbj diretto, che la suite vieta, e non e' rimasta; **da decidere** se renderla ripetibile).
- **Residui:** le informative I1-I9 come scritte; F21-F24 e le prove sul campo di R1, R5, R6 della revisione (sotto "Da provare sul
  campo") non sono state fatte (nessun telefono). Di R3: un file il cui unico nome e' quello del temporaneo lo toglie `deleteOwnTemp` (come
  gia' le altre vie del motore e la pulizia D33); il collegamento simbolico non si crea via SMB e non e' stato provato sul WD.

## Fase 5B: fondamenta e contratti (P0-B, 19/10)
Fonte: `docs/fase5-progetto.md` (§0.2, §0.3, §1.1-§1.5, §2.4, §2.5, §2.7, D17-D35, §4.3, riga P0-B del §4.4, §6.4, risposte Q2, Q5, Q6, Q8, Q9 del §9.1),
`docs/sicurezza.md`, `docs/review/5a-fase5a.md`. Consegna il modulo, i contratti, i null-object e i fake su cui partono in parallelo P8 (versioni e
correzioni), P9 ("Libera spazio"), P10 (selettore e provider), P11 (arrivi, "Modifica con…", senza posizione) e P13 (prova delle 500 interruzioni
della 5B). Nessuna dipendenza esterna nuova, nessun permesso Android nuovo. **Nessuna logica di produzione** oltre a due eccezioni volute: la
riscrittura di un file del telefono (`MediaStorePhoneMediaRewriter`, perché è il punto in cui l'app può lasciare un file a metà e va provato subito) e
le richieste di sistema di `core-mediastore`. Tutto il resto sono firme, tipi, regole pure, null-object e fake funzionanti.

**Nuovo modulo `:feature-integrations`** (`settings.gradle`, `galleryModules`, `app/build.gradle`; vede `core-backup`, `core-library`, `core-imaging`,
`core-mediastore`, `core-designsystem`; **cieco a `core-nas` e `core-nasops`**): `contract/` (`IntegrationPorts`: `MediaDocuments`, `DocumentReader`,
`DocumentIds`, `EntryDecision`; i byte di un media solo-NAS arrivano da una porta che `:app` lega a `NasMediaReader`), `noop/`, `testing/`
(`IntegrationFakes`), `NoNetworkSourcesTest`, `NoWallClockSourcesTest`, e i **quattro componenti approvati** come scheletri che non agiscono:
`PickerActivity` e `ShareReceiverActivity` chiudono con `RESULT_CANCELED` senza leggere l'intento; `MediaDocumentsProvider` non ha radici e non apre
documenti; `IntegrationFileProvider` serve solo `cacheDir/share/` e `cacheDir/edit/`. Le activity hanno `taskAffinity=""` e stanno fuori dai recenti.

**Contratti** (una riga ciascuno; tutti con null-object che fallisce chiuso e fake funzionante)
| Dove | Cosa | Per chi |
|---|---|---|
| `core-nas/ops/backup` | `NasBackupOps.archiveVersion` e `restoreVersion`: rinomina nella stessa share, **timbro obbligatorio** (`NasStamp`, mai nullo), **una sola estremità sotto `Versioni/`**, mai sostituire, nessuna cancellazione. `VersionPaths` (`requireArchive`, `requireRestore`, `mirrorOf`) controlla i percorsi in ogni implementazione. Insieme chiuso di primitive, provato dal test di architettura. `SmbNasBackupOps` controlla i percorsi e risponde `NOT_IMPLEMENTED`; il fake (`InMemoryNasMedia.backupView`) le esegue davvero (`FakeNasOp.ARCHIVE_VERSION`, `RESTORE_VERSION`, guasti prima e dopo) | P8 (la parte SMB: chiama `SmbNasFileOps.rename`), P13 |
| `core-metadata/contract` | `MetadataChange` (`GeoChange`, data, rotazione), `MetadataWriter` (`WriteOutcome`: scritto, non supportato `WriteUnsupported`, fallito `WriteFailure`) e `LocationStripper` (`StripOutcome.Stripped` porta il tipo dell'uscita; `StripUnsupported`); fake `testing/WritingFakes.kt`; `exifinterface` passa a `implementation` | P8, P11 |
| `core-mediastore` | `PhoneMediaIntents.deleteRequest` e `writeRequest` (insieme a `trashRequest`: solo con il dialogo di sistema, righe distinte, al massimo 2.000, da Android 11); `PhoneMediaRewriter.replace(ref, file, sha)` con `RewriteResult` (`Replaced`, `NotGranted`, `Gone`, `SourceMismatch`, `Failed(code, restored)`); **adattatore vero** `MediaStorePhoneMediaRewriter` (copia di sicurezza, scrittura `rw` senza troncare, rilettura con `setRequireOriginal`, ripristino se non torna, lavoro non annullabile); `NoPhoneMediaRewriter`; `FakeMediaStore` che simula i dialoghi (accetta, rifiuta, parziale, app uccisa), i permessi concessi e la riscrittura | P8, P9, P14 |
| `core-backup/contract` | `PhoneConfirmation` (costruttore `internal`, `PhoneConfirmationIssuer`, `PhoneConfirmationSheet`, `ConfirmablePlan` + `PhonePlanHash`: vale per un piano, 10 minuti monotoni, 600 ms fra i due tocchi di "Libera subito"); `PhoneRewriter` + `PhoneWriteGrant` (la porta del riscrittore, che `:app` mappa su `PhoneMediaRewriter` con la recinzione D13) | P8, P9, P12 |
| `core-backup/contract` | `VersionKeeper` (piano delle versioni di un media, "Ripristina originale" con conferma e permesso di scrittura), `VersionEntry`/`VersionRole`/`VersionPlace`/`RestoreOutcome` | P8, P12 |
| `core-backup/contract` | `Corrections` (piano e `apply` di una correzione di data, luogo o rotazione: conferma, permesso del dialogo, originale al sicuro prima), `CorrectionPlan`/`CorrectionOutcome` | P8, P12 |
| `core-backup/contract` | `FreeSpace` ("Libera spazio" in cinque passi: `plan`, `verify`, `resume`, `nextBatch`, `recordOutcome`), `FreeSpaceRules` (avviso forte sempre nel piano, verifica valida 6 ore monotone e non sopravvive a un riavvio, lotti di al massimo 2.000, timbro ricontrollato prima di ogni dialogo, ultimi 30 giorni esclusi di default, i mai: non verificati, in errore, in caricamento, congelati, NAS irraggiungibile; **le liste non crescono mai da dati del NAS**, E-S11) | P9, P12 |
| `core-backup/contract` | `PendingTasks` ("Da decidere": `tasks`, `dismiss`, `complete`) | P8, P12 |
| `core-backup/contract` | `IncomingUploads` (`stage` copia subito con `IncomingLimits`, `save` solo con una `PhoneConfirmation` legata a `IncomingSavePlan`, `discard`) | P11, P12 |
| `core-backup/contract` | `EditSessions` (`begin` → `EditBegin`, `settle` → `EditOutcome`; finestra di `EditSessionRules`) | P11, P12 |
| `feature-integrations/contract` | `MediaDocuments` + `DocumentReader` + `DocumentIds` (id opachi, mai percorsi) | P10 |

Fake e null-object stanno in `core-backup/testing/FiveBFakes.kt` e `noop/BackupFiveBNullObjects.kt`. `core-backup` ora vede `core-metadata` con `api`
(le firme delle correzioni usano `MetadataChange`).

**Come è garantito il perimetro** (`Phase5BArchitectureTest`, `core-common`; i suoi ingressi, compreso il manifest di debug, la policy, `sicurezza.md` e
`tools/check.ps1`, sono dichiarati in `core-common/build.gradle`)
- `feature-integrations` dipende solo da ciò che il §1.1 permette, non vede `core-nas` né `core-nasops` (né per compilatore, né per nomi) e ha i
  pacchetti e i test di guardia dei moduli, nessun permesso.
- `PhoneConfirmation` ha il costruttore `internal`; emittente e foglio solo nella UI (`feature-backup`, `feature-integrations`, `feature-viewer`,
  `:app`) e, in `core-backup`, nei fake e nei test; i servizi che toccano il telefono (`FreeSpace.verify`, `VersionKeeper.restoreOriginal`,
  `Corrections.apply`, `IncomingUploads.save`) la pretendono nella firma.
- `NasBackupOps` ha un insieme **chiuso** di primitive (le sette della 5A, `archiveVersion`, `restoreVersion`); le due rinomine hanno il timbro non
  nullo e ogni implementazione chiama `VersionPaths`.
- Richieste di sistema (`create…Request`) e riscrittore solo in `core-mediastore` (e il riscrittore in `:app`), nel codice che va in un APK.
- Solo `:app` e `feature-integrations` dichiarano componenti; i quattro approvati con le loro esposizioni; `MANAGE_DOCUMENTS` sul provider dei documenti;
  `releaseManifestPolicy` e `docs/sicurezza.md` §4-bis d'accordo; `FileProvider` solo `share/` e `edit/`; le prove guidate (`FieldProbe*`) solo in
  `app/src/debug` e `app/src/testDebug`, ogni componente del manifest di debug è una prova, e `tools/check.ps1` le cerca nel dex di release.
- **Provato il 19/10 reintroducendo ogni violazione** (il test corrispondente fallisce, poi si toglie e torna verde), in sei giri e con tutte le
  asserzioni di ogni test: un modulo fuori dal §1.1 e `core-nas` fra le dipendenze; `core-nas` e il nome delle primitive nei sorgenti di
  `feature-integrations`; un `uses-permission` nel suo manifest; un `NoNetworkSourcesTest` o un `NoWallClockSourcesTest` tolto; l'emittente della
  conferma in `core-library`; `PhoneConfirmation(` costruita in `feature-library`; il costruttore reso pubblico; `FreeSpace.verify` senza conferma;
  una rinomina libera in `NasBackupOps`; il timbro reso nullo; `requireRestore` tolto da un'implementazione; `createTrashRequest` in
  `feature-library`; `PhoneMediaRewriter` in `core-library`; un componente in più nel manifest di `feature-integrations` e in `feature-library`, e uno dei
  quattro dichiarato anche in `:app`; il
  `FileProvider` esportato o con un'altra radice; un componente senza permesso dove serve `MANAGE_DOCUMENTS`; un esportato tolto dalla policy o un
  permesso proibito aggiunto; `MANAGE_MEDIA` tolto dai rifiuti per nome; il provider dei documenti tolto dal §4-bis; un `FieldProbe*` in `app/src/main`
  e un componente non di prova nel manifest di debug; `FieldProbe` tolto da `tools/check.ps1`. (Una prima versione del controllo su `check.ps1` passava
  anche senza il nome: lo trovava in un commento. Ora legge solo i nomi fra apici.)

**Manifest e policy.** `feature-integrations/src/main/AndroidManifest.xml` dichiara i quattro componenti e nient'altro, e si fonde nel manifest di
`:app` (nessuna copia in `app/src/main`). `releaseManifestPolicy`: i tre esportati in `allowedExported`, `MANAGE_DOCUMENTS` per il provider in
`exportedRequiresPermission`, e una novità, `forbiddenPermissions` (`MANAGE_MEDIA` e `MANAGE_EXTERNAL_STORAGE`, rifiutati per nome con la ragione). `tools/check.ps1`
controlla anche che i quattro siano nel manifest dell'APK di release con la giusta esposizione e che nessun componente di prova ci sia. `docs/sicurezza.md`: §4-bis, C17,
checklist e §8.

**Prove guidate T-09, T-14, T-15, T-16** (solo debug, **non eseguite**): *Impostazioni → Sviluppo → "Prove guidate…"* (`app/src/debug/.../debugtools/probes/`,
tutte `FieldProbe*`, 12 file; manifest e `<queries>` in `app/src/debug/AndroidManifest.xml`). Leggono e toccano solo `DCIM/mcg-prova/` e
`Pictures/mcg-prova/`; il resoconto è di soli numeri (insieme chiuso di chiavi in `FieldProbeKey`, una riga `chiave=numero`, nessun nome, percorso o
indirizzo; gli indirizzi delle condivisioni ricevute stanno in un file privato fuori dal resoconto); sopravvive alla morte del processo (T-15 la provoca). La
tabella delle chiavi e il posto per il resoconto sono in `docs/android.md` («Resoconto delle prove guidate»). Provate sul MediaStore finto
(`FieldProbeFlowTest`: recinzione, cestino, ripristino, richiesta di 500, scrittura con gli stessi byte, `ACTION_VIEW`; `FieldProbeReportTest`;
`FieldProbeMathTest`). Assenti dalla release: `$debugRemnants` di `tools/check.ps1` (`FieldProbe`, `debugtools/probes`, `field-probe`, `fieldprobe`) e il
manifest.

**Modifiche ai contratti che P8-P13 devono conoscere** (rispetto al §1.2/§2 del progetto, scoperte scrivendo i fake)
- `NasBackupOps.archiveVersion/restoreVersion`: la cartella di destinazione sotto `Versioni/` va creata **prima** con `mkdirs`; il timbro è obbligatorio; il
  sorgente deve essere un file (una cartella dà `SourceChanged(null)`); la parte SMB sta in `SmbNasBackupOps` (non in `SmbNasFileOps`), che per la rinomina
  userà `SmbNasFileOps.rename` con lo stesso timbro.
- `FreeSpace`: `resume` esiste (la verifica prosegue a tappe) e `recordOutcome` restituisce il `FreeSpaceRun` aggiornato; la conferma si controlla in `verify`.
- `PendingTasks.complete` è nuovo: lo chiama `:app` quando l'operazione "Elimina…" della 4B è partita.
- `IncomingUploads.discard` e `IncomingSavePlan` (la conferma è legata allo stage e alla destinazione) sono nuovi; `stage` non avvia nessun caricamento.
- `EditSessions.begin` restituisce `EditBegin` (sessione o motivo per cui non c'è); `VersionEntry` porta `isCurrent` e `VersionRole.ORIGINAL`/`EDITED`.
- `PhoneRewriter` è una porta di `core-backup` (che non vede `core-mediastore`): `:app` la mappa su `PhoneMediaRewriter`, con la recinzione D13 in debug.
- `StripOutcome.Stripped` porta il tipo dell'uscita (l'HEIC diventa JPEG). `Mp4Scout` di `core-prepare` non si può usare da `core-metadata`: lo scrittore
  dei video usa `IsoBmff` di `core-metadata`.
- `core-backup` → `core-metadata` è `api`; `exifinterface` in `core-metadata` è `implementation`.
- I wrapper del caos di `core-backup` (`GuardedBackupOps`, `ChaosBackupOps`) delegano le due rinomine senza entrate nuove in `Primitive`: P13 le aggiunge.
- I quattro componenti sono già nel manifest e nella policy (il §4.4 lo metteva in P14): P14 resta con `AppLockGate` su più activity, la navigazione e la prova con `adb`.
  Nota per chi installa la build sul telefono: i componenti sono abilitati ma inerti (le activity chiudono subito), quindi compaiono già nei selettori e nel
  foglio di condivisione delle altre app, senza fare niente.

**Punti aperti**
- La semina dello script `tools/chaos/s25.ps1 -Mode seed` produce circa 300 righe: la prova (f) di T-09 vuole almeno 500. Servono più foto di prova (a cura di chi esegue).
- La schermata delle prove sta sotto Impostazioni → Sviluppo, non sotto Diagnostica: «Sviluppo» è già lì (`DeveloperTools`).
- Le prove T-09, T-14, T-15, T-16 non sono eseguite: il resoconto in `docs/android.md` è vuoto. Dai loro esiti dipendono la finestra di tolleranza del cestino di
  Samsung (E-P11), l'eventuale `MANAGE_MEDIA` (atteso di no), la forma della risposta per i media solo-NAS, la strategia di copia delle condivisioni e l'algoritmo delle versioni.
- Il `FileProvider` di androidx non si può provare con un URI su Windows (la radice ha un separatore in più): `FileProviderPathsTest` legge il file XML e le radici configurate.

## Fase 5B: P8, versioni e correzioni (19/10)
Fonte: `docs/fase5-progetto.md` (§2.4 per intero, D17-D20, D32, D35, §4.3, riga P8 del §4.4, §5.4 invarianti 12-14, §6, risposte Q2 (a) e Q6 (a)), le note
per P8 della sezione P0-B qui sopra, `docs/review/5a-fase5a.md` (R1, R3), il codice della 5A (`upload/SafeUploadEngine.kt`, diario, `safety/`).
Perimetro: `core-backup/.../versions/**`, `.../corrections/**`, `.../tasks/**` (e i loro test), `core-nas/.../ops/backup/` con
`SmbNasFileOps.renameVersion`, `core-metadata/.../write/**`. Nessuna dipendenza nuova, nessun permesso nuovo.

**Cosa c'è**
| Dove | File | Cosa |
|---|---|---|
| `core-nas` | `ops/SmbNasFileOps.kt` (`renameVersion`), `ops/backup/NasBackupOpsFactory.kt` | `archiveVersion` e `restoreVersion` veri: la rinomina della 4B sullo stesso handle, con il timbro **obbligatorio** confrontato prima del `SET_INFO`, senza sostituire, solo un file (cartella o punto di rianalisi = `SourceChanged`), senza creare cartelle (`NotFound`: prima `mkdirs`). Sopra primitive non SMB resta `NOT_IMPLEMENTED` |
| `core-metadata/write` | `ExifMetadataWriter.kt` | lo scrittore vero (`ExifInterface`): tipo dai **byte**, solo JPEG, PNG, WebP; destinazione nuova creata in esclusiva, sorgente mai toccato, rilettura con `ExifInterface`, niente resta se qualcosa va storto |
| | `WrittenFileCheck.kt` | `ExtractingFileCheck`: la copia corretta si rilegge con l'estrattore dell'app (i valori chiesti ci sono) e si **decodifica** un'anteprima (D19) |
| `core-backup/versions` | `VersionModel.kt` | lavori `VERSION`/`SWAP` (`VersionJob`), porte `VersionJournal`, `VersionCatalog`, `StagedVersionFiles`, `VideoOriginalTasks` |
| | `NasVersionEngine.kt` | il motore dei due cammini del §2.4 (sotto) |
| | `VersionPlanning.kt`, `VersionNames.kt` | decisioni pure (solito percorso, versione del telefono, file preparato, scambio) e nomi in `Versioni/` |
| | `DefaultVersionKeeper.kt` | `VersionKeeper`: piano delle versioni e "Ripristina originale" (E-E10) |
| | `VersionFranchise.kt` | la franchigia del congelamento (D32) |
| | `RoomVersionJournal.kt`, `DirectoryStagedFiles.kt` | il diario su Room nella tabella `backup_item` e i file preparati in una cartella dell'app |
| `core-backup/corrections` | `DefaultCorrections.kt` | `Corrections` (D19, Q6 (a)) |
| `core-backup/tasks` | `RoomPendingTasks.kt` | `PendingTasks` su `pending_task` (D20) e la porta `VideoOriginalTasks` |

**I due cammini** (stessa macchina `UploadStateMachine`; ogni valore si scrive nel diario prima della chiamata; la risposta persa si riconosce dal NAS)
- `VERSION`: lettura completa (telefono o file preparato) con l'hash, temporaneo proprio con i punti di ripresa e l'identità di R3, rilettura completa (D4); **solo
  dopo la verifica** il piano si ricalcola dal catalogo: se la versione è ancora l'attuale, il file che il catalogo conosce al solito percorso (dello **stesso**
  media) va in `Versioni/` (nome scritto prima, cartella creata prima, timbro obbligatorio), la posizione nel catalogo lo segue **subito** (prima di scrivere
  `ARCHIVED`, così un giro fermato lì lascia catalogo e NAS d'accordo), poi la versione si pubblica al solito percorso senza sostituire. Se non è più l'attuale
  (la versione del PC registrata dalla scansione) va direttamente in `Versioni/` e il solito percorso non si tocca. `VERSION_DIRECT` (nessun attuale da spostare:
  l'originale rimasto sul telefono dopo una modifica del PC, E-E6) pubblica subito in `Versioni/`.
- `SWAP`: due rinomine, nessun byte caricato; il catalogo segue ogni rinomina subito; `restoreOriginal` del catalogo solo se la versione riportata è l'originale.

**Scelte** (quelle che l'utente vede sono da confermare)
- **Nomi in `Versioni/`** (Q2 (a)): `Versioni/<percorso del file>/IMG_1234 (originale).jpg`; le altre `IMG_1234 (versione del 2026-10-15 14.32).jpg` con l'**ora del NAS**
  del file archiviato (quando è arrivato sul NAS, non quando è stato modificato sul telefono: l'orologio del telefono non entra, D31), mostrata nel fuso del
  telefono; un nome occupato da un altro file diventa "… (2)" (mai sostituito); un nome oltre i 255 byte si accorcia nella radice (come R1).
- **`SOURCE_CHANGED`** (il PC ha modificato o tolto l'attuale durante il lavoro): niente si sposta, la versione resta nel suo temporaneo (citato dal diario: la
  pulizia D33 non lo tocca) e il lavoro aspetta in `ARCHIVING`; quando la scansione registra il file del PC il piano si ricalcola (versione del PC attuale → la
  nostra in `Versioni/`; file tolto → la nostra al solito percorso libero). Per uno `SWAP` lo scambio finisce `FAILED`: il piano che l'utente ha confermato non vale.
- **Il solito percorso occupato da un file di altri** fra l'archiviazione e la pubblicazione: mai sostituito; la versione va in `Versioni/` (`USUAL_PATH_TAKEN`).
- **Uno scambio che non può riportare l'originale** (cambiato o sparito dal PC): l'attuale **torna al suo posto** (`ROLLED_BACK`, `FAILED`): a lavoro finito il
  solito percorso non resta mai vuoto. Se nemmeno l'attuale si può riportare, entrambi restano in `Versioni/` (nessun byte perso).
- **Errori inattesi** (R1): prima di spostare qualcosa il lavoro si parcheggia `FAILED` con `UNEXPECTED`; **dopo** (`ARCHIVING` in poi, uno scambio iniziato) resta
  aperto e si riprende: un `FAILED` lascerebbe il solito percorso vuoto.
- **Congelamento ed emergenza** (D32): il cancello si guarda prima di ogni passo; un congelamento che arriva fra `ARCHIVED` e `PUBLISHED` lascia il solito percorso
  vuoto finché dura (i byte sono in `Versioni/` e nel temporaneo verificato; il catalogo è già d'accordo). Residuo dichiarato.
- **Franchigia** (D32): `VersionFranchise.fileIds(share)` = i `fileId` che le versioni hanno spostato o messo (archiviati, pubblicati, riportati), dal diario
  locale: per la guardia dei cambiamenti di massa non contano né fra i "cambiati" né fra gli "spariti". In più il catalogo segue ogni rinomina subito, quindi la
  scansione trova i file al posto che il catalogo già sa.
- **"Ripristina originale" dall'app** (E-E10): permesso `null` → `NeedsPhoneWrite` e niente si tocca; un permesso che **non copre** le righe = l'utente ha detto
  no al dialogo → si scambia solo sul NAS e il telefono tiene la modifica (il piano lo mostra); con il permesso, a scambio finito, la riga si riscrive con
  l'originale **riletto dal NAS e verificato** con il suo SHA-256.
- **"Ripristina originale" in Samsung Gallery** (E-E2): `NasVersionEngine.planRealign(mediaId)` crea lo `SWAP` senza conferma (due rinomine dei file del media
  stesso, da dati locali: il catalogo dice già che l'attuale è l'originale).
- **Correzioni** (D19): l'ordine è catalogo (versione `CORRECTED` con l'impronta nuova, **nessun posto**: la lettura del telefono la riconoscerà come ritorno a
  una versione nota) → lavoro `VERSION` dal file preparato → riscrittura del telefono → il lavoro va avanti subito. Il file preparato ha una **nota** (a quale
  media appartiene) scritta prima del file: dopo una morte del processo `recoverStaged` (a ogni giro) lo affida al suo lavoro o lo toglie se la correzione non è
  mai arrivata al catalogo. Una riscrittura del telefono fallita risponde `Failed(PHONE_WRITE_FAILED)` (o `PHONE_NOT_RESTORED`), ma la correzione resta nell'app e
  va sul NAS: il telefono tiene la versione di prima. Un media del telefono **non al sicuro** (la versione attuale senza copia privata verificata e SHA-256) non
  si corregge: `NeedsSafeFirst`, niente letto né scritto. Anche un solo-NAS non verificato vuole prima la verifica.
- **Q6 (a)**: HEIC, RAW e video → `FormatNotWritable` dopo la conferma, con `VersionCatalog.correctInApp` (il file non si tocca). Anche un "JPEG" che dentro non lo
  è (lo scrittore guarda i byte).
- **Scrittore** (`ExifMetadataWriter`): data in `DateTimeOriginal`/`DateTimeDigitized` con `OffsetTime*` e `SubSecTime*` (senza fuso nella correzione vale quello
  del file); l'ora GPS si sposta con la data (l'estrattore la usa per il fuso); un luogo nuovo toglie l'altitudine di prima; "togli il luogo" svuota tutte le
  etichette di posizione GPS (non l'ora GPS).
- **D20**: l'attività in sospeso nasce solo quando il file spostato in `Versioni/` è l'**originale** di un **video**; una per originale, per sempre ("Tienilo"
  non la riapre); "Elimina" resta della 4B (`:app` chiama `complete`).
- **D13 in debug**: le versioni nascono solo da media letti dal telefono recintato (`mcg-prova`) e si scrivono nella share di prova del media, ma `Versioni/`
  sta alla **radice** della share (Q2 (a)), quindi fuori da `mcg-campo/` (es. `Versioni/mcg-campo/Backup/...`). Se la recinzione deve restare tutta sotto
  `mcg-campo/`, `VersionPaths` vuole una radice (punto aperto per P14).
- **D35**: un media proprio che sta solo nell'area comune non diventa una versione scritta lì (né dal telefono né da una correzione: `COMMON_AREA_NOTICE`): il
  foglio "la vedranno tutti" non è nel contratto. Un'area in sola lettura ferma il giro al cancello.

**Il diario su Room** (`RoomVersionJournal`, **soluzione provvisoria**): i lavori stanno in `backup_item` con i tipi `version` e `swap` già previsti dallo schema v4,
così la 5A li vede (`openFor`: nessuna copia nuova in parallelo; `citedTemps`: D33; conteggi). Lo schema non ha le colonne per l'attuale da spostare, il nome
in `Versioni/`, il timbro archiviato, la destinazione e l'attesa: stanno in una **nota** nella colonna `problem` con il prefisso `mcg-versioni-1;`, che
`RoomBackupStore` legge come "nessun problema" (per costruzione); la versione da riportare di uno scambio sta in `guard_path`/`guard_*`. Un'unione di identità
aggiorna `backup_item.version_id` ma non l'id dell'attuale dentro la nota (caso raro: l'attività di D20 di quel lavoro fallirebbe). Le colonne vere sono una
richiesta per lo schema (sotto).

**Test** (tutti JVM; Robolectric dove c'è Room o `ExifInterface`)
- `core-nas` `ops/backup/SmbVersionRenamesTest` (simulatore Samba): una sola apertura e una sola rinomina, byte e `fileId` intatti; collisione (anche per maiuscole);
  sorgente cambiato dal PC (byte o un altro file) senza nessuna `RENAME` inviata; cartella mancante `NotFound` senza `MKDIR`; cartella, file assente, file aperto
  dal PC; percorsi sbagliati prima di toccare il NAS; caduta della connessione e sessione scaduta prima e dopo **ogni** richiesta SMB (file a un solo nome,
  intero, stessa identità, ripresa riconosciuta).
- `core-metadata` `write/ExifMetadataWriterTest`: JPEG, PNG e WebP corretti (data, luogo messo o tolto, rotazione, tutto) **riletti dall'estrattore e decodificati**
  (grafica nativa), `ExifInterface` d'accordo, sorgente intatto; formati fuori dalla Q6 e "JPEG" falso non scritti; destinazione esistente intatta; copia rovinata
  (troncata, solo la testa) rifiutata dalla rilettura.
- `core-backup` `versions/VersionScenariosTest` (§2.4 scenario per scenario), `VersionKeeperTest`, `VersionEveryCallTest` (7 scenari: guasto prima e dopo ogni
  chiamata al NAS, circa 85 chiamate in tutto, e morte del processo prima e dopo ogni scrittura del diario e del catalogo, circa 100 passi, con gli invarianti 1, 2, 3, 6, 8,
  10, 12, 13 a lavoro finito), `VersionSimulatorTest` (il motore sul simulatore Samba: E-E1, E-E2, `SOURCE_CHANGED`, guasto a ogni richiesta SMB),
  `RoomVersionJournalTest` (ogni campo, regole, convivenza con il diario della 5A, ripresa da un database chiuso e riaperto), `VersionEdgeCasesTest`
  (E-R9, E-B15, R6, E-P12, R1 applicati al lavoro `VERSION`); `corrections/CorrectionsTest`;
  `tasks/RoomPendingTasksTest`. Il banco (`VersionWorld`, diario e catalogo in memoria che muoiono, `HookedOps`, invarianti) resta per P13.

**Da provare sul campo** (F34-F36, solo `mcg-prova` con D13)
- F34: Samsung Gallery "Salva" su una foto **al sicuro** di `mcg-prova` → dal PC la versione al solito percorso e `Versioni/…/IMG (originale).jpg` con lo stesso
  `fileId` (stessa data di creazione) e gli stessi byte di prima; poi "Ripristina originale" in Samsung Gallery → scambio senza traffico (contatore dei byte
  caricati fermo); poi "Salva copia" → un media nuovo (non una versione). Da misurare: la rinomina in `Versioni/` sul WD (tempo, `fileId` dopo la rinomina, ora
  di modifica conservata).
- F35: video modificato e salvato sopra → l'originale in `Versioni/`, l'attività "Da decidere"; Sì → cestino del NAS (4B); No → resta e non si ripropone.
- F36: correzione di data, luogo e rotazione su un JPEG del telefono (dialogo di sistema; il telefono e il NAS con la versione corretta, l'originale in
  `Versioni/`; dal PC `exiftool` legge i valori nuovi); la stessa su un solo-NAS; un HEIC → "corretto solo nell'app", file intatto; un PNG e un WebP veri
  del telefono (le fixture non bastano a dire che `ExifInterface` scriva bene i file di Samsung). Dal PC modificare il file al solito percorso mentre il backup
  della versione è in corso (`SOURCE_CHANGED`: niente spostato, il temporaneo resta, dopo la scansione la versione va in `Versioni/`).

**Modifiche richieste ai contratti degli altri**
- **P14 (`:app`)**: legare `VersionCatalog` a `MediaCatalog` (`versionLocations` con tipo, MIME, proprietario e attuale; `recordFullHash`; `attachCopies`;
  `relocate` **conservando `verified_at`**; `restoreOriginal`; `correct` senza posti; `markBackupProblem`); `RoomVersionJournal`; `DirectoryStagedFiles` in
  `noBackupFilesDir/versions` con `dropLeftoverWork()` all'avvio; `RoomPendingTasks` (con `refresh()` all'avvio) come `PendingTasks` e `VideoOriginalTasks`;
  `ExifMetadataWriter`; `ExtractingFileCheck` (estrattore e `AndroidPreviewDecoder`); `DefaultVersionKeeper` e `DefaultCorrections` al posto dei null-object.
  Nel lavoro a tappe del backup: `planPhoneVersions(planner.next(...))` **prima** del giro della 5A (altrimenti la 5A carica la modifica come "nome (2)"), poi
  `versionEngine.run(...)` nella stessa tappa; dopo la lettura del telefono, per un `RevertedToVersion`, `planRealign(mediaId)`. Le versioni della copia in
  `Pictures/MyCloud Gallery` (E-E7) vanno offerte come candidati anche se quella cartella non è una sorgente. La franchigia va composta nella guardia della
  scansione (una porta come `OwnRemovals` per i "cambiati" e gli "spariti" per `fileId`: core-nasmedia). Il cancello è lo stesso del backup.
- **P1 / schema**: colonne vere per `VERSION`/`SWAP` in `backup_item` (l'attuale con versione, posizione, timbro e originale; nome e timbro in `Versioni/`;
  destinazione; attesa) al posto della nota; una query del DAO dei lavori per tipo e share (oggi `RoomVersionJournal.inShare` legge gli id con una query
  diretta); `MediaCatalog.correctInApp` (Q6 (a): una correzione di metadati che sopravviva alla rilettura META) non c'è.
- **P0-B / contratti di core-backup**: `BackupProblemCode` non ha `SOURCE_CHANGED` (sta in `VersionHold`): per la pagina del backup serve un codice o lo stato del
  lavoro; `RestoreOutcome` non dice "scambio fatto ma telefono non riscritto" (oggi `Queued`: la UI ripianifica e lo vede); `RestoreAvailability` non ha "un'altra
  versione in corso" (oggi `NOT_AVAILABLE`); la semantica di `PhoneWriteGrant` che non copre le righe = "dialogo negato" va scritta nel contratto;
  `BackupItem.flow` ignora `archivesCurrent` (un `VERSION_DIRECT` letto da `RoomBackupStore` ha il cammino sbagliato: il verificatore di P13 usi `VersionJob.flow`).
- **P9**: una copia privata verificata **in `Versioni/`** è una copia privata della sua versione come le altre (la posizione la segue con `verified_at`);
  `VersionKeeper.plan` è solo catalogo e diario locale.
- **P12**: `VersionsState` da `DefaultVersionKeeper.plan`; `FormatNotWritable` = "corretta solo nell'app"; `COMMON_AREA_NOTICE` non ha ancora un foglio nel
  contratto (D35 per le scritture nell'area comune non è fatto).
- **P11**: per "Modifica con…" di un solo-NAS (E-E3) il risultato dell'editor diventa una versione così: `StagedVersionFiles.keep(file, sha, media)`, versione nel
  catalogo, `NasVersionEngine.planStaged`.
- **P13**: il banco delle versioni è pronto (`VersionWorld`, `InMemoryVersionJournal`/`Catalog`, `DyingVersion*`, `HookedOps`, `violations`); i wrapper del caos
  della 5A delegano le due rinomine senza entrate in `Primitive` (nota di P0-B).

## Fase 5B: P9, "Libera spazio" e cancellazioni dal telefono (19/10)
Fonte: `docs/fase5-progetto.md` (§2.5 per intero, §2.3, D9, D13, D21, D22, D31, §4.3, riga P9 del §4.4, risposte Q4 (b) e Q9 (a)), il contratto `FreeSpace` e
`PhoneConfirmation` di P0-B, il codice della 5A (`safety/`, diario, catalogo). Perimetro: `core-backup/.../free/**` (e i suoi test). È l'unico flusso
dell'app che toglie copie dal telefono: **nessun media lascia il telefono se la sua copia sul NAS non è stata riletta per intero, qui e da poco**.

**Cosa c'è** (`core-backup/src/main/kotlin/.../backup/free/`)
| File | Cosa |
|---|---|
| `SafeFreeSpace.kt` | il motore (`FreeSpace`): piano, conferma, guardiano, verifica sul momento, controllo finale, lotti, risposte di Android; più `assessPhoneOnly` ("Elimina solo dal telefono") e `settle` (esiti in dubbio dopo la lettura del telefono) |
| `FreeSpaceEvaluator.kt` | le regole su una copia del telefono, **solo da dati locali** (catalogo, diario del backup, versioni di P8) |
| `FreeSpacePorts.kt` | le porte: `FreeSpaceCatalog` (il grafo vero dei media, a pagine), `BootCounter`, il diario `FreeSpaceDiary` con `FreeRunRecord`/`FreeItemRecord`, la tabella `FreeItemTransitions` |
| `RoomFreeSpaceDiary.kt` | il diario su `free_space_run`/`free_space_item` della v4, **senza colonne nuove** (D27), con le stesse regole del fake |
| `EverywhereCopies.kt` | l'elenco di "Ovunque" con le copie di **tutte** le versioni (E-D15), dal catalogo, per il piano `Trash` della 4B |
| `PhoneOnlyDeletion.kt` | `PhoneOnlyAdvice`/`PhoneOnlyAssessment`: il foglio di "Elimina solo dal telefono" (E-D14, E-B14) |
| `testing/` | `InMemoryFreeSpaceCatalog`, `InMemoryFreeSpaceDiary` (rifiuta ciò che il diario vero non deve contenere), per P12, P13, P14 |

**Le regole** (tutte provate; tra parentesi i test in `core-backup/src/test/.../free/`)
- **Versioni da proteggere**: quella della copia del telefono più l'originale e l'attuale che esistono ancora (`SafetyRules`). Ognuna vuole nell'area privata
  **dell'account** (share uguale a `privateShare`, area `PRIVATE`) una copia presente, con l'hash completo noto e `verified_at`. Un hash dell'indice non conta
  (F4-D8). Solo la copia comune → guardiano (D9, E-B14), e solo con il cancello `OPEN`. Per un media con più versioni anche il custode delle versioni di P8
  (`VersionKeeper.plan`) deve dire "attuale uguale, ogni versione da proteggere `safe`": **due fonti indipendenti**; con `NoVersionKeeper` un media con più
  versioni non si libera mai (`FreeSpacePlanTest.versioni_*`).
- **Mai**: non proprio, non verificato, in caricamento (un elemento aperto nel diario del backup per **una qualunque** versione del media: anche guardiano,
  versioni e scambi di P8) o in errore (`BACKUP_PROBLEM`), con una versione scoperta, con l'area congelata o le scritture ferme (E-B22), fuori da `mcg-prova`
  con la recinzione D13, una riga già consegnata ad Android in un altro giro (mai una riga in due dialoghi).
- **Il cancello**: `FROZEN`/`WRITES_STOPPED` → escluso e giro fermo (`AREA_FROZEN`); `TEST_ONLY`/`NO_PRIVATE_AREA`/`NOT_READY` → "Libera spazio" non c'è
  (`NOT_AVAILABLE`). **Quindi in release con un account di prova (R2) e, fino a Q3, con l'account vero (D12) non si libera niente**: è il motivo di D13 (senza
  recinzione "Libera spazio" potrebbe togliere foto vere). `PAUSED` e `READ_ONLY_AREA` no: il flusso legge soltanto, ma il guardiano non scrive e quei media escono.
- **Il piano** non legge mai il NAS (E-S11: `FreeSpacePlanTest.eS11_*` conta zero aperture, stat ed elenchi) e non allarga mai la scelta dell'utente (un alias o
  un gruppo di copie simili non aggiungono media, I9 della revisione 5A). Di default tutto il sicuro tranne gli ultimi 30 giorni, filtri "solo video" e "più
  vecchi di un anno" (Q9); "oggi" viene dall'ora del NAS nel fuso del telefono, mai dall'orologio del telefono; senza l'ora del NAS un media datato è "recente".
  Il tetto di 20 GB del guardiano si applica già nel piano.
- **La conferma** si controlla contro il piano **ricalcolato adesso** (un elemento, un timbro, un filtro diversi: `CONFIRMATION_REJECTED`); "Libera subito"
  vuole due tocchi distinti (`PhoneConfirmationSheet`, 600 ms) e dà `TrashMode.DELETE_NOW`. **Un giro alla volta**: un giro nuovo chiude quello di prima
  (`EXPIRED`); un suo lotto già nel dialogo resta tracciato.
- **La validità** (D21, D31) parte dalla creazione del giro (prima di ogni rilettura: è il conto prudente), vale 6 ore di orologio **monotono** e solo nello
  **stesso avvio**: conteggio dei riavvii uguale (`Settings.Global.BOOT_COUNT`); se non si legge, solo nello stesso processo; un monotono che torna indietro
  è un riavvio. L'ora del NAS serve solo all'istante scritto nel catalogo.
- **La verifica sul momento** rilegge per intero ogni copia scelta (`NasRereader` di P3: timbro uguale all'apertura e dopo, dimensione della versione) e passa lo
  SHA-256 **letto** al catalogo (`markVerified`): diverso → l'elemento esce e la copia perde "al sicuro" (E-B8, il backup la rifà). Una copia letta per due
  righe del telefono si rilegge una volta. Un errore del NAS ferma il giro (`NAS_UNREACHABLE`, terminale: si rifà dal piano; E-B9).
- **Il controllo finale, prima di ogni dialogo**: le regole di nuovo dal catalogo (una versione nuova, un caricamento partito, una riga fuori recinto fanno
  uscire l'elemento), la riga del telefono (stessa riga, stesso timbro: un file modificato da Samsung "Salva" è una versione nuova, non al sicuro) e il **timbro**
  di ogni copia riletta (`COPY_CHANGED`, L39). Poi il lotto (≤ 2.000) si scrive `IN_DIALOG` **prima** di arrivare alla UI. Un lotto senza risposta si
  ripropone uguale e ricontrollato: mai due lotti aperti.
- **Le risposte**: confermate → `FREED` (solo le righe del lotto), le altre `KEPT`; rifiutato → `KEPT` e giro `CANCELLED`; sconosciuto, o un lotto di un
  processo morto → la **prova del telefono**: riga assente = liberata, presente = rimasta, altrimenti `IN_DOUBT` (mai riproposta, mai data per liberata).
- **Ripresa dopo la morte del processo**: con un diario completo (il fake; Room con colonne nuove) il giro continua nello stesso avvio; con Room v4 il motore
  ricorda per intero ciò che ha scritto finché il processo vive, e dopo la morte un giro a metà è `EXPIRED` (come prevede il commento di P1 sull'entità) mentre
  le righe consegnate ad Android restano tracciate per `phone_location_id` (`IN_DOUBT` finché la lettura del telefono non le risolve).
- **Nessuna scrittura sul telefono**: il motore vede il telefono solo con `PhoneBytes` (lettura) e il NAS solo con `NasMediaReader`; produce `TrashBatch` che la
  UI traduce in `createTrashRequest`/`createDeleteRequest` di core-mediastore (`FreeSpaceSourcesTest`).
- **"Elimina solo dal telefono"** (D22): `assessPhoneOnly` dà per media `FREE`, `GUARD_FIRST` (E-B14), `SAFE_FIRST` (E-D14, preselezionata), `WAIT`, `FROZEN`,
  `UNAVAILABLE`, `OUTSIDE_TEST_FENCE`, `NOT_ON_PHONE`; i liberabili passano da `plan(freeFilter)`, con **zero giorni esclusi** (li ha scelti l'utente).
- **"Ovunque"** (D22, E-D15): `EverywhereCopies.list` elenca le copie presenti dell'account di ogni versione (l'attuale prima, poi dalla più vecchia; area;
  in `Versioni/` o no; timbro del catalogo) e le righe del telefono; con D13 un media fuori recinto non si elenca. Nessuna lettura del NAS.
- **Motivi senza un codice proprio** nel contratto: un media non proprio o un backup non attivo → `NOT_VERIFIED`; una riga del telefono rinumerata o non
  leggibile al controllo finale → `GONE_FROM_PHONE`; una riga già nel dialogo di un altro giro → `IN_PROGRESS_OR_ERROR`.

**Prove** (58 test, tutti verdi): il "Fatto quando" di 5.5 con ogni variante (`FattoQuando55Test`: byte cambiato prima della verifica con il timbro nuovo e
**con lo stesso timbro**, dopo la verifica e prima del controllo finale, copia tolta prima e dopo, originale in `Versioni/` rovinato, copia rovinata dal guardiano, NAS
che cade a metà verifica e al controllo finale, ora del NAS spostata di ±1 anno con il monotono che decide); ogni lotto controllato quando esce **contro la
verità** del NAS finto e del telefono finto (invariante 14 del §5.4); `FreeSpaceEveryCallTest`: `FaultPlan` prima e dopo ognuna delle 27 chiamate al NAS e morte
del processo prima e dopo ognuna delle 19 scritture del diario e del catalogo, con ripresa nello stesso avvio e dopo un riavvio (130 interruzioni): nessun lotto
con una copia non integra, nessuna riga tolta senza traccia, alla fine tutto il sicuro liberato e la copia rovinata mai; lotti 2.000/2.000/500 su 4.500 media;
Room vero con il database chiuso e riaperto. **Provato reintroducendo i difetti** (il test giusto diventa rosso, poi si toglie): niente controllo del timbro
prima del dialogo (3 test), niente confronto dello SHA-256 (1 test: il secondo strato, il catalogo che toglie la verifica, ferma comunque l'elemento), conteggio
dei riavvii ignorato (3), lotto non scritto prima di consegnarlo (27), esito sconosciuto dato per liberato (1).

**Modifiche richieste ai contratti degli altri**
- **P14** (`:app`): legare `SafeFreeSpace` con `RoomFreeSpaceDiary`, `BootCounter` su `Settings.Global.BOOT_COUNT` (come `NasGraph`), `systemDialogs` = Android 11+,
  la recinzione di `DeveloperTools`, e un `FreeSpaceCatalog` su `MediaCatalog`: `media(ids)` = `media(id)` + `local_day` + `mediaSafety().problem` + il tipo;
  `markVerified` = `MediaCatalog.markVerified`; **`phoneMedia(after, limit)` manca**: serve una query a pagine dei media con una posizione del telefono presente,
  per `sort_time` decrescente (in `MediaCatalog`, d'accordo con P1, o in `:app`). La lettura del telefono deve consultare `free_space_item.forPhoneLocation`: una
  posizione sparita con una riga `freed`, `in_dialog` o `in_doubt` è "liberata" (cella "solo NAS"), non "persa"; dopo ogni lettura `SafeFreeSpace.settle()`
  (e la risoluzione degli `in_doubt` per posizione, che Room non sa per riga del telefono). La verifica come lavoro avviato dall'utente (`verify` poi `resume` a
  tappe) con la notifica "Pronto: N elementi, X GB" a fine verifica. "Ovunque" da `EverywhereCopies` (al posto dell'elenco di `NasOrganizer.targetOfPhoneCopy`) al
  piano `Trash` della 4B. `FreeSpaceDiary.handedPhoneLocations` (le righe consegnate ad Android e non risolte) oggi scorre tutti i giri: con molti giri nel diario serve una query su `free_space_item.state` (un metodo nuovo di `FreeSpaceDao`, P1). Facoltativo: colonne per validità e riga del telefono (migrazione 4→5, contro D27) se si vuole riprendere un giro dopo la morte del processo.
- **P12** (UI): `FreeSpaceRun` non dice quante righe sono "in dubbio" né quante "rimaste": proposta di due campi (`inDoubt`, `kept`) nel contratto di P0-B; due
  codici in più in `FreeSpaceExclusion` (`NOT_MINE`, `BACKUP_NOT_ACTIVE`) se il foglio vuole distinguerli da `NOT_VERIFIED`. I fogli usano `PhoneOnlyAssessment`
  (preselezione, `freeFilter`) e `EverywherePlan` (`previousVersions`, area, `Versioni/`). Il lotto si traduce in `PhoneMediaIntents.trashRequest`/`deleteRequest`
  con `MediaRef.Phone(volume, _ID, mime)` e la risposta torna con `recordOutcome` (sconosciuta se l'app è stata uccisa: `TrashOutcome.Unknown`).
- **P8**: `VersionKeeper.plan` va chiamato per ogni media con più versioni a ogni piano e a ogni controllo finale: deve essere solo catalogo (nessuna lettura del
  NAS) e veloce; `VersionEntry.safe` deve voler dire "copia privata riletta e verificata" e `current` la versione attuale del catalogo. Gli elementi `VERSION` e
  `SWAP` devono restare aperti nel diario del backup (`openFor` di una versione del media) per tutta la loro durata, e un'archiviazione in `Versioni/` deve
  aggiornare la posizione nel catalogo conservando `verified_at` (altrimenti l'originale archiviato non risulta al sicuro e il media resta sul telefono).

**Da provare sul campo** (F37, F38): "Libera spazio" su 50 media di `mcg-prova` con, dal PC, una copia con un byte cambiato e una cancellata (escluse; il resto nel
cestino di Android; celle "solo NAS"; una foto liberata si apre dal NAS); "Libera subito" con due gesti; l'app uccisa con il dialogo di sistema aperto (dopo la
riapertura le righe confermate risultano liberate, mai perse); un riavvio del telefono fra la verifica e il dialogo (giro scaduto); il Wi-Fi spento durante la
verifica (fermo, niente liberato); "Elimina solo dal telefono" di un media non al sicuro e di uno con la sola copia comune ("Prima mettilo al sicuro", guardiano
prima del cestino). Da misurare: tempo della verifica sul momento per GB (16 MB/s attesi) e il dialogo di Samsung con 2.000 righe.

## Fase 5B: P10, scegliere foto per le altre app (19/10)
Fonte: `docs/fase5-progetto.md` (§2.7 per intero, D23, D29, §4.3, riga P10 del §4.4, §6.1 e §6.4, E-H1, E-X1, E-X4, E-X6, E-X7), `docs/sicurezza.md` §4-bis e C17,
`docs/android.md` §5.3 (T-14), la sezione P0-B qui sopra. Perimetro: `feature-integrations/.../picker/**` e `.../provider/**`. Riempie due dei quattro
scheletri di P0-B, `PickerActivity` e `MediaDocumentsProvider`. **Nessun permesso, nessuna dipendenza, nessuna modifica al manifest, alle build o ai contratti
di `contract/`**: le porte nuove stanno nei pacchetti di P10 e `:app` le installa (P14).

**Cosa c'è** (`feature-integrations/src/main/kotlin/.../integrations/`)
| File | Cosa |
|---|---|
| `provider/IntegrationLibrary.kt` | **la Libreria per le altre app, una porta sola**: la vista (`ViewerItems`, senza nascosti), le miniature, l'URI del MediaStore, nome/tipo/dimensione dal catalogo, la lettura a intervalli di una copia del telefono e di un solo-NAS, la prova del NAS, le Cartelle NAS (chiavi opache). Più il null-object |
| `provider/LibraryMediaDocuments.kt` | `MediaDocuments` (P0-B) sopra quella porta, più `DocumentIssuer`: ciò che il selettore usa per **consegnare** un solo-NAS (`issue`, `isDelivered`, `revoke`) |
| `provider/DocumentIdTable.kt` | gli **id opachi**: token casuali di 18 byte (`SecureRandom`), tabella in memoria, due classi (da sfoglio, consegnati), scadenza a scorrimento sull'orologio monotono, capienza, revoca |
| `provider/ProxyDescriptors.kt` | il **descrittore a richiesta** (`StorageManager.openProxyFileDescriptor`): una lettura per volta dentro il buffer del sistema, scadenza a ogni lettura, annullamento, tetto dei descrittori aperti |
| `provider/CallerGuard.kt` | la seconda cintura: chi chiama il provider |
| `provider/MediaDocumentsProvider.kt`, `DocumentRows.kt` | il provider vero e le righe che risponde |
| `provider/DocumentPolicy.kt` | i tetti e le scadenze (un posto solo), l'orologio, i **contatori della Diagnostica** (soli codici fissi) |
| `picker/PickerRequest.kt` | cosa chiede l'altra app (azione, tipi, scelta multipla, solo locale), letto in modo difensivo |
| `picker/PickerPorts.kt` | le porte che `IntegrationPorts` non ha: la Libreria (`IntegrationLibrary`) e lo sblocco (`EntryUnlock`) |
| `picker/PickerDelivery.kt`, `PickerViewModel.kt` | dalla scelta al risultato (tetti, NAS, annullamento); lo stato (blocco, griglia, consegna) |
| `picker/PickerScreen.kt`, `PickerActivity.kt` | la schermata (solo componenti del design system, nessun colore a mano, stringhe in `strings_integrations_picker.xml` e `..._provider.xml`) e l'activity, ora una `FragmentActivity` (lo sblocco con la credenziale di sistema la vuole, come la `MainActivity`) |
| `provider/testing/`, `picker/testing/` | il telefono finto e il NAS finto (`FakeIntegrationLibrary`: indice misto, nascosti fuori dall'indice, byte virtuali, guasti, una porta che "sbaglia" apposta) e lo sblocco finto |

**Le regole** (tutte provate; tra parentesi i test in `src/test/.../integrations/`)
- **La vista di adesso è l'unico elenco, e la porta non è mai fidata** (E-H1; `HiddenNeverLeavesTest`, `LibraryMediaDocumentsTest`, `PickerDeliveryTest`). Un media si elenca,
  si descrive, si apre e si consegna solo se è nella vista della Libreria **in quel momento**: la vista non ha i nascosti, né i membri di un gruppo di copie simili con
  un nascosto (nascosto contagioso, D36), né ciò che l'accesso parziale non concede. Gli id che la porta delle Cartelle NAS restituisce lo stesso, e i nascosti che la
  porta "sbaglia" a conoscere (nomi, riferimenti, byte), non escono: né dal selettore né dal provider. Un media nascosto **dopo** che il suo id è nato non si apre più,
  e **prima di aprire qualunque file**; la vista si ricontrolla anche subito prima di creare gli id, perché durante le attese un media può essere stato nascosto da un altro dispositivo.
- **Blocco dell'app** (E-X7; `PickerViewModelTest`, `PickerActivityTest`, `PickerLayoutTest`, `MediaDocumentsProviderTest`). Il selettore decide da `EntryGate` e **rilegge il cancello a ogni ritorno
  in primo piano, al tocco su "Scegli" e un'ultima volta prima di consegnare**. Bloccata: nello stato non resta niente della Libreria (né griglia né selezione) e la griglia
  non è nemmeno in composizione; si vede solo "Sblocca", che chiede la credenziale di sistema da solo una volta per ritorno e poi con il tasto. Uno sblocco "riuscito" che
  non cambia il cancello non vale. Se il blocco si richiude durante la consegna gli id appena creati si revocano. Provider: l'app non pronta non ha radici né documenti; con il
  blocco acceso la radice c'è ("Bloccata: sbloccala dall'app") ma lo sfoglio risponde `AuthenticationRequiredException` con un tocco immutabile ed esplicito sul pacchetto che apre
  l'app; **un documento consegnato dal selettore** (sbloccato e toccato) si serve anche con l'app richiusa finché l'id vale, uno da sfoglio no.
- **Un'altra app non ottiene niente senza un tocco e senza un grant** (`MediaDocumentsProviderTest`). Il sistema fa già rispettare `MANAGE_DOCUMENTS` e i permessi sugli URI; qui c'è
  la **seconda cintura** (`CallerGuard`), che serve anche perché `getType` non passa dai permessi del provider: radici ed elenchi solo per il sistema (o per noi); un documento
  per il sistema o per chi ha un permesso di lettura su **quel** documento (non sulla cartella, non su un altro). Provato con un chiamante estraneo su `query`, `openFile`,
  `getType`, `queryRoots`, `queryChildDocuments`, `queryDocument`, `openDocument` (tutte `SecurityException`, zero descrittori aperti), con il controllo vero di Android (uid estraneo) e con un chiamante che ha il permesso
  su un solo documento. Il provider si rifiuta di nascere senza `MANAGE_DOCUMENTS` o non esportato.
- **Id opachi** (`DocumentIdTableTest`, `LibraryMediaDocumentsTest`, `PickerDeliveryTest`). Mai un percorso, una share, un account, un id del MediaStore o del NAS, un `MediaId`, un nome:
  token casuali; i nomi delle cartelle e dei file sono dati dell'utente che compaiono **solo** nel nome visualizzato, e lì senza separatori di percorso, senza caratteri di controllo,
  mai solo punti, al massimo 200 caratteri; il tipo MIME che non è `tipo/sottotipo` diventa `application/octet-stream`. L'URI di un solo-NAS è `content://<pacchetto>.integrations.documents/document/<token>` e basta.
  Id scaduto, sconosciuto, revocato o non opaco: sempre `FileNotFoundException` (nessun oracolo sui media).
- **Solo lettura**: ogni modo di apertura diverso da "r" è `SecurityException`; nessun flag di scrittura, cancellazione, rinomina, copia, spostamento, miniatura; nessun "recente" né ricerca di sistema
  (metterebbero le foto fra i recenti del selettore senza passare dallo sblocco).
- **Risultato del selettore** (`PickerDeliveryTest`, `PickerActivityTest`): copie del telefono = URI del MediaStore, solo-NAS = URI del provider con un id **consegnato**; sempre e solo
  `FLAG_GRANT_READ_URI_PERMISSION` (mai scrittura, mai persistente, mai prefisso) e `ClipData`; `data` solo se è uno. Niente esce da solo: un tocco (su un elemento, o su "Scegli" con
  `EXTRA_ALLOW_MULTIPLE`). `EXTRA_LOCAL_ONLY` esclude i solo-NAS. I tipi precisi (`image/jpeg`) si ricontrollano sul tipo vero di ogni file. L'intento si legge solo per azione, tipi e tre booleani
  (un extra ostile vale come assente; l'URI in `data` di `PICK` non si apre mai); un'azione che non serve o nessun tipo di media chiudono subito con `RESULT_CANCELED`.
- **Log, notifiche, Diagnostica**: solo numeri e codici (`IntegrationHygieneTest`): nessun log (né `Log` né `println`), messaggi delle eccezioni a frase fissa senza dati, `toString` di
  `MediaFileInfo`, `NasFolder`, `DocumentTarget`, `PickerUi`, `Ready` senza nomi né id. I contatori (`IntegrationCounters.snapshot()`, 19 codici fissi) li legge P14.

**Edge case e i test che li provano**
| ID | Cosa | Test |
|---|---|---|
| E-X1 | **Scadenza a ogni lettura** di 15 s (`ETIMEDOUT`, mai un'attesa infinita); apertura del documento con la stessa scadenza (`FileNotFoundException`); NAS irraggiungibile detto **prima** di creare gli id, con il motivo (`OFFLINE`, `METERED`, `UNAVAILABLE`) e la prova del NAS con scadenza di 5 s; **un URI usato dopo che il permesso è scaduto** non apre niente (sfoglio 30 min, consegnato 6 h, a scorrimento, monotono; un riavvio del processo li invalida tutti) | `ProxyDescriptorsTest.unaLetturaCheNonFinisceScadeConEtimedout_E_X1`, `MediaDocumentsProviderTest.unNasCheNonRispondeDaUnErroreSubito…`, `…unUriUsatoDopoLaScadenzaNonApreNiente_E_X1`, `…dopoUnRiavvioDelProcesso…`, `DocumentIdTableTest`, `PickerDeliveryTest.ilNas…_E_X1`, `PickerViewModelTest.ilNas…_E_X1` |
| E-X4 | **Annullamento a metà**: nel selettore ogni sospensione è prima della creazione degli id e dopo non si sospende più, quindi annullare durante le informazioni o la prova del NAS non lascia id; un guasto dopo la creazione li revoca; indietro e "Annulla" danno `RESULT_CANCELED` senza id né permessi. Nel provider il segnale di annullamento ferma l'apertura e le letture in volo (`ECANCELED`), il lettore si chiude una volta sola, il thread esce, il posto si libera | `PickerDeliveryTest.annullare…_E_X4`, `…seQualcosaFallisceDopo…`, `PickerViewModelTest.annullare…_E_X4`, `PickerActivityTest.indietroAnnulla…_E_X4`, `ProxyDescriptorsTest.annullareFermaLaLetturaInVolo…`, `MediaDocumentsProviderTest.unaAperturaAnnullata…_E_X4` |
| E-X6 | Molti solo-NAS insieme: **al massimo 100 solo-NAS e 2 GiB** in una scelta, e **500 elementi in tutto** (il limite del binder per i `ClipData`); oltre, rifiuto con la frase e la scelta resta per correggerla (anche al tocco, non solo al "Scegli"). Nel provider: **8 descrittori aperti**; le aperture oltre aspettano un posto fino alla scadenza, **al massimo 6 in attesa** (ognuna blocca un thread del binder), oltre rifiuto immediato | `PickerDeliveryTest.oltreCento…`, `…oltreDueGib…`, `PickerViewModelTest.ilCentounesimo…`, `…troppiSoloNas…`, `ProxyDescriptorsTest.oltreIlTetto…`, `MediaDocumentsProviderTest.moltiSoloNasInsieme…` |
| E-X7 | Blocco attivo: prima lo sblocco, nessuna cella in composizione, niente senza sblocco e tocco | vedi sopra |
| E-H1, E-L11 | Mai i nascosti, né il gruppo di copie simili con un nascosto, su **entrambe** le vie | `HiddenNeverLeavesTest` (selettore: 3 prove; provider: 3; confronto fra le due) |

**Prove**: 223 test nuovi in `feature-integrations` (237 nel modulo, tutti verdi): `HiddenNeverLeavesTest` 6, `IntegrationHygieneTest` 6, `PickerActivityTest` 13, `PickerDeliveryTest` 20,
`PickerViewModelTest` 22, `PickerRequestTest` 11, `PickerPhone/TabletLayoutTest` 12 (bordi a 1x e 1,3x, 48 dp, nessuna sovrapposizione, nomi per TalkBack), `PickerPhone/TabletScreenshotTest` 60,
`DocumentIdTableTest` 12, `LibraryMediaDocumentsTest` 19, `MediaDocumentsProviderTest` 27, `ProxyDescriptorsTest` 15. Screenshot in `docs/screenshots/5-P10/` (chiaro e scuro, telefono 412 dp e tablet 1280 dp, testo a 1,3x).
**Provato reintroducendo i difetti** (il test giusto diventa rosso, poi si toglie; 21 mutazioni, 20 rosse): il filtro della vista nelle cartelle (4 test), nell'apertura (1) e nella consegna dell'id (3), la vista in `prepare` (1),
la prova del NAS (8), i tetti dei solo-NAS (3), la revoca dopo un guasto (1), l'ultimo cancello (1) e quello al tocco (1), l'annullamento che non ferma la consegna (1), la potatura della selezione (1),
`CallerGuard` su documenti (3) ed elenchi (2), il modo di apertura (1), il documento consegnato sempre servito (2), la scadenza della lettura (1), l'annullamento della lettura in volo (1), la chiusura del lettore (3),
la scadenza degli id (4). Tre test sono nati da mutanti sopravvissuti: l'apertura di un media nascosto con una porta che sbaglia ("nemmeno il file", `phoneReaders` vuoto), il cancello al tocco (ora la scelta ha anche un solo-NAS e si
controlla che il catalogo e il NAS non siano stati toccati) e l'annullamento (ora si lascia scorrere il tempo e non cambia più niente). **Sopravvissuta una, equivalente**: il ricontrollo della vista subito prima di creare gli id
**dei solo-NAS** (`issue` ricontrolla la vista da solo: due strati). I test che provano una scadenza girano con un limite di tempo proprio (`ProviderTestSupport.within`, `withTimeout`): un mutante senza scadenza fa
**fallire** il test, non lo lascia appeso fino ai 20 minuti di Gradle (era successo con la mutazione della prova del NAS).

**Descrittore a richiesta** (D23, T-14). Niente copia in cache: l'altra app legge a intervalli. Ogni `onRead` riempie il buffer del sistema (mai tutto il file: provato con un file virtuale di 3 GiB, al
massimo 128 KiB per lettura) e riempie sempre la richiesta salvo la fine del file; offset e dimensioni assurdi sono `EINVAL`; `onWrite` è `EBADF`. Un thread per descrittore (nome generico), chiuso alla chiusura.
Se T-14 chiedesse il download prima in cache la forma della risposta cambierebbe: oggi il selettore consegna sempre l'URI del provider.

**Come si lega** (P14, da `:app`)
```kotlin
val library: IntegrationLibrary = ...           // vedi sotto
val documents = LibraryMediaDocuments(library, DocumentLabels.from(context))   // le tre etichette dalle stringhe del modulo
IntegrationPorts.install(entryGate, documents)  // provider E consegna dei solo-NAS
PickerPorts.install(library, unlock)            // selettore
```
`IntegrationLibrary`: `items` = `GalleryGraph.viewerItems(LibraryFilter.ALL)` (l'indice senza i nascosti e con l'accesso parziale), `thumbnails`/`placeholders` = la pipeline delle miniature,
`phoneUri` = `PhoneMediaIntents.uriOf`, `fileInfos` = il catalogo (nome, tipo, dimensione, data), `openPhone` = `ContentUriMediaBytes.open` dentro un `DocumentReader`,
`openNas` = il lettore dei byte del NAS **con la scadenza e senza mai accumulare il file** (`null` se non risponde), `nasReach` = rete + `MobileDataConsent` (`METERED` se i dati mobili non sono
stati accettati per quei media, D23 e E-V2), `nasFolders`/`mediaIn` = il catalogo. `EntryUnlock` = `AppLockController.beginAuthentication` + `BiometricPromptAuthenticator(activity).authenticate` + `endAuthentication`.

**Modifiche richieste agli altri**
- **P14, `EntryGate.decide()`** deve valutare il blocco **adesso**, tenendo conto del tempo passato dall'uscita dall'app (`AppLockController.onAppForegrounded` lo fa solo al ritorno in primo piano della `MainActivity`: con
  la `MainActivity` in secondo piano lo stato resterebbe `UNLOCKED` per sempre). Serve un metodo come `isLockedNow()`. E uno sblocco fatto nel selettore deve far rispondere `ALLOWED` a `decide()`; con "Blocca subito"
  il provider dei documenti non si può sfogliare dalle altre app se `decide()` torna `NEEDS_UNLOCK` appena la `MainActivity` esce: proporre una **finestra di tolleranza dopo uno sblocco d'ingresso** (due minuti, monotona).
- **P14**: azzeramento dell'app e cambio di famiglia → `LibraryMediaDocuments.revokeAll()`; Diagnostica → `IntegrationCounters.snapshot()` (`codice=numero`); le righe di `docs/sicurezza.md` §4-bis del selettore e del
  provider dicono ancora "scheletro" (non le ho toccate: sono di P14); una `ThumbnailLoader` **separata** per il selettore (la finestra delle richieste è per caricatore: due schermate sullo stesso la sovrascrivono).
- **Contratti di `contract/`**: nessuna modifica. `MediaDocuments` non ha un modo di consegnare un id a un media: per questo `DocumentIssuer` è nuovo (lo implementa `LibraryMediaDocuments`). Se si preferisce un'unica
  `install`, `IntegrationPorts` può prendere anche `PickerPorts` come terzo argomento: non l'ho fatto per restare nel perimetro.
- **`IntegrationSkeletonsTest` (P0-B)**: tolti i due test dello scheletro del selettore e del provider (descrivevano il comportamento che P10 sostituisce); restano quello della ricezione (P11) e le stesse garanzie, più forti, in
  `PickerActivityTest` e `MediaDocumentsProviderTest`.

**Limiti dichiarati e punti aperti**
- Nessuna miniatura per il selettore di documenti (`FLAG_SUPPORTS_THUMBNAIL`): gli elenchi hanno solo nome, tipo, dimensione e data.
- Con il blocco su "Subito" lo sfoglio dalle altre app chiede lo sblocco a ogni volta (vedi la finestra di tolleranza sopra); il selettore non ha il problema (sblocca e consegna nella stessa activity).
- Le Cartelle NAS dipendono da `nasFolders`/`mediaIn` di P14: se non li lega, la radice ha "Recenti" e una cartella vuota.
- I tetti di descrittori (8, attese 6) e la scadenza di 15 s sono quelli del progetto; il tetto del binder per i `ClipData` (500) è una scelta di P10. Da ricalibrare con le misure di F39.
- Il rendimento dei descrittori a richiesta con Gmail, WhatsApp e Telegram (lunghezza e parallelismo delle letture) non è misurato: è T-14.

**Da provare sul campo** (F39, T-14): la radice "MyCloud Gallery" compare in Gmail ("Allega file"), "Documento" di WhatsApp e Telegram; il selettore compare in OneDrive, I miei file, Google Drive, e **non** nel Photo Picker; con 5 foto del telefono e 5 solo-NAS (Wi-Fi di casa) scelte da Gmail l'allegato è identico all'originale (SHA-256); un video da 1 GB solo-NAS si allega senza riempire la memoria e, annullato a metà, non lascia thread né posti; NAS spento dopo la scelta: l'altra app riceve un errore subito (E-X1), non un'attesa; blocco app acceso con timeout "Subito": il selettore mostra lo sblocco e consegna; **nascondere una foto mentre il selettore è aperto** e toccare "Scegli": non esce; `am start -a android.intent.action.GET_CONTENT -t image/*` da `adb` con l'app bloccata non mostra niente prima dello sblocco; `content query --uri content://<pacchetto>.integrations.documents/root` da `adb shell` (senza `MANAGE_DOCUMENTS`): `SecurityException`; un URI consegnato aperto dopo 6 ore: non si apre; tempi di lettura di Gmail e WhatsApp (F39) per tarare tetti e scadenze.

## Revisione grafica della Libreria da parte dell'utente (13/10)
Giudizio: le prestazioni della navigazione non sono male, la **grafica no**. Serve molto lavoro, da fare in un **filone grafico
parallelo** alle fasi successive (insieme ai lavori che non toccano l'app sul momento, per esempio il back-end NAS della Fase 4).
Osservazioni da cui ripartire:
1. **I segmenti Anni/Mesi/Giorni/Tutte sopra la pillola sono di troppo.** Meglio un comando in alto (ordina/vista) e il pinch
   come gesto principale (come Foto di iOS 18, che ha tolto i segmenti in basso).
2. **Alcuni pulsanti si compenetrano** (barre e pulsanti in vetro sovrapposti, barra delle azioni sopra la pillola).
3. **Le scritte sotto il vetro si leggono a malapena**: serve un vetro pensato per la leggibilità (contrasto adattivo,
   scrim/tinta in base al contenuto sotto, ombre del testo, peso dei caratteri), non solo sfocatura.
4. "Il vetro richiede un bel po' di thinking dietro": va progettato con cura (anche lato engine; l'engine 2.12.0 cambia già
   sfocatura e rifrazione delle superfici grandi).
Le domande grafiche aperte dei pacchetti P7/P8/P10 (pinch che ancora la riga, segmenti sempre visibili, barra delle azioni sopra la
pillola, angoli e badge sotto i 64 dp, matte del viewer, "Nascondere vale solo in app") confluiscono in questo filone.

## Da aggiungere a `docs/sicurezza.md` dopo il P0 (per non scrivere in contemporanea)
- ~~Limite A2~~: aggiunto come L18 in `docs/sicurezza.md`.
- Contromisure C7–C10 e C23 da aggiornare con il modello finale di `formato-nas.md` (firme low-S, rami del registro, codice di approvazione a 80 bit).

## Cose tecniche da sistemare dopo la 1.5
- ~~`:app` dovrà dichiarare `ACCESS_NETWORK_STATE`~~: fatto nel P0 della Fase 2.

## Aperto
- Inventario dei media (numeri e formati).
- Copia tra share.
- WoL.
- Misure EmbeddingGemma 2 (velocità, RAM, qualità, risoluzione, MRL).
- Misure sull'S25.
- Profili secondari del tablet.
