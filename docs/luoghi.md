# Luoghi offline (`core-places`, Fase 3, P4)

Dal punto GPS di una foto al **nome del posto** e al **fuso orario**, senza rete: un file binario compatto (`MCGP1`) ricavato da GeoNames, mappato in memoria. Nomi in italiano ("Londra", "Monaco di Baviera", "Lisbona"), regione e paese. Fonti di progetto: `docs/fase3-progetto.md` D8, §1.2 (`core-places`), §2.3, §4.3 (P4).

## 1. Stato

| Cosa | Stato |
|---|---|
| Formato `MCGP1`: scrittura, lettura, mappatura in memoria | fatto e provato |
| Ricerca ad anelli (antimeridiano, poli), precisione `CITY` / `NEAR` / `REGION_ONLY` / `COUNTRY_ONLY` | fatto, confrontata con la forza bruta |
| Classifica pesata con la popolazione (distanza meno raggio d'influenza) e micro-stati come enclavi (Vaticano, San Marino, Monaco, Liechtenstein) | fatto: "Citta' del Vaticano" per il centro di Roma non succede piu' |
| Nomi italiani, esonimi, regioni, fusi, `PlaceNames.summary`, `PlacesAttribution` | fatto |
| Strumento `:core-places:buildPlacesDataset` (riproducibile, non scarica niente) | fatto, provato su un input di prova |
| **Asset reale nell'APK** | **da generare dopo il consenso dell'utente al download** (passi qui sotto). Finche' non c'e', `PlacesDataset.open(context)` ritorna `NoReverseGeocoder` e la Libreria funziona senza luoghi |
| Verifica sulle 200 coordinate | provata sul **mini-dataset** di prova; sul dataset reale si ripete con un comando (passo 4) |

## 2. Generare l'asset reale: i passi per l'utente

Serve il consenso dell'utente al download (circa **200 MB**, quasi tutti per `alternateNamesV2.zip`). Lo strumento **non scarica niente**: se i file mancano si ferma con un messaggio che dice cosa scaricare.

### Passo 1: scaricare i quattro file a mano

Da <https://download.geonames.org/export/dump/> (GeoNames, licenza **CC BY 4.0**), nella cartella `%USERPROFILE%\.mycloudgallery\geonames\`:

| File | Dimensione | A cosa serve |
|---|---|---|
| `cities1000.zip` | circa 8 MB | i luoghi del mondo con almeno 1000 abitanti o sede amministrativa |
| `IT.zip` | circa 1 MB | tutti i luoghi italiani, compresi i comuni sotto i 1000 abitanti |
| `admin1CodesASCII.txt` | circa 0,2 MB | le regioni (codice, nome, id GeoNames) |
| `alternateNamesV2.zip` | circa 190 MB | i nomi alternativi: se ne leggono solo le righe `it` dei luoghi tenuti |

In PowerShell:

```powershell
$d = "$env:USERPROFILE\.mycloudgallery\geonames"
New-Item -ItemType Directory -Force $d | Out-Null
foreach ($f in "cities1000.zip","IT.zip","admin1CodesASCII.txt","alternateNamesV2.zip") {
  Invoke-WebRequest "https://download.geonames.org/export/dump/$f" -OutFile "$d\$f"
}
```

Gli zip non vanno estratti (lo strumento li legge cosi' come sono; accetta anche i `.txt` gia' estratti accanto agli zip).

### Passo 2: generare il dataset

```powershell
.\gradlew.bat :core-places:buildPlacesDataset
```

Impiega alcuni minuti (legge oltre 1 GB di testo, soprattutto `alternateNamesV2`). Scrive **due** file, entrambi da versionare:

- `core-places/src/main/assets/places/places.mcgp1`: l'asset (circa 4-5 MB, circa 2,5 MB compressi nell'APK);
- `core-places/src/main/kotlin/dev/pampa/mycloudgallery/core/places/dataset/PlacesDatasetPin.kt`: l'impronta fissata nel codice (versione, SHA-256, dimensione). Non si modifica a mano.

Stampa solo conteggi e impronte (mai nomi di luoghi): luoghi tenuti, scartati per motivo, nomi italiani trovati, **versione = data del dump** (la data di modifica piu' recente nei dati), dimensione e SHA-256.

Opzioni (proprieta' Gradle): `-PplacesInput=<cartella>` (altra cartella dei dump), `-PplacesExpectSha=<64 cifre esadecimali>` (fallisce senza scrivere se l'impronta cambia: serve a verificare la riproducibilita'), `-PplacesOutput=<file> -PplacesPin=<file>` (scrive altrove, per provare). Con la cartella mancante esce con codice 2 e un messaggio chiaro, senza scrivere nulla.

### Passo 3: controllare la dimensione

```powershell
$a = "core-places\src\main\assets\places\places.mcgp1"
(Get-Item $a).Length / 1MB
Compress-Archive $a "$env:TEMP\places.zip" -Force; (Get-Item "$env:TEMP\places.zip").Length / 1MB
```

Obiettivo: **meno di 3 MB compressi** (D8: circa 2,5 MB). Se fosse di piu', la leva e' `PlacesDatasetBuilder.classify` (per esempio non tenere i `PPL` italiani sotto una soglia), non il formato.

### Passo 4: provare

```powershell
.\gradlew.bat :core-places:testDebugUnitTest :core-places:lintDebug
```

Con l'asset presente girano in piu':

- `PlacesAccuracyTest.realDatasetAccuracy`: le stesse 200 coordinate (`taratura-100.tsv`, `verifica-100.tsv`) sul dataset **reale**, soglia 95% per file. I nomi accettati sono scritti per il mini-dataset: sul dataset vero una riga puo' fallire perche' GeoNames ha un luogo in piu' (o in meno) vicino a quel punto. In quel caso si guarda la riga: se il luogo trovato e' giusto si aggiunge ai nomi accettati, se e' sbagliato e' un difetto da capire. **Per `verifica-100` non si ritara l'algoritmo** (e' cieco): si correggono solo righe sbagliate.
- `BuildPlacesDatasetTest.ilPinCommittatoEQuelloDiNessunDatasetOQuelloDellAsset`: l'impronta nel codice e' quella dell'asset;
- `PlacesDatasetOpenTest`: estrazione e apertura sul contesto di Robolectric.

### Passo 5: registrare e committare

Compilare la tabella del §8 (SHA-256 e data del dump), committare asset e `PlacesDatasetPin.kt`. Riesecuzione di controllo (stesso dump, stessa impronta):

```powershell
.\gradlew.bat :core-places:buildPlacesDataset -PplacesExpectSha=<SHA-256 della tabella>
```

### Cosa deve fare P11 (integrazione)

```kotlin
val data = (PlacesDataset.openResult(context) as? PlacesOpenResult.Opened)?.data   // fuori dal thread principale
val geocoder: ReverseGeocoder = data ?: NoReverseGeocoder
val zones: TimeZoneLookup = data ?: NoTimeZoneLookup
val names: PlaceNames = data ?: NoPlaceNames
```

`PlacesData` e' insieme geocoder, cercatore di fusi e nomi per la UI sullo stesso file. `openResult` dice anche perche' manca (`NotBundled`, `Failed(ASSET_MISSING | HASH_MISMATCH | IO_ERROR | INVALID_FORMAT)`) per la Diagnostica. Un cambio di `dataset` (versione) rispetto a `catalog_meta.places_dataset` e' il segnale per il ricalcolo in manutenzione.

## 3. Il formato `MCGP1`

File unico, little-endian, sezioni allineate a 4 byte, con un CRC-32 finale. Sta in `dataset/Mcgp1Format.kt` (commento in testa); lettura in `Mcgp1.kt`, scrittura in `Mcgp1Writer.kt`.

| Sezione | Contenuto |
|---|---|
| intestazione (96 B) | magic `MCGP1\0\0\0`, versione del dataset, conteggi, offset di ogni sezione, dimensione totale |
| `rowStart` | per ognuna delle 720 fasce di 0,25 gradi di latitudine, l'indice della sua prima cella |
| `cellCol`, `cellFirst`, `cellName` | le **celle non vuote** (griglia di 0,25 gradi, 1440 colonne): colonna, indice del primo luogo, offset del primo nome |
| `places` (17 B ciascuno) | id GeoNames (int32); posizione **relativa alla cella** (2 x uint16, in 1e-5 gradi, quindi circa 1 m); regione (uint16); paese (uint16); fuso (uint16); classe (uint8: 1 abitato, 2 sede amministrativa, 3 capitale); popolazione (uint8, logaritmica); lunghezza del nome (uint8) |
| `names` | i nomi UTF-8 nello stesso ordine dei luoghi, senza separatori |
| `regions` (12 B) | id GeoNames della regione, paese, nome |
| `zones`, `countries`, pool | identificativi IANA dei fusi, codici ISO dei paesi, stringhe |

Scelte rispetto alla bozza di progetto: la posizione e' relativa alla cella (4 byte invece di 8 per luogo); il nome si trova sommando le lunghezze dei luoghi che lo precedono nella cella (la ricerca, che e' il percorso caldo, non lo legge mai); l'indice per id (`place(id)`) si costruisce in memoria alla prima richiesta (circa 1,4 MB per 170 mila luoghi) invece di stare nel file. Il tutto per restare nei 4-5 MB.

**Il formato non cambia con la classifica pesata e le enclave**: la popolazione e' gia' nel file (il byte logaritmico di ogni luogo) e da quel byte la ricerca ricava il raggio d'influenza con una tabella di 256 valori; i poligoni dei micro-stati sono dati scritti a mano nel codice (`dataset/MicroStates.kt`), legati al codice paese, e non dipendono dal dump. Per questo resta `MCGP1`, un file generato prima di questa modifica si apre e da' gli stessi risultati di uno generato dopo.

Il file e' **deterministico**: stesso input, anche con le righe in ordine diverso, stessi byte. Non c'e' orologio, ne' ordine di mappe hash, ne' `log` in virgola mobile (la popolazione si comprime con soli interi).

## 4. La ricerca

`ReverseGeocoder.nearest(GeoPoint)` sceglie fra i luoghi **qualificati**: almeno 1000 abitanti, oppure sede amministrativa (`PPLA*`, in Italia anche i comuni sotto i 1000 abitanti) o capitale. **Non vince il centroide piu' vicino ma il luogo col punteggio piu' basso**, con due correzioni a "il piu' vicino" decise dopo la prima versione (il centroide piu' vicino dava "Citta' del Vaticano" per il centro di Roma).

### 4.1 La classifica pesata con la popolazione

```
punteggio = distanza dal luogo - raggio d'influenza(popolazione)
raggio d'influenza = min(8.000 m, 5 m * radice(popolazione))
```

Vince il punteggio piu' basso; a parita' l'id piu' piccolo. Il raggio cresce come la **radice** della popolazione (l'area di una citta' cresce come la popolazione, il suo raggio come la radice) e ha un **tetto** di 8 km: una grande citta' si prende i dintorni del suo centro (il centroide di Roma sta a est del centro storico, il confine del comune a molti km), un paese di 500 abitanti quasi nulla. Le costanti (`PlacesLimits.INFLUENCE_K_M` = 5 e `INFLUENCE_MAX_M` = 8.000) danno:

| Luogo | Popolazione | Raggio |
|---|---|---|
| Roma (e ogni citta' sopra 2,6 milioni) | 2,9 milioni | **8 km** (il tetto) |
| Milano | 1,4 milioni | 5,8 km |
| Firenze | 380.000 | 3,1 km |
| citta' media | 80.000 | 1,4 km |
| Fiesole | 14.000 | 0,6 km |
| paese | 4.000 | 0,3 km |
| Citta' del Vaticano | 826 | 0,14 km |
| paese | 500 | 0,1 km |

Il raggio si calcola dal **byte** di popolazione del file (non esiste la popolazione esatta), con la tabella `Mcgp1Format.INFLUENCE_RADIUS_M` di 256 valori ottenuti solo con operazioni esatte: uguale su ogni JVM. Fra due luoghi di raggi r1 e r2 a distanza D, il confine sta a (D + r1 - r2) / 2 dal primo.

Conseguenze, tutte volute e provate (`WeightedRankingTest`):

- **Roma.** Piazza Navona, Pantheon, Piazza di Spagna, Trevi, Vittoriano: **Roma**. (La riga V003 di `verifica-100.tsv` passa.)
- **Una citta' media tiene il suo centro, una grande si prende il confine.** Il centro di Sesto San Giovanni: **Sesto San Giovanni**, non Milano. Bicocca, sul confine: **Milano**. Cinisello Balsamo resta sua. Fiesole e' Fiesole nel suo centro e Firenze a 2 km verso Firenze.
- **Una frazione vicino a una citta' media e' della citta'.** Una frazione (meno di 1000 abitanti, non sede amministrativa) non e' nel dataset: a 1-2 km da una citta' di 50.000 abitanti la risposta e' la citta'. Anche un paese qualificato di 1.500 abitanti a 1,7 km da quella citta' tiene solo i primi 400 m intorno al suo centro; oltre e' un quartiere della citta'. Un paese isolato non e' toccato: il raggio di una citta' media (1,4 km) e di una grande (fino a 8 km) pesa solo dove la distanza e' paragonabile. Non c'e' un rimedio "giusto" per tutti i comuni (i confini veri non sono nel dataset): e' il prezzo del peso.

### 4.2 Micro-stati come enclavi

Con il solo peso, una foto in Piazza San Pietro diventerebbe "Roma" (Roma pesa 8 km, il Vaticano 0,14). I micro-stati sono quindi **poligoni** scritti a mano (`dataset/MicroStates.kt`: vertici approssimati a circa 100 m, in senso orario; sono i contorni di quattro stati, non una copia di nessun dataset):

| Stato | Vertici | Superficie | Note |
|---|---|---|---|
| Citta' del Vaticano (`VA`) | 10 | 0,44 km2 | da Piazza San Pietro (lato est) ai Giardini e ai Musei |
| San Marino (`SM`) | 9 | 61 km2 | dal monte Titano a Serravalle e ai castelli del sud |
| Monaco (`MC`) | 11 | 2 km2 | la striscia costiera da Fontvieille a Larvotto |
| Liechtenstein (`LI`) | 9 | 160 km2 | la valle del Reno da Ruggell a Balzers e i versanti fino a Malbun |

La regola, la piu' semplice che passa i test:

- se il punto e' **dentro** un poligono, **o a meno di 300 m** dal suo confine (`PlacesLimits.ENCLAVE_MARGIN_M`: un GPS sbaglia di decine di metri e i poligoni sono approssimati), vince quel paese: la ricerca guarda solo i luoghi di quel paese, con la classifica pesata (dentro San Marino vince Serravalle a Serravalle e la citta' di San Marino al centro);
- **altrimenti i luoghi dei micro-stati non competono**, nemmeno se sono i piu' vicini: da Roma non si risponde mai "Citta' del Vaticano", da Mentone non si risponde "Monaco", da Rimini non si risponde "San Marino" anche se il centroide di Serravalle e' piu' vicino;
- gli stati si riconoscono dal codice paese: se il dataset non ha luoghi di quel paese (o se ne ha solo con un codice diverso) il poligono non ha effetto.

Provati: Piazza San Pietro e Musei Vaticani: **Citta' del Vaticano**; Castel Sant'Angelo (590 m fuori): Roma; Via della Conciliazione a 180 m dal confine: ancora Vaticano; San Marino centro: **San Marino**; Rimini (anche il suo entroterra a 1,7 km dal confine di San Marino): **Rimini**; Casino di Monte Carlo: **Monaco**, mentre 1,4 km a est e' **Mentone**; Vaduz, Schaan, Malbun: Liechtenstein; Buchs, oltre il Reno: Buchs.

### 4.3 Ricerca ad anelli, esatta

1. **Anelli.** Si guarda la cella del punto, poi le 8 intorno, poi le 16 successive, e cosi' via. La colonna si riavvolge all'antimeridiano; le fasce non si riavvolgono, ai poli l'anello si ferma dove le fasce finiscono.
2. **Quando fermarsi.** Dopo ogni anello si calcola un limite inferiore alla distanza di tutto cio' che non si e' ancora visto: in latitudine, la distanza fino al bordo della prima fascia non vista; in longitudine, la distanza dal punto al **meridiano** a quella differenza di longitudine, `asin(cos(lat) * sin(theta))`, che vicino ai poli diventa piccola (e per questo i poli non sono un caso speciale). Un luogo non visto ha un punteggio di almeno *limite - raggio massimo*: **si finisce quando il punteggio migliore e' minore o uguale a limite - 8 km**, cioe' l'anello deve superare il miglior punteggio piu' il raggio massimo. La ricerca resta esatta, solo si allarga di 8 km.
3. **Niente trigonometria per scartare.** Un luogo si scarta con un limite inferiore fatto di sole operazioni elementari (`2R * sqrt(...)` meno il suo raggio contro il punteggio migliore); solo gli altri si calcolano con haversine.
4. **Il tetto** (300 km) si applica alla distanza dal luogo scelto; oltre non c'e' risultato.

`RingSearchTest` la confronta con la forza bruta **con lo stesso punteggio** (distanza meno raggio dal byte di popolazione) su dataset casuali con popolazioni da 1.000 a 3 milioni.

### 4.4 Precisione

Dalla **distanza** del luogo scelto (non dal punteggio):

| Distanza | Precisione | Cosa si puo' dire |
|---|---|---|
| fino a 15 km | `CITY` | il nome del luogo |
| fino a 50 km | `NEAR` | "vicino a ..." |
| fino a 150 km | `REGION_ONLY` | solo la regione (se il luogo ne ha una; altrimenti il paese) |
| fino a 300 km | `COUNTRY_ONLY` | solo il paese |
| oltre | nessun risultato | oceano, deserto: mai un nome inventato |

Per `REGION_ONLY` e `COUNTRY_ONLY` il `Place` restituito **e' la regione o il paese** (`name` = "Toscana" o "Italia"; id = id GeoNames della regione o `2.000.000.000 + indice del paese`), cosi' `PlaceRef.geonameId` ha sempre un nome vero da mostrare e non si scrive mai "Firenze" per una foto fatta a 80 km. Le soglie sono in `PlacesLimits`.

`TimeZoneLookup.zoneAt` prende il fuso dal luogo scelto (la stessa classifica, le stesse enclave: Piazza San Pietro e' `Europe/Vatican`) se e' **entro 150 km** (altrimenti null: il chiamante ripiega sui vicini o sul fuso del telefono). Un fuso che il sistema non conosce da' null, mai un'eccezione.

## 5. Cosa si tiene da GeoNames (la politica del dataset)

Tutto in `tool/PlacesDatasetBuilder.kt`:

- si leggono `cities1000` e `IT`; un luogo in entrambi si tiene una volta (vincono i dati di `IT`);
- **si tengono**: `PPLC` (capitale), `PPLA`, `PPLA2`..`PPLA5`, `PPLG` (sedi amministrative), e `PPL`, `PPLL`, `PPLS` con almeno 1000 abitanti;
- **non si tengono**: `PPLX` (quartieri e frazioni di una citta': darebbero "Brera" invece di "Milano"), `PPLH`/`PPLQ`/`PPLW` (storici, abbandonati, distrutti), `PPLF`/`PPLR`, i `PPL` sotto i 1000 abitanti (non potrebbero mai essere scelti) e tutto cio' che non e' un luogo abitato;
- **nome**: quello italiano di `alternateNamesV2` (`isolanguage` = `it`; preferito, poi non abbreviato, poi non colloquiale, poi il piu' vecchio; gli storici si ignorano), altrimenti quello di GeoNames. Lo stesso per le regioni. Il nome del paese viene da `Locale.ITALIAN` (`Regno Unito`, `Stati Uniti`);
- **fuso**: quello di GeoNames; **versione** del dataset: la data di modifica piu' recente fra i luoghi tenuti, come aaaammgg (dipende solo dall'input).

## 6. I nomi per la UI

`PlaceNames.summary(top, distinct)`: un luogo "Firenze"; due "Firenze e Fiesole"; tre o piu' "Firenze e altri 2 luoghi". Con due luoghi ma uno solo noto: "Firenze e un altro luogo" (mai "altri 1 luoghi"). `label(ref)` e `labelOfId(id)` funzionano per luoghi, regioni e paesi.

## 7. Verifica e prestazioni

**Mini-dataset di prova** (`core-places/src/test/resources/places/geonames-mini/`): 4 file nel formato dei dump di GeoNames (19 colonne, tabulazione), circa 500 luoghi reali con coordinate, nomi italiani, regioni e fusi: Italia (capoluoghi, localita' di mare e di montagna, isole), confini (Como/Chiasso, Gorizia/Nova Gorica, Ventimiglia/Mentone, San Marino, Vaticano, Campione d'Italia, Monaco, Liechtenstein/Buchs) e capitali e citta' del mondo con i loro esonimi. Sono **dati di fatto scritti a mano, non una copia di GeoNames**; gli id sono **di prova** (da 9.000.001), non quelli di GeoNames. Dentro ci sono apposta anche i casi da scartare (quartieri, storici, frazioni sotto i 1000 abitanti, monti e laghi). Lo stesso strumento che costruira' il dataset vero li trasforma in un MCGP1 nei test.

**200 coordinate** (`src/test/resources/places/`): `taratura-100.tsv` per tarare e `verifica-100.tsv` cieco, mai usato per tarare (nessuna coordinata in comune: lo verifica un test). Colonne separate da tabulazione: id, latitudine, longitudine, nomi accettati (separati da `|`), precisioni accettate (`CITY`, `NEAR`, `REGION_ONLY`, `COUNTRY_ONLY`, `NONE` = nessun risultato), fusi accettati (`-` = non si controlla), nota. Una riga e' corretta se nome, precisione e fuso sono fra quelli attesi.

Esiti sul mini-dataset (`PlacesAccuracyTest`, soglia 95%):

| File | Corrette |
|---|---|
| `taratura-100.tsv` | 100 su 100 |
| `verifica-100.tsv` | **100 su 100** (con la sola distanza erano 99: la riga V003, Piazza Navona) |

Le costanti (K = 5 m per radice di abitante, tetto 8 km, margine delle enclave 300 m) si sono scelte ragionando sui casi della classifica (Roma e Vaticano, Sesto e Milano, Fiesole e Firenze) e verificate su `taratura-100.tsv`; `verifica-100.tsv` non e' stato usato per sceglierle: si e' solo rilanciato. I casi nuovi (Navona e Pantheon, San Pietro e Musei, Sesto, la frazione, San Marino, Rimini, Monaco, Liechtenstein) sono in `WeightedRankingTest` e `EnclaveTest`.

**Punti da sapere.** I poligoni sono approssimati: sono disegnati a mano, non misurati. Dentro il margine di 300 m dal Vaticano, la strada davanti al colonnato e' Vaticano. Con la classifica pesata il confine fra una grande citta' e un centro minore vicino e' una stima: Bicocca e' Milano e il centro di Sesto e' Sesto, ma un punto a 3 km da Sesto e a 5 da Milano e' Milano. Se sul dataset reale i numeri (5 m, 8 km) non convincono si cambiano in `Mcgp1Format` senza toccare il file.

**Prestazioni** (JVM, `PlacesPerformanceTest`): 100.000 ricerche in **circa 0,35 s** su un dataset di 170.000 luoghi raggruppati in citta' (limite del progetto: 2 s); 100.000 ricerche sparse sul globo sul mini-dataset in meno di 1 s (la maggior parte non trova nulla entro 300 km e allarga molto l'anello). Con 170.000 luoghi sintetici il file e' di circa 5,7 MB (33,7 byte per luogo; i nomi di prova sono piu' lunghi di quelli di GeoNames, che hanno circa 10 caratteri) e circa 2,3 MB compressi: con i nomi veri si resta nei 4-5 MB e sotto i 3 MB compressi previsti. La misura vera si fa sul dataset reale (passo 3).

**Correttezza della ricerca.** `RingSearchTest` confronta la ricerca con la forza bruta su dataset casuali (tutto il globo, citta' affollate, un arcipelago a cavallo dell'antimeridiano, calotte polari), con tetti da 15 km a 2000 km: stesso luogo e stesso punteggio in tutti i casi.

**Riproducibilita'.** `BuildPlacesDatasetTest` e `MiniDatasetFixtureTest`: stesso input (anche con le righe mescolate, anche come zip o come testo) = stesso SHA-256. Provato anche con due esecuzioni separate di `gradlew :core-places:buildPlacesDataset`.

## 8. Impronte

| Dataset | Data del dump | Luoghi | Byte | SHA-256 |
|---|---|---|---|---|
| mini-dataset di prova (test) | 2025-02-14 (fittizia, dei file di prova) | 500 | 25.528 | `b8e078e8ee2149b2e3e92b1922894692cdd3f613c739eded316184cac282a565` |
| **reale (asset dell'APK)** | *da compilare dopo il passo 2* | | | *da compilare* |

## 9. Privacy

- `GeoPoint` e `PlaceRef` sono `@Sensitive`: il punto cercato non si conserva e non finisce in nessuna stringa. Nessuna classe del modulo ha un `toString` che stampi coordinate o nomi.
- **Nessun log di coordinate o luoghi**: `NoLogSourcesTest` vieta `println`, `Log`, `GalleryLog`, `printStackTrace` nel codice che gira nell'app. L'unico codice che stampa e' lo strumento sul PC, e solo conteggi e impronte.
- **Nessuna rete** (D24): `NoNetworkSourcesTest`. Lo strumento non scarica nulla; i dump li scarica l'utente.
- Il luogo non lascia il telefono; l'asset e' dello stesso APK, estratto in `noBackupFilesDir/places/` (non entra nei backup).

## 10. Attribuzione (CC BY 4.0)

GeoNames chiede l'attribuzione e che si dica che i dati sono stati modificati. Il testo e' in `PlacesAttribution` (da mostrare in Impostazioni → Informazioni e licenze, a cura di P11):

> Nomi e fusi orari dei luoghi: dati di GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0. I dati sono stati modificati: ridotti, in parte tradotti in italiano e convertiti in un formato compatto.

Licenza: <https://creativecommons.org/licenses/by/4.0/> (si apre solo con un'azione esplicita dell'utente). Gli id sono quelli di GeoNames, il resto e' una trasformazione.

## 11. Dove sta il codice

| Pacchetto | Contenuto |
|---|---|
| `contract/`, `noop/`, `testing/` | (P0) interfacce, null-object, fake |
| `dataset/` | formato MCGP1 (`Mcgp1Format` con il raggio d'influenza, `Mcgp1Writer`, `Mcgp1` con la ricerca), `MicroStates` (i poligoni delle enclave), `PlacesData` (geocoder + fusi + nomi), `PlacesDataset` (apertura), `PlacesStore` (estrazione e impronta), `PlacesDatasetPin` (generato) |
| `tool/` | lo strumento `buildPlacesDataset` (non entra nell'APK di release: nessuno lo referenzia) |

Il task Gradle `buildPlacesDataset` e' in `core-places/build.gradle`; usa le sole classi del modulo e la libreria standard di Kotlin (`ToolIndependenceTest` lo garantisce).
