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
| Distretti di una citta' piu' grande (Manhattan per New York) e localita' molto note sotto i 1000 abitanti (Madonna di Campiglio) | fatto: due regole per principio, trovate sul dataset vero (§4.1, §5, §7.4) |
| **Asset reale nell'APK** | **generato** dal dump GeoNames del 2026-10-12: 161.650 luoghi, 4,98 MB (2,6 MB compressi), SHA-256 in §8. L'impronta e' in `PlacesDatasetPin.kt`, quindi `PlacesDataset.open(context)` ora apre il dataset vero |
| Verifica sulle 200 coordinate | mini-dataset: 100/100 e 100/100. **Dataset vero**: taratura 100/100 col criterio del §7.1; verifica cieca 76/100 cosi' com'e' scritta, 98/100 col criterio (§7.3) |

## 2. Generare l'asset reale: i passi per l'utente

Serve il consenso dell'utente al download (circa **200 MB**, quasi tutti per `alternateNamesV2.zip`). Lo strumento **non scarica niente**: se i file mancano si ferma con un messaggio che dice cosa scaricare.

### Passo 1: scaricare i quattro file a mano

Da <https://download.geonames.org/export/dump/> (GeoNames, licenza **CC BY 4.0**), nella cartella `%USERPROFILE%\.mycloudgallery\geonames\`:

| File | Dimensione | A cosa serve |
|---|---|---|
| `cities1000.zip` | circa 11 MB | i luoghi del mondo con almeno 1000 abitanti o sede amministrativa |
| `IT.zip` | circa 4,4 MB | tutti i luoghi italiani, compresi i comuni sotto i 1000 abitanti |
| `admin1CodesASCII.txt` | circa 0,2 MB | le regioni (codice, nome, id GeoNames) |
| `alternateNamesV2.zip` | circa 205 MB | i nomi alternativi: se ne leggono solo le righe `it` dei luoghi tenuti |

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

Impiega meno di un minuto (legge oltre 1 GB di testo, soprattutto `alternateNamesV2`; sul dump del 2026-10-12 il calcolo e' di 7 secondi). Scrive **due** file, entrambi da versionare:

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

Obiettivo: **meno di 3 MB compressi** (D8: circa 2,5 MB). Sul dump del 2026-10-12: 4.984.292 byte, 2,6 MiB compressi. Se fosse di piu', la leva e' `PlacesDatasetBuilder.classify` (per esempio non tenere i `PPL` italiani sotto una soglia), non il formato.

### Passo 4: provare

```powershell
.\gradlew.bat :core-places:testDebugUnitTest :core-places:lintDebug
```

Con l'asset presente girano in piu':

- `PlacesAccuracyTest.realDatasetTaratura`: le 100 coordinate di `taratura-100.tsv` sul dataset **reale**, soglia 95%. `realDatasetVerificaCieca` stampa `verifica-100.tsv` sul dataset reale senza soglia (e' cieco: si legge, non si ritara). I nomi accettati si giudicano col criterio del §7.1.
- `RealDatasetTest`: i punti centrali di Roma, Milano, Londra, Parigi, New York e Tokyo danno il nome della citta'; dentro i micro-stati risponde l'unita' amministrativa; UTF-8 di apostrofi e diacritici; 100.000 ricerche in meno di 2 s;
- `BuildPlacesDatasetTest.ilPinCommittatoEQuelloDiNessunDatasetOQuelloDellAsset`: l'impronta nel codice e' quella dell'asset;
- `PlacesDatasetOpenTest`: estrazione e apertura sul contesto di Robolectric.

### Passo 5: registrare e committare

La tabella del §8 ha gia' i numeri del dump del 2026-10-12. Committare asset e `PlacesDatasetPin.kt`. Riesecuzione di controllo (stesso dump, stessa impronta):

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
| `places` (17 B ciascuno) | id GeoNames (int32); posizione **relativa alla cella** (2 x uint16, in 1e-5 gradi, quindi circa 1 m); regione (uint16); paese (uint16); fuso (uint16); classe (uint8: i due bit bassi sono la classe, 1 abitato, 2 sede amministrativa, 3 capitale; il bit 4 segna un **distretto** che non compete); popolazione (uint8, logaritmica); lunghezza del nome (uint8) |
| `names` | i nomi UTF-8 nello stesso ordine dei luoghi, senza separatori |
| `regions` (12 B) | id GeoNames della regione, paese, nome |
| `zones`, `countries`, pool | identificativi IANA dei fusi, codici ISO dei paesi, stringhe |

Scelte rispetto alla bozza di progetto: la posizione e' relativa alla cella (4 byte invece di 8 per luogo); il nome si trova sommando le lunghezze dei luoghi che lo precedono nella cella (la ricerca, che e' il percorso caldo, non lo legge mai); l'indice per id (`place(id)`) si costruisce in memoria alla prima richiesta (circa 1,4 MB per 170 mila luoghi) invece di stare nel file. Il tutto per restare nei 4-5 MB.

**Il formato non cambia con la classifica pesata e le enclave**: la popolazione e' gia' nel file (il byte logaritmico di ogni luogo) e da quel byte la ricerca ricava il raggio d'influenza con una tabella di 256 valori; i poligoni dei micro-stati sono dati scritti a mano nel codice (`dataset/MicroStates.kt`), legati al codice paese, e non dipendono dal dump. Per questo resta `MCGP1`. Il bit "distretto" sta nel byte di classe, in un bit che prima era sempre zero: un file generato prima si apre lo stesso (nessun luogo e' un distretto) e un file nuovo ha gli stessi byte di prima per ogni luogo che non lo e'.

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

**Distretti.** Times Square e' a Manhattan (1,5 milioni, raggio 6 km), e il centroide di Manhattan e' piu' vicino di quello di New York (8,8 milioni, raggio 8 km): con la sola classifica Manhattan vinceva di 400 m. Il nome principale deve essere la citta'. Quindi, **per regola** e nello strumento (`tool/DistrictAbsorption.kt`), un luogo Q e' un *distretto* di C, e **non compete**, se:

1. sono dello stesso paese;
2. C ha almeno **3 volte** gli abitanti di Q ("molto piu' grande");
3. Q ha almeno **150.000** abitanti: i luoghi piu' piccoli li gestisce gia' il peso (Segrate, 36.000 abitanti, a 5 km da Milano, resta Segrate; Sesto, Monza, Fiesole idem);
4. le aree d'influenza si toccano: distanza fra i centroidi <= raggio(C) + raggio(Q).

Il distretto resta nel file, con il bit "secondario" nel byte di classe, e si risolve per id (`place(id)`, `labelOfId`): non e' mai la risposta di `nearest`. Sul dump del 2026-10-12 sono 206 (Manhattan, Brooklyn, Queens, le circoscrizioni di Parigi e di Shanghai, Islington, Westminster, i municipi di Citta' del Messico...); **in Italia nessuno**, quindi Roma, Milano, Napoli non cambiano. Resta fuori il Bronx (19 km dal centroide di New York): nel suo centro il nome e' "The Bronx". Il rovescio: una citta' satellite di piu' di 150.000 abitanti dentro il raggio di una molto piu' grande (L'Hospitalet per Barcellona, Gatineau per Ottawa) ne prende il nome.

### 4.2 Micro-stati come enclavi

Con il solo peso, una foto in Piazza San Pietro diventerebbe "Roma" (Roma pesa 8 km, il Vaticano 0,14). I micro-stati sono quindi **poligoni** scritti a mano (`dataset/MicroStates.kt`: vertici approssimati a circa 100 m, in senso orario; sono i contorni di quattro stati, non una copia di nessun dataset):

| Stato | Vertici | Superficie | Note |
|---|---|---|---|
| Citta' del Vaticano (`VA`) | 10 | 0,44 km2 | da Piazza San Pietro (lato est) ai Giardini e ai Musei |
| San Marino (`SM`) | 9 | 61 km2 | dal monte Titano a Serravalle e ai castelli del sud |
| Monaco (`MC`) | 11 | 2 km2 | la striscia costiera da Fontvieille a Larvotto |
| Liechtenstein (`LI`) | 9 | 160 km2 | la valle del Reno da Ruggell a Balzers e i versanti fino a Malbun |

La regola, la piu' semplice che passa i test:

- se il punto e' **dentro** un poligono, **o a meno di 300 m** dal suo confine (`PlacesLimits.ENCLAVE_MARGIN_M`: un GPS sbaglia di decine di metri e i poligoni sono approssimati), vince quel paese: la ricerca guarda solo i luoghi di quel paese, con la classifica pesata, e **solo le sedi amministrative e le capitali** (classe 2 e 3: la capitale e i "castelli" o comuni), non i villaggi: a San Marino, Valdragone (un villaggio di 7.000 abitanti, piu' del capoluogo che ne ha 4.500) non puo' battere la citta' di San Marino; il castello piu' popoloso vicino (Borgo Maggiore) puo'. Se il paese non ha sedi amministrative nel file, rispondono tutti i suoi luoghi;
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
- **si tengono anche le localita' molto note sotto i 1000 abitanti**: `PPL`, `PPLL`, `PPLS` che hanno almeno **10 nomi alternativi diversi** nella colonna `alternatenames` (`PlacesDatasetBuilder.FAMOUS_MIN_ALTERNATE_NAMES`). I nomi in molte lingue sono l'indice di una localita' che tutti conoscono: Madonna di Campiglio (656 abitanti, 12 nomi), Manarola (398, 10), Paestum, Bagno di Romagna, Tiarno di Sotto. Sono 20 in tutta Italia sulle 51.945 localita' sotto i 1000 abitanti (con 5 nomi sarebbero 56, con 3 sarebbero 326): la soglia e' tonda, non scelta per far tornare una riga. Nessuna lista scritta a mano. Breuil-Cervinia (753 abitanti, nessun nome alternativo) non entra: li' si risponde con la frazione vicina o con il comune;
- i **distretti** di una citta' molto piu' grande restano nel file ma non competono (§4.1);
- **non si tengono**: `PPLX` (quartieri e frazioni di una citta': darebbero "Brera" invece di "Milano"), `PPLH`/`PPLQ`/`PPLW` (storici, abbandonati, distrutti), `PPLF`/`PPLR`, i `PPL` sotto i 1000 abitanti che non sono molto noti (non potrebbero mai essere scelti) e tutto cio' che non e' un luogo abitato;
- **nome**: quello italiano di `alternateNamesV2` (`isolanguage` = `it`; preferito, poi non abbreviato, poi non colloquiale, poi il piu' vecchio; gli storici si ignorano), altrimenti quello di GeoNames. Lo stesso per le regioni. Il nome del paese viene da `Locale.ITALIAN` (`Regno Unito`, `Stati Uniti`);
- **fuso**: quello di GeoNames; **versione** del dataset: la data di modifica piu' recente fra i luoghi tenuti, come aaaammgg (dipende solo dall'input).

## 6. I nomi per la UI

`PlaceNames.summary(top, distinct)`: un luogo "Firenze"; due "Firenze e Fiesole"; tre o piu' "Firenze e altri 2 luoghi". Con due luoghi ma uno solo noto: "Firenze e un altro luogo" (mai "altri 1 luoghi"). `label(ref)` e `labelOfId(id)` funzionano per luoghi, regioni e paesi.

## 7. Verifica e prestazioni

**Mini-dataset di prova** (`core-places/src/test/resources/places/geonames-mini/`): 4 file nel formato dei dump di GeoNames (19 colonne, tabulazione), 506 luoghi reali con coordinate, nomi italiani, regioni e fusi: Italia (capoluoghi, localita' di mare e di montagna, isole), confini (Como/Chiasso, Gorizia/Nova Gorica, Ventimiglia/Mentone, San Marino, Vaticano, Campione d'Italia, Monaco, Liechtenstein/Buchs) e capitali e citta' del mondo con i loro esonimi. Sono **dati di fatto scritti a mano, non una copia di GeoNames**; gli id sono **di prova** (da 9.000.001), non quelli di GeoNames. Dentro ci sono apposta anche i casi da scartare (quartieri, storici, frazioni sotto i 1000 abitanti, monti e laghi) e i casi che hanno trovato i difetti sul dataset vero: Valdragone (un villaggio piu' popoloso del capoluogo di San Marino), i borough di New York, Paestum (una localita' molto nota sotto i 1000 abitanti). Lo stesso strumento che costruira' il dataset vero li trasforma in un MCGP1 nei test.

**200 coordinate** (`src/test/resources/places/`): `taratura-100.tsv` per tarare e `verifica-100.tsv` cieco, mai usato per tarare (nessuna coordinata in comune: lo verifica un test). Colonne separate da tabulazione: id, latitudine, longitudine, nomi accettati (separati da `|`), precisioni accettate (`CITY`, `NEAR`, `REGION_ONLY`, `COUNTRY_ONLY`, `NONE` = nessun risultato), fusi accettati (`-` = non si controlla), nota. Una riga e' corretta se nome, precisione e fuso sono fra quelli attesi.

### 7.1 Cosa conta come corretto (il criterio, fissato prima di guardare i risultati sul dataset vero)

Le 200 righe erano scritte per il mini-dataset, dove un luogo e' un comune. Il dataset vero ha anche le localita' (frazioni, borgate, porti), quindi "corretto" va definito per principio, non per quello che esce. La domanda e' "dov'e' stata scattata la foto?", e un risultato e' **corretto** se e' una risposta che direbbe una persona del posto:

1. **Il comune** in cui cade il punto, oppure **una localita' abitata riconoscibile che ne fa parte** (frazione, borgata, porto con nome proprio: Burano, Porto Cervo, Cala Gonone, Linate, Giglio Castello, Porto d'Ischia, Lampedusa). Una localita' piu' fine e' una risposta migliore, non peggiore.
2. **Il nome italiano** del comune o della localita' (esonimo: "Nuova Gorizia" per Nova Gorica, Capodistria, Mentone, Monaco di Baviera, Londra); se non c'e' un nome italiano, quello ufficiale. **Accenti, maiuscole e apostrofi (' e ’) non contano**: il confronto li normalizza ("Cukotka" = "Čukotka"). La forma ufficiale della regione ("Circondario autonomo della Čukotka") vale quanto quella breve.
3. **Sui confini** (di stato o di comune) sono corretti i due lati, con il loro fuso.
4. **Punti fuori dagli abitati** (rifugi, valichi, vette, altipiani): il centroide non dice a quale comune appartiene il punto, e un comune di montagna e' lungo 10-20 km. Sono corretti il comune giusto, i **comuni confinanti con esso** (stessa valle o stesso massiccio) e le loro localita'; la precisione e' `CITY` (entro 15 km) o `NEAR` (entro 50 km).
5. **Precisione.** Quella scritta nella riga e' la piu' grossa che accetto per quel punto. **Una precisione piu' fine nella regione o nel paese attesi e' sempre corretta** (`CITY` o `NEAR` al posto di `REGION_ONLY`; `REGION_ONLY` al posto di `COUNTRY_ONLY`): il dataset vero ha piu' luoghi di quello che si poteva sapere scrivendo la riga, e un luogo vero nella regione giusta dice di piu', non di meno. Nelle righe con precisione "o piu' fine" (suffisso `+`, per esempio `REGION_ONLY+`) il nome puo' essere quello del luogo, della sua regione o del suo paese.
6. **Coordinate sbagliate** (un errore di chi ha scritto la riga) si correggono, e si dice quale.

**Non e' corretto**: un comune non confinante; un quartiere o una circoscrizione al posto della sua citta' (il nome principale di Times Square e' New York, non Manhattan); il nome di un'enclave (Citta' del Vaticano, San Marino, Monaco) per un punto che e' fuori dal suo confine; una frazione o una localita' al posto della capitale dentro un micro-stato; una precisione piu' fine di quella reale (dire "Firenze" a 80 km); un fuso sbagliato.

Una riga e' corretta se nome, precisione e fuso (se indicato) lo sono. Il criterio vale per entrambi i file; **`taratura-100.tsv` si aggiorna secondo il criterio** (ogni riga cambiata e' elencata in 7.2 con il motivo), **`verifica-100.tsv` non si tocca mai**: se sul dataset vero qualche riga non torna, si riporta com'e'.

### 7.2 Le righe di `taratura-100.tsv` cambiate secondo il criterio (21)

Il criterio e' quello del §7.1. Ogni riga cambiata e' qui, con la regola che la giustifica. Solo la taratura: `verifica-100.tsv` non e' stato toccato. (T095, Times Square, non e' fra le righe cambiate: era un difetto del codice, §7.4.)

| Riga | Cosa cambia | Regola e motivo |
|---|---|---|
| T018 | + `Linate` | 1: localita' riconoscibile (aeroporto di Linate) |
| T024 | + `Isola Bella`, `Isola dei Pescatori` | 1: sono le isole del comune di Stresa |
| T036 | + `Porto d'Ischia` | 1: porto del comune di Ischia |
| T039 | + `Giglio Castello` | 1: frazione di Isola del Giglio |
| T040 | + `Lampedusa` | 1: l'abitato dell'isola, nel comune di Lampedusa e Linosa |
| T048 | + `Burano` | 1: isola del comune di Venezia |
| T050 | + `Manarola` | 1: frazione di Riomaggiore; entra nel dataset con la regola "molto nota" (10 nomi) |
| T056 | + `Porto Cervo` | 1: localita' di Arzachena |
| T057 | + `Cala Gonone` | 1: frazione di Dorgali |
| T063 | + `Staffa` | 1: la frazione principale di Macugnaga |
| T066 | + `Ragalna` | 4: comune confinante di Nicolosi sull'Etna, punto fuori dagli abitati |
| T067 | + `San Nicolò` | 1: frazione di Valfurva, sulla strada dello Stelvio |
| T069 | + `Madonna di Campiglio` | 1: localita' di Pinzolo; ora nel dataset (§5) |
| T070 | + `Paquier` | 1: frazione di Valtournenche |
| T072 | + `Pietracamela` | 4: comune del Gran Sasso che confina con L'Aquila, punto fuori dagli abitati |
| T076 | + `Nuova Gorizia` | 2: esonimo italiano di Nova Gorica |
| T078 | coordinata 43,9424 / 12,4578 -> 43,9367 / 12,4464 | 6: la riga era 1,1 km a nord-est della Citta' di San Marino, in mezzo a Valdragone; il centro (Piazza della Liberta') e' quello nuovo. Vale lo stesso per il luogo "San Marino" del mini-dataset |
| T082 | + `Crevatini` | 1: localita' del comune di Capodistria |
| T098 | `COUNTRY_ONLY` -> `COUNTRY_ONLY+` | 5: GeoNames ha una regione ("Isole Svalbard") e la ricerca la dice: piu' fine, nel paese giusto |
| T099 | `REGION_ONLY` -> `REGION_ONLY+` | 5: a 18 km c'e' un villaggio vero (Newtonmore, in Scozia): `NEAR` nella regione giusta |
| T100 | + `Circondario autonomo della Čukotka` | 2: la forma ufficiale italiana della regione (il confronto ignora gia' l'accento) |

### 7.3 Esiti

Sul mini-dataset (`PlacesAccuracyTest`, soglia 95%): **taratura 100/100, verifica 100/100**.

Sul **dataset vero** (asset del 2026-10-12):

| File | Cosi' com'e' scritto | Col criterio |
|---|---|---|
| `taratura-100.tsv` (dopo le righe del §7.2) | **100/100** | 100/100 |
| `verifica-100.tsv` (non toccato) | **76/100** | **98/100** |

La verifica cieca e' stata lanciata dopo le correzioni del codice e **senza cambiare nessuna attesa**. I 24 scarti, e come li giudica il criterio (ognuno con la regola che lo accetterebbe; due restano errori):

| Riga | Atteso | Trovato | Giudizio |
|---|---|---|---|
| V014 | Rho, Milano | Pero | accettabile (4): comune confinante, la Fiera sta a cavallo di Rho e Pero |
| V020 | Pavia | Guinzano | accettabile (1): localita' a 0,9 km dal punto; la Certosa di Pavia e' a 1,2 km |
| V031 | Forio | Serrara Fontana | accettabile (4): comune confinante |
| V033 | Marciana Marina, Portoferraio | Marina di Campo | accettabile (1): la localita' giusta (comune di Campo nell'Elba) |
| V035 | Venezia | Murano | accettabile (1): isola del comune di Venezia |
| V037 | Lipari, Malfa, Santa Marina Salina | Canneto | accettabile (1): frazione di Lipari |
| V039 | Venezia | Lido di Venezia | accettabile (1) |
| V041 | Vernazza, Riomaggiore | Manarola | **dubbio**: Manarola e' frazione di Riomaggiore (uno dei due comuni attesi) ma sta 2,4 km piu' in la di Corniglia; Corniglia (239 abitanti, 7 nomi alternativi) e' sotto la soglia di 10 |
| V045 | Ricadi, Tropea | San Nicolò | accettabile (1): frazione di Ricadi |
| V049 | Castrignano del Capo | Leuca | accettabile (1) |
| V050 | Jesolo | Lido di Iesolo | accettabile (1): la grafia e' quella di GeoNames |
| V052 | Canazei, Moena, Ortisei, Selva | Mazzin | accettabile (4): localita' vicina della Val di Fassa |
| V054 | Ceresole Reale, Cogne | Noasca | accettabile (4): comune confinante |
| V056 | Courmayeur, Chamonix | Les Houches | accettabile (4): comune francese confinante |
| V057 | Castel del Monte, L'Aquila, Teramo | Pietracamela | accettabile (4) |
| V058 | Linguaglossa, Acireale, Nicolosi | Milo | accettabile (4): comune dell'Etna |
| V059 | Ortisei, Castelrotto | Siusi allo Sciliar | accettabile (1): e' il comune dell'Alpe di Siusi |
| V060 | Sestriere | Sauze di Cesana | accettabile (4): localita' del comune confinante |
| V068 | Nova Gorica | Nuova Gorizia | accettabile (2): esonimo |
| V074 | Füssen | Schwangau | accettabile (1): e' il comune del castello di Neuschwanstein |
| V093 | Cusco (`REGION_ONLY`) | Machupicchu (`CITY`) | accettabile (5): piu' fine, nella regione giusta |
| V095 | Honolulu | Waikīkī | accettabile (1) |
| V096 | Waiyevo | divisione Nord (`REGION_ONLY`) | **errore**: nessun luogo di almeno 1000 abitanti entro 50 km da Taveuni nel dataset; la regione e' giusta ma la precisione e' piu' grossa |
| V097 | Marocco (`COUNTRY_ONLY`) | Tata (`NEAR`) | accettabile (5): piu' fine, nel paese giusto |

Sono 22 accettabili su 24: 76 + 22 = **98/100**. I due scarti veri sono V041 (una frazione vicina al posto di quella giusta) e V096 (dati: Taveuni non ha luoghi con 1000 abitanti). Se l'orchestratore vuole la verifica scritta col criterio, le attese vanno aggiornate da lui: qui non si toccano.

### 7.4 I difetti trovati sul dataset vero e corretti nel codice (con test)

- **T095, Times Square -> "Manhattan".** Manhattan batteva New York di 400 m per il suo centroide piu' vicino. Corretto con la regola dei **distretti** (§4.1). Test: `DistrictAbsorptionTest`, `WeightedRankingTest.timesSquareENewYorkNonManhattan`, `RealDatasetTest.ilCentroDelleGrandiCittaEIlNomeDellaCitta` (2-5 punti centrali in Roma, Milano, Londra, Parigi, New York e Tokyo: tutti danno il nome della citta').
- **T078, Citta' di San Marino -> "Valdragone".** Due cause. Una era un errore mio: le coordinate della riga e del mini-dataset erano 1,1 km a nord-est del centro (§7.2). L'altra e' un difetto vero: nei dati di GeoNames Valdragone e' un villaggio (`PPL`) di 6.983 abitanti, piu' del capoluogo (4.500), e dentro un micro-stato un villaggio batteva la capitale. Corretto: dentro un'enclave rispondono solo le sedi amministrative (§4.2). Test: `WeightedRankingTest.dentroUnMicroStatoRispondeLUnitaAmministrativaNonUnVillaggio` e `RealDatasetTest.dentroIMicroStatiRispondeLUnitaAmministrativa`.
- **T069, Madonna di Campiglio -> "Carisolo".** Madonna di Campiglio e' in `IT.txt` (`PPL`, 656 abitanti, 12 nomi alternativi) ed era scartata perche' sotto i 1000. Corretto con la regola delle localita' molto note (§5): niente lista scritta a mano. Test: `BuildPlacesDatasetTest.leLocalitaMoltoNoteSotto1000SiTengonoPerRegola`, `WeightedRankingTest.unaLocalitaSotto1000AbitantiMaMoltoNotaEUnRisultato`, `RealDatasetTest.leLocalitaMoltoNoteSiTrovano`.
- **Apostrofi e diacritici.** I nomi sono memorizzati in UTF-8 corretto: "Porto d’Ischia" con l'apostrofo tipografico (U+2019), "Čukotka" (U+010C), "Città del Vaticano", "Waikīkī" (`RealDatasetTest.inomiConApostrofiEDiacriticiSonoInUtf8Corretto`). I "?" dei rapporti di prima venivano dalla console di Windows (cp1252) che stampava il rapporto, non dal dataset: ora i rapporti scrivono i caratteri non ASCII come `\uXXXX`. Il confronto dei test normalizza accenti, maiuscole e apostrofi (`PlacesAccuracy.normalize`).

### 7.5 Prestazioni, correttezza della ricerca, riproducibilita'

**Prestazioni** (JVM, `PlacesPerformanceTest`, `RealDatasetTest`): 100.000 ricerche in **circa 0,45 s sul dataset vero** (161.650 luoghi; limite del progetto: 2 s) e in circa 0,45 s su 170.000 luoghi sintetici; 100.000 ricerche sparse sul globo sul mini-dataset in meno di 1 s. **Dimensione** dell'asset vero: 4.984.292 byte, 2,6 MiB compressi (33,7 byte per luogo nei dati sintetici, 30,8 nei veri).

**Correttezza della ricerca.** `RingSearchTest` confronta la ricerca con la forza bruta **con lo stesso punteggio** (distanza meno raggio dal byte di popolazione) su dataset casuali (tutto il globo, citta' affollate, un arcipelago a cavallo dell'antimeridiano, calotte polari), con tetti da 15 km a infinito: stesso luogo e stesso punteggio in tutti i casi.

**Riproducibilita'.** `BuildPlacesDatasetTest` e `MiniDatasetFixtureTest`: stesso input (anche con le righe mescolate, anche come zip o come testo) = stesso SHA-256. Provato sul dump vero con due esecuzioni di `gradlew :core-places:buildPlacesDataset` (la seconda con `-PplacesExpectSha`): stessa impronta.

## 8. Impronte

| Dataset | Data del dump | Luoghi | Byte | SHA-256 |
|---|---|---|---|---|
| mini-dataset di prova (test) | 2025-02-14 (fittizia, dei file di prova) | 506 | 25.716 | `0d953814bb173e982de08ac71af829ba891caeb35e61d0ae8b05978a9e9193c3` |
| **reale (asset dell'APK)** | 2026-10-12 (versione 20261012) | 161.650 | 4.984.292 | `a26fcfcdbc2a09f3c0a7cdedfb3a9297399226c183795e37fbfe962b5a7652d8` |

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
| `tool/` | lo strumento `buildPlacesDataset` (non entra nell'APK di release: nessuno lo referenzia), `DistrictAbsorption` (i distretti), `PlacesDatasetBuilder` (la politica del dataset) |

Il task Gradle `buildPlacesDataset` e' in `core-places/build.gradle`; usa le sole classi del modulo e la libreria standard di Kotlin (`ToolIndependenceTest` lo garantisce).
