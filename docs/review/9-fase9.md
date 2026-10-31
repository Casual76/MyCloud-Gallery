# Revisione di sicurezza della Fase 9 (Ricordi: motore, scaffale, scelta delle foto, viaggi e persone, presentazione con musica, valutazione; notifiche dei ricordi; mappa: MapLibre con il cancello di rete verso OpenFreeMap, carta di base offline, punti e bolle, schermate, mini-mappa nel viewer)

| | |
|---|---|
| **Oggetto** | i commit della Fase 9 su `main` da `351a8cc0` (progetto tecnico) a `f9aeef8c`: P0-P4, P6-P11, P13 con le integrazioni e il pacchetto "Test stabili". Moduli: `core-memories`, `feature-memories`, `core-map`, `feature-map` (`net/` soprattutto), `core-personal` (scelte dei ricordi sincronizzate), `core-database` (`MapPointsQueries`), `core-places` (crediti), `core-albums` (`PastTrips`), `:app` (`MemoriesGraph`, `MapGraph`, `di/`, `work/MemoriesWork`, `notify/MemoryNotifier`, `MainActivity`, azzeramento in `graph/LocalData.kt`, Diagnostica, `AlbumsModule` con `HiddenMask`), `tools/check.ps1` (passi 7e, 7h, "niente fake", "resti di debug"), `tools/maps/**`, `feature-map/src/main/assets/map/`. **Fuori perimetro**: P5 (regioni offline: solo il punto d'aggancio, `NoMapRegions`), P12 (solo `app/src/benchmarkRelease`). **In secondo piano**: i pacchetti di correzione della Fase 8 (`fase8/corr-a`, `corr-b`, `corr-c`, `corr-d`, `corr-e`, `corr-formato`), solo per lo stato delle voci di `docs/review/8-fase8.md`. |
| **Riferimenti** | `docs/fase9-progetto.md` (D1-D38, §2.6, §2.8, §4.3, §5.5, §6, risposte Q1-Q9 del §11), `docs/decisioni.md` (sezioni "Fase 9 ..." da P0 a P13, "Test stabili sotto carico"), `docs/sicurezza.md` (C150-C157, C300-C386, L13 riscritta, L260-L274, L300, L410-L495; checklist "Fase 9: ricordi, notifiche, mappa"), `docs/review/8-fase8.md` (formato e stato delle voci). |
| **Attaccanti e guasti** | (1) la **rete della mappa** (§5.5): niente a mappa spenta, un solo host e solo le richieste ammesse, header ripuliti, redirect, TileJSON e risposte ostili, OkHttp e MapLibre al loro posto, MapLibre pigra, nessuna telemetria; (2) **cosa rivela la mappa al servizio** (zona guardata, mini-mappa, avvio, richieste guidate dalle foto); (3) la **"casa"**; (4) i **Nascosti** (E-H1, D31) su ricordi, copertine, presentazione, notifiche, punti, bolle, Luoghi, mini-mappa, "Mostra sulla mappa"; (5) le **notifiche** (schermata di blocco, nomi, `PendingIntent`, blocco app, allarmi, permesso); (6) la **privacy fra familiari** (scelte nell'area privata, nomi, persone dai volti propri); (7) gli **ingressi ostili** (dati sincronizzati, scaffale, canzone, stili, coordinate); (8) lo **storage locale e l'azzeramento**; (9) le **recinzioni nella release**; (10) **licenze e crediti**. |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice. Niente Gradle, nessun telefono, nessun `adb`, nessun NAS, nessuna rete. |
| **Data** | 31/10/2026 |
| **Metodo** | Lettura del codice nel worktree `rev9/sicurezza` a `f9aeef8c`, in quattro passate fatte dallo stesso revisore, senza revisori di supporto: (a) la rete della mappa (`TilePolicy`, `MapGateInterceptor`, `MapWireGuard`, `MapTileHttp`, `TileJsonCache`, `MapLibreInit`, superficie, mini-mappa, stili negli asset, passo 7h); (b) Nascosti e notifiche (filtri finali, registro `HiddenSurfaces`, `MemoryNotifyRun`, `MemoryNotifyPolicy`, `MemoryNotifier`, `MainActivity`, lavoro unico); (c) dati sincronizzati e storage (`MemoriesRecords`, `SyncedMemoriesSettings`, id dei ricordi, `ShelfCodec`, musica, `DataStoreMapSettings`, azzeramento); (d) release e correzioni della Fase 8 (recinzioni, manifest, fake e strumenti di debug nel dex, campione delle correzioni). Seguita ogni via dalla rete, dal NAS o da un altro dispositivo fino a cio' che l'utente vede, e ogni via dei dati dell'utente (posizioni, zone, persone, giorni) verso la rete o un file. **Ogni voce e' stata ricontrollata nel codice**; per ognuna si dice se e' confermata (dal codice) o plausibile (dipende da un comportamento della piattaforma, della libreria o da un innesco raro). Nessun test eseguito. Nessun file del repository modificato a parte questo documento. |
| **Stato** | **Aperta** il 31/10: nessuna voce corretta. Le correzioni proposte sono in fondo ("Pacchetti di correzione proposti"); le voci R1, R2, R4 e I1 chiedono prima una decisione dell'utente. |

---

## Riepilogo

| Gravita' | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 0 | – |
| Media | 0 | – |
| Bassa | 5 | R1 la nota della mappa dice che OpenFreeMap "non riceve la posizione delle foto", ma "Mostra sulla mappa" e il tocco sulle bolle chiedono le tessere attorno alle foto, e "scarica la zona" promette una difesa che non c'e' · R2 la scelta "mappa dettagliata spenta" non regge a un file delle impostazioni rovinato o illeggibile, e prima della prima lettura vale "accesa" · R3 il filtro finale dei Nascosti di ricordi e mappa torna muto se il legame di `HiddenMask` sparisce, e nessuna prova lo vedrebbe · R4 gli id dei ricordi sono hash senza segreto di chiavi con pochi valori e i periodi "Mai" sono date in chiaro: dall'area privata si risale a quali giorni e viaggi si sono visti o non si vogliono rivedere · R5 l'azzeramento toglie `map/` da sotto a DataStore e a MapLibre, e il database delle tessere puo' restare fuori da `map/` |
| Informativa | 6 | I1-I6 (sotto) |

**Confermate e plausibili.** Delle 5 voci R, **3 sono confermate** nel codice (R1, R3, R4) e **2 lo sono in parte**: in R2 la perdita della scelta con un file rovinato o illeggibile e' confermata, la finestra prima della prima lettura e' plausibile (una corsa fra due collettori dello stesso DataStore, di pochi millisecondi); in R5 il file delle impostazioni tolto da sotto a DataStore e' confermato, la riscrittura della vecchia camera e il database di MapLibre fuori da `map/` sono plausibili (dipendono dalla vita del processo dopo l'azzeramento e da come MapLibre sposta il suo database). Fra le informative, I2 e I5 sono plausibili, le altre confermate.

**La rete della mappa regge.** Il cancello e' una funzione pura chiamata per ogni richiesta e ogni redirect prima del DNS; la richiesta che esce e' ricostruita da zero sull'unico host con un `User-Agent` fisso; il DNS risolve solo `tiles.openfreemap.org`; niente proxy, redirect automatici, cookie, cache di OkHttp, nuovi tentativi; risposte con tipo e dimensione controllati due volte (sul filo e dopo la decompressione); un TileJSON ostile non entra in un percorso; MapLibre parte pigra con il nostro client gia' dentro e i suoi log spenti; mini-mappa solo con lo stile senza sorgente online; `check.ps1` 7h prova nel bytecode della release dove stanno OkHttp e MapLibre. Restano: la nota sulla privacy dice meno di quello che succede (R1), la scelta "spenta" non fallisce chiusa (R2), le validazioni della cache con `If-None-Match` (I1), un traffico non spiegato in una prova di P1 da chiudere con la cattura (I2).

**I Nascosti non trapelano dalle superfici della fase.** Le fonti (istantanea senza nascosti, query dei punti con il gruppo intero escluso, scaffale ripulito, `visibleOrThrow` per posizione e mini-mappa) li tolgono gia'; il filtro finale e' legato da P13 e fallisce chiuso (la presentazione tratta un filtro lento come "tutti nascosti"). Il punto debole e' strutturale: senza il legame il filtro finale non fa niente e nessuna prova lo vedrebbe (R3), che e' esattamente lo stato di prima di P13.

**Le notifiche sono discrete.** Di base la notifica non porta niente del ricordo (`GENERIC`), la versione pubblica e' sempre generica, il `PendingIntent` e' immutabile ed esplicito con la sola azione, l'azione si serve sotto il blocco app, nessun allarme esatto, nessun permesso nuovo, il permesso si chiede solo da un tocco. Una nota funzionale sul lavoro in un processo freddo e' in I5.

**I dati personali dei ricordi restano nell'area privata, ma non sono muti come dice C150** (R4): non ci sono nomi ne' titoli, pero' gli id si invertono e i periodi "Mai" sono date.

---

## Risposte al perimetro

1. **La rete della mappa (§5.5).**
   - **A mappa spenta, niente.** `MapNetWiring.state` rilegge a ogni richiesta interruttore di build, scelta del dispositivo e rete (`feature-map/.../map/MapLibreInit.kt:44-51`); `TilePolicy.decide` risponde `OFF` prima di ogni altra cosa che non sia uno scaricamento confermato (`core-map/.../policy/TilePolicy.kt:46-53`), e lo scaricamento oggi non esiste (`app/.../map/MapGraph.kt:79`, `{ null }`). Il rifiuto arriva prima del DNS e di ogni socket (`MapGateInterceptor.kt:45-62`); `MapLibre.setConnected(false)` resta allineato (`MapLibreInit.kt:139-149`). **Resta** R2 (la scelta "spenta" non fallisce chiusa).
   - **Acceso, solo l'host e le richieste ammesse.** `GET`, `https`, host esattamente `tiles.openfreemap.org`, 443, nessun utente, frammento o query; solo `/planet` e l'URL stabile `/planet/v/{z}/{x}/{y}.pbf` in forma canonica con z <= 14 e x, y nei limiti (`TilePolicy.kt:40-98`); la versione del TileJSON passa una regola sua (`Policy.kt:119`, `TileJsonCache.kt:24-39`). La richiesta che esce e' ricostruita da zero (`MapGateInterceptor.kt:194-205`) e ricontrollata subito prima del filo (`MapWireGuard.kt:64-70`); il DNS risolve solo l'host (`MapWireGuard.kt:38-43`); niente proxy, solo TLS moderno, niente redirect automatici, cookie, cache di OkHttp, nuovi tentativi, al piu' 4 richieste insieme (`MapTileHttp.kt:63-83`).
   - **Header.** Escono solo il `User-Agent` fisso ("MapLibre Android", `MapWireGuard.kt:28`), `If-None-Match` e `If-Modified-Since` (al piu' 256 caratteri stampabili) e i tre di OkHttp (`MapGateInterceptor.kt:194-202`, `:215`; `MapWireGuard.kt:123`). Della risposta restano sei header (`MapGateInterceptor.kt:218`). **Resta** I1 (`If-None-Match` riporta un valore scelto dal server).
   - **Redirect, TileJSON e risposte ostili.** Redirect seguiti a mano solo se l'indirizzo nuovo passa la decisione, al piu' 2 (`MapGateInterceptor.kt:88-96`); tipo della risposta controllato, al piu' 2 MiB sul filo (`MapWireGuard.kt:73-119`) e 8 MiB dopo la decompressione (`MapGateInterceptor.kt:148-157`); un TileJSON che non ha esattamente la forma attesa non entra e senza versione nessuna tessera esce (`TileJsonCache.kt:28-38`, `TilePolicy.kt:57-59`); attesa crescente dopo 5xx, 429 e rete assente (`TileJsonCache.kt:126-155`).
   - **OkHttp e MapLibre al loro posto.** Dipendenze solo in `feature-map/build.gradle`; client installato solo da `MapTileHttp.installInMapLibre` (`MapTileHttp.kt:99-103`); passo 7h di `tools/check.ps1` (OkHttp chiamato solo da OkHttp, Okio, MapLibre e `feature/map/net/`; MapLibre solo da `feature/map/`; controllo positivo con la mappa nell'app) e regole R8 contro le fusioni (`feature-map/consumer-rules.pro`). Nessuna altra libreria nuova parla in rete (Timber, gesti, GeoJSON, Turf, fragment); Timber non ha alberi piantati (nessun uso di `Timber` nel codice dell'app).
   - **MapLibre pigra.** Nessuna `MapView` prima di `MapLibreInit.isReady`, che diventa vero solo dopo l'installazione del client (`MapLibreInit.kt:119-142`, `:152-157`; `MapLibreSurface.kt:155-167`); la mini-mappa chiede lo stesso avvio (`MapLibreMiniMap.kt:25-26`); niente sul percorso dell'avvio (`MapGraph` pigro in ogni pezzo).
   - **Telemetria.** MapLibre 13.7.1 (`android-sdk-opengl`) senza chiave, `Logger.NONE`, log di rete spenti; P1 l'ha verificato sul bytecode e con le prove strumentate (`docs/decisioni.md` "Fase 9 P1", verifiche 3 e 4). **Resta** I2 (3,07 MB sull'UID non spiegati in una corsa di prova su sette).
   - Manifest fuso: i permessi di posizione e Wi-Fi che MapLibre dichiara sono tolti dal filtro e vietati per nome (`gradle/verify-release-manifest.gradle:112-121`).
2. **Cosa rivela la mappa al servizio.** La zona guardata (L13): la prima apertura inquadra tutte le foto con lo zoom non oltre 5 (`MapViewport.kt:126-132`, `MapCameraRules.FIRST_MAX_ZOOM`), poi l'ultima camera **solo locale** (`DataStoreMapSettings.kt:85-89`); niente precaricamento (`MapLibreInit.kt:152-162`). La mini-mappa usa sempre lo stile senza sorgente online (`MapStyles.kt:47-48`; gli stili `style-*-offline.json` hanno solo `asset://`). Nessuna richiesta all'avvio (MapLibre parte alla prima mappa o mini-mappa). Le richieste guidate dalla posizione delle foto partono solo da un gesto: aprire la mappa, toccare una bolla, "Mostra sulla mappa" (zoom 13 sulla foto, `MapViewport.kt:140`, `MapController.kt:155-160`). **Resta** R1: la nota dice "non riceve ... la loro posizione", e per questi gesti non e' cosi'.
3. **La "casa".** Nessun modulo della fase legge `home_estimate` o `HomeStore` (ricerca nei sorgenti di `core-memories`, `core-map`, `feature-memories`, `feature-map`, `:app/map`, `:app/memories`); la query dei punti non la nomina (`MapPointsQueries.kt`); le bolle scartano le etichette "casa" e "home" (`BubbleEngine.kt:39`); i viaggi arrivano senza coordinate ne' casa (`core-albums/.../trips/PastTrips.kt`); niente casa nelle notifiche (titoli solo da anni, luogo GeoNames, titolo del viaggio, mese: `MemoryNotifyPolicy.kt:117-136`) ne' nella Diagnostica (`MapDiagnostics.kt`, `MemoriesDiagnostics.kt`: numeri e flag). Nessun problema trovato.
4. **I Nascosti.**
   - **Fonti**: punti dalle sole celle visibili, con il gruppo che ha un nascosto escluso per intero (`core-database/.../dao/MapPointsQueries.kt`, `VISIBLE_CELL`); posizione di un media e mini-mappa solo dopo `visibleOrThrow` (`app/.../map/MapGraph.kt:105-122`) e il filtro finale (`MiniMap.kt`, `place`); "Mostra sulla mappa" non si offre dai Nascosti (`ViewerOverlay.kt:1033`, `PlaceMapInfo.kt:57`) e il controllore lo ricontrolla (`MapController.kt:155-160`); motore dei ricordi su `VisibleMedia` e `hiddenAmongOrThrow` (`MemoriesWiring.kt`, `visibility`), scelta delle foto con `VisiblePhotoPicker`, viaggi con il filtro finale (`PastTrips.kt`, `finalMask = true`).
   - **Filtro finale**: legato da P13 (`AlbumsModule.kt:150`) per le porte opzionali di feature-albums, feature-memories e feature-map; lo usano sezione, pagina, "Tutti i ricordi", presentazione (un filtro lento vale "tutti nascosti": `PlayerController.kt:251`), bolle, griglia della zona, Luoghi, mini-mappa. Notifica: candidati e ricontrollo subito prima di pubblicare con `hiddenOrThrow` (`MemoryNotifyRun.kt:218-237`), revoca su scaffale, nascosti e istantanea (`MemoryNotifyRevocation.kt`, `MemoriesGraph.kt:174-196`).
   - **Registro**: `HiddenSurfaces` ha le righe `MEMORIES` (sezione, pagina, presentazione, persone), `NOTIFICATIONS` e `MAP` (punti, zona, Luoghi, mini-mappa), tutte `Backing.PRODUCTION` e `live`, con i file nuovi nell'elenco `wiring`.
   - **Resta** R3: senza il legame il filtro finale non fa niente, e le prove costruiscono le dipendenze a mano con la maschera.
5. **Le notifiche.** Canale `memories` senza suono ne' vibrazione, `VISIBILITY_PRIVATE` (`AppNotifications.kt`); di base `LockScreenMode.GENERIC` (`core-memories/.../contract/Notify.kt:59`), e in quella modalita' anche la versione privata e' generica, senza foto (`MemoryNotifyPolicy.kt:108`); la versione pubblica e' sempre generica (`MemoryNotifier.kt:146-168`); con "Titolo e foto" mai un nome (`titleOf`: un ricordo `PERSON` resta generico). `PendingIntent` immutabile ed esplicito con la sola azione, senza extra ne' dati (`MemoryNotifier.kt:174-177`); `MainActivity` guarda solo l'azione e ripulisce l'intent (`MainActivity.kt:59`, `:81`, `:97-98`); la richiesta si serve sotto `AppLockGate` (`AppNavHost.kt:215`, `AppMemoriesNavigation.kt:188-196`). Lavoro unico di WorkManager, nessun allarme, nessun permesso nuovo (`work/MemoriesWork.kt`); il permesso solo da un tocco (`MemoriesPermission.kt`, `NotifyPermissionFlow.kt:118-123`). Registro del giorno scritto prima di pubblicare (`MemoryNotifyRun.kt:191`). Nessun problema di sicurezza trovato; I5 e' funzionale.
6. **Privacy fra familiari.** Le scelte (visto, "Non mostrare", "Mostra meno", "Mai", periodi, "Ricordi con le persone") passano da `PersonalStore` nei tipi esistenti `MEMORY` e `SETTING`, quindi nell'area privata dell'account, firmate, con il giro di sempre (`core-personal/.../settings/SyncedMemoriesSettings.kt`); un valore che non si capisce vale "nessuna scelta" (`MemoriesPersonal.kt:160`); le persone solo con l'id casuale, mai il nome. Le persone dei ricordi vengono dall'indice e dalla ricerca per persona dei volti **propri** montati (`app/.../memories/MemoriesPeople.kt`, `IndexMemoryPeople`), solo con un nome, mai nascoste ne' dimenticate. Niente nell'area comune, nell'indice condiviso o nella pagina Famiglia. **Resta** R4 (gli id e i periodi dicono giorni e viaggi a chi legge l'area privata).
7. **Ingressi ostili.** Dati sincronizzati: lettura tollerante e tetti del giro dei dati personali (`PersonalLimits`, `NasPersonalSync`); un dispositivo compromesso dello stesso account puo' solo togliere o nascondere ricordi, come per le altre scelte. Scaffale: un file solo con lunghezza, CRC, versione e ogni campo controllati, UTF-8 rigoroso, al piu' 256 KiB; rovinato = rigenerato (`ShelfCodec.kt`). Canzone: solo un `content://` dal selettore di sistema con `audio/*` e il permesso persistito, mai copiata (`MemoriesMusic.kt`, `AppMemoriesNavigation.kt:158-178`); resta I4. Stili, glifi e sprite: solo `asset://` (gli unici URL negli stili sono gli asset e l'URL stabile delle tessere). Coordinate: finite, dentro i limiti, mai (0, 0) esatto ne' oltre 85 gradi (`core-map/.../contract/Geo.kt:49-63`); la camera salvata e' validata (`DataStoreMapSettings.kt:104-113`).
8. **Storage locale e azzeramento.** `memories/` e `map/` in `noBackupFilesDir` (`MemoriesWiring.kt:63`, `MapLibreInit.kt:101`, `DataStoreMapSettings.kt:101`); backup e Smart Switch esclusi dalle regole di sempre (`allowBackup=false`, `data_extraction_rules.xml`). L'azzeramento ferma ricordi e mappa per primi, annulla il lavoro `memories-notify` e toglie la notifica 1009 anche se viene da un processo di prima, poi toglie `memories/` e `map/` (`graph/LocalData.kt:157-170`, `:216`, `:266-271`). **Restano** R5 (file tolti da sotto a DataStore e a MapLibre, percorso del database non garantito) e I4 (permesso della canzone non rilasciato).
9. **Release.** Valutazione dei ricordi solo in `app/src/debug`, stub vuoto in release e nomi vietati nel dex (`tools/check.ps1`, elenco dei resti di debug); fake di `core-map`, `core-memories`, `feature-map` solo in package `testing`, vietati nel dex (passo 8); nessun fake fuori da `testing/` nei moduli della fase. Recinzioni D12, D30, D34 e F4-D31 invariate (`FeatureFlags.kt`: si aggiunge solo `mapOnline`, che in debug si puo' solo spegnere). Nessun componente esportato nuovo nella release (l'unico ricevitore nuovo e' nel manifest di debug, protetto da `DUMP`). Log: solo codici, classi e numeri nei file della fase (ricerca di `GalleryLog` nei moduli della fase). Nessun problema trovato.
10. **Licenze e crediti.** Credito sulla mappa secondo lo stile ("© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap" online, "Natural Earth · GeoNames" offline: `MapStyles.kt:34-48`, `MapScreen.kt:108`; l'attribuzione di MapLibre e' spenta perche' apre un collegamento); mini-mappa con il credito sotto. Informazioni e licenze: MapLibre Native Android (BSD-2), GeoJSON e Turf, OkHttp, Timber, Okio, stile, Noto Sans, Natural Earth e GeoNames (`AboutLibraries.kt:197-224`, `strings_about.xml:28-36`); licenze e `NOTICE.txt` negli asset (`assets/map/`). Nessuna mancanza trovata.

---

## Bassa

### R1. La nota della mappa dice che OpenFreeMap "non riceve la posizione delle foto", ma "Mostra sulla mappa" e il tocco sulle bolle chiedono le tessere attorno alle foto; e "scarica la zona" promette una difesa che non c'e'

- **Gravita':** bassa. Con Q1 (c) la mappa dettagliata e' accesa su ogni dispositivo senza pagina di consenso, quindi la nota in Impostazioni -> Mappa e' l'unica informazione che un familiare riceve su cosa esce; e dice meno di quello che succede proprio sul punto che la fase deve proteggere (dove la famiglia e' stata). Nessun dato esce oltre quanto dichiarato nel progetto (E-MAP19): e' la promessa scritta che non regge, come R12 della Fase 8.
- **Dove:**
  - `feature-map/src/main/res/values/strings_map_settings.xml:13`: "... riceve l'indirizzo della tua connessione e la zona che stai guardando. **Non riceve le tue foto ne' la loro posizione.**"; la stessa frase e' in L13 (`docs/sicurezza.md:436`: "Non riceve foto, posizioni delle foto") e in §2.8 del progetto;
  - `feature-map/.../map/MapViewport.kt:140` (`FOCUS_ZOOM = 13.0`) e `MapController.kt:137-160`: "Mostra sulla mappa" apre la mappa **centrata sulla foto** a zoom 13; con la mappa online accesa le tessere chieste sono quelle attorno alla foto (a z13 un quadrato di circa 3,6 km alle nostre latitudini, la zona esatta dello scatto con le tessere vicine); il tocco su una bolla (`BubbleTap.ZoomTo`, `MapRoute.kt`) avvicina la camera alle foto della bolla, fino alla griglia;
  - E-MAP19 (§0.2 del progetto) chiede che questo sia "detto nella pagina sulla privacy": la nota non lo dice;
  - `strings_map_settings.xml:21`: "Per non far sapere a nessuno una zona, scaricala: le zone scaricate non si chiedono piu' a OpenFreeMap", mentre le regioni non esistono (P5 non c'e', `NoMapRegions`; L493 dice che "Zone senza Internet" non porta da nessuna parte).
- **Scenario:** un familiare legge la nota, si fida di "non riceve la loro posizione" e usa "Mostra sulla mappa" dalle info delle foto di una visita medica o della casa di un parente: OpenFreeMap riceve, con l'IP di casa e l'ora, le tessere z13 attorno a ogni foto aperta cosi'. Volendo evitarlo, cerca "Zone senza Internet" e non trova niente da scaricare.
- **Confermato:** si' (testo, zoom e percorso nel codice).
- **Correzione proposta:**
  1. riscrivere la nota (e L13, C155, §2.8) dicendo che OpenFreeMap riceve la zona guardata, **anche quella attorno a una foto** quando la si apre sulla mappa o ci si avvicina alle sue foto, e mai le foto, le coordinate esatte o i conteggi; testo da far scegliere all'utente;
  2. finche' P5 non c'e', togliere `mappa_settings_zones_note` (o sostituirla con "Per non far sapere una zona, spegni la mappa dettagliata prima di aprirla");
  3. facoltativo, per l'utente: con la mappa online accesa, "Mostra sulla mappa" puo' aprirsi allo zoom massimo della carta di base (9, nessuna tessera dettagliata) e chiedere le tessere vicine solo al primo gesto di zoom;
  4. una prova sulla stringa (come `AboutScreenTest`) che la nota nomini "Mostra sulla mappa".

### R2. La scelta "mappa dettagliata spenta" non regge a un file delle impostazioni rovinato o illeggibile, e prima della prima lettura vale "accesa"

- **Gravita':** bassa. E' la sola scelta che tiene la zona guardata lontana da un terzo (D24, C154), e fallisce aperta: chi l'ha spenta se la ritrova accesa senza saperlo. Gli inneschi sono rari (file rovinato, errore di I/O, una corsa di pochi millisecondi).
- **Dove:** `feature-map/.../settings/DataStoreMapSettings.kt`:
  - `:122`: `ReplaceFileCorruptionHandler { emptyPreferences() }`: un file rovinato si **sostituisce** con preferenze vuote, e `onlineOf(null)` (`:59-63`) da' `MapOnline.On(byDefault = true)`: la scelta "spenta" sparisce per sempre (il KDoc lo dice: "Un file rovinato ... torna a 'di serie'");
  - `:51`: `store.data.catch { if (e is IOException) emit(emptyPreferences()) }`: un errore di lettura emette "vuoto" e il flusso **finisce**; `online` (`:53`) resta `On` per tutta la vita del processo, qualunque cosa dica il file;
  - `:53`: il valore iniziale della `StateFlow` e' `onlineOf(null)` = `On`; il cancello lo legge a ogni richiesta (`MapLibreInit.kt:44-51`) e `refreshConnectivity` lo usa per `setConnected` (`:145-149`). La carta nasce solo dopo `loaded()` (`MapController.kt:137-138`), che pero' e' un **altro** collettore dello stesso DataStore (`DataStoreMapSettings.kt:66-68`): non c'e' un ordine garantito fra il suo risveglio e quello di `online` (lanciato in `MapGraph.scope`). La finestra e' di pochi millisecondi prima di una `MapView` che sta ancora caricando lo stile: plausibile, non provata.
- **Scenario:** l'utente spegne la mappa dettagliata per non far sapere dove guarda. Un aggiornamento dell'app o un guasto della memoria lascia `map/settings/map.preferences_pb` rovinato (o il primo accesso del processo da' un errore di I/O): alla prossima apertura della mappa lo stile e' quello online, `setConnected(true)`, e le tessere della zona guardata escono; Impostazioni -> Mappa dice "Accesa" ma nessuno avvisa del cambiamento.
- **Confermato:** si' per il file rovinato e per l'errore di lettura (il codice); plausibile per la finestra prima della prima lettura.
- **Correzione proposta:**
  1. un valore "non ancora letto" che per il cancello e per `setConnected` vale **spenta** (per esempio `online` parte da `MapOnline.Off` interno, o `MapNetWiring.state` chiede `settings.loaded`), e la carta online solo dopo la prima lettura riuscita;
  2. errore di lettura: spenta per il processo e un codice in Diagnostica (`MAP_SETTINGS_UNREADABLE`), senza far finire il flusso (riprovare la lettura);
  3. file rovinato: decisione dell'utente fra "spenta finche' non la riaccendi" (fallisce chiusa, consigliata) e "di serie" (come oggi, ma con un avviso nella pagina); in entrambi i casi un codice in Diagnostica;
  4. prove: file rovinato e lettura che lancia con la scelta salvata "off" -> zero `proceed()` dell'intercettore.

### R3. Il filtro finale dei Nascosti di ricordi e mappa torna muto se il legame di `HiddenMask` sparisce, e nessuna prova lo vedrebbe

- **Gravita':** bassa. Le fonti dei dati (istantanea, query dei punti, scaffale ripulito, `visibleOrThrow`) tolgono gia' i nascosti; il filtro finale copre le finestre fra un "Nascondi" e l'aggiornamento (L480, D31). Ma e' lo stato in cui la fase e' rimasta fino a P13 senza che niente se ne accorgesse, e un legame spostato lo riporta in silenzio.
- **Dove:**
  - `feature-map/.../ui/FinalFilter.kt:15-19` e `feature-memories/.../ui/FinalFilter.kt:22-27`, `:43`: senza maschera "nessuno e' nascosto" (`emptySet()`, `return cards`);
  - `feature-map/.../nav/MapDependencies.kt:58` e `feature-memories/.../nav/MemoriesDependencies.kt:54`: `FinalFilter(mask.orElse(null))` sopra un `@BindsOptionalOf HiddenMask`;
  - l'unico legame e' `app/.../di/AlbumsModule.kt:150`, in un modulo degli album; `docs/decisioni.md` "Fase 9 P13", P13-4, dice che "la compilazione di `:app` lo prova", ma un `@BindsOptionalOf` compila anche senza nessun legame: la compilazione prova solo che i tre `@BindsOptionalOf` duplicati sono ammessi;
  - le prove costruiscono le dipendenze a mano con `Optional.of(mask)` (`app/src/test/.../integration/Phase9ScreenshotTest.kt:200-221`; le sonde di `hidden/RealMapProbes.kt` e `RealMemoriesSurfaces.kt` usano `FinalFilter(...)` diretto): nessuna guarda il grafo di Hilt.
- **Scenario:** un pacchetto futuro sposta o toglie il `@Provides` di `AlbumsModule` (per esempio quando gli album smettono di servire la maschera). Tutto compila e i test passano; nella release copertine delle bolle, griglia della zona, sezione e pagina dei ricordi tornano a contare su fonti che si aggiornano dopo la calma (2-3 s), e un media nascosto un attimo fa resta visibile come copertina finche' l'indice non si rifa'.
- **Confermato:** si' (il ripiego, il legame unico, le prove che non lo guardano).
- **Correzione proposta:**
  1. nelle schermate della fase iniettare `HiddenMask` **non opzionale** (la lega sempre `:app`), oppure, se l'opzionale serve ai moduli da soli, un `FinalFilter` che senza maschera **fallisce chiuso** nella release ("tutti nascosti", con un codice in Diagnostica) e apre solo nei test;
  2. spostare il legame in un modulo della galleria (`GalleryModule`), dove sta la maschera;
  3. una prova sul grafo vero (un test Hilt di `:app`, o un controllo di `check.ps1` sul codice generato) che `Optional<HiddenMask>` sia presente per le tre feature;
  4. correggere P13-4 in `docs/decisioni.md`.

### R4. Gli id dei ricordi sono hash senza segreto di chiavi con pochi valori, e i periodi "Mai" sono date in chiaro: dall'area privata si risale a quali giorni e viaggi si sono visti o non si vogliono rivedere

- **Gravita':** bassa. C150 e la tabella dei beni (`docs/sicurezza.md:61`, `:255`) promettono "solo id hash ... mai titoli, luoghi, **date** o nomi fuori dall'app"; "Non mostrare" e "Mai" sono le scelte piu' delicate della fase (un lutto, una separazione, D8). Chi legge l'area privata dell'account vede gia' altro in chiaro (L26, L86: titoli e luoghi degli album dei viaggi), quindi l'esposizione e' della stessa categoria; la promessa scritta pero' non regge.
- **Dove:**
  - `core-memories/.../contract/Identity.kt:144-146`: `MemoryId` = primi 128 bit di SHA-256("mcg-memory\0" + tipo + "\0" + chiave), **senza segreto**; le chiavi (`:81-119`) sono `otd:<aaaa-mm-gg>` (36.500 valori in un secolo), `month:<aaaa-mm>` (1.200), `trip:<inizio>:<fine>` (inizio per durata: poche centinaia di migliaia), `event:<giorno>:<quarto d'ora>` (3,5 milioni), `person:<id>:<anno>` (gli id delle persone sono nelle voci `memories_less:person:*` e nei dati dei volti dello stesso account);
  - `core-personal/.../contract/MemoriesPersonal.kt:15-24`, `:136`: nei dati personali viaggiano `memory/<id>/seen` (con il giorno della visione), `memory/<id>/dismissed` e `memories_never:days:<aaaammgg>-<aaaammgg>`, cioe' **le date del periodo in chiaro** nel nome della voce;
  - i dati personali non sono cifrati dentro la share privata (L86).
- **Scenario:** chi puo' leggere la share privata dell'account (l'amministratore del NAS, un PC con le credenziali SMB dell'account, una copia del NAS) prende le voci `memory/*/dismissed`, calcola in pochi secondi su un PC gli hash di tutti i giorni, mesi, viaggi ed eventi possibili e trova "l'utente non vuole piu' vedere il 14 marzo 2023" o "il viaggio dal 2 al 9 agosto 2022"; dalle voci `memories_never:days:*` legge direttamente i periodi; dalle voci `seen` i giorni dei ricordi aperti e quando.
- **Confermato:** si' (il codice); l'attaccante (accesso in lettura alla share privata) e' quello gia' dichiarato da L26 e L86.
- **Correzione proposta** (decisione dell'utente):
  1. **dichiarare** (consigliata): un limite nuovo ("le scelte sui ricordi nell'area privata dicono a chi legge quella share quali giorni, mesi e viaggi si sono visti o esclusi") e C150 e la riga dei beni riscritte senza "mai date";
  2. oppure **nascondere** gli id con un segreto dell'account che non stia in chiaro sul NAS (un HMAC al posto di SHA-256): serve una chiave comune ai dispositivi dello stesso account che oggi non c'e', e i periodi "Mai" andrebbero cifrati o tenuti solo locali; costo alto per un attaccante che legge comunque gli album dei viaggi.

### R5. L'azzeramento toglie `map/` da sotto a DataStore e a MapLibre, e il database delle tessere puo' restare fuori da `map/`

- **Gravita':** bassa. D37 promette che l'azzeramento toglie cache, regioni e ultima camera ("la cache della mappa dice dove si e' guardato"); la cancellazione dei file c'e', ma due parti vive del processo possono riscrivere o tenere altrove cio' che dovrebbe sparire. Gli inneschi dipendono dalla vita del processo dopo l'azzeramento e da MapLibre.
- **Dove:**
  - `app/.../graph/LocalData.kt:79`: la regola dell'eraser stesso, "DataStore non tollera che gli si tolga il file da sotto"; ma `map/settings/map.preferences_pb` e' un DataStore e si toglie come file con tutta `map/` (`:216`, `:268-271`);
  - `MapGraph.stop()` (`app/.../map/MapGraph.kt`) ferma solo il suo `scope`: il DataStore ha uno `storeScope` suo (`DataStoreMapSettings.kt`, `create`, `storeScope` di serie) e tiene in memoria le preferenze, camera compresa; una scrittura dopo l'azzeramento nello stesso processo (`setWifiOnly`, `disable`/`enable`, `saveCamera`) riscrive **tutte** le preferenze, e la vecchia camera torna sul disco. Succede se il processo sopravvive all'azzeramento (`eraseAccountData`, o un nuovo ingresso in famiglia senza riavvio) e l'utente tocca Impostazioni -> Mappa: plausibile;
  - `MapLibreInit.kt:133-138`: `FileSource.setResourcesCachePath` si chiama **dopo** `MapLibre.getInstance` e con `NoCallback` (`:179-183`), che ignora `onError`: lo spostamento e' asincrono in MapLibre e un errore lascia il database delle tessere (zone guardate) nella cartella di serie di MapLibre, fuori da `noBackupFilesDir/map/`, dove l'eraser non guarda (nessun riferimento a `mbgl-offline.db` nel codice); comportamento della libreria non provato qui: plausibile;
  - il database di MapLibre aperto nel processo non si chiude ne' si svuota prima della cancellazione: le tessere viste restano nella cache in memoria della libreria fino alla fine del processo.
- **Scenario:** l'utente esce dalla famiglia e rientra in un'altra senza chiudere l'app; apre Impostazioni -> Mappa e cambia "Solo sul Wi-Fi": `noBackupFilesDir/map/settings/map.preferences_pb` torna con la camera dell'ultima zona guardata dal vecchio account (spesso vicino a casa: L482).
- **Confermato:** in parte. Il file tolto da sotto a DataStore e l'errore ignorato sono nel codice; la riscrittura dipende dalla vita del processo, il database fuori posto dal comportamento di MapLibre.
- **Correzione proposta:**
  1. `MapGraph.stop()` svuota le impostazioni **attraverso** DataStore (`store.edit { it.clear() }`, un `clear()` in `MapSettings`) prima che l'eraser tolga la cartella, e dopo lo stop nessuna scrittura (`stopped` controllato anche da `DataStoreMapSettings`);
  2. un `MapLibreInit.erase()` che, se MapLibre e' partita, svuota il database con l'API della libreria (`OfflineManager.resetDatabase` o `clearAmbientCache`) prima dei file;
  3. `setResourcesCachePath` con un callback vero: un errore diventa un codice in Diagnostica e la mappa resta sullo stile offline (fallisce chiusa); l'eraser toglie anche i file di serie di MapLibre (`mbgl-offline.db*` nella cartella di serie) come seconda difesa;
  4. F127 guarda anche la cartella di serie di MapLibre e `shared_prefs/` dopo l'azzeramento.

---

## Informative

### I1. `If-None-Match` riporta al server un valore che il server ha scelto

`MapGateInterceptor.kt:215` lascia uscire `If-None-Match` e `:218` tiene `ETag` della risposta, come previsto da §2.8. Un `ETag` diverso per ogni client e' un identificativo che torna a ogni nuova convalida, anche da un altro IP (casa, dati mobili), contro "nessun identificativo" di L13 e C155. Oggi pesa poco: le tessere di OpenFreeMap sono in cache 10 anni e il TileJSON un'ora (`docs/decisioni.md` "Fase 9 P1"), quindi le convalide sono rare; e OpenFreeMap dichiara di non registrare gli IP nei log di accesso. **Proposta** (decisione dell'utente): togliere `If-None-Match` ed `ETag` e tenere solo `If-Modified-Since` e `Last-Modified` (una data non distingue i client), oppure dichiararlo in L264. Confermato.

### I2. Un traffico non spiegato nelle prove di P1

`docs/decisioni.md:10771` (verifica 3 di P1): in una corsa su sette la prova a mappa accesa ha contato **3,07 MB sull'UID** con zero socket e zero DNS del client; mai ripetuto, attribuito al sistema dopo l'installazione, "punto aperto". Finche' F119 e F120 non sono fatte con PCAPdroid sul telefono, "zero traffico a mappa spenta" e "solo `tiles.openfreemap.org`" sono provati sulla JVM e sull'emulatore ma non su un dispositivo vero, e L267 resta l'unica copertura. **Proposta**: F119 e F120 prima di dire chiusa la voce della checklist, con una corsa ripetuta subito dopo un'installazione pulita. Plausibile.

### I3. Il punto d'aggancio di P5 si legge una volta sola

`MapLibreInit.ensure` (`MapLibreInit.kt:119-135`) tiene per tutto il processo la `MapNetWiring` costruita da `MapGraph.wiring()` (`MapGraph.kt:79`) con il valore di `MapGraph.regions` di quel momento (`:69`); `MapNetWiring.regions` e' un `val` e `covered = regions::covers` resta legato al vecchio oggetto. Oggi e' `NoMapRegions` e non apre niente (nessuna regione, `downloadScope = { null }`, `openRegions` vuoto: `AppMapNavigation.kt:94`). Ma se P5 assegna `MapGraph.regions` dopo la prima mappa o mini-mappa del processo, `COVERED` non scatta mai e le zone scaricate si chiedono online (contro D26 e C155). **Proposta** per il brief di P5: `MapNetWiring` legge le regioni a ogni richiesta (`regions: () -> MapRegions`), e una prova "regione legata dopo l'avvio di MapLibre -> `COVERED`". Confermato (oggi senza effetti).

### I4. Il permesso sulla canzone scelta non si rilascia mai

`AppMemoriesNavigation.kt:158-163` prende il permesso persistito con `takePersistableUriPermission`; nessun codice chiama `releasePersistableUriPermission`, ne' quando l'utente cambia canzone ne' nell'azzeramento (che toglie solo il file `memories/music`). L'app tiene il diritto di leggere ogni file audio mai scelto, e l'elenco dei permessi del sistema conserva gli URI anche dopo "dispositivo azzerato". **Proposta**: rilasciare il vecchio permesso a ogni cambio e tutti quelli dell'app (`persistedUriPermissions`) nel passo "ricordi" dell'azzeramento. Confermato.

### I5. In un processo nato per il lavoro delle notifiche i ricordi non sono ancora montati (funzionale)

`MemoryNotifyWorker.doWork` (`work/MemoriesWork.kt:112`) esce con `success` se `slot.runner` e' vuoto; `slot.runner` si riempie al montaggio dei ricordi (`MemoriesGraph.kt:176`), che aspetta la famiglia pronta e il primo disegno (al piu' 1,5 s dall'avvio del processo). Quando WorkManager sveglia l'app per l'ora scelta e l'app non e' gia' in memoria, il lavoro trova lo slot vuoto e quel giorno non c'e' notifica; il montaggio che segue ripianifica per l'indomani. Non e' un problema di sicurezza (fallisce in silenzio, senza mostrare niente), ma puo' rendere le notifiche rare su un Samsung che chiude le app in secondo piano. **Proposta** per la revisione funzionale: il lavoro chiede `ensureLive()` con un tempo massimo invece di leggere lo slot. Plausibile.

### I6. Le correzioni della Fase 8 entrate nel periodo

Lo stato in testa a `docs/review/8-fase8.md` corrisponde a quello che i pacchetti dicono: A (R1, R11, R12, R13, I1, I6), formato (R3-R8, I5, I7), D (R2, R9, R10, I2, I4; I3 dichiarata), con B, C ed E sulle voci della revisione funzionale. A campione, nel codice: R1 (l'interruttore scorre le generazioni sul disco e fallisce chiuso se il disco non si legge: `core-faces/.../switch/DefaultFacesSwitch.kt:296-345`), R4 (la `seq` dentro `TrustLimits.MAX_VERSION`: `core-trust/.../data/FacesDocs.kt:49`, `:229`), I7 (`PrePublish.ProceedWithout` con codice stabile: `core-personal/.../contract/Personal.kt`), il ricevitore degli scenari di E solo nel manifest di debug e protetto da `DUMP`. Nessun buco nuovo visto. **Resta aperta** I8 (documento finale: C39 e il commento di `FacesStores`).

---

## Cose fatte bene

- **Il cancello decide tutto e prima di tutto.** Una funzione pura per ogni richiesta e ogni redirect, prima del DNS; la richiesta che esce e' **ricostruita** (non ripulita) sull'unico host; un secondo controllo sul filo; DNS che risolve un nome solo; tetti sul filo e dopo la decompressione; un TileJSON ostile non entra in un percorso; i rifiuti "per adesso" e "per sempre" distinti per MapLibre (niente raffiche).
- **MapLibre tenuta corta.** Partenza pigra con il client gia' dentro, `Logger.NONE` e log di rete spenti (nessuna tessera nel logcat), precaricamento a zero, `setConnected` allineato, attribuzione che non apre il browser, permessi di posizione della libreria tolti e vietati, dex controllato con un controllo positivo e regole R8 che rendono il controllo vero.
- **La mini-mappa non puo' andare in rete per costruzione**: non esiste uno stile mini con la sorgente online.
- **Camera e casa.** Prima apertura a zoom 5 al piu', ultima camera solo locale e validata, nessuna lettura della casa, nessuna etichetta "casa" nelle bolle.
- **Nascosti che falliscono chiusi**: `visibleOrThrow` per la posizione, gruppo con un nascosto escluso per intero nella query, filtro lento della presentazione = "tutti nascosti", notifica ricontrollata subito prima di pubblicare e revocata ai cambi.
- **Notifica senza niente dentro di base**, versione pubblica sempre generica, `PendingIntent` immutabile con un'azione sola, richiesta servita sotto il blocco app, registro del giorno prima della pubblicazione, nessun allarme esatto.
- **Codec dello scaffale** stretto (CRC, lunghezze, UTF-8 rigoroso, nessun nome) e **lettura delle scelte** tollerante campo per campo.
- **Diagnostica e log** di soli numeri e codici; i contatori della rete per livello `z`, mai `x` e `y`; `toString()` senza coordinate in tutti i tipi della mappa.

---

## Da provare sul campo

- **F119 e F120** con PCAPdroid in modalita' "solo questa app" (I2): mappa spenta (zero byte verso Internet), accesa (solo `tiles.openfreemap.org`), ripetuta subito dopo un'installazione pulita.
- **R2**: con la mappa dettagliata spenta, un `map.preferences_pb` rovinato a mano (build di debug, `run-as`) e un riavvio: oggi la mappa torna accesa; dopo la correzione resta spenta e la Diagnostica lo dice.
- **R5 e F127**: dopo l'azzeramento, `run-as ... ls files/ no_backup/ shared_prefs/` senza database di MapLibre (`mbgl-offline.db*`) fuori da `no_backup/map/` e senza `map/settings/`; poi, nello stesso processo, un cambio di "Solo sul Wi-Fi": il file non deve riportare una camera.
- **R1**: con la mappa online accesa e PCAPdroid, "Mostra sulla mappa" da una foto: le richieste sono le tessere z13 attorno alla foto (per scrivere la nota giusta).
- **F117**: tocco sulla notifica con il blocco app attivo; un `am start -a dev.pampa.mycloudgallery.action.OPEN_MEMORIES` con extra e dati da `adb`: al piu' la pagina Ricordi dietro il blocco.
- **F122**: un media nascosto con la mappa aperta sparisce da bolle, griglia, Luoghi e mini-mappa.

---

## Checklist della Fase 9 (`docs/sicurezza.md` §7, "Fase 9: ricordi, notifiche, mappa")

- [ ] **Ricordi generati sul dispositivo, senza rete ne' NAS; nei dati personali solo id hash, giorni e scelte (C150; P0, P6, F126).**
  - Generazione: regge (moduli puri, `Phase9ArchitectureTest`). Dati personali: niente nomi ne' titoli, ma gli id si invertono e i periodi sono date (R4). Manca F126.
- [ ] **Nascosti mai in ricordi, presentazione, notifiche, mappa, Luoghi, mini-mappa (C157).**
  - Regge (risposta 4); il filtro finale dipende da un legame non provato (R3). Mancano F113, F116, F122.
- [ ] **Punti della mappa: solo celle visibili con le coordinate effettive, gruppo con un nascosto escluso per intero, indice solo in memoria, `toString()` e log senza coordinate ne' id (C270).**
  - Regge (`MapPointsQueries`, `Geo.kt`, `GeoMapPoints`).
- [ ] **Notifiche: di base al piu' 3 a settimana, al piu' una al giorno; generiche di default sulla schermata di blocco; mai nomi; tolte per un nascosto o uno scarto (C152).**
  - Regge (risposta 5). Mancano F115-F117; I5 per la revisione funzionale.
- [ ] **Apertura dalla notifica con un'azione fissa e nessun dato; `EntryHardeningTest` esteso (C151).**
  - Regge. Manca F117.
- [ ] **Mappa: carta di base offline; mappa online accesa di default con l'interruttore e la nota; zero traffico a mappa spenta (F119); accesa solo `tiles.openfreemap.org` (F120); L13 riscritta (C154, C155).**
  - Il cancello regge (risposta 1). La nota e L13 dicono meno del vero (R1); la scelta "spenta" non fallisce chiusa (R2); `If-None-Match` (I1); F119 e F120 mancano (I2).
- [ ] **Regioni offline: scaricamento solo da un tocco, catalogo fisso, zone scaricate mai chieste online (F121).**
  - Fuori perimetro (P5 non c'e'); il punto d'aggancio non apre niente, ma si legge una volta sola (I3).
- [ ] **La casa non entra nella mappa: nessun segno, camera iniziale lontana, mini-mappa offline (C156).**
  - Regge (risposte 2 e 3).
- [ ] **"Mostra meno", "Mai" e "Non mostrare" immediati e sincronizzati; lapidi alla cancellazione delle persone; nessun filtro automatico (C153).**
  - Regge sul codice (`SyncedMemoriesSettings`, `PeopleLessJanitor`, `FacesPeopleSwitch` che non scrive lapidi su uno stato non confermato). Mancano F114, F130.
- [ ] **Valutazione dei ricordi solo debug, di sola lettura, con i giudizi sul telefono e solo numeri esportati (C360, L470).**
  - Regge nella release (stub vuoto, nomi vietati nel dex). Manca F125.
- [ ] **Log, Diagnostica ed esportazione senza titoli, luoghi, date, nomi, coordinate, tessere (F126).**
  - Regge sul codice (risposte 3 e 9). Manca F126.
- [ ] **Dipendenze nuove approvate e fissate con l'impronta; licenze e attribuzioni nell'app; musica libera solo dopo l'approvazione.**
  - Regge (risposta 10; `MusicPackCatalog.TRACKS` vuoto, `install` risponde `false`).

Delle voci "per ogni fase, sempre":
- nessun permesso Android nuovo, nessun componente esportato nuovo nella release; un host nuovo dichiarato (`tiles.openfreemap.org`); le dipendenze nuove (MapLibre, OkHttp e i transitivi) confinate in `feature-map` e controllate da `check.ps1`;
- questa revisione vale come passo di `security-review` della Fase 9. Va insieme alla revisione funzionale di fine fase.

---

## Pacchetti di correzione proposti

- **Pacchetto A, "mappa: scelta, nota e azzeramento"** (prima di F119-F121 e F127, che li incontrano): **R1, R2, R5**, con **I1** se l'utente sceglie di togliere `If-None-Match` (stesso codice: `DataStoreMapSettings`, `MapLibreInit`, `MapGraph.stop`, `MapGateInterceptor`, le stringhe di Impostazioni -> Mappa, `LocalData`).
- **Pacchetto B, "ricordi: filtro finale e musica"** (piccolo, si puo' fare con A): **R3** (maschera non opzionale o filtro che fallisce chiuso, legame nella galleria, prova sul grafo), **I4** (rilascio dei permessi della canzone).
- **Nel brief di P5**: **I3** (regioni lette a ogni richiesta, con la prova).
- **Revisione funzionale**: **I5** (il lavoro delle notifiche in un processo freddo).
- **Documenti** (con l'ultimo pacchetto): L13, C155 e §2.8 riscritti (R1); C150 e la riga dei beni, e un limite nuovo (R4, se si dichiara); P13-4 in `docs/decisioni.md` (R3); L264 (I1, se si dichiara); la nota di P1 su I2 chiusa dopo F119.

**Decisioni che servono all'utente:**
- **R1**: il testo della nota su cosa riceve OpenFreeMap (con "Mostra sulla mappa"); se togliere la nota sulle zone finche' P5 non c'e'; se "Mostra sulla mappa" con la mappa online deve aprirsi prima sulla sola carta di base.
- **R2**: con il file delle impostazioni rovinato, la mappa dettagliata resta **spenta** finche' l'utente non la riaccende (fallisce chiusa, consigliato) oppure torna **di serie** (accesa) con un avviso.
- **R4**: **dichiarare** che le scelte sui ricordi dicono giorni e viaggi a chi legge la share privata (consigliato), oppure un pacchetto che nasconde gli id con un segreto dell'account.
- **I1**: togliere `If-None-Match` ed `ETag` dalle richieste della mappa, oppure dichiararlo.


---

## Stato dopo il pacchetto ricordi e notifiche (31/10)

Pacchetto "ricordi e notifiche" (branch `fase9/corr-ricordi`; dettaglio, decisioni e prove in `docs/decisioni.md`, "Fase 9, correzioni dopo le revisioni: ricordi e notifiche (31/10)"). Le tabelle sopra non sono riscritte.

| Voce | Stato | Come |
|---|---|---|
| **R3** filtro finale muto senza il legame di `HiddenMask` | **Corretta** | `MemoriesDependencies` e `MapDependencies` chiedono la maschera senza `Optional`; `FinalFilter` non ammette una maschera nulla (nessun ripiego silenzioso); il legame e' in `GalleryModule` (C413); `HiddenMaskWiringTest` guarda il codice generato da Dagger e i sorgenti; P13-4 corretto in `docs/decisioni.md`. Nota: feature-albums non nomina la maschera (il "tre" di P13-4 era sbagliato). |
| **R4** id con pochi valori e periodi "Mai" in chiaro nell'area privata | **Dichiarata** (decisione del 31/10) | Nessun cambio al codice. Limite nuovo L520; C150, la riga dei beni (§2) e la checklist riscritte senza "mai date". |
| **I4** permesso della canzone mai rilasciato | **Corretta** | `PermissionReleasingMusicSettings` rilascia il permesso della vecchia a ogni cambio; l'azzeramento rilascia tutti i permessi persistiti, nel passo "ricordi" e nel passo che gira sempre (C414). |
| **I5** il lavoro delle notifiche in un processo freddo | **Corretta** | Vedi NT-1 della verifica funzionale (C410): attesa del montaggio senza il primo disegno e della Libreria, al piu' 20 s. |

Restano per l'altro pacchetto R1, R2, R5, I1 e I3; I2 e F119-F120 sul telefono.

---

## Stato dopo il pacchetto mappa e rete (31/10)

Pacchetto "mappa e rete" (branch `fase9/corr-mappa`; le decisioni dell'orchestratore del 31/10 sono in `docs/decisioni.md`, sezione "Fase 9, correzioni dopo le revisioni: mappa e rete (31/10)"). Le tabelle sopra non sono riscritte.

| Voce | Stato | Cosa e' stato fatto | Prova |
|---|---|---|---|
| R1 nota della mappa | **Corretta** (punti 1, 2 e 4) | La nota (versione 2) dice che OpenFreeMap riceve la zona guardata **anche attorno a una foto** aperta con "Mostra sulla mappa" o avvicinandosi alle sue foto, e mai foto, coordinate esatte o quante sono; `mappa_settings_zones_note` dice "Per non far sapere una zona, spegni la mappa dettagliata prima di aprirla" finche' P5 non c'e'; L13, C155, §2.8 aggiornati. **Punto 3 (aprire "Mostra sulla mappa" sulla sola carta di base) non fatto: decide l'utente**, il comportamento non cambia. | `MapPrivacyNoteTest`, `MapScreensTest` |
| R2 scelta "spenta" | **Corretta** | Prima della prima lettura riuscita il cancello e `setConnected` valgono "spenta" (un solo lettore aggiorna tutte le `StateFlow`, `loaded()` torna a valori gia' scritti); errore di lettura = spenta per il processo con `MAP_SETTINGS_UNREADABLE`, il flusso non finisce e la lettura si riprova; file rovinato = spenta finche' non la riaccendi (segno `damaged`, `MAP_SETTINGS_DAMAGED`, riga nella pagina). | `MapFailClosedTest` (zero richieste dal cancello con file rovinato, lettura che lancia e prima lettura lenta) |
| R5 azzeramento | **Corretta** (punti 1-3; il 4 e' F127) | `MapGraph.stop()` svuota le impostazioni attraverso DataStore (`MapSettings.clear()`) e il database di MapLibre con `OfflineManager.resetDatabase` prima della cartella, poi nessuna scrittura; `setResourcesCachePath` con un callback vero (errore = rete chiusa, stile offline, `MAP_CACHE_UNMOVED`); l'eraser toglie anche `mbgl-offline.db*` dalle cartelle di serie. | `MapEraseTest`, `MapFailClosedTest`, `MapWiringAndEraseTest` |
| I1 `If-None-Match` | **Corretta** | Tolti `If-None-Match` ed `ETag` (in uscita e in cache): restano `If-Modified-Since` e `Last-Modified`. | `MapTileHttpTest`, `MapGatePropertyTest`, `MapWireGuardTest`, `MapNetDeviceTest` |
| I3 punto d'aggancio di P5 | **Corretta** | `MapNetWiring` legge le regioni a ogni richiesta (`regions: () -> MapRegions`). | `MapWiringAndEraseTest` (regione legata dopo l'avvio: `COVERED`) |
| R3, R4, I2, I4, I5, I6 | non di questo pacchetto | R3 e I4 sono del pacchetto "ricordi"; R4 e I2 aspettano l'utente e le prove F119-F120; I5 e' della revisione funzionale. | - |

Da guardare sul telefono (F127 ampliata, F119, F120): vedi `REPORT.md` e `docs/decisioni.md`.
