# Revisione di sicurezza della Fase 6 (Ricerca) e delle correzioni dal campo, gruppo A

| | |
|---|---|
| **Oggetto** | i 95 commit da `8753b89` (progetto tecnico) a `263dcc7` (P11, unione con le correzioni dal campo A e le risposte della Fase 7), cioe' `2445d9e..263dcc7`, circa 85 mila righe: P0-P14 con P11 e P11b, e il gruppo A delle correzioni dal campo (D1-D7). In particolare: `core-models` (catalogo con dimensione e SHA-256 fissati, `download/https/`, `PartialFile`, `source/` NAS, cartella dal PC e asset, `shelf/`, `manager/`, `store/ModelStore`, `verify/`), `core-ai` (`runtime/` con `LiteRtLmSessions` e `ModelAccess.PATH_CHECKED`, `ModelInputs`, `ImageHeaders`; `ocr/` con `MlKitBootstrap`, `GatedTextReader`, `TextReadingCleanup`; `compute/` con `DiskAiBuffer`, `ItemWorker`, `MediaComputer`, `ComputeSession`; `cutout/` con MagicTouch su LiteRT), `core-search` (analizzatore delle query, `LocalAiIndex`, `VectorFile` con CRC, `OriginLedger`, `TextSearch`, motore e nascosti), `core-trust` (`AiSegments`, `AiBodies`: formato v2), `core-index/ai/` (`AiShareSync`, `AiStores`, `AiFusion`, `OutboxAiIndexPublisher`, `IndexAnalysisFetcher`), `core-database` (schema v5, `AiIndexCatalog`, FTS4, cronologia), `feature-search`, `feature-viewer` (testo dal vivo, ritaglio, foto in movimento, `NasMotionSource`), `feature-settings`, `core-mediastore` (`CutoutPhoneSaver`), `:app` (`ai/`, `AiGraph`, `AiWiring`, `SearchGraph`, `TextLayouts`, `work/AiWork`, `graph/LocalData`, `MyCloudGalleryApp`, debug `debugtools/eval`), `gradle/verification-metadata.xml`, `gradle/verify-release-manifest.gradle`, `app/build.gradle` (release solo `arm64-v8a`), `tools/check.ps1`; del gruppo A `core-backup` (`BackupPrivateCopyGuardian`, `DefaultCorrections`), `core-database` (`BackupQueries`, `MediaCatalog`), `core-nasops` (`MoveCompletions`), `feature-integrations` (`ShareReceiverActivity` `singleTop`, `DefaultIncomingUploads.discard`), `:app` (`IncomingQueue`, `BackupWork`, `BackupGraph`, `NasOrganizer`). |
| **Riferimenti** | `docs/fase6-progetto.md` (§0.1 D1-D51, §0.2 E-A11-E-A19, E-I20-E-I22, E-Q9-E-Q15, E-V9-E-V17, E-S16; §2.1-§2.11; §6.1-§6.4), `docs/sicurezza.md` (C9, C14, C19, C21, C22, §4-bis, §8, L2, L32, L45-L56, checklist "Fase 6"), `docs/decisioni.md` (sezioni della Fase 6 da "decisioni del progetto" a "P11b", e "Correzioni dal campo, gruppo A"), `docs/review/5b-fase5b.md` e `4b-fase4b.md` (formato). |
| **Attaccanti e guasti** | (a) un **dispositivo di famiglia compromesso**, dello stesso account o di un altro, che pubblica segmenti `ai` e anteprime ostili, o mette modelli finti sul NAS; (b) la **rete e gli host dei modelli** (redirezioni, `200` al posto di `206`, file cambiati, TLS, E-S16); (c) un'**app di terze parti** sul telefono (appunti, ritagli condivisi o salvati, componenti esportati e `FileProvider`, nascosti che finiscono nei ritagli o nei risultati); (d) la **privacy** (ML Kit spento, nessuna rete durante le ricerche, cronologia locale, log, risultati del solo telefono, F4-D31); (e) lo **storage locale** (`PATH_CHECKED`, file dei vettori, buffer, azzeramento); (f) la **catena di fornitura** (dipendenze nuove, librerie native, R8, release, strumenti di debug); (g) i **guasti** (morte del processo, memoria bassa, GPU che da' valori non finiti o fa cadere il processo). |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice di produzione. Niente Gradle, nessun telefono, nessun `adb`, nessun NAS, nessuna rete. |
| **Data** | 22/10/2026 |
| **Metodo** | Lettura del codice nel worktree staccato a `263dcc7`, seguendo ogni via che porta byte, testo o vettori da un altro dispositivo fino a cio' che l'utente vede o che questo dispositivo ripubblica, ogni via che apre la rete o avvia ML Kit, e ogni file che l'app scrive o toglie. Tre revisori di supporto, in sola lettura, hanno letto in parallelo: (1) modelli e catena di fornitura; (2) sezione `ai` dell'indice e fiducia; (3) viewer, appunti, ritagli, privacy di ML Kit, ricerca e azzeramento. Il revisore principale ha letto `PATH_CHECKED`, il calcolo (`compute/`), il buffer, gli adattatori di `:app` e il gruppo A. **Ogni voce riportata qui e' stata ricontrollata nel codice**; per ognuna si dice se e' confermata (dal codice) o plausibile (dipende da un comportamento della libreria o del dispositivo che qui non si prova). Nessun test eseguito. Nessun file del repository modificato a parte questo documento. |
| **Stato** | **Aperta**: tutte le voci. |

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 1 | R1 un dispositivo compromesso porta il file dei vettori oltre 2 GiB: la ricerca e la sincronizzazione si rompono su ogni telefono della famiglia, anche dopo la revoca, e solo l'azzeramento le ripara |
| Media | 5 | R2 pochi segmenti `ocr` grandi fermano la sincronizzazione dell'indice (miniature comprese) e il calcolo su tutta la famiglia · R3 l'indice locale accumula senza tetti testo, vocabolario e righe del viewer di un dispositivo compromesso · R4 il testo dal vivo mostra l'ultimo corpo `ocr` arrivato, anche dopo la revoca di chi l'ha pubblicato, al posto della lettura propria · R5 le anteprime di un altro dispositivo diventano risultati "propri", ripubblicati firmati nell'area privata e mai tolti dalla revoca · R6 "Spegni" la lettura del testo non spegne finche' il telefono non ha l'ora del NAS (fuori casa senza VPN) |
| Bassa | 15 | R7 uno spegnimento durante una lettura fa ripartire ML Kit dopo l'annullamento dei lavori · R8 gli altri dispositivi dell'account avviano ML Kit senza l'avviso promesso · R9 `PATH_CHECKED`: la cache dei programmi compilati non si verifica ne' si ripara, e una GPU che fa cadere il processo non lascia memoria · R10 senza l'ora del NAS la quarantena delle sorgenti dei modelli lancia e non vale · R11 le sorgenti NAS dei modelli non passano dal cancello della rete a consumo · R12 il cancello HTTPS guarda la rete della policy, non quella del socket · R13 scaricamento: parziali buttati, colpa alla sorgente sbagliata, riprese che ripartono da zero · R14 i segmenti `ai` propri non si tolgono mai · R15 la riparazione dell'indice non riconsegna i dati della replica · R16 la ricetta fino a 255 vince sempre, anche sul calcolo locale · R17 la selezione di sistema nel foglio "Testo nella foto" copia senza `EXTRA_IS_SENSITIVE` · R18 il PNG del ritaglio copiato o condiviso resta leggibile fino al prossimo avvio · R19 un ritaglio dai Nascosti non avvia la sincronizzazione del segno "nascosto" · R20 la memoria esaurita nel ritaglio fa cadere l'app · R21 corsa fra mappa e dimensione nel file dei vettori |
| Informativa | 13 | I1-I13 (sotto) |

**Confermate e plausibili.** Delle 21 voci, **16 sono confermate** nel codice (per R1 il limite e la sua permanenza; quanti byte servono e' un calcolo, non una misura) e **5 sono plausibili**: R7 (la corsa e' nel codice, l'invio dipende da quando ML Kit scrive i suoi eventi), R9, R12, R20, R21. R4 e R5 le hanno trovate in modo indipendente sia il revisore principale sia quello dell'indice `ai`.

**`PATH_CHECKED` regge per quello che promette, ed e' una deviazione accettabile da D16** (risposta 2). Un file rinominato o sostituito fra la verifica e l'uso e' rifiutato, e il descrittore aperto impedisce il riuso dell'inode. Resta scoperta solo una scrittura **sullo stesso inode**, che richiede l'UID dell'app: e' fuori dal modello delle minacce (chi ha l'UID dell'app ha gia' database, chiavi e credenziali) e vale allo stesso modo per `DESCRIPTOR`, perche' anche la mappatura del descrittore vede le scritture sullo stesso inode. La TOCTOU quindi **non e' sfruttabile** nel modello del progetto. La deviazione vera e' un'altra: con il percorso il motore legge, a ogni avvio a caldo, **i programmi compilati e i pesi preparati** in `models/cache/<sha256>-<backend>/`, che nessuno verifica e nessuno ripara (R9). E' un rischio di guasto, non di attacco; va scritto in C22 (I1).

**La fiducia regge nel formato e cede a valle della replica.** Codec ostile, firma e versione 2, attestazione per share e chiave intera, M3, F4-D31 e D14 reggono (risposta 3). I problemi stanno dopo: nel **volume** che un dispositivo compromesso puo' far arrivare in ogni telefono (R1, R2, R3: oltre L45, che parla di dati falsi e non di blocchi), nella **copia del testo** che il viewer tiene da parte (R4) e nei **dati derivati** che un indicizzatore onesto calcola sulle anteprime di un altro e poi firma come suoi (R5). Per R1, R4 e R5 "La revoca lo toglie" (L45) non vale: il danno resta dopo la revoca; R2 e R3 invece si fermano con la revoca, ma solo dopo che ha colpito ogni telefono.

**La lettura del testo spenta regge all'avvio, non allo spegnimento.** Da spenta ML Kit non parte: manifest senza `MlKitInitProvider`, un solo cancello, pulizia di DataTransport prima di Hilt, nessun'altra via verso `com.google.mlkit` (risposta 5). Ma spegnerla e' debole: fuori casa "Spegni" torna "Attiva" (R6), una lettura in corso puo' far ripartire ML Kit dopo l'annullamento dei lavori (R7), e sugli altri dispositivi dell'account ML Kit parte senza l'avviso promesso (R8). La pagina di attivazione dice "La spegni quando vuoi: da quel momento ML Kit non parte più" (`feature-search/src/main/res/values/strings_search_activation.xml:18`): oggi fuori casa non e' vero.

**Rete e modelli: l'integrita' regge, il traffico meno.** Impronta e dimensione fissate e verificate a ogni apertura, redirezioni a mano, solo host ammessi, TLS di sistema, niente cookie (risposta 1). R10-R13 riguardano traffico sprecato, dati mobili senza conferma e robustezza, non file sbagliati accettati.

---

## Risposte al perimetro

1. **Modelli, rete e host (D16-D19, E-A1, E-A2, E-A11, E-S16).**
   - **Host ammessi**: `huggingface.co` esatto e i suffissi `.huggingface.co`, `.hf.co` con lunghezza strettamente maggiore (quindi `evilhf.co` e `hf.co` no), minuscole e punto finale normalizzati, solo ASCII, nessun IP (`core-models/.../contract/ModelCatalog.kt:56-69`); `PinnedUrl` senza porta, utente, parametri ne' frammento (`:75-93`).
   - **Redirezioni** seguite a mano (`instanceFollowRedirects = false`), al piu' 5; a ogni salto solo `https`, niente utente, frammento o punto finale, porta 443, host ammesso (`download/https/RedirectPolicy.kt:29-48`, `HttpsModelFetcher.kt:123-131`).
   - **Risposte**: un `200` al posto di `206` riparte da zero; un `206` che non comincia dove si e' chiesto, o con un totale diverso, si rifiuta; un `Content-Length` diverso dalla dimensione fissata si rifiuta prima di leggere; mai piu' byte della dimensione fissata (un byte in piu' e' `Mismatch`); niente compressione (`HttpsModelFetcher.kt:132-160`, `:205-256`). Resta R13 (una ripresa che riceve sempre `200`).
   - **TLS e identita'**: fiducia di sistema, nessun `TrustManager`, `HostnameVerifier` o `network_security_config` proprio, `usesCleartextTraffic=false`; nessun cookie (si parte solo senza `CookieHandler`), `User-Agent` fisso, nessuna cache (`HttpsModelFetcher.kt:79`, `:175-193`).
   - **Verifica**: dimensione e SHA-256 sullo stesso descrittore aperto in sola lettura, a ogni apertura, sotto il lucchetto della cartella; un file che non torna si toglie con la sua cache e torna in coda (`verify/ModelVerifier.kt:43-100`, `verify/LocalModelFiles.kt:25-35`). Promozione con rinomina atomica dopo la verifica del parziale (`store/ModelStore.kt:137-145`).
   - **NAS**: lo scaffale oggi e' spento (`NoModelShelf`, `app/.../ai/AiWiring.kt:197`); la cartella `MyCloud Gallery/Modelli/` si legge con dimensione esatta e link rifiutati. Il cancello della rete a consumo pero' vale solo per HTTPS (R11), e la quarantena dipende dall'ora del NAS (R10).
   - **Restano**: R10-R13, I2-I5.
2. **`ModelAccess.PATH_CHECKED` (deviazione da D16).**
   - **Cosa fa**: `ModelFiles.open` verifica sullo stesso descrittore e lo consegna aperto; `RealSession.create` confronta `stat` del percorso con `fstat` del descrittore (dispositivo, inode, dimensione) **prima e dopo** `initialize()`, e la sessione tiene aperto il modello finche' vive (`core-ai/.../runtime/LiteRtLmSessions.kt:166-176`, `:197-225`, `:144-159`). In produzione il descrittore c'e' sempre (`ModelVerifier.kt:65`); `VerifiedModel` nasce solo in `core-models` (costruttore `internal`, `Phase6ArchitectureTest`).
   - **Contro chi sostituisce file**: regge. Nell'app l'unico che mette un file in `models/<sha256>` e' `promote`, dopo la verifica, con una rinomina (inode nuovo); una sostituzione durante l'inizializzazione da' `MODEL_UNAVAILABLE`, una dopo non cambia cio' che il motore ha aperto.
   - **Contro chi scrive sullo stesso inode**: non regge, ma serve l'UID dell'app; vale anche per `DESCRIPTOR` e per il ritaglio (`MagicTouchCutter.kt:235-251`, mappatura `READ_ONLY` condivisa). Non sfruttabile nel modello.
   - **Non coperto**: la cache dei programmi compilati e dei pesi preparati (`LiteRtLmSessions.kt:192`, `:210`) e' letta dal motore e non e' coperta dall'impronta; la toglie solo un rifiuto, una rimozione o un modello non piu' elencato (`ModelStore.kt:82-93`, `:151-162`). E' R9.
   - **Documenti**: il file non e' in sola lettura come dicono D16 e il KDoc di `ModelAccess` (`LiteRtLmSessions.kt:72`), e C22 dice ancora "sullo stesso descrittore che si passa al motore" (I1).
3. **Sezione `ai` dell'indice condiviso (formato v2, D6-D8, E-I20, E-I21).**
   - **Codec ostile**: `vec` con versione, spazio, dimensione, precisione, conteggio e lunghezza esatta controllati prima di allocare, componenti non finite rifiutate, norma fra 0,99 e 1,01 (`core-trust/.../data/AiSegments.kt:143-187`); `ocr` con al piu' 400 righe, riquadro ordinato, inclinazione fra -90 e 90, da 1 a 64 parole con estremi non decrescenti, testo da 1 a 1.024 byte in UTF-8 valido, NFC, senza caratteri di controllo ne' di direzione, parole uguali ai pezzi del testo (`:217-269`); tabella con chiavi strettamente crescenti; ogni eccezione e' un rifiuto. Coordinate intere fra 0 e 65.535: niente NaN nel viewer. Unica deviazione: la ricetta (R16).
   - **Firma e versione**: testa firmata come le altre, versione 2 solo per le teste `ai`, anti-rollback ed equivocazione, posto, share e dispositivo legati alla firma; una testa `index` non puo' elencare `vec` o `ocr`.
   - **Attestazione**: stessa share, chiave intera, record presente; si riattesta a ogni giro (`core-index/.../ai/AiShareSync.kt:268-276`). **M3** in `stage` e di nuovo nel piano (`OutboxAiIndexPublisher.kt:84-118`; `app/.../ai/AiCatalogPorts.kt:43-54`).
   - **F4-D31 e cancelli**: sotto blocco permanente le voci restano `HELD` e si pubblicano solo quando la share si apre; `flush` passa da `blockFor` e dalla fiducia (`OutboxAiIndexPublisher.kt:115`, `:130-145`, `:184-193`). La lettura al volo del viewer passa dallo stesso pubblicatore (`app/.../ai/AiGraph.kt:216-237`). **Il solo telefono non si pubblica mai** (`ComputeSession.kt:283-288`, `ItemWorker.kt:140`, `:157`).
   - **D14**: si calcola solo sulle share di questo dispositivo, area privata propria e area comune (`AiComputeEngine.kt:108-139`, `AiWiring.kt:273-276`).
   - **Revoca**: i dispositivi revocati, persi o sconosciuti escono dalla replica e dall'indice della ricerca (`AiShareSync.kt:138-163`, `LocalAiIndex.kt:201-203`). **Non** escono dalle righe del viewer (R4) ne' dai risultati che altri hanno calcolato sulle loro anteprime (R5).
   - **Volume**: i tetti per dispositivo (2 GiB e 524.288 voci per share) valgono per la replica, non per cio' che l'indice locale ne ricava (R1, R2, R3).
   - **Restano**: R1-R5, R14-R16, R21, I10.
4. **Ricerca locale (D24-D29, D33, E-Q7, E-Q9).**
   - **FTS4 letterale**: ogni alternativa si riduce a `[a-z0-9 ]` e va al database fra virgolette, con tetti (`core-database/.../AiIndexCatalog.kt:158-170`, `:264-278`). Nessuna sintassi `MATCH` dall'utente ne' dal testo pubblicato.
   - **Nascosti**: candidati solo dalle celle senza nascosti ne' membri di gruppo (`CellContentDao.kt:51-52`), filtro finale (`SearchCore.kt:119-127`), risultati vivi ricontrollati (`LiveResults.kt:27-35`), luoghi e suggerimenti solo da celle visibili, "Trova simili" assente sui nascosti (`ViewerPorts.kt:221-224`); una correzione dei refusi si dice solo se e' fra i risultati.
   - **Nessuna rete**: miniature solo locali, modello delle query solo dai file verificati, luoghi dal geocodificatore offline (`app/.../search/SearchPorts.kt:103-106`); un vettore di query non finito si scarta (`LazyQueryEmbedding.kt:64`).
   - **Restano**: R3 (vocabolario dei refusi in memoria senza tetto).
5. **ML Kit e privacy (D33, D40-D42, D49, C21, L48, L49).**
   - **Da spenta ML Kit non parte**: `MlKitInitProvider` tolto (`core-ai/src/main/AndroidManifest.xml:35-37`) e controllato da `verifyReleaseManifest` e `check.ps1`; `MlKit.initialize` solo in `MlKitBootstrap.ensureInitialized`, che rilegge lo stato (`core-ai/.../ocr/MlKitBootstrap.kt:42-53`); `MlKitTextReader` si costruisce solo dentro la lambda di `GatedTextReader.create`, mai chiamata da spenti (`GatedTextReader.kt:30-33`, `:55-56`); calcolo e lettura al volo passano dal cancello (`MediaComputer.kt:172-185`, `app/.../ai/ViewerPorts.kt:154-157`, `:186-187`).
   - **All'avvio**: con la lettura non attiva qui, prima dell'iniezione di Hilt, si annullano i lavori di DataTransport e si tolgono la coda e l'identificativo di ML Kit; nel dubbio vale "spenta" (`MyCloudGalleryApp.kt:81`, `app/.../ai/TextReading.kt:59-69`, `:149-159`, `TextReadingCleanup.kt:192-198`). Un dispositivo con la lettura spenta usa il testo degli altri senza ML Kit (D42).
   - **Allo spegnimento**: debole. R6 (fuori casa lo spegnimento non arriva allo stato), R7 (una lettura in corso ricrea il riconoscitore dopo il rilascio), R8 (nessun avviso sugli altri dispositivi), I7 (versione del consenso non vincolante), I8 (spegnimento fatto da un altro dispositivo).
   - **Nessuna rete durante le ricerche**: risposta 4; `core-search`, `feature-search` e `core-ai` con `NoNetworkSourcesTest`.
   - **Cronologia**: solo nel database locale, al piu' 50 voci, senza miniature ne' "Simili a", mai nei dati personali ne' nell'esportazione della Diagnostica, tolta con il database all'azzeramento (`core-search/.../history/LocalSearchHistory.kt`, `AiIndexCatalog.kt:214-223`). Il database e' escluso da backup e trasferimento (`app/src/main/res/xml/data_extraction_rules.xml`).
   - **Log e Diagnostica**: nessuna chiamata nuova a `GalleryLog` porta query, testo, luoghi, nomi o chiavi (solo `simpleName` e codici); in release `GalleryLog` non scrive; la Diagnostica della ricerca esporta solo codici e numeri (`app/.../ai/AiDiagnostics.kt:26-64`); `toString` muti sui tipi con testo.
   - **Solo telefono**: i risultati restano locali (risposta 3).
6. **Viewer, appunti, ritagli (D43-D47, D50, D51, E-V9-E-V17, L53, L54).**
   - **Appunti**: "Copia", "Copia tutto" e la copia del ritaglio sono segnati sensibili (`feature-viewer/.../livetext/LiveTextSystem.kt:17`, `cutout/AndroidCutoutSaver.kt:133-142`); la condivisione del ritaglio e' in sola lettura con `ClipData` (`:148-158`). La selezione di sistema del foglio no (R17).
   - **Ritagli**: PNG con i soli blocchi `IHDR`, `IDAT`, `IEND`, senza metadati ne' posizione (`core-ai/.../cutout/PngEncoder.kt:31-105`); "Salva come nuova foto" con `IS_PENDING`, collegamento al catalogo e segno "nascosto" prima che la riga sia visibile (`core-mediastore/.../write/CutoutPhoneSaver.kt:99-148`); righe orfane tolte all'avvio. Il segno "nascosto" non viaggia subito ai propri dispositivi (R19); fuori dall'app la foto e' pubblica (I6). Copia e Condividi passano dal `FileProvider` esistente (`cacheDir/share/`, nessun percorso nuovo), ma il file e il permesso restano fino al prossimo avvio (R18).
   - **Ingressi dei motori (D5)**: a ML Kit e a LiteRT-LM solo JPEG nostri, dopo `ImageHeaders` e `ImageDecoder` con i tetti (`runtime/ModelInputs.kt`); al ritaglio solo pixel decodificati dalla piattaforma con i tetti letti dall'intestazione (`cutout/CutoutImages.kt:57-96`). Mai byte del NAS o di altre app ai decodificatori nativi.
   - **Testo da altri dispositivi**: il codec garantisce coordinate intere, estremi ordinati e niente caratteri di direzione; la geometria del viewer si difende dai riquadri degeneri (`livetext/LiveTextGeometry.kt:44-66`). Nessun crash trovato.
   - **Foto in movimento solo-NAS**: animazione automatica solo con la rete aperta (`video/NasMotionSource.kt:119-121`), badge con conferma e dimensione sui dati mobili (`motion/MotionDirector.kt:168-176`), cache di 64 MB in `cacheDir/motion/`. Resta I9.
   - **Componenti**: nessun componente esportato ne' permesso nuovo; percorsi del `FileProvider` invariati.
7. **Storage locale e azzeramento.**
   - **Azzeramento** (`app/.../graph/LocalData.kt`): AI e ricerca si fermano per prime (`:150`); poi `cacheDir` intera (motion, share), il database (tabelle `ai_*`, FTS, cronologia), `ai-buffer/`, `search/` (vettori, registro, righe del viewer, corpi propri), `ai-unsupported`, `text-reading`, il battito (`:176`, `:224-230`); con l'azzeramento completo anche `models/` con la cache (`:116-124`) e tutti i database, coda di DataTransport compresa. Resta I11 (debug).
   - **File dei vettori**: un CRC per record, una coda troncata si riprende dal multiplo intero, un record rovinato non si usa (`core-search/.../index/VectorFile.kt:58-79`, `:81-92`); cornici del registro con CRC (`OriginLedger.kt:99-123`). Non regge oltre 2 GiB (R1) e la riparazione non riconsegna (R15).
   - **Buffer**: nomi solo da chiave ed esadecimale, temporaneo e rinomina, 500 MB e 1 GiB libero (`core-ai/.../compute/DiskAiBuffer.kt:42-67`, `:109-132`). E' per chiave di contenuto, senza share ne' dispositivo d'origine: e' la base di R5.
   - **Modelli**: `noBackupFilesDir/models/`, fuori dai backup; non in sola lettura (I1).
8. **Catena di fornitura (D38, D49, §8).**
   - **Dipendenze nuove**: `litertlm-android` 0.18.1, `text-recognition` 16.0.1 e `litert` 2.3.0 solo in `core-ai`, con `kotlin-reflect`, le coroutines della libreria e `ai-delivery` esclusi (`core-ai/build.gradle:53-64`); tutte le voci nuove di `gradle/verification-metadata.xml` hanno lo SHA-256, nessun `trusted-artifacts`. Nel grafo c'e' anche la variante di ML Kit che passa da Play services (I2).
   - **Librerie native e manifest**: release solo `arm64-v8a`, allineamento a 16 KB, `uses-native-library` solo le due facoltative, elenco chiuso dei componenti di Google non esportati, niente `MlKitInitProvider` (`app/build.gradle:87`, `:139-148`, `gradle/verify-release-manifest.gradle`, `tools/check.ps1` passo 7e).
   - **Release**: `debuggable false`, R8 con `-dontobfuscate` (i controlli sul dex restano affidabili), regole `keep` limitate ai pacchetti dei motori (`core-ai/consumer-rules.pro`).
   - **Strumenti di debug**: lo strumento di valutazione sta solo in `app/src/debug/`; in release `DeveloperTools.installSearch` e' vuota (`app/src/release/.../debugtools/DeveloperTools.kt:53`) e `check.ps1` cerca i suoi nomi nel dex. Il manifest di debug non e' cambiato.
   - **HTTPS solo da `download/https/`**: `Phase6ArchitectureTest` sui sorgenti e `check.ps1` sul dex; copertura con due buchi (I3).
9. **Guasti (E-A12, E-A16, E-A17).**
   - **GPU che da' valori non finiti**: si chiude il motore, si riapre su CPU e si ripete l'immagine; sulla CPU un vettore non valido e' `NON_FINITE`; un vettore non finito o fuori norma non si salva ne' si pubblica (`runtime/LiteRtLmImageEmbedder.kt:78-99`, `compute/MediaComputer.kt:116-134`). Una GPU che **fa cadere** il processo invece non lascia memoria (R9).
   - **Morte del processo**: risultati salvati per media e outbox per share; buffer e parziali con temporaneo e rinomina o blocchi sincronizzati; prenotazioni che scadono (E-I2). Lo scaricamento HTTPS staccato puo' ancora toccare il parziale dopo un annullamento (R13).
   - **Memoria e spazio**: buffer svuotato e calcolo fermo sotto 1 GiB libero (`ComputeSession.kt:53-60`); controllo dello spazio prima di scaricare un modello. Mancano i tetti sull'indice locale (R1, R3) e la memoria del ritaglio non e' sempre gestita (R20).
10. **Correzioni dal campo, gruppo A (D1-D7).**
   - **D1**: la ricezione `singleTop` rilancia la condivisione nuova in un task suo, con `Intent(intent)` verso la stessa activity e lo stesso cancello d'ingresso; la coda e' tutto o niente in una transazione non annullabile; `discard` non toglie copie che il diario cita, e nel dubbio le tiene (`feature-integrations/.../receive/ShareReceiverActivity.kt:147-160`, `DefaultIncomingUploads.kt:89-103`, `app/.../backup/IncomingQueue.kt:166-188`). Regge.
   - **D2**: il guardiano di sottofondo solo per i media che il telefono ha avuto (posizione del telefono o elemento `new_copy`); un media mai stato sul telefono e spostato nell'area comune resta protetto solo dal cestino di 30 giorni, come deciso (Q4 (b), L44) (`core-database/.../dao/BackupQueries.kt:248`, `:257-260`).
   - **D3**: la correzione riscrive anche le righe del telefono di versioni precedenti, ma solo se anche quella versione e' al sicuro, e ogni riga si controlla sullo SHA-256 della sua versione prima di riscriverla (R3 della 5B resta valida) (`core-backup/.../corrections/DefaultCorrections.kt:134`, `:146-158`, `:237-238`).
   - **D4-D6**: segni di problema e lavori; i file ricevuti ripartono su una rete non a consumo e dietro gli stessi cancelli (`app/.../work/BackupWork.kt`). **D7**: lo spostamento fra share aggiorna il catalogo **senza** la verifica (`app/.../nas/NasOrganizer.kt`, `CatalogMoveCompletions`), quindi un media spostato non risulta piu' "al sicuro" nell'area privata: va nella direzione giusta.
   - Nessuna voce nuova dal gruppo A.

---

## Alta

### R1. Un dispositivo compromesso porta il file dei vettori oltre 2 GiB: ricerca e sincronizzazione rotte su ogni telefono, anche dopo la revoca

- **Gravita':** alta. Un solo dispositivo approvato e compromesso rompe la ricerca per contenuto di **tutta la famiglia**; la revoca non la ripara e serve l'azzeramento di ogni app. Va oltre L45, che parla di dati falsi, non di un blocco che resta.
- **Dove:**
  - `core-search/.../index/VectorFile.kt:126-147` (`slice`): ogni lettura mappa il file **intero**; `:136` `require(size <= Int.MAX_VALUE)`; il file e' in sola aggiunta (`:58-79`, record da 1.584 byte, `:46`);
  - `core-search/.../index/LocalAiIndex.kt:320-345` (`changeLocked`): un corpo diverso della stessa origine aggiunge record nuovi (`sameVectors`, `:471`, evita solo i doppioni identici);
  - `core-trust/.../data/AiSegments.kt:155-156`: un corpo `vec` di sorgente `FRAMES` porta fino a 9 vettori per **qualunque** chiave (il lettore non sa se il media e' un video);
  - `LocalAiIndex.kt:236-262` (`maintain`): la compattazione parte solo con piu' del 20% di record morti e legge con `file.read` (`:248`); la chiama solo la manutenzione notturna (`app/.../work/GalleryWork.kt:142`), e solo se l'indice e' aperto (`app/.../search/SearchGraph.kt:139-140`);
  - `LocalAiIndex.kt:277-300` (`openLocked`): all'apertura si leggono i record; `SearchGraph.kt:120-136`: un'apertura fallita lascia l'indice chiuso per tutto il processo;
  - `core-index/.../ai/AiShareSync.kt:330-339`: i tetti (128 MiB per share e per giro, 2 GiB e 524.288 voci per dispositivo e share) valgono per la replica, non per il file dei vettori.
- **Scenario (a):** il telefono B, compromesso, pubblica nella testa `ai` dell'area comune vettori a 9 fotogrammi per alcune migliaia di chiavi presenti nella share, **e li cambia a ogni giro**. Ogni telefono della famiglia che legge l'area comune scarica fino a 128 MiB per giro e li aggiunge al file; la compattazione notturna non fa in tempo. Bastano una ventina di giri. Anche senza ricambio: circa 155.000 corpi a 9 vettori stanno nei 2 GiB per dispositivo e danno 1,39 milioni di record, circa 2,2 GB. Oltre 2^31 byte ogni lettura lancia `IllegalArgumentException`: Cerca dice "La ricerca non ha funzionato"; al riavvio `index.open()` fallisce e l'indice resta chiuso; `maintain()` non parte (l'indice non e' aperto) e comunque leggerebbe con la stessa `slice`; ogni giro con novita' `ai` fa lanciare la consegna alla ricerca (vedi R2). L'admin revoca B: la replica toglie le voci, ma il file resta oltre 2 GiB e nessuno lo compatta.
- **Confermato:** si', nel codice (tetto, mappatura intera, compattazione legata all'apertura). Quanti giri servono e' un calcolo; prima ancora dei 2 GiB, circa 280 byte di memoria per record nella prima passata fanno temere un `OutOfMemoryError` all'apertura (plausibile).
- **Correzione proposta:**
  1. letture posizionali o mappature a finestre (al piu' 1 GiB), senza un tetto di 2 GiB sul file;
  2. tetti di record per origine della replica e per share (per esempio un multiplo dei media della share), oltre i quali le voci di quell'origine si scartano e si contano;
  3. `FRAMES` accettato solo per una chiave che il catalogo dice video;
  4. compattazione anche per dimensione e subito, non solo di notte, capace di lavorare su un file grande; un'apertura fallita ricostruisce l'indice dal registro e dalla replica invece di restare chiusa;
  5. un test sulla JVM con un file dei vettori sparso oltre 2 GiB e un pubblicatore che ricambia i vettori.

## Media

### R2. Pochi segmenti `ocr` grandi fermano la sincronizzazione dell'indice e il calcolo su tutta la famiglia

- **Gravita':** media. Un blocco che dura finche' il dispositivo non e' revocato, su ogni telefono che legge l'area comune; le miniature delle altre share si fermano con lui.
- **Dove:**
  - `core-index/.../ai/AiShareSync.kt:278` e `:342`: la fusione va a blocchi di 2.000 chiavi; `:307-309`: un solo `sink.apply` per blocco, nessuna cattura, e `commitApplied` solo dopo;
  - `app/.../ai/AiIndexAdapters.kt:124-136` → `LocalAiIndex.kt:173-190` → `:342-343`: il testo normalizzato di **tutto** il blocco va in una sola cornice del registro;
  - `core-search/.../index/OriginLedger.kt:128-139`: `require(payload.size <= MAX_FRAME)` (`:133`), `MAX_FRAME` = 64 MiB (`:305`); un corpo `ocr` arriva a 64 KiB (`core-trust/.../TrustFormat.kt:224`);
  - propagazione: `core-index/.../sync/IndexSyncEngine.kt:130` chiama `ai.afterIndex` senza `try`, e `downloadImages()` (`:135`) viene dopo; `app/.../nas/NasGraph.kt:785-788` (`syncIndex`) neppure; sugli indicizzatori `AiPublishedClasses.kt:36` (`refresh` all'inizio di ogni unita') e `ComputeSession.kt:127-132`, che cattura solo `IOException`.
- **Scenario (a):** B pubblica nell'area comune da 1.100 a 2.000 voci `ocr` da circa 64 KiB (63 righe di una parola da 1.024 byte), con ricetta 255, per chiavi di file presenti (o di suoi record `meta`). Stanno nei 128 MiB di un giro. Alla fusione un blocco supera i 64 MiB di cornice: `IllegalArgumentException` (o prima un `OutOfMemoryError` sui corpi decodificati). Il giro si interrompe, le chiavi restano "da rifondere" e il giro dopo ripete lo stesso errore. Su ogni telefono: si fermano la sincronizzazione delle share dopo quella comune, le miniature (`downloadImages`) e la riproiezione dei dati personali; sugli indicizzatori ogni tappa del calcolo cade all'inizio dell'unita'.
- **Confermato:** si', nel codice; non eseguito.
- **Correzione proposta:**
  1. dividere `applyReplica` e le cornici del registro per **byte** (per esempio 4 MiB), non per numero di chiavi, e togliere il `require` a favore della divisione;
  2. limitare per byte anche i blocchi della fusione;
  3. in `AiShareSync.afterIndex` e `refresh` catturare gli errori che non sono annullamenti, contarli con un codice della Diagnostica e restituire `complete = false`; in `IndexSyncEngine` isolare `ai.afterIndex`, cosi' indice della Fase 4 e miniature vanno avanti;
  4. un test con un pubblicatore ostile da 2.000 corpi da 64 KiB.

### R3. L'indice locale accumula senza tetti testo, vocabolario e righe del viewer di un dispositivo compromesso

- **Gravita':** media. Il commento di `AiShareSync.kt:41` dice "un dispositivo compromesso non riempie il telefono": vale per la replica, non per cio' che l'indice locale ne ricava.
- **Dove:**
  - registro in sola aggiunta con il testo di ogni origine, compattato solo quando i record **dei vettori** morti superano il 20% (`LocalAiIndex.kt:236-243`);
  - un file per chiave nelle righe del viewer (`app/.../search/TextLayouts.kt:31-50`); `ai_text`, FTS4 e vocabolario nel database (`core-database/.../AiIndexCatalog.kt:93-107`, `:193-202`);
  - il vocabolario dei refusi si carica **tutto** in memoria alla prima parola sconosciuta, senza tetto (`core-search/.../index/TextSearch.kt:18-40`, `:141-158`);
  - nessun controllo dello spazio libero prima di applicare la replica.
- **Scenario (a):** B pubblica fino a 2 GiB di corpi `ocr` vincenti (ricetta 255) fatti di parole casuali, oppure ripubblica a ogni giro testi diversi per le stesse chiavi. Su ogni telefono: circa 2 GiB nella replica, altrettanti nel registro, nel database con l'FTS e nei file delle righe, e un vocabolario di decine di milioni di parole. Con il ricambio il registro cresce senza fine (i vettori non cambiano, quindi non si compatta). Alla prima ricerca con un refuso il processo dell'interfaccia carica tutto il vocabolario e va in `OutOfMemoryError`.
- **Confermato:** l'assenza di tetti si', nel codice; le quantita' sono stime. Plausibile anche un picco di memoria alla prima sincronizzazione `ai` di una famiglia gia' indicizzata (fino a 128 MiB di corpi tenuti fino ad `applyHead`, `AiShareSync.kt:208-255`).
- **Correzione proposta:** tetti per origine della replica su byte di testo, voci vincenti e parole nuove del vocabolario; compattazione del registro anche per il testo morto; un tetto al vocabolario dei refusi (le N parole piu' frequenti); controllo dello spazio libero prima di applicare; consegna alla ricerca segmento per segmento.

### R4. Il testo dal vivo e "Testo nella foto" mostrano l'ultimo corpo `ocr` arrivato, anche dopo la revoca di chi l'ha pubblicato

- **Gravita':** media. L45 accetta testo falso finche' il dispositivo e' approvato e promette "La revoca lo toglie"; qui il testo resta dopo la revoca, e prende il posto anche della lettura fatta da questo stesso telefono.
- **Dove:**
  - `app/.../search/TextLayouts.kt:31-76` (`OcrLayoutStore`): un file `<chiave>.ocr` per chiave di contenuto; nell'app ci sono solo chiamate a `put`, `remove` e `clear` non le chiama nessuno;
  - `app/.../ai/AiIndexAdapters.kt:90-95` (`IndexAiResults.saveText`) e `:124-136` (`IndexReplicaSink.apply`, `layouts.put` alla `:130`): scrivono lo stesso file, l'ultimo vince, qualunque sia la share o il dispositivo;
  - `AiIndexAdapters.kt:138-151`: `withdraw`, `forgetDevice` e `forgetShare` toccano solo l'indice, mai le righe del viewer;
  - `TextLayouts.kt:82-94` (`StoredMediaText.layout`): il viewer legge il file per chiave, senza chiedere all'indice quale origine vince;
  - per confronto `core-search/.../index/LocalAiIndex.kt:534-543`: la ricerca preferisce la ricetta piu' alta e, a parita', il calcolo di questo dispositivo, e la revoca toglie le voci della replica (`:201-203`, da `AiShareSync.kt:144`, `:157-163`, `:307-308`).
- **Scenario (a):** B (compromesso, dello stesso account o con la stessa foto nell'area comune) pubblica per la foto di una fattura un corpo `ocr` con un IBAN diverso. Il telefono A aveva letto la foto da se': la ricerca di A tiene il suo testo, ma il file del viewer ora e' quello di B. A apre la foto, "Testo" mostra l'IBAN di B, "Copia tutto" lo copia. L'admin revoca B: la replica toglie le voci di B e la ricerca torna giusta, il file del viewer no. Il calcolo non lo riscrive: il testo di A risulta "salvato", e `ItemWorker.kt:67-75` lo ripubblica senza ricalcolarlo e senza toccare le righe. Il testo di B resta fino all'azzeramento.
- **Confermato:** si', nel codice (anche dal revisore dell'indice). Che un familiare copi un dato dal foglio invece che leggerlo dalla foto e' plausibile.
- **Correzione proposta:**
  1. le righe del viewer seguono la scelta dell'indice: `StoredMediaText` chiede a `LocalAiIndex` quale origine vince per la chiave e legge il corpo di quella origine (righe per origine, o il corpo nel registro con l'origine);
  2. `withdraw`, `forgetDevice` e `forgetShare` tolgono le righe delle origini che spariscono, e un cambio di vincitore riscrive la riga;
  3. un test: testo locale, poi una replica con ricetta piu' alta, poi la revoca: il viewer torna al testo locale.

### R5. Le anteprime di un altro dispositivo diventano risultati "propri" per lo stesso contenuto in ogni share, e la revoca non li toglie

- **Gravita':** media. Va oltre L45 su due punti: il dato derivato dall'anteprima di un altro dispositivo finisce anche nell'area privata della vittima, **firmato dal dispositivo della vittima**, e la revoca non lo toglie. E' lo stesso schema di R1 della 4A (miniature scelte senza share), spostato sui risultati del calcolo.
- **Dove:**
  - `core-ai/.../compute/DiskAiBuffer.kt:124`: il buffer e' per (chiave di contenuto, classe), senza share ne' dispositivo d'origine;
  - `core-index/.../ai/IndexAnalysisFetcher.kt:100-112`: i blob attestati di una share, dal dispositivo con l'id minore in su;
  - `core-ai/.../compute/ItemWorker.kt:109`: il prefetch di una share salta una chiave gia' nel buffer, qualunque share l'abbia portata; `:173`: il buffer viene **prima** dei byte del telefono; `:139` e `:156`: il risultato si salva come calcolo di questo dispositivo (`AiOrigin.Local`);
  - `ItemWorker.kt:67-75` e `:95-102` (`restage`): nella share Y si ripubblica il corpo locale della stessa chiave, firmato da questo dispositivo, senza sapere da quale anteprima e' nato; `app/.../ai/AiIndexAdapters.kt:30-66` (`LocalBodyStore`): nessuna provenienza;
  - `app/.../ai/ViewerPorts.kt:100`: anche la lettura al volo del viewer prende prima l'anteprima del buffer;
  - `app/.../ai/AiCatalogPorts.kt:61-76` (`FileAiUnsupported`) e `ItemWorker.kt:147`, `:162`: un ingresso rifiutato segna la chiave "non supportata" per sempre e in tutte le share;
  - `core-model/.../NasTypes.kt:37-46`: la chiave e' `qh1` piu' dimensione (L32): due file diversi nel mezzo hanno la stessa chiave.
- **Scenario (a), un altro account:** la famiglia si gira le foto con WhatsApp, quindi A e B hanno spesso gli stessi byte. B, compromesso, mette una di quelle foto nell'area comune e, da preparatore, pubblica per quella chiave un'anteprima di analisi diversa (per esempio un documento con un IBAN), con un record `meta` vero: l'attestazione regge. L'indicizzatore A elabora l'area comune: l'anteprima di B finisce nel buffer, A calcola vettore e testo, li salva come propri e li pubblica nell'area comune. Poi elabora la sua area privata, dove ha la stessa foto: la chiave risulta "salvata", e `restage` pubblica vettore e testo nella testa `ai` dell'area privata di A, firmati da A; gli altri dispositivi di A li adottano e il viewer mostra il testo letto dall'immagine di B. Succede anche se A elabora prima la sua area, quando l'anteprima di B e' gia' nel buffer da un giro precedente (il buffer viene prima del telefono e del prefetch). L'admin revoca B: nell'area comune le voci di B spariscono, quelle calcolate da A sull'anteprima di B restano ovunque e nessuno le ricalcola. Variante senza dati falsi: un'anteprima che `ModelInputs` rifiuta toglie quel contenuto dalla ricerca per contenuto di A per sempre, anche per la copia privata e per quella del telefono.
- **Confermato:** si', nel codice (buffer per chiave, origine `Local`, `restage`), anche dal revisore dell'indice. Lo scenario chiede gli stessi byte (o la stessa `qh1`) in due aree: frequente in una famiglia che si gira le foto.
- **Correzione proposta:**
  1. il buffer ricorda share e dispositivo d'origine di ogni blob; per una share si usano solo i blob attestati **in quella share** o i byte del telefono, che vengono prima del buffer quando ci sono;
  2. il corpo locale ricorda da quale anteprima e' nato (share e dispositivo, o "telefono"); `restage` ripubblica in Y solo un corpo nato in Y o dal telefono, altrimenti si calcola per Y;
  3. alla revoca di un dispositivo si tolgono anche i corpi locali nati dalle sue anteprime e i segni "non supportato" che ha causato: tornano da fare;
  4. il segno "non supportato" vale per chiave e share d'origine e scade (per esempio dopo 30 giorni);
  5. in `docs/sicurezza.md` L45 dice che vale per il **contenuto**, anche per le copie dello stesso contenuto nelle aree private (vedi anche R16).

### R6. "Spegni" la lettura del testo non spegne finche' il telefono non ha l'ora del NAS

- **Gravita':** media. E' la promessa che rende accettabile l'eccezione di ML Kit (D41, L49, C21): fuori casa senza VPN fallisce aperta e in silenzio.
- **Dove:**
  - `app/.../ai/TextReading.kt:132-140` (`disable`): segna `disabling`, chiama `onTurnedOff()`, poi `setChoice(Off)`; `:101-105`: lo stato e la memoria locale seguono la voce sincronizzata;
  - `app/.../ai/AiWiring.kt:239`: `setChoice` restituisce falso senza dati personali, e nessuno lo guarda;
  - `core-personal/.../settings/SyncedSearchSettings.kt:35-42`: `textReading` si legge da `store.entries`;
  - `core-personal/.../store/LedgerPersonalStore.kt:70-86`: senza ora del NAS la modifica resta solo "da datare" e lo stato pubblicato non cambia (lo stato con le modifiche da datare, `effectiveNow`, `:264`, non arriva a `entries`);
  - `app/.../nas/NasOrganizer.kt:389`: l'ora manca se l'orologio e' bloccato (D32) o se nel processo non c'e' ancora stato un login SMB (`core-nas/.../session/Clocks.kt:80`).
- **Scenario (d):** fuori casa, sui dati mobili, VPN spenta, app appena aperta. Ricerca e modelli → "Spegni" → conferma. Il motore si rilascia e i lavori di DataTransport si annullano, ma la voce `text_reading` resta "accesa" nello stato: finito `disabling`, lo stato torna `OnForAccount(thisDevice = true)` e `TextReadingMemory` salva di nuovo "accesa". Una pressione prolungata su una foto non letta fa ripartire ML Kit; il calcolo `LOCAL_BUFFER_ONLY` e i media del solo telefono leggono anche di notte; DataTransport ripianifica e spedisce le statistiche, anche sui dati mobili. All'avvio dopo non si pulisce niente: la memoria dice "accesa". La pagina mostra di nuovo "Attiva". Tutto si sistema quando il telefono torna a casa e la modifica prende l'ora del NAS. Variante: se i dati personali non sono pronti, lo spegnimento si perde del tutto.
- **Confermato:** si', nel codice; non eseguito. Lo stesso schema riguarda le altre impostazioni sincronizzate (animazione delle foto in movimento, ordine dei risultati), dove e' solo funzionale.
- **Correzione proposta:**
  1. lo spegnimento vale **subito in locale**: una sovrascrittura "spenta" in `TextReadingMemory` che resta finche' la voce sincronizzata non dice spenta, oppure `SyncedSearchSettings` che legge lo stato con le modifiche da datare (`effectiveNow`);
  2. controllare l'esito di `setChoice` e dirlo all'utente;
  3. un test "Spegni senza ora del NAS" sul grafo vero (stato, memoria, nessuna lettura dopo).

## Bassa

### R7. Uno spegnimento durante una lettura fa ripartire ML Kit dopo l'annullamento dei lavori

- **Gravita':** bassa. Le statistiche riguardano letture fatte da acceso, ma partono dopo lo "Spegni" e prima del riavvio, contro E-A18.
- **Dove:** `core-ai/.../ocr/GatedTextReader.kt:30-44` (lo stato si controlla, poi si prende il motore); `MlKitTextReader.kt:54-75` (`ensureInitialized`, decodifica, poi `recognizerLocked()`), `:77-88` (`release` mette il riconoscitore a null e `recognizerLocked` lo **ricrea**); `TextReadingCleanup.kt:183-190` (`onTurnedOff`: rilascio e un solo annullamento dei lavori; con ML Kit gia' partito la coda resta fino al riavvio); `app/.../ai/TextReading.kt:101-105`, `:132-135` (lo stato e' uno `stateIn`: quando `onTurnedOff` gira puo' dire ancora "attiva").
- **Scenario (d):** "Indicizza ora", oppure una pressione prolungata con lettura al volo, poi subito "Spegni". La lettura in corso supera il cancello, il rilascio arriva fra la decodifica e `recognizerLocked()`, e la lettura ricrea un `TextRecognizer` che nessuno chiudera' piu'. L'inferenza finisce dopo `cancelScheduledJobs()`: un evento di ML Kit scritto dopo l'annullamento ripianifica `JobInfoSchedulerService`, che spedisce mentre il processo vive. `docs/decisioni.md` (P4, "Possibile rinforzo, non fatto") conosceva solo il caso di un invio gia' in corso.
- **Confermato:** la corsa si', nel codice; che ML Kit scriva un evento dopo il rilascio e' plausibile.
- **Correzione proposta:** un `MlKitTextReader` rilasciato non ricrea il riconoscitore (lancia `TEXT_READING_OFF`); un segno "spenta" letto subito dal cancello, messo prima di `onTurnedOff`; il rilascio aspetta (con un tetto) le letture in corso e poi annulla di nuovo i lavori; valutare il rinforzo di `docs/decisioni.md` (disabilitare i componenti di DataTransport allo spegnimento).

### R8. Gli altri dispositivi dell'account avviano ML Kit senza l'avviso promesso

- **Gravita':** bassa. La pagina di attivazione dice "Vale per i dispositivi del tuo account" (`strings_search_activation.xml:16`), quindi il consenso c'e'; manca l'avviso "una volta" di D41 (`docs/fase6-progetto.md:224`) e F69 (`:1332`) non puo' passare.
- **Dove:** `app/.../ai/TextReading.kt:75-78` (l'account acceso vale subito come "attiva qui"); `feature-search/.../models/TextReadingBlock.kt:66-74` (da spenta c'e' solo "Scopri e attiva": non ci si esclude prima); nessun avviso nel codice.
- **Scenario (d):** si attiva la lettura sull'S25; la voce arriva sul Tab S9; li' una pressione prolungata su una foto mai letta (anche solo per ritagliare) legge al volo: ML Kit si inizializza, crea l'identificativo e mette in coda le statistiche prima che chi usa il tablet sappia qualcosa o possa dire "Non su questo dispositivo".
- **Confermato:** si'.
- **Correzione proposta:** su un dispositivo che non ha ancora visto l'attivazione, uno stato "da confermare qui" (non attivo) finche' l'avviso non e' stato mostrato; l'esclusione anche da spenta.

### R9. `PATH_CHECKED`: la cache dei programmi compilati non si verifica ne' si ripara, e una GPU che fa cadere il processo non lascia memoria

- **Gravita':** bassa. Non e' sfruttabile (serve l'UID dell'app), ma e' la parte di D16 che il percorso non copre, e i guasti che ne vengono non si riparano da soli.
- **Dove:** `core-ai/.../runtime/LiteRtLmSessions.kt:192`, `:205-215` (la cartella `cache/<sha256>-<backend>/` passata al motore; con il percorso LiteRT-LM ci salva programmi e pesi preparati: 68 MB per il 270M su CPU, 277 MB per il 440M su CPU); `core-models/.../store/ModelStore.kt:82-93`, `:151-162` (la cache si toglie solo con un rifiuto, una rimozione o un modello non piu' elencato); `LiteRtLmImageEmbedder.kt:61`, `:103-127` (il ripiego su CPU vale solo in memoria); `compute/MediaComputer.kt:100` (`INIT_FAILED` diventa "modello mancante" e il calcolo si ferma, `ComputeSession.kt:219`).
- **Scenario (g):** il processo muore (memoria, Android 17) mentre il motore scrive per la prima volta i 277 MB della cache del 440M su CPU. Se LiteRT-LM non riconosce la cache troncata, ogni apertura fallisce (`AI_ENGINE_INIT_FAILED`, la pagina dice che il modello manca) o il processo cade nel codice nativo a ogni tappa; nessuno toglie la cache, il file del modello e' buono e la verifica passa. Allo stesso modo una GPU che fa cadere il processo (driver, memoria) invece di lanciare: al processo dopo si riprova la GPU, e il calcolo muore a ogni tappa senza mai segnare `AI_GPU_FALLBACK`.
- **Confermato:** il codice si'; il comportamento di LiteRT-LM con una cache rovinata e quello del driver sono plausibili.
- **Correzione proposta:** un segno su disco "cache completa" scritto dopo la prima inizializzazione riuscita (e un "GPU in prova" tolto dopo il primo vettore): se all'avvio manca, si toglie `cache/<sha256>-<backend>/` e si usa la CPU; si toglie la cache anche a ogni `INIT_FAILED`; in C22 una riga che dice che con `PATH_CHECKED` l'impronta copre il file del modello, non la cache (I1).

### R10. Senza l'ora del NAS la quarantena delle sorgenti dei modelli lancia e non vale

- **Gravita':** bassa. Nessun file sbagliato si accetta; si riscaricano 157-370 MB a ogni giro e lo scaricamento in background si blocca.
- **Dove:** `core-models/.../LocalModels.kt:71` e `app/.../ai/AiWiring.kt:199` (l'orologio e' l'ora del NAS); `core-nas/.../session/Clocks.kt:82` (senza ancora `nowMillis()` lancia); `core-models/.../manager/SourceStrikes.kt:29-57` (`isQuarantined` e `strike` lo chiamano fuori da ogni `try`); `LocalModelManager.kt:85` (si cattura solo l'annullamento), `:139`, `:275-280`; `app/.../work/AiWork.kt:186-199` (`ModelFetchWorker` non apre la sessione del NAS e non isola i modelli).
- **Scenario (b):** fuori casa senza VPN, o in un processo che non ha ancora fatto un login SMB, Hugging Face da' un file sbagliato o redirige fuori elenco: `rejected()` butta il parziale e `strike()` lancia. Nessun errore registrato, la sorgente dopo non si prova, lo stato resta a meta', il lavoro cade e salta gli altri modelli; al giro dopo si riscarica tutto. Se un errore era gia' registrato, ogni giro automatico senza ancora lancia in `isQuarantined` prima di provare qualunque sorgente.
- **Confermato:** si'.
- **Correzione proposta:** `SourceStrikes` riceve un orologio che puo' dire "non so" (`nowMillisOrNull`): con l'ora ignota `strike` ripiega in memoria sull'orologio monotono e `isQuarantined` risponde prudente senza togliere la voce; `ensure` cattura gli errori che non sono annullamenti; `ModelFetchWorker` isola ogni modello.

### R11. Le sorgenti NAS dei modelli non passano dal cancello della rete a consumo

- **Gravita':** bassa oggi (vale solo per la cartella `MyCloud Gallery/Modelli/`), media quando lo scaffale si accende (le copie sul NAS vengono prima di HTTPS).
- **Dove:** `core-models/.../manager/LocalModelManager.kt:213-221` (il cancello si legge solo per `ModelSource.Https`); `core-network/.../policy/NetworkPolicy.kt:155-157` (il NAS via VPN e' ammesso anche in roaming e sui dati mobili, giusto per guardare le foto); `app/.../work/AiWork.kt:80-87` (`fetchNow` senza vincoli di rete).
- **Scenario (b):** fuori casa sui dati mobili o in roaming, con la VPN di casa accesa. "Scarica" in Cerca: HTTPS chiede una conferma che non arriva mai (I4) e si passa alla cartella dal PC: se il file c'e', 157 MB via VPN sui dati mobili, anche in roaming. Accendere il ruolo Indicizzatore fa lo stesso con 370 MB, in background. Contro §2.1 ("Roaming: mai, nemmeno con Scarica ora") e la riga `INTERNET` del §4-bis.
- **Confermato:** si'.
- **Correzione proposta:** il cancello vale per ogni sorgente tranne l'APK; roaming sempre rifiutato; i dati mobili via VPN come HTTPS sui dati mobili; `fetchNow` con `NetworkType.UNMETERED` o dalle regole.

### R12. Il cancello HTTPS guarda la rete scelta dalla policy, non quella del socket

- **Gravita':** bassa.
- **Dove:** `core-models/.../download/https/HttpsConnections.kt:18-21` (`URL.openConnection` sulla rete predefinita, senza legarsi a una `Network`); `app/.../ai/ModelPorts.kt:16-24`, `:36-47` (cancello e registro del traffico leggono `snapshot.underlying`).
- **Scenario (b):** a casa, Wi-Fi riconosciuto e validato, ma One UI ha reso predefiniti i dati mobili ("Passa ai dati mobili" con un Wi-Fi lento). Il cancello dice `Allowed` e la connessione esce sui dati mobili: fino a 370 MB senza conferma, registrati come Wi-Fi di casa. Il lavoro periodico e' protetto dal vincolo `UNMETERED` di WorkManager; "Scarica" e il cambio di ruolo no.
- **Confermato:** plausibile (dipende dal sistema).
- **Correzione proposta:** aprire la connessione sulla `Network` approvata (`Network.openConnection`, da una porta di `:app`), oppure controllare anche la rete attiva (non a consumo, non in roaming, validata) a ogni fetta; registrare il traffico sulla rete del socket.

### R13. Scaricamento dei modelli: parziali buttati, colpa alla sorgente sbagliata, riprese che ripartono da zero

- **Gravita':** bassa. Nessun file sbagliato si accetta (l'impronta finale lo ferma): si sprecano traffico e tempo e si mette in quarantena una sorgente buona.
- **Dove e scenari (b):**
  - un `Mismatch` scoperto **prima di leggere** (dimensione del file sul NAS o `Content-Length`) butta il parziale scritto da un'altra sorgente: `LocalModelManager.kt:235`, `:275-276`, `source/NasModelFetcher.kt:95`, `:121`. Un file della dimensione sbagliata in `Modelli/` e un HTTPS interrotto fanno ripartire da zero a ogni giro;
  - l'errore va all'**ultima** sorgente che ha scritto (`store/ModelStore.kt:112-120` ricorda solo il tipo): byte sbagliati letti a meta' dalla cartella dal PC, poi completati da HTTPS, mettono in quarantena Hugging Face;
  - dopo un annullamento il lavoro HTTPS "staccato" continua (`HttpsModelFetcher.kt:84-100` non lo aspetta) e puo' troncare il parziale che un secondo scaricamento sta allungando (nessun `ensureActive` fra `responseCode` e `PartialFile.open`, `:121-160`, `:205-215`); "Scarica" premuto due volte basta;
  - con fette di 30 s (`contract/ModelPorts.kt:34`) un CDN che cambia ETag fra le richieste da' sempre `200`: ogni fetta riparte da zero e il 440M non finisce mai sotto i 13 MB/s circa (`HttpsModelFetcher.kt:132-139`).
- **Confermato:** la logica si'; i casi sul campo plausibili (F48).
- **Correzione proposta:** la nota `.src` tiene ogni sorgente che ha scritto; l'errore si registra solo se una sola sorgente ha scritto tutto, altrimenti si riprova da zero una volta senza colpa; un `Mismatch` prima di leggere non butta il parziale; `fetch` aspetta (non annullabile, con un tetto) la fine del lavoro staccato; dopo un `200` in ripresa niente piu' `If-Range` per quel parziale, e le ripartenze contano come stallo.

### R14. I segmenti `ai` propri non si tolgono mai

- **Gravita':** bassa. Privacy (oltre L46, che parla del testo dei file che la share contiene) e funzionamento a lungo termine.
- **Dove:** `core-index/.../ai/OutboxAiIndexPublisher.kt:231-234` (la testa elenca tutti i segmenti propri; oltre 8.192 la pubblicazione fallisce per sempre); il formato `ai` non ha lapidi e `OwnFilesCleaner` non tocca i segmenti (`docs/decisioni.md`, "Fase 6 P2", punti aperti); `app/.../ai/AiGraph.kt:216-237` (ogni lettura al volo, al piu' ogni 5 minuti per share, fa un segmento).
- **Scenario (a/d):** un documento con dati sensibili sta per un giorno nell'area comune, viene letto e poi tolto. Il suo testo resta in chiaro in `devices/<id>/ai/seg/` del dispositivo che l'ha letto, leggibile via SMB da ogni account, per sempre (i lettori lo ignorano: niente record presente, ma i byte ci sono). Con un uso normale del testo dal vivo e qualche ricalcolo, in un anno o due si arriva a 8.192 segmenti e la testa `ai` di quella share non si pubblica piu'.
- **Confermato:** si'; il punto aperto era noto, l'effetto sulla privacy no.
- **Correzione proposta:** compattare i propri segmenti tenendo solo le voci vive e ancora di file della share (M3 al momento della compattazione), con la regola E-I17 per i lettori; dirlo in L46 finche' non c'e'.

### R15. La riparazione dell'indice non riconsegna i dati della replica

- **Gravita':** bassa. Guasto (g): un record dei vettori rovinato o una cornice rotta del registro tolgono i dati degli altri dispositivi e non tornano.
- **Dove:** `app/.../ai/AiGraph.kt:173-182` (`redeliver` e `redeliverAll` segnano le chiavi "da rifondere"); `core-index/.../ai/AiShareSync.kt:291-296` (se il vincitore e' quello gia' consegnato, `mark == old`, si passa oltre senza `sink.apply`); `core-search/.../index/LocalAiIndex.kt:213-230`.
- **Scenario (g):** un record con il CRC sbagliato su un tablet che non indicizza: l'indice lo toglie e chiede la riconsegna, la fusione trova lo stesso vincitore e non consegna niente. Quel media resta fuori dalla ricerca per contenuto finche' il vincitore non cambia, forse mai.
- **Confermato:** si'.
- **Correzione proposta:** `redeliver` toglie anche il segno "consegnato" (`ai_replica_applied`) di quelle chiavi, oppure la fusione forza `apply` per le chiavi chieste dalla riparazione.

### R16. La ricetta fino a 255 vince sempre, anche sul calcolo locale

- **Gravita':** bassa. Quasi tutto dentro L45, ma devia da E-I20 ("ricetta sconosciuta: segmento rifiutato", `docs/fase6-progetto.md:257`).
- **Dove:** `core-trust/.../data/AiSegments.kt:152`, `:222` e `AiBodies.kt:50` (si accetta da 1 a 255; oggi la ricetta e' 1); `core-search/.../index/LocalAiIndex.kt:542-543` (prima la ricetta, poi il calcolo locale); `core-index/.../ai/AiPublishedClasses.kt:45`, `:60` e `app/.../ai/AiCatalogPorts.kt:143-144` (ricetta maggiore o uguale = "fatto").
- **Impatto:** un dispositivo compromesso vince la fusione D7 senza bisogno dell'id minore, batte il risultato di questo telefono nella ricerca e nel viewer (R4), e il contenuto risulta "fatto" per tutti, anche dopo un cambio di ricetta legittimo. Vale per chiave di contenuto in ogni share (R5).
- **Confermato:** si'.
- **Correzione proposta:** rifiutare le ricette oltre la massima nota all'app (`AiRecipe`) piu' un piccolo margine, o trattarle come pari; a parita' preferire il calcolo locale.

### R17. La selezione di sistema nel foglio "Testo nella foto" copia senza `EXTRA_IS_SENSITIVE`

- **Gravita':** bassa (c).
- **Dove:** `feature-viewer/.../livetext/LiveTextSheet.kt:43-49` (`SelectionContainer`); nessun `LocalClipboardManager` sostituito nel viewer.
- **Scenario (c):** nel foglio si seleziona una parte del testo (un codice, una password fotografata) e si usa "Copia" del menu di sistema: il `ClipData` non e' sensibile, quindi anteprima negli appunti e cronologia della tastiera (Gboard, Samsung Keyboard). "Copia" e "Copia tutto" dell'app invece lo sono.
- **Confermato:** si'.
- **Correzione proposta:** nel foglio un `LocalClipboardManager` che segna il clip come sensibile, oppure niente selezione di sistema e solo "Copia tutto".

### R18. Il PNG del ritaglio copiato o condiviso resta leggibile fino al prossimo avvio

- **Gravita':** bassa (c). Deroga a C17 ("permessi revocati dopo l'uso").
- **Dove:** `app/.../ai/CutoutWiring.kt:219-244` (`CutoutFiles`: un file nuovo in `cacheDir/share/<token>/` a ogni Copia o Condividi, tolto solo dalla pulizia all'avvio); `feature-viewer/.../cutout/AndroidCutoutSaver.kt:71-88`; per confronto le altre condivisioni usano `ShareLease` e `ShareHolder` (`app/.../integrations/AppShares.kt:90`).
- **Scenario (c):** un ritaglio dai Nascosti copiato negli appunti: finche' il processo vive (anche giorni) qualunque app in primo piano o tastiera che legge gli appunti riceve il permesso di leggere il PNG; ogni Copia o Condividi lascia un PNG in piu', anche di decine di MB.
- **Confermato:** si'.
- **Correzione proposta:** per Condividi, `ShareLease` e `ShareHolder` come le altre condivisioni; per Copia un solo file "appunti" che si sostituisce alla copia successiva e scade.

### R19. Un ritaglio dai Nascosti non avvia la sincronizzazione del segno "nascosto"

- **Gravita':** bassa. Riguarda i propri dispositivi.
- **Dove:** `core-mediastore/.../write/CutoutPhoneSaver.kt:124-148` (`record`, poi `setPersonalFlags` in una seconda transazione, senza `personalChanged()`); per confronto `app/.../gallery/GalleryGraph.kt:487-491`; sincronizzazione periodica dei dati personali ogni 6 ore (`app/.../work/NasWork.kt:88`).
- **Scenario:** la sorgente "Ritagli" del backup e' accesa di serie, quindi il ritaglio va nell'area privata; sugli altri dispositivi dello stesso account compare in Libreria, in cima (ha la data di adesso), finche' il giro dei dati personali non porta il segno, fino a 6 ore. Fra le due transazioni il media e' anche, per un attimo, visibile nell'istantanea della Libreria (deviazione minima da E-H1).
- **Confermato:** si'; i tempi sul campo sono plausibili.
- **Correzione proposta:** registrare il media gia' nascosto in una sola transazione e chiamare `personalChanged()` dopo un salvataggio dai Nascosti.

### R20. La memoria esaurita nel ritaglio fa cadere l'app

- **Gravita':** bassa (g).
- **Dove:** `app/.../ai/CutoutWiring.kt:180-189` (`mask.alpha()` e `ViewerMask`: due copie da circa 12,6 MB per un'immagine di 4096 px, fuori dal `try`), `:211-216` (un'altra copia per il PNG); `feature-viewer/.../cutout/CutoutController.kt:173`, `:239`, `:262`, `:284` (si catturano solo le `Exception`).
- **Scenario (g):** ritaglio da una foto da 200 MP o da un panorama, sul Tab S9 con molte app aperte, poi Copia e Salva: un `OutOfMemoryError` chiude l'app invece di dare `CUTOUT_FAILED`.
- **Confermato:** plausibile.
- **Correzione proposta:** catturare `OutOfMemoryError` in quei punti e togliere le copie (usare la maschera senza copiarla).

### R21. Corsa fra mappa e dimensione nel file dei vettori

- **Gravita':** bassa (g).
- **Dove:** `core-search/.../index/VectorFile.kt:51-53` (due campi `@Volatile` scritti separatamente), `:126-147` (`slice` legge la mappa e poi la dimensione senza lucchetto).
- **Scenario:** mentre la sincronizzazione aggiunge record e una ricerca rimappa il file, un'altra ricerca prende la mappa vecchia e la dimensione nuova: `position(offset)` lancia `IllegalArgumentException` e la ricerca fallisce.
- **Confermato:** plausibile.
- **Correzione proposta:** un solo oggetto immutabile con mappa e dimensione, o il confronto con `capacity()` della mappa presa.

---

## Informative

- **I1. `PATH_CHECKED` e i documenti.**
  - Il file del modello nasce 0600 e non si rende mai in sola lettura (`core-models/.../download/PartialFile.kt:58`, `store/ModelStore.kt:137-145`; `setReadOnly` solo in un test), contro D16 e il KDoc di `ModelAccess` (`core-ai/.../runtime/LiteRtLmSessions.kt:72`). Non cambia la sicurezza (il proprietario puo' sempre rimettere la scrittura), ma una scrittura per sbaglio dell'app sul file verificato oggi non si vede.
  - `VerifiedModel` non porta lo `stat` preso durante la verifica: `PATH_CHECKED` fa `fstat` alla creazione della sessione. Come difesa in profondita', `ModelVerifier` puo' registrare dispositivo, inode, dimensione e `ctime` sul descrittore e `PATH_CHECKED` confrontarli prima e dopo l'inizializzazione: la `ctime` cambia a ogni scrittura e scopre anche le modifiche sullo stesso inode.
  - `docs/sicurezza.md` C22 e la checklist dicono "sullo stesso descrittore che si passa al motore": va scritta la deviazione (il motore riceve il percorso, confrontato con il descrittore verificato; la cache dei programmi non e' coperta, R9).
- **I2. Variante di ML Kit che passa da Play services nel grafo.** `com.google.android.gms:play-services-mlkit-text-recognition` 19.0.1 e' in `gradle/verification-metadata.xml:4141` (arrivata con P0, insieme a `text-recognition-bundled-common`). D12 dice "versione integrata, niente Play services". Va controllato sull'APK di release (`aapt2 dump`, `dexdump`) se ci sono le sue classi o la voce `com.google.mlkit.vision.DEPENDENCIES` nel manifest: se il motore "sottile" venisse scelto, il modello si scaricherebbe tramite Play services, fuori dalla cattura del traffico dell'app (F56).
- **I3. "HTTPS solo da `download/https/`": due buchi nella copertura.** Il controllo sul dex guarda solo i chiamanti `Ldev/pampa/` e tre bersagli (`tools/check.ps1`, passo 7e): non vede `URL.openStream`, le altre `URLConnection`, `Network.openConnection`, `DownloadManager`, `WebView`, `HttpEngine`; `Phase6ArchitectureTest` non guarda i moduli dell'engine (`engine-foundation`, `engine-ui`). Oggi nessuno di questi fa rete.
- **I4. "Scarica ora" sui dati mobili non esiste.** `mobileConfirmed` non diventa mai vero (`app/.../ai/AiWiring.kt:185`, `work/AiWork.kt:202`): fallisce chiuso, ma §2.1 e §4-bis lo promettono. "Scarica" e il cambio di ruolo girano nel `viewModelScope` della pagina, non come lavoro accelerato (D17): uscire dalla pagina li ferma (e alimenta R13). `ModelFetchWorker` da' 8 minuti per modello in fila, oltre i circa 10 minuti di un lavoro.
- **I5. Scaffale dei modelli, quando si accendera'.** `NasModelShelf` (`core-models/.../shelf/NasModelShelf.kt:171-190`) prende copie da qualunque cartella con la forma di un id (fino a 256), non solo dai dispositivi dell'elenco firmato, e le prova tutte prima di HTTPS: chi scrive in `.mycloudgallery/devices/` puo' tenere "Scarica ora" oltre il budget. Prima di accenderlo: solo dispositivi approvati e non dismessi, al piu' 2-3 copie, HTTPS dopo il primo errore sul NAS (insieme a R11 e R13).
- **I6. Il ritaglio dai Nascosti e' nascosto solo nell'app (L2).** Nel MediaStore e' una foto pubblica: la vedono Samsung Galleria e le sincronizzazioni cloud delle altre app. Per un nascosto che sta solo sul NAS e' la prima copia sul telefono, e il nome viene dal file d'origine (`CutoutPhoneSaver.kt:49-54`: "Passaporto-ritaglio.png"). Proposta: dai Nascosti un avviso nel foglio e il nome generico "Ritaglio.png".
- **I7. La versione del consenso non ferma la lettura.** `TextReadingMemory.stateOf` (`app/.../ai/TextReading.kt:75-78`) non guarda la versione accettata: con una versione nuova del testo la lettura resta attiva e la pagina mostra solo un avviso. Oggi la versione e' 1.
- **I8. Uno spegnimento fatto da un altro dispositivo vale solo dopo la sincronizzazione dei dati personali.** Fuori casa il telefono non la vede e continua a leggere (lettura al volo, calcolo `LOCAL_BUFFER_ONLY` e dei media del solo telefono). E' nel disegno (impostazione per account), ma va detto nella pagina e in L49.
- **I9. Foto in movimento: la rete si controlla una volta.** `video/NasMotionSource.kt:115-160` decide all'inizio della lettura: un passaggio dal Wi-Fi ai dati mobili con la VPN accesa a meta' animazione puo' portare fino a 16 MB senza conferma.
- **I10. Area privata e attestazione.** Le teste `ai` di un'area privata non controllano che chi firma sia dell'account proprietario (`core-trust/.../data/DeviceHead.kt:171-174` lo fa solo per `PERSONAL`): oggi lo garantiscono i permessi SMB, va aggiunto come difesa in profondita' (D42: "dello stesso account per l'area privata"). L'attestazione non ferma un dispositivo compromesso che pubblica record `meta` per chiavi qualunque: e' coerente con D7 e L45, ma va saputo leggendo R2, R3 e R5.
- **I11. Debug: `noBackupFilesDir/eval/` non si toglie con l'azzeramento.** Lo strumento di valutazione (`app/src/debug/.../eval/EvalStore.kt:82-83`) tiene li' etichette e query del set privato; `LocalData.kt` non lo elenca. Solo nelle build di debug, che pero' girano sul telefono vero durante le prove.
- **I12. Testo dal vivo nei Nascosti.** D29 dice che nel viewer dei Nascosti testo dal vivo e ritaglio funzionano; `StoredMediaText` e la lettura al volo passano da `cellContentKeysOf`, che esclude nascosti e membri di gruppo (`CellContentDao.kt:51-52`): li' niente testo dal vivo. Funzionale, da vedere in F65.
- **I13. Minori.** Il `User-Agent` fisso "MyCloudGallery" dice a Hugging Face quale app scarica (L51, voluto). Le righe tolte della cronologia possono restare nelle pagine libere del database privato fino all'azzeramento. L'interruttore della lettura non si ferma prima dell'azzeramento: una sua emissione tardiva puo' riscrivere `text-reading` (finestra stretta). Un nome di share scritto con maiuscole diverse da due chiamanti lascerebbe origini doppie nell'indice locale (`LocalAiIndex.kt:178-196` confronta il nome esatto, la replica usa il minuscolo): oggi i chiamanti usano lo stesso nome.

---

## Cose fatte bene

- **Modelli**: impronta e dimensione fissate nell'app e verificate a **ogni** apertura sullo stesso descrittore; nessun manifest che possa cambiarle; host ammessi con suffissi controllati bene, redirezioni a mano, `206` controllato al byte, mai un byte oltre la dimensione, niente cookie ne' compressione; promozione atomica dopo la verifica; il modello del ritaglio nell'APK con la sua impronta, provata in build.
- **`PATH_CHECKED`**: la scelta per le prestazioni e' stata misurata e scritta, e la difesa (dispositivo, inode e dimensione prima e dopo l'inizializzazione, descrittore tenuto aperto) chiude la sostituzione del file, l'unica via realistica nel modello; `FdOwnership` evita di chiudere un descrittore riusato.
- **D5 rispettato ovunque**: a LiteRT-LM e a ML Kit arrivano solo JPEG nostri, dopo un controllo delle intestazioni che non decodifica (22 ingressi ostili, 0 decodifiche; 30.000 mutazioni senza eccezioni); al ritaglio solo pixel della piattaforma con i tetti. Nessun byte del NAS o di altre app ai decodificatori nativi: e' anche il motivo per cui un'anteprima ostile non puo' far cadere il motore (resta il problema di fiducia di R5).
- **Codec `ai`**: tetti per ogni campo, lunghezze controllate prima di allocare, non finiti e norme fuori tetto rifiutati, testo in UTF-8 valido, NFC, senza controlli ne' direzione, parole coerenti con il testo; versione 2 che le app vecchie non aprono; attestazione per share e chiave intera riattestata a ogni giro; M3 due volte.
- **F4-D31 anche per i dati AI**: le voci restano in attesa con il corpo gia' calcolato e si pubblicano solo quando la share si apre; la lettura al volo passa dallo stesso pubblicatore; i media del solo telefono non arrivano mai al NAS.
- **ML Kit da spento**: `MlKitInitProvider` tolto e controllato sull'APK, un solo cancello, `MlKitTextReader` costruibile solo dietro il cancello, pulizia di DataTransport prima di Hilt con "nel dubbio spenta", un test strumentato che conta le classi caricate.
- **Ricerca**: FTS4 sempre letterale, nascosti esclusi in tre punti (candidati, filtro finale, risultati vivi) e da luoghi, suggerimenti e "Trova simili"; nessuna rete; cronologia solo locale con tetto.
- **Ritagli**: PNG costruito da zero (solo `IHDR`, `IDAT`, `IEND`), salvataggio con `IS_PENDING` e segno "nascosto" prima che la riga sia visibile, righe orfane tolte all'avvio, appunti sensibili.
- **Release**: solo `arm64-v8a` (meno codice nativo installato), `debuggable false`, `-dontobfuscate` per controlli del dex affidabili, `ai-delivery` (alpha, con componenti esportati) e `kotlin-reflect` esclusi, `uses-native-library` filtrate e controllate, elenco chiuso dei componenti di Google, strumento di valutazione solo in debug e cercato nel dex.
- **Gruppo A**: correzioni piccole e mirate; D3 estende la regola R3 della 5B (ogni riga del telefono si controlla sullo SHA-256 della sua versione) invece di aggirarla, D7 toglie un "al sicuro" sbagliato.

## Da provare sul campo

Solo con l'account e le share di prova (`mcg-prova`/`mcg-campo`, D13), sull'S25 e sul Tab S9.
- **R1, R2, R3** (con un pubblicatore ostile di debug, mai sulle share vere):
  - 2.000 corpi `ocr` da 64 KiB con ricetta 255 nell'area comune di prova: esito della sincronizzazione su un telefono lettore e su un indicizzatore, miniature delle altre share, `ApplicationExitInfo`, Diagnostica;
  - vettori a 9 fotogrammi ricambiati a ogni giro per un giorno: dimensione di `noBackupFilesDir/search/` (vettori, `ledger.log`, `ocr-layout`), memoria all'apertura dell'indice, comportamento oltre 2 GiB (dopo la correzione: niente oltre il tetto);
  - una prima sincronizzazione `ai` sul tablet con l'indice della famiglia gia' grande (memoria).
- **R4**: testo pubblicato da un dispositivo di prova B, visto nel viewer di A; revocare B: il pulsante "Testo" e il foglio devono tornare al testo letto da A (oggi atteso: resta quello di B).
- **R5**: la stessa foto nell'area comune di prova e nell'area privata di prova; un'anteprima `analysis` diversa pubblicata da un dispositivo con l'id minore; dopo il calcolo, che cosa pubblica A nell'area privata e che cosa resta dopo la revoca.
- **R6**: S25 sui dati mobili, VPN spenta, app appena riavviata: "Spegni", poi controllare lo stato della pagina, una pressione prolungata su una foto non letta, `adb shell dumpsys jobscheduler` (lavori di `JobInfoSchedulerService` del pacchetto) e `run-as` su `shared_prefs/com.google.mlkit.internal.xml`.
- **R7**: "Indicizza ora" con la lettura attiva e subito "Spegni"; dopo 10-60 secondi cercare lavori di DataTransport e traffico verso i server di Google (contatori di rete o una cattura, come F56).
- **R8 (F69)**: attivare sull'S25; sul Tab S9 controllare che compaia l'avviso prima di qualunque lettura, e che "Non su questo dispositivo" si possa scegliere prima.
- **R9**: `am kill` durante la prima apertura del 440M su CPU (ripiego forzato); alla tappa dopo il motore deve partire o la cache deve sparire, mai "modello mancante" con il file buono.
- **R10-R13 (F48)**: scaricamento reale del 270M con le redirezioni vere (catena, ETag stabile fra le fette, `identity` rispettato, byte del registro uguali all'"Utilizzo dati" di Android); "Scarica" premuto due volte; fuori casa senza VPN con un file sbagliato forzato da un catalogo di prova in debug (`ensure` non deve lanciare); sui dati mobili con la VPN accesa e il 270M in `Modelli/` (niente letture dal NAS); a casa con "Passa ai dati mobili" attivo e Wi-Fi lento (niente traffico sui dati mobili).
- **R17, R18**: selezione di sistema nel foglio "Testo nella foto" → Copia, e confronto con "Copia tutto" nell'anteprima degli appunti e nella cronologia di Gboard e di Samsung Keyboard; Condividi un ritaglio verso WhatsApp, poi `run-as ls cache/share`.
- **R19, I6**: "Salva come nuova foto" dai Nascosti; sul secondo dispositivo dello stesso account controllare se il ritaglio compare in Libreria prima della sincronizzazione dei dati personali; controllare che Samsung Galleria lo veda (atteso, L2).
- **R20**: ritaglio da una foto da 200 MP e da un panorama sul Tab S9 con molte app aperte, poi Copia e Salva ripetuti.
- **I2**: `aapt2 dump xmltree` del manifest e `dexdump` dell'APK di release: niente classi di `play-services-mlkit-text-recognition` e nessuna voce `com.google.mlkit.vision.DEPENDENCIES`; con la lettura attiva nessuno scaricamento di moduli OCR da Play services.
- **E-A11/F47**: un byte cambiato in `models/<sha256>` con `run-as` (debug): rifiutato alla prossima apertura, cache tolta, riscaricato; tempo della verifica di 370 MiB a ogni apertura.
- **Il resto, come previsto**: F46-F71 di `docs/fase6-progetto.md` §5.6, in particolare F55 e F56 (catture del traffico durante ricerche e OCR, con la lettura spenta, accesa e di nuovo spenta, dopo un riavvio) e F67 (ritaglio e testo dal vivo senza traffico).

## Checklist della Fase 6 (`docs/sicurezza.md` §7, "Fase 6")

- [ ] **Modello alterato di un byte rifiutato; download interrotto che riprende.**
  - Regge in lettura (risposta 1): verifica a ogni apertura, file tolto con la sua cache, ripresa a blocchi con `Range` e `If-Range`.
  - Restano R10 e R13 (quarantena e riprese); manca F47 e F48.
- [ ] **Manifest remoto manomesso.** Rimandato alla Fase 10 (risposta Q2): nessun manifest remoto nel codice (confermato).
- [ ] **Nessun traffico di rete durante le ricerche. † Nessun host imprevisto durante OCR e indicizzazione.**
  - Regge in lettura: nessun codice di rete nei moduli della ricerca, miniature solo locali, luoghi offline.
  - Mancano le catture F55 e F56, e il controllo di I2 sull'APK.
- [ ] **Cronologia delle ricerche solo locale e cancellabile. Nascosti mai nei risultati.**
  - Regge in lettura (risposte 4 e 5). Resta I13 (pagine libere del database) e F58.
- [ ] **Verifica di dimensione e SHA-256 prima di ogni caricamento, sullo stesso descrittore.**
  - Regge per il file del modello, con la deviazione di `PATH_CHECKED` (risposta 2): la riga va riscritta come in I1.
  - Non copre la cache dei programmi compilati (R9).
- [ ] **Solo `core-models/.../download/https/` apre connessioni HTTPS, verso host ammessi, senza cookie, parametri ne' identificativi.**
  - Regge in lettura; due buchi nel controllo (I3); il `User-Agent` fisso e' voluto.
  - Il cancello della rete non segue il socket (R12) e non vale per le sorgenti NAS (R11).
- [ ] **Lettura del testo spenta di default; spenta, ML Kit non si inizializza e nulla esce; accesa solo dopo la pagina; spegnimento che ferma tutto, anche dopo il riavvio (F69).**
  - Regge da spenta e all'avvio.
  - **Non regge allo spegnimento**: R6 (fuori casa), R7 (letture in corso); l'avviso sugli altri dispositivi manca (R8); I7, I8.
- [ ] **Ritaglio e testo dal vivo senza traffico; ritagli PNG senza metadati; appunti sensibili; un ritaglio dai Nascosti nasce nascosto.**
  - Regge in lettura per PNG, appunti dell'app e segno "nascosto" nel catalogo.
  - Restano R17 (selezione di sistema), R18 (file e permesso fino al riavvio), R19 (segno non sincronizzato subito), I6; manca F67.
- [ ] **Foto in movimento solo-NAS: nessun byte sui dati mobili senza conferma (F71).**
  - Regge in lettura (animazione solo con la rete aperta, badge con conferma). Resta I9; manca F71.
- [ ] **Segmenti `ai` ostili rifiutati senza crash; dati di dispositivi revocati ignorati.**
  - Il codec regge; **non regge a valle**: R1, R2, R3 (blocchi e memoria), R4 e R5 (dati che restano dopo la revoca), R16.
- [ ] **Nessun byte letto dal NAS o da altre app arriva ai decodificatori nativi dei motori.**
  - Confermato in lettura (D5, risposta 6). Il problema di R5 e' di fiducia, non di decodifica.
- [ ] **Nascosti mai in risultati, suggerimenti, completamenti, luoghi, conteggi, "Trova simili" (F58).**
  - Confermato in lettura (risposta 4); manca F58.
- [ ] **Log e Diagnostica senza query, testo letto, luoghi, vettori, chiavi di contenuto.**
  - Confermato in lettura (risposta 5).

Delle voci "per ogni fase, sempre":
- nessun permesso Android nuovo e nessun componente esportato nuovo (confermato); dipendenze nuove con checksum (confermato, con I2 da guardare sull'APK);
- un host nuovo (Hugging Face), solo da `core-models` (confermato, con I3);
- questa revisione vale come passo di `security-review` della Fase 6 e del gruppo A delle correzioni dal campo. Insieme alla revisione funzionale (`docs/review/funzionale-6.md`).

**Prima delle prove sul telefono** si propone di correggere almeno R1, R2 e R6 (e, se si fa la prova F69, R7 e R8): R1 e R2 perche' le prove con due dispositivi e un pubblicatore di prova li toccano, R6 perche' le prove F56 e F69 lo incontrano subito fuori casa. R4 e R5 cambiano il modo in cui il viewer e il calcolo tengono i dati: meglio prima di F65, per non provarli due volte.
