# Verifica funzionale: Fase 9, Ricordi, notifiche e mappa (commit `f9aeef8c`, 31/10/2026)

**Stato (31/10).** Alla stesura le 16 voci sono nuove: nessuna e' corretta nel codice. Ne risultano 1 che blocca, 5 sbagliate e 10 minori; 12 sono confermate nel codice
e 4 plausibili (serve il telefono o un test, descritto nella voce). La verifica dei fake (§3) ha trovato sette divergenze, quattro delle quali nascondono voci di questo
documento (NT-1, NT-2, RP-1, MO-1). I flussi del §4 sono da provare sul telefono (F113-F130 dalle decisioni di P13, piu' F147-F153 nuovi).

**La domanda.** Ogni funzione della Fase 9 (Ricordi, notifiche, mappa) promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release?
Questa non e' una revisione di sicurezza: quella sta in `docs/review/9-fase9.md` (altro revisore, in parallelo) e qui non e' duplicata.

**Come.** Ho letto il codice committato (worktree su `main` `f9aeef8c`), senza eseguire niente: niente Gradle, telefono, emulatori, `adb`, NAS o rete. Per ogni flusso
dell'utente ho seguito la catena: schermata o tasto → ViewModel o controllore → porta → oggetto legato in `:app` (Hilt, `MemoriesGraph`, `MemoriesWiring`, `MapGraph`,
`work/`, `src/debug` e `src/release`) → codice che tocca database, file, NAS o rete → risultato che torna alla schermata. In piu' ho confrontato i fake dei test di
`core-memories`, `feature-memories`, `core-map` e `feature-map` con le implementazioni vere. Nessun sotto-agente: ho letto tutto io, in due passate: (a) ricordi e
notifiche; (b) mappa, impostazioni, azzeramento e Diagnostica. Le voci "Blocca" e le "Sbagliata" piu' pesanti le ho ricontrollate nel codice (NT-1, RP-1, RP-2, MO-1,
MO-2, MU-1), compreso l'ordine dei montaggi di Persone e Ricordi e il test che fotografa il comportamento sbagliato di NT-1.

**Il modello dei difetti** e' quello di `docs/review/funzionale-8.md`:
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo o con la chiusura della pagina;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object;
- un lavoro che nessuno programma, o che finisce a vuoto;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, `src/debug` e `src/release`, le recinzioni D12, D30, D34, F4-D31) che rompe una funzione.

**Perimetro.** La Fase 9 cosi' come P13 l'ha integrata in `:app` (sezione "Fase 9 P13: integrazione" di `docs/decisioni.md`). Le regioni offline (P5) non ci sono:
"Zone senza Internet" non porta da nessuna parte (`AppMapActions.openRegions`, `app/.../navigation/AppMapNavigation.kt:94`), ed e' voluto.
- Ricordi in Raccolte: sezione, "Tutti i ricordi", un ricordo, presentazione con movimento e musica, "Mostra meno" e "Mai", visti e scartati;
- il motore: generazione notturna, `restore()`, "Un anno fa oggi", viaggi, persone, meglio del mese, scelta delle foto;
- le notifiche: lavoro `memories-notify`, una al giorno, permesso, contenuto, tocco, revoca;
- Impostazioni (Ricordi e notifiche, Mappa) e la mappa: Luoghi, pagina, bolle, griglia, "Mostra sulla mappa", mini-mappa, carta offline, stile online, media solo sul NAS;
- azzeramento, Diagnostica, avvio istantaneo, debug e release.

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravita':**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `core-memories/...` per
`core-memories/src/main/kotlin/dev/pampa/mycloudgallery/core/memories/...`; `feature-memories/...` per
`feature-memories/src/main/kotlin/dev/pampa/mycloudgallery/feature/memories/...`; `feature-map/...` per
`feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/...`. I numeri di riga sono quelli di `f9aeef8c`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Ricordi in Raccolte e pagine (RP) | 0 | 2 | 0 | 2 | 2 | 0 |
| Motore dei ricordi (MO) | 0 | 2 | 1 | 3 | 2 | 1 |
| Notifiche (NT) | 1 | 0 | 1 | 2 | 2 | 0 |
| Musica della presentazione (MU) | 0 | 1 | 2 | 3 | 2 | 1 |
| Impostazioni (IM) | 0 | 0 | 1 | 1 | 1 | 0 |
| Mappa (MA) | 0 | 0 | 1 | 1 | 1 | 0 |
| Azzeramento (AZ) | 0 | 0 | 1 | 1 | 0 | 1 |
| Diagnostica (DG) | 0 | 0 | 2 | 2 | 2 | 0 |
| Debug e release (DR) | 0 | 0 | 1 | 1 | 0 | 1 |
| **Totale** | **1** | **5** | **10** | **16** | **12** | **4** |

**Esito per flusso:**

| Flusso | Esito | Voci |
|---|---|---|
| Sezione Ricordi in Raccolte | Sbagliata | RP-2, MO-3 |
| "Tutti i ricordi" | Sbagliata | RP-2 |
| Un ricordo: pagina, griglia, copertina, titolo, presentazione col movimento | Sbagliata (a freddo) | RP-1 |
| Presentazione: musica di serie (nessuna, voluta da Q3) | Funziona | |
| Presentazione: canzone propria dal selettore di sistema | Sbagliata | MU-1, MU-2, MU-3 |
| "Mostra meno", "Mai", visti e scartati (anche fra dispositivi) | Funziona | |
| Motore: generazione notturna dopo i viaggi (60 s) | Sbagliata | MO-2 |
| Motore: `restore()`, "Un anno fa oggi", viaggi, meglio del mese, scelta delle foto, mai vuoti ne' nascosti | Funziona | MO-3 (minore) |
| Motore: ricordi con le persone (solo con i volti attivi) | Sbagliata (plausibile) | MO-1 |
| Notifiche: il lavoro `memories-notify`, l'ora scelta, al massimo una al giorno | **Blocca** | NT-1 |
| Notifiche: contenuto per impostazione, permesso da un tocco, notifica tolta per nascosti e scelte | Funziona | |
| Notifiche: il tocco che apre il ricordo (anche ad app bloccata) | Funziona | NT-2 (minore) |
| Impostazioni: Ricordi e notifiche, Mappa, sincronizzazione dove decisa | Funziona | IM-1 (minore) |
| Mappa: Luoghi, pagina, camera, bolle, griglia, viewer, "Mostra sulla mappa" | Funziona | MA-1 (minore) |
| Mini-mappa nel viewer | Funziona (plausibile) | |
| Carta di base offline a mappa spenta, stile online alle condizioni decise, media solo sul NAS | Funziona | |
| Azzeramento (`memories/`, `map/`, lavoro, notifica) | Funziona | AZ-1 (minore) |
| Diagnostica (codici nel catalogo e nell'esportazione) | Funziona | DG-1, DG-2 (minori) |
| Avvio istantaneo (montaggio dopo il primo disegno, nessuna pagina "vuota" mentre carica) | Sbagliata | RP-1, RP-2 |
| Debug e release (R8, strumento di valutazione, recinzioni) | Funziona | DR-1 (minore) |

Funziona 12, Sbagliata 7, Blocca 1 (su 20 flussi).

**Il quadro.**
- **Il cuore regge.** A processo caldo, con la Libreria pronta e la famiglia agganciata:
  - il montaggio dei ricordi dopo il primo disegno, con `restore()` per prima e lo stop dell'azzeramento per primo;
  - la generazione, la pulizia dello scaffale, i nascosti (`HiddenMask` legato in Hilt, `onHiddenChanged`, `prune` e `revalidate`), visti, scartati, "Mostra meno" e
    "Mai" che viaggiano dal telefono al tablet;
  - la notifica: contenuto generico di serie, miniatura solo dal telefono, `PendingIntent` immutabile con la sola azione fissa, tocco servito dopo il blocco app;
  - la mappa: ogni pezzo pigro, indice dei punti alla prima apertura, camera iniziale dall'ultima posizione locale, bolle fuori dal thread principale, griglia viva
    sull'istantanea visibile, carta di base offline a mappa spenta e cancello di rete letto a ogni richiesta;
  - azzeramento, Diagnostica e le porte di debug (solo debug, in release le funzioni sono vuote).
- I difetti stanno in quattro posti:
  - **la notifica non parte a processo morto (NT-1)**: il lavoro `memories-notify` trova lo scaffale non montato e finisce con "riuscito" senza fare niente, ogni
    giorno, e il test lo da' per buono;
  - **le schermate dei ricordi dicono cose false mentre i ricordi si montano (RP-1, RP-2)**: "Non trovo questo ricordo" a freddo e, per un attimo, "I ricordi arrivano
    quando ci sono abbastanza foto" con lo scaffale pieno;
  - **il motore dipende da come si montano le cose, non solo da cosa sono (MO-1, MO-2, MO-3)**: i ricordi delle persone si potano ad ogni avvio finche' Persone non e'
    montata, un errore della notte salta le Utility, il giro di oggi parte solo al cambio di scheda;
  - **la canzone propria si prepara sul thread principale (MU-1)** e non dice niente quando il sistema nega l'audio o il permesso (MU-2, MU-3).
- **La release** non ha difetti suoi di legami: `MemoriesModule`, `MapModule` e `FacesModule` sono gli stessi, `installMemories` e' vuoto, nessun pacchetto `testing`
  entra in `:app` (P13-11). Resta da provare a mano la mappa con R8 (DR-1).

**Le cose piu' gravi:**
- **La notifica dei ricordi non arriva se l'app non e' viva (NT-1).** `MemoryNotifyWorker.doWork` risponde `success` se `slot.runner` e' null
  (`app/.../work/MemoriesWork.kt:112`). `slot.runner` lo mette solo il montaggio (`app/.../memories/MemoriesGraph.kt:176`), che aspetta il primo disegno (al piu' 1,5 s
  dall'avvio del processo, `core-common/.../perf/AfterFirstDraw.kt:27`) e la famiglia pronta. WorkManager lancia il lavoro appena nato il processo: ricordi non
  montati, nessuna notifica, nessuna ripianificazione dal lavoro; il montaggio, un secondo dopo, ripianifica per domani. Il lavoro "di ogni giorno" funziona solo
  con il processo gia' in vita alle 19:00.
- **I ricordi delle persone si potano ad ogni avvio (MO-1).** Il montaggio dei ricordi chiama `janitor.watch` (`MemoriesGraph.kt:180`), il primo `sweep` fa sempre `prune()`
  (`core-memories/.../settings/less/PeopleLessJanitor.kt:60`) e la pulizia toglie i ricordi `PERSON` se Persone "non e' disponibile"
  (`ShelfPruner.kt:67-73`): prima che Persone sia montata lo e' sempre.

---

## 2. I difetti

### 2.1 Ricordi in Raccolte e pagine (RP)

#### RP-1 – La pagina del ricordo dice "Non trovo questo ricordo" e la presentazione "Questo ricordo non c'e' piu'" finche' i ricordi non sono montati
- **Gravita':** Sbagliata (per qualche secondo a ogni apertura a freddo che porta a un ricordo). **Stato:** confermato.
- **Flusso:** il processo e' morto; l'utente tocca la notifica, oppure riprende l'app dai recenti su una pagina di ricordo o sulla presentazione.
- **Percorso:**
  - la rotta si apre subito (`MemoriesNotificationEffect`, `app/.../navigation/AppMemoriesNavigation.kt:188-196`, o il ripristino della pila) e il controllore chiede
    `memories.memory(id)`;
  - la facciata, prima del montaggio, risponde `flowOf(null)` (`app/.../memories/MemoriesFacades.kt:47`: `graph.live.flatMapLatest { it?.engine?.memory(id) ?: flowOf(null) }`);
  - `MemoryPageController.stateOf(null)` con `everReady` falso e scaffale ancora vuoto (`PREPARING`) risponde `NotFound`
    (`feature-memories/.../page/MemoryPageController.kt:72-76`): "Non trovo questo ricordo" (`MemoryPageScreen.kt:106`);
  - `PlayerController.onView(null)` risponde `gone()` (`feature-memories/.../player/PlayerController.kt:140-143`): "Questo ricordo non c'e' piu'";
  - al montaggio il flusso emette il ricordo vero e le due schermate si correggono da sole (se il montaggio fallisce o la famiglia non e' pronta restano cosi').
- **Atteso:** principio "primo disegno istantaneo" (ultimo stato o scheletro, mai uno stato sbagliato) e `MemoryPageState.Loading`, che nel controllore esiste ma nel
  percorso vero non si usa piu' dopo il primo fotogramma.
- **Correzione:** la facciata non emette niente finche' non e' montata (`graph.live` null → nessun valore, pagina in `Loading`, presentazione in `Loading`); `NotFound` e
  `Gone` solo con i ricordi montati. Un test sulla facciata vera con `live` null (oggi `InMemoryMemories` non ha questo stato: §3).

#### RP-2 – Con lo scaffale pieno e la Libreria non ancora letta, la sezione e "Tutti i ricordi" dicono "I ricordi arrivano quando ci sono abbastanza foto"
- **Gravita':** Sbagliata (finche' l'istantanea della Libreria non arriva, a ogni avvio a freddo). **Stato:** confermato nel codice; quanto dura lo dice il telefono (F149).
- **Flusso:** avvio a freddo, scaffale gia' scritto dalla notte, Raccolte aperta subito.
- **Percorso:**
  - `MemoryEngine.shelf` combina `held`, scelte e `library.snapshot`; con l'istantanea null `ShelfCards.view` disegna lo scaffale com'e' nel file, `ShelfState.Ready`
    (`core-memories/.../shelf/ShelfCards.kt:26-35, 39`): la sezione parte con le sue voci (`feature-memories/.../section/MemoriesSections.kt:96-101`);
  - subito dopo il filtro finale (`FinalFilter.refine`, `feature-memories/.../ui/FinalFilter.kt:42-51`) chiede a ogni voce `memories.memory(card.id).firstOrNull()`;
    `MemoryEngine.memory` risponde `null` con l'istantanea null (`core-memories/.../plan/MemoryEngine.kt:169`), quindi `?.view ?: continue` **butta ogni voce**;
  - `MemoriesSectionStates.section` con `ShelfState.Ready` e zero voci risponde `Empty(NOT_ENOUGH_PHOTOS)` (`MemoriesSectionStates.kt:30`): "I ricordi arrivano quando
    ci sono abbastanza foto"; "Tutti i ricordi" fa lo stesso (`feature-memories/.../list/AllMemoriesController.kt:36-41`);
  - quando l'istantanea arriva `shelf` riemette e `transformLatest` ricomincia: la sezione si corregge.
- **Atteso:** la sezione "si disegna dall'ultimo scaffale senza aspettare la Libreria" (P9, P13-1), e il filtro finale senza istantanea non puo' dire "tutti nascosti".
- **Correzione:** `refine` lascia le voci com'erano quando l'istantanea non c'e' (o `Memories.memory` distingue "non so ancora" da "non c'e'"); un test con `library.snapshot` null.

### 2.2 Motore dei ricordi (MO)

#### MO-1 – I ricordi delle persone si potano ad ogni avvio finche' Persone non e' montata
- **Gravita':** Sbagliata. **Stato:** plausibile (il percorso e' tutto nel codice; l'ordine dei due montaggi va visto sul telefono: F148).
- **Flusso:** Persone accesa e un ricordo `PERSON` sullo scaffale (generato dalla notte); `am force-stop`, riapro l'app.
- **Percorso:**
  - i ricordi si montano appena la famiglia e' pronta (`MemoriesGraph.start`, `MemoriesGraph.kt:134-140`) e `watch` avvia il janitor (`:180`); Persone si monta sullo
    stesso `ready` (`FacesGraph.start`, `app/.../faces/FacesGraph.kt:176-177`), ma ha un montaggio piu' pesante e `bridge.bind` arriva solo in `FacesLive.mounted`
    (`app/.../faces/FacesWiring.kt:430-433`); fino ad allora `PeopleBridge.available` e' falso (`FacesPeoplePorts.kt:110-114`) e `PhaseGatedPeople.available` pure;
  - `PeopleLessJanitor.watch` fa subito `sweep()`; con la fase `UNKNOWN` non lascia lapidi (`PeopleLessJanitor.kt:53-57`) ma **chiama sempre `memories.prune()`** (`:60`);
  - `MemoryEngine.pruneLocked` pulisce con `ShelfPruner` (`MemoryEngine.kt:261-266`), che usa `people.available.value` (`ShelfPruner.kt:44`) e per ogni ricordo `PERSON`
    chiede `ChoiceRules.personStillShown(..., peopleAvailable = false, ...)`, che risponde falso (`ChoiceRules.kt:25-26`): il ricordo esce e lo scaffale si riscrive
    (`store.write`);
  - al montaggio di Persone il janitor rifa' il giro, ma non puo' rimettere niente; il giro `OPEN` di oggi e' gia' fatto (`MemoryEngine.kt:213`): i ricordi delle persone
    di stanotte non si vedono piu' fino alla notte dopo. Lo stesso vale per un giro `OPEN` o `LIGHT` fatto con Persone non ancora disponibile.
- **Atteso:** P8: "con `UNKNOWN` il janitor non cancella niente" (`FacesPeopleSwitch`, P13-7), e `PeopleLessJanitor` lo scrive nel commento (`:35`). Vale per le **scelte** (le lapidi), non per lo scaffale: il test
  `PeopleLessJanitorTest.conLoStatoIgnotoNonCancellaNiente` (`core-memories/src/test/.../settings/less/PeopleLessJanitorTest.kt:106-122`) prova che con `UNKNOWN` e `available` falso i ricordi di persone **escono**
  (`:118`): giusto per Persone spenta (E-MEM10), sbagliato per Persone "non ancora montata", che e' la stessa cosa per la porta.
- **Correzione:** `prune` (e la pulizia dentro `refresh`) non tolgono i `PERSON` finche' lo stato di Persone e' `UNKNOWN`: la fase passa al pruner o `MemoryPeople`
  espone "non lo so" distinto da "no". Un test con `FakePeopleSwitch(UNKNOWN)` e un `PERSON` sullo scaffale.

#### MO-2 – Un errore della generazione notturna salta il resto della manutenzione
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Percorso:** `LibraryMaintenanceWorker.maintain` chiama `gallery.runIfActive { memories.nightly() }` (`app/.../work/GalleryWork.kt:206`) **prima** delle Utility
  (`:211`). `MemoriesGraph.nightly` non ha nessun `try` (`MemoriesGraph.kt:209-217`), mentre il resto del file usa `quietly` (`:235-244`) e `AlbumsGraph.nightly` ha `guarded`
  (`AlbumsGraph.kt:405-411`). `MemoryEngine.refresh` lancia per scelta (R17: una lettura dei visibili che fallisce, `store.write` con il disco pieno): l'eccezione esce da `maintain`,
  `AlbumJobReports.maintenance` la registra come lavoro fallito e la rilancia (`AlbumJobReports.kt:39-47`) e `utility.nightly` (hash dei video del NAS, firme visive) non gira
  quella notte, e cosi' ogni notte finche' l'errore dura.
- **Atteso:** P13-14: la generazione "dopo i viaggi", senza togliere niente al resto della manutenzione.
- **Correzione:** `nightly` dentro `quietly` (conta `roundErrors`, risponde falso); un test che fa lanciare il motore e guarda che le Utility girino.

#### MO-3 – Il giro "di oggi" parte solo al cambio di scheda, e se i ricordi non sono ancora montati si perde
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:**
  - `onCollectionsOpened` e' chiamato da `onSelectTab` solo se `selected != tab` (`app/.../navigation/AppNavHost.kt:466-474`), e `tab` e' `rememberSaveable` (`:439`): dopo la
    morte del processo, o con l'app viva da giorni, Raccolte e' gia' la scheda aperta e il giro non parte;
  - `onCollectionsOpened` ignora `ensureLive() == null` senza riprovare (`MemoriesGraph.kt:203`), e `refresh` con l'istantanea null risponde `NOTHING`
    (`MemoryEngine.kt:210`) senza nessun nuovo tentativo quando l'istantanea arriva;
  - un giro fatto con la Libreria ancora quasi vuota (primo avvio, lettura del telefono in corso) scrive `lastRunDay = oggi` (`:231`), e `OPEN` non rifa' piu' niente
    fino a domani (`:213`).
- **Atteso:** D4: i ricordi di oggi li prepara la notte; se non c'e' stata, "all'apertura di Raccolte" un passo leggero.
- **Correzione:** il giro `OPEN` anche al ritorno in primo piano con Raccolte aperta e al primo cambio di istantanea quando oggi lo scaffale e' vuoto; non fissare il giorno
  quando la Libreria e' ancora in lettura. Decisione dell'utente: vedi REPORT.md.

### 2.3 Notifiche (NT)

#### NT-1 – La notifica dei ricordi non parte se il processo non e' gia' vivo: il lavoro trova i ricordi non montati e finisce "riuscito" senza fare niente
- **Gravita':** Blocca (la funzione "una notifica all'ora scelta" non c'e' nel caso normale: telefono fermo, app non in uso). **Stato:** confermato.
- **Flusso:** notifiche accese, permesso concesso, `memories-notify` programmato; alle 19:00 il processo dell'app non c'e': WorkManager lo crea per il lavoro.
- **Percorso:**
  - `Application.onCreate` costruisce `MemoriesGraph` e chiama `start()` (`app/.../di/MemoriesModule.kt:91`): la coroutine aspetta `AfterFirstDraw.await()` e poi `ready.first { it }`
    (`MemoriesGraph.kt:134-140`). In un processo nato per un lavoro nessuno disegna: il cancello si apre da solo 1,5 s dopo l'avvio (`AfterFirstDraw.kt:27, 55-62`);
  - `MemoryNotifyWorker.doWork` parte subito dopo `onCreate` e legge `slot.runner` (`app/.../work/MemoriesWork.kt:112`): null, perche' `runner` lo mette solo il montaggio
    (`MemoriesGraph.kt:176`) e lo toglie `stop` (`:231`). Risponde `Result.success()` **senza ripianificare** ("niente da fare, e niente da ripianificare": il commento e' di prima di P13);
  - dopo 1,5 s o piu' il montaggio fa `run.watch` → `replan` (`core-memories/.../notify/MemoryNotifyRun.kt:105-110`), che con "adesso" dopo le 19:00 sceglie le 19:00 di domani:
    il giorno e' perso, e cosi' ogni giorno. Funziona solo se alle 19:00 il processo e' gia' vivo e montato (app aperta da poco);
  - anche con i ricordi montati il lavoro non basta: senza l'istantanea della Libreria `MemoryEngine.memory` risponde `null` (`MemoryEngine.kt:169`), `candidatesOf` salta ogni
    scheda (`MemoryNotifyRun.kt:209`) e il passo leggero risponde `NOTHING` (`MemoryEngine.kt:210`): "niente di bello oggi" (`Skipped(NOTHING)`). Gli altri lavori della galleria aspettano la
    famiglia pronta fino a 20 s (`GalleryWork.kt:97, 171`); questo no;
  - il test lo da' per giusto: `MemoriesWorkTest.senzaRicordiMontatiNonFaNiente` (`app/src/test/.../work/MemoriesWorkTest.kt:135-141`) prova che con lo slot vuoto il lavoro non fa niente, e le altre prove
    mettono sempre `runner` a mano (`:148-153`): il caso del processo nato per il lavoro non c'e'.
- **Atteso:** D20 e §2.6: all'ora scelta (finestra H..H+2 h) una notifica, anche con l'app chiusa; e' la funzione.
- **Correzione:** (1) il lavoro aspetta, come gli altri, il montaggio (nuova `MemoriesGraph.awaitLive(timeout)`: attende `ready` **senza** il primo disegno e fa `ensureLive`) e l'istantanea
  della Libreria non nulla, al piu' 20 s; (2) `enterWorker()` all'inizio di `doWork`, **prima** dell'attesa: il `replan` che il montaggio fa partire (`watch`) usa `REPLACE` finche' il flag e'
  spento (`MemoriesWork.kt:55`) e cancellerebbe il lavoro che sta aspettando; (3) dopo l'attesa, se `runner` e' ancora null (famiglia mai pronta) `success` va bene, con la ripianificazione che
  il montaggio fa da se'; (4) un test con un processo "nato per il lavoro" (slot vuoto che si riempie dopo) al posto di `senzaRicordiMontatiNonFaNiente`; (5) F147 sul telefono.

#### NT-2 – Il tocco "apri il ricordo della notifica" legge un registro che puo' essere vecchio
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `MemoryNotifier.openTarget` risponde il ricordo dell'ultimo registro (`app/.../notify/MemoryNotifier.kt:141-142`, `readPosted` `:211-216`) senza guardare che la notifica sia ancora in
  tendina (come fa `posted()`, `:130-139`) ne' che sia di oggi. `OpenMemoriesRequests` e' servito anche per l'azione che manda un'altra app (`MainActivity.entryOf`, `MainActivity.kt:97-98`): con la
  tendina vuota ma il registro di ieri, `MemoriesNotificationEffect` apre la pagina di **ieri** (`AppMemoriesNavigation.kt:188-196`), non "al piu' la pagina Ricordi" come dice la guida di
  `MainActivity.kt:41`. Il ricordo e' dell'utente: non e' una fuga, ma la pagina e' un'altra.
- **Correzione:** `openTarget` usa `posted()` (nessun registro se la notifica non c'e'), e il giorno del registro deve essere oggi; altrimenti `openAllMemories`.

### 2.4 Musica della presentazione (MU)

#### MU-1 – La canzone propria si prepara sul thread principale
- **Gravita':** Sbagliata. **Stato:** plausibile (una canzone su un fornitore lento: Drive, scheda SD; F150).
- **Percorso:** `PlayerController.startMusic` lancia nello `scope` della pagina, cioe' `viewModelScope` (principale), `library.open(choice)` e poi `playback.start(source)`
  (`feature-memories/.../player/PlayerController.kt:382-398`); `AndroidMusicPlayback.start` fa `MediaPlayer.setDataSource(app, content://...)` e `prepare()` **sincroni**
  (`app/.../memories/MemoriesMusic.kt:143-150`). `prepare()` su un documento remoto o lento blocca il thread principale: la presentazione si ferma al titolo (il suo tempo e' lo stesso `scope`), il
  tocco non risponde e con un fornitore che non risponde Android puo' chiudere l'app (ANR).
- **Atteso:** E-MEM14/E-MEM18: "mai un'attesa", la presentazione resta muta e avvisa se il file non c'e'.
- **Correzione:** `prepareAsync` con `setOnPreparedListener` e `setOnErrorListener` (→ `MusicEvent.MISSING`), oppure l'intera apertura in `withContext(IO)`. Un test strumentato con un fornitore finto lento.

#### MU-2 – Se il sistema nega l'audio, la presentazione si ferma e la musica non c'e' piu', senza dirlo
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `AndroidMusicPlayback.start` con `requestAudioFocus` non concesso emette `MusicEvent.FOCUS_LOST` e torna (`MemoriesMusic.kt:137-140`); `PlayerController` mette `music = PLAYING`
  subito dopo `start` (`PlayerController.kt:394-395`) e riceve `FOCUS_LOST`: pausa e timer fermo (`:405-411`). Il focus negato non porta un `FOCUS_GAINED` (non c'e' richiesta in attesa: manca
  `setAcceptsDelayedFocusGain`): serve un tocco; il tocco fa `resume`, che non trova nessun lettore (`:308`) e la presentazione va avanti muta, con lo stato "musica in riproduzione" e senza l'avviso
  `musicMissing`.
- **Correzione:** il rifiuto iniziale vale `MISSING` (muta, con l'avviso) o `setAcceptsDelayedFocusGain(true)`; `music` si imposta solo se un lettore e' partito.

#### MU-3 – Il permesso di leggere la canzone che non si puo' conservare non dice niente
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** nel risultato del selettore `takePersistableUriPermission` fallito (fornitore che non lo concede) lascia `kept = false` e la scelta non si salva
  (`app/.../navigation/AppMemoriesNavigation.kt:158-163`): l'utente sceglie il brano, torna alla pagina e non cambia niente. L'altra uscita, `ActivityNotFoundException`, e' dichiarata muta nel commento (`:177-181`).
- **Correzione:** un messaggio (`MemoriesMessage.FAILED`) dalla pagina delle impostazioni quando la scelta non si conserva.

### 2.5 Impostazioni (IM)

#### IM-1 – La riga "Ricordi e notifiche" di Impostazioni parte dai valori di serie
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `AppMemoriesEntryStatus.summary` e' `stateIn(Lazily, summaryOf(NotifyPrefs(), GRANTED))` (`app/.../memories/MemoriesScreenPorts.kt:95-97`): nei primi fotogrammi dice "2-3 a settimana" e
  nessun pallino, anche a chi ha spento le notifiche o ha il permesso negato; poi la lettura delle preferenze (fuori dal thread principale) la corregge. L'utente vede cambiare la riga appena aperta la pagina.
- **Correzione:** tenere l'ultimo valore letto in memoria (o leggere le preferenze al primo accesso, come `MemoriesSettingsController`, che parte dal valore vero) e non mostrare la riga finche' non c'e'.

### 2.6 Mappa (MA)

#### MA-1 – Un errore nel leggere i punti diventa "nessuna foto con posizione"
- **Gravita':** Minore (gia' noto: L495). **Stato:** confermato.
- **Percorso:** `GeoMapPoints.rebuild` che fallisce lascia l'indice che c'era o, se non c'e', `GeoIndexState.Empty(0)` (`core-map/.../points/GeoMapPoints.kt:75-79`); `MapController` lo mostra come
  `MapScreenState.Empty` (`feature-map/.../map/MapController.kt:105`), la griglia di una zona come `ZoneGridState.Empty` (`feature-map/.../grid/ZoneGridController.kt:69`) e la sezione Luoghi come "senza GPS"
  (`feature-map/.../places/PlacesStates.kt:33`). Un database che non risponde si legge come "nessuna foto ha una posizione": nessuna schermata dice "non riesco a leggerle" e nessun tasto riprova.
- **Correzione:** uno stato d'errore (cambia `GeoIndexState`, `MapScreenState` e gli stati di P4: decide l'orchestratore, L495); intanto un "Riprova" sulla riga "nessuna posizione" che rifa' `rebuild(OPEN)`.

### 2.7 Azzeramento (AZ)

#### AZ-1 – Una notifica in corso mentre si azzera puo' scrivere dopo
- **Gravita':** Minore. **Stato:** plausibile (finestra stretta; F127 con il lavoro lanciato a mano durante l'azzeramento).
- **Percorso:** `MemoriesGraph.stop` toglie `slot.runner` e ferma i collettori (`MemoriesGraph.kt:228-233`) ma non aspetta una corsa di `MemoryNotifyRun` gia' partita: il lavoro non e' sotto `runIfActive`
  (`MemoriesWork.kt:110-121`). La corsa puo' scrivere il registro `memories/notified` (`MemoryNotifyRun.kt:191`) e pubblicare la notifica (`:192`) **dopo** che l'azzeramento ha annullato il lavoro e
  tolto la notifica 1009 (`app/.../graph/LocalData.kt:160-168`) e dopo `memories/`: una cartella e una notifica che l'azzeramento aveva tolto.
- **Correzione:** `MemoryNotifyRun` si ferma con il grafo (una corsa in corso si annulla e si aspetta in `stop`, come i collettori) e controlla `stopped` prima di scrivere il registro e prima di pubblicare.

### 2.8 Diagnostica (DG)

#### DG-1 – I conti dei ricordi e delle notifiche sono del solo processo di adesso
- **Gravita':** Minore (gia' noto: L492). **Stato:** confermato.
- **Percorso:** `MemoriesCounters` sta in memoria (`app/.../memories/MemoriesFacades.kt:87-139`): `NOTIFY_SKIPPED`, `MEMORY_DROPPED`, `MEMORY_SHELF_RESET`, `MEMORY_NO_VECTORS` e `NOTIFY_REVOKED`
  (`MemoriesDiagnostics.kt:82-90`) si azzerano a ogni processo. Il lavoro delle notifiche e la notte girano in processi che muoiono subito dopo (con NT-1 corretto, di piu'): quando l'utente apre la
  Diagnostica, la sezione Ricordi non ha quasi mai niente da dire sulle notifiche saltate e sugli esclusi.
- **Correzione:** persistere gli stessi numeri (solo conti, su file come `notify-late`) oppure dirlo nella voce ("da quando l'app e' avviata"). Decisione: vedi REPORT.md.

#### DG-2 – `NOTIFY_LATE` mette un conto di 14 giorni vicino a uno dell'ultimo avvio
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** la voce esporta `late` (le consegne in ritardo degli ultimi 14 giorni, da file) e `delivered` (consegne **di questo processo**, `MemoriesDiagnostics.kt:88-89`; `MemoriesFacades.kt:106, 131-134`).
  Il rapporto che D20 chiede (piu' del 10% in ritardo: piano B) non si puo' leggere: dopo un riavvio `delivered` riparte da zero e `late` no, e un 3 su 1 sembra un guasto.
- **Correzione:** `delivered` anche su file, per gli stessi 14 giorni.

### 2.9 Debug e release (DR)

#### DR-1 – La mappa con R8 non e' mai stata vista funzionare in release
- **Gravita':** Minore. **Stato:** plausibile (serve l'APK di release sul telefono: F151).
- **Percorso:** `feature-map/consumer-rules.pro` tiene OkHttp, MapLibre, `feature.map.net`, `MapLibreInit`, `MapLibreSurface*` e `MapLibreMiniMap` con `-keep,allowshrinking`
  (`feature-map/consumer-rules.pro:9-21`): le classi che il codice non nomina si tolgono, e il codice nativo di MapLibre cerca per nome (JNI) classi e metodi Java che nessun chiamante Java nomina.
  Cio' che le regole non coprono lo devono coprire quelle che MapLibre porta con se'. `tools/check.ps1` (passo 7h) prova solo che nessuna chiamata a OkHttp o a MapLibre esca da dove deve: il
  bytecode, non il comportamento. P13 ha dichiarato "nessuna prova su dispositivo" (`docs/decisioni.md`, "Punti aperti"): la prima volta che `MapLibreInit.ensure`, `MapView` e `MapSnapshotter`
  girano con R8 e' sul telefono.
- **Atteso:** F119 e F120 con la build di release, non solo con quella di debug.
- **Correzione:** nessuna senza una prova che fallisca; se emerge un crash nativo, `-keep` pieno (senza `allowshrinking`) sulle classi che il codice nativo nomina.

---

## 3. I fake che divergono dal vero

| Fake | Dove si usa | Divergenza |
|---|---|---|
| `InMemoryMemories` (`core-memories/.../testing/InMemoryMemories.kt:101-121`) e `NoMemories` | `MemoryPageControllerTest`, `PlayerControllerTest`, `MemoriesSectionSourcesTest`, `AllMemoriesControllerTest`, screenshot | Nessuno stato "non ancora montato": `memory(id)` risponde sempre dal servizio. Il vero, prima del montaggio, risponde `flowOf(null)` (`MemoriesFacades.kt:47`): RP-1 non puo' emergere |
| `MemoriesWorkTest` (`app/src/test/.../work/MemoriesWorkTest.kt:135-160`) e `RecordingNotifyScheduler` (`core-memories/.../testing/NotifyFakes.kt:89-105`) | il lavoro `memories-notify` | Il test dell'unico caso "senza ricordi montati" lo da' per **giusto** ("non fa niente e non ripianifica"); gli altri mettono `runner` a mano. Il programmatore finto non ha `REPLACE` e `APPEND_OR_REPLACE` (`MemoriesWork.kt:50-62`), quindi non vede che il `replan` del montaggio cancella un lavoro in corso: NT-1 e la sua correzione restano fuori |
| `FakePeopleSwitch(initial = ON)` (`core-memories/.../testing/FakePeopleSwitch.kt:9`) | `PeopleRig` (`core-memories/src/test/.../plan/people/PeopleRig.kt:18, 49`) e le prove del motore e del janitor con le persone | Parte da `ON`; il vero (`FacesPeopleSwitch`, `app/.../memories/MemoriesPeople.kt:117`) parte da `UNKNOWN` fino al montaggio di Persone. L'unica prova con `UNKNOWN` (`PeopleLessJanitorTest.kt:106-122`) afferma che i ricordi di persone escono: MO-1 e' scritto nel test |
| `MemoriesIntegrationTest.live()` (`app/src/test/.../integration/MemoriesIntegrationTest.kt:77-78`) | l'integrazione a due dispositivi con le persone vere | Monta i ricordi a mano con `graph.ensureLive()`, quando lo decide il test: non chiama mai `MemoriesGraph.start()` (primo disegno, `ready`) e non c'e' un riavvio a freddo con Persone che si monta dopo. NT-1, MO-1 e RP-1 sono tutti sul percorso che il test salta |
| `FakeNotifyPublisher.posted()` (`NotifyFakes.kt:77`) | revoca, corsa del giorno | Risponde l'ultimo pubblicato anche se l'utente l'ha tolto; il vero (`MemoryNotifier.kt:130-139`) guarda la tendina, e `openTarget` no (`:141-142`): NT-2 non emerge |
| `FakeMapSurface` (`feature-map/.../testing/FakeMapSurface.kt:42-50, 61-92`) | `MapController`, bolle, screenshot | La camera e la proiezione ci sono sempre (la vera ha `viewport` null fino al layout, `MapLibreSurface.kt:87-97`); `animateTo` e' un `moveTo` istantaneo (`:68-70`) e non manda mai `onIdle` da solo (`settle` a mano, `:92`), mentre la vera anima 450 ms con molti `onMoved` e un `onIdle` alla fine (`:109-111`). MapLibre non pronta e `MapView` che nasce dopo il primo fotogramma non sono mai provati: restano F119-F122 |
| Impostazioni della mappa in memoria nelle prove di `:app` (`docs/decisioni.md`, P13, "Prove") e `Phase9EraseTest` | azzeramento e mappa | Il DataStore vero (`DataStoreMapSettings`, file in `map/settings/`) e' fuori dalle prove: l'azzeramento di `map/` con il DataStore aperto, e `SettingsLoaded.loaded()` (`MapController.kt:138`), non sono coperti |

---

## 4. Flussi da provare sul telefono: da mettere per primi nelle prove sul campo (F147-F153, poi F113-F130)

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere. `MemoriesIntegrationTest` e `Phase9EraseTest` coprono il grafo vero a processo caldo, a mano, con le persone gia' attive:
non hanno mai avviato a freddo, ne' fatto partire il lavoro delle notifiche in un processo nuovo, ne' provato la release.

1. **F147 (nuovo), notifica a processo morto** (NT-1): su `test`, notifiche accese con l'ora a adesso + 3 minuti; `adb shell am force-stop dev.pampa.mycloudgallery.debug`; alle H il lavoro parte da solo
   (o `adb shell cmd jobscheduler run -f dev.pampa.mycloudgallery.debug <id>` del lavoro `memories-notify`, `adb shell dumpsys jobscheduler | findstr memories`). Atteso: **una** notifica del canale "Ricordi" e il lavoro
   di domani. Oggi: nessuna notifica e il lavoro rifatto solo dal montaggio. **Da fare prima di F115**, che presuppone l'app viva; poi ripetere F115 (una sola al giorno, Doze, `deviceidle force-idle`) con il processo morto.
2. **F148 (nuovo), ricordi delle persone dopo un avvio a freddo** (MO-1): con Persone attiva e un ricordo "Con <nome di prova>" sullo scaffale (F130), `am force-stop` e riapri Raccolte, dieci volte: il ricordo
   della persona deve esserci sempre (e `adb exec-out run-as ... ls -l no_backup/memories` non deve riscrivere lo scaffale prima che Persone sia montata).
3. **F149 (nuovo), avvio a freddo e ripristino sulle schermate dei ricordi** (RP-1, RP-2, AV): dieci avvii a freddo con Raccolte aperta subito, guardando la sezione Ricordi nei primi secondi ("I ricordi arrivano
   quando ci sono abbastanza foto" non deve comparire con lo scaffale pieno); il tocco sulla notifica con il processo morto; poi `am kill` su una pagina di ricordo e sulla presentazione e ripristino dai recenti:
   "Non trovo questo ricordo" e "Questo ricordo non c'e' piu'" non devono comparire.
4. **F150 (nuovo), canzone propria** (MU-1, MU-2, MU-3): una canzone scelta su un fornitore lento (Drive offline, scheda SD tolta a meta') e la presentazione deve restare fluida; una chiamata in corso quando parte la
   presentazione (focus negato: la musica deve dirlo, non restare "in riproduzione"); un fornitore che non concede il permesso persistente (la scelta deve dirlo). Insieme a F118.
5. **F151 (nuovo), mappa in release** (DR-1): l'APK di release sull'S25: la mappa (carta offline, poi accesa), le bolle, la griglia di una zona, il viewer con la mini-mappa e "Mostra sulla mappa", Impostazioni → Mappa
   acceso e spento. Poi F119 e F120 con la release (il cancello e' lo stesso, ma R8 no).
6. **F152 (nuovo), la manutenzione con un errore dei ricordi** (MO-2): in debug, `run-as` per togliere i permessi di scrittura a `no_backup/memories/` (o lo spazio quasi pieno) e
   `adb shell cmd jobscheduler run -f ... gallery-maintenance`: le Utility (hash dei video del NAS, firme visive) devono girare lo stesso; in "Ultimi esiti ed errori" la manutenzione non deve risultare fallita per i ricordi.
7. **F153 (nuovo), il giro di oggi** (MO-3): app viva con Raccolte aperta a cavallo della mezzanotte; processo morto e ripristino su Raccolte; una prima installazione con la lettura del telefono in corso: quando
   compaiono i ricordi di oggi.
8. **Da P13, ora sul grafo legato** (le descrizioni sono in `docs/decisioni.md`, "Fase 9 P13: integrazione", "Da provare sul telefono"):
   - **F113** (nascosti in tutte le superfici dei ricordi, anche durante la presentazione) e **F114** (le quattro scelte, S25 poi emulatore): senza cambi;
   - **F115-F117** (la notifica all'ora, una al giorno, revocata, generica a telefono bloccato, il tocco dopo lo sblocco, `am start -a ...OPEN_MEMORIES` con extra inventati): **dopo F147**; in F117 anche un tocco
     con la tendina vuota e il registro di ieri (NT-2): deve aprire la pagina Ricordi, non un ricordo;
   - **F118** (presentazione, canzone propria, focus audio con una chiamata finta): con F150;
   - **F119** e **F120** (zero traffico a mappa spenta, solo `tiles.openfreemap.org` a mappa accesa): in debug e in release (F151); **F121** (regioni): non c'e' ancora, P5; **F122** (media solo sul NAS): senza cambi;
   - **F126** (Diagnostica e log): senza cambi; in piu' guardare `NOTIFY_LATE` dopo un riavvio (DG-2);
   - **F127** (azzeramento): in piu' un lavoro `memories-notify` lanciato a mano mentre si azzera (AZ-1);
   - **F129** (due dispositivi) e **F130** (ricordi con le persone e "Disattiva e cancella"): F130 dopo F148.
9. **I momenti con il tablet** (F128): senza cambi.

---

## 5. Flussi verificati da capo a fondo

Non hanno voci (o solo voci minori): il percorso completo e' nel codice, e dove c'e' una prova a processo caldo l'ho tenuta in conto.
- **Montaggio e avvio.** `MyCloudGalleryApp.onCreate` costruisce `MemoriesGraph` e `MapGraph` senza I/O (`MyCloudGalleryApp.kt:142-143`; `MemoriesWiring.build` e `MapGraph` hanno solo campi pigri); il montaggio dei ricordi aspetta il primo
  disegno e la famiglia pronta, legge lo scaffale con `restore()` fuori dal thread principale **prima** di tutto (`MemoriesGraph.kt:151-170`) e poi avvia `run.watch`, `notifier.watch`, il janitor e i due collettori dei nascosti e delle persone
  (`:174-186`); i due ViewModel dell'host (`MemoriesHostViewModel`, `MapHostViewModel`) non costruiscono niente; la sezione Luoghi parte `Lazily`; MapLibre solo da `AppMapEngineStarter`, dopo il primo fotogramma
  (`MapLibreSurface.kt:155`).
- **Nascosti.** "Nascondi" da `GalleryGraph` → `hiddenListeners` (`GalleryGraph.kt:514-524`) → `MemoriesGraph.onHiddenChanged` (`prune()` e `revalidate()`, `MemoriesModule.kt:88`) e `MapGraph.onHiddenChanged` (indice rifatto subito e mini-mappa
  svuotata, `MapModule.kt:56`); un nascosto da un altro dispositivo cambia l'istantanea e i ricordi si ripuliscono dopo 3 s di calma (`MemoriesGraph.kt:183-185`), la mappa con `SNAPSHOT_CHANGED` (`MapLive.kt:52-57`). `HiddenMask` e'
  legato in Hilt (`AlbumsModule`, P13-4) e il filtro finale ripete la verifica in sezione, pagina, "Tutti i ricordi", presentazione, bolle, griglia e mini-mappa.
- **Visti, scartati, "Mostra meno" e "Mai".** I fogli (`LessFlow`) → facciata → `MemoryEngine.setLess`/`dismiss`/`markSeen` → `AliasFollowingSettings(SyncedMemoriesSettings(parts.personalStore))` (`MemoriesWiring.kt:128`), con `prune()`: nel
  telefono subito, sull'altro dispositivo al giro dei dati personali (`MemoriesIntegrationTest`). "Visto" solo dalla pagina e dalla presentazione, mai dalla sola sezione.
- **Le notifiche, a processo vivo.** Il giorno (`MemoryNotifyRun`: finestra H..H+3 h, condizioni, registro prima della notifica, al massimo una per giorno di calendario), il contenuto (generico di serie, "Titolo e foto" mai con nomi, versione
  pubblica sempre generica: `MemoryNotifyPolicy.content`, `MemoryNotifier.build`), il `PendingIntent` immutabile con la sola azione, `MainActivity` `singleTask` che pulisce l'intent e serve il tocco sotto `AppLockGate`
  (`AppNavHost.kt:215`, `ServeOpenMemoriesRequests`), la revoca a ogni cambio dello scaffale e dei nascosti, il permesso chiesto solo da un tocco (`rememberMemoriesActions`, `AppMemoriesNavigation.kt:152-184`; `MemoriesSettingsRoute.kt:65`).
  Il lavoro vive nel processo vivo: il difetto e' il processo morto (NT-1).
- **Impostazioni.** "Ricordi e notifiche" (frequenza, ora a quarti d'ora, schermata di blocco, permesso e canale, "Ricordi con le persone", scelte "Mostra meno" e "Mai", musica) e "Mappa" (interruttore con la nota breve, "Solo sul Wi-Fi"):
  ogni tasto e' legato al metodo vero; le notifiche e la mappa sono per dispositivo (`FileNotifySettings`, `DataStoreMapSettings`), le scelte dei ricordi viaggiano. L'interruttore della mappa vale dalla richiesta dopo (`MapNetWiring.state`,
  `MapLibreInit.kt:44-51`) e `setConnected` segue scelte e rete (`:139-149`).
- **La mappa.** Camera iniziale dall'ultima posizione locale o da tutte le foto con zoom <= 5, mai la casa (`MapController.decideInitial`); bolle al piu' 60 a camera ferma fuori dal thread principale; tocco su una bolla (avvicina o apre la
  griglia, `MapRoute.kt:78-83`); griglia viva sull'istantanea visibile, con il filtro finale e il viewer su quella vista (anche per i media solo sul NAS: la query dei punti non guarda la sorgente, `MapPointsQueries.kt:120-124`); "Mostra sulla
  mappa" dal viewer (`AppViewerPlaceMap.show` → `viewer.close()` → `openMapAt`, `AppMapNavigation.kt:109-112`; il fuoco mai per un nascosto, `MapController.focusSpot`); la mini-mappa sempre con lo stile offline (`MapStyles.miniMap`), cache di sei
  immagini in memoria e svuotata al blocco e ai nascosti; stile della mappa grande offline a mappa spenta e senza regioni (`MapStyles.choose`), online solo ad interruttore acceso.
- **Azzeramento e Diagnostica.** Ricordi e mappa per primi (`LocalData.kt:160-163`), poi lavoro e notifica sempre (`:165-168`), poi `memories/` e `map/` fra i file (`PHASE9_FILES`, `:216, 268-271`); le due sezioni della Diagnostica sono `@IntoSet` e portano
  solo numeri, flag e codici (`MemoriesDiagnostics`, `MapDiagnostics`), con un `HOST` o `PATH` rifiutato come avviso.
- **Debug e release.** `DeveloperTools.installMemories` e' vuoto in release (`src/release/.../DeveloperTools.kt:68`); lo strumento di valutazione e' solo debug, in sola lettura, con un motore suo e lo scaffale in memoria
  (`AppMemoriesEvalEnvironment.kt:45-75`); le porte di serie di `feature-memories` non portano fake in release (P13-11, `noop/SessionSettings.kt`); `AboutLibraries` ha le nuove licenze.

---

## 6. Note

**Scartati dopo il controllo nel codice:**
- **"Canzone di serie".** Non esiste, per la risposta Q3: `MusicPackCatalog.TRACKS` e' vuoto (`core-memories/.../contract/Music.kt:74`), `AppMusicLibrary.install` risponde falso (`MemoriesMusic.kt:91`) e la pagina offre solo "Nessuna" e "Una canzone
  mia" (`MemoriesSettingsScreen.kt:271-276`). E' coerente; manca solo la decisione sui brani del pacchetto libero (REPORT.md).
- **`MemoriesFacade` che non fa niente prima del montaggio** per `markSeen`, `dismiss`, `setLess`, `setPeopleMemories` (`MemoriesFacades.kt:52-67`): nessuna schermata offre quei comandi prima del montaggio (lo scaffale e' "sto preparando" e le scelte
  sono vuote), quindi non c'e' un tasto morto.
- **Due tocchi veloci su "+15 minuti"** dell'ora delle notifiche: `update` legge `prefs.value` prima della scrittura (`MemoriesSettingsController.kt:112`) e `FileNotifySettings.set` aggiorna il flusso dopo il file; un secondo tocco entro pochi
  millisecondi perderebbe il primo. Una finestra che un dito non raggiunge.
- **"Zone senza Internet" che non porta da nessuna parte** (`openRegions`): voluto (P5).
- **Il registro scritto prima della notifica** (un giorno perso se il processo muore in mezzo): e' E-NT5, voluto.
- **La Diagnostica che apre il DataStore della mappa** (`MapDiagnostics.kt:32`): coerente con "alla prima pagina che le legge" (P13-2); l'indice dei punti, invece, non lo costruisce.

**Non guardato qui:** la sicurezza (altro revisore); la qualita' dei ricordi sui dati veri (F125, Q7); le prestazioni (P12, F123-F124); il cancello di rete della mappa in se' (`TilePolicy`, `MapGateInterceptor`: sicurezza).

---

## 7. Stato di ogni voce e proposta di correzione (31/10)

Nessuna voce e' corretta. Il pacchetto e' la proposta; ogni correzione porta un test rosso senza la modifica.

| Voce | Gravita' | Stato | Pacchetto |
|---|---|---|---|
| NT-1 Notifica a processo morto | Blocca | confermato | A |
| NT-2 `openTarget` con registro vecchio | Minore | confermato | A |
| AZ-1 Notifica in corso durante l'azzeramento | Minore | plausibile | A |
| RP-1 "Non trovo questo ricordo" prima del montaggio | Sbagliata | confermato | B |
| RP-2 Sezione "vuota" con l'istantanea null | Sbagliata | confermato | B |
| MO-1 Ricordi delle persone potati all'avvio | Sbagliata | plausibile | B |
| IM-1 Riga di Impostazioni dai valori di serie | Minore | confermato | B |
| MO-2 Errore notturno che salta le Utility | Sbagliata | confermato | C |
| MO-3 Giro di oggi solo al cambio di scheda | Minore | confermato | C (decisione 2) |
| MU-1 Canzone preparata sul thread principale | Sbagliata | plausibile | D |
| MU-2 Focus audio negato | Minore | confermato | D |
| MU-3 Permesso della canzone non conservato | Minore | confermato | D |
| MA-1 Errore dell'indice letto "nessuna posizione" | Minore | confermato | E (decisione 4) |
| DG-1 Conti per processo | Minore | confermato | E (decisione 3) |
| DG-2 `NOTIFY_LATE` con due basi | Minore | confermato | E (decisione 3) |
| DR-1 R8 con MapLibre in release | Minore | plausibile | E (solo la prova F151) |

**Pacchetti:**
- **A, "notifiche a processo morto":** NT-1 con `MemoriesGraph.awaitLive`, l'attesa dell'istantanea, `enterWorker` prima dell'attesa e il test del processo nato per il lavoro; NT-2; AZ-1 (la corsa si ferma con il grafo). Per primo: e' l'unica voce che blocca.
- **B, "lo stato prima del montaggio":** RP-1 (la facciata tace finche' non e' montata), RP-2 (`refine` con l'istantanea null), MO-1 (il pruner non toglie i `PERSON` con Persone `UNKNOWN`), IM-1. Test con `live` null, istantanea null e `FakePeopleSwitch(UNKNOWN)`.
- **C, "manutenzione e giri":** MO-2 (`nightly` in `quietly`) e MO-3 (giro `OPEN` al ritorno in primo piano e al primo cambio di istantanea con lo scaffale vuoto).
- **D, "musica":** MU-1, MU-2, MU-3 (`prepareAsync`, il rifiuto del focus come "muta", il messaggio della scelta non conservata).
- **E, "mappa, Diagnostica e release":** MA-1 (dopo la decisione), DG-1 e DG-2 (conti su file), DR-1 (solo la prova F151, e se serve il `-keep`).

**Decisioni per l'utente** (dettaglio in `REPORT.md`): (1) la notifica a processo morto: attendere il montaggio nel lavoro (proposto) o il piano B di D20; (2) quando rifare il giro dei ricordi di oggi; (3) conti di Diagnostica
persistenti o dichiarati "dall'avvio"; (4) uno stato d'errore per i punti della mappa (L495); (5) i brani del pacchetto di musica libera (nessuna musica di serie fino ad allora).


---

## Stato dopo il pacchetto ricordi e notifiche (31/10)

Pacchetto "ricordi e notifiche" (branch `fase9/corr-ricordi`; dettaglio, decisioni e prove in `docs/decisioni.md`, "Fase 9, correzioni dopo le revisioni: ricordi e notifiche (31/10)"). Le tabelle sopra non sono riscritte. Ogni voce ha un test rosso senza la correzione; **nessuna prova sul telefono** (F147-F153 restano da fare, aggiornate in fondo alla sezione di `docs/decisioni.md`).

| Voce | Stato | Come |
|---|---|---|
| **NT-1** notifica a processo morto (Blocca) | **Corretta** | Il lavoro aspetta il montaggio senza il primo disegno (`MemoriesGraph.awaitLive`) e l'istantanea della Libreria, al piu' 20 s; `enterWorker()` prima dell'attesa; lo stesso orario non si accoda due volte; niente piano B (D20). `MemoriesWorkTest` sostituisce `senzaRicordiMontatiNonFaNiente` con il processo nato per il lavoro. |
| **NT-2** `openTarget` con il registro vecchio | **Corretta, in modo diverso dalla proposta** | Apre il ricordo del registro solo se il giorno e' oggi, altrimenti la pagina Ricordi. **Non** usa `posted()`: il tocco ha gia' tolto la notifica (`setAutoCancel`) prima che l'app legga il registro, e ogni tocco vero finirebbe nella pagina Ricordi. |
| **AZ-1** notifica in corso durante l'azzeramento | **Corretta** | `MemoriesGraph.stop` chiama `MemoryNotifyRun.stop`: una corsa in corso si annulla e si aspetta; `stopped` e' guardato prima del registro e prima di pubblicare; dopo lo stop niente si ripianifica. |
| **RP-1** "Non trovo questo ricordo" prima del montaggio | **Corretta** | `Memories.memory` non emette niente prima del montaggio (facciata) e prima dell'istantanea (motore): pagina e presentazione restano in caricamento; `NotFound` e `Gone` solo a ricordi montati. |
| **RP-2** sezione "vuota" con l'istantanea nulla | **Corretta** | `FinalFilter.refine` aspetta (nessun tetto che butta le voci); la sezione e "Tutti i ricordi" restano sull'ultimo scaffale; il filtro finale dei Nascosti resta (senza istantanea non si mostra niente di non filtrato). |
| **MO-1** ricordi delle persone potati all'avvio | **Corretta** | `MemoryPeople.undecided` (stato di Persone `UNKNOWN`): il pruner non toglie i `PERSON` per cio' che dipende da Persone; escono con Persone spenta davvero e per le scelte esplicite. `PeopleLessJanitorTest` cambiato di conseguenza (`FakePeopleSwitch(UNKNOWN)`). |
| **IM-1** riga di Impostazioni dai valori di serie | **Corretta** | `MemoriesEntryStatus.summary` e' `null` finche' le preferenze non sono lette; poi l'ultimo valore letto resta in memoria. |
| **MO-2** errore notturno che salta le Utility | **Corretta** | `MemoriesGraph.nightly` dentro `quietly`: conta `roundErrors` (Diagnostica) e risponde "non finito". |
| **MO-3** giro di oggi solo al cambio di scheda | **Corretta** | Il giro `OPEN` anche a ogni ripresa con Raccolte aperta (`MemoriesForegroundRound`) e al montaggio se Raccolte era gia' aperta; con Raccolte aperta e oggi lo scaffale vuoto, a ogni cambio di istantanea un giro `LIGHT`. Mai nei lavori in background. |
| **MU-1** canzone preparata sul thread principale | **Corretta** | `MusicPlayback.start` e' una `suspend` che apre su `Dispatchers.IO` e si annulla con l'uscita. |
| **MU-2** focus audio negato | **Corretta** | Un focus negato vale "musica mancante" (muta, con l'avviso); `PLAYING` solo se un lettore e' partito. |
| **MU-3** permesso della canzone non conservato | **Corretta** | `MemoriesMessage.FAILED` dalla pagina delle impostazioni (anche se la scelta non si salva). |
| **DG-1** conti per processo | **Corretta** (decisione del 31/10: si persistono) | `memories/diag-counts`: solo numeri, 14 giorni, scrittura atomica, tolto dall'azzeramento; L492 riscritta (resta per la mappa). |
| **DG-2** `NOTIFY_LATE` con due basi | **Corretta** | `delivered` sullo stesso file e sulla stessa finestra di `late`. |

Restano per l'altro pacchetto MA-1 e DR-1. Il piano B di D20 non si fa (decisione del 31/10): L522.
