# Fase 7 di MyCloud Gallery: progetto tecnico (album, album smart, condivisione, Raccolte)

> Base per i brief dei sub-agent. Progetto del 21/10/2026. Fonti:
> - piano: FASE 7 (7.1–7.5), §1 (glossario: media con identità stabile, copia privata, dati personali, area comune), §2 ("Libreria
>   e famiglia", "Album", "Extra": Nascosti con biometria), §3 (proposte 5, 6, 7), §4 (robustezza con 500 interruzioni; "nessuna
>   azione distruttiva parte da dati letti dal NAS"), §6 (E-AL1–8, E-SH1–11, E-H1–4, E-D8, E-L3, E-B13, E-B14, E-S11), §7
>   (contromisure 4, 7, 8; limiti dichiarati);
> - i progetti delle fasi precedenti: `docs/fase4-progetto.md` (D4–D11, D24–D26, D31–D33, §2.1, §2.7, §2.9), `docs/fase5-progetto.md`
>   (D2, D8, D9, D14, D21, D24, D35, D36, §2.3, §2.10, §2.12, L44, Q4), `docs/fase6-progetto.md` (§0, D26–D31, D48, §2.9), le
>   decisioni R11 «Lascia i nascosti dove sono» e R15 di `docs/decisioni.md`;
> - `docs/formato-nas.md` (§2.2, §2.3, §4.6, §5.1, §5.5, §5.5-bis, §5.7, §5.8, §7, §8.1, §8.4, §9, §10, §11.2), `docs/modello-dati.md`
>   (§2, §4, §5, §6, §9, §11, §12.5, §12.6), `docs/sicurezza.md` (S5, S9, S11, C10, C14, C19, L2, L26, L27, L29, L44, §7 "Fase 7"),
>   `docs/grafica-progetto.md` (§12), `docs/luoghi.md`, `docs/review/4b-fase4b.md` (R8, R11, I3, I8), `docs/review/1.4-core-trust.md`
>   (B1, B6), `docs/review/5b-fase5b.md` (R16, R17), `docs/prove-campo-esiti.md` (D-3, blocco B, F29);
> - il **codice** da cui la fase parte (verificato il 21/10 sul commit `89571d9`): `core-personal` (`Personal.kt`,
>   `PersonalCollections.kt`, `PersonalProjection.kt`, `PersonalCodec.kt`, `NasPersonalSync.kt`), `core-nasops` (`Operations.kt`,
>   `Request.kt`, `Diary.kt`, `Confirmation.kt`, `NasOpsEngine.kt`, `EnginePorts.kt`, `TrashAndSpace.kt`), `core-trust`
>   (`Envelope.kt`, `NasLayout.kt`, `DeviceHead.kt`, `IndexSegment.kt`, `TrustFormat.kt`, `Keys.kt`, `FamilyRegistry.kt`,
>   `TextRules.kt`), `core-index` (`IdentityRules.kt`, `Publishing.kt`), `core-database` v5 (`DerivedEntities.kt`, `LibraryDaos.kt`,
>   `BackupDaos.kt`, `MediaCatalog.kt`, `GalleryMigrations.kt`), `core-library` (`Timeline.kt`, `similar/`), `core-backup`
>   (`BackupDiary.kt`: `INCOMING`; guardiano), `core-search` (`Search.kt`, `Query.kt`, `ItalianQueryParser`), `core-places`
>   (`Places.kt`), `core-security` (`AppLockController`, `BiometricPromptAuthenticator`, `ScreenProtection`), `feature-library`
>   (`collections/`, `hidden/`, `trash/`), `core-designsystem` (`GalleryBadge.Shared`, `GalleryShareSheetContent`),
>   `app/.../main/MainScaffold.kt`, `app/.../nas/NasOrganizer.kt`, `CatalogNasPorts.kt`, `FiveBNavigation.kt`, `FeatureFlags.kt`.
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F6-Dn`, `F5-Dn`, `F4-Dn` = decisioni delle Fasi 6, 5 e 4; "R11 della 4B" =
> voce di revisione; `Fn` = prova sul campo (la Fase 6 arriva a F71, qui **F72–F93**); `Qn` = domanda del §9; `Pn` = pacchetto del
> §4.4; `Ln` = limite di `docs/sicurezza.md` (la Fase 6 arriva a L56, qui **L57–L66**); `Cn` = contromisura (qui C24–C27).
>
> Contesto:
> - **La Fase 5 è chiusa nel codice** (5A e 5B revisionate e corrette fino al 21/10). **In sospeso**: alcune prove sul campo con le
>   app vere (blocco B di `docs/prove-campo-esiti.md`: F29, F17, F27, F31-bis, F45/F14) e il primo backup sulle share vere (Q3
>   della 5B: F5-D12 "backup solo sulle share di prova" resta acceso; F32/F44). **La Fase 7 non dipende da questi punti**, con una
>   eccezione dichiarata: lo spostamento in condivisione usa lo stesso cammino di F29 (spostamento nell'area comune e guardiano),
>   quindi F29 va fatta **prima** di F79 (§5.6), nella stessa sessione se possibile.
> - **La Fase 6 (Ricerca) è in corso**: fatti P0 (contratti, null-object, fake), P1 (database v5), P2 (formato `ai`), P3 (modelli),
>   P7 (analizzatore delle query: `ItalianQueryParser` vero) e P14 (foto in movimento); **mancano** P4 (motori), P5 (calcolo AI),
>   P6 (motore di ricerca), P8–P13. **La Fase 7 non la aspetta**: dove servono la ricerca o "Trova simili" si appoggia ai
>   **contratti** già scritti da P0 (`SearchEngine`, `SearchRequest`, `SearchChip`, `QueryParser`, `SearchCoverage`) e al loro
>   esecutore di riferimento (`InMemorySearchEngine`), e funziona **anche senza vettori** (date, luoghi, GPS, dispositivo). Cosa le
>   serve da P6 della Fase 6 è al §2.9.
> - **L'indice condiviso sulle share vere è ancora "solo locale"** (F4-D31 acceso, F64 della Fase 6 non fatta). La Fase 7 **non ne
>   dipende**: gli elementi di un album condiviso si riconoscono dal **catalogo di chi legge** (che scansiona da sé l'area comune),
>   non dall'indice (D9). Le definizioni degli album condivisi sono però file nuovi in `.mycloudgallery/` dell'area comune: hanno
>   una recinzione loro, `sharedAlbumsTestSharesOnly`, che si spegne solo con il consenso (Q7, D30).
> - **In parallelo**: il filone grafico (G3). La Fase 7 consegna stati e contratti con composable funzionali fatte solo con i
>   componenti G2 esistenti (D32), come le Fasi 4–6.

## 0. Sintesi

**Che cosa consegna la Fase 7**
- **Album personali completi** (7.1): creazione, aggiunta e rimozione, **ordinamento** (per data o a mano) e **copertina** scelti,
  sincronizzati fra i dispositivi dell'account come i preferiti (dati personali della 4.7, area privata), senza nessun file
  spostato (D3–D5, D21).
- **Nascosti e Utility** (7.1): i Nascosti già fatti nella Fase 3 diventano una garanzia su **ogni** superficie, compresi album,
  album smart, viaggi, copertine, conteggi e un widget di prova (D22, D23); **Utility** con Screenshot, Video, Modificate,
  WhatsApp, **Doppioni** (identici e simili, con una pulizia guidata che passa sempre dal cestino) ed Eliminati di recente (cestino
  di Android e del NAS insieme) (D24–D27).
- **Album smart** (7.2): il nome ("Oslo 2026") diventa regole modificabili (periodo, luogo con raggio, descrizione, foto
  d'esempio, sorgenti, tipi); i **sicuri** entrano da soli, i **dubbi** vanno in "Suggerite"; esclusioni che restano;
  aggiornamento con i media nuovi; "Congela" (D13–D17).
- **Viaggi ed eventi** (7.3): la "casa" dedotta **solo in locale** dalle proprie foto con GPS; i giorni lontani da casa e le giornate
  con molte foto in poche ore diventano proposte in Raccolte, che con un tocco diventano album smart (D18–D20).
- **Album condivisi** (7.4): definizione **firmata** nell'area comune (un file per album e per dispositivo, D6–D9), **permessi
  imposti dall'app** a tre livelli con una tabella per ogni operazione (D10, D11), **condivisione = spostamento nell'area comune**
  che parte **solo** da un'azione locale confermata, con diario, verifica, ripresa, identità stabile e "al sicuro" garantito
  (D12, D28, D29), "Smetti di condividere" con "Riporta nelle mie cartelle" (D31).
- **Raccolte** (7.5): sezioni Album, Album smart, Viaggi, Cartelle NAS, Telefono, Utility, Nascosti (Persone e Ricordi con le Fasi 8
  e 9), **ordinabili e nascondibili**, scelta sincronizzata fra i propri dispositivi (D33).

**Moduli nuovi**
- `core-albums`: modello degli album (personali, smart, condivisi), motore delle regole degli album smart, viaggi ed eventi con la
  "casa" locale, replica e fusione degli album condivisi con il controllo dei permessi, orchestratore della condivisione (diario
  proprio). **Non vede** `core-nasops`, `core-backup`, `core-nas` né la rete: sposta, carica e pubblica attraverso porte sue, legate
  da `:app` (§1.1).
- `feature-albums`: elenco e pagina degli album, editor delle regole, "Suggerite", fogli di condivisione e permessi, proposte di
  viaggio.

**Aggiunte ai moduli esistenti**
- `core-trust`: contenuto dei file `albums/` (testa di tipo 6 già riservata, segmenti nuovi), codec ostile, **versione 3** del
  formato solo per i file `albums/` (D6, D7);
- `core-personal`: campi nuovi **dentro** i tipi esistenti `ALBUM`, `SETTING` e `MEDIA_FLAG` (nessun tipo nuovo: un tipo nuovo farebbe
  scartare i segmenti alle app vecchie, D4); impronta sugli elementi degli album; proiezione degli album sul catalogo (D5);
- `core-database` (schema **v6**, D34), `core-library` (filtro `EDITED`, viste per album, sorgente del widget di prova), `core-nasops`
  (porta di completamento degli spostamenti, D29), `core-backup` (destinazione "cartella dell'album" per `INCOMING`, avvio del
  guardiano dopo una condivisione, D28), `core-security` (nessuna API nuova), `feature-library` (Raccolte, Utility, Doppioni,
  Eliminati di recente), `feature-viewer` (voci ⋮ "Aggiungi ad album", "Rimuovi dall'album"), `feature-integrations` ("Aggiungi
  ad album" dalla condivisione in arrivo, promesso da F5-D24), `feature-diagnostics`, `:app`.

**Quattordici pacchetti** (§4.4). Opus per i tre fondanti o difficili: **P0** (fondamenta, contratti, recinti), **P2** (formato
firmato degli album condivisi e permessi), **P4** (condivisione e spostamento); sonnet per gli altri undici.

**Tappe**
- **M1** (7.1): sull'S25 (debug, account `test`) album personali con ordine e copertina, Utility completa (Doppioni compresi),
  Eliminati di recente unificati, nessun nascosto su nessuna superficie (test automatico e F73).
- **M2** (7.4): album condiviso creato sull'S25 nelle share di prova, visto dal profilo `test2` del tablet con i tre livelli di
  permesso rispettati; banco delle **500 interruzioni** sullo spostamento passato (JVM, simulatore SMB, NAS vero dal PC, S25).
- **M3** (7.2, 7.3, 7.5): album smart e viaggi sui dati veri dell'utente (≥ 98% dei sicuri corretti e ≥ 90% delle foto del
  viaggio inserite o suggerite su 3 viaggi; viaggi degli ultimi 2 anni proposti); Raccolte ordinabili e nascondibili.
- Chiusura: revisione di sicurezza e verifica funzionale; con il consenso (Q7) gli album condivisi sulle share vere.

**Test senza dispositivo**: NAS finto e simulatore SMB delle Fasi 4 e 5 (con le vere `SmbNasFileOps`), famiglia finta con più
account e dispositivi (registro, revoche), Libreria sintetica da 100 mila elementi con viaggi sintetici (GPS, fusi, dispositivi),
motore di ricerca di riferimento (`InMemorySearchEngine`) con i motori finti della Fase 6; fake fedeli con test di parità (§5.2).

**Cosa le fasi precedenti hanno lasciato pronto** (verificato nel codice)
- **Album nei dati personali**: `PersonalKind.ALBUM` con i campi `definition`, `order`, `cover`, `item:<id>`
  (`core-personal/.../contract/Personal.kt:29,49-62`) e `PersonalAlbums` (`settings/PersonalCollections.kt:32`: `define`, `delete`,
  `setOrder`, `setCover`, `addItem`, `removeItem`, `albums()`), già sincronizzati e provati con il giro dei dati personali (teste
  firmate nell'area privata, fusione per `(atNas, autore)`).
- **Tabelle** `album` e `album_item` (`core-database/.../entity/DerivedEntities.kt:127-175`, `AlbumDao` in `LibraryDaos.kt:170-191`);
  le unioni d'identità spostano elementi e copertine sul media sopravvissuto (`MergeDao.copyAlbumItems`, `repointAlbumCovers`;
  `MediaCatalog.kt:1310, 2278, 2659`); `MergeReferencesTest` le copre.
- **Nel formato**: `DocType.SHARED_ALBUM_HEAD(6)` con tetto 256 KiB (`Envelope.kt:34-35`, `TrustFormat.kt:45`), il percorso
  `albums/<album>/<dispositivo>/head/<versione>` (`NasLayout.kt:92-94`), l'id d'album a 128 bit (`Keys.kt:221`), le regole sul nome
  (`TextRules.kt:58`), `AlbumId` in `core-model` (`MediaId.kt:86-97`).
- **Nascosti**: `media_personal.hidden`, filtro SQL nella timeline, `HiddenAccessGate` (credenziale di sistema a ogni ingresso, un
  minuto di tolleranza condiviso con gli Eliminati di recente, orologio monotono, fail closed), `ScreenProtection`/`RecentsPrivacy`,
  nascosto contagioso nei gruppi (F5-D36), R11 «Lascia i nascosti dove sono» (`OpRequest.Move.leaveBehind`), F6-D29 sulla ricerca.
- **Spostamenti tra share**: `ItemFlow.MOVE_ACROSS_SHARES` (copia lato server → verifica SHA-256 → pubblicazione esclusiva →
  sorgente registrata → sorgente nel cestino per 30 giorni), conferme con `UserConfirmation` dal costruttore `internal`, controllo
  dello spazio (dimensione + 1 GiB), file bloccati con attese fino a 24 ore, `FROZEN` e salto dell'ora come pause; banco F16-PC
  passato dal PC con 400 interruzioni e 0 violazioni.
- **Caricamento diretto nell'area comune** (`UploadKind.INCOMING`, 5B P14: `core-backup/.../contract/BackupDiary.kt:37,138`) e
  **guardiano** delle copie private (F5-D9).
- **Copie simili** (F5-D36: firma visiva OKLab, gruppi `similar_copies`, "Separa" e "Mostra come principale" sincronizzati) e
  gruppi `PROBABLE_DUPLICATE` con "da verificare".
- **Raccolte** minime (`CollectionsTab`, `CollectionKind`: Preferiti, Video, Screenshot, WhatsApp, Nascosti, Eliminati di recente;
  gruppi "Dal telefono", "Da tenere al sicuro", "Sul NAS"; Utility con la sola voce "Libera spazio") e la scheda Raccolte
  (`GalleryTab.Collections`).
- **Pezzi grafici**: `GalleryBadge.Shared` ("album condiviso o area comune"), `GalleryShareSheetContent(albumName)`, la stringa
  "I permessi dell'album li fa rispettare l'app, non il NAS" (`core-designsystem/src/main/res/values/strings.xml:93`).
- **Fase 6**: `ItalianQueryParser` (date, feste, stagioni, luoghi della Libreria, tipi) e i contratti `SearchEngine.search/similar`
  con le due classi "sicuri" e "meno probabili"; `ReverseGeocoder.place(id)` e `PlaceNames.summary` di `core-places`.

**Cosa le fasi precedenti promettevano per la Fase 7 e nel codice non c'è** (la Fase 7 lo costruisce o lo cambia, §0.3)
1. **Gli album non arrivano mai al catalogo**: `CatalogPersonalProjection` proietta solo preferiti e nascosti
   (`PersonalProjection.kt:17-18,28`); `PersonalAlbums` non ha chiamanti fuori dai test; nessuna schermata (D5).
2. **Gli elementi degli album non portano l'impronta**: `addItem` scrive `item:<id>` con la sola posizione; se due dispositivi
   hanno id diversi per lo stesso media (prima della convergenza di F4-D7) l'elemento si perde. I flag dei media invece la portano
   e si ritrovano per impronta (D4).
3. **La testa di tipo 6 è solo una busta** (`formato-nas.md` §5.8): niente contenuto, niente segmenti, niente regole di chi legge,
   niente permessi; nessun codice la legge o la scrive (D6–D10).
4. **"Aggiungi ad album" dalla condivisione in arrivo** (F5-D24) non c'è (nessuna occorrenza nel codice) (D35).
5. **Utility** ha solo "Libera spazio" (`FiveBNavigation.kt:21-32`); **nessun filtro "Modificate"** (`LibraryFilter` non ha
   `EDITED`, c'è solo il bit `CellFlags.EDITED`); nessuna pagina **Doppioni**; nessun rilevatore di copie doppie **dello stesso
   lato** (`modello-dati.md` §9); l'hash completo dei video del NAS "per tutti, di notte" promesso da Q10 della Fase 4 per Utility →
   Doppioni non è accendibile (D24, D25).
6. **Eliminati di recente** mostra solo il cestino di Android (`RecentlyDeletedScreen`); il cestino del NAS sta altrove (`NasTrash`
   in `NasOrganizer`), e su Samsung l'app non ritrova i media mandati nel cestino (D-3 aperto) (D26).
7. **Nomi dei nascosti in Cartelle NAS** (I8 della 4B, informativa): l'elenco mostra ancora i nomi dei file nascosti (D23).
8. **Nessun widget**: il "Fatto quando" di 7.1 chiede il test anche "sul widget di prova", che non esiste (il widget vero è della
   Fase 10) (D22).
9. **Ricerca**: nessuna implementazione di produzione di `SearchEngine` (solo `InMemorySearchEngine` di prova e `NoSearchEngine`);
   descrizione e foto d'esempio degli album smart, e i "simili" di Doppioni, dipendono da P5 e P6 della Fase 6 (D15, D25, §2.9).
10. **Nessuna "casa", nessun raggruppamento di luoghi, nessun viaggio**: `core-places` fa solo punto → luogo (D18, D19).
11. **Lo spostamento tra share non avvisa il catalogo**: la nuova posizione la scopre la scansione successiva
    (`CatalogNasPorts.relocateAll`, chiamata dalla scansione); fra la pubblicazione nell'area comune e la scansione l'app punta
    alla sorgente già nel cestino. Per E-SH2 ("nessun elemento mancante in app") serve una porta di completamento (D29).
12. **Il guardiano non parte mai per un media già assente dal telefono**: con Q4 (b) della Fase 5 parte "quando sparisce la copia
    del telefono"; un media solo-NAS spostato nell'area comune resta senza copia privata per sempre (D28).
13. **Le Raccolte non si ordinano né si nascondono**: sezioni fisse in `CollectionsTab` più `extraGroups` passati da `:app` (D33).
14. **Nessuna recinzione per gli album condivisi**: `FeatureFlags` ha `indexPublishTestSharesOnly` e `backupTestSharesOnly`, nulla
    per i file `albums/` né per gli spostamenti verso l'area comune avviati da un album (D30).

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)

| # | Decisione | Perché |
|---|---|---|
| D1 | **Una fase sola, tre tappe** (M1 album personali, Nascosti e Utility; M2 album condivisi; M3 album smart, viaggi, Raccolte). **Tutto ciò che sposta o toglie file passa da `core-nasops`** con un `UserConfirmation` (F4-D24, C10); `core-albums` non vede né `NasFileOps` né `core-nasops`: chiede piani e avvii attraverso porte che `:app` lega (D2). I file nuovi in `.mycloudgallery/albums/` si scrivono con la sola `publishExclusive` di `NasFileSystem` (niente cancellazione né sovrascrittura) | Le parti distruttive restano dove sono già difese e provate (F16-PC); la Fase 7 aggiunge decisioni, non nuovi modi di toccare i file |
| D2 | **Moduli**: `core-albums` (modello, regole smart, viaggi, replica e permessi degli album condivisi, orchestratore della condivisione) e `feature-albums` (schermate). `core-albums` vede `common`, `model`, `trust` (codec e `NasLayout`, `NasFileSystem` per i soli file `albums/`), `database`, `personal` (contratti), `places` (contratti), `search` (solo contratti); **non vede** `nasops`, `backup`, `nas`, `index`, `ai`, `models` né la rete. Le porte `AlbumMover`, `AlbumUploader`, `SafeCopyRequester`, `MoveCompletions` le lega `:app` a `NasOperations`, a `core-backup` (`INCOMING`) e al guardiano | Nessun ciclo; il motore degli album si prova sulla JVM con fake; nessuna strada nuova verso operazioni distruttive (test d'architettura) |
| D3 | **Gli album personali (manuali e smart) vivono nei dati personali** (4.7, F4-D26): voci `ALBUM` nell'area privata dell'account, firmate dalle teste `personal` dei suoi dispositivi, fuse per `(atNas, autore)`. **Mai un file spostato o copiato** per un album personale; mai nulla nell'area comune | È ciò che il piano chiede ("album manuali" fra i dati personali, §1) e ciò che il codice ha già (`PersonalKind.ALBUM`); la privacy viene dal permesso della share privata (C19) |
| D4 | **Campi nuovi dentro i tipi esistenti**, nessun `PersonalKind` nuovo (un tipo nuovo fa scartare l'intero segmento alle app vecchie, `PersonalCodec.kt:81-82,130-136`). In `ALBUM`: `kind` (`manual`/`smart`), `sort`, `smart:period`, `smart:place`, `smart:text`, `smart:example:<media>`, `smart:sources`, `smart:types`, `exclude:<media>`, `accept:<media>`, `frozen`, `shared` (id dell'album condiviso nato da questo). In `SETTING`: `collections_order`, `collections_hidden:<sezione>`, `albums_sort`, `trip_dismissed:<impronta>`, `shared_album_left:<id>`, `full_hash_nightly`. In `MEDIA_FLAG`: `shared_origin` (cartella d'origine di un media proprio spostato in un album condiviso, D31). **Ogni voce `item:`, `exclude:`, `accept:`, `smart:example:` porta l'impronta `fp`** (dimensione + `qh1`), come i flag dei media | Compatibilità provata dalla Fase 5 (`similar_cover`, `similar_apart`) e dalla Fase 6 (`text_reading`, `search_order`); una voce per elemento fa convivere le aggiunte di due dispositivi (stesso motivo di `private_exclusions`); l'impronta ritrova il media se gli id divergono (§0, punto 2) |
| D5 | **Proiezione sul catalogo**: `CatalogAlbumProjection` (in `core-albums`, dopo ogni giro dei dati personali e dopo ogni scansione con novità) porta lo stato unito nelle tabelle v6 (`album`, `album_item`, `album_rule`, D34); un elemento si risolve per id, poi per impronta solo se indica **un** media (come `PersonalProjection`); un elemento non risolto resta "in attesa" e non si mostra. Le tabelle sono **derivate**: si possono ricostruire dallo stato in ogni momento (test) | Stessa strada già provata per preferiti e nascosti; nessuna seconda fonte di verità |
| D6 | **Album condiviso sul NAS: un "contributo" per album e per dispositivo**, nell'area comune: testa firmata di tipo 6 in `.mycloudgallery/albums/<album>/<dispositivo>/head/<versione>` (già nel formato) che elenca segmenti in `.../albums/<album>/<dispositivo>/seg/<impronta>` (nuovo), contenitore `MCGB` con la classe nuova **`album` (codice 17)**. I file `albums/` portano la **versione 3** del formato; teste `index` e `personal` restano alla 1, i file `ai` alla 2. Al più una testa ogni 5 minuti per album e dispositivo, modifiche accorpate in 30 s; pulizia dei propri file come `OwnFilesPolicy` (ultime 3 teste, 7 giorni di grazia) | Ogni dispositivo scrive solo nella sua cartella: due familiari non si sovrascrivono mai (E-SH6, `formato-nas.md` §5.8); un'app della Fase 6 non apre `albums/`; la versione propria evita di toccare il significato della 2 |
| D7 | **Contenuto: voci per chiave con l'ultima scrittura che vince**, come i dati personali. Chiave = SHA-256("mcg-album\0"‖id album‖campo). Campi: `definition` (titolo, cartella dell'album, stato `active`/`closed`/`deleted`), `level` (storia dei livelli di permesso), `cover`, `sort`, `item:<media>` (posizione, chiave di contenuto intera, versione, percorso nella cartella, account di chi l'ha aggiunto; lapide = tolto), `heir` (eredità, D11). Corpo JSON canonico ≤ 4 KiB (`at` = ora del NAS, `author` = dispositivo, `account`). **Id dell'album legato al creatore**: primi 128 bit di SHA-256("mcg-album-id\0"‖account del creatore‖nonce di 16 byte); `definition` porta account e nonce e chi legge ricalcola | La fusione per chiave è commutativa e idempotente (già provata in `PersonalMerge`); legare l'id all'account impedisce a un altro familiare di "rivendicare" un album esistente |
| D8 | **Regole di chi legge** (codec ostile, `core-trust`): busta `MCGS` valida; firmatario approvato nel registro corrente (un dispositivo **dismesso** vale per le teste congelate; uno **revocato o perso** è ignorato del tutto: i suoi contributi spariscono, i file restano, `formato-nas.md` §4.6); `family`, `device` e cartella coerenti; anti-rollback e equivocazione per dispositivo (`HeadMark`); `account` della voce = `nasUser` del dispositivo nel registro; `at` al più 2 minuti nel futuro; tetti di A4 (§0.3); un campo sconosciuto rifiuta il segmento. Ogni violazione rifiuta il contributo intero, gli altri valgono (E-SH14) | Stesse difese delle teste esistenti; un contributo falso costa al più una vista sbagliata |
| D9 | **Attestazione degli elementi: decide il catalogo di chi legge, mai il NAS da solo.** Un elemento di un album condiviso si mostra solo se il catalogo locale vede, **nell'area comune** (`commonShare` del registro), un file presente con la **stessa chiave di contenuto intera** (dimensione e `qh1`, e l'hash completo se entrambi lo hanno). Un elemento che rimanda a un file privato, sparito o diverso non si mostra (né conta) e **non avvia nulla**: nessuno spostamento, copia, cancellazione o scaricamento. Solo il dispositivo che ha aggiunto un elemento lo vede "in arrivo" finché il suo spostamento non è finito (diario locale) | E-S11 e §4 del piano: un finto album con media privati non sposta niente e non mostra niente che chi legge non veda già; funziona anche con F4-D31 acceso, perché ogni dispositivo scansiona l'area comune da sé |
| D10 | **Permessi imposti dall'app, a tre livelli**: **Tutti possono tutto**, **Gli altri aggiungono**, **Gli altri guardano**, scelto dal creatore e cambiabile solo da lui (o dall'erede). La tabella operazione per operazione è al §2.4. **Doppio controllo**: chi scrive non offre l'azione (e il pacchetto lo prova); chi legge **scarta** le voci non permesse, valutate con il livello in vigore all'ora `at` della voce (storia dei livelli nella voce `level`, al più 32 cambi). Ogni schermata dell'album lo dichiara: "I permessi li fa rispettare l'app: dal PC chi accede all'area comune può fare tutto" (proposta 7, L2) | Un'app difettosa o un dispositivo compromesso non allargano i permessi agli altri; dal PC nessuna protezione è possibile e si dice (L57) |
| D11 | **Il creatore è un account, non un dispositivo**: tutti i dispositivi approvati del suo account hanno i diritti del creatore. **Eredità** (E-SH11): quando nel registro firmato l'account del creatore non ha più nessun dispositivo approvato né dismesso, un dispositivo admin può pubblicare la voce `heir` (azione locale confermata in "Album di familiari usciti"); da quel momento l'admin ha i diritti del creatore. Prima, nessuno | "L'admin eredita l'album" senza dare all'admin un potere silenzioso sugli album di chi è ancora in famiglia |
| D12 | **Condivisione = spostamento nell'area comune, solo da un'azione locale confermata** (7.4, E-S11, C10): il foglio dice "N foto e M video (X GB) verranno spostati nell'area comune, nella cartella «…», e li vedranno tutti i familiari"; i media del telefono non ancora sul NAS si **caricano direttamente** nella cartella (`INCOMING`, E-SH1); quelli già nell'area comune restano dove sono (riferimento, un solo file: E-SH11); quelli nell'area privata di un altro account non si possono aggiungere. Un **piano unico** con conteggi, spazio richiesto e impronta (`PlanHash`), confermato una volta; poi tutto in background con il diario della condivisione (D29, §2.5). **Nascosti**: il foglio offre «Lascia fuori i N nascosti» (preselezionata, come R11) o «Condividi anche i nascosti» con l'avviso E-SH9 (Q3) | È la regola più importante della fase: nessun dato letto dal NAS (definizioni, indice, cestini) può far partire uno spostamento |
| D13 | **Album smart: il nome diventa regole.** Il titolo passa da `ItalianQueryParser` (Fase 6, P7 fatto): chip di data → **periodo**, chip di luogo → **luogo** (con le alternative per gli omonimi, E-AL2), il resto → **descrizione**. Il periodo si **raffina sulle foto**: per un anno o una stagione con un luogo, si cercano i giorni con foto geolocalizzate in quel luogo e il periodo diventa quella serie di giorni ± 1 giorno (E-AL1: una serie che attraversa il 31/12 resta una). Le regole proposte si mostrano e si cambiano tutte prima di creare (periodo, luogo con raggio, descrizione, fino a 6 foto d'esempio, sorgenti, tipi) | Il piano: "il nome viene interpretato e propone regole modificabili"; l'analizzatore esiste e non usa né rete né modelli |
| D14 | **Sicuri e dubbi** (7.2). **Sicuro** (entra da solo): (A) data nel periodo **e** GPS entro il raggio; (B) foto senza GPS **dello stesso dispositivo**, scattata fra due sicuri (A) dello stesso viaggio a meno di 6 ore da ciascuno; (C) solo per album senza luogo: data nel periodo **e** descrizione "sicura" per il motore di ricerca (F6-D27). **Dubbio** ("Suggerite"): foto nel periodo senza GPS di un altro dispositivo o di un familiare (E-AL3), screenshot e registrazioni dello schermo nel periodo (E-AL4), GPS appena fuori raggio (fino a 2 volte), descrizione o foto d'esempio "meno probabili", media arrivati dopo che rispettano solo regole deboli (E-AL7). **Mai**: nascosti (E-AL8, E-H1), esclusi (E-AL5), membri di gruppo diversi dalla copertina (una cella per gruppo, F5-D36). Un "Accetta" su un suggerito lo fa entrare (voce `accept:`) | Le regole forti (data e GPS) danno da sole il 98% di precisione sui viaggi; il resto è suggerito, mai inserito alla cieca |
| D15 | **Descrizione e foto d'esempio passano dai contratti della Fase 6**: `SearchEngine.search` con i chip di periodo e luogo come vincoli e `semantic` = descrizione; `SimilarTo` (aggiunta richiesta a P6, §2.9) per le foto d'esempio. **Senza motore** (P6 non pronto, modello assente) le regole di data, luogo, dispositivo, sorgente e tipo funzionano lo stesso e l'editor dice "La descrizione si userà quando la ricerca per contenuto sarà pronta" | La Fase 7 non aspetta la Fase 6 (§0); i vettori migliorano i suggerimenti, non servono ai sicuri dei viaggi |
| D16 | **Esclusioni, aggiornamento, congelamento.** "Togli dall'album" su un elemento automatico scrive `exclude:` (sincronizzato, per sempre: E-AL5). L'album si **ricalcola** a ogni cambiamento del catalogo che tocca il suo periodo o le sue sorgenti (indice per giorno, a lotti) e a ogni cambiamento di regole. **"Congela"**: gli elementi di quel momento diventano elementi manuali (`item:` con impronta) e le regole smettono di agire; "Scongela" le riattiva tenendo esclusioni e accettazioni. **Regole troppo larghe** (E-AL6): oltre 3.000 elementi o oltre il 20% della Libreria, l'editor lo dice e propone di restringere periodo o raggio | 7.2: "le esclusioni restano; aggiornamento quando arrivano nuovi media; un album smart si può congelare" |
| D17 | **Album smart condiviso**: le regole restano **personali** (del creatore); nell'area comune ci sono solo gli elementi. Una foto nuova che entrerebbe da sola **non si sposta da sola**: va in "Da condividere" dell'album con un avviso nel riepilogo, e si condivide con un tocco che apre la conferma con il conteggio (Q4) | Lo spostamento parte solo da un'azione locale confermata (D12); un album che si riempie da solo nell'area comune sposterebbe file senza un sì |
| D18 | **"Casa" dedotta solo in locale** (C19, §7 del piano): dalle foto **proprie** con GPS (telefono di questo dispositivo, area privata dell'account) degli ultimi 12 mesi, celle di ~1 km pesate per **giorni distinti** con foto serali o notturne (19–8, ora locale dello scatto); "casa" = la cella (con le vicine) con più giorni, se copre almeno il 15% dei giorni con GPS; un secondo luogo abituale oltre il 10% (casa al mare) non è un viaggio. Tenuta in una tabella locale (`home_estimate`) **mai** sincronizzata, pubblicata, esportata o scritta nei log; si ricalcola ogni notte; tolta dall'azzeramento. Nascosti esclusi | Il piano la vuole solo locale; i giorni pesano più delle foto (una festa con 300 foto non sposta la casa) |
| D19 | **Viaggi ed eventi rilevati.** **Viaggio**: serie massima di giorni locali con foto proprie geolocalizzate a più di max(50 km, 3 volte il raggio di casa), con al più 2 giorni senza foto in mezzo, e almeno 2 giorni **oppure** un giorno con almeno 20 foto oltre 100 km. **Evento**: almeno 30 media in al più 4 ore, fuori da un viaggio, con un ritmo almeno 5 volte quello medio dei giorni con foto. Titolo dalla Fase 6 (`PlaceNames.summary` + periodo: "Oslo, marzo 2026", "Toscana e altri 2 luoghi, agosto 2025"; evento: "Sabato 12 aprile 2026" + luogo se c'è). Le foto dei familiari e senza GPS non creano viaggi (ma entrano come suggerite nell'album nato dal viaggio) | 7.3: "giorni lontani da casa o molte foto in poche ore diventano proposte"; regole deterministiche e misurabili (F88, F89) |
| D20 | **Proposte**: sezione **Viaggi** delle Raccolte, dal più recente; "Crea album" (un tocco: album smart con periodo e luogo del viaggio, regole già compilate) o "Non mi interessa" (voce `trip_dismissed:<impronta di inizio, fine e luogo>`, sincronizzata: una proposta scartata non torna su nessun dispositivo). Nessuna notifica nella Fase 7 (le notifiche dei ricordi sono della Fase 9). Le proposte già diventate album non si ripropongono | "Accettabili con un tocco"; niente notifiche nuove senza un motivo |
| D21 | **Ordinamento e copertina.** Elenco degli album: "Personalizzato" (trascinamento, voce `order`), "Aggiornati di recente", "Nome", "Data dei media"; la scelta è `albums_sort` (sincronizzata). Dentro un album: "Dal più vecchio" (predefinito per smart e viaggi), "Dal più recente" (predefinito per i manuali), "Personalizzato" (posizioni degli elementi). Copertina scelta (`cover`) o automatica (per i manuali l'ultimo aggiunto, per gli smart la foto con più pixel del giorno con più sicuri); **una copertina nascosta, tolta o non attestata si sostituisce con quella automatica**, solo per chi non la può mostrare | 7.1: "ordinamento e copertina scelti"; una copertina non deve mai far trapelare un nascosto (E-H1) |
| D22 | **Nascosti mai, su ogni superficie, da una sola fonte.** Ogni superficie che mostra media fuori dai Nascosti legge da `VisibleMedia` (vista del catalogo senza nascosti né membri di gruppo nascosti) o filtra con `HiddenMask`: Libreria, Raccolte e loro conteggi, album (manuali, smart, condivisi: anche quelli in cui un altro li ha messi), Suggerite, Da condividere, copertine, viaggi ed eventi (anche la loro rilevazione), Utility e Doppioni, ricerca (F6-D29), "Aggiungi ad album", selettore per le altre app, **widget di prova** (solo debug, un `AppWidgetProvider` con `RemoteViews` della piattaforma sopra `WidgetFeed`, il contratto che userà il widget vero della Fase 10). Un **test di proprietà** genera Librerie con nascosti a caso e controlla ogni superficie elencata in un registro (`HiddenSurfaces`): una superficie nuova senza test fa fallire il registro | 7.1: "un elemento nascosto non compare in nessuna superficie (test automatico su tutte le schermate e sul widget di prova)" |
| D23 | **Nascosti: protezione, avvisi, Cartelle NAS.** Restano dietro `HiddenAccessGate` (biometria **o** credenziale del telefono a ogni ingresso, tolleranza di 1 minuto, schermata protetta da `FLAG_SECURE`, recenti oscurati); il cancello è lo stesso degli Eliminati di recente e usa lo stesso autenticatore del blocco dell'app, ma **non** si apre con lo sblocco dell'app (E-H3: biometria cambiata → credenziale). Nascondere un media dell'area comune o di un album condiviso mostra l'avviso "I familiari continuano a vederlo: Nascosti protegge solo dentro l'app" (E-H2). In **Cartelle NAS** i file nascosti diventano una riga "N elementi nascosti" senza nomi (chiude I8 della 4B) | Piano 7.1 ("va dichiarato che è una protezione dell'app"); I8 era rimasta aperta |
| D24 | **Utility**: pagine **Screenshot**, **Video**, **Modificate** (nuovo `LibraryFilter.EDITED`, dal bit `CellFlags.EDITED`), **WhatsApp**, **Doppioni**, **Eliminati di recente**, più "Libera spazio" (Fase 5). Le pagine-filtro usano la stessa griglia e lo stesso viewer della Libreria (come le Raccolte minime) | Il piano 7.1 elenca le sei voci; riuso di ciò che c'è |
| D25 | **Doppioni** (E-L3, proposta 5 aggiornata da F5-D36). **Identici**: un media con più posizioni **dalla stessa parte** (due file uguali nell'area privata, o nell'area privata e nell'area comune, o due sul telefono) verificate con l'hash completo; la pulizia guidata propone di tenere **una** copia per parte, nell'ordine: copia privata verificata ("al sicuro"), copia in un album condiviso, copia nella cartella di backup del dispositivo, la più vecchia; mai l'ultima copia privata, mai copie in aree senza permesso, mai copie del telefono senza il dialogo di sistema. **Simili** (scatti diversi ma quasi uguali, non le copie simili che sono già una cella): gruppi di foto dello stesso giorno, a meno di 10 minuti, con vettori molto vicini (soglia calibrata in P8 sul set sintetico) e, finché i vettori mancano, con la firma visiva di F5-D36 estesa a una finestra di 10 minuti; la pulizia propone di tenere la migliore (preferita, poi modificata, poi in un album, poi più pixel, poi meno compressa) e l'utente sceglie. **Tutto finisce nei cestini** (telefono e NAS, 30 giorni), con il foglio di eliminazione esistente ("Solo dal telefono" / "Ovunque") | "Pulizia guidata": l'app propone, l'utente decide, niente di definitivo (§4 del piano) |
| D26 | **Eliminati di recente unificati**: una pagina con il cestino di Android (per i media del telefono) e i cestini del NAS che si possono ripristinare (voci proprie e quelle con il permesso, F4-D25), giorni rimasti per elemento, "Ripristina" e "Elimina subito" (doppio tocco, F4 `PURGE_NOW`), dietro lo stesso cancello dei Nascosti. Su Samsung il cestino di Galleria è un'altra cartella (D-3): finché D-3 è aperto la pagina dice "Su questo telefono le foto eliminate con Galleria si ripristinano da Galleria → Cestino" (la stringa c'è già) | 7.1 la chiede in Utility; D-3 non si chiude in questa fase e si dice |
| D27 | **Hash completo dei video del NAS "per tutti, di notte"** (Q10 della Fase 4): un interruttore in Utility → Doppioni, spento di default, impostazione personale sincronizzata (`full_hash_nightly`); acceso, l'attività `FULL_HASH` esistente lavora anche fuori dai gruppi `PROBABLE_DUPLICATE`, secondo le regole dei video (attività pesante, Wi-Fi di casa) | È la promessa della Fase 4; serve ai doppioni identici dei video grandi |
| D28 | **"Al sicuro" garantito** (7.4, E-B13, E-B14, F5-D9, Q4 (b) della Fase 5). La sorgente di ogni spostamento resta 30 giorni nel cestino privato (F4-D24). Per i media **propri** che dopo lo spostamento hanno solo la copia comune: se il telefono ha ancora la copia, vale Q4 (b) (il guardiano parte quando sparisce o prima di "Libera spazio"); **se il telefono non l'ha già più**, la condizione di Q4 (b) è già vera e il guardiano parte **subito dopo** lo spostamento (copia lato server in `Backup/Area comune/AAAA/MM/`). Lo stato del media dice la verità ("Nell'area comune · copia privata in arrivo") | Chiude il buco del §0 (punto 12) senza cambiare la risposta Q4 (b): con un media solo-NAS spostato, il guardiano oggi non partirebbe mai |
| D29 | **Identità stabile e nessun elemento mancante** (E-SH2, E-SH3). Il diario della condivisione (`album_share_op`, `album_share_item`, D34) registra per ogni elemento media, chiave di contenuto, origine, destinazione e l'id dell'operazione di `core-nasops` o del caricamento. `core-nasops` ottiene una **porta di completamento** (`MoveCompletionSink`, chiamata dopo `PUBLISHED`, idempotente): `:app` collega subito la nuova posizione allo **stesso** `MediaId` (`attachCopy`/`relocateNas`), **prima** che la sorgente vada nel cestino. Preferiti, nascosti, album personali, vettori (per chiave di contenuto) e gruppi restano collegati perché l'id non cambia; il record `meta` che il dispositivo pubblica nell'area comune porta lo stesso `MediaId`/`VersionId` (F4-D7). Solo dopo, la voce `item:` si pubblica nell'album | Il piano: "identità stabile, quindi album, preferiti e vettori restano collegati"; oggi l'app scopre la nuova posizione solo alla scansione (§0, punto 11) |
| D30 | **Recinzione**: `FeatureFlags.sharedAlbumsTestSharesOnly` (vero anche in release finché l'utente non dice di sì, Q7): gli album condivisi (definizioni e spostamenti) solo fra le share di prova (`test`/`test2` → `test-comune`); sulle share vere il comando "Condividi" dice "Arriva dopo le prove". In debug con gli account di prova vale `ShareFence` (F4-D33). Gli album personali, smart, i viaggi e l'Utility non hanno recinzione (non spostano file; Doppioni usa il cestino come oggi) | Il primo spostamento di file di famiglia nell'area comune vera deve venire dopo le prove e con un sì, come F64 della Fase 6 |
| D31 | **Smetti di condividere, Elimina, Riporta nelle mie cartelle** (proposta 6, E-SH7). Solo il creatore (o l'erede). **Smetti di condividere**: voce `definition` con stato `closed`; per gli altri l'album sparisce dalle Raccolte con l'avviso "«…» non è più condiviso" (Q5); per il creatore diventa un album personale con gli stessi elementi. **Elimina**: stato `deleted`, l'album sparisce per tutti. In entrambi i casi **i file restano** nell'area comune (visibili in Cartelle NAS). Il foglio offre **"Riporta nelle mie cartelle"** per i soli media **propri** (aggiunti dal proprio account e di cui si ha `shared_origin`): spostamento area comune → area privata con lo stesso diario e la stessa conferma col conteggio, verso la cartella d'origine (o `Backup/<dispositivo>/AAAA/MM/` se non c'è più), nomi alternativi se occupati; la copia comune va nel cestino comune 30 giorni. "Riporta" esiste anche per singoli elementi propri ("Rimuovi dall'album e riporta nelle mie cartelle") | "Non riporta indietro i file, con l'opzione «Riporta nelle mie cartelle»"; la cartella d'origine sta nei dati personali (area privata), non nella definizione comune (privacy dei percorsi) |
| D32 | **UI come stati e contratti** (come F4-D27, F5-D30, F6-D36): `feature-albums` e le aggiunte a `feature-library` consegnano ViewModel e stati sigillati (§1.5), composable fatte **solo** con i componenti G2 esistenti; `GalleryShareSheetContent` e `GalleryBadge.Shared` si usano così come sono | Il filone grafico rifinisce senza toccare la logica |
| D33 | **Raccolte ordinabili e nascondibili** (7.5): sezioni **Album** (miei e condivisi, sempre visibile), **Album smart**, **Viaggi**, **Cartelle NAS** (oggi "Sul NAS"), **Telefono** (oggi "Dal telefono"), **Da tenere al sicuro**, **Utility**, **Nascosti**; **Persone** e **Ricordi** compaiono con le Fasi 8 e 9 (il posto nell'ordine è già riservato). "Modifica Raccolte": trascinamento e interruttore per sezione; scelte sincronizzate (`collections_order`, `collections_hidden:<sezione>`). Nascosti nascosti dalle Raccolte restano raggiungibili da Impostazioni → Privacy | Il piano 7.5; una scelta per persona, uguale su telefono e tablet |
| D34 | **Schema v6** (migrazione 5→6 mai distruttiva; `ExportedSchemaTest` 1→6 … 5→6): `album` esteso (`kind`, `sort`, `frozen`, `shared_id`, `state`), `album_item` esteso (`origin` auto/manuale/accettato, `fp`), `album_rule`, `album_exclusion`, `album_suggestion`, `shared_album`, `shared_album_entry` (replica delle voci, senza chiavi esterne), `shared_album_head_mark`, `album_share_op`, `album_share_item`, `trip_proposal`, `home_estimate` (locale, tolta dall'azzeramento); `MergeReferencesTest` con le tabelle nuove che puntano a `media` | Una sola migrazione sul campo per la fase |
| D35 | **"Aggiungi ad album" ovunque**: dalla selezione in Libreria, dal viewer (⋮), dai risultati di Cerca, dalla condivisione in arrivo da altre app (F5-D24: `feature-integrations`, "Salva sul NAS" o "Aggiungi ad album"). Aggiungere a un album condiviso apre sempre il foglio di conferma di D12 | Promessa della Fase 5 (§0, punto 4) |
| D36 | **Ora.** Voci personali e degli album condivisi con l'ora del NAS (`at`, F4-D32: un salto oltre 5 minuti sospende le pubblicazioni); periodi degli album smart e viaggi in **giorni locali dello scatto** (`local_day`, col fuso dello scatto); durate e attese con l'orologio monotono; "oggi" solo dalla porta `Today` (F6-D39) | Come le fasi precedenti; un viaggio in un altro fuso resta nei suoi giorni |
| D37 | **Log e Diagnostica**: mai titoli d'album, nomi di cartelle, luoghi, casa, coordinate, descrizioni, id di media o account; solo numeri e codici (`ALBUM_*`, `SHARE_*`, `SMART_*`, `TRIP_*`, §2.8). `toString()` sicuri come in `core-nasops` | C21, come le fasi precedenti |
| D38 | **Cancelli**: le definizioni degli album condivisi si scrivono dietro gli stessi cancelli delle teste (`IndexWriteRules`: congelamento, emergenza, salto dell'ora, fiducia, sola lettura) più D30; gli spostamenti si fermano con `FROZEN` (pausa, ripresa allo scongelamento) e non fanno scattare il congelamento (franchigia del diario, R8 della 4B) | Nessuna scrittura nuova sfugge ai cancelli esistenti |
| D39 | **Togliere da un album non è eliminare** (E-D8). "Rimuovi dall'album" toglie la voce (il file resta dove sta, anche nella cartella dell'album, dove dal PC si continua a vedere); "Elimina" è l'eliminazione normale (cestino del NAS 30 giorni) con l'avviso "Sparirà per tutti i familiari" e solo se il livello lo permette (§2.4). Un file aggiunto **dal PC** nella cartella dell'album **non** entra nell'album (l'appartenenza sta nella definizione), ma si vede in Cartelle NAS e l'album propone "N file nuovi nella cartella: aggiungili" (azione locale) | La cartella è la casa dei file, non l'album (E-SH11); dal PC nulla decide al posto dell'app |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)

**Album smart e viaggi (E-AL)**
- **E-AL9**: nome senza né data né luogo ("Gatto", "Compleanni"): nessun periodo, solo descrizione; senza motore di ricerca l'editor
  lo dice e l'album resta vuoto con "Aggiungi a mano" (D15).
- **E-AL10**: viaggio in un altro fuso orario: i giorni sono quelli locali dello scatto; una foto dell'ultima sera non cade nel giorno
  dopo (D36).
- **E-AL11**: telefono senza GPS per un giorno intero del viaggio: le foto di quel giorno vanno in "Suggerite" (la regola B vale solo
  fra due foto geolocalizzate a meno di 6 ore).
- **E-AL12**: due viaggi nello stesso luogo nello stesso anno ("Oslo 2026" a marzo e a dicembre): il periodo raffinato li prende
  entrambi e l'editor mostra le due serie di giorni, con la scelta di tenerne una.
- **E-AL13**: regole cambiate dopo esclusioni e accettazioni: le esclusioni restano; un'accettazione che le regole nuove rendono
  "sicura" resta com'è; una fuori dal nuovo periodo resta solo se l'utente lo sceglie ("Tieni le 12 foto aggiunte a mano").
- **E-AL14**: album smart congelato su un dispositivo e regole cambiate su un altro nello stesso momento: vince la voce più recente per
  campo; un album congelato non applica le regole, qualunque esse siano.
- **E-AL15**: "casa" che cambia (trasloco): la stima usa gli ultimi 12 mesi e cambia quando la nuova casa supera la vecchia; i viaggi
  già proposti non cambiano.
- **E-AL16**: nessuna foto con GPS (dispositivo senza posizione nelle foto): niente casa, niente viaggi; la sezione Viaggi lo spiega
  una volta e propone gli **eventi** (che non hanno bisogno del GPS).
- **E-AL17**: foto di un familiare con GPS nel luogo e nel periodo del mio viaggio: suggerita, mai sicura (non è del mio dispositivo).

**Condivisione (E-SH)**
- **E-SH12**: lo stesso media aggiunto allo stesso album condiviso da due familiari insieme: una sola voce vince, un solo file; il
  secondo spostamento trova il file già nell'area comune e si chiude senza copiare (D9, D29).
- **E-SH13**: il livello di permesso cambia mentre un familiare sta aggiungendo: le voci con `at` successivo al cambio che il nuovo
  livello non permette sono scartate da chi legge; il file già spostato resta nell'area comune e il familiare vede "Non aggiunte:
  l'album ora è in sola visione" con "Riporta nelle mie cartelle" (D10, D31).
- **E-SH14**: definizione d'album falsa, malformata, con un id che non torna con l'account, o firmata da un dispositivo revocato:
  contributo ignorato, contatore in Diagnostica, nessuna azione (D7, D8).
- **E-SH15**: elemento di un album condiviso che rimanda a un file privato o inesistente: non si mostra, non si conta, non si sposta
  (D9).
- **E-SH16**: un familiare rinomina o sposta dal PC la cartella dell'album: gli elementi si ritrovano per chiave di contenuto
  ovunque siano nell'area comune; l'album propone "La cartella è cambiata: usa «…»" (azione locale del creatore).
- **E-SH17**: un familiare cancella dal PC un file dell'album: l'elemento esce dall'album per tutti finché il file non torna (dal
  cestino del NAS o dal PC); al proprietario resta la copia privata se il guardiano l'ha fatta (D28, L44).
- **E-SH18**: lo spostamento di un media proprio trova il file sorgente cambiato dal PC (E-D13): quell'elemento si ferma, entrambe le
  copie restano, l'album lo mostra "Non condiviso: il file è cambiato" con "Riprova".
- **E-SH19**: album condiviso mentre l'area privata è congelata o l'area comune è in sola lettura: la conferma non si offre; se succede
  a metà, pausa con il motivo e ripresa (D38).
- **E-SH20**: un dispositivo del creatore viene revocato: i suoi contributi spariscono (anche le aggiunte fatte da lì); gli altri
  dispositivi dello stesso account possono ripubblicarle con "Ripristina gli elementi di un dispositivo revocato" (azione locale,
  solo per elementi i cui file sono ancora nell'area comune).
- **E-SH21**: familiare che ha lasciato l'album ("Non mostrare più"): non lo vede più su nessun suo dispositivo (`shared_album_left`);
  può ritrovarlo da "Album nascosti dalle Raccolte".
- **E-SH22**: nome dell'album con caratteri vietati, punti finali, nomi riservati di Windows o lungo oltre 64 caratteri: la cartella
  usa un nome corretto (regole di `TextRules`, §8.4 del formato), il titolo resta quello scelto.

**Nascosti (E-H)**
- **E-H5**: un media nascosto in un album condiviso da un altro familiare: per me non c'è (né nell'album né nei conteggi né come
  copertina); per gli altri sì.
- **E-H6**: un media nascosto è la copertina scelta da un altro dispositivo dello stesso account: copertina automatica (D21).
- **E-H7**: viaggio formato solo da media nascosti: non si propone; un viaggio con qualche nascosto si propone senza di loro.

**Utility e Doppioni (E-U, nuova categoria)**
- **E-U1**: pulizia dei doppioni identici con una copia nell'area di un altro account: quella copia non si propone (nessun permesso)
  e si dice "1 copia in un'area di Marco".
- **E-U2**: doppione identico con l'unica copia verificata ("al sicuro") nella cartella proposta per l'eliminazione: si tiene quella e
  si propone di eliminare l'altra.
- **E-U3**: "simili" con una delle foto in un album o preferita: preselezionata da tenere; mai eliminata senza che l'utente la
  selezioni.
- **E-U4**: hash completo notturno acceso con il NAS che si spegne a metà: riprende la notte dopo dal video successivo.
- **E-U5**: un media eliminato dalla pulizia dei doppioni è in un album: esce dall'album; se torna dal cestino, rientra (l'id è lo
  stesso).

**Cancellazioni e Raccolte**
- **E-D16**: "Elimina" su un elemento di un album condiviso al livello "Gli altri aggiungono" aggiunto da un altro familiare: l'azione
  non c'è; c'è "Rimuovi dall'album" solo se il livello lo permette (§2.4).
- **E-L14**: sezioni delle Raccolte nascoste su un dispositivo: nascoste su tutti quelli dell'account; una sezione nuova (Persone, con
  la Fase 8) compare in coda.

### 0.3 Modifiche da fare in P0 (agli altri documenti)

**`docs/formato-nas.md`** (versione 3 del formato **solo** per i file `albums/`)
- **A1, §2.2 albero**: `albums/<id album>/<id dispositivo>/head/<versione>` (c'è già) e `albums/<id album>/<id dispositivo>/seg/<impronta>`
  (nuovo), solo nell'area comune; fuori da `.mycloudgallery/` la cartella degli album condivisi scelta con Q1 (non fa parte del
  formato: sono file di media normali).
- **A2, §5.8**: contenuto della testa di tipo 6 (`{family, album, device, version, registryVersion, createdAt, segments[]}`), classe
  `album` (17), chiave, corpi, campi e regole di chi legge di D7–D11; id dell'album legato al creatore.
- **A3, §5.5-bis**: nuovi campi (valori) dentro `album`, `setting` e `media_flag` dei dati personali (D4), senza cambiare versione;
  impronta obbligatoria sulle voci `item:`, `exclude:`, `accept:`, `smart:example:`.
- **A4, §7 tetti**: corpo di una voce d'album ≤ 4 KiB; al più 20.000 voci per contributo, 8.192 per segmento; titolo ≤ 64 caratteri
  (§8.4); percorso dell'elemento ≤ 1.024 byte, relativo alla share, mai dentro `.mycloudgallery` (confronto NFKC, B1 della revisione
  1.4); storia dei livelli ≤ 32.
- **A5, §9 regole di sicurezza**: punto 4 esteso: "una definizione d'album, vera o falsa, non sposta, non copia, non cancella e non
  scarica nulla; gli elementi si mostrano solo se il catalogo di chi legge vede il file nell'area comune (D9)".
- **A6, §10**: i file `albums/` hanno la versione 3; chi legge accetta 1..3 secondo il tipo; un'app più vecchia non apre `albums/`.
- **A7, §4.6**: "Album condivisi" con la distinzione dismesso (teste congelate valide) e revocato (ignorato), ed E-SH20.

**`docs/sicurezza.md`**: S5 e S11 (scenari nuovi: finti album, permessi aggirati, nascosti condivisi); C10 (album: spostamenti solo dalla
conferma, mai da una definizione), C14 (Nascosti su ogni superficie, widget di prova), C19 ("casa" solo locale realizzata, D18;
cartella d'origine nei dati personali, D31); **C24–C27** nuove (§6.1); §6 **L57–L66** (§6.2); §7 checklist della Fase 7 riscritta
(§6.3, i tre livelli definiti); §8 righe per D6, D9, D10, D18, D28, D30.

**`docs/modello-dati.md`**: nuovo §14 "Schema v6" (lo scrive P1); §9 "Album completi, album smart e condivisi: Fase 7" chiuso; §5
"un media condiviso resta di chi l'ha condiviso": il proprietario è l'account in `item:` (D7).

**`docs/decisioni.md`**: sezione "Fase 7" con D1–D39 e le risposte Q1–Q7 del §9.

**`docs/fase5-progetto.md`**: nota a D9 e al §2.3 (il guardiano parte subito per i media già assenti dal telefono, D28); nota a D24
("Aggiungi ad album" fatto, D35); nota a D35 (permessi delle versioni nell'area comune: §2.4).

**`docs/fase4-progetto.md`**: nota a D24 (porta di completamento degli spostamenti, D29) e a Q10 (interruttore dell'hash completo,
D27).

**`docs/fase6-progetto.md`**: §2.9, le righe della Fase 7 rimandano a questo documento; aggiunta a P6 della Fase 6: `SimilarTo` e
la ricerca ristretta a un insieme di candidati (§2.9 di questo documento).

**`docs/regole-attivita.md`**: §6 costanti nuove (`smartAlbumRecomputeBudget`, `tripDetectionNightly`, `similarDuplicatesWindow`);
gli spostamenti in condivisione e il guardiano dopo la condivisione come attività pesanti (Wi-Fi di casa, F5-D9).

**`docs/grafica-progetto.md`**, "Dubbi grafici": elenco e pagina degli album, editor delle regole, "Suggerite" e "Da condividere",
foglio di condivisione con i tre livelli e la dichiarazione, proposte di viaggio, Doppioni e pulizia guidata, "Modifica Raccolte".

**Piano**: edge case del §0.2; proposta 5 (Doppioni: identici dalla stessa parte e simili, D25); proposta 6 (Riporta solo per i
media propri, D31); proposta 7 (tabella dei permessi, §2.4); 7.4 "al sicuro garantito" con D28; §6 E-SH11 precisato (l'appartenenza
non dipende dalla cartella, D39).

**`README.md`**: moduli nuovi e grafo.

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-trust       + data/AlbumDocs.kt: testa di tipo 6 (contenuto), classe album (17), codec ostile, versione 3;
                   NasLayout.albumSegment(album, device, hash)                                            (P2)
core-albums      → common, model, trust (AlbumDocs, NasLayout, NasFileSystem SOLO per albums/), database,
                   personal (contratti), places (contratti), search (solo contratti: SearchEngine, QueryParser) (P0)
                   personal/   proiezione degli album personali, campi D4                                  (P3)
                   shared/     replica, fusione, permessi, pubblicatore dei contributi                     (P2)
                   share/      orchestratore della condivisione e del "Riporta", diario, porte              (P4)
                   smart/      interpretazione del nome, regole, classi, ricalcolo, congela               (P6)
                   trips/      casa (solo locale), viaggi, eventi, proposte                                (P7)
                   NON vede: nasops, backup, nas, nasmedia, index, ai, models, rete
feature-albums   → albums (contratti di stato e comandi), library (viste), imaging, designsystem         (P10)
                   cieca a core-nas, core-nasops, core-backup, core-index, core-trust
feature-library  + collections/ (sezioni ordinabili e nascondibili, P11), utility/ (pagine-filtro, Doppioni,
                   P8), trash/ (Eliminati di recente unificati, P8), hidden/ (riga senza nomi in Cartelle NAS, P9)
feature-viewer   + voci ⋮ "Aggiungi ad album", "Rimuovi dall'album", "Imposta come copertina" come dati      (P10)
feature-integrations + "Aggiungi ad album" nella condivisione in arrivo (azione verso :app)               (P10)
core-nasops      + contract/Completions.kt: MoveCompletionSink (chiamata dopo PUBLISHED, idempotente)     (P4)
core-backup      + destinazione "cartella dell'album" per INCOMING; richiesta del guardiano dopo una
                   condivisione (SafeCopyRequests)                                                         (P4)
core-library     + LibraryFilter.EDITED; VisibleMedia / HiddenMask; WidgetFeed; AlbumTimelineView         (P0 firme, P8, P9)
core-personal    + campi D4 (costanti e lettori), impronta sugli elementi (PersonalAlbums)                (P0 firme, P3)
core-database    schema v6 (D34)                                                                           (P1)
app → tutti
```
- `core-albums` non vede `core-nasops` né `core-backup`: lo spostamento, il caricamento nell'area comune e il guardiano passano
  dalle porte `AlbumMover`, `AlbumUploader`, `SafeCopyRequester`, legate da `:app`; la conferma (`UserConfirmation`) la costruisce
  solo `ConfirmationIssuer` di `core-nasops`, chiamato da `:app` quando la schermata la emette (test d'architettura: nessuna
  classe di `core-albums` nomina `core.nasops` o `core.backup`).
- `core-albums/.../shared/` scrive **solo** sotto `albums/<id>/<proprio dispositivo>/` (test di proprietà sul `NasFileSystem` finto:
  nessun percorso fuori, nessuna scrittura senza `publishExclusive`); **nessun** altro pacchetto di `core-albums` vede
  `NasFileSystem`.
- `core-albums/.../trips/` è l'**unico** che legge e scrive `home_estimate`; nessuna API pubblica restituisce coordinate della casa
  (solo "questo giorno è lontano da casa sì/no" e la distanza arrotondata a 10 km), test d'architettura e test di proprietà sui log.
- Restano le regole delle Fasi 4–6: solo `core-nasops` vede `NasFileOps`; i `feature-*` non si conoscono fra loro; `core-search`
  non vede `core-albums` (la dipendenza è solo nel verso albums → contratti di search).

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-trust` (P2): file degli album condivisi**
| Elemento | Firma | Note |
|---|---|---|
| Testa | `data class AlbumHead(val family: FamilyId, val album: AlbumId, val device: DeviceId, val version: Long, val registryVersion: Long, val createdAt: Long, val segments: List<SegmentRef>)`; busta `DocType.SHARED_ALBUM_HEAD`, versione 3 | `NasLayout.albumHead(album, device, version)`, `albumSegment(album, device, hash)` |
| Voce | `data class AlbumEntry(val key: AlbumKey, val at: Long, val author: DeviceId, val account: String, val value: AlbumValue?)`; `sealed interface AlbumValue { Definition(title, folder, state, creatorAccount, nonce); Levels(history: List<LevelChange>); Cover(media, contentKey); Sort(code); Item(media, version, contentKey, path, position, addedBy); Heir(account, registryVersion) }` | `value = null` è la lapide |
| Codec | `object AlbumDocs { fun encodeHead(...): ByteArray; fun decodeHead(bytes, expected): Verdict<AlbumHead>; fun encodeSegment(entries): EncodedSegment; fun decodeSegment(bytes, ref, album): Verdict<List<AlbumEntry>> }` | tetti di A4 (§0.3); un'eccezione è un bug: tutto ciò che è ostile diventa un `Verdict` con il codice |
| Id | `object AlbumIds { fun derive(creatorAccount: String, nonce: ByteArray): AlbumId; fun matches(id, creatorAccount, nonce): Boolean }` | D7 |

**`core-albums` (P0 contratti)**
| Elemento | Firma | Note |
|---|---|---|
| Album | `sealed interface Album { val id: AlbumId; val title: String; val cover: CoverRef; val count: Int; data class Manual(...); data class Smart(rules: SmartRules, frozen: Boolean, ...); data class Shared(level: ShareLevel, creator: AccountRef, mine: Boolean, state: SharedState, ...) }` | `count` senza nascosti né non attestati (D9, D22) |
| Elenco | `interface Albums { val all: StateFlow<AlbumList>; fun items(id: AlbumId): Flow<AlbumTimelineView>; suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): AlbumId; suspend fun rename(id, title); suspend fun delete(id); suspend fun add(id, media: List<MediaId>): AddOutcome; suspend fun remove(id, media: List<MediaId>); suspend fun setCover(id, media: MediaId?); suspend fun setSort(id, sort: AlbumSort); suspend fun reorder(id, moved: MediaId, after: MediaId?) }` | `add` su un album condiviso restituisce `AddOutcome.NeedsShareConfirmation(plan)` (D12) |
| Regole | `data class SmartRules(val period: LocalDayRange?, val place: PlaceRule?, val text: String?, val examples: List<MediaRef>, val sources: Set<SmartSource>, val types: Set<MediaTypeRule>)`; `data class PlaceRule(val placeIds: List<Int>, val center: GeoPoint, val radiusKm: Int, val label: String)` | `SmartSource { MY_PHONE, MY_PRIVATE, COMMON, FAMILY }` |
| Interpretazione | `interface SmartNameInterpreter { suspend fun propose(title: String, today: LocalDate): RuleProposal }`; `data class RuleProposal(val rules: SmartRules, val alternatives: List<PlaceAlternative>, val periodSeries: List<LocalDayRange>, val notes: List<ProposalNote>)` | `ItalianQueryParser` + raffinamento sulle foto (D13); puro sulla JVM con le porte del catalogo |
| Motore | `interface SmartAlbumEngine { suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome; suspend fun preview(rules: SmartRules): SmartPreview }`; `data class SmartPreview(val confident: Int, val suggested: Int, val tooBroad: Boolean, val sample: List<MediaId>)` | D14–D16; mai un nascosto (filtro finale e candidati da `VisibleMedia`) |
| Scelte | `interface SmartChoices { suspend fun exclude(id, media); suspend fun accept(id, media); suspend fun freeze(id); suspend fun unfreeze(id) }` | voci D4 |
| Condivisi | `interface SharedAlbums { val visible: StateFlow<List<Album.Shared>>; suspend fun create(title, level: ShareLevel, media: List<MediaId>): SharePlanOutcome; suspend fun setLevel(id, level); suspend fun close(id, mode: CloseMode): ClosePlanOutcome; suspend fun leave(id); suspend fun inherit(id): InheritOutcome; fun allowed(id: AlbumId, op: AlbumOp, item: MediaId?): Boolean }` | `allowed` è la tabella del §2.4, la stessa che usa chi legge (`AlbumPermissions`, puro) |
| Replica | `interface SharedAlbumSync { suspend fun sync(budget: Duration): AlbumSyncReport }`; `object AlbumFusion { fun merge(contributions: List<VerifiedContribution>, registry: RegistryView): MergedAlbum }`; `object AlbumPermissions { fun check(entry: AlbumEntry, levels: LevelHistory, creator: AccountRef, heir: AccountRef?, previous: AlbumEntry?): PermissionVerdict }` | D8–D11; fusione commutativa e idempotente (test di proprietà) |
| Pubblicazione | `interface AlbumPublisher { suspend fun stage(album: AlbumId, entries: List<AlbumEntry>); suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome; val pending: StateFlow<Int> }` | testa ogni 5 minuti al più, accorpamento di 30 s (D6); dietro `AlbumWriteGate` (D30, D38) |
| Condivisione | `interface ShareRunner { val operations: StateFlow<List<ShareOpState>>; suspend fun plan(request: ShareRequest): SharePlan; suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): StartOutcome; suspend fun resumeAll(budget: Duration): RunResult; suspend fun cancel(op: ShareOpId) }`; `sealed interface ShareRequest { AddToShared(album, media, hiddenChoice); BringBack(album, media); FolderAdoption(album, files) }` | §2.5; `ShareConfirmation` con costruttore `internal`, emessa solo dal foglio di conferma (come `UserConfirmation`) |
| Porte | `fun interface AlbumMover { suspend fun move(items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): MoveTicket }`; `fun interface AlbumUploader { suspend fun upload(phone: List<PhoneItemRef>, dest: ShareFolder): UploadTicket }`; `interface TicketStatus { suspend fun status(ticket): TicketState }`; `fun interface SafeCopyRequester { suspend fun request(media: List<MediaId>) }`; `fun interface CatalogFacts { ... }` | legate da `:app` a `NasOperations`, `IncomingUploads`, guardiano, catalogo |
| Viaggi | `interface Trips { val proposals: StateFlow<List<TripProposal>>; suspend fun detect(budget: Duration): TripReport; suspend fun accept(id: TripId): AlbumId; suspend fun dismiss(id: TripId) }`; `data class TripProposal(val id: TripId, val kind: TripKind, val days: LocalDayRange, val label: String, val count: Int, val cover: MediaId)` | D18–D20; nessuna coordinata della casa fuori da `trips/` |
| Stato | `sealed interface AlbumsStatus { Ready; Recomputing(done, total); Sharing(ops); Waiting(reason: ShareWait); Problem(code) }` | numeri e codici |

**`core-nasops` (P4)**: `fun interface MoveCompletionSink { suspend fun published(op: OpId, item: OpItemId, source: NasItemRef, dest: NasItemRef, stamp: NasStamp, sha256: Sha256) }`, chiamata dal motore dopo il passo `PUBLISHED` e **prima** di `SOURCE_RECORDED`, ripetuta a ogni ripresa (idempotente); un errore della porta ferma l'elemento in `PUBLISHED` e si ritenta (mai la sorgente nel cestino prima che il catalogo conosca la destinazione). `NasOpsEngine` la riceve dal costruttore (null-object di default).

**`core-backup` (P4)**: `IncomingDestination.AlbumFolder(share, folder)` per `UploadKind.INCOMING`; `interface SafeCopyRequests { suspend fun requestNow(media: List<MediaId>) }` (mette i media in coda al guardiano anche se la copia del telefono non è appena sparita, D28).

**`core-library` (P0 firme, P8, P9)**: `LibraryFilter.EDITED`; `interface VisibleMedia { fun visible(ids: Collection<MediaId>): Set<MediaId>; val snapshot: StateFlow<TimelineSnapshot> }` (l'istantanea della timeline esiste già senza nascosti); `fun interface HiddenMask { suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> }`; `interface WidgetFeed { suspend fun next(size: Int): List<MediaId> }`; `class AlbumTimelineView : OrderedTimelineView` (F6, già nei contratti) per gli album in ordine personalizzato.

### 1.3 Aggiunte a moduli esistenti
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | `ShareLevel { ALL, ADD, VIEW }` (codici `all`, `add`, `view`), `AlbumKind`, `AlbumSort`, `TripKind`; `AlbumId.derived` | P0 |
| `core-personal` | costanti e lettori dei campi D4 (`PersonalFields.SMART_*`, `EXCLUDE`, `ACCEPT`, `FROZEN`, `SORT`, `SHARED_ORIGIN`, `COLLECTIONS_*`, `TRIP_DISMISSED`, `SHARED_ALBUM_LEFT`, `FULL_HASH_NIGHTLY`); `PersonalAlbums.addItem(albumId, mediaId, position, fp)` con l'impronta; `albums()` legge anche i campi nuovi | P0 (firme), P3 |
| `core-database` | schema v6 (D34); `MediaCatalog`: elementi per giorno e per sorgente (candidati degli album smart, indice su `local_day`, `place_id`, dispositivo), posizioni per parte (Doppioni), `attachMoved(media, location)` per la porta di completamento | P1 |
| `core-nasops` | `MoveCompletionSink` (sopra) | P4 |
| `core-backup` | `IncomingDestination.AlbumFolder`, `SafeCopyRequests` | P4 |
| `core-library` | `LibraryFilter.EDITED`, `VisibleMedia`, `HiddenMask`, `WidgetFeed` | P0 (firme), P8, P9 |
| `core-common` | `FeatureFlags.sharedAlbumsTestSharesOnly`; `DiagSection.ALBUMS`; `Phase7ArchitectureTest` | P0 |
| `feature-library` | Raccolte (P11), Utility e Doppioni ed Eliminati di recente (P8), Nascosti e Cartelle NAS (P9) | P8, P9, P11 |
| `feature-viewer` | voci ⋮ come dati (`viewerMenuEntries`): "Aggiungi ad album", "Rimuovi dall'album", "Imposta come copertina" (solo aperto da un album) | P10 |
| `feature-integrations` | "Aggiungi ad album" nella ricezione | P10 |
| `feature-settings` | Impostazioni → Privacy: "Nascosti" (anche con la sezione nascosta) | P9 |
| `feature-diagnostics` | codici del §2.8, sezione Album, lavori di condivisione e ricalcolo | P13 |
| `tools/` | `s25.ps1 -Mode share` (interruzioni sull'S25), `tools/chaos/share-pc` (banco dal PC sul NAS di prova), generatore di viaggi sintetici (`tools/fixtures/trips/`) | P5, P12 |

### 1.4 Cosa resta in `:app` (P13)
- **`AlbumsGraph`** in `app/.../albums/`: nasce con la famiglia `Ready`; `Albums`, `SmartAlbumEngine`, `Trips`, `CatalogAlbumProjection`
  vivono anche **senza NAS** (album personali e smart sul telefono e sulla replica); `SharedAlbums`, `SharedAlbumSync`,
  `AlbumPublisher`, `ShareRunner` con il `NasGraph`.
- **Porte**: `AlbumMover` → `NasOperations.plan/start` (con `OpRequest.Move` verso la cartella dell'album e `leaveBehind` per i
  nascosti lasciati fuori); `AlbumUploader` → `INCOMING` con `AlbumFolder`; `SafeCopyRequester` → guardiano; `MoveCompletionSink` →
  `MediaCatalog.attachMoved`; `HiddenMask` → `CatalogHidden`; `SearchEngine` della Fase 6 quando c'è, `NoSearchEngine` altrimenti.
- **Lavori** (`work/AlbumWork.kt`, con `runIfActive` e `StagedRun`): la sincronizzazione degli album condivisi nello stesso
  `IndexSyncWorker` (dopo indice e dati personali); `ShareWorker` (attività pesante, ripresa dal diario, anche come lavoro avviato
  dall'utente: UIDT come le operazioni della 4B, F4-D29); `SmartAlbumWorker` (ricalcolo a lotti dopo scansioni con novità, dentro
  `LibraryMaintenanceWorker`); rilevazione dei viaggi una volta per notte.
- **Navigazione**: sezioni nuove delle Raccolte, pagine degli album, editor, Utility, "Aggiungi ad album" da selezione, viewer, Cerca e
  ricezione.
- **Cancelli**: `AlbumWriteGate` = `IndexWriteRules` + `sharedAlbumsTestSharesOnly` + `ShareFence` in debug.
- **Azzeramento**: `AlbumsGraph.stop()` per primo; `AppLocalDataEraser` toglie `home_estimate`, proposte, replica degli album
  condivisi e diario della condivisione **solo a operazioni ferme** (un'operazione a metà si riprende o si annulla prima, come la 4B).

### 1.5 Confine con il filone grafico: stati e contratti
| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `AlbumListState` | `Loading`, `Ready(sections: mine, shared, smart; sort)`, `Empty` | Raccolte → Album, pagina "Tutti gli album" | G2 |
| `AlbumPageState` | `Ready(header, view, sort, permissions, banner)`, `Recomputing(progress)`, `Closed(notice)`, `Unavailable(reason)` | pagina dell'album | G2 |
| `SmartEditorState` | `Interpreting`, `Proposal(rules, alternatives, series, preview, notes)`, `TooBroad(preview, hints)`, `NoSearch` | editor delle regole | G2 |
| `SuggestionsState` | `None`, `Some(count, view)`; "Da condividere" per gli smart condivisi | pagina dell'album | G2 |
| `ShareSheetState` | `Plan(counts, bytes, folder, level, hiddenCount, notice)`, `Blocked(reason)`, `Confirming`, `Started(op)` | foglio di condivisione (`GalleryShareSheetContent`) | G2 |
| `ShareProgress` | per operazione: `Running(done, total)`, `Paused(reason)`, `WaitingLocked`, `Done`, `PartlyDone(failures)` | pagina dell'album, Attività | G2 |
| `PermissionInfo` | livello, chi è il creatore, cosa posso fare, la dichiarazione "li fa rispettare l'app" | intestazione e foglio "Permessi" | G2 |
| `CloseSheetState` | `Stop`, `Delete`, con "Riporta nelle mie cartelle (N miei)" | foglio del creatore | G2 |
| `TripsState` | `Proposals(list)`, `NoGps`, `Empty` | Raccolte → Viaggi | G2 |
| `UtilityState` | voci con conteggi; Doppioni: `Scanning`, `Groups(identical, similar)`, `Cleanup(plan)` | Raccolte → Utility | G2 |
| `RecentlyDeletedState` | telefono e NAS insieme, giorni rimasti, `GalleryTrashHint` (D-3) | Utility → Eliminati di recente | G2 |
| `CollectionsLayoutState` | sezioni con ordine e visibilità, modalità "Modifica" | Raccolte | G2 |

**Chi tocca cosa**
- La Fase 7 **non** modifica `core-designsystem`; il filone grafico non modifica contratti e ViewModel qui sopra.
- File condivisi a rischio: `feature-library/.../collections/CollectionsTab.kt` (P11 ne è il proprietario; P8 e P9 consegnano le
  loro voci come dati), `app/.../main/MainScaffold.kt` e `AppNavHost.kt` (solo P13), il menu del viewer (`viewerMenuEntries`, solo
  P10), `MediaCatalog.kt` (solo P1; gli altri chiedono le query nel report).
- Stringhe in file propri: `strings_albums_*.xml`, `strings_utility_*.xml`, `strings_trips_*.xml`.

## 2. Flussi

### 2.1 Album personali, Nascosti e Utility (7.1; P3, P8, P9)
**Album personali**
| Momento | Cosa | Dettagli |
|---|---|---|
| Creazione | "Nuovo album" (Raccolte, selezione, viewer, ricezione) → titolo → manuale o smart | id = `AlbumIds.derive(account, nonce)` anche per i personali (un album nato personale si può condividere senza cambiare id, D7); voci `definition` e `kind` |
| Aggiunta | selezione → "Aggiungi ad album" → scelta dell'album (i nascosti non si offrono fuori dai Nascosti; dai Nascosti l'album riceve il media ma non lo mostra finché è nascosto, D22) | una voce `item:<media>` con posizione e impronta per media, in una transazione; l'album si aggiorna subito dal catalogo locale (non aspetta la sincronizzazione) |
| Ordine | "Ordina" nella pagina: dal più vecchio, dal più recente, personalizzato (trascinamento) | posizioni a intervalli (1.000, 2.000, …) per inserire senza riscrivere tutto; ricompattazione rara, a lotti |
| Copertina | "Imposta come copertina" dal viewer aperto dall'album, o "Scegli copertina" | voce `cover` con impronta; automatica se nascosta, tolta o assente (D21) |
| Sincronizzazione | il giro dei dati personali esistente (accorpamento 30 s, `INDEX_SYNC`) | nessun lavoro nuovo; la proiezione (D5) aggiorna le tabelle v6 |
| Unione d'identità | due media diventano uno (F4-D7, F5-D36) | `MergeDao` sposta già elementi e copertine; P3 aggiunge le voci personali riscritte sull'id sopravvissuto alla prossima modifica, con la stessa impronta |
| Media sparito | il media esce dal catalogo (cancellato ovunque, area persa) | l'elemento resta nello stato e torna se il media torna (stessa impronta); l'album non lo conta |

**Nascosti** (D22, D23): il lavoro è di garanzia, non di funzione. P9 scrive `HiddenSurfaces` (registro delle superfici) e il test
di proprietà; il widget di prova (`app/src/debug/`, `AppWidgetProvider` con `RemoteViews` sopra `WidgetFeed`, mai nel dex di release: `check.ps1`);
l'avviso E-H2 al momento di nascondere un media dell'area comune o di un album condiviso; la riga "N elementi nascosti" in Cartelle
NAS (I8). Le superfici del registro:

| Superficie | Fonte | Prova |
|---|---|---|
| Libreria, filtri, intestazioni e conteggi | istantanea della timeline (già senza nascosti) | test esistenti + proprietà |
| Raccolte (conteggi di ogni sezione) | `VisibleMedia` | proprietà |
| Album manuali, smart, condivisi; copertine; "Suggerite"; "Da condividere" | `VisibleMedia` + `HiddenMask` finale | proprietà, E-H5, E-H6 |
| Viaggi ed eventi (rilevazione, conteggi, copertine) | candidati da `VisibleMedia` | proprietà, E-H7 |
| Utility e Doppioni | `VisibleMedia` | proprietà |
| Ricerca, suggerimenti, "Trova simili" | F6-D29 | test della Fase 6 |
| Selettore per le altre app, provider di documenti | esistente (Fase 5) | test esistenti |
| Widget di prova | `WidgetFeed` | proprietà + F73 |
| Cartelle NAS | riga senza nomi | P9 |
| Viewer aperto da un album | la vista dell'album | proprietà |

**Utility** (D24–D27)
| Voce | Cosa mostra | Note |
|---|---|---|
| Screenshot, Video, WhatsApp | i filtri esistenti | `CollectionKind` diventa la lista dell'Utility |
| Modificate | `LibraryFilter.EDITED` | media con almeno una versione modificata |
| Doppioni | due schede: "Identici" e "Simili" | §2.1.1 |
| Eliminati di recente | cestino di Android + cestini del NAS | dietro `HiddenAccessGate` (come oggi); D-3 dichiarato |
| Libera spazio | Fase 5 | invariato |

#### 2.1.1 Doppioni (P8)
| Passo | Identici | Simili |
|---|---|---|
| Chi li trova | il catalogo: media con più posizioni **dalla stessa parte** (telefono, area privata propria, area comune), con hash completo uguale; i video senza hash completo sono "da verificare" finché `FULL_HASH` non decide (D27) | gruppi di scatti dello stesso giorno a meno di 10 minuti, fuori dalle copie simili e dalle raffiche già raggruppate: coseno dei vettori ≥ soglia `Sdup` (calibrata in P8 sul set sintetico, valore di partenza 0,95); senza vettori la firma visiva OKLab (F5-D36) con la soglia "simile ma diverso" |
| Quando | dopo ogni scansione con novità (query sul catalogo, a lotti) | `LibraryMaintenanceWorker`, a lotti per giorno, solo media con vettore o firma |
| Pulizia guidata | per ogni gruppo una copia da tenere per parte (ordine di D25), le altre preselezionate; "Rivedi" mostra ogni copia con la posizione | per ogni gruppo la migliore preselezionata da **tenere** (ordine di D25); nessuna preselezione "da eliminare" sui preferiti e sugli elementi di un album |
| Esecuzione | piano di `core-nasops` (`Trash`) per le copie del NAS e dialogo di sistema per quelle del telefono, con il riepilogo "Eliminare 41 copie (3,2 GB)? Restano nei cestini 30 giorni" | il foglio di eliminazione esistente ("Solo dal telefono" / "Ovunque") |
| Mai | l'ultima copia privata; una copia in un'area senza permesso (E-U1); una copia del telefono non al sicuro senza l'avviso E-D14 | eliminare senza scelta dell'utente |

### 2.2 Album smart (7.2; P6)
**Creazione**
1. L'utente scrive il titolo. `SmartNameInterpreter.propose` passa il titolo a `ItalianQueryParser` con il dizionario dei luoghi della
   Libreria (solo media visibili): "Oslo 2026" → luogo Oslo (alternative omonime), anno 2026; "Natale 2025" → 24/12/2025–6/1/2026
   (la festa la sa l'analizzatore); "Estate in Puglia" → regione Puglia, ogni estate.
2. **Raffinamento del periodo** (D13): con un luogo e un periodo lungo (anno, stagione, "ogni anno"), le serie di giorni con foto
   proprie geolocalizzate entro il raggio; ogni serie ± 1 giorno. Una serie = periodo proposto; più serie = l'editor le mostra tutte
   (E-AL12). Senza foto nel luogo: periodo intero del titolo e una nota.
3. **Raggio**: dalla precisione del luogo (`PlacePrecision`): città 25 km, vicino 15 km, regione = confine `admin1`, paese = codice
   del paese; modificabile (5–300 km).
4. L'editor mostra regole e **anteprima** (`preview`: quanti sicuri e quanti suggeriti, 12 miniature) prima di creare; ogni regola si
   cambia o si toglie; "Aggiungi foto d'esempio" apre il selettore (al più 6).

**Classificazione** (D14), per ogni candidato (media visibile, non escluso, una cella per gruppo):
| Regola | Condizione | Classe |
|---|---|---|
| A | giorno locale nel periodo **e** GPS entro il raggio (o nella regione o paese) **e** sorgente ammessa | sicuro |
| B | nessun GPS, **stesso dispositivo** di origine (colonna derivata `origin_device` di v6: questo dispositivo per i media del telefono, la cartella `Backup/<dispositivo>/` per i backup dell'app, altrimenti marca e modello della fotocamera dall'EXIF) di un sicuro A dello stesso giorno, fra due sicuri A a meno di 6 ore da ciascuno | sicuro |
| C | album **senza luogo**: giorno nel periodo **e** classe "sicuri" del motore per la descrizione (F6-D27); oppure, senza periodo né luogo, solo la descrizione sicura (precisione dichiarata della ricerca, L50) | sicuro |
| S1 | nel periodo, senza GPS, altro dispositivo o familiare (E-AL3, E-AL17) | suggerito |
| S2 | nel periodo, screenshot o registrazione dello schermo (E-AL4) | suggerito |
| S3 | nel periodo, GPS fra 1 e 2 volte il raggio | suggerito |
| S4 | descrizione o esempi "meno probabili" (nel periodo, se c'è) | suggerito |
| S5 | nel periodo, nessun GPS, stesso dispositivo ma fuori dalla finestra di B (E-AL11) | suggerito |
| — | nascosto, escluso, fuori periodo senza descrizione, sorgente non ammessa | fuori |

- **Accetta** (suggerito → voce `accept:`) ed **Escludi** (voce `exclude:`) valgono per sempre (E-AL5, E-AL13).
- **Ricalcolo** (D16): le novità del catalogo (scansione, sincronizzazione dell'indice, backup) segnano i giorni toccati; il lavoro
  ricalcola solo gli album smart non congelati che coprono quei giorni, a lotti di 2.000 candidati; obiettivo < 2 s per album su 100
  mila elementi (F91). Un media arrivato mesi dopo entra o va in Suggerite (E-AL7) e il conteggio "Nuove: 12" compare sull'album.
- **Congela** (D16): gli elementi diventano voci `item:` con impronta, in una transazione; `frozen` = vero.
- **Senza motore di ricerca** (D15): le regole C e S4 non agiscono; l'editor lo dice; tutte le altre sì.
- **Obiettivi** (7.2, misurati in F88): sui 3 viaggi reali scelti dall'utente, precisione dei sicuri ≥ 98% e copertura (sicuri +
  suggeriti) ≥ 90% delle foto del viaggio.

### 2.3 Viaggi ed eventi (7.3; P7)
| Passo | Cosa | Dettagli |
|---|---|---|
| Casa (D18) | celle di ~1 km (griglia di latitudine e longitudine, 0,01°) delle foto proprie con GPS degli ultimi 12 mesi, peso = giorni distinti con foto fra le 19 e le 8 locali | "casa" = cella più pesante e sue 8 vicine se ≥ 15% dei giorni con GPS; luoghi abituali ≥ 10%; raggio di casa = distanza al 90° percentile delle foto serali attorno al centro (minimo 2 km) |
| Giorni lontani | per ogni giorno locale con foto proprie geolocalizzate: distanza massima e mediana da casa e dai luoghi abituali | "lontano" se la mediana supera max(50 km, 3 × raggio di casa) |
| Viaggi (D19) | serie di giorni lontani con buchi ≤ 2 giorni senza foto; ≥ 2 giorni, oppure 1 giorno con ≥ 20 foto oltre 100 km | luogo del viaggio = luoghi `CITY`/`NEAR` delle foto, riassunti con `PlaceNames.summary` |
| Eventi (D19) | finestre di ≤ 4 ore con ≥ 30 media propri, ritmo ≥ 5 volte la media dei giorni con foto, fuori dai viaggi | anche senza GPS (E-AL16) |
| Proposte (D20) | `trip_proposal` locale; impronta = SHA-256(inizio, fine, luoghi); scartate da `trip_dismissed` | dal più recente; 2 anni nella prima schermata, "Mostra più vecchi" |
| Accetta | album smart con periodo = giorni del viaggio, luogo = luoghi del viaggio (centro e raggio che li coprono), sorgenti tutte | le foto dei familiari e senza GPS arrivano come suggerite |
| Quando | una volta per notte (`LibraryMaintenanceWorker`) e dopo un primo backup o una prima scansione | < 5 s su 100 mila elementi (F91) |
| Privacy | coordinate e casa solo in `trips/` e in `home_estimate`; mai in log, Diagnostica, dati personali, indice | test d'architettura e sui log (§1.1) |

**Obiettivo** (7.3, F89): sui dati veri dell'utente, i viaggi principali degli ultimi 2 anni (quelli che l'utente elenca prima di
guardare) sono tutti fra le proposte.

### 2.4 Permessi degli album condivisi (proposta 7, D10, D11; P2)
"Creatore" = ogni dispositivo approvato dell'account del creatore, o dell'erede dopo la voce `heir`. "Altri" = ogni altro account
della famiglia. **Sempre permesso a tutti** (sono azioni personali o di sola lettura): vedere, aprire, scaricare sul telefono,
condividere verso altre app, preferito e nascosto (personali), "Non mostrare più" (lasciare l'album), aggiungere a un proprio album
personale.

| Operazione | Tutti possono tutto | Gli altri aggiungono | Gli altri guardano | Chi controlla (scrittore / lettore) |
|---|---|---|---|---|
| Aggiungere media (spostamento confermato, D12) | tutti | tutti | solo il creatore | voce `item:` scartata se non permessa all'ora `at` |
| Togliere dall'album un elemento aggiunto da sé | tutti | tutti | solo il creatore | lapide `item:` di chi l'aveva aggiunto |
| Togliere dall'album un elemento aggiunto da altri | tutti | solo il creatore | solo il creatore | lapide confrontata con `addedBy` della voce precedente |
| Eliminare il file (cestino del NAS, E-D8) | tutti | il creatore e chi l'ha aggiunto | solo il creatore | solo scrittore (sul NAS non c'è un "permesso" da leggere: L57); la voce diventa lapide |
| Nuova versione nell'area comune (modifica, correzione, F5-D35) | tutti | il creatore e chi l'ha aggiunto | solo il creatore | solo scrittore; altrimenti la modifica nasce come media nuovo nell'area privata (E-E9) |
| Rinominare l'album (titolo e cartella) | tutti | solo il creatore | solo il creatore | `definition.title`; la cartella si rinomina con un'operazione locale confermata |
| Copertina, ordine | tutti | solo il creatore | solo il creatore | voci `cover`, `sort` |
| "Riporta nelle mie cartelle" un proprio media | sì | sì | sì, se aggiunto quando il livello lo permetteva | come "togliere un elemento aggiunto da sé" + spostamento confermato |
| Adottare file aggiunti dal PC nella cartella (D39) | tutti | tutti | solo il creatore | come "aggiungere" (nessuno spostamento: sono già lì) |
| Cambiare il livello | solo il creatore | solo il creatore | solo il creatore | voce `level` solo dal creatore |
| Smetti di condividere, Elimina l'album | solo il creatore | solo il creatore | solo il creatore | `definition.state` solo dal creatore |
| Ereditare (E-SH11) | admin, solo con il creatore uscito dalla famiglia | idem | idem | voce `heir` valida solo se il registro lo conferma |

- Il livello **predefinito** alla creazione è quello di Q2 (raccomandato "Gli altri aggiungono").
- Chi ha aggiunto ogni elemento (`addedBy`, un account) serve ai permessi in ogni caso; **mostrarlo** nelle info ("Aggiunta da …")
  dipende da Q6 (raccomandato sì).
- Ogni pagina e foglio di un album condiviso mostra il livello e la frase "I permessi li fa rispettare l'app: dal PC chi accede
  all'area comune può fare tutto" (stringa esistente `share_footnote`).
- **Lettura**: `AlbumPermissions.check` è una funzione pura usata **sia** da chi scrive (che non offre l'azione) **sia** da chi legge
  (che scarta la voce): stessa tabella, stesso codice, un test generato dalla tabella per ogni riga e ogni livello (P2).
- **Ora delle voci**: `at` è dichiarata da chi scrive; chi legge rifiuta una voce nuova (assente nella testa precedente dello stesso
  dispositivo) con `at` anteriore alla `createdAt` di quella testa meno 2 minuti. Un dispositivo compromesso che non è mai stato
  letto prima può comunque retrodatare entro i livelli che ha avuto (L59).

### 2.5 Condivisione e spostamento nell'area comune (7.4; P2, P4)
**Sul NAS**
```
<area comune>/<cartella degli album condivisi, Q1>/<titolo>/          file dei media (nomi originali; alternativi se occupati)
<area comune>/.mycloudgallery/albums/<id album>/<id dispositivo>/head/<versione>   testa firmata, tipo 6, versione 3
<area comune>/.mycloudgallery/albums/<id album>/<id dispositivo>/seg/<impronta>    segmenti MCGB classe album (17)
```
Un media già nell'area comune (in un altro album o in un'altra cartella) **non si sposta**: la voce `item:` porta il suo percorso
(E-SH11). La cartella dell'album nasce con `OpRequest.CreateFolder` nello stesso piano.

**Il flusso "Condividi" (album nuovo o esistente, o "Aggiungi" a un album condiviso)**
| Passo | Chi | Cosa | Ripresa e guasti |
|---|---|---|---|
| 1. Piano | `ShareRunner.plan` (P4) | per ogni media: dove sta (telefono soltanto, area privata propria, area comune, area di altri), nascosto o no, dimensione; esclusi: aree di altri (non aggiungibili), nascosti se «Lascia fuori» (Q3), media già nell'album; spazio richiesto sull'area comune (dimensione + 1 GiB, F4); cartella e nome libero; `SharePlan` con impronta | nulla scritto; un piano scade dopo 10 minuti (come le conferme) |
| 2. Conferma | foglio (P10) | "N foto e M video (X GB) verranno spostati nell'area comune, nella cartella «…», e li vedranno tutti i familiari"; livello (creazione); nascosti (D12); dichiarazione dei permessi; `ShareConfirmation` | senza conferma non parte nulla; recinzione D30 |
| 3. Diario | `album_share_op` + `album_share_item` (`PLANNED`) **prima** di ogni azione; definizione (album nuovo) messa in coda al pubblicatore come `active` senza elementi | la morte del processo qui lascia un'operazione `PLANNED`, ripresa o annullabile |
| 4. Trasferimenti | `AlbumMover` (area privata → cartella dell'album: un `OpRequest.Move` con `leaveBehind` per i nascosti lasciati fuori, conferma emessa da `ConfirmationIssuer` con lo stesso piano) e `AlbumUploader` (`INCOMING` verso la cartella per i media solo sul telefono, E-SH1); il diario registra gli id (`MoveTicket`, `UploadTicket`) → `TRANSFERRING` | i due motori esistenti fanno copia lato server, verifica SHA-256, pubblicazione esclusiva, sorgente nel cestino; si riprendono dai loro diari |
| 5. Completamento | `MoveCompletionSink` / fine del caricamento → `attachMoved`: lo **stesso** `MediaId` ottiene la posizione nell'area comune (D29) → `IN_COMMON` | idempotente; prima che la sorgente vada nel cestino (§1.2) |
| 6. Voce | `item:` (posizione, chiave di contenuto, percorso, `addedBy`) messa nel pubblicatore → `ENTRY_STAGED`; testa pubblicata (al più ogni 5 minuti) → `PUBLISHED` | il pubblicatore è idempotente per (album, chiave, valore); dopo la morte si ripubblica lo stato intero |
| 7. Al sicuro | media propri senza copia sul telefono → `SafeCopyRequester` (D28) → `DONE` | il guardiano ha il suo diario e i suoi tetti (20 GB per giro) |
| Errori | file cambiato (E-SH18), spazio finito (E-SH8), file bloccato dal PC, congelamento (E-SH19), NAS irraggiungibile | elemento `PAUSED` con il motivo o `FAILED` con "Riprova"; **mai** una voce pubblicata per un file che non è nell'area comune |

**Stato visibile**: per l'utente che condivide il media **non cambia posto in app** (stessa cella, stesse info, badge "Condiviso"
quando è `IN_COMMON`); un elemento ancora in viaggio si vede nell'album con "In arrivo" solo sui suoi dispositivi (D9). Per gli altri
l'elemento compare quando la testa arriva e il loro catalogo vede il file (scansione dell'area comune o indice).

**Invarianti** (provate dal banco del §5.5): nessun file a metà visibile; nessun file doppio nell'area comune; nessuna perdita (la
sorgente va nel cestino solo dopo la verifica e dopo che il catalogo conosce la destinazione); hash verificato al 100%; nessuna voce
per un file assente dall'area comune; nessuno spostamento senza un'operazione confermata nel diario; dopo le riprese ogni elemento è
`DONE`, `FAILED` con motivo o `CANCELLED`, e la Libreria ha lo stesso numero di media di prima.

**Ricevere un album condiviso** (`SharedAlbumSync`, nel giro dell'indice)
1. Elenca `albums/` dell'area comune; per ogni album e dispositivo legge l'ultima testa valida (serie per nome, `MAX_JUNK_RUN`),
   verifica (D8), scarica i segmenti nuovi (tetti), aggiorna `shared_album_entry`.
2. Fonde i contributi per chiave (`AlbumFusion`), dopo aver scartato le voci non permesse (`AlbumPermissions`, §2.4).
3. Attesta gli elementi sul catalogo (D9); proietta in `album`/`album_item` (tipo condiviso).
4. Album chiusi o eliminati: avviso una volta ("«…» non è più condiviso", E-SH7); album lasciati (`shared_album_left`): non mostrati.

### 2.6 Smetti di condividere, Elimina, Riporta, eredità (P4)
| Azione | Chi | Effetto sul NAS | Effetto in app |
|---|---|---|---|
| Smetti di condividere | creatore o erede | voce `definition.state = closed`; nessun file toccato | per gli altri sparisce (Q5); per il creatore diventa personale (voci `ALBUM` create dagli elementi attestati) |
| Elimina album | creatore o erede | `state = deleted`; nessun file toccato | sparisce per tutti; avviso agli altri |
| Riporta nelle mie cartelle (dal foglio di chiusura o per elemento) | proprietario del media (`addedBy` = proprio account e `shared_origin` presente) | piano `Move` area comune → area privata, verso `shared_origin` (o `Backup/<dispositivo>/AAAA/MM/`), conferma con conteggio; copia comune nel cestino comune 30 giorni; lapide `item:` se l'album è ancora attivo | il media resta lo stesso (D29); esce dall'album per gli altri |
| Ereditare | dispositivo admin, creatore senza dispositivi approvati né dismessi | voce `heir` | l'admin vede "Sei il nuovo responsabile di «…»" e i comandi del creatore |
| Lasciare l'album | chiunque non sia il creatore | nessuno | `shared_album_left` (personale) |

### 2.7 Raccolte (7.5; P11)
- **Ordine predefinito**: Album · Album smart · Viaggi · (Persone, Fase 8) · (Ricordi, Fase 9) · Cartelle NAS · Telefono · Da tenere al
  sicuro · Utility · Nascosti.
- **Album**: i miei e i condivisi insieme (badge "Condiviso" e il nome del creatore per quelli degli altri), con "Tutti gli album"
  oltre i primi 6. **Album smart**: separati perché si comportano diversamente (Suggerite). Ogni sezione ha il suo `CollectionsGroup`
  (dati, non composable), costruito da chi la possiede (P8, P9, P10) e consegnato a `CollectionsTab` (P11).
- **"Modifica Raccolte"**: trascinamento delle sezioni e interruttore "Mostra"; **Album** non si nasconde (è il modo di arrivare agli
  album); salvataggio nelle voci `collections_order` e `collections_hidden:<sezione>`.
- Tablet: le sezioni in due colonne oltre 840 dp (`WindowSizeClass`), stesso ordine.

### 2.8 Diagnostica e codici (P13)
| Codice | Quando | Messaggio e azione |
|---|---|---|
| `ALBUM_CONTRIB_REJECTED` | contributo d'album rifiutato (firma, id, tetti, campi) | conteggio per motivo; nessuna azione |
| `ALBUM_ENTRY_DENIED` | voce scartata per permesso | conteggio; nessuna azione |
| `ALBUM_ITEM_UNATTESTED` | elemento senza file nell'area comune | conteggio |
| `SHARE_PAUSED_SPACE`, `SHARE_PAUSED_FROZEN`, `SHARE_WAITING_LOCKED`, `SHARE_SOURCE_CHANGED` | spostamento fermo | "Riprova" o attesa |
| `SHARE_SAFE_COPY_QUEUED` | guardiano chiesto dopo una condivisione | — |
| `SMART_TOO_BROAD`, `SMART_NO_SEARCH` | regole larghe; descrizione senza motore | nell'editor |
| `TRIP_NO_GPS` | nessuna casa stimabile | nella sezione Viaggi |
| `ALBUM_PROJECTION_PENDING` | elementi personali non ancora risolti | conteggio |

### 2.9 Cosa serve dalla Fase 6 e cosa la Fase 7 predispone
**Dalla Fase 6** (contratti già scritti da P0; implementazioni da P5 e P6)
| Serve | Uso | Senza (oggi) |
|---|---|---|
| `QueryParser` (P7, fatto) | interpretazione del nome (D13) | — |
| `SearchEngine.search` con chip di vincolo e `semantic` | regole C e S4 (descrizione) | regole C e S4 spente, l'editor lo dice |
| **Aggiunta a P6**: `suspend fun similarToAll(examples: List<MediaId>, within: CandidateFilter?): SearchResults` (media normalizzata dei vettori degli esempi, stessa soglia di "Trova simili") | foto d'esempio | esempi ignorati, detto nell'editor |
| **Aggiunta a P6**: `SearchRequest.within: CandidateFilter?` (giorni o insieme di media) e nessuna voce nella cronologia per le richieste degli album | valutare solo i candidati del periodo; latenza | il motore di riferimento filtra dopo |
| Vettori per chiave di contenuto (P5) e accesso di sola lettura `VectorLookup` | Doppioni "simili" (D25) | firma visiva OKLab |

La richiesta va nel report di P0 come "modifica ai contratti degli altri" e l'orchestratore la gira a P6 della Fase 6; finché
non c'è, `core-albums` usa un adattatore sopra `search` e filtra dopo (stesso risultato, più lento).

**Per le fasi successive**
| Pezzo | Come lo usano |
|---|---|
| Viaggi ed eventi, casa locale | Fase 9: ricordi ("Un anno fa a Oslo") e mappa |
| Regole degli album smart | Fase 8: regola "persone" (i volti restano nell'area privata, proposta 13) |
| `WidgetFeed` e il registro `HiddenSurfaces` | Fase 10: il widget vero si aggancia al registro |
| Album condivisi e permessi | Fase 10: "Aggiungi ad album" da più app; trascinamento sul tablet |

### 2.10 Stima dei costi
- **Album personali**: ~150 byte per elemento nei segmenti personali; 5.000 elementi ≈ 750 KB nell'area privata, una volta.
- **Album condivisi**: ~300 byte per elemento per contributo; un album da 1.000 elementi ≈ 300 KB per dispositivo che contribuisce.
- **Spostamento**: copia lato server ~39 MB/s misurata (F16-PC): 4 GB ≈ 2 minuti di copia più la verifica (lettura completa,
  ~110 MB/s sul NAS) ≈ 5 minuti; i media del telefono al ritmo del backup (Wi-Fi di casa).
- **Album smart**: ricalcolo incrementale < 2 s per album; rilevazione dei viaggi < 5 s sulla Libreria intera (JVM: < 1 s su 100 mila
  righe sintetiche).

## 3. Alternative scartate

| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Dove stanno gli album personali | file nell'area privata (un JSON per album); voci dei dati personali; solo locale | **Voci dei dati personali** (D3): sincronizzazione, firme, fusione e privacy già fatte e provate; un file per album andrebbe riscritto per intero a ogni modifica e due dispositivi si sovrascriverebbero |
| Campi degli album smart | un tipo `PersonalKind` nuovo (`smart_album`); un solo valore JSON con tutte le regole; un campo per regola dentro `ALBUM` | **Un campo per regola dentro `ALBUM`** (D4): un tipo nuovo fa scartare i segmenti alle app vecchie; un solo valore perderebbe modifiche contemporanee di due regole e supera i 1.024 caratteri con gli esempi |
| Definizione dell'album condiviso | un file unico riscritto (sovrascrittura); un file per album e per dispositivo; un registro delle operazioni (log) | **Un contributo per album e dispositivo con voci per chiave** (D6, D7): nessuna sovrascrittura possibile (`publishExclusive`), fusione commutativa già provata; un log crescerebbe per sempre e andrebbe compattato da qualcuno |
| Versione del formato | estendere la v1; usare la v2 (oggi solo `ai`); v3 propria | **v3 solo per `albums/`** (D6): la v1 è già scritta sul NAS di prova, la v2 ha un significato già documentato; un'app della Fase 6 rifiuta la v3 se mai aprisse `albums/` |
| Creatore dell'album | il dispositivo che scrive per primo; l'`at` più vecchio; id legato all'account | **Id legato all'account** (D7): l'`at` è dichiarato da chi scrive e si può retrodatare; il dispositivo si perde, l'account resta |
| Permessi | dal NAS (ACL SMB per cartella); dall'app; nessuno | **Dall'app** (proposta 7, D10): le ACL del WD si gestiscono dal pannello e non distinguono "aggiungi" da "modifica"; dal PC l'app non può impedire nulla e lo dichiara (L57) |
| Chi controlla i permessi | solo chi scrive; solo chi legge; entrambi | **Entrambi, con lo stesso codice** (D10): solo lo scrittore lascerebbe passare un'app difettosa o compromessa; solo il lettore farebbe vedere azioni che poi spariscono |
| Riconoscere gli elementi | dall'indice dell'area comune (attestazione F6-D7); dal catalogo di chi legge; dalla cartella | **Catalogo di chi legge** (D9): funziona con F4-D31 acceso; la cartella non basta (E-SH11, un file in due album) |
| Dove mettere i file condivisi | una cartella per album; una cartella unica "Condivisi/AAAA/MM"; lasciarli dove sono (solo per i media già nell'area comune) | **Una cartella per album** (E-SH10: chi non ha l'app la trova dal PC), con il riferimento per i media già nell'area comune (E-SH11); dove sta la cartella lo sceglie l'utente (Q1) |
| Condividere = copiare | copia nell'area comune e originale privato al suo posto; spostamento | **Spostamento** (piano 7.4, §2): lo chiede il piano ed evita doppioni; la copia privata la rifà il guardiano quando serve (D28) |
| "Al sicuro" dopo la condivisione | copia privata sempre (Q4 (a) della Fase 5); solo quando sparisce la copia del telefono (Q4 (b)); Q4 (b) più il caso del media già assente | **Q4 (b) con il caso del media già assente** (D28): rispetta la risposta dell'utente e chiude il buco del §0, punto 12 |
| Catalogo dopo lo spostamento | aspettare la scansione; porta di completamento | **Porta di completamento** (D29): E-SH2 "nessun elemento mancante in app" anche nei minuti fra la pubblicazione e la scansione |
| Album smart condiviso | nuove foto spostate da sole; "Da condividere" con un tocco; solo album congelati condivisibili | **"Da condividere"** (D17, Q4): nessuno spostamento senza conferma, e l'album resta vivo |
| Interpretare il nome | un modello linguistico; l'analizzatore della Fase 6 | **Analizzatore della Fase 6** (D13): già fatto, deterministico, senza rete né modelli |
| Sicuri degli album smart | solo il motore di ricerca; regole di data e GPS; entrambi | **Regole forti per i sicuri, motore per i suggerimenti** (D14): il 98% di precisione lo danno data e GPS; la ricerca dichiara il 90% (L50) |
| "Casa" | indirizzo chiesto all'utente; posizione del telefono; dedotta dalle foto, solo in locale | **Dedotta dalle foto, solo in locale** (D18): nessun permesso di posizione, nessun dato che esce dal dispositivo (C19) |
| Proposte di viaggio | notifica; sezione delle Raccolte; niente finché non si crea un album | **Sezione Viaggi** (D20): le notifiche sono della Fase 9 |
| Doppioni simili | firma visiva OKLab; vettori; pHash | **Vettori con ripiego sulla firma visiva** (D25): nessun algoritmo nuovo; pHash avrebbe aggiunto una terza firma |
| Pulizia dei doppioni | automatica; guidata con preselezione; solo elenco | **Guidata con preselezione e cestino** (D25): "l'app non cancella mai definitivamente da sola" (§4 del piano) |
| Eliminati di recente | due pagine (telefono, NAS); una pagina | **Una pagina** (D26): un posto solo dove cercare; D-3 detto chiaramente |
| Widget di prova | aspettare la Fase 10; un widget di prova solo debug sopra il contratto del widget | **Widget di prova** (D22): il "Fatto quando" lo chiede, e il contratto resta per la Fase 10 |
| Nascosti e blocco dell'app | aprire i Nascosti con lo sblocco dell'app; sblocco separato | **Separato, stesso autenticatore** (D23): chi presta il telefono sbloccato con l'app aperta non deve trovare i Nascosti aperti |
| Modulo | dentro `core-library`; dentro `core-personal`; modulo nuovo | **`core-albums`** (D2): `core-library` è la base delle viste e non deve vedere trust né personal; un modulo proprio tiene i recinti verso nasops e backup |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
P0 fondamenta, contratti, recinti, documenti ──┬── P1 database v6 ─────────────────────────────────┐
                                                ├── P3 album personali (dati, proiezione) ─────────┤
                                                ├── P8 Utility, Doppioni, Eliminati di recente ────┤
                                                ├── P9 Nascosti su ogni superficie ────────────────┼── M1 (integrazione minima)
                                                ├── P10 schermate degli album ─────────────────────┤
                                                ├── P11 Raccolte ──────────────────────────────────┤
                                                ├── P2 formato firmato e permessi ─────────────────┤
                                                ├── P4 condivisione e spostamento (dopo P2) ───────┤
                                                ├── P5 banco delle 500 interruzioni (dopo P4) ─────┼── M2
                                                ├── P6 album smart ────────────────────────────────┤
                                                ├── P7 viaggi ed eventi ───────────────────────────┤
                                                └── P12 valutazione (dopo P6, P7) ─────────────────┴── P13 integrazione ── M3
                                                     ── check.ps1 (-Bench) + security-review + verifica funzionale ── (Q7) F93
```
- Dopo P0 tutti partono **insieme** sui contratti, sui fake fedeli (§5.2) e sull'esecutore di riferimento (`InMemoryAlbums`,
  `InMemoryShareRunner`); si incontrano in P13.
- **P4 aspetta P2** per il pubblicatore vero (fino ad allora usa `FakeAlbumPublisher`); **P5 aspetta P4** per l'orchestratore vero
  ma scrive subito gli invarianti e il banco sull'esecutore di riferimento.
- **P6** lavora con `InMemorySearchEngine` della Fase 6; quando P6 della Fase 6 consegna, il test di parità del §5.2 si rifà sul
  motore vero (nessun codice da cambiare).
- **M1** è un'integrazione parziale dell'orchestratore dopo P1, P3, P8, P9, P10, P11: album personali, Utility e Nascosti sull'S25.
- **Prima di M2**: F29 della Fase 5 (stesso cammino di spostamento e guardiano) e il tablet con il profilo `test2` aggiornato.

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Sull'S25 (debug, `test`) album personali con ordine e copertina, sincronizzati; Utility completa con Doppioni ed Eliminati di recente unificati; nessun nascosto su nessuna superficie, widget di prova compreso | F72–F78 | P0, P1, P3, P8, P9, P10, P11 (+ integrazione minima) |
| **M2** | Album condiviso creato sull'S25 nelle share di prova e visto dal profilo `test2` del tablet; tre livelli rispettati; "Smetti di condividere" e "Riporta"; finti album ignorati; banco delle 500 interruzioni | F79–F86, F92 | + P2, P4, P5 |
| **M3** | Album smart e viaggi sui dati veri: ≥ 98% dei sicuri corretti, ≥ 90% delle foto inserite o suggerite su 3 viaggi; viaggi degli ultimi 2 anni proposti; Raccolte ordinate e nascoste su telefono e tablet | F87–F91 | + P6, P7, P12, P13 |
| Chiusura | Revisione di sicurezza e verifica funzionale; checklist (§6.3); screenshot approvati; con il consenso (Q7) gli album condivisi sulle share vere | `check.ps1`, `check.ps1 -Bench`, `security-review`, verifica funzionale, F93 | — |

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–6)
- **Mai** toccare file fuori dal proprio perimetro. `settings.gradle`, `build.gradle`, `versions-app.gradle`, manifest, `check.ps1`,
  `MediaCatalog.kt`, `CollectionsTab.kt`, `MainScaffold.kt`, `AppNavHost.kt` solo dai pacchetti indicati al §1.5 e al §4.4.
- **Nessuna azione distruttiva da dati remoti** (C10, E-S11): ogni pacchetto che legge definizioni d'album, indice, cestini o cartelle
  ha il test "un dato remoto falso non produce nessuna chiamata a `AlbumMover`, `AlbumUploader`, `NasOperations.start`" (porte finte
  che falliscono se chiamate).
- **Conferme**: `ShareConfirmation` e `UserConfirmation` hanno costruttori `internal`; nessun pacchetto ne costruisce una fuori dal
  foglio di conferma (test d'architettura).
- **Nascosti**: ogni superficie nuova si registra in `HiddenSurfaces` e ha il test "un nascosto perfetto non esce mai" (D22).
- **Ora**: ora del NAS per le voci, giorni locali dello scatto per i periodi, orologio monotono per durate; `Today` per "oggi".
- **Log**: mai titoli, cartelle, luoghi, casa, coordinate, descrizioni, id, account; solo numeri e codici; `toString()` sicuri.
- **Ogni passo in più tappe è idempotente** e ha il test "guasto a ogni chiamata" (`FaultPlan` prima e dopo) e la morte del processo
  a ogni salvataggio.
- **F4-D31, F4-D33 (`ShareFence`), D30 (`sharedAlbumsTestSharesOnly`) e la guardia delle prove dal PC (F4-D30)** valgono in ogni prova.
- **Fake fedeli con test di parità** (§5.2).
- Test JVM o Robolectric per ogni modulo; screenshot (solo dove c'è UI) in `docs/screenshots/7-<pacchetto>/`, chiaro e scuro,
  telefono e tablet, testo a 1,3x; TalkBack e bersagli da 48 dp.
- Stringhe in italiano in file propri.
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti
  degli altri**.

### 4.4 I pacchetti
"Agente": **esterno** = adatto a un agente esterno (OpenCode) sul suo branch, con il brief autosufficiente e senza dispositivi né NAS;
**interno** = agente interno, perché tocca recinti, file condivisi, il NAS di prova o i dispositivi.

| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Agente | Campo |
|---|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Moduli, contratti, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`); creazione di `core-albums` e `feature-albums` (build, manifest, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest`, `NoWallClockSourcesTest`); **contratti** del §1.2 con null-object, fake e gli esecutori di riferimento `InMemoryAlbums`, `InMemoryShareRunner`, `InMemoryTrips`; firme in `core-model` (`ShareLevel`, `AlbumKind`, `AlbumSort`, `TripKind`), `core-personal` (costanti dei campi D4), `core-library` (`LibraryFilter.EDITED`, `VisibleMedia`, `HiddenMask`, `WidgetFeed`), `core-nasops` (`MoveCompletionSink`, solo l'interfaccia e il null-object), `core-backup` (`IncomingDestination.AlbumFolder`, `SafeCopyRequests`, solo firme), `core-common` (`FeatureFlags.sharedAlbumsTestSharesOnly` acceso anche in release, `DiagSection.ALBUMS`, `Phase7ArchitectureTest`); `AlbumPermissions` come **tabella dati** del §2.4 (la logica la scrive P2); documenti del §0.3 | Fase 6 P0 (contratti di `core-search`) | `check.ps1` verde; ogni test d'architettura fallisce reintroducendo la violazione (`core-albums` che nomina `core.nasops`, `core.backup`, `core.nas`, `java.net`; `NasFileSystem` fuori da `shared/`; `home_estimate` fuori da `trips/`; `ShareConfirmation` costruita fuori dal foglio; un `PersonalKind` nuovo); gli esecutori di riferimento passano le suite di contratto; documenti aggiornati (§0.3) | **opus** | interno | no |
| **P1 Database v6** | Schema v6 (D34), query del catalogo | `core-database/**`, `docs/modello-dati.md` §14 | P0 | `6.json` esportato; `ExportedSchemaTest` 1→6 … 5→6 con `seedV5`; `MergeReferencesTest` con le tabelle nuove; `origin_device` derivato e riempito dalla migrazione e dal catalogo; candidati per giorno, luogo e dispositivo su 100 mila media sotto 50 ms senza `USE TEMP B-TREE`; posizioni per parte (Doppioni) sotto 200 ms; `attachMoved` idempotente in una transazione con un solo cambiamento dell'indice della timeline | sonnet | esterno | no |
| **P2 Formato firmato e permessi** | D6–D11, §2.4, §2.5 (parte "Ricevere") | `core-trust/.../data/AlbumDocs.kt` (più le righe minime in `Envelope.kt`, `NasLayout.kt`, `IndexSegment.kt` per la classe 17, `TrustFormat.kt` per la versione 3), `core-albums/.../shared/**` (replica, `AlbumFusion`, `AlbumPermissions`, `AlbumPublisher`, `SharedAlbumSync`) | P0 (P1 per gli adattatori Room) | test ostili per ogni campo e tetto (A4 del §0.3: lunghezze, UTF-8 non valido, caratteri di controllo e di direzione, percorsi con `..`, assoluti, dentro `.mycloudgallery` anche con `．` a larghezza piena, campi sconosciuti, corpi oltre i tetti, versioni 1, 2 e 4, id che non torna con account e nonce); anti-rollback ed equivocazione; dismesso (teste congelate valide) e revocato (ignorato); **tabella dei permessi** generata in test per ogni riga e livello, lato scrittore e lato lettore con lo stesso esito; storia dei livelli e voci retrodatate (§2.4); fusione commutativa, associativa e idempotente (test di proprietà su 10.000 storie casuali di 3 account e 5 dispositivi); E-SH6, E-SH11 (eredità), E-SH12, E-SH13, E-SH14; il pubblicatore scrive solo sotto la propria cartella e solo con `publishExclusive` (proprietà); 200 giri del banco con `FaultPlan` | **opus** | interno | sì (F79, F84, F86) |
| **P3 Album personali** | D3–D5, D21, §2.1 (album personali) | `core-personal/.../settings/PersonalCollections.kt` (impronta, campi nuovi), `core-personal/.../contract/Personal.kt` (solo i lettori dei campi D4, non i tipi), `core-albums/.../personal/**` (`CatalogAlbumProjection`, ordine, copertina) | P0, P1 | proiezione dallo stato a v6 e ricostruzione identica da zero (test di proprietà); risoluzione per id e per impronta univoca; elementi "in attesa" non mostrati; unione d'identità; posizioni a intervalli e ricompattazione; copertina automatica quando quella scelta è nascosta o sparita; un'app della Fase 6 legge i segmenti con i campi nuovi senza scartarli (test con il codec della versione precedente); due dispositivi che aggiungono insieme 1.000 elementi diversi: tutti presenti | sonnet | esterno | sì (F72) |
| **P4 Condivisione e spostamento** | D12, D28, D29, D31, D38, D39, §2.5, §2.6 | `core-albums/.../share/**` (`ShareRunner`, diario, piano, "Riporta", adozione dei file aggiunti dal PC), `core-nasops/.../engine/NasOpsEngine.kt` (solo la chiamata a `MoveCompletionSink` dopo `PUBLISHED`), `core-backup` (solo `AlbumFolder` in `INCOMING` e `SafeCopyRequests`) | P0, P2 (prima con `FakeAlbumPublisher`) | ogni riga del §2.5 e del §2.6 in test con il NAS finto e il simulatore SMB (vere `SmbNasFileOps`); invarianti del §2.5 dopo `FaultPlan` a ogni chiamata e morte del processo a ogni salvataggio del diario; nessuna voce per un file fuori dall'area comune (proprietà); `MoveCompletionSink` chiamata prima di `SOURCE_RECORDED` e idempotente (test sul motore, senza regressioni nei test della 4B: 400 giri di F16 sulla JVM verdi); nascosti lasciati fuori (R11) e condivisi con l'avviso; E-SH1, E-SH2, E-SH3, E-SH4, E-SH8, E-SH12, E-SH18, E-SH19; guardiano chiesto solo per i media propri senza copia sul telefono (D28); "Riporta" solo per i propri media verso `shared_origin`; recinzione D30; nessuna chiamata alle porte da un dato remoto (porte finte che falliscono) | **opus** | interno | sì (F79–F83) |
| **P5 Banco delle 500 interruzioni** | §5.5 | `core-albums/src/test/.../chaos/**`, `tools/chaos/share-pc/**` (dal PC sul NAS di prova, come F16-PC), `tools/s25.ps1` (modo `share`), `docs/prova-500.md` (sezione Fase 7) | P0 (riferimento), poi P4 | invarianti del §5.5; 2.000 giri JVM; 600 sul simulatore SMB; **500 dal PC sul NAS vero** (share di prova); ≥ 100 sull'S25; `ChaosMutationTest` (sorgente nel cestino prima della verifica, voce pubblicata prima del completamento, completamento dopo il cestino, conferma riusata: rossi con l'invariante giusto) | sonnet | interno | sì (F80) |
| **P6 Album smart** | D13–D17, §2.2 | `core-albums/.../smart/**` | P0, P1 (fake del catalogo prima), contratti di `core-search` | tabella pubblica di almeno 150 titoli con le regole attese (anni, stagioni, feste, mesi a cavallo d'anno, luoghi di più parole, omonimi, nessuna data, nessun luogo); raffinamento delle serie di giorni; ogni regola di §2.2 su viaggi sintetici (§5.3), con E-AL1–17; precisione ≥ 99% e copertura ≥ 95% sui viaggi sintetici (soglia più alta di quella del campo, che ha dati più sporchi); ricalcolo incrementale uguale al ricalcolo da zero (proprietà); < 2 s per album su 100 mila elementi sulla JVM; nessun nascosto (proprietà); senza motore le regole C e S4 tacciono e l'editor lo dice | sonnet | esterno | sì (F87, F88) |
| **P7 Viaggi ed eventi** | D18–D20, §2.3 | `core-albums/.../trips/**` | P0, P1 | casa e luoghi abituali su storie sintetiche (trasloco E-AL15, casa al mare, nessun GPS E-AL16, fusi E-AL10); viaggi ed eventi attesi su 20 storie sintetiche di 2 anni (tutti i viaggi trovati, nessun falso viaggio a casa); `trip_dismissed` sincronizzato; nessuna coordinata fuori da `trips/` (test d'architettura sulle API pubbliche e test sui log); < 5 s su 100 mila elementi | sonnet | esterno | sì (F89) |
| **P8 Utility, Doppioni, Eliminati di recente** | D24–D27, §2.1.1 | `feature-library/.../utility/**` (nuovo), `feature-library/.../trash/**`, `core-library` (filtro `EDITED`, rilevatore dei doppioni identici e simili in `duplicates/`), l'interruttore `full_hash_nightly` | P0, P1 | Doppioni identici dalla stessa parte con l'ordine di D25 e i "mai" (E-U1, E-U2); simili con vettori finti e con la firma visiva, soglia `Sdup` dal set sintetico scritta in `docs/decisioni.md`; piano di pulizia che passa da `NasOperations` (fake) e dal foglio di eliminazione esistente; E-U3, E-U5; Eliminati di recente con i due cestini, giorni rimasti, ripristino, "Elimina subito" a doppio tocco, avviso D-3; `FULL_HASH` notturno acceso e spento; screenshot `7-P8` | sonnet | esterno | sì (F75–F78) |
| **P9 Nascosti su ogni superficie** | D22, D23, §2.1 (Nascosti) | `core-library` (`VisibleMedia`, `HiddenMask`, `WidgetFeed`), `feature-library/.../hidden/**`, la riga dei nascosti in Cartelle NAS (`feature-nas`, solo l'elenco), `app/src/debug/.../widget/` (widget di prova), il registro `HiddenSurfaces` e il test di proprietà (in `app/src/test/`), l'avviso E-H2, Impostazioni → Privacy → Nascosti | P0; le superfici degli altri si aggiungono al registro quando arrivano | test di proprietà su tutte le superfici registrate (Librerie casuali con nascosti, gruppi, album, viaggi); il widget di prova non mostra mai un nascosto e non c'è nel dex di release (`check.ps1`); I8 chiusa (nessun nome); E-H2 sui media dell'area comune e degli album condivisi; E-H3 con la credenziale; E-H5–E-H7 | sonnet | interno | sì (F73, F74) |
| **P10 Schermate degli album** | §1.5, D12 (foglio), D21, D31 (fogli), D32, D35 | `feature-albums/**`, `feature-viewer/.../menu/` (le tre voci), `feature-integrations/.../receive/` ("Aggiungi ad album") | P0 (fake di P2, P4, P6, P7) | ogni stato del §1.5 composto; foglio di condivisione con conteggi, cartella, livello, nascosti e dichiarazione; azioni mostrate secondo `AlbumPermissions` per ogni livello (test generato dalla tabella); editor delle regole con anteprima, alternative, serie di giorni, "troppo larghe"; "Suggerite" e "Da condividere"; proposte di viaggio; TalkBack, 1,3x, 48 dp; screenshot `7-P10` | sonnet | esterno | sì |
| **P11 Raccolte** | D33, §2.7 | `feature-library/.../collections/**` (proprietario di `CollectionsTab.kt`), le voci `collections_*` in `core-personal/.../settings/` (file nuovo `SyncedCollectionsSettings.kt`) | P0 | sezioni come dati dagli altri pacchetti; "Modifica Raccolte" con trascinamento accessibile (azioni di TalkBack "Sposta su/giù"); ordine e visibilità sincronizzati; sezione nuova in coda (E-L14); Nascosti raggiungibili da Impostazioni quando la sezione è nascosta; due colonne sul tablet; screenshot `7-P11` | sonnet | esterno | sì (F90) |
| **P12 Valutazione** | §5.3 | `tools/fixtures/trips/**` (generatore di viaggi sintetici con EXIF e GPS, nessun dato personale), `app/src/debug/.../eval/albums/**` (strumento di etichettatura e corsa, solo debug), `core-albums/src/test/.../eval/**` (metriche) | P6, P7 | metriche (precisione dei sicuri, copertura, viaggi trovati) calcolate; strumento solo debug (assente dal dex di release); **nessuna etichetta, data o luogo dei viaggi veri nel repository** (test su git, `.gitignore`); numeri in `docs/decisioni.md` | sonnet | esterno (strumento e generatore); la corsa sui dati veri la fa l'utente | sì (F87–F89) |
| **P13 Integrazione** | §1.4 | `app/**` (`AlbumsGraph`, porte, lavori, navigazione, `MainScaffold`, azzeramento), `feature-diagnostics/**`, `README.md`, `docs/sicurezza.md`, `docs/decisioni.md` | P0–P12 | test d'integrazione sul NAS finto con due famiglie di dispositivi: album creato sul telefono finto A, spostamento, voce pubblicata, dispositivo B (altro account) lo vede con i permessi; finto album ostile → nessuna operazione nel diario di `core-nasops`; azzeramento completo a operazioni ferme; codici della Diagnostica nel catalogo; `check.ps1` verde; APK debug e release | sonnet | interno | sì |

> Nota sui crediti: opus per 3 pacchetti su 14, quelli fondanti o difficili: **P0** (contratti e recinti: conferme, porte, nessuna
> strada nuova verso operazioni distruttive), **P2** (formato firmato ostile, fusione e permessi, da cui dipende la fiducia fra
> familiari), **P4** (lo spostamento in condivisione, l'unica parte della fase che toglie file da un posto). P6 (album smart) è
> difficile ma ben misurabile: sonnet con i viaggi sintetici e una revisione opus mirata nella `security-review` di fine fase solo se
> le misure di F88 mancano l'obiettivo. Otto pacchetti su quattordici (P1, P3, P6, P7, P8, P10, P11 e P12 per strumento e
> generatore) sono adatti a un agente esterno sul suo branch.

### 4.5 Chiusura della fase
- `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro le guardie delle Fasi 3–6 e dentro lo scenario nuovo `album`
  (§5.4); F72–F92 superate (F93 con il consenso, Q7).
- `security-review` (opus) con revisione mirata del codec `album` di `core-trust`, di `AlbumPermissions`, dell'orchestratore della
  condivisione e della porta di completamento di `core-nasops`; **in parallelo un agente di verifica funzionale** (§5.7).
- Checklist della Fase 7 spuntata (§6.3); `docs/sicurezza.md` aggiornato; screenshot approvati; risposte del §9 registrate.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-albums` (smart, trips, shared, share), codec di `core-trust` | regole, viaggi, fusione, permessi, diario, codec ostile |
| NAS finto | `InMemoryNasFileSystem`, `InMemoryNasOperations` (fedele, §5.2), famiglia finta con registro | più account e dispositivi, revoche, dismissioni, guasti prima e dopo |
| Simulatore SMB | `InMemorySmbBackend` con le vere `SmbNasFileOps` e il vero `NasOpsEngine` | spostamenti, completamento, cestino, file bloccati, spazio |
| NAS vero dal PC | `tools/chaos/share-pc` con gli account di prova (`test`, `test2`, `test-comune`), guardia F4-D30 | banco delle 500 interruzioni (§5.5), finti album (F84), modifiche dal PC (F85) |
| Robolectric (SDK 35) | Room v6, Compose, widget di prova | |
| Roborazzi | `feature-albums`, Utility, Raccolte | chiaro e scuro, telefono e tablet, testo a 1,3x |
| Benchmark | `AlbumBenchmark` su S25 e Tab S9 | apertura di un album da 5.000, scorrimento, ricalcolo (§5.4) |
| Dispositivi | S25 (`test`), Tab S9 (profilo `test2`) | §5.6 |

### 5.2 Fake fedeli e test di parità
| Fake | Fedeltà | Parità |
|---|---|---|
| `InMemoryNasOperations` | stessa macchina a stati di `OpStateMachine` (anche `PUBLISHED` → completamento → `SOURCE_RECORDED`), conferme con impronta, spazio, `FROZEN`, file bloccati, guasti iniettabili | la suite `NasOperationsContract` gira sul fake e sul vero `NasOpsEngine` col simulatore SMB: stessi stati, stesse chiamate a `MoveCompletionSink`, stessi esiti |
| `InMemoryIncoming` | `INCOMING` verso una cartella dell'area comune, timbri, verifica | la suite di contratto del caricamento della Fase 5 |
| `FakeAlbumPublisher` / `InMemoryAlbumRemote` | teste e segmenti veri (codec di P2), serie numerate, `publishExclusive`, file spazzatura | la stessa suite di `NasPersonalRemote` adattata (anti-rollback, salti, collisioni) |
| Famiglia finta | registro firmato con account, dispositivi approvati, dismessi (teste congelate), revocati, admin | test del registro esistenti |
| `InMemorySearchEngine` (Fase 6) | sicuri e meno probabili, chip di vincolo | quando P6 della Fase 6 consegna: su 200 album sintetici le classi delle regole C e S4 coincidono con il motore vero, salvo i casi a ± 1e-4 dalla soglia |
| Libreria sintetica con viaggi (P12) | media con `local_day`, fuso, GPS, dispositivo d'origine, sorgente, account; viaggi, eventi, case, traslochi | — (è il banco) |

### 5.3 Valutazione degli album smart e dei viaggi: sui dati veri, senza dati personali nel repository
**Il problema**: gli obiettivi di 7.2 e 7.3 si misurano solo sui viaggi veri dell'utente; né le foto né le date né i luoghi dei
viaggi devono finire nel repository.
1. **Pubblico, nel repository** (a ogni `check.ps1`): il generatore di P12 produce 20 storie di 2 anni (casa, trasloco, casa al mare,
   viaggi brevi e lunghi, a cavallo d'anno, in altri fusi, giorni senza GPS, foto di familiari, screenshot, eventi); P6 e P7 devono
   dare precisione ≥ 99% e copertura ≥ 95%, tutti i viaggi trovati e nessun falso viaggio.
2. **Privato, fuori dal repository** (la misura che conta, F88 e F89):
   - l'utente sceglie **3 viaggi** degli ultimi anni e scrive, **prima** di guardare le proposte, l'elenco dei viaggi principali
     degli ultimi 2 anni (solo nello strumento, sul telefono);
   - con lo strumento **solo debug** (Diagnostica → Sviluppo → "Valutazione degli album"), per ogni viaggio crea l'album smart dal
     titolo e segna, scorrendo **i giorni del viaggio nella Libreria** (non l'album, per non premiare il motore), le foto che sono del
     viaggio;
   - lo strumento calcola **precisione dei sicuri** e **copertura** (sicuri + suggeriti) per viaggio e in totale, e quali viaggi
     dell'elenco sono fra le proposte; le etichette sono id locali in `noBackupFilesDir/eval/albums/` (fuori dai backup); esporta
     **solo numeri**, che vanno in `docs/decisioni.md`;
   - tempo dell'utente: circa 45 minuti, in un momento a sua scelta (non serve il tablet).
3. **Obiettivi**: precisione ≥ 98% e copertura ≥ 90% sui 3 viaggi; tutti i viaggi dell'elenco proposti. Se mancano: le soglie
   (raggio, finestra della regola B, buchi fra i giorni) si regolano sui numeri, mai guardando le foto dal PC.

### 5.4 Benchmark
- **`AlbumBenchmark`** (`:benchmark`, Macrobenchmark): album da 5.000 elementi (sintetico) aperto dalle Raccolte (primo fotogramma
  della griglia), scorrimento (stessa guardia della Libreria: < 1% di fotogrammi oltre 8,3 ms), ricalcolo di un album smart su 100
  mila elementi con il lavoro in corso; Raccolte con 50 album.
- Soglie in `benchmark/soglie.json`, scenario `album`: apertura ≤ 300 ms, ricalcolo ≤ 2 s, regressione +15%; guardia `check.ps1
  -Bench` su S25 e Tab S9. Le guardie esistenti non peggiorano con la sincronizzazione degli album condivisi in corso.

### 5.5 Il banco delle 500 interruzioni (spostamento in condivisione, P5)
**Invarianti** (dopo ogni morte, a fine passo e in una rilettura finale con l'hash di ogni file)
1. **Nessun file a metà visibile**: nella cartella dell'album e nella cartella d'origine solo file completi con l'hash atteso; i
   temporanei solo in `.mycloudgallery/tmp/` e citati da un diario.
2. **Nessun doppione**: ogni media dell'operazione ha **un solo** file nell'area comune (più la sorgente nel cestino).
3. **Nessuna perdita**: ogni media dell'operazione ha, in ogni istante, almeno una copia completa fuori dal cestino o una copia
   nel cestino con il record firmato e una copia completa altrove; a fine giro la sorgente è nel cestino solo se la destinazione è
   verificata.
4. **Hash al 100%**: ogni destinazione ha lo SHA-256 della sorgente.
5. **Nessun elemento mancante in app**: la Libreria ha gli stessi media (stessi `MediaId`) prima e dopo; ogni media ha in ogni
   istante una posizione presente che esiste davvero (dopo `IN_COMMON` quella nell'area comune).
6. **Nessuna voce senza file**: nessuna voce `item:` pubblicata per un file assente dall'area comune.
7. **Nessuna azione senza conferma**: ogni operazione nel diario di `core-nasops` nasce da una conferma registrata in
   `album_share_op` con la stessa impronta.
8. **Il lavoro arriva in fondo**: dopo le riprese ogni elemento è `DONE`, `FAILED` con motivo o `CANCELLED`; nessun errore
   permanente per un guasto passeggero.
9. **Nascosti**: con «Lascia fuori» nessun nascosto si è mosso.

**Interruzioni**
| Dove | Come | Quante |
|---|---|---|
| JVM, NAS finto | `FaultPlan` (rete prima e dopo, sessione scaduta, stallo, spazio finito), morte del processo a ogni salvataggio dei diari (condivisione, `core-nasops`, caricamento), file cambiato a metà, file bloccato | 2.000 giri a ogni `check.ps1` |
| JVM, simulatore SMB | come sopra con le vere `SmbNasFileOps` e il vero `NasOpsEngine` | 600 giri |
| PC, NAS vero (share di prova) | `tools/chaos/share-pc`: lo stesso motore sulla JVM del PC contro `test` → `test-comune`, processo ucciso e connessione tagliata a tempi casuali, verificatore che rilegge tutto (come F16-PC) | **500** |
| Telefono, area di prova | `s25.ps1 -Mode share`: `kill -9` e Wi-Fi spento per 3–20 s a tempi casuali durante condivisioni di 20–200 media (anche dal telefono, `INCOMING`), poi il verificatore dal PC | ≥ 100 |

### 5.6 Verifica sul campo
**Come si lavora**: le prove girano **da sole** sull'S25 con la build debug e l'account `test` (configurazione rifatta il 20/10,
area comune `test-comune`): uno scenario per prova nel pannello di debug "Prove della Fase 7" (come le prove guidate della 5B), che
semina i media di prova in `mcg-prova`, guida l'interfaccia con UI Automator dove serve una conferma, e scrive l'esito in un file
letto dal PC con `adb`; il verificatore dal PC (`mcg-it`) controlla il NAS di prova. L'utente non serve, salvo **un solo momento
breve con il tablet** (F86, circa 30 minuti) e la valutazione sui suoi viaggi (F88, F89, circa 45 minuti, quando vuole).

**A. Preparazione**
1. S25 con la build debug e `test`; Tab S9 con il profilo "Prova" e `test2` (stessa build), per F86.
2. F29 della Fase 5 fatta prima di F79 (stesso cammino).
3. Media di prova **non personali** generati da P12 (viaggi sintetici con GPS ed EXIF, screenshot, video brevi, doppioni identici e
   simili) caricati in `mcg-prova` e, dal backup di prova, in `test`.
4. Per F88 e F89: la build debug con lo strumento di valutazione sul telefono con la Libreria vera (sola lettura: lo strumento non
   scrive sul NAS).

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F72 | Album personali (autonoma): 5 album, 1.000 elementi in uno, ordine personalizzato, copertina, rinomina, eliminazione; poi un secondo dispositivo con `test` (emulatore) | ordine e copertina ritrovati dopo la sincronizzazione; nessun file toccato sul NAS (verificatore); scorrimento fluido |
| F73 | Nascosti ovunque (autonoma): 3 media nascosti con GPS, testo, in un album manuale, in un album smart, in un viaggio sintetico, in un gruppo di doppioni, come copertina scelta; widget di prova sulla schermata Home | nessuno compare in nessuna superficie (UI Automator legge le descrizioni accessibili di ogni schermata del registro); nessun nome in Cartelle NAS; conteggi senza di loro |
| F74 | Sblocco dei Nascosti (nel momento con il tablet, F86): biometria, poi credenziale; app sbloccata non apre i Nascosti; schermata protetta | come D23; screenshot nero dai recenti |
| F75 | Utility (autonoma): Screenshot, Video, Modificate, WhatsApp | conteggi uguali al catalogo; Modificate con le versioni seminate |
| F76 | Doppioni identici (autonoma): lo stesso file in due cartelle di `test` e in `test-comune`; pulizia guidata | proposta secondo D25; dopo la conferma una copia per parte, le altre nel cestino del NAS con il record; ripristino dagli Eliminati di recente |
| F77 | Doppioni simili (autonoma): raffiche a mano (scatti a 2 s), foto ritagliate, una preferita | gruppi giusti; la preferita preselezionata da tenere; le altre nel cestino dopo la scelta |
| F78 | Eliminati di recente (autonoma): media eliminati dal telefono con l'app e dal NAS | una pagina con entrambi, giorni rimasti, ripristino; su Samsung l'avviso D-3 |
| F79 | Album condiviso (autonoma): "Prova Oslo" con 20 foto di `test` (area privata), 3 solo sul telefono (E-SH1), 1 già in `test-comune`, 1 nascosta (lasciata fuori) | conferma con i conteggi; dal PC: 23 file nella cartella dell'album con l'hash giusto, 20 sorgenti nel cestino privato con il record, nessun temporaneo, la nascosta al suo posto; testa e segmenti validi; in app gli stessi `MediaId`, nessun elemento mancante durante lo spostamento; guardiano per i media già assenti dal telefono |
| F80 | Banco delle interruzioni sul telefono (autonoma, `s25.ps1 -Mode share`, ≥ 100) e dal PC (500, `tools/chaos/share-pc`) | invarianti del §5.5 |
| F81 | Area comune "piena" (autonoma, debug: tetto di spazio simulato) | pausa prima del primo file, nessuno spostamento a metà, ripresa togliendo il tetto |
| F82 | Conflitti e riferimenti (autonoma): nome già presente nella cartella; lo stesso media in due album condivisi; cartella del primo album rinominata dal PC | nome alternativo, nessuna sovrascrittura; un solo file; gli elementi si ritrovano e l'album propone la nuova cartella (E-SH16) |
| F83 | Smetti di condividere e Riporta (autonoma) | per il creatore album personale; i propri media tornano nelle cartelle d'origine (verificatore), copie comuni nel cestino comune; i file degli altri restano |
| F84 | Finti album (autonoma, dal PC con il dispositivo di prova della guardia F4-D30): definizione malformata, id che non torna, voce con un media privato, voce "elimina", contributo di un dispositivo revocato, file spazzatura in `albums/` | tutto ignorato con i contatori; nessuna operazione nel diario di `core-nasops`; nessun traffico verso l'area privata |
| F85 | Permessi aggirati dal PC (autonoma, `test2` dal PC): un file aggiunto nella cartella dell'album, uno cancellato | il primo non entra nell'album (proposta "aggiungili"); il secondo esce dall'album per tutti; la copia privata del proprietario c'è se il guardiano l'ha fatta |
| F86 | **Il momento con il tablet** (utente, ~30 minuti, una volta sola): (1) l'album "Prova Oslo" di F79 compare nelle Raccolte del profilo `test2`; (2) il creatore (S25) passa per i tre livelli e a ogni livello il tablet prova aggiungi, togli (proprio e altrui), elimina, rinomina, copertina; (3) aggiunte contemporanee dai due dispositivi (E-SH6); (4) "Smetti di condividere" dall'S25 (E-SH7); (5) F74 sul tablet; (6) Raccolte riordinate sul tablet | (1) entro un giro di sincronizzazione; (2) solo le azioni della tabella del §2.4, le altre non offerte; (3) entrambe le aggiunte; (4) avviso e album sparito sul tablet, file ancora visibili in Cartelle NAS; (5) come D23 |
| F87 | Album smart sui viaggi sintetici (autonoma) | precisione ≥ 99%, copertura ≥ 95% come sulla JVM |
| F88 | Album smart su 3 viaggi veri (utente, strumento, §5.3) | precisione dei sicuri ≥ 98%, copertura ≥ 90% |
| F89 | Viaggi proposti sui dati veri (utente, stesso momento di F88) | tutti i viaggi principali dell'elenco degli ultimi 2 anni proposti; nessuna coordinata della casa nei log e nell'esportazione della Diagnostica (controllo automatico) |
| F90 | Raccolte (autonoma, poi sul tablet in F86): ordine e visibilità, sezione Nascosti nascosta e aperta da Impostazioni, TalkBack | scelte ritrovate sull'altro dispositivo dell'account; screenshot |
| F91 | Prestazioni (autonoma): `AlbumBenchmark` su S25 e Tab S9; ricalcolo e rilevazione sulla Libreria sintetica da 100 mila | soglie del §5.4 |
| F92 | Nessuna azione da dati remoti (autonoma, 24 ore): sincronizzazione continua con i finti album di F84 presenti | diario di `core-nasops` e del caricamento senza operazioni non confermate (confronto con `album_share_op`) |
| F93 | (con il consenso, Q7) APK di release: album condivisi sulle share vere | un album con 5 foto scelte dall'utente si condivide, il familiare lo vede; dal PC nulla fuori dalla cartella dell'album e da `.mycloudgallery/albums/` |

**I "Fatto quando" del piano, prova per prova**
| Sottofase | Fatto quando (piano) | Dove si prova |
|---|---|---|
| 7.1 | Un elemento nascosto non compare in nessuna superficie (test automatico su tutte le schermate e sul widget di prova) | P9 (proprietà), F73 |
| 7.2 | Su 3 viaggi reali ≥ 98% dei media inseriti corretti, ≥ 90% delle foto del viaggio inserite o suggerite | P6, P12, F87, F88 |
| 7.3 | Sui dati reali i viaggi principali degli ultimi 2 anni proposti | P7, F89 |
| 7.4 | "Oslo 2026" creato su S25 compare sul profilo di un altro account sul tablet; permessi rispettati a tutti e tre i livelli; 500 interruzioni sullo spostamento | P2, P4, P5, F79, F80, F86 |
| 7.5 | Sezioni ordinabili e nascondibili | P11, F90 |

### 5.7 Verifica funzionale di fine fase
Un agente di verifica funzionale (come `docs/review/funzionale-5b.md`), in parallelo alla `security-review`, rilegge i percorsi interi
contro questo documento e scrive `docs/review/funzionale-7.md` con voci "percorso, atteso, trovato, stato":
- album personali (creazione da ogni punto d'ingresso, ordine, copertina, sincronizzazione, unione d'identità, media spariti);
- album smart (interpretazione, raffinamento, regole, sicuri e suggeriti, esclusioni, ricalcolo, congela, senza motore);
- viaggi ed eventi (casa, proposte, scarto sincronizzato, accettazione, privacy della casa);
- condivisione (piano, conferma, nascosti, spostamento, caricamento dal telefono, completamento, voci, guardiano, errori e pause);
- permessi (ogni riga del §2.4 a ogni livello, lato scrittore e lato lettore, eredità, revoche);
- chiusura (smetti, elimina, riporta, lasciare l'album);
- Utility (filtri, Doppioni identici e simili, pulizia, hash notturno, Eliminati di recente);
- Nascosti su ogni superficie registrata; Raccolte; azzeramento; Diagnostica.
Le voci confermate si correggono prima della chiusura.

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 7
| Minaccia | Contromisura | Prova |
|---|---|---|
| **Familiare compromesso che scrive finti album con media privati** (per farli spostare, scaricare o mostrare) | una definizione non sposta, non copia, non cancella e non scarica nulla (D9, C10); gli elementi si mostrano solo se il catalogo di chi legge vede il file nell'area comune; lo spostamento nasce solo da `ShareConfirmation` + `UserConfirmation` locali (D12) | P2, P4, P13, F84, F92 |
| Finto album con un titolo ingannevole ("Foto da cancellare") o una voce "elimina" | nessuna voce di un album produce un'azione sui file; "Elimina" è un'azione locale con il foglio e il cestino | P2, F84 |
| Contributo che si spaccia per il creatore o rivendica un album esistente | id dell'album legato all'account del creatore (D7); `account` della voce = `nasUser` del firmatario nel registro (D8) | P2 |
| **Permessi aggirati da un'app difettosa o modificata** | doppio controllo con lo stesso codice: chi legge scarta le voci non permesse (D10) | P2 (tabella generata), F86 |
| **Permessi aggirati dal PC** (SMB) | impossibile da impedire: dichiarato in ogni schermata (L57); i file aggiunti dal PC non entrano nell'album (D39); quelli tolti escono per tutti e il proprietario ha la copia privata se il guardiano l'ha fatta (D28) | F85 |
| Retrodatazione delle voci per sfruttare un livello di permesso passato | controllo dell'`at` rispetto alla testa precedente (§2.4); resto dichiarato (L59) | P2 |
| **Nascosti aggiunti a un album condiviso** (E-SH9) | «Lascia fuori i N nascosti» preselezionata; con «Condividi anche i nascosti» l'avviso "diventeranno visibili ai familiari" (Q3); i nascosti non si offrono nel selettore fuori dai Nascosti | P4, P10, F79 |
| Nascosti che trapelano da album, copertine, viaggi, conteggi, widget | una sola fonte (`VisibleMedia`), registro delle superfici e test di proprietà (D22) | P9, F73 |
| "Casa" che esce dal dispositivo | solo in `home_estimate` e in `trips/`; nessuna API pubblica con coordinate; mai in log, Diagnostica, dati personali, indice (D18) | P7, F89 |
| Percorsi d'origine dei propri media letti dai familiari | `shared_origin` nei dati personali (area privata), mai nella definizione comune (D31) | P2, P4 |
| Percorsi ostili nelle voci (`..`, assoluti, `.mycloudgallery` con caratteri a larghezza piena) | codec con confronto NFKC (B1 della revisione 1.4), percorsi relativi alla sola area comune | P2 |
| Spostamento di massa che fa scattare il congelamento o che avviene durante un congelamento | franchigia del diario (R8 della 4B); pausa con `FROZEN` (D38) | P4 |
| Dispositivo perso del creatore | revoca: contributi ignorati, file restano; ripristino da un altro dispositivo dell'account (E-SH20) | P2 |
| Eredità usata per prendere album di chi è ancora in famiglia | `heir` valida solo se il registro firmato non ha più dispositivi del creatore (D11) | P2 |
| Pulizia dei doppioni che toglie l'ultima copia sicura | regole "mai" di D25; tutto nei cestini | P8, F76 |

**Contromisure nuove** (proposta per `sicurezza.md` §4)
- **C24. Definizioni d'album solo descrittive**: firmate per dispositivo, id legato all'account del creatore, codec ostile; nessuna
  voce produce un'azione sui file; elementi attestati dal catalogo di chi legge (D6–D9).
- **C25. Permessi a doppio controllo**: la stessa tabella pura decide cosa offre chi scrive e cosa accetta chi legge (D10).
- **C26. Spostamento in condivisione a tre chiavi**: conferma dell'album (`ShareConfirmation`), conferma di `core-nasops`
  (`UserConfirmation`) con la stessa impronta, diario prima di ogni passo; la sorgente va nel cestino solo dopo la verifica e dopo che
  il catalogo conosce la destinazione (D12, D29).
- **C27. Nascosti da una sola fonte**: `VisibleMedia`, registro delle superfici e test di proprietà (D22).

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L57. I permessi degli album condivisi li fa rispettare l'app.** Dal PC, o con un'altra app, chi accede all'area comune può
  aggiungere, cambiare e cancellare i file di qualunque album, a qualunque livello (estende L2).
- **L58. Un dispositivo approvato compromesso può scrivere definizioni d'album false** nell'area comune: album con titoli falsi,
  elementi che non si mostrano, voci scartate. Non può far spostare, cancellare o mostrare file che chi legge non vede già; la revoca lo
  toglie.
- **L59. L'ora delle voci la dichiara chi scrive**: un dispositivo compromesso che ha avuto un livello di permesso più alto può
  retrodatare voci a quel periodo, se nessuno ha letto le sue teste di allora.
- **L60. Le definizioni degli album condivisi non sono cifrate** (come L26): chi legge l'area comune dal PC vede titoli, elementi e
  chi li ha aggiunti (non i percorsi d'origine privati).
- **L61. Togliere un file dall'area comune dal PC lo toglie a tutti gli album** che lo contengono (E-SH11, E-SH17): l'appartenenza
  non dipende dalla cartella, ma il file è uno solo.
- **L62. Un media condiviso senza copia sul telefono ha la copia privata solo dopo il giro del guardiano** (D28): nei minuti fra lo
  spostamento e la copia privata, e se il guardiano è fermo (NAS pieno, regole), la sola protezione è la sorgente nel cestino per 30
  giorni (con Q4 (b) della Fase 5 vale L44 per i media che il telefono ha ancora).
- **L63. La "casa" dedotta dalle foto** sta in chiaro nel database dell'app: chi ha il telefono sbloccato con l'app aperta e gli
  strumenti di sviluppo la può leggere (come L48).
- **L64. Gli album smart sbagliano**: "sicuro" vuol dire corretto almeno 98 volte su 100 sui viaggi misurati; per descrizione vale la
  precisione della ricerca (L50).
- **L65. Il widget di prova** esiste solo nella build debug; il widget vero (Fase 10) userà lo stesso contratto e lo stesso registro.
- **L66. Un familiare che lascia un album lo nasconde solo a sé**: l'album e i suoi file restano per gli altri, e dal PC nella
  cartella.

### 6.3 Checklist della Fase 7 (sostituisce le quattro voci di `sicurezza.md` §7 "Fase 7")
- [ ] Nascosto mai in nessuna superficie del registro né nel widget di prova (test di proprietà, F73).
- [ ] Condivisione con conferma esplicita e conteggio; lo spostamento parte **solo** da quella conferma (test d'architettura sulle
  conferme, F84, F92).
- [ ] Nessuna definizione d'album, vera o falsa, produce un'azione sui file (test con porte che falliscono se chiamate).
- [ ] La UI dichiara che permessi degli album e Nascosti sono protezioni dell'app; avviso per un nascosto aggiunto a un album
  condiviso (E-SH9) e al momento di nascondere un media dell'area comune (E-H2).
- [ ] **Permessi rispettati a tutti e tre i livelli** (Tutti possono tutto, Gli altri aggiungono, Gli altri guardano), lato scrittore
  e lato lettore, ogni riga del §2.4 (test generato dalla tabella, F86).
- [ ] Codec `album` ostile: firme, id legato all'account, tetti, percorsi; dispositivi revocati ignorati.
- [ ] Banco delle 500 interruzioni sullo spostamento passato (F80).
- [ ] "Casa" mai fuori dal dispositivo né nei log; percorsi d'origine solo nell'area privata.
- [ ] Pulizia dei doppioni sempre nei cestini, mai l'ultima copia privata.
- [ ] Log e Diagnostica senza titoli, cartelle, luoghi, coordinate, account.

### 6.4 Permessi, componenti, dipendenze e host
| Voce | Cosa cambia |
|---|---|
| Permessi Android nuovi | **nessuno** (la "casa" viene dalle foto, non dalla posizione) |
| Componenti esportati o servizi nuovi | **nessuno**; il widget di prova è un `receiver` solo della build debug, non esportato salvo l'azione di sistema dei widget, assente dal manifest di release (`verifyReleaseManifest`) |
| Dipendenze nuove | **nessuna** (il widget di prova usa `AppWidgetProvider` e `RemoteViews` della piattaforma: niente Glance; il widget vero della Fase 10 sceglierà la sua tecnica) |
| Host nuovi | **nessuno** |

## 7. Rischi e piani B

| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| La porta di completamento cambia il motore di `core-nasops` già provato | regressioni negli spostamenti della 4B | un'unica chiamata dopo `PUBLISHED`, null-object di default, 400 giri di F16 sulla JVM e F80 dal PC | catalogo aggiornato dall'orchestratore leggendo lo stato `PUBLISHED` del diario, senza toccare il motore |
| Precisione degli album smart sotto il 98% sui dati veri | obiettivo 7.2 mancato | regole forti per i sicuri, il resto suggerito; misura in F88 | regola B più stretta (3 ore), raggio più piccolo, sicuri solo dalla regola A |
| Copertura sotto il 90% (foto di altri dispositivi senza GPS) | foto del viaggio mancanti | suggerite da S1 e S5; descrizione col motore della Fase 6 | suggerire tutto il periodo per gli album di viaggio |
| Ricerca della Fase 6 non pronta a fine Fase 7 | descrizione ed esempi inattivi; Doppioni simili solo con la firma visiva | tutto funziona senza (D15, D25); l'editor lo dice | consegnare le regole C e S4 con la chiusura della Fase 6 |
| GPS assente nelle foto dell'utente | niente casa, niente viaggi | eventi senza GPS (E-AL16); album smart per periodo | proposte solo per eventi |
| Spostamenti grandi (GB) interrotti spesso fuori casa | condivisioni lunghe | attività pesante sul Wi-Fi di casa, UIDT con l'app aperta, ripresa dal diario | dividere il piano in lotti da 2 GB |
| Due contributi grandi da 20.000 voci | sincronizzazione lenta sul tablet | segmenti stabili (si scaricano solo quelli nuovi), tetti | album oltre 20.000 elementi non condivisibili (detto nel foglio) |
| Conflitti di file condivisi (`CollectionsTab`, menu del viewer, `MediaCatalog`) | merge rotti fra pacchetti paralleli | proprietari unici (§1.5), voci come dati | l'orchestratore serializza |
| D-3 (cestino di Samsung) resta aperto | ripristini dal telefono non visibili negli Eliminati di recente | avviso esplicito (D26) | correzione di D-3 come pacchetto della Fase 5 |
| Il familiare con `test2` non è disponibile per F86 | 7.4 non chiuso | un solo momento breve, preparato in anticipo; tutto il resto autonomo | secondo profilo sull'emulatore con `test2` per le prove automatiche dei permessi (resta F86 sul tablet per la chiusura) |
| Album condivisi sulle share vere senza consenso | prima scrittura di file di famiglia nell'area comune vera | recinzione D30 | — (Q7) |
| Crediti | costo alto | opus su 3 pacchetti su 14; 8 pacchetti a un agente esterno | P2 sonnet con revisione opus del codec e della tabella |

### File cruciali
- `core-personal/.../contract/Personal.kt` (`PersonalKind`, `PersonalFields`), `settings/PersonalCollections.kt` (`PersonalAlbums`),
  `store/PersonalProjection.kt`, `codec/PersonalCodec.kt` (tipi e campi sconosciuti), `sync/NasPersonalSync.kt`.
- `core-trust/.../envelope/Envelope.kt` (`SHARED_ALBUM_HEAD`), `nas/NasLayout.kt` (`albumHeadDir`), `data/DeviceHead.kt`
  (`HeadVerifier`, `HeadMark`), `data/IndexSegment.kt` (`SegmentClass`, codici riservati 17–31), `TrustFormat.kt`, `Keys.kt`
  (id d'album), `registry/FamilyRegistry.kt` (`DeviceEntry.nasUser`, `status`, `frozenHeads`), `TextRules.kt`, `SharePath.kt`.
- `core-nasops/.../contract/Request.kt` (`OpRequest.Move`, `LeaveBehind`), `contract/Diary.kt` (`ItemFlow.MOVE_ACROSS_SHARES`),
  `contract/Confirmation.kt`, `engine/NasOpsEngine.kt` (`verifyPhase`, pubblicazione, sorgente), `engine/EnginePorts.kt`,
  `contract/TrashAndSpace.kt` (`NasTrash`).
- `core-backup/.../contract/BackupDiary.kt` (`INCOMING`), il guardiano (`free/`, `contract/FreeSpace.kt`).
- `core-database/.../entity/DerivedEntities.kt` (`album`, `album_item`, `media_personal`), `dao/LibraryDaos.kt` (`AlbumDao`,
  `MergeDao`), `dao/BackupDaos.kt` (`SimilarDao`), `MediaCatalog.kt` (`attachCopy`, `relocateNas`, unioni), `GalleryMigrations.kt`.
- `core-library/.../contract/Timeline.kt` (`LibraryFilter`, `OrderedTimelineView`), `similar/` (firma visiva).
- `core-search/.../contract/Search.kt`, `contract/Query.kt`, `parse/ItalianQueryParser.kt`; `core-places/.../contract/Places.kt`.
- `feature-library/.../collections/` (`CollectionsTab.kt`, `CollectionKind.kt`), `hidden/HiddenAccessGate.kt`, `trash/`;
  `core-designsystem/.../sheet/GalleryConfirmSheets.kt` (`GalleryShareSheetContent`), `media/GalleryBadge.kt`.
- `app/.../nas/NasOrganizer.kt` (`hiddenAmong`, cestino), `nas/CatalogNasPorts.kt` (`relocateAll`), `navigation/FiveBNavigation.kt`
  (Utility), `main/MainScaffold.kt`, `core-common/.../featureflags/FeatureFlags.kt`.

## 8. Cose che richiedono l'utente

| Cosa | Perché | Nel frattempo |
|---|---|---|
| Rispondere a Q1–Q7 | cambiano P2, P4, P10 e la chiusura | valgono le raccomandazioni; P0 parte |
| Fare F29 della Fase 5 (blocco B), se non è già fatta | stesso cammino di spostamento e guardiano di F79 | P4 sul simulatore SMB e dal PC |
| Il momento con il tablet (F86, ~30 minuti, una volta) | permessi a tutti e tre i livelli fra due account (7.4) | prove automatiche con un secondo profilo sull'emulatore |
| Scegliere 3 viaggi ed etichettarli con lo strumento (F88, F89, ~45 minuti) | la misura che conta per 7.2 e 7.3 | viaggi sintetici (F87) |
| Revisione degli screenshot (album, editor, foglio di condivisione, Utility, Doppioni, Raccolte) | gate grafico | generati in anticipo |
| Consenso agli album condivisi sulle share vere (Q7) | F93 | solo share di prova |

## 9. Domande per l'utente
Poche, solo quelle che decidono cosa vede la famiglia e cosa resta privato. Se non rispondi, vale la raccomandazione.

| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | Quando condividi un album, le foto vanno in una cartella dell'area comune. Dove la mettiamo? I familiari la vedono anche dal PC. | (a) In una cartella "Album condivisi", con dentro una cartella per ogni album. (b) Dentro "MyCloud Gallery/Album", vicino alle altre cose dell'app. (c) Divise per anno: "Album condivisi/2026/Oslo". | **(a)**: si trova subito dal PC e il nome dice cosa c'è dentro. |
| Q2 | Quando crei un album condiviso, che permesso proponiamo per primo? Lo puoi sempre cambiare. | (a) "Gli altri aggiungono": tutti aggiungono foto, ognuno toglie solo le sue, tu decidi il resto. (b) "Tutti possono tutto". (c) "Gli altri guardano". | **(a)**: è il modo più comune di fare un album di famiglia, e nessuno toglie le foto degli altri. |
| Q3 | Se in un album che condividi ci sono foto che hai nascosto, cosa facciamo? | (a) Le lasciamo fuori, e te lo diciamo ("3 foto nascoste restano fuori"). Puoi sempre scegliere di condividerle. (b) Le condividiamo, con un avviso: i familiari le vedranno. | **(a)**: una foto nascosta di solito non la vuoi mostrare; è la stessa scelta che hai già fatto per le cartelle del NAS. |
| Q4 | Un album smart che hai condiviso (per esempio "Oslo 2026") trova foto nuove. Le condividiamo da sole? | (a) No: le mettiamo in "Da condividere" e le condividi con un tocco. (b) Sì, da sole: le foto nuove si spostano nell'area comune senza chiederti niente. | **(a)**: spostare foto nell'area comune è sempre una tua scelta; un tocco basta. |
| Q5 | Se smetti di condividere un album, cosa vedono gli altri? (Le foto restano comunque nell'area comune, a meno che tu non le riporti nelle tue cartelle.) | (a) L'album sparisce dalle loro Raccolte, con un avviso. Le foto le trovano ancora nelle cartelle del NAS. (b) Ognuno si tiene una copia personale dell'album con le stesse foto, finché le foto restano. | **(a)**: "smetti di condividere" deve voler dire che l'album non è più loro. |
| Q6 | Negli album condivisi mostriamo chi ha aggiunto ogni foto ("Aggiunta da Marco")? | (a) Sì, nelle informazioni della foto. (b) No. | **(a)**: aiuta a capire chi ha messo cosa, e serve per i permessi ("ognuno toglie le sue"). |
| Q7 | Gli album condivisi spostano davvero le foto nell'area comune. Quando li accendiamo sulle cartelle vere (oggi funzionano solo su quelle di prova)? | (a) A fine Fase 7, dopo tutte le prove, con l'app definitiva e il tuo sì. (b) Più avanti, insieme alla pubblicazione dell'indice sulle cartelle vere (Fase 6). | **(a)**: le prove con le cartelle di prova e il tablet bastano; per le cartelle vere serve comunque il tuo sì. |

## 11. Risposte del 22/10 e cosa cambiano
| # | Risposta | Cosa cambia |
|---|---|---|
| Q1 | **(a)** "Album condivisi/<nome>" | Come raccomandato |
| Q2 | **(a)** "Gli altri aggiungono" come permesso proposto | Come raccomandato |
| Q3 | **(a)** Le foto nascoste restano fuori, con l'avviso; si possono condividere a scelta | Come raccomandato |
| Q4 | **(a)** Le foto nuove di un album smart condiviso vanno in "Da condividere" | Come raccomandato |
| Q5 | **Diversa dalle opzioni: "lo decide chi elimina l'album"** | Il foglio "Smetti di condividere" / "Elimina l'album" chiede a chi lo fa di scegliere tra (a) l'album sparisce dalle Raccolte degli altri, con un avviso, e (b) ognuno si tiene una copia personale dell'album con le stesse foto, finché le foto restano. In entrambi i casi le foto restano nell'area comune, salvo "Riporta nelle mie cartelle". La scelta viaggia nella definizione firmata (D31 da adeguare in P0/P2/P4) |
| Q6 | **(a)** Si mostra chi ha aggiunto ogni foto | Come raccomandato |
| Q7 | **(a)** Album condivisi sulle cartelle vere a fine Fase 7, con il consenso | Come raccomandato |

Conferma collegata (Fase 5, guardiano D9 e Q4 (b) della 5): la copia privata automatica di sottofondo vale solo per i media che sono stati sul telefono; le foto spostate nell'area comune dalle Cartelle NAS, mai state sul telefono, restano protette dal cestino del NAS (30 giorni). Confermato dall'utente il 22/10 (correzioni dal campo, gruppo A, D2).
