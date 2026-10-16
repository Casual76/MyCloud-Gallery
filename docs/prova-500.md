# La prova delle 500 interruzioni (Fase 4B, P15)

> **Parte A**: le operazioni sui file del NAS (Fase 4B, P15), qui sotto. **Parte B**: il caricamento del **backup** (Fase 5A, P6), in fondo a questo file (da "Parte B").

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
| **Stato** | 16/10/2026 (P6). Il banco JVM gira a ogni `check.ps1` sul NAS finto con **due motori**: l'esecutore di riferimento di P0 (`InMemoryBackupEngine`) e il **motore vero** di P2 (`SafeUploadEngine`, con la deduplica vera di P3): **2.000 giri ciascuno, circa 4.000 interruzioni, zero violazioni, 6 secondi a motore**. Sul **simulatore SMB** con la sessione di produzione e le primitive vere del backup (`SmbNasBackupOps` sopra le primitive SMB della 4B): **600 giri a motore, circa 1.200 interruzioni, zero violazioni**, 5 secondi. Cacce lunghe con altri semi (20.000 giri a motore sul NAS finto, 3.000 sul simulatore SMB, circa 180.000 interruzioni in tutto): zero violazioni. Il banco ha trovato **un difetto vero** del motore (F1, §B6), dichiarato e non nascosto. Il runner contro l'area di prova del NAS vero e lo script del telefono sono **scritti e provati a secco / sul simulatore**; **mai lanciati sul NAS vero né sul telefono**. |
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
| 12 | Il motore non lancia | un'eccezione uscita da `run`/`resumeAll` per un guasto del NAS o del telefono è una violazione (contratto di §1.2) |

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

**F1: un elemento resta aperto per sempre se la foto sparisce mentre il MediaStore cambia versione** (motore di riferimento e motore vero, stesso codice in `refreshPhone`). Scenario: un elemento del diario è aperto (`PLANNED`, o in caricamento con il suo temporaneo), la foto viene cancellata dal telefono e il MediaStore si ricostruisce (`_ID` rinumerati, E-P12). L'`_ID` dell'elemento è di una versione vecchia (`PhoneLookup.Stale`); la lettura del telefono ha già tolto la riga dal catalogo, quindi `BackupCatalog.phoneRefOf` risponde `null`. Il motore (`refreshPhone`: "la riga nuova dal catalogo, o si aspetta che la lettura del telefono la ritrovi") **aspetta**, e se la foto è cancellata la lettura non la ritroverà mai: l'elemento resta `PLANNED` (o `UPLOADING`, con il temporaneo che nessuno toglie) invece di diventare `GONE` ("persa prima del backup", E-B4). Senza il cambio di versione il motore risponde bene (`Gone` → `GONE`). Conseguenze: la pagina Backup dice "in attesa" per sempre, un temporaneo di un video a metà resta (citato dal diario, quindi la pulizia non lo tocca). Nessun file a metà, nessun doppione, nessuna perdita: sono violati solo l'invariante 9 e, per il temporaneo, il 6 "a lavoro finito". Frequenza nel banco: ~7% dei giri (è raro nella realtà: serve una cancellazione e una ricostruzione del MediaStore nella stessa finestra).
  **Dichiarato, non nascosto**: come i `knownDefects` della 4B, il banco riconosce lo scenario con un segno preciso (`ItemFact.waitsForRow`: elemento non finito, riga `Stale`, `phoneRefOf` null), non lo conta come violazione (`Invariant.KNOWN_GAP`) e lo scrive sempre in testa al rapporto con il suo conteggio ("DIFETTO NOTO F1"). `BackupChaosKnownGapTest` lo riproduce in modo deterministico e controlla che il banco lo classifichi giusto **qualunque cosa faccia il motore**: se il motore lo corregge il test stampa "F1 corretto: togliere il difetto noto dal banco". Correzione proposta (P2, in `refreshPhone` di `SafeUploadEngine` e di `InMemoryBackupEngine`): `phoneRefOf == null` con la riga `Stale` → `gone(item, lost = true)`; il catalogo non risponde `null` finché la lettura del telefono non ha deciso che la riga non c'è più (prima restituisce quella vecchia), quindi non si perde un caricamento per una lettura non ancora fatta. Quando è corretto, si tolgono `ItemFact.waitsForRow`, il ramo `KNOWN_GAP` del verificatore e `BackupChaosKnownGapTest`.

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
1. **JVM**: 2.000 giri su ciascuno dei due motori (riferimento e vero) sul NAS finto e 600 sul simulatore SMB, **zero violazioni** degli invarianti 1-12 (`check.ps1` lo verifica a ogni giro). Il difetto noto F1 si dichiara; non è una violazione, ma non scompare dal rapporto finché non è corretto.
2. **Mutazioni**: `BackupChaosMutationTest` rosso con l'invariante giusto per tutte e quattro, su entrambi i motori, e verde per lo stack sano.
3. **PC, area di prova**: ≥ 400 interruzioni sul NAS vero, **zero giri rossi**, nessun errore di autenticazione, area ripulita dalla suite di core-nas; i tre file di rapporto (`backup-area-report-*.txt`, `-progress-*.txt`, `-log-*.txt`) in `core-backup/build/chaos/`.
4. **Telefono**: ≥ 100 interruzioni (`kill -9` e Wi-Fi) durante il primo backup di `mcg-prova`; a prova ferma il telefono non è cambiato e `area.ps1 -Mode backup -Stage verify` dice "TUTTI GLI INVARIANTI RISPETTATI" (nessun file a metà, nessun doppione, nessuna perdita, nessun temporaneo rimasto); in Diagnostica ogni elemento `DONE`, `LINKED_EXISTING` o `GONE`.
5. Un giro rosso **si rifà** dal suo seme e **si verifica da solo** dal suo stato salvato: non basta che sia rosso, deve essere spiegabile.

I risultati sul campo (NAS vero e telefono) si aggiungono qui sotto quando la prova è fatta.

## B12. Tempi

2.000 giri a motore sul NAS finto: ~6 secondi (circa 40.000 file riletti con SHA-256, ~1,1 GB). 600 giri a motore sul simulatore SMB: ~5 secondi. L'intero pacchetto di test del banco (banchi, mutazioni, verificatore, determinismo, runner dietro la guardia): ~40 secondi. Le cacce lunghe: 20.000 giri a motore ~1 minuto, 3.000 giri sul simulatore SMB ~25 secondi. Nessun test usa il tempo vero: le attese del motore (un file aperto dal PC) fanno avanzare un orologio finto, e nessun test dorme. Il runner contro il NAS vero dipende dai file grandi: i giri normali durano secondi, quelli con un video da 200 MB un paio di minuti, uno da 3 GB una ventina di minuti (scrittura, rilettura di verifica, rilettura finale).
