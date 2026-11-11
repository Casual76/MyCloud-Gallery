# HR - giro di correzione 2 (11 novembre 2026)

Worktree mcg-k5, ramo fix/hr-primo-avvio. Prima delle correzioni: git merge integra/beta-grafica, fast-forward a 3f686ef9, senza conflitti. Nessun push, dispositivo, adb o sotto-agente; engine non modificato e local.properties non letto.

## Esiti

1. **CR-7: corretto nelle prove JVM.** Il profilo conserva models/meta/indexer-role-requested. Il primo valore true senza segno controlla modello Ready/rimozione usando RULES per l'eligibilita, poi accoda ROLE_CHANGED accelerato su rete non a consumo. Il segno viene scritto dopo che WorkManager ha accettato la richiesta; se il modello e gia pronto o rimosso, non si accoda e il segno viene comunque registrato. Avvii successivi: regola CR-2. Le richieste automatiche usano KEEP; soltanto richieste esplicite possono REPLACE. Nessun recupero automatico di un modello rimosso.
2. **CS-9: corretto nelle prove JVM.** CatalogSimilarCopies controlla il callback tra due candidati e tra i gruppi della manutenzione. Se la fetta lunga deve cedere, conserva il secondo corrente: le firme gia salvate si ricaricano, non si decodificano di nuovo. Il grafo non interpreta questa interruzione durante busy come assenza definitiva di progresso. La priorita resta legata al primo piano (CR-1 invariato).
3. **CS-13: corretto nelle prove JVM e screenshot.** Il probe distingue READY, HOT, BUSY. La pagina mostra il motivo del rinvio termico e controlla ogni 2 s la disponibilita mentre aspetta. Appena torna possibile, riparte automaticamente la misura rinviata per caldo. Dopo un timeout, il tasto Riprova resta nascosto fino alla liberazione effettiva del tentativo; non compare neppure se il modello manca. Un errore ordinario mantiene Riprova. Nessuna misura sovrapposta al driver bloccato; nessun cambio di priorita della misura.
4. **CR-KDOC: corretto.** Il commento del ritmo e tornato sopra paceFor; canMeasureDevice conserva soltanto la propria documentazione. Nessuna modifica alle regole.
5. **BUILD-INC: corretto in parte.** Non ho riprodotto l'esatto Unresolved reference del log storico: la base locale compila anche dopo modifiche reali a main. Non dichiaro quindi dimostrata la causa dell'incidente originale. Ho isolato e protetto il cambio di layout invisibile alla chiave cache: main/debug con gli stessi file puo restare UP-TO-DATE. La convenzione registra ora mcgKotlinSourceLayout (percorsi relativi al progetto) per core-designsystem e app. Cambiare radice modifica l'input; modificare il corpo di un file conserva l'incrementale. E una protezione conservativa della ricollocazione, non una prova che l'errore storico fosse causato da essa.

## Build: verifiche e limiti

Nessun kotlin.srcDir aggiunto a mano per debug, nessun doppione di nome fra main e debug nei due moduli. CatalogSection e pubblico; sampleLibraryMenu/sampleViewerMenu sono internal nello stesso package e nella stessa compilazione debug. Non ho cambiato visibilita o source set.

Prima della protezione: catalogo spostato temporaneamente in main e poi riportato in debug, compilazioni FROM-CACHE e UP-TO-DATE. Dopo: input mcgKotlinSourceLayout cambiato riconosciuto nei log. Prova di accettazione: aggiunto temporaneamente un parametro default a GalleryMenuButton in src/main (API chiamata dal catalogo debug), compileDebugKotlin incrementale riuscita senza --rerun in 57 s; ripristino esatto e ulteriore compilazione riuscita. Non sono state usate pulizie o cancellazioni delle cache.

Ulteriore controllo con la protezione rimossa, cache scaldata con un commento unico in CatalogGroup, ricollocazione main/debug e modifica dell'API: anche questo passa (52 s, 19 s, 42 s). Il solo successo dell'accettazione non dimostra pertanto la chiusura del difetto originale. Per attribuirne la causa serve uno stato incrementale che fallisce conservato prima del rerun; il log storico non contiene quell'indice.

App: StepSimulationWiring.kt, StepSimulationSources.kt e debugtools/StepSimulationEntry.kt sono in src/debug, senza omonimi in main. La stessa protezione degli input copre app. Nessun sorgente debug aggiunto a release; nessuna dipendenza o versione cambiata. Estratti dei log: qa/hr/giro2/build-incrementale.txt.

## Prove differenziali

- CS-9: disabilitando il controllo tra media, il nuovo test decodifica tutte le copie invece di fermarsi al primo gesto. Con la correzione riprende lo stesso secondo e forma il gruppo senza rileggere la prima firma.
- CS-13: ripristinando stato generico e assenza di controllo della disponibilita, falliscono il caso caldo/raffreddamento e il timeout con driver occupato. Le modifiche temporanee sono ripristinate prima delle prove positive.
- CR-7: prova con segno su file fra due osservatori/processi e prova sul WorkManager di test vero: la richiesta esplicita deve conservare il proprio ID e restare ENQUEUED dopo la richiesta automatica. Il controllo negativo ripristina RULES iniziale senza segno e REPLACE automatico, mantenendo soltanto le firme necessarie ai test nuovi.

Non si tratta della ricompilazione integrale di un vecchio commit con API che allora non esistevano. Gli XML distinguono controlli negativi e corse positive; i conteggi di corse diverse non si sommano.

## Batch Gradle

Tutti: un posto, --no-daemon --no-watch-fs --max-workers=2 --no-parallel, offline, compilatore in-process, heap progetto 4 GiB + 4 GiB (eccezione dichiarata), chiusura dei soli Java propri per albero/percorso, release in finally. Righe inviate subito al Coordinatore, comprese le precisazioni sugli errori.

| Batch | Esito |
|---|---|
| HR-R2-B1, 21 s | OK, compileDebugKotlin di base FROM-CACHE |
| HR-R2-B2, 39 s | OK, sorgente main modificato senza rerun |
| HR-R2-B3, 41 s | OK, ricollocazione reversibile; cache non distingue la radice |
| HR-R2-B4, 16 s | FAIL di configurazione: classe del plugin non visibile direttamente nello script comune; corretto caricandola dal plugin gia applicato |
| HR-R2-B5, 6m46s | OK: layout distinto, accettazione API main e ripristino; 24 test CatalogSimilarCopies e 20 screenshot |
| HR-R2-B6, 10m14s | 3 fallimenti negativi attesi CS-9/CS-13; app non testata per errore di compilazione sul tipo ListenableFuture di Operation.result.get; corretto con Operation.await |
| HR-R2-B7, 7m24s | OK: 20 screenshot corretti, 26 test Controller, 18 app e 16 probe; zero falliti/errori/saltati |
| HR-R2-B8, 3m30s | Due test nuovi CR-7 falliscono con algoritmo precedente; ripristino e risultati verdi dei 18 test app ripresi FROM-CACHE da B7 |

In B5 l'ispezione visiva ha trovato gli accenti alterati della frase nuova; risorsa e asserzione sono state corrette e le immagini rigenerate in B7. La fixture degli stati di misura ora usa correttamente modello Ready e misura non ancora salvata. I vecchi XML app rimasti da corse precedenti dopo il fallimento di compilazione B6 non sono conteggiati ne conservati come prova B6.

## Screenshot e prove residue sul telefono

Otto PNG nuovi in screenshots/beta-grafica/HR: modelli-{caldo,occupato}-{chiaro,scuro}-{telefono,tablet}.png. Registrazione della schermata reale con stato sintetico, non foto del dispositivo. Le immagini del caldo e della misura occupata non mostrano Riprova. Verificati visivamente caldo chiaro telefono e occupato scuro tablet; accenti del caldo corretti nella seconda ispezione. Le schermate precedenti di misura/errore sono rigenerate con fixture coerente.

Prove fisiche NON eseguite:
1. Onboarding Indicizzatore acceso, preset Notte di giorno, rete non a consumo: un solo download accelerato, poi avvio successivo senza nuova richiesta se pronto. Con rete a consumo e arresto/riavvio, la richiesta ROLE_CHANGED resta in coda con lo stesso ID.
2. Confronto partito a riposo: toccare e scorrere durante una decodifica. Alla fine del media corrente deve cedere; non deve iniziare una lunga sequenza di nuovi originali durante il gesto. Riposo successivo: confronto riprende e finisce senza perdita di firme.
3. Pagina modelli con telefono caldo: motivo esplicito, nessun tasto inutile; lasciarla aperta e raffreddare: misura automatica al ritorno favorevole delle regole. Timeout nativo: attesa esplicita, Riprova soltanto dopo il rilascio.

Scelte: segno locale della richiesta accettata (non del download completato); KEEP automatico; disponibilita ricontrollata ogni 2 s; interruzione fra media, senza tentare di cancellare una decodifica nativa a meta; input layout soltanto nei due moduli interessati da Z1.


B8 completa la prova differenziale CR-7: senza segno il test non trova il file atteso; con REPLACE automatico WorkManager cambia l'ID della richiesta esplicita ancora in coda. Dopo ripristino i risultati dei 18 test delle due classi sono FROM-CACHE; la loro esecuzione positiva effettiva e B7. Il codice restituito e quello positivo, senza mutazioni temporanee o fixup nella storia finale.
