# Revisione di sicurezza della Fase 5A (il backup al sicuro)

| | |
|---|---|
| **Oggetto** | i commit della 5A da `bd2fdef` a P7 `87b89e3`: `core-backup` (`upload`, `safety`, `plan`, `settings`, `store`), `core-nas/.../ops/backup/` (`NasBackupOps`, `NasBackupOpsFactory`/`SmbNasBackupOps`), `ops/SmbNasFileOps.reopenBackupTemp`, `smb/SmbjTransport` (`openExistingForWrite`, `setLength`), schema v4 e operazioni del backup in `core-database` (`MediaCatalog`, `BackupQueries`, `BackupDaos`), copie simili (`core-library/.../similar/`, `core-personal` proiezione e scelte), `core-nasops` (`CitedTemps`), `feature-backup`, `feature-viewer` (sezioni "Backup" e "N copie", copia fuori indice), `:app` (`backup/`, `work/BackupWork.kt`, `BackupStages.kt`, `BackupJobService.kt`, `BackupJobs.kt`, `notify/BackupNotification.kt`, `AppBackupGate`, `DeveloperTools`), manifest. |
| **Riferimenti** | `docs/fase5-progetto.md` (§0.1 D2, D4, D8, D9, D12, D13, D32, D33, D36; §1.1, §2.2, §2.3, §2.12, §6), `docs/sicurezza.md` (C10, C12, C17, C19, §4-bis, L28, L37-L44, checklist della Fase 5), `docs/prova-500.md` parte B, `docs/review/4b-fase4b.md` (formato; R3, R9, I4). |
| **Attaccanti e guasti** | (a) un **dispositivo di famiglia compromesso**: dello stesso account (scrive nell'area privata) o di un altro account (scrive nell'area comune e nel suo indice); (b) un'**app di terze parti** sul telefono (nomi dei file e delle cartelle, righe del MediaStore, broadcast, intent); (c) **dati letti dal NAS** (nomi, dimensioni, contenuti, record dell'indice, collegamenti); (d) **guasti ed errori umani**: interruzioni, file aperti dal PC, permessi parziali dei media, configurazione di famiglia corretta a meta' strada, l'APK sbagliato con l'account sbagliato. |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice di produzione. Nessun NAS vero, nessun telefono, nessun `adb`. |
| **Data** | 16/10/2026 |
| **Metodo** | Lettura del codice delle superfici richieste, seguendo la macchina a stati del motore vero (`SafeUploadEngine`) e del guardiano passo per passo (pubblicazione, nome occupato, risposta persa, ripresa, telefono che cambia o rifiuta, cancello, eccezioni) fino al catalogo (`attachCopies`, `markVerified`, `recordFullHash`, collegamenti, adozione d'identita') e ai lavori di `:app`. Tre **test temporanei**, sul motore vero (`RealEngineKit`) e sul guardiano vero (`SafetyBench`) di core-backup, per R1 (motore e guardiano) e R5. Sono stati eseguiti in una **copia del repository** nella cartella temporanea della sessione (`git archive` di `87b89e3` piu' il sottomodulo `engine`), perche' un'altra sessione stava eseguendo `:core-backup:testDebugUnitTest --tests *BackupNasIntegrationTest*` (timeout di 180 minuti) e teneva occupata la cartella dei risultati del modulo: niente e' stato toccato nel repository, copia e test sono stati cancellati. Esiti: R1 motore 5 giri su 5 finiti con `TrustViolation`, R1 guardiano 3 su 3, R5 4 giri delle foto su 4 fermi a `phone`. |
| **Stato** | **Parzialmente corretta (19/10/2026)**: R1, R2, R4, R5, R6, R7 **chiuse** (correzioni in un pacchetto successivo, senza commit, ognuna con il suo test; le scelte che l'utente vede in `docs/decisioni.md`, "Fase 5A: correzioni della revisione (19/10)"). **R3 resta aperta** (la fa un'altra sessione), come le informative I1-I9. |

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 0 | – |
| Media | 2 | R1 un nome "nome (n)" oltre i 255 byte ferma per sempre il backup (e, nel guardiano, tutte le verifiche) · R2 con l'APK di release e un account di prova nessuna recinzione del telefono: tutta la libreria va nella share di prova |
| Bassa | 5 | R3 `reopenTemp` accorcia quello che trova al nome del temporaneo, anche un collegamento · R4 "Ferma" non e' una pausa · R5 con "foto selezionate" un media non scelto ferma ogni giro delle foto · R6 la lettura del telefono non controlla dove finisce il file · R7 il cancello non ricontrolla che la share dell'elemento sia ancora l'area privata |
| Informativa | 9 | I1 la vista in sola aggiunta pubblica ovunque fuori da `.mycloudgallery/` · I2 il nome della copia del guardiano viene dal NAS · I3 fra due riletture "al sicuro" regge sul timbro · I4 l'emergenza non ferma il file in corso · I5 il guardiano scrive anche se il backup non e' mai stato avviato · I6 "collegare" sposta la posizione di un altro media senza unirli · I7 il nascosto contagioso dipende dall'ordine delle voci · I8 "fermo da 3 giorni" con l'orologio del telefono · I9 la copertina delle copie simili si decide anche da dati remoti |

**D2 regge**: con dati locali normali il backup non toglie, non sposta e non sostituisce niente. Le primitive raggiungibili sono solo quelle in sola aggiunta, la pubblicazione non sostituisce mai, `deleteOwnTemp` tocca solo nomi `<io>-bk-` e `NasFileSystem` non ha operazioni distruttive. **L'unica via che cambia byte esistenti e' R3**, e richiede che al nome di un nostro temporaneo ci sia un collegamento.

**"Al sicuro" nasce solo da una rilettura fatta qui** e nessun dato letto dal NAS diventa un'azione che toglie qualcosa (E-S11). Restano R6 e I3.

Le due medie riguardano la protezione stessa:
- **R1**: un solo nome lungo, scelto da un'app del telefono o da un familiare nell'area comune, ferma per sempre il caricamento e la rotazione, con un messaggio sbagliato;
- **R2**: la combinazione "release + account di prova" non e' coperta da nessuna recinzione, proprio il caso che D13 voleva evitare.

---

## Risposte al perimetro

1. **Il backup non puo' togliere ne' sostituire file del NAS (D2).**
   - **Confine dei moduli**: `core-backup` vede il NAS solo con `NasBackupOps`. Il marcatore `AppendOnlyNasAccess` lo passano solo `core-nas` e `core-backup` (`core-backup/build.gradle:32`); `NasFileOpsFactory` chiede `DestructiveNasAccess`, che `core-backup` non ha. `Phase5ArchitectureTest` ha i controlli; li ho letti, non li ho rieseguiti reintroducendo le violazioni.
   - **Superficie della sessione**: `NasSessionManager` espone solo lettura, `NasBrowser` e `NasFileSystem` (`list`, `read`, `publishExclusive`: nessuna cancellazione, spostamento o sovrascrittura).
   - **`publishTemp`**: rinomina con `replaceIfExists = false` (`SmbjTransport`, `renameNoReplace`). Il nome occupato torna come `Collision` e porta a rileggere il file che c'e'.
   - **`deleteOwnTemp`**: il prefisso `<io>-bk-` si controlla nella vista (`NasBackupOpsFactory.kt:71-73`) e di nuovo in SMB (`SmbNasFileOps.kt:426`). La cancellazione avviene su un handle esclusivo e salta cartelle e punti di rianalisi; un collegamento al suo nome si toglie, ma si toglie solo il nome.
   - **`reopenTemp`**: solo un temporaneo proprio del backup, aperto senza crearlo. Pero' accorcia l'oggetto che trova a quel nome: **R3**.
   - **Rinomine**: la 5A non ne ha, a parte la pubblicazione.
   - **Percorsi**: tutti dal catalogo locale (nome del telefono validato, data, cartella del dispositivo scelta dall'utente). Le radici `Backup/` le ricontrolla il motore, il guardiano no (**I1**). Dal NAS viene solo il nome del file dell'area comune, nella destinazione del guardiano (**I2**).
2. **D12.**
   - **Il cancello** (`AppBackupGate`) si guarda a ogni giro e a ogni elemento:
     - **motore**: all'inizio del giro, prima di ogni elemento e fra un passo e l'altro (`SafeUploadEngine.kt:191`, `:269`, `:315`);
     - **guardiano**: all'inizio e per ogni elemento;
     - **verifiche**: chiuse con `TEST_ONLY`.
   - **Le corse dell'utente**: primo backup, "Fai ora" e D34 passano tutti da `BackupStages.round`, che guarda il cancello e poi arriva al motore.
   - **La share controllata** e' quella dove si scrive davvero (`item.share`, la destinazione). La sorgente del guardiano nell'area comune si legge soltanto.
   - **Altre scritture**: l'indice dei media caricati (D10) passa dai cancelli dell'indice (D31). Non ho trovato scritture di media che saltano il cancello.
   - **Restano**: R7 (la share non si ricontrolla contro l'area privata) e I4 (l'emergenza a meta' file).
3. **D13.**
   - Esiste solo in debug: `DeveloperTools` di release risponde `null` (`app/src/release/.../DeveloperTools.kt:42`) e `check.ps1` cerca `DebugBackupFence`, `mcg-prova` e `mcg-campo` nel dex di release.
   - In debug la recinzione vale due volte: nel piano (sorgenti) e alla porta del telefono (righe aperte). Vale anche per le destinazioni del motore e del guardiano.
   - Ma la release con un account di prova non ha nessuna recinzione: **R2**.
4. **Dati letti dal NAS che diventano azioni (E-S11).**
   - **Deduplica e guardiano**: partono dal catalogo (`privateCopies`, `onlyCommonCopies`).
   - **Collegamento**: avviene solo dopo una rilettura completa con lo SHA-256 dei byte del telefono, o della versione.
   - **Nomi alternativi**: vengono dal nome previsto, mai dal NAS.
   - **Identita' remote**: `observationOf` passa all'osservazione solo `qh1`, mai l'hash dell'indice. Le identita' adottate non uniscono senza hash completo (`MediaCatalog.kt:1600-1632`, `IdentityRules`).
   - **Copie simili**: sono un dato derivato che cambia solo l'aspetto (**I9**).
   - **File ostile sul NAS**: non puo' far scrivere fuori da `Backup/` (un solo componente validato sotto una radice fissa) ne' far collegare byte diversi come "al sicuro". Pero' un nome lungo nell'area comune ferma la tappa di sicurezza (**R1**, **I2**).
5. **"Al sicuro" solo con una rilettura fatta qui (L28, F4-D8).**
   - **Le vie del motore**: rilettura completa del temporaneo prima del nome, timbro dopo, e rilettura completa se il timbro e' cambiato. Rilettura completa anche nella deduplica, nel nome occupato e nella risposta persa.
   - **Il catalogo**: `attachCopies` vuole l'hash completo della versione; `markVerified` confronta lo SHA **letto** con quello della versione.
   - **`FULL_HASH`**: impara l'hash dal NAS solo per versioni che non ce l'hanno. Una versione del telefono non arriva mai a una posizione privata senza hash completo: `LinkingRules` collega per contenuto solo con hash uguali, e il motore registra l'hash prima di collegare.
   - **E-B15**: un temporaneo completo con i byte di prima diventa la copia della versione di prima. `VersionRules.contentChangedAt` la conserva come versione (`parent`), quindi `attachCopies` su una versione non piu' corrente e' corretto. La versione nuova resta da proteggere e il media non diventa "al sicuro" finche' non lo e' anche lei.
   - **Restano**: R6 (file piu' lungo della dimensione del MediaStore) e I3 (il timbro fra due riletture).
6. **Privacy.**
   - **Deduplica**: legge solo l'area privata dell'account. La query filtra account, share e `area = 'private'` (`BackupQueries.kt:148-165`) e il cercatore ricontrolla la share (`PrivateCopyFinder.kt:105`).
   - **Guardiano**: legge solo sorgenti con `area = 'common'` (`BackupQueries.kt:192-214`), mai l'area privata stessa. La sessione e' quella dell'account.
   - **Log**: nella release `GalleryLog` non ha un sink. I messaggi del codice nuovo hanno solo numeri e codici.
   - **Notifiche, Diagnostica, esportazione**: notifica privata, con una versione pubblica e soli numeri. Diagnostica ed esportazione hanno solo numeri ed enumerati. Le firme visive restano solo nel database locale.
   - **Nascosto contagioso (E-H1)**: vale in `groupSimilar`, in ogni cambio di nascosto (`writePersonal`, che lo propaga al gruppo) e nelle unioni. Con una copia nascosta nessuna copia rappresenta il gruppo (copertina nulla), quindi niente compare in Libreria. La copia fuori indice del viewer si apre solo dalla sezione "N copie" del media che si sta guardando, e un gruppo visibile non ha copie nascoste. I conteggi del backup contano anche i nascosti, ma sono solo numeri.
   - **Resta I7**, che sbaglia dalla parte prudente.
7. **Componenti Android.**
   - `BackupJobService`: `exported="false"`, protetto da `BIND_JOB_SERVICE`, senza filtri.
   - "Ferma": ricevitore registrato a runtime con `RECEIVER_NOT_EXPORTED`, vivo solo mentre il servizio lavora. Il `PendingIntent` e' immutabile ed esplicito sul pacchetto.
   - Il tocco sulla notifica apre solo l'app.
   - Nessun altro componente nel manifest della 5A e nessun deep link nelle pagine del backup.
8. **Ora.**
   - **Dall'ora del NAS**: "oltre domani" (`BackupPaths.tomorrowOf`; senza ora del NAS il tetto fisso del 2100, mai l'orologio del telefono), l'orizzonte di D34, l'istante della verifica e la scadenza della rotazione.
   - **Orologio monotono**: budget, tappe e attese.
   - **Orologio del telefono**: solo l'avviso "fermo da 3 giorni" (**I8**) e la fascia notturna delle regole (gia' della Fase 4).
9. **Pulizia dei temporanei (D33).**
   - **Temporanei citati**: `OwnFilesCleaner` tiene quelli citati da uno dei due diari. Il diario del backup cita `temp_path` degli elementi aperti (`BackupDaos.kt:84`).
   - **Se la porta fallisce** in quel giro non si toglie nessun temporaneo.
   - **Gli altri temporanei**: uno non citato si toglie dopo un giorno di ora del NAS. La pulizia di core-nas riconosce solo i suoi nomi da 32 cifre esadecimali.
   - **Mai file non nostri**: il nome deve essere di questo dispositivo e la cancellazione salta cartelle e collegamenti riconosciuti.
10. **Altro**:
    - R1: eccezioni non gestite che fermano tutto;
    - R4: "Ferma" che non dura;
    - R5: un rifiuto di una riga che ferma il giro;
    - R6: il file del telefono piu' lungo del MediaStore.

---

## Media

### R1. Un nome "nome (n)" oltre i 255 byte ferma per sempre il backup (e, nel guardiano, tutte le verifiche)

- **Gravita':** media. Non perde file, ma un solo file blocca tutti gli altri, per sempre, con un messaggio sbagliato; nel guardiano spegne anche la rotazione annuale.
- **Dove:**
  - **il nome**:
    - `core-backup/.../upload/UploadNames.kt:14-21` (`planned.parent()?.child(alt)` senza accorciare la radice del nome);
    - `core-trust/.../nas/SharePath.kt:31` e `:92` (`child` → `checkComponent` → violazione);
    - `core-trust/.../TrustFormat.kt:269` (`TrustViolation : Exception`, non `IllegalArgumentException`);
  - **nel motore**:
    - `SafeUploadEngine.kt:792` (nome occupato) e `:816` (finale cambiato dopo la pubblicazione);
    - `:833-839` (`attach` cattura solo `IllegalArgumentException`);
    - `:236` (`coroutineScope`: l'eccezione di un elemento annulla il giro);
    - `core-database/.../dao/BackupDaos.kt:80` (la ripresa va in ordine di `item_id`: l'elemento bloccato torna fra i primi);
  - **nel guardiano**: `safety/SafetyReads.kt:151-158` (`SafetyCommon.nameFor`), `BackupPrivateCopyGuardian.kt:635`, `:659`;
  - **in `:app`**:
    - `work/BackupStages.kt:188-191` (il guardiano gira prima delle verifiche, senza cattura);
    - `work/BackupWork.kt:233-238` (eccezione → `Result.success()`, nessun problema salvato);
    - `work/BackupJobService.kt:145-149` (eccezione → `RESCHEDULE`: il lavoro dell'utente si ripete a oltranza).
- **Scenario (guasto d, attaccante b o a):**
  - **Il file**: il telefono ha un file con un nome di 252-255 byte UTF-8. Bastano 85 caratteri giapponesi, una sessantina di emoji, o un download con un nome lungo in una cartella aggiunta alle sorgenti.
  - **Il nome occupato**: in `Backup/<dispositivo>/AAAA/MM/` c'e' gia' un file diverso con lo stesso nome. Puo' essere un vecchio backup, l'altro telefono dopo "Continua li'", o lo stesso nome in due cartelle del telefono che finiscono nella stessa sottocartella.
  - **Il crollo**: la pubblicazione risponde `Collision`; il motore rilegge per intero il file che c'e' (anche un video da GB); "nome (2)" non e' un componente valido e arriva una `TrustViolation`. Il giro muore e l'elemento resta `PUBLISHING`.
  - **Ogni giro dopo** si rilegge di nuovo il file e si muore allo stesso punto: ogni 6 ore, dopo ogni lettura del telefono, e con l'UIDT riprogrammato.
  - **Il blocco**: gli elementi dopo di lui nel diario non si muovono piu', ne' quelli nuovi.
  - **Cosa vede l'utente**: lo stato del motore resta `Running` (`finish` non arriva), quindi la pagina dice "in corso" per sempre. Dopo 3 giorni il riepilogo dice "backup fermo" e propone la guida della batteria.
  - **Nel guardiano** basta il nome di un file dell'area comune, scelto da chi ce l'ha messo (I2). La tappa di sicurezza muore prima delle verifiche: niente piu' copie non verificate, `FULL_HASH` ne' rotazione.
- **Confermato:** **si'.** Due test temporanei, poi cancellati:
  - **sul motore vero** (`RealEngineKit`, un file in volo): 5 giri su 5 finiscono con `TrustViolation`; il secondo media resta `PLANNED`;
  - **sul guardiano** (`SafetyBench`): 3 giri su 3; il secondo media non arriva mai al sicuro.
- **Correzione proposta:**
  1. `nameFor` (motore e `SafetyCommon`) accorcia la radice del nome perche' " (n)" stia in 255 byte, come `DeviceFolderPolicy.freeName` e `BackupPaths.truncate`. Lo stesso ingresso deve dare sempre lo stesso nome, per il diario.
  2. Difesa in profondita': nel motore e nel guardiano un'eccezione di un elemento (non `CancellationException`, non `Error`) parcheggia quell'elemento con un codice di problema, e il giro continua con gli altri.
  3. In `BackupStages.safety` le verifiche girano anche se il guardiano fallisce.
  4. Nel banco e negli scenari, fixture con nomi da 252-255 byte e con caratteri multibyte (oggi i nomi sono corti).
- **Stato:** **chiusa (19/10).** Un solo `BackupPaths.alternateName` accorcia la radice a un confine di carattere (motore, guardiano, riferimento); l'eccezione di un elemento lo parcheggia `FAILED` con il nuovo problema `UNEXPECTED` (DiagCodes, voce, testi, `problem.unexpected`) e il giro continua; `BackupStages.safety` fa girare le verifiche comunque; `BackupWork` e `BackupJobService` salvano il problema (`attemptStage`) senza successo muto ne' `RESCHEDULE`. Test: `Review5aEngineTest.r1_*` (5 giri), `Review5aSafetyTest.r1_*` (3 giri), `BackupPathsTest.nomeAlternativo_*`, `BackupWorkTest.r1_*`.

### R2. Con l'APK di release e un account di prova nessuna recinzione del telefono: tutta la libreria va nella share di prova

- **Gravita':** media (privacy: l'intera libreria del telefono in una share con una password di prova, con un tocco).
- **Dove:**
  - `app/src/release/.../debugtools/DeveloperTools.kt:42` (`backupFence() = null`), `app/.../di/GalleryModule.kt:142`;
  - `app/.../backup/AppBackupGate.kt:48` e `core-common/.../featureflags/FeatureFlags.kt:41`: `backupMayWriteTo("test")` e' vero anche in release (`TestShares.kt:25`);
  - `core-backup/.../plan/DefaultBackupPlanner.kt:127-132`: senza recinzione valgono le sorgenti scelte, di serie fotocamera e screenshot dal passo 6;
  - `app/.../backup/MediaStorePhoneBytes.kt:117-121`.
- **Scenario (errore umano d):**
  - **Il momento**: prima del "primo backup vero" (chiusura della 5B, APK di release), qualcuno prova l'APK di release sull'S25 con `test` o `test2` per vederlo girare sulle share di prova. E' la prova piu' naturale: F21-F24 sono in debug, ma la release e' quella che conta.
  - **I due recinti**: D12 lascia scrivere, perche' la share e' di prova. D13 non c'e'.
  - **Il tocco**: il foglio del primo backup propone le 6.274 foto e i 384 video; un tocco su "Stanotte" o "Inizia ora" e la fotocamera e gli screenshot veri, con le posizioni, finiscono in `test/Backup/...`.
  - **Chi li vede**: la password di `test` e' di prova e sta nel file delle credenziali del PC (L25); lo stesso account scrive anche su `NAS` e `Public`.
  - **Perche' conta**: D13 esiste proprio per impedire questo (§0.1: "Senza, una prova sull'S25 caricherebbe le foto vere della famiglia nella share di prova"), ma vale solo con il debug e un account di prova.
  - **Attenuante**: il foglio mostra il numero vero dei media.
- **Confermato:** lettura del codice. Il percorso e' completo e nella release nessun altro controllo guarda gli account di prova: `TestShares.isTestAccount` lo usano solo `ShareFence` e `DebugBackupFence`, entrambi di debug.
- **Correzione proposta:** la recinzione restringe e basta, quindi puo' stare anche in release. Due strade:
  - **la regola in `main`**: "account di prova → si legge solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/`, si scrive solo sotto `mcg-campo/`", pura e ricavata da `TestShares.ACCOUNTS`; in debug restano solo gli strumenti di semina;
  - **piu' semplice**: in release, con un account di prova, il cancello risponde `TEST_ONLY` per ogni share. Fino a Q3 il backup in release non scrive da nessuna parte.
  - **In entrambi i casi**: aggiornare D13, `check.ps1` (oggi vieta "mcg-prova" nel dex di release) e la voce D13 della checklist.
- **Stato:** **chiusa (19/10).** Strada "piu' semplice": `FeatureFlags.backupBlocksTestAccounts` (vero in `RELEASE`, falso in debug) e `backupMayWriteAs`; `AppBackupGate` risponde `TEST_ONLY` per ogni share a un account di prova in release; `check.ps1` lo prova nel bytecode di release; D13 e checklist aggiornati. Test: `FeatureFlagsTest.r2_*`, `AppBackupGateTest.r2_*` (tutte le combinazioni), `BackupIntegrationTest.r2_*`.

---

## Bassa

### R3. `reopenTemp` accorcia quello che trova al nome del temporaneo, anche un collegamento

- **Gravita':** bassa. Serve chi ha gia' la password dell'account o SSH, ma rompe il "per costruzione" di D2.
- **Dove:**
  - `core-nas/.../smb/SmbjTransport.kt:283-304` (`openExistingForWrite`: `FILE_OPEN` e `FILE_OPEN_REPARSE_POINT`);
  - `core-nas/.../ops/SmbNasFileOps.kt:315-353` (unica difesa: cartella o `REPARSE_POINT`, `:335-337`), poi `setLength(keepBytes)` a `:340`;
  - `SmbjTransport.kt:390` (`setLength`);
  - lo chiama `SafeUploadEngine.kt:556-572` quando il diario ha un punto di ripresa.
- **Scenario (attaccante a, guasto d):**
  - **Il collegamento**: R3 della 4B ha mostrato che Samba di serie presenta un collegamento simbolico come un file normale (nessun attributo, l'identita' della destinazione). Un collegamento fisico e' indistinguibile per costruzione, e Samba lo crea anche via SMB2 (`FileLinkInformation`).
  - **Come succede**: al nome `.mycloudgallery/tmp/<io>-bk-<n>` di un elemento con un punto di ripresa compare un collegamento a un media della share (un dispositivo compromesso dello stesso account, uno script via SSH).
  - **Il danno**: il giro dopo apre la destinazione, la accorcia a `keepBytes` e ci scrive sopra i byte del telefono. La verifica non torna e `deleteOwnTemp` toglie solo il nome: il media resta troncato e rovinato.
  - **Perche' e' l'unica**: niente rinomine, cestino o cancellazioni; e' la sola primitiva della 5A che cambia byte gia' esistenti.
- **Confermato:** plausibile. Il simulatore SMB non ha collegamenti trasparenti a un file; il percorso nel codice c'e'.
- **Correzione proposta:**
  - scrivere nel diario l'identita' del temporaneo (il `fileId` del timbro di `checkpoint`/`finish`) e, alla riapertura, confrontarla sullo stesso handle;
  - rifiutare (da capo, con `deleteOwnTemp`) se `NumberOfLinks` di `FileStandardInformation` non e' 1 o se il `fileId` non torna;
  - controllare che `tmp/` sia la cartella vera, con la voce `..` come in R3 della 4B;
  - prova sul campo (sotto).
- **Stato:** aperta.

### R4. "Ferma" non e' una pausa

- **Gravita':** bassa (non distrugge niente; l'utente crede di aver fermato le scritture).
- **Dove:**
  - `SafeUploadEngine.kt:144` e `:183-185` (la richiesta resta in memoria), `:189` e `:203` (ogni giro la azzera);
  - `app/.../backup/BackupUi.kt:361-363`, `BackupGraph.kt:327-330`;
  - `feature-backup/.../page/BackupStatusCard.kt:217` ("Riprendi");
  - `work/BackupWork.kt:80-97` (giri periodici ogni 6 ore), `:157-169` e `work/GalleryWork.kt:102` (un giro dopo ogni lettura del telefono).
- **Scenario (errore umano d):**
  - **Il tocco**: l'utente tocca "Ferma" (pagina o notifica) perche' non vuole che le foto appena scattate vadano sul NAS adesso, o perche' il NAS e' in manutenzione.
  - **Cosa vede**: il giro in corso si ferma e la pagina offre "Riprendi".
  - **Cosa succede**: alla prossima lettura del telefono (pochi minuti) o al giro periodico il lavoro a tappe riparte da solo e carica.
  - **Fra due giri**: un "Ferma" toccato li' si perde, perche' `run` lo azzera.
  - E' R9 della 4B in un'altra forma, senza nemmeno un processo che muore.
- **Confermato:** lettura del codice.
- **Correzione proposta:** un segno "fermato dall'utente" persistente (`BackupRunMemory`) che `BackupStages.round`/`safety` e lo stato rispettano finche' l'utente non tocca "Riprendi" o "Fai ora". In alternativa, chiamare il tasto "Ferma questo giro" e dirlo.
- **Stato:** **chiusa (19/10).** Il segno `backup-user-paused` (`BackupRunMemory`) e il nuovo stato del cancello `PAUSED` fermano motore e guardiano (le verifiche continuano) finche' non si tocca "Riprendi" o "Fai ora"; pagina, Impostazioni e notifica dicono "In pausa". Test: `Review5aEngineTest.r4_*`, `Review5aSafetyTest.r4_*`, `AppBackupGateTest.r4_*`, `BackupPauseTest`, `BackupNotificationTest.r4_*`.

### R5. Con "foto selezionate" un media non scelto ferma ogni giro delle foto

- **Gravita':** bassa (disponibilita'; il messaggio "Si salvano solo le foto selezionate" in quel caso e' falso).
- **Dove:**
  - `SafeUploadEngine.kt:374` e `:532`: `PhoneOpen.Denied` → `Step.Stop(RunStop.PHONE)` per ogni motivo;
  - `app/.../backup/MediaStorePhoneBytes.kt:108-112`: con l'accesso parziale una riga non vista vale `NOT_SELECTED`;
  - `core-backup/.../contract/BackupPorts.kt:30`; `BackupDaos.kt:80` (ordine di ripresa);
  - `work/BackupJobService.kt:73`: `PHONE` chiude anche il giro dei video del lavoro dell'utente.
- **Scenario (E-P1):**
  - **Il punto di partenza**: il diario ha elementi aperti (per esempio un primo backup interrotto).
  - **Il cambio**: l'utente passa a "foto selezionate" (Android 14+).
  - **Perche' blocca**: il catalogo tiene le righe che non vede piu' (nessuna deduzione dall'assenza) e il piano le propone ancora. Il primo elemento non scelto ferma il giro con `PARTIAL_ACCESS`, a ogni giro.
  - **Il risultato**: nessuna delle foto scelte arriva sul NAS.
- **Confermato:** **si'**, con un test temporaneo poi cancellato, sul motore vero: 4 giri delle foto su 4 fermi a `phone`, la foto scelta resta `PLANNED`.
- **Correzione proposta:** un rifiuto che riguarda una sola riga (`NOT_SELECTED`) fa aspettare quell'elemento (`Step.Wait()`), non il giro. Il giro si ferma solo per i rifiuti globali: `MEDIA_PERMISSION`, e `MEDIA_LOCATION` per le foto.
- **Stato:** **chiusa (19/10).** `SafeUploadEngine.denied`: `NOT_SELECTED` fa aspettare solo l'elemento (`Step.Wait()`), `MEDIA_PERMISSION` e `MEDIA_LOCATION` fermano ancora il giro. Test: `Review5aEngineTest.r5_*` (1 non scelto e 3 scelti: i 3 arrivano al sicuro).

### R6. La lettura del telefono non controlla dove finisce il file

- **Gravita':** bassa. Oggi e' un falso "al sicuro" su una copia troncata; nella 5B "Libera spazio" potrebbe fidarsene.
- **Dove:**
  - `core-backup/.../upload/PhoneReading.kt:22-42`: legge esattamente `size` byte, la dimensione del MediaStore, senza guardare se il file continua;
  - `SafeUploadEngine.kt:379` e `:383-388`: timbro prima e dopo, dal MediaStore;
  - `:389`: l'hash dei byte letti diventa l'hash completo della versione.
- **Scenario (guasto d, attaccante b):**
  - **Il file**: un file cresce dopo l'ultima lettura del MediaStore (un'app che scrive in piu' sessioni, o la vecchia memoria condivisa di Android 10, dove il MediaStore si aggiorna solo con una nuova scansione).
  - **La lettura**: il backup legge i primi `size` byte. Il timbro, che viene dal MediaStore, e' uguale prima e dopo.
  - **Il risultato**: il backup carica il prefisso, la rilettura torna con il suo stesso hash e la versione diventa "al sicuro" con una copia troncata.
  - **Chi se ne accorge**: nessuno, finche' il MediaStore non si aggiorna.
- **Confermato:** plausibile; dipende da quanto il MediaStore resta indietro. Sull'S25 con FUSE si aggiorna alla chiusura del file.
- **Correzione proposta:** dopo `size` byte leggerne uno in piu' (deve arrivare la fine del file), oppure confrontare con la dimensione del descrittore aperto (`ParcelFileDescriptor.statSize`). Se e' diversa, il file e' cambiato (E-B15).
- **Stato:** **chiusa (19/10).** Dopo `size` byte `PhoneReading` controlla la dimensione del descrittore e un byte in piu' (`endsAtSize`); il file cresciuto e' E-B15 (`GONE`, niente sul NAS); `MediaStorePhoneBytes` consegna la dimensione di adesso (`ContentUriMediaBytes.currentSize()`). Test: `Review5aEngineTest.r6_*` (telefono finto, `FakePhoneBytes.growSilently`), `AndroidMediaStoreSourceTest.r6_*`.

### R7. Il cancello non ricontrolla che la share dell'elemento sia ancora l'area privata

- **Gravita':** bassa (serve un cambio della configurazione di famiglia).
- **Dove:**
  - `app/.../backup/AppBackupGate.kt:47`: controlla solo `privateShare() != null`;
  - `BackupGraph.kt:211-289`: il motore nasce in `start` con la share di quel momento e non si ricostruisce piu' (`:213`);
  - `SafeUploadEngine.kt:191`, `:269`, `:315`: il cancello guarda `item.share`.
- **Scenario (errore umano d):**
  - **Il cambio**: l'admin corregge la configurazione, e la share che era l'area privata dell'account diventa l'area comune (o l'area privata cambia).
  - **Cosa succede**: gli elementi aperti e il motore costruito all'avvio continuano a scrivere nella share di prima finche' il processo vive.
  - **Il danno**: foto private nell'area comune, viste da tutti (C19).
  - **Oggi**: D12 limita il caso alle share di prova.
- **Confermato:** lettura del codice.
- **Correzione proposta:** nel cancello la share deve essere uguale (senza maiuscole) a `privateShare()`, altrimenti `NO_PRIVATE_AREA`. Le parti del grafo si ricostruiscono quando l'area privata cambia; un elemento di un'altra share aspetta, e lo dice.
- **Stato:** **chiusa (19/10).** Il cancello vuole la share uguale a `privateShare()` (senza maiuscole) o risponde `NO_PRIVATE_AREA`; `BackupGraph.start` ricostruisce le parti se l'area cambia; un elemento di un'altra share aspetta (log) e non ferma gli altri. Test: `AppBackupGateTest.r7_*`, `Review5aEngineTest.r7_*`, `Review5aSafetyTest.r7_*`.

---

## Informative

Tutte con stato **aperta**.

- **I1. La vista in sola aggiunta pubblica ovunque fuori da `.mycloudgallery/`.**
  - **Dove**: `NasBackupOpsFactory.kt:117-121` (solo `isMediaPath`); `:75-78` (`mkdirs` esposta e mai usata).
  - **Il punto**: le radici `Backup/` le controlla solo il motore (`SafeUploadEngine.kt:217`, `:727`, `:741`), non il guardiano. Il guardiano costruisce il percorso sotto `Backup/Area comune/`, ma nessuno lo ricontrolla (`BackupPrivateCopyGuardian.kt:238-245`, `:589-592`).
  - **Oggi**: nessuna sostituzione e' possibile comunque.
  - **Proposta**: mettere le radici ammesse (`Backup/`, e nella 5B `Versioni/`) nella vista di `core-nas`, cosi' un errore futuro di core-backup non pubblica in `Foto/`.
- **I2. Il nome della copia del guardiano viene dal NAS.**
  - **Dove**: `BackupPrivateCopyGuardian.kt:243`, `MediaCatalog.kt:1081`.
  - **Il punto**: il nome del file dell'area comune, scelto da chi l'ha messo li', diventa il nome in `Backup/Area comune/AAAA/MM/`.
  - **Perche' non esce**: e' un componente validato da `SharePath`, sotto una radice fissa.
  - **Con R1**: un nome lungo scelto da un altro familiare basta a fermare la tappa di sicurezza.
- **I3. Fra due riletture "al sicuro" regge sul timbro (I4 della 4A).**
  - **Dove**: `MediaCatalog.kt:1677` (stesso posto con il timbro uguale: la verifica resta), `:1709` (spostamento riconosciuto), `:471-485` (spostamento per `fileId`: la verifica resta).
  - **Il punto**: una modifica sul posto che conserva dimensione e data (`touch -r`, un programma che ripristina la data) tiene "al sicuro" fino alla rotazione, al massimo un anno.
  - **Proposta**: dirlo in L37 o in un limite nuovo. Per "Libera spazio" la 5B ha la verifica sul momento.
- **I4. L'emergenza non ferma il file in corso.**
  - **Dove**: `SafeUploadEngine.kt:1007-1022` (`mayContinue` non guarda il cancello) e `:597-608`.
  - **Cosa ferma e cosa no**: il congelamento ferma la scrittura, tramite le regole (`RuleEvaluator.kt:96`, ricontrollate ogni 5 s). "Ferma le scritture sul NAS" no: `NasGraph.kt:321` vale solo per l'indice. La copia lato server del guardiano (fino a 20 GB) non si interrompe.
  - **Perche' e' informativa**: si scrive solo nei temporanei propri, e D32 dice "al passo successivo".
  - **Proposta**: guardare il cancello a ogni punto di ripresa.
- **I5. Il guardiano scrive anche se il backup non e' mai stato avviato.**
  - **Dove**: `work/BackupStages.kt:176-198` (nessun controllo della cartella del dispositivo o delle sorgenti); `work/BackupWork.kt:166`.
  - **Il punto**: un familiare che non ha mai toccato "Inizia ora" o "Stanotte" trova `Backup/Area comune/...` nella sua area privata (dopo Q3).
  - **Proposta**: e' coerente con D9 ("sempre"), ma va deciso esplicitamente e detto nella pagina.
- **I6. "Collegare" sposta la posizione di un altro media senza unirli.**
  - **Dove**: `MediaCatalog.kt:712-718` (`attachInTransaction` riscrive una posizione esistente, qualunque sia il suo media). La usano deduplica e nome occupato (`SafeUploadEngine.kt:475`, `:782`).
  - **Il punto**: il media "vecchio backup" perde la posizione senza `merge`: preferito, nascosto e identita' pubblicata non passano. Non ho trovato fughe, perche' gli stessi byte si vedono gia' dal media del telefono.
  - **Il banco non lo vede**: il catalogo finto rifiuta proprio questo caso (`InMemoryBackupCatalog.kt:202`).
  - **Proposta**: unire i due media (hash completo uguale) invece di spostare la posizione.
- **I7. Il nascosto contagioso dipende dall'ordine delle voci sincronizzate.**
  - **Dove**: `LedgerPersonalStore.kt:125-127` (le voci si applicano nell'ordine d'arrivo); la proiezione porta il nascosto a `setPersonalFlags`, che lo propaga al gruppo (`MediaCatalog.kt:1949-1963`).
  - **Il punto**: se "nascosto" arriva prima della separazione, il gruppo vecchio lo propaga a una copia che l'utente aveva separato, e `adoptLocal` lo ripubblica.
  - **Perche' e' informativa**: sbaglia sempre dalla parte prudente (nasconde di piu').
- **I8. "Backup fermo da 3 giorni" con l'orologio del telefono.**
  - **Dove**: `BackupDiagnostics.kt:82`, `notify/NasDailySummary.kt` (`backupProblems(facts, now)` con l'ora del dispositivo), `work/BackupStages.kt:165-167`, `BackupGraph.kt:545`.
  - **Perche' e' informativa**: e' solo un avviso, come gli altri battiti della Fase 4, e non decide niente sui file.
- **I9. La copertina delle copie simili si decide anche da dati remoti.**
  - **Dove**: `CatalogSimilarCopies.kt:318-321`. Ogni copia sotto `Backup/` vale come "originale della fotocamera", anche le copie del guardiano e le cartelle "Backup" di altri strumenti; la regola della copertina usa i pixel dai metadati del file del NAS.
  - **Il punto**: un file dell'area comune preparato da un altro account (stesso secondo di scatto, miniatura simile, piu' pixel) diventa la copertina della mia cella.
  - **Oggi**: cambia solo l'aspetto. Nella 5A le azioni dalla cella non si estendono al gruppo, tranne nascondere (`CatalogLocalMediaActions.kt:29-39`).
  - **Per la 5B**: il §2.12 dice "le azioni dalla cella valgono per tutte le copie". Il foglio dovra' elencare ogni copia con il suo posto, altrimenti la portata di "Elimina" dipenderebbe da un raggruppamento che un dato remoto puo' influenzare.

---

## Cose fatte bene

- **Sola aggiunta per costruzione**:
  - marcatore proprio e test di architettura;
  - `NasFileSystem` senza operazioni distruttive;
  - il nome del temporaneo si controlla due volte, nella vista e in SMB;
  - `FILE_CREATE` esclusivo e rinomina senza sostituire;
  - `deleteOwnTemp` solo per `<io>-bk-`, su un handle esclusivo.
- **Diario prima di ogni effetto**:
  - la risposta persa si riconosce con dimensione **e** SHA-256;
  - tre tentativi di verifica, poi `VERIFY_FAILED`;
  - il banco ha provato circa 180.000 interruzioni su due motori e sul simulatore SMB con le primitive vere.
- **"Al sicuro" da una rilettura**:
  - rilettura completa ovunque (verifica, deduplica, nome occupato, risposta persa, guardiano);
  - `markVerified` confronta lo SHA **letto** con quello della versione, quindi decide il catalogo;
  - l'hash dell'indice non entra mai nel catalogo (`observationOf`);
  - E-B15 conserva l'originale come sua versione.
- **D12** e' un cancello per elemento sulla share di destinazione, provato nel bytecode della release da `check.ps1`; con "in prova" anche le verifiche si fermano.
- **D33 fallisce in modo sicuro**: se la porta si rompe, nessun temporaneo si toglie.
- **Superficie Android**:
  - nessun componente esportato nuovo, `BackupJobService` protetto;
  - ricevitore a runtime non esportato, `PendingIntent` immutabile ed esplicito;
  - notifica privata con soli numeri.
- **Privacy**:
  - nessun log in release; Diagnostica ed esportazione solo con numeri ed enumerati;
  - firme visive solo locali;
  - nascosto contagioso in ogni via di scrittura del catalogo, e nessuna copertina per un gruppo con un nascosto.
- **Ora**:
  - "oltre domani" e D34 dall'ora del NAS, mai dal telefono;
  - budget e attese con l'orologio monotono.

## Da provare sul campo

- **R3** (solo nell'area di prova, su file da buttare):
  - creare un collegamento fisico (via SSH, o `mklink /H` da Windows su una share Samba) al nome di un temporaneo `-bk-` con un punto di ripresa;
  - vedere se il WD crea il collegamento fisico via SMB e cosa riporta `FileStandardInformation.NumberOfLinks`;
  - con un collegamento simbolico, vedere se `FILE_OPEN_REPARSE_POINT` apre il collegamento o la destinazione;
  - dopo la correzione, l'elemento deve ricominciare da capo e il file collegato restare intero.
- **R1**, dopo la correzione: un file del telefono in `DCIM/mcg-prova/` con un nome da 255 byte e un file diverso con lo stesso nome in `mcg-campo/Backup/...`. Il file deve arrivare come "(2)" e gli altri media devono andare avanti.
- **R5** sull'S25: primo backup interrotto, poi "foto selezionate". Dopo la correzione le foto scelte arrivano e le altre aspettano.
- **R6**: un file in `DCIM/mcg-prova/` allungato con `dd` in coda, senza far rileggere il MediaStore; poi controllare nel viewer che non diventi "al sicuro" con la copia corta.
- **R2**: corretto il 19/10 (con la release un account di prova non scrive in nessuna share): nessuna prova sul campo da fare, ma l'APK di release con `test` o `test2` resta una prova che non carica niente ("Backup in prova"). Per provare il backup sull'S25 serve la build di debug con D13.
- **Il resto, come previsto**: il runner del backup sull'area di prova (`BackupNasIntegrationTest`, era in corso in un'altra sessione durante questa revisione) e F30 sul telefono (`s25.ps1 -Mode backup`).

## Checklist della 5A (`docs/sicurezza.md` §7, "Fase 5")

- [ ] **Prova delle 500 interruzioni sul backup di foto e video grandi: zero file a meta', duplicati o perdite; hash verificato al 100%.**
  - Fatto: banco JVM e simulatore SMB verdi (P6, `docs/prova-500.md` §B).
  - Manca: il runner sul NAS vero (in corso in un'altra sessione, esito non visto) e il telefono (F30).
  - Il banco non copriva R1 (nomi corti nelle fixture), R3 (niente collegamenti trasparenti) ne' R5 (nessun rifiuto per una riga sola): R1 e R5 hanno ora i loro scenari (`Review5aEngineTest`, `Review5aSafetyTest`); R3 no.
- [ ] **(5A) `core-backup` non puo' togliere ne' sostituire un file: test del compilatore e di architettura, provati reintroducendo la violazione.**
  - Regge per tipi e per nomi: marcatore, `Phase5ArchitectureTest` letto (non rieseguito reintroducendo le violazioni), `NasFileSystem` senza distruttivi.
  - Resta **R3**: accorciamento attraverso un collegamento al nome del temporaneo. Poi I1.
  - Si spunta quando R3 e' corretta o dichiarata come limite.
- [ ] **(5A) Nessun file visibile non verificato; "al sicuro" solo da una rilettura fatta qui (prova delle 500 interruzioni, F21-F24).**
  - Nel codice e' vero (risposta 5).
  - Restano **R6** e **I3**; F21-F24 non sono ancora fatte.
- [x] **(5A) D12 attivo alla consegna; D13 solo in debug e assente dal dex di release.**
  - La voce, cosi' come e' scritta, e' confermata dalla lettura: il cancello guarda ogni elemento sulla share di destinazione e le verifiche si chiudono con `TEST_ONLY`; D13 e' `null` in release e `check.ps1` lo cerca nel dex.
  - Pero' non copriva **R2**: la voce "in release un account di prova non carica i media veri del telefono" e' stata aggiunta (19/10) alla stessa riga della checklist di `docs/sicurezza.md` e provata dal passo di `check.ps1`.
- [x] **(5A) I temporanei del backup non li toglie la pulizia dei propri file finche' il diario li cita.**
  - Confermato in lettura (risposta 9).
  - Restano i temporanei di un dispositivo azzerato, gia' dichiarati nel §1.4 del progetto.
- [x] **(5A) Deduplica e guardiano non leggono aree private altrui.**
  - Confermato in lettura (risposta 6): query filtrata per account, share e area privata, share ricontrollata dal cercatore; il guardiano legge solo sorgenti dell'area comune, con la sessione dell'account.

Le voci (5B) e le cinque voci generali della Fase 5 che riguardano la 5B ("Libera spazio", condivisioni in arrivo, posizione, `FileProvider`, "Modifica con…") restano fuori da questa revisione.

Delle voci "per ogni fase, sempre":
- nessun permesso o componente esportato nuovo nella 5A (confermato);
- log, Diagnostica e notifiche senza nomi, percorsi, share, account, id o impronte (confermato in lettura; resta la sessione vera);
- questa revisione vale come passo di `security-review` della 5A.
