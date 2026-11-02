# Verifica funzionale: Fase 10, widget, tablet, accessibilita' e rilascio (commit `fc94710e`, 02/11/2026)

**Stato (02/11).** Alla stesura le 13 voci sono nuove: nessuna e' corretta nel codice. Ne risultano 0 che bloccano, 6 sbagliate e 7 minori; 8 sono confermate nel codice e 5 plausibili (serve il telefono o un test, descritto nella voce). **Il cuore regge**: con il manifest assente o `Normal` i cancelli nuovi non cambiano nessun flusso, e con `WRITES`/`ALL`/versione minima fermano le scritture dove devono e solo quelle. I difetti stanno ai margini del cuore: i **testi** che raccontano un blocco del manifest come se lo avesse messo l'utente (CA-1), gli **ingressi** (provider dei documenti, selettore, ricezione, rilascio) che con `Stopped` restano aperti (CA-2), il **widget** nel suo ciclo di vita (WG-1: dopo l'azzeramento il lavoro periodico non riparte; WG-2: la frequenza scelta si perde; WG-3 e WG-4: il tocco). I flussi da provare sul telefono (collegati a F160-F189) sono nel §5.

**La domanda.** Ogni funzione della Fase 10 promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release? Questa non e' una revisione di sicurezza: quella sta in `docs/review/10-fase10.md` (altro revisore, in parallelo) e qui non e' duplicata.

**Come.** Ho letto il codice committato (worktree su `main` `fc94710e`), senza eseguire niente: niente Gradle, telefono, emulatori, `adb`, NAS o rete. Per ogni flusso ho seguito la catena: schermata o tasto → ViewModel o controllore → porta → oggetto legato in `:app` (Hilt, `ReleaseGraph`, `WidgetGraph`, `IntegrationsGraph`, `work/`, `src/debug` e `src/release`) → codice che tocca database, file, NAS o rete → risultato che torna alla schermata. In piu' ho confrontato i fake dei test con le implementazioni vere (§3). Nessun sotto-agente: ho letto tutto io, in due passate: (a) il cuore sotto i cancelli nuovi, il rilascio, l'azzeramento e la Diagnostica; (b) finestre, pannelli, trascinamento, widget, accessibilita' e strumenti. Le voci piu' pesanti (CA-1, CA-2, WG-1, WG-3) le ho ricontrollate nel codice una seconda volta, compreso l'elenco di chi chiama `WidgetWork.schedule` e `reschedulePeriodic` e il costruttore di `AppEntryGate`.

**Il modello dei difetti** e' quello di `docs/review/funzionale-9.md`:
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo o con la chiusura della pagina;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object;
- un lavoro che nessuno programma, o che finisce a vuoto;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, `src/debug` e `src/release`, le recinzioni D12, D30, D34, F4-D31) che rompe una funzione;
- **un cancello nuovo che blocca un flusso del cuore quando non dovrebbe**.

**Perimetro.** La Fase 10 cosi' come P13 l'ha integrata in `:app` (sezione "Fase 10 P13: integrazione" di `docs/decisioni.md`). Le quattro recinzioni (F4-D31, F5-D12, F7-D30, F8-D34) sono tutte accese in `FeatureFlags` e in `tools/release/recinzioni.json`: sulle share vere oggi non si scrive niente a prescindere dal manifest, quindi i cancelli nuovi mordono davvero solo sulla famiglia di prova (e dopo il passo unico di P10, sulla famiglia vera).

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravita':**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `feature-widget/...` per `feature-widget/src/main/kotlin/dev/pampa/mycloudgallery/feature/widget/...`; `feature-settings/...` per `feature-settings/src/main/kotlin/dev/pampa/mycloudgallery/feature/settings/...`; le stringhe sono `<modulo>/src/main/res/values/<file>`. I numeri di riga sono quelli di `fc94710e`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Cancelli del manifest e cuore (CA) | 0 | 2 | 2 | 4 | 3 | 1 |
| Radice e rilascio visto dall'utente (RL) | 0 | 0 | 2 | 2 | 2 | 0 |
| Widget (WG) | 0 | 3 | 2 | 5 | 3 | 2 |
| Finestre e pannelli (FI) | 0 | 0 | 1 | 1 | 0 | 1 |
| Accessibilita' (AC) | 0 | 1 | 0 | 1 | 0 | 1 |
| **Totale** | **0** | **6** | **7** | **13** | **8** | **5** |

**Esito per flusso:**

| Flusso | Esito | Voci |
|---|---|---|
| Backup e file in arrivo con manifest assente o `Normal` | Funziona | |
| Sincronizzazione e pubblicazione dell'indice, prenotazioni, compattazione (manifest `Normal`, `WRITES`) | Funziona | |
| Album e album condivisi sotto i cancelli | Funziona (i testi: CA-1) | CA-1 |
| Volti e dati personali sotto i cancelli (la lettura continua, la pubblicazione si ferma) | Funziona (i testi: CA-1) | CA-1 |
| Modelli: scaffale, scaricamento, indirizzi aggiuntivi del manifest | Funziona | |
| Operazioni sui file del NAS (sposta, cestino, cartelle) | Funziona (i testi: CA-1) | CA-1 |
| Cosa dice l'app di un blocco `WRITES`/versione minima (backup, album, cestino, Diagnostica) | Sbagliata | CA-1 |
| Provider dei documenti, selettore, ricezione e rilascio con `ALL` | Sbagliata | CA-2 |
| "Questa versione e' stata fermata" con Diagnostica ed esportazione | Funziona | CA-4 (minore) |
| Avviso "Aggiorna" e "Ho capito" | Funziona | RL-1 (minore) |
| Avvio istantaneo (il manifest non si aspetta) e stato "chiuso finche' non e' letto" | Funziona | CA-3 (minore) |
| Lettura del manifest nel giro dell'indice, ogni 24 ore, solo dal NAS | Funziona | |
| Azzeramento (widget per primi, finestre, registro, manifest) | Sbagliata | WG-1 |
| "Novita' di questa versione", versione e changelog, `release/versions.txt` letto da Gradle | Sbagliata | RL-2 |
| "Apri in una nuova finestra", due finestre sugli stessi dati, un solo foglio "Condividi" | Funziona (plausibile) | |
| Rotazione e ridimensionamento senza perdite | Funziona (plausibile) | |
| Impostazioni a due pannelli, ogni pagina raggiungibile | Funziona (plausibile) | FI-1 (minore) |
| Libreria e viewer affiancati; mappa con il pannello | Funziona (plausibile) | |
| Trascinamento in uscita (Libreria, album, ricerca, ricordi, mappa, viewer) | Funziona | |
| Trascinamento in arrivo (Libreria, album, Cartelle NAS) e foglio della ricezione | Funziona | CA-2 (con `ALL`) |
| Widget: aggiunta, tre misure, contenuto (ricordo o foto), titolo | Funziona | WG-5 (plausibile, minore) |
| Widget: Impostazioni → Widget (contenuto e frequenza) | Sbagliata | WG-2 |
| Widget: ridisegno ai Nascosti (anche dal giro dei dati personali) e al blocco dell'app | Funziona | |
| Widget: tocco sulla foto, sul ricordo, "Questa foto non c'e' piu'" | Sbagliata | WG-3, WG-4 |
| Widget: lavoro periodico (3/6/24 ore, mai 23-7, risparmio energetico) | Sbagliata | WG-1, WG-5 |
| Descrizioni delle celle e azioni personalizzate (TalkBack) | Funziona | |
| Contrasto alto e meno trasparenza | Sbagliata (plausibile) | AC-1 |
| Testo grande, movimento ridotto | Funziona (plausibile) | |
| "Simula il passo" (solo debug) | Funziona | |
| Diagnostica: codici nuovi nel catalogo e nell'esportazione | Funziona | |
| Debug e release: R8 dei moduli nuovi, recinzioni accese | Funziona | decisione sulle chiavi (§7) |

Funziona 23, Sbagliata 8, Blocca 0 (su 31 flussi; cinque dei "Funziona" sono "plausibili", cioe' scritti correttamente ma da vedere sul telefono).

**Il quadro.**
- **Il cuore regge.** Con i cancelli nuovi:
  - con `Normal` il percorso e' identico a prima: `NasInputs.releaseWritesStopped` e' `{ false }` di serie, `ReleaseStageRules` e `ReleaseModelNetworkGate` sono "solo restrittivi" (`release/ReleaseGates.kt:19`), la sessione riceve l'account vero (`graph/AppGraph.kt:285`);
  - con `WRITES` o versione minima restano aperte le letture: la sincronizzazione dei dati personali legge e applica anche a scritture ferme (`core-personal/.../sync/NasPersonalSync.kt:138`, solo la pubblicazione aspetta, `:217-232`), le prenotazioni dell'indice passano a "senza prenotazione" e il lavoro locale continua (`core-index/.../work/LeasedWorkUnits.kt:79,117`), il backup si ferma al passo dopo con il suo stato `WRITES_STOPPED` e riprende dal diario;
  - con `ALL` non si apre nessuna sessione (`graph/AppGraph.kt:285`), nessun lavoro a tappe parte (`:470`), nessuno scaricamento di modelli (`ai/AiWiring.kt:218`), e la radice mostra solo la pagina fermata con la Diagnostica (che si esporta con il selettore di sistema, non con un effetto della navigazione principale: funziona anche li').
- **L'avvio non aspetta il manifest.** `ReleaseGraph.load` parte su `graph.scope` appena il grafo nasce (`di/GraphModule.kt:132`) e la prima schermata non lo aspetta; lo stato "non letto" vale "chiuso" per i cancelli (`release/ReleaseGraph.kt:68-71`) e "aperto" per la UI.
- **I difetti stanno in quattro posti:**
  - **le parole** (CA-1): un blocco del manifest si presenta con undici testi scritti per l'emergenza "Ferma le scritture" ("Hai fermato le scritture", "Riprendo da sola", "Riprova piu' tardi"): chi ha una versione bloccata non puo' riattivare niente e niente riparte da solo;
  - **gli ingressi** (CA-2): `AppEntryGate` non conosce il manifest; con `ALL` il provider dei documenti, il selettore, la ricezione e il rilascio restano raggiungibili da altre app;
  - **il widget** (WG-1, WG-2, WG-3, WG-4): l'azzeramento cancella il lavoro periodico e nessuno lo rimette; la frequenza scelta si perde; il tocco con un viewer aperto non cambia foto e, a freddo, puo' dire "Questa foto non c'e' piu'" a una foto che c'e';
  - **la trasparenza** (AC-1): "meno trasparenza" si legge da quattro chiavi di sistema indovinate, nessuna documentata.
- **La release** non ha differenze di legami: `ReleaseWiring`, `Phase10Module` e `WidgetWiring` sono gli stessi; `DeveloperTools` di `src/release` ha la stessa forma di quello di debug (strumenti vuoti); le classi di "Simula il passo" in `src/main` sono nell'elenco dei resti che `tools/check.ps1` cerca nel dex (riga 856). Resta aperta, per scelta dichiarata, `ReleaseKeys.PROVISIONAL = true`: finche' K1 e K2 non sono vere nessun manifest vero e' accettato e `release.ps1` rifiuta di pubblicare (passo 0).

**Le cose piu' gravi:**
- **Una versione "fermata" lascia aperti gli ingressi (CA-2).** `AppEntryGate.decide` guarda solo famiglia e blocco app (`integrations/IntegrationsAdapters.kt:126-133`) e `IntegrationsGraph` lo costruisce senza il manifest (`integrations/IntegrationsGraph.kt:146`): provider dei documenti, selettore, ricezione e `IncomingDrops` (`:333-341`) continuano a servire foto a chi le chiede, anche per un blocco `SECURITY`.
- **Dopo l'azzeramento il widget non si aggiorna piu' da solo (WG-1).** `WidgetGraph.stop` cancella il lavoro periodico (`widget/WidgetGraph.kt:240`) e nessuno lo riprogramma: solo il ricevitore lo fa, alla nascita del primo widget (`feature-widget/.../glance/GalleryWidgetReceiver.kt:38-40,70-72`) e i widget della Home restano.
- **Chi ha una versione in sola lettura legge "Hai fermato le scritture" (CA-1)** e non ha niente da riattivare: il blocco passa solo con un aggiornamento.

---

## 2. I difetti

### 2.1 Cancelli del manifest e cuore (CA)

#### CA-1 – Un blocco del manifest si racconta con le parole dell'emergenza "Ferma le scritture"
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** l'utente ha una versione sotto la minima o dentro un blocco `WRITES` (F177) e apre la pagina Backup, un album, il cestino del NAS, "Sposta"; oppure sceglie "Disattiva e cancella" in Persone.
- **Percorso:**
  - il manifest entra nei cancelli come l'emergenza: `WriteBlock.EMERGENCY_STOP` (`app/nas/NasGraph.kt:362`), `GateStatus.WRITES_STOPPED` (`:414`, `app/nas/NasOrganizer.kt:904`), `BackupGateState.WRITES_STOPPED` (`app/backup/BackupGraph.kt:182`, `app/backup/FiveBAdapters.kt:389`, `app/backup/AppBackupGate.kt:61`). E' la scelta 1 di P13: "ogni schermata che sa dire 'scritture ferme' lo dice gia'";
  - ogni schermata lo dice con parole scritte per l'utente che ha premuto l'interruttore:
    - Backup: "Hai fermato le scritture sul NAS: il backup riprende quando le riattivi" (`feature-backup/.../strings_backup_problems.xml:29`);
    - piede della Libreria: "Apri la Diagnostica" (`feature-library/.../strings_backup_footer.xml:60-61`);
    - album: "Riprendo da sola" e "Riprovo da sola appena si puo'" (`feature-albums/.../strings_albums_page.xml:145,206`);
    - cestino e doppioni: "Riprova piu' tardi" (`feature-library/.../strings_trash_nas.xml:67`, `strings_utility_duplicates.xml:140`);
    - operazioni sui file e viewer: "interruttore di emergenza in Diagnostica", "Riprova piu' tardi" (`feature-nas/.../strings_nas_operations.xml:109`, `strings_nas_common.xml:83,98`, `feature-viewer/.../strings_nas_actions.xml:59`);
    - cancellazione dei volti: "riparte quando le riaccendi" (`feature-people/.../strings_faces_erase.xml:61`);
    - Diagnostica: "Hai fermato le scritture sul NAS da questa Diagnostica" (`feature-diagnostics/.../strings_backup.xml:31`);
  - in Diagnostica la voce `NAS_WRITES_STOPPED` c'e' solo con l'emergenza dell'utente (`app/nas/NasLibraryDiagnostics.kt:60-66`) e il tasto "Ferma le scritture" si offre ancora (`:62`) senza poter toccare il manifest; la causa vera sta in un'altra voce (`RELEASE_BLOCKED`, `app/release/ReleaseDiagnostics.kt:70-77`).
- **Effetto:** l'utente cerca un interruttore che non ha messo, e "riprende da sola" o "riprova piu' tardi" non diventano mai veri: i blocchi non scadono (`core-release/.../state/ReleaseRules.kt`, E-REL4) e lo stato si toglie solo con un manifest piu' nuovo o aggiornando l'app (L566). L'avviso "Aggiorna" lo dice una volta in alto e si chiude con "Ho capito".
- **Correzione proposta:** dare alle schermate la causa senza un enum nuovo nei core: un `LocalWritesHeldByRelease` nel design system (letto da `ReleaseGraph.status`) e, per ognuna delle stringhe sopra, una variante "Questa versione dell'app e' in sola lettura: aggiornala dal Pampa Store"; in Diagnostica `NAS_WRITES_STOPPED` con la guida "versione in sola lettura" e senza il tasto. Se non si vuole toccare undici schermate, almeno un testo neutro ("Le scritture sul NAS sono ferme. Se non le hai fermate tu, aggiorna l'app") nelle stesse stringhe. Prova: una per porta con `ReleaseStatus.ReadOnly`, che il testo scelto non contenga "riprend" ne' "riprova".

#### CA-2 – Con `ALL` provider dei documenti, selettore, ricezione e rilascio restano aperti; con `ReadOnly` la ricezione mette in coda per sempre
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** con la versione fermata (`SECURITY` o `DATA_RISK`) un'altra app apre il selettore o "Archivio" di MyCloud Gallery, usa "Condividi con MyCloud Gallery" o trascina un file sull'app (F169, F177).
- **Percorso:**
  - `IntegrationPorts.install(entryGate, documents)`, `PickerPorts`, `ReceivePorts` e `IncomingDrops(context, { entryGate.decide() }, ...)` (`app/integrations/IntegrationsGraph.kt:332-341`) passano da un cancello solo;
  - `AppEntryGate.decide` guarda famiglia, caricamento e blocco app (`app/integrations/IntegrationsAdapters.kt:126-133`); il manifest non c'e' nel costruttore (`app/integrations/IntegrationsGraph.kt:146`) e in `integrations/` non c'e' nessun riferimento a `release`. `AppEntryGateTest.kt:29` costruisce il cancello senza e non ha un caso `Stopped`;
  - E-REL6 e la tabella di §2.8 dicono "solo la pagina... nessun lavoro, nessun traffico" e, per `ALL`, "cosa che non puo' fare: rivelare qualcosa": il provider continua a dare foto (telefono, e NAS in cache) a chi le chiede;
  - con `ReadOnly`: il foglio della ricezione offre "Salva sul NAS" e `AppIncomingQueue.blockOf` lascia mettere in coda con `WRITES_STOPPED` (`app/backup/IncomingQueue.kt:135`); il file entra in coda e non parte mai (una copia in piu' nello spazio dell'app), e "Ricevuti in coda" lo chiama "congelato" (`app/backup/FiveBReceived.kt:74,92`, stesso difetto di CA-1).
- **Correzione proposta:** `AppEntryGate` riceve `blockedByRelease: () -> Boolean` (`release.stopped`) e risponde `NOT_READY` (o un valore nuovo "versione ferma"); `decisions()` combina anche `release.status`, cosi' le radici del provider si notificano al cambio. Con `ReadOnly`, `blockOf` risponde con un motivo dedicato e il foglio non offre "Salva sul NAS" (o lo offre con "resta in coda finche' non aggiorni"). Prova: `AppEntryGateTest` con `Stopped`; `ReleaseGatesAppTest` con provider e ricezione.

#### CA-3 – La finestra "stato non letto" decide male per chi parte per primo, e nessun test la copre
- **Gravita':** Minore. **Stato:** plausibile (serve un test con `ReleaseGraph` non ancora caricato).
- **Percorso:**
  - `writesStopped` e `stopped` valgono `true` finche' `load()` non ha finito (`app/release/ReleaseGraph.kt:68-71`); `load` parte su `graph.scope` dopo la costruzione del grafo (`app/di/GraphModule.kt:132`); `awaitLoaded` (`:88`) non ha chiamanti fuori dai test;
  - chi decide nei primi millisecondi (la prima tappa di un lavoro nato con il processo) riceve `Stop(ActivityDisabled)` da `ReleaseStageRules` (`app/release/ReleaseGates.kt:19`), che `TrustSyncRun` tratta da "niente da fare" (`app/work/TrustSyncRun.kt:126`): il giro del giorno si perde. Nella pratica la finestra e' quasi sempre coperta da cio' che viene prima (l'attesa della rete di `SettledStageRules`, `awaitBackupReady`), per questo "plausibile";
  - il widget usa un'altra condizione: `release.status.value is Stopped` (`app/di/Phase10Module.kt:52`), cioe' nella stessa finestra non e' fermo, mentre gli altri cancelli si';
  - i test non la vedono: `ReleaseGatesAppTest.kt:100` chiama `release.load()` prima di tutto e `NasInputs.releaseWritesStopped` vale `false` di serie.
- **Correzione proposta:** `ReleaseStageRules.decide` e `ReleaseModelNetworkGate.verdict` aspettano `release.awaitLoaded()` (tetto 2 s, poi "chiuso") prima di rispondere; il widget usa `release.stopped`. Prova: un `StagedRun` con `ReleaseGraph` non ancora caricato che dopo diventa `Normal` parte.

#### CA-4 – `ALL` ferma la sessione e i lavori a tappe, non i lavori locali
- **Gravita':** Minore. **Stato:** confermato (serve una decisione).
- **Percorso:** `Stopped` agisce sulla sessione (`app/graph/AppGraph.kt:285`), su `stageRules()` (`:470`, usato da `AiWork`, `FacesWork`, `NasWork`, `BackupStages`) e sui modelli (`app/ai/AiWiring.kt:218`). Non sul lavoro `memories-notify` (`app/work/MemoriesWork.kt`: nessun riferimento al manifest) ne' sui lavori della galleria "fuori dal motore delle regole" (`PhoneSyncWorker`, `LibraryMaintenanceWorker` in `app/work/GalleryWork.kt`). Con `ALL` la notifica "Un anno fa" arriva ogni giorno e il tocco apre solo la pagina fermata.
- **Decisione:** E-REL6 dice "nessun lavoro". O si dichiara che i lavori **locali** continuano (non toccano il NAS) e si corregge la frase, o si fermano. Propongo di fermarli (lettura letterale: con `ALL` la Libreria non si vede, quindi la scansione non serve, e la notifica porta a una pagina "fermata"): `ReleaseGraph.stopped` come primo controllo di `MemoryNotifyWorker` e dei due lavori della galleria, con `Result.success()`.

### 2.2 Radice e rilascio visto dall'utente (RL)

#### RL-1 – La radice si ricostruisce quando lo stato cambia (all'avvio e a meta' sessione)
- **Gravita':** Minore. **Stato:** confermato (per come Compose tratta due chiamate in rami diversi; l'effetto si vede con F177).
- **Percorso:**
  - `ReleaseRoot` chiama `content()` in rami diversi di un `when`: `Normal -> content()`, `UpdateBanner -> Box { content(); banner }` (`app/release/ReleaseRootUi.kt:78-96`). Al cambio di ramo il contenuto si butta e si ricrea;
  - il contenuto e' tutta la navigazione: `AppNavHost` fa `key(destination) { rememberNavController(); GalleryRoot { ... } }` (`app/navigation/AppNavHost.kt:158-163`), quindi anche il viewer (lo stato e' di `GalleryRoot`) e la pila;
  - lo stato parte `Normal` (`core-release/.../state/DefaultReleaseState.kt`, `mutableStatus`) e `AppRoot` lo legge subito (`app/AppRoot.kt:91-94`): con uno stato salvato diverso, a freddo, il primo disegno compone la navigazione e pochi millisecondi dopo la rifa (o, con `Stopped`, la butta): per chi e' in sola lettura l'avvio costa il doppio, e per un attimo c'e' una Libreria in composizione sotto una versione fermata, contro la promessa "nessuna Libreria in composizione, nessuna foto in memoria" (`ReleaseRootUi.kt:73-76`);
  - a meta' sessione, l'arrivo di un blocco `WRITES` (una lettura ogni 24 ore, nel giro dell'indice) riporta alla Libreria e chiude il viewer.
- **Correzione proposta:** struttura stabile, la stessa chiamata per `Normal` e `UpdateBanner` (`Box { content(); if (banner) Banner }`) e `Stopped` come unico ramo diverso; e `ReleaseRootViewModel.status` che non cambia il contenuto per un valore "non ancora letto" (per `Stopped` il fail-closed dei cancelli basta per i millisecondi). Prova: un test Compose che cambia `ReleaseUi` e verifica che un `rememberSaveable` dentro `content` resti.

#### RL-2 – Il testo delle novita' promette "nessun luogo" sul widget, il widget li mostra
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `CHANGELOG.md:32` e la copia che l'app mostra in "Novita' di questa versione" (`feature-settings/src/main/res/raw/changelog.md:32`) dicono "il widget non mostra nomi di persone ne' luoghi". Il titolo del ricordo sul widget compone il primo luogo ("Un anno fa · Oslo") o l'etichetta del viaggio ("Oslo, marzo 2025"): `feature-widget/.../frame/WidgetTexts.kt:30-52`, con `places.name(id)` a `:41`. D4 ammette il luogo e vieta solo le persone.
- **Correzione proposta:** la frase vera in entrambe le copie (un test le tiene uguali): "Il widget non mostra mai nomi di persone; un ricordo puo' dire dove e' stato." Oppure togliere il luogo dal titolo (decisione, §7).

### 2.3 Widget (WG)

#### WG-1 – Dopo l'azzeramento il lavoro periodico non riparte; dopo un cambio di account il widget resta fermo per il processo
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** "Configura di nuovo" o azzeramento con un widget sulla Home (F164); oppure fine di un onboarding fatto con un altro account o NAS.
- **Percorso:**
  - l'azzeramento chiama `widgetStopper.stop()` per primo (`app/graph/LocalData.kt:119`) e di nuovo per i soli dati di un account (`:169`, `eraseAccountData`);
  - `WidgetGraph.stop` fa `WidgetWork.cancel` e `stopped.set(true)` (`app/widget/WidgetGraph.kt:234-243`): per il resto del processo il fornitore risponde null (`awaitServices`, `:113`) e niente si ridisegna;
  - `AppPreviousAccountData.erase` (`app/graph/LocalData.kt:337-345`) riavvia il processo solo se la galleria girava, e il suo KDoc dice che "di norma non c'e' niente da fermare": dopo un onboarding con un altro account o NAS il widget resta "Configura MyCloud Gallery" e non si ridisegna piu' finche' il processo non muore;
  - il lavoro `widget-refresh` lo programma solo il ricevitore, in `onEnabled` (primo widget) e `onUpdate` (`feature-widget/.../glance/GalleryWidgetReceiver.kt:38-50,70-72`); `reschedulePeriodic` ha un solo chiamante, il cambio della frequenza (`app/widget/WidgetWiring.kt:94`), e `publishAtStart` fa solo un giro (`:111-113`). Dopo un azzeramento i widget della Home ci sono ancora (nessun nuovo `onEnabled`): il lavoro resta cancellato finche' il launcher non richiama `onUpdate` (riavvio del telefono o del launcher, aggiornamento dell'app). Il commento di `GlanceWidgetRefresher.reschedulePeriodic` ("e quando nasce il primo widget", `:122-125`) descrive un legame che non c'e'.
- **Correzione proposta:**
  - `stop()` distingue l'azzeramento completo (permanente: il processo si riavvia) dai soli dati di un account (si ferma, e poi `resume()` a lavoro finito);
  - `WidgetWiring.publishAtStart` programma il lavoro con `KEEP` se ci sono widget (con la frequenza letta dalle preferenze, vedi WG-2).
  - Prova: con `WorkManagerTestInitHelper`, azzeramento → avvio del processo dopo → il lavoro c'e' con i widget; `eraseAccountData(false)` e poi un giro del widget riesce.

#### WG-2 – La frequenza scelta (6 o 24 ore) si perde togliendo e rimettendo il widget
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `onEnabled` e `onUpdate` programmano sempre `WidgetPeriod.DEFAULT` con `KEEP` (`feature-widget/.../glance/GalleryWidgetReceiver.kt:70-72`); la scelta arriva da `settings().period.drop(1)` (`app/widget/WidgetWiring.kt:94`), cioe' solo a un cambio. Si toglie l'ultimo widget (`onDisabled` cancella il lavoro, `:62-65`) e se ne rimette uno: il lavoro gira ogni 3 ore con la pagina che dice 24. Lo stesso dopo WG-1.
- **Correzione proposta:** ricordare la frequenza fuori dalle porte (un numero in un file del widget, o letta da `SettingsWidgetPrefs`) e usarla nel ricevitore; oppure, dopo il primo disegno, `WidgetWiring` ripianifica con `UPDATE` se la frequenza scelta differisce da quella di serie. Prova: scelta 24 ore, ultimo widget tolto e rimesso, il lavoro ha l'intervallo di 24 ore.

#### WG-3 – Il tocco sul widget con un viewer gia' aperto non cambia foto
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** l'utente guarda una foto nel viewer, va alla Home e tocca una foto del widget (F163).
- **Percorso:** `WidgetTapEffect` risolve `WidgetTarget.Viewer` e fa `nav.popBackStack<MainRoute>` e `openViewer(request)` (`app/navigation/AppWidgetNavigation.kt:69-77`) **senza** chiudere il viewer; `ViewerHostState.open` con una richiesta gia' aperta esce subito (`app/main/GalleryRoot.kt:112-118`: solo affiancato e `fromLibrary` fa un salto, e il tocco del widget non e' dalla Libreria). I rami `MemoryPage` e `Library` il viewer lo chiudono (`:79`, `:91`): solo questo ramo no. L'app torna alla foto di prima.
- **Correzione proposta:** `viewer?.close()` prima di `openViewer` nel ramo `Viewer`. Prova: test Compose con un viewer aperto e un tocco sul widget.

#### WG-4 – Il tocco a freddo puo' dire "Questa foto non c'e' piu'" a una foto che c'e'
- **Gravita':** Sbagliata. **Stato:** plausibile (corsa fra l'istantanea e il primo disegno; F163 con processo morto).
- **Flusso:** l'app non gira, l'utente tocca una foto del widget (il caso normale); l'app parte e, dopo lo sblocco, serve il tocco.
- **Percorso:** `WidgetTap.resolve` legge la visibilita' dal catalogo (Room, `core-library/.../visibility/CatalogVisibility.kt:78`) e risponde `Viewer`; poi `WidgetHostViewModel.viewerOf` risponde null se l'istantanea della timeline non e' ancora arrivata (`app/navigation/AppWidgetNavigation.kt:48`) o non contiene il media (`:50`), e il ramo `else` apre la Libreria con il messaggio "Questa foto non c'e' piu'" (`:75`, E-W8). Il messaggio e' per la foto sparita o nascosta, non per quella che non e' ancora in istantanea.
- **Correzione proposta:** `viewerOf` aspetta l'istantanea (`filterNotNull().first()`, tetto 2 s) e distingue "non e' piu' li'" da "non e' ancora pronta" (in quel caso, Libreria senza messaggio). Prova: istantanea nulla che arriva dopo 200 ms.

#### WG-5 – Il lavoro periodico in un processo nato per lui puo' non avere le porte in 1,5 s
- **Gravita':** Minore. **Stato:** plausibile (si vede con F162 e con `lateRuns7d` in Diagnostica).
- **Percorso:** il lavoro aspetta le porte al piu' `COLD_START_BUDGET_MS` = 1,5 s (`app/widget/WidgetGraph.kt:112-131`: `snapshot.filterNotNull().first()` dentro il tempo) e, se non ci sono, finisce "riuscito" senza ridisegnare (`feature-widget/.../work/WidgetWork.kt:77-86,109-126`). La notifica dei ricordi ha avuto bisogno di 20 secondi e del montaggio senza il primo disegno (NT-1, `app/work/MemoriesWork.kt`). L'istantanea parte con la famiglia pronta, ma su un archivio grande 1,5 s dalla nascita del processo possono non bastare: il widget si aggiornerebbe solo all'apertura dell'app (`appBackgrounded`).
- **Correzione proposta (se F162 la conferma):** per il lavoro periodico (che non ha fretta) un budget a freddo di 8-10 s e, come per le notifiche, un `awaitLive` della Libreria. Il ricevitore e il ridisegno per un nascosto restano a 1,5 s.

### 2.4 Finestre e pannelli (FI)

#### FI-1 – "Indietro" dalla prima pagina del pannello delle Impostazioni
- **Gravita':** Minore. **Stato:** plausibile (serve un test Compose; nessuna prova copre l'"Indietro" nel pannello).
- **Percorso:** `SettingsPaneHost` passa `back = { if (!paneNav.popBackStack()) close() }` (`feature-settings/.../SettingsPanes.kt:111`) a pagine che nel pannello sono la **prima e unica** destinazione. Il codice conta sul fatto che `popBackStack()` risponda falso quando non c'e' altro; ma toglie comunque la destinazione (il pannello resta vuoto) e, se risponde vero, `close()` non parte e la pagina resta "scelta" (`code` non cambia, quindi riscegliere la stessa non la rimette). `SettingsPanesTest.kt` prova la scelta, la soglia e le rotte, non l'"Indietro".
- **Correzione proposta:** `if (paneNav.previousBackStackEntry == null) close() else paneNav.popBackStack()`: l'ultima non si toglie mai. Prova: Compose con `TestNavHostController`, "Indietro" dalla prima pagina e da una seconda.

### 2.5 Accessibilita' (AC)

#### AC-1 – "Meno trasparenza" si legge da quattro chiavi di sistema indovinate
- **Gravita':** Sbagliata. **Stato:** plausibile (serve il telefono, F183).
- **Percorso:** `GalleryAccessibilityReader.REDUCE_TRANSPARENCY_KEYS` (`core-designsystem/.../a11y/GalleryAccessibility.kt:66-71`) sono quattro nomi "candidati" (`accessibility_reduce_transparency`, `reduce_transparency`, ...), nessuno documentato da Android, cercati in `Secure`, `Global` e `System`; il commento stesso dice che "la chiave di One UI va confermata sul telefono". Se nessuna combacia, "Riduci trasparenza e sfocatura" di One UI non cambia niente e il vetro resta trasparente: la superficie su cui l'utente ha gia' segnalato di leggere male. Il contrasto alto ha strade documentate (`UiModeManager.getContrast` da Android 14, `high_text_contrast_enabled`). P8 lo ha rinviato a P14 (F183).
- **Correzione proposta:** confermare la chiave sull'S25 (`adb shell settings list secure|global|system` con l'opzione accesa e spenta) e fissarla; in ogni caso, per non dipendere da una chiave del produttore, un interruttore dell'app "Superfici piene" (fase grafica) che l'utente trova in Impostazioni.

---

## 3. I fake e le prove contro il vero

Le prove di P13 sono verdi (19.488 prove, 0 fallite) e quelle sui cancelli mordono (mutazioni fatte a mano da P13). Qui sono le differenze fra cio' che le prove usano e cio' che l'app ha davvero; quattro nascondono voci di questo documento.

| Prova o fake | Il vero | Differenza | Voce |
|---|---|---|---|
| `NasInputs.releaseWritesStopped` di serie `{ false }` (`app/nas/NasInputs.kt:63`); `ReleaseGatesAppTest` chiama `release.load()` prima di tutto (`:100`) | `ReleaseGraph.writesStopped = !isLoaded \|\| ...` (`ReleaseGraph.kt:68`) | la finestra "stato non letto" non e' mai provata | CA-3 |
| `AppEntryGateTest` (`app/src/test/.../integrations/AppEntryGateTest.kt:29`): cancello con quattro ingressi, nessuno e' il manifest | `IntegrationsGraph.kt:146`: stesso costruttore | il test e' uguale al vero; e' il vero a non conoscere il manifest, e nessuna prova dice "con `Stopped` il provider tace" | CA-2 |
| Nessuna prova su `WidgetHostViewModel.viewerOf`, `WidgetTapEffect`, `WidgetWiring.publishAtStart`, `reschedulePeriodic` (cercati in `app/src/test` e `androidTest`) | sono il percorso del tocco e del lavoro periodico | il tocco con un viewer aperto, a freddo con l'istantanea nulla, e il lavoro dopo l'azzeramento non hanno una prova | WG-1, WG-3, WG-4 |
| `Phase10ResetAppTest` mette `windowsCloser` come funzione del test | `WindowLauncher.closeOtherWindows` con `ActivityManager.appTasks` (Robolectric non le ha, dichiarato da P13) | la chiusura vera delle finestre e il suo ordine rispetto al resto non si provano | F167 |
| `SettingsPanesTest` prova scelta, soglia e rotte (`ogniPaginaDelPannelloHaLaSuaRotta`) con `back = {}` | `SettingsPaneHost` con `back = { if (!popBackStack()) close() }` | l'"Indietro" del pannello non e' provato | FI-1 |
| `InMemoryReleaseState` e `DefaultReleaseState` | la suite `ReleaseStateContract` li fa coincidere su storie casuali | nessuna differenza trovata | |

---

## 4. Cosa regge, ripercorso nel codice

- **I cancelli.** `ReleaseStatus` entra in tre punti e basta: `NasGraph.writeGate` (`:362`: indice, prenotazioni, album, volti, dati personali, scaffale dei modelli), `opsGate` (`:414`: sposta, rinomina, cestino, cartelle) e `BackupGateInputs.writesStopped` (`BackupGraph.kt:182`: backup e file in arrivo). Nell'ordine di ogni cancello le ragioni "dure" vengono prima (D12/R2 `TEST_ONLY` in `AppBackupGate.kt:59-60`, D31 `LOCAL_ONLY` in `IndexWriteRules.blockFor`, D30/D34 `TEST_SHARES_ONLY`): sulle share vere di oggi il motivo che l'utente vede resta quello giusto ("in prova"), e CA-1 compare solo dove il manifest e' l'unico motivo.
- **Le letture non si fermano.** La sincronizzazione dei dati personali legge e applica con la scrittura bloccata (`NasPersonalSync.kt:138`); le verifiche del backup in sola lettura non guardano l'emergenza (KDoc di `AppBackupGate`); le prenotazioni del lavoro dell'indice passano a "senza prenotazione" e il lavoro locale va avanti (`LeasedWorkUnits.kt:79,117`); la cancellazione dei volti locale va avanti e solo quella sul NAS aspetta (`FacesPorts.kt:78-80`).
- **I modelli.** Lo stesso `CatalogExtraModelSources` va a `LocalModels` e a `ModelFetchers.create` (`ai/AiWiring.kt:199-219`); gli indirizzi vengono solo da un manifest valido e non scaduto, per lo stesso id e la stessa impronta (`ReleaseRules.extraUrls`); lo scaricamento con `ALL` e' `Refused(OFFLINE)` (`ReleaseModelNetworkGate`, `release/ReleaseGates.kt`).
- **Il manifest.** Lettura dentro `NasStages.sync` a giro finito (`work/NasWork.kt:208-212`), al piu' una volta ogni 24 ore **con una risposta** (`ReleaseGraph.kt:101-123`: `Unavailable` non segna l'ora, `Absent` e `TooLarge` si'), con la sessione del giro, 64 KiB; il percorso e' `.mycloudgallery/release/manifest.mcgr` nell'area comune (`ManifestLimits.NAS_PATH`, `docs/formato-nas.md` §15). Lo stato e' un file atomico con CRC (`FileReleaseStateStore`); un file rovinato vale "niente salvato" (E-REL1, L561); una `versionCode` diversa ricalcola dall'ultimo manifest valido (`DefaultReleaseState.load`).
- **L'azzeramento.** Ordine: widget, finestre, onboarding, account, casa, VPN, battito, dati della galleria, modelli, manifest (`graph/LocalData.kt:116-143`); `release.clear()` piu' la cartella `release/`; `widget/` fra i `PHASE10_FILES` (`:306`). Lo stato del manifest lo toglie solo l'azzeramento completo.
- **La Diagnostica.** `ReleaseDiagnostics` (`RELEASE_STATE`, `RELEASE_MANIFEST_REJECTED`, `RELEASE_BLOCKED`) porta solo numeri, flag e codici; la pagina fermata apre un `diagnosticsGraph` senza azioni dell'app (`appActions` vuoto: nessun tasto che non puo' girare) e l'esportazione usa il selettore di sistema.
- **Le finestre.** Ammissione e tetto di due in `WindowRegistry.admit` (la terza e' rifiutata alla nascita, qualunque intent la porti), `onNewIntent` porta davanti la principale quando l'ingresso e' una richiesta del processo (`MainActivity.kt:143-154`), "Condividi" e "Modifica con" partono una sola volta (`ShareHostEffect.kt`, `FiveBHostEffect.kt`: la richiesta presa con `compareAndSet`, `AppShares.kt:87-88`).
- **I pannelli.** `GalleryPanesLayout` tiene sempre gli stessi due `Box` e cambia solo i modificatori (nessuna ricreazione cambiando pannelli); `MainScaffold` chiude il viewer affiancato al cambio di scheda e all'uscita (`LaunchedEffect(selectedTab)`, `DisposableEffect(viewer)`); un tocco sulla griglia a lato cambia foto con `jump`, senza riaprire.
- **Il trascinamento.** `gridDragSubject` decide al tocco (selezione o cella, mai dai Nascosti: senza `dragOrigin` nessuna sorgente); `gridGestures` lascia la pressione lunga al trascinamento solo dove la sorgente la vuole (`ownsLongPress`); `IncomingDrops` ignora un trascinamento nostro, rispetta il blocco dell'app e non parte mai con un caricamento (apre il foglio); `DropHandoff` e' fresco e una volta sola (10 s, orologio monotono). `MediaDragPorts` e `MediaDropPorts` si legano in `IntegrationsGraph.install()`, chiamato senza condizioni da `Application.onCreate` (`MyCloudGalleryApp.kt:172`).
- **Il widget.** `WidgetEngine`: ogni giro e' in fila, la causa nuova sposta `generation` e il giro in corso non consegna, svuotamento prima del registro, registro prima del disegno, fallire chiusi a ogni errore (`affectedBy`: tutti i widget); `WidgetTap.resolve` legge dal catalogo con `visibleOrThrow` e `hiddenAmongOrThrow` (un guasto = la Libreria); `ShelfWidgetMemories` rispetta D4 (pubblicato oggi, mai `PERSON`, mai un titolo con una persona, mai una copertina con una persona a cui si e' detto "Mostra meno" o "Mai"); `SnapshotWidgetLock` vale "acceso" finche' le impostazioni non si leggono; `LocalWidgetThumbs` decodifica con `ThumbContext.Ahead` (mai l'originale dal NAS). Il ridisegno ai Nascosti arriva da tre strade (il "Nascondi" di qui, l'istantanea nuova dopo 1 s di calma, il giro dei dati personali: `WidgetWiring.followHidden`).
- **L'accessibilita'.** Le azioni delle celle si costruiscono solo quando il lettore le chiede; "Elimina" non elimina (seleziona e apre lo stesso foglio di conferma); "Condividi" e "Preferito" fanno quello che fa la barra; `SelectionAnnouncer` dice la selezione una volta; i nomi delle persone nella descrizione solo con Persone attiva e mai per un media escluso da `VisibleMedia` o `HiddenMask` (`describe/DescribeWiring.kt`); il descrittore dimentica al blocco e in secondo piano (`MyCloudGalleryApp.installPhase10`).
- **Debug e release.** `DeveloperTools` di `src/release` ha la stessa forma di quello di `src/debug` (tutto vuoto; `releaseTrust()` solo le due chiavi vere, `releaseChannel` mai `DEBUG`); le classi di "Simula il passo" stanno in `src/main` ma nessuno le legge in release e `tools/check.ps1:856` le cerca nel dex; `consumer-rules.pro` di `feature-widget` tiene i pacchetti di produzione con `-keep,allowshrinking` (niente `testing/`); le quattro recinzioni sono accese in `FeatureFlags` e in `recinzioni.json`.

---

## 5. Flussi da provare sul telefono a mano

Le prove F160-F189 sono dell'orchestratore (autonome dove non c'e' l'utente). Qui le righe che il codice non puo' chiudere, collegate alle voci. I tre momenti sono quelli del §5.5 di `docs/fase10-progetto.md` (M1 S25, M2 tablet, M3 TalkBack).

| Prova | Cosa fare | Cosa deve succedere | Voce |
|---|---|---|---|
| F160 | S25: widget 2x2, 4x2, 4x4 con "Ricordo di oggi"; poi "Solo foto"; tema scuro | titolo con anni e luogo ("Un anno fa · Oslo"), mai una persona; con il blocco app solo foto del telefono | RL-2 (il testo di Novita' dice il contrario) |
| F161 | S25: nascondere una foto che il widget mostra; poi nascondere dall'emulatore una foto mostrata dall'S25 e fare un giro dei dati personali | svuotamento entro 1 s; poi la foto non c'e' piu' | |
| F162 | S25, 24 ore: frequenza 6 ore, ultimo widget tolto e rimesso; giri in Diagnostica (`lateRuns7d`) | il lavoro riprende a 6 ore; nessun giro 23-7; nessun giro in ritardo con il telefono fermo la notte | WG-2, WG-5 |
| F163 | (1) viewer aperto su una foto, Home, tocco su una foto del widget; (2) app uccisa (`am force-stop`), tocco su una foto del widget; (3) `am start` con numeri inventati; (4) blocco app acceso | (1) si apre la foto toccata, non quella di prima; (2) si apre la foto toccata, mai "Questa foto non c'e' piu'"; (3) con numeri ben formati ma inventati si apre la Libreria senza messaggio, con un URI malformato l'app si apre come dall'icona; (4) il tocco aspetta lo sblocco | WG-3, WG-4 |
| F164 | emulatore: "Configura di nuovo" e azzeramento con un widget sulla Home; poi configurare di nuovo con lo stesso NAS e con un altro | prima della cancellazione il widget dice "Configura MyCloud Gallery"; dopo la nuova configurazione il widget torna a ridisegnarsi **e** `dumpsys jobscheduler` mostra di nuovo `widget-refresh`; con un altro account il widget non resta fermo | WG-1 |
| F165 | tablet: Impostazioni a due pannelli in verticale e in orizzontale; "Indietro" dalla prima pagina scelta e da una seconda; Libreria e viewer affiancati; mappa e griglia | pannelli alle soglie, nessuna perdita ruotando; "Indietro" torna a "Scegli una voce" e la stessa voce si puo' riscegliere | FI-1 |
| F166 | tablet in DeX: ridimensionamenti, mouse e tastiera (Canc apre il foglio di conferma, Invio apre, Ctrl+A) | nessun crollo di MapLibre o del video; nessuna eliminazione diretta | non ripercorso a fondo qui (`gridKeyboard`) |
| F167 | tablet: "Apri in una nuova finestra"; azzeramento lanciato **dalla finestra secondaria**; "Condividi" con due finestre | un solo foglio; l'azzeramento arriva in fondo anche se la finestra che lo lancia viene chiusa (e' ripreso all'avvio: `resumeIfInterrupted`) | |
| F168 | tablet con l'app di prova: pressione lunga su una cella gia' selezionata; mouse trascinando; da Nascosti | trascina la selezione; mai dai Nascosti; una pressione lunga che non parte non lascia la cella "morta" (il rilascio non e' un tocco: lo si deve vedere) | |
| F169 | tablet: rilascio da Archivio sulla Libreria, su un album, su una cartella di Cartelle NAS; **con un manifest `WRITES`** | il foglio con il conteggio; con `WRITES` il foglio non deve offrire una coda che non parte mai | CA-2 |
| F177 | emulatore e S25 (debug, KT): manifest con `WRITES`, poi `ALL`, poi un `seq` piu' alto che li toglie; sulla release un manifest con KT ignorato | `WRITES`: nessuna scrittura SMB in cattura, avviso "Aggiorna", **testi** di Backup, album, cestino, Diagnostica; `ALL`: nessuna sessione, solo la pagina fermata, **provider, selettore, ricezione e rilascio che non rispondono**, nessuna notifica dei ricordi; il manifest dopo toglie il blocco (con `ALL` solo se l'app lo rilegge: L566) | CA-1, CA-2, CA-4, RL-1 |
| F177b | S25 con uno stato `WRITES` salvato: avvio a freddo; a meta' sessione un `seq` nuovo con un blocco | l'avvio non ricompone due volte; il viewer e la pagina aperta non si perdono all'arrivo del blocco | RL-1, CA-3 |
| F182 | S25 e tablet, TalkBack: ogni schermata; Libreria con 500 celle; "Elimina" da azione della cella | ogni elemento attivo con nome; i nomi delle persone solo con Persone attiva; "Elimina" apre il foglio e non elimina | |
| F183 | S25 e tablet: testo 2,0x, contrasto alto, **"Riduci trasparenza e sfocatura" di One UI**, con `adb shell settings list secure\|global\|system` prima e dopo | nessun testo tagliato; con meno trasparenza le superfici sono piene; scrivere nel resoconto **quale chiave cambia** | AC-1 |
| F184 | tablet in DeX: movimento ridotto, tastiera, Switch Access | nessun movimento non essenziale; ogni azione raggiungibile | |
| F185 | emulatore Android 16: servizio di accessibilita' non "strumento" (solo debug) | nessun testo ne' descrizione letti | |
| F186 | S25: release candidata, aggiornamento da una beta con la stessa chiave, APK con un'altra chiave | aggiornamento con i dati; l'altro APK rifiutato | |
| F187 | S25 con la release e R8: mappa (DR-1 di Fase 9), ricordi, **widget (ricevitore, lavoro, anteprima del selettore)**, ricerca, viewer, condivisione | nessun crollo; stessi esiti della debug | |
| F188 | S25 e Tab S9: avvio a freddo con stato `Normal` e con `ReadOnly` salvato | l'avvio con `ReadOnly` non costa piu' del `Normal` | RL-1 |

---

## 6. Note

**Scartati dopo il controllo nel codice:**
- **"`ownsLongPress` senza una porta installata"** (la pressione lunga su una cella selezionata sarebbe "morta" se `MediaDragPorts.installed` fosse null): `IntegrationsGraph.install()` lega le due porte senza condizioni a ogni avvio del processo; il caso e' teorico.
- **Il canale `beta` della riga di `release/versions.txt`** contro `releaseChannel(versionName)` (`-beta` nel nome): il canale serve solo a togliere KT fuori dalla debug (`ReleaseTrust.forChannel`); `beta` e `stable` si comportano allo stesso modo.
- **La finestra che si chiude con il suo azzeramento**: `windowsCloser` tiene la principale, non la finestra in uso (D42); l'azzeramento e' ripreso all'avvio se interrotto (`DeviceSelfWipe.resumeIfInterrupted`): da vedere in F167, non una voce.
- **Un tocco sul widget rimasto in sospeso** durante l'onboarding o l'attesa (`OpenWidgetRequests.pending`) che si serve molto dopo: serve solo se la schermata principale compare (`AppNavHost.kt:220`), e il registro del widget e' controllato comunque al servizio.
- **Il salto asincrono di `CountingWidgetRefresher.counted`** prima di `inner.hiddenChanged`: il conto legge il registro e il motore ha il suo ordine (`generation`): non cambia cio' che si vede.
- **E-REL13** (una conferma distruttiva aperta decade quando lo stato cambia): non fatto come tale, dichiarato da P13 ("l'operazione confermata passa dal cancello all'esecuzione e si ferma"); con CA-1 sistemato il messaggio che l'utente legge dopo e' giusto.
- **`ReleaseKeys.PROVISIONAL = true`**: dichiarato (L560); `release.ps1` passo 0 lo rifiuta (`tools/release/release-lib-check.ps1`). E' un'azione dell'utente (§7), non un difetto.

**Non guardato qui:** la sicurezza (altro revisore); `tools/release/*.ps1`, `tools/security/*` e `check.ps1` oltre alle righe citate; le prestazioni (P9, F188); la qualita' dei testi e della grafica sul telefono; la mappa a due pannelli (`MapPanesLayout`) e le righe di `gridKeyboard` (Canc, Invio, Ctrl+A), che ho letto solo nei commit e che restano a F165-F166; i fogli di conferma nel dettaglio.

---

## 7. Stato di ogni voce e proposta di correzione (02/11)

Nessuna voce e' corretta. Il pacchetto e' la proposta; ogni correzione porta un test rosso senza la modifica.

| Voce | Gravita' | Stato | Pacchetto |
|---|---|---|---|
| CA-1 Blocco del manifest detto con le parole dell'emergenza | Sbagliata | confermato; **Corretta (A, 02/11)** | A |
| CA-2 Ingressi aperti con `ALL`; coda che non parte con `ReadOnly` | Sbagliata | confermato; **Corretta (A, 02/11)** | A |
| CA-3 Finestra "stato non letto" | Minore | plausibile; **Corretta (A, 02/11)** (il widget: B) | A |
| CA-4 `ALL` e i lavori locali | Minore | confermato; **Corretta (A, 02/11)** | A (decisione 3) |
| RL-1 Radice ricostruita al cambio di stato | Minore | confermato; **Corretta (A, 02/11)** | C |
| RL-2 "Nessun luogo" sul widget | Minore | confermato | B (decisione 4) |
| WG-1 Lavoro periodico dopo l'azzeramento; widget fermo dopo un cambio di account | Sbagliata | confermato | B |
| WG-2 Frequenza scelta persa | Minore | confermato | B |
| WG-3 Tocco con un viewer aperto | Sbagliata | confermato | B |
| WG-4 Tocco a freddo: "non c'e' piu'" | Sbagliata | plausibile | B |
| WG-5 Lavoro periodico a freddo: 1,5 s | Minore | plausibile | B (solo dopo F162) |
| FI-1 "Indietro" del pannello | Minore | plausibile | C |
| AC-1 "Meno trasparenza" da chiavi indovinate | Sbagliata | plausibile | D (solo dopo F183) |

**Pacchetti:**
- **A, "il blocco del manifest nel cuore":** CA-1 (causa leggibile e testi), CA-2 (`AppEntryGate` con il manifest, motivo dedicato per la coda), CA-3 (`awaitLoaded` nelle regole e nel cancello dei modelli, il widget con `release.stopped`), CA-4 (dopo la decisione). Per primo: e' il cuore, e CA-2 e' l'unica voce che apre una porta a un'altra app. Sonnet (lavoro ordinario di legami e stringhe); i test sono quelli di §3.
- **B, "widget: ciclo di vita e tocco":** WG-1 (`stop()` con `resume`, `publishAtStart` che programma il lavoro), WG-2 (frequenza ricordata), WG-3 (`viewer.close()`), WG-4 (`viewerOf` che aspetta), RL-2 (testo), WG-5 solo se F162 la conferma. Tutte con test su WorkManager e Compose.
- **C, "radice e pannelli":** RL-1 (struttura stabile di `ReleaseRoot`), FI-1 (`previousBackStackEntry`).
- **D, "trasparenza":** AC-1: prima F183 sull'S25 per la chiave, poi la correzione e, nella fase grafica, l'interruttore "Superfici piene".

**Decisioni per l'utente:**
1. **Le chiavi K1 e K2 (azione tua).** Finche' `ReleaseKeys.PROVISIONAL` e' vero nessun manifest vero e' accettato e `release.ps1` si rifiuta di pubblicare: va fatto `manifest-tool.ps1 keys`, incollate le due righe e aggiornate le impronte in `docs/sicurezza.md` §5.6. Senza, la versione 1.0.0 non ha il blocco a distanza.
2. **CA-1: come dire "questa versione e' in sola lettura".** (a) una causa leggibile dalle schermate (variante dedicata nelle 11 stringhe e in Diagnostica) o (b) un testo neutro nelle stesse stringhe. Propongo (a) per Backup, album, piede della Libreria e Diagnostica, (b) per le altre.
3. **CA-2 e CA-4: cosa fa una versione fermata fuori dalla sua finestra.** Propongo di chiudere provider, selettore, ricezione e rilascio con `ALL`, di non offrire "Salva sul NAS" con `ReadOnly`, e di fermare la notifica dei ricordi e la scansione del telefono con `ALL` (lettura letterale di E-REL6).
4. **RL-2: testo o widget.** Propongo di correggere la frase in `CHANGELOG.md` (il luogo nel titolo del ricordo e' una scelta di D4); la strada opposta e' togliere il luogo dal titolo.
5. **AC-1.** Ok a confermare la chiave One UI con F183, e a un interruttore "Superfici piene" dell'app nella fase grafica (non dipende dal produttore).
