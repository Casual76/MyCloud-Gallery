# Verifica funzionale: Fase 6, la ricerca (commit `263dcc7`, 22/10/2026)

**La domanda.** Ogni funzione della Fase 6 promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release? Questa non
e' una revisione di sicurezza: quella sta in un altro file di `docs/review/`.

**Come.** Ho letto il codice committato, senza eseguire niente: niente Gradle, telefono, emulatori, `adb` o NAS. Per ogni flusso dell'utente ho seguito
questa catena: schermata o tasto → ViewModel → porta → oggetto legato in `:app` (Hilt, `AppGraph`, `AiGraph`, `SearchGraph`, `NasGraph`, `BackupGraph`,
`GalleryGraph`, `work/`, debug e release) → codice che tocca modelli, NAS, telefono o ML Kit → risultato che torna alla schermata. In piu' ho confrontato
i fake dei test (i motori finti di `core-ai/testing`, `core-models/testing`, `core-search/testing`, i fake del viewer in `testing/LiveTextAndCutoutFakes.kt`
e l'attrezzatura `AiRig` dei test d'integrazione di `:app`) con le implementazioni vere. Tre sotto-agenti hanno letto in parallelo, con lo stesso metodo,
i modelli e il calcolo, la scheda Cerca con la lettura del testo, e il viewer con le correzioni dal campo A. Le loro voci piu' gravi le ho ricontrollate
nel codice. Le altre le riporto con lo stato che hanno dato loro.

**Il modello dei difetti** e' quello di `docs/review/funzionale-5b.md`:
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo o con la chiusura della pagina;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object;
- un lavoro che finisce a vuoto, o che nessuno programma;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, solo `arm64-v8a`, la recinzione D13 di `mcg-prova` e `mcg-campo`, le share di prova) che rompe una funzione.

**Perimetro.** La Fase 6 cosi' come P11 e P11b l'hanno integrata in `:app` (P0–P14), piu' le correzioni dal campo, gruppo A, entrate con l'unione:
- modelli: scaricamento, ripresa, verifica, spazio, rimozione, scaffale del NAS;
- ruolo Indicizzatore e calcolo AI, con i media del solo telefono (P11b);
- pubblicazione e ricezione della sezione `ai`, copertura;
- lettura del testo (D41, D42, D49);
- scheda Cerca, cronologia, ordini, "Trova simili";
- viewer: testo dal vivo, ritaglio del soggetto, foto in movimento;
- Diagnostica, azzeramento, strumenti di debug (P9, P10);
- le sette correzioni dal campo A (D1–D7 di `docs/decisioni.md`, «Correzioni dal campo, gruppo A»).

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravità:**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `core-ai/...`
sta per `core-ai/src/main/kotlin/dev/pampa/mycloudgallery/core/ai/...`, e cosi' per gli altri moduli (`feature-search/...` per
`feature-search/src/main/kotlin/dev/pampa/mycloudgallery/feature/search/...`). I numeri di riga sono quelli di `263dcc7`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Modelli (MO) | 0 | 5 | 4 | 9 | 8 | 1 |
| Ruolo Indicizzatore e calcolo AI (IX) | 0 | 6 | 2 | 8 | 5 | 3 |
| Pubblicazione, ricezione e copertura (PR) | 0 | 1 | 2 | 3 | 3 | 0 |
| Lettura del testo (LT) | 0 | 2 | 1 | 3 | 3 | 0 |
| Scheda Cerca (CE) | 1 | 7 | 4 | 12 | 10 | 2 |
| "Trova simili" (TS) | 1 | 3 | 1 | 5 | 4 | 1 |
| Viewer: testo dal vivo, ritaglio, foto in movimento (VT, VR, VM) | 0 | 3 | 5 | 8 | 6 | 2 |
| Diagnostica e azzeramento (DG, AZ) | 0 | 4 | 3 | 7 | 5 | 2 |
| Strumenti di debug (DB) | 1 | 1 | 2 | 4 | 2 | 2 |
| Correzioni dal campo, gruppo A (CA) | 0 | 2 | 0 | 2 | 0 | 2 |
| Release (RL) | 0 | 0 | 0 | 0 | 0 | 0 |
| **Totale** | **3** | **34** | **24** | **61** | **46** | **15** |

**Il quadro.**
- A processo caldo, in debug, su una share di prova e a casa, i cammini principali arrivano in fondo:
  - scaricamento HTTPS del 270M e del 440M con ripresa, verifica a ogni caricamento e rimozione;
  - calcolo su GPU con ripiego su CPU, buffer, `LOCAL_BUFFER_ONLY`, media del solo telefono (P11b);
  - pubblicazione della testa `ai` sulle share di prova, attesa sulle share vere (F4-D31), ricezione sul tablet non indicizzatore;
  - ricerca con il parser italiano, i chip, i due ordini, la cronologia, i nascosti esclusi;
  - testo dal vivo dalle righe dell'indice, lettura al volo, ritaglio con "Salva come nuova foto", foto in movimento del telefono.
- I difetti stanno in cinque posti:
  - **i lavori**: il calcolo non concatena le tappe (IX-1), lo scaricamento vive quanto la pagina (MO-1), "Rimuovi" non dura (MO-3);
  - **le pagine che dicono lo stato**: lo stato del calcolo, la stima, i motivi della copertura, le aree senza indicizzatori e meta' dei codici
    della Diagnostica si calcolano una volta sola o mai (IX-2, IX-3, IX-4, PR-1, DG-1, DG-2, DG-3);
  - **fuori casa, senza l'ora del NAS**: le impostazioni personali non si applicano. "Spegni" della lettura del testo torna acceso (LT-1), e lo
    stesso succede all'ordine dei risultati (CE-3) e all'interruttore delle foto in movimento (VM-2);
  - **le promesse mai collegate**: il filtro "Documenti" (CE-1), "Trova simili" dalla selezione (TS-4), l'avviso una tantum della lettura del
    testo (LT-2), lo scaffale dei modelli sul NAS (MO-4), il testo dal vivo nei Nascosti (VT-1);
  - **l'avvio a freddo**: "Trova simili" non c'e' finche' non si fa una ricerca (TS-1).
- **La release** non ha difetti suoi: le regole R8 dei motori ci sono, la release tiene solo `arm64-v8a`, `DeveloperTools` di release ha le stesse firme e
  nessuno strumento di debug. Restano, uguali, i difetti di sopra.

**Le cose piu' gravi:**
- **Il filtro "Documenti" della Libreria e la parola "documenti" in Cerca non trovano mai niente (CE-1).** Il bit `CellFlags.DOCUMENT` non lo accende
  nessuno, ma il filtro e' offerto e il tipo "documenti" diventa un vincolo.
- **"Trova simili" manca dal menu del viewer dopo ogni avvio a freddo (TS-1)**, finche' l'utente non fa una ricerca qualunque. Quando c'e', ha altri tre
  difetti di navigazione (TS-2, TS-3) e manca dalla selezione (TS-4).
- **Il calcolo si ferma dopo una tappa di 8 minuti (IX-1).** La tappa dopo si chiede con `KEEP` sullo stesso nome del lavoro ancora in corso, e WorkManager
  la butta. "Indicizza ora" analizza circa 1.300 foto e si ferma. Un'attesa delle regole non riprogramma niente: di notte il calcolo gira solo quando un
  lavoro capita per caso nella fascia. La stima "circa 2 notti" (D15) diventa settimane.
- **La lettura del testo fuori casa (LT-1).** Senza l'ora del NAS la scelta resta "da datare" e lo stato non cambia: "Attiva" non fa niente, e
  "Spegni" rilascia il motore ma torna "Attiva per il tuo account". Alla lettura successiva ML Kit riparte. In piu' l'avviso una tantum sugli altri
  dispositivi dell'account non esiste (LT-2). Sono due promesse di privacy di D41.
- **I modelli:**
  - "Scarica" gira nello scope della pagina: uscendo si ferma e riparte col periodico, 6 ore dopo (MO-1);
  - sui dati mobili non c'e' modo di confermare, e la pagina dice "non riuscito" (MO-2);
  - un modello rimosso torna da solo al primo giro periodico sul Wi-Fi (MO-3);
  - la copia sul NAS e' spenta, quindi F48 (il tablet senza Internet) oggi non puo' passare (MO-4).
- **La pagina Ricerca e modelli e la Diagnostica non dicono il vero:**
  - a un Indicizzatore la pagina dice "Questo dispositivo non analizza le foto" (IX-2);
  - la stima resta su "Sto misurando il dispositivo…" (IX-3);
  - un motore che non parte diventa "Serve il modello" (IX-4);
  - le aree senza indicizzatori e i motivi della copertura non arrivano mai (PR-1);
  - `AI_MODEL_MISMATCH`, `AI_SEGMENT_REJECTED`, `AI_KILLED_MEMORY` e il lavoro `MODEL_FETCH` non arrivano in Diagnostica (DG-1, DG-2, DG-3).
  - Cosi' F46, F47, F60 e F62 non possono passare.
- **Cerca sul tablet:**
  - i media solo-NAS hanno solo il segnaposto sfocato (CE-5);
  - togliere un chip butta le parole del testo (CE-2);
  - il viewer aperto da Cerca non segue la vista viva (CE-4).
- **Gli strumenti delle prove:**
  - l'etichettatura di P9 non mostra le foto (DB-1, Blocca per F54);
  - il controllo dei vettori di `s25.ps1 -Mode ai` non trova il file vero e, se lo trovasse, non ne riconoscerebbe un record (DB-2): l'invariante I5
    di F61 oggi passa sempre.

---

## 2. I difetti

### 2.1 Modelli (MO)

#### MO-1 · "Scarica" vive quanto la pagina: uscendo, lo scaricamento si ferma e riparte solo col giro periodico
- **Gravità:** Sbagliata. **Stato:** confermato (verificatore principale; lo hanno visto anche gli agenti dei modelli e di Cerca).
- **Flusso:** Impostazioni → Ricerca e modelli → "Scarica" del 440M (370 MiB), poi Indietro. Oppure Cerca → "Scarica il modello (157 MB)", poi
  l'app in secondo piano. Oppure l'interruttore Indicizzatore acceso dalla pagina, poi Indietro.
- **Percorso:**
  - `SearchSettingsController.downloadModel` e `setIndexer` chiamano `ModelManager.ensure(..., USER_NOW o ROLE_CHANGED, 30 min)` nello scope del
    controller (`feature-search/.../models/SearchSettingsController.kt:141-150`, `:173-176`). Lo scope e' `viewModelScope`
    (`feature-search/.../models/SearchSettingsRoute.kt:41-50`). Lo stesso fa Cerca (`feature-search/.../SearchController.kt:240-243`).
  - Chiusa la pagina, il ViewModel si cancella. `ensure` prende la `CancellationException` e mette lo stato `Queued`
    (`core-models/.../manager/LocalModelManager.kt:85-91`).
  - Lo riprende solo `ModelFetchWorker` periodico: ogni 6 ore, su una rete non a consumo (`app/.../work/AiWork.kt:64-71`).
  - Il lavoro accelerato c'e' (`AiWorkScheduling.fetchNow`, `AiWork.kt:79-87`), ma nessuno lo chiama con `USER_NOW`: lo usa solo il cambio di ruolo
    (`app/.../MyCloudGalleryApp.kt:142-146`).
  - Quando la pagina accende il ruolo, la richiesta della pagina e quella del lavoro accelerato corrono insieme. Chi arriva secondo trova il lucchetto
    preso e risponde `Deferred` (`LocalModelManager.kt:79`). Se vince la pagina e l'utente esce, non scarica nessuno dei due.
- **Atteso:** D17 e §1.4: "Scarica ora" e il cambio di ruolo sono un lavoro di WorkManager accelerato, ripreso se interrotto.
- **Correzione:**
  - i tasti della pagina e di Cerca, e `setIndexer(true)`, chiedono `fetchNow(USER_NOW o ROLE_CHANGED)` attraverso una porta di `:app`
    (per esempio `ModelRequests`) invece di chiamare `ensure` nello scope della pagina;
  - la pagina osserva solo `ModelManager.states`;
  - `fetchNow(USER_NOW)` scarica il modello chiesto, non tutti quelli voluti.

#### MO-2 · Sui dati mobili "Scarica" non si puo' confermare e finisce in "non riuscito"
- **Gravità:** Sbagliata. **Stato:** confermato (verificatore principale e agente dei modelli).
- **Flusso:** fuori casa, sui dati mobili: Cerca → "Scarica il modello per cercare per contenuto (157 MB)", oppure Ricerca e modelli → "Scarica".
- **Percorso:**
  - Per `USER_NOW` sui dati mobili il cancello di `:app` risponde `NeedsConfirmation` finche' `mobileConfirmed` e' falso
    (`app/.../ai/ModelPorts.kt:43-46`).
  - `mobileConfirmed` nasce falso (`app/.../ai/AiWiring.kt:185`) e nessuno lo mette a vero. L'unica scrittura e' `set(false)` in
    `ModelFetchWorker` (`app/.../work/AiWork.kt:202`).
  - Il gestore traduce la richiesta di conferma in `Failed(NETWORK_REFUSED, bytes)` (`core-models/.../manager/LocalModelManager.kt:157`).
  - La pagina la mostra come `AI_MODEL_FETCH_FAILED`, "Scaricamento del modello non riuscito · Riprova" (`feature-search/.../models/ModelsSection.kt:56-60`).
    "Riprova" rifa' la stessa cosa. Nessun foglio chiede la conferma con la dimensione.
- **Atteso:** §2.1 e §1.4: con l'app aperta, "Scarica ora" sui dati mobili chiede conferma con la dimensione, come "Fai ora". Mai in roaming.
- **Perche' i test passano:** `FakeModelNetworkGate` da' il verdetto scelto dal test; nessun test di `:app` passa da `AppModelNetworkGate` con i dati
  mobili.
- **Correzione:**
  - uno stato `ModelState.NeedsConfirmation(bytes)`, oppure il codice `NETWORK_REFUSED` con i byte, che la pagina e Cerca mostrano come foglio
    "Scaricare 157 MB con i dati mobili?";
  - il "Si'" mette `mobileConfirmed` e chiede `fetchNow(USER_NOW)`;
  - per gli altri rifiuti della rete, vedi MO-8.

#### MO-3 · "Rimuovi" non dura: il modello torna da solo al primo giro periodico sul Wi-Fi
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli, ricontrollato).
- **Flusso:** Ricerca e modelli → "Rimuovi" del 270M (o del 440M su un Indicizzatore) → conferma → Wi-Fi → giro periodico.
- **Percorso:**
  - Il foglio di conferma dice "Potrai scaricarlo di nuovo" (`feature-search/.../models/ModelsSection.kt:146`, `:151`).
  - `LocalModelManager.remove` toglie i file e mette `Absent` (`core-models/.../manager/LocalModelManager.kt:372-388`). Non resta nessun segno che
    l'ha voluto l'utente.
  - `ModelFetchWorker` vuole sempre il 270M, e il 440M se il dispositivo e' Indicizzatore (`app/.../work/AiWork.kt:192-198`). Con `ensure(RULES)` li
    riscarica, da 160 a 390 MB, entro 6 ore.
- **Atteso:** D20 ("Rimuovi toglie il file e dice la conseguenza") e D15 (spegnendo il ruolo si chiede se togliere il 440M). La scelta dell'utente
  resta finche' non tocca "Scarica".
- **Perche' i test passano:** `FakeModelManager` (`core-models/.../testing/ModelFakes.kt:90-121`) non ha un lavoro periodico; nessun test di `:app`
  esegue `ModelFetchWorker`.
- **Correzione:** un segno "rimosso dall'utente" per modello (per esempio `models/meta/<sha256>.removed`). `ModelFetchWorker` lo salta con `RULES`; lo
  tolgono `USER_NOW` e, per il 440M, l'accensione del ruolo.

#### MO-4 · Lo scaffale dei modelli sul NAS e' spento: il tablet scarica da Internet, e "Dal PC" vale solo se Internet manca
- **Gravità:** Sbagliata. **Stato:** confermato. E' il punto aperto di P3, dichiarato in P11 e P11b, ma F48 e F49 lo promettono.
- **Flusso:** il tablet (profilo "Prova") prende i modelli; oppure l'utente copia il modello in `MyCloud Gallery/Modelli/` dell'area comune.
- **Percorso:**
  - `AiWiring.build` non passa uno scaffale a `LocalModels` (`app/.../ai/AiWiring.kt:193-200`, commento a `:197`), quindi resta `NoModelShelf`
    (`core-models/.../LocalModels.kt:44`), che non elenca niente (`core-models/.../noop/ModelNullObjects.kt:52-58`).
  - Le sorgenti, in ordine, sono le copie sul NAS (nessuna), HTTPS, la cartella dal PC (`core-models/.../manager/LocalModelManager.kt:285-311`).
  - La cartella dal PC funziona (`core-models/.../source/NasModelFetcher.kt:112-114`), ma la si raggiunge solo se HTTPS manca o rifiuta.
  - Nessun dispositivo pubblica mai la sua copia (D18).
- **Atteso:** D17, D18; F48 ("nessuna connessione a Internet dal tablet, circa 10 s per il 270M"); F49.
- **Perche' i test passano:** i test della sorgente NAS di core-models usano `InMemoryModelShelf` (`ModelFakes.kt:152-175`).
- **Correzione:** collegare lo scaffale quando ci sara' lo scrittore a flusso di core-nas. Fino ad allora: F48 rimandata e scritta come tale nel piano
  delle prove; F49 fatta con il NAS raggiungibile e Internet bloccato (altrimenti la sorgente sara' HTTPS).

#### MO-5 · Il giro di `ModelFetchWorker` in un processo appena nato puo' finire a vuoto
- **Gravità:** Sbagliata. **Stato:** plausibile (agente dei modelli).
- **Percorso:**
  - `ModelFetchWorker.doWork` non aspetta la famiglia pronta (`app/.../work/AiWork.kt:186-199`). Lo fanno `AiComputeWorker` (`:150`) e i lavori del
    NAS (`app/.../work/NasWork.kt:317`), per la correzione D-7 delle prove sul campo.
  - Nello stato transitorio "non configurato" di un processo nuovo `runIfActive` risponde null (`app/.../gallery/GalleryGraph.kt:432-441`, `:760-761`):
    niente `refresh`, niente `ensure`, e il giro e' consumato.
  - Non resta niente in Diagnostica (vedi DG-2).
- **Atteso:** D17 (scaricamento secondo le regole) e la correzione D-7.
- **Prova sul telefono:** app uccisa, poi il lavoro con `adb shell cmd jobscheduler run`; guardare se lo stato del modello cambia.
- **Correzione:** `gallery.awaitReady(COLD_START_WAIT)` come `AiComputeWorker`, e l'esito riportato con `DiagJob.MODEL_FETCH`.

#### MO-6 · Il modello del ritaglio compare come "Assente", con "Scarica ora"
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** lo stato iniziale del gestore ha tutti i modelli del catalogo, il ritaglio come `Absent` (`core-models/.../store/ModelStore.kt:164-169`).
  Il ripiego `?: Ready(BUNDLED)` del controller non scatta mai (`feature-search/.../models/SearchSettingsController.kt:124`), e la riga mostra
  "Scarica ora" (`ModelsSection.kt:145`) fino al primo ritaglio o al primo giro di `ModelFetchWorker`.
- **Atteso:** D44: il modello sta nell'APK, non si scarica.
- **Perche' i test passano:** `FakeModelManager` parte con la mappa vuota (`ModelFakes.kt:91`), e nei test il ripiego scatta.
- **Correzione:** `CUTOUT` con `Absent` come pronto dall'APK, oppure `ensure(CUTOUT)` all'avvio (copia locale, senza rete).

#### MO-7 · Ogni 6 ore, sul Wi-Fi, l'app rilegge per intero tutti i modelli: circa 1,1 GB di SHA-256
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** `ModelFetchWorker` chiama `refresh()`, che verifica ogni file (`app/.../work/AiWork.kt:191`; `LocalModelManager.kt:403-417`), e poi
  `ensure` su ogni modello voluto, che lo riverifica (`AiWork.kt:198`; `LocalModelManager.kt:102-108`): 270M, 440M e ritaglio, due volte per giro.
- **Atteso:** D16 chiede la verifica a ogni caricamento, non a ogni giro periodico.
- **Correzione:** dopo `refresh()` nessun `ensure` per i modelli gia' `Ready`; `refresh()` una volta per processo.

#### MO-8 · Un rifiuto della rete si mostra come "Scaricamento non riuscito · Riprova"
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Flusso:** "Scarica" in roaming o senza rete; ruolo acceso sui dati mobili (`ROLE_CHANGED`).
- **Percorso:** `AppModelNetworkGate` rifiuta (`app/.../ai/ModelPorts.kt:38-46`), il gestore risponde `Failed(NETWORK_REFUSED)`
  (`LocalModelManager.kt:157-158`), la pagina dice `AI_MODEL_FETCH_FAILED` con "Riprova" (`ModelsSection.kt:57`), che non puo' riuscire.
- **Atteso:** §2.6, D17. In background la regola e' "solo Wi-Fi": lo stato giusto e' "in coda".
- **Correzione:** per un rifiuto della rete, `Deferred(Queued)` con la frase giusta ("aspetto il Wi-Fi", "in roaming non scarico").

#### MO-9 · Lo scaricamento periodico non segue le regole del calcolo
- **Gravità:** Minore. **Stato:** confermato (verificatore principale).
- **Percorso:**
  - `ModelFetchWorker` periodico ha solo il vincolo `UNMETERED` (`app/.../work/AiWork.kt:64-71`) e non valuta le regole (`:186-207`). Scarica
    370 MiB su un Wi-Fi qualunque, anche fuori casa (`heavyOnlyAtHome` ignorato), con la batteria bassa o il telefono caldo. Il commento (`:47-49`) e
    §1.4 dicono "secondo le regole di `AI_COMPUTE`".
  - Il lavoro accelerato (`:81-82`) non ha `getForegroundInfo`. Su Android 10 e 11 (minSdk 29, `gradle.properties:14`) WorkManager lo rifiuta. Non
    tocca i dispositivi della famiglia.
- **Correzione:** valutare le regole di `AI_COMPUTE` (o di una voce propria) prima di uno scaricamento in background; `getForegroundInfo`, oppure niente
  `setExpedited` sotto Android 12.

### 2.2 Ruolo Indicizzatore e calcolo AI (IX)

#### IX-1 · Il calcolo si ferma dopo una tappa da 8 minuti: le tappe non si concatenano e un'attesa delle regole non riprogramma niente
- **Gravità:** Sbagliata. **Stato:** confermato (verificatore principale).
- **Flusso:** Ricerca e modelli → Indicizzatore acceso → "Indicizza ora". Oppure la notte, con il preset Notte (il predefinito), in carica.
- **Percorso:**
  - `AiComputeWorker.doWork` esegue **una** tappa di `StagedRun` (`app/.../work/AiWork.kt:148-168`), lunga 8 minuti
    (`core-rules/.../run/StagedRun.kt:346`). A tempo finito il calcolo risponde `AiStop.BUDGET` (`core-ai/.../compute/AiStageBatch.kt:21`, `:48`) e la
    tappa e' `Yielded` (`StagedRun.kt:398-403`).
  - Per la tappa dopo, il lavoro chiama `AiWorkScheduling.afterNews` (`AiWork.kt:166`). Questo mette in coda `COMPUTE_NOW_WORK` con
    `ExistingWorkPolicy.KEEP` (`:98-102`).
  - Ma `COMPUTE_NOW_WORK` e' il nome del lavoro **ancora in corso** quando questo e' nato da "Indicizza ora" (`:89-96`, stesso nome) o da un `afterNews`
    (dopo la sincronizzazione o la preparazione, `app/.../work/NasWork.kt:333-335`). Per WorkManager un lavoro in corso e' "non finito", e `KEEP` butta
    la richiesta nuova. Il lavoro finisce e nessuno lo riprende.
  - Gli altri lavori a tappe lo fanno bene: `NasWorkScheduling.continueLater` e il backup usano `APPEND_OR_REPLACE` (`NasWork.kt:127-129`,
    `app/.../work/BackupWork.kt:255-257`).
  - Un esito `Waiting` (fuori dalla fascia notturna, non in carica: preset Notte, `core-rules/.../Presets.kt:118-122`) non riprogramma niente
    (`AiWork.kt:165-167`). Il backup ha `afterWait`, con il vincolo della carica e l'inizio della fascia (`BackupWork.kt:185`); il calcolo no.
  - Il calcolo parte quindi solo quando il periodico (ogni 6 ore, senza vincoli, `AiWork.kt:72-76`) o un `afterNews` cadono per caso dentro la fascia
    e in carica.
- **Scenario:**
  - "Indicizza ora" analizza circa 1.300 foto (8 minuti a 2,8 foto/s) e si ferma; la pagina dice "In attesa: lo permettono le regole…" (IX-2).
  - Di notte il calcolo gira quando il periodico capita nella fascia (al piu' due tappe: il periodico e la sua prima continuazione) e dopo ogni
    sincronizzazione o preparazione finita. Un quarto d'ora o poco piu' per notte invece di otto ore.
  - La stima "circa 2 notti per 18.000 media" (D15) diventa settimane. F50, F51 e F52 misurerebbero la programmazione dei lavori, non il calcolo.
- **Perche' i test passano:** i test chiamano `AiStages.compute` o il motore direttamente; WorkManager e' sempre finto.
- **Correzione:**
  - la tappa dopo con un nome suo e `APPEND_OR_REPLACE`, come `NasWorkScheduling.continueLater`;
  - "Indicizza ora" con un nome suo, cosi' che un `afterNews` in coda non lo assorba;
  - un `afterWait` per `AI_COMPUTE` (vincolo della carica, ritardo fino all'inizio della fascia), come quello del backup.
- **Test:** con `WorkManagerTestInitHelper` e una tappa di pochi millisecondi, "Indicizza ora" con lavoro per tre tappe: devono girare tre tappe.

#### IX-2 · A un Indicizzatore la pagina dice "Questo dispositivo non analizza le foto"
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli e verificatore principale).
- **Flusso:** Indicizzatore acceso → l'app si riapre (il lavoro della notte gira di solito in un altro processo) → Ricerca e modelli.
- **Percorso:**
  - Lo stato del calcolo nasce `AiStatus.Off` (`app/.../ai/AiGraph.kt:239-242`) e lo aggiorna solo il motore, creato alla prima tappa del processo
    (`:250-279`, `:277`).
  - La porta mostra lo stato del motore quando il ruolo e' acceso (`app/.../ai/SearchScreenPorts.kt:95-96`), quindi `Off`. La pagina lo rende con
    "Questo dispositivo non analizza le foto." in verde (`feature-search/.../models/SearchSettingsScreen.kt:152`, `:176`;
    `feature-search/src/main/res/values/strings_models_page.xml:26`).
  - Dopo una tappa finita per tempo la frase e' "In attesa: lo permettono le regole di rete e batteria." (`core-ai/.../compute/AiComputeEngine.kt:149`),
    anche se le regole non c'entrano. Un `Waiting` delle regole prima di partire (fuori fascia) non arriva mai alla pagina.
- **Atteso:** §1.5 (`IndexerState` con l'`AiStatus` vero), D15.
- **Perche' i test passano:** in `FakeIndexerControl` (`feature-search/src/test/.../SearchTestSupport.kt:95-103`) lo stato lo sceglie il test.
- **Correzione:** con il ruolo acceso e nessuna tappa nel processo, `Idle(null)` o l'ultimo stato salvato (insieme al battito `heartbeat-ai-compute`, con
  il motivo dell'ultima attesa delle regole); `BUDGET` come "riprendo fra poco", distinto da `RULES`.

#### IX-3 · Stima, notti e spazio restano quelli del primo momento; "Sto misurando il dispositivo…" resta per sempre
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli e verificatore principale).
- **Flusso:** si accende il ruolo → si esce → si rientra nella pagina; oppure si lascia lavorare il calcolo.
- **Percorso:**
  - `AppIndexerControl` e' un `@Singleton` (`app/.../di/AiModule.kt:84-87`) che rilegge lavoro e spazio solo alla nascita e dopo un cambio di ruolo
    (`app/.../ai/SearchScreenPorts.kt:104-125`). Il commento dice "all'apertura della pagina", ma nessuno lo chiama li', e non dopo una tappa.
  - Nasce con il primo `SearchViewModel` (`feature-search/.../SearchViewModel.kt:42`). Se Cerca si apre prima che il motore del NAS sia partito,
    `AiGraph.backlog()` risponde null (`app/.../ai/AiGraph.kt:304-307`) e la pagina dice 0 media da analizzare fino al prossimo cambio di ruolo.
  - La misura breve vive solo nel ViewModel (`feature-search/.../models/SearchSettingsController.kt:57`) e parte solo da `setIndexer(true)`, **dopo**
    lo scaricamento del 440M nello scope della pagina (`:146-147`, vedi MO-1). A ogni nuova visita, o se la misura e' fallita perche' il 440M mancava
    (`core-ai/.../runtime/RealDeviceProbe.kt:62`, errore inghiottito a `SearchSettingsController.kt:215-223`), `estimateText` mostra "Sto misurando il
    dispositivo…" (`SearchSettingsScreen.kt:180-184`) anche se niente sta misurando.
  - Con F4-D31 il lavoro che resta non scende: `settled` non conta cio' che e' salvato qui o in attesa (`app/.../ai/AiCatalogPorts.kt:138-148`;
    `core-ai/.../compute/AiBacklog.kt:32`).
- **Atteso:** D15 ("circa N notti"), D20 (spazio di vettori e testo).
- **Perche' i test passano:** in `FakeIndexerControl` lavoro e spazio sono flussi impostati dal test.
- **Correzione:** `refresh()` nel contratto `IndexerControl`, chiamato dalla pagina a ogni apertura e dopo ogni tappa; la misura salvata e rifatta quando
  il 440M diventa `Ready`; nel lavoro che resta, escludere cio' che e' salvato qui (come fa `AppAiPhone`).

#### IX-4 · Se il motore non parte la pagina dice "Serve il modello" con il modello "Pronto"; "caldo" e "problema" non compaiono mai
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli).
- **Percorso:**
  - Un motore che non parte ne' su GPU ne' su CPU (`INIT_FAILED`) passa da `MediaComputer.kt:100`, `ItemWorker.kt:150`, `ComputeSession.kt:218-221` e
    `AiComputeEngine.kt:146` (tutti in `core-ai/.../compute/`) e diventa `AiStatus.ModelMissing`: "Serve il modello: scaricalo qui sotto"
    (`strings_models_page.xml:27`), con il modello in stato "Pronto".
  - `AiStatus.Problem` non lo costruisce nessun codice di produzione (solo `feature-search/.../testing/SearchSamples.kt:68`): i tasti "Diagnostica" e
    "Libera spazio" della pagina (`SearchSettingsScreen.kt:131-138`) non compaiono mai.
  - `AiWaitReason.HOT` e `PREPARATION` non li produce nessuno: col caldo (`SEVERE`) la pagina dice "In attesa: lo permettono le regole…"
    (`AiComputeEngine.kt:149`, `AiStageBatch.kt:21`). Sotto 1 GiB libero dice "In attesa di spazio libero" senza "Libera spazio".
- **Atteso:** §2.6 (`AI_ENGINE_INIT_FAILED` → Diagnostica, `AI_STORAGE_LOW` → Libera spazio), E-A5.
- **Perche' i test passano:** `FakeAiCompute` (`core-ai/.../testing/FakeAiCompute.kt:146-150`) tratta ogni `AiEngineException` come "riprova".
- **Correzione:** un `AiStop.ENGINE_FAILED` → `Problem(AI_ENGINE_INIT_FAILED)`; `STORAGE_LOW` → `Problem(AI_STORAGE_LOW)`; un fermo per caldo →
  `Waiting(HOT)`.

#### IX-5 · Il motore del 440M resta in memoria dopo la misura e dopo ogni tappa
- **Gravità:** Sbagliata. **Stato:** plausibile (agente dei modelli).
- **Percorso:** la misura lascia il motore com'e' (`core-ai/.../runtime/RealDeviceProbe.kt:41`); `LiteRtLmImageEmbedder.release()`
  (`core-ai/.../runtime/LiteRtLmImageEmbedder.kt:72-76`) lo chiama solo `AiGraph.stop()` (`app/.../ai/AiGraph.kt:320`); `onTrimMemory` rilascia solo
  il motore delle query e il ritagliatore (`app/.../ai/AiWiring.kt:148-152`). Centinaia di MB di GPU restano nel processo della galleria in primo piano.
- **Atteso:** §1.4 ("Memoria") e §7 (rischio "memoria esaurita").
- **Prova sul telefono:** in F52, PSS del processo in primo piano dopo aver acceso il ruolo e dopo una tappa.
- **Correzione:** `images.release()` alla fine di `runStage`, dopo `measure()` e in `onTrimMemory`.

#### IX-6 · Con F4-D31 (le share vere di oggi) ogni tappa ripercorre tutto cio' che e' gia' calcolato
- **Gravità:** Sbagliata. **Stato:** plausibile (agente dei modelli, ricontrollato il percorso).
- **Flusso:** S25 Indicizzatore sull'archivio vero (18.000 media), dalla seconda notte.
- **Percorso:**
  - Le voci in attesa non sono "a posto" (`app/.../ai/AiCatalogPorts.kt:138-148`): `AiPublishedClasses` propone tutti i mesi
    (`core-index/.../ai/AiPublishedClasses.kt:77-82`) e le unita' ripartono dal mese piu' recente (`core-index/.../work/LeasedWorkUnits.kt:79-110`).
  - `ItemWorker` rimette nell'outbox ogni media gia' salvato (`core-ai/.../compute/ItemWorker.kt:67-75`, `:95-102`).
  - Per ogni media il pubblicatore chiede la regola M3, che rilegge **tutta** la share (`core-index/.../ai/OutboxAiIndexPublisher.kt:88` → `CatalogAiShareContent.owned`
    → `shareRows`, `AiCatalogPorts.kt:28-31`, `:47-53`). Anche `CatalogAiWorkCatalog.monthMedia` rilegge tutta la share per ogni mese (`:120-138`).
  - In una tappa da 8 minuti il tempo va nel ripercorso prima di arrivare ai media nuovi (e IX-1 limita le tappe).
- **Atteso:** D22 e §2.2 (ripresa senza rifare), F4-D31 (voci in attesa, pubblicate dopo).
- **Prova sul telefono:** sulla share vera, dopo la prima notte, le notti seguenti devono aggiungere media nuovi; misurare `nasWorkRows` sulla share.
- **Correzione:** per scegliere le unita', considerare fatto anche cio' che e' salvato qui o in attesa; la regola M3 una volta per lotto; le righe della
  share lette una volta per tappa.

#### IX-7 · Un errore passeggero del motore su CPU segna il media "non supportato" per sempre
- **Gravità:** Minore. **Stato:** plausibile (agente dei modelli).
- **Percorso:** su CPU un errore qualunque diventa `BAD_INPUT` o `OUT_OF_MEMORY` (`core-ai/.../runtime/LiteRtLmImageEmbedder.kt:84-91`,
  `VectorSessions.kt:57`), poi `Refused` o `Invalid` (`core-ai/.../compute/MediaComputer.kt:101-106`) e `unsupported.mark` su file
  (`ItemWorker.kt:143-148`, `app/.../ai/AiCatalogPorts.kt:61-76`): da li' `settled` lo considera fatto (`AiCatalogPorts.kt:146`, `app/.../ai/AiPhone.kt:101`).
- **Atteso:** E-A12: solo un ingresso rifiutato, o un vettore non valido due volte.
- **Correzione:** "non supportato" solo per un rifiuto di `ModelInputs`; un guasto del motore lascia il media in attesa per il giro dopo.

#### IX-8 · Spegnendo il ruolo non si propone di togliere il 440M, e il buffer resta pieno
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** `SearchSettingsController.setIndexer(false)` salva e basta (`feature-search/.../models/SearchSettingsController.kt:141-150`). Il buffer
  (fino a 500 MB) lo svuotano solo `AiGraph.stop` (`app/.../ai/AiGraph.kt:323`) e lo spazio basso (`core-ai/.../compute/ComputeSession.kt:56`).
- **Atteso:** D15 ("chiede se togliere il 440M").
- **Correzione:** foglio "Togliere anche il modello?" e `buffer.clear()` quando lo spegnimento e' salvato.

### 2.3 Pubblicazione, ricezione e copertura (PR)

#### PR-1 · Le aree senza indicizzatori (E-I12) e i motivi della copertura non arrivano mai all'utente
- **Gravità:** Sbagliata. **Stato:** confermato (tutti e due gli agenti e il verificatore principale). Dichiarato aperto in P11b ("`IndexerAreas`
  invariati", `docs/decisioni.md:4480`), ma F60 lo prova.
- **Flusso:** F60: Indicizzatore spento sull'S25 e sul tablet, poi Cerca e Ricerca e modelli. Oppure Cerca con la copertura sotto il 98% → "Perche'?".
- **Percorso:**
  - La pagina riceve sempre una lista vuota: `AppIndexerControl.refresh` costruisce `IndexerBacklog(..., emptyList())`
    (`app/.../ai/SearchScreenPorts.kt:112`). La sezione delle aree senza indicizzatori (`feature-search/.../models/SearchSettingsScreen.kt:78-82`) non
    compare mai. `IndexerAreas` (`core-ai/.../compute/IndexerAreas.kt:21-32`) e' scritto e non e' legato.
  - `SearchNotice.NoIndexerForArea` e `SearchNotice.TextReadingOff` (`core-search/.../contract/Search.kt:84`) hanno la loro resa
    (`feature-search/.../SearchNoticeItems.kt:87`), ma nessun codice di produzione li costruisce. `AI_NO_INDEXER_AREA`
    (`core-ai/.../contract/Compute.kt:191`) non lo emette nessuno.
  - I motivi della copertura (D31) sono sempre "in preparazione": `SearchGraph` prende il default `CoverageReasons.PREPARATION`
    (`app/.../search/SearchGraph.kt:62`) e `AiWiring.build` non ne passa altri (`app/.../ai/AiWiring.kt:213-223`;
    `core-search/.../coverage/CoverageTracker.kt:14-19`). `NO_INDEXER_FOR_AREA`, `PHONE_ONLY_NOT_INDEXER`, `UNSUPPORTED` e `TEXT_READING_OFF` non
    nascono mai (la pagina li sa dire: `SearchSettingsScreen.kt:217`, `:233`).
- **Atteso:** D14, D31, E-I12, F60: "La tua area privata non ha indicizzatori" in Cerca e nella pagina; "Perche'?" con i motivi veri; "Parole cercate
  con la lettura non attiva" (§2.6).
- **Perche' i test passano:** i test di `SearchNoticeItems` passano avvisi costruiti a mano.
- **Correzione:** `IndexerAreas.uncovered` sugli stati firmati dei dispositivi (ruoli e `lastSeenAt` entro 7 giorni) in `AppIndexerControl` e come
  `SearchNotice`; un `CoverageReasons` di `:app` che conta i motivi dal catalogo (stato della preparazione, presenza solo sul telefono, "non
  supportati", ruolo, modello, lettura).

#### PR-2 · Il testo letto al volo da un non Indicizzatore, su una share vera, non si pubblica mai
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** `NasGraph.flushOutbox` pubblica l'outbox `ai` ma non chiama `releasePending` (`app/.../nas/NasGraph.kt:852-858`). Lo chiama solo la
  tappa del calcolo (`core-ai/.../compute/ComputeSession.kt:113`), che parte solo con il ruolo acceso e il 440M pronto
  (`core-ai/.../compute/AiComputeEngine.kt:97-104`). Quando F4-D31 si spegne, il testo letto dal tablet resta in attesa.
- **Atteso:** F4-D31 e D42 (pubblicato dopo, senza rifarlo), F64.
- **Correzione:** `releasePending(share)` in `flushOutbox` prima della pubblicazione.

#### PR-3 · Le unita' del calcolo si rilasciano anche con risultati ancora nell'outbox: un altro Indicizzatore puo' rifarle
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** `LeasedWorkUnits` del calcolo nasce senza `unpublished` (`app/.../nas/NasAi.kt:104-110`); quello dell'indice lo passa
  (`app/.../nas/NasGraph.kt:614`). `LeasedWorkUnits` rilascia allora sempre (`core-index/.../work/LeasedWorkUnits.kt:147-156`).
- **Atteso:** D9, E-I1 ("una volta per tutta la famiglia"), F50.
- **Correzione:** `unpublished = publisher::hasPending` (`core-index/.../ai/OutboxAiIndexPublisher.kt:170-171`).

### 2.4 Lettura del testo (LT)

#### LT-1 · Fuori casa "Attiva" non fa niente e "Spegni" torna acceso: alla lettura dopo ML Kit riparte
- **Gravità:** Sbagliata (una promessa di privacy di D41). **Stato:** confermato nel codice (agente di Cerca, ricontrollato); quanto spesso succede a
  casa va visto in F69.
- **Flusso:**
  1. Fuori casa, con WireGuard spento, app appena avviata: nessuna sessione SMB.
  2. Ricerca e modelli → "Scopri e attiva" → "Attiva": la pagina si chiude e la riga resta "Spenta".
  3. Con la lettura gia' attiva, "Spegni" mostra "Sto spegnendo…" e poi torna "Attiva per il tuo account".
- **Percorso:**
  - Il registro dei dati personali ha l'ora del NAS solo dopo un accesso SMB del processo, e non con l'ora bloccata
    (`app/.../nas/NasOrganizer.kt:387-392`).
  - Senza ora, `LedgerPersonalStore.set` mette la modifica in `pending` (`core-personal/.../store/LedgerPersonalStore.kt:70-85`). `entries`, lo stato
    che pubblica, non la contiene (`:57-58`).
  - `SyncedSearchSettings.textReading` (e `searchOrder`) leggono solo `store.entries` (`core-personal/.../settings/SyncedSearchSettings.kt:35-38`,
    `:44-47`): la scelta non si vede finche' un giro con il NAS non la data.
  - `enableForAccount` ignora l'esito (`app/.../ai/TextReading.kt:119-122`). Senza registro agganciato `setChoice` risponde `false`
    (`app/.../ai/AiWiring.kt:239`) e nessuno lo dice.
  - `disable()` rilascia il motore e annulla i lavori di DataTransport, poi `setChoice(Off)` finisce in `pending`; nel `finally` lo stato torna
    `OnForAccount(thisDevice = true)` (`TextReading.kt:132-140`, `:101-105`). La memoria dell'avvio resta "attiva". Alla prossima lettura al volo o
    tappa del calcolo ML Kit e DataTransport ripartono.
- **Atteso:** D41, E-A18: dopo "Spegni" ML Kit non parte piu', subito su questo dispositivo; "Attiva" vale subito. La sincronizzazione puo' arrivare dopo.
- **Perche' i test passano:** `SyncedSearchSettingsTest` e `AiRig` (righe 149-151) usano dispositivi con l'ora del NAS sempre ancorata
  (`PersonalWorld.anchored = true`); il caso senza ora e' provato solo per preferiti e nascosti (`PersonalSyncTest`).
- **Correzione:** `SyncedSearchSettings` (e `SyncedMotionSettings`) leggono lo stato efficace, `entries` piu' `pending` (un flusso `effectiveEntries`
  dello store). In piu', in `AppTextReadingSwitch`, la scelta dell'utente vale subito e si salva in `TextReadingMemory` finche' lo store non la
  raggiunge. Un esito negativo di `setChoice` si dice. Test con l'ora non ancorata.

#### LT-2 · L'avviso una tantum sugli altri dispositivi dell'account non esiste
- **Gravità:** Sbagliata (privacy). **Stato:** confermato (agente di Cerca, ricontrollato).
- **Flusso:** l'utente attiva la lettura sull'S25. Il tablet, alla sincronizzazione, passa a "attiva su questo dispositivo" senza dire niente e comincia
  a leggere (e a mandare le statistiche di ML Kit).
- **Percorso:** lo stato diventa attivo appena arriva la voce sincronizzata (`app/.../ai/TextReading.kt:101-105`). L'unica cosa visibile e' la riga di
  stato della pagina Ricerca e modelli (`feature-search/.../models/TextReadingBlock.kt:38-97`): nessuna notifica, nessun foglio, nessun segno "avvisato".
  `OnForAccount` lo leggono solo la pagina e le porte.
- **Atteso:** D41 ("ogni altro dispositivo dell'account lo dice una volta e si puo' escludere"), §1.4, §1.5, Q13, F69.
- **Correzione:** un segno locale "avviso mostrato per la versione n" (nel file di `TextReadingMemory`); un foglio alla prima volta che lo stato e'
  `OnForAccount(thisDevice = true)` senza il consenso dato qui, con "Non su questo dispositivo". Piu' prudente: `activeHere` falso finche' l'avviso non
  e' stato visto.

#### LT-3 · La versione del testo accettata non ha effetto sul motore
- **Gravità:** Minore, latente (oggi `CURRENT_TEXT_VERSION = 1`). **Stato:** confermato (agente di Cerca).
- **Percorso:** `activeHere` (`core-ai/.../contract/TextReading.kt:25`) e `AppTextReadingSwitch.state` (`app/.../ai/TextReading.kt:101-105`) non guardano
  `acceptedVersion < CURRENT_TEXT_VERSION`; il confronto c'e' solo nella pagina (`SearchSettingsController.kt:121`). Con una versione 2 del testo, chi ha
  accettato la 1 continuerebbe a leggere.
- **Atteso:** D41: un testo cambiato si fa riaccettare.
- **Correzione:** `activeHere` falso finche' la versione accettata e' piu' vecchia di quella corrente.

### 2.5 Scheda Cerca (CE)

#### CE-1 · Il filtro "Documenti" della Libreria e la parola "documenti" in Cerca non trovano mai niente
- **Gravità:** Blocca. **Stato:** confermato (agente di Cerca e verificatore principale). Elencato fra le cose che restano in P11 e P11b, ma il filtro
  e' offerto all'utente.
- **Flusso:** Libreria → filtri → "Documenti". Oppure Cerca "documenti", "scontrino documenti 2025", o il suggerimento "Documenti".
- **Percorso:**
  - Il bit `CellFlags.DOCUMENT` (`core-model/.../CellFlags.kt:92`) lo scrive solo `MediaCatalog.setDerivedFlags`
    (`core-database/.../MediaCatalog.kt:1761`), che nessun codice di produzione chiama.
  - Il filtro della Libreria lo legge (`core-library/.../contract/Timeline.kt:140`) ed e' fra i filtri rapidi
    (`feature-library/.../library/LibraryParts.kt:213`, `:224`): mostra sempre "Nessun documento" (`:287`).
  - Il tipo "documenti" del parser (`core-search/.../parse/TypeRules.kt:28`) diventa un vincolo (`core-search/.../contract/Indexes.kt:61`): zero
    risultati, anche dove il testo da solo troverebbe lo scontrino.
- **Atteso:** D34: bit acceso dal testo letto (6 righe o 200 caratteri) o dal vettore vicino alle frasi prototipo, a lotti di 500.
- **Correzione:** il calcolo del bit dopo `saveText` e `saveVectors` (o in `LibraryMaintenanceWorker`) con `setDerivedFlags` e la soglia di P9
  (`EvalDocuments`). Finche' manca: togliere "Documenti" dai filtri rapidi e trattare "documenti" come parola, non come vincolo.

#### CE-2 · Togliere un chip, cambiarne il luogo o "Cerca come parola" butta le parole libere della query
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca, ricontrollato).
- **Flusso:** cerco "scontrino Esselunga ieri", tocco il chip "Ieri" → "Togli il filtro": il testo letto non cerca piu' "scontrino" ne' "esselunga".
- **Percorso:** `SearchController` rilancia la query con i chip rimasti (`feature-search/.../SearchController.kt:191-215`). Con `editedChips` non nullo
  `LocalSearchEngine.search` prende parole ed esatte solo dai chip `Word` (`core-search/.../engine/LocalSearchEngine.kt:73-76`), che il parser crea solo
  per le virgolette (`core-search/.../parse/ItalianQueryParser.kt:43`). Le parole libere (`ParsedQuery.words`) si perdono; resta solo il contenuto.
- **Atteso:** §2.5, E-Q10: modificare un chip cambia solo quel filtro.
- **Perche' i test passano:** `InMemorySearchEngine` fa lo stesso (`core-search/.../testing/InMemorySearchEngine.kt:60-63`), quindi la suite di parita'
  non lo vede; `SearchControllerTest` controlla solo i chip passati al motore.
- **Correzione:** con `editedChips` non nullo, `parsed.words` e `parsed.exact` restano (meno cio' che un chip tolto aveva consumato) piu' i `Word` dei chip.

#### CE-3 · L'ordine scelto fuori casa vale per una ricerca sola e non si ricorda
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca). Stessa radice di LT-1.
- **Percorso:** `onSetOrder` rilancia solo l'ultima ricerca con `orderOverride` (`feature-search/.../SearchController.kt:217-223`); le ricerche nuove leggono
  `orderStore.order.value` (`:277`), che con il registro agganciato segue `searchOrder` (fermo senza l'ora del NAS) e ignora la scelta locale
  (`app/.../ai/SearchScreenPorts.kt:32-43`).
- **Atteso:** D48, F68: la scelta vale subito e si ritrova sul tablet.
- **Correzione:** come LT-1; in piu' `order` preferisce la scelta locale finche' lo store non la raggiunge.

#### CE-4 · Il viewer aperto da Cerca non segue la vista viva (E-Q13)
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca, ricontrollato).
- **Flusso:** apro un risultato, poi "Nascondi" dal ⋮ (o la foto sparisce altrove): nel viewer resta nel pager; la griglia dietro la toglie dopo.
- **Percorso:** `SearchHostViewModel.itemsOf` da' al viewer `view = MutableStateFlow(results)`, mai riemesso (`app/.../navigation/SearchNavigation.kt:80-86`).
  Il viewer reagisce solo se `items.view` emette (`feature-viewer/.../ViewerOverlay.kt:283-296`, E-V6). `LiveResults.follow`
  (`core-search/.../engine/LiveResults.kt:22-30`) alimenta solo la griglia.
- **Atteso:** E-Q13 ("anche con il viewer aperto") e il commento di `LiveResults`.
- **Correzione:** passare al viewer un `StateFlow<TimelineView>` alimentato da `LiveResults.follow`.

#### CE-5 · I media solo-NAS hanno solo il segnaposto sfocato nei risultati (sul tablet, quasi tutti)
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca, ricontrollato).
- **Percorso:** le miniature di Cerca usano `ColumnarTimelineSnapshot.refAt`, che da' solo i riferimenti del telefono e null per il NAS
  (`app/.../navigation/SearchNavigation.kt:66-73`; `core-library/.../index/ColumnarTimelineSnapshot.kt:118-123`). La Libreria usa `thumbRefAt`
  (`app/.../gallery/LibraryStates.kt:209`). Anche `LocalOnlyRefs` scarta ogni `MediaRef.Nas` (`feature-search/.../contract/SearchPorts.kt:72-89`).
- **Atteso:** D28, §2.4: "miniature solo locali (replica e telefono)". Le miniature della replica sono locali e non leggono il NAS.
- **Correzione:** `thumbRefAt` con un caricatore che per i riferimenti del NAS consulta solo la replica (un contesto "solo replica" per
  `NasThumbnailSource`, che per un contesto visibile oggi leggerebbe il NAS).

#### CE-6 · Il filtro finale dei nascosti fa tre letture del database per ogni risultato
- **Gravità:** Sbagliata. **Stato:** confermato il codice (agente di Cerca); i tempi vanno misurati in F53.
- **Flusso:** una ricerca per soli filtri ("video", "foto di ieri", un anno) su 15-100 mila celle.
- **Percorso:** con soli filtri tutti i candidati sono "sicuri" e passano da `data.hidden(...)` (`core-search/.../engine/SearchCore.kt:112-122`). Nell'app,
  per ogni id, `resolve` e due letture di `personalDao`, in fila (`app/.../search/SearchPorts.kt:92-99`). Lo stesso a ogni istantanea nuova in
  `LiveResults.follow` (riga 27).
- **Atteso:** p95 sotto 300 ms con 100 mila elementi (§2.4).
- **Correzione:** letture a blocchi (`WHERE media_id IN (...)`), e in `LiveResults` solo cio' che e' cambiato.

#### CE-7 · La ricerca gira sul thread principale
- **Gravità:** Sbagliata. **Stato:** plausibile (agente di Cerca; serve un profilo con 100 mila elementi, F53).
- **Percorso:** `SearchViewModel` usa `viewModelScope` (`feature-search/.../SearchViewModel.kt:54`); ne' `SearchGraph.engine` ne' `LocalSearchEngine`
  cambiano dispatcher (nessun `Dispatchers` in `core-search/src/main`). Sul principale girano candidati, `CellMap.build`, `ContentScorer.score` (che
  aspetta il pool di `TwoPassIndex.kt:97-102`) e l'ordinamento.
- **Atteso:** Libreria fluida (§5.4) e p95 sotto 300 ms.
- **Correzione:** `withContext(Dispatchers.Default)` in `SearchGraph.engine` o in `SearchController.launch`.

#### CE-8 · Finito lo scaricamento del modello, l'avviso "serve il modello" resta e la ricerca non riparte
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca).
- **Percorso:** la prontezza del motore delle query cambia solo dentro `vector()` (`app/.../search/LazyQueryEmbedding.kt:38`, `:51-54`, `:59`, `:67`);
  l'avviso con "Scarica" non guarda lo stato del modello (`feature-search/.../SearchNoticeItems.kt:93`); `onDownloadModel` non aspetta la fine
  (`SearchController.kt:240-243`).
- **Atteso:** E-Q11: "contenuto appena pronto".
- **Correzione:** quando il 270M diventa `Ready`, il controller rilancia l'ultima ricerca; l'avviso si toglie con il modello pronto.

#### CE-9 · La prima ricerca a freddo aspetta il motore delle query prima di filtri e testo
- **Gravità:** Minore. **Stato:** confermato (agente di Cerca).
- **Percorso:** `LocalSearchEngine.search` aspetta `embedding.vector()` prima del resto (`LocalSearchEngine.kt:83-90`); il motore si carica alla prima
  query, non all'apertura di Cerca (`LazyQueryEmbedding.kt:49-78`); durante `Searching` non c'e' "Sto preparando…" (`SearchScreen.kt:129-140`).
- **Atteso:** D35, E-Q11: si carica all'apertura di Cerca; filtri e testo subito, il contenuto appena pronto.
- **Correzione:** precaricare all'apertura di Cerca; rispondere subito con filtri e testo e aggiungere il contenuto dopo.

#### CE-10 · Se il catalogo non c'era alla nascita, la cronologia resta vuota per tutto il processo
- **Gravità:** Minore. **Stato:** plausibile (agente di Cerca; provare con un'installazione appena configurata).
- **Percorso:** `changes()` emette una lista vuota e finisce se `catalog()` e' null (`app/.../search/SearchPorts.kt:72-75`), e `LocalSearchHistory` lo
  prende con `stateIn(Eagerly)` (`core-search/.../history/LocalSearchHistory.kt:35-37`). Dopo l'onboarding nello stesso processo non si aggiorna.
- **Correzione:** `changes()` aspetta il catalogo invece di finire.

#### CE-11 · Con la ricerca ferma o l'indice che non si apre resta "Sto cercando…"
- **Gravità:** Minore. **Stato:** confermato (agente di Cerca).
- **Percorso:** `SearchGraph.ready()` lancia una `CancellationException` (`app/.../search/SearchGraph.kt:115-117`); `SearchController.launch` la rilancia
  (`SearchController.kt:282-284`) e lo stato resta `Searching`. Il commento di `SearchGraph` promette "La ricerca non ha funzionato".
- **Correzione:** un'eccezione che non sia di cancellazione, o `Unavailable(INTERNAL_ERROR)` nel controller.

#### CE-12 · Le foto senza data stanno in cima ai risultati
- **Gravità:** Minore. **Stato:** confermato (agente di Cerca).
- **Percorso:** un giorno non valido vale "oggi" (`core-search/.../engine/Ranking.kt:21-22`): periodo piu' recente e premio di recenza pieno; in
  "Recenti" compaiono per prime sotto "Senza data" (`feature-search/.../SearchGridEntries.kt:68-72`).
- **Correzione:** periodo piu' vecchio e recenza zero.

### 2.6 "Trova simili" (TS)

#### TS-1 · Dopo ogni avvio a freddo "Trova simili" manca dal menu del viewer, finche' non si fa una ricerca
- **Gravità:** Blocca (con un aggiramento che l'utente non conosce: una ricerca qualunque). **Stato:** confermato (agente di Cerca, ricontrollato).
- **Flusso:** app avviata → Libreria → una foto → ⋮: la voce non c'e'. Dopo una ricerca in Cerca compare.
- **Percorso:**
  - `SearchViewerSimilar.availability` cerca la cella in `CellKeys` (`app/.../ai/ViewerPorts.kt:221-224`); senza cella risponde `HIDDEN`.
  - `CellKeys` si riempie solo con `sync()` (`core-search/.../index/CellMap.kt:33-64`), che chiamano solo la ricerca (`SearchCore.prepare`,
    `core-search/.../engine/SearchCore.kt:46`) e `IndexSearchData` (`core-search/.../engine/IndexSearchData.kt:28`, `:41`). `SearchGraph.open()`
    registra solo le invalidazioni (`app/.../search/SearchGraph.kt:133`).
  - Anche dopo una ricerca, un media arrivato dopo non ha la voce fino alla ricerca successiva.
- **Atteso:** D30, E-Q14: la voce c'e' per ogni cella della Libreria (spiegata se senza vettore).
- **Perche' i test passano:** `SearchViewerSimilarTest` chiama `sync()` prima.
- **Correzione:** `keys.sync()` in `SearchGraph.open()` e a ogni cambiamento del catalogo (o `availability` che legge la cella al volo).

#### TS-2 · Ogni rientro nella scheda Cerca rilancia "Simili a"
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca).
- **Flusso:** "Trova simili" su A → i simili → scrivo "mare" → un'altra scheda → torno in Cerca: il campo si svuota e tornano i simili ad A. Una seconda
  richiesta sullo stesso media non fa niente.
- **Percorso:** `findSimilar` non si azzera mai (`app/.../navigation/AppNavHost.kt:355-362`); la scheda compone `SearchRoute` solo se selezionata
  (`app/.../main/MainScaffold.kt:158`), e `LaunchedEffect(findSimilar)` riparte a ogni ingresso con il valore non nullo
  (`feature-search/.../SearchScreen.kt:219`).
- **Correzione:** azzerare `findSimilar` dopo averlo consegnato, o un evento con contatore invece di uno stato.

#### TS-3 · "Trova simili" da un viewer aperto fuori dalla schermata principale non fa niente, e scatta al ritorno
- **Gravità:** Sbagliata. **Stato:** confermato nella struttura (agente di Cerca); da vedere sul telefono: Raccolte → Preferiti → foto → ⋮ → Trova simili.
- **Percorso:** `SimilarRequests` si consuma nel `LaunchedEffect` di `MainDestination` (`app/.../navigation/AppNavHost.kt:356-362`). Il viewer aperto dalle
  Raccolte (`AppNavHost.kt:181`, `feature-library/.../collections/CollectionsNavigation.kt:44`) o dalle Cartelle NAS gira con `MainDestination` fuori composizione: la
  richiesta resta in sospeso e, al ritorno nella schermata principale, l'app salta di colpo a Cerca.
- **Atteso:** D30: la voce funziona dovunque compaia.
- **Correzione:** consumare `SimilarRequests` nella radice (dove vive l'ospite del viewer), chiudere il viewer e andare a Cerca.

#### TS-4 · "Trova simili" non c'e' dalla selezione della Libreria
- **Gravità:** Sbagliata (promessa non mantenuta). **Stato:** confermato (agente di Cerca, ricontrollato).
- **Percorso:** nessuna azione di selezione di `feature-library` la offre; l'unico ingresso e' la voce del viewer (`GalleryGraph.kt:372`, `:632`).
- **Atteso:** D30: "dal viewer (⋮) e dalla selezione".
- **Correzione:** un'azione per la selezione di un solo elemento con la stessa porta (`SimilarRequests`), oppure togliere la promessa dal progetto.

#### TS-5 · Il chip "Simili a" mostra un'icona al posto della miniatura
- **Gravità:** Minore. **Stato:** plausibile (agente di Cerca; provare con un media non in cache e con un solo-NAS).
- **Percorso:** il chip legge `loader.image(key, ref).value` (`feature-search/.../SearchChipBar.kt:117-131`), che per contratto non avvia un caricamento
  (`core-imaging/.../contract/Thumbnails.kt:79-85`); la cella e' esclusa dai risultati, quindi la finestra della griglia (`SearchResultsGrid.kt:98-106`) non la
  chiede mai. Per un solo-NAS il riferimento e' null (CE-5).
- **Correzione:** chiedere la miniatura del chip nella stessa finestra della griglia.

### 2.7 Viewer: testo dal vivo, ritaglio, foto in movimento (VT, VR, VM)

#### VT-1 · Nel viewer dei Nascosti il testo dal vivo non c'e' mai
- **Gravità:** Sbagliata. **Stato:** confermato (tutti e due gli agenti, ricontrollato). Non e' una scelta: D29 dice il contrario.
- **Flusso:** Raccolte → Nascosti → una foto con delle scritte → pressione prolungata o pulsante "Testo": niente righe, e la pressione va al ritaglio.
- **Percorso:** `StoredMediaText.layout` passa da `cellContentKeysOf` (`app/.../search/TextLayouts.kt:86-94`), che usa la condizione `INDEXED`
  (`COALESCE(p.hidden, 0) = 0`, `core-database/.../dao/CellContentDao.kt:52`): un nascosto non ha cella. Lo stesso per la lettura al volo
  (`app/.../ai/ViewerPorts.kt:85-87`, `:96-98`, dove il commento lo dichiara voluto).
- **Atteso:** D29: "Nel viewer dei Nascosti testo dal vivo e ritaglio funzionano".
- **Perche' i test passano:** `ViewerTextOnTheFlyTest` usa solo media visibili.
- **Correzione:** per il viewer, layout e immagine per chiave con una lettura che non escluda i nascosti; nessun'altra superficie cambia.

#### VT-2 · Le righe del testo ritirato restano nel viewer; il pulsante "Testo" non compare se il testo arriva a foto aperta
- **Gravità:** Minore. **Stato:** confermato (tutti e due gli agenti).
- **Percorso:** `OcrLayoutStore.remove` non ha chiamanti (`app/.../search/TextLayouts.kt:63-65`). `IndexReplicaSink.withdraw`, `forgetDevice` e
  `forgetShare` tolgono il testo solo dall'indice (`app/.../ai/AiIndexAdapters.kt:138-151`): il testo di un dispositivo revocato o di un'area persa resta
  nel testo dal vivo, e i file restano su disco. `LiveTextController.bind` carica le righe una volta sola (`feature-viewer/.../livetext/LiveTextController.kt:131-137`).
- **Atteso:** D42, C19: il testo vale con i permessi della share del file.
- **Correzione:** togliere le chiavi da `OcrLayoutStore` insieme alle uscite dall'indice; ricontrollare la sorgente quando l'indice cambia.

#### VR-1 · Un ritaglio salvato dai Nascosti e' nascosto solo su questo telefono, per un po'
- **Gravità:** Sbagliata. **Stato:** plausibile (agente del viewer); dipende da quando gira la sincronizzazione dei dati personali.
- **Percorso:** `CutoutPhoneSaver.link` registra il media e poi mette `hidden = true` in due transazioni (`core-mediastore/.../write/CutoutPhoneSaver.kt:128-146`),
  senza passare da `GalleryGraph.actions.setHidden` (`app/.../gallery/GalleryGraph.kt:487-491`), che prima toglie la cella dall'indice e poi chiama
  `personalChanged()`. Fra le due scritture la cella e' visibile; la voce entra nel registro dei dati personali solo con `adoptLocal` del giro periodico
  (`core-personal/.../sync/NasPersonalSync.kt:129`). Il backup puo' caricare il PNG prima, e il tablet dello stesso account lo mostrerebbe in Libreria.
- **Atteso:** D47, E-V11, E-H1: la foto nuova nasce nascosta, ovunque.
- **Correzione:** la riga nascosta nella stessa transazione di `record`, poi `forget` e `personalChanged()` come `setHidden`.
- **Prova sul telefono:** F66 "da un media nascosto", con il backup subito attivo, poi il tablet.

#### VR-2 · Con meno di circa 306 MB liberi il ritaglio non funziona
- **Gravità:** Minore. **Stato:** confermato (agente del viewer).
- **Percorso:** `EnsuringModelFiles` chiede `ensure` per copiare i 6 MB dell'APK (`app/.../ai/CutoutWiring.kt:61-66`); il gestore pretende il margine
  di 300 MB (`core-models/.../manager/LocalModelManager.kt:124-133`). `NO_SPACE` diventa `MODEL_UNAVAILABLE`
  (`core-ai/.../cutout/MagicTouchCutter.kt:207`), cioe' "Ritaglio non riuscito" e `CUTOUT_FAILED`.
- **Correzione:** un margine piccolo per i modelli dell'APK, e un messaggio di spazio.

#### VR-3 · "Copia" del ritaglio: dopo un riavvio del processo gli appunti puntano a un file sparito
- **Gravità:** Minore. **Stato:** plausibile (agente del viewer).
- **Percorso:** il PNG sta in `cacheDir/share/<token>/` (`app/.../ai/CutoutWiring.kt:228-239`) e la pulizia all'avvio toglie cio' che non e' del processo
  corrente (`feature-integrations/.../share/ShareCache.kt:67-71`).
- **Correzione:** per "Copia" una vita piu' lunga (come le copie consegnate di MC-1 della 5B) o un URI che si ricostruisce.

#### VM-1 · L'animazione d'apertura parte anche se l'utente ha gia' toccato o ingrandito
- **Gravità:** Sbagliata. **Stato:** confermato nel codice (agente del viewer, ricontrollato); l'effetto va visto sul telefono.
- **Flusso:** apro una foto in movimento e subito pizzico o tocco. Molto piu' probabile sui solo-NAS.
- **Percorso:** il tocco chiama `MotionDirector.interruptOpening()` (`feature-viewer/.../page/ViewerPage.kt:267`), che ferma solo un'animazione **gia'
  partita** (`feature-viewer/.../motion/MotionDirector.kt:85-87`). Nessun segno ricorda un tocco durante i 150 ms di attesa (`:80`) o durante l'attesa
  fino a 1,5 s della coda video di un solo-NAS (`:133`); poi `playMotion` parte (`:149`). La foto va a trasparenza zero al primo fotogramma
  (`ViewerPage.kt:224-233`) e il video sta fuori dallo zoom (`:290`): la foto ingrandita sparisce e per circa 1,2 s c'e' il video a pagina intera.
- **Atteso:** D50, E-V14: mai durante un pizzico; un tocco la ferma.
- **Perche' i test passano:** `MotionDirectorTest` (`:124`, `:137`) chiama `interruptOpening()` solo ad animazione in corso; pager e lettore sono finti.
- **Correzione:** un segno "toccata o ingrandita" per pagina, controllato prima di `playMotion` e di nuovo dopo l'attesa della coda.

#### VM-2 · L'interruttore "Animazione delle foto in movimento" si ignora senza dirlo
- **Gravità:** Minore. **Stato:** confermato (agente del viewer).
- **Percorso:** `PersonalMotionAnimationSettings.setEnabled` scrive solo se `personal.motion` c'e' (`app/.../ai/SearchScreenPorts.kt:62-64`), cioe' con il
  registro dei dati personali agganciato (`app/.../nas/NasOrganizer.kt:201-203`). Prima l'interruttore torna su "acceso"; e senza l'ora del NAS la
  scelta resta "da datare" come in LT-1.
- **Correzione:** come `PersonalSearchOrderStore` (`SearchScreenPorts.kt:32-43`), una scelta locale riapplicata quando lo store c'e', e lo stato efficace
  di LT-1.

#### VM-3 · Badge "Movimento" di un solo-NAS: nessun segno mentre legge, e un secondo tocco ricomincia da capo
- **Gravità:** Minore. **Stato:** confermato (agente del viewer).
- **Percorso:** `playFullNow` aspetta la lettura dal NAS, fino a 64 MB a blocchi da 1 MiB (`feature-viewer/.../motion/MotionDirector.kt:155-184`);
  `MotionBadge` conosce solo "in riproduzione" e "non disponibile" (`feature-viewer/.../motion/MotionBadge.kt:62-67`). Un secondo tocco annulla la
  lettura e ricomincia (`MotionDirector.kt:107-108`). Col NAS spento (F71) il tocco non mostra niente finche' SMB non scade.
- **Correzione:** uno stato "carico" sul badge; il tocco ignorato mentre la lettura e' in corso.

### 2.8 Diagnostica e azzeramento (DG, AZ)

#### DG-1 · `AI_SEGMENT_REJECTED`, `AI_VECTOR_DISAGREE` e lo stato dell'indice `ai` non compaiono mai in Diagnostica
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli, ricontrollato).
- **Percorso:** `AiIndexDiagnostics` (`core-index/.../ai/AiIndexDiag.kt:70-101`) non lo istanzia nessuno; `AiModule` registra solo `AiDiagnostics`
  (`app/.../di/AiModule.kt:101-103`). I contatori (`app/.../ai/AiGraph.kt:149`) li riempiono la replica e il pubblicatore, e nessuno li legge.
- **Atteso:** §2.6, E-I20, E-A14, E-A15; `docs/decisioni.md` ("Da collegare (P11)").
- **Correzione:** `@IntoSet AiIndexDiagnostics(stack.ai.indexCounters, …)`, con le voci in attesa lette dal motore del NAS di quel momento.

#### DG-2 · I codici dei modelli e il lavoro `MODEL_FETCH` non arrivano in Diagnostica
- **Gravità:** Sbagliata. **Stato:** confermato (agente dei modelli, ricontrollato).
- **Percorso:** i `ModelCounters` (`core-models/.../LocalModels.kt:52`) non li legge nessuno, e `AiDiagnostics` non li guarda (`app/.../ai/AiDiagnostics.kt:26-64`).
  Nessun `events.code` per `FETCH_FAILED`, `MISMATCH` o `NO_SPACE`: il tasto "Diagnostica" della pagina (`feature-search/.../models/SearchCodes.kt:46`) apre
  una Diagnostica senza il codice. `DiagJob.MODEL_FETCH` ha la sua etichetta ma `ModelFetchWorker` non lo riporta mai (`app/.../work/AiWork.kt:186-207`).
- **Atteso:** §1.4; F46 ("contatore in Diagnostica"), F47 (`AI_MODEL_MISMATCH`).
- **Correzione:** voci dai contatori e dagli stati `Failed` in `AiDiagnostics`; `events.stage` e `jobFailed(MODEL_FETCH)` nel lavoro.

#### DG-3 · `AI_KILLED_MEMORY` non lo emette nessuno
- **Gravità:** Sbagliata (F62 non puo' passare). **Stato:** confermato (agente dei modelli, ricontrollato).
- **Percorso:** il codice e' dichiarato (`core-ai/.../contract/Compute.kt:193`) ed e' nel catalogo; le uscite per memoria le legge solo il contributo
  generico (`feature-diagnostics/.../contributors/BackgroundHealthContributor.kt:183`, `:286`), senza attribuirle al calcolo.
- **Atteso:** E-A16, §1.4, F62.
- **Correzione:** un segno "tappa in corso" e `getHistoricalProcessExitReasons` all'inizio della tappa dopo → `events.code(AI_KILLED_MEMORY)`.

#### DG-4 · `AI_STORAGE_LOW` e `AI_VECTOR_INVALID` si perdono con il processo della notte
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** i contatori del calcolo esistono solo nel processo che ha fatto la tappa (`app/.../ai/AiGraph.kt:310`), e `AiDiagnostics` li legge da li'
  (`AiDiagnostics.kt:47-52`). Per questi due codici non c'e' `events.code` (`AI_GPU_FALLBACK` e `OCR_FAILED` invece passano dagli eventi).
- **Correzione:** `events.code` anche per questi due.

#### DG-5 · Lo spazio non elenca modelli, indice e buffer; `AI_MODEL_MISSING` resta acceso sul tablet
- **Gravità:** Minore. **Stato:** confermato (agente dei modelli).
- **Percorso:** `StorageContributor` mostra solo i totali (`feature-diagnostics/.../contributors/StorageContributor.kt:53-97`), mentre §1.4 chiede modelli e indice. `AiDiagnostics` conta "non
  pronti" anche il ritaglio e il 440M di un non Indicizzatore (`AiDiagnostics.kt:45-46`): sul tablet "models=2" per sempre.
- **Correzione:** voci di spazio per `models/`, `search/` e `ai-buffer/`; contare solo i modelli voluti (come `AppSearchEntryStatus`).

#### AZ-1 · Fra l'arresto dell'AI e quello della galleria, una tappa in corso riapre il 440M
- **Gravità:** Minore. **Stato:** plausibile (agente dei modelli).
- **Flusso:** azzeramento durante "Indicizza ora".
- **Percorso:** prima `aiStopper`, poi la galleria (`app/.../graph/LocalData.kt:150-154`); `stopped` si guarda solo all'inizio della tappa
  (`app/.../ai/AiGraph.kt:292`); dopo `release()` il primo `embed` riapre il motore (`core-ai/.../runtime/LiteRtLmImageEmbedder.kt:66`, `:103-104`);
  `SearchGraph.open()` risponde vero anche dopo `stop()` (`app/.../search/SearchGraph.kt:120-121`). I file li toglie comunque il passo dopo l'attesa
  dei lavori.
- **Correzione:** `AiGraph.stop()` annulla e aspetta la tappa in corso prima di rilasciare; `SearchGraph.open()` falso dopo `stop`.

#### AZ-2 · Dopo l'azzeramento dei dati di un account, la scelta "lettura attiva" resta in memoria e vale per il nuovo account
- **Gravità:** Sbagliata (D41). **Stato:** plausibile (agente dei modelli).
- **Percorso:** `TextReadingMemory` si legge una volta all'avvio del processo (`app/.../ai/TextReading.kt:26-37`); finche' non arrivano le impostazioni
  personali del nuovo account vale la scelta in memoria (`:101-105`); `eraseAccountData(stopGallery = false)` toglie solo il file
  (`app/.../graph/LocalData.kt:142-146`, `:176-179`).
- **Prova sul telefono:** onboarding con un altro account nello stesso processo, NAS irraggiungibile, poi testo dal vivo su una foto non letta.
- **Correzione:** azzerare anche la memoria dell'interruttore nella stessa fase dell'eraser.

### 2.9 Strumenti di debug (DB)

#### DB-1 · L'etichettatura di P9 non mostra nessuna foto
- **Gravità:** Blocca, per lo strumento (solo debug): senza le foto non si possono etichettare le 50 query di F54. **Stato:** confermato (agente di Cerca,
  ricontrollato).
- **Flusso:** Impostazioni → Sviluppo → "Valutazione della ricerca" → Sfoglia o Pozzo: una griglia di riquadri grigi con la data.
- **Percorso:** `AppEvalEnvironment.thumbnail()` risponde sempre null (`app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/AppEvalEnvironment.kt:59`);
  senza miniatura `EvalGrid` disegna il riquadro con il giorno (`debugtools/eval/EvalGrid.kt:51-70`).
- **Atteso:** §5.3: si segnano le foto attese sfogliando la Libreria, con le miniature locali.
- **Perche' i test passano:** l'attrezzatura dei test fa lo stesso (`EvalTestRig.kt:56`, null).
- **Correzione:** `gallery.thumbnails` con `thumbRefAt` e la finestra del caricatore.

#### DB-2 · Il controllo dei vettori di `s25.ps1 -Mode ai` non trova il file vero e non ne riconoscerebbe i record: l'invariante I5 passa sempre
- **Gravità:** Sbagliata. **Stato:** confermato (agente di Cerca per il primo motivo, verificatore principale per il secondo).
- **Percorso:**
  - **Il nome.** Lo script cerca `^vectors-[0-9]+-[0-9]+\.bin$` (`tools/chaos/s25.ps1:848`), ma l'indice scrive
    `vectors-embeddinggemma2_float_v1-<generazione>.bin` (`core-search/.../index/LocalAiIndex.kt:521`; `core-model/.../AiCodes.kt:17`). Nessun file
    trovato: "nessun file di vettori (l'indicizzazione non ha ancora salvato niente?)" e nessun controllo.
  - **Il segno.** Il file e' little-endian e `putInt(0x4D434756)` scrive i byte `56 47 43 4D` (`core-search/.../index/VectorFile.kt:59`, `:64`, `:171`);
    lo script cerca `4D 43 47 56` (`s25.ps1:272`). Anche trovato, ogni record sarebbe "spazzatura": `valid = 0`, `bad = 0`.
  - In tutti e due i casi `bad > allowed` e' falso (`s25.ps1:860`) e il verdetto e' "Verifica dei dati sul telefono: tutto a posto" (`:869`). Un record
    rovinato da un'interruzione non si vede mai.
- **Atteso:** P10, §5.5, invariante 5; F61.
- **Perche' i test passano:** `Invoke-AiSelfTest` e `New-VectorRecord` (`s25.ps1:313-314`) e `tools/chaos/s25-ai-finto.ps1:57` costruiscono record e nomi
  come li aspetta lo script, non come li scrive l'app.
- **Correzione:** il nome dal codice dello spazio (o `^vectors-.+-[0-9]+\.bin$`); il segno letto little-endian; i record di prova costruiti con lo stesso
  ordine dei byte dell'app; un file non vuoto con `valid = 0` deve fallire.

#### DB-3 · La corsa di valutazione non rilascia il motore del testo
- **Gravità:** Minore. **Stato:** plausibile (agente di Cerca; solo debug: ripetere "Corsa" e guardare il PSS).
- **Percorso:** `AppEvalEnvironment` crea un `LiteRtLmTextEmbedder` nuovo a ogni accesso a `embedder` (`AppEvalEnvironment.kt:37-42`); `EvalSession.documentChoice`
  lo usa e non lo rilascia (`debugtools/eval/EvalSession.kt:159-162`). Circa 330 MB per istanza.
- **Correzione:** un'istanza sola, rilasciata a fine corsa.

#### DB-4 · Nessun passo scritto per portare dentro il set OCR o tirare fuori le etichette
- **Gravità:** Minore. **Stato:** plausibile (agente di Cerca).
- **Percorso:** `EvalStore` e `tools/fixtures/generate-ocr.ps1:13` dicono "con adb" verso `noBackupFilesDir/eval/`, che da shell non si scrive senza
  `run-as` (`debugtools/eval/EvalStore.kt:13-15`).
- **Correzione:** un passo di `s25.ps1` (`adb push` in `/sdcard`, poi `run-as cp`; per le etichette `exec-out run-as cat`).

### 2.10 Correzioni dal campo, gruppo A (CA)

Tutte e sette sono legate in `:app` e raggiungono il caso del campo, salvo i due bordi qui sotto (D2, D3, D4, D5 e D7 sono nel §5).

#### CA-1 · D6: a freddo, una modifica fatta ad app chiusa puo' ancora aspettare il giro periodico
- **Gravità:** Sbagliata. **Stato:** plausibile (agente del viewer, ricontrollato il percorso): dipende da quale finisce prima, la lettura del telefono o
  la partenza del backup.
- **Flusso:** `am force-stop`, modifica in Galleria, apertura dell'app a freddo: la lettura di primo piano trova la riga cambiata.
- **Percorso:**
  - `startWhenReady` avvia il NAS e poi il backup in una coroutine a parte (`app/.../gallery/GalleryGraph.kt:677-687`); intanto la lettura del telefono
    parte, e alla sua fine si chiama `afterPhoneRead` (`:694-699`).
  - `BackupGraph.afterPhoneRead` esce subito se il backup non e' ancora partito (`app/.../backup/BackupGraph.kt:399`); `parts` nasce solo in `start()`
    (`:365`).
  - Se la lettura finisce prima di `b.start()`, i lavori non si chiedono e nessuno li richiede: la versione aspetta il giro periodico, come sul campo (B-S7).
  - Il lavoro di fondo non ha il problema: `BackupWorkScheduling.afterPhoneRead` chiama `backup.start()` prima di `nextAll()`
    (`app/.../work/BackupWork.kt:267-268`).
- **Atteso:** D6 (la correzione deve valere proprio a freddo).
- **Perche' i test passano:** `CampoAAppTest.d6_*` (righe 164-178) avvia il backup e chiama `afterPhoneRead` a mano; il collegamento con `GalleryGraph`
  non e' provato, e il registratore dei lavori non registra `quickWork`.
- **Correzione:** in `afterPhoneRead`, `if (!start()) return` come il lavoro di fondo; oppure una chiamata in `startWhenReady` dopo `b.atStart()`.
- **Prova sul telefono:** B-S7 cinque volte (`force-stop`, modifica, apertura), cronometrando la partenza del lavoro delle foto.

#### CA-2 · D1: la condivisione nuova si inoltra con `startActivity` da `onNewIntent`, e la vecchia si chiude comunque
- **Gravità:** Sbagliata. **Stato:** plausibile (agente del viewer).
- **Flusso:** la schermata "Salva sul NAS" di `IMG_mcg_0105` e' aperta; con `am start` o Quick Share arriva `IMG_mcg_0106`.
- **Percorso:** la ricezione e' `singleTop` (`feature-integrations/src/main/AndroidManifest.xml:54`); `ShareReceiverActivity.onNewIntent` rilancia l'intento
  con `NEW_TASK | MULTIPLE_TASK` e poi annulla la schermata vecchia (`feature-integrations/.../receive/ShareReceiverActivity.kt:147-159`), che toglie le
  copie non salvate (`feature-integrations/.../receive/ReceiveViewModel.kt:454-461`). Se Android blocca l'avvio in silenzio (limiti di avvio da sfondo di Android 14 e 15), non arriva
  nessuna eccezione: la condivisione nuova non parte e la vecchia sparisce.
- **Atteso:** D1: "una condivisione nuova non si perde".
- **Perche' i test passano:** `ShareReceiverActivityTest.d1_*` usa Robolectric, che registra l'intento senza regole di task e senza limiti di avvio.
- **Correzione:** trattare l'intento nuovo nella stessa schermata (chiudere lo stage vecchio, `setIntent(intent)`, rifare la schermata): il permesso di
  lettura che porta `onNewIntent` vale, e non servono un task ne' un avvio nuovi.
- **Prova sul telefono:** due `am start -a SEND` di fila; Quick Share con la prima schermata aperta e l'app in secondo piano.

### 2.11 Release (RL)

Nessun difetto proprio della release. Le regole R8 dei motori stanno nelle consumer-rules di `core-ai` (LiteRT-LM, LiteRT, TensorFlow Lite) e ML Kit porta
le sue; `-dontobfuscate`; la release tiene solo `arm64-v8a` (`tools/check.ps1`, passo 7e); `DeveloperTools` di release ha le stesse firme della debug, con
`backupFence()` e `fencedCutouts()` a null e `installSearch` vuota; `mcg-prova` non compare nel codice di release (passo 7b). Valgono pero' in release tutti
i difetti di sopra: con F4-D31 acceso le share vere sono il caso normale, quindi IX-6 e PR-2 pesano di piu' che in debug.

---

## 3. I fake che divergono dal vero

| Fake | Test coinvolti | Differenza dal vero |
|---|---|---|
| WorkManager e JobScheduler | tutti | Sempre finti. Un `KEEP` sul nome di un lavoro ancora in corso non si e' mai visto (IX-1); nessun test esegue `ModelFetchWorker` o `AiComputeWorker` (MO-1, MO-3, MO-5) |
| `AiRig`, `SearchChainIntegrationTest` (`:app`) | integrazione della catena AI | `modelReady = { true }`, fetcher che risponde sempre `NotFound`, `runStage` chiamato a mano con le regole aperte, ora del NAS ancorata. Mai i lavori veri, `AppIndexerControl` o `AiDiagnostics` (IX-1, IX-2, LT-1, DG-1, DG-2) |
| `FakeModelManager` (`core-models/.../testing/ModelFakes.kt:90`) | `SearchSettingsControllerTest`, test della pagina | Parte senza stati (il vero li ha tutti, ritaglio `Absent`: MO-6); nessun giro periodico (MO-3) |
| `FakeModelNetworkGate` | `ModelManagerTest` | Il verdetto lo sceglie il test; il vero non conferma mai i dati mobili (MO-2) |
| `InMemoryModelShelf` | test della sorgente NAS di core-models | In produzione c'e' `NoModelShelf` (MO-4) |
| `FakeIndexerControl` (`feature-search/src/test/.../SearchTestSupport.kt:95`) | test della pagina Ricerca e modelli | Stato, lavoro e spazio li sceglie il test; il vero parte da `Off` e li calcola una volta (IX-2, IX-3) |
| `FakeImageEmbedder` (`core-ai/.../testing/FakeEngines.kt:129`) | `PhoneOnlyAiTest`, `SearchChainIntegrationTest`, `ViewerTextOnTheFlyTest` | `release()` non fa niente, nessun `INIT_FAILED`, nessuna memoria occupata (IX-4, IX-5, AZ-1) |
| `FakeAiCompute` (`core-ai/.../testing/FakeAiCompute.kt`) | banchi di P9 e P10 | Nessuna unita', nessun "rimetti nell'outbox", ogni errore del motore uguale (IX-4, IX-6, IX-7) |
| `FakeSearchPersonalSettings`, `SyncedSearchSettingsTest`, `PersonalWorld.anchored = true` | lettura del testo, ordine, animazione | Una modifica e' subito efficace; il vero, senza l'ora del NAS, la tiene "da datare" e non la mostra (LT-1, CE-3, VM-2) |
| `InMemorySearchEngine` (`core-search/.../testing/InMemorySearchEngine.kt:60-63`) e la suite di parita' | `SearchControllerTest`, suite del motore | Stessa gestione dei chip modificati del vero (CE-2); nessuno dei due produce `NoIndexerForArea` o `TextReadingOff` (PR-1) |
| `SearchViewerSimilarTest.cellKeys` | "Trova simili" | Chiama `sync()` prima; all'avvio il vero no (TS-1) |
| `ViewerItems` vivo dei test del viewer | E-V6, E-Q13 | `SearchHostViewModel.itemsOf` da' un flusso fermo (CE-4) |
| `SearchRefs` dei test di `feature-search` | griglia di Cerca | Un riferimento locale per ogni cella; il vero da' null per i solo-NAS (CE-5) |
| `EvalTestRig.thumbnail` | strumento di P9 | null come il vero: nessuna prova che le foto si vedano (DB-1) |
| `tools/chaos/s25-ai-finto.ps1`, `Invoke-AiSelfTest`, `fake-adb` | prova del modo `ai` di P10 | Nomi dei file e segno dei record come li aspetta lo script, non come li scrive l'app (DB-2); `pidof` vuoto: la morte del processo non e' mai provata |
| `FakeTextReader` dentro `GatedTextReader` | `AiRig`, `ViewerTextOnTheFlyTest` | Le classi di ML Kit non si caricano mai: lo provano solo `MlKitOffAtStartupDeviceTest` e `TextReadingOffDeviceTest` sul telefono. Angolo, scala 1024/2048 e `foldAngle` restano da vedere con ML Kit vero |
| `FakeViewerTextSource`, `FakeViewerTextReader` | `LiveText*Test` | Risposta immediata, nessun limite di 2 s, nessuna scrittura; non distinguono "mai letto" da "letto, vuoto" |
| `FakeViewerSubjectCutter`, `FakeCutoutSaver` | `Cutout*Test` | Maschera 64×64 immediata, mai un errore di memoria; nessun PNG, `FileProvider` o appunti sensibili |
| Ritagliatore "regione connessa" e `FakePhoneMediaWriter` | `CutoutIntegrationTest` | LiteRT, la sessione di MagicTouch e R8 non girano; niente `IS_PENDING`, nomi in conflitto ne' `purgeOrphans` |
| `FakeViewerPlayer`, `FakeMotionVideoLocator` | `MotionDirectorTest`, `ViewerMotionTest`, `MotionNasDirectorTest` | Aspetto, primo fotogramma e fine li emette il test; durata fissa; intervallo scelto dal test. Mai `ClippingMediaSource`, la ricerca del fotogramma chiave, il focus audio, la rotazione, la lettura di XMP e `Mp4Duration` dai file veri (VM-1, E-V16, E-V17) |
| `RecordingJobs` (`app/src/test/.../CampoAAppTest.kt:69-78`) | `CampoAAppTest.d6_*` | Registra solo `versionWork`; `quickWork` e il collegamento da `GalleryGraph` non sono provati (CA-1) |
| `FakeIncomingQueue.cites` | test di D1 | Risponde `false`; il vero legge il diario |
| Robolectric (`nextStartedActivity`) | `ShareReceiverActivityTest.d1_*` | Nessuna regola dei task, nessun limite di avvio da sfondo: la causa del campo non si riproduce (CA-2) |

---

## 4. Flussi provati solo con fake: da mettere per primi nelle prove sul campo (F46–F71)

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere.

1. **Il calcolo vero, di notte e con "Indicizza ora"** (F50, F51, F52):
   - quante tappe girano in una notte e dopo "Indicizza ora" (IX-1);
   - cosa dice la pagina la mattina e durante il lavoro (IX-2, IX-3, IX-4);
   - PSS del processo in primo piano dopo la misura e dopo una tappa (IX-5);
   - sulla share vera, con F4-D31 acceso, dalla seconda notte: arrivano media nuovi? (IX-6).
2. **La lettura del testo** (F69, F56):
   - "Attiva", "Spegni", "Non su questo dispositivo" senza la sessione del NAS (WireGuard spento) e in un processo nuovo dopo "Spegni" (LT-1);
   - l'avviso sul tablet (LT-2);
   - un azzeramento seguito da un altro account nello stesso processo (AZ-2).
3. **Lo scaricamento dei modelli** (F46, F47, F48, F49):
   - HTTPS vero con `If-Range` dietro i rinvii di Hugging Face; uscire dalla pagina durante lo scaricamento (MO-1);
   - i dati mobili (MO-2); "Rimuovi" e poi un giro periodico (MO-3); il lavoro in un processo appena nato (MO-5);
   - i codici in Diagnostica (DG-2); F48 oggi non puo' passare (MO-4); F49 solo con Internet bloccato.
4. **Cerca sul tablet** (F58, F59):
   - le miniature dei solo-NAS (CE-5);
   - "Trova simili" a freddo (TS-1), dalle Raccolte (TS-3), al rientro nella scheda (TS-2), dalla selezione (TS-4);
   - "Nascondi" con il viewer aperto da Cerca (CE-4); E-Q8, gli stessi sicuri dell'S25.
5. **I tempi della ricerca** (F53): una ricerca per soli filtri su 15-100 mila celle, e sul thread principale (CE-6, CE-7). `SearchBenchmark` non c'e'
   ancora.
6. **Testo dal vivo** (F65): ML Kit vero (angolo, posizioni delle parole, zoom 3x), lettura al volo entro 2 s, TalkBack; nei Nascosti (VT-1); un testo
   che arriva con la foto aperta (VT-2).
7. **Ritaglio** (F66, F67):
   - MagicTouch vero nella release (R8, `arm64-v8a`), la copia dell'asset fra i modelli verificati, le sorgenti `OfUri` e `OfFile`;
   - "Salva come nuova foto" con `IS_PENDING`, il backup in `Backup/<dispositivo>/Ritagli/AAAA/MM/` con un account vero, `purgeOrphans` dopo un `kill`;
   - dai Nascosti con il backup subito attivo, poi il tablet (VR-1); con poco spazio (VR-2).
8. **Foto in movimento** (F70, F71): il lettore vero (taglio, fotogramma chiave, audio, dissolvenza); un tocco o un pizzico nei primi 150 ms (VM-1);
   l'interruttore sincronizzato sul tablet (VM-2); i solo-NAS con SMB vero e col NAS spento (VM-3).
9. **E-I12 e la memoria** (F60, F62): oggi falliscono (PR-1, DG-3); vanno corretti prima.
10. **Le interruzioni** (F61): `s25.ps1 -Mode ai` va corretto prima (DB-2), altrimenti I5 passa sempre.
11. **La qualita'** (F54): l'etichettatura senza foto (DB-1); `ZC` e `ZL` sono ancora i valori di partenza di `SearchThresholds`.
12. **Le query del piano** (F57): un chip tolto (CE-2); "documenti" (CE-1, che nessuna prova F copre: da aggiungere).
13. **L'ordine** (F68): sul tablet dopo la sincronizzazione, e con l'ora del NAS non ancorata (CE-3).
14. **La release** (F63, F64): i motori con R8; la pubblicazione delle voci in attesa quando F4-D31 si spegne, anche del testo letto dal tablet (PR-2);
    due Indicizzatori con l'outbox piena (PR-3).
15. **Correzioni dal campo A**:
    - D6 a freddo, B-S7 ripetuta (CA-1);
    - D1 con due `am start -a SEND` di fila e Quick Share con l'app in secondo piano (CA-2);
    - D3 con il file `exif-05` (dialogo di Android, telefono riscritto, data nuova in Libreria); D4 (foto cancellata in Galleria prima del giro); D5 (Wi-Fi
      spento e riacceso con la coda piena); D2 e D7 (le 76 copie non producono copie private; il catalogo giusto subito dopo uno spostamento).

---

## 5. Flussi verificati da capo a fondo

Una riga ciascuno: il percorso e' completo nel codice e i dati arrivano alla schermata (a processo caldo, salvo dove e' detto).

**Porte e collegamenti**
- **`AiStack`:** `AiModule` (in `src/main`, quindi debug e release) costruisce un solo `AiStack` per processo, iniettato nell'`Application`; `graph.aiHooks`
  si legge pigro dal motore del NAS, quindi la parte `ai` dell'indice nasce dentro `NasGraph.start` anche se Hilt costruisce lo stack dopo.
- **`NasGraph.start` senza NAS raggiungibile:** e' solo locale, quindi `runStage` lavora anche offline (`LOCAL_BUFFER_ONLY`, E-A6).
- **Porte del viewer:** `MyCloudGalleryApp.onCreate` installa `ai.viewerHooks` su `gallery.searchViewer` prima di ogni schermata; Libreria, Raccolte,
  Cerca e Nascosti usano la stessa istanza. `ViewerServices.subjectPress` resta null per scelta: la pressione fuori dal testo va al ritagliatore.

**Modelli**
- **Verifica a ogni caricamento (D16):** `ModelFiles.open` calcola dimensione e SHA-256 sullo stesso descrittore che passa al motore; un file alterato
  si toglie e torna in coda (E-A11). `LocalModels.refresh()` all'avvio della galleria.
- **HTTPS:** `Range` con `If-Range`, un `200` riparte da zero, i rinvii seguiti a mano solo verso host ammessi; il cancello di rete rifiuta sempre il
  roaming e in background ammette solo reti non a consumo.
- **Rimozione:** "Rimuovi" ferma uno scaricamento in corso e toglie parziale e file (per quanto dura, vedi MO-3).

**Ruolo Indicizzatore e calcolo**
- **Ruolo dalla pagina:** `OnboardingRolesSettings` salva, lo stato firmato del dispositivo si ripubblica, `fetchNow(ROLE_CHANGED)` parte dal collettore.
- **Buffer:** al piu' 500 MB, svuotato sotto 1 GiB libero, con lo spazio del volume di `noBackupFilesDir`.
- **Ripiego su CPU (E-A12)** con `AI_GPU_FALLBACK` negli eventi; **E-A13:** il ruolo spento a meta' unita' pubblica cio' che c'e' e rilascia.
- **Media del solo telefono (P11b):** elenco da `phoneOnlyWork`, byte dal MediaStore con la recinzione D13 in debug, `CheckedPreparer` (D8), risultati
  solo locali.

**Pubblicazione e ricezione**
- **F4-D31:** sulle share di prova la testa `ai` si pubblica; sulle share vere i risultati vanno in `ai_pending_publish`, e un Indicizzatore li rilascia
  alla prima tappa con la share aperta (per un non Indicizzatore vedi PR-2).
- **Ricezione:** le teste `ai` si leggono nello stesso giro di `IndexSyncWorker`, dopo le `index`, e arrivano all'indice locale e alle righe del testo
  dal vivo su ogni dispositivo, Indicizzatore o no (`wanted` riguarda solo il lavoro). Il tablet cerca con il 270M sugli stessi vettori (E-Q8).

**Lettura del testo**
- **Avvio con la lettura spenta:** `TextReadingStartup.run` prima di `super.onCreate()`; `GatedTextReader` e' pigro e non carica ML Kit;
  `MlKitInitProvider` tolto dal manifest (`core-ai/src/main/AndroidManifest.xml:36-38`), controllato da `check.ps1` e da `verifyReleaseManifest`.
- **Esclusione del dispositivo:** stato `OnForAccount(thisDevice = false)` → `onTurnedOff` → memoria salvata (e' locale: non dipende dall'ora del NAS).
- **D42:** il testo degli altri arriva all'indice e alle righe del viewer senza guardare la lettura locale; il viewer lo legge senza ML Kit.

**Cerca**
- **Parser:** date relative, mesi, anni, Pasqua e Pasquetta (Meeus), Capodanno a cavallo d'anno, stagioni con "scorso", date impossibili corrette e dette.
- **Ordini:** "Recenti" e "Pertinenti" con la formula del §2.4; l'apertura nel viewer segue l'ordine mostrato (`OrderedTimelineView`).
- **Cronologia:** al piu' 50 voci, una per testo, "Simili a" non registrato, "Cancella" e "Cancella tutte" anche dalle Impostazioni.
- **Nascosti esclusi:** candidati dall'istantanea, filtro finale con i membri nascosti, luoghi dalle sole celle visibili, completamenti mai dal
  vocabolario, refusi detti solo se trovano una cella visibile, "Trova simili" vuoto per un nascosto.
- **Nessuna lettura del NAS durante la ricerca:** le miniature di Cerca sono solo del telefono (anzi troppo: CE-5).

**Viewer**
- **Testo dal vivo dall'indice:** `IndexViewerTextSource` → `StoredMediaText` → `OcrLayoutStore`, scritto dal calcolo e dalla replica; maniglie da 48 dp,
  appunti sensibili, foglio "Testo nella foto".
- **Lettura al volo:** `GatedViewerTextReader` → `ViewerOcrImages` → `keepReadText` → outbox `ai`; mai chiamata con la lettura spenta; un testo vuoto
  si salva come "letto, niente".
- **Arbitro della pressione:** video esclusi, un secondo dito o un movimento annullano, la pressione non fa piu' partire il video; con la selezione aperta
  pager, trascinamento e doppio tocco sono fermi.
- **Ritaglio:** `CutoutRuntime`, Copia, Condividi, "Salva come nuova foto" in `Ritagli/`; la sorgente "Ritagli" dall'onboarding e una volta con
  `offerCutoutsOnce`; il backup la carica in `Ritagli/AAAA/MM` (cartella esatta).
- **Foto in movimento:** badge, voce del menu, un solo decoder, `NasMotionSource` con la cache `cacheDir/motion/`; interruttore nelle Impostazioni della
  Libreria con `SyncedMotionSettings`.

**Correzioni dal campo, gruppo A**
- **D1:** coda tutto-o-niente in una transazione; `discard` non tocca una condivisione citata dal diario (per l'inoltro vedi CA-2).
- **D2:** il guardiano di sottofondo solo per i media che il telefono ha avuto (`BackupQueries.onlyCommonCopies`), con `guardianHasWork` sulla stessa query.
- **D3:** la correzione conta le righe di ogni versione, chiede il dialogo, riscrive il telefono e rilegge i metadati dai byte corretti
  (`incomingQueue.metadata` legato da `IntegrationsGraph.install()`).
- **D4:** "persa prima del backup" scritta nella transazione della lettura del telefono che vede sparire un candidato.
- **D5:** `INCOMING_RETRY_WORK` senza ritardo su rete non a consumo, `awaitNasReady`, il quarto d'ora solo per la ripresa che non trova il NAS.
- **D6:** la lettura di primo piano chiede `nextAll` con `quickWork` e `versionWork` (per l'avvio a freddo vedi CA-1).
- **D7:** `MoveCompletions` → `CatalogMoveCompletions` legato in `NasOrganizer`, chiamato dopo `PUBLISHED` e prima del cestino.

**Azzeramento e release**
- **Azzeramento:** `aiStopper` (AI e ricerca) prima della galleria, poi buffer, `search/`, "non supportati", memoria della lettura, battito; i modelli solo
  nell'azzeramento completo; tabelle `ai_*` e cronologia con il database; `motion/` con `cacheDir`.
- **Release:** vedi §2.11.

---

## 6. Note

**Scartati dopo la verifica:**
- **I non Indicizzatori non ricevono le teste `ai`.** No: `wanted` serve solo alle unita' del lavoro (`app/.../nas/NasAi.kt:106`); sincronizzazione e
  destinazione sono legate per tutti.
- **`runStage` senza NAS raggiungibile blocca E-A6.** No: il motore del NAS nasce in locale.
- **Il ritaglio di un solo-NAS senza l'HQ della replica non funziona.** No: il viewer porta l'originale in `NasOriginalStore` e `CutoutSources` lo usa.
- **`incomingQueue.metadata` non legato (D3), `quickWork` vuoto (D6), `moves` che non arriva all'esecutore (D7).** No: legati da `IntegrationsGraph`,
  `JobSchedulerBackupJobs` e `NasOrganizer` → `NasOpsWiring`.
- **La cache `motion/` sopravvive all'azzeramento.** No: l'eraser svuota `cacheDir`.
- **ML Kit parte da solo in release; doppio spegnimento a ogni cambio.** No: manifest e controlli; il collettore ignora `Disabling`.
- **Un codice emesso ma assente dal catalogo della Diagnostica.** Nessuno (il contrario si': DG-1, DG-2, DG-3).
- **Le date e le feste del parser.** Tabelle e algoritmo corretti.

**Scelte e non difetti:**
- F4-D31 acceso anche in release: sulle share vere i dati `ai` restano locali e in attesa (Q7).
- Il motore delle query che si carica alla prima query invece che all'apertura di Cerca e' scritto in P11 come scelta; contraddice D35 ed E-Q11, per
  questo e' CE-9.
- Punti aperti dichiarati da P11 e P11b, ma visibili all'utente o promessi dalle prove, quindi riportati come difetti: lo scaffale dei modelli (MO-4), il bit
  `DOCUMENT` (CE-1), `IndexerAreas` (PR-1). Restano solo dichiarati, perche' non visibili: la selezione multipla in Cerca e `SearchBenchmark` (F53).

**Non letti in dettaglio**, quindi non dati per verificati:
- l'interno dei motori (LiteRT-LM, la sessione di MagicTouch, la geometria delle righe di ML Kit): li provano i test sul dispositivo di P4 e P13;
- la taratura delle soglie (`SearchThresholds`) e la qualita' dei risultati: e' F54;
- `IndexSyncEngine` e `AiShareSync` passo per passo (attestazione, fusione, revoca, area persa): li coprono i banchi di P2 e P10;
- i layout e gli screenshot delle schermate della Fase 6.
