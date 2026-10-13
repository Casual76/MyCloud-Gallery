# Revisione di sicurezza della Fase 3 (parte 1: moduli P2–P10)

| | |
|---|---|
| **Oggetto** | i moduli della Fase 3 gia' committati: `core-mediastore` (P2), `core-metadata` (P3), `core-places` (P4), `core-library` (P5), `core-imaging` (P6), `feature-library` (P7, P10), `feature-viewer` (P8), `core-designsystem` (grid/viewer). **Fuori perimetro:** l'integrazione in `:app` (P11, in corso), che si rivede come delta a parte (vedi in fondo). |
| **Riferimenti** | `docs/sicurezza.md` (§4 C9/C14/C17/C18/C21, §6, §4-bis), `docs/fase3-progetto.md` (§0 D5–D24, §2, §7), `docs/review/2-fase2.md` (formato). |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice di produzione. |
| **Data** | 13/10/2026 |
| **Metodo** | Lettura del codice delle superfici richieste, con attenzione ai parser di file non fidati, ai tetti di decodifica, ai Nascosti e alla condivisione. Un test nuovo, `core-metadata/.../SecurityReviewFuzzTest.kt`, prova la robustezza dei lettori di contenitori e **dimostra** il problema grave R1. Eseguito con `:core-metadata:testDebugUnitTest` (`ANDROID_SERIAL=none`): 5 prove di robustezza verdi, 1 (R1) rossa, come atteso. |

## Riepilogo

| Gravità | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 1 | R1 `iloc` HEIF con extent senza limite: OOM/blocco da una foto di pochi KB |
| Media | 1 | R2 decodifica di GIF/WebP animati senza tetto di pixel nel ripiego del viewer |
| Bassa | 2 | R3 MCGP1: `checkIndex` non valida gli indici interni · R4 coda `vanished`/log: categorie d'errore larghe |
| Informativa | 2 | I1 i parser sincroni non sono annullabili a meta' · I2 nessuna rete: verificato |

---

## Risposte al perimetro

1. **Parser di file non fidati** (`core-metadata/format/**`, `extract/**`, motion photo, ThumbHash). Difesa coerente e forte: ogni ciclo ha un tetto (`JpegScanner` 400 segmenti, `PngScanner` 256 chunk, `WebpScanner` 4096 chunk, `IsoBmff` 256 riquadri di primo livello e `meta` ≤ 8 MiB, `TiffParser` 1000 voci/IFD, 8 sub-IFD, `MotionCandidates`/`SefTrailer` 256 voci, `XmpFacts` testo ≤ 1 MChar con quantificatori limitati). Offset e lunghezze negativi o oltre il file non lanciano: `RandomAccess` accorcia e chi interpreta controlla la lunghezza. I cicli di IFD sono spezzati da un `visited`. L'estrattore trasforma ogni eccezione in `Corrupt`/`NotMedia`/`Unsupported`. **Unica eccezione**: `IsoBmff.parseIloc` non limita `extent_count` ne' il prodotto voci×extent → **R1** (confermato da test). I 5 test di robustezza nuovi (JPEG/PNG/WebP/TIFF/ISO casuali e troncati, ThumbHash, XMP, SEF) passano senza eccezioni ne' sforare il budget.
2. **Decodifica delle immagini.** Il viewer legge le dimensioni con `ImageDecoder` uscendo dall'`onHeaderDecoded` **prima** di allocare pixel, poi `DecodePlanner` taglia a `budget.viewerMaxPixels` (da `memoryClass`) e i RAW a 4096 px; le miniature del telefono passano da `ContentResolver.loadThumbnail` (il sistema). Il ThumbHash in META decodifica l'anteprima con `inSampleSize` verso 100 px, mai la bitmap intera, e con `catch (OutOfMemoryError)`. Le bombe per dimensione dichiarata sono quindi coperte **tranne** il ripiego degli animati → **R2**.
3. **Nascosti (E-H1, E-H3, E-S5).** Nessuna fuga trovata: l'indice e l'istantanea `MCGT1` escludono i nascosti in SQL (`COALESCE(p.hidden,0)=0`), le Raccolte e il viewer leggono la stessa vista, il conteggio dei Nascosti nell'elenco e' `null` (dall'esterno non si capisce se esistono), i conteggi "N foto/M video" scorrono la vista (senza nascosti), lo scrubber e le descrizioni TalkBack nascono dall'istantanea. L'unico lettore e' `HiddenLibrary` (dopo lo sblocco), e `forget()` toglie dall'istantanea su disco **prima** che il catalogo nasconda (file provvisorio, o cancellato se la scrittura fallisce). Il cancello con tolleranza di un minuto usa l'orologio **monotono** (`elapsedRealtime`: cambiare data non la allunga), e' `@Singleton` di processo (un riavvio riparte bloccato), non si riapre con rotazione/ricreazione (`isChangingConfigurations`), non ha deep link e un orologio all'indietro lo richiude (fail closed). Robusto.
4. **File su disco.** Istantanea (`noBackupFilesDir/replica/timeline.idx`), preferenze (`noBackupFilesDir/datastore`), asset luoghi (`noBackupFilesDir/places`): tutti fuori dal backup per posizione, e `data_extraction_rules.xml` esclude comunque **tutti** i domini in `<cloud-backup>` e `<device-transfer>` (compresi `device_*`), con `allowBackup=false`. `gallery.db` (metadati, luoghi, flag personali) sta nel dominio `database`, anch'esso escluso. La lettura di un `MCGT1` corrotto non crasha: `decode` valida magic/versione/limiti (`n*50 ≤ length`, tabelle ≤ 255 stringhe) e CRC, e in ogni caso `read()` cattura e ricostruisce. Il `MCGP1` valida intestazione, layout, dimensione totale e CRC all'apertura; vedi R3 per il residuo.
5. **Intent e condivisione.** `AndroidPhoneMediaIntents.share` usa `FLAG_GRANT_READ_URI_PERMISSION` **e** `ClipData` con tutti e soli gli URI `content://media/...` (nessun URI di file dell'app, **nessun** `FLAG_GRANT_PERSISTABLE`). `copy` mette l'URI (non i byte) negli appunti, marcato `EXTRA_IS_SENSITIVE` dai Nascosti. "Apri in Mappe" (`geo:`) parte solo dal foglio di conferma `MapsSheet` dopo un tocco esplicito. La posizione GPS resta nelle foto condivise (D, dichiarato). Conforme a C17/C18.
6. **Log e privacy.** In `core-*`/`feature-*` della Fase 3 i `GalleryLog` passano solo messaggi generici e `e.javaClass.simpleName` (mai percorsi, URI, coordinate, luoghi o id). `GalleryLog` in release non ha sink (non scrive). `PlaceRef`/`GeoPoint` sono `@Sensitive` senza `toString`. La Diagnostica di `PhoneLibraryDiagnostics` esporta solo `Num`/enum. Le stampe con percorsi sono solo nello strumento `buildPlacesDataset` (gira sul PC, non nell'app). Pulito.
7. **Nessuna rete (D24).** Ogni modulo ha `NoNetworkSourcesTest`; Media3 usa solo `ContentDataSource` (mai `DefaultDataSource`/`DefaultHttpDataSource`), telephoto lavora su una sorgente nostra, nessun Coil/Glide. Verificato (I2).
8. **Strumento `buildPlacesDataset` e asset.** Lo SHA-256 e' fissato in `PlacesDatasetPin` (ora valorizzato: `f7a2eb7f…`, 4.983.736 B) e **verificato alla prima estrazione** (`PlacesStore` calcola lo SHA mentre copia; impronta o dimensione diverse → `HASH_MISMATCH`, niente file tenuto). Vedi R3 per cosa succede se l'asset estratto viene alterato dopo.

---

## Alta

### R1. `IsoBmff.parseIloc`: `extent_count` e numero di voci senza tetto → OOM o blocco da un HEIF di pochi KB

- **Gravità:** alta
- **Stato:** **corretto** (13/10): tetti espliciti in `IsoBmff` (costanti `internal`, documentate): 4096 voci (`item_count` dell'`iloc`, `infe` di `iinf`, voci di `ipma`), 32 extent per voce, 8192 extent in tutto l'`iloc`, 1024 proprieta' in `ipco`, 4160 riquadri figli per contenitore, 32 marche compatibili in `ftyp` (`Sniffer`). Con le taglie > 0 gli extent dichiarati devono stare nei byte residui del riquadro; posizioni/lunghezze negative o che traboccano a 64 bit sono rifiutate. Oltre un tetto l'HEIF e' "non leggibile" (`ImageScan.malformed` → `Corrupt(DECODE_FAILED)`), mai un'eccezione fuori da `scanHeif`. `iref`, `stsz`/`stco`/`co64` non vengono letti: nessun conteggio da limitare. Test: `SecurityReviewFuzzTest.R1_ilocConMoltiExtentNonDeveGirareSenzaLimite` (ora verde) e `core-metadata/.../IsoBmffLimitsTest.kt` (15 prove: sotto il tetto passa, sopra e' `malformed`, una per ogni conteggio, piu' estrattore e `ftyp`).
- **Dove:** `core-metadata/src/main/kotlin/dev/pampa/mycloudgallery/core/metadata/format/IsoBmff.kt:222-259` (`parseIloc`); raggiunto da `scanHeif` → `parseMeta` (`:80-113`), e cosi' dall'estrattore per ogni HEIC/AVIF (`DefaultMetadataExtractor.readImage`).
- **Scenario:** una foto HEIC ricevuta (per esempio su WhatsApp) il cui riquadro `meta` contiene un `iloc` con `offset_size`, `length_size`, `base_offset_size` e `index_size` tutti a **0** e molte voci con `extent_count = 65535`. Con le taglie a zero ogni extent **non consuma byte**: il parser costruisce comunque un `Extent` per ognuno. Il numero di extent non e' piu' legato alla dimensione del file. Con `construction_method = 0` (il default) gli `Extent` vengono anche **conservati** in `out`: un `meta` da 8 MiB (il tetto `MAX_META_BYTES`) porta a ~1,4 milioni di voci × 65535 ≈ 9×10^10 oggetti → `OutOfMemoryError` durante la fase META, cioe' la sincronizzazione della Libreria si ferma (o l'app viene uccisa). Anche senza conservarli (metodo 1) resta la pura allocazione/CPU: minuti di lavoro, non annullabili (vedi I1), da un file di poche decine di KB.
- **Perché funziona:** `MAX_META_BYTES` (8 MiB) limita solo il **corpo** del `meta`, non `extent_count` (nessun tetto) ne' il prodotto voci×extent. Tutti gli altri lettori del modulo hanno un tetto per ciclo; `parseIloc` e' l'unico senza.
- **Correzione proposta:** limitare `extent_count` per voce (per le immagini bastano pochissimi extent; un tetto basso, es. 16–64, e' abbondante) e il totale degli extent dell'`iloc` (o pretendere che ogni extent consumi almeno un byte, rifiutando `offset_size == 0 && length_size == 0`); in piu' un tetto sul numero di voci coerente con gli altri lettori. Su violazione, trattare l'HEIF come `malformed` (foto non leggibile), non lanciare.
- **Confermato:** **sì.** `SecurityReviewFuzzTest.R1_ilocConMoltiExtentNonDeveGirareSenzaLimite` costruisce un HEIF di ~240 KB con 30.000 voci × 65535 extent e verifica che `scanHeif` finisca entro 4 s su un thread a scadenza: oggi fallisce (il thread non finisce in tempo). Il test usa il metodo 1 per restare entro la memoria pur dimostrando il tempo illimitato; in produzione il metodo 0 porta all'OOM.

---

## Media

### R2. `ImageDecoderAnimatedLoader`: GIF/WebP animati decodificati senza tetto di pixel

- **Gravità:** media
- **Stato:** **corretto** (P11, 13/10): il ripiego legge la tela dall'intestazione e la riduce con le stesse regole del percorso primario (lato lungo dello schermo, un quarto di `viewerMaxPixels` per animare); una tela che da sola supera `viewerMaxPixels` non si anima e si mostra il primo fotogramma fermo, alla taglia dello schermo (`AnimatedSizing`, `ImageDecoderAnimatedLoader`). `:app` gli passa il lato dello schermo e `ImagingBudget.viewerMaxPixels`. Test: `feature-viewer/.../AnimatedSizingTest.kt` (tela 16383×16383, GIF normale, GIF piccola, dimensioni assurde).
- **Dove:** `feature-viewer/.../ViewerServices.kt:81-101` (`ImageDecoderAnimatedLoader.load`): `ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri))` **senza** `onHeaderDecoded` ne' `setTargetSize`.
- **Scenario:** un WebP animato ostile che dichiara una tela grande (VP8X arriva a 16383×16383) con piu' fotogrammi. `AnimatedImageDrawable` tiene in memoria piu' bitmap a tela piena: un picco di centinaia di MB, jank o chiusura del processo. Il percorso **primario** (`PipelineViewerImageSource.decodeAnimated`, `core-imaging`) invece taglia correttamente la tela a `viewerMaxPixels/4` nell'`onHeaderDecoded`: questo ripiego senza tetto si usa solo quando il primario torna null, ma resta raggiungibile.
- **Perché funziona:** manca il tetto di pixel che il resto della pipeline applica ovunque. C'e' un `catch (OutOfMemoryError)` che evita il crash, ma non la pressione di memoria/jank prima dell'OOM.
- **Correzione proposta:** dare anche al ripiego un `onHeaderDecoded` con lo stesso tetto del percorso primario (lato lungo e `viewerMaxPixels/4`), oppure togliere il ripiego e lasciare che l'unico decodificatore animato sia quello gia' limitato di `core-imaging`.
- **Confermato:** no (lettura del codice).

---

## Bassa

### R3. `Mcgp1.checkIndex` non valida gli indici interni dei record

- **Gravità:** bassa
- **Stato:** **corretto** (13/10): `Mcgp1.checkIndex` valida all'apertura, in un solo passaggio lineare (circa 2 ms su 170 mila luoghi; la ricerca non cambia, 100 mila ricerche in ~230 ms): colonne e primi luoghi delle celle, id/paese/regione/fuso di ogni luogo, nomi dentro la tabella, paesi (due lettere, in ordine), regioni (id crescenti, paese valido) e stringhe dentro il pool. Un file alterato anche col CRC rifatto dà `Mcgp1FormatException` (`BAD_INDEX`, nuovo `BAD_RECORD`) → `PlacesOpenResult.Failed(INVALID_FORMAT)` → `NoReverseGeocoder`. Test: `core-places/.../Mcgp1TamperedTest.kt` (14 prove: un campo alterato per volta, asset alterato fino a `PlacesStore`, fuzz di 2500 alterazioni "rifiutata o letta senza eccezioni") e `PlacesPerformanceTest.lAperturaConLaValidazioneDegliIndiciCostaPoco`.
- **Dove:** `core-places/.../dataset/Mcgp1.kt:511-520` (`checkIndex`): valida i bordi e l'ordine di `rowStart`/`cellFirst`, **non** i `cellCol` (range 0..1439) ne', per ogni luogo, gli indici di paese/regione/fuso. `scanCell` (`:390-437`) usa `microCountry[country]` con `country = u16(at+10)` letto dal file.
- **Scenario:** l'asset `places.mcgp1` estratto in `noBackupFilesDir` viene alterato mantenendo dimensione e CRC-32 coerenti (il CRC e' banale da ricalcolare). Un `country` fuori range → `ArrayIndexOutOfBoundsException` invece di un fallimento pulito; indici di nome fuori range → letture incoerenti. **Nel modello di minaccia conta poco:** riscrivere quel file richiede accesso in scrittura alla cartella privata dell'app (root/malware gia' dentro la sandbox), e lo SHA-256 e' verificato alla **prima** estrazione; le riaperture controllano solo dimensione+CRC+versione.
- **Correzione proposta:** in `checkIndex`, validare `cellCol ∈ 0..COLS-1` e, almeno a campione o in `verifyChecksum`, `country < countryCount`, `region < regionCount || NONE`, `zone < zoneCount || NONE`. In alternativa, dichiarare il limite (il file estratto e' fidato quanto la sandbox).
- **Confermato:** no (lettura del codice).

### R4. Categorie d'errore larghe nei log della sincronizzazione

- **Gravità:** bassa (privacy, latente)
- **Stato:** **corretto** (13/10): un test che scansiona i sorgenti in ognuno dei sette moduli della Fase 3 (`NoExceptionTextInLogsTest`, modello `NoLogSourcesTest`) e fallisce se un `GalleryLog` contiene `e.message`, `e.localizedMessage`, `e.toString()`, `$e`, `${e}` (o equivalenti sulle eccezioni catturate); ammesso solo `e.javaClass.simpleName`. Nessuna violazione presente. Passare `e` come argomento `throwable` (`core-library`) non e' testo del messaggio e non e' vietato (il sink esiste solo in debug). Test: `core-mediastore`, `core-metadata`, `core-places`, `core-imaging`, `core-library`, `feature-library`, `feature-viewer` (`.../NoExceptionTextInLogsTest.kt`, 3 prove ciascuno).
- **Dove:** `core-mediastore/.../sync/DefaultPhoneLibrarySync.kt:151,160,237`, `DeferredFullHashes.kt:87`, `meta/DefaultMetadataPass.kt:245-247`: i messaggi usano `e.javaClass.simpleName`. Oggi nessun nome/percorso vi finisce (le eccezioni del MediaStore sono di tipo generico), ma la regola C21/C14 e' "mai percorsi o nomi nei log".
- **Scenario:** un contributore futuro potrebbe aggiungere `${e.message}` a uno di questi, e un messaggio di `IOException` del provider puo' contenere un URI o un percorso. Nessuna fuga oggi; e' una barriera che conviene tenere esplicita.
- **Correzione proposta:** un commento/regola che vieta `e.message`/`toString()` in questi punti, o un test che verifica che i log della Fase 3 non contengano mai dati dei media (come fa `:core-places` con `NoLogSourcesTest`).
- **Confermato:** no.

---

## Informative

- **I1. I parser sincroni non sono annullabili.** `parseIloc`/`parseIpma`/`scanCell` lavorano su `ByteArray`/buffer in memoria senza punti di sospensione: l'annullamento della coroutine (l'utente scorre via) non li ferma a meta'. Con i tetti giusti e' irrilevante; senza (R1) peggiora il caso ostile. La correzione di R1 lo risolve di fatto.
- **I2. Nessuna rete: verificato.** `NoNetworkSourcesTest` in ogni modulo; Media3 solo `ContentDataSource`; telephoto su sorgente nostra; nessun Coil/Glide; nessun `java.net`/HTTP nei sorgenti della Fase 3.

---

## Cose fatte bene

- **Lettori di contenitori** con un tetto a ogni ciclo e a ogni lettura; offset/lunghezze fuori dal file gestiti senza eccezioni; cicli di IFD spezzati; l'estrattore non lascia **mai** uscire un'eccezione (solo l'annullamento), coerente con `RobustnessTest` (R1 e' la sola falla).
- **Tetti di decodifica** dal `memoryClass` (`ImagingBudget`), lettura delle dimensioni prima dei pixel, campionamento dei RAW a 4096 px, `catch (OutOfMemoryError)` ovunque.
- **Nascosti** con un cancello `@Singleton`, fail-closed, orologio monotono, nessun deep link, esclusi da indice/istantanea/conteggi/scrubber/TalkBack; `forget()` toglie dal disco prima di nascondere.
- **Istantanea `MCGT1`** con validazione di versione, limiti e CRC, scrittura atomica e ripiego alla ricostruzione.
- **Condivisione** con `ClipData`, permesso solo di lettura e non persistente, solo URI `content://media`, "Apri in Mappe" dietro conferma, copia marcata sensibile dai Nascosti.
- **Backup**: `noBackupFilesDir` per tutto lo stato, piu' `data_extraction_rules` che esclude ogni dominio (compresi `device_*`) e `allowBackup=false`.
- **Dataset luoghi** con SHA-256 verificato all'estrazione, scrittura atomica, CRC/dimensione/versione alla riapertura, nessun log con coordinate.

---

## Da controllare nel delta di P11 (`:app`)

- **Manifest**: `READ_EXTERNAL_STORAGE` con `maxSdkVersion="32"` e nient'altro di nuovo; il `provider` (FileProvider?) esportato `false`; nessun componente esportato oltre `MainActivity`; `verifyReleaseManifest`/policy aggiornati.
- **Worker** (`PhoneSyncWorker`, `LibraryMaintenanceWorker`): nessun avvio da fuori, nessun dato dei media nei log, rispetto del motore delle regole.
- **Permessi**: richiesta solo da un tocco dell'utente; `ACCESS_MEDIA_LOCATION` come da D9.
- **Provider/URI**: il cablaggio di `services.animated` (vedi **R2**) e la scelta tra percorso primario e ripiego; gli URI condivisi/copiati solo `content://media`.
- **Backup**: `data_extraction_rules` invariato; `gallery.db` e `replica/` fuori dal backup; la Libreria sintetica di debug su database separato mai in release.
- **Diagnostica**: i codici/chiavi della Fase 3 solo numeri ed enum (come F4 della Fase 2).
