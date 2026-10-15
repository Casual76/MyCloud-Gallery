# La prova delle 500 interruzioni (Fase 4B, P15)

| | |
|---|---|
| **Stato** | 15/10/2026 (P16). Il banco JVM gira a ogni `check.ps1` su **due stack**: le operazioni vere di P12 (`ProductionChaosStackFactory`) e quelle fake di P0-B, entrambe **senza esclusioni** (i difetti D1-D4 del fake li ha corretti P12; il P16 ha tolto le esclusioni, §6): 2.000 giri ciascuno, circa 7.000 interruzioni, zero violazioni, circa 10 secondi per stack. Il runner contro l'area di prova del NAS vero ha l'aggancio a smbj (`SmbChaosBinding`, P12) ma **non e' mai stato lanciato sul NAS vero**: al P16 la suite d'integrazione di `core-nas` si e' fermata al login (`AUTH_FAILED`, un solo tentativo: il file delle credenziali va aggiornato con le password di prova nuove). Lo script del telefono e' controllato a secco, **mai lanciato su un telefono**. |
| **Revisione 4B (15/10)** | I1 chiuso: il banco gira anche sulle **vere `SmbNasFileOps`** e sulla sessione SMB di produzione, sopra il simulatore SMB di core-nas (`SimSmbTarget`, `SmbPrimitivesChaosBenchTest`): **600 giri, 2.172 interruzioni, 0 violazioni**, ~12 s, a ogni `check.ps1` (§2). Gli invarianti valgono con le correzioni della revisione (date del cestino, inventario delle cartelle, tetto per giorno, "Ferma" nel diario). |
| **Dove** | `core-nasops/src/test/kotlin/dev/pampa/mycloudgallery/core/nasops/chaos/` (il banco), `tools/chaos/` (gli script), questo file |
| **Riferimenti** | `docs/fase4-progetto.md` §2.7 (macchine a stati), §4.4 riga P15, §5.4 (area di prova e guardia), §5.5 (le interruzioni) |

## 1. Che cosa promette

Ogni operazione in più passi sul NAS (sposta, rinomina, elimina, ripristina, crea cartella, spostamento tra share, pulizia del cestino) deve sopravvivere a **interruzioni casuali** senza lasciare cose a metà. Un'interruzione è un colpo che cade a un punto preciso di un'operazione:

| Colpo | Come lo vede il codice |
|---|---|
| connessione caduta | la chiamata fallisce con `Timeout` |
| sessione scaduta | `SessionExpired` |
| socket fermo | `Stalled` |
| **morte del processo** | niente prosegue: si buttano tutti gli oggetti e si riparte dal diario locale (come dopo un `am kill`) |

Ogni colpo scatta **prima** che il NAS (o il diario) abbia applicato la chiamata, oppure **dopo** (la modifica c'è, la risposta si perde). Dopo una morte, le chiamate che il vecchio processo ancora facesse (un `finally`, una coroutine rimasta) non toccano più niente.

Gli **invarianti** del §5.5, controllati dopo ogni morte, a fine passo e in una rilettura finale completa:

| # | Invariante | Come si verifica |
|---|---|---|
| 1 | Nessun file a metà visibile | ogni file fuori da `.mycloudgallery/` ha lo SHA-256 di uno degli originali (anche un solo byte diverso lo rompe) |
| 2 | Nessun doppione | ogni originale compare **una volta sola** fra i file visibili. A metà strada due copie sono ammesse solo se un elemento di spostamento tra share è fra "verificato" e "sorgente nel cestino" |
| 3 | Nessuna perdita | ogni originale è visibile **oppure** nel cestino con la voce del diario locale; fanno eccezione solo quelli eliminati per sempre di proposito ("Elimina subito", scadenza dei 30 giorni) |
| 4 | Hash al 100% | a ogni scansione **ogni** file visibile e ogni file nel cestino si rilegge e si confronta; la rilettura finale non usa nessuna cache |
| 5 | Temporanei | a metà: solo quelli propri (`tmp/<io>-…`) e citati da un elemento non finito del diario; a operazioni finite: nessuno |
| 6 | Catalogo coerente | a operazioni finite il NAS ha **esattamente** lo stato atteso: posto e grafia di ogni file, cartelle create o rinominate, contenuto di ogni voce del cestino |
| 7 | Diario coerente | ogni transizione di ogni elemento è ammessa da `OpStateMachine`; i nomi alternativi scritti nel diario sono quelli sul NAS; le voci `TRASHED` del diario locale sono quelle attese (una voce senza file è innocua, §2.7: non è una violazione) |
| 8 | Le operazioni arrivano in fondo | dopo le riprese ogni operazione e ogni elemento sono `DONE` (nessun `FAILED` per un guasto passeggero) |
| 9 | Nessun file dell'app fuori posto | in `.mycloudgallery/` solo temporanei propri, cestino proprio e (se lo stack li scrive) i record firmati |
| 10 | Il cestino ha il suo diario | ogni file nel proprio cestino ha la sua voce `TRASHED` nel diario locale (scritta **prima** della rinomina) e, se lo stack scrive i record firmati, il suo `rec/<id>` |
| 11 | Il NAS si legge | una lettura che non riesce non passa in silenzio: una scansione con problemi non prova niente |

La ripresa a vuoto è parte della prova: dopo un passo, una morte senza colpo seguita da `resumeAll` non deve cambiare né il NAS né il diario.

## 2. I tre banchi

| Dove | Che cosa | Quante | Stato |
|---|---|---|---|
| **JVM, NAS finto** | `ChaosBenchTest`: giri casuali sul NAS finto in memoria, con le operazioni vere di P12 (`ProductionChaosStackFactory`) e con quelle fake di P0-B, senza esclusioni | 2.000 giri per stack, ~7.000 interruzioni ciascuno, ~10 s per stack | **verde** su entrambi |
| **JVM, simulatore SMB** (revisione 4B, I1) | `SmbPrimitivesChaosBenchTest`: gli stessi giri con le operazioni vere di P12 sopra la **sessione SMB di produzione e le vere `SmbNasFileOps`** (handle, `FILE_CREATE`, rinomina senza sostituire, condivisioni, copia lato server a blocchi, codici NTSTATUS), sul simulatore di core-nas (`InMemorySmbBackend`, uno nuovo per giro: `SimSmbTarget`); il tempo passa (30 giorni del cestino) e la copia lato server si puo' rifiutare (ripiego dal telefono); "connessione caduta" chiude davvero le connessioni | 600 giri (`MCG_CHAOS_SMB_ROUNDS`, al massimo 3.000), 2.172 interruzioni, ~12 s | **verde** (0 violazioni) |
| **PC, area di prova del NAS vero** | `ChaosNasIntegrationTest` modo `run`: giri brevi, **un colpo per passo**, file da 1 MB e da `chaos.bigFileMb` MB, dietro la guardia, solo sotto `mcg-it/<giro>/` | ≥ 400 interruzioni (`chaos.rounds`) | scritto e agganciato a smbj (`SmbChaosBinding`); provato sul NAS finto con 20 interruzioni; **non lanciato sul NAS vero** (P16: login di prova fallito, credenziali da aggiornare) |
| **Telefono, area di prova** | `tools/chaos/s25.ps1`: durante una mossa di massa, a tempi casuali `kill -9` del processo o Wi-Fi spento e riacceso; poi il verificatore | ≥ 100 | scritto; **solo a secco** |

## 3. Come si lancia

Tutto dal PC, da `C:\VibeCoded Projects\MyCloud-Gallery-2`. `tools\chaos\area.ps1` imposta le variabili e lancia Gradle; con `-DryRun` stampa e basta.

```powershell
# Il banco JVM (gira già in check.ps1 insieme agli altri test di core-nasops):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode jvm                    # 2.000 giri
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode jvm -Rounds 12000 -Seed 777   # una caccia più lunga

# Rifare UN giro che è fallito (il rapporto dice "giro 163, seme di base 20261015"):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode jvm -Seed 20261015 -FirstRound 163 -Rounds 1

# Il runner contro l'area di prova (serve il file delle credenziali e l'aggancio, §9):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -Interruptions 20    # giro corto di prova (al massimo 20)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run                      # le 400 (o chaos.rounds)

# La prova sul telefono: seminare l'area, avviare la mossa di massa nell'app, interrompere, verificare
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode seed -Files 300 -Manifest build\chaos\manifest.txt
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -DryRun -Interruptions 100 -Seed 42        # il calendario, senza telefono
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Serial <numero> -Interruptions 100 -Manifest build\chaos\manifest.txt -Verify
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode verify -Manifest build\chaos\manifest.txt   # la verifica da sola
```

Gradle non vede le variabili d'ambiente come ingressi dei test: gli script aggiungono `--rerun` (solo al task dei test, non `--rerun-tasks`).

**Variabili** (le imposta `area.ps1`; si possono dare anche a mano):

| Variabile | Vale per | Significato |
|---|---|---|
| `MCG_CHAOS_JVM_ROUNDS`, `MCG_CHAOS_SEED`, `MCG_CHAOS_FIRST_ROUND` | banco JVM | quanti giri (2.000), seme di base (20261015), primo giro |
| `MCG_CHAOS_STACK` | banco JVM | il nome di una classe che implementa `ChaosStackFactory`, per provare uno stack diverso |
| `MCG_NAS_INTEGRATION=true` (o `-DnasIntegration=true`) | NAS vero | senza, nessuna prova tocca il NAS e nessun file di credenziali si legge |
| `MCG_CHAOS_MODE` | NAS vero | `run` (default), `seed`, `verify` |
| `MCG_CHAOS_ROUNDS` | NAS vero, `run` | le interruzioni da far cadere, se `chaos.rounds` non è nel file |
| `MCG_CHAOS_FILES`, `MCG_CHAOS_RUN`, `MCG_CHAOS_MANIFEST`, `MCG_CHAOS_SETTLED=false` | NAS vero | originali da seminare, nome del giro, manifesto, "prova non ancora ferma" |

`-PnasIntegration` di core-nasops non arriva ai test (il suo `build.gradle` non inoltra la proprietà come fa quello di core-nas): per questo c'è la variabile. Chi vuole la stessa forma aggiunge a `core-nasops/build.gradle` `testOptions { unitTests.all { test -> test.systemProperty('nasIntegration', project.hasProperty('nasIntegration') ? 'true' : 'false') } }` (è fuori dal perimetro di P15).

**File delle credenziali** (`%USERPROFILE%\.mycloudgallery\nas-it.properties`, §5.4): il runner legge `user`, `password`, `share`, `share2`, `host`, `guid`, `user2`, `password2`, `privateShare2` e le due chiavi nuove, **facoltative**:

```properties
chaos.rounds=400        # interruzioni del runner (0 = non eseguire). Senza: MCG_CHAOS_ROUNDS, poi 400
chaos.bigFileMb=200     # il file grande. Senza: 200
```

## 4. Come funziona un giro

Un giro (`ChaosRound`) è ripetibile: stesso seme, stessi passi, stessi colpi.

1. **Il mondo**: due share di prova (`test`, `test-comune`) con 6-12 originali di contenuto noto e diverso (quasi sempre piccoli; ogni tanto ~340 KB, che sono più blocchi del ripiego dal telefono; rarissimi i grandi), in cartelle fino a 3 livelli, con **pochi nomi apposta** (le collisioni nascono da sole). Ogni stato iniziale si verifica prima di cominciare.
2. **Da 2 a 6 passi**, scelti guardando lo stato atteso (mai una richiesta che il piano rifiuterebbe): crea cartella, rinomina, rinomina solo maiuscole (E-N7), sposta nella stessa share (file e cartelle), sposta tra share (copia lato server o **ripiego dal telefono**, file e cartelle), elimina (file e cartelle), ripristina (anche con la cartella sparita e il nome occupato, E-D3), elimina subito, **pulizia del cestino scaduto con l'ora finta** (passano 31 giorni), e azioni "del PC" (un file nuovo che crea collisioni). Alcuni passi si possono fermare con "Ferma" dopo una morte.
3. **I colpi** sono sorteggiati **per passo**, da 1 a 6 (25/25/20/15/10/5%):
   - 25% sulle scritture del diario (sempre morte del processo, prima o dopo che il database le abbia scritte);
   - 75% sulle chiamate al NAS, metà puntati a una primitiva che il passo usa di sicuro (così anche `deleteOwnTemp`, `rmdirIfEmpty`, `openTemp` vengono colpite), metà a un punto qualunque; tipo: 40% connessione, 12% sessione, 12% socket fermo, 36% morte; momento: prima o dopo, uno su due (per le letture "dopo" è uguale a "prima": non si spreca un colpo).
4. **L'utente tocca**: piano, conferma, avvio. Se il processo muore, si controlla il mondo **così com'è** (invarianti che valgono sempre), si ricostruiscono gli oggetti sullo stesso diario e si riprende con `resumeAll` finché non c'è più niente di aperto (al massimo 80 riprese). Se la morte è caduta nell'avvio, il banco guarda il diario come lo vedrebbe l'utente: l'operazione è nata? Se no, ripete.
5. **Fine passo**: tutti gli invarianti contro lo stato atteso. Dopo il passo, 20 volte su 100, una **morte senza colpo** con ripresa a vuoto.
6. **Fine giro**: rilettura completa senza cache.

Il **modello** (cosa deve esserci) è indipendente dal codice sotto prova: il banco calcola il posto finale di ogni file dalla richiesta, e controlla anche che il diario dica la stessa cosa del NAS. I nomi alternativi (`foto (2).jpg`) si accettano solo se il posto del piano era davvero occupato.

### Risultato sulle operazioni fake (2.000 giri, seme 20261015)

~8.000 passi, ~7.100 interruzioni, ~6.300 verifiche a metà, ~9.500 a operazioni finite, ~5.600 riavvii, ~200.000 file riletti con SHA-256 (~5 GB). Distribuzione dei colpi **caduti**: morte del processo 57%, connessione caduta 27%, sessione scaduta 8%, socket fermo 8%; prima della chiamata 57%, dopo (risposta persa) 43%. Ogni primitiva di scrittura e ogni scrittura del diario è stata colpita sia prima sia dopo (la tabella di copertura per primitiva è nel rapporto). **Zero violazioni.** Una caccia di 12.000 giri con un altro seme (777): zero violazioni.

## 5. Come si leggono i risultati

`core-nasops/build/chaos/jvm-report-fake.txt` (e `-vere.txt`, `area-report-<giro>.txt`, `verify-<giro>.txt`): in testa il riassunto (giri, interruzioni per tipo e momento, passi per tipo, copertura per primitiva, difetti noti e quante volte sono comparsi); poi, per ogni giro che ha fallito:

```
giro 163, seme 20261077073859: 1 violazioni, 8 interruzioni, 4 morti del processo
  passo 1: sposta: file test-comune:DSC_0100.jpg; ... -> test-comune:Città di Lucca/...
  interruzioni: #11 RENAME morte del processo prima; #14 RENAME connessione caduta prima; ...
  VIOLAZIONE [2. nessun doppione] sposta tra share: 2 copie visibili di uno stesso originale: ...
  cronaca: ... (colpi previsti, morti, "l'utente tocca Ferma", ...)
```

- Il numero fra parentesi è l'invariante (§1). Il prefisso dice **quando** è stato visto: `dopo la morte #N` (a metà), `a meta' (riprese N)`, il nome del passo (a operazioni finite), `rilettura finale`.
- `interruzioni` elenca i colpi **scattati** (numero del punto, primitiva, tipo, prima/dopo); `colpi previsti` nella cronaca sono anche quelli che non sono scattati.
- `(difetto del banco)` vuol dire che è il banco a non avere il modello giusto, non il codice sotto prova: è un errore da sistemare qui.
- Per rifare il giro: il seme di base e il numero del giro (§3).
- Una violazione con `eccezione nel codice sotto prova` è un'eccezione uscita da `plan`/`start`/`resumeAll`: il codice non deve mai lanciare per un guasto del NAS.

**Il banco ha i denti**: `ChaosMutationTest` rompe di proposito lo stack (il cestino che perde il file, che mente, un'eliminazione del temporaneo che mente, la voce del diario che non si scrive, la ripresa che non riprende) e vuole che il banco diventi rosso con l'invariante giusto; `ChaosVerifierTest` fa lo stesso invariante per invariante con mondi fatti a mano (anche un solo byte diverso).

## 6. Difetti e osservazioni trovati

Il banco ha trovato **quattro difetti nello stack fake di P0-B** (il fake e' un riferimento, non il prodotto). Per non nascondere il resto li aveva dichiarati ed evitati (non colpiva o non toccava la cosa che li scatenava), elencandoli sempre nel rapporto. **P12 li ha corretti in `InMemoryNasOperations`** e **il P16 ha tolto le esclusioni** da `FakeChaosStackFactory` (`knownDefects`, `unfaultable`, `cancelIsSafe`, `knownDefectHit`): il banco ora colpisce anche le letture a flusso e tocca "Ferma" in ogni passo con entrambi gli stack. `ChaosKnownDefectsTest` e' diventato una regressione: riproduce ciascuno scenario e fallisce se un difetto torna. La tabella resta come storia.

| Id | Difetto | Dove | Cosa faceva il banco (fino al P16) |
|---|---|---|---|
| D1 | `fallbackCopy` non cattura `NasIoException`: la connessione che cade a metà di una lettura a flusso (ripiego, E-D12) esce da `start`/`resumeAll` come eccezione invece di mettere l'operazione in pausa | `InMemoryNasOperations` | non colpisce `NasRandomFile.read` |
| D2 | `cancel` ignora l'esito di `deleteOwnTemp`: se la cancellazione fallisce, l'elemento risulta ANNULLATO e il temporaneo resta orfano (invariante 5) | `InMemoryNasOperations` | non tocca "Ferma" con spostamenti tra share in `COPYING`/`COPIED`/`VERIFIED` |
| D3 | `OpStateMachine.cancellable` ammette `VERIFIED`, ma `PUBLISHED` si scrive **dopo** `publishTemp`: con la risposta persa, "Ferma" annulla un elemento la cui copia è già visibile e la sorgente non è mai andata nel cestino → **due copie visibili** (invariante 2) | contratto (`contract/Diary.kt`) e fake | come D2 |
| D4 | `publishedByThisOp` tratta un errore di lettura passeggero come "non pubblicato": dopo la risposta persa di `publishTemp`, un guasto mentre si controlla la destinazione fa **FALLIRE** l'elemento con la copia già pubblicata → due copie visibili, operazione fallita | `InMemoryNasOperations` | riconosce il segno (elemento `FAILED unexpected` dopo la verifica), ferma il giro e lo conta (8 giri su 2.000) |

Osservazioni (non difetti del fake, ma del contratto o di scelte da fare in P12):

- **O1.** `start` è idempotente sull'impronta del piano **per sempre**, anche a operazione finita. Rinomina a→b, poi b→a, poi di nuovo a→b: il terzo piano è identico al primo (una rinomina non cambia dimensione, data né `fileId`) e `start` risponde `AlreadyStarted` senza fare niente. Lo stesso per "crea cartella X" due volte. Il banco evita di generare piani già avviati.
- **O2.** Dopo una ripresa il nome alternativo può partire dal nome già scelto (`foto (2) (2).jpg`) invece che da quello del piano; con nomi che hanno già un contatore (`Cena (1).jpg`) lo stack vero sceglie `Cena (2).jpg`. Il banco accetta entrambe le forme e conta le doppie.
- **O3.** Un'operazione annullata con "Ferma" mentre il processo muore a metà dell'annullamento non conserva l'intenzione: alla ripresa i suoi elementi non ancora annullati proseguono. Non perde niente né duplica, ma l'utente aveva detto "ferma".

**Sonda sulle operazioni vere di P12.** Su una copia del suo lavoro in corso (non sul repository: il banco non è ancora agganciato, §9) con `ProductionChaosStackFactory` come in §9 (`NasOpsEngine`, `NasTrashService`, diario in memoria): il banco compila e gira senza modifiche (~15 s per 2.000 giri). Due fotografie dello stesso giorno:

- **mattina**: 2.000 giri 9 rossi, 12.000 giri (seme 777) 24 rossi; **tutti con "Ferma"** (in tutto 27 doppioni, invariante 2; 5 temporanei orfani, invariante 5; 1 operazione non arrivata in fondo, invariante 8): i casi D3 e D2 anche nell'esecutore vero;
- **dopo le correzioni di P12**: 3.000 giri (seme 20261015) 2 rossi, **entrambi "Ferma" + morte del processo durante l'annullamento di uno spostamento tra share**: l'elemento esce `FAILED unexpected` invece di `CANCELLED` (giri 1943 e 2299); 12.000 giri (seme 777), ~41.600 interruzioni: **zero violazioni**.

Senza "Ferma" l'esecutore vero non ha mai violato un invariante (~100.000 interruzioni in totale). È una fotografia di un lavoro in corso, non un giudizio su P12.

## 7. L'area di prova sul NAS vero e la guardia (§5.4)

- **Quando gira**: solo con `MCG_NAS_INTEGRATION=true` **e** il file delle credenziali; altrimenti i test risultano *saltati* con il motivo, **senza aprire un socket**.
- **La guardia** (`ChaosAreaGuard`, stesse regole di `TestAreaGuard` di core-nas, riscritte perché quella sta nei test di core-nas): prima di qualunque connessione l'account (anche `user2`) non è `admin`, `alessio`, `pampa`, `root`, `administrator` e deve essere `test` o `test2`; le share ammesse sono l'**intersezione** fra quelle del file e l'elenco **scritto nel codice** (`TestShares`: `test`, `test2`, `test-comune`); `alessio`, `pampa`, `NAS`, `Public` sono rifiutate in ogni forma (NFKC, maiuscole). Un errore nel file **fa fallire** la prova (non la salta); non allarga il recinto.
- **Ogni chiamata** passa dai decoratori della guardia (`GuardedReader`, `GuardedFileOps`) che controllano la share **prima** del vero. I percorsi sono `SharePath` (niente `..`, niente assoluti) e le primitive vere girano **radicate** in `mcg-it/<giro>/r<N>/` (anche `.mycloudgallery/`: temporanei e cestino finiscono lì, non alla radice della share).
- **L'autotest** (`ChaosAreaSelfTest`) gira prima del primo giro: account vietati, share vietate in ogni forma (lettura, elenco, scrittura, copia), percorsi che escono, e **zero chiamate al vero** per ogni rifiuto (contatore).
- **Pulizia**: il banco non può eliminare media (le primitive non lo permettono, per costruzione). La cartella `mcg-it/<giro>/` la toglie `NasIntegrationSuiteTest` di core-nas al giro dopo (`gradlew :core-nas:testDebugUnitTest -PnasIntegration`): la suite pulisce tutti i giri precedenti sotto `mcg-it/` e vuole `mcg-it` vuota alla fine.
- Il file credenziali **reale** dell'utente è stato letto una volta in locale per verificare che la guardia lo accetti (nessuna rete, niente stampato): è dentro il recinto.

## 8. Il telefono (`tools/chaos/s25.ps1`)

Per la prova del §5.5/F16 (circa 100 interruzioni):

1. Dal PC: `area.ps1 -Mode seed -Files 300 -Manifest build\chaos\manifest.txt` semina `mcg-it/<giro>/r0/sorgente/` con 300 originali (tra cui, ogni tanto, uno grande) e scrive il **manifesto** con le impronte.
2. Sul telefono (build debug con gli account di prova, D33 attiva): da Cartelle NAS aprire `mcg-it/<giro>/r0/sorgente`, selezionare tutto e spostare in `test-comune` (una mossa tra share: copia, verifica, pubblicazione, sorgente nel cestino).
3. Subito dopo: `s25.ps1 -Serial <numero> -Interruptions 100 -Manifest build\chaos\manifest.txt -Verify`. A intervalli casuali (6-40 s) lo script uccide il processo (`run-as … kill -9`, poi `am crash` se non riesce; si può scegliere `-KillMode`) o spegne il Wi-Fi per 3-20 s. Dopo ogni morte rilancia l'app dopo 4 s (così la ripresa parte come all'avvio vero). Alla fine aspetta 3 minuti che la ripresa finisca e lancia il verificatore.
4. Il **calendario dipende solo dal seme** (`-Seed`): lo stesso seme, la stessa prova. `-DryRun` lo stampa insieme ai comandi `adb` e non esegue niente (nemmeno `adb`).

Garanzie dello script: tocca **un solo** dispositivo (`-Serial` o `ANDROID_SERIAL`; con `none` rifiuta, tranne a secco); il Wi-Fi viene **sempre** riacceso (anche se lo script è interrotto); non scrive sul NAS e non legge dati dell'utente; il racconto resta in `build\chaos\s25-<data>.log` (solo orari, numeri e azioni).

**Limite**: sul telefono il diario locale vive nell'app. Dal PC il verificatore controlla ciò che il NAS può dire (invarianti 1, 2, 3, 4 e 5, con i file del cestino proprio del dispositivo `device=` del manifesto), non la voce del diario locale. Quella si guarda sul telefono (Diagnostica → NAS → Operazioni).

## 9. Cosa serve da P12 (e da P16)

1. **Lo stack vero nel banco** (5 minuti, quando le API sono ferme): un file `ProductionChaosStackFactory.kt` in `core-nasops/src/test/.../chaos/`. Il banco lo trova per nome e lo usa da solo: `ChaosBenchTest` smette di saltare la seconda prova. È stato provato su una copia dello stato di lavoro di P12:

   ```kotlin
   package dev.pampa.mycloudgallery.core.nasops.chaos

   object ProductionChaosStackFactory : ChaosStackFactory {
     override val name = "operazioni vere di P12 (NasOpsEngine, NasTrashService)"

     override fun newStore(world: ChaosWorld): OpsStore = InMemoryOpsStore()   // il diario che sopravvive alle morti

     override fun create(env: StackEnv): ChaosStack {
       val lock = NasOpsLock()
       val access = ShareAccessView { share -> env.readOnly(share) }
       val engine = NasOpsEngine(
         deviceId = env.world.deviceId, accountId = env.world.accountId, fileOps = env.fileOps, reader = env.reader,
         store = env.store, clocks = env.clocks, gate = env.gate, access = access,
         records = TrashRecordWriter.NONE, lock = lock, newId = env.newId,
       )
       val trash = NasTrashService(
         deviceId = env.world.deviceId, accountId = env.world.accountId, shares = { env.world.shares }, store = env.store,
         fileOps = env.fileOps, reader = env.reader, clocks = env.clocks, gate = env.gate, access = access,
         remote = RemoteTrashSource.NONE, lock = lock, authorName = { null },
       )
       return ChaosStack(engine, trash)
     }
   }
   ```

   Con i record firmati veri (`SignedTrashRecords`, come in `p12/P12Support.kt`) si aggiunge `override val writesTrashRecords = true`: il verificatore vuole allora `trash/<io>/rec/<id>` per ogni file nel cestino. Lo stack vero **non deve avere** `unfaultable`, `cancelIsSafe` né `knownDefectHit`: se il banco trova qualcosa, è da sistemare, non da escludere. Con il diario Room vero (`RoomOpsStore`, Robolectric) basta cambiare `newStore`.
2. **Sistemare ciò che il banco ha già trovato** (§6): D1-D4 valgono anche per l'esecutore vero dove li ha copiati; in particolare **"Ferma"**: prima di annullare un elemento di spostamento tra share, controllare se la pubblicazione è avvenuta (temporaneo assente + destinazione con dimensione e hash giusti = pubblicato, si porta a termine, non si annulla); annullare un elemento solo dopo aver eliminato il suo temporaneo con successo; non far fallire un elemento per un errore di lettura passeggero. E decidere O1 e O3. *Fatto da P12 (anche nell'esecutore vero: "Ferma" che porta a termine un elemento gia' pubblicato, annullamento solo dopo aver tolto il temporaneo, nessun fallimento per un errore di lettura passeggero; O1 deciso: un piano uguale a uno gia' chiuso con una conferma nuova e' una richiesta nuova). Esclusioni del fake tolte al P16.*
3. **L'aggancio al NAS vero**: una classe `dev.pampa.mycloudgallery.core.nasops.chaos.SmbChaosBinding` (un `object`) che implementa `ChaosAreaBinding`: `openTarget(credentials, guard)` apre la sessione con l'account di prova e restituisce un `ChaosTarget` i cui mondi (`openWorld(round)`) sono **radicati in `guard.roundRoot(round)`**: primitive di scrittura con `root` (c'è già in `SmbNasFileOps`, serve una fabbrica pubblica con la radice), la lettura radicata (`RootedReader` sopra `mediaReader()`), `OpClocks` con l'ora del NAS, `connectionLost()` che invalida la sessione, `putFile` con `openTemp`/`write`/`finish`/`publishTemp`; `openReader` per la verifica da sola (lettore non radicato). Sessione, rete e interruttore come in `NasIntegrationSuiteTest`. La guardia e i decoratori sono già pronti.
4. **L'interruttore** `-PnasIntegration` per core-nasops (3 righe in `build.gradle`, §3), se si preferisce alla variabile d'ambiente.
5. **Per il file da 200 MB**: oggi un originale è un `ByteArray` (il processo di test di Gradle ha 512 MB di heap di default): per `chaos.bigFileMb` alto serve alzare l'heap del test o un `putFile` a flusso (`ChaosWorld.putFile` oggi prende i byte).

## 10. Che cosa non copre (e chi lo copre)

- **Pubblicazione dell'indice** e **"Scarica sul telefono"**: stanno in `core-index` (P3: outbox che sopravvive alla morte del processo a ogni passo) e in `core-mediastore`/`feature-viewer` (P8: morte a metà, `IS_PENDING` ripulito). Questo banco non li conosce: il perimetro di P15 è `core-nasops`.
- **Interferenza del PC** a metà operazione (sorgente modificata, file aperto, E-D13, E-N13): ci sono i test per caso di P12; qui il PC fa solo cose "pulite" (un file nuovo).
- **Il catalogo dell'app** (la scansione di `core-nasmedia`, P5): gli invarianti guardano il NAS con lo stesso lettore, non il database della Libreria. La coerenza della Libreria dopo una mossa è il test d'integrazione di P16 e la verifica sul campo F16.
- **Il tempo del NAS vero**: sul NAS vero non si fanno passare 31 giorni; la pulizia del cestino scaduto si prova sul NAS finto e sul simulatore SMB (E-N16 e simili, come da §5.4 punto 9). Sul campo (F20) la build **debug** accorcia i 30 giorni a qualche minuto (§12).

## 11. Tempi

2.000 giri sul NAS finto: ~13 secondi (limite del brief: 5 minuti). 600 giri sul simulatore SMB con le primitive vere: ~12 secondi. L'intero pacchetto di test di P15 (banco, difetti iniettati, verificatore, determinismo, runner): ~45 secondi. Nessun test usa più di un thread.

## 12. La pulizia del cestino in minuti (prova sul campo F20, solo debug)

La scadenza dei 30 giorni non si puo' aspettare sul campo. Nella sola build **debug** un marcatore scritto da `adb` la accorcia
(`DebugTrashRetention` in `app/src/debug`; nella release non esiste e `tools/check.ps1` cerca il suo nome e quello del marcatore nel dex):

```powershell
# I minuti (da 1 a 43.200) che il cestino del NAS tiene le voci, letti all'avvio della parte NAS dell'app:
adb shell "echo 5 > /sdcard/Android/data/dev.pampa.mycloudgallery.debug/files/debug-trash-retention-minutes.txt"
# Tornare ai 30 giorni:
adb shell rm /sdcard/Android/data/dev.pampa.mycloudgallery.debug/files/debug-trash-retention-minutes.txt
```

Il percorso del pacchetto e' quello della build debug installata (`adb shell pm list packages | findstr mycloudgallery`). Il resto delle
regole non cambia: tetto di 200 file e 20 GiB per giorno, inventario delle cartelle, collegamenti (L35), salto dell'ora.
