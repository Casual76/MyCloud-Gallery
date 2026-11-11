# HR: ultimo giro di correzione (11 novembre 2026)

Worktree `mcg-k5`, ramo `fix/hr-primo-avvio`. Unione iniziale di `integra/beta-grafica`: fast-forward a `ad4310a5`, senza conflitti. Nessun dispositivo, adb, sotto-agente o push; `engine/` non modificato, `local.properties` non letto. Il test NAS affidato all'orchestratore resta intatto.

## Esiti delle tre voci

1. **Fatto: attesa della misura legata alla pagina STARTED.** La rotta usa `repeatOnLifecycle(STARTED)` sul proprietario della voce di navigazione. Il ciclo da 2 s esiste soltanto dentro quel blocco e soltanto finche' HOT o BUSY richiedono un controllo. Fuori pagina non parte una misura nemmeno quando arriva il modello pronto. Il controllo usa l'attivita' del Job, quindi vede la cancellazione anche prima del relativo `finally`; una seconda verifica precede la chiamata al probe sul dispatcher di calcolo. Un tentativo nativo gia' iniziato quando la pagina era visibile puo' completarsi: restano timeout e protezione dalle sovrapposizioni, senza promettere di interrompere un driver nativo.
2. **Fatto: protezione Gradle rimossa.** Eliminato l'intero blocco `mcgKotlinSourceLayout`, inclusi riflessione e cattura di `project`. Nessuna modifica ai source set o a cio' che finisce nella release. La riga in `decisioni.md`, sezione HR dell'ultimo giro, conserva il comando mirato `--rerun` e distingue le sequenze riuscite del giro precedente dalla causa storica non riprodotta. Il batch positivo configura e compila il progetto senza questa protezione; non e' una nuova prova di attribuzione del vecchio guasto incrementale.
3. **Fatto: scadenza locale senza morte dell'osservatore.** Il timeout dell'accodamento viene gestito dentro il singolo evento del ruolo; il segno della prima richiesta rimane assente. Nessun timer di retry: si riprova al prossimo vero passaggio spento/acceso, oppure alla prima lettura del processo successivo. La cancellazione del chiamante continua a propagarsi. Nessun cambiamento a rete, KEEP delle richieste automatiche o rispetto delle rimozioni dell'utente.

## Prove nuove e copertura

- `SearchSettingsLifecycleTest.caldoNascostoNonSiSvegliaNeMisura_eRiprendeSoloStarted`: CREATED senza misura; STARTED caldo; pagina coperta senza controlli o misure per 10 s, anche chiamando `measure` subito dopo la cancellazione; rientro STARTED e raffreddamento con una sola misura; altri 10 s a risultato pronto senza risvegli.
- `SearchSettingsLifecycleTest.modelloProntoInBackgroundAspettaLaPaginaVisible`: modello pronto a pagina CREATED, nessuna misura per 5 s; misura soltanto dopo STARTED.
- `IndexerModelRequestsTest.scadenzaLocaleNonChiudeLOsservatore_eIlCambioRuoloRiprova`: timeout locale dopo 10 s, osservatore ancora attivo, segno assente; nessun tentativo aggiuntivo in 60 s; spento/acceso accoda di nuovo.
- `IndexerModelRequestsTest.cancellareIlChiamanteFermaDavveroLOsservatore`: timeout del chiamante, osservatore cancellato davvero.

La copertura app eseguita comprende anche `AiWorkSchedulingTest` e `AiIndexerPageTest`: richieste automatiche/esplicite, avvio, modello pronto, rimozione e pagina. Le 26 prove precedenti del controller restano verdi. Nessuna modifica grafica, nessun nuovo screenshot necessario.

## Batch e prove differenziali

Tutti i batch: un posto acquisito e atteso nello stesso comando, rilascio in `finally`; `--no-daemon --no-watch-fs --max-workers=2 --no-parallel`, offline e compilatore in-process. Heap del progetto 4 GiB + 4 GiB, eccezione dichiarata. Chiusura dei Java soltanto per albero del lanciatore o percorso `mcg-k5`, mai per nome. Righe di esito inviate al Coordinatore dopo ogni batch. Evidenze locali ignorate da Git in `build/hr-correzioni/R3/`, log in `build/hr-correzioni/R3-*.log`.

- **HR-R3-B1: OK, 505 s** dopo l'attesa del semaforo (acquisizione 19:22:08). 53 test, zero fallimenti/errori/saltati: feature-search 28, app 25. Esecuzione effettiva dei task di test; compilazione anche di app. Posto rilasciato, nessun Java proprio residuo.
- **HR-R3-B2: FAIL atteso dei tre test negativi, 255 s; errore del lanciatore dopo Gradle.** Tolto temporaneamente soltanto il vincolo STARTED, i due test lifecycle trovano una misura nascosta invece di zero. Propagando nuovamente il timeout locale, il test dell'osservatore lo trova inattivo. Tre fallimenti, zero errori, nessun altro test eseguito. `ErrorActionPreference=Stop` con stderr nativo ha troncato il log client e interrotto archivio/corsa positiva; il log del daemon proprio 4840 e gli XML confermano invece l'esecuzione e `BUILD FAILED in 4m 10s`. Riprodotto il problema del lanciatore con un innocuo processo che scrive su stderr, corretto usando Continue e controllando esplicitamente il codice di uscita. Il `finally` ha ripristinato i sorgenti; posto rilasciato e nessun Java proprio residuo. Rettifica inviata al Coordinatore dopo il primo esito, inizialmente basato sul log client incompleto. Nessuna ripetizione della corsa negativa.
- **HR-R3-B3: OK, 51 s** sul codice ripristinato, cinque classi e 53 risultati verdi FROM-CACHE del batch B1 (feature-search 28, app 25), zero fallimenti/errori/saltati. Non e' una seconda esecuzione dei 53 test: B1 e' la prova positiva effettivamente eseguita. Compilazione/configurazione finali riuscite senza protezione riflessiva, posto rilasciato, nessun Java proprio residuo.

Le prove differenziali sono mutazioni mirate dei due comportamenti, non una ricompilazione integrale di un commit vecchio con le API nuove. I conteggi delle corse ripetute non si sommano come test distinti.

## Prove sul telefono ancora non eseguite

1. Aprire Ricerca e modelli con telefono caldo, coprire la pagina con un'altra destinazione e poi mettere l'app in secondo piano. Atteso: nessuna nuova misura o controllo periodico della pagina; al rientro, raffreddato il telefono, una sola misura. Difetto: avvio nascosto o controlli che continuano dopo il completamento.
2. Far diventare pronto il modello mentre la pagina e' coperta: atteso nessun avvio fino al rientro STARTED. Difetto: carico della misura insieme al backup fuori pagina.
3. Con accodamento artificialmente lento oltre 10 s, spegnere e riaccendere il ruolo: atteso nuovo accodamento e nessun ciclo continuo. Dopo riavvio, il segno assente permette ancora la prima richiesta.

Scelte: frequenza di 2 s conservata solo durante l'attesa visibile; nessun retry temporizzato dell'accodamento; nessuna interruzione forzata di una chiamata nativa gia' iniziata. Le prove fisiche e il consumo reale restano NV.
