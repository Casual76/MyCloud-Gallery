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

## Note per il pacchetto P9 (integrazione) dalla Fase 2
- P3: collegare le porte nuove di registro e ciclo di vita. `LocalDataEraser` **non** deve cancellare `wipe-journal`, che serve a riprendere un azzeramento. `FamilyReset` espone `recoveryCodeForDisplay` per mostrare il codice del kit nuovo. `AdminRecovery.recover` senza il QR "stato della famiglia" prende l'ancora dal nome del file sul NAS: va bene, ma solo con l'avviso in Diagnostica.
- P5: legare `QrCodec` a `ZxingQrCodec` (oggi è `UnavailableQrCodec`).
- P1: collegare `SmbNasSessionManager` e `SmbNasFileSystem`; il cancello di P2 deve negare quando la rete locale non è consentita; F11 (release con R8) da provare. **Orologio del NAS avanti di ~53 s** rispetto all'NTP (misurato dal PC con la NEGOTIATE): da controllare la sincronizzazione NTP nella dashboard del NAS. Non è un problema per la fiducia, perché l'ora di riferimento è quella del NAS, ma è un segnale che l'NTP non sta lavorando.
- P8: legare `AndroidBackgroundHealthReader`, `ActivityRulesEngine`, `TrafficLedger`, `ConnectionTestInputSource`, `HeartbeatSource`. I contributori di rete, NAS e famiglia usano gli ID di `DiagCodes`. Da stringere in `core-common`: `ExportValue.Enum.restore` accetta identificatori liberi dal file del log (l'esportatore li scarta comunque).
- P4: collegare `NasAccountStore` alla sessione di P1, poi `OnboardingPorts`, `DefaultFamilySetupRepository`, `ApprovalWatcher`. Nel passo 12 il codice di recupero non viene mai salvato: se l'app si riavvia tra la generazione e la verifica, si riparte con una chiave nuova (comportamento voluto).
- P2: usare `EnvironmentDeviceStateSource` per la rete del motore delle regole (al posto di `AndroidDeviceStateSource`). P6 e P7 usano `VpnControlConfigStore` e `NasSessionActivity` (non fake locali).
- Residui della revisione N2, R2, R3, R4: chiusi dal P3 (`ReviewRound3Test`, verificati con mutanti, senza una terza revisione indipendente). M3 resta in attesa della decisione 2.

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
