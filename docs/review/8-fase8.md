# Revisione di sicurezza della Fase 8 (Persone: rilevamento e riconoscimento dei volti, raggruppamento, nomi, formato firmato dei volti, "Disattiva e cancella", ricerca per persona, album smart con persone)

| | |
|---|---|
| **Oggetto** | i commit della Fase 8 su `main` da `cc3d021a` (progetto tecnico) a `e9086269`: P0-P13 con le integrazioni, la correzione O1/O2/O3 (`ffba1424`, `2c72422a`, `c64f0b68`) e, del pacchetto "Avvio e pagine istantanei" (merge `127631ac`), solo cio' che tocca il montaggio di Persone e il recinto dei volti. Moduli: `core-faces` (`compute/`, `erase/`, `grouping/`, `people/`, `store/`, `switch/`, `sync/`), `feature-people`, le parti dei volti di `core-ai` (`faces/`, `runtime/ModelInputs`, asset `assets/faces/`), `core-security` (`faces/`), `core-database` (`faces/`), `core-trust` (`FacesDocs`, `FacesBodies`, `FacesCluster`, `FacesPeopleDocs`, `FacesOwnFiles`), `core-nasops` (`faces/`), `core-search` (ricerca per persona, cronologia), `core-albums` (album smart con persone), `:app` (`faces/`, `di/FacesModule`, `work/FacesWork`, `graph/LocalData`, `MyCloudGalleryApp`, Diagnostica), `tools/check.ps1` (passi 7d-ter, 7f, 7g). **Escluso** il resto della Fase 9. |
| **Riferimenti** | `docs/fase8-progetto.md` (D1-D39, §2.8, §6, risposte Q1-Q5 del 27/10), `docs/decisioni.md` (sezioni "Fase 8 ..." fino a "correzione O1 e O2 (e O3)", e "Avvio e pagine istantanei"), `docs/sicurezza.md` (C37-C60, C63, C64, C85-C90, C95-C101, C105-C113, C116-C120, C130-C137, C145, C191; L94-L99, L111, L117, L136-L139, L190-L196, L220-L226; checklist "Fase 8: persone"), `docs/formato-nas.md` (`faces/`, §5.11), `docs/review/7-fase7.md` (formato). |
| **Attaccanti e guasti** | (1) un **dispositivo compromesso** dello stesso account o di un altro familiare: dati dei volti firmati falsi o ostili, gruppi e nomi avvelenati, volume, rollback; (2) la **privacy fra familiari**: volti e nomi solo nell'area privata di chi attiva (proposta 13, E-F4), niente verso area comune, indice condiviso, log, Diagnostica, esportazione; (3) **"Disattiva e cancella"** (E-F6): nessuna traccia su NAS e dispositivi, recinto P8 prima di ogni apertura, morte del processo a meta', dispositivo spento che torna, riattivazione; (4) i **Nascosti** (E-H1) in Persone, pagine persona, copertine, ricerca per persona, album smart, suggerimenti; (5) gli **ingressi ostili** (immagini malformate, modelli alterati, vettori non finiti); (6) lo **storage locale** (cifratura P6, chiavi, `StateFiles`, azzeramento); (7) le **recinzioni nella release** (D12, D30, D34, F4-D31); (8) i **guasti** (morte del processo, rete); (9) l'**avvio istantaneo** (Persone montata dopo il primo disegno, C131 e recinto P8). |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice. Niente Gradle, nessun telefono, nessun `adb`, nessun NAS, nessuna rete. |
| **Data** | 30/10/2026 |
| **Metodo** | Lettura del codice nel worktree `rev8/sicurezza` a `e9086269`, seguendo ogni via che porta dati da un altro dispositivo o dal NAS fino a cio' che l'utente vede o a un'azione sui file, e ogni file che l'app scrive o toglie. Due revisori di supporto, in sola lettura, hanno letto in parallelo: (a) formato firmato, replica e pubblicazione dei dati dei volti; (b) Nascosti, ricerca per persona, album smart con persone, Diagnostica e log. Il revisore principale ha letto "Disattiva e cancella" (diario, passi locali e sul NAS, pulizia di ogni dispositivo, gancio di D30), l'interruttore, il recinto, la cifratura (chiavi del Keystore, negozi, colonne sigillate), i motori e i loro ingressi, il montaggio e l'avvio istantaneo, le recinzioni. **Ogni voce riportata qui e' stata ricontrollata nel codice**; per ognuna si dice se e' confermata (dal codice) o plausibile (dipende da un comportamento del NAS, della piattaforma o da un attacco che qui non si prova). Nessun test eseguito. Nessun file del repository modificato a parte questo documento. |
| **Stato** | **Aperta**: nessuna voce e' ancora corretta. La proposta dei pacchetti e' in fondo ("Pacchetti di correzione proposti"). |

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 0 | – |
| Media | 5 | R1 una generazione dei volti che `faces-local` non ricorda resta sul disco per sempre, e il dispositivo "conferma" lo stesso la cancellazione · R2 la cronologia delle ricerche tiene i nomi rinominati, dimenticati o non latini, e sugli altri dispositivi la pulizia non si riprova · R3 le voci `people` portano l'autore che sceglie chi le scrive: la revoca non le toglie, "Dimentica" non torna indietro e le persone si gonfiano fino a fermare il custode · R4 una `seq` dell'istantanea vicina al tetto ferma il custode per sempre, anche dopo la revoca · R5 un file illeggibile ferma il giro della replica dei volti, e i segmenti elencati e assenti si richiedono a ogni giro |
| Bassa | 8 | R6 nessun tetto complessivo ai dati dei volti di un dispositivo: crescita su piu' teste, memoria e tempo del raggruppamento · R7 file tolti, aggiunti o letti male dal PC: segmenti mai ripubblicati, spazzatura in `head/` che blocca, rifiuti per sempre · R8 il cancello D35 si chiede solo all'inizio della pubblicazione · R9 un nome che la normalizzazione svuota spegne la ricerca per persona di tutto l'account; nomi "invisibili" · R10 album smart con persone: "non so" vale "nessuno" e le righe automatiche si tolgono · R11 dopo "Disattiva e cancella" restano nella memoria del processo nomi e raggruppamento, e sul disco la forma della griglia delle persone · R12 il foglio dice "Fatto ovunque" anche quando non lo e' (cancellazione solo locale, niente da cancellare) · R13 un file dei volti tenuto aperto dal PC tiene la cancellazione "in corso" senza motivo, con un giro al minuto |
| Informativa | 8 | I1-I8 (sotto) |

**Confermate e plausibili.** Delle 13 voci R, **10 sono confermate** nel codice e **3 sono plausibili**: R5 (dipende da come `SmbNasFileSystem` traduce un'ACL o un file aperto in esclusiva), R6 (memoria e tempi: stime), R10 (l'innesco: un Keystore che non risponde a meta' valutazione). In quattro voci confermate una parte e' plausibile e lo si dice: R1 (l'innesco: una scrittura di `faces-local` non riuscita prima di una morte del processo, o un file illeggibile), R3 (il superamento di 10.000 persone e' un calcolo), R11 (la corsa sulla cache dei vettori), R13 (che SMB segnali il file aperto come `Locked`). R3-R8 le ha trovate il revisore (a); R2 (in parte), R9, R10 e R11 (in parte) il revisore (b); R1, R12, R13, la parte di R2 sugli altri dispositivi e la parte di R11 su `FencedArchives` il revisore principale. Tutte sono state ricontrollate nel codice dal revisore principale.

**"Disattiva e cancella" regge sul dispositivo che cancella e sul NAS; cede ai margini.** Il diario si scrive prima di tutto, la chiave si distrugge per prima, il recinto chiude le aperture di una generazione nominata dal diario, la parte locale non chiede il NAS, la cancellazione sul NAS e' stretta (per generazione e motivo, solo area privata, regola di core-nasops), la pulizia di ogni dispositivo (`FacesNasJanitor`) toglie comunque le generazioni non correnti, il gancio di D30 fa uscire `off|G` solo dopo la cancellazione locale. I punti deboli: gli **altri dispositivi** cancellano solo la generazione che `faces-local` ricorda (R1) e i nomi della cronologia che le voci di adesso conoscono (R2); la **memoria del processo** e una forma della pagina restano (R11); il foglio **dice di piu'** di quello che e' successo (R12).

**I dati dei volti non escono dall'area privata dell'account.** Nessuna via trovata verso l'area comune, l'indice condiviso, gli album condivisi, i log, la Diagnostica, l'esportazione, le notifiche o i nomi dei lavori; nei dati personali gli album smart portano solo id casuali, mai nomi. I Nascosti reggono su tutte le superfici delle persone (fonte unica che fallisce chiusa, cancello prima di ogni ritaglio).

**Il formato firmato regge sulla forma, non sul contenuto di un dispositivo dell'account.** Firme, tetti prima di allocare, vettori finiti e normalizzati, isolamento per account e generazione, anti-rollback: tutto regge. Un dispositivo **compromesso dello stesso account** pero' scrive voci `people` a nome di un altro (R3), fissa una `seq` che ferma il custode (R4), e gonfia corpi e persone (R3, R6); dal PC si ferma o si rallenta la replica (R5, R7). Con la recinzione D34 accesa nella release, tutto questo oggi tocca solo le share di prova.

---

## Risposte al perimetro

1. **Dispositivo compromesso (dati dei volti firmati falsi o ostili, gruppi e nomi avvelenati, volume, rollback).**
   - **Codec**: busta con tetto prima della copia, magic, versione 4 legata al tipo, firma ECDSA canonica, poi il JSON (`core-trust/.../envelope/Envelope.kt:151-175`); firmatario approvato o dismesso con testa congelata, mai revocato o perso; stesso account del firmatario; campi sconosciuti rifiutati; famiglia, dispositivo, account, generazione e numero legati al posto; ora oltre +2 minuti rifiutata; rollback rifiutato (`core-trust/.../data/FacesDocs.kt:205-261`). Testa: al piu' 8.192 segmenti ordinati e unici, 12 MiB ciascuno, 512 MiB in tutto (`FacesDocs.kt:70-85`). Segmenti: impronta e dimensione prima del parsing, `N` e lunghezze nei tetti prima di allocare (`FacesDocs.kt:338-367`). Vettori: 128 componenti finite, norma fra 0,99 e 1,01 (`FacesBodies.kt:65-85`).
   - **Isolamento**: si leggono solo i dispositivi approvati e dismessi **dell'account** (`core-faces/.../sync/FacesSyncGuard.kt:71-74`, `NasFacesSync.kt:133-136`); i segmenti solo dalla cartella del firmatario, con percorsi costruiti da id validati (`NasFacesRemote.kt:91-94`); la share comune e' rifiutata da ogni chiamata (`NasFacesRemote.kt:52-54`).
   - **Revoca**: i corpi hanno come autore il firmatario e si tolgono (`NasFacesSync.kt:338`, `:199-208`); le voci `people` no, se dichiarano un altro autore (R3).
   - **Azioni sui file**: nessuna voce `people`, istantanea o corpo produce un'azione su un file o su un media; le sole cancellazioni sul NAS sono quelle strette di `core-nasops`.
   - **Restano**: R3, R4, R5, R6, R7, R8, I5.
2. **Privacy fra familiari.**
   - Pubblicazione solo con `publishExclusive` sotto `faces/<G>/<io>/` dell'area privata (`NasFacesRemote.kt:96-110`), dietro `FacesSyncGuard.check` (registro, area privata diversa dalla comune, D34, stato attivo e fresco: `FacesSyncGuard.kt:44-63`) e `AppFacesWriteGate` (`app/.../faces/FacesPorts.kt:86-99`); stesse regole per le prenotazioni (`NasFacesLeases.kt:70-76`) e la pulizia dei propri file (`FacesOwnFilesCleaner.kt:121`).
   - Log, Diagnostica, esportazione: solo classi d'eccezione, codici e numeri; `PeopleDiagnostics` con chiavi dell'elenco chiuso (`ExportKeys.kt:93-95`); nomi dei lavori costanti (`app/.../work/FacesWork.kt:56-67`); nessuna notifica delle persone.
   - Album smart con persone: nei dati personali solo id base32 (`core-albums/.../smart/SmartRulesText.kt:118`); la condivisione di uno smart porta solo le foto, la regola resta personale (`core-albums/.../contract/Share.kt:40-41`).
   - Backup: `allowBackup=false`, `data_extraction_rules.xml` esclude tutto; database, chiavi avvolte e file di stato dei volti in `noBackupFilesDir`.
   - **Restano**: la forma della griglia delle persone (numeri) fuori dal database cifrato (R11), i `toString()` di default di alcuni tipi con nomi (I4).
3. **"Disattiva e cancella".**
   - **Ordine**: diario prima di tutto (`DefaultFacesEraser.kt:134-136`), segno `erasing` e stop (`:166-170`), album e cronologia con tre tentativi (`:172-181`, L190), voce `off|G` (`:183`, `:191-201`), dati locali di tutte le generazioni sul disco con la chiave per prima (`:203-212`, `FacesStores.kt:275-285`), poi annuncio, NAS, verifica, conferme, fine (`EraseRemote.kt:44-98`). La parte sul NAS e' per generazione e motivo, solo area privata, dietro D34 e D35, mai la generazione corrente, mai un file che il catalogo conosce come media (`core-nasops/.../faces/NasFacesEraser.kt:79-90`, `:138-156`, `:179-230`).
   - **Recinto P8**: ogni apertura (archivi, stato della replica e del pubblicatore, negozio del calcolo) passa da `FacesEraseFence` (`app/.../faces/FacesFences.kt:38-77`, `core-faces/.../erase/FacesLocalErase.kt:31-60`): una generazione si apre solo se attiva qui e non nominata dal diario, e mai durante una cancellazione (stesso lucchetto). Nel montaggio la lettura delle persone parte un attimo prima che il diario chiuda il recinto (I1): oggi senza danni.
   - **Morte del processo**: ogni passo e' idempotente e segnato nel diario; un diario scritto e non ricordato si rilegge (`DefaultFacesEraser.kt:296-310`); con il segno `erasing` e il diario perso si ricostruisce (`:265-279`); la cancellazione dei file non si interrompe (`NonCancellable`). Una morte fra la chiave distrutta e i file tolti fa nascere alla ripresa una chiave nuova e un `FACES_KEY_LOST` falso (I6).
   - **Dispositivo spento che torna**: il gancio (C131) tiene fermi i dati personali finche' le persone non sono montate e c'e' un database dei volti sul disco (`app/.../faces/FacesSupport.kt:52-63`); montate, l'interruttore cancella prima di pubblicare (`FacesPrePublishHook.kt:22-26`, `DefaultFacesSwitch.kt:288-306`). Il pubblicatore dei volti non scrive senza lo stato fresco (C90). **Cede** se `faces-local` non ricorda la generazione sul disco (R1) e per i nomi della cronologia (R2).
   - **Riattivazione**: non durante la parte locale (`DefaultFacesSwitch.kt:104`); una generazione nuova casuale; una voce `on|G` di una generazione cancellata che ricompare si riafferma `off` (`EraseRemote.kt:46-51`). L'attivazione toglie solo `dataGeneration` (R1).
   - **Restano**: R1, R2, R11, R12, R13, I1, I6.
4. **Nascosti.** Un solo filtro (`core-faces/.../people/PeopleVisibility.kt:25-31`: visibili, poi `hiddenAmong`, poi solo celle dell'istantanea, nulla senza istantanea) che fallisce chiuso (`core-library/.../visibility/CatalogVisibility.kt:33-43`, `:66-74`); ci passano conteggi, copertine, pagine, proposte, revisione, dizionario e celle della ricerca e degli smart (`ArchivePeople.kt:139-248`, `:300-330`; `app/.../faces/FacesPeoplePorts.kt:58-82`); il cancello prima di ogni ritaglio, anche con l'immagine in cache (`feature-people/.../crops/GuardedPeopleCrops.kt:28-31`); la riga Persone del viewer salta le persone nascoste e dimenticate e controlla il riferimento (`ArchivePeople.kt:197-204`). Le finestre di dato vecchio dopo un "nascondi" sono brevi e senza ritagli (I3).
5. **Ingressi ostili.**
   - Immagini: tetto dei byte (4 MiB), intestazione letta senza decodificare, solo JPEG e WebP, dimensioni dichiarate uguali a quelle lette, decodifica gia' ridotta a 2.048 px, software, sRGB, `OutOfMemoryError` preso (`core-ai/.../runtime/ModelInputs.kt:96-119`, `:150-178`).
   - Modelli: dimensione e SHA-256 a ogni apertura, interprete sul descrittore verificato mappato (`core-ai/.../faces/FaceEnginesFactory.kt:46-80`); forma di ingressi e uscite controllata o `MODEL_UNAVAILABLE` (`FaceLiteRtSessions.kt:49-118`); LiteRT solo in `FaceLiteRtSessions` (passo 7f di `tools/check.ps1` sul dex di release), asset con impronta (passo 7g).
   - Uscite: valori non finiti scartati, al piu' 5.000 candidati all'NMS e 64 volti per immagine (`YuNetDecoder.kt:112`, `:154`, `:179`; `YuNetDetector.kt:117`).
   - Nessun problema trovato.
6. **Storage locale.**
   - Chiave: AES-256 nel Keystore per generazione (StrongBox se c'e'), chiave dati avvolta in `noBackupFilesDir/faces-keys/`, mai creata senza hardware nella release (`allowSoftwareKey = BuildConfig.DEBUG`, `app/.../graph/AppPlatform.kt:147-148`); distrutta per prima, poi il file avvolto (`core-security/.../faces/FacesKeyStore.kt:225-236`); "non risponde" distinto da "sparita" (`:137-177`).
   - Database: vettori, landmark e nomi sigillati con `aad` della riga, un nome in chiaro rifiutato in lettura (`core-faces/.../store/PeopleTables.kt:67-90`); struttura dei gruppi in chiaro come dichiarato (L111, I8).
   - Azzeramento: le persone si fermano per prime, poi tutte le chiavi dei volti, poi tutti i database e i file di `FacesWiring.FILES` (`app/.../graph/LocalData.kt:156-159`, `FacesGraph.kt:279-287`); le chiavi dei volti vanno via con le credenziali nell'autocancellazione (`AppGraph.kt:296-306`).
   - **Restano**: R1 (generazioni sul disco che nessuno ricorda), R11.
7. **Recinzioni nella release.** D34 e' la prima domanda del cancello dei volti (`FacesPorts.kt:86-99`) e vale per replica, pubblicazione, prenotazioni, cancellazione e pulizia sul NAS; anche la lettura e' chiusa (`FacesSyncGuard.kt:55`); `check.ps1` la prova nel bytecode (7d-ter: decisione, risposta, chi istanzia e chi chiama). F4-D31 non ferma i volti per progetto: decide D34. D12 e D30 non sono toccati dalla fase. La recinzione D33 (`ShareFence`) e' solo debug. Nessun problema trovato.
8. **Guasti.** Errori del database nel giro (O1/O2) presi in `sync` e `flush` senza far avanzare lo stato (`NasFacesSync.kt:89-103`, `NasFacesPublisher.kt:139-150`); la coroutine dell'interruttore non cade (O3, `DefaultFacesSwitch.kt:202-209`, `:273-281`); i giri dei lavori sono dentro `guarded` (`FacesGraph.kt:289-296`). **Restano**: un errore su un file ferma il giro (R5), un file aperto tiene ferma la cancellazione (R13), residui O1/O2 senza effetti (I5).
9. **Avvio istantaneo.** `FacesGraph` nasce in `Application.onCreate` (`MyCloudGalleryApp.kt`, `facesLazy.get()`) prima di `startDeferred` e di `nas.start()`; il gancio di C131 si mette in `FacesWiring.build` (`FacesWiring.kt:397-401`) e prima del montaggio tiene fermo il giro con un database dei volti sul disco. Prima del montaggio nessun archivio si apre (tutte le aperture passano da `FacesLive`). Il recinto regge (I1 sull'ordine). Il pacchetto aggiunge una forma ricordata della pagina Persone (R11).

---

## Media

### R1. Una generazione dei volti che `faces-local` non ricorda resta sul disco per sempre, e il dispositivo "conferma" lo stesso la cancellazione

- **Gravita':** media. E' la promessa centrale di E-F6 ("nessuna traccia su nessun dispositivo") per la categoria di dati piu' delicata: la pagina di chi cancella arriva a "Fatto ovunque" mentre su un altro dispositivo dell'account restano `faces-<G>.db` e la sua chiave. L'innesco e' raro, il danno no.
- **Dove:**
  - `core-faces/.../switch/DefaultFacesSwitch.kt:288-306` (`settleLocked`): l'allineamento con la voce dell'account guarda **solo** `FacesLocal.dataGeneration` (`:291`); senza quel segno risponde `Done` senza guardare il disco;
  - `:308-312` (`eraseStale`, all'attivazione) e `:174-182` (`excludeThisDevice`, `:180`): stessa regola, solo `dataGeneration`;
  - `core-faces/.../store/FacesStores.kt:118-124`: `eraseAllExcept` (tutte le generazioni sul disco tranne la corrente) dice nel KDoc di servire `FacesSwitch.reconcile`, ma **non ha chiamanti** nel codice di produzione;
  - `core-faces/.../switch/FileFacesLocalStore.kt:20-26`, `:41-48`, `:63-83`: una scrittura non riuscita lascia la memoria nuova e il file vecchio (`unsaved`), al riavvio vale il file; un file rovinato o di un'altra versione vale `FacesLocal.EMPTY`;
  - `app/.../faces/FacesSupport.kt:52-63`: **prima** del montaggio il gancio di C131 tiene fermo il giro se c'e' un qualunque database dei volti sul disco; **dopo**, decide `FacesPrePublishHook` (`core-faces/.../switch/FacesPrePublishHook.kt:22-26`) con `settleLocked`, che il disco non lo guarda: il gancio montato e' piu' largo di quello non montato;
  - per contrasto, il dispositivo che cancella usa `generationsOnDisk()` (`core-faces/.../erase/DefaultFacesEraser.kt:203-212`).
- **Scenario:**
  1. Il tablet B accetta l'avviso (`markNoticed(G)`, `DefaultFacesSwitch.kt:336-346`); la scrittura di `faces-local` non riesce (spazio finito) e il processo muore prima di un `flush` riuscito; il calcolo ha intanto scritto `faces-G.db`. Al riavvio `faces-local` dice "nessun dato" (oppure il file e' rovinato).
  2. Dal telefono A l'utente fa "Disattiva e cancella". B legge `off|G`: `settleLocked` non trova `dataGeneration`, risponde `Done`; il gancio lascia uscire la testa personale con `off|G`.
  3. A conta B fra i dispositivi che hanno cancellato (`EraseRules.confirms`) e la pagina dice "Fatto ovunque".
  4. `faces-G.db` e la chiave `mcg-faces-G` restano su B fino all'azzeramento. Anche "Non su questo dispositivo" (F94) e una nuova attivazione (`eraseStale`) li lasciano.
- **Confermato:** si', il percorso nel codice (nessuna strada dell'interruttore guarda le generazioni sul disco; `eraseAllExcept` senza chiamanti). L'innesco (scrittura non riuscita e morte del processo, o un file illeggibile) e' plausibile e raro.
- **Correzione proposta:**
  1. in `settleLocked`, `eraseStale` ed `excludeThisDevice` togliere **ogni** generazione di `generationsOnDisk()` diversa da quella che la voce nomina accesa e che questo dispositivo tratta (con `dataGeneration` come caso particolare), passando da `FacesLocalErase` (cronologia prima, chiave per prima, sotto il recinto);
  2. il gancio montato non risponde `Proceed` finche' sul disco resta una generazione che la voce non nomina accesa (come quello non montato);
  3. un test: `faces-local` assente con `faces-G.db` e chiave presenti, poi `off|G` dagli altri: nessuna testa personale finche' i file ci sono, poi niente sul disco; lo stesso per l'esclusione e per l'attivazione.

### R2. La cronologia delle ricerche tiene i nomi rinominati, dimenticati o non latini, e sugli altri dispositivi la pulizia non si riprova

- **Gravita':** media. C101 e C117 promettono che "Disattiva e cancella" toglie dalla cronologia le ricerche con i nomi, "anche i nomi vecchi", e la checklist chiede "cronologia ripulita": in casi comuni (un nome corretto, una persona dimenticata) il nome resta nelle ricerche recenti e nei suggerimenti.
- **Dove:**
  - `core-faces/.../erase/EraseFacts.kt:105-106` (`EraseRules.names`): i nomi sono quelli delle voci `people` **di adesso**; il database tiene una riga per chiave (soggetto, campo), quindi dopo "Rinomina" o "Dimentica" (la lapide del nome, `PeopleEditor`) il nome di prima non c'e' piu' (`core-faces/.../store/PeopleTables.kt:31-58`, `:67-76`);
  - `core-search/.../history/HistoryNames.kt:17-18` e `core-search/.../contract/SearchText.kt:14-18`, `:38`: le parole si normalizzano con `[^a-z0-9]+` -> spazio; un nome di soli caratteri non latini o simboli ("李明", "Мария", un emoji) diventa vuoto e si scarta;
  - `core-faces/.../erase/FacesLocalErase.kt:79-84`: sugli **altri** dispositivi (cancellazione dal gancio di D30, esclusione, cambio di generazione) i nomi si leggono una volta sola; un Keystore che non risponde in quel momento da' "nessun nome", poi la chiave si distrugge e i nomi non si leggono piu': nessun ritentativo e nessun segno (L190 copre solo il dispositivo che cancella);
  - "Nascondi persona" e "Dimentica" non toccano la cronologia: il nome resta nelle recenti e nei suggerimenti (E-F28 chiede che una persona nascosta non si proponga).
- **Scenario:** cerco "Giulia al mare", poi correggo il nome in "Giulia R."; oppure chiamo una persona "李明" e la cerco; poi "Disattiva e cancella": "Giulia al mare" e la ricerca con "李明" restano nelle ricerche recenti di ogni dispositivo.
- **Confermato:** si'.
- **Correzione proposta:**
  1. la cronologia ricorda per ogni voce nata da un chip "Persona" l'id della persona (mai il nome fuori dal testo gia' salvato) e la cancellazione toglie per id, oltre che per nome;
  2. a ogni "Rinomina", "Dimentica" e "Nascondi" si tolgono dalla cronologia le voci con il nome di prima;
  3. normalizzazione Unicode dei nomi (`\p{L}\p{N}`, la stessa di R9);
  4. sugli altri dispositivi tre tentativi di lettura dei nomi e, se non si leggono, un segno nella Diagnostica (L190 esteso).

### R3. Le voci `people` portano l'autore che sceglie chi le scrive: la revoca non le toglie, "Dimentica" non torna indietro e le persone si gonfiano fino a fermare il custode

- **Gravita':** media. Contro L99 ("un dispositivo compromesso dello stesso account ... la revoca lo toglie") e L136. Solo dentro l'account del dispositivo compromesso; niente media toccati, ma nomi, persone nascoste e dimenticate dell'utente restano falsi dopo la revoca, e il raggruppamento condiviso si puo' fermare.
- **Dove:**
  - `core-faces/.../sync/FacesFusion.kt:46-55` (`PeopleRules.verdict`): basta un autore fra i dispositivi dell'account non revocati; nessun confronto con il firmatario del segmento;
  - `core-faces/.../sync/NasFacesSync.kt:344-352` e `FacesWire.kt:126`: la voce entra con l'autore dichiarato nel documento;
  - `core-faces/.../store/PeopleTables.kt:48-50` (`dropAuthors`): la revoca toglie per autore;
  - `core-trust/.../data/FacesPeopleDocs.kt:63-66` (`fits`): `forget` vale solo `true`, `alias` e `born` non ammettono lapide;
  - `core-faces/.../grouping/PassCorrections.kt:50-51`: ogni id nominato da `born`, `is` o `alias` diventa una persona;
  - `core-trust/.../data/FacesDocs.kt:406-411` (`checkSnapshot`): oltre `MAX_SNAPSHOT_PERSONS` (10.000) l'istantanea e' `TOO_LARGE`; `FacesWire.snapshotEntries` la dice non pubblicabile.
- **Scenario:**
  1. Il telefono D1, approvato e compromesso, pubblica voci firmate da lui con `author = D2` (il tablet onesto): nomi offensivi, `hidden`, `forget` su persone vere, 5.000 `alias` verso id casuali (circa 1 MB).
  2. Gli altri dispositivi le adottano e le ripubblicano nello stato unito (sono "di D2").
  3. L'admin revoca D1: `dropAuthors({D1})` non le trova; nomi e persone nascoste restano; `forget` non si annulla (la persona resta dimenticata, i suoi volti ignorati).
  4. Le persone nate dagli alias superano 10.000: il custode non pubblica piu' istantanee e la vista resta provvisoria.
- **Confermato:** si' (autore non legato al firmatario, revoca per autore, niente lapide). Il superamento delle 10.000 persone e' un calcolo, non provato.
- **Correzione proposta:**
  1. salvare con ogni voce la **provenienza** (il firmatario del segmento che l'ha portata) e toglierla alla revoca anche per provenienza, oppure accettare un autore diverso dal firmatario solo per una voce identica (chiave, valore, ora) gia' ricevuta da quel suo autore;
  2. una lapide anche per `forget` (un "Ricorda di nuovo") e per `alias`, se l'utente lo vuole (decisione);
  3. creare persone solo per id che un'istantanea o un volto nominano, e un tetto alle voci `people` dell'archivio, non solo per testa;
  4. riscrivere L99 e L136.

### R4. Una `seq` dell'istantanea vicina al tetto ferma il custode per sempre, anche dopo la revoca

- **Gravita':** media. Un dispositivo compromesso dell'account, con una sola testa, ferma la pubblicazione delle istantanee per tutti; se ne esce solo con "Disattiva e cancella" e una generazione nuova.
- **Dove:**
  - `core-trust/.../data/FacesDocs.kt:47-51` (`SnapshotMark`): `seq` ammessa fino a `TrustLimits.MAX_VERSION` (10^9), senza nessun legame con la `seq` di prima;
  - `core-faces/.../sync/NasFacesSync.kt:451-457` (`winningSnapshot`): vince la `seq` piu' alta fra i dispositivi dell'account;
  - `core-faces/.../grouping/CustodianGrouping.kt:144-147` (il custode si riallinea sulla vincente) e `:173-178` (chi non e' custode ne prende il numero);
  - `core-faces/.../grouping/GroupingPass.kt:30` (`newSeq = seq + 1`) e `core-faces/.../sync/FacesWire.kt:157-162` (`SnapshotMark(10^9 + 1)` lancia: `snapshotEntries` null, non pubblicabile).
- **Scenario:** D1 pubblica un'istantanea valida con una persona e `seq = 1.000.000.000`. Tutti la adottano; il passo successivo del custode vale 10^9 + 1, che il formato rifiuta: nessuna istantanea esce piu'. Dopo la revoca di D1 la sua istantanea sparisce, ma lo stato locale salvato resta a 10^9.
- **Confermato:** si'.
- **Correzione proposta:** chi legge accetta una `seq` al piu' di `seq locale + N` (o un salto massimo per testa) e conta il rifiuto; un tetto della `seq` molto sotto `MAX_VERSION`; alla revoca di chi aveva scritto l'istantanea vincente, il custode riparte dal proprio stato con una `seq` ammessa; un test con un'istantanea a 10^9.

### R5. Un file illeggibile ferma il giro della replica dei volti, e i segmenti elencati e assenti si richiedono a ogni giro

- **Gravita':** media. Si ferma o si rallenta l'arrivo dei dati dei volti degli altri dispositivi dell'account; la variante con l'ACL si fa dal PC con la sola password dell'account. Come R2 della Fase 7.
- **Dove:**
  - `core-faces/.../sync/NasFacesRemote.kt:52-59`: ogni `IOException` di una chiamata vale `UNREACHABLE`;
  - `core-faces/.../sync/NasFacesSync.kt:160-163` (testa) e `:278` (segmento): `UNREACHABLE` e' fatale (`isFatal`, `:459-462`) e chiude il giro;
  - `:282-285`: un segmento `NotFound` si conta e si salta, senza memoria e senza costo; il ciclo lo ferma solo il budget di tempo.
- **Scenario:**
  1. Dal PC una testa o un segmento di un dispositivo riceve un'ACL che nega la lettura (o resta aperto in esclusiva): ogni giro che arriva a quel dispositivo si ferma li'; la rotazione dei dispositivi limita il danno, ma quelli dopo non si leggono in quei giri.
  2. Un dispositivo compromesso pubblica una testa valida che elenca 8.192 segmenti mai scritti: ogni lettore fa 8.192 richieste SMB a ogni giro, fino al budget di tempo.
- **Confermato:** il percorso si'; che un'ACL o un file aperto arrivino come `IOException` e non come `NotFound` e' plausibile (dipende da `SmbNasFileSystem`).
- **Correzione proposta:**
  1. dopo l'errore su un file, una `list` di controllo: se il NAS risponde, quel file si riprova con attese crescenti e il giro continua;
  2. ricordare in modo durevole (dispositivo, impronta) dei segmenti assenti fino alla testa successiva, con attese crescenti;
  3. un tetto alle richieste per giro, oltre a quello dei byte;
  4. un test con un segmento illeggibile e uno assente su due dispositivi.

---

## Bassa

### R6. Nessun tetto complessivo ai dati dei volti di un dispositivo: crescita su piu' teste, memoria e tempo del raggruppamento

- **Dove:** `core-faces/.../sync/NasFacesSync.kt:321-326` (le voci si contano solo sui segmenti della testa corrente: teste successive con chiavi di contenuto nuove si sommano nell'archivio); `core-faces/.../store/GroupFaceCache.kt:55-67` (`loadAll` legge tutti i volti e **solo dopo** confronta con `MAX_FACES`); `core-faces/.../grouping/Realign.kt:53-54` (per ogni persona orfana un filtro su tutte le assegnazioni dell'istantanea: persone x assegnazioni); fra i corpi di uno stesso contenuto vince la ricetta piu' alta (`FaceBody.winner`).
- **Scenario:** un dispositivo compromesso pubblica a ogni testa corpi con chiavi di contenuto inventate (fino a 32 MiB per giro e lettore): in poche ore milioni di righe; `groupFaces()` e `people()` le caricano tutte prima del tetto e il processo cade a ogni passo; un'istantanea con 10.000 persone orfane costa circa 10^9 confronti a ogni giro incrementale. La revoca toglie i corpi (autore = firmatario), non il tempo perso.
- **Confermato:** il codice si'; memoria e tempi sono stime (plausibile).
- **Correzione proposta:** un tetto per autore e totale (righe e byte) anche in lettura; `loadAll` a pagine con il tetto durante la lettura; le assegnazioni indicizzate per persona in `Realign` e un tetto ai riallineamenti per giro; i corpi di contenuti che il catalogo non conosce non si tengono oltre un tetto.

### R7. File tolti, aggiunti o letti male dal PC: segmenti mai ripubblicati, spazzatura in `head/` che blocca, rifiuti per sempre

- **Dove:**
  - `core-faces/.../sync/NasFacesPublisher.kt:187`: un segmento gia' nell'ultima testa non si rimette mai; contro L138 ("i suoi dati arrivano quando il suo autore ripubblica");
  - `core-faces/.../sync/FacesHeadSeries.kt:163-186` e `:226-229`: oltre `MAX_JUNK_RUN` file non validi di fila un lettore nuovo non trova la testa vera e il proprietario non pubblica piu' (`Blocked`); i file non firmati dal proprietario non li toglie la sua pulizia (`core-trust/.../data/FacesOwnFiles.kt`, `NOT_SIGNED_BY_ME`);
  - `core-faces/.../sync/NasFacesSync.kt:287-298`: una lettura `TooLarge` o con impronta diversa segna il segmento come fatto per sempre, anche se era una lettura corta o un file sostituito e poi rimesso.
- **Scenario:** dal PC si toglie un segmento: chi non l'aveva letto non lo avra' mai; si mettono 40 file in `faces/<G>/<D2>/head/`: D2 non pubblica piu' e un dispositivo nuovo non vede le sue teste.
- **Confermato:** si'. Si perde solo cio' che si ricalcola; nessun media.
- **Correzione proposta:** ogni N giri il pubblicatore confronta `seg/` con la propria ultima testa e rimette cio' che manca (`publishExclusive` e' gia' sicuro); il proprietario toglie i file non validi della propria `head/`; per impronta o dimensione sbagliate 2-3 tentativi con attesa prima del rifiuto definitivo.

### R8. Il cancello D35 si chiede solo all'inizio della pubblicazione

- **Dove:** `core-faces/.../sync/NasFacesPublisher.kt:131` (`gate.blockFor` una volta per `flush`); `:189` e `:196` (`guard.stillOpen` ricontrolla registro, D34, stato e generazione, `FacesSyncGuard.kt:44-67`, ma non congelamento, emergenza, salto dell'ora e fiducia dell'indice).
- **Scenario:** un congelamento o un arresto d'emergenza che scattano durante una pubblicazione lunga (molti segmenti) non fermano i segmenti rimasti ne' la testa.
- **Confermato:** si'.
- **Correzione proposta:** `gate.blockFor` prima di ogni segmento e prima della testa; lo stesso per le prenotazioni (`NasFacesLeases`).

### R9. Un nome che la normalizzazione svuota spegne la ricerca per persona di tutto l'account; nomi "invisibili"

- **Dove:**
  - `core-faces/.../contract/PeopleEdits.kt:22-27` ammette "李明", "Мария", un emoji; `app/.../faces/FacesPeoplePorts.kt:58-61` costruisce `PersonEntry(PeopleDictionary.normalize(name), ...)` e `core-search/.../contract/People.kt:54-56` lancia per un nome normalizzato vuoto (`SearchText.normalize` tiene solo `[a-z0-9]`);
  - `core-search/.../engine/LocalSearchEngine.kt:200-207`, `LibrarySuggestions.kt:114-120`, `core-albums/.../smart/RuleSmartNameInterpreter.kt:115`: l'eccezione diventa `PeopleDictionary.EMPTY`: **tutti** i nomi spariscono dalla ricerca, dai suggerimenti e dai titoli degli smart;
  - `core-trust/.../codec/TextRules.kt:13`, `:28-35`: ZWJ e i selettori FE0E/FE0F sono ammessi; un nome fatto solo di loro passa e appare vuoto (lo puo' scrivere anche un dispositivo compromesso, R3).
- **Scenario:** l'utente chiama una persona con un emoji o in cirillico: da quel momento nessun nome funziona nella ricerca.
- **Confermato:** si'. Fallisce chiuso: nessuna fuga, solo la funzione persa.
- **Correzione proposta:** saltare (`mapNotNull`) le voci con nome normalizzato vuoto; normalizzazione Unicode per i nomi (come in R2); almeno un carattere visibile in `PersonNames.normalize` e `PeopleForms.isName`.

### R10. Album smart con persone: "non so" vale "nessuno" e le righe automatiche si tolgono

- **Dove:** `core-faces/.../people/ArchivePeople.kt:307-309` (`core.current() ?: return emptyMap()`: un archivio che adesso non si apre, per esempio con il Keystore che non risponde, vale "nessuna foto"); `app/.../faces/FacesPeoplePorts.kt:64-65`; `core-albums/.../smart/SmartPeople.kt:44-51` (insiemi vuoti = nessuna classe); `core-albums/.../smart/RulesSmartEngine.kt:77`, `:113` (la disponibilita' si chiede solo all'inizio) e `:147-151` (il commento dice "un'eccezione sale: il motore non scrive niente", ma qui non sale niente).
- **Scenario:** il Keystore non risponde per un momento, o l'utente esclude il dispositivo a meta' valutazione: la valutazione toglie le righe automatiche dell'album con persone (dati personali, sincronizzati sugli altri dispositivi dell'account); tornano al giro dopo. Nessuna fuga.
- **Confermato:** il percorso si'; l'innesco e' plausibile. Come R17 della Fase 7.
- **Correzione proposta:** `contentWith` e le celle rispondono con un errore (o null) quando l'archivio non si legge; `classify` lo propaga; il motore non scrive; un test con l'archivio che non si apre a meta' valutazione.

### R11. Dopo "Disattiva e cancella" restano nella memoria del processo nomi e raggruppamento, e sul disco la forma della griglia delle persone

- **Dove:**
  - `app/.../faces/FacesWiring.kt:193-195`: `clearMemory` toglie solo le copertine ritagliate;
  - `core-faces/.../people/PeopleCore.kt:39` (`loaded`, con le voci `people` decifrate, nomi compresi, e il raggruppamento) e `ArchivePeople.kt:94`, `:122-130` (`proposalMemo`; con la disponibilita' spenta `refresh` svuota la lista ma non `loaded` ne' `proposalMemo`);
  - `app/.../faces/FacesFences.kt:40-50`: `FencedArchives.last` tiene l'ultimo archivio aperto e `forget()` non ha chiamanti;
  - `core-faces/.../store/GroupFaceCache.kt:50-53`: `clear()` senza il lucchetto; un `faces()` in corso puo' rimettere i vettori in chiaro nella cache di un archivio ritirato (plausibile);
  - `feature-people/.../list/PeopleInstant.kt:30-38` e `PeopleListScreen.kt:101` (avvio istantaneo): quante persone con nome e senza nome, in `instant-shapes` fuori dal database cifrato; la toglie solo l'azzeramento, e dopo la cancellazione la pagina disegna per 1,5 s lo scheletro con i numeri di prima.
- **Scenario:** dopo "Disattiva e cancella" il processo resta vivo: nomi e assegnazioni restano nell'heap finche' il processo non muore (raggiungibili da un dump della memoria con il telefono sbloccato); la forma resta sul disco.
- **Confermato:** si' (la corsa sulla cache e' plausibile). Solo memoria e numeri.
- **Correzione proposta:** `ArchivePeople.release()` (azzera `loaded`, `proposalMemo` e la lista) chiamata da `clearMemory`; `FencedArchives.forget()` dalla cancellazione locale; `GroupFaceCache.clear` sotto il lucchetto e `faces()` che controlla l'archivio chiuso; `InstantShapes.remove("persone")` alla cancellazione e quando Persone non e' attiva.

### R12. Il foglio dice "Fatto ovunque" anche quando non lo e'

- **Dove:** `feature-people/.../erase/FacesEraseController.kt:91` (`NothingToErase` -> `Done`) e `:111-114` (anteprima con impronta 0 -> `Done`); `app/.../faces/FacesScreenPorts.kt:60-65` (persone non montate: `NoFacesEraser`); `core-faces/.../erase/DefaultFacesEraser.kt:239-242` (`finishLocalOnly`: con la recinzione D34 la cancellazione e' finita dopo la parte locale); `feature-people/src/main/res/values/strings_faces_erase.xml:66-67` ("Fatto ovunque", "cancellati dal NAS e da tutti i tuoi dispositivi").
- **Scenario:** oggi, nella release sulle share vere (D34 accesa), "Disattiva e cancella" e' locale e il foglio lo dice prima ("Le persone sono solo su questo dispositivo: si cancellano qui"), ma alla fine dice "cancellati dal NAS e da tutti i tuoi dispositivi". Lo stesso testo esce, senza che sia stato cancellato niente, se lo stato cambia fra la riga delle Impostazioni e il tocco (voce dell'account non capita: `NothingToErase`).
- **Confermato:** si'.
- **Correzione proposta:** un esito "Fatto su questo dispositivo" per `localOnly`; per `NothingToErase` e l'anteprima vuota un testo "Niente da cancellare qui" che non parla del NAS ne' degli altri dispositivi.

### R13. Un file dei volti tenuto aperto dal PC tiene la cancellazione "in corso" senza motivo, con un giro al minuto

- **Dove:** `core-nasops/.../faces/NasFacesEraser.kt:200-225` (`Locked`, `SizeChanged`, `NotAFile` -> riprova, esito `BUDGET`; lo stesso per un file sotto `faces/<G>/` che il catalogo conosce come media, `:197-200`); `core-faces/.../erase/EraseRemote.kt:142-146` (un giro che non toglie niente esce senza un motivo); `app/.../faces/FacesGraph.kt:250-255` (senza motivo: `ERASE_SOON`, un minuto).
- **Scenario:** il PC tiene aperto un segmento della generazione cancellata: il lavoro riparte ogni minuto con 3 minuti di budget e un cammino dell'albero, finche' il file resta aperto; la pagina dice "in corso" senza dire perche'.
- **Confermato:** il percorso si'; che SMB lo segnali come `Locked` e' plausibile. Come I2 della Fase 7.
- **Correzione proposta:** un giro che non toglie niente diventa un'attesa dichiarata (`EraseWait.NAS`, o un motivo nuovo "un file e' aperto sul NAS") con attese crescenti; la Diagnostica conta i file trattenuti.

---

## Informative

- **I1. Nel montaggio la lettura delle persone parte prima che il diario chiuda il recinto.** `FacesWiring.kt:292-305` costruisce `ArchivePeople`, che nel suo `init` lancia subito `refresh(reload = true)` su un altro thread (`ArchivePeople.kt:106-108`) e quindi apre l'archivio dal recinto; `DefaultFacesEraser`, che legge il diario e chiama `fence.block` (`DefaultFacesEraser.kt:94-103`), nasce dopo (`FacesWiring.kt:332-354`), contro C130 ("prima di qualunque apertura"). Oggi senza danni: dopo una parte locale finita `clearMarker` riporta `faces-local` a vuoto (nessun avviso visto, stato non attivo) e una cancellazione a meta' ha ancora i dati. Solo se `ensureMarker` non si e' scritto (`DefaultFacesEraser.kt:227-230`, un `attempt`) e un dispositivo vecchio riscrive `on|G`, quella finestra potrebbe aprire `G` con una chiave nuova e un database vuoto (poi tolto). Allo stesso modo C131 regge per l'**ordine** in `MyCloudGalleryApp.onCreate` (`facesLazy.get()` prima di `startDeferred` e di `nas.start()`), non per costruzione. Proposta: costruire la cancellazione (o leggere il diario nel costruttore del recinto) prima di `ArchivePeople`, e un test sul montaggio con un diario a meta'.
- **I2. Un chip "Persona" gia' in Cerca continua a valere dopo "Nascondi persona".** `feature-search/.../SearchController.kt` rilancia la stessa domanda (ordine, "mostra meno probabili"); `core-search/.../engine/PeopleGate.kt:32-40` e `ArchivePeople.contentWith` non guardano le persone nascoste (il dizionario si'): finche' la domanda resta in memoria la persona nascosta torna nei risultati. Proposta: in `PeopleGate` filtrare i chip sul dizionario di adesso.
- **I3. Finestre di dato vecchio dopo un "nascondi".** Lista e pagina persona si rifanno con `debounce(250 ms)` (`ArchivePeople.kt:107-113`) e le proposte sono ricordate per `seq` dell'istantanea (`:250-253`): durante una scansione che cambia spesso l'istantanea, nome, conteggio e griglia possono restare un attimo; i ritagli no (cancello vivo, `GuardedPeopleCrops.kt:28-31`).
- **I4. `toString()` di default con nomi o id.** `HistoryEntry` (`core-search/.../contract/Search.kt:323`), `SearchNotice.TypoCorrected` (`:86`, il campo `to` puo' essere un nome), `SectionEntry` e `SectionData` (`feature-people/.../section/PeopleSections.kt:51`, `:63`), `PersonDestination` e `ReviewDestination` (`feature-people/.../nav/PeopleNavigation.kt:35`, `:39`). Oggi nessun log li stampa (D37, C120).
- **I5. Residui di O1/O2 senza effetti.** `NasFacesSync.latest()` e `winningSnapshot()` (`:440`, `:455`) e `CustodianGrouping.closedSafe` prendono solo `IllegalStateException`, e `NasFacesPublisher.flush` apre l'archivio fuori dal `try` (`:138`): nessuna caduta, perche' `FacesGraph.guarded` prende tutto (`FacesGraph.kt:289-296`). `plan()` salta in silenzio corpi e voci che non si aprono (`NasFacesPublisher.kt:252-263`) e pubblica una testa piu' stretta; gli altri tengono le copie gia' lette.
- **I6. Una ripresa dopo la morte fra "chiave distrutta" e "file tolti" fa nascere una chiave nuova.** `StoresLocalData.people` (`core-faces/.../erase/ErasePorts.kt:101-110`) apre il negozio se i file ci sono; `FacesStores.openLocked` (`:194-201`) con la chiave sparita toglie i file, **crea** una chiave nuova e un database vuoto e segnala `FACES_KEY_LOST` alla Diagnostica (`:221-226`); poi la cancellazione li toglie. Nessun dato, ma un allarme falso e un'operazione sul Keystore in piu'. Proposta: una lettura dei nomi che non crea mai (chiave sparita = nessun nome, file tolti senza rifare).
- **I7. Un montaggio fallito ferma i dati personali per tutto il processo.** Con un database dei volti sul disco e le persone non montate, il gancio risponde `Hold` (`FacesSupport.kt:56-61`); se il montaggio e' fallito (`FacesMount.failed`, `FacesGraph.kt:61-70`) nessun giro dei dati personali pubblica (album personali, preferiti, nascosti) finche' il processo vive, con il solo codice `FACES_ERASE_PENDING`. E' il lato giusto (fallisce chiuso); da sapere e da dire nella Diagnostica con un codice suo.
- **I8. "Ogni resto diventa illeggibile" vale per le colonne sigillate.** Il commento di `FacesStores.eraseLocked` (`FacesStores.kt:279`) e C39 parlano di pagine libere, WAL e blocchi della flash; la struttura dei gruppi (chiavi di contenuto, riquadri, assegnazioni, correzioni) e' in chiaro per progetto (L111): dopo la cancellazione dei file la protegge la cifratura per file di Android, non la chiave dei volti. Proposta: dirlo in C39 e nel commento.

---

## Cose fatte bene

- **"Disattiva e cancella" come protocollo**: diario prima di ogni effetto e dopo ogni passo, passi idempotenti, parte locale che non chiede il NAS, chiave del Keystore distrutta per prima con `NonCancellable`, ripresa da un diario scritto e non ricordato, ricostruzione dal segno `erasing`, nessuna conferma senza la testa personale del dispositivo, voce `on|G` che ricompare riaffermata `off`; e in piu' la pulizia di ogni dispositivo, che toglie dal NAS le generazioni non correnti anche se chi ha cancellato non torna.
- **Cancellazione sul NAS stretta**: solo area privata diversa dalla comune, per generazione e motivo, mai la generazione corrente, mai senza lo stato fresco, mai un file che il catalogo conosce come media, `deleteIfUnchanged` con dimensione e timbro, cancello D34 e D35 che chiude anche quando lancia.
- **Il recinto delle aperture**: un lucchetto solo per aperture e cancellazioni, e ogni porta del database dei volti (archivi, replica, pubblicatore, calcolo) passa da li'.
- **Chiavi e database**: chiave dati avvolta, mai su disco in chiaro, mai creata senza Keystore hardware nella release, "non risponde" distinto da "sparita" (solo la seconda cancella), colonne sigillate legate alla riga, un nome in chiaro rifiutato in lettura, controllo della chiave nel file, nessun nome di file o generazione nei log.
- **Formato firmato**: ordine dei controlli rigoroso (tetto, firma, poi JSON), nessuna allocazione da lunghezze dichiarate, generazione e dispositivo legati al percorso, vettori finiti e normalizzati, chi scrive usa gli stessi controlli di chi legge.
- **Ingressi dei motori**: la stessa catena difesa della Fase 6 (intestazione senza decodificare, dimensioni che non mentono, decodifica ridotta), modelli verificati a ogni apertura e letti dal descrittore verificato, forma dei tensori controllata, LiteRT confinato e provato nel dex.
- **Nascosti**: una fonte sola che fallisce chiusa, filtro finale anche sulle celle della ricerca e degli smart, cancello prima di ogni ritaglio, cache delle copertine solo in memoria.
- **Privacy**: nessun log con nomi, id o generazioni; Diagnostica ed esportazione a chiavi chiuse; negli smart solo id casuali; database, chiavi e file di stato fuori dai backup.
- **Recinzione D34**: prima domanda del cancello, anche in lettura, provata nel bytecode della release.
- **Avvio istantaneo**: il gancio dei dati personali nasce con il processo e, finche' le persone non sono montate, non lascia uscire una testa personale con un database dei volti sul disco.

## Da provare sul campo

Solo con gli account e le share di prova (`test`, `test2`, `test-comune`), sull'S25 e sul Tab S9, dopo le correzioni.
- **R1**: sul tablet, Persone attiva e calcolo fatto; togliere `faces-local` (debug, `run-as`) lasciando `faces/`; dal telefono "Disattiva e cancella": il tablet non deve pubblicare la testa personale finche' `faces-<G>.db` c'e', e alla fine non deve restare niente in `noBackupFilesDir/faces*` (oggi atteso: restano).
- **R2 (F101)**: cercare una persona per nome, rinominarla, poi "Disattiva e cancella": nessuna ricerca recente con il nome vecchio su nessun dispositivo; lo stesso con un nome in cirillico.
- **R3, R4, R5** (con un pubblicatore ostile di debug, mai sulle share vere): voci `people` con l'autore del tablet, un'istantanea con `seq` 10^9, un segmento reso illeggibile dal PC; poi la revoca del telefono ostile.
- **R11**: dopo "Disattiva e cancella", senza chiudere l'app, un dump della memoria (debug) non deve contenere i nomi; la pagina Persone non deve mostrare lo scheletro di prima.
- **R12**: sulle share vere (D34 accesa) "Disattiva e cancella" deve finire con "Fatto su questo dispositivo".
- **R13 (F102)**: tenere aperto dal PC un segmento della generazione che si cancella: la pagina deve dire perche' aspetta e il lavoro non deve ripartire ogni minuto.
- **Il resto, come previsto**: F94-F107 di `docs/decisioni.md`, in particolare F99 (Nascosti), F100-F103 (cancellazione, dispositivo spento, interruzioni), F104 (ingressi e modelli sul telefono), F106 (dati solo nell'area privata) e F107 (log ed esportazione).

## Checklist della Fase 8 (`docs/sicurezza.md` §7, "Fase 8: persone")

- [ ] **Attivazione per account dopo la pagina versionata; nessun dato trattato prima dell'avviso; "Non su questo dispositivo" cancella in locale (F94, F95).**
  - Regge per l'avviso (nessun archivio si apre senza `activeHere`).
  - "Non su questo dispositivo" cancella solo la generazione che `faces-local` ricorda (R1). Mancano F94 e F95.
- [ ] **Dati dei volti solo nell'area privata dell'account, mai nell'indice ne' nell'area comune, letti solo dai dispositivi dell'account (P3, F106).**
  - Regge (risposte 1 e 2). Manca F106.
- [ ] **"Disattiva e cancella" senza tracce su NAS e dispositivi, anche con un dispositivo spento che torna (F100-F103); chiave distrutta prima dei file; cronologia ripulita.**
  - Regge sul dispositivo che cancella e sul NAS (risposta 3).
  - Non regge per una generazione che `faces-local` non ricorda (R1), per i nomi vecchi o non latini nella cronologia (R2), per la memoria del processo e la forma della pagina (R11); il foglio dice piu' di quello che e' successo (R12). Mancano F100-F103.
- [ ] **Nessuna cancellazione guidata da un dato remoto fuori da `faces/`; mai un media (proprieta' di P8).**
  - Regge (cammino stretto, regola di core-nasops, media del catalogo mai toccati; L192 dichiarato).
- [ ] **Nessuna inferenza di eta', genere, etnia, emozioni o accessori; nessun ritaglio di volti salvato; screenshot e documenti esclusi.**
  - Regge in lettura (corpo solo numerico, cache dei ritagli solo in memoria).
- [ ] **Vettori, centroidi e nomi cifrati sul dispositivo; sul NAS il limite L94 dichiarato.**
  - Regge; la struttura dei gruppi in chiaro e' dichiarata (L111, I8). Manca la prova con la chiave vera sul telefono.
- [ ] **Nascosti mai nelle superfici delle persone (F99).**
  - Regge (risposta 4); finestre brevi (I3) e il chip gia' in Cerca dopo "Nascondi persona" (I2). Manca F99.
- [ ] **Log, Diagnostica ed esportazione senza nomi, id, chiavi, riquadri, vettori (F107).**
  - Regge; `toString()` di default da sistemare (I4). Manca F107.
- [ ] **Licenze e provenienza dei modelli documentate nell'APK; nota GDPR riletta (L14, L98).**
  - Licenze e avviso negli asset (`core-ai/src/main/assets/faces/LICENSE-*.txt`, `NOTICE.txt`) e passo 7g; la nota GDPR resta da rileggere se l'app sara' pubblicata.
- [ ] **Recinzione `facesSyncTestSharesOnly` accesa in release fino al si' (Q5); LiteRT solo in `core-ai` anche nel bytecode.**
  - Regge (risposta 7).
- [ ] **Ingressi preparati dall'app, modelli verificati e della forma attesa, uscite non fidate (C51-C53; F104).**
  - Regge (risposta 5). Manca F104.
- [ ] **Nessuna etichetta, nome o volto dei dati veri nel repository; valutazione solo debug; soglie provvisorie dichiarate (F105, Q4).**
  - Non ripercorso in questa revisione oltre ai passi di `check.ps1`; nessun problema visto.
- [ ] **Calcolo e "Disattiva e cancella" sopravvivono a ogni interruzione (F103).**
  - Regge sulla JVM (banco di P12, O1-O3 corretti); restano un file illeggibile che ferma il giro (R5), un file aperto che tiene ferma la cancellazione (R13) e una chiave rifatta alla ripresa (I6). Manca F103.

Delle voci "per ogni fase, sempre":
- nessun permesso Android nuovo per Persone; nessun host nuovo; le dipendenze nuove (LiteRT per i volti) sono confinate in `core-ai` e controllate da `check.ps1`;
- questa revisione vale come passo di `security-review` della Fase 8 (con la correzione O1/O2/O3 e la parte di Persone dell'avvio istantaneo). Va insieme alla revisione funzionale di fine fase.

---

## Pacchetti di correzione proposti

- **Pacchetto A, "cancellazione e stato locale"** (prima di F94, F95 e F100-F103, perche' quelle prove li incontrano subito e oggi valgono anche nella release locale): **R1, R2, R11, R12, R13**, con **I1** e **I6** (stesso codice: interruttore, `FacesLocalErase`, `FacesStores`, montaggio, foglio).
- **Pacchetto B, "formato e replica dei volti"** (prima del si' di Q5, che toglie la recinzione D34; oggi toccano solo le share di prova): **R3, R4, R5, R6, R7, R8**, con **I5** (`core-trust` per la `seq` e la provenienza, `core-faces/sync`, `grouping`, `store`).
- **Pacchetto C, "ricerca e album con persone"** (piccolo, si puo' fare con A): **R9, R10**, con **I2, I3, I4** (la normalizzazione Unicode dei nomi va insieme a R2).
- **Documenti** (con l'ultimo pacchetto): L99 e L136 (R3), L138 (R7), L190 esteso agli altri dispositivi (R2), C39 e il commento di `FacesStores` (I8), C130 (I1), un codice della Diagnostica per un montaggio fallito (I7).

**Decisioni che servono all'utente:**
- **R3**: accettare voci `people` con un autore diverso dal firmatario solo se gia' viste dal loro autore (la strada piu' stretta), oppure tenere la provenienza e togliere per provenienza alla revoca; e se "Dimentica" deve poter tornare indietro (una lapide, cioe' un "Ricorda di nuovo").
- **R2**: se "Nascondi persona" e "Dimentica" devono togliere subito il nome dalle ricerche recenti (oggi lo fa solo "Disattiva e cancella", e male), e se basta segnare le voci nate da un chip "Persona".
- **R12**: il testo dell'esito per la cancellazione solo locale.
- **R6**: i valori dei tetti (righe e byte per autore e in tutto).
- **I7**: tenere "fallisce chiuso" (i dati personali non si pubblicano con un montaggio fallito) e dirlo con un codice suo, oppure lasciar passare i dati personali senza la voce `faces`.
