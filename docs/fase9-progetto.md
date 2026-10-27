# Fase 9 di MyCloud Gallery: progetto tecnico (Ricordi, notifiche, mappa)

> Base per i brief dei sub-agent. Progetto del 27/10/2026. Fonti:
> - piano: FASE 9 (9.1, 9.2, 9.3 e il "Fatto quando" della fase), §1 (glossario: dati personali, replica locale, "casa" dedotta), §2
>   ("Offline", "Extra": ricordi, notifiche, mappa online più regioni offline), §3 (proposte **3**: le regole valgono solo per il lavoro in
>   background; **9**: nessuna telemetria; **12**: ricordi al massimo una notifica al giorno), §4 (fluidità, consumi, accessibilità, nessuna
>   telemetria), §6 (E-H1, E-L4, E-L7, E-M8, E-M9, E-P5, E-P10, E-P13, E-O4, E-R3, E-R13, E-V6, E-AL16, E-F28), §7 (beni: luoghi e "casa";
>   privacy fra familiari; nessun servizio di terzi; L13 "Telemetria e mappa"), FASE 10 (10.1 widget dei ricordi, 10.3 accessibilità);
> - i progetti delle fasi precedenti: `docs/fase3-progetto.md` (D8 luoghi offline, **D18** mini-mappa rimandata alla 9.3, pannello info),
>   `docs/fase4-progetto.md` (§2.9 dati personali: "date di ultima visione dei ricordi, campo pronto per la Fase 9", Q9), `docs/fase6-progetto.md`
>   (§2.9: vettori e "Trova simili" per i ricordi, dizionario dei luoghi per mappa e ricordi, ritagliatore per le copertine; D24, D27, D29,
>   D39 `Today`), `docs/fase7-progetto.md` (D18–D20 casa e viaggi, D22 Nascosti, D33 Raccolte con "Ricordi" riservata, §2.9 "Per le fasi
>   successive"), `docs/fase8-progetto.md` (§0 come modello, D22 id stabili, D25 copertine al momento, **D40** e §2.11: ricordi con le
>   persone, "mostra meno", notifiche senza nomi);
> - `docs/decisioni.md` (Fase 7 P11, P12, P13; Fase 8 P0–P11), `docs/formato-nas.md` (§5.5-bis dati personali), `docs/modello-dati.md`
>   (§14, §15), `docs/sicurezza.md` (§1, §2, §4 C17, C19, C21, C27, C31, §4-bis, §6 L13, L63, §7 "Fase 9"), `docs/grafica-progetto.md`
>   (§12.13, §12.14), `docs/android.md` (Tema 1, Tema 3, "Novità di Android 17"), `docs/regole-attivita.md` (§2, §6), `docs/luoghi.md`,
>   le revisioni in `docs/review/`;
> - il **codice** da cui la fase parte, verificato il 27/10 sul commit `43a99bae` (branch `fase9/progetto`): elenco al §0 e al §7.
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F8-Dn`, `F7-Dn`, `F6-Dn`, `F4-Dn`, `F3-Dn` = decisioni delle fasi precedenti; `Fn` =
> prova sul campo (la Fase 8 arriva a F110, qui **F111–F130**); `Qn` = domanda del §9; `Pn` = pacchetto del §4.4; `Ln` = limite di
> `docs/sicurezza.md` (in `main` il massimo è L231: qui **L232–L245**); `Cn` = contromisura (in `main` il massimo è C127: qui
> **C128–C135**). La Fase 8 è ancora aperta (P12, P13, revisioni): se nel frattempo prende altri numeri, **P0 di questa fase rinumera** in un
> passo solo e lo scrive in `docs/decisioni.md`, come ha fatto P0 della Fase 8. "[stima]" = numero non misurato; "[da verificare]" = fatto
> che questo documento non ha potuto controllare (niente rete per il brief) e che P1 o P2 controllano prima di usarlo.
>
> Contesto:
> - **Le Fasi 5, 6 e 7 sono chiuse nel codice**, con le revisioni di fine fase chiuse. **Della Fase 8 sono in `main` P0–P11**; sono in corso
>   **P12** (banco delle interruzioni) e **P13** (integrazione in `:app`: oggi `:app` vede `core-faces` e `feature-people` solo in
>   `testImplementation`, `app/build.gradle` righe ~292–294), poi le revisioni di fine fase. **I ricordi con le persone aspettano P13 della Fase
>   8**; tutto il resto della Fase 9 no.
> - **Le prove sul telefono sono indietro**: aperte F46–F71 (Fase 6), F72–F93 (Fase 7), F94–F110 (Fase 8). **La Fase 9 non le aspetta.** Le sue
>   prove sul campo (§5.6) girano **da sole** sull'S25 (build debug, account `test`) e sull'emulatore, si raccolgono insieme a quelle in sospeso,
>   e le parti con il tablet stanno in **un solo momento breve**.
> - **Le share vere sono ancora "solo locali"** per indice e dati personali (F4-D31): le voci nuove dei ricordi (visti, scartati, "mostra
>   meno") viaggiano fra i dispositivi solo dove viaggiano già i dati personali; altrove valgono su ogni dispositivo, subito.
> - **In parallelo**: il filone grafico. La Fase 9 consegna stati e contratti con composable fatte solo con i componenti G2 (D34); la
>   presentazione animata e le bolle della mappa sono le due parti dove il filone grafico avrà più da fare (§1.5).

## 0. Sintesi

**Che cosa consegna la Fase 9**
- **Ricordi generati sul dispositivo** (9.1): "N anni fa oggi", viaggi ed eventi passati (dalla Fase 7), persone con un nome (dalla Fase 8,
  se attiva), "Il meglio del mese". Nessun servizio esterno, nessun modello nuovo: date, luoghi offline, viaggi rilevati, persone, vettori
  della Fase 6 e segnali dell'utente (preferiti, modifiche, album). Si generano di notte in carica e, se serve, in un passo leggero all'ora
  della notifica (D3, D4).
- **Foto scelte bene senza capire il contenuto** (9.1): esclusi screenshot, registrazioni dello schermo, documenti, immagini animate,
  ritagli, date stimate; punteggio da segnali dell'utente, luce e nitidezza ricavate dalla miniatura; doppioni e scatti quasi uguali tolti
  con i vettori (o con la firma visiva dove i vettori non ci sono); varietà nei giorni e nei luoghi (D5–D7).
- **Ricordi tristi o sbagliati evitati con i comandi dell'utente**: "Non mostrare questo ricordo", "Mostra meno" e "Mai nei ricordi" per una
  persona o un periodo, viaggi scartati che non tornano; con Q6 in più un filtro prudente sul dispositivo (D8, D11).
- **Stabilità**: un ricordo pubblicato non cambia sotto gli occhi (si possono solo togliere elementi diventati nascosti o eliminati); id
  deterministico, così "visto" e "non mostrare" valgono su tutti i propri dispositivi (D9, D10).
- **Presentazione**: pagina del ricordo (stessa griglia e stesso viewer della Libreria) e **presentazione animata** a schermo intero, fluida
  a 120 Hz, con le animazioni ridotte rispettate; musica secondo Q3 (D13).
- **Raccolte → Ricordi** al posto del segnaposto `memories`, e una sezione nuova **Luoghi** con la mappa e i luoghi più fotografati (D14,
  D30).
- **Notifiche dei ricordi** (9.2): al massimo una al giorno, all'ora scelta, solo se c'è un ricordo bello e non visto, disattivabili; testo
  generico sulla schermata di blocco di default (impostabile), mai nomi di persone; il tocco apre i Ricordi di oggi senza deep link con
  dati; un ricordo diventato nascosto toglie la notifica (D15–D20).
- **Mappa** (9.3): foto raggruppate per zona → griglia → viewer, anche per i media solo sul NAS, fluida con 100 mila elementi. **Carta di
  base offline** nell'APK (nessun traffico), **mappa online OpenFreeMap** solo se l'utente la accende sul suo dispositivo dopo una pagina
  che dice cosa esce, **regioni offline** scaricate a richiesta; un solo host nuovo, `tiles.openfreemap.org`, dietro un cancello di rete
  scritto da noi e provato (D21–D29).

**Moduli nuovi**
- `core-memories` (JVM puro): contratti, generatori dei ricordi, scelta delle foto, scaffale stabile, impostazioni, decisioni sulle
  notifiche. **Non vede** rete, NAS, `core-faces`, `core-search`, `core-albums`, `core-ai` (porte legate da `:app`).
- `feature-memories`: sezione Ricordi delle Raccolte, pagina del ricordo, "Tutti i ricordi", presentazione, fogli "Mostra meno",
  pagina Impostazioni → Ricordi e notifiche.
- `core-map` (JVM puro): punti della mappa, indice e raggruppamento, catalogo e stime delle regioni, **politica delle richieste di rete**
  (funzione pura), impostazioni e consenso della mappa online.
- `feature-map`: schermata della mappa (MapLibre), bolle con le miniature, griglia di una zona, sezione Luoghi, pagina sulla privacy,
  regioni offline, mini-mappa per il viewer; il pacchetto `feature-map/.../net/` è **l'unico** codice di rete della fase.

**Aggiunte ai moduli esistenti**: `core-personal` (campi nuovi dentro `MEMORY` e `SETTING`, nessun tipo nuovo), `core-albums` (porta
`TripMemories` in `trips/`, senza coordinate), `core-database` (query dei punti della mappa, **schema v6 invariato**), `feature-library`
(codice di sezione `places`), `feature-viewer` (riga "Mostra sulla mappa" e mini-mappa come porta), `feature-settings` (due voci),
`core-common` (`DiagSection.MEMORIES` e `MAP`, `FeatureFlags.mapOnline`, `Phase9ArchitectureTest`, eccezione di rete dichiarata),
`feature-diagnostics`, `:app` (grafi, lavori, canale "Ricordi", `MainActivity`), `benchmark` (scenari `ricordi` e `mappa`), `tools/`.

**Quattordici pacchetti** (§4.4). Opus per i due fondanti o rischiosi: **P0** (fondamenta, contratti, recinti, eccezione di rete
dichiarata) e **P1** (libreria delle mappe e cancello di rete: l'unica parte che parla con Internet); sonnet per gli altri dodici. Tutti
con un **agente interno**.

**Tappe**
- **M1** (9.1 sul telefono): sull'S25 (debug, `test`) Raccolte → Ricordi con "N anni fa oggi", viaggi, il meglio del mese; pagina,
  presentazione, "Mostra meno", nascosti mai.
- **M2** (9.2 e sincronizzazione): notifica all'ora scelta, schermata di blocco, apertura dalla notifica, ricordo nascosto dopo la notifica;
  visti e "mostra meno" che viaggiano verso l'emulatore; ricordi con le persone dopo P13 della Fase 8.
- **M3** (9.3): mappa con la carta di base offline, 100 mila punti fluidi, griglia e viewer anche solo-NAS; mappa online con il cancello;
  regioni offline; prova di zero traffico a mappa spenta.
- Chiusura: valutazione della qualità sui dati veri (F125, Q7), benchmark, revisione di sicurezza e verifica funzionale in parallelo.

**Test senza dispositivo**: Libreria sintetica da 100 mila elementi con date, luoghi, coordinate, viaggi, gruppi, nascosti e vettori finti;
viaggi e persone finti dietro le porte; scaffale e dati personali finti con due dispositivi; politica di rete come funzione pura con
proprietà su richieste casuali; raggruppamento contro un riferimento a forza bruta (§5.2).

**Cosa le fasi precedenti hanno lasciato pronto** (verificato nel codice)
- **Il posto nelle Raccolte**: `CollectionSections.MEMORIES = "memories"`, quinto nell'ordine predefinito e fra le sezioni riservate
  (`feature-library/.../collections/CollectionSections.kt`, `CollectionsLayoutTest`); una sezione è un `CollectionsSectionProvider`
  (`code`, `group: StateFlow<CollectionEntryGroup?>`) legato in `:app` con `@IntoSet` (`app/.../di/CollectionsModule.kt`, `FlowSection` in
  `app/.../navigation/CollectionsSectionsWiring.kt`), le voci si aprono da `EntryTarget.of(code)` in `CollectionEntryRouting.kt`; una sezione
  nuova va in coda a un ordine già salvato (E-L14).
- **Il campo dei ricordi visti**: `PersonalKind.MEMORY("memory")`, `PersonalFields.MEMORY_SEEN = "seen"` e `PersonalMemories.markSeen/lastSeen`
  (`core-personal/.../contract/Personal.kt:34,58`, `settings/PersonalCollections.kt:295-306`), **senza nessun chiamante di produzione**.
- **Viaggi ed eventi della Fase 7**: `Trips`, `TripProposal(id, kind, days, label, count, cover, placeIds)`, `TripId` (SHA-256 di tipo, giorni
  e luoghi), `TripRules`, `HomeClearance.closeToHome`, `AwayFromHome` (`core-albums/.../contract/Trips.kt`); rilevazione notturna da
  `LibraryMaintenanceWorker` (`app/.../work/GalleryWork.kt:177` → `AlbumsGraph.nightly`), casa solo in `home_estimate` (C31, `TripPrivacyTest`).
- **Nascosti da una sola fonte**: `VisibleMedia` (anche `visibleOrThrow`), `HiddenMask`, `WidgetFeed` (`core-library/.../contract/Visibility.kt`);
  registro `HiddenSurfaces` con `SpecRow` (`app/src/test/.../hidden/HiddenSurfaces.kt:19-36`) che fallisce per un file nuovo non registrato.
- **Il viewer su una lista qualunque**: `ViewerRequest(items, start, anchor, secure)`, `IndexOrderedTimelineView.of(snapshot, ids)` (salta gli
  id non più visibili), il modello "vivo" di `AlbumViewerItems` (`app/.../albums/ViewerAlbums.kt:172`); funziona per i media solo sul NAS.
- **L'istantanea della Libreria in colonne**: `TimelineSnapshot.localDayAt`, `sourceAt` (screenshot), `flagsAt` (`FAVORITE`, `EDITED`,
  `DOCUMENT`, `HAS_PLACE`, `DATE_ESTIMATED`, `MOTION`, `PANORAMA`), `placeAt`, `kindAt`, `groupSizeAt`, `thumbHashAt`, `durationMsAt`
  (`core-library/.../contract/Timeline.kt:42-100`): senza nascosti, una cella per gruppo.
- **Luoghi offline**: `ReverseGeocoder`, `PlaceNames.label/labelOfId/summary`, `TimeZoneLookup` (`core-places/.../contract/Places.kt`); asset
  `places.mcgp1` da 4.984.292 byte, 161.650 luoghi (GeoNames, CC BY 4.0, `PlacesDatasetPin`); il pannello info mostra luogo, coordinate a 4
  decimali e "Apri in Mappe" con conferma (`feature-viewer/.../info/InfoBuilder.kt`, `ViewerActions.kt:231`, intento `geo:`).
- **Le coordinate dei media**: `version_metadata.latitude/longitude` (`core-database/.../entity/MediaEntities.kt`), le correzioni in
  `metadata_correction`, il campo `geo` dell'indice condiviso (`GeoE7`, `core-trust/.../data/IndexSegment.kt`) adottato per i media solo sul
  NAS; una query con `withGpsOnly` e paginazione per chiave (`core-database/.../dao/AlbumQueries.kt:102,130`).
- **Persone (Fase 8, contratti in `main`)**: `People`, `PeopleIndex.dictionary/contentWith/resolve`, `PeopleLookup.cells`, `PeopleVisibility`,
  `FaceCropGate`, `GuardedPeopleCrops` (ritagli solo in memoria), `FacesState`/`HereState`.
- **Vettori e simili (Fase 6)**: `SearchData.vectors(ids, space)` (`core-search/.../contract/Search.kt:234`), `QueryEmbedding.vector(text)`,
  `SimilarShotsPlanner`, `VectorAffinity(sdup = 0,95)`, `SignatureAffinity`, `SimilarCluster.best` (`core-library/.../duplicates/`).
- **Notifiche**: `AppNotifications` (`app/.../notify/AppNotifications.kt`) con i canali `approvals`, `nas`, `nas-ops`, `backup`, tutti
  `VISIBILITY_PRIVATE` con `setPublicVersion`, `canNotify()`, id fino a 1008; `POST_NOTIFICATIONS` nel manifest (`app/src/main/AndroidManifest.xml:28`),
  chiesto al passo 9 dell'onboarding; `PermissionAsks` per ricordare le richieste.
- **Recinti della rete**: `NoNetworkSources` e un `NoNetworkSourcesTest` per modulo, la regola 5 di `Phase6ArchitectureTest` (HTTP/TLS solo in
  `core-models/.../download/https/`, host ammessi in `ModelHosts`), `verifyReleaseManifest` (permessi in elenco chiuso, niente posizione),
  `tools/check.ps1` (dex, librerie native a 16 KB), la regola in testa a `versions-app.gradle` (nessun SDK che parli con Internet senza una
  riga in `docs/sicurezza.md`); `android:usesCleartextTraffic="false"`.
- **Impostazioni**: tre schemi già usati (locale in `SharedPreferences` come `SharingSettings`; personale sincronizzato come
  `SyncedMotionSettings`; DataStore in `noBackupFilesDir` come `RuleSettings`).
- **Prove**: soglie in `benchmark/soglie.json` e `check.ps1 -Bench`; Libreria da 100 mila elementi del `benchmarkRelease` (`BenchSeeder`) e
  `SyntheticLibrary` (`core-library/.../testing/InMemoryTimeline.kt:200`); strumenti di valutazione solo debug e "solo numeri" (Fase 7 P12,
  Fase 8 P11).

**Cosa le fasi precedenti promettevano per la Fase 9 e nel codice non c'è** (la Fase 9 lo costruisce o lo cambia, §0.3)
1. **Ricordi**: c'è solo il codice riservato `memories`; nessun fornitore, schermata, rotta o voce in `EntryTarget`; nessuna stringa
   "Un anno fa", nessuna presentazione, nessun Ken Burns, nessuna musica nel codice.
2. **`PersonalMemories` scrive l'ora del NAS come valore** (`markSeen(memoryId, seenAtNas)`): chi segna "visto" fuori casa non la conosce;
   e non c'è un formato di `memoryId` (testo libero fino a 128 caratteri). Il campo `memory` non è documentato in `docs/formato-nas.md` §5.5-bis.
3. **"Mostra meno"** per una persona o un periodo, promesso da F8-D40 e dal §2.11 della Fase 8 (`memories_less:<persona>`, "lo decide la Fase
   9"): non esiste.
4. **I viaggi non espongono i loro media**: `TripProposal` ha giorni, conteggio e copertina, non gli elementi (li legge solo `TripReader`,
   `internal`); le proposte diventate album o scartate non sono in `Trips.proposals`. Per "viaggi passati" serve una porta nuova (D12).
5. **Notifiche**: nessun canale per i ricordi; visibilità sulla schermata di blocco fissa (`VISIBILITY_PRIVATE`, nessuna impostazione), mentre
   `docs/sicurezza.md` §4-bis promette "contenuto oscurabile nella schermata di blocco"; nessun deep link e intent ripulito a ogni ingresso
   (`MainActivity.kt:35,45,64-75`, C17): una notifica oggi sa solo aprire l'app; `POST_NOTIFICATIONS` si chiede solo nell'onboarding.
6. **Nessun orario preciso**: niente `AlarmManager` (`docs/android.md` riga 612: "Non usato dal piano"); solo WorkManager periodico o con
   ritardo (`NasDailySummaryWorker` ogni 24 ore senza ora scelta).
7. **`HiddenSurfaces` non ha righe** per ricordi, notifiche e mappa (`SpecRow` arriva a `PEOPLE`); il widget è solo quello di prova.
8. **Mappa**: nessuna libreria, nessuno stile, nessun codice; nessun indice su `version_metadata.latitude/longitude`; nessuna API per le
   coordinate di un `PlaceId` (interne a `Mcgp1`); `CellFlags.HAS_PLACE` vuol dire "ha un luogo `CITY`/`NEAR`", non "ha il GPS". La
   **mini-mappa del pannello info** promessa da F3-D18 per la 9.3 non c'è.
9. **Rete**: l'unico codice HTTPS ammesso è il pacchetto dei modelli; la mappa online richiede la **seconda eccezione dichiarata** (D23), che
   la regola 5 della Fase 6 oggi vieta per costruzione.
10. **Fase 6 §2.9** promette per la Fase 9 i vettori e "Trova simili" per i ricordi, il dizionario dei luoghi per mappa e ricordi, il
    ritagliatore per le copertine: i pezzi ci sono, ma **`ShotVectorSource` non è legata in produzione** (`app/.../utility/UtilityGraph.kt:91`:
    i Doppioni simili usano solo la firma visiva) e la parte a vettori dei documenti è spenta (`DocumentFlags.kt:37`,
    `VECTOR_THRESHOLD = +∞`). Non esiste un punteggio di qualità della foto intera (solo `FaceQuality` per i volti).
11. **Persone non legate in `:app`** (P13 della Fase 8 in corso): i ricordi con le persone si scrivono sui contratti e si accendono dopo.
12. **Benchmark promessi e mancanti**: gli scenari `album` (Fase 7, §5.4) e `volti` (Fase 8, §5.4) non sono né in `benchmark/src` né in
    `soglie.json`. La Fase 9 non può ripetere l'errore: i suoi scenari `ricordi` e `mappa` sono un pacchetto con un "Fatto quando" proprio
    (P12) e la chiusura li controlla.
13. **`MediaCatalog.setPlacesDataset` non ha chiamanti** fuori dai test: al cambio del dataset dei luoghi i `place_id` non si ricalcolano. Non
    blocca la Fase 9 (i titoli dei ricordi si fissano alla pubblicazione), ma va segnalato (§7).
14. **`docs/sicurezza.md` §7 "Fase 9"** ha tre voci generiche; L13 dice solo che "il server vede la zona guardata e l'IP".
15. **Il sottomodulo `engine/` è vuoto in questo worktree** (versione 2.13.1 in `engine.properties`): i componenti Fluid si conoscono dagli
    import. I pacchetti con UI lavorano in un worktree con il sottomodulo inizializzato.

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)

| # | Decisione | Perché |
|---|---|---|
| D1 | **Una fase, tre tappe** (M1 ricordi, M2 notifiche e sincronizzazione, M3 mappa); ricordi e mappa vanno **in parallelo** (nessuna dipendenza fra loro). **Nulla tocca i media né scrive sul NAS** se non voci dei dati personali dell'area privata con il giro esistente (`NasPersonalSync`); nessuna cancellazione, nessuno spostamento, nessun file nuovo sul NAS | La fase aggiunge viste e scelte personali: niente di distruttivo, nessuna strada nuova verso i file |
| D2 | **Moduli e recinti**: `core-memories` e `core-map` sono **JVM puri** (nessuna classe Android, nessuna rete, nessun NAS); vedono `common`, `model`, `library` (contratti: `VisibleMedia`, `HiddenMask`, istantanea, viste), `personal` (contratti), `places` (contratti). **Non vedono** `faces`, `search`, `albums`, `ai`, `nas`, `nasops`, `network`, `database`: viaggi, persone, vettori, pixel delle miniature e punti arrivano da porte legate da `:app`. `feature-memories` e `feature-map` non si conoscono e non vedono il viewer (aprono il viewer con un'azione verso `:app`). **MapLibre e il client HTTP stanno solo in `feature-map`**, e il client solo in `feature-map/.../net/` | Nessun ciclo; generazione, scelta, raggruppamento e politica di rete si provano sulla JVM con fake; la rete resta in un pacchetto solo, controllato da test d'architettura e dal dex |
| D3 | **Tipi di ricordo**: (1) **"N anni fa oggi"** (`ON_THIS_DAY`): i media dello stesso giorno dell'anno di un anno passato (29 febbraio → 28 negli anni non bisestili), uno per anno con abbastanza foto, al più due al giorno (il più recente e il più vecchio che si qualificano); (2) **viaggi ed eventi passati** (`TRIP`, `EVENT`): l'anniversario (inizio fra −3 e +3 giorni dalla data di oggi, anni passati) e la **riscoperta** di un viaggio di più di un anno fa mai mostrato negli ultimi 12 mesi (al più una a settimana); (3) **persone** (`PERSON`, solo con Persone attiva qui e Q9): una persona con un nome, non nascosta, con almeno 20 foto visibili in almeno 3 giorni, "Con Anna" o "Anna nel 2024", al più una a settimana; (4) **il meglio del mese** (`MONTH`): dal 1° al 7 del mese, per il mese prima, se ha almeno 40 candidati. **Al più due ricordi nuovi al giorno** (più quello del mese nel suo giorno) | I quattro del piano 9.1; le soglie evitano ricordi poveri; poche novità al giorno tengono il ricordo speciale |
| D4 | **Quando e quanto costa**: (a) **di notte**, dentro `LibraryMaintenanceWorker` (in carica, batteria non scarica: come viaggi e album smart), con un budget di 60 s: prepara i ricordi del giorno (quello che comincia) e le misure dei pixel; (b) **all'ora della notifica** (o all'apertura dell'app se la notte non c'è stata), un **passo leggero** con budget di 5 s, senza decodifiche se il risparmio energetico è acceso; (c) all'apertura di Raccolte, solo se il giorno è cambiato. **Nessuna attività nuova** in `core-rules` e nessun traffico: tutto locale, sulla replica e sull'istantanea, anche fuori casa e con il NAS spento. Costo atteso [stima]: < 1 s di CPU sull'S25 per 100 mila elementi; il passo leggero < 300 ms | Un'attività nuova rompe sei `when` esaustivi e le stime (§0, `core-rules`); la generazione non usa rete né NAS, quindi la proposta 3 non la riguarda; il lavoro pesante sta dove c'è già la presa |
| D5 | **Candidati ed esclusi**: celle **visibili** dell'istantanea (senza nascosti, una per gruppo: raffiche e copie simili danno già la migliore). **Esclusi**: screenshot e registrazioni dello schermo (`MediaSource.SCREENSHOT`), documenti (`CellFlags.DOCUMENT`), immagini animate (`ANIMATED_IMAGE`: adesivi e meme), ritagli (cartella `Ritagli/`, come F8-D18), media illeggibili (`UNREADABLE`), date stimate (`DATE_ESTIMATED`) nei ricordi legati a una data, periodi e persone "Mai nei ricordi" (D11). **Penalizzati, mai copertina**: media ricevuti da WhatsApp (al più un quarto del ricordo) | Il piano chiede "screenshot e documenti esclusi"; una data stimata (E-M8, E-M9) farebbe un "un anno fa" sbagliato |
| D6 | **Punteggio senza capire il contenuto** (`PhotoScore`, pesi provvisori calibrati in P11): preferito +3, modificato +1, in un album manuale +1, una persona con nome visibile +1, panorama +0,5, foto in movimento +0,3, video da 3 a 60 s +0,5 (oltre 5 minuti −0,5), WhatsApp −1,5; **luce** dalla `ThumbHash` (luminanza media sotto 0,12 o sopra 0,92: −1); **nitidezza** dalla miniatura locale (varianza del laplaciano su 256 px, solo per i ≤ 200 candidati finali di un ricordo, misure in una cache locale): sotto soglia −1,5. Nessun modello nuovo, nessuna stima di bellezza | Nessun punteggio estetico esiste (§0, punto 10) e un modello nuovo sarebbe un download e una licenza; preferiti e modifiche sono il segnale più forte di ciò che l'utente ama |
| D7 | **Doppioni e varietà con i vettori della Fase 6**: dentro un ricordo due foto con coseno ≥ 0,92 (spazio `embeddinggemma2`) sono doppioni: resta quella col punteggio più alto; la scelta finale è **a massima rilevanza marginale** (punteggio − λ · somiglianza massima con le già scelte, λ = 0,5) con tetti per giorno (un viaggio: almeno una foto per giorno, al più 8) e per ora (al più 4 nella stessa ora). **Senza vettori** (dispositivo non indicizzatore, copertura parziale): firma visiva della Fase 5 (`SignatureAffinity`) e, in mancanza, una foto ogni 3 minuti. Dimensioni: "anni fa" 8–24, viaggio 15–40 (circa 6 per giorno), persona 15–30, mese 20–30; sotto il minimo (6, 10, 15, 20) il ricordo non nasce | "Trova simili" e i vettori sono pronti (F6 §2.9); la varietà evita trenta foto della stessa tavolata; il ripiego tiene i ricordi utili anche dove i vettori mancano |
| D8 | **Ricordi tristi o sbagliati senza capire il contenuto**: (1) i comandi dell'utente (D11) valgono subito e su tutti i suoi dispositivi; (2) un viaggio scartato ("Non mi interessa", `trip_dismissed`) non diventa un ricordo; (3) persone nascoste o dimenticate mai; (4) nessun ricordo da giorni dominati da documenti o screenshot; (5) un ricordo scartato non torna mai (stesso id, D9); (6) **con Q6 (b)** un filtro prudente sul dispositivo: frasi prototipo ("un ospedale", "un funerale", "una bara", "un cimitero", "una radiografia", "un incidente stradale") con i vettori della ricerca e la soglia relativa della Fase 6 (`ZS = 3,5`): sopra soglia la foto **esce** dal ricordo e non ne è mai la copertina. L'esito non si salva, non si sincronizza, non si esporta e non si scrive nei log | L'app non sa chi è morto o cosa fa male: i comandi dell'utente sono la difesa vera; il filtro, se l'utente lo vuole, toglie e basta (un errore costa una foto in meno, mai una foto in più) |
| D9 | **Identità e stabilità**: `MemoryId` = primi 128 bit di SHA-256("mcg-memory\0" ‖ tipo ‖ "\0" ‖ chiave) in base32 (26 caratteri); chiavi: `otd:<aaaa-mm-gg>` (il giorno d'origine), `trip:<inizio>:<fine>`, `event:<giorno>:<ora d'inizio a 15 minuti>`, `person:<PersonId>:<anno o finestra>`, `month:<aaaa-mm>`. Un ricordo **pubblicato** sullo **scaffale** (file locale, D10) ha titolo, elementi (≤ 40, in ordine) e copertina **fissi**: dopo si possono solo **togliere** elementi diventati nascosti, eliminati o esclusi da un "Mai"; la copertina passa al primo elemento rimasto; sotto 3 elementi il ricordo esce dallo scaffale. Un ricordo visto non si rigenera per 12 mesi; uno scartato mai | "Un ricordo visto non cambia sotto gli occhi"; un id deterministico fa valere "visto" e "scartato" su ogni dispositivo dell'account senza sincronizzare i contenuti |
| D10 | **Cosa si salva e cosa viaggia**: **viaggia** (dati personali, area privata, nessun tipo nuovo): `memory`/`<id>`/`seen` (valore = **giorno locale** `aaaammgg`, non l'ora del NAS: l'ora la porta già la voce), `memory`/`<id>`/`dismissed`, e nel `setting` i campi di D11 e `memories_people` (Q9). **Resta sul dispositivo**: lo **scaffale** (`noBackupFilesDir/memories/shelf.bin`, scrittura atomica con CRC, al più 60 ricordi e 30 giorni), le misure dei pixel, le impostazioni delle notifiche (accese, ora, frequenza, schermata di blocco) e della mappa. I contenuti dei ricordi **non** viaggiano: ogni dispositivo li genera dalla sua Libreria (il tablet può non avere i media del solo telefono: E-MEM8) | Il campo pronto della 4.7 diventa usabile anche fuori casa; il tablet condiviso ha le sue notifiche per profilo; lo scaffale contiene date, luoghi e id: resta locale come la replica |
| D11 | **"Mostra meno" e "Mai nei ricordi"**: per una **persona** due livelli, `memories_less:person:<id>` = `less` (niente ricordi dedicati, mai copertina, mai nella notifica; può comparire nelle foto di gruppo) o `never` (nessuna sua foto nei ricordi); per un **periodo** un livello, `memories_never:days:<inizio>-<fine>` (giorni locali, al più 366 giorni a voce). Voci `setting` dei dati personali (sincronizzate, valgono subito anche senza l'ora del NAS); **mai un nome**, solo l'id casuale della persona. Quando Persone si spegne o cancella (`FacesState` `Off` o `Erasing`), `core-memories` mette le lapidi su tutte le voci `memories_less:person:*` (`PeopleLessJanitor`), senza toccare il codice della Fase 8 | F8-D40 chiede l'impostazione personale; nella classe `people` un campo nuovo farebbe rifiutare il segmento alle app della Fase 8 (formato §2.4 della Fase 8), nei dati personali un campo nuovo no; la lapide toglie anche il ricordo di chi si voleva vedere meno |
| D12 | **Viaggi e persone attraverso porte**: `core-albums` aggiunge in `trips/` la porta **`TripMemories.past(until, budget)`** con `TripDigest(id, kind, days, label, placeIds, members, album)`: solo media propri e visibili, viaggi accettati compresi, **scartati esclusi**, **mai coordinate né casa** (l'elenco chiuso dei membri pubblici di `TripPrivacyTest` si allarga di questa sola firma). Le persone arrivano da `MemoryPeople` sopra `PeopleIndex`/`PeopleLookup` (solo persone con nome, non nascoste né dimenticate, alias risolti con `resolve`; vuota se Persone non è `On` con `ACTIVE` o `LOCAL_ONLY` qui). Il ricordo salva l'**id** della persona, mai il nome: il nome si legge al momento di mostrarlo (una rinomina si vede, un "nascondi" o un "dimentica" tolgono il ricordo) | `core-memories` non vede `core-albums` né `core-faces`; i viaggi restano l'unico posto che conosce la casa (C31); i nomi restano nei dati dei volti (F8-D13) |
| D13 | **Presentazione**: (1) **pagina del ricordo**: titolo, sottotitolo (periodo, luogo), griglia degli elementi (`IndexOrderedTimelineView`, viva: un nascosto esce), viewer della Libreria, menu (Riproduci, Mostra meno…, Non mostrare questo ricordo, Apri l'album del viaggio); (2) **presentazione** a schermo intero (`MemoryPlayer`): una foto ogni 4 s, movimento lento di zoom e spostamento ("Ken Burns", solo trasformazioni della GPU con i token di `GalleryMotion`/`FluidMotion`, mai animazioni scritte a mano), passaggi in dissolvenza, le due immagini successive decodificate in anticipo (anteprima HQ locale, poi miniatura: mai un'attesa sul NAS), video come fotogramma di copertina con l'icona (i clip brevi solo se locali, porta `MemoryClips`), tocco = pausa, scorrimento = avanti e indietro, carta finale con "Rivedi"; **animazioni ridotte**: nessun movimento, solo dissolvenze; **TalkBack**: niente avanzamento automatico, ogni foto con la sua descrizione. Musica secondo **Q3** | Il piano chiede "presentazione animata con slideshow"; i token del design system tengono il movimento coerente e lasciano la rifinitura al filone grafico |
| D14 | **Raccolte → Ricordi**: il fornitore della sezione `memories` (come Persone): fino a 6 voci (prima i non visti, poi i più recenti), "Tutti i ricordi" (lo scaffale intero), un segno per i nuovi; da vuota la riga "I ricordi arrivano quando ci sono abbastanza foto"; nascondibile come le altre sezioni; codici delle voci `m_<16 caratteri>` (stabili, senza dati dell'utente, validi per `rememberSaveable`). Le copertine delle voci sono del filone grafico (oggi le righe hanno icona e tono, F7 §12.13) | Il segnaposto riservato dalla Fase 7 diventa una sezione come le altre, senza codice speciale nella scheda |
| D15 | **Notifiche dei ricordi**: canale **`memories`** ("Ricordi", importanza predefinita **senza suono né vibrazione**, `VISIBILITY_PRIVATE`), id 1009; **al più una al giorno** (registro locale del giorno, `noBackupFilesDir/memories/notified`), all'**ora scelta** (predefinita 19:00), con la **frequenza di Q4**; solo per un ricordo **pubblicato oggi, non visto, non scartato** e con almeno 8 elementi; mai se l'utente ha già aperto i Ricordi oggi; mai un ricordo `PERSON` con "Mostra meno"; ordine di scelta: anniversario di un viaggio, "anni fa", il meglio del mese, persona. Disattivabili dalla pagina **Impostazioni → Ricordi e notifiche** e dal sistema (canale o app) | Proposta 12 del piano; "meglio nessuna che una brutta"; il silenzio di default rispetta chi riceve la notifica a cena |
| D16 | **Schermata di blocco** (impostabile, **Q5**): modalità **"Solo «Hai un ricordo»"** (predefinita): la notifica intera dice "Un ricordo per te · Tocca per vederlo", **senza titolo, luogo, data né foto**, quindi niente trapela nemmeno con "Mostra tutti i contenuti" del sistema; modalità **"Titolo e foto"**: versione privata con il titolo (mai nomi di persone: un ricordo `PERSON` resta generico) e la miniatura di copertina dalla replica locale, versione pubblica generica (`setPublicVersion`) | Con `VISIBILITY_PRIVATE` Android mostra la versione privata sulla schermata di blocco se l'utente lo permette nelle impostazioni di sistema (lo dice già `AppNotifications`): la sola garanzia è non mettere niente nella notifica |
| D17 | **Apertura dalla notifica senza deep link con dati**: il `PendingIntent` (immutabile, esplicito) porta **solo un'azione fissa** `dev.pampa.mycloudgallery.action.OPEN_MEMORIES`, **senza extra, senza dati, senza id**; `MainActivity` riconosce quella sola azione mentre ripulisce l'intent (come oggi) e la trasforma in un evento interno "apri i Ricordi di oggi", servito **dopo** il blocco app; qualsiasi altro contenuto dell'intent si butta. Un'altra app che manda la stessa azione può solo aprire la pagina Ricordi (dietro il blocco app). **C17** e `EntryHardeningTest` si aggiornano: un'azione nota, nessun dato | C17 vieta deep link che eseguono azioni; aprire una pagina non è un'azione, e l'id del ricordo lo sa già l'app (lo scaffale) |
| D18 | **Permesso `POST_NOTIFICATIONS`** (già dichiarato, chiesto al passo 9): negato o revocato = nessuna notifica e nessuna richiesta automatica (E-O4); la pagina delle impostazioni lo dice con "Consenti" che, **da un tocco**, chiede il permesso se Android lo permette ancora (`PermissionAsks`), altrimenti apre le impostazioni di sistema dell'app; canale spento dall'utente = "Spente dalle impostazioni di Android" con il collegamento al canale. Ogni profilo del tablet ha il suo permesso e le sue scelte | Mai una richiesta che l'utente non ha chiesto; il sistema resta l'ultima parola |
| D19 | **Ricordo che diventa nascosto dopo la notifica**: prima di pubblicare la notifica si rifanno `HiddenMask` e lo scaffale; dopo, ogni cambio di nascosti (e ogni "Non mostrare", "Mai nei ricordi") chiama `MemoryNotifier.revalidate()`, che **toglie la notifica** se il ricordo non è più mostrabile o se la sua copertina è nascosta (in modalità "Titolo e foto"); aprendo, il ricordo si mostra senza i nascosti; se ne restano meno di 3, "Questo ricordo non c'è più" e la pagina Ricordi | Una miniatura nascosta non deve restare nella tendina; la modalità generica non ha niente da togliere, ma la notifica sparisce comunque |
| D20 | **L'ora scelta con WorkManager**: un lavoro `OneTimeWork` unico (`memories-notify`) con il ritardo fino alla prossima ora scelta (come `followUpOf`), senza vincoli di rete né di carica, rifatto a ogni esecuzione, a ogni cambio di impostazioni e a ogni avvio dell'app (WorkManager lo conserva anche dopo un riavvio del telefono); **finestra** di consegna da H a H+2 h; se il lavoro parte oltre H+3 h (Doze, telefono spento) **quel giorno si salta**. **Nessun allarme esatto** (servirebbe un permesso nuovo). Piano B se F115 mostra più del 10% di ritardi: `AlarmManager.setWindow` non esatto (nessun permesso) con un ricevitore **non esportato** (§7) | L'ora "più o meno" basta per un ricordo; un allarme esatto chiede `SCHEDULE_EXACT_ALARM` o `USE_EXACT_ALARM`, fuori dalla lista dei permessi ammessi |
| D21 | **Libreria delle mappe: MapLibre Native Android** (`org.maplibre.gl:android-sdk`, licenza BSD-2-Clause, rendering vettoriale su GPU, nessun account, nessuna telemetria [da verificare in P1 con la cattura]), **solo in `feature-map`**, dentro un `AndroidView` (`MapView` con `SurfaceView`, non `TextureView`). Versione: l'ultima stabile che passa i controlli di P1 (librerie native allineate a **16 KB**, nessun componente esportato nel manifest fuso, nessun host diverso da quelli della politica, peso misurato), fissata con la sua impronta in `gradle/verification-metadata.xml` | Unica libreria aperta matura per le tessere vettoriali su Android; osmdroid vuole tessere raster (OpenFreeMap non le serve), Mapsforge è solo offline e più lento; Google Maps e Mapbox chiedono un account e mandano dati |
| D22 | **Fornitore online: OpenFreeMap** (nessun account, nessuna chiave, dati OpenStreetMap ODbL, schema OpenMapTiles). **Tutto il resto è dentro l'APK**: lo **stile** (adattato da Positron per il chiaro e da Dark per lo scuro, licenze da verificare in P2), i **glifi** (Noto Sans, SIL OFL 1.1, solo gli intervalli latini e greci), gli **sprite**, con `asset://`. Dal telefono escono solo il TileJSON (`/planet`, una volta al giorno al più) e le **tessere** `z/x/y` fino a z14; nessuna richiesta di stile, caratteri o icone. L'URL delle tessere nello stile è **stabile** (`.../planet/v/{z}/{x}/{y}.pbf`) e il nostro intercettore lo riscrive sulla versione corrente del TileJSON, così cache e regioni offline restano valide quando il server cambia versione [comportamento del server da verificare in P1] | Meno richieste, meno cose che il server vede; un URL stabile evita che le regioni scaricate diventino inutili a ogni aggiornamento settimanale del server |
| D23 | **Seconda eccezione di rete, dichiarata**: il solo codice HTTP nuovo sta in `feature-map/.../net/` (`MapTileHttp`): il client OkHttp che MapLibre usa (`HttpRequestUtil.setOkHttpClient`, impostato **prima** di creare qualunque mappa) con un **intercettore che decide tutto** chiamando la funzione pura `TilePolicy.decide` di `core-map`: solo `GET` verso **`tiles.openfreemap.org`** su HTTPS e porta 443, solo i percorsi del TileJSON e delle tessere (z ≤ 14), niente redirect (seguiti a mano solo verso lo stesso host), niente cookie, niente `Referer`, `User-Agent` fisso e generico senza versione né dispositivo, niente richieste per tessere coperte da una regione offline, **niente di niente** con la mappa online spenta, in roaming o sui dati mobili non permessi; in più `MapLibre.setConnected(false)` quando è spenta e precaricamento dei livelli più bassi spento. `Phase9ArchitectureTest` estende la regola 5 della Fase 6 a **due** pacchetti ammessi, `NoNetworkSourcesTest` di `feature-map` ammette solo `net/`, `check.ps1` controlla nel dex che OkHttp sia chiamato solo da lì e da MapLibre | La regola 5 diventa "due posti, elencati", non "dove capita"; la stessa funzione pura decide e si prova con proprietà su richieste casuali; zero richieste a mappa spenta si prova sulla JVM e sul telefono (§5.5) |
| D24 | **La mappa online è una scelta per dispositivo** (**Q1**): spenta di default; si accende da Impostazioni → Mappa o dal primo avviso della mappa, **dopo una pagina versionata** (`MapOnlineConsent`, costruito solo da lì) che dice in frasi brevi cosa esce, a chi, e come evitarlo; "Solo sul Wi-Fi" acceso di default, mai in roaming; si spegne con un tocco (cache tenuta, nessuna richiesta). Impostazione locale (DataStore in `noBackupFilesDir`), **non sincronizzata**: il tablet condiviso e ogni telefono decidono per sé. `FeatureFlags.mapOnline` spegne tutto per costruzione (interruttore di build) | È la sola funzione che fa sapere a un terzo dove si guarda: la decide chi tiene il telefono, sapendo cosa succede |
| D25 | **Carta di base offline nell'APK**: terre, coste, laghi e confini dei paesi (Natural Earth 1:50m, pubblico dominio) più i confini delle regioni italiane (Natural Earth 1:10m) e le città da 50.000 abitanti in su (dal GeoNames che l'utente ha già sul PC per i luoghi, CC BY 4.0), come GeoJSON semplificati letti da `asset://`, circa 2–4 MB [stima]; stile chiaro e scuro; **nessuna rete**. Con la mappa online spenta e nessuna regione, la mappa resta usabile a livello di paese e regione | Il "Fatto quando" chiede la mappa anche senza traffico; F3-D18 indicava già Natural Earth; niente catena di generazione delle tessere (basta un programma che filtra e semplifica) |
| D26 | **Regioni offline**: catalogo fisso (le 20 regioni italiane, i paesi vicini, "La zona attorno a un luogo" scelto per nome con raggio 15, 30 o 60 km); stima della dimensione prima (tessere da z0 a z14 nel riquadro, con una tabella di densità per tipo di zona); scaricamento **solo da un tocco**, con un foglio che dice "il servizio delle mappe vedrà quale zona scarichi", solo sul Wi-Fi (sui dati mobili solo con conferma e la dimensione), con `OfflineManager` di MapLibre nel suo database offline (un file SQLite con le tessere vettoriali compresse così come arrivano dal server) in `noBackupFilesDir/map/`; tetti: 1 GB per regione, 3 GB in tutto, 1 GiB libero da lasciare; ripresa all'apertura se il processo muore; **aggiornamento solo a mano** (l'app lo propone dopo 6 mesi); le tessere coperte da una regione **non si chiedono mai online** (D23). Con **Q2 (b)** le regioni si preparano sul PC e passano dal NAS (§3) | Le regioni offline sono la difesa contro la zona guardata (L13); il catalogo fisso fa vedere al server "Toscana", non una casella attorno a casa; un aggiornamento automatico rivelerebbe la zona a ogni giro |
| D27 | **Punti della mappa** (`MapPointSource`): dalle celle **visibili** dell'istantanea con coordinate **effettive** (la correzione di luogo se c'è, altrimenti `version_metadata`: la stessa regola del pannello info, verificata da P3), anche per i media **solo sul NAS** (coordinate dal record `geo` adottato o dall'EXIF letto dal NAS); coordinate fuori dai limiti, (0, 0) esatto e latitudini oltre ±85° escluse. Una query nuova di `core-database` a pagine per chiave, **schema v6 invariato**; indice in memoria (`GeoClusterIndex`, ~24 byte a punto) costruito fuori dal thread principale all'apertura della mappa e rifatto, con 2 s di calma, quando cambia l'istantanea; i media senza posizione si contano ("1.240 foto senza posizione") | Nessuna migrazione sul campo per una vista; 100 mila punti in memoria pesano ~2,4 MB; la regola sulle correzioni deve essere una sola |
| D28 | **Raggruppamento e bolle**: punti ordinati per chiave di Morton (z-order) a 2^24 per lato; per ogni zoom (passo di mezzo livello) le celle sono prefissi della chiave, quindi i conteggi di una vista sono ricerche binarie (centinaia, < 4 ms per 100 mila punti sulla JVM); la copertina di una cella è il suo media col punteggio di D6 più alto (pre-calcolato per livello, aggiornato con l'indice). Le **bolle** sono composable sopra la mappa (al più 60 visibili, miniatura, conteggio, etichetta del luogo da `PlaceNames` per le celle grandi), riposizionate a ogni fotogramma con la proiezione della mappa, ricalcolate solo a camera ferma o al cambio di mezzo livello. Tocco su una bolla: la camera si avvicina; sotto 30 elementi, o al livello massimo, si apre la **griglia della zona** (`OrderedTimelineView` dal più recente) e da lì il viewer | MapLibre raggrupperebbe un GeoJSON da 100 mila oggetti con costi di serializzazione e memoria; le bolle in Compose usano le nostre miniature e lo stile Fluid; l'indice a prefissi regge 100 mila punti con tempi costanti |
| D29 | **La "casa" e la mappa**: la casa dedotta (Fase 7) **non si usa e non si mostra**: nessun segno, nessuna etichetta "Casa", nessuna camera iniziale su di lei, nessuna regione proposta attorno a lei. **Camera iniziale**: l'ultima posizione salvata **solo in locale**; la prima volta "tutte le foto" con lo zoom **non oltre 5** (circa un paese), così la prima apertura online non chiede tessere dettagliate della zona dove ci sono più foto. La **mini-mappa** delle info del viewer (F3-D18) usa **solo** la carta di base e le regioni scaricate (uno stile senza la sorgente online): non fa mai richieste | Le foto di casa si vedono sulla mappa come tutte le altre, perché sono dell'utente; ma né il server né chi guarda lo schermo devono ricevere da noi "dove abiti" |
| D30 | **Dove si apre la mappa**: una sezione nuova delle Raccolte, **Luoghi** (codice `places`, subito dopo `memories` nell'ordine predefinito; con un ordine già salvato va in coda, E-L14): la riga "Mappa" e i luoghi più fotografati (fino a 8, da `PlaceNames.summary` sui `placeAt` dell'istantanea, mai la parola "casa"), ognuno apre la griglia del luogo; nel pannello info del viewer la riga **"Mostra sulla mappa"** (in app, accanto ad "Apri in Mappe" che resta) e la **mini-mappa** (porta `ViewerPlaceMap` in `ViewerServices`: null = niente riga); niente da un media aperto dai Nascosti | Come "Luoghi" di Apple Foto; il pannello info è dove l'utente cerca la posizione; dai Nascosti non si apre una superficie che mostra altri media |
| D31 | **Nascosti su ogni superficie nuova** (E-H1): righe nuove di `HiddenSurfaces` con tre `SpecRow` nuove, **`MEMORIES`** (sezione, pagina, "Tutti i ricordi", presentazione, copertine), **`NOTIFICATIONS`** (scelta del ricordo, contenuto, copertina della notifica, revoca) e **`MAP`** (punti, conteggi, bolle e copertine, griglia della zona, sezione Luoghi, mini-mappa); candidati da `VisibleMedia`, filtro finale `HiddenMask`, `visibleOrThrow` dove si confronta con lo scaffale salvato (R17), fallire chiusi; persone nascoste mai (`PeopleVisibility` dietro la porta) | Il "Fatto quando" della fase: "nessun elemento nascosto vi compare"; il registro fallisce da solo per un file nuovo non registrato (F7-D22) |
| D32 | **Privacy fra familiari**: ricordi, scaffale, scelte e mappa sono **di chi li usa**: niente nell'area comune, niente nell'indice condiviso, niente nella pagina Famiglia; le voci personali stanno solo nell'area privata dell'account; i ricordi mostrano solo ciò che la Libreria di quel dispositivo mostra già (le foto comuni sì, le aree private degli altri mai); i profili del tablet sono dispositivi separati | Come i dati personali (C19) e i volti (C37): un familiare non deve sapere cosa ricordo, chi vedo meno, dove guardo |
| D33 | **Log e Diagnostica**: mai titoli, luoghi, date o id dei ricordi, nomi o id di persone, coordinate, riquadri della mappa, tessere `z/x/y`, nomi delle regioni, l'ora scelta; solo numeri e codici (`MEMORY_*`, `NOTIFY_*`, `MAP_*`, §2.12); sezioni `DiagSection.MEMORIES` e `DiagSection.MAP`; chiavi nuove nell'elenco chiuso dell'esportazione (`ExportKeys.ALLOWED`); un test semina titoli, luoghi, coordinate e nomi riconoscibili e li cerca in log, Diagnostica ed esportazione (F126); `toString()` sicuri | C21: la Diagnostica non è una telemetria e non deve diventare una mappa delle abitudini |
| D34 | **UI come stati e contratti** (come F8-D38): `feature-memories`, `feature-map` e le aggiunte a viewer e impostazioni consegnano ViewModel e stati sigillati (§1.5), composable fatte solo con i componenti G2, movimento solo dai token (`NoHandWrittenStyleTest` vale anche qui) | Il filone grafico rifinisce presentazione e bolle senza toccare la logica |
| D35 | **Permessi, componenti, dipendenze, host**: **nessun permesso nuovo** (mai la posizione del telefono: la mappa mostra dove sono state scattate le foto); **nessun componente esportato né servizio nuovo** (lavori di WorkManager; il piano B di D20 aggiungerebbe un ricevitore **non** esportato); **dipendenze nuove**: MapLibre Native Android e i suoi transitivi (OkHttp, Okio e le librerie di MapLibre, elenco esatto in P1), da approvare (§8); **asset nuovi**: carta di base, stile, glifi, sprite, con licenze e avvisi; **un host nuovo**: `tiles.openfreemap.org`, solo con la mappa online accesa o per scaricare una regione | Checklist "per ogni fase": ogni libreria, host o voce del manifest è una decisione visibile; la regola in testa a `versions-app.gradle` chiede la riga in `docs/sicurezza.md` |
| D36 | **Ora**: "oggi" e i giorni dei ricordi in **giorni locali dello scatto** (`localDayAt`) e dalla porta `Today` (F6-D39); l'ora della notifica nell'ora locale del dispositivo (cambi di fuso e ora legale: si ripianifica, E-NT4); voci personali con l'ora del NAS messa dal registro (F4-D32); durate, finestre e budget con l'orologio monotono | Come le fasi precedenti; il valore di `seen` non dipende più dall'ora del NAS (D10) |
| D37 | **Azzeramento e uscita**: `AppLocalDataEraser` toglie lo scaffale, le misure dei pixel, il registro delle notifiche, la cache e le regioni della mappa (il database di MapLibre), l'ultima camera, i file della valutazione; il lavoro `memories-notify` si annulla e la notifica si toglie; con "Disattiva e cancella" delle persone le voci `memories_less:person:*` diventano lapidi (D11) e i ricordi `PERSON` escono dallo scaffale | La cache della mappa dice dove si è guardato, lo scaffale cosa si è ricordato: dati personali come la replica |
| D38 | **Per la Fase 10**: `WidgetFeed` dei ricordi (il widget "Ricordi" mostra il ricordo di oggi, mai nascosti, mai persone con "Mostra meno"); descrizioni per TalkBack delle bolle e della presentazione; mappa a due pannelli sul tablet (mappa e griglia insieme); regioni condivise in famiglia dal NAS (Q2 (b)) se l'utente le vorrà dopo | Il widget del piano 10.1 dice "ricordi o foto casuali": lo scaffale è la sua fonte |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)

Tre categorie nuove: **E-MEM** (ricordi), **E-NT** (notifiche), **E-MAP** (mappa); più tre voci dei Nascosti (E-H8–E-H10).

**Ricordi (E-MEM)**
- **E-MEM1**: Libreria giovane o con poche foto: nessun ricordo; la sezione dice "I ricordi arrivano quando ci sono abbastanza foto".
- **E-MEM2**: giorno di un anno passato fatto solo di screenshot, documenti o foto di WhatsApp: nessun "anni fa" per quel giorno (D5).
- **E-MEM3**: 29 febbraio: negli anni non bisestili il ricordo di un 29 febbraio esce il 28 (D3).
- **E-MEM4**: foto fatte in un altro fuso orario: conta il giorno locale dello scatto (E-M14, E-AL10); "oggi" segue il fuso del telefono.
- **E-MEM5**: un elemento di un ricordo pubblicato diventa nascosto: sparisce dal ricordo, dalla copertina e dalla presentazione; la notifica
  si toglie (D19); se restano meno di 3 elementi il ricordo esce.
- **E-MEM6**: un elemento viene eliminato o la sua area diventa inaccessibile (E-R10): esce dal ricordo; se torna (stesso id) non rientra
  in un ricordo già pubblicato.
- **E-MEM7**: foto dello stesso giorno arrivate dopo (backup dal tablet, scansione del NAS): un ricordo già pubblicato **non** le prende;
  quello dell'anno dopo sì (D9).
- **E-MEM8**: due dispositivi dello stesso account: "visto" e "non mostrare" valgono su entrambi dopo un giro dei dati personali; i contenuti
  possono differire (il tablet non ha i media del solo telefono), ed è dichiarato.
- **E-MEM9**: "Non mostrare questo ricordo": non torna su nessun dispositivo dell'account, nemmeno fra un anno (id deterministico).
- **E-MEM10**: persona unita a un'altra: il ricordo segue l'alias; persona nascosta, dimenticata o Persone disattivata e cancellata: i suoi
  ricordi escono subito dallo scaffale e le sue voci "mostra meno" diventano lapidi (D11, D37).
- **E-MEM11**: persona con "Mostra meno" in una foto di gruppo di un altro ricordo: può comparire, mai come copertina né nella notifica; con
  "Mai nei ricordi" nessuna sua foto.
- **E-MEM12**: periodo "Mai nei ricordi" a cavallo di due anni o sovrapposto a un altro: vale l'unione; un viaggio che lo tocca anche per un
  giorno non diventa un ricordo.
- **E-MEM13**: viaggio scartato con "Non mi interessa": nessun ricordo; viaggio diventato album: il ricordo offre "Apri l'album".
- **E-MEM14**: media solo sul NAS con il NAS irraggiungibile: miniature dalla replica; la presentazione usa la migliore immagine locale e non
  aspetta mai il NAS (E-L4).
- **E-MEM15**: qualità delle anteprime "Risparmio" (solo segnaposto per molti media del NAS): niente misura della nitidezza per quelli; la
  presentazione mostra ciò che c'è e, con l'app aperta sul Wi-Fi di casa, scarica le anteprime che servono come contenuto visibile (proposta 3).
- **E-MEM16**: dispositivo senza vettori (non indicizzatore, ricerca non ancora pronta): doppioni tolti con la firma visiva o con la finestra
  di 3 minuti; filtro di Q6 (b) spento; detto in Diagnostica.
- **E-MEM17**: video lunghi o 4K solo sul NAS: nella presentazione il fotogramma di copertina; il video si guarda dal viewer.
- **E-MEM18**: data dello scatto stimata o assurda (E-M8, E-M9): il media non entra nei ricordi legati a una data.
- **E-MEM19**: presentazione con TalkBack o animazioni ridotte: niente avanzamento automatico con TalkBack, niente movimento con le animazioni
  ridotte (D13).
- **E-MEM20**: primo avvio dopo l'aggiornamento con un archivio grande: la generazione non blocca la UI; i primi ricordi arrivano la prima
  notte o al primo passo leggero.
- **E-MEM21**: cambio del dataset dei luoghi: i titoli già pubblicati restano; i nuovi usano i nomi nuovi.
- **E-MEM22**: un "Mostra meno" fatto fuori casa, senza ora del NAS: vale subito qui e viaggia alla prima sincronizzazione (`effectiveEntries`).

**Notifiche (E-NT)**
- **E-NT1**: permesso negato o revocato: nessuna notifica, nessuna richiesta automatica; la pagina lo dice e offre "Consenti" da un tocco (D18).
- **E-NT2**: canale "Ricordi" spento dalle impostazioni di Android: rispettato; la pagina lo dice.
- **E-NT3**: telefono in Doze o spento all'ora scelta: la notifica arriva entro la finestra (fino a H+2 h); oltre H+3 h quel giorno si salta.
- **E-NT4**: cambio di fuso orario o dell'ora legale: l'ora scelta resta l'ora locale; il ritardo si ricalcola alla corsa successiva o all'apertura dell'app, quindi quel giorno la notifica può arrivare spostata (dentro la finestra o saltata), poi si riallinea.
- **E-NT5**: due esecuzioni nello stesso giorno (riavvio, ripianificazione, aggiornamento dell'app): al più una notifica (registro del giorno).
- **E-NT6**: nessun ricordo bello e non visto oggi: nessuna notifica.
- **E-NT7**: ricordo nascosto, scartato o rimasto vuoto dopo la notifica: la notifica si toglie; aprendola prima che sparisca si vede il
  ricordo senza i nascosti o "Questo ricordo non c'è più" (D19).
- **E-NT8**: blocco app attivo: il tocco apre la richiesta di sblocco, poi i Ricordi di oggi.
- **E-NT9**: un'altra app manda l'azione `OPEN_MEMORIES` (o un intent con extra o dati): si apre al più la pagina Ricordi, dietro il blocco
  app; tutto il resto si butta (D17).
- **E-NT10**: tablet condiviso: ogni profilo ha permesso, canale, ora e frequenza propri.
- **E-NT11**: "Non disturbare" o le modalità di Samsung: decide il sistema; l'app non prova ad aggirarlo.
- **E-NT12**: sistema impostato su "Mostra tutti i contenuti" nella schermata di blocco: con la modalità generica la notifica non ha niente da
  mostrare (D16).
- **E-NT13**: ricordo di una persona: mai il nome, né nella notifica né nella versione pubblica.
- **E-NT14**: risparmio energetico acceso: la notifica parte lo stesso (lavoro di pochi istanti); la generazione fa solo il passo leggero.
- **E-NT15**: azzeramento, dispositivo perso o uscita dalla famiglia: notifica tolta e lavoro annullato prima di cancellare i dati.

**Mappa (E-MAP)**
- **E-MAP1**: mappa online spenta: **nessuna richiesta di rete** dalla mappa, dalla mini-mappa o dalle regioni; si vedono la carta di base, le
  regioni scaricate e la cache.
- **E-MAP2**: mappa online accesa ma sui dati mobili (con "Solo sul Wi-Fi") o in roaming: nessuna richiesta; la carta di base con l'avviso
  "Mappa dettagliata solo sul Wi-Fi".
- **E-MAP3**: server lento, irraggiungibile o con errori: cache e carta di base; nuovi tentativi con attesa crescente, mai a raffica; nessun
  messaggio d'errore a ogni tessera.
- **E-MAP4**: il server risponde con un redirect verso un altro host, o con un tipo di contenuto inatteso: richiesta rifiutata e contata
  (come E-S16 per i modelli).
- **E-MAP5**: media senza posizione: non sono sulla mappa; la sezione Luoghi dice quanti sono.
- **E-MAP6**: media solo sul NAS con la posizione nell'indice o nell'EXIF: sulla mappa come gli altri; con il NAS irraggiungibile le bolle
  usano la replica e il viewer si comporta come E-L4.
- **E-MAP7**: coordinate assurde (fuori dai limiti, esattamente (0, 0), latitudine oltre ±85°): escluse dai punti, contate in Diagnostica.
- **E-MAP8**: posizione corretta nell'app (E-L8, E-E8): la mappa usa quella corretta.
- **E-MAP9**: migliaia di foto nello stesso punto: una bolla sola; la griglia della zona dal più recente, scorrevole come la Libreria.
- **E-MAP10**: foto vicino all'antimeridiano o ai poli: celle e conteggi giusti ai bordi della proiezione; latitudini oltre ±85° escluse.
- **E-MAP11**: scaricamento di una regione interrotto (Wi-Fi che cade, app chiusa, processo terminato): riprende all'apertura dell'app; la
  regione resta "parziale" finché non è completa; le tessere già arrivate valgono.
- **E-MAP12**: spazio insufficiente per una regione: stima prima, rifiuto motivato con lo spazio che serve; mai sotto 1 GiB libero (E-P3).
- **E-MAP13**: il server cambia versione dei dati (ogni settimana): l'URL stabile tiene valide cache e regioni; le regioni si aggiornano solo
  a mano.
- **E-MAP14**: nomi di luoghi con caratteri fuori dai glifi inclusi: non si disegnano (nessuna richiesta di caratteri online); lo stile usa i
  nomi in italiano o latini dove ci sono.
- **E-MAP15**: un media diventa nascosto con la mappa aperta: esce da bolle, conteggi e griglia al prossimo aggiornamento dell'indice; la
  griglia viva lo toglie subito (E-H9).
- **E-MAP16**: mini-mappa nelle info di una foto in una zona non scaricata: solo la carta di base con il punto, mai una richiesta online.
- **E-MAP17**: rotazione, finestre, tablet: camera e bolle si conservano; nessuna richiesta nuova solo per la rotazione se le tessere sono in
  cache.
- **E-MAP18**: foto scattate a casa: sulla mappa come le altre, senza nessuna etichetta "Casa"; la prima apertura non si centra lì (D29).
- **E-MAP19**: "Mostra sulla mappa" dal viewer con la mappa online accesa: la camera va su quel punto e chiede le tessere di quella zona
  (azione dell'utente, detta nella pagina sulla privacy); con la mappa spenta, carta di base e regioni.
- **E-MAP20**: la mappa online si spegne mentre una regione si scarica: lo scaricamento si ferma (riprende solo con un tocco).

**Nascosti (E-H)**
- **E-H8**: un media nascosto non compare in nessun ricordo, copertina, presentazione o notifica, nemmeno se era nel ricordo prima di essere
  nascosto (E-MEM5).
- **E-H9**: un media nascosto non compare sulla mappa: né punto, né conteggio, né bolla, né copertina, né griglia della zona, né luogo della
  sezione Luoghi; "Mostra sulla mappa" non si offre aprendolo dai Nascosti.
- **E-H10**: una persona nascosta non ha ricordi e non è mai copertina di un ricordo (E-F28).

### 0.3 Modifiche da fare in P0 (agli altri documenti)

**`docs/formato-nas.md`** (nessuna versione nuova, nessun tipo nuovo)
- **A1, §5.5-bis**: il tipo `memory` (già nel codice, mai documentato): soggetto = `MemoryId` (26 caratteri base32, D9); campi `seen`
  (intero `aaaammgg`, giorno locale della visione; nessuna voce `seen` è mai stata scritta prima di questa fase, quindi il cambio di significato non tocca dati esistenti) e
  `dismissed` (vero); nessuna impronta (`fp` assente).
- **A2, §5.5-bis "Campi della Fase 9"**: in `setting`, `memories_less:person:<id>` (testo `less` o `never`; lapide = tolto),
  `memories_never:days:<aaaammgg>-<aaaammgg>` (vero; al più 366 giorni), `memories_people` (vero/falso, Q9); un valore che non si capisce
  vale "nessuna scelta" e non rovina le altre voci; un'app della Fase 8 li conserva senza usarli.

**`docs/sicurezza.md`**: §1 (una riga: ricordi e mappa restano sul dispositivo; la mappa online è una scelta di ognuno e dice cosa esce); §2
(bene "Ricordi e scelte" e "Cache e regioni della mappa": dove stanno); **L13 riscritta** (§6.2: cosa vede il server, cosa no, come si evita);
C17 (l'azione fissa `OPEN_MEMORIES`, D17), C21 (l'eccezione della mappa online, scelta per dispositivo), C27 (superfici `MEMORIES`,
`NOTIFICATIONS`, `MAP`), C31 (la casa non entra nella mappa, D29); **C128–C135** nuove (§6.1); §4-bis (riga "Notifiche" con le due modalità;
riga "Rete verso Internet: mappa"; nessun permesso nuovo); §6 **L232–L245** (§6.2); §7 checklist della Fase 9 riscritta (§6.3); §8 righe per
D8, D11, D16, D17, D21–D26, D29, D35.

**`docs/modello-dati.md`**: nuovo §16 "Dati locali della Fase 9" (scaffale dei ricordi, misure dei pixel, registro delle notifiche, database di
MapLibre; nessuna tabella nuova nel database della galleria, che resta v6); la query dei punti della mappa nel capitolo delle query.

**`docs/decisioni.md`**: sezione "Fase 9" con D1–D38 e le risposte Q1–Q9 del §9.

**`docs/fase3-progetto.md`**: nota a D18 (la mini-mappa arriva, solo offline, D29).

**`docs/fase4-progetto.md`**: nota al §2.9 (il valore di `seen` è il giorno locale, D10).

**`docs/fase6-progetto.md`**: §2.9, le righe della Fase 9 rimandano a questo documento (vettori per doppioni, varietà e filtro di Q6;
dizionario dei luoghi per la sezione Luoghi; il ritagliatore **non** serve: le copertine sono foto intere, §3).

**`docs/fase7-progetto.md`**: §2.9, la riga "Viaggi ed eventi, casa locale → Fase 9" con il rimando a D12 e D29; nota a D33 (sezioni `memories` e
`places`).

**`docs/fase8-progetto.md`**: §2.11, la riga "Fase 9" con il rimando a D11 e D12 (`memories_less` nei dati personali, non nella classe `people`).

**`docs/regole-attivita.md`**: §2 (la generazione dei ricordi non è un'attività: notte dentro `LibraryMaintenanceWorker`, passo leggero
all'ora della notifica; scaricamento delle regioni come contenuto chiesto dall'utente); §6 costanti nuove (`memoriesNightlyBudget`,
`memoriesLightBudget`, `notifyWindow`, `mapIndexRebuild`).

**`docs/android.md`**: Tema 1, riga sulle notifiche a un'ora scelta con WorkManager (finestra e Doze) e sul piano B `setWindow`.

**`docs/grafica-progetto.md`**, "Dubbi grafici" §12.15: sezione Ricordi e Luoghi, pagina del ricordo, presentazione (movimento, passaggi,
carta finale), fogli "Mostra meno", pagina Ricordi e notifiche, mappa (bolle, griglia della zona, pagina sulla privacy, regioni), mini-mappa
del viewer, notifica (testo e immagine).

**`docs/luoghi.md`**: §10, l'attribuzione si estende alle città della carta di base (stesso testo GeoNames) e si aggiungono Natural Earth,
OpenStreetMap/OpenMapTiles/OpenFreeMap (§2.7).

**`README.md`**: moduli nuovi e grafo.

**Piano** (lo riporta l'orchestratore): edge case del §0.2; 9.1 precisata con i tipi e il "Mai nei ricordi" (D3, D11); 9.2 con la modalità
generica della schermata di blocco (D16); 9.3 con la carta di base offline e la mappa online spenta di default (D24, D25); proposta 12
precisata (frequenza di Q4); §7 "Limiti" con L232–L245; §9 "Punti aperti": nessuno nuovo.

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)

```
core-model       nessuna aggiunta (MemoryKind e DayRange stanno nei contratti di core-memories)                     (—)
core-personal    + campi di MEMORY (seen come giorno, dismissed) e di SETTING (memories_less, memories_never,
                   memories_people); SyncedMemoriesSettings; PersonalMemories con il giorno                         (P0 firme, P6)
core-albums      + trips/: porta TripMemories (TripDigest senza coordinate), nell'elenco chiuso di TripPrivacyTest  (P0 firme, P8)
core-database    + dao/MapPointsQueries.kt: punti visibili con coordinate effettive, a pagine; schema v6 invariato    (P3)
core-memories    → common, model, library (contratti), personal (contratti), places (contratti)                    (P0)
                   contract/  Memories, MemoryShelf, porte (MemoryTrips, MemoryPeople, MemoryVectors, MemoryPixels,
                              MemoryClips, MemoryClock), decisioni sulle notifiche
                   plan/      generatori dei quattro tipi, regole di D3, scelta del giorno                           (P6)
                   pick/      candidati, esclusi, punteggio, doppioni, varieta', filtro di Q6                       (P7)
                   shelf/     scaffale stabile, revoca degli elementi, file atomico                                 (P6)
                   settings/  visti, scartati, "mostra meno", PeopleLessJanitor                                     (P6, P8)
                   notify/    ora, finestra, registro del giorno, scelta del ricordo, contenuto per modalita'       (P10)
                   NON vede: faces, search, albums, ai, nas, nasops, network, database, rete, Android
feature-memories → memories (contratti), library (viste), imaging, designsystem                                     (P9)
                   cieca a core-faces, core-search, core-albums, core-map, feature-*
core-map         → common, model, library (contratti), places (contratti)                                           (P0)
                   contract/  MapPoints, GeoClusterIndex, MapRegions, MapSettings, TilePolicy, porte
                   points/    indice a chiavi di Morton, livelli, copertine, viste                                  (P3)
                   regions/   catalogo, stime, copertura delle tessere                                              (P5)
                   policy/    decisione pura su ogni richiesta di rete della mappa                                  (P1)
                   NON vede: rete, NAS, database, Android
feature-map      → map (contratti), library (viste), imaging, designsystem, MapLibre                                (P1, P2, P4, P5)
                   map/ grid/ places/ privacy/ regions/ mini/     schermate, bolle, carta di base, mini-mappa
                   net/       MapTileHttp: l'UNICO codice di rete della fase (OkHttp per MapLibre + intercettore)    (P1)
                   cieca a core-network (stato della rete da una porta), core-nas, core-albums, core-memories
feature-library  + CollectionSections.PLACES = "places"                                                             (P0)
feature-viewer   + porta ViewerPlaceMap in ViewerServices; riga "Mostra sulla mappa" e mini-mappa nelle info        (P0 firme, P4)
feature-settings + voci "Ricordi e notifiche" e "Mappa" (aprono le pagine dei due feature)                           (P9, P4)
core-common      + DiagSection.MEMORIES e MAP, FeatureFlags.mapOnline, Phase9ArchitectureTest, eccezione di rete    (P0)
app → tutti                                                                                                         (P13)
```
- `core-memories` e `core-map` sono **JVM puri**: nessuna classe `android.*` (test d'architettura), quindi generazione, scelta, raggruppamento e
  politica di rete girano sulla JVM nei test e nei banchi.
- **Rete**: `Phase9ArchitectureTest` allarga la regola 5 della Fase 6 a **due** pacchetti ammessi (`core-models/.../download/https/` e
  `feature-map/.../net/`); fuori da lì nessun nome di client HTTP/TLS, nemmeno in `feature-map`; `org.maplibre` e `okhttp3` solo in
  `feature-map`; `check.ps1` controlla nel dex che OkHttp sia chiamato solo da `feature/map/net/` e dalle classi di MapLibre. Nessuna nuova
  eccezione a `NoNetworkSources` oltre a quella dichiarata.
- **Conferme e consensi**: `MapOnlineConsent` lo costruisce solo la pagina sulla privacy, `RegionDownloadConfirmation` solo il foglio della
  regione (test d'architettura, come `TextReadingConsent` e `FacesActivationConsent`).
- `feature-memories` e `feature-map` non si conoscono e non conoscono `feature-viewer`, `feature-people`, `feature-albums`: aprono viewer,
  album e pagine delle persone con azioni verso `:app`.
- `core-albums/.../trips/` resta l'unico che conosce la casa; la porta nuova non ha `GeoPoint`, `Double` né la tabella `home_estimate` nella
  firma (controllo per riflessione di `TripPrivacyTest`).
- Restano le regole delle Fasi 4–8: solo `core-nasops` vede `NasFileOps`; i `feature-*` non si conoscono; `core-search` e `core-albums` non
  vedono `core-faces`; LiteRT solo in `core-ai`.

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-memories` (P0 contratti)**
| Elemento | Firma | Note |
|---|---|---|
| Identità | `@JvmInline value class MemoryId(val value: String)` (26 caratteri base32); `object MemoryIds { fun of(kind: MemoryKind, key: MemoryKey): MemoryId }`; `sealed interface MemoryKey { OnThisDay(day: LocalDate); Trip(days: DayRange); Event(day: LocalDate, startQuarter: Int); Person(id: PersonRef, window: DayRange); Month(month: YearMonth) }`; `enum class MemoryKind(code) { ON_THIS_DAY("otd"), TRIP("trip"), EVENT("event"), PERSON("person"), MONTH("month") }` | D9; `toString()` senza date né id; `PersonRef` è l'id della persona come testo opaco e `DayRange(from, to)` è un intervallo di giorni locali proprio (`core-memories` non vede `core-faces` né `core-albums`, dove sta `LocalDayRange`) |
| Ricordo | `data class Memory(val id: MemoryId, val kind: MemoryKind, val title: MemoryTitle, val members: List<MediaId>, val cover: MediaId, val publishedDay: Int, val origin: MemoryOrigin?)`; `data class MemoryTitle(val yearsAgo: Int?, val days: DayRange, val placeIds: IntArray, val tripLabel: String?, val person: PersonRef?, val month: YearMonth?)`; `sealed interface MemoryOrigin { TripAlbum(album: AlbumRef) }` | titolo come **dati**, testo composto dalla UI (nome della persona letto al momento) |
| Servizio | `interface Memories { val shelf: StateFlow<ShelfView>; fun memory(id: MemoryId): Flow<MemoryView?>; suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport; suspend fun markSeen(id: MemoryId); suspend fun dismiss(id: MemoryId); suspend fun setLess(target: LessTarget, level: LessLevel?); val lessChoices: StateFlow<List<LessChoice>> }` | `RefreshReason { NIGHTLY, LIGHT, OPEN }`; `LessTarget { Person(ref); Days(range) }`; `LessLevel { LESS, NEVER }` (`null` = togli) |
| Vista | `data class MemoryView(val memory: Memory, val view: OrderedTimelineView, val cover: MediaId, val seen: Boolean, val personName: String?)`; `data class ShelfView(val items: List<MemoryCard>, val unseen: Int, val state: ShelfState)`; `ShelfState { Ready; Empty(reason); Working }` | la vista si rifà sull'istantanea visibile (nascosti fuori); `personName` dalla porta, mai salvato |
| Generatori | `interface MemoryPlanner { fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> }`; `data class PlanInput(val cells: CellTable, val trips: List<TripDigestView>, val people: List<PersonDigest>, val choices: Choices, val history: SeenHistory)` | **puro e deterministico**; `CellTable` è un'estrazione a colonne dell'istantanea (giorno, ora, sorgente, flag, tipo, durata, luogo, gruppo) |
| Scelta | `interface PhotoPicker { fun pick(candidates: List<CandidateCell>, spec: PickSpec, signals: PickSignals): PickResult }`; `data class PickSignals(val vectors: Map<MediaId, FloatArray>?, val pixels: Map<MediaId, PixelProbe>, val sad: Set<MediaId>, val albums: Set<MediaId>, val people: Map<MediaId, Int>)` | D5–D8; puro; `PickResult(members, cover, dropped: Map<DropReason, Int>)` (solo conteggi) |
| Scaffale | `interface MemoryShelfStore { suspend fun read(): ShelfFile?; suspend fun write(file: ShelfFile) }`; `class FileShelfStore(dir)` (scrittura atomica, CRC, versione del file) | D9, D10; un file rovinato = scaffale vuoto e rigenerazione, mai un crollo |
| Porte | `interface MemoryTrips { suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> }`; `interface MemoryPeople { val available: StateFlow<Boolean>; suspend fun named(): List<PersonDigest>; suspend fun cells(ref: PersonRef): Set<MediaId>; fun resolve(ref: PersonRef): PersonRef?; suspend fun label(ref: PersonRef): String? }`; `interface MemoryVectors { suspend fun vectors(ids: Collection<MediaId>): Map<MediaId, FloatArray>; suspend fun sadScores(ids: Collection<MediaId>): Set<MediaId> }`; `fun interface MemoryPixels { suspend fun probe(ids: Collection<MediaId>, budget: Duration): Map<MediaId, PixelProbe> }`; `fun interface MemoryClips { suspend fun clip(id: MediaId): ClipSource? }`; `interface MemoryClock { fun today(): LocalDate; fun nowLocal(): LocalDateTime; fun monotonic(): Long }` | legate da `:app`; null-object che rispondono "niente" (nessun ricordo di viaggio, nessuna persona, nessun vettore) |
| Notifiche (decisioni) | `object MemoryNotifyPolicy { fun nextSlot(now: LocalDateTime, prefs: NotifyPrefs): LocalDateTime?; fun choose(shelf: ShelfView, prefs: NotifyPrefs, ledger: NotifyLedger, today: LocalDate, openedToday: Boolean): MemoryId?; fun content(memory: Memory, mode: LockScreenMode): NotifyContent }`; `data class NotifyPrefs(val enabled: Boolean, val hour: LocalTime, val frequency: NotifyFrequency, val lockScreen: LockScreenMode)`; `enum class NotifyFrequency { DAILY, FEW_PER_WEEK, ANNIVERSARIES, OFF }`; `enum class LockScreenMode { GENERIC, TITLE_AND_PHOTO }`; `data class NotifyContent(val title: TextRef, val text: TextRef, val image: MediaId?, val publicTitle: TextRef)` | D15, D16, D20; puro; `TextRef` = id di stringa con argomenti, mai testo dell'utente nel contenuto generico |
| Stato | `sealed interface MemoriesStatus { Idle(lastDay: Int); Working; Problem(code: String) }` | numeri e codici |

**`core-map` (P0 contratti)**
| Elemento | Firma | Note |
|---|---|---|
| Punti | `data class MapPoint(val id: MediaId, val latE6: Int, val lonE6: Int, val day: Int, val score: Short)`; `interface MapPointSource { fun pages(): Flow<List<MapPoint>> }` (porta verso `core-database`); `interface MapPoints { val index: StateFlow<GeoIndexState>; suspend fun rebuild(reason: RebuildReason) }`; `GeoIndexState { Building; Ready(index: GeoClusterIndex, withoutGps: Int); Empty }` | D27; `toString()` senza coordinate |
| Indice | `class GeoClusterIndex { fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster>; fun members(key: CellKey, limit: Int): List<MediaId>; fun boundsOfAll(): GeoBox? }`; `data class GeoCluster(val key: CellKey, val center: GeoE6, val count: Int, val cover: MediaId, val placeId: Int?)` | D28; `members` dal più recente; `boundsOfAll` per la prima camera (con lo zoom limitato a 5, D29) |
| Politica | `object TilePolicy { fun decide(req: MapRequestFacts, state: MapNetState): MapVerdict }`; `data class MapRequestFacts(val method: String, val scheme: String, val host: String, val port: Int, val path: String, val query: String?)`; `data class MapNetState(val online: Boolean, val network: MapNetwork, val mobileAllowed: Boolean, val covered: (TileKey) -> Boolean, val currentVersion: String?)`; `sealed interface MapVerdict { Allow(rewrittenPath: String); Refuse(reason: RefuseReason) }` | D22, D23; **pura**: la stessa funzione la usano l'intercettore, i test di proprietà e il banco |
| Regioni | `interface MapRegions { val regions: StateFlow<List<RegionState>>; fun catalog(): List<RegionSpec>; suspend fun estimate(spec: RegionSpec): RegionEstimate; suspend fun download(spec: RegionSpec, confirmation: RegionDownloadConfirmation): DownloadStart; suspend fun resume(id: RegionId); suspend fun delete(id: RegionId); fun covers(tile: TileKey): Boolean }`; `RegionSpec { Named(code, box); AroundPlace(placeId, radiusKm) }` | D26; `RegionDownloadConfirmation` solo dal foglio; la regione "attorno a un luogo" prende il centro dal luogo GeoNames, mai dalla casa |
| Impostazioni | `interface MapSettings { val online: StateFlow<MapOnline>; suspend fun enable(consent: MapOnlineConsent); suspend fun disable(); val wifiOnly: StateFlow<Boolean>; suspend fun setWifiOnly(on: Boolean); val lastCamera: StateFlow<CameraSpot?>; suspend fun saveCamera(spot: CameraSpot?) }`; `MapOnline { Off; On(textVersion) ; Unavailable }` | D24; DataStore locale; `Unavailable` con `FeatureFlags.mapOnline` spento |
| Porte | `interface MapNetworkState { val current: StateFlow<MapNetwork> }` (`WIFI`, `MOBILE`, `ROAMING`, `NONE`, legata a `core-network`); `interface MapTraffic { fun record(bytes: Long, network: MapNetwork) }` (al registro del traffico, come contenuto visibile) | `core-map` non vede `core-network` |

**`core-albums` (P0 firme, P8)**: `interface TripMemories { suspend fun past(until: LocalDate, budget: Duration): List<TripDigest> }`; `data class
TripDigest(val id: TripId, val kind: TripKind, val days: LocalDayRange, val label: String, val placeIds: List<Int>, val members: List<MediaId>,
val album: AlbumId?)`; implementata in `trips/` sopra `TripReader` (solo media propri e visibili, `visibleOrThrow`), scartati esclusi
(`trip_dismissed` per id e per `startKey`), accettati compresi; nessuna coordinata.

**`core-personal` (P0 firme, P6)**: `PersonalFields.MEMORY_DISMISSED = "dismissed"`, `memoriesLess(personRef)`, `memoriesNeverDays(range)`,
`SETTING_MEMORIES_PEOPLE`; `PersonalMemories.markSeen(memoryId: String, day: Int)`, `seenDay(memoryId)`, `dismiss(memoryId)`, `isDismissed`;
`SyncedMemoriesSettings` (lettura da `effectiveEntries`, scrittura con `store.set`, lapidi per il janitor). Nessun `PersonalKind` nuovo
(`Phase7ArchitectureTest.nessunPersonalKindNuovo` resta verde).

**`core-database` (P3)**: `MapPointsQueries.page(after: MediaId?, limit: Int): List<MapPointRow>` (`media_id`, latitudine e longitudine
effettive, `local_day`, flag) sulle sole celle visibili, ordinata per `media_id`, senza `USE TEMP B-TREE`; nessuna tabella né indice nuovo.

**`feature-viewer` (P0 firme, P4)**: `interface ViewerPlaceMap { fun miniMap(media: ViewerMediaId, sizePx: Int): Flow<ImageBitmap?>; fun show(media:
ViewerMediaId) }` in `ViewerServices` (null di serie: nessuna riga, nessuna mini-mappa); `feature-viewer` non vede `core-map` né MapLibre.

### 1.3 Aggiunte a moduli esistenti

| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | nessuna aggiunta: `MemoryKind` e `DayRange` stanno nei contratti di `core-memories`; le coordinate restano `GeoPoint` dove sono già | — |
| `core-personal` | campi di `MEMORY` e `SETTING` (D10, D11), `PersonalMemories` con il giorno, `SyncedMemoriesSettings`; test: un'app della Fase 8 legge i segmenti con i campi nuovi senza scartarli (codec della versione precedente) | P0 (firme), P6 |
| `core-albums` | `contract/TripMemories.kt` (firma, null-object, fake `InMemoryTripMemories`); implementazione in `trips/` sopra `TripReader`; `TripPrivacyTest` con il membro nuovo nell'elenco chiuso | P0 (firme), P8 |
| `core-database` | `dao/MapPointsQueries.kt` e il suo test di prestazioni (100 mila righe); nessuna migrazione | P3 |
| `core-library` | nessuna API nuova: ricordi e mappa usano `VisibleMedia`, `HiddenMask`, `OrderedTimelineView`, `IndexOrderedTimelineView` | — |
| `core-common` | `DiagSection.MEMORIES`, `DiagSection.MAP`; `FeatureFlags.mapOnline` (acceso, spegnibile per build); `Phase9ArchitectureTest` (moduli puri, rete in due pacchetti, MapLibre e OkHttp solo in `feature-map`, consensi costruiti solo dalle loro pagine, `TripMemories` senza coordinate, nessun `PersonalKind` nuovo) | P0 |
| `feature-library` | `CollectionSections.PLACES = "places"` dopo `memories` nell'ordine predefinito (non riservata: compare quando c'è un fornitore); `CollectionsLayoutTest` aggiornato | P0 |
| `feature-viewer` | porta `ViewerPlaceMap` (P0); riga "Mostra sulla mappa" e mini-mappa in `info/` (solo P4) | P0, P4 |
| `feature-settings` | voci "Ricordi e notifiche" (sezione "Libreria") e "Mappa" (sezione "Protezione", perché decide cosa esce), come callback nullable di `SettingsLinks` | P9, P4 |
| `feature-diagnostics` | sezioni Ricordi e Mappa, codici del §2.12, lavori `MEMORIES_GENERATE`, `MEMORIES_NOTIFY`, `MAP_REGION` in `DiagJob`, chiavi in `ExportKeys` | P13 |
| `benchmark` | `MemoriesBenchmark` e `MapBenchmark`, scenari `ricordi` e `mappa` in `soglie.json` | P12 |
| `app/src/benchmarkRelease` | coordinate e giorni "di un anno fa" nella Libreria da 100 mila (`BenchSeeder`), scaffale seminato | P12 |
| `tools/` | `tools/maps/base/` (carta di base riproducibile), `tools/maps/style/` (stile, glifi, sprite e licenze), `tools/fixtures/memories/` (Libreria di prova con date relative a oggi), `s25.ps1 -Mode memories` e `-Mode map`, controlli in `check.ps1` | P0, P1, P2, P11, P12 |

### 1.4 Cosa resta in `:app` (P13)

- **`MemoriesGraph`** in `app/.../memories/`: nasce con la famiglia `Ready`, vive **anche senza NAS** (tutto locale); legge l'istantanea visibile
  dal `GalleryGraph`; porte: `MemoryTrips` → `TripMemories` di `AlbumsGraph`; `MemoryPeople` → `PeopleIndex`/`PeopleLookup`/`FacesSwitch` del
  `FacesGraph` (dopo P13 della Fase 8; prima il null-object); `MemoryVectors` → `SearchData.vectors` e `QueryEmbedding` (le frasi di Q6, solo se
  il modello delle query c'è); `MemoryPixels` → miniature locali di `core-imaging` (mai il NAS, mai la rete); `MemoryClips` → il lettore del
  viewer solo per i video del telefono; `MemoryClock` → `Today` e l'orologio monotono.
- **`MapGraph`** in `app/.../map/`: `MapPointSource` → `MapPointsQueries`; `MapNetworkState` → `core-network` (rete in uso, roaming); `MapTraffic`
  → registro del traffico come contenuto visibile; inizializzazione di MapLibre **pigra** alla prima apertura della mappa o della mini-mappa,
  con `MapTileHttp` installato prima di tutto (test: nessuna `MapView` nasce senza il client).
- **Lavori**: la generazione notturna dentro `LibraryMaintenanceWorker` (dopo i viaggi, budget 60 s); `MemoryNotifyWorker`
  (`work/MemoriesWork.kt`, `OneTimeWork` unico `memories-notify` ripianificato a ogni corsa, D20; passo leggero, scelta, notifica); nessun
  lavoro per la mappa (le regioni si scaricano nel processo, E-MAP11).
- **Notifiche**: canale `memories` e id 1009 in `AppNotifications` (`createChannels` resta idempotente); `MemoryNotifier` (pubblica, revoca,
  `revalidate()` a ogni cambio di nascosti e di scelte).
- **`MainActivity`**: l'azione fissa `OPEN_MEMORIES` riconosciuta durante la pulizia dell'intent, consegnata come evento interno a
  `RootViewModel` dopo il blocco app (D17); `EntryHardeningTest` esteso.
- **Navigazione**: fornitori delle sezioni `memories` e `places` (`CollectionsModule`, `CollectionsSectionsWiring`), voci in
  `CollectionEntryRouting` (`m_…`, `map`, `place_…`), pagine dei due feature, azioni verso il viewer (`AlbumViewerItems` come modello),
  "Apri l'album" del viaggio, le due voci delle Impostazioni, `ViewerPlaceMap` nei `ViewerServices`.
- **Azzeramento** (D37): `MemoriesGraph.stop()` e `MapGraph.stop()` per primi; `AppLocalDataEraser` toglie `memories/`, `map/` e i file di
  valutazione; notifica tolta e lavoro annullato.
- **Memoria**: `onTrimMemory` rilascia la mappa (MapLibre) e le immagini precaricate della presentazione.

### 1.5 Confine con il filone grafico: stati e contratti

La Fase 9 decide **cosa** si mostra e **quando**; il filone grafico decide **come** (D34). Composable fatte solo con i componenti G2.

| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `MemoriesSectionState` | `Ready(cards, unseen)`, `Empty(reason)`, `Working` | Raccolte → Ricordi | G2 |
| `MemoryPageState` | `Ready(title, subtitle, view, cover, actions, origin)`, `Gone` ("Questo ricordo non c'è più"), `NotFound` | pagina del ricordo | G2 |
| `MemoriesListState` | `Ready(byMonth)`, `Empty` | "Tutti i ricordi" | G2 |
| `PlayerState` | `Playing(index, item, next)`, `Paused`, `Ended`, con `reducedMotion` e `talkBack` | presentazione | **G3** (movimento, passaggi) |
| `LessSheetState` | `Person(name, level)`, `Days(range)`, `Confirm`, `Undone` | fogli "Mostra meno" e "Mai nei ricordi" | G2 |
| `MemoriesSettingsState` | ricordi con le persone (Q9), notifiche (accese, ora, frequenza, schermata di blocco), permesso o canale spenti, "Persone e periodi nascosti dai ricordi", filtro di Q6 | Impostazioni → Ricordi e notifiche | G2 |
| `MapScreenState` | `Loading`, `Ready(online, notice, withoutGps)`, `Empty` | mappa | G2 (+ MapLibre) |
| `MapBubble` | `Cluster(count, cover, label)`, `Single(cover)` | bolle sopra la mappa | **G3** |
| `ZoneGridState` | `Ready(title, view)`, `Empty` | griglia di una zona | G2 |
| `PlacesSectionState` | `Ready(mapRow, places)`, `NoGps` | Raccolte → Luoghi | G2 |
| `MapPrivacyState` | `Explain(textVersion)`, `Enabled`, `Unavailable` | pagina sulla privacy della mappa | G2 |
| `RegionsState` | `Catalog`, `Estimate(size, freeSpace)`, `Downloading(percent)`, `Partial`, `Ready(size, age)`, `NeedsUpdate` | regioni offline | G2 |
| `MiniMapState` | `Ready(bitmap)`, `NoPosition` | info del viewer | G3 |
| `NotifyContent` | generica o con titolo e foto | notifica | sistema |

**Chi tocca cosa**
- La Fase 9 **non** modifica `core-designsystem`; il filone grafico non modifica contratti e ViewModel.
- File condivisi a rischio: `CollectionSections.kt` e `CollectionsLayoutTest` (solo P0), `feature-viewer/.../info/InfoBuilder.kt` (solo P4),
  `ViewerServices` (P0 la firma), `core-albums/.../trips/` e `TripPrivacyTest` (solo P8), `AppNotifications.kt` e `MainActivity.kt` (solo P10),
  `GalleryWork.kt` (solo P13, una chiamata), `MediaCatalog.kt` (nessuno: la query sta in un file nuovo), `AppNavHost.kt`, `CollectionsModule`,
  `LocalData.kt` (solo P13), `gradle/verification-metadata.xml` e `versions-app.gradle` (solo P1), `check.ps1` (P0 e P1).
- Stringhe in file propri: `strings_memories_*.xml`, `strings_map_*.xml`.

## 2. Flussi

### 2.1 Generazione dei ricordi (9.1; P6, P8)
**Il giro** (`Memories.refresh`, uguale nei tre momenti di D4; cambia solo il budget)
| Passo | Cosa | Dettagli |
|---|---|---|
| 1. Istantanea | l'istantanea visibile (`VisibleMedia.snapshot`) → `CellTable` a colonne (giorno locale, ora, sorgente, flag, tipo, durata, luogo, gruppo) e un **indice per giorno** (posizioni per `localDay`) | 100 mila celle in ~20 ms [stima]; si rifà solo se `seq` è cambiato |
| 2. Scelte | visti, scartati, "Mostra meno", "Mai nei ricordi" da `SyncedMemoriesSettings` (`effectiveEntries`: valgono anche le scelte non ancora datate) | E-MEM22 |
| 3. Fonti | viaggi (`MemoryTrips.past`), persone (`MemoryPeople.named`, se disponibili e se Q9 è sì) | null-object = quei tipi tacciono |
| 4. Candidati del giorno | `MemoryPlanner.plan(input, today)`: per ogni tipo le chiavi di oggi (D3), scartate quelle già viste negli ultimi 12 mesi, scartate o toccate da un "Mai" | puro; ordine deterministico (tipo, poi chiave) |
| 5. Scelta delle foto | `PhotoPicker.pick` per ogni candidato (§2.2), con vettori e misure dei pixel chieste **una volta** per tutti i candidati | budget: nel passo leggero niente pixel se il risparmio energetico è acceso |
| 6. Pubblicazione | al più 2 ricordi nuovi (più il mese), in ordine di priorità: anniversario di un viaggio, "anni fa" più recente, persona, riscoperta, "anni fa" più vecchio | scrittura atomica dello scaffale; un ricordo già pubblicato non si tocca (D9) |
| 7. Pulizia dello scaffale | togliere elementi diventati nascosti o eliminati (`visibleOrThrow`: un guasto del database non svuota lo scaffale, R17), ricordi sotto 3 elementi, ricordi oltre 30 giorni o oltre i 60 | E-MEM5, E-MEM6 |
| 8. Notifica | solo nel passo dell'ora scelta: `MemoryNotifyPolicy.choose` (§2.6) | — |

**I quattro tipi in dettaglio** (soglie provvisorie, calibrate in P11)
| Tipo | Chiave (D9) | Quando si propone | Candidati | Titolo (dati) |
|---|---|---|---|---|
| "Anni fa oggi" | `otd:<giorno d'origine>` | ogni giorno, per ogni anno passato con almeno 6 candidati buoni dopo le esclusioni; i due migliori (il più recente e il più vecchio) | media di quel giorno locale (D5); 29/2 → 28/2 | "Un anno fa", "3 anni fa"; data; luoghi (`PlaceNames.summary` dei `placeAt`) |
| Viaggio, evento | `trip:<inizio>:<fine>`, `event:<giorno>:<quarto d'ora>` | anniversario (inizio a ±3 giorni, anni passati); riscoperta (più di un anno fa, non mostrato in 12 mesi, al più una a settimana, il viaggio più lungo prima) | `TripDigest.members` (propri e visibili) | `tripLabel` della Fase 7 ("Oslo, marzo 2025"); "Un anno fa" se è un anniversario |
| Persona | `person:<id>:<anno>` o `person:<id>:recenti` | al più una a settimana; persona con nome, non nascosta, senza "Mostra meno", con ≥ 20 foto visibili in ≥ 3 giorni; un anno con ≥ 15 foto della persona o, altrimenti, "negli ultimi mesi" | `MemoryPeople.cells` ∩ celle visibili | "Con <nome>", "<nome> nel 2024" (nome letto al momento) |
| Il meglio del mese | `month:<aaaa-mm>` | dal 1° al 7 del mese, per il mese prima, se ≥ 40 candidati | media del mese (D5) | "Il meglio di settembre 2026" |

**Costi** (S25 [stima], misurati in P6 sulla JVM e in F124 sul telefono)
| Voce | Valore | Note |
|---|---|---|
| Tabella e indice per giorno | ~20 ms per 100 mila celle | solo a `seq` nuovo |
| Generatori | < 50 ms | ricerche per giorno, nessuna scansione per tipo |
| Vettori dei candidati | ≤ 1.000 vettori (~1,5 MB in float16) dalla replica locale, < 100 ms | solo dove i vettori ci sono |
| Doppioni e varietà | ≤ 200 candidati per ricordo, ~40.000 prodotti scalari a 768 → < 30 ms | |
| Pixel | ≤ 200 miniature locali da 256 px, ~2 ms l'una → < 0,5 s, poi in cache | solo di notte o con la batteria normale |
| Totale | notte < 2 s; passo leggero < 300 ms | budget 60 s e 5 s (D4) |

| Edge case | Comportamento |
|---|---|
| E-MEM1, E-MEM2, E-MEM3, E-MEM4, E-MEM18, E-MEM20 | §0.2 |
| E-H1, E-H8 | candidati solo da celle visibili; filtro finale `HiddenMask` prima di pubblicare e prima di mostrare |
| E-MEM13 | i viaggi scartati non arrivano dalla porta |

### 2.2 Scelta delle foto (D5–D8; P7)
**I passi di `PhotoPicker.pick`**
| Passo | Cosa | Dettagli |
|---|---|---|
| Esclusione | screenshot e registrazioni dello schermo, documenti, immagini animate, ritagli, illeggibili, date stimate (per i tipi legati a una data), persone e periodi "Mai", filtro di Q6 | conteggi per motivo nel resoconto, mai gli id |
| Punteggio | `PhotoScore` (D6): segnali dell'utente, luce dalla `ThumbHash`, nitidezza dalla miniatura | i pesi stanno in `PickWeights` (una tabella, provvisoria) |
| Doppioni | coppie con coseno ≥ 0,92 (vettori) o firma visiva "simile" (senza vettori) o, in mancanza di entrambi, entro 3 minuti: resta la migliore | le raffiche e le copie simili sono già una cella (F5-D36) |
| Varietà | massima rilevanza marginale, λ = 0,5; tetti per giorno e per ora (D7) | deterministica: a parità, l'ora di scatto più vecchia |
| Ordine | per ora di scatto (la presentazione racconta il giorno o il viaggio) | il mese: per giorno, poi per ora |
| Copertina | la scelta col punteggio più alto fra le foto (non video) **non** di WhatsApp, senza persone con "Mostra meno", orizzontale o quadrata se possibile | mai un nascosto; si ricalcola solo se quella sparisce (D9) |
| Minimo | sotto la soglia del tipo (D7) il ricordo non nasce | E-MEM1 |

**Il filtro di Q6 (b)** (solo se l'utente lo vuole; P7): sei frasi prototipo nella lingua dell'app, vettori dal modello delle query (270M,
già su ogni dispositivo), coseno di ogni candidato con ogni frase, **soglia relativa** alla Libreria (z = (coseno − mediana) / (1,4826 · MAD), come
`ContentScorer`), sopra `ZS = 3,5` la foto esce. Si calcola al momento, in memoria, per i soli candidati; l'esito non si salva da nessuna parte
(D8). Calibrazione: in P11 sul set sintetico (vettori finti con "concetti" seminati) e in F125 sui dati veri (quante foto toglie, quante
avrebbe dovuto togliere); se toglie più del 3% dei candidati di un ricordo medio, la soglia sale.

**Senza capire il contenuto, che cosa protegge davvero** (D8): le scelte dell'utente valgono subito e ovunque; i periodi "Mai" bloccano
anche i viaggi che li toccano; le persone nascoste, dimenticate o "Mai" non entrano; i ricordi scartati non tornano; i giorni pieni di
documenti non diventano ricordi; nessuna notifica per una persona con "Mostra meno". Il resto è un limite dichiarato (L233).

| Edge case | Comportamento |
|---|---|
| E-MEM2, E-MEM11, E-MEM12, E-MEM16 | §0.2 |
| Gruppo con il membro migliore nascosto | il gruppo è nascosto per intero (F5-D36, C27): non è un candidato |

### 2.3 Stabilità, sincronizzazione, "Mostra meno" (D9–D11; P6, P8)
**Lo scaffale** (`noBackupFilesDir/memories/shelf.bin`)
| Aspetto | Comportamento |
|---|---|
| Contenuto | per ricordo: id, tipo, chiave, titolo come dati, elementi (≤ 40 `MediaId`), copertina, giorno di pubblicazione, origine (album del viaggio) |
| Scrittura | file temporaneo + `fsync` + rinomina, con versione e CRC (come `StateFiles`); un file rovinato vale "scaffale vuoto" e si rigenera, `MEMORY_SHELF_RESET` in Diagnostica |
| Cosa cambia dopo la pubblicazione | solo togliere: elementi nascosti, eliminati, esclusi da un "Mai"; mai aggiungere, mai riordinare, mai cambiare titolo |
| Quanto resta | al più 60 ricordi e 30 giorni; i ricordi visti negli ultimi 12 mesi non si rigenerano anche se escono dallo scaffale |
| Fra dispositivi | non viaggia (D10): ogni dispositivo ha il suo; gli id coincidono, quindi "visto" e "scartato" valgono ovunque |

**Le voci che viaggiano** (dati personali, area privata, giro esistente)
| Voce | Tipo, soggetto, campo | Valore | Chi la scrive |
|---|---|---|---|
| Visto | `memory`, `<MemoryId>`, `seen` | giorno locale `aaaammgg` | apertura della pagina o della presentazione (non la sola vista in Raccolte) |
| Non mostrare | `memory`, `<MemoryId>`, `dismissed` | vero | menu del ricordo, con "Annulla" per 10 s (lapide) |
| Mostra meno / Mai (persona) | `setting`, `settings`, `memories_less:person:<id>` | `less` o `never` (lapide = tolto) | foglio dalla pagina del ricordo o Impostazioni → Ricordi |
| Mai (periodo) | `setting`, `settings`, `memories_never:days:<inizio>-<fine>` | vero | foglio "Non ricordarmi questo periodo" (scelta dei giorni, al più 366) |
| Ricordi con le persone | `setting`, `settings`, `memories_people` | vero/falso (Q9) | Impostazioni → Ricordi |

**"Mostra meno" e "Mai nei ricordi"**: dalla pagina di un ricordo `PERSON` ("Mostra meno <nome>"), dal menu di un ricordo qualunque ("Non
ricordarmi questo periodo") e dalle Impostazioni (elenco delle scelte, con le persone per nome se Persone è attiva, altrimenti "Una persona"
e il numero; "Togli" per ognuna). L'effetto è **immediato**: lo scaffale si ripulisce subito (D9) e la notifica si rivaluta (D19).

**Persone spente o cancellate** (D11): `PeopleLessJanitor` osserva `MemoryPeople.available` e lo stato dei volti dalla porta; con `Off` o
`Erasing` scrive le lapidi di tutte le voci `memories_less:person:*` e toglie dallo scaffale i ricordi `PERSON`; con una persona unita le voci
seguono l'alias alla prossima scrittura (`resolve`).

| Edge case | Comportamento |
|---|---|
| E-MEM5–E-MEM10, E-MEM22 | §0.2 |
| Due dispositivi scartano e riattivano lo stesso ricordo quasi insieme | vince l'ultima scrittura per ora del NAS (`PersonalMerge`), uguale ovunque |
| App della Fase 8 sullo stesso account | conserva i campi nuovi senza usarli (nessun tipo nuovo) |

### 2.4 Pagina del ricordo e presentazione (D13; P9)
**La pagina**: titolo grande ("Un anno fa", "Oslo, marzo 2025", "Con Anna"), sottotitolo (data o periodo, luoghi), "Riproduci" in evidenza, la
griglia degli elementi (stessa cella e stesso viewer della Libreria, `IndexOrderedTimelineView` sull'istantanea visibile: un nascosto esce
subito, il viewer si chiude da solo se la vista si svuota, E-V6), il menu (Mostra meno…, Non ricordarmi questo periodo, Non mostrare questo
ricordo, "Apri l'album" per un viaggio diventato album). Aprire la pagina segna "visto" (D10).

**La presentazione** (`MemoryPlayer`, schermo intero, scuro, `FLAG_SECURE` no: non è un'area protetta)
| Momento | Comportamento | Dettagli |
|---|---|---|
| Inizio | titolo sopra la copertina per 2 s, poi la prima foto | con TalkBack il titolo si legge e non parte niente da solo |
| Una foto | 4 s; movimento lento (zoom dal 100% al 108% e spostamento lento verso il centro dell'immagine; un centro d'interesse migliore è del filone grafico) con le curve di `GalleryMotion` | solo `graphicsLayer` (trasformazioni della GPU), nessuna nuova misura del layout a ogni fotogramma |
| Passaggio | dissolvenza di 600 ms (token di `FluidMotion`) | con le animazioni ridotte: niente movimento, dissolvenza breve |
| Precaricamento | le due immagini successive decodificate prima (anteprima HQ locale se c'è, poi miniatura grande, poi `ThumbHash`); mai un'attesa sul NAS: se manca, si usa ciò che c'è | E-MEM14, E-MEM15; memoria: al più 3 bitmap a schermo intero (~60 MB [stima] sul tablet) |
| Video | fotogramma di copertina con l'icona "video"; con `MemoryClips` (solo video del telefono) un clip muto di al più 8 s dal punto più "vivo" (il centro) | i video del solo NAS si guardano dal viewer (E-MEM17) |
| Gesti | tocco = pausa e comandi; scorrimento = avanti e indietro; trascinamento in basso = esci | come il viewer (stesse soglie di `GalleryMotion`) |
| Fine | carta finale con le miniature e "Rivedi" | nessuna condivisione o esportazione del ricordo in questa fase (idea per dopo, §3) |
| Musica | secondo **Q3**: nessuna (predefinita finché l'utente non sceglie); con Q3 (b) un file audio scelto dall'utente con il selettore di sistema (`ACTION_OPEN_DOCUMENT`, `audio/*`, permesso di lettura persistito, nessun permesso nuovo), suonato con Media3 a volume moderato, si ferma con la presentazione e con le chiamate (focus audio) | il file resta dove l'utente lo tiene; se sparisce, presentazione muta e un avviso |
| Rotazione, finestre | lo stato (indice, pausa) si conserva | tablet: stessa presentazione, immagini più grandi |

**Accessibilità**: ogni foto ha la descrizione ("Foto del 12 marzo 2025, Oslo"; con Persone attiva "con Anna" solo dentro l'app); con TalkBack
l'avanzamento automatico è spento e i gesti di TalkBack scorrono le foto; bersagli da 48 dp; testo a 1,3x.

| Edge case | Comportamento |
|---|---|
| E-MEM14, E-MEM15, E-MEM17, E-MEM19 | §0.2 |
| Un elemento diventa nascosto durante la presentazione | la presentazione lo salta al prossimo passaggio e non lo mostra più; quello sullo schermo esce subito con una dissolvenza (filtro finale) |
| Ricordo rimasto vuoto | "Questo ricordo non c'è più" e ritorno alla pagina Ricordi |

### 2.5 Raccolte: Ricordi e Luoghi (D14, D30; P9, P4)
- **Ricordi** (fornitore `memories`, nel posto riservato): fino a 6 voci, prima i non visti (con il segno "nuovo"), poi i più recenti; "Tutti
  i ricordi" (lo scaffale per mese); da vuota "I ricordi arrivano quando ci sono abbastanza foto" (E-MEM1); in generazione "Sto preparando i
  ricordi" solo al primo giro. Un tocco apre la pagina del ricordo.
- **Luoghi** (fornitore `places`, sezione nuova dopo Ricordi; in coda se l'ordine è già salvato, E-L14): la riga "Mappa" (con il numero di
  foto con posizione), i luoghi più fotografati (fino a 8: nome del luogo e numero, da `placeAt` e `PlaceNames`), "N foto senza posizione".
  Un luogo apre la griglia del luogo (stessa griglia della zona, §2.10).
- Entrambe sono **nascondibili** e ordinabili come le altre (P11 della Fase 7); nessuna modifica a `CollectionsTab` oltre al codice `places`.
- Tablet: dettaglio a destra oltre 840 dp come le altre raccolte; la mappa apre a schermo pieno.

### 2.6 Notifiche dei ricordi (9.2; D15–D20; P10)
**Il giorno di una notifica**
| Passo | Cosa | Dettagli |
|---|---|---|
| Pianificazione | `MemoryNotifyPolicy.nextSlot(now, prefs)` → `OneTimeWork` unico `memories-notify` con il ritardo (REPLACE) | a ogni corsa, a ogni cambio di impostazioni e a ogni avvio dell'app; nessun ricevitore nuovo per i cambi di fuso: il ritardo si ricalcola alla corsa dopo (E-NT4); WorkManager conserva il lavoro dopo un riavvio |
| Partenza | il lavoro parte nella finestra; se `now` è oltre H+3 h, si ripianifica per domani senza notificare | E-NT3 |
| Condizioni | notifiche accese qui, permesso concesso e canale acceso (`canNotify()` e canale), frequenza di Q4 rispettata (registro), nessuna notifica già oggi, i Ricordi non aperti oggi | altrimenti nulla e un codice in Diagnostica |
| Passo leggero | `Memories.refresh(LIGHT, 5 s)` se la notte non ha generato | D4 |
| Scelta | `choose`: un ricordo pubblicato oggi, non visto, non scartato, ≥ 8 elementi, mai `PERSON` con "Mostra meno"; priorità di D15; con Q4 (c) solo anniversari di viaggi ed eventi | E-NT6: nessuno = nessuna notifica |
| Ricontrollo | `HiddenMask` sugli elementi e sulla copertina; scaffale riletto | D19 |
| Contenuto | `content(memory, mode)`: generica ("Un ricordo per te" · "Tocca per vederlo") o "Titolo e foto" (titolo senza nomi, miniatura locale della copertina, versione pubblica generica) | D16; la miniatura non si scarica mai per una notifica |
| Pubblicazione | canale `memories`, id 1009, `setVisibility(PRIVATE)`, `setPublicVersion(generica)`, `PendingIntent` immutabile con la sola azione `OPEN_MEMORIES`, `setAutoCancel(true)` | registro del giorno scritto **prima** di pubblicare (E-NT5) |
| Revoca | `MemoryNotifier.revalidate()` a ogni cambio di nascosti e di scelte: toglie la notifica se il ricordo non è più mostrabile o la copertina (in "Titolo e foto") è nascosta | E-NT7 |
| Tocco | `MainActivity` vede `OPEN_MEMORIES`, pulisce l'intent, consegna l'evento; dopo il blocco app si apre la pagina del ricordo scelto oggi (dallo scaffale) o, se non c'è più, la pagina Ricordi | D17, E-NT8, E-NT9 |

**La frequenza** (Q4): `DAILY` = al più una al giorno; `FEW_PER_WEEK` (raccomandata) = al più 3 a settimana, mai due giorni di fila, nei giorni
col ricordo migliore (anniversari prima); `ANNIVERSARIES` = solo anniversari di viaggi ed eventi; `OFF`. Ora predefinita **19:00**, scelta dalla
pagina con un selettore dell'ora (a passi di 15 minuti).

**La pagina Impostazioni → Ricordi e notifiche**: "Ricordi con le persone" (Q9, solo con Persone attiva), "Notifiche dei ricordi" (interruttore,
ora, frequenza), "Sulla schermata di blocco" (Solo «Hai un ricordo» · Titolo e foto), lo stato del permesso e del canale con "Consenti" o
"Apri le impostazioni di Android" (D18), "Persone e periodi nascosti dai ricordi" (elenco con "Togli"), il filtro di Q6 se scelto.

**Il testo** (in risorse, italiano): canale "Ricordi" con la descrizione "Un ricordo al giorno al massimo, all'ora che scegli"; notifica generica
"Un ricordo per te" / "Tocca per vederlo"; con titolo: "Un anno fa · Oslo" / "Tocca per rivederlo".

| Edge case | Comportamento |
|---|---|
| E-NT1–E-NT15 | §0.2 |
| E-O4 | permesso negato all'onboarding: niente notifiche, la pagina lo spiega |
| E-P13 (riavvio notturno, memoria bloccata) | il lavoro riparte dopo il primo sblocco; se è oltre la finestra, quel giorno si salta |
| E-P15 (app usata poco, sospensione profonda di Samsung) | i lavori non partono: nessuna notifica, e va bene così (nessun tentativo di aggirarla) |

### 2.7 Mappa: libreria, stile, carta di base (D21, D22, D25; P1, P2)
**Che cosa serve davvero** (nulla è stato scaricato per questo documento; versioni e dimensioni da fissare in P1 e P2, elenco da approvare al §8)
| Pezzo | Scelta | Licenza | Dove sta | Peso [stima] |
|---|---|---|---|---|
| Motore di disegno | **MapLibre Native Android** (`org.maplibre.gl:android-sdk`), ultima stabile che passa i controlli di P1 | BSD-2-Clause | dipendenza di `feature-map` (Maven Central) | APK +5–9 MB per l'ABI `arm64-v8a` (libreria nativa compressa) |
| Rete di MapLibre | OkHttp 4.x e Okio 3.x (transitivi di MapLibre), più le librerie di supporto di MapLibre (geojson, gesti) e i loro transitivi | Apache-2.0, BSD, MIT [elenco esatto in P1] | idem | ~1–2 MB |
| Stile | due stili JSON (chiaro da **Positron**, scuro da **Dark**/**Fiord** di OpenFreeMap), schema OpenMapTiles, ridotti a ciò che serve (niente edifici 3D, niente POI commerciali), con il colore Ametista per acqua e confini sottili | da verificare: codice BSD-3 (OpenMapTiles), disegno CC-BY 4.0 | `feature-map/src/main/assets/map/style-*.json` | < 100 KB |
| Glifi | **Noto Sans** Regular e Bold come PBF di MapLibre, solo gli intervalli 0–255, 256–511, 768–1023 (latino, latino esteso, greco) e 8192–8447 (punteggiatura) | SIL OFL 1.1 | `assets/map/glyphs/` | ~0,5–1 MB |
| Sprite | icone essenziali (frecce delle strade, porti, aeroporti) da quelle degli stili OpenFreeMap, 1x e 2x | come lo stile | `assets/map/sprites/` | ~100–300 KB |
| Carta di base | **Natural Earth** 1:50m (terre, coste, laghi, confini dei paesi) e 1:10m (confini delle regioni italiane), semplificata; città ≥ 50.000 abitanti dal GeoNames già usato per i luoghi | pubblico dominio; CC BY 4.0 per le città | `assets/map/base/*.geojson` | 2–4 MB (circa 1–2 MB compressi nell'APK) |
| Strumenti | `tools/maps/base/` (Python standard o Kotlin: filtra, semplifica con Douglas-Peucker a tolleranza fissata, arrotonda a 5 decimali, scrive i GeoJSON e le impronte) e `tools/maps/style/` (adatta gli stili, controlla che ogni `text-font` abbia i glifi, che non ci siano URL fuori da `asset://` e dall'URL stabile delle tessere) | nostri | `tools/` | — |
| Attribuzione | "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap" sulla mappa online; "Natural Earth · GeoNames" sulla carta di base; testo completo in Impostazioni → Informazioni e licenze | obbligo di ODbL e CC BY | `PlacesAttribution` esteso | — |

**Da verificare in P1, prima di scegliere la versione** (spike in `spike/maplibre/`, fuori da `settings.gradle`, poi tolto o lasciato come le
altre prove): (1) librerie native allineate a 16 KB (`check.ps1`, come per LiteRT); (2) manifest fuso senza componenti esportati né permessi
fuori da `allowedPermissions`; (3) **ogni** richiesta passa da `HttpRequestUtil` e dal nostro client (prova: client che rifiuta tutto, cattura
del traffico a mappa aperta: zero connessioni); (4) nessuna telemetria né richiesta all'avvio (`MapLibre.getInstance` senza chiave); (5)
`asset://` per stile, glifi, sprite e GeoJSON; (6) `OfflineManager` con un percorso del database in `noBackupFilesDir` (`FileSource.setResourcesCachePath`),
il tetto delle tessere offline e la ripresa; (7) `MapLibre.setConnected(false)` e il precaricamento spento (`prefetchZoomDelta = 0`); (8) il
`User-Agent` che MapLibre mette e se l'intercettore lo può riscrivere; (9) peso dell'APK prima e dopo; (10) tempi di apertura a freddo e
memoria sull'S25. Se uno di (1)–(4) fallisce, piano B al §7.

**Il server** (da verificare in P1 sulla documentazione pubblica di OpenFreeMap, senza account): host `tiles.openfreemap.org`; TileJSON in
`/planet` con l'URL versionato delle tessere (`/planet/<versione>/{z}/{x}/{y}.pbf`, z0–z14, vettoriali compresse); stili, caratteri e sprite
anche lì (noi non li chiediamo); condizioni d'uso, uso offline consentito o no, politica dei log, chi ospita il servizio (CDN o server propri),
ogni quanto ruotano le versioni e quanto restano disponibili le vecchie.

### 2.8 Mappa: che cosa esce dal telefono e il cancello (D23, D24; P1)
**A mappa online spenta** (predefinito): **niente**. Né tessere, né TileJSON, né caratteri, né DNS per l'host della mappa: il client rifiuta
tutto prima di aprire un socket, MapLibre è "offline" (`setConnected(false)`), la mini-mappa usa uno stile senza la sorgente online, le regioni
non si scaricano. Si vede la carta di base, le regioni già scaricate e la cache.

**A mappa online accesa**, per ogni richiesta che il cancello lascia passare:
| Che cosa | Chi lo vede | Che cosa rivela |
|---|---|---|
| L'**indirizzo IP** pubblico (quello di casa sul Wi-Fi o via WireGuard; quello dell'operatore sui dati mobili) | OpenFreeMap e chi ospita il servizio | da dove si guarda (casa, all'incirca la città) |
| Il **nome dell'host** (`tiles.openfreemap.org`) nella richiesta DNS e nel TLS (SNI) | il DNS della rete in uso (FRITZ!Box, operatore) e chi osserva la rete | che si usa una mappa, non dove si guarda |
| Il **percorso** `/planet/<versione>/<z>/<x>/<y>.pbf` | OpenFreeMap | **la zona guardata**: a z14 un quadrato di circa 1,7 km di lato alle nostre latitudini; ai livelli bassi un paese o una regione |
| L'**ora** e il ritmo delle richieste | OpenFreeMap | quando si usa la mappa e quanto |
| Gli **header** | OpenFreeMap | solo un `User-Agent` fisso e generico (senza nome dell'app, versione o dispositivo; il valore esatto lo sceglie P1 leggendo le condizioni d'uso del servizio), `Accept-Encoding: gzip`, eventuali `If-None-Match`/`If-Modified-Since` per le tessere in cache; **mai** cookie, `Referer`, identificativi, chiavi |
| Il **TileJSON** (`/planet`) | OpenFreeMap | solo che l'app è aperta (al più una volta al giorno, poi in cache) |

**Che cosa non esce mai**: foto, miniature, coordinate delle foto, numero o posizione dei punti, nomi di luoghi, la casa, l'account, l'id del
dispositivo. Le bolle si calcolano sul telefono; le tessere arrivano dal server e basta.

**Il cancello** (`TilePolicy.decide`, funzione pura in `core-map`, chiamata dall'intercettore di `MapTileHttp` per **ogni** richiesta e per ogni
redirect)
| Controllo | Esito se fallisce |
|---|---|
| `FeatureFlags.mapOnline` acceso e `MapOnline.On` su questo dispositivo | `Refuse(OFF)` |
| rete in uso: Wi-Fi (o dati mobili se "Solo sul Wi-Fi" è spento), mai roaming, rete validata | `Refuse(NETWORK)` |
| metodo `GET`, schema `https`, host esattamente `tiles.openfreemap.org`, porta 443, nessun utente, nessun frammento | `Refuse(HOST)` |
| percorso: il TileJSON `/planet` oppure una tessera dell'URL stabile `/planet/v/{z}/{x}/{y}.pbf` con `z` ≤ 14 e `x`, `y` dentro i limiti di `z` | `Refuse(PATH)` |
| tessera coperta da una regione offline completa (`MapRegions.covers`) | `Refuse(COVERED)`: MapLibre usa quella del database |
| versione corrente del server nota (dal TileJSON in cache) | riscrive `/planet/v/…` in `/planet/<versione>/…`; senza versione, prima il TileJSON |
| redirect | seguito a mano solo se resta sullo stesso host con le stesse regole; altrimenti `Refuse(REDIRECT)` (E-MAP4) |
| tipo della risposta | solo `application/x-protobuf` o `application/vnd.mapbox-vector-tile` per le tessere e JSON per il TileJSON, dimensione ≤ 2 MiB; altrimenti scartata |

L'intercettore toglie cookie, `Referer` e ogni header non elencato, mette il `User-Agent` fisso, conta richieste, byte e rifiuti per motivo
(solo numeri, Diagnostica), registra i byte nel registro del traffico (`MapTraffic`, contenuto visibile: proposta 3, tetti mensili rispettati).
Un errore del server non fa ripartire richieste a raffica (attesa crescente, E-MAP3).

**La pagina sulla privacy** (`MapOnlineConsent`, versionata), in frasi brevi:
- "La mappa dettagliata arriva da OpenFreeMap, un servizio gratuito che usa i dati di OpenStreetMap. Non serve un account."
- "Quando la usi, OpenFreeMap riceve l'indirizzo della tua connessione e **quale zona stai guardando**. Non riceve le tue foto né la loro
  posizione."
- "Per non far sapere a nessuno una zona, **scaricala**: le zone scaricate non si chiedono più a OpenFreeMap."
- "La scelta vale **solo su questo dispositivo**. Puoi spegnere la mappa dettagliata quando vuoi: resta la carta semplice dentro l'app."
- "Solo sul Wi-Fi" (acceso) e, se spento, l'avviso sul consumo di dati.

| Edge case | Comportamento |
|---|---|
| E-MAP1–E-MAP4, E-MAP13, E-MAP14, E-MAP19 | §0.2 |
| E-R3, E-R13 (fuori casa, Wi-Fi a consumo o con pagina d'accesso) | Wi-Fi a consumo trattato come dati mobili; rete non validata = nessuna richiesta |
| E-P10 (risparmio energetico) | nessun effetto sulla mappa aperta; nessun lavoro in background per la mappa |

### 2.9 Mappa: regioni offline (D26; P5)
| Momento | Cosa | Dettagli |
|---|---|---|
| Catalogo | Impostazioni → Mappa → "Zone senza Internet": le 20 regioni italiane, i paesi confinanti (Francia, Svizzera, Austria, Slovenia, San Marino, Vaticano) e "La zona attorno a un luogo" (ricerca del luogo per nome nel dataset GeoNames, raggio 15, 30 o 60 km) | riquadri fissi nel codice (regioni e paesi, da Natural Earth, arrotondati a 0,1°); mai una zona proposta dalla casa o dai punti delle foto |
| Stima | tessere da z0 a z14 nel riquadro, peso da una tabella di densità per tipo di zona (città, campagna, mare) calibrata in P5 su tre regioni di prova | "Toscana: circa 250 MB [stima]"; "Zona di 30 km attorno a Siena: circa 25 MB [stima]" |
| Foglio | "Scarica «Toscana»? Circa 250 MB, sul Wi-Fi. OpenFreeMap vedrà quale zona scarichi, una volta." `RegionDownloadConfirmation` (scade dopo 10 minuti, come le altre conferme) | solo da un tocco; spazio libero controllato (dimensione + 1 GiB, E-MAP12) |
| Scaricamento | `OfflineManager` di MapLibre, attraverso lo stesso client e lo stesso cancello (con la conferma la politica ammette le tessere della regione anche a mappa online spenta, solo per quell'operazione); avanzamento in Impostazioni e nella mappa | solo sul Wi-Fi (sui dati mobili con una seconda conferma e la dimensione); fermato da roaming o da "Solo sul Wi-Fi" |
| Interruzione | processo terminato, Wi-Fi caduto: la regione resta "parziale" e riprende alla prossima apertura dell'app (E-MAP11) | nessun lavoro in background: niente componenti nuovi |
| Uso | le tessere della regione non si chiedono più online (`Refuse(COVERED)`), anche a mappa online accesa | la zona scaricata non si rivela più guardandola |
| Aggiornamento | solo a mano ("Aggiorna"); dopo 6 mesi la pagina lo propone | un aggiornamento automatico rivelerebbe la zona a ogni giro |
| Cancellazione | "Elimina" per regione; "Svuota la cache della mappa" per le tessere viste online | azzeramento: tutto (D37) |
| Tetti | 1 GB per regione, 3 GB in tutto, 1 GiB libero sempre | oltre: "Scegli una zona più piccola" |

Con **Q2 (b)** (regioni preparate sul PC e messe sul NAS): uno strumento in `tools/maps/regions/` scarica dal PC le tessere delle regioni scelte
con lo stesso cancello (riusato come libreria JVM) e produce un database offline di MapLibre; il file va in una cartella dell'area privata (o
comune, se l'utente lo vuole per la famiglia) e i dispositivi lo uniscono con `mergeOfflineRegions` leggendolo via SMB. I telefoni allora non
parlano mai con OpenFreeMap per le regioni. Più lavoro (pacchetto in più, §4.4 nota) e una cartella nuova sul NAS: per questo è un'opzione.

| Edge case | Comportamento |
|---|---|
| E-MAP11, E-MAP12, E-MAP20 | §0.2 |
| Regione scaricata e poi server in versione nuova | l'URL stabile la tiene valida; "Aggiorna" a scelta (E-MAP13) |

### 2.10 Mappa: punti, raggruppamento e griglia con 100 mila elementi (D27, D28; P3, P4)
**L'indice** (`core-map/.../points/`, JVM)
| Passo | Cosa | Dettagli |
|---|---|---|
| Lettura | `MapPointSource.pages()` → `MapPointsQueries.page(after, 2.000)` sulle celle visibili con coordinate effettive | 100 mila righe in ~50 pagine; < 400 ms sulla JVM, < 1,5 s sull'S25 [stima], fuori dal thread principale |
| Validità | fuori dai limiti, (0, 0) esatto, latitudine oltre ±85° → scartati e contati (E-MAP7) | — |
| Chiave | proiezione di Mercatore in un intero a 24 bit per lato → chiave di Morton a 48 bit; array ordinati (chiave, posizione nell'istantanea, punteggio, giorno) | ~24 byte a punto: ~2,4 MB per 100 mila |
| Livelli | per ogni mezzo livello di zoom, celle = prefissi della chiave; per ogni livello le celle non vuote con conteggio e copertina (il punteggio di D6 più alto, a parità il più recente) | costruiti pigri e tenuti in una cache LRU di 8 livelli |
| Vista | `clusters(view, zoom, cellPx = 72 dp)`: le celle del livello che toccano la vista (centinaia), con ricerche binarie | < 4 ms p95 sulla JVM per 100 mila punti, < 8 ms sull'S25 [stima] |
| Aggiornamento | nuova istantanea (`seq`) → ricostruzione dopo 2 s di calma, poi scambio atomico dell'indice | E-MAP15; un nascosto esce anche prima dalla griglia viva |

**La schermata** (`feature-map`)
| Momento | Comportamento | Dettagli |
|---|---|---|
| Apertura | `MapView` (MapLibre, `SurfaceView`) con lo stile della modalità (chiaro o scuro) e la camera di D29; l'indice si costruisce mentre la mappa compare | primo fotogramma della mappa ≤ 500 ms p90; bolle ≤ 800 ms a freddo, ≤ 300 ms a caldo (§5.4) |
| Bolle | al più 60 bolle visibili come composable sopra la mappa; posizione ricalcolata a ogni fotogramma con `projection.toScreenLocation` (solo spostamenti, nessuna nuova misura); celle ricalcolate a camera ferma o al cambio di mezzo livello | miniature da `core-imaging` (replica locale, mai il NAS durante il gesto); con la qualità Risparmio il segnaposto `ThumbHash` |
| Etichette | per le celle grandi il nome del luogo più frequente (`PlaceNames.labelOfId`), mai "Casa" | E-MAP18 |
| Tocco su una bolla | la camera si avvicina sulla cella; da 30 elementi in giù, o al livello massimo, si apre la **griglia della zona** | animazione di camera di MapLibre, durata dai token |
| Griglia della zona | `IndexOrderedTimelineView` degli elementi dal più recente, viva sull'istantanea visibile; un tocco apre il viewer (anche solo-NAS) | viewer come dalla Libreria (≤ 100 ms dalla miniatura locale) |
| Senza punti | "Nessuna foto con la posizione" e quante sono senza posizione | E-MAP5 |
| Avvisi | mappa online spenta: una riga discreta "Carta semplice · Mappa dettagliata" (apre la pagina sulla privacy); dati mobili con "Solo sul Wi-Fi": "Mappa dettagliata solo sul Wi-Fi" | mai un dialogo all'apertura |
| Uscita | l'ultima camera si salva **solo in locale** (DataStore, azzerata con il resto) | D29 |

**Mini-mappa del viewer** (`ViewerPlaceMap.miniMap`): un'immagine statica (`MapSnapshotter`) di 320×160 dp con lo stile **solo offline** (carta di
base e, se coprono il punto, le regioni scaricate: la sorgente online non c'è nello stile) e un segno sul punto; un tocco = "Mostra sulla mappa"
(la mappa vera, con le sue regole). Mai per un media aperto dai Nascosti.

| Edge case | Comportamento |
|---|---|
| E-MAP5–E-MAP10, E-MAP15–E-MAP18 | §0.2 |
| E-L7 (100 mila elementi) | guardie del §5.4 |
| E-V6 (media eliminato mentre lo guardi dalla griglia) | il viewer si chiude gentile, la griglia lo toglie |

### 2.11 Nascosti su ogni superficie nuova (D31; P6, P9, P10, P4)
Le superfici si registrano in `HiddenSurfaces` (`app/src/test/.../hidden/`) con tre righe nuove del §2.1 di quel registro e le loro sonde sul
mondo casuale (nascosti, nascosti a metà in un gruppo, nascosti dell'ultimo momento, persone nascoste):
| Superficie | Riga | Fonte | Prova |
|---|---|---|---|
| `memories-section`: Raccolte → Ricordi, conteggi, segni "nuovo", copertine | `MEMORIES` | scaffale ripulito con `visibleOrThrow`, filtro finale `HiddenMask` | proprietà |
| `memories-page` e `memories-all`: pagina, "Tutti i ricordi", viewer della pagina | `MEMORIES` | `IndexOrderedTimelineView` sull'istantanea visibile | proprietà, come `album-viewer` |
| `memories-player`: presentazione, precaricamento, carta finale | `MEMORIES` | la stessa vista; filtro finale prima di ogni passaggio | proprietà, F113 |
| `memories-people`: ricordi con le persone | `MEMORIES` | `MemoryPeople` sopra `PeopleVisibility` (persone nascoste e dimenticate escluse) | proprietà, E-H10 |
| `memories-notify`: scelta, contenuto, miniatura, revoca | `NOTIFICATIONS` | scaffale + `HiddenMask` al momento di pubblicare; `revalidate()` a ogni cambio | proprietà, F116 |
| `map-points`: punti, conteggi, bolle, copertine, etichette | `MAP` | `MapPointsQueries` sulle sole celle visibili; filtro finale sulle copertine | proprietà, E-H9 |
| `map-zone`: griglia della zona e del luogo, viewer | `MAP` | vista viva sull'istantanea visibile | proprietà |
| `places-section`: Raccolte → Luoghi | `MAP` | `placeAt` dell'istantanea visibile | proprietà |
| `viewer-minimap`: riga "Mostra sulla mappa" e mini-mappa | `MAP` | mai per un media aperto dai Nascosti (`ViewerRequest.secure`) | test della riga |

Il registro fallisce da solo se un file nuovo che nomina `VisibleMedia` o `HiddenMask` non è elencato (regola di P9 della Fase 7).

### 2.12 Diagnostica e codici (P13)
| Codice | Quando | Messaggio e azione |
|---|---|---|
| `MEMORY_STATE` | voce di stato di `DiagSection.MEMORIES`: ricordi sullo scaffale, nuovi, ultimo giro (giorni fa), durata | — |
| `MEMORY_SHELF_RESET` | scaffale rovinato e rigenerato | — |
| `MEMORY_NO_VECTORS` | doppioni senza vettori (ripiego) | — |
| `MEMORY_DROPPED` | esclusi per motivo (screenshot, documenti, data stimata, "Mai", filtro di Q6): solo conteggi | — |
| `NOTIFY_SKIPPED` | notifica non mandata, per motivo (permesso, canale, frequenza, nessun ricordo, fuori finestra, già aperti) | "Apri le impostazioni" per permesso e canale |
| `NOTIFY_LATE` | consegne oltre H+1 h negli ultimi 14 giorni (conteggio) | — |
| `NOTIFY_REVOKED` | notifiche tolte per un nascosto o uno scarto | — |
| `MAP_STATE` | voce di stato di `DiagSection.MAP`: mappa online sì/no, regioni (numero e byte), punti, senza posizione | — |
| `MAP_REQUESTS` | richieste lasciate passare e byte (oggi, 30 giorni) | — |
| `MAP_REFUSED` | richieste rifiutate per motivo (`OFF`, `NETWORK`, `HOST`, `PATH`, `COVERED`, `REDIRECT`) | un `HOST` o `PATH` diverso da zero è un difetto: "Segnala" |
| `MAP_REGION_PARTIAL`, `MAP_REGION_SPACE` | regione interrotta; spazio insufficiente | "Riprendi"; "Libera spazio" |
| `MAP_INVALID_POINTS` | coordinate scartate (E-MAP7) | — |

Mai titoli, luoghi, date, id, nomi, coordinate, riquadri, tessere, nomi delle regioni, ora scelta (D33). Le chiavi nuove entrano nell'elenco
chiuso dell'esportazione (`ExportKeys`).

### 2.13 Cosa serve dalle fasi precedenti e cosa la Fase 9 predispone
**Dalle fasi precedenti**
| Serve | Uso | Senza (oggi) |
|---|---|---|
| P13 della Fase 8 (`FacesGraph`, `PeopleIndex` legati in `:app`) | ricordi con le persone | `MemoryPeople` null: i ricordi `PERSON` tacciono; il resto funziona |
| F4-D31 spento sulle share vere (F64 della Fase 6) | "visto", "non mostrare" e "mostra meno" che viaggiano | valgono su ogni dispositivo, subito (E-MEM8 dichiarato) |
| Vettori della Fase 6 sulla replica | doppioni, varietà, filtro di Q6 | ripiego sulla firma visiva (E-MEM16) |
| Viaggi della Fase 7 (`CatalogTrips`, `TripReader`) | ricordi di viaggio | — (in `main`) |
| `VisibleMedia`, `HiddenMask`, `HiddenSurfaces`, `CollectionsSectionProvider` | §2.11, §2.5 | — |
| `core-places` e il dataset `places.mcgp1` | titoli, Luoghi, etichette delle bolle | — |

**Per la Fase 10**
| Pezzo | Come lo usano |
|---|---|
| Scaffale dei ricordi, `MemoryNotifyPolicy.choose` | 10.1: widget "Ricordi" sopra `WidgetFeed` (il ricordo di oggi, mai nascosti, mai persone con "Mostra meno") |
| Descrizioni della presentazione e delle bolle | 10.3: TalkBack completo |
| Mappa e griglia della zona | 10.2: due pannelli sul tablet; trascinamento dalla griglia verso altre app |
| Carta di base e mini-mappa | editor degli album smart: scegliere il luogo e il raggio sulla carta (idea per dopo) |

### 2.14 Stima dei costi e prestazioni attese
**Ipotesi**: ~100 mila elementi visibili come obiettivo di progetto (l'archivio vero dell'utente ha ~15.000 foto e ~2.600 video, Fase 8 §2.12); ~60% con la posizione;
~10 viaggi ed eventi all'anno; Persone attiva con ~40 persone con un nome.
| Voce | Valore [stima] | Note |
|---|---|---|
| Generazione dei ricordi | notte < 2 s; passo leggero < 300 ms; misure dei pixel ≤ 0,5 s per notte | S25; Tab S9 ~1,5 volte |
| Scaffale | ~60 ricordi × 40 id ≈ 40 KB | locale |
| Voci personali | ~1–3 voci `memory` al giorno (~600 all'anno, < 100 KB), poche voci `setting` | dentro i tetti dei dati personali |
| Notifica | un lavoro al giorno di < 1 s | trascurabile per la batteria (obiettivo < 2% al giorno del §4 del piano) |
| Indice della mappa | 100 mila punti: lettura < 1,5 s, ~2,4 MB in memoria | all'apertura della mappa e dopo i cambi |
| MapLibre | PSS +80–150 MB a mappa aperta; apertura a freddo 300–600 ms | P1 misura; rilasciata con `onTrimMemory` |
| APK | +5–9 MB (MapLibre arm64) + ~1–2 MB (OkHttp e altri) + ~3–4 MB (carta di base, stile, glifi, sprite) | P1 e P2 misurano prima e dopo |
| Traffico della mappa online | ~6–15 tessere per schermata nuova, 10–60 KB l'una → 0,1–0,6 MB per spostamento ampio; cache di 100 MB | solo con la mappa online accesa |
| Regioni offline | zona di 30 km: ~20–30 MB; una regione italiana media: 150–400 MB; tutta Italia (oltre il tetto): ~1,5 GB | stime da verificare in P5 con tre regioni di prova |

## 3. Alternative scartate

| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Dove si generano i ricordi | sul dispositivo; un dispositivo "custode" per account che pubblica i ricordi nell'area privata | **Su ogni dispositivo** (D4, D10): costano meno di un secondo, non serve il NAS, e un formato firmato nuovo sarebbe lavoro e superficie d'attacco per niente; gli id deterministici bastano a "visto" e "scartato" |
| Quando | attività nuova in `core-rules`; dentro `LibraryMaintenanceWorker` più un passo leggero | **Manutenzione notturna + passo leggero** (D4): un'attività nuova rompe sei `when` e le stime; il lavoro non usa rete né NAS |
| Dove si salvano i ricordi | tabelle nuove nel database della galleria (v7); un database a parte; un file | **Un file atomico** (D10): 40 KB, nessuna migrazione sul campo, si cancella e si rigenera |
| Qualità delle foto | un modello estetico nuovo (NIMA, LAION aesthetic); segnali dell'utente + pixel della miniatura | **Segnali + miniatura** (D6): un modello nuovo è un download, una licenza e un'altra inferenza; preferiti e modifiche dicono già cosa l'utente ama |
| Ricordi tristi | classificatore automatico sempre acceso; solo comandi dell'utente; comandi + filtro prudente a scelta | **Comandi sempre, filtro a scelta** (D8, Q6): un'inferenza su ospedali e funerali è delicata; se l'utente la vuole, toglie e basta e non lascia tracce |
| "Mostra meno" per una persona | nella classe `people` dei volti; nei dati personali | **Dati personali** (D11): un campo nuovo in `people` fa rifiutare il segmento alle app della Fase 8; la lapide alla cancellazione delle persone chiude il resto |
| Valore di `seen` | ora del NAS (come oggi nel codice); giorno locale | **Giorno locale** (D10): fuori casa l'ora del NAS non c'è; l'ora della modifica la porta già la voce |
| Copertine | ritaglio del soggetto (MagicTouch) o del volto; foto intere | **Foto intere** (D13): niente ritagli di volti salvati o mostrati fuori da Persone (C40), nessun lavoro della GPU per una copertina; il filone grafico può proporre il ritaglio dopo |
| Presentazione | modalità del viewer; componente proprio | **Componente proprio** (`MemoryPlayer`): il viewer ha zoom e gesti che in una presentazione disturbano; il proprio usa solo i token di movimento e le miniature |
| Musica | nessuna; brani liberi nell'APK; la musica dell'utente; servizi di streaming | **Q3**, raccomandata la musica dell'utente: nessun download, nessun diritto da gestire per noi, nessun servizio; i brani liberi pesano e vanno verificati uno per uno |
| Ora della notifica | allarme esatto (`SCHEDULE_EXACT_ALARM`); WorkManager con ritardo; `AlarmManager.setWindow` | **WorkManager con finestra** (D20), piano B `setWindow`: niente permessi nuovi; un ricordo non è una sveglia |
| Apertura dalla notifica | deep link con l'id del ricordo; activity "trampolino"; azione fissa senza dati | **Azione fissa** (D17): nessun dato dall'esterno, nessun componente nuovo, C17 rispettata (aprire una pagina non è un'azione) |
| Schermata di blocco | `VISIBILITY_PRIVATE` con versione pubblica; contenuto generico nella notifica stessa | **Generico di default** (D16): con "Mostra tutti i contenuti" del sistema la versione privata si vede comunque |
| Motore della mappa | MapLibre Native; osmdroid; Mapsforge; Google Maps; Mapbox; carta disegnata da noi | **MapLibre** (D21): vettoriale, aperto, senza account; osmdroid vuole tessere raster (OpenFreeMap non le ha, le tessere di OpenStreetMap.org non sono per le app); Mapsforge è solo offline, più lento e LGPL; Google e Mapbox chiedono account e mandano dati; disegnare da noi le tessere vettoriali è un progetto a sé |
| Wrapper Compose | `maplibre-compose` (sotto `org.maplibre`, versioni 0.x); `MapView` in `AndroidView` | **`AndroidView`**: una dipendenza in meno, API stabile; le bolle sono nostre comunque |
| Fornitore online | OpenFreeMap; tessere di OpenStreetMap.org; MapTiler/Stadia (account e chiave); Protomaps ospitato da noi; nessuno | **OpenFreeMap** (il piano, D22): senza account né chiave; le tessere di OpenStreetMap.org hanno una politica d'uso che esclude le app; un server nostro non c'è (il NAS non esegue codice) |
| Risorse dello stile | dal server (stile, caratteri, sprite); dentro l'APK | **Dentro l'APK** (D22): meno richieste, meno cose che il server vede, funziona offline |
| Rete di MapLibre | client predefinito; nostro client con l'intercettore; proxy locale | **Nostro client con politica pura** (D23): un posto solo, provabile sulla JVM; un proxy locale sarebbe un socket in ascolto in più |
| Mappa online | accesa per tutti; scelta per account (sincronizzata); scelta per dispositivo | **Per dispositivo, spenta di default** (D24, Q1): chi tiene in mano il telefono decide cosa esce da quel telefono; il tablet condiviso decide per ogni profilo |
| Carta offline | nessuna (sfondo vuoto); tessere vettoriali del mondo a bassi livelli nell'APK; GeoJSON di Natural Earth | **GeoJSON di Natural Earth** (D25): pochi MB, nessuna catena di generazione delle tessere; le tessere del mondo fino a z6 peserebbero decine di MB |
| Regioni offline | scaricate dal telefono; preparate sul PC e messe sul NAS; Italia intera nell'APK | **Dal telefono, a richiesta** (D26, Q2): semplice e senza cartelle nuove sul NAS; l'Italia intera fino a z14 pesa ~1,5 GB [stima]; il PC resta l'opzione Q2 (b) |
| Raggruppamento | GeoJSON con il raggruppamento di MapLibre; supercluster sulla JVM; chiavi di Morton a prefissi | **Morton a prefissi** (D28): costante a ogni livello, memoria piccola, nessuna serializzazione di 100 mila oggetti; supercluster costruisce un albero per livello |
| Bolle | simboli di MapLibre con le immagini; composable sopra la mappa | **Composable** (D28): miniature nostre, stile Fluid, al più 60 bolle; aggiungere centinaia di immagini allo stile di MapLibre a ogni spostamento costa |
| Coordinate | tabella o colonna derivata nuova con un indice (v7); query sulla v6 e indice in memoria | **Query e memoria** (D27): una lettura per apertura basta; nessuna migrazione sul campo |
| Camera iniziale | sulla zona con più foto; "tutte le foto" senza limite; "tutte le foto" con lo zoom fino a 5, poi l'ultima posizione locale | **Zoom fino a 5, poi l'ultima posizione** (D29): la zona con più foto è quasi sempre casa |
| Mini-mappa | con le tessere online; solo offline; niente | **Solo offline** (D29): aprire le info di una foto non deve dire a un terzo dov'è stata scattata |
| Dove si apre la mappa | scheda nuova in basso; sezione Luoghi delle Raccolte; solo dalle info | **Sezione Luoghi** (D30): la navigazione resta Libreria · Raccolte · Cerca (piano §2) |
| Condividere un ricordo | esportarlo come video o album; niente | **Niente in questa fase**: un video si fa con un codificatore e una scelta di musica e diritti; si può già fare "Crea album" dal viaggio |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
P0 fondamenta, contratti, recinti, documenti ──┬── P6 motore dei ricordi e scaffale ─────────────────────┐
                                                ├── P7 scelta delle foto ───────────────────────────────┤
                                                ├── P8 viaggi e persone nei ricordi (persone dopo P13 F8) ┤
                                                ├── P9 schermate dei ricordi e presentazione ───────────┼── M1 (integrazione minima)
                                                ├── P10 notifiche ──────────────────────────────────────┼── M2
                                                ├── P11 valutazione dei ricordi (dopo P6, P7) ──────────┤
                                                │                                                        │
                                                ├── P1 libreria delle mappe e cancello di rete (download) ┤
                                                ├── P2 carta di base, stile, glifi (download) ──────────┤
                                                ├── P3 punti e raggruppamento ──────────────────────────┤
                                                ├── P4 schermate della mappa (dopo P1, P2) ─────────────┤
                                                ├── P5 regioni offline (dopo P1, P3) ───────────────────┤
                                                ├── P12 benchmark e fluidita' (dopo P4, P9) ────────────┤
                                                └── P13 integrazione ───────────────────────────────────┴── M3
                                                     ── check.ps1 (-Bench) + security-review + verifica funzionale ── F125 (Q7)
```
- Dopo P0 i due filoni (**ricordi**: P6–P11; **mappa**: P1–P5) partono **insieme** e non si toccano; si incontrano solo in P12 e P13.
- **P1 e P2 aspettano gli scaricamenti approvati** (§8, Q8). Fino ad allora: P3 (puro), la parte pura di P5 (catalogo, stime, copertura) e i
  test della politica di rete di P1 sulla JVM (la funzione `TilePolicy` non ha bisogno di MapLibre).
- **P4 aspetta P1** (MapLibre nel progetto) e **P2** (stile e carta di base); prima può scrivere bolle, griglia della zona, sezione Luoghi e
  pagina sulla privacy sopra una superficie finta (`FakeMapSurface` con la stessa proiezione).
- **P8**: i viaggi partono subito (Fase 7 in `main`); le persone si scrivono sui contratti di `core-faces` e sul null-object, e si
  **accendono dopo P13 della Fase 8** (la riga dei ricordi con le persone di M2 aspetta quel merge).
- **P9 e P10** usano i fake di P6 (`InMemoryMemories`) finché P6 non è in `main`.
- **P11** aspetta P6 e P7 per la corsa vera; scrive subito metriche e Libreria sintetica.
- **M1** è un'integrazione parziale dell'orchestratore dopo P6, P7, P8 (viaggi), P9: Ricordi sull'S25 senza notifiche.
- **Prima di M2**: l'emulatore con l'account `test` (secondo dispositivo dello stesso account), acceso solo per le prove e spento subito dopo.
- Al più quattro agenti insieme (limite del PC); P1 e P0 hanno la precedenza sui crediti opus.

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Sull'S25 (debug, `test`): Raccolte → Ricordi con "anni fa", viaggi ed eventi, il meglio del mese (dalla Libreria di prova con date relative a oggi); pagina, "Tutti i ricordi", presentazione; "Mostra meno", "Mai nei ricordi", "Non mostrare"; nascosti mai | F111, F112, F113, F114 (un dispositivo), F118 | P0, P6, P7, P8 (viaggi), P9 (+ integrazione minima) |
| **M2** | Notifica all'ora scelta (finestra, frequenza, al più una al giorno), schermata di blocco nelle due modalità, tocco che apre i Ricordi dopo il blocco app, notifica tolta per un nascosto; visti e scelte che viaggiano verso l'emulatore; ricordi con le persone (dopo P13 della Fase 8) | F114 (due dispositivi), F115, F116, F117, F129, F130 (dopo P13 della Fase 8) | + P10, P8 (persone) |
| **M3** | Mappa con la carta di base e zero traffico a mappa spenta; 100 mila punti fluidi; griglia e viewer anche solo-NAS; mappa online col cancello e solo l'host dichiarato; una regione scaricata e mai più chiesta online; mini-mappa offline | F119–F124, F126, F127 | + P1, P2, P3, P4, P5, P12, P13 |
| Chiusura | Valutazione della qualità sui dati veri (Q7), benchmark su S25 e Tab S9, revisione di sicurezza e verifica funzionale; checklist (§6.3); screenshot approvati | `check.ps1`, `check.ps1 -Bench`, F125, F128, `security-review`, verifica funzionale | — |

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–8)
- **Mai** toccare file fuori dal proprio perimetro. `settings.gradle`, `build.gradle`, `versions-app.gradle`, `gradle/verification-metadata.xml`,
  manifest, `check.ps1`, `CollectionSections.kt`, `InfoBuilder.kt`, `AppNotifications.kt`, `MainActivity.kt`, `GalleryWork.kt`,
  `TripPrivacyTest`, `AppNavHost.kt`, `LocalData.kt` solo dai pacchetti indicati al §1.5 e al §4.4.
- **Rete**: nessun codice HTTP/TLS fuori da `feature-map/.../net/` (e dal pacchetto dei modelli, che non si tocca); ogni pacchetto della mappa ha
  il test "a mappa spenta zero richieste" sul suo pezzo; **nessuna dipendenza** che non sia nell'elenco approvato (§8); nessuno scaricamento
  durante i test (i test della rete usano un server finto in memoria dentro l'intercettore, mai Internet).
- **Nessun dato personale** nel repository, nei test, nei log, negli screenshot e nei report: niente foto vere, luoghi veri della famiglia, date
  vere, nomi di persone vere, coordinate di casa; le prove usano Librerie sintetiche e le immagini di prova delle fasi precedenti (viaggi
  sintetici della Fase 7, ritratti di pubblico dominio della Fase 8).
- **Nascosti**: ogni superficie nuova si registra in `HiddenSurfaces` e ha il test "un nascosto perfetto non esce mai" (D31).
- **Casa**: nessun pacchetto della Fase 9 legge `home_estimate`, `HomeClearance` o `AwayFromHome`; la porta dei viaggi non ha coordinate.
- **Ora**: `Today` per "oggi", giorni locali dello scatto, ora locale del dispositivo per la notifica, monotono per durate e budget.
- **Log**: mai titoli, luoghi, date, id, nomi, coordinate, riquadri, tessere, regioni; solo numeri e codici; `toString()` sicuri.
- **Stabilità**: ogni scrittura dello scaffale e del registro delle notifiche è atomica e ha il test della morte del processo a ogni salvataggio;
  il generatore è deterministico a parità d'ingresso (stessa Libreria, stesso giorno = stessi ricordi, stessi elementi, stesso ordine).
- **Fake fedeli con test di parità** (§5.2).
- Test JVM o Robolectric per ogni modulo; strumentati per MapLibre e il cancello (emulatore, S25, Tab S9); screenshot (solo dove c'è UI) in
  `docs/screenshots/9-<pacchetto>/`, chiaro e scuro, telefono e tablet, testo a 1,3x; TalkBack e bersagli da 48 dp.
- Stringhe in italiano in file propri.
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti degli
  altri**.

### 4.4 I pacchetti
"Agente": **interno** per tutti; chi tocca dispositivi, NAS di prova o scaricamenti lo dice nella colonna "Campo".

| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Agente | Campo |
|---|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Moduli, contratti, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`); creazione di `core-memories`, `feature-memories`, `core-map`, `feature-map` (build, manifest, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest` con la sola eccezione `net/` per `feature-map`, `NoWallClockSourcesTest`); **contratti** del §1.2 con null-object, fake ed esecutori di riferimento (`InMemoryMemories`, `ReferencePlanner`, `ReferencePicker`, `InMemoryShelfStore`, `ReferenceClusterIndex`, `InMemoryMapRegions`, `FakeMapNetworkState`); firme in `core-personal` (costanti dei campi D10–D11, `PersonalMemories` con il giorno), `core-albums` (`TripMemories`, solo firma e null-object, membro nuovo nell'elenco chiuso di `TripPrivacyTest`), `feature-viewer` (`ViewerPlaceMap`), `feature-library` (`CollectionSections.PLACES`, `CollectionsLayoutTest`), `core-common` (`DiagSection.MEMORIES`, `DiagSection.MAP`, `FeatureFlags.mapOnline`, `Phase9ArchitectureTest`, regola 5 a due pacchetti); `tools/check.ps1` (OkHttp e MapLibre solo dove ammessi nel dex, strumenti di valutazione assenti dalla release); documenti del §0.3 | Fase 8 P0–P11 in `main` | `check.ps1` verde; ogni test d'architettura fallisce reintroducendo la violazione (classe Android in `core-memories` o `core-map`; `core-memories` che nomina `core.faces`, `core.search`, `core.albums`, `core.ai`, `java.net`; client HTTP fuori da `feature-map/.../net/`; MapLibre fuori da `feature-map`; `MapOnlineConsent` o `RegionDownloadConfirmation` costruiti fuori dalla loro pagina; `GeoPoint` o `Double` nella firma di `TripMemories`; un `PersonalKind` nuovo); gli esecutori di riferimento passano le suite di contratto (§5.2); un'app della Fase 8 legge i segmenti personali con i campi nuovi senza scartarli; documenti aggiornati (§0.3) | **opus** | interno | no |
| **P1 Libreria delle mappe e cancello di rete** | D21–D24, §2.7 (verifiche), §2.8 | `versions-app.gradle` e `gradle/verification-metadata.xml` (MapLibre e transitivi approvati), `feature-map/build.gradle`, `spike/maplibre/` (prova, fuori da `settings.gradle`), `core-map/.../policy/**` (`TilePolicy`), `feature-map/.../net/**` (`MapTileHttp`, intercettore, contatori, riscrittura dell'URL stabile, TileJSON in cache), l'inizializzazione di MapLibre in `feature-map/.../map/MapLibreInit.kt`, i controlli di rete e di 16 KB in `tools/check.ps1`, `gradle/verify-release-manifest.gradle` (solo se il manifest fuso lo chiede, con la riga in `docs/sicurezza.md`) | P0; **scaricamenti approvati** (§8, Q8) | le dieci verifiche del §2.7 scritte con l'esito (versione scelta, peso dell'APK prima e dopo, 16 KB, manifest, nessuna richiesta all'avvio); `TilePolicy` con test di proprietà su 100.000 richieste casuali (host simili, porte, schemi, percorsi ostili, `z` oltre 14, `x`/`y` fuori limite, redirect, mappa spenta, roaming, dati mobili, tessere coperte): **nessuna** richiesta lasciata passare fuori dalle regole; intercettore: cookie, `Referer` e header non elencati tolti, `User-Agent` fisso; a mappa spenta **zero socket** (test strumentato con un `SocketFactory` che conta, più `setConnected(false)`); redirect fuori host rifiutati; dex: OkHttp chiamato solo da `feature/map/net/` e da MapLibre; numeri e versioni in `docs/decisioni.md` | **opus** | interno | sì (F119, F120) |
| **P2 Carta di base, stile, glifi** | D22, D25, §2.7 | `tools/maps/base/**` (Natural Earth e città GeoNames → GeoJSON semplificati, impronte), `tools/maps/style/**` (adattamento degli stili, controllo di glifi e URL), `feature-map/src/main/assets/map/**` (stili chiaro e scuro, glifi, sprite, carta di base, `LICENSE-*`, `NOTICE`), le righe dell'attribuzione (`PlacesAttribution` esteso, Informazioni e licenze) | P0; **scaricamenti approvati** | carta di base riproducibile (stesse impronte a ogni corsa dello strumento), ≤ 4 MB nell'APK; ogni `text-font` ha i suoi glifi; nessun URL nello stile fuori da `asset://` e dall'URL stabile delle tessere (test sul JSON); stile scuro e chiaro con contrasto ≥ 4,5:1 per le etichette (§4 del piano); licenze e attribuzioni giuste per ogni pezzo; screenshot `9-P2` della carta di base senza rete | sonnet | interno | no (PC) |
| **P3 Punti e raggruppamento** | D27, D28, §2.10 (indice) | `core-database/.../dao/MapPointsQueries.kt` e il suo test, `core-map/.../points/**`, `core-map/.../testing/` (Libreria sintetica con coordinate, mai nel dex di release) | P0 | coordinate effettive con la stessa regola del pannello info (correzioni comprese, test con `metadata_correction`); solo celle visibili (nascosti e membri di gruppo nascosti mai, proprietà); 100 mila righe < 400 ms sulla JVM, `EXPLAIN QUERY PLAN` senza `TEMP B-TREE`; indice uguale al riferimento a forza bruta (conteggi e copertine) su 1.000 viste casuali a ogni livello, antimeridiano e poli compresi; `clusters` < 4 ms p95 su 100 mila punti; memoria ≤ 3 MB per 100 mila; ricostruzione con scambio atomico | sonnet | interno | no |
| **P4 Schermate della mappa** | D24 (pagina), D28 (bolle), D29, D30, §2.5 (Luoghi), §2.10 (schermata, mini-mappa) | `feature-map/.../map/**` (tranne `MapLibreInit.kt`), `grid/**`, `places/**`, `privacy/**`, `mini/**`, `settings/**` (`MapSettings` su DataStore; il contratto è di `core-map`), `feature-viewer/.../info/` (solo la riga "Mostra sulla mappa" e la mini-mappa), `feature-settings` (voce "Mappa"), le righe `MAP` di `HiddenSurfaces` con le sonde | P0, P1, P2 (prima con `FakeMapSurface`), P3 | ogni stato del §1.5 della mappa composto; bolle al più 60, riposizionate senza nuova misura del layout (test di composizione), ricalcolate a camera ferma; camera iniziale con zoom ≤ 5 e poi l'ultima locale (test); mai "Casa" (test sulle etichette); mini-mappa con lo stile solo offline (test: lo stile non ha la sorgente online) e mai dai Nascosti; pagina sulla privacy con il testo del §2.8 e la versione, l'unica che costruisce `MapOnlineConsent`; superfici registrate e proprietà verde (rossa togliendo il filtro); TalkBack sulle bolle ("12 foto, Firenze"), 1,3x, 48 dp; screenshot `9-P4` | sonnet | interno | sì (F119, F122) |
| **P5 Regioni offline** | D26, §2.9 | `core-map/.../regions/**` (catalogo, stime, copertura per `TilePolicy`), `feature-map/.../regions/**` (foglio, avanzamento, `OfflineManager`, ripresa), la parte "Zone senza Internet" delle impostazioni della mappa | P0, P1, P3 | catalogo fisso (20 regioni, paesi vicini, "attorno a un luogo"); stima entro ±30% su tre regioni di prova scaricate (numeri in `docs/decisioni.md`); `RegionDownloadConfirmation` solo dal foglio; scaricamento solo sul Wi-Fi o con la seconda conferma; ripresa dopo la morte del processo (test strumentato); `covers` esatto ai bordi (proprietà sulle tessere); tessere coperte mai richieste online (contatore dell'intercettore a zero); tetti e spazio libero; aggiornamento solo a mano; screenshot `9-P5` | sonnet | interno | sì (F121) |
| **P6 Motore dei ricordi e scaffale** | D3, D4, D9, D10, §2.1, §2.3 (scaffale, voci) | `core-memories/.../plan/**`, `shelf/**`, `settings/**` (visti, scartati; `SyncedMemoriesSettings` in `core-personal/.../settings/`), `core-personal/.../settings/PersonalCollections.kt` (solo `PersonalMemories`) | P0 | i quattro tipi su Librerie sintetiche con le soglie di D3 (29 febbraio, fusi, anni senza foto, giorni di soli screenshot); al più 2 nuovi al giorno; generatore **deterministico** (proprietà: stessa Libreria e stesso giorno = stesso risultato); id deterministici e uguali su due dispositivi finti con Librerie diverse; scaffale: solo togliere dopo la pubblicazione (proprietà su 10.000 storie di nascosti, eliminazioni e arrivi), file atomico con la morte del processo a ogni salvataggio, file rovinato = rigenerazione; visti e scartati che viaggiano fra due dispositivi finti (`InMemoryPersonalStore`), anche senza l'ora del NAS; < 1 s su 100 mila sulla JVM | sonnet | interno | sì (F111, F112) |
| **P7 Scelta delle foto** | D5–D8, §2.2 | `core-memories/.../pick/**`, le porte `MemoryVectors` e `MemoryPixels` (contratti e fake), la misura dei pixel come funzione pura su un array di luminanze (la decodifica la lega P13) | P0 | esclusioni di D5 una per una; punteggio con i pesi in tabella; doppioni con vettori finti (coseno ≥ 0,92), con la firma e con la finestra di 3 minuti; varietà con i tetti per giorno e per ora; copertina mai WhatsApp, mai un nascosto, mai una persona con "Mostra meno"; filtro di Q6 dietro un'opzione, che **toglie e basta** e non lascia tracce (test sullo stato dopo la scelta e sui log); risultato con soli conteggi per motivo; < 50 ms per un ricordo da 200 candidati sulla JVM | sonnet | interno | no |
| **P8 Viaggi e persone nei ricordi** | D11, D12, §2.3 ("Mostra meno") | `core-albums/.../trips/` (solo `TripMemories` sopra `TripReader`, scartati esclusi, accettati compresi) e `TripPrivacyTest`, il generatore `PERSON` in `core-memories/.../plan/people/`, `core-memories/.../settings/less/` (voci `memories_less`, `memories_never`, `PeopleLessJanitor`), i fake di `MemoryPeople` | P0; P13 della Fase 8 per accendere le persone | `TripMemories` senza coordinate (riflessione) e con i soli media propri e visibili; viaggi scartati mai, alias e startKey compresi (E-MEM13); persone solo con nome, non nascoste, non dimenticate, alias risolti; "Mostra meno" e "Mai" con l'effetto di D11 (proprietà su ricordi e copertine); lapidi alla cancellazione delle persone e ricordi `PERSON` tolti dallo scaffale (E-MEM10); nessun nome nei dati personali né nello scaffale (test sui byte) | sonnet | interno | sì (F114, F130) |
| **P9 Schermate dei ricordi e presentazione** | D13, D14, §2.4, §2.5 (Ricordi), §1.5 (ricordi) | `feature-memories/**` (sezione, pagina, "Tutti i ricordi", `MemoryPlayer`, fogli, pagina delle impostazioni), `feature-settings` (voce "Ricordi e notifiche"), le righe `MEMORIES` di `HiddenSurfaces` con le sonde | P0 (fake di P6, P7, P8) | ogni stato del §1.5 dei ricordi composto; presentazione con i soli token di movimento (`NoHandWrittenStyleTest`), animazioni ridotte senza movimento, TalkBack senza avanzamento automatico; precaricamento di 2 immagini e mai un'attesa sul NAS (test con una sorgente lenta); pausa, scorrimento, uscita; musica secondo Q3 (con Q3 (b): selettore di sistema, permesso persistito, focus audio, file sparito); "visto" solo dalla pagina o dalla presentazione; superfici registrate e proprietà verde; TalkBack, 1,3x, 48 dp; screenshot `9-P9` | sonnet | interno | sì (F113, F118) |
| **P10 Notifiche** | D15–D20, §2.6 | `core-memories/.../notify/**` (`MemoryNotifyPolicy`, registro del giorno), `app/.../notify/AppNotifications.kt` (solo il canale e l'id 1009), `app/.../notify/MemoryNotifier.kt`, `app/.../work/MemoriesWork.kt` (`MemoryNotifyWorker`), `app/.../MainActivity.kt` (solo l'azione `OPEN_MEMORIES`) ed `EntryHardeningTest`, il flusso del permesso nella pagina di P9 (porta), le righe `NOTIFICATIONS` di `HiddenSurfaces` | P0 (fake di P6) | finestra, salto oltre H+3 h, fusi e ora legale, frequenze di Q4, al più una al giorno anche con due esecuzioni e con la morte del processo fra registro e notifica (proprietà); modalità generica senza nessun testo del ricordo (test sul contenuto); "Titolo e foto" mai con nomi e con la versione pubblica generica; revoca su nascosto e scarto; intent: solo l'azione nota, extra e dati buttati, apertura dopo il blocco app (Robolectric); permesso negato e canale spento senza richieste automatiche; nessun allarme né permesso nuovo (`verifyReleaseManifest` verde); screenshot `9-P10` della notifica nelle due modalità | sonnet | interno | sì (F115–F117) |
| **P11 Valutazione dei ricordi** | §5.3 | `core-memories/src/test/.../eval/**` (metriche, Libreria sintetica "di una famiglia" con anniversari, viaggi, persone, giorni tristi seminati come concetti nei vettori finti), `app/src/debug/.../eval/memories/**` (strumento solo debug), `tools/fixtures/memories/**` (generatore della Libreria di prova con date relative al giorno della prova, nessun dato personale) | P6, P7 (prima sul riferimento) | metriche (ricordi graditi, foto da togliere per ricordo, doppioni, ricordi "da non mostrare") calcolate; corsa del riferimento sul sintetico con i numeri attesi; strumento solo in debug (assente dal dex di release, `check.ps1`); **nessuna etichetta, data, luogo o immagine vera nel repository** (test su git, `.gitignore`); pesi e soglie scelti con la regola del §5.3 e scritti in `docs/decisioni.md` | sonnet | interno (la corsa sui dati veri la fa l'utente) | sì (F125) |
| **P12 Benchmark e fluidità** | §5.4 | `benchmark/src/**` (`MemoriesBenchmark`, `MapBenchmark`), `benchmark/soglie.json` (scenari `ricordi` e `mappa`), `app/src/benchmarkRelease/**` (coordinate, giorni "di un anno fa", scaffale seminato), `docs/bench/README.md` | P4, P9 (schermate), P3 (indice) | i due scenari **esistono e girano** (non solo nei documenti: §0, punto 12) su emulatore (smoke) e S25; soglie del §5.4 nel file con le guardie di regressione; le guardie delle Fasi 3–8 non peggiorano con la mappa aperta e con la generazione in corso; riferimento S25 registrato | sonnet | interno | sì (F123, F124) |
| **P13 Integrazione** | §1.4 | `app/**` (tranne i file di P10), `feature-diagnostics/**`, `README.md`, `docs/sicurezza.md`, `docs/decisioni.md`, `docs/modello-dati.md` | P0–P12 | `MemoriesGraph` e `MapGraph` legati; porte vere (viaggi, persone dopo P13 della Fase 8, vettori, pixel dalla replica, punti, stato della rete, traffico); MapLibre pigra con il client installato prima (test); test d'integrazione con due dispositivi finti dello stesso account (visti, scartati, "Mostra meno" che viaggiano; scaffali diversi; nessun nome nei dati personali); azzeramento completo (nessun file in `memories/` e `map/`, notifica tolta, lavoro annullato); codici della Diagnostica nel catalogo e nell'esportazione; registro dei Nascosti verde; `check.ps1` verde; APK debug e release | sonnet | interno | sì (F126, F127, F129) |

> Nota sui crediti: opus per 2 pacchetti su 14, quelli che il brief indica: **P0** (contratti e recinti, l'eccezione di rete dichiarata, i
> moduli puri) e **P1** (la libreria che parla con Internet e il cancello che decide ogni richiesta: l'unica parte della fase che può far uscire
> qualcosa dal telefono). P10 tocca `MainActivity` e C17 ma con una sola azione e test chiari: sonnet, con una revisione opus mirata nella
> `security-review`. **Con Q2 (b)** si aggiunge **P14 Regioni dal PC** (`tools/maps/regions/`, unione con `mergeOfflineRegions` via SMB;
> sonnet, interno, sul PC e sul NAS di prova).

### 4.5 Chiusura della fase
- `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro le guardie delle Fasi 3–8 e dentro gli scenari nuovi `ricordi` e `mappa`
  (§5.4); F111–F130 superate (F125 con Q7).
- `security-review` (opus) con revisione mirata di `feature-map/.../net/`, `TilePolicy`, inizializzazione di MapLibre, manifest fuso, azione di
  `MainActivity`, contenuto delle notifiche, `TripMemories`; **in parallelo un agente di verifica funzionale** (§5.7).
- Checklist della Fase 9 spuntata (§6.3); `docs/sicurezza.md` aggiornato; screenshot approvati; risposte del §9 registrate.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-memories` (plan, pick, shelf, settings, notify), `core-map` (points, regions, policy), `TripMemories` di `core-albums` | generatori, scelta, stabilità, scelte dell'utente, decisioni sulle notifiche, raggruppamento, politica di rete |
| Libreria sintetica | `SyntheticLibrary` estesa con coordinate, anniversari, viaggi, persone, raffiche, screenshot, documenti, nascosti, vettori finti con "concetti" | 100 mila elementi per prestazioni e proprietà |
| Robolectric (SDK 35) | Compose dei due feature, `MainActivity` e intent, canale e notifica, DataStore della mappa, `PersonalStore` vero | stati, contenuti, consensi |
| Roborazzi | `feature-memories`, `feature-map`, info del viewer, notifica | chiaro e scuro, telefono e tablet, testo a 1,3x |
| Strumentati | MapLibre con il client (emulatore x86_64, S25, Tab S9), `OfflineManager`, proiezione, `MapSnapshotter` | zero socket a mappa spenta, ripresa delle regioni, proiezione delle bolle |
| Benchmark | `MemoriesBenchmark`, `MapBenchmark` e le guardie esistenti | §5.4 |
| Dispositivi | S25 (`test`), emulatore (`test`, secondo dispositivo), Tab S9 nel momento breve | §5.6 |

### 5.2 Fake fedeli e test di parità
| Fake | Fedeltà | Parità |
|---|---|---|
| `InMemoryMemories` (riferimento del servizio) | scaffale, scelte e visti in memoria con le stesse regole di D9–D11 | su 500 storie sintetiche (giorni che passano, nascosti, arrivi, scelte su due dispositivi) lo scaffale del servizio vero è uguale a quello del riferimento |
| `ReferencePlanner` | i quattro tipi per forza bruta (scansione di tutta la Libreria per ogni tipo) | stessi candidati e stesse chiavi del generatore vero su 2.000 giorni casuali |
| `ReferencePicker` | punteggio, doppioni e varietà senza ottimizzazioni (tutte le coppie) | stessi elementi e stessa copertina salvo i casi a ± 1e-4 dalle soglie |
| `InMemoryTripMemories` | dalle storie sintetiche di P6/P7 della Fase 7 | sulle 20 storie le stesse `TripDigest` (giorni, membri, scartati esclusi) della porta vera sopra `TripReader` |
| `FakeMemoryPeople` | sopra `InMemoryPeople` della Fase 8 (solo con nome, nascoste, dimenticate, alias) | la suite `MemoryPeopleContract` gira sul fake e sull'adattatore vero sopra `PeopleIndex` (dopo P13 della Fase 8) |
| `FakeMemoryVectors`, `FakeMemoryPixels` | vettori finti normalizzati con concetti seminati (doppioni, "ospedale"), luminanze e nitidezze da tabella | la suite di contratto gira anche sugli adattatori veri (`SearchData.vectors`, miniature di `core-imaging`) in Robolectric e strumentati |
| `InMemoryShelfStore` | file in memoria con morte simulata fra scrittura e rinomina | la suite `ShelfStoreContract` gira su `FileShelfStore` (cartella temporanea, file troncati e rovinati) |
| `ReferenceClusterIndex` | raggruppamento per forza bruta (ogni punto nella sua cella) | stessi conteggi e copertine di `GeoClusterIndex` su 1.000 viste casuali per livello |
| `FakeMapSurface` | proiezione di Mercatore con le stesse formule di MapLibre, camera finta | posizioni delle bolle uguali a quelle di `MapLibre` entro 0,5 px (strumentato) |
| `FakeTileServer` | risposte finte dentro la catena dell'intercettore (TileJSON, tessere, redirect, errori, tipi sbagliati) | lo **stesso** intercettore di produzione: il fake sostituisce solo l'ultimo anello (la rete) |
| `InMemoryMapRegions` | catalogo, stime, copertura, stati di scaricamento | la copertura coincide con quella della regione vera (tessere elencate dal database di MapLibre) su tre regioni |
| `FakeMemoryClock` | "oggi" e l'ora scelti dal test (anche nel pannello di debug per le prove sul campo) | — |

### 5.3 Valutazione della qualità dei ricordi sui dati veri, senza dati personali nel repository
**Il problema**: se un ricordo è bello, triste o noioso lo sa solo l'utente, sulle sue foto; né foto, né date, né luoghi, né giudizi devono
finire nel repository.
1. **Pubblico, nel repository** (a ogni `check.ps1`): la Libreria sintetica "di una famiglia" di P11 (cinque anni, viaggi, compleanni, raffiche,
   screenshot e documenti nei giorni giusti, foto buie e mosse come misure finte, concetti "tristi" nei vettori finti): nessun escluso di D5 nei
   ricordi (100%), almeno il 95% dei doppioni seminati tolti, nessun concetto "triste" con Q6 (b) acceso, ricordi deterministici, dimensioni
   dentro i limiti di D7.
2. **Privato, fuori dal repository** (la misura che conta, F125):
   - lo strumento **solo debug** (Diagnostica → Sviluppo → "Valutazione dei ricordi") genera in memoria i ricordi degli **ultimi 45 giorni**
     come se "oggi" fosse ognuno di quei giorni (circa 40–60 ricordi), senza scrivere lo scaffale né i dati personali;
   - per ogni ricordo l'utente vede titolo e griglia e risponde con un tocco: **"Bello"**, **"Così così"**, **"Da non mostrare"** (con un motivo
     facoltativo: triste, sbagliato, noioso); nella griglia tocca le foto che **non** dovevano esserci (doppione, mossa o buia, screenshot o
     documento, non c'entra, triste);
   - **misure**: ricordi "Bello" ≥ 70%; "Da non mostrare" ≤ 3%; foto da togliere ≤ 1 su 12 (8%) in media; doppioni ≤ 3% delle foto; per il
     filtro di Q6, quante foto ha tolto e quante avrebbe dovuto togliere;
   - le etichette sono `MediaId` e giudizi in `noBackupFilesDir/eval/memories/` (fuori dai backup, tolti dall'azzeramento); lo strumento
     esporta **solo numeri**, che vanno in `docs/decisioni.md`.
3. **Calibrazione**: se le misure mancano, si cambiano **solo** i parametri (pesi di D6, soglia dei doppioni, λ della varietà, minimi di D7,
   soglia del filtro), guardando i numeri e mai le foto dal PC; poi una seconda sessione breve di conferma.
4. **Tempo dell'utente**: circa **25 minuti** (40–60 ricordi a circa 25 secondi l'uno) più, se servono correzioni, circa 10 minuti di conferma,
   sul telefono, quando vuole (Q7); il tablet non serve.

### 5.4 Benchmark e soglie di fluidità (il "Fatto quando" della fase)
**Scenari nuovi** (P12; Macrobenchmark sul `benchmarkRelease` con la Libreria da 100 mila, il 60% con coordinate, scaffale seminato)
| Scenario | Metrica | Soglia (S25 e Tab S9, 120 Hz) | Guardia |
|---|---|---|---|
| `ricordi` | apertura di un ricordo da Raccolte (primo fotogramma della griglia) | p90 ≤ 300 ms | +15% |
| `ricordi` | presentazione per 30 s con il movimento: fotogrammi oltre 8,3 ms | ≤ 1% (`jankPercent`, come la Libreria) | riferimento +10% relativo, pavimento 0,15 punti |
| `ricordi` | fotogramma più lungo a un passaggio | ≤ 16,7 ms | +15% |
| `ricordi` | generazione notturna su 100 mila (sezione di traccia `memories.generate`) | ≤ 2.000 ms; passo leggero ≤ 300 ms | +15% |
| `ricordi` | scorrimento delle Raccolte con le sezioni Ricordi e Luoghi | guardia esistente (≤ 1%) | come oggi |
| `mappa` | apertura della mappa dalla sezione Luoghi (bolle visibili) | p90 ≤ 800 ms a freddo, ≤ 300 ms a caldo | +15% |
| `mappa` | spostamento e pizzico scriptati per 20 s: fotogrammi dell'interfaccia oltre 8,3 ms | ≤ 1% | come la Libreria |
| `mappa` | tempo di disegno di MapLibre (p95 per fotogramma) | ≤ 12 ms [da calibrare in P12 sul primo riferimento] | +15% |
| `mappa` | aggiornamento delle bolle a camera ferma (sezione di traccia `map.clusters`) | p95 ≤ 8 ms | +15% |
| `mappa` | apertura della griglia di una zona; viewer dalla griglia | p90 ≤ 150 ms; ≤ 100 ms (guardia del viewer) | +15% |
| `mappa` | memoria (PSS) a mappa aperta | riferimento | +15% |

- **Le guardie esistenti non peggiorano** con la mappa aperta in un'altra scheda e con la generazione dei ricordi in corso (`check.ps1 -Bench`).
- **JVM** (a ogni `check.ps1`): generazione su 100 mila < 1 s; scelta di un ricordo da 200 candidati < 50 ms; indice della mappa: costruzione
  < 800 ms, `clusters` < 4 ms p95; query dei punti < 400 ms senza `TEMP B-TREE`.
- **Nessun nascosto** (l'altra metà del "Fatto quando"): proprietà del registro `HiddenSurfaces` sulle righe `MEMORIES`, `NOTIFICATIONS`, `MAP`
  (§2.11) e F113, F116, F122.

### 5.5 Prove sul traffico della mappa
| Livello | Prova | Atteso |
|---|---|---|
| Sorgenti | `Phase9ArchitectureTest` (regola 5 a due pacchetti), `NoNetworkSourcesTest` di ogni modulo nuovo, `check.ps1` sul dex (OkHttp chiamato solo da `feature/map/net/` e da `org.maplibre`) | rosso reintroducendo un client fuori posto |
| JVM | proprietà di `TilePolicy` su 100.000 richieste casuali; intercettore con `FakeTileServer`: a mappa spenta **nessuna** chiamata a `proceed()`, accesa solo le richieste ammesse, header ripuliti, redirect fuori host rifiutati, tessere coperte rifiutate | zero eccezioni alle regole |
| Strumentato | client con `Dns` e `SocketFactory` che contano: mappa aperta, spostamenti, mini-mappa, Luoghi a mappa spenta → **0 risoluzioni DNS, 0 socket**; accesa → solo `tiles.openfreemap.org:443` | numeri nel report di P1 |
| Emulatore | NAS irraggiungibile, mappa spenta, 5 minuti d'uso: `dumpsys netstats detail` per l'UID dell'app prima e dopo | nessun byte verso Internet |
| S25 | con **PCAPdroid** (se installato: è già nella lista della Fase 8) in modalità "solo questa app": F119 e F120 | spenta: solo l'IP del NAS (SMB); accesa: in più il solo host della mappa (e il DNS) |
| Debug | contatori dell'intercettore per motivo e per livello `z` (mai `x` e `y`) letti dal PC con `adb` | coerenti con la cattura |

### 5.6 Verifica sul campo
**Come si lavora**: le prove girano **da sole** sull'S25 con la build debug e l'account `test` (area comune `test-comune`): uno scenario per
prova nel pannello di debug "Prove della Fase 9" (come le prove delle Fasi 7 e 8), che semina la **Libreria di prova con date relative al giorno
della prova** (P11: JPEG sintetici con EXIF e GPS di luoghi lontani dalla famiglia, "un anno fa", "tre anni fa", un viaggio sintetico di un
anno fa, un evento, il mese scorso) in `mcg-prova`, sposta "oggi" con `FakeMemoryClock` dove serve, guida l'interfaccia con UI Automator e
scrive l'esito in un file letto dal PC con `adb`; il verificatore dal PC (`mcg-it`) controlla il NAS di prova. L'emulatore con `test` è il
secondo dispositivo dello stesso account, acceso solo per F114, F117 e F129 e spento subito dopo. **Nessuna prova chiede passaggi all'utente**:
i dialoghi della nostra app li conferma l'automazione; le parti che chiedono un PIN della schermata di blocco si fanno sull'emulatore con un PIN
di prova messo e tolto dallo script. L'utente serve solo per **F125** (circa 25 minuti, quando vuole) e per **un momento breve con il tablet**
(circa 15 minuti, F128, insieme ai momenti con il tablet ancora in sospeso delle Fasi 6, 7 e 8). F120 e F121 parlano con OpenFreeMap: partono
solo dopo il sì a Q1 e agli scaricamenti (Q8).

**A. Preparazione**
1. S25 con la build debug e `test`; emulatore con la stessa build e `test` (approvato con la guardia di prova).
2. La Libreria di prova di P11 in `mcg-prova` e, dal backup di prova, in `test` (una parte poi tolta dal telefono, per i media solo sul NAS).
3. Per F125: la build debug con lo strumento di valutazione sul telefono con la Libreria vera (lo strumento non scrive né lo scaffale né i dati
   personali).
4. PCAPdroid sull'S25 se l'utente l'ha già installato (Fase 8, §8); altrimenti contatori e `netstats` sull'emulatore.

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F111 | Ricordi generati (autonoma, S25): Libreria di prova, generazione notturna forzata, Raccolte → Ricordi | "Un anno fa", "3 anni fa", il viaggio di un anno fa, l'evento, il meglio del mese (con "oggi" al 3 del mese); nessuno screenshot, documento o data stimata dentro; titoli e luoghi giusti |
| F112 | Stabilità (autonoma): ricordo aperto; poi una foto nuova dello stesso giorno, un elemento nascosto, uno eliminato; poi "domani" | il ricordo non prende la foto nuova, perde il nascosto e l'eliminato, non cambia titolo né ordine; domani resta uguale; sotto 3 elementi esce |
| F113 | Nascosti nei ricordi (autonoma): nascosti fra gli elementi, come miglior copertina, in un gruppo, durante la presentazione | non compaiono in nessuna superficie della riga `MEMORIES` (UI Automator legge le descrizioni accessibili); conteggi senza di loro |
| F114 | Scelte (autonoma, S25 poi emulatore): "Non mostrare", "Non ricordarmi questo periodo", "Mostra meno" e "Mai" per una persona (dopo P13 della Fase 8) | effetto subito sull'S25; dopo un giro dei dati personali, uguale sull'emulatore; dal PC: nulla di nuovo fuori dalle teste personali dell'area privata di `test`, nessun nome nei segmenti |
| F115 | Notifica all'ora scelta (autonoma): ora = adesso + 3 minuti; seconda esecuzione nello stesso giorno; Doze forzato (`dumpsys deviceidle force-idle`); frequenza "2-3 a settimana" su 14 giorni simulati; poi 14 giorni reali passivi | una notifica nella finestra, mai due nello stesso giorno, giorno saltato oltre H+3 h; frequenza rispettata; nei 14 giorni reali la percentuale di ritardi oltre H+1 h (decide il piano B di D20 se > 10%) |
| F116 | Notifica e nascosti (autonoma): modalità "Titolo e foto", poi la copertina nascosta; poi "Non mostrare" | la notifica sparisce entro pochi secondi; aprendo prima, il ricordo senza il nascosto |
| F117 | Schermata di blocco e apertura (autonoma; schermata di blocco sull'emulatore con un PIN di prova): due modalità, con il sistema su "Mostra tutti i contenuti" e su "Nascondi"; tocco sulla notifica; `am start -a …OPEN_MEMORIES` con extra e dati inventati | in modalità generica nessun titolo, luogo o foto in nessun caso; con "Titolo e foto" mai un nome; il tocco apre i Ricordi dopo lo sblocco; l'intent esterno apre solo la pagina Ricordi, extra e dati ignorati |
| F118 | Presentazione (autonoma): 60 s su un viaggio; animazioni ridotte (`animator_duration_scale 0`); TalkBack acceso; musica secondo Q3 con un file di prova | fluida (misure del §5.4); niente movimento con le animazioni ridotte; niente avanzamento con TalkBack; musica che si ferma con la presentazione |
| F119 | **Zero traffico a mappa spenta** (autonoma): mappa, spostamenti per 2 minuti, mini-mappa, Luoghi, regioni (elenco) | nessuna connessione oltre al NAS (cattura o `netstats`); contatori dell'intercettore a zero; carta di base visibile |
| F120 | Mappa online (autonoma, dopo Q1 e Q8): pagina sulla privacy, accensione, 2 minuti di spostamenti sul Wi-Fi, poi i dati mobili con "Solo sul Wi-Fi" | solo `tiles.openfreemap.org` (e il DNS); percorsi solo `/planet…` (contatori per `z`); header ripuliti (dump di debug dei soli nomi); sui dati mobili nessuna richiesta |
| F121 | Regione offline (autonoma, dopo Q8): "zona di 15 km attorno a" un luogo di prova lontano dalla famiglia, sul Wi-Fi; `kill -9` a metà; poi spostamenti nella zona a mappa accesa e spenta | ripresa fino a "completa"; stima entro ±30%; nella zona nessuna richiesta online (contatore `COVERED`); a mappa spenta la zona si vede |
| F122 | Mappa con i media solo sul NAS (autonoma): media di prova con GPS tolti dal telefono; poi Wi-Fi spento | bolle, griglia e viewer dal NAS; senza NAS miniature dalla replica e viewer come E-L4; nessun nascosto (riga `MAP`) |
| F123 | Prestazioni della mappa (autonoma sull'S25; sul Tab S9 nel momento breve): `MapBenchmark` | soglie del §5.4 |
| F124 | Prestazioni dei ricordi (autonoma; Tab S9 nel momento breve): `MemoriesBenchmark` e tempi di generazione sulla Libreria da 100 mila | soglie del §5.4 |
| F125 | **Qualità sui dati veri** (utente, ~25 minuti, §5.3, Q7) | misure del §5.3; numeri in `docs/decisioni.md`; nessun dato fuori dal telefono |
| F126 | Diagnostica e log (autonoma): esportazione e `logcat` di una giornata con titoli, luoghi, coordinate e nomi seminati riconoscibili | nessuno di questi nell'esportazione e nei log (controllo automatico) |
| F127 | Azzeramento (autonoma, debug, account di prova) | nessun file in `memories/` e `map/`, nessuna notifica, nessun lavoro `memories-notify` |
| F128 | **Il momento con il tablet** (utente, ~15 minuti, una volta): profilo "Prova" con la sua ora delle notifiche; mappa e presentazione sul tablet; misure di F123 e F124 sul Tab S9 | scelte del profilo separate da quelle dell'S25 (E-NT10); misure dentro le soglie |
| F129 | Due dispositivi (autonoma, emulatore): visti e scartati sull'S25, poi un giro sull'emulatore; notifiche impostate diversamente | visti e scartati uguali; scaffali con contenuti che possono differire; impostazioni delle notifiche e della mappa non viaggiano |
| F130 | Ricordi con le persone (autonoma, dopo P13 della Fase 8): ritratti di pubblico dominio della Fase 8 con nomi di prova; poi "Disattiva e cancella" delle persone | ricordo "Con <nome di prova>"; "Mostra meno" rispettato; dopo la cancellazione nessun ricordo `PERSON` e le voci `memories_less` diventate lapidi |

**I "Fatto quando" del piano, prova per prova**
| Sottofase | Fatto quando (piano) | Dove si prova |
|---|---|---|
| 9.1 | Soglie di fluidità dei ricordi; nessun nascosto | §5.4 (`ricordi`), P9, P12, F113, F118, F124 |
| 9.2 | Soglie (la notifica non pesa); nessun nascosto nella notifica | P10, F115, F116, F117 |
| 9.3 | Soglie di fluidità della mappa con 100 mila elementi; nessun nascosto; funziona con i media solo sul NAS | §5.4 (`mappa`), P3, P4, P12, F122, F123 |
| 9.1–9.3 | Qualità dei ricordi (obiettivo del progetto, §5.3) e privacy della mappa (§5.5) | F119, F120, F121, F125 |

### 5.7 Verifica funzionale di fine fase
Un agente di verifica funzionale (come `docs/review/funzionale-7.md`), in parallelo alla `security-review`, rilegge i percorsi interi contro
questo documento e scrive `docs/review/funzionale-9.md` con voci "percorso, atteso, trovato, stato":
- generazione (i quattro tipi, soglie, al più due nuovi al giorno, notte e passo leggero, senza NAS, senza vettori);
- scelta delle foto (esclusioni, punteggio, doppioni, varietà, copertina, filtro di Q6);
- stabilità (scaffale, solo togliere, id, visti e scartati fra dispositivi, file rovinato);
- scelte dell'utente ("Mostra meno", "Mai", periodi, lapidi alla cancellazione delle persone);
- pagina e presentazione (gesti, precaricamento, animazioni ridotte, TalkBack, musica);
- notifiche (ora, finestra, frequenza, al più una, modalità della schermata di blocco, permesso e canale, revoca, apertura senza dati);
- mappa (carta di base, punti, bolle, griglia, viewer solo-NAS, camera iniziale, mai la casa, mini-mappa offline);
- rete (cancello, host, percorsi, header, redirect, regioni coperte, zero traffico a mappa spenta);
- regioni (catalogo, stima, conferma, ripresa, tetti, aggiornamento a mano);
- Nascosti su ogni superficie registrata; azzeramento; Diagnostica ed esportazione.
Le voci confermate si correggono prima della chiusura.

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 9
| Minaccia | Contromisura | Prova |
|---|---|---|
| **Posizione e abitudini dedotte dai ricordi** (dove si va ogni anno, con chi, quando) che escono dal telefono | generazione solo sul dispositivo; scaffale locale in `noBackupFilesDir`; fuori dal telefono solo id di ricordi (hash) e scelte nell'area privata; mai titoli, luoghi o date nei dati personali, nei log, nella Diagnostica (D4, D9, D10, D33) | P0 (moduli puri), P6, F126 |
| **Posizione e abitudini dedotte dalla mappa** (dove si vive, dove si guarda) | carta di base offline; mappa online spenta di default e per dispositivo; camera iniziale con lo zoom fino a 5; mai la casa; mini-mappa solo offline; regioni scaricate mai più chieste online; ultima camera solo locale (D24–D29) | P1, P4, P5, F119–F121 |
| **Traffico verso il fornitore delle mappe** (zona guardata, IP, orari) | un solo host; cancello puro su ogni richiesta; header ripuliti; niente cookie, `Referer`, identificativi; precaricamento spento; tessere fino a z14; regioni a catalogo fisso; pagina che lo dice prima (D22–D24, L236–L238) | P1, F120, F121 |
| **Una libreria che parla con Internet per conto suo** (telemetria, richieste all'avvio, altri host) | MapLibre solo in `feature-map`, client imposto prima di ogni mappa, `setConnected(false)` a mappa spenta, verifiche di P1 con cattura, dex controllato, manifest fuso controllato (D21, D23, L239) | P1, F119 |
| **Notifiche viste da chi ha il telefono in mano** (schermata di blocco, tendina) | modalità generica di default senza titolo, luogo, data né foto; con "Titolo e foto" mai nomi e versione pubblica generica; revoca su nascosto o scarto; nessun suono (D15, D16, D19) | P10, F116, F117 |
| **Un'app esterna che apre l'app su un ricordo** (deep link) | una sola azione fissa senza dati; si apre al più la pagina Ricordi, dietro il blocco app (D17, C17) | P10, F117 |
| **Nascosti che trapelano** da ricordi, presentazione, notifiche, mappa, Luoghi | una sola fonte, tre righe nuove del registro, proprietà, filtro finale (D31) | P4, P9, P10, F113, F116, F122 |
| **Un familiare che vede i ricordi o le scelte di un altro** | niente nell'area comune né nell'indice; voci solo nell'area privata dell'account; profili del tablet separati; ricordi solo da ciò che la Libreria di quel dispositivo già mostra (D32) | P13, F114, F129 |
| **Ricordi che fanno male** (un lutto, una separazione) | "Non mostrare", "Mostra meno", "Mai" per persone e periodi, sincronizzati e immediati; viaggi scartati esclusi; filtro di Q6 se scelto, senza tracce (D8, D11) | P7, P8, F114, F125 |
| **Inferenze delicate salvate** (foto "di ospedale") | il filtro di Q6 non salva, non sincronizza, non esporta: decide al momento e basta (D8) | P7 (test sullo stato e sui log) |
| **Nomi di persone fuori dai dati dei volti** | i ricordi salvano l'id, il nome si legge al momento; "Mostra meno" con l'id; lapidi alla cancellazione delle persone (D11, D12) | P8, F130 |
| **L'esportazione della Diagnostica o i log** | solo numeri e codici; elenco chiuso; test con titoli, luoghi, coordinate e nomi seminati (D33) | P13, F126 |
| **Dati ostili dal server delle mappe** (tessere enormi, tipi inattesi, redirect) | tipi e dimensioni controllati dall'intercettore; redirect solo sullo stesso host; MapLibre decodifica solo tessere vettoriali (D23, E-MAP4) | P1 |
| **Dispositivo perso o rubato** | scaffale, cache e regioni nello spazio privato dell'app, tolti dall'azzeramento alla riconnessione; notifica generica sulla schermata di blocco (D16, D37) | P13, F127 |

**Contromisure nuove** (proposta per `sicurezza.md` §4)
- **C128. Ricordi solo sul dispositivo**: generati dalla Libreria locale senza rete né NAS; scaffale locale; nei dati personali solo id hash,
  giorni di visione e scelte; mai titoli, luoghi, date o nomi fuori dall'app (D4, D9, D10).
- **C129. Comandi contro i ricordi indesiderati**: "Non mostrare", "Mostra meno" e "Mai" per persone e periodi, immediati e sincronizzati,
  con le lapidi alla cancellazione delle persone; il filtro facoltativo non lascia tracce (D8, D11).
- **C130. Notifiche discrete**: al più una al giorno, generiche di default, mai nomi, tolte se il ricordo diventa nascosto o scartato (D15, D16,
  D19).
- **C131. Apertura dalla notifica senza dati**: un'azione fissa riconosciuta in `MainActivity`, nessun extra né dato accettato, apertura dopo il
  blocco app (D17).
- **C132. Mappa online scelta per dispositivo**: spenta di default, pagina versionata prima di accenderla, "Solo sul Wi-Fi", mai in roaming;
  interruttore di build (D24).
- **C133. Cancello di rete della mappa**: un pacchetto solo, una funzione pura che decide ogni richiesta (un host, `GET`, percorsi, livelli,
  rete, regioni coperte), header ripuliti, zero richieste a mappa spenta, controlli su sorgenti, dex e cattura (D21–D23).
- **C134. La casa non passa dalla mappa**: nessun segno né etichetta, camera iniziale lontana, mini-mappa solo offline, regioni da un catalogo
  fisso, nessuna lettura di `home_estimate` (D26, D29).
- **C135. Nascosti anche nelle superfici della Fase 9**: righe `MEMORIES`, `NOTIFICATIONS`, `MAP` del registro, proprietà, filtro finale (D31).

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L13 riscritta**: "**Mappa online** (Fase 9, D22–D24): se la accendi su un dispositivo, OpenFreeMap riceve l'indirizzo della connessione e
  le tessere della zona guardata (a z14 un quadrato di circa 1,7 km), con l'ora; il DNS della rete vede che usi la mappa. Non riceve foto,
  posizioni delle foto, la casa o identificativi. Le zone scaricate non si chiedono più; a mappa spenta non esce niente."
- **L232. I dati locali della Fase 9 sono in chiaro** sul dispositivo (scaffale dei ricordi, cache e regioni della mappa, ultima camera): chi ha
  il telefono sbloccato con gli strumenti di sviluppo li può leggere (come L63 per la casa).
- **L233. I ricordi non capiscono il contenuto**: possono riproporre un giorno triste o una foto sbagliata; il filtro facoltativo sbaglia in
  entrambi i sensi; la difesa vera sono i comandi dell'utente.
- **L234. "Titolo e foto" sulla schermata di blocco** dipende anche dall'impostazione di Android: con "Mostra tutti i contenuti" titolo e foto si
  vedono a telefono bloccato.
- **L235. L'ora della notifica è indicativa**: WorkManager e il risparmio energetico la spostano fino a due ore; oltre tre ore quel giorno non
  arriva.
- **L236. Chi gestisce OpenFreeMap e chi lo ospita** vedono la zona guardata e l'IP: non sappiamo cosa registrano (servizio gratuito, nessun
  contratto); P1 riporta ciò che dichiarano.
- **L237. Scaricare una regione rivela quella regione** al server, una volta (per questo le regioni sono a catalogo fisso).
- **L238. OpenFreeMap può cambiare o sparire**: la mappa resta con la carta di base, le regioni e la cache; un fornitore nuovo sarebbe un host
  nuovo, con una scelta nuova dell'utente.
- **L239. MapLibre è codice nativo grande** che apre connessioni di rete: le facciamo passare tutte dal nostro cancello e lo verifichiamo con la
  cattura, ma un difetto della libreria che aprisse un socket fuori dal client si vedrebbe solo da una cattura, non dai test sui sorgenti.
- **L240. Sulla mappa si vede dove si scatta di più**, e quindi spesso dove si vive, a chi guarda lo schermo con l'app aperta (come la Libreria e
  le info delle foto).
- **L241. I ricordi mostrano anche foto dell'area comune** che i familiari vedono già; non mostrano mai aree private altrui.
- **L242. Dove i vettori non ci sono** (dispositivo non indicizzatore, ricerca non pronta) i doppioni si riconoscono peggio e il filtro di Q6 è
  spento.
- **L243. Media senza posizione** (o con la posizione solo in file di altri dispositivi senza il campo `geo` nell'indice) non sono sulla mappa.
- **L244. I ricordi non sono uguali su tutti i dispositivi**: ognuno li genera dalla sua Libreria; viaggiano solo "visto", "non mostrare" e le
  scelte.
- **L245. Qualunque app può aprire la pagina Ricordi** con l'azione della notifica (dietro il blocco app); non può aprire un ricordo preciso né
  passare dati.

### 6.3 Checklist della Fase 9 (sostituisce le tre voci di `sicurezza.md` §7 "Fase 9")
- [ ] Ricordi generati sul dispositivo, senza rete né NAS; nei dati personali solo id hash, giorni e scelte (P0, P6, F126).
- [ ] Nascosti mai in ricordi, presentazione, notifiche, mappa, Luoghi, mini-mappa (registro, F113, F116, F122).
- [ ] Notifiche: generiche di default sulla schermata di blocco (impostabile), mai nomi, al più una al giorno, tolte per un nascosto (F115–F117).
- [ ] Apertura dalla notifica con un'azione fissa e nessun dato; `EntryHardeningTest` esteso (F117).
- [ ] Mappa: carta di base offline; **zero traffico a mappa online spenta** (F119); accesa, solo `tiles.openfreemap.org` con percorsi e header
  dichiarati (F120); nota sulla privacy prima di accenderla (L13 riscritta).
- [ ] Regioni offline: scaricamento solo da un tocco, catalogo fisso, zone scaricate mai chieste online (F121).
- [ ] La casa non entra nella mappa: nessun segno, camera iniziale lontana, mini-mappa offline (P4).
- [ ] "Mostra meno", "Mai" e "Non mostrare" immediati e sincronizzati; lapidi alla cancellazione delle persone (F114, F130).
- [ ] Log, Diagnostica ed esportazione senza titoli, luoghi, date, nomi, coordinate, tessere (F126).
- [ ] Dipendenze nuove approvate e fissate con l'impronta; licenze e attribuzioni nell'app (P1, P2).

### 6.4 Permessi, componenti, dipendenze e host (proposta per `sicurezza.md` §4-bis e §8)
| Voce | Cosa cambia | Protezione | Perché |
|---|---|---|---|
| Permessi Android nuovi | **nessuno** (niente posizione del telefono, niente allarmi esatti); `POST_NOTIFICATIONS` c'è già | `verifyReleaseManifest` | D20, D35 |
| Componenti esportati o servizi nuovi | **nessuno**; con il piano B di D20 un ricevitore **non** esportato | `verifyReleaseManifest`, `check.ps1` | D20 |
| Ingresso di `MainActivity` | l'azione `OPEN_MEMORIES` riconosciuta, nessun dato | `EntryHardeningTest` | D17 |
| Dipendenze nuove | MapLibre Native Android con OkHttp, Okio e i transitivi di MapLibre (elenco esatto in P1) | impronte in `verification-metadata.xml`, solo in `feature-map`, dex controllato | D21 |
| Asset nuovi | stili, glifi Noto Sans (OFL), sprite, carta di base (Natural Earth, pubblico dominio; città GeoNames, CC BY 4.0), con licenze e `NOTICE` | impronte negli strumenti di P2 | D22, D25 |
| Host nuovi | **`tiles.openfreemap.org`**, solo con la mappa online accesa su quel dispositivo o per scaricare una regione confermata | `TilePolicy`, intercettore, cattura | D22–D24 |
| Uso delle notifiche | canale nuovo `memories` | impostazioni di Android | D15 |

## 7. Rischi e piani B

| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| MapLibre non passa le verifiche di P1 (16 KB, componenti, richieste fuori dal client, telemetria) | niente mappa con le strade | P1 è uno spike prima di tutto, con le dieci verifiche scritte | un'altra versione stabile; altrimenti mappa **solo offline** (carta di base e regioni) con MapLibre e rete spenta per costruzione, o le bolle sopra la sola carta di base disegnata da noi in Compose (Natural Earth), senza strade |
| OpenFreeMap non ammette lo scaricamento di regioni, cambia gli URL o sparisce | regioni offline impossibili o mappa online spenta | URL stabile riscritto dall'intercettore; regioni a catalogo; L238 dichiarato | Q2 (b) (regioni dal PC, una volta, sul NAS); un altro fornitore senza account sarebbe un host nuovo con una nuova scelta dell'utente |
| APK più pesante (+10–15 MB [stima]) | aggiornamenti più lunghi | solo l'ABI `arm64-v8a` dove già si fa così; carta di base semplificata | glifi ridotti agli intervalli usati; carta di base a 1:110m (~1 MB) |
| MapLibre consuma memoria sul Tab S9 o va in crash nativo | app chiusa durante la mappa | rilascio con `onTrimMemory`, una mappa sola alla volta, `SurfaceView` | mini-mappa con `MapSnapshotter` disattivabile; la mappa in un processo separato (un attributo del manifest, da approvare) |
| 100 mila punti non fluidi | soglie del §5.4 mancate | indice a prefissi, bolle al più 60, ricalcolo a camera ferma | livelli più radi (un livello intero invece di mezzo), al più 40 bolle |
| Notifiche spesso in ritardo (Doze, Samsung) | ricordo fuori orario o saltato | finestra di 2 ore, salto oltre 3 ore, misura in F115 | `AlarmManager.setWindow` non esatto con un ricevitore non esportato (D20) |
| Ricordi poco belli sui dati veri (F125 sotto le misure) | funzione poco usata | segnali dell'utente, doppioni, varietà, calibrazione in P11 | meno ricordi (solo viaggi e "anni fa"), soglie più alte, nessuna notifica finché le misure non tornano |
| Ricordi tristi nonostante tutto | dolore | comandi immediati e sincronizzati; filtro di Q6 | un interruttore "Ricordi" per spegnere tutta la funzione (c'è già: la sezione si nasconde e le notifiche si spengono); "Mai" più largo (un anno intero con un tocco) |
| P13 della Fase 8 in ritardo | niente ricordi con le persone | `MemoryPeople` null; il resto funziona | consegnare i ricordi `PERSON` con la chiusura della Fase 8 |
| Vettori non legati in produzione per i doppioni (`ShotVectorSource`) | doppioni dei ricordi solo con la firma | `MemoryVectors` legata da P13 direttamente su `SearchData.vectors` | ripiego sulla firma e sulla finestra di 3 minuti (E-MEM16) |
| Scenari di benchmark scritti solo nei documenti (come `album` e `volti`) | soglie della fase non controllate | P12 ha come "Fatto quando" scenari che **girano**; la chiusura li richiede | la chiusura non si fa senza i numeri di F123 e F124 |
| Il registro dei dati personali cresce (una voce `seen` per ricordo visto, ~600 all'anno) | giri dei dati personali più lenti | una voce per ricordo, scritta una volta; tetti dei dati personali | dopo 13 mesi le voci `seen` si possono togliere con una lapide in un giro notturno (pacchetto dopo, se misurato) |
| `MediaCatalog.setPlacesDataset` non collegato | `place_id` vecchi dopo un dataset nuovo (titoli e Luoghi) | i titoli dei ricordi si fissano alla pubblicazione | compito separato da segnalare (§0, punto 13) |
| Il filone grafico rifà presentazione e bolle | lavoro doppio | stati e contratti (§1.5), solo token di movimento | — |
| `engine/` vuoto nel worktree | pacchetti con UI che non compilano | i pacchetti con UI lavorano in un worktree con il sottomodulo inizializzato | — |
| Crediti | costo alto | opus su 2 pacchetti su 14 | P1 sonnet con una revisione opus del cancello prima del merge |

### File cruciali
- `core-personal/.../contract/Personal.kt` (`PersonalKind.MEMORY`, `PersonalFields.MEMORY_SEEN`), `settings/PersonalCollections.kt`
  (`PersonalMemories`), `settings/SyncedCollectionsSettings.kt` e `SyncedMotionSettings.kt` (modelli delle impostazioni sincronizzate),
  `sync/NasPersonalSync.kt`.
- `core-albums/.../contract/Trips.kt` (`Trips`, `TripProposal`, `TripId`, `HomeClearance`, `TripRules`), `trips/TripStore.kt`, `trips/TripReader`
  (interno), `src/test/.../trips/TripPrivacyTest.kt`.
- `core-library/.../contract/Visibility.kt`, `contract/Timeline.kt`, `contract/Viewer.kt`, `index/IndexOrderedTimelineView`, `duplicates/SimilarShots.kt`,
  `duplicates/ShotAffinities.kt`, `testing/InMemoryTimeline.kt` (`SyntheticLibrary`).
- `core-places/.../contract/Places.kt` (`PlaceNames`, `ReverseGeocoder`), `dataset/PlacesDatasetPin.kt`.
- `core-database/.../entity/MediaEntities.kt` (`version_metadata`), `entity/BackupEntities.kt` (`metadata_correction`), `dao/AlbumQueries.kt`
  (modello della query a pagine), `MediaCatalog.kt`.
- `core-search/.../contract/Search.kt` (`SearchData.vectors`), `engine/QueryEmbedding.kt`, `engine/ContentScorer.kt` (soglia relativa),
  `engine/DocumentFlags.kt`.
- `core-faces/.../contract/People.kt` (`People`, `PeopleIndex`), `people/PeopleVisibility.kt`, `contract/FacesState.kt`; `core-search/.../contract/People.kt`
  (`PeopleLookup`).
- `core-common/.../testing/NoNetworkSources.kt`, `src/test/.../architecture/Phase6ArchitectureTest.kt` (regola 5), `Phase7ArchitectureTest.kt`,
  `featureflags/FeatureFlags.kt`, `diagnostics/Diagnostics.kt`.
- `core-models/.../download/https/` (`HttpsModelFetcher`, `RedirectPolicy`: il modello del cancello), `contract/ModelCatalog.kt` (`ModelHosts`).
- `feature-library/.../collections/CollectionSections.kt`, `CollectionsLayout.kt`; `feature-viewer/.../info/InfoBuilder.kt`, `ViewerActions.kt`
  (`mapsIntent`), `ViewerServices.kt`.
- `app/.../notify/AppNotifications.kt`, `MainActivity.kt`, `main/PermissionAsks.kt`, `work/GalleryWork.kt` (`LibraryMaintenanceWorker`),
  `navigation/CollectionsSectionsWiring.kt`, `navigation/CollectionEntryRouting.kt`, `di/CollectionsModule.kt`, `albums/ViewerAlbums.kt`,
  `graph/LocalData.kt`, `src/test/.../hidden/HiddenSurfaces.kt`.
- `gradle/verify-release-manifest.gradle`, `gradle/verification-metadata.xml`, `versions-app.gradle`, `tools/check.ps1`, `benchmark/soglie.json`,
  `app/src/benchmarkRelease/.../bench/BenchSeeder.kt`.

## 8. Cose che richiedono l'utente

| Cosa | Perché | Nel frattempo |
|---|---|---|
| Rispondere a Q1–Q9 | cambiano P1, P4, P5, P7, P9, P10, P11 e la chiusura | valgono le raccomandazioni; P0 parte |
| **Approvare gli scaricamenti** (sotto, Q8) | servono a P1 e P2 | ricordi interi, indice della mappa, politica di rete sulla JVM, schermate sopra una superficie finta |
| La valutazione sui dati veri (F125, ~25 minuti, Q7) | la misura che conta per la qualità dei ricordi | Libreria sintetica (§5.3) |
| Il momento breve con il tablet (~15 minuti, insieme a quelli in sospeso delle Fasi 6, 7 e 8) | misure del Tab S9, notifiche del profilo (F128) | prove sull'S25 e sull'emulatore |
| PCAPdroid sull'S25 (se non c'è già: è nella lista della Fase 8) | F119 e F120 con la cattura del telefono | contatori dell'intercettore e `netstats` sull'emulatore |
| Revisione degli screenshot (Ricordi, pagina, presentazione, fogli, impostazioni, mappa, bolle, regioni, notifica) | gate grafico | generati in anticipo |

**Scaricamenti da approvare** (tutti sul PC, nessuno sul telefono; **niente è stato scaricato per questo documento**; versioni e dimensioni
esatte le fissano P1 e P2 con le impronte, e se cambiano molto si richiede)
| File | Fonte | Versione | Licenza | Dimensione [stima] | A cosa serve |
|---|---|---|---|---|---|
| MapLibre Native Android (`org.maplibre.gl:android-sdk`) | Maven Central (`repo.maven.apache.org`) | l'ultima stabile che passa le verifiche di P1 (serie 11 o successiva: da verificare quale è la più recente oggi) | BSD-2-Clause | AAR 15–25 MB con tutte le ABI; nell'APK +5–9 MB | disegnare la mappa |
| Le dipendenze di MapLibre: OkHttp 4.x, Okio 3.x, le librerie geojson e dei gesti di MapLibre, eventuali altre (es. Timber) | Maven Central | quelle che la versione scelta chiede (elenco esatto in P1, con le impronte in `verification-metadata.xml`) | Apache-2.0, BSD, MIT [da verificare una per una] | 1–3 MB | rete e supporto di MapLibre |
| Stili Positron e Dark (o Fiord) di OpenFreeMap, con gli sprite | GitHub, repository degli stili di OpenFreeMap (`hyperknot/openfreemap-styles`) o le copie in `tiles.openfreemap.org/styles/` | un commit fissato | da verificare (il codice degli stili OpenMapTiles è BSD-3, il disegno CC-BY 4.0) | < 1 MB | stile adattato dentro l'app |
| Glifi Noto Sans Regular e Bold (PBF di MapLibre) | GitHub `openmaptiles/fonts` (release) o generati dai TTF di Noto (`github.com/notofonts`) con lo strumento di MapLibre | una release fissata | SIL OFL 1.1 | pacchetto fino a ~70 MB, nell'app 0,5–1 MB | etichette della mappa |
| Natural Earth 1:50m (terre, coste, laghi, confini dei paesi) e 1:10m (regioni e province) | `naturalearthdata.com` o GitHub `nvkelso/natural-earth-vector` | 5.1.x | pubblico dominio | ~5 MB + ~40 MB | carta di base offline |
| GeoNames `cities1000` | **già sul PC** (`%USERPROFILE%\.mycloudgallery\geonames\`, Fase 3) | quello di `places.mcgp1` (20261012) | CC BY 4.0 | nessuno scaricamento nuovo | città della carta di base |
| Prove della mappa online (F120, F121) | `tiles.openfreemap.org`, dal telefono di prova sul Wi-Fi di casa | — | dati OpenStreetMap, ODbL | qualche decina di MB | provare il cancello e le regioni |
| (solo con Q3 (c)) due o tre brani musicali | un archivio di musica CC0, scelto in P9 | — | CC0 | 3–5 MB | musica della presentazione |
| (solo con Q2 (b)) le tessere delle regioni scelte | `tiles.openfreemap.org`, dal PC | — | ODbL | 150–400 MB per regione | regioni messe sul NAS |

**Host che riceverebbero traffico dall'app** (in esercizio): **solo `tiles.openfreemap.org`** (e il DNS della rete in uso), solo con la mappa
online accesa su quel dispositivo o per scaricare una regione confermata. Restano quelli di prima: il NAS (LAN o WireGuard), gli host dei
modelli della Fase 6 (`huggingface.co`) e, solo con la lettura del testo accesa, Google per ML Kit (F6-Q4). **Durante lo sviluppo**, dal PC:
Maven Central, GitHub, `naturalearthdata.com`.

**Misure da fare dopo il consenso** (P1, P2, P5): le dieci verifiche del §2.7; peso dell'APK prima e dopo; cattura del traffico a mappa spenta
e accesa; dimensione reale di tre regioni di prova; tempi e memoria di MapLibre su S25 e Tab S9.

## 9. Domande per l'utente

Poche, solo quelle che decidono cosa vedi, cosa esce dal telefono e cosa entra nell'app. Se non rispondi, vale la raccomandazione.

| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | La mappa può mostrare strade e nomi presi da Internet, da un servizio gratuito senza account (OpenFreeMap). In cambio quel servizio vede **quale zona guardi** e da quale connessione. Senza, la mappa è più semplice: paesi, regioni, città principali. Come la vuoi? | (a) Spenta all'inizio: ognuno la accende sul suo telefono o tablet, dopo una pagina che spiega cosa esce. (b) Mai: solo la mappa semplice dentro l'app. (c) Accesa per tutti fin dall'inizio. | **(a)**: la decide chi tiene in mano il telefono, sapendo cosa succede. |
| Q2 | Puoi scaricare una zona (una regione, o i dintorni di un posto) per vederla con le strade anche senza Internet, e senza che il servizio sappia più quando la guardi. Come le scarichiamo? | (a) Dal telefono, sul Wi-Fi, scegliendo da un elenco; il servizio vede quale zona scarichi, una volta. (b) Le preparo io dal PC e le metto sul NAS: i telefoni le prendono da lì e non parlano mai con il servizio. Più lavoro e una cartella in più sul NAS. (c) Niente zone senza Internet. | **(a)**: semplice, e la zona si rivela una volta sola. |
| Q3 | La presentazione dei ricordi può avere una musica. Quale? | (a) Nessuna musica. (b) Una tua canzone: la scegli una volta fra i file del telefono, niente da scaricare. (c) Qualche musica libera dentro l'app (da scaricare una volta, gratuita e senza diritti; l'app cresce di qualche MB). | **(b)**: è tua, non serve scaricare niente; finché non la scegli la presentazione è muta. |
| Q4 | Ogni quanto vuoi una notifica con un ricordo? Arriva all'ora che scegli (all'inizio le 19). | (a) Al massimo una al giorno, solo quando c'è un bel ricordo. (b) Al massimo 2 o 3 a settimana, nei giorni migliori. (c) Solo per gli anniversari dei viaggi e delle giornate speciali. (d) Spente all'inizio: le accendi tu. | **(b)**: abbastanza per farti sorridere, non tanto da diventare un rumore. |
| Q5 | Cosa si vede della notifica **a telefono bloccato**? | (a) Solo "Un ricordo per te", niente foto né titolo. (b) Il titolo e una foto (mai nomi di persone). | **(a)**: chi prende in mano il telefono non vede niente; il ricordo lo apri tu. |
| Q6 | L'app non sa cosa c'è nelle foto. Puoi sempre dire "Non mostrare questo ricordo", "Mostra meno" una persona, "Non ricordarmi questo periodo". Vuoi anche un aiuto automatico contro i ricordi tristi? | (a) No, bastano questi comandi. (b) Sì: l'app prova da sola a lasciare fuori le foto che sembrano di ospedali, funerali, cimiteri. Può sbagliare, ma al massimo toglie qualche foto; tutto resta sul telefono e non viene salvato. | **(b)**: un errore costa una foto in meno, mai un dispiacere in più. |
| Q7 | Per sapere se i ricordi sono belli servono circa **25 minuti tuoi**, sul telefono: guardi una cinquantina di ricordi e per ognuno tocchi "bello", "così così" o "da non mostrare". Tutto resta sul telefono. Lo facciamo? | (a) Sì, quando vuoi, dopo la prima tappa. (b) No: solo prove con foto finte. | **(a)**: è l'unico modo di sapere se funzionano sulle tue foto. |
| Q8 | Per la mappa servono una libreria gratuita (MapLibre) e alcuni file liberi (stile, caratteri, confini dei paesi): l'elenco è al §8. Nessuno chiede un account. | (a) Approvo tutto l'elenco, comprese le prove della mappa online sul telefono di prova. (b) Approvo tutto tranne le prove online: si prova solo la mappa senza Internet. (c) Niente mappa per ora: solo ricordi e notifiche. | **(a)**: senza le prove online non sapremmo cosa esce davvero. |
| Q9 | Se usi Persone, l'app può farti ricordi come "Con Anna": solo persone a cui hai dato un nome, mai quelle nascoste, mai con il nome nella notifica. Li vuoi? | (a) Sì. (b) No: solo date, viaggi e mesi. | **(a)**: sono spesso i ricordi più belli, e li controlli tu con "Mostra meno". |

## 10. Fonti

**Nessuna fonte nuova consultata per questo documento** (niente rete, niente scaricamenti, per il brief). Dati e scelte vengono da:
- il codice del commit `43a99bae` (elenco al §0 e al §7) e i documenti del progetto citati nell'intestazione;
- `docs/fase3-progetto.md` D18 (Natural Earth come alternativa per una carta offline), `docs/luoghi.md` (GeoNames, licenza e attribuzione),
  `docs/sicurezza.md` L13 (OpenFreeMap senza account), `docs/android.md` Tema 1 (quote, Doze, WorkManager, allarmi);
- conoscenze generali, **da verificare in P1 e P2 sulle fonti prima di usarle**: la versione stabile più recente di MapLibre Native Android,
  l'allineamento a 16 KB delle sue librerie native, l'assenza di telemetria, l'API `HttpRequestUtil.setOkHttpClient`, `MapLibre.setConnected`,
  `OfflineManager` e il percorso del suo database, il supporto di `asset://` per stile, glifi, sprite e GeoJSON, il `User-Agent` predefinito;
  per OpenFreeMap: host, forma del TileJSON e degli URL versionati, livello massimo delle tessere (z14), rotazione delle versioni, condizioni
  d'uso (uso nelle app, scaricamento per l'offline), politica dei log, chi ospita il servizio; le licenze esatte degli stili Positron e Dark e
  degli sprite; la release di Natural Earth e dei glifi Noto Sans.
- Pagine da leggere in P1 (dal PC, con l'approvazione): `https://openfreemap.org` (documentazione e condizioni), `https://github.com/maplibre/maplibre-native`
  (note di rilascio Android), `https://maplibre.org/maplibre-native/android/api/`, `https://www.naturalearthdata.com/about/terms-of-use/`,
  `https://github.com/openmaptiles/fonts`.

<!-- fine del documento -->

## 11. Risposte del 27/10 e cosa cambiano

| # | Risposta | Cosa cambia |
|---|---|---|
| Q1 | (c) **Accesa per tutti fin dall'inizio.** | La mappa online (OpenFreeMap) è accesa di default su ogni dispositivo, senza pagina di consenso; resta un interruttore per dispositivo in Impostazioni per spegnerla, con una nota breve su cosa esce (la zona guardata, mai le foto). Il cancello di rete resta com'è (un solo host, nessun cookie, zone già scaricate mai chieste online). Restano anche le altre protezioni: la "casa" non si mostra mai e la mini-mappa delle info non va in rete. |
| Q2 | (a) **Dal telefono**, sul Wi-Fi, da un elenco. | Nessun pacchetto P14. |
| Q3 | **Di base nessuna musica.** Dalle Impostazioni si può scegliere una propria canzone **oppure installare un pacchetto di musica libera**, che poi si usa da solo; **la musica libera è l'opzione preferita**. | La presentazione parte muta. Il pacchetto di musica libera (brani CC0 o di pubblico dominio, qualche MB) si installa a richiesta, come i modelli della Fase 6: si scarica una volta per la famiglia e poi si prende dal NAS; impronte fissate nell'app. **Le fonti e i brani precisi si fanno approvare all'utente prima di scaricarli** (pacchetto dei ricordi o delle impostazioni, come deciso al §4). La canzone propria resta un file del telefono scelto con il selettore di sistema, mai copiato sul NAS. |
| Q4 | **Di base 2–3 notifiche a settimana**; nei giorni più speciali (anniversari dei viaggi, giornate importanti) **fino a una al giorno**. | Il tetto settimanale di base è 3, con le eccezioni dei giorni speciali che possono arrivare a una al giorno; mai più di una al giorno. |
| Q5 | (a) **Solo "Un ricordo per te"** a telefono bloccato. | Visibilità privata della notifica; il contenuto si vede solo dopo lo sblocco. |
| Q6 | (a) **Solo i pulsanti** ("Non mostrare", "Mostra meno", "Non ricordarmi questo periodo"). | Nessun filtro automatico dei ricordi tristi. |
| Q7 | Non chiesta esplicitamente: vale la raccomandazione **(a)**, quando l'utente vorrà. | Come F105 della Fase 8, la misura sui dati veri non blocca la chiusura del codice della fase. |
| Q8 | (a) **Approvo tutto l'elenco**, comprese le prove della mappa online sul telefono di prova. | I download del §8 sono approvati; la musica libera ha la sua approvazione a parte (Q3). |
| Q9 | (a) **Sì**, ricordi con le persone. | Solo persone con un nome, mai nascoste, mai il nome nella notifica. |