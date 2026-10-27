# Revisione di sicurezza della Fase 7 (album, album smart, viaggi, condivisione, Utility e Doppioni, Nascosti, Raccolte)

| | |
|---|---|
| **Oggetto** | i commit di `git log --first-parent 9dc47f67..870a723b` della Fase 7: P0-P13 con le integrazioni, la correzione SD-1-SD-4 di P4 (`cf173b4d`, `e2d77ca0`), le correzioni trasversali "File di stato locali" (`4174b830`) e "test a tempo" (`e457ee28`). In particolare: `core-albums` (`shared/`, `share/`, `personal/`, `smart/`, `trips/`), `core-trust` (`AlbumDocs`, versione 3, classe 17), `core-database` (schema v6, `Migration5To6`, `AlbumCatalog`, `AlbumQueries`, `MediaCatalog.attachMoved`), `core-nasops` (porta di completamento `MoveCompletionSink`, `completionPortOf`), `core-backup` (`AlbumFolderUploads`, `GuardianSafeCopyRequests`, cammino `INCOMING` con la riga del telefono), `core-library` (`visibility/`, `duplicates/`), `core-common` (`StateFiles`, `FeatureFlags`), `feature-albums`, `feature-library` (Utility, Doppioni, Eliminati di recente, Raccolte, selettore), `feature-viewer` (menu degli album), `feature-integrations` ("Aggiungi ad album"), `feature-nas` (nascosti in Cartelle NAS), `:app` (`albums/`, `utility/`, `di/AlbumsModule`, `CollectionsModule`, `UtilityModule`, `work/`, `graph/LocalData`, `nas/NasOrganizer`, Diagnostica), `tools/check.ps1`. **Esclusa** la Fase 8 (`core-faces`, `feature-people`, asset dei volti, P0, P1, P5 e P6 della Fase 8): avra' una revisione sua. |
| **Riferimenti** | `docs/fase7-progetto.md` (§0.1 D1-D39, §0.2, §2, §6, §11 Q5), `docs/decisioni.md` (sezioni "Fase 7 ..." da "decisioni del progetto" a "P13", e "File di stato locali"), `docs/sicurezza.md` (C9, C10, C24-C35, L57-L109, checklist "Fase 7"), `docs/formato-nas.md` §5.8, `docs/review/6-fase6.md` (formato). |
| **Attaccanti e guasti** | (1) un **familiare compromesso** (un dispositivo approvato dello stesso account o di un altro) che scrive definizioni d'album, contributi, lapidi, livelli, chiusure o eredita' false o ostili; (2) i **permessi aggirati dal PC** (SMB sull'area comune); (3) i **Nascosti** che escono da una superficie; (4) lo **spostamento in condivisione**, l'unica parte che toglie file (conferme, diario, porta di completamento, guardiano, "Riporta", Q5); (5) la **pulizia dei Doppioni** (cancellazioni); (6) la **privacy della casa** (coordinate, log, titoli, `trip_dismissed`); (7) lo **storage locale e l'azzeramento** (registri su Room e su file, `StateFiles`); (8) la **recinzione D30** e F4-D31 nella release; (9) i **guasti**: morte del processo e rete. |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice. Niente Gradle, nessun telefono, nessun `adb`, nessun NAS, nessuna rete. |
| **Data** | 27/10/2026 |
| **Metodo** | Lettura del codice nel worktree staccato a `870a723b`, seguendo ogni via che porta dati da un altro dispositivo o dal NAS fino a cio' che l'utente vede o a un'azione sui file, e ogni file che l'app scrive o toglie. Tre revisori di supporto, in sola lettura, hanno letto in parallelo: (1) formato firmato, replica, fusione e permessi degli album condivisi; (2) Nascosti, Doppioni, casa e viaggi, album smart; (3) `StateFiles`, registri durevoli, azzeramento, schema v6, recinzioni, `check.ps1`, test a tempo, Diagnostica. Il revisore principale ha letto lo spostamento in condivisione (orchestratore, passi, porte di `:app`, porta di completamento in `core-nasops`, `attachMoved`, guardiano, conferme) e le correzioni SD-1-SD-4. **Ogni voce riportata qui e' stata ricontrollata nel codice**; per ognuna si dice se e' confermata (dal codice) o plausibile (dipende da un comportamento del NAS, della piattaforma o da un attacco che qui non si prova). Nessun test eseguito. Nessun file del repository modificato a parte questo documento. |
| **Stato** | **Chiusa nel codice il 27/10** con i pacchetti di correzione A (`fase7/rev-a`), B (`fase7/rev-b`), C (`fase7/rev-c`), D (`fase7/rev-d`) e E (`fase7/rev-e`: i collegamenti fra i pacchetti, i due confini A/B e questi documenti), uniti nei rami d'integrazione `integra/27-10a` e `integra/27-10b`: ogni voce ha una correzione con un test, oppure e' motivata come "non un difetto" (I9, I12), oppure resta **aperta** con il suo limite o la decisione che serve (R3 in parte, R19 in parte, I6, I8 in parte). Lo stato di ogni voce e' nella sezione "Stato di ogni voce" qui sotto; le sezioni "Fase 7, correzioni dopo le revisioni: pacchetto A/B/C/D/E" di `docs/decisioni.md` hanno le decisioni. Restano da provare sul telefono (F72-F93). |

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 1 | R1 un album chiuso con "ognuno tiene una copia" fa scrivere copie personali a ogni familiare, anche di album mai visti attivi, e le copie restano dopo la revoca |
| Media | 5 | R2 un contributo rotto (segmento assente, file illeggibile) blocca la sincronizzazione degli album di tutta la famiglia e fa riscaricare fino a 64 MiB per giro, in ciclo · R3 le righe "Eliminare il file" e "Nuova versione" della tabella dei permessi non le applica nessuno: viewer, Cartelle NAS e Doppioni mandano nel cestino il file di un elemento altrui a ogni livello · R4 "Spazio" e "Cestino" di Cartelle NAS mostrano nome e cartella dei file nascosti, senza cancello · R5 Doppioni identici: alla conferma il piano non ricontrolla la copia da tenere, e la regola "mai l'ultima copia privata" si rompe con una corsa · R6 dismettere un dispositivo fa sparire per tutti i suoi contributi agli album, definizione compresa (nessuna testa congelata), e l'eredita' cade con il dispositivo admin che l'ha scritta |
| Bassa | 16 | R7 la difesa contro le voci retrodatate (L59) si aggira dichiarando un `createdAt` vecchio · R8 la porta di completamento si salta se la prima occhiata alla destinazione fallisce per un errore che non e' di rete · R9 i registri durevoli degli album valgono "vuoti" se illeggibili e la prima modifica li sovrascrive (e SD-2 non distingue "non so") · R10 l'azzeramento di un dispositivo perso salta la cancellazione dei dati locali se lo stato fidato non si toglie · R11 `StateFiles`: altri punti che restano aperti dopo un errore (franchigia, lettura del testo, emergenza, istantanea, temporanei) · R12 il pubblicatore ricostruito dopo la perdita del database si fida di cio' che trova sul NAS · R13 replica degli album: tetti dopo il download, cache intera in memoria, mai potata · R14 chi legge accetta la riscrittura dell'elemento di un altro con un contenuto diverso · R15 "Usa la nuova cartella" e "Ripristina" possono far cadere l'app · R16 coordinate vicine a casa nei dati personali (album smart della propria citta', viaggi) e titoli dei viaggi fissati prima di un "nascondi" · R17 "fallire chiusi" diventa "vuoto": un errore di lettura svuota album smart e proposte di viaggio · R18 Doppioni: la prova del tocco si fabbrica, le copie del telefono non hanno il timbro, "Rivedi" puo' scartare l'unica copia verificata · R19 nessun tetto al lavoro che i dati personali di un dispositivo dello stesso account impongono agli altri · R20 il passo D30 di `check.ps1` non prova che il cancello usi la recinzione nella release · R21 `linkCopyAsVersion` non porta con se' i riferimenti delle tabelle v6 · R22 la sezione Album della Diagnostica esporta solo `mounted` |
| Informativa | 12 | I1-I12 (sotto) |

**Confermate e plausibili.** Delle 22 voci, **18 sono confermate** nel codice e **4 sono plausibili**: R8 (dipende da quali errori SMB arrivano a una `stat`), R13 (memoria: una stima), R15 (dipende dall'ambito delle coroutine della pagina), R19 (l'attacco). In cinque voci confermate una parte e' plausibile e lo si dice: R1 (il peso sul tetto dei dati personali), R2 (la variante con l'errore di I/O), R4 (il Cestino), R11 (il battito ricreato), R16 (il centro del cerchio di un viaggio). R1, R2 e R3 le hanno trovate sia il revisore principale sia quello degli album condivisi.

**Lo spostamento in condivisione regge.** Le tre chiavi ci sono (conferma del foglio dal tocco con 600 ms e 10 minuti, conferma di `core-nasops` per **quel** piano, diario prima di ogni passo), la porta di completamento e' una sola e vincolante, la sorgente va nel cestino solo dopo la verifica e dopo che il catalogo collega la destinazione allo **stesso** `MediaId`, nessuna voce `item:` nasce per un file che il catalogo non vede nell'area comune, e nessun dato letto dal NAS fa partire uno spostamento, un caricamento, un cestino o uno scaricamento (risposta 4). Le correzioni SD-1-SD-4 sono alla radice. I punti deboli sono ai margini: una via plausibile che salta la porta (R8), i registri su file che valgono "vuoti" quando non si leggono (R9) e operazioni che possono restare tenute senza fine con un motivo muto (I2).

**I dati remoti non muovono file, ma scrivono nei dati personali.** La sola via trovata da un dato letto dal NAS a una scrittura sul dispositivo di chi legge, senza un tocco, sono le copie personali di Q5 (b) (R1): un dispositivo compromesso le fa scrivere a tutta la famiglia, firmate dalle vittime, e la revoca non le toglie. Il resto del formato regge (codec ostile, firme, id legato all'account, anti-rollback, tetti in voci); cede nel **volume** e nell'**isolamento** del giro (R2, R13) e nella **retrodatazione** (R7).

**La tabella dei permessi e' davvero una sola**, con un buco: le due righe che solo lo scrittore puo' applicare non le applica nessuno (R3). Il lettore e' un po' piu' largo dello scrittore in un caso (R14).

**Nascosti: la fonte unica regge dove e' usata.** `VisibleMedia` e `HiddenMask` falliscono chiusi e passano su album, smart, viaggi, Doppioni, widget, selettore e "Aggiungi ad album". Escono dai nomi di una pagina che il registro non vede (R4) e dai titoli dei viaggi gia' calcolati (R16).

## Stato di ogni voce (27/10, revisione E)

**Corretta**: la correzione e i suoi test (pacchetto fra parentesi: A fiducia e replica, B flusso della condivisione, C Doppioni e file di stato, D smart, viaggi, Raccolte e Diagnostica, E collegamenti e confini). **Non un difetto**: il motivo. **Aperta**: il limite in `docs/sicurezza.md` o la decisione che serve. Le sigle dei limiti (L...) sono in `docs/sicurezza.md` §6.

| Voce | Stato | Pacchetto, prove, limiti |
|---|---|---|
| R1 | Corretta (A, E) | Copie solo per album visti attivi, una volta, chiusura definitiva, tetti, ritiro alla revoca (`SharedAlbumClosedCopiesTest`, `PersonalAlbumCopiesTest.r1...`); E: il creatore tiene la copia del suo album anche senza il segno e la copia non si ritira con il telefono che ha chiuso (`SharedAlbumCreatorCopyTest`, `RevisioneEConfiniAppTest.sc1...`). L140 |
| R2 | Corretta (A) | Contributi in attesa, errori di file e di sessione, cursore, byte contati, tetti prima di scaricare, segmenti rimessi dal proprietario (`SharedAlbumRobustnessTest`). L141 |
| R3 | Corretta (B), **aperta in parte** | La tabella e' applicata nel piano di `core-nasops` (`ProtectedFiles`): cestino, spostamento e rinomina lasciano fuori i file protetti, anche da Cartelle NAS e dai Doppioni (`AlbumFileRulesTest`, `RevisioneBNasOpsTest`, `RevisioneBCondivisioneAppTest.r3...`). "Nuova versione": non un difetto (nessuna strada scrive versioni nell'area comune). Aperta: Cartelle NAS e le scelte "Ovunque" dei Doppioni simili offrono ancora l'azione e il piano risponde con il motivo, non prima del tocco (L152) |
| R4 | Corretta (C) | Spazio senza i nascosti e fallendo chiuso; il Cestino di Cartelle NAS apre gli Eliminati di recente dietro il cancello; due righe nuove nel registro dei Nascosti (`NasTrashGateTest`, `HiddenSurfacesRegistryTest`) |
| R5 | Corretta (C) | Il piano si rifa al foglio e di nuovo al tocco, con l'intersezione (`CatalogIdenticalRevisionCTest.r5...`) |
| R6 | Corretta (A) | Le teste d'album si congelano alla dismissione e l'erede segue l'account admin (`RetireFrozenHeadsTest`, `SharedAlbumRetireTest`). L142 |
| R7 | Corretta (A) | Le ore di chi legge: `seenAt`, teste e voci retrodatate rifiutate (`SharedAlbumRetrodatingTest`). L59 riscritto |
| R8 | Corretta (B) | Un errore che non e' di rete tiene l'elemento (`CATALOG_PENDING`), mai "avanti senza porta" (`RevisioneBNasOpsTest.r8...`) |
| R9 | Corretta (A, E) | "Non so" e non "vuoto", mai sovrascritti; potatura (`AlbumDurableStoresTest`). E: il conteggio arriva in Diagnostica (`RevisioneEDiagnosticaAppTest`). L143 |
| R10 | Corretta (C) | Nessun passo dell'azzeramento ne ferma un altro, lo stato fidato per ultimo (`FileStateClearTest`) |
| R11 | Corretta (C, E) | Franchigia, lettura del testo, emergenza, istantanea, battito e `StateFiles` (`StateOpenPointsTest`, `StateFilesTest`); E: l'emergenza non salvata e' una riga della Diagnostica del NAS (`RevisioneEDiagnosticaAppTest`). L163, L164 |
| R12 | Corretta (A) | Il pubblicatore ricostruito preferisce la copia della replica e non adotta un contributo che non si rilegge tutto (`NasAlbumPublisherAdoptionTest`). L144 |
| R13 | Corretta (A) | Tetti prima di scaricare, cache limitata, conti per dispositivo (`SharedAlbumRobustnessTest`, `RoomAlbumStoresTest`) |
| R14 | Corretta (A) | Su un elemento altrui una voce nuova cambia solo la posizione (`AlbumPermissionsLogicTest.r14...`) |
| R15 | Corretta (B) | La cartella proposta si valida con le regole del formato; un errore e' `Invalid` (`AlbumFolderWatchTest.r15...`, `RevisioneBAlbumPageTest.r15...`) |
| R16 | Corretta (D) | Il centro dei luoghi su una griglia di 0,1 gradi e senza le foto di casa; titoli e luoghi dei viaggi ricalcolati (`TripsReviewTest`, `SmartReviewTest`). L171 |
| R17 | Corretta (D) | `visibleOrThrow` e `hiddenAmongOrThrow`: un errore non svuota (`SmartFailClosedTest`) |
| R18 | Corretta (C) | La prova del tocco nasce solo nel composable, timbro sulle copie del telefono, "mai l'ultima copia verificata" (`TrashTapGuardTest`, `UtilityRevisionCTest`). L162 |
| R19 | Corretta in parte (A), **aperta in parte** | Tetti sui dati personali di un altro dispositivo: album, riferimenti, ricerca a blocchi (`AlbumProjectionWorkCapTest`, L146). Aperta: gli album smart con descrizione o foto d'esempio non hanno un tetto, serve una scelta dell'utente (proposta di D in `docs/decisioni.md`) |
| R20 | Corretta (A) | Il passo D30 di `check.ps1` legge il bytecode del cancello nella release; provato con un cancello rotto apposta |
| R21 | Corretta (A) | `repointMediaReferences` e' l'unica strada (`MergeV6Test`) |
| R22 | Corretta (D, E) | D: l'esportazione ha tutte le chiavi (`AlbumsDiagnosticsExportTest`); E: i codici preparati sono collegati e i rifiuti si contano per motivo (`RevisioneEDiagnosticaAppTest`, `SharedAlbumRejectionReasonsTest`). L174 |
| I1 | Corretta (A) | L'hash completo nella voce e nell'attestazione E-SH12 (`ShareFullHashTest`) |
| I2 | Corretta (B, E) | Pausa `CATALOG` con il suo motivo e un tetto di 24 ore, trasferimenti da al piu' 10.000 elementi (`RevisioneBNasOpsTest.i2...`, `RevisioneBShareTest.i2...`); E: la pausa e' una riga della Diagnostica (`SHARE_PAUSED_CATALOG`) |
| I3 | Corretta (B) | La seconda chiave legata al diario (`RevisioneBCondivisioneAppTest.i3...`, `Phase7ArchitectureTest`). C71 |
| I4 | Corretta (B) | Il caricamento verso una cartella adottata fuori da "Album condivisi" e' escluso dal piano con il motivo (`RevisioneBShareTest.sg1...`). L153 |
| I5 | Corretta (A, E) | Una riga del diario illeggibile resta un elemento fermo, in viaggio senza biglietto (`AlbumDurableStoresTest.i5...`); E: il confine con SH-2 e' provato sul diario vero (`RevisioneEConfiniAppTest.sh2...`) |
| I6 | **Aperta** | La pulizia delle proprie teste e dei propri segmenti d'album toglie file sul NAS: per D1 e C10 la fa solo `core-nasops`, che non conosce `albums/`, e serve un lettore che ritrovi la serie oltre le teste tolte. Limite L145; decisione: se farla in una fase di manutenzione o accettare la crescita (una testa ogni 5 minuti per album e dispositivo) |
| I7 | Corretta (D) | `toString()` senza dati sulle righe v6 (`AlbumRowsToStringTest`) |
| I8 | **Aperta in parte** | Prima meta' (`trip_dismissed` e' uno SHA-256 senza segreto): non un difetto, e' il limite L87 (L170 aggiunge la seconda impronta). Seconda meta' (la query dei Doppioni non confronta l'account, `OTHERS` vuol dire "area sconosciuta"): aperta, oggi la garantiscono il catalogo (solo share dell'account) e `NasOperations` in sola lettura; il controllo esplicito vuole un parametro nuovo in `AlbumCatalog.duplicates` |
| I9 | Non un difetto | Nessuna recinzione per Utility e Doppioni: mandano solo nei cestini (30 giorni) e non spostano nell'area comune; decisione di C. L161 |
| I10 | Corretta (C) | `StateFiles`: segno di interruzione, ritentativo solo su Windows, tetto di lettura (`StateFilesTest`) |
| I11 | Corretta (D) | `Mount`: lo stop aspetta il montaggio in corso (`MountTest`) |
| I12 | Non un difetto | Informativa sui test a tempo (`e457ee28`): nessun codice di produzione cambiato; `NoFixedWaitsTest` continua a vietare le attese scritte a mano |

**Conteggio.** Delle 34 voci (R1-R22, I1-I12): **28 corrette**, **2 non un difetto** (I9, I12), **4 aperte in tutto o in parte** (R3, R19, I6, I8). Nessuna delle quattro aperte e' un difetto di sicurezza ancora vivo: R3 e' protetta dal piano e manca solo di dirlo prima del tocco, R19 ha i tetti e manca solo quello degli smart con descrizione, I6 e' la crescita lenta di file del NAS, I8 e' un controllo in piu'. Le decisioni che servono all'utente sono in "Fase 7, correzioni dopo le revisioni: pacchetto E" di `docs/decisioni.md`.

---

## I tre giudizi richiesti

### 1. La porta di completamento unica e vincolante (P4), cioe' il cambio di comportamento del campo A: **regge**, con un buco plausibile e un costo da dichiarare meglio

- **Cosa fa.** `NasOpsEngine` chiama **solo** `MoveCompletionSink`, per ogni file spostato tra share, dopo `PUBLISHED` e prima di `SOURCE_RECORDED`, a ogni ripresa, con il timbro della destinazione letto adesso e l'impronta verificata (`core-nasops/.../engine/NasOpsEngine.kt:780-787`, `:802-821`). `completionPortOf` impedisce di legare due porte (`engine/EnginePorts.kt:136-151`, anche nel costruttore di `NasOpsConfig`, `wiring/NasOpsWiring.kt:82-85`). In `:app` la porta e' una sola, `CatalogMoveCompletionSink`, legata in `NasOrganizer` per **tutti** gli spostamenti tra share, non solo per gli album (`app/.../nas/NasOrganizer.kt:384`, `app/.../albums/CatalogAlbumPorts.kt:120-139`).
- **Il catalogo.** `MediaCatalog.attachMoved` (`core-database/.../MediaCatalog.kt:815-864`) sposta **la stessa riga** della posizione al posto nuovo, in una transazione, idempotente; rifiuta una destinazione di un altro media e byte che non tornano con la versione (dimensione, hash completo se noto). Preferiti, nascosti, album, vettori e gruppi restano collegati perche' l'id non cambia (D29).
- **Il cambio del campo A.** Prima un errore del catalogo era un annuncio perso (la sorgente andava nel cestino e la scansione rimetteva a posto); ora tiene l'elemento in `PUBLISHED` con la sorgente al suo posto e mette in pausa l'operazione ("condizioni") senza fermare le altre (`NasOpsEngine.kt:477`, `:496-500`). E' la direzione giusta: chiude E-SH2 anche per gli spostamenti di Cartelle NAS, e il costo e' quello di L74 (doppio spazio finche' il catalogo non risponde).
- **Cosa non regge.**
  - Con un errore della prima occhiata alla destinazione che non e' di rete, il motore va avanti **senza** chiamare la porta; se la seconda occhiata (`copyStillThere`) riesce, la sorgente va nel cestino senza che il catalogo sappia la destinazione (R8, plausibile).
  - Una tenuta puo' durare per sempre (un catalogo che risponde sempre "destinazione di un altro media", o "byte diversi"), con il motivo "condizioni" che in Operazioni e nella condivisione non dice niente, e un'operazione con piu' di 10.000 elementi non parte affatto (I2).

### 2. Le correzioni SD-1-SD-4: **corrette alla radice, reggono**; una dipendenza da rinforzare

- **SD-1** (`core-albums/.../share/ShareSteps.kt:284-321`): gli esiti si assegnano per la posizione di ogni elemento fra **tutti** quelli del biglietto, in qualunque stato; con una parte ancora `PLANNED` si aspetta; una lista che non torna con la richiesta (il pianificatore di `core-nasops` salta elementi: doppioni, sola lettura, sorgente assente, `OpPlanner.kt:177-203`) non si applica per posizione e decide il catalogo. L'ordine degli elementi del piano di `core-nasops` e' quello della richiesta (`OpPlanner.kt:206-212`) e gli esiti escludono le cartelle (`app/.../albums/AlbumTransferPorts.kt:142`): l'assegnazione per posizione e' giusta.
- **SD-2** (`ShareSteps.kt:117-137`, `:172-179`): un errore di `find` vale "non so" (il gruppo resta `PLANNED`, niente E-SH12, niente avvio, niente `NOT_STARTED`). **Ma** `find` legge registri su file che, se illeggibili, valgono "vuoti" e **non lanciano** (`AlbumTransferPorts.kt:76`, `:137`, `:226`; `app/.../albums/AlbumFiles.kt:40-44`, `:66-79`): in quel caso "non so" torna a essere "nessuno" (R9). Il danno resta senza perdite (il trasferimento finisce, l'elemento dice "non partito", "Riprova" lo cita come riferimento), ma la garanzia di SD-2 dipende da un registro che oggi non la regge.
- **SD-3** (`ShareOrchestrator.kt:190-225`): "Annulla" chiede `find`; ritrovato, l'elemento diventa `TRANSFERRING` e si ferma con la porta; senza risposta resta com'e' e la conferma si toglie (niente di nuovo parte).
- **SD-4** (`ShareSteps.kt:349-382`): un esito di fallimento o di annullamento con il media gia' dall'altra parte (stesso `MediaId` nell'area comune, o nella mia area privata per "Riporta") vale "arrivato": e' il catalogo a decidere, coerente con D29. Il riferimento di P0 (`testing/ReferenceShareSteps.kt`) ha le stesse regole.
- **Nessun nuovo difetto della stessa famiglia** trovato nei passi. Restano: il banco sui legami veri di `:app` e' piccolo e la morte del processo e' una chiusura (L109); una riga del diario che non si decodifica sparisce in silenzio dalla lista degli elementi (I5).

### 3. La tabella dei permessi, lato scrittore uguale al lato lettore: **regge per le righe a doppio controllo, non regge per le due righe "solo scrittore"**

- **Una tabella, due usi.** La tabella e' in `core-albums/.../contract/AlbumPermissions.kt:115-138`; `AlbumPermissions.check` (`core-albums/.../shared/AlbumRules.kt:113-178`, chi legge) e `SharedAlbumRules.allows` (`AlbumRules.kt:229-241`, chi scrive) usano le stesse celle (`admits`, `cell(op, level).condition`), lo stesso livello all'ora della voce e la stessa condizione "aggiunto quando si poteva"; la UI le prende da `AlbumPolicy` sopra la stessa tabella; l'orchestratore chiede `allowed` alla replica (`ReplicaSharedAlbums.kt:301-308`, `SharePlanner.kt:97`, `:191`). Riga per riga:

| Riga del §2.4 | Esito |
|---|---|
| Aggiungere, adottare | Uguali (`SharePlanner.kt:97`; `AlbumRules.kt:159-160`). |
| Togliere il proprio o l'altrui | Uguali (`ReplicaSharedAlbums.kt:189`; `AlbumRules.kt:166-174`). |
| "Riporta" | Uguali (`SharePlanner.kt:191`; `AlbumRules.kt:130-132`, `:169-170`). |
| Copertina, ordine | Uguali per la copertina; il riordino dei condivisi e' offerto e sempre rifiutato (I6). |
| Rinominare (titolo e cartella) | Uguali: il lettore ammette il cambio di cartella per la riga RENAME (`AlbumRules.kt:201-204`), lo scrittore lo offre solo al creatore o all'erede (`app/.../albums/AlbumFolderWatch.kt:88`). |
| Livello, chiusura | Solo creatore o erede da entrambi i lati (`AlbumFusion.kt:202-233`; `AlbumRules.kt:205-209`). |
| Ereditare | Uguali (`ReplicaSharedAlbums.kt:281-282`; `AlbumRules.kt:147-154`); fragile con il ciclo di vita dei dispositivi (R6). |
| **Eliminare il file, nuova versione** | **Non le applica nessuno** (R3). |

- **Dove il lettore e' piu' largo dello scrittore:** la riscrittura dell'elemento di un altro con un contenuto diverso e `addedBy` invariato (R14); nessuno scrittore produce quella voce.
- **Dove il controllo del lettore e' aggirabile:** l'ora delle voci (R7).

---

## Risposte al perimetro

1. **Familiare compromesso (definizioni, contributi, lapidi, eredita').**
   - **Codec**: busta, firmatario approvato (dismesso solo con testa congelata, revocato o perso mai), versione 3 legata al tipo, JSON canonico con campi sconosciuti rifiutati, chiave di ogni voce ricalcolata, `account` uguale al `nasUser` del firmatario, id dell'album ricalcolato da account e nonce, corpo al piu' 4 KiB, 8.192 voci per segmento controllate prima di allocare, percorso al piu' 1.024 byte, relativo, senza `..`, nomi riservati o `.mycloudgallery` anche in forma NFKC (`core-trust/.../data/AlbumDocs.kt:456-463`, `:489-492`, `:526-532`, `:676-697`, `:718`; `core-trust/.../nas/SharePath.kt:50-71`).
   - **Far vedere media privati**: no. Un elemento si mostra solo se il catalogo di chi legge vede **nell'area comune** un file con la stessa chiave di contenuto (`core-database/.../AlbumCatalog.kt:199-217`, `core-albums/.../shared/SharedAlbumReplica.kt:129-138`), poi passa da `VisibleMedia` (`ReplicaSharedAlbums.kt:118-123`, copertina `:128`). Le voci non portano mai l'hash completo (I1).
   - **Far spostare, copiare, scaricare o cancellare**: no (risposta 4).
   - **Gonfiare**: la replica ha tetti per dispositivo (512 album, 100.000 voci, 64 MiB per giro, 1.024 cartelle elencate; `core-albums/.../shared/SharedPorts.kt:66-77`), ma in voci e controllati dopo il download (R13); le copie di Q5 (b) gonfiano i **dati personali** di tutti (R1).
   - **Rompere la sincronizzazione degli altri**: un contributo rifiutato resta isolato e il giro degli album gira dopo l'indice, dentro un `try` (`app/.../work/NasWork.kt:413-422`); un contributo con un segmento assente o un file illeggibile invece blocca il giro di tutti gli album e lo ripete (R2).
   - **Rivendicare o ereditare**: l'id legato all'account impedisce di rivendicare; l'eredita' vale solo con il creatore uscito dal registro firmato e da un dispositivo admin approvato (`AlbumFusion.kt:67-71`); l'erede dipende pero' dal dispositivo che ha scritto la voce (R6).
   - **Restano**: R1, R2, R7, R12, R13, R14.
2. **Permessi aggirati dal PC.** Impossibili da impedire, dichiarati in ogni schermata (L57). Un file messo dal PC nella cartella non entra nell'album finche' un familiare con il permesso non lo adotta con conferma (D39); uno tolto esce per tutti (E-SH17); la cartella rinominata si propone solo al creatore (E-SH16, L105). Dal PC, senza chiavi, si puo' pero' togliere un segmento e mandare in ciclo i lettori (R2), e una cartella con un percorso troppo lungo fa cadere l'app al tocco su "Usa la nuova cartella" (R15). Teste vecchie rimesse: anti-rollback ed equivocazione per nome (L70).
3. **Nascosti.** Una fonte (`core-library/.../visibility/CatalogVisibility.kt`, `VisibilityQueries.kt`): id sconosciuto, errore e gruppo contagiato valgono "nascosto" (`CatalogVisibility.kt:60-69`). Album personali (`AlbumReader`), condivisi (`ReplicaSharedAlbums.kt:118-138`, anche il nascosto aggiunto da un altro familiare e la copertina scelta altrove), smart e viaggi (candidati da `VisibleMedia` prima di contare, `HiddenMask` prima di scrivere), Doppioni (rifiltrati in `plan` e `placesOf`), widget (`RotatingWidgetFeed`, solo debug, non esportato), selettore e "Aggiungi ad album" (`feature-albums/.../choose/AddToAlbumController.kt:91-100`), viewer da un album (`app/.../albums/ViewerAlbums.kt:180-182`), Cartelle NAS ("N elementi nascosti"). **Escono**: dalla pagina Spazio e dal Cestino di Cartelle NAS (R4), dai titoli e dai luoghi dei viaggi gia' calcolati (R16). Il registro `HiddenSurfaces` trova solo i file che nominano i contratti dei nascosti (R4).
4. **Spostamento in condivisione.**
   - **Conferma**: una sola emissione dal tocco sul foglio (`feature-albums/.../share/ShareSheetController.kt:86`, `:141-147`; `ShareConfirmationSheet.tap` scarta i tocchi a meno di 600 ms, `core-albums/.../contract/ShareConfirmation.kt:80-88`); costruttore `internal` (`:66`); 10 minuti di orologio monotono sia per la conferma sia per il piano (`:113-119`); allo `start` il piano si rifa' sul catalogo e deve avere la stessa impronta, lo stesso piano non parte due volte (`ShareOrchestrator.kt:80-89`). La `UserConfirmation` la emette `:app` per il piano di `core-nasops` costruito dal diario (`AlbumTransferPorts.kt:97-121`): e' una conseguenza della prima chiave, non una chiave indipendente (I3).
   - **Diario**: operazione ed elementi in una transazione **prima** di ogni azione (`ShareOrchestrator.kt:98-99`, `app/.../albums/RoomShareDiary.kt:46-53`); la chiave di ogni trasferimento e' nel diario prima di chiamare la porta; la cartella d'origine si registra nei dati personali prima dello spostamento (`ShareSteps.kt:182-194`); un passo non ammesso dalla macchina a stati lancia (`RoomShareDiary.kt:61-70`).
   - **Porta di completamento**: giudizio 1. **Guardiano**: solo per i miei media spostati davvero dall'area privata e che il telefono non ha piu', con richiesta durevole e `GuardScope.All` (`ShareSteps.kt:474-483`, `core-backup/.../safety/GuardianSafeCopyRequests.kt:57-74`).
   - **"Riporta"**: solo media miei, nell'area comune, con la cartella d'origine nei miei dati personali, aggiunti da me e permessi dalla tabella; la destinazione si scrive nel diario allo `start` e resta nell'area privata (`SharePlanner.kt:172-218`, `ShareOrchestrator.kt:123-131`).
   - **Q5**: la scelta `REMOVE`/`KEEP_COPY` e' di chi chiude, senza valore di partenza (`CloseSheetController`), firmata nella definizione, un codice sconosciuto vale `remove`; nessuna delle due muove file. `KEEP_COPY` pero' e' anche la via di R1.
   - **E-S11**: nessun dato letto dal NAS produce una conferma, un piano o una chiamata alle porte (`ShareOrchestrator` lavora solo sulle operazioni del suo diario; la cartella di un album esistente, che e' un dato remoto, fa solo da destinazione di un piano confermato e sta sempre nell'area comune del registro locale, `ReplicaSharedAlbums.kt:326-330`).
   - **File persi o doppi**: nessuna perdita trovata; un file doppio nell'area comune non si forma (E-SH12 con la chiave di contenuto, un media in una sola operazione aperta, `SharePlanner.kt:110-116`, `:138`); dopo un cestino della sorgente non riuscito restano due copie, come voluto (E-D13).
   - **Restano**: R8, R9, I1-I5.
5. **Pulizia dei Doppioni.** Tutto nei cestini (nessuna cancellazione definitiva), identici solo con l'hash completo ("da verificare" senza), area di un altro account mai proposta, "mai l'ultima copia privata" e "mai un elemento di album condiviso" alla proposta (`core-library/.../duplicates/IdenticalCleanup.kt:41-73`), nascosti rifiltrati al piano, timbro sulle copie del NAS, il NAS prima e il telefono solo se il NAS accetta, "Elimina subito" solo voci proprie e due tocchi. **Non regge** al momento del tocco (R5), per gli elementi di album fuori dal prefisso e per i simili "Ovunque" (R3), e la prova del tocco non e' infalsificabile (R18).
6. **Privacy della casa (P7).** La casa sta solo in `home_estimate`, letta e scritta solo da `TripStore` (`Phase7ArchitectureTest`), `toString()` sicuri, log a formato fisso (`TripLog`), nei dati personali solo l'impronta di una proposta scartata (coerente con L87, I8). Le coordinate vicine a casa arrivano pero' nei dati personali per un'altra strada: il centro di un album smart sulla propria citta' e, in casi particolari, il cerchio di un viaggio (R16). Nessun titolo, cartella, luogo o account nei log nuovi della fase.
7. **Storage e azzeramento.**
   - `StateFiles` (`core-common/.../files/StateFiles.kt`): temporaneo nella stessa cartella, `force`, `ATOMIC_MOVE` + `REPLACE_EXISTING`, temporaneo tolto su errore, cancellazione vera solo se il file non c'e' piu', log con il solo nome. Regge come primitiva; i chiamanti hanno ancora punti che restano aperti dopo un errore (R10, R11).
   - Registri della Fase 7 su file in `noBackupFilesDir/albums/` (atomici, fuori dai backup: `data_extraction_rules.xml` esclude tutto, `allowBackup=false`): illeggibile vale vuoto e si sovrascrive (R9).
   - Room: schema v6 senza migrazioni distruttive, query parametrizzate, nessun `LIKE`; le tabelle v6 seguono le unioni d'identita' tranne in `linkCopyAsVersion` (R21).
   - Azzeramento (`app/.../graph/LocalData.kt`): gli album si fermano per primi (`albumsStopper`), poi AI, galleria, file; `noBackupFilesDir/albums/` e il database (con `home_estimate`, `trip_proposal`) si tolgono. Restano R10, R11 e una corsa plausibile allo stop (I11).
8. **Recinzione D30 e F4-D31 nella release.**
   - `FeatureFlags.RELEASE` ha `sharedAlbumsTestSharesOnly = true` come costante; `forBuild` non ha parametri che la spengano.
   - Il cancello delle scritture (`app/.../albums/AppAlbumPorts.kt:98-118`) la mette prima di tutto e vale per la pubblicazione (`NasAlbumPublisher.kt:115-120`), per il piano della condivisione su area comune **e** area privata (`SharePlanner.kt:58-61`) e per la lettura (`FlagsAlbumReadGate`, `NasSharedAlbumSync.kt:96-97`); `ShareFence` e' solo debug.
   - F4-D31 (indice "solo locale") per progetto non ferma gli album (`AppAlbumPorts.kt:110-111`): per loro decide D30 fino al consenso di Q7.
   - Utility e Doppioni non hanno recinzione, per progetto (I9).
   - `check.ps1` controlla solo che i due membri di `FeatureFlags` siano nel dex, non che il cancello li usi (R20).
9. **Guasti (morte del processo, rete).** Diario della condivisione su Room, chiavi dei trasferimenti prima delle porte, conferma solo in memoria (dopo la morte "non partito", L73), caricamenti `INCOMING` ritrovati per chiave, ripresa per giro di `ShareWorker`; replica e pubblicatore su Room; un errore passeggero di una porta non diventa mai permanente. Restano: un errore di un solo file ferma il giro degli album (R2), i registri su file (R9), il pubblicatore dopo la perdita del database (R12), le tenute senza fine della porta (I2).

---

## Alta

### R1. Un album chiuso con "ognuno tiene una copia" fa scrivere copie personali a ogni familiare, anche di album mai visti, e restano dopo la revoca

- **Gravita':** alta. E' l'unica via trovata da un dato letto dal NAS a una **scrittura** sul dispositivo di chi legge senza un tocco. Un solo dispositivo approvato e compromesso mette fino a 512 album personali, con titoli suoi, nei dati personali firmati di **ogni** altro account della famiglia; la revoca non li toglie (sono voci delle vittime) e vanno eliminati a mano, uno per uno, su ogni account. Contro L58 ("la revoca lo toglie", "non riempie il telefono") e contro D31 ("uno stato `closed` o `deleted` e' definitivo per quell'id").
- **Dove:**
  - `core-albums/.../shared/AlbumRules.kt:192-196`: la prima definizione di un album vale anche se nasce gia' `closed`/`deleted` (basta che la scriva il creatore);
  - `core-albums/.../shared/ReplicaSharedAlbums.kt:141-154` (`keptCopies`): una copia per ogni album chiuso con `KEEP_COPY`, non mio e non lasciato, senza chiedersi se questo account l'abbia mai visto attivo e senza tetti; `:106-110`: un avviso "chiuso" per ognuno;
  - `app/.../albums/AlbumsGraph.kt:262` e `:272-283` (`keepClosedCopies`): la scrittura avviene a ogni `syncRound` del lavoro dell'indice, senza azione locale;
  - `core-albums/.../personal/PersonalAlbumCopies.kt:42-58`: a ogni giro si aggiungono gli elementi attestati che mancano (definizione, `copy_of`, una voce `item:` per elemento);
  - `core-albums/.../shared/AlbumFusion.kt:202-233`: di ogni dispositivo conta solo l'ultima testa, quindi il creatore riapre un album chiuso pubblicando una definizione `active` (la chiusura non resta);
  - tetti della replica: `core-albums/.../shared/SharedPorts.kt:73-76` (512 album e 100.000 voci per dispositivo).
- **Scenario (1):**
  1. Il telefono B, approvato e compromesso, pubblica nell'area comune 512 album che nascono gia' chiusi con `afterClose = keep_copy`, titoli a scelta (64 caratteri) ed elementi che citano file qualunque dell'area comune (bastano chiave di contenuto e percorso, che B vede).
  2. Al giro dell'indice successivo ogni dispositivo di ogni altro account scrive 512 album personali con quei titoli e quegli elementi (fino a 100.000 voci in tutto), piu' 512 avvisi "non e' piu' condiviso". Le copie entrano nell'area privata di ogni account e si sincronizzano sugli altri suoi dispositivi.
  3. L'admin revoca B: gli album condivisi spariscono, le copie no.
  4. Con il giro "riapri, aggiungi, richiudi" B allunga le copie gia' fatte (un elemento tolto dall'utente non torna, uno nuovo si').
  5. Oggi la recinzione D30 limita tutto alle share di prova; il consenso di Q7 lo porterebbe sulle share vere.
- **Confermato:** si', nel codice (copia senza azione locale, album mai visti attivi, chiusura non definitiva). Quante voci si scrivono e' un calcolo; che 100.000 voci in piu' pesino sul tetto dei dati personali (262.144 per dispositivo) e' plausibile, non provato.
- **Correzione proposta:**
  1. copia solo per un album che questo account ha visto **attivo** (un segno locale scritto quando l'album entra fra i visibili) e chiuso **dopo** quel segno; nessuna copia per un album nato chiuso;
  2. gli elementi della copia si fissano alla prima scrittura (o solo quelli attestati al momento della chiusura), niente aggiunte nei giri dopo;
  3. la chiusura vista resta in locale per quell'id: una definizione `active` successiva si scarta (D31);
  4. un tetto alle copie per creatore e agli elementi per copia, con un codice della Diagnostica;
  5. un test: un dispositivo ostile con 512 album nati chiusi e il ciclo riapri/richiudi, poi la revoca: nessuna copia.

## Media

### R2. Un contributo rotto blocca la sincronizzazione degli album di tutta la famiglia e fa riscaricare fino a 64 MiB per giro, in ciclo

- **Gravita':** media. Si ferma l'arrivo delle teste nuove degli album su ogni telefono che legge l'area comune, il traffico non si conta, e la variante con il segmento tolto si fa **dal PC**, senza chiavi.
- **Dove:**
  - `core-albums/.../shared/NasSharedAlbumSync.kt:230-235`: un segmento elencato e assente rende il giro "incompleto" e si esce senza ricordare niente: al giro dopo si riscaricano gli altri segmenti della testa;
  - `:239` e `:214-221`: i byte gia' letti contano nel tetto del giro (64 MiB): gli album dopo trovano il tetto pieno e saltano la loro testa nuova;
  - `:112` e `:128`: il cursore avanza di quanti album si sono visitati; se il giro li visita tutti, l'ordine resta lo stesso (e l'id di un album lo sceglie chi lo crea, con il nonce);
  - `:170-174`, `:183-187`, `:121-125`: una `IOException` su un solo file (una testa illeggibile) vale "il NAS non risponde" e ferma il giro, con il cursore fermo su quell'album;
  - `app/.../work/NasWork.kt:282-284`: `scope.record(0)` (i byte non si contano) e `hasMore = !done`; `app/.../albums/AlbumsGraph.kt:268`: un giro incompleto chiede subito un altro giro alla tappa;
  - `core-albums/.../shared/NasAlbumPublisher.kt:147`: un proprio segmento gia' pubblicato non si ricontrolla mai, quindi chi l'ha perso non lo ritrova.
- **Scenario (1):** B pubblica due contributi da circa 32 MiB in un album con l'id scelto per venire primo, e toglie l'ultimo segmento. Ogni lettore scarica circa 64 MiB, trova il buco, butta tutto e ripete subito finche' dura la tappa; gli album dopo non ricevono piu' teste nuove.
- **Scenario (2):** dal PC si toglie un segmento della testa piu' recente di un familiare: chi non l'aveva ancora letta riscarica in ciclo, e il pubblicatore non lo rimette mai. Variante plausibile: una testa con un'ACL che nega la lettura, o tenuta aperta in esclusiva dal PC, ferma ogni giro su quell'album.
- **Confermato:** si' per il segmento assente; plausibile per l'errore di I/O di un file (dipende da come `SmbNasFileSystem` traduce un'ACL o una condivisione esclusiva: solo "assente" diventa `NotFound`).
- **Correzione proposta:**
  1. ricordare in modo durevole la testa con un segmento assente e riprovarla a intervalli crescenti, senza segnare il giro "incompleto" per un'attesa;
  2. registrare i byte veri con `scope.record` e un tetto di byte per dispositivo e per giro;
  3. far avanzare il cursore oltre l'album rimasto senza budget o con un errore;
  4. distinguere gli errori di sessione (NAS irraggiungibile, si ferma il giro) da quelli di un file (quel contributo si riprova, il giro continua);
  5. il pubblicatore ricontrolla (o ripubblica con `publishExclusive`) i segmenti che riusa;
  6. un test con un contributo senza l'ultimo segmento e un secondo album dopo: il secondo si aggiorna.

### R3. Le righe "Eliminare il file" e "Nuova versione" della tabella non le applica nessuno: un familiare manda nel cestino il file di un elemento altrui a ogni livello

- **Gravita':** media. Il §2.4 ed E-D16 (`docs/fase7-progetto.md:275-276`) promettono che a "Gli altri aggiungono" eliminano il file solo il creatore e chi l'ha aggiunto, e a "Gli altri guardano" solo il creatore. Sono righe che per progetto controlla solo lo scrittore: se non le applica lui, non le applica nessuno. Il file va nel cestino del NAS (30 giorni) ed esce dall'album per tutti (E-SH17).
- **Dove:**
  - `core-albums/.../contract/AlbumPermissions.kt:120-121`: `DELETE_FILE` e `NEW_VERSION` con `Enforcement.WRITER_ONLY`; nel codice di produzione nessuno chiede `allowed(DELETE_FILE)` ne' `allowed(NEW_VERSION)`;
  - `feature-viewer/.../menu/NasActions.kt:124-137`: "Elimina...", "Sposta" e "Rinomina" dipendono solo da `writable` dell'area, non dagli album;
  - `core-library/.../duplicates/CatalogSimilarShots.kt:312-355` (`placesOf`): "Ovunque" dei Doppioni simili manda nel cestino anche le copie dell'area comune, elementi di album compresi (la protezione di preferite e album e' solo una preselezione nell'interfaccia);
  - `core-library/.../duplicates/IdenticalCleanup.kt:84-88`: gli identici riconoscono un elemento di album solo dal prefisso "Album condivisi/": un riferimento (E-SH11) altrove, o una cartella adottata con E-SH16, non e' protetto.
- **Scenario (1, ma basta un'app difettosa):** album "Gli altri guardano" del familiare A. B, di un altro account, apre una foto di A dall'album, ⋮ → "Elimina..." → "Ovunque": la foto va nel cestino del NAS di B ed esce dall'album per tutti. "Sposta" verso la sua area privata la porta via senza passare da "Riporta". Lo stesso con i Doppioni simili, se B ha una copia quasi uguale e sceglie "Ovunque".
- **Confermato:** si', nel codice.
- **Correzione proposta:**
  1. il viewer, Cartelle NAS e i Doppioni ricevono l'appartenenza del file agli album condivisi (dalla replica, per chiave di contenuto) e chiedono `allowed(DELETE_FILE)`; dove non e' permesso, niente "Elimina", "Sposta" o "Rinomina" su quel file;
  2. il percorso di modifica che salva nell'area comune chiede `allowed(NEW_VERSION)`, altrimenti salva come media nuovo nell'area privata (E-E9);
  3. nei Doppioni gli elementi di album si riconoscono dall'appartenenza, non dal prefisso;
  4. il test generato dalla tabella anche per queste due righe, lato scrittore.

### R4. "Spazio" e "Cestino" di Cartelle NAS mostrano nome e cartella dei file nascosti, senza cancello

- **Gravita':** media. D22, D23 e C27 promettono i Nascosti "mai, su ogni superficie"; la pagina delle cartelle mostra "N elementi nascosti" senza nomi, ma la pagina accanto li elenca.
- **Dove:**
  - `app/.../nas/NasOrganizer.kt:925-943` (`CatalogLargestFiles`): i 50 file piu' grandi da `media_location`, senza `media_personal` ne' contagio dei gruppi; legata a `:372`;
  - `feature-nas/.../space/NasSpaceScreen.kt:311-318`: titolo = nome del file, sottotitolo = cartella;
  - `feature-nas/.../trash/NasTrashScreen.kt:318-330`: nome e cartella d'origine di ogni voce del cestino;
  - `app/.../navigation/CollectionsSectionsWiring.kt:124-126`: Raccolte → Cartelle NAS → Spazio e Cestino, senza il cancello dei Nascosti;
  - il registro dei Nascosti trova solo i file che nominano `VisibleMedia`, `HiddenMask` o `WidgetFeed` (`app/src/test/.../hidden/RepoFiles.kt:45-50`): una schermata che mostra nomi senza usarli non e' mai segnalata.
- **Scenario (3):** un video nascosto da 2 GB e' quasi certamente fra i 50 file piu' grandi. Chi ha il telefono sbloccato apre Raccolte → Cartelle NAS → Spazio e legge nome, cartella e peso. Nel Cestino, i nomi dei nascosti eliminati negli ultimi 30 giorni.
- **Confermato:** si' per Spazio; plausibile per il Cestino (dipende da cosa c'e' dentro; `feature-library/.../trash/NasTrashPort.kt:101` dice che il cestino "puo' avere i nomi di cose nascoste" e lo apre solo dietro il cancello, la pagina di `feature-nas` no).
- **Correzione proposta:** `CatalogLargestFiles` escluda le posizioni di `CatalogHidden.hiddenLocationKeys` (o le conti in una riga "N elementi nascosti"); il Cestino di Cartelle NAS dietro il cancello, o con i nomi dei nascosti mascherati; le due pagine nel registro con una sonda sul codice vero; un test d'architettura che elenchi le schermate di `feature-nas` che mostrano un nome o un percorso.

### R5. Doppioni identici: alla conferma il piano non ricontrolla la copia da tenere

- **Gravita':** media. C28 promette "mai l'ultima copia privata": vale quando si apre la pagina, non quando si tocca "Elimina". Tutto va nei cestini per 30 giorni, quindi niente e' definitivo; ma la regola si rompe e "al sicuro" si perde.
- **Dove:**
  - `core-library/.../duplicates/CatalogIdenticalDuplicates.kt:105-152` (`plan`): rilegge solo le copie da togliere (`:123-131`), non guarda mai `proposal.keep` ne' le altre copie del gruppo, e usa l'area della scansione (`:144`);
  - `feature-library/.../utility/DuplicatesController.kt:104` (scansione all'apertura), `:216` (piano al tocco di "Pulisci"), `:312-320` (conferma con il piano del foglio);
  - `core-database/.../MediaCatalog.kt:846-850`: `attachMoved` fa cambiare posto alla **stessa** riga (stesso `location_id`), quindi una condivisione in sottofondo cambia l'area di una copia senza cambiarne l'id.
- **Scenario (5, 9):** gruppo NAS con A (privata verificata, da tenere) e B (privata in `Backup/...`, da togliere). La pagina resta aperta; nel frattempo A viene tolta dal PC, oppure un'altra condivisione la sposta nell'area comune. Tocco su "Pulisci": B va nel cestino. Dell'area privata non resta niente fuori dai cestini.
- **Confermato:** si', nel codice.
- **Correzione proposta:** in `plan` (e di nuovo in `confirm`, subito prima di `cleanupNas.trash`) rifare `IdenticalCleanup.propose` sui dati di adesso (area, appartenenza agli album, `verifiedAt`) con la stessa scelta dell'utente; tenere solo l'intersezione con le copie da togliere; pretendere almeno un'altra copia presente dalla stessa parte, e verificata se la proposta diceva "al sicuro"; un test con la copia da tenere che sparisce fra la scansione e il tocco.

### R6. Dismettere un dispositivo fa sparire per tutti i suoi contributi agli album, definizione compresa; l'eredita' cade con il dispositivo admin che l'ha scritta

- **Gravita':** media. D8 e `formato-nas.md` A7: "dismesso: valgono i contributi delle sue teste congelate". Nessun flusso congela le teste, quindi per gli album un dispositivo dismesso vale come uno revocato. Dismettere il vecchio telefono del creatore, il gesto normale quando si cambia telefono, fa sparire l'album a tutta la famiglia: senza la definizione del creatore l'album "non e' creato". Spariscono anche gli elementi aggiunti da li'.
- **Dove:**
  - `core-family/.../registry/RegistryWriter.kt:150-152`: "La dismissione congela le teste gia' verificate [...] per ora nessuna";
  - `core-albums/.../shared/SharedAlbumReplica.kt:163-170` e `core-trust/.../data/AlbumDocs.kt:460`: un dismesso vale solo con una testa congelata;
  - `core-albums/.../shared/AlbumRules.kt:192-197`: senza la definizione del creatore (o dell'erede) l'album e' `NOT_CREATED`;
  - `core-albums/.../shared/AlbumFusion.kt:67` e `:188-200` (il ciclo degli eredi): la voce `heir` vale solo se il dispositivo che l'ha scritta e' admin **e approvato adesso**; se viene dismesso o revocato, l'erede perde il ruolo e la sua definizione non vale piu';
  - `docs/decisioni.md:5376-5377` (punto aperto di P2: "Va detto a chi scrive il ritiro (P13)") e `:7945-7946` (P13, punto aperto 7: un album mio rimasto senza definizione non ha una riga che offra il ripristino).
- **Scenario (9):** il creatore cambia telefono; l'admin segna il vecchio come dismesso. Al giro dopo l'album sparisce dalle Raccolte di tutti; i file restano nell'area comune. Il telefono nuovo del creatore non ha una pagina da cui ripristinarlo (E-SH20 e' offerto solo dalla pagina di un album). Lo stesso succede a un album ereditato quando si dismette il telefono admin dell'erede.
- **Confermato:** si', nel codice.
- **Correzione proposta:** alla dismissione le `frozenHeads` comprendono le teste d'album accettate dalla replica (come promette A7), oppure il ritiro chiede prima a un altro dispositivo dello stesso account di ripubblicare definizioni ed elementi; l'eredita' legata all'account admin, non al dispositivo; una riga dell'elenco per gli album propri senza definizione (E-SH20); un test "dismetti il telefono del creatore".

## Bassa

### R7. La difesa contro le voci retrodatate (L59) si aggira dichiarando un `createdAt` vecchio

- **Gravita':** bassa. Serve un album che sia stato, in passato, a un livello piu' largo di quello di adesso; ma la difesa scritta in L59 non regge.
- **Dove:** `core-albums/.../shared/NasSharedAlbumSync.kt:209-213` (si controlla solo il futuro), `:256-268` (una voce nuova si confronta con `stored.createdAt`, cioe' con l'ora **dichiarata** dalla testa precedente dello stesso dispositivo); `core-trust/.../data/AlbumDocs.kt:382` (`createdAt` deve solo essere plausibile).
- **Scenario (1):** B pubblica ogni testa con `createdAt` uguale all'ora della creazione dell'album: nessuna sua voce nuova risulta mai retrodatata. Se l'album e' stato a "Tutti possono tutto", B data in quel periodo lapidi su elementi altrui, una rinomina o la copertina, anche dopo che gli altri hanno letto le sue teste di allora.
- **Confermato:** si'.
- **Correzione proposta:** confrontare con l'ora del NAS in cui chi legge ha accettato la testa precedente (nella riga `~meta`), rifiutare un `createdAt` minore di quello della testa precedente accettata, e per il primo contributo di un dispositivo usare la prima osservazione dell'album; L59 riscritto.

### R8. La porta di completamento si salta se la prima occhiata alla destinazione fallisce per un errore che non e' di rete

- **Gravita':** bassa. Rompe l'invariante centrale di D29 ("mai la sorgente nel cestino prima che il catalogo conosca la destinazione") in una finestra stretta.
- **Dove:** `core-nasops/.../engine/NasOpsEngine.kt:806-810` (un errore che non e' di rete, una destinazione assente o una cartella: `return true`, si va avanti **senza** chiamare la porta); `:1094-1101` e `:1130-1139` (`copyStillThere` rifa' l'occhiata: se adesso riesce, la sorgente va nel cestino).
- **Scenario (4, 9):** appena pubblicata la copia, la `stat` della destinazione torna un errore che non e' di rete (il file e' aperto in esclusiva da un altro client SMB, un errore passeggero del NAS): niente porta; la sorgente si registra; un attimo dopo `copyStillThere` vede il file e la sorgente va nel cestino. Il catalogo punta ancora alla sorgente fino alla scansione, che puo' vedere la copia come un media nuovo (`NeedsFullHash`): preferiti, nascosto e album personali si staccano. E' proprio E-SH2, che D29 doveva chiudere.
- **Confermato:** plausibile (la via nel codice c'e'; quali errori SMB arrivano a una `stat` subito dopo una pubblicazione non si prova qui).
- **Correzione proposta:** andare avanti senza porta solo con `Probe.Absent` (e allora `copyStillThere` ferma comunque); con un altro errore tenere l'elemento (`heldForCatalog`) come per un errore della porta; un test con una `stat` che fallisce una volta.

### R9. I registri durevoli degli album valgono "vuoti" se illeggibili, la prima modifica li sovrascrive, e SD-2 non distingue "non so"

- **Gravita':** bassa. Su Android un errore di lettura della memoria interna e' raro; ma i registri portano le richieste del guardiano (D28) e i legami da cui dipende SD-2.
- **Dove:**
  - `app/.../albums/AlbumFiles.kt:40-44`: un file oltre 16 MiB o un'`IOException` danno `null`; `:66-79`: `null` o un errore di decodifica diventano una mappa vuota **in cache**; `:84-96`: la modifica dopo riscrive il file intero; `:134-135`: `List(input.readInt())` alloca con un contatore letto dal file prima di leggere (un `OutOfMemoryError` non preso a ogni avvio);
  - il registro dei caricamenti non toglie mai un'intenzione (`core-backup/.../upload/AlbumFolderUploads.kt:50-56`) e porta il nome del file del telefono (`AlbumFiles.kt:170`); i legami chiave→piano non si tolgono a operazione finita (`app/.../albums/AlbumTransferPorts.kt:76-81`);
  - `AlbumTransferPorts.kt:137` e `:226` (`find`) leggono quei registri: un registro illeggibile non lancia, quindi `ShareSteps.lookUp` (`core-albums/.../share/ShareSteps.kt:172-179`) riceve "nessun trasferimento" invece di "non so".
- **Scenario (7, 9):** un errore di lettura passeggero all'avvio, o un registro dei caricamenti cresciuto oltre 16 MiB: il registro vale vuoto e la prima modifica lo sostituisce. Si perdono le richieste al guardiano (la copia privata dei media condivisi senza telefono non parte: resta solo il cestino, L62) e i legami dei trasferimenti: un trasferimento partito prima della morte del processo risulta "mai partito", finisce lo stesso e l'album non ha la voce.
- **Confermato:** si', il meccanismo; la probabilita' e' bassa.
- **Correzione proposta:** distinguere "assente" (vuoto) da "illeggibile o troppo grande": non mettere in cache, non sovrascrivere, rinominare in `.bad`, contare in Diagnostica, e far lanciare `find` (SD-2: "non so"); togliere intenzioni e legami a operazione chiusa; niente nome del file nel registro; contatori letti con un tetto legato ai byte rimasti.

### R10. L'azzeramento di un dispositivo perso salta la cancellazione dei dati locali se lo stato fidato non si toglie

- **Gravita':** bassa. Una regressione di `4174b830` in una funzione di sicurezza; la probabilita' su Android e' bassa.
- **Dove:** `core-family/.../lifecycle/DeviceSelfWipe.kt:182-186` (nella fase `LOCAL_DATA_ERASED` lo stato fidato si toglie **per primo**, poi `localState` ed `eraser.eraseAll()`); `core-family/.../trust/FileTrustStateStore.kt:56-60` (`clear()` ora lancia se il file resta; prima era silenzioso e il resto andava avanti).
- **Scenario (7):** telefono segnato perso; chiavi e credenziali sono gia' distrutte; lo stato fidato non si toglie (errore del disco, una cartella al suo posto): l'eccezione salta tutto il resto della fase. Catalogo, miniature, dati personali e `home_estimate` restano finche' la fase non riesce, al prossimo avvio o mai se l'errore resta.
- **Confermato:** si', il meccanismo.
- **Correzione proposta:** passi indipendenti con un esito complessivo (come `AppLocalDataEraser.Steps`), lo stato fidato per ultimo; la fase si ripete se uno dei passi non e' riuscito.

### R11. `StateFiles`: altri punti che restano aperti dopo un errore

- **Gravita':** bassa. Ognuno chiede un errore di scrittura o di cancellazione, raro su Android; il pacchetto voleva chiuderli.
- **Dove e scenario:**
  - `app/.../nas/FileFreezeAcceptances.kt:68-84`: il `throw` della `:72` lo prende il `catch` della `:81`: una franchigia consumata che non si toglie torna al riavvio (entro la sua settimana);
  - `app/.../ai/TextReading.kt:112-126`: se fallisce la scrittura di "spenta" mentre altri campi non sono quelli di serie, il file vecchio dice "accesa" al riavvio (il commento della `:123` dice il contrario); `:90-100`: `reset()` ignora l'esito;
  - `app/.../nas/NasGraph.kt:1055-1069`: un'emergenza "Ferma le scritture" accesa che non si salva vale solo in memoria: dopo il riavvio e' spenta, e `setWritesStopped` (`:905-908`) non lo dice a nessuno;
  - `core-library/.../data/CatalogTimelineRepository.kt:180`: l'istantanea con un media appena nascosto si toglie con `store?.delete()`, esito ignorato: al riavvio la prima immagine puo' mostrarlo;
  - `core-common/.../files/StateFiles.kt:225-233`: `delete` non prende il lucchetto di `write` e non toglie `<nome>.tmp`; `app/.../graph/LocalData.kt:119` toglie il battito prima che i lavori si fermino, e un giro della fiducia lo puo' ricreare dopo (plausibile).
- **Confermato:** si', tranne il battito ricreato (plausibile).
- **Correzione proposta:** propagare l'esito (o tenere in memoria un "consumata"/"spenta" che prevale sul file e toglierlo all'avvio prima di leggere); per l'emergenza dirlo nella pagina; per l'istantanea scriverne una vuota se il file non si toglie; `delete` sotto lo stesso lucchetto, con il temporaneo; il battito tolto dopo `WORK_CANCELLED`.

### R12. Il pubblicatore ricostruito dopo la perdita del database si fida di cio' che trova sul NAS

- **Gravita':** bassa. Serve la perdita del database locale (azzeramento, reinstallazione) **e** un segmento alterato dal PC o rovinato sul NAS.
- **Dove:** `core-albums/.../shared/NasAlbumPublisher.kt:133` (l'adozione scatta con `headVersion == 0`); `:239-257` (`adoptOwnHead`: legge l'ultima testa propria senza segno precedente; `:247`: un proprio segmento rifiutato fa ripartire **senza** le voci gia' pubblicate).
- **Scenario (2, 9):** dopo un azzeramento l'utente modifica un album a cui contribuiva. Se un suo segmento e' stato alterato, la testa nuova porta solo le modifiche in coda: per tutti spariscono i suoi elementi e, se e' il creatore, la definizione. Se dal PC sono state tolte le sue teste piu' recenti, adotta e ripubblica uno stato vecchio con un numero gia' visto (equivocazione, L70).
- **Confermato:** si', la logica.
- **Correzione proposta:** non pubblicare una testa che toglie voci gia' pubblicate se non si sono rilette tutte (`Failed` e un avviso); preferire la propria copia nella replica, quando c'e'.

### R13. Replica degli album: tetti dopo il download, cache intera in memoria, mai potata

- **Gravita':** bassa (media se una misura conferma l'esaurimento della memoria).
- **Dove:** `core-albums/.../shared/NasSharedAlbumSync.kt:245`, `:252-255` (20.000 voci per contributo, 100.000 e 512 per dispositivo controllati dopo il download e la decodifica); `app/.../albums/RoomAlbumReplicaStore.kt:36`, `:120-129` (tutte le voci di un album in memoria, mai svuotate), `:67` (`forget` non ha chiamanti in produzione); `core-albums/.../shared/SharedAlbumReplica.kt:150-151` (i contributi esclusi restano e si rifondono per gli orfani); `core-database/.../AlbumCatalog.kt:199-217` (l'attestazione legge tutte le versioni dell'area comune, per album, a ogni ricalcolo: `AlbumsGraph.kt:155`); `core-database/.../Migration5To6.kt:144-147` (nessun indice su `device_id` di `shared_album_entry`); `AlbumsGraph.kt:319-327` (`guarded` prende `Exception`, non `OutOfMemoryError`).
- **Scenario (1):** 100.000 voci con percorsi da 1.024 byte: circa 130 MB in Room e, a stima, 150-250 MB di oggetti al primo ricalcolo; un `OutOfMemoryError` all'avvio resta anche dopo la revoca. 512 album da un elemento: 512 letture dell'area comune a ogni cambio del catalogo.
- **Confermato:** plausibile (le quantita' sono stime).
- **Correzione proposta:** tetti in byte per dispositivo, controllati prima di scaricare; cache per album con un tetto; potatura dei contributi esclusi oltre una scadenza; attestazione in una passata per tutto il ricalcolo; indice `(device_id, album_id)`.

### R14. Chi legge accetta la riscrittura dell'elemento di un altro con un contenuto diverso

- **Gravita':** bassa.
- **Dove:** `core-albums/.../shared/AlbumRules.kt:161-165` (con `addedBy` invariato, una voce nuova per un elemento altrui vale come "togliere un elemento di altri" e passa a "Tutti possono tutto" o per il creatore, anche con chiave di contenuto e percorso diversi); `app/.../albums/AlbumFacades.kt:262-265` ("Aggiunta da ..."); lo scrittore non produce mai questa voce (`core-albums/.../personal/CatalogAlbums.kt:250-251`).
- **Scenario (1):** il creatore compromesso riscrive `item:<foto di B>` con la chiave di un'altra foto dell'area comune: tutti vedono un'altra foto "aggiunta da B".
- **Confermato:** si'.
- **Correzione proposta:** per un elemento altrui ammettere solo il cambio di `position`, o rifiutare la voce.

### R15. "Usa la nuova cartella" e "Ripristina" possono far cadere l'app

- **Gravita':** bassa.
- **Dove:** `app/.../albums/AlbumFolderWatch.kt:54-63` (la cartella proposta viene dai percorsi del catalogo, che arrivano a 4.096 byte, senza le regole del formato degli album), `:91` (`publisher.stage`); `core-albums/.../shared/NasAlbumPublisher.kt:100-101`, `:108` (`require` lancia `IllegalArgumentException`); `feature-albums/.../page/AlbumPageController.kt:297-305` (`scope.launch` senza `catch`).
- **Scenario (2):** dal PC si spostano i file dell'album in una cartella con un percorso oltre 1.024 byte; il creatore tocca "Usa la nuova cartella" e l'app si chiude.
- **Confermato:** plausibile (dipende dal gestore delle eccezioni dell'ambito della pagina).
- **Correzione proposta:** validare la cartella con le regole di `AlbumTexts`/`SharePath` prima di proporla; catturare l'errore e rispondere `EditOutcome.Invalid`.

### R16. Coordinate vicine a casa nei dati personali, e titoli dei viaggi fissati prima di un "nascondi"

- **Gravita':** bassa.
- **Dove:**
  - `core-albums/.../smart/SmartPlaces.kt:69-79`: il centro di un luogo e' la mediana delle foto proprie visibili di quel luogo; `core-albums/.../personal/SmartRulesText.kt:27`, `:95`: `smart:place` porta latitudine e longitudine a precisione piena nei **dati personali** (area privata sul NAS, non cifrata dentro la share, L26). L80 dice "il centro e le coordinate restano nel database dell'app": non e' cosi';
  - `core-albums/.../trips/TripDetector.kt:221-232` e `TripGeometry.kt:82-99`: il cerchio di un viaggio esclude le foto vicine a casa, ma non controlla il centro: un viaggio con due mete ai lati opposti di casa ha il centro vicino a casa (plausibile);
  - `core-albums/.../trips/CatalogTrips.kt:165-174`: titolo e luoghi di una proposta si fissano alla rilevazione (una volta per notte); `:212-224` (`recheck`) li riusa; `:176-188` (`accept`) scrive nei dati personali il titolo e i luoghi della riga.
- **Scenario (3, 6):** un album smart "<citta' di casa> 2025": il centro e' la mediana delle foto proprie in citta', in pratica vicino a casa, ed entra nei dati personali sul NAS. Un viaggio "A e B, marzo 2026" in cui l'unica foto di B viene nascosta: fino alla notte la proposta mostra ancora B, e "Crea album" lo scrive nei dati personali.
- **Confermato:** si' i meccanismi; plausibile la vicinanza a casa del centro di un viaggio.
- **Correzione proposta:** centro arrotondato (per esempio a 0,1°) e calcolato senza le celle di casa e dei luoghi abituali, come per i viaggi, o solo gli id dei luoghi nei dati personali con il centro ricalcolato in locale; titolo e luoghi ricalcolati dal gruppo filtrato in `recheck` e `accept`; L80 riscritto.

### R17. "Fallire chiusi" diventa "vuoto": un errore di lettura svuota album smart e proposte di viaggio

- **Gravita':** bassa. Nessuna fuga di nascosti; si perdono righe derivate e i segni "Nuove".
- **Dove:** `core-library/.../visibility/CatalogVisibility.kt:60-69` (un errore risponde `emptySet()`); `core-albums/.../smart/SmartVisible.kt:10-14` promette invece "l'eccezione risale e il motore non scrive niente"; `RulesSmartEngine.kt:129-164` e `SmartEvaluation.kt:112` (zero candidati: si tolgono le righe automatiche e i suggeriti); `core-albums/.../trips/TripVisibility.kt:28`, `CatalogTrips.kt:146-150` (casa e proposte sostituite).
- **Scenario (9):** un errore passeggero del database durante il ricalcolo notturno: gli album smart perdono le righe automatiche (e `added_at`), le proposte di viaggio spariscono fino al giro dopo.
- **Confermato:** si'.
- **Correzione proposta:** per chi confronta con uno stato salvato, `visible()` lancia un errore interno invece di rispondere vuoto (o il motore non applica un risultato nato da una lettura fallita).

### R18. Doppioni: la prova del tocco si fabbrica, le copie del telefono non hanno il timbro, "Rivedi" puo' scartare l'unica copia verificata

- **Gravita':** bassa (garanzie dichiarate in C28 che non reggono come scritto).
- **Dove:**
  - `feature-library/.../trash/NasTrashPort.kt:91` (`UserTap` con costruttore `internal`), `:133-156` (`DoubleTapConfirm` e `proof()` pubblici), `:167` (`singleTap()` usabile in tutto il modulo); `feature-library/.../trash/testing/TrashFakes.kt:101-111`: `fakeSingleTap()` e `fakeDoubleTap()` sono API pubblica nel set `main`; `feature-library/.../utility/DuplicatesController.kt:320`: la prova nasce in `confirm()`, non nel foglio, e senza tempo minimo fra la comparsa del foglio e il tocco (come C34);
  - `core-library/.../duplicates/IdenticalTypes.kt:147-149`: `PhoneTrashRef` ha solo il riferimento e la dimensione, niente timbro (E-D13 vale solo per il NAS);
  - `core-library/.../duplicates/IdenticalCleanup.kt:52-70`: "Rivedi" puo' scegliere da tenere una copia non verificata e mandare nel cestino l'unica verificata (i "mai" proteggono l'ultima copia privata, non l'ultima verificata).
- **Scenario (5):** una chiamata in `:app` a `fakeDoubleTap()` o a `controller.confirm()` mette in moto un `Trash` o un `PurgeNow` senza tocco; una copia del telefono sovrascritta dopo la scansione (stesso `_ID`, byte nuovi) va nel cestino anche se non e' piu' un doppione; dopo "Rivedi" il media perde "al sicuro".
- **Confermato:** si'.
- **Correzione proposta:** i falsi in `testFixtures` o `src/test`; la prova nasce solo nel composable, con il tempo minimo; il test di guardia promesso da `NasTrashPort.kt:88` (`TrashTapGuardTest` non esiste); timbro (dimensione, `date_modified`) sulle copie del telefono, controllato prima della richiesta; un "mai" per l'ultima copia verificata, o un avviso nel foglio.

### R19. Nessun tetto al lavoro che i dati personali di un dispositivo dello stesso account impongono agli altri

- **Gravita':** bassa.
- **Dove:** `core-albums/.../personal/AlbumDerivation.kt:66-72` e `MediaResolver.kt:20-42` (una query per impronta), dentro `database.transaction` (`CatalogAlbumProjection.kt:141-145`); `:94` (gli album con elementi "in attesa" si riproiettano a ogni cambio del catalogo); `core-albums/.../smart/RulesSmartEngine.kt:104` (`Duration.INFINITE` per ogni smart con descrizione o esempi); `app/.../albums/AlbumsGraph.kt:304-307` (la notte valuta tutti gli smart). Nessun tetto oltre i 262.144 record per dispositivo dei dati personali.
- **Scenario (1, stesso account):** 100.000 voci `item:` con impronte inventate in un album fanno 100.000 ricerche per impronta sotto il lucchetto di scrittura a ogni scansione; migliaia di smart "a descrizione" fanno partire ricerche a ogni foto nuova.
- **Confermato:** plausibile (l'assenza di tetti e' nel codice; l'attacco no).
- **Correzione proposta:** tetti su album per account, riferimenti per album e smart con descrizione; risoluzione per impronta fuori dalla transazione, con un ritardo crescente per gli album sempre "in attesa".

### R20. Il passo D30 di `check.ps1` non prova che il cancello usi la recinzione nella release

- **Gravita':** bassa (la recinzione c'e' nel codice: costante e cancello, risposta 8; manca la prova sull'APK).
- **Dove:** `tools/check.ps1:882-902` cerca solo i nomi `getSharedAlbumsTestSharesOnly` e `sharedAlbumsMayUse` nel dex e in `mapping.txt`; li tiene in vita la regola `-keepclassmembers` di `core-common/consumer-rules.pro:17-21` anche se nessuno li chiama; il commento della `:885` ("Il cancello che li usa lo aggiunge P13 a questo passo") non e' stato seguito (P13, punto aperto 5). Per D12 invece c'e' la prova nel bytecode (`IncomingGate.stateOf -> backupMayWriteTo -> TEST_ONLY`).
- **Confermato:** si'.
- **Correzione proposta:** la stessa lettura del bytecode per `AppAlbumWriteGate.blockFor` e `FlagsAlbumReadGate.mayRead` (chiamano `sharedAlbumsMayUse` e rispondono `TEST_SHARES_ONLY`), istanziati da `albums/`; un test JVM sul valore di `FeatureFlags.RELEASE`.

### R21. `linkCopyAsVersion` non porta con se' i riferimenti delle tabelle v6

- **Gravita':** bassa (la copia appena nata raramente ha questi riferimenti).
- **Dove:** `core-database/.../MediaCatalog.kt:1406-1439` ripunta album, copertine, backup, attivita', sessioni e "Libera spazio", poi toglie la riga della copia; `mergeIdentities` (`:2429-2437`) ripunta in piu' `album_rule`, `album_exclusion`, `album_suggestion`, `album_share_item` e le copertine dei viaggi.
- **Scenario (9):** una copia gia' in un'operazione di condivisione (`album_share_item`, chiave esterna differita) fa fallire la transazione; regole d'esempio ed esclusioni di quella copia si perdono in cascata.
- **Confermato:** si'.
- **Correzione proposta:** un solo `repointReferences(perdente, sopravvissuto)` usato da entrambi, e il caso in `MergeReferencesTest`.

### R22. La sezione Album della Diagnostica esporta solo `mounted`

- **Gravita':** bassa (la privacy regge: le chiavi fuori elenco si scartano), ma le prove F72-F93 contano sulla Diagnostica esportata.
- **Dove:** `app/.../albums/AlbumsDiagnostics.kt:36-48` (`manual`, `smart`, `shared`, `openShares`, `contributions`, `syncIncomplete`, `roundErrors`); `feature-diagnostics/.../export/ExportKeys.kt:45`, `:53` (ci sono solo `mounted` e `count`); `DiagnosticsExporter.kt:86` (filtro sulle chiavi ammesse).
- **Confermato:** si'.
- **Correzione proposta:** aggiungere le chiavi (sono numeri e segni) e un test come quelli delle altre sezioni.

---

## Informative

- **I1. Le voci non portano mai l'hash completo.** `ShareSteps.kt:414` crea `SharedAlbumChange.Item` senza `sha256`, anche se il KDoc (`core-albums/.../contract/Publishing.kt:73-85`) dice che "chi aggiunge lo conosce" e "chi legge lo confronta"; anche E-SH12 chiede l'attestazione senza hash (`ShareSteps.kt:203`). L'attestazione (D9) e "gia' nell'area comune" valgono quindi sempre per `qh1` e dimensione (L32): un file diverso nel mezzo, con la stessa chiave, si mostra al posto del media aggiunto. Mai un file privato.
- **I2. La porta vincolante puo' tenere un'operazione senza fine, con un motivo muto.** `CatalogMoveCompletionSink` lancia per "destinazione di un altro media" o "byte diversi" (`app/.../albums/CatalogAlbumPorts.kt:134-138`), aspettando che la scansione unisca; finche' non succede l'operazione e' in pausa "condizioni" (`NasOpsEngine.kt:496-500`), che la condivisione mostra come "in corso" (`AlbumTransferPorts.kt:165`, `ShareOrchestrator.kt:246`), con il doppio spazio di L74. Serve un codice della Diagnostica e un motivo visibile. Inoltre tutti gli spostamenti di un'operazione vanno in **un** piano di `core-nasops` (`ShareSteps.kt:119`), che accetta al piu' 10.000 elementi (`core-nasops/.../contract/Rules.kt:16`) contro i 20.000 di un album (`SharedAlbums.kt:246`): oltre, niente parte e il motivo e' "trasferimento non riuscito".
- **I3. La seconda chiave di C26 e' una conseguenza della prima.** `OpsAlbumMover` emette la `UserConfirmation` per qualunque piano che gli arriva con una `ShareConfirmation` giovane (`AlbumTransferPorts.kt:97-112`); `Phase7ArchitectureTest` ammette `ShareConfirmationIssuer` in tutto `:app` e in `feature-albums` (`core-common/src/test/.../Phase7ArchitectureTest.kt:150-156`). Oggi l'unico emittente e' il foglio; un'emissione nuova in `:app` passerebbe il test.
- **I4. La cartella di un album esistente e' un dato remoto.** Fa da destinazione degli spostamenti confermati (`ReplicaSharedAlbums.kt:326-330`), sempre nell'area comune del registro locale, e il foglio la mostra. I caricamenti dal telefono la accettano solo sotto "Album condivisi" (`AlbumFolderUploads.kt:152`, `app/.../backup/FiveBGraph.kt:229`): dopo E-SH16 verso una cartella fuori, ogni caricamento verso quell'album fallisce.
- **I5. Una riga del diario della condivisione che non si decodifica sparisce in silenzio** (`app/.../albums/RoomShareDiary.kt:57-59`, `mapNotNull`): l'operazione puo' chiudersi con un elemento ancora in viaggio, o non riprendere mai.
- **I6. Pubblicatore e scrittore.** `PublishRules.KEEP_HEADS` e `GRACE_DAYS` (`Publishing.kt:179-181`) non hanno chiamanti: le proprie teste e i segmenti non si puliscono mai; `Blocked` (oltre 32 file spazzatura) diventa `Failed` senza la lista (`NasAlbumPublisher.kt:172`); il riordino dei condivisi e' offerto (`feature-albums/.../contract/AlbumPolicy.kt:112`) e sempre rifiutato (`CatalogAlbums.kt:250-251`); `AlbumFolderWatch.blocked` non controlla `canWrite` (lo ricontrolla la pubblicazione).
- **I7. `toString()` di alcune righe v6.** `AlbumRuleEntity` (`core-database/.../entity/AlbumV6Entities.kt:47`) e' una `data class` senza `toString` sicuro e stampa `value` (centro del luogo, descrizione, periodo); `AlbumItemEntity`, `AlbumExclusionEntity` e `AlbumSuggestionEntity` stampano id e impronte. Oggi nessun log le stampa.
- **I8. Minori sui Doppioni e sui viaggi.** `trip_dismissed` e' uno SHA-256 senza segreto di tipo, giorni e luoghi: chi li conosce verifica lo scarto (L87, coerente). `CopyPlace.OTHERS` vuol dire "area sconosciuta" (`AlbumCatalog.kt:259-267`) e la query dei doppioni non confronta l'account (`AlbumQueries.kt:210-217`): oggi lo garantiscono il catalogo (solo share dell'account) e `NasOperations` (sola lettura); un controllo esplicito sarebbe meglio.
- **I9. Utility e Doppioni non hanno recinzione** (`FeatureFlags.kt:37`): "Elimina ovunque" e il cestino del NAS agiscono sulle share vere della release, con la conferma, sulle `NasOperations` della 4B. E' una scelta di progetto (D30), da non confondere con la recinzione degli album.
- **I10. `StateFiles`, dettagli.** Un thread con il segno di interruzione fa fallire `FileChannel` e `Files.readAllBytes` (`ClosedByInterruptException`); su Android `retrying` riprova ogni `IOException` (fino a circa 310 ms) contro la nota "su Android non si riprova mai" (`StateFiles.kt:21-26`), e l'attesa gira dentro il lucchetto della striscia (`:129`, `:274-275`); `readBytes` non ha un tetto di dimensione.
- **I11. `AlbumsGraph.stop()` non aspetta un montaggio in corso** (`app/.../albums/AlbumsGraph.kt:97-117`, `:313-317`): un `build` che finisce dopo lo stop assegna `liveFlow` con un ambito gia' annullato (plausibile, finestra breve; `GalleryGraph.shutdown` ferma i lavori).
- **I12. Test a tempo (`e457ee28`).** I 31 file sono tutti in `src/test`: nessun codice di produzione cambiato, nessuna garanzia indebolita. Da sapere: `Attese.TETTO` passa da 20 s a 2 minuti, BK-13 rifa' la premessa fino a 5 volte, e `NoFixedWaitsTest` vieta i `withTimeout` con letterali ma non copre `delay` ne' `sleep`.

---

## Cose fatte bene

- **Spostamento in condivisione**: tre chiavi con un solo punto che emette la conferma, piano rifatto allo `start` con la stessa impronta, diario prima di ogni azione, chiavi dei trasferimenti prima delle porte, porta di completamento unica e vincolante, `attachMoved` sulla **stessa** riga in una transazione (identita' stabile per preferiti, nascosti, album e vettori), voce solo per un file che il catalogo vede adesso nell'area comune, un media in una sola operazione aperta, E-SH12 senza copie doppie, guardiano solo per i propri media che il telefono ha avuto, "Riporta" solo verso l'area privata e solo per media propri. Banchi: 2.000 + 600 giri senza evitamenti e un colpo a ogni chiamata, con le mutazioni rosse.
- **SD-1-SD-4**: corretti alla radice e non evitati, con i test di regressione dei giri che li avevano trovati e il riferimento di P0 allineato.
- **Formato degli album**: ordine dei controlli rigoroso, nessuna allocazione da lunghezze dichiarate, chiave di ogni voce ricalcolata, `account` e autore legati al firmatario, id legato all'account del creatore, percorsi con il confronto NFKC, `entryBytesOrNull` che impedisce allo scrittore di pubblicare cio' che il lettore rifiuterebbe, pubblicazione solo con `publishExclusive` nella propria cartella e segmenti gia' presenti confrontati byte per byte.
- **Permessi**: una tabella pura usata dai due lati, livello all'ora della voce con la storia, `addedBy` che non cambia, chiusura che ferma le voci dopo, "Gli altri guardano" prima del primo cambio di livello; ogni schermata dichiara che li fa rispettare l'app.
- **E-S11**: nessuna definizione d'album, vera o falsa, produce una conferma, un piano o una chiamata alle porte; provato anche sul grafo vero dell'app (`AlbumsIntegrationTest`).
- **Nascosti**: fonte unica che fallisce chiusa (id sconosciuto, errore, gruppo contagiato), filtro finale prima di ogni scrittura degli smart e dei viaggi, proposte di viaggio ricontrollate a ogni cambio dell'istantanea, Cartelle NAS senza nomi, widget di prova fuori dalla release.
- **Doppioni**: solo cestini, identici solo con l'hash completo, nessuna area di altri, il NAS prima del telefono, "Elimina subito" a due tocchi solo per voci proprie.
- **Casa**: una tabella sola, letta e scritta da un solo file, `toString()` sicuri, log a formato fisso, controlli per riflessione sulle API pubbliche.
- **Recinzione D30**: costante accesa nella release, controllata prima di ogni altro cancello, su scrittura, piano (area comune e privata) e lettura.
- **`StateFiles`**: scrittura atomica con `fsync` e `ATOMIC_MOVE`, nessun temporaneo lasciato, cancellazione che dice la verita', errori senza percorsi; prove che diventano rosse togliendo la correzione e nessuna attesa scritta a mano.
- **Schema v6**: migrazione mai distruttiva, query tutte parametrizzate e a blocchi, `MergeReferencesTest` che obbliga a classificare ogni tabella nuova.

## Da provare sul campo

Solo con gli account e le share di prova (`test`, `test2`, `test-comune`), sull'S25 e sul Tab S9.
- **R1** (con un pubblicatore ostile di debug, mai sulle share vere): un album nato chiuso con `keep_copy` e un album chiuso, riaperto e richiuso dal telefono di `test`; sul tablet di `test2` contare gli album personali creati, poi revocare il telefono e controllare che restino (oggi atteso: restano).
- **R2 (F84, F92)**: dal PC togliere un segmento della testa piu' recente di un album di prova; sul tablet, in 30 minuti, controllare la Diagnostica (`syncIncomplete`), il traffico dell'app e se un secondo album riceve una modifica fatta dopo. Variante: una testa resa illeggibile dal PC.
- **R3 (F86)**: a "Gli altri guardano" e a "Gli altri aggiungono", dal tablet di `test2`, ⋮ su una foto del creatore aperta dall'album: "Elimina", "Sposta" e "Rinomina" non devono esserci (oggi ci sono); Doppioni simili "Ovunque" su uno scatto in un album condiviso.
- **R4 (F73)**: un video nascosto grande sul NAS di prova: Raccolte → Cartelle NAS → Spazio e Cestino, `uiautomator dump`: nessun nome.
- **R5 (F76)**: Doppioni aperti, poi dal PC togliere la copia proposta "da tenere", poi "Pulisci": cosa va nel cestino.
- **R6**: dismettere l'emulatore di `test` dopo che ha creato un album condiviso: l'album deve restare sul tablet (oggi atteso: sparisce).
- **R8**: durante F79, tenere aperto dal PC in esclusiva un file appena pubblicato nella cartella dell'album; controllare che il media resti lo stesso (preferito e album personale collegati).
- **R16**: un album smart con il nome della citta' di casa: leggere `smart:place` nei dati personali (debug) e confrontare il centro con `home_estimate`.
- **R22**: Esporta la Diagnostica dopo F72 e F79: devono esserci i conteggi degli album.
- **Il resto, come previsto**: F72-F93 di `docs/decisioni.md` ("Fase 7 P13"), in particolare F79-F83 (spostamento, interruzioni, spazio, conflitti, "Riporta"), F84 e F92 (finti album), F86 (permessi, col tablet) e F73 (Nascosti ovunque), dopo F29 della Fase 5.

## Checklist della Fase 7 (`docs/sicurezza.md` §7, "Fase 7")

- [ ] **Nascosto mai in nessuna superficie del registro ne' nel widget di prova (F73).**
  - Regge sulle superfici del registro (risposta 3).
  - Non regge su Spazio e Cestino di Cartelle NAS, fuori dal registro (R4), ne' sui titoli dei viaggi gia' calcolati (R16). Manca F73.
- [ ] **Condivisione con conferma esplicita e conteggio; lo spostamento parte solo da quella conferma.**
  - Regge in lettura (risposta 4); la seconda chiave e' derivata (I3). Mancano F84 e F92.
- [ ] **Nessuna definizione d'album, vera o falsa, produce un'azione sui file.**
  - Regge per i file (risposta 4). Una definizione falsa produce pero' scritture nei dati personali di tutti (R1).
- [ ] **La UI dichiara che permessi e Nascosti sono protezioni dell'app; avvisi E-SH9 ed E-H2.**
  - Regge in lettura.
- [ ] **Permessi rispettati a tutti e tre i livelli, lato scrittore e lato lettore, ogni riga del §2.4.**
  - Regge per le righe a doppio controllo (giudizio 3).
  - Non regge per "Eliminare il file" e "Nuova versione" (R3); il lettore e' piu' largo in R14; l'ora delle voci si aggira (R7). Manca F86.
- [ ] **Codec `album` ostile: firme, id legato all'account, tetti, percorsi; dispositivi revocati ignorati.**
  - Regge il codec (risposta 1).
  - Non reggono il volume e l'isolamento del giro (R2, R13); un dismesso vale come un revocato (R6).
- [ ] **Banco delle 500 interruzioni sullo spostamento passato (F80).**
  - Sulla JVM si' (P5 e P4 senza evitamenti); sui legami di `:app` il banco e' piccolo (L109). Manca F80.
- [ ] **"Casa" mai fuori dal dispositivo ne' nei log; percorsi d'origine solo nell'area privata.**
  - Regge per la casa e per i percorsi d'origine.
  - Coordinate vicine a casa nei dati personali per altre strade (R16).
- [ ] **Pulizia dei doppioni sempre nei cestini, mai l'ultima copia privata.**
  - Sempre nei cestini: si'.
  - "Mai l'ultima copia privata" vale alla proposta, non al tocco (R5); elementi di album e simili "Ovunque" (R3); R18.
- [ ] **Log e Diagnostica senza titoli, cartelle, luoghi, coordinate, account.**
  - Regge (nessun log nuovo con dati dell'utente; la Diagnostica degli album ha solo numeri). La Diagnostica esportata e' pero' troppo povera (R22); I7.
- [ ] **Alla chiusura la scelta per gli altri e' di chi chiude, esplicita, e non tocca nessun file.**
  - Regge; la chiusura non e' definitiva e `keep_copy` si abusa (R1).
- [ ] **La sorgente va nel cestino solo dopo che il catalogo conosce la destinazione; nessuna voce per un file fuori dall'area comune; guardiano solo per i media che il telefono ha avuto.**
  - Regge (giudizio 1), con R8 (plausibile), R9 e I2.
- [ ] **Album smart: nessun nascosto, familiari e screenshot mai sicuri, il motore scrive solo righe sue, ricalcolo incrementale uguale a quello da zero.**
  - Regge in lettura. Un errore di lettura svuota (R17); R19.

Delle voci "per ogni fase, sempre":
- nessun permesso Android nuovo; un solo componente nuovo, il widget di prova, solo nel manifest di debug e non esportato; nessuna dipendenza ne' host nuovi (confermato);
- questa revisione vale come passo di `security-review` della Fase 7 e delle correzioni trasversali "File di stato locali" e "test a tempo". Va insieme alla revisione funzionale di fine fase.

**Prima delle prove sul telefono** si propone di correggere:
- **R3, R4 e R5**, perche' F86, F73 e F76 li incontrano subito e darebbero esiti sbagliati;
- **R2**, perche' F84 e F92 (finti album e 24 ore di sincronizzazione) lo toccano da vicino, e la variante dal PC non chiede chiavi;
- **R22**, perche' le prove leggono la Diagnostica, ed **R8**, piccolo e sul cammino di F79.

**Prima del consenso di Q7 (F93, share vere)** vanno chiuse anche R1, R6, R7, R9, R12 e R20: con la recinzione accesa oggi toccano solo le share di prova. R10 e' piccola e riguarda l'azzeramento di un dispositivo perso: conviene correggerla subito.
