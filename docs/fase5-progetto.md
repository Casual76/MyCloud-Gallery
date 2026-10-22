# Fase 5 di MyCloud Gallery: progetto tecnico (backup, versioni, integrazioni)

> Base per i brief dei sub-agent. Fonti:
> - piano: FASE 5 (5.1–5.7), §1 (glossario: copia privata, preparazione), §2 ("Backup, file, versioni", "Integrazioni con le altre app"), §3 (proposte 1, 2, 3, 8, 10, 12), §4 (prova delle 500 interruzioni, "l'app non cancella mai definitivamente da sola", "nessuna azione distruttiva da dati letti dal NAS", consumi e stime entro ±30%), §6 (E-B, E-E, E-X, E-D, E-L, E-S, E-P), §7;
> - `docs/fase4-progetto.md` (§1.5, §2.7, §2.10, §2.11, §5.4, §5.5, F4-D8, F4-D20, F4-D22, F4-D24–F4-D33) e il **codice** della Fase 4: `core-nasops` (diario, `OpStateMachine`, `UserConfirmation`, `ConfirmationSheet`, `OwnFilesCleaner`), `core-nas/ops` (`NasFileOps`: `openTemp` + `NasTempWriter`, `publishTemp`, `copyToTemp`; `SmbNasFileOps`; `NasOpsPaths`), `core-prepare` (`Preparer`, `PrepareSource.Bytes`/`Local`), `core-index` (`IndexPublisher`, `IndexWriteGate`, `OwnIndexSink`), `core-mediastore` (`MediaStoreSource.open` con `setRequireOriginal`, `PhoneMediaIntents`, `PhoneMediaWriter`), `core-rules` (`StagedRun`, `BacklogFacts.toProtect`, `DeviceMeasures`), `core-database` v3 (`MediaCatalog.attachCopy`, `markVerified`, `correct`, `restoreOriginal`), `core-model` (`SafetyRules`, `VersionRules`, `LinkingRules`, `CellFlags`), `core-family` (passo 6 dell'onboarding, `LocalDeviceProfile`), `app/.../work/` e il manifest;
> - `docs/modello-dati.md` (§4, §5, §11), `docs/formato-nas.md` (§2, §5.5-bis, §5.7, §9), `docs/regole-attivita.md`, `docs/android.md` (temi 1, 4, 5, scoperte 7–11; T-09, T-11, T-14, T-15, T-16), `docs/sicurezza.md` (C10, C12, C13, C17, C18, C19, §4-bis, L1–L36, checklist della Fase 5), `docs/decisioni.md` (anche "Fase 4B" e "Revisione di sicurezza della 4B"), `docs/inventario-nas.md`, `docs/prova-500.md`, `docs/review/4a-fase4a.md`, `docs/review/4b-fase4b.md`, `docs/grafica-progetto.md` §3, §11, §12.
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F4-Dn` = decisione n della Fase 4 (`docs/fase4-progetto.md` §0.1); "R8 della 4B" = voce della revisione di sicurezza; `Fn` = prova sul campo (la Fase 4 ha usato F1–F20, qui F21–F45); `Qn` = domanda del §9.
>
> Contesto:
> - **La Fase 4 è integrata** (4A e 4B, revisioni di sicurezza corrette il 14 e il 15/10). Il NAS è nella Libreria, le operazioni sui file passano dal diario di `core-nasops` con la conferma dell'utente, il banco delle 500 interruzioni è verde sulla JVM (2.000 giri per stack) e sul simulatore SMB con le vere `SmbNasFileOps` (600 giri). **Le prove sul campo F1–F20 non sono ancora state fatte**, tranne F16: il 15/10 il runner contro il NAS vero ha superato la prova delle 500 interruzioni (537 giri, 400 interruzioni, 0 violazioni; `docs/prova-500.md` §6bis) e la suite d'integrazione sul NAS vero è verde. La Fase 5 scrive i media dell'utente: il §8 mette le altre prove prima di M2.
> - **Cosa la Fase 4 ha lasciato pronto** (verificato nel codice): `NasFileOps.openTemp` + `NasTempWriter` (scritture a offset, `finish` che controlla la lunghezza sullo stesso handle, "dalla Fase 5, per il caricamento"), `publishTemp` (rinomina senza sostituire, crea le cartelle che mancano), `copyToTemp` (copia lato server anche tra share); `Preparer` con `PrepareSource.Bytes` e `Local` ("dalla Fase 5 i byte del telefono"); `IndexPublisher` ("dalla Fase 5, l'uploader"); `MediaCatalog.attachCopy` (copia scritta dall'app, con `verifiedAtMillis`), `markVerified`, `correct`, `restoreOriginal`; `SafetyRules.evaluate` e `canFreePhoneCopy` (con `CREATE_PRIVATE_COPY_FIRST` e `VERIFICATION_TOO_OLD`); `BacklogFacts.toProtect` nello stimatore; il passo 6 dell'onboarding salva già "fotocamera" e "screenshot".
> - **Cosa la Fase 4 prometteva e nel codice non c'è**: il `VerifyWorker` del §2.11 e un esecutore della fase `FULL_HASH` che il catalogo mette in coda (F4-D21). **Nessun codice chiama `markVerified`**: oggi il segno "al sicuro" non può comparire. Inoltre `OwnFilesCleaner` protegge solo i temporanei citati dal diario delle operazioni: un temporaneo del backup fermo da più di un giorno verrebbe tolto dalla pulizia giornaliera. La Fase 5 chiude entrambi (D14, D33).
> - **Android**: le prove T-09 (cestino), T-11 (byte originali e hash), T-14 (selettori), T-15 (condivisioni in arrivo) e T-16 ("Modifica con…") di `docs/android.md` non sono mai state fatte. Le strategie di 5.5 e 5.7 poggiano su letture di sistema e documentazione: la 5A ha bisogno solo di T-11 (la prova M1), la 5B fa le altre all'inizio (P0-B).
> - **Il telefono**: l'S25 (Android 17) aveva il 12/10 **6.274 immagini e 384 video** nel MediaStore (`docs/android.md` 4.2 bis): 2.105 di app di messaggistica, 1.088 in una sottocartella di `DCIM`, 915 screenshot, 866 in `DCIM/Camera`, 654 di Quick Share, 224 in `Pictures`. **Le dimensioni non sono mai state misurate**: il §2.9 le ipotizza, il riepilogo del primo backup userà quelle vere del MediaStore. Il Tab S9 (Android 16, profili) ha 834 immagini e 56 video.
> - **Il NAS**: sempre acceso, 2,42 TB liberi, scrittura SMB cifrata **9 MB/s** con un flusso (S25), lettura **16 MB/s** con quattro, copia lato server 124 MB/s nella stessa share e 148 MB/s tra share. **Nessun backup esterno** (scelta dell'utente, S10): il RAID 1 copia anche i danni.
> - **Prove**: contro il NAS vero solo nell'area di prova (`mcg-it/<giro>/` di `test`, `test2`, `test-comune`) dietro la guardia (F4-D30) e, sul telefono, con la recinzione delle share (F4-D33). `NAS` e `Public` restano pubbliche: ogni recinto è da codice.
> - **In parallelo**: il filone grafico (G2 fatto, G3 viewer da fare). Le modifiche della 4B sono tutte committate (R11, "Lascia i nascosti dove sono"; l'ultima il 15/10: la cartella in uso dall'app è un'attesa breve, non un errore).

## 0. Sintesi

**Che cosa consegna la Fase 5**
- Il **backup automatico** dei media del telefono nell'area privata, in `Backup/<dispositivo>/AAAA/MM/` per data di scatto: foto prima dei video, dal più recente, con le regole delle attività; il primo backup con un riepilogo e una stima onesta.
- Un **caricamento che non lascia mai un file a metà e non sovrascrive mai**: temporaneo invisibile, rilettura completa dal NAS con lo SHA-256, nome definitivo senza sostituire, ripresa dei video grandi; provato con 500 interruzioni.
- **"Al sicuro" vero**: solo una copia privata riletta qui con lo SHA-256 giusto; deduplica byte per byte; copie private ricreate dall'area comune; riverifica a rotazione.
- **Versioni**: la versione attuale sempre al solito percorso, le precedenti in una cartella documentata; modifiche da Samsung Gallery, correzioni dall'app, "Vedi originale" e "Ripristina originale".
- **"Libera spazio"** con verifica sul momento e cestino di Android, e cancellazioni dal telefono che non lasciano mai un media senza copia sotto il controllo del proprietario.
- **Stato del backup** in Libreria, nel viewer e in una pagina con errori e azioni.
- **Integrazioni**: scegliere foto per le altre app (anche quelle solo sul NAS), ricevere condivisioni ("Salva sul NAS"), "Modifica con…", condividere senza posizione.

**Moduli nuovi**
- `core-backup`: piano, caricamento (diario e macchina a stati), verifiche, deduplica, guardiano delle copie private, stato; nella 5B versioni, correzioni, "Libera spazio", attività in sospeso, caricamenti in arrivo. Vede il NAS **solo** con primitive in sola aggiunta (D2);
- `feature-backup` (5A e 5B): impostazioni e pagina del backup, primo backup, versioni, "Libera spazio", attività in sospeso;
- `feature-integrations` (5B): selettore per le altre app, provider di documenti, ricezione delle condivisioni, sessioni di "Modifica con…", condivisione senza posizione.

**Aggiunte ai moduli esistenti**
- `core-nas`: la vista `NasBackupOps` delle primitive di `SmbNasFileOps`, in sola aggiunta, con `reopenTemp`; nella 5B `archiveVersion` e `restoreVersion`;
- `core-model` e `core-database`: schema **v4** (diario del backup e tabelle della 5B), bit `BACKUP_PROBLEM`, query dei candidati e della rotazione;
- `core-rules`: misure del backup (rilettura, costo per file), regola "Proteggi subito le foto nuove" (D34, Q7);
- `core-nasops`: la pulizia dei propri file rispetta i temporanei del backup (D33);
- `core-metadata` (5B): scrittura di data, luogo e orientamento, rimozione della posizione;
- `core-mediastore` (5B): `createDeleteRequest`, `createWriteRequest`, riscrittura di un media con il permesso di sistema;
- `core-library`, `feature-viewer`, `feature-settings`, `feature-diagnostics`, `:app`.

**Diciotto pacchetti, in due metà** (P15 e P16 aggiunti il 16/10 con le risposte Q11–Q13)
- **5A "Il backup al sicuro"** (P0–P7 e P15 copie simili): 5.1, 5.2, 5.3, 5.6. Sul NAS si **aggiungono** soltanto file nuovi, pubblicati senza sostituire; dal telefono non si toglie niente.
- **5B "Versioni, Libera spazio, integrazioni"** (P0-B, P8–P14): 5.4, 5.5, 5.7. Le azioni che spostano file sul NAS (versioni), riscrivono un file del telefono (correzioni) o tolgono copie dal telefono arrivano dopo la revisione della 5A e il primo backup vero.
- Opus solo per i cinque pacchetti fondanti o pericolosi: P0 (fondamenta), P2 (caricamento sicuro), P3 ("al sicuro"), P8 (versioni), P9 ("Libera spazio"); sonnet per gli altri tredici (§4.4).

**Tappe**
- **M1** (5A): una foto del telefono caricata, riletta e "al sicuro", con lo scudo in Libreria (emulatore con il NAS finto e S25 sull'area di prova).
- **M2** (5A): primo backup completo e riprendibile di un set di prova con video da GB, stima entro ±30%, prova delle 500 interruzioni superata.
- **M3** (5B): una modifica da Samsung Gallery diventa una versione, "Libera spazio" esclude una copia rovinata, i tre flussi con WhatsApp, Gmail e Samsung Gallery.
- Chiusure: 5A con la revisione di sicurezza; 5B con la sua prova delle interruzioni, la revisione e, con il consenso (Q3, risposta (b)), il primo backup vero dell'S25 sull'area privata con l'APK di release.

**Test senza dispositivo**: il NAS finto e il simulatore SMB della Fase 4, più un **telefono finto** (`FakeMediaStore` esteso con file che cambiano, spariscono o cambiano `_ID` a metà lettura) per il banco delle interruzioni del backup.

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)
| # | Decisione | Perché |
|---|---|---|
| D1 | **Fase divisa in 5A e 5B** (§4.2, Q1). 5A: backup, "al sicuro", stato. Sul NAS crea solo file nuovi, mai sostituiti né spostati; sul telefono non tocca nulla. 5B: versioni (spostano l'attuale nella cartella delle versioni), correzioni (riscrivono un file del telefono con il dialogo di sistema), "Libera spazio" e cancellazioni (tolgono copie dal telefono), integrazioni (porte d'ingresso nuove) | Il backup è il valore più grande con il rischio più basso; ciò che toglie o sposta arriva su un backup già provato (500 interruzioni, revisione, primo backup vero), come la 4B dopo la 4A |
| D2 | **`core-backup` vede il NAS solo con primitive in sola aggiunta**: `NasBackupOps`, una vista di `SmbNasFileOps` con un marcatore suo (`@AppendOnlyNasAccess`, `@RequiresOptIn` di livello errore): `mkdirs`, `openTemp`, `reopenTemp` (nuova, D5), `copyToTemp`, `publishTemp`, `deleteOwnTemp`, `space`; nella 5B `archiveVersion` e `restoreVersion` (D17). **Niente** `trashMove`, `rename` libera, `deleteIfUnchanged`. `core-backup` non vede `NasFileOps`, `DestructiveNasAccess` né `core-nasops`; ciò che toglie un file dal NAS (originale di un video, versioni intermedie) passa dal foglio di `feature-nas` e da `core-nasops` con `UserConfirmation` | Il backup gira da solo, ogni notte, partendo da dati locali e senza una conferma per file: deve essere **incapace** di togliere o sostituire un file per costruzione. Lo verificano il compilatore e `Phase4ArchitectureTest` esteso |
| D3 | **Caricamento con diario e macchina a stati** (`backup_item`, schema v4, §2.2): `PLANNED → HASHED → UPLOADING → UPLOADED → VERIFIED → PUBLISHING → PUBLISHED → LINKED → DONE`, con le uscite `LINKED_EXISTING` (copia privata identica già presente, D8), `GONE` (sparito dal telefono prima della lettura, E-B4) e `FAILED`. Ogni valore di un passo (temporaneo, punto di ripresa, nome finale) si scrive **prima** di usarlo, ogni passo è idempotente; la risposta persa della pubblicazione si riconosce come in F4-D24 (temporaneo assente + destinazione con dimensione e SHA-256 giusti = pubblicato) | Stesso metodo della 4B, che il banco ha già provato; tabelle proprie perché la macchina è diversa e non nasce da una conferma |
| D4 | **La verifica è una rilettura completa del temporaneo dal NAS, prima del nome definitivo** (`readAll` a 4 flussi, SHA-256 uguale a quello calcolato sui byte del telefono). Dopo la pubblicazione si controlla solo il timbro (dimensione, data e `fileId` uguali al temporaneo verificato): la copia è "verificata" senza una seconda lettura. Una verifica che non torna toglie il temporaneo e rifà il caricamento (al massimo 3 volte, poi `VERIFY_FAILED`, E-B18) | "Nome temporaneo, verifica dell'hash completo, poi nome definitivo" (piano 5.2): nessun file visibile non verificato. La cifratura SMB3 protegge il trasporto, non gli errori nostri, del server o di un altro scrittore. Costo: il traffico raddoppia (§2.9). Limite sulla cache del NAS dichiarato (L37) |
| D5 | **Ripresa dei file grandi** (E-B1): ogni 64 MiB lo scrittore fa `flush`, controlla la lunghezza sullo stesso handle e scrive nel diario il punto di ripresa; dopo un'interruzione `reopenTemp(temp, keepBytes)` riapre il **proprio** temporaneo senza crearlo, lo accorcia al punto salvato e riprende da lì. La verifica finale rilegge tutto il file. Timbro del file del telefono cambiato = si ricomincia da zero | Un video da 3 GB a 9 MB/s dura 6 minuti: senza ripresa un Wi-Fi instabile non finisce mai. Fidarsi del prefisso è sicuro perché la verifica completa viene comunque dopo |
| D6 | **Destinazione e nomi**: `/<area privata>/Backup/<cartella del dispositivo>/AAAA/MM/` con anno e mese della **data di scatto** nell'ora del luogo (`local_day` del catalogo), ripiego sulla data del file; date assurde (prima del 1990 o oltre domani) e assenti = `Senza data/` (E-B17). Fotocamera in `AAAA/MM/`; screenshot e cartelle aggiuntive in `<Nome>/AAAA/MM/` (Q8). Nome = nome del file sul telefono, validato con `SharePath` (E-N6); se il nome è occupato si rilegge il file che c'è: **identico** → si collega (riprese, reinstallazioni); diverso → "nome (2)", scritto nel diario prima di riprovare (E-B3). Mai una sostituzione | Piano §2 e 5.1. L'inventario ha file datati 1979: una cartella `1979/01` sarebbe rumore |
| D7 | **Cartella del dispositivo** scelta **una volta**, al primo backup: il nome del dispositivo ripulito per `SharePath` (es. "S25 di Mario"), salvato nelle impostazioni del backup. Rinominare il dispositivo non la cambia (E-B7); due dispositivi dello stesso account non la condividono (E-B6: un nome già presente in `Backup/` fa chiedere "continua lì" o "nuova cartella"); dopo una reinstallazione o un telefono nuovo (E-O7, E-O8, E-B16) si propone di continuare nella cartella esistente, e la deduplica collega senza ricaricare | Una cartella per dispositivo (piano §2) senza dipendere dall'id del dispositivo, che cambia a ogni reinstallazione |
| D8 | **Deduplica byte per byte** (5.3, E-B5): candidati **solo nell'area privata del proprietario** (mai un'altra area privata, mai l'area comune), con la stessa dimensione e l'impronta rapida letta qui (o un hash completo calcolato qui); il candidato si **rilegge per intero**: SHA-256 uguale → `attachCopy` verificata, nessun caricamento; diverso o illeggibile → si carica. Un hash dall'indice non basta (F4-D8) | "Nel dubbio si carica" (piano §2). La rilettura costa meno del caricamento (16 contro 9 MB/s) e capita di rado: riprese, reinstallazioni, copie fatte dal PC |
| D9 | **Guardiano delle copie private** (5.3, E-B13, E-B14, Q4): per un media **proprio** la cui sola copia sul NAS è nell'area comune, la copia privata si crea con la **copia lato server** dall'area comune a un temporaneo dell'area privata (124–148 MB/s), verifica completa contro l'hash della versione, pubblicazione in `Backup/Area comune/AAAA/MM/`. Parte dal catalogo locale (stato `ONLY_COMMON_COPY`), legge la sorgente e scrive solo nell'area privata, con un tetto di 20 GB per giro. Quando: sempre prima di "Libera spazio" e di "Elimina solo dal telefono"; quando la copia del telefono sparisce (E-B13); non subito dopo uno spostamento nell'area comune (risposta Q4 (b)) **Nota della Fase 7 (D28) e delle correzioni dal campo A (22/10):** "quando la copia del telefono sparisce" vale per un media che il telefono **ha avuto**: se la copia era gia' sparita prima di uno spostamento nell'area comune, il guardiano parte **subito dopo** lo spostamento (`SafeCopyRequests.requestNow` di P0, chiamata dall'orchestratore della condivisione); un media mai stato sul telefono non ha copia privata automatica (L44, L62). | La copia comune la può cancellare un familiare, e il RAID 1 copia il danno (L2). Il guardiano crea, non cancella: un dato remoto falso costa al massimo una copia in più |
| D10 | **Chi carica prepara** (piano §1, F4-§2.11): miniature, anteprime e fotogrammi si fanno dai **byte del telefono** (`PrepareSource.Bytes` su `ContentUriMediaBytes`, foto lette con `setRequireOriginal`) nella stessa lettura dell'hash. Il record va all'indice con il timbro letto **dopo** la pubblicazione (la scansione lo adotta senza rilevarlo, F4-D6). Con F4-D31 acceso (share vere) o con una share non pubblicabile le uscite restano nella replica locale alle classi del livello di qualità, senza outbox (R2 della 4A); quando la pubblicazione si apre, la preparazione rifà ciò che manca | Nessun originale riletto dal NAS per prepararlo; nessun arretrato di GB sul telefono |
| D11 | **Ordine e attività**: prima le foto (`PHOTO_BACKUP`, leggera), poi i video (`VIDEO_BACKUP`, pesante, regole proprie, solo sul Wi-Fi di casa con la proposta 1); dal più recente al più vecchio; due file in volo (uno in caricamento, uno in lettura, preparazione o verifica). Lavori a tappe da 8 minuti con `StagedRun` (F4-D22). Il **primo backup** e "Fai ora" sono lavori avviati dall'utente (UIDT, `BackupJobService`; il permesso è già approvato dalla 4B) con la notifica di avanzamento e "Ferma"; "Fai ora" sui dati mobili chiede conferma con la stima (proposta 10) | Piano 5.1, proposte 1, 10, 12; `android.md` 1.3: nessun servizio in primo piano notturno |
| D12 | **Backup solo sulle share di prova fino alla fine della 5B** (come F4-D31; risposta Q3 (b)): `FeatureFlags.backupTestSharesOnly = true`, anche in release e non spegnibile da `forBuild`. Sulle share vere lo stato dice "Backup in prova" e niente si scrive. Lo spegne l'utente con la risposta a Q3 | La prima scrittura di media in `alessio` arriva dopo una tappa approvata, con l'APK di release |
| D13 | **Recinzione del telefono in debug con gli account di prova** (come F4-D33, build debug con `test` o `test2`): il backup legge **solo** `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (seminate con `adb`) e scrive in `mcg-campo/Backup/` delle share di prova; "Libera spazio", correzioni, "Ripristina originale" e "Modifica con…" toccano solo i media di quelle cartelle. Nella release non esiste (`DeveloperTools` di release; `check.ps1` lo cerca nel dex). **Revisione 5A, R2: per questo, in release, un account di prova non scrive in nessuna share**: il cancello risponde `TEST_ONLY` per ogni share (`FeatureFlags.backupBlocksTestAccounts`, vero in `RELEASE`, falso nelle build di debug dove la recinzione c'e'; decisione pura `backupMayWriteAs` in `core-common`), e `check.ps1` lo prova nel bytecode della release (`AppBackupGate.stateOf` chiama `backupMayWriteAs` e risponde `TEST_ONLY`). In debug resta com'era: D13 recinta il telefono e il backup scrive solo in `mcg-campo/Backup/`. Fino a Q3 la release con `test` o `test2` non carica niente | Senza, una prova sull'S25 caricherebbe le foto vere della famiglia nella share di prova (password di prova, L25) e "Libera spazio" potrebbe togliere foto vere dal telefono; e senza R2 l'APK di release con un account di prova le caricherebbe lo stesso (nessuna recinzione) |
| D14 | **Verifiche e hash completi in `core-backup`** (`SafetyVerifier`, P3): rilettura delle copie private non verificate (`PRIVATE_COPY_UNVERIFIED`), esecuzione della fase `FULL_HASH` che il catalogo mette in coda (F4-D21), **verifica sul momento** per "Libera spazio" (D21) e **riverifica a rotazione** (Q10): ogni copia privata riletta almeno una volta all'anno, dalla verifica più vecchia (~140 MB per notte con 50 GB). Una copia che non torna perde "al sicuro" (`markVerified` falso), si ricarica dal telefono se la copia c'è ancora, altrimenti avviso (E-B8) | La Fase 4 li prometteva (§2.11) ma nel codice non ci sono; "al sicuro" nasce solo da una rilettura fatta qui (L28). Il RAID 1 non vede la corruzione silenziosa: la rotazione la scopre finché c'è ancora la copia del telefono |
| D15 | **Segni per media**: `PHONE_ONLY` (non ancora sul NAS) e `SAFE` restano quelli della Fase 4; un bit nuovo `BACKUP_PROBLEM` (bit 14 di `CellFlags`, oggi libero) per un errore che chiede l'utente (illeggibile, verifica fallita tre volte, sparito prima del backup); "in coda", "in caricamento" e "in verifica" **solo** nel viewer e nella pagina del backup | Un segno che cambia a ogni file caricato farebbe ricostruire l'indice della timeline migliaia di volte durante il primo backup (guardia delle prestazioni, F4-§5.6) |
| D16 | **Stato del backup** come contratto (`BackupStatus` in `core-backup`, `LibraryBackupStatus` in `core-library`, §1.5): terza voce del piede della Libreria (sotto rete e NAS, con i componenti G2 esistenti), pagina "Backup" per sorgente con errori e azione (§2.6), riga nel pannello info del viewer. Notifiche (proposta 12): avanzamento solo per il primo backup e "Fai ora"; problemi nel riepilogo giornaliero (`NasDailySummary`), al massimo una volta al giorno; "backup fermo da 3 giorni" dal battito (E-P15) | Piano 5.6; stesso confine fra logica e aspetto della Fase 4 (F4-D27) |
| D17 | **Versioni sul NAS** (5.4, Q2): la versione **attuale** sta sempre al solito percorso; le precedenti in `<share>/Versioni/<cartella del file>/` con nomi leggibili ("IMG_1234 (originale).jpg", "IMG_1234 (versione del 2026-10-15 14.32).jpg"). Ordine: versione nuova caricata e **verificata** nel temporaneo → `archiveVersion` dell'attuale in `Versioni/` (rinomina nella stessa share, senza sostituire, con il timbro verificato) → pubblicazione della nuova al solito percorso. Ogni passo nel diario; nessuna cancellazione automatica (le intermedie si tolgono solo a mano, con il cestino del NAS) | "Dal PC si vede sempre la versione attuale al solito percorso; le precedenti stanno in un posto documentato e sfogliabile" (piano 5.4). La rinomina non perde byte; verificare prima di spostare tiene vuoto il solito percorso solo per un istante |
| D18 | **Modifiche fatte sul telefono** (E-E1, E-E2, E-E5): la lettura del telefono le riconosce già (`ContentChanged`, `RevertedToVersion` di `LinkingRules`); il backup carica la versione nuova con D17. "Ripristina originale" di Samsung Gallery = ritorno a una versione nota: sul NAS si **scambiano** le due (l'attuale in `Versioni/`, l'originale al solito percorso), senza caricare niente. "Salva copia" fuori da una sessione di "Modifica con…" = media nuovo (piano 5.4); dentro una sessione, versione (D25) | Le regole del catalogo esistono (`VersionRules`): la Fase 5 le porta sul NAS |
| D19 | **Correzioni dall'app** (data, luogo, rotazione; 5.4, E-L8): scritte **nel file** dove si può senza dipendenze nuove: JPEG, PNG e WebP con `ExifInterface` (già nell'APK); HEIC, RAW e video secondo Q6. Su un media del telefono: **prima deve essere al sicuro** (l'originale sul NAS), poi `createWriteRequest` (dialogo di sistema), file corretto scritto su un temporaneo locale, riletto e decodificato, poi copiato nella riga del MediaStore; il backup carica la versione con D17. Su un media solo-NAS: copia locale dell'originale, correzione, caricamento come versione | "Scritte nel file come nuova versione, con l'originale conservato" (piano §2); un file del telefono si riscrive solo con il permesso di sistema e solo quando l'originale è già al sicuro |
| D20 | **Video modificato e salvato sopra l'originale** (5.4, E-E4): la versione nuova va sul NAS con D17 e nasce un'**attività in sospeso** "Hai modificato questo video. L'originale occupa X sul NAS: vuoi eliminarlo?". Sì → il foglio "Elimina…" di `feature-nas` (cestino del NAS per 30 giorni, `UserConfirmation`); No o ignorato → resta come versione e l'attività resta in "Da decidere" | Piano 5.4; l'unica cancellazione passa dal modulo che può cancellare (D2) |
| D21 | **"Libera spazio"** (5.5): (1) foglio con l'**avviso forte** (nessun backup esterno del NAS) e il riepilogo (numero e GB; filtri "tutti", "solo video", "più vecchi di un anno"; di default esclusi gli ultimi 30 giorni, Q9); (2) guardiano (D9) per i media con la sola copia comune; (3) **verifica sul momento**: rilettura completa di ogni copia privata in un lavoro avviato dall'utente, con avanzamento; vale 6 ore di orologio monotono e non sopravvive a un riavvio; (4) appena prima del dialogo, controllo del **timbro** di ogni copia; (5) dialogo di sistema `createTrashRequest` a lotti di 2.000 (cestino di Android, 30 giorni) oppure "Libera subito" (`createDeleteRequest`) dopo una seconda conferma con due gesti distinti (R6 della 4B); (6) mai: non verificati, in errore, in caricamento, con una versione da proteggere non al sicuro, con l'area privata congelata (E-B22), con il NAS irraggiungibile (E-B9). La conferma è legata al piano: `PhoneConfirmation`, come `UserConfirmation` (impronta della selezione, 10 minuti, costruita solo dal foglio) | Piano 5.5 e §4: il definitivo è solo "Libera subito", con due conferme e il dialogo di sistema |
| D22 | **Cancellazioni dal telefono** (5.5, E-D1, E-D14): il NAS non si tocca; "Elimina solo dal telefono" di un media **non al sicuro** avvisa e propone "Prima mettilo al sicuro" (preselezionata: backup e verifica, poi il cestino di sistema); per un media con la sola copia comune parte prima il guardiano. "Ovunque" (4B) comprende le copie di **tutte** le versioni, elencate nel foglio (E-D15) | "Cancellare dal telefono non cancella dal NAS" (piano §2) e 5.3: nessun media resta senza una copia sotto il controllo del proprietario |
| D23 | **Scegliere foto per le altre app** (5.7, T-14): il selettore di sistema è chiuso (`android.md` scoperta 10), quindi `PickerActivity` per `GET_CONTENT` e `PICK` di immagini e video, con selezione multipla, **separata** dalla `MainActivity` (`singleTask`, non restituisce risultati a un altro task), con il blocco dell'app e **mai** i nascosti; `MediaDocumentsProvider` (radice "MyCloud Gallery": Recenti, Cartelle NAS) per i selettori di documenti (Gmail, "Documento" di WhatsApp e Telegram). Un media solo-NAS si consegna con un descrittore a richiesta (`StorageManager.openProxyFileDescriptor` sopra `NasMediaReader`, con scadenze), oppure scaricato prima in cache se T-14 lo chiede; NAS irraggiungibile = errore subito con il motivo (E-X1) | Le sole vie possibili; nessuna promessa sul Photo Picker |
| D24 | **Ricevere condivisioni** (5.7, E-X2, T-15): `ShareReceiverActivity` per `SEND` e `SEND_MULTIPLE` di `image/*` e `video/*`; i flussi si aprono e si copiano **subito** in `noBackupFilesDir/incoming/` con nomi derivati dall'hash (tetti: 2 GiB per file, 4 GiB e 500 elementi per condivisione, almeno 1 GiB libero dopo la copia), tipo dai byte (`MediaSniffer`), rifiuto di `file://` e degli URI dei provider dell'app; poi "Salva sul NAS" nella cartella scelta con Cartelle NAS (area comune con l'avviso "lo vedranno tutti i familiari") come caricamento di `core-backup` (lavoro avviato dall'utente se grande). "Aggiungi ad album" arriva con la Fase 7 **Nota della Fase 7 (D35):** "Aggiungi ad album" nella ricezione e' di P10 della Fase 7 (`feature-integrations`); verso un album condiviso apre sempre il foglio di conferma della condivisione (D12). | I file in arrivo sono input non fidato (C18); il permesso di lettura non dura oltre l'activity (`android.md` 5.3) |
| D25 | **"Modifica con…"** (5.7, E-E3, E-X3, T-16): media del telefono → `ACTION_VIEW` verso Samsung Gallery con pacchetto esplicito e una **sessione di modifica** (media, riga, timbro, 30 minuti): la riga cambiata è una versione (D18); una riga nuova nella stessa cartella con la stessa data di scatto **durante la sessione** è una versione dello stesso media (E-X3). Media solo-NAS → copia in cache servita da `FileProvider` in lettura e scrittura con `ACTION_EDIT` (Google Foto, OneDrive), permessi revocati al ritorno, byte confrontati; oppure "Scarica e modifica in Galleria" (copia in `Pictures/MyCloud Gallery`, F4-D20: la modifica diventa una versione del media del NAS, E-E7) | Samsung Gallery non risponde ad `ACTION_EDIT` (scoperta 11): l'esito si legge dal MediaStore |
| D26 | **Condividere senza posizione** (E-X5, C18): con "Rimuovi la posizione" acceso si condivide una **copia in cache** senza GPS: JPEG, PNG e WebP con `ExifInterface`; MP4 e MOV rinominando in `free` le scatole della posizione (stessa dimensione, nessuna ricodifica); HEIC convertito in JPEG. Ogni copia si **ricontrolla** con l'estrattore dell'app e con `MediaMetadataRetriever`: se resta una posizione la condivisione si ferma con il motivo. `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` sempre (Android 17/18) | "Il file condiviso non contiene GPS (verificato)": si dimostra, non si presume |
| D27 | **Schema v4** (migrazione 3→4 mai distruttiva, `ExportedSchemaTest` 1→4, 2→4, 3→4): `backup_item` (diario), `pending_task` (attività in sospeso), `edit_session`, `free_space_run` e `free_space_item`, `incoming_item`; indice su `media_location.verified_at` (rotazione); bit `BACKUP_PROBLEM`. Le tabelle della 5B nascono già nella v4 | Una sola migrazione sul campo per tutta la fase (come le tabelle della 4B nella v3) |
| D28 | **Nessuna dipendenza nuova**: `ExifInterface` (già presente) per scrivere e togliere metadati; il lettore MP4 di `core-prepare` (`Mp4Scout`) per le scatole della posizione; `ImageDecoder` + `Bitmap.compress` per HEIC → JPEG; `StorageManager.openProxyFileDescriptor`, `DocumentsProvider` del framework e `FileProvider` di androidx.core (già nel grafo) | Piano §7.9 |
| D29 | **Permessi e componenti** (approvati, Q5 (a)): nessun permesso nuovo. Componenti nuovi: `BackupJobService` (5A, non esportato, `BIND_JOB_SERVICE`); nella 5B `PickerActivity` e `ShareReceiverActivity` (esportate solo con i loro filtri, nessuna azione senza un tocco), `MediaDocumentsProvider` (esportato, protetto da `MANAGE_DOCUMENTS`), `FileProvider` (non esportato, `grantUriPermissions`, solo `cacheDir/share/` e `cacheDir/edit/`). Nessun `MANAGE_MEDIA`, nessun accesso a tutti i file, nessun servizio in primo piano | Ogni componente esportato è una porta d'ingresso (C17): elenco chiuso in `releaseManifestPolicy` e in `sicurezza.md` §4-bis |
| D30 | **UI come stati e contratti** (F4-D27): `feature-backup` e `feature-integrations` consegnano ViewModel e stati sigillati (§1.5) con composable funzionali fatti **solo** con i componenti G2 esistenti (chrome condivisa, capsule tonali del piede, `GalleryConfirmSheet` con lo scudo contro le sovrapposizioni, bersagli da 48 dp, testo a 1,3x); nessuno stile nuovo | Il filone grafico rifinisce senza toccare la logica |
| D31 | **Ora**: il backup non ha scadenze e non dipende dall'ora del NAS (le cartelle vengono dalla data di scatto); la validità della verifica sul momento e delle conferme si misura con l'orologio monotono; le attività in sospeso e i dati firmati usano l'ora del NAS (F4-D32). Un salto dell'ora non ferma il backup | Nessuna scadenza, nessun salto che possa farla scattare prima del tempo |
| D32 | **Congelamento ed emergenza** (F4-D10, C11): backup, guardiano, versioni e "Libera spazio" si fermano con la share congelata o con "Ferma le scritture sul NAS"; ciò che fa il backup non fa scattare il congelamento: i file nuovi non contano, e i solito-percorso cambiati dalle versioni si riconoscono per identità dal diario del backup (franchigia come R8 della 4B) | Un ransomware non deve trovare un backup che "aggiorna" copie cifrate né un "Libera spazio" che si fida di loro |
| D33 | **Temporanei del backup**: `.mycloudgallery/tmp/<io>-bk-<id>` nella share di destinazione (cartella col punto, nessuna estensione: invisibili alla scansione, a DLNA e alle viste normali del PC). `OwnFilesCleaner` riceve i temporanei citati da **entrambi** i diari (porta `CitedTemps` composta da `:app`); la pulizia di `core-nas` (`SmbNasHousekeeping`) riconosce solo i suoi nomi da 32 cifre esadecimali e non tocca i `-bk-` | Oggi un temporaneo del backup fermo da più di un giorno (un video a metà, telefono fuori casa) verrebbe tolto dalla pulizia giornaliera |
| D34 | **"Proteggi subito le foto nuove"** (approvata, Q7 (a)): con qualunque preset, una foto appena scattata si carica appena c'è il Wi-Fi di casa e la batteria è sopra la soglia, anche fuori dalla fascia notte (solo foto della fotocamera, pochi MB, mai video, mai dati mobili) | Samsung "Salva" sovrascrive il file: una foto modificata prima del backup notturno non arriva mai originale sul NAS (E-B15, L38) |
| D35 | **Versioni di media non propri o in aree in sola lettura** (E-E9): nell'area comune la versione nuova si scrive accanto all'originale (stessa share, `Versioni/`) solo dopo un foglio "La modifica sarà visibile a tutti i familiari"; in un'area senza permesso di scrittura (E-N9) la modifica diventa un **media nuovo** nell'area privata (`Backup/<dispositivo>/Modificate/AAAA/MM/`) e le info lo dicono **Nota della Fase 7 (§2.4):** in un album condiviso la versione nuova nell'area comune la fanno a "Tutti possono tutto" tutti, a "Gli altri aggiungono" il creatore e chi ha aggiunto il media, a "Gli altri guardano" solo il creatore; altrimenti la modifica nasce nell'area privata (E-E9). Lo decide la tabella `AlbumPermissions` (riga `NEW_VERSION`). | "Rispettando i permessi" (piano 5.4); i permessi degli album arrivano con la Fase 7 |
| D36 | **Copie simili** (risposta Q11, §2.12): lo stesso scatto con byte diversi si carica sempre e si mostra in **una cella** (gruppo `SIMILAR_COPIES`); copertina = più pixel, poi meno compressione, poi l'originale della fotocamera; "Mostra questa come principale" e "Separa" come dati personali sincronizzati. Firma visiva solo locale e solo per i candidati con la stessa ora di scatto; nascosto contagioso nel gruppo | Niente doppioni in Libreria e niente media non protetti: l'originale del telefono va sempre sul NAS (cambia la proposta 5 del piano: le copie dello stesso scatto si uniscono in Libreria, Utility → Doppioni resta per gli scatti simili ma diversi) |
| D37 | **Riordino dei vecchi backup** (risposta Q13, §2.13): proposto dopo il primo backup vero, come piano di spostamenti della 4B con conferma; solo file propri dell'area privata che sono copie di media di un proprio dispositivo | Il backup non sposta mai niente da solo (D2); il riordino è un'azione separata, confermata e già provata dal banco della 4B |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)
- **E-B15**: il file del telefono cambia durante il caricamento (Samsung "Salva", un'app che lo riscrive): timbro o hash diversi, il caricamento si scarta e riparte con i byte nuovi; se quelli di prima erano i byte originali, l'originale non arriva al NAS (L38, D34).
- **E-B16**: la cartella del dispositivo esiste già sul NAS (reinstallazione, telefono nuovo): si propone di continuare lì; i media già presenti si collegano con la rilettura, mai sovrascritti.
- **E-B17**: data di scatto assurda o assente: cartella `Senza data/` (o la data del file se credibile), mai `1979/01`.
- **E-B18**: verifica fallita ripetuta (il NAS restituisce byte diversi): dopo 3 tentativi l'elemento si ferma con "copia non integra", avviso nel riepilogo, mai "al sicuro".
- **E-B19**: area privata congelata o "scritture ferme" durante il backup: si ferma senza file a metà e riprende con lo scongelamento.
- **E-B20**: sorgenti del backup nella build debug con gli account di prova: solo `mcg-prova` (D13).
- **E-B21**: temporaneo del backup fermo da giorni (telefono fuori casa): resta, citato dal diario; la pulizia dei propri file non lo tocca (D33).
- **E-B22**: "Libera spazio" con l'area privata congelata: impossibile, spiegato.
- **E-E8**: correzione di un formato che l'app non sa scrivere (HEIC, RAW, video): Q6.
- **E-E9**: modifica di un media dell'area comune o di un'area in sola lettura (D35).
- **E-E10**: "Ripristina originale" dall'app con la copia del telefono modificata: dialogo di scrittura di sistema; negato, la copia del telefono resta la versione modificata e l'app lo dice.
- **E-D14**: "Elimina solo dal telefono" di un media non al sicuro: avviso, "Prima mettilo al sicuro" preselezionata.
- **E-D15**: "Ovunque" su un media con versioni precedenti: anche le versioni vanno nel cestino del NAS, elencate nel foglio.
- **E-X6**: un'altra app chiede molti media solo-NAS insieme: coda con tetto (100 elementi, 2 GiB), oltre rifiuto motivato.
- **E-X7**: selettore aperto con il blocco dell'app attivo: prima lo sblocco; i nascosti non compaiono mai.
- **E-X8**: condivisione in arrivo verso il NAS mentre il NAS non è raggiungibile: copia locale in coda (con i tetti di D24), caricata appena possibile, con lo stato.
- **E-L10**: copia simile (ricompressa, EXIF riscritto, HEIC convertito) di una foto del telefono già sul NAS: l'originale si carica, le due copie stanno in una cella con la migliore come copertina (D36).
- **E-L11**: copia simile di una foto nascosta: il gruppo è nascosto per intero; la foto non ricompare dalla copia (D36, E-H1).
- **E-L12**: fotogrammi di una raffica o scatti diversi nello stesso secondo: mai uniti come copie simili; un media che somiglia a due altri non entra in nessun gruppo.
- **E-L13**: "Separa" su un dispositivo: le copie restano separate su tutti i dispositivi dell'utente e il gruppo non si riforma.
- **E-B23**: vecchi backup fatti con altri strumenti nell'area privata: le copie identiche si collegano senza caricare, quelle simili si caricano e si uniscono, il riordino arriva solo su proposta e con conferma (D37).

### 0.3 Modifiche da fare in P0 e P0-B (agli altri documenti)
**`docs/formato-nas.md`** (nessun file firmato cambia: il backup scrive media, non dati del formato)
- **N1, §2.2 albero**: i temporanei del backup `tmp/<io>-bk-<id>`; fuori da `.mycloudgallery/`, le cartelle che l'app crea nelle aree degli utenti: `Backup/<dispositivo>/…`, `Backup/Area comune/…`, `Backup/<dispositivo>/Modificate/…` e `Versioni/…` (file normali con la loro estensione, non firmati, visibili dal PC).
- **N2, §2.3 pulizia dei propri file**: un temporaneo proprio si toglie solo se nessuno dei due diari (operazioni e backup) lo cita (D33).
- **N3, §9 nuovo punto 8**: `core-backup` e `NasBackupOps`: cosa possono fare (creare, pubblicare senza sostituire, copiare lato server in un temporaneo, togliere i propri temporanei; nella 5B rinominare verso e da `Versioni/` con il timbro) e cosa no (nessuna di queste azioni toglie byte); ingressi: catalogo locale, impostazioni, conferme della UI; test di architettura.
- **N4, §5.5-bis**: il record `meta` di una versione caricata (`vkind` `edited`/`corrected`, `parent`) e quelli dei file in `Versioni/` (file normali della share, pubblicati come gli altri).
- **N5, §2.1**: `Backup/` e `Versioni/` sono cartelle normali: se la share è nelle librerie di Twonky o Plex le vedono (prova F45, L43).

**`docs/sicurezza.md`**: C10 (un secondo modulo che scrive i media, in sola aggiunta: D2), C12 (D2–D5, D12, D14), C13 (avviso forte di D21), C17 (componenti esportati della 5B, D29), C18 (D24, D26); §4-bis (`BackupJobService` nella 5A; i componenti della 5B come **proposta**); §6 L37–L44 (§6.2 di questo documento); §7 checklist della Fase 5 estesa (§6.3); §8 righe di D2, D4, D9, D12, D13, D17, D21, D29.

**`docs/modello-dati.md`**: nuovo §12 "Schema v4" (lo scrive P1), con il gruppo `SIMILAR_COPIES` e la firma visiva locale (D36).

**`docs/decisioni.md`**: sezione "Fase 5" con D1–D37 e le risposte del §9.

**`docs/regole-attivita.md`**: §6 costanti nuove (`verifyReadBytesPerSecond`, `perFileOverheadSeconds`, `uploadStreams`), il traffico del backup che conta anche la rilettura, D34 se approvata.

**`docs/fase4-progetto.md`**: nota al §2.11 e al §1.4: `VerifyWorker` ed esecutore di `FULL_HASH` non realizzati, passano alla Fase 5 (D14, P3).

**`docs/prova-500.md`**: sezione del backup (P6) e della 5B (P13).

**`docs/android.md`**: esiti di T-11 (con M1) e di T-09, T-14, T-15, T-16 (P0-B).

**Piano**: edge case del §0.2 (compresi E-L10…E-L13 ed E-B23); proposta 5 aggiornata da D36 (le copie dello stesso scatto si uniscono in Libreria, Doppioni resta per gli scatti simili ma diversi); punto aperto "Dove conservare sul NAS le versioni precedenti" chiuso con D17 e Q2; "Selettore foto delle altre app" chiuso con D23.

**`README.md`**: moduli nuovi e grafo. **`docs/grafica-progetto.md`**, "Dubbi grafici": terza voce del piede (Backup), pagina Backup, foglio "Libera spazio" con l'avviso forte, segno `BACKUP_PROBLEM` (per G3).

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-nas        + ops/NasBackupOps (sola aggiunta, @AppendOnlyNasAccess)      (5A, P2; 5B, P8: archiveVersion/restoreVersion)
core-backup     → model, common, trust (SharePath, NasLayout), nas (reader + NasBackupOps), database (porte), index (publisher),
                  prepare, metadata, rules
                  NON vede: core-nasops, NasFileOps, DestructiveNasAccess, core-mediastore (i byte del telefono arrivano da una porta)
core-metadata   + write/ (MetadataWriter), strip/ (LocationStripper)             (5B)
core-mediastore + richieste di cancellazione e di scrittura, riscrittura con il permesso (5B)
core-nasops     + porta CitedTemps per OwnFilesCleaner (5A, P0)
feature-backup  → backup (contratti), library, imaging, designsystem            (cieca a core-nas e core-nasops)
feature-integrations → backup, library, imaging, mediastore (contratti), designsystem   (5B; cieca a core-nas e core-nasops)
app → tutti
```
- `NasBackupOps` e il marcatore `AppendOnlyNasAccess` stanno solo in `core-nas` e `core-backup` (compilatore e `Phase4ArchitectureTest` esteso); `core-backup` non nomina `NasFileOps`, `DestructiveNasAccess`, `ConfirmationIssuer` né tipi di `core-nasops`.
- Chi legge il NAS o mostra la Libreria (`core-index`, `core-nasmedia`, `core-prepare`, `core-personal`, `core-library`, `core-imaging`) **non vede** `core-backup`: nessun dato letto dal NAS diventa un'azione del backup se non passando dal catalogo locale o dalla UI.
- `PhoneConfirmation` (D21) la costruiscono solo `feature-backup`, `feature-integrations` e i fogli del viewer, dopo un tocco (stessa regola di `UserConfirmation`).
- I `feature-*` non vedono `core-nas`: il provider delle integrazioni legge i media del NAS da una porta che `:app` lega a `NasMediaReader`.

### 1.2 Responsabilità e API (firme indicative; P0 e P0-B scrivono contratti, null-object e fake)

**`core-nas` (P2 nella 5A, P8 nella 5B): primitive in sola aggiunta** (`ops/backup/`)
| Elemento | Firma | Note |
|---|---|---|
| Marcatore | `@RequiresOptIn(level = ERROR) annotation class AppendOnlyNasAccess` | l'argomento del compilatore lo passano solo `core-nas` e `core-backup` |
| Vista | `@AppendOnlyNasAccess interface NasBackupOps { ownerDeviceId; mkdirs; openTemp; reopenTemp; copyToTemp; publishTemp; deleteOwnTemp; space }` | stesse firme ed esiti di `NasFileOps` (4B); la implementa `SmbNasFileOps`; la consegna `NasBackupOpsFactory` solo a `core-backup`; `NasSessionManager` non la espone |
| Ripresa | `suspend fun reopenTemp(share, temp: SharePath, keepBytes: Long, purpose): NasResult<NasTempWriter>` | solo un temporaneo proprio con il prefisso `<io>-bk-`; apre **senza** creare, accorcia a `keepBytes` (`SET_INFO` di fine file sullo stesso handle), lo scrittore riparte da lì; `keepBytes` oltre la lunghezza = errore, il chiamante ricomincia da zero |
| Versioni (5B) | `archiveVersion(share, from, to, expect: NasStamp, purpose): NasResult<RenameOutcome>`; `restoreVersion(share, from, to, expect, purpose)` | rinomina senza sostituire nella stessa share; `to` (archiviare) o `from` (ripristinare) sotto `Versioni/`; timbro **obbligatorio**, controllato sullo stesso handle prima del `SET_INFO` |
| Fake | `InMemoryNasMedia.backupOps(dispositivo)` con `opsFaults` (prima e dopo l'applicazione), spazio, quota, sola lettura, accorciamento | e `InMemorySmbBackend` con `SET_INFO` di fine file, per il banco sul simulatore |

**`core-backup` (contratti in P0 e P0-B; 5A: P2, P3, P4; 5B: P8, P9)**
| Elemento | Firma | Note |
|---|---|---|
| Motore | `interface BackupEngine { val status: StateFlow<BackupStatus>; suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult; suspend fun resumeAll(budget: Duration): BackupRunResult; suspend fun stop(by: StopBy) }` | `BackupKind { PHOTOS, VIDEOS }`; `BackupTrigger { SCHEDULED, FIRST_BACKUP, USER_NOW, QUICK_NEW_PHOTOS, INCOMING }`; nessuna eccezione per un errore del NAS o del telefono |
| Diario | `interface BackupStore { suspend fun create(candidates): List<BackupItem>; suspend fun item(id); suspend fun itemsToResume(limit); suspend fun update(item); suspend fun openFor(versionId): BackupItem?; suspend fun citedTemps(): Set<SharePath> }` | Room v4 (`RoomBackupStore`, P2); rifiuta le transizioni fuori da `UploadStateMachine`; fake in memoria che fa lo stesso |
| Macchina | `object UploadStateMachine { fun flowOf(kind: UploadKind): UploadFlow; fun next(flow, state); fun canTransition(flow, from, to) }` | come dati, come `OpStateMachine`; `UploadKind { NEW_COPY, GUARD_COPY, VERSION, SWAP, INCOMING }`; `VERSION`, `SWAP` e `INCOMING` arrivano con la 5B |
| Piano | `interface BackupPlanner { suspend fun summary(): BackupSummary; suspend fun next(kind, limit): List<BackupCandidate>; fun destination(candidate, folder: DeviceFolder): SharePath }` | D6, D7, D11; `BackupCandidate(mediaId, versionId, phoneRef, stamp, size, kind, source, localDay)` |
| Impostazioni | `interface BackupSettings { val sources: StateFlow<BackupSources>; val deviceFolder: StateFlow<DeviceFolder?>; suspend fun setSources(..); suspend fun chooseFolder(..) }` | del dispositivo, non personali (come preset e qualità); con D13 le sorgenti recintate |
| Verifiche | `interface SafetyVerifier { suspend fun verify(scope: VerifyScope, budget: Duration): VerifyReport }` | `VerifyScope { Unverified, FullHash, Rotation, OnTheSpot(ids) }` (D14) |
| Guardiano | `interface PrivateCopyGuardian { suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport }` | D9; `GuardScope { All, Media(ids) }` |
| Porte | `PhoneBytes` (byte originali di una riga, foto con `setRequireOriginal`), `BackupCatalog` (candidati, `attachCopy`, `markVerified`, problemi, conteggi), `BackupGate` (`TEST_ONLY` D12, `FROZEN`, `WRITES_STOPPED`, `READ_ONLY_AREA`, `NO_PRIVATE_AREA`, `NOT_READY`), `BackupClock` (monotono) | legate da `:app` (P7) |
| Stato | `sealed interface BackupStatus`, `MediaBackupState`, `BackupProblem` (§1.5, §2.6) | solo numeri e codici |
| Versioni (5B) | `interface VersionKeeper { suspend fun plan(mediaId): NasVersionPlan; suspend fun restoreOriginal(mediaId, confirmation: PhoneConfirmation) }` | D17, D18, D35: i passi sono elementi del diario (`VERSION`, `SWAP`) |
| Correzioni (5B) | `interface Corrections { suspend fun plan(mediaId, change: CorrectionRequest): CorrectionPlan; suspend fun apply(plan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?) }` | D19 |
| Libera spazio (5B) | `interface FreeSpace { suspend fun plan(filter: FreeSpaceFilter): FreeSpacePlan; suspend fun verify(plan, confirmation: PhoneConfirmation, budget): FreeSpaceRun; suspend fun nextBatch(run): TrashBatch?; suspend fun recordOutcome(batch, outcome) }` | D21 |
| Conferma (5B) | `class PhoneConfirmation internal constructor(planHash, issuedAtMonotonicMs, steps)`; `PhoneConfirmationIssuer`, `PhoneConfirmationSheet` (600 ms fra due gesti, R6 della 4B) | solo dalla UI (§1.1) |
| Attività in sospeso (5B) | `interface PendingTasks { val tasks: StateFlow<List<PendingTask>>; suspend fun dismiss(id) }` | D20 |
| Arrivi (5B) | `interface IncomingUploads { suspend fun stage(streams: List<IncomingStream>): IncomingStage; suspend fun save(stage, dest: NasDestination, confirmation: PhoneConfirmation) }` | D24 |
| Sessioni (5B) | `interface EditSessions { suspend fun begin(mediaId, target: EditTarget): EditSession; suspend fun settle(session): EditOutcome }` | D25 |

**`core-metadata` (5B: P8 scrittura, P11 posizione)**
| Elemento | Firma | Note |
|---|---|---|
| Scrittura | `interface MetadataWriter { fun apply(src: File, dst: File, change: MetadataChange): WriteOutcome }` | JPEG, PNG, WebP con `ExifInterface`; `MetadataChange(takenAt?, offsetMinutes?, geo?, orientation?)`; il risultato si rilegge con l'estrattore e si decodifica prima di valere |
| Posizione | `interface LocationStripper { fun strip(src: File, dst: File, mime: String): StripOutcome }` | D26; `StripOutcome { Stripped, Unsupported(reason), StillHasLocation }` |

**`core-mediastore` (5B, P0-B)**
| Elemento | Firma | Note |
|---|---|---|
| Richieste | `PhoneMediaIntents.deleteRequest(refs): IntentSender?`, `writeRequest(refs): IntentSender?` | `createDeleteRequest`, `createWriteRequest`; al massimo 2.000 URI (come il cestino) |
| Riscrittura | `interface PhoneMediaRewriter { suspend fun replace(ref, file: File, expectedSha256: Sha256): RewriteResult }` | solo dopo il permesso di `writeRequest`; scrive, rilegge, confronta; con D13 solo `mcg-prova` |

**`core-rules` (P4)**
| Elemento | Firma | Note |
|---|---|---|
| Misure | `DeviceMeasures` + `verifyReadBytesPerSecond = 16e6`, `perFileOverheadSeconds = 0.12`, `uploadStreams = 1` | §2.9; si ricalibrano a M2 e al primo backup vero |
| Arretrato | `BacklogFacts.toProtect` (esiste) + `toVerify` | il traffico del backup conta caricamento **e** rilettura |
| Regola | `RuleSettings.protectNewPhotos` (D34) | `PHOTO_BACKUP` con `QUICK_NEW_PHOTOS`: Wi-Fi di casa, batteria sopra la soglia, anche fuori fascia |

**`core-library` (P5)**: `LibraryBackupStatus` (sta in `core-library` come `LibraryNasStatus`, scelta di P0 della 4A) e `LibraryTabState.backup`.

### 1.3 Aggiunte a moduli esistenti
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | `CellFlags.BACKUP_PROBLEM` (bit 14), `PresenceBadge.PROBLEM`; nessuna fase di lavoro nuova (i candidati si leggono dallo stato "al sicuro", non da una coda) | P0 (firme), P1 |
| `core-database` | schema v4 (D27); `MediaCatalog`: `backupCandidates(sources, kind, after, limit)` per `sort_time` senza ordinamenti in memoria, `safetyCounts()`, `verifyCandidates(scope, limit)` (non verificate, `FULL_HASH`, rotazione per `verified_at`), `markBackupProblem`, `versionLocations(mediaId)`; per la 5B `relocate` delle versioni archiviate e le tabelle di sessioni, attività, "Libera spazio", arrivi | P1 |
| `core-nasops` | porta `CitedTemps` in `OwnFilesCleaner` (D33); nessun'altra modifica: "Ovunque" con le versioni (D22) è un elenco di elementi più lungo, costruito da `:app` | P0 |
| `core-mediastore` | `PhoneMediaIntents.deleteRequest`/`writeRequest`, `PhoneMediaRewriter`; `FakeMediaStore` con righe che cambiano, spariscono o cambiano `_ID` durante una lettura (banco) | P0 (fake), P0-B |
| `core-prepare` | nessuna logica nuova: il backup usa `Preparer` con `PrepareSource.Bytes`; `Mp4Scout` esteso a trovare le scatole della posizione (5B) | P11 |
| `core-common` | `FeatureFlags.backupTestSharesOnly` (D12), `DiagSection.BACKUP`, test di architettura estesi | P0 |
| `feature-viewer` | riga "Backup" nel pannello info (5A); menu ⋮: Versioni, Vedi originale, Ripristina originale, Correggi data e luogo, Ruota, Modifica con…, Condividi (con la posizione secondo l'impostazione) (5B) | P5, P12 |
| `feature-settings` | voce "Backup" (apre `feature-backup`); "Condivisioni: rimuovi la posizione" (5B) | P5, P12 |
| `feature-diagnostics` | codici e chiavi d'esportazione del backup (§2.6) | P7, P14 |
| `core-designsystem` | solo il glifo del segno `PROBLEM` registrato nel catalogo (§12.4 della grafica: disco intero) | P5 |

### 1.4 Cosa resta in `:app` (P7 per la 5A, P14 per la 5B)
- **`BackupGraph`** in `app/.../backup/`: nasce con la famiglia `Ready` e il `NasGraph`; area privata da `LocalDeviceProfile.privateShare` (assente = `NO_PRIVATE_AREA`); un `BackupEngine`, un `SafetyVerifier`, un `PrivateCopyGuardian`; porte legate al catalogo, a `MediaStoreSource` e a `NasBackupOpsFactory`.
- **Lavori** (`work/BackupWork.kt`, tutti con `runIfActive` e `StagedRun`): `PhotoBackupWorker` (`PHOTO_BACKUP`), `VideoBackupWorker` (`VIDEO_BACKUP`), `SafetyVerifyWorker` (verifiche, `FULL_HASH`, rotazione, con l'attività del tipo di media), `QuickPhotoWorker` (D34, se approvata); `BackupJobService` (UIDT: primo backup, "Fai ora", nella 5B la verifica di "Libera spazio" e i caricamenti in arrivo grandi), con notifica di avanzamento e "Ferma" verso un ricevitore registrato a runtime e non esportato (come la 4B).
- **Trigger**: a fine giro `PhoneSyncWorker` (Fase 3) chiede `QuickPhotoWorker` o `PhotoBackupWorker` (lavoro unico, `KEEP`) se ci sono media nuovi in una sorgente; nessun polling.
- **Cancello** `BackupGate`: D12 + congelamento (F4-D10) + emergenza + area in sola lettura (E-I19) + famiglia pronta.
- **Pulizia dei propri file**: `CitedTemps` = temporanei del diario delle operazioni ∪ quelli del diario del backup (D33).
- **Notifiche e Diagnostica**: avanzamento del primo backup e di "Fai ora"; riepilogo giornaliero esteso (`NasDailySummary`: backup fermo da 3 giorni, quota, NAS pieno, perse prima del backup, copie non integre); `BackupDiagnostics` con i codici del §2.6.
- **Azzeramento**: `BackupGraph.stop()` per primo, poi `AppLocalDataEraser` (diario nel database, `noBackupFilesDir/incoming/`, `cacheDir/share/`, `cacheDir/edit/`). I temporanei già sul NAS portano l'id del dispositivo azzerato: nessuna installazione nuova li considera propri, quindi **restano** finché non si tolgono dal PC (come il cestino, L31); la guida lo dice.
- **5B**: componenti del manifest (D29), il blocco dell'app (`AppLockGate`) esteso alle activity d'ingresso delle integrazioni, navigazione delle pagine nuove, voce "Libera spazio" in Raccolte → Utility, "Ovunque" con le copie di tutte le versioni (gli elementi li elenca `core-backup`, D22; `:app` li passa al piano `Trash` di `core-nasops` come oggi).

### 1.5 Confine con il filone grafico: stati e contratti
La Fase 5 decide **cosa** si mostra e **quando**; il filone grafico decide **come** (F4-D27, D30). Le composable della Fase 5 usano solo i componenti G2 esistenti.

| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `BackupStatus` | `NotConfigured(missing)`, `TestOnly` (D12), `FirstBackupReady(summary)`, `UpToDate(safeCount, at)`, `Waiting(items, bytes, reason)`, `Running(kind, done, total, bytesDone, bytesTotal, eta, trigger)`, `Stopped(by)`, `Problem(code, count, action)` | pagina Backup, Impostazioni | G2 |
| `LibraryBackupStatus` | riduzione per il piede: frase breve e dettaglio ("Tutto al sicuro" · "Oggi alle 03:12"; "312 in coda" · "Attesa carica"; "Quota esaurita" · "Chiedi all'admin del NAS"; "Backup in prova") | piede della Libreria, terza voce (`GalleryFooterStatus`) | G2 (§12.5 della grafica) |
| `BackupSummary` | foto e video: numero e GB, per sorgente; stima con le regole attuali (tempo, notti, traffico Wi-Fi e dati mobili, batteria); avvisi; scelte "Stanotte" (predefinita) e "Inizia ora" | foglio del primo backup | `GalleryConfirmSheet` |
| `SourceStatus` | per sorgente: attiva, media, al sicuro, in coda, problemi con l'azione | pagina Backup | G2 |
| `MediaBackupState` | `NotMine`, `NotOnNas(reason)`, `Queued(reason)`, `Uploading(progress)`, `Verifying(progress)`, `Safe(verifiedAt)`, `OnlyCommonCopy`, `Unverified`, `Problem(code)`, `LostBeforeBackup` | pannello info del viewer | G3 |
| `PresenceBadge` | i cinque della Fase 4 + `PROBLEM` (da `BACKUP_PROBLEM`) | cella della griglia | G2 (§12.4) |
| `VersionsState` (5B) | versioni (originale, intermedie, attuale) con data, dimensione e dove stanno; azioni "Vedi", "Ripristina originale", "Elimina le intermedie" | viewer ⋮ → Versioni | G3 |
| `FreeSpaceState` (5B) | `Idle`, `Summary(items, bytes, excluded by reason, filter)`, `Guarding(done, total)`, `Verifying(done, total, bytes)`, `Ready(batches, items, bytes)`, `Confirming(batch, of)`, `Done(freedBytes, items, toAndroidTrash)`, `Blocked(reason)` | Raccolte → Utility → Libera spazio; selezione della Libreria | G2 |
| `PendingTasksState` (5B) | voci (tipo, media, byte, da quando) con "Elimina l'originale" e "Tienilo" | pagina Backup → "Da decidere" | G2 |
| `CorrectionState` (5B) | `Editing(fields)`, `NeedsSafeFirst`, `AwaitingSystemDialog`, `Writing`, `Uploading`, `Done`, `Failed(code)` | viewer ⋮ → Correggi | G3 |
| `PickerState`, `IncomingState`, `EditSessionState`, `ShareOutState` (5B) | selezione e scaricamenti con avanzamento; copia, tipo e tetti dei file in arrivo con la cartella scelta; sessione aperta e esito; copia senza posizione verificata o rifiutata | activity delle integrazioni, viewer | G2 |

**Chi tocca cosa**
- La Fase 5 **non** modifica `core-designsystem` (salvo il glifo `PROBLEM` nel catalogo), la chrome, il vetro, la pillola, la cella; il filone grafico non modifica i contratti e i ViewModel qui sopra.
- File condivisi a rischio: `feature-library/.../library/LibraryStatusFooter.kt` (P5 aggiunge la terza voce con i componenti esistenti), il menu del viewer (`viewerMenuEntries`, solo P12 nella 5B), `CollectionsTab.extraGroups` (la voce "Libera spazio" arriva da `:app` come dato).
- Stringhe in file propri: `strings_backup_*.xml`, `strings_integrations_*.xml`.

## 2. Flussi

### 2.1 Configurazione e piano del backup (5.1, P4)
| Momento | Cosa | Dettagli |
|---|---|---|
| Sorgenti | fotocamera, screenshot, cartelle aggiuntive | il passo 6 dell'onboarding salva già fotocamera e screenshot; Impostazioni → Backup elenca le altre cartelle del MediaStore con numero e GB (app di messaggistica in `Android/media/…`, Quick Share, altre cartelle di `DCIM` e di `Pictures`); la sorgente di un media viene da `MediaSource` e dal percorso relativo; la microSD ha il suo volume (E-P6) |
| Area e cartella | area privata, cartella del dispositivo | `LocalDeviceProfile.privateShare`; la cartella di D7 si sceglie al primo backup ("Backup/S25 di Mario" c'è già con 1.234 file: "Continua lì" o "Nuova cartella") |
| Candidati | dal catalogo | media **propri** (`owner` = account del dispositivo) con una copia presente sul telefono in una sorgente scelta e stato diverso da `SAFE`; per ogni versione da proteggere (originale e attuale, `SafetyRules.protectedVersions`) senza copia privata verificata; ogni file di una raffica o di una coppia RAW+JPEG è un media a sé; mai una riga `IS_PENDING` |
| Ordine | | prima le foto, poi i video; dentro, `sort_time` decrescente; l'originale prima della sua versione (E-E5) |
| Primo backup (E-B11) | riepilogo | numero e GB **veri** (dimensioni del MediaStore), per foto e video e per sorgente; stima con `ConsumptionEstimator` sulle regole attuali (§2.9); "Stanotte" (predefinita: segue il preset) o "Inizia ora" (lavoro avviato dall'utente, con l'avviso "meglio in carica e sul Wi-Fi di casa"); sui dati mobili chiede conferma con la stima |
| Pausa e ripresa | pagina Backup e notifica | "Ferma" ferma prima del file successivo e lascia ogni temporaneo con il suo punto di ripresa; "Riprendi"; "Fai ora" (proposta 10) |

| Edge case | Comportamento |
|---|---|
| E-B6 | Due dispositivi dello stesso account: cartelle separate (D7); un nome già presente fa chiedere |
| E-B7 | Dispositivo rinominato: la cartella resta quella scelta |
| E-B11 | Migliaia di media: riepilogo con priorità (foto, poi video, dal più recente) e stima per preset |
| E-B12 | Sorgente tolta dalle impostazioni: il backup fatto resta, i media restano al sicuro; quelli nuovi della sorgente non si caricano più |
| E-B16 | Cartella esistente (reinstallazione, telefono nuovo): "Continua lì"; la deduplica collega senza ricaricare |
| E-B17 | Data assente o assurda: `Senza data/` |
| E-B20 | Debug con gli account di prova: solo `mcg-prova` (D13) |
| E-P1 | Accesso solo a foto selezionate: si salvano solo quelle; nessuna deduzione dall'assenza; la pagina lo dice |
| E-P7, E-P17 | Secure Folder, Spazio privato, app clonate: non visibili, dichiarato (L15) |
| E-P21 | Senza `ACCESS_MEDIA_LOCATION` le foto restano in attesa (mai un'impronta oscurata): stato "Foto in attesa: consenti l'accesso alla posizione delle foto" |

### 2.2 Caricamento sicuro (5.2, P2)
**La macchina di un elemento** (`backup_item`; ogni riga "si scrive prima" va nel diario **prima** della chiamata)
| Passo | Si scrive prima | Cosa si fa | Ripresa dopo un'interruzione |
|---|---|---|---|
| `PLANNED` | elemento: media, versione, riga del telefono, timbro atteso, destinazione prevista | — | si riparte da `HASHED` |
| `HASHED` | — | lettura completa dal telefono (`PhoneBytes`; foto con `setRequireOriginal`): SHA-256, `qh1`, preparazione dagli stessi byte (D10); timbro uguale prima e dopo la lettura | timbro cambiato → si rilegge (E-B15); riga sparita → `GONE` (E-B4) |
| (deduplica) | — | D8: candidati nell'area privata propria con la stessa dimensione e `qh1`, rilettura completa | uguale → `LINKED_EXISTING` |
| `UPLOADING` | temporaneo `<io>-bk-<id>` e punto di ripresa 0 | `openTemp` (o `reopenTemp(keep = punto)`), scritture da 1 MiB; ogni 64 MiB `flush`, lunghezza sullo stesso handle, punto di ripresa nel diario | temporaneo lungo almeno il punto → `reopenTemp`; assente → da 0; `Exists` senza punto → `deleteOwnTemp` e da 0 |
| `UPLOADED` | — | `finish(size)`: lunghezza giusta sullo stesso handle | ripetibile |
| `VERIFIED` | SHA-256 del temporaneo | `readAll` a 4 flussi del temporaneo: uguale all'hash del telefono | diverso → `deleteOwnTemp`, tentativo + 1, di nuovo `UPLOADING` da 0; al terzo `FAILED` con `VERIFY_FAILED` (E-B18) |
| `PUBLISHING` | nome finale (D6) | `publishTemp(temp, finale)` | `TempMissing` + finale con dimensione e SHA-256 giusti = pubblicato (risposta persa); `Collision` → si rilegge il file che c'è: identico → `LINKED_EXISTING` e temporaneo tolto; diverso → "nome (n)" nel diario e di nuovo |
| `PUBLISHED` | timbro del finale | `stat` del finale: dimensione, data e `fileId` uguali al temporaneo verificato | timbro diverso (toccato fra la rinomina e il controllo) → verifica completa del finale prima di andare avanti |
| `LINKED` | — | `attachCopy(verifiedAt)` nel catalogo (il media diventa `SAFE`) e record più immagini all'indice (`IndexPublisher.stage`, D10) | idempotente: `attachCopy` sulla stessa chiave aggiorna |
| `DONE` | | | |

**Regole**
- Si scrive **solo** in `Backup/…` dell'area privata (per i caricamenti in arrivo nella cartella scelta, nella 5B anche in `Versioni/` e al solito percorso di una versione) e nei propri temporanei `.mycloudgallery/tmp/<io>-bk-…`. Nessuna primitiva toglie o sostituisce un file (D2).
- Niente di visibile prima della verifica: il temporaneo sta in una cartella col punto, senza estensione (D33); la scansione non lo vede (E-N16).
- **Spazio** (E-R9, E-N13): prima di un file oltre 1 GB `space()` deve dare almeno dimensione + 1 GiB (tocca i dischi: solo lì); `DISK_FULL` a metà toglie il temporaneo (come nella 4B) e ferma il giro con `NAS_FULL`; quota esaurita con il NAS non pieno = `QUOTA`.
- **Rete**: NAS irraggiungibile ferma il giro, non l'elemento (si riprende al giro dopo, senza contare il tentativo); trasferimento fermo senza errori = sorvegliante a blocchi (F4-D3, E-R14); errore di autenticazione = interruttore (E-R8), il giro si ferma con "Serve la password".
- **Telefono**: i video si leggono in streaming, mai interi in memoria; foto oltre 64 MiB da un temporaneo locale (`PrepBudget`); un file illeggibile o rovinato si **salva comunque** (i byte sono dell'utente, la preparazione mette il segnaposto, E-M10); un file a zero byte o un falso media no (E-M12).
- **Traffico**: caricamento e rilettura si registrano subito nel registro del traffico con la rete usata (`StagedRun`), prima del punto di ripresa.

| Edge case | Comportamento |
|---|---|
| E-B1 | Wi-Fi che cade a metà di un video da 3 GB: ripresa dall'ultimo punto (al massimo 64 MiB ricaricati) |
| E-B2 | App o telefono spenti durante il caricamento: ripresa dal diario; dal PC nessun file a metà (solo il temporaneo nascosto) |
| E-B3 | Nome già usato da un altro file: "nome (2)", mai sostituito; da un file identico: collegato |
| E-B4 | Foto cancellata prima del backup: `GONE`, stato "persa prima del backup" con "Recuperala dal cestino di Galleria"; se torna, si ricollega senza ricaricare (E-P11) |
| E-B5 | Copia privata identica già presente: collegata dopo la rilettura (D8) |
| E-B10 | Tetto dati raggiunto: il giro si ferma con l'avviso; "Fai ora" con conferma e stima |
| E-B15 | File cambiato durante il caricamento: si riparte con i byte nuovi (L38, D34) |
| E-B18 | Verifica fallita tre volte: `VERIFY_FAILED`, segno `PROBLEM`, mai "al sicuro" |
| E-B19 | Congelamento o emergenza: si ferma al passo successivo, nessun file a metà |
| E-B21 | Temporaneo fermo da giorni: resta (D33) |
| E-N13 | Destinazione aperta dal PC (`SHARING_VIOLATION` sulla pubblicazione): attese crescenti come nella 4B |
| E-P4, E-P8, E-P13 | Riavvio, aggiornamento dell'app, riavvio notturno: ripresa dal diario al primo lavoro dopo lo sblocco |
| E-P12 | `_ID` del MediaStore cambiato a metà: l'elemento si ritrova per impronta; la riga vecchia non vale più (`getVersion`), si rilegge |
| E-R2, E-R14 | Rete che cade o si blocca: come E-B1 |

### 2.3 "Al sicuro", deduplica e guardiano (5.3, P3)
| Lavoro | Ingressi | Cosa fa | Quando |
|---|---|---|---|
| Verifica delle copie non verificate (D14) | catalogo: posizioni presenti nell'area privata propria con `verified_at` nullo e hash della versione noto | `readAll` a 4 flussi, `markVerified` | dopo una scansione con novità; `PHOTO_BACKUP`/`VIDEO_BACKUP` secondo il media |
| `FULL_HASH` (F4-D21) | coda del catalogo (gruppi `PROBABLE_DUPLICATE`) | rilettura completa della copia del NAS; l'hash entra nel catalogo come impronta calcolata qui, che risolve il gruppo | stessa attività, le foto prima |
| Deduplica (D8) | un candidato del backup | posizioni dell'area privata propria con la stessa dimensione e `qh1` letto qui; rilettura; uguale = collegamento | dentro il caricamento, prima di `UPLOADING` |
| Guardiano (D9) | catalogo: media propri in `ONLY_COMMON_COPY` | copia lato server dall'area comune a un temporaneo privato, verifica contro l'hash completo della versione (se manca: contro quello letto qui dalla sorgente, solo se timbro e `qh1` sono quelli della versione), pubblicazione in `Backup/Area comune/AAAA/MM/`, `attachCopy` verificata | sempre prima di "Libera spazio" e di "Elimina solo dal telefono" per quei media; alla scansione del telefono che vede sparire la copia (E-B13); non dopo uno spostamento nell'area comune (risposta Q4 (b)); 20 GB per giro **Fase 7 (D28):** anche subito dopo uno spostamento in un album condiviso per i media propri che il telefono non ha piu'. |
| Riverifica a rotazione (Q10) | catalogo: posizioni private per `verified_at` crescente | rilettura; diversa → `markVerified` falso; se il telefono ha ancora il media, il backup lo ricarica; altrimenti segno `PROBLEM` e avviso nel riepilogo (E-B8) | ogni notte, circa 1/365 dei byte protetti (almeno 100 MB) |
| Verifica sul momento (5B) | il piano di "Libera spazio" | rilettura di ogni copia privata scelta (D21) | lavoro avviato dall'utente |

**"Mai leggere o spostare file privati altrui"**: i candidati della deduplica e del guardiano escono solo dalle posizioni dell'area privata **dichiarata per l'account del dispositivo** (`LocalDeviceProfile`) e dell'area comune; la sessione SMB è quella dell'account, quindi le altre aree private non si aprono comunque. Nessun dato dell'indice decide (F4-D8): servono impronte calcolate qui.

**Il "Fatto quando" di 5.3, scenario per scenario**
| Scenario | Cosa tiene una copia sotto il controllo del proprietario |
|---|---|
| Condivisione: spostamento nell'area comune (Cartelle NAS della 4B; album della Fase 7) | Risposta Q4 (b): la sorgente resta 30 giorni nel cestino del NAS e il guardiano parte solo quando sparisce la copia del telefono |
| Cancellazione da Samsung Gallery (la copia del telefono sparisce, E-P11) | già al sicuro: niente da fare; solo copia comune: guardiano alla prima lettura del telefono che vede l'assenza; nessuna copia sul NAS: E-B4 |
| "Libera spazio" | D21: guardiano, verifica sul momento e controllo del timbro **prima** del dialogo di sistema |
| "Elimina solo dal telefono" | D22: guardiano o "Prima mettilo al sicuro" |
| Un familiare cancella la copia comune dal PC | resta la copia privata (con Q4 (b) solo se la copia del telefono c'era ancora: limite dichiarato) |
| Reinstallazione o telefono nuovo | la deduplica ritrova le copie private e le collega |

| Edge case | Comportamento |
|---|---|
| E-B5 | Copia privata identica: collegata dopo la rilettura |
| E-B8 | Copia non più integra (rotazione, verifica sul momento): esce da "al sicuro", backup rifatto se il telefono ce l'ha |
| E-B13 | Media condiviso e poi cancellato dal telefono: guardiano finché la copia comune esiste |
| E-B14 | "Libera spazio" con la sola copia comune: prima la copia privata, poi si libera |
| E-L3 | Identici in più cartelle: un media con più posizioni; le copie in eccesso non si toccano |
| E-S11 | Un dato remoto (indice, cestino, cartella) non avvia mai una copia o una verifica che tolga qualcosa: le azioni del §2.3 creano o leggono |

### 2.4 Versioni e correzioni (5.4, 5B, P8)
**Sul NAS** (elementi del diario `VERSION` e `SWAP`; le rinomine verso e da `Versioni/` con il timbro, come la 4B)
| Cammino | Passi | Ripresa |
|---|---|---|
| `VERSION` (versione nuova) | `PLANNED → HASHED → UPLOADING → UPLOADED → VERIFIED → ARCHIVING → ARCHIVED → PUBLISHING → PUBLISHED → LINKED → DONE` | `ARCHIVING`: il nome in `Versioni/` si scrive prima; sorgente assente + destinazione con lo stesso `fileId` e dimensione = fatto; timbro dell'attuale cambiato (modificato dal PC) → stop `SOURCE_CHANGED`, niente archiviato, la versione nuova resta nel temporaneo finché la scansione non registra quella del PC e il piano si ricalcola. Fra `ARCHIVED` e `PUBLISHED` il solito percorso è vuoto per un istante: la ripresa pubblica; la scansione riconosce l'archiviazione per `fileId` (E-N1) |
| `VERSION` senza attuale da spostare | come sopra senza `ARCHIVING` | il solito percorso è libero: si pubblica lì |
| Versione **non** attuale da salvare (E-E6: il PC ha sovrascritto, l'originale c'è solo sul telefono) | `PLANNED → … → VERIFIED → PUBLISHING` direttamente in `Versioni/` | come il caricamento |
| `SWAP` ("Ripristina originale", E-E2, E-E10) | `PLANNED → ARCHIVING (attuale in Versioni/) → ARCHIVED → RESTORING (originale al solito percorso) → RESTORED → LINKED → DONE` | come le rinomine della 4B; nessun byte caricato |

**Scenari** (ognuno con il suo test; F34–F36 sul campo)
| Caso | Sul NAS | In Libreria |
|---|---|---|
| E-E1 Più modifiche | ogni versione nuova al solito percorso, l'originale e le intermedie in `Versioni/`; le intermedie si tolgono solo a mano (cestino del NAS) | si vede l'ultima; ⋮ → Versioni le elenca |
| E-E2 "Ripristina originale" in Samsung Gallery | `SWAP` | l'originale torna attuale, nessun caricamento |
| E-E3 Media solo-NAS modificato con un editor esterno | `VERSION` dalla copia in cache (D25) | versione nuova |
| E-E4 Avviso del video ignorato | l'originale resta | attività in sospeso (D20) |
| E-E5 Modifica durante il backup dell'originale | se l'originale era già `DONE`: `VERSION`; se era a metà, la lettura vede il file cambiato (E-B15) e l'originale non arriva al NAS | versione nuova (L38) |
| E-E6 Modifica fatta dal PC | la scansione crea la versione (Fase 4); se il telefono ha ancora l'originale, il backup lo salva in `Versioni/` | versione del PC come attuale |
| E-E7 Modifica della copia in `Pictures/MyCloud Gallery` | `VERSION` nella cartella del file del NAS (non in `Backup/`) | versione del media del NAS, nessun media nuovo |
| E-E8 Formato non scrivibile | Q6 | — |
| E-E9 Area comune o in sola lettura | D35 | — |
| E-E10 "Ripristina originale" dall'app con la copia del telefono modificata | `SWAP`; sul telefono `writeRequest` con l'originale riletto e verificato; negato → resta la versione sul telefono | l'app dice quale copia ha cosa |
| E-L8 Correzione di data o luogo | `VERSION` con `vkind = corrected`; solo META da rifare (`VersionRules.stagesToRedo`) | data e luogo nuovi, stessa identità |
| E-X3 L'editor salva una copia | dentro la sessione: versione dello stesso media; fuori: media nuovo | — |

**Correzioni dall'app** (D19)
| Dove sta il media | Passi |
|---|---|
| Sul telefono | (1) se non è al sicuro: "Prima lo metto al sicuro" (backup e verifica); (2) foglio della correzione con l'anteprima e la conferma; (3) `writeRequest` (dialogo di sistema); (4) copia locale corretta (`MetadataWriter`), riletta e decodificata; (5) `catalog.correct(...)` con l'impronta nuova **prima** che la lettura del telefono veda il cambio (come F4-D20: trova `SameFile`, non una modifica sconosciuta); (6) `PhoneMediaRewriter.replace`, riletto e confrontato; (7) il backup carica la versione (`VERSION`) |
| Solo sul NAS | originale letto in cache con l'hash, copia corretta, verifica, `VERSION` al solito percorso, `catalog.correct` |
| Area comune o sola lettura | D35 |
| Formato non scrivibile | Q6 |

### 2.5 Cancellazioni dal telefono e "Libera spazio" (5.5, 5B, P9)
| Passo | Cosa | Dettagli |
|---|---|---|
| 1. Scelta | Raccolte → Utility → Libera spazio, o "Libera spazio" sulla selezione | riepilogo: numero e GB liberabili, esclusi per motivo (non al sicuro, in caricamento, in errore, area congelata, versioni scoperte); filtri "tutti", "solo video", "più vecchi di un anno"; di default esclusi gli ultimi 30 giorni (Q9); **avviso forte** in evidenza: "Il NAS non ha un backup esterno: dopo, l'unica copia sarà sul NAS" |
| 2. Conferma | `PhoneConfirmation` (un tocco) | legata all'impronta del piano (elementi, timbri, versioni, filtro): un piano ricalcolato vuole un'altra conferma |
| 3. Guardiano | per i media `ONLY_COMMON_COPY` | D9; quelli che non riescono escono dal piano |
| 4. Verifica sul momento | lavoro avviato dall'utente con avanzamento | rilettura completa di ogni copia privata; valida 6 ore di orologio monotono, annullata da un riavvio; se l'app è in secondo piano a fine verifica, una notifica "Pronto: N elementi, X GB. Tocca per liberare" (il dialogo di sistema vuole l'app visibile) |
| 5. Controllo finale | subito prima di ogni dialogo | `stat` di ogni copia del lotto: timbro uguale a quello verificato; diverso o assente → l'elemento esce |
| 6. Dialogo di sistema | lotti di 2.000 | `createTrashRequest` (cestino di Android, 30 giorni) di default; con "Libera subito" seconda conferma con due gesti distinti (R6 della 4B) e `createDeleteRequest` |
| 7. Esito | catalogo e Libreria | ogni elemento confermato da Android diventa assente dal telefono (`free_space_item` lo sa prima della lettura del telefono): la cella resta, con il segno "solo NAS"; "Eliminati di recente" (Fase 3) permette il ripristino per 30 giorni |

| Escluso perché | Quando |
|---|---|
| Non verificato o verifica non tornata (E-B8) | ogni volta che la rilettura non dà lo SHA-256 della versione |
| Versione da proteggere non al sicuro | originale o attuale senza copia privata verificata |
| In caricamento o in errore | elemento del diario aperto o `BACKUP_PROBLEM` |
| Area privata congelata o scritture ferme (E-B22) | finché dura |
| NAS irraggiungibile (E-B9) | "Libera spazio" non parte, con il motivo |
| Debug con gli account di prova (D13) | tutto ciò che non sta in `mcg-prova` |

| Edge case | Comportamento |
|---|---|
| E-B8 | Copia non integra: esclusa, backup rifatto |
| E-B9 | NAS irraggiungibile: impossibile, spiegato |
| E-B14 | Sola copia comune: prima la copia privata |
| E-B22 | Area privata congelata: impossibile, spiegato |
| E-D1 | Media su telefono e NAS: "Solo dal telefono" / "Ovunque" (4B), con D22 |
| E-D7 | Eliminazione di massa: riepilogo, conferma, cestino di Android annullabile per 30 giorni |
| E-D14 | "Elimina solo dal telefono" di un media non al sicuro: "Prima mettilo al sicuro" |
| E-D15 | "Ovunque" con versioni precedenti: anche le versioni nel cestino del NAS, elencate |
| E-P11 | Un media liberato non è "perso": la Libreria lo mostra dal NAS |
| E-S11 | Un dato letto dal NAS non sceglie mai cosa liberare: l'elenco nasce dalla scelta dell'utente sul catalogo locale, il NAS fa solo da cancello (una verifica può togliere un elemento, mai aggiungerlo) |

**Il "Fatto quando" di 5.5** ("simulando una copia sul NAS corrotta o mancante, quell'elemento non finisce mai tra quelli da liberare"): test con un byte cambiato prima della verifica, dopo la verifica e prima del controllo finale, con la copia tolta, con il NAS che cade a metà verifica, con l'ora del telefono spostata; F37 sul campo.

### 2.6 Stato del backup (5.6, P5)
- **Piede della Libreria** (terza voce, componenti G2): "Tutto al sicuro" · "Oggi alle 03:12"; "312 in coda" · "Attesa carica"; "Primo backup: 1.240 di 6.658" · "circa 1 h 20 min"; "Quota esaurita" · "Chiedi all'admin del NAS"; "Backup in prova" · "Si scrive solo nelle share di prova" (D12). Il tocco apre la pagina Backup.
- **Pagina Backup**: una riga per sorgente (`SourceStatus`), il problema più grave in cima con la sua azione, "Fai ora", "Ferma", la cartella del dispositivo, le impostazioni; nella 5B la sezione "Da decidere" (attività in sospeso).
- **Viewer**: la riga "Backup" del pannello info (`MediaBackupState`).
- **Notifiche** (proposta 12): avanzamento solo per il primo backup e "Fai ora"; i problemi nel riepilogo giornaliero, al massimo una volta al giorno, con soli numeri; nessun nome di file.

**Ogni problema ha un messaggio e un'azione** (Fatto quando di 5.6; `BackupProblem`, codici stabili, un test li confronta con il catalogo della Diagnostica)
| Codice | Messaggio | Azione | Edge case |
|---|---|---|---|
| `NAS_UNREACHABLE` | Il NAS non risponde | Riprova · Diagnostica | E-R5 |
| `NAS_FULL` | Il NAS è pieno | Spazio NAS · Riprova | E-R9 |
| `QUOTA` | Spazio del tuo account esaurito | Chiedi all'admin del NAS · Riprova | E-N13 |
| `AUTH` | Serve la password del NAS | Inserisci la password · Riprova | E-R8 |
| `READ_ONLY_AREA` | La tua area privata è in sola lettura | Chiedi all'admin del NAS · Riprova | E-N9, E-I19 |
| `NO_PRIVATE_AREA` | Manca l'area privata | Chiedi all'admin del NAS (la pagina «Impostazioni → Aree» non c'è: le aree si fissano alla fondazione della famiglia e l'app non crea share, E-O3) | E-O2 |
| `MEDIA_PERMISSION` | Accesso alle foto ritirato | Consenti | E-P15, E-O4 |
| `MEDIA_LOCATION` | Foto in attesa: serve l'accesso alla posizione delle foto | Consenti | E-P21 |
| `PARTIAL_ACCESS` | Si salvano solo le foto selezionate | Consenti tutto | E-P1 |
| `CAP_REACHED` | Tetto dati raggiunto | Fai ora (con conferma) | E-B10 |
| `FROZEN` | Lavori fermi: cambiamenti anomali sul NAS | Controlla (Diagnostica) | E-B19, C11 |
| `WRITES_STOPPED` | Scritture sul NAS ferme | Diagnostica | E-B19 |
| `TEST_ONLY` | Backup in prova | — (lo spegne la chiusura della 5A) | D12 |
| `LOST_BEFORE_BACKUP` | N foto cancellate prima del backup | Apri il cestino di Galleria | E-B4 |
| `VERIFY_FAILED` | N copie non integre | Riprova · Diagnostica | E-B18, E-B8 |
| `UNREADABLE` | N file del telefono illeggibili | Riprova · Diagnostica | D15, E-M12 |
| `COMMON_COPY_LOST` | N media spariti dall'area comune | Recuperalo dal cestino del NAS | E-B13, L44 |
| `UNEXPECTED` | N media con un errore inatteso | Riprova · Diagnostica | R1 |
| `SOURCE_CHANGED` | N versioni in attesa del computer: il file sul NAS è stato cambiato dal PC (P14, 5B) | Diagnostica | E-E6 |
| `PHONE_STORAGE_LOW` | Memoria del telefono quasi piena | Libera spazio | E-P3 |
| `STALE` | Backup fermo da 3 giorni | Guida: batteria "Illimitata", "Mai in sospensione" | E-P15, E-P2 |
| `WAITING_UNLOCK` | Il telefono si è riavviato: riprende allo sblocco | — | E-P13 |
| `PROFILE_INACTIVE` | Profilo non attivo | — | E-P19 |

### 2.7 Integrazioni con le altre app (5.7, 5B, P10, P11)
**Scegliere foto per le altre app** (D23)
| Via | Chi la usa | Comportamento |
|---|---|---|
| `PickerActivity` (`GET_CONTENT`, `PICK`, `image/*`, `video/*`, selezione multipla) | app che offrono la scelta fra le app (OneDrive, I miei file, Google Drive la mostrano già: `android.md` 5.2) | griglia della Libreria (stessa pipeline), nascosti esclusi (E-H1), blocco dell'app prima di mostrare (E-X7); risultato: URI del MediaStore per le copie del telefono, URI del nostro provider per i solo-NAS, sempre con `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` |
| `MediaDocumentsProvider` (radice "MyCloud Gallery": Recenti, Cartelle NAS) | selettori di documenti: Gmail ("Allega file"), "Documento" di WhatsApp e Telegram | id dei documenti opachi (mai percorsi); solo media del catalogo; nascosti esclusi; nomi visualizzati = nome del file |
| Condividi dal viewer (Fase 3, esteso) | WhatsApp, Telegram, Messaggi (hanno un selettore loro, irraggiungibile) | con "Rimuovi la posizione" una copia verificata senza GPS (D26) |

| Edge case | Comportamento |
|---|---|
| E-X1 | Media solo-NAS con il NAS irraggiungibile: errore subito con il motivo, mai un'attesa infinita (scadenza a ogni lettura, 15 s) |
| E-X4 | Selezione annullata durante lo scaricamento: scaricamento annullato, temporaneo tolto |
| E-X6 | Molti solo-NAS insieme: coda con tetto (100 elementi, 2 GiB), oltre rifiuto motivato |
| E-X7 | Blocco dell'app attivo: prima lo sblocco con la credenziale di sistema |
| E-H1 | Nascosti mai nel selettore né nel provider (test su entrambe le vie) |
| E-V7 | Condividere un solo-NAS: scaricamento temporaneo in `cacheDir/share/`, posizione secondo l'impostazione, pulizia alla fine e all'avvio |

**Ricevere condivisioni** (D24): copia immediata con i tetti, tipo dai byte, poi foglio "Salva sul NAS" (cartella scelta; per l'area comune "Lo vedranno tutti i familiari"); il caricamento è un elemento `INCOMING` del diario del backup, con la stessa verifica e lo stesso "mai sostituire".
| Edge case | Comportamento |
|---|---|
| E-X2 | File enorme, troppi elementi, URI `file://` o di un provider dell'app, tipo falso: rifiuto motivato, niente copiato |
| E-X8 | NAS non raggiungibile: la copia locale resta in coda (con i tetti) e si carica appena si può |
| E-S11 | Una condivisione non fa partire niente da sola: serve il tocco su "Salva sul NAS" |

**"Modifica con…"** (D25)
| Media | Via | Esito |
|---|---|---|
| Sul telefono | `ACTION_VIEW` verso Samsung Gallery (pacchetto esplicito), sessione di 30 minuti | riga cambiata → versione; riga nuova nella stessa cartella con la stessa data di scatto → versione dello stesso media (E-X3); nessun cambio → niente |
| Solo NAS, editor che accetta `ACTION_EDIT` (Google Foto, OneDrive) | copia in `cacheDir/edit/` servita da `FileProvider` in lettura e scrittura | byte cambiati → versione (E-E3); file nuovo altrove → ricollegato nella sessione, altrimenti media nuovo |
| Solo NAS, "Scarica e modifica in Galleria" | copia in `Pictures/MyCloud Gallery` (F4-D20), poi come "sul telefono" | versione del media del NAS (E-E7) |

**Condividere senza posizione** (D26, E-X5): JPEG/PNG/WebP, MP4/MOV, HEIC → JPEG; ricontrollo con due lettori; formato non trattabile o posizione rimasta = condivisione fermata con il motivo ("Questa foto non si può condividere senza posizione: condividila con la posizione o annulla").

### 2.8 Regole applicate, lavori e UIDT (P4, P7)
| Lavoro | Attività | Note |
|---|---|---|
| Backup delle foto | `PHOTO_BACKUP` (leggera) | Bilanciato e Wi-Fi sempre: appena c'è il Wi-Fi, anche a batteria sopra la soglia; Notte: in fascia, in carica |
| Backup dei video | `VIDEO_BACKUP` (pesante) | regole proprie; solo Wi-Fi di casa con la proposta 1 |
| "Proteggi subito le foto nuove" (D34) | `PHOTO_BACKUP`, richiesta `QUICK_NEW_PHOTOS` | solo foto nuove della fotocamera, Wi-Fi di casa, batteria sopra la soglia, anche fuori fascia |
| Verifiche, `FULL_HASH`, rotazione, guardiano | `PHOTO_BACKUP` / `VIDEO_BACKUP` secondo il media | leggono (e il guardiano copia lato server, senza traffico del telefono) |
| Primo backup, "Fai ora", verifica di "Libera spazio", arrivi grandi | `UserInitiated` (UIDT) | scavalca fascia e carica; sui dati mobili chiede conferma con la stima; termica `CRITICAL` ferma tutto |
| Pubblicazione dell'indice dei media caricati | quella dell'uploader, con i cancelli dell'indice (F4-D31) | solo locale sulle share vere finché vale F4-D31 |

- **Tappe da 8 minuti** con `StagedRun`: valuta → lotto (un elemento alla volta, due in volo) → registro del traffico → punto di ripresa nel diario; `Stop` salva ed esce; in carica i limiti dei bucket non valgono (`android.md` 1.1).
- **UIDT**: garantite 6 ore, limite 12 per lavoro, 24 cumulate (`android.md` 1.2): il primo backup stimato (§2.9) ci sta; se Android lo ferma (termica, memoria) la ripresa è un lavoro normale.
- **Battito**: `PHOTO_BACKUP` e `VIDEO_BACKUP` nel `HeartbeatStore`; "backup fermo da 3 giorni" nel riepilogo (E-P15).
- **Profili del tablet** (E-P19), riavvii (E-P13), limiti di Android (E-P14), Protezione batteria (E-P16): già nel motore delle regole.

### 2.9 Stima dei costi
**Ipotesi** (da sostituire con le misure di M2 e del primo backup vero; il riepilogo del primo backup usa le dimensioni **vere** del MediaStore)
- Scrittura SMB cifrata **W = 9 MB/s** con un flusso (S25, file da 50 MB); due caricamenti paralleli **non misurati** (la CPU del NAS fa da tetto: in lettura 4 flussi rendono 2,2 volte uno).
- Rilettura di verifica **R = 16 MB/s** a 4 flussi.
- **Costo per file o = 0,12 s**: circa 12 viaggi SMB (creazione del temporaneo, scritture, `flush`, chiusura, apertura e lettura di verifica, rinomina, `stat`) a circa 10 ms l'uno in casa: **ipotesi**, da misurare.
- Preparazione dai byte del telefono: 0,15–0,3 s a foto sulla CPU, due in parallelo: si sovrappone ai trasferimenti. Hash locale: trascurabile (lettura dalla memoria del telefono).
- Composizione dell'S25 (conteggi veri del 12/10, dimensioni **ipotizzate**):

| Gruppo | Media | Dimensione media ipotizzata | GB |
|---|---|---|---|
| Fotocamera (`DCIM/Camera`) | 866 foto | 4 MB | 3,5 |
| Sottocartella di `DCIM` | 1.088 foto | 3 MB | 3,3 |
| Screenshot | 915 | 0,7 MB | 0,6 |
| App di messaggistica (`Android/media/…`) | 2.105 | 0,2 MB | 0,4 |
| Quick Share | 654 | 2,5 MB | 1,6 |
| `Pictures` e altre | 646 | 1,5 MB | 1,0 |
| Video | 384 | 100 MB (da 50 a 230) | 38 (da 20 a 90) |
| **Totale** | **6.658** | | **~48 GB** (da ~26 a ~106) |

**Tempo del primo backup** (in casa, S25): `T = B/W + B/R + N·o`
| | Foto (6.274, ~10,4 GB) | Video (384, ~38 GB) | **Totale** |
|---|---|---|---|
| Caricamento | ~19 min | ~70 min | ~90 min |
| Rilettura di verifica | ~11 min | ~40 min | ~50 min |
| Costo per file | ~13 min | ~1 min | ~14 min |
| **Tempo** | **~45 min** | **~1 h 50 min** | **~2,6 h** (caso basso ~1,5 h, alto ~5,3 h) |
| Pubblicazione dell'indice (quando F4-D31 si spegne) | ~3,6 GB | ~0,35 GB | ~4 GB, ~8 min in più |

Con due caricamenti paralleli, se il NAS li regge, il totale può scendere verso ~2 h: lo dice la misura di P2 sul campo (F22). Solo le sorgenti predefinite (fotocamera e screenshot, ~1.800 foto e i video della fotocamera) costano circa la metà.

**Traffico e batteria del primo backup**
| | Valore centrale | Note |
|---|---|---|
| Traffico Wi-Fi | ~97 GB (48 caricati + 48 riletti) | caso basso ~52 GB, alto ~212 GB; più ~4 GB d'indice quando si pubblica |
| Dati mobili | 0 con Notte, Bilanciato e Wi-Fi sempre | con Massimo al più il tetto di 10 GB, cioè ~5 GB di media (la rilettura conta) |
| Batteria (modello, 0,5% per GB, **ipotesi**) | ~48% se fosse tutta a batteria | controllo fisico: 2,6 h di trasferimento a 1,5–2,5 W sono 4–6,5 Wh, cioè il 25–40% della batteria dell'S25; con Notte e con i video di Bilanciato viene dalla presa |
| Spazio sul NAS | ~48 GB nell'area privata | 2,42 TB liberi |
| Spazio sul telefono | ~190 MB di miniature S e M (qualità Standard) | nessun temporaneo grande: il caricamento legge in streaming |

**Primo backup per preset** (valore centrale, ~6.700 media, ~48 GB)
| Preset | Quando finisce | Batteria dalla batteria | Traffico |
|---|---|---|---|
| **Notte** (default) | **1 notte**: 23–07 in carica sono ~6,4 h utili (efficienza 0,8) contro ~2,6 h (anche il caso alto, 5,3 h, sta in una notte); 2 notti se il telefono non resta in carica tutta la notte | 0% (dalla presa; la Protezione batteria all'80% conta come in carica) | ~97 GB sul Wi-Fi di casa |
| **Bilanciato** | foto in giornata appena c'è il Wi-Fi (~45 min), video la notte | ~10–13% per le foto se a batteria (traffico più preparazione) | uguale |
| **Wi-Fi sempre** | ~2,6 h a casa, anche a batteria | ~45–50% secondo il modello: meglio in carica | uguale |
| **Massimo** | come Wi-Fi sempre; fuori casa anche sui dati mobili entro il tetto | come Wi-Fi sempre | fino a 10 GB di dati mobili |
| **"Fai ora"** (UIDT) | ~2,6 h da subito, scavalca fascia e carica | come Wi-Fi sempre se a batteria | sui dati mobili chiede conferma con la stima |

**A regime** (ipotesi: 200 foto e 10 video al mese, ~1,7 GB): ~3,4 GB di traffico al mese (caricamento e verifica), ~6 minuti di lavoro al mese, ~1,7% di batteria al mese secondo il modello (~0,06% al giorno, dalla presa con Notte): dentro l'obiettivo del 2% al giorno. Con D34, le foto nuove partono di giorno sul Wi-Fi di casa: qualche secondo per foto.

**Altri costi**
| Lavoro | Costo |
|---|---|
| Riverifica a rotazione (Q10) | ~48 GB/anno → ~140 MB a notte, ~10 s |
| Verifica sul momento di "Libera spazio" | liberare tutto (~48 GB): ~50 min più ~6 min per i file; solo i video (~38 GB): ~40 min; poi un dialogo di sistema ogni 2.000 elementi (4 per l'intero telefono) |
| Guardiano | copia lato server a 124–148 MB/s più la rilettura a 16 MB/s: ~1 min per GB, nessun traffico del telefono oltre la rilettura |
| Tab S9 (834 immagini, 56 video) | sotto i 30 minuti |
| Via VPN fuori casa | **non misurato** (`inventario-nas.md` §5): le foto vanno su qualunque Wi-Fi via VPN, i video aspettano casa (proposta 1) |

**Calibrazione (obiettivo ±30%)**: P2 e P4 misurano W con 1 e 2 flussi, o e R durante le scritture (F22) e li scrivono in `DeviceMeasures`; il primo backup vero (F32) è il confronto che conta per il "Fatto quando" di 5.1; la settimana dei preset (F33) ricalibra la batteria per GB.

### 2.10 Cosa la Fase 5 predispone per le fasi successive
| Pezzo | Come lo usano |
|---|---|
| Anteprime di analisi preparate al caricamento (D10) | Fase 6: gli Indicizzatori le trovano nell'indice, nessun originale riletto |
| Guardiano (D9) | Fase 7: "al sicuro" garantito quando un album condiviso sposta i file nell'area comune (7.4) |
| Caricamento con destinazione nell'area comune (`INCOMING`) | Fase 7, E-SH1: media solo sul telefono caricati direttamente nell'area comune |
| Versioni nell'area comune (D35) | Fase 7: i permessi dell'album decidono chi può creare versioni |
| `MediaDocumentsProvider` | Fase 10.2: trascinamento fra app sul tablet |
| `PhoneConfirmation` | ogni azione futura che tolga copie dal telefono |

### 2.11 Backup già fatti prima dell'app (richiesta dell'utente del 15/10)
L'utente ha già copiato sul NAS, a mano o con altri strumenti, molte foto e video che sono anche sul telefono: **non vanno
ricaricati**. Requisiti per P0 e P4 (piano del backup), P2 (caricamento) e P3 (deduplica):
1. **Prima del primo backup la scansione della Fase 4 dell'area privata deve essere completa**, così che i candidati della
   deduplica (D8) siano noti **in qualunque cartella** dell'area privata (es. vecchie cartelle "Backup telefono 2023/"), non
   solo in `Backup/<dispositivo>/`. Il piano del primo backup aspetta la fine della scansione e lo dice ("Sto cercando
   cosa c'è già sul NAS…").
2. **Copie identiche byte per byte nell'area privata**: collegate dopo la rilettura (D8, `LINKED_EXISTING`), nessun
   caricamento e niente spostamenti dei file esistenti (restano dove l'utente li ha messi).
3. **Copie identiche nell'area comune o in una share pubblica** (es. `NAS`, `Public`): **non si ricaricano** nell'area
   privata. Il media risulta "sul NAS (area comune)", non "al sicuro" (piano §2). La copia privata si crea solo quando sparisce
   quella del telefono o prima di "Libera spazio", come da risposta Q4 (§9.1).
4. **Riepilogo del primo backup** con tre numeri e la stima: "N già sul NAS (collegati), M sul NAS ma non identici,
   K da caricare (X GB, circa Y ore)".
5. **Costo**: la rilettura delle copie già presenti (es. 40 GB a 16 MB/s, circa 45 minuti) si fa secondo le regole
   (`PHOTO_BACKUP`/`VIDEO_BACKUP`, di notte con il preset Notte), dai più recenti, e si riprende.
6. **Copie "quasi uguali"** (stessa data di scatto, stesse dimensioni, impronta visiva simile, ma byte diversi: ricompressione
   o EXIF cambiati da un'altra app di backup): **si caricano sempre** e poi si **uniscono in una cella** (risposta Q11, D36,
   §2.12). Il riepilogo diventa: "N già sul NAS (collegati), M con una copia simile sul NAS (si caricano e si vedranno
   insieme), K nuovi: da caricare M+K, X GB, circa Y ore".
7. **Dove e con cosa** (risposta Q12): i vecchi backup stanno nell'area privata dell'utente, fatti con l'app di WD e con
   un'altra app o un cloud. Ci si aspetta quindi sia copie identiche (l'app di WD carica gli originali) sia copie simili
   (i cloud spesso ricomprimono o riscrivono l'EXIF). I numeri veri li dà la scansione in sola lettura dell'archivio (F2).
8. **Riordino** (risposta Q13): le copie restano dove sono per il backup. **Dopo il primo backup vero** (fine 5B, Q3) l'app
   propone di spostarle in `Backup/<dispositivo>/AAAA/MM/` con la conferma e il diario della Fase 4B (D37, P16).

Domande per l'utente (fatte il 16/10, risposte nel §9.1):
| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q11 | Copie sul NAS "quasi uguali" ma non identiche byte per byte | (a) Si caricano comunque (doppione sul NAS, sicuro). (b) Si mostra l'elenco una volta ("312 foto sembrano già sul NAS ma non sono identiche") e decidi tu: carica / salta / guarda. (c) Si saltano sempre | **(b)**: niente doppioni senza che tu lo sappia, niente media non protetti senza che tu lo sappia |
| Q12 | Dove stanno i backup già fatti (share e cartelle) e con quale strumento sono stati fatti | Elenco libero | Serve a includerli nella scansione e a stimare quante copie sono identiche |
| Q13 | Le copie già presenti restano dove sono o si riordinano in `Backup/<dispositivo>/AAAA/MM/`? | (a) Restano dove sono. (b) Riordino proposto più avanti, con conferma (Fase 4B: sposta) | **(a)** per il backup; il riordino eventuale è un'operazione separata e confermata |

### 2.12 Copie simili (D36, risposta Q11)
**Cosa vede l'utente.** Lo stesso scatto salvato con byte diversi (ricompresso da un cloud, EXIF riscritto da un'altra app,
HEIC convertito in JPEG) occupa **una sola cella** in Libreria, come le raffiche di Samsung Gallery e le pile di Google Foto.
Il viewer mostra "2 copie" nelle info e una striscia per guardarle tutte (dove sta ognuna, risoluzione, dimensione, formato).
Due azioni: **"Mostra questa come principale"** e **"Separa"**. Il backup carica sempre l'originale del telefono: solo i byte
identici si collegano senza caricare (D8 resta com'è).

**Quando due media sono copie simili** (tutte le condizioni; "nel dubbio non si unisce"):
1. stesso tipo (foto con foto, video con video);
2. stessa **ora di scatto letta dai metadati**, al secondo (mai una data "stimata", mai la data del file); se entrambi hanno
   i sottosecondi (`SubSecTimeOriginal`) devono coincidere (le raffiche li hanno diversi);
3. stessa proporzione dopo l'orientamento (scarto massimo 1%); per i video anche la durata (scarto massimo 0,5 s);
4. **firma visiva** uguale entro le soglie (sotto);
5. nessuno dei due è in una raffica o in una coppia RAW+JPEG (non si spostano, come i gruppi `PROBABLE_DUPLICATE`);
6. nessuna ambiguità: un media che somiglia a due media distinti non ancora raggruppati fra loro non entra in nessun gruppo;
7. nessuna separazione salvata dall'utente fra i due ("Separa" vale per sempre e si sincronizza fra i suoi dispositivi).

**Firma visiva.** Calcolata **solo in locale e solo per i candidati** (stessa ora di scatto e stessa proporzione: pochi casi),
mai pubblicata sul NAS (il formato dell'indice non cambia). Foto: decodifica a misura fissa (es. 32×32 dopo l'orientamento,
in uno spazio di colore percettivo) dall'originale sul telefono e dalla **miniatura dell'indice** sul NAS (la stessa su ogni
dispositivo; se manca, il candidato passa avanti nella coda della Preparazione). Video: la stessa firma su due fotogrammi
agli stessi istanti. Le soglie si tarano con le fixture:
- **devono unirsi**: JPEG ricompresso da qualità 50 a 95; ridotto al 50% e a 16 MP; EXIF tolto o riscritto; HEIC convertito
  in JPEG; video ricodificato a 1080p o con meno bitrate;
- **non devono unirsi**: fotogrammi consecutivi di una raffica; esposizione ±0,3 EV; filtro bianco e nero o di colore;
  ritaglio del 3%; raddrizzamento di 1°; specchiatura; due scatti diversi nello stesso secondo.

**Quale si vede** (copertina del gruppo), in ordine:
1. più pixel (video: risoluzione);
2. a parità di formato, **meno compressione**: più byte per pixel (video: bitrate più alto);
3. a formati diversi, **l'originale della fotocamera** (la copia che viene dal telefono o con i dati del produttore intatti):
   una conversione non può avere più dettaglio della sua sorgente;
4. file più grande; poi id minore (stabile).

La scelta manuale ("Mostra questa come principale") è un dato personale e vince sulla regola.

**Regole del gruppo** (nuovo `MediaGroupKind.SIMILAR_COPIES`, stessa meccanica di copertina e `GROUP_MEMBER` del §11 di
`modello-dati.md`):
- **Segni della cella**: sul telefono se almeno una copia è sul telefono; **al sicuro solo se ogni copia che sta sul telefono
  è al sicuro**; `BACKUP_PROBLEM` se una copia lo ha; più il segno "copie" con il numero (glifo del catalogo G2).
- **Nascosti** (E-H1): nascondere dalla cella nasconde **tutte** le copie. Se un gruppo nasce con una copia nascosta, il gruppo
  intero è nascosto e le info lo dicono: la stessa foto non deve ricomparire dalla porta della sua copia. "Separa" non toglie
  il nascosto.
- **Azioni dalla cella** (preferito, elimina, condividi, sposta): valgono per tutte le copie, e il foglio dice "2 copie"; dalla
  striscia del viewer valgono per la copia scelta.
- **Album e ricerca**: un album che contiene una copia mostra la cella del gruppo; la ricerca (Fase 6) restituisce una cella
  per gruppo.
- **"Separa"**: toglie quella copia dal gruppo e salva la separazione (dato personale sincronizzato, 4.7); un gruppo rimasto
  con una copia si scioglie.
- Il gruppo è un **dato derivato locale**: ogni dispositivo lo ricalcola dagli stessi dati; nessuna azione sul NAS parte da lì.

**Quando gira.** Come manutenzione del catalogo, dopo che META ha letto l'ora di scatto di un media nuovo (telefono o NAS),
con i candidati presi dall'indice `sort_time`; per il riepilogo del primo backup il piano (P4) aspetta che il confronto dei
candidati sia finito e lo dice ("Sto confrontando 120 foto con le copie sul NAS…").

### 2.13 Riordino dei vecchi backup (D37, risposta Q13; 5B, dopo il primo backup vero)
- Dopo il primo backup vero (fine 5B, Q3) la pagina Backup propone una volta, poi resta disponibile: "312 file dei vecchi backup
  stanno fuori da `Backup/`: vuoi spostarli in `Backup/<dispositivo>/AAAA/MM/`?". Elenco per cartella con conteggi e GB.
- Si spostano solo file dell'area privata del proprietario, fuori da `Backup/` e `Versioni/`, che sono copie (identiche o simili)
  di media di un suo dispositivo. Mai l'area comune, mai file di altri.
- Lo spostamento è un piano della 4B (`core-nasops`, `UserConfirmation` dal foglio, diario, ripresa, conflitti di nome senza
  sostituire: "IMG_1234 (2).jpg"); nella stessa share è una rinomina, senza copie. Il congelamento non scatta: i movimenti propri
  si riconoscono dal diario (franchigia della 4B). Le cartelle rimaste vuote si tolgono solo se davvero vuote.
- **Domanda Q14** (da fare all'avvio di P16, con i numeri della scansione vera F2): dove vanno i file dei vecchi backup che non
  sono più su nessun dispositivo (telefoni precedenti)?

## 3. Alternative scartate
| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Verifica del caricamento | fidarsi della cifratura SMB3 e della lunghezza; rilettura a campione; rilettura completa | **Rilettura completa** (D4): la cifratura protegge il trasporto, non un nostro errore di offset, un bug del server o un altro scrittore; il piano chiede l'hash completo |
| Dove si verifica | sul file pubblicato; sul temporaneo | **Sul temporaneo**: nessun file visibile non verificato, e una sola lettura (dopo basta il timbro) |
| Ripresa dei video | ricominciare da zero; punto di ripresa più verifica finale; hash a pezzi salvati | **Punto di ripresa ogni 64 MiB** più verifica completa (D5): lo stato di SHA-256 di Java non si salva, e la verifica finale rende inutile fidarsi del prefisso |
| Primitive del backup | `NasFileOps` intera; una vista in sola aggiunta | **Vista in sola aggiunta** (D2): il codice che gira da solo ogni notte non deve poter togliere un file |
| Dove sta il caricamento | dentro `core-nasops`; modulo nuovo | **`core-backup`**: `core-nasops` entra solo da una conferma per piano; il backup parte da dati locali senza conferma per file, quindi deve essere un modulo diverso con poteri minori |
| Diario | le tabelle `nas_op`; tabelle proprie | **Proprie** (`backup_item`): macchina diversa, campi di ripresa, nessuna conferma all'origine |
| Deduplica | fidarsi dell'indice; dimensione e `qh1`; rilettura completa | **Rilettura completa** (D8, F4-D8): "nel dubbio si carica" |
| Copia privata di un media dell'area comune | ricaricarla dal telefono; copia lato server con verifica | **Copia lato server** (D9): 15 volte più veloce e vale anche senza la copia del telefono; la verifica completa resta |
| Versioni precedenti | sovrascrivere e tenere solo l'ultima; nascoste in `.mycloudgallery`; accanto a ogni cartella; una radice `Versioni/` | **Radice `Versioni/`** (D17, Q2): sfogliabile dal PC, un solo posto da escludere in Plex e Twonky, il formato non cambia |
| Ordine delle rinomine di una versione | pubblicare prima la nuova con un altro nome; archiviare prima l'attuale | **Verifica, archiviazione, pubblicazione** (D17): il solito percorso resta vuoto un istante, mai con un file non verificato |
| "Libera spazio" | cancellazione diretta; cestino di Android; `MANAGE_MEDIA` per togliere i dialoghi | **Cestino di Android** con il dialogo di sistema (piano 5.5), "Libera subito" solo con due conferme; niente `MANAGE_MEDIA` (minimo privilegio) |
| Validità della verifica sul momento | solo la verifica; verifica più controllo del timbro prima del dialogo | **Entrambi** (D21): la verifica dura minuti, il dialogo arriva dopo |
| Comparire nel selettore di sistema | Cloud Media Provider; `GET_CONTENT`/`PICK` più `DocumentsProvider` più Condividi | **Le tre vie possibili** (D23): il Cloud Media Provider è chiuso alle app nominate dagli OEM |
| Media solo-NAS ai selettori | scaricare tutto prima; descrittore a richiesta | **A richiesta** con ripiego sullo scaricamento (D23, T-14) |
| Primo backup lungo | servizio in primo piano; UIDT; solo tappe | **UIDT** per il primo backup e "Fai ora", tappe per il resto (D11; `android.md` 1.3) |
| Correzioni di HEIC e video | scrittore nostro di MP4 e HEIF; dipendenza nuova; solo nell'app | **Domanda Q6**, raccomandato "solo nell'app" per ora |
| Togliere la posizione dai video | ricodificare con `MediaMuxer`; rinominare le scatole in `free` | **Rinominare** (D26): stessa dimensione, nessuna perdita di qualità, ricontrollo dopo |
| "In caricamento" sulle celle | bit nei flag della cella; solo viewer e pagina | **Solo viewer e pagina** (D15): l'indice della timeline non deve cambiare migliaia di volte |
| Primo backup | subito; stanotte; scelta nel riepilogo | **Scelta**, con "Stanotte" predefinita (D11) |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
5A
P0 fondamenta, contratti, recinti, documenti ──┬── P1 modello e database v4 ────────────────────────┐
                                                ├── P2 caricamento sicuro (core-backup + core-nas) ─── M1 ┤
                                                ├── P3 "al sicuro", deduplica, guardiano ────────────┤
                                                ├── P4 piano, sorgenti, destinazioni, stime ─────────┤
                                                ├── P5 stato del backup e UI della 5A ───────────────┤
                                                ├── P15 copie simili (rilevamento, copertina, cella) ┤
                                                └── P6 prova delle 500 interruzioni (banco, area, telefono) ┴── P7 integrazione 5A ── M2
                                                                         ── check.ps1 (-Bench) + security-review (D12 resta acceso, Q3 (b))
5B (dopo l'approvazione della 5A)
P0-B fondamenta 5B e prove T ──┬── P8 versioni e correzioni ──────────────────┐
                                ├── P9 "Libera spazio" e cancellazioni ───────┤
                                ├── P10 scegliere foto per le altre app ───────┤
                                ├── P11 ricevere, "Modifica con…", senza posizione ┤
                                ├── P12 UI della 5B ──────────────────────────┤
                                └── P13 prova delle 500 interruzioni della 5B ┴── P14 integrazione 5B ── M3
                                                                         ── security-review
                                                                         ── Q3 → D12 spento → primo backup vero (F32) ── P16 riordino dei vecchi backup (Q14)
```
- Dopo P0 tutti i pacchetti della 5A partono **insieme** sui contratti, sull'esecutore di riferimento e sui fake di P0; si incontrano in P7.
- **M1** è un'integrazione parziale dell'orchestratore dopo P1 e P2 (la verifica del caricamento è dentro P2): una foto, dal telefono finto e poi dall'S25, fino allo scudo in Libreria.
- P6 parte con l'esecutore di riferimento di P0 e passa a quello vero quando P2 e P3 consegnano.
- **Prima di P0**: committare le modifiche della 4B ancora aperte (R11) e, prima di M2, lanciare F16 della 4B (runner sull'area di prova), che prova anche il file delle credenziali usato qui.

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Una foto del telefono caricata in `Backup/<dispositivo>/AAAA/MM/`, riletta, con lo **scudo** in Libreria e "Al sicuro sul NAS" nel viewer | Emulatore con il NAS finto; S25 debug con `test` sull'area di prova (F21): SHA-256 del file sul NAS uguale a `adb shell sha256sum` del file sul telefono (T-11: byte originali, posizione compresa) | P0, P1, P2 (+ integrazione minima dell'orchestratore) |
| **M2** | Primo backup completo e riprendibile del set di prova (`mcg-prova`: 300 foto di formati misti, 2 video da 2–3 GB), riepilogo con la stima, notifica, "Ferma" e ripresa; stato in Libreria | F22–F31; stima contro misura entro ±30%; **prova delle 500 interruzioni** (2.000 giri JVM, 600 sul simulatore, ≥ 400 sull'area di prova, ≥ 100 sul telefono) | + P3, P4, P5, P6, P7 |
| Chiusura 5A | Revisione di sicurezza; D12 resta acceso (Q3 (b)) | `check.ps1`, `check.ps1 -Bench` su S25 e Tab S9, `security-review` con revisione mirata di `core-backup` e `NasBackupOps` | — |
| **M3** | Una modifica in Samsung Gallery diventa una versione (originale in `Versioni/`); "Libera spazio" esclude una copia rovinata dal PC e manda il resto nel cestino di Android; WhatsApp, Gmail e Samsung Gallery nei tre flussi | F34–F42, prova delle interruzioni della 5B (F43) | P0-B, P8–P14 |
| Chiusura 5B | Revisione di sicurezza (componenti esportati, conferme, "Libera spazio"); con il consenso (Q3 (b)) D12 si spegne e parte il **primo backup vero** dell'S25 sull'area privata con l'APK di release; settimana dei preset con il backup acceso (F33) | F43–F45, `security-review`, F32 (tempo, traffico e batteria contro il riepilogo) | — |

**Proposta: sì alla divisione in 5A e 5B** (D1, Q1). La 5A da sola è il valore che conta (il telefono al sicuro sul NAS, tutte le notti) e non può togliere o sostituire nulla; la 5B arriva dopo che il backup ha superato le interruzioni, la revisione e una notte vera sull'area privata.

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–4)
- **Mai** toccare file fuori dal proprio perimetro. Dipendenze, permessi, manifest, file di build condivisi e `settings.gradle` li toccano solo P0, P0-B, P7 e P14.
- **Nessuna rete nei moduli nuovi**: SMB solo con i contratti di `core-nas`; `NoNetworkSourcesTest` in ogni modulo nuovo.
- **Nessuna primitiva che toglie o sostituisce un file del NAS fuori da `core-nasops`**; `core-backup` usa solo `NasBackupOps` (compilatore e test di architettura).
- **Nessuna scrittura sul telefono** fuori dai dialoghi di sistema (`createWriteRequest`, `createTrashRequest`, `createDeleteRequest`) e dalle proprie righe `IS_PENDING` (F4-D20); nessuna nella 5A.
- **Foto sempre lette con `setRequireOriginal`** e `ACCESS_MEDIA_LOCATION`; mai un hash di byte oscurati.
- **Ora**: orologio monotono per durate e validità; ora del NAS per ciò che si firma o scade; mai l'orologio del telefono nelle decisioni (`NoWallClockSourcesTest`).
- **Log**: mai nomi di file, percorsi, share, account, id di media **né impronte** (uno SHA-256 riconosce una foto nota); notifiche e Diagnostica con soli numeri e codici.
- **Lettura difensiva** dei file in arrivo, dei risultati degli editor e di ciò che si rilegge: tetti prima di allocare, `MediaSniffer`, `ImageBounds` prima di decodificare.
- **Ogni passo in più tappe è idempotente** e ha il test "guasto a ogni chiamata" (`FaultPlan` prima e dopo) più la morte del processo a ogni scrittura del diario, sul NAS finto **e** sul telefono finto.
- **D12 e D13** valgono in ogni prova sul telefono; le prove dal PC solo dietro la guardia (F4-D30).
- Test JVM o Robolectric per ogni modulo; screenshot (solo dove c'è UI) in `docs/screenshots/5-<pacchetto>/`, chiaro e scuro, telefono e tablet, testo a 1,3x.
- Stringhe in italiano in file propri.
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti degli altri**.

### 4.4 I pacchetti
| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Campo |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti (5A)** | Moduli, contratti, esecutore di riferimento, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`), `tools/check.ps1` (D12 nel dex di release, D13 assente); creazione di `core-backup` e `feature-backup` (build, manifest, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest`, `NoWallClockSourcesTest`); **contratti** del §1.2 della 5A (`NasBackupOps`, marcatore e fake in `core-nas/.../ops/backup/` e `testing/`; porte, `UploadStateMachine`, `BackupStatus` di `core-backup`; `LibraryBackupStatus`; `CellFlags.BACKUP_PROBLEM` come firma; per D36 `MediaGroupKind.SIMILAR_COPIES`, la porta del rilevatore delle copie simili con il fake e i record personali di separazione e di copertina scelta, come firme); **esecutore di riferimento** `InMemoryBackupEngine`; `FakeMediaStore` esteso (§5.2); `core-nasops` (porta `CitedTemps` di `OwnFilesCleaner`, D33); `core-common` (`FeatureFlags.backupTestSharesOnly`, `DiagSection.BACKUP`, test di architettura); firma della recinzione di D13 in `DeveloperTools`; documenti del §0.3 | 4B chiusa (R11 committata) | `check.ps1` verde; nessuna dipendenza nuova; ogni test di architettura fallisce reintroducendo la violazione (`core-backup` che nomina `NasFileOps` o `core-nasops`; `NasBackupOps` fuori da `core-nas`/`core-backup`; un modulo che legge il NAS che vede `core-backup`; `feature-backup` che vede `core-nas`); `EveryCallTest` del backup sull'esecutore di riferimento (guasto prima e dopo a ogni primitiva, morte del processo a ogni scrittura del diario: invarianti del §5.4); `backupTestSharesOnly` vero in release e non spegnibile; un temporaneo `-bk-` citato dal diario sopravvive a `OwnFilesCleaner` | opus | no |
| **P1 Modello e database v4** | §1.3 `core-model`, `core-database`; per D36 le tabelle locali della firma visiva e delle copie simili (§2.12) | `core-model/**`, `core-database/**` (schemi, DAO, `MediaCatalog`), `docs/modello-dati.md` §12 | P0 | `4.json` esportato; `ExportedSchemaTest` 1→4, 2→4, 3→4 con `seedV3`; `MergeReferencesTest` con le tabelle nuove; `backupCandidates` su 100 mila media sotto 100 ms per pagina senza `USE TEMP B-TREE`; `verifyCandidates` per `verified_at` con l'indice nuovo; `attachCopies` a lotti in una transazione (un solo cambiamento dell'indice per lotto); `BACKUP_PROBLEM` derivato nella stessa transazione | sonnet | no |
| **P2 Caricamento sicuro** | §2.2, primitive del §1.2 | `core-backup/.../upload/**`, `.../store/**` (`RoomBackupStore`), `core-nas/.../ops/backup/**` e la vista in `SmbNasFileOps` (`reopenTemp`), `InMemorySmbBackend` (fine file) | P0 (P1 per l'adattatore Room) | ogni passo con `FaultPlan` a **ogni** chiamata e morte del processo a ogni scrittura del diario: invarianti del §5.4 veri; E-B1 (3 GB virtuali, al massimo 64 MiB ricaricati), E-B2, E-B3 (file diverso e file identico allo stesso nome), E-B5 tramite la porta, E-B15, E-B18, E-R9 (`DISK_FULL` a metà), E-N13, E-P12; nessun file fuori dalle radici ammesse (proprietà); nessuna sostituzione con 1.000 collisioni casuali; temporanei solo `<io>-bk-`; `reopenTemp` sul simulatore SMB; 200 giri del banco; costo per file misurato sul simulatore con le latenze dell'inventario; lettura e preparazione a priorità bassa con l'app in primo piano | opus | sì (F21–F25) |
| **P3 "Al sicuro", deduplica, guardiano** | §2.3, D8, D9, D14 | `core-backup/.../safety/**` (`SafetyVerifier`, `PrivateCopyGuardian`, deduplica, rotazione) | P0 (fake di P1, P2) | la tabella "Fatto quando di 5.3" del §2.3 in test, scenario per scenario (con il diario finto della 4B per lo spostamento nell'area comune); un hash dell'indice non fa mai "al sicuro" né collegamento (F4-D8); candidati solo dell'area privata propria (un'altra area privata e l'area comune piene di file identici non vengono lette); `FULL_HASH` risolve i gruppi `PROBABLE_DUPLICATE`; la rotazione trova un byte cambiato e toglie "al sicuro"; guardiano con copia lato server rifiutata (ripiego) e sorgente cambiata a metà (E-D13: niente pubblicato); `FaultPlan` a ogni chiamata; tetto di 20 GB per giro | opus | sì (F29) |
| **P4 Piano, sorgenti, destinazioni, stime** | §2.1, §2.8, §2.9, §2.11 (riepilogo con i tre numeri, attesa della scansione e del confronto delle copie simili), D6, D7, D11, D34 | `core-backup/.../plan/**`, `.../settings/**`, `core-rules/.../estimate/**`, `core-rules/.../settings/**` (regola di D34) | P0 | regole dei percorsi (E-B6, E-B7, E-B12, E-B16, E-B17, E-N6, nomi rifiutati da `SharePath`, microSD) in test; ordine (foto, recenti, originale prima della versione); riepilogo dalle dimensioni vere; la tabella dei preset del §2.9 riprodotta dallo stimatore con la composizione del §2.9; `PresetMatrixTest` esteso con D34 e "Fai ora" sui dati mobili | sonnet | no |
| **P5 Stato del backup e UI della 5A** | §1.5 (stati della 5A), §2.6, D15, D16, D30 | `feature-backup/**` (impostazioni, primo backup, pagina Backup), `core-library/.../library/LibraryBackupStatus*` (dati), `feature-library/.../library/LibraryStatusFooter.kt` (terza voce, componenti esistenti), `feature-viewer/.../info/BackupInfo*`, `feature-settings/.../backup/**` (voce), glifi `PROBLEM` e "copie" (D36) nel catalogo di `core-designsystem` | P0 (fake di `core-backup`) | ogni stato del §1.5 della 5A composto; ogni codice del §2.6 con messaggio e azione (test che legge l'elenco dal contratto); TalkBack e testo a 1,3x; bersagli da 48 dp (test dei bordi del piede); contrasti in `GalleryFooterStatusTest`; screenshot `5-P5` | sonnet | sì |
| **P6 Prova delle 500 interruzioni del backup** | §5.4 | `core-backup/src/test/.../chaos/**`, `tools/chaos/**` (modi `backup` di `area.ps1` e `s25.ps1`, semina di `mcg-prova` con `adb`), `docs/prova-500.md` (sezione del backup) | P0 (esecutore di riferimento), poi P2, P3 | 2.000 giri JVM verdi sullo stack di riferimento e su quello vero; 600 sul simulatore SMB con le vere `SmbNasFileOps`; runner contro l'area di prova (≥ 400) dietro la guardia; script del telefono con il manifesto da `adb shell sha256sum`; verificatore che gira anche da solo; `ChaosMutationTest` (pubblicare prima della verifica, saltare la rilettura, scrivere il diario dopo la chiamata, temporaneo tolto dalla pulizia: rossi con l'invariante giusto) | sonnet | sì (F30) |
| **P7 Integrazione 5A** | §1.4 per la 5A | `app/**`, `feature-diagnostics/.../catalog/**` ed `export/ExportKeys.kt`, `README.md`, `docs/sicurezza.md` (checklist), `docs/decisioni.md` | P0–P6 | `BackupGraph`, lavori, `BackupJobService` (UIDT, "Ferma"), trigger dopo la lettura del telefono, cancelli (D12, congelamento, emergenza), `CitedTemps` composta, notifiche e riepilogo, Diagnostica (test di catalogo per ogni codice del §2.6), azzeramento; test d'integrazione sul NAS finto e sul telefono finto: foto nuova → backup → `SAFE` in Libreria → un secondo "dispositivo" la vede dalla scansione senza doppioni → spegnimento; D13 solo in debug e assente dal dex di release; `check.ps1` verde; APK debug e release | sonnet | sì |
| **P0-B Fondamenta 5B** | Moduli, contratti e prove guidate della 5B | `settings.gradle`, `build.gradle`, creazione di `feature-integrations`; contratti del §1.2 della 5B (`archiveVersion`/`restoreVersion` con il fake, `VersionKeeper`, `Corrections`, `FreeSpace`, `PhoneConfirmation`, `PendingTasks`, `IncomingUploads`, `EditSessions`, `MetadataWriter`, `LocationStripper`); `core-mediastore` (`deleteRequest`, `writeRequest`, `PhoneMediaRewriter` e fake); manifest e `releaseManifestPolicy` dei componenti di D29 approvati (Q5), `sicurezza.md` §4-bis; test di architettura (`PhoneConfirmation` solo dalla UI, `feature-integrations` cieca a `core-nas` e `core-nasops`); **prove guidate** di T-09, T-14, T-15, T-16 (schermata di debug in Diagnostica → Sviluppo che le esegue su `mcg-prova` e scrive un resoconto di soli numeri) | 5A chiusa | `check.ps1` verde; test di architettura provati reintroducendo le violazioni; nel manifest fuso solo i componenti approvati; resoconto di T-09…T-16 dall'S25 in `docs/android.md` | sonnet | sì (prove T) |
| **P8 Versioni e correzioni** | §2.4, D17–D20, D35 | `core-backup/.../versions/**`, `.../corrections/**`, `.../tasks/**`, `core-nas/.../ops/backup/` (`archiveVersion`, `restoreVersion` in `SmbNasFileOps`), `core-metadata/.../write/**` | P0-B | tutti gli scenari del §2.4 in test, con `FaultPlan` a ogni chiamata e morte del processo a ogni passo; nessuna versione persa né doppia, l'attuale al solito percorso a lavoro finito; `SOURCE_CHANGED` se il PC modifica durante; correzioni JPEG, PNG, WebP rilette e decodificate, file rovinato dalla scrittura scartato con l'originale intatto; nessuna correzione di un media del telefono non al sicuro; franchigia del congelamento per le versioni (D32) | opus | sì (F34–F36) |
| **P9 "Libera spazio" e cancellazioni dal telefono** | §2.5, D21, D22 | `core-backup/.../free/**` (compreso l'elenco delle copie di tutte le versioni per "Ovunque", che P14 lega in `:app`) | P0-B (fake di P8) | il "Fatto quando" di 5.5 (§2.5) con tutte le varianti; nessun elemento non verificato, in caricamento, in errore, congelato o con versioni scoperte nei lotti; verifica oltre le 6 ore o dopo un riavvio rifiutata; lotti ≤ 2.000; "Libera subito" solo con due gesti; con D13 niente fuori da `mcg-prova`; E-B9, E-B14, E-B22, E-D14, E-D15 | opus | sì (F37, F38) |
| **P10 Scegliere foto per le altre app** | §2.7, D23 | `feature-integrations/.../picker/**`, `.../provider/**` | P0-B | selettore e provider con il telefono finto e il NAS finto: nascosti mai (test su entrambe le vie), blocco dell'app, id opachi, E-X1 (scadenza), E-X4, E-X6, E-X7; descrittore a richiesta con letture a intervalli e annullamento; Robolectric per `DocumentsProvider` | sonnet | sì (F39) |
| **P11 Ricevere, "Modifica con…", senza posizione** | §2.7, D24–D26 | `feature-integrations/.../receive/**`, `.../edit/**`, `.../share/**`, `core-metadata/.../strip/**`, `core-prepare/.../video/Mp4Scout.kt` (solo le scatole della posizione) | P0-B (fake di P8) | E-X2 con URI ostili, tipi falsi, tetti; copia immediata; sessioni di modifica (E-X3, E-E3, E-E7) sul telefono finto; posizione tolta e **ricontrollata** su JPEG, PNG, WebP, MP4, MOV e HEIC delle fixture (nessuna coordinata, file ancora decodificabile o riproducibile); permessi del `FileProvider` revocati dopo l'uso | sonnet | sì (F40–F42) |
| **P12 UI della 5B** | §1.5 (stati della 5B) | `feature-backup/.../versions/**`, `.../free/**`, `.../tasks/**`, `feature-viewer/.../menu/BackupActions*`, `feature-settings/.../sharing/**` | P0-B (fake di P8, P9, P11) | Versioni, Libera spazio con l'avviso forte, Da decidere, Correggi, Condividi; `PhoneConfirmationSheet` solo da un tocco, due gesti per "Libera subito"; scudo contro le sovrapposizioni in ogni foglio (R7 della 4B); TalkBack, 1,3x, 48 dp; screenshot `5-P12` | sonnet | sì |
| **P13 Prova delle 500 interruzioni della 5B** | §5.4 per versioni, scambi, guardiano, verifica sul momento | `core-backup/src/test/.../chaos/**` (estensione), `tools/chaos/**`, `docs/prova-500.md` | P0-B, poi P8, P9 | 2.000 giri JVM, 600 sul simulatore, ≥ 400 sull'area di prova con versioni e scambi; invarianti 12–14 del §5.4 | sonnet | sì (F43) |
| **P14 Integrazione 5B** | §1.4 per la 5B | `app/**` (manifest, `AppLockGate` con più activity, navigazione), `feature-diagnostics/**`, `docs/sicurezza.md`, `docs/decisioni.md`, `README.md` | P0-B, P8–P13 | componenti di D29 nel manifest e nella policy, provati con `adb` (`am start` e `content query` da fuori non ottengono niente senza un tocco e senza grant); test d'integrazione: modifica sul telefono → versione → Versioni → Ripristina originale; "Libera spazio" con una copia rovinata; ricezione → "Salva sul NAS"; selettore con un solo-NAS; `check.ps1` verde; APK | sonnet | sì |
| **P15 Copie simili (5A)** | §2.12, D36, E-L10…E-L13 | `core-library/.../similar/**` (rilevatore, firma visiva, scelta della copertina, regole del gruppo), le righe del gruppo `SIMILAR_COPIES` in `MediaCatalog` (dopo P1, con il suo accordo sul perimetro), `feature-viewer/.../info/SimilarCopies*` (striscia, "Mostra questa come principale", "Separa"), i record personali di separazione e di copertina in `core-personal` | P0 (contratti), P1 (tabelle) | tabella "devono unirsi / non devono unirsi" del §2.12 in test con fixture generate (ricompressione, riduzione, EXIF, HEIC→JPEG, raffica, ±0,3 EV, filtri, ritaglio, raddrizzamento, specchiatura, video ricodificati); copertina secondo le quattro regole; nascosto contagioso (test su tutte le superfici che leggono l'indice); "Separa" sincronizzato e mai riformato; un media ambiguo non entra; nessuna lettura del NAS oltre le miniature dei candidati; 100 mila media con 1.000 gruppi: indice della timeline senza `USE TEMP B-TREE` e guardia delle prestazioni invariata | sonnet | sì (F31-bis) |
| **P16 Riordino dei vecchi backup (5B)** | §2.13, D37, E-B23 | `core-nasops/.../plan/reorganize/**` (piano di spostamenti dalla lista proposta), `feature-backup/.../reorganize/**` (proposta, elenco per cartella, foglio di conferma) | P14 chiusa, primo backup vero fatto, risposta a Q14 | solo file propri dell'area privata, fuori da `Backup/` e `Versioni/`, copie di media propri; piano della 4B con `UserConfirmation` dal foglio; prova delle interruzioni della 4B estesa al riordino (2.000 giri JVM, 600 sul simulatore); nessun congelamento per i propri spostamenti; cartelle tolte solo se vuote | sonnet | sì |

> Nota sui crediti: opus per 5 pacchetti su 18, quelli che l'utente ha indicato come fondanti o pericolosi: P0 (i contratti che rendono il backup incapace di distruggere), P2 (caricamento sicuro), P3 ("al sicuro"), P8 (versioni, le uniche rinomine automatiche di media), P9 ("Libera spazio", l'unico flusso che toglie copie dal telefono). Le integrazioni P7 e P14 sono sonnet con brief completi; la revisione di sicurezza di fine metà fa da controllo indipendente.

### 4.5 Chiusura delle due metà
- **5A**: `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro la guardia della Fase 4, anche con un backup in corso (F22); F21–F31 superati; F16 della 4B fatta; screenshot del piede, della pagina e del primo backup approvati; `security-review` con revisione mirata di `core-backup` e `NasBackupOps`; checklist della 5A spuntata (§6.3). D12 resta acceso (Q3 (b)).
- **5B**: prova delle interruzioni della 5B (F43); F34–F45; `security-review` (componenti esportati, conferme, "Libera spazio"); checklist completa; `docs/android.md` aggiornato con T-09, T-14, T-15, T-16; F33 se non già fatta; risposta a Q3 → D12 spento → F32 (primo backup vero), poi P16.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-backup`, `core-rules` | macchine a stati, piano, percorsi, verifiche, guardiano, versioni, "Libera spazio" |
| NAS finto | `InMemoryNasMedia.backupOps` (§1.2) | semantica Samba, guasti prima e dopo, spazio, quota, sola lettura |
| Simulatore SMB | `InMemorySmbBackend` con le vere `SmbNasFileOps` | handle, `FILE_CREATE`, rinomina senza sostituire, fine file, copia lato server |
| Telefono finto | `FakeMediaStore` esteso (§5.2) | righe che cambiano, spariscono, cambiano `_ID`; dialoghi di sistema simulati |
| Robolectric (SDK 35) | Room v4, `ExifInterface`, `DocumentsProvider`, `FileProvider`, Compose | |
| Roborazzi | `feature-backup`, `feature-integrations`, piede, viewer | chiaro e scuro, telefono e tablet, testo a 1,3x |
| Integrazione dal PC | `@NasIntegration` dietro la guardia | caricamento, ripresa, verifica, copia lato server, versioni in `mcg-it/<giro>/` |
| Strumentati su emulatore | `Medium_Phone`, `Pampa_Tablet` | `createWriteRequest`, `createTrashRequest`, `createDeleteRequest` (dialoghi con UiAutomator), `openProxyFileDescriptor`, UIDT; sempre con il NAS finto |
| Dispositivi | S25, Tab S9 | §5.6 |

### 5.2 Il telefono finto (estensione di `FakeMediaStore`, P0)
| Fedeltà | Come |
|---|---|
| Byte | righe con file veri (fixture: JPEG con GPS, HEIC, PNG, WebP, motion photo, MP4 con indice in testa e in coda) e righe **virtuali** (video da GB generati a richiesta da un seme, come il NAS sintetico) |
| Cambi durante la lettura | dopo n byte il contenuto cambia (Samsung "Salva", E-B15) o la riga sparisce (E-B4) |
| Identità | `getVersion` che cambia e `_ID` rinumerati (E-P12) |
| Permessi | `ACCESS_MEDIA_LOCATION` ritirato a metà (E-P21), accesso parziale (E-P1), permessi ritirati (E-P15) |
| Stato | `IS_PENDING`, `IS_TRASHED`, spazio del telefono pieno (E-P3) |
| Dialoghi di sistema | `createWriteRequest`, `createTrashRequest`, `createDeleteRequest` simulati: accetta, rifiuta, accetta solo una parte, app uccisa durante il dialogo |
| Morte del processo | lo stato del telefono finto sopravvive (è il telefono), il resto si ricrea dal diario |

### 5.3 Area di prova e recinzioni
- **Dal PC**: la guardia della Fase 4 (F4-D30, `TestAreaGuard`, `ChaosAreaGuard`), con il backup radicato in `mcg-it/<giro>/`: `Backup/…`, `Versioni/…` e `.mycloudgallery/tmp/` finiscono lì. Chiavi nuove, facoltative, del file delle credenziali: `backup.bigFileMb=3072` (video grande di E-B1), `backup.rounds=400`.
- **Sul telefono**: la recinzione delle share (F4-D33) più quella delle sorgenti e delle azioni (D13); destinazione `mcg-campo/Backup/` di `test`. La semina di `DCIM/mcg-prova/` e `Pictures/mcg-prova/` la fa uno script `adb` (P6) con i file di `tools/fixtures/` e una scansione del MediaStore; la pulizia la fa l'utente dal PC o lo stesso script dietro la guardia.
- **Perché anche D13**: le share di prova hanno password di prova (L25) e `test` è leggibile da `alessio`; le foto vere della famiglia non ci devono finire, e "Libera spazio" non deve toccare foto vere durante una prova.

### 5.4 La prova delle 500 interruzioni del backup (P6, P13)
**Invarianti** (dopo ogni morte, a fine passo e in una rilettura finale completa)
1. **Nessun file a metà visibile**: ogni file fuori da `.mycloudgallery/` nelle radici del backup ha lo SHA-256 di un media del telefono o di un file che c'era prima del giro.
2. **Nessun doppione creato**: il giro non crea mai una seconda copia visibile di byte già presenti nell'area privata, né due copie dello stesso media.
3. **Nessuna sostituzione**: ogni file che c'era prima del giro ha ancora gli stessi byte, lo stesso nome e lo stesso `fileId` (salvo le archiviazioni in `Versioni/` della 5B, che conservano byte e `fileId`).
4. **Nessuna perdita**: nella 5A il telefono non cambia; ogni media che il catalogo dice `SAFE` ha nell'area privata una copia presente con lo SHA-256 della versione.
5. **Hash al 100%**: la rilettura finale non usa nessuna cache.
6. **Temporanei**: a metà solo i propri `<io>-bk-` citati da un elemento non finito; a lavoro finito nessuno.
7. **Catalogo coerente**: `SAFE` se e solo se c'è una copia privata presente e verificata; ogni elemento `DONE` o `LINKED_EXISTING` ha la sua posizione; nessun "al sicuro" per un elemento `FAILED`.
8. **Diario coerente**: ogni transizione è ammessa da `UploadStateMachine`; i nomi finali del diario sono quelli sul NAS.
9. **Il lavoro arriva in fondo**: dopo le riprese ogni elemento è `DONE`, `LINKED_EXISTING` o `GONE`; nessun `FAILED` per un guasto passeggero.
10. **Nessun file fuori posto**: niente scritto fuori dalle radici ammesse.
11. **Il NAS si legge**: una lettura che non riesce non passa in silenzio.
12. (5B) **L'attuale al solito percorso**: a lavoro finito ogni media con versioni ha al solito percorso i byte della versione attuale.
13. (5B) **Versioni conservate**: originale e intermedie presenti in `Versioni/` con il loro SHA-256.
14. (5B) **"Libera spazio" non sbaglia**: la richiesta di sistema simulata contiene solo elementi verificati nel giro e con il timbro ancora uguale.

**Interruzioni**
| Dove | Come | Quante |
|---|---|---|
| JVM, NAS finto e telefono finto | `FaultPlan` (rete prima e dopo, sessione scaduta, stallo), morte del processo, e i colpi del telefono: file che cambia o sparisce a metà lettura, `getVersion` che cambia, NAS pieno a metà, un file con lo stesso nome messo "dal PC" a metà | 2.000 giri a ogni `check.ps1` |
| JVM, simulatore SMB | come sopra, con le vere `SmbNasFileOps` | 600 giri |
| PC, area di prova | runner: caricamenti da file del PC (che fanno da telefono) da 1 MB, 200 MB e `backup.bigFileMb`; connessione chiusa a punti casuali; oggetti ricreati dal diario | ≥ 400 |
| Telefono, area di prova | `s25.ps1 -Mode backup`: durante il primo backup di `mcg-prova`, `kill -9` del processo o Wi-Fi spento per 3–20 s a tempi casuali; poi il verificatore con il manifesto | ≥ 100 |

Operazioni coperte: caricamento di foto e video grandi, deduplica, guardiano, verifica, rotazione, arrivi; nella 5B versioni, scambi, correzione di un solo-NAS, verifica sul momento.

### 5.5 Guardia delle prestazioni
- `check.ps1 -Bench` come nella Fase 4 (guardia sulle misure del 13/10 o sull'ultimo riferimento accettato): il backup aggiunge una voce al piede e un bit ai flag, non colonne all'istantanea.
- Durante il primo backup il catalogo si aggiorna **a lotti** (`attachCopies`, un cambiamento dell'indice per lotto) e lettura, hash e preparazione girano a priorità bassa con l'app in primo piano: lo scorrimento della Libreria con un backup in corso resta dentro la guardia (prova a occhio e con il profilo dei fotogrammi in F22).

### 5.6 Verifica sul campo
**A. Preparazione (utente, circa 30 minuti)**
1. Aggiornare `%USERPROFILE%\.mycloudgallery\nas-it.properties` con le password di prova attuali (al P16 il login è fallito) e lanciare **F16 della 4B** (runner sull'area di prova).
2. Password di prova forti (L19: l'app rifiuta quelle deboli all'onboarding).
3. S25 con la build debug e l'account `test` (area privata `test`, area comune `test-comune`); Tab S9 con il profilo "Prova" e `test2`.
4. Seminare `mcg-prova` sull'S25 con lo script di P6 (300 foto di formati misti, 2 video da 2–3 GB, un HEIC, una motion photo, uno screenshot, una foto con GPS).
5. Per la 5B: WhatsApp, Gmail, Telegram e Google Foto sull'S25 (ci sono); un'app di prova o `adb` per i componenti esportati.
6. Per F32 (dopo Q3): l'APK di release con l'account vero e il telefono in carica per una notte.

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F21 | (M1) Una foto in `DCIM/mcg-prova`, "Fai ora" | file in `test/mcg-campo/Backup/<dispositivo>/AAAA/MM/`; SHA-256 uguale a `adb shell sha256sum` sul telefono (T-11); scudo in Libreria; "Al sicuro sul NAS" nel viewer |
| F22 | Primo backup di `mcg-prova` con "Inizia ora" | riepilogo, notifica, "Ferma" e "Riprendi"; tempo, traffico e batteria entro ±30% della stima; costo per file e scrittura con 1 e 2 flussi misurati; Libreria fluida durante il backup |
| F23 | E-B1: Wi-Fi spento a metà di un video da 3 GB | ripresa dall'ultimo punto (contatore dei byte ricaricati ≤ 64 MiB in Diagnostica) |
| F24 | E-B2: `am kill` durante il caricamento e durante la verifica | dal PC nessun file a metà (solo `.mycloudgallery/tmp`); ripresa |
| F25 | E-B3, E-B16: dal PC un file diverso con lo stesso nome nella destinazione, poi una copia identica | "(2)" per il diverso; l'identico collegato senza traffico (contatore) |
| F26 | E-B4, E-P11: foto cancellata in Samsung Gallery prima del backup, poi ripristinata | "Persa prima del backup" con il suggerimento; dopo il ripristino si ricollega senza ricaricare (T-09, parte a) |
| F27 | E-B6, E-B7: rinomina del dispositivo; un secondo dispositivo con lo stesso account di prova | stessa cartella dopo la rinomina; cartelle separate per i due dispositivi |
| F28 | E-B10, E-R13: hotspot del tablet (dati mobili) con Bilanciato | niente backup in background; "Fai ora" chiede conferma con la stima; il tetto ferma |
| F29 | E-B13, E-B14, Q4: da Cartelle NAS spostare 20 foto proprie in `test-comune`; poi `test2` cancella dal PC le copie comuni | copie private in `Backup/Area comune/` (Q4 a); dopo la cancellazione restano; Libreria "al sicuro" |
| F30 | Prova delle 500 interruzioni del backup | runner dal PC ≥ 400, telefono ≥ 100: invarianti del §5.4 veri |
| F31 | E-R9, E-N13 (facoltativa): quota piccola sull'account `test` dalla dashboard | "Spazio del tuo account esaurito", nessun file a metà; tolta la quota riprende |
| F31-bis | E-B23, E-L10…E-L12: in `mcg-campo/` della share di prova, prima del backup, copie di alcune foto di `mcg-prova` identiche, ricompresse, con EXIF riscritto e convertite da HEIC (preparate dal PC), più una raffica | riepilogo con i tre numeri giusti; le identiche collegate senza caricare; le simili caricate e unite in una cella con la copertina giusta; la raffica intatta; "Separa" sul telefono e il tablet di prova vede la separazione (due celle subito dopo la sincronizzazione dei dati personali, e il confronto non le riunisce) **anche se sul tablet le stesse copie hanno id diversi** (20/10: la voce porta l'impronta di entrambe; il caso capita da solo, a caso, quando l'id del tablet e' il minore); poi "Separa" sul tablet e il telefono vede la separazione |
| F32 | (chiusura 5B, Q3 (b)) APK di release, account vero, D12 spento: primo backup vero dell'S25 su `alessio` | tempo, traffico e batteria entro ±30% del riepilogo; dal PC struttura `Backup/<dispositivo>/AAAA/MM/`, niente toccato fuori da lì |
| F33 | Una settimana con Notte e una con Bilanciato, backup acceso (insieme a F18 della Fase 4) | meno del 2% di batteria al giorno con Notte; stime entro ±30% |
| F34 | (5B) Samsung Gallery: "Salva" su una foto al sicuro, poi "Ripristina originale", poi "Salva copia" | versione al solito percorso e originale in `Versioni/`; scambio; media nuovo (T-16, parte a) |
| F35 | Video modificato e salvato sopra | attività in sospeso; Sì → cestino del NAS; No → resta |
| F36 | Correzioni di data, luogo e rotazione su un JPEG del telefono (dialogo di sistema) e su un solo-NAS; un HEIC | dal PC la versione corretta al solito percorso, l'originale in `Versioni/`; HEIC secondo Q6 |
| F37 | "Libera spazio" su 50 media di `mcg-prova`: dal PC una copia con un byte cambiato e una cancellata | le due escluse; il resto nel cestino di Android; "Libera subito" con due gesti; celle "solo NAS"; una foto liberata si apre (T-09, parte d) |
| F38 | "Elimina solo dal telefono" di un media non al sicuro e di uno con la sola copia comune | avviso e "Prima mettilo al sicuro"; guardiano prima del cestino |
| F39 | Selettore: Gmail (allega), "Documento" di WhatsApp e Telegram; media del telefono e solo-NAS; NAS spento; annullato a metà; blocco dell'app; un nascosto | arrivano; E-X1 subito; E-X4 pulito; sblocco prima; il nascosto non c'è (T-14) |
| F40 | Ricevere da Samsung Gallery, I miei file, Chrome: 100 immagini e un video da 1 GB; un URI `file://`, uno del nostro provider, un `.jpg` falso | salvati nella cartella scelta; i tre rifiutati con il motivo (T-15) |
| F41 | "Modifica con…": Samsung Gallery (telefono), Google Foto (solo NAS); un editor che salva una copia | versioni; la copia ricollegata nella sessione (T-16, parte b) |
| F42 | Condividere con "Rimuovi la posizione": JPEG, HEIC, MP4 | dal PC (`exiftool` o l'estrattore) nessuna posizione nel file ricevuto |
| F43 | Prova delle 500 interruzioni della 5B | invarianti 1–14 veri |
| F44 | APK di release | backup, versioni, "Libera spazio", integrazioni con R8 |
| F45 | F14 esteso: `Backup/` e `Versioni/` di una share di prova aggiunta a Twonky e Plex | l'utente vede se compaiono in TV e decide se escludere `Versioni/` (L43) |

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 5
| Minaccia | Contromisura | Prova |
|---|---|---|
| Un errore del backup che toglie o sovrascrive file (il codice gira da solo ogni notte) | primitive in sola aggiunta (D2), rinomina e creazione senza sostituire, radici ammesse, test di architettura e del compilatore | P0, P2, §5.4 |
| File a metà o copie non integre spacciate per "al sicuro" | temporaneo invisibile, rilettura completa prima del nome definitivo, timbro dopo, "al sicuro" solo da una rilettura fatta qui (D4, D14, L28) | P2, P3, F21–F24 |
| Dati remoti che fanno saltare un caricamento (finto file identico, indice falso) | la deduplica rilegge e confronta byte per byte (D8); l'indice non decide mai (F4-D8) | P3 |
| Ransomware o errore di massa durante il backup o "Libera spazio" | congelamento: tutto fermo (D32, E-B19, E-B22); la rotazione scopre le copie alterate; il cestino di Android resta 30 giorni | P2, P9, F37 |
| Il NAS (o chi lo controlla) che dice di avere byte che non conserva | fuori dal modello (formato §11.4: il NAS è fidato); la rilettura prova ciò che il NAS restituisce; rotazione annuale (L37) | — |
| Copia sul NAS alterata fra la verifica e "Libera spazio" | controllo del timbro subito prima del dialogo; cestino di Android per 30 giorni (L39) | P9, F37 |
| Foto vere della famiglia nelle share di prova; "Libera spazio" su foto vere durante le prove | D12, D13 (solo debug), F4-D33, guardia del PC | P0, P7, F21 |
| Componenti esportati usati da un'altra app (selettore, ricezione, provider) | nessuna azione senza un tocco; provider con `MANAGE_DOCUMENTS` e id opachi; nascosti mai; blocco dell'app; elenco chiuso nel manifest (D29, C17) | P10, P11, P14 |
| File in arrivo ostili (URI verso file dell'app, tipi falsi, file enormi, nomi con percorsi) | copia immediata con tetti, tipo dai byte, nomi locali dall'hash, `ImageBounds` (D24, C18, E-X2) | P11, F40 |
| Un editor esterno che restituisce un file ostile o rovinato | trattato come input non fidato; diventa solo una versione nuova, mai una sostituzione; l'originale resta | P8, P11 |
| Posizione che esce nelle condivisioni | copia senza GPS **ricontrollata** con due lettori; formato non trattabile = condivisione fermata (D26, E-X5) | P11, F42 |
| Riscrittura di un file del telefono (correzioni, "Ripristina originale") | solo con il dialogo di sistema, solo se l'originale è al sicuro, scrittura su un temporaneo riletto prima (D19) | P8, F36 |
| Conferme falsificate o doppi tocchi su "Libera subito" | `PhoneConfirmation` con costruttore `internal`, impronta del piano, 10 minuti, due gesti a 600 ms, scudo contro le sovrapposizioni (D21, R6 e R7 della 4B) | P9, P12 |
| Log, notifiche e Diagnostica | niente nomi, percorsi, share, account, id né impronte; solo numeri e codici | P5, P7, P14 |

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L37. La verifica rilegge ciò che il NAS restituisce subito dopo la scrittura**, che può venire dalla sua cache: prova che il NAS ha ricevuto e conserva quei byte, non che il disco li abbia scritti senza errori. La riverifica a rotazione e il RAID 1 riducono il rischio, non lo tolgono; solo un backup esterno lo toglierebbe.
- **L38. Una foto modificata con Samsung Gallery prima del suo backup** non arriva originale sul NAS: "Salva" sovrascrive il file e Samsung tiene l'originale dove l'app non lo vede. "Proteggi subito le foto nuove" (D34) accorcia la finestra.
- **L39. "Libera spazio" si fida di una verifica di poco prima**: fra la verifica e il cestino di Android un altro (PC, dispositivo compromesso dello stesso account, ransomware) può alterare la copia sul NAS. Restano il cestino di Android (30 giorni) e il congelamento.
- **L40. Correzioni scritte nel file solo per JPEG, PNG e WebP** (Q6): per gli altri formati la correzione resta nell'app e nell'indice, e dal PC si vede il file com'era.
- **L41. Condividere senza posizione** vale per JPEG, PNG, WebP, MP4, MOV e HEIC (convertito in JPEG); per gli altri formati la condivisione senza posizione non si fa.
- **L42. Le integrazioni sono porte d'ingresso**: altre app possono aprire il selettore, il provider e la ricezione; non avviano azioni senza un tocco e non vedono i nascosti, ma un'app che l'utente ha scelto riceve i media che l'utente le dà.
- **L43. Versioni e backup sono file normali**: chi ha accesso alla share li vede e li può cancellare dal PC; se la share è nelle librerie di Twonky o Plex, le versioni precedenti possono comparire in TV.
- **L44. Le copie private dei media spostati nell'area comune** (Q4) occupano spazio una seconda volta; con Q4 (b) un media spostato dalle Cartelle NAS e poi cancellato dall'area comune da un familiare è perso dopo i 30 giorni del cestino, se il telefono non ne aveva una copia.

### 6.3 Checklist della Fase 5 (da aggiungere a `sicurezza.md` §7, oltre alle sei voci esistenti)
- [ ] (5A) `core-backup` non può togliere né sostituire un file: test del compilatore e di architettura, provati reintroducendo la violazione.
- [ ] (5A) Nessun file visibile non verificato; "al sicuro" solo da una rilettura fatta qui (prova delle 500 interruzioni, F21–F24).
- [ ] (5A) D12 attivo alla consegna; D13 solo in debug e assente dal dex di release; **in release un account di prova non carica i media veri del telefono** (R2: il cancello dice `TEST_ONLY` per ogni share).
- [ ] (5A) I temporanei del backup non li toglie la pulizia dei propri file finché il diario li cita.
- [ ] (5A) Deduplica e guardiano non leggono aree private altrui.
- [ ] (5B) "Libera spazio" con copia corrotta, mancante o cambiata dopo la verifica: l'elemento non si libera (F37); l'avviso forte è visibile.
- [ ] (5B) `PhoneConfirmation` solo dalla UI; due gesti per "Libera subito"; scudo in ogni foglio.
- [ ] (5B) Componenti esportati solo quelli approvati; nessuna azione senza tocco (prova con `adb`).
- [ ] (5B) Selettore e provider senza nascosti; blocco dell'app rispettato.
- [ ] (5B) Correzioni e "Ripristina originale" sul telefono solo con il dialogo di sistema e l'originale al sicuro.

### 6.4 Permessi e componenti (proposta per `sicurezza.md` §4-bis, Q5)
| Componente | Esportato | Protezione | Perché | Metà |
|---|---|---|---|---|
| `BackupJobService` | no | `BIND_JOB_SERVICE` | primo backup e "Fai ora" come lavoro avviato dall'utente (UIDT, permesso già approvato) | 5A |
| `PickerActivity` | sì, solo con i filtri `GET_CONTENT` e `PICK` di immagini e video | blocco dell'app; restituisce solo ciò che l'utente tocca | scegliere foto per le altre app | 5B |
| `ShareReceiverActivity` | sì, solo con `SEND` e `SEND_MULTIPLE` di immagini e video | nessuna azione senza "Salva sul NAS" | ricevere condivisioni | 5B |
| `MediaDocumentsProvider` | sì | `MANAGE_DOCUMENTS` (solo il sistema), id opachi | selettori di documenti | 5B |
| `FileProvider` | no | `grantUriPermissions`, percorsi `cacheDir/share/` e `cacheDir/edit/`, permessi revocati dopo l'uso | condividere e "Modifica con…" per i solo-NAS e le copie senza posizione | 5B |
| Permessi Android nuovi | — | — | **nessuno** (niente `MANAGE_MEDIA`, niente accesso a tutti i file, niente servizi in primo piano) | — |

## 7. Rischi e piani B
| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| Le prove sul campo della Fase 4 (F1–F20) e il runner sul NAS vero mai fatti | La 5A scrive su fondamenta non provate sul NAS vero | F16 della 4B prima di M2 (§8); M1 su un solo file | Rimandare M2 finché F16 non passa |
| Dimensioni dei media dell'S25 ignote | Stima del primo backup lontana dal vero | il riepilogo usa le dimensioni vere del MediaStore; F22 misura W, R e il costo per file | Ricalibrare `DeviceMeasures` dopo F22 e F32 |
| Scrittura SMB più lenta del previsto con file piccoli | Primo backup oltre una notte | due file in volo, catalogo a lotti, misura in F22 | Due notti; foto prima, quindi il valore arriva subito |
| Verifica che raddoppia il traffico | Più tempo e più batteria | verifica sul temporaneo, una sola lettura | Nessuno: è il requisito del piano |
| Cache del NAS nella verifica (L37) | Un errore di disco non visto subito | rotazione annuale, RAID 1 | Backup esterno (scelta dell'utente) |
| `reopenTemp` (fine file) non accettato da Samsung o dal WD | Ripresa dei video impossibile | prova sul simulatore e su F23 | Ripresa senza accorciare: si scrive dal punto e si sovrascrive solo il proprio temporaneo; in ultima istanza da zero |
| `OwnFilesCleaner` e diario del backup disallineati | Un temporaneo tolto a metà | porta `CitedTemps`, test di P0 | La ripresa lo vede come assente e ricomincia (nessun danno, solo traffico) |
| VerifyWorker e `FULL_HASH` mancanti dalla Fase 4 | "Al sicuro" mai visto finora | P3 li costruisce | — |
| Samsung "Salva" prima del backup (L38) | Originale perso per il NAS | D34 (Q7) | Dichiarato |
| T-09, T-14, T-15, T-16 diversi dal previsto | Integrazioni o "Libera spazio" da rifare | prove guidate in P0-B, prima di P8–P11 | Ripiego: scaricare in cache prima (selettore), copia dei flussi (ricezione), solo `ACTION_EDIT` per i solo-NAS |
| `createTrashRequest` che non compare nel cestino di Samsung Gallery | L'utente non trova i media liberati | "Eliminati di recente" dell'app li mostra (Fase 3) | Testo che lo spiega |
| Limite di 2.000 URI per dialogo | Più dialoghi per un telefono intero | lotti, avanzamento | — |
| UIDT fermato dal sistema (termica, memoria) | Primo backup a metà | ripresa dal diario come lavoro normale | "Stanotte" |
| `AppLockGate` con più activity d'ingresso | Blocco aggirabile dal selettore | P14 lo estende e lo prova | Selettore che chiede sempre lo sblocco con il blocco attivo |
| Processo del provider in secondo piano senza rete | Un solo-NAS non arriva all'altra app | il provider serve un client visibile (importanza elevata); scadenze | Scaricamento in cache prima di restituire |
| `ExifInterface` che rovina un file in scrittura | Versione illeggibile | scrittura su copia, rilettura e decodifica prima di valere; originale intatto | Correzione solo nell'app per quel file |
| Versioni viste come media nuovi dagli altri dispositivi finché F4-D31 è acceso | Doppioni sul tablet o per un altro account | record con `parent` e `vkind` quando si pubblica; scansione che riconosce l'archiviazione per `fileId` | Accettato fino all'accensione dell'indice sulle share vere |
| Congelamento scatenato dalle versioni | Falso allarme | franchigia dal diario del backup (D32) | Gesto "Ho controllato" |
| Migrazione v4 su una libreria vera | Dati persi | `ExportedSchemaTest` 1→4, 2→4, 3→4, mai distruttiva | — |
| Conflitti con il filone grafico (piede, menu del viewer) | Merge rotti | §1.5, voci come dati, un solo pacchetto per file conteso | L'orchestratore serializza |
| Crediti | Costo alto | opus su 5 pacchetti su 18 | P3 su sonnet con una revisione opus del risultato |
| Modifiche della 4B non committate | P0 parte su un albero sporco | commit prima di P0 | — |

### File cruciali
- `core-nas/.../ops/NasFileOps.kt`, `SmbNasFileOps.kt` (`openTemp`, `SmbTempWriter`, `publishTemp`, `copyToTemp`), `NasOpsPaths.kt`, `fs/ShareTools.kt` (`SmbNasHousekeeping`), `testing/InMemoryNasMedia.kt`, `testing/InMemorySmbBackend.kt`: le primitive che la vista in sola aggiunta riusa.
- `core-nasops/.../contract/Diary.kt` (`OpStateMachine`, il modello del diario), `contract/Confirmation.kt` (il modello di `PhoneConfirmation`), `clean/OwnFilesCleaner.kt` (`citedTemps`, D33), `engine/OpPlanner.kt` ("Ovunque").
- `core-model/.../domain/SafetyRules.kt` (`evaluate`, `canFreePhoneCopy`), `VersionRules.kt`, `LinkingRules.kt`, `CellFlags.kt`.
- `core-database/.../MediaCatalog.kt` (`attachCopy`, `markVerified`, `correct`, `restoreOriginal`, `requestWork`), `GalleryMigrations.kt`.
- `core-prepare/.../contract/Preparation.kt` (`PrepareSource`, `PrepBudget`), `video/Mp4Scout.kt`.
- `core-index/.../contract/Publishing.kt` (`IndexPublisher`, `IndexWriteGate`).
- `core-mediastore/.../android/AndroidMediaStoreSource.kt` (`setRequireOriginal`), `AndroidPhoneMediaIntents.kt`, `contract/Actions.kt`, `testing/FakeMediaStore.kt`.
- `core-rules/.../run/StagedRun.kt`, `estimate/BacklogSource.kt`, `estimate/ConsumptionEstimator.kt` (`DeviceMeasures`).
- `app/.../nas/NasGraph.kt`, `nas/NasOrganizer.kt`, `work/NasWork.kt`, `work/NasOpsJobService.kt`, `notify/NasDailySummary.kt`, `AndroidManifest.xml`, `gradle/verify-release-manifest.gradle`.

## 8. Cose che richiedono l'utente
| Cosa | Perché | Nel frattempo |
|---|---|---|
| Aggiornare il file delle credenziali di prova e lanciare F16 della 4B | Il runner sul NAS vero non è mai partito; la 5A ci si appoggia | NAS finto e simulatore |
| Password di prova forti | L'app rifiuta quelle deboli (L19) | Solo prove dal PC |
| Seminare `mcg-prova` sull'S25 (script di P6) | M1, M2, F21–F31 senza foto vere (D13) | Telefono finto |
| Profilo "Prova" del tablet con `test2` (c'è dalla Fase 4) e un secondo dispositivo con `test` per F27 | E-B6 sul campo | Due dispositivi simulati |
| Committare le modifiche aperte della 4B (R11) | P0 tocca `core-nasops` | — |
| Rispondere alle domande del §9 | Cambiano P2–P5 e P8–P12 | Valgono le raccomandazioni |
| Approvare i componenti nuovi (Q5) | Policy di sicurezza | 5A senza integrazioni |
| Consenso al primo backup vero (Q3) e una notte con l'S25 in carica | F32, il "Fatto quando" di 5.1 | Backup solo sulle share di prova (D12) |
| Revisione degli screenshot (piede, pagina Backup, primo backup; nella 5B Versioni, "Libera spazio") | Gate grafico | Generati in anticipo |
| Due settimane con Notte e Bilanciato con il backup acceso (con F18 della Fase 4) | F33 | Stime dalle ipotesi del §2.9 |
| Facoltativo: escludere `Versioni/` da Twonky e Plex se una share con versioni è nelle loro librerie | L43, F45 | — |

## 9. Domande per l'utente
| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | Dividere la fase in due metà? | (a) **5A** backup, "al sicuro", stato (solo file nuovi sul NAS, niente tolto dal telefono); **5B** versioni, "Libera spazio", integrazioni. (b) Una fase sola | **(a)**: il backup arriva prima ed è la parte che conta; ciò che toglie o sposta arriva su un backup già provato e rivisto |
| Q2 | Dove conservare sul NAS le versioni precedenti (punto aperto del piano, 5.4)? | (a) Una cartella **`Versioni/` alla radice di ogni share** che rispecchia i percorsi ("Versioni/Backup/S25 di Mario/2026/10/IMG_1234 (originale).jpg"). (b) Una cartella `Versioni` dentro ogni cartella con file modificati. (c) Nascoste dentro `.mycloudgallery/versioni/` | **(a)**: sfogliabile dal PC, un solo posto da documentare e da escludere in Plex e Twonky; la (c) non si vede dal PC senza "file nascosti", la (b) sparge cartelle ovunque |
| Q3 | Quando il primo backup vero dell'S25 su `alessio`? | (a) A fine 5A, dopo la prova delle 500 interruzioni e la revisione, con l'APK di release e il tuo consenso (D12 si spegne). (b) Solo a fine 5B. (c) Subito a M2 | **(a)**: è la prova che chiude il "Fatto quando" di 5.1 (stima entro ±30% su un backup reale), e da quel momento il telefono è davvero protetto |
| Q4 | I tuoi media spostati nell'area comune (Cartelle NAS ora, album condivisi dalla Fase 7): tenere sempre una copia privata? | (a) **Sì, subito**: dopo lo spostamento l'app crea in silenzio una copia privata in `Backup/Area comune/` con la copia lato server (spazio doppio). (b) Solo quando sparisce la copia del telefono (lettera del piano 5.3): un media spostato senza copia sul telefono resta protetto solo dai 30 giorni del cestino | **(a)**: è l'unico modo per cui "in nessuno scenario un media resta senza una copia sotto il tuo controllo", anche se un familiare cancella la copia comune dopo un mese |
| Q5 | Approvi i componenti nuovi (§6.4)? | (a) Sì: `BackupJobService` nella 5A; `PickerActivity`, `ShareReceiverActivity`, `MediaDocumentsProvider`, `FileProvider` nella 5B. (b) Solo il servizio della 5A, le integrazioni dopo. (c) No | **(a)**: nessun permesso nuovo; ogni componente esportato non agisce senza un tuo tocco; senza, la 5.7 non si può fare |
| Q6 | Correzioni (data, luogo, rotazione) su HEIC, RAW e video | (a) **Solo nell'app** (e nell'indice) per questi formati, file intatto, dichiarato; nel file solo per JPEG, PNG e WebP. (b) Uno scrittore nostro per MP4 e HEIF (lavoro e rischio di file rovinati, sempre con l'originale conservato). (c) Una libreria esterna (dipendenza nuova) | **(a)** per la Fase 5: sull'S25 i formati scrivibili sono quasi tutto; la (b) si può aggiungere più avanti |
| Q7 | "Proteggi subito le foto nuove" (D34) anche con il preset Notte? | (a) Sì: le foto appena scattate vanno sul NAS appena c'è il Wi-Fi di casa e la batteria è sopra la soglia, anche di giorno (pochi MB, mai video, mai dati mobili). (b) No, con Notte tutto solo di notte | **(a)**: protegge l'originale se modifichi la foto in Samsung Gallery prima della notte (L38); costa pochi secondi per foto |
| Q8 | Screenshot e cartelle aggiuntive sul NAS | (a) In sottocartelle col loro nome: `Backup/<dispositivo>/Screenshot/AAAA/MM/`, `…/WhatsApp/AAAA/MM/`. (b) Gli screenshot insieme alle foto della fotocamera in `AAAA/MM/`, le altre in sottocartelle | **(a)**: dal PC le foto della fotocamera restano pulite; la Libreria li mostra comunque insieme |
| Q9 | "Libera spazio": cosa propone di default? | (a) Tutto ciò che è al sicuro **tranne gli ultimi 30 giorni**, con i filtri "solo video" e "più vecchi di un anno". (b) Solo i video. (c) Solo i più vecchi di un anno | **(a)**: le foto recenti si guardano di più e restano sul telefono; i filtri coprono gli altri casi |
| Q10 | Riverifica a rotazione delle copie private | (a) Sì, ogni copia riletta almeno una volta all'anno (~140 MB a notte, una decina di secondi). (b) No, solo la verifica al caricamento e quella sul momento | **(a)**: senza backup esterno è l'unico modo di accorgersi di una copia rovinata finché c'è ancora quella del telefono |

### 9.1 Risposte dell'utente (15/10)
| # | Risposta | Effetto |
|---|---|---|
| Q1 | (a) **5A** e poi **5B** | Divisione confermata |
| Q2 | (a) Cartella **`Versioni/` alla radice di ogni share** | Punto aperto 5.4 chiuso |
| Q3 | **(b) Primo backup vero su `alessio` solo a fine 5B** | D12 resta acceso fino alla fine della 5B; la stima ±30% (5.1) si misura allora |
| Q4 | **(b) Copia privata solo quando sparisce quella del telefono** (lettera del piano 5.3) | Niente copia privata subito dopo lo spostamento nell'area comune; resta la protezione dei 30 giorni del cestino, da dichiarare come limite |
| Q5 | (a) **Sì a tutti i componenti** (`BackupJobService`; `PickerActivity`, `ShareReceiverActivity`, `MediaDocumentsProvider`, `FileProvider`) | §4-bis scritto con P0; componenti e policy del manifest in P7 (5A) e P14 (5B) |
| Q6 | Non chiesta: vale (a) (correzioni nel file solo per JPEG, PNG e WebP) | — |
| Q7 | (a) **Sì**, "Proteggi subito le foto nuove" anche con Notte | D34 confermata |
| Q8 | Non chiesta: vale (a) (sottocartelle per screenshot e cartelle aggiuntive) | — |
| Q9 | (a) "Libera spazio" propone tutto il sicuro **tranne gli ultimi 30 giorni**, con i filtri | — |
| Q10 | (a) **Riverifica** a rotazione, ogni copia almeno una volta l'anno | — |

**16/10** (domande del §2.11)
| # | Risposta | Effetto |
|---|---|---|
| Q11 | **Nuova opzione**: le copie quasi uguali si caricano sempre, ma si uniscono in una cella come in Google Foto e Samsung Gallery; tutte le copie si possono vedere, c'è una funzione per separarle, e di default si mostra quella a risoluzione maggiore o compressione minore | D36, §2.12, P15 |
| Q12 | Nell'area privata dell'utente; fatti con l'app di WD e con un'altra app o un cloud | Ci si aspettano copie identiche e simili; numeri dalla scansione F2 |
| Q13 | (b) **Riordino proposto dopo**, con conferma | D37, §2.13, P16 (dopo il primo backup vero, Q14 allora) |

<!-- fine del documento -->

