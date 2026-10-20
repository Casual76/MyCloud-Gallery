# Verifica funzionale: Fase 5B (commit `eca476e`, 20/10/2026)

**La domanda.** Ogni funzione della 5B promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release? Questa non e' una
revisione di sicurezza: quella sta in un altro file di `docs/review/`.

**Come.** Ho letto il codice committato, senza eseguire niente: niente Gradle, telefono, emulatori, `adb` o NAS. Per ogni flusso dell'utente ho seguito
questa catena: schermata o tasto → ViewModel → porta → oggetto legato in `:app` (Hilt, `BackupGraph`/`FiveBGraph`, `IntegrationsGraph`, debug e
release) → codice che tocca NAS o telefono → risultato che torna alla schermata. In piu' ho confrontato i fake di `FiveBIntegrationTest`,
`FiveBButtonsTest` e dei test di P8–P12 con le implementazioni vere. Tre sotto-agenti hanno letto in parallelo, con lo stesso metodo, "Libera spazio",
le integrazioni e la parte trasversale (lavori, Diagnostica, prove guidate). Le loro voci piu' gravi le ho ricontrollate nel codice. Le altre le
riporto con lo stato che hanno dato loro.

**Il modello dei difetti** e' il catalogo di `docs/review/funzionale-fasi-1-5a.md` (59 voci, tutte corrette):
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object;
- un lavoro che finisce a vuoto, o che nessuno programma;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, D13, flag) che rompe una funzione.

**Perimetro.** La 5B cosi' come P14 l'ha collegata:
- versioni e correzioni (P8);
- "Libera spazio" e cancellazioni dal telefono (P9);
- selettore e provider dei documenti (P10);
- ricezione, "Modifica con…" e condivisione senza posizione (P11);
- la UI (P12);
- le prove guidate T-09, T-14, T-15 e T-16;
- "Separa" e "Mostra come principale" fra dispositivi;
- lavori, stato e Diagnostica.

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravità:**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `core-backup/...`
sta per `core-backup/src/main/kotlin/dev/pampa/mycloudgallery/core/backup/...`, e cosi' per gli altri moduli. I numeri di riga sono quelli di `eca476e`:
il commit successivo, `2b7ea2c`, tocca solo `docs/decisioni.md`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Versioni, correzioni, "Da decidere" (VE) | 1 | 4 | 5 | 10 | 9 | 1 |
| "Modifica con…" e "Condividi" (MC) | 1 | 3 | 2 | 6 | 6 | 0 |
| "Libera spazio" ed eliminazioni dal telefono (LS) | 2 | 6 | 7 | 15 | 11 | 4 |
| Integrazioni: selettore, documenti, ricezione (IN) | 2 | 7 | 4 | 13 | 5 | 8 |
| Lavori, Diagnostica, "Separa", prove guidate, debug/release (LA) | 0 | 4 | 4 | 8 | 6 | 2 |
| **Totale** | **6** | **24** | **22** | **52** | **37** | **15** |

**Il quadro.**
- A processo caldo, con l'app aperta e sbloccata e su una share di prova in debug, i cammini principali arrivano in fondo:
  - Samsung "Salva" → versione → "Ripristina originale";
  - selettore;
  - "Salva sul NAS";
  - "Libera spazio" con il dialogo.
- I difetti stanno nei bordi:
  - **cosa succede dopo il tocco**: chi programma il lavoro, quanto vive la copia, cosa dice la schermata;
  - **il processo freddo**, che per le integrazioni e' il caso normale;
  - **la release**, dove D12 e R2 chiudono tutto, ma con frasi sbagliate.

**Le cose piu' gravi:**
- **"Condividi" dal viewer toglie la copia e revoca il permesso 3,2 secondi dopo aver aperto la scelta dell'app (MC-1).**
  - Vale per "Rimuovi la posizione" acceso e per ogni solo-NAS.
  - WhatsApp e Telegram, che leggono il file dopo che l'utente ha scelto la chat, ricevono un file che non c'e' piu'.
  - Inoltre la selezione della Libreria condivide sempre con il GPS (MC-2).
- **I dialoghi di sistema si chiedono con l'indirizzo generico `files/<id>` (VE-2).**
  - "Ripristina originale" e "Correggi" sul telefono lo fanno sempre; "Libera spazio" quando manca il MIME.
  - Il MediaProvider di AOSP accetta solo URI di immagini, video e audio, e nessuno intercetta l'eccezione: probabile chiusura dell'app al tocco.
  - Se invece passa, la scrittura apre un altro URI.
  - La prova guidata T-09 usa l'URI giusto: sul telefono non lo vedrebbe.
- **Le integrazioni a processo freddo:**
  - la pulizia d'avvio cancella la condivisione appena copiata prima di "Salva sul NAS" (IN-1);
  - selettore e ricezione dicono "Prima configura MyCloud Gallery" (IN-3);
  - gli id della radice del provider cambiano a ogni processo, e DocumentsUI li tiene in cache: dopo un riavvio Gmail "Allega file" non apre piu'
    MyCloud Gallery (IN-2).
- **In release (D12/R2, fino a Q3) "Elimina… → Solo dal telefono" del viewer non elimina piu' niente (LS-1).**
  - E' una regressione rispetto alla 4B.
  - "Salva sul NAS" fallisce con "In quella cartella non si può scrivere" (IN-6).
- **Alcuni lavori nati da un gesto non li programma nessuno, o li programmano le regole sbagliate:**
  - la modifica di un solo-NAS aspetta il giro periodico (MC-3) e, nell'area comune, non sale mai (MC-4);
  - "Salva sul NAS" di una foto con il preset Notte (il predefinito) aspetta la notte (IN-5);
  - un video modificato sale con le regole delle foto e ritarda "Proteggi subito" (VE-1).
- **"Libera spazio": la verifica vive quanto la pagina.**
  - Non c'e' il lavoro avviato dall'utente, non c'e' la notifica "Pronto", non c'e' ripresa (LS-2).
  - Dal viewer la pagina si apre sotto il viewer (LS-3).
  - "Prima mettilo al sicuro" e "Metti al sicuro adesso" sono vicoli ciechi (LS-4, VE-3).

---

## 2. I difetti

### 2.1 Versioni, correzioni, "Da decidere" (VE)

#### VE-1 · I video modificati salgono con le regole delle foto e di "Proteggi subito", e ritardano la foto nuova
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Flusso:** un video gia' al sicuro viene modificato e salvato sopra in Samsung Gallery (F35); intanto si scattano foto (D34).
- **Percorso:**
  - `VersionedBackupEngine.run` (`core-backup/.../versions/FiveBFactory.kt:154-172`) pianifica le versioni del tipo del giro.
  - Poi chiama `versions.run(budget)`, che guida **tutti** i lavori aperti del diario, di ogni tipo (`NasVersionEngine.run`,
    `NasVersionEngine.kt:192-209`): non filtra ne' per foto/video ne' per trigger.
  - Una volta pianificato, il `VERSION` del video non lo riprende piu' il giro dei video:
    - `BackupGraph.hasWork(VIDEOS)` guarda solo il piano e gli elementi `new_copy` (`app/.../backup/BackupGraph.kt:565-572`);
    - `hasOpenVersionWork` fa chiedere il giro delle **foto** per ogni lavoro di versione aperto (`BackupGraph.kt:533-535`, `:575-582`).
  - Il giro `QUICK_NEW_PHOTOS` passa dallo stesso motore (`work/BackupStages.kt:104-108`, `:190`): prima tutte le versioni, poi la foto nuova.
- **Scenario:**
  - L'utente taglia un video da 3 GB e lo salva. La versione (6 minuti a 9 MB/s) sale di giorno, a batteria, con le regole di `PHOTO_BACKUP` o di D34.
    Dovrebbero valere quelle di `VIDEO_BACKUP`: con Notte, in carica e in fascia.
  - Se intanto scatta una foto, "Proteggi subito" spende il suo tempo sul video e la foto aspetta. E' BK-1, rientrato dalla 5B.
  - Il traffico va nel conto di `PHOTO_BACKUP`.
- **Perche' i test passano:** `FiveBIntegrationTest` e il banco della 5B chiamano il giro o il motore direttamente, senza il tipo del giro.
- **Correzione:**
  - un filtro per tipo di media in `NasVersionEngine.run` (`BackupKind.of`);
  - con `QUICK_NEW_PHOTOS`, solo le versioni delle foto pianificate dal giro stesso (come `only` di BK-1);
  - `hasWork(VIDEOS)` deve contare i lavori di versione dei video, e `hasOpenVersionWork` deve chiedere il giro del tipo giusto.
- **Test:** in `VerificaFunzionaleBackupAppTest`, un `VERSION` di video aperto e una foto nuova, giro `QUICK` con il budget per un file:
  - la foto deve finire, il video restare;
  - `afterPhoneRead` deve chiedere `VIDEOS`.
- **Stato: corretta** (20/10, `docs/decisioni.md` «Correzioni 5B: versioni e correzioni»). `NasVersionEngine.run(..., kinds, only)` guida solo i lavori
  dei media del tipo del giro (`BackupKind.of`); `VersionedBackupEngine` passa il tipo, e con `QUICK_NEW_PHOTOS` solo le versioni pianificate dal giro
  stesso (`only`, come BK-1). `BackupGraph.hasWork` e `nextAll` contano i lavori delle versioni per tipo di media (`hasOpenVersionWork(kind, …)`): un video
  modificato chiede il lavoro dei video, con le regole di `VIDEO_BACKUP`. Test: `Revisione5BVersioniTest.ve1_*` (motore), `Correzioni5BVersioniAppTest.ve1_*`
  (grafo vero: un giro delle foto non lo tocca, quello dei video lo finisce; `nextAll` = solo video), rossi prima.

#### VE-2 · I dialoghi di sistema si chiedono con l'indirizzo generico `files/<id>`: probabile chiusura dell'app, o permesso che non serve
- **Gravità:** Blocca (se la prova conferma). **Stato:** plausibile, probabile.
- **Flusso:**
  - viewer → ⋮ → "Ripristina originale" di una foto modificata sul telefono (E-E10);
  - "Correggi data e luogo" / "Ruota" di un JPEG del telefono (D19);
  - "Libera spazio" di un media senza MIME nel catalogo.
- **Percorso:**
  - `AppPhoneSystemDialogs.writeRequest` costruisce `MediaRef.Phone(volume, id, null)`, cioe' **senza tipo** (`app/.../backup/FiveBAdapters.kt:304-311`).
  - Con il tipo nullo `AndroidPhoneMediaIntents.uriOf` usa la collezione `Files` (`core-mediastore/.../android/AndroidPhoneMediaIntents.kt:38-46`),
    quindi `createWriteRequest` riceve `content://media/<vol>/file/<id>` (`:79-83`).
  - Due esiti possibili, tutti e due cattivi:
    - **(a)** Il MediaProvider di AOSP accetta nelle richieste solo URI delle collezioni immagini, video e audio, e per un `files/<id>` lancia
      `IllegalArgumentException`. Nessuno la intercetta: `AndroidPhoneMediaIntents.writeRequest`, `AppPhoneSystemDialogs.writeRequest`,
      `VersionsViewModel.askWrite` (`feature-backup/.../versions/VersionsViewModel.kt:162-165`) e `CorrectionViewModel.askWrite` (`:226-229`) girano
      tutti in `viewModelScope`. Risultato: **l'app si chiude** al tocco su "Ripristina originale" o "Correggi".
    - **(b)** Se la richiesta passa, il permesso vale per `files/<id>`, ma la riscrittura apre l'URI tipizzato.
      `AppPhoneRewriter` (`FiveBAdapters.kt:265-275`, chiamato da `DefaultVersionKeeper.kt:164` e `DefaultCorrections.rewrite`) passa il tipo, e
      `MediaStorePhoneMediaRewriter` apre `images/media/<id>` o `video/media/<id>` (`core-mediastore/.../write/MediaStorePhoneMediaRewriter.kt:79-81`,
      `:140-141`). Se il permesso non vale anche li', l'esito e' `NotGranted`: "sul telefono resta la versione modificata", oppure `PHONE_WRITE_FAILED`.
  - Lo stesso succede al cestino di "Libera spazio" quando il catalogo non ha il MIME della versione attuale (metadati non letti, formato non supportato):
    - `CatalogFreeSpaceCatalog` prende il tipo da `versionMetadata` (`FiveBAdapters.kt:223`);
    - `removeRequest` lo passa com'e' (`:296-302`), e `createTrashRequest` riceve `files/<id>` (`AndroidPhoneMediaIntents.kt:65-69`);
    - `FreeSpaceFlow.free` non intercetta (`feature-backup/.../free/FreeSpaceFlow.kt:103-129`): ogni "Libera ora" con quel media nel lotto chiude l'app.
      Al riavvio le righe tornano `KEPT` e il giro dopo le ripropone.
- **Perche' nessuna prova lo vede:**
  - Il telefono finto concede per `(volume, _ID)`, in qualunque collezione (`core-mediastore/.../testing/FakeMediaStore.kt:363-396`,
    `FakePhoneMediaRewriter.kt:33-41`). `FakePhoneMediaIntents` non lancia mai e ignora il tipo.
  - La prova guidata T-09 usa il tipo **in tutte e due** le chiamate (`app/src/debug/.../probes/FieldProbePhone.kt:114`, `FieldProbeT09.kt:136` e `:165`):
    sul telefono prova una coppia di indirizzi che l'app non usa.
  - `FiveBIntegrationTest` (righe 254-257) risolve il dialogo con riferimenti senza tipo, e la riscrittura passa dal riscrittore finto.
- **Correzione:**
  - passare il tipo anche al dialogo: dal catalogo, o dal `MIME_TYPE` della riga, o da `media.kind`, cosi' dialogo e scrittura usano lo stesso URI;
  - mettere `try/catch` attorno a `create*Request`, con un esito dedicato.
  - Aggiungere un test che confronti l'URI del dialogo con quello della scrittura.
- **Prova sul campo (prima di F34–F37):** T-09 con il dialogo costruito come lo costruisce l'app (riferimento senza tipo) e la riscrittura con il tipo;
  "Libera spazio" di un file con i metadati non letti.
- **Stato: corretta** (20/10). La prova sul telefono resta da fare (T-09), ma la correzione non dipende da come risponde il MediaProvider:
  - **l'URI** di ogni richiesta di sistema (cestino, eliminazione, scrittura) e della riscrittura e' quello della collezione del **tipo della riga**, letto
    adesso dal MediaStore (`MEDIA_TYPE`: `MediaStore.Images`/`Video`); se la riga non si legge, quello del MIME; senza nessuno dei due, nessuna richiesta
    (mai `files/`). Lo stesso calcolo (`AndroidPhoneMediaIntents.requestUriOf`) lo usa `MediaStorePhoneMediaRewriter`: dialogo e scrittura hanno lo stesso
    URI. Vale anche per il cestino di "Libera spazio" quando il catalogo non ha il MIME.
  - **le eccezioni**: `create*Request` dentro un `try`; `AppPhoneSystemDialogs` ripete la protezione; `VersionsViewModel` e `CorrectionViewModel`
    intercettano la creazione e il lancio del dialogo e dicono «Il telefono non ha accettato la richiesta di permesso» (diverso da «su questo telefono
    non posso chiederlo»). L'app non si chiude piu' al tocco.
  - **i finti**: `FakeMediaStore` da' il permesso per (collezione, volume, `_ID`) con la regola dell'adattatore vero e rifiuta `files/` come AOSP; il
    riscrittore finto apre la collezione del tipo della riga; il `PhoneSystemDialogs` finto di feature-backup puo' lanciare (`failure`). Test di parita':
    `AndroidSystemRequestsTest.ve2_ilTelefonoFintoSceglieLaCollezioneComeQuelloVero`.
  - **T-09** chiede il dialogo con il riferimento **senza tipo**, come i fogli dell'app (`FieldProbePhone.untypedRefOf`), e riscrive con il tipo: sul
    telefono prova proprio la coppia di indirizzi dell'app (`FieldProbeFlowTest` lo controlla).
  - Test: `AndroidSystemRequestsTest.ve2_*` (3, il primo rosso prima con l'eccezione), `MediaStorePhoneMediaRewriterTest.ve2_dialogoEScritturaUsanoLoStessoUri`
    (permessi per URI: il dialogo senza tipo e la scrittura hanno lo stesso URI), `SystemRequestsFakeTest.ve2_*`, `VersionsViewModelTest.ve2_*`,
    `CorrectionViewModelTest.ve2_*`; `FiveBIntegrationTest` risolve ora proprio la richiesta che l'app ha fatto.

#### VE-3 · "Metti al sicuro adesso" (Correggi) non fa niente che si veda, e per molti media non puo' fare niente
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Flusso:** viewer → ⋮ → "Correggi data e luogo" su un media non al sicuro → foglio "Prima la metto al sicuro" → "Metti al sicuro adesso".
- **Percorso:**
  - `CorrectionViewModel.safeguardNow` chiama solo `BackupActions.backupNow()` (`feature-backup/.../versions/CorrectionViewModel.kt:164-167`).
    Lo stato del foglio non cambia (resta `NeedsSafeFirst`, `CorrectionSheet.kt:155-166`), niente si ricontrolla e niente riapre il foglio.
  - `backupNow` e' "Fai ora" (`app/.../backup/BackupUi.kt:389-391`): carica cio' che il piano propone **dalle sorgenti**.
    - Un media fuori dalle sorgenti (una foto di WhatsApp con la cartella non scelta, un file di `Download/`) non diventa mai candidato.
    - Prima del primo backup, cioe' senza cartella del dispositivo, il giro non parte (`work/BackupStages.kt:110`).
  - Un **solo-NAS** letto dalla scansione resta `NEEDS_SAFE_FIRST` finche' la copia non ha l'hash completo e la verifica
    (`core-backup/.../corrections/DefaultCorrections.kt:125`). La porta solo il lavoro notturno delle verifiche (D14), non "Fai ora".
  - Il testo promette cio' che il tasto non fa: "Questa foto non ha ancora una copia controllata sul NAS. La metto al sicuro…"
    (`feature-backup/src/main/res/values/strings_backup_correction.xml:62-64`).
- **Scenario:** l'utente vuole correggere la data di una vecchia foto che sta solo sul NAS. Tocca "Metti al sicuro adesso" e il foglio resta uguale; non
  arriva nessuna notifica, perche' non c'e' niente da caricare. Riaprendo il foglio, per giorni e' ancora "Prima la metto al sicuro".
- **Correzione:**
  - per un solo-NAS, la verifica sul momento di quella copia (`SafetyVerifier` con `OnTheSpot(ids)`, come "Libera spazio");
  - per un media fuori dalle sorgenti, dirlo e non offrire il tasto;
  - in ogni caso, uno stato "sto mettendo al sicuro…" che ricontrolla la prontezza e riapre la correzione.
  - Stessa cosa per LS-4.
- **Test:** `CorrectionViewModel` con il servizio vero su `AppGraph` e un solo-NAS non verificato: al tocco lo stato deve cambiare e la verifica partire.
- **Stato: corretta** (20/10). `Corrections.safeguard(mediaId)` (lo lega `:app`, `BackupGraph.safeguard`): una copia privata non riletta (un solo-NAS) →
  la verifica sul momento di quella copia (`OnTheSpot`, come "Libera spazio", uno alla volta con il backup); una riga del telefono in una sorgente →
  "Fai ora"; fuori dalle sorgenti → `NOT_IN_SOURCES`. Il foglio mostra "La metto al sicuro…", ricontrolla la prontezza ogni 3 secondi e si riapre da solo
  quando e' al sicuro; fuori dalle sorgenti lo dice e il tasto non c'e'; "Non posso adesso" e "non e' ancora finito" hanno la loro frase. Test:
  `Correzioni5BVersioniAppTest.ve3_*` (grafo vero: camera → lavoro dell'utente; `Pictures/Altro/` → fuori dalle sorgenti; solo-NAS → la copia diventa
  verificata), `CorrectionViewModelTest` (il foglio cambia e si riapre; fuori dalle sorgenti), `CorrectionSheetTest.ve3_*`. LS-4 ("Prima mettilo al
  sicuro" di "Elimina solo dal telefono") non e' toccato.

#### VE-4 · "Ruota" di un HEIC o di un RAW dice "fatto", ma la foto non gira da nessuna parte
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Flusso:** viewer → ⋮ → "Ruota" su un HEIC (Q6 (a): correzione solo nell'app).
- **Percorso:**
  - La voce compare per ogni foto (`feature-viewer/.../menu/BackupActions.kt:212-214`).
  - Il servizio scrive la correzione solo nel catalogo (`MediaCatalog.correctInApp`, `core-database/.../MediaCatalog.kt:1303-1315`).
  - Miniature, viewer e anteprime del NAS leggono l'orientamento dai **byte del file**, mai dal catalogo (`core-imaging/.../decode/ImageProbe.kt:89`,
    `:101-108`).
  - Il foglio finisce con "Corretta solo nell'app… la data e il luogo nuovi valgono nell'app" (`strings_backup_correction.xml:74-75`).
- **Scenario:** l'utente ruota un HEIC storto e legge "Corretta solo nell'app", ma griglia e viewer lo mostrano storto come prima. La data e il luogo
  corretti invece si vedono: timeline e pannello informazioni leggono il catalogo.
- **Correzione:** una delle due:
  - la pipeline delle immagini applica la rotazione di `metadata_correction` a miniature e viewer;
  - "Ruota" non si offre per i formati che non si scrivono.
- **Stato: corretta** (20/10), con la seconda strada: la rotazione di un formato che non si scrive **non si fa e non dice "fatto"** (la prima, applicare
  la rotazione del catalogo a miniature e viewer, toccherebbe core-imaging, fuori perimetro). Il menu del viewer non offre "Ruota" per HEIC e RAW
  (`BackupMediaActions.canRotate`: il formato si scrive?); il foglio "Correggi" di un formato che non si scrive non ha la sezione della rotazione; il
  servizio risponde `ROTATION_NOT_WRITABLE` a una richiesta con la rotazione e non la registra. Data e luogo restano correggibili nell'app (Q6 (a)).
  Test: `Revisione5BVersioniTest.ve4_*`, `BackupActionsTest.ve4_*` (2), `CorrectionSheetTest.ve4_*`.

#### VE-5 · Ruotare lo schermo durante "Ripristina originale" o una correzione interrompe il lavoro, e il permesso dato si perde
- **Gravità:** Minore. **Stato:** confermato nel codice.
- **Percorso:**
  - `VersionsSheetRoute` e `CorrectionSheetRoute` chiamano `reset()` in un `LaunchedEffect` a ogni composizione nuova con il foglio visibile
    (`feature-backup/.../versions/VersionsSheet.kt:76-81`, `CorrectionSheet.kt:83-88`).
  - `reset()` cancella il lavoro in corso (`VersionsViewModel.kt:69-73`, `CorrectionViewModel.kt:94-100`).
  - La `MainActivity` non dichiara `configChanges` (`app/src/main/AndroidManifest.xml:60-69`), quindi una rotazione ricrea la composizione.
  - Il ponte dei dialoghi e' fatto per sopravvivere alla rotazione (`feature-backup/.../free/DialogBridge.kt:14-19`), ma il lavoro che aspettava la
    risposta non c'e' piu'.
- **Scenario:**
  - Il telefono ruota mentre e' aperto "Consentire a MyCloud Gallery di modificare questa foto?". L'utente tocca Consenti, ma il foglio e' tornato
    all'inizio e non succede niente.
  - Se la rotazione cade durante lo scambio, lo scambio lo finisce il backup, ma la riga del telefono non si riscrive piu'.
- **Correzione:** `reset()` solo alla chiusura del foglio o al cambio di media, non a ogni composizione: per esempio una chiave salvata con
  `rememberSaveable`, oppure lasciare decidere a `bind` ("stesso media, niente").
- **Stato: corretta** (20/10). `SheetOpening` (feature-backup): il foglio si azzera solo quando si apre o cambia media o modo; il segno "aperto" sta in
  `rememberSaveable` e sopravvive alla ricreazione, che chiama solo `bind` (stesso media: niente). Il ViewModel e il ponte dei dialoghi restano quelli.
  Test: `SheetOpeningTest.ve5_*` (`StateRestorationTester`: dopo la ricreazione nessun nuovo azzeramento; chiuso e riaperto si', rosso prima).

#### VE-6 · "Da decidere" ed "Elimina le intermedie…": il foglio "Elimina…" non dice perche' non parte, e le frasi compaiono nel viewer piu' tardi
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `VersionDeleteSheet` (`app/.../main/FiveBHostEffect.kt:94-111`) mostra solo i fogli del controller. L'avviso di un piano rifiutato (NAS che non
    risponde, sola lettura, `NasActionsController.kt:136-145`) non si vede da nessuna parte, e il foglio resta con il tasto spento.
  - La richiesta si toglie solo a operazione partita (`FiveBUi.kt:183-188`).
  - Le frasi di `FiveBUi.say` (`FiveBUi.kt:276-278`) le mostra solo il viewer (`BackupMediaActions.notice`). "Eliminazione avviata", toccata da
    "Da decidere", compare la prossima volta che si apre una foto.
  - Senza copie da eliminare la frase e' quella della modifica: "Non posso aprire la modifica adesso" (`FiveBUi.kt:159-168`).
- **Correzione:** l'avviso del controller nel foglio o nella pagina "Da decidere"; la richiesta tolta anche alla chiusura; una frase propria per
  "nessuna copia da eliminare".
- **Stato: corretta in parte** (20/10). Un piano rifiutato del foglio "Elimina…" si dice e il foglio si chiude (`FiveBUi.deleteRefused`); "Eliminazione
  avviata" e "nessuna copia da eliminare" (frase sua) si dicono adesso, alla radice (`FiveBUi.rootNotice`, un avviso di sistema da `FiveBHostEffect`), non
  nel viewer alla prossima foto. Resta: la richiesta non si toglie se l'utente chiude il foglio senza confermare (lo stato del controller della 4B e'
  interno a feature-viewer); una richiesta nuova la sostituisce, quindi non ha effetti.

#### VE-7 · Ragioni diverse finiscono tutte in "non è ancora pronta": pausa, backup in prova, un'altra versione in corso
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `DefaultCorrections.readiness` (`core-backup/.../corrections/DefaultCorrections.kt:117-128`) porta a `NOT_AVAILABLE` quattro casi diversi:
    - il cancello chiuso dalla pausa dell'utente (R4);
    - D12/R2 (`TEST_ONLY`);
    - la famiglia non pronta;
    - un'altra versione in corso.
  - Il foglio dice "La correzione non è ancora pronta." (`strings_backup_correction.xml:91`).
  - `DefaultVersionKeeper.availability` fa lo stesso con la pausa (`DefaultVersionKeeper.kt:110-115`), e il menu toglie "Ripristina originale" senza
    dire perche' (`BackupActions.kt:111-117`).
  - Lo stesso vale per le altre schermate in release: "Libera spazio", Versioni e Correggi dicono "non disponibile"; solo pagina e piede dicono
    "Backup in prova".
- **Scenario:** ieri l'utente ha toccato "Ferma"; oggi "Ripristina originale" non e' piu' nel menu e "Correggi" dice "non è ancora pronta".
- **Correzione:** codici propri (`PAUSED`, `TEST_ONLY`, e `BUSY` anche per le correzioni) con la loro frase; per la pausa, "Riprendi il backup".
- **Stato: corretta** (20/10). `RestoreAvailability.PAUSED`/`TEST_ONLY`, `CorrectionReadiness.PAUSED`/`TEST_ONLY`/`BUSY` (anche il turno che non si libera
  e' `BUSY`), ognuno con la sua frase ("Il backup e' in pausa: riprendilo dalla pagina Backup", "e' ancora in prova"); il menu del viewer offre ancora
  "Ripristina originale" e il foglio dice perche' adesso no. Le altre schermate di release ("Libera spazio") non sono toccate. Test:
  `Revisione5BVersioniTest.ve7_*`, `CorrectionSheetTest.ogniMotivoPerCuiNonSiPuoDiceLaSuaFrase`, `BackupActionsTest.ilRipristinoSiOffreSoloQuandoHaSenso`,
  `VersionsSheetTest.r3_laVersioneNonAlSicuroELaRigaDelTelefono`.

#### VE-8 · "Ripristina originale" e "Correggi" aspettano in silenzio il turno del backup, anche solo per sapere che serve il permesso
- **Gravità:** Minore. **Stato:** confermato nel codice.
- **Percorso:**
  - Le facciate passano ogni `restoreOriginal` e ogni `apply` da `BackupGraph.exclusive`, con 15 minuti di attesa (`app/.../backup/FiveBGraph.kt:340-358`,
    `BackupGraph.kt:209`). Vale **anche per la prima chiamata**, che risponde soltanto `NeedsPhoneWrite`/`NeedsWriteGrant` senza toccare niente
    (`DefaultVersionKeeper.kt:131-132`, `DefaultCorrections.kt:144-147`).
  - Un lavoro dell'utente (primo backup, "Fai ora") non cede il turno (`work/BackupStages.kt:133`) e lo rilascia solo fra una tappa e l'altra, cioe'
    fino a 8 minuti.
  - Intanto il foglio mostra "Sto scambiando…"/"Sto scrivendo…" senza dire che aspetta.
  - Dopo 15 minuti la risposta e' `BUSY`/`NOT_AVAILABLE`, e il foglio torna alla lista senza spiegazioni (`VersionsViewModel.kt:157`).
- **Correzione:** chiedere il permesso prima di entrare nel turno, perche' quali righe servano lo dice gia' il solo catalogo; uno stato "aspetto che il
  backup finisca il lotto".
- **Stato: corretta** (20/10). `VersionKeeper.precheck` e `Corrections.precheck` (piano, conferma, permesso del telefono: niente si tocca) girano **fuori**
  dal turno: le facciate di `FiveBGraph` rispondono subito `NeedsPhoneWrite`/`NeedsWriteGrant` e solo dopo il permesso aspettano il turno. "Ripristino…" e
  "Scrivo la correzione…" dicono che, se il backup sta lavorando, si aspetta che finisca il lotto; un turno che non si libera in 15 minuti torna alla lista
  con la sua frase (`VersionsMessage.BACKUP_BUSY`) o con `BUSY`. Test: `Revisione5BVersioniTest.ve8_*`, `VersionsViewModelTest.ve8_*`.

#### VE-9 · "Versioni in attesa del computer" (`SOURCE_CHANGED`) non compare mai nella pagina Backup ne' nel piede
- **Gravità:** Sbagliata. **Stato:** confermato (agente LA, ricontrollato).
- **Percorso:**
  - `composeStatus` restituisce `waitingStatus` quando `queued > 0` (`BackupGraph.kt:853-856`), prima di guardare `sourceChangedHolds()`
    (`BackupGraph.kt:860`).
  - Una versione ferma per `SOURCE_CHANGED` e' la versione **attuale** del media, che non ha una copia privata verificata: il media conta fra
    `noNasCopy`/`unverified`, quindi `queued >= 1`.
  - Il codice compare solo in Diagnostica (`BackupDiagnostics.kt:88`).
- **Scenario:** Samsung "Salva", poi il PC cambia il file sul NAS. La pagina dice "1 in coda · prossimo giro", mai "Versioni in attesa del computer".
- **Correzione:** guardare le attese prima del ramo `queued > 0`, oppure togliere da `queued` i media con un lavoro fermo per `source_changed`.
- **Test:** un lavoro con `hold='source_changed'` su una foto della fotocamera; leggere `status`.
- **Stato: corretta** (20/10). I media la cui versione attuale aspetta il PC (`FiveBGraph.sourceChangedCurrentMedia`) non contano fra quelli in coda: con
  solo quelli la pagina dice "Versioni in attesa del computer". Test: `Correzioni5BVersioniAppTest.ve9_*` (grafo vero, rosso prima).

#### VE-10 · Scambi e versioni ferme ripartono solo con il giro periodico
- **Gravità:** Minore. **Stato:** confermato (versioni ferme); plausibile (scambio di Samsung).
- **Percorso:**
  - **Versioni ferme.** Un `SOURCE_CHANGED` riparte solo con il periodico delle foto, perche' `hasOpenVersionWork(waiting = false)` le esclude
    (`BackupGraph.kt:531-534`, `:577`). Dopo la scansione del NAS non parte nessun giro del backup. I due periodi da 6 ore sono indipendenti: l'attesa
    puo' arrivare a circa 12 ore. Il testo promette "riparto da solo dopo la prossima verifica del NAS"
    (`feature-backup/src/main/res/values/strings_backup_problems.xml:67`; `feature-library/src/main/res/values/strings_backup_footer.xml:93`).
  - **Scambio dopo "Ripristina originale" di Samsung (E-E2).** `revertsWatcher` e `atStart` scrivono solo nel diario (`BackupGraph.kt:353-354`,
    `FiveBGraph.kt:273-279`). Nel `PhoneSyncWorker`, `afterPhoneRead` interroga `hasOpenVersionWork` pochi millisecondi dopo l'emissione e puo' arrivare
    prima del collector (`work/GalleryWork.kt:103-104`). Lo `SWAP` resta allora aperto fino al prossimo cambiamento del MediaStore o al periodico.
- **Correzione:**
  - `planRealign` e `realign` chiedono il lavoro delle foto;
  - la fine di una scansione con dei `source_changed` aperti lo chiede anche lei;
  - la frase va allineata al comportamento.
- **Stato: corretta** (20/10). `FiveBGraph.realign` (dopo la lettura del telefono e all'avvio) chiede il lavoro del tipo del media appena pianifica uno
  scambio (`BackupJobs.versionWork`, `KEEP`); la fine di una scansione del NAS (`NasScanWorker`) chiede il lavoro dei tipi con versioni ferme per il PC
  (`BackupGraph.afterNasScan`). La frase ("riparto da solo dopo la prossima verifica del NAS") adesso e' vera e non cambia. Test:
  `Correzioni5BVersioniAppTest.ve10_*` (2, rossi prima).

### 2.2 "Modifica con…" e "Condividi" (MC)

#### MC-1 · "Condividi" dal viewer: 3,2 secondi dopo l'apertura della scelta dell'app, le copie si tolgono e i permessi si revocano
- **Gravità:** Blocca, per "Condividi senza posizione" e per i solo-NAS. **Stato:** percorso confermato; quanto spesso l'altra app legge troppo tardi va
  visto in F42.
- **Flusso:** viewer → "Condividi" (con "Rimuovi la posizione" acceso, o su un solo-NAS) → scelta dell'app → WhatsApp / Telegram / Gmail.
- **Percorso:**
  - `FiveBUi.startShare` mette lo stato `Ready` e, nello stesso istante, chiede il lancio della scelta dell'app (`app/.../backup/FiveBUi.kt:297-306`).
  - Il viewer spegne "da solo" l'avviso `Ready` dopo `MessageMillis` (3.200 ms) chiamando `backupController.dismissShare()`
    (`feature-viewer/.../ViewerOverlay.kt:757-762`, `:1035`). E' un `LaunchedEffect`: il `delay` scade anche con l'activity in pausa o ferma.
  - Il `dismissShare` di `:app` non chiude solo l'avviso. `releaseShare()` → `ShareLease.release()` revoca i permessi e **cancella la cartella** delle
    copie (`FiveBUi.kt:352-356`, `:287-290`; `feature-integrations/.../share/ShareIntents.kt:64-70`).
- **Scenario:**
  - Foto con il GPS, "Rimuovi la posizione" acceso. "Condividi", poi l'utente sceglie WhatsApp e la chat. Sono passati piu' di 3 s: la copia senza
    posizione non c'e' piu' e il permesso e' revocato. WhatsApp mostra un errore o un'anteprima vuota.
  - Lo stesso per un solo-NAS: la copia in `cacheDir/share/` e' l'unica fonte.
  - Con l'impostazione spenta, un media del telefono passa per l'URI del MediaStore: chi ha il permesso dei media lo legge comunque.
- **Due rischi vicini (plausibili):**
  - Il lease si rilascia anche appena l'utente torna nell'app (`FiveBUi.kt:244-250`). Gmail, che invia in background, o Telegram, con il suo foglio
    traslucido, possono leggere dopo.
  - Se il nostro processo muore e la lettura dell'altra app lo fa ripartire, `ShareCache.sweepAtStart` svuota `share/`
    (`feature-integrations/.../share/ShareCache.kt:54-57`).
- **Perche' i test passano:**
  - il `BackupMediaActions` finto del viewer conta le chiamate a `dismissShare` e non rilascia niente
    (`feature-viewer/src/test/.../menu/BackupActionsTestSupport.kt:99-101`);
  - `FiveBIntegrationTest.condividiSenzaPosizione…` prova `LocationFreeShare` da solo e rilascia a mano.
- **Correzione:**
  - separare "spegni l'avviso" (solo stato) da "rilascia" (con "Annulla" durante la preparazione, o piu' tardi);
  - tenere la copia per qualche minuto, o fino alla condivisione successiva, e non svuotare all'avvio le copie giovani.
- **Test:** `ViewerBackupMenuTest` con `FiveBUi` vero, o con un finto che rilascia come il vero: 3,2 s dopo `Ready` la copia esiste ancora.
- **Stato:** corretta (20/10, `docs/decisioni.md` «Correzioni 5B: posizione e Condividi»). Spegnere l'avviso e' solo lo stato (`hideShareNotice`); la
  copia consegnata passa al custode (`feature-integrations/.../share/ShareHolder.kt`, legato in `app/.../integrations/AppShares.kt`), che la rilascia
  quando l'altra app ha finito: un minuto dopo il ritorno dell'utente nell'app (un'activity che torna davanti dopo una pausa), comunque dopo mezz'ora, e
  all'avvio successivo (`ShareCache.sweepAtStart`). "Annulla" e la chiusura del foglio non toccano una copia gia' consegnata. Test:
  `ShareHolderTest` (feature-integrations), `CondividiSenzaPosizioneAppTest.mc1_laCopiaConsegnataRestaDopoLAvvisoEFinoAlRitornoNellApp` (:app),
  `ViewerBackupMenuTest.lAvvisoProntaSiSpegneDaSoloMaLaCopiaNonSiRilascia_MC1` (feature-viewer). Il rischio vicino della morte del processo resta (la
  pulizia d'avvio e' la regola "comunque all'avvio successivo"): da guardare in F42.

#### MC-2 · La selezione della Libreria condivide sempre con la posizione
- **Gravità:** Sbagliata: una promessa di privacy non mantenuta. **Stato:** confermato.
- **Flusso:** Impostazioni → Condivisioni → "Rimuovi la posizione" acceso; poi Libreria → selezione → "Condividi".
- **Percorso:**
  - `AppLibraryCommands.share` (`app/.../gallery/LibraryStates.kt:77-85`) costruisce l'intento con `gallery.intents.share(refs)`: gli URI del MediaStore,
    senza `LocationFreeShare` e senza leggere `SharingSettings`. Solo il viewer passa da `FiveBUi.share`.
  - Anche la voce "Copia" del viewer (`feature-viewer/.../ViewerController.kt:371`) mette negli appunti l'URI dell'originale, posizione compresa
    (agente IN).
- **Scenario:** l'utente ha acceso "Rimuovi la posizione" proprio per WhatsApp. Condivide tre foto dalla selezione: partono con il GPS.
- **Correzione:** far passare la selezione da `FiveBUi.share` quando l'impostazione e' accesa, oppure dire nella selezione che la posizione resta.
- **Stato:** corretta (20/10). Con l'impostazione accesa la selezione passa dallo stesso canale del viewer (`AppShares`, anche senza backup), con
  avanzamento e foglio "Condivisione ferma" nella Libreria; "Copia" del viewer non compare. Test: `CondividiSenzaPosizioneAppTest.r4_*` e
  `conLImpostazioneSpentaLaSelezioneRestaQuellaDiPrima` (:app), `ViewerBackupMenuTest.conRimuoviLaPosizioneAccesaCopiaNonCe_MC2` (feature-viewer).

#### MC-3 · "Modifica con…" di un solo-NAS: la versione resta solo nell'app finche' non passa un giro del backup qualunque
- **Gravità:** Sbagliata. **Stato:** confermato il ritardo; plausibile cio' che la Libreria mostra nel frattempo.
- **Flusso:** viewer di un solo-NAS → ⋮ → "Modifica con…" → Google Foto o OneDrive riscrivono la copia → ritorno all'app, che dice "Salvata la modifica:
  è la nuova versione".
- **Percorso:**
  - `DefaultEditSessions.recordEdited` tiene il file preparato, rende la versione `EDITED` **corrente e senza posizione**, poi chiede il lavoro
    (`feature-integrations/.../edit/DefaultEditSessions.kt:271-291`; `MediaCatalog.addEditedVersion`, `core-database/.../MediaCatalog.kt:1231-1245`).
  - Il lavoro lo crea `FiveBGraph.planStaged` (`app/.../backup/FiveBGraph.kt:372-382`, legato in `IntegrationsGraph.kt:216`). Scrive solo nel diario:
    non chiede nessun giro e non fa `runJob`. Le correzioni invece lo fanno (`DefaultCorrections.kt:210`).
  - Nessun evento del MediaStore, perche' il file sta in `cacheDir/edit/`. Quindi `PhoneSyncWorker` non parte e nessuno chiede il giro delle foto.
  - Lo riprende il lavoro periodico: ogni 6 ore e con le regole del preset; con Notte, di notte.
- **Scenario:** l'utente modifica in Google Foto una foto che sta solo sul NAS. L'app dice "Salvata", ma sul NAS non arriva niente fino a stanotte. Intanto
  la versione corrente del media non ha nessuna copia leggibile: miniatura e viewer non hanno i byte della versione nuova (da provare).
- **Perche' i test passano:** `FiveBIntegrationTest.modificaConDiUnSoloNas…` chiama `rig.round()` subito dopo `settle` (righe 374-380): e' il passo che
  nell'app manca.
- **Correzione:**
  - dopo `planStaged`, chiedere il lavoro delle foto, oppure un `runJob` breve come per le correzioni;
  - rendere corrente la versione nuova solo quando c'e' la copia verificata.
- **Stato: corretta** (20/10), con la prima strada: `FiveBGraph.planStaged` chiede subito il lavoro del tipo del media (`BackupJobs.versionWork`). La
  seconda (la versione corrente solo a copia verificata) non e' fatta: cambierebbe `MediaCatalog.addEditedVersion` e il modello delle versioni. Test:
  `Correzioni5BVersioniAppTest.mc3_*` (rosso prima).

#### MC-4 · "Modifica con…" di un solo-NAS dell'area comune (o, in debug, fuori da `mcg-campo/`): la modifica si accetta e non si carica mai
- **Gravità:** Sbagliata: in quel caso la modifica vive solo in `noBackupFilesDir`. **Stato:** confermato.
- **Flusso:** come MC-3, ma su una foto propria che sta solo nell'area comune (spostata con Cartelle NAS, F29). Oppure in debug, con un account di prova,
  su un file della share fuori da `mcg-campo/`: e' il caso di F41.
- **Percorso:**
  - `beginInCache` vuole soltanto una copia sul NAS dell'account (`DefaultEditSessions.kt:138-167`; `CatalogEditCatalog.media`,
    `app/.../integrations/IntegrationsAdapters.kt:373-395`): l'area comune va bene.
  - `recordEdited` rende corrente la versione nuova **prima** di sapere se il lavoro nasce, e ignora la risposta del pianificatore
    (`DefaultEditSessions.kt:282-290`).
  - Il lavoro non nasce:
    - `VersionPlanning.stagedVersion` vuole un solito percorso nell'**area privata** (`core-backup/.../versions/VersionPlanning.kt:25-30`, `:85-101`);
    - `NasVersionEngine.planStaged` vuole la radice di D13 (`NasVersionEngine.kt:135-140`);
    - quindi risponde null. `recoverStaged` riprova a ogni giro e trova di nuovo null (`NasVersionEngine.kt:167-186`).
  - P14 ha deciso "dalla 5B non si scrive mai nell'area comune", ma questo flusso non lo controlla.
- **Scenario:** l'app dice "Salvata la modifica: è la nuova versione". La versione esiste solo nel file preparato dell'app: un azzeramento o una
  disinstallazione la perdono, e la versione corrente del media non ha copie.
- **Correzione:**
  - prima di aprire la sessione, la stessa prontezza delle correzioni (`COMMON_AREA_NOTICE`, recinzione D13);
  - in `recordEdited`, creare la versione nel catalogo solo se il lavoro puo' nascere.
- **Stato: corretta** (20/10), con la prima strada: **prima** di aprire "Modifica con…" (e "Scarica e modifica") di un solo-NAS si chiede se la modifica
  potra' diventare una versione sul NAS (`NasVersionEngine.canPlanStaged`: un solito percorso proprio nell'area privata, sotto la radice di D13); se no
  l'app lo dice ("Da qui questa modifica non si potrebbe salvare sul NAS…") e non apre l'editor. `recordEdited` (feature-integrations) non e' cambiato:
  con il controllo prima, la versione nasce solo per un media che puo' averla. Test: `Revisione5BVersioniTest.mc4_*` (area comune e recinzione D13),
  `Correzioni5BVersioniAppTest.mc3_*` (l'area privata si').

#### MC-5 · "Scarica e modifica in Galleria" non si raggiunge; senza un editor, la frase parla di Galleria Samsung
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `FiveBUi.startEdit` sceglie solo `PHONE_GALLERY` o `NAS_CACHE` (`app/.../backup/FiveBUi.kt:399-421`, riga 408): `EditTarget.NAS_DOWNLOAD` (D25,
    E-E7, F41) non lo chiama nessuno.
  - Se il lancio fallisce, `launched(ok = false)` dice sempre "Galleria Samsung non c'è su questo telefono…" (`FiveBUi.kt:226-232`,
    `app/src/main/res/values/strings_integrations_app.xml:10`). Succede anche per un solo-NAS senza un editor che accetti `ACTION_EDIT`.
  - `EditIntents.launch` promette nel commento che la UI proponga "Scarica e modifica" (`EditIntents.kt:58-61`).
  - In debug con un account di prova, la riga scaricata in `Pictures/MyCloud Gallery/` sarebbe comunque fuori dalla recinzione D13
    (`app/src/debug/.../debugtools/DebugBackupFence.kt`) → `GONE_FROM_PHONE` (`DefaultEditSessions.kt:121`). Vedi anche LA-3.
- **Correzione:** la voce "Scarica e modifica in Galleria" per i solo-NAS, o come scelta dopo un lancio fallito, con la frase giusta per tipo di media.
- **Stato: corretta** (20/10). Voce nuova del menu del viewer `EDIT_DOWNLOAD` per i solo-NAS (`BackupMenuCapabilities.editDownload`, il media non sta sul
  telefono secondo il piano delle versioni) → `EditTarget.NAS_DOWNLOAD`. Un lancio fallito dice la frase giusta: Galleria Samsung assente per un media del
  telefono o una copia scaricata; "nessuna app di modifica accetta questa foto/questo video: prova «Scarica e modifica in Galleria»" per un solo-NAS.
  Test: `BackupActionsTest.mc5_*`, `FiveBButtonsTest.ogniVoceDelBackupNelViewerFaQualcosa`. LA-3 (la riga scaricata fuori dalla recinzione D13 in debug)
  e' corretta a parte (sotto).

#### MC-6 · "Modifica con…" di una foto del telefono, fuori casa e a processo freddo: "Il NAS non risponde"
- **Gravità:** Minore: una scelta documentata, da rivedere. **Stato:** confermato.
- **Percorso:**
  - `DefaultEditSessions.doBegin` vuole l'ora del NAS (`DefaultEditSessions.kt:96`); `SessionNasClock` la tiene solo in memoria
    (`core-nas/.../session/Clocks.kt:80`).
  - Dopo un avvio a freddo fuori casa l'ora e' null. Anche una foto del telefono, che con Samsung Gallery non ha bisogno del NAS, risponde allora
    "Il NAS non risponde: riprova quando sei a casa" (`strings_integrations_app.xml:16`).
  - A casa succede lo stesso appena aperta l'app, prima del primo contatto SMB.
  - Decisioni P14, tabella "Le righe P14": "lasciato così… da riguardare con F41".
- **Correzione:** per `PHONE_GALLERY`, misurare la sessione con il monotono, azzerato al riavvio, come propone P11.
- **Stato: corretta** (20/10). `DefaultEditSessions`: senza l'ora del NAS una sessione `PHONE_GALLERY` nasce lo stesso, con inizio e scadenza
  `MONOTONIC_MARK` nel database e la scadenza (30 minuti) misurata con l'orologio monotono di questo processo; un'ora del NAS arrivata dopo non la fa
  scadere; un riavvio la chiude (`expireStale`). Le copie dal NAS (`NAS_CACHE`, `NAS_DOWNLOAD`) vogliono ancora l'ora del NAS. Test:
  `DefaultEditSessionsTest.mc6_*`.

### 2.3 "Libera spazio", "Elimina solo dal telefono", "Ovunque" (LS)

#### LS-1 · In release, "Elimina… → Solo dal telefono" del viewer non elimina piu' niente
- **Gravità:** Blocca, in release fino alla risposta a Q3. **Stato:** confermato.
- **Flusso:** viewer di un media che sta sul telefono e sul NAS → "Elimina…" → "Solo dal telefono" (E-D1, 4B).
- **Percorso:**
  - `FiveBUi` dichiara sempre `phoneOnlyDelete = true` (`app/.../backup/FiveBUi.kt:319-325`). Quindi `BackupActionsController.deleteFromPhone` manda
    sempre al foglio della 5B (`feature-viewer/.../menu/BackupActionsController.kt:75-78`), non piu' al cestino del viewer.
  - In release, con l'account vero (D12) o con uno di prova (R2), il cancello e' `TEST_ONLY`: `SafeFreeSpace.assessPhoneOnly` da' `UNAVAILABLE` a ogni
    media (`core-backup/.../free/SafeFreeSpace.kt:550-560`, `PhoneOnlyDeletion.kt:66-73`).
  - Il foglio dice "non si può togliere ora: il backup non è pronto" (`feature-backup/src/main/res/values/strings_backup_free.xml:174-177`) e ha solo
    "Chiudi". La pagina Backup invece dice "Backup in prova".
- **Scenario:** con l'APK di release l'utente non riesce piu' a togliere dal telefono la copia di una foto che e' anche sul NAS. Nella 4B si poteva, con
  il cestino di Android per 30 giorni. La selezione della Libreria invece cestina ancora senza nessun controllo D22: lo stesso gesto, due comportamenti.
- **Correzione, da decidere con l'utente:**
  - quando "Libera spazio" non c'e', tornare al cestino del viewer della 4B;
  - oppure tenere il blocco, ma dirlo con le parole giuste.
- **Stato: corretta** (20/10, decisione dell'utente «Elimina con avviso», `docs/decisioni.md` «Correzioni 5B: Libera spazio»). Dal viewer, un media che **non risulta al sicuro sul NAS**
  (backup non attivo come in release con D12/R2, nessuna copia privata verificata, in caricamento, area ferma, fuori da D13: `PhoneOnlyAssessment.notOnNas`)
  ha nel foglio l'avviso "Questa foto non è sul NAS" con il perche' e "la recuperi da Eliminati di recente per 30 giorni"; il tocco su "Elimina dal
  telefono" chiede al sistema il cestino di Android (`createTrashRequest`, come la 4B) per le sue righe, dalla radice (`FiveBUi.trashFromPhone`). Con il
  backup attivo c'e' anche "Metti al sicuro" (preselezionata). Un media al sicuro segue la 5B. "Libera spazio" resta chiuso senza backup attivo. Test:
  `Correzioni5BLiberaSpazioAppTest.ls1_*` (release, grafo vero), `Correzioni5BSoloDalTelefonoTest.ls1_*`.

#### LS-2 · La verifica sul momento vive quanto la pagina: niente lavoro avviato dall'utente, niente notifica "Pronto", niente ripresa
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - `FreeSpaceFlow` gira in `viewModelScope` (`feature-backup/.../free/FreeSpaceViewModel.kt:63-81`) e lo chiude `onCleared` (`:145-147`).
  - La pagina dice "Può durare un po'. Lascia aperta questa pagina." (`strings_backup_free.xml:68`).
  - "Pronto: N elementi" e' solo il titolo di una scheda (`:72-73`): non c'e' nessuna notifica.
  - All'apertura la pagina riparte sempre da `load()`, perche' il contratto `FreeSpace` non dice quali giri sono attivi. Diario su Room, validita' di
    6 ore e conteggio dei riavvii servono quindi solo dentro la stessa visita.
  - §2.5 punto 4 e D21 (3) chiedono un lavoro avviato dall'utente con la notifica "Pronto: N elementi, X GB. Tocca per liberare". La nota di P9 per P14
    non ha esito nella tabella di P14.
- **Scenario:** 30 minuti di verifica su 50 GB. L'utente esce dalla pagina, oppure lo schermo si spegne e Android ferma l'app. Al ritorno trova un
  riepilogo nuovo, e la verifica ricomincia da capo.
- **Correzione:**
  - `FreeSpaceFlow` in un lavoro avviato dall'utente (`BackupJobService` con un id suo, come per gli arrivi), con la notifica;
  - un metodo del contratto per riprendere all'apertura il giro attivo.
- **Stato: corretta** (20/10, `docs/decisioni.md` «Correzioni 5B: Libera spazio»). Il giro sta in un oggetto del processo (`FiveBFreeSpaceRuns`, porta `FreeSpaceRuns`, conduttore
  `FreeSpaceDriver`), ospitato dal lavoro avviato dall'utente (`BackupJobService`, id `1_000_105`, UIDT) con la notifica dell'avanzamento e poi "Pronto: N
  elementi, X GB. Tocca per liberare"; al ritorno l'app apre la pagina. Dopo la morte del processo il lavoro (o la pagina) ritrova il giro dal diario
  (`SafeFreeSpace.active`: 6 ore monotone, stesso avvio, altrimenti scaduto). "Annulla" lo chiude (`abandon`). Test: `Correzioni5BLiberaSpazioTest.ls2_*`,
  `FreeSpaceEveryCallTest` (ripresa da `active()` sotto guasti e morti), `Correzioni5BLiberaSpazioFlowTest.ls2_*`, `Correzioni5BLiberaSpazioAppTest.ls2_*`.

#### LS-3 · "Elimina solo dal telefono" dal viewer apre "Libera spazio" sotto il viewer
- **Gravità:** Sbagliata. **Stato:** confermato (anche dall'agente LS).
- **Percorso:**
  - Il foglio → "Continua" → `FiveBUi.openFreeSpace(selection)` → `nav.navigate(FREE_SPACE)` (`app/.../main/FiveBHostEffect.kt:59-67`, `:81-88`).
  - Il viewer e' disegnato sopra tutta la navigazione (`main/GalleryRoot.kt:139-142`: prima `content()`, poi `ViewerLayer`), e nessuno lo chiude.
- **Scenario:** il foglio si chiude e resta la foto nel viewer: sembra che non sia successo niente. "Libera spazio" compare solo chiudendo il viewer.
- **Correzione:** chiudere il viewer (`LocalViewerHost.current?.close()`) prima di navigare.
- **Stato: corretta** (20/10). `FiveBHostEffect` chiude il viewer (`LocalViewerHost.current?.close()`) prima di aprire "Libera spazio". Senza test
  automatico (servirebbe il viewer vero composto sopra la navigazione): da vedere in F38.

#### LS-4 · "Prima mettilo al sicuro" non riporta mai al foglio, e per molti media non puo' riuscire
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - La scelta preselezionata di E-D14 chiama `backupNow()` e chiude il foglio (`feature-backup/.../free/PhoneOnlySheet.kt:107-110`, `:144-147`).
  - Il testo promette "Parte il backup e controllo la copia sul NAS. Poi ti riporto qui." (`strings_backup_free.xml:187-188`).
  - Niente riapre il foglio.
  - Niente verifica quei media in particolare: una copia non verificata resta tale fino alle verifiche notturne.
  - Un media fuori dalle sorgenti non si carica mai. E' lo stesso difetto di VE-3.
- **Correzione:** come VE-3. Oppure una frase onesta, con l'indicazione di aggiungere la cartella alle sorgenti.
- **Stato: corretta** (20/10). "Metti al sicuro" chiama `Corrections.safeguard` per ognuno dei media non al sicuro (`BackupGraph.safeguard`: verifica sul
  momento o "Fai ora"); il foglio resta aperto ("Sto mettendo al sicuro…") e si aggiorna da solo quando la valutazione cambia; fuori dalle sorgenti dice
  "Questa cartella non è nel backup", se non parte niente "Adesso non posso metterla al sicuro". Test: `Correzioni5BSoloDalTelefonoTest.ls4_*`.

#### LS-5 · Se le parti del backup spariscono durante la verifica, la pagina gira all'infinito sul thread principale (ANR)
- **Gravità:** Blocca, in quel caso raro. **Stato:** confermato (agente LS, ricontrollato).
- **Percorso:**
  - `FreeSpaceFlow.start` ripete `service.resume(current)` finche' lo stato e' `GUARDING` o `VERIFYING` (`FreeSpaceFlow.kt:87-97`). Non si sospende
    se non lo fa il servizio.
  - La facciata passa a `NoFreeSpace` quando le parti si tolgono (`FiveBGraph.kt:319-320`): con `clear()` a un cambio d'area (`BackupGraph.kt:252-265`),
    anche per un `privateShare()` per un attimo nullo, o all'azzeramento (`:376-394`).
  - `NoFreeSpace.resume` restituisce lo stesso giro senza sospendersi (`core-backup/.../noop/BackupFiveBNullObjects.kt:92`).
  - Il ciclo gira su `viewModelScope`, cioe' sul thread principale.
- **Correzione:** `NoFreeSpace.resume` risponde `BLOCKED`/`NOT_AVAILABLE`, e il ciclo esce se il giro non avanza.
- **Stato: corretta** (20/10). La facciata senza parti risponde `BLOCKED`/`NOT_AVAILABLE`; il conduttore (`FreeSpaceDriver`) si ferma se il servizio
  restituisce lo stesso giro, e gira nello scope del grafo, non sul thread principale. Test: `Correzioni5BLiberaSpazioFlowTest.ls5_*` (una tappa sola,
  prima infinita), `Correzioni5BLiberaSpazioAppTest.ls5_ls10_*`.

#### LS-6 · Dopo la morte del processo, "Libera spazio" di una selezione diventa "tutto il sicuro"
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - La selezione e' una variabile del singleton `FiveBUi` (`FiveBUi.kt:139-151`), e la rotta la legge con `remember { freeSpaceSelection() }`
    (`app/.../navigation/BackupNavigation.kt:29-32`).
  - Dopo la morte del processo la navigazione ripristina la rotta, ma la variabile e' null.
- **Scenario:** "Elimina solo dal telefono" di una foto → "Libera spazio" con quella foto. L'app va in secondo piano e Android la chiude. Al ritorno la
  pagina propone "tutto il sicuro tranne gli ultimi 30 giorni", cioe' migliaia di media.
- **Correzione:** tenere gli id nello stato salvato (`SavedStateHandle` o `rememberSaveable`).
- **Stato: corretta** (20/10). La selezione sta nello stato salvato della rotta (`FreeSpaceSelection`): gli id fino a 500, oltre solo il numero della
  richiesta (che ritrova il processo vivo); persa dopo la morte del processo, la pagina **si chiude**, mai "tutto il sicuro". Test: `FreeSpaceSelectionTest`.

#### LS-7 · Un rifiuto al secondo lotto dice "Nessun file è stato tolto"; tutti gli usciti hanno lo stesso motivo
- **Gravità:** Sbagliata. **Stato:** confermato (ricontrollato).
- **Percorso:**
  - Un rifiuto porta il giro a `CANCELLED` (`SafeFreeSpace.kt:587`).
  - `FreeSpaceFlow.finished` passa anche i liberati prima (`FreeSpaceFlow.kt:173-185`), ma con `cancelled` la pagina mostra solo "Hai detto di no al
    telefono. Nessun file è stato tolto" (`FreeSpaceScreen.kt:386-392`), anche dopo 2.000 media gia' nel cestino.
  - La riga degli usciti (`:402`) dice "la copia sul NAS non tornava" per ogni uscita, anche "gia' tolto" o "in caricamento".
  - I test provano solo il rifiuto del primo lotto (`FreeSpaceFlowTest.kt:164`).
- **Correzione:** "N liberati, poi ti sei fermato", e il motivo vero di ogni uscita.
- **Stato: corretta** (20/10). "Liberati X … Poi hai detto di no al telefono: gli altri restano sul telefono"; i rimasti contano anche i lotti non
  proposti; ogni uscita con il suo motivo (`FreeSpaceUi.Done.droppedBy`). Test: `Correzioni5BLiberaSpazioFlowTest.ls7_*`, `FreeSpaceScreenTest.ls7_*`.

#### LS-8 · Il guardiano aspetta fino a 15 minuti il turno del backup tenendo il lucchetto di "Libera spazio": "0 di N" senza spiegazione
- **Gravità:** Minore. **Stato:** confermato nel codice.
- **Percorso:**
  - `ExclusiveGuardian` (`FiveBGraph.kt:415-421`) entra in `BackupGraph.exclusive` con un'attesa di 15 minuti (`BackupGraph.kt:209`).
  - Un lavoro dell'utente non cede il turno (`work/BackupStages.kt:133`).
  - Intanto `SafeFreeSpace` tiene il suo lucchetto (`SafeFreeSpace.kt:218`, `:265`, `:290`), quindi aspetta anche `settle()` dopo una lettura del telefono.
  - Allo scadere il guardiano risponde `BUDGET` e la pagina riprova senza fine.
- **Correzione:** uno stato "aspetto il backup", e l'attesa del turno fuori dal lucchetto.
- **Stato: corretta** (20/10). Il guardiano gira fuori dal lucchetto di "Libera spazio" (`SafeFreeSpace.drive`), aspetta il turno al massimo una tappa
  (`ExclusiveGuardian`) e la scheda dice "aspetto che il backup finisca il file che sta caricando". Test: `Correzioni5BLiberaSpazioTest.ls8_*` (rosso con il
  guardiano sotto il lucchetto), `FreeSpaceScreenTest.ls8_*`.

#### LS-9 · "Libera spazio" del problema "Memoria del telefono quasi piena" apre le impostazioni di Android
- **Gravità:** Minore. **Stato:** confermato (agenti LS e LA).
- **Percorso:**
  - `PHONE_STORAGE_LOW` ha l'azione `FREE_SPACE` (`core-backup/.../contract/BackupStatus.kt:99`), con l'etichetta "Libera spazio".
  - `AppBackupActions.perform` la manda a `openStorageSettings()` (`app/.../backup/BackupUi.kt:361`; `main/BackupHostEffect.kt:78-80`), non alla pagina
    che dalla 5B esiste.
- **Correzione:** aprire `BackupScreen.FREE_SPACE` quando c'e', altrimenti le impostazioni.
- **Stato: corretta** (20/10). `BackupAction.FREE_SPACE` apre `BackupScreen.FREE_SPACE` (tutto il sicuro); le impostazioni di Android solo senza il
  dialogo del sistema. Test: `Correzioni5BLiberaSpazioAppTest.ls9_*`.

#### LS-10 · Appena aperta l'app, riepilogo vuoto "niente da liberare" che non si ricalcola
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - Prima che il backup parta (pochi secondi dopo l'avvio, o senza area privata) la facciata usa `NoFreeSpace.plan`, un piano vuoto senza esclusi
    (`FiveBGraph.kt:319-322`).
  - La pagina dice "Per ora non c'è niente da liberare" e non si ricalcola quando le parti arrivano.
  - Lo stato `Blocked(NOT_AVAILABLE)` del ViewModel (`FreeSpaceViewModel.kt:52-53`) non si raggiunge mai, perche' `FiveBModule` da' sempre una porta.
- **Correzione:** la facciata risponde "non ancora pronto" invece di un piano vuoto, e la pagina segue `FiveBGraph.partsState`.
- **Stato: corretta** (20/10). La pagina segue `FreeSpaceRuns.ready` (le parti in `FiveBGraph`): "Non è ancora disponibile" e poi il riepilogo, da
  solo. Test: `Correzioni5BLiberaSpazioFlowTest.ls10_*`, `Correzioni5BLiberaSpazioAppTest.ls5_ls10_*`.

#### LS-11 · Senza l'ora del NAS tutto e' "degli ultimi 30 giorni", e la verifica dice "NAS non raggiungibile"
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:**
  - L'ora del NAS e' solo in memoria (`core-nas/.../session/Clocks.kt:80`).
  - Senza, `isRecent` e' vero per ogni media datato (`core-backup/.../free/FreeSpaceEvaluator.kt:254-262`), e "più vecchi di un anno" resta vuoto.
  - `verify` risponde `NAS_UNREACHABLE` (`SafeFreeSpace.kt:225`) anche a casa, finche' nessuno ha aperto una sessione SMB. All'apertura la scansione la
    apre dopo qualche secondo.
- **Prova:** avvio a freddo e subito Raccolte → Libera spazio. Contare gli esclusi "recenti" e vedere se la pagina si aggiorna.
- **Stato: corretta** (20/10). `verify` senza l'ora del NAS prova a raggiungerlo (`reachNas`, una sessione sull'area privata) prima di dire "non
  risponde"; la pagina all'apertura fa lo stesso senza aspettare, lo dice nel riepilogo e ricalcola quando l'ora arriva. Test:
  `Correzioni5BLiberaSpazioTest.ls11_*`, `Correzioni5BLiberaSpazioFlowTest.ls11_*`, `FreeSpaceScreenTest.ls11_*`. Da misurare in F37 (avvio a freddo).

#### LS-12 · Righe "in dubbio" o "nel dialogo" spiegate male, e alcune mai risolte
- **Gravità:** Minore. **Stato:** confermato (etichetta); plausibile (righe mai risolte).
- **Percorso:**
  - Nel riepilogo compaiono come "In caricamento o con un problema" (`SafeFreeSpace.kt:170-172`).
  - Una riga `IN_DOUBT` con la riga del telefono `Stale` (MediaStore ricostruito) non si risolve mai (`:643`).
  - Una riga `IN_DIALOG` del processo vivo non la risolve `settle()` (filtro `mine`, `:619-623`) se il lavoro e' stato annullato prima di
    `recordOutcome`: per esempio un secondo tocco su "Libera ora" durante il controllo finale.
- **Stato: corretta** (20/10). Motivo proprio nel riepilogo (`FreeSpaceExclusion.AWAITING_PHONE`); una riga `Stale` si risolve con la lettura del
  telefono (`FreeSpaceCatalog.phoneRowOf`, la ricetta del backup); il lotto di questo processo di un giro finito si da' per liberato solo con la prova (il
  dialogo puo' ancora rispondere: una riga ancora presente aspetta); un secondo tocco su "Libera ora" non annulla piu' il primo. Test:
  `Correzioni5BLiberaSpazioTest.ls12_*`, `Correzioni5BLiberaSpazioFlowTest.ls12_*`, `FreeSpaceScreenTest.ls12_ls13_*`.

#### LS-13 · "Qualcosa è cambiato" a ogni tentativo mentre il backup lavora
- **Gravità:** Sbagliata. **Stato:** plausibile.
- **Percorso:**
  - L'impronta del piano copre ogni elemento (`core-backup/.../contract/FreeSpace.kt:153-169`), e `verify` la ricalcola (`SafeFreeSpace.kt:228`).
  - Con "Tutto il sicuro" durante il primo backup basta un media che diventa al sicuro fra l'apertura del foglio e il tocco.
- **Prova:** "Libera spazio" con "Tutto il sicuro" a primo backup in corso.
- **Stato: corretta** (20/10). La conferma vale per il piano visto; il giro prende solo i suoi elementi ancora identici nel piano di adesso (mai uno in
  piu'), e la fine dice quanti erano cambiati. Test: `Correzioni5BLiberaSpazioTest.ls13_*` (rossi prima), il caso "arrivato dopo il foglio" in
  `FreeSpaceEveryCallTest`, `Correzioni5BLiberaSpazioFlowTest.ls13_*`. Da provare in F37 durante il primo backup.

#### LS-14 · "Ovunque" toglie una sola copia del telefono
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:**
  - `EverywherePlan.phoneCopies` (`core-backup/.../free/EverywhereCopies.kt:142-148`) non lo usa nessuno.
  - Il viewer cestina solo il riferimento di adesso (`feature-viewer/.../ViewerController.kt:383-388`).
  - Una seconda copia sul telefono resta, per esempio quella di "Scarica sul telefono" in `Pictures/MyCloud Gallery`, e il backup puo' ricaricarla.
- **Stato: corretta** (20/10). "Ovunque" toglie dal telefono tutte le copie del media con un solo dialogo del cestino
  (`BackupMediaActions.deleteEverywhereFromPhone`); con una copia sola resta il cestino del viewer. Test: `Correzioni5BLiberaSpazioAppTest.ls14_*`,
  `BackupActionsTest.ls14_*`.

#### LS-15 · Costi: circa 5 query per media del telefono, ripetute tre volte per conferma; diario quadratico
- **Gravità:** Minore. **Stato:** plausibile, da misurare con F37.
- **Percorso:**
  - `CatalogFreeSpaceCatalog.freeMedia` chiama sempre `indexRows` (`FiveBAdapters.kt:214-225`).
  - Il piano si calcola all'apertura, all'apertura del foglio (senza indicatore) e dentro `verify`.
  - `RoomFreeSpaceDiary.update` rilegge tutti gli elementi del giro a ogni scrittura (`RoomFreeSpaceDiary.kt:84`).
  - `settle()` scorre tutti i giri storici (`SafeFreeSpace.kt:605`), che non si potano mai.
- **Stato: corretta in parte** (20/10). `settle()` solo sui giri con righe da risolvere, la ripresa solo sui giri aperti (query su Room), le pagine
  del catalogo senza la riga dell'indice, il foglio che riusa il piano del riepilogo. Resta `RoomFreeSpaceDiary.update`, che rilegge gli elementi del giro a
  ogni scrittura (serve una query per indice in core-database): da misurare in F37. Test: `RoomFreeSpaceDiaryTest.ls15_*`,
  `Correzioni5BLiberaSpazioFlowTest.ls15_*`.

### 2.4 Integrazioni: selettore, provider dei documenti, ricezione (IN)

#### IN-1 · A processo freddo, la pulizia d'avvio cancella i file appena ricevuti prima di "Salva sul NAS"
- **Gravità:** Blocca, nel caso: la prima condivisione verso l'app chiusa. **Stato:** plausibile. La corsa e' nel codice (trovata da me e da due agenti);
  quanto spesso la si perde va misurato.
- **Flusso:** l'app non e' in esecuzione; WhatsApp → Condividi → MyCloud Gallery → scelta della cartella → "Salva sul NAS".
- **Percorso:**
  - `MyCloudGalleryApp.onCreate` → `IntegrationsGraph.install()` → `scope.launch(IO) { atStart() }` (`app/.../integrations/IntegrationsGraph.kt:242-251`).
  - `atStart` aspetta il catalogo (`editSessions()` → `coreIfReady()`), poi fa `stager.sweep(keep)`. `keep` sono le condivisioni citate da righe di
    `incoming_item` in coda o fallite (`IntegrationsGraph.kt:257-269`).
  - `IncomingStager.sweep` cancella ogni cartella di `incoming/` fuori da `keep`, senza guardarne l'eta'
    (`feature-integrations/.../receive/IncomingStager.kt:259-268`).
  - La condivisione appena copiata non ha righe finche' l'utente non tocca "Salva sul NAS" (`app/.../backup/IncomingQueue.kt:96-143`).
  - Al tocco, `DefaultIncomingUploads.save` controlla `isIntact` e risponde `StageGone` (`DefaultIncomingUploads.kt:63`): "I file copiati non ci sono
    più. Condividili di nuovo."
  - Se la pulizia arriva durante la copia, l'esito e' invece "1 non si leggono".
- **Correzione:**
  - la pulizia salta le cartelle create da questo processo (un insieme nello stager) e quelle giovani (monotono);
  - oppure la pulizia gira prima di installare `ReceivePorts`.
- **Prova:** `am force-stop`, poi una condivisione con 3–5 s di attesa prima di "Salva".
- **Stato: corretta** (20/10, `docs/decisioni.md` «Correzioni 5B: integrazioni»). Prima strada, con un insieme invece dell'eta': `IncomingStager` tiene le condivisioni copiate da
  **questo processo** e non ancora scartate (`ownStages`: la cartella vi entra prima di essere creata) e `sweep` le salta, comprese quella in copia
  adesso e quelle che aspettano la scelta della cartella. Il resto di un processo morto non ha piu' una schermata che lo aspetta e si toglie come
  prima. Test: `Correzioni5BIntegrazioniAppTest.in1_*` (grafo vero: la pulizia d'avvio arriva dopo la copia e prima del tocco, la condivisione c'e' ancora
  e "Salva sul NAS" la mette in coda; il resto di un processo morto si toglie), `IncomingStagerTest.in1_*` (2). Prova di mutazione: senza `ownStages` i due `IncomingStagerTest.in1_*` diventano rossi.
  Resta la prova sul telefono (F40: forza l'arresto, poi una condivisione con 3-5 s prima di "Salva").

#### IN-2 · Provider dei documenti: gli id della radice cambiano a ogni processo e le radici non si notificano mai
- **Gravità:** Blocca, per Gmail "Allega file" e per il "Documento" di WhatsApp e Telegram. **Stato:** plausibile, alta probabilita'. Gli id sono
  confermati (ricontrollato); il comportamento della cache di DocumentsUI va provato.
- **Percorso:**
  - La radice, "Recenti" e "Cartelle NAS" hanno token casuali nuovi a ogni processo (`feature-integrations/.../provider/DocumentIdTable.kt:73-79`,
    `LibraryMediaDocuments.kt:71`).
  - DocumentsUI tiene le radici nella cache di sistema, che vale finche' nessuno notifica l'URI delle radici. Il codice non chiama mai
    `notifyChange(buildRootsUri)`.
  - Dopo un riavvio del nostro processo, aprire la radice da' `queryChildDocuments(vecchioToken)` → id sconosciuto → `FileNotFoundException`.
  - A freddo, `queryRoots` puo' arrivare in `Loading` (vedi IN-3): risponde `NOT_READY` e nessuna radice (`MediaDocumentsProvider.kt:55-57`), e nessuno
    lo corregge.
- **Correzione:**
  - id fissi, non segreti, per le tre cartelle virtuali;
  - `notifyChange` delle radici dopo `install`, quando la famiglia diventa pronta e quando il blocco cambia.
- **Prova (F39):**
  1. Gmail → Allega → MyCloud Gallery.
  2. `am kill` del pacchetto.
  3. Di nuovo Gmail → Allega → MyCloud Gallery.
- **Stato: corretta** (20/10). **Schema degli id** (`DocumentIdTable`, `DocumentIdKey`):
  - **radice, "Recenti", "Cartelle NAS" e cartelle del NAS**: stabili. `HMAC-SHA256(chiave locale, etichetta | chiave opaca della cartella)`, troncato a
    18 byte e scritto in base64 URL-safe (24 caratteri); uguali a ogni avvio del processo finche' la chiave non ruota. La chiave (32 byte casuali) sta in
    `noBackupFilesDir/integrations/doc-id.key` (fuori dal backup di Android), si legge alla prima richiesta (mai all'avvio del processo), ruota con
    l'azzeramento dell'app (`revokeAll`) e, se il file non si legge o non si scrive, vale per il processo (come prima, ma senza rompere niente). Un id non
    contiene percorso, share, account o nome e non si indovina senza la chiave; apre solo una cartella, mai un media.
  - **documenti dei media**: invariati. Token casuali di 18 byte in memoria, a scadenza (a scorrimento, piu' lunga se consegnati) e revocabili: dopo la
    scadenza o la revoca non aprono piu' niente (E-X1).
  - **una cartella dopo un riavvio**: `LibraryMediaDocuments.found` ritrova l'id che DocumentsUI ricorda ricalcolando gli id da
    `IntegrationLibrary.nasFolderIndex()` (`AppIntegrationLibrary`: una sola lettura del catalogo, al piu' 20.000 cartelle, mai `Versioni/`), non piu' di una
    ricerca ogni 5 s; un id inventato resta sconosciuto.
  - **radici notificate**: `IntegrationsGraph.install()` ascolta `AppEntryGate.decisions()` e notifica `DocumentsContract.buildRootsUri` a ogni cambio
    (stato letto, blocco acceso, sbloccato, richiuso); l'azzeramento ruota la chiave e notifica.
  - Trovato dal test: `FolderKeys.parse` (`:app`) usava `SharePath.of` su un percorso intero e rifiutava ogni cartella oltre il secondo livello, quindi
    l'albero "Cartelle NAS" si fermava li' e i media delle cartelle annidate non si elencavano. Ora `SharePath.parse`.
  - Test: `DocumentIdTableTest.in2_*` (5), `LibraryMediaDocumentsTest.in2_*` (2), `MediaDocumentsProviderTest.in2_*`,
    `Correzioni5BIntegrazioniAppTest.in2_*` (2: le radici si notificano; un processo nuovo con la stessa chiave ritrova una cartella a tre livelli con lo
    stesso id e la camminata completa da' gli stessi id), `AppEntryGateTest.in2_*`. Prova di mutazione: con una chiave casuale a ogni processo (id nuovi a ogni avvio, com'era) sono rossi `DocumentIdTableTest.in2_*` (3), `LibraryMediaDocumentsTest.in2_*` e `MediaDocumentsProviderTest.in2_*`; senza la notifica delle radici e con `FolderKeys.parse` com'era, i due `Correzioni5BIntegrazioniAppTest.in2_*`.
  - Resta la prova sul telefono (F39: Gmail, `am kill`, di nuovo Gmail).

#### IN-3 · A processo freddo selettore e ricezione dicono "Prima configura MyCloud Gallery", o non riescono a sbloccare, e non si ricontrollano
- **Gravità:** Sbagliata. **Stato:** plausibile: e' una corsa.
- **Percorso:**
  - `AppEntryGate.decide` legge `setup.state.value is Ready` (`app/.../di/FiveBModule.kt:114`) e `isUnlockedNow()`. Con le impostazioni del blocco
    non ancora lette risponde "chiuso" (`core-security/.../lock/AppLockController.kt:153-155`).
  - `AppGraph.start()` e `lock.follow` girano in coroutine dopo `onCreate` (`process/ProcessWatcher.kt:51`, `IntegrationsGraph.kt:245`).
  - Le activity rileggono il cancello solo in `onResume` e dopo uno sblocco (`ShareReceiverActivity.kt:134-140`, `PickerActivity.kt:90-96`).
  - Il caso "chiuso" porta a `startUnlock`. Li' `beginAuthentication()` risponde false finche' le impostazioni non sono lette
    (`IntegrationsAdapters.kt:109`): compare "Non sono riuscito a sbloccare", senza nessuna richiesta di sblocco.
  - Testi: "Apri l'app e completa la configurazione, poi condividi di nuovo i file." (`strings_integrations_receive.xml:15-16`, `..._picker.xml:37-38`).
- **Correzione:** un esito `LOADING` del cancello ("sto aprendo…") e l'osservazione di `setup.state` e delle impostazioni del blocco come flussi.
- **Prova:** forza l'arresto, poi 10 ingressi consecutivi da Gmail e da WhatsApp.
- **Stato: corretta** (20/10). `EntryDecision.LOADING` ("sto leggendo lo stato") e `EntryGate.decideAfterLoading(timeoutMs)` (10 s di serie; 4 s nel
  provider, che risponde a un thread del binder). `AppEntryGate` guarda la famiglia (`familyLoading`, `familyChanges`: `setup.state`) e le impostazioni del
  blocco come flussi, e dice `LOADING` finche' una delle due non e' letta. Selettore e ricezione aspettano (mai "Prima configura MyCloud Gallery" per una
  corsa) e, se lo stato non arriva, mostrano "non pronta" con "Riprova" al ritorno; il provider risponde dopo l'attesa e le radici si notificano appena
  lo stato e' letto (IN-2). Test: `AppEntryGateTest.in3_*` (4), `PickerViewModelTest.in3_*` (2), `PickerActivityTest.in3_*` (2),
  `ReceiveViewModelTest.in3_*` (3), `ShareReceiverActivityTest.in3_*`, `MediaDocumentsProviderTest.in3_*` (3). Prova di mutazione: se la ricezione non aspetta (`LOADING` diventa subito «non pronta») sono rossi `ReceiveViewModelTest.in3_*` (2) e `ShareReceiverActivityTest.in3_*`.
  Resta la prova sul telefono (forza l'arresto, poi 10 ingressi consecutivi da Gmail e da WhatsApp).

#### IN-4 · Provider con il blocco "Subito": DocumentsUI non si sblocca mai
- **Gravità:** Sbagliata. **Stato:** la logica e' confermata dall'agente IN; il comportamento di DocumentsUI va provato.
- **Percorso:**
  - `AuthenticationRequiredException` apre la `MainActivity` (`MediaDocumentsProvider.kt:249-253`).
  - Li' lo sblocco passa da `endAuthentication`, non da `endEntryAuthentication`: niente due minuti di tolleranza (`AppLockController.kt:133-139`).
  - Tornando a DocumentsUI scatta `onAppBackgrounded`, e con timeout 0 `isUnlockedNow()` richiude subito.
- **Scenario:** Gmail → Allega → MyCloud → Sblocca → indietro → di nuovo "Sblocca", senza fine.
- **Correzione:** un'activity d'ingresso leggera che usa `endEntryAuthentication`, oppure la tolleranza anche per lo sblocco nato dal provider.
- **Stato: corretta** (20/10); il comportamento di DocumentsUI resta da provare sul telefono. L'`AuthenticationRequiredException` porta un
  `PendingIntent` immutabile ed **esplicito** verso `PickerActivity` con l'azione `ACTION_UNLOCK` (non piu' la `MainActivity`): un'activity d'ingresso
  leggera che usa `endEntryAuthentication` (la tolleranza di 2 minuti vale, anche con "Subito"), non mostra la Libreria e finisce con `RESULT_OK` appena
  sbloccato, cosi' DocumentsUI riprova da solo; se lo sblocco non riesce resta la sua schermata con il motivo e "Riprova"; "Annulla" chiude. Test:
  `MediaDocumentsProviderTest.ilTocco_Sblocca_EImmutabileEdEsplicito…`, `PickerActivityTest.in4_*` (3), `ManifestComponentsTest`. Prova di mutazione: con il tocco di nuovo sulla `MainActivity` il test del provider e' rosso.

#### IN-5 · "Salva sul NAS" di una condivisione piccola aspetta la notte; un'attesa di rete non si ripianifica
- **Gravità:** Sbagliata. **Stato:** confermato (agenti IN e LA; il preset predefinito e' Notte, `core-rules/.../settings/RuleSettings.kt:45`).
- **Percorso:**
  - Sotto `IncomingLimits.USER_INITIATED_JOB_BYTES` il lavoro e' `WorkRequest.Background(PHOTO_BACKUP)` (`app/.../work/BackupStages.kt:165-169`).
  - Con Notte si parte solo in fascia, in carica e sul Wi-Fi di casa.
  - `afterWait` ripianifica le attese di rete solo per `QUICK` (`work/BackupWork.kt:159-171`).
  - Fuori casa, o a casa dopo una prova a vuoto, il file aspetta la prossima lettura del telefono, la fine di un altro lavoro o il periodico di 6 ore.
    Aprire l'app non lo fa partire.
- **Scenario:** alle 15, a casa, una foto da WhatsApp. La schermata dice "1 file in coda… li carico sul NAS", oppure col NAS spento "li carico appena
  risponde". Sul NAS, e nella Libreria (vedi IN-7), arriva di notte con il telefono in carica. Un video da 1 GB invece parte subito come lavoro
  dell'utente.
- **Correzione:**
  - trattare l'arrivo come gesto dell'utente: `UserInitiated`, o un lavoro con le sole regole di rete;
  - in alternativa dire "lo carico stanotte";
  - in ogni caso, applicare a `INCOMING` il nuovo tentativo su rete non a consumo di BK-2.
- **Stato: corretta** (20/10). Un "Salva sul NAS" e' un gesto dell'utente, di qualunque grandezza: `BackupStages.incoming` e' sempre
  `WorkRequest.UserInitiated` col turno esclusivo dell'utente (restano la rete, il NAS, il congelamento, il caldo e i dati mobili che chiedono conferma;
  niente fascia, niente carica, niente preset Notte). `userInitiated` dice solo se il lavoro e' il UIDT (attesa del turno piu' lunga). Un'attesa di rete
  (rete non fidata, dati mobili, roaming, NAS non raggiungibile) e una conferma dei dati mobili si ripianificano come BK-2: `BackupWorkScheduling.afterWait`
  per `INCOMING` rimette in coda il lavoro su una rete non a consumo, e riparte da solo al rientro. Test: `Correzioni5BIntegrazioniAppTest.in5_*` (con
  Notte fuori fascia, senza carica e col Wi-Fi: la foto piccola sale subito), `BackupSchedulingTest.in5_*` (2). Prova di mutazione: come lavoro di fondo (`WorkRequest.Background`) `Correzioni5BIntegrazioniAppTest.in5_*` diventa rosso.

#### IN-6 · In release "Salva sul NAS" non riesce mai, con un messaggio sbagliato e dopo aver copiato tutto
- **Gravità:** Sbagliata. **Stato:** confermato (agenti IN e LA).
- **Percorso:**
  - Il cancello degli arrivi risponde `TEST_ONLY` (D12 sulle share vere, R2 con un account di prova), quindi `destinationAllowed` da' false
    (`app/.../backup/IncomingQueue.kt:88-91`).
  - La schermata dice "In quella cartella non si può scrivere. Scegline un'altra." per ogni cartella (`ReceiveViewModel.kt:339`). Intanto la copia, fino
    a 4 GiB, e' gia' fatta.
  - `AppIncomingDestinations.roots()` non guarda il cancello (`IntegrationsAdapters.kt:341`).
- **Correzione:** un esito "Backup in prova", controllato prima di copiare.
- **Stato: corretta** (20/10). **Causa:** non e' un difetto di scrittura. In release D12 chiude ogni scrittura della 5B sulle share vere e R2 su un account
  di prova, quindi il cancello degli arrivi risponde `TEST_ONLY` per tutte le aree e "Salva sul NAS" non puo' riuscire in questa versione (fino a Q3);
  era sbagliato il modo: lo si scopriva dopo aver copiato (fino a 4 GiB) e con la frase «In quella cartella non si può scrivere». Ora
  `IncomingDestinations.savingBlockedByTrial()` (`AppIncomingDestinations`: **tutte** le aree dell'utente chiuse con `BackupGateState.TEST_ONLY`) si
  chiede in `ReceiveViewModel.begin()` **prima di copiare**: la schermata `TEST_ONLY` dice "Backup in prova" e non copia niente. Se la prova arriva dopo
  la copia (cancello cambiato nel frattempo) l'esito e' `ReceiveFailure.TEST_ONLY` con la stessa frase. In debug, con una share di prova, il cancello e'
  aperto e niente cambia. Test: `Correzioni5BIntegrazioniAppTest.in6_*` (release: bloccato, nessuna copia, `DestinationNotAllowed` anche dal motore;
  debug: aperto), `ReceiveViewModelTest.in6_*` (2), `ShareReceiverActivityTest.in6_*`.

#### IN-7 · I file in arrivo non hanno stato: invisibili finche' non salgono, problemi nascosti, notifica e "Ferma" del lavoro grande sbagliati
- **Gravità:** Sbagliata. **Stato:** confermato (agenti IN e LA).
- **Percorso:**
  - Il media di un arrivo nasce **invisibile**: presenza `NONE`, sicurezza `LOST` (`core-database/.../MediaCatalog.kt:1392-1416`). Finche' non e' caricato
    non c'e' nella Libreria, e nessuna schermata legge `incoming_item`. E-X8 promette "con lo stato".
  - `BackupStages.incoming()` non chiama `afterRound` (`work/BackupStages.kt:162-183`). Un NAS pieno, la quota o la password per un arrivo non arrivano a
    `memory` ne' alla pagina, e gli arrivi non entrano in `queued`.
  - La notifica UIDT (`work/BackupJobService.kt:192-202`) segue `backup.status`:
    - senza cartella del dispositivo e' `FirstBackupReady`, quindi "Sto riprendendo il backup…";
    - altrimenti mostra i numeri dei media delle sorgenti, non quelli degli arrivi.
  - "Ferma" chiama `requestStop(USER)` (`BackupJobService.kt:216`): la pausa R4 di **tutto** il backup. Chi non ha ancora fatto il primo backup non vede
    "Riprendi" (`BackupGraph.kt:840-842`).
  - Gli arrivi `FAILED` tengono la copia locale per sempre (`IncomingStates.KEEP`, `IncomingQueue.kt:59`), senza modo di riprovarli o toglierli.
- **Scenario:** un utente senza backup 5A condivide un video da 1 GB e tocca "Ferma" nella notifica. Gli arrivi restano in pausa finche' non avvia il
  primo backup.
- **Correzione:**
  - una notifica degli arrivi con N di M e uno stop solo per `INCOMING`;
  - i problemi degli arrivi in `memory`;
  - una riga "Ricevuti in coda" nella pagina Backup, con riprova e scarta.
- **Stato: corretta in parte** (20/10). Fatto: (1) i file in arrivo hanno **la loro notifica** (id 1007, "Salvataggio sul NAS", «N di M file
  ricevuti», solo numeri, dal diario degli arrivi: `BackupProgress.incoming`, `followIncomingProgress`), non piu' il testo del backup delle foto; (2) il suo
  **"Ferma" e' solo suo**: un'azione e un ricevitore propri (`BackupNotification.stopAction(context, incoming = true)`), un `IncomingStop` per lavoro che
  chiede al giro di finire al passo dopo (nessun file a meta', i file restano in coda), mai la pausa R4 di tutto il backup; anche lo "Stop" di Android
  sul lavoro degli arrivi non mette in pausa le foto; (3) un NAS pieno, la quota o la password per un arrivo arrivano alla **memoria** dei problemi e
  agli eventi come per ogni altro giro (`BackupStages.incoming`). **Non fatto** (fuori dal perimetro di questo gruppo: pagina Backup e Libreria): la
  riga «Ricevuti in coda» con riprova e scarta nella pagina Backup, e un modo di riprovare o togliere gli arrivi `FAILED` (tengono la copia locale
  come prima: `IncomingStates.KEEP`). Il media di un arrivo resta invisibile nella Libreria finche' non e' sul NAS (E-X8 invariato). Test:
  `BackupNotificationTest.in7_*`, `Correzioni5BIntegrazioniAppTest.in5_*` (il giro degli arrivi).

#### IN-8 · Il media ricevuto arriva nella Libreria senza la sua data e senza metadati
- **Gravità:** Sbagliata. **Stato:** plausibile.
- **Percorso:**
  - `sortTimeHint = clock.nasNowMs() ?: 0L` (`IncomingQueue.kt:103`). A freddo o col NAS spento (E-X8) vale 0, cioe' l'1/1/1970, in fondo alla timeline.
  - Il cammino `INCOMING` va da `LINKED` a `DONE` senza preparare ne' pubblicare nell'indice (`core-backup/.../upload/SafeUploadEngine.kt:499-512`), e
    la scansione vede la posizione come gia' nota. Quindi la lettura dei metadati non avviene: data, MIME e copertina del video mancano.
- **Prova (F40):** una foto WhatsApp e un video da Chrome, una volta a freddo e una col NAS spento. Controllare data, pannello informazioni e copertina.
- **Correzione:**
  - una data provvisoria che si corregge alla prima ora del NAS nota;
  - i metadati del file in arrivo letti dai byte locali, come fa `PreparingUploadIndexing` per il backup.
- **Stato: corretta** (20/10); la copertina del video resta da provare sul telefono (F40). La data: `AppIncomingQueue` usa l'ora del NAS se nota,
  altrimenti la data del file in arrivo (`lastModified`), **mai 0** (l'1/1/1970). I metadati: se il media e' nato dall'arrivo (`created`), `AppIncomingMetadata`
  li legge dai **byte locali** con l'estrattore dell'app (data di scatto con il fuso, dimensioni, durata, tipo) e li scrive nel catalogo
  (`MediaCatalog.updateMetadata`): la data di scatto vince su quella d'arrivo. Il resto (miniatura, indice del NAS) lo fa la preparazione normale
  dopo il caricamento. Test: `Correzioni5BIntegrazioniAppTest.in8_*` (2: con EXIF 2020; senza EXIF, come WhatsApp, la data d'arrivo; e la data provvisoria senza l'ora del NAS e' quella della copia, mai 0: con 0 il test e' rosso).

#### IN-9 · Selettore e provider ignorano l'accesso parziale ("solo le foto selezionate")
- **Gravità:** Sbagliata. **Stato:** l'elenco e' confermato dall'agente IN; la concessione e' plausibile.
- **Percorso:**
  - `SubsetView.snapshot` restituisce l'indice intero (`app/.../gallery/PartialAccessTimeline.kt:78`).
  - Il selettore (`PickerViewModel.kt:134-135`) e il provider (`LibraryMediaDocuments.kt:171`, `recent`) scorrono lo snapshot, non la vista.
  - Mostrano quindi anche le foto non concesse (senza miniatura) e i solo-NAS che la Libreria nasconde.
- **Correzione:** iterare sulla vista (`snapshotIndex`/`viewIndexOf`).
- **Stato: corretta** (20/10). Selettore e provider scorrono la **vista** della Libreria (`snapshotIndex(viewIndex)`), non l'indice: la griglia del selettore
  (`PickerItems.of(view, ...)`), "Recenti" del provider (`recent`) e il controllo prima di consegnare (`viewIndexOf`) vedono solo cio' che la Libreria
  mostra, quindi con l'accesso parziale non compaiono ne' le foto non concesse ne' i solo-NAS che la Libreria nasconde, e un id fuori dalla vista non
  esce nemmeno se l'indice lo conosce. **Finto allineato:** `FakeIntegrationLibrary.restrictView` (indice intero, vista parziale) e **test di parita'**
  `PartialAccessTimelineTest.in9_*` (`:app`) contro `SubsetView`. Test: `PickerViewModelTest.in9_*` (2), `LibraryMediaDocumentsTest.in9_*`. Prova di mutazione: con «Recenti» dall'indice `LibraryMediaDocumentsTest.in9_*` e' rosso.

#### IN-10 · Sblocco nelle activity d'ingresso: senza credenziale di sistema e' un vicolo cieco; "Annulla" puo' riaprire il prompt
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:**
  - Senza una credenziale di sistema (`NotAvailable`), "Sblocca" dice solo "Non sono riuscito a sbloccare".
  - Il prompt riparte a ogni `onResume` (`PickerActivity.kt:95`, `ShareReceiverActivity.kt:139`): se la schermata delle credenziali mette in pausa
    l'activity, annullare la riapre in ciclo.
- **Stato: corretta** (20/10); resta da provare con un telefono senza blocco schermo (T-14). Lo sblocco dice il perche': `UnlockFailure`
  (`CANCELLED`, `NO_SCREEN_LOCK`, `OTHER`) da `EntryUnlock.lastFailure()` / `ReceiveUnlock.lastFailure()`; senza credenziale di sistema la schermata spiega cosa
  fare («imposta un blocco schermo») invece del generico «Non sono riuscito a sbloccare»; «Annulla» non dice niente. Il prompt parte **una volta** per
  schermata (`autoPrompted`, `repeatOnLifecycle`) e non si riapre in ciclo se la schermata delle credenziali mette in pausa l'activity; «Sblocca» lo
  rilancia. Test: `PickerActivityTest.in10_*` (2), `ShareReceiverActivityTest.in10_*`, `PickerViewModelTest.in10_*`.

#### IN-11 · Piccoli difetti della schermata di ricezione
- **Gravità:** Minore. **Stato:** confermato (agente IN).
- **Percorso:**
  - "Apri la cartella scelta" mostra solo le sottocartelle di `Ricevute/` note al catalogo, quindi quasi sempre nessuna (`IntegrationsAdapters.kt:351`).
  - Su `FAILED`/`GONE`, "Riprova" chiude la schermata (`ReceiveViewModel.kt:351`).
  - Su "niente da salvare" con dei rifiuti e senza cartelle, il testo non dice che manca la cartella.
  - Col NAS spento il passaggio "Salvo…" dura fino a 5 s (la sonda).
  - Sui dati mobili la frase dice "il NAS non risponde".
- **Stato: corretta** (20/10) per tutti e cinque i punti. (1) «Apri la cartella scelta» non porta piu' a una schermata vuota: se non ci sono
  sottocartelle lo dice (`noSubfolders`) e resta dov'e'. (2) «Riprova» chiude la schermata per `GONE` e `TEST_ONLY` (non c'e' niente da ritentare) e riprova
  per `FAILED`. (3) «Niente da salvare» con dei rifiuti e senza cartelle dice che manca la cartella (`noFolder`). (4) Col NAS spento il passaggio «Salvo…»
  non aspetta piu' la sonda: si mette in coda subito e la frase giusta (`nasWait`) arriva dopo, asincrona (`checkNasWait`). (5) Sui dati mobili la frase e'
  «lo carico appena sei su Wi-Fi», non «il NAS non risponde» (`NasWait.METERED`; `NasReach.Unreachable` ha il motivo). Test: `ReceiveViewModelTest.in11_*` (2),
  `ShareReceiverActivityTest.in11_*`, `Correzioni5BIntegrazioniAppTest.in11_*`.

#### IN-12 · Un errore che non e' di I/O al salvataggio chiude l'app; un file illeggibile si scarta in silenzio
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:**
  - `ReceiveViewModel.save` intercetta solo `IOException` (`ReceiveViewModel.kt:322`): una `SQLiteException` di Room (disco pieno) chiude l'app.
  - Un `quickHashOf` nullo salta il file senza dirlo, e il numero "in coda" e' piu' basso di quello copiato (`IncomingQueue.kt:102`).
- **Stato: corretta** (20/10). `ReceiveViewModel.save` intercetta ogni `Exception` (non la cancellazione), non solo `IOException`: una `SQLiteException`
  per il disco pieno mostra «non disponibile» e non chiude l'app. Un file copiato che al momento della coda non si legge piu' (impronta nulla in
  `AppIncomingQueue.enqueue`) non sparisce piu' in silenzio: `outcome.items` e' piu' basso dei file accettati e la schermata dice «N file copiati non si
  leggono più e non li ho messi in coda» (`notQueued`). Test: `ReceiveViewModelTest.in12_*` (2).

#### IN-13 · La prova del NAS guarda solo la prima area; `PICK` classico non servito
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `IntegrationsGraph.nasReach` sonda solo `areas().first()` (`IntegrationsGraph.kt:128`).
  - `PICK` con un URI del MediaStore (`vnd.android.cursor.dir/image`) non entra nel filtro del manifest, e `PickerRequest.from` lo rifiuterebbe.
  - In debug compaiono anche le voci "MCG prova (T-14/T-15)" e una radice "MCG prova": chi prova puo' scegliere quella sbagliata.
- **Stato: corretta in parte** (20/10). (1) `IntegrationsGraph.nasReach` sonda **tutte** le aree: basta una che risponde per dire `Reachable`; se
  nessuna risponde, `Unreachable` con il motivo. (2) `PICK` classico con il tipo del MediaStore (`vnd.android.cursor.dir/image` e `.../video`) e' nel
  manifest e `PickerRequest.from` lo serve (immagini o video secondo il tipo; audio e «voce singola» restano rifiutati); un `PICK` con un indirizzo del
  MediaStore e senza tipo si legge dal percorso dell'indirizzo, senza mai aprirlo. (3) Le voci di debug «MCG prova (T-14/T-15)» e la radice «MCG prova»
  **non sono toccate**: esistono solo nella build di debug e servono a quelle prove guidate; in release non ci sono. Test:
  `Correzioni5BIntegrazioniAppTest.in13_*`, `PickerRequestTest.in13_*` (2), `ManifestComponentsTest`.

### 2.5 Lavori, Diagnostica, "Separa", prove guidate, debug/release (LA)

#### LA-1 · `INTEGRATIONS_COUNTERS` si esporta senza nessun numero
- **Gravità:** Sbagliata. **Stato:** confermato (agente LA, ricontrollato).
- **Percorso:**
  - Il contributore mette chiavi `receive_*`, `edit_*`, `share_*`, `picker_*`, `provider_*`, `entryAllowed` e `receiveReady`
    (`app/.../integrations/IntegrationsGraph.kt:282-291`).
  - L'esportazione tiene solo le chiavi di `ExportKeys.ALLOWED` o delle sue famiglie
    (`feature-diagnostics/.../export/ExportKeys.kt:20-81`; filtro in `DiagnosticsExporter.kt:86`). Nessuna di quelle chiavi c'e'.
  - Il file contiene quindi soltanto codice e gravita'. Nessun test controlla questo contributore.
- **Scenario:** decisioni P14 chiede di guardare `INTEGRATIONS_COUNTERS` durante F34–F42, ma l'esportazione e' vuota.
- **Correzione:** una famiglia di chiavi per i contatori, per esempio `[a-z]+_[a-z_]+` limitata ai prefissi noti, e lo stesso test di chiavi del backup.
- **Stato: corretta** (20/10). `ExportKeys` ha le 55 chiavi dei contatori (`IntegrationCounter`, `ReceiveCounter`, `EditCounter`, `ShareCounter`) piu' `entryAllowed` e `receiveReady`, una per una
  come le altre: niente famiglia a regex (avrebbe fatto passare anche `share_mario_rossi`). `IntegrationsGraph.counterExport` costruisce i valori. Test: `IntegrationsCountersExportTest` (:app): ogni chiave
  del contributore e' nell'elenco; ogni contatore del codice e' in elenco (uno nuovo senza chiave fa fallire il test); il file esportato ha i numeri e i flag (prima solo codice e gravita'); un nome fuori
  elenco, anche con la forma di un contatore, non passa.

#### LA-2 · "Mostra come principale" non passa all'altro dispositivo quando gli id sono diversi
- **Gravità:** Sbagliata. **Stato:** confermato per l'id (ricontrollato); plausibile se il gruppo si forma dopo l'arrivo della voce.
- **Percorso:**
  - `SimilarCopiesChoices.coverEntries` risolve la voce `similar_cover` **solo per id**
    (`core-personal/.../store/SimilarCopiesChoices.kt:143-153`); lo stesso fa `core-library/.../similar/CatalogSimilarCopies.kt:292` e `:378`.
  - Il commit `5c1138e` ha dato l'impronta solo a "Separa".
  - Se il gruppo si forma dopo l'arrivo della voce, `applySimilarCover` restituisce `APPLIED` senza riprovare, e la scelta si perde.
- **Scenario:** F31-bis con l'id del tablet diverso: "Separa" funziona, "Mostra come principale" no.
- **Nota:** "Separa" e' corretto: chiave, valore con l'impronta e `onChoiceSaved` → `personalChanged` sono collegati. Annullare una separazione non e'
  promesso (§2.12 punto 7).
- **Correzione:** la stessa regola di "Separa" (`fingerprintOfMedia`/`mediaWithFingerprint`) per la copertina.
- **Stato: corretta** (20/10). `SimilarCopiesChoices` ritrova il media di una voce `similar_cover` come «Separa»: una sola classe (`Locator`) per id (anche tramite un alias) e, se non c'e', per impronta **solo
  se indica un media solo**; `chosenCover` e `saveCover` (che spegne la voce accesa dell'altro dispositivo) leggono cosi' la scelta, e `CatalogPersonalProjection.applySimilarCover` fa lo stesso per le
  voci dello stato. Il rilevatore di core-library (`CatalogSimilarCopies`, righe 292 e 378) non cambia: chiede `chosenCover` e ora lo ottiene. Il secondo sintomo («se il gruppo si forma dopo l'arrivo
  della voce la scelta si perde») era lo stesso: il rilevatore non ritrovava la voce. Test: `SimilarCopiesProjectionTest`, 5 casi nuovi con id diversi sui due dispositivi (4 rossi sul codice di
  prima): la scelta del telefono arriva e il rilevatore del tablet la legge; vince la piu' recente su entrambi; una scelta nuova spegne quella dell'altro; l'ordine d'arrivo non cambia la copertina;
  un'impronta ambigua non sceglie niente.

#### LA-3 · Debug con un account di prova (D13): le versioni di E-E7 restano aperte per sempre
- **Gravità:** Minore. **Stato:** confermato (agente LA).
- **Percorso:**
  - I candidati di `Pictures/MyCloud Gallery/` (`app/.../backup/FiveBGraph.kt:253-257`) diventano lavori `VERSION`.
  - La lettura del telefono recintata risponde `NOT_SELECTED` (`app/.../backup/MediaStorePhoneBytes.kt:117`) e il motore aspetta
    (`NasVersionEngine.kt:436`).
  - Il lavoro non si chiude, non dice niente, e `nextAll` continua a chiedere il giro delle foto. Va provato con un account vero (vedi MC-5).
- **Correzione:** in debug, non proporre come candidati le righe fuori dalla recinzione (o chiudere quei lavori con un codice).
- **Stato: aperta, non corretta qui** (20/10). Il percorso e' confermato nel codice e la correzione e' piccola, ma sta in `FiveBGraph` (`app/.../backup/FiveBGraph.kt:253-257`), file dell'agente delle versioni
  e fuori dal perimetro di questa correzione. Il difetto: i candidati extra di E-E7 (`backupCatalog.candidates(CandidateQuery(downloads, ...))`, la cartella `Pictures/MyCloud Gallery/`) non passano dalla
  recinzione D13, che invece filtra le sorgenti del piano (`DefaultBackupPlanner`: `fence.allowsPhoneFolder`); con un account di prova il lavoro `VERSION` nasce, la lettura recintata risponde
  `NOT_SELECTED` e il motore aspetta per sempre. Patch proposta (debug soltanto, in release `fence` e' null): nell'`extra` di `FiveBGraph` restituire `emptyList()` se
  `fence?.allowsPhoneFolder(account, "Pictures/MyCloud Gallery/") == false`; test: un candidato fuori recinzione non diventa un lavoro `VERSION`.
- **Stato: corretta** (20/10, dall'agente delle versioni, con la patch proposta qui sopra). Nell'`extra` di `FiveBGraph` i candidati di
  `Pictures/MyCloud Gallery/` passano dalla recinzione di D13 come le sorgenti del piano; con la recinzione e senza account non passa niente (fallire
  chiusi, come `MediaStorePhoneBytes`); in release la recinzione e' null e non cambia niente. Test (grafo vero, `Correzioni5BVersioniAppTest`):
  `la3_unaCopiaScaricataFuoriDallaRecinzioneNonDiventaUnLavoroCheAspettaPerSempre` (rosso prima: il lavoro nasceva e `nextAll` chiedeva le foto) e il
  caso di controllo `la3_senzaRecinzioneLaCopiaScaricataModificataDiventaUnaVersione` (E-E7 resta: senza recinzione la copia modificata sale).
  Trovato scrivendo il test, **non corretto**: la copia scaricata e modificata da sola non fa partire nessun giro (`BackupGraph.hasWork` non conta i
  candidati di `Pictures/MyCloud Gallery/`, e `BackupStages.round` senza lavoro non chiama il motore): sale col primo giro che parte per altro. Il
  test ci aggiunge una foto nuova della fotocamera. Il rimedio va pensato con LA-4 (`docs/decisioni.md`, «Correzioni 5B: versioni e correzioni»).

#### LA-4 · Possibile andirivieni fra i lavori delle foto, degli arrivi e del guardiano
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:**
  - Ogni lavoro che finisce con `None` richiama `afterPhoneRead` (`work/BackupWork.kt:325-334`, `:185-202`), che chiede con `KEEP` i lavori degli altri
    tipi.
  - Basta un `VERSION` fermo in un'attesa non "tenuta" (rete, `journal.busy`, LA-3) e un altro tipo con lavoro aperto perche' i lavori si richiamino a
    vicenda.
- **Prova:** contare le esecuzioni WorkManager di fila in quella condizione.
- **Stato: non confermata come ciclo; nessuna correzione** (20/10). Riletti `BackupWork.kt` e `BackupGraph.nextAll`: un lavoro che finisce con `None` chiede (`KEEP`) i lavori con una richiesta ancora aperta,
  ma quello del **suo** tipo non puo' essere accodato mentre lui gira (`KEEP` con un lavoro in corso), e le attese vere (`Waiting`, `Stopped`, errore del NAS) non passano da li' (`afterWait`, `Retry`). Un andirivieni
  stabile servirebbe a **due tipi** di lavoro con una richiesta aperta che non si chiude e un giro che finisce senza fare niente e senza attesa; l'unica richiesta di questo genere che ho trovato e'
  quella di LA-3 (un `VERSION` fermo che `hasOpenVersionWork(false)` fa chiedere al lavoro delle foto): da sola costa un giro a vuoto per ogni lettura del telefono, non un ciclo. Non ho toccato `work/` (fuori
  dal perimetro): se la prova del telefono lo vedesse (contare le esecuzioni di WorkManager di fila con un `VERSION` fermo e un arrivo aperto) il rimedio e' non richiamare `afterPhoneRead` da un giro che
  non ha fatto niente.

#### LA-5 · Il cancello degli arrivi non e' verificato nel dex di release
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `tools/check.ps1:317` e `proguard-rules.pro` controllano solo `AppBackupGate.stateOf`.
  - `IncomingGate.stateOf` (`app/.../backup/FiveBAdapters.kt:339-352`), che porta D12/R2 a "Salva sul NAS", no.
  - Il comportamento e' giusto (i membri sono tenuti da `core-common/consumer-rules.pro`): manca solo il controllo.
- **Stato: corretta** (20/10). `tools/check.ps1`, nel passo "D12 e R2 nella release", legge nel dex di release anche `IncomingGate.stateOf`: chiama `backupMayWriteTo` e `backupMayWriteAs`, risponde `TEST_ONLY` dopo ognuna
  ed e' istanziato da una classe del backup (un cancello tolto, fuso o con una decisione in meno fa FALLIRE il passo). `app/proguard-rules.pro` lo tiene come tiene `AppBackupGate`. Provato il lettore del dex su un
  `dexdump` finto con quattro casi (a posto, senza decisione per account, senza `TEST_ONLY`, solo `AppBackupGate`) e la sintassi dello script; il passo vero (APK di release) non l'ho lanciato: lo fa `check.ps1`.

#### LA-6 · Prove guidate: "Azzera i numeri" cancella anche le scelte della schermata
- **Gravità:** Sbagliata (solo debug). **Stato:** confermato (agente LA).
- **Percorso:**
  - "Azzera" (`app/src/debug/.../probes/FieldProbeScreen.kt:368-371`) toglie dallo store anche forma, latenza, numero di file e modo T-15
    (`FieldProbeStore.kt:82`).
  - Le variabili della schermata (`:85-88`) mostrano ancora le scelte vecchie, mentre provider e activity usano i valori di default.
  - Senza un tocco su un chip, il resoconto non dice quale forma e' stata usata.
- **Stato: corretta** (20/10). «Azzera i numeri» azzera le misure e **tiene le scelte** della schermata (forma, latenza, numero di file, modo di T-15: `FieldProbeStore.resetNumbers`); la schermata le rende esplicite
  all'apertura e dopo l'azzeramento (`seedChoices`) e rilegge le sue variabili dallo store, cosi' non mostra una forma e i componenti ne usano un'altra e il resoconto dice sempre quale forma e' stata usata.
  Test: `FieldProbeCorrezioni5BTest` (`azzerareLeMisureTieneLeScelteDellaSchermata`, `leScelteNonFatteDiventanoEsplicite...`).

#### LA-7 · Prove guidate: il riferimento e le righe di T-09 e T-16 si perdono con la morte del processo o con la ricreazione
- **Gravità:** Sbagliata (solo debug). **Stato:** plausibile.
- **Percorso:**
  - `baseline`, `trashedIds`, `writeRow` e `pending` stanno in `remember` e nei campi di `FieldProbeT09` e `FieldProbeT16` (`:43-46`, `:59-65`).
  - T-09 a/b/c e T-16 mandano l'utente in Samsung Gallery. Al ritorno dopo una ricreazione:
    - "Confronta" dice "Prima la fotografia di riferimento";
    - "Ripristina" non trova le righe.
  - I contatori sopravvivono, queste variabili no.
- **Stato: corretta** (20/10). Il riferimento di T-09, le righe cestinate, la foto in scrittura, il riferimento di T-16 e la copia in modifica stanno anche su file (`FieldProbeStore.saveSession`: soli numeri e il nome
  `probe-<id>.jpg`, mai nel resoconto) e `FieldProbeT09`/`FieldProbeT16` si ripristinano alla creazione; cosa si aspetta dal dialogo (`Pending`) e' un `rememberSaveable`. Test: `FieldProbeCorrezioni5BTest` (T-09:
  confronto, cestino e ripristino, riscrittura dopo un «processo nuovo»; T-16: confronto e fine della modifica con permessi revocati e copia tolta; un nome che non e' di una copia di prova non riapre niente). Per effetto di R3 (il riscrittore non scrive se la riga ha gia' i byte attesi) la riscrittura di T-09 ora usa un byte in piu' in coda e poi
  rimette la foto com'era (codici 7 e 8 nel resoconto).

#### LA-8 · Prove guidate: messaggi lontani dai tasti, chiavi T-14/T-15 sovrascritte, scritture del contatore a ogni lettura
- **Gravità:** Minore. **Stato:** confermato (agente LA).
- **Percorso:**
  - `message` compare sotto "Ambiente" (`FieldProbeScreen.kt:142`).
  - `picker_caller`, `docs_caller` e `reopen_*` non sono indicizzati e si sovrascrivono fra un'app e l'altra.
  - `setIfAbsent` registra solo il primo `serve_first_read_ms`.
  - `FieldProbeServer.kt:115-118` scrive il file dei contatori fino a tre volte a ogni `onRead`, proprio dove T-14 misura i tempi.
- **Stato: corretta** (20/10). Il messaggio di un tocco compare in fondo alla sua sezione (`noticeItem`); `picker_*` e `t15.*` di ogni ricezione hanno un indice (una riga per ogni app); `docs_caller` ha un valore per
  chiamante (`recordDistinct`); `serve_first_read_ms` e' per apertura; la riprova di T-15 scrive un blocco indicizzato per volta; lo store del processo scrive il file in differita (150 ms, fuori dalla lettura misurata)
  invece di riscriverlo fino a quattro volte a ogni `onRead` (`flush` quando una ricezione finisce, perche' T-15 uccide il processo apposta). Test: `FieldProbeCorrezioni5BTest` (righe per chiamante, prima lettura per
  apertura, riprove, 200 scritture contro 2).

---

## 3. I fake che divergono dal vero

| Fake | Test coinvolti | Differenza dal vero |
|---|---|---|
| `FakeMediaStore` + `FakePhoneMediaRewriter` + `FakePhoneMediaIntents` (core-mediastore/testing) | `FiveBIntegrationTest` (ripristino, "Libera spazio"), `CorrectionsTest`, `VersionKeeperTest`, banco 5B | Il permesso vale per `(volume, _ID)` in qualunque collezione, e le richieste non lanciano mai. Il vero ragiona per URI, e l'app chiede `files/` (VE-2) |
| Prova guidata T-09 (`FieldProbeT09`, `FieldProbePhone.refOf`) | prova sul telefono | Dialogo e riscrittura con lo stesso URI tipizzato; l'app no (VE-2) |
| `FakeBackupMediaActions` (feature-viewer/test) | `ViewerBackupMenuTest`, `BackupActionsTest` | `dismissShare` conta e basta; il vero rilascia copie e permessi (MC-1) |
| `FiveBIntegrationTest`: `rig.round()` dopo ogni gesto | versioni dal telefono, "Modifica con…" | Il giro lo chiama il test. Nell'app un `planStaged` non chiede nessun giro (MC-3), e il giro delle versioni non ha il tipo (VE-1) |
| `FiveBIntegrationTest`, "Modifica con…" su `Archivio/` (area privata) | — | Non prova mai un solo-NAS dell'area comune o fuori dalla radice D13 (MC-4) |
| `FiveBIntegrationTest`, integrazioni | integrazione `:app` | `familyReady = { true }`, blocco gia' letto, niente `install()`/`atStart()`, destinazione senza recinzione, `LibraryMediaDocuments` chiamato senza provider, Libreria del ricevuto non controllata (IN-1, IN-2, IN-3, IN-8) |
| `InMemoryFreeSpace` (`core-backup/.../testing/FiveBFakes.kt:247-436`) | `FreeSpaceFlowTest`, `FreeSpaceViewModelTest`, schermate | `verify` va subito a `READY`: guardiano e verifica a tappe mai esercitati (LS-5, LS-8). Un esito sconosciuto e' "in dubbio" senza la prova del telefono. Non produce gli esclusi del cancello ne' il piano vuoto di `NoFreeSpace` (LS-10) |
| `PhoneSystemDialogs` finto di feature-backup | test di "Libera spazio", Versioni, Correggi | Non lancia mai (VE-2) |
| `InMemoryFreeSpaceCatalog` | test di P9 | Il MIME lo sceglie il test (nel vero e' spesso null, VE-2); un alias non si risolve; nessun costo per media (LS-15) |
| `FreeSpaceViewModelTest.senzaIServiziDiceCheNonEPronto` | — | Prova uno stato che in produzione non si raggiunge (LS-10) |
| `FiveBButtonsTest` (righe 143-145) | — | `advice.toString().isNotEmpty()` e' sempre vero: non prova che "Elimina solo dal telefono" non risponda "non disponibile" per tutti (lo fa in release, LS-1) |
| `InMemoryMediaDocuments` | `MediaDocumentsProviderTest` | Radice con id fisso; il vero e' casuale per processo (IN-2) |
| `FakeEntryGate`, `FakeEntryUnlock` | test di selettore e ricezione | Decidono subito e sbloccano sempre. Il vero e' `NOT_READY`/`NEEDS_UNLOCK` durante il caricamento, non si rivaluta, e risponde false senza credenziale (IN-3, IN-10) |
| `FakeIncomingQueue` (`allowed = true`), `ReceiveFakes.destinationAllowed` | `ReceiveViewModelTest`, `DefaultIncomingUploadsTest` | Accetta sempre e non programma. Il vero in release rifiuta tutto (IN-6) e programma con le regole del preset (IN-5) |
| `FakeIntegrationLibrary` | selettore e provider | Vista uguale allo snapshot e sempre piena; il vero e' vuoto a freddo e diverso con l'accesso parziale (IN-9) |
| `InMemoryBackupEngine` | banco della 5A | `Step.Wait` per `VERSION`, `SWAP` e `INCOMING`, e rifiuta `INCOMING` |
| WorkManager e JobScheduler | tutti | Sempre finti. Il lavoro UIDT `1_000_104` non e' mai stato eseguito (IN-7) |

---

## 4. Flussi provati solo con fake: da mettere per primi nelle prove sul campo (F34–F45)

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere.

1. **"Condividi" dal viewer verso WhatsApp, Telegram e Gmail** (F42):
   - con "Rimuovi la posizione", e un solo-NAS senza;
   - aspettare piu' di 3 s prima di scegliere la chat;
   - tornare subito nell'app (MC-1).
   - Poi la stessa condivisione dalla selezione della Libreria (MC-2).
2. **"Ripristina originale" e "Correggi" di una foto del telefono con il dialogo di sistema vero** (F34, F36): prima T-09 con il dialogo costruito come
   nell'app (VE-2). Ruotare lo schermo con il dialogo aperto (VE-5).
3. **Ricezione a processo freddo** (F40):
   - `am force-stop`, poi "Salva sul NAS" dopo 5 s (IN-1, IN-3);
   - preset Notte di giorno e fuori casa (IN-5);
   - il ricevuto nella Libreria: data, informazioni, copertina (IN-7, IN-8);
   - video da 1 GB con la notifica e "Ferma" (IN-7).
4. **Provider dei documenti in DocumentsUI vero** (F39): dopo `am kill`, a freddo, con il blocco "Subito" (IN-2, IN-4); accesso parziale (IN-9).
5. **"Libera spazio" con il dialogo vero** (F37):
   - un file con i metadati non letti (VE-2);
   - verifica lunga uscendo dalla pagina o con lo schermo spento (LS-2);
   - morte del processo con una selezione (LS-6);
   - rifiuto al secondo lotto (LS-7);
   - conferma durante il primo backup (LS-13);
   - guardiano con "Fai ora" in corso (LS-8).
6. **"Elimina solo dal telefono" dal viewer** (F38):
   - la pagina sotto il viewer (LS-3);
   - "Prima mettilo al sicuro" (LS-4);
   - in release (LS-1).
7. **Video modificato e salvato sopra** (F35):
   - con quali regole sale, e se una foto scattata subito dopo aspetta (VE-1);
   - "Da decidere" → "Elimina l'originale" col NAS spento (VE-6).
8. **"Modifica con…" di un solo-NAS in Google Foto** (F41):
   - quando arriva sul NAS (MC-3);
   - un solo-NAS fuori da `mcg-campo/` o nell'area comune (MC-4);
   - fuori casa a freddo (MC-6);
   - "Scarica e modifica" (MC-5, LA-3).
9. **Modifica dal PC durante una versione e Samsung "Ripristina originale"**, con l'app in primo piano e chiusa (F34): cosa dice la pagina
   (VE-9), quando riparte (VE-10).
10. **"Mostra come principale" sul tablet con id diversi** (F31-bis, LA-2).
11. **Esportazione della Diagnostica durante F34–F42** (LA-1) e **prove guidate T-09, T-14, T-15, T-16** con ricreazione e "Azzera" (LA-6, LA-7, LA-8).
12. **APK di release** (F44): con D12/R2 ogni scrittura della 5B e' chiusa. Controllare i messaggi (LS-1, IN-6, VE-7).
13. **Interruzioni della 5B con il grafo dell'app** (F43): cambio d'area durante una verifica (LS-5).

---

## 5. Flussi verificati da capo a fondo

Una riga ciascuno: il percorso e' completo nel codice e i dati arrivano alla schermata (a processo caldo, salvo dove e' detto).

**Porte e collegamenti**
- **Porte di Hilt della 5B:** `FiveBModule` e' in `src/main`, quindi vale in debug e in release. Lega tutti gli `@BindsOptionalOf`, tranne `CorrectionPlaces`
  (scelta dichiarata). Le facciate vanno ai servizi veri appena il backup parte; prima rispondono come i null-object.
- **`DeveloperTools` di release:** stesse firme, con `backupFence()` a null.
- **R8:** `-dontobfuscate`, Room e Hilt senza riflessione, activity dal manifest; i `FieldProbe*` non entrano in release.
- **Lavoro UIDT `1_000_104`:** id fuori dall'intervallo di WorkManager; `cancelAll` toglie entrambi i lavori; `triggerOf` riconosce `INCOMING`.

**Versioni e correzioni**
- **Samsung "Salva" → versione:** lettura del telefono → `ContentChanged` → `planPhoneVersions` prima del giro della 5A (stessa pagina di 500 candidati,
  stesso ordine) → `VERSION` → l'originale in `Versioni/`, la modifica al solito percorso. Vale per le foto; per i video vedi VE-1.
- **Samsung "Ripristina originale" (E-E2):** `reverts` del catalogo → `planRealign` (scambio senza conferma). A processo freddo il recupero di `atStart`
  sui 500 media con versioni copre la finestra prima che il backup ascolti; quando parte, vedi VE-10.
- **Foglio Versioni:** piano dal solo catalogo, voci del menu secondo `restore`, "Elimina le intermedie…" → foglio "Elimina…" della 4B sulle copie in
  `Versioni/`.
- **"Ripristina originale" di un solo-NAS:** scambio subito (`runJob` di 2 minuti) dentro il turno del backup. Con il permesso negato vale E-E10:
  scambio sul NAS, telefono com'era, il foglio lo dice. Per le righe del telefono vedi VE-2.
- **"Correggi" di un JPEG/PNG/WebP al sicuro:** piano → conferma → permesso → copia corretta, riletta e decodificata → catalogo prima del telefono →
  `VERSION` prima della riscrittura → `runJob`.
- **Correzioni solo nell'app (HEIC, RAW, video):** data e luogo si vedono (timeline, informazioni); la rotazione no (VE-4).
- **"Da decidere":** nasce solo per l'originale di un video, una volta. "Tienilo" chiude per sempre. "Elimina l'originale" apre il foglio della 4B sopra la
  pagina (`FiveBHostEffect` sta fuori dal `NavHost`) e, a operazione partita, chiama `complete`.
- **`SOURCE_CHANGED` in Diagnostica:** colonna `hold` e valore `source_changed` coincidono; titolo, guida e azione "Diagnostica" ci sono (per la pagina
  vedi VE-9).

**"Modifica con…" e "Condividi"**
- **"Modifica con…" di una foto del telefono:** sessione su Room, `ACTION_VIEW` a Samsung Gallery con pacchetto esplicito. Al ritorno, `settle` con
  `syncNow` sospendente:
  - "Salva" = versione;
  - "Ripristina" = originale;
  - "Salva copia" nella sessione = versione dello stesso media.
- **"Condividi" del viewer:** con e senza posizione, solo-NAS compresi; foglio "Condividi con la posizione" solo come scelta esplicita; impostazione
  persistente. Quanto dura la copia: vedi MC-1.

**"Libera spazio" ed eliminazioni**
- **Ingressi:** Raccolte → Utilita' (`openFreeSpace(null)` sul telefono, pannello di dettaglio sul tablet); selezione della Libreria (solo i media sul
  telefono, 0 giorni esclusi).
- **Riepilogo:** filtri, 30 giorni esclusi di default, avviso forte (riepilogo, "pronto" e fogli), esclusi con il motivo.
- **Conferme:** un tocco (`ConfirmTaps`), ricontrollato contro il piano ricalcolato. "Libera subito" con due gesti a 600 ms e un secondo pannello, poi
  `DELETE_NOW` e `createDeleteRequest`.
- **Lotti e dialogo:**
  - controllo finale di timbro e riga, lotto scritto `IN_DIALOG` prima della UI, tetto di 2.000;
  - risposte accettato o rifiutato, catalogo aggiornato con `markPhoneLocationsVanished`.
- **Dopo:** `settle()` all'avvio e dopo ogni lettura del telefono, in primo piano e nel lavoro; righe nel cestino di sistema = liberate. Validita' di 6 ore
  monotone con il conteggio dei riavvii; "Ricomincia" sul blocco scaduto.
- **"Ovunque":** `EverywhereCopies` → `others` con `previousVersion` → piano `Trash` della 4B, sia dal telefono sia da un solo-NAS. Il bersaglio e' la
  copia attuale, e il foglio conta le versioni precedenti.

**Integrazioni**
- **Selettore:** media del telefono (URI del MediaStore, `FLAG_GRANT_READ` e `ClipData`); solo-NAS (sonda di 5 s, id "consegnato", apertura anche col
  blocco richiuso); nascosti esclusi alla consegna e all'apertura (E-H1); annullamento e NAS spento senza id lasciati (E-X4, E-X1); blocco a caldo con la
  tolleranza di due minuti (E-X7).
- **Provider a caldo e sbloccato:** Recenti, Cartelle NAS, byte a intervalli con scadenza. Le porte si leggono a ogni chiamata, non si catturano a
  `onCreate`.
- **Ricezione a caldo in debug su una share di prova:** copia → tocco → `backup_item` `INCOMING` piu' `incoming_item` → WorkManager o UIDT → caricamento
  sicuro → collegamento → copia locale tolta prima del passo terminale. Rifiuti di `file://`, del nostro provider, dei tipi falsi, dei file troppo grandi
  o troppo numerosi. Col NAS spento a casa: "in coda", poi ripresa con l'attesa crescente di WorkManager.

**Prove guidate e "Separa"**
- **Prove guidate:** raggiungibili da Impostazioni → Sviluppo in debug. I quattro componenti del manifest di debug esistono, senza conflitti di autorita'
  con il provider e la ricezione veri; store che sopravvive alla morte del processo; resoconto di soli numeri.
- **"Separa" fra due dispositivi:** chiave, valore con l'impronta, ricerca per impronta, `onChoiceSaved` → sincronizzazione dei dati personali.

---

## 6. Note

**Scartati dopo la verifica:**
- **Porte catturate come null-object.** Il provider dei documenti nasce prima di `Application.onCreate`, ma legge `IntegrationPorts` a ogni chiamata; le
  activity leggono le porte dopo `install()`.
- **Gli id dei file in arrivo.** `ShareCache.randomToken` da' 32 cifre esadecimali, compatibili con il filtro `SAFE` di `AppIncomingLocalFiles`.
- **Le copie in `edit/` all'avvio.** `editSessions()` aspetta il catalogo prima di `sweepAtStart`; senza l'ora del NAS le sessioni non scadono.
- **Il valore `source_changed`.** Coincide fra il diario e le query di `:app`.
- **"Ovunque" da un solo-NAS.** Usa anch'esso l'elenco del backup (`NasOrganizer.targetOf`, `everywhereOthers`).

**Scelte e non difetti:**
- D12/R2: in release la 5B non scrive. Lo sono anche i messaggi (VE-7, LS-1, IN-6), ma quelli sono difetti.
- "Vedi originale" non offerto, `CorrectionPlaces` non legata, `COMMON_AREA_NOTICE` che ferma: scelte di P14.
- "Elimina…" della 4B fuori dal viewer, senza la frase finale (gia' in decisioni P14; per l'effetto vedi VE-6).

**Non letti in dettaglio**, quindi non dati per verificati:
- il motore `NasVersionEngine` passo per passo e `SafeUploadEngine` (li coprono i banchi di P13);
- lo strip della posizione (`DefaultLocationStripper`, `IsoLocation`);
- i layout e gli screenshot dei fogli della 5B;
- i descrittori a richiesta del provider (`ProxyDescriptors`).
