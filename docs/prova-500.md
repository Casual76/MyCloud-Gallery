# La prova delle 500 interruzioni (Fase 4B, P15)

> **Parte A**: le operazioni sui file del NAS (Fase 4B, P15), qui sotto. **Parte B**: il caricamento del **backup** (Fase 5A, P6), più sotto (da "Parte B"). **Parte C**: versioni, scambi, guardiano e "Libera spazio" (Fase 5B, P13), in fondo a questo file (da "Parte C"). **Parte E**: la **condivisione** (spostamento nell'area comune, Fase 7, P5), in fondo a questo file (da "Parte E"). **Parte F**: il **calcolo dei volti** e "Disattiva e cancella" (Fase 8, P12), in fondo a questo file (da "Parte F").

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

Per l'**area di prova** e per il **telefono** (niente diario locale) `BackupAreaVerifier` ha due controlli nuovi, solo se il manifesto ha righe `edit=<sha vecchio>|<sha nuovo>|<etichetta>`: scansiona `Versioni/` **in sola lettura** (invarianti 1, 2 e 5 valgono anche lì) e a lavoro finito vuole, per ogni modifica, una sola copia al solito percorso (12) e l'originale in `Versioni/` **o** al solito percorso (13: "Ripristina originale" li scambia, va bene l'una o l'altra disposizione, ma sempre una sola volta ciascuna). Dalle prove sul campo (D-14) le righe `edit=` di un file modificato più volte sono una catena (la vecchia impronta di una è la nuova della precedente) e si giudicano una volta per file; un file che c'era prima del backup (`pre=`) e che una modifica ha aggiornato al suo percorso non è una sostituzione (3) solo se i suoi byte di prima stanno ancora in `Versioni/`.

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

---

# Parte D. La prova delle interruzioni dello scaricamento e del calcolo AI (Fase 6, P10)

Il banco della Fase 6 (`docs/fase6-progetto.md` §5.5, riga P10 del §4.4). Stesso impianto delle Parti A-C: un mondo, un piano di guasti deciso dal seme, un verificatore, un banco che somma i giri, `Mutation` che rompe il codice sotto prova, e un modo `ai` di `s25.ps1` per il telefono. Il codice di produzione non si tocca: dove un invariante non regge, il banco lo dice con il seme (§D6).

## D1. Che cosa promette

I 10 invarianti del §5.5, controllati **dopo ogni morte del processo, a fine di ogni passo, a fine giro e in una rilettura da un processo nuovo**. Il numero è quello del §5.5; due moduli, perché `core-ai` non vede `core-nas` e `core-models` non vede `core-index`:

| N. | Invariante | Dove si controlla |
|---|---|---|
| 1 | nessun modello non verificato caricato | `core-models` (`ModelFiles.open` consegna solo file con dimensione e SHA-256 del catalogo, sul suo descrittore); `core-ai` (il motore delle immagini non gira senza un caricamento verificato di quella vita) |
| 2 | nessun parziale promosso | `core-models`: in `models/` solo file con dimensione e SHA-256 del catalogo, dopo ogni morte e ogni passo |
| 3 | ripresa vera | `core-models`: ogni richiesta (HTTPS con `Range`, lettura del NAS) riparte dal confine di blocco sotto il parziale buono; i byte salvati superano la dimensione di un blocco per interruzione (più una dimensione per ogni ripartenza imposta: `200`, impronta sbagliata, file manomesso) |
| 4 | nessun calcolo perso | `core-ai`: un media salvato non si salva due volte; un processo nuovo a lavoro finito non ricalcola niente |
| 5 | dati coerenti | `core-ai`: ogni riga dell'indice locale ha il suo record nel file con il CRC giusto; ogni voce pubblicata ha la sua riga (e si mette in fila e si pubblica solo dopo averla salvata); ogni voce in attesa ha il corpo con cui è nata |
| 6 | nessuna voce fuori posto | `core-ai`: M3 (nessuna voce per contenuti di un'altra share), nessuna area privata altrui toccata; `core-models`: sul NAS solo `devices/<io>/models/<sha256>` interi, oltre ai file messi dal banco |
| 7 | prenotazioni | `core-ai`: mai oltre un'ora, mai la stessa unità a due dispositivi insieme |
| 8 | il lavoro arriva in fondo | `core-ai`: a rete sana ogni contenuto in ambito ha `vec` e `ocr` o è "non supportato" (e solo le anteprime davvero rotte lo sono); `core-models`: il modello è pronto e la copia sul NAS pubblicata |
| 9 | buffer e temporanei | `core-ai`: buffer sotto il tetto, nessun `*.tmp` orfano, nulla di già fatto rimasto (difetto AI-D1, §D6); `core-models`: niente in `partial/`, niente temporanei sul NAS |
| 10 | nascosti | `core-ai`: i nascosti si calcolano (E-H1) e una ricerca di riferimento a fine giro non ne mostra nessuno (limite al §D8) |

## D2. I banchi

| Dove | Cosa | Giri |
|---|---|---|
| `core-models/src/test/.../chaos/` | `ModelChaosBenchTest`: il gestore vero (`LocalModels`) con `NasModelFetcher` e `HttpsModelFetcher` veri, il server HTTPS di prova del JDK (certificato di prova fidato solo dal banco), il NAS finto con `FaultPlan` sulle letture | **2.000** |
| `core-models/src/test/.../chaos/` | lo stesso con il **simulatore SMB**: sessione di produzione (`SmbNasSessionManager`), lettura vera (`SmbNasMediaReader`, blocchi da 1 MiB), copia propria scritta con il vero `SmbNasFileSystem.publishExclusive` | **600** |
| `core-ai/src/test/.../chaos/` | `AiChaosBenchTest`: il vero `AiComputeEngine` sul NAS finto di P5 (`SimNas`, unità con prenotazioni), motori finti, il vero `DiskAiBuffer`, il vero `LocalModels` per il modello, un indice locale con file dei vettori e CRC | **2.000** |
| `core-models/.../chaos/ModelChaosMutationTest`, `core-ai/.../chaos/AiChaosMutationTest` | i quattro errori introdotti apposta (§D5) | 400 giri per difetto, si ferma al primo rosso |
| `core-ai/.../chaos/AiChaosKnownDefectsTest` | la riproduzione minima dei difetti noti (§D6) | 2 test |
| `tools/chaos/s25.ps1 -Mode ai` | il telefono (§D8) | ≥ 100 interruzioni |

File: `ModelChaosTypes` (invarianti e piano del giro), `ModelChaosNas`/`ModelChaosSmb` (le due facce del NAS), `ModelChaosWorld`, `ModelChaosVerifier`, `ModelChaosRound`, `ModelChaosBench`, `ModelChaosMutants` e le prove; in `core-ai` `AiChaosTypes`, `AiChaosIndex` (indice locale), `AiChaosPorts` (guasti e pubblicatore con la cronaca), `AiChaosModel`, `AiChaosWorld`, `AiChaosVerifier`, `AiChaosRound`, `AiChaosBench`, `AiChaosMutants` e le prove.

## D3. Come si lancia

```powershell
# Tutto sulla JVM, due moduli, --max-workers=2, DOPO aver aspettato 2 GB di RAM libera (il PC e' condiviso):
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode ai -Stage jvm        # 2.000 + 2.000 giri
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode ai -Stage smb        # 600 giri sul simulatore SMB
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode ai -Stage mutants    # i quattro errori introdotti apposta
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode ai -Stage known      # i difetti noti (rossi quando si correggono)
# Rifare UN giro rosso (il rapporto dice "indice 163, ... seme di base 6103"): stesso seme di base, un solo giro, a partire da quell'indice
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode ai -Stage jvm -Rounds 1 -Seed 6103 -FirstRound 163
# Il telefono: vedi §D8. Il piano a secco e l'autotest non vogliono ne' telefono ne' adb:
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -DryRun -Interruptions 100
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -SelfTest
powershell -ExecutionPolicy Bypass -File tools\chaos\s25-ai-finto.ps1
```

Variabili che i test leggono (come `MCG_BACKUP_CHAOS_*`): `MCG_MODELS_CHAOS_ROUNDS` (2.000), `MCG_MODELS_CHAOS_SMB_ROUNDS` (600), `MCG_AI_CHAOS_ROUNDS` (2.000), `MCG_MODELS_CHAOS_SEED` (6101 sulla JVM, 6102 sull'SMB), `MCG_AI_CHAOS_SEED` (6103), `MCG_MODELS_CHAOS_FIRST_ROUND` e `MCG_AI_CHAOS_FIRST_ROUND`. I rapporti vanno in `core-models/build/chaos/` (`modelli-jvm.txt`, `modelli-smb.txt`) e `core-ai/build/chaos/` (`calcolo-jvm.txt`). Con `gradlew :core-models:testDebugUnitTest :core-ai:testDebugUnitTest` i giri sono quelli di default.

## D4. Come funziona un giro

**Scaricamento** (`ModelChaosRound`). Un modello di prova (da 2 a 4 blocchi da 1 MiB, byte dal seme), una cartella `models/` vera su disco e le "vite" del processo una dopo l'altra, ognuna con un `LocalModels` nuovo sulla stessa cartella. Il piano decide, dal seme:
- la **morte** dopo il blocco salvato *n* (il traffico si registra dopo la scrittura sincronizzata: li' muore), prima o dopo la chiamata *n* a un fetcher;
- il **server HTTPS** che risponde `200` anche con `Range`, che taglia il corpo a *k* byte, che cambia il validatore (`If-Range` non torna) o serve un file con un byte cambiato;
- il **NAS**: letture che cadono prima o dopo la risposta (`FaultPlan`), la copia che **cambia a meta'** (byte diversi, stessa lunghezza), la cartella dal PC;
- il file pronto **manomesso** (un byte), poco spazio, il cancello di rete chiuso una volta, la copia sul NAS che fallisce la prima volta.

Dopo ogni morte e ogni passo: invarianti 2 e 3 sulla cartella e sulle richieste; un caricamento per l'1. A fine giro la rete torna, "Scarica ora" porta in fondo, si pubblica la copia sul NAS e si controllano 8, 9, 6 e il tetto dei byte (3). Poi un processo nuovo rilegge tutto (`refresh`, caricamento, verifica).

**Calcolo** (`AiChaosRound`). Una famiglia sul NAS finto di P5 (aree private di Anna e Bruno, area comune, video, anteprime rotte, nascosti, lo stesso contenuto in area privata e comune, media del solo telefono), il telefono di Anna con il vero motore e quello di Bruno che lavora sulle stesse unita'. Il piano fa:
- **morire il processo dentro ogni salvataggio**: prima del file, a meta' del record (CRC sbagliato, nessuna riga), dopo il record e prima della riga, dopo la riga (P6, "Ordine delle scritture");
- cadere la **rete** alla chiamata *n* del NAS (unita', anteprime, pubblicazione, "gia' fatto"), prima o dopo l'effetto, con sessione scaduta e stallo;
- far prendere le unita' da un **altro dispositivo** a meta' (l'ora del NAS salta oltre l'ora della prenotazione e l'altro lavora), **chiudere e riaprire** la pubblicazione (F4-D31: voci in attesa, poi ripubblicate senza ricalcolo);
- **uccidere e manomettere il modello** (`LocalModels` vero con un fetcher a pezzi), una copia sul NAS con un byte cambiato, un temporaneo lasciato nel buffer dalla morte.

## D5. Il banco ha i denti

Quattro errori, ognuno scritto come uno stack rotto che avvolge le porte del codice sano e ne riproduce l'effetto (come B7 e C7): il codice di produzione non si tocca.

| Errore | Dove | Invariante che diventa rosso | Giro in cui lo vede (400 al massimo, seme 4242) |
|---|---|---|---|
| caricare prima di verificare (`open` consegna `models/<sha>` senza ricalcolare) | `ModelMutants.loadBeforeVerify` | **1** (dimensione e impronta del file consegnato non tornano) | 4 |
| accettare un parziale (un `finally` lo promuove quando il passo finisce senza modello o il processo muore) | `ModelMutants.acceptPartial` | **2** (in `models/` un file che non e' del catalogo) | 10 |
| pubblicare prima di salvare (la voce si mette in fila e si pubblica prima di scrivere la riga) | `AiMutants.publishBeforeSave` | **5** (voce messa in fila e pubblicata senza la sua riga locale) | 1 |
| salvare dopo aver pubblicato (le righe si scrivono a fine unita', dopo la pubblicazione) | `AiMutants.saveAfterPublish` | **5** (idem) | 1 |

Lo stack sano con gli stessi semi resta verde (150 giri per lo scaricamento, 200 per il calcolo), cosi' il rosso viene dal difetto. Gli ultimi due errori hanno lo stesso invariante perché la regola e' una sola ("si salva, poi si mette in fila, poi si pubblica"): il banco la controlla nel momento in cui la voce entra in fila e in cui esce, non solo dopo una morte.

## D6. Difetti trovati nel codice di produzione

**AI-D1 (invariante 9, "buffer"): una morte fra il salvataggio di un media e lo svuotamento di fine tappa lascia le sue anteprime nel buffer per sempre.** `ComputeSession` svuota il buffer con `evict(tally.finished)` a fine unita' e a fine tappa dei media del telefono; `tally.finished` contiene solo i media che QUELLA tappa ha toccato. Se il processo muore dopo il salvataggio di un media e prima dello svuotamento, il processo nuovo non lo ritocca: un media salvato non e' piu' un candidato (`phoneOnly`) e l'unita' di un media gia' fatto non si prende piu' (`WorkUnits.next` salta le unita' senza lavoro). `DiskAiBuffer` all'apertura toglie i temporanei e i nomi estranei, ma non i blob di lavoro finito. Effetto: ogni morte lascia fino a un'unita' (200 media, ~30 MB) di anteprime nel buffer, che pesa sul tetto di 500 MB (`put` falso = pieno: lo scaricamento si ferma) finche' non si svuota tutto per spazio basso (E-A17). Non e' una perdita di dati e non rompe nessuna garanzia degli altri invarianti: il banco lo conta a parte (`KnownDefect.BUFFER_ORPHAN_BLOBS`: non fa rosso) e lo riproduce `AiChaosKnownDefectsTest.aiD1_laMorteLasciaLeAnteprimeNelBuffer` (due media del solo telefono, morte dopo il primo salvataggio); nel banco grande compare in 312 giri su 2.000, il primo e' l'indice 8 con seme di base 6103 (seme del giro 6103081690). **Correzione suggerita** (a chi ha il perimetro, P5/P11): all'apertura del buffer, o a inizio tappa, togliere i blob dei media che `AiResults.saved` dice finiti; quando la si fa, il test di riproduzione diventa rosso e si toglie l'etichetta dal verificatore.

Per lo scaricamento il banco non ha trovato difetti: 2.000 giri sulla JVM e 600 sull'SMB verdi.

## D7. Il telefono (`tools/chaos/s25.ps1 -Mode ai`)

**Pronto, non eseguito** (niente telefono e niente NAS in questo pacchetto). Prima, a mano: l'S25 con la build debug e l'account di prova ha fatto il primo backup di `mcg-prova` (modo backup) e nell'app il ruolo Indicizzatore e' acceso; poi:

```powershell
# 1. nell'app: Ricerca e modelli -> si scaricano i modelli / "Indicizza ora"
# 2. almeno 100 interruzioni: kill -9 (run-as) e Wi-Fi spento per 3-20 s, a tempi casuali; l'app si rilancia dopo ogni morte
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Serial RFCY61FZDHW -Interruptions 100 -Seed 61 -SettleSeconds 300 -Verify
# 3. (o a parte) il verificatore dei dati sul telefono
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Step verify -Serial RFCY61FZDHW -Interruptions 100
```

Il verificatore (`-Step verify`) lavora in **sola lettura** e solo nella cartella dell'app di debug, via `run-as` (`find`, `stat`, `sha256sum`, `exec-out ... cat`): in `models/` ogni file ha nome, dimensione e SHA-256 del catalogo (letto da `ModelCatalog.kt`; invarianti 2 e 1), `models/partial/` e' vuota a ripresa finita (9); in `ai-buffer/` nessun `*.tmp` e al massimo 500 MB (9); in `search/index/` i record del file dei vettori hanno il CRC giusto (5): una morte puo' rovinare al piu' i record di una scrittura (fino a 9), di piu' e' un errore. Il file dei vettori si copia in `build\chaos\` e si cancella a fine controllo. Esce con 4 se qualcosa non torna, come il modo backup.

**Il file dei vettori, com'e' davvero (DB-2, revisione C).** Il nome e' `vectors-<codice dello spazio>-<generazione>.bin` (`LocalAiIndex.vectorFileFor`; oggi `vectors-embeddinggemma2_float_v1-1.bin`, il codice si legge da `AiCodes.kt`) e il file e' **little-endian** (`ByteBuffer.order(LITTLE_ENDIAN)`): il segno `MCGV` (`0x4D434756`) e' scritto `56 47 43 4D`, il CRC32 e' little-endian. Prima lo script cercava `vectors-<n>-<n>.bin` e il segno `4D 43 47 56`: non trovava mai il file (o, trovato, leggeva solo "spazzatura") e diceva "tutto a posto", quindi l'invariante 5 passava sempre; l'autotest e il banco finto costruivano i record come li aspettava lo script, non come li scrive l'app. Adesso: il nome e il codice dello spazio si leggono dai sorgenti; un file `vectors-*` che non ha il nome dell'app e' un errore; un file **non vuoto senza nemmeno un record riconoscibile** e' un errore (nome giusto e formato sbagliato; fa eccezione la sola coda di una prima scrittura interrotta); il banco `s25-ai-finto.ps1` scrive i record byte per byte come `VectorFile.append` e controlla che i sorgenti dell'app dicano ancora le stesse cose (se l'app cambia formato il banco diventa rosso). La tolleranza del passo `verify` da solo e' `-Interruptions` morti (prima dipendeva dal calendario di un seme casuale).

**Guardie** (come gli altri modi, un po' piu' strette): un solo telefono, `RFCY61FZDHW` (con un adb finto, `MCG_ADB`, vale il numero che si vuole); solo `dev.pampa.mycloudgallery.debug`; il Wi-Fi si spegne solo con `svc wifi disable` dello script, al massimo 120 s, e si riaccende SEMPRE (anche se lo script e' interrotto); sul telefono si leggono solo file della cartella dell'app. Il calendario dipende solo dal seme; `-DryRun` lo stampa. `-Mode ai` ha i passi `interrupt` e `verify` (e, dalla revisione C, `eval-push` ed `eval-pull` per lo strumento di valutazione, DB-4: vedi `docs/prove-campo.md`, sezione 9): la semina di `mcg-prova` e il primo backup sono del modo backup, e i passi `eval-*` negli altri modi sono rifiutati. I modi `move` e `backup` non cambiano.

**Provato senza telefono**: `s25.ps1 -Mode ai -SelfTest` (record dei vettori buoni, rovinati nel mezzo, troncati in coda, spazzatura, nel nome e nel formato dell'app; catalogo dei modelli letto dal sorgente; guardie accettate e rifiutate) e `s25-ai-finto.ps1` (stile di `s25-finto.ps1`, contro `fake-adb/`, che capisce `run-as <pacchetto> find`, `exec-out run-as <pacchetto> cat`, **il processo dell'app** (`pidof`, `run-as ... kill -9 <pid>`, `am crash|kill|force-stop`, `monkey`), `svc wifi`, `adb push`, `run-as ... mkdir|cp`): verificatore verde su un telefono in ordine (e legge davvero i 20 record), rosso per un modello con un byte cambiato, un file estraneo, un modello piu' corto, un parziale rimasto (verde con `-NotSettled`), un temporaneo orfano, troppi record rovinati, un file con il nome giusto e il vecchio ordine dei byte, un file `vectors-*` con un nome che l'app non scrive, un file non vuoto senza record; interruzioni con Wi-Fi riacceso e adb che riceve solo comandi previsti, solo `run-as` dell'app di debug, solo percorsi `no_backup/(models|ai-buffer|search)`; **la morte del processo** (3 `kill -9` di pid diversi, rilancio a ogni morte, mezzo record lasciato in coda al file e accettato dal verificatore, nessun `kill` se il processo non c'era); `eval-push` ed `eval-pull`; guardie. Il banco e' **rosso con lo script di prima** della revisione C (la verifica non legge i 20 record, i quindici rovinati non fanno fallire, il vecchio ordine dei byte e il nome sbagliato passano, `eval-push` non esiste). **Non provato**: `kill -9` e `svc wifi` veri, `run-as` sul telefono vero, `adb push` verso `/data/local/tmp` vero, l'indicizzazione e lo scaricamento veri dell'app (P11).

## D8. Che cosa non copre (e chi lo copre)

- **Le anteprime lette dall'SMB**: `core-ai` non vede `core-nas` (e `core-models` non vede `core-index`), quindi `IndexAnalysisFetcher` e `NasRemoteIndex` non si possono montare sul simulatore SMB da questi due moduli, senza toccare i `build.gradle`. I 600 giri SMB leggono i **modelli** con la lettura vera; per le anteprime serve `testImplementation project(':core-nas')` in `core-ai` (P11, o P0) oppure un test in `:app`/`core-index` che monti `IndexAnalysisFetcher` sul simulatore e lanci questo mondo. Resta aperto.
- **Le primitive `SmbNasFileOps`**: i modelli si leggono con `SmbNasMediaReader` e la copia propria si scrive con il vero `SmbNasFileSystem.publishExclusive` (`ShelfWriter` vero e' un punto aperto di P3: scrittura a flusso). `SmbNasFileOps` (cestino, rinomine) non e' sul percorso dei modelli.
- **L'indice locale vero** (`LocalAiIndex`, in `core-search`, che `core-ai` non vede): il banco mette alla prova il **contratto** che l'indice deve rispettare (record con CRC, riga di conferma, coda rovinata che un processo nuovo butta) con una sua implementazione in `AiChaosIndex`. La stessa verifica (`AiChaosVerifier`: righe contro record) va ripetuta sull'indice vero in un test di `:app` (P11), dove si vedono tutti i moduli.
- **I nascosti (10)**: nel banco di `core-ai` non c'e' un motore di ricerca. Il banco controlla che i nascosti si calcolino (E-H1) e che una ricerca di riferimento (dati pubblicati + bandierine del catalogo) non ne mostri: e' la meta' che sta nel calcolo. L'altra meta' (`InMemorySearchEngine`/`SearchEngine` dopo le interruzioni) e' nei test di proprieta' di `core-search` (P6) e va ripetuta a fine giro in un test di `:app`.
- **Il vero HTTPS di Hugging Face**: il server di prova e' del JDK con un certificato fidato solo dal banco; redirezioni verso host non ammessi, `http`, intestazioni e cookie sono di `HttpsModelFetcherTest` (P3). Il CDN vero, il TLS di sistema e `HttpURLConnection` di Android sono del telefono (F46-F49).
- **Il tempo del NAS vero e le ore**: le prenotazioni di un'ora e l'ora del NAS fanno avanzare un orologio finto.

## D9. Come collegare il banco a `check.ps1` (P11)

Il banco gira gia' con `gradlew :core-models:testDebugUnitTest :core-ai:testDebugUnitTest` (le sue classi stanno nei moduli, con i loro giri di default). **Non e' collegato a `check.ps1`**, che tocca solo P11:
1. nessuna riga nuova se `check.ps1` lancia i test unitari di tutti i moduli (lo fa gia' per le Fasi 4 e 5): i 2.000 giri a ogni giro di `check.ps1` sono i default del banco; `-PmcgTestTimeoutMinutes` resta quello (20 minuti: il banco del calcolo dura ~4 minuti con le mutazioni e `core-models` ~3,5 minuti in tutto: 2.000 giri, 600 SMB e mutazioni);
2. se `check.ps1` filtra i test per modulo, aggiungere `core-models` e `core-ai` ai moduli con i banchi, accanto a `core-nasops` e `core-backup`;
3. facoltativo, per un passo a se': `tools\chaos\area.ps1 -Mode ai -Stage jvm` e `-Stage mutants` (aspettano da soli 2 GB di RAM libera), e `tools\chaos\s25-ai-finto.ps1` + `s25.ps1 -Mode ai -SelfTest` accanto al passo che prova `s25-finto.ps1`;
4. `AiChaosKnownDefectsTest` e' un test che diventa rosso quando AI-D1 viene corretto: va letto come un avviso, non come una regressione (§D6).

## D10. Che cosa conta come superata

1. **JVM**: 2.000 giri dello scaricamento e 2.000 del calcolo, 600 dello scaricamento sull'SMB, **zero violazioni** (i difetti noti del §D6 non contano, sono nel report).
2. **Mutazioni**: i quattro errori rossi con l'invariante giusto, lo stack sano verde.
3. **Difetti noti**: ognuno ha la sua riproduzione minima (verde fino alla correzione) e il suo seme.
4. **Telefono** (F61, a mano dall'orchestratore): `s25.ps1 -Mode ai` con ≥ 100 interruzioni durante lo scaricamento e l'indicizzazione e il verificatore dei dati verde; poi, in un modulo che vede il NAS, il controllo delle teste `ai` (invarianti 5-7 sul NAS) e in Diagnostica nessun elemento fermo.
5. Un giro rosso **si rifa'** dal suo seme (§D3).

## D11. Tempi

Sul PC di sviluppo, condiviso: 2.000 giri dello scaricamento sulla JVM ~2,5 minuti (~5,4 GiB di blocchi salvati, ~11.500 caricamenti controllati, ~750 guasti di lettura del NAS); 600 giri SMB ~47 secondi (~480 aperture e ~1.100 blocchi letti dal simulatore con la lettura vera, ~180 guasti di lettura); 2.000 giri del calcolo ~4 minuti (~27.000 salvataggi, ~3.400 morti); mutazioni ~35 secondi (lo stack sano 150-200 giri: ~10-23 secondi). Nessun test usa il tempo vero.

# Parte E. La prova delle interruzioni della condivisione (Fase 7, P5)

Il banco della Fase 7 (`docs/fase7-progetto.md` §5.5, riga P5 del §4.4, F80). Stesso impianto delle Parti A-D: un mondo, un piano di colpi deciso dal seme, un verificatore, un banco che somma i giri, errori introdotti apposta che lo rendono rosso, una prova dal PC contro il NAS vero di prova (come F16-PC) e un modo `share` di `s25.ps1` per il telefono. Dove un invariante non regge, il banco lo dice con il seme (§E6): i difetti che ha trovato nel codice di produzione sono stati corretti il 27/10 (P4) e il banco non ne evita nessuno.

Che cosa si prova: **spostare i file nell'area comune (condividere un album, aggiungere media, riportarli nelle mie cartelle, adottare i file aggiunti dal PC) può essere interrotto in qualunque punto** (processo ucciso, Wi-Fi tagliato, un guasto a ogni chiamata) **senza danni**: mai un file perso, doppio o a metà, mai una sorgente nel cestino prima della verifica, mai una voce per un file che non è nell'area comune, mai una conferma riusata.

## E1. Che cosa promette

I 9 invarianti del §5.5, più due del banco, controllati **dopo ogni morte del processo, a fine di ogni passo, a ogni giro in cui un colpo è scattato e in una rilettura finale con l'hash di ogni file**. Il numero è quello del §5.5:

| N. | Invariante | Come si controlla |
|---|---|---|
| 1 | nessun file a metà visibile | ogni file fuori da `.mycloudgallery/` (area comune e cartelle d'origine) ha i byte di uno degli originali; i temporanei solo in `.mycloudgallery/tmp/`, citati da un'operazione di `core-nasops` (o lasciati da uno stop per spazio finito, dichiarato dal giro) |
| 2 | nessun doppione | ogni media dell'operazione ha **un solo** file nell'area comune; due copie visibili solo fra «verificato» e «sorgente nel cestino» |
| 3 | nessuna perdita | in ogni istante ogni media ha una copia completa fuori dal cestino, oppure una nel cestino con il record e una completa altrove; **un osservatore guarda la porta di completamento**: quando il catalogo conosce la destinazione la sorgente c'è ancora e la destinazione ha i byte giusti (il catalogo sa prima del cestino) |
| 4 | hash al 100% | ogni destinazione ha i byte dell'originale; rilettura finale file per file |
| 5 | nessun elemento mancante in app | la Libreria ha gli stessi media (stessi `MediaId`) prima e dopo; ogni media ha una posizione che esiste davvero (dopo `IN_COMMON` quella nell'area comune) |
| 6 | nessuna voce senza file | **un osservatore guarda ogni voce `item:` che sta per essere accodata**: il file è già nell'area comune, con i byte giusti, e il catalogo lo sa; la voce non si pubblica per un file assente (rivisto dopo ogni svuotamento della coda) |
| 7 | nessuna azione senza conferma | **un osservatore guarda ogni trasferimento che parte**: la conferma è di quel piano (impronta), ha meno di 10 minuti ed è stata emessa dal foglio **di questo processo** (una conferma di un processo morto, riusata, è una violazione); ogni operazione di `core-nasops` ha la sua conferma in `album_share_op` con la stessa impronta |
| 8 | il lavoro arriva in fondo | dopo le riprese ogni elemento è `DONE`, `FAILED` con un motivo vero o `CANCELLED`; `NOT_STARTED` solo se il processo è morto o l'ambiente ha dato fastidio; `SOURCE_CHANGED`/`SOURCE_GONE` solo se il PC ha toccato quel file; `NO_SPACE`/quota solo con un colpo «spazio finito» scattato |
| 9 | nascosti | con «Lascia fuori» nessun nascosto si è mosso |
| 10 | (del banco) il codice non lancia | nessuna eccezione esce dal codice sotto prova per un guasto che non sia del catalogo; nessuna transizione non ammessa del diario |
| 11 | (del banco) la ripresa a vuoto non cambia niente | una morte senza colpo, a lavoro finito, seguita da una ripresa: il NAS, i diari e il catalogo restano identici (impronta) |

In più, a ogni controllo: il **catalogo dice la stessa cosa del diario** (nessun media «in comune» senza un elemento `DONE` o un file nell'area comune), le **operazioni di `core-nasops` e le conferme** (anche a metà), e **i temporanei** solo dove devono stare. Gli osservatori sono **a eventi e sempre accesi**: non dipendono da quando il banco guarda il mondo, per questo vedono il momento giusto (la voce che entra in coda, la sorgente che sta per andare nel cestino) e non solo il suo effetto.

## E2. I banchi

| Dove | Cosa | Giri |
|---|---|---|
| `core-albums/src/test/.../chaos/ShareChaosBenchTest` | il vero `ShareOrchestrator` e il vero `NasOpsEngine` sul **NAS finto** in memoria di `core-nasops` (`NasOpsTestKit`), con colpi alle porte (spostamento, caricamento dal telefono, biglietti dei trasferimenti, pubblicatore degli album, copia privata del guardiano, cartelle d'origine, fatti del catalogo, porta di completamento) e alla scrittura dei diari (morte a **ogni** salvataggio del diario della condivisione) | **2.000** |
| `ShareChaosSmbBenchTest` | lo stesso sul **simulatore SMB** di `core-nas`: sessione di produzione (`SmbNasSessionManager`) e primitive vere (`SmbNasFileOps`, `SmbNasFileSystem`); in più un colpo a **ogni chiamata SMB** del motore e la morte a **ogni salvataggio del diario di `core-nasops`** | **600** |
| `ShareChaosSweepTest` | **un colpo a ogni chiamata** di un passo (condividere un album nuovo, con un caricamento dal telefono): per ogni punto e ogni chiamata, la morte del processo prima e dopo, la rete caduta prima e dopo, la sessione scaduta, il socket fermo, lo spazio finito dove ha senso; sul NAS finto e sul simulatore SMB (dove ogni chiamata SMB e ogni salvataggio del diario di `core-nasops` sono un punto) | **541** + **1.695** giri |
| `ShareChaosMutationTest` | i quattro errori introdotti apposta (§E7) | 200 giri per errore |
| `ShareChaosKnownDefectsTest` | le regressioni dei difetti corretti (§E6): gli scenari di SD-1, SD-2 e SD-3 senza giri casuali e i giri che avevano trovato SD-1 e SD-2 | 4 test |
| `DyingOpsStoreTest` | la morte al diario di `core-nasops` è un proxy sull'interfaccia: ogni metodo di `OpsStore` è classificato (scrittura o lettura) e la prima scrittura, `create`, è davvero un punto dove si muore (i metodi con un `OpId` hanno il nome storpiato dal compilatore: il primo proxy ne saltava un quarto) | 2 test |
| `ShareChaosVerifierTest`, `ShareChaosDeterminismTest` | il verificatore ha i denti (16 casi, ognuno costruito rompendo il mondo) e un giro si rifà identico dal seme | 16 + 3 |
| `ShareAreaRunnerTest` | il codice del NAS vero (guardia, trasporto recintato, oracolo, pulizia, autotest) sopra il simulatore SMB, senza rete (§E4) | 4 test |
| `ShareManifestVerifierTest` | il verificatore in sola lettura dell'area di una condivisione fatta dal telefono (§E5) | 11 test |
| `ShareNasIntegrationTest` + `tools/chaos/share-pc/**` | la prova dal PC sul **NAS vero** di prova, come F16-PC (§E4) | **500 interruzioni** |
| `tools/chaos/s25.ps1 -Mode share` | il telefono (§E5) | ≥ 100 interruzioni |

File (tutti in `core-albums/src/test/kotlin/dev/pampa/mycloudgallery/core/albums/chaos/`, pacchetto `...core.albums.chaos`): `ShareChaosTypes` (invarianti, colpi, statistiche), `ShareChaosInjector` (i colpi e il diario con la morte), `ShareChaosPorts` (le porte avvolte e gli osservatori), `ShareChaosTransport` (il trasporto SMB con i colpi), `ShareChaosTargets` (i due NAS e l'oracolo), `ShareChaosPlan` (capacità, difetti noti, i colpi di un passo), `ShareChaosWorld` (il mondo e gli accidenti dell'ambiente), `ShareChaosVerifier`, `ShareChaosRound`, `ShareChaosBench`, `ShareChaosMutants`; per il NAS vero `ShareAreaGuard`, `ShareAreaTransport`, `ShareAreaAdmin`, `ShareArea`, `ShareAreaRunner`, `ShareAreaReader`, `ShareManifest`. Il banco riusa, senza toccarli, `ShareBench` e i `ShareNasTarget` di P4 (`core-albums/src/test/.../share/`).

Il banco **non nomina** `SmbNasFileOps`, `NasFileSystem` fuori da `albums/shared/`, `smbj`, `java.net` né i tipi della conferma (`UserConfirmation`, `ConfirmationSheet`...): lo vietano i test di architettura di P4 e di Fase 7, anche per il codice di prova. Il motore si costruisce solo con `NasOpsWiring.build(sessione, diario, configurazione)`; il trasporto con i colpi avvolge `SmbTransport`.

**Un giro** (`ShareChaosRound`). Un mondo: da 2 a 4 media nelle mie cartelle (in cartelle diverse, qualche video, due con lo stesso nome ogni tanto: E-SH4), da 0 a 2 solo sul telefono (`INCOMING`, E-SH1), spesso uno già nell'area comune, a volte uno nascosto e uno di un altro account; quasi tutti piccoli, ogni tanto di decine di KB, di rado di centinaia (più blocchi di copia); un familiare che legge l'area comune. Da 1 a 3 **passi dell'utente**, scelti guardando lo stato (mai una richiesta che il piano rifiuterebbe): condividi un album nuovo (1-5 media, «Lascia fuori» o «Condividi anche i nascosti»), aggiungi a un album condiviso, riporta nelle mie cartelle, adotta i file che il PC ha messo nella cartella dell'album. Per ogni passo il piano decide, dal seme:

- i **colpi**: da 0 a 4, ognuno a una *superficie* (spostamento, caricamento dal telefono, biglietti dei trasferimenti, pubblicatore degli album, copia privata del guardiano, cartelle d'origine, fatti del catalogo, porta di completamento, diario della condivisione, diario di `core-nasops`, chiamata SMB), alla chiamata *n*, **prima** o **dopo** l'effetto (la modifica c'è, la risposta si perde), di tipo connessione caduta, sessione scaduta, socket fermo, spazio finito o **morte del processo**. Alcune superfici sono per costruzione solo di morte: il salvataggio di un diario muore *dentro* (prima del file, o dopo la scrittura e prima della conferma). Metà dei colpi di un passo cade subito, metà quando l'avvio è tornato;
- un **accidente dell'ambiente** (il 35% dei passi sul NAS finto): un file di cui l'utente chiede lo spostamento tenuto aperto dal PC, un file cambiato dal PC prima della copia (E-SH18), l'area congelata, lo spazio che finisce, le scritture degli album ferme.

L'utente tocca (piano, foglio, conferma, avvio: se l'avvio non arriva al diario riprova con un foglio nuovo), poi il lavoro gira a colpi armati per otto riprese; **a ogni morte** il banco guarda il mondo *così com'è*, ricostruisce tutti gli oggetti del processo (orchestratore, motore, sessione) sugli stessi diari, NAS e catalogo, e continua; alla fine del passo toglie i guasti e lascia che il lavoro arrivi in fondo (fino a 40 riprese), poi controlla **tutti** gli invarianti a lavoro finito. Dopo un passo su quattro una **morte senza colpo con una ripresa a vuoto**: non deve cambiare niente (invariante 11). In fondo al giro la **rilettura completa** con l'hash di ogni file, quello che vede un familiare (nessun elemento senza il suo file nell'area comune) e un'ultima ripresa a vuoto.

Il motore che sposta i file è sempre il vero `NasOpsEngine` di `core-nasops` (sul NAS finto in memoria, sul simulatore SMB con le primitive vere, sul NAS vero); i colpi stanno nei decoratori delle porte, del trasporto e dei diari, mai dentro il codice di produzione.

Il rapporto di un banco ha anche la **tabella di copertura**: per ogni superficie, quante chiamate ci sono state, quanti colpi programmati, quanti scattati e di quelli quanti morti. Un banco che non colpisce un punto lo dice (il rapporto dei 2.000 giri colpisce tutti e nove i punti del NAS finto, quello dei 600 tutti e undici del simulatore, compresi il diario di `core-nasops` e le chiamate SMB).

**Un colpo a ogni chiamata** (`ShareChaosSweepTest`). I giri casuali campionano le chiamate; questo banco le prova *tutte*. Sceglie dal seme due mondi (uno con un caricamento dal telefono, uno con il più lavoro sul NAS), fa **un solo passo** (condividere un album nuovo) e lo misura con un giro senza colpi: quante chiamate fa a ogni punto (sul simulatore SMB, per esempio, 75 chiamate SMB, 30 salvataggi del diario di `core-nasops` e 24 del diario della condivisione). Poi, per ogni punto e ogni chiamata *n*, un giro con **un solo colpo**: la morte del processo prima e dopo, la connessione caduta prima e dopo, la sessione scaduta, il socket fermo, lo spazio finito dove ha senso (le porte che scrivono), e per ciascuno tutti gli invarianti come negli altri banchi. Sul NAS finto 541 giri (20 s), sul simulatore 1.695 (72 s, di cui 1.014 con un colpo a una chiamata SMB e 138 con la morte a un salvataggio del diario di `core-nasops`); in 12 e 58 giri il colpo non scatta (lo spazio finito su una chiamata che non occupa spazio: non è un colpo).

## E3. Come si lancia

```powershell
# Tutto sulla JVM, --max-workers=2, DOPO aver aspettato 2 GB di RAM libera (il PC e' condiviso; chi lavora in piu' agenti prende prima un posto con gradle-slot.ps1):
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode jvm        # 2.000 giri sul NAS finto
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode smb        # 600 giri sul simulatore SMB
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode sweep       # un colpo a OGNI chiamata di un passo (-Rounds 3: tre mondi invece di due)
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode mutants    # i quattro errori introdotti apposta
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode known      # le regressioni dei difetti corretti (SD-1, SD-2, SD-3: rosse se tornano)
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode area       # il codice del NAS vero sopra il simulatore: nessuna rete
# Rifare UN giro rosso (il rapporto dice "giro 163, seme di base 20261026"): stesso seme di base, un solo giro, a partire da quell'indice
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 163 -Seed 20261026
# Il NAS vero (serve il file delle credenziali, §E4; 500 interruzioni di norma):
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode run -Interruptions 20 -DryRun
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode run -Interruptions 500
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode clean -Run <giro>      # toglie un giro rimasto dopo una prova interrotta
# Il telefono: vedi §E5. Il piano a secco, l'autotest e la prova contro l'adb finto non vogliono ne' telefono ne' adb:
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -Step interrupt -DryRun -Interruptions 100
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -SelfTest
powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\s25-share-finto.ps1
```

Variabili che i test leggono: `MCG_SHARE_CHAOS_ROUNDS` (2.000), `MCG_SHARE_CHAOS_SMB_ROUNDS` (600), `MCG_SHARE_CHAOS_SEED` (20261026 sulla JVM, 20261027 sull'SMB), `MCG_SHARE_CHAOS_FIRST_ROUND`, `MCG_SHARE_SWEEP_WORLDS` (2); per il NAS vero `MCG_NAS_INTEGRATION=true` (l'interruttore), `MCG_SHARE_MODE` (`run`, `clean`, `verify`), `MCG_SHARE_INTERRUPTIONS`, `MCG_SHARE_SEED`, `MCG_SHARE_FIRST_ROUND`, `MCG_SHARE_RUN`, `MCG_SHARE_MANIFEST`, `MCG_SHARE_SETTLED`. I rapporti vanno in `core-albums/build/chaos/`: `share-jvm-report-fake.txt`, `share-jvm-report-smb.txt`, `share-sweep-report-{fake,smb}.txt`, `share-area-report-<giro>.txt` (con `share-area-progress-<giro>.txt` che cresce a ogni giro e `share-area-log-<giro>.txt` con gli avvisi). Con `gradlew :core-albums:testDebugUnitTest` (e quindi `check.ps1`) i giri sono quelli di default; il NAS vero **non** gira mai da solo (si salta con il motivo se manca l'interruttore o il file).

Un giro rosso si rifà dal suo seme: il seme del giro è `base × 1.000.003 + indice × 7.919 + 17` e il rapporto lo stampa insieme alla cronaca del giro (passi, colpi scattati, morti, stato del diario e delle operazioni).

## E4. L'area di prova sul NAS vero e la guardia (§5.4)

Lo stesso banco gira **dal PC contro il NAS di casa** (`ShareNasIntegrationTest`, lanciato da `share-pc.ps1 -Mode run`): il motore vero, la sessione SMB di produzione e le primitive vere sopra `smbj`, il socket diretto del PC e l'ora vera. Cambia solo l'ambiente: nessun file «aperto dal PC» (gli handle restano sul NAS) e nessuno spazio da cambiare (`Capabilities.REAL`), meno riprese armate per passo (6), accidenti dell'ambiente al 20% (area congelata e scritture degli album ferme), e fra una ripresa e l'altra si aspetta davvero 2 s perché le attese del motore (un handle che il NAS non ha ancora chiuso, 1 minuto) sono di orologio. Il giro si ferma appena le interruzioni cadute sono quelle chieste (al massimo `4 × n + 5` giri).

**Il recinto** (le stesse regole di `ChaosAreaGuard` della 4B, F4-D30/D33, ma più strette):

- **La guardia** (`ShareAreaGuard`), prima di qualunque connessione: l'account deve essere `test` (non `admin`, `alessio`, `pampa`, `root`, `administrator` in nessuna forma: NFKC, maiuscole), l'area privata esattamente la share `test`, quella comune esattamente `test-comune`, l'host un IP letterale. Un errore nel file delle credenziali **fa fallire** la prova (non la salta) e non allarga il recinto. Le sole share toccate sono `test` e `test-comune`: **`test2`, `NAS`, `Public` e le aree personali sono rifiutate in ogni forma**.
- **Ogni chiamata** passa dal trasporto recintato (`AreaTransport`): controlla la share prima del vero e **radica ogni percorso in `mcg-it/<giro>/r<N>/`** (anche `.mycloudgallery/`: i temporanei e il cestino finiscono lì, non alla radice della share). Un percorso con `..`, assoluto, con un'unità (`C:`) o vuoto lancia `AreaViolation` e non arriva al NAS. Le sole cartelle fuori dalla radice di un giro che si possono creare sono i suoi antenati (`mcg-it`, `mcg-it/<giro>`); la sola che si può togliere è la radice stessa, quando è vuota.
- **L'autenticazione**: al primo login rifiutato un fermo (`AreaAuthLatch`) scatta e **nessun'altra autenticazione arriva al NAS** (il NAS blocca l'account dopo pochi tentativi); la prova si ferma e lo dice. Anche tre aperture consecutive fallite fermano tutto (rete assente).
- **L'oracolo** (`AreaAdmin`): una connessione sua, con **un solo login**, attraverso lo stesso trasporto recintato ma senza colpi. Serve a mettere i file di partenza, a leggere i byte di ogni file e a elencarli (gli invarianti si controllano sul NAS vero, non su quello che il motore dice di aver fatto), a cambiare un file «dal PC». La pulizia ritenta e conta cosa toglie.
- **L'autotest** (`ShareAreaSelfTest`, una novantina di casi) gira prima del primo giro: account e share vietati in ogni forma, percorsi che escono, login rifiutato, e **zero chiamate al vero** per ogni rifiuto (contatore). Con una password sbagliata sul simulatore il banco si ferma al primo rifiuto (`ShareAreaRunnerTest`).
- **Il file delle credenziali** (`%USERPROFILE%\.mycloudgallery\nas-it.properties`) lo legge **solo questo codice, in memoria**: `user`, `password`, `share`, `share2`, `host`, `guid` (facoltativa); le altre chiavi (`user2`, `password2`, `privateShare2`, `chaos.rounds`...) non si leggono nemmeno. `share-pc.ps1` ne controlla soltanto l'esistenza (`Test-Path`), non lo apre né lo stampa. Il test si salta, con il motivo, se manca l'interruttore (`MCG_NAS_INTEGRATION=true`) o il file: **nessuna connessione prima della guardia**.
- **Log**: senza nomi di file, percorsi, share, account, id né impronte; i rapporti dicono invarianti, numeri e primitive, mai contenuti. Lo stato di un giro rosso non si salva sul NAS vero.

**La pulizia.** Ogni giro del banco ha il suo mondo (`mcg-it/<giro>/r<N>/`, i suoi file, la sua cartella dell'app) e lo toglie quando ha finito; a fine prova si toglie la radice del giro e il rapporto dice **quanti file e quante cartelle ha tolto e cosa è rimasto** (`share-area-report-<giro>.txt`, riga «pulizia»). `mcg-it` stessa e i giri più vecchi (di altre prove) non si toccano: ognuno si pulisce con il suo `-Mode clean -Run <giro>`, e solo se vuole l'utente. Una prova interrotta lascia al più la cartella del suo giro: `-Mode clean -Run <giro>` la toglie, e lo stesso `-Mode clean` non esce mai da `mcg-it/<giro>/` di `test` e `test-comune`.

## E5. Il telefono (`tools/chaos/s25.ps1 -Mode share`)

**Pronto, non eseguito** (niente telefono e niente adb in questo pacchetto: la prova F80 la fa chi ha l'S25, dopo F79). Prima, a mano: l'S25 con la build debug e l'account di prova; poi:

```powershell
# 1. la semina: 120 foto uniche in DCIM\mcg-prova e Pictures\mcg-prova (da 20 a 200, nessun video grande) e il MANIFESTO della condivisione
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -Step seed -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Seed 80
# 2. nell'app: il primo backup di mcg-prova (parte dei media puo' restare solo sul telefono: E-SH1, caricamento INCOMING verso la cartella dell'album),
#    poi "Condividi" tutto in un album nuovo (foglio di conferma, area comune test-comune)
# 3. almeno 100 interruzioni: kill -9 (run-as) e Wi-Fi spento per 3-20 s, a tempi casuali; l'app si rilancia dopo ogni morte; poi la verifica
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -Step interrupt -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Seed 80 -Interruptions 100 -SettleSeconds 300 -Verify
# 4. (o a parte, dopo, a prova ferma) la sola verifica; con -AllowFreed i file che il guardiano o "Libera spazio" hanno tolto dal telefono non sono un cambiamento
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -Step verify -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -AllowFreed
# 5. la pulizia del telefono (solo le due cartelle di mcg-prova)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode share -Step clean -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Yes
```

Il manifesto (`build\chaos\share-manifest-<giro>.txt`, `# mcg-share-manifest 1`) dice: `run`, `device`, `private=test|mcg-campo` (la destinazione del backup, D13), `common=test-comune|Album condivisi`, e per ogni media `phone=<sha256>|<byte>|<etichetta>`; i fixture che non sono media (l'app li ignora, E-M12) sono `notmedia=`: sul NAS devono mancare. Le due cartelle sono scritte nello script, mai in un parametro.

**Il passo `verify`** fa due cose. (1) **Il telefono non è cambiato**: le impronte di `mcg-prova` tornano (la condivisione non tocca i media del telefono; senza `-AllowFreed` anche un file tolto è un cambiamento, codice 4). (2) **Il verificatore dal PC**, in **sola lettura**, sull'area di prova: `share-pc.ps1 -Mode verify -Manifest <manifesto con le righe freed=>`. Legge solo le cartelle del manifesto (`mcg-campo`, `Album condivisi`) e `.mycloudgallery/{tmp,trash}` di `test` e `test-comune` (`ReadOnlyAreaTransport`: **nessuna primitiva di scrittura per costruzione**, un'allowlist di cartelle, un tetto alle dimensioni), e dice, con l'impronta di ogni file letto:

| Invariante | Cosa vede dal NAS |
|---|---|
| 1 | ogni file visibile ha l'impronta di un media seminato (un solo byte diverso, o un fixture che non è un media, è una violazione); a prova ferma nessun temporaneo, durante la prova solo temporanei propri (`-op-`, `-bk-`) |
| 2 | un media non sta due volte nell'area comune, e a prova ferma non sta due volte in tutto (la sorgente è nel cestino); a metà al più due copie |
| 3 | un media che il telefono non ha più (`freed=`) sta sul NAS fuori dal cestino; solo nel cestino, o in nessun posto, è una perdita |

Il resto (diario della condivisione, diario del motore, conferme, voci firmate degli album) **sta nel telefono**: lo guardano il banco sulla JVM e la schermata dell'app; `s25.ps1` non lo legge. È un limite dichiarato (§E8). `-NotSettled` per una prova non ancora ferma. Esce con 4 se qualcosa non torna, come i modi backup e ai.

**Guardie** (come gli altri modi, un po' più strette): un solo telefono, `RFCY61FZDHW` (con un adb finto, `MCG_ADB`, vale il numero che si vuole); solo `dev.pampa.mycloudgallery.debug`; il Wi-Fi si spegne solo con `svc wifi disable` dello script, al massimo 120 s, e si riaccende SEMPRE (anche se lo script è interrotto); `-Photos` da 20 a 200, al più un video grande, `-DestShare` solo `test`; sul telefono si toccano solo le due cartelle di `mcg-prova`. Il calendario dipende solo dal seme; `-DryRun` lo stampa. `-Mode share` ha i passi `seed`, `interrupt`, `verify` e `clean`; gli altri passi (`edit`, `eval-*`) sono rifiutati; i modi `move`, `backup` e `ai` non cambiano (le loro prove a secco restano verdi).

**Provato senza telefono**: `s25.ps1 -Mode share -SelfTest` (il manifesto della condivisione, la riga `freed=`, le guardie accettate e rifiutate, 11 controlli) e `tools\chaos\share-pc\s25-share-finto.ps1` (stile di `s25-finto.ps1`, contro `fake-adb/`): piani a secco che non chiamano adb; semina (manifesto con le due cartelle, nessun video grande, i fixture finti `notmedia=`, i file solo in `mcg-prova`); impronte che tornano; interruzioni con Wi-Fi riacceso, app rilanciata e solo comandi previsti; un file tolto dal telefono (rosso senza `-AllowFreed`, verde con); un file del telefono che cambia (rosso, codice 4); pulizia (senza `-Yes` rifiuta); guardie (pacchetto di release, Wi-Fi oltre 120 s, meno di 20 o più di 200 foto, share diversa da `test`, un passo che il modo non ha) rifiutate prima di toccare il telefono. Il verificatore sul NAS nel finto non parte (`-SkipNas`); il suo codice è provato dai test Gradle di `ShareManifestVerifierTest` (manifesto, manifesti ostili, area sana, file a metà, fixture sul NAS, doppioni, perdita, temporanei, lettore che rifiuta ogni scrittura). **Non provato**: `kill -9` e `svc wifi` veri, `run-as`/`adb push` sul telefono vero, la condivisione vera dell'app (F79), il verificatore del PC sull'area scritta davvero dall'app (sul NAS vero lo prova `ShareNasIntegrationTest` in modo `verify`, ma solo quando c'è un manifesto vero).

## E6. Difetti trovati nel codice di produzione (corretti il 27/10)

Il banco ha trovato **due difetti reali di `ShareSteps`** (`core-albums/src/main/.../share/ShareSteps.kt`, P4), SD-1 e SD-2; P5 non li ha corretti (il codice di produzione non era del suo perimetro): il banco li evitava (`ShareChaosConfig.avoid`) e li riproduceva in `ShareChaosKnownDefectsTest`. **Il 27/10 P4 li ha corretti alla radice**, insieme a due difetti della stessa famiglia venuti fuori togliendo gli evitamenti (SD-3, SD-4): `KnownDefect` e `ShareChaosConfig.avoid` non ci sono più, il banco di serie fa anche le condivisioni con file cambiati dal PC dentro uno stesso trasferimento di più file, lo spazio finito nelle chiamate SMB di quei trasferimenti e i guasti passeggeri su `TicketStatus.find`. `ShareChaosKnownDefectsTest` è diventato una **regressione** (come `ChaosKnownDefectsTest` di core-nasops dopo P12/P16): fallisce se uno torna. Con il codice di prima i suoi scenari sono rossi (SD-1: 1 morte su 40; SD-2: 2 su 12; SD-3: 1 su 12; il giro 73 rosso), con quello di oggi verdi.

**SD-1 (invarianti 8 e 5, `ShareSteps.pollTransfers`): quando un trasferimento di più file finisce, gli esiti si applicano agli elementi sbagliati se un'interruzione ne ha lasciati assestati solo alcuni.** Gli esiti di un biglietto finito (`TicketState.Ended`) sono una lista **nell'ordine di tutti i file del trasferimento**; `pollTransfers` però li applica per posizione agli elementi **ancora in `TRANSFERRING`** (`ordered.forEachIndexed { i, item -> settle(item, state.outcomes.getOrNull(i)) }`). Se la morte del processo (o un errore del catalogo) cade fra due salvataggi del diario dopo che il primo elemento è stato assestato, alla ripresa il secondo è l'unico rimasto in `TRANSFERRING` e riceve l'esito del **primo**. Effetto con esiti diversi (il PC ha cambiato uno dei file, che finisce `SOURCE_CHANGED`): il file cambiato, mai partito, riceve l'esito «arrivato» dell'altro e resta in `TRANSFERRING` per sempre (aspetta un catalogo che non lo vedrà mai); oppure un file **arrivato** riceve l'esito «cambiato» e diventa `FAILED(source_changed)` mentre sta nell'area comune, senza voce nell'album. Non è una perdita di dati (ogni file è intero dov'è), ma l'utente legge «cambiato» per un file arrivato, o un elemento fermo per sempre. Riproduzione: `ShareChaosKnownDefectsTest.sd1_oggiGliEsitiSiApplicanoAgliElementiRimasti`: due file da spostare, il PC cambia il secondo, la morte a ognuno dei primi 40 salvataggi del diario: **1 morti su 40** lasciano un elemento sbagliato (la prima al salvataggio 7: elemento appeso per sempre in `TRANSFERRING`). Il banco lo trova senza l'evitamento: con `avoid = emptySet()` e il seme di base 20261026 (il seme del giro *i* è `base × 1.000.003 + i × 7.919 + 17`), 1.000 giri danno **3 giri rossi: il 221 è SD-1** (un errore permanente `source_changed` per un file arrivato, invariante 8) **e il 73 e il 739 sono SD-2** («trasferimento mai partito ma il media ha cambiato posto», invariante 3), mentre con gli evitamenti di serie gli stessi 300 giri sono verdi. **Correzione suggerita** (a chi ha il perimetro, P4/P13): applicare l'esito per la posizione dell'elemento **nel trasferimento** (`group` ordinato per `index` fra *tutti* gli elementi con quella chiave, non solo quelli ancora in `TRANSFERRING`); **Corretto (27/10)**: gli esiti si assegnano per la posizione di ogni elemento **nella richiesta**, cioè fra tutti gli elementi con quel biglietto in qualunque stato (il biglietto resta sull'elemento in ogni passo dopo), non fra i soli rimasti; se una parte della richiesta è ancora `PLANNED` (la morte a metà dei salvataggi dell'avvio) si aspetta che prenda il biglietto; una lista di esiti che non torna con la richiesta non si applica per posizione (decide il catalogo, come per un biglietto sconosciuto).

**SD-2 (invariante 8, `ShareSteps.startTransfers`): un errore del catalogo dei biglietti vale «il trasferimento non è mai partito».** `startTransfers` ritrova un trasferimento già avviato con `transient { p.tickets.find(key) }`: se `find` lancia, `transient` lo riduce a `null`, cioè «non esiste». Se il processo è nuovo (la conferma in memoria non c'è più) l'elemento ancora `PLANNED` diventa `FAILED(not_started)` («niente si è mosso»), ma il trasferimento era già partito, o è finito: il file è nell'area comune e l'elemento resta fallito, senza voce nell'album. Nello stesso processo, con la conferma ancora valida, lo stesso errore fa ripartire il trasferimento invece di ritrovarlo. Riproduzione: `sd2_oggiUnErroreDiFindVale_maiPartito`: due file, il secondo tenuto aperto dal PC, la morte a un salvataggio del diario mentre i due elementi passano a `TRANSFERRING`, poi `find` fallisce una volta: **2 morti su 12** danno un elemento «mai partito» con il file arrivato (ai salvataggi 2 e 3 il file di un elemento fallito come «mai partito» sta nell'area comune). **Corretto (27/10)**: `find` ha tre risposte (`ShareSteps.lookUp`): un biglietto, «nessun trasferimento con quella chiave» (solo questa porta a `NOT_STARTED`, senza conferma) e «non so» (un errore: il gruppo resta `PLANNED` com'è e si richiede al giro dopo, senza controllo E-SH12 e senza avvio). Lo stesso in `InMemoryShareRunner` (il riferimento di P0).

**SD-3 (invariante 8, `ShareOrchestrator.cancel`; la radice di SD-2): «Annulla» chiudeva come annullato un elemento `PLANNED` il cui trasferimento era già partito.** Dopo la morte del processo fra l'avvio di un trasferimento e il salvataggio di `TRANSFERRING`, l'elemento è ancora `PLANNED`; «Annulla» lo chiudeva `CANCELLED` senza fermare la porta, e il trasferimento arrivava in fondo: il file nell'area comune, l'elemento annullato, nessuna voce. Trovato scrivendo la regressione di SD-2 (`annullareDopoLaMorteFermaIlTrasferimentoGiaPartito`: con il codice di prima 1 morte su 12). **Corretto**: anche «Annulla» chiede `find`; ritrovato, l'elemento diventa `TRANSFERRING` con il suo biglietto e si ferma con la porta; senza risposta resta com'è e la conferma dell'operazione si toglie (niente di nuovo parte).

**SD-4 (invarianti 3 e 8, `ShareSteps.settle`): un file fermato dal motore DOPO la pubblicazione risultava fallito.** Il motore di core-nasops ferma un elemento anche dopo averlo pubblicato e collegato al catalogo, quando il cestino della sorgente non riesce (lo spazio finito alla cartella del cestino, la sorgente cambiata dopo la verifica: E-D13, restano le due copie). L'esito della porta è un fallimento, ma il file è nell'area comune e il catalogo lo sa: l'elemento diventava `FAILED(transfer_failed)` senza voce nell'album. Lo evitava, senza dirlo, l'evitamento di SD-1 (niente spazio finito sulle chiamate SMB dei trasferimenti di più file): tolto, il simulatore SMB ha dato 3 giri rossi su 600 (244 e 461 per SD-4; il 467 era un falso allarme del verificatore, sotto). **Corretto**: per un esito di fallimento o di annullamento decide il catalogo (D29): se lo **stesso** media sta già dall'altra parte, il file è arrivato e l'elemento prosegue come un file arrivato.

**Il verificatore** (`ShareChaosVerifier.engineErrorOf`) cercava il codice d'errore del motore nella **prima** operazione di core-nasops con quella sorgente, non in quella del trasferimento dell'elemento: con lo stesso media in due passi (il primo fermato dal PC, il secondo dallo spazio) leggeva il motivo del primo (giro 467 del simulatore SMB, «errore permanente per un guasto passeggero»). Ora guarda l'operazione del biglietto.

Per il resto il banco non ha trovato difetti: senza evitamenti, sul NAS finto **2.000 giri**, sul simulatore SMB **600 giri**, un colpo a ogni chiamata **541 + 1.695 giri**, senza violazioni (§E10); sul NAS vero **500 interruzioni** del 26/10, prima della correzione e con gli evitamenti.

## E7. Il banco ha i denti

Quattro errori, ognuno scritto come uno stack rotto che avvolge le porte del codice sano e ne riproduce l'effetto (come B7, C7 e D5): il codice di produzione non si tocca (`ShareChaosMutants`).

| Errore | Dove | Invariante che diventa rosso | Giro in cui lo vede (200 al massimo, seme 4242) |
|---|---|---|---|
| la sorgente va nel cestino **prima della verifica**: appena il piano parte, ogni sorgente esce dall'area privata e va nel cestino (con i suoi byte) prima di essere copiata | `ShareChaosMutants.trashBeforeVerify` (`EarlyTrashTarget`) | **3, nessuna perdita** (il file è solo nel cestino, senza una copia completa altrove) | giro 0 (169 rossi su 200: invarianti 3, 5, 8, 10) |
| la voce `item:` è **pubblicata prima del completamento**: appena il trasferimento è chiesto, la voce col percorso di destinazione è accodata e pubblicata, col file ancora fuori dall'area comune | `ShareChaosMutants.entryBeforeCompletion` (`EarlyEntryMover`) | **6, nessuna voce senza file** | giro 0 (176 rossi su 200: invariante 6) |
| il **completamento dopo il cestino**: la porta di completamento risponde subito «fatto» e il catalogo viene a sapere della destinazione solo al giro dopo, quando la sorgente è già nel cestino | `ShareChaosMutants.completionAfterTrash` (`DeferredCompletions`) | **3** (il catalogo conosce la destinazione dopo che la sorgente è andata) e **5** (il catalogo punta a un file che non c'è) | giro 0 (165 rossi su 200: invarianti 3, 5, 6, 8) |
| la **conferma riusata**: la prima conferma emessa si tiene (anche oltre la morte del processo) e serve per ogni trasferimento dopo, di ogni piano | `ShareChaosMutants.reusedConfirmation` (`ReusingMover`) | **7, nessuna azione senza conferma** | giro 0 (76 rossi su 200: invarianti 7, 8) |

Lo stack sano con gli stessi semi resta verde (200 giri), così il rosso viene dall'errore, non dal banco. I due invarianti 6 e 7 si vedono grazie agli osservatori **a eventi** (l'ingresso in coda della voce, la partenza del trasferimento): un banco che guardasse solo il mondo dopo ogni morte potrebbe non vedere mai il momento sbagliato. `ShareChaosVerifierTest` fa lo stesso lavoro per il verificatore: 16 casi, ciascuno costruito rompendo un pezzo del mondo (un byte, un doppione, una sorgente sparita, una voce senza file, un temporaneo senza operazione...) e controllando che quel pezzo, e solo quello, faccia rossa la riga giusta.

## E8. Che cosa non copre (e chi lo copre)

- **I legami del banco, non quelli di `:app`.** L'orchestratore è quello di produzione, il motore delle operazioni anche, ma lo spostamento (`NasAlbumMover`), i biglietti (`BenchTickets`), la porta di completamento sul catalogo (`LibraryCompletions`) e il caricamento dal telefono (`PhoneUploader`) sono i legami di P4, scritti nei test di `core-albums`: `:app` (P13) li riscrive sul vero `NasOperations`, sul diario di `core-nasops` e su `MediaCatalog.attachMoved`. **Il banco va ripetuto sui legami di `:app`** (stessa classe `ShareChaosBench`, un altro `ChaosTargetFactory`) quando P13 li ha, e il cammino `INCOMING` vero del backup (`core-backup`, chunk, ripresa) è del banco di core-backup (Parte B), non di questo: qui il caricamento dal telefono è un motore semplice che scrive file interi, idempotente per chiave.
- **Il catalogo e il familiare veri.** Il catalogo è `FakeAlbumLibrary` e il familiare un secondo `FakeAlbumLibrary`: le voci `item:` firmate, la replica e i dati personali veri sono di `core-albums` (P1-P3) e delle loro proprietà. Il banco controlla l'invariante 6 sul punto di accodamento, non sulla firma.
- **I file aperti dal PC e lo spazio, sul NAS vero.** Sul NAS vero gli handle restano sul NAS e lo spazio libero non si cambia: il banco di quella prova non usa «un file aperto dal PC», «un file cambiato a metà» né «spazio finito» (`Capabilities.REAL`). Li provano i 2.000 giri sul NAS finto e i 600 sul simulatore SMB (che tiene gli handle come Samba).
- **Il tempo vero del NAS e le ore.** Sulla JVM l'ora è finta (le conferme di 10 minuti, i 31 giorni del cestino, i timeout del motore avanzano a comando); sul NAS vero i tempi sono quelli veri ma una prova dura minuti, non ore.
- **Il telefono.** `s25.ps1 -Mode share` non è eseguito (§E5). Non c'è modo di leggere dal PC il diario della condivisione né le voci degli album: il verificatore dal PC controlla gli invarianti 1, 2, 3 e i temporanei (e i fixture che non sono media), non 5-9. Per questo la prova F80 chiede anche uno sguardo all'app dopo la verifica: nessun elemento fermo e nessun media mancante nelle schermate della condivisione.
- **L'architettura.** Il banco non nomina `SmbNasFileOps`, i tipi della conferma né `smbj` direttamente (vietato nei test di architettura): quello che viene colpito è il motore e il trasporto, non le primitive una per una. Le primitive vere una per una sono del banco di `core-nasops` (Parte A, 600 giri).
- **Sul NAS finto in memoria** le chiamate del motore al NAS e il diario di `core-nasops` non si colpiscono: il NAS finto di `NasOpsTestKit` è `internal` a `core-nasops` (fuori da lì si usano solo i suoi metodi pubblici) e il kit costruisce da sé il motore sul proprio diario, senza un gancio. Quei colpi stanno **solo sul simulatore SMB** (600 giri casuali e il banco «un colpo a ogni chiamata»). Il banco sul NAS finto colpisce le porte dell'orchestratore, la porta di completamento e **ogni salvataggio del diario della condivisione** (la morte, prima o dopo la scrittura).
- **Nessun evitamento.** Fino al 26/10 il banco evitava i difetti del §E6 (niente file cambiati dal PC né spazio finito nelle chiamate SMB di un trasferimento di più file, nessun guasto passeggero su `TicketStatus.find`); dal 27/10, corretti, li fa tutti.

## E9. Che cosa conta come superata

1. **JVM**: 2.000 giri sul NAS finto e 600 sul simulatore SMB, **zero violazioni**, senza evitamenti.
2. **Un colpo a ogni chiamata**: 541 giri sul NAS finto e 1.695 sul simulatore SMB, zero violazioni, almeno nove colpi su dieci scattati.
3. **Mutazioni**: i quattro errori sono rossi con l'invariante giusto (§E7), lo stack sano sugli stessi semi è verde.
4. **Difetti corretti**: ognuno ha la sua regressione (lo scenario e il giro che lo aveva trovato), verde.
5. **NAS vero** (F80, dal PC): 500 interruzioni cadute su `test` → `test-comune`, zero giri rossi, l'area di prova pulita a fine prova (il rapporto dice cosa è rimasto), nessun errore di autenticazione.
6. **Telefono** (F80, a mano dall'orchestratore): `s25.ps1 -Mode share` con ≥ 100 interruzioni durante una condivisione di 20-200 media, il verificatore verde (impronte del telefono e area di prova), poi in app nessun elemento fermo e nessuno mancante.
7. Un giro rosso **si rifà** dal suo seme (§E3).
8. I banchi esistenti (Parti A-D) e i test di `core-albums`, `core-nasops` e `core-backup` restano verdi; `tools\check.ps1 -NoDaemon` verde.

## E10. Risultati e tempi

Dopo la correzione di SD-1-SD-4, **senza evitamenti** (27/10/2026, stesso PC, `--max-workers=2`):

| Banco | Giri / interruzioni | Violazioni | Tempo |
|---|---|---|---|
| NAS finto, 2.000 giri (seme di base 20261026) | 2.000 giri, 4.043 interruzioni cadute (2.582 morti del processo), 3.998 passi, 52.782 file riletti con SHA-256 (1,1 GiB), 20.045 eventi guardati; elementi: 6.369 fatti, 1.392 falliti con un motivo | **0** | 89 s |
| simulatore SMB, 600 giri (seme di base 20261027) | 600 giri, 1.336 interruzioni cadute (795 morti del processo), 1.176 passi, 15.498 file riletti (290 MiB), 5.836 eventi guardati; elementi: 1.834 fatti, 471 falliti con un motivo | **0** | 26 s |
| un colpo a ogni chiamata (NAS finto: 541 giri; simulatore SMB: 1.695 giri) | 2.236 giri, 2.190 colpi scattati (949 morti del processo) | **0** | 18 s + 63 s |
| mutazioni (200 giri per errore, seme 4242) | quattro rossi con l'invariante giusto (§E7), sano verde | — | 39 s |
| regressioni di SD-1, SD-2, SD-3 | gli scenari (40, 12 e 12 morti) e i giri 73, 221 e 739 del seme 20261026 | **0** | 1 s |
| NAS vero | **non rifatto** il 27/10 (si rifà a parte) | — | — |

Prima della correzione (26/10/2026, con gli evitamenti del §E6):

| Banco | Giri / interruzioni | Violazioni | Tempo |
|---|---|---|---|
| NAS finto, 2.000 giri (seme di base 20261026) | 2.000 giri, 3.930 interruzioni cadute (2.581 morti del processo), 3.998 passi, 52.724 file riletti con SHA-256 (1,1 GiB), 20.029 eventi guardati dagli osservatori | **0** | ~100 s |
| simulatore SMB, 600 giri (seme di base 20261027) | 600 giri, 1.273 interruzioni cadute (800 morti del processo), 1.176 passi, 15.497 file riletti (306 MiB), 5.830 eventi guardati | **0** | 30 s |
| un colpo a ogni chiamata (NAS finto: 541 giri; simulatore SMB: 1.695 giri) | 2.236 giri, 2.166 colpi scattati (949 morti del processo) | **0** | 21 s + 72 s |
| mutazioni (200 giri per errore, seme 4242) | quattro rossi con l'invariante giusto, sano verde | — | 49 s |
| NAS vero, dal PC (`test` → `test-comune`) | 243 giri, **500 interruzioni cadute** (326 morti del processo, 94 connessioni cadute, 35 sessioni scadute, 33 socket fermi, 12 «spazio finito»; 267 prima della chiamata, 233 dopo) | **0** (nessun giro rosso, nessun login rifiutato) | 18 min 42 s |
| telefono (S25) | **non eseguito** | — | — |

**Il NAS vero, in dettaglio** (giro del NAS `20261026-171309`, seme di base `362981077623100`, casuale come per ogni prova sul NAS; rapporti in `core-albums/build/chaos/share-area-{report,progress,log}-20261026-171309.txt`): 462 passi dell'utente (314 condivisioni di un album nuovo, 56 aggiunte a un album, 47 «riporta nelle mie cartelle», 45 adozioni di file del PC), 1.052 verifiche dopo una morte o a metà e 1.063 a lavoro finito, 326 riavvii e 358 riprese a vuoto, 6.311 file riletti con SHA-256 (142 MiB), elementi: 764 fatti, 112 falliti **con un motivo che il verificatore ammette** (invariante 8: nessun errore permanente per un guasto passeggero), 0 annullati; 206.091 chiamate al NAS passate dalla guardia, nessun tentativo di login rifiutato. Copertura per punto: tutti gli 11 (spostamento 20 colpi scattati, caricamento dal telefono 10, biglietti 14, pubblicatore 72, copia privata del guardiano 4, cartelle d'origine 16, catalogo 11, porta di completamento 29, **diario della condivisione 130 morti**, **diario di `core-nasops` 68 morti**, **chiamate SMB 126, di cui 40 morti**). Un'avvertenza del codice sotto prova compare 11 volte nel registro («catalogo non aggiornato dopo la pubblicazione (ChaosFault): l'elemento aspetta, la sorgente resta»): è la reazione voluta a un colpo sul catalogo, non un errore. Una prima prova più corta (`20261026-170732`: 12 giri, 20 interruzioni, 0 violazioni) ha provato guardia, recinto e pulizia prima della lunga.

**Lo stato del NAS dopo la prova.** Ogni mondo di giro è stato tolto subito dopo il giro (243 mondi, 243 puliti: 1.605 file e 2.757 cartelle tolti in tutto) e la pulizia finale ha trovato la cartella del giro già vuota: «tolti 0 file e 0 cartelle; radice tolta, niente rimasto». **Nelle share `test` e `test-comune` non resta niente di questa prova.** `mcg-it` stessa e i giri di altre prove non sono stati toccati (la guardia lo vieta: la radice di un giro è la sola cartella che il banco può togliere).

**Una nota sul rapporto.** Nel rapporto del NAS vero la riga «eventi guardati dagli osservatori» dice 0: quella prova è stata fatta prima di collegare il contatore alla statistica del giro (gli osservatori guardavano comunque ogni trasferimento, ogni completamento e ogni voce in coda: lo prova `ShareChaosMutationTest`, dove due dei quattro errori si vedono solo da lì). Corretto in `ShareChaosRound`; nei rapporti dei banchi JVM la riga ora conta (20.029 e 5.830 eventi).

Una morte del processo cade in ognuno dei 9 punti dei diari e delle porte del NAS finto e in tutti gli 11 del simulatore SMB (tabella di copertura nel rapporto). Nessun test usa il tempo vero tranne la prova sul NAS vero.

---

# Parte F. La prova delle interruzioni del calcolo dei volti e di "Disattiva e cancella" (Fase 8, P12)

| | |
|---|---|
| **Stato** | **Fatto** (27/10/2026): banchi sulla JVM e sul simulatore SMB verdi a ogni `check.ps1`, mutazioni rosse con l'invariante giusto, prova dal PC sul NAS vero verde (402 interruzioni); il modo `faces` del telefono è pronto e non eseguito (F103) |
| **Dove** | `core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/chaos/` (il banco), `.../faces/sync/chaos/` (il NAS finto e le sessioni: lì sta `NasFileSystem`, che `Phase8ArchitectureTest` ammette in core-faces solo sotto `sync/`), `tools/chaos/faces-pc/` (lo script dal PC e la prova a secco dello script del telefono), `tools/chaos/s25.ps1` (modo `faces`), questo file |
| **Riferimenti** | `docs/fase8-progetto.md` §2.3 (calcolo), §2.4 (formato), §2.8 (cancellazione, i sei invarianti), §4.3, §5.5 (invarianti e interruzioni), §5.6 (F103), riga P12 del §4.4. Parti A, B, D ed E (sopra): questa ne ripete l'impianto (un mondo, un piano di colpi dal seme, un verificatore, un banco che somma i giri, errori introdotti apposta, una prova dal PC contro il NAS vero e un modo di `s25.ps1`) |

Che cosa si prova: **il calcolo dei volti, la loro pubblicazione e replica, il raggruppamento con il custode e "Disattiva e cancella" si possono interrompere in qualunque punto** (processo ucciso a ogni salvataggio, rete tagliata, un guasto a caso su qualunque chiamata, la generazione che cambia a metà, un dispositivo spento che torna dopo) **senza danni**: nessun volto salvato si ricalcola, niente finisce fuori posto, dopo `off|G` niente si pubblica più di `G`, la cancellazione è completa e non esce mai da `faces/`, le istantanee si rileggono uguali, le prenotazioni non si sovrappongono e il lavoro arriva sempre in fondo.

## F1. Che cosa promette

Gli **otto invarianti del §5.5**, controllati dopo ogni morte del processo, a fine di ogni passo e in una rilettura finale (`ChaosVerifier`, `ChaosSnapshots`). Il numero è quello del §5.5:

| N. | Invariante | Come si controlla |
|---|---|---|
| 1 | nessun lavoro perso o rifatto | ogni corpo `faces` che un dispositivo scrive nel suo archivio (`ChaosArchive.saveBody`) si conta per (dispositivo, generazione, contenuto): la seconda volta è un volto salvato e rifatto. I dati locali tolti (cancellazione, esclusione) azzerano il conto. A fine giro ogni media in ambito di ogni dispositivo acceso ha la sua voce o è "non supportato" (8) |
| 2 | nessun dato fuori posto | l'area comune non ha niente sotto `faces/`; ogni tentativo di scrittura di un dispositivo (anche finito con la risposta persa) sta sotto `faces/<G>/<io>/` o sotto le prenotazioni `faces/<G>/leases/`; **prima di ogni `publishExclusive`** lo stato di Persone qui dev'essere `On` attivo, **fresco**, per la generazione del percorso (D30). Una porta dei volti costruita sull'area comune rifiuta senza mandare nessuna richiesta (prova a fine giro) |
| 3 | dopo `off\|G`, nessuna pubblicazione di `G` | un osservatore dello stato di ogni dispositivo (a ogni emissione, anche dentro la coroutine dell'interruttore) segna quante scritture aveva sotto `faces/<G>/` quando ha visto "non più G" (stato spento, in cancellazione, in attesa dell'avviso, di un'altra generazione) e quante ne ha dopo; mentre un dispositivo non può pubblicare non scrive niente sotto `faces/`. Vale anche per un dispositivo spento durante la cancellazione e ripartito dopo |
| 4 | cancellazione completa (i sei invarianti del §2.8) | dopo `LOCAL_DONE` nessun dato locale delle generazioni cancellate, la chiave tolta prima dei file (l'ordine registrato), nessun nome nella cronologia (anche per chi cancella dal gancio di D30); nessuna testa personale "non più G" mentre quel dispositivo ha ancora dati di `G`; un dispositivo che la pagina dice "ha cancellato" non ha davvero più niente; a cancellazione finita nessun file sotto `faces/<G>/` |
| 5 | mai fuori da `faces/` | ogni file tolto dalla cancellazione sul NAS sta sotto `.mycloudgallery/faces/`; i media e i file dell'app messi nell'area privata prima del giro (con il loro SHA-256) ci sono ancora, con gli stessi byte, dopo ogni passo (le "impronte prima e dopo") |
| 6 | istantanee coerenti | un osservatore (un dispositivo nuovo senza stato, la replica vera di P3) rilegge dal NAS le teste di ogni dispositivo: nessuna testa o segmento rifiutato; ogni alias punta a una persona dell'istantanea; tutto ciò che un'istantanea pubblicata dice sta nell'istantanea che l'autore ha salvato con lo stesso numero (le assegnazioni provvisorie di chi non è custode non si pubblicano); l'istantanea vincente non torna mai indietro e **nessun id di persona si perde fra due istantanee vincenti** |
| 7 | prenotazioni | ogni presa riuscita si confronta con chi la teneva e non era scaduta (ora del NAS): una presa di un altro prima della scadenza è una violazione, salvo `Contended`; una chiamata che non torna (guasto, morte) può aver cambiato la prenotazione sul NAS lo stesso e la fa dimenticare al tracciato |
| 8 | il lavoro arriva in fondo | a fine giro (tutti a casa, niente congelamento, il tempo passato oltre le prenotazioni, ogni dispositivo ripartito una volta) ogni dispositivo acceso ha analizzato ogni media in ambito, non ha niente in coda e **tutti scelgono gli stessi corpi vincenti**; ogni cancellazione è `DONE` o in attesa con un motivo dichiarato |
| 10 | (del banco) il codice non lancia | nessuna eccezione esce da `FacesComputeEngine.run`, `DefaultFacesEraser`, `NasFacesPublisher`, `NasFacesSync`, `FacesNasJanitor` per un guasto previsto del NAS o di un file di stato (vedi O1 al §F6 per gli errori del database) |
| 11 | (del banco) la ripresa a vuoto non cambia niente | a lavoro finito il processo di un dispositivo riparte e riprende i lavori dal diario: i file dei volti sul NAS, i diari, i dati su disco, l'outbox e la voce dei dati personali restano identici |
| 12 | (del banco) il banco non cade | un'eccezione uscita dal banco stesso, non dal codice sotto prova, è un difetto del banco da sistemare qui |

La morte del processo di **un** dispositivo è un `Error` (`Death`): nessun `catch (Exception)` della produzione la ferma e, dopo, ogni chiamata dei suoi vecchi oggetti si ferma con la stessa morte finché il banco non ricostruisce quel processo (interruttore, recinto, cancellazione, pubblicatore, replica, prenotazioni, calcolo e raggruppamento nuovi; resta ciò che è "su disco": diario, dati locali, outbox, stato della replica, voce dei dati personali). Un processo che riparte può morire di nuovo all'avvio (un colpo sullo stato locale mentre l'interruttore si assesta): il banco lo fa ripartire finché vive.

## F2. I banchi

| Dove | Che cosa | Quante | Stato |
|---|---|---|---|
| **JVM, NAS finto** | `FacesChaosBenchTest`: giri casuali su 2-3 dispositivi di un account sopra la share in memoria di P3 (`MemoryShare`) con i colpi su ogni richiesta (`ChaosFs`) e la cancellazione stretta con la regola di D31; il calcolo, la pubblicazione, la replica, il raggruppamento e "Disattiva e cancella" sono quelli veri | **2.000 giri**, 2.256 interruzioni cadute (1.389 morti del processo) | verde, 0 violazioni, 135 s |
| **JVM, simulatore SMB** | `FacesChaosSmbBenchTest`: gli stessi giri con la **sessione di produzione** (`SmbNasSessionManager`, una per dispositivo, con il suo trasporto che colpisce a ogni richiesta SMB), le **primitive vere** del NAS (`FacesNasWiring`) e il **vero `NasFacesEraser`** sopra `InMemorySmbBackend` (semantica Samba); il cestino di rete (`vfs_recycle`) è emulato | **600 giri**, 761 interruzioni cadute (468 morti) | verde, 0 violazioni, 45 s |
| **Mutazioni** | `FacesChaosMutationTest`: quattro errori introdotti apposta, 200 giri ciascuno (§F7) | 4 x 200 | verde: rossi con l'invariante giusto |
| **Il verificatore** | `FacesChaosVerifierTest` (ogni invariante rosso su un mondo fatto a mano, un mondo sano verde), `FacesChaosDeterminismTest` (un giro si rifà identico dal seme), `FacesChaosKnownDefectsTest` (O1 e O2, §F6) | 12 + 2 + 5 test | verde |
| **PC, area di prova del NAS vero** | `FacesNasIntegrationTest` modo `run` (`tools/chaos/faces-pc/faces-pc.ps1 -Mode run`): lo stesso motore sulla JVM del PC contro la share `test`, **solo** sotto `mcg-it/<giro>/`, dietro la guardia (§F8); processo ucciso e connessione tagliata a tempi casuali durante pubblicazione, calcolo e cancellazione; l'oracolo rilegge tutto | **400 interruzioni** | verde: 226 giri, 0 violazioni, 59 min 50 s, area di prova pulita |
| **Il codice del NAS vero sul simulatore** | `FacesAreaRunnerTest`: guardia, trasporto recintato, oracolo, pulizia, autotest, runner e verificatore dal manifesto sopra il simulatore, senza rete | 4 test | verde |
| **Telefono, area di prova** | `tools/chaos/s25.ps1 -Mode faces` (§F9) | >= 100 | scritto e provato a secco, **non eseguito** |

File (nel pacchetto `...core.faces.chaos`): `ChaosTypes` (punti, tipi di colpo, statistiche), `ChaosInjector` (i colpi e la morte), `ChaosWraps`/`ChaosLocal`/`ChaosPersonal` (i salvataggi durevoli con la morte e i dati personali), `ChaosNas` (l'interfaccia del NAS: `MemoryChaosNas`, `SmbChaosNas`, `FacesAreaNas`), `ChaosMedia` (i media e i motori finti di P4), `ChaosDevice` (i pezzi veri legati insieme come li lega `:app`), `ChaosWorld`, `ChaosVerifier`/`ChaosSnapshots`, `ChaosPlan`, `ChaosRound`, `ChaosBench`, `ChaosMutants`; per il NAS vero `FacesAreaGuard`, `FacesAreaTransport`, `FacesAreaAdmin`, `FacesArea`, `FacesAreaReader`, `FacesManifest`. Il banco **non nomina** le primitive di scrittura del NAS, `NasFileSystem` fuori da `sync/chaos/`, `java.net` né le conferme di core-nasops: lo vietano i test d'architettura; il NAS di core-nasops si usa solo da `FacesNasWiring.build(sessione, configurazione)`.

## F3. Come si lancia

```powershell
# Tutto sulla JVM, --max-workers=2, DOPO aver aspettato 2 GB di RAM libera (il PC è condiviso; chi lavora in più agenti prende prima un posto con gradle-slot.ps1, -Slot <nome>):
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode jvm        # 2.000 giri sul NAS finto
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode smb        # 600 giri sul simulatore SMB
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode mutants    # i quattro errori introdotti apposta
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode area       # il codice del NAS vero sopra il simulatore: nessuna rete
# Rifare UN giro rosso (il rapporto dice "giro 163, seme di base 20261029"): stesso seme di base, un solo giro, a partire da quell'indice
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 163 -Seed 20261029
# Il NAS vero (serve il file delle credenziali, §F8; 400 interruzioni di norma):
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode run -Interruptions 20 -DryRun
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode run -Interruptions 400
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode clean -Run <giro>      # toglie un giro rimasto dopo una prova interrotta
# Il telefono: vedi §F9. Il piano a secco, l'autotest e la prova contro l'adb finto non vogliono né telefono né adb:
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -Step interrupt -DryRun -Interruptions 100
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -SelfTest
powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\s25-faces-finto.ps1
```

Variabili che i test leggono: `MCG_FACES_CHAOS_ROUNDS` (2.000), `MCG_FACES_CHAOS_SMB_ROUNDS` (600), `MCG_FACES_CHAOS_SEED` (20261029 sulla JVM, 20261030 sull'SMB), `MCG_FACES_CHAOS_FIRST_ROUND`; per cercare i difetti noti (§F6) `MCG_FACES_CHAOS_NO_AVOID=1` (il banco non evita O2) e `MCG_FACES_CHAOS_DB_ERRORS=1` (anche gli errori del database che la produzione non guarda: O1); per spiegare una violazione `MCG_FACES_CHAOS_TRACE=1` (la cronaca fine del giro, mai contenuti); per il NAS vero `MCG_NAS_INTEGRATION=true` (l'interruttore), `MCG_FACES_MODE` (`run`, `clean`, `verify`), `MCG_FACES_INTERRUPTIONS`, `MCG_FACES_SEED`, `MCG_FACES_FIRST_ROUND`, `MCG_FACES_RUN`, `MCG_FACES_MANIFEST`, `MCG_FACES_PHASE`. I rapporti vanno in `core-faces/build/chaos/`: `faces-jvm-report-fake.txt`, `faces-jvm-report-smb.txt`, `faces-area-report-<giro>.txt` (con `faces-area-progress-<giro>.txt` che cresce a ogni giro e `faces-area-log-<giro>.txt` con gli avvisi). Con `gradlew :core-faces:testDebugUnitTest` (e quindi `check.ps1`) i giri sono quelli di default; il NAS vero **non** gira mai da solo (si salta con il motivo se manca l'interruttore o il file).

Un giro rosso si rifà dal suo seme: il seme del giro è `base x 1.000.003 + indice x 7.919 + 17` e il rapporto lo stampa insieme alla cronaca del giro (passi, colpi scattati, morti). `FacesChaosDeterminismTest` prova che due corse con lo stesso seme fanno gli stessi passi e fanno scattare gli stessi colpi; le chiavi di cifratura dei database del banco vengono da un generatore con seme (`SHA1PRNG`), non dalla ora.

## F4. Come funziona un giro

Un **giro** è un account con 2 o 3 dispositivi che condividono la share privata. Ognuno è fatto dai **pezzi veri** legati come li lega `:app` (l'interruttore di P7 con il recinto della cancellazione, la cancellazione locale e quella sul NAS di P8, il pubblicatore e la replica di P3, le prenotazioni, il calcolo di P4, il raggruppamento con il custode di P5) sopra uno stato che sopravvive alla morte: il diario, i dati locali (le chiavi e i database dei volti), l'outbox, lo stato della replica e la voce dei dati personali stanno in oggetti del banco che il processo ucciso non porta con sé (`ChaosLocalData`, `ChaosPersonal`, `ChaosJournal`). I motori di rilevamento e di firma sono i finti di P4 (`FakeFaceEngines`, identità sintetiche 1-3): **nessun dato biometrico**, nessun volto vero, nessun modello.

Il piano di un giro dipende solo dal seme:

1. **Il mondo**: media in comune sul NAS (area privata), qualche media solo sul telefono di un dispositivo, il mese e il tipo (foto, documento, ritaglio) che i finti di P4 sanno leggere, le impronte SHA-256 di tutto ciò che sta nell'area privata e non è dei volti (invariante 5).
2. **I passi** (3-5 sul NAS vero, 3-7 sulla JVM e sul simulatore), scelti a pesi fra quindici tipi:

| Passo | Peso | Che cosa fa |
|---|---|---|
| calcolo | 26 | un dispositivo lavora (tappe di P4: unità di mese, media, firma, corpo salvato, messa in coda) |
| calcolo con la generazione che cambia | 8 | durante il calcolo un altro dispositivo spegne e riaccende Persone: la generazione cambia sotto i piedi del primo |
| giro dei dati e dei volti | 12 | pubblicazione, replica degli altri, raggruppamento, ricalcolo dell'istantanea |
| nome dato a una persona | 6 | un nome (la cronologia dei nomi è un dato personale) |
| media nuovi | 5 | arrivano sul NAS o sul telefono |
| "Disattiva e cancella" | 12 | scelta degli album, stato `off|G`, diario, cancellazione locale (chiave, poi file) e sul NAS, avviso agli altri |
| riattivazione | 5 | Persone riacceso (nuova generazione) |
| dispositivo spento | 4 | un dispositivo sparisce fino a un passo dopo |
| dispositivo che torna | 6 | il dispositivo spento torna e trova la generazione cambiata o la sua cartella cancellata |
| congelamento | 3 | l'area di prova resta ferma (nessuna scrittura) per un passo |
| cestino di rete | 2 | `vfs_recycle` (solo dove c'è, non sul NAS vero) |
| il tempo passa | 6 | scadono le prenotazioni (ora del NAS), le conferme, il lavoro di notte |
| manutenzione notturna | 9 | il guardiano dei volti (`FacesNasJanitor`) |
| esclusione di un dispositivo | 2 | un dispositivo dell'account non vale più |
| morte del processo senza colpo | 4 | il processo di un dispositivo è ucciso fuori da un salvataggio, e riparte |

3. **I colpi** (`Blow`), armati prima di ogni passo e fatti scattare al loro punto: **dove** (`Surface`: chiamata al NAS, cancellazione sul NAS, dati personali, quattro punti del database, outbox, replica, diario, registro, dati locali, generazione che cambia), **quando** (`Moment`: prima o dopo la chiamata, così una risposta persa si prova), **che cosa** (`FaultKind`: rete caduta, sessione scaduta, stallo, spazio finito, **morte del processo**, errore di salvataggio, generazione che cambia). Con `dense=true` (NAS vero) ogni passo ne arma di più. Un colpo non scattato nel passo si disarma.
4. **La morte** è la morte di un dispositivo: un `Error` (`Death`) che nessun `catch (Exception)` ferma, e che dalla chiamata dopo in poi rilancia da ogni vecchio oggetto di quel dispositivo. Il banco **ricostruisce il processo** (`restart()`), che riprende i lavori dal diario e, se muore ancora all'avvio, lo rifà (fino a 12 volte). Sul simulatore SMB la morte è segnata senza lanciare (la sessione classifica le eccezioni), ma le richieste dopo di lei sono rifiutate.
5. **Dopo ogni morte e a fine di ogni passo**: il verificatore guarda gli invarianti 1-7; **a fine giro** tutti i dispositivi tornano, il tempo passa oltre le prenotazioni, la nottata gira, ogni dispositivo riparte una volta e fa un giro finale (30 giri sul NAS vero, 24 sulla JVM) e il verificatore controlla la convergenza (8), la ripresa a vuoto (11: a lavoro finito, un riavvio e una ripresa dal diario **non cambiano niente**, nemmeno un byte: i file dei volti sul NAS, i diari, i dati su disco, l'outbox e la voce dei dati personali) e che ogni cancellazione sia finita o dichiari perché aspetta.

Il giro dopo una `Death` che esce da dove non deve (ad esempio nel codice del banco) è un errore del banco (invariante 12), non una violazione: serve a non nasconderlo.

## F5. Il verificatore

`ChaosVerifier` (invarianti 1-5 e 12), `ChaosSnapshots` (6, 8) e `ChaosLeases`/`ChaosWorld` (7) guardano **il mondo**, non quello che il codice dice di aver fatto: rileggono il NAS con SHA-256, i dati su disco e gli eventi che gli osservatori hanno registrato (le scritture tentate, le prenotazioni, gli stati emessi, l'ordine fra chiave e file). Due scelte contano:

- **Gli osservatori sono a eventi**. L'invariante 3 (dopo `off|G`, niente di `G`) si vede solo se si guarda ogni emissione dello stato e ogni scrittura nel momento in cui avviene; una rilettura del NAS dopo la morte potrebbe non vedere mai il momento sbagliato. Lo stesso per l'invariante 2 (lo stato fresco **prima** di ogni scrittura esclusiva) e per l'ordine chiave-poi-file dell'invariante 4.
- **Il verificatore ha i denti**: `FacesChaosVerifierTest` costruisce a mano un mondo per ogni invariante (un file dei volti nell'area comune, una scrittura nella cartella di un altro dispositivo, una scrittura senza lo stato fresco, un volto salvato due volte, una scrittura dopo `off`, un media cancellato, cambiato o sparito, due dispositivi con la stessa unità, un media non analizzato, una cancellazione non finita e senza motivo) e vuole **solo** quella riga rossa; un mondo sano resta verde (8 turni, 3 dispositivi).

Il verificatore **non si fida del banco per essere verde**: `ChaosSnapshots` rilegge le teste e i segmenti con la replica vera di P3 come un dispositivo nuovo senza stato, quindi un'istantanea che il codice di produzione non sa leggere è una violazione, e controlla che nessun id di persona si perda fra due istantanee vincenti (il `PersonId` è stabile).

## F6. Difetti e osservazioni trovati

Il perimetro di P12 non è il codice di produzione: i difetti **non sono corretti** qui. Ognuno ha il suo scenario senza giri casuali in `FacesChaosKnownDefectsTest` (il comportamento di oggi è verde; l'asserzione giusta è `@Ignore` con il motivo e si riaccende quando il difetto è corretto) e il banco lo **evita** di serie (`ChaosConfig.avoid`) per non essere rosso per un difetto che nessuno corregge in P12. Per ritrovarlo: `MCG_FACES_CHAOS_NO_AVOID=1` (O2) e `MCG_FACES_CHAOS_DB_ERRORS=1` (O1).

**O1. Un errore del database che non è un `IOException` esce dalla tappa del calcolo.** `FacesComputeEngine.run` promette "mai un'eccezione per un caso previsto" e ferma la tappa con un codice (`FacesRunResult.stop`, un `FacesStop`) per un guasto del NAS e per un corpo dei volti che non si salva; ma tre salvataggi non sono guardati allo stesso modo: il segno di unità finita (`FacesUnitsDone.markDone`), la messa in coda del pubblicatore (`FacesPublishStore`) e quella del raggruppamento. Un errore di SQLite (database pieno o bloccato: Room lo lancia come `SQLiteException`, non `IOException`) li fa uscire dalla tappa come eccezione. **Nessun dato si perde** (il corpo è già salvato, e la ripresa lo rimette in coda), ma l'eccezione arriva a chi lancia la tappa: il lavoro di `:app` (P13) deve prenderla (o il motore deve fermarsi con un codice). Seme che lo trova: con `MCG_FACES_CHAOS_DB_ERRORS=1` il banco di serie (seme di base 20261029) lo trova in 33 giri su 2.000, tutti con l'invariante 10; il primo è il 71: `faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 71 -Seed 20261029`. Sul banco di serie è evitato: nel banco il database fallisce solo nei punti dove la produzione lo gestisce (diario e dati locali, `HANDLED_FAILURES`), e solo con `MCG_FACES_CHAOS_DB_ERRORS=1` anche negli altri.

**O2. Un media calcolato dai byte del telefono che poi arriva sul NAS non si pubblica mai.** Una foto solo sul telefono di D0, nello stesso mese di una foto del NAS, si calcola da D0 sui byte locali e **l'unità del mese si segna finita**. Quando il backup riesce e la foto arriva sul NAS (con la sua anteprima), la riga 5 della tabella del §2.3 dice che il corpo calcolato dal telefono va messo in coda per la pubblicazione; ma l'unità è già segnata finita e **nemmeno dopo un riavvio viene più visitata**. Gli altri dispositivi ricalcolano la foto dal NAS e non vedono mai il corpo di D0 (due corpi diversi per lo stesso media, la scelta del vincente non converge fino a una nuova unità). Lo scenario di `FacesChaosKnownDefectsTest.o2_oggiIlCorpoDelSoloTelefonoNonVieneMaiAccodatoEGliAltriScelgonoUnAltroCorpo` lo riproduce senza giri casuali. Seme e giro del banco con `MCG_FACES_CHAOS_NO_AVOID=1`: con `MCG_FACES_CHAOS_NO_AVOID=1` il banco di serie (seme di base 20261029) lo trova nel giro 1393 (seme del giro 20261100814271, invariante 8): `faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 1393 -Seed 20261029`. È un difetto di **convergenza** (invariante 8), non di sicurezza: nessun dato esce dal posto e nessuna cancellazione è toccata.

**Osservazione di modellazione.** L'archivio dei volti finto di P3/P4 tiene **solo l'ultimo nome** per persona: due nomi dati alla stessa persona nello stesso giro lasciavano il primo nella cronologia del banco e facevano credere che la cancellazione non l'avesse tolto. Il banco usa una persona per nome (`world.personIds.next()`): non è un difetto del codice di produzione, è un limite del finto, e il registro dei nomi vero (core-personal) ha la sua prova di cancellazione completa in `core-personal`.

**Gli artefatti del banco corretti lungo la strada** (un giro rosso era sempre prima un dubbio sul banco, poi, se serviva, sul codice): lo stato `Off` che emette l'interruttore **vecchio** fermandosi dopo la morte non è una pubblicazione (`ChaosDevice.stopping`); un errore di salvataggio del registro dei dati personali o di `addPeople` nel codice del banco è preso dal banco, non è del codice sotto prova; un `IO_FAIL` su un salvataggio di cui la produzione non si occupa è limitato a O1; l'invariante 1 si azzera dopo l'esclusione di un dispositivo; il tempo (70 minuti) passa a fine giro perché scadano le prenotazioni; una prenotazione liberata con la risposta persa non si dà per tenuta (`leaseUnknown`); il processo che riparte e muore ancora all'avvio si fa ripartire.

**Nessun altro difetto** è emerso nei 2.000 giri sul NAS finto, nei 600 sul simulatore SMB (con il vero `NasFacesEraser` e le primitive vere) e nella prova sul NAS vero (§F12): né un volto ricalcolato, né una pubblicazione dopo `off|G`, né un file fuori da `faces/`, né una cancellazione incompleta.

## F7. Il banco ha i denti

`FacesChaosMutationTest` introduce quattro errori, uno per promessa di sicurezza, e vuole il banco **rosso con l'invariante giusto**; lo stack sano sugli stessi 200 giri (seme 4242) resta verde. Il codice di produzione non si tocca: ogni errore è un decoratore o un'opzione dei pezzi del banco che riproduce l'**effetto** dell'errore sul codice vero.

| Errore | Dove | Invariante | Esito (200 giri, seme 4242) |
|---|---|---|---|
| **pubblicare senza lo stato fresco** | l'interruttore che il pubblicatore, la replica e le prenotazioni vedono dice sempre "fresco" (`AlwaysFreshSwitch`) | **2**, nessuna pubblicazione senza lo stato fresco (D30) | 127 giri rossi su 200: invariante 2 (139 violazioni) |
| **dichiarare la cancellazione prima di farla** | il gancio prima di pubblicare i dati personali (D30) non cancella più niente: la testa con `off|G` esce con i dati di `G` ancora lì | **4**, nessuna testa "non più G" con i dati di `G` ancora lì | 28 giri rossi su 200: invariante 4 (64 violazioni) e, di riflesso, 11 (6) |
| **togliere i file prima della chiave** | la cancellazione locale toglie i file del database prima di distruggere la chiave (§2.8 passo 6) | **4**, la chiave per prima | 62 giri rossi su 200: invariante 4 (62) |
| **cancellare fuori da `faces/`** | la cancellazione sul NAS toglie, con i file dei volti, un file fuori da `faces/` (un media) | **5**, mai fuori da `faces/`, nessun media toccato | 68 giri rossi su 200: invariante 5 (137) |

Il secondo errore all'inizio non diventava rosso: l'interruttore stesso cancellava i dati prima che il banco guardasse (la cancellazione locale **vera** gira dentro l'interruttore). Per vederlo serviva un evento, non una rilettura: un dispositivo con un file bloccato (`failErase`, il file che il sistema non lascia togliere) negli altri passi di cancellazione lascia i dati di `G` in disco quando il gancio dichiara `off|G`; l'osservatore sulla testa personale (invariante 4) vede l'uscita e il banco diventa rosso. Questo è il motivo per cui gli invarianti 3 e 4 sono a eventi (§F5).

## F8. L'area di prova sul NAS vero e la guardia (§5.4)

Lo stesso banco gira **dal PC contro il NAS di casa** (`FacesNasIntegrationTest`, lanciato da `faces-pc.ps1 -Mode run`): il codice vero del calcolo, della pubblicazione, della replica, del raggruppamento e della cancellazione, con la sessione SMB di produzione e le primitive vere sopra `smbj` (`FacesNasWiring.build`), il socket diretto del PC e l'ora vera. Cambia solo l'ambiente: nessuno spazio da cambiare e nessun cestino di rete (`Capabilities.REAL`), 3-5 passi per giro, colpi più fitti (`dense`), e fra una ripresa e l'altra si aspetta davvero 600 ms perché le attese della sessione (un handle che il NAS non ha ancora chiuso) sono di orologio. Il giro si ferma appena le interruzioni cadute sono quelle chieste (al massimo `4 x n + 5` giri).

**Il recinto** (le stesse regole delle prove 4B e 7, più strette):

- **La guardia** (`FacesAreaGuard`), prima di qualunque connessione: l'account deve essere `test` (non `admin`, `alessio`, `pampa`, `root`, `administrator` in nessuna forma: NFKC, maiuscole), l'area privata esattamente la share `test`, quella comune esattamente `test-comune`, l'host un IP letterale. Un errore nel file delle credenziali **fa fallire** la prova (non la salta) e non allarga il recinto. Le sole share toccate sono `test` e `test-comune`: **`test2`, `NAS`, `Public` e le aree personali sono rifiutate in ogni forma**.
- **Ogni chiamata** passa dal trasporto recintato (`FacesAreaTransport`): controlla la share prima del vero e **radica ogni percorso in `mcg-it/<giro>/r<N>/`** (anche `.mycloudgallery/`: i temporanei e il cestino finiscono lì, non alla radice della share). Un percorso con `..`, assoluto, con un'unità (`C:`) o vuoto lancia `FacesAreaViolation` e non arriva al NAS. Le sole cartelle fuori dalla radice di un giro che si possono creare sono i suoi antenati (`mcg-it`, `mcg-it/<giro>`); la sola che si può togliere è la radice stessa, quando è vuota. **La cancellazione dei volti ("Disattiva e cancella") toglie file e cartelle solo sotto `faces/` del giro**, e il banco lo controlla in più sul NAS vero (invariante 5).
- **L'area comune**: `test-comune` si apre **solo** per provare che l'app non ci scrive niente dei volti (invariante 2): l'oracolo la legge, non la scrive.
- **L'autenticazione**: al primo login rifiutato un fermo scatta e **nessun'altra autenticazione arriva al NAS** (il NAS blocca l'account dopo pochi tentativi); la prova si ferma e lo dice (`stoppedBecause`). Anche tre aperture consecutive fallite fermano tutto (rete assente).
- **L'oracolo** (`FacesAreaAdmin`): una connessione sua, con **un solo login**, attraverso lo stesso trasporto recintato ma senza colpi. Serve a mettere i media di partenza nell'area privata (`test`), a leggere i byte di ogni file e a elencarli (gli invarianti si controllano sul NAS vero, non su quello che il codice dice di aver fatto), a cambiare un file «dal PC». Scrive solo in `test` sotto `mcg-it/<giro>/`; `test-comune` solo la legge.
- **L'autotest** (`FacesAreaSelfTest`) gira prima del primo giro: account e share vietati in ogni forma, percorsi che escono, login rifiutato, e **zero chiamate al vero** per ogni rifiuto (contatore). Con una password sbagliata sul simulatore il banco si ferma al primo rifiuto (`FacesAreaRunnerTest`).
- **Il file delle credenziali** (`%USERPROFILE%\.mycloudgallery\nas-it.properties`) lo legge **solo questo codice, in memoria**: `user`, `password`, `share`, `share2`, `host`, `guid` (facoltativa); le altre chiavi non si leggono nemmeno. `faces-pc.ps1` ne controlla soltanto l'esistenza (`Test-Path`), non lo apre né lo stampa. Il test si salta, con il motivo, se manca l'interruttore (`MCG_NAS_INTEGRATION=true`) o il file: **nessuna connessione prima della guardia**.
- **Log**: senza nomi di file, percorsi, share, account, id né impronte; i rapporti dicono invarianti, numeri e primitive, mai contenuti. Lo stato di un giro rosso non si salva sul NAS vero. Nessun dato biometrico: i motori sono i finti di P4.

**La pulizia.** Ogni giro del banco ha il suo mondo (`mcg-it/<giro>/r<N>/`, i suoi media, la sua cartella dell'app) e lo toglie quando ha finito; a fine prova si toglie la radice del giro e il rapporto dice **quanti file e quante cartelle ha tolto e cosa è rimasto** (`faces-area-report-<giro>.txt`, riga «pulizia»). `mcg-it` stessa e i giri più vecchi (di altre prove) non si toccano: ognuno si pulisce con il suo `-Mode clean -Run <giro>`, e solo se vuole l'utente.

## F9. Il telefono (`tools/chaos/s25.ps1 -Mode faces`)

**Pronto, non eseguito** (niente telefono e niente adb in questo pacchetto: la prova F103 la fa chi ha l'S25, dopo che P13 ha scritto il pannello di debug «Prove della Fase 8», §5.6). Prima, a mano: l'S25 con la build debug e l'account di prova.

```powershell
# 1. la semina: i ritratti e le composizioni di PUBBLICO DOMINIO di tools\fixtures\faces\portraits (da 10 a 200 file, non scaricati dallo script) in mcg-prova,
#    con nomi neutri (ritratto-NN), e il MANIFESTO dei volti (# mcg-faces-manifest 1)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -Step seed -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Seed 103
# 2. nell'app: il backup di mcg-prova (i ritratti vanno sul NAS di prova)
# 3. almeno 100 interruzioni in tre finestre (calcolo, "Disattiva e cancella" in corso, ripresa dopo la cancellazione), poi la verifica
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -Step interrupt -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Seed 103 -Interruptions 100 -SettleSeconds 300 -Verify
#    solo il calcolo: -Window compute;  solo la cancellazione e la ripresa: -Window erase;  tutte e tre (default, pesi 5/3/2): -Window all
# 4. (o a parte, a prova ferma) la sola verifica; -Phase computed (dopo il calcolo) | erased (dopo la cancellazione)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -Step verify -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Phase erased
# 5. la pulizia del telefono (solo mcg-prova e il file di stato)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode faces -Step clean -Serial RFCY61FZDHW -Run 20261030-101500-1a2b3c4d -Yes
```

**Come parla con l'app.** Lo script non tocca l'interfaccia: manda all'app di debug un broadcast mirato al suo pacchetto (`am broadcast -a dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO -p dev.pampa.mycloudgallery.debug --es scenario <nome>`) con i nomi `f103-compute` (attiva Persone sull'account di prova e fa «Analizza ora» sull'area di prova), `f103-erase` («Disattiva e cancella» con la conferma) e `f103-status` (riscrive soltanto il file di stato); e legge l'esito dal **file di stato** (`run-as dev.pampa.mycloudgallery.debug cat files/prove/f8-status.txt`, righe `chiave=valore`: `phase`, `state`, `generation_present`, `local_done`, `keys`, `faces_db`, `erase_done`). I nomi stanno in testa al modo (variabili `$Faces*`): si cambiano in un punto solo se P13 li sceglie diversi.

**Le guardie** (le stesse degli altri modi): un solo telefono, `RFCY61FZDHW` (con un adb finto, `MCG_ADB`, vale il numero che si vuole); solo l'app di debug `dev.pampa.mycloudgallery.debug` (`run-as` non funziona su una release: il modo rifiuta altri pacchetti); il Wi-Fi si spegne **solo** con `svc wifi disable` dello script, al massimo 120 s, e si riaccende **sempre**, anche se lo script è interrotto; da 1 a 500 interruzioni; nessun dato personale (solo ritratti di pubblico dominio, con nomi neutri sul telefono).

**Il passo `verify`** fa tre cose. (1) **I media del telefono non sono cambiati**: le impronte di `mcg-prova` tornano (la funzione dei volti non tocca i media; senza `-AllowFreed` anche un file tolto è un cambiamento, codice 4). (2) **La cartella dell'app, in sola lettura** (`run-as ls` e `cat`): in fase `computed` nessun compagno `-wal`/`-shm`/`-journal` orfano senza il suo `.db` e `faces_db` del file di stato che torna con i `.db` visti; in fase `erased` **nessun file `faces-*` salvo `faces-erase` e `faces-local`** (il diario che si tiene 30 giorni per la pagina, §2.8 passo 11, e lo stato locale), e il file di stato dice `local_done=1`, `keys=0`, `faces_db=0`. (3) **Il verificatore dal PC**, in sola lettura, sull'area di prova: `faces-pc.ps1 -Mode verify -Manifest <manifesto> -Phase computed|erased`. Legge solo la cartella del backup del manifesto e `.mycloudgallery/{faces,tmp}` di `test` e `test-comune` (nessuna primitiva di scrittura per costruzione, un'allowlist di cartelle, un tetto alle dimensioni), e dice, con l'impronta di ogni file letto: niente dei volti nell'area comune, i media del manifesto ci sono e non sono cambiati, la forma dei file sotto `faces/` (teste, segmenti, prenotazioni) e, in fase `erased`, **nessun file sotto `faces/` in nessuna share**. Esce con 4 se qualcosa non torna.

**Provato senza telefono**: `s25.ps1 -Mode faces -SelfTest` (manifesto, file di stato, finestre, guardie) e `tools\chaos\faces-pc\s25-faces-finto.ps1`, che lancia seme, interruzioni, verifica e pulizia contro `tools\chaos\fake-adb\fake-adb.ps1` (un adb finto che imita il telefono in una cartella) e controlla gli esiti, compreso un database orfano e un `faces-1.db` rimasto dopo la cancellazione (che il verificatore deve vedere). Il calendario delle interruzioni dipende solo dal seme.

## F10. Che cosa non copre (e chi lo copre)

- **I legami del banco, non quelli di `:app`.** Il banco lega i pezzi veri (interruttore, cancellazione, pubblicatore, replica, prenotazioni, calcolo, raggruppamento) come li lega `:app`, ma i legami di `:app` (P13: l'`Application`, i lavori di WorkManager, il database Room e la chiave nel Keystore) li scrive P13: **il banco va ripetuto sui legami di `:app`** quando ci sono (stessa classe `ChaosBench`, un altro `ChaosTargetFactory`). Il database è in memoria nel banco (con la stessa interfaccia e le stesse morti ai salvataggi): le migrazioni, il WAL e SQLCipher/Keystore veri sono del telefono (§F9) e di `core-faces` (P6).
- **I dati personali veri.** Il registro dei dati personali (core-personal) e la sua voce sul NAS sono modellati in memoria (`ChaosPersonal`, `ChaosHeads`) con i punti dove cade la produzione (salvataggio del registro, pubblicazione della testa); le proprietà del registro vero (firma, replica, cronologia dei nomi) sono dei suoi banchi.
- **Il telefono.** `s25.ps1 -Mode faces` non è eseguito (§F9). Dal PC non si legge il diario della cancellazione né il database del telefono: `verify` controlla i nomi dei file, il file di stato e l'area di prova sul NAS, non gli invarianti 1, 3, 6, 7 (quelli sono dentro il processo dell'app: li guardano il banco sulla JVM e la schermata della pagina Persone). Per questo la prova F103 chiede anche uno sguardo all'app dopo la verifica.
- **Il NAS vero senza cestino e senza spazio finito.** Sul NAS vero non c'è `vfs_recycle` e lo spazio non si cambia: il banco di quella prova non usa «cestino di rete» né «spazio finito» (`Capabilities.REAL`). Li provano i 2.000 giri sul NAS finto e i 600 sul simulatore SMB (che emula il cestino come Samba).
- **Il tempo vero e le ore.** Sulla JVM l'ora è finta (le prenotazioni, le conferme, la nottata avanzano a comando); sul NAS vero i tempi sono quelli veri, ma una prova dura minuti, non ore.
- **Gli errori del database fuori dai punti guardati (O1)** e **il corpo del solo telefono (O2)**: il banco li evita di serie (§F6).
- **L'architettura.** Il banco non nomina le primitive di scrittura del NAS (`*NasFileOps*`, la cancellazione distruttiva di core-nasops), `NasFileSystem` fuori da `faces/sync/` (le sole copie sono in `faces/sync/chaos/`, sotto la sua cartella) né `java.net`: lo vietano `Phase8ArchitectureTest` e `Phase4ArchitectureTest` (in `core-common`). Quello che viene colpito è la sessione e il trasporto; le primitive vere una per una sono del banco di core-nasops (Parte A).
- **Il pannello di debug dell'app e i suoi scenari** (`f103-*`) sono di P13; qui c'è il loro contratto con lo script.

## F11. Che cosa conta come superata

1. **JVM**: 2.000 giri sul NAS finto e 600 sul simulatore SMB, **zero violazioni** (evitando solo O1 e O2, §F6).
2. **Mutazioni**: i quattro errori sono rossi con l'invariante giusto (§F7), lo stack sano sugli stessi semi è verde.
3. **Il verificatore**: ogni invariante ha il suo caso rosso e il mondo sano è verde; un giro si rifà dal suo seme (`FacesChaosDeterminismTest`).
4. **NAS vero** (dal PC): 400 interruzioni cadute su `test` sotto `mcg-it/<giro>/`, zero giri rossi, nessun errore di autenticazione, l'area di prova pulita a fine prova (il rapporto dice cosa è rimasto).
5. **Telefono** (F103, a mano dall'orchestratore): `s25.ps1 -Mode faces` con >= 100 interruzioni nelle tre finestre, `verify` verde nelle due fasi, poi in app nessun volto da ricalcolare e, dopo «Disattiva e cancella», nessun dato dei volti né sul telefono né sul NAS.
6. I banchi esistenti (Parti A-E) e i test di `core-faces`, `core-nasops` e `core-personal` restano verdi; `tools\check.ps1 -NoDaemon` verde.

## F12. Risultati e tempi

27/10/2026, stesso PC, `--max-workers=2`, **senza evitamenti diversi da O1 e O2** (§F6):

| Banco | Giri / interruzioni | Violazioni | Tempo |
|---|---|---|---|
| NAS finto, 2.000 giri (seme di base 20261029) | 2.000 giri, 2.256 interruzioni cadute (1.389 morti del processo, 422 connessioni cadute, 120 sessioni scadute, 121 stalli, 16 «spazio finito», 117 salvataggi falliti, 71 cambi di generazione; 1.259 prima della chiamata, 997 dopo), 10.027 passi, 1.384 verifiche dopo una morte o a metà e 12.027 a lavoro finito, 1.384 riavvii e 2.539 riprese a vuoto, 44.444 file riletti con SHA-256 (24 MiB), 8.442 istantanee rilette, 16.741 corpi dei volti salvati, 851 cancellazioni partite, 38.306 prenotazioni viste | **0** | 135 s |
| simulatore SMB, 600 giri (seme di base 20261030) | 600 giri, 761 interruzioni cadute (468 morti), 3.013 passi, 467 verifiche dopo una morte e 3.613 a lavoro finito, 780 riprese a vuoto, 13.867 file riletti (7,7 MiB), 2.581 istantanee, 5.057 corpi salvati, 257 cancellazioni, 11.763 prenotazioni viste | **0** | 45 s |
| mutazioni (200 giri per errore, seme 4242) | quattro rossi con l'invariante giusto (§F7), lo stack sano verde | — | 72 s |
| verificatore, determinismo, difetti noti | 12 + 2 + 5 test (due `@Ignore` con il motivo: O1 e O2 giusti) | **0** | 1 s |
| il codice del NAS vero sul simulatore | `FacesAreaRunnerTest`, 4 test | **0** | 1 s |
| NAS vero, dal PC (`test`, sotto `mcg-it/<giro>/`) | 226 giri, **402 interruzioni cadute** (245 morti del processo, 91 connessioni cadute, 23 sessioni scadute, 20 stalli, 21 salvataggi falliti, 2 cambi di generazione; 248 prima della chiamata, 154 dopo) | **0** (nessun giro rosso, nessun login rifiutato) | 59 min 50 s |
| telefono (S25) | **non eseguito** | — | — |

Ogni cella è un conteggio del rapporto del banco; i rapporti dei due banchi JVM sono scritti a ogni corsa in `core-faces/build/chaos/`.

**Il NAS vero, in dettaglio** (giro del NAS `20261027-131021`, nome in UTC; seme di base `434813905792900`, casuale come per ogni prova sul NAS; rapporti in `core-faces/build/chaos/faces-area-{report,progress,log}-20261027-131021.txt`): 886 passi dell'utente (166 calcoli, 135 giri dei dati e dei volti, 101 riattivazioni, 77 «Disattiva e cancella», 63 manutenzioni notturne, 61 «il tempo passa», 61 arrivi di media nuovi, 51 morti senza colpo, 47 congelamenti, 41 calcoli con la generazione che cambia, 39 nomi, 34 dispositivi spenti, 7 esclusioni, 3 ritorni), 242 verifiche dopo una morte o a metà e 1.112 a lavoro finito, 242 riavvii e 243 riprese a vuoto, 5.162 file riletti con SHA-256 (2,8 MiB), 768 istantanee del NAS rilette, 1.580 corpi dei volti salvati, 3.858 prenotazioni viste (0 contese); **253.808 chiamate al NAS passate dalla guardia**, nessun tentativo di login rifiutato. Copertura per punto (colpi scattati, di cui morti): chiamate al NAS 145 (52), dati personali sul NAS 52 (11), database: corpo dei volti 9 (7), istantanea 1 (1), persone 24 (24), unità finite 3 (3), outbox 14 (14), stato della replica 1 (1), diario della cancellazione 45 (31), registro dei dati personali 51 (51), stato locale 57 (50). Sul NAS vero la cancellazione non ha una porta a parte (sono richieste SMB come le altre, contate nelle chiamate al NAS) e non c'è lo «spazio finito» (`Capabilities.REAL`). Una prima prova più corta (`20261027-125847`: 19 giri, 20 interruzioni, 12 morti, 0 violazioni) ha provato guardia, recinto e pulizia prima della lunga.

**Lo stato del NAS dopo la prova.** Ogni mondo di giro è stato tolto subito dopo il giro (226 mondi, 226 puliti: 5.162 file e 5.419 cartelle tolti in tutto; nella prova corta 19 mondi, 410 file e 513 cartelle) e la pulizia finale ha trovato la cartella del giro già vuota: «tolti 0 file e 0 cartelle; radice tolta, niente rimasto». **Nelle share `test` e `test-comune` non resta niente di questa prova.** `mcg-it` stessa e i giri di altre prove non sono stati toccati (la guardia lo vieta: la radice di un giro è la sola cartella che il banco può togliere). Nessun dato biometrico è stato scritto: i motori sono i finti di P4.

**I due difetti, ritrovati con un seme.** Con `MCG_FACES_CHAOS_NO_AVOID=1` il banco di serie (seme di base 20261029, 2.000 giri) trova **O2** in un giro, il 1393 (seme del giro 20261100814271, invariante 8: i corpi vincenti di D1 non sono quelli di D0), e lo rifà da solo: `MCG_FACES_CHAOS_NO_AVOID=1` e `faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 1393 -Seed 20261029`. Con `MCG_FACES_CHAOS_DB_ERRORS=1` trova **O1** in 33 giri su 2.000 (tutti con l'invariante 10, `ChaosDbFault` esce dal codice sotto prova), il primo è il 71 (seme del giro 20261090345353): `MCG_FACES_CHAOS_DB_ERRORS=1` e `faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 71 -Seed 20261029`.

**Un'avvertenza sul rapporto.** Nel rapporto del NAS vero la riga «punti dove nessun colpo è scattato» elenca anche «cambio di generazione», benché 2 cambi siano scattati (contati fra le interruzioni): il cambio di generazione è un *tipo* di colpo, non un *punto*, e il rapporto lo contava male. Corretto in `ChaosStats.neverHit` dopo la prova (che non è stata rifatta): nei rapporti nuovi non compare più, e per la cancellazione sul NAS dice perché non ha colpi (sul simulatore SMB e sul NAS vero non ha una porta a parte).

Una morte del processo cade in ognuno dei 12 punti del banco sul NAS finto e negli 11 del simulatore SMB (tabella di copertura nel rapporto). Nessun test usa il tempo vero tranne la prova sul NAS vero.
