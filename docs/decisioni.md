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
- ~~"Separa" fra due dispositivi dipende da identita' uguali.~~ **Risolto il 20/10**: la voce porta l'impronta di entrambi i media e
  l'altro dispositivo li ritrova per id o per impronta; il test di integrazione prova i due casi ("stessi id" e "id diversi") ogni volta.
  Vedi "Separa fra dispositivi per impronta (20/10)" in fondo.
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

## Fase 5B: P11, ricevere, "Modifica con…" e condividere senza posizione (19/10)
Fonte: `docs/fase5-progetto.md` (§2.7 per intero, §2.4, D24, D25, D26, D29, D31, §4.3, riga P11 del §4.4, §6.1, §6.4, L41, E-X1, E-X2, E-X3, E-X5, E-X8, E-E3, E-E7, E-S11), le note per P11 delle sezioni P0-B e P8 qui sopra,
`docs/sicurezza.md` C17, C18 e §4-bis. Perimetro: `feature-integrations/.../receive/**`, `.../edit/**`, `.../share/**` (e i loro test e le loro stringhe), `core-metadata/.../strip/**`. Nessuna dipendenza nuova, nessun permesso nuovo,
manifest, `settings.gradle` e build condivisi intatti. **`Mp4Scout` di `core-prepare` non è stato toccato**: le scatole della posizione dei video le trova `IsoLocation` dentro `core-metadata` (come già deciso in P0-B: `core-prepare` dipende da
`core-metadata`, non il contrario), e un secondo camminatore di riquadri in `core-prepare` sarebbe stato codice morto.

**Cosa c'è**
| Dove | File | Cosa |
|---|---|---|
| `core-metadata/strip` | `DefaultLocationStripper.kt` | `LocationStripper` vero: tipo dai **byte**; JPEG, PNG, WebP (`ExifInterface` + XMP), MP4/MOV (scatole rinominate in `free`), HEIC → JPEG; ogni altro formato `Unsupported(FORMAT)` (L41); destinazione creata in esclusiva, mai lasciata se qualcosa va storto |
| | `ImageLocationStrip.kt`, `ImageXmp.kt`, `XmpGpsScrub.kt` | EXIF: tutte le etichette GPS **e la nota del produttore** (`MakerNote`: alcune marche ci mettono le coordinate); XMP: ogni proprietà `GPS*` (attributo o elemento, qualunque prefisso) diventa spazi **sul posto** (stessa lunghezza, nessun offset che cambia; PNG: CRC rifatto; i chunk "Raw profile type" di ImageMagick si neutralizzano; un pacchetto UTF-16 o PNG compresso con GPS dentro ferma la condivisione); la **parte video di una motion photo** (un MP4 dentro il JPEG, trovato da `ftyp` e dalla catena dei riquadri, qualunque sia il formato Google/Samsung) si ripulisce come un video |
| | `IsoLocation.kt` | camminata dei soli header: `moov/udta/©xyz`, `loci`, `gps `, la tabella `keys` di QuickTime con `com.apple.quicktime.location.*` e le voci di `ilst` che la richiamano (la chiave diventa `xxxx…` della stessa lunghezza), XMP in `uuid` e `XMP_`; riquadro → `free` **con il contenuto azzerato** (rinominare e basta lascerebbe i byte delle coordinate nel file). Una traccia di metadati a tempo `camm`/`gpmd`/`gps `/`nmea` (GPS che non si toglie senza ricodificare) ferma la condivisione. Tetti: 8 livelli, 4.096 riquadri per livello, 4 MiB di XMP, 64 MiB per un riquadro da azzerare; oltre, `TOO_COMPLEX` |
| | `LocationRecheck.kt`, `HeicToJpeg.kt` | il ricontrollo (sotto) e il convertitore (`ImageDecoder` + `Bitmap.compress`, oltre 32 MP si riduce; la bitmap non ha metadati) |
| `feature-integrations/share` | `LocationFreeShare.kt`, `ShareModel.kt`, `ShareCache.kt`, `ShareIntents.kt`, `ShareMessages.kt`, `CodeCounters.kt` | `prepare(media, removeLocation)`: copia in `cacheDir/share/<token>/<n>/`, strip, ricontrollo, URI; `ShareLease.release()` revoca i permessi e toglie le copie; `ShareStop` con i motivi |
| `feature-integrations/receive` | `IncomingStager.kt`, `DefaultIncomingUploads.kt`, `IncomingOrigins.kt`, `IncomingMime.kt`, `BlockingGuard.kt`, `ReceiveRequest.kt` | copia immediata dei file in arrivo, tetti, tipo dai byte, rifiuto di `file://` e dei provider nostri, `save` solo con la conferma |
| | `ReceiveViewModel.kt`, `ReceiveScreen.kt`, `ShareReceiverActivity.kt`, `ReceivePorts.kt` | la schermata "Salva sul NAS": cancello, copia, scelta della cartella, tocco, "in coda" |
| `feature-integrations/edit` | `DefaultEditSessions.kt`, `EditAttribution.kt`, `EditPorts.kt`, `EditIntents.kt` | le sessioni (`begin`/`settle` di `EditSessions`) e le regole con cui un cambiamento è o non è della sessione |

**Condividere senza posizione (D26, E-X5)**
- **Si fallisce chiusi.** `LocationFreeShare` non consegna mai "com'è" per ripiego: formato non trattabile (`CANNOT_REMOVE_LOCATION`), posizione rimasta (`LOCATION_REMAINS`), guasto, NAS che non risponde, spazio, tetti → `Stopped(motivo, indice)`, **tutte** le copie già fatte si tolgono (una condivisione è tutta o niente) e nessun URI è stato emesso. Con l'impostazione spenta un media del telefono si condivide com'è (il suo URI del MediaStore, nessuna copia) e un solo-NAS si scarica in cache (E-V7).
- **Il ricontrollo** (`DefaultLocationStripper` richiede almeno **due** lettori che si applicano al tipo, altrimenti `Unsupported(NOT_AVAILABLE)`: un legame sbagliato non produce un successo; in coda ne aggiunge sempre uno suo, grezzo): (1) l'**estrattore dell'app** (`ExtractorLocationRecheck`: deve leggere la copia come media senza luogo e, per le foto, il decodificatore deve ricavarne un'anteprima); (2) per le foto `ExifInterface`, per i video `MediaMetadataRetriever` (`METADATA_KEY_LOCATION`, qualunque stringa non vuota è una posizione; il video deve aprirsi e avere una durata); (3) `RawLocationRecheck` guarda i byte: XMP di ogni pacchetto, motion photo incorporate, scatole dei video, tracce a tempo, e che un video abbia ancora il suo `moov`. **Un lettore che non riesce a leggere non assolve** (`UNREADABLE` → la copia si toglie). `LocationRechecks.standard(extractor, decoder, zone)` li dà a `:app`.
- Il nome consegnato è quello dell'utente, ripulito (niente separatori né controlli, 80 caratteri) e con l'**estensione del tipo vero** (un HEIC diventato JPEG si chiama `.jpg`), in una cartella per elemento (nessuna collisione); la copia letta con la posizione si toglie appena finito lo strip.
- Tetti (E-X6): 100 elementi, 2 GiB di copie, almeno 1 GiB libero dopo la copia (per togliere la posizione servono **due** copie insieme, quindi si conta il doppio).
- `ShareIntents.send`: `ACTION_SEND`/`SEND_MULTIPLE` con `ClipData` e `FLAG_GRANT_READ_URI_PERMISSION` e **mai** scrittura né permessi persistenti. Il `FileProvider` serve solo `share/` ed `edit/` (`ShareCache.isInside`/`deleteTree` non escono dalle due radici nemmeno con un percorso canonico diverso).
- Contatori in `FlowCounters` (`ReceiveCounter`, `EditCounter`, `ShareCounter`): solo codici fissi `minuscole_e_trattini_bassi`.

**Ricevere (D24, C18, E-X2, E-X8, E-S11)**
- `IncomingOrigins.classify` guarda solo l'**host** (non l'autorità: `0@<pacchetto>…` è il nostro provider con l'utente davanti), senza badare alle maiuscole: un host uguale al pacchetto o che comincia per `<pacchetto>.` è `OWN_PROVIDER`; qualunque schema che non sia `content://` (file, http, `android.resource`, senza schema, senza host) è rifiutato con il codice `FILE_SCHEME`. Questi flussi **non si aprono mai**.
- `IncomingMime` (con `MediaSniffer` di core-mediastore come primo filtro): un `.jpg` che dentro è HTML, JSON, PDF, ZIP, azzerato o vuoto è `NOT_MEDIA`; il tipo è quello della firma (un PNG dichiarato JPEG è PNG); un contenitore MP4 con marca principale audio (`M4A `…) non è un video; un RAW (intestazione TIFF) prende il tipo dichiarato solo se è un tipo RAW; HEIC/HEIF/AVIF si distinguono dalle marche di `ftyp`.
- `IncomingStager`: uno alla volta, in `noBackupFilesDir/incoming/<id>/<sha256>`; i tetti (`StageLimits`: 2 GiB, 4 GiB, 500 elementi, 1 GiB libero) valgono prima di aprire (dimensione dichiarata) e **mentre si legge** (un flusso infinito si interrompe al tetto, mai letto fino in fondo); l'immagine che dichiara dimensioni da bomba (`ImageBounds` di core-trust sull'intestazione, `BitmapFactory` `inJustDecodeBounds`) si rifiuta prima che qualcosa la decodifichi; lo stesso contenuto due volte nella stessa condivisione si tiene una volta; il nome dell'altra app (ripulito) non entra mai in un percorso. Un'**infinità di indirizzi** si conta e non si guarda (`maxExamined` 2.000).
- `BlockingGuard`: l'altra app è un'origine non fidata anche **di tempo**. Un `query`, `getType`, `openInputStream` o una `read` che non risponde non si interrompe con la cancellazione di una coroutine: il lavoro bloccante gira in una scope sua, chi aspetta guarda l'avanzamento in byte e dopo 30 s senza progresso (3 s per le sole informazioni di nome e dimensione, 15 s di budget in tutto) **abbandona e chiude il flusso**; il file a metà lo toglie il lavoro stesso, la cartella la ritenta una pulizia di fondo.
- **Niente parte da solo.** `ReceiveViewModel` copia appena il cancello (`IntegrationPorts.entryGate`) è aperto; con l'app bloccata `streams()` non è nemmeno chiamato (nessun `query`, nessuna apertura); se il cancello si richiude dopo, la schermata torna a mostrare solo lo sblocco (riepilogo e nomi delle cartelle nascosti, il lavoro prosegue, lo stato vero riappare allo sblocco). La `PhoneConfirmation` nasce **solo** dal tocco su "Salva sul NAS" (`PhoneConfirmationSheet(IncomingSavePlan).tap(monotonic)`), dal piano di adesso, e `DefaultIncomingUploads.save` la ricontrolla (altro piano, oltre 10 minuti, istante monotono nel futuro) dopo aver verificato che i file siano ancora quelli copiati. Indietro, "Annulla" e la chiusura dell'activity senza scegliere tolgono le copie; dopo "in coda" i file restano al diario.
- L'activity è una `FragmentActivity` (lo sblocco con la credenziale di sistema la vuole), ha `filterTouchesWhenObscured` e, da Android 12, `setHideOverlayWindows(true)` (il permesso è già nel manifest di `:app` per `core-security`; senza, resta il filtro dei tocchi oscurati): nessuna app che disegna sopra fa premere "Salva sul NAS".
- L'avviso dell'area comune ("Lo vedranno tutti i familiari.") sta sopra il bottone, sempre in vista (D24). Un solo bottone "Apri la cartella scelta" per le sottocartelle, sotto la lista e non dentro le righe: nessun controllo ne contiene un altro (`ReceiveLayoutTest` lo controlla anche a 1,3x).

**"Modifica con…" (D25, E-X3, E-E3, E-E7)**
- Regole (`EditAttribution`): la **riga cambiata** la riconosce la lettura del telefono (il servizio la mette in pari con `EditCatalog.refreshPhone()` e legge la versione attuale: `EDITED` → `NewVersion`, `ORIGINAL` → `RestoredOriginal` (E-E2), `CORRECTED` → `Unchanged`: una correzione dell'app non è dell'editor). Una **copia** ("Salva copia") è della sessione solo se, insieme: nata **dopo** l'inizio, nella **stessa cartella**, con la **stessa data di scatto** (`DATE_TAKEN`), dello **stesso genere** (foto o video) e non la riga di partenza. "Dopo l'inizio" si misura con la **generazione** del MediaStore (mai l'orologio del telefono, D31); senza generazioni (Android 10) con la data di modifica confrontata con quella della riga di partenza. **Nel dubbio non si unisce**: più candidate → `NewMedia` senza chiedere niente al catalogo; il catalogo che rifiuta → `NewMedia`; la scansione che non ha ancora visto la copia → `Unreadable` (la sessione resta aperta e si riprova).
- Sessioni: 30 minuti di **ora del NAS** (D31, `BackupClock.nasNowMs`), nel database (`EditSessionStore` ↔ `edit_session`, tutte colonne già dello schema v4), sopravvivono alla morte del processo; alla fine esatta della finestra vale ancora, un millisecondo dopo è `Expired` e un cambiamento non è più attribuito (sarà una versione normale, E-E5). Una sola sessione aperta per media: una nuova chiude la vecchia (e ne toglie la copia). `Unchanged` e `Unreadable` lasciano la sessione aperta (l'editor può essere ancora aperto).
- **Solo-NAS in cache (E-E3)**: `EditNasCopies.copyCurrent` scrive `cacheDir/edit/<token>` e dà l'hash; `ACTION_EDIT` con `ClipData` e permesso di lettura **e scrittura** sul solo quell'indirizzo (`EditIntents.editCopy`); a `settle`: byte uguali → si cerca una copia salvata altrove (Google Foto scrive un file nuovo; stessa data di scatto con **1 s** di scarto perché per un media senza riga la data viene dall'EXIF, solo con le generazioni, nata dopo, un solo candidato); byte cambiati → il risultato è **input non fidato**: non vuoto, ≤ 2 GiB, un media (`IncomingMime`) e **dello stesso genere** (una foto che diventa video si rifiuta, `RESULT_REFUSED`, la sessione resta aperta e l'originale intatto); se vale, nell'ordine di P8: `StagedVersionFiles.keep(file, sha, media)` → `EditCatalog.addEditedVersion` → `StagedVersionPlanner` (`NasVersionEngine.planStaged`). Un catalogo che non ha più il media → il file preparato si toglie.
- **Scarica e modifica in Galleria (E-E7)**: `GalleryDownloads.downloadForEdit` (il download di F4-D20, già legato al media del NAS) restituisce la riga nuova e da lì la sessione è come quella del telefono: la modifica è una **versione del media del NAS**, nessun media nuovo, nessun ricollegamento.
- A sessione finita (esito, scadenza, annullamento) la copia in cache si toglie e il permesso si revoca (`UriRevoker`); `expireStale()` (da chiamare all'avvio) chiude le scadute e restituisce i nomi delle copie da tenere per `ShareCache.sweepAtStart(keepEdit)`.
- D13: `phoneFolderAllowed(relativePath)` (lo lega `:app` a `BackupFence`): in debug con gli account di prova la riga fuori da `mcg-prova` non si apre.
- `EditIntents`: `ACTION_VIEW` verso `com.sec.android.gallery3d` con pacchetto esplicito; si **lancia e basta** (`ActivityNotFoundException` → la UI propone "Scarica e modifica" o un altro editor): nessun `<queries>` nel manifest.

**Test** (JVM; Robolectric con grafica nativa dove c'è `ExifInterface`, `BitmapFactory` o Compose)
- `core-metadata/strip` (39): `DefaultLocationStripperTest` (24: JPEG, PNG con CRC dei chunk, WebP, JPEG con **solo XMP** con GPS e EXIF pulito, motion photo, MP4 stessa dimensione e `©xyz` → `free`, MOV di Apple con chiavi e `ilst` (la voce di marca e modello resta), MP4 con XMP in `uuid`, tracce `camm` e `gpmd`, video senza `moov`, HEIC con convertitore finto / che perde la posizione / mancante / guasto, GIF, BMP e testo, destinazione esistente, sorgente assente, tetto, i lettori che dicono "posizione" o "non so", meno di due lettori; il sorgente è sempre byte per byte intatto e dopo ogni fallimento la destinazione non c'è); `IsoLocationTest` (6: posizioni in ogni posto, 64 bit, riquadri che escono dal file, coda che non è un riquadro e che nasconde un XMP, annidamento, troppi riquadri, regione incorporata, `MediaMetadataRetriever` con il suo shadow); `XmpGpsScrubTest` (4); `AndroidHeicToJpegTest` (5: la parte che gira su JVM, vedi i limiti).
- `feature-integrations`: `receive` (`IncomingStagerTest` 16: URI ostili, tipi falsi, tetti, flusso infinito, flusso che si pianta, cancellazione, nome ostile, doppioni, bomba, pulizia; `IncomingOriginsAndMimeTest` 7; `DefaultIncomingUploadsTest` 8; `ReceiveRequestTest` 6; `ReceiveViewModelTest` 13; `ShareReceiverActivityTest` 8, con un provider vero che **conta le aperture**; `ReceiveLayoutTest` 10 e gli screenshot 72), `edit` (`DefaultEditSessionsTest` 30, sul telefono finto `FakeMediaStore`), `share` (`LocationFreeShareTest` 16 con lo stripper vero, `ShareNamesAndIntentsTest` 7), `FlowHygieneTest` (8: nessun log, nessun colore a mano, nessuna frase nel codice, messaggi delle eccezioni fissi, nessuna scrittura sul telefono). Suite intera di `core-metadata` (259) e di `feature-integrations` (439, comprese quelle di P10) verdi.
- **Mutazioni provate** (si rompe il codice, il test giusto diventa rosso, si ripristina): un lettore che trova una posizione ignorato; payload di una scatola non azzerato; XMP non ripulito; host del provider nostro riconosciuto solo se uguale al pacchetto; conferma non controllata; flussi richiesti con l'app bloccata; data di scatto non confrontata; tetto dei 2 GiB di una condivisione tolto; `file://` non rifiutato.

**Da provare sul campo** (F40–F42, T-15, T-16; solo `mcg-prova`, D12/D13)
- **F40**: condividere da Samsung Gallery, I miei file e Chrome verso "Salva sul NAS" (T-15: quali app passano `file://`, quali `ClipData`, per quanto tempo vale il permesso: la copia parte appena l'activity è su); 3 foto e 1 video da 1 GB; un video da 3 GB (rifiuto con il motivo); app bloccata con timeout "Subito" (prima lo sblocco, niente copiato: `ls noBackupFilesDir/incoming` vuota); "Salva" con il NAS spento (copia in coda, "lo salvo appena risponde") e poi acceso; indietro a metà copia non lascia niente; area comune con l'avviso.
- **F41** (T-16): "Modifica con…" di una foto del telefono in Samsung Gallery con "Salva" (versione), con "Salva copia" (versione dello stesso media: verificare che `DATE_TAKEN` della copia sia **identico** a quello della riga di partenza, è l'ipotesi della regola), con "Ripristina originale"; di un solo-NAS in Google Foto e OneDrive (scrivono nell'URI o salvano un file nuovo? con quale `DATE_TAKEN`?); "Scarica e modifica in Galleria"; l'editor tenuto aperto oltre 30 minuti; prima e dopo, il contatore dei byte caricati.
- **F42**: foto con posizione dell'S25 (JPEG, HEIC, WebP/PNG da un'altra app, uno **screenshot di Maps** con la posizione nell'XMP) e video (MP4 dalla fotocamera, `exiftool` dal PC: `-a -G1 -gps:all -xyz -location*` vuoto sul file condiviso); il **HEIC vero** (HEVC): `ImageDecoder` lo converte? con che tempo e dimensione, orientamento giusto (`irot`)?; una motion photo di Samsung e una di Google (il video incorporato non ha più `©xyz`); un video con più tracce di metadati di Samsung (nessuna deve essere scambiata per `camm`/`gpmd`: se una condivisione di un video normale si ferma con `LOCATION_REMAINS`, è un falso positivo da correggere); WhatsApp, Telegram e Gmail ricevono il file e il nome è quello dell'utente; i permessi revocati dopo l'uso (`dumpsys` o aprire l'URI dopo `release()`).

**Modifiche richieste agli altri**
- **P14 (`:app`)** — legare, all'avvio: `ReceivePorts.install(DefaultIncomingUploads(IncomingStager(File(noBackupFilesDir, "incoming")), queue, clock), destinations, unlock)` (lo `unlock` è lo stesso `EntryUnlock` del selettore) e poi `stager.sweep(keep = <stage_id degli incoming_item aperti>)`; `ShareCache(cacheDir).sweepAtStart(keepEdit = editSessions.expireStale())` **prima** di tutto il resto; `IntegrationPorts.install(entryGate, …)` come per P10. Il `ReceiveUnlock` e l'`EntryGate` devono valutare il blocco **adesso** (nota di P10), con la stessa finestra di tolleranza dopo uno sblocco d'ingresso.
- **P14, il diario dei file in arrivo**: `IncomingQueue` (`destinationAllowed` con D12/D13, E-N9 e l'area; `enqueue` crea un elemento `INCOMING` per file in `backup_item`/`incoming_item`, nome occupato → "nome (2)", e **cita** i file locali). Oggi `SafeUploadEngine` rifiuta `BackupTrigger.INCOMING` e per `UploadKind.INCOMING` risponde `Step.Wait`: il cammino `INCOMING` del motore (leggere dal file locale con l'hash già noto, verifica, pubblicazione senza sostituire, poi **toglie il file locale**) non esiste ancora — è una richiesta per il motore del backup (P14 o un pacchetto di `core-backup`), non per questo modulo.
- **P14/P1, catalogo**: due operazioni nuove di `MediaCatalog` dietro `EditCatalog`: `linkCopyAsVersion(into, copy, parent)` (il media nato dalla riga che l'editor ha scritto diventa una versione `EDITED` figlia di `parent`, posizioni e alias; rifiuta con hash completi che dimostrano altro, nascosti, proprietari diversi; **mai sostituire**) e `addEditedVersion(into, parent, sha, qh1, size)` (versione `EDITED` senza posizione, come `correct` ma `VersionKind.EDITED` e senza campi di correzione: `VersionRules.corrected` pretende dei campi, quindi serve `contentChangedAt`-senza-posto o una funzione nuova); più `mediaOfPhoneRow(PhoneRef)`, `current(media)` (id e tipo) e `refreshPhone()` (una scansione incrementale del telefono). `EditNasCopies` ← `NasMediaReader.readAll` sul file di cache con l'hash; `GalleryDownloads` ← l'azione "Scarica sul telefono" della Fase 4; `StagedVersionPlanner` ← `NasVersionEngine::planStaged`; `EditSessionStore` ← `edit_session` (colonne già presenti: la riga di partenza, `taken_at_utc`, `relative_path`, `start_generation`, `cache_name`, `cache_sha256`).
- **P14, ora del NAS**: `begin` risponde `NAS_UNREACHABLE` se `BackupClock.nasNowMs()` è null (come il fake di riferimento): fuori casa, a un avvio a freddo senza una sessione NAS già ancorata, "Modifica con…" di una foto del **telefono** non parte. Se non va bene, l'ancora dell'ora va persistita (D31) o la sessione del telefono può misurarsi in monotono (`SystemClock.elapsedRealtime`) con un azzeramento al riavvio.
- **P12 (UI)**: "Condividi" del viewer usa `LocationFreeShare.prepare(media, removeLocation, onProgress)`, `ShareOutState` (`toState()`), `shareStopText(context, stop)` (testo di ogni fermata, con "condividilo con la posizione o annulla"), `ShareIntents.send(files)` e **`lease.release()` quando l'utente torna nell'app** (e comunque all'avvio dopo, `sweepAtStart`): i permessi non sono permanenti; le stringhe `integrations_share_preparing`, `…_progress`, `…_ready_verified`, `…_share_with_location`, `…_cancel` sono già in `strings_integrations_share.xml` e non sono ancora usate. La voce "Modifica con…" chiama `EditSessions.begin(media, target)` e poi `EditIntents.forSession(...)`/`launch` (che dice `false` se Samsung Gallery non c'è), e `settle` al ritorno in primo piano.
- **`docs/sicurezza.md` (P14)**: §4-bis e C17 dicono ancora "scheletro" per la ricezione; C18/L41 possono dire che i tre lettori sono davvero tre e che un pacchetto XMP UTF-16 o PNG compresso con GPS ferma la condivisione; un limite nuovo: **una traccia di metadati a tempo `camm`/`gpmd`/`gps `/`nmea` o un formato non trattabile fermano la condivisione senza posizione** (già L41 nella sostanza).
- **`IntegrationSkeletonsTest` (P0-B)**: il test della ricezione descriveva lo scheletro che chiude subito; ora afferma le garanzie ancora valide (porte non installate → nessun flusso né `query` verso l'altra app; azione che non è una condivisione → `RESULT_CANCELED`).

**Limiti dichiarati e punti aperti**
- **La conversione HEIC → JPEG vera non gira su JVM** (`ImageDecoder` risponde "solo su Android", e non c'è un decodificatore HEVC): `AndroidHeicToJpeg` si prova con una sorgente di bitmap di `BitmapFactory` (la parte che compone e comprime, l'assenza di ogni metadato, il tetto dei pixel, il rifiuto di un file che non si decodifica) e `ImageDecoderBitmaps` solo per il suo tetto; che un HEIC dell'S25 si converta lo prova F42. Senza decodificatore la condivisione senza posizione di un HEIC si ferma (`CANNOT_REMOVE_LOCATION`).
- `MediaMetadataRetriever` non gira su JVM: lo prova il suo shadow di Robolectric (che non legge il file: gli si dice cosa risponderebbe). I test dei video con lo stripper vero usano una sonda `©xyz` per l'estrattore e un lettore di prova al posto di `MediaMetadataLocationRecheck`; la parte vera è F42.
- `FileProvider.getUriForFile` non si prova su JVM/Windows (separatori, già annotato in P0-B): `FileProviderUris` è l'unico codice non coperto, i test usano `FakeCacheUris`.
- **Non si toglie**: i nomi di luogo di IPTC/XMP (`photoshop:City`…) e il testo libero (descrizione, commento): sono parole dell'utente, non coordinate; le coordinate scritte come testo dentro il flusso compresso di un PNG non si vedono (si controlla solo l'XMP non compresso e quello compresso, che si decomprime solo per controllare).
- Un MP4/MOV con **più di 4.096 riquadri** a un livello o annidato oltre 8 livelli si rifiuta (`TOO_COMPLEX`); un video con `moov` oltre il fondo di un file troncato non si condivide (non si riprodurrebbe nemmeno).
- La data di scatto di un solo-NAS in cache e quella della copia dell'editor devono coincidere entro 1 secondo e **solo** con le generazioni del MediaStore (Android 11 e successivi); su Android 10 un solo-NAS modificato in un editor che salva altrove diventa un media nuovo.
- Il caricamento delle condivisioni grandi come lavoro avviato dall'utente (`IncomingStage.needsUserInitiatedJob`) lo decide `save` (ritorna `Queued(userInitiated)`): chi lancia il `BackupJobService` è P14.
- Il caso di un `iTXt` PNG **compresso** con GPS dentro (si ferma la condivisione) non ha una fixture; il caso UTF-16 sì.
- Le miniature dei file in arrivo non si mostrano (il foglio dice numeri e tipi, come da §1.5): niente decodifica di un'immagine non fidata in schermata.

## Fase 5B: P12, la UI della 5B (19/10)
Fonte: `docs/fase5-progetto.md` (§1.3 `feature-backup`, `feature-viewer`, `feature-settings`; §1.5 gli stati; §2.3, §2.4, §2.5, §2.7, D13, D19–D22, D26, D31, R7, Q6 (a), Q9 (a), riga P12 del §4.4), `docs/grafica-progetto.md` (§3, P2 e P3, "vetro adattivo"),
i contratti di P0-B (`PhoneConfirmationSheet`, `FreeSpace`, `VersionKeeper`, `Corrections`, `PendingTasks`) e le sezioni P8, P9, P10, P11 qui sopra. Perimetro: `feature-backup/.../free/**`, `.../versions/**`, `.../tasks/**`;
`feature-viewer/.../menu/BackupActions*`; `feature-settings/.../sharing/**` (e i loro test e le loro stringhe). Manifest, `settings.gradle`, build condivisi e dipendenze intatti; **niente è legato in `:app`** (lo fa P14, elenco sotto).
Nessun contratto di `core-backup` è stato cambiato (richieste in fondo). Le righe già esistenti in altri file sono toccate solo dove serviva: `ViewerServices`, `ViewerActions`, `ViewerOverlay`, `NasActionsSheets` (viewer), `SettingsScreen` e `SettingsNavigation` (una riga e una rotta).

**Cosa c'è**
| Dove | File | Cosa |
|---|---|---|
| `feature-backup/free` | `FreeSpaceFlow.kt`, `FreeSpaceViewModel.kt`, `FreeSpaceUi.kt` | la macchina a stati di "Libera spazio" (**vive quanto il processo**, riusabile da un lavoro lanciato dall'utente di P14): piano → guardiano → verifica sul momento → pronto → lotti → fatto; `FreeSpaceUi` ha un caso per ogni stato del §1.5 |
| | `FreeSpaceScreen.kt`, `FreeSpaceSheets.kt`, `ChoiceRow.kt` | la pagina (avviso forte, "cosa liberare", "non li libero" con i numeri, i due pulsanti) e i due fogli: **"Libera spazio"** (un tocco) e **"Libera subito"** (due gesti distinti) |
| | `ConfirmTaps.kt` | l'**unico** punto che costruisce `PhoneConfirmationSheet`: `PhoneConfirmation` nasce solo da un tocco |
| | `DialogBridge.kt`, `FiveBPorts.kt` | la porta `PhoneSystemDialogs` (restituisce un `IntentSender`: feature-backup non conosce `core-mediastore`) e il ponte che lo lancia con `rememberLauncherForActivityResult`; la risposta arriva anche dopo una rotazione |
| | `PhoneOnlySheet.kt` | il foglio di "Elimina solo dal telefono" (sopra `PhoneOnlyAssessment`: si può liberare / metti prima al sicuro / aspetta / area bloccata) |
| | `FiveBBindings.kt` | `@BindsOptionalOf` per le porte nuove (`FreeSpace`, `VersionKeeper`, `Corrections`, `PendingTasks`, `BackupClock`, `PhoneSystemDialogs`, `PhoneOnlyAssessor`, `PendingTaskActions`, `VersionActions`, `CorrectionPlaces`): l'app compila anche prima di P14 e le schermate dicono "non ancora pronto" invece di rompersi (fail closed) |
| `feature-backup/versions` | `VersionsSheet.kt`, `VersionsViewModel.kt`, `VersionsModel.kt` | il foglio **Versioni** (corrente, intermedie, originale; dove sta ogni copia e se è al sicuro), "Vedi originale", **"Ripristina originale"** con riepilogo, permesso di Android e scambio, "Elimina le intermedie…" |
| | `CorrectionSheet.kt`, `CorrectionViewModel.kt`, `CorrectionModel.kt` | il foglio **Correggi** (data e ora, luogo, rotazione, anteprima di cosa cambia) e i suoi stati: prima al sicuro, permesso, scrittura, caricamento, fatto, "solo nell'app", guasto, bloccato |
| `feature-backup/tasks` | `PendingTasksScreen.kt`, `PendingTasksViewModel.kt` | **Da decidere** (l'originale di un video modificato: tienilo o eliminalo) e la scheda d'ingresso `PendingTasksEntryCard` per la pagina del backup |
| `feature-viewer/menu` | `BackupActions.kt`, `BackupActionsController.kt`, `BackupActionsSheets.kt` | le voci del menu ⋮ (Versioni, Vedi originale, Ripristina originale, Correggi data e luogo, Ruota, Modifica con…) solo se `:app` ha legato `BackupMediaActions`; "Condividi" passa da `:app` e dice **"Condividi senza posizione"** quando l'impostazione è accesa; avviso di preparazione, foglio "Condivisione ferma" (non condivide mai da solo), frase dopo un'azione che si spegne da sola |
| | `NasActionsSheets.kt` (editato) | "Elimina…" elenca anche le versioni precedenti (`extraLocations`) e instrada "Elimina solo dal telefono" al foglio di P12 |
| `feature-settings/sharing` | `SharingSettings.kt`, `SharingScreen.kt` | l'impostazione **"Rimuovi la posizione"** (spenta di default, C18): porta + implementazione su `SharedPreferences` (un solo valore, niente di personale) + modulo Hilt; la pagina "Condivisioni" con l'interruttore e i formati che si sanno trattare (L41); la riga nelle Impostazioni (`SettingsLinks.openSharing`, la rotta è dentro il grafo) |

**Regole che le schermate rispettano**
- **Un tocco è la conferma.** `PhoneConfirmation` non si crea mai fuori da `ConfirmTaps` e il foglio si apre solo da un tocco; un foglio riaperto o scaduto (10 minuti di orologio monotonico) riparte da capo e **ricontrolla il piano** (l'hash del piano è nella conferma).
- **"Libera subito" richiede due gesti distinti.** Il contratto ne impone due con almeno 600 ms in mezzo; la UI aggiunge un secondo pannello ("Ultima conferma") che compare solo dopo il primo gesto, con il pulsante che conta **in un'altra posizione** (in alto "No, tienili", pieno; sotto "Sì, elimina per sempre", rosso) e armato dopo la stessa pausa: un doppio tocco sullo stesso punto non basta. Testato a livello JVM e Compose.
- **R7 in ogni foglio.** Tutti i fogli passano da `GalleryConfirmSheet`/`GallerySheetScaffold` (velo anti-sovrapposizione `filterTouchesWhenObscured` e `LocalConfirmSheetShield`): `SheetShieldTest` prova, per ciascun foglio, che lo scudo è chiamato quando il foglio è visibile e mai quando è chiuso.
- **Il dialogo di Android è l'ultima parola.** `Libera spazio` chiede il cestino di Android (30 giorni), `Libera subito` la cancellazione definitiva, entrambi per lotti; rifiutato → "non l'ho tolto"; senza dialogo disponibile (`NOT_AVAILABLE`) l'esito si registra come **sconosciuto** e la pagina dice "non posso confermare", mai "fatto".
- **Q9 (a):** "Libera spazio" propone tutto ciò che è al sicuro **tranne gli ultimi 30 giorni**, con i filtri "solo video" e "più vecchi di un anno"; ciò che non libera è elencato con i numeri e il perché. Se l'utente parte da una selezione della Libreria, i 30 giorni non si escludono (l'ha scelta lui).
- **Q6 (a):** HEIC, RAW e video si correggono solo nell'app (la copia del telefono non si riscrive): il foglio finisce con "Corretto solo nell'app" e lo dice.
- **Avviso forte** se il NAS non ha un backup esterno: tono d'allarme, primo della pagina e di nuovo nell'ultima conferma di "Libera subito" ("l'unica copia sarà sul NAS").
- **Parole semplici.** Nessun "hash", "SAF", "MediaStore", "EXIF"; un test (`leFrasiSonoSempliciENonHannoGergo`) lo controlla per la pagina Condivisioni.
- **Grafica (vetro adattivo, C):** solo componenti di `core-designsystem` e di Fluid, nessun colore o dimensione scritta a mano (`NoHandWrittenStyleTest`, `BackupStyleTest`); sui fogli alti il corpo scorre e **i pulsanti restano in vista** anche a 1,3x (prova dei bordi); nessun testo sotto il vetro poco leggibile, nessun controllo sovrapposto.

**Stati del §1.5 composti e provati**: Libera spazio (riepilogo, riepilogo vuoto, conta, guardiano, verifica, pronto, pronto definitivo, dialogo aperto, fatto, fatto-niente, un caso per ogni `FreeSpaceBlockedBy`: area ferma, NAS non raggiungibile, conferma rifiutata, non pronto, niente dialogo, scaduta); "Elimina solo dal telefono" (da liberare, non al sicuro, misto, tutti i consigli); Versioni (lista, lista con avviso, riepilogo, permesso, scambio, fatto, caricamento, non pronto, area comune, originale non al sicuro); Correggi (chiuso, tutto acceso, solo la data, toglie il luogo, ruota, prima al sicuro, permesso, scrittura, carica, fatto, solo nell'app, guasto, bloccato, non pronto); Da decidere (vuota, due, senza il tasto elimina); viewer (menu con e senza backup, con una sola versione, con "senza posizione", preparazione, pronto, condivisione ferma con e senza scelta, "Elimina…" con le versioni); Impostazioni (spenta, accesa, la riga).

**Test** (JVM; Robolectric con grafica nativa e Compose)
- `feature-backup`: macchina e ViewModel di Libera spazio con i fake di P0-B (`InMemoryFreeSpace`, `TestConfirmations`, `FakeBackupClock`) e una fake `PhoneSystemDialogs` (dialogo concesso, rifiutato, assente; rotazione a metà); i due gesti (600 ms, secondo pannello, scadenza, doppio tocco); le bozze di correzione (data, ora, coordinate, rotazione composta "prima lo specchio, poi i gradi"); i ViewModel di Versioni e Correggi (permesso di scrittura, rifiuto, piano cambiato, polling del caricamento); le schermate in ogni stato; `SheetShieldTest` (R7); la **prova dei bordi** (`FiveBLayoutSuite`, telefono 412 dp e tablet 1280 dp, a 1x e 1,3x: bersagli da 48 dp, dentro lo schermo, niente sopra altro, ogni controllo con un nome, pulsanti dei fogli alti in vista).
- `feature-viewer`: le voci (`BackupActionsTest`), i fogli (`BackupActionsSheetsTest`, anche a 1,3x), il viewer vero con le voci (`ViewerBackupMenuTest`: senza backup le voci non ci sono, ogni voce è una richiesta a `:app` per il media di adesso, "Condividi" non usa più il percorso di prima, un foglio fermo non condivide da solo).
- `feature-settings`: l'impostazione (default spento, vale subito, resta dopo un riavvio, un solo valore), il ViewModel, la pagina (interruttore con ruolo e stato per TalkBack, tocco, frasi, formati), la riga, la prova dei bordi su telefono e tablet.
- Gli screenshot (sotto) sono anche test: se una scena non compone, falliscono.

**Screenshot**: `docs/screenshots/5-P12/` (chiaro e scuro, telefono e tablet, testo 1,3x sulle schermate principali). Si rigenerano con `gradlew :feature-backup:recordRoborazziDebug --tests "dev.pampa.mycloudgallery.feature.backup.free.FiveB*Shots*"`, `gradlew :feature-viewer:recordRoborazziDebug --tests "dev.pampa.mycloudgallery.feature.viewer.menu.BackupActions*Screenshot*"` e `gradlew :feature-settings:recordRoborazziDebug --tests "dev.pampa.mycloudgallery.feature.settings.sharing.Sharing*Shots"`. Prefissi: `libera-spazio-*`, `foglio-libera-spazio-*`, `foglio-libera-subito-*`, `elimina-solo-telefono-*`, `versioni-*`, `correggi-*`, `da-decidere-*`, `viewer-*`, `impostazioni-condivisioni-*`, `impostazioni-voce-condivisioni-*`.

**Da provare sul campo** (solo `mcg-prova`, D12/D13; niente con il NAS vero e niente sul telefono vero finché P14 non lega le porte)
- I dialoghi veri di Android (cestino e cancellazione definitiva, a lotti; rifiuto; Android 10 senza `createTrashRequest`) con `PhoneSystemDialogs` legato.
- TalkBack su telefono: il nome letto di ogni riga e di ogni pulsante dei fogli (vedi "Limiti": la riga dell'engine tiene il testo in un nodo figlio) e l'ordine di lettura dei due pannelli di "Libera subito".
- La leggibilità dei fogli sul vetro vero (Robolectric non sfoca): contrasto del testo del foglio "Libera subito" e "Condivisione ferma" sopra una foto chiara e una scura.
- Il lavoro lungo di "Libera spazio" con lo schermo che si spegne e con la rotazione a metà verifica.

**Cosa deve legare P14 in `:app`**
- Hilt: `FreeSpace` (`SafeFreeSpace` + `RoomFreeSpaceDiary`, `BootCounter`, `FreeSpaceCatalog`), `VersionKeeper` (`DefaultVersionKeeper`), `Corrections` (`DefaultCorrections`), `PendingTasks` (`RoomPendingTasks`), `BackupClock` (monotonico), `PhoneSystemDialogs` (mappa `PhoneRef` → `MediaRef.Phone` e `PhoneMediaIntents.trashRequest/deleteRequest/writeRequest`, dietro la recinzione D13), `PhoneOnlyAssessor` (`SafeFreeSpace.assessPhoneOnly`), `PendingTaskActions` ("Elimina…" di feature-nas, poi `PendingTasks.complete`), `VersionActions`, `CorrectionPlaces` (facoltativo), `BackupMediaActions` (per `ViewerServices.backupActions`). `SharingSettings` è già fornita da feature-settings.
- Navigazione: le rotte `BackupScreen.FREE_SPACE` e `PENDING_TASKS`; la scheda "Da decidere" (`PendingTasksEntryCard`) nella pagina del backup; "Libera spazio" in Raccolte → Utility e nella selezione della Libreria (`FreeSpaceRoute(selection)`).
- `BackupMediaActions.perform` monta alla radice `VersionsSheetHost`, `CorrectionSheetHost`, `PhoneOnlySheetHost` o `FreeSpaceRoute(selection)`; "Condividi" va su `LocationFreeShare` di P11 (mappato su `BackupShareUi`, con `shareStopText` per i messaggi) e legge `SharingSettings.removeLocation`; `EverywhereSummary` viene da `EverywherePlan`; **le versioni precedenti devono entrare anche in `NasActionTarget.others`**, altrimenti il piano `Trash` della 4B non le contiene.
- Facoltativo: riusare `FreeSpaceFlow` in un lavoro lanciato dall'utente con la notifica "Pronto: N elementi, X GB".

**Modifiche richieste agli altri** (nessuna fatta)
- `core-backup/contract`: `FreeSpaceRun` dovrebbe avere `inDoubt` e `kept` (oggi la UI calcola `kept = pronti all'inizio − liberati − scartati dopo`) e `FreeSpaceExclusion` dovrebbe distinguere `NOT_MINE` e `BACKUP_NOT_ACTIVE` (oggi finiscono in "con un problema"). Se si vuole, vanno aggiunti insieme a `SafeFreeSpace` e ai suoi test: non l'ho fatto per non toccare il perimetro di P9.
- `core-backup/contract`: il significato di `PhoneWriteGrant` va scritto nel contratto: **un permesso che non copre le righe = dialogo rifiutato** (la UI manda `PhoneWriteGrant(emptySet())` e i keeper veri devono rispondere con un rifiuto senza scrivere niente, non con un guasto; il fake e il keeper vero devono comportarsi allo stesso modo, oggi la UI è provata solo con il fake).
- `COMMON_AREA_NOTICE` non ha un foglio nel contratto: oggi Correggi e Versioni **si fermano** con una nota ("nell'area comune la vedrebbero tutti") e non scrivono. Se si vuole permettere la correzione nell'area comune serve un passo di conferma nel contratto (proposta: `CorrectionReadiness.COMMON_AREA_NOTICE` + `Corrections.acknowledgeCommonArea`).
- Engine (non si tocca): il campo di testo `FluidTextField` non espone un nome a TalkBack (ho usato l'etichetta sopra), il suo tasto "svuota" è un bersaglio da 28 dp senza nome (spento con `showClearButton = false`), e la barra di `FluidScreen` ha il tasto "Indietro" e il tocco sul titolo alti **44 dp** (`ControlRowHeight`), sotto i 48 dp delle altre pagine nostre.
- Per `SecurityScreen` (feature-settings, non mio): la riga interruttore usa `Modifier.semantics(mergeDescendants = true) { role = Switch }` ma la riga dell'engine tiene il testo in un figlio che si fonde per conto suo: il nodo dell'interruttore resta **senza nome** per TalkBack. In `SharingScreen` ho aggiunto una `contentDescription` esplicita; la stessa riga andrebbe sistemata in `SecurityScreen` e in `PreviewQuality`.

**Limiti dichiarati e punti aperti**
- Robolectric non sfoca: il vetro dei fogli non si vede negli screenshot e la leggibilità vera è da guardare sul telefono (sopra). Nelle foto i fogli sono su una superficie piatta.
- Il foglio di Correggi, a 1,3x e con tutte le sezioni aperte, scorre dentro i 640 dp dell'engine; il corpo scorre e i pulsanti restano, ma una nota in fondo al foglio Versioni si taglia dietro i pulsanti finché non si scorre (`versioni-lista-*`).
- Nel menu ⋮ "Correggi data e luogo" è la voce più lunga e arriva a pochi pixel dall'icona (`viewer-menu-backup-*`): l'engine non manda a capo le voci.
- La pagina Libera spazio ha molte schede (avviso, riepilogo, tre scelte, quattro motivi, due pulsanti): pesa a colpo d'occhio su telefono (`libera-spazio-riepilogo-*`); i motivi di esclusione si potrebbero chiudere dietro un "Perché non tutti?" se all'utente pare troppo.
- Gli screenshot del tablet sono solo quelli principali (un PNG da 2560 px pesa un mega); il resto è provato dai test dei bordi sul tablet.
- In "Da decidere", "Elimina…" non elimina da sola: apre l'eliminazione di feature-nas (callback `PendingTaskActions`) e il compito si chiude solo dopo; "Tienilo" chiude il compito subito e dice che la domanda non torna.

## Fase 5B: P13, la prova delle 500 interruzioni della 5B (19/10)
Fonte: `docs/fase5-progetto.md` (§2.4, §2.5, D17-D22, §5.4 invarianti 12-14, riga P13 del §4.4, F43), il banco della 5A (P6, `docs/prova-500.md` Parte B) e i
servizi di P8 (versioni, scambi, correzioni), P3 (guardiano) e P9 ("Libera spazio"). Perimetro: `core-backup/src/test/.../backup/chaos/**`, `tools/chaos/**`,
`docs/prova-500.md` (Parte C). **Nessun codice di produzione toccato**: i tre difetti che la prova ha trovato (F1 e F3 in P8, F2 in P3) sono **dichiarati**, non
corretti. Documento di riferimento: `docs/prova-500.md` Parte C.

**Cosa c'è** (`core-backup/src/test/kotlin/dev/pampa/mycloudgallery/core/backup/chaos/`)
| File | Cosa |
|---|---|
| `BackupChaos5BPhase.kt` | la **fase 5B** di un giro: semina, da 3 a 5 passi (`EDIT`, `EDIT_TWICE`, `EDIT_WHILE_READ`, `REVERT`, `RESTORE`, `STAGED`, `CORRECT`, `FREE`, `RESUME`), eventi tra i passi, dialogo di sistema simulato (conferma, parziale, rifiuto, app uccisa), `checkBatch` (invariante 14), convergenza, rilettura finale |
| `BackupChaos5BWorld.kt` | `FiveBWorld`: i media del banco (`MediaRec`, cinque `FiveBCategory`), il PC che modifica o crea file, Samsung "Salva", "Ripristina originale", il MediaStore che si ricostruisce, il riavvio, il tempo, il marciume di un bit, la scansione (`rescan`); `KnownDefect` e `Backup5BConfig.avoid` |
| `BackupChaos5BCatalog.kt`, `BackupChaos5BWrappers.kt` | `ChaosMediaCatalog` (un catalogo solo per `VersionCatalog`, `FreeSpaceCatalog` e la parte del guardiano) e i decoratori dei colpi sui diari e sul catalogo (`ChaosVersionJournal`, `ChaosVersionCatalog`, `ChaosFreeDiary`, `ChaosFreeCatalog`, `ChaosRewriter`) |
| `BackupChaos5BStacks.kt` | `FiveBStackFactory` / `RealFiveBStack`: i servizi veri (`NasVersionEngine`, `DefaultVersionKeeper`, `DefaultCorrections`, `BackupPrivateCopyGuardian`, `SafeFreeSpace`) legati alle porte avvolte dai colpi |
| `BackupChaos5BVerifier.kt` | `FiveBVerifier` e `FiveBStateFile` (`# mcg-backup5b-state 1`): gli invarianti su fatti semplici, anche da soli su uno stato salvato |
| `BackupChaos5BMutants.kt` | i quattro difetti della 5B per `BackupChaosMutationTest` |
| `BackupChaosFaults.kt` (esteso) | `Primitive` con le 13 voci della 5B, `FaultKind.PC_EDIT`, `FaultMix.FIVE_B`, `RuleScope`, `NasTarget`, `ChaosStats.count` |
| `BackupAreaRunner.kt`, `BackupAreaGuard.kt`, `BackupNasIntegrationTest.kt` (estesi) | il runner contro il NAS vero con `FiveB {OFF, WITH, ONLY}`, il tetto `MCG_BACKUP_CAP`, il manifesto con `edit=`, `Versioni/` in sola lettura, `archiveVersion`/`restoreVersion` dietro la guardia |
| `ChaosKnownDefectsTest.kt`, `ChaosStaleScene.kt`, `StaleCatalogAfterPcEditTest.kt` | i tre difetti (oggi / giusto `@Ignore` / il banco li trova), la scena del catalogo che ritarda e le sue quattro domande |
| `FiveBVerifierTest.kt`, `ChaosMediaCatalogTest.kt` | il verificatore invariante per invariante, il catalogo del banco |

**Scelte**
- **La 5B è un'estensione del giro della 5A, non un banco a parte**: stesso mondo (NAS, telefono, orologio, cancello), stesso `Injector`, stessi decoratori dei colpi.
  La fase gira dopo il caricamento con un **seme suo** (`seed xor 0x5B5B5B5B5B5B`): i colpi del caricamento restano **quelli di prima, bit per bit**
  (`BackupChaosDeterminismTest`), quindi i giri rossi della 5A si rifanno con il loro seme. Sul NAS vero gira anche **da sola** (`only`): nessun caricamento prima.
- **«Stack di riferimento e vero»** (scelta da confermare): per la 5B **non esiste un esecutore di riferimento** alternativo (P0-B non l'ha scritto: i servizi di
  P8, P3 e P9 sono l'unica implementazione). I due stack del banco differiscono quindi per il **caricamento della 5A** (`InMemoryBackupEngine` / `SafeUploadEngine`)
  e per il seme della fase 5B (`fiveBSalt`, perché non facciano due volte la stessa fase); i servizi della 5B sono i veri su entrambi. Un riferimento della 5B sarebbe
  stato un secondo modello da tenere allineato senza altro scopo.
- **Il banco non salta un caso reale senza dirlo** (indicazione del coordinatore sul giro 122). I difetti noti sono voci di `KnownDefect`; il banco li evita solo
  se sono in `Backup5BConfig.avoid` (il default, perché `check.ps1` deve restare verde) e per ognuno `ChaosKnownDefectsTest` prova che **con l'evitamento spento il
  banco lo trova** (un giro preciso, rifatto dal suo seme). Il catalogo che ritarda dopo una modifica del PC di un'altra dimensione (giro 122) **non si evita**: si
  lascia accadere, si modella la scansione che lo sistema e si prova con lo scenario che il ritardo è innocuo (O1).
- **La scansione del NAS è un modello**: `FiveBWorld.rescan` fa quel che serve alle versioni (il telefono ritrovato o sparito, il ritorno a una versione nota, il
  file del PC come versione nuova, e per un media che il PC ha toccato la copia sparita dichiarata assente, D10). Gira prima della verifica finale. Lo scansionatore vero
  (`relocateNas` per `fileId`, i periodi di grazia) è fuori dal banco.
- **Un catalogo solo** (`ChaosMediaCatalog`): le tre viste che i servizi chiedono (versioni, «Libera spazio», guardiano) sullo **stesso** dato, sopra
  `InMemoryVersionCatalog` di P8. Fa solo le due cose che il catalogo vero fa e quello di P8 no: una riga tolta da Android **non è più presente**, e una rilettura il cui
  SHA-256 non torna **toglie la verifica** (E-B8). Le regole di sicurezza restano dei servizi.
- **L'invariante 14 si giudica nel momento in cui un lotto esce** (`checkBatch`), non dallo stato finale: dopo il dialogo il telefono e il NAS cambiano e la
  consegna non si ricostruisce. Il lotto è sbagliato se una riga non ha una copia privata riletta per intero nello stesso avvio (`bootCount`), da meno di 6 ore
  monotone, con il timbro, i byte e la riga del telefono di allora. Il resto (nessuna riga tolta senza traccia, `freed` con la riga ancora sul telefono, `kept`
  con la riga tolta, `in_dialog` a lavoro finito) è contabilità sullo stato finale.
- **`Invariant.CONTRACT` passa da 12 a 15**: nel §5.4 i numeri 12-14 sono quelli della 5B. Cambia solo il numero stampato (e nelle note di uno stato salvato).
- **Le primitive nuove del NAS passano dalle decorazioni di sempre**: `ARCHIVE_VERSION` e `RESTORE_VERSION` hanno tutti i colpi del NAS e due del mondo: `PC_EDIT` (il PC
  modifica il file proprio prima della rinomina, il timbro non torna) e `PC_FILE` (un file al nome di destinazione). Le 5A delegavano le due rinomine senza colpi (nota di P0-B).
- **La morte durante il dialogo di sistema** (`PHONE_DIALOG`) lascia l'esito `Unknown`: lo risolve `settle()` con la lettura del telefono, come prevede P9.
- **Sul NAS vero** un colpo per passo, nessun «il PC modifica un file» né orologio che avanza né bit che marcisce (`NasCaps`: il NAS vero non fa passare le ore e la cache
  dei timbri non li vedrebbe a metà), rilettura finale sempre completa. Tutto sotto `mcg-it/<giro>/` dietro la guardia; `Versioni/` si legge soltanto dal verificatore.
- **`MCG_BACKUP_CAP`**: il file delle credenziali può avere `backup.rounds=400`; un giro breve da 20 non lo deve mai diventare. `area.ps1` lo imposta.

**Risultati** (19/10/2026)
| Banco | Giri | Interruzioni | Violazioni | Durata |
|---|---|---|---|---|
| JVM, NAS finto, stack di riferimento | 2.000 | 7.859 (3.778 nella fase 5B) | **0** | ~41 s |
| JVM, NAS finto, motore vero | 2.000 | 7.579 (3.726 nella 5B) | **0** | ~41 s |
| JVM, simulatore SMB (vere `SmbNasFileOps`/`SmbNasBackupOps`), riferimento / vero | 600 + 600 | 2.418 / 2.376 (1.145 / 1.154 nella 5B) | **0** | ~25 s |
| NAS vero, area di prova, solo 5B: giro breve | 32 | 20 | **0** | 155 s |
| NAS vero, area di prova, solo 5B: giro da 400 (l'unico) | 506 | **401** | **0** | 47 min 56 s |
- **Copertura per primitiva** sul NAS vero (chiamate; colpi prima / dopo): `ARCHIVE_VERSION` 796 (8 / 9), `RESTORE_VERSION` 198 (5 / 2), `VERSION_UPDATE` 6.696 (39 / 23),
  `CATALOG_RELOCATE` 1.827 (5 / 14), `FREE_MARK_VERIFIED` 1.387 (5 / 5), `PHONE_DIALOG` 79 morti; non colpite in quel lancio: `READ`, `DELETE_OWN_TEMP`, `PHONE_REWRITE`
  (colpite a decine o centinaia di volte nel banco JVM: `ARCHIVE_VERSION` 2.729 chiamate con 283 / 35 colpi, `RESTORE_VERSION` 784 con 36 / 16, `PHONE_REWRITE` 414, `PHONE_DIALOG` 270 morti).
- **Mutazioni** (500 giri sul NAS finto, `BackupChaosMutationTest`): archiviare senza il timbro 7 giri rossi (invariante 7), sostituire invece di rinominare 49 (3, 6, 7, 9, 12, 13),
  liberare con la verifica scaduta 24 (14), uno scambio che lascia vuoto il solito percorso 203 (7 e 12); lo stack sano verde; i quattro difetti della 5A restano rossi.
- **Modulo `core-backup`**: 511 test, 0 falliti, 6 saltati (3 del NAS vero, 3 `@Ignore` dei difetti noti), ~4 minuti con `--max-workers=2`.
- **Osservazione sul NAS vero**: `NasSession chiusura lenta: 60000 ms` (101 volte nel giro da 400): la chiusura della sessione SMB dopo una morte simulata aspetta il limite di 60 s
  (`CLOSE_WAIT_MS` in `SmbNasSessionManager`, codice della 4B). Su un thread a parte: non rallenta il giro né viola un invariante; la causa non è stata indagata.
- **Area di prova non pulita**: restano `mcg-it/20261019-181432/` e `mcg-it/20261019-181746/` nelle share di prova; le toglie `NasIntegrationSuiteTest` di core-nas al lancio dopo.

**Difetti trovati** (non corretti: li correggono i pacchetti che hanno scritto il codice; per ognuno: test «oggi» verde, test «giusto» `@Ignore`, il banco lo trova se non lo evita)
- **F1 (P8, `NasVersionEngine.refreshPhone`)**: un lavoro `VERSION` pianificato da una riga del telefono resta `PLANNED` **per sempre** se il MediaStore si ricostruisce
  (`_ID` rinumerati, E-P12) e la foto cambia un'altra volta prima che il lavoro la legga: `refreshPhone` aspetta una riga che non tornerà (`Step.Wait`, mai `GONE`).
  Conseguenze: «Ripristina originale» `NOT_AVAILABLE` per quel media, «Libera spazio» lo esclude (c'è un lavoro aperto: D21), con un video a metà anche il temporaneo resta. Nessun byte perso.
  Rimedio: se il catalogo non ha nessuna copia presente del telefono per la versione del lavoro, `gone(job)`.
- **F2 (P3, `BackupPrivateCopyGuardian.link`, innescato da P8)**: elemento del guardiano `PUBLISHED` con la copia già nel catalogo ma non `LINKED` (processo morto), poi una versione
  nuova (correzione, `planStaged`) sposta quella copia in `Versioni/`: il guardiano rifà la copia e crea un **doppione** (`nome (2)`, invariante 2). Rimedio: riprendendo, se la
  versione ha già una copia privata verificata (anche in `Versioni/`) chiudere l'elemento; o far partire le versioni solo con l'elemento del guardiano `LINKED`.
- **F3 (P8, `NasVersionEngine.restore` / `archive` per identità)**: dopo una rinomina la cui risposta è andata persa, il lavoro riconosce il suo file per `fileId` e **dimensione**
  (`sameIdentity`). Se il PC lo modifica sul posto **conservando la dimensione** prima della ripresa, il lavoro lo prende per il suo, scrive nel catalogo il **timbro trovato**
  (`restored(job, src, st.stamp)`, `archived(job, st.stamp)`) e **tiene la verifica**: il catalogo dice «al sicuro» un originale con **byte diversi**, **la scansione non lo corregge**
  (il timbro del catalogo è già quello del file) e **una versione nuova archivia i byte del PC come «(originale)»**. «Libera spazio» non ne risente (la verifica sul momento rilegge e
  confronta con l'impronta). Rara (morte del processo fra la rinomina e la ripresa **e** modifica del PC della stessa dimensione), ma il banco l'ha trovata da solo (seme 777, giro 1306).
  Rimedio: dopo un ritrovamento per identità scrivere nel catalogo il timbro **atteso** (`src.stamp`), non quello trovato; o confrontare anche la data; o rileggere l'impronta.
- **O1, non un difetto**: stesso scenario di F3 con una modifica del PC di **un'altra dimensione** (giro 122): `sameIdentity` non riconosce il file, il lavoro finisce `FAILED`/`USUAL_PATH_TAKEN`
  e il catalogo ritarda (dice l'originale in `Versioni/`, dove non c'è più) finché la scansione non lo dichiara assente. Le quattro domande (al sicuro con byte diversi? versione persa o
  sovrascritta? «Libera spazio» che toglie? uno scambio su dati vecchi?) hanno risposta **no**, con i test in `StaleCatalogAfterPcEditTest`; il segno «al sicuro» resta in ritardo fino alla scansione,
  come per ogni cambiamento del PC fra due scansioni (D10).

**Modifiche richieste ai contratti degli altri**
- **P8**: F1 e F3 (sopra); `BackupItem.flow` ignora `archivesCurrent` (già segnalato): il banco usa il cammino del lavoro (`VersionJob.flow`) per giudicare le transizioni.
- **P3 / P8**: F2 (sopra).
- **P0-B / contratti**: `RestoreOutcome` non distingue «scambio fatto ma telefono non riscritto» (già segnalato da P8: il banco lo vede come `Queued` e ripianifica).
- **P14 (`:app`)**: dopo **ogni** lettura del telefono va chiamato `SafeFreeSpace.settle()` (P9 l'ha scritto): il banco lo fa e senza di esso un esito `Unknown` (app uccisa
  durante il dialogo di sistema) resterebbe `in_dialog` o `in_doubt` per sempre.
- **Nessuna modifica al codice di produzione** per far passare il banco.

**Da provare sul campo** (F43, solo `mcg-prova` con D13; il banco non può)
- `s25.ps1 -Mode backup -Step seed`, primo backup, **`-Step edit -Edits 20`**, «Fai ora» nell'app, poi `-Step interrupt -Verify -AllowFreed` con `kill -9` e Wi-Fi spento durante il caricamento delle versioni.
- «Ripristina originale» da Samsung Gallery e dall'app, con `kill -9` a metà dello scambio (il solito percorso dal PC non deve restare vuoto a lavoro finito).
- «Libera spazio» con il **dialogo di sistema vero** (`createTrashRequest`), `kill -9` dell'app mentre è aperto e Wi-Fi spento dopo la verifica sul momento; poi la lettura del telefono risolve gli esiti.
- Il PC che modifica un file mentre l'app lo archivia (file aperto da Esplora risorse: `SOURCE_CHANGED`), e il caso di F3 (modificare il file con un editor che ne conserva la dimensione subito dopo uno scambio interrotto).
- Il tempo: le 6 ore e il riavvio del telefono (le ore non passano nel banco del NAS vero).

**Limiti dichiarati**: il bit che marcisce con lo stesso timbro fra la verifica sul momento e il dialogo non si può provare (D21, L39); il dialogo di sistema, la
decodifica delle immagini corrette, Room e lo scansionatore vero del NAS sono fuori dal banco (`docs/prova-500.md` §C10).

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

## Correzioni funzionali: onboarding e famiglia (19/10)
Le voci ON-1 … ON-12c della verifica funzionale (`docs/review/funzionale-fasi-1-5a.md`, ognuna con "Stato: corretta" e i suoi
test). Ogni difetto ha un test che falliva prima: in `core-family` i test del coordinatore vero sono stati visti rossi prima della
correzione; quelli del ViewModel sono stati visti rossi (12 su 13) rimettendo per una prova i comportamenti di prima. I flussi di
famiglia che erano provati solo con i finti ora hanno un test sul grafo vero (`VerificaFunzionaleFamigliaTest`: ingresso senza QR
con il ViewModel vero, richiesta scaduta e rifatta, revoca e rientro, reset e rientro, cartelle firmate, riavvio dopo
l'azzeramento).

**Decisioni**
1. **ON-6, "via dal PC" (decisione dell'utente).** Senza admin, kit e codice nessun familiare azzera la famiglia dall'app: resta un
   potere dell'admin. L'app spiega come farlo dal PC (cancellare solo `.mycloudgallery\family` dell'area comune, con il percorso
   vero; poi "Crea la famiglia" e "Rientra con un QR nuovo" sugli altri): nel recupero dell'onboarding (nessun kit, codice che
   non apre), nel recupero delle Impostazioni e in Impostazioni → Famiglia. Si perdono la famiglia e i dispositivi registrati,
   le foto no. Dettagli in `docs/famiglia-dispositivi.md` §5.
2. **ON-4: il dismesso si comporta come il revocato.** Niente sola lettura (la Libreria esiste solo con la famiglia pronta): una
   pagina dice cosa e' successo e offre "Configura di nuovo".
3. **ON-4 e ON-5: "Configura di nuovo" e "Rientra con un QR nuovo" sono lo stesso gesto** (`OnboardingCoordinator.startOver`):
   via chiave del dispositivo, chiave di famiglia, password e account del NAS, stato fidato e avanzamento; restano foto, nascosti,
   preferiti e miniature. Dalle Impostazioni parte solo dopo un foglio di conferma, con un avviso in piu' su un admin.
4. **ON-2: la richiesta scaduta si rifa' dalla pagina d'attesa** ("Rifai la richiesta"). La finestra di 15 minuti resta contata
   dalla prima vista dell'admin (M2 della revisione 1.4): spostarla all'apertura della pagina l'avrebbe allungata.
5. **ON-10: niente Libreria in sola lettura durante l'attesa.** Tolte le frasi che la promettevano.
6. **ON-11: dopo un azzeramento "Configura di nuovo" riavvia il processo** invece di ricreare `GalleryGraph`: la galleria fermata
   non riparte nello stesso processo (R7, `GalleryShutdownTest`), e le porte di Hilt puntano a oggetti nati con lei.
7. **ON-1: l'ingresso senza QR chiede anche le due aree** (il registro sta nell'area comune) e "e' la rete di casa?"; la pagina
   "Configura un familiare" dell'admin mostra, sotto lo stesso `FLAG_SECURE` del QR, i dati da leggere a voce (indirizzo,
   account, aree, id e impronta della famiglia). Il QR "stato della famiglia" riempie id e impronta; se id o impronta scritti non
   coincidono con quelli del QR, l'ingresso non parte (`INVITE_OTHER_FAMILY`).
8. **ON-7: a onboarding finito si riapre anche un passo locale gia' fatto** (6-11 e 13): il controllo di WireGuard e la checklist
   dalle Impostazioni. Mai un passo del NAS.

**Modifiche ai contratti e al codice condiviso**
- `core-family`: `OnboardingCoordinator.savedNas()`, `recoveryPending()`, `startOver()` e `resume` allargato (ON-3, ON-4, ON-5,
  ON-7); `SavedNas` con `familyFolderForWindows()` (ON-6); `RegistryService.updateCommonExclusions` con
  `RegistryWriter.updateCommonExclusions` (ON-8); `NasError.asOnboardingProblem()` (ON-9); `ManualInvite.create(…,
  minRegistryVersion)` (ON-1); `TypedFingerprint.normalize` con gli alias di Crockford (ON-12b); `OnboardingRules`, le regole dei
  passi in un posto solo, usate dal coordinatore vero e da quello finto (ON-12c compreso: con l'admin recuperato percorso e passi
  2-4 sono fissati).
- `feature-onboarding`: pagine "fuori dalla famiglia" e "Ricominciare da zero"; `AwaitingApprovalScreen(onRenew)`,
  `OnboardingRenewRoute` e `onboardingRenewal()`; il ViewModel riceve anche `RegistryService`.
- `feature-settings`: "Rientra con un QR nuovo", "Ricominciare da zero", la checklist del backup solo dove il passo 13 c'e',
  i dati per l'ingresso senza QR nella pagina dell'invito.
- `:app`: solo navigazione e riavvio. La pagina d'attesa registra la sola rotta del rinnovo (non l'onboarding intero: F5 della
  revisione 2 resta chiusa, `EntryHardeningTest` lo verifica); `GraphRootPorts` riavvia il processo dopo "Configura di nuovo"
  (`ProcessRestart`); `AppGraph.forgetCompletedWipe()` dice se ha dimenticato un azzeramento.

**Sicurezza: cosa e' stato toccato, e perche' non si indebolisce**
- *Invito manuale*: `InviteRules` non pretende piu' la versione minima 0 senza QR; accetta quella del QR "stato della famiglia"
  (da 0 in su). Alzarla toglie rollback possibili, non ne aggiunge. Id e impronta passano da `Base32.normalizeHumanInput`, come
  gia' faceva `TrustAnchor.create`: la forma canonica e i controlli dell'ancora restano quelli di prima.
- *Impronta digitata (ancora del recupero)*: gli alias O → 0, I/L → 1 servono solo al confronto; sono fuori dall'alfabeto
  dell'impronta, quindi un'impronta vera non cambia e due impronte diverse non diventano uguali (test con 200 coppie).
- *Firma delle esclusioni*: passa da `RegistryWriter.adminContext` come ogni azione dell'admin (dispositivo approvato, chiave di
  famiglia dalla cassaforte a telefono sbloccato, M3) e da un gesto locale (E-S11).
- *"Configura di nuovo"*: toglie solo dati locali e chiavi di questo dispositivo, dopo un gesto confermato; nessuna scrittura sul
  NAS. La chiave di famiglia di un admin che esce si cancella (un dispositivo fuori dalla famiglia non deve poterne firmare il
  registro); il foglio lo dice prima.
- *Rinnovo della richiesta*: lo stesso di `DefaultJoinFlow` (solo "in attesa", chiave nuova); finestra M2 invariata; dalla pagina
  d'attesa si raggiunge solo la rotta del rinnovo.
- *Admin recuperato*: percorso e aree fissati come dopo la fondazione (ON-12c): il registro non si legge da una share scelta dopo.
- Nessuna voce chiusa da `docs/review/2-fase2.md` o `docs/review/1.4-core-trust.md` e' stata riaperta.

**Fake allineati al vero**: `FakeOnboardingCoordinator` (regole vere dei passi), `FakeJoinFlow` (controlli di `start`),
`FakeAdminRecovery` (account, area, profilo), `FakeRegistryService` (stato che cambia rileggendo, finestra di `ApprovalWindow`).
Confronti: `FakeCoordinatorParityTest`, `FakeJoinFlowParityTest`, `VerificaFunzionaleRegistroTest`.

9. **ON-12c: fissati indirizzo, account e aree, "indietro" non ci torna piu'.** Prima, dopo la fondazione si tornava fino alle aree
   (per rivederle) ma non si potevano confermare, e da li' non c'era un "avanti": la stessa trappola sarebbe toccata all'admin
   recuperato. Ora "indietro" si ferma al primo passo che si puo' rifare (per il fondatore le cartelle).

**Residui**
- L'ingresso con lo "stato della famiglia" alza la versione minima ma non confronta l'impronta del contenuto della stessa versione
  (lo fa solo il recupero): una versione uguale con un altro contenuto sarebbe comunque firmata dalla chiave della famiglia.
- Il riavvio del processo (ON-11) e le pagine nuove vanno provati sul telefono (vedi `docs/famiglia-dispositivi.md` §9, prove 8 e 9).

## Correzioni funzionali: rete e VPN (19/10)
Le voci RE-1 … RE-11 della verifica funzionale (`docs/review/funzionale-fasi-1-5a.md`, ognuna con "Stato: corretta" e i suoi test). Come
per ON, ogni difetto ha un test che prima falliva (le prove "viste rosse" sono nominate nel documento della verifica). I flussi che erano
provati solo con i finti (processo freddo, sonda, limiti di Android, password mancante, indirizzo firmato) ora hanno un test sul grafo
vero (`VerificaFunzionaleReteTest`, `LanConstraintsTest`).

**Decisioni**
1. **RE-1: il permesso di WireGuard si chiede da un tocco, mai da solo.** `RequestPermission` con il dialogo di sistema dal tasto «Consenti
   il controllo» (passo VPN e Impostazioni → Rete e VPN). `VpnController.refresh()` rilegge la capacità, che da sola non cambiava; il passo
   VPN e la Rete rileggono anche a ogni ritorno in primo piano (permesso dato dalla scheda dell'app). Un rifiuto resta visibile con «Riprova a
   consentire»; quando Android non mostra piu' il dialogo (`shouldShowRequestPermissionRationale` falso dopo un rifiuto) il tasto diventa
   «Apri le impostazioni dell'app». Niente nei lavori e niente all'avvio: e' un permesso di un'altra app e lo concede l'utente guardando.
2. **RE-2: riattivare vuol dire ricominciare da zero.** `DefaultVpnAutomation` azzera ultimo tentativo, insuccessi e attesa quando
   vede sparire la sospensione (togliendola con «Riattiva il controllo», salvando di nuovo la scelta o con una prova a mano riuscita). Una
   prova riuscita toglie `NOT_CONTROLLABLE`; `LOCKDOWN_SUSPECTED` solo lo spegnimento riuscito con la rete che resta (altrimenti un blocco
   vero verrebbe riattivato da una prova qualunque).
3. **RE-3: un solo punto per aspettare la rete.** `AppGraph.stageRules()` e' il motore delle regole preceduto da `NetworkSettler.settle()` (fino
   a 5 s che la rete si veda, poi una sonda TCP 445 se non si sa che il NAS risponda), una volta per tappa. Lo usano tutti i lavori
   (`NasStages`, `BackupStages`, `TrustSyncRun` con la sonda sempre). Il lavoro che parte a processo freddo non finisce piu' "riuscito" a
   vuoto per una rete che compare 300 ms dopo.
4. **RE-4: l'anello della Diagnostica si scrive da un posto solo.** `DiagnosticsRecorder` (`feature-diagnostics`) riceve gli eventi del
   :app (esiti dei lavori, errori inattesi, stop di Android, sessione NAS, problemi del backup, sospensione VPN, avvio fallito) e scrive
   solo codici e numeri. Per non riempire l'anello ripete lo stesso esito al massimo ogni 6 ore (10 minuti per gli errori del NAS e per gli
   stop), e un giro a vuoto non scrive niente. Il testo delle righe dei lavori nasce nello schermo (`RecentEventsContributor`), mai
   nell'esportazione.
5. **RE-5: l'indirizzo e l'impronta di casa si firmano, ma solo dall'admin, e la pagina dice dove valgono.** Ordine: salvataggio locale,
   sessione invalidata, poi la firma sul NAS al nuovo indirizzo. Se la firma non riesce il cambio resta locale e c'e' «Riprova»; il non
   admin cambia solo per se' e la pagina lo dice («gli altri telefoni li aggiorna l'admin»). I telefoni gia' dentro la famiglia **non**
   adottano da soli un indirizzo firmato (lo legge solo chi entra, `DefaultJoinFlow`): e' un cambio di configurazione locale che si decide
   con l'utente, non in questa correzione.
6. **RE-6: l'esito della sonda si dimentica quando la sessione risponde.** `ObservedReachabilityProbe` si azzera anche con la sessione pronta
   o in risveglio, e all'ingresso in primo piano `ProcessWatcher` fa una sonda sola se non si sa che il NAS risponda.
7. **RE-7: i limiti di Android arrivano alle regole da quello che Android fa davvero.** `AndroidLimitsTracker` sostituisce `NoAndroidLimits`:
   uno stop per quota, bucket sceso o sfondo limitato (`STOP_REASON_QUOTA`, `APP_STANDBY`, `BACKGROUND_RESTRICTION`) segna la quota esaurita per
   un'ora o finche' l'app non torna in primo piano. I budget dei servizi in primo piano restano spenti (non usiamo quei tipi) e gli stop dei
   lavori dell'utente (UIDT, fuori quota) non si registrano: non toccati i `JobService` dell'altra squadra. Il motivo dello stop
   (`ListenableWorker.getStopReason`) esiste da Android 12 (API 31): su Android 10 e 11 non si sa e non si registra niente (`StopReports.reasonOf`).
8. **RE-8: la pagina «Quando lavora l'app» scrive nelle stesse `RuleSettings` dell'onboarding.** Il preset ha lo stesso effetto del passo
   8 (preset e fascia notte derivata dall'orario del NAS), tetto dei dati mobili e roaming nella stessa pagina; il tetto Wi-Fi non cambia mai
   (resta quello del preset). Le anteprime HQ restano alla pagina delle anteprime.
9. **RE-9: password mancante = nessuna connessione, nessun tentativo.** `SmbNasSessionManager` ricorda l'account senza password e rifiuta
   ogni connessione con `CredentialsMissing` finche' il Keystore non la restituisce (rilegge in locale, quindi vale per qualunque via con cui
   la password viene salvata); un guasto passeggero non conta come "mancante". Niente autenticazioni a vuoto, quindi niente rischio di bloccare
   l'account sul NAS. L'indicatore dice «Serve la password del NAS» e la notifica parte una volta sola.
10. **RE-10: «Ripeti il riconoscimento» e le azioni della VPN portano a Impostazioni → Rete e VPN.** `diagnosticsRouteOf` e' una funzione pura,
    provata senza interfaccia.
11. **RE-11: i lavori chiedono una rete, non internet.** `LanConstraints` (una richiesta con Wi-Fi, Ethernet e cellulare, senza `INTERNET` e
    senza `VALIDATED`) per i lavori periodici, immediati e a catena di WorkManager e per i `JobInfo` dell'utente (UIDT). Se la rete serve
    davvero e se e' casa lo decide il motore delle regole dentro il lavoro, con il cancello di rete, non il vincolo.

**Modifiche ai contratti e al codice condiviso**
- `core-network`: `VpnController.refresh()`, `WireGuardPermission` (nome del permesso, `outcomeOf`) e `ControlPermissionOutcome`;
  `DefaultVpnAutomation` ricomincia da zero dopo una sospensione; azioni della Diagnostica `RECAPTURE_HOME` e `OPEN_VPN_CONTROL`.
- `core-nas`: `SmbNasSessionManager` rifiuta le connessioni finche' la password manca (RE-9).
- `core-family`: `RegistryService.updateNasAddress` e `updateHomeUla`, con `RegistryWriter.amendConfig` (la stessa strada di
  `updateCommonExclusions`), `FamilyConfigFactory.hostOf(ip)`.
- `feature-settings`: `NetworkViewModel` (registro, sospensione, permesso, ambito del salvataggio), `AddressViewModel` (registro, ambito),
  pagina `rules` (`RulesChoices`, `RulesViewModel`), riga «Quando lavora l'app»; `feature-onboarding`: solo il permesso nel passo VPN.
- `feature-diagnostics`: `DiagnosticsRecorder`, i codici e il catalogo dei lavori (`EVT_JOB_RESULT`, `EVT_JOB_FAILED`, `EVT_START_FAILED`), le
  etichette delle azioni di rete.
- `:app`: `work/LanConstraints`, `work/StopReports` (`AndroidLimitsTracker`), `graph/StageSupport` (`NetworkSettler`, `SettledStageRules`),
  `AppGraph` (`stageRules()`, registratore di eventi, `diagnostics` come parametro), `GlobalStatus` (password mancante), `ProcessWatcher`
  (notifica della password, primo piano), `AppNavHost` (rotte della Diagnostica).

**Sicurezza: cosa e' stato toccato, e perche' non si indebolisce**
- *Registro firmato (RE-5)*: le due azioni nuove passano da `RegistryWriter.adminContext` come ogni azione dell'admin (dispositivo
  approvato, chiave di famiglia nella cassaforte a telefono sbloccato, gesto locale). Nessun controllo di fiducia e' stato toccato:
  `FamilyAuthority.updateConfig` e `commit` sono quelli di prima. Un indirizzo non valido o un'impronta senza un /48 ULA valido non firmano
  niente; la stessa configurazione non firma una versione nuova.
- *Sessione NAS (RE-9)*: piu' stretta, mai piu' larga: meno connessioni, nessuna autenticazione senza password. L'interruttore di sicurezza non
  si apre (non c'e' stato un tentativo).
- *Anello della Diagnostica (RE-4)*: solo `DiagCode`, gravita' e `ExportValue`; i nomi dei lavori vengono da un enum fisso, mai da
  nomi, percorsi, share, account o indirizzi.
- *Vincoli di rete (RE-11)*: il lavoro puo' partire su una rete non validata o non riconosciuta; il divieto di parlare con una rete non
  riconosciuta resta nel cancello (`NasRouteGate`) e nelle regole.
- *Permesso di WireGuard (RE-1)*: nessun permesso nuovo (era nel manifest). Si chiede solo da un tocco; la VPN gestita non e' un
  prerequisito della sicurezza (la sospensione e il blocco della VPN restano come prima). `docs/sicurezza.md`, §4-bis e registro, aggiornati.
- Nessuna voce chiusa da `docs/review/2-fase2.md` o `docs/review/1.4-core-trust.md` e' stata riaperta.

**Fake allineati al vero**: `FakeVpnController` (la capacita' cambia solo rileggendo: `capabilityOnRefresh`, `refreshes`), `FakeRegistryService`
(`addressUpdates`, `homeUlaUpdates`), `InMemoryRuleStore` (impostazioni vere dei test di Impostazioni), `ObservedReachabilityProbe` vera nei test
di `RootViewModelTest` al posto del finto che non falliva mai. Confronti: `WireGuardPermissionTest` (fake/vero), `VerificaFunzionaleRegistroTest`,
`VerificaFunzionaleReteTest`.

**Residui**
- La riga «Quando fa il backup» della pagina Backup (`feature-backup`, fuori perimetro) non ha ancora l'azione: puo' aprire la pagina nuova
  `SettingsRulesRoute`.
- Gli stop dei lavori dell'utente (UIDT) non si registrano nell'anello: servirebbe `onStopJob` nei `JobService` (`NasOpsJobService` e quello
  del backup, dell'altra squadra).
- Un familiare non admin che cambia l'indirizzo lo cambia solo per se'; l'adozione automatica dell'indirizzo firmato sui telefoni gia'
  nella famiglia non c'e' (da decidere con l'utente: oggi l'indirizzo nuovo lo prende chi entra con un QR nuovo).
- Da provare sul telefono: il dialogo di `CONTROL_TUNNELS` (`dumpsys package` deve dare `granted=true` dopo il tocco; aperta M10: con
  WireGuard installato dopo l'app il dialogo potrebbe non comparire), «Serve la password» una volta sola dopo l'invalidazione della chiave,
  un lavoro notturno a processo freddo, il router senza WAN, e gli stop per quota (vedi `docs/review/funzionale-fasi-1-5a.md`, prove 4, 8, 14 e 15).

## Correzioni funzionali: Libreria e NAS (19/10)
Le voci GA-1 … GA-8 e NA-1 … NA-12 della verifica funzionale (`docs/review/funzionale-fasi-1-5a.md`, ognuna con il suo "Stato" e i
suoi test). Ogni difetto ha un test che falliva prima: sono stati visti rossi, rimettendo per una prova il comportamento di prima, i
test di GA-2, GA-3, GA-5, GA-6, GA-8 e quelli di NA-1 … NA-9 e NA-12 (coda, ripresa, cartelle, franchigia, prima scansione, piede,
congelamenti a freddo, battito, capsula, lavoro a freddo). GA-1, GA-4, NA-10 e NA-11 aggiungono parti che prima non c'erano (la vista
viva dei Nascosti, il ricordo su file, l'esclusione e il gesto dell'ora): la prova è sul grafo vero e, per i finti, il test di parità.
Le voci che erano "plausibili" sono state provate: GA-4, GA-5, GA-6, NA-7 e NA-12 **confermate**; GA-7 **non confermata** (non
dimostrabile fuori dal telefono, vedi sotto).

**Decisioni**
1. **GA-8: dalla selezione della Libreria non si fa niente sul NAS.** Condividi ed Elimina dal telefono toccano solo i media che hanno
   una copia sul telefono (`LibraryCommands.onPhone`); su una selezione tutta sul NAS dicono che non si può e come fare (aprirli e
   "Scarica sul telefono"; per toglierli dal NAS, uno per uno dal viewer o da Cartelle NAS), su una mista fanno la parte del telefono
   e dicono quanti sono rimasti fuori. Cestino e download del NAS hanno le loro conferme per file: non si aggirano con un gesto sul
   gruppo.
2. **GA-7 non confermata.** Se `loadThumbnail` di un elemento nel cestino riesca lo decide il MediaProvider; nell'SDK 37 non esiste un
   `setIncludeTrashed` pubblico, quindi non c'è nessun interruttore da girare. Nessuna correzione al buio: da guardare nella prova F15.
3. **GA-4: il ricordo "ho già chiesto il permesso" sta su un file** (solo i nomi dei permessi), non in `remember`: senza, il primo tocco
   dopo una rotazione o un riavvio lanciava una richiesta che Android scarta in silenzio.
4. **NA-1: l'operazione nuova entra subito nel diario e si vede "In coda"**; parte quando il lucchetto dell'esecuzione è libero, in
   ordine di arrivo. Il finto di riferimento fa lo stesso.
5. **NA-4: "Ho controllato, continua" si ricorda fra i processi, ma vale una volta sola.** La franchigia (i conteggi visti) sta in un file
   sotto `replica/`; la usa e la toglie il primo giro completo dopo il gesto **qualunque ne sia l'esito** (prima restava anche dopo un
   nuovo congelamento, e valeva per quelli dopo) e scade dopo 7 giorni di ora del NAS. Il gesto chiede subito la verifica.
6. **NA-7: i congelamenti salvati si pubblicano prima che il motore del NAS sia visibile** (non quando il guardiano arriva a leggerli).
7. **NA-2: la ripresa dal diario si chiede per ogni operazione rimasta aperta**, lunga o breve, e quando passa ciò che la teneva ferma
   (share scongelata, "Riattiva le scritture", ora del NAS accettata).
8. **NA-12: una tappa che finisce il tempo continua subito** (`NasOpsJobEnd.Continue`, lavoro nuovo), invece di farsi rimandare col
   ritardo esponenziale di JobScheduler; il ritardo progressivo resta per NAS non raggiungibile e scritture ferme. Il lavoro, in un
   processo appena nato, avvia il NAS e lo aspetta: "non pronto con lavoro nel diario" è "il NAS non risponde", mai "fatto".
9. **NA-10: le esclusioni dell'area privata si impostano da Cartelle NAS** («Escludi dalla Libreria» / «Includi nella Libreria» sulle
   cartelle scelte), solo nell'area privata (quelle dell'area comune sono firmate dalla famiglia, ON-8). Non cambia niente sul NAS.
10. **NA-11: il gesto "L'ora del NAS è giusta, continua" sta in "Operazioni in corso"**, con un foglio di conferma, non in Diagnostica
    (era in perimetro dell'altra squadra). La voce di Diagnostica `INDEX_CLOCK_JUMP` resta solo testo: può rimandare a questa pagina.
11. **NA-9: la capsula del NAS apre la Diagnostica**, quella della rete resta con la sua azione.

**Modifiche ai contratti e al codice condiviso**
- `core-imaging`: `ThumbnailLoader.resume()` (GA-3). `core-mediastore`: `PhoneSyncConfig.foregroundMetaPause`, ripresa della sorgente (GA-5, GA-6).
- `core-nasops`: `NasOpsEngine.start` in due tempi (registrazione / esecuzione), `NasFolderService.observe` che rilegge e pubblica per
  numero di lettura (NA-1, NA-3).
- `core-nasmedia`: `FreezeAcceptance` e `FreezeAcceptances` (+ `InMemoryFreezeAcceptances`), `StoreFreezeState` con la franchigia
  persistita e consumata, `NasScanStatus.Scanning.first` (NA-4, NA-6).
- `feature-library`: `LibraryCommands.onPhone`, tre avvisi nuovi, il `BackHandler` della selezione (GA-2, GA-8). `feature-nas`:
  `NasAreas.privateExclusions`/`setPrivateExclusions`/`clockJumpMs`/`acceptClockJump` (di serie vuoti), "Aggiorna", "Escludi dalla
  Libreria", la scheda e il foglio dell'ora del NAS (NA-3, NA-10, NA-11).
- `:app`: `gallery/CatalogPorts` (vista viva dei Nascosti), `main/PermissionAsks`, `nas/FileFreezeAcceptances`, `nas/ColdResume`,
  `NasGraph`/`NasOrganizer` (coda, ripresa, congelamenti, esclusioni, ora), `MainScaffold`/`AppNavHost` (una riga), e in `work/`:
  `NasOpsJobService` (`Continue`, il servizio che avvia il NAS) e `NasWork` (il battito, NA-8). Gli ultimi due sono dell'altra squadra:
  tocchi di poche righe, riportati qui.

**Sicurezza: cosa è stato toccato, e perché non si indebolisce**
- *Operazioni sul NAS (NA-1)*: `ConfirmationGate.accept` e `PlanRules.firstViolation` restano prima di tutto, identici; il diario nasce
  solo da `OpsStore.create(AcceptedPlan)`, come prima. Cambia solo **quando** si prende il lucchetto dell'esecuzione (dopo la
  registrazione). Un'operazione in coda non tocca niente sul NAS; quando parte ogni elemento ricontrolla il cancello (congelata, scritture
  ferme, salto dell'ora) e i timbri come sempre (E-D13), quindi un congelamento o una conferma scaduta nell'attesa fermano l'esecuzione.
  Se l'utente la ferma mentre aspetta (R9) non si esegue; la stessa richiesta toccata due volte non ne fa partire due. Il lucchetto
  dell'esecuzione resta uno solo, condiviso con le pulizie: due esecuzioni non si sovrappongono.
- *Congelamento (NA-4, NA-7)*: la conferma resta il numero riscritto uguale a quello mostrato (doppia conferma); **non vale più di prima,
  vale meno**: una volta sola, per quel congelamento, con scadenza. È salvata *prima* di togliere il congelamento dal cursore (se il
  processo muore in mezzo, il gesto che l'utente ha fatto vale ancora; il contrario l'avrebbe perso e ricongelato). Un file illeggibile
  non dà sconti. NA-7 chiude una piccola finestra: nel tempo fra l'avvio del processo e la partenza del guardiano i cancelli delle
  scritture potevano vedere "nessun congelamento".
- *D31 e D33*: `writeGate`, `IndexWriteRules` (recinzione D31) e i temporanei del diario del backup (D33) non sono stati toccati.
  Il gesto dell'ora del NAS ha un foglio di conferma in più, non uno in meno; la sospensione di 24 ore resta come prima.
- *Esclusioni (NA-10)*: impostazione personale (area privata dell'account), mai dati letti dal NAS; non cancellano né spostano file.
- *Nascosti (GA-1)*: la vista dei Nascosti si rilegge solo finché qualcuno la guarda (la pagina sbloccata o il viewer aperto da lì), e
  si ferma da sola all'uscita: niente dei nascosti si legge fuori da una schermata aperta.
- Nessuna voce chiusa da `docs/review/4a-fase4a.md` o `docs/review/4b-fase4b.md` è stata riaperta.

**Fake allineati al vero**: `FakeThumbnailLoader` (da `UI_HIDDEN` in su le celle tornano sul segnaposto finché non c'è `resume`),
`FakeCollectionsWorld.hiddenLibrary` (vista viva), `InMemoryNasOperations` (registra e poi si mette in fila), `FakeNasAreas` (esclusioni,
verifiche, salto dell'ora), `ScanHarness` (la franchigia sopravvive a `restartScanner`, come il file in produzione). `FakeMediaStore`: i
due non hanno lo stesso ordine per le righe senza data (il vero mette prima gli zeri, poi i NULL), ma danno le stesse righe: lo prova un test
di parità. Confronti: `ImagingContractTest.ga3_…`, `HiddenLiveViewTest`, `AndroidMediaStoreSourceTest.ga5_…`,
`VerificaFunzionaleNasOpsTest.na1_…` (su entrambi gli esecutori).

**Residui**
- Il selettore per le altre app (5B, P10) svuota la finestra delle miniature della Libreria (`PickerScreen`: `window(vuota, vuota)` allo
  smontarsi): al ritorno a `MainActivity` nessuno la rimanda. Stesso meccanismo di GA-3, fuori perimetro: da guardare con la 5B.
- `BackupHostEffect` (BK-3, altra squadra) ha ancora il ricordo "già chiesto" in `remember` (vedi GA-4): può usare `PermissionAsks`.
- Un'operazione in coda perde il suo posto se muore il processo e la riprende `resumeAll` nell'ordine del diario, non necessariamente in
  quello di arrivo.
- Da provare sul telefono: vedi il report e `docs/review/funzionale-fasi-1-5a.md` (prove 7, 8, 9, 12, 13, 16).

## Correzioni della prova 5B: F1, F2, F3 (19/10)
Fonte: `docs/prova-500.md` Parte C §C6 e la sezione «Fase 5B: P13» qui sopra (i tre difetti, gli evitamenti del banco, i test «oggi» / «giusto» / «il banco
lo trova»). Perimetro: `core-backup/.../versions/**` (F1, F3, F2 lato versioni), `core-backup/.../safety/BackupPrivateCopyGuardian.kt` (F2), i test di
`core-backup`, `docs/prova-500.md` Parte C. Nessun contratto cambiato (`VersionJournal`, `VersionCatalog`, `BackupCatalog`, `NasBackupOps` come prima),
niente in `core-nas`. Nessun NAS vero: il giro sull'area di prova lo rifà l'utente.

**F1 (P8, `NasVersionEngine.refreshPhone`): un lavoro dal telefono non resta mai appeso.**
- Scelta: la regola proposta, resa completa. Con la riga del lavoro diventata `Stale` si guardano le righe **presenti** della versione nel catalogo: una con il
  timbro atteso e un altro `PhoneRef` → il lavoro la segue (come prima); la riga di prima ancora presente (il `PhoneRef` contiene la versione del MediaStore:
  uguale = la lettura del telefono non ha ancora visto il cambio) → si aspetta, mai un `GONE` in anticipo; **in ogni altro caso `GONE`** (`gone(job)` toglie
  prima il proprio temporaneo; se non ci riesce il giro si ferma e riprova, e c'è comunque la pulizia D33).
- Perché anche «presente ma con un altro timbro» porta a `GONE` e non ad aspettare: la proposta («nessuna copia presente → `GONE`») lascerebbe appeso il
  lavoro se la versione fosse sul telefono con un timbro diverso da quello del lavoro (per esempio dopo una ricostruzione che cambia la generazione). `GONE`
  è sicuro: se la versione serve ancora, il piano del giro dopo ne fa un lavoro nuovo dalla riga di adesso.
- Test: `ChaosKnownDefectsTest.f1_*` (lo scenario di P13; un video a metà con il temporaneo, tolto prima di `GONE`; la riga di prima ancora nel catalogo:
  nessun `GONE` in anticipo), `VersionSimulatorTest.f1_*` (simulatore SMB), `ilGiroCheTrovavaF1OraEVerde` (seme 20261016, giro 116).

**F2 (P3 `BackupPrivateCopyGuardian.link`, innescato da P8): nessun doppione della copia del guardiano. Corretto da tutti e due i lati.**
- **Le versioni** (`NasVersionEngine.archive`): prima di spostare in `Versioni/` il file al solito percorso, se la **sua** versione ha un altro elemento aperto
  nel diario (`VersionJournal.busy(current.versionId)`: nella tabella vera `backup_item` lo vede per ogni tipo, guardiano e caricamento della 5A compresi) il
  lavoro aspetta. È la seconda proposta («versioni solo con il guardiano `LINKED`»), resa generale: chiude la finestra per ogni strada che crea una versione
  (correzione, file preparato, Samsung «Salva» su un media del guardiano) e anche per un elemento della 5A pubblicato e attaccato ma non ancora `LINKED`, che
  il banco non può provare (nella 5B il caricamento è sempre finito). L'attesa dura quanto l'altro elemento: il guardiano riprende i suoi elementi aperti a
  ogni giro (`GuardScope.All`).
- **Il guardiano** (`link`): riprendendo un elemento `PUBLISHED` il cui nome finale è vuoto o ha altri byte, prima di rifare la copia cerca nell'area privata
  una copia con gli stessi byte (`PrivateCopyFinder.findIdentical`, la deduplica D8, **riletta qui** per intero; il catalogo vero la cerca «in qualunque
  cartella», `Versioni/` compresa): se c'è, la collega lì (`LINKED`, il nome finale diventa il suo) e non rifà niente. È la prima proposta, ma con la rilettura
  invece della fiducia nella verifica del catalogo (la porta del guardiano non sa dire «verificata» per una versione, e «al sicuro» nasce da una rilettura).
  Serve quando la copia è stata spostata comunque (un'altra strada, una finestra futura); con il controllo delle versioni nel banco non scatta più.
- Scartato: chiudere l'elemento con `LINKED_EXISTING` (da `PUBLISHED` la macchina a stati non lo ammette e il contratto non si cambia qui); `LINKED` è il passo
  successivo del cammino.
- Test: `ChaosKnownDefectsTest.f2_unaVersioneNuovaAspetta...` e `f2_ilGuardianoRitrova...` (NAS finto e simulatore SMB), `ilGiroCheTrovavaF2OraEVerde` (seme
  20261016, giro 633).

**F3 (P8, `NasVersionEngine.restore` / `archive` dopo una risposta persa): nessun file con un timbro diverso da quello atteso resta «verificato».**
- Scelta: «confrontare anche la data» e «rileggere», insieme (`movedFile`). Dopo una rinomina con la risposta persa (rami `SourceMissing` e `Collision`) il
  file a destinazione è quello del lavoro solo se ha la sua identità **e** il timbro atteso (dimensione, data, `fileId`; la rinomina non li cambia: su Samba,
  sul simulatore SMB e sul NAS finto la data del file resta). Se torna solo l'identità (stesso `fileId` e stessa dimensione, un'altra data) vale solo una
  **rilettura completa** con lo SHA-256 della versione (per uno scambio tornato indietro, `ROLLED_BACK`, la versione dell'attuale archiviato): byte uguali
  → è il suo e il catalogo riceve il timbro dei byte riletti; diversi, o l'impronta non nota → **non è il suo**: lo scambio finisce `FAILED` /
  `USUAL_PATH_TAKEN`, la versione aspetta con `SOURCE_CHANGED`, e niente del catalogo segue il file. È esattamente il cammino della modifica di un'altra
  dimensione (O1), già analizzato con le sue quattro domande (`StaleCatalogAfterPcEditTest`): il catalogo ritarda fino alla scansione, senza copie
  «verificate» con altri byte.
- Così vale la regola F4-D8 / L28: «al sicuro» nasce da una rilettura fatta qui o da un timbro **atteso** che coincide, mai da un timbro trovato dopo
  un'interruzione.
- Scartata la sola «scrivere nel catalogo il timbro atteso»: la copia al solito percorso sarebbe rimasta «verificata» con altri byte fino alla scansione (e lo
  scambio `DONE` con l'originale perso). La rilettura costa la lettura del file solo in questo caso raro (risposta persa e data cambiata).
- Test: `ChaosKnownDefectsTest.f3_*`: lo scambio con la modifica della stessa dimensione (NAS finto e SMB), il «giusto» di P13 (dopo la scansione tutti gli
  invarianti), «Libera spazio» che non libera, una versione nuova che non archivia i byte del PC come «(originale)», la riscrittura con gli stessi byte
  riconosciuta dalla rilettura (NAS finto e SMB), l'archiviazione nelle due varianti (`archived`); `ilGiroCheTrovavaF3OraEVerde` (seme 777, giro 1306).

**Il banco senza evitamenti.** Tolte le tre voci di `KnownDefect` (l'enum resta, vuoto, per i difetti futuri: il banco non salta un caso senza dirlo) e i tre
punti che le usavano: il MediaStore si ricostruisce anche con un lavoro dal telefono aperto, una correzione parte anche con un elemento del guardiano aperto,
**metà delle modifiche del PC conserva la dimensione**. I sorteggi con `avoid = emptySet()` sono gli stessi di prima: i giri che trovavano i difetti si
rifanno uguali. I test «oggi» sono tolti, i «giusti» riaccesi, «il banco lo trova» diventati «il giro che lo trovava ora è verde».

**Risultati** (19/10, senza evitamenti, `--max-workers=2`)
| Banco | Giri | Interruzioni (nella 5B) | Violazioni |
|---|---|---|---|
| JVM, NAS finto, seme 20261016, riferimento / vero | 2.000 + 2.000 | 7.861 (3.780) / 7.573 (3.720) | **0** |
| JVM, NAS finto, seme 777, riferimento / vero | 2.000 + 2.000 | 7.854 (3.831) / 7.520 (3.775) | **0** |
| JVM, simulatore SMB, riferimento / vero | 600 + 600 | 2.419 (1.146) / 2.374 (1.152) | **0** |
- Prima della correzione, senza evitamenti, gli stessi 2.000 giri sui due semi davano 10 giri rossi (P13).
- **Mutazioni**: le quattro della 5B (7, 49, 24, 203 giri rossi su 500) e le quattro della 5A (41 / 31, 36 / 27, 41 / 32, 22 / 22) restano rosse con gli
  stessi numeri; gli stack sani verdi.
- **Moduli**: `core-backup` 520 test, 0 falliti, 3 saltati (solo quelli del NAS vero), ~4 minuti; `core-nas` 276 test, 0 falliti, 2 saltati.

**Da provare sul campo** (F43 e area di prova del NAS vero, li fa l'utente)
- Il giro `area.ps1 -Mode backup -Stage run5b` sul NAS vero con il codice nuovo (le versioni che aspettano il guardiano allungano qualche lavoro di un giro).
- Sul telefono: un lavoro di versione a metà (video grande), poi il MediaStore che si ricostruisce (aggiornamento di sistema, scheda SD) e una modifica nuova:
  in Diagnostica nessun lavoro fermo, «Ripristina originale» di nuovo disponibile, nessun temporaneo rimasto.
- «Ripristina originale» interrotto con `kill -9` subito dopo lo scambio e, prima di riaprire l'app, il file al solito percorso riscritto dal PC con la
  stessa dimensione (un editor di metadati) o soltanto salvato uguale: nel primo caso lo scambio non riesce e il catalogo si sistema alla scansione, nel
  secondo riesce.
- Un media con la sola copia comune: «Libera spazio» (il guardiano) con `kill -9` a metà, poi una correzione o Samsung «Salva» prima della ripresa: una
  sola copia privata.

**Note (fuori perimetro)**
- La stessa finestra di F2 per un elemento della 5A (`SafeUploadEngine`, P2) è chiusa dal controllo delle versioni; il motore della 5A non ha la ripresa con
  la deduplica del guardiano e se la sua copia venisse spostata da un'altra strada la rifarebbe. Da valutare da chi tiene P2.
- Uno scambio la cui archiviazione ha la risposta persa e il cui attuale archiviato è poi modificato dal PC in `Versioni/` finisce `FAILED` con il solito
  percorso vuoto: i byte dell'attuale li ha sovrascritti il PC, l'originale resta in `Versioni/`. Era già così con una modifica di un'altra dimensione; il
  banco non lo prova (il PC del banco non tocca `Versioni/`).

## Correzioni funzionali: backup 5A (19/10)
Le voci BK-1 … BK-14 della verifica funzionale (`docs/review/funzionale-fasi-1-5a.md`, ognuna con il suo "Stato" e i suoi test) e la finestra di F2 nel motore
della 5A. Ogni difetto ha un test che prima falliva. Visti rossi rimettendo per una prova il comportamento di prima: i due motori e la finestra di F2
(`VerificaFunzionaleBackupTest.bk1_…`, `f2_…`: 3 rossi su 7, gli altri provano l'API nuova), e 12 dei 16 test sul grafo vero di `VerificaFunzionaleBackupAppTest`
(BK-3, BK-4, BK-6, BK-7, BK-8, BK-10, BK-12, BK-13; BK-2, BK-11 e `riprova` provano parti che il comportamento di prima non aveva). BK-13 e BK-14, "plausibili",
sono **confermate**. Il backup 5A aveva i tasti e gli stati che mentivano di più (BK-6 bloccava "Inizia ora"): adesso la pagina e le notifiche dicono quello
che il backup fa.

**Decisioni**
1. **BK-6: un tocco dell'utente non si perde; parte dopo e gli altri cedono il passo.** Si è scelto di aspettare il turno invece di interrompere a forza le
   verifiche: il guardiano e le verifiche non hanno un punto sicuro a metà lotto e una rilettura interrotta è lavoro buttato; fermarsi a un confine di lotto
   (al massimo 60 secondi) è già il loro modo di finire per tempo. Quindi: il lavoro dell'utente aspetta fino a 15 minuti (la tappa più lunga è di otto,
   più un lotto) invece di 90 secondi, e **dice che aspetta** (`BackupGraph.userIsWaiting`): tappe di sfondo, guardiano e verifiche non cominciano un altro
   lotto. Se il turno resta occupato la tappa risponde `occupato` (`BackupStageOutcome.busy`), non «niente da fare» (`null`): il servizio si fa riprogrammare
   (`RESCHEDULE`), un lavoro di WorkManager si riprova con l'attesa crescente. Nessun file a metà e nessun temporaneo perso: si ferma dove si ferma per
   tempo (punto di ripresa nel diario).
2. **BK-1: "Proteggi subito" guida solo ciò che ha creato.** Un elemento di una corsa rapida lasciato a metà (il telefono è uscito di casa) non è più
   un candidato della corsa dopo (il piano lo vede già pianificato) e lo riprende il lavoro delle foto con le regole del preset: la foto nuova non aspetta più
   l'arretrato, quella interrotta aspetta il preset. È la lettura più stretta di D34 ("una foto appena scattata si carica appena c'è il Wi-Fi di casa") e
   la sola che non richiede un campo nuovo nel diario (schema v4 invariato).
3. **BK-2: tutti i giri che servono, e il rientro a casa.** `afterPhoneRead` chiede foto nuove, foto e video insieme (lavori unici, `KEEP`). Il lavoro rapido
   che aspetta una rete diversa da casa si rimette in coda su una rete non a consumo con 15 minuti di ritardo (nessuna rete = nessun lavoro; Wi-Fi che non è
   casa = al massimo un controllo ogni quarto d'ora); non riguarda gli altri lavori né il NAS spento.
4. **BK-3: un problema ricordato non è un fatto.** Si confronta con il mondo di adesso (permessi, sola lettura) e si toglie da solo se non vale più; per
   quello che solo l'utente sa (password, spazio, quota) c'è "Riprova" su ogni problema di giro e il ritorno da un'azione (`onPageResumed`).
5. **BK-4: la pausa finisce quando il lavoro parte.** Prima della decisione non si tocca.
6. **BK-5: l'avanzamento sta sul catalogo.** `totale = fatti + media ancora in coda`; il motore non lo conosce e non cambia. Il tempo che manca è il ritmo
   osservato mentre lavorava, da due media in poi.
7. **BK-8: il riepilogo si ricalcola se il backup ha fatto strada;** la stima davanti a un tocco aspetta al massimo 3 secondi e poi va senza.
8. **BK-9: nessuna pagina «Aree»;** `NO_PRIVATE_AREA` dice «Chiedi all'admin del NAS». Le aree non si cambiano dopo la fondazione (ON-12c) e l'app non crea
   share (E-O3): un tasto avrebbe promesso una cosa che nessuna pagina fa.
9. **BK-10, BK-12, BK-13, BK-14:** come nel documento della verifica. `WAITING_UNLOCK` e `PROFILE_INACTIVE` restano solo nel catalogo dei codici: la pagina
   che li dovrebbe mostrare non gira mai in quelle condizioni.
10. **BK-11:** una scelta sulle copie simili chiede la sincronizzazione accorpata (30 s, solo con l'app in primo piano) come preferiti e nascosti.
11. **F2 nel motore della 5A: chiusa, con lo schema del guardiano.** Al collegamento, se la copia appena pubblicata non c'è più al suo nome (spostata da
    una versione, da "Cartelle NAS", dal PC), o c'è un file con altri byte, o la pubblicazione è finita con la risposta persa e il finale è sparito, il
    motore **non ricarica subito**: cerca nell'area privata una copia con gli stessi byte con la deduplica (D8, `ExistingCopyFinder`, riletta per intero qui)
    e, se c'è, la collega dov'è (`LINKED`, il nome finale diventa il suo; da `PUBLISHED` la macchina a stati non ammette `LINKED_EXISTING`). Se il catalogo non
    la conosce ancora (la scansione non l'ha vista) il motore ricarica come prima: stesso limite del guardiano. L'esecutore di riferimento non ha questa
    ripresa (non ha la deduplica con `qh1`): il banco 5A non muove la copia fra pubblicazione e collegamento, e il banco 5B usa il motore vero.
    Il lato delle versioni (`VersionJournal.busy`) è com'era.

**Modifiche ai contratti e al codice condiviso**
- `core-backup`: `UploadStages.stage(yieldTo)` e `BackupStageOutcome.busy` (BK-6); `SafeUploadEngine` (BK-1, F2); `DefaultBackupSettings.refresh()` e
  `BackupSourceFence` (BK-14); `BackupProblemCode.actions` (`RETRY` su `NAS_FULL`, `QUOTA`, `AUTH`, `READ_ONLY_AREA`; `NO_PRIVATE_AREA` → `ASK_ADMIN`);
  nel `testing`: `ReferenceBackupPlanner(clock)` e il trigger, `InMemoryBackupEngine`, `FakeBackupSettings(fence, account)`.
- `core-personal`: `SimilarCopiesChoices(onSaved)` (BK-11; `resolve` resta l'ultimo parametro, per la lambda finale).
- `feature-backup`: `BackupActions.onPageResumed()` (di serie niente), la pagina lo chiama a ogni `ON_RESUME`; la scheda «non è ancora pronto» e `NO_PRIVATE_AREA`
  senza il tasto delle Aree.
- `:app`: `backup/` (`BackupGraph`: `nextAll`, `userIsWaiting`, `uploadEstimateBytes`, `rememberedProblem`, `waitingStatus`; `RunProgress`, `SummaryCache`,
  `BackupRunMemory.progress`; `AppBackupActions`: ordine di `startUser`, ritorno da un'azione, `decide`), `work/` (`BackupStages`, `BackupWork`: `busy`, BK-2;
  `BackupJobService`; `LanConstraints.unmetered`), `main/BackupHostEffect.kt` (`PermissionAsks`, `permissionStep`; niente «Impostazioni, Aree»).

**Sicurezza: cosa è stato toccato, e perché non si indebolisce**
- D2: nessuna primitiva nuova; il backup non toglie né sostituisce niente. F2 collega una copia **già esistente** e riletta; non sposta, non cancella, non
  sovrascrive. BK-1 e BK-6 cambiano solo **quali** elementi si guidano e **quando**.
- «Al sicuro» nasce solo da una rilettura fatta qui (F4-D8): F2 passa dalla deduplica (SHA-256 riletto) e l'`attach` del catalogo viene prima del diario; un hash
  o un timbro dell'indice non decidono niente. Un problema ricordato che si toglie da solo (BK-3) non rende mai «al sicuro» un media.
- D12/D13/R2 invariati: `AppBackupGate` non è stato toccato. Le sorgenti ammesse dalla recinzione (BK-14) si **restringono** soltanto: senza account vale
  ancora nessuna sorgente; con un account vero la recinzione non si applica, come prima. `check.ps1` cerca ancora `DebugBackupFence` nel dex di release.
- Il lavoro dell'utente che aspetta (BK-6) non scavalca il cancello, le regole né la pausa: il turno si prende e il cancello si guarda a ogni elemento come
  sempre. BK-2 chiede un vincolo di rete più stretto (Wi-Fi non a consumo), mai più largo.

**Fake allineati al vero**: `ReferenceBackupPlanner` (trigger), `InMemoryBackupEngine` (corsa rapida), `FakeBackupSettings` (D13), `BackupRig` (lavoro dell'utente,
azioni, orologio, ingressi). Confronti: `DefaultBackupPlannerTest.bk1_…`, `DefaultBackupSettingsTest.bk14_…`. `FakeBackupOverview`, `FakeBackupGate`,
`FakePhoneFolders` e `FakeDeviceFolderProposals` restano lavagne scelte dal test.

**Residui**
- Una corsa rapida lasciata a metà aspetta il lavoro delle foto e le regole del preset (decisione 2).
- La capsula del piede della Libreria vede «Riattiva le scritture» al primo evento successivo, non subito (la pagina Backup sì, al ritorno): il flag
  `EmergencyStop` sta in `nas/`, fuori dal perimetro di questa correzione.
- Il lavoro rapido che aspetta casa riprova ogni quarto d'ora su un Wi-Fi che non lo è: costa un avvio e una valutazione delle regole, mai traffico.
- F2: una copia spostata che il catalogo non ha ancora visto si ricarica (stesso limite del guardiano).
- Da provare sul telefono: vedi il report e `docs/review/funzionale-fasi-1-5a.md` (prove 5, 6, 13 della sezione 4).

## Separa fra dispositivi per impronta (20/10)
Chiude il rischio aperto del P7 ("Separa" fra due dispositivi dipende da identita' uguali). La voce `similar_apart:<id>` nomina le due copie con
gli id del dispositivo che l'ha scritta; l'altro dispositivo ritrovava il soggetto anche per impronta, l'altro media solo per id. Con id diversi
(D7 converge al minore, D8 tiene l'id di un media con la copia sul telefono) la voce restava "in attesa" per sempre e le copie restavano unite.

**Decisioni**
1. **L'impronta dell'altro media sta nel valore, come testo** (`formato-nas.md` §5.5-bis). Il valore di una separazione e'
   `<qh1 in base32>:<dimensione>` (`PersonalValue.Apart` nel codice) invece di `true`; l'impronta del soggetto resta in `fp`. Scartato un campo
   nuovo della voce (`fp2`): nella versione 1 un campo sconosciuto rifiuta il segmento intero, quindi un'app di prima avrebbe perso tutte le voci
   del segmento (preferiti, nascosti, album), e servirebbe la versione 2 del formato, che e' di core-trust e comune a ogni file firmato. Un testo
   la versione 1 lo ammette gia': **nessuna versione nuova** (la strada di D36: campi nuovi dentro `media_flag`). Un'app di prima legge il
   segmento, prende il testo per un testo e non separa niente: le copie restano unite li' finche' non si aggiorna, nessun'altra voce si perde.
   In questa versione il codec lo legge solo nelle chiavi di una separazione e in una forma sola: un altro testo li' rifiuta il segmento, come
   ogni campo fuori forma. L'impronta e' nel corpo, quindi la copre l'impronta del segmento elencata nella testa firmata.
2. **Le voci di prima si leggono come prima**: `true` separa, e l'altro media si ritrova solo per id. Nessuna migrazione; un "Separa" rifatto
   sulla stessa coppia scrive il valore nuovo. Chi scrive usa ancora `true` solo se il suo catalogo non conosce l'altro media.
3. **Una regola sola per ritrovare i due media**, nella proiezione (`CatalogPersonalProjection`, per la voce che arriva e per le separazioni
   dello stato con cui si contano i gradi) e nelle scelte che legge il rilevatore (`SimilarCopiesChoices.separatedPairs`, dietro la porta
   `SimilarChoices` di core-library): l'id (anche tramite un alias), altrimenti l'impronta **se indica un media solo**. Un'impronta che indica
   piu' media (L32, due copie che il catalogo tiene separate) non indica nessuno: la voce resta in attesa e non separa una copia a caso (E-S11).
4. **Chi esce dal gruppo non dipende dagli id locali.** Il grado (le separazioni di un media dentro il gruppo) si conta sui legami, che dopo la
   risoluzione per impronta sono gli stessi su ogni dispositivo. L'unico caso che usava l'id ("due soli media: esce l'identita' maggiore") ora fa
   uscire entrambi: il gruppo si scioglieva comunque, quindi il risultato e' quello di prima, senza una scelta. Il rilevatore
   (`SimilarGroupPlanner`) non decideva niente dagli id: legge le coppie gia' risolte, e non cambia.
5. **La lapide** vale per la chiave, quindi arriva anche con id diversi. Per scriverla da un dispositivo con id diversi c'e'
   `SimilarCopiesChoices.removeSeparation(a, b)`: la mette su ogni separazione che da li' nomina quella coppia, comprese quelle scritte
   dall'altro dispositivo (due "Separa" della stessa coppia su due dispositivi con id diversi sono due voci). Nessuna UI la chiama ancora.
6. **Il test di integrazione non alterna piu'**: `SimilarCopiesIntegrationTest` ha due prove, `_stessiId` (il tablet adotta l'indice; dove ha
   tenuto il suo id, la prova gli fa conoscere quello del telefono con un alias) e `_idDiversi` (il tablet non scarica l'indice: nessun id in
   comune, verificato). Tutte e due chiedono zero voci in attesa, due celle sul tablet e un confronto che non le riunisce.

**Modifiche ai contratti e al codice condiviso** (solo `core-personal`, piu' un commento della porta `SimilarChoices` di core-library)
- `PersonalValue.Apart` (`text`, `parse`); `PersonalMerge` (rango dopo i testi); `PersonalEntry` (`Apart` solo in una separazione);
  `SimilarCopiesRecords.apart`, `separates`, `otherFingerprint`.
- `PersonalStore.fingerprintOfMedia` e `mediaWithFingerprint` (di serie null), le stesse in `PersonalProjection`; `LedgerPersonalStore` le passa
  alla proiezione. Codec: il testo di una separazione e' un `Apart`. Registro locale (`FilePersonalLedger`): un tag in piu' nella stessa versione.
- `SimilarCopiesChoices`: `saveSeparation` scrive l'impronta dell'altro media, `separatedPairs` ritrova per impronta, `removeSeparation` nuova.
- `:app/src/main` non cambia: la costruzione di `SimilarCopiesChoices` (store e `catalog::resolve`) e l'adattatore restano quelli, l'impronta
  passa dallo store. Nessuna query nuova in core-database (`versionDao().byQuickFingerprint`, `MediaCatalog.resolve`).

**Sicurezza e privacy**
- L'impronta sta solo nei dati personali: area privata dell'account, segmenti elencati da una testa firmata, registro locale in
  `noBackupFilesDir`. `PersonalValue.Apart.toString()` non la contiene: mai nei log, nelle notifiche o in Diagnostica.
- Una voce letta dal NAS non separa mai una copia sbagliata: un'impronta con piu' candidati non risolve, un soggetto che non e' un'identita' non
  si cerca nemmeno per impronta, una voce non tocca altro che l'appartenenza a un gruppo che il dispositivo ricalcola da se'.

**Prove**
- core-personal, rosse prima e verdi dopo: `conIdDiversiUnSeparaSulTelefonoScioglieIlGruppoAncheSulTablet`,
  `conIdDiversiSeparandoUnaCopiaDaTreEsceLaStessaCopiaAncheSulTablet`. Nuove: impronta ambigua (`...NonSeparaNienteEResta_inAttesa`), voce
  del formato di prima (`unaVoceNelFormatoDiPrimaSiLeggeComePrima_soloPerId`), lapide con id diversi dai due lati, forma e lettura ostile del
  valore e lettura "di prima" (`SimilarCopiesRecordsTest`), ordine totale (`PersonalMergeTest`), registro locale (`LedgerStoreTest`).
- `SimilarCopiesIntegrationTest` (grafo vero di `:app`): sul codice di prima `_stessiId` verde e `_idDiversi` rossa (`unresolved=1`, le copie
  restano unite sul tablet); con la correzione verdi tutte e due. Provata in un worktree staccato su `e559397` con le sole modifiche di questa
  correzione: nell'albero di lavoro `feature-backup` era a meta' del P14 e non compilava.
- Cambiata la premessa di `unaSeparazioneArrivataPrimaCheIlTabletConoscaLaCopiaSiApplicaQuandoArriva`: "il tablet non conosce la copia" voleva
  dire "la ha con un altro id", che ora si ritrova per impronta; ora la copia sul tablet non c'e' ancora, e la voce si applica quando arriva.

**Residui**
- Un'app di prima (sul NAS di prova) non vede le separazioni nuove: le copie restano unite li' finche' non si aggiorna.
- L'impronta e' quella della versione corrente quando si separa: un dispositivo che dell'altro media conosce solo una versione diversa (una
  modifica fatta dopo) lo ritrova solo per id. `byQuickFingerprint` cerca fra tutte le versioni del catalogo.
- Una separazione con id che qui non ci sono costa una ricerca per impronta in piu' a ogni lettura (proiezione, giro del rilevatore): le
  separazioni sono poche.

**Da provare sul campo (F31-bis, S25 e tablet di prova)**: "Separa" sul telefono e sul tablet, con le copie dell'area comune e quelle caricate
dal backup; se sul tablet un id e' diverso (capita da solo, a caso) le copie devono tornare due celle lo stesso, dopo la sincronizzazione dei
dati personali, e il confronto non deve riunirle.

## Fase 5B: P14, la 5B nell'app (20/10)

**Confermate dall'utente (20/10):** (1) "Ripristina originale" con il permesso di scrittura negato: lo scambio sul NAS si fa e il telefono tiene la modifica (E-E10, non la proposta di P12); (2) dalla 5B la modifica di una foto gia' al sicuro e' una versione: l'originale va in `Versioni/` con una rinomina, intatto (`BackupIntegrationTest.eB15` aggiornato).
Fonte: `docs/fase5-progetto.md` (§1.4, §2.4, §2.5, §2.7, riga P14 del §4.4, §5.6 F34–F45, §6.4, §9.1), le sezioni P0-B…P13 e «Correzioni della prova 5B»
qui sopra, le note di passaggio dei pacchetti (ogni riga «P14» e «!!» e' qui sotto, fatta o motivata), `docs/review/funzionale-fasi-1-5a.md` (i difetti
da non ripetere: tasti che non fanno niente, porte mai legate, condizioni non vere al momento della chiamata, finti diversi dai veri). Nessuna
dipendenza, nessun permesso e nessun componente nuovi; schema v4 cambiato (non rilasciato, nessuna migrazione 4→5).

**1. I buchi di base**
| Dove | Cosa |
|---|---|
| `core-backup/upload` | **Il cammino `INCOMING`** (D24): `SafeUploadEngine` carica il file locale (`IncomingLocalFiles`) con lo stesso caricamento sicuro (hash gia' noto ricontrollato, temporaneo, rilettura completa, pubblicazione senza sostituire, "nome (2)" se occupato, collegamento nel catalogo) e **solo dopo** la copia locale si toglie: la pulizia locale gira **prima** del passo terminale del diario (`saveItem`), cosi' una morte fra i due rifa' solo la pulizia (trovato dalla prova delle morti: con l'ordine inverso la copia restava per sempre). Il cancello e' quello della share di **destinazione** (`IncomingUploadPorts`: D12, R2, sola lettura, congelamento, pausa) e la radice di D13. `IncomingEveryCallTest`: guasti a ogni chiamata e morte a ogni scrittura (foto privata, video nell'area comune con nome occupato, file gia' presente, D12, D13, copia locale sparita o cambiata, senza porte, errori di lettura) |
| `core-database` | **Schema v4** con le colonne vere: `backup_item` (bersaglio, attuale, archiviazione, attesa, sorgente, impronta del file preparato) al posto della nota nella colonna `problem`; `free_space_run` (inizio monotono della verifica, conteggio dei riavvii, blocco) e `free_space_item` (riga e timbro del telefono, MIME, guardiano, controlli fatti); `metadata_correction` (la correzione "solo nell'app", Q6 (a), che sopravvive alla rilettura di META); indici `backup_item(kind, item_id)` e `free_space_item(state)`; la migrazione 3→4 riscritta uguale allo schema esportato rigenerato. DAO per tipo e share, per stato degli elementi di "Libera spazio", per i file in arrivo |
| `MediaCatalog` | `phoneMedia` (a pagine, `sort_time` decrescente), `relocateKeepingVerification` (una rinomina non toglie la verifica), `markPhoneLocationsVanished`, `mediaOfPhoneRow`, `phoneRowFolder`, `addEditedVersion` (versione `EDITED` senza campi di correzione, `VersionRules.edited`), `linkCopyAsVersion` (rifiuta nascosti, gruppi, proprietari diversi, impronte che dimostrano altro; mai sostituire), `correctInApp`/`inAppCorrection`, `incomingMedia` (il media di un file in arrivo: quello gia' noto per gli stessi byte, o uno nuovo **invisibile** finche' la copia verificata non c'e'), `reverts` (i ritorni a una versione nota dalla lettura del telefono, dopo il commit), `mediaWithVersions`. `CatalogFiveBTest` (8) |
| `core-nas` | **D13 e `Versioni/`** (`!!` di P8): una radice di prova `mcg-*` ha la sua `Versioni/` dentro (`mcg-campo/Backup/x.jpg` → `mcg-campo/Versioni/Backup/x.jpg`); fuori, alla radice della share come prima. `NasBackupVersionsContractTest.d13_…` |
| contratti `core-backup` | `BackupProblemCode.SOURCE_CHANGED` (riga nel §2.6 del progetto, azione Diagnostica, E-E6); `RestoreOutcome.PhoneNotRewritten(reason)` ("scambiato sul NAS, il telefono non e' riscritto": permesso negato, scambio non finito, riscrittura non riuscita); `RestoreAvailability.BUSY`; `BackupItem.flow` con `archivesCurrent`; `FreeSpaceRun.inDoubt/kept`; `FreeSpaceExclusion.NOT_MINE`/`BACKUP_NOT_ACTIVE`; il significato di `PhoneWriteGrant` scritto nel contratto (null = non chiesto → `NeedsPhoneWrite`; un permesso che non copre le righe = l'utente ha detto no); `BackupStore.createIncoming` |
| `COMMON_AREA_NOTICE` | **Scelta motivata: dalla 5B non si scrive mai nell'area comune** (correzioni e versioni si fermano con l'avviso, come gia' faceva la UI di P12). Un passo di conferma vorrebbe dire scrivere nella share che tutti i familiari possono cambiare e cancellare (L2) con le rinomine di `Versioni/`: rimandato a quando l'utente lo chiede, con un pacchetto suo |

**2. I collegamenti in `:app`**
- `backup/FiveBGraph` dentro `BackupGraph` (stessa vita, stessi cancelli, nessun I/O nel costruttore): i diari su Room (versioni nella tabella del backup, "Da decidere", giri di "Libera spazio"), i file preparati, la coda dei file in arrivo e le **facciate** per le porte di Hilt (vanno ai servizi veri appena il backup parte; prima rispondono come i null-object). `FiveBFactory` (core-backup) costruisce versioni, custode e correzioni dalla sessione: `:app` non nomina le primitive (`Phase5ArchitectureTest`).
- **Versioni prima della 5A, nella stessa tappa** (`VersionedBackupEngine`): `planPhoneVersions(planner.next(...))` e `versionEngine.run`, poi il caricamento della 5A con il tempo che resta. Senza, una modifica sarebbe andata su come "nome (2)". Anche le copie di "Scarica sul telefono" modificate in Galleria (`Pictures/MyCloud Gallery`, E-E7: non e' una sorgente) diventano versioni (solo `original = false`).
- **Dopo ogni lettura del telefono** (app aperta, `PhoneSyncWorker`): `settle()` di "Libera spazio"; un ritorno a una versione nota (`RevertedToVersion`, flusso `reverts` del catalogo) pianifica subito lo scambio (`planRealign`). **All'avvio**: `dropLeftoverWork`, `refresh` delle attivita', `settle`, gli scambi persi (i 500 media con piu' versioni), le sessioni di modifica scadute (`expireStale` → `ShareCache.sweepAtStart`), le cartelle di `incoming/` che nessuna riga aperta cita (`stager.sweep`).
- **Franchigia** delle versioni nella guardia dei cambiamenti di massa della scansione (`OwnChanges` di core-nasmedia, `NasGraph.ownChanges`): i file messi o spostati dalle versioni non sono "cambiati". **Conteggio dei riavvii** da `Settings.Global.BOOT_COUNT`.
- **File in arrivo**: `IncomingUploadWorker` (WorkManager) o, per una condivisione grande, `BackupJobService` con un id suo (`1_000_104`, UIDT, stessa notifica e stesso "Ferma"); la lettura del telefono chiede il lavoro se ci sono arrivi aperti. Lo spazio libero dello stager si misura sulla prima cartella che esiste (`IncomingSpace`): `usableSpace` di `incoming/`, che nasce alla prima copia, era 0 e **ogni prima condivisione sarebbe stata rifiutata** (trovato dal test d'integrazione).
- **Integrazioni** (`integrations/IntegrationsGraph`): installate da `MyCloudGalleryApp.onCreate` prima di qualunque richiesta (`IntegrationPorts`, `PickerPorts`, `ReceivePorts`); le impostazioni del blocco seguite a livello di processo (un'activity d'ingresso a processo freddo trovava il blocco "da leggere"); la Libreria per le altre app con la vista senza nascosti, un **caricatore delle miniature suo**, le Cartelle NAS e i media di una cartella dal catalogo (mai `Versioni/`), i byte del NAS a intervalli con la scadenza d'apertura; "Salva sul NAS" nella cartella `Ricevute` di ogni area (sotto `mcg-campo/` con D13); "Modifica con…" (`EditCatalog` sul catalogo, `edit_session` su Room, copia in cache dal NAS, "Scarica e modifica in Galleria", versione preparata → `planStaged`, D13 sulla cartella della riga); "Condividi senza posizione" (`LocationFreeShare` con la sorgente del telefono o del NAS e i tre lettori dell'app). L'azzeramento revoca ogni id dei documenti (`GalleryGraph.onShutdown`). Diagnostica: `INTEGRATIONS_COUNTERS` (soli codici e numeri).
- **`EntryGate.decide()` valuta il blocco adesso** (`!!` di P10, buco di sicurezza): `AppLockController.isUnlockedNow` (impostazioni non lette = chiuso; timeout dall'uscita misurato adesso, la `MainActivity` che torna trova la schermata di blocco; orologio che torna indietro = chiuso) e, dopo uno sblocco fatto da un'activity d'ingresso (`endEntryAuthentication`), **due minuti di tolleranza** monotoni anche con "Subito" (proposta di P10), annullati appena la `MainActivity` torna in primo piano. `AppEntryGateTest` (6), `AppLockControllerTest.p14_*` (5).
- **Porte di Hilt** (`di/FiveBModule`, `src/main`: debug e release): `FreeSpace`, `VersionKeeper`, `Corrections`, `PendingTasks`, `BackupClock`, `PhoneSystemDialogs` (Android 11+, recinzione D13 riga per riga), `PhoneOnlyAssessor`, `PendingTaskActions`, `VersionActions`, e `backup/FiveBUi` per le voci del viewer (`BackupMediaActions` in `ViewerServices`). "Ripristina originale", le correzioni e il guardiano di "Libera spazio" girano **uno alla volta** con il backup (`BackupGraph.exclusive` come un tocco che aspetta il turno; occupato = `BUSY`).
- **Navigazione e fogli alla radice** (`main/FiveBHostEffect`): Versioni (anche al riepilogo di "Ripristina originale"), Correggi e Ruota (precompilati dalla versione attuale), "Elimina solo dal telefono" (che apre "Libera spazio" con la selezione), il foglio "Elimina…" della 4B sulle copie di versioni precedenti ("Da decidere" → l'attivita' si chiude quando l'operazione parte; "Elimina le intermedie…"), gli intenti di "Modifica con…" e "Condividi" dall'activity (al ritorno: permessi revocati, copie tolte, sessioni lette). Rotte `backup/free-space` e `backup/pending`; "Libera spazio" in Raccolte → Utilita' e nella selezione della Libreria (`LibraryCommands.canFreeSpace`/`freeSpace`, feature-library); la scheda "Da decidere" nella pagina Backup.
- **"Ovunque"** (D22, E-D15): le copie di tutte le versioni dall'elenco del backup (`EverywhereCopies`) entrano in `NasActionTarget.others` con `previousVersion`, quindi nel piano `Trash` della 4B; il foglio le conta nella riga "versioni precedenti" (non le ripete come aree) e, per un solo-NAS, lo dice nel messaggio. Il bersaglio di un media visto dal telefono e' la copia della versione attuale, mai una in `Versioni/`.
- **Testi**: `SOURCE_CHANGED` nella pagina (stato `Problem` quando ci sono versioni ferme per il PC, Diagnostica) e in Diagnostica (`BACKUP_SOURCE_CHANGED`, E-E6); `BUSY`, `NOT_MINE`, `BACKUP_NOT_ACTIVE`, `PhoneNotRewritten` nei fogli. I lavori delle versioni aperti chiedono il giro delle foto, ma quelli che aspettano il PC no (niente polling: li riprende il giro periodico).

**3. Le righe «P14» e «!!» delle note**
| Nota | Esito |
|---|---|
| P9: legare `SafeFreeSpace` + `RoomFreeSpaceDiary`, `BOOT_COUNT`, dialogo = Android 11+, D13 | fatto |
| P9: `MediaCatalog.phoneMedia` mancante | fatto |
| P9: la lettura del telefono legge `free_space_item` per posizione e chiama `settle()` | `settle()` dopo ogni lettura e all'avvio: fatto. La lettura **non** cambia la sua regola: un media liberato ha sempre una copia privata verificata, quindi la sua riga che sparisce non lo fa mai "perso prima del backup" (quel segno vale solo senza copia al sicuro); il diario ha comunque `freedOrHandedPhoneLocations()` se servisse |
| P9: "Ovunque" da `EverywhereCopies` | fatto |
| P9: query su `free_space_item.state`; colonne facoltative | fatto (in v4) |
| P0-B: `AppLockGate` su piu' activity, navigazione, prova con `adb` | le activity d'ingresso decidono con `EntryGate` (blocco valutato adesso) e si sbloccano con la credenziale di sistema: `AppLockGate` resta della sola `MainActivity` (presume una sola activity: con due ogni passaggio conterebbe come uscita). Navigazione fatta. **`adb` no** (regola del pacchetto): F39/T-14 |
| P8: legare catalogo, diario, file preparati (`dropLeftoverWork`), attivita' (`refresh`), scrittore e controllo, custode, correzioni | fatto |
| P8: `planPhoneVersions` prima di ogni giro e `run` nella stessa tappa; `planRealign` dopo `RevertedToVersion` | fatto |
| P8: candidati E-E7 da `Pictures/MyCloud Gallery`; franchigia nella guardia della scansione | fatto (il primo senza un test d'integrazione: "Scarica sul telefono" del grafo vero non gira su JVM; F41) |
| P8 `!!`: D13 e `Versioni/`; nota temporanea nella colonna `problem` | fatto |
| P10: `LibraryMediaDocuments`, `IntegrationPorts.install`, `PickerPorts.install`; `revokeAll()`; contatori; `ThumbnailLoader` separata; sicurezza.md 4-bis; `nasFolders` e `mediaIn` | fatto |
| P10 `!!`: `EntryGate.decide()` adesso, con la tolleranza | fatto, con test |
| P11: `ReceivePorts.install`, `stager.sweep`, `ShareCache.sweepAtStart(expireStale())` | fatto |
| P11 `!!`: cammino `INCOMING` mancante | fatto |
| P11: `EditCatalog` (`linkCopyAsVersion`, `addEditedVersion`, `mediaOfPhoneRow`, `current`, `refreshPhone`); `EditNasCopies`, `GalleryDownloads`, `StagedVersionPlanner`, `phoneFolderAllowed` | fatto |
| P11: `begin` risponde `NAS_UNREACHABLE` con l'ora del NAS sconosciuta | **lasciato cosi'** (fallisce chiuso): una sessione dura 30 minuti d'ora del NAS (D31); un'ora persistita andrebbe tenuta valida ai riavvii e ai salti dell'ora, e a casa la prima sessione SMB la ancora. Da riguardare con F41 se fuori casa da' fastidio |
| P12: porte Hilt, rotte, scheda "Da decidere", "Libera spazio" in Raccolte e nella selezione, fogli alla radice, "Condividi", versioni precedenti in `others` | fatto. `CorrectionPlaces` (facoltativa) **non legata**: il foglio chiede le coordinate; il nome di un luogo dal dataset offline e' un lavoro a parte. "Vedi originale" e "Vedi" una versione **non offerti** (il viewer mostra solo la versione attuale: niente voce morta, D13 della Fase 3) |
| P12: contratti `inDoubt/kept`, `NOT_MINE`/`BACKUP_NOT_ACTIVE`, `PhoneWriteGrant`, `COMMON_AREA_NOTICE` | fatto (l'ultimo come scelta motivata, sopra) |

**4. Prove sul grafo vero** (`app/src/test/.../integration`, finti solo il NAS di core-nas e il telefono di core-mediastore)
- `FiveBIntegrationTest`: **modifica sul telefono → versione → Versioni → "Ripristina originale"** (Samsung "Salva"; il giro mette la modifica al solito percorso e l'originale in `Versioni/`; il piano dice `AVAILABLE`; prima il permesso di scrittura, il dialogo di sistema, lo scambio sul NAS e la riga riscritta con l'originale riletto); **"Libera spazio" con una copia rovinata dal PC** (esce come non verificata e resta sul telefono, l'altra si libera dopo il dialogo, il catalogo la dice solo sul NAS, niente toccato sul NAS); **ricevi → "Salva sul NAS"** (in coda solo dopo il tocco, caricata, riletta e collegata, copia locale tolta, riga "caricato"); **selettore e provider** con un solo-NAS visibile e uno nascosto (il nascosto non esce da nessuna via, il visibile si apre a intervalli byte per byte, l'azzeramento revoca); **"Modifica con…" di un solo-NAS** (copia in cache dal NAS, editor che la riscrive, versione dello stesso media, giro che la porta sul NAS con l'originale in `Versioni/`); **"Condividi senza posizione"** di un solo-NAS con il GPS (il file consegnato non ha la posizione, l'originale sul NAS e' intatto, la copia si toglie al rilascio).
- `FiveBButtonsTest`: le porte prese dal modulo Hilt dell'app non sono null-object e arrivano ai servizi veri; ogni voce del backup che il viewer mostra ha il suo effetto (fogli, sessione e intento, condivisione con un esito, "Ovunque", "Libera spazio" con la selezione); il viewer della galleria riceve le voci; nessun sorgente di `src/release` tocca le porte.
- `AppEntryGateTest`, `AppLockControllerTest.p14_*`, `IncomingEveryCallTest`, `CatalogFiveBTest`, `Review4bScanTest.d32_*` (franchigia), `NasBackupVersionsContractTest.d13_*`; i test della 5A aggiornati alle colonne nuove e alla 5B (`BackupIntegrationTest.eB15`: dalla 5B la modifica di una foto gia' al sicuro e' una versione, l'originale va in `Versioni/` con una rinomina invece di restare accanto a un "nome (2)"; resta intatto, byte per byte).

**Scelte fatte qui (da confermare con l'utente se non vanno)**
- **Permesso di scrittura negato in "Ripristina originale"**: vale E-E10 del progetto (lo scambio sul NAS si fa, il telefono tiene la versione
  modificata e l'app lo dice: `PhoneNotRewritten(PERMISSION_DENIED)`), non la proposta di P12 di "rifiutare senza scrivere niente". Il custode di
  riferimento (`InMemoryVersionKeeper`) ora fa lo stesso del vero; `VersionsViewModelTest` aggiornato (un finto diverso dal vero e' uno dei difetti
  della verifica funzionale).
- **Dalla 5B una modifica di una foto gia' al sicuro e' una versione** (D17): l'originale passa in `Versioni/` con una rinomina; il test della 5A
  `BackupIntegrationTest.eB15` diceva "stesso nome" e ora dice "intatto, byte per byte, in `Versioni/`" (nessuna sostituzione, nessun file tolto).
- **`SOURCE_CHANGED`** nella pagina e nel piede come attesa ("Versioni in attesa del computer", tono di attesa: ripartono da sole), messaggio del
  §2.6 allineato alla schermata.
- **I file ricevuti** vanno in `Ricevute/` dell'area scelta (privata o comune, con l'avviso), con il nome dell'altra app ripulito e l'estensione
  del tipo vero; le sottocartelle si scelgono fra quelle che la Libreria conosce.

**Limiti e punti aperti**
- Nessuna prova con `adb` ne' sul telefono (regola del pacchetto): i componenti esportati, i dialoghi di sistema veri, `FileProvider.getUriForFile`, `BitmapFactory` sulle dimensioni dei file in arrivo (su Robolectric e Windows tiene aperto il file: nel test le legge il test) e la conversione HEIC si provano sul campo.
- `MediaStoreRowFolders` legge la cartella di una riga dal thread di chi chiede il dialogo (una riga per volta): da misurare con F37 su lotti grandi.
- "Elimina…" delle versioni precedenti usa il foglio della 4B fuori dal viewer: dopo l'avvio dell'operazione la frase della 4B non si mostra (si chiude solo l'attivita').
- Le decisioni di P8 ancora da confermare con l'utente (nomi in `Versioni/`, `SOURCE_CHANGED` che aspetta, percorso occupato da file altrui) restano com'erano.

**Verifica** (20/10): `tools\check.ps1` verde (gradle, test di ogni modulo, APK di release, niente fake ne' strumenti di debug nel dex, D12 e R2
nel bytecode, permessi documentati); i banchi delle interruzioni del backup `tools\chaosrea.ps1 -Mode backup -Stage jvm` (2.000 giri, motore
vero ed esecutore di riferimento) e `-Stage smb` (600 giri sul simulatore SMB con le primitive vere): **0 giri con violazioni**, nessun caso
schivato. Nota: i banchi provano i motori di core-backup (5A e 5B); il cammino `INCOMING` e i collegamenti di `:app` li provano
`IncomingEveryCallTest` e i test d'integrazione qui sopra. I rossi delle prime due `check.ps1` erano test da aggiornare alla 5B (il messaggio del
§2.6 di `SOURCE_CHANGED`, la frase del piede, i due motivi nuovi di "Libera spazio", `BUSY` fra i ripristini offerti, i contatori delle integrazioni
nella sezione del backup, il custode di riferimento allineato al vero): nessun difetto nascosto.

**Da provare sul campo** (solo `mcg-prova`, D12/D13; il backup della 5B parte solo su una share di prova anche in release, fino a Q3):
F34 (Samsung "Salva" → versione al solito percorso e originale in `mcg-campo/Versioni/`; "Ripristina originale" → scambio e riga riscritta dopo il
dialogo; "Salva copia" → media nuovo; T-16 a), F35 (video modificato → "Da decidere"; "Elimina l'originale" → foglio della 4B e cestino del NAS;
"Tienilo" → resta), F36 (correzioni con il dialogo di sistema e su un solo-NAS; HEIC solo nell'app, Q6), F37 ("Libera spazio" su 50 media con una
copia cambiata dal PC e una cancellata; dialogo vero, app uccisa con il dialogo aperto, riavvio fra verifica e dialogo; durata della verifica per
GB), F38 ("Elimina solo dal telefono" di un media non al sicuro e di uno con la sola copia comune), F39/T-14 (selettore e provider da Gmail,
WhatsApp, Telegram, OneDrive; NAS spento; annullato a meta'; blocco "Subito" con la tolleranza dei due minuti; un nascosto; `am start` e `content
query` da `adb` senza niente), F40/T-15 ("Salva sul NAS" da Samsung Gallery, I miei file, Chrome; 100 immagini e un video da 1 GB come lavoro
avviato dall'utente; NAS spento → in coda; `incoming/` vuota alla fine), F41/T-16 b ("Modifica con…" in Samsung Gallery e in Google Foto; "Scarica e
modifica in Galleria"; fuori casa a freddo), F42 (condividere senza posizione JPEG, HEIC, MP4: `exiftool` vuoto), F43 (500 interruzioni della 5B
con il grafo dell'app), F44 (APK di release: tutto con R8), F45 (Twonky e Plex con `Versioni/`); T-09 e T-11 come da progetto. Durante F34–F37
guardare in Diagnostica `BACKUP_SOURCE_CHANGED` e `INTEGRATIONS_COUNTERS`.

## Correzioni 5B: posizione e Condividi (20/10)
Fonte: `docs/review/5b-fase5b.md` (R1, R2, R4, R12, R13) e `docs/review/funzionale-5b.md` (MC-1, MC-2). Perimetro: `core-metadata/.../strip/**` e
`.../write/**`, `feature-integrations/.../share/**`, il collegamento di "Condividi" in `feature-viewer`, `feature-library` e `:app` (`integrations/`,
`gallery/LibraryStates.kt`, `backup/FiveBUi.kt`, `di/FiveBModule.kt`, i due host alla radice). "Rimuovi la posizione" e' una promessa di privacy: prima la
tradiva un video con una traccia di metadati sconosciuta, una foto con la posizione nell'XMP sotto un nome che non e' `GPS…`, e la selezione della
Libreria; e i "lettori indipendenti" non lo erano.

**Lo stripper tiene per elenco, non toglie per elenco** (`core-metadata/.../strip/`)
- **Immagini** (`ImageRebuild`, `MinimalExif`): la copia si **ricostruisce** dal sorgente. JPEG: SOI, JFIF, un EXIF scritto da zero, il profilo colore
  (APP2 `ICC_PROFILE`), APP14 `Adobe`, poi tabelle, fotogrammi e scansioni (DQT, DHT, DAC, SOFn, DRI, SOS con i dati, DNL) nell'ordine del sorgente, EOI.
  PNG: IHDR, PLTE, tRNS, IDAT, IEND, i chunk del colore (gAMA, cHRM, sRGB, iCCP con il nome del profilo neutro, sBIT, cICP, mDCv, cLLi), pHYs, i chunk
  dell'animazione, e l'EXIF nuovo in `eXIf`. WebP: VP8/VP8L/ALPH, ICCP, ANIM e i fotogrammi ANMF con i soli dati dell'immagine, un VP8X nuovo e l'EXIF
  nuovo. Fuori tutto il resto: l'XMP intero (anche esteso), APP13, MPF con le immagini secondarie, commenti, `tEXt`/`zTXt`/`iTXt`, `tIME`, chunk e segmenti
  sconosciuti, cio' che segue EOI o IEND.
- **L'EXIF nuovo** ha solo orientamento (2-8) e data di scatto con fuso e millisecondi, letti con `ExifInterface` e ricontrollati (solo cifre e
  separatori): nessun testo del sorgente entra.
- **Video** (`IsoClean`), sul posto: restano `ftyp`, `moov` con `mvhd` e le sole tracce con `hdlr` `vide` o `soun` (dentro: `tkhd`, `tapt`, `edts/elst`, `mdia`
  con `mdhd`, `hdlr`, `minf` con l'intestazione, `dinf/dref` e le tabelle dei campioni), `mdat` con **solo i byte dei campioni delle tracce tenute**
  (calcolati da `stsc`, `stsz`/`stz2`, `stco`/`co64`). Ogni altra traccia (metadati, sottotitoli, timecode, `mebx`, `djmd`, `camm`, `gpmd`), `udta`,
  `meta`, `uuid`, `iods`, `tref` e ogni riquadro sconosciuto diventa `free` della stessa dimensione con il contenuto azzerato; `free`/`skip`/`wide`
  restano vuoti; la coda che non e' un riquadro si azzera. Niente si sposta: gli indirizzi restano validi, nessuna ricodifica. Si decide tutto prima di
  scrivere.
- **Motion photo**: la parte video (il primo MP4 dopo EOI) si riattacca ricostruita con le regole dei video, e il JPEG porta un XMP nuovo scritto qui che
  la dichiara (Google "MotionPhoto" con la `Container:Directory` e il vecchio "MicroVideo": solo la lunghezza). Il trailer SEF di Samsung non si copia.
- **HEIC**: i pixel diventano un JPEG in un file di lavoro, e il JPEG si ricostruisce come gli altri (senza EXIF: la rotazione e' gia' nei pixel).
- **Si ferma** (fallire chiusi, "Questo file non si può condividere senza posizione: condividilo con la posizione o annulla."): MP4 frammentato (`moof`,
  `mvex`), senza `moov` o con due, senza tracce audio ne' video, tabelle che puntano fuori da `mdat`, oltre i tetti (100.000 riquadri, 8 milioni di
  campioni); JPEG o PNG troncato o che non torna; motion photo il cui video non si ricostruisce.
- Tolti `ImageLocationStrip`, `IsoLocation`, `ImageXmp`, `XmpGpsScrub` e il vecchio `RawLocationRecheck`.

**Il controllo finale indipendente** (`strip/LocationTraces.kt`)
- Scritto da zero in un file suo: non usa niente dello stripper (ne' camminatori, ne' elenchi, ne' `RawFile` o gli aiuti sui byte); riconosce il formato
  da solo. Cerca nei byte del file finale: un blocco GPS dell'EXIF con almeno una voce (ovunque ci sia un EXIF: segmenti, `eXIf`, `EXIF`, `Exif\0\0` in
  `mdat` o in coda, intestazioni TIFF nei metadati, l'XMP dentro l'EXIF); nomi XMP con `gps`, `latitude`, `longitude`, `longtitude` in qualunque spazio
  dei nomi (anche come parole chiave dei testi PNG, anche in UTF-16, e l'XMP esteso **riassemblato per GUID**); le parole `latitude`, `longitude`,
  `iso6709`; coordinate ISO 6709 con i decimali; frasi NMEA; i blocchi `GPS5`/`GPS9` di GoPro e `freeGPS` di Novatek; `©xyz`, `loci`, `gps `; ogni traccia
  che non sia `vide` o `soun` (o con un formato di campione di metadati). Salta i dati compressi delle immagini; in `mdat` cerca solo cio' che non si
  trova per caso (coordinate con i decimali, NMEA, parole lunghe, marcatori). Lineare, con tetti di dimensione e di tempo (5 minuti). Un file che non
  legge fino in fondo e' illeggibile, e la condivisione si ferma.
- Lo stripper lo fa girare **sempre per primo** (non e' un lettore iniettato e non si toglie), poi i lettori iniettati, che restano almeno due per tipo:
  l'estrattore dell'app e `ExifInterface` (foto) o `MediaMetadataRetriever` (video).

**"Togli il luogo" (R12) e l'XMP costruito apposta (R13)** (`core-metadata/.../write/`)
- `GeoChange.Clear` toglie **tutto** il blocco GPS (anche ora e data GPS: un blocco con la sola ora e' ancora un blocco GPS) e la nota del produttore; poi,
  sul posto (`LocationClear`): le proprieta' del luogo dal pacchetto XMP principale (diventano spazi), l'XMP esteso e l'APP13 azzerati interi, nei PNG
  l'XMP in `iTXt`/`tEXt` pulito (CRC rifatto) e i testi compressi, "Raw profile type" o con un nome del luogo resi un chunk privato vuoto, nel WebP il
  chunk `XMP `, la parte video di una motion photo con `IsoClean`. Poi il rilevatore indipendente: se trova ancora qualcosa, o qualcosa non si sa pulire,
  `WriteUnsupported.FORMAT` (la correzione resta nell'app, come L40) e la copia non resta.
- `XmpLocationScrub`: nessuna espressione regolare. Per ogni `<` il nome in avanti, per ogni `=` all'indietro (256 caratteri); un nome e' del luogo se il
  suo nome locale contiene `gps`, `latitude`, `longitude`, `longtitude`, `altitude`, `location`, o e' un nome di luogo testuale (`City`, `State`,
  `Country`, `CountryCode`, `CountryName`, `Sublocation`, `ProvinceState`, `WorldRegion`). Si toglie l'attributo intero o l'elemento intero con cio' che
  contiene. Un tag, un valore o un elemento che non si chiude ferma subito; tetto di 1 MiB per pacchetto e 2 secondi. Il resto dell'XMP resta (e' una
  correzione, non una condivisione).

**Condividere (R4, MC-1, MC-2)** (`feature-integrations/.../share/ShareHolder.kt`, `app/.../integrations/AppShares.kt`, `ShareHostEffect.kt`)
- **Ogni "Condividi" passa da `AppShares`**, che sta nelle integrazioni (ci sono anche senza backup) e legge "Rimuovi la posizione" (`FiveBModule` la passa
  alle integrazioni). Due canali: il viewer (con il backup tramite `FiveBUi`, senza tramite una voce "Condividi" sola, `AppShares.viewerActions`) e la
  selezione della Libreria (`AppLibraryCommands`: con l'impostazione accesa la stessa preparazione senza posizione; spenta, gli URI del MediaStore come
  prima). La Libreria mostra l'avanzamento con "Annulla" e il foglio "Condivisione ferma" (`LibraryShare.kt`), con "Condividi con la posizione" solo come
  scelta esplicita. La scelta dell'app la lancia `ShareHostEffect` alla radice della galleria, anche senza backup; `FiveBLaunch.Share` non c'e' piu'.
- **MC-1**: spegnere l'avviso "Pronta" e' solo lo stato (`BackupMediaActions.hideShareNotice`). La copia consegnata passa al custode (`ShareHolder`), che
  la rilascia (revoca e cancellazione) quando l'altra app ha finito: **un minuto dopo il ritorno dell'utente nell'app** (un'activity dell'app che torna
  davanti dopo essere andata in pausa; il minuto e' per chi finisce di leggere in background, come Gmail che invia o il foglio traslucido di Telegram),
  **comunque dopo mezz'ora**, e **all'avvio successivo** (`ShareCache.sweepAtStart`, invariato). Una scelta dell'app che non parte, o "Annulla" prima del
  lancio, toglie subito la copia; "Annulla" e la chiusura di un foglio non toccano una copia gia' consegnata. Il ciclo di vita lo seguono le integrazioni
  (`registerActivityLifecycleCallbacks`); l'azzeramento rilascia tutto.
- **MC-2**: con l'impostazione accesa il viewer non offre "Copia" (metterebbe negli appunti l'indirizzo dell'originale).

**Scelte fatte qui (da confermare con l'utente se non vanno)**
- Nella copia condivisa restano **solo orientamento e data** (con il fuso, che e' una data e non un luogo): fotocamera, obiettivo, XMP (anche titoli,
  descrizioni e parole chiave), IPTC e nomi di luogo non escono piu'. Scritto in L41.
- Motion photo: il video resta, dichiarato da un XMP di Google scritto qui; come lo vede Samsung Gallery lo dice F42.
- Video: tracce timecode e sottotitoli si tolgono come i metadati; un MP4 frammentato non si condivide senza posizione.
- "Togli il luogo" toglie anche l'ora GPS, i nomi di luogo dell'XMP, l'APP13 e l'XMP esteso interi (la versione di prima resta sul NAS).
- Il margine dopo il ritorno e' un minuto, il tetto mezz'ora.
- Senza backup il viewer ha la voce "Condividi" del canale nuovo anche per un solo-NAS (lo scarica se il NAS risponde, altrimenti lo dice).
- Selezione della Libreria con l'impostazione accesa: al massimo 100 elementi (il tetto di `LocationFreeShare`), invece di 500.
- In `IntegrationsGraph` l'impostazione non legata vale "togli" (fallire chiusi); nell'app e' sempre legata.

**Test** (prima → dopo)
- `PosizioneNascostaTest` (core-metadata, fixture costruite a mano in `LocationFixtures`): con lo stripper di prima **10 rossi su 12**: DJI `djmd`,
  `camm` come seconda voce di `stsd`, Apple `mebx`, XMP `drone-dji`, XMP esteso spezzato, APP13, PNG `tEXt`/`iTXt`, motion photo con il GPS nel video e
  HEIC con un convertitore che si porta dietro l'XMP uscivano `Stripped` **con la posizione dentro**; GoPro `gpmd` veniva rifiutato invece di essere
  pulito. Verdi da subito i due del rilevatore (trova la posizione in ogni fixture non pulita, comprese quelle di prima; niente sui file puliti). Dopo:
  **12 verdi**, con i campioni audio e video identici byte per byte, i campioni dei metadati azzerati, il `moov` con `vide` e `soun`, le foto che si
  decodificano.
- Nuovi: `IsoCleanTest` (11: `moov` prima del `mdat`, piu' campioni per chunk e `stz2`, 64 bit, code, frammentati, tabelle fuori da `mdat`, troppi
  riquadri, regione incorporata, `MediaMetadataRetriever`), `XmpLocationScrubTest` (5, R13: 1 MiB di `<GPSA` in meno di un secondo), `TogliIlLuogoTest`
  (4, R12), `ShareHolderTest` (4, MC-1), `CondividiSenzaPosizioneAppTest` (6, :app: MC-1, R4, MC-2), `ViewerBackupMenuTest` (+2).
- Aggiornati alla regola nuova: `DefaultLocationStripperTest` (la fotocamera e l'XMP non restano; un video di prova ha una traccia video), `LocationFreeShareTest`
  (stesso motivo), `AndroidHeicToJpegTest`, `FiveBButtonsTest` (le porte come le lega l'app). Tolti `IsoLocationTest` e `XmpGpsScrubTest` con il codice.
- Esiti dei moduli (20/10, `--max-workers=2`): core-metadata 281 test, 0 falliti; feature-integrations 445, 0; feature-viewer 484, 0 (14 saltati, come
  prima); feature-library 528, 0; `:app` 427, 1 fallito fuori perimetro (`FieldProbeFlowTest.ilPermessoDiScritturaRiscriveLaFotoConISuoiStessiByte`,
  la prova guidata T-09 che un'altra correzione sta cambiando in quelle ore); i test di `:app` toccati qui (`CondividiSenzaPosizioneAppTest`,
  `AppLibraryCommandsTest`, `FiveBButtonsTest`, `FiveBIntegrationTest`) verdi.

**Limiti e punti aperti**
- Selettore e provider dei documenti consegnano ancora gli originali (informativa di R4, fuori perimetro); la pagina Condivisioni di feature-settings dice
  "Quando condividi con un'altra app" e non lo precisa (non toccata). **Superato il 20/10**: vedi «Correzioni 5B: integrazioni», in fondo (decisione dell'utente).
- Se il processo muore mentre l'altra app non ha ancora letto e la lettura lo fa ripartire, la pulizia d'avvio puo' togliere la copia: e' la regola
  "comunque all'avvio successivo"; di solito l'altra app ha gia' aperto il file. Da guardare in F42.
- Il rilevatore non riconosce posizioni binarie orfane in `mdat` che non siano GoPro o testo (es. `camm` senza traccia): le azzera lo stripper, e i test lo
  controllano sui byte.

**Da provare sul campo (F42, con `exiftool` dal PC)**: condividere con "Rimuovi la posizione" a Gmail verso se stessi, poi `exiftool -a -G1 -ee -gps:all
-xyz -location* -xmp:all -iptc:all` sul file ricevuto (deve essere vuoto di posizione, e restare solo orientamento e data):
- dal viewer e dalla **selezione della Libreria**, con il backup e senza (account senza NAS);
- una foto e un video di **drone o dashcam** se la famiglia ne ha, una foto passata da **Lightroom o Photoshop** (XMP esteso, APP13), un **PNG** con
  testi, una **motion photo di Samsung** (la parte video si vede ancora in Samsung Gallery e in Google Foto?), un **HEIC**, un **video di iPhone** (MOV);
- un video normale della fotocamera Samsung: si riproduce, ha audio, la durata e' giusta, nessuna traccia persa che servisse;
- **WhatsApp e Telegram**: scegliere la chat dopo piu' di 3 secondi e dopo un minuto, tornare subito nell'app dopo l'invio, mandare un video grande: il
  file arriva intero; dopo il ritorno, un minuto dopo, `cache/share/` e' vuota;
- "Correggi data e luogo" → togli il luogo su una foto con XMP GPS (anche di drone): `exiftool` sulla versione nuova sul NAS non mostra il luogo.

## Correzioni 5B: configurazione e Diagnostica (20/10)
Le voci R6, R7, R18, R19 della revisione di sicurezza (`docs/review/5b-fase5b.md`) e LA-1 ... LA-8 della verifica funzionale (`docs/review/funzionale-5b.md`),
ognuna con il suo "Stato" nel documento. Metodo delle correzioni di prima: un test che falliva sul codice di prima (visto rosso: R18, R19 `adminContext`,
LA-1 e LA-2 rimettendo per una prova i file di prima; gli altri usano contratti nuovi e non compilavano), la correzione, i fake allineati al vero. Questa
correzione ha toccato solo `core-family`, `core-personal`, `feature-onboarding`, `feature-settings`, `feature-diagnostics`, le prove guidate di debug,
`tools/check.ps1` e, in `:app`, `graph/` e i contatori di `integrations/`: **LA-3 e LA-4 non sono state corrette qui** (vedi sotto).

**Decisioni**
1. **R6, dove e quando si tolgono i dati del vecchio account: alla fine dell'onboarding (`finish`), non a "Configura di nuovo".** Tre vincoli
   decidono. (a) ON-4 e ON-5 dicono che chi rientra con lo stesso account sullo stesso NAS non perde nascosti, preferiti e miniature: a
   `startOver` non si sa ancora chi rientra. (b) La Libreria esiste solo con `SetupState.Ready`, che vuole l'onboarding **finito**: fino a "Vai
   alla Libreria" niente del telefono si vede ne' si pubblica, quindi basta togliere i dati un passo prima, in un punto solo che tutti i percorsi
   (fondatore, QR, senza QR, admin recuperato, secondo admin) attraversano. (c) Una galleria fermata non riparte nello stesso processo (R7 della
   revisione 3): meglio che i dati si tolgano quando la galleria non e' mai partita. `startOver` salva un **riassunto** di account e GUID del NAS che
   lascia (`OnboardingSnapshot.previousOwner`: SHA-256 troncato di "GUID, account" in minuscolo, mai l'account in chiaro; sopravvive a un
   cambio di percorso e a un secondo "Configura di nuovo" prima di scegliere l'account); `finish` lo confronta con quello di chi e' rientrato.
   Uguale: il ricordo si toglie e i dati restano. Diverso (anche solo il NAS): si chiede a `:app` di toglierli (`OnboardingPorts.previousData`), e se non
   ci riesce la fine si ferma (`OnboardingProblem.LOCAL_DATA_NOT_REMOVED`, "Riprova" ripassa da `finish`) e la Libreria non si apre.
2. **R6, cosa si toglie.** `AppLocalDataEraser.eraseAccountData`: il database della galleria (catalogo del telefono e dei solo-NAS, diari del
   backup, di "Libera spazio", delle sessioni di modifica e dei file in arrivo), replica e pacchi dell'indice, miniature e anteprime, dati
   personali, outbox, stato del NAS, memoria del backup, **file preparati delle versioni e copie dei file ricevuti** (`versions/`, `incoming/`),
   cache (anche di modifica e condivisione) e impostazioni del backup. **Non** si tocca: l'onboarding in corso, l'account del NAS, la casa, il controllo di
   WireGuard, le chiavi, le preferenze del telefono; **mai** le foto del telefono ne' il NAS. Si chiude solo il database della galleria (gli altri,
   WorkManager in testa, restano aperti). `eraseAll` (l'azzeramento) ora usa lo stesso pezzo e **toglie anche `versions/` e `incoming/`**: l'elenco di
   P7 non li aveva, e un telefono "azzerato" teneva i file preparati e le copie ricevute (trovato strada facendo).
3. **R6, il riavvio.** Se in quel processo la famiglia e' stata pronta almeno una volta (`AppGraph.galleryMayRun`) la galleria puo' essere partita:
   "Configura di nuovo" riavvia il processo (`OnboardingPorts.afterStartOver`, come dopo un azzeramento, ON-11), cosi' il catalogo non conserva il
   proprietario di prima (`MediaCatalog` lo legge una volta) e al momento di togliere i dati nessuna galleria e' viva. Se per un riavvio non riuscito c'e'
   ancora, i dati si tolgono fermandola e il processo si riavvia subito dopo (`AppGraph.restartSoon`). Nei test il riavvio e' un contatore.
4. **R6, l'impronta di casa si dimentica a `startOver`.** Il fingerprint appartiene alla famiglia che si lascia, ogni percorso d'ingresso la registra
   di nuovo, e `DefaultJoinFlow.mergeHome` la univa a quella firmata del NAS nuovo. Non e' la causa di R6 ma lo stesso difetto (R6 la nomina).
5. **R7, il tasto non parte da solo.** La pagina "fuori dalla famiglia" apre un foglio di conferma (`askStartOver` → `StartOverSheet`, con il suo host dei
   pop-up: in onboarding la radice non ne ha uno); "Annulla" e chiudere non confermano; "Riprova" dopo un problema riapre il foglio. Per un admin
   (`OnboardingCoordinator.holdsFamilyKey`: fondatore, admin recuperato o secondo admin, o approvato admin nel registro; nel dubbio si') il foglio dice che
   si perde la chiave di famiglia, che senza non si approvano dispositivi ne' si cambia la famiglia, e che si riprende solo con il kit di recupero e il
   suo codice ("Recupera l'admin" dal benvenuto), e "se non li hai non continuare"; se la causa e' "lo stato non c'e' piu'" ricorda che puo' essere un guasto
   del telefono. Lo stesso avviso e' nel foglio delle Impostazioni ("Rientra con un QR nuovo"). I testi sul cosa resta dicono che nascosti, preferiti e
   miniature restano solo con lo stesso account sullo stesso NAS.
6. **R7, un Keystore che non risponde non distrugge niente.** `startOver` chiede `hasDeviceKey()` prima di toccare qualsiasi cosa: se lancia, `KEYS_NOT_REMOVED` e
   lo stato resta com'era. Non cambia come `TrustEngine` calcola lo stato (con un Keystore che lancia resta `NotConfigured`, fail-closed): la pagina puo'
   ancora comparire in quel caso, ma non distrugge niente senza un gesto confermato e senza un Keystore che risponde. A freddo la radice non mostra la pagina prima del primo calcolo
   dello stato (`SetupState` resta `Loading` finche' `AppGraph.start()` non ha finito) e vuole l'onboarding finito. Distinguere "Keystore guasto"
   da "non configurato" con uno stato nuovo (`StaleReason`, pagina `Blocked`) toccava `TrustEngine`, `SetupResolver` e quattro schermate: lasciato fuori.
7. **R19, le chiavi non si fingono tolte.** Ogni distruzione (chiave del dispositivo, poi chiave di famiglia: l'ordine di prima) si ripete fino a tre volte con
   300 ms; la chiave del dispositivo si ricontrolla (`!hasDeviceKey()`), quella di famiglia non ha un "ci sei?" (la cassaforte la toglie o lancia). Se non riesce, `KEYS_NOT_REMOVED`
   e niente altro (account, password, stato fidato, avanzamento) si e' toccato: si riprova dalla stessa pagina. **`RegistryWriter.adminContext` vuole anche il
   segno admin del registro firmato** per il dispositivo (prima bastavano "approvato" e la chiave nella cassaforte): una chiave rimasta per sbaglio su un ex admin
   rientrato come familiare, con un id nuovo, non firma piu' la configurazione. Il segno e' informativo per `core-trust` ("il potere vero e' la chiave"), qui chiude
   una porta in piu'; fondazione, recupero e secondo admin lo scrivono `true`.
8. **R18, il rinnovo senza QR.** `DefaultJoinFlow.start` nel rinnovo (dispositivo gia' registrato e in attesa) usa il GUID gia' fissato come atteso se l'invito non ne ha uno
   (la `NEGOTIATE` lo confronta **prima** della password), rifiuta un invito con un altro GUID senza toccare la rete (`IDENTITY_MISMATCH`), e la versione minima del registro non scende:
   il piu' alto fra quella dell'invito, quella fissata alla prima richiesta e quella dello stato fidato della stessa famiglia (`JoinRecord.minRegistryVersion` e l'ancora di
   `RegistrySync` usano quella). La prima richiesta senza QR resta a 0 (L22, non cambia).
9. **LA-1, le chiavi dei contatori una per una.** `ExportKeys` ha le 55 chiavi dei contatori (`IntegrationCounter`, `ReceiveCounter`, `EditCounter`, `ShareCounter`) piu' `entryAllowed` e
   `receiveReady`, scritte una per una come tutte le altre: **niente famiglia a regex** (una famiglia `share_*` avrebbe fatto passare anche `share_mario_rossi`). Un contatore nuovo senza
   la chiave fa fallire `IntegrationsCountersExportTest`, che confronta le enum con l'elenco. `IntegrationsGraph.counterExport` e' la funzione che costruisce i valori.
10. **LA-2, la copertina fra dispositivi per impronta.** `SimilarCopiesChoices` ritrova il media di una voce `similar_cover` come "Separa" (una sola classe, `Locator`: per id, anche tramite un
    alias; altrimenti per impronta **solo se indica un media solo**; un id che non e' un'identita' non si cerca nemmeno per impronta); lo stesso nella proiezione per le voci dello stato
    (`applySimilarCover`). `chosenCover`, `saveCover` (che spegne la voce accesa dell'altro dispositivo) e il rilevatore di core-library leggono cosi' la scelta con le identita' di qui. Il
    secondo sintomo ("se il gruppo si forma dopo l'arrivo della voce la scelta si perde") era lo stesso: il rilevatore non ritrovava la voce.
11. **LA-5, il secondo cancello nel dex di release.** `tools/check.ps1` (passo "D12 e R2 nella release") prova anche `IncomingGate.stateOf`: chiama `backupMayWriteTo` e `backupMayWriteAs`, risponde
    `TEST_ONLY` dopo ognuna ed e' istanziato da una classe del backup. `app/proguard-rules.pro` lo tiene come tiene `AppBackupGate`. Provato il lettore del dex su un `dexdump` finto (cancello a
    posto, senza decisione per account, senza `TEST_ONLY`, solo `AppBackupGate`): le quattro risposte sono quelle attese. Il passo vero (APK di release) lo fa `check.ps1`.
12. **LA-6, LA-7, LA-8, le prove guidate.** (LA-6) "Azzera i numeri" azzera le misure e **tiene le scelte** (forma, latenza, numero di file, modo di T-15); la schermata le rende esplicite all'apertura (`seedChoices`),
    cosi' il resoconto dice quale forma e' stata usata anche senza un tocco su un chip. (LA-7) Il riferimento di T-09 e T-16, le righe cestinate, la foto in scrittura e la copia in modifica stanno anche su file
    (`FieldProbeStore.saveSession`, soli numeri e il nome di un file di prova, mai nel resoconto; il nome si rilegge solo se ha la forma `probe-<id>.jpg`); cosa si aspetta dal dialogo (`Pending`) e'
    un `rememberSaveable`. (LA-8) Il messaggio di un tocco compare in fondo alla sua sezione; `picker_*` e `t15.*` di ogni ricezione hanno un indice (una riga per app); `docs_caller` ha un valore per chiamante
    diverso (`recordDistinct`); `serve_first_read_ms` e' per apertura; lo store del processo scrive il file in differita (150 ms, fuori dalla lettura misurata) invece di riscriverlo fino a quattro volte a
    ogni `onRead`; lo store dei test scrive subito, come prima. Lo store scrive subito (`flush`) quando una ricezione finisce o chiude in fretta, perche' T-15 uccide il processo apposta.
    **Effetto di R3 sulla prova T-09 (riscrittura):** il riscrittore ora non scrive se la riga ha gia' i byte che deve avere, quindi la riscrittura "con gli stessi byte" della prova non scriveva piu' niente (e il test
    `FieldProbeFlowTest.ilPermessoDiScritturaRiscriveLaFotoConISuoiStessiByte` non vedeva piu' il permesso negato). La prova riscrive la foto di `mcg-prova` con **un byte in piu' in coda** e subito dopo la rimette
    com'era (due scritture vere, due riletture; `FieldProbeMath.rewriteCode` conosce `RowChanged` = 7, e 8 = "non rimessa com'era"). Da sapere prima di F34-F36: in caso di errore fra le due la foto di prova resta
    con un byte in piu' (il riscrittore ha la sua copia di sicurezza e il suo diario).
13. **LA-3 e LA-4: non corrette.** LA-3 (le versioni di E-E7 restano aperte in debug) sta in `FiveBGraph` (i candidati di `Pictures/MyCloud Gallery` non passano dalla recinzione D13) e LA-4 (il possibile
    andirivieni fra i lavori) in `work/BackupWork.kt` e `BackupGraph.hasWork`: file dell'agente delle versioni o fuori dal mio perimetro, in parallelo. La patch di LA-3 (filtrare i candidati extra con
    `fence.allowsPhoneFolder`) e la lettura di LA-4 sono nei due documenti.

**Modifiche ai contratti e al codice condiviso**
- `core-family`: `OnboardingCoordinator.holdsFamilyKey()`; `OnboardingProblem.KEYS_NOT_REMOVED` e `LOCAL_DATA_NOT_REMOVED` (due schede in `ProblemPanel`, una voce in `Feedback` delle Impostazioni);
  `OnboardingSnapshot.previousOwner` (campo `prv` del file locale, versione 1 invariata: un file di prima si legge come prima) con `OwnerStamp`; `OnboardingPorts.previousData` (`PreviousAccountData`,
  di serie `KeepPreviousAccountData`) e `afterStartOver`; `OnboardingRules.choosePath` tiene `previousOwner`; `RegistryWriter.adminContext`; `FakeOnboardingCoordinator.keysStuck` e `holdsFamilyKey`.
- `feature-onboarding`: `RemovalUi.confirming`/`holdsKey`, `OnboardingViewModel.askStartOver/cancelStartOver` (`startOver` vuole il foglio aperto), `StartOverSheet`, `OnboardingSheetHost`, `RemovedScreen(onAskStartOver, onCancelStartOver, onStartOver)`.
- `:app`: `AppGraph(restart)`, `galleryMayRun`, `restartSoon`; `AppLocalDataEraser.eraseAccountData`, `AppPreviousAccountData`; `GraphModule` passa `AndroidProcessRestart`; `IntegrationsGraph.counterExport`;
  `ExportKeys` (feature-diagnostics); `app/proguard-rules.pro`.
- `core-personal`: `SimilarCopiesChoices.Locator`, `CatalogPersonalProjection.applySimilarCover`.
- Prove guidate (debug): `FieldProbeStore` (`flush`, `recordDistinct`, `seedChoices`, sessioni, scrittura differita), `ProbeRowMarks`, `FieldProbeT09`/`FieldProbeT16` che si ripristinano.

**Sicurezza: cosa e' stato toccato, e perche' non si indebolisce**
- *Nessun controllo e' stato tolto o allentato.* Aggiunti: la conferma di "Configura di nuovo" dalla pagina "fuori dalla famiglia"; la sonda del Keystore e la verifica delle chiavi tolte (prima ignorate con `runCatching`); il segno admin
  in `adminContext`; il GUID noto e la versione minima non decrescente nel rinnovo; la tolta dei dati locali di un altro account; il secondo cancello nel dex di release.
- *`startOver` e' piu' severo di prima in un solo punto che sembra il contrario:* adesso **si ferma** se le chiavi non si tolgono, dove prima diceva "fatto" (R19). Chi ha un Keystore guasto non puo' piu' "configurare di nuovo" finche' non risponde: e' voluto.
- *Il ricordo di chi c'era prima* sta nel file locale dell'avanzamento come riassunto a senso unico (SHA-256 troncato): non rivela l'account, serve solo a un confronto.
- *La tolta dei dati locali* cancella dati dell'app (database, cache, replica, personali) e mai le foto del telefono ne' il NAS; non parte se chi rientra e' la stessa persona sullo stesso NAS.
- *ExportKeys* resta un elenco chiuso: 57 chiavi nuove, tutte di soli numeri o flag, scritte una per una.

**Prove**
- `core-family` (261 test verdi, 18 di questa correzione): `CorrezioniConfigurazione5BTest` (11: R6, R7, R19 sul coordinatore vero), `CorrezioniIngresso5BTest` (6: R18, i primi 5 rossi sul codice di prima), `CorrezioniAdmin5BTest` (1: R19, `adminContext`, rosso prima).
- `feature-onboarding` (696 test verdi): `CorrezioniConfigurazione5BViewModelTest` (5: il tasto non parte da solo, annulla, admin, riprova), `StartOverSheetTest` (5: la pagina apre la conferma; il foglio di un admin dice cosa si perde e il kit); `feature-settings` (531): il messaggio delle chiavi.
- `core-personal` (146 test verdi): `SimilarCopiesProjectionTest`, 5 casi nuovi sulla copertina con id diversi (4 rossi sul codice di prima). `feature-diagnostics`: 323 verdi.
- `:app` (grafo vero, simulatore SMB; 420 test verdi, provati in una copia pulita di `HEAD` con le sole mie modifiche perche' `feature-backup`, `feature-viewer` e `core-metadata` dell'albero di lavoro erano a meta' dei
  lavori in parallelo): `CorrezioniConfigurazione5BAppTest` (5: altro account: dati tolti e restart; stesso account: restano; nessun riavvio senza galleria viva; `eraseAccountData` non tocca l'onboarding; i nomi delle
  cartelle), `IntegrationsCountersExportTest` (4, LA-1: 3 rossi con `ExportKeys` di prima), `FieldProbeCorrezioni5BTest` (12: LA-6, LA-7, LA-8).
- `tools/check.ps1`: lettore del dex provato su un `dexdump` finto; sintassi PowerShell verificata. **Non eseguito il passo vero** (serve l'APK di release): lo fa `tools\check.ps1`.

**Residui**
- LA-3 e LA-4 (sopra). R6: i dati locali di un altro account si tolgono alla fine dell'onboarding, non a "Configura di nuovo": fra i due momenti restano sul telefono ma la Libreria non c'e'. Il filtro
  `NasPersonalSync.publish` per dispositivi dell'account (proposto da R6) non serve piu' con i dati tolti.
- R7: un Keystore che lancia continua a dare "non configurato" in `TrustEngine` (vedi decisione 6).
- Il riavvio del processo di "Configura di nuovo" si vede come una chiusura dell'app: da provare sul telefono.

**Da provare sul campo (S25 e tablet di prova)**: (1) revocare il tablet, "Configura di nuovo" dalla pagina "fuori dalla famiglia": deve chiedere conferma, e sull'admin dire della chiave e del kit; (2) rientrare con **un altro account**
(`test2` → `test`): la Libreria parte vuota di cio' che era dell'altro, i nascosti di prima non si vedono, niente passa nell'area privata nuova; con **lo stesso** account i nascosti restano; (3) il riavvio dopo "Configura di nuovo" da un
tablet che aveva la Libreria viva; (4) Diagnostica → Esporta durante F34-F42: `INTEGRATIONS_COUNTERS` ha i numeri; (5) "Mostra come principale" sul tablet con id diversi (F31-bis); (6) le prove T-09, T-14, T-15, T-16 con la schermata ricreata
(ruotando) e "Azzera i numeri"; (7) `tools\check.ps1` su un APK di release: il passo del cancello dei file in arrivo.

## Correzioni 5B: versioni e correzioni (20/10)
Fonte: `docs/review/5b-fase5b.md` (R3, R9, R10, R16, R17) e `docs/review/funzionale-5b.md` (VE-1…VE-10, MC-3…MC-6, poi LA-3). Perimetro: `core-mediastore`,
`core-backup/.../versions/**`, `.../corrections/**` e i contratti che li servono (`contract/PhoneWrites.kt`, `Versions.kt`, `Corrections.kt`),
`feature-backup/.../versions/**`, `feature-viewer/.../menu/BackupActions*`, `feature-integrations/.../edit/DefaultEditSessions.kt` (MC-6), in `:app`
`backup/FiveB*`, `backup/BackupGraph.kt`, `main/FiveBHostEffect.kt`, `work/` e la prova T-09. Riscrivere una riga del telefono e' il solo punto della 5B
che tocca byte che non sono una copia: prima poteva sovrascrivere una modifica mai salvata, lasciare una foto a meta' dopo una morte del processo, scrivere
una copia oscurata, e il foglio diceva "non si perde niente". Accanto, i lavori delle versioni non partivano quando dovevano (video, scambi, modifiche di un
solo-NAS) e alcuni tasti non facevano niente o chiudevano l'app.

**Decisioni**
1. **La regola del riscrittore** (R3). `PhoneMediaRewriter.replace(ref, file, expectedSha256, currentSha256)`: prima di scrivere, i byte attuali della riga
   si copiano (la copia di sicurezza) e se ne calcola lo SHA-256. Se sono gia' quelli nuovi, e' fatta (una risposta persa); se non sono `currentSha256`,
   `RowChanged` e **niente si scrive**; solo con i byte attesi si scrive, si rilegge, e se la rilettura non torna si rimette la copia. Il chiamante passa
   come `currentSha256` solo l'impronta di una versione **al sicuro** (impronta intera nota e copia privata verificata, `DefaultVersionKeeper.isSafe`).
   Fra "metterla al sicuro prima" e "fermarsi e dirlo" si e' scelto di **fermarsi e dirlo**: `availability`, e di nuovo `restoreOriginal`, rispondono
   `CURRENT_NOT_SAFE` se la versione di adesso o una di quelle sulle righe da riscrivere non e' al sicuro; il backup la mette al sicuro con il suo giro (per
   le correzioni c'e' anche "Metti al sicuro adesso"). Lo scambio sposta in `Versioni/` solo la versione **attuale** (`VersionPlanning.swapTo(from)`). Una
   riga cambiata dopo l'ultima lettura: lo scambio sul NAS c'e' (nessun file perso) e il telefono tiene la sua (`PhoneKeptReason.PHONE_CHANGED`, detto
   nel foglio); una correzione si ferma alla riga cambiata (`CorrectionFailure.PHONE_CHANGED`). Copre il catalogo in ritardo, l'`_ID` riciclato, la seconda
   riga di una correzione.
2. **Il foglio dice il vero.** Il riepilogo di "Ripristina originale" dice che la versione di adesso ha gia' una copia controllata e va in `Versioni/`, che
   sul telefono c'e' la versione modificata e si rimette com'era solo se e' ancora quella al sicuro; dopo, ogni ragione per cui il telefono tiene la
   modifica ha la sua frase, e "Ripristino avviato" quando lo scambio non e' ancora finito.
3. **Copia di sicurezza, diario e recupero** (R9). In `noBackupFilesDir/rewrite/` (privata, fuori dal backup di Android): `<chiave>.undo` (i byte di prima,
   sincronizzati) e `<chiave>.journal` (URI, impronta di prima e nuova; temporaneo sincronizzato e rinomina), scritti **prima** di toccare la riga e tolti
   solo con la riga a posto. `recover()` gira all'avvio (`FiveBGraph.atStart`, prima di tutto il resto) e prima di ogni riscrittura: riga nuova o com'era
   = a posto, sparita = niente, altrimenti la copia (solo se ha l'impronta di prima) si rimette. Senza permesso il diario aspetta. Un `Mutex` di processo:
   una riscrittura alla volta.
4. **Byte originali** (R10). `setRequireOriginal` per foto **e video**, sulla copia di sicurezza, sulla rilettura e nel recupero; senza
   `ACCESS_MEDIA_LOCATION` l'apertura lancia e diventa `Failed(ORIGINAL_UNREADABLE)` senza toccare niente. Nessuna eccezione esce dal riscrittore.
5. **Un solo URI per richiesta e scrittura** (VE-2). `AndroidPhoneMediaIntents.requestUriOf`: la collezione dal `MEDIA_TYPE` della riga letto adesso
   (`MediaStore.Images`/`Video`), poi dal MIME; senza nessuno dei due nessuna richiesta, mai `files/<id>` (AOSP lo rifiuta con una
   `IllegalArgumentException`). Vale per cestino, eliminazione, scrittura (anche "Libera spazio" senza MIME) e per il riscrittore. Le `create*Request` sono
   in un `try` (anche in `AppPhoneSystemDialogs`) e i fogli dicono "Il telefono non ha accettato la richiesta di permesso". La prova T-09 chiede il
   dialogo con il riferimento **senza tipo**, come i fogli dell'app (`FieldProbePhone.untypedRefOf`), e riscrive con il tipo.
6. **Gli scambi senza conferma nascono solo dai fatti del telefono** (R16): `planRealign` vuole una riga del telefono sulla versione attuale (presente o
   tolta dopo). Un ritorno visto solo dalla scansione del NAS (un altro account nell'area comune) non sposta niente nell'area privata. La prima versione
   ("riga presente") era troppo stretta: il banco delle interruzioni l'ha mostrato (una foto riportata all'originale e poi liberata non si riallineava).
7. **La franchigia del congelamento e' stretta** (R17): un `fileId` e' esente solo se la rinomina e' dell'app (archiviazione, pubblicazione, ripristino; mai
   `LINKED_EXISTING`), recente (7 giorni d'ora del NAS dall'ultimo passo del diario; senza l'ora del NAS nessuna franchigia) e il catalogo non l'ha ancora
   seguita. Il contratto `OwnChanges` di core-nasmedia resta per `fileId`: la coppia (`fileId`, timbro) proposta dalla revisione si approssima cosi' senza
   uscire da core-backup.
8. **I lavori delle versioni seguono il tipo del media e partono da soli.** VE-1: `NasVersionEngine.run(…, kinds, only)`, un video con le regole dei
   video; `QUICK_NEW_PHOTOS` solo con le versioni pianificate dal giro. VE-10 e MC-3: `BackupJobs.versionWork(kind)` (`KEEP`) dopo uno scambio pianificato,
   dopo una modifica di un solo-NAS e alla fine di una scansione del NAS con versioni ferme per il PC. VE-9: chi aspetta il PC non conta fra i "in coda".
9. **Controlli fuori dal turno** (VE-8): `VersionKeeper.precheck` e `Corrections.precheck` rispondono subito a piano, conferma e permesso; il turno
   esclusivo si aspetta solo dopo il permesso, e se non si libera in 15 minuti lo si dice (`BACKUP_BUSY`, `BUSY`). VE-7: `PAUSED` e `TEST_ONLY` hanno la loro
   frase.
10. **"Ruota" solo dove si scrive** (VE-4): per HEIC e RAW il menu non la offre, il foglio non ha la sezione e il servizio risponde
    `ROTATION_NOT_WRITABLE`. La rotazione del catalogo applicata a miniature e viewer (l'altra strada) toccherebbe core-imaging: non fatta.
11. **"Metti al sicuro adesso"** (VE-3): `Corrections.safeguard` (in `:app` `BackupGraph.safeguard`): la verifica sul momento di un solo-NAS, "Fai ora" per
    una riga in una sorgente, `NOT_IN_SOURCES` fuori; il foglio aspetta e si riapre da solo.
12. **Modifica**: MC-4, prima di aprire l'editor per un solo-NAS si chiede se la modifica potra' diventare una versione (`NasVersionEngine.canPlanStaged`);
    `recordEdited` non e' cambiato. MC-5: "Scarica e modifica in Galleria" (`EDIT_DOWNLOAD`) e frasi per tipo di media quando nessuna app accetta. MC-6:
    una sessione `PHONE_GALLERY` senza l'ora del NAS si misura con il monotono del processo.
13. **I fogli**: VE-5, il foglio si azzera solo all'apertura (`SheetOpening`, `rememberSaveable`), non alla ricreazione; VE-6, il piano rifiutato e gli
    avvisi di "Elimina…" si dicono alla radice, subito.

**Modifiche ai contratti e al codice condiviso**
- `core-mediastore`: `PhoneMediaRewriter.replace(ref, file, expectedSha256, currentSha256)`, `RewriteResult.RowChanged`, `RewriteRecovery` e `recover()`,
  `RewriteCodes.ORIGINAL_UNREADABLE`; `AndroidPhoneMediaIntents.requestUriOf`; `FakeMediaStore.RequestCollection`.
- `core-backup`: `PhoneRewriter.replace(ref, mime, file, expectedSha256, currentSha256)`, `PhoneRewrite.RowChanged`; `RestoreAvailability.CURRENT_NOT_SAFE`,
  `PAUSED`, `TEST_ONLY`; `PhoneKeptReason.PHONE_CHANGED`; `VersionKeeper.precheck`; `CorrectionReadiness.PAUSED`, `TEST_ONLY`, `BUSY`,
  `ROTATION_NOT_WRITABLE`; `CorrectionFailure.PHONE_CHANGED`; `Corrections.precheck`, `Corrections.safeguard` (`SafeguardStart`);
  `VersionJournal.inShareSince`; `NasVersionEngine.run(…, kinds, only)` e `canPlanStaged`; `VersionFranchise(journal, catalog, nasNowMs, window)`.
- `feature-backup`: `VersionsMessage.SYSTEM_DIALOG_FAILED`, `BACKUP_BUSY`; `VersionsUi.Restored(plan, kept)`; `CorrectionUi.NeedsSafeFirst` (con le sue
  varianti) e `Safeguarding`; `CorrectionMessage.SYSTEM_DIALOG_FAILED`; `SheetOpening`; `CorrectionViewModel` non prende piu' `BackupActions`.
- `feature-viewer`: `BackupMenuAction.EDIT_DOWNLOAD`, `BackupMenuCapabilities.editDownload`, `BackupMenuTarget.rotatable`/`onPhone`,
  `BackupMediaActions.canRotate`.
- `feature-integrations`: `DefaultEditSessions` (scadenze monotone per `PHONE_GALLERY`, `MONOTONIC_MARK`).
- `:app`: `FiveBGraph(onVersionWork, safeguard)`, `editVersionReady`, `sourceChangedCurrentMedia`; `BackupGraph.safeguard`, `afterNasScan`, il conteggio dei
  lavori per tipo; `BackupJobs.versionWork`; `NasScanWorker` → `afterNasScan`; `FiveBUi.rootNotice`, `deleteRefused`, `canRotate`, `startEdit(download)`;
  quattro stringhe in `strings_integrations_app.xml`.

**Finti allineati** (il difetto lo nascondevano loro)
- `FakeMediaStore`: il permesso vale per (collezione del tipo della riga, volume, `_ID`) e una richiesta `files/` e' rifiutata, come AOSP; `FakeMediaProvider`
  e `WritableMediaProvider` rifiutano gli URI senza tipo e danno i permessi per URI; test di parita'
  `AndroidSystemRequestsTest.ve2_ilTelefonoFintoSceglieLaCollezioneComeQuelloVero`.
- `FakePhoneMediaRewriter` nello stesso ordine del vero (impronta, sparita, gia' fatta, cambiata, permesso); `FakePhoneRewriter.rowContents` e
  `VersionWorld` con il controllo di R3; `FakePhoneSystemDialogs.failure` (il dialogo che lancia); `InMemoryVersionJournal` con l'ora del NAS.

**Sicurezza: cosa e' stato toccato, e perche' non si indebolisce**
- Nessun controllo tolto. Il riscrittore ha un controllo in piu' (l'impronta attuale), una copia di sicurezza sincronizzata prima di ogni scrittura, il
  recupero e un solo scrittore alla volta. La copia di sicurezza contiene i byte originali (con la posizione): sta in `noBackupFilesDir`, si toglie appena
  la riga e' a posto, e si rimette solo se ha l'impronta giusta.
- R16 e' piu' severo di prima ("una riga del telefono sulla versione attuale" contro "qualunque ritorno"), ma meno della prima versione di questa
  correzione ("riga presente"): il motivo e' sopra (decisione 6).
- R17: la franchigia esente meno di prima in ogni caso (mai senza l'ora del NAS, mai oltre una settimana, mai i file trovati, mai dopo che il catalogo ha
  seguito).
- I dialoghi di sistema che lanciano non chiudono piu' l'app; nessuna richiesta parte con un URI che il sistema rifiuterebbe.

**Prove**
- Ogni correzione ha un test nuovo, rosso sul codice di prima (ripristinato a mano per la prova e poi tolto): `core-mediastore`
  (`MediaStorePhoneMediaRewriterTest` r3/r9/r10/ve2, `SystemRequestsFakeTest`, `AndroidSystemRequestsTest`), `core-backup`
  (`Revisione5BVersioniTest`: r3 ×4, r16, r17, ve1, ve4, ve7, ve8, mc4), `feature-backup` (`VersionsSheetTest`, `VersionsViewModelTest`,
  `CorrectionViewModelTest`, `CorrectionSheetTest`, `SheetOpeningTest`), `feature-viewer` (`BackupActionsTest` ve4/mc5), `feature-integrations`
  (`DefaultEditSessionsTest.mc6_*`), `:app` (`Correzioni5BVersioniAppTest`: ve1, ve3, ve9, ve10 ×2, mc3, r9, la3 ×2, sul grafo vero; `FieldProbeFlowTest` per T-09).
- Banchi delle interruzioni (`tools/chaos/area.ps1 -Mode backup`): `-Stage jvm` 2000 giri sull'esecutore di riferimento e 2000 sul motore vero, 0
  violazioni, nessun passo saltato; `-Stage smb` 600 giri sul simulatore SMB, con l'esecutore di riferimento e con il motore vero, 0 violazioni.

**Residui**
- VE-6 in parte: la richiesta di "Elimina…" non si toglie se il foglio si chiude senza conferma (lo stato del controller della 4B e' interno a
  feature-viewer); una richiesta nuova la sostituisce.
- MC-3: la versione nuova diventa corrente prima della copia verificata (la seconda strada cambierebbe `MediaCatalog.addEditedVersion`).
- R9: se il backup legge una riga a meta' **prima** del recupero, la carica come versione; rimessa la riga, lo scambio la porta in `Versioni/`. Una
  versione intermedia in piu', nessun byte perso.
- R17: la coppia (`fileId`, timbro) nel contratto `OwnChanges` di core-nasmedia, se servira'.
- LA-3 corretta qui (passata dal coordinatore, perche' sta in `FiveBGraph`): i candidati di E-E7 in `Pictures/MyCloud Gallery/` passano dalla
  recinzione di D13; test `Correzioni5BVersioniAppTest.la3_*` (2: il caso recintato, rosso prima, e quello di controllo).
- **Trovato scrivendo il test di LA-3, non corretto:** una copia scaricata e modificata in Galleria (E-E7, anche dopo "Scarica e modifica in Galleria")
  da sola **non fa partire nessun giro**: `BackupGraph.hasWork` conta le sorgenti del piano e i lavori delle versioni aperti, non i candidati di
  `Pictures/MyCloud Gallery/`, e `BackupStages.round` senza lavoro non chiama il motore. La versione sale solo col primo giro che parte per altro (una
  foto nuova, un lavoro aperto). Il rimedio (contare in `hasWork` i candidati di E-E7 non originali che possono diventare un lavoro) va pensato con
  LA-4: un candidato che non diventa mai un lavoro farebbe girare a vuoto.

**Da provare sul campo (S25, `mcg-prova` con D13)**: (1) T-09 con il dialogo chiesto senza tipo: deve uscire il dialogo di sistema e la riscrittura
riuscire (se il telefono rifiuta, la prova lo dice invece di chiudersi); (2) F34: "Ripristina originale" su una foto modificata in Galleria **prima** che
il backup la salvi: il foglio dice "prima il backup la mette al sicuro" e niente si tocca; dopo il backup, il ripristino; poi T-16; (3) F35 con un video:
il lavoro parte con le regole dei video; (4) F36: correzioni su JPEG con il dialogo, "Metti al sicuro adesso" su un solo-NAS, un HEIC (niente "Ruota");
(5) uccidere l'app durante una riscrittura (o togliere il permesso dei media): alla riapertura la foto torna com'era; (6) "Scarica e modifica in Galleria"
su un solo-NAS, e "Modifica con…" fuori casa a processo freddo.

## Correzioni 5B: Libera spazio (20/10)
Fonte: `docs/review/funzionale-5b.md` (LS-1…LS-15) e la nota dell'agente delle versioni («Correzioni 5B: versioni e correzioni», residui: una copia
scaricata e modificata non faceva partire nessun giro). Perimetro: `core-backup/.../free/**` (piu' un motivo nuovo nel contratto `FreeSpace` e il servizio
di riferimento in `testing/FiveBFakes.kt`), `feature-backup/.../free/**`, `feature-viewer/.../menu/**` (e una riga di `ViewerOverlay` per "Ovunque"), in `:app`
`backup/FiveB*` (con `BackupGraph.hasWork`/`nextAll` e il passaggio dei parametri della 5B), `backup/BackupUi.kt` (LS-9), `work/` (il lavoro della verifica),
`main/FiveBHostEffect.kt`, `navigation/`, `notify/`, una `@Provides` in `di/FiveBModule.kt`. E' l'unico flusso che toglie copie dal telefono: **nessun media
lascia il telefono senza una copia verificata adesso da una rilettura fatta qui**. Nessuna di queste correzioni tocca quella regola (vedi "Sicurezza").

**✅ DECISO (utente, 20/10): LS-1, «Elimina con avviso».** In release, finche' D12 e' acceso (fino a Q3), il backup non scrive sul NAS, e "Elimina… →
Solo dal telefono" del viewer non eliminava piu' niente (una regressione rispetto alla 4B). Adesso:
- **come nella 4B**: la foto va nel cestino di Android con il dialogo del sistema (`createTrashRequest`) e resta recuperabile per 30 giorni;
- **se il media non risulta al sicuro sul NAS** (backup non attivo, oppure nessuna copia privata verificata; anche in caricamento, area ferma, fuori dalla
  cartella di prova di D13), prima il foglio lo dice chiaro, "Questa foto non è sul NAS" ("Questo video…"), con il perche' e "la recuperi da Eliminati di
  recente per 30 giorni, poi non ci sarà più da nessuna parte", e vuole un tocco in piu' ("Elimina dal telefono"); se il backup e' attivo c'e' anche
  "Metti al sicuro", preselezionata (E-D14);
- **se il media e' al sicuro** vale il flusso 5B gia' esistente, con il guardiano prima del cestino ("Continua" → "Libera spazio" con la selezione);
- **fuori da questo comportamento**: "Libera spazio", che toglie molti media in blocco, resta com'e': senza backup attivo non libera niente.

**Decisioni**
1. **LS-2, il giro vive fuori dalla pagina.** Porta nuova `FreeSpaceRuns` (feature-backup): il giro di adesso, la ripresa all'apertura, l'avvio, "Annulla". Il
   conduttore e' `FreeSpaceDriver` (un solo conduttore alla volta, `verify` poi `resume` a tappe); in `:app` `FiveBFreeSpaceRuns` (in `FiveBGraph`) lo fa
   girare nello scope del grafo, chiede il **lavoro avviato dall'utente** (`BackupJobService`, id `1_000_105`, UIDT su Android 14+, con la sua notifica e
   `JOB_END_NOTIFICATION_POLICY_DETACH`) e annuncia "Pronto: N elementi, X GB. Tocca per liberare" se l'app non e' davanti all'utente; al ritorno l'app apre
   "Libera spazio" (`openRequest`, una volta per giro; la notifica, come le altre, apre solo l'app: C17). Il lavoro **ospita** il giro (`host`): se il
   processo muore Android lo rilancia e il giro si ritrova dal diario con `SafeFreeSpace.active()`, **nei limiti di sempre** (6 ore monotone dall'inizio,
   stesso avvio; altrimenti scade, una volta, e la pagina dice "La verifica è scaduta"). "Annulla" chiude il giro (`SafeFreeSpace.abandon`, `CANCELLED`):
   non riparte. Lo "Stop" del Task Manager di Android vale "Annulla" (mai un "Ferma" del backup). Il lavoro non libera niente: i lotti e il dialogo del
   sistema partono solo dalla pagina, da un tocco (il dialogo vuole un'activity viva). La frase della pagina e' diventata "Puoi uscire da questa pagina:
   quando è pronto ti avviso con una notifica".
2. **LS-5.** Il conduttore si ferma "non disponibile" se il servizio restituisce **lo stesso** giro che gli si e' passato (il null-object); un giro uguale ma
   nuovo (il guardiano che aspetta il turno) si riprova dopo una pausa, mai a vuoto. La facciata di `FiveBGraph` senza parti risponde `BLOCKED`/`NOT_AVAILABLE`.
   Il ciclo non gira piu' sul thread principale (scope del grafo).
3. **LS-13, la conferma vale per il piano visto.** `SafeFreeSpace.verify` controlla la conferma (impronta, 10 minuti, due tocchi per "Libera subito") contro il
   piano **che l'utente ha visto**, e il giro prende solo i suoi elementi che il piano ricalcolato adesso ha ancora **identici** (stesso media, versione,
   riga, dimensione, copia decisa: i campi dell'impronta). Un media diventato al sicuro dopo il foglio non entra mai (la lista non cresce, E-S11); uno
   cambiato o uscito resta sul telefono e la fine lo dice ("N erano cambiati dopo la tua conferma"). Prima ogni differenza rifiutava la conferma: durante il
   primo backup "Qualcosa è cambiato" a ogni tentativo. Cambia la regola scritta in P9 ("un elemento in piu', un timbro diverso: CONFIRMATION_REJECTED"):
   un'altra impronta, una conferma scaduta, dal futuro o con meno tocchi restano rifiutate. L'impronta del giro e' quella del piano confermato.
4. **LS-8.** Il guardiano gira **fuori** dal lucchetto di "Libera spazio" (`SafeFreeSpace.drive`: prima lo teneva per tutta l'attesa del turno, fino a 15
   minuti, e `settle()` dopo la lettura del telefono aspettava); il turno si aspetta al massimo una tappa del giro (`ExclusiveGuardian` con
   `guardianTurn`), e intanto la scheda dice "aspetto che il backup finisca il file che sta caricando" (`waitingForBackup`). Dopo il guardiano il giro si
   rilegge dal diario: se nel frattempo e' finito (un giro nuovo, "Annulla") non si va avanti.
5. **LS-11.** Senza l'ora del NAS `verify` prova prima a raggiungerlo (`reachNas`: in `:app` una sessione sull'area privata, nessun file letto; la
   sessione mette l'ora); solo se non arriva dice "il NAS non risponde". All'apertura la pagina chiede la stessa cosa senza aspettare
   (`FreeSpaceRuns.prepare`), il riepilogo dice "Non conosco ancora la data del NAS: per sicurezza le foto e i video con una data restano sul telefono" e
   si ricalcola quando l'ora arriva. La regola di Q9 non cambia: senza l'ora del NAS un media datato resta "recente".
6. **LS-10.** La pagina segue `FreeSpaceRuns.ready` (le parti di "Libera spazio" in `FiveBGraph`): prima "Non è ancora disponibile", poi il riepilogo, da solo.
7. **LS-12.** Le righe gia' consegnate ad Android hanno il loro motivo nel riepilogo, `FreeSpaceExclusion.AWAITING_PHONE` ("In attesa della risposta del
   telefono"; prima "in caricamento o con un problema"). Una riga in dubbio rinumerata dal MediaStore (`Stale`) si risolve con la stessa ricetta del backup
   (`FreeSpaceCatalog.phoneRowOf` → `BackupCatalog.phoneRowOf`): nessuna riga dopo la rilettura completa del volume = liberata, un'altra riga presente =
   rimasta, altrimenti resta in dubbio. Il lotto di **questo** processo di un giro finito (scaduto, chiuso da un giro nuovo) si risolve solo con la prova
   che la riga non c'e' piu': il suo dialogo puo' ancora rispondere (la risposta tardiva si registra, lo provano i test di P9), quindi una riga ancora
   presente resta nel dialogo, con il suo motivo, fino alla risposta o alla morte del processo. Un secondo tocco su "Libera ora" mentre il primo lotto e'
   in corso non lo annulla piu' (era la via per lasciare un lotto senza risposta). La fine dice quanti aspettano la risposta del telefono.
8. **LS-7.** Un "no" al secondo lotto: "Liberati X … Poi hai detto di no al telefono: gli altri restano sul telefono" (prima "Nessun file è stato tolto"
   anche dopo 2.000 media nel cestino); i rimasti contano anche i lotti non piu' proposti. Ogni uscita ha il suo motivo vero ("N sono rimasti sul telefono
   · La copia sul NAS è cambiata", "… · Non sono più sul telefono"); "la copia non tornava" resta solo per chi non ha superato la rilettura.
9. **LS-6.** La selezione sta nello stato salvato della rotta (`FreeSpaceSelection`, `rememberSaveable`): "tutto il sicuro", o gli id (fino a 500); una
   scelta piu' grande si ricorda per numero di richiesta, che solo il processo vivo ritrova (`FiveBUi.freeSpaceRequest`). Dopo la morte del processo una
   scelta persa **chiude la pagina**: mai "tutto il sicuro" al posto di una scelta. La rotta prende la sua richiesta una volta (`takeFreeSpaceRequest`);
   aperta da altrove (LS-9) e' "tutto il sicuro".
10. **LS-3.** Prima di aprire "Libera spazio" la radice chiude il viewer (`LocalViewerHost.current?.close()` in `FiveBHostEffect`).
11. **LS-4.** "Metti al sicuro" del foglio fa partire cio' che serve a **quel** media (`Corrections.safeguard`, in `:app` `BackupGraph.safeguard`: la
    verifica sul momento della copia sul NAS, o "Fai ora" se sta in una sorgente) e il foglio **resta aperto** ("Sto mettendo al sicuro…") e si aggiorna
    da solo quando il media e' al sicuro; fuori dalle sorgenti lo dice ("Questa cartella non è nel backup"), se non parte niente anche.
12. **LS-9.** "Libera spazio" del problema "Memoria del telefono quasi piena" apre la pagina della 5B; le impostazioni di Android solo senza il dialogo del
    sistema (Android 10).
13. **LS-14.** "Ovunque" di un media del telefono toglie **tutte** le sue copie dal telefono (anche quella di "Scarica sul telefono" in `Pictures/MyCloud
    Gallery`, che il backup altrimenti ricaricava) con un solo dialogo del cestino chiesto dalla radice; con una copia sola resta il cestino del viewer.
14. **LS-15.** `settle()` guarda solo i giri con righe consegnate e non risolte (`FreeSpaceDiary.runsToSettle`, su Room dall'indice di `state`); la ripresa
    solo i giri aperti (`openRuns`); le pagine del catalogo non rileggono la riga dell'indice (giorno e ordine li portano gia'); il foglio di conferma riusa
    il piano del riepilogo (anche per "Libera subito"), invece di ricalcolarlo senza indicatore. Con LS-13 e' sicuro: il giro prende comunque solo cio' che
    e' ancora identico adesso.
15. **La copia scaricata e modificata** (E-E7, nota dell'agente delle versioni): `BackupGraph.hasWork` e `nextAll` contano una copia di `Pictures/MyCloud
    Gallery/` modificata **solo se diventerebbe davvero un lavoro** (`FiveBGraph.downloadVersionWaiting`: nessun lavoro aperto, `VersionPlanning.phoneVersion`
    la accetta, dentro la radice di D13): da sola fa partire il giro delle foto, e fatta la versione il giro non riparte a vuoto.

**Modifiche ai contratti e al codice condiviso**
- `core-backup`: `FreeSpaceExclusion.AWAITING_PHONE`; `FreeSpaceCatalog.phoneRowOf` (default null); `FreeSpaceDiary.openRuns`, `runsToSettle` (default dal
  diario, Room con le query); `SafeFreeSpace.active`, `abandon`, parametro `reachNas`; `PhoneOnlyAssessment.notOnNas`.
- `feature-backup`: `FreeSpaceRuns`, `FreeSpaceDriver` (porta opzionale in `FiveBOptionalBindingsModule`); `FreeSpaceUi.Summary.nasTimeKnown`,
  `Guarding.waitingForBackup`, `Done.droppedBy`/`changedBeforeStart`/`inDoubt`; `PhoneOnlySheetHost(onTrash, isVideo)`, `PhoneOnlyUi.Safeguarding`,
  `SafeguardNote`; `PhoneOnlyViewModel` prende `Corrections`; `FreeSpaceViewModel` prende `FreeSpaceRuns`.
- `feature-viewer` (menu): `BackupMediaActions.deleteEverywhereFromPhone`, `BackupActionsController.deleteEverywhereFromPhone`, `NasActionSheets(onEverywherePhone)`.
- `:app`: `FiveBFreeSpaceRuns`, `NoFreeSpaceRuns`, `FiveBGraph.freeRuns`/`trashRequest`/`downloadVersionWaiting`, `ExclusiveGuardian` con l'attesa limitata,
  `CatalogFreeSpaceCatalog(catalog, backupCatalog)`, `FiveBUi.trashFromPhone`/`trashRequests`/`takeFreeSpaceRequest`/`freeSpaceRequest`, `FreeSpaceRequest`,
  `FiveBSheet.PhoneOnly(fromViewer, isVideo)`, `BackupJobs.freeSpaceWork` e `JOB_FREE_SPACE`, `FreeSpaceNotification`/`FreeSpaceNotices`,
  `AppNotifications.ID_FREE_SPACE`, `FreeSpaceSelection`, `backupGraph(takeFreeSpaceRequest, freeSpaceRequest)`.

**Finti allineati**
- `InMemoryFreeSpace`: la conferma sul piano visto e l'intersezione come il vero (LS-13); la verifica a tappe (`verifySteps`, `stepDelayMs`) e il servizio
  che non avanza (`stuck`, LS-5) invece di "subito pronto"; `active`/`abandon`; conta piani e tappe (LS-15).
- `InMemoryFreeSpaceCatalog.phoneRows` (la risposta della lettura del telefono dopo una ricostruzione del MediaStore).
- `FiveBButtonsTest` si accontentava di `advice.toString()`: il caso della release lo prova `Correzioni5BLiberaSpazioAppTest.ls1_*`.

**Sicurezza: dove si ricontrolla la regola d'oro**
- Nessun controllo tolto dal motore. Ogni elemento che arriva a un dialogo passa ancora, nell'ordine: piano dal catalogo locale (E-S11), conferma, guardiano,
  **verifica sul momento** (rilettura completa, SHA-256 al catalogo), **controllo finale** prima del dialogo (regole di adesso, riga del telefono, timbro di
  ogni copia riletta), lotto scritto `IN_DIALOG` prima della UI. Il lavoro in background esegue solo guardiano e verifica, mai un lotto.
- LS-13 restringe, non allarga: un elemento entra nel giro solo se e' **sia** nel piano confermato **sia** in quello di adesso, identico.
- LS-1 non tocca "Libera spazio": il cestino del viewer per un media non sul NAS e' quello della 4B (cestino di Android, 30 giorni, dialogo del sistema),
  dopo un avviso esplicito e un tocco in piu'; nessuna copia sul NAS si tocca.
- LS-12: un lotto il cui dialogo puo' ancora rispondere non si da' mai per "rimasto" (la risposta tardiva si registra); "liberato" solo con la prova.
- La ripresa dopo la morte del processo usa la validita' del diario (inizio monotono, conteggio dei riavvii): mai oltre 6 ore, mai dopo un riavvio.

**Prove**
- `core-backup`: `Correzioni5BLiberaSpazioTest` (LS-13 ×3, LS-2 ×3, LS-8, LS-11 ×2, LS-12 ×3), `RoomFreeSpaceDiaryTest.ls15_*`; `FreeSpaceEveryCallTest` con i
  casi nuovi sotto guasti a ogni chiamata e morte a ogni scrittura (stesso avvio e riavvio): un media diventa al sicuro dopo il foglio (mai in un lotto del
  giro confermato, liberato dal giro dopo) e la ripresa passa da `active()`. Rossi prima (riprovati rimettendo la logica di prima): LS-13 ×3 e LS-12
  `Stale`; LS-8 (il guardiano sotto il lucchetto: `settle` scade).
- `feature-backup`: `Correzioni5BLiberaSpazioFlowTest` (LS-2 ×3, LS-5, LS-7, LS-10, LS-11, LS-12, LS-13, LS-15), `Correzioni5BSoloDalTelefonoTest` (LS-1 ×5,
  LS-4 ×3), `FreeSpaceScreenTest.ls*` (LS-7 ×2, LS-8, LS-11, LS-12/13).
- `feature-viewer`: `BackupActionsTest.ls14_*` (il controller chiede a `:app` tutte le copie, o il cestino del viewer con una sola).
- `:app`: `Correzioni5BLiberaSpazioAppTest` (LS-1 in release, LS-2 lavoro che ritrova il giro, LS-5/LS-10, LS-9, LS-14 ×2, la copia scaricata e
  modificata), `FreeSpaceSelectionTest` (LS-6 ×4), `FreeSpaceJobTest` (il lavoro di LS-2: id suo, rete del NAS, non si sostituisce, l'azzeramento lo
  toglie); `FiveBButtonsTest` ora vuole `FREE` per la foto al sicuro. Rossi rimettendo la logica di prima: LS-5/LS-10, LS-9 e la copia scaricata e
  modificata (gli altri usano metodi nuovi: prima non compilavano).

**Residui**
- LS-3 non ha un test automatico (servirebbe il viewer vero composto sopra la navigazione): una riga, da vedere in F38.
- LS-15: `RoomFreeSpaceDiary.update` rilegge ancora gli elementi del giro a ogni scrittura (serve una query per indice in `FreeSpaceDao`, core-database):
  da misurare con F37 su 50 GB prima di cambiarla. Il piano rifa' ancora le query per media dentro `verify` (necessario: e' il piano di adesso).
- LS-12: una riga di un lotto di questo processo, rimasta sul telefono a giro finito, resta "in attesa della risposta del telefono" fino alla risposta o
  alla morte del processo (scelta: il dialogo puo' ancora rispondere).
- La notifica "Pronto" non c'e' se l'utente ha negato le notifiche (come le altre); la pagina si apre comunque al ritorno nell'app.
- La riga di `ViewerOverlay` per "Ovunque" (LS-14) e la `@Provides` in `di/FiveBModule.kt` stanno fuori dal perimetro dichiarato: senza, la correzione non
  arriva al viewer e la pagina non trova la porta.

**Da provare sul campo (S25, `mcg-prova` con D13)**: F37 — "Libera spazio" su 50 media: uscire dalla pagina a meta' verifica e spegnere lo schermo
(continua, notifica con l'avanzamento, poi "Pronto"; il tocco riapre la pagina); `am kill` a meta' verifica (il lavoro riparte e riprende; dopo 6 ore o un
riavvio: scaduta); un "no" al secondo lotto di una selezione grande (la fine dice quanti sono liberati); la conferma durante il primo backup (parte, e
la fine dice quanti erano cambiati); "Fai ora" in corso con un media della sola area comune (la scheda dice che aspetta il backup); avvio a freddo e subito
Raccolte → Libera spazio (la nota sulla data del NAS, poi il riepilogo si ricalcola). F38 — "Elimina… → Solo dal telefono": la pagina "Libera spazio" si
apre sopra (il viewer si chiude); "Metti al sicuro" di una foto non ancora salvata (il foglio aspetta e poi dice "Continua"); una foto fuori dalle
sorgenti; con l'APK di release (F44): l'avviso "Questa foto non è sul NAS", poi il cestino di Android e la foto in Eliminati di recente. "Ovunque" di una
foto anche scaricata in `Pictures/MyCloud Gallery` (un solo dialogo per le due righe).

## Correzioni 5B: integrazioni (20/10)
Fonte: `docs/review/funzionale-5b.md` (IN-1 ... IN-13) e `docs/review/5b-fase5b.md` (R5, R8, R11, R14, R15). Perimetro: `feature-integrations` (`receive/`,
`picker/`, `provider/`, `edit/`), il cammino `INCOMING` di `core-backup` (`SafeUploadEngine`, `RoomBackupStore`, finti) e, in `:app`, `integrations/`,
`backup/IncomingQueue.kt` e `work/` (con la notifica del lavoro dei file ricevuti). Le integrazioni sono il caso in cui **l'app non e' in esecuzione**: il
processo nasce perche' un'altra app ha chiamato, e i difetti stavano tutti nei primi secondi (lo stato non ancora letto, la pulizia d'avvio, gli id che
DocumentsUI ricorda) e nel lavoro che ne nasce (chi lo programma, con quali regole, che cosa dice).
**Decisione dell'utente a lavoro iniziato (20/10/2026): «Rimuovi la posizione» vale anche per il selettore e per il provider dei documenti.** Prima il
brief diceva di non toccarli (limite L41: consegnavano l'originale con la posizione); ora fanno parte di questo lavoro (sezione sotto). Cade la parte di L41
che li escludeva.

**Processo freddo: lo stato si aspetta, non si indovina (IN-3)**
- `EntryDecision.LOADING` ("l'app sta ancora leggendo la configurazione salvata e le impostazioni del blocco") e `EntryGate.decideAfterLoading(timeoutMs)`
  (10 s per le activity, 4 s nel provider, che risponde a un thread del binder; orologio monotono). `AppEntryGate` osserva `setup.state` e le impostazioni
  del blocco come flussi (`familyLoading`, `familyChanges`: li passa `FiveBModule`); `decisions()` e' il flusso delle decisioni, senza ripetizioni.
- Selettore e ricezione aspettano e poi decidono (mai «Prima configura MyCloud Gallery» per una corsa); se lo stato non arriva, «non pronta» con
  «Riprova» al ritorno nella schermata. Il provider risponde dopo l'attesa; la radice compare appena lo stato e' letto perche' le radici si notificano.

**Gli id del provider (IN-2). Schema**
- **Radice, «Recenti», «Cartelle NAS», cartelle del NAS: id stabili.** `HMAC-SHA256(chiave locale, etichetta | chiave opaca della cartella)` troncato a 18
  byte, base64 URL-safe (24 caratteri). Gli stessi a ogni avvio del processo finche' la chiave non ruota. Non contengono e non fanno ricavare percorso,
  share, account, nome, `MediaId`; senza la chiave non si indovinano; aprono una cartella, mai un media (che passa sempre dalla vista di adesso e dal
  suo id a scadenza).
- **La chiave** (`DocumentIdKey`): 32 byte casuali in `noBackupFilesDir/integrations/doc-id.key` (`FileDocumentIdKey`; fuori dal backup di Android, scritta
  in un temporaneo e rinominata), letta alla prima richiesta e non all'avvio del processo. **Ruota** con l'azzeramento dell'app (`revokeAll`): tutti gli id
  vecchi smettono di valere. Se il file non si legge o non si scrive, la chiave vale per il processo (gli id cambiano a ogni avvio, come prima, e niente
  si rompe). I test usano una chiave in memoria.
- **I media: invariati.** Token casuali di 18 byte (`SecureRandom`) in memoria, validi per l'avvio, a **scadenza** a scorrimento (piu' lunga se consegnati dal
  selettore) e **revocabili**; un URI usato dopo la scadenza o la revoca non apre piu' niente (E-X1). Un media nascosto dopo la nascita dell'id non si apre.
- **Una cartella dopo un riavvio**: la tabella e' in memoria e riparte vuota, ma DocumentsUI ricorda gli id. `LibraryMediaDocuments.found` (ora sospesa)
  ricalcola gli id di tutte le cartelle da `IntegrationLibrary.nasFolderIndex()` (di serie una visita dalla radice con `nasFolders`; `AppIntegrationLibrary`
  la fa con **una sola lettura del catalogo**, `CatalogNasFolders.allFolders`, al piu' 20.000 cartelle, mai `Versioni/`) e non piu' di una volta ogni 5 s;
  un id inventato resta sconosciuto. Gli id che non sono nostri non costano niente.
- **Le radici si notificano** (`IntegrationsGraph`): `install()` ascolta `AppEntryGate.decisions()` e chiama `ContentResolver.notifyChange(buildRootsUri)` a
  ogni cambio (stato letto, blocco acceso/spento, sbloccato, richiuso); l'azzeramento ruota la chiave e notifica.
- Trovato dal test e corretto: `FolderKeys.parse` (`:app`) costruiva il percorso con `SharePath.of`, che accetta un solo componente: ogni cartella oltre il
  secondo livello non si apriva, quindi l'albero «Cartelle NAS» si fermava li' e i media delle cartelle annidate non si elencavano. Ora `SharePath.parse`.

**Sblocco dal provider (IN-4) e dalle activity (IN-10)**
- L'`AuthenticationRequiredException` porta un `PendingIntent` **immutabile ed esplicito** verso `PickerActivity` con `ACTION_UNLOCK` (non piu' la
  `MainActivity`): un'activity d'ingresso leggera che si sblocca con `endEntryAuthentication` (tolleranza di 2 minuti, anche con «Subito»), non mostra la
  Libreria e finisce con `RESULT_OK`. DocumentsUI riprova da solo. Se non riesce resta con il motivo e «Riprova»; «Annulla» chiude.
- `UnlockFailure` (`CANCELLED`, `NO_SCREEN_LOCK`, `OTHER`) da `EntryUnlock.lastFailure()` e `ReceiveUnlock.lastFailure()` (`AppEntryUnlock.failureOf`): la nota
  sotto il tasto dice cosa fare senza blocco schermo e non dice «non sono riuscito» a chi ha solo annullato. Il prompt parte una volta per schermata
  (`repeatOnLifecycle`) e «Sblocca» lo rilancia: niente ciclo se la schermata delle credenziali mette in pausa l'activity.

**File ricevuti (IN-1, IN-6, IN-8, IN-11, IN-12)**
- **IN-1**: `IncomingStager.ownStages` (le condivisioni copiate da questo processo, inserite prima di creare la cartella) e `sweep` le salta. Un
  processo nuovo toglie il resto di quello morto, come prima.
- **IN-6. Causa:** in release D12 (share vere) e R2 (account di prova) chiudono ogni scrittura della 5B, quindi «Salva sul NAS» **non puo' riuscire**
  finche' non cade (fino a Q3): giusto cosi'; sbagliato era scoprirlo dopo aver copiato (fino a 4 GiB) e con “In quella cartella non si può scrivere”.
  `IncomingDestinations.savingBlockedByTrial()` (tutte le aree dell'utente con `BackupGateState.TEST_ONLY`) si chiede prima di copiare: la schermata
  `TEST_ONLY` dice «Backup in prova» e non copia; se la prova arriva dopo la copia, `ReceiveFailure.TEST_ONLY`.
- **IN-8**: la data del media e' l'ora del NAS se nota, altrimenti la data del file in arrivo, **mai 0**; se il media nasce dall'arrivo, `AppIncomingMetadata`
  legge dai byte locali (estrattore della Libreria, `IncomingMetadata`) la data di scatto con il fuso, le dimensioni, la durata e il tipo e li scrive nel
  catalogo; la data di scatto vince su quella d'arrivo.
- **IN-11**: «Apri la cartella scelta» senza sottocartelle lo dice e non naviga; «Riprova» chiude per `GONE` e `TEST_ONLY`; «niente da salvare» con rifiuti e
  senza cartelle dice che manca la cartella; la sonda del NAS non fa piu' aspettare «Salvo…» (si va a «In coda» e la frase giusta, `NasWait`, arriva
  dopo); sui dati mobili: «lo carico appena sei sul Wi-Fi di casa».
- **IN-12**: `save` intercetta ogni `Exception`; i file che alla messa in coda non si leggono piu' si contano e si dicono (`notQueued`).

**Il lavoro dei file ricevuti (IN-5, IN-7)**
- **IN-5**: «Salva sul NAS» e' un gesto dell'utente di qualunque grandezza: `BackupStages.incoming` e' sempre `WorkRequest.UserInitiated` (turno esclusivo
  dell'utente; resta la rete, il NAS, il congelamento, il caldo, i dati mobili con la conferma; niente fascia, carica o preset Notte). `userInitiated`
  dice solo se il lavoro e' il UIDT. Le attese per la rete e la conferma dei dati mobili si ripianificano come BK-2 (`afterWait` per `INCOMING`: lavoro su rete
  non a consumo, con ritardo).
- **IN-7**: notifica propria (id 1007, «Salvataggio sul NAS», «N di M file ricevuti» dal diario degli arrivi, solo numeri), «Ferma» proprio (azione e
  ricevitore propri; `IncomingStop` per lavoro: il giro finisce al passo dopo, i file restano in coda; mai la pausa R4 di tutto il backup), anche lo «Stop»
  di Android sul lavoro degli arrivi non mette in pausa le foto. Due ricevitori nel servizio (uno per lavoro) e il «Ferma» arriva sempre al lavoro in
  corso. Un NAS pieno, la quota o la password per un arrivo arrivano alla memoria dei problemi e agli eventi come per ogni altro giro.

**Selettore (IN-9, IN-13, R8)**
- **IN-9**: la griglia (`PickerItems.of(view, ...)`, `TimelineView.indexInSnapshotOf`), «Recenti» del provider e il controllo prima di consegnare scorrono la
  **vista**, non l'indice.
- **IN-13**: `nasReach` sonda tutte le aree; `PICK` con il tipo del MediaStore e' servito (manifest e `PickerRequest`).
- **R8**: `OverlayShield.protect` (filtro dei tocchi oscurati e, da Android 12, `setHideOverlayWindows`) nel selettore e nel suo pannello di sblocco; e un
  tocco su un elemento **sceglie ma non consegna** anche senza selezione multipla: la consegna e' «Scegli».

**«Rimuovi la posizione» per il selettore e per il provider (decisione dell'utente, 20/10/2026)**
- **Cosa**: con l'impostazione accesa (`SharingSettings.removeLocation`) cio' che un'altra app riceve dal selettore o apre dal provider e' una **copia senza
  posizione**, fatta con lo stripper nuovo e il rilevatore indipendente `LocationTraces` di «Condividi» (`LocationFreeShare`, commit `147cfa4`): nessun secondo
  stripper. Spenta, come oggi: l'originale.
- **Come** (`provider/PreparedCopies.kt`, porta `DeliveryPreparer`): un registro di copie per media in `cacheDir/share/`, **una preparazione per volta**, con
  i tetti di «Condividi» (100 elementi per consegna, 2 GiB in tutto, almeno 1 GiB libero dopo la copia: oltre, la copia dello sfoglio meno usata fa posto, una
  consegnata mai, e se non basta la consegna si rifiuta). Il media si copia a blocchi di 1 MiB in un file di lavoro (mai tutto in memoria), si ricostruisce
  da file a file e il file con la posizione si cancella subito. L'avanzamento e' a byte (`LocationFreeShare.prepare(..., onBytes)`), la cancellazione toglie
  le copie fatte, il NAS spento ferma subito (E-X1).
- **Il selettore** (`PickerDeliverer`, `PickerViewModel`, schermata): con l'impostazione accesa **ogni** elemento scelto (anche una foto del telefono: prima
  usciva come indirizzo del MediaStore, cioe' l'originale) passa dalla preparazione **prima** di ogni id, poi esce come documento del provider con un id
  consegnato che apre **la copia**. La schermata di consegna mostra «Tolgo la posizione dai file», «N di M», una barra a byte e **«Annulla»** (torna alla
  griglia con la scelta com'era; `cancelDelivery`). Tetto di scelta a 100 (come «Condividi»). Se la posizione non si toglie, **non esce nessuno**: l'avviso
  dice «Questo file non si può condividere senza posizione», e se il motivo e' di un file solo lo toglie dalla scelta (gli altri escono con un altro
  «Scegli»); mai gli indirizzi del MediaStore al posto delle copie. Senza chi crea gli id (nessun `DocumentIssuer`) si fallisce chiusi.
- **Il provider** (`LibraryMediaDocuments(preparer)`, `MediaDocumentsProvider`): aprire un documento con l'impostazione accesa apre **solo la copia** (se non
  c'e' la prepara dentro la chiamata, con `DocumentPolicy.PREPARE_DEADLINE_MS` = 3 minuti, annullabile con il segnale dell'altra app). Se la posizione non si
  toglie: `FileNotFoundException` con la frase del motivo («Questo file non si può condividere senza posizione.»; spazio, NAS, guasto, troppo lento hanno la
  loro), un avviso a schermo (Toast), nessun descrittore aperto, nessuna copia a meta' in cache. Le voci descrivono la copia: nome e tipo come saranno (un
  HEIC e' un JPEG, `IMG_1.jpg`), **dimensione sconosciuta finche' la copia non c'e'** (`DocumentEntry.UNKNOWN_SIZE`, colonna nulla: una dimensione sbagliata
  farebbe leggere un file troncato), esatta dopo.
- **Quando se ne vanno le copie**: come l'id. Si rinnovano a ogni uso; 30 minuti a scorrimento per lo sfoglio, 6 ore per le consegnate dal selettore (le
  stesse degli id); con la revoca dell'id, quando il media si nasconde, con l'azzeramento dell'app (`revokeAll`) e all'avvio successivo (`ShareCache.sweepAtStart`);
  un custode le toglie anche se nessuno le chiede piu'. **La pulizia dell'avvio non tocca le copie di questo processo** (`ShareCache` ricorda le cartelle che
  ha creato: stessa corsa di IN-1; una chiamata di DocumentsUI che fa nascere il processo puo' preparare una copia mentre la pulizia passa); quelle di un processo
  morto si tolgono come prima. Se un media esce da un gruppo preparato insieme se ne va il suo file subito.
- **Cablaggio** (`IntegrationsGraph`): `preparedCopies = PreparedCopies(share, { removeLocation.value }, scope)` passato a `LibraryMediaDocuments` e a
  `PickerPorts.install(library, unlock, preparedCopies)`; senza l'impostazione legata vale «togli» (fallire chiusi). L'impostazione si legge al momento
  dell'uso: un id consegnato con l'impostazione spenta e poi acceso apre la copia, non l'originale.
- **Costo, detto**: un solo-NAS grande (qualche GiB) si scarica tutto in cache prima di uscire (e ci vuole la meta' di spazio in piu' mentre si ricostruisce).
  Dal **selettore** si vede l'avanzamento e si annulla; **sfogliando** i documenti (Gmail «Allega file») la preparazione sta dentro la chiamata dell'altra
  app, che aspetta al piu' 3 minuti: oltre, errore («sceglilo dal selettore di MyCloud Gallery») e la copia a meta' non resta. Non c'e' un avanzamento visibile
  dal provider (l'utente e' nell'altra app). Una foto o un video normale costa pochi secondi. La voce delle Impostazioni («Quando condividi con un'altra app
  mando una copia senza il luogo…») dice gia' il giusto: non toccata.
- **Test**: `LocationFreeDeliveryTest` (14, `:feature-integrations`: selettore e provider su JPEG, HEIC e MP4 con la posizione, verificati dal rilevatore
  indipendente; lo stripper che fallisce in tre modi e un formato non trattabile: niente consegnato, niente in cache, l'originale mai aperto; spenta, l'originale;
  tetti; cache piena; scadenza, revoca e azzeramento; annullamento a meta' con la barra a byte), `LocationFreeProviderTest` (3: dal provider vero la copia con la sua
  dimensione, l'errore con la frase e l'avviso e nessun descrittore, il tempo scaduto), `PickerViewModelTest` (+4: avanzamento, «Annulla», il file che non si puo' si
  toglie dalla scelta, il tetto di 100), `PickerLayoutTest` (la schermata con l'avanzamento e «Annulla», l'avviso; bersagli da 48 dp a 1x e 1,3x),
  `FiveBIntegrationTest` (+2, grafo vero: una foto solo-NAS con il GPS esce dal selettore e dal provider senza GPS, l'originale sul NAS non si tocca, spenta
  esce l'originale; il grafo lega l'impostazione).

**Conferma dell'utente (stessa data, per il gruppo «Libera spazio»)**: in debug con un account di prova, «Elimina dal telefono» su una foto **fuori da
`mcg-prova`** va nel cestino di Android dopo l'avviso, come nella 4B. La recinzione D13 vale per il backup, non per l'eliminazione chiesta dall'utente dal
viewer. (Non e' codice di questo gruppo: lo applica chi ha «Libera spazio».)

**Il motore dei file in arrivo (R5, R14, R15)** (`SafeUploadEngine`, `RoomBackupStore`)
- **R5**: un arrivo con il cancello della **sua** share chiuso aspetta da solo (`waitsAlone`, `queue.park()`); pausa, emergenza e famiglia non pronta fermano il
  giro come prima. Un rifiuto della share di destinazione (`PermissionDenied`, share sparita) e' `Step.Wait()` dell'arrivo.
- **R14**: `createIncoming` e' idempotente per (versione, share, cartella) e non per versione.
- **R15**: un arrivo che non riesce non segna il media (`markMedia`); `openFor` non vede gli arrivi.

**«Modifica con…» (R11)**: una lettura sola della copia in cache dentro un file privato; impronta, dimensione, `qh1` e tipo sugli stessi byte; la versione e' quella copia.

**Contratti cambiati**
- `EntryDecision.LOADING` e `EntryGate.decideAfterLoading(timeoutMs)` (`contract/IntegrationPorts.kt`).
- `IntegrationLibrary.nasFolderIndex()` e `MAX_FOLDER_INDEX` (`provider/IntegrationLibrary.kt`).
- `IncomingDestinations.nasWait()` e `savingBlockedByTrial()`; `ReceiveUnlock.lastFailure()` e `EntryUnlock.lastFailure()`; `enum UnlockFailure`, `enum NasWait`.
- `DocumentIdTable(idKey: DocumentIdKey)` e `DocumentIdKey` (`FileDocumentIdKey`, `InMemoryDocumentIdKey`).
- `BackupStore.openFor` non restituisce gli arrivi; `createIncoming` per destinazione (Room e in memoria); `BackupDiary` lo documenta.
- `DefaultEditSessions(openForRead)`. `BackupProgress.incoming(done, total)` e `BackupNotification.stopAction(context, incoming)` (`:app`).
- Posizione nel selettore e nel provider: `DeliveryPreparer` (`NoPreparation`, `PreparedCopies`), `LibraryMediaDocuments(preparer)`, `PickerPorts.install(library,
  unlock, preparer)`, `MediaDocuments.preparesOnOpen()`, `DocumentEntry.UNKNOWN_SIZE`, `LocationRemovalRefused`, `PickerNotice.NoLocation`, `PickerProgress`,
  `PickerDeliverer(preparer)`, `LocationFreeShare.prepare(..., onBytes)`, `DocumentPolicy.PREPARE_DEADLINE_MS`.
- `FiveBModule` passa `familyLoading` e `familyChanges` a `AppEntryGate` (modifica minima di collegamento).
- Il manifest di `feature-integrations`: `PICK` ammette `vnd.android.cursor.dir/image` e `.../video`.

**Finti allineati ai veri**
- `FakeEntryGate` (`LOADING`, attesa vera di `decideAfterLoading`); `FakeIntegrationLibrary.restrictView` (indice intero, vista parziale) con il **test di
  parita'** `PartialAccessTimelineTest.in9_*` contro `SubsetView` (`:app`); `FakeIntegrationLibrary.folderIndexCalls`; i finti della ricezione e del selettore
  (`nasWait`, `savingBlockedByTrial`, `lastFailure`); `InMemoryBackupStore` come `RoomBackupStore` (stessi test: `IncomingStoreTest` gira su tutti e due).

**Sicurezza**
- Gli id stabili non rivelano niente e non si indovinano senza la chiave (locale, fuori dal backup, ruotata all'azzeramento); non danno accesso ai media.
- `PickerActivity.ACTION_UNLOCK` non consegna niente: mostra lo sblocco e risponde `RESULT_OK`/annullato; il `PendingIntent` e' immutabile ed esplicito.
- R8 chiude il tapjacking del selettore (e del suo pannello di sblocco) con due difese diverse.
- R11 chiude la corsa fra impronta, tipo e copia: i byte giudicati sono quelli caricati.
- «Rimuovi la posizione» per selettore e provider: fallire chiusi in ogni punto (stripper che non riesce, posizione rimasta, formato non trattabile, niente
  spazio, NAS spento, tempo scaduto, nessun chi crea gli id): non esce niente e l'originale non prende mai il posto della copia; le copie stanno in una cache
  privata e se ne vanno con l'id. Scritto in L41 e C18 di `docs/sicurezza.md`.

**Test** (prima → dopo)
- Ogni voce ha un test scritto prima della correzione e rosso; i test chiave sono stati provati anche per mutazione (togliendo la correzione a mano): 
  - IN-1: senza `ownStages` rossi i due `IncomingStagerTest.in1_*`;
  - IN-2: con una chiave casuale a ogni processo rossi `DocumentIdTableTest.in2_*` (3), `LibraryMediaDocumentsTest.in2_*`, `MediaDocumentsProviderTest.in2_*`; senza la notifica delle radici e con `FolderKeys.parse` com'era, i due `Correzioni5BIntegrazioniAppTest.in2_*`;
  - IN-3: ricezione che non aspetta: `ReceiveViewModelTest.in3_*` (2) e `ShareReceiverActivityTest.in3_*`;
  - IN-4: il tocco del provider di nuovo sulla `MainActivity`: `MediaDocumentsProviderTest.ilTocco_Sblocca…`;
  - IN-5: lavoro di fondo: `Correzioni5BIntegrazioniAppTest.in5_*`; IN-6: `savingBlockedByTrial` sempre falso: `…in6_*`; IN-8: data 0: `…in8_senzaLOraDelNas…`; IN-13: solo la prima area: `…in13_*`;
  - IN-9: «Recenti» dall'indice: `LibraryMediaDocumentsTest.in9_*`;
  - R5: `waitsAlone` falso: 3 dei 5 `IncomingIsolationTest.r5_*`; R14: `sameDestinationAs` vero: `IncomingStoreTest.r14_*_Room`; R15: `markMedia` e `openFor` come prima: `IncomingIsolationTest.r15_*` e `IncomingStoreTest.r15_*_Room`;
  - R8: senza `OverlayShield.protect` o con la consegna al tocco: `PickerActivityTest.r8_*`, `PickerViewModelTest.r8_*`; R11: seconda lettura: `DefaultEditSessionsTest.r11_*`;
  - posizione nel selettore e nel provider: provider che apre l'originale, o selettore che salta la preparazione: `LocationFreeDeliveryTest` (10), `LocationFreeProviderTest` (3), `MediaDocumentsProviderTest`, `PickerViewModelTest` (3 dei nuovi).
  Il banco di prova ha trovato anche un difetto vero di prima: `FolderKeys.parse` rifiutava i percorsi annidati (l'albero «Cartelle NAS» si fermava al secondo livello).
  Un test che si bloccava con la mutazione (`annullareMentreSiPrepara…`, in attesa di una preparazione mai partita) ora ha una scadenza e fallisce.
- Nuovi: `Correzioni5BIntegrazioniAppTest` (9, grafo vero), `IncomingIsolationTest` (6, R5 e R15 sul motore), `IncomingStoreTest` (4, R14 e R15 su Room e in
  memoria), `LocationFreeDeliveryTest` (14), `LocationFreeProviderTest` (3), `LocationFreeShareTest.lAvvioNonToglie…` (la cache), `PartialAccessTimelineTest.in9_*`, `BackupSchedulingTest.in5_*` (2), `BackupNotificationTest.in7_*`, `AppEntryGateTest.in2_*`/`in3_*`, e le serie
  `in*`/`r*` di `IncomingStagerTest`, `DocumentIdTableTest`, `LibraryMediaDocumentsTest`, `MediaDocumentsProviderTest`, `PickerViewModelTest`,
  `PickerActivityTest`, `PickerRequestTest`, `ReceiveViewModelTest`, `ShareReceiverActivityTest`, `DefaultEditSessionsTest`.
- Aggiornati alla regola nuova: `AppEntryGateTest` (a freddo `LOADING`, non «chiusa»), il test di `IncomingStagerTest` sulla pulizia (adesso la fa uno stager
  nuovo, cioe' un processo nuovo), i test del selettore (un tocco sceglie, «Scegli» consegna), `ManifestComponentsTest`, `FiveBButtonsTest`.
- Esiti (20/10, `--max-workers=2`, un solo Gradle alla volta): `:feature-integrations` **517 test, 0 falliti**; `:core-backup` **573, 0 falliti** (3 saltati, come
  prima); `:app` **468, 0 falliti**. Il banco delle interruzioni del backup, rifatto perche' ho toccato il motore (`tools\chaos\area.ps1 -Mode backup`, con
  lo stesso comando Gradle e `--max-workers=2` invece di 4): **-Stage jvm 2.000 giri, 0 violazioni; -Stage smb 600 giri, 0 violazioni** (sia con l'esecutore di
  riferimento sia con il motore vero). `tools/check.ps1` non lanciato (come da brief).

**Limiti e punti aperti**
- **IN-7, non fatto allora (fatto dopo: «Ricevuti in coda (20/10)»)**: la riga «Ricevuti in coda» con riprova e scarta nella pagina Backup (e il conto degli arrivi in `queued`), e un modo di riprovare o togliere
  gli arrivi `FAILED` (tengono la copia locale come prima). Sono UI di `feature-backup`, fuori da questo gruppo; il media di un arrivo resta invisibile
  nella Libreria finche' non e' sul NAS (E-X8 invariato).
- **R15, non fatto allora (fatto dopo: «Ricevuti in coda (20/10)»)**: `MediaCatalog.incomingMedia` (`core-database`) prende ancora qualunque versione con lo stesso SHA-256, anche di un media nascosto o di un
  altro account; il file salvato non si vede o non e' «mio». Nessun effetto sul backup (sistemati `markMedia` e `openFor`).
- **R5**: un arrivo bloccato per sempre (sola lettura permanente) non diventa `FAILED`: aspetta con la sua copia locale e riparte se il cancello si riapre.
- **IN-13**: le voci di debug «MCG prova (T-14/T-15)» non sono toccate (solo debug).
- **IN-8**: la miniatura del video e l'indice del NAS arrivano dalla preparazione normale dopo il caricamento (non dal cammino `INCOMING`).
- Il R8 costa un tocco in piu' a chi sceglie una sola foto: scelta fatta qui, da confermare.
- Posizione, provider: un solo-NAS grande aperto sfogliando i documenti puo' non farcela nei 3 minuti (errore chiaro, nessuna copia a meta'); dal selettore no.
  Un solo-NAS grande occupa la cache fino alla scadenza dell'id (6 ore se consegnato): il tetto e' di 2 GiB in tutto.
- Posizione: i file che lo stripper non sa trattare (GIF, BMP, RAW, MP4 frammentati, ...) non escono dal selettore ne' dal provider con l'impostazione accesa;
  con l'impostazione spenta escono come prima.

**Da provare sul campo** (solo `mcg-prova`, D12/D13)
- **F39**: Gmail → «Allega file» → MyCloud Gallery; `am kill` del pacchetto; di nuovo Gmail → «Allega file» → MyCloud Gallery (la radice e l'ultima cartella aprono
  senza errori); con il blocco «Subito»: «Sblocca» e subito indietro (deve aprire, non richiedere lo sblocco in ciclo); `am force-stop` e 10 ingressi
  consecutivi da Gmail e da WhatsApp (mai «Prima configura MyCloud Gallery»).
- **F40/T-15**: una foto WhatsApp e un video da Chrome verso «Salva sul NAS», una volta a freddo (dopo `am force-stop`, con 3-5 s prima del tocco) e una col
  NAS spento: la schermata dice «In coda», la notifica «N di M file ricevuti» appare e «Ferma» ferma solo loro; con il preset «Notte» di giorno e a
  Wi-Fi il file sale subito; fuori casa parte al rientro; in release dice «Backup in prova» **prima** di copiare. Sul NAS e nella Libreria: data (di scatto,
  o d'arrivo, mai 1970), pannello informazioni e copertina del video. Gli stessi byte salvati in due cartelle: arrivano in tutte e due.
- **Posizione (F42 esteso al selettore e al provider)**: con «Rimuovi la posizione» accesa, scegliere dal selettore di MyCloud Gallery (da OneDrive, I miei
  file) e da «Allega file» di Gmail una foto del telefono con GPS, un HEIC, un video MP4 con GPS e un solo-NAS grande (1 GB): `exiftool -a -G1 -ee -gps:all
  -xyz -location* -xmp:all` sul file ricevuto non deve mostrare una posizione; il video grande dal selettore mostra la barra e «Annulla» funziona; da Gmail
  «Allega file» un video da 1 GB solo-NAS: quanto ci mette, e se i 3 minuti bastano sul WD.
- **F41**: «Modifica con…» di un solo-NAS in Samsung Gallery e in Google Foto («Salva» e «Salva copia»): la versione e' quella che l'editor ha scritto (il
  controllo sul tipo non rifiuta niente di buono).
- **T-14**: il selettore da OneDrive, I miei file e Google Drive; `PICK` classico con il tipo del MediaStore; sul telefono senza blocco schermo il messaggio
  dice cosa fare; con «foto selezionate» il selettore e «Recenti» mostrano solo quelle concesse; un filtro dello schermo acceso mentre si apre il selettore
  scarta i tocchi (R8).

## Ricevuti in coda (20/10)
Fonte: `docs/review/funzionale-5b.md` IN-7 (la parte che restava) e `docs/review/5b-fase5b.md` R15 (`incomingMedia`). Perimetro: `feature-backup` (pagina e
stringhe), in `:app` `backup/IncomingQueue.kt`, `backup/FiveBReceived.kt` (nuovo), `FiveBUi`, `FiveBGraph`, `FiveBModule`, `notify/`, `navigation/`,
`core-database` (`incomingMedia` e le query sugli arrivi), il piede della Libreria (`feature-library`, solo la frase). Un file in arrivo e' un media che
un'altra app condivide e che l'utente manda sul NAS con «Salva sul NAS»: il suo media nasce **invisibile** (presenza `none`) e finche' non e' sul NAS la
Libreria non lo mostra. E-X8 promette «con lo stato»; fino a qui nessuna schermata leggeva `incoming_item`, e gli arrivi `FAILED` tenevano la copia locale
per sempre senza modo di riprovarli o toglierli.

**La scheda e l'elenco**
- **Scheda «Ricevuti in coda»** nella pagina Backup (`ReceivedEntryCard`, accanto a «Da decidere»): **solo se c'e' almeno un file**; dice «2 in attesa · 1 in
  caricamento · 3 con un problema» (le parti a zero non ci sono); tono Attenzione con un problema, Lavoro se qualcosa sale, Attesa altrimenti; il tocco su
  «Vedi» apre l'elenco (`BackupScreen.RECEIVED`, rotta `backup/received`).
- **L'elenco** (`ReceivedScreen`): una scheda per file, **problemi per primi**, poi quelli che salgono, poi gli altri, a pari punto dal piu' vecchio. Ogni
  scheda: la **miniatura dalla copia locale** (`ThumbnailUtils` su un thread di lavoro; un segnaposto con il glifo del tipo finche' non c'e' o se non si legge),
  «Nella cartella Viaggi/Estate» (la cartella di destinazione: dato dell'utente, mostrato e mai nei log), lo **stato in una frase**, «Foto · 3,2 MB» e i tasti
  **«Riprova»** e **«Scarta»** (50 dp, uno a fianco dell'altro; per TalkBack «Riprova: Nella cartella Ricevute», perche' nell'elenco i tasti sono tanti e
  uguali). Un file che sale non ha tasti; uno senza copia sul telefono ha solo «Scarta». In fondo: «Scartare toglie solo la copia sul telefono: sul NAS non
  cambia niente.» Dopo un tocco una scheda in cima dice com'e' andata (rimesso in coda, non riprovo adesso perche'..., e' gia' sul NAS, scartato...).
- **Gli stati** (`ReceivedStatus`, 17 valori, codici stabili, un `when` esaustivo per il testo): in attesa (in coda, in attesa di rete, Backup in prova,
  cartella bloccata, NAS pieno, quota esaurita, serve la password, scritture ferme, in pausa, l'app non e' pronta), in caricamento (lo sto caricando,
  controllo che sia arrivato intero), con un problema (la copia sul telefono non si legge o e' cambiata, non e' arrivato intero, la cartella non puo'
  ricevere il file, la copia non c'e' piu', qualcosa e' andato storto). `ReceivedStates.of` (pura) li decide: per un file in coda vince il **cancello della
  sua share** (`TEST_ONLY`, sola lettura, congelamento/emergenza, pausa, famiglia non pronta), poi il motore (se sta girando e l'elemento e' a meta'
  cammino), poi il problema ricordato dal giro (NAS pieno, quota, password, NAS spento) o l'attesa delle regole (rete, Wi-Fi di casa, tetto dei dati);
  fascia, carica e preset Notte non entrano (IN-5: un salvataggio e' un gesto dell'utente). Un file che il motore ha lasciato ha il suo problema, dal problema
  dell'elemento del diario (`unreadable`, `verify_failed`, `unexpected`; senza un problema ricordato e' una cartella rifiutata).
- **Come si legge**: `IncomingDao.observeQueue` (un `SELECT i.*, b.state, b.problem ... LEFT JOIN backup_item` su `incoming_item`: un flusso che si rifa' anche
  quando avanza solo il diario del backup), unito allo stato del backup **ridotto** (senza i numeri che cambiano a ogni passo: l'elenco si rifa' solo se cambia
  una frase) e messo in `StateFlow` con `WhileSubscribed`. Niente I/O finche' nessuno guarda.

**«Riprova»** (`AppReceivedQueue.retry`, `AppIncomingQueue.blockOf/requeue/askJob`)
- Con **gli stessi cancelli di «Salva sul NAS»**: `destinationAllowed` ora e' `blockOf(dest) == null`, quindi la regola e' una sola. D12 e R2 (`TEST_ONLY`:
  «Backup in prova»), sola lettura, area che non e' piu' dell'utente (la share di una riga si confronta con l'area privata e comune di **adesso**), `Versioni/`
  e l'area dell'app, la radice di prova di D13; congelamento, emergenza e pausa **mettono in coda e aspettano**, come quando si salva. Se un cancello vieta non
  cambia niente (ne' la riga, ne' il diario) e non si chiede nessun lavoro: la risposta dice quale cancello (`ReceivedRetry.Blocked`).
- Un file `FAILED` o `GONE` con la copia locale: l'elemento del diario che ha rinunciato e' **terminale e non si riapre** (`UploadStateMachine`), quindi ne
  nasce **uno nuovo** (`createIncoming`, stessi byte, stessa destinazione; il media si cerca di nuovo con `incomingMedia`, quindi con le regole di R15) e la riga
  di `incoming_item` torna «in coda» legata a lui, **nella stessa transazione**. Un file in coda con l'elemento ancora aperto non ne ha bisogno: basta chiedere
  il lavoro. Il lavoro e' **un lavoro dell'utente come «Salva sul NAS»** (`onQueued`: IN-5, turno esclusivo dell'utente; UIDT solo oltre i 256 MiB).
- Senza la copia sul telefono: la riga si segna `GONE` e l'esito e' «la copia non c'e' piu'», niente da riprovare (resta «Scarta»). Un file che il motore ha
  finito ma di cui la riga non ha saputo (morte del processo fra i due passi) non si carica due volte: si sistema la riga e l'esito e' «e' gia' sul NAS».
- `IncomingStates.KEEP` ora comprende anche `GONE`: un arrivo «sparito» per un'unione del catalogo ha ancora il suo file e prima la pulizia dell'avvio lo
  toglieva in silenzio; adesso lo si puo' riprovare o scartare.

**«Scarta»** (`AppReceivedQueue.discard`)
- Dopo una **conferma** (foglio «Scartare questo file?»: «La copia sul telefono viene eliminata e il file non sara' salvato sul NAS. Sul NAS non cambia niente:
  se ci e' gia' arrivato, resta dov'e'»). Toglie la **copia locale** (solo il file di quella riga dentro `incoming/`, con un nome dall'hash; la cartella della
  condivisione se resta vuota) e poi la **riga** di `incoming_item`; se la copia non se ne va la riga resta (mai una copia che nessuna riga cita).
- **Non tocca mai il NAS**: nessun codice di questo percorso chiama il NAS. E **non e' «scartabile» un file che ha gia' i byte completi sul NAS**: da `UPLOADED` in
  poi il motore finisce da solo (rilegge, pubblica, collega) senza la copia locale, quindi scartare darebbe comunque un file sul NAS: e' «caricato». L'esito e'
  «e' gia' sul NAS, o sta per arrivarci» (`ReceivedDiscard.AlreadySaved`), niente cambia. Un file che **sta salendo adesso** (`HASHED` o `UPLOADING` con il motore in giro: i byte letti possono stare in
  memoria del motore, che li carica senza la copia locale) si scarta dopo (`Uploading`). Si scartano sempre `FAILED` e `GONE`, un file ancora `PLANNED` (aspetta il suo turno) e,
  con il motore fermo, anche `HASHED` e `UPLOADING`.
- L'elemento aperto del diario del backup **non si chiude da qui** (una corsa col motore che lo sta leggendo farebbe lanciare una transizione illegale a lui):
  senza la sua riga il motore lo trova al giro dopo, la copia locale non c'e' e lo chiude come `GONE` (provato sul grafo vero: nessun fallito, niente sul NAS,
  nessun arrivo aperto). Il media invisibile che era nato per lui resta nel catalogo senza posizioni (inoffensivo: se gli stessi byte tornano lo riusa, R14).

**Il piede della Libreria e le notifiche**
- **Piede** (`BackupStatusReducers.footer(..., incomingProblems)`, `BackupGraph.recompute`): se ci sono file con un problema (`FAILED`/`GONE`) dice **«N file ricevuti
  non salvati»** (codice `incoming_failed`, tono Attenzione, «Riprova o scartali da Backup.»; il tocco porta alla pagina Backup, dove la scheda e' in cima) **quando non ha
  niente di piu' urgente**: il piede muto («Backup non configurato»), «Tutto al sicuro» e «N in coda» gli lasciano il posto; un problema del backup, «Backup in
  prova», il confronto e un giro in corso restano al loro posto. Non e' un `BackupProblemCode` (non riguarda le foto del telefono): il piede lo conosce per codice
  stringa (`BackupFooterProblems`). Il conto e' una query sull'indice di `state`, mai il NAS; «Riprova» e «Scarta» avvisano `BackupGraph.kick` perche' si ricalcoli.
- **Notifica del lavoro** (id 1007): «2 di 5 file ricevuti · 1 con un problema». Un file che non si e' caricato non e' «fatto»: prima usciva dalla coda e la
  notifica diceva «5 di 5». `BackupProgress.incoming(done, total, problems)`; `followIncomingProgress` conta i problemi nati nel giro.
- **Notifica dei problemi** (id 1008, canale «Problemi del NAS», `IncomingProblemNotification`): «File ricevuti non salvati» e il numero, **solo numeri**; resta dopo la
  fine del lavoro (quella del lavoro se ne va con lui). La posta `IncomingProblemWatcher`, che segue il conto dei problemi per tutta la vita del processo: la **prima
  lettura** e' il punto di partenza e non avvisa; un conto che **sale** con l'app in secondo piano avvisa; con l'app davanti no (lo vede il piede); a zero la
  notifica se ne va. **Il tocco porta alla riga**, senza rompere C17 (il tocco di una notifica apre soltanto l'app: intent del launcher, immutabile, senza extra):
  come per «Libera spazio» dopo la notifica «Pronto», l'app sa dove andare (`FiveBUi.receivedRequests`, `FiveBHostEffect`) e al ritorno apre la pagina Backup se non
  e' gia' su una pagina del backup. Nessuna azione sulla notifica: «Riprova» e «Scarta» si toccano nella pagina, dopo il blocco dell'app.

**R15: `MediaCatalog.incomingMedia`**
- Prima prendeva **qualunque** versione con lo stesso SHA-256: un arrivo uguale a un media **nascosto** gli si agganciava (il file salvato non si vedeva e il nascosto
  prendeva una copia che non aveva chiesto: nascosto contagioso, E-H1) e uno uguale a un media di **un altro account** diventava «mio». Adesso valgono solo i media
  **dello stesso account** (`owner_account`) **e non nascosti**; un media ancora senza posizione (il primo arrivo uguale, in volo) e' dell'utente e vale, cosi' R14 resta
  com'era (gli stessi byte in due cartelle: un solo media, due elementi); fra piu' candidati si preferisce quello che si vede; **nel dubbio ne nasce uno nuovo**. Sul grafo
  vero (`RicevutiInCodaAppTest.r15_*`): la foto nascosta e lo stesso file ricevuto → un media nuovo, visibile appena e' sul NAS; il nascosto resta nascosto e senza copie
  in piu'. Il catalogo non li fonde (provato).

**Contratti cambiati e nuovi**
- Nuovi (`feature-backup/received/`): `ReceivedQueue` (porta: `items`, `thumbnail`, `retry`, `discard`; opzionale per Dagger, `NoReceivedQueue`), `ReceivedItem`,
  `ReceivedStatus`, `ReceivedPhase`, `ReceivedCounts`, `ReceivedRetry`, `ReceivedDiscard`; `BackupScreen.RECEIVED`; `BackupPageUi.received`; `BackupPageViewModel(received)`;
  `ReceivedViewModel`, `ReceivedScreen`, `ReceivedRoute`, `ReceivedEntryCard`; il finto `FakeReceivedQueue` e `ReceivedSamples` (package `testing`).
- `core-database`: `IncomingDao.observeQueue/queueRow/countInStates/observeCountInStates/delete`, `IncomingQueueRow`; `MediaCatalog.incomingMedia` (R15). Nessuna
  migrazione: nessuna tabella nuova.
- `:app`: `AppReceivedQueue`, `ReceivedStates`, `IncomingProblemWatcher` (`backup/FiveBReceived.kt`); `AppIncomingQueue.blockOf/destinationOf/requeue/askJob`;
  `AppIncomingLocalFiles.localFileOf/discardLocal`; `IncomingStates.PROBLEMS/LISTED` e `KEEP` con `GONE`; `FiveBGraph.receivedQueue`; `FiveBUi.receivedRequests/openReceived`;
  `FiveBModule.receivedQueue`; `BackupProgress.problems`; `IncomingProblemNotification/IncomingProblemNotices`, `AppNotifications.ID_INCOMING_PROBLEMS`;
  `BackupStatusReducers.footer(..., incomingProblems)` e `INCOMING_FAILED_CODE`.
- **Toccato fuori dall'elenco del brief, con il minimo** (necessario): `backup/BackupGraph.kt` (due punti: passa `backupStatus` e `onReceivedChanged` a `FiveBGraph` e legge il
  conto dei problemi nel ricalcolo del piede), `backup/BackupStatusReducers.kt` (il parametro del piede), `work/BackupJobService.kt` (`followIncomingProgress` conta i
  problemi del giro: senza, la notifica 1007 non poteva dirli).

**Sicurezza**
- Nessun permesso nuovo, nessun componente nuovo nel manifest. La notifica 1008 e' una notifica come le altre: solo numeri (la versione pubblica non ne ha nemmeno), il tocco
  apre solo l'app (C17). «Riprova» non scavalca nessun cancello (la regola e' quella di «Salva sul NAS», una sola); «Scarta» non chiama il NAS e non esce da `incoming/`
  (un nome di riga che non sia un hash non tocca niente: `ReceivedGatesTest.scartaNonEsceMaiDaIncoming`). Mai nomi di file, percorsi, share o account nei log. R15 chiude il
  caso in cui un arrivo diventava il media di un altro account o rendeva visibile, di fatto, qualcosa di nascosto.

**Test** (prima → dopo)
- R15: `CatalogFiveBTest.r15_*` (4), rossi prima su `incomingMedia` com'era (3 su 4; il quarto e' la guardia del caso normale), poi verdi; `RicevutiInCodaAppTest.r15_*` (grafo vero).
- Query: `IncomingQueueDaoTest` (5: l'elenco con lo stato dell'elemento, la riga singola, i conti come flusso, il caricamento che avanza che rifa' la coda, «Scarta» che toglie
  solo la riga).
- Pagina e elenco: `ReceivedViewModelTest` (10), `ReceivedScreenTest` (15: ogni stato con la sua frase, cartella/tipo/peso, i tasti per stato, i nomi per TalkBack, la conferma, le frasi dopo
  un tocco, la miniatura, 48 dp e niente sovrapposizioni a 1x e a 1,3x), `ReceivedTabletLayoutTest`, `ReceivedEntryCardTest` (la scheda: assente a zero, i conti, il tocco; la pagina
  la mostra insieme a «Da decidere»; il ViewModel della pagina).
- Stati e cancelli: `ReceivedStatesTest` (pura: ogni stato, il cancello che vince, `canDiscard`), `ReceivedGatesTest` (10, con il Room vero: ogni stato del cancello, la share,
  la radice di D13, `Versioni/`, il file grande, l'elemento aperto, «Scarta» e il motore).
- Grafo vero in `:app`: `RicevutiInCodaAppTest` (13: la lista che dice «in attesa» e si svuota quando il file e' sul NAS; «Riprova» dopo una copia cambiata, due volte, la seconda
  buona: il file sale riletto; in release dice «Backup in prova» e non cambia niente; verso `Versioni/`, l'area dell'app e una share altrui dice «cartella bloccata»; senza copia
  locale; «Scarta» che toglie riga e copia e lascia il NAS **byte per byte** com'era, e il motore che chiude l'orfano; un file gia' pubblicato che non si scarta; un file caricato;
  il piede; la notifica e la richiesta di aprire la pagina; R15), `FiveBButtonsTest` (la porta legata al grafo vero, non al null-object).
- Notifiche e piede: `IncomingProblemNotificationTest`, `IncomingProblemWatcherTest` (anche le regole del piede), `BackupFooterTest.iFileRicevutiNonSalvati...`,
  `BackupFooterProblemsTest`.
- **Prove di mutazione** (tolta a mano la correzione, tutto il resto com'e', poi rimessa): con `incomingMedia` come prima sono rossi `CatalogFiveBTest.r15_*` (3) e
  `RicevutiInCodaAppTest.r15_*`; con «Riprova» senza `blockOf` sono rossi `ReceivedGatesTest` (3: i cancelli, la share, la radice di D13) e
  `RicevutiInCodaAppTest.riprovaInRelease...` e `riprovaVerso...`; con «Scarta» senza la guardia di `canDiscard` sono rossi `ReceivedGatesTest.scartaGuardaLoStatoDelMotore` e
  `RicevutiInCodaAppTest.unFileGiaPubblicatoNonSiScarta...`.
- Screenshot in `docs/screenshots/5-IN7/` (chiaro e scuro, telefono e tablet, testo grande): l'elenco tipico, solo problemi, solo attese, vuoto, la frase dopo «Riprova» e quando
  e' bloccata, la conferma di «Scarta», la pagina Backup con la scheda (anche solo attese) e senza. Si rifanno con
  `gradlew :feature-backup:recordRoborazziDebug --tests "*Received*Shots*"`.
- **Esiti** (20/10, `--max-workers=4`, una build alla volta): `:core-database` **160 test, 0 falliti**; `:feature-backup` **564, 0 falliti**; `:feature-library` **530, 0 falliti**; `:app`
  **512, 0 falliti**. `tools\check.ps1` (engine, checksum, `assembleDebug`, `assembleRelease`, `galleryTest`, `galleryLint`, ispezione dell'APK di release, D12/R2 nel dex) **tutto OK**,
  con una avvertenza: `VerificaFunzionaleBackupAppTest.bk5_glIStatiInCorsoSonoCumulativiENonTornanoIndietro` **e' instabile gia' su HEAD** (su una copia pulita del commit `6698c00` fallisce
  5 volte su 6, `expected: 24 but was: 23`: il catalogo conta un media fatto prima che il motore lo conti, e il primo stato dell'avanzamento ha un fatto in meno), non c'entra con questo
  lavoro e sta fuori dal perimetro; per far finire il controllo l'ho tolto **solo in una copia di verifica** del repo (`@Ignore`), non nel repo. Segnalato come attivita' a parte.
  Anche i test di `:core-library` sono caduti una volta per un crollo della JVM di prova (`EXCEPTION_ACCESS_VIOLATION` nella grafica nativa di Robolectric, modulo non toccato) e alla seconda
  esecuzione e' passato.

**Limiti e punti aperti**
- «Riprova» di un file la cui copia sul telefono e' **cambiata** (stessa dimensione, byte diversi) lo rimette in coda e il motore ritrova lo stesso problema: l'utente vede di
  nuovo «La copia sul telefono non si legge o e' cambiata» (e puo' scartarlo). Non si rilegge tutto il file in «Riprova» per non farlo due volte (lo fa il motore).
- Un file in coda con il motore fermo e l'elemento a meta' caricamento (`UPLOADING`) si scarta: il temporaneo sul NAS lo toglie il motore quando chiude l'orfano (`GONE`), non «Scarta».
- Il media invisibile di un arrivo scartato resta nel catalogo senza posizioni (inoffensivo).
- La notifica dei problemi non si rifa' se l'utente la toglie a mano e il conto non sale; e quella di un processo precedente non si aggiorna (il piede e la pagina dicono il numero giusto).
- Il tocco sulla notifica porta alla pagina Backup anche se l'utente apre l'app a ore di distanza da un'altra via, come «Libera spazio» dopo «Pronto»: stesso compromesso, scelto per C17.
- Il piede dice i file non salvati solo se non ha niente di piu' urgente: durante un giro in corso o con un problema del backup non compaiono, ma la scheda nella pagina Backup c'e' sempre.

**Da provare sul campo** (F40, solo `mcg-prova`, D12/D13)
- Una foto WhatsApp e un video da Chrome verso «Salva sul NAS», col NAS **spento**: nella pagina Backup compare «Ricevuti in coda» con «2 in attesa», nell'elenco le miniature, la
  cartella e «In attesa di rete»; accendere il NAS e vederli salire («Lo sto caricando sul NAS.») e sparire; la Libreria li mostra solo adesso.
- In release la scheda dice «Backup in prova» e «Riprova» risponde «Non riprovo adesso. Backup in prova...» senza cambiare niente.
- Far fallire un arrivo (cambiare la copia in `incoming/` non si puo' senza root: usare un NAS pieno, o la share in sola lettura): la scheda dice «con un problema»; il piede della
  Libreria dice «N file ricevuti non salvati» e porta alla pagina; con l'app in secondo piano arriva la notifica «File ricevuti non salvati» e il tocco apre la pagina Backup.
- «Scarta» con la conferma: la copia sparisce dall'elenco e, controllato da PC, **il NAS non e' cambiato**; un file che sta salendo non si scarta; uno gia' arrivato dice «E' gia' sul NAS».
- TalkBack sull'elenco: ogni scheda e' una frase, i tasti dicono la cartella; testo a 1,3x e schermo da tablet.
