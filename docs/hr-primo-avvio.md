# HR - primo avvio (11 novembre 2026)

Base: `261a3553`, worktree `mcg-k5`, ramo `fix/hr-primo-avvio`. Nessun dispositivo, adb, push, modifica di engine o lettura di local.properties.

## Esito delle voci

1. **Fatto in parte - crash Album:** riprodotta con Compose la collisione delle chiavi degli avvisi, corretta con tipo + UUID completo e verificata nella suite Album completa (670 test). La causa dell'incidente specifico dell'utente resta non attribuita senza log. Non aggiunti catch alle letture di Album.
2. **Fatto nelle prove JVM - lavoro di fondo:** raggruppamento delle pubblicazioni della timeline in primo piano e ampliamento della priorita' Linux gia' presente. Resta da verificare sul telefono la fluidita'; dal giro CR-1 la priorita YieldToApp segue tutto il primo piano; tocchi, scroll e inerzia regolano solo Pace.APP_IN_USE; dopo 800 ms di calma torna il ritmo normale, senza cambiare i fermi termici o di rete.
3. **Fatto - Ricerca e modelli:** preparazione immagini su Default; lettura/salvataggio misura su IO; errore visibile e Riprova, timer separato di 120 s, modello pronto obbligatorio e cancello termico prima del motore. Scansione spazio indice su IO con cache di un minuto. Perimetro models autorizzato dall'aggiunta dell'utente.

## Cause del crash e ipotesi controllate

| Ipotesi | Evidenza e stato |
|---|---|
| `search_history` assente / database bloccato nel seminatore | `app/src/benchmarkRelease/.../bench/BenchDatabaseFiles.kt`: la base ha gia' la separazione building/ready/database attivo. Esclusa come prova di un difetto di produzione: sorgente della sola variante benchmark, non riprodotta qui la vecchia implementazione. |
| Apertura concorrente della prima istanza Room | `core-database/.../GalleryDatabase.kt`: Room crea lo schema completo, comprese cronologia e tabelle album, prima di restituire la connessione; nessuna creazione manuale differita. Test nuovo su file con 12 coroutine, 240 letture e 20 scritture, risultati sotto. |
| Due istanze sullo stesso database all'avvio normale | `GalleryGraph.kt`: una sola costruzione della galleria; `SyntheticLibraryStack` e' su file sintetico separato; cancellazione del database reale in `LocalData` solo durante azzeramento, dopo arresto. Esclusa dal percorso normale per lettura statica, non da un trace Android. |
| Album vuoti / smart senza indice | `AlbumReader.bestOfBusiestDay`: ritorna null se insieme vuoto, fallback protetti. `SpecialSummary.of`: controlla size prima di indice zero; `FilteredTimelineView` mantiene snapshot immutabile e mappa lazy sincronizzata. Nessuna causa riprodotta. |
| Album cambia mentre si caricano copertine | `AlbumsTabScreen.FollowCoverWindow`: righe vuote saltate; `coverRequestsOfRow` controlla limite superiore quando una vecchia riga visibile scompare. Chiavi di riga generate da indici non negativi. Nessuna causa riprodotta. |
| Chiavi duplicate degli avvisi | `AlbumsTabScreen.notices`: **riprodotta** in HR-5 con due avvisi validi sintetici di album diversi e stesso hash. Compose cade per chiave duplicata. Correzione: tipo avviso + UUID completo; verifica positiva finale sotto. Il legame con D4 del telefono resta non dimostrato. |
| first su flusso vuoto | Il first in AlbumsLive e' nel percorso di copia/condivisione, non nell'apertura della scheda; viste speciali con guardia su vuoto e snapshot immutabile. Nessuna causa riprodotta su questo percorso. |

Ulteriore verifica HR: eseguita una regressione Compose con due avvisi Inherited di album diversi, UUID canonici sintetici `fccc5285-938f-1c52-eb45-9818b993dc41` e `88d35679-7ed3-a3cd-703b-bc0cce614d0b`, stesso titolo. Gli hash String coincidono (106731507), quindi coincidono gli hash degli avvisi: il codice precedente genera due chiavi uguali. HR-5 ha riprodotto IllegalArgumentException: Key notice-1562590278 was already used. La correzione usa tipo dell'avviso e UUID completo, senza hash e senza nascondere errori; prova dopo sotto; non e' una ricostruzione dell'incidente sul telefono.

Pressione di memoria/OOM: plausibile non riprodotta. Il motore della misura e' nello stesso processo e il sorgente RealDeviceProbe documenta il costo nativo elevato; avvio di immagini e viste Album puo' coincidere con quel costo. Senza crash log non si attribuisce la chiusura alla memoria. Spostare preparazione e I/O fuori dal main riduce i blocchi, non dimostra un calo del picco di memoria GPU.

Altre pagine: Raccolte legge viste immutabili; Fotocamera e Messaggistica usano `CatalogFilterMembership` su IO con accorpamento 2,5 s. Cerca apre l'indice su Default, serializzato da Mutex, e usa `OffMainSearchEngine`; la cronologia usa DAO Room sospendenti. Impostazioni/Ricerca aveva la preparazione bitmap sul chiamante Main, corretta qui. AppIndexerControl invece riceve gia' lo scope Default da GraphModule: non e' provato il walkTopDown sul main in produzione; restava comunque ripetuto a ogni refresh, ora IO con cache. Viewer usa snapshot e cache dei riferimenti recentemente usciti; nessuna modifica nel perimetro H1. Queste sono ispezioni statiche, non prove di assenza di ogni crash.

## Inventario dei lavori dopo onboarding / primo disegno

Le frequenze sono quelle del codice, non misure sul telefono. Non esiste un tetto unico della concorrenza fra tutti i sottosistemi.

| Lavoro e sorgente | Esecuzione / concorrenza / priorita' | Scritture e notifiche |
|---|---|---|
| Telefono, `DefaultPhoneLibrarySync`, META | IO, giri serializzati da Mutex, lotti 500; META a fette 30 s con pausa 5 s fra fette esaurite. Attach ora eredita YieldToApp; anche PhoneSyncWorker eredita lo stesso dosaggio. | Catalogo e cursore per lotto; META esegue updateMetadata + updateWork per successo (due scritture), non accorpati da HR; change_seq dopo commit. Le invalidazioni Room restano per transazione. |
| NAS, `NasStages.scan`, `DefaultNasScanner` | Tapper WorkManager, regole INDEX_SYNC, lotti catalogo 500, fino a 4 rilevamenti paralleli, scansione entro SLICE; ora YieldToApp anche per scan. | Catalogo per lotto, stato scansione e outbox separati; non una transazione per tutta la libreria. |
| Replica/pubblicazione indice, `NasStages.sync`, `LocalReplica`, `OutboxIndexPublisher` | Mutex per share/testa, rete IO; ora YieldToApp su sync; refresh catalogo replica a gruppi fino a 2000 chiavi. | Transazioni per testa/segmento; catalogo emette dopo applicazione, miniature su file; politica di flush esistente. |
| Timeline, `CatalogTimelineRepository` | Default con YieldToApp, consumatore seriale; nuove finestre 250 ms solo in primo piano. | Nessuna scrittura Room aggiuntiva; pubblica snapshot e viste filtrate insieme; file snapshot accorpato 5 s, rimozioni salvate subito. `forget` dei nascosti resta immediato. |
| Copie simili, `SimilarCopiesGraph`, `CatalogSimilarCopies` | Default + YieldToApp, un job per grafo e Mutex nel rilevatore; prima partenza dopo disegno + 10 s, poi nessuna attesa iniziale; fette 1 s + pausa 1 s durante interazione, 30 s a riposo/background, tetto cumulato 600 s. | Firme per versione e unioni per secondo; decodifiche fuori dalle scritture brevi. Stato pending/awaiting dopo i giri, non per frame. |
| Miniature / viewer, `ImagingBudget`, `PipelineThumbnailLoader` | Default/IO; 2-4 miniature insieme, viewer massimo 3, priorita' a finestra visibile. | Cache immagini, non un aggiornamento Room per ogni fotogramma; arrivi per richiesta. Nessuna modifica HR. |
| Indice ricerca / modelli, `SearchGraph`, `LocalModels` | Indice Default + Mutex; modelli IO, refreshOnce per processo dentro YieldToApp. Il refresh prima rileggeva circa 1,1 GB per processo; CR-5 legge stat e una nota <1 KiB/modello se invariati, altrimenti verifica tutto. La verifica per caricare un motore resta intera. ModelFetchWorker usa YieldToApp. Chiavi accorpate tramite canale conflated; documenti con ritardo e conflate. | Indice locale e file; flussi version/stati, non scritture catalogo per immagine sintetica. |
| Misura dispositivo, `RealDeviceProbe` | 20 scene e 2 giri + avvio; preparazione su Default, motore sul suo dispatcher, a priorita normale (CR-4: nessun quietly/YieldToApp). Il giudizio termico viene dal valutatore delle regole. Un tentativo per processo, timer UI 120 s, rifiuto se caldo, rilascio motore nel finally. La cancellazione non termina una chiamata nativa bloccata. | Un risultato e piccolo file finale; nessuna scrittura catalogo per scena. |
| IA / OCR, `AiWork`, `AiComputeEngine` | Worker a tappe, YieldToApp gia' presente, Pace.APP_IN_USE per attivita' intensive, buffer locale e lotti secondo control. | Indice e outbox per risultati; non regolati dalla nuova finestra se non producono una modifica timeline. |
| Volti, `FacesWork` | Worker dedicato; YieldToApp gia' presente, massimo 2 thread inferenza in primo piano; notturno separato. | Archivio volti separato e aggiornamenti a lotti; budget e checkpoint esistenti. |
| Backup, `SafeUploadEngine` | Parallelismo predefinito 2; lettura in primo piano su executor singolo con Thread.MIN_PRIORITY, regole e semafori di corsia esistenti. | Diario per passaggio di stato, catalogo per copia verificata, outbox; nessun accorpamento nuovo HR sul diario. |
| Album smart / viaggi, `AlbumsGraph` | ChangeBatcher con quiete 5 s, motori con budget; lavori di ripresa separati. | Proiezione album e smart per gruppo; aggiornamenti stato elenco. Nessuna modifica HR. |
| Manutenzioni / ricordi / mappa / widget / manifest | LibraryMaintenanceWorker in carica, riconciliazione/hash/luoghi/album/ricordi a budget; ricordi giornalieri dopo apertura Raccolte, mappa su richiesta; widget e manifest dopo primo disegno. | Database e file dei sottosistemi; frequenza governata dai trigger, non dal frame. Nessuna nuova priorita' HR su questi percorsi. |

La nuova finestra agisce sul produttore della timeline, non sulla griglia H3 e non sul flusso completo degli eventi del catalogo: gli altri consumatori conservano tutte le sequenze. Riduce patch e allocazioni, **non** il numero di transazioni Room. Non sono stati aggiunti ritardi alla scansione stessa: tempo totale a telefono fermo da misurare, nessuna promessa numerica senza hardware.

## Aggiunta HA verificata nel codice

- a: confermati avvio immediato e fino a 20 fette da 30 s. Ora anche la prima lettura del rilevatore aspetta primo disegno + 10 s, una volta per processo (delta HR-7); YieldToApp per tutto il primo piano; fette da 1 s con pausa 1 s durante busy, 30 s senza pause a riposo o in secondo piano, stesso tetto cumulato di 600 s di budget. Non riduciamo il tetto di lavoro a 20 secondi per errore. Il dosaggio usa il segnale di interazione comune dell'app: a UI ferma riprende il budget pieno dopo 800 ms.
- b: confermati timeout mancante, errore ingoiato, misura senza modello e duplicazione frase. Correzioni nella sola pagina models, core-ai e adattatore DI; stato di errore esplicito e Riprova, timer 120 s indipendente dal lavoro nativo. Il tentativo cancellato mantiene il posto fino alla fine reale, anche riaprendo la pagina. Con telefono gia' caldo (almeno LIGHT o headroom >= soglia di riduzione delle regole) la misura non inizia. La stima delle notti resta come prima, fuori incarico.
- c (corretto da CR-2): il primo valore true non viene perso, ma usa RULES dopo il disegno/refresh solo per modello non Ready e non rimosso dall'utente; un vero false -> true usa ROLE_CHANGED. Due avvii non sono due cambi di ruolo. Nessuna pubblicazione NAS o afterNews per risultati vuoti. fetchNow usa REPLACE, non KEEP come riportato erroneamente nella prima consegna.
- d: confermato walkTopDown a ogni refresh. La catena AiModule -> AppIndexerControl -> graph.scope (GraphModule) usa gia' Default: il passaggio sul main ipotizzato da HA non e' confermato in produzione. Ora IO e cache serializzata di 60 s, condivisa dall'IndexerControl singleton; leggere arretrato non obbliga a ricamminare i file. Il test conta 20 aperture = una lettura, poi una nuova dopo scadenza. Spazio mostrato potenzialmente vecchio di un minuto: scelta deliberata.
- e: confermato il flag mancante in PreparationWorker. Ora usa lo stesso richiamo gia' esistente per replica/dati personali, anche dopo tappa parziale. Ordine dai piu' recenti valutato e lasciato invariato: similarRuns usa query crescente, completamento del secondo e nextSecond+1; invertirlo significa modificare query, contratto e ripresa, oltre il piccolo trigger sicuro. Resta proposta, non implementazione dichiarata.

Non sono state prese per misure nuove le durate e i consumi storici del report HA. Proposta: processo separato per inferenza/misura soltanto con gestione di binder, morte processo e rilascio GPU dedicata; non e' una piccola modifica sicura per questa beta. Il timeout UI non pretende di poter terminare il driver.

## Numeri e verifiche

I test sintetici misurano lavoro/emissioni, non FPS Android o tempi del NAS.

| Prova JVM | Prima (HR-2) | Dopo (HR-3) |
|---|---:|---:|
| Commit simulati (100 media, un lotto ogni 10 ms) | 100 | 100 |
| Patch costruite | 100 | 4 |
| Emissioni snapshot, escluso valore iniziale | 100 | 4 |
| Sequenza finale / media conservati | 100 / 100 | 100 / 100 |
| Preparazione scene sul thread chiamante | si, regressione fallita | no, regressione passata in HR-4 |

Le 100 scritture sono commit del ModelSource sintetico, non un conteggio strumentato di Room. La modifica non accorpa transazioni del catalogo: cambia il produttore degli snapshot. Le invalidazioni Room restano per commit. Cache spazio indice: 20 richieste consecutive producono una lettura filesystem; dopo 60 s una seconda lettura (test JVM). Non e' una misura di 20 riaperture fisiche della pagina.

Nessun numero di FPS, durata reale della prima scansione o assenza di regressione S25/Tab S9 e' deducibile da questi dati.

## Prove sul telefono (non eseguite)

1. Installazione pulita firmata, onboarding reale; aprire immediatamente Album e alternare Album/Raccolte/Cerca/Impostazioni almeno 20 volte durante la scansione. Atteso: viste vuote o parziali valide, nessuna chiusura. Difetto: eccezioni fatali o stato mai aggiornato.
2. Durante lettura telefono + NAS scorrere e rilasciare fling nella Libreria, cambiare pagina, aprire/chiudere viewer. Atteso: gesto e inerzia continui; difetto: arresto al rilascio, pause visibili o regressione delle soglie S25/Tab S9. La parte di ricezione/gesto e' di H3.
3. Ripetere scansione identica a telefono fermo, poi in background: confrontare durata e quantita' importate con base. Difetto: durata raddoppiata a telefono fermo, sequenze ferme o dati persi. Nuovi media in primo piano possono arrivare con ritardo fino alla finestra piu' il tempo di calcolo.
4. Prima apertura Ricerca e modelli con modello gia' pronto e misura assente. Atteso: pagina disegnata subito, misura successiva, motore rilasciato; controllare main thread / GC / memoria durante preparazione e inferenza. Nessuna deduzione di FPS dalla JVM.
5. Nascondere e riaprire immediatamente durante import: il media deve sparire subito e non riapparire al riavvio (forget bypassa l'accorpamento).
6. Recuperare Diagnostica > registro crash e stack completo (caused by, versione, ora); logcat crash/AndroidRuntime/SQLite/Room con contesto prima della chiusura. Cercare no such table, database is locked, indici fuori limite, chiavi duplicate e OOM. Non stampare account, percorsi privati o credenziali nel report pubblico.

7. CR-2: con preset Notte di giorno e ruolo inizialmente acceso, modello mancante: trigger RULES e attesa della fascia/carica/termica/rete; un vero spegni-accendi usa ROLE_CHANGED e rete non a consumo. Due avvii con modelli Ready non accodano nulla; dopo Rimuovi il modello resta assente al riavvio.
8. Preparare miniature di coppie NAS prima indecidibili: al termine della tappa deve partire un nuovo confronto e i gruppi devono aggiornarsi senza riaprire l'app. L'ordine crescente resta invariato.
9. Con telefono gia' caldo aprire la pagina modelli: nessuna inferenza deve partire; deve comparire il fallimento con Riprova. Dopo raffreddamento, Riprova deve riuscire. La scadenza del driver bloccato e' provata da finto non cancellabile in JVM, non provocata sul telefono.
10. Con inerzia della Libreria di oltre 800 ms verificare che il dosaggio resti ridotto fino alla fine dello scroll; dopo la calma deve tornare normale. Con due finestre, chiuderne una mentre l'altra scorre non deve dichiarare la UI ferma.

Scelte da validare: finestra 250 ms in primo piano; calma di 800 ms dopo l'ultima interazione; confronto con fette da 1 s e pausa da 1 s mentre la UI e' usata, attesa iniziale 10 s; cache spazio indice 60 s. Il nested scroll osserva senza consumare; la Libreria usa inoltre il booleano di inerzia (snapshotFlow), non la posizione per fotogramma. Il motore delle tappe rivaluta il ritmo ai suoi controlli ordinari (fino a 5 s), mentre YieldToApp legge il segnale a ogni ripresa della coroutine.

Screenshot: previsti normale/misura/errore, chiaro/scuro, telefono 360x780 e tablet 1280x800. Esito e file sotto.

## Riferimenti puntuali

- `core-database/src/main/kotlin/dev/pampa/mycloudgallery/core/database/GalleryDatabase.kt:311`
- `app/src/benchmarkRelease/kotlin/dev/pampa/mycloudgallery/bench/BenchDatabaseFiles.kt:26`
- `feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/tab/AlbumsTabModel.kt:46`
- `feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/tab/AlbumsTabScreen.kt:236`
- `core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums/personal/AlbumReader.kt:134`
- `core-library/src/main/kotlin/dev/pampa/mycloudgallery/core/library/index/FilteredTimelineView.kt:22`
- `app/src/main/kotlin/dev/pampa/mycloudgallery/ai/SearchScreenPorts.kt:120`
- `core-ai/src/main/kotlin/dev/pampa/mycloudgallery/core/ai/runtime/RealDeviceProbe.kt:61`

## Batch del semaforo

Tutti con un solo posto, no-daemon/no-watch-fs/max-workers=2/no-parallel, nessun clean, nessun dispositivo, compilatore Kotlin in-process. HR-2 e successivi offline. Heap del progetto 4 GiB + 4 GiB (eccezione dichiarata dal brief H); nessun JAVA_TOOL_OPTIONS. Le durate qui sono dall'acquisizione del posto al rilascio, non includono la coda. Ogni esito e' stato comunicato subito come richiesto.

| Nome | Task / perimetro | Esito | Durata | Rilascio / Java propri |
|---|---|---|---|---|
| HR-1 | Due regressioni core-library/core-ai | FAIL prima dei test: opzione -P spezzata da PowerShell, corretta quotatura | 17 s | rilasciato, nessun processo residuo |
| HR-2 | Due regressioni mirate, continue | FAIL atteso: 100 emissioni e preparazione sul chiamante | 1m37s | rilasciato, nessun processo residuo |
| HR-3 | Suite core-library/core-database/core-ai + controller Album | 305 + 290 + 5 OK; FAIL compilazione nuovo test IA per import mancante, poi corretto | 11m09s | rilasciato, daemon 15720 e worker 48792 terminati |
| HR-4 | Suite core-ai + recordRoborazziDebug HR | OK: 350 test IA (anche 2000 giri caos), 12 screenshot; controllo visivo ha poi trovato accenti corrotti nel file stringhe, ripristinato dalla base UTF-8 prima di HR-5 | 8m53s | rilasciato, daemon 38004 e worker 50012 terminati |
| HR-5 | Suite feature-search, regressione Album, 10 classi app | App compila e 38 test OK; ricerca 277 test/1 FAIL (errore tardivo dopo cancellazione); crash Album riprodotto con chiave duplicata. Entrambi corretti prima di HR-6 | 12m31s | rilasciato, daemon 50440 terminato |
| HR-6 | Nuovi PNG, suite Album completa, poi suite ricerca completa e test app mirati | OK: 12 PNG, 670 Album, 278 ricerca, 38 app | 8m37s | rilasciato, daemon 46564 e 57232 terminati |
| HR-7 | Delta avvio copie simili: prima richiesta con attesa, successive immediate | OK: 2 test, compila app e test | 4m01s | rilasciato, daemon 63132 terminato |

La fixture della collisione contiene UUID canonici sintetici: non sono stati letti avvisi, account o dati dell'utente. La prova di apertura Room ha 12 coroutine, 240 letture e 20 scritture su file nuovo; non ha riprodotto search_history assente o database locked. Il confronto prima/dopo della timeline usa la logica precedente con il solo parametro di finestra aggiunto ma ancora inerte nel controllo negativo HR-2.

Test precedenti aggiornati: accensione ruolo e persistenza della misura richiedono ora il modello Ready nella fixture; il tentativo senza modello o dopo cambio ruolo fallito deve fare zero misure, non una misura finta riuscita. La nuova prova del primo stato controlla ruolo e arretrato noti prima di eseguire coroutine o I/O.

## Commit per l'aggiunta HA

- a: `738bf3ed` - precedenza a tocchi/scroll/inerzia, ritorno a ritmo normale a riposo, ritardo confronto iniziale e fette ridotte.
- c: `e794fe40` - prima emissione del ruolo gia' acceso, controllo negativo drop(1), richiesta dopo primo disegno; ModelFetchWorker con YieldToApp.
- d: `110c42b7` - cache spazio indice su IO e test del numero di letture.
- e: `e8eecd11` - trigger del confronto dopo preparazione NAS e test di wiring del worker (non e' una prova end-to-end del NAS vero).

Primo commit HR: `9991e961` (timeline e database nuovo). b: `eac55b8f`; crash Album: `27dd89bf`. La documentazione e' l'ultimo commit del ramo.

## Chiusura delle voci e limiti

| Voce | Stato verificato |
|---|---|
| 1 crash Album | **Fatto in parte**: collisione delle chiavi riprodotta e corretta, 670 test Album verdi. Non attribuito l'incidente reale D4; servono crash log e stack del telefono. |
| 2 lavoro di fondo / emissioni | **Fatto in JVM**: inventario sopra, dosaggio comune sui percorsi modificati, 100 -> 4 emissioni, nessun media perso, ritorno a pieno ritmo a riposo e niente ritardo sulle richieste successive. Fluidita' e durata della scansione reale non verificate. |
| 3 Ricerca e modelli | **Fatto**: nessuna preparazione bitmap, lettura o salvataggio misura sul main; stato iniziale dai dati noti, I/O e completamento dopo. Test controller, runtime, app e screenshot verdi. |
| a confronto all'avvio | **Fatto**, commit `738bf3ed`: disegno + 10 s una volta per processo, fette ridotte durante uso, test del riposo e della richiesta successiva immediata. |
| b misura | **Fatto**, commit `eac55b8f`: 120 s, errore/Riprova, niente modello assente o telefono gia' caldo, frase singola, una misura per processo anche se il driver ignora la cancellazione. Processo separato non implementato: proposta sopra. |
| c ruolo gia' acceso | **Fatto**, commit `e794fe40`: test negativo del vecchio drop(1), richiesta immediata della prima emissione true dopo il disegno, vincoli di rete mantenuti. |
| d spazio indice | **Fatto**, commit `110c42b7`: IO e cache 60 s; 20 richieste = una lettura. Il vecchio montaggio era gia' su Default, non dimostrato sul main. |
| e copie NAS | **Fatto il richiamo**, commit `e8eecd11`, test di wiring positivo. **Non fatto l'ordine dai piu' recenti**: cambiarebbe query e contratto di ripresa crescente; non e' la piccola modifica provabile richiesta. |

Rischi residui: GPU e memoria nativa della misura restano condivise con la UI; il timeout toglie l'attesa visibile ma non puo' terminare un driver bloccato. Le invalidazioni Room e le due scritture META per successo non sono eliminate: si raggruppa il produttore degli snapshot. Le altre manutenzioni conservano i budget e dispatcher esistenti indicati nell'inventario. Soglie S25 e Tab S9 **NV**, nessun benchmark o telefono eseguito. Nessun nuovo layer, animazione o servizio per cella/frame.

## Verifiche finali eseguite

| Modulo | Esecuzione | Fallimenti / saltati |
|---|---:|---:|
| core-library | 305 test (HR-3) | 0 / 0 |
| core-database | 290 test (HR-3) | 0 / 0 |
| core-ai | 350 test, inclusi 2000 giri caos (HR-4) | 0 / 0 |
| feature-albums | 670 test completi (HR-6) | 0 / 0 |
| feature-search | 278 test completi (HR-6, dopo registrazione PNG) | 0 / 0 |
| app | 38 test in 10 classi (HR-6); poi 2 test sul delta startup (HR-7, uno comune) | 0 / 0 |

Non sommare le ripetizioni come test distinti. L'ultimo delta tocca solo SimilarRunStart/SimilarRunPacing e il relativo montaggio in SimilarCopiesGraph; HR-7 lo ricompila e lo verifica. Gli XML prima/dopo e il riepilogo HR-6 sono in `docs/qa/hr/`. `git diff --check` OK. Dopo consolidamento dei commit, tree dei sorgenti e prove invariato: `467c60f2588034ed1f49830e0eeacbc8773a193d` (prima del solo commit documentale).

## Screenshot finali

12 PNG della pagina di produzione con stati sintetici, telefono 360x780 dp e tablet 1280x800 dp. Controllo visivo di campioni che coprono i tre stati, entrambi i temi e formati; accenti corretti e messaggio singolo, Riprova leggibile. Tutti i 12 test di registrazione e di composizione passano. Nessun dato personale.

- `docs/screenshots/beta-grafica/HR/modelli-errore-chiaro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-errore-chiaro-telefono.png`
- `docs/screenshots/beta-grafica/HR/modelli-errore-scuro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-errore-scuro-telefono.png`
- `docs/screenshots/beta-grafica/HR/modelli-misura-chiaro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-misura-chiaro-telefono.png`
- `docs/screenshots/beta-grafica/HR/modelli-misura-scuro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-misura-scuro-telefono.png`
- `docs/screenshots/beta-grafica/HR/modelli-normale-chiaro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-normale-chiaro-telefono.png`
- `docs/screenshots/beta-grafica/HR/modelli-normale-scuro-tablet.png`
- `docs/screenshots/beta-grafica/HR/modelli-normale-scuro-telefono.png`

## Riferimenti del montaggio finale

- `feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/tab/AlbumsTabScreen.kt:250`
- `app/src/main/kotlin/dev/pampa/mycloudgallery/di/GraphModule.kt:114`
- `app/src/main/kotlin/dev/pampa/mycloudgallery/work/UiWorkActivity.kt:7`
- `app/src/main/kotlin/dev/pampa/mycloudgallery/backup/SimilarRunPacing.kt:26`
- `app/src/main/kotlin/dev/pampa/mycloudgallery/work/NasWork.kt:465`
- `feature-search/src/main/kotlin/dev/pampa/mycloudgallery/feature/search/models/SearchSettingsController.kt:221`


## Giro di correzione 1: CR-1, CR-2, CR-4, CR-5

Questa sezione aggiorna la prima consegna; le tabelle dei commit e dei batch HR-1..HR-7 sopra restano la cronologia della prima implementazione.

- CR-1: priorita bassa e due thread dei motori per tutto il primo piano; busy regola soltanto ritmo e pause. Nessuna riapertura causata dai gesti.
- CR-2: il ruolo iniziale usa RULES, dopo il primo disegno e il refresh; Ready e removedByUser impediscono la richiesta. Solo false -> true usa ROLE_CHANGED. Un elenco di risultati vuoto non pubblica sul NAS e non notifica AI/volti.
- CR-4: la misura attesa dall'utente non passa piu da quietly. Il giudizio termico usa lo stesso valutatore/configurazione delle regole, inclusa isteresi.
- CR-5, integrazione inventario: il refresh modelli su IO, serializzato dal lock dei modelli e ora dentro quietly, costava circa 1,1 GB di letture e SHA-256 per ogni processo. Ora una nota locale della verifica confronta dispositivo, inode, dimensione, ctime e mtime (nanosecondi): a file invariati si leggono soltanto stat e nota (<1 KiB/modello). File nuovo/cambiato o stat/nota assente: verifica intera. La nota sopravvive al processo, viene tolta con il modello. Ogni apertura per il motore continua a verificare tutto sul proprio descrittore. Non e una cache di fiducia per caricare modelli.

Prove fisiche ancora NV: video/foto ferma durante inferenza, gesto dopo quiete senza riaperture YuNet/SFace, secondo avvio senza I/O sequenziale dei modelli; modello rimosso che resta assente e misura a temperatura fresca. Nessun dispositivo usato.


### Controlli negativi del giro 1

HR-G2-ripresa (1m34s, FAIL atteso) ha completato i controlli interrotti dal riavvio esterno:
- CR-1: QuietWork e GalleryGraph esattamente da df23eae2; la prova su attachSync fallisce dopo 850 ms di quiete (yielding false).
- CR-2: ripristinato il precedente algoritmo dell'osservatore mantenendo la nuova firma per poter eseguire i test. Due avvii emettono ROLE_CHANGED invece di RULES; la condizione modello pronto/rimosso non impedisce due richieste (attese zero).
- CR-4: AiModule esattamente da df23eae2; il motore finto della misura riceve YieldToApp, atteso null.
- CR-5: disabilitato soltanto il riuso della nota, quindi ogni refresh richiama la verifica completa come prima. Il secondo oggetto/processo conta 2 hash invece di 1.

Sono prove differenziali mirate, non una ricompilazione integrale del vecchio commit con tutte le nuove API. Tutte le modifiche temporanee sono state ripristinate prima di HR-G3. XML in docs/qa/hr/giro1/G2.

HR-G1: OK in 16m37s; core-models 304, core-rules 401, app 15 test (4 classi); zero errori o saltati. Il filtro della nuova prova misura era stato aggiunto al file del lanciatore dopo che il processo lo aveva caricato: non e contato in G1, eseguito in G2-ripresa e incluso in G3.
HR-G2 originale: interrotto dal riavvio esterno verso le 16:08, senza esito finale; al rientro rilasciato il posto HR-G2. La ripresa e stata esplicitamente autorizzata dall'orchestratore; nessun retry di test implicito.
Ogni batch usa un posto, --no-daemon --no-watch-fs --max-workers=2 --no-parallel, offline e compilatore in-process. Heap del progetto 4 GiB + 4 GiB, eccezione dichiarata. Righe di esito inviate al Coordinatore subito dopo il rilascio; processi chiusi solo tramite discendenza del lanciatore o percorso mcg-k5, mai per nome.


HR-G3: **OK in 1m48s** sul codice corretto, con cache di compilazione riusata e test rieseguiti. core-models: 35 test, 0 falliti; core-rules: 1 test, 0 falliti; core-ai: 32 test, 0 falliti; app: 20 test, 0 falliti. Zero errori o saltati. XML e conteggi in docs/qa/hr/giro1/G3; i conteggi sono corse distinte, non si sommano fra G1 e G3. La prova misura passa nel cablaggio di AiModule, con motore finto che controlla l'assenza di YieldToApp. I test esistenti dei volti verificano priorita ridotta, numero thread e riaperture soltanto quando cambia yielding; l'attach di produzione mantiene yielding true prima/dopo busy, anche dopo 850 ms di quiete.

### Prove fisiche residue del giro 1 (NV, non eseguite)

1. Avviare indicizzazione e riprodurre un video, poi restare su una foto senza toccare per almeno 10 s. Atteso: thread del fondo a priorita bassa e motori volti con thread ridotti per tutto il primo piano. Difetto: ritorno a priorita piena o aumento thread dopo 800 ms.
2. Alternare gesto, inerzia e quiete durante i volti. Atteso: cambi del ritmo ma nessuna chiusura/apertura YuNet o SFace causata dal gesto; a riposo lotti pieni. Difetto: ricaricamento dei motori a ogni gesto.
3. Due avvii con modelli pronti, poi Rimuovi e nuovo avvio: nessuna richiesta extra e modello ancora assente. Modello mancante non rimosso: RULES rispetta caldo/carica/fascia/rete; vero off/on: ROLE_CHANGED e rete non a consumo.
4. Misura a telefono fresco: priorita normale senza alternanze indotte dai tocchi; telefono caldo: non parte. Secondo avvio con file invariati: nessuna lettura SHA sequenziale da refresh; file modificato: verifica completa. Ogni apertura per il motore resta verificata, anche con nota presente.

Scelte: nota di verifica locale persistente solo per refresh, invalidazione con stat completo (ctime/mtime inclusi i nanosecondi); nessuna modifica alle soglie, uso dello stesso valutatore delle regole. Ritmo, attesa iniziale e timer della prima consegna invariati.


## Giro di correzione 2

Dettaglio aggiornato: [hr-primo-avvio-giro2.md](hr-primo-avvio-giro2.md). Unione fast-forward di integra/beta-grafica a 3f686ef9 senza conflitti.
CR-7, CS-9, CS-13 e CR-KDOC corretti nelle prove previste. BUILD-INC corretto in parte: layout ora incluso negli input di compilazione e accettazione main/API senza rerun verde, ma errore storico Unresolved reference non riprodotto e causa specifica non dimostrata.

Aggiornamento inventario: la prima richiesta del modello conserva un segno locale, scritto dopo l'accodamento accettato da WorkManager; richieste automatiche KEEP, esplicite REPLACE. Il confronto puo cedere tra due media anche durante una fetta lunga, conservando le firme e il secondo da riprendere. La pagina ricontrolla la disponibilita del probe ogni 2 s solo mentre attende caldo o liberazione; misura sempre fuori da YieldToApp. Screenshot nuovi caldo/occupato in HR, nessuna misura fisica o dispositivo usato. I dettagli del giro 1 sulla prima emissione RULES sono superati dal segno di onboarding: prima richiesta accelerata una volta, poi regole CR-2.
