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
| D29 | Nessun permesso nella 4A; UIDT nella 4B | Proposta (4B) |
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
