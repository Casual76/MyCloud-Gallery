# Fase 6 di MyCloud Gallery: progetto tecnico (ricerca)

> Base per i brief dei sub-agent. Progetto del 21/10/2026. Fonti:
> - piano: FASE 6 (6.1–6.4), §1 (glossario: calcolo AI, ruoli, indicizzatore, replica locale), §2 ("Indice e ruoli", "Extra":
>   cronologia locale), §3 (proposte 1, 3, 9), §4 (ricerca sotto 300 ms nel 95% delle query su 100 mila elementi, anche su tablet
>   e offline; almeno l'80% delle foto attese tra i primi 20 risultati su 50 query italiane), §6 (E-A, E-Q, E-I4, E-I10, E-I11,
>   E-I12, E-H1, E-Q7), §7 (catena di fornitura, manifest firmato, nessuna telemetria);
> - Fase 0: `docs/research/embeddinggemma2.md`, `docs/research/ocr-volti.md`, `docs/decisioni.md` ("AI", "EmbeddingGemma 2: prova
>   con 700 foto", "DECISO: spazio standard float", "Ricerca: requisito emerso dalle prove"), i risultati dello spike in
>   `spike/results/embedprobe-s25/`;
> - il **codice** da cui la fase parte (verificato il 21/10): `core-trust` (`IndexSegment.kt`, `DeviceHead.kt`, `Leases.kt`,
>   `DeviceStatusReport.kt`, `TrustFormat.kt`), `core-index` (`Publishing.kt`, `OutboxIndexPublisher`, `IndexWriteRules`,
>   `LeasedWorkUnits`, `WorkPorts.kt`, `LocalReplica`, `BlobIndex`, `Fusion`, `IndexSyncEngine`, `ReplicaQualityController`),
>   `core-prepare` (`Preparation.kt`, `OutputPlan.kt`, `VideoPreparer.kt`, `FrameTimes.kt`), `core-rules` (`BackgroundActivity`,
>   `RuleEvaluator`, `StagedRun`, `ConsumptionEstimator`, `BacklogSource`), `core-database` v4 (`DerivedEntities.kt`,
>   `LibraryDaos.kt`, `TimelineIndexDao`, `MediaCatalog`), `core-model` (`Embedding.kt`, `WorkState.kt`, `CellFlags.kt`),
>   `core-family` (`LocalDeviceProfile`, `DeviceStatusBook`, onboarding passo 7), `core-places` (`Places.kt`), `core-library`
>   (`Timeline.kt`, `Viewer.kt`, `FilteredTimelineView`), `app/.../main/MainScaffold.kt`, `app/.../work/`, il manifest e
>   `gradle/verify-release-manifest.gradle`, `versions-app.gradle`, `tools/check.ps1`;
> - `docs/formato-nas.md` (§2, §5.5, §5.5-bis, §5.6, §5.10, §6.2, §7, §10), `docs/modello-dati.md` (§2, §5, §6, §9, §12),
>   `docs/regole-attivita.md` (§2, §3, §4, §6), `docs/sicurezza.md` (C9, C19, C21, C22, §4-bis, §6, §7 "Fase 6", §8),
>   `docs/android.md` (temi 1, 6, T-23, novità di Android 17), `docs/luoghi.md`, `docs/grafica-progetto.md` (§12),
>   `docs/fase4-progetto.md` (D4–D16, §2.10), `docs/fase5-progetto.md` (§2.10, D10, D36), `docs/review/4a-fase4a.md` (R1, R2),
>   `docs/prove-campo-esiti.md`;
> - fonti pubbliche consultate il 21/10/2026 (elenco al §10).
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F5-Dn` e `F4-Dn` = decisioni delle Fasi 5 e 4; "R1 della 4A" = voce della
> revisione di sicurezza; `Fn` = prova sul campo (la Fase 5 arriva a F45, qui F46–F71); `Qn` = domanda del §9; `Pn` = pacchetto
> del §4.4; `Ln` = limite di `docs/sicurezza.md` (qui L45–L56).
>
> Contesto:
> - **La Fase 5 è implementata e revisionata** (5A e 5B: revisione di sicurezza e verifica funzionale, correzioni fino al 21/10). Il
>   blocco A delle prove sul campo è quasi tutto passato (`docs/prove-campo-esiti.md`). **In sospeso**: il blocco B (prove con le
>   altre app e l'utente), Q3 della Fase 5 (D12 "backup solo sulle share di prova" resta acceso), il primo backup vero (F32) e P16
>   (riordino dei vecchi backup). **La Fase 6 non dipende da nessuno di questi punti**: non tocca il backup né i media, scrive
>   solo nell'area dell'app (`.mycloudgallery/`) e le sue prove usano le share di prova.
> - **L'indice condiviso sulle share vere è ancora "solo locale"** (F4-D31, `FeatureFlags.indexPublishTestSharesOnly = true`
>   anche in release). "Una volta per tutta la famiglia" (6.2) funziona quindi sulle share di prova; sulle share vere ogni
>   indicizzatore calcola per sé finché l'utente non spegne F4-D31 (Q7). La Fase 6 funziona in entrambi i casi.
> - **Cosa le fasi precedenti hanno lasciato pronto** (verificato nel codice):
>   - l'attività `AI_COMPUTE` (pesante, quota `mediaProcessing`, ruolo `INDEXER`, `cpuIntensive`, `canRunOnLocalBuffer`) e il
>     verdetto `Run(LOCAL_BUFFER_ONLY)` quando gli unici ostacoli sono di rete (`RuleEvaluator.kt:182-184, 257-258`);
>   - le stime (`DeviceMeasures.aiItemsPerSecond = 2.8`, `aiBatteryPercentPerThousandItems = 4.0`, `BacklogFacts.toAnalyze`) e la
>     sorgente di traffico `TrafficSource.AI_COMPUTE`;
>   - l'anteprima di analisi (`SegmentClass.ANALYSIS`: lato lungo **1024 px**, JPEG q90, già raddrizzata, trasparenza composta su
>     bianco per l'OCR) e i fotogrammi dei video (`FRAMES`: copertina più fino a 8 fotogrammi, uno ogni 30 s, JPEG 1024 q75, al
>     massimo 9 in un blob da 2 MiB) prodotti da `core-prepare`;
>   - le prenotazioni (`LeaseManager`, 1 ora, rinnovo 20 minuti) e le unità di lavoro (`LeasedWorkUnits`, 200 media per mese di
>     scatto, chiave `work/1/<passaggio>/<mese>/<parte>`);
>   - il tipo `EmbeddingSpace` (modello + dimensione + precisione, uguaglianza su tutti e tre: E-I4) e `EmbeddingVector` (indice del
>     fotogramma, `computedFrom` per E-A10); la tabella `embedding` (dalla v1) e gli stati di lavoro `EMBED` e `OCR`, che ogni media
>     nuovo riceve già in coda (`WorkRules.initialStages`);
>   - il ruolo `indexer` nello stato firmato del dispositivo (`DeviceStatusReport.roles`, tipo 8, area comune);
>   - la scheda **Cerca** della barra (`GalleryTab.Search`) con un segnaposto (`MainScaffold.kt:267`, "La ricerca arriverà qui").
> - **Cosa le Fasi 4 e 5 promettevano per la Fase 6 e nel codice non c'è** (la Fase 6 lo costruisce o lo cambia, §0.3):
>   1. **Nessun buffer di analisi.** `analysis` e `frames` degli altri non si scaricano mai (`ReplicaQualityController.DOWNLOADABLE`
>      = {thumbS, thumbM, hq}; i loro segmenti restano `SKIPPED`) e i propri non si tengono (`LocalReplica.applyOwn`). Con F4-D31
>      acceso, sulle share vere le anteprime di analisi preparate dal backup (F5-D10) o dalla preparazione **si buttano**
>      (politica R2 della 4A): il "gli Indicizzatori le trovano nell'indice" del §2.10 della Fase 5 vale solo sulle share
>      pubblicabili (D10).
>   2. **`WorkPass` ha solo `PREP`**: nessuna unità di lavoro per il calcolo AI (D9).
>   3. **Le regole dell'indice penalizzano chi aggiunge dati derivati senza aver preparato**: un blob vale solo se lo **stesso**
>      dispositivo ha un record presente con la stessa chiave nella stessa share (attestazione, R1 della 4A, `BlobIndex.kt:17-22`);
>      il "fatto" del lavoro richiede che lo stesso dispositivo abbia pubblicato `thumbS` (`RemotePublishedClasses`, evidenza
>      `THUMB_S`, R5). Vettori e testo di un indicizzatore che non ha preparato sarebbero ignorati (D7, D9).
>   4. **`vec` e `ocr` sono riservati e rifiutano la testa intera** (`DeviceHead.kt:134-139`); `IndexSegments.checkBody` manda ogni
>      classe sconosciuta al controllo delle immagini; `toBlobPut` restituisce `null` per le altre classi. Un'app vecchia
>      rifiuterebbe una testa `index` con classi nuove (D6).
>   5. **La tabella `embedding` non la scrive e non la legge nessuno**: `MediaCatalog.putEmbedding` non ha chiamanti, non c'è una
>      lettura nel catalogo; **nessuna tabella OCR né FTS** (D23, D25, D37).
>   6. **I ruoli si scelgono solo all'onboarding** (passo 7): in Impostazioni sono in sola lettura (`DeviceDetailScreen`); il
>      consiglio dell'onboarding è "telefono Indicizzatore e Preparatore, tablet solo Preparatore" (D15).
>   7. **Le code `EMBED` e `OCR` per media non le consuma nessuno**, e le code di lavoro (`nextPending`) non escludono i nascosti
>      (giusto per il calcolo, da non far trapelare: D29).
>   8. **Nessuna ricerca nei luoghi per nome**: `core-places` fa solo il percorso punto → luogo, con un solo nome italiano per luogo,
>      niente alias; sui media si salva `place_id` solo per `CITY` e `NEAR`, la regione si ricava da `Place.admin1` (D26).
>   9. **Il viewer e le viste assumono l'ordine della timeline** (`FilteredTimelineView` con ricerca binaria, `SubsetView` che
>      riordina): risultati in un ordine diverso chiedono una vista nuova (D28).
>   10. **Il filtro rapido "Documenti"** promesso dalla Fase 3 (D16 della Fase 3, `Timeline.kt:105`) non c'è (D34).
>   11. **`versions-app.gradle` vieta ogni SDK che parli con Internet** (righe 12-13) e per i QR ha escluso apposta ML Kit
>       (riga 62); `verifyReleaseManifest` non controlla `uses-native-library`: le due dipendenze nuove e il primo traffico
>       Internet dell'app vanno decisi e scritti (D38, Q1, Q4).
>   12. **La cattura del traffico di ML Kit** (Fase 0.4, `docs/sicurezza.md` §8, "Aperto") non è mai stata fatta (D12, F56).
>   13. **Nessuna misura sul Tab S9**: lo spike ha misurato solo l'S25; manca il confronto salvato 270M contro 440M (il codice c'è,
>       `TestWorkflows.kt:216`; il confronto incrociato dà 0,98 di sovrapposizione); nessun codice di valutazione (recall@k) né
>       un set etichettato (§5.3, P9).
>   14. **`litertlm-android` è compilata con Kotlin 2.4**: lo spike (Kotlin 2.2.20, come l'app) ha dovuto usare
>       `-Xskip-metadata-version-check`, e porta a runtime `kotlin-reflect` 2.4.0 e coroutines 1.11.0 (D2, rischio al §7).
>   15. Commenti superati: `WorkStage.ANALYSIS_COPY` e `DeviceMeasures.analysisPreviewBytes` dicono "~768 px", il codice produce
>       1024 px (P1 li corregge).
>   16. Nessuna voce della Diagnostica per ricerca e AI (`DiagSection`, `DiagJob`), e `StorageContributor` non elenca modelli e
>       indice (P11).
> - **Le misure della Fase 0** (spike sull'S25, 12/10, anteprime a 768 px, 70 token): 440M generico su GPU **4,4 foto/s** di solo
>   embedding (p50 183 ms), **2,8 foto/s** con la decodifica degli originali, PSS 850 MB, −26% con il caldo, ~4% di batteria ogni
>   1000 foto; query di testo **270M su CPU 53–58 ms** (440M su CPU 49–53 ms); indice GPU con query CPU 270M: **98%** dei primi 10
>   uguali (Spearman 0,999); coseno GPU–CPU 0,9997 (testo) e 0,997 (immagini); punteggi piatti (0,65–0,70) su 200 foto. Avvio a
>   freddo del motore 3,6 s.
> - **Le librerie oggi** (verificato il 21/10): `litertlm-android` **0.18.1** (20/10/2026: corregge un'incompatibilità ABI di
>   EmbeddingGemma 2 nelle build da sorgente); i file del modello su Hugging Face sono **invariati** dal 6/10 (440M commit
>   `e301f74d…`, 270M commit `9be6e8b9…`, licenza Apache 2.0, non "gated"); `com.google.mlkit:text-recognition` **16.0.1** è ancora
>   l'ultima (07/08/2024).
> - **In parallelo**: il filone grafico (G3 del viewer). La Fase 6 consegna stati e contratti con composable funzionali (D36).
> - **Le risposte dell'utente del 21/10 a Q1–Q10 sono integrate** in decisioni, flussi, pacchetti, prove e sicurezza; le risposte
>   diverse dalle opzioni (Q3, Q4, Q9, Q10) hanno cambiato il progetto: lettura del testo da attivare (D41, D42), testo dal vivo e
>   ritaglio del soggetto nel viewer (D43–D47, §2.10), due ordini dei risultati (D48). Il riepilogo è al §11.
> - **Seconda tornata del 21/10 (Q11–Q14) integrata**: MagicTouch dentro l'app, ritagli in `Pictures/MyCloud Gallery/Ritagli/` e nel
>   backup, lettura del testo per account con esclusione di un dispositivo; **foto in movimento** (Q14, risposta diversa dalle
>   opzioni): la pressione prolungata diventa testo dal vivo o ritaglio anche su di loro, il video parte dal badge "Movimento", e
>   all'apertura una breve animazione arriva fino all'istante della foto (D45, D50, D51, §2.11, P14).
> - **Terza tornata del 21/10 (Q15, Q16) integrata**: il video avviato dal badge "Movimento" ha l'audio, al volume dei contenuti
>   multimediali (l'animazione d'apertura resta sempre muta); un interruttore "Animazione delle foto in movimento" nelle Impostazioni
>   della Libreria, acceso di default, impostazione personale sincronizzata fra i dispositivi dell'utente (D50, D51, §2.11, P0, P14).

## 0. Sintesi

**Che cosa consegna la Fase 6**
- **Modelli on-device** scaricati una volta, verificati con l'hash fissato nell'app **prima di ogni caricamento**, con lo spazio
  mostrato e la rimozione; il modello delle query (270M, 157 MiB) su ogni dispositivo, quello per indicizzare (440M, 370 MiB) solo
  sugli indicizzatori. Il NAS fa da distributore per la famiglia (D17, D18).
- **Calcolo AI una volta per tutta la famiglia**: vettori (EmbeddingGemma 2) e testo letto (ML Kit) calcolati sulle anteprime di
  analisi e sui fotogrammi, coordinati con le prenotazioni, pubblicati firmati in una testa `ai` dell'indice di ogni share, mai
  dagli originali; le aree private solo dai dispositivi del proprietario (D6–D14, D22). Il ruolo Indicizzatore lo sceglie ogni
  utente per i propri dispositivi (Q3); la **lettura del testo** è una funzione da attivare, spenta di default, perché ML Kit manda
  a Google statistiche tecniche (Q4, D41); il testo letto da un dispositivo vale per tutta la famiglia (D42).
- **Un motore di ricerca locale** che risponde senza rete: indice vettoriale a due passate, indice del testo con tolleranza ai
  refusi, analizzatore delle query italiane (date, feste, stagioni, luoghi, tipi), fusione con una soglia di convinzione
  calibrata e "Mostra anche risultati meno probabili", "Trova simili", copertura dichiarata, nascosti mai (D23–D31).
- **La schermata Cerca**: chip modificabili, suggerimenti, ricerche recenti locali e cancellabili, stati vuoti e d'errore utili,
  risultati con lo stesso viewer in due ordini, "Recenti" (predefinito) e "Pertinenti" (D48); **Impostazioni → Ricerca e
  modelli** (ruolo Indicizzatore, lettura del testo, modelli, avanzamento, aree senza indicizzatori); "Trova simili" nel viewer; il
  filtro rapido **Documenti** in Libreria (D32–D36).
- **Nel viewer, come su Samsung e Apple** (Q9): **testo dal vivo** (pressione prolungata sul testo, selezione con le maniglie,
  Copia) e **ritaglio del soggetto** (pressione prolungata sul soggetto, Copia, Condividi, Salva come nuova foto), on-device, senza
  Play Services né servizi esterni (D43–D47, §2.10). Le **foto in movimento** si animano per un istante all'apertura fino al momento
  della foto e il video intero, con l'audio (Q15), parte dal badge "Movimento"; un interruttore nelle Impostazioni della Libreria
  spegne l'animazione (Q14, Q16, D50, D51, §2.11).

**Moduli nuovi**
- `core-models`: catalogo dei modelli fissato nell'app, sorgenti (NAS, HTTPS, cartella dal PC), scaricamento con ripresa, verifica,
  spazio e rimozione, copia sul NAS. **L'unico modulo dell'app che apre una connessione HTTPS** (D33).
- `core-ai`: i motori on-device (vettori del testo e delle immagini con LiteRT-LM, OCR con ML Kit solo se attivato, ritaglio del
  soggetto con MagicTouch su LiteRT), la preparazione degli ingressi, il **calcolo AI** (unità, prenotazioni, buffer, ripresa,
  pubblicazione). L'unico modulo che vede `litertlm`, `litert` e ML Kit.
- `core-search`: indice locale (vettori e testo), analizzatore delle query, fusione e soglie, "Trova simili", copertura,
  cronologia. Nessuna rete, nessun NAS.
- `feature-search`: la schermata Cerca.

**Aggiunte ai moduli esistenti**
- `core-trust`: classi `vec` (7) e `ocr` (8), testa di tipo `ai`, codec ostile, versione 2 del formato solo per i file `ai` (D6);
- `core-index`: pubblicazione e replica della testa `ai`, attestazione e fusione dei dati derivati, `WorkPass.AI`, scaricamento di
  `analysis`, `frames` e `hq` nel buffer degli indicizzatori (D7–D10);
- `core-database` (schema **v5**), `core-model` (`CellFlags.DOCUMENT`, ricetta), `core-rules` (misure del calcolo AI e dell'OCR),
  `core-family` (ruoli modificabili), `core-library` (vista ordinata, filtro Documenti), `core-prepare` (uscite di analisi verso il
  buffer), `core-personal` (impostazioni sincronizzate: lettura del testo, ordine dei risultati, animazione delle foto in movimento), `core-mediastore` (ritagli
  salvati), `feature-settings`, `feature-viewer` (testo dal vivo, ritaglio, "Trova simili"), `feature-library`,
  `feature-diagnostics`, `benchmark`, `:app`.

**Quindici pacchetti** (§4.4; P12 e P13 aggiunti con le risposte del 21/10, P14 con la risposta Q14). Opus per i quattro fondanti o
difficili: P0 (fondamenta e contratti), P2 (formato `ai` e indice condiviso), P5 (calcolo AI), P6 (motore di ricerca); sonnet per
gli altri undici.

**Tappe**
- **M1**: sull'S25 (build debug, account di prova) i due modelli scaricati e verificati, l'area di prova indicizzata, una ricerca
  per contenuto e una per testo trovano le foto giuste.
- **M2**: l'area di prova indicizzata **una volta sola** con due indicizzatori (prova di E-I1; nella famiglia l'utente farà fare tutto
  all'S25, Q3); Cerca completa (chip, testo, filtri, "Trova simili", copertura, cronologia, i due ordini); testo dal vivo,
  ritaglio e foto in movimento nel viewer; il tablet non indicizzatore e con la lettura spenta cerca come il telefono (E-Q8) e usa
  il testo letto dall'S25 (D42).
- **M3**: obiettivi del §4 del piano misurati: p95 sotto 300 ms con 100 mila elementi su S25 e Tab S9, ≥ 80% al primo 20 sulle 50
  query italiane, nessun traffico durante le ricerche.
- Chiusura: revisione di sicurezza e verifica funzionale; con il consenso (Q7) la pubblicazione sulle share vere.

**Test senza dispositivo**: fake fedeli dei motori (vettori deterministici, latenze e guasti iniettabili), NAS finto e simulatore
SMB della Fase 4, server HTTPS di prova del JDK, Libreria sintetica da 100 mila elementi; test di parità fra fake e motori veri sul
dispositivo (§5.2).

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)
| # | Decisione | Perché |
|---|---|---|
| D1 | **Una fase sola, tre tappe** (nessuna divisione 6A/6B). Niente di distruttivo: sul NAS si scrivono solo file nuovi nell'area dell'app (teste e segmenti `ai`, copie dei modelli nella cartella del proprio dispositivo), dietro gli stessi cancelli dell'indice (F4-D31, congelamento, emergenza, salto dell'ora, fiducia, sola lettura) | La divisione della Fase 5 serviva a far arrivare ciò che toglie o sposta dopo un backup provato; qui nulla toglie o sposta un media |
| D2 | **Runtime: `com.google.ai.edge.litertlm:litertlm-android` 0.18.1, `EmbeddingEngine` diretto** (niente MediaPipe, niente ML Kit per gli embedding, che non esiste ancora). Dietro interfacce di `core-ai` (`TextEmbedder`, `ImageEmbedder`); versione fissata; test di regressione dei vettori su immagini e testi sintetici (coseno ≥ 0,99 con i vettori di riferimento). La compilazione di `core-ai` usa `-Xskip-metadata-version-check` (solo lì, come lo spike); P0 decide con un test se tenere `kotlin-reflect` 2.4.0 e coroutines 1.11.0 portate dalla libreria o fermarle alle versioni dell'app | Strada A della ricerca 0.3: la più leggera, con `outputSize` e `ModelInfo`; la 0.18.1 corregge un difetto ABI proprio di EmbeddingGemma 2 |
| D3 | **Modelli e acceleratori come deciso in Fase 0**: indicizzatori con il **440M generico su GPU**, ripiego su CPU per la sessione (inizializzazione fallita, errore d'inferenza, vettore non finito); **query con il 270M su CPU su ogni dispositivo, anche sugli indicizzatori** (stessi vettori di query ovunque: E-Q8); niente NPU, niente librerie Qualcomm. `uses-native-library` facoltative per `libOpenCL.so` e `libvndksupport.so` (GPU); `abiFilters` `arm64-v8a` e `x86_64` (emulatori) | `docs/decisioni.md` 12/10; con il 270M ovunque il tablet non indicizzatore dà gli stessi risultati del telefono |
| D4 | **Spazio, precisione, ricetta.** Spazio `embeddinggemma2-float-v1`, **768 dimensioni**, calcolate in float32, normalizzate e **salvate e pubblicate in float16**. La **ricetta** (intero, oggi 1) descrive come si preparano gli ingressi (anteprima a 768 px JPEG q95, 70 token visivi, fotogrammi, OCR): una ricetta nuova resta nello **stesso spazio** (vettori confrontabili) e si ricalcola piano; uno spazio nuovo non si mescola mai (E-I4, D21) | Calcolare a 768 e ridurre dopo è possibile, il contrario no (ricerca 0.3 §5); float16 dimezza lo spazio con un errore trascurabile sul coseno; la ricetta separa "compatibile" da "migliore" |
| D5 | **Ingresso del modello sempre preparato da noi**: le anteprime (lette dal NAS o dal buffer) passano da `ImageBounds` e da `ImageDecoder`, si riducono a 768 px sul lato lungo e si ricodificano in JPEG q95 (la configurazione misurata dallo spike); **mai byte remoti al decodificatore nativo** (`stb_image`) di LiteRT-LM. Testo: `maxInputLength` 128, query troncata a 200 caratteri, nessun prefisso (in Fase 0 i prefissi non cambiavano i risultati; P9 li rimisura) | Il decodificatore nativo è C senza tetti nostri; i nostri decoder sono quelli già difesi dalla Fase 4 |
| D6 | **Formato: testa separata `ai`** (`devices/<id>/ai/head/<versione>` e `ai/seg/<impronta>`) con le sole classi **`vec` (codice 7)** e **`ocr` (codice 8)**, chiave = **chiave di contenuto** (come `analysis`); i file `ai` portano la **versione 2** del formato, teste `index` e `personal` restano alla 1. Corpi binari con tetti (§2.3) | Un'app vecchia non apre mai `ai/` (E-I3): una classe nuova in una testa `index` le farebbe rifiutare la testa intera, miniature comprese |
| D7 | **Attestazione e fusione dei dati derivati.** Una voce `vec` o `ocr` vale se, **nella stessa share**, c'è un record `meta` presente (di qualunque dispositivo approvato, il vincitore di F4-D6) con la **stessa chiave di contenuto intera**; il dispositivo che pubblica la voce non deve avere un record suo. Fra più voci per la stessa chiave: ricetta più alta, poi id del dispositivo minore; `vec` e `ocr` si scelgono separatamente. Due vettori della stessa ricetta che non concordano (coseno < 0,95) si contano in Diagnostica (`AI_VECTOR_DISAGREE`) | Il calcolo lo fa chi indicizza, non chi ha preparato; la regola della 4A (stesso dispositivo) lo renderebbe invisibile. Il confine resta la share e la chiave intera (R1 della 4A); il resto è L27 (L45) |
| D8 | **Pubblicazione dei dati AI**: outbox e pubblicatore propri per la testa `ai` (stessa meccanica di `OutboxIndexPublisher`), con la regola M3: una voce solo per chiavi di contenuto di file **di quella share** nel catalogo locale, con timbro e `qh1` freschi. Stessi cancelli dell'indice (`IndexWriteRules`). **Sotto un blocco permanente** (F4-D31, area in sola lettura) i risultati restano locali e si **pubblicano dopo**, quando la share si apre, senza ricalcolarli | Vettori e testo costano una notte di calcolo, non una rilettura: non si buttano come le miniature (R2 della 4A) |
| D9 | **Lavoro coordinato**: `WorkPass.AI` ("ai"), unità `work/1/ai/<mese>/<parte>` da 200 media per chiave di contenuto, prenotazione di 1 ora rinnovata ogni 20 minuti (F4-D13). Un contenuto è "fatto" **per classe**, quando una testa `ai` attestata di **qualunque** dispositivo approvato ha la voce della ricetta corrente (niente evidenza `thumbS`): `vec` lo cerca ogni indicizzatore, `ocr` solo un indicizzatore con la lettura del testo attiva (D41); per gli altri un `ocr` mancante non è lavoro da fare. All'inizio di ogni unità si rileggono le teste `ai` della share e si salta ciò che altri hanno già pubblicato | Due indicizzatori non fanno lo stesso lavoro (E-I1); un indicizzatore che muore libera l'unità entro l'ora (E-I2) |
| D10 | **Ingressi del calcolo e buffer di analisi.** In ordine: il **buffer** locale (`noBackupFilesDir/ai-buffer/`, ≤ 500 MB, F4 §2.10); le anteprime di analisi e i fotogrammi **scaricati dall'indice della share** (attestati, a lotti, prima dell'unità successiva); per i media del telefono di questo dispositivo la preparazione dai byte del telefono (`Preparer`, solo `analysis` e `frames`); altrimenti il media resta "in attesa della preparazione". **Sugli indicizzatori** la preparazione della Fase 4 e il backup della Fase 5 consegnano `analysis` e `frames` anche al buffer (oggi si buttano). Il buffer si svuota per ciò che è già calcolato e si ferma sotto 1 GiB libero | "Lavora sulle anteprime, mai sugli originali" (6.2); con F4-D31 acceso è l'unico modo di non rileggere gli originali; "una notte senza NAS elabora solo il buffer" (E-A6) |
| D11 | **Video**: un vettore **per fotogramma** del blob `frames` (al massimo 9, la copertina per prima); il punteggio del video è il massimo dei suoi fotogrammi; "Trova simili" usa la media normalizzata. OCR (se la lettura è attiva, D41) sulla copertina e sul fotogramma centrale, righe senza doppioni. I video lunghissimi sono già limitati dalla preparazione (E-A9) | Si trova "il momento" senza un modello video (il 440M non lo ha); costo prevedibile |
| D12 | **OCR con ML Kit Text Recognition v2, script latino, versione integrata** (`com.google.mlkit:text-recognition:16.0.1`, modello nell'APK, niente Play Services), **solo dove la lettura del testo è attiva** (D41, risposta Q4). Prima passata sull'anteprima di analisi (1024 px); **seconda passata sull'anteprima HQ (2048 px)** per il testo fitto (sorgente `SCREENSHOT`, oppure ≥ 6 righe con altezza mediana sotto 16 px): dalla replica se c'è, altrimenti scaricata nel buffer; per i media del telefono dagli originali. Le statistiche di ML Kit **non si bloccano**: con la funzione attiva partono come le manda la libreria (scelta dichiarata dell'utente); con la funzione spenta ML Kit non si inizializza (D41) | Ricerca 0.4: italiano coperto, nessun download a runtime; la regola dei 16 px per carattere rende stretta l'anteprima a 1024 per screenshot e documenti. Il blocco delle statistiche proposto prima (togliere DataTransport) è caduto con la risposta Q4 |
| D13 | **Testo letto: due forme.** Pubblicata: righe con riquadro, inclinazione ed estremi delle parole (servono al testo dal vivo, D43), UTF-8 in NFC, senza caratteri di controllo né di direzione, tetti (§2.3); "nessun testo" è un risultato (0 righe). Locale, per cercare: minuscolo, senza accenti, apostrofi ed elisioni separati ("dell'Esselunga" → "dell", "esselunga"), "€" → "euro", cifre come parole | Pubblicare il testo com'è (servirà a evidenziare e copiare); normalizzare solo in locale, dove la regola può cambiare senza ricalcolare |
| D14 | **Aree e privacy del calcolo.** Un indicizzatore lavora solo su: l'area privata dichiarata per il suo account (`LocalDeviceProfile.privateShare`), l'area comune, i media del proprio telefono (risultati solo locali per quelli che non sono sul NAS). Mai un'altra area privata (SMB la chiude comunque). **Aree senza indicizzatori** (E-I12): dagli stati firmati dei dispositivi (`roles` + `lastSeenAt` entro 7 giorni) e dall'account del registro: la propria area privata ne ha se un dispositivo dello stesso account è indicizzatore; l'area comune se ce n'è uno qualunque. Solo un'informazione, mai un'azione | "L'area privata di un familiare la indicizzano solo i suoi dispositivi" (6.2); lo stato del dispositivo è già informativo (formato §5.10) |
| D15 | **Ruolo Indicizzatore: lo sceglie ogni utente per i propri dispositivi** (risposta Q3). Si propone all'onboarding (passo 7, con il consiglio di oggi: telefono sì, tablet no) e si cambia in Impostazioni → Ricerca e modelli (oggi manca). Nel caso dell'utente l'S25 fa tutto (Indicizzatore, Preparatore e, se la attiva, lettura del testo). Restano gli avvisi per le aree senza indicizzatori (D14). Accenderlo propone di scaricare il 440M e lancia una **misura breve** (20 immagini sintetiche dell'APK) che dà foto al secondo e la stima "Analizzare 18.000 media richiede circa 2 notti" (E-A4 senza NPU: "dispositivo lento"); spegnerlo ferma il calcolo, rilascia le prenotazioni e chiede se togliere il 440M. Lo stato firmato del dispositivo si ripubblica subito (cambio di ruoli) | Il consiglio dell'onboarding ("tablet solo Preparatore") va potuto cambiare dopo le misure (F52) |
| D16 | **Modelli fissati nell'app** (`ModelCatalog`: id, ruolo, spazio, nome, **dimensione e SHA-256**, licenza, sorgenti). File in `noBackupFilesDir/models/<sha256>` (fuori dai backup, uno per profilo Android). **Verifica a ogni caricamento**: dimensione e SHA-256 calcolati sullo stesso descrittore che si passa al motore (se l'API accetta un descrittore, da verificare in P4; altrimenti subito prima, su un file dell'app in sola lettura). Un file diverso è rifiutato e tolto (E-A2) | 6.1: "hash fissato nell'app e verificato prima di ogni caricamento"; il descrittore chiude la finestra fra verifica e uso |
| D17 | **Sorgenti e scaricamento** (risposta Q1). In ordine: **(1) il NAS** (copie pubblicate da un dispositivo della famiglia, D18), **(2) HTTPS** dagli host ammessi scritti nell'app (oggi Hugging Face, commit fissato: `huggingface.co` e i suoi CDN `*.hf.co`, `*.huggingface.co`, che cambiano nel tempo), **(3) la cartella visibile `MyCloud Gallery/Modelli/` dell'area comune**, dove l'utente può copiare i file dal PC. Ripresa: richieste `Range` con `If-Range` (HTTPS) e letture a intervalli (NAS); file parziale `models/partial/<sha256>.part`; tetto alla dimensione fissata; controllo dello spazio prima (dimensione + 300 MB, E-A3). Un hash sbagliato butta il parziale e passa alla sorgente dopo (E-A1, E-A2). **Lavoro di WorkManager accelerato** (157 MiB da casa ≈ 30 s, dal NAS ≈ 10 s; ripreso se interrotto): nessun servizio nuovo né lavoro avviato dall'utente | 6.1: "su richiesta o secondo le regole, ripreso dopo le interruzioni"; l'hash rende indifferente chi serve il file |
| D18 | **Il NAS distribuisce i modelli in famiglia.** Il primo dispositivo che ha un modello verificato lo pubblica in `<area comune>/.mycloudgallery/devices/<id>/models/<sha256>` (scrittura esclusiva, nessuna sostituzione, solo nella propria cartella, solo se la share è pubblicabile: F4-D31); gli altri lo leggono da lì e lo verificano. Un dispositivo toglie solo le **proprie** copie che l'app non elenca più | Un solo scaricamento da Internet per famiglia; i profili del tablet e i telefoni dei familiari non vanno su Internet; un file falso costa solo un ripiego |
| D19 | **Nessun manifest remoto nella Fase 6** (risposta Q2): gli indirizzi stanno nell'app e cambiano con un aggiornamento (Pampa Store). Il manifest firmato arriva con la Fase 10 (versione minima, kill switch, §7 del piano) e potrà **solo** aggiungere indirizzi per i modelli già elencati nell'app, mai far accettare un file diverso (§2.1) | Una chiave in più da custodire e un host in più, oggi senza un bisogno: l'hash fissato dà già la garanzia che conta |
| D20 | **Spazio e rimozione**: la pagina mostra ogni modello (dimensione, stato, da dove è arrivato, quando è stato verificato) e lo spazio di vettori e testo; "Rimuovi" toglie il file e dice la conseguenza ("la ricerca per contenuto non funziona su questo dispositivo; date, luoghi e testo sì"). Un modello che l'app non elenca più si toglie da solo | 6.1: "spazio mostrato, modello rimovibile" |
| D21 | **Reindicizzazione graduale** (E-A10). Ricetta nuova: gli indicizzatori ricalcolano dal più recente; il vettore vecchio vale finché non arriva il nuovo; la copertura dice "Aggiornamento della ricerca: 40%". Spazio nuovo: i due spazi convivono, le query usano il vecchio finché il nuovo non copre almeno quanto lui, poi si cambia in un colpo; i vettori del vecchio si tolgono 30 giorni dopo | La ricerca resta sempre utilizzabile; mai vettori di spazi diversi nello stesso confronto |
| D22 | **Calcolo AI: regole, temperatura, batteria, ripresa.** `AiComputeWorker` con `AI_COMPUTE` e `StagedRun` (tappe da 8 minuti); `Pace.REDUCED` col caldo (lotti più piccoli e pause), stop a `SEVERE`; `LOCAL_BUFFER_ONLY` quando mancano solo rete o NAS. Ogni risultato si salva **subito**, per media, nell'indice locale (file dei vettori e database): dopo una morte del processo non si rifà. Embedding (GPU) e OCR (CPU, solo con la lettura attiva) in catena sulla stessa anteprima decodificata. `AndroidLimits` lo compila il lavoro (come promesso in `SystemState.kt:147`). "Indicizza ora" = richiesta `UserInitiated` (scavalca fascia e carica, non la temperatura) | 6.2: "rispetta regole, temperatura e batteria; riprende dal punto esatto" |
| D23 | **Indice locale per chiave di contenuto.** Vettori e testo si salvano per chiave di contenuto (tabelle `ai_vector`, `ai_text` dello schema v5), da qualunque origine (telefono, area privata, area comune); il collegamento ai media passa dalle versioni (`size`, `quick_hash`). Lo stesso contenuto in tre posti si calcola e si conserva una volta. La tabella `embedding` per media della v1 resta vuota e documentata come superata | La chiave di contenuto è già quella dell'indice; unifica telefono e NAS senza unioni d'identità |
| D24 | **Indice vettoriale a due passate, in memoria e mappato**: (1) prima passata sulle prime 256 componenti rinormalizzate in **int8** con scala per vettore (260 B a vettore: 26 MB per 100 mila), scansione parallela a 4 thread sui soli candidati; (2) **seconda passata** sui migliori 1.000 con i 768 componenti float16 letti da un file mappato (`noBackupFilesDir/search/`), coseno esatto. Niente HNSW, niente libreria nativa | Esatto quanto basta (P6 lo prova: ≥ 99% dei primi 20 uguali alla forza bruta float32), deterministico, aggiornabile per aggiunta; 100 mila vettori in ~20 ms |
| D25 | **Indice del testo**: FTS4 di Room sul testo già normalizzato (tokenizzatore `simple`: la normalizzazione è nostra e uguale su ogni dispositivo e in Robolectric); refusi con un **vocabolario locale** delle parole lette (distanza 1 per 4–7 lettere, 2 da 8 in su, metodo a cancellazioni); i termini della query diventano sempre letterali (nessuna sintassi `MATCH` dall'utente) | Room supporta FTS3/FTS4; E-Q5; nessuna iniezione di sintassi FTS (E-Q9) |
| D26 | **Analizzatore italiano delle query**, a regole, deterministico, senza rete né modelli: date relative ("ieri", "settimana scorsa", "2 anni fa"), mesi (senza anno = tutti gli anni), anni, decenni, intervalli, feste (Natale, Capodanno, Pasqua e Pasquetta calcolate, Ferragosto), stagioni (meteorologiche, con l'anno dell'inverno detto nel chip), luoghi dal **dizionario dei luoghi presenti in Libreria** (nome del luogo, regione `admin1`, paese, nomi di più parole, il più frequente fra gli omonimi), tipi e sorgenti (foto, video, screenshot, WhatsApp, panorami, foto in movimento, documenti, preferiti, modificate, sul telefono, solo sul NAS); refusi tollerati sul dizionario; il resto va alla ricerca per contenuto e al testo; le virgolette chiedono il testo esatto. "Oggi" = la data del telefono, la stessa delle intestazioni della Libreria | 6.3: "date e luoghi italiani diventano filtri, il resto va alla ricerca semantica e all'OCR"; dizionario piccolo e senza falsi luoghi |
| D27 | **Fusione e soglia di convinzione.** I filtri sono vincoli. Per il contenuto, la soglia è **relativa alla query**: punteggio standardizzato sulla distribuzione dei candidati (mediana e deviazione robusta); **sicuri** sopra `Zc` (e sopra un minimo assoluto), **meno probabili** fra `Zl` e `Zc`. Il testo: tutte le parole della query lette nel media = sicuro, almeno metà = meno probabile. Classifica con la fusione per ranghi (RRF, k = 60). `Zc` e `Zl` si calibrano in P9 sui due obiettivi: **precisione dei sicuri ≥ 90%** e **≥ 80% delle foto attese fra i primi 20** della classifica | Richiesta dell'utente del 12/10 ("solo ciò di cui è davvero convinta"); i punteggi assoluti erano piatti (0,65–0,70), quelli relativi no |
| D28 | **Risultati**: una cella per gruppo (copie simili, raffiche, RAW+JPEG: F5-D36), il punteggio del gruppo è quello del membro migliore. **Sicuri**, poi i **meno probabili** dopo un divisore, entrambi nell'ordine scelto dall'utente: "Recenti" (predefinito) o "Pertinenti" (D48, risposta Q10). Il viewer scorre i risultati nell'ordine mostrato con una vista ordinata nuova (`OrderedTimelineView`). **Nella schermata Cerca solo miniature locali** (replica e telefono): nessuna lettura del NAS durante la ricerca | Le viste di oggi assumono l'ordine della timeline (§0, punto 9); "nessun traffico durante le ricerche" (6.4) |
| D29 | **Nascosti mai**, in tre punti: i candidati sono l'istantanea della Libreria (senza nascosti né membri di gruppo), un filtro finale sui nascosti, e i test su ogni superficie: risultati, suggerimenti, luoghi proposti, conteggi della copertura, "Trova simili", ricerche recenti. Un luogo presente solo in media nascosti non si propone. Si **calcolano** anche i nascosti (risposta Q6). Nel viewer dei Nascosti testo dal vivo e ritaglio funzionano, e un ritaglio salvato nasce nascosto (D47) | E-Q7, E-H1; i conteggi e i luoghi farebbero trapelare l'esistenza di un nascosto |
| D30 | **"Trova simili"** dal viewer (⋮) e dalla selezione: il vettore del media come query (media normalizzata dei fotogrammi per un video), stesso indice, soglia calibrata a parte (le distribuzioni immagine–immagine sono diverse), escluse la cella e il gruppo del media; chip "Simili a" con la miniatura. Non c'è per un media senza vettore (spiegato) né nei Nascosti | 6.3 |
| D31 | **Copertura dichiarata** (`SearchCoverage`): celle della Libreria con vettore e testo della ricetta corrente, e quelle in attesa per motivo (preparazione, regole dell'indicizzatore, nessun indicizzatore per l'area, modello assente, media solo sul telefono di un dispositivo non indicizzatore, formato non supportato). In Cerca un avviso **solo sotto il 98%** ("La ricerca per contenuto copre 12.300 media su 15.000" · "Perché?"). Conteggi senza nascosti. Il testo letto ha una copertura sua: un'area in cui nessun dispositivo ha la lettura attiva è "lettura del testo non attiva", detto una volta nella pagina e mai come avviso insistente (D42) | E-Q6, E-I12, E-A10 |
| D32 | **Cronologia locale** (risposta Q5): tabella `search_history` del database (mai sincronizzata, mai nei dati personali né nell'esportazione della Diagnostica, tolta dall'azzeramento), al massimo **50 voci**, testo e chip, senza miniature; "Cancella" per voce e "Cancella tutte" | Piano §2 "Extra": salvata solo in locale e cancellabile |
| D33 | **Nessuna rete durante la ricerca.** `core-search`, `feature-search` e `core-ai` con `NoNetworkSourcesTest`; l'unico codice HTTPS sta in `core-models/.../download/https/` (test d'architettura: nessun altro nomina `java.net` né `javax.net`); `core-search` non vede `core-models`; il motore delle query (in `core-ai`) apre il modello solo con `ModelFiles` (lettura di un file verificato) e non usa la rete. Testo dal vivo e ritaglio non usano la rete (il testo viene dall'indice locale, il ritaglio gira sul dispositivo) | 6.4: "nessuna ricerca lascia il dispositivo"; C21 |
| D34 | **Filtro rapido "Documenti"** in Libreria e tipo "documenti" nelle query: bit derivato locale `CellFlags.DOCUMENT` (bit 16), acceso se il testo letto ha almeno 6 righe o 200 caratteri **oppure** il vettore è vicino a un piccolo insieme di frasi prototipo ("un documento", "uno scontrino", "una ricevuta", "un foglio con del testo") oltre una soglia calibrata in P9 (senza testo letto, solo le frasi prototipo). Si aggiorna a lotti di 500 (un cambiamento dell'indice della timeline per lotto) | Promessa della Fase 3 (D16); ogni dispositivo lo ricava dagli stessi dati, niente di pubblicato |
| D35 | **Ricerca sempre utilizzabile**: senza il modello delle query (non scaricato, rimosso, in verifica, in caricamento) funzionano filtri e testo, con "Scarica il modello per cercare per contenuto (157 MB)"; offline funziona tutto (nessuna rete serve); il motore si **carica quando si apre Cerca** e si rilascia 3 minuti dopo l'uscita o quando Android chiede memoria | 6.3 e §4 del piano: anche offline; il 270M su CPU occupa ~330 MB |
| D36 | **UI come stati e contratti** (come F4-D27, F5-D30): `feature-search` e le voci nuove di Impostazioni e viewer consegnano ViewModel e stati sigillati (§1.5), composable fatte **solo** con i componenti G2 esistenti; nessuno stile nuovo | Il filone grafico rifinisce senza toccare la logica |
| D37 | **Schema v5** (migrazione 4→5 mai distruttiva; `ExportedSchemaTest` 1→5 … 4→5): `ai_vector`, `ai_text` con la tabella FTS4 `ai_text_fts`, `ai_vocabulary`, `ai_pending_publish` (risultati in attesa di una share pubblicabile), `ai_replica_entry` (voci delle teste `ai` per la fusione), `search_history`; bit `DOCUMENT`; nessuna tabella nuova punta a `media` (tutte per chiave di contenuto: `TABLES_WITHOUT_MEDIA_IDS`) | Una sola migrazione sul campo per la fase |
| D38 | **Permessi, componenti, dipendenze, host.** Nessun permesso Android nuovo (`INTERNET` c'è già: la sua riga del §4-bis cambia). Nessun componente esportato né servizio nuovo; i componenti non esportati che ML Kit e DataTransport portano nel manifest fuso si elencano e si controllano; **`MlKitInitProvider` si toglie** (D41). Il modello del ritaglio sta nell'APK (risposta Q11 (a)). **Tre dipendenze nuove**, con checksum in `verification-metadata.xml` e righe in `docs/sicurezza.md` §8: `litertlm-android` 0.18.1 (Apache 2.0), `text-recognition` 16.0.1 (termini ML Kit) e `com.google.ai.edge.litert:litert` 2.3.0 (Apache 2.0, runtime del ritaglio, D44). **Un host nuovo**: Hugging Face (solo `core-models`, solo per scaricare i modelli; il modello del ritaglio sta nell'APK, risposta Q11 (a)). `verifyReleaseManifest` controlla anche `uses-native-library` | Piano §7.9 e checklist "per ogni fase": ogni libreria, host o voce del manifest è una decisione visibile |
| D39 | **Ora.** "Oggi" e "ieri" nelle query vengono dalla data del telefono (un'interpretazione da mostrare nel chip, mai una scadenza); prenotazioni e teste con l'ora del NAS; durate, attese del motore e validità dei caricamenti con l'orologio monotono (`NoWallClockSourcesTest` in `core-ai`, `core-models` e `core-search`: la data di oggi arriva dalla porta `Today`, implementata in `:app`) | E-P9: l'orologio sbagliato si vede nei chip e non fa scadere niente |
| D40 | **Log e Diagnostica**: mai testo delle query, testo letto o copiato, nomi di luoghi, vettori, chiavi di contenuto, nomi di file; solo numeri e codici (§2.6, catalogo della Diagnostica) | C21, come le fasi precedenti |

**Decisioni nate dalle risposte del 21/10** (§11)
| # | Decisione | Perché |
|---|---|---|
| D41 | **Lettura del testo nelle foto: una funzione da attivare, spenta di default** (risposte Q4 e Q13 (a): per l'account, con l'esclusione di un dispositivo). Si attiva per l'**account** (impostazione personale sincronizzata fra i propri dispositivi, 4.7) dopo una pagina che dice **cosa ML Kit manda a Google**: produttore e modello del telefono, versione di Android, nome e versione dell'app, identificativi per installazione, tempi, configurazione e codici d'errore; contatti per correzioni e compatibilità; **mai foto né testo** (termini e pagina dei dati di ML Kit, §10). Ogni altro dispositivo dell'account lo dice una volta e si può escludere ("Non su questo dispositivo"). **Spenta = ML Kit non si inizializza e non manda niente**: `com.google.mlkit.common.internal.MlKitInitProvider` tolto dal manifest fuso con `tools:node="remove"` (la via documentata da Google per l'avvio manuale); `MlKit.initialize(context)` lo chiama **solo** il motore OCR di `core-ai` quando la funzione è attiva su quel dispositivo; nessun'altra classe nomina `com.google.mlkit` (test d'architettura); DataTransport, che ML Kit avvia al primo uso, resta inerte. **Allo spegnimento**: motore rilasciato, lavori pianificati di DataTransport annullati; al primo avvio successivo, prima di qualunque altra cosa, si tolgono gli eventi che DataTransport ha in coda (nomi di componenti e file da leggere in P0 sul manifest fuso e nella libreria). **Accesa**: DataTransport resta com'è e le statistiche partono come le manda ML Kit | "Nessuna telemetria" resta la regola; l'eccezione è dichiarata, spenta di default e scelta da chi sa cosa esce (risposta Q4, proposta 9 aggiornata: D49) |
| D42 | **Il testo letto è un dato della famiglia.** Un dispositivo con la lettura spenta usa, per la ricerca e per il testo dal vivo, il testo letto e pubblicato nell'indice da un altro dispositivo (dello stesso account per l'area privata, di qualunque familiare per l'area comune): lì ML Kit non gira e non manda niente. L'OCR in background lo fanno solo gli indicizzatori con la lettura attiva; quello "al volo" (testo dal vivo su un media non ancora letto) solo i dispositivi con la lettura attiva, e il risultato segue il percorso normale (salvato, pubblicato se il file è in una share pubblicabile). Un'area il cui account non ha la lettura attiva non ha testo letto: la copertura lo dice (D31) | Coerente con Q4: le statistiche nascono dove ML Kit gira, non dai dati; il testo resta nella share del file, con i suoi permessi (C19, L46) |
| D43 | **Testo dal vivo nel viewer** (risposta Q9). Pressione prolungata su una riga di testo letto → si seleziona la parola sotto il dito, con due maniglie che si agganciano alle parole e scorrono sulle righe riconosciute (anche su più righe) → barra "Copia · Seleziona tutto · Condividi testo". Appunti segnati come sensibili (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+). Un pulsante **"Testo"** compare nella barra del viewer quando la foto ha testo letto: evidenzia tutte le righe e apre il foglio "Testo nella foto" (D46). **Le posizioni vengono dall'indice**: il corpo `ocr` pubblica per ogni riga il riquadro normalizzato, l'inclinazione e gli estremi delle parole (§2.3), così il testo dal vivo funziona sui dispositivi con la lettura spenta e sui media solo-NAS, senza ML Kit; al volo solo dove la lettura è attiva (D42). **Niente "Copia testo" nelle info** (D46) | Come Samsung e Apple, richiesta dell'utente; pubblicare le posizioni costa ~4 byte per parola e risparmia una lettura per ogni dispositivo |
| D44 | **Ritaglio del soggetto** (risposta Q9). Pressione prolungata su un soggetto (fuori dal testo) → il soggetto si stacca con un'animazione → "Copia · Condividi · Salva come nuova foto" (PNG trasparente; interruttore "Contorno" per l'effetto adesivo). **Modello: MagicTouch di Google** (Interactive Segmenter: maschera da un punto per oggetti, animali e persone; **Apache 2.0**, 6 MB), eseguito con il runtime **LiteRT** (`com.google.ai.edge.litert:litert` 2.3.0) su CPU, **non** con MediaPipe Tasks (Google ha annunciato statistiche d'uso "per tutte le piattaforme" senza modo di spegnerle, §10). Fra la versione 1 (512×512, float) e la 2 (768×768, int8) sceglie P13 con le misure. Maschera riportata alla risoluzione dell'immagine con un filtro guidato (bordi), soglia e sfumatura di 1–2 px; nessun soggetto chiaro (maschera sotto l'1% o sopra il 90% dell'immagine, o confidenza bassa) = niente, con una vibrazione leggera. Sorgente dell'immagine: la migliore già sul dispositivo (originale del telefono, originale già aperto nel viewer, altrimenti l'HQ), al più 4096 px sul lato lungo. Piano B: MobileSAM (Apache 2.0) con ONNX Runtime (§2.10) | Licenza permissiva, piccolo, pensato proprio per "tocca il soggetto"; niente Play Services né servizi esterni (richiesta dell'utente) |
| D45 | **Gesti del viewer** (risposte Q9 e Q14). La pressione prolungata decide **dove** cade il dito: su una riga di testo letto → testo dal vivo; altrove su una foto → ritaglio; **le foto in movimento si comportano come le altre foto** (Q14): la pressione prolungata **non** fa più partire il video (oggi lo fa: `holdToPlay` in `ViewerGestures.kt`, `ViewerController.onMotionHold`, `ViewerPlayback.playMotion`), che parte dal badge "Movimento" (D51); sui video nessuno dei due. Un secondo dito o un movimento oltre la soglia annullano (come `holdToPlay`, `ViewerGestures.kt`); funziona a ogni livello di zoom (il punto passa per la trasformazione di telephoto). Con una selezione o un ritaglio aperti: il tocco fuori chiude, pager e trascinamento verso il basso sono fermi, il pizzico resta; doppio tocco e tocco singolo invariati quando non c'è niente di aperto | Un solo gesto, deciso dal contenuto sotto il dito; le funzioni esistenti non cambiano |
| D46 | **Accessibilità**: sull'immagine due azioni di TalkBack, "Testo nella foto" e "Ritaglia il soggetto" (punto: il soggetto più grande fra cinque punti di prova: centro e quattro punti a un terzo); "Testo nella foto" apre un **foglio** con il testo letto come testo normale (TalkBack lo legge, la selezione di sistema funziona) e "Copia tutto"; lo stesso foglio si apre dal pulsante "Testo". Maniglie con bersagli da 48 dp; annunci "Testo copiato", "Soggetto ritagliato". **Il "Copia testo" nelle info non c'è**: l'alternativa accessibile è il foglio, raggiungibile da un'azione dell'immagine | Le maniglie su un'immagine non si usano con TalkBack, un foglio di testo sì; le info restano leggere (risposta Q9: "metterlo nelle info è scomodo") |
| D47 | **Salvataggio del ritaglio** (risposta Q12 (a)). "Salva come nuova foto" scrive un PNG nel telefono in `Pictures/MyCloud Gallery/Ritagli/` (riga propria con `IS_PENDING`, come F4-D20), con la **data di adesso** e **nessuna posizione** né altro metadato, collegato al catalogo prima che la lettura del telefono lo veda. La cartella "Ritagli" è una sorgente del backup accesa di default (sul NAS `Backup/<dispositivo>/Ritagli/AAAA/MM/`, Q8 della Fase 5). Dai Nascosti la foto nuova nasce **nascosta**. Copia e Condividi passano da `cacheDir/share/` del `FileProvider` esistente (nessun percorso nuovo). Nella build debug con un account di prova si salva in `Pictures/mcg-prova/Ritagli/` (recinzione F5-D13) | Una foto nuova, come uno screenshot; nessuna posizione in un'immagine fatta per essere condivisa |
| D48 | **Ordine dei risultati** (risposta Q10): due ordini, **"Recenti" (predefinito) e "Pertinenti"**, con un selettore sopra i risultati, **ricordati** come impostazione personale sincronizzata (vale su tutti i propri dispositivi); valgono per sicuri e meno probabili. In ciascuno domina il criterio scelto e l'altro pesa poco: in "Pertinenti" il punteggio è la pertinenza più un piccolo premio di recenza a decadimento (a pari pertinenza vince il più recente, uno molto vecchio scende un poco); in "Recenti" i risultati vanno per periodo (giorno, mese o anno secondo l'età) e dentro il periodo per pertinenza. Formula e taratura con il set di Q8 al §2.4 | Risposta Q10: un risultato poco pertinente non sta mai sopra uno molto pertinente dello stesso periodo; vince la scelta dell'utente |
| D49 | **Telemetria e regola delle dipendenze.** La regola "niente SDK che parli con internet" di `versions-app.gradle` resta, con eccezioni scritte accanto: `litertlm-android` e `litert` (nessuna rete attesa, verificata con le catture F55 e F67), ML Kit `text-recognition` (rete solo con la lettura attiva: eccezione dichiarata e scelta dall'utente). La **proposta 9** del piano diventa: "nessuna telemetria e nessun crash reporting esterno, **salvo la lettura del testo nelle foto se l'utente la attiva**: ML Kit manda a Google statistiche tecniche, mai foto né testo" | Risposta Q4 |
| D50 | **Animazione d'apertura delle foto in movimento** (risposta Q14). Quando una foto in movimento diventa la pagina ferma del viewer (aperta dalla griglia o raggiunta scorrendo, dopo ~150 ms di pager fermo) si riproduce **in silenzio** il tratto del suo video che **precede l'istante della foto**, **1,2 s** (fra 1 e 1,5 s), poi l'immagine si ferma sulla foto con una dissolvenza di ~200 ms. Il video si disegna **nello stesso riquadro** della foto (con proporzioni diverse oltre il 3%: niente animazione). **Istante della foto** (§2.11): `Camera:MotionPhotoPresentationTimestampUs` per il formato Google (già letto da `XmpFacts`, −1 = assente); `MicroVideoPresentationTimestampUs` per il vecchio MicroVideo; per il trailer SEF di Samsung nessun campo documentato: vale l'XMP se il file lo porta, altrimenti la regola della specifica Android, **metà del video**, sostituita solo se le misure di P14 sui file dell'S25 mostrano dove sta davvero la foto. Il tratto finisce all'istante (sorgente tagliata) e comincia dal fotogramma chiave precedente (al più 2 s prima, altrimenti ricerca esatta). **Mai**: con "Rimuovi animazioni" (`LocalFluidMotionPolicy.reducedMotion`), con il risparmio energetico, con l'interruttore "Animazione delle foto in movimento" spento (risposta Q16), durante uno scorrimento o un pizzico, su una pagina che non è quella ferma; ogni tocco la ferma subito e mostra la foto; lo zoom resta sulla foto. **Un decoder solo** (`ViewerPlayback`): l'animazione usa il lettore condiviso; il precarico ±1 prepara solo i **byte** (intervallo, istante e, per i solo-NAS, la coda), mai un secondo decoder. **Interruttore** (risposta Q16 (b)): "Animazione delle foto in movimento" nelle Impostazioni della Libreria, **acceso di default**, impostazione personale **sincronizzata** fra i dispositivi dell'utente (campo `motion_animation` di `core-personal`, come `search_order`; voce assente = acceso); si somma a "Rimuovi animazioni" e al risparmio energetico. "Mai" vale anche con l'interruttore spento; il badge "Movimento" (D51) funziona sempre | Risposta Q14: l'effetto delle Live Photo di Apple e delle gallerie Samsung, un istante senza attese e senza rubare i gesti; risposta Q16: chi non la vuole la spegne una volta per tutti i suoi dispositivi |
| D51 | **Badge "Movimento" e video delle foto in movimento** (risposta Q14). Sulle foto in movimento un badge "Movimento" (icona e scritta; l'aspetto lo decide G3) nella barra in alto del viewer, visibile con le barre: un tocco riproduce **il video intero una volta** (con l'audio, al volume dei contenuti multimediali: risposta Q15 (a); l'animazione d'apertura resta sempre muta) e torna alla foto con la stessa dissolvenza; un altro tocco lo ferma. Anche dal menu ⋮ ("Riproduci il movimento") e come azione di TalkBack. **Media solo-NAS** (oggi la parte video non si riproduce: `onMotionHold` lavora solo sui media del telefono): la parte video, in coda al file e di pochi MB, si legge a intervalli dal NAS. Per l'animazione automatica **solo** se è ≤ 16 MB, sul Wi-Fi di casa o su un Wi-Fi via VPN (mai dati mobili né roaming) e se è pronta entro 1,5 s dalla pagina ferma; altrimenti niente animazione e nessuna attesa. Con il badge sui dati mobili si chiede conferma con la dimensione (come E-V2). Cache `cacheDir/motion/` (64 MB, la più vecchia esce per prima, tolta dall'azzeramento); traffico come `VISIBLE_CONTENT` | Un pulsante come nelle altre gallerie; il NAS non costa dati mobili senza un sì |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)
- **E-A11**: il file del modello si altera dopo la verifica (memoria rovinata, ripristino da un backup di sistema): la verifica al
  caricamento successivo lo rifiuta, lo toglie e lo riprende dalle sorgenti.
- **E-A12**: la GPU non parte o dà risultati non finiti: ripiego su CPU per la sessione, codice `AI_GPU_FALLBACK` in Diagnostica;
  un vettore non finito non si salva né si pubblica.
- **E-A13**: il ruolo Indicizzatore si spegne a metà di un'unità: il calcolo si ferma al media successivo, i risultati già fatti si
  pubblicano, la prenotazione si rilascia.
- **E-A14**: due indicizzatori pubblicano vettori diversi per lo stesso contenuto (ricette diverse, dispositivo difettoso): vince
  la regola deterministica (D7), il disaccordo si conta.
- **E-A15**: un media preparato solo in locale (F4-D31) su un dispositivo non indicizzatore: nessun vettore finché la share non
  diventa pubblicabile o un indicizzatore non lo prepara da sé; la copertura lo dice.
- **E-A16**: Android chiude il processo per memoria durante il calcolo (limitatore di Android 17, T-23): ripresa dal media dopo,
  motivo (`ApplicationExitInfo`) in Diagnostica.
- **E-A17**: memoria del telefono sotto 1 GiB libero durante il calcolo: buffer svuotato, calcolo fermo con il motivo.
- **E-Q9**: query lunghissima o con sintassi strana (emoji, `*`, `NEAR`, virgolette sbilanciate): troncata a 200 caratteri, nessuna
  sintassi interpretata.
- **E-Q10**: parola che è anche un luogo (un cognome, un negozio): il chip del luogo propone "Cerca come parola".
- **E-Q11**: ricerca mentre il modello delle query manca o si sta caricando: filtri e testo subito, contenuto appena pronto.
- **E-Q12**: nessun risultato sicuro ma qualche meno probabile: lo si dice e si propone "Mostra anche risultati meno probabili".
- **E-Q13**: un risultato aperto nel viewer viene nascosto o eliminato altrove: esce dai risultati (vista viva).
- **E-Q14**: "Trova simili" su un media senza vettore: spiegato; su un nascosto: non offerto.
- **E-I20**: testa o segmento `ai` malformati (vettore non finito o non normalizzato, spazio o ricetta sconosciuti, testo enorme o
  con caratteri di controllo): segmento rifiutato, gli altri valgono.
- **E-I21**: voci `ai` per un contenuto che nella share non esiste (nessun record presente): ignorate (D7).
- **E-I22**: un'app senza la Fase 6 in famiglia: non legge `ai/`, i suoi dati restano validi; la sua Libreria non ha ricerca per
  contenuto.
- **E-S16**: un host dei modelli che redirige fuori dall'elenco ammesso o serve un file diverso: scaricamento fermato, sorgente
  dopo, nessun file accettato.
- **E-A18** (21/10): la lettura del testo si spegne dopo essere stata accesa: ML Kit si ferma, i lavori di DataTransport si
  annullano, gli eventi in coda si tolgono al primo avvio dopo; dal riavvio ML Kit non si inizializza più (D41).
- **E-A19** (21/10): la lettura del testo è attiva per l'account ma un dispositivo si è escluso: lì ML Kit non gira; il dispositivo
  usa il testo letto dagli altri (D42).
- **E-Q15** (21/10): ordine "Pertinenti" con un risultato molto pertinente di anni fa e uno quasi uguale di ieri: quello di ieri
  sopra; con "Recenti" un risultato poco pertinente non sta sopra uno molto pertinente dello stesso periodo (D48).
- **E-V9** (21/10): pressione prolungata sul testo di una foto senza testo letto (lettura spenta ovunque, media non ancora letto):
  niente testo dal vivo; dove la lettura è attiva si legge al volo (D42, D43).
- **E-V10** (21/10): pressione prolungata dove non c'è un soggetto chiaro (cielo, muro, foto sfocata): nessun ritaglio, vibrazione
  leggera, nessun messaggio d'errore.
- **E-V11** (21/10): ritaglio di una foto nascosta salvato come nuova foto: la foto nuova nasce nascosta (D47, E-H1).
- **E-V12** (21/10): pressione prolungata su una foto in movimento: testo dal vivo o ritaglio come su ogni foto; il video **non**
  parte; parte solo dal badge "Movimento" (D45, D51, risposta Q14).
- **E-V13** (21/10): selezione del testo aperta e gesti del viewer (scorrimento al media dopo, trascinamento verso il basso, pizzico,
  doppio tocco): il tocco fuori chiude la selezione; pager e chiusura fermi finché è aperta; il pizzico funziona e le maniglie
  seguono lo zoom.
- **E-V14** (21/10): animazione d'apertura di una foto in movimento e gesti: nessuna animazione sulle pagine attraversate da uno
  scorrimento veloce; un tocco, un pizzico o un trascinamento la fermano subito e mostrano la foto; si ripete arrivando da un'altra
  pagina, non chiudendo le info o tornando dallo zoom (D50).
- **E-V15** (21/10): foto in movimento solo-NAS: sul Wi-Fi di casa la coda video arriva e l'animazione parte se è pronta in tempo;
  sui dati mobili niente animazione e nessun traffico, il badge chiede conferma; con il NAS irraggiungibile foto ferma e badge "non
  disponibile ora" (D51).
- **E-V16** (21/10): istante della foto assente, −1 o fuori dalla durata del video: metà del video (regola della specifica Android);
  formato non riconosciuto (E-M16): niente animazione né badge, la nota nelle info come oggi (D50).
- **E-V17** (21/10): video della foto in movimento ruotato o con proporzioni diverse dalla foto: rotazione dalla traccia; oltre il 3%
  di differenza niente animazione, il badge lo riproduce nel suo riquadro (D50).

### 0.3 Modifiche da fare in P0 (agli altri documenti)
**`docs/formato-nas.md`** (versione 2 del formato **solo** per i file `ai`)
- **A1, §2.2 albero**: `devices/<id>/ai/head/<versione>` e `ai/seg/<impronta>` (in ogni share indicizzata);
  `devices/<id>/models/<sha256>` nell'area comune (copie dei modelli, D18; file grezzi senza estensione, eccezione dichiarata come
  il cestino); fuori da `.mycloudgallery/` la cartella visibile `MyCloud Gallery/Modelli/` dell'area comune (non fa parte del
  formato: l'app la legge, non la scrive).
- **A2, §5.5 teste**: `kind` = `ai`, con le sole classi `vec` e `ocr`; una testa `index` o `personal` non le elenca mai.
- **A3, §5.5-bis**: classi 7 `vec` e 8 `ocr`, chiave di contenuto, corpi binari e tetti del §2.3 di questo documento.
- **A4, regole di chi legge**: attestazione e fusione dei dati derivati (D7); stessa regola M3 alla pubblicazione (D8).
- **A5, §5.6**: unità `work/1/ai/<mese>/<parte>` (D9).
- **A6, §7 tetti**: corpo `vec` ≤ 14 KiB e al massimo 9 vettori; corpo `ocr` ≤ 64 KiB, ≤ 400 righe, riga ≤ 1.024 byte e ≤ 64
  parole (con inclinazione ed estremi delle parole, D43); vettori finiti con norma fra 0,99 e 1,01.
- **A7, §10**: i file `ai` hanno versione 2; chi legge accetta 1..2; un'app vecchia non apre `ai/` (E-I3, E-I22).
- **A8, §9**: niente cambia: i dati `ai` mostrano e coordinano, non decidono nessuna azione sui file; le copie dei modelli si
  tolgono solo dal proprio dispositivo e solo nella propria cartella.

**`docs/sicurezza.md`**: C9 (corpi `vec` e `ocr` ostili, ingresso del modello preparato da noi: D5), C19 (il testo letto sta
nell'indice della share, D42), C21 (**eccezione dichiarata alla "nessuna telemetria"**: la lettura del testo, spenta di default,
manda con ML Kit statistiche tecniche a Google solo se l'utente la attiva; spenta, ML Kit non si inizializza; nessun traffico
durante le ricerche, durante il testo dal vivo e durante il ritaglio; primo traffico Internet solo per i modelli: D41, D49), C22
(catena di fornitura realizzata: D16–D19); §1 "Che cosa fa l'app" (la frase "un dubbio su una libreria di Google è ancora da
chiarire" diventa la scelta dell'utente); §4-bis: riga `INTERNET` aggiornata, `uses-native-library`, `MlKitInitProvider` tolto,
componenti non esportati di ML Kit e DataTransport, appunti sensibili; §6 L45–L56 (§6.2, L49 riscritto); §7 checklist della Fase 6
estesa (§6.3); §8: la riga del 12/10 "Dubbio su ML Kit … Aperto" si chiude con la risposta Q4, più le righe per D2, D3, D12,
D16–D19, D33, D38, D41, D44, D49, D51.

**`docs/modello-dati.md`**: nuovo §13 "Schema v5" (lo scrive P1); nota al §9 (OCR e FTS fatti; `embedding` superata).

**`docs/decisioni.md`**: sezione "Fase 6" con D1–D51 e le risposte Q1–Q16 del §9.

**`docs/regole-attivita.md`**: §6 costanti nuove o ricalibrate (`aiItemsPerSecond` dalle anteprime, `ocrSecondsPerItem`,
`framesPerVideo`, scaricamento dei modelli); §2 nota: il calcolo AI legge anteprime dal NAS (traffico registrato) e lavora sul
buffer senza rete.

**`docs/fase4-progetto.md`**: nota a D16 e al §2.10 (buffer degli indicizzatori realizzato nella Fase 6, D10) e a R2 della 4A (sugli
indicizzatori `analysis` e `frames` vanno al buffer).

**`docs/android.md`**: esito di T-23 (F62); GPU e OpenCL su S25 e Tab S9; esito della cattura del traffico di ML Kit (F56).

**`docs/research/embeddinggemma2.md`**: nota sulla 0.18.1 e sugli host di scaricamento di Hugging Face.

**`versions-app.gradle`** (P0, codice): la regola "niente SDK che parli con internet" resta, con tre eccezioni scritte accanto
(D49): `litertlm-android` e `litert` (nessuna rete attesa, verificata con le catture), ML Kit solo `text-recognition` integrata,
che parla con Google **solo con la lettura del testo attiva** (eccezione scelta dall'utente); l'unico codice HTTPS dell'app è
nostro, in `core-models` (D33).

**Piano**: edge case del §0.2; E-A4 riletto senza NPU ("dispositivo lento: stima e consiglio"); punto aperto "Runtime, risoluzione
e dimensione dei vettori di EmbeddingGemma 2; parte testuale separabile" chiuso con D2–D5; **proposta 9** con l'eccezione
dichiarata della lettura del testo (D49); §2 "Extra" e 6.4: testo dal vivo e ritaglio del soggetto nel viewer (D43, D44); 6.3: i due
ordini dei risultati (D48); glossario, "Ruoli del dispositivo": la lettura del testo è una funzione dell'account, non un ruolo.

**`README.md`**: moduli nuovi e grafo. **`docs/grafica-progetto.md`**, "Dubbi grafici": schermata Cerca, chip, selettore
dell'ordine, avviso di copertura, pagina Ricerca e modelli, pagina di attivazione della lettura del testo, evidenziazione e maniglie
del testo dal vivo, animazione del soggetto che si stacca, badge "Movimento" e animazione d'apertura delle foto in movimento,
interruttore "Animazione delle foto in movimento" (per il filone grafico).

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-trust      + data/AiSegments.kt: classi vec e ocr, testa ai, codec ostile, versione 2            (P2)
core-index      + ai/: pubblicatore e outbox della testa ai, replica e fusione, attestazione dei derivati,
                  AiPublishedClasses, scaricamento di analysis, frames e hq nel buffer; WorkPass.AI    (P2)
core-models     → common, model, trust (SharePath, NasLayout, NasFileSystem), nas (lettura; pubblicazione
                  esclusiva nella propria cartella), rules (traffico, cancelli)                         (P3)
                  l'UNICO con codice HTTPS (download/https/); NON vede: index, ai, search, database
core-ai         → common, model, trust, index (contratti), prepare (Preparer, solo per i media del
                  telefono), models (solo ModelFiles), rules; litertlm, litert e ML Kit SOLO qui (P4, P5, P13)
                  + cutout/: ritaglio del soggetto (MagicTouch su LiteRT); ocr/: l'unico che inizializza ML Kit
                  NON vede: nas (i byte del NAS arrivano dalle porte di core-index), nasops, backup, search
core-search     → common, model, database (porte), library (istantanea, viste), places (nomi),
                  ai (solo contratti: TextEmbedder)                                                    (P6, P7)
                  NON vede: nas, index, models, prepare, nasops, backup (nessuna rete, nessun NAS)
feature-search  → search, library, imaging, designsystem; ai e models solo nei contratti di stato     (P8)
                  (cieca a core-nas, core-index, core-nasops, core-backup)
feature-settings + voce "Ricerca e modelli" (apre la pagina di feature-search, come la voce Backup)    (P8)
                + interruttore "Animazione delle foto in movimento" (Impostazioni della Libreria)    (P14)
feature-viewer  + voce ⋮ "Trova simili" come dato (azione verso :app, nessuna dipendenza da search)    (P8)
                + livetext/ e cutout/: testo dal vivo e ritaglio, con porte definite dal viewer
                  (ViewerTextSource, ViewerTextReader, SubjectCutter, CutoutSaver) legate da :app   (P12, P13)
                + motion/: badge "Movimento", animazione d'apertura; video/: istante della foto, tratto
                  tagliato, coda dei solo-NAS (nessuna dipendenza nuova: Media3 e core-metadata ci sono) (P14)
core-personal   + impostazioni sincronizzate: lettura del testo (account), ordine dei risultati        (P8)
                + animazione delle foto in movimento: contract/MotionPersonalSettings (P0, firma) e
                  settings/SyncedMotionSettings.kt (P14, file nuovo: non tocca SyncedPersonalSettings.kt)
core-mediastore + salvataggio dei ritagli con PhoneMediaWriter (cartella Ritagli, IS_PENDING)           (P13)
app → tutti
```
- `litertlm`, `com.google.ai.edge.litert` e `com.google.mlkit` compaiono **solo** nel `build.gradle` di `core-ai` (test
  d'architettura, P0); `MlKit.initialize` e ogni classe `com.google.mlkit` solo in `core-ai/.../ocr/`, dietro il cancello della
  lettura del testo (D41).
- `feature-viewer` non vede `core-ai` né `core-search`: testo dal vivo e ritaglio passano da porte sue, che `:app` lega ai dati
  dell'indice locale, al motore OCR (solo se attivo) e al ritagliatore.
- **Solo `core-models/.../download/https/`** nomina `javax.net.ssl`, `java.net.URL` o `HttpURLConnection`: `NoNetworkSourcesTest`
  vale in tutti gli altri pacchetti di `core-models` e in ogni altro modulo; un test d'architettura prova che nessun altro modulo
  vede quel pacchetto (le classi sono `internal`, il resto dell'app riceve solo `ModelManager` e `ModelFiles`).
- Il motore della ricerca (`core-search`) non vede `core-models` né `core-index`: per costruzione non può scaricare né leggere dal
  NAS (D33). `feature-search` vede di `core-models` e `core-ai` solo i contratti di stato e di comando che servono alla pagina
  Ricerca e modelli (`ModelManager`, `AiStatus`), mai il codice HTTPS (`internal`); la schermata Cerca usa solo `core-search`.
- Chi legge il NAS o mostra la Libreria (`core-index`, `core-nasmedia`, `core-prepare`, `core-library`, `core-imaging`) **non vede**
  `core-ai`, `core-models` né `core-search` (nessun ciclo; `core-prepare` riceve una porta `AnalysisSink`, definita da lui).
- Restano le regole delle Fasi 4 e 5: `core-ai` e `core-models` non vedono `NasFileOps`, `DestructiveNasAccess`, `NasBackupOps`,
  `core-nasops` né `core-backup`; i `feature-*` non si conoscono fra loro.

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-trust` (P2): dati AI nell'indice**
| Elemento | Firma | Note |
|---|---|---|
| Classi | `SegmentClass.VEC("vec", 7)`, `SegmentClass.OCR("ocr", 8)`; `AI_CLASSES = {VEC, OCR}`; `INDEX_CLASSES` invariato | `RESERVED_WIRE` resta {`faces`} |
| Testa | `kind = "ai"`: una testa `ai` elenca solo `AI_CLASSES`, una `index` o `personal` mai; envelope versione 2 | `NasLayout.aiHead(device, version)`, `aiSegment(device, hash)` |
| Corpi | `class VecBody(space: SpaceCode, dimension: Int, precision: VectorPrecision, recipe: Int, source: VecSource, vectors: List<VecItem>)`, `class VecItem(frameMs: Int?, f16: ShortArray)`; `class OcrBody(engine: OcrEngineCode, recipe: Int, source: OcrSource, lines: List<OcrLine>)`, `class OcrLine(box: ShortBox, text: String)` | §2.3; ogni violazione rifiuta il segmento (E-I20) |
| Codec | `object AiSegments { fun encode(cls, entries): EncodedSegment; fun decode(bytes, ref, expected): Verdict<AiSegmentContent> }` | stesso contenitore `MCGB`, versione 2 |
| Modelli sul NAS | `NasLayout.modelCopy(device, sha256)` | file grezzo, nessuna busta, nessuna estensione |

**`core-index` (P2): pubblicazione, replica e lavoro dei dati AI** (pacchetto `ai/`)
| Elemento | Firma | Note |
|---|---|---|
| Pubblicazione | `interface AiIndexPublisher { suspend fun stage(share: String, entries: List<AiEntry>); suspend fun flush(share: String, reason: FlushReason): PublishOutcome; val pending: StateFlow<PendingCounts> }`; `sealed interface AiEntry { val contentKey: ContentKey; class Vector(contentKey, body: VecBody); class Text(contentKey, body: OcrBody) }` | M3 con `ShareCatalog` (D8); sotto blocco permanente le voci vanno a `AiLocalSink` e a `ai_pending_publish` |
| Ripubblicazione | `suspend fun releasePending(share: String): Int` | quando la share diventa pubblicabile, solo voci con timbro ancora fresco (D8) |
| Replica | `interface AiReplicaSink { suspend fun apply(share: String, winners: List<AiWinner>); suspend fun forgetDevice(device: String); suspend fun forgetShare(share: String) }`; `object AiFusion` | attestazione e scelta di D7; teste `ai` lette nello stesso giro di `IndexSyncEngine`, dopo le `index` |
| Lavoro | `WorkPass.AI("ai")`; `class AiPublishedClasses : PublishedClasses`; `class AiWorkSource : WorkSource` | "fatto" = `vec` e `ocr` della ricetta corrente da qualunque dispositivo approvato (D9); `LeasedWorkUnits` riusato |
| Ingressi | `interface AnalysisFetcher { suspend fun fetch(share: String, keys: List<ContentKey>, classes: Set<SegmentClass>, into: AiBuffer, budget: Duration): FetchReport }` | solo `analysis`, `frames`, `hq`; blob attestati (R1 della 4A); letture a blocchi con `readAll`; traffico registrato |
| Contatori | `IndexCounters.aiRejected`, `aiDisagree`, `aiPublished` | Diagnostica |

**`core-models` (P3): modelli**
| Elemento | Firma | Note |
|---|---|---|
| Catalogo | `enum class ModelRole { QUERY_TEXT, INDEX_MULTIMODAL, CUTOUT }`; `data class ModelSpec(val id: ModelId, val role: ModelRole, val space: EmbeddingSpace, val fileName: String, val sizeBytes: Long, val sha256: Sha256, val license: LicenseNote, val https: List<PinnedUrl>)`; `object ModelCatalog { val QUERY_270M: ModelSpec; val INDEX_440M: ModelSpec; val CUTOUT_MAGICTOUCH: ModelSpec; fun needed(roles: Set<DeviceRole>): List<ModelSpec> }` | valori al §2.1 (il ritaglio come asset dell'APK, Q11); cambiano solo con un aggiornamento dell'app |
| Gestore | `interface ModelManager { val states: StateFlow<Map<ModelId, ModelState>>; suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult; suspend fun remove(id: ModelId): Boolean; suspend fun usage(): ModelUsage }` | `ModelState { Absent, Queued(reason), Fetching(source, done, total), Verifying, Ready(sizeBytes, source, verifiedAtMonotonic), Failed(code) }`; `ModelTrigger { USER_NOW, RULES, ROLE_CHANGED }` |
| Uso | `interface ModelFiles { suspend fun open(id: ModelId): VerifiedModel? }`; `class VerifiedModel internal constructor(val spec: ModelSpec, val descriptor: ModelDescriptor) : Closeable` | verifica a **ogni** apertura; il costruttore `internal` lo usa solo il verificatore (test d'architettura) |
| Sorgenti | `sealed interface ModelSource { data class NasCopy(share, device); data class Https(url: PinnedUrl); data class PcFolder(share) }`; `fun interface ModelFetcher { suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult }` | `FetchResult { Done, Partial(bytes), Mismatch, NotFound, Refused(reason), NoSpace, NetworkError }` |
| HTTPS | `internal class HttpsModelFetcher(hosts: HostRules, gate: ModelNetworkGate)` | `HttpsURLConnection` della piattaforma; redirezioni seguite a mano e solo verso host ammessi; mai cookie, credenziali, parametri, cache |
| Scaffale sul NAS | `interface ModelShelf { suspend fun publish(spec: ModelSpec, share: String): ShelfResult; suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy>; suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int }` | solo nella cartella del proprio dispositivo; scrittura esclusiva (D18) |

**`core-ai` (P4 motori, P5 calcolo)**
| Elemento | Firma | Note |
|---|---|---|
| Testo | `interface TextEmbedder { suspend fun warmUp(): WarmUpResult; suspend fun embed(text: String): TextVector; suspend fun release() }`; `class TextVector(val space: EmbeddingSpace, val values: FloatArray)` | 270M su CPU (D3); un solo motore, una richiesta alla volta |
| Immagini | `interface ImageEmbedder { val backend: StateFlow<AiBackend>; suspend fun embed(images: List<ModelImage>): List<FloatArray>; suspend fun release() }`; `enum class AiBackend { GPU, CPU }` | 440M; ripiego su CPU (E-A12) |
| OCR | `interface TextReader { suspend fun read(image: OcrImage): OcrResult }`; `class OcrResult(val lines: List<OcrLine>, val dense: Boolean)` | ML Kit integrato, **solo dietro il cancello della lettura del testo** (D41); `dense` chiede la seconda passata (D12); righe con inclinazione ed estremi delle parole (D43) |
| Ingressi | `object ModelInputs { fun fromAnalysis(jpeg: ByteArray, recipe: AiRecipe): ModelImage?; fun fromFrames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage>; fun forOcr(jpegOrWebp: ByteArray): OcrImage? }` | `ImageBounds` prima di decodificare, `ImageDecoder`; `ModelImage` si costruisce solo qui (D5) |
| Ricetta | `data class AiRecipe(val version: Int = 1, val visionTokens: Int = 70, val longSide: Int = 768, val jpegQuality: Int = 95, val ocrFramesPerVideo: Int = 2)` | D4 |
| Calcolo | `interface AiComputeRun { suspend fun run(scope: AiScope, budget: Duration, run: RunScope): AiRunResult }`; `sealed interface AiScope { data class Share(val share: String); data object PhoneOnly }` | §2.2 |
| Buffer | `interface AiBuffer { suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean; suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray?; suspend fun evict(done: Collection<ContentKey>); val usedBytes: StateFlow<Long> }` | ≤ 500 MB; si ferma sotto 1 GiB libero (D10) |
| Porte | `AiResults` (salva vettori e testo per chiave), `AiCandidates` (chiavi da fare per share e per il telefono), `AiRoles`, `AnalysisSink` (implementata qui, usata da `core-prepare`) | legate da `:app` |
| Stato | `sealed interface AiStatus { Off; ModelMissing; Idle(at); Running(done, total, eta, backend); Waiting(reason); BufferOnlyDone(items); Problem(code) }` | numeri e codici |
| Misura | `interface DeviceProbe { suspend fun measure(): ProbeResult }` | 20 immagini sintetiche nell'APK (D15) |
| Lettura del testo | `interface TextReadingSwitch { val state: StateFlow<TextReadingState>; suspend fun enableForAccount(consent: TextReadingConsent); suspend fun excludeThisDevice(); suspend fun disable() }`; `TextReadingState { Off, OnForAccount(thisDevice: Boolean), Disabling }`; `class TextReadingConsent internal constructor(textVersion: Int, shownAtMonotonic)` | D41; il consenso lo costruisce solo la pagina di attivazione (test d'architettura); `MlKitBootstrap` chiama `MlKit.initialize` solo con lo stato `OnForAccount(thisDevice = true)` |
| Ritaglio | `interface SubjectCutter { suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult }`; `CutoutResult { Subject(mask: AlphaMask, bounds), NoClearSubject, Failed(code) }`; `object CutoutRender { fun png(image, mask, outline: Boolean): ByteArray }` | D44; MagicTouch su LiteRT, CPU; `CutoutImage` costruibile solo da `ModelInputs` (al più 4096 px) |

**`core-search` (P6 motore, P7 analizzatore)**
| Elemento | Firma | Note |
|---|---|---|
| Motore | `interface SearchEngine { suspend fun search(request: SearchRequest): SearchResults; suspend fun similar(media: MediaId): SearchResults; val coverage: StateFlow<SearchCoverage>; val readiness: StateFlow<SearchReadiness> }` | `SearchRequest(text, editedChips, showLikely)`; mai un'eccezione per un indice incompleto |
| Analizzatore | `interface QueryParser { fun parse(text: String, today: LocalDate, places: PlaceDictionary): ParsedQuery }`; `data class ParsedQuery(val chips: List<SearchChip>, val semantic: String?, val words: List<String>, val exact: List<String>)` | puro (JVM), §2.5 |
| Chip | `sealed interface SearchChip { DateRange(from, to, everyYear, label); Place(ids, label, alternatives); MediaType(type); Source(source); Presence(where); Similar(media); Word(text) }` | modificabili e togliibili |
| Indice vettoriale | `interface VectorIndex { fun coarse(query: FloatArray, candidates: CandidateSet, out: ScoreBlock); fun rerank(query: FloatArray, rows: IntArray): FloatArray }` | D24 |
| Indice del testo | `interface TextIndex { fun match(words: List<String>, exact: List<String>, candidates: CandidateSet): TextMatches; fun complete(prefix: String, limit: Int): List<String> }` | D25 |
| Risultati | `data class SearchResults(val chips: List<SearchChip>, val confident: OrderedTimelineView, val likely: OrderedTimelineView, val coverage: SearchCoverage, val notices: List<SearchNotice>)` | D28 |
| Suggerimenti | `interface Suggestions { suspend fun idle(): IdleSuggestions; suspend fun complete(prefix: String): List<Suggestion> }` | luoghi, tipi, recenti; mai da nascosti (D29) |
| Cronologia | `interface SearchHistory { val entries: StateFlow<List<HistoryEntry>>; suspend fun record(text: String, chips: List<SearchChip>); suspend fun delete(id: Long); suspend fun clear() }` | D32 |
| Porte | `SearchData` (vettori, testo, chiavi delle celle dal catalogo), `Today` (data del telefono), `PlaceLookup` (sopra `ReverseGeocoder.place(id)`: nome, `admin1`, paese) | legate da `:app` |
| Ordine | `enum class ResultOrder { RECENT, RELEVANT }`; `object ResultRanking { fun key(order, relevance: Float, ageDays: Int, params: RankParams): Comparable }`; `data class RankParams(halfLifeDays: Int = 90, recencyWeight: Float = 0.10f)` | D48, §2.4; la scelta sta nelle impostazioni personali sincronizzate |
| Testo di un media | `interface MediaText { suspend fun layout(media: MediaId): TextLayout? }`; `class TextLayout(lines: List<TextLine(box, angle, words: List<WordSpan>, text)>)` | dall'indice locale (`ai_text`), coordinate normalizzate sull'immagine raddrizzata; `:app` lo lega a `ViewerTextSource` (D43) |

**`core-library` (P0 firme, P8)**: `interface OrderedTimelineView : TimelineView` (posizioni in un ordine qualunque, `viewIndexOf` con
una mappa, mai la ricerca binaria) e `LibraryFilter.DOCUMENTS`. P0 prova che griglia e viewer funzionano con una vista non in
ordine di tempo (test sul `ViewerItems` di prova).

### 1.3 Aggiunte a moduli esistenti
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | `CellFlags.DOCUMENT` (bit 16, oggi libero); `SpaceCode`, `VecSource`, `OcrSource` come codici stabili; commento di `WorkStage.ANALYSIS_COPY` corretto (1024 px) | P0 (firme), P1 |
| `core-database` | schema v5 (D37); `MediaCatalog`: chiavi di contenuto delle celle e dei membri di gruppo (`cellContentKeys(afterSeq)`), lettura e scrittura di `ai_vector`, `ai_text`, `ai_vocabulary`, `ai_pending_publish`, `search_history`; `setDerivedFlags(ids, DOCUMENT)` a lotti in una transazione | P1 |
| `core-prepare` | porta `AnalysisSink` (consegna `analysis` e `frames` prodotti qui quando il dispositivo è indicizzatore); nessuna logica nuova | P0 (firma), P5 |
| `core-rules` | `DeviceMeasures`: `aiItemsPerSecond` dalle anteprime, `ocrSecondsPerItem`, `framesPerVideo`, `modelBytes`; `BacklogFacts.toAnalyze` alimentato dal catalogo; `analysisPreviewBytes` corretto | P5, P11 |
| `core-family` | `RolesSettings.setRoles(indexer, preparer)`: scrive le scelte locali (oggi nell'istantanea dell'onboarding) e ripubblica subito lo stato firmato; il passo 7 dell'onboarding resta la proposta iniziale (Q3) | P0 (firma), P8 |
| `core-personal` | impostazioni sincronizzate `text_reading` (account: attiva o spenta, versione del testo accettato), `search_order` (`RECENT`, `RELEVANT`) e `motion_animation` (`PersonalValue.Flag`: animazione d'apertura delle foto in movimento acceso o spento, voce assente = acceso; interfaccia `MotionPersonalSettings` in `core-personal/.../contract/`, implementazione `settings/SyncedMotionSettings.kt`, file nuovo, così non si scontra con P8 che implementa `text_reading` e `search_order` in `SyncedPersonalSettings.kt`); l'esclusione di un dispositivo dalla lettura del testo è locale | P0 (firme), P8, P14 (`SyncedMotionSettings`) |
| `core-mediastore` | `PhoneMediaWriter` (F4-D20) con la sottocartella `Ritagli/` e il collegamento al catalogo prima della lettura del telefono (anche come nascosto, D47) | P13 |
| `core-backup` | sorgente "Ritagli" accesa di default (`BackupSources`), come gli screenshot | P13 |
| `core-common` | `DiagSection.SEARCH`; test d'architettura del §1.1; eccezione di `NoNetworkSources` per `core-models/.../download/https/` | P0 |
| `feature-settings` | voce "Ricerca e modelli" (apre la pagina di `feature-search`); interruttore "Animazione delle foto in movimento" nelle Impostazioni della Libreria (legge e scrive `MotionPersonalSettings`) | P8, P14 |
| `feature-viewer` | voce ⋮ "Trova simili" come dato (`viewerMenuEntries`), azione verso `:app` | P8 |
| `feature-viewer` | `livetext/`: evidenziazione, selezione con le maniglie, barra delle azioni, pulsante "Testo", foglio "Testo nella foto", azioni di TalkBack; `cutout/`: animazione del soggetto, barra "Copia · Condividi · Salva come nuova foto", voce ⋮ "Ritaglia il soggetto"; arbitro della pressione prolungata al posto di `holdToPlay` (D45) | P12, P13 |
| `feature-viewer` | `motion/`: badge "Movimento", dissolvenza, condizioni dell'animazione (D50, D51); `video/MotionPhotos.kt`: `MotionVideo.Found(range, stillUs)` (oggi l'istante c'è in `core-metadata`, `MotionPart.stillPresentationUs`, ma non arriva al viewer); `video/ViewerPlayback.kt` e `Media3ViewerPlayer.kt`: tratto tagliato e muto, video intero dal badge, sorgente NAS a intervalli, sempre un solo lettore; `ViewerController.kt`: niente più `onMotionHold` | P14 |
| `feature-library` | chip "Documenti" nel filtro rapido | P8 |
| `feature-diagnostics` | codici del §2.6, sezione Ricerca, modelli e indice in `StorageContributor`, `DiagJob` per scaricamento e calcolo | P11 |
| `benchmark` | `SearchBenchmark` e scenario `ricerca` in `soglie.json` | P9 |
| `tools/check.ps1`, `gradle/verify-release-manifest.gradle` | `MlKitInitProvider` assente dal manifest fuso; elenco chiuso dei componenti di ML Kit e DataTransport (non esportati; un componente nuovo di una versione nuova della libreria fa fallire la verifica: potrebbe avviarsi da solo); `uses-native-library` solo le due ammesse e facoltative; riferimenti a `HttpsURLConnection` solo nel pacchetto ammesso del dex; riferimenti a `MlKit.initialize` solo nella classe del cancello | P0 |

### 1.4 Cosa resta in `:app` (P11)
- **`AiGraph`** in `app/.../ai/`: nasce con la famiglia `Ready` e il `NasGraph`; `ModelManager`, motori, `AiComputeRun`, pubblicatore e
  replica `ai`, buffer; porte legate al catalogo, a `core-index`, a `core-prepare` e a `LocalDeviceProfile`.
- **`SearchGraph`** in `app/.../search/`: `SearchEngine`, indice locale, cronologia; vive **anche senza NAS** (offline) e anche
  senza `AiGraph` (filtri e testo).
- **Lavori** (`work/AiWork.kt`, tutti con `runIfActive` e `StagedRun`):
  - `ModelFetchWorker`: accelerato per "Scarica ora" e per il cambio di ruolo; periodico secondo le regole (Wi-Fi, mai i dati
    mobili in background);
  - `AiComputeWorker`: `AI_COMPUTE`, tappe da 8 minuti; parte dopo `IndexSyncWorker`, `PreparationWorker` e un giro di backup con
    novità (lavoro unico, `KEEP`), e periodico; "Indicizza ora" come richiesta `UserInitiated`;
  - la sincronizzazione delle teste `ai` nello stesso `IndexSyncWorker`, dopo le teste `index`;
  - la compattazione dei file dell'indice di ricerca dentro `LibraryMaintenanceWorker`.
- **Cancelli**: `IndexWriteRules` per pubblicare `ai` e per copiare i modelli sul NAS; `ModelNetworkGate` per HTTPS (rete validata, non
  in roaming; in background solo Wi-Fi; con l'app aperta e "Scarica ora" anche i dati mobili, con la conferma e la stima, come
  "Fai ora").
- **Navigazione**: la scheda Cerca al posto del segnaposto, "Trova simili" dal viewer, la pagina Ricerca e modelli da Impostazioni,
  il filtro Documenti.
- **Lettura del testo** (D41): `TextReadingSwitch` legato alle impostazioni personali sincronizzate; all'avvio del processo, **prima di
  ogni altra cosa**, se la lettura è spenta su questo dispositivo si annullano i lavori pianificati di DataTransport e se ne
  tolgono gli eventi in coda; `MlKit.initialize` solo dal motore OCR, solo con la lettura attiva qui; avviso una tantum sugli altri
  dispositivi dell'account quando la si attiva.
- **Viewer**: porte del testo dal vivo (`ViewerTextSource` sopra `MediaText`, `ViewerTextReader` sopra il motore OCR solo se attivo)
  e del ritaglio (`SubjectCutter`, `CutoutSaver` sopra `PhoneMediaWriter`); sorgente "Ritagli" del backup.
- **Memoria**: `onTrimMemory` rilascia il motore delle query e il ritagliatore; `ApplicationExitInfo` (limitatore di Android 17) in
  Diagnostica.
- **Azzeramento**: `AiGraph.stop()` e `SearchGraph.stop()` per primi, poi `AppLocalDataEraser` toglie modelli, buffer, file
  dell'indice di ricerca, tabelle `ai_*` e cronologia. Le copie dei modelli già sul NAS portano l'id del dispositivo azzerato:
  restano (gli altri le possono ancora usare, l'hash le rende sicure) e si tolgono dal PC (come L31).

### 1.5 Confine con il filone grafico: stati e contratti
La Fase 6 decide **cosa** si mostra e **quando**; il filone grafico decide **come** (D36). Composable fatte solo con i componenti G2
esistenti.

| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `SearchScreenState` | `Idle(suggestions, recents)`, `Typing(text, completions)`, `Searching(chips)`, `Results(chips, order, confident, likelyCount, showLikely, coverage, notices)`, `NoResults(chips, likelyCount, alternatives)`, `Unavailable(reason)` | scheda Cerca | G2 |
| `ResultOrder` | "Recenti" (predefinito), "Pertinenti" | selettore sopra i risultati | G2 |
| `TextReadingState` | spenta; pagina di attivazione (cosa manda ML Kit a Google, mai foto né testo); attiva per l'account, su questo dispositivo sì/no; spegnimento | Impostazioni → Ricerca e modelli; avviso una tantum sugli altri dispositivi | G2 |
| `LiveTextState` | `Idle`, `Highlighted(lines)`, `Selecting(from, to)`, `Actions(selection)`, `Sheet(text)`, `ReadingNow` (al volo) | viewer | G3 |
| `CutoutState` | `Idle`, `Lifting(progress)`, `Lifted(preview, outline)`, `Saving`, `Saved`, `NoSubject`, `Failed(code)` | viewer | G3 |
| `SearchChip` | data, luogo (con alternative), tipo, sorgente, presenza, simili a, parola | barra dei chip | G2 |
| `SearchCoverage` | `Complete`, `Partial(covered, total, reasons)`, `ModelMissing(sizeBytes)`, `Updating(percent)` | avviso in Cerca (sotto il 98%), pagina Ricerca e modelli | G2 |
| `SearchNotice` | `PreparingContentSearch`, `OnlyFiltersAndText`, `NoIndexerForArea(area)`, `TypoCorrected(from, to)` | sopra i risultati | G2 |
| `ModelsState` | per modello: `Absent(size)`, `Queued(reason)`, `Fetching(done, total, source)`, `Verifying`, `Ready(size, source)`, `Failed(code)`; spazio di vettori e testo | Impostazioni → Ricerca e modelli | G2 |
| `IndexerState` | ruolo, `AiStatus`, stima (notti, batteria), misura del dispositivo, aree senza indicizzatori | Impostazioni → Ricerca e modelli | G2 |
| `HistoryState` | voci con testo e chip, "Cancella", "Cancella tutte" | Cerca a riposo | G2 |
| `LibraryFilter.DOCUMENTS` | chip "Documenti" | filtro rapido della Libreria | G2 |
| Azione "Trova simili" | presente, assente (nascosto), spiegata (senza vettore) | viewer ⋮ | G3 |
| Azioni "Testo nella foto" e "Ritaglia il soggetto" | presenti, assenti (nessun testo letto; video), spiegate | viewer ⋮ e azioni di TalkBack | G3 |
| `MotionState` | `Still`, `Opening(progress)` (animazione d'apertura), `Playing` (dal badge), `Fetching(bytes, total)` (solo-NAS), `Unavailable(reason)`; badge "Movimento" con il suo stato. Con l'interruttore spento (`MotionPersonalSettings`) lo stato dell'apertura resta `Still`; il badge c'è sempre | viewer, barra in alto | G3 |
| `MotionPersonalSettings` | animazione d'apertura accesa (predefinito) o spenta; impostazione personale sincronizzata fra i dispositivi dell'utente | Impostazioni della Libreria (interruttore "Animazione delle foto in movimento") | G2 |

**Chi tocca cosa**
- La Fase 6 **non** modifica `core-designsystem`, la chrome, il vetro, la pillola, la cella; il filone grafico non modifica contratti e
  ViewModel qui sopra.
- File condivisi a rischio: `app/.../main/MainScaffold.kt` (solo P11 sostituisce il segnaposto), il menu del viewer
  (`viewerMenuEntries`: P8 la voce "Trova simili", P13 "Ritaglia il soggetto", P12 "Testo nella foto", in quest'ordine o con
  l'orchestratore che serializza; P14 "Riproduci il movimento"), `feature-viewer/.../page/ViewerPage.kt`, `ViewerController.kt` e
  `gesture/ViewerGestures.kt` (prima P14: toglie la riproduzione con la pressione lunga, aggiunge badge e animazione; poi l'arbitro
  della pressione prolungata: P12 lo scrive, P13 si aggancia), `LibraryFilter` (P0 la firma, P8 il chip).
- Stringhe in file propri: `strings_search_*.xml`, `strings_models_*.xml`.

## 2. Flussi

### 2.1 Modelli (6.1, P3)
**Il catalogo fissato nell'app** (valori verificati il 21/10/2026 con l'API di Hugging Face: commit e date invariati dal 6/10;
dimensioni e SHA-256 dalla ricerca 0.3 e da `spike/embed-probe/.../ModelStore.kt`)
| Modello | Ruolo | File e repository | Commit | Dimensione | SHA-256 | Chi lo tiene |
|---|---|---|---|---|---|---|
| Ricerca 270M | `QUERY_TEXT` | `embeddinggemma-2-text-270m.litertlm`, `litert-community/embeddinggemma-2-text-270m-litert-lm` | `9be6e8b90982095dc05c2bd162e4b954ee4dbac7` | 164.626.432 B (157 MiB) | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` | ogni dispositivo e ogni profilo del tablet che cerca |
| Indicizzatore 440M | `INDEX_MULTIMODAL` | `embeddinggemma-2-text-vision-440m.litertlm`, `litert-community/embeddinggemma-2-text-vision-440m-litert-lm` | `e301f74d5551b0c2641bd5cb4652a76239d5c5f8` | 387.710.976 B (370 MiB) | `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0` | solo gli indicizzatori |
| Ritaglio MagicTouch (D44) | `CUTOUT` | `magic_touch.tflite`, **versione 1** (float32, 512×512), dalla raccolta dei modelli di MediaPipe: `storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite` (P0, 21/10: la versione 2, int8 768×768, è un pacchetto `.task` da 30,5 MB, non il file da ~6 MB approvato: per sceglierla P13 deve chiedere un nuovo scaricamento) | — (cartella `1`, uguale a `latest` del 03/05/2023: si fissa l'impronta) | 6.227.884 B | `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25` (MD5 uguale a quello dichiarato dal server) | **dentro l'APK** come asset di `core-ai` (`assets/cutout/`, con la licenza Apache 2.0 e gli avvisi: risposta Q11 (a)), come `places.mcgp1`; estratto in `models/` e verificato a ogni caricamento come gli altri |

Indirizzo HTTPS: `https://huggingface.co/<repository>/resolve/<commit>/<file>`. Licenza: Apache 2.0 secondo la scheda, con la
"Gemma Prohibited Use Policy"; la pagina MediaPipe parla di "Gemma license" (contrasto annotato in Fase 0): il testo si mostra in
Impostazioni → Informazioni e licenze. Apache 2.0 permette di tenere copie sul NAS con gli avvisi.

**Il flusso**
| Momento | Cosa | Dettagli |
|---|---|---|
| Quando serve | Cerca aperta senza il 270M; ruolo Indicizzatore acceso senza il 440M; un'app nuova che elenca un modello nuovo | Cerca propone "Scarica (157 MB)"; il cambio di ruolo chiede il 440M con la stima; in background `ModelFetchWorker` scarica ciò che manca secondo le regole (§2.7) |
| Sorgenti, in ordine (D17) | (1) copia sul NAS di un dispositivo della famiglia (D18); (2) HTTPS dagli host ammessi; (3) cartella `MyCloud Gallery/Modelli/` dell'area comune | (1) e (3) solo con la sessione NAS valida e la rete di casa o la VPN (cancello di rete del NAS); (3) solo un file con nome e dimensione del catalogo |
| Scaricamento | file parziale `noBackupFilesDir/models/partial/<sha256>.part`, a blocchi da 1 MiB | **ripresa**: HTTPS con `Range: bytes=<n>-` e `If-Range` (ETag); un `200` al posto di `206` riparte da zero; NAS con letture a intervalli. Tetto: mai più byte della dimensione fissata. Spazio: dimensione mancante + 300 MB, altrimenti `NoSpace` (E-A3) |
| Verifica | dimensione esatta, SHA-256 calcolato durante la scrittura e **ricalcolato** sul file finito | uguale → rinomina atomica in `models/<sha256>`; diverso → parziale tolto, sorgente dopo, conteggio (E-A2, E-S16) |
| Copia sul NAS | il primo dispositivo con il modello verificato lo pubblica nella propria cartella (D18) | solo con la share pubblicabile (F4-D31), scrittura esclusiva, nessuna sostituzione |
| Caricamento | `ModelFiles.open` → dimensione e SHA-256 **ogni volta** → motore | cache dei programmi compilati in `noBackupFilesDir/models/cache/<sha256>-<backend>/`, tolta quando il modello cambia. Un errore di verifica toglie il file e lo rimette in coda (E-A11) |
| Spazio e rimozione (D20) | pagina Ricerca e modelli | dimensione, sorgente, ultima verifica; "Rimuovi" con la conseguenza detta; un modello che l'app non elenca più si toglie da sé |
| Profili del tablet | ogni profilo ha i suoi file (archivio dell'app per utente Android) | dal NAS circa 10 s per il 270M (16 MB/s) |

**HTTPS (D17, D33)**
- Solo `HttpsURLConnection` della piattaforma (nessuna libreria nuova), solo `https`, fiducia di sistema (nessun pinning del
  certificato: gli host del CDN cambiano; l'integrità la dà l'hash, il TLS dà la riservatezza).
- **Host ammessi** scritti nell'app: `huggingface.co` e i suffissi `.huggingface.co` e `.hf.co`. I CDN di Hugging Face cambiano
  nome nel tempo (`cdn-lfs.hf.co`, `cdn-lfs-us-1.hf.co`, `cas-bridge.xethub.hf.co`, …: §10), per questo si ammettono suffissi e non
  nomi esatti. Redirezioni seguite **a mano**, al massimo 5, solo verso host ammessi e solo `https`.
- Nessun cookie, nessuna credenziale, nessun parametro, nessuna cache HTTP; `User-Agent: MyCloudGallery`; scadenze di 15 s per la
  connessione e di 30 s per blocco (come il sorvegliante di F4-D3).
- Traffico registrato nel `TrafficLedger` (`AI_COMPUTE`, rete usata) subito, prima del punto di ripresa.
- Il percorso non passa dal cancello del NAS (non è SMB): lo guarda `ModelNetworkGate` (§2.7).

**Il manifest firmato che verrà (D19, Fase 10).** Se Q2 è (b), o quando arriva la Fase 10: un JSON canonico firmato (ECDSA P-256 come
il formato, chiave di pubblicazione fissata nell'app, diversa dalla chiave di famiglia), ospitato su HTTPS da un host ammesso, con
versione anti-rollback e scadenza. Per i modelli può solo **aggiungere indirizzi** per `ModelId` e SHA-256 già elencati nell'app;
un id o un hash sconosciuti si ignorano; un manifest con firma non valida si ignora per intero (E-S7). Non può mai far accettare un
file diverso.

| Edge case | Comportamento |
|---|---|
| E-A1 | Scaricamento interrotto (Wi-Fi che cade, app chiusa, telefono spento): riprende dal parziale alla volta dopo, anche da un'altra sorgente se la prima non risponde (stessi byte per hash) |
| E-A2 | Hash sbagliato: file scartato, sorgente dopo; dopo due errori della stessa sorgente in un giorno la sorgente si salta fino al giorno dopo |
| E-A3 | Spazio insufficiente: avviso con lo spazio necessario, niente scaricato |
| E-A11 | File alterato dopo la verifica: rifiutato al caricamento, tolto, riscaricato |
| E-S7 | Manifest manomesso: nella Fase 6 non c'è (D19); dalla Fase 10 firma non valida = ignorato |
| E-S16 | Redirezione fuori dagli host ammessi o file diverso: fermato, sorgente dopo |
| — | Copia sul NAS con il nome giusto e byte diversi (dispositivo compromesso, file rovinato): scartata come E-A2, contata (`AI_MODEL_MISMATCH`) |
| — | Dati mobili: in background mai; "Scarica ora" con l'app aperta chiede conferma con la dimensione (come "Fai ora", proposta 10) |
| — | Roaming: mai, nemmeno con "Scarica ora" (proposta 2) |

### 2.2 Calcolo AI (6.2, P5)
**La tappa di un'unità** (per share; poi i media del solo telefono, `AiScope.PhoneOnly`)
| Passo | Cosa | Ripresa e guasti |
|---|---|---|
| Scelta | prima l'area privata propria, poi l'area comune (D14); dentro, i mesi più recenti | ruolo spento o modello assente = niente |
| Unità | `LeasedWorkUnits.next(share, WorkPass.AI, priorità)`: 200 media per parte, già fatti tolti con `AiPublishedClasses` (D9) | `Busy`/`Lost` = unità dopo; `Contended` o scritture bloccate = unità senza prenotazione (danno solo d'efficienza) |
| Ingressi | buffer → `AnalysisFetcher` per ciò che manca (a lotti di 50, il lotto dopo scaricato mentre si calcola) → media del telefono: `Preparer` dai byte del telefono (solo `analysis` e `frames`) → altrimenti "in attesa della preparazione" (D10) | un blob non attestato non si usa; la preparazione fallita (F4-D14) = "non supportato" |
| Calcolo | anteprima decodificata **una volta**: `ImageEmbedder` (GPU) per la foto o per ogni fotogramma, `TextReader` (CPU, solo con la lettura attiva, D41) in parallelo; seconda passata HQ se `dense` o screenshot (D12) | GPU che fallisce → CPU per la sessione (E-A12) |
| Controlli | 768 valori finiti, norma 1 ± 0,01; testo dentro i tetti del §2.3 | vettore non finito: ripetuto su CPU; al secondo errore `AI_VECTOR_INVALID` e media saltato |
| Salvataggio | **subito**, per chiave di contenuto: vettori nel file dell'indice locale e righe in `ai_vector`, testo in `ai_text`; poi `AiIndexPublisher.stage` | dopo una morte del processo i media già salvati non si rifanno (D22) |
| Pubblicazione | `flush(UNIT_FINISHED)` a fine unità, `PERIODIC` al più ogni 5 minuti d'ora del NAS | blocco permanente → `ai_pending_publish` (D8); temporaneo → outbox in attesa |
| Fine unità | `finish(Completed)`; buffer svuotato di ciò che è fatto; `keepAlive` ogni 20 minuti | prenotazione persa = si finisce il media in corso e si cambia unità |
| Tappa | `StagedRun`: traffico delle anteprime registrato, punto di ripresa (share, unità, ultimo media) | `Stop` salva ed esce |

**Ingressi per tipo di media**
| Tipo | Vettore | Testo |
|---|---|---|
| Foto, RAW, animata, foto in movimento | `analysis` (un vettore) | `analysis`; seconda passata su `hq` se il testo è fitto |
| Screenshot (sorgente `SCREENSHOT`) | `analysis` | direttamente `hq` (o l'originale, se è sul telefono) |
| Video | `frames`: un vettore per fotogramma, fino a 9 (D11) | copertina e fotogramma centrale, righe senza doppioni |
| Qualunque, con la lettura del testo spenta su questo dispositivo | come sopra | **niente**: ML Kit non si inizializza (D41); il testo arriva, se c'è, da un altro dispositivo (D42) |
| Media del solo telefono | dai byte del telefono (preparazione locale) | come sopra; risultati solo locali |
| Formato non decodificabile | — | — (contato come "non supportato" nella copertura) |

**"Una volta per tutta la famiglia", scenario per scenario** (il "Fatto quando" di 6.2)
| Scenario | Chi calcola | Chi riceve |
|---|---|---|
| Foto nuova caricata dal backup nell'area privata | un indicizzatore dello stesso account; se è il telefono che ha caricato, l'anteprima è già nel suo buffer (D10) | i dispositivi dello stesso account, con le teste `ai` |
| Media dell'area comune | il primo indicizzatore, di qualunque account, che prende l'unità | tutti i familiari |
| Due indicizzatori attivi insieme | le prenotazioni dividono le unità; all'inizio di ogni unità si salta ciò che l'altro ha pubblicato | — |
| Stesso contenuto in area privata e comune | una volta; pubblicato in entrambe le share se il dispositivo le scrive (stessa chiave, M3 rispettata) | chi vede l'una o l'altra |
| Media solo sul telefono di un indicizzatore | lui, dai byte del telefono | nessuno: non è sul NAS |
| Media solo sul telefono di un non indicizzatore | nessuno finché non va sul NAS | la copertura lo dice (E-A15) |
| Share vera con F4-D31 acceso | ogni indicizzatore per sé; i risultati restano locali e si pubblicano quando la share si apre (D8, Q7) | nessuno, fino ad allora |
| Area privata di un account senza indicizzatori | nessuno; avviso nella pagina e in Cerca (E-I12) | filtri e testo già letto restano |

| Edge case | Comportamento |
|---|---|
| E-A5 | Caldo: `Pace.REDUCED` (lotti da 5 invece di 20, pausa di 2 s fra lotti) da `LIGHT`; stop da `SEVERE`, ripartenza dopo 5 minuti (regole) |
| E-A6 | Notte senza NAS: `Run(LOCAL_BUFFER_ONLY)`, si calcola ciò che c'è nel buffer, poi stato "Fatto ciò che c'era in locale: 120 media; il resto aspetta il NAS" |
| E-A9 | Video lunghissimi: al più 9 fotogrammi già dalla preparazione (uno ogni 30 s, al massimo 8 più la copertina) |
| E-A10 | Ricetta o spazio nuovi: D21 |
| E-A12 – E-A17 | §0.2 |
| E-I1, E-I2 | Prenotazioni delle unità; scadenza entro un'ora |
| E-I11 | Il tablet carica e prepara senza indicizzare; il telefono indicizzatore scarica solo le anteprime (~150 KB) quando le regole lo consentono (pesante: solo Wi-Fi di casa, proposta 1); fuori casa calcola solo sul buffer |
| E-I12 | Aree senza indicizzatori: D14 |
| E-I19 | Area in sola lettura per questo account: risultati solo locali, nessuna pubblicazione |
| E-P13, E-P19 | Riavvio notturno, profilo non attivo: ripresa allo sblocco e al ritorno, come gli altri lavori |
| E-H1 | I nascosti si calcolano (risposta Q6) e non si mostrano mai (D29) |
| E-A18, E-A19 | Lettura del testo spenta dopo essere stata accesa, o dispositivo escluso: §0.2, D41 |

### 2.3 Formato dei dati AI sul NAS (P2)
Dopo `"MCGB" | versione 2` il contenuto ha la stessa tabella di §5.5-bis del formato (classe, N voci, chiavi crescenti, lunghezze),
con classe 7 o 8 e chiave = chiave di contenuto. I corpi:

**Corpo `vec`** (big endian come il resto del formato)
```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           spazio (1 = embeddinggemma2-float-v1; altri rifiutati)
2       2           dimensione (768 per lo spazio 1)
4       1           precisione (2 = float16; altre rifiutate)
5       1           ricetta (1..255)
6       1           sorgente (1 anteprima di analisi, 2 fotogrammi, 3 byte originali del telefono)
7       1           N vettori (1..9; esattamente 1 per le sorgenti 1 e 3)
8       N × 4       istante del fotogramma in ms (solo sorgente 2)
...     N × 768 × 2 componenti float16
```
Lunghezza esatta; ogni componente finita; norma ricalcolata in float32 fra 0,99 e 1,01. Corpo al massimo 13.868 byte.

**Corpo `ocr`**
```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           motore (1 = ML Kit Text Recognition v2, script latino)
2       1           ricetta OCR (1..255)
3       1           sorgente (1 analisi, 2 HQ, 3 fotogrammi, 4 byte originali del telefono)
4       2           N righe (0..400; 0 = "analizzata, nessun testo")
6       ...         per riga: riquadro (4 × 2 byte, x0 y0 x1 y1 in 1/65535 dell'immagine raddrizzata, x0 ≤ x1, y0 ≤ y1),
                    inclinazione (1 byte con segno, gradi da −90 a 90, dall'angolo della riga di ML Kit),
                    M parole (1 byte, 1..64),
                    M × (2 + 2 byte): inizio e fine di ogni parola lungo la riga, in 1/65535 della lunghezza della riga,
                    non decrescenti,
                    lunghezza del testo (2 byte, 1..1024), testo UTF-8 (le parole sono i pezzi separati da uno spazio: devono
                    essere M)
```
Testo: UTF-8 valido (niente forme lunghe né surrogati), in forma NFC, senza caratteri di controllo C0 e C1, senza i caratteri di
direzione (U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) e senza U+FEFF. Corpo al massimo 64 KiB. Inclinazione ed estremi delle
parole servono al testo dal vivo (D43): costano ~4 byte a parola (una schermata fitta da 400 parole: ~1,6 KB in più) e così il
testo dal vivo funziona anche dove ML Kit non gira.

**Regole di chi legge** (come §5.5-bis: dimensione e impronta uguali alla testa, classe uguale, tabella coerente, ogni corpo nei suoi
tetti, nessuna allocazione da lunghezze dichiarate). Ogni errore scarta **il segmento intero** (`AI_SEGMENT_REJECTED`), non ferma
gli altri; i dati già nella replica restano (E-I17, E-I20).

**Attestazione e fusione** (D7)
- Una voce vale solo se nella **stessa share** c'è un record `meta` presente (vincitore di F4-D6) con la **stessa chiave di contenuto
  intera**; senza, resta in attesa (E-I21). Si riattesta a ogni testa applicata.
- Teste `ai` di dispositivi revocati, persi o sconosciuti: ignorate e dimenticate (come `forgetDevice` delle teste `index`); area
  persa (E-I8): le voci di quella share escono dalla replica.
- Più voci valide per (share, chiave, classe): ricetta più alta, poi id del dispositivo minore. `vec` e `ocr` indipendenti.
  Disaccordo fra vettori della stessa ricetta (coseno < 0,95): `AI_VECTOR_DISAGREE`.

**Pubblicazione** (D8): regola M3 (solo chiavi di file della share, timbro e `qh1` freschi nel catalogo locale); stessi cancelli di
`IndexWriteRules`; una testa `ai` al più ogni 5 minuti d'ora del NAS per share, salvo la fine di un'unità; segmenti a 12 MiB
(al più 4.096 voci, il tetto del formato). Compattazione dei propri segmenti `ai` come il `Compactor` della Fase 4.

**Compatibilità** (E-I3, E-I22): versione 2 solo per teste e segmenti `ai`; chi legge accetta 1..2; un'app vecchia non apre `ai/`.

### 2.4 Indice locale e motore di ricerca (6.3, P6)
**Costruzione dell'indice locale**
- Le righe di `ai_vector` (per chiave di contenuto, spazio, ricetta, fotogramma) puntano a un **file di vettori** in sola aggiunta
  (`noBackupFilesDir/search/vectors-<spazio>.bin`, float16, CRC per record): è l'unica copia dei vettori sul dispositivo (Room tiene
  solo l'indice delle righe). Il file della **prima passata** (`coarse-<spazio>.bin`, int8 a 256 dimensioni con scala) si ricava dal
  primo e si ricostruisce se manca. Un file rovinato si ricostruisce dalla replica (o si ricalcola, per i media del solo telefono).
- **Mappa delle celle**: per ogni posizione dell'istantanea della Libreria (che esclude nascosti e membri di gruppo) le chiavi di
  contenuto della versione corrente della cella e dei membri del suo gruppo; se una versione non ha ancora il vettore, vale quello
  di una versione precedente (E-A10). Si aggiorna per `change_seq` (`CatalogChange`) e per le righe nuove, a lotti.
- Compattazione dei file quando le righe morte superano il 20% (in `LibraryMaintenanceWorker`).

**Una query, passo per passo** (stime da misurare in P9 e F53; il Tab S9 non ha misure della Fase 0)
| Passo | Cosa | S25 | Tab S9 |
|---|---|---|---|
| 1 | Analisi della query (§2.5) | < 2 ms | < 3 ms |
| 2 | Candidati: istantanea della Libreria filtrata dai chip, in memoria | < 5 ms | < 8 ms |
| 3 | Vettore della query: 270M su CPU, motore già caldo (misurato 53–58 ms) | ~55 ms | ~90 ms |
| 4 | Prima passata int8 a 256 dimensioni sui candidati (100 mila, 4 thread) | ~15 ms | ~25 ms |
| 5 | Distribuzione dei punteggi e soglie (D27) | < 2 ms | < 3 ms |
| 6 | Seconda passata float16 a 768 sui primi 1.000 | ~3 ms | ~5 ms |
| 7 | Testo: FTS4 e vocabolario (in parallelo con il passo 3) | < 20 ms | < 30 ms |
| 8 | Fusione, gruppi, filtro dei nascosti, ordine | < 10 ms | < 15 ms |
| 9 | Prima schermata di risultati con miniature locali | < 30 ms | < 40 ms |
| | **Totale** (obiettivo del piano: p95 < 300 ms con 100 mila elementi) | **~140 ms** | **~215 ms** |

**Motore a freddo** (D35): all'apertura di Cerca si verifica il 270M (157 MiB, stima 0,2–0,5 s) e si inizializza il motore (Fase 0:
3,6 s a freddo per il 440M su GPU, meno con la cache): nel frattempo rispondono filtri e testo, con "Sto preparando la ricerca per
contenuto…" (E-Q11). Il p95 dell'obiettivo si misura a motore caldo; il primo risultato a freddo si misura a parte (§5.4).

**Soglia di convinzione** (D27)
- Per ogni query: punteggio della prima passata di ogni candidato (foto: il suo vettore; video: il massimo dei fotogrammi); mediana
  `m` e deviazione robusta `σ = 1,4826 · MAD`; `z = (s − m) / σ`.
- **Sicuri**: `z ≥ Zc` e `s ≥ Smin`; **meno probabili**: `Zl ≤ z < Zc`, al massimo 300 celle. Valori di partenza prudenti (`Zc = 3,5`,
  `Zl = 2,0`, `Smin = 0` finché P9 non lo misura), poi **calibrati in P9** sul set privato (§5.3): precisione dei sicuri ≥ 90%, ≥ 80% delle attese
  fra i primi 20 della classifica. I valori finiti stanno in una costante della ricetta della ricerca, con il test che li rilegge.
- Una distribuzione piatta (niente stacca: la cosa cercata non c'è) dà zero sicuri e, se ce ne sono, i meno probabili (E-Q12).

**Fusione** (D27)
| Caso | Sicuro | Meno probabile |
|---|---|---|
| Solo filtri (E-Q4) | tutti i candidati, per data | — |
| Contenuto | `z ≥ Zc` e `s ≥ Smin` | `Zl ≤ z < Zc` |
| Testo | tutte le parole della query lette nel media, esatte (dopo la normalizzazione) | almeno metà, anche con un refuso (D25) |
| Contenuto e testo | uno dei due sicuro | uno dei due meno probabile |
| Testo esatto (fra virgolette) | la frase letta così com'è (normalizzata) | — |
| Simili (D30) | `z ≥ Zs` sulla distribuzione immagine–immagine | `Zls ≤ z < Zs` |

Classifica per pertinenza dentro ogni classe: RRF dei ranghi del contenuto e del testo (`k = 60`), a parità la data più recente. La
qualità (§5.3) si misura su questa classifica **senza** il premio di recenza (`β = 0`); l'ordine mostrato segue la scelta
dell'utente (D48, sotto).

**Ordine dei risultati** (D48, risposta Q10)
- **Pertinenza** `p` in [0, 1] di una cella: contenuto `p_c = min(1, max(0, (z − Zl) / (Zc + 2 − Zl)))`; testo `p_t = 1` con tutte le
  parole esatte, `0,6` con almeno metà, altrimenti 0; `p = min(1, max(p_c, p_t) + 0,1 se entrambi > 0)`. Solo filtri: `p = 1`.
- **Recenza** `ρ(a) = 2^(−a / H)`, con `a` = giorni dallo scatto (`local_day`) a oggi (data del telefono, D39) e `H` = mezza vita
  (partenza: 90 giorni). Una foto di oggi vale 1, di tre mesi fa 0,5, di un anno fa 0,06.
- **"Pertinenti"**: chiave `p + β · ρ(a)`, decrescente, con `β` = 0,10 di partenza. A pertinenza quasi uguale vince la più recente;
  una molto vecchia perde al più `β` rispetto a una di oggi; una molto pertinente resta sopra una poco pertinente anche se recente
  (la differenza di pertinenza supera `β`). A parità di chiave, la data.
- **"Recenti"** (predefinito): i risultati vanno per **periodo**, dal più recente: lo stesso giorno per gli ultimi 31 giorni, lo
  stesso mese fino a 2 anni, lo stesso anno oltre; **dentro il periodo per pertinenza** `p`, poi per data. Così un risultato poco
  pertinente non sta mai sopra uno molto pertinente dello stesso periodo, e la griglia può mostrare le intestazioni di periodo.
- **Taratura** con il set di Q8 (P9): `H` fra {30, 90, 180, 365} giorni e `β` fra {0,05; 0,10; 0,15; 0,20}: si sceglie il `β` più grande
  che non toglie più di 1 punto di recall@20 rispetto a `β = 0`, e fra gli `H` quello con l'MRR migliore sulle query con le date più
  recenti. I confini dei periodi di "Recenti" sono una scelta di presentazione e non si tarano.
- **Ricordata**: l'ultima scelta del selettore è l'impostazione personale `search_order`, sincronizzata fra i propri dispositivi (4.7);
  vale per sicuri e meno probabili e per "Trova simili".

**Gruppi, nascosti, viewer**: una cella per gruppo, punteggio del membro migliore; nascosti esclusi dai candidati e da un filtro
finale (D29); risultati come `OrderedTimelineView` viva (un media nascosto o eliminato altrove esce, E-Q13); miniature solo locali
(replica e telefono): una cella senza miniatura mostra il segnaposto ThumbHash (D28).

**Copertura** (D31): per le celle candidate (senza nascosti), quante hanno vettore e testo della ricetta corrente (il testo solo dove
qualcuno ha la lettura attiva, D42) e quante aspettano,
per motivo. Ricalcolata a ogni cambio dell'indice, non a ogni query.

### 2.5 Analizzatore italiano delle query (6.3, P7)
**Le categorie**
| Categoria | Esempi | Chip |
|---|---|---|
| Giorni relativi | oggi, ieri, l'altro ieri, sabato scorso | "Ieri · 20 ottobre 2026" |
| Periodi relativi | questa settimana, la settimana scorsa, il mese scorso, quest'anno, l'anno scorso, il weekend scorso | lunedì–domenica, mese, anno |
| Fa | 3 giorni fa, 2 settimane fa, un mese fa, 2 anni fa | il giorno, la settimana, il mese o l'anno di allora |
| Mesi | agosto, ago, ad agosto | "Agosto (tutti gli anni)" |
| Mese e anno | agosto 2023, ago 23, 8/2023 | "Agosto 2023" |
| Anni e decenni | 2023, nel 2023, anni 90, anni '90, anni novanta | anno; 1990–1999 |
| Date | 12 agosto, 12/8, 12/08/2023, 12-08-2023 (giorno/mese, all'italiana) | il giorno |
| Intervalli | dal 3 al 10 agosto 2023, tra marzo e maggio | l'intervallo |
| Stagioni | estate 2023, l'inverno scorso, in primavera | meteorologiche: estate giugno–agosto; "inverno 2023" = dicembre 2023–febbraio 2024, detto nel chip "Inverno 2023–24" |
| Feste | Natale, Vigilia, Santo Stefano, Capodanno, Epifania, Pasqua, Pasquetta, Ferragosto, San Valentino | Natale 24–26/12, Capodanno 31/12–1/1, Pasqua e Pasquetta calcolate per anno; senza anno tutti gli anni |
| Luoghi | Firenze, a Firenze, in Toscana, Monaco di Baviera, in Italia | luogo, regione o paese (sotto) |
| Tipi | foto, video, screenshot, schermate, panorami, foto in movimento, documenti | tipo (D34 per i documenti) |
| Sorgenti e stati | WhatsApp, Quick Share, preferiti, modificate, sul telefono, sul NAS, solo sul NAS | sorgente, preferiti (`FAVORITE`), modificate (`EDITED`), presenza (`ON_PHONE`, `ON_NAS`) |
| Testo esatto | "Via Roma 12" | parola o frase |
| Il resto | cane in spiaggia, scontrino Esselunga | contenuto (vettore) e parole (testo) |

**Gli esempi del piano**
| Query | Chip | Contenuto | Parole cercate nel testo |
|---|---|---|---|
| cane in spiaggia | — | "cane in spiaggia" | cane, spiaggia |
| scontrino Esselunga | — | "scontrino Esselunga" | scontrino, esselunga |
| Firenze estate 2023 | Firenze · Giugno–agosto 2023 | — | — |
| video di Natale | Video · Natale (24–26 dicembre, tutti gli anni) | — | — |
| screenshot di ieri | Screenshot · Ieri (20 ottobre 2026) | — | — |

**Regole**
- Le date si confrontano con `local_day` dell'istantanea (il giorno nell'ora del posto dello scatto, come le intestazioni della
  Libreria); "oggi" è la data del telefono (D39).
- **Luoghi**: il dizionario nasce dai `place_id` delle celle **visibili** della Libreria (mai dei nascosti), con nome del luogo,
  regione (`admin1`) e paese da `ReverseGeocoder.place(id)`; nomi normalizzati (minuscolo, senza accenti, apostrofi uniformi), di più
  parole ("monaco di baviera", "reggio emilia") con la corrispondenza più lunga; preposizioni tolte ("a", "in", "al", "ad", "da",
  "di", "nel", "nella"). Una regione o un paese diventano l'insieme dei luoghi che vi appartengono. Fra omonimi vince quello con più
  media e il chip propone gli altri; ogni chip di luogo propone anche "Cerca come parola" (E-Q10). Solo i nomi italiani del dataset:
  un nome straniero non riconosciuto passa al contenuto e al testo.
- **Refusi** (E-Q5): sul dizionario (mesi, giorni, feste, tipi, luoghi) distanza 1 da 4 a 7 lettere e 2 da 8 in su, solo se il
  migliore è unico; l'avviso dice "Ho cercato «screenshot» invece di «screeshot»". Sul testo letto vale il vocabolario di D25.
- **Ambiguità** (E-Q2): l'interpretazione scelta è sempre nel chip, che si tocca per cambiarla; date impossibili si correggono e lo si
  dice ("31 febbraio" → "29 febbraio 2024"); anni futuri restano ("2099": nessun risultato, chip visibile).
- **Query vuota o di una lettera** (E-Q1): suggerimenti, nessuna ricerca. **Più di 200 caratteri** (E-Q9): troncata.
- Le parole consumate dai chip escono dal contenuto; le parole vuote ("di", "il", "con", "foto di") escono dal testo ma restano nel
  contenuto (il modello le usa).

### 2.6 Schermata Cerca, pagine e stati (6.4, P8)
**Cerca**
- **A riposo**: ricerche recenti (fino a 10, con "Cancella" e "Cancella tutte"), luoghi più frequenti (fino a 8, mai da nascosti),
  tipi (Screenshot, Video, Documenti, Preferiti, Panorami).
- **Mentre si scrive**: completamenti di luoghi, mesi, feste, tipi e ricerche recenti. **Mai parole del testo letto** (il
  vocabolario contiene anche i nascosti, D29).
- **Risultati**: barra dei chip; selettore **"Recenti · Pertinenti"** (ricordato, D48); sezione "Risultati" (sicuri) con il conteggio,
  con le intestazioni di periodo in "Recenti"; "Mostra anche risultati meno probabili (N)" apre la sezione "Meno probabili" (stesso
  ordine); avviso di copertura sotto il 98%; stessa cella della Libreria; selezione
  multipla con le azioni della Libreria se il contratto della selezione si riusa così com'è (altrimenti rinviata, P8 lo dice).
- **Viewer**: lo stesso; scorre i risultati nell'ordine mostrato; il ritorno riporta alla stessa posizione.
- **Accessibilità**: chip letti da TalkBack ("Filtro data: ieri, 20 ottobre. Tocca per modificare"), bersagli da 48 dp, testo a 1,3x.

**Stati vuoti e d'errore** (ognuno con un'azione)
| Situazione | Messaggio | Azione |
|---|---|---|
| Modello della ricerca assente (E-Q11) | Per cercare per contenuto serve il modello di ricerca (157 MB). Intanto cerco per date, luoghi e testo. | Scarica |
| Modello in caricamento | Sto preparando la ricerca per contenuto… | — |
| Nessun risultato (E-Q3) | Nessun risultato per «…». Prova a togliere un filtro. | tocco su un chip; "Mostra anche…" se ci sono meno probabili |
| Solo meno probabili (E-Q12) | Nessun risultato sicuro. | Mostra anche risultati meno probabili (N) |
| Copertura parziale (E-Q6) | La ricerca per contenuto copre 12.300 media su 15.000. | Perché? (Ricerca e modelli) |
| Area senza indicizzatori (E-I12) | Nessun dispositivo analizza l'area comune. | Attiva su questo dispositivo |
| Parole cercate con la lettura del testo non attiva da nessuno (una volta, poi solo nella pagina) | Le parole dentro le foto non si cercano: la lettura del testo è spenta. | Scopri di più (pagina di attivazione) |
| Libreria vuota o permessi negati | Non ci sono ancora foto da cercare. | Consenti accesso |
| Errore interno | La ricerca non ha funzionato. | Riprova (codice in Diagnostica) |

**Impostazioni → Ricerca e modelli**: ruolo Indicizzatore (con la misura del dispositivo e la stima; Q3), **lettura del testo nelle
foto** (spenta; "Attiva per il mio account", "Non su questo dispositivo", "Spegni"), stato del calcolo (`AiStatus`, "Indicizza ora"),
modelli (stato, dimensione, sorgente, "Scarica ora", "Rimuovi"), spazio di vettori e testo, aree senza indicizzatori, "Cancella le
ricerche recenti".

**La pagina di attivazione della lettura del testo** (D41), in frasi brevi:
- "Per cercare le parole dentro le foto (scontrini, cartelli, screenshot) e per copiarle, l'app usa ML Kit di Google, che lavora sul
  telefono: **le foto e il testo non escono mai dal telefono**."
- "Quando è attiva, ML Kit manda a Google **statistiche tecniche**: marca e modello del telefono, versione di Android, nome e versione
  dell'app, un codice di questa installazione, tempi ed eventuali errori. Lo decide Google e non si può spegnere: per questo la
  funzione è spenta finché non la attivi."
- "Vale per i dispositivi del tuo account; su ognuno puoi dire «Non su questo dispositivo». La spegni quando vuoi: da quel momento
  ML Kit non parte più."
- "Anche spenta, puoi cercare e copiare il testo che un altro dispositivo della famiglia ha già letto."
- Pulsanti "Attiva" e "Non ora". Il testo ha una versione: se cambia (per esempio dopo una cattura che trova altro), si richiede.

**Viewer**: ⋮ → "Trova simili" (D30), "Testo nella foto" e "Ritaglia il soggetto"; testo dal vivo e ritaglio con la pressione
prolungata (§2.10). **Libreria**: chip "Documenti" nel filtro rapido (D34).

**Ogni problema ha un codice e un'azione** (codici stabili nel catalogo della Diagnostica, un test li confronta con il contratto)
| Codice | Messaggio | Azione |
|---|---|---|
| `AI_MODEL_MISSING` | Modello non scaricato | Scarica ora |
| `AI_MODEL_FETCH_FAILED` | Scaricamento del modello non riuscito | Riprova · Diagnostica |
| `AI_MODEL_MISMATCH` | Un file del modello non era quello giusto: scartato | — (si riprova da un'altra sorgente) |
| `AI_MODEL_NO_SPACE` | Spazio insufficiente per il modello (serve X) | Libera spazio |
| `AI_ENGINE_INIT_FAILED` | Il motore di ricerca non è partito su questo dispositivo | Diagnostica |
| `AI_GPU_FALLBACK` | Analisi senza GPU: più lenta | — |
| `AI_VECTOR_INVALID` | N media non analizzati (errore del motore) | Riprova |
| `AI_NO_INDEXER_AREA` | Nessun dispositivo analizza un'area | Attiva su questo dispositivo |
| `ACT_LOCAL_BUFFER_ONLY` (c'è già) | Analizzato ciò che c'era in locale | — |
| `AI_STORAGE_LOW` | Memoria quasi piena: analisi ferma | Libera spazio |
| `AI_KILLED_MEMORY` | Android ha chiuso l'analisi per memoria | — (riprende) |
| `AI_SEGMENT_REJECTED` | Dati di ricerca non validi scartati | Diagnostica |
| `AI_VECTOR_DISAGREE` | Due dispositivi danno risultati diversi | Diagnostica |
| `AI_PENDING_PUBLISH` | N risultati in attesa di poter essere condivisi (share non pubblicabile) | — |
| `OCR_FAILED` | Testo non letto in N media | Riprova |
| `SEARCH_INDEX_REBUILT` | Indice di ricerca ricostruito | — |
| `TEXT_READING_OFF_CLEANUP` | Lettura del testo spenta: statistiche in coda tolte | — |
| `CUTOUT_FAILED` | Ritaglio non riuscito | Riprova · Diagnostica |
| `CUTOUT_SAVE_FAILED` | Ritaglio non salvato (spazio o permesso) | Libera spazio · Riprova |

### 2.7 Regole applicate e lavori (P5, P11)
| Lavoro | Attività | Note |
|---|---|---|
| Scaricamento dei modelli (in background) | regole di `AI_COMPUTE` (pesante: fascia e alimentazione del preset, solo sul Wi-Fi di casa con la proposta 1); da HTTPS rete validata e non a consumo, dal NAS il cancello di rete del NAS | mai dati mobili né roaming; traffico come `AI_COMPUTE` |
| "Scarica ora" | `UserInitiated(AI_COMPUTE)` | scavalca fascia e carica; sui dati mobili chiede conferma con la dimensione |
| Calcolo AI | `AI_COMPUTE` (pesante, `mediaProcessing`, ruolo Indicizzatore) | Notte di default; `LOCAL_BUFFER_ONLY` senza rete utile; le anteprime si scaricano solo sul Wi-Fi di casa (pesante, proposta 1) |
| "Indicizza ora" | `UserInitiated(AI_COMPUTE)` | scavalca fascia e carica, non `CRITICAL`; col caldo ritmo ridotto |
| OCR in background | dentro `AI_COMPUTE`, solo su un indicizzatore con la lettura attiva | D41 |
| Testo dal vivo, ritaglio, OCR al volo | nessuna attività | in primo piano, su un tocco, tutto locale |
| Teste `ai` | `INDEX_SYNC` (leggera) | nello stesso giro delle teste `index` |
| Ricerca | nessuna attività | in primo piano, tutta locale |

- **Tappe da 8 minuti** con `StagedRun`, come la Fase 5; `AndroidLimits` compilato dal lavoro (budget `mediaProcessing`).
- **Battito**: `AI_COMPUTE` nel `HeartbeatStore`; "analisi ferma da 7 giorni con media in attesa" nel riepilogo giornaliero
  (`NasDailySummary`), solo numeri.
- **Nessun servizio nuovo**: niente lavori avviati dall'utente (UIDT) per l'AI (non è un trasferimento di dati), niente servizi in
  primo piano; "Scarica ora" è un lavoro accelerato di WorkManager (D17).

### 2.8 Stima dei costi
**Ipotesi** (da sostituire con le misure di P4, F52 e F53)
- Archivio di famiglia (F4 §2.10): ~15.550 immagini e ~2.570 video sul NAS; S25 6.658 media e Tab S9 ~890, che col backup diventano
  in gran parte media del NAS. **In tutto ~22.000 foto e ~2.600 video**, con ~4 fotogrammi a video: **~32.000 vettori**.
- Embedding 440M su GPU: 4,4 foto/s di solo calcolo (Fase 0); dalle anteprime da 1024 px la decodifica pesa meno che dagli originali:
  ~3,6 foto/s, ~2,8 foto/s col caldo (−26%). OCR (ML Kit su CPU): **non misurato**, ipotesi 0,1–0,25 s a immagine, in parallelo
  all'embedding; seconda passata su ~15% (screenshot e testo fitto).
- Anteprime: analisi ~150 KB, fotogrammi ~450 KB a video, HQ ~400 KB; lettura SMB 16 MB/s.

**Prima indicizzazione** (in casa, un indicizzatore, S25)
| | Valore centrale | Note |
|---|---|---|
| Calcolo | ~32.000 vettori / 2,8 al secondo ≈ **3,2 ore** | con due indicizzatori ~1,6 ore ciascuno |
| Scaricamento delle anteprime | ~3,3 GB (foto) + ~1,2 GB (video) + ~1,2 GB (HQ per il testo fitto) ≈ 5,7 GB → **~6 minuti** | si sovrappone al calcolo |
| Batteria | ~4% ogni 1000 foto (Fase 0) → ~130% | dalla presa con Notte: **una notte** (23–07 in carica sono ~6,4 ore utili) |
| Pubblicazione | `vec` ~50 MB, `ocr` ~8 MB | segmenti da 12 MiB, pochi secondi |

**A regime** (200 foto e 10 video al mese): ~250 vettori al mese, **~2 minuti di calcolo al mese**, ~1% di batteria al mese (dalla
presa con Notte): dentro l'obiettivo del 2% al giorno.

**Spazio**
| Dove | Archivio di famiglia (~32.000 vettori) | 100 mila media |
|---|---|---|
| NAS, dati `ai` (per tutte le share) | ~60 MB | ~180 MB |
| NAS, copie dei modelli (una volta) | 527 MB | 527 MB |
| Dispositivo, modelli | 157 MB (tutti) + 370 MB (indicizzatori) | uguale |
| Dispositivo, file dei vettori + prima passata | ~50 MB + ~8 MB | ~150 MB + ~26 MB |
| Dispositivo, testo e FTS | ~15 MB | ~50 MB |
| Dispositivo, buffer degli indicizzatori | ≤ 500 MB, svuotato man mano | uguale |
| RAM in Cerca | ~330 MB del motore 270M su CPU (misura Samsung della Fase 0) + 8 MB | + 26 MB |
| APK | + `liblitertlm_jni.so` (~22 MB non compressi per ABI), ML Kit latino (~4 MB per ABI), LiteRT (pochi MB, da misurare in P0) e MagicTouch (~6 MB, Q11 (a)) | uguale |
| RAM durante un ritaglio | stima < 100 MB oltre all'immagine (MagicTouch è piccolo; immagine al più 4096 px: 64 MB in ARGB) | uguale |

**Traffico**: ogni dispositivo scarica le teste `ai` delle share che vede (~60 MB la prima volta per l'archivio di famiglia, poi
pochi KB); i modelli una volta da Internet per famiglia (527 MB), poi dal NAS; nessun traffico durante le ricerche.

### 2.9 Cosa la Fase 6 predispone per le fasi successive
| Pezzo | Come lo usano |
|---|---|
| Analizzatore delle query (date, feste, luoghi) | Fase 7: il nome di un album smart ("Oslo 2026") interpretato come periodo e luogo |
| Soglia di convinzione (sicuri e meno probabili) | Fase 7: gli album smart fanno entrare da soli i sicuri e mettono in "Suggerite" i meno probabili |
| Testa `ai`, codec ostile, attestazione dei derivati | Fase 8: dati dei volti con lo stesso metodo, ma nell'area privata (proposta 13) |
| Catalogo dei modelli fissato, scaffale sul NAS | Fase 8: modelli dei volti (YuNet, SFace) con hash fissato |
| Indice vettoriale locale e "Trova simili" | Fase 7: Utility → Doppioni (scatti simili ma diversi); Fase 9: ricordi |
| Dizionario dei luoghi della Libreria | Fase 9: mappa e ricordi |
| Filtro Documenti | Fase 7: Utility |
| Formato del manifest firmato (§2.1) | Fase 10: versione minima e kill switch |
| Ritagliatore (MagicTouch su LiteRT) | Fase 9: copertine dei ricordi; Fase 10: widget con il soggetto ritagliato |

### 2.10 Testo dal vivo e ritaglio del soggetto nel viewer (risposta Q9; P12, P13)
**Testo dal vivo** (D43)
| Momento | Cosa | Dettagli |
|---|---|---|
| Disponibilità | la foto ha testo letto nell'indice locale (`MediaText.layout`) | da qualunque dispositivo della famiglia (D42); altrimenti, se la lettura è attiva **qui**, lettura al volo dell'immagine mostrata (≤ 2 s, "Leggo il testo…"), salvata e pubblicata come le altre; se è spenta, niente (E-V9) |
| Pulsante "Testo" | nella barra del viewer, solo se c'è testo | evidenzia tutte le righe con un velo leggero; un secondo tocco apre il foglio "Testo nella foto" |
| Pressione prolungata su una riga | seleziona la parola sotto il dito | la riga si colora; compaiono le due maniglie e la barra "Copia · Seleziona tutto · Condividi testo" |
| Maniglie | si trascinano e si agganciano agli estremi delle parole; passano da una riga all'altra nell'ordine di lettura (dall'alto, poi da sinistra) | le righe inclinate usano l'inclinazione pubblicata; bersagli da 48 dp; seguono lo zoom e lo spostamento |
| Copia | appunti con `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+: niente anteprima negli appunti) | annuncio "Testo copiato"; il testo copiato non va mai nei log |
| Condividi testo | `ACTION_SEND` di testo semplice | come ogni condivisione dell'app |
| Chiusura | tocco fuori, indietro, scorrimento al media dopo (dopo aver chiuso) | la selezione non sopravvive al cambio di media |

**Ritaglio del soggetto** (D44, D47)
| Momento | Cosa | Dettagli |
|---|---|---|
| Pressione prolungata fuori dal testo | punto normalizzato sull'immagine raddrizzata, dopo la trasformazione dello zoom | vibrazione all'inizio; un velo pulsante mentre MagicTouch lavora (stima 150–400 ms) |
| Maschera | MagicTouch dal punto, su un'immagine a 512 o 768 px; poi filtro guidato alla risoluzione dell'uscita, soglia, sfumatura di 1–2 px | nessun soggetto chiaro → niente (E-V10) |
| Il soggetto si stacca | animazione: il soggetto si solleva con un contorno luminoso, il resto si attenua | barra "Copia · Condividi · Salva come nuova foto"; interruttore "Contorno" (adesivo con bordo bianco) |
| Copia | PNG trasparente in `cacheDir/share/` del `FileProvider` esistente, `ClipData` con permesso di lettura, segnato sensibile | annuncio "Soggetto ritagliato e copiato" |
| Condividi | lo stesso PNG con `ACTION_SEND`, `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` (Android 17 e 18) | nessuna posizione: il PNG nasce senza metadati |
| Salva come nuova foto | PNG in `Pictures/MyCloud Gallery/Ritagli/` (D47) | data di adesso; dai Nascosti nasce nascosta (E-V11); entra nel backup come media nuovo |
| Sorgente dell'immagine | la migliore già sul dispositivo: originale del telefono, originale aperto nel viewer, altrimenti l'HQ della replica | mai uno scaricamento solo per ritagliare; al più 4096 px sul lato lungo (tetti di `ImagingBudget`) |

**Gesti** (D45)
| Dove cade il dito | Foto | Foto in movimento | Video |
|---|---|---|---|
| Su una riga di testo letto | testo dal vivo | testo dal vivo | — |
| Altrove | ritaglio | ritaglio (il video parte solo dal badge "Movimento", D51; risposta Q14) | — (i controlli del video) |
| Durante l'animazione d'apertura (D50) | — | qualunque tocco la ferma subito e mostra la foto; poi il gesto vale come sopra | — |
| Con una selezione o un ritaglio aperti | tocco fuori = chiudi; pager e trascinamento verso il basso fermi; pizzico e spostamento dello zoom attivi; doppio tocco ignorato | come la foto | — |

L'arbitro **prende il posto** di `holdToPlay` (`ViewerGestures.kt`), con la stessa tecnica: osserva nel passaggio `Initial` senza
consumare finché non scade il tempo della pressione lunga di sistema; un secondo dito o un movimento oltre la soglia annullano, come
oggi. `holdToPlay` e `onMotionHold`/`onMotionRelease` si tolgono (P12 il gesto, P14 la riproduzione).

**Accessibilità** (D46): azioni di TalkBack sull'immagine "Testo nella foto" (foglio con il testo selezionabile e "Copia tutto") e
"Ritaglia il soggetto" (il soggetto più grande fra cinque punti di prova); il foglio è la via accessibile al testo, perciò il "Copia
testo" nelle info non serve. Annunci per ogni esito; con "Rimuovi animazioni" di sistema niente animazione del soggetto.

**Scelta del modello del ritaglio** (verifiche del 21/10, fonti al §10; le latenze sui nostri dispositivi sono stime da misurare in P13)
| Modello | Licenza | Dimensione | Come si sceglie il soggetto | Latenza | Memoria | Qualità | Esito |
|---|---|---|---|---|---|---|---|
| **MagicTouch** (Google, Interactive Segmenter) | **Apache 2.0** (scheda del modello, 13/03/2023) | **6 MB** | da un punto (il dito) | v1: ~30 fotogrammi/s su Pixel 7 con la GPU (scheda); v2 int8 768×768: 208 ms su CPU e 581 ms su GPU, catena intera, Pixel 10 (pagina MediaPipe); stima S25 ~150–250 ms, Tab S9 ~250–400 ms su CPU | stima < 100 MB | buona su oggetti, animali e persone; bordi non perfetti, dettagli sottili (dita) a volte persi (scheda); persona: IoU medio 87,7% sul loro set | **scelto** (D44), con LiteRT e non MediaPipe Tasks |
| MobileSAM | Apache 2.0 (codice; pesi non dichiarati a parte) | ~9,7 milioni di parametri (~40 MB in float32) | punto o riquadro | 12 ms su GPU da tavolo, ~3 s su una CPU Mac i5 (repository); telefono: stima 0,5–2 s | stima 200–400 MB | buona, ma un punto può dare una parte invece del tutto | **piano B**, con ONNX Runtime (MIT) |
| U²-Net / U²-Netp | Apache 2.0 (codice) | 176 MB / 4,7 MB | nessuno: prende "l'oggetto saliente" | U²-Netp veloce (320×320) | bassa | un solo soggetto, non quello toccato | scartato |
| BiRefNet (e lite) | MIT (codice; pesi non dichiarati) | centinaia di MB | nessuno | 58–96 ms su RTX 4090, 3,5–4,8 GB di memoria GPU a 1024×1024 | GB | ottima | scartato: troppo pesante per un telefono |
| ML Kit Subject Segmentation | termini ML Kit | — | nessuno | — | — | — | escluso: solo tramite Google Play services, in beta |
| RMBG-1.4 e 2.0 (BRIA), EdgeSAM | non commerciali [D] | — | — | — | — | — | esclusi: licenza |

**Runtime del ritaglio.** MagicTouch è un file TFLite: si esegue con `com.google.ai.edge.litert:litert` 2.3.0 (06/10/2026, Apache 2.0)
su CPU, con pre e post-elaborazione nostre (immagine e mappa del punto in ingresso, softmax in uscita). **Non** con MediaPipe Tasks
(`tasks-vision`): a luglio 2026 i manutentori di MediaPipe hanno confermato statistiche d'uso "per tutte le piattaforme" senza
un'opzione per spegnerle (§10). Per LiteRT nessuna telemetria è nota: lo prova la cattura F67; se qualcosa uscisse, piano B
(MobileSAM con ONNX Runtime). Da verificare in P0 e P13: che `litert` e `litertlm-android` convivano nello stesso processo (due
runtime LiteRT, librerie native con nomi diversi).

| Edge case | Comportamento |
|---|---|
| E-V9 | Nessun testo letto e lettura spenta qui: niente testo dal vivo, la pressione prolungata va al ritaglio |
| E-V10 | Nessun soggetto chiaro: niente, vibrazione leggera |
| E-V11 | Ritaglio dai Nascosti salvato: foto nuova nascosta |
| E-V12 | Foto in movimento: testo dal vivo o ritaglio come le altre foto; il video dal badge "Movimento" |
| E-V13 | Selezione aperta e gesti del viewer: §0.2 |
| E-M1–M4 | Foto da 200 MP o panorami: ritaglio su un'immagine ridotta a 4096 px; nessun errore di memoria |
| E-H1 | Il testo dal vivo e il ritaglio non mostrano mai nulla di un altro media; nei Nascosti la schermata resta protetta (`FLAG_SECURE`) |

### 2.11 Foto in movimento nel viewer (risposta Q14; P14)
**Il flusso** (D50, D51)
| Momento | Cosa | Dettagli |
|---|---|---|
| Riconoscimento | il catalogo sa che è una foto in movimento (`MediaKind.MOTION_PHOTO`); per la pagina corrente e le ±1 `MotionVideoLocator` trova **intervallo e istante della foto** | legge 256 KiB di testa e 128 KiB di coda del file (come oggi); per i solo-NAS con letture a intervalli |
| Pagina ferma | ~150 ms senza movimento del pager, pagina corrente | se si scorre via prima, niente animazione (E-V14) |
| Condizioni | interruttore "Animazione delle foto in movimento" acceso (Impostazioni della Libreria, acceso di default: Q16), niente "Rimuovi animazioni", niente risparmio energetico, proporzioni compatibili (≤ 3%), byte già disponibili (telefono; NAS secondo D51) | altrimenti foto ferma; il badge resta, anche con l'interruttore spento |
| Animazione | lettore condiviso, muto, sorgente tagliata a [istante − 1,2 s, istante], partenza dal fotogramma chiave precedente; il video sta nello stesso riquadro della foto, la foto sparisce solo al primo fotogramma (niente lampo nero, come oggi) e riappare con una dissolvenza di ~200 ms all'ultimo | costa circa un secondo di decodifica; nessuna attesa visibile |
| Interruzione | tocco, pizzico, doppio tocco, scorrimento, chiusura, uscita dall'app | `playback.clear()`, foto subito; i gesti valgono normalmente |
| Badge "Movimento" | tocco: video intero, una volta, con l'audio al volume dei contenuti multimediali (risposta Q15), poi la foto; anche nel ⋮ e come azione di TalkBack | solo-NAS sui dati mobili: conferma con la dimensione |
| Ripetizione | l'animazione si rifà arrivando da un'altra pagina; non chiudendo le info né tornando dallo zoom | — |
| Precarico | pagine ±1: solo i byte (intervallo, istante; per i solo-NAS la coda nella cache, solo sul Wi-Fi) | mai un secondo decoder: `ViewerPlayback.created` resta 1 |

**Dove sta l'istante della foto** (verificato nel codice di `core-metadata` e nella specifica Android, §10)
| Formato | Campo | Ripiego |
|---|---|---|
| Google Motion Photo (v1) | `Camera:MotionPhotoPresentationTimestampUs` nell'XMP: "il fotogramma del video che corrisponde alla foto"; −1 = non impostato | la specifica: "il fotogramma subito prima della metà del video" |
| MicroVideo (Google, formato vecchio) | `MicroVideoPresentationTimestampUs` (la specifica nuova lo toglie e dice di ignorarlo; serve solo per i file vecchi che hanno solo quello: `XmpFacts` lo legge già, dopo il campo nuovo) | metà del video |
| Trailer SEF di Samsung (`MotionPhoto_Data`, il formato dell'S25) | nel trailer nessun istante; l'XMP dei file Samsung può avere `MotionPhotoPresentationTimestampUs` (facoltativo): se c'è, vale quello | metà del video; P14 misura sui file dell'S25 dove cade la foto (confrontando alcuni fotogrammi con la foto) e, solo se i numeri lo mostrano, fissa una regola per il SEF (per esempio "fine del video") |
| HEIC di Samsung e formati non riconosciuti (E-M16) | — | niente animazione né badge; la nota "parte video non riproducibile" nelle info, come oggi |

**Accessibilità**: TalkBack legge "Foto in movimento"; il badge ha l'etichetta "Riproduci il movimento" e c'è l'azione equivalente
sull'immagine; l'animazione non si annuncia e non parte con "Rimuovi animazioni" né con l'interruttore spento; il badge da 48 dp.

**Costi**: un secondo di video decodificato per apertura, sul lettore che c'è già; per i solo-NAS al più 16 MB letti sul Wi-Fi,
una volta (cache); nessun costo sui dati mobili senza conferma.

## 3. Alternative scartate
| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Runtime | MediaPipe `UniversalEmbedder`; ML Kit per gli embedding; modello convertito per ONNX o TFLite; LiteRT-LM diretto | **LiteRT-LM** (D2): MediaPipe aggiunge ~23 MB e un vector store che non serve, senza `outputSize`; ML Kit non ha ancora gli embedding; una conversione nostra non sarebbe il modello misurato |
| Acceleratore | NPU Qualcomm (11 foto/s); GPU; CPU | **GPU per indicizzare, CPU per le query** (Fase 0, D3): i vettori della NPU danno solo il 47–65% degli stessi risultati, servono librerie Qualcomm con licenza propria |
| Modello delle query sugli indicizzatori | il 440M già presente; il 270M | **270M ovunque** (D3): stessi vettori di query su ogni dispositivo (E-Q8); il costo è 157 MiB in più sugli indicizzatori |
| Dimensione e precisione | 768 float32; 768 float16; 512 float16; 256 int8 pubblicati | **768 float16 pubblicati**, int8 a 256 solo come prima passata locale (D4, D24): niente da ricalcolare se domani serve meno o più dettaglio |
| Dove stanno vettori e testo sul NAS | classi nuove nella testa `index`; una testa `ai` separata; un file per media | **Testa `ai`** (D6): un'app vecchia rifiuterebbe una testa `index` con classi nuove (miniature comprese); un file per media vorrebbe dire decine di migliaia di file |
| Attestazione dei derivati | regola della 4A (stesso dispositivo); nessuna; record presente di qualunque dispositivo nella share | **Record presente nella share** (D7): l'indicizzatore non è chi ha preparato; senza attestazione chiunque potrebbe "vestire" contenuti di altre share |
| Coordinamento | per media; per unità con prenotazioni; nessuno | **Unità** (D9, F4-D13): già provato dalla preparazione; il lavoro doppio è solo inefficienza |
| Ingressi | originali; anteprime | **Anteprime e buffer** (D10): lo chiede il piano; gli originali costerebbero GB e notti |
| Video | un vettore per video (come Edge Gallery, 2 fotogrammi); uno per fotogramma; il 740M con la modalità video | **Uno per fotogramma** (D11): trova "il momento"; il 740M pesa 462 MiB e la sua API video non è documentata in Kotlin |
| OCR | ML Kit integrato con le statistiche bloccate; ML Kit dei Play Services; PaddleOCR con ONNX Runtime o LiteRT; ML Kit integrato come funzione da attivare | **ML Kit integrato, funzione da attivare, spenta di default** (D41, risposta Q4): l'utente accetta le statistiche così come sono se attiva la funzione, sapendo cosa esce; il blocco con una pratica non ufficiale è caduto; PaddleOCR resta un'alternativa possibile più avanti |
| Indice vettoriale | forza bruta float32 (300 MB per 100 mila); HNSW (libreria nativa, approssimato, memoria in più); `sqlite-vec` (estensione non caricabile nell'SQLite di sistema); vector store di MediaPipe; due passate | **Due passate** (D24): esatte quanto basta, nessuna libreria, 26 MB in RAM per 100 mila |
| Testo | FTS5 (non garantita nell'SQLite di Android, Room non la supporta); FTS4; `LIKE`; indice invertito nostro | **FTS4 con normalizzazione nostra e vocabolario** (D25) |
| Analizzatore | un modello linguistico on-device per capire la query; regole | **Regole** (D26): deterministiche e testabili, nessun modello da GB, nessuna latenza in più |
| Soglia | punteggio assoluto fisso; primi N; relativa alla distribuzione della query | **Relativa** (D27): i punteggi assoluti della Fase 0 erano piatti (0,65–0,70) |
| Ordine dei risultati | tutto per pertinenza; tutto per data; sicuri per data e meno probabili per pertinenza; due ordini a scelta con l'altro criterio che pesa poco | **Due ordini a scelta, "Recenti" predefinito** (D48, risposta Q10): domina il criterio scelto, l'altro corregge; nessun risultato poco pertinente sopra uno molto pertinente dello stesso periodo |
| Sorgente dei modelli | dentro l'APK (527 MiB, riscaricati a ogni aggiornamento); Hugging Face da ogni dispositivo; il NAS come distributore; solo dal PC; una copia su GitHub dell'utente | **NAS, poi Hugging Face, poi il PC** (D17, D18, Q1): un solo scaricamento da Internet per famiglia, sempre con l'hash |
| Libreria per scaricare | OkHttp (dipendenza nuova); `HttpsURLConnection` | **Piattaforma** (D17): nessuna libreria nuova per un GET con `Range` |
| Lavoro dello scaricamento | lavoro avviato dall'utente (UIDT); servizio in primo piano; WorkManager accelerato | **WorkManager accelerato** (D17): file da 10 s a 1 minuto, ripresa; nessun componente nuovo |
| Manifest remoto | subito; con la Fase 10; mai | **Con la Fase 10** (D19, Q2) |
| Calcolo in un processo separato | sì (`:ai`); no | **No per ora** (piano B al §7): servirebbe un componente nuovo; prima si misura il limitatore di Android 17 (T-23) |
| `engine-ai` dell'engine | usarlo; no | **No**: parla con servizi cloud (Groq, Gemini, OpenRouter), contro "niente cloud di terzi" |
| Cronologia | nei dati personali sincronizzati; solo locale | **Solo locale** (D32, piano §2) |
| Nascosti | non calcolati; calcolati e mai mostrati | **Calcolati** (Q6): nasconderli è una protezione dell'app (L2); toglierli dal calcolo ritarderebbe la ricerca quando si mostrano di nuovo |
| Miniature in Cerca | anche lette dal NAS; solo locali | **Solo locali** (D28): nessun traffico durante la ricerca |
| Filtro Documenti | un classificatore dedicato; regole su testo letto e frasi prototipo | **Regole** (D34): nessun modello in più |
| Testo dal vivo: da dove vengono le posizioni | ML Kit al volo su ogni dispositivo; posizioni pubblicate nell'indice | **Pubblicate** (D43): funziona anche dove la lettura è spenta e sui media solo-NAS; ~4 byte a parola; al volo solo dove la lettura è attiva |
| Copia del testo | "Copia testo" nelle info; testo dal vivo con le maniglie; foglio di testo | **Testo dal vivo, più il foglio come via accessibile** (D43, D46, risposta Q9): le info restano leggere; con TalkBack le maniglie non si usano, un foglio sì |
| Modello del ritaglio | MagicTouch; MobileSAM; U²-Net; BiRefNet; RMBG; ML Kit Subject Segmentation | **MagicTouch** (D44, §2.10): Apache 2.0, 6 MB, si sceglie il soggetto col dito; MobileSAM piano B; gli altri pesanti, senza scelta del soggetto, non commerciali o legati ai Play Services |
| Runtime del ritaglio | MediaPipe Tasks (`tasks-vision`); LiteRT; ONNX Runtime | **LiteRT** (D44): MediaPipe ha annunciato statistiche d'uso su tutte le piattaforme, senza spegnimento; ONNX Runtime serve solo al piano B |
| Pressione prolungata delle foto in movimento | video (come oggi); testo e ritaglio come le altre foto, video da un pulsante | **Testo e ritaglio, video dal badge "Movimento"** (D45, D51, risposta Q14): come le altre gallerie; un gesto solo per tutte le foto |
| Movimento all'apertura delle foto in movimento | nessuno; il video intero ogni volta; il breve tratto che precede la foto, senza audio | **Il breve tratto prima della foto** (D50, risposta Q14): è l'effetto delle altre gallerie, costa un secondo di decodifica e nessuna attesa |
| Spegnere l'animazione d'apertura | solo con "Rimuovi animazioni" e risparmio energetico; interruttore nelle Impostazioni della Libreria | **Interruttore, acceso di default e sincronizzato** (D50, risposta Q16 (b)): una scelta sola per tutti i dispositivi dell'utente |
| Audio del video dal badge | muto; con l'audio | **Con l'audio, al volume dei contenuti multimediali** (D51, risposta Q15 (a)): è un video che l'utente chiede; l'animazione d'apertura resta muta |
| Istante della foto senza metadati | metà del video (regola della specifica Android); fine del video; stima confrontando fotogrammi e foto | **Metà del video**, con una regola diversa per il formato SEF solo se le misure di P14 sui file dell'S25 la giustificano (D50) |
| Lettura del testo: ambito | per dispositivo; per account con esclusione di un dispositivo | **Per account con esclusione** (D41, risposta Q13 (a)): una scelta sola per la persona, ma ogni telefono può restare fuori |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
P0 fondamenta, contratti, dipendenze, documenti ──┬── P1 database v5 ───────────────────────────────┐
                                                   ├── P2 formato ai e indice condiviso ─────────────┤
                                                   ├── P3 modelli: catalogo, sorgenti, verifica ─────┼── M1 (integrazione minima)
                                                   ├── P4 motori on-device: vettori e OCR ───────────┤
                                                   ├── P5 calcolo AI ────────────────────────────────┤
                                                   ├── P6 motore di ricerca ─────────────────────────┤
                                                   ├── P7 analizzatore delle query ──────────────────┤
                                                   ├── P8 schermata Cerca e UI ──────────────────────┤
                                                   ├── P9 valutazione, calibrazione, benchmark ──────┤
                                                   ├── P10 prova delle interruzioni ─────────────────┤
                                                   ├── P12 testo dal vivo nel viewer ────────────────┤
                                                   ├── P14 foto in movimento nel viewer ─────────────┤
                                                   └── P13 ritaglio del soggetto ────────────────────┴── P11 integrazione ── M2 ── M3
                                                        ── check.ps1 (-Bench) + security-review + verifica funzionale ── (Q7) F64
```
- Dopo P0 tutti partono **insieme** sui contratti, sugli esecutori di riferimento (`InMemorySearchEngine`, `FakeAiCompute`) e sui fake
  fedeli (§5.2); si incontrano in P11.
- **M1** è un'integrazione parziale dell'orchestratore dopo P2, P3, P4 e P5: modelli veri, calcolo vero sull'area di prova, una ricerca
  minima (forza bruta) dall'S25.
- P9 parte con l'esecutore di riferimento e i fake; la calibrazione vera aspetta P6, P7 e il set privato dell'utente (Q8).
- P10 parte con l'esecutore di riferimento e passa a quello vero quando P3 e P5 consegnano.
- P12 parte con il testo letto finto (righe, inclinazione, parole) e passa a quello vero quando P2 e P4 consegnano; P13 parte con un
  ritagliatore finto e misura MagicTouch sui dispositivi appena P0 ha portato LiteRT. P12 scrive l'arbitro della pressione
  prolungata, P13 vi si aggancia (file condivisi, §1.5).
- **P14** (foto in movimento) non dipende dalla ricerca: parte subito dopo P0, in parallelo a tutto. Su `page/ViewerPage.kt` e
  `ViewerController.kt` lavora **prima** di P12 (toglie la riproduzione con la pressione lunga, aggiunge badge e animazione); P12 poi
  mette l'arbitro al posto di `holdToPlay`. L'orchestratore serializza i due su quei file.
- **Prima di M1**: l'S25 con la build debug e l'account di prova (rifatta con `test` il 20/10) e il consenso al
  primo scaricamento da Internet (Q1).

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Sull'S25 (debug, account di prova) i due modelli scaricati e verificati, l'area di prova indicizzata, "cane" (o un soggetto dell'area di prova) e una parola di uno scontrino trovano le foto giuste | F46, F50 (parziale); confronto dal PC delle teste `ai` pubblicate in `test` | P0, P1, P2, P3, P4, P5 (+ integrazione minima) |
| **M2** | Area di prova indicizzata **una volta sola** con S25 e Tab S9 (prova di E-I1); Cerca completa: chip, testo, filtri, i due ordini, "Trova simili", copertura, cronologia, Documenti; lettura del testo attivata sull'S25 con la pagina di consenso; testo dal vivo, ritaglio e foto in movimento (animazione d'apertura, badge, solo-NAS) nel viewer; il tablet non indicizzatore e con la lettura spenta cerca come il telefono e usa il testo letto dall'S25 | F47–F51, F57–F60, F65, F66, F68–F71 | + P6, P7, P8, P11, P12, P13, P14 |
| **M3** | Obiettivi del §4 del piano misurati: p95 < 300 ms con 100 mila elementi su S25 e Tab S9; ≥ 80% delle attese fra i primi 20 sulle 50 query italiane; precisione dei sicuri ≥ 90%; nessun traffico durante le ricerche | F53, F54, F55 | + P9, P10 |
| Chiusura | Revisione di sicurezza e verifica funzionale; checklist (§6.3); screenshot approvati; con il consenso (Q7) pubblicazione sulle share vere | `check.ps1`, `check.ps1 -Bench`, `security-review`, agente di verifica funzionale, F56, F61, F62, F63, F67; F64 con Q7 | — |

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–5)
- **Mai** toccare file fuori dal proprio perimetro. Dipendenze, manifest, file di build condivisi, `settings.gradle`,
  `verification-metadata.xml` e `check.ps1` li toccano solo P0 e P11.
- **Rete**: l'unico codice HTTPS sta in `core-models/.../download/https/`; ogni altro pacchetto e modulo ha `NoNetworkSourcesTest`;
  `core-search` e `feature-search` non vedono né rete né NAS.
- **Ingressi difesi**: mai byte letti dal NAS o da altre app al decodificatore nativo dei motori; `ImageBounds` e tetti prima di
  allocare; codec ostile per tutto ciò che arriva da una testa `ai`.
- **Ora**: orologio monotono per durate e attese; ora del NAS per prenotazioni e teste; la data del telefono solo dalla porta `Today`
  e solo per interpretare la query.
- **Log**: mai testo delle query, testo letto, nomi di luoghi, vettori, chiavi di contenuto, nomi di file, share, account; solo numeri
  e codici.
- **Nascosti**: ogni pacchetto che tocca una superficie (risultati, suggerimenti, completamenti, conteggi, "Trova simili", Documenti,
  testo dal vivo, ritaglio) ha il test "un nascosto perfetto non esce mai".
- **Lettura del testo (D41)**: nessun codice nomina `com.google.mlkit` fuori da `core-ai/.../ocr/`; ogni test che tocca l'OCR ha anche
  la variante "lettura spenta" con il lettore finto che fallisce se chiamato.
- **Viewer**: chi tocca gesti, menu o sovrapposizioni del viewer fa girare tutti i test del viewer esistenti (foto in movimento,
  zoom, pager, chiusura) prima di consegnare.
- **Ogni passo in più tappe è idempotente** e ha il test "guasto a ogni chiamata" (`FaultPlan` prima e dopo) e la morte del processo a
  ogni salvataggio (scaricamento, calcolo, pubblicazione).
- **F4-D31, F4-D33 (`ShareFence`) e la guardia delle prove dal PC (F4-D30)** valgono in ogni prova.
- **Fake fedeli con test di parità** (§5.2): un fake nuovo passa la stessa suite di contratto dell'implementazione vera.
- Test JVM o Robolectric per ogni modulo; test strumentati per i motori (emulatore e dispositivi); screenshot (solo dove c'è UI) in
  `docs/screenshots/6-<pacchetto>/`, chiaro e scuro, telefono e tablet, testo a 1,3x.
- Stringhe in italiano in file propri.
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti
  degli altri**.

### 4.4 I pacchetti
| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Campo |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Moduli, contratti, dipendenze, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`, `abiFilters`), `versions-app.gradle` (`litertlm-android` 0.18.1, `text-recognition` 16.0.1, `litert` 2.3.0, regola con le eccezioni di D49), `gradle/verification-metadata.xml`, `gradle/verify-release-manifest.gradle` (`uses-native-library`, `MlKitInitProvider` tolto, elenco chiuso dei componenti di ML Kit e DataTransport), `tools/check.ps1` (gli stessi controlli sul manifest fuso, `MlKit.initialize` solo nel cancello, HTTPS solo nel pacchetto ammesso); l'asset MagicTouch con la sua impronta (risposta Q11 (a)); creazione di `core-models`, `core-ai`, `core-search`, `feature-search` (build, manifest, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest`, `NoWallClockSourcesTest`); **contratti** del §1.2 con fake e null-object; esecutori di riferimento `InMemorySearchEngine` e `FakeAiCompute`; firme in `core-model` (`CellFlags.DOCUMENT`, codici), `core-library` (`OrderedTimelineView`, `LibraryFilter.DOCUMENTS`), `core-family` (`RolesSettings`), `core-personal` (`text_reading`, `search_order`, `motion_animation` con l'interfaccia `MotionPersonalSettings` in `contract/`), `core-prepare` (`AnalysisSink`), `core-common` (`DiagSection.SEARCH`, `Phase6ArchitectureTest`); porte del viewer per testo dal vivo e ritaglio; documenti del §0.3 | Fase 5 chiusa nel codice | `check.ps1` verde; APK debug e release con R8 e le regole `keep` delle tre librerie (allineamento a 16 KB delle librerie native controllato; `litert` e `litertlm-android` caricate insieme in un test strumentato); `-Xskip-metadata-version-check` solo in `core-ai`; scelta motivata su `kotlin-reflect` 2.4.0 e coroutines 1.11.0 con un test strumentato che inizializza il motore 270M (modello scaricato con il consenso, Q1); ogni test d'architettura fallisce reintroducendo la violazione (litertlm, litert o ML Kit fuori da `core-ai`; `com.google.mlkit` fuori da `core-ai/.../ocr/`; `MlKit.initialize` fuori dal cancello; `TextReadingConsent` costruita fuori dalla pagina di attivazione; `java.net` fuori dal pacchetto HTTPS; `core-search` che vede `core-models`, `core-index` o `core-nas`; `feature-viewer` che vede `core-ai` o `core-search`; chi legge il NAS che vede `core-ai`); `MlKitInitProvider` assente dal manifest fuso e componenti di ML Kit e DataTransport elencati per nome; con la lettura spenta ML Kit non si inizializza (test strumentato: nessuna classe di ML Kit caricata, nessun lavoro di DataTransport pianificato); `OrderedTimelineView` aperta nel viewer di prova | opus | no |
| **P1 Database v5** | Schema v5 (D37), operazioni del catalogo | `core-database/**`, `core-model/**` (codici, commenti corretti), `docs/modello-dati.md` §13 | P0 | `5.json` esportato; `ExportedSchemaTest` 1→5 … 4→5 con `seedV4`; `MergeReferencesTest` con le tabelle nuove fra quelle senza id di media; `cellContentKeys` su 100 mila media sotto 300 ms senza `USE TEMP B-TREE`; FTS4 sul testo normalizzato (Robolectric); `setDerivedFlags` a lotti (un cambiamento dell'indice della timeline per lotto); cronologia con il tetto di 50 | sonnet | no |
| **P2 Formato `ai` e indice condiviso** | §2.3, D6–D9, parte di D10 (scaricamento delle anteprime) | `core-trust/.../data/AiSegments.kt` (più le righe minime in `IndexSegment.kt`, `DeviceHead.kt`, `NasLayout.kt`, `TrustFormat.kt`), `core-index/.../ai/**`, `core-index/.../contract/Work.kt` (`WorkPass.AI`), l'aggancio delle teste `ai` in `IndexSyncEngine` | P0 (P1 per gli adattatori Room) | test ostili per ogni campo del §2.3 (NaN, infiniti, norma, spazio, ricetta, lunghezze, UTF-8 non valido, controllo e direzione, righe e corpi oltre i tetti, inclinazione fuori intervallo, parole che non tornano col testo o con estremi decrescenti, classi nella testa sbagliata); un lettore della versione 1 ignora `ai/`; attestazione (record di un altro dispositivo = valida; nessun record = in attesa; altra share = no; chiave intera, non i primi 64 bit); fusione deterministica; proprietà M3 (nessuna voce per contenuti di un'altra share); `FaultPlan` a ogni chiamata della pubblicazione; con F4-D31 i risultati restano locali e si pubblicano dopo **senza ricalcolo**; `AiPublishedClasses` riconosce il lavoro di un altro indicizzatore; `AnalysisFetcher` usa solo blob attestati e registra il traffico; 200 giri del banco | opus | sì (F50) |
| **P3 Modelli** | §2.1, D16–D20 | `core-models/**` | P0 | catalogo uguale al §2.1; un byte cambiato nel file = rifiutato (E-A2) e alla verifica di ogni apertura (E-A11); ripresa a ogni blocco da 1 MiB con un server HTTPS di prova del JDK (`com.sun.net.httpserver`, certificato di prova fidato solo nei test): `Range` e `If-Range`, `200` al posto di `206`, lunghezze false, redirezione verso un host non ammesso, `http`, file oltre la dimensione fissata; nessun cookie né parametro nelle richieste (il server di prova le ispeziona); sorgente NAS con `FaultPlan`; cartella dal PC; pubblicazione esclusiva sul NAS con le collisioni; spazio; rimozione; `pruneOwn` solo nella propria cartella; `NoNetworkSourcesTest` fuori da `download/https/` | sonnet (revisione opus mirata alla chiusura) | sì (F46–F49) |
| **P4 Motori on-device** | D2, D3, D5, D11 (ingressi), D12, D15 (misura), D41 (cancello di ML Kit) | `core-ai/.../runtime/**`, `core-ai/.../ocr/**` (motore OCR, `MlKitBootstrap`, pulizia allo spegnimento), `core-ai/src/androidTest/**` | P0 | suite di contratto comune a fake e motori veri (§5.2); strumentati su emulatore, S25 e Tab S9: vettori finiti e normalizzati, coseno GPU–CPU ≥ 0,99 su 20 immagini e 20 testi sintetici, vettori di riferimento salvati; ripiego su CPU; `ModelInputs` rifiuta JPEG e WebP ostili prima di decodificare; OCR sulle fixture sintetiche (errore per carattere a 10–40 px, a 1024 e a 2048: fissa la soglia del "testo fitto"); `DeviceProbe`; con la lettura spenta nessuna classe di ML Kit si carica e nulla si pianifica; allo spegnimento lavori di DataTransport annullati e coda tolta al riavvio (nomi letti dal manifest fuso e dalla libreria, scritti nel report); OCR con righe, inclinazione ed estremi delle parole; misure (foto/s, ms di OCR, PSS, avvio a freddo e a caldo, verifica dell'hash) in `docs/decisioni.md` | sonnet | sì (F52, F56, F69) |
| **P5 Calcolo AI** | §2.2, D9–D11, D14, D21, D22 | `core-ai/.../compute/**`, `core-prepare` (solo l'uso della porta `AnalysisSink`), `core-rules/.../estimate/**` (misure del calcolo) | P0 (fake di P2, P3, P4) | la tabella "una volta per tutta la famiglia" del §2.2 in test, scenario per scenario, con due dispositivi finti sul NAS finto; `FaultPlan` a ogni chiamata e morte del processo dopo ogni salvataggio: nessun media ricalcolato dopo la ripresa; `LOCAL_BUFFER_ONLY`; ritmo ridotto col caldo; mai un'altra area privata (test di proprietà); media del solo telefono; tetti del buffer e 1 GiB libero; risultati in attesa pubblicati quando la share si apre; ricetta nuova graduale (E-A10); OCR solo su un indicizzatore con la lettura attiva e "fatto" per classe (D9, D41: con la lettura spenta nessuna chiamata al motore OCR, il fake fallisce se chiamato); stime dal backlog vero | opus | sì (F50, F51) |
| **P6 Motore di ricerca** | §2.4, D23, D24, D27–D31, D35 | `core-search/.../engine/**`, `.../index/**`, `.../coverage/**`, `.../similar/**`, `.../history/**` | P0 (fake di P1, P4, P7) | parità: due passate contro forza bruta float32 su 100 mila vettori sintetici a gruppi, ≥ 99% dei primi 20 uguali; sulla JVM 100 mila candidati sotto 60 ms escluso il vettore della query; nascosti mai (test di proprietà su ogni percorso, compresi conteggi e simili); una cella per gruppo; ripiego sulla versione precedente (E-A10); copertura; soglie lette da una costante con il test; vista ordinata viva (E-Q13); file rovinato ricostruito | opus | sì (F53) |
| **P7 Analizzatore delle query** | §2.5, D26 | `core-search/.../parse/**` | P0 | tabella pubblica di almeno 300 query italiane con i chip attesi (tutte le righe del §2.5, Pasqua e Pasquetta 2015–2035 contro una tabella, stagioni, inverni a cavallo d'anno, date impossibili, refusi, luoghi di più parole, omonimi, preposizioni, virgolette, 200 caratteri); deterministico; meno di 2 ms a query sulla JVM | sonnet | no |
| **P8 Schermata Cerca e UI** | §1.5, §2.6, D15 (pagina e ruolo, Q3), D20, D30 (voce), D32, D34, D36, D41 (pagina di attivazione), D48 (selettore dell'ordine) | `feature-search/**`, `feature-settings/.../search/SearchEntry*`, `feature-viewer/.../menu/` (voce "Trova simili"), `feature-library/.../library/` (chip Documenti), `core-family/.../roles/**` (`RolesSettings`), `core-personal/.../settings/` (`text_reading`, `search_order`) | P0 (fake di P3, P5, P6) | ogni stato del §1.5 composto; pagina di attivazione con il testo del §2.6 (versione del testo salvata col consenso), esclusione di un dispositivo, spegnimento; selettore "Recenti · Pertinenti" ricordato e sincronizzato; ogni codice del §2.6 con messaggio e azione (test che legge l'elenco dal contratto); nessuna lettura del NAS in Cerca (caricatore di miniature finto: solo sorgenti locali); nascosti mai in suggerimenti, completamenti, recenti e luoghi; TalkBack, 1,3x, 48 dp; screenshot `6-P8` | sonnet | sì |
| **P9 Valutazione, calibrazione, benchmark** | §5.3, §5.4, calibrazione di D27 e D34 | `core-search/src/test/.../eval/**` (metriche), `app/src/debug/.../eval/**` (strumento di etichettatura e corsa, solo debug), `benchmark/.../SearchBenchmark.kt`, `benchmark/soglie.json` (scenario `ricerca`), `tools/fixtures/**` (generatore del set OCR sintetico) | P6, P7 (P4 sui dispositivi) | recall@5, @10, @20, MRR e precisione dei sicuri calcolati; set OCR sintetico con il resoconto dell'errore per carattere; strumento di etichettatura solo in debug (assente dal dex di release, `check.ps1`); **nessuna etichetta né query del set privato nel repository** (test su git); `Zc`, `Zl`, `Smin`, la soglia di Documenti e i parametri dell'ordine (`H`, `β`, §2.4) scelti e scritti in `docs/decisioni.md` con i numeri; benchmark p50 e p95 su S25 e Tab S9 registrati; guardia `-Bench` | sonnet | sì (F53, F54) |
| **P10 Prova delle interruzioni** | §5.5 | `core-ai/src/test/.../chaos/**`, `core-models/src/test/.../chaos/**`, `tools/chaos/**` (modo `ai` di `s25.ps1`), `docs/prova-500.md` (sezione della Fase 6) | P0 (esecutore di riferimento), poi P3, P5 | invarianti del §5.5; 2.000 giri JVM; 600 sul simulatore SMB (modelli e anteprime letti dal NAS con le vere `SmbNasFileOps`); ≥ 100 interruzioni sull'S25 durante l'indicizzazione; `ChaosMutationTest` (caricare prima di verificare, accettare un parziale, pubblicare prima di salvare, salvare dopo aver pubblicato: rossi con l'invariante giusto) | sonnet | sì (F61) |
| **P11 Integrazione** | §1.4 | `app/**` (`AiGraph`, `SearchGraph`, lavori, navigazione, `MainScaffold`, azzeramento, manifest, cancello della lettura del testo all'avvio, porte del viewer), `feature-diagnostics/**`, `README.md`, `docs/sicurezza.md` (checklist), `docs/decisioni.md` | P0–P10, P12, P13, P14 | test d'integrazione sul NAS finto, sul telefono finto e con i motori finti: foto caricata → preparata → anteprima nel buffer → vettore e testo → pubblicati in una share di prova → un secondo dispositivo li riceve → la ricerca la trova su entrambi; tablet non indicizzatore con gli stessi risultati (E-Q8); share vera con F4-D31 → solo locale e in attesa; un dispositivo con la lettura spenta cerca e seleziona il testo letto da un altro senza inizializzare ML Kit (D42); ritaglio salvato → riga del telefono → backup "Ritagli"; azzeramento completo; codici della Diagnostica nel catalogo; `check.ps1` verde; APK debug e release | sonnet | sì |
| **P12 Testo dal vivo nel viewer** | §2.10, D43, D45, D46 | `feature-viewer/.../livetext/**`, l'arbitro della pressione prolungata in `feature-viewer/.../gesture/` e il suo aggancio in `page/ViewerPage.kt`, il foglio "Testo nella foto", la voce ⋮ | P0 (porte, testo finto), poi P2 e P4; su `page/ViewerPage.kt` e `ViewerController.kt` dopo P14 | selezione su fixture con righe dritte, inclinate e su più righe; maniglie agganciate alle parole; copia con `EXTRA_IS_SENSITIVE` (test Robolectric sul `ClipData`); a ogni livello di zoom; nessuna chiamata all'OCR con la lettura spenta (porta finta che fallisce se chiamata); al volo solo con la lettura attiva; gesti: pager, trascinamento verso il basso, pizzico, doppio tocco, foto in movimento (E-V12, E-V13) senza regressioni sui test del viewer; TalkBack: azione "Testo nella foto" e foglio leggibile; 48 dp; screenshot `6-P12` | sonnet | sì (F65) |
| **P13 Ritaglio del soggetto** | §2.10, D44, D45, D47 | `core-ai/.../cutout/**` (MagicTouch su LiteRT, maschera, filtro guidato, PNG), `feature-viewer/.../cutout/**` (animazione, barra, "Contorno", voce ⋮), `core-mediastore` (cartella `Ritagli/` di `PhoneMediaWriter`), `core-backup` (sorgente "Ritagli") | P0 (LiteRT, asset, porte), P12 (arbitro) | misura su S25 e Tab S9 di MagicTouch v1 e v2 (latenza p50 e p95, PSS) e scelta scritta con l'impronta fissata; maschere sulle fixture sintetiche (forme e sagome su sfondi diversi: IoU sopra una soglia fissata prima di misurare); nessun soggetto = niente (E-V10); PNG con trasparenza e **senza metadati** (test che rilegge il file); salvataggio con `IS_PENDING`, collegamento al catalogo prima della lettura del telefono, nascosto dai Nascosti (E-V11), `mcg-prova/Ritagli` in debug; 200 MP senza errori di memoria; `NoNetworkSourcesTest`; screenshot `6-P13` | sonnet | sì (F66, F67) |
| **P14 Foto in movimento nel viewer** | §2.11, D45 (la parte del video), D50, D51 | `feature-viewer/.../motion/**` (badge "Movimento", dissolvenza, condizioni dell'animazione, `MotionState`), `feature-viewer/.../video/**` (`MotionPhotos.kt`: `MotionVideo.Found(range, stillUs)` e localizzatore anche per i solo-NAS; `ViewerPlayback.kt`: tratto tagliato e muto, video intero dal badge; `Media3ViewerPlayer.kt`: taglio della sorgente; coda dei solo-NAS in `cacheDir/motion/`), in `ViewerController.kt` e `page/ViewerPage.kt` solo la riproduzione delle foto in movimento (via `onMotionHold`/`onMotionRelease`, dentro badge e animazione), la voce ⋮ "Riproduci il movimento"; `core-personal/.../settings/SyncedMotionSettings.kt` (file nuovo: implementa `MotionPersonalSettings`, la chiave `motion_animation`; non tocca `SyncedPersonalSettings.kt`, di P8) e, in `feature-settings`, l'interruttore "Animazione delle foto in movimento" nella pagina delle Impostazioni della Libreria; `core-metadata` solo in lettura | P0 (`MotionPersonalSettings` e la chiave); prima di P12 sui due file condivisi | istante per formato sulle fixture di `core-metadata` (Google con il campo, senza, −1, oltre la durata; MicroVideo; SEF con e senza XMP; HEIC Samsung non riconosciuto): lo stesso del localizzatore vero; con il lettore finto: taglio [istante − 1,2 s, istante], muto, una partenza per ogni arrivo sulla pagina ferma, nessuna sulle pagine attraversate da uno scorrimento veloce (pager finto), tocco, pizzico e doppio tocco la fermano subito; "Rimuovi animazioni", risparmio energetico e interruttore "Animazione delle foto in movimento" spento = nessuna chiamata al lettore per l'animazione d'apertura (il badge funziona sempre, anche con l'interruttore spento); proporzioni oltre il 3% = niente; il video dal badge ha l'audio (traccia attiva, volume dei contenuti multimediali) e l'animazione d'apertura resta muta; voce `motion_animation` sincronizzata (un secondo dispositivo dello stesso account la vede, assente = acceso); la pressione lunga non chiama mai il lettore; `ViewerPlayback.created` resta 1 dopo 50 pagine e il precarico ±1 non crea decoder; solo-NAS: con `NasNetwork.OPEN` coda letta a intervalli e animazione solo se pronta entro 1,5 s (orologio finto), oltre 16 MB niente; con `METERED` nessuna lettura (contatore del NAS finto) e il badge chiede conferma (`NasVideoGate.NeedsMobileData`); `VPN_OFF` e `OFFLINE`: badge "non disponibile ora"; cache col tetto; traffico in `VISIBLE_CONTENT`; istante dei SEF misurato sulle foto in movimento dell'area di prova e scritto in `docs/decisioni.md` con i numeri (o "metà del video"); TalkBack: badge e azione; 48 dp; tutti i test del viewer verdi; screenshot `6-P14`, compreso l'interruttore nelle Impostazioni della Libreria (chiaro e scuro, telefono e tablet, testo a 1,3x) | sonnet | sì (F70, F71) |

> Nota sui crediti: opus per 4 pacchetti su 15 (P12 e P13, nati dalla risposta Q9, e P14, nato dalla risposta Q14, sono sonnet:
> interfaccia, un modello piccolo e il lettore che c'è già, con le prove sul campo F65–F67, F70 e F71), quelli fondanti o difficili: P0 (i contratti e i recinti: rete, librerie, nascosti),
> P2 (formato ostile e regole dell'indice condiviso, da cui dipende la fiducia fra dispositivi), P5 (il calcolo "una volta per tutta
> la famiglia", riprendibile), P6 (il motore che deve rispettare latenza, qualità e nascosti). P3 tocca la catena di fornitura ma è
> ben delimitato: sonnet con il brief completo e una revisione opus mirata nella `security-review`.

### 4.5 Chiusura della fase
- `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro le guardie delle Fasi 3–5 **e** dentro lo scenario `ricerca` nuovo;
  F46–F63 e F65–F71 superate (F64 con il consenso, Q7).
- `security-review` (opus) con revisione mirata di `core-models` (catena di fornitura), del codec `ai` di `core-trust` e degli ingressi
  di `core-ai`; **in parallelo un agente di verifica funzionale** dei flussi (scaricamento, calcolo, ricerca, nascosti; gesti del
  viewer e foto in movimento con la rete), come per la Fase 5.
- Checklist della Fase 6 spuntata (§6.3); `docs/sicurezza.md` aggiornato; screenshot di Cerca e di Ricerca e modelli approvati;
  risposte del §9 registrate.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-search`, `core-ai/compute`, `core-models`, codec di `core-trust` | analizzatore, indice, fusione, soglie, calcolo, scaricamento, codec ostile |
| NAS finto | `InMemoryNasMedia`, `InMemoryRemoteIndex` con le teste `ai` | guasti prima e dopo, prenotazioni, più dispositivi, revoche |
| Simulatore SMB | `InMemorySmbBackend` con le vere `SmbNasFileOps` | modelli e anteprime letti a intervalli, pubblicazione esclusiva nella cartella del dispositivo |
| Server HTTPS di prova | `com.sun.net.httpserver.HttpsServer` del JDK, certificato di prova fidato solo nei test | `Range`, `If-Range`, redirezioni, lunghezze false, interruzioni |
| Motori finti | `FakeTextEmbedder`, `FakeImageEmbedder`, `FakeTextReader`, `FakeSubjectCutter` (§5.2) | contenuto, testo e maschere controllati, latenze e guasti; il lettore finto **fallisce se chiamato** quando la lettura è spenta |
| Robolectric (SDK 35) | Room v5 con FTS4, Compose, `ImageDecoder` dove serve | |
| Roborazzi | `feature-search`, pagina Ricerca e modelli, chip Documenti | chiaro e scuro, telefono e tablet, testo a 1,3x |
| Strumentati | motori veri su emulatore x86_64 (CPU), S25 e Tab S9 (GPU) | contratto, vettori di riferimento, OCR delle fixture, misure |
| Benchmark | `SearchBenchmark` su S25 e Tab S9 | latenza p50 e p95, memoria (§5.4) |
| Dispositivi | S25, Tab S9 | §5.6 |

### 5.2 Fake fedeli e test di parità
| Fake | Fedeltà | Parità |
|---|---|---|
| `FakeTextEmbedder` | 768 dimensioni, norma 1, float16 andata e ritorno; "concetti" da una tabella di sinonimi italiani (cane, cani, cagnolino → lo stesso concetto) proiettati in modo deterministico; latenza e guasti iniettabili (avvio fallito, memoria, valori non finiti); una richiesta alla volta come il motore vero | la stessa suite di contratto (`EmbedderContract`: dimensione, norma ± 1e-3, determinismo con coseno ≥ 0,999, chiamate serializzate, `warmUp`/`release` idempotenti, errori mappati) gira sul fake (JVM) e sul motore vero (strumentato) |
| `FakeImageEmbedder` | immagini sintetiche delle fixture con i loro concetti in una tabella per chiave di contenuto; fotogrammi; ripiego GPU → CPU simulato | come sopra; in più i **vettori di riferimento** del motore vero su immagini e testi sintetici (`core-ai/src/androidTest/assets/golden/`, nessun dato personale): coseno ≥ 0,99 a ogni aggiornamento della libreria |
| `FakeTextReader` | righe e riquadri dalle fixture del generatore OCR; "testo fitto" con altezze sotto 16 px | lo stesso testo atteso lo legge ML Kit vero sulle stesse fixture (errore per carattere entro la soglia di P4) |
| `InMemorySearchEngine` (riferimento) | forza bruta float32, stesse regole di fusione e d'ordine (D48) | su 1.000 query con i motori finti il motore vero dà la stessa classe (sicuro, meno probabile, fuori) per ogni cella, salvo i casi a ± 1e-4 dalla soglia, e lo stesso ordine nei due criteri |
| `FakeTextReader` con le posizioni | righe con riquadro, inclinazione ed estremi delle parole dalle fixture; stato del cancello (spenta = eccezione) | lo stesso testo e le stesse posizioni (entro 2% della dimensione dell'immagine) li dà ML Kit vero sulle fixture |
| `FakeSubjectCutter` | maschere disegnate dalle fixture sintetiche, "nessun soggetto", latenza | MagicTouch vero sulle stesse fixture: IoU sopra la soglia di P13 |
| `FakeMotionVideoLocator` e lettore finto | intervalli **e istanti** scelti dal test; il lettore finto registra taglio, silenzio, posizione di partenza e quanti lettori esistono | le fixture di `core-metadata` (Google con e senza il campo, −1, MicroVideo, SEF) danno gli stessi istanti con il localizzatore vero |
| NAS finto e simulatore | come nelle Fasi 4 e 5 | invariati |

### 5.3 Set di valutazione: 50 query italiane senza dati personali nel repository
**Il problema**: la qualità della ricerca per contenuto si misura solo sulle foto vere della famiglia, e né le foto né le query
("Firenze estate 2023") devono finire nel repository.

**La proposta: due livelli**
1. **Pubblico, nel repository** (deterministico, a ogni `check.ps1`):
   - **analizzatore**: almeno 300 query italiane con i chip attesi (P7); i nomi di luogo sono nomi pubblici;
   - **testo**: un **set OCR sintetico** generato da `tools/fixtures/` (P9): scontrini di negozi inventati, chat e schermate finte,
     cartelli con accenti, apostrofi ed euro, testo da 10 a 40 px, ruotato, sfocato, con artefatti JPEG; misura l'errore per
     carattere a 1024 e a 2048 px e la ricerca per parola;
   - **ricerca**: i motori finti (§5.2) provano la pipeline (filtri, fusione, soglie, nascosti, gruppi), non la qualità del modello.
2. **Privato, fuori dal repository** (la misura che conta per il §4 del piano):
   - l'utente scrive **50 query** sulle sue foto, divise in otto categorie (oggetti e animali, scene, eventi e feste, testo nelle
     foto, colori e aspetto, luoghi e date, video, screenshot e documenti);
   - le **etichetta** con uno strumento **solo debug** (Diagnostica → Sviluppo → "Valutazione della ricerca", P9): per ogni query si
     segnano le foto attese **prima** sfogliando la Libreria per data e luogo, **poi** aggiungendo dai primi 100 risultati di due
     configurazioni diverse (metodo "a pozzo", per non premiare il motore che si sta misurando); un gruppo conta una volta (la cella);
   - le etichette sono **chiavi di contenuto**, salvate sul telefono in `noBackupFilesDir/eval/` (fuori dai backup) ed esportabili con
     `adb` in `%USERPROFILE%\.mycloudgallery\eval\` (come il file delle credenziali di prova); **mai nel repository** (`.gitignore`, test
     di P9 su git, `check.ps1`);
   - la corsa calcola recall@5, @10, @20, MRR e la precisione dei sicuri, in totale e per categoria; esporta **solo numeri**, che vanno
     in `docs/decisioni.md`;
   - si ripete a ogni cambio di ricetta, di modello o di soglie: un calo di 3 punti di recall@20 blocca il cambio.
3. **Facoltativo**: un piccolo set di foto libere da diritti scelte dall'utente, da scaricare solo con il suo consenso, per una prova
   di qualità ripetibile anche in CI. Non serve agli obiettivi.

**Obiettivi**: recall@20 ≥ 80% (piano §4); precisione dei sicuri ≥ 90% (D27). Lo stesso set tara i parametri dell'ordine "Pertinenti"
(`H`, `β`, §2.4): il premio di recenza non deve togliere più di 1 punto di recall@20.

### 5.4 Benchmark di latenza
- **`SearchBenchmark`** (`:benchmark`, Macrobenchmark come le guardie delle Fasi 3–5): la Libreria sintetica da **100 mila** elementi
  (quella delle guardie), con vettori sintetici a gruppi (768 float16 e prima passata) e testo sintetico sul 10%; il motore 270M
  **vero** (modello già sul dispositivo); 50 query del set pubblico; misura dall'invio al primo fotogramma dei risultati (sezione
  `search.firstResults`), **a caldo** e, a parte, la **prima query a freddo**; memoria (PSS) con Cerca aperta.
- **Soglie** in `benchmark/soglie.json`, scenario `ricerca`: p95 a caldo ≤ 300 ms (assoluta), regressione +15%; primo risultato a
  freddo ≤ 4 s per contenuto e ≤ 300 ms per filtri e testo; guardia `check.ps1 -Bench` su S25 e Tab S9.
- Le guardie esistenti (scorrimento, apertura del viewer, avvio) non peggiorano con l'indice di ricerca in memoria e con il calcolo AI
  in corso (prova a occhio e con il profilo dei fotogrammi in F52).

### 5.5 La prova delle interruzioni (scaricamento e calcolo, P10)
**Invarianti** (dopo ogni morte, a fine passo e in una rilettura finale)
1. **Nessun modello non verificato caricato**: ogni caricamento è preceduto da una verifica riuscita sullo stesso descrittore.
2. **Nessun parziale promosso**: in `models/` stanno solo file con dimensione e SHA-256 del catalogo.
3. **Ripresa vera**: un'interruzione costa al più 1 MiB riscaricato (salvo un `200` del server o un hash sbagliato).
4. **Nessun calcolo perso**: un media salvato prima della morte non si ricalcola.
5. **Dati coerenti**: ogni riga di `ai_vector` ha i suoi byte nel file dei vettori con il CRC giusto; ogni voce pubblicata ha la sua
   riga locale; ogni voce in attesa ha il timbro con cui è nata.
6. **Nessuna voce fuori posto**: nessuna voce per contenuti di un'altra share (M3), niente scritto fuori da `devices/<io>/ai/` e
   `devices/<io>/models/` e dalle prenotazioni.
7. **Prenotazioni**: rilasciate o scadute entro l'ora; mai la stessa unità prenotata da due dispositivi insieme (salvo `Contended`).
8. **Il lavoro arriva in fondo**: dopo le riprese ogni contenuto in ambito ha `vec` e `ocr` della ricetta corrente o è "non
   supportato"; nessun errore permanente per un guasto passeggero.
9. **Buffer** sotto il tetto; nessun temporaneo orfano a lavoro finito.
10. **Nascosti**: nessuno nei risultati di una ricerca fatta a fine giro.

**Interruzioni**
| Dove | Come | Quante |
|---|---|---|
| JVM, NAS finto, server HTTPS di prova, motori finti | `FaultPlan` (rete prima e dopo, sessione scaduta, stallo), morte del processo a ogni salvataggio, server che risponde `200` invece di `206`, file del modello cambiato sul NAS a metà, unità presa da un altro dispositivo a metà | 2.000 giri a ogni `check.ps1` |
| JVM, simulatore SMB | come sopra con le vere `SmbNasFileOps` | 600 giri |
| Telefono, area di prova | `s25.ps1 -Mode ai`: `kill -9` e Wi-Fi spento per 3–20 s a tempi casuali durante scaricamento e indicizzazione; poi il verificatore | ≥ 100 |

### 5.6 Verifica sul campo
**A. Preparazione (utente, circa un'ora, più l'etichettatura di Q8)**
1. S25 con la build debug e l'account `test` (configurazione rifatta il 20/10, area comune `test-comune`); Tab S9 con il profilo "Prova" e `test2`.
2. Nell'area di prova alcune foto **non personali** ma con soggetti riconoscibili: oggetti di casa, uno scontrino di un negozio, una
   schermata, un cartello, un video breve, due foto con il GPS (seminate con lo script di P6 della Fase 5 e caricate dal backup di
   prova).
3. Il consenso al primo scaricamento da Internet (Q1, già dato) e lo spazio sui due dispositivi (~530 MB per un indicizzatore).
4. PCAPdroid sull'S25 e sul Tab S9 per le catture (usa una VPN locale: con WireGuard spento, cioè a casa).
5. Per F56, F65, F69: la lettura del testo **attivata dall'utente** sull'S25 di prova dopo la pagina di consenso (sul tablet resta
   spenta); per F66: due o tre foto con un soggetto chiaro (un oggetto, una pianta o un animale) e una senza soggetto (cielo, muro);
   per F70 e F71: cinque o sei foto in movimento non personali scattate con l'S25 (con e senza testo), in `mcg-prova` e, caricate dal
   backup di prova, anche come solo-NAS sul tablet.
6. Per F54: le 50 query etichettate dall'utente con lo strumento dell'app (Q8).
7. Per F64: l'APK di release con l'account vero e il consenso (Q7).

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F46 | Scaricamento del 270M da Hugging Face sul Wi-Fi di casa; Wi-Fi spento al 40% e riacceso | ripresa dal parziale (byte riscaricati ≤ 1 MiB, contatore in Diagnostica); hash giusto; "Pronto" con dimensione e sorgente; traffico solo verso host ammessi |
| F47 | Modello alterato: un byte cambiato nel file locale (debug, `run-as`); poi una copia falsa con il nome giusto nella cartella del dispositivo sul NAS (dal PC, area di prova) | rifiutato al caricamento e ripreso; copia falsa scartata (`AI_MODEL_MISMATCH`), sorgente dopo |
| F48 | Il tablet (profilo Prova) prende i modelli dal NAS | nessuna connessione a Internet dal tablet (cattura); ~10 s per il 270M |
| F49 | "Dal PC": modello copiato a mano in `test-comune/MyCloud Gallery/Modelli/` | preso e verificato; un file della dimensione giusta con un byte diverso ignorato |
| F50 | Area di prova indicizzata con S25 e Tab S9 indicizzatori insieme | ogni contenuto calcolato una volta (i conteggi per dispositivo sommano al totale); pubblicato in `test` e `test-comune`; l'altro dispositivo lo riceve |
| F51 | E-A6: notte senza NAS (NAS irraggiungibile con il buffer pieno) | solo il buffer, poi "Fatto ciò che c'era in locale"; col NAS si riprende |
| F52 | Misure del calcolo su S25 e Tab S9 (500 media dell'area di prova, in carica e a batteria) | foto/s p50 e p95, calo col caldo, batteria ogni 1000, PSS, ms di OCR; stima della pagina entro ±30%; Libreria fluida durante il calcolo |
| F53 | Latenza: `SearchBenchmark` con 100 mila elementi su S25 e Tab S9; 20 query sull'area di prova | p95 < 300 ms a caldo; primo risultato a freddo registrato |
| F54 | Qualità: le 50 query del set privato (Q8) | ≥ 80% delle attese fra i primi 20; precisione dei sicuri ≥ 90%; numeri in `docs/decisioni.md` |
| F55 | Nessun traffico durante le ricerche: cattura di 5 minuti di ricerche (contenuto, testo, filtri, simili) con il NAS raggiungibile; poi in modalità aereo | nessun pacchetto dell'app (né verso Internet né verso il NAS) mentre si cerca; in modalità aereo tutto funziona |
| F56 | Traffico di ML Kit (D41): (a) installazione nuova con la lettura spenta, un'ora d'uso e d'indicizzazione; (b) lettura attivata, un'ora di indicizzazione; (c) lettura spenta di nuovo e telefono riavviato, un'ora | (a) e (c): nessun pacchetto verso host di Google, nessuna classe di ML Kit caricata, nessun lavoro di DataTransport pianificato (`dumpsys jobscheduler`); (b): l'elenco degli host e della frequenza degli invii, confrontato con la pagina di attivazione (se dice di meno, la pagina si corregge e la versione del testo sale) |
| F57 | Le query del piano sull'area di prova: un soggetto in spiaggia o simile, lo scontrino con il nome del negozio, "<luogo> estate <anno>" delle foto con il GPS, "video di Natale" (un video datato a dicembre), "screenshot di ieri", più due refusi | chip giusti; le foto attese fra i sicuri o i meno probabili; refusi corretti e detti |
| F58 | Nascosti: una foto con un testo e un soggetto unici, nascosta | non esce in nessuna ricerca, suggerimento, luogo proposto, conteggio, "Trova simili", né sul telefono né sul tablet dello stesso account |
| F59 | E-Q8: 20 query uguali su S25 e sul tablet non indicizzatore | stessi sicuri, stessa copertura |
| F60 | E-I12: ruolo Indicizzatore spento su entrambi | avviso in Cerca e nella pagina (subito sul dispositivo, al più un giorno sugli altri) |
| F61 | Prova delle interruzioni sul telefono (≥ 100) | invarianti del §5.5 |
| F62 | T-23: limitatore di memoria di Android 17 attivato in prova durante il calcolo | chiusura registrata (`AI_KILLED_MEMORY`), ripresa senza ricalcolo |
| F63 | APK di release: scaricamento, calcolo, ricerca e OCR con R8 | tutto funziona; dex senza DataTransport; `check.ps1` verde |
| F64 | (con il consenso, Q7) F4-D31 spento: pubblicazione sulle share vere con l'APK di release | i risultati locali in attesa si pubblicano senza ricalcolo; il tablet li riceve; dal PC nulla fuori da `.mycloudgallery/devices/<id>/ai/` e `models/` |
| F65 | Testo dal vivo (D43): uno screenshot e uno scontrino dell'area di prova, sul telefono (lettura attiva) e sul tablet (lettura spenta, testo letto dall'S25); una foto solo-NAS; zoom a 3x; una foto in movimento con del testo; TalkBack | selezione con le maniglie su una e su più righe; "Copia" incolla il testo giusto in un'altra app e gli appunti non mostrano l'anteprima; sul tablet nessuna classe di ML Kit caricata; pager, chiusura e pizzico senza sorprese; con TalkBack l'azione "Testo nella foto" apre il foglio leggibile |
| F66 | Ritaglio del soggetto (D44, D47) su S25 e Tab S9: tre soggetti e una foto senza soggetto; "Copia" in un'altra app, "Condividi" a WhatsApp, "Salva come nuova foto"; da un media nascosto | latenza p50 e p95 sotto 1 s; bordi accettabili a occhio; nessun ritaglio dove non c'è soggetto; PNG trasparente senza posizione (`exiftool` dal PC); la foto nuova in `Pictures/mcg-prova/Ritagli/` e poi sul NAS di prova dal backup; quella dai Nascosti nasce nascosta |
| F67 | Nessun traffico durante il ritaglio e il testo dal vivo: cattura di 10 minuti d'uso (runtime LiteRT) | nessun pacchetto dell'app verso Internet; se LiteRT manda qualcosa → piano B del ritaglio (§2.10, §7) |
| F68 | Ordine dei risultati (D48): 10 query in "Recenti" e in "Pertinenti"; scelta cambiata sul telefono | in "Pertinenti" a pertinenza simile sopra il più recente, uno molto pertinente di anni fa sopra uno poco pertinente di ieri; in "Recenti" per periodo e, dentro, per pertinenza; la scelta si ritrova sul tablet dello stesso account dopo la sincronizzazione dei dati personali |
| F69 | Attivazione e spegnimento della lettura del testo (D41, E-A18, E-A19): attivazione dall'S25; l'altro dispositivo dello stesso account mostra l'avviso e si esclude; spegnimento | pagina con il testo approvato; avviso una volta sola sull'altro dispositivo; escluso = ML Kit mai inizializzato lì; dopo lo spegnimento e il riavvio ML Kit non riparte e la coda di DataTransport è vuota (`TEXT_READING_OFF_CLEANUP` in Diagnostica) |
| F70 | Foto in movimento del telefono (D50, D51, E-V14): apertura dalla griglia e arrivo scorrendo; scorrimento veloce fra dieci foto in movimento; tocco e pizzico durante l'animazione; "Rimuovi animazioni", risparmio energetico e interruttore spento (e la scelta ritrovata sul tablet dello stesso account); badge (con l'audio) e ⋮; pressione lunga; TalkBack | animazione di ~1–1,5 s che si ferma sulla foto senza salti d'inquadratura; nessuna durante lo scorrimento veloce; il tocco la ferma subito; niente animazione con le tre impostazioni, ma il badge funziona; il badge riproduce il video intero con l'audio e torna alla foto; la pressione lunga dà testo dal vivo o ritaglio, mai il video; un solo lettore (contatore della build debug) |
| F71 | Foto in movimento solo-NAS (E-V15) sul tablet: a casa, sull'hotspot del telefono (dati mobili), con il NAS spento | a casa animazione se la coda arriva in tempo, altrimenti foto ferma senza attesa; sui dati mobili niente animazione e nessun traffico (contatore), il badge chiede conferma con la dimensione; NAS spento: foto ferma, badge "non disponibile ora"; un secondo arrivo usa la cache |

**I "Fatto quando" del piano, prova per prova**
| Sottofase | Fatto quando (piano) | Dove si prova |
|---|---|---|
| 6.1 | Un file del modello alterato di un byte viene rifiutato e uno scaricamento interrotto riprende | P3 (test), P10, F46, F47 |
| 6.2 | L'archivio di prova è indicizzato una volta sola anche con due indicizzatori; una notte senza NAS elabora solo il buffer e si ferma con lo stato corretto | P2, P5 (test), F50, F51 |
| 6.3 | Soglie di qualità e latenza del §4 del piano | P6, P9, F53, F54 |
| 6.4 | Screenshot approvati; nessun traffico di rete durante le ricerche | P8, F55 |
| Risposta Q9 (21/10) | Testo dal vivo e ritaglio come su Samsung e Apple, senza Play Services | P12, P13, F65, F66, F67 |
| Risposta Q14 (21/10) | La pressione lunga sulle foto in movimento diventa testo o ritaglio; il video dal badge; animazione d'apertura fino alla foto | P12, P14, F70, F71 |
| Risposta Q4 (21/10) | Lettura del testo spenta di default; spenta, ML Kit non parte né manda nulla | P0, P4, F56, F69 |

### 5.7 Verifica funzionale di fine fase
Un agente di verifica funzionale (come `docs/review/funzionale-5b.md`), in parallelo alla `security-review`, rilegge i percorsi interi
contro questo documento e scrive `docs/review/funzionale-6.md` con voci "percorso, atteso, trovato, stato":
- scaricamento (ogni sorgente, ripresa, verifica a ogni caricamento, spazio, rimozione, copia sul NAS, profili del tablet);
- ruolo Indicizzatore (accensione, misura, stima, spegnimento a metà unità, stato firmato ripubblicato; onboarding e Impostazioni, Q3);
- lettura del testo (pagina di attivazione, esclusione di un dispositivo, spegnimento, nessuna inizializzazione di ML Kit da spenta,
  testo degli altri usato da spenta);
- viewer (testo dal vivo, ritaglio, gesti e precedenze, accessibilità, salvataggio e backup dei ritagli, Nascosti);
- ordine dei risultati (due criteri, ricordo sincronizzato);
- foto in movimento (istante della foto per formato, animazione d'apertura e sue condizioni, badge, solo-NAS e rete, un solo lettore);
- calcolo (unità, buffer, `LOCAL_BUFFER_ONLY`, caldo, ripresa, pubblicazione, attesa sotto F4-D31 e rilascio);
- replica (attestazione, fusione, revoca, area persa);
- ricerca (analizzatore, candidati, soglie, "Mostra anche", gruppi, ordine, viewer, cronologia, copertura, "Trova simili", Documenti);
- nascosti su ogni superficie; azzeramento; Diagnostica.
Le voci confermate si correggono prima della chiusura (come le correzioni della 5B).

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 6
| Minaccia | Contromisura | Prova |
|---|---|---|
| Modello manomesso (host, CDN, NAS, copia locale) | dimensione e SHA-256 fissati nell'app, verifica a **ogni** caricamento sullo stesso descrittore; nessun manifest che possa cambiare l'hash (D16, D19) | P3, F46, F47 |
| Host dei modelli che redirige o traccia | host ammessi, redirezioni controllate, nessun cookie, parametro o identificativo; un solo scaricamento per famiglia (D17, D18) | P3, F46, F48 |
| Statistiche di ML Kit verso Google | **eccezione dichiarata e scelta** (risposta Q4): lettura del testo spenta di default; pagina che dice cosa esce; spenta = `MlKitInitProvider` tolto, `MlKit.initialize` mai chiamato, nessuna classe di ML Kit toccata; allo spegnimento lavori di DataTransport annullati e coda tolta; esclusione per dispositivo (D41) | P0, P4, F56, F69 |
| Un dispositivo con la lettura spenta che fa girare ML Kit lo stesso (per il testo dal vivo, per errore) | il testo dal vivo usa il testo pubblicato; l'OCR al volo passa dal cancello; il lettore finto dei test fallisce se chiamato da spento (D42, D43) | P5, P12, F65 |
| Runtime nuovo del ritaglio (LiteRT) che parla con Internet | nessuna telemetria nota; MediaPipe Tasks escluso perché la annuncia; cattura F67; piano B ONNX Runtime con MobileSAM (D44) | P13, F67 |
| Testo o ritaglio copiati negli appunti, letti da altre app | appunti segnati come sensibili; nessun testo copiato nei log; il ritaglio non porta posizione né metadati (D43, D47) | P12, P13; L53 |
| Un ritaglio da una foto nascosta che finisce in Libreria | la foto nuova nasce nascosta; nei Nascosti la schermata resta protetta (D47, E-V11) | P13, F66 |
| Animazione di una foto in movimento solo-NAS sui dati mobili | mai senza conferma; solo Wi-Fi, tetto di 16 MB, cache tolta dall'azzeramento, traffico registrato (D51) | P14, F71 |
| Byte ostili ai decodificatori nativi (`stb_image` di LiteRT-LM, ML Kit) | ingressi sempre preparati da noi: `ImageBounds`, `ImageDecoder`, ricodifica (D5) | P4 |
| Segmenti `ai` ostili (valori non finiti, norme, lunghezze, testo enorme, caratteri di controllo e di direzione) | codec ostile con tetti, segmento rifiutato intero, contatori (§2.3, E-I20) | P2 |
| Dispositivo approvato compromesso che pubblica vettori o testo falsi | attestazione per share e chiave intera, fusione deterministica, disaccordi contati, revoca = dati ignorati (D7) | P2; L45 |
| Testo letto che espone documenti, indirizzi, codici | resta nella share del file (C19); mai nei log né nella Diagnostica; nessun completamento dal testo letto (D13, D40, §2.6) | P2, P8; L46 |
| Query che lasciano il dispositivo | nessuna rete nei moduli della ricerca (test d'architettura), miniature solo locali (D28, D33) | P0, P6, F55 |
| Cronologia letta da chi ha il telefono | solo locale, cancellabile, senza miniature, tolta dall'azzeramento; blocco dell'app (D32) | P8; L48 |
| Nascosti che trapelano dalla ricerca | tre barriere e test su ogni superficie (D29) | P6, P8, F58 |
| Sintassi FTS iniettata dalla query | parole sempre letterali, query troncata (D25, E-Q9) | P6, P7 |
| Memoria o spazio esauriti (modelli, buffer, motore) | tetti, 1 GiB libero, rilascio del motore, limitatore registrato (D10, D35) | P3, P5, F62 |
| Codice nativo nuovo di terzi | versioni fissate con checksum, solo in `core-ai`; processo separato come piano B (§7) | P0; L47 |
| Primo traffico Internet dell'app | un modulo, uno scopo, host ammessi, mai in roaming, solo su richiesta o secondo le regole (D17, D33); ML Kit a parte, solo se attivato (D41) | P0, P3 |

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L45. Un dispositivo approvato compromesso può pubblicare vettori o testo falsi** per i file delle share in cui scrive (come L27 per
  le miniature): può far trovare una foto con parole sbagliate o toglierla dalla ricerca per contenuto; non può far vedere file di
  altre share né toccare file. La revoca lo toglie.
- **L46. Il testo letto nelle foto sta nell'indice della share, non cifrato** (come L26): chi legge la share legge anche il testo di
  scontrini, documenti e screenshot che contiene.
- **L47. Due librerie native di Google** (LiteRT-LM, ML Kit) girano nel processo dell'app: versioni fissate e verificate, ma il loro
  codice non lo rivediamo.
- **L48. La cronologia delle ricerche** sta in chiaro nel database dell'app: chi ha il telefono sbloccato con l'app aperta (senza
  blocco dell'app) la vede.
- **L49. Con la lettura del testo attiva, ML Kit manda a Google statistiche tecniche** (marca e modello, versione di Android e
  dell'app, un codice dell'installazione, tempi, errori; mai foto né testo): è l'eccezione alla "nessuna telemetria", spenta di
  default e scelta dall'utente (risposta Q4). Spenta, ML Kit non si avvia: lo garantiscono il manifest e il codice dell'app, non
  un'opzione di Google; un aggiornamento della libreria può aggiungere un componente che si avvia da solo (il controllo di
  `check.ps1` lo scopre alla build, la cattura F56 lo verifica).
- **L50. La ricerca per contenuto sbaglia**: "sicuro" vuol dire giusto almeno 9 volte su 10 sul nostro set, non certo.
- **L51. Il primo scaricamento dei modelli** dice a Hugging Face (e al suo CDN) l'indirizzo IP di casa e quale file si scarica;
  nient'altro.
- **L52. L'hash garantisce che il modello è il file fissato**, non che il modello sia senza difetti o pregiudizi: pesi e licenza
  sono di Google.
- **L53. Il testo e i ritagli copiati restano negli appunti** finché non si copia altro: un'app con accesso agli appunti li può
  leggere. Sono segnati come sensibili (da Android 13 niente anteprima), ma la protezione è di Android, non dell'app.
- **L54. Il ritaglio non è perfetto**: bordi e dettagli sottili (dita, capelli) possono mancare o restare in più (scheda di
  MagicTouch). Una persona ritagliata è una foto nuova da condividere con il suo permesso.
- **L55. LiteRT** (il runtime del ritaglio) non dichiara telemetria e le catture non ne trovano, ma non c'è un'opzione ufficiale
  che lo garantisca per le versioni future: la cattura si ripete a ogni aggiornamento della libreria.
- **L56. Nelle foto in movimento di Samsung l'istante della foto non è sempre scritto** (il campo dell'XMP è facoltativo e il trailer
  SEF non lo ha): senza, l'animazione d'apertura si ferma su un istante stimato (metà del video o la regola misurata), e la
  dissolvenza copre la piccola differenza con la foto.

### 6.3 Checklist della Fase 6 (da aggiungere a `sicurezza.md` §7, oltre alle quattro voci esistenti)
- [ ] Verifica di dimensione e SHA-256 prima di **ogni** caricamento, sullo stesso descrittore (test e revisione del codice).
- [ ] Solo `core-models/.../download/https/` apre connessioni HTTPS, verso host ammessi, senza cookie, parametri né identificativi.
- [ ] Lettura del testo spenta di default; con la lettura spenta ML Kit non si inizializza e nulla esce (manifest senza
  `MlKitInitProvider`, test strumentato, cattura F56); accesa solo dopo la pagina che dice cosa esce; spegnimento che ferma tutto,
  anche dopo il riavvio (F69).
- [ ] Ritaglio e testo dal vivo senza traffico (F67); ritagli PNG senza metadati; appunti segnati come sensibili; un ritaglio dai
  Nascosti nasce nascosto.
- [ ] Foto in movimento solo-NAS: nessun byte sui dati mobili senza conferma (F71).
- [ ] Segmenti `ai` ostili rifiutati senza crash; dati di dispositivi revocati ignorati.
- [ ] Nessun byte letto dal NAS o da altre app arriva ai decodificatori nativi dei motori.
- [ ] Nascosti mai in risultati, suggerimenti, completamenti, luoghi, conteggi, "Trova simili" (F58).
- [ ] Log e Diagnostica senza query, testo letto, luoghi, vettori, chiavi di contenuto.
- [ ] Manifest remoto: rimandato alla Fase 10 (risposta Q2); la voce "manifest manomesso = ignorato" passa alla checklist della Fase 10.

### 6.4 Permessi, componenti, dipendenze e host (proposta per `sicurezza.md` §4-bis e §8)
| Voce | Cosa cambia | Protezione | Perché |
|---|---|---|---|
| `INTERNET` (c'è già) | oltre al NAS, HTTPS verso gli host dei modelli, solo da `core-models` | test d'architettura, host ammessi, regole | scaricare i modelli (Q1) |
| `uses-native-library` `libOpenCL.so`, `libvndksupport.so` | nuove, `required="false"` | ammesse per nome in `verifyReleaseManifest` | GPU per LiteRT-LM |
| `MlKitInitProvider` di ML Kit | **tolto** (`tools:node="remove"`, la via documentata per l'avvio manuale) | `verifyReleaseManifest` e `check.ps1` sul manifest fuso | ML Kit parte solo con la lettura attiva (D41) |
| Altri componenti di ML Kit e di DataTransport nel manifest fuso | restano, non esportati, inerti finché ML Kit non parte; elenco chiuso per nome (P0) | `verifyReleaseManifest` e `check.ps1`: un componente nuovo fa fallire la verifica | statistiche solo con la lettura attiva (D41, L49) |
| `com.google.ai.edge.litertlm:litertlm-android:0.18.1` | dipendenza nuova, Apache 2.0, nativa (`liblitertlm_jni.so`), solo `core-ai` | checksum, versione fissata | motore dei vettori |
| `com.google.mlkit:text-recognition:16.0.1` | dipendenza nuova, termini ML Kit, modello integrato, solo `core-ai/.../ocr/` | checksum; avvio solo con la lettura attiva | OCR (eccezione dichiarata, D49) |
| `com.google.ai.edge.litert:litert:2.3.0` | dipendenza nuova, Apache 2.0, nativa, solo `core-ai/.../cutout/` | checksum, versione fissata, cattura F67 | runtime del ritaglio (D44) |
| Modello MagicTouch | asset dell'APK (~6 MB, Apache 2.0) con impronta fissata (Q11 (a)) | verifica a ogni caricamento come gli altri modelli | ritaglio del soggetto |
| Appunti | testo e ritagli con `EXTRA_IS_SENSITIVE`; ritagli tramite il `FileProvider` esistente (`cacheDir/share/`) | nessun percorso nuovo del provider | Copia del testo dal vivo e del ritaglio |
| Host `huggingface.co`, `*.huggingface.co`, `*.hf.co` | primo host Internet dell'app | hash, host ammessi, nessun dato inviato | sorgente dei modelli |
| Permessi Android nuovi | **nessuno** | | |
| Componenti esportati o servizi nuovi | **nessuno** | | |

## 7. Rischi e piani B
| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| `litertlm-android` compilata con Kotlin 2.4 (metadati, `kotlin-reflect` 2.4.0, coroutines 1.11.0) | build o runtime rotti | `-Xskip-metadata-version-check` solo in `core-ai`; test strumentato in P0 | allineare la toolchain con l'engine; fermare le dipendenze transitive con un test di non regressione |
| Tab S9 senza misure | latenza o memoria oltre il target sul tablet | misure presto (P4, P9); motore rilasciato fuori da Cerca | prima passata su meno candidati; vettori delle query recenti in cache |
| RAM del 270M (~330 MB) con la Libreria aperta | chiusure per memoria | rilascio dopo 3 minuti e su `onTrimMemory` | caricare il motore solo al primo invio |
| Limitatore di Android 17 durante il calcolo (440M ~850 MB di PSS) | calcolo interrotto | ripresa per media (D22), F62 | processo separato `:ai` (componente nuovo, da approvare) o lotti più piccoli su CPU |
| Qualità sotto l'80% | obiettivo mancato | calibrazione (P9), prefissi | ricetta 2 con 140 token visivi; piano B della Fase 0 (MobileCLIP2, SigLIP2) solo se servisse |
| OCR debole sul testo piccolo | scontrini non trovati | seconda passata HQ (D12) | originale per gli screenshot del telefono; tessere sull'HQ |
| Un aggiornamento di ML Kit aggiunge un componente che si avvia da solo | ML Kit parte con la lettura spenta | elenco chiuso dei componenti in `verifyReleaseManifest` e `check.ps1`; cattura F56 a ogni aggiornamento | togliere anche quel componente dal manifest fuso; se non si può, fermare la versione della libreria |
| Spegnere la lettura non svuota la coda di DataTransport | statistiche partite dopo lo spegnimento | lavori annullati subito, coda tolta al riavvio (D41); F69 | se i nomi dei file della libreria cambiano: la pulizia lo segnala in Diagnostica e la pagina lo dice |
| Nessuno della famiglia attiva la lettura del testo | niente testo letto, niente testo dal vivo, Documenti solo dal contenuto | copertura che lo dice una volta; la ricerca per contenuto c'è comunque | — (scelta dell'utente) |
| Due runtime LiteRT nello stesso processo (`litert` e `litertlm-android`) | crash o conflitti delle librerie native | test strumentato in P0 che li carica insieme | ritaglio con ONNX Runtime e MobileSAM (§2.10) |
| LiteRT manda statistiche | eccezione alla "nessuna telemetria" non scelta | cattura F67 | ONNX Runtime e MobileSAM |
| Qualità del ritaglio di MagicTouch | bordi brutti, parti mancanti | filtro guidato; scelta fra v1 e v2 con le misure (P13) | MobileSAM |
| Gesti del viewer in conflitto (pressione prolungata, zoom, pager, foto in movimento) | regressioni del viewer | un solo arbitro al posto di `holdToPlay` (P12); il video delle foto in movimento solo dal badge (P14); i test del viewer esistenti; F65, F70 | testo dal vivo solo dal pulsante "Testo" e ritaglio solo dal menu |
| Istante della foto delle foto in movimento Samsung (SEF) non dichiarato | l'animazione si ferma su un fotogramma diverso dalla foto | metà del video, misura in P14, dissolvenza | niente animazione automatica per i SEF senza istante (solo il badge) |
| Foto Ultra HDR e video SDR | salto di luminosità alla fine dell'animazione | dissolvenza più lunga (~300 ms) sulle foto con `ULTRA_HDR` | niente animazione automatica su quelle foto |
| Taglio della sorgente di Media3 sopra le letture a intervalli (telefono e NAS) | il tratto non finisce all'istante | test con video di prova in P14 | fermare il lettore alla posizione, con un controllo a ogni fotogramma |
| Hugging Face cambia indirizzi o CDN | scaricamento rotto | suffissi ammessi; NAS e PC come sorgenti | aggiornamento dell'app; manifest firmato (Fase 10) |
| F4-D31 acceso a lungo | ogni indicizzatore calcola per sé; i non indicizzatori senza vettori sulle share vere | risultati locali tenuti e pubblicati dopo (D8) | Q7 |
| Allineamento a 16 KB delle librerie native | crash su dispositivi futuri | controllo in P0 sull'APK | versione successiva della libreria |
| Soglie senza il set privato | troppi o troppo pochi sicuri | valori di partenza prudenti, "Mostra anche…" | calibrare quando l'utente etichetta (Q8) |
| Viewer e griglia legati all'ordine di tempo | risultati non navigabili | `OrderedTimelineView` provata in P0 | provvisoriamente solo "Recenti" senza riordino dentro il periodo (l'ordine della timeline) |
| Conflitti con il filone grafico | merge rotti | voci come dati, perimetri esclusivi | l'orchestratore serializza |
| Crediti | costo alto | opus su 4 pacchetti su 15 | P6 su sonnet con una revisione opus del risultato |

### File cruciali
- `core-trust/.../data/IndexSegment.kt` (`SegmentClass`, `IndexSegments`, `checkBody`), `data/DeviceHead.kt` (`HeadVerifier`, classi per
  tipo di testa), `data/Leases.kt`, `data/DeviceStatusReport.kt` (`roles`), `nas/NasLayout.kt`, `TrustFormat.kt` (tetti, versione).
- `core-index/.../contract/Publishing.kt`, `contract/Work.kt` (`WorkPass`), `publish/OutboxIndexPublisher.kt` (`checkProvenance`,
  `toBlobPut`), `publish/PublishSupport.kt` (`IndexWriteRules`), `work/LeasedWorkUnits.kt`, `work/WorkPorts.kt`
  (`RemotePublishedClasses`, evidenza `THUMB_S`), `replica/BlobIndex.kt` (attestazione), `replica/Fusion.kt`, `replica/LocalReplica.kt`
  (`applyOwn`), `sync/IndexSyncEngine.kt`, `quality/ReplicaQualityController.kt` (`DOWNLOADABLE`).
- `core-prepare/.../contract/Preparation.kt` (`PrepSpec`), `photo/OutputPlan.kt`, `video/VideoPreparer.kt`, `video/FrameTimes.kt`,
  `run/DefaultPreparationRun.kt` (`publishDone`).
- `core-model/.../Embedding.kt`, `WorkState.kt`, `CellFlags.kt`; `core-database/.../entity/DerivedEntities.kt`, `dao/LibraryDaos.kt`
  (`EmbeddingDao`), `dao/TimelineIndexDao.kt` (`INDEXED`), `MediaCatalog.kt`, `GalleryMigrations.kt`.
- `core-rules/.../BackgroundActivity.kt`, `RuleEvaluator.kt` (`LOCAL_BUFFER_ONLY`), `run/StagedRun.kt`, `estimate/ConsumptionEstimator.kt`,
  `estimate/BacklogSource.kt`.
- `core-library/.../contract/Timeline.kt` (`LibraryFilter`, `TimelineView`), `contract/Viewer.kt`, `index/FilteredTimelineView.kt`;
  `core-places/.../contract/Places.kt` (`ReverseGeocoder.place`).
- `core-family/.../trust/LocalDeviceProfile.kt`, `registry/DeviceStatusBook.kt`, `onboarding/OnboardingSnapshot.kt` (ruoli).
- `app/.../main/MainScaffold.kt` (segnaposto di Cerca), `app/.../work/`, `app/.../graph/Adapters.kt` (`OnboardingRolesSignal`),
  `app/.../nas/NasGraph.kt` (cancello delle scritture), `app/src/main/AndroidManifest.xml`, `gradle/verify-release-manifest.gradle`,
  `versions-app.gradle`, `tools/check.ps1`.
- `spike/embed-probe/.../EngineManager.kt`, `ModelStore.kt`, `Photos.kt` (configurazione misurata del motore e scaricamento con
  ripresa da cui partire).
- Per il viewer (P12, P13): `feature-viewer/.../gesture/ViewerGestures.kt` (`holdToPlay`, la pressione lunga delle foto in
  movimento, che l'arbitro sostituisce), `page/ViewerPage.kt`, `page/ZoomableMedia.kt` (telephoto e la trasformazione dello zoom), `menu/` (voci del ⋮);
  `core-mediastore` (`PhoneMediaWriter` della Fase 4, F4-D20); `core-personal/.../settings/SyncedPersonalSettings.kt` (impostazioni
  sincronizzate); `core-backup` (sorgenti del backup).
- Per le foto in movimento (P14): `feature-viewer/.../video/MotionPhotos.kt` (`MotionVideo`, `BytesMotionVideoLocator`),
  `video/ViewerPlayback.kt` (`playMotion`, un solo lettore), `video/Media3ViewerPlayer.kt` (sorgenti `Range` e `Nas`),
  `ViewerController.kt` (`onMotionHold`, oggi solo per i media del telefono), `page/ViewerPage.kt` (la foto che sparisce al primo
  fotogramma); `core-metadata/.../format/Motion.kt`, `format/XmpFacts.kt`, `contract/Details.kt` (`MotionPart.stillPresentationUs`).

## 8. Cose che richiedono l'utente
| Cosa | Perché | Nel frattempo |
|---|---|---|
| Rispondere alle domande nuove Q15 e Q16 (Q1–Q14 avevano già risposta del 21/10) | cambiavano P14 | Fatto il 21/10: Q15 (a), Q16 (b) (§11); nessuna domanda aperta |
| Primo scaricamento da Internet (Q1 (a), consenso dato il 21/10) | è il primo traffico Internet dell'app | sviluppo con i motori finti; modelli copiati dal PC (sorgente 3) |
| Approvare le tre librerie nuove (LiteRT-LM, ML Kit integrato, LiteRT per il ritaglio) | `versions-app.gradle` oggi vieta gli SDK che parlano con Internet; righe in `sicurezza.md` §8; ML Kit è già accettato con la risposta Q4 | contratti e fake senza librerie |
| Attivare la lettura del testo sull'S25 di prova leggendo la pagina di consenso | F56, F65, F69 | il testo finto dei test |
| Scattare cinque o sei foto in movimento non personali con l'S25 per l'area di prova | F70, F71 | le fixture di `core-metadata` |
| Mettere nell'area di prova alcune foto non personali con soggetti e testo riconoscibili, e una senza soggetto | M1, F50, F57, F66 | fixture sintetiche |
| Etichettare le 50 query (Q8, circa un'ora e mezza) | F54, la misura che conta | soglie prudenti |
| Installare PCAPdroid sull'S25 | F46, F55, F56 | — |
| Profilo "Prova" del tablet con `test2` e il ruolo Indicizzatore per F50 | E-I1 sul campo | due dispositivi simulati |
| Revisione degli screenshot (Cerca con i due ordini, Ricerca e modelli, pagina di attivazione della lettura, testo dal vivo, ritaglio, badge "Movimento", Documenti) | gate grafico | generati in anticipo |
| Consenso alla pubblicazione sulle share vere (Q7) | F64 | solo locale |

## 9. Domande per l'utente
Q1–Q16 hanno avuto risposta il 21/10 (ultima colonna: Q1–Q10 nella prima tornata, Q11–Q14 nella seconda, Q15 e Q16 nella terza,
nate dalla risposta Q14; il dettaglio di cosa cambia è al §11).

| # | Domanda | Opzioni | Raccomandazione | Risposta (21/10) |
|---|---|---|---|---|
| Q1 | Da dove scaricare i modelli, cioè i file che fanno funzionare la ricerca (157 MB per tutti, 370 MB in più per chi analizza le foto)? | (a) Dal sito ufficiale (Hugging Face), una volta sola per la famiglia: gli altri telefoni e il tablet li prendono poi dal NAS. Si possono sempre copiare anche dal PC. (b) Solo dal PC: li scarichi tu e li metti sul NAS. L'app non va mai su Internet. (c) Da una copia che metti tu sul tuo GitHub | **(a)**: un solo scaricamento, sempre controllato con l'impronta fissata nell'app; nessun tuo dato esce. La (b) è la più chiusa, ma a ogni modello nuovo tocca a te | **(a)** |
| Q2 | Vuoi un elenco online, firmato, degli indirizzi da cui scaricare i modelli? | (a) No per ora: gli indirizzi stanno nell'app e cambiano con un aggiornamento. L'elenco firmato arriva con la Fase 10. (b) Sì, subito: serve una chiave in più da custodire, come quella che firma l'app | **(a)**: meno cose da custodire; l'impronta fissata protegge comunque | **(a)** |
| Q3 | Quali dispositivi analizzano le foto (ruolo Indicizzatore)? | (a) Solo l'S25, come consigliato all'inizio; il tablet si decide dopo le misure. (b) S25 e tablet insieme per la prima analisi (una notte invece di due), poi a scelta. (c) Solo il tablet | **(a)**: l'S25 finisce l'archivio in una notte, in carica; il tablet è più lento e condiviso | **Diversa dalle opzioni**: "Lo sceglie ogni utente. Nel mio caso farò fare tutto al telefono." → D15 |
| Q4 | Il lettore del testo nelle foto (ML Kit di Google) manda a Google statistiche d'uso: modello del telefono, versione dell'app, tempi. Mai foto né testo. Cosa facciamo? | (a) Lo usiamo bloccando le statistiche e controlliamo che nulla esca; se qualcosa esce, passiamo a un lettore open source. (b) Usiamo subito il lettore open source (PaddleOCR): più lavoro, niente Google. (c) Lo usiamo accettando le statistiche | **(a)**: rispetta "nessuna telemetria" senza il costo della (b), se il blocco regge | **Diversa dalle opzioni**: "Lo accettiamo così com'è e informiamo l'utente che, se vuole attivarlo, le condizioni sono quelle." → funzione da attivare, spenta di default (D41, D42, D49) |
| Q5 | Le ricerche recenti? | (a) Salvate solo sul telefono, le ultime 50, cancellabili una per una o tutte. (b) Non salvate. (c) Salvate, ma cancellate da sole dopo 90 giorni | **(a)**: è quello che chiede il piano; restano sul telefono e non si condividono mai | **(a)** |
| Q6 | Le foto nascoste vanno analizzate? | (a) Sì, ma non compaiono mai nella ricerca; se le mostri di nuovo, sono subito cercabili. (b) No, finché restano nascoste | **(a)**: "nascosto" protegge dentro l'app; la foto sta comunque sul NAS. Con la (b) una foto mostrata di nuovo aspetta la notte | **(a)** |
| Q7 | Oggi i risultati dell'analisi si condividono solo nelle cartelle di prova del NAS. Quando anche in quelle vere? Serve perché l'analisi si faccia una volta per tutta la famiglia | (a) A fine Fase 6, con l'app definitiva e il tuo consenso, insieme a miniature e anteprime. (b) Più avanti. (c) Mai: ogni dispositivo analizza per sé | **(a)**: niente lavoro doppio e il tablet cerca come il telefono; è la prima scrittura dell'indice nelle cartelle vere, per questo serve il tuo sì | **(a)** (F64 a fine fase) |
| Q8 | Per misurare la qualità servono 50 ricerche "con le risposte giuste" sulle tue foto. Chi le prepara? | (a) Tu, sul telefono, con uno strumento dell'app: scrivi le ricerche e segni le foto giuste (circa un'ora e mezza). Nulla finisce nel repository. (b) Nessuno: solo prove con immagini artificiali (misurano bene il testo, male il contenuto) | **(a)**: è l'unico modo di sapere se il "80% fra i primi 20" è vero | **(a)** |
| Q9 | Mostrare nelle info della foto il testo letto, con "Copia testo"? | (a) Sì. (b) No | **(a)**: utile per numeri e indirizzi negli screenshot; costa poco (P8). Gli appunti si marcano come sensibili | **Diversa dalle opzioni**: come su Samsung e Apple, tenendo premuto si copia il testo o si ritaglia il soggetto; nelle info è scomodo → testo dal vivo e ritaglio (D43–D47, §2.10, P12, P13) |
| Q10 | In che ordine vedere i risultati sicuri? | (a) Dal più recente, come la Libreria. (b) Dal più pertinente | **(a)**: i sicuri sono quasi tutti giusti, e per data si ritrova ciò che si ricorda; i "meno probabili" restano dal più pertinente | **Diversa dalle opzioni**: di default dal più recente, con la scelta del più pertinente; in ciascuno l'altro criterio pesa poco; vince la scelta dell'utente → D48, §2.4 |
| Q11 | Il ritaglio usa un piccolo modello di Google (6 MB, licenza libera). Dove lo teniamo? | (a) Dentro l'app: l'app cresce di 6 MB, il ritaglio funziona subito, senza scaricare niente e senza contattare un sito in più. (b) Come gli altri modelli: scaricato una volta dal sito di Google, poi dagli altri dispositivi dal NAS | **(a)**: è piccolo, funziona anche fuori casa e senza NAS, e l'app non deve parlare con un altro sito | **(a)** |
| Q12 | Quando scegli "Salva come nuova foto" su un ritaglio, dove va la foto? | (a) Sul telefono, nella cartella "MyCloud Gallery/Ritagli", e poi nel backup sul NAS come le altre foto. (b) Solo sul telefono, senza backup. (c) Direttamente sul NAS | **(a)**: è una foto tua come le altre: la trovi subito sul telefono e il backup la protegge | **(a)** |
| Q13 | La lettura del testo, quando la attivi, vale per tutti i tuoi dispositivi o la scegli su ognuno? (Ogni familiare decide per sé) | (a) Per tutti i tuoi dispositivi, con la possibilità di escluderne uno ("Non su questo dispositivo"). (b) Dispositivo per dispositivo: la attivi su ognuno | **(a)**: una scelta sola, ma ogni telefono o tablet può restare fuori; l'avviso compare una volta su ognuno | **(a)** |
| Q14 | Sulle foto in movimento (quelle con un breve video dentro) oggi tenere premuto fa partire il video. Con il ritaglio, cosa deve succedere? | (a) Resta così: tenendo premuto parte il video; se premi su una scritta si seleziona il testo; il ritaglio lo scegli dal menu ⋮. (b) Tenendo premuto si ritaglia il soggetto; il video parte con un pulsante | **(a)**: non si toglie una cosa che usi già; ritagliare una foto in movimento capita più di rado | **Diversa dalle opzioni**: il video parte dal pulsante; tenendo premuto si copia il testo o si ritaglia il soggetto; all'apertura una piccola animazione fino al momento della foto, come nelle altre gallerie → D45, D50, D51, §2.11, P14 |
| Q15 | Quando tocchi "Movimento" per vedere il video di una foto in movimento, si sente l'audio? | (a) Sì, come un video, al volume dei contenuti multimediali. (b) No, senza audio come l'animazione d'apertura | **(a)**: è quello che fanno le altre gallerie quando lo chiedi tu; l'animazione automatica all'apertura resta sempre muta | **(a)**: il video avviato dal badge ha l'audio, al volume dei contenuti multimediali; l'animazione d'apertura resta muta → D51, §2.11 |
| Q16 | Vuoi un interruttore per spegnere l'animazione d'apertura delle foto in movimento? | (a) No: si spegne già da sola con "Rimuovi animazioni" del telefono e con il risparmio energetico. (b) Sì, in Impostazioni, accesa di default | **(a)**: meno impostazioni; se poi ti dà fastidio, l'interruttore si aggiunge in un attimo | **(b)**, con precisazioni: un interruttore nelle **Impostazioni della Libreria**, "Animazione delle foto in movimento", **acceso di default**, impostazione personale **sincronizzata fra i dispositivi dell'utente** (come `search_order`) → D50, §2.11, P0, P14 |

## 10. Fonti consultate il 21/10/2026
- LiteRT-LM su Google Maven, versioni (0.18.1 del 20/10/2026):
  <https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml>; note di rilascio (0.18.0 con
  EmbeddingGemma 2, 0.18.1 con la correzione ABI): <https://github.com/google-ai-edge/LiteRT-LM/releases>.
- Hugging Face, metadati dei modelli (commit, data, licenza, file):
  <https://huggingface.co/api/models/litert-community/embeddinggemma-2-text-vision-440m-litert-lm>,
  <https://huggingface.co/api/models/litert-community/embeddinggemma-2-text-270m-litert-lm>. Dimensioni e SHA-256: ricerca 0.3
  (`docs/research/embeddinggemma2.md` §2.2) e `spike/embed-probe`.
- ML Kit Text Recognition, versioni (16.0.1 ancora l'ultima):
  <https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition/maven-metadata.xml>; termini e dati raccolti:
  <https://developers.google.com/ml-kit/terms>, <https://developers.google.com/ml-kit/android-data-disclosure> (ricerca 0.4).
- ML Kit, avvio manuale (`MlKit.initialize`, e `MlKitInitProvider` da togliere con `tools:node="remove"` per non farlo partire
  all'avvio dell'app): <https://developers.google.com/android/reference/com/google/mlkit/common/MlKit>; `tools:node="remove"`:
  <https://developer.android.com/build/manage-manifests>.
- DataTransport, uno dei componenti che ML Kit porta nel manifest (`TransportBackendDiscovery`, voce `cct`; riferimento per l'elenco
  chiuso di P0, non più da togliere dopo la risposta Q4):
  <https://github.com/dandar3/android-google-datatransport-transport-backend-cct/blob/master/AndroidManifest.xml>; la pratica di
  toglierlo in progetti open source (non ufficiale), considerata e poi scartata: <https://github.com/AntoC-dev/Recipedia/pull/594>,
  <https://github.com/181192/brasscribe/issues/171>.
- Ritaglio: MediaPipe Interactive Segmenter (MagicTouch, v2 int8 768×768, latenza su Pixel 10):
  <https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter>; scheda del modello MagicTouch (Apache 2.0,
  6 MB, da un punto, limiti): <https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf>; statistiche d'uso di
  MediaPipe "per tutte le piattaforme", senza un modo per spegnerle (luglio 2026):
  <https://github.com/google-ai-edge/mediapipe/issues/6291>; LiteRT 2.3.0 su Google Maven:
  <https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml>.
- Alternative del ritaglio: U²-Net (Apache 2.0, 176,3 MB e 4,7 MB, 320×320) <https://github.com/xuebinqin/U-2-Net>; BiRefNet (MIT,
  tempi e memoria su GPU da tavolo) <https://github.com/ZhengPeng7/BiRefNet>; MobileSAM (Apache 2.0, 9,66 milioni di parametri)
  <https://github.com/ChaoningZhang/MobileSAM>; ML Kit Subject Segmentation (solo Google Play services, beta)
  <https://developers.google.com/ml-kit/vision/subject-segmentation/android>. Le licenze di RMBG e di EdgeSAM (non commerciali) sono
  da ricordo [D], non riverificate: sono escluse comunque.
- Host di scaricamento di Hugging Face che cambiano (CDN `cdn-lfs*.hf.co`, Xet `cas-bridge.xethub.hf.co`):
  <https://discuss.huggingface.co/t/how-to-get-a-list-of-all-huggingface-download-redirections-to-whitelist/30486>,
  <https://docs.cloudera.com/machine-learning/1.5.5/model-hub/topics/ml-model-hub-air-gap-installation.html>.
- Foto in movimento, formato Android (`Camera:MotionPhotoPresentationTimestampUs`: il fotogramma del video che corrisponde alla foto,
  −1 se non impostato, assente = il fotogramma subito prima della metà della traccia video; gli attributi MicroVideo tolti, da
  ignorare): <https://developer.android.com/media/platform/motion-photo-format>. Formato di Samsung (trailer `MotionPhoto_Data` in
  fondo al file con la sezione SEF; `MotionPhotoPresentationTimestampUs` facoltativo nei metadati; descrizione non ufficiale):
  <https://github.com/doodspav/motionphoto>.
- API di `EmbeddingEngine`, token visivi, preprocessamento, prefissi: ricerca 0.3 e i sorgenti citati lì (LiteRT-LM e Edge Gallery su
  GitHub).

## 11. Risposte del 21/10 e cosa cambiano
| # | Risposta | Cosa cambia nel progetto |
|---|---|---|
| Q1 | (a) Dal sito ufficiale una volta per la famiglia, poi dal NAS; sempre possibile dal PC | Confermati D17 e D18; il primo scaricamento da Internet ha il consenso (§8). Il modello del ritaglio è a parte (Q11) |
| Q2 | (a) Niente elenco firmato ora | Confermato D19: il manifest firmato arriva con la Fase 10; la voce della checklist "manifest manomesso" passa alla Fase 10 |
| Q3 | Lo sceglie ogni utente; nel suo caso l'S25 fa tutto | **D15 riscritta**: ruolo per dispositivo scelto dall'utente, proposto all'onboarding (passo 7, oggi c'è) e modificabile in Impostazioni (oggi manca: §0 punto 6, P8); avvisi per le aree senza indicizzatori invariati (D14). M2 e F50 usano ancora due indicizzatori, ma solo per provare E-I1 |
| Q4 | ML Kit accettato così com'è, con l'utente informato | **Nuove D41, D42, D49; D12 e D38 riscritte**: lettura del testo **da attivare, spenta di default**, per l'account con esclusione per dispositivo (Q13); pagina che dice cosa esce (§2.6); **spenta = ML Kit non si inizializza** (`MlKitInitProvider` tolto, `MlKit.initialize` solo nel cancello, DataTransport inerte, pulizia allo spegnimento); il testo letto da un dispositivo vale per tutti, anche dove la lettura è spenta (ML Kit lì non gira). Cade il blocco delle statistiche con `TransportBackendDiscovery`. Cambiano: D9 (lavoro per classe), D11, D22, D31, D34, D38; §1.1–§1.5; §2.2 (ingressi), §2.6 (pagina di attivazione, stato vuoto); P0, P4, P5, P8, P11; F56 riscritta e F69 nuova; §6 (minacce, L49 riscritto, checklist, §6.4); deroga in `versions-app.gradle` e proposta 9 del piano (§0.3) |
| Q5 | (a) Ultime 50, solo sul telefono, cancellabili | Confermato D32 |
| Q6 | (a) Le nascoste si analizzano, mai mostrate | Confermato D29; in più: nel viewer dei Nascosti un ritaglio salvato nasce nascosto (D47, E-V11) |
| Q7 | (a) A fine Fase 6, con l'APK di release e il consenso | Confermata F64 alla chiusura |
| Q8 | (a) Le 50 query le prepara l'utente con lo strumento dell'app | Confermato §5.3 e P9; lo stesso set tara anche l'ordine "Pertinenti" (D48) |
| Q9 | Come Samsung e Apple: tenendo premuto si copia il testo o si ritaglia il soggetto; nelle info è scomodo | **Nuove D43–D47 e §2.10**: testo dal vivo con maniglie e appunti sensibili (posizioni delle righe e delle parole pubblicate nel corpo `ocr`, così funziona senza ML Kit e sui media solo-NAS: §2.3); ritaglio con **MagicTouch** (Apache 2.0, 6 MB) su **LiteRT**, non MediaPipe Tasks (telemetria annunciata), piano B MobileSAM; gesti e precedenze, accessibilità con il foglio "Testo nella foto" al posto del "Copia testo" nelle info; ritagli salvati in `Pictures/MyCloud Gallery/Ritagli/` e nel backup. **Nuovi pacchetti P12 e P13** (sonnet), una dipendenza in più (`litert` 2.3.0), prove F65–F67, domande Q11, Q12, Q14 |
| Q10 | Recenti di default, Pertinenti a scelta; l'altro criterio pesa poco; vince la scelta dell'utente | **Nuova D48 e formula al §2.4**: "Pertinenti" = pertinenza + β · recenza a decadimento (mezza vita H); "Recenti" = per periodo e, dentro, per pertinenza; parametri tarati con il set di Q8; scelta ricordata come impostazione personale sincronizzata. Cambiano D28, §2.6, P6, P8, P9; prova F68 |
| Q11 | (a) MagicTouch dentro l'app | D38, D44, §2.1 e §6.4: asset dell'APK con l'impronta fissata, nessun host in più; P0 lo porta, P13 sceglie la versione e fissa l'impronta |
| Q12 | (a) Ritagli in `Pictures/MyCloud Gallery/Ritagli/` e poi nel backup | D47 confermata; sorgente "Ritagli" accesa di default (P13) |
| Q13 | (a) Lettura del testo per l'account, con l'esclusione di un dispositivo | D41 confermata (avviso una tantum sugli altri dispositivi, "Non su questo dispositivo") |
| Q14 | Il video dal pulsante; tenendo premuto testo o ritaglio; all'apertura una piccola animazione fino al momento della foto | **D45 riscritta, nuove D50 e D51, §2.11, nuovo pacchetto P14** (sonnet, parallelo dopo P0): la pressione lunga non fa più partire il video (oggi lo fa: `holdToPlay`, `onMotionHold`, `playMotion`); badge "Movimento"; animazione silenziosa di ~1,2 s fino all'istante della foto (Google `MotionPhotoPresentationTimestampUs`, MicroVideo, SEF con ripiego a metà del video come dice la specifica Android), mai con "Rimuovi animazioni", risparmio energetico o scorrimento; solo-NAS solo sul Wi-Fi e senza attese; un solo decoder. Prove F70, F71; domande Q15, Q16 |
| Q15 | (a) Il video del badge ha l'audio, al volume dei contenuti multimediali | **D51 e §2.11**: la risposta sostituisce la raccomandazione; il video dal badge suona, l'animazione d'apertura resta sempre muta (D50). P14: lettore con l'audio per il badge e muto per l'animazione; F70 lo prova |
| Q16 | (b) Un interruttore nelle Impostazioni della Libreria, acceso di default, sincronizzato fra i dispositivi | **D50 riscritta, §2.11 (riga "Condizioni"), §1.3, §1.5, P0 e P14**: campo `motion_animation` in `core-personal` (`PersonalValue.Flag`, voce assente = acceso) e interfaccia `MotionPersonalSettings` in `core-personal/.../contract/` (P0, le firme); P14 scrive `settings/SyncedMotionSettings.kt` (file nuovo, non si scontra con P8 che implementa `text_reading` e `search_order` in `SyncedPersonalSettings.kt`) e l'interruttore in `feature-settings`. "Mai" vale anche con l'interruttore spento; il badge funziona sempre; F70 con l'interruttore spento e con la scelta ritrovata su un altro dispositivo |

**Impatto su pacchetti e tempi**
- Da 12 a **15 pacchetti**: P12 (testo dal vivo), P13 (ritaglio) e P14 (foto in movimento), tutti sonnet; opus resta su 4 (P0, P2,
  P5, P6).
- Crescono: P0 (cancello di ML Kit nel manifest e nei test d'architettura, terza libreria, asset del ritaglio, firme nuove), P4 (motore
  OCR dietro il cancello, pulizia allo spegnimento), P8 (pagina di attivazione, ruolo in Impostazioni, selettore dell'ordine), P11
  (cancello all'avvio, porte del viewer, sorgente "Ritagli"); poco P2 (corpo `ocr` con le parole), P5 (OCR solo se attivo) e P9
  (taratura dell'ordine). Le risposte Q15 e Q16 aggiungono poco: P0 (la chiave `motion_animation` e `MotionPersonalSettings`) e P14
  (`SyncedMotionSettings` e l'interruttore).
- P12, P13 e P14 corrono in parallelo agli altri dopo P0 e non stanno sul cammino critico del calcolo e della ricerca (M1 non cambia);
  entrano in M2. P14 non dipende da niente della ricerca, ma condivide due file del viewer con P12 (P14 prima). Stima: circa un quarto
  di lavoro in più sulla fase rispetto al progetto di partenza, quasi tutto nell'interfaccia del viewer e nelle prove sul campo
  (F65–F71, sette in più).
- Prove nuove: F65 (testo dal vivo), F66 (ritaglio), F67 (nessun traffico del ritaglio), F68 (ordine), F69 (attivazione e
  spegnimento della lettura), F70 e F71 (foto in movimento, del telefono e solo-NAS); F56 riscritta (traffico di ML Kit con la
  lettura spenta, accesa e di nuovo spenta).

<!-- fine del documento -->
