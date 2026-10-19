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
Fonte: `docs/review/5a-fase5a.md` (R1, R2, R4, R5, R6, R7 corrette, ognuna con il suo test; **R3 resta aperta**: la fa un'altra sessione,
`reopenTemp`, `openExistingForWrite`, `setLength` e `SmbNasFileOps` non sono stati toccati). Regola di tutte le scelte: **ogni correzione
riduce cio' che il backup scrive, mai il contrario**. Quelle che l'utente vede (da confermare):

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
- **Residui:** R3 (aperta), le informative I1-I9 come scritte; F21-F24 e le prove sul campo di R1, R5, R6 della revisione (sotto "Da
  provare sul campo") non sono state fatte (nessun NAS vero, nessun telefono).

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
