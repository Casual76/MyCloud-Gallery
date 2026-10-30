# Verifica funzionale: Fase 8, Persone (commit `e9086269`, 30/10/2026)

**Stato (30/10).** Le 26 voci sono nuove: nessuna e' ancora corretta nel codice. Ne risultano 1 che blocca, 9 sbagliate e 16 minori; 18 sono confermate nel
codice e 8 plausibili (serve il telefono o un test, descritto nella voce). La verifica dei fake (§3) ha trovato dieci divergenze, quattro delle quali
nascondono voci di questo documento (CE-1 e CE-2, AT-1, CA-3 e CA-4, RI-1). I flussi del §4 sono da provare sul telefono (F131-F146), insieme a quelli di P13 non ancora fatti (F94-F110, con le
correzioni del §4).

**La domanda.** Ogni funzione della Fase 8 (Persone) promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release?
Questa non e' una revisione di sicurezza: quella sta in `docs/review/8-fase8.md` (altro revisore, in parallelo) e qui non e' duplicata.

**Come.** Ho letto il codice committato, senza eseguire niente: niente Gradle, telefono, emulatori, `adb`, NAS o rete. Per ogni flusso dell'utente ho seguito
questa catena: schermata o tasto → controller/ViewModel → porta → oggetto legato in `:app` (Hilt, `FacesWiring`, `FacesGraph`, `FacesModule`,
`GalleryGraph`, `work/`, `src/debug` e `src/release`) → codice che tocca NAS, telefono o database → risultato che torna alla schermata. In piu' ho
confrontato i fake dei test (`core-faces/.../testing/`, `feature-people/src/test` e le prove di `app/src/test/.../faces/`) con le implementazioni vere.
Due sotto-agenti (sonnet, sola lettura) hanno letto in parallelo, con lo stesso metodo: (a) attivazione, calcolo, raggruppamento, Diagnostica e Nascosti;
(b) gestione, ricerca, album smart, "Disattiva e cancella" e debug/release. Le voci "Blocca" e le "Sbagliata" piu' pesanti le ho ricontrollate nel codice
(CE-1, CE-2, AT-1, AT-2, CA-1, CA-2, CA-3, RI-1, DR-1); ho anche ricontrollato CA-4, CA-5, CE-5, RG-1 e DG-2. Delle altre riporto lo stato che hanno dato
loro, ripulito di cio' che il codice ha smentito (§6, "Scartati").

**Il modello dei difetti** e' quello di `docs/review/funzionale-7.md`:
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo o con la chiusura della pagina;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object, o legata all'oggetto sbagliato;
- un lavoro che finisce a vuoto, o che nessuno programma;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, `src/debug` e `src/release`, le recinzioni D12, D30, D34, F4-D31) che rompe una funzione.

**Perimetro.** La Fase 8 cosi' come P13 l'ha integrata in `:app` (P0-P11; P12 e' il banco delle interruzioni), la correzione O1/O2/O3 e il montaggio di
Persone dopo il primo disegno (pacchetto "Avvio e pagine istantanei", merge `127631ac`):
- Raccolte → Persone sempre visibile; da spenta: spiegazione, consenso e attivazione per account (P7);
- calcolo dei volti (chi lo fa, regole delle attivita', progresso, stima, "Analizza ora", ripresa, solo sulle anteprime);
- raggruppamento incrementale e stabile (P5) e la pagina Persone, con l'ultimo stato o lo scheletro;
- gestione: nomi personali sincronizzati (E-F5), unisci, dividi, correggi, nascondi, pagina persona, viewer;
- ricerca per persona e album smart con persone (P10);
- "Disattiva e cancella" (P8) da ogni ingresso, e la riattivazione dopo;
- Diagnostica dei volti (codici di P13);
- Nascosti: che la funzione non si rompa (il dettaglio di sicurezza e' dell'altro revisore).

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravita':**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `core-faces/...` sta per
`core-faces/src/main/kotlin/dev/pampa/mycloudgallery/core/faces/...`; `feature-people/...` per
`feature-people/src/main/kotlin/dev/pampa/mycloudgallery/feature/people/...`; `core-search/...` per
`core-search/src/main/kotlin/dev/pampa/mycloudgallery/core/search/...`. I numeri di riga sono quelli di `e9086269`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Attivazione e sezione delle Raccolte (AT) | 0 | 2 | 1 | 3 | 2 | 1 |
| Calcolo dei volti e lavori (CA) | 0 | 3 | 4 | 7 | 5 | 2 |
| Raggruppamento notturno (RG) | 0 | 1 | 0 | 1 | 0 | 1 |
| Gestione e viewer (GE) | 0 | 0 | 3 | 3 | 1 | 2 |
| Ricerca per persona (RI) | 0 | 1 | 0 | 1 | 1 | 0 |
| Album smart con persone (SM) | 0 | 0 | 1 | 1 | 1 | 0 |
| "Disattiva e cancella" (CE) | 1 | 1 | 3 | 5 | 4 | 1 |
| Nascosti (NV) | 0 | 0 | 1 | 1 | 1 | 0 |
| Diagnostica (DG) | 0 | 0 | 2 | 2 | 1 | 1 |
| Debug e release (DR) | 0 | 1 | 1 | 2 | 2 | 0 |
| **Totale** | **1** | **9** | **16** | **26** | **18** | **8** |

**Esito per flusso:**

| Flusso | Esito | Voci |
|---|---|---|
| Sezione Persone nelle Raccolte, da spenta, e attivazione | Sbagliata | AT-1, AT-2 |
| Avviso su un altro dispositivo, esclusione, "Attiva qui", Impostazioni → Persone | Funziona | AT-3 (minore) |
| Calcolo: chi lo fa, regole delle attivita', solo anteprime, ripresa dopo la morte del processo | Funziona | CA-4...CA-7 (minori) |
| Progresso, stima e "Analizza ora" | Sbagliata | CA-1, CA-2, CA-3 |
| Raggruppamento incrementale e pagina Persone (con l'ultimo stato o lo scheletro) | Funziona | |
| Raggruppamento notturno (unioni, esemplari, nascite sotto soglia) | Sbagliata (plausibile) | RG-1 |
| Gestione: nomi, unisci, dividi, correggi, ignora, nascondi, dimentica, copertina, pagina persona, viewer | Funziona | GE-1...GE-3, NV-1 (minori) |
| Ricerca per persona | Sbagliata | RI-1 |
| Album smart con persone | Funziona | SM-1 (minore) |
| "Disattiva e cancella" | **Blocca** (dalla seconda volta) | CE-1, CE-2, CE-3...CE-5 |
| Diagnostica dei volti | Funziona | DG-1, DG-2 (minori) |
| Nascosti nelle schermate di Persone | Funziona | NV-1 (minore) |
| Release (recinzione D34 accesa) | Sbagliata | DR-1, DR-2 |

Funziona 7, Sbagliata 5, Blocca 1 (su 13 flussi).

**Il quadro.**
- **Il cuore regge.** A processo caldo, sulle share di prova e in debug:
  - l'attivazione (consenso, ruolo di Indicizzatore, voce `faces`, generazione, avviso su un secondo dispositivo, esclusione e "Attiva qui");
  - il calcolo, con le regole di `AI_COMPUTE`, una tappa alla volta, il cancello di stato prima di ogni media, la ripresa dalla chiave di contenuto;
  - il raggruppamento incrementale dopo ogni unita' e dopo ogni replica;
  - nomi, unisci, dividi, correggi, nascondi, dimentica, copertina e il viewer, tutti legati ai metodi veri (nessun tasto morto);
  - la ricerca per persona (dizionario riletto a ogni query) e gli album smart (`peopleChanged` entro 20 secondi, "Tieni le foto");
  - il foglio "Disattiva e cancella" alla prima volta, con la ripresa dal diario dopo la morte del processo e l'azzeramento che ferma le persone per prime.
- I difetti stanno in cinque posti:
  - **la cancellazione non si puo' rifare (CE-1)**: dopo una "Disattiva e cancella" finita e una riattivazione, il foglio dice "Fatto ovunque" e non offre
    il tasto; in piu' le attese dei dispositivi e del cestino di rete non vengono riprese dal giro dell'indice come dice P13-7 (CE-2);
  - **la sezione Persone delle Raccolte dice cose false o sparisce**: sparisce da accesa quando non ci sono ancora persone (AT-1), propone di attivare a
    chi ha gia' Persone finche' non e' montata (AT-2), e su un dispositivo che non analizza dice "Sto per riconoscere i volti" (DR-1);
  - **il progresso e' dell'unita', non della libreria (CA-1)**, "Analizza ora" e la stima non esistono (CA-2) e il primo calcolo parte da solo, scavalcando
    notte e carica, mentre la pagina dice "di notte, in carica" (CA-3);
  - **un solo nome senza lettere latine spegne la ricerca per persona (RI-1)**;
  - **il raggruppamento notturno dipende da un lavoro che non aspetta la famiglia pronta (RG-1)**.
- **La release** non ha difetti suoi di R8 o di legami: `AppFacesWriteGate` e' tenuto da `proguard-rules.pro` e provato nel bytecode da `check.ps1`,
  `DeveloperTools.installPeople` e' vuoto in release, nessun pacchetto `testing` entra in `:app`. Resta la conseguenza di D34 (DR-1): con la recinzione accesa
  ogni dispositivo ha le sue persone, e uno che non analizza non ne avra' mai.

**Le cose piu' gravi:**
- **"Disattiva e cancella" non si rifa' (CE-1).** `DefaultFacesEraser` tiene il diario `DONE` per 30 giorni (§2.8 passo 11) e lo toglie solo in `housekeeping`,
  che gira solo dentro `resume`; dopo `DONE` nessuno chiama piu' `resume`. `FacesEraseController.load()` apre in `follow()` se `status != null`, e
  `EraseSheetStates.of` risponde `Done`. Attiva, cancella, riattiva, riapri il foglio: "Fatto ovunque" e nessun tasto; i volti della generazione nuova restano
  sul telefono. Ne' il fake del foglio ne' quello della cancellazione provano un secondo giro (§3).
- **La sezione Persone non e' "sempre visibile" (AT-1, AT-2).** `PeopleSectionStates.active` con zero persone e stato `Off`, `Idle` o `Problem` restituisce
  `Ready(vuoto)`: zero voci, e `CollectionsLayout.sectionOf` scarta il gruppo senza voci. Subito dopo l'attivazione, a processo nuovo prima della prima tappa e
  con una libreria senza volti la sezione non c'e'. Prima del montaggio, invece, `FacesState.Off` fa comparire "Attiva riconoscimento dei volti" a chi ha
  Persone accesa.
- **Il primo calcolo scavalca le regole e dice il contrario (CA-3).** Ogni accensione, "Va bene" e "Attiva qui" accoda `computeNow`, una richiesta
  `UserInitiated(AI_COMPUTE)` che scavalca fascia e carica; la pagina dice "Le foto si analizzano di notte, in carica". Non c'e' nessun "Analizza ora" in nessuna
  schermata, e F94 non si puo' fare come e' scritta.
- **Un nome "?" o "❤" spegne la ricerca per persona (RI-1).** `SearchText.normalize` tiene solo `[a-z0-9]`; il nome vuoto fa lanciare `PersonEntry`; i due
  chiamanti catturano l'eccezione e usano il dizionario vuoto: spariscono tutti i chip "Persona" e la riga "Persone" dei suggerimenti, per tutti i nomi.

---

## 2. I difetti

### 2.1 Attivazione e sezione delle Raccolte (AT)

#### AT-1 · La sezione Persone sparisce dalle Raccolte quando e' attiva ma non ci sono ancora persone
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** l'utente attiva Persone; oppure riapre l'app dopo la morte del processo, prima della prima tappa; oppure la libreria non ha volti (o ne ha meno
  di 50 non assegnati, RG-1).
- **Percorso:**
  - lo stato del calcolo parte da `FacesStatus.Off` a ogni processo (`core-faces/.../compute/FacesComputeEngine.kt:57`) e cambia solo quando una tappa
    parte; a fine tappa senza volti diventa `Idle` (`:213`);
  - `PeopleSectionStates.active` (`feature-people/.../section/PeopleSectionStates.kt:35-51`): con nessuna persona e senza `progressPercent`, `Off`, `Idle`
    e `Problem` (non dei modelli) non entrano in nessun ramo e cadono su `Ready(vuoto)`;
  - `PeopleSections.sectionOf` (`feature-people/.../section/PeopleSections.kt:114`, `:135-160`): `Ready` vuoto produce zero voci (la riga "Tutte le
    persone" c'e' solo con `total > 0`, "Altri volti" solo con `others > 0`);
  - `CollectionsLayout.sectionOf` (`feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/collections/CollectionsLayout.kt:183-185`):
    "Senza voci non c'e' sezione".
- **Atteso:** §2.1 e D25: "da spenta la riga 'Attiva...'"; il commento di `PeopleSections.kt:86` ("Sempre una riga almeno"). Con Persone accesa la sezione
  deve dire sempre qualcosa (un `Problem(ENGINE_FAILED)` compreso).
- **Correzione:**
  - con zero persone, ogni stato che non e' `Idle` diventa `FirstRun(null)`; `Idle` con zero persone una riga "Ancora nessuna persona" che apre la pagina;
  - un test su `sectionOf(Ready vuoto)` (oggi `PeopleSectionStatesTest` prova lo stato, non le righe).

#### AT-2 · Prima del montaggio le schermate di Persone dicono "spenta" o "non trovata" invece di "non ancora letta"
- **Gravita':** Sbagliata (per qualche secondo a ogni avvio a freddo). **Stato:** plausibile (avvio a freddo con Raccolte aperta subito: F133).
- **Flusso:** Persone e' accesa; l'app parte a freddo e si apre Raccolte (o si ripristina una pagina persona dopo la morte del processo).
- **Percorso:**
  - `FacesGraph.live` e' null finche' la galleria non e' partita e i dati personali non sono agganciati (`app/.../faces/FacesWiring.kt:154-157`);
  - `FacesSwitchFacade.state` risponde `FacesState.Off` (`app/.../faces/FacesFacades.kt:131`), e `PeopleSectionSources.sectionState` parte da
    `PeopleSectionState.Off` (`feature-people/.../section/PeopleSections.kt:83-84`): la riga "Attiva riconoscimento dei volti" compare a chi l'ha gia'
    accesa; un tocco apre la pagina di attivazione e "Attiva" risponde `ActivateOutcome.Unavailable` (`FacesFacades.kt:139`, "Persone non e' disponibile
    adesso");
  - `FacesPeopleFacade.person` emette `null` (`FacesFacades.kt:64`) e `PersonPageController` lo legge come `NotFound` (`feature-people/.../page/PersonPageController.kt:62`:
    `seen` e' ancora falso).
- **Atteso:** principio "primo disegno istantaneo" (ultimo stato o scheletro, mai uno stato sbagliato): L297 dichiara la sezione assente, non sbagliata.
- **Correzione:** finche' non e' montata, la sezione resta assente (o mostra la forma ricordata, `InstantShapes`) e la pagina persona resta `Loading`
  (`FacesPeopleFacade` espone "non ancora" distinto da "non c'e'").

#### AT-3 · Un fornitore della sezione per ogni ViewModel delle Raccolte, e i suoi collettori restano per tutto il processo
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `FacesModule.peopleSection` e' `@Provides @IntoSet` senza scope (`app/.../di/FacesModule.kt:160-168`); `CollectionsLayoutViewModel` riceve il
  `Set<CollectionsSectionProvider>` a ogni creazione e `peopleSectionProvider` costruisce `PeopleSectionSources` con tre `stateIn(Eagerly)` su `graph.scope`
  (`app/.../faces/FacesScreenPorts.kt:177-190`). Ogni apertura nuova della scheda aggiunge collettori che non finiscono mai.
- **Correzione:** `@Singleton` sul fornitore (o `SharingStarted.WhileSubscribed`).

### 2.2 Calcolo dei volti e lavori (CA)

#### CA-1 · Il progresso e' dell'unita' (al piu' 200 media), non della libreria
- **Gravita':** Sbagliata. **Stato:** confermato (il valore e' nel codice; l'effetto visivo va visto: F132).
- **Flusso:** primo calcolo di una libreria da 12.000 foto; Raccolte e Impostazioni → Persone.
- **Percorso:**
  - `core-faces/.../compute/UnitRunner.kt:201`: `Running(state.done, unit.items.size, DETECT)`, dove `done` e `size` sono dell'unita' corrente (200 media per
    chiave di contenuto, D16); `:498` e `:510` mettono `Running(analyzed, analyzed, GROUPING/PUBLISH)`, cioe' 100% a ogni fine unita';
  - `FacesWiring.kt:287-289` ne ricava la percentuale; la mostrano la riga della sezione ("Sto riconoscendo i volti: 35%",
    `feature-people/.../section/PeopleSectionStates.kt:39-41`), la lista (`list/PeopleListController.kt:72`) e Impostazioni ("Analizzate 40 foto su 200",
    `activation/PeopleSettingsScreen.kt:163`, `strings_faces_settings.xml:30`).
- **Effetto:** una percentuale a dente di sega (0-100% a ogni unita', 100% durante raggruppamento e pubblicazione) e "su 200" per una libreria da 12.000.
- **Atteso:** §2.1 e §2.3: "Analizzare le tue 12.000 foto".
- **Correzione:** `Running(fatti, totali)` dell'intera libreria (il totale si conta una volta per tappa, non a ogni media: costa una lettura del catalogo, L242).

#### CA-2 · "Analizza ora" e la stima non esistono
- **Gravita':** Sbagliata. **Stato:** confermato (la stima e' nota come L242; "Analizza ora" non e' dichiarato assente da nessuna parte).
- **Flusso:** §1.5 (`docs/fase8-progetto.md:590`): "Primo calcolo: stima 'Analizzare le tue 12.000 foto richiede circa mezz'ora...' e 'Analizza ora'"; F94.
- **Percorso:**
  - nessuna stringa "Analizza ora" in `feature-people`, `app` o `feature-settings` (`grep` vuoto); nessun tasto, nessuna porta nei contratti della pagina;
  - `FacesWorkScheduling.computeNow` (`app/.../work/FacesWork.kt:81`) ha un solo chiamante: `FacesWiring.kt:393`, cioe' il cambio di stato (`FacesGraph.kt:176`);
  - `FacesLive.backlog` (`FacesWiring.kt:284`, `FacesLive.kt:98`) non ha lettori.
- **Effetto:** dopo un'attesa delle regole (notte, carica, Wi-Fi) l'utente non puo' dire "adesso"; F94 ("'Analizza ora' sull'area di prova") non si puo' fare.
- **Correzione:** il tasto (in Impostazioni → Persone, con la stima di `FacesBacklog` e una conferma), oppure togliere "Analizza ora" da §1.5, da P13-7 e da F94.
  **Decisione dell'utente** (§7).

#### CA-3 · Il primo calcolo parte subito, scavalca notte e carica, e la pagina dice il contrario
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** "Attiva" (o "Va bene" sull'avviso, o "Attiva qui") di giorno, a batteria.
- **Percorso:**
  - `FacesGraph.onStateChanged` (`app/.../faces/FacesGraph.kt:176`) chiede `FacesWork.COMPUTE` a ogni passaggio a `On` non escluso;
  - `FacesWorkScheduling.computeNow` accoda il lavoro con `user = true` (`FacesWork.kt:81`, `:180`): `WorkRequest.UserInitiated(AI_COMPUTE, confirmed = true,
    writesToNas = true)`, che per `docs/regole-attivita.md:88-90` "scavalca fascia e carica";
  - la pagina dice "Su questo dispositivo vale da subito. Le foto si analizzano di notte, in carica" (`strings_faces_activation.xml:54`) e la riga della
    sezione "Va avanti da sola, di notte e in carica" (`strings_people_sheets.xml:58`).
- **Effetto:** YuNet e SFace girano sulla libreria intera, a batteria e di giorno, a una richiesta che l'utente non ha fatto ("Attiva" e' il consenso a
  Persone, non a un calcolo pesante). In F104 il calcolo "in carica e a batteria" e' un confronto che l'app non farebbe da sola.
- **Atteso:** §2.3 e D16: "regole di `AI_COMPUTE`"; "Analizza ora" e' la richiesta dell'utente.
- **Correzione:** il primo giro dopo l'accensione segue le regole di sottofondo (`request(user = false)`) e "Analizza ora" (CA-2) e' l'unico a scavalcarle; oppure
  il testo dice "parte subito". **Decisione dell'utente** (§7).

#### CA-4 · Un lavoro che nessuno programma: `FacesWorkScheduling.afterNews`
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `FacesWorkScheduling.afterNews` e `COMPUTE_NEWS_WORK` (`app/.../work/FacesWork.kt:62`, `:86-88`) non hanno chiamanti; `NasWork.kt:382` e
  `AiWork.kt:366` chiamano solo `AiWorkScheduling.afterNews`. P13-7 promette "un giro del calcolo dei volti dopo un giro con novita'".
- **Effetto:** le foto nuove si analizzano solo con il periodico da 6 ore e con le catene dell'attesa.
- **Correzione:** chiamarlo dove `AiWorkScheduling.afterNews` finisce bene e a fine `IndexSyncWorker` con novita'.

#### CA-5 · Dopo "Disattiva e cancella" e una riattivazione nello stesso processo manca il periodico
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `EraseStopWork` fa `cancelUniqueWork(COMPUTE_WORK)` (`app/.../faces/FacesWiring.kt:349-352`); `FacesWorkScheduling.ensure` (`FacesWork.kt:72`) gira solo in
  `MyCloudGalleryApp.kt:205`, all'avvio del processo; la riattivazione chiede solo `computeNow` (`FacesGraph.kt:176`).
- **Effetto:** fino al riavvio dell'app il calcolo delle foto nuove non ha il giro delle 6 ore.
- **Correzione:** `FacesWorkScheduling.ensure(context)` anche nel passaggio a `On` (`onStateChanged`).

#### CA-6 · Una porta mai legata: `mayFetch`
- **Gravita':** Minore. **Stato:** plausibile (NAS spento durante il calcolo: F145).
- **Percorso:** `AppFaceInputs` ha `mayFetch: () -> Boolean = { true }` (`app/.../faces/FacesInputs.kt:54`) e `FacesWiring.kt:251-257` non lo passa; `fetch` inghiotte
  l'`IOException` (`FacesInputs.kt:130`, `:140`, `:148`) e `waiting()` risponde sempre `NO_PREVIEW` (`:179`). `InputWait.NAS` ("In attesa del NAS") nasce
  solo da una pubblicazione che fallisce (`UnitRunner.kt:512`, `nasDown`).
- **Effetto:** con il NAS spento ogni media di un'unita' ritenta lo scaricamento e il motore vede `NO_PREVIEW`; la pagina dice "In attesa delle anteprime"
  invece di "In attesa del NAS".
- **Correzione:** legare `mayFetch` al cancello di rete delle regole, o propagare l'`IOException` come dice il contratto di `FaceMediaComputer`.

#### CA-7 · Un archivio che non si apre (Keystore, versione piu' nuova) si legge "poco spazio libero"
- **Gravita':** Minore. **Stato:** plausibile (una release su un telefono con un Keystore che non crea la chiave: non provabile a mano senza quel telefono).
- **Percorso:** `FacesStores.open` distingue `KEYSTORE`, `NEWER_DATABASE` e `STORAGE` (`core-faces/.../store/FacesStores.kt:187-217`);
  `ComputeStoreOpen.Unavailable(reason)` (`compute/FacesComputeParts.kt:48`) arriva a `FacesComputeEngine.kt:116` che lo butta in `FacesStop.STORE_UNAVAILABLE`, e
  `statusFor` lo mostra come `Waiting(STORAGE)` (`:224`): "In attesa: poco spazio libero sul telefono" (`strings_faces_settings.xml:43`). Nessun codice di Diagnostica.
- **Correzione:** passare il motivo (`FacesUnavailable`) a `statusFor` e a un codice (`FACES_KEY_LOST` o uno nuovo).

### 2.3 Raggruppamento notturno (RG)

#### RG-1 · Il raggruppamento notturno gira in un lavoro che non aspetta la famiglia pronta
- **Gravita':** Sbagliata. **Stato:** plausibile (una notte vera in carica: F137). E' un difetto del lavoro, comune ai viaggi e agli smart della Fase 7 (VG-1).
- **Flusso:** il telefono e' in carica di notte; `LibraryMaintenanceWorker` parte in un processo nato per lui.
- **Percorso:**
  - le persone nascono a fine unita' solo se i volti CORE non assegnati sono almeno 50 in piu' dell'ultima volta (`core-faces/.../grouping/FastGrouping.kt:35`,
    `contract/Grouping.kt:215`); sotto quella soglia nascite, unioni ("stessa persona a eta' diverse") ed esemplari aspettano `nightly` (`FastGrouping.kt:46-58`);
  - `nightly` gira solo da `LibraryMaintenanceWorker.maintain` (`app/.../work/GalleryWork.kt:184`, `gallery.runIfActive { faces.nightly(FACES_BUDGET) }`),
    in fondo, dopo le tre manutenzioni del telefono e quelle degli album;
  - `doWork` (`GalleryWork.kt:149`) non chiama `gallery.awaitReady(...)`, a differenza di `IndexSyncWorker` (`NasWork.kt:365`), di `FacesComputeWorker` (`FacesWork.kt:206`) e dei
    lavori dell'AI; `runIfActive` risponde null se la famiglia non e' ancora pronta (`GalleryGraph.kt:458-467`, `:902-904`), e **D-7** (`GalleryGraph.kt:469-475`) dice proprio che
    all'avvio lo stato "non configurato" dura un attimo.
- **Effetto:** in un processo nato dal lavoro la manutenzione puo' finire in qualche decimo di secondo con `Result.success()`, senza nascite, unioni ne' esemplari;
  la libreria piccola (meno di 50 volti nuovi) non ha persone, e quelle spezzate fra eta' diverse non si uniscono, finche' una notte non va bene.
- **Atteso:** §2.5 e D21 (le nascite e le unioni di notte).
- **Correzione:** `gallery.awaitReady(COLD_START_WAIT)` all'inizio di `LibraryMaintenanceWorker.doWork`; e il raggruppamento notturno dei volti dopo la tappa del calcolo,
  non in coda alle altre manutenzioni (o con un lavoro suo).

### 2.4 Gestione e viewer (GE)

#### GE-1 · I nomi e le unioni non partono "dopo 30 secondi"
- **Gravita':** Minore. **Stato:** plausibile (due dispositivi, share di prova, con il cronometro: F140).
- **Percorso:** `PeopleEditor` accoda soltanto (`core-faces/.../people/PeopleEditor.kt:75`, `publisher.stage(FacesChange.People(edits))`); `FlushReason.DEBOUNCE` ("30 s dopo
  l'ultima modifica", `sync/NasFacesPublisher.kt:133`) non ha chiamanti per i volti (gli album hanno `watchPending`, `app/.../albums/AlbumsGraph.kt:251-268`). Si pubblica
  in `syncRound` (`FacesGraph.kt:210`), a fine unita' (`UnitRunner.kt:512`) e di notte (`FacesGraph.kt:240`).
- **Effetto:** un nome dato sull'S25 arriva sul tablet al prossimo giro dell'indice dell'S25 (apertura con almeno 15 minuti dall'ultima verifica, o 6 ore) piu' quello
  del tablet. F96 ("dopo un giro dell'indice") regge; il "30 s" dichiarato in `NasFacesPublisher` e nel progetto non c'e'.
- **Correzione:** `watchPending` anche per `publisher.pending` (nel `afterMount` di `FacesLive`), come per gli album.

#### GE-2 · "Controlla le foto" nasconde l'errore: "Non e'..." o "Ignora" falliti sembrano riusciti
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `FaceReviewController.apply` chiude con `FaceReviewUi(Done, message = failure...)` (`feature-people/.../review/FaceReviewController.kt:104`); `FaceReviewRoute`
  esce con `onBack()` appena lo stato e' `Done` (`review/FaceReviewRoute.kt:64-68`), prima che il messaggio si legga.
- **Effetto:** con Persone non piu' attiva (cambio di stato nel frattempo) o un volto sparito, tutte le azioni falliscono e la pagina torna indietro come se fosse andata.
- **Correzione:** se `message != null` restare sulla schermata e mostrarlo.

#### GE-3 · Nel viewer il volto di un gruppo senza nome si nomina da solo
- **Gravita':** Minore. **Stato:** plausibile (F142).
- **Percorso:** il tocco su `ViewerFaceChip.Unnamed` apre sempre "Chi e'?" (`feature-viewer/.../info/PeopleInfoController.kt:53`) anche quando il chip porta il gruppo
  (`Unnamed(ref, person)`, `app/.../faces/FacesFacades.kt:185-190`); "Apri la pagina" esiste solo per `Named`; `IsNew` fa `assign(face, PersonTarget.New(name))` (`FacesFacades.kt:209`),
  una persona nuova con quel volto solo, non il nome al gruppo.
- **Effetto:** "Marco" ha 1 foto e le altre dello stesso gruppo restano in "Altri volti" finche' la notte non le unisce o propone l'unione; la pagina del gruppo non si raggiunge dal viewer
  (§2.6: "tocco = pagina persona"; P9 tollera "o Chi e'?").
- **Correzione:** con `person != null` offrire "Dai un nome a questo gruppo" (`rename`) e "Apri la pagina".

### 2.5 Ricerca per persona (RI)

#### RI-1 · Un solo nome senza lettere latine ne' cifre spegne la ricerca per persona e i suggerimenti, per tutti i nomi
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** l'utente da' a una persona il nome "❤", "?" o "Анна" (un nome di soli emoji, simboli o lettere non latine); poi cerca un'altra persona in Cerca.
- **Percorso:**
  - `PersonNames.normalize` accetta qualunque testo di 1-64 caratteri senza controllo (`core-faces/.../contract/PeopleEdits.kt:22-27`);
  - `AppPeopleLookup.dictionary` costruisce `PersonEntry(PeopleDictionary.normalize(it.name), ...)` per ogni nome (`app/.../faces/FacesPeoplePorts.kt:60`);
  - `SearchText.normalize` tiene solo `[a-z0-9]` (`core-search/.../contract/SearchText.kt:31`), quindi "❤" o "Анна" dicono `""`;
  - `PersonEntry.init` esige `name.isNotEmpty()` (`core-search/.../contract/People.kt:56`) e lancia dentro il `map`;
  - `LocalSearchEngine.peopleDictionary` (`core-search/.../engine/LocalSearchEngine.kt:199-209`) e `LibrarySuggestions.peopleDictionary` (`.../LibrarySuggestions.kt:113-120`) catturano
    l'eccezione e rispondono `PeopleDictionary.EMPTY`.
- **Effetto:** spariscono i chip "Persona", "Marco e Anna", "Marco 2024" e la riga "Persone" dei suggerimenti, per **tutte** le persone, senza un errore (il nome resta testo da cercare).
  Un'emoji per la nonna e' un nome che una famiglia usa.
- **Atteso:** E-F36 e D26: la ricerca per nome; un nome non e' mai un motivo per perdere gli altri.
- **Correzione:** in `AppPeopleLookup.dictionary` scartare con `mapNotNull` i nomi che normalizzano a vuoto (o proteggere ogni voce); in `PersonNames` o nella normalizzazione dei nomi di
  ricerca dire se un nome non e' cercabile; un test con "❤", "?", "Анна" e un nome normale accanto.

### 2.6 Album smart con persone (SM)

#### SM-1 · "Crea album smart" su una persona senza nome apre un editor senza la regola
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `PersonPageController.stateOf` offre `SMART_ALBUM` quando `card.photos > 0`, anche senza nome (`feature-people/.../page/PersonPageController.kt:90`);
  `AppPeopleActions.createSmartAlbum` senza nome fa `nav.openSmartEditor("")` (`app/.../navigation/PeopleNavigation.kt:136-139`), un editor vuoto.
- **Atteso:** §2.7: "titolo = nome, regola = quella persona" (le scelte di `PeopleChoices` sono solo persone con nome).
- **Correzione:** nascondere la voce finche' la persona non ha un nome, o chiedere prima il nome.

### 2.7 "Disattiva e cancella" (CE)

#### CE-1 · Dopo la prima cancellazione, il foglio non la offre piu': dice "Fatto ovunque"
- **Gravita':** **Blocca** (dalla seconda volta in poi). **Stato:** confermato.
- **Flusso:** Impostazioni → Persone → "Disattiva e cancella" (tutto finito); poi "Attiva" di nuovo; poi di nuovo Impostazioni → Persone → "Disattiva e cancella".
- **Percorso:**
  - la riga si offre con Persone `On` (`feature-people/.../activation/PeopleSettingsController.kt:35`) e porta a `FacesEraseDestination` (`app/.../navigation/PeopleNavigation.kt:165`, `:168`);
  - `FacesEraseController.load()` (`feature-people/.../erase/FacesEraseController.kt:56-61`): se `eraser.status.value != null` fa `follow()`, altrimenti `showPreview()`;
  - `DefaultFacesEraser.publish()` (`core-faces/.../erase/DefaultFacesEraser.kt:336-360`) tiene `status` non nullo finche' il diario esiste; il diario `DONE` resta 30 giorni (§2.8 passo 11) e si toglie
    solo in `housekeeping` (`:246-262`, la riga `:256`), che gira solo dentro `resume` (`:155`);
  - dopo `DONE` nessuno chiama piu' `resume`: `FacesGraph.watch` lo chiede solo con `step != DONE` (`app/.../faces/FacesGraph.kt:162`), `syncRound` solo con stato `Erasing` (`:194-195`), `eraseRound` parte
    solo dal lavoro (`:248`), `FacesEraserFacade.resume` non ha chiamanti (`app/.../faces/FacesScreenPorts.kt:67`);
  - `EraseSheetStates.of` (`FacesEraseController.kt:149`): `step == DONE && waiting == null` → `Done`, e `FacesEraseSheet.kt:86` mostra "Fatto ovunque" con il solo tasto di chiusura.
- **Contrasto:** Impostazioni → Persone filtra gia' `step != DONE` per la sua nota (`FacesScreenPorts.kt:124`); il foglio no. Il backend permette il secondo giro
  (`erase()`, `DefaultFacesEraser.kt:119-141`: un diario `done` non blocca).
- **Atteso:** E-F33 ("Riattivazione dopo: generazione nuova") e l'invariante di §2.8: dopo la riattivazione si puo' cancellare di nuovo.
- **Effetto:** il foglio dichiara "Fatto" e i volti e i nomi della generazione nuova restano sul telefono, per sempre (il diario non si toglie mai).
- **Correzione:**
  - in `load()` usare `eraser.status.value?.takeIf { it.step != EraseStep.DONE }` (e confrontare la generazione dello stato con quella corrente);
  - far girare `housekeeping` anche a `DONE` scaduto (dal `watch` al montaggio);
  - un test "attiva, cancella, riattiva: il foglio mostra l'anteprima" (§3: ne' `ScriptedEraser` ne' `FakeFacesEraser` lo provano).

#### CE-2 · "Attesa dei dispositivi" e cestino di rete non vengono mai riprese dal giro dell'indice
- **Gravita':** Sbagliata. **Stato:** confermato (il percorso; l'effetto dipende da quanti riavvii fa il processo, plausibile sul secondo dispositivo: F136).
- **Flusso:** "Disattiva e cancella" sull'S25 con il tablet spento o che torna dopo giorni.
- **Percorso:**
  - finita la parte locale `clearMarker` toglie il segno e lo stato diventa `Off` (`core-faces/.../erase/DefaultFacesEraser.kt:186`, `:233-237`);
  - `eraseRound` risponde `null` per `DEVICES` e `RECYCLE_BIN` (`app/.../faces/FacesGraph.kt:254`: "le riprendono i giri dell'indice", P13-7);
  - ma `syncRound` riprende l'eraser solo se `state is Erasing` (`FacesGraph.kt:194-195`), cioe' mai a quel punto; fa solo `janitor.run` (`:198`), che toglie file dal NAS e non aggiorna diario ne' conferme;
  - le conferme (`PersonalHeadsView.latestValueOf`) si rilegge solo in `resume`, e `resume` riparte solo al montaggio (`watch`, `:162`) o con un nuovo `Erasing`.
- **Effetto:** la nota di Impostazioni resta "in attesa dei dispositivi" (e `RECYCLE_BIN`, e `DONE`) finche' il processo non e' ricreato; con CE-1, poi, il foglio e' bloccato.
- **Atteso:** P8 ("dopo ogni giro dei dati personali") e P13-7 ("li riprendono i giri dell'indice").
- **Correzione:** in `syncRound`, dopo il `janitor`, con un diario non `DONE` chiamare `live.eraser.resume(budget)` in qualunque stato.

#### CE-3 · Il secondo dispositivo non sa che l'altro ha cancellato: torna muto a "Attiva"
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `FacesState.Erasing` si scrive solo dal segno locale (`core-faces/.../switch/FacesStateRules.kt:40`, `DefaultFacesEraser.ensureMarker`); sull'altro dispositivo la voce `off|G` fa `settleLocked`
  → `ACCOUNT_OFF` (`DefaultFacesSwitch.kt:288-300`) e lo stato diventa direttamente `Off`. §2.8 "Gli altri dispositivi" punto 1 dice `Erasing(G)`.
- **Effetto:** Raccolte e Persone tornano a "Attiva riconoscimento dei volti", senza una parola sul perche' i nomi sono spariti.
- **Correzione:** una riga informativa nella sezione ("Persone e' stata disattivata da un altro tuo dispositivo") o aggiornare il documento. **Decisione dell'utente** (§7).

#### CE-4 · Gli album smart con persone possono restare con la regola, senza che il diario riprovi
- **Gravita':** Minore. **Stato:** plausibile (F143).
- **Percorso:** `DefaultFacesEraser.runLocal` riprova `albums.release` solo se lancia (`core-faces/.../erase/DefaultFacesEraser.kt:173`); `AppSmartAlbumsPeople.release` ingoia ogni errore e risponde il numero dei riusciti
  (`app/.../faces/FacesPeoplePorts.kt:185-200`) e `live() ?: return 0` (`:186`) se gli album non sono ancora montati; il passo si segna `DONE` con un album ancora con la regola.
- **Effetto:** "Tieni le foto di adesso come album normali" non vale per quell'album, che resta smart con una regola che non si valuta piu' (la striscia "Le persone non sono attive").
- **Correzione:** restituire il numero dei falliti (o `null` se gli album non sono montati) e far fallire il tentativo.

#### CE-5 · Il tasto "Apri Dispositivi" del foglio non e' mai legato
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `FacesEraseSheet` mostra il tasto solo con `onOpenDevices != null` (`feature-people/.../erase/FacesEraseSheet.kt:207`); `FacesEraseRoute(onOpenDevices = null)` e la rotta non lo passa
  (`app/.../navigation/PeopleNavigation.kt:168`), mentre `SettingsDevicesRoute` esiste (`feature-settings/.../SettingsNavigation.kt:45`).
- **Effetto:** L193 (un dispositivo dismesso tiene ferma la fine) ha la correzione F nel foglio e nessuna strada per arrivarci: l'utente vede "In attesa dei dispositivi" senza il tasto che porta a revocarli.
- **Correzione:** `onOpenDevices = { nav.navigate(SettingsDevicesRoute) }`.

### 2.8 Nascosti (NV)

#### NV-1 · Una persona con nome le cui foto diventano tutte nascoste mostra "Persona dimenticata"
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `ArchivePeople.personNow` risponde `null` se non ci sono celle mostrabili ma ci sono volti (`core-faces/.../people/ArchivePeople.kt:161`); `PersonPageController` con `seen = true` produce
  `Forgotten` (`feature-people/.../page/PersonPageController.kt:62`), "Il gruppo e il nome non ci sono piu'".
- **Effetto:** l'avviso e' sbagliato (il nome c'e' ancora, sono le foto a essere nascoste); succede nascondendo l'ultima foto con la pagina aperta. La funzione dei Nascosti regge.
- **Correzione:** uno stato dedicato ("Tutte le foto di questa persona sono nascoste").

### 2.9 Diagnostica (DG)

#### DG-1 · `FACES_NO_INDEXER` puo' comparire anche quando questo dispositivo e' l'indicizzatore
- **Gravita':** Minore. **Stato:** plausibile (aprire la Diagnostica appena dopo aver acceso "Analizza le foto": F138).
- **Percorso:** `PeopleDiagnostics` guarda solo `accountIndexers` (`app/.../faces/FacesDiagnostics.kt:73`, `:118`); `AppFacesRequirements.indexers` conta i ruoli degli stati firmati e, se `roles()` non c'e' ancora
  (nessuno stato firmato letto), `live` vale 0 anche con `thisDevice = true` (`app/.../faces/FacesPorts.kt:140-149`). L'attivazione invece ammette `accountIndexers == 0 && thisDevice`
  (`core-faces/.../switch/DefaultFacesSwitch.kt:124`).
- **Effetto:** "Nessun tuo dispositivo analizza le foto" (informativo) mentre questo le analizza.
- **Correzione:** `accountIndexers + (thisDevice ? 1 : 0)` o guardare `thisDevice`.

#### DG-2 · Le azioni del §2.10 non sono legate: "Fallo su questo dispositivo", "Configurazione", "Aggiorna", "Diagnostica"
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** ogni voce `PEOPLE` del catalogo ha `actions = null` (`feature-diagnostics/.../catalog/DiagCatalogEntries.kt:326-346`); le azioni del §2.10 sono solo testo della guida.
- **Atteso:** §2.10 ("Messaggio e azione": "Fallo su questo dispositivo", "Configurazione", "Aggiorna", "Svuotalo dal pannello del NAS").
- **Correzione:** legare almeno "Fallo su questo dispositivo" (`indexerRole`) e "Configurazione".

### 2.10 Debug e release (DR)

#### DR-1 · Su un dispositivo che non analizza, la sezione dice "Sto per riconoscere i volti" e con D34 non cambiera' mai
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** build di release sulle share vere (D34 acceso, `HereState.LOCAL_ONLY`); il tablet non e' indicizzatore, l'S25 si'; Persone accesa su tutti e due.
- **Percorso:**
  - sul tablet la tappa si ferma subito con `FacesStop.NOT_INDEXER` (`core-faces/.../compute/FacesComputeEngine.kt:108`), stato `Waiting(NOT_INDEXER)` (`:218`);
  - `PeopleSectionStates.active` lo manda a `FirstRun(null)` (`feature-people/.../section/PeopleSectionStates.kt:42`): "Sto per riconoscere i volti" e "Va avanti da sola, di notte e in carica"
    (`strings_people_sheets.xml:57-58`), mentre il testo giusto esiste ("In attesa: questo dispositivo non analizza le foto", `strings_faces_settings.xml:35`);
  - con la recinzione nessun dato dei volti viaggia (`AppFacesWriteGate`: `TEST_SHARES_ONLY`, `app/.../faces/FacesPorts.kt:86-87`; `FeatureFlags.facesSyncMayUse`), quindi il tablet non riceve mai persone;
  - la riga prima dell'attivazione dice "I nomi viaggiano fra i tuoi dispositivi e restano tuoi" (`strings_people_sheets.xml:51`), falso finche' D34 resta acceso.
- **Effetto:** in release il tablet aspetta per sempre un lavoro che non fara'; in debug (sync attiva) il testo e' falso solo finche' non arrivano le persone dalla replica.
- **Atteso:** D4, D34 e F110: "in release tutto resta sul telefono"; la pagina lo dice con `LocalOnly`, ma non per chi non calcola.
- **Correzione:** `Waiting(NOT_INDEXER)` → la riga che c'e' gia' (e con `LOCAL_ONLY` "qui le persone non si calcolano"); togliere "viaggiano" finche' D34 e' acceso o scriverlo condizionato. **Decisione dell'utente** (§7): in release, un dispositivo non indicizzatore
  puo' calcolare le sue persone per conto suo?

#### DR-2 · Il banco F103 e la ricetta forzata (F109) non hanno il loro ricevitore in debug
- **Gravita':** Minore. **Stato:** confermato (noto: punto 7 di P13 e L243).
- **Percorso:** `tools/chaos/s25.ps1:606` manda `dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO`; nessun ricevitore in `app/src/debug` ne' nel manifest (`grep` vuoto fuori da `tools/` e `docs/`).
- **Effetto:** `s25.ps1 -Mode faces` funziona solo contro l'adb finto; F103 e F109 non si possono fare sul telefono con gli strumenti di oggi.
- **Correzione:** il pannello di debug "Prove della Fase 8" o un `BroadcastReceiver` in `src/debug` (mai in release: `check.ps1` guarda il manifest).

---

## 3. I fake che divergono dal vero

| Fake | Dove si usa | Divergenza |
|---|---|---|
| `FakeFacesEraser` (`core-faces/.../testing/FacesSwitchFakes.kt:110-133`) e `ScriptedEraser` (`feature-people/src/test/.../erase/FacesEraseControllerTest.kt:50-65`) | `FacesEraseControllerTest`, schermate e screenshot del foglio | Il diario non si toglie mai e `erase` risponde `AlreadyRunning` se lo stato esiste; `resume` finisce subito. Nessun test fa "cancella, riattiva, riapri": CE-1 non puo' emergere; nessuno prova che `syncRound` riprenda un diario in attesa (CE-2) |
| `FakeFacesSwitch.activate` (`FacesSwitchFakes.kt:54`) | `feature-people` (attivazione, Impostazioni), `core-faces` | Rifiuta con `accountIndexers == 0`; il vero ammette `accountIndexers == 0 && thisDevice` (`DefaultFacesSwitch.kt:124`). Senza `join`: da `AwaitingNotice` crea una generazione nuova (il vero la conserva); `acceptNotice` e `includeThisDevice` vanno sempre ad `ACTIVE` (il vero `LOCAL_ONLY` con D34); `fresh` fisso a vero (agente) |
| `PeopleSectionsTest`, `PeopleSectionStatesTest` (`feature-people/src/test/.../people/`) | sezione delle Raccolte | Non provano mai `FacesStatus.Off`/`Idle` con Persone attiva e zero persone, ne' le righe di `sectionOf(Ready vuoto)`: AT-1 resta invisibile |
| `FacesWorkSchedulingTest` (`app/src/test/.../work/`) | lavori delle persone | Un lavoro finto con `awaitCancellation`: non copre `FacesComputeWorker`, `computeNow` come richiesta dell'utente (CA-3) ne' `afterNews` (CA-4) |
| `PersonEntry(...)` scritti a mano (`core-search/src/test/.../parse/PeopleParserPropertiesTest.kt:39`) e le sole prove di `AppPeopleLookup` (le sonde dei Nascosti) | parser e ricerca | I nomi sono gia' normalizzati e latini; nessun test passa "❤", "?" o "Анна" da `AppPeopleLookup`: RI-1 non emerge |
| `InMemoryPeople.personNow` (`core-faces/.../testing/InMemoryPeople.kt:160`) | pagina persona, schermate | Da' una pagina anche con le foto tutte nascoste; il vero risponde `null` (`ArchivePeople.kt:161`, NV-1) (agente) |
| `InMemoryPeople.split` (`InMemoryPeople.kt:255`), `contentWith` ANY con una persona dimenticata (`:328`) | gestione, ricerca | Il fake accetta di dividere tutti i volti (il vero rifiuta, `PeopleEditor.kt:152`); con una persona dimenticata risponde vuoto (il vero conta le altre, `ArchivePeople.kt:311-314`) (agente) |
| `InMemoryFacesArchive.addPeople` (`.../testing/InMemoryFacesArchive.kt:106`) | test di `ArchivePeople` con l'archivio finto | Usa `PeopleRecord.merge`; il vero (`store/PeopleTables.kt:31-65`) sostituisce due voci "da datare" della stessa chiave: offline "Bea" poi "Anna" lascia "Bea" nel fake (agente) |
| `InMemoryPeople` in generale | ricerca, album, viewer, pagina | Manca quanto `ArchivePeople` fa e che i test non vedono: lapidi di `assign`/`reject`/`ignore`, annullamento dei "No" in `merge`, copertina solo da un volto mostrabile, ordine del dizionario per foto (agente) |
| `NoFacesEraser.preview()` (digest 0) letto dal foglio (`FacesEraseController.kt:111-114`) | prima del montaggio | Vale `Done` ("Fatto ovunque"): in `:app` capita solo prima del montaggio, con il tasto non ancora offerto; da tenere d'occhio con AT-2 |

---

## 4. Flussi da provare sul telefono: da mettere per primi nelle prove sul campo (F131-F146)

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere. `FacesIntegrationTest` copre il grafo vero con motori finti, a processo caldo, due account e tre dispositivi:
non ha mai riattivato dopo una cancellazione, ne' avviato a freddo con le Raccolte aperte, ne' avuto una libreria senza volti.

1. **F131, seconda "Disattiva e cancella"** (CE-1, CE-2): su `test`, S25 ed emulatore: attiva, "Disattiva e cancella" ("Tieni le foto"), aspetta "Fatto ovunque", "Attiva" di nuovo, lascia calcolare
   finche' c'e' una persona; poi di nuovo Impostazioni → Persone → "Disattiva e cancella". Atteso: l'anteprima. Oggi: "Fatto ovunque". Fare **dopo** F100 (che la cancellazione e' gia' stata fatta una volta).
2. **F132, primo calcolo di giorno a batteria** (CA-3, CA-1, AT-1): su un'installazione pulita (o dopo F131) attiva Persone di giorno, senza carica, e guarda per 10 minuti Raccolte e Impostazioni → Persone: la sezione c'e' sempre?
   la percentuale cresce o fa il dente di sega (0-100% a ogni 200 foto)? "Analizzate N foto su 200"? Parte da sola mentre il testo dice "di notte, in carica"? `ADB shell dumpsys batterystats` per il consumo.
3. **F133, avvio a freddo con Persone accesa** (AT-2): `ADB shell am force-stop`, apri subito Raccolte e riprendi la riga della sezione per i primi secondi (dieci avvii); poi su una pagina persona, `am kill` e ripristino: "Questa persona non c'e'"?
4. **F134, dov'e' "Analizza ora"** (CA-2): in Impostazioni → Persone cerca il tasto e la stima; se la decisione e' toglierlo, riscrivere F94.
5. **F135, nomi senza lettere latine** (RI-1): dai a una persona il nome "❤" (o "?" o "Анна"), poi cerca un'altra persona con nome: i chip "Persona" e la riga "Persone" dei suggerimenti ci sono ancora?
6. **F136, cancellazione con il tablet spento, poi acceso** (CE-2, CE-3): cancella sull'S25 a tablet spento; lascia l'app; accendi il tablet; guarda Impostazioni → Persone sull'S25 finche' passa a "Fatto" senza riavviare l'app
   (`ADB shell cmd jobscheduler run` del lavoro dell'indice); sul tablet, cosa dicono Raccolte e Persone dopo che ha cancellato.
7. **F137, la notte** (RG-1): libreria piccola (meno di 50 volti nuovi) o appena attivata: lascia l'S25 in carica una notte e vedi se le persone nascono e se si uniscono quelle spezzate; leggi in Diagnostica l'esito di
   `LIBRARY_MAINTENANCE` (quando e' girato e come e' finito); se non lascia traccia, il lavoro e' partito senza famiglia pronta.
8. **F138, release su un tablet non indicizzatore** (DR-1, DG-1): APK di release, `test2` o un account di prova con un solo indicizzatore: la riga della sezione sul tablet; il testo prima dell'attivazione ("I nomi viaggiano...");
   Diagnostica poco dopo aver acceso "Analizza le foto" su questo dispositivo.
9. **F139, il periodico dopo cancella e riattiva** (CA-5, CA-4): dopo F131 senza riavviare l'app, `ADB shell dumpsys jobscheduler | grep faces` (nomi dei lavori, non dati): `faces-compute` c'e'?
10. **F140, nomi fra due dispositivi** (GE-1, E-F5): in debug sulle share di prova, rinomina sull'S25 e cronometra quando compare sull'emulatore con l'app in primo piano e con il solo giro dell'indice; in release il nome non deve uscire dal telefono.
11. **F141, piccoli gesti** (GE-2, SM-1, NV-1): "Non e'..." dalla revisione con Persone appena esclusa (l'errore si vede?); "Crea album smart" da una persona senza nome (SM-1); la pagina di una persona con tutte le foto nascoste (NV-1).
12. **F142, viewer su un gruppo senza nome** (GE-3): "Chi e'?" con un nome nuovo su un volto di un gruppo senza nome: quante foto ha la persona nuova, cosa resta in "Altri volti".
13. **F143, album smart al momento di cancellare** (CE-4): "Disattiva e cancella" con "Tieni le foto" appena dopo l'avvio a freddo, e con "Togli la regola": gli album sono diventati normali?
14. **F144, dispositivo dismesso** (CE-5): revoca il tablet (dismesso) prima di cancellare: "In attesa dei dispositivi" e il tasto "Apri Dispositivi" (oggi manca).
15. **F145, NAS spento durante il calcolo** (CA-6): Wi-Fi di casa spento a meta' tappa: quanto impiega ogni media a fallire, che stato mostra la pagina ("In attesa delle anteprime" o "del NAS").
16. **F146, release completa** (R8): APK di release: pagina persona, Dividi, chip del viewer, "<nome> 2024", "<nome> e <nome>", "Crea album smart", "Disattiva e cancella"; la prima esecuzione e la seconda (CE-1).

**I flussi di P13 non ancora fatti (F94-F110), con le correzioni:**
- **F94:** "Analizza ora" non c'e' (CA-2) e il calcolo parte da solo (CA-3): si guarda la tappa dalla Diagnostica e dai file sul NAS.
- **F95:** invariata; prima di "Va bene" l'emulatore parte comunque con `faces-local` (come scritto).
- **F96, F97, F98:** invariate; F96 conta il tempo (GE-1), F97 aggiunge F135, F98 aggiunge SM-1.
- **F100:** farla per ultima di giornata e seguirla subito da F131.
- **F101:** si legge anche con CE-2 e CE-3.
- **F103 e F109:** non si possono fare sul telefono (DR-2, L243): restano al banco dal PC.
- **F104:** il calcolo "a batteria" parte da solo (CA-3): partire dall'APK pulito e misurare il consumo.
- **F110:** DR-1 va letto con questa prova: il tablet non indicizzatore aspetta sempre in release.
- **F105-F108:** nessuna correzione.

---

## 5. Flussi verificati da capo a fondo

Una riga ciascuno: il percorso e' completo nel codice e i dati arrivano alla schermata (a processo caldo, in debug, sulle share di prova, salvo dove e' detto).

**Porte, grafo e lavori**
- **`FacesGraph`:** uno per processo (`FacesModule`, `src/main`: debug e release); monta `FacesLive` quando la galleria e' partita e i dati personali sono agganciati (`FacesWiring.kt:154-157`); le facciate rispondono
  come i null-object prima del montaggio (ma vedi AT-2). I null-object di NAS (`NoFacesRemote`, `NoFacesPublisher`, `NoFacesSync`, `NoFacesLeases`) restano solo senza area privata dichiarata
  (`FacesWiring.kt:224-237`): e' il fallimento chiuso voluto, non una porta dimenticata.
- **Lavori:** `faces-compute` (periodico 6 ore) e le catene `-now`/`-next`; `faces-erase` chiesto da `watch` (diario non `DONE`) e dal passaggio a `Erasing`; replica e pubblicazione dentro `IndexSyncWorker` dopo i dati
  personali (`NasWork.kt:444`); pulizia dei propri file in `IndexMaintenanceWorker`; tutto dentro `runIfActive`. Il raggruppamento notturno e' RG-1.
- **Release:** `AppFacesWriteGate` tenuto da `app/proguard-rules.pro:63-66` e provato nel bytecode da `check.ps1`; `DeveloperTools.installPeople` vuoto in release; `FacesWiring.Testing` solo dai test; nessun
  pacchetto `testing` in `:app`.

**Attivazione e sezione**
- **Attivazione, prima volta:** riga della sezione → `PeopleEntryTarget.parse` → `AppPeopleActions.openActivation` → `FacesActivationRoute` → `FacesActivationController` → `FacesSwitchFacade.activate` → `DefaultFacesSwitch`
  (requisiti: area privata e Indicizzatore; prima lo stato locale, poi la voce `faces`; versione del testo coincidente). Ogni codice di `PeopleEntryTarget` e' instradato (ENABLE/NOTICE, EXCLUDED/PROGRESS/LOCAL/UPDATE/ERASING/MODELS, ALL, OTHERS, PERSON).
- **Avviso su un altro dispositivo, esclusione, "Attiva qui", Impostazioni → Persone:** `PeopleNoticeEffect` una volta per processo; `acceptNotice` si unisce senza generazione nuova; `includeThisDevice`; la voce in `AppNavHost`.

**Calcolo e raggruppamento**
- **Chi calcola e con quali regole:** ruolo di Indicizzatore, modelli verificati, `StageFacesControl` sulle regole di `AI_COMPUTE`, una tappa alla volta (`FacesGraph.kt:221`), cancello di stato prima di ogni media, `FacesStageWatch`.
- **Solo anteprime:** buffer → preparazione dei byte del telefono (`PrepSpec(ANALYSIS)`, messi nel buffer) → blob attestato scaricato → il media aspetta; ogni immagine passa da `ModelInputs.forFaces` (intestazione prima della
  decodifica); mai byte remoti al decodificatore (`FacesInputs.kt`).
- **Ripresa:** dopo la morte del processo si riparte dalla chiave di contenuto gia' `analyzed`; WorkManager riavvia le catene; i motori si rilasciano a fine tappa e a `onTrimMemory`.
- **Release (D34):** con `LOCAL_ONLY` non ci sono prenotazioni (`NasFacesLeases` → `FacesGuard.Closed` → `Role.LOCAL`), il custode e' locale e `syncRound` esce presto.
- **Raggruppamento incrementale:** dopo ogni unita' e, dopo una replica che porta dati nuovi, `FacesGraph.syncRound` esegue `incremental` (chi non e' custode adotta l'istantanea) e `dataArrived` rinfresca le viste.
- **Pagina Persone:** `PeopleInstant` e `KeepInstantShape` salvano la forma solo con `Ready` e fuori dalla selezione; `InstantShapeMemory.install` all'avvio; con l'ultimo stato ricordato un elenco vuoto nei primi 1,5 s e' lo
  scheletro e non "Ancora nessuna persona".

**Gestione, ricerca, smart**
- **Rinomina (pagina e viewer), unisci (pagina, elenco, proposte, "Altri volti"), dividi, ignora, nascondi e mostra, dimentica, copertina, "Altri volti":** controller → `FacesPeopleFacade` → `PeopleEditor`/`ArchivePeople` → `stage`
  e `refresh`; lista, dizionario e `choices()` si aggiornano subito; sul secondo dispositivo il percorso arriva a `archive.addPeople` (`NasFacesSync`) e a `dataArrived` (non provato: F140).
- **Viewer:** `AppViewerPeople.act` copre `Is`, `IsNew`, `IsNot`, `Ignore`; `PeopleOpener` e' legato in `AppNavHost`; `viewerPeople` e' impostato in `onCreate` prima della costruzione pigra di `viewerServices`.
- **Ricerca:** `PeopleBridge` legato da `AiModule`, `AlbumsModule` e `AlbumsWiring`, e alle persone vere da `FacesWiring.mounted`; il dizionario si rilegge a ogni query (un nome appena dato e' cercabile); "e", "o", nome piu' data,
  nome che e' anche un luogo (con l'alternativa del chip); `PeopleGate` fallisce chiuso senza persone.
- **Album smart:** `RulesSmartEngine(people)` e l'interprete con `peopleLookup` (`AlbumsLive.kt:264-268`); `peopleChanged` agganciato in `FacesLive.afterMount` (quiete di 20 s); `AppPeopleChoices` e `openSmartEditorForPerson`.

**"Disattiva e cancella", Diagnostica, Nascosti**
- **Prima volta:** foglio → anteprima → conferma con l'impronta → diario → stop → album → cronologia → voce `off|G` → dati locali; ripresa dopo la morte del processo da `watch` e dal diario; azzeramento dell'account:
  `AppGraph.facesStopper` per primo, `FacesKeys.destroyAll`, `FACES_FILES`.
- **Diagnostica:** ogni codice di `FacesDiag.ALL` e' nel catalogo con titolo e guida (`Phase8DiagCatalogTest`) ed e' prodotto da `PeopleDiagnostics`, `FacesSignalsToDiagnostics` o `FacesStageWatch`; solo numeri e flag.
- **Nascosti:** `PeopleVisibility(gallery.visibleMedia, gallery.hiddenMask)` e' l'unico filtro: conteggi, copertine, dizionario, smart e `choices()`; le viste si rifanno al cambio di istantanea (250 ms).

---

## 6. Note

**Scartati dopo la verifica:**
- **"Il foglio dice 'dal NAS e da tutti i tuoi dispositivi' anche con la recinzione" (agente b).** No: `FacesEraseSheet.kt:114-127` usa `faces_erase_intro_local` e il blocco `faces_erase_local_only` quando `localOnly`.
- **"La pagina persona dice 'non trovata' dopo la morte del processo" (agente a, voce a parte).** E' vero ma e' lo stesso difetto di AT-2 (prima del montaggio): unito li'.
- **`FacesComputeWorker` che non si riaccoda.** No: `Yielded`/`Waiting`/`Stopped` riaccodano la catena; l'unico buco e' CA-4 (le novita').
- **R8.** Nessun riflesso nuovo oltre alle rotte `@Serializable` e a Hilt; `-dontobfuscate`; la regola `AppFacesWriteGate` c'e' e ha la sua prova.
- **Le porte null-object di `FacesWiring`.** Non sono mai quelle in uso con un'area privata dichiarata.

**Scelte e non difetti:**
- **I dati dei volti sulle share vere restano sul telefono (D34, F4-D31).** Nomi, persone, istantanee e la voce `faces` non viaggiano in release; la sezione dice `LocalOnly`. Conviene dirlo anche a chi non calcola (DR-1) e aggiornare la riga "I nomi viaggiano".
- **Dichiarati e non difetti nuovi:** la ri-preparazione dall'originale del NAS non e' legata (L240: un media del NAS senza anteprima aspetta, `FacesInputs.kt:179`); "Altri volti" a un tocco e' l'unica variante (L241);
  `recluster` per una ricetta nuova non e' legato (L243, F109); le cartelle vuote sul NAS dopo la cancellazione (`rmdirIfEmpty`, L191/L246); dispositivi dismessi (L193, ma vedi CE-5).
- **La notte "dopo il calcolo AI della stessa notte" (D16)** e' in realta' il periodico da 6 ore con le sue catene (vedi CA-4).

**Non letti in dettaglio**, quindi non dati per verificati:
- il formato firmato e la fusione dei dati sul NAS (`FacesWire`, `FacesFusion`, `NasFacesSync` oltre all'ingresso delle voci `people`) e le prenotazioni (`NasFacesLeases`): li coprono i test ostili, il banco di P12 e la revisione di sicurezza;
- `FacesCatalogPorts` (attestazione, celle, contenuti spariti) nei dettagli;
- la matematica del raggruppamento (`FastGrouping` contro `ReferenceGrouping`): la copre P5 con la sua parita' bit per bit e P11 con la valutazione;
- i layout Compose, TalkBack e gli screenshot delle schermate di Persone;
- lo strumento di valutazione `FacesEval*` (solo debug, P11) e il banco di P12.

---

## 7. Stato di ogni voce e proposta di correzione (30/10)

Nessuna voce e' corretta. Il pacchetto e' la proposta; ogni correzione porta un test rosso senza la modifica (come per le Fasi 6 e 7).

| Voce | Gravita' | Stato | Pacchetto |
|---|---|---|---|
| CE-1 Seconda cancellazione bloccata | Blocca | confermato | A |
| CE-2 Attese dei dispositivi non riprese | Sbagliata | confermato | A |
| CE-3 Secondo dispositivo muto | Minore | confermato | A (decisione 3) |
| CE-4 Smart non sciolti in silenzio | Minore | plausibile | A |
| CE-5 "Apri Dispositivi" non legato | Minore | confermato | A |
| AT-1 Sezione che sparisce | Sbagliata | confermato | B |
| AT-2 "Attiva" prima del montaggio, "non trovata" | Sbagliata | plausibile | B |
| AT-3 Collettori del fornitore | Minore | confermato | B |
| DR-1 Non indicizzatore e testi in release | Sbagliata | confermato | B (decisione 4) |
| CA-1 Progresso dell'unita' | Sbagliata | confermato | C |
| CA-2 "Analizza ora" e stima | Sbagliata | confermato | C (decisione 2) |
| CA-3 Primo calcolo che scavalca le regole | Sbagliata | confermato | C (decisione 1) |
| CA-4 `afterNews` mai chiamato | Minore | confermato | C |
| CA-5 Periodico dopo la cancellazione | Minore | confermato | C |
| CA-6 `mayFetch` non legato | Minore | plausibile | C |
| CA-7 Archivio che non si apre letto "poco spazio" | Minore | plausibile | C |
| RI-1 Nome senza lettere latine | Sbagliata | confermato | D |
| GE-1 Nomi senza il "30 s" | Minore | plausibile | D |
| GE-2 Errore nascosto in "Controlla le foto" | Minore | confermato | D |
| GE-3 Viewer su un gruppo senza nome | Minore | plausibile | D (decisione 5) |
| SM-1 Editor smart senza regola | Minore | confermato | D |
| NV-1 "Persona dimenticata" con foto nascoste | Minore | confermato | D |
| RG-1 Notte senza famiglia pronta | Sbagliata | plausibile | E |
| DG-1 `FACES_NO_INDEXER` a torto | Minore | plausibile | E |
| DG-2 Azioni della Diagnostica | Minore | confermato | E |
| DR-2 Ricevitore del banco F103 | Minore | confermato | E |

**Pacchetti di correzione proposti** (modello consigliato per ognuno: sonnet; nessuna parte e' di fondazione):
- **A, la cancellazione** (CE-1, CE-2, CE-4, CE-5; CE-3 dopo la decisione): `FacesEraseController.load` e `housekeeping` a `DONE` scaduto, `FacesGraph.syncRound` che riprende il diario, `AppSmartAlbumsPeople.release` con il conteggio dei
  falliti, `onOpenDevices`. Test: foglio "cancella, riattiva, riapri"; `FacesIntegrationTest` con un secondo giro; `syncRound` che chiude l'attesa dei dispositivi.
- **B, la sezione e la prima apertura** (AT-1, AT-2, AT-3, DR-1): `PeopleSectionStates`/`PeopleSections`, "non ancora" in `FacesPeopleFacade`, `@Singleton` del fornitore, la riga `NOT_INDEXER`, il testo "viaggiano". Test: le righe di `sectionOf` per ogni
  stato; la facciata prima del montaggio.
- **C, calcolo: avanzamento e avvio** (CA-1...CA-7), **dopo A e B** e dopo le decisioni 1 e 2 (tocca `FacesGraph`, la sezione, le stringhe e `FacesWork`): progresso dell'intera libreria, tasto e stima o testo onesto, richiesta
  non utente al primo giro, `afterNews`, `ensure` nel passaggio a `On`, `mayFetch`, il motivo di `STORE_UNAVAILABLE`.
- **D, ricerca e piccoli gesti** (RI-1, GE-1, GE-2, GE-3, SM-1, NV-1): indipendente (core-search/app `FacesPeoplePorts`, `feature-people` review e pagina, `feature-viewer`). Test: nomi "❤", "?", "Анна" accanto a un nome normale.
- **E, la notte e la Diagnostica** (RG-1, DG-1, DG-2, DR-2): `awaitReady` in `LibraryMaintenanceWorker` (corregge anche il lavoro dei viaggi e degli smart della Fase 7), `FACES_NO_INDEXER`, le azioni del catalogo, il ricevitore di debug.
- **Ordine:** A e B insieme (file diversi), poi D ed E insieme, poi C. Al massimo due agenti alla volta; `tools\check.ps1` a ogni unione.

**Decisioni che servono all'utente:**
1. **Il primo calcolo dopo l'accensione (CA-3):** parte subito come richiesta dell'utente (scavalcando notte e carica) e si cambia il testo, oppure segue le regole di sottofondo e "Analizza ora" e' l'unico a scavalcarle?
2. **"Analizza ora" e la stima (CA-2):** si fanno (tasto in Impostazioni → Persone con la stima di `FacesBacklog`, che rilegge il catalogo: L242) o si tolgono dal progetto e da F94?
3. **Il secondo dispositivo dopo la cancellazione (CE-3):** una riga che lo dica ("Persone e' stata disattivata da un altro tuo dispositivo") o resta muto a "Attiva"?
4. **Release e dispositivo non indicizzatore (DR-1):** finche' D34 e' acceso, il tablet che non analizza non avra' mai persone: lo si dice con un testo onesto, o gli si permette di calcolarle per conto suo (`LOCAL_ONLY`, a spese della batteria)?
5. **Il tocco su un volto di un gruppo senza nome nel viewer (GE-3):** "Chi e'?" come oggi, o la pagina del gruppo con "Dai un nome" (come dice il §2.6)?

