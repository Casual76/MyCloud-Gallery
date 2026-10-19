# La prova delle 500 interruzioni (Fase 4B, P15)

> **Parte A**: le operazioni sui file del NAS (Fase 4B, P15), qui sotto. **Parte B**: il caricamento del **backup** (Fase 5A, P6), più sotto (da "Parte B"). **Parte C**: versioni, scambi, guardiano e "Libera spazio" (Fase 5B, P13), in fondo a questo file (da "Parte C").

| | |
|---|---|
| **Stato** | 15/10/2026 (P16). Il banco JVM gira a ogni `check.ps1` su **due stack**: le operazioni vere di P12 (`ProductionChaosStackFactory`) e quelle fake di P0-B, entrambe **senza esclusioni** (i difetti D1-D4 del fake li ha corretti P12; il P16 ha tolto le esclusioni, §6): 2.000 giri ciascuno, circa 7.000 interruzioni, zero violazioni, circa 10 secondi per stack. Il runner contro l'area di prova del NAS vero ha l'aggancio a smbj (`SmbChaosBinding`, P12) ma **non e' mai stato lanciato sul NAS vero**: al P16 la suite d'integrazione di `core-nas` si e' fermata al login (`AUTH_FAILED`, un solo tentativo: il file delle credenziali va aggiornato con le password di prova nuove). Lo script del telefono e' controllato a secco, **mai lanciato su un telefono**. |
| **Revisione 4B (15/10)** | I1 chiuso: il banco gira anche sulle **vere `SmbNasFileOps`** e sulla sessione SMB di produzione, sopra il simulatore SMB di core-nas (`SimSmbTarget`, `SmbPrimitivesChaosBenchTest`): **600 giri, 2.172 interruzioni, 0 violazioni**, ~12 s, a ogni `check.ps1` (§2). Gli invarianti valgono con le correzioni della revisione (date del cestino, inventario delle cartelle, tetto per giorno, "Ferma" nel diario). |
| **F16 sul NAS vero (15/10)** | Primo giro lungo sul WD: 493 giri, 401 interruzioni, **13 giri rossi** (operazioni "in pausa" per sempre, doppioni da spostamenti fermi, temporanei rimasti). Causa: **handle del telefono rimasti aperti sul NAS** (§6bis). Corretto nel codice di produzione (la lettura condivide la cancellazione, chiusure ordinate, motivo della pausa nel diario) e nel banco (la morte del processo chiude la sua connessione, come sul telefono); il simulatore SMB ora tiene gli handle come Samba. Dopo: **537 giri, 400 interruzioni, 0 violazioni** sul NAS vero (142 min), area pulita |
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
| **JVM, simulatore SMB** (revisione 4B, I1) | `SmbPrimitivesChaosBenchTest`: gli stessi giri con le operazioni vere di P12 sopra la **sessione SMB di produzione e le vere `SmbNasFileOps`** (`FILE_CREATE`, rinomina senza sostituire, copia lato server a blocchi, codici NTSTATUS e, dalla prova F16, **gli handle dei client con le loro condivisioni** come Samba: §6bis), sul simulatore di core-nas (`InMemorySmbBackend`, uno nuovo per giro: `SimSmbTarget`); il tempo passa (30 giorni del cestino) e la copia lato server si puo' rifiutare (ripiego dal telefono); "connessione caduta" chiude davvero le connessioni; la morte del processo chiude la sua | 600 giri (`MCG_CHAOS_SMB_ROUNDS`, al massimo 3.000), 2.172 interruzioni, ~14 s | **verde** (0 violazioni). Con gli handle e il codice di prima: 49 giri rossi su 600, la stessa firma del NAS vero |
| **PC, area di prova del NAS vero** | `ChaosNasIntegrationTest` modo `run`: giri brevi, **un colpo per passo**, file da 1 MB e da `chaos.bigFileMb` MB, dietro la guardia, solo sotto `mcg-it/<giro>/` | ≥ 400 interruzioni (`chaos.rounds`) | 15/10: primo giro lungo 13 rossi su 493 (§6bis); dopo le correzioni **537 giri, 400 interruzioni, 0 violazioni** (142 min) |
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
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -TimeoutMinutes 300  # con il PC carico (tetto del task, di norma 180)
# Rifare sul NAS vero UN giro rosso del rapporto ("giro 149, seme di base 113917122009500", 1 interruzione):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -Seed 113917122009500 -FirstRound 149 -Interruptions 1
# L'avanzamento di una prova lunga, giro per giro: core-nasops\build\chaos\area-progress-<giro>.txt

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
| `MCG_CHAOS_SEED`, `MCG_CHAOS_FIRST_ROUND` | NAS vero, `run` | seme di base e primo giro: con le interruzioni del giro rifanno un giro rosso (`area.ps1 -Seed -FirstRound`) |
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

## 6bis. F16 sul NAS vero: gli handle del telefono rimasti aperti (15/10)

**Il primo giro lungo sul WD** (`area-report-20261015-125120.txt`: 493 giri, 401 interruzioni, 5.810 s) ha avuto **13 giri rossi**:
81 violazioni [8] (operazioni `paused` dopo 81 riprese, elementi fermi in `source_recorded`, `copied`, `verified`), 17 [2] (copia
arrivata e sorgente ancora li': conseguenza delle operazioni ferme), 10 [5] (temporanei non pubblicati), 1 [6], 2 [7]. Nessun file perso,
sovrascritto o rovinato. Le operazioni "rinomina", "crea cartella" ed "elimina" del rapporto non erano ferme loro: le violazioni di ogni
passo elencano l'operazione rimasta aperta, ed era sempre lo spostamento tra share di prima.

**Che cosa era.** 11 giri su 13 avevano una **morte del processo durante una lettura** (`OPEN` dopo, `READ_ALL` prima o dopo: 11 delle 14
morti cadute su una lettura; nessuna delle altre 230 morti). L'handle di lettura del processo morto restava **aperto sul WD**: il banco
teneva la stessa connessione SMB e la passava al processo nuovo (sul telefono il sistema chiude il socket del processo ucciso e Samba chiude
i suoi handle). Samba applica le condivisioni, e la lettura **non condivideva la cancellazione**: la rinomina dello stesso file (la sorgente
nel cestino dopo la verifica, il temporaneo da pubblicare) rispondeva `SHARING_VIOLATION`, cioe' "file aperto dal PC" (E-N13), e l'elemento
aspettava 1, 2, 5... minuti. Nel banco l'ora del NAS non avanza: l'attesa non finiva mai. Rigiocato il giro 149 sul NAS vero (seme di base
113917122009500, una interruzione): stesso esito in 0,9 s, e il diario diceva solo `source_recorded locked`, **l'operazione nessun motivo**.
Gli altri 2 giri (394, 480, **zero interruzioni**) erano una corsa del codice di produzione: lo scrittore del temporaneo chiude l'handle su
un altro thread e la pubblicazione che segue arriva al NAS prima della chiusura (`Locked`). Il simulatore SMB non teneva gli handle dei
client: per questo 600 giri su 600 erano verdi. Gli indiziati (salto dell'ora, ancora dopo il login, tetto del giorno, spazio, "Ferma") non
c'entravano: nel banco il cancello e' sempre aperto e ogni pausa era un'attesa di file "aperti".

**Correzioni nel codice di produzione** (`core-nas`, `core-nasops`, `:app`):

| | |
|---|---|
| Lettura che condivide la cancellazione | `SmbOpenMode.READ` (`SmbTransport.kt`, usato da `SmbjTransport`): un nostro handle di lettura (una chiusura in volo, la Libreria che legge, una connessione persa che il NAS tiene ancora per minuti) non blocca la rinomina o il cestino di quel file, a noi ne' al PC. Provato sul WD (`t15` della suite). Chi rinomina condivide solo la lettura (nessuno scrive fra il controllo del timbro e la rinomina), la cancellazione con il controllo della dimensione resta esclusiva |
| Chiusure ordinate | `SmbNasSessionManager.awaitCloses`: ogni operazione, prima di partire, aspetta le chiusure chieste prima di lei sulla stessa sessione (`closeLater`), ognuna al massimo 2 s dalla sua partenza; quelle di una connessione gia' chiusa non si aspettano. Il tetto serve: sul WD una `CLOSE` in volo mentre la connessione si chiude non torna mai e smbj la aspetta 60 s (una prima versione senza tetto rallentava il giro lungo di 60 s a ogni morte del processo; lo dice `area-log-<giro>.txt`, "chiusura lenta") |
| Aperture non consegnate | `open` e `openTemp`: annullati mentre il NAS risponde, chiudono l'handle che nessuno ricevera' |
| Il motivo della pausa nel diario | `nas_op.error_code` (stessa colonna, codici di `PauseReason` distinti da quelli di `OpErrorCode`: `RulesTest`); l'attesa di file aperti scrive `locked`; dopo un riavvio la schermata dice il motivo vero, non "condizioni". Diagnostica: `NAS_OPS_STATE` conta le pause per motivo dal diario (`reason.<MOTIVO>`; `UNKNOWN` = pausa senza motivo, gravita' alzata) |

**Il banco**: la morte del processo chiude la connessione del processo morto (`ChaosWorld.processDied`, anche nella ripresa a vuoto), come
il sistema sul telefono; la violazione [8] dice il motivo letto dal diario (operazione ed elementi: passo, codice, tentativi, prossima prova);
`-FirstRound` vale anche per `run`; l'avanzamento sta in `area-progress-<giro>.txt` (una riga per giro) e gli avvisi del codice sotto
prova (solo codici e tempi) in `area-log-<giro>.txt`; `-TimeoutMinutes` alza il tetto del task (default 180).

**Il simulatore** (`InMemorySmbBackend`): ogni apertura resta viva sul server con diritti e condivisioni (le stesse di `SmbjTransport`,
`SmbOpenMode`) finche' il client non la chiude o la connessione non cade per il server; **connessioni abbandonate** (rete persa: il NAS tiene
sessione e handle finche' non se ne accorge, `abandonAllConnections`/`reapAbandoned`); cancellazione alla chiusura dell'ultimo handle
(`DELETE_PENDING`); una cartella con un handle **dello stesso client** aperto sotto non si rinomina (`ACCESS_DENIED`, visto sul WD; un altro
client non conta). Con il simulatore nuovo e il codice di prima il banco SMB ha **49 giri rossi su 600** con la stessa firma del WD; casi fissi
in `OwnHandlesTest`, `SimulatorHandlesTest` (core-nas) e `SmbStaleHandlesTest`, `PauseReasonDiaryTest` (core-nasops), che con il codice di
prima falliscono (`source_recorded/locked`; `failed/permission_denied` per il cestino di una cartella con sottocartelle e le chiusure in volo).

**Dopo** (15/10, sera, `area-report-20261015-160842.txt`): area pulita con la suite, giro corto 27 giri / 20 interruzioni / 0 violazioni,
poi **537 giri, 400 interruzioni, 0 violazioni** in 8.545 s (142 min): connessione caduta 101, sessione scaduta 13, socket fermo 22, morte
del processo 264 (16 su `OPEN`, 6 su `READ_ALL`: il caso che prima restava fermo); prima 227, dopo 173; 1.352 passi (242 spostamenti tra
share, 315 eliminazioni, 48 "elimina subito"), 8.584 file riletti con SHA-256 (58 GB). `area-log` riporta 179 chiusure "lente" (60 s, smbj)
tutte su connessioni gia' chiuse: nessuna richiesta le ha aspettate. Alla fine la suite ha ripulito `mcg-it/` (verificato con entrambi gli
account). La durata dipende dai file grandi (200 MB): i giri normali durano 2-5 s, quelli con il file grande fino a 5 minuti.

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

2.000 giri sul NAS finto: ~13 secondi (limite del brief: 5 minuti). 600 giri sul simulatore SMB con le primitive vere: ~15-25 secondi
(dalla prova F16 il simulatore tiene gli handle e le richieste aspettano le chiusure in volo). L'intero pacchetto di test di P15 (banco, difetti iniettati, verificatore, determinismo, runner): ~45 secondi. Nessun test usa più di un thread.

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


---

# Parte B. La prova delle interruzioni del backup (Fase 5A, P6)

| | |
|---|---|
| **Stato** | 16/10/2026 (P6). Il banco JVM gira a ogni `check.ps1` sul NAS finto con **due motori**: l'esecutore di riferimento di P0 (`InMemoryBackupEngine`) e il **motore vero** di P2 (`SafeUploadEngine`, con la deduplica vera di P3): **2.000 giri ciascuno, circa 4.000 interruzioni, zero violazioni, 6 secondi a motore**. Sul **simulatore SMB** con la sessione di produzione e le primitive vere del backup (`SmbNasBackupOps` sopra le primitive SMB della 4B): **600 giri a motore, circa 1.200 interruzioni, zero violazioni**, 5 secondi. Cacce lunghe con altri semi (20.000 giri a motore sul NAS finto, 3.000 sul simulatore SMB, circa 180.000 interruzioni in tutto): zero violazioni. Il banco ha trovato **un difetto vero** del motore (F1, §B6), dichiarato e poi corretto in entrambi i motori. Il runner contro l'area di prova del NAS vero e lo script del telefono sono **scritti e provati a secco / sul simulatore**; **mai lanciati sul NAS vero né sul telefono**. |
| **Dove** | `core-backup/src/test/kotlin/dev/pampa/mycloudgallery/core/backup/chaos/` (il banco), `tools/chaos/area.ps1` e `tools/chaos/s25.ps1` (modo `backup`), questo file |
| **Riferimenti** | `docs/fase5-progetto.md` §2.2 (il caricamento), §5.2 (il telefono finto), §5.3 (area di prova e recinzioni), §5.4 (invarianti e interruzioni), D12, D13, D33; riga P6 del §4.4. Parte A (sopra): il banco della 4B, di cui questo ripete l'impianto |

## B1. Che cosa promette

Il caricamento dei media del telefono sul NAS (§2.2) deve sopravvivere a **interruzioni casuali** senza lasciare cose a metà. Un'interruzione è un colpo che cade a un punto preciso, **prima** che il NAS (o il diario) abbia applicato la chiamata oppure **dopo** (la modifica c'è, la risposta si perde). Dopo una morte del processo nessun oggetto del vecchio processo tocca più niente: si ricostruisce tutto dal diario, dal catalogo, dal telefono e dal NAS (che sopravvivono).

| Colpo | Come lo vede il motore | Dove cade |
|---|---|---|
| connessione caduta, socket fermo, sessione scaduta | `Timeout`, `Stalled`, `SessionExpired` (e la sessione si chiude davvero: gli handle del NAS vanno via) | ogni chiamata al NAS: `openTemp`, `reopenTemp`, ogni scrittura, punto di ripresa, `finish`, `publishTemp`, `deleteOwnTemp`; `stat`, `open`, `readAll`, letture a intervalli |
| **morte del processo** | niente prosegue, si riparte dal diario | ogni chiamata al NAS, **ogni scrittura del diario e del catalogo**, ogni chiamata al telefono |
| NAS pieno a metà (E-R9) | `NasFull` | scritture |
| file aperto dal PC (E-N13) | `Locked` | `publishTemp`, `deleteOwnTemp`, `reopenTemp` |
| password rifiutata (E-R8) | `AuthBlocked`: il giro si ferma | ogni chiamata al NAS |
| il NAS restituisce byte diversi (E-B18) | **nessun errore**: un byte della scrittura cambia | scritture |
| il telefono: lettura che fallisce | `IOException`, riga e timbro invariati (D15) | lettura dei byte |
| il telefono a **metà lettura** | il file viene riscritto (Samsung "Salva", E-B15), sparisce (E-B4) o cambia `_ID` (E-P12) con la versione del MediaStore | `FakePhoneBytes.duringRead`, dopo n byte |
| "Ferma", share congelata, un file del PC | `stop()` dell'utente, cancello chiuso (D32, E-B19), un file con il nome previsto dell'elemento (E-B3) | prima di una chiamata al NAS |

Tra un passo e l'altro il mondo cambia: il MediaStore si ricostruisce (`_ID` rinumerati), una foto o un video nuovo, un file riscritto sul posto, una foto cancellata, i permessi ritirati, **due giorni e la pulizia dei propri file** (D33, E-B21: un temporaneo fermo da giorni e citato dal diario deve restare), un file del PC con il nome previsto. La lettura del telefono (la scansione del MediaStore) ritrova le righe rinumerate, segna come sparite le cancellate e registra come **versione nuova** quella riscritta.

**Gli invarianti** del §5.4, controllati dopo ogni morte, a fine passo e in una rilettura finale completa (`BackupVerifier`):

| # | Invariante | Come si verifica |
|---|---|---|
| 1 | Nessun file a metà visibile | ogni file nuovo fuori da `.mycloudgallery/` ha lo SHA-256 di un contenuto che il telefono ha avuto (prima o dopo una riscrittura) o di un file che c'era prima del giro |
| 2 | Nessun doppione creato | nessun file nuovo ha i byte di un file che c'era già nell'area privata, e nessun byte nuovo compare due volte |
| 3 | Nessuna sostituzione | ogni file che c'era (compresi quelli messi dal PC durante il giro) ha ancora gli stessi byte, lo stesso nome e lo stesso `fileId` |
| 4 | Nessuna perdita | ogni media che il catalogo dice al sicuro ha nell'area privata una copia **presente** con lo SHA-256 della versione; il telefono non cambia (nella 5A il motore non ha nessuna primitiva di scrittura sul telefono) |
| 5 | Hash al 100% | la rilettura finale riscansiona ogni file per intero con `readAll`, senza cache (`cacheHits == 0`); le verifiche a metà possono usare la cache solo nel runner contro il NAS vero |
| 6 | Temporanei | a metà solo i propri `<io>-bk-` citati da un elemento non finito; a lavoro finito nessuno; **la pulizia non toglie un temporaneo citato dal diario** (D33) |
| 7 | Catalogo coerente | ogni elemento `DONE` o `LINKED_EXISTING` ha il suo media al sicuro; nessun "al sicuro" per l'**ultimo** elemento `FAILED` di un media (un fallito superato da un elemento riuscito dopo "Riprova" non conta) |
| 8 | Diario coerente | ogni transizione è ammessa da `UploadStateMachine`; i nomi finali di `PUBLISHED`, `LINKED`, `DONE`, `LINKED_EXISTING` sono sul NAS |
| 9 | Il lavoro arriva in fondo | dopo le riprese ogni elemento è `DONE`, `LINKED_EXISTING` o `GONE`; nessun `FAILED` per un guasto passeggero; ogni media ancora sul telefono è al sicuro |
| 10 | Nessun file fuori posto | niente di nuovo fuori da `Backup/` dell'area privata (né nell'area comune, né nella cartella dell'app salvo i temporanei) |
| 11 | Il NAS si legge | una lettura che non riesce non passa in silenzio: la scansione del verificatore con problemi è una violazione, e un giro che finisce `COMPLETE` conta in attesa ogni elemento che lascia aperto |
| 15 | Il motore non lancia | un'eccezione uscita da `run`/`resumeAll` per un guasto del NAS o del telefono è una violazione (contratto di §1.2). **Era il numero 12 fino alla 5A**: dalla 5B i numeri 12-14 sono gli invarianti della 5B del §5.4 (Parte C, §C1) e questo è il 15 |

I guasti sono **passeggeri** per costruzione: i colpi che cambiano il senso della prova se ripetuti (byte alterati: tre verifiche non tornate sono `VERIFY_FAILED`, E-B18; lettura del telefono che fallisce: tre volte è `UNREADABLE`, D15) sono al più due per giro (`FaultBudget`).

## B2. I banchi

| Dove | Che cosa | Quante | Stato |
|---|---|---|---|
| **JVM, NAS finto** | `BackupChaosBenchTest`: giri casuali sul NAS finto in memoria (`InMemoryNasMedia`), con il telefono finto (`FakePhoneBytes`), il diario e il catalogo in memoria di P0; due volte, con lo stesso banco: sull'**esecutore di riferimento** (`ReferenceStack`) e sul **motore vero** di P2 (`ProductionStack`: `SafeUploadEngine`, uno o due file in volo a sorte, `PrivateCopyFinder` di P3) | 2.000 giri a motore (`MCG_BACKUP_CHAOS_ROUNDS`), circa 4.000 interruzioni, 6 s | **verde** su entrambi |
| **JVM, simulatore SMB** | `BackupChaosSmbBenchTest`: gli stessi giri con la **sessione SMB di produzione** (`SmbNasSessionManager`) e le **primitive vere del backup** (`NasBackupOpsFactory` → `SmbNasBackupOps`) sopra `InMemorySmbBackend` (semantica Samba: `FILE_CREATE`, rinomina senza sostituire, fine file sullo stesso handle, handle che il NAS tiene finché la connessione non cade); la morte del processo chiude la sua connessione, come sul telefono (lezione di F16, parte A §6bis) | 600 giri a motore (`MCG_BACKUP_CHAOS_SMB_ROUNDS`, al massimo 5.000), circa 1.200 interruzioni, 5 s | **verde** su entrambi |
| **PC, area di prova del NAS vero** | `BackupNasIntegrationTest` modo `run`: lo stesso giro, il "telefono" sono file veri o virtuali (1 MB, 200 MB e il video enorme di `backup.bigFileMb`), un colpo per passo, dietro la guardia, solo sotto `mcg-it/<giro>/` | ≥ 400 interruzioni (`backup.rounds`) | scritto; provato **sul simulatore SMB** dietro la stessa guardia (`BackupAreaRunnerTest`); **mai lanciato sul NAS vero** |
| **Telefono, area di prova** | `tools/chaos/s25.ps1 -Mode backup`: durante il primo backup di `mcg-prova`, `kill -9` del processo o Wi-Fi spento per 3-20 s a tempi casuali; poi il manifesto da `adb shell sha256sum` e il verificatore dal PC | ≥ 100 | scritto; **solo a secco** |

## B3. Come si lancia

Tutto dal PC, da `C:\VibeCoded Projects\MyCloud-Gallery-2`. `tools\chaos\area.ps1 -Mode backup -Stage <livello>` imposta le variabili e lancia Gradle (`:core-backup:testDebugUnitTest`, con `--rerun`); con `-DryRun` stampa e basta.

```powershell
# I banchi JVM (girano già in check.ps1 insieme agli altri test di core-backup, ~25 s in tutto):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage jvm                          # 2.000 giri a motore, NAS finto
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage smb                          # 600 giri a motore, simulatore SMB
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage jvm -Rounds 20000 -Seed 777  # una caccia più lunga (~1 minuto a motore)

# Rifare UN giro rosso (il rapporto dice "giro 163, seme di base 20261016"):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage jvm -Seed 20261016 -FirstRound 163 -Rounds 1

# Verificare DA SOLO lo stato salvato di un giro rosso (core-backup\build\chaos\backup-state-<giro>.txt):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage state -Manifest core-backup\build\chaos\backup-state-163.txt

# Il runner contro l'area di prova (serve il file delle credenziali, §B8):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Interruptions 20      # giro corto di prova (al massimo 20)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run                        # le 400 (o backup.rounds)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -TimeoutMinutes 600    # con i video grandi (ore)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Seed 113917 -FirstRound 149 -Interruptions 1   # rifare UN giro rosso sul NAS vero

# La prova sul telefono (§B9): semina, (avvio del primo backup nell'app), interruzioni, verifica
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -DryRun                 # il piano della semina, senza telefono
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -Serial <numero> -Run 20261016-101500-1a2b3c4d
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -DryRun -Interruptions 100 -Seed 42   # il calendario
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial <numero> -Interruptions 100 -Manifest build\chaos\backup-manifest-<giro>.txt -Verify
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step verify -Serial <numero> -Manifest build\chaos\backup-manifest-<giro>.txt
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -SelfTest                                       # la conversione di sha256sum nel manifesto, su un testo finto
powershell -ExecutionPolicy Bypass -File tools\chaos\s25-finto.ps1                                          # semina, interruzioni, verifica e pulizia contro un adb FINTO (nessun telefono, nessun NAS)
```

**Variabili** (le imposta `area.ps1`; si possono dare anche a mano, poi `--rerun`: Gradle non le vede come ingressi):

| Variabile | Vale per | Significato |
|---|---|---|
| `MCG_BACKUP_CHAOS_ROUNDS`, `MCG_BACKUP_CHAOS_SEED`, `MCG_BACKUP_CHAOS_FIRST_ROUND` | banco JVM sul NAS finto | quanti giri (2.000), seme di base (20261016), primo giro |
| `MCG_BACKUP_CHAOS_SMB_ROUNDS` | banco JVM sul simulatore SMB | quanti giri (600, al massimo 5.000); seme e primo giro come sopra |
| `MCG_NAS_INTEGRATION=true` (o `-DnasIntegration=true`, o `-PnasIntegration`) | NAS vero | senza, nessuna prova tocca il NAS e nessun file di credenziali si legge |
| `MCG_BACKUP_MODE` | NAS vero | `run` (default) o `verify` |
| `MCG_BACKUP_ROUNDS`, `MCG_BACKUP_SEED`, `MCG_BACKUP_FIRST_ROUND`, `MCG_BACKUP_RUN`, `MCG_BACKUP_STACK=reference` | NAS vero, `run` | le interruzioni se `backup.rounds` non è nel file, il seme di base, il primo giro, il nome del giro, l'esecutore di riferimento invece del motore vero |
| `MCG_BACKUP_MANIFEST`, `MCG_BACKUP_SETTLED=false` | NAS vero, `verify` | il manifesto del telefono; "prova non ancora ferma" |
| `MCG_BACKUP_STATE` | verificatore da solo | lo stato salvato da un giro rosso |

**Per il NAS vero** il file delle credenziali è lo stesso della Parte A (`%USERPROFILE%\.mycloudgallery\nas-it.properties`, §5.4 del progetto della 4B): `user`, `password`, `share`, `share2`, `host`, `guid`, `user2`, `password2`, `privateShare2`, più due chiavi nuove, **facoltative** (§5.3 del progetto della 5A):

```properties
backup.rounds=400        # interruzioni del runner (0 = non eseguire). Senza: MCG_BACKUP_ROUNDS, poi 400
backup.bigFileMb=3072    # il video enorme di E-B1 (~1 video su 125 lo è). Senza la chiave non c'è
```

**Passare al motore vero** (o a un altro motore) richiede **una riga**: il banco riceve il motore da un `BackupChaosStackFactory` (`create(env: EngineEnv): BackupEngine`, dove `env` ha le porte già avvolte dai colpi). `ReferenceStack` e `ProductionStack` stanno in `BackupChaosStacks.kt`; `BackupChaosBenchTest` e `BackupChaosSmbBenchTest` passano l'uno o l'altro. Con il diario Room vero (`RoomBackupStore`) basta darlo a `BackupChaosWorld.store` al posto di `InMemoryBackupStore` (richiede Robolectric).

## B4. Come funziona un giro

Un giro (`BackupChaosRound`) è ripetibile: stesso seme, stessi passi, stessi colpi. Il seme del giro viene da (seme di base, numero del giro) (`BackupChaosBench.seedOf`); `BackupChaosDeterminismTest` prova che due serie con lo stesso seme hanno lo stesso rapporto.

1. **Il mondo**: un telefono finto con 1-5 foto (1-60 KB) e 0-2 video (40-200 KB; le scritture sono da 16 KiB e il punto di ripresa ogni 32 KiB, così la ripresa si prova con file piccoli), con data di scatto normale, vecchia, assente o assurda (`Senza data/`); a volte due media con lo stesso nome nella stessa cartella (E-B3 fra i nostri file); l'8% delle righe già cancellate. Sul NAS: a volte un vecchio backup identico che il catalogo conosce (deduplica, D8), un file identico o **diverso** con il nome previsto che il catalogo non conosce (E-B3, E-B5, E-B16), e qualche file qua e là (anche nell'area comune: nessuno deve cambiare). Il 17% dei media ha un colpo del telefono a metà lettura.
2. **Da 1 a 3 passi**: ognuno è un lavoro del backup ("foto e video", "solo le foto", "solo i video", "riprendi dal diario"). Un passo senza niente da fare non prova niente: riprendere senza elementi aperti diventa un giro intero, e un giro senza media in attesa trova media nuovi (il telefono continua a fare foto). Prima di ogni passo, due volte su tre circa, il mondo cambia (§B1).
3. **I colpi** sono sorteggiati **per passo**, da 1 a 5 (15/20/25/20/20%): 25% sul diario e sul catalogo (sempre morte), 8% sul telefono, il resto sul NAS (metà puntati a una primitiva precisa, così anche `reopenTemp` o `deleteOwnTemp` vengono colpite); tipo e momento (prima o dopo) a sorte. Una lettura "dopo" è uguale a "prima": non si spreca un colpo, salvo la morte.
4. **Dopo ogni passo e ogni morte**: si guarda il mondo così com'è (verifica "a metà"), si ricostruiscono gli oggetti sullo stesso diario, catalogo, telefono e NAS e si riprende. Dopo un colpo che non è una morte il processo resta quello di prima o riparte (a sorte).
5. **Fine giro**: tolti i guasti, si riprende dal diario finché non c'è più niente da fare (al massimo 12 riprese, con la lettura del telefono tra l'una e l'altra), poi la **rilettura completa senza cache** e gli invarianti a lavoro finito.

Il **modello** (cosa deve esserci) è indipendente dal motore: i contenuti del telefono, i file che c'erano, quelli che il PC ha messo durante il giro. Il verificatore non conosce il motore.

## B5. Il verificatore, anche da solo

`BackupVerifier.check(state, fase)` giudica uno `BackupState`: fatti semplici, senza oggetti vivi (file del NAS con SHA-256 e `fileId`, impronte del telefono, elementi e transizioni del diario, media e copie del catalogo, temporanei citati, quelli che la pulizia ha tolto, note del banco). Si cattura da un mondo che gira (`BackupChaosWorld.capture`), si scrive in un file di testo e si rilegge (`BackupStateFile`): un giro rosso lascia `core-backup/build/chaos/backup-state-<giro>.txt`, che si verifica **senza il banco, il motore o il NAS**:

```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage state -Manifest core-backup\build\chaos\backup-state-163.txt
```

`BackupVerifierTest` prova il verificatore invariante per invariante con stati fatti a mano (anche un solo byte diverso lo rompe), e con uno stato vero salvato, riletto e manomesso. Nei rapporti del NAS vero `showNames = false`: niente nomi di file, solo cosa è successo.

Per l'**area di prova** e per il **telefono** il diario locale non c'è (sta nell'app): `BackupAreaVerifier` controlla gli invarianti che il NAS può dire da solo, dal **manifesto** (`# mcg-backup-manifest 1`: `run`, `device`, `dest=<share>|<cartella>`, `app=<share>|<cartella>`, `phone=<sha256>|<byte>|<etichetta>`, `pre=<sha256>|<share>|<percorso>` per ciò che c'era già in `Backup/`) e in **sola lettura**: 1 (ogni file in `Backup/` ha i byte di un file del telefono o di uno che c'era), 2 (nessun file del telefono due volte), 3 (quelli che c'erano non sono cambiati), 4 (a lavoro finito ogni file del telefono c'è), 5 (rilettura completa), 6 (a lavoro finito nessun temporaneo `-bk-`), 11. Gli invarianti 7, 8, 9 e il punto 6 "a metà" si guardano sul telefono (Diagnostica → Backup).

## B6. Difetti e osservazioni trovati

**F1 (corretto): un elemento restava aperto per sempre se la foto spariva mentre il MediaStore cambiava versione** (motore di riferimento e motore vero, stesso codice in `refreshPhone`). Scenario: un elemento del diario è aperto (`PLANNED`, o in caricamento con il suo temporaneo), la foto viene cancellata dal telefono e il MediaStore si ricostruisce (`_ID` rinumerati, E-P12). L'`_ID` dell'elemento è di una versione vecchia (`PhoneLookup.Stale`); la lettura del telefono ha già tolto la riga dal catalogo, quindi `BackupCatalog.phoneRefOf` risponde `null`. Il motore (`refreshPhone`: "la riga nuova dal catalogo, o si aspetta che la lettura del telefono la ritrovi") **aspetta**, e se la foto è cancellata la lettura non la ritroverà mai: l'elemento resta `PLANNED` (o `UPLOADING`, con il temporaneo che nessuno toglie) invece di diventare `GONE` ("persa prima del backup", E-B4). Senza il cambio di versione il motore risponde bene (`Gone` → `GONE`). Conseguenze: la pagina Backup dice "in attesa" per sempre, un temporaneo di un video a metà resta (citato dal diario, quindi la pulizia non lo tocca). Nessun file a metà, nessun doppione, nessuna perdita: sono violati solo l'invariante 9 e, per il temporaneo, il 6 "a lavoro finito". Frequenza nel banco: ~7% dei giri (è raro nella realtà: serve una cancellazione e una ricostruzione del MediaStore nella stessa finestra).
  **Corretto (P2)**. Il rimedio proposto (`phoneRefOf == null` → `GONE`) aveva il rischio inverso: il catalogo vero risponde `null` anche **mentre la lettura del telefono rilegge** (la scansione completa salva subito la versione nuova del cursore e rilegge le righe a lotti, anche in più giri; `PHONE_PLACE_SQL` vuole la versione del cursore), quindi una foto che c'è ancora sarebbe diventata "persa prima del backup", e il segno `LOST_BEFORE_BACKUP` toglie il media dai candidati senza che nessuno lo levi. Regola scelta: il motore chiede `BackupCatalog.phoneRowOf(media, versione, rigaVecchia)` (aggiunta compatibile alla porta di P0) e va a `GONE` **solo se il catalogo dimostra di aver riletto tutto il volume** dopo il cambio di versione e la riga non c'è (`Absent`); la riga vecchia (catalogo in ritardo) o una lettura non finita (`Rereading`) fanno aspettare. "Persa prima del backup" solo se il media non è più da nessuna parte (`mediaSafety == LOST`: un file riscritto ha la sua versione nuova). Il default della porta non dice mai `Absent`; `:app` (P7) la lega con `MediaCatalog.mediaStoreCursor` (versione diversa da quella della riga, `scanEpoch == null`, `generation != null`) e poi `phoneRefOf`. Tolti `ItemFact.waitsForRow`, `Invariant.KNOWN_GAP` e `BackupChaosKnownGapTest`; il banco torna rigoroso (un elemento fermo è l'invariante 9). Prove mirate: `BackupStaleRowTest` (i due motori: F1, catalogo in ritardo, lettura in corso, verificatore pulito) e i test F1 con un video a metà in `ReferenceEngineTest` e `UploadScenariosTest`.

**Osservazioni (non difetti del motore):**
- **O1. Falso positivo del verificatore di P0** (segnalato da P2): `BackupInvariants` diceva "al sicuro per l'elemento fallito" e "elemento fermo in failed" anche quando un elemento successivo dello stesso media era riuscito (dopo "Riprova"). Corretto: contano l'**ultimo** elemento del media (`BackupInvariantsRetryTest`); lo stesso nel verificatore del banco.
- **O2. La sessione scaduta chiude gli handle** sul NAS, come la connessione caduta: senza, il simulatore SMB teneva l'handle esclusivo di un temporaneo la cui apertura era "andata persa" e il verificatore (che legge) trovava `FileBusy`. È realismo del banco (su Samba la sessione scaduta chiude le sue aperture), non un difetto del motore.
- **O3. Guardiano, verifica e rotazione** (P3) non sono nel banco casuale: hanno i loro test "a ogni chiamata" (`SafetyEveryCallTest`, `FattoQuando53Test`). Il banco copre la deduplica (che è dentro il caricamento) e la verifica del caricamento (D4). Estendere il giro con un guardiano e una rotazione dopo un "bit rot" di una copia sul NAS (E-B8) è possibile con gli stessi colpi e un invariante in più.

## B7. Il banco ha i denti

`BackupChaosMutationTest` rompe di proposito il motore e vuole che il banco diventi rosso **con l'invariante giusto**, sull'esecutore di riferimento **e** sul motore vero. Il motore sta in `core-backup/src/main` (non si tocca): ogni errore è uno stack rotto che avvolge le porte del motore sano e ne riproduce l'**effetto** (`BackupChaosMutants.kt`), sopra i decoratori dei colpi (i guasti del banco colpiscono anche lui). Come per la 4B ("ogni difetto avvolge un pezzo dello stack e rompe una promessa").

| Errore introdotto | Come | Invariante che lo coglie | Giri rossi su 500 (riferimento / vero) |
|---|---|---|---|
| Pubblicare prima della verifica | a fine scrittura il temporaneo prende subito il nome definitivo, senza rileggerlo | **1** (un file visibile con byte che non sono di nessun media) | 36 / 30 |
| Saltare la rilettura | la "verifica" risponde l'impronta di ciò che il motore ha passato alla scrittura, invece di rileggere dal NAS | **1** e **4** (un file corrotto spacciato per al sicuro, senza la copia con i byte della versione) | 40 / 33 |
| Scrivere il diario dopo la chiamata | un diario che rimanda le scritture a dopo la chiamata al NAS che segue (o alla fine del giro); una morte in mezzo le perde | **6** (un temporaneo che il diario non cita) | 43 / 31 |
| Il temporaneo tolto dalla pulizia | la pulizia dei propri file (D33; sta in core-nasops, che core-backup non vede: il banco ne tiene un modello con la stessa regola) non rispetta i temporanei citati dal diario | **6** (la pulizia ha tolto un temporaneo citato) | 22 / 23 |

I primi due hanno bisogno dell'altro colpo: senza il NAS che altera un byte nessun difetto di verifica produce un file sbagliato (per questo "byte alterati" è fra i colpi). Il controllo è nello stesso test: lo stack sano sugli stessi giri resta verde (un rosso è colpa del difetto, non del banco).

## B8. L'area di prova sul NAS vero e la guardia (§5.3)

- **Quando gira**: solo con `MCG_NAS_INTEGRATION=true` (o `-PnasIntegration`, o `-DnasIntegration=true`) **e** il file delle credenziali; altrimenti i test risultano *saltati* con il motivo, **senza aprire un socket e senza leggere il file** (`BackupAreaGate`).
- **La guardia** (`BackupAreaGuard`, stesse regole della 4B): prima di qualunque connessione l'account (anche `user2`) non è `admin`, `alessio`, `pampa`, `root`, `administrator` e deve essere `test` o `test2`; le share ammesse sono l'**intersezione** fra quelle del file e l'elenco **scritto nel codice** (`TestShares`: `test`, `test2`, `test-comune`); `alessio`, `pampa`, `NAS`, `Public` sono rifiutate in ogni forma (NFKC, maiuscole). Un errore nel file **fa fallire** la prova (non la salta) e non allarga il recinto.
- **Ogni chiamata** passa dai decoratori della guardia (`GuardedReader`, `GuardedBackupOps`) che controllano la share **prima** del vero. I percorsi sono `SharePath` (niente `..`, niente assoluti) e le primitive vere girano **radicate** in `mcg-it/<giro>/r<N>/` (`NasBackupOpsFactory.createForTestArea`; anche `.mycloudgallery/`: temporanei del backup finiscono lì, non alla radice della share). Le destinazioni del backup sono `Backup/…` sotto quella radice (D6, D33).
- **L'autotest** (`BackupAreaSelfTest`) gira prima del primo giro: account e share vietati in ogni forma, su ogni primitiva (lettura, elenco, apertura, cartella, temporaneo, ripresa, pubblicazione, cancellazione del proprio temporaneo, copia da e verso, spazio) e **zero chiamate al vero** per ogni rifiuto (contatore).
- **Autenticazione**: la password si prova **una volta sola** all'apertura; se il login non riesce la prova si ferma. Un errore di autenticazione in qualunque punto della prova (`Auth`, `AuthBlocked`, `CredentialsMissing`) lancia `AreaAuthStop` e **ferma tutto subito**, senza nuovi tentativi (provato in `BackupAreaRunnerTest`: una sola richiesta arriva al NAS dopo il rifiuto).
- **La verifica dal manifesto** legge soltanto: il lettore è limitato alle cartelle del manifesto (`Backup/` sotto la destinazione e i temporanei della cartella dell'app) e non ha nessuna primitiva di scrittura.
- **Pulizia**: il banco non può eliminare media (le primitive del backup non lo permettono, per costruzione). La cartella `mcg-it/<giro>/` la toglie `NasIntegrationSuiteTest` di core-nas al giro dopo (`gradlew :core-nas:testDebugUnitTest -PnasIntegration`); `mcg-campo/Backup/` delle prove sul telefono la toglie l'utente dal PC.
- **Mondi diversi dal telefono**: il "telefono" del runner è `FakePhoneBytes` con contenuti veri (fino a 4 MiB) o **virtuali** generati da un seme (200 MB, 3 GB: non stanno in memoria, né sul PC); l'ora del NAS e le durate seguono il banco, non si fanno passare due giorni (niente pulizia dei propri file sul NAS vero: `NasCaps.housekeeping = false`).
- **Log**: senza nomi di file, percorsi, share, account, id né impronte (`redact` nel runner e `showNames = false` nel verificatore); i rapporti del NAS vero dicono invarianti, numeri e primitive, mai contenuti. Lo stato di un giro rosso non si salva sul NAS vero (conterrebbe percorsi e impronte).

## B9. Il telefono (`tools/chaos/s25.ps1 -Mode backup`)

Per la prova F30 (≥ 100 interruzioni): build **debug** con l'account di prova (D12, D13: il backup legge solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/` e scrive in `mcg-campo/Backup/` di `test`; lo script rifiuta un pacchetto che non finisca per `.debug`).

1. **`-Step seed`** (dal PC, telefono collegato): genera i file di `tools\fixtures` (formati misti: JPEG con GPS, HEIC, PNG, WebP, motion photo, screenshot, MP4, DNG…; `generate.ps1 -SkipBig`), aggiunge JPEG **unici** fino a `-Photos` (300; un JPEG dei fixture con in coda byte casuali dal seme), li spinge con `adb push` nelle due cartelle, fa `-BigVideos` (2) video da `-BigVideoMb` (2.560 MB) **sul telefono** (copia di un MP4 più `dd if=/dev/urandom`: niente da trasferire), chiede la scansione del MediaStore e scrive il **manifesto** da `adb shell find … -exec sha256sum` e `stat` (`build\chaos\backup-manifest-<giro>.txt`; i file a zero byte non entrano: il backup non li carica, E-M12). Le due cartelle sono scritte nello script, non vengono da nessun parametro.
2. **L'utente avvia il primo backup** nell'app ("Inizia ora").
3. **`-Step interrupt`**: a intervalli casuali (6-40 s) lo script uccide il processo (`run-as … kill -9`, poi `am crash`; si può scegliere `-KillMode`) o spegne il Wi-Fi per 3-20 s; dopo ogni morte rilancia l'app dopo 4 s (la ripresa parte come all'avvio vero). Alla fine aspetta `-SettleSeconds` (180; per i video grandi alzarlo) e, con `-Verify`, passa a `verify`. **Il calendario dipende solo dal seme**; `-DryRun` lo stampa insieme ai comandi `adb` e non esegue niente (nemmeno `adb`).
4. **`-Step verify`**: ricalcola le impronte di `mcg-prova` e le confronta con il manifesto (il telefono non deve essere cambiato: nella 5A il backup non scrive sul telefono), poi lancia `area.ps1 -Mode backup -Stage verify` in sola lettura.
5. **`-Step clean -Yes`**: toglie `mcg-prova` dal telefono (solo le due cartelle).

Garanzie dello script: tocca **un solo** dispositivo (`-Serial` o `ANDROID_SERIAL`; con `none` rifiuta, tranne a secco e con `-SelfTest`); il Wi-Fi viene **sempre** riacceso (anche se lo script è interrotto); non scrive sul NAS e non legge dal telefono altro che `mcg-prova`; il racconto resta in `build\chaos\s25-<data>.log` (solo orari, numeri e azioni). Lo script è provato **senza telefono** in tre modi: `s25.ps1 -SelfTest` (la conversione di un testo finto di `sha256sum` nel manifesto); `-DryRun` (il piano e i comandi, nessun `adb`); e **`tools\chaos\s25-finto.ps1`**, che lancia semina, verifica, interruzioni, un file del telefono che cambia (la verifica deve fallire con il codice 4), pulizia con e senza `-Yes` e un pacchetto non di debug (rifiutato) contro un **adb finto** (`tools\chaos\fake-adb\`: uno script che imita il telefono in una cartella temporanea; si attiva con la variabile `MCG_ADB`; `-SkipNas` evita il verificatore sul NAS). In più il formato del manifesto è letto da `BackupAreaRunnerTest.ilManifestoDelloScriptDelTelefonoSiLegge`, che parte dall'uscita dell'autotest. **Non provato**: la semina vera (`adb push`, `dd` sul telefono, la scansione `content call … scan_volume` del MediaStore, che su Android 11+ va vista all'opera), il `run-as kill -9` e il Wi-Fi.

**Limite**: sul telefono il diario locale vive nell'app. Dal PC il verificatore controlla ciò che il NAS può dire (invarianti 1, 2, 3, 4, 5, 6 e 11), non le voci del diario locale: quelle si guardano sul telefono (Diagnostica → Backup).

## B10. Che cosa non copre (e chi lo copre)

- **Guardiano, verifica sul momento, rotazione** (P3): `SafetyEveryCallTest` e `FattoQuando53Test`; vedi O3.
- **Il video da 3 GB virtuale con la ripresa** (E-B1, "al massimo 64 MiB ricaricati"): `BigFileResumeTest` (P2). Qui i file sono di decine di KB con i punti di ripresa ogni 32 KiB; il runner sul NAS vero usa i file veri (1 MB, 200 MB, `backup.bigFileMb`).
- **La preparazione e l'indice** (D10): il banco guida il motore senza `UploadIndexing` (un errore lì non ferma mai un caricamento): `UploadWiringTest` (P2).
- **L'adattatore Room** del diario (`RoomBackupStore`): `RoomBackupStoreTest` (P2); il banco usa il diario in memoria, che fa le stesse verifiche (transizioni, idempotenza). Il giro con Room richiede Robolectric.
- **Il catalogo vero** (P1) e le regole di `:app` (cancello, D12, congelamento): il banco usa `InMemoryBackupCatalog` e `FakeBackupGate`; l'integrazione è di P7.
- **Interferenza vera del PC a metà operazione** (file aperto, rinominato, cancellato): colpi `LOCKED` e `PC_FILE`; i casi per scenario sono di P2 (E-N13).
- **Il tempo del NAS vero**: sul NAS vero non si fanno passare due giorni; la pulizia dei propri file si prova sul NAS finto e sul simulatore SMB (E-B21), sul campo con F24.

## B11. Che cosa conta come superata

La prova del backup (F30) è **superata** quando, tutte insieme:
1. **JVM**: 2.000 giri su ciascuno dei due motori (riferimento e vero) sul NAS finto e 600 sul simulatore SMB, **zero violazioni** degli invarianti 1-11 e 15 (ex 12; `check.ps1` lo verifica a ogni giro). F1 è corretto: il banco non ha più difetti noti da dichiarare.
2. **Mutazioni**: `BackupChaosMutationTest` rosso con l'invariante giusto per tutte e quattro, su entrambi i motori, e verde per lo stack sano.
3. **PC, area di prova**: ≥ 400 interruzioni sul NAS vero, **zero giri rossi**, nessun errore di autenticazione, area ripulita dalla suite di core-nas; i tre file di rapporto (`backup-area-report-*.txt`, `-progress-*.txt`, `-log-*.txt`) in `core-backup/build/chaos/`.
4. **Telefono**: ≥ 100 interruzioni (`kill -9` e Wi-Fi) durante il primo backup di `mcg-prova`; a prova ferma il telefono non è cambiato e `area.ps1 -Mode backup -Stage verify` dice "TUTTI GLI INVARIANTI RISPETTATI" (nessun file a metà, nessun doppione, nessuna perdita, nessun temporaneo rimasto); in Diagnostica ogni elemento `DONE`, `LINKED_EXISTING` o `GONE`.
5. Un giro rosso **si rifà** dal suo seme e **si verifica da solo** dal suo stato salvato: non basta che sia rosso, deve essere spiegabile.

I risultati sul campo (NAS vero e telefono) si aggiungono qui sotto quando la prova è fatta.

## B12. Tempi

2.000 giri a motore sul NAS finto: ~6 secondi (circa 40.000 file riletti con SHA-256, ~1,1 GB). 600 giri a motore sul simulatore SMB: ~5 secondi. L'intero pacchetto di test del banco (banchi, mutazioni, verificatore, determinismo, runner dietro la guardia): ~40 secondi. Le cacce lunghe: 20.000 giri a motore ~1 minuto, 3.000 giri sul simulatore SMB ~25 secondi. Nessun test usa il tempo vero: le attese del motore (un file aperto dal PC) fanno avanzare un orologio finto, e nessun test dorme. Il runner contro il NAS vero dipende dai file grandi: i giri normali durano secondi, quelli con un video da 200 MB un paio di minuti, uno da 3 GB una ventina di minuti (scrittura, rilettura di verifica, rilettura finale).

---

# Parte C. La prova delle interruzioni della 5B (Fase 5B, P13)

| | |
|---|---|
| **Stato** | 19/10/2026 (P13). Il banco della 5A (Parte B) ora ha una **fase 5B** in ogni giro: dopo il caricamento semina qualche media e lavora su **versioni, scambi ("Ripristina originale"), guardiano e "Libera spazio" con la verifica sul momento**, con le stesse interruzioni (e altre nuove) e gli invarianti **12-14** del §5.4 più i 1-11. Il banco JVM gira a ogni `check.ps1` sul NAS finto con due stack: **2.000 giri ciascuno, 7.859 e 7.579 interruzioni in tutto (3.778 e 3.726 nella fase 5B), zero violazioni, ~41 s a stack**; sul **simulatore SMB** con le vere `SmbNasFileOps`/`SmbNasBackupOps` **600 giri ciascuno, 2.418 e 2.376 interruzioni (1.145 e 1.154 nella 5B), zero violazioni, ~25 s**. Quattro difetti nuovi iniettati apposta (§C7) rendono il banco rosso con l'invariante giusto. Ha trovato **tre difetti veri** dei servizi di P8 e P3 (F1, F2, F3, §C6), **corretti il 19/10** con i loro evitamenti tolti: **senza evitamenti** il banco JVM è verde (2.000 + 2.000 giri, 7.861 e 7.573 interruzioni, zero violazioni; altri 2.000 + 2.000 sul seme 777, 7.854 e 7.520, zero) e lo è il simulatore SMB (600 + 600, 2.419 e 2.374, zero); i tre giri che li avevano trovati ora sono verdi (§C6). Il runner contro l'area di prova del NAS vero gira anche **solo con la 5B** (`-Stage run5b`): **un giro breve (32 giri, 20 interruzioni, zero rossi) e poi l'unico giro lungo del P13: 506 giri, 401 interruzioni, zero giri rossi, nessun errore di autenticazione, 47 min 56 s** (§C8). Lo script del telefono ha il passo nuovo `-Step edit`, provato **solo contro l'adb finto**; **mai lanciato sul telefono** (F43). |
| **Dove** | `core-backup/src/test/kotlin/dev/pampa/mycloudgallery/core/backup/chaos/` (il banco: i file nuovi sono `BackupChaos5B*.kt`, più `ChaosKnownDefectsTest.kt`, `FiveBVerifierTest.kt`, `ChaosMediaCatalogTest.kt`), `tools/chaos/area.ps1`, `s25.ps1`, `s25-finto.ps1`, `fake-adb/`, questo file |
| **Riferimenti** | `docs/fase5-progetto.md` §2.4-§2.5, D17-D22, §5.4 (invarianti 12-14 e interruzioni), riga P13 del §4.4, F43. Parte B (sopra): il banco della 5A, di cui questa è un'estensione |

## C1. Che cosa promette

Versioni, scambi, guardiano e "Libera spazio" devono sopravvivere a interruzioni casuali **senza perdere una versione, senza lasciare vuoto il solito percorso e senza togliere dal telefono una foto che sul NAS non è al sicuro**. Valgono tutti i colpi della Parte B (connessione, sessione, socket, NAS pieno, file aperto dal PC, byte alterati, "Ferma", congelamento, **morte del processo a ogni chiamata e a ogni scrittura dei diari e del catalogo**); in più la 5B ha **punti nuovi** dove un colpo può cadere, prima e dopo:

| Primitiva (nuova) | Che cos'è | Colpi |
|---|---|---|
| `ARCHIVE_VERSION`, `RESTORE_VERSION` | le due rinomine con timbro di `NasBackupOps` (archiviare l'attuale in `Versioni/`, riportare un'altra versione al solito percorso); dietro la guardia e nel simulatore SMB sono le vere `SmbNasBackupOps` | i colpi del NAS **e** `PC_EDIT` (il PC modifica il file proprio prima della rinomina: il timbro non torna, `Collision`/`SOURCE_CHANGED`) e `PC_FILE` (un file al nome di destinazione) |
| `VERSION_CREATE/UPDATE`, `FREE_CREATE/UPDATE` | i diari dei lavori delle versioni e di "Libera spazio" | morte del processo |
| `CATALOG_RELOCATE`, `CATALOG_RESTORE_ORIGINAL`, `CATALOG_CORRECT`, `FREE_MARK_VERIFIED`, `FREE_FREED` | le scritture del catalogo di P8 e P9 | morte del processo |
| `PHONE_REWRITE` | la riscrittura di una riga del telefono per una correzione | morte del processo |
| `PHONE_DIALOG` | l'app viene **uccisa mentre il dialogo di sistema è aperto** (l'esito arriva a un processo che non c'è più) | morte (dopo) |

Tra un passo e l'altro il mondo cambia come sul telefono vero: Samsung "Salva" sopra una foto (prima o dopo che nessuno l'abbia letta), "Ripristina originale" di Samsung, il PC che modifica o crea un file sul NAS, un byte che marcisce con lo stesso timbro (E-B8), il tempo che passa (1-10 ore), il telefono che si riavvia, il MediaStore che si ricostruisce con gli `_ID` rinumerati, una foto cancellata, la share che si congela.

**Gli invarianti** (§5.4). Da 1 a 11 come nella Parte B, applicati anche a `Versioni/`; i nuovi:

| # | Invariante | Come si verifica |
|---|---|---|
| 12 | **L'attuale al solito percorso** | a lavoro finito ogni media con versioni ha al solito percorso i byte della versione attuale (lo SHA-256 del catalogo), salvo i byte che il PC ha messo di proposito; nessun solito percorso vuoto se la versione attuale è disponibile |
| 13 | **Versioni conservate** | a lavoro finito originale e intermedie sono **tutte** sul NAS, quelle non attuali in `Versioni/`, con lo SHA-256 che il catalogo conosce (una versione che c'era e non c'è più è una violazione) |
| 14 | **"Libera spazio" non sbaglia** | giudicato **al momento in cui un lotto esce verso il dialogo** (non dallo stato finale: dopo il dialogo il telefono e il NAS cambiano): ogni riga del lotto ha una copia privata riletta per intero nello stesso avvio (stesso `bootCount`), da meno di **6 ore monotone**, la cui verità sul NAS e sul telefono è ancora quella (stesso timbro, byte uguali alla versione, riga del telefono ancora quella); più la contabilità finale: nessuna riga tolta dal telefono senza traccia nel diario, nessun elemento "liberato" con la riga ancora sul telefono, nessun "rimasto" con la riga tolta, nessun elemento ancora nel dialogo a lavoro finito |
| 15 | Il codice non lancia | era il 12 nella Parte B (`Invariant.CONTRACT`): dalla 5B i numeri 12-14 sono quelli del §5.4 e il "contratto" è il 15 |

Anche qui i guasti sono **passeggeri** per costruzione (`FaultBudget`), e un lavoro che non arriva in fondo è l'invariante 9 (`CONVERGENCE`): dopo le riprese ogni lavoro delle versioni è `DONE` o `GONE`, ogni scambio è `DONE`, `GONE` o `FAILED` **per un motivo noto** (il PC ha cambiato il file, la destinazione era occupata), mai per `unexpected`.

## C2. I banchi

| Dove | Che cosa | Quante | Stato |
|---|---|---|---|
| **JVM, NAS finto** | `BackupChaosBenchTest`, ora con `BackupChaosConfig(fiveB = Backup5BConfig())`: il giro della 5A (invariato, stesso seme) e poi la fase 5B; due stack: `ReferenceStack` (l'esecutore di riferimento di P0 per il caricamento) e `ProductionStack` (`SafeUploadEngine` di P2). **I servizi della 5B sono sempre i veri** (`NasVersionEngine`, `DefaultVersionKeeper`, `DefaultCorrections`, `BackupPrivateCopyGuardian` di P3, `SafeFreeSpace` di P9): non esiste un motore di riferimento alternativo per la 5B (§C11) | 2.000 giri a stack, 7.859 / 7.579 interruzioni (3.778 / 3.726 nella 5B), ~41 s a stack. **Senza evitamenti (19/10, dopo F1-F3)**: 7.861 / 7.573 (3.780 / 3.720 nella 5B), ~38 s; seme 777: 7.854 / 7.520 (3.831 / 3.775) | **verde** su entrambi, anche senza evitamenti |
| **JVM, simulatore SMB** | `BackupChaosSmbBenchTest`: gli stessi giri con la sessione SMB di produzione e le **vere** `SmbNasFileOps`/`SmbNasBackupOps` (`archiveVersion` e `restoreVersion` comprese) sopra `InMemorySmbBackend`; il test pretende che `ARCHIVE_VERSION`, `RESTORE_VERSION` e `COPY_TO_TEMP` siano state davvero chiamate | 600 giri a stack, 2.418 / 2.376 interruzioni (1.145 / 1.154 nella 5B), ~25 s a stack. **Senza evitamenti (19/10)**: 2.419 / 2.374 (1.146 / 1.152 nella 5B), ~26 s | **verde** su entrambi, anche senza evitamenti |
| **PC, area di prova del NAS vero** | `BackupNasIntegrationTest` modo `run`, con `MCG_BACKUP_FIVEB=only` (`area.ps1 -Stage run5b`): i media li semina il banco sotto `mcg-it/<giro>/r<N>/` (1-1,5 MB), un colpo per passo, dietro la guardia | ≥ 400 interruzioni | **verde sul NAS vero** (19/10): 506 giri, 401 interruzioni, 0 rossi (§C8) |
| **Telefono, area di prova** | `s25.ps1 -Mode backup -Step edit` (§C9) | F43 | scritto; **solo contro l'adb finto** |

**I due stack e la fase 5B.** I servizi della 5B sono gli stessi veri su entrambi gli stack; per non fare due volte la stessa fase, lo stack di riferimento rimescola il seme della 5B (`BackupChaosStackFactory.fiveBSalt`) e il motore vero tiene quello di sempre (così il giro sul NAS vero, che usa solo lui, resta ripetibile): il banco JVM fa quindi 4.000 fasi 5B diverse, non 2.000 uguali.

**Copertura** (stack vero, NAS finto; chiamate, colpi prima / dopo, di cui morte): `ARCHIVE_VERSION` 2.729 (283 / 35; 29), `RESTORE_VERSION` 784 (36 / 16; 11), `VERSION_UPDATE` 27.452, `CATALOG_RELOCATE` 6.251, `CATALOG_RESTORE_ORIGINAL` 714, `CATALOG_CORRECT` 436 (4 / 3; 7), `FREE_MARK_VERIFIED` 5.487, `FREE_FREED` 954, `PHONE_REWRITE` 414, `PHONE_DIALOG` 270 morti; sul simulatore SMB (stack vero): `ARCHIVE_VERSION` 796 (92 / 11; 9), `RESTORE_VERSION` 227 (16 / 6; 6), `COPY_TO_TEMP` 203. «Libera spazio» (stack vero, NAS finto): 1.264 lotti controllati dall'invariante 14, 2.544 righe nei lotti, 1.677 tolte dal dialogo simulato; elementi `freed` 2.023, `kept` 494, `dropped` 566, `in_doubt` 35; lavori `version` 3.421 `DONE` e 418 `GONE`, `swap` 859 `DONE` e 62 `FAILED` (sempre per un motivo noto: il PC ha cambiato o occupato il file); guardiano 719 `DONE`.

## C3. Come si lancia

Dal PC, da `C:\VibeCoded Projects\MyCloud-Gallery-2`. Gli stessi comandi della Parte B (§B3) valgono: i banchi JVM (`-Stage jvm`, `-Stage smb`) ora **includono la 5B**. Novità:

```powershell
# La 5B sul NAS vero: solo la fase 5B (versioni, scambi, guardiano, "Libera spazio") sotto mcg-it/<giro>/
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run5b -Interruptions 20    # giro breve di prova (al massimo 20)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run5b -Interruptions 400 -TimeoutMinutes 120
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run5b -WithUpload ...      # prima il caricamento della 5A, poi la 5B nello stesso giro
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run5b -DryRun             # le variabili e il comando, senza lanciare

# Il verificatore da solo su uno stato salvato (backup5b-state-<giro>.txt, intestazione "# mcg-backup5b-state 1"): lo riconosce da sé
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage state -Manifest core-backup\build\chaos\backup5b-state-12.txt

# Il telefono: dopo il primo backup, modificare 20 foto come Samsung "Salva", rifare il backup, interrompere, verificare
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step edit -DryRun -Edits 20 -Seed 42
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step edit -Serial <numero> -Edits 20 -Manifest build\chaos\backup-manifest-<giro>.txt
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial <numero> -Interruptions 100 -Manifest build\chaos\backup-manifest-<giro>.txt -Verify -AllowFreed
```

**Variabili nuove** (le imposta `area.ps1`):

| Variabile | Significato |
|---|---|
| `MCG_BACKUP_FIVEB` | `off` (default: la 5A di prima), `with` (caricamento e poi 5B nello stesso giro), `only` (solo la 5B) |
| `MCG_BACKUP_CAP` | il **tetto** delle interruzioni del runner, anche se il file delle credenziali dice `backup.rounds=400`: `area.ps1` lo imposta a `-Interruptions` (un giro breve da 20 non diventa mai 400) |

Gli stati salvati dei giri rossi sono due, se serve: `backup-state-<giro>.txt` (5A, Parte B) e `backup5b-state-<giro>.txt` (5B). `-Stage state` guarda l'intestazione e usa il verificatore giusto.

## C4. Come funziona il giro 5B

La fase 5B (`Backup5BPhase`) gira **dopo** il caricamento e la sua verifica, con un seme suo (`seed xor 0x5B5B5B5B5B5B`): i colpi del caricamento restano quelli di prima, bit per bit (`BackupChaosDeterminismTest.lAggiuntaDellaCinqueBNonCambiaIColpiDelCaricamento`), e un giro rosso si rifà dallo stesso seme.

1. **Si semina**: da 3 a 5 media (20-70 KB sul NAS finto, 1-1,5 MB sul NAS vero) di cinque categorie (`FiveBCategory`): al sicuro (`SAFE_PHONE`: l'originale sul telefono e al solito percorso, riletto e verificato), già modificato (`EDITED_SAFE`: l'originale in `Versioni/`, l'attuale anche sul telefono al solito percorso), solo sul NAS (`SOLO_NAS`, E-E3: la correzione di un solo-NAS), con la sola copia comune (`COMMON_ONLY`: il caso del guardiano, D9) e solo sul telefono (`PHONE_ONLY`: mai liberabile, nessuna versione).
2. **Da 3 a 5 passi**, ognuno con i suoi colpi (sorteggiati per passo, 1-4 nel banco JVM, uno solo sul NAS vero): `EDIT` (modifica sul telefono, lettura, versione nuova sul NAS), `EDIT_TWICE` (due di seguito), `EDIT_WHILE_READ` (Samsung "Salva" **mentre la versione si legge**), `REVERT` ("Ripristina originale" di Samsung: il telefono torna a una versione nota, lo scambio), `RESTORE` ("Ripristina originale" dall'app), `STAGED` (editor esterno su un media solo-NAS), `CORRECT` (correzione di data/luogo/rotazione), `FREE` ("Libera spazio" intero: piano, guardiano, verifica sul momento, controllo del timbro, lotti, dialogo simulato) e `RESUME` (riprendere i lavori dal diario).
3. **Il dialogo di sistema** è simulato: conferma, conferma parziale, rifiuto, **app uccisa durante il dialogo** (l'esito resta `Unknown` finché la lettura del telefono non lo risolve: `settle()`).
4. **Tra un passo e l'altro** gli eventi del §C1; dopo ogni morte e a fine passo il verificatore guarda lo stato "a metà" (tutti gli invarianti tranne quelli che valgono solo a lavoro finito). **L'invariante 14 si giudica a ogni lotto** (`checkBatch`), prima che il lotto arrivi al dialogo.
5. **Fine fase**: tolti i guasti, i servizi si ricostruiscono sugli stessi diari e si **riprende finché non c'è più niente da fare** (al massimo 14 riprese, guardiano compreso: `guardian.ensure(GuardScope.All)`), poi la **rilettura completa senza cache** e gli invarianti a lavoro finito.

**La scansione** (`FiveBWorld.rescan`) riproduce quel che serve alle versioni: la riga del telefono ritrovata o sparita, il ritorno a una versione nota, il file del PC cambiato al solito percorso come versione nuova e, per un media che il PC ha toccato, una copia «presente» che sul NAS non c'è più dichiarata assente (D10). Gira a ogni passo, a ogni ripresa e **prima della verifica finale**: gli invarianti a lavoro finito valgono *dopo* la scansione, come nella vita vera.

**I difetti noti** (§C6) il banco li evita solo se sono in `Backup5BConfig.avoid` (`KnownDefect`). **Oggi nessuno**: F1, F2 e F3 sono stati corretti il 19/10 e i loro tre evitamenti tolti (prima: `phoneRebuild` non scattava con un lavoro dal telefono aperto, `CORRECT` non scattava con un elemento del guardiano aperto, una modifica del PC non conservava mai la dimensione). Ora il MediaStore si ricostruisce anche con un lavoro dal telefono aperto, una correzione parte anche con un elemento del guardiano aperto e **metà delle modifiche del PC conserva la dimensione** (stesso `fileId`, data nuova).

**Il catalogo del banco** (`ChaosMediaCatalog`) è uno solo per le tre viste di cui i servizi hanno bisogno (`VersionCatalog` di P8, `FreeSpaceCatalog` di P9, la parte del catalogo del guardiano di P3): avvolge `InMemoryVersionCatalog` di P8 e aggiunge le due cose che il catalogo vero fa e quello di P8 no: **una riga che Android ha tolto non è più presente** (la copia del telefono diventa `ABSENT`) e **una rilettura che non torna toglie la verifica** (E-B8: `markVerified` con uno SHA-256 diverso lascia la copia non verificata). Se il catalogo del banco mentisse darebbe falsi rossi o falsi verdi: `ChaosMediaCatalogTest` lo prova.

## C5. Il verificatore, anche da solo

`FiveBVerifier.check(state, fase, showNames)` giudica un `FiveBState` (fatti semplici: file del NAS con SHA-256 e `fileId`, media con le loro versioni e copie, lavori del diario delle versioni con gli stati attraversati, righe di "Libera spazio", note del banco). Come per la 5A lo stato si scrive in un file (`FiveBStateFile`, `# mcg-backup5b-state 1`) e si rilegge: un giro rosso lascia `core-backup/build/chaos/backup5b-state-<giro>.txt`, che si verifica **senza il banco, i servizi o il NAS** (§C3). `FiveBVerifierTest` prova il verificatore **invariante per invariante** con stati fatti a mano (un solo byte diverso lo rompe) e con uno stato salvato, riletto e manomesso.

Per l'**area di prova** e per il **telefono** (niente diario locale) `BackupAreaVerifier` ha due controlli nuovi, solo se il manifesto ha righe `edit=<sha vecchio>|<sha nuovo>|<etichetta>`: scansiona `Versioni/` **in sola lettura** (invarianti 1, 2 e 5 valgono anche lì) e a lavoro finito vuole, per ogni modifica, una sola copia al solito percorso (12) e l'originale in `Versioni/` **o** al solito percorso (13: "Ripristina originale" li scambia, va bene l'una o l'altra disposizione, ma sempre una sola volta ciascuna).

## C6. Difetti e osservazioni trovati

Il banco ha trovato **tre difetti veri** nei servizi di P8 e P3 (F1, F2, F3) e una **osservazione** che non è un difetto (O1). Il P13 non li ha corretti (il codice di produzione non si toccava); **sono stati corretti il 19/10** (decisioni.md, «Correzioni della prova 5B: F1, F2, F3»): la correzione di ciascuno è in fondo alla sua voce, gli evitamenti sono tolti e `ChaosKnownDefectsTest` ora prova il comportamento giusto (sul NAS finto e sul simulatore SMB) e che **i giri del banco che li trovavano sono verdi**. Fino alla correzione il banco **non li saltava in silenzio**: ogni difetto noto era una voce di `KnownDefect` e il banco lo evitava **solo** se la voce era in `Backup5BConfig.avoid` (il default, perché `check.ps1` doveva restare verde); `ChaosKnownDefectsTest` provava, per ciascuno, tre cose:
- il **comportamento di oggi** (verde): lo scenario riprodotto senza giri casuali. Quando diventa rosso il difetto è stato corretto;
- il **comportamento giusto**, lo stesso scenario con l'asserzione rovesciata, `@Ignore` con il motivo: si riaccende quando il difetto si corregge;
- **il banco lo trova quando non lo evita** (`avoid = emptySet()`): un giro preciso, rifatto dal suo seme (F1: seme 20261016 giro 116, invariante 9; F2: seme 20261016 giro 633, invariante 2; F3: seme 777 giro 1306, invariante 7). Con tutti gli evitamenti spenti, 2.000 giri su due semi trovano 10 giri rossi (4 + 4 di F1, 1 di F2, 1 di F3). **Dopo la correzione (19/10)** gli stessi 2.000 giri sugli stessi due semi, senza evitamenti, sono **tutti verdi** su entrambi gli stack, e i tre giri precisi sono test di regressione (`ilGiroCheTrovavaF1/F2/F3OraEVerde`).

**F1 (P8, `NasVersionEngine.refreshPhone`): un lavoro `VERSION` nato da una riga del telefono resta `PLANNED` per sempre se il MediaStore si ricostruisce e la foto cambia di nuovo prima che la si sia letta.**
- *Scenario*: (1) l'utente salva una modifica in Samsung Gallery; la scansione del NAS e del telefono pianifica un lavoro `VERSION` che parte dalla **riga del telefono** (`VersionSource.Phone`, con il suo `_ID`); (2) prima che il lavoro legga i byte, il MediaStore si ricostruisce (E-P12: `_ID` rinumerati, la riga vecchia sparisce dal catalogo); (3) l'utente modifica la foto un'altra volta, quindi la versione nuova ha un'altra riga e un altro timbro; (4) `refreshPhone` non ritrova la riga del lavoro nel catalogo e risponde `Step.Wait`: il lavoro non finisce e non diventa `gone`.
- *Conseguenze*: "Ripristina originale" dà `NOT_AVAILABLE` (c'è un lavoro aperto sul media) e "Libera spazio" esclude il media per sempre (c'è un lavoro aperto per una qualunque versione: D21). Nessun byte perso, nessun doppione: violato solo il 9 (il lavoro non arriva in fondo; con un video a metà, anche il 6: il temporaneo resta).
- *Rimedio probabile*: in `refreshPhone`, se il catalogo ha riletto il volume dopo il cambio di versione e non ha **nessuna** copia presente del telefono per quella versione, `gone(job)` (la stessa regola che P2 ha scelto per F1 della Parte B). *Evitamento nel banco*: `phoneRebuild` non scatta se c'è un lavoro aperto con `source is VersionSource.Phone`.
- **Corretto (19/10)**. `refreshPhone` guarda le righe **presenti** della versione nel catalogo: una con il timbro atteso e un'altra riga → il lavoro la segue (come prima); la riga di prima ancora presente (la lettura del telefono non ha visto il cambio del MediaStore) → aspetta, mai un `GONE` in anticipo; **altrimenti `GONE`** (`gone(job)` toglie prima il proprio temporaneo, se c'è). Il lavoro non resta mai appeso: se la versione è ancora sul telefono con un'altra riga o un altro timbro, il piano del giro dopo ne fa un lavoro nuovo. Test: `f1_ilLavoroDellaVersioneSuperata...` (lo scenario di P13), `f1_unVideoAMeta...` (temporaneo a metà tolto prima di `GONE`), `f1_conLaRigaDiPrima...` (nessun `GONE` in anticipo), `VersionSimulatorTest.f1_...` (simulatore SMB), `ilGiroCheTrovavaF1OraEVerde`. Evitamento tolto.

**F2 (P3 `BackupPrivateCopyGuardian.link`, innescato dalle versioni di P8): una copia che il guardiano ha già pubblicato e attaccato al catalogo, ma non ancora segnato `LINKED`, può diventare un doppione.**
- *Scenario*: il guardiano ha pubblicato la copia privata di un media con la sola copia comune e l'ha già attaccata al catalogo (verificata), ma il processo muore prima di scrivere `LINKED` nel diario; nel frattempo una versione nuova (una correzione di `DefaultCorrections`, o `planStaged`) sposta quella copia in `Versioni/` e pubblica la sua al solito nome; riprendendo, il guardiano trova altri byte al nome finale e rifà la copia: una **seconda copia privata** (`nome (2)`) della stessa versione, che aveva già la sua in `Versioni/`.
- *Conseguenze*: invariante 2 (due copie visibili degli stessi byte nell'area privata). Nessun byte perso.
- *Rimedio probabile*: riprendendo un elemento `PUBLISHED`, controllare nel catalogo se la versione ha già una copia privata verificata (anche in `Versioni/`) e in quel caso chiudere l'elemento (`LINKED_EXISTING`); oppure `DefaultCorrections`/`planStaged` non partono con un elemento del guardiano aperto per quella versione. *Evitamento nel banco*: `CORRECT` non scatta se c'è un elemento del guardiano aperto per il media (`guardBusy`).
- **Corretto (19/10), da due lati**. (1) **Le versioni** (`NasVersionEngine.archive`, P8): prima di spostare in `Versioni/` il file al solito percorso, se la **sua** versione ha un altro elemento aperto nel diario (`VersionJournal.busy`: il guardiano o il caricamento della 5A che l'hanno già attaccato al catalogo e non hanno ancora scritto `LINKED`), il lavoro aspetta che finisca. Vale per ogni strada che crea una versione (correzione, file preparato, Samsung «Salva» su un media del guardiano) e chiude anche la stessa finestra con un elemento della 5A. (2) **Il guardiano** (`BackupPrivateCopyGuardian.link`, P3): riprendendo un elemento `PUBLISHED` il cui nome finale è vuoto o ha altri byte, prima di rifare la copia cerca nell'area privata una copia con gli stessi byte (la deduplica D8, **riletta qui** per intero): se c'è (la sua copia spostata, seguita dal catalogo), la collega lì e chiude l'elemento, nessun doppione. Test: `f2_unaVersioneNuovaAspetta...` e `f2_ilGuardianoRitrova...` (NAS finto e simulatore SMB), `ilGiroCheTrovavaF2OraEVerde`. Evitamento tolto.

**F3 (P8, `NasVersionEngine.restore` e `archive` che ritrovano il loro file per identità): una modifica del PC della stessa dimensione, fra una rinomina la cui risposta è andata persa e la ripresa, è presa per il file del lavoro; la copia resta «verificata» con altri byte e nessuna scansione lo vede.**
- *Scenario* (`ChaosStaleScene.kt`, `staleWindow(keepSize = true)`): (1) un media con l'originale in `Versioni/` e la versione modificata al solito percorso; (2) Samsung «Ripristina originale»: il telefono torna all'originale, la lettura registra il ritorno e parte uno scambio; (3) il lavoro archivia l'attuale in `Versioni/`, poi `restoreVersion` porta l'originale al solito percorso e **il processo muore dopo la rinomina**, prima di `RESTORED`; (4) il **PC modifica sul posto il file appena riportato conservandone la dimensione** (un editor che riscrive i metadati, un byte cambiato: stesso `fileId`, data nuova); (5) il processo riparte: `restoreVersion` risponde `SourceMissing`, il lavoro guarda il solito percorso, `sameIdentity` (`fileId` e **dimensione**) dice «è il mio», `restored(job, src, st.stamp)` scrive nel catalogo **il timbro trovato**, cioè quello del file già modificato, e **tiene la verifica**; lo scambio finisce `DONE`.
- *Conseguenze*: (a) il catalogo dice l'originale «al sicuro» (copia verificata al solito percorso) con byte che non sono quelli della versione (invariante 7); (b) **la scansione del NAS non lo corregge mai**: il timbro del catalogo è già quello del file, quindi non vede nessuna modifica del PC (test `f3_oggi_loScambioPrende...`: il verdetto a lavoro finito, dopo la scansione, è ancora l'invariante 7); (c) «Libera spazio» **non ne risente**: la verifica sul momento rilegge i byte e li confronta con l'impronta della versione (E-B8), il media esce dal lotto (`f3_oggi_liberaSpazioLoFermaComunque...`); (d) **una versione nuova successiva archivia i byte del PC come se fossero l'originale**: `archiveVersion` riceve il timbro atteso e il timbro torna, quindi non può accorgersene; in `Versioni/` finisce un file chiamato «(originale)» che non lo è, e «Ripristina originale» lo riporterebbe (`f3_oggi_unaVersioneNuova...`). Nessun file è sostituito o perso dall'app: i byte originali li ha sovrascritti il PC.
- *Frequenza*: rara, serve una morte del processo (o una risposta persa) fra la rinomina e la ripresa **e** una modifica del PC sul posto, senza cambiare dimensione, nello stesso intervallo; il banco con l'evitamento spento l'ha trovata da solo in 2.000 giri su due semi (una volta, seme 777 giro 1306). Lo stesso schema vale per `archived(job, st.stamp)` (la rinomina di archiviazione).
- *Rimedio probabile*: dopo un ritrovamento per identità non scrivere nel catalogo il timbro trovato ma quello atteso (`src.stamp`): la scansione vedrà la differenza e registrerà la modifica del PC come versione nuova; oppure confrontare anche la data (`NasStamp.sameAs`); oppure rileggere l'impronta prima di dichiarare `RESTORED`/`ARCHIVED` quando il timbro non è quello atteso. *Evitamento nel banco*: `FiveBWorld.pcEdit` non conserva mai la dimensione (se il sorteggio la lascia uguale, un byte in più).
- **Corretto (19/10)**: le due proposte insieme, «confrontare anche la data» e «rileggere». Dopo una rinomina con la risposta persa (`archive` e `restore`, rami `SourceMissing` e `Collision`) il file a destinazione è quello del lavoro solo se ha la sua identità **e** il **timbro atteso** (dimensione, data e `fileId`: la rinomina non li cambia; sul simulatore SMB e sul NAS finto la data resta, come su Samba). Se torna solo l'identità (stesso `fileId` e stessa dimensione, un'altra data) vale **una rilettura completa fatta qui** con lo SHA-256 della versione: byte uguali → è il suo, con il timbro letto; diversi (o l'impronta della versione non nota) → non è il suo, e il lavoro fa quello che faceva già con una modifica di un'altra dimensione (O1): lo scambio finisce `FAILED`/`USUAL_PATH_TAKEN`, la versione aspetta con `SOURCE_CHANGED`, e **niente del catalogo segue il file**. Così un timbro trovato dopo un'interruzione non diventa mai «verificato» (F4-D8, L28): nessun file con un timbro diverso da quello atteso resta verificato senza una rilettura. Scartata la sola «scrivere il timbro atteso»: il catalogo avrebbe tenuto «verificata» la copia al solito percorso con altri byte fino alla scansione. Test: `f3_loScambioNonPrende...` (NAS finto e SMB), `f3_dopoLaScansione...` (il «giusto» di P13), `f3_liberaSpazioNonLibera...`, `f3_unaVersioneNuovaNonArchivia...`, `f3_unaRiscritturaConGliStessiByte...` (NAS finto e SMB: la rilettura lo riconosce), `f3_lArchiviazione...` (le due varianti per `archived`), `ilGiroCheTrovavaF3OraEVerde`. Evitamento tolto: ora **metà** delle modifiche del PC conserva la dimensione.

**O1. Il catalogo che ritarda dopo una modifica del PC di un'altra dimensione (giro 122, seme 20261016, stack di riferimento): non è un difetto.** Lo scenario è lo stesso di F3 con la modifica del PC **di un'altra dimensione** (`staleWindow(keepSize = false)`): `sameIdentity` non riconosce il file, il lavoro finisce `FAILED`/`USUAL_PATH_TAKEN` (nessun byte spostato dall'app oltre la rinomina già fatta) e il catalogo continua a dire che l'originale sta in `Versioni/`, dove non c'è più, **finché la scansione del NAS non lo dichiara assente** (D10). Il banco **non evita** questo caso: lo lascia accadere (l'evento «il PC modifica una copia» può cadere su un media con un lavoro aperto), e per la scansione usa il modello di quella vera: una copia presente che sul NAS non c'è più **si dichiara assente**, ma solo per i media che il PC ha toccato (`FiveBWorld.rescan`); per tutti gli altri il catalogo dev'essere già giusto da sé, e l'invariante 7 resta rigoroso. A lavoro finito (dopo la scansione) tutti gli invarianti valgono. Le quattro domande che questo ritardo pone, con i test che rispondono (`StaleCatalogAfterPcEditTest`):
1. *Un media segnato «al sicuro» con byte diversi?* **No.** Nessuna copia che il catalogo dice presente ha sul NAS byte diversi da quelli della sua versione (o il file c'è ed è giusto, o non c'è). Il segno «al sicuro» invece **è in ritardo**: il ritorno all'originale ha reso attuale l'originale, la cui copia «verificata» non c'è più, e `mediaSafety` risponde «safe» finché la scansione non passa; come per ogni cambiamento fatto dal PC fra due scansioni, è la natura del catalogo (D10). Dopo la scansione non è più «safe» (`domande1e2_...`).
2. *Una versione persa o sovrascritta?* **No, non dall'app.** I soli byte che mancano sono quelli dell'originale, che il PC ha sovrascritto nel file che ha modificato (e `lostShas` lo sa); il verificatore a metà non trova altro che l'invariante 7: 1, 2, 3, 12 e 13 sono puliti.
3. *«Libera spazio» toglie qualcosa dal telefono?* **No.** Il piano, che usa solo il catalogo locale (E-S11), vede ancora il media; la verifica sul momento rilegge ogni copia, non trova il file e il media non arriva a nessun lotto: la riga del telefono resta (`domanda3_...`).
4. *Uno scambio successivo che agisce su dati vecchi senza accorgersene?* **No.** Un altro giro di backup (che pianifica anche gli scambi dei ritorni) e una modifica nuova sul telefono nella stessa finestra **non producono nessun lavoro**: senza una copia al solito percorso nel catalogo `VersionPlanning.head` non ha da dove cominciare (il media non ha più un «solito percorso» finché la scansione non lo ritrova), quindi niente parte su dati vecchi. Il lavoro di prima resta `FAILED` con il suo motivo, nessun byte sparisce, il file del PC resta dov'è, il verificatore non trova altro che il ritardo (`domanda4_...`). Le primitive, comunque, vogliono il timbro atteso e a un file mancante o cambiato rispondono `SourceMissing`/`SourceChanged`. **Nel caso F3, con la stessa dimensione, la risposta alla 1 e alla 4 era sì** (il timbro atteso tornava, la copia sbagliata veniva archiviata come «originale»); **dalla correzione del 19/10 è no anche lì**: il lavoro non riconosce il file e lo scenario è questo stesso (`ChaosKnownDefectsTest.f3_...`).

**Limiti dichiarati (non difetti)**
- **Il bit che marcisce con lo stesso timbro dopo la verifica sul momento**: la verifica vale 6 ore e il controllo prima del dialogo guarda il **timbro** (dimensione e data), non rilegge una seconda volta: un bit cambiato senza cambiare il timbro (E-B8, L39) fra la rilettura e il dialogo non si vede. È la finestra dichiarata di D21. Il banco fa marcire un bit (`un byte marcisce sul NAS (stesso timbro)`) solo dove **nessun giro di «Libera spazio» ha già riletto una copia**: lì la verifica sul momento lo deve trovare (e il media esce) e il controllo finale lo vede nella rilettura completa; la finestra dopo la verifica non è giudicabile da nessun controllo e non è provata.
- **Il bit che marcisce non si fa sul NAS vero** (`hashCache = true`): la cache dei timbri nelle verifiche a metà non lo vedrebbe fino alla rilettura finale, che è sempre completa.
- **La scansione del NAS è un modello**: il banco non esegue lo scansionatore vero (core-index/core-database); riproduce quel che serve alle versioni (le modifiche del PC al solito percorso diventano versioni nuove, le copie sparite dopo un'azione del PC diventano assenti). Il resto del suo comportamento (`relocateNas` per `fileId` in caso di spostamento corroborato, i periodi di grazia di D10) è fuori dal banco.

## C7. Il banco ha i denti

`BackupChaosMutationTest` ha quattro difetti nuovi della 5B (`BackupChaos5BMutants.kt`), con lo stesso schema della Parte B (§B7): ogni difetto è uno stack rotto che avvolge le porte dei servizi sani e ne riproduce l'**effetto**, sopra i decoratori dei colpi. Il caricamento è quello sano; il banco gira con la sola 5B rotta. 500 giri ciascuno sul NAS finto, seme 4242:

| Errore introdotto | Come | Invariante che lo coglie | Giri rossi su 500 |
|---|---|---|---|
| **Archiviare senza il timbro** | `archiveVersion` passa il timbro che il NAS dice adesso, non quello del piano: un file cambiato dal PC dopo il piano viene archiviato come "l'attuale" | **7** (la copia del catalogo non ha i byte della sua versione) | 7 |
| **Sostituire invece di rinominare** | davanti a un nome occupato, pubblicazione, scambio e archiviazione tolgono il file che c'è e mettono il loro | **3** (sostituzione), 6, 7, 9, 12, 13 | 49 |
| **Liberare con la verifica scaduta** | l'orologio monotono e il conteggio dei riavvii di "Libera spazio" non avanzano: una verifica vecchia di ore vale come nuova | **14** ("verifica scaduta" nel dettaglio) | 24 |
| **Uno scambio che lascia vuoto il solito percorso** | "Ripristina originale" archivia l'attuale e poi dice di aver riportato l'originale senza riportarlo | **12** (e 7) | 203 |

Come nella Parte B, lo stack sano sugli stessi giri resta **verde** (`lo_stackSanoDellaCinqueBNonFaScattareNiente`) e i quattro difetti della 5A (§B7) restano rossi con gli stessi invarianti: un rosso è colpa del difetto, non del banco. Oggi, riferimento / vero su 500 giri: diario dopo la chiamata 41 / 31 (invariante 6), pubblica prima 36 / 27 (1), salta la rilettura 41 / 32 (1 e 4), pulizia che toglie il temporaneo 22 / 22 (6). I numeri del §B7 sono quelli del 16/10, prima delle revisioni della 5A.

**Dopo le correzioni di F1-F3 (19/10)** le otto mutazioni restano rosse con gli **stessi numeri** (5B: 7, 49, 24, 203 giri rossi; 5A: 41 / 31, 36 / 27, 41 / 32, 22 / 22) e lo stack sano resta verde.

## C8. L'area di prova sul NAS vero

Valgono la guardia, il recinto e l'autotest della Parte B (§B8): `test`, `test2`, `test-comune`, tutto sotto `mcg-it/<giro>/r<N>/`, mai `NAS`, `Public`, `alessio`, `pampa`; il login si prova **una volta sola** e un `AUTH_FAILED` ferma tutto (`AreaAuthStop`). Cambia questo:

- **Le primitive nuove passano dalla guardia**: `GuardedBackupOps.archiveVersion/restoreVersion` controllano la share prima del vero; l'autotest ora le prova su account e share vietati, **zero chiamate al vero** per ogni rifiuto. `GuardedNasSide.pcModify` (il PC che modifica un file) controlla la share come tutto il resto.
- **Il runner `run5b`** non carica niente (`only`): semina 3-4 media da 1-1,5 MB sotto `mcg-it/<giro>/r<N>/`, fa 3-4 passi con **un colpo per passo** (`FaultMix.SINGLE`, `stepFaults = 1`), con gli eventi che il NAS vero può fare (non l'orologio che avanza di ore né il bit che marcisce: `NasCaps`), la cache dei timbri nelle verifiche a metà e la rilettura finale **sempre completa**.
- **Il tetto delle interruzioni** `MCG_BACKUP_CAP`: `area.ps1` lo imposta a `-Interruptions`; il runner si ferma appena le interruzioni cadute sono quelle (al massimo `4 x + 5` giri). Il file delle credenziali può avere `backup.rounds=400` senza che un giro da 20 diventi di 400.
- **Sola lettura di `Versioni/`**: il lettore del verificatore dal manifesto, se ha righe `edit=`, include la radice `Versioni/` (mai in scrittura).
- **Pulizia**: come la Parte B: `mcg-it/<giro>/` la toglie `NasIntegrationSuiteTest` di core-nas (`gradlew :core-nas:testDebugUnitTest -PnasIntegration`). Il banco non elimina media: le versioni non si cancellano (D17).

**Risultati sul NAS vero (19/10/2026, `area.ps1 -Mode backup -Stage run5b`)**: due lanci, il giro breve e **uno solo** da 400; login riuscito alla prima, nessun `AUTH_FAILED`. Rapporti in `core-backup/build/chaos/backup-area-{report,progress,log}-20261019-181432.txt` (20) e `-181746.txt` (400).

| | Giro breve | Giro da 400 |
|---|---|---|
| Giri, giri con violazioni | 32, **0** | 506, **0** |
| Interruzioni cadute (prima / dopo la chiamata) | 20 (14 / 6) | **401** (194 / 207) |
| Durata | 155 s | 2.868 s (47 min 56 s; ~5,7 s e ~0,8 interruzioni a giro) |
| File riletti con SHA-256 | 432 (365 MiB) | 6.721 (5,6 GiB) |
| Passi (`FREE` / `EDIT` / `RESTORE` / `CORRECT` / `EDIT_WHILE_READ` / `RESUME` / `EDIT_TWICE` / `REVERT` / `STAGED`) | 115 | 1.766 (807 / 314 / 179 / 105 / 100 / 80 / 76 / 75 / 30) |

*I colpi* (400): morte del processo 286 (71%), connessione caduta 63, lettura del telefono fallita 16, socket fermo 9, byte alterati 8, sessione scaduta 7, NAS pieno 7, file aperto dal PC 5 (il mix del NAS vero, `FaultMix.SINGLE`, non fa password rifiutate, «Ferma», congelamenti né file del PC, come per la 5A). *Cosa hanno fatto i servizi*: lavori di versione 686 `DONE` e 105 `GONE` (la foto è sparita prima), scambi 206 `DONE` (nessuno `FAILED`), guardiano 141 `DONE`; «Libera spazio» 321 lotti controllati (680 righe), 422 righe tolte dal dialogo simulato, 534 elementi `freed`, 134 `kept`, 52 `dropped`, 12 `in_doubt`, 26 dialoghi rifiutati, 79 volte l'app uccisa durante il dialogo; 1.787 media seminati.

*Copertura per primitiva* (chiamate; colpi prima / dopo; di cui morte): `ARCHIVE_VERSION` 796 (8 / 9; 4), `RESTORE_VERSION` 198 (5 / 2; 1), `VERSION_UPDATE` 6.696 (39 / 23; 62), `VERSION_CREATE` 915 (5 / 2; 7), `CATALOG_RELOCATE` 1.827 (5 / 14; 19), `CATALOG_RESTORE_ORIGINAL` 182 (1 / 1; 2), `CATALOG_CORRECT` 100 (0 / 1; 1), `FREE_CREATE` 800 (3 / 3; 6), `FREE_UPDATE` 2.290 (7 / 7; 14), `FREE_MARK_VERIFIED` 1.387 (5 / 5; 10), `FREE_FREED` 240 (1 / 0; 1), `PHONE_DIALOG` (79 morti), `COPY_TO_TEMP` 127 (1 / 0), più le primitive del NAS (`OPEN_TEMP`, `WRITE_TEMP`, `FINISH_TEMP`, `PUBLISH_TEMP`, `STAT`, `OPEN`, `READ_ALL`, ...). **Non colpite sul NAS vero** in questo lancio: `READ`, `DELETE_OWN_TEMP`, `PHONE_REWRITE` (110 chiamate, nessun colpo). Con circa un colpo a giro alcune primitive rare non vengono colpite: lo sono centinaia di volte nel banco JVM (2.000 giri a stack, §C2) e nel simulatore SMB.

*Versione del banco*: i due lanci sono precedenti alle ultime modifiche del banco JVM (seme della 5B per stack, `Backup5BConfig.avoid`, la scansione che dichiara assente le copie sparite dopo un'azione del PC). Non cambiano il giro sul NAS vero: lì il PC non modifica file (`NasCaps.pcModify` falso) e il motore vero tiene il seme di prima.

*Osservazioni sul NAS vero (non violazioni)*
- **`NasSession chiusura lenta: 60000 ms`** compare 101 volte nel log del giro da 400 (3 nel breve): la chiusura della sessione SMB, dopo la morte simulata del processo o una connessione caduta, aspetta il limite di 60 s (`CLOSE_WAIT_MS`, `SmbNasSessionManager`). Succede su un thread a parte e **non rallenta il giro** (101 x 60 s > 48 min) né viola un invariante; la causa non è stata indagata (è codice della 4B).
- **`BackupVersions verifica non tornata, tentativo 1 di 3`**: 8 volte, quante sono le scritture con byte alterati (E-B18): la verifica rilegge, vede i byte sbagliati e rifà. Come previsto.
- **Area di prova non pulita**: restano `mcg-it/20261019-181432/` e `mcg-it/20261019-181746/` nelle share di prova (`test`, `test2`, `test-comune`). Non le ho tolte: la pulizia è la suite d'integrazione di core-nas (`gradlew :core-nas:testDebugUnitTest -PnasIntegration`), che al giro dopo le toglie e che qui non è stata lanciata.

## C9. Il telefono (`tools/chaos/s25.ps1 -Mode backup -Step edit`)

Per la prova F43. Si aggiunge un passo alla sequenza della Parte B (§B9) e un'opzione alla verifica:

1. `-Step seed` e il primo backup come prima (manifesto con le impronte delle foto).
2. **`-Step edit -Edits 20`**: a primo backup finito, modifica 20 foto di `mcg-prova` come farebbe Samsung "Salva" (24 byte casuali in coda con `dd`: un JPEG li ignora, l'immagine non cambia, lo SHA-256 sì) e **aggiunge al manifesto** una riga `edit=<sha256 vecchio>|<sha256 nuovo>|<etichetta>` per ciascuna. `-DryRun` stampa il piano e i comandi, senza `adb`.
3. Nell'app: **Fai ora** (la lettura del telefono vede 20 versioni nuove, il backup le carica con le versioni di D17); poi `-Step interrupt` come prima (`kill -9` e Wi-Fi) e `-Verify`.
4. **`-Step verify`** (e `interrupt -Verify`): le impronte del telefono tornano per `sha|etichetta` con le **modifiche ammesse**; con **`-AllowFreed`** i file che "Libera spazio" ha tolto dal telefono non sono un cambiamento. Poi il verificatore sul NAS controlla gli invarianti 12 e 13 per ogni modifica (§C5).

`s25-finto.ps1` ha un stadio nuovo (3b) che prova tutto contro l'adb finto: la modifica, la verifica con le modifiche, un file tolto con e senza `-AllowFreed` (con: verde; senza: rosso). `fake-adb/fake-adb.ps1` ora capisce `dd bs=N count=M`. **Non provato**: la modifica vera con `dd` sul telefono e il flusso con "Libera spazio" e il dialogo di sistema (che nel banco è simulato).

## C10. Che cosa non copre (e chi lo copre)

- **Il dialogo di sistema vero** (`createTrashRequest`/`createDeleteRequest`), le sue risposte e la reale cancellazione di una riga del MediaStore: nel banco è simulato (conferma, parziale, rifiuto, app uccisa). Si prova sul telefono (F43, F34-F42).
- **Correzioni scritte nel file** (EXIF, rotazione): il banco prova il flusso (`CATALOG_CORRECT`, `PHONE_REWRITE`, la versione che ne nasce) ma non decodifica immagini; le correzioni JPEG/PNG/WebP sono di P8 (`corrections/**`).
- **Il guardiano con il tetto dei 20 GB** e i file grandi (200 MB, 3 GB) nella 5B: sulla 5B i file sono di decine di KB (NAS finto) o 1-1,5 MB (NAS vero); i grandi sono del caricamento (Parte B, `BigFileResumeTest`).
- **La riscrittura di una riga con Room**: il banco usa i diari e il catalogo in memoria (che fanno le stesse verifiche di transizione); `RoomFreeSpaceDiary` ha i suoi test (P9).
- **Il tempo del NAS vero**: sul NAS vero non passano ore; le 6 ore di validità, il riavvio e i salti di tempo si provano su NAS finto e simulatore SMB, e sul campo con F43.

## C11. Che cosa conta come superata

La prova della 5B (F43) è **superata** quando, tutte insieme:
1. **JVM**: 2.000 giri su ciascuno dei due stack (riferimento e vero) sul NAS finto e 600 sul simulatore SMB, **zero violazioni** degli invarianti 1-15 (`check.ps1` lo verifica a ogni giro). **Nota sullo stack di riferimento**: per la 5B non esiste un "esecutore di riferimento" alternativo (non è stato scritto in P0-B: i servizi di P8 e P9 sono l'unica implementazione), quindi «riferimento» e «vero» differiscono per il **caricamento della 5A** e per il seme della fase 5B (§C2); i servizi della 5B sono gli stessi veri su entrambi. È una scelta dichiarata del P13 (decisioni.md, «Fase 5B: P13»).
2. **Mutazioni**: i quattro difetti della 5A e i quattro della 5B rossi con l'invariante giusto; lo stack sano verde.
3. **Difetti noti**: ogni difetto trovato e non corretto ha il suo test del comportamento di oggi (verde), quello del comportamento giusto (`@Ignore` con il motivo) e la prova che il banco lo trova quando non lo evita (§C6). Dal 19/10 non ce n'è nessuno: F1, F2 e F3 sono corretti e il banco gira senza evitamenti.
4. **PC, area di prova**: ≥ 400 interruzioni sul NAS vero (solo 5B: versioni e scambi), **zero giri rossi**, nessun errore di autenticazione, area ripulita dalla suite di core-nas; i tre file di rapporto in `core-backup/build/chaos/`.
5. **Telefono**: `-Step edit` con 20 modifiche, poi interruzioni durante il backup, "Ripristina originale" e "Libera spazio" con `kill -9` e Wi-Fi spento a metà; `verify -AllowFreed` e il verificatore sul NAS "TUTTI GLI INVARIANTI RISPETTATI"; in Diagnostica nessun elemento fermo.
6. Un giro rosso **si rifà** dal suo seme e **si verifica da solo** dal suo stato salvato.

## C12. Tempi

2.000 giri a stack sul NAS finto con la 5B: ~41 s a stack (~235.000 file riletti con SHA-256, ~8,5 GiB). 600 giri a stack sul simulatore SMB: ~25 s. L'intero modulo `core-backup` (511 test, 6 saltati: 3 del NAS vero e 3 `@Ignore` dei difetti noti): ~4 minuti con `--max-workers=2`; dopo le correzioni di F1-F3 (19/10) 520 test, 3 saltati (solo quelli del NAS vero), ~4 minuti. Il runner `run5b` sul NAS vero: ~5,7 s per giro e ~0,8 interruzioni per giro, quindi **400 interruzioni sono ~500 giri e ~48 minuti** (il giro da 20 interruzioni: 32 giri, 155 s). Nessun test usa il tempo vero: le ore della 5B fanno avanzare un orologio finto.
