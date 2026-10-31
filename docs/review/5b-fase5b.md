# Revisione di sicurezza della Fase 5B (versioni, Libera spazio, integrazioni) e delle correzioni dopo la 5A

| | |
|---|---|
| **Oggetto** | i commit da `ea788bf` a `eca476e`: P0-B, P8-P14 della 5B; R1-R7 della 5A; le correzioni funzionali ON, RE, GA, NA, BK; F1-F3 della prova delle interruzioni; "Separa" fra dispositivi. In particolare: `core-backup` (`free/`, `versions/`, `corrections/`, `tasks/`, il cammino `INCOMING` di `upload/SafeUploadEngine`, `contract/PhoneConfirmation`), `core-nas` (`archiveVersion`/`restoreVersion`, `VersionPaths`, `SmbNasFileOps.renameVersion`, RE-9 in `SmbNasSessionManager`), `core-metadata` (`strip/`, `write/`), `core-mediastore` (`MediaStorePhoneMediaRewriter`, richieste di sistema), `feature-integrations` (selettore, provider dei documenti, ricezione, "Modifica con…", condivisione senza posizione, `FileProvider`), `feature-backup` e `feature-viewer` (fogli della 5B), `core-security` (`AppLockController.isUnlockedNow`), `core-family` (`startOver`, inviti manuali, azioni dell'admin), `core-personal` ("Separa"), `core-nasmedia` (NA-4, franchigia), `feature-diagnostics` (`DiagnosticsRecorder`), `:app` (`backup/FiveB*`, `backup/IncomingQueue`, `integrations/`, `di/FiveBModule`, `work/LanConstraints`, `graph/StageSupport`), e le vie della Fase 3 e della 4B che toccano ancora i file del telefono (`LibraryStates`, `ViewerController`). |
| **Riferimenti** | `docs/fase5-progetto.md` (§0.1 D2, D12, D13, D17-D35; §2.4, §2.5, §2.7; §6, §6.4), `docs/sicurezza.md` (C10, C12, C13, C17, C18, C19, §4-bis, L37-L44, checklist della Fase 5), `docs/decisioni.md` (sezioni della 5B, delle correzioni funzionali, di F1-F3, di "Separa"), `docs/review/5a-fase5a.md` (formato), `docs/review/funzionale-fasi-1-5a.md`. |
| **Attaccanti e guasti** | (a) un **dispositivo di famiglia compromesso** (stesso account o altro account; un familiare che mette file nell'area comune); (b) un'**app di terze parti** sul telefono (intenti espliciti verso i componenti esportati, URI ostili, sovrapposizioni, un editor scelto per "Modifica con…"); (c) **dati letti dal NAS** (nomi, file in `Versioni/`, voci personali, file costruiti apposta); (d) **file normali di dispositivi diversi** (droni, dashcam, fotocamere che scrivono la posizione dove l'app non guarda); (e) **guasti ed errori umani** (morte del processo, Keystore guasto, dialoghi di sistema lasciati aperti, un telefono che passa di mano, regole del backup che rimandano un giro). |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice di produzione. Nessun NAS vero, nessun telefono, nessun `adb`. |
| **Data** | 20/10/2026 |
| **Metodo** | Lettura del codice: ogni via che toglie o riscrive un file del telefono (`SafeFreeSpace`, `DefaultVersionKeeper.restoreOriginal`, `DefaultCorrections`, `MediaStorePhoneMediaRewriter`, e le vie della Fase 3 e della 4B ancora vive), ogni porta esportata (manifest, `CallerGuard`, `DocumentIdTable`, `EntryGate`, `AppLockController`), ogni via che scrive sul NAS (versioni, scambi, `INCOMING`) e lo stripper della posizione con i suoi lettori. Tre revisori di supporto, in sola lettura, hanno letto in parallelo le versioni e i file in arrivo, la posizione e le correzioni nel file, e il codice di fiducia toccato dalle correzioni; **ogni loro voce riportata qui e' stata ricontrollata nel codice**. Due **test temporanei**, eseguiti con `--max-workers=2` e poi cancellati insieme ai risultati e alle classi compilate: (1) sul custode vero (`DefaultVersionKeeper` su `VersionWorld`, core-backup) per R3; (2) sullo stripper vero (`DefaultLocationStripper` con i lettori di serie, core-metadata, Robolectric) per R1 e R2. Esiti sotto le voci. Nessun file del repository e' rimasto modificato a parte questo documento. |
| **Stato** | **R1-R19 corrette** (20/10: «Correzioni 5B: posizione e Condividi», «configurazione e Diagnostica», «versioni e correzioni», «Libera spazio», «integrazioni», «Ricevuti in coda» in `docs/decisioni.md`, ognuna con il suo "Stato" qui sotto). Le informative: I9 chiusa (LA-5 della verifica funzionale, passo di `tools/check.ps1`); I1-I8 e I10-I12 aperte, ognuna con chi la chiude. Riconciliazione del 31/10 (Fase 10 P11): `docs/review/10-chiusura.md`. |

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 2 | R1 "senza posizione": un video con una traccia di metadati o un contenitore fuori elenco esce con il GPS · R2 "senza posizione": una foto con la posizione nell'XMP sotto un nome che non e' `GPS…` (droni DJI) o dove il camminatore non guarda esce con il GPS |
| Media | 5 | R3 "Ripristina originale" riscrive sul telefono una versione che non e' sul NAS, che va persa ("Non si perde niente") · R4 "Condividi" dalla selezione della Libreria (e dal viewer senza backup) ignora "Rimuovi la posizione" · R5 un file in arrivo verso una share chiusa ferma tutto il backup privato · R6 dopo "Configura di nuovo" con un altro account i dati del vecchio account restano, si vedono e si pubblicano nell'area privata del nuovo · R7 "Configura di nuovo" dalla pagina "fuori dalla famiglia": un tocco, nessuna conferma, e dopo un guasto del Keystore distrugge la chiave di famiglia dell'admin |
| Bassa | 12 | R8 il selettore non si difende dalle sovrapposizioni · R9 riscrittura del telefono: nessun recupero dopo la morte del processo · R10 "Ripristina originale" di un video: rilettura oscurata · R11 "Modifica con…" di un solo-NAS: il tipo si controlla su byte diversi da quelli caricati · R12 la correzione "togli il luogo" lascia la posizione nell'XMP · R13 un XMP costruito apposta blocca un thread per ore · R14 due "Salva sul NAS" degli stessi byte verso destinazioni diverse diventano uno · R15 un file in arrivo legato a un media esistente gli mette i segni di problema e ne blocca il backup · R16 uno scambio senza conferma puo' partire da un file dell'area comune cambiato da un altro account · R17 la franchigia D32 vale per `fileId` e per sempre · R18 il rinnovo della richiesta senza QR non usa il GUID gia' fissato e riporta a 0 la versione minima · R19 `startOver` ignora il fallimento della distruzione delle chiavi |
| Informativa | 12 | I1-I12 (sotto) |

**Confermate e plausibili.** Delle 19 voci da R1 a R19, **17 sono confermate** (dal codice, e R1, R2, R3 anche da un test temporaneo) e **2 plausibili** (R10: dipende da come Android oscura la posizione dei video; R19: serve un errore del Keystore). Per R1, R2, R4, R8, R11 e R17 il difetto nel codice e' certo, la frequenza nel mondo reale e' stimata.

**"Libera spazio" regge** (risposta 1): dal suo flusso nessun media lascia il telefono senza una copia privata riletta per intero, adesso, con il timbro ricontrollato prima di ogni dialogo, validita' monotona di 6 ore nello stesso avvio, lotti scritti nel diario prima di uscire e due gesti per il definitivo. **Ma c'e' un'altra via che toglie dal telefono byte non al sicuro: R3** ("Ripristina originale" riscrive la riga del telefono senza chiedersi se la versione che c'e' e' sul NAS). Restano I1 (i byte del telefono non si rileggono) e I2 (il dialogo di sistema non scade).

**La parte piu' debole e' "senza posizione"** (R1, R2, R4). La promessa e' "si dimostra, non si presume", con lettori indipendenti. In pratica per i video l'estrattore e il secondo lettore sono lo stesso `MediaMetadataRetriever`, e il lettore grezzo e' lo stesso piano dello stripper: dove lo stripper non guarda (tracce di metadati non in elenco, XMP con nomi diversi da `GPS…`, segmenti dopo la prima scansione), nessuno guarda, e la copia esce come "senza posizione, verificata". In piu' la selezione della Libreria condivide gli originali anche con l'impostazione accesa.

**Le porte esportate reggono** (risposta 2): radici ed elenchi solo per il sistema, un documento solo con il permesso su quell'URI (anche `getType`), id opachi da 144 bit con scadenza e revoca, nascosti filtrati a ogni elenco e apertura, sola lettura, blocco valutato adesso, `file://` e provider propri mai aperti, niente sul NAS senza "Salva sul NAS". Manca la protezione dalle sovrapposizioni nel selettore (R8).

**D2 regge per le versioni** (risposta 3): rinomine sullo stesso handle con il timbro, mai una sostituzione, una sola estremita' in `Versioni/`, mai l'area dell'app; F3 e' chiusa. Le correzioni dopo la 5A toccano la fiducia in due punti nuovi e scoperti: `startOver` (R6, R7, R19) e il rinnovo della richiesta (R18).

---

## Risposte al perimetro

1. **"Libera spazio" non toglie mai dal telefono un media senza una copia verificata adesso da una rilettura fatta qui.**
   - **Il piano** nasce solo dal catalogo locale (E-S11): proprio, area privata dell'account (share uguale a `privateShare`, area `PRIVATE`), hash completo noto, `verified_at`, percorso di media; per un media con piu' versioni anche il custode delle versioni deve dire "al sicuro" (due fonti) (`FreeSpaceEvaluator.kt:126-212`). Una scelta dell'utente non si allarga (`SafeFreeSpace.kt:189-194`).
   - **La conferma** si controlla contro il piano **ricalcolato adesso** (`SafeFreeSpace.kt:227-230`); l'impronta copre filtro, modo, media, versione, riga e timbro della copia (`FreeSpace.kt:153-169`).
   - **La verifica sul momento** rilegge per intero ogni copia (timbro all'apertura e dopo la lettura, dimensione della versione), confronta lo SHA-256 **letto** e lo passa a `markVerified`, che decide da se' (`SafeFreeSpace.kt:389-397`, `MediaCatalog.kt:772-786`). Un errore del NAS ferma il giro.
   - **Validita'**: 6 ore di orologio monotono dalla creazione del giro, stesso conteggio dei riavvii (o, se non si legge, stesso processo); un monotono che torna indietro e' un riavvio (`SafeFreeSpace.kt:671-677`). Dal P14 la validita' sta nel diario e non si allunga (`RoomFreeSpaceDiary.kt:104-106`).
   - **Lotti**: al massimo 2.000, scritti `IN_DIALOG` **prima** di uscire; un lotto senza risposta si ripropone solo dopo la prova del telefono; mai due lotti aperti; una riga gia' consegnata in un altro giro non entra in un piano (`SafeFreeSpace.kt:401-476`, `:157`, `:170`).
   - **Controllo finale** prima di ogni dialogo: regole dal catalogo di adesso, riga e timbro del telefono (con la generazione), timbro di ogni copia riletta (`SafeFreeSpace.kt:483-541`).
   - **Due gesti** per "Libera subito": il contratto (600 ms, `PhoneConfirmation.kt:134-141`), un secondo pannello con il pulsante in un altro punto e spento per 600 ms (`FreeSpaceSheets.kt:131-195`), poi il dialogo di `createDeleteRequest`.
   - **`PhoneConfirmation` solo dalla UI**: costruttore `internal`; nasce in `ConfirmTaps` (feature-backup), nei fogli Versioni e Correggi e in `ReceiveViewModel.save`, sempre dal gestore di un tocco; `:app` non ne costruisce. `TestConfirmations` sta in `core-backup/src/main/.../testing/`, ma `check.ps1` vieta i package `testing` nel dex di release.
   - **Esiti in dubbio**: un esito `Unknown` (app uccisa) si risolve solo con la prova del telefono (assente = liberato, presente = rimasto, altrimenti in dubbio, mai riproposto); `settle()` gira all'avvio e dopo ogni lettura del telefono (`SafeFreeSpace.kt:603-645`, `FiveBGraph.kt:273-295`).
   - **D13 in debug**: la recinzione vale nel piano (cartella del catalogo) e di nuovo, sulla riga viva, al controllo finale (`MediaStorePhoneBytes.kt:117-126`); con D12, e con R2 della 5A in release, il cancello e' `TEST_ONLY` e "Libera spazio" non c'e' (`FreeSpaceEvaluator.kt:127-133`).
   - **Altre vie verso il cestino o la cancellazione**: il viewer manda "Elimina solo dal telefono" al foglio della 5B quando il backup c'e' (`ViewerOverlay.kt:719`, `BackupActionsController.kt:77`); il cestino diretto resta per i media solo sul telefono. **La selezione della Libreria no** (I3). **"Ripristina originale" riscrive la riga del telefono** con un'altra versione: e' R3.
   - **Restano**: R3, I1, I2, I3.
2. **Componenti esportati.**
   - **Manifest**: solo `PickerActivity` (`GET_CONTENT`, `PICK` di immagini e video), `ShareReceiverActivity` (`SEND`, `SEND_MULTIPLE`), `MediaDocumentsProvider` (`MANAGE_DOCUMENTS`, `grantUriPermissions`); il `FileProvider` non e' esportato e serve solo `cache/share/` e `cache/edit/`. Nessun permesso nuovo.
   - **Senza un tocco o un grant non si ottiene niente**: radici ed elenchi solo per il sistema o per il processo stesso; un documento solo con un permesso di lettura su **quell'**URI, anche per `getType` che non passa dai permessi del provider (`CallerGuard.kt:59-78`). Le vie della classe base (`openTypedAssetFile`, `call`, alberi, ricerca, recenti) finiscono nei metodi controllati o in "non supportato". Il selettore consegna solo dopo un tocco su un elemento o su "Scegli".
   - **Id, percorsi, nascosti**: token casuali da 144 bit, scadenza a scorrimento (30 minuti di sfoglio, 6 ore se consegnati), revoca all'azzeramento (`DocumentIdTable.kt`); un id sconosciuto, scaduto o non opaco e' sempre `FileNotFoundException`. I nascosti si filtrano con la vista di adesso a ogni elenco, descrizione, apertura e consegna, e l'id di un media nascosto dopo si revoca (`LibraryMediaDocuments.kt:97-181`). Sola lettura (modo diverso da "r" = `SecurityException`). Restano visibili i **nomi** delle cartelle che contengono solo nascosti (I7).
   - **Sul NAS** da un'altra app si scrive solo con la ricezione, dopo il tocco su "Salva sul NAS" (risposta 4).
   - **`EntryGate` e blocco**: `isUnlockedNow` valuta il blocco adesso (impostazioni non lette = chiuso, timeout misurato adesso, orologio che torna indietro = chiuso) (`AppLockController.kt:153-168`); selettore e ricezione rileggono il cancello a ogni ritorno, al tocco e prima di consegnare o salvare. **La tolleranza di 2 minuti** dopo uno sblocco d'ingresso vale per **tutte** le porte (anche per un'altra app che apre il selettore con un intento esplicito subito dopo), e se la `MainActivity` torna entro il timeout dell'utente la trova sbloccata: e' la scelta dichiarata in C17, non un difetto. Il provider nasce con le porte nulle (`NOT_READY`) finche' `:app` non le installa.
   - **URI ostili** (E-X2): `file://`, schemi diversi da `content://`, URI senza autorita' e provider dell'app (anche `0@pacchetto`, maiuscole, il suffisso di debug) si rifiutano **senza aprirli** (`IncomingOrigins.kt`); tipo dai byte, tetti prima e durante la copia, tempi massimi su `query`/`getType`/`read` di un provider che non risponde (`BlockingGuard`), immagini "bomba" rifiutate prima di decodificare.
   - **Permessi URI**: condivisione solo lettura con `ClipData`, mai persistenti ne' per prefisso; "Modifica con…" lettura e scrittura su un solo URI e revoca prima di cancellare la copia; Samsung Gallery solo lettura con il pacchetto esplicito. Nessuna chiamata a `grantUriPermission` verso altre app.
   - **Restano**: R8 (sovrapposizioni nel selettore), I4 (il selettore nei recenti del task dell'altra app), I7. La prova con `adb` (F39) non e' fatta.
3. **Versioni e scambi.**
   - **Mai una sostituzione**: `renameVersion` sullo stesso handle, timbro obbligatorio confrontato prima del `SET_INFO`, rinomina senza sostituire, cartelle e punti di rianalisi rifiutati, nessun `mkdir` (`SmbNasFileOps.kt:195-226`).
   - **Mai fuori dalla share o da `Versioni/`**: `VersionPaths.requireArchive`/`requireRestore` vogliono una sola estremita' sotto `Versioni/` e l'altra un percorso di media, mai `.mycloudgallery/` (NFKC e maiuscole); i percorsi vengono solo da copie private, presenti, verificate, dello **stesso** media nella share privata (`VersionPlanning.kt:25-53`, `:125-147`).
   - **"Al sicuro" (F3)**: dopo una risposta persa il catalogo riceve il timbro **atteso** o quello dei byte riletti con lo SHA-256 della versione, mai quello trovato; `relocateKeepingVerification` solo dopo una rinomina sullo stesso handle o un ritrovamento provato. Resta I10 (un `fileId` nullo).
   - **Franchigia D32**: nella guardia dei cambiamenti di massa per `fileId` (R17).
   - **D13 in debug**: le versioni nascono solo per media sotto `mcg-campo/` e la loro `Versioni/` sta dentro (`NasBackupOps.kt:251-292`, `NasVersionEngine.kt:127-132`). La sorgente di un ripristino non e' recintata (I12). La regola del prefisso `mcg-` vale anche in release (I8).
   - **Cancello** prima di ogni passo; D12 e R2 della 5A valgono per versioni, scambi e correzioni (tramite `AppBackupGate`).
   - **Restano**: R16 (scambio senza conferma da un dato del NAS), R17, I10, I11, I12.
4. **Cammino `INCOMING`.**
   - **Caricamento sicuro**: la copia locale si rilegge e deve dare lo SHA-256 scritto al tocco; temporaneo proprio, rilettura completa dal NAS, pubblicazione senza sostituire con "nome (n)", collegamento solo dopo la rilettura (`SafeUploadEngine.kt:659-711`).
   - **Cancello** della share di **destinazione**: famiglia pronta, solo area privata o comune dell'account, **D12**, **R2 della 5A**, emergenza, congelamento, sola lettura, pausa (`FiveBAdapters.kt:335-353`); la radice di D13 per la cartella (`UploadPorts`, `IncomingQueue.kt:82-94`). `check.ps1` non prova questo secondo cancello nel bytecode di release (I9).
   - **Nessuna sostituzione**; nome dell'altra app ripulito e validato da `SharePath`; il percorso locale nasce solo dall'hash (`IncomingQueue.kt:181-190`).
   - **La copia locale si toglie solo dopo** `DONE`/`LINKED_EXISTING`, prima del passo terminale del diario (`SafeUploadEngine.kt:519-526`, `IncomingQueue.kt:234-249`); `GONE` e `FAILED` la lasciano.
   - **Nell'area comune dalla 5B**: le versioni e le correzioni non ci scrivono (`COMMON_AREA_NOTICE`); "Salva sul NAS" **si'**, per scelta dell'utente e con l'avviso (D24), e solo su una share di prova fino a Q3. Il default e' l'area privata.
   - **Restavano**: R5 (un arrivo verso una share chiusa ferma il backup privato), R14, R15 (tutte corrette il 20/10).
5. **Posizione tolta.**
   - **Regge**: con la posizione da togliere non esce mai l'URI del telefono; una condivisione e' tutta o niente; il tipo si decide dai byte; il JPEG nato da un HEIC si ricontrolla; servono almeno due lettori e un lettore che non legge non assolve; la copia letta con la posizione si cancella (`LocationFreeShare.kt:84-137`, `DefaultLocationStripper.kt:41-155`).
   - **Non regge**: R1 (video), R2 (foto), R4 (selezione della Libreria e viewer senza backup). Per i video l'estrattore e il secondo lettore sono lo stesso `MediaMetadataRetriever` (`VideoProbes.kt:24-32`, `LocationRecheck.kt:107-133`) e il lettore grezzo e' lo stesso piano dello stripper (`LocationRecheck.kt:149-171`): i "lettori indipendenti" condividono i punti ciechi.
6. **Correzioni nel file.**
   - **Regge**: solo JPEG, PNG e WebP decisi dai byte; destinazione nuova in esclusiva, sorgente mai toccato; la copia corretta si rilegge con l'estrattore e si decodifica; niente si tocca prima; ordine catalogo → lavoro `VERSION` → telefono; la prima riga del telefono si rilegge e deve avere lo SHA-256 della versione al sicuro (`DefaultCorrections.kt:135-216`); il riscrittore scrive con `rw`, accorcia, sincronizza, rilegge e ripristina (`MediaStorePhoneMediaRewriter.kt:69-160`).
   - **Restano**: R9 (morte del processo durante la riscrittura), R12 ("togli il luogo" lascia l'XMP), e in R3 il riscrittore che non controlla cosa c'e' adesso nella riga.
7. **Codice di fiducia toccato dalle correzioni.**
   - **Invito manuale (ON-1)**: versione minima da 0 in su, impronta canonica e di 26 caratteri, `TrustAnchor.create` obbligatorio; id e impronta digitati diversi dallo stato della famiglia = `INVITE_OTHER_FAMILY` (`InviteSupport.kt:64-70`, `OnboardingViewModel.kt:862-868`).
   - **Rinnovo della richiesta (ON-2)**: solo da `Pending`, chiave nuova, finestra M2 invariata; dalla pagina d'attesa si raggiunge solo la rotta del rinnovo (F5 resta chiusa). Ma senza QR non usa il GUID gia' fissato e riporta a 0 la versione minima (R18).
   - **"Configura di nuovo" e "Rientra" (ON-4, ON-5)**: dalle Impostazioni con un foglio di conferma; **dalla pagina "fuori dalla famiglia" con un tocco solo** (R7); i fallimenti della distruzione delle chiavi si ignorano (R19); i dati locali del vecchio account restano e passano al nuovo (R6).
   - **Impronta digitata (ON-12b)**: gli alias O→0, I/L→1 sono fuori dall'alfabeto, servono solo al confronto, nessun confronto per prefisso (`Base32.kt:84-95`, `KitAdminRecovery.kt:139-157`).
   - **`updateNasAddress`, `updateHomeUla`, `updateCommonExclusions` (RE-5, ON-8)**: passano da `adminContext` (dispositivo approvato e cassaforte aperta) e da un gesto; indirizzo solo IP letterale; il GUID si confronta prima dell'autenticazione al nuovo indirizzo; gli altri telefoni non adottano da soli l'indirizzo firmato. `adminContext` non guarda il segno admin del registro: conta solo chi ha la chiave di famiglia (vedi R19).
   - **RE-9**: senza password nessuna connessione TCP e nessuna autenticazione; `CredentialsMissing` non apre l'interruttore; un guasto del Keystore non e' "password mancante" (`SmbNasSessionManager.kt:507`, `:575-583`, `:668-677`).
   - **`LanConstraints` (RE-11)**: i lavori partono anche su una rete non validata, ma il divieto resta nel cancello: la sonda 445 di `NetworkSettler` parte solo con `NasRoute.Allowed` ed e' legata alla rete scelta (`StageSupport.kt:35-38`, `TcpReachabilityProbe.kt:40`); le vie nuove della 5B (provider, ricezione, modifica, condivisione, `IncomingUploadWorker`) passano da `NasMediaReader` e dal gestore della sessione.
   - **Conferma del congelamento persistita (NA-4)**: chiave per account e share, consumata al primo giro completo, scade dopo 7 giorni di ora del NAS (che torna indietro = scaduta), un file illeggibile non da' sconti (`MassChangeGuard.kt:109-161`, `FileFreezeAcceptances.kt:51-63`). La coda di NA-1 non ricontrolla la conferma a distanza (I5).
   - **"Separa" fra dispositivi**: una sola forma canonica del valore `qh1:dimensione`, una forma diversa rifiuta il segmento senza eccezioni; un lettore vecchio prende il testo per un testo e non separa niente, senza perdere le altre voci; un'impronta che indica piu' media non risolve; il nascosto contagioso si salva su ogni copia e `separate` non lo toglie, quindi nessuna voce ostile rende visibile un nascosto (`PersonalCodec.kt:145-160`, `MediaCatalog.kt:1509`, `:1552-1569`). Costo per voce ostile: I6.
8. **Privacy.**
   - In release `GalleryLog` non ha un sink (quello di logcat sta solo in `src/debug`).
   - `DiagnosticsRecorder` scrive solo `DiagCode`, gravita', enumerati e numeri; ripete lo stesso esito al massimo ogni 6 ore (10 minuti per gli errori del NAS); il testo delle righe nasce nello schermo (`RecentEventsContributor`), mai nell'esportazione.
   - I contatori delle integrazioni (`IntegrationCounters`, `FlowCounters`) sono insiemi chiusi di codici fissi con numeri.
   - Notifiche del backup e della password senza dati dell'utente; `toString` dei tipi nuovi senza nomi, percorsi, id o impronte (`PhoneConfirmation`, `FreeSpaceItem`, `TrashItem`, `PersonalValue.Apart`, `SavedNas`…); nei log nuovi solo `simpleName`, codici e conteggi.
   - Il rischio di privacy della 5B non sta nei log ma nei file condivisi (R1, R2, R4) e nei dati di un altro account (R6).
9. **Altro.**
   - Corse e morte del processo: R9 (riscrittura), I2 (dialogo di sistema), R14 (idempotenza per versione).
   - Input ostili: R13 (XMP che blocca un thread), R11 (risultato di un editor).
   - Stati incoerenti: R5 (un arrivo che ferma il giro), R15 (segni di problema sul media sbagliato).

---

## Alta

### R1. "Senza posizione": un video con una traccia di metadati o un contenitore fuori elenco esce con il GPS

- **Gravita':** alta. La funzione esiste per non mandare la posizione di casa a chi riceve, e dice "verificato". Qui fallisce **aperta**: cio' che non conosce lo dichiara pulito.
- **Dove:**
  - `core-metadata/.../strip/IsoLocation.kt:59`: blocca solo le tracce con formato `camm`, `gpmd`, `gps `, `GPS `, `nmea`;
  - `:168-177` (`inspectMedia`): guarda solo la **prima** voce di `stsd` e non legge mai `hdlr`;
  - `:118-127` (`visit`): `meta` di primo livello stile HEIF (`iinf`/`iloc` con EXIF), `uuid` dei produttori diversi dall'XMP, `moof/udta`, i blocchi GPS dentro `free` (Novatek) non si aprono;
  - `LocationRecheck.kt:149-155`: il lettore grezzo e' **lo stesso piano** (`IsoLocation.plan(...).isClean`);
  - `LocationRecheck.kt:107-133` e `extract/VideoProbes.kt:24-32`: estrattore e secondo lettore sono entrambi `MediaMetadataRetriever`, che dice solo `METADATA_KEY_LOCATION` (`©xyz` e le chiavi di QuickTime).
- **Scenario (dispositivi diversi, d; familiare, a):** un MP4 di un drone DJI recente (traccia di metadati `djmd`), di una dashcam (sottotitoli `tx3g` con le coordinate), di una fotocamera Sony (`rtmd`), una traccia Apple `mebx` con chiavi di posizione dentro la voce di `stsd`, o una traccia GPS come seconda voce di `stsd`. Senza `©xyz` il piano e' vuoto, i tre lettori dicono "nessuna posizione", la condivisione parte come "senza posizione" e chi riceve legge il percorso con `exiftool -ee`. I video della fotocamera Samsung (`©xyz`) e di GoPro (`gpmd`) sono coperti.
- **Confermato:** **si'** nel codice, e con un test temporaneo sullo stripper vero con i lettori di serie (Robolectric): un MP4 con una traccia di formato `djmd` (la fixture `mp4WithTimedTrack` di P11 con un altro formato) da' `Stripped(video/mp4)`, mentre `camm` da' `StillHasLocation`. Che i file di quei dispositivi abbiano davvero il GPS li' e' plausibile (non verificabile qui).
- **Correzione proposta:**
  1. elenco di **cio' che si tiene**, non di cio' che si toglie: leggere `hdlr` di ogni `trak`; ammesse senza condizioni solo `vide`, `soun` e `tmcd`; `mebx` solo dopo aver aperto il suo `keys` e trovato zero chiavi di posizione; ogni altra traccia (`meta`, `text`, `sbtl`, `subt`, sconosciute) **ferma** la condivisione;
  2. controllare tutte le voci di `stsd`; fermare un `meta` con `iinf`/`iloc`, un `uuid` sconosciuto con dimensione non nulla, `moof` con `udta`;
  3. un lettore grezzo che **non** sia il piano dello stripper (per esempio una ricerca in tutto il file, fuori da `mdat`, dei nomi di posizione e delle stringhe ISO 6709);
  4. fixture e test per ognuno di questi casi; in F42 un video di drone o dashcam, se la famiglia ne ha.
- **Stato:** corretta (20/10, `docs/decisioni.md` «Correzioni 5B: posizione e Condividi»). Un video si ricostruisce per elenco di cio' che si tiene
  (`core-metadata/.../strip/IsoClean.kt`): restano `ftyp`, `moov` con `mvhd` e le sole tracce `vide`/`soun` (con `tkhd`, `edts`, `mdia` e le tabelle
  dei campioni), e in `mdat` i soli byte dei loro campioni; ogni altra traccia, `udta`, `meta`, `uuid`, `free` e riquadro sconosciuto diventa `free`
  azzerato, stessa dimensione. Frammentato, senza `moov`, senza audio ne' video, tabelle che puntano fuori da `mdat`: la condivisione si ferma. Il
  controllo finale e' un rilevatore scritto da zero (`strip/LocationTraces.kt`: EXIF GPS, nomi XMP, `©xyz`/`loci`, ISO 6709, NMEA, `GPS5`, tracce non
  audio/video) piu' l'estrattore e `MediaMetadataRetriever`. Test: `PosizioneNascostaTest.djiTracciaDjmdTolta`, `cammComeSecondaVoceTolta`,
  `goproGpmdTolta`, `appleMebxTolta`, `motionPhotoConLaPosizioneNelVideo`, `ilRilevatoreTrovaLaPosizioneInOgniFixtureNonPulita`; `IsoCleanTest`
  (core-metadata). Con lo stripper di prima i quattro video uscivano `Stripped` con la posizione (`gpmd` rifiutato).

### R2. "Senza posizione": una foto con la posizione nell'XMP sotto un nome che non e' `GPS…`, o dove il camminatore non guarda, esce con il GPS

- **Gravita':** alta (stesso motivo di R1).
- **Dove:**
  - `XmpGpsScrub.kt:14`, `:17`, `:20`: pulizia e ricontrollo vogliono `GPS` maiuscolo seguito da lettere (`GPS\w*`, `GPS[A-Z]`), senza ignorare le maiuscole; nessun altro lettore guarda l'XMP (l'estrattore non ne legge le coordinate, `ExifInterface` legge solo l'EXIF);
  - `ImageXmp.kt:69-123` (JPEG, si ferma a `:83`): il camminatore si ferma alla prima `SOS` e non guarda APP13 (Photoshop, EXIF 0x0422 o XMP 0x0424), i segmenti fra le scansioni di un JPEG progressivo, i dati dopo `EOI`; ogni pezzo di "extended XMP" si pulisce **da solo** (`:98-99`), quindi un attributo spezzato fra due pezzi non lo vede nessuno;
  - `ImageXmp.kt:145` (PNG): XMP in `tEXt`/`zTXt` con la parola chiave `XML:com.adobe.xmp` ignorato; un secondo chunk `eXIf` non lo legge nessuno;
  - l'XMP dentro l'EXIF (IFD0, tag 700) lo riscrive `ExifInterface` com'e'; `ImageLocationStrip.kt:126`, `:143`: la parte video di una motion photo si cerca dal primo `ftyp` e un MOV senza `ftyp` non si riconosce.
- **Scenario (d):** una foto di un drone DJI. `ExifInterface` toglie l'EXIF GPS, ma nell'XMP restano `drone-dji:GpsLatitude`, `drone-dji:GpsLongitude` (e `GpsLongtitude`) e le altitudini: la condivisione esce "senza posizione, verificata". Per una riga del telefono nemmeno la redazione di Android li toglie (conosce `exif:GPS*`). Gli altri casi: file elaborati da Lightroom o Photoshop (APP13, XMP esteso), PNG da strumenti che usano `tEXt`.
- **Confermato:** **si'** nel codice, e con il test temporaneo: un JPEG delle fixture con l'XMP `drone-dji:GpsLatitude="+45.4643"` da' `Stripped(image/jpeg)` e le coordinate sono ancora nel file consegnato. Gli altri sotto-casi sono confermati nel codice; che `exiftool` li legga e' l'oracolo previsto da F42 (plausibile).
- **Correzione proposta:**
  1. ricostruire le immagini per elenco di cio' che si tiene (JPEG: SOI, DQT, DHT, SOF, DRI, SOS con i dati, EOI, ICC, piu' un EXIF minimo nuovo senza GPS; PNG: IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, iCCP, pHYs; WebP: VP8, VP8L, VP8X, ALPH, ANIM, ANMF, ICCP); riattaccare il video di una motion photo solo se dichiarato nell'XMP e ripulito;
  2. nel ricontrollo grezzo un rifiuto prudente, senza maiuscole, su `gps`, `latitude`, `longitud`, `longtitude` in qualunque nome di proprieta' e su un'intestazione TIFF con il tag 0x8825, in tutto il file fuori dai dati compressi; riassemblare l'XMP esteso per GUID prima di cercare;
  3. decidere e scrivere in L41 se "posizione" comprende i nomi di luogo testuali (`photoshop:City`, `Iptc4xmpCore:Location`, IPTC-IIM): oggi restano.
- **Stato:** corretta (20/10). JPEG, PNG, WebP e il JPEG nato da un HEIC si ricostruiscono per elenco di cio' che si tiene (`strip/ImageRebuild.kt`):
  i dati dell'immagine, il profilo colore e un EXIF scritto da zero con orientamento e data (`strip/MinimalExif.kt`); fuori l'XMP intero (anche esteso),
  APP13, MPF, commenti, `tEXt`/`zTXt`/`iTXt`, `eXIf` di prima, cio' che segue EOI (tranne il video di una motion photo, ricostruito e dichiarato da un
  XMP nuovo). I nomi di luogo testuali quindi non escono piu' (scritto in L41). Il rilevatore finale riassembla l'XMP esteso per GUID e guarda i nomi in
  ogni spazio dei nomi. Test: `PosizioneNascostaTest.droneDjiLXmpDroneDjiSparisce`, `xmpEstesoSpezzatoSparisce`, `app13DiPhotoshopSparisce`,
  `pngConTestiEXmpSparisconoEIlPngResta`, `heicConUnConvertitoreCheSiPortaDietroLXmp`; `DefaultLocationStripperTest` (core-metadata). Con lo stripper
  di prima uscivano tutti `Stripped` con la posizione.

---

## Media

### R3. "Ripristina originale" riscrive sul telefono una versione che non e' sul NAS, che va persa ("Non si perde niente")

- **Gravita':** media. Toglie dal telefono byte che non sono da nessun'altra parte, in un flusso che promette il contrario. E' la via indiretta, fuori da "Libera spazio", che la checklist chiede di cercare.
- **Dove:**
  - `core-backup/.../versions/DefaultVersionKeeper.kt:105-122` (`availability`): vuole l'originale al sicuro, **non** che le versioni sulle righe da riscrivere siano al sicuro;
  - `:143-146` (`phoneRowsToRewrite`): ogni riga presente di una versione diversa dall'originale; `:154-185` (`rewritePhone`): le riscrive con l'originale riletto;
  - `VersionPlanning.kt:125-147` (`swapTo`): l'attuale da spostare e' "la versione piu' recente con una copia verificata al solito percorso", non la versione attuale del catalogo;
  - `core-mediastore/.../write/MediaStorePhoneMediaRewriter.kt:69-128`: calcola lo SHA-256 dei byte che ci sono (`before`) ma non lo confronta con niente (il contratto non porta l'impronta attesa della riga, e `AppPhoneRewriter` butta la versione del MediaStore: `FiveBAdapters.kt:268`);
  - `feature-backup/src/main/res/values/strings_backup_versions.xml:54`: «la versione di adesso va nella cartella Versioni. Non si perde niente».
- **Scenario (e):** una foto modificata con Samsung "Salva" e gia' salvata come versione (V1 al solito percorso, l'originale in `Versioni/`). L'utente la modifica di nuovo (V2) e, prima che il backup ne faccia una versione (preset "Notte" di giorno, o semplicemente prima del giro), tocca "Ripristina originale" nel viewer. Il piano dice `AVAILABLE`; il foglio dice "non si perde niente"; il dialogo di sistema chiede il permesso; lo scambio porta V1 in `Versioni/` e l'originale al solito percorso; la riga del telefono (V2) si riscrive con l'originale. **V2 non e' piu' da nessuna parte**: non sul NAS, non sul telefono, non nel cestino di Android. Variante senza regole: l'utente modifica in Samsung e torna subito nell'app prima della lettura del telefono (il catalogo crede che la riga abbia ancora V1): il riscrittore sovrascrive senza accorgersene, perche' non guarda cosa c'e'. Lo stesso vale per un `_ID` riciclato dopo una ricostruzione del MediaStore. Per le correzioni la prima riga si rilegge e il caso e' coperto; le altre righe dello stesso media no (`DefaultCorrections.kt:158`).
- **Confermato:** **si'**, con un test temporaneo sul custode vero (`VersionWorld`): due modifiche sul telefono, la prima salvata come versione, la seconda no; il piano dice `AVAILABLE` e per V2 `safe=false, places=[PHONE]`; con il permesso l'esito e' `Queued`; dopo, lo SHA-256 di V2 non e' in nessun file del NAS e la riga del telefono ha l'originale.
- **Correzione proposta:**
  1. in `availability` (e di nuovo in `restoreOriginal`): ogni versione presente sulle righe da riscrivere deve avere una copia privata verificata (al solito percorso o in `Versioni/`), altrimenti `NEEDS_SAFE_FIRST` ("prima la metto al sicuro"), come le correzioni;
  2. in `swapTo` per "Ripristina originale": l'attuale da spostare deve essere la versione attuale del catalogo;
  3. contratto del riscrittore: `replace(ref, file, attesoNuovo, attesoAdesso)` con la versione del MediaStore; se `before` non e' l'impronta attesa della riga, `SourceMismatch` e niente si scrive (copre anche il catalogo in ritardo e l'`_ID` riciclato, e le righe successive delle correzioni);
  4. il foglio dice quale versione c'e' sul telefono e che cosa ne sara'.
- **Stato: corretta** (20/10, `docs/decisioni.md` «Correzioni 5B: versioni e correzioni»). Tutti e quattro i punti.
  - **La regola del riscrittore** (`PhoneMediaRewriter.replace(ref, file, attesoNuovo, attesoAdesso)`, e la stessa nella porta `PhoneRewriter` di core-backup):
    prima di scrivere i byte della riga si copiano e se ne calcola lo SHA-256; devono essere quelli della versione che si sostituisce **e che e' al sicuro
    sul NAS** (il chiamante passa solo l'impronta di una versione con una copia privata verificata). Altrimenti `RowChanged` e niente si scrive; con i
    byte nuovi gia' sopra e' fatta (una risposta persa). Copre il catalogo in ritardo, l'`_ID` riciclato e le righe successive delle correzioni.
  - `availability` (e di nuovo `restoreOriginal`): la versione di adesso e quelle sulle righe del telefono da riscrivere devono essere al sicuro, se no
    `CURRENT_NOT_SAFE` ("prima il backup la mette al sicuro") e niente si tocca; `swap` sposta in `Versioni/` solo la versione **attuale**.
  - La riga cambiata dopo l'ultima lettura: lo scambio sul NAS c'e' (due rinomine, nessun file perso) e il telefono non si tocca:
    `PhoneNotRewritten(PHONE_CHANGED)`, il foglio lo dice; le correzioni: `CorrectionFailure.PHONE_CHANGED`.
  - Il foglio dice il vero: il riepilogo non promette piu' «non si perde niente» senza condizioni, dice che sul telefono c'e' la versione modificata e
    che si rimette com'era solo se e' ancora quella al sicuro; dopo, ogni ragione per cui il telefono tiene la modifica ha la sua frase.
  - Test: `Revisione5BVersioniTest.r3_*` (4, core-backup: lo scenario della revisione, il catalogo in ritardo, lo scambio dalla sola attuale, la seconda
    riga di una correzione; rossi sul codice di prima), `MediaStorePhoneMediaRewriterTest.r3_*` (riscrittore vero), `SystemRequestsFakeTest.r3_*`
    (riscrittore finto allineato), `VersionsSheetTest.r3_*`, `VersionsViewModelTest.r3_*`.

### R4. "Condividi" dalla selezione della Libreria (e dal viewer senza backup) ignora "Rimuovi la posizione"

- **Gravita':** media (privacy). L'impostazione promette «Ogni foto o video che condividi parte senza posizione, dopo che l'ho controllato».
- **Dove:**
  - `app/.../gallery/LibraryStates.kt:77-85` (`share`) → `core-mediastore/.../AndroidPhoneMediaIntents.kt:48-63`: gli URI del MediaStore, cioe' gli originali; il commento a `:22-23` dice ancora «"rimuovi posizione" arriva con la 5.7»;
  - `feature-library/.../LibraryParts.kt:112-122` (`shareSelection`), `:423` (la voce della barra);
  - `feature-viewer/.../ViewerOverlay.kt:717` e `ViewerController.kt:356-369`: senza `backupController` (nessun backup, `FiveBModule.kt:83` da' `Optional.empty`) il viewer torna al percorso di prima, e la pagina "Condivisioni" resta visibile;
  - l'unico che legge l'impostazione e' `FiveBUi.kt:342-346`;
  - `feature-settings/src/main/res/values/strings_sharing.xml:7`, `:9`.
- **Scenario (e):** impostazione accesa, l'utente seleziona 5 foto nella griglia e tocca "Condividi" verso Telegram come file o verso Gmail. Partono gli originali: la posizione la toglie Android solo se l'app che riceve non ha `ACCESS_MEDIA_LOCATION` o non chiede l'originale, e mai l'XMP di R2.
- **Confermato:** **si'** (lettura): nessuna via della selezione passa da `LocationFreeShare`. La fuga reale dipende da chi riceve (plausibile).
- **Collegato (informativo):** il selettore e il provider dei documenti consegnano sempre i byte originali (anche i solo-NAS), qualunque sia l'impostazione. D23/D26 non lo vietano, ma la pagina dice «Quando condividi con un'altra app»: va detto, o applicato anche li'.
- **Correzione proposta:** con l'impostazione accesa, la selezione della Libreria passa da `LocationFreeShare.prepare(ids, true)` (lo stesso foglio "Condivisione ferma"); senza `FiveBUi` e con l'impostazione accesa, "Condividi" si rifiuta con il motivo invece di usare il percorso di prima; la pagina dice esattamente dove vale. Test: la selezione con l'impostazione accesa non chiama mai `PhoneMediaIntents.share`.
- **Stato:** corretta (20/10). Ogni "Condividi" passa da `app/.../integrations/AppShares.kt` (legato dalle integrazioni, che ci sono anche senza
  backup): la selezione della Libreria con l'impostazione accesa usa `LocationFreeShare` e mostra avanzamento e foglio "Condivisione ferma"
  (`feature-library/.../LibraryShare.kt`); il viewer senza backup ha la sola voce "Condividi" dello stesso canale, che rispetta l'impostazione (meglio
  del rifiuto proposto); con l'impostazione accesa "Copia" non compare. Test: `CondividiSenzaPosizioneAppTest.r4_laSelezioneConLImpostazioneAccesaNonUsaMaiGliOriginali`,
  `r4_unaSelezioneFermaHaIlSuoFoglioEConLaPosizioneSoloSeLoSceglie`, `r4_ilViewerSenzaBackupRispettaLImpostazione` (:app);
  `ViewerBackupMenuTest.conRimuoviLaPosizioneAccesaCopiaNonCe_MC2`. Superata dal 20/10 (decisione dell'utente, «Correzioni 5B: integrazioni» in `docs/decisioni.md`): selettore e provider dei documenti, che consegnavano
  gli originali, con l'impostazione accesa consegnano una copia senza posizione (L41).

### R5. Un file in arrivo verso una share chiusa ferma tutto il backup privato

- **Gravita':** media (disponibilita' della protezione, proprio durante un incidente).
- **Dove:**
  - `core-backup/.../upload/SafeUploadEngine.kt:301-322`: nei giri normali (foto, video, `resumeAll`) gli elementi `INCOMING` entrano nella coda e sempre fra i "miei" (`:313`); per tipo passano in ogni giro (`:309`);
  - `:346-356` e `:406-410`: se `gateOf(item)` (il cancello della share **di destinazione**) e' chiuso, `round.stopForGate` ferma **tutto il giro**;
  - un errore del NAS su quella share (`PermissionDenied`, share mancante) da' `Step.Stop` del giro;
  - la ripresa va in ordine di `item_id`: l'arrivo piu' vecchio si prende per primo.
- **Scenario (a, e):** fuori casa l'utente salva una ricevuta nell'area comune; resta in coda (E-X8). Poi il PC di un familiare cifra file nell'area comune e la scansione **congela la share comune**. Ogni giro del backup prende per primo l'arrivo, `IncomingGate.stateOf(comune)` = `FROZEN`, e il giro si chiude con `Problem(FROZEN)`: nessuna foto nuova va nell'area privata finche' dura il congelamento. Con `READ_ONLY_AREA` o `NO_PRIVATE_AREA` scoperti dopo la messa in coda il blocco e' permanente: l'elemento non diventa mai `FAILED`.
- **Confermato:** **si'** (catena del codice). `IncomingEveryCallTest` usa un solo cancello e nessun giro misto.
- **Correzione proposta:** per un `INCOMING`, cancello chiuso o errore della sua share **parcheggiano solo quell'elemento** (`queue.park()` senza `stopWith`), oppure gli `INCOMING` si guidano solo con `BackupTrigger.INCOMING`; `NO_PRIVATE_AREA` e `READ_ONLY_AREA` persistenti → `FAILED` con la copia locale che resta. Test: un arrivo verso l'area comune congelata e una foto nuova nella stessa coda: la foto arriva nell'area privata.
- **Stato: corretta** (20/10, `docs/decisioni.md` «Correzioni 5B: integrazioni»). Un arrivo con il cancello della **sua** share chiuso (area comune congelata,
  sola lettura, non piu' dell'utente, backup in prova) aspetta da solo: nei giri normali (foto, video, ripresa) e nel giro dei soli arrivi il motore lo
  mette da parte (`waitsAlone`, `queue.park()`) e va avanti con il resto; la pausa dell'utente (R4), l'emergenza e la famiglia non pronta valgono per tutto e
  fermano il giro come per ogni elemento. Un rifiuto della share di destinazione scoperto scrivendo (`PermissionDenied`, share sparita) e' `Step.Wait()`
  dell'arrivo, non `Step.Stop` del giro (prima: `READ_ONLY_AREA` per tutti). Non fatto: portare a `FAILED` un arrivo bloccato per sempre (resta in attesa
  con la sua copia locale, come ogni arrivo, e riparte da solo se il cancello si riapre). Test: `IncomingIsolationTest.r5_*` (5: l'incidente della
  voce, ogni cancello chiuso per motivi di quella share, il giro dei soli arrivi, pausa/emergenza/famiglia che restano globali, il rifiuto in scrittura).
  Prova di mutazione: con `waitsAlone` sempre falso sono rossi 3 dei 5 `IncomingIsolationTest.r5_*` (quelli del cancello chiuso; gli altri due provano cio' che resta globale).

### R6. Dopo "Configura di nuovo" con un altro account i dati del vecchio account restano, si vedono e si pubblicano nell'area privata del nuovo

- **Gravita':** media (privacy fra account, C19, L29).
- **Dove:**
  - `core-family/.../onboarding/DefaultOnboardingCoordinator.kt:590-608` (`startOver`): toglie chiavi, password, account, stato fidato e avanzamento; non tocca niente dei dati locali legati all'account, ne' l'impronta di casa (`homeFingerprints`);
  - `app/.../nas/NasOrganizer.kt:384-388`: il registro dei dati personali e' un file fisso (`noBackupFilesDir/personal/…`), non legato ad account o NAS;
  - `core-personal/.../sync/NasPersonalSync.kt:311-317`: pubblica **lo stato unito intero** nell'area privata dell'account di adesso; le modifiche "da datare" del vecchio account le firma il dispositivo nuovo;
  - `app/.../graph/LocalData.kt:139-151`: replica, miniature, anteprime e dati personali si cancellano solo con l'azzeramento; la vista della Libreria non filtra per account (`LibraryDaos.kt`, `VISIBLE`).
- **Scenario (e):** il tablet di Alice viene revocato e passa a Bob. La pagina "fuori dalla famiglia" offre "Configura di nuovo"; Bob entra con il suo account. Nella Libreria restano le foto private di Alice in cache (anche per il selettore delle altre app) e i nascosti di Alice dietro il blocco dell'app di Bob; al primo giro dei dati personali nascosti, preferiti, album e impronte `qh1` di Alice finiscono firmati nella share privata di Bob. Oggi D31 limita la pubblicazione alle share di prova; la Libreria locale no.
- **Confermato:** il percorso del registro e della pubblicazione **si'** (lettura); la visibilita' in Libreria dipende dal calcolo di `presence` delle posizioni non piu' scansionate (plausibile).
- **Correzione proposta:** `startOver` ricorda la coppia (account, GUID del NAS) di prima; se il rientro avviene con un account o un NAS diverso, prima di tutto la cancellazione dei dati locali dell'azzeramento (personali, replica, miniature, anteprime, posizioni del vecchio account, franchigie, cache di modifica e condivisione; non le foto del telefono) e `homeFingerprints.clear()`; `NasPersonalSync.publish` pubblica solo voci scritte da dispositivi dell'account corrente. Test sul grafo vero: `startOver`, rientro con un altro account, zero voci nella sua share e zero media del primo account visibili.
- **Stato: corretta** (20/10). `startOver` salva un riassunto (SHA-256 troncato di GUID del NAS e account, mai in chiaro) di chi lascia in `OnboardingSnapshot.previousOwner`, che
  sopravvive al cambio di percorso, e dimentica l'impronta di casa. A «Vai alla Libreria» (`finish`, l'unico punto che apre la Libreria: `SetupState.Ready` vuole l'onboarding finito) il
  coordinatore lo confronta con chi e' rientrato: **un altro account o un altro NAS** → `AppLocalDataEraser.eraseAccountData` toglie i dati locali di prima (database della galleria con
  catalogo dei solo-NAS e diari, replica e pacchi, dati personali, miniature e anteprime, **file preparati delle versioni e copie dei file ricevuti**, cache, impostazioni del backup) **prima**
  di finire, senza toccare l'onboarding in corso, l'account, la casa, le chiavi, le foto del telefono e il NAS; se non riesce non finisce (`LOCAL_DATA_NOT_REMOVED`). Lo stesso account sullo
  stesso NAS tiene i dati (ON-4, ON-5). Con la galleria gia' partita in quel processo «Configura di nuovo» riavvia il processo (`AppGraph.afterStartOver`, come dopo un azzeramento): il
  catalogo non tiene il proprietario di prima e nessuna galleria e' viva quando si tolgono i dati. Trovato strada facendo: l'azzeramento intero non toglieva `versions/` e `incoming/` della
  5B (ora si'). Test: `CorrezioniConfigurazione5BTest` (core-family: il riassunto, stesso account, altro NAS, tolta che fallisce e si riprova, cambio di percorso, codec) e
  `CorrezioniConfigurazione5BAppTest` (:app, grafo vero con simulatore SMB: tablet revocato, «Configura di nuovo», processo nuovo, altro account → dati tolti prima di finire; stesso account →
  restano; nessun riavvio senza galleria viva; `eraseAccountData` non tocca l'onboarding; i nomi delle due cartelle sono quelli di `FiveBGraph`). Non fatto, perche' superfluo con i dati
  tolti: `NasPersonalSync.publish` che filtra le voci per dispositivi dell'account corrente.

### R7. "Configura di nuovo" dalla pagina "fuori dalla famiglia": un tocco, nessuna conferma, e dopo un guasto del Keystore distrugge la chiave di famiglia dell'admin

- **Gravita':** media (perdita della chiave di famiglia: il recupero vuole il kit e il codice, altrimenti resta solo il reset "via dal PC").
- **Dove:**
  - `feature-onboarding/.../steps/RemovedScreen.kt:60` e `OnboardingHost.kt:338`: il tasto chiama `startOver` direttamente; dalle Impostazioni invece c'e' `ConfirmSheet` con l'avviso per l'admin (`FamilyRejoinScreen.kt:185`);
  - `OnboardingViewModel.kt:197-221`: la pagina compare con `TrustStatus.NotConfigured` e onboarding finito (motivo `OTHER`), anche per un admin;
  - `core-family/.../trust/TrustEngine.kt:289-317`: `NotConfigured` anche quando `myDeviceId()` lancia (Keystore) o lo stato locale non si riverifica (`markUntrusted`, `:214-220`), e come valore iniziale (`:103`);
  - `DefaultOnboardingCoordinator.kt:595`: `startOver` cancella la chiave di famiglia.
  - `docs/decisioni.md` (ON, "Sicurezza") dice «dopo un gesto confermato… il foglio lo dice prima».
- **Scenario (e):** l'unico admin apre l'app dopo un aggiornamento di sistema e il Keystore lancia su `deviceSigner()`. La pagina dice «Lo stato della famiglia salvato qui non c'e' piu'» con "Configura di nuovo"; un tocco e spariscono chiave del dispositivo, chiave di famiglia e password.
- **Confermato:** il percorso **si'**; l'innesco (un guasto passeggero del Keystore) e' plausibile.
- **Correzione proposta:** lo stesso foglio di conferma delle Impostazioni (con l'avviso dell'admin se la cassaforte ha la chiave); distinguere "stato non leggibile / Keystore guasto" (pagina `Blocked`, riprova) da "non configurato"; con il motivo `OTHER` non distruggere la chiave di famiglia senza una seconda conferma. Controllare che a freddo la radice non mostri la pagina prima del primo calcolo dello stato.
- **Stato: corretta** (20/10). «Configura di nuovo» dalla pagina «fuori dalla famiglia» apre un foglio di conferma (`askStartOver` → `StartOverSheet`, con un host dei pop-up suo: in onboarding la radice non ne
  ha uno) e solo la conferma chiama `startOver`; «Annulla» e chiudere non confermano e «Riprova» dopo un problema riapre il foglio. Per un admin (`OnboardingCoordinator.holdsFamilyKey`: fondatore, admin
  recuperato o secondo admin, o approvato admin nel registro; nel dubbio si') il foglio dice che si perde la chiave di famiglia, che senza non si approvano dispositivi ne' si cambia la famiglia, che
  si riprende solo con il kit di recupero e il suo codice («Recupera l'admin», dal benvenuto) e che senza non si deve continuare; con la causa sconosciuta aggiunge che puo' essere un guasto del
  telefono. Lo stesso avviso e' nel foglio delle Impostazioni. **Una chiave di famiglia non si distrugge per un guasto temporaneo del Keystore:** `startOver` chiede `hasDeviceKey()` prima di toccare
  qualsiasi cosa e, se lancia, risponde `KEYS_NOT_REMOVED` lasciando tutto com'era. Residuo dichiarato: `TrustEngine` continua a dare «non configurato» quando il Keystore lancia (fail-closed), quindi la
  pagina puo' ancora comparire in quel caso; uno stato nuovo (`StaleReason`, pagina `Blocked`) toccava `TrustEngine`, `SetupResolver` e quattro schermate ed e' rimasto fuori. Controllato a freddo:
  la radice non mostra la pagina prima del primo calcolo dello stato (`SetupState` resta `Loading` finche' `AppGraph.start()` non ha riletto il diario dell'azzeramento e calcolato lo stato fidato), e la pagina
  vuole anche l'onboarding finito (`SUMMARY` completato). Test:
  `CorrezioniConfigurazione5BViewModelTest` (il tasto apre la conferma e non tocca niente; annulla; la conferma configura; l'admin vede l'avviso, il familiare no; il problema delle chiavi e il «Riprova»
  che chiede di nuovo), `StartOverSheetTest` (la pagina non configura con un tocco; il foglio di un admin dice cosa si perde e il kit), `CorrezioniConfigurazione5BTest` (Keystore guasto: la chiave di
  famiglia resta, niente si tocca) e quello di `feature-settings`.
- **Residuo: chiuso** (20/10, `docs/decisioni.md`, «Avvio e Keystore: D-7 a monte e il residuo di R7»). Un Keystore che non risponde con uno stato della famiglia salvato e' ora
  `TrustStatus.KeystoreUnavailable` (`TrustEngine`), e `SetupResolver` ne fa `SetupState.Blocked(KEYSTORE_UNAVAILABLE)`: mai piu' «non configurato», quindi mai la pagina «fuori dalla famiglia».
  Resta chiuso: nessun permesso di scrittura, `preflight` non da' `Proceed`, `adminContext` non c'e' (approvazioni e cambi di stato: `NotAdmin`), nessuno stato del dispositivo pubblicato, nessuna richiesta
  da approvare, `TrustSyncRun` si ferma. L'avvio non si ferma piu' al Keystore (`DeviceSelfWipe.resumeIfInterrupted` non lancia e non azzera). La radice mostra la pagina «Il telefono non riesce a usare le
  chiavi di sicurezza in questo momento» con «Riprova» (ricalcola lo stato come all'avvio) e la spiegazione; «Configura di nuovo» e' un'uscita in fondo, dietro lo stesso `StartOverSheet` con l'avviso
  dell'admin (`holdsFamilyKey` nel dubbio vero anche per `KeystoreUnavailable`). Impostazioni, Dispositivi, Diagnostica e la pagina della radice in onboarding lo gestiscono. Test: `KeystoreNonDisponibileTest`
  (core-family), `SetupRepositoryTest.r7_…`, `CorrezioniConfigurazione5BTest.r7_conLoStatoKeystoreNonDisponibile…` e `r7_ilFintoRispondeComeIlVero…` (parita' del coordinatore finto),
  `SoftwareKeysParityTest` (core-family e `:app`: i Keystore finti come la classe vera), `CorrezioniConfigurazione5BViewModelTest.r7_conIlKeystoreCheNonRisponde…`, `RootViewModelTest.r7_…` (il flusso:
  «Riprova», il foglio, il problema delle chiavi) e `AvvioEChiaviAppTest.r7_…` (grafo vero con il Keystore guasto, «Riprova» guarito, «Configura di nuovo» che non tocca niente).

---

## Bassa

### R8. Il selettore non si difende dalle sovrapposizioni

- **Gravita':** bassa (Android 12+ blocca i tocchi attraverso le sovrapposizioni opache di altre app; restano quelle trasparenti oltre la soglia e le app con l'accessibilita').
- **Dove:** `feature-integrations/.../picker/PickerActivity.kt:40-80` (nessun `filterTouchesWhenObscured`, nessun `setHideOverlayWindows`); `PickerViewModel.kt:176-181`: senza `EXTRA_ALLOW_MULTIPLE` **un tocco su un elemento consegna**. `ShareReceiverActivity.kt:107`, `:123-131` invece si protegge.
- **Scenario (b):** un'app con il permesso di disegnare sopra apre il selettore con un intento esplicito (app sbloccata, o blocco spento) e copre la griglia con un gioco "tocca qui": il tocco consegna una foto dell'utente, con il permesso di lettura, all'app che l'ha chiesta.
- **Confermato:** la protezione manca (lettura); lo sfruttamento e' plausibile.
- **Correzione proposta:** la stessa `protectFromOverlays()` della ricezione nel selettore (e nel suo pannello di sblocco); un test come quello della ricezione.
- **Stato: corretta** (20/10). (1) `OverlayShield.protect(window)` (`filterTouchesWhenObscured` e, da Android 12, `setHideOverlayWindows`) e' ora nel selettore
  e nel suo pannello di sblocco, come gia' nella ricezione (che usa lo stesso oggetto). (2) Difesa in profondita': senza `EXTRA_ALLOW_MULTIPLE` un tocco
  su un elemento **sceglie ma non consegna**; la consegna parte solo da «Scegli» (barra sempre presente, `PickerViewModel.confirm`), cosi' un tocco
  rubato non porta una foto all'app che l'ha chiesta. Costo: un tocco in piu' per chi sceglie una foto sola (scelta dichiarata in `docs/decisioni.md`).
  Test: `PickerActivityTest.r8_*` (2: i tocchi attraverso una sovrapposizione si scartano; un tocco sceglie, serve «Scegli»),
  `PickerViewModelTest.r8_*`. Resta la prova sul telefono (un filtro dello schermo mentre si apre il selettore). Prova di mutazione: senza `OverlayShield.protect` sono rossi `PickerActivityTest.r8_*` (2); con la consegna al tocco, `PickerViewModelTest.r8_*` e `PickerActivityTest.r8_unTocco…`.

### R9. Riscrittura del telefono: nessun recupero dopo la morte del processo

- **Gravita':** bassa (la versione buona e' al sicuro sul NAS; il telefono resta con un file rotto che diventa una versione nuova).
- **Dove:** `MediaStorePhoneMediaRewriter.kt:84-127`: la copia di sicurezza sta in `cacheDir/rewrite/undo-<nanoTime>.bin`, senza legame con la riga; nessun codice la cerca all'avvio; `NonCancellable` (`:66`) non protegge dalla morte del processo o dallo spegnimento.
- **Scenario (e):** batteria finita o processo ucciso durante `writeInPlace` di un JPEG da 30 MB (correzione o "Ripristina originale"): la riga resta con l'inizio nuovo e la coda vecchia. La lettura del telefono lo vede come una modifica e il backup lo carica come **nuova versione attuale**; la copia buona del telefono resta orfana in cache e si perde alla pulizia della cache.
- **Confermato:** **si'** (nessun recupero nel codice).
- **Correzione proposta:** prima della scrittura un diario in `noBackupFilesDir` (riga, versione del MediaStore, impronta vecchia e nuova) con la copia di sicurezza accanto, sincronizzato; all'avvio, una riga che non ha ne' l'impronta vecchia ne' la nuova si ripristina, poi si pulisce.
- **Stato: corretta** (20/10). Copia di sicurezza e diario (URI, impronta di prima e nuova, scritto in un temporaneo sincronizzato e poi rinominato) in
  `noBackupFilesDir/rewrite/`, **prima** di toccare la riga; si tolgono solo con la riga a posto (nuova, com'era o mai toccata). `recover()` all'avvio
  (`FiveBGraph.atStart`, prima di tutto il resto) e prima di ogni riscrittura: riga con i byte nuovi o con quelli di prima = a posto; sparita = niente;
  altrimenti la copia (solo se ha l'impronta di prima) si rimette e si rilegge. Senza permesso (decaduto) diario e copia restano per la volta dopo. Una
  riscrittura alla volta in tutto il processo. Test: `MediaStorePhoneMediaRewriterTest.r9_*` (2: riga a meta' rimessa da un riscrittore nuovo; senza
  permesso aspetta), `Correzioni5BVersioniAppTest.r9_*` (il recupero parte all'avvio). Resta: se il backup legge la riga a meta' **prima** del recupero la
  carica come versione (rimessa la riga, la lettura del telefono vede il ritorno a una versione nota e lo scambio la porta in `Versioni/`): una versione
  intermedia in piu', nessun byte perso.

### R10. "Ripristina originale" di un video: rilettura oscurata

- **Gravita':** bassa.
- **Dove:** `MediaStorePhoneMediaRewriter.kt:81` (`setRequireOriginal` solo per `image/*`), `:88`, `:115`, `:131-133`; `DefaultVersionKeeper.kt:145`, `:164` (i video non sono esclusi). Senza `ACCESS_MEDIA_LOCATION`, `setRequireOriginal` fa lanciare `UnsupportedOperationException` dall'apertura, che `copyOut` non gestisce: l'eccezione esce da `apply` o `restoreOriginal` a catalogo e lavoro gia' scritti.
- **Scenario (e):** si riscrive il video originale; la rilettura dall'URI oscurato da' un'altra impronta (`HASH_MISMATCH`); il ripristino rimette la copia di sicurezza **oscurata**; la risposta e' `restored=true`. Il video sul telefono perde per sempre `©xyz` e ogni nuovo tentativo fallisce.
- **Confermato:** plausibile (dipende da come MediaProvider oscura i video per un'app che non ne e' proprietaria).
- **Correzione proposta:** `setRequireOriginal` per ogni media (come `AndroidMediaStoreSource`); le eccezioni a runtime diventano `Failed`/`NotGranted`; il controllo di R3.3 fermerebbe il caso prima di scrivere.
- **Stato: corretta** (20/10). `setRequireOriginal` per foto e video (copia di sicurezza, rilettura, recupero); senza `ACCESS_MEDIA_LOCATION` l'apertura
  lancia e diventa `Failed(ORIGINAL_UNREADABLE, restored = true)` senza toccare niente; le altre eccezioni a runtime sono `Failed`, mai fuori dal
  riscrittore. In piu' il controllo di R3: una copia oscurata ha un'altra impronta e la riga non si scrive. Test: `MediaStorePhoneMediaRewriterTest.r10_*`
  (2: anche i video si leggono con i byte originali; senza il permesso della posizione niente si tocca).

### R11. "Modifica con…" di un solo-NAS: il tipo si controlla su byte diversi da quelli caricati

- **Gravita':** bassa (l'editor l'ha scelto l'utente; l'integrita' la protegge la rilettura del motore).
- **Dove:** `feature-integrations/.../edit/DefaultEditSessions.kt:248` (impronta), `:263-268` (tipo, una seconda lettura), `:275` (copia, una terza lettura) del file in cache che l'editor puo' ancora scrivere.
- **Scenario (b):** un editor ostile alterna l'intestazione: impronta e copia su A (non un media), controllo del tipo su B (intestazione JPEG). Un file che non e' un media, o un video al posto di una foto, diventa la versione attuale sul NAS.
- **Confermato:** l'ordine **si'**; lo sfruttamento richiede tentativi.
- **Correzione proposta:** una sola lettura che copia nel file privato di lavoro; impronta e tipo sulla copia.
- **Stato: corretta** (20/10). Una lettura sola del file della cache, che un editor ostile puo' ancora scrivere: `DefaultEditSessions.copyPrivately` lo copia nel file di
  lavoro privato e calcola SHA-256, dimensione, `qh1` e i primi byte per il tipo **sugli stessi byte**; il tipo (media dello stesso genere, non vuoto,
  sotto i tetti) si giudica su quella copia e la versione che si registra e' quella copia, mai una seconda lettura. La prima lettura (impronta del file
  com'e') serve solo a sapere se i byte sono cambiati e non entra mai in una versione; oltre il tetto non si legge nemmeno. Se l'editor rimette i byte
  di prima fra le due letture, non c'e' niente da registrare. Test: `DefaultEditSessionsTest.r11_*` (un editor che alterna l'intestazione: un file che
  non e' un media, o un video al posto di una foto, non diventa una versione). Prova di mutazione: con una seconda lettura per il tipo `DefaultEditSessionsTest.r11_*` diventa rosso.

### R12. La correzione "togli il luogo" lascia la posizione nell'XMP

- **Gravita':** bassa.
- **Dove:** `core-metadata/.../write/ExifMetadataWriter.kt:109` (svuota solo le etichette EXIF), `WrittenFileCheck.kt:82-92` (controlla con l'estrattore, che legge solo l'EXIF). Restano l'XMP `exif:GPS*`, la nota del produttore e il `©xyz` del video di una motion photo.
- **Scenario (e):** l'utente toglie il luogo da una foto e poi la condivide con l'impostazione spenta, o da Samsung Gallery: chi riceve legge la posizione dall'XMP.
- **Confermato:** **si'**.
- **Correzione proposta:** per `GeoChange.Clear` la stessa pulizia di `ImageLocationStrip` (con le correzioni di R2) e il ricontrollo grezzo.
- **Stato:** corretta (20/10). "Togli il luogo" toglie tutto il blocco GPS (anche ora e data GPS) e la nota del produttore, poi sul posto
  (`core-metadata/.../write/LocationClear.kt`) le proprieta' del luogo dall'XMP (`XmpLocationScrub`: gps, latitude, longitude, altitude, location e i
  nomi di luogo, in ogni spazio dei nomi), l'XMP esteso e l'APP13 (azzerati), i testi dei PNG con il luogo, la parte video di una motion photo
  (`IsoClean`); poi lo stesso rilevatore indipendente delle condivisioni. Se resta una traccia o non si puo' pulire: `WriteUnsupported.FORMAT`, la
  correzione resta nell'app (come L40). Test: `TogliIlLuogoTest` (core-metadata).

### R13. Un XMP costruito apposta blocca un thread per ore

- **Gravita':** bassa (disponibilita').
- **Dove:** `XmpGpsScrub.kt:17` (`ELEMENT`: `[^>]*?` pigro senza `>` nel testo = tempo quadratico); i tetti sono 4 MiB (`ImageXmp.kt:19`, `IsoLocation.kt:69`). `strip` e' bloccante: annullare la condivisione non lo ferma.
- **Scenario (a, c):** un familiare mette nell'area comune un PNG con un `iTXt` di 4 MiB fatto di `<GPSA<GPSA…`; l'utente lo condivide "senza posizione" e un thread di I/O resta occupato per ore.
- **Confermato:** **si'** (analisi dell'espressione).
- **Correzione proposta:** uno scanner lineare dei nomi di proprieta', o un tetto molto piu' basso per l'XMP da pulire (oltre, si ferma la condivisione).
- **Stato:** corretta (20/10). Le condivisioni non analizzano piu' l'XMP (lo tolgono intero). La pulizia di "togli il luogo" e' uno scanner lineare
  senza espressioni regolari (`write/XmpLocationScrub.kt`): tetto di 1 MiB per pacchetto e 2 secondi, e un tag, un valore o un elemento che non si chiude
  ferma subito (`unsupported`) invece di riprovare dal byte dopo. Anche il rilevatore finale e' lineare, con tetti di dimensione (16 MiB per pacchetto e
  per testo compresso) e di tempo (5 minuti per file). Test: `XmpLocationScrubTest.unXmpCostruitoApposta_SiFermaSubitoInvecheDiBloccareUnThread`
  (core-metadata: 1 MiB di `<GPSA` in meno di un secondo).

### R14. Due "Salva sul NAS" degli stessi byte verso destinazioni diverse diventano uno

- **Gravita':** bassa (nessun byte perso; la seconda scelta dell'utente non si realizza e la sua copia locale si toglie).
- **Dove:** `core-backup/.../store/RoomBackupStore.kt:51-58` (`createIncoming` restituisce l'`INCOMING` gia' aperto della stessa versione, qualunque siano share e cartella); `MediaCatalog.kt:1392-1397` (stessi byte = stessa versione); `app/.../backup/IncomingQueue.kt:96-137`, `:234-249` (a `DONE` tutte le righe diventano `uploaded` e tutte le copie locali si tolgono).
- **Scenario (e):** fuori casa l'utente salva una foto ricevuta nell'area privata, poi la ricondivide scegliendo l'area comune ("lo vedranno tutti"): il file va solo nell'area privata, la seconda riga dice "caricato" e i familiari non lo ricevono.
- **Confermato:** **si'**.
- **Correzione proposta:** idempotenza per (versione, share, percorso previsto); una destinazione diversa crea un elemento nuovo. Test: due `enqueue` con destinazioni diverse prima di un giro.
- **Stato: corretta** (20/10). `createIncoming` (Room e in memoria) e' idempotente per (versione, share, **cartella** di destinazione), senza badare alle
  maiuscole e non al nome del file (che puo' cambiare da un'app all'altra): una destinazione diversa e' un elemento nuovo, con la sua riga
  `incoming_item` e la sua copia locale; lo stesso arrivo toccato due volte nella stessa cartella resta uno. Test: `IncomingStoreTest.r14_*` (2: Room e in
  memoria), `Correzioni5BIntegrazioniAppTest.r14_*` (grafo vero: gli stessi byte in `Ricevute/` e in `Viaggi/`, entrambi arrivano e le due righe sono
  caricate). Prova di mutazione: con `sameDestinationAs` sempre vero `IncomingStoreTest.r14_*_Room` diventa rosso.

### R15. Un file in arrivo legato a un media esistente gli mette i segni di problema e ne blocca il backup

- **Gravita':** bassa (nessun falso "al sicuro": la copia e' riletta).
- **Dove:** `MediaCatalog.kt:1392-1397` (`incomingMedia` prende qualunque versione con lo stesso SHA-256, senza guardare proprietario, nascosto o area); `SafeUploadEngine.kt:443-444`, `:645-650` (`markProblem(item.mediaId, …)`); `SafeUploadEngine.kt:264` e il guardiano: `openFor(versionId)` di un `INCOMING` aperto ferma 5A e guardiano per quella versione.
- **Scenario (e):** l'utente condivide dalla Galleria verso "Salva sul NAS" (area comune) una sua foto non ancora al sicuro. Con R5 l'arrivo resta aperto, e finche' resta aperto ne' la 5A ne' il guardiano la mettono al sicuro nell'area privata; se la verifica fallisce tre volte il media del telefono prende `VERIFY_FAILED` ed esce dal piano della 5A. Un arrivo uguale a un media nascosto, o di un altro account, vi si aggancia: il file salvato non si vede, o e' "non mio".
- **Confermato:** **si'** (lettura).
- **Correzione proposta:** il segno del problema solo su un media creato dall'arrivo (`created = true`); `openFor` della 5A e del guardiano ignora gli `INCOMING` verso un'altra share; `incomingMedia` sceglie solo media propri e non nascosti, altrimenti ne crea uno nuovo.
- **Stato: corretta** (20/10, in due tempi). **Primo tempo:** (1) un arrivo che non riesce (`UNEXPECTED`, `LOST_BEFORE_BACKUP`, `UNREADABLE`, `VERIFY_FAILED`) non mette piu' il segno del
  problema sul media (`SafeUploadEngine.markMedia`): il problema resta sull'elemento (`FAILED`, con la sua copia locale). (2) `BackupStore.openFor(versionId)`,
  che usano il piano della 5A e il guardiano, **ignora gli arrivi**: un arrivo aperto non ferma piu' la messa al sicuro di quella versione. Test:
  `IncomingStoreTest.r15_*` (2), `IncomingIsolationTest.r15_*` (il motore: un arrivo con la copia locale cambiata va a `FAILED` e il media non ha nessun problema).
  Prova di mutazione: con `markMedia` che segna sempre e con `openFor` che vede gli arrivi sono rossi `IncomingIsolationTest.r15_*` e `IncomingStoreTest.r15_*_Room`.
  **Secondo tempo (`core-database`):** (3) `MediaCatalog.incomingMedia` cerca fra le versioni con gli stessi byte **solo i media dello stesso account e non
  nascosti** (E-H1); un media ancora senza posizione, cioe' il primo arrivo uguale in volo (R14), e' dell'utente e vale; fra piu' candidati preferisce quello
  che si vede; nel dubbio ne nasce **uno nuovo**. Un arrivo uguale a un nascosto non gli si aggancia piu' (il file salvato si vede, il nascosto non prende copie
  che non ha chiesto, resta nascosto), e uno uguale al media di un altro account non diventa "mio". Test: `CatalogFiveBTest.r15_*` (4: il nascosto, un altro
  account, il secondo arrivo uguale che si aggancia al primo e mai al nascosto, il media dell'utente che resta quello giusto) e, sul grafo vero,
  `RicevutiInCodaAppTest.r15_*` (la foto nascosta e lo stesso file ricevuto: nasce un media visibile, il nascosto non cambia). Rossi prima della correzione
  (`incomingMedia` com'era): tre dei quattro di `CatalogFiveBTest.r15_*` (il quarto e' la guardia del caso normale).

### R16. Uno scambio senza conferma puo' partire da un file dell'area comune cambiato da un altro account

- **Gravita':** bassa (nessun byte perso; rinomine nell'area privata decise da un dato del NAS, contro E-S11).
- **Dove:** `MediaCatalog.kt:1988-1995` (ogni `RevertedToVersion` si annuncia in `reverts`, anche dalla scansione del NAS); `app/.../backup/BackupGraph.kt:353-354`, `FiveBGraph.kt:301-314` (`planRealign` su ogni annuncio e sui 500 media con piu' versioni all'avvio); `LinkingRules.kt:73-98` (sul NAS la continuita' c'e' sempre; il ritorno a una versione si riconosce con `qh1` e dimensione).
- **Scenario (a):** una mia foto ha nell'area comune sia l'originale O sia la modifica E. Un familiare di un altro account copia O sopra E dal PC. La scansione dell'area comune fa `RevertedToVersion(O)`, l'attuale del mio media diventa O, e al giro dopo nella **mia area privata** O torna al solito percorso ed E va in `Versioni/`, mentre il telefono mostra ancora E.
- **Confermato:** **si'** (catena del codice).
- **Correzione proposta:** annunciare `reverts` solo per una posizione del telefono (E-E2 e' un fatto del telefono), o richiedere in `planRealign` una riga del telefono presente sulla versione attuale. Test: una scansione dell'area comune con un ritorno a una versione nota non crea nessuno `SWAP`.
- **Stato: corretta** (20/10). In `NasVersionEngine.planRealign`: lo scambio senza conferma nasce solo se la versione attuale ha una riga del telefono
  (presente o tolta dopo: il banco delle interruzioni ha mostrato che "presente" e' troppo stretto, una foto riportata all'originale in Galleria e poi
  liberata dal telefono non si riallineava piu'). Un ritorno visto solo dalla scansione del NAS lascia le righe del telefono sulla modifica: niente si
  sposta nell'area privata. Test: `Revisione5BVersioniTest.r16_unRitornoVistoDallaScansioneDelNasNonFaUnoScambio` (rosso prima; lo stesso ritorno fatto
  sul telefono lo scambio lo fa ancora); banchi delle interruzioni verdi.

### R17. La franchigia D32 vale per `fileId` e per sempre

- **Gravita':** bassa (riduce la sensibilita' della guardia dei cambiamenti di massa, solo per i file toccati dalle versioni).
- **Dove:** `core-backup/.../versions/VersionFranchise.kt:20-37` (tutti i lavori della share, terminali compresi, senza scadenza; `LINKED_EXISTING` e `FAILED` contano); `core-nasmedia/.../scan/DefaultNasScanner.kt:645-648` (un file "cambiato" con un `fileId` in franchigia non conta, qualunque sia il timbro nuovo).
- **Scenario (a):** un ransomware che cifra sul posto (stesso `fileId`) non fa contare fra i "cambiati" i file che le versioni hanno mai toccato; su ext4 un `fileId` si puo' riusare per un file nuovo; un file trovato al solito percorso (non scritto dall'app) entra in franchigia.
- **Confermato:** la logica **si'**; l'impatto e' plausibile e cresce con l'uso.
- **Correzione proposta:** esentare la coppia (`fileId`, timbro che l'app ha prodotto) e solo finche' la scansione vede quel timbro; limitarla ai lavori dopo l'ultima scansione completa; escludere `LINKED_EXISTING` e i timbri trovati.
- **Stato: corretta** (20/10), nel perimetro di core-backup (il contratto `OwnChanges` di core-nasmedia passa ancora soli `fileId`). `VersionFranchise` da'
  un `fileId` solo se: il lavoro e' **recente** (una settimana d'ora del NAS dall'ultimo passo del diario, `VersionJournal.inShareSince`; senza l'ora del
  NAS nessuna franchigia); il timbro e' **prodotto dall'app** (archiviazione, pubblicazione, ripristino; mai il file trovato di un `LINKED_EXISTING`); il
  catalogo **non ha ancora seguito quella rinomina** (per quel `fileId` nessuna copia, o solo il timbro di prima). Con il timbro dell'app il catalogo e' in
  pari e da li' un cambiamento sul posto con lo stesso `fileId` (un ransomware) conta fra i "cambiati"; con un altro timbro la scansione l'ha gia' visto.
  In pratica la franchigia copre solo la finestra fra la rinomina sul NAS e il catalogo che la segue. Test: `Revisione5BVersioniTest.r17_*` (rosso prima:
  catalogo in ritardo = franchigia; in pari = niente; oltre la settimana = niente; senza l'ora del NAS = niente).

### R18. Il rinnovo della richiesta senza QR non usa il GUID gia' fissato e riporta a 0 la versione minima

- **Gravita':** bassa (stesso livello di L22, ma su un secondo contatto, quando il GUID si conosce gia').
- **Dove:** `feature-onboarding/.../OnboardingViewModel.kt:964-971` (`startRenewal` riapre l'ingresso manuale senza lo stato della famiglia); `core-family/.../invite/DefaultJoinFlow.kt:133-161` (percorso manuale: `expectedGuid = null`, la password parte prima del confronto con il GUID firmato), `:194` (`JoinRecord` con la versione minima dell'invito nuovo, 0 senza QR), `:264` (l'ancora del rinnovo con quella versione).
- **Scenario (a, e):** durante il rinnovo l'utente digita un IP sbagliato, o un impostore in LAN risponde ripetendo il GUID pubblico: l'NTLMv2 parte verso un NAS che il dispositivo sapeva gia' non essere quello giusto; e il registro accettato al rinnovo si verifica con versione minima 0.
- **Confermato:** **si'**.
- **Correzione proposta:** nel rinnovo `expectedGuid` = il GUID gia' fissato (o quello firmato) con `IDENTITY_MISMATCH`, e versione minima = massimo fra la precedente e la nuova.
- **Stato: corretta** (20/10). `DefaultJoinFlow.start`, nel rinnovo (dispositivo gia' registrato e in attesa), usa il GUID gia' fissato come atteso quando l'invito non ne porta uno (la `NEGOTIATE` lo
  confronta prima della password, quindi un NAS con un altro GUID non vede mai la password), rifiuta senza toccare la rete un invito con un altro GUID (`IDENTITY_MISMATCH`) e non riporta a 0
  la versione minima: il massimo fra quella dell'invito, quella fissata alla prima richiesta e quella dello stato fidato della stessa famiglia (`JoinSession.minRegistryVersion`), usato per
  `JoinRecord` e per l'ancora di `RegistrySync`. La prima richiesta senza QR resta a 0 (L22, non cambia). Test: `CorrezioniIngresso5BTest` (6; i primi 5 rossi sul codice di prima: GUID noto,
  impostore in LAN, invito con un altro GUID, versione minima nel record, registro piu' vecchio = rollback).

### R19. `startOver` ignora il fallimento della distruzione delle chiavi

- **Gravita':** bassa.
- **Dove:** `DefaultOnboardingCoordinator.kt:594-595` (`runCatching` sulla distruzione della chiave del dispositivo e della chiave di famiglia, poi comunque `Advanced`); `core-family/.../registry/RegistryWriter.kt:75-91` (`adminContext` guarda dispositivo approvato e cassaforte, non il segno admin del registro).
- **Scenario (e):** un ex admin rientra come familiare con la chiave di famiglia rimasta nella cassaforte (distruzione fallita in silenzio). Approvato, cambiare indirizzo o impronta di casa (RE-5) **firma** la configurazione della famiglia.
- **Confermato:** plausibile (serve un errore del Keystore).
- **Correzione proposta:** se la distruzione fallisce, `Problem` e niente procede; in `adminContext` richiedere anche `device(me).admin`.
- **Stato: corretta** (20/10). `startOver` non ignora piu' la distruzione delle chiavi: ognuna (dispositivo, poi famiglia) si ripete fino a tre volte con 300 ms, la chiave del dispositivo si
  ricontrolla, e se non riesce risponde `KEYS_NOT_REMOVED` **senza toccare il resto** (account, password, stato fidato, avanzamento): la pagina resta quella di prima e si riprova. Ed e' piu'
  stretto `RegistryWriter.adminContext`: oltre a «approvato» e alla chiave nella cassaforte vuole che il registro firmato segni **questo** dispositivo come admin, cosi' una chiave rimasta su un ex
  admin rientrato come familiare (id nuovo, non admin) non firma piu' indirizzo, impronta di casa o esclusioni. Test: `CorrezioniConfigurazione5BTest` (guasto che dura, guasto passeggero ripetuto),
  `CorrezioniAdmin5BTest` (rosso sul codice di prima: il familiare con la chiave firmava).

---

## Informative

Tutte con stato **aperta**.

- **I1. "Libera spazio" non rilegge i byte del telefono.**
  - **Dove**: `SafeFreeSpace.kt:494-497` (del telefono si guarda solo il timbro); `FreeSpaceEvaluator.kt:201-211`; `MediaCatalog.kt:1963-1979` (`SameFile` aggiorna il timbro e tiene la versione); `LinkingRules.kt:44-52`, `:86-98` (allo stesso posto, e per il ritorno a una versione nota, bastano dimensione e `qh1`); limite di `qh1` in `Fingerprint.kt:60-75`.
  - **Il punto**: "i byte che lasciano il telefono sono quelli verificati sul NAS" regge sul collegamento del catalogo. Un file cambiato solo nel mezzo, con la stessa dimensione e gli stessi 64 KiB di testa e coda, resta sulla versione verificata e si libera. Raro (RAW, DNG, BMP ritoccati nel mezzo; un file ricreato allo stesso nome).
  - **Proposta**: nella verifica sul momento rileggere anche la riga del telefono (locale, veloce) e pretendere lo SHA-256 della versione; e chiedere l'hash completo prima di un `RevertedToVersion` del telefono.
- **I2. Il dialogo di sistema di "Libera spazio" non scade.**
  - **Dove**: `SafeFreeSpace.kt:567-596` (`recordOutcome` non guarda la validita'); `FreeSpaceFlow.kt:104-130`.
  - **Il punto**: il controllo finale e' "subito prima del dialogo", ma un dialogo lasciato aperto (o sopravvissuto al processo) si puo' confermare ore dopo; nel frattempo la riga del telefono puo' essere stata modificata sul posto (Samsung "Salva", stesso `_ID`) e il definitivo la toglie. E' L39 dal lato del telefono; dopo una morte del processo `settle()` puo' segnare `KEPT` righe che l'utente confermera' dopo.
  - **Proposta**: dirlo in L39; dopo la risposta confrontare il timbro delle righe tolte con quello del lotto e, se diverso, segnalare il media.
- **I3. "Elimina dal telefono" dalla selezione della Libreria salta D22.**
  - **Dove**: `LibraryParts.kt:470-526`, `LibraryStates.kt:99-105`, `strings_library.xml:73`.
  - **Il punto**: il cestino di Android per ogni media con una copia sul telefono, senza il foglio di "Elimina solo dal telefono" e senza "Prima mettilo al sicuro"; il testo dice «Non c'e' ancora una copia sul NAS» anche per i media che ce l'hanno. Sbaglia dalla parte prudente (30 giorni nel cestino, avviso di perdita), ma non e' D22.
  - **Proposta**: con il backup legato, mandare la selezione al foglio della 5B come fa il viewer; testo vero.
- **I4. Il selettore nei recenti del task dell'altra app.** `taskAffinity=""` e niente `singleTask`: il selettore vive nel task di chi lo chiede, e l'anteprima di quel task nei recenti puo' mostrare la griglia della Libreria anche con il blocco acceso (l'oscuramento dei recenti e' della sola `MainActivity`). Proposta: `setRecentsScreenshotEnabled(false)` (o `FLAG_SECURE` con il blocco acceso) nel selettore.
- **I5. NA-1: una conferma scaduta nell'attesa non ferma un'operazione in coda.** `docs/decisioni.md` ("Correzioni funzionali: Libreria e NAS", Sicurezza) lo dice, ma `NasOpsEngine.kt:261-306` controlla la conferma solo alla registrazione: un'operazione confermata ore prima parte quando il lucchetto si libera. Regge con i timbri di ogni elemento (E-D13) ed e' coerente con la ripresa dal diario; il documento va corretto.
- **I6. Costo delle voci ostili di "Separa".** Un dispositivo dello stesso account puo' scrivere molte voci `similar_apart` con id che non si risolvono e impronte diverse: ognuna costa un `resolve` e una ricerca per impronta a ogni pagina del rilevatore e a ogni separazione applicata (`SimilarCopiesChoices.separations`, `applySimilarApart`). Nessun crash. Proposta: ricordare per tutto il giro le voci non risolte.
- **I7. I nomi delle cartelle con soli nascosti.** `IntegrationsAdapters.kt:128-146` (`CatalogNasFolders.children`) elenca le cartelle dalle posizioni presenti senza guardare i nascosti, e `LibraryMediaDocuments.kt:140-143` le mostra nel selettore di documenti e in "Salva sul NAS": una cartella con soli media nascosti compare con il suo nome (vuota). Proposta: elencare solo le cartelle con almeno un media della vista.
- **I8. La regola `mcg-` delle versioni vale anche in release.** `NasBackupOps.kt:251-264`: una cartella dell'utente `mcg-viaggio/` avrebbe la sua `Versioni/` interna e i file sotto `mcg-viaggio/Versioni/` sarebbero trattati come versioni. Innocuo; la regola andrebbe legata alla recinzione attiva.
- **I9. Il cancello dei file in arrivo non e' provato nel bytecode di release.** `IncomingGate` (`FiveBAdapters.kt:335-353`) e' un secondo cancello che scrive sul NAS, ma `check.ps1` prova D12 e R2 della 5A solo per `AppBackupGate.stateOf`. Proposta: lo stesso passo per `IncomingGate.stateOf`.
- **I10. F3 senza `fileId`.** Se il NAS restituisse un `fileId` nullo (`SmbjTransport` rende null uno 0), `sameIdentity` ricadrebbe su dimensione e data: una copia fatta da Esplora risorse, che conserva la data, sarebbe presa per "la mia" e terrebbe la verifica. Proposta: senza `fileId` rileggere sempre. Da guardare sul WD (sotto).
- **I11. Le versioni toccano qualunque copia privata del media, non solo `Backup/`.** `VersionPlanning.kt:25-53`: un file messo dal PC in `Foto/2019/`, uguale a una foto del telefono, dopo una modifica sul telefono va in `Versioni/Foto/2019/` e al suo posto c'e' la modifica; con piu' copie vince la posizione piu' vecchia. Coerente con D17, ma va detto in L43.
- **I12. D13 in debug: due punti non recintati.** La recinzione del riscrittore usa la cartella del **catalogo** (`FiveBGraph.kt:214`, `MediaCatalog.phoneRowFolder`), non la riga viva come `AppPhoneSystemDialogs` (una riga spostata fuori da `mcg-prova` dopo l'ultima lettura si riscrive lo stesso, ma serve anche il dialogo di sistema); la **sorgente** di un ripristino non si controlla contro la radice di prova (`NasVersionEngine.kt:127-132` guarda solo il solito percorso): una copia verificata fuori da `mcg-campo/` puo' essere spostata dentro. Solo build di debug con account di prova.

---

## Cose fatte bene

- **"Libera spazio"**:
  - piano solo dal catalogo, due fonti per i media con piu' versioni, nessun dato del NAS che aggiunge un elemento;
  - rilettura completa con lo SHA-256 letto passato al catalogo, timbro prima e dopo, controllo finale di riga e copie prima di ogni dialogo;
  - validita' monotona e per avvio, scritta nel diario e mai allungata; lotti scritti prima di uscire; esiti sconosciuti risolti solo con una prova;
  - due gesti con il pulsante che cambia posto, piu' il dialogo di sistema.
- **Porte esportate**: seconda cintura nel provider anche per `getType`; id opachi da 144 bit e mai un oracolo; nascosti ricontrollati a ogni passo e id revocati; sola lettura; il cancello d'ingresso ora guarda l'ora di adesso e si richiude anche senza la `MainActivity`; URI ostili rifiutati senza aprirli; tempi massimi su ogni chiamata a un provider altrui.
- **Versioni**: D2 per costruzione anche con le due rinomine nuove (timbro obbligatorio sullo stesso handle, una sola estremita' in `Versioni/`, nessuna sostituzione, nessuna cartella creata); il catalogo segue ogni rinomina prima del passo del diario; F1-F3 chiuse con le regole proposte rese piu' strette (rilettura invece di fiducia); il banco delle interruzioni senza evitamenti.
- **File in arrivo**: lo stesso caricamento sicuro del backup, con lo SHA-256 del tocco ricontrollato e la copia locale tolta solo dopo il collegamento; un cancello per la share di destinazione con D12 e R2 della 5A.
- **Correzioni**: niente si tocca prima della rilettura e della decodifica; prima riga del telefono riletta contro lo SHA-256 della versione al sicuro; riscrittura senza troncare prima di scrivere, con rilettura e ripristino.
- **Codice di fiducia**: RE-9 piu' stretto (nessuna connessione senza password); NA-4 vale meno di prima (una volta, con scadenza, per share); "Separa" senza una versione nuova del formato e senza poter scoprire un nascosto.
- **Privacy**: nessun log in release, Diagnostica ed esportazione a soli codici e numeri, contatori delle integrazioni in insiemi chiusi.

## Da provare sul campo

Solo `mcg-prova`/`mcg-campo` con D13, sull'S25, e l'area di prova del NAS.
- **R1, R2 (F42 esteso)**: condividere "senza posizione" e leggere il file ricevuto con `exiftool -a -G1 -ee -gps:all -xyz -location* -xmp:all`:
  - una foto e un video di un drone o di una dashcam, se la famiglia ne ha;
  - una foto passata da Lightroom o Photoshop (XMP esteso, APP13);
  - un PNG con XMP in `tEXt`;
  - una motion photo di Samsung;
  - un video normale della fotocamera Samsung, per vedere che non ci siano tracce di metadati in piu' (un falso positivo dopo la correzione di R1).
- **R3**: una foto in `mcg-prova` modificata due volte con Samsung, la seconda prima del giro del backup (preset "Notte" di giorno), poi "Ripristina originale" dall'app. Dopo la correzione: "prima la metto al sicuro". Oggi: confrontare dal PC che la seconda modifica non e' piu' da nessuna parte (senza usare foto vere).
- **R4**: impostazione accesa, selezione di 3 foto con posizione in Libreria, "Condividi" verso Gmail a se stessi; `exiftool` sull'allegato.
- **R5**: un arrivo in coda verso `test-comune` e poi l'area comune in sola lettura per l'account di prova (o congelata con il banco F9): le foto nuove devono continuare ad arrivare nell'area privata.
- **R8**: un'app che disegna sopra (un filtro dello schermo) mentre si apre il selettore da un'altra app: i tocchi devono essere scartati dopo la correzione.
- **R9, R10**: `kill -9` durante la riscrittura di "Ripristina originale" su un video grande in `mcg-prova`; poi controllare che la riga sia intera e che `©xyz` ci sia ancora.
- **I2**: "Libera spazio" con il dialogo di sistema aperto, app uccisa, conferma del dialogo dopo la riapertura: il diario e il catalogo devono dire "liberato".
- **I10**: sul WD, `fileId` di un file prima e dopo una rinomina e di una copia fatta da Esplora risorse (non deve mai essere 0).
- **Tolleranza di 2 minuti**: sblocco nella ricezione, poi entro 2 minuti `am start -a android.intent.action.GET_CONTENT -t image/*` da `adb`: il selettore si apre senza sblocco; dopo 2 minuti chiede lo sblocco.
- **F39 con `adb`** come previsto: `content query --uri content://<pacchetto>.integrations.documents/root` da `adb shell` deve dare `SecurityException`; un URI consegnato dopo 6 ore non si apre.
- **R7**: non si simula un guasto del Keystore; dopo la correzione, controllare che la pagina "fuori dalla famiglia" chieda la conferma. Residuo (20/10): la pagina «Keystore non disponibile» si guarda
  solo nelle prove a schermo (`ScaffoldScreenshotTest.fermoChiavi`); sul telefono si controlla che un avvio normale non passi mai da «fuori dalla famiglia» ne' da questa pagina.
- **Il resto, come previsto**: F34-F43 della 5B (`docs/decisioni.md`, «Fase 5B: P14»), il giro `run5b` sul NAS vero con il codice dopo F1-F3.

## Checklist della 5B (`docs/sicurezza.md` §7, "Fase 5")

Le voci generali della Fase 5 che riguardano la 5B:
- [ ] **Prova delle 500 interruzioni.**
  - Fatto per la 5B: banco JVM e simulatore SMB verdi senza evitamenti dopo F1-F3; `IncomingEveryCallTest` per il cammino `INCOMING`; sul NAS vero 401 interruzioni senza violazioni, **prima** di F1-F3.
  - Manca: il giro sul NAS vero con il codice di adesso e F43 sul telefono. Il banco non copre R5 (giri misti con un arrivo verso un'altra share) ne' R14.
- [ ] **"Libera spazio" con copia corrotta o mancante: l'elemento non viene liberato. Avviso forte visibile.**
  - Regge in lettura (risposta 1) e l'avviso forte e' in ogni piano e in ogni foglio.
  - Manca F37. Resta R3 come via indiretta, e I1, I2.
- [ ] **Condivisioni in arrivo: limiti, tipo verificato dai byte, rifiuto di URI di file dell'app, flussi copiati subito.**
  - Regge in lettura (risposta 2, ultime voci; risposta 4).
  - Manca F40. Dopo la copia restavano R5, R14, R15 (corrette il 20/10).
- [ ] **Condividere con "rimuovi posizione": il file non contiene GPS.**
  - **Non regge**: R1, R2, R4; per i video i lettori non sono indipendenti.
  - Dopo le correzioni 5B (20/10): R1, R2, R4 corrette (stripper per elenco di cio' che si tiene, rilevatore indipendente, ogni "Condividi" con
    l'impostazione). Manca F42 con `exiftool` sui file veri.
- [ ] **FileProvider: permessi revocati dopo l'uso; flag di lettura espliciti sugli URI condivisi (Android 17 e 18).**
  - Regge in lettura: solo `share/` ed `edit/`, `ClipData` e solo lettura per le condivisioni, revoca prima di cancellare.
  - `FileProvider.getUriForFile` non si prova su JVM: F42.
- [x] **"Modifica con…": l'originale resta conservato, nessuna sovrascrittura.**
  - Regge per "Modifica con…" (sul NAS una versione, mai una sostituzione).
  - Non copriva "Ripristina originale", che sovrascrive la riga del telefono (R3), ne' il controllo del tipo del risultato (R11).

Le voci (5B):
- [ ] **"Libera spazio" con copia corrotta, mancante o cambiata dopo la verifica: l'elemento non si libera (F37); l'avviso forte e' visibile.**
  - Regge in lettura; i test del P9 (`FattoQuando55Test`, `FreeSpaceEveryCallTest`) e del P14 coprono i casi.
  - Manca F37. Restano I1 e I2.
- [ ] **`PhoneConfirmation` solo dalla UI; due gesti per "Libera subito"; scudo in ogni foglio.**
  - Regge in lettura: nasce solo dal gestore di un tocco (`ConfirmTaps`, fogli Versioni e Correggi, `ReceiveViewModel.save`); due gesti nel contratto e nella UI; `SheetShieldTest` per ogni foglio della 5B.
  - Non sono fogli ma vanno nella stessa famiglia: il selettore senza protezione dalle sovrapposizioni (R8) e "Configura di nuovo" senza foglio (R7).
- [ ] **Componenti esportati solo quelli approvati; nessuna azione senza tocco (prova con `adb`).**
  - Regge in lettura (risposta 2); manifest e policy d'accordo.
  - Manca la prova con `adb` (F39). Resta R8.
- [x] **Selettore e provider senza nascosti; blocco dell'app rispettato.**
  - Confermato in lettura: vista di adesso a ogni passo, id revocati, cancello valutato adesso.
  - Restano I4 (recenti) e I7 (nomi delle cartelle); la tolleranza di 2 minuti e' la scelta dichiarata.
- [x] **Correzioni e "Ripristina originale" sul telefono solo con il dialogo di sistema e l'originale al sicuro.**
  - La voce, cosi' come e' scritta, e' confermata: ogni riscrittura vuole il permesso del dialogo; l'originale (o la versione corretta) e' al sicuro e riletto prima.
  - Pero' non copriva R3: si propone di aggiungere alla stessa riga «**e la versione che si sovrascrive sul telefono e' al sicuro, controllata sui byte della riga di adesso**».
  - 20/10: R3 corretta (la regola del riscrittore sopra); la riga della checklist puo' prendere quella frase.

Delle voci "per ogni fase, sempre":
- nessun permesso Android nuovo; i soli componenti esportati nuovi sono i tre approvati (confermato);
- log, Diagnostica, esportazione e notifiche senza nomi, percorsi, share, account, id o impronte (confermato in lettura; resta la sessione vera);
- questa revisione vale come passo di `security-review` della 5B e delle correzioni dopo la 5A.
