# Fase 8 di MyCloud Gallery: progetto tecnico (Persone, da attivare)

> Base per i brief dei sub-agent. Progetto del 26/10/2026. Fonti:
> - piano: FASE 8 (8.1, 8.2), §1 (glossario: dati personali, replica locale, calcolo AI, ruoli), §2 ("Persone", "Sicurezza",
>   "Extra"), §3 (proposte 9 e **13**: i dati dei volti sono personali, stanno nell'area privata di chi attiva la funzione e ogni
>   familiare che la attiva rifà il calcolo per sé), §4 (robustezza, "nessuna azione distruttiva parte da dati letti dal NAS",
>   consumi, accessibilità), §6 (**E-F1–E-F6**, E-H1, E-A1–E-A19, E-I3, E-S11), §7 (volti = **dati biometrici**, contromisure 6, 7,
>   9, 10; limiti dichiarati), FASE 9 (ricordi con le persone, "mostra meno" per una persona) e FASE 10 (widget, revisione 10.4);
> - Fase 0: `docs/research/ocr-volti.md` (rilevatori, embedder, licenze, raggruppamento, GDPR), `docs/decisioni.md` §AI (YuNet MIT,
>   SFace Apache-2.0; esclusi InsightFace ed EdgeFace perché non commerciali);
> - i progetti delle fasi precedenti: `docs/fase4-progetto.md` (§2.9 dati personali firmati nell'area privata, D26, E-F5),
>   `docs/fase6-progetto.md` (D9, D10, D14–D22, **D41/D42** lettura del testo da attivare per account, D29, §2.1–§2.4, §2.9, P3, P4,
>   P5), `docs/fase7-progetto.md` (§0 come modello, D4, D13–D17, D22, D30, D33, §2.9, "Persone" nelle Raccolte), le sezioni della
>   Fase 6 e della Fase 7 di `docs/decisioni.md` (P0, P2, P3, P4, P5, P11, P11b, P13, correzioni A, B, C; Fase 7 P0, P2, P3, P4, P8,
>   P9, P11) e le revisioni in `docs/review/` (6-fase6, funzionale-6);
> - `docs/formato-nas.md` (§2.2, §2.3, §4.6, §5.5, §5.5-bis, §5.6, §5.8, §7, §9, §10), `docs/modello-dati.md` (§9, §13, §14),
>   `docs/sicurezza.md` (§1, §2, S1, S11, C19, **C20**, C21, C22, C27, **L14**, §7 "Fase 8"), `docs/famiglia-dispositivi.md` (§3
>   azzeramento), `docs/regole-attivita.md` (§2, §6), `docs/grafica-progetto.md` (§12.12, §12.13), `docs/inventario-nas.md` (cestino
>   delle share);
> - il **codice** da cui la fase parte, verificato il 26/10 sul commit `e2d77ca` (branch `fase8/progetto`): `core-model`
>   (`WorkState.kt`, `domain/WorkRules.kt`, `MediaId.kt`, `NasTypes.kt`), `core-personal` (`contract/Personal.kt`,
>   `codec/PersonalCodec.kt`, `sync/NasPersonalSync.kt`, `settings/SyncedSearchSettings.kt`, `contract/PersonalLimits.kt`),
>   `core-trust` (`envelope/Envelope.kt`, `TrustFormat.kt`, `data/IndexSegment.kt`, `data/DeviceHead.kt`, `data/OwnFiles.kt`,
>   `data/Leases.kt`, `nas/NasLayout.kt`), `core-models` (`contract/ModelCatalog.kt`, `source/BundledModelFetcher.kt`), `core-ai`
>   (`contract/TextReading.kt`, `cutout/LiteRtSessions.kt`, `compute/ComputeSession.kt`, `runtime/ModelInputs.kt`), `core-index`
>   (`contract/Work.kt`, `work/LeasedWorkUnits.kt`), `core-search` (`contract/Query.kt`, `contract/Search.kt`), `core-albums`
>   (`contract/Smart.kt`), `core-database` (`GalleryDatabase.kt`, `entity/AlbumV6Entities.kt`), `core-security`
>   (`credentials/AndroidSecretKeyStore.kt`), `feature-library` (`collections/CollectionSections.kt`,
>   `collections/testing/CollectionsSectionSamples.kt`), `feature-viewer` (`info/InfoBuilder.kt`), `app` (`graph/LocalData.kt`,
>   `src/test/.../hidden/HiddenSurfaces.kt`).
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F7-Dn`, `F6-Dn`, `F5-Dn`, `F4-Dn` = decisioni delle Fasi 7, 6, 5 e 4; "R6 della
> revisione B" = voce di revisione; `Fn` = prova sul campo (la Fase 7 arriva a F93, qui **F94–F110**); `Qn` = domanda del §9; `Pn` =
> pacchetto del §4.4; `Ln` = limite di `docs/sicurezza.md` (in `main` si arriva a L87 con P7 della Fase 7: qui **L88–L97**); `Cn` =
> contromisura (in `main` fino a C32: qui **C33–C37**). Se P10, P12 o P13 della Fase 7 prendono altri numeri prima, P0 rinumera e lo
> scrive in `docs/decisioni.md`. `G` = la **generazione** dei dati dei volti di un account (D3). "[stima]" = numero non misurato.
>
> Contesto:
> - **Le Fasi 5, 6 e 7 sono chiuse nel codice.** Della Fase 7 sono ancora in corso **P10** (schermate degli album), **P12**
>   (valutazione) e **P13** (integrazione in `:app`), più le revisioni di fine fase. Nel commit di partenza `e2d77ca` mancano anche il
>   motore degli album smart (P6 della Fase 7, sul suo branch) e viaggi ed eventi (P7, entrati in `main` dopo): la Fase 8 parte dai
>   **contratti** di `core-albums` e aggancia le persone al motore vero quando c'è (P10, §4.1).
> - **Le prove sul telefono sono indietro**: aperte F46–F71 (Fase 6), F72–F93 (Fase 7) e alcune della Fase 5 (blocco B). **La Fase 8 non
>   le aspetta.** Le sue prove sul campo (§5.6) girano **da sole** sull'S25 e su un emulatore con l'account `test`, si raccolgono insieme a
>   quelle in sospeso, e le parti con il tablet o con `test2` stanno in **un solo momento breve**.
> - **L'indice e i dati personali sulle share vere sono ancora "solo locali"** (F4-D31 acceso, F64 della Fase 6 non fatta): sulle share
>   vere `NasPersonalSync` si ferma a `LOCAL_ONLY`. La Fase 8 funziona in entrambi i casi: sulle share di prova sincronizza, sulle
>   share vere resta su ogni dispositivo finché l'utente non dice di sì (recinzione D34, Q5).
> - **In parallelo**: il filone grafico. La Fase 8 consegna stati e contratti con composable fatte solo con i componenti G2 (D38).

## 0. Sintesi

**Che cosa consegna la Fase 8**
- **Persone da attivare per account** (8.1): la sezione Persone delle Raccolte è sempre visibile; da spenta spiega cosa fa, dove restano
  i dati e come si cancella, con "Attiva riconoscimento dei volti". Il consenso vale per l'account (impostazione personale con la
  **generazione** dei dati e la versione del testo letto); gli altri dispositivi dell'account lo dicono una volta e si possono escludere
  ("Non su questo dispositivo"). Lo stesso modello della lettura del testo (F6-D41), ma qui nulla esce dalla casa e i dati non sono
  della famiglia: sono solo dell'account (D3–D5).
- **Modelli on-device con licenze compatibili**: rilevamento **YuNet** (MIT) ed embedding **SFace 2021dec** (Apache-2.0), convertiti
  una volta da ONNX al formato di **LiteRT** (il runtime già nell'app per il ritaglio), con l'impronta fissata e verificata a ogni
  caricamento; nessuna dipendenza nuova, nessun host nuovo con la risposta raccomandata a Q1 (D6–D10).
- **Calcolo dei volti dell'account** sugli indicizzatori dell'utente, sulle anteprime di analisi (con una seconda passata sull'HQ per le
  foto di gruppo e i volti piccoli), a tappe e con le regole di `AI_COMPUTE`, per le foto dell'area privata **e** dell'area comune
  (proposta 13: ogni familiare che attiva la funzione rifà il calcolo per sé) (D16–D19).
- **Dati dei volti solo nell'area privata dell'account**, in una cartella per generazione (`faces/<G>/`), firmati da ogni dispositivo
  come le teste dei dati personali, **mai** nell'indice condiviso né nell'area comune; sul dispositivo in un database a parte, con vettori
  e nomi cifrati da una chiave del Keystore che si distrugge alla cancellazione (D11–D15).
- **Raggruppamento incrementale e stabile**: assegnazione per esemplari con soglia e margine, nascita di una persona solo da almeno tre
  volti di almeno due foto, vincoli "stessa foto" e correzioni dell'utente, manutenzione notturna; un solo "custode" per account
  pubblica istantanee numerate, così gli id delle persone sono gli stessi su ogni dispositivo e sopravvivono ai ricalcoli (D20–D23).
- **Nomi e gestione** (8.2): rinomina, unisci, dividi, "Non è…", "È…", ignora un volto, nascondi o dimentica una persona, pagina
  persona, proposte "È la stessa persona?"; tutto sincronizzato fra i propri dispositivi (E-F5) (D24, D25).
- **Ricerca per persona** nel motore della Fase 6 (chip "Persona", anche con date, luoghi e contenuto) e **persone negli album smart**
  della Fase 7 (regola "con Marco", "con Marco e Anna") (D26, D27).
- **Nascosti mai**, su ogni superficie nuova registrata in `HiddenSurfaces` (D28).
- **"Disattiva e cancella"** che toglie tutto: chiave locale distrutta per prima, dati locali, cartella della generazione sul NAS per
  **tutti** i dispositivi dell'account, nomi, correzioni, ricerche recenti con i nomi; un dispositivo spento che si ricollega cancella i
  suoi dati prima di pubblicare qualunque cosa e non ripopola il NAS; la pagina dice dispositivo per dispositivo cosa manca (D29–D31).

**Moduli nuovi**
- `core-faces`: attivazione e consenso, calcolo (unità, ingressi, passate, salvataggio), raggruppamento, persone (nomi, correzioni,
  viste), sincronizzazione dei dati dei volti con il NAS, "Disattiva e cancella", archivio cifrato. **Non vede** `core-nasops`,
  `core-backup`, `core-nas`, `core-search` e `core-albums` (implementazioni) né la rete: cancella, cerca e alimenta gli album attraverso
  porte sue, legate da `:app` (§1.1).
- `feature-people`: sezione Persone, pagina persona, revisione dei volti, fogli di unione, attivazione, avviso, impostazioni e
  "Disattiva e cancella".

**Aggiunte ai moduli esistenti**
- `core-trust`: testa dei volti (**tipo 9**), classi `faces` (18), `cluster` (19) e `people` (20), codec ostile, **versione 4** del formato
  solo per i file `faces/`, percorsi `faces/<G>/…` e prenotazioni sotto la generazione (D12);
- `core-ai`: motori YuNet e SFace su LiteRT (`faces/`), allineamento e qualità, `ModelInputs.forFaces`, una porta perché il buffer di
  analisi tenga le anteprime che servono ancora ai volti (D17);
- `core-models` (catalogo: due modelli nell'APK, chiesti solo dove Persone è attiva), `core-database` (database dei volti in un **file a
  parte**, schema proprio v1, D15), `core-personal` (campo `faces` di `SETTING`, gancio prima della pubblicazione, D3, D30),
  `core-security` (chiave dei volti per generazione), `core-nasops` (cancellazione stretta di `faces/<G>/`, D29, D31), `core-search`
  (chip `Person`, dizionario dei nomi, D26), `core-albums` (regola `people` degli album smart, D27), `core-model` (`PersonId`,
  `FaceGeneration`, `FaceSpaceCode`), `core-common` (recinzione, sezione della Diagnostica, test d'architettura), `feature-viewer` (riga
  "Persone" nelle info, voci sui volti), `feature-library` (testo della sezione Persone), `feature-search`, `feature-albums`,
  `feature-settings`, `feature-diagnostics`, `:app`.

**Quattordici pacchetti** (§4.4). Opus per i quattro fondanti o difficili: **P0** (fondamenta, contratti, recinti), **P3** (formato
firmato dei dati dei volti e sincronizzazione), **P5** (raggruppamento stabile), **P8** ("Disattiva e cancella"); sonnet per gli altri
dieci. Tutti con un **agente interno** (OpenCode non è più disponibile).

**Tappe**
- **M1** (8.1, sul telefono): sull'S25 (debug, account `test`) attivazione, modelli verificati, calcolo dell'area di prova con i ritratti
  di pubblico dominio, persone formate, nomi, "Non su questo dispositivo" che cancella in locale.
- **M2** (8.1 e 8.2, fra dispositivi): con un secondo dispositivo dell'account `test` (emulatore) avviso ed esclusione, nomi, unioni e
  divisioni che viaggiano (E-F5); "Disattiva e cancella" con il secondo dispositivo spento che torna (E-F6); banco delle interruzioni del
  calcolo e della cancellazione.
- **M3** (8.2 e qualità): ricerca per persona, album smart con persone; **≥ 97% dei gruppi senza persone sbagliate** sui dati veri
  dell'utente (F105); prestazioni su S25 e Tab S9.
- Chiusura: revisione di sicurezza e verifica funzionale in parallelo; con il consenso (Q5) la sincronizzazione delle persone sulle
  share vere (F110).

**Test senza dispositivo**: motori finti dei volti (rilevamento e vettori deterministici da una tabella di "identità" sintetiche, con
gemelli, età diverse, profili e rumore), NAS finto e simulatore SMB delle Fasi 4–7 (con le vere `SmbNasFileOps`), famiglia finta con più
account e dispositivi (approvati, dismessi, revocati, spenti), Libreria sintetica da 100 mila elementi, chiave del Keystore finta; fake
fedeli con test di parità (§5.2).

**Cosa le fasi precedenti hanno lasciato pronto** (verificato nel codice)
- **La fase di lavoro dei volti**: `WorkStage.FACES("faces")` (`core-model/.../WorkState.kt:25`), fuori dalle fasi iniziali di ogni
  media (`domain/WorkRules.kt:17-22`: "si calcolano solo dopo l'attivazione esplicita").
- **Un tipo di voce riservato ai nomi**: `PersonalKind.PERSON("person", reserved = true)` (`core-personal/.../contract/Personal.kt:40-41`);
  chi legge lo scarta voce per voce con `RESERVED_KIND` (`Personal.kt:447,463`) e il codec lo riconosce (`PersonalCodec.kt:130-136`),
  quindi un'app vecchia non butta il segmento intero.
- **Un nome di classe riservato**: `SegmentClass.RESERVED_WIRE = {"faces"}` (`core-trust/.../data/IndexSegment.kt:100`): una testa
  `index`, `personal`, `ai` o d'album che elenca `faces` è rifiutata; i codici 18–31 sono liberi. I tipi dei file firmati usano 1–8
  (`envelope/Envelope.kt:18-42`), le versioni del formato 1 (indice, dati personali), 2 (`ai`) e 3 (`albums/`) (`TrustFormat.kt:24,38`).
- **Modelli fissati e verificati**: `ModelSpec.bundledAsset` per un modello dentro l'APK (`core-models/.../ModelCatalog.kt:108,113`),
  `BundledModelFetcher`, verifica di dimensione e SHA-256 a ogni apertura (`LocalModelFiles`), file in sola lettura (revisione B, I1),
  `needed(roles)` (`ModelCatalog.kt:189`); **LiteRT** 2.3.0 con l'API `Interpreter` su CPU e il modello mappato sul descrittore
  verificato (`core-ai/.../cutout/LiteRtSessions.kt`), senza delegati, senza rete (F67).
- **Il calcolo AI**: `AiComputeEngine`, unità con prenotazioni (`LeasedWorkUnits`), buffer `DiskAiBuffer` (500 MB), `AnalysisFetcher`,
  preparazione dai byte del telefono (`AiPhone`), `ModelInputs` che legge le intestazioni prima di decodificare, `StagedRun` con
  `AI_COMPUTE`, `GpuTrial`, `AiWorkScheduling` con i nomi dei lavori corretti (IX-1).
- **Il modello di consenso della lettura del testo**: `TextReadingSwitch` con `AwaitingNotice` (l'avviso sugli altri dispositivi),
  esclusione locale, `TextReadingConsent` costruito solo dalla pagina (test d'architettura), versione del testo accettato
  (`core-ai/.../contract/TextReading.kt:27,41`), impostazioni che valgono subito anche senza l'ora del NAS (`effectiveEntries`, revisione B).
- **La sincronizzazione dei dati personali**: teste firmate per dispositivo nell'area privata, ultima scrittura che vince, stato unito
  ripubblicato da ogni dispositivo, anti-rollback (`NasPersonalSync.kt`), lettura difensiva (`PersonalLimits`).
- **Il posto nelle Raccolte**: `CollectionSections.PEOPLE = "people"`, quarta nell'ordine predefinito e fra le sezioni riservate
  (`feature-library/.../collections/CollectionSections.kt:27,48,55`); un fornitore di sezione (`CollectionsSectionProvider`) basta,
  "nessun codice speciale nella scheda" (P11 della Fase 7).
- **I Nascosti da una sola fonte**: `VisibleMedia`, `HiddenMask` in `GalleryGraph`, il registro `HiddenSurfaces` con il test di proprietà
  che fallisce per una superficie nuova non registrata (`app/src/test/.../hidden/HiddenSurfaces.kt`).
- **La regola degli album smart si può scrivere senza schema nuovo**: `album_rule(album_id, rule, position, value)` ha `rule` e `value`
  testuali (`core-database/.../entity/AlbumV6Entities.kt:27-54`).
- **La cancellazione locale**: `AppLocalDataEraser` con gli elenchi dei file per fase (`app/.../graph/LocalData.kt:93,235`),
  l'azzeramento in fasi con le chiavi per prime (`docs/famiglia-dispositivi.md` §3), `SecretKeyStore` per chiavi AES-GCM del Keystore che
  non chiedono lo sblocco (`core-security/.../AndroidSecretKeyStore.kt`).
- **Nei documenti**: C20 ("Disattiva e cancella" senza tracce, nessun dato esportato, nessuna inferenza di attributi, dati cifrati a
  riposo), L14 (dati biometrici, dataset dei modelli), la checklist della Fase 8 in `docs/sicurezza.md` §7.

**Cosa le fasi precedenti promettevano per la Fase 8 e nel codice non c'è** (la Fase 8 lo costruisce o lo cambia, §0.3)
1. **Niente dei volti esiste**: nessun motore, nessun modello nel catalogo, nessun formato (solo i nomi riservati), nessun modulo,
   nessuna tabella ("i volti restano alla Fase 8", `docs/modello-dati.md` §9).
2. **I nomi delle persone erano pensati nei dati personali** (E-F5 della 4B: "tipo di voce riservato per la Fase 8"). Lì una cancellazione
   lascerebbe i nomi nelle teste vecchie di ogni dispositivo (le ultime tre restano 7 giorni) e in quelle dei dispositivi spenti, che il
   dispositivo che cancella non può toccare (contengono anche preferiti e album). La Fase 8 li mette nella cartella dei volti; `PERSON`
   resta riservato e rifiutato nelle teste `personal` (D13).
3. **C20 promette "dati dei volti cifrati a riposo con chiave nel Keystore"**: una chiave del Keystore è di un dispositivo, mentre i dati
   sul NAS servono a tutti i dispositivi dell'account. La Fase 8 la realizza sul dispositivo (con la cancellazione crittografica) e
   dichiara perché non sul NAS (D14, L88).
4. **Il testo della sezione Persone è sbagliato**: "Raggruppa le foto per persona, solo su questo telefono"
   (`CollectionsSectionSamples.kt:82`): i dati viaggiano fra i dispositivi dell'account (P9 lo corregge).
5. **La riga "Persone" del pannello info** è promessa (`InfoBuilder.kt:49-50`, piano 3.x) e non c'è.
6. **Gli album smart non hanno le persone** (`SmartRules` senza regola, `core-albums/.../contract/Smart.kt:122-137`; piano 7.2 "le persone
   dalla Fase 8") e il motore vero (P6 della Fase 7) non è nel commit di partenza.
7. **La ricerca non conosce le persone**: `QueryParser.parse(text, today, places)` (`core-search/.../contract/Query.kt:125`) non ha un
   dizionario dei nomi; la cronologia salva le etichette dei chip come testo (`Search.kt:265`), quindi anche i nomi (D26, D29).
8. **Le prenotazioni hanno una radice fissa** (`NasLayout.leaseDir`, `NasLayout.kt:73-81`): quelle dei volti devono stare sotto la
   generazione, perché la cancellazione tolga anche loro (D16).
9. **La pulizia dei propri file non conosce `faces/`** (`OwnFile.classify` riconosce solo `devices/`, `leases/`, `tmp/`,
   `data/OwnFiles.kt:31-63`) e **il §9 del formato ammette una sola conseguenza distruttiva di un dato remoto** (l'auto-azzeramento): la
   Fase 8 ne aggiunge una, stretta e dichiarata (D31).
10. **Il buffer di analisi butta le anteprime appena vettori e testo sono fatti** (`ComputeSession.pruneFinished`,
    `core-ai/.../compute/ComputeSession.kt:101-111`): attivando Persone dopo, le anteprime andrebbero riscaricate o rifatte (D17).
11. **`NasPersonalSync` non ha un gancio prima della pubblicazione**: serve per garantire che un dispositivo dichiari "volti cancellati"
    solo dopo averli cancellati davvero (D30).
12. **`ModelCatalog.needed(roles)` guarda solo i ruoli**, non le funzioni attive; **l'azzeramento** cancella solo il database della
    galleria (`LocalData.kt:174`) e non conosce chiavi diverse da quelle del dispositivo e delle credenziali.
13. **Nessuna sezione della Diagnostica** per le persone.

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)

| # | Decisione | Perché |
|---|---|---|
| D1 | **Una fase sola, tre tappe** (M1 sul telefono, M2 fra dispositivi, M3 ricerca, album e qualità). **Nulla tocca i media.** Sul NAS si scrivono solo file nuovi sotto `.mycloudgallery/faces/<G>/` dell'**area privata** dell'account (con la sola `publishExclusive`) e la voce `faces` dei dati personali; le sole cancellazioni sono di **dati dei volti**, con le regole di D29–D31, e le esegue `core-nasops` | Nessun file di media si sposta, si copia o si toglie: la fase aggiunge dati derivati e personali, con le difese già provate |
| D2 | **Moduli e recinti**: `core-faces` (attivazione, calcolo, raggruppamento, persone, sincronizzazione, cancellazione, archivio) e `feature-people` (schermate); i motori stanno in `core-ai/.../faces/` (LiteRT solo in `core-ai`, `Phase6ArchitectureTest`). `core-faces` vede `common`, `model`, `trust` (codec dei volti, `NasLayout`, `NasFileSystem` **solo** in `sync/`), `index` (contratti: cancello, prenotazioni, esiti remoti), `personal` (contratti), `ai` e `models` (solo contratti), `database` (solo il database dei volti), `library` (`VisibleMedia`, `HiddenMask`), `security` (contratto della chiave); **non vede** `nasops`, `backup`, `nas`, `nasmedia`, `search`, `albums` né la rete. Cancellazione sul NAS, ricerca e album passano da porte (`FacesEraseOps`, `PeopleLookup`, `PeopleMembership`) legate da `:app` | Nessun ciclo; il raggruppamento e la cancellazione si provano sulla JVM con fake; nessuna strada nuova verso operazioni distruttive (test d'architettura) |
| D3 | **Attivazione per account**: voce `SETTING` `faces` (nessun `PersonalKind` nuovo, F7-D4) con valore testo `1\|on\|<G>\|<v>` o `1\|off\|<G>`: `G` = **generazione**, 128 bit casuali in base32 (26 caratteri), nuova a ogni attivazione; `v` = versione del testo della pagina accettato. Voce assente = spenta; forma sconosciuta (un'app più nuova) = niente calcolo, niente pubblicazione, **nessuna cancellazione**, "Aggiorna l'app". La **pagina di attivazione** (§2.1: cosa fa, dove restano i dati, come si cancella, amici e bambini, niente attributi, modelli e licenze) è l'unica che costruisce `FacesActivationConsent` (test d'architettura); la scelta vale **subito** su questo dispositivo anche senza l'ora del NAS (`effectiveEntries`, revisione B) e viaggia alla prima sincronizzazione | È il modello della lettura del testo (F6-D41), che il piano chiede ("da attivare per account"); la generazione rende impossibile che dati di un'attivazione vecchia tornino dopo una cancellazione (E-F6) |
| D4 | **Gli altri dispositivi dell'account**: lo dicono **una volta** (`FacesState.AwaitingNotice`) e finché non si vede l'avviso **non scaricano, non calcolano e non mostrano** niente; "Non su questo dispositivo" è un'**esclusione locale** (file `faces-local`, mai una voce): niente calcolo, nessuna copia dei dati dei volti, dati locali cancellati subito (chiave per prima, D14), la sezione Persone lo dice e si riattiva (scaricando di nuovo dal NAS). Una versione nuova del testo richiede di nuovo l'avviso | Un telefono o un profilo del tablet può restare fuori (richiesta del brief); come per la lettura del testo (LT-2, R8), un dispositivo non tratta dati biometrici prima che l'utente lo sappia |
| D5 | **Requisiti per attivare**: un'**area privata** dichiarata diversa dall'area comune (L23; altrimenti "Serve un'area privata sul NAS") e almeno un **indicizzatore** dell'account (stati firmati dei dispositivi, ultimi 7 giorni, F6-D14); senza indicizzatori la pagina propone il ruolo su questo dispositivo, con la stima di D10 | I volti si calcolano solo dagli indicizzatori dell'utente (8.1) e stanno solo nell'area privata (proposta 13) |
| D6 | **Modelli e runtime**: rilevamento **YuNet** (OpenCV Zoo, MIT) ed embedding **SFace 2021dec** (OpenCV Zoo, Apache-2.0). I file ufficiali sono **ONNX**: si convertono **una volta, sul PC**, in `.tflite` con uno strumento riproducibile (`tools/models/faces/`: versioni fissate degli strumenti, impronte dei file d'ingresso e d'uscita, test di parità con l'ONNX) e girano con `org.tensorflow.lite.Interpreter` di **`litert` 2.3.0, già nell'app**, su **CPU** a 4 thread, con il modello mappato sul descrittore verificato come MagicTouch. Niente delegato GPU (servirebbe un artefatto nuovo), niente NNAPI, niente NPU | Il runtime c'è già ed è provato senza traffico (F67); ONNX Runtime aggiungerebbe una libreria nativa più grande dei modelli; sulla CPU bastano pochi millisecondi a volto (D10) |
| D7 | **Versioni, formato e impronte** (§2.2). Sorgenti: YuNet `2023mar` (232.589 B, SHA-256 `8f2383e4…`) e `2026may` (229.738 B, `ebafce4e…`); SFace `2021dec` fp32 (38,70 MB, `0ba9fbfa…`) e int8 (9,90 MB, `2b0e941e…`, solo riferimento). **YuNet**: si convertono le due e si sceglie con una regola **scritta prima di misurare** (richiamo dei volti sotto 48 px sui ritratti di prova a parità di falsi positivi, poi latenza; a pari merito `2023mar`); ingresso fisso **1024×1024** con bordi (letterbox). **SFace**: da fp32 a `.tflite` con pesi **float16** (~19,4 MB [stima]); int8 dinamico (~10 MB [stima]) solo se il coseno con fp32 è ≥ 0,995 su 300 volti di prova **e** la purezza sul set sintetico non cala. Spazio `sface-2021dec-v1`: 128 dimensioni, calcolate in float32, salvate in float16. Le impronte dei `.tflite` le fissa P1 sul file del repository (catalogo e `CatalogMatchesDesignTest`); verifica di dimensione e SHA-256 **a ogni caricamento** (F6-D16) | Il brief chiede versioni, formato per LiteRT, impronte e dimensioni; le impronte d'ingresso sono quelle verificate nella ricerca 0.4 (Git LFS, 12/10); una regola scritta prima evita di scegliere guardando il risultato (come MagicTouch, P13 della Fase 6) |
| D8 | **Dove stanno i modelli** (Q1, raccomandata (a)): **dentro l'APK** come asset di `core-ai` (`assets/faces/`, con LICENSE e NOTICE), come MagicTouch (risposta Q11 (a) della Fase 6); si estraggono in `models/<sha256>` **solo** dove Persone è attiva (dopo l'avviso) e si tolgono con l'esclusione e con "Disattiva e cancella"; mai sullo scaffale del NAS. Con Q1 (b): voce HTTPS fissata su un host **già ammesso** (Hugging Face, copia pubblicata dall'utente con commit fissato) e scaffale come F6-D18 | Circa 20 MB in tutto: funzionano subito, offline e senza NAS, nessun host in più; con la (b) l'APK è più leggero ma serve una copia pubblicata e un passo in più |
| D9 | **Qualità attesa e piano B.** SFace: 99,40% su LFW (fp32), 99,32% (int8), **nessun numero pubblicato** su profili ed età; YuNet: AP 0,708–0,750 su WIDER FACE "hard" (il README ne riporta due). Le debolezze attese (profili oltre 45°, occhiali da sole, volti sotto 48 px, bambini che crescono, gemelli) si gestiscono con i livelli di qualità (D19), il margine (D20), il vincolo "stessa foto" e gli strumenti manuali (D24). **La misura che decide è sui dati veri** (F105). **Piano B**, solo licenze compatibili: AuraFace-v1 (Apache-2.0, solo `glintr100.onnx`, 261 MB, scaricato da Hugging Face, host già ammesso; provenienza da verificare), poi dlib ResNet (CC0), MobileFaceNet (Apache-2.0) solo se servisse velocità; **mai** InsightFace, EdgeFace, GhostFaceNets | Le cifre su LFW non misurano profili ed età (ricerca 0.4, §3.2); un ResNet100 da 261 MB è pesante ma gestibile di notte e con lo scaricamento |
| D10 | **Prestazioni attese** [stima, §2.12]: S25 **~30–50 ms a foto** (decodifica dell'anteprima, YuNet a 1024, uno o due volti con SFace), Tab S9 ~45–80 ms; seconda passata HQ ~100–200 ms sul ~20% delle foto; l'archivio dell'utente (~15.000 foto e ~2.600 video) in **~25–35 minuti** di calcolo sull'S25, di notte in carica; PSS +80–150 MB col calcolo in corso; raggruppamento iniziale di ~20.000 volti in meno di un minuto. P2 misura sui dispositivi e scrive `DeviceMeasures.facesPerSecond`; F104 conferma | Ordine di grandezza da YuNet (~76 mila parametri) e MobileFaceNet (~1 M, ~0,2 GFLOP a volto) contro i 5,7 foto/s del 440M sulla GPU già misurati (P4 della Fase 6) |
| D11 | **Dati dei volti solo nell'area privata dell'account**: `<area privata>/.mycloudgallery/faces/<G>/<dispositivo>/head/<versione>` e `.../seg/<impronta>`; **mai** nell'indice condiviso, **mai** nell'area comune, anche per le foto dell'area comune (proposta 13); li leggono solo i dispositivi approvati (o dismessi, con le teste congelate) **dello stesso account**. Un account diverso non li legge (SMB) e un indicizzatore non calcola mai sull'area privata di un altro (F6-D14). **Ogni account che attiva rifà il calcolo per sé**, anche per le foto comuni | È la proposta 13 del piano; i volti sono la categoria più delicata (L14) |
| D12 | **Formato firmato** (§2.4): testa **tipo 9** (`FACES_HEAD`, **versione 4** del formato solo per `faces/`), una serie per dispositivo e generazione; classi **`faces` (18)**: rilevamenti e vettori per chiave di contenuto, corpo binario; **`cluster` (19)**: istantanea del raggruppamento, solo dal custode (D21); **`people` (20)**: nomi e correzioni, voci con l'ultima scrittura che vince, **stato unito ripubblicato da ogni dispositivo** (come F4-D26). Regole di chi legge come le altre teste: firmatario approvato o dismesso **del proprio account** (`nasUser`), posto (generazione, cartella, numero), **generazione corrente**, anti-rollback ed equivocazione, tetti, ora non oltre 2 minuti nel futuro, campo sconosciuto = segmento rifiutato. Pubblicazione: solo `publishExclusive` sotto `faces/<G>/<io>/`, una testa ogni 5 minuti, voci `faces` solo per chiavi di contenuto di media del NAS di questo account ("M3 dei volti") | Le stesse difese dei dati personali e degli album, già provate; ogni dispositivo scrive solo nella sua cartella; un'app vecchia non apre `faces/` e, se aprisse un file, la versione 4 sarebbe "più nuova" (E-I3) |
| D13 | **Nomi e correzioni nella classe `people`**, non nei dati personali: `PERSON` resta riservato e **rifiutato** nelle teste `personal` (test). Le voci nominano una persona (`PersonId`) o un volto per **posto** (D23) | Così "Disattiva e cancella" toglie anche i nomi con la cartella della generazione, compresi quelli dei dispositivi spenti; nei dati personali resterebbero nelle teste vecchie e in quelle degli altri dispositivi (§0, punto 2) |
| D14 | **Cifratura.** **Sul dispositivo**: vettori, centroidi e nomi cifrati AES-256-GCM con una chiave dati casuale, avvolta da una chiave del Keystore **per generazione** (`mcg-faces-<G>`, StrongBox se c'è, senza sblocco né biometria come le credenziali: il calcolo gira di notte); la cancellazione distrugge **per prima** la chiave del Keystore, così ogni resto (pagine libere di SQLite, WAL, blocchi della flash) diventa illeggibile. **Sul NAS** i dati restano in chiaro, firmati, protetti dai permessi dell'area privata, come indice e dati personali (L26, L29, L46) | Realizza C20 dove ha senso. Sul NAS una chiave dovrebbe essere comune ai dispositivi dell'account (ricavata dalla password del NAS: cambiarla farebbe ricalcolare tutto) e non proteggerebbe da chi ha la password o i dischi, che hanno anche le foto da cui i vettori si ricalcolano con modelli pubblici (L88) |
| D15 | **Database a parte** `faces-<G>.db` in `core-database` (`FacesDatabase`, schema proprio **v1** esportato in `schemas/`): volti, persone, esemplari, assegnazioni, voci `people`, segni anti-rollback, outbox, unità fatte. Il database della galleria **resta v6** | Un file si cancella e si verifica ("non c'è"); nessuna migrazione sul campo per una funzione che molti non attivano |
| D16 | **Chi calcola e come ci si coordina**: gli **indicizzatori dell'account con Persone attiva qui**; lavoro `FacesComputeWorker` con le regole di `AI_COMPUTE` e `StagedRun` (tappe da 8 minuti), dopo il calcolo AI della stessa notte; **unità** `faces/<G>/work/<area>/<mese>/<parte>` (`area` = `priv` o `comm`, mai il nome della share; 200 media per chiave di contenuto) prenotate **nell'area privata**, sotto `faces/<G>/leases/`; "fatto" = una voce `faces` della ricetta corrente di **un** dispositivo dell'account, oppure "non supportato"; "Analizza ora" = `UserInitiated(AI_COMPUTE)` | Le unità della Fase 6 stanno nella share e sono comuni a tutti gli account: un'unità dell'area comune fatta da un altro familiare non direbbe niente dei volti di questo account; sotto la generazione la cancellazione toglie anche le prenotazioni |
| D17 | **Ingressi** (§2.3): l'**anteprima di analisi** (1024 px) dal buffer, poi dall'indice della share (blob attestati, `AnalysisFetcher`), poi dai byte del telefono (`Preparer`); **seconda passata sull'HQ** (2048 px, quattro tasselli da 1024 con 128 px di sovrapposizione) per foto con un volto sotto 40 px o con almeno 5 volti; il ritaglio per SFace dalla sorgente più nitida già disponibile. Se l'anteprima non c'è da nessuna parte (share vere con F4-D31 e buffer già svuotato), un indicizzatore che è anche **Preparatore** la rifà dall'originale del NAS come una preparazione (solo `analysis`, attività pesante, Wi-Fi di casa) e la tiene nel buffer; altrimenti il media aspetta. **Il buffer non butta un'anteprima che serve ancora ai volti** (porta `BufferKeepers` chiesta da `ComputeSession.pruneFinished`) | "Sulle anteprime, mai sugli originali" (6.2); la regola dei 16 px per l'OCR ha il suo analogo nei ~48 px per un embedding affidabile (ricerca 0.4, §2.1) |
| D18 | **Cosa si analizza**: foto, foto in movimento, panorami, RAW (dalle anteprime), **video** (copertina e fino a 3 fotogrammi del blob `frames`, volti al più deboli: non fanno nascere persone, E-F17). **Non** si analizzano screenshot e registrazioni dello schermo (sorgente `SCREENSHOT`), documenti (`CellFlags.DOCUMENT`), ritagli (`Ritagli/`). I **nascosti** si calcolano e non si mostrano mai (come F6-Q6) | Negli screenshot i volti sono quasi sempre di estranei (social, chat); un documento d'identità non serve a raggruppare; meno dati biometrici di terzi (D33) |
| D19 | **Livelli di qualità, nessun attributo**: **CORE** (lato corto ≥ 48 px nella sorgente dell'allineamento, punteggio del rilevatore ≥ 0,80, imbardata stimata dai cinque punti ≤ 35°, nitidezza sopra soglia), **WEAK** (da 24 a 48 px, profilo fino a 60°, sfocato, fotogrammi dei video), **NONE** (sotto 24 px o oltre 60°: nessun vettore, solo "1 volto non riconoscibile" nelle info). Solo i CORE fanno nascere persone; i WEAK si uniscono a una persona solo con una somiglianza più alta (D20). **Nessuna stima** di età, genere, etnia, emozioni o accessori | Un volto piccolo o di profilo dà vettori poco affidabili e farebbe catene di errori (ricerca 0.4, §5.2); gli attributi sono categorie delicate e non servono (C20) |
| D20 | **Raggruppamento incrementale** (§2.5). (1) **Assegnazione**: un volto entra nella persona migliore se la media dei 3 esemplari più simili è ≥ `T_join` (partenza 0,45; 0,55 per i WEAK) **e** supera la seconda persona di almeno `M` (0,06), senza vincoli contrari; altrimenti va fra i **non assegnati**. (2) **Nascite**: agglomerazione a **legame medio** sui non assegnati CORE (archi ≥ 0,45, arresto sotto `T_birth` 0,48); una persona nasce con **≥ 3 volti di ≥ 2 foto**. (3) **Manutenzione notturna**: fusione automatica se il legame medio fra gli esemplari è ≥ `T_merge` (0,52), le due persone non sono **mai insieme in una foto**, non hanno entrambe un nome e non sono "separate"; fra 0,40 e `T_merge` una proposta "È la stessa persona?"; i volti automatici sotto 0,32 tornano fra i non assegnati; esemplari (≤ 16) rinnovati. Vincoli sempre: due volti della stessa foto mai la stessa persona; le correzioni dell'utente (D23). Soglie calibrate in P11 (§5.3) | Il legame singolo (union-find) fa catene e non regge il 97%; gli esemplari con il margine sono il metodo di Immich, la passata notturna a legame medio quello di Apple (ricerca 0.4, §5.3); il "mai insieme in una foto" separa gemelli e fratelli (E-F1) |
| D21 | **Un solo custode del raggruppamento per account**: la prenotazione `faces/<G>/cluster` (1 ora, rinnovo ogni 20 minuti) la prende un indicizzatore con Persone attiva; pubblica **istantanee numerate** (`seq`, `base`) nella classe `cluster`. Gli altri dispositivi leggono l'istantanea con `seq` più alto (a pari numero, il dispositivo minore) e assegnano in modo **provvisorio e solo locale** i volti che non contiene ancora. Due custodi insieme (prenotazione contesa): vince il numero più alto, l'altro si **riallinea** (le persone nate solo da lui si abbinano per volti; senza abbinamento rinascono con lo stesso id; i nomi non si perdono). Un custode spento oltre l'ora: un altro riparte dall'ultima istantanea | Un raggruppamento incrementale dipende dall'ordine di arrivo: farlo su ogni dispositivo darebbe gruppi e id diversi; un custode solo dà gli **stessi id ovunque** (requisito del brief) |
| D22 | **Stabilità degli id**: `PersonId` casuale a 128 bit, nato con la persona (o con "Dividi"); **unire = alias** (`alias` P → Q), mai un id che cambia; catene e cicli di alias si risolvono in modo deterministico (radice = id minore del ciclo). Un **ricalcolo completo** (modello, spazio o ricetta nuovi, E-F24) raggruppa da capo e poi **abbina** i gruppi nuovi ai vecchi per volti in comune (riquadri con IoU ≥ 0,5, assegnazione ottima), così id, nomi e correzioni si ereditano; una persona con nome che non si ritrova resta con il nome e "0 foto · ricontrolla" | Album smart, ricerche e ricordi (Fase 9) puntano a id di persone: un id che cambia li romperebbe; un nome non si perde mai in silenzio |
| D23 | **Correzioni come vincoli permanenti**, riferite al volto per **posto** (chiave di contenuto, fotogramma, riquadro; un volto attuale corrisponde con IoU ≥ 0,5), non per indice: "È …" lo fissa nella persona, "Non è …" lo esclude per sempre da quella persona, "Ignora questo volto" lo toglie da tutto (poster, TV, statue: E-F3), "Separati" fra due persone ne vieta l'unione automatica | Le correzioni sopravvivono a un ricalcolo con una ricetta diversa (indici diversi, stessi posti) (E-F1, E-F2) |
| D24 | **Gestione** (8.2, §2.6): Rinomina (1–64 caratteri, regole di `TextRules`), **Unisci** (con due nomi si sceglie quale tenere), **Dividi** (si scelgono i volti → persona nuova, "separata" dalla prima), "Non è …", "È …" (persona esistente o nuova), Ignora volto, **Nascondi persona** (sparisce da Persone, suggerimenti e ricerca; le foto restano in Libreria), **Dimentica persona** (gruppo e nome tolti, i suoi volti ignorati; **nessuna impronta** per riconoscerla dopo: con foto nuove può riformarsi e si dimentica di nuovo), Copertina; tutto sincronizzato fra i propri dispositivi (E-F5) | Le voci del piano 8.2; "Dimentica" senza impronte negative tiene il minimo di dati biometrici di chi non si vuole riconoscere |
| D25 | **Cosa si vede** (§2.6): sezione **Persone** delle Raccolte (fino a 8 persone e "Tutte le persone"; da spenta la riga "Attiva…"); **pagina persona** (foto in ordine di tempo, azioni, "Controlla le foto", "Forse è anche…", "Crea album smart"); **"Altri volti"** per le persone senza nome (Q2 (b)); riga **"Persone"** nelle info del viewer con "Chi è?". Una persona compare con almeno 3 foto visibili o un nome. **Le copertine si ritagliano al momento** dall'immagine migliore già sul dispositivo e **non si salvano** (cache solo in memoria) | Piano 8.1 e 8.2; "nessun ritaglio di volti salvato se evitabile" (checklist della Fase 8) |
| D26 | **Ricerca per persona** (§2.7): chip `SearchChip.Person` dal **dizionario dei nomi** (persone con nome, non nascoste, con almeno un media visibile; nomi normalizzati come i luoghi, anche di più parole); "Marco al mare", "Marco e Anna 2024": persone (una o tutte) come vincolo, il resto a date, luoghi e contenuto; ambiguità con feste e luoghi ("Natale", "San Marco") nel chip con l'alternativa (come E-Q10); candidati dalla porta `PeopleLookup` (celle con un volto assegnato, membri del gruppo compresi); a riposo le persone più frequenti fra i suggerimenti; dove i volti non ci sono (spenti, esclusi) il nome resta testo | Piano 6.3 ("le persone si aggiungono in Fase 8") e 8.2; `core-search` non vede `core-faces` (D33 della Fase 6: nessuna rete, nessun NAS nella ricerca) |
| D27 | **Album smart con persone** (§2.7): regola `smart:people` (`any:` o `all:` seguito dagli id) nei dati personali (F7-D4) e riga `album_rule` `people`; **sicuro** = volto assegnato (automatico sopra `T_join`, o confermato); **suggerito** = volto fra 0,40 e `T_join` per quella persona; si combina in "e" con periodo, luogo, sorgenti e tipi; "Crea album smart" dalla pagina persona; su un dispositivo senza volti la regola non inserisce niente e l'album lo dice; persona dimenticata = avviso; album smart condiviso: regole personali, foto nuove in "Da condividere" (F7-D17) | Piano 7.2 ("le persone dalla Fase 8"); nessun dato dei volti nell'area comune: si condividono solo le foto, con la conferma |
| D28 | **Nascosti** (§2.9): righe nuove del registro `HiddenSurfaces` con la riga del §2.1 **`PEOPLE`**: elenco delle persone e conteggi, copertine, pagina persona, "Altri volti", riga delle info e "Chi è?", proposte di unione, chip e suggerimenti della ricerca, regola degli album smart, revisione dei volti. I volti dei nascosti si calcolano e non si mostrano mai; una persona con soli media nascosti non compare; candidati da `VisibleMedia`, filtro finale `HiddenMask` | F7-D22 e C27: una superficie nuova senza test fa fallire il registro |
| D29 | **"Disattiva e cancella"** (protocollo al §2.8): azione **locale confermata** (`FacesEraseConfirmation`, costruita solo dal foglio: test d'architettura); diario `faces-erase` scritto **prima** di tutto; calcolo e pubblicazione spenti subito; **cancellazione locale** con la chiave del Keystore **per prima**, poi database, cache in memoria, modelli estratti, ricerche recenti con i nomi (D26), segni dei lavori; voce `faces = off\|G`; **sul NAS**, appena raggiungibile, l'**intera** `faces/<G>/` (cartelle di **tutti** i dispositivi dell'account e prenotazioni) con un'operazione stretta di `core-nasops`; verifica ripetuta finché ogni dispositivo dell'account non ha confermato (D30). Album smart con persone: "Tieni le foto di adesso come album normali" (congela, preselezionato) o "Togli la regola" | Piano 8.1: "rimuove tutti i dati dei volti dell'utente, da NAS e da tutti i suoi dispositivi"; la chiave per prima chiude anche i resti che un file cancellato lascia sulla flash |
| D30 | **Dispositivi spenti e ritorni** (E-F6, §2.8): un dispositivo **pubblica nei dati personali una voce `faces` diversa dalla sua generazione solo dopo aver cancellato i dati di quella generazione** (gancio `PrePublishHook` di `NasPersonalSync`, chiamato prima di ogni pubblicazione): la sua testa personale con `off\|G` (o con una generazione più nuova) è la **prova** che ha cancellato. Nessun dato dei volti si pubblica senza uno stato **fresco** (un giro completo dei dati personali in questo processo da meno di 15 minuti, orologio monotono) che dica "attiva, generazione G"; le generazioni non correnti non si leggono mai. La pagina mostra dispositivo per dispositivo chi ha cancellato e chi no (con l'ultimo contatto); i dispositivi revocati o persi non si aspettano (L89) | Un dispositivo spento non può essere raggiunto: lo si rende innocuo al ritorno (prima cancella, poi pubblica) e lo si dice; la conferma viaggia con un meccanismo che c'è già |
| D31 | **Cancellazioni guidate da dati remoti: un'eccezione dichiarata** (formato §9, nuovo punto 6-bis): **solo dati dei volti** (mai media, mai fuori da `faces/`), solo nell'area privata dell'account, solo per (a) una **generazione** che la voce `faces` firmata dell'account dice non corrente, (b) la cartella di un dispositivo **dell'account** revocato o perso nel registro accettato. Le esegue solo `core-nasops` (`FacesNasEraser`, percorsi derivati dalla regola, mai letti da un dato), mai `core-faces` | Senza, i dati di un dispositivo che non torna resterebbero sul NAS per sempre; il danno peggiore di un dato falso è un ricalcolo dei volti (L93), mai una perdita di foto |
| D32 | **Minori e terzi**: l'app **non stima l'età** né altro (D19), quindi non sa chi è un bambino; tutti i volti hanno le stesse regole e gli stessi strumenti (Nascondi, Dimentica, Ignora); cosa compare in Persone lo decide **Q2** (raccomandata: solo le persone con un nome; gli altri gruppi in "Altri volti", a un tocco); la pagina di attivazione dice che si raggruppano anche amici e bambini e che i dati restano dell'utente | La famiglia è di soli adulti, ma nelle foto ci sono nipoti e amici; stimare l'età per trattarli a parte sarebbe un'altra inferenza su dati delicati |
| D33 | **GDPR in pratica**: uso esclusivamente domestico (art. 2 par. 2 lett. c, considerando 18: il regolamento non si applica), ma le misure sono quelle che servirebbero: attivazione esplicita per account con testo versionato (D3), dati solo dell'utente (area privata e suoi dispositivi), **nessuna condivisione** con la famiglia (Q3), cancellazione completa e verificata (D29), minimizzazione (nessun ritaglio salvato, nessun attributo, screenshot e documenti esclusi, D18, D19, D25), nessuna esportazione né telemetria (D37); prima di una pubblicazione dell'app serve una valutazione legale (L14, L92) | Gli embedding usati per raggruppare sono probabilmente dati biometrici (art. 4.14, art. 9); i terzi nelle foto non hanno scelto niente: il minimo è non portare i loro dati fuori dall'area dell'utente |
| D34 | **Recinzione**: `FeatureFlags.facesSyncTestSharesOnly`, vera **anche in release** finché l'utente non dice di sì (Q5): sulle share vere **niente sul NAS** (nessun file sotto `faces/`, nessuna prenotazione), calcolo e raggruppamento **locali** sul dispositivo, nomi e correzioni per dispositivo, "Disattiva e cancella" locale; la pagina lo dice ("finché la sincronizzazione delle persone non è accesa, ogni dispositivo ha le sue"); in debug con gli account di prova vale `ShareFence` (F4-D33) | Come l'indice (F4-D31) e gli album condivisi (F7-D30): la prima scrittura di dati biometrici della famiglia sulle share vere viene dopo le prove e con un sì. Sulle share vere oggi anche i dati personali sono solo locali (F4-D31) |
| D35 | **Cancelli**: scritture e cancellazioni sul NAS dietro `IndexWriteRules` (congelamento, emergenza, salto dell'ora, fiducia, sola lettura) più D34; con il congelamento la cancellazione sul NAS **aspetta** (quella locale no) e la pagina lo dice | Nessuna scrittura nuova sfugge ai cancelli esistenti (come F7-D38) |
| D36 | **Ora**: voci `people`, teste e prenotazioni con l'**ora del NAS** (F4-D32: un salto oltre 5 minuti sospende le pubblicazioni); la scelta di attivare o cancellare senza ora del NAS vale subito e prende l'ora alla prima sincronizzazione; durate, freschezza (D30) e attese con l'orologio monotono; nessuna data del telefono | Come le fasi precedenti; l'ultima scelta dell'utente vince anche se fatta fuori casa |
| D37 | **Log e Diagnostica**: mai nomi, id di persone o di volti, chiavi di contenuto, riquadri, vettori, conteggi per persona, generazione; solo numeri e codici (`FACES_*`, `PEOPLE_*`, §2.10); sezione `DiagSection.PEOPLE`; esportazione con l'elenco chiuso delle chiavi; un test semina nomi e vettori riconoscibili e li cerca in log, Diagnostica ed esportazione (F107); `toString()` sicuri | C21 e checklist della Fase 8: "nessun dato esportato" |
| D38 | **UI come stati e contratti** (come F7-D32): `feature-people` e le aggiunte a viewer, ricerca, album e impostazioni consegnano ViewModel e stati sigillati (§1.5), composable fatte solo con i componenti G2 | Il filone grafico rifinisce senza toccare la logica |
| D39 | **Permessi, componenti, dipendenze, host**: **nessun** permesso Android, componente o servizio nuovo; **nessuna dipendenza nuova** (LiteRT c'è); due asset nuovi con le loro licenze (MIT, Apache-2.0) e gli avvisi; **nessun host nuovo** con Q1 (a); `tools/check.ps1` controlla che i due modelli siano nell'APK con le impronte del catalogo e che nessuna classe nomini LiteRT fuori da `core-ai` | Checklist "per ogni fase": ogni libreria, host o voce del manifest è una decisione visibile |
| D40 | **Per le Fasi 9 e 10**: ricordi con le persone (solo con nome, non nascoste, mai su un dispositivo escluso), "mostra meno" per persona (impostazione personale), notifiche dei ricordi senza nomi sulla schermata di blocco, widget senza persone nascoste; i contratti `People` e `PeopleLookup` sono pronti | Piano 9.1 ("persone, se attive"; "mostra meno per una persona") |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)

**Attivazione e dispositivi**
- **E-F7**: Persone attivata su un dispositivo mentre un altro dello stesso account è spento o fuori casa: lo sa al primo giro dei dati
  personali; finché l'utente non vede l'avviso lì, quel dispositivo non scarica, non calcola e non mostra niente (D4).
- **E-F8**: "Non su questo dispositivo" su un dispositivo che ha già i dati: cancellati subito (chiave per prima); il NAS e gli altri
  dispositivi non cambiano; riattivare qui riscarica dal NAS senza ricalcolare.
- **E-F9**: attivazione su due dispositivi dello stesso account quasi insieme (due generazioni): vince la scelta più recente per ora del
  NAS; la generazione che perde si cancella dal NAS e dai dispositivi come una generazione non corrente (D31).
- **E-F10**: nessun indicizzatore nell'account, o area privata uguale all'area comune: Persone non parte; la pagina dice perché e propone
  il ruolo su questo dispositivo (D5).
- **E-F11**: un dispositivo con un'app senza la Fase 8 nello stesso account: non legge `faces/`, conserva la voce `faces` senza capirla,
  non calcola volti; il suo `PERSON` resta riservato (E-I3).
- **E-F12**: voce `faces` con una forma sconosciuta (app più nuova su un altro dispositivo): niente calcolo, niente pubblicazione, nessuna
  cancellazione, "Aggiorna l'app" (D3).

**Calcolo e qualità**
- **E-F13**: volto piccolo, sfocato o di profilo: rilevato e tenuto, ma non fa nascere persone; si unisce a una persona solo con una
  somiglianza più alta; sotto 24 px nessun vettore, solo "1 volto non riconoscibile" nelle info (D19).
- **E-F14**: foto di gruppo con volti piccoli nell'anteprima: seconda passata sull'HQ a tasselli; i volti trovati due volte (bordo dei
  tasselli) si fondono (D17).
- **E-F15**: la stessa persona due volte nella stessa foto (specchio, foto nella foto, collage): una sola delle due entra nella persona,
  l'altra resta fra i non assegnati.
- **E-F16**: screenshot, registrazioni dello schermo, documenti e ritagli: non analizzati; un documento riconosciuto dopo (bit
  `DOCUMENT`) perde i suoi volti alla manutenzione (D18).
- **E-F17**: video: volti sulla copertina e su al più 3 fotogrammi; il video entra nella persona; i volti dei fotogrammi non fanno nascere
  persone da soli (D18).
- **E-F18**: anteprima assente ovunque (share vere con F4-D31, buffer svuotato): un indicizzatore Preparatore la rifà dall'originale del
  NAS (attività pesante, Wi-Fi di casa); altrimenti il media aspetta e la pagina lo conta (D17).
- **E-F19**: chiave del Keystore dei volti sparita o inutilizzabile (ripristino, aggiornamento del sistema): i dati locali sono
  illeggibili, si cancellano e si riscaricano dal NAS (o si ricalcolano sotto la recinzione); nessun crollo.

**Raggruppamento e gestione**
- **E-F20**: due persone diverse finite insieme: "Dividi" (scegli i volti) crea una persona nuova "separata"; non si riuniscono mai da
  sole (D23).
- **E-F21**: un volto messo a mano in una persona ("È Marco") resta lì anche dopo i ricalcoli; uno tolto ("Non è Marco") non ci torna,
  ma può entrare in un'altra persona (D23).
- **E-F22**: nome dato alla stessa persona su due dispositivi quasi insieme: vince l'ultima modifica per ora del NAS, uguale ovunque.
- **E-F23**: "Unisci" di due persone con un nome: si sceglie quale nome tenere; album smart, ricerche recenti e ricordi che puntavano
  all'altra seguono la persona unita (alias, D22).
- **E-F24**: modello, spazio o ricetta nuovi: ricalcolo graduale (dal più recente); id, nomi e correzioni si ereditano per volti in comune;
  una persona con nome che non si ritrova resta con "0 foto · ricontrolla"; vettori di spazi diversi mai confrontati (come E-I4).
- **E-F25**: il custode (S25) spento oltre un'ora mentre il tablet calcola: il tablet prende la prenotazione e riparte dall'ultima
  istantanea; id e nomi non cambiano (D21).
- **E-F26**: due custodi pubblicano insieme (prenotazione contesa): vince l'istantanea con il numero più alto; l'altro si riallinea senza
  perdere nomi né correzioni (D21).
- **E-F27**: "Dimentica" una persona: il gruppo e il nome spariscono, i suoi volti restano ignorati; con foto nuove la persona può
  riformarsi come gruppo senza nome (nessuna impronta per riconoscerla, D24).
- **E-F28**: persona nascosta: sparisce da Persone, suggerimenti e dizionario della ricerca; gli album smart che la usano continuano a
  funzionare; si ritrova in Impostazioni → Persone → "Persone nascoste".
- **E-F29**: foto modificata (nuova versione, nuova chiave di contenuto): i volti della versione nuova si calcolano; le correzioni passano
  alla nuova se il volto si ritrova nello stesso posto.

**Cancellazione**
- **E-F30**: "Disattiva e cancella" fuori casa (senza NAS): locale subito; sul NAS appena raggiungibile (il diario riprende); gli altri
  dispositivi cancellano quando ricevono la scelta (D29, D30).
- **E-F31**: cancellazione interrotta (app chiusa, telefono spento, NAS che cade, congelamento): riprende dal diario fino alla verifica;
  ogni passo è idempotente; con il congelamento la parte sul NAS aspetta.
- **E-F32**: dispositivo spento durante la cancellazione che torna dopo giorni: al primo giro completo dei dati personali cancella i suoi
  dati e la sua cartella, non pubblica niente della generazione cancellata, e la sua testa personale lo conferma (D30).
- **E-F33**: riattivazione dopo una cancellazione: generazione nuova, calcolo da capo; nessun dato della generazione vecchia torna,
  nemmeno da un dispositivo rimasto spento (D3, D30).
- **E-F34**: cestino di rete acceso nell'area privata del NAS: i file cancellati vi restano; se la cartella del cestino è visibile l'app lo
  dice ("il NAS ne tiene una copia nel suo cestino: svuotalo dal pannello"), altrimenti è un limite dichiarato (L90). Oggi le aree
  private hanno il cestino spento (`docs/inventario-nas.md` §1).
- **E-F35**: dispositivo perso con i dati dei volti: l'azzeramento alla riconnessione distrugge per prima la chiave dei volti; se non si
  ricollega, i dati restano cifrati sul dispositivo, dietro il blocco schermo (L89).

**Ricerca, album smart, nascosti**
- **E-F36**: query "Marco" con una persona Marco e un luogo "San Marco" (o una persona "Natale"): chip della persona con l'alternativa,
  come E-Q10; la scelta si tocca nel chip.
- **E-F37**: album smart con una persona su un dispositivo escluso o senza volti: l'album dice "Le persone non sono attive su questo
  dispositivo" e non inserisce niente da solo (D27).
- **E-F38**: un media nascosto con volti: calcolato, mai mostrato (copertine, conteggi, pagine, info, "Altri volti", ricerca); una persona
  con soli nascosti non compare (D28).
- **E-F39**: media eliminato o sparito: i suoi volti escono dai conteggi delle persone; tornano se il media torna (stessa chiave).

**Privacy e formato**
- **E-F40**: foto dell'area comune con i volti di un familiare: ognuno che attiva Persone ha i suoi gruppi e i suoi nomi; gli altri
  familiari non vedono niente, nemmeno che esistono (Q3).
- **E-F41**: testa o segmento dei volti malformati, firmati da un dispositivo di un altro account o revocato, fuori posto, o in una
  generazione non corrente: ignorati e contati; gli altri valgono.
- **E-F42**: file `faces/` trovati nell'area comune (per errore o falsi): mai letti, mai scritti.
- **E-F43**: esportazione della Diagnostica e log: nessun nome, id, chiave, riquadro, vettore o conteggio per persona (D37).

### 0.3 Modifiche da fare in P0 (agli altri documenti)

**`docs/formato-nas.md`** (versione 4 del formato **solo** per i file `faces/`)
- **A1, §2.2 albero**: `faces/<generazione>/<id dispositivo>/head/<versione>` [9], `faces/<generazione>/<id dispositivo>/seg/<impronta>`
  (contenitori `MCGB` versione 4, classi 18–20), `faces/<generazione>/leases/<impronta risorsa>/<gruppo>/<numero della prenotazione>` [4]; **solo
  nell'area privata** dell'account, mai nell'area comune; `<generazione>` in base32 (26 caratteri).
- **A2, §5.11 nuovo "Testa dei volti (tipo 9, Fase 8)"**: contenuto della testa, regole di chi legge (firmatario **dello stesso account**,
  generazione corrente dalla voce `faces`, posto, anti-rollback), pubblicazione, custode e istantanee (D12, D21, §2.4).
- **A3, §5.5-bis**: classi `faces` (18, corpo binario del §2.4), `cluster` (19), `people` (20); `RESERVED_WIRE` resta `faces` per tutte le
  altre teste (una testa `index`, `personal`, `ai` o d'album che elenca una classe dei volti è rifiutata).
- **A4, §5.5-bis dati personali**: campo `faces` di `setting` (D3); `person` resta **riservato** e rifiutato nelle teste `personal` (D13).
- **A5, §5.6 prenotazioni**: la radice delle prenotazioni dei volti è `faces/<G>/leases/`; risorse `faces/<G>/work/<area>/<mese>/<parte>` e
  `faces/<G>/cluster`, mai nomi di share, cartelle o file.
- **A6, §7 tetti**: corpo `faces` ≤ 20 KiB e ≤ 64 volti; voce `cluster` ≤ 8 KiB, ≤ 10.000 persone e ≤ 200.000 assegnazioni per
  istantanea; voce `people` ≤ 4 KiB, nome da 1 a 64 caratteri; ≤ 100.000 voci per testa; vettori finiti con norma 1 ± 0,01.
- **A7, §9 nuovo punto 6-bis**: le sole cancellazioni guidate da un dato remoto oltre l'auto-azzeramento: **dati dei volti** di una
  generazione non corrente o di un dispositivo dell'account revocato o perso, solo sotto `faces/` dell'area privata (D31); "Disattiva e
  cancella" è un'azione locale confermata.
- **A8, §10**: i file `faces/` hanno la versione 4; chi legge accetta 1..4 con la versione legata al tipo; un'app più vecchia non apre
  `faces/` (E-F11); §4.6: i dati dei volti di un dispositivo revocato o perso si ignorano e si tolgono (D31); §2.3: la pulizia dei propri
  file conosce `faces/<G>/<io>/` (ultime 3 teste, 7 giorni di grazia, come M6).

**`docs/sicurezza.md`**: §1 (una riga sui volti per la famiglia: da attivare, restano tuoi, si cancellano davvero); §2 (bene "Volti e
persone": dove sta adesso); S1 e S11 (scenari: dispositivo perso con i volti, familiare compromesso che cancella o falsa i gruppi, terzi
nelle foto); **C20 riscritta** (D14: cifratura sul dispositivo con distruzione della chiave; sul NAS no, con il motivo); C21 (nessuna
esportazione dei dati dei volti, D37); C27 (superfici delle persone); **C33–C37** nuove (§6.1); §4-bis (asset dei modelli, nessun permesso
né componente); §6 **L88–L97** (§6.2); §7 checklist della Fase 8 riscritta (§6.3); §8 righe per D3, D6–D8, D11–D14, D29–D34.

**`docs/modello-dati.md`**: nuovo §15 "Database dei volti, v1" (lo scrive P6); §9 "volti" chiuso con il rimando; nota allo §5 sugli stati
di lavoro (`FACES` resta fuori dalle fasi iniziali: lo stato dei volti sta nel database dei volti, non in `work_state`).

**`docs/decisioni.md`**: sezione "Fase 8" con D1–D40 e le risposte Q1–Q5 del §9.

**`docs/fase4-progetto.md`**: nota a E-F5 (§2.9): i nomi delle persone **non** sono voci dei dati personali ma della classe `people` sotto
`faces/` (D13); nota a D26 (`PERSON` resta riservato).

**`docs/fase6-progetto.md`**: §2.9, righe della Fase 8 (testa `ai`, catalogo dei modelli, scaffale) con il rimando a questo documento; nota a
D10 (il buffer tiene anche ciò che serve ai volti, D17) e a D41 (stesso modello di consenso, `FacesState`).

**`docs/fase7-progetto.md`**: §2.9, la riga "Regole degli album smart → Fase 8" con il rimando a D27; nota a D33 (sezione Persone: testo e
fornitore, D25).

**`docs/famiglia-dispositivi.md`**: §3, fase (3) dell'azzeramento: anche le chiavi `mcg-faces-*` (D14); fase (5): il database dei volti.

**`docs/regole-attivita.md`**: §2 (il calcolo dei volti dentro `AI_COMPUTE`, la ri-preparazione di `analysis` per i volti come attività
pesante, D17); §6 costanti nuove (`facesPerSecond`, `facesHqShare`, `faceFramesPerVideo`, `facesGroupingNightly`).

**`docs/grafica-progetto.md`**, "Dubbi grafici" §12.14: sezione Persone e "Altri volti", pagina persona, revisione dei volti, foglio di
unione e di divisione, riga "Persone" nelle info con "Chi è?", pagina di attivazione e avviso, foglio "Disattiva e cancella" con lo stato
per dispositivo, chip "Persona" in Cerca, regola "Persone" nell'editor degli album smart.

**`docs/research/ocr-volti.md`**: nota in fondo: conversione a LiteRT (D6), scelte di YuNet e SFace (D7), piano B (D9), ritratti di
pubblico dominio per le prove (§5.2).

**`README.md`**: moduli nuovi e grafo.

**Piano** (lo riporta l'orchestratore): edge case del §0.2; 8.1 "Una volta attiva…" precisata con la generazione, l'avviso e
l'esclusione per dispositivo (D3, D4); proposta 13 precisata (nomi e correzioni nella cartella dei volti, D13); glossario "Dati personali"
(i volti stanno nella cartella dei volti dell'area privata, non nei dati personali); §7 "Limiti" con L88–L97; punto aperto "Modello dei volti
e licenza" chiuso con D6–D9 dopo P1.

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)

```
core-model       + PersonId, FaceGeneration, FaceSpaceCode (codici stabili)                                       (P0)
core-trust       + data/FacesDocs.kt: testa tipo 9, classi faces (18), cluster (19), people (20), versione 4, codec
                   ostile; NasLayout.faces*(G, dispositivo, ...), prenotazioni sotto faces/<G>/leases/                (P0 firme, P3)
core-ai          + contract/Faces.kt; faces/: YuNet, allineamento, SFace, qualita' su LiteRT (CPU)                    (P0 firme, P2)
                 + compute/: porta BufferKeepers chiesta da pruneFinished                                            (P4)
core-models      + catalogo FACE_DETECT e FACE_EMBED (asset dell'APK), needed(ruoli, funzioni)                       (P1)
core-faces       → common, model, trust (FacesDocs, NasLayout; NasFileSystem SOLO in sync/), index (contratti:
                   cancello, prenotazioni, esiti), personal (contratti), ai e models (solo contratti), database
                   (solo il database dei volti), library (VisibleMedia, HiddenMask), security (solo FacesKeys)       (P0)
                   switch/    attivazione, consenso, avviso, esclusione, freschezza                                  (P7)
                   compute/   unita', ingressi, passate, salvataggio                                                (P4)
                   grouping/  assegnazione, nascite, manutenzione, custode, istantanee, ricalcolo                    (P5)
                   people/    nomi, correzioni, viste, copertine, proposte                                           (P9)
                   sync/      pubblicazione e replica dei dati dei volti (l'UNICO pacchetto che vede NasFileSystem)  (P3)
                   erase/     "Disattiva e cancella": diario, ordine dei passi, verifica, conferme dei dispositivi   (P8)
                   store/     archivio cifrato sopra il database dei volti                                          (P6)
                   NON vede: nasops, backup, nas, nasmedia, search, albums (implementazioni), rete
feature-people   → faces (contratti di stato e comandi), library (viste), imaging, designsystem                     (P7, P9)
                   cieca a core-nas, core-nasops, core-trust, core-ai, core-search, core-albums
core-database    + faces/: FacesDatabase (file faces-<G>.db, schema proprio v1)                                     (P6)
core-security    + FacesKeyStore: una chiave AES del Keystore per generazione (sopra SecretKeyStore)                 (P6)
core-personal    + campo `faces` di SETTING (lettura e scrittura), PrePublishHook in NasPersonalSync                 (P0 firme, P7, P8)
core-nasops      + faces/: FacesNasEraser (cancellazione stretta sotto faces/), pulizia dei propri file dei volti     (P8)
core-search      + SearchChip.Person, PeopleDictionary, porta PeopleLookup, regole dell'analizzatore                 (P0 firme, P10)
core-albums      + SmartRules.people (PeopleRule), porta PeopleMembership, regola nel motore smart                  (P0 firme, P10)
feature-viewer   + riga "Persone" nelle info e azioni sui volti, come dati (porta ViewerPeople)                      (P0 firme, P9)
feature-search   + chip "Persona" con l'alternativa                                                                  (P10)
feature-albums   + regola "Persone" nell'editor                                                                      (P10)
feature-settings + voce "Persone" (apre la pagina di feature-people)                                                (P7)
feature-library  + testo della sezione Persone nei campioni                                                          (P9)
core-common      + FeatureFlags.facesSyncTestSharesOnly, DiagSection.PEOPLE, Phase8ArchitectureTest                 (P0)
app → tutti                                                                                                           (P13)
```
- `core-faces` non vede `core-nasops`, `core-search` né `core-albums`: la cancellazione sul NAS passa dalla porta `FacesEraseOps`, la
  ricerca da `PeopleLookup` (contratto di `core-search`) e gli album da `PeopleMembership` (contratto di `core-albums`), tutte legate da
  `:app` sopra il contratto `PeopleIndex` di `core-faces` (test d'architettura: nessuna classe di `core-faces` nomina `core.nasops`,
  `core.search`, `core.albums`, `java.net`).
- **Solo `core-faces/.../sync/`** vede `NasFileSystem` e scrive solo sotto `faces/<G>/<proprio dispositivo>/` dell'area privata dichiarata
  (test di proprietà sul `NasFileSystem` finto: nessun percorso fuori, nessuna scrittura nell'area comune, nessuna senza
  `publishExclusive`).
- **Solo `core-ai/.../faces/FaceLiteRtSessions.kt`** nomina `org.tensorflow.lite` fra le classi dei volti (come `LiteRtSessions.kt` per il
  ritaglio); `feature-people` e `core-faces` non vedono LiteRT.
- `FacesActivationConsent` la costruisce solo la pagina di attivazione e `FacesEraseConfirmation` solo il foglio "Disattiva e cancella",
  entrambi in `feature-people` (test d'architettura, come `TextReadingConsent`).
- `PersonalKind.PERSON` non compare mai in una testa `personal` scritta da questa app (test sul pubblicatore dei dati personali).
- Restano le regole delle Fasi 4–7: solo `core-nasops` vede `NasFileOps`; i `feature-*` non si conoscono fra loro; `core-search` e
  `core-albums` non vedono `core-faces`.

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-model` (P0)**: `PersonId` (128 bit, forma UUID in app, base32 nei file, `PersonId.random()`), `FaceGeneration` (128 bit,
base32 a 26 caratteri, `FaceGeneration.random()`), `FaceSpaceCode` (`SFACE_2021DEC_V1` = 1: 128 dimensioni, float16 salvato), `FaceTier`
(`CORE`, `WEAK`, `NONE`), codici stabili e `toString()` senza valori.

**`core-trust` (P3): file dei volti**
| Elemento | Firma | Note |
|---|---|---|
| Testa | `data class FacesHead(val family: FamilyId, val account: String, val generation: FaceGeneration, val device: DeviceId, val version: Long, val registryVersion: Long, val createdAt: Long, val snapshot: SnapshotMark?, val segments: List<ClassedSegment>)`; `data class SnapshotMark(val seq: Long, val base: Long)`; busta `DocType.FACES_HEAD(9)`, versione 4 (`TrustFormat.FACES_VERSION`) | `NasLayout.facesGenerationDir(G)`, `facesHead(G, device, version)`, `facesSegment(G, device, hash)`, `facesLeaseFile(G, resource, number)` |
| Classi | `SegmentClass.FACES("faces", 18)`, `CLUSTER("cluster", 19)`, `PEOPLE("people", 20)`; `FACES_CLASSES`; `INDEX_CLASSES`, `AI_CLASSES` e le classi d'album invariate | il nome `faces` resta rifiutato in ogni altra testa |
| Corpi | `class FacesBody(val space: FaceSpaceCode, val detector: Int, val recipe: Int, val source: FaceSource, val faces: List<FaceRecord>)`; `class FaceRecord(val frame: Int, val box: ShortBox, val landmarks: ShortArray, val score: Int, val tier: FaceTier, val reasons: Int, val sidePx: Int, val yaw: Int, val sharpness: Int, val vector: ShortArray?)`; `sealed interface ClusterEntry { Person(id, state, aliasOf, bornSeq, changedSeq, count, centroid, exemplars: List<FaceRef>); Assignment(face: FaceRef, person: PersonId, score: Int, origin: AssignOrigin) }`; `data class PeopleEntry(val key: PeopleKey, val at: Long, val author: DeviceId, val value: PeopleValue?)` | §2.4; `value = null` è la lapide |
| Codec | `object FacesDocs { fun encodeHead(...): ByteArray; fun decodeHead(bytes, expected): Verdict<FacesHead>; fun encodeSegment(cls, entries): EncodedSegment; fun decodeSegment(bytes, ref, cls): Verdict<FacesSegmentContent> }` | tetti di A6 (§0.3); un'eccezione è un bug: tutto ciò che è ostile diventa un `Verdict` |
| Volto | `data class FaceLocator(val contentKey: ContentKey, val frame: Int, val box: ShortBox)` (correzioni, D23); `data class FaceRef(val contentKey: ContentKey, val frame: Int, val index: Int, val bodyDigest: Long)` (istantanee) | `FaceLocator.matches(face)` = stesso contenuto e fotogramma, IoU ≥ 0,5 |

**`core-ai` (P2): motori dei volti** (pacchetto `faces/`)
| Elemento | Firma | Note |
|---|---|---|
| Rilevatore | `interface FaceDetector { suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace>; suspend fun release() }`; `data class DetectedFace(val box: FloatBox, val landmarks: FloatArray, val score: Float)`; `enum class DetectPass { PREVIEW, HQ_TILES }` | YuNet a 1024×1024 con bordi; tasselli da 1024 con 128 px di sovrapposizione e fusione per NMS sull'HQ |
| Embedder | `interface FaceEmbedder { val space: FaceSpaceCode; suspend fun embed(faces: List<AlignedFace>): List<FloatArray>; suspend fun release() }` | SFace, 112×112, vettori normalizzati; un volto alla volta se uno fa lanciare il motore |
| Allineamento | `object FaceAligner { fun align(image: FaceImage, landmarks: FloatArray): AlignedFace }` | similitudine sui 5 punti verso il modello a 112 px di ArcFace; JVM pura |
| Qualità | `object FaceQuality { fun assess(face: DetectedFace, sidePx: Int, aligned: AlignedFace): QualityVerdict }`; `QualityVerdict(tier, reasons, yaw, sharpness)` | D19; nessun attributo della persona |
| Ingressi | `ModelInputs.forFaces(bytes: ByteArray, maxSide: Int): FaceImage?`; `FaceImage` si costruisce solo lì (e dai byte del telefono, dal `Preparer`) | intestazione letta prima di decodificare, `ImageBounds`, `ImageDecoder` (F6-D5) |
| Buffer | `fun interface BufferKeepers { suspend fun stillWanted(keys: Collection<ContentKey>): Set<ContentKey> }`, parametro di `AiComputeParts` (null-object: nessuna) | D17: `pruneFinished` non toglie le anteprime ancora chieste |

**`core-models` (P1)**: `ModelRole.FACE_DETECT`, `ModelRole.FACE_EMBED`; `ModelCatalog.FACE_YUNET`, `ModelCatalog.FACE_SFACE` (asset
`faces/…tflite`, dimensione e SHA-256 dal file del repository, licenze `MIT` e `Apache-2.0` con la nota sui dataset); `enum class
ModelFeature { FACES }`; `fun needed(roles: Set<DeviceRole>, features: Set<ModelFeature>): List<ModelSpec>` (i volti solo con `FACES`
attiva qui; la firma vecchia resta e vale `features = ∅`).

**`core-faces` (P0 contratti)**
| Elemento | Firma | Note |
|---|---|---|
| Stato | `sealed interface FacesState { data object Off; data class On(val generation: FaceGeneration, val here: HereState); data class AwaitingNotice(val generation: FaceGeneration, val textVersion: Int); data class Erasing(val generation: FaceGeneration); data object NeedsUpdate }`; `enum class HereState { ACTIVE, EXCLUDED, LOCAL_ONLY }` | `LOCAL_ONLY` = recinzione (D34) |
| Interruttore | `interface FacesSwitch { val state: StateFlow<FacesState>; fun currentState(): FacesState; val fresh: StateFlow<Boolean>; val syncPending: StateFlow<Boolean>; suspend fun activate(consent: FacesActivationConsent): ActivateOutcome; suspend fun acceptNotice(textVersion: Int); suspend fun excludeThisDevice(); suspend fun includeThisDevice(); suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome; companion object { const val CURRENT_TEXT_VERSION = 1 } }`; `class FacesActivationConsent(val textVersion: Int, val shownAtMonotonicNanos: Long)` | D3–D5; `ActivateOutcome { Activated(generation); NoPrivateArea; NoIndexer(canBecomeIndexer) }`; `reconcile` cancella i dati locali di una generazione non più corrente e risponde `Done` o `Failed` (è il gancio di D30) |
| Calcolo | `interface FacesComputeRun { suspend fun run(scope: FacesScope, budget: Duration): FacesRunResult }`; `sealed interface FacesScope { data class Area(val share: String, val kind: AreaKind); data object PhoneOnly }` | §2.3; un'area di un altro account non è mai un ambito |
| Raggruppamento | `interface FaceGrouping { val holder: StateFlow<HolderState>; suspend fun incremental(budget: Duration): GroupingReport; suspend fun nightly(budget: Duration): GroupingReport; suspend fun recluster(budget: Duration): GroupingReport }`; `object GroupingParams { T_JOIN = 0.45f; T_JOIN_WEAK = 0.55f; MARGIN = 0.06f; T_EDGE = 0.45f; T_BIRTH = 0.48f; T_MERGE = 0.52f; T_SUGGEST = 0.40f; T_EJECT = 0.32f; MIN_FACES = 3; MIN_PHOTOS = 2; MAX_EXEMPLARS = 16 }` | §2.5; valori di partenza, li fissa P11 |
| Persone | `interface People { val list: StateFlow<PeopleList>; fun person(id: PersonId): Flow<PersonView?>; fun others(): Flow<OthersView>; suspend fun facesOf(media: MediaId): List<FaceChip>; suspend fun rename(id: PersonId, name: String?): EditOutcome; suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome; suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome; suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome; suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome; suspend fun ignore(face: FaceLocator): EditOutcome; suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome; suspend fun forget(id: PersonId): EditOutcome; suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome; suspend fun proposals(): List<MergeProposal>; suspend fun answer(proposal: MergeProposal, same: Boolean) }` | D24, D25; esiti, mai eccezioni per i casi previsti; `PersonTarget { Existing(id); New(name) }` |
| Indice | `interface PeopleIndex { suspend fun dictionary(): List<PersonName>; suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel>; fun resolve(id: PersonId): PersonId? }`; `enum class MatchLevel { SURE, LIKELY }` | la ricerca e gli album lo vedono dalle loro porte; `resolve` segue gli alias, null = dimenticata o sconosciuta |
| Sincronizzazione | `interface FacesSync { suspend fun sync(budget: Duration): FacesSyncReport }`; `interface FacesPublisher { suspend fun stage(change: FacesChange); suspend fun flush(reason: FlushReason): PublishOutcome; val pending: StateFlow<Int> }` | §2.4; dietro `FacesWriteGate` (D34, D35) |
| Cancellazione | `interface FacesEraser { val status: StateFlow<EraseStatus>; suspend fun preview(): ErasePreview; suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart; suspend fun resume(budget: Duration): EraseReport }`; `class FacesEraseConfirmation(val previewDigest: Long, val shownAtMonotonicNanos: Long)`; `data class EraseStatus(val local: StepState, val nas: StepState, val devices: List<DeviceAck>)` | §2.8; `PeopleAlbumsChoice { KEEP_PHOTOS, DROP_RULE }`; la conferma scade dopo 10 minuti (come le altre) |
| Porte | `FaceInputs` (anteprima e HQ: buffer, indice, telefono, ri-preparazione), `FacesCatalog` (media dell'account per area e mese, chiavi di contenuto delle celle e dei membri, sorgente, bit `DOCUMENT`, visibilità), `FacesRemote` (elenco, lettura, `publishExclusive` sotto la propria cartella), `FacesLeases` (prenotazioni sotto la generazione), `FacesEraseOps` (→ `core-nasops`), `SmartAlbumsPeople` (congela o togli le regole: → `core-albums`), `SearchHistoryPurge` (→ `core-search`), `FacesRunControl` (regole di `AI_COMPUTE`), `FacesClock` (monotono e ora del NAS) | legate da `:app` |
| Stato del lavoro | `sealed interface FacesStatus { Off; Waiting(reason: FacesWait); Running(done: Int, total: Int, phase: FacesPhase); Idle(atNas: Long); Problem(code: String) }` | numeri e codici |

**`core-personal` (P0 firme, P7, P8)**: `PersonalFields.SETTING_FACES = "faces"`; `object FacesSettingCodec { fun parse(value:
PersonalValue?): FacesSetting?; fun on(generation, textVersion): PersonalValue; fun off(generation): PersonalValue }`; `fun interface
PrePublishHook { suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish }` con `PrePublish { Proceed; Hold(code) }`,
parametro di `NasPersonalSync` (null-object: `Proceed`); `PersonalHeadsView.latestValueOf(key): Map<DeviceId, PersonalEntry?>` (la voce che
ogni dispositivo dell'account ha nella sua ultima testa letta: la conferma di D30).

**`core-security` (P6)**: `interface FacesKeys { suspend fun create(generation: FaceGeneration): Boolean; suspend fun open(generation:
FaceGeneration): FacesDataKey?; suspend fun destroy(generation: FaceGeneration): Boolean; suspend fun destroyAll(): Boolean }`; alias
`mcg-faces-<G>` nel Keystore (StrongBox se c'è), chiave dati AES-256 avvolta, mai fuori dal processo; `FacesDataKey.close()` azzera.

**`core-nasops` (P8)**: `interface FacesNasEraser { suspend fun listGenerations(share: String): List<FaceGeneration>; suspend fun
eraseGeneration(share: String, generation: FaceGeneration, reason: FacesEraseReason, budget: Duration): EraseRoundReport; suspend fun
eraseDevice(share: String, generation: FaceGeneration, device: DeviceId, reason: FacesEraseReason, budget: Duration): EraseRoundReport }`;
`enum class FacesEraseReason { USER_CONFIRMED, NOT_CURRENT, DEVICE_REVOKED }`; i percorsi li costruisce `NasLayout`, mai chi chiama.

**`core-search` (P0 firme, P10)**: `SearchChip.Person(val option: PersonOption, val alternatives: List<ChipAlternative>, val word: String)`;
`data class PersonOption(val ids: List<PersonId>, val mode: PeopleMode, val label: String)`; `class PeopleDictionary(val entries:
List<PersonEntry>)`; `QueryParser.parse(text, today, places, people: PeopleDictionary = PeopleDictionary.EMPTY)`; `interface PeopleLookup {
val available: StateFlow<Boolean>; suspend fun dictionary(): PeopleDictionary; suspend fun cells(option: PersonOption): Set<MediaId> }`.

**`core-albums` (P0 firme, P10)**: `SmartRules.people: PeopleRule? = null`; `data class PeopleRule(val ids: List<PersonId>, val mode:
PeopleMode)`; `interface PeopleMembership { val available: StateFlow<Boolean>; suspend fun classify(rule: PeopleRule, candidates:
Collection<MediaId>): Map<MediaId, PeopleClass> }` con `PeopleClass { SURE, LIKELY }`; `SmartRulesText` con `smart:people`.

**`feature-viewer` (P0 firme, P9)**: `interface ViewerPeople { fun faces(media: ViewerMediaId): Flow<List<ViewerFaceChip>>; suspend fun
act(media: ViewerMediaId, face: ViewerFaceRef, action: ViewerFaceAction) }` in `ViewerServices` (null di serie: niente riga); `feature-viewer`
non vede `core-faces`.

### 1.3 Aggiunte a moduli esistenti

| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | `PersonId`, `FaceGeneration`, `FaceSpaceCode`, `FaceTier` | P0 |
| `core-trust` | `DocType.FACES_HEAD(9)`, `TrustFormat.FACES_VERSION = 4` con la versione legata al tipo, `SegmentClass` 18–20, `FacesDocs`, `NasLayout` dei volti e radice delle prenotazioni per generazione (`LeaseManager` prende la radice), `FacesOwnFiles` (politica pura della pulizia dei propri file dei volti) | P0 (firme), P3 |
| `core-ai` | `contract/Faces.kt`, `faces/` (YuNet, allineamento, SFace, qualità, `FaceLiteRtSessions`), `ModelInputs.forFaces`, `BufferKeepers` in `AiComputeParts` e in `ComputeSession.pruneFinished` | P0 (firme), P2, P4 |
| `core-models` | ruoli e voci del catalogo, `ModelFeature`, `needed(roles, features)`; estrazione e rimozione legate alla funzione | P1 |
| `core-database` | `faces/FacesDatabase` (file a parte, schema v1, DAO dei volti, delle persone, delle assegnazioni, delle voci `people`, dei segni, dell'outbox e delle unità) | P6 |
| `core-security` | `FacesKeys` e `FacesKeyStore` | P6 |
| `core-personal` | campo `faces`, `FacesSettingCodec`, `PrePublishHook` in `NasPersonalSync`, `PersonalHeadsView.latestValueOf`; test: `PERSON` mai pubblicato | P0 (firme), P7, P8 |
| `core-nasops` | `faces/FacesNasEraser` e la pulizia dei propri file dei volti (stessa esecuzione di `OwnFilesCleaner`) | P8 |
| `core-search` | chip `Person`, `PeopleDictionary`, `PeopleLookup`, regole dell'analizzatore, candidati, suggerimenti, cronologia | P0 (firme), P10 |
| `core-albums` | `PeopleRule`, `PeopleMembership`, `smart:people` in `SmartRulesText`, regola nel motore smart (P6 della Fase 7) | P0 (firme), P10 |
| `core-library` | nessuna API nuova: le persone usano `VisibleMedia`, `HiddenMask`, `OrderedTimelineView` | — |
| `core-common` | `FeatureFlags.facesSyncTestSharesOnly` (accesa anche in release, regola R8), `DiagSection.PEOPLE` (con la voce di stato `PEOPLE_STATE`), `Phase8ArchitectureTest` | P0 |
| `feature-viewer` | riga "Persone" nelle info (`InfoBuilder`), "Chi è?" e azioni sui volti come dati, porta `ViewerPeople` | P0 (firme), P9 |
| `feature-search` | chip "Persona" con l'alternativa, suggerimenti delle persone | P10 |
| `feature-albums` | regola "Persone" nell'editor (scelta di una o più persone, "una qualunque" o "tutte") | P10 |
| `feature-settings` | voce "Persone" in Impostazioni (apre la pagina di `feature-people`, come "Ricerca e modelli") | P7 |
| `feature-library` | `CollectionsSectionSamples.people()` con il testo giusto; nessun'altra modifica (la sezione è un fornitore) | P9 |
| `feature-diagnostics` | sezione Persone, codici del §2.10, lavori `FACES_COMPUTE`, `FACES_ERASE` | P13 |
| `tools/` | `tools/models/faces/` (conversione e parità, P1), `tools/fixtures/faces/` (ritratti di pubblico dominio e composizioni, P11), `tools/chaos/faces-pc/` e `s25.ps1 -Mode faces` (P12), controlli in `check.ps1` (P0, P13) | P0, P1, P11, P12, P13 |

### 1.4 Cosa resta in `:app` (P13)

- **`FacesGraph`** in `app/.../faces/`: nasce con la famiglia `Ready`. Interruttore, archivio, persone, calcolo e raggruppamento vivono
  **anche senza NAS** (sotto la recinzione e fuori casa); sincronizzazione, custode e cancellazione sul NAS con il `NasGraph`. Si apre
  solo se lo stato è `On` con `ACTIVE` o `LOCAL_ONLY` (prima nessuna classe dei motori dei volti si carica).
- **Porte**: `FaceInputs` sopra `DiskAiBuffer`, `IndexAnalysisFetcher` (anche `hq`) e `AiPhone`/`Preparer` (anche la ri-preparazione di
  D17); `FacesCatalog` sopra `MediaCatalog` (chiavi di contenuto delle celle, aree, mesi, sorgente, bit `DOCUMENT`); `VisibleMedia` e
  `HiddenMask` da `GalleryGraph`; `FacesRemote` e `FacesLeases` sopra la share privata del `NasGraph`; `FacesEraseOps` → `FacesNasEraser`;
  `PeopleLookup` e `PeopleMembership` sopra `PeopleIndex` e le chiavi delle celle; `SmartAlbumsPeople` → `CatalogAlbums`;
  `SearchHistoryPurge` → `SearchHistory`; `BufferKeepers` → il calcolo dei volti; `ViewerPeople` → `People`; `PrePublishHook` →
  `FacesSwitch.reconcile` (`Failed` = `Hold`).
- **Lavori** (`work/FacesWork.kt`, con `runIfActive` e `StagedRun`): `FacesComputeWorker` (`AI_COMPUTE`, tappe da 8 minuti, dopo
  `AiComputeWorker` della stessa notte e periodico; "Analizza ora" come `UserInitiated`; nomi dei lavori distinti come in IX-1 della
  revisione B); la sincronizzazione dei volti nello stesso `IndexSyncWorker`, **dopo** i dati personali (così la generazione è fresca);
  il raggruppamento notturno dentro `LibraryMaintenanceWorker`; `FacesEraseWorker` (riprende il diario della cancellazione; accelerato
  dopo la conferma).
- **Navigazione**: sezione Persone delle Raccolte (fornitore `people`), pagina persona, "Altri volti", revisione dei volti, pagina di
  attivazione e avviso, Impostazioni → Persone, chip "Persona" in Cerca, regola "Persone" nell'editor degli album smart.
- **Cancelli**: `FacesWriteGate` = `IndexWriteRules` + `facesSyncTestSharesOnly` + `ShareFence` in debug.
- **Modelli**: `ModelCatalog.needed(roles, features)` con `FACES` solo se lo stato è attivo qui; estrazione degli asset alla prima apertura
  (come MagicTouch, `EnsuringModelFiles`), rimozione con l'esclusione e con la cancellazione.
- **Azzeramento**: `FacesGraph.stop()` per primo (motori rilasciati, calcolo fermo); `AppLocalDataEraser` toglie i database
  `faces-*.db` (anche con `allDatabases = false`), la memoria locale (`faces-local`, diario della cancellazione); `DeviceSelfWipe` distrugge
  le chiavi `mcg-faces-*` nella fase (3) con le credenziali. Il NAS non si tocca (i dati dell'account restano per gli altri suoi
  dispositivi; si tolgono con "Disattiva e cancella").
- **Memoria**: `onTrimMemory` rilascia rilevatore ed embedder; `ApplicationExitInfo` in Diagnostica come per il calcolo AI (E-A16).

### 1.5 Confine con il filone grafico: stati e contratti

La Fase 8 decide **cosa** si mostra e **quando**; il filone grafico decide **come** (D38). Composable fatte solo con i componenti G2.

| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `PeopleSectionState` | `Off` (riga "Attiva riconoscimento dei volti"), `AwaitingNotice`, `Excluded`, `FirstRun(percent)`, `Ready(top: List<PersonCard>, total, othersCount)`, `LocalOnly` (con la nota della recinzione), `Unavailable(reason)` | Raccolte → Persone | G2 |
| `PeopleListState` | `Ready(named, unnamed, hiddenCount)` (con Q2 (b) `unnamed` vuoto e la riga "Altri volti"), `Empty`, `Working(percent)` | "Tutte le persone" | G2 |
| `PersonPageState` | `Ready(header: name?, count, cover, view: OrderedTimelineView, actions, proposals)`, `Forgotten`, `Hidden`, `NotFound` | pagina persona | G2 |
| `FaceReviewState` | `Grid(faces, selection)`, `Applying`, `Done` | "Controlla le foto", "Dividi", "Altri volti" | G2 |
| `MergeSheetState` | `Choose(candidates)`, `PickName(a, b)`, `Confirm` | unione e proposte "È la stessa persona?" | G2 |
| `ViewerFaceChip` | `Named(name, crop)`, `Unnamed(crop)`, `Unrecognizable(count)` con le azioni "Chi è?", "Non è …", "Ignora questo volto" | info del viewer | G3 |
| `FacesActivationState` | `Explain(textVersion, requirements)`, `NeedsIndexer`, `NoPrivateArea`, `Activating`, `Activated(syncPending)` | pagina di attivazione | G2 |
| `FacesNoticeState` | `Show(textVersion, by: DeviceLabel?)`, `Hidden` | avviso una tantum sugli altri dispositivi | G2 |
| `PeopleSettingsState` | stato, chi calcola (dispositivi), avanzamento, "Non su questo dispositivo", persone nascoste, modelli e licenze, "Disattiva e cancella", stato della cancellazione | Impostazioni → Persone | G2 |
| `EraseSheetState` | `Preview(namedPeople, albumsWithPeople, devices, choice)`, `Confirming`, `Started`, `Progress(local, nas, devices)`, `Done`, `WaitingDevices(list)` | foglio e pagina "Disattiva e cancella" | G2 |
| `PersonChipState` | chip "Persona" (`Person(label, alternatives)`) in Cerca; suggerimenti "Persone" a riposo | Cerca | G2 |
| `PeopleRuleEditorState` | persone scelte, "una qualunque" o "tutte", `Unavailable` (spenta o esclusa) | editor degli album smart | G2 |

**Chi tocca cosa**
- La Fase 8 **non** modifica `core-designsystem`; il filone grafico non modifica contratti e ViewModel qui sopra.
- File condivisi a rischio: `feature-viewer/.../info/InfoBuilder.kt` (solo P9), `ViewerServices` (P0 la firma), `core-ai/.../compute/
  ComputeSession.kt` (solo P4, una porta), `core-personal/.../sync/NasPersonalSync.kt` (solo P8, il gancio), `core-trust/.../data/Leases.kt`
  e `nas/NasLayout.kt` (solo P3), `core-search/.../parse/ItalianQueryParser.kt` (solo P10), il motore smart di `core-albums` (P10, dopo P6
  della Fase 7), `app/.../graph/LocalData.kt`, `MainScaffold.kt` e `AppNavHost.kt` (solo P13), `MediaCatalog.kt` (nessuno: le query
  nuove stanno in `FacesCatalog` sopra le letture che ci sono; se ne serve una, la scrive P13 e lo dice).
- Stringhe in file propri: `strings_people_*.xml`, `strings_faces_*.xml`.

## 2. Flussi

### 2.1 Attivazione, avviso, esclusione (8.1; P7)
| Momento | Cosa | Dettagli |
|---|---|---|
| Spenta | la sezione Persone delle Raccolte ha la sola riga "Attiva riconoscimento dei volti" (il campione di oggi dice "solo su questo telefono": sbagliato, P9 lo corregge) | anche Impostazioni → Persone porta alla stessa pagina |
| Requisiti (D5) | area privata dichiarata diversa dalla comune; almeno un indicizzatore dell'account (stati firmati, 7 giorni) | senza area privata: "Serve un'area privata sul NAS" e nient'altro; senza indicizzatori: "Nessun tuo dispositivo analizza le foto" con "Fallo su questo dispositivo" (accende il ruolo con la stima di F6-D15) |
| Pagina di spiegazione | il testo sotto, in frasi brevi, con una **versione** (`CURRENT_TEXT_VERSION`) | "Attiva" e "Non ora"; l'unico punto che costruisce `FacesActivationConsent` |
| "Attiva" | generazione nuova `G` (128 bit casuali); voce `faces = 1\|on\|G\|v`; stato locale `On(G, ACTIVE)` **subito**, anche senza l'ora del NAS (la voce resta "da datare" e prende l'ora alla prima sincronizzazione, `syncPending`) | con la recinzione sulle share vere: `On(G, LOCAL_ONLY)` (D34) |
| Modelli | asset estratti in `models/<sha256>` e verificati (dimensione e SHA-256) alla prima apertura | con Q1 (b): "Scarica (20 MB)" come i modelli della ricerca |
| Primo calcolo | stima "Analizzare le tue 12.000 foto richiede circa mezz'ora, di notte in carica" (da `facesPerSecond` misurato, D10) e "Analizza ora" | §2.3 |
| Altri dispositivi dell'account | al primo giro dei dati personali vedono `on\|G`: stato `AwaitingNotice(G, v)`; **niente scaricato, calcolato o mostrato** finché l'utente non apre l'app e sceglie | avviso: "Hai attivato Persone da un altro tuo dispositivo. Qui vedrai le persone e i loro nomi." con "Va bene" e "Non su questo dispositivo" |
| Esclusione (D4) | "Non su questo dispositivo" (anche dalle Impostazioni, anche prima dell'avviso): file locale `faces-local` | se c'erano dati: chiave del Keystore distrutta, database cancellato, modelli estratti tolti; la sezione dice "Persone non è attiva su questo dispositivo" con "Attiva qui" |
| Riattivare qui | "Attiva qui" su un dispositivo escluso | nessuna generazione nuova: si scarica dal NAS ciò che gli altri hanno (sotto la recinzione si ricalcola) |
| Testo cambiato | `v` più nuovo nell'app | tutti i dispositivi tornano ad `AwaitingNotice` finché l'utente non rilegge; il calcolo si ferma ma i dati restano |
| Voce sconosciuta (E-F12) | forma che questa versione non capisce | `NeedsUpdate`: niente calcolo, niente pubblicazione, **nessuna cancellazione**; "Aggiorna l'app" |
| Due attivazioni quasi insieme (E-F9) | due `on` con generazioni diverse | vince la voce più recente per ora del NAS; l'altra generazione è "non corrente": si cancella (D31) |

**La pagina di attivazione** (D3), in frasi brevi:
- "Persone raggruppa le tue foto per volto: dai un nome a chi conosci e lo ritrovi con un tocco, anche nella ricerca e negli album."
- "Il lavoro lo fa un tuo dispositivo che analizza le foto, di notte, in carica. **Nessuna foto e nessun volto escono da casa.**"
- "**I gruppi e i nomi sono solo tuoi**: stanno nella tua area privata del NAS e sui tuoi dispositivi. Nessun familiare li vede, nemmeno per
  le foto dell'area comune."
- "Nelle foto ci sono anche amici e bambini: anche i loro volti vengono raggruppati. L'app **non stima** età, sesso o altro. Puoi nascondere
  o far dimenticare chiunque."
- "**«Disattiva e cancella»** toglie tutto, dal NAS e da tutti i tuoi dispositivi; un dispositivo spento lo fa appena si riaccende."
- "Su ogni tuo dispositivo puoi dire «Non su questo dispositivo»."
- "Modelli usati: YuNet (licenza MIT) e SFace (licenza Apache 2.0), dentro l'app." (link a Impostazioni → Informazioni e licenze, con la
  nota sui dataset di addestramento, L92)
- Con la recinzione: "Per ora le persone restano su questo dispositivo: la sincronizzazione con gli altri tuoi dispositivi arriva dopo le
  prove."

| Edge case | Comportamento |
|---|---|
| E-F7 | Avviso alla prima apertura; prima niente |
| E-F8 | Cancellazione locale immediata, NAS e altri dispositivi invariati |
| E-F10 | La pagina lo dice; nessuna voce scritta |
| E-F11 | Un dispositivo con un'app vecchia conserva la voce `faces` senza capirla e non calcola |
| E-F12 | `NeedsUpdate` |

### 2.2 Modelli (D6–D10; P1, P2)
**Le sorgenti** (verificate nella ricerca 0.4 del 12/10 sulle impronte Git LFS di OpenCV Zoo; **niente è stato scaricato**: lo scaricamento
è nell'elenco da approvare, §8)
| Modello | File e fonte | Dimensione | SHA-256 | Licenza | Uso |
|---|---|---|---|---|---|
| YuNet 2023mar | `face_detection_yunet_2023mar.onnx`, `github.com/opencv/opencv_zoo`, `models/face_detection_yunet/` (Git LFS) | 232.589 B | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | MIT | candidato |
| YuNet 2026may | `face_detection_yunet_2026may.onnx`, stessa cartella (ingresso dinamico) | 229.738 B | `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0` | MIT | candidato |
| SFace 2021dec fp32 | `face_recognition_sface_2021dec.onnx`, `models/face_recognition_sface/` | 38,70 MB | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` | Apache-2.0 | da convertire |
| SFace 2021dec int8 | `face_recognition_sface_2021dec_int8.onnx` [D: nome da confermare], stessa cartella | 9,90 MB | `2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a` | Apache-2.0 | solo riferimento della parità |

**La conversione** (P1, sul PC, `tools/models/faces/`, nessun dato personale)
1. Ambiente Python isolato con versioni fissate e impronte (`requirements.txt` con `--require-hashes`): `onnx2tf` (MIT), `tensorflow` CPU
   (Apache-2.0), `onnx`, `onnxruntime` (MIT, solo per la parità), `numpy`; versioni scelte da P1 e scritte nel report.
2. YuNet: forma d'ingresso fissata a `1×3×1024×1024` (per la 2026may, dinamica, si fissa all'esportazione), conversione con le uscite
   grezze (le tre scale 8, 16, 32: punteggi, oggettività, riquadri, cinque punti); **la decodifica e la soppressione dei doppi (NMS) le fa
   il nostro codice** in `core-ai`, non il modello.
3. SFace: `1×3×112×112` → `.tflite` con pesi float16 (e una variante int8 dinamica per la regola di D7).
4. **Parità** (sul PC, prima di consegnare): sui ritratti di prova (§5.2) e su 300 volti delle composizioni, riquadri con IoU ≥ 0,95 e
   cinque punti entro 1 px dall'ONNX (con la nostra decodifica applicata a entrambi), vettori con coseno ≥ 0,999 (float16) e ≥ 0,995 (int8);
   preprocessing da verificare sui sorgenti di OpenCV (`FaceDetectorYN`: ingresso BGR 0–255; `FaceRecognizerSF`: ritaglio allineato,
   112×112, RGB 0–255, nessuna media sottratta) [D: da confermare in P1 con la parità, non con la lettura].
5. **Scelta scritta prima di misurare** (D7): YuNet `2026may` solo se trova almeno il 5% in più dei volti fra 24 e 48 px sui ritratti di
   prova con non più falsi positivi e non oltre +30% di latenza, altrimenti `2023mar`; SFace int8 solo con le due condizioni di D7.
6. I `.tflite` scelti vanno in `core-ai/src/main/assets/faces/` con `LICENSE-YuNet` (MIT), `LICENSE-SFace` (Apache-2.0) e `NOTICE` (fonte,
   commit di OpenCV Zoo, impronte di ingresso, strumenti usati, nota sui dataset); dimensioni e SHA-256 **del file nel repository** nel
   catalogo (`ModelCatalog.FACE_YUNET`, `FACE_SFACE`) e in `CatalogMatchesDesignTest`. Dimensioni attese [stima]: YuNet 0,3–0,6 MB, SFace
   ~19,4 MB (float16) o ~10 MB (int8).

**Nel telefono** (P2)
| Momento | Cosa | Dettagli |
|---|---|---|
| Estrazione | alla prima apertura con Persone attiva qui (`EnsuringModelFiles`, nessuna rete) | `models/<sha256>` in sola lettura; mai su un dispositivo spento, escluso o in attesa dell'avviso |
| Caricamento | `ModelFiles.open` → dimensione e SHA-256 **ogni volta** → `ByteBuffer` mappato sul descrittore verificato → `Interpreter` (CPU, 4 thread) | un file diverso si toglie e si riestrae (E-A11); `FACES_MODEL_MISMATCH` |
| YuNet | anteprima con bordi a 1024×1024; uscite → riquadri e cinque punti; soglia di rilevamento 0,6, NMS 0,3; poi `FaceQuality` | tasselli sull'HQ per la seconda passata (D17) |
| Allineamento | similitudine (minimi quadrati, Umeyama) dai cinque punti al modello a 112 px di ArcFace (38,29 51,70 · 73,53 51,50 · 56,03 71,74 · 41,55 92,37 · 70,73 92,20) | JVM pura, provata contro la parità di P1 |
| SFace | ritaglio allineato → vettore a 128 → normalizzato in float32 → salvato in float16 | vettore non finito o norma lontana da 1 = `FACES_VECTOR_INVALID`, volto senza vettore |
| Rilascio | a fine tappa, a `onTrimMemory` e allo stop | come il motore delle immagini (IX-5) |
| Rimozione | esclusione, "Disattiva e cancella", app che non elenca più il modello | asset sempre nell'APK; la copia estratta si toglie |

**Qualità attesa e misure che decidono** (D9): numeri pubblicati solo su LFW (SFace 99,40% fp32, 99,32% int8) e su WIDER FACE "hard" per
YuNet (AP 0,708 o 0,750). Su profili, età e occhiali da sole nessun numero: le misure sono quelle di F105 (≥ 97% dei gruppi senza persone
sbagliate) e del set sintetico di P11. **Piano B** (§7): AuraFace-v1 (`glintr100.onnx`, Apache-2.0, 261 MB, da Hugging Face), dlib ResNet
(CC0), MobileFaceNet (Apache-2.0); stessa conversione, stessa parità, stessa misura; la scelta torna all'utente solo se cambia il peso o
l'host (Q1).

### 2.3 Calcolo dei volti (8.1; P4)
**La tappa di un'unità** (`FacesComputeWorker`, regole di `AI_COMPUTE`; prima l'area privata, poi l'area comune, poi i media del solo
telefono; dentro, dal mese più recente)
| Passo | Cosa | Ripresa e guasti |
|---|---|---|
| Cancello | stato `On(G, ACTIVE o LOCAL_ONLY)` su **questo** dispositivo, ruolo Indicizzatore, modelli verificati, generazione uguale a quella del database locale | altrimenti niente; una generazione diversa ferma la tappa e fa riconciliare (§2.8) |
| Unità | `FacesUnits.next(area)`: unità `faces/<G>/work/<priv\|comm>/<mese>/<parte>` (200 media per chiave di contenuto, gli stessi confini su ogni dispositivo), già fatti tolti (voce `faces` della ricetta corrente di **un** dispositivo dell'account, o "non supportato"); prenotazione di 1 ora sotto `faces/<G>/leases/` dell'area privata, rinnovo ogni 20 minuti | `Busy`/`Lost` = unità dopo; cartella delle prenotazioni anomala = senza prenotazione (danno solo d'efficienza); con la recinzione nessuna prenotazione |
| Filtro | fuori screenshot e registrazioni dello schermo, documenti (`DOCUMENT`), ritagli (D18); i nascosti restano (calcolati, mai mostrati) | un media diventato documento dopo: i suoi volti si tolgono alla manutenzione |
| Ingressi | buffer → `FaceInputs.fetch` dall'indice della share (blob `analysis` attestati, a lotti di 50) → telefono (`Preparer`, solo `analysis`) → ri-preparazione dall'originale del NAS se il dispositivo è anche Preparatore (D17) → altrimenti "in attesa" | un blob non attestato non si usa; la sorgente cambiata (dimensione o `qh1` diversi da quelli del catalogo) = media saltato (come D8 della P11b) |
| Prima passata | `ModelInputs.forFaces` → YuNet sull'anteprima (1024) | errore del motore = media saltato e contato; due errori di fila = tappa ferma con `FACES_ENGINE_FAILED` |
| Seconda passata (D17) | se un volto ha il lato corto sotto 40 px o ci sono ≥ 5 volti: HQ (dal buffer, dalla replica con la qualità "Alta", o scaricato) in quattro tasselli con sovrapposizione + fusione dei doppi | HQ assente e non scaricabile = si tengono i volti della prima passata, segnati `WEAK` se piccoli |
| Qualità e vettori | `FaceQuality` (D19) → per CORE e WEAK: allineamento dalla sorgente più nitida già in mano (originale del telefono, HQ, anteprima) → SFace | volti NONE senza vettore |
| Video | copertina e fino a 3 fotogrammi del blob `frames`, distribuiti sulla durata, volti al più WEAK | un video senza fotogrammi preparati aspetta come per i vettori |
| Salvataggio | **subito**, per chiave di contenuto, nel database dei volti cifrato (D14, D15), poi l'outbox della pubblicazione (solo per chiavi di media del NAS dell'account, M3 dei volti) | dopo una morte del processo un media già salvato non si rifà |
| Raggruppamento | a fine unità, `FaceGrouping.incremental` se questo dispositivo è il custode (D21); altrimenti assegnazione provvisoria locale | §2.5 |
| Pubblicazione | `flush(UNIT_FINISHED)` a fine unità, al più una testa ogni 5 minuti d'ora del NAS; **solo con lo stato fresco** (D30) | con la recinzione nessuna; blocco temporaneo = outbox in attesa |
| Fine unità | `finish(Completed)`; il buffer libera le anteprime che non servono più a nessuno (vettori, testo e volti) | prenotazione persa = si finisce il media in corso e si cambia unità |

**"Una volta per account", scenario per scenario**
| Scenario | Chi calcola | Chi riceve |
|---|---|---|
| Foto nuova dal backup nell'area privata | un indicizzatore dell'account con Persone attiva; se è il telefono che ha caricato, l'anteprima è già nel suo buffer | i dispositivi dell'account non esclusi |
| Foto dell'area comune | un indicizzatore **di ogni account** che ha attivato Persone, per sé | solo i dispositivi di quell'account |
| Due indicizzatori dello stesso account | le prenotazioni (sotto la generazione) dividono le unità; il custode è uno solo (D21) | — |
| Stesso contenuto in area privata e comune | una volta (chiave di contenuto) | come sopra |
| Media solo sul telefono di un indicizzatore | lui, dai byte del telefono; risultati **solo locali** finché il media non arriva sul NAS (poi si pubblicano senza ricalcolo) | nessuno |
| Media solo sul telefono di un non indicizzatore | nessuno finché non va sul NAS | la pagina lo conta |
| Share vere con la recinzione (D34) | ogni indicizzatore per sé, tutto locale | nessuno |
| Account senza indicizzatori | nessuno; la pagina lo dice (E-F10) | — |

**Ingressi per tipo di media**
| Tipo | Prima passata | Seconda passata | Vettori |
|---|---|---|---|
| Foto, foto in movimento, RAW, panorami | `analysis` (1024) | `hq` (2048) a tasselli se servono | CORE e WEAK |
| Video | copertina + 3 fotogrammi di `frames` (1024) | — | solo WEAK |
| Screenshot, registrazioni dello schermo, documenti, ritagli | — | — | — |
| Media del solo telefono | anteprima preparata dai byte del telefono | originale del telefono, ridotto a 2048 | come sopra |
| Formato non decodificabile | — | — | "non supportato", contato |

| Edge case | Comportamento |
|---|---|
| E-A5 (caldo) | ritmo ridotto (lotti da 5 con pausa di 2 s) da `LIGHT`, stop da `SEVERE`, come il calcolo AI |
| E-A6 (notte senza NAS) | solo ciò che è nel buffer e sul telefono; poi "Fatto ciò che c'era in locale" |
| E-A16, E-A17 | come il calcolo AI: ripresa dal media dopo; buffer svuotato e calcolo fermo sotto 1 GiB libero |
| E-F13, E-F14, E-F16, E-F17, E-F18 | §0.2 |
| E-F19 | chiave sparita: database illeggibile → cancellato → riscaricato dal NAS o ricalcolato |
| E-H1 | i nascosti si calcolano; nessuna superficie li mostra (§2.9) |

### 2.4 Formato dei dati dei volti sul NAS (P3)
**Sul NAS** (solo nell'area privata dell'account; mai nell'area comune)
```
<area privata>/.mycloudgallery/faces/<generazione>/<id dispositivo>/head/<versione>     [9] testa dei volti, versione 4
<area privata>/.mycloudgallery/faces/<generazione>/<id dispositivo>/seg/<impronta>      MCGB versione 4, classi 18, 19, 20
<area privata>/.mycloudgallery/faces/<generazione>/leases/<impronta risorsa>/<gruppo>/<numero della prenotazione>   [4]
```
**La testa** (`FacesDocs.encodeHead`/`decodeHead`): `{account, createdAt, device, family, generation, registryVersion, segments, snapshot?,
version}`; `account` = `nasUser` del firmatario, in minuscolo; `generation` in base32; `segments` da 0 a 8.192 `{class, hash, size}` con
`class` ∈ {`faces`, `cluster`, `people`}, in ordine di impronta, ognuno al più 12 MiB; `snapshot` = `{seq, base}` **se e solo se** la testa
elenca segmenti `cluster`. Tetto 1 MiB.

**Regole di chi legge**, in quest'ordine: busta (tipo 9; versione oltre la 4 = `NEWER_FORMAT`, una testa dei volti a una versione diversa
dalla 4 = `INVALID_FIELD`) → firmatario nel registro accettato: approvato, oppure dismesso con la testa fra le sue `frozenHeads`; **il suo
`nasUser` è l'account di questo dispositivo** (altrimenti `NOT_OWNER`); revocato o perso = ignorato e la sua cartella si toglie (D31) →
campi (famiglia, `device` = firmatario, `account`, tetti) → posto (cartella della generazione e del dispositivo, numero del nome uguali ai
campi: `WRONG_LOCATION`) → **generazione = quella corrente** dello stato fresco (altrimenti la testa non si legge nemmeno) → anti-rollback
ed equivocazione per (generazione, dispositivo) (`HeadMark`) → segmenti con le loro regole. Ogni errore scarta la testa o il segmento e
non ferma gli altri dispositivi (E-F41). Per giro: al più 64 MiB di segmenti dei volti (il resto al giro dopo), al più 32 cartelle di
dispositivi per generazione (i nomi che non sono id si ignorano), teste lette per nome come al §5.5 del formato (`MAX_JUNK_RUN`).

**Corpo `faces`** (classe 18; chiave = chiave di contenuto; big endian come il resto del formato)
```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           spazio (1 = sface-2021dec-v1: 128 dimensioni, float16; altri rifiutati)
2       1           rilevatore (1 = YuNet 2023mar, 2 = YuNet 2026may)
3       1           ricetta (1..255)
4       1           sorgente (1 analisi, 2 HQ a tasselli, 3 fotogrammi, 4 byte originali del telefono)
5       1           N volti (0..64; 0 = "analizzato, nessun volto")
6       N x V       per volto:
                    1   fotogramma (0 per le foto; 0..8, l'indice nel blob `frames`)
                    8   riquadro x0 y0 x1 y1 (4 x 2 byte, in 1/65535 dell'immagine raddrizzata, x0 < x1, y0 < y1)
                    20  cinque punti (occhio destro, sinistro, naso, bocca a destra, a sinistra) x (x, y), in 1/65535
                    1   punteggio del rilevatore (0..255 = 0..1)
                    1   livello (2 bit: 0 NONE, 1 WEAK, 2 CORE) e motivi (bit: piccolo, profilo, sfocato, fotogramma)
                    2   lato corto del riquadro in pixel della sorgente dell'allineamento (1..65535)
                    1   imbardata stimata (gradi, con segno, -90..90)
                    1   nitidezza (0..255)
                    0 o 256  vettore: 128 x 2 byte float16, presente solo con livello WEAK o CORE
```
Lunghezza esatta; ogni componente finita; norma ricalcolata in float32 fra 0,99 e 1,01; corpo al più 18.630 byte (6 + 64 volti da 291 byte, sotto i 20 KiB di A6).
Chi pubblica scrive una voce **solo** per chiavi di contenuto di media del NAS di questo account (area privata o comune) che il suo catalogo
conosce con timbro e `qh1` freschi (M3 dei volti); chi legge **usa** una voce solo se il suo catalogo ha un media visibile con quella chiave
(come l'attestazione degli album, F7-D9): niente si mostra o si conta per contenuti che il lettore non vede già.

**Voci `cluster`** (classe 19, solo dal custode; corpi binari)
| Voce | Chiave | Corpo |
|---|---|---|
| Persona | SHA-256(`"mcg-face-person\0"` ‖ 16 byte dell'id) | versione (1), stato (1 attiva, 2 unita in un'altra, 3 dimenticata), id (16), alias (16, zeri se nessuno), `bornSeq` (8), `changedSeq` (8), volti (4), centroide (128 x 2 float16), N esemplari (1, ≤ 16) x riferimento a un volto (chiave di contenuto 32, fotogramma 1, indice 1, prime 8 byte dell'impronta del corpo `faces` usato) |
| Assegnazione | SHA-256(`"mcg-face-assign\0"` ‖ riferimento del volto) | versione (1), riferimento del volto (42), persona (16), punteggio (2: coseno x 10.000), origine (1: automatica, dell'utente, ignorata) |

Un'istantanea è **tutte** le voci `cluster` elencate da una testa con `snapshot`; si sceglie quella con `seq` più alto fra i dispositivi
dell'account (a pari, il dispositivo minore); `base` = la `seq` da cui è partita (serve al riallineamento, D21). Tetti: 10.000 persone,
200.000 assegnazioni.

**Voci `people`** (classe 20; JSON canonico ≤ 4 KiB, come i dati personali): `{at, author, field, subject, value}` con `value` assente =
lapide; chiave = SHA-256(`"mcg-people\0"` ‖ soggetto ‖ `\0` ‖ campo); `at` = ora del NAS (dal 2020 al 2100, non oltre 2 minuti nel futuro);
`author` = un dispositivo approvato dell'account; ultima scrittura che vince come `PersonalMerge` (ora, poi autore minore, poi valore).
| Campo | Soggetto | Valore |
|---|---|---|
| `name` | persona | testo da 1 a 64 caratteri, regole di `TextRules` (niente controlli, direzione, invisibili), senza spazi ai bordi |
| `hidden` | persona | vero/falso (Nascondi) |
| `forget` | persona | vero (Dimentica) |
| `alias` | persona | id della persona in cui è stata unita |
| `apart:<persona>` | persona | vero: mai unite in automatico ("Separati", da "Dividi") |
| `born` | persona | `split:<id d'origine>` (persona nata da "Dividi") |
| `cover` | persona | localizzatore del volto scelto |
| `is` | localizzatore di un volto | id della persona ("È …") |
| `not:<persona>` | localizzatore di un volto | vero ("Non è …") |
| `ignore` | localizzatore di un volto | vero ("Ignora questo volto") |

Localizzatore: `<chiave di contenuto in base32>:<fotogramma>:<x0>.<y0>.<x1>.<y1>` (riquadro in 1/65535), al più 128 caratteri (D23). Un
campo sconosciuto o un valore d'altra forma rifiuta il segmento. Ogni dispositivo pubblica **lo stato unito** delle voci `people` che
conosce (come F4-D26): un telefono spento non porta via i nomi.

**Fusione dei rilevamenti**: per una chiave di contenuto, fra i corpi `faces` dei dispositivi dell'account vince la ricetta più alta, poi il
dispositivo minore, poi l'impronta del corpo minore; le istantanee si riferiscono a quel vincitore (le prime 8 byte dell'impronta nel
riferimento): se il vincitore cambia, il volto è "nuovo" per il custode e si riassegna.

**Pubblicazione** (`FacesPublisher`): voci divise in segmenti **stabili** per secchi della chiave (come i dati personali); prima i
segmenti (nome = impronta), poi la testa al primo numero libero; **solo `publishExclusive`, solo sotto `faces/<G>/<proprio
dispositivo>/` dell'area privata dichiarata** (mai se la share è l'area comune, L23); al più una testa ogni 5 minuti d'ora del NAS, salvo
la fine di un'unità; dietro `FacesWriteGate`; **solo con lo stato fresco** che dice `On(G)` (D30). Pulizia dei propri file (`FacesOwnFiles`
in `core-trust`, eseguita da `core-nasops`): ultime 3 teste, segmenti elencati da loro, 7 giorni di grazia (M6).

**Compatibilità** (E-F11): versione 4 solo per `faces/`; chi legge accetta 1..4 secondo il tipo; un'app più vecchia non apre `faces/` e
conserva la voce `faces` dei dati personali senza capirla.

### 2.5 Raggruppamento incrementale e stabile (8.1; P5)
**Le grandezze**: somiglianza = coseno fra vettori normalizzati (SFace); per una persona P e un volto f, `s(f, P)` = media dei 3 coseni
più alti fra f e gli **esemplari** di P (al più 16 volti CORE, scelti per essere diversi fra loro: il più vicino al centroide, poi via via il
più lontano dai già scelti, a parità l'id minore); il centroide è la media normalizzata dei volti automatici e confermati di P.

**I passi** (`core-faces/.../grouping/`, sulla JVM, deterministici a parità d'ingresso: ogni spareggio per id)
| Passo | Quando | Cosa | Perché così |
|---|---|---|---|
| Assegnazione | a fine unità, per i volti nuovi in ordine di riferimento | P1 = persona con `s` più alta, P2 la seconda; f entra in P1 se `s(f, P1) ≥ T_join` (0,45; WEAK 0,55) **e** `s(f, P1) − s(f, P2) ≥ M` (0,06) **e** nessun vincolo contrario (`not:P1`, `ignore`, la stessa foto ha già un volto in P1 con `s` più alta, P1 dimenticata); altrimenti fra i **non assegnati** | gli esemplari con il margine (Immich) evitano che un volto ambiguo scelga a caso fra due persone simili (E-F1) |
| Nascite | a fine unità se i non assegnati CORE sono almeno 50 in più dell'ultima volta, e ogni notte | grafo fra i non assegnati CORE con archi `≥ T_edge` (0,45) fra volti di foto diverse; **agglomerazione a legame medio** (si uniscono i due gruppi con la somiglianza media più alta, fermandosi sotto `T_birth` 0,48; mai due volti della stessa foto nello stesso gruppo); un gruppo con **≥ 3 volti di ≥ 2 foto** diventa una persona con un `PersonId` casuale | il legame medio non fa catene: un volto sfocato non unisce due persone (ricerca 0.4, §5.2) |
| Manutenzione notturna | ogni notte (`LibraryMaintenanceWorker`), solo il custode | (a) **fusioni**: per le coppie con centroidi ≥ 0,40, legame medio fra i loro esemplari; ≥ `T_merge` (0,52) → unione automatica (alias, D22) **se** le due persone non sono mai insieme in una foto, non hanno **entrambe** un nome e non sono `apart`; fra `T_suggest` (0,40) e `T_merge` → proposta "È la stessa persona?"; (b) **espulsioni**: un volto **automatico** con `s` verso la sua persona (esclusi i suoi stessi esemplari) sotto `T_eject` (0,32) torna fra i non assegnati; (c) esemplari e centroidi rinnovati; (d) riassegnazione dei non assegnati; (e) i volti di media diventati documenti o spariti da tutto escono | la passata a due velocità di Apple: prima la precisione, poi le unioni prudenti; le unioni fra due nomi le decide solo l'utente |
| Correzioni | subito, in ogni passo | `is` fissa il volto (mai espulso, mai riassegnato); `not:P` lo esclude da P per sempre; `ignore` lo toglie da tutto (né esemplare né nascita); `apart` vieta l'unione; `forget` toglie la persona e ignora i suoi volti; `alias` dell'utente unisce subito | i vincoli si riapplicano a ogni passo e a ogni ricalcolo (D23) |
| Persone mostrate | in lettura | una persona compare se ha un nome o almeno 3 foto **visibili**; i suoi conteggi sono celle visibili (§2.9) | — |

**I parametri** (`GroupingParams`, valori di partenza; P11 li calibra sul set privato e li scrive con i numeri in `docs/decisioni.md`, §5.3)
| Parametro | Partenza | Cosa decide | Come si calibra |
|---|---|---|---|
| `T_join` / `T_join_weak` | 0,45 / 0,55 | entrare in una persona | il valore più basso che tiene la purezza dei gruppi ≥ 98,5% sul set di calibrazione (margine sul 97% del piano) |
| `M` | 0,06 | margine sulla seconda persona | come sopra, con le coppie "persone diverse ma simili" del set |
| `T_edge`, `T_birth` | 0,45, 0,48 | nascere | nessuna persona nata mista sul set di calibrazione |
| `T_merge`, `T_suggest` | 0,52, 0,40 | unire da sole o proporre | nessuna unione sbagliata; proposte giuste almeno 1 su 2 |
| `T_eject` | 0,32 | espellere | tolti gli errori segnati senza togliere più del 2% dei volti giusti |
| `MIN_FACES`, `MIN_PHOTOS` | 3, 2 | soglia di nascita | fissi |

La soglia 0,363 della demo di SFace è di **verifica** (coppia contro coppia, su LFW): per raggruppare servono soglie più severe, e la scelta
è un risultato delle misure, non un dato in ingresso (ricerca 0.4, §5.3).

**Il custode e le istantanee** (D21)
| Momento | Cosa | Dettagli |
|---|---|---|
| Chi | il primo indicizzatore dell'account con Persone attiva che prende la prenotazione `faces/<G>/cluster` (1 ora, rinnovo ogni 20 minuti durante le tappe) | con la recinzione ogni indicizzatore è custode per sé, in locale |
| Partenza | carica l'istantanea con `seq` più alta (sua o di altri) più le voci `people` unite | un custode nuovo continua da dove l'altro si è fermato: stessi id |
| Lavoro | assegnazione, nascite, manutenzione | i nuovi id li genera solo lui (e "Dividi" dell'utente) |
| Pubblicazione | istantanea con `seq` = massimo visto + 1 e `base` = la `seq` da cui è partito; segmenti stabili (si riscrivono solo i secchi cambiati) | al più ogni 5 minuti, a fine unità e a fine manutenzione |
| Gli altri dispositivi | leggono l'istantanea vincente; per i volti che non contiene (calcolati da loro o arrivati dopo) **assegnazione provvisoria locale** con le stesse regole e gli esemplari dell'istantanea, mai nascite | il provvisorio non si pubblica e sparisce quando arriva l'istantanea che li contiene |
| Due custodi (E-F26) | una prenotazione contesa fa pubblicare due istantanee con la stessa `base` | vince la `seq` più alta (a pari, il dispositivo minore); il perdente si **riallinea**: riparte dall'istantanea vincente, riassegna i suoi volti nuovi, e per le persone nate solo da lui cerca la persona vincente con più volti in comune (alias, come un'unione) o, se non c'è, le fa rinascere con lo stesso id; i nomi stanno nelle voci `people` e non si perdono |
| Custode spento (E-F25) | la prenotazione scade dopo un'ora | un altro indicizzatore la prende alla sua tappa |

**Ricalcolo completo** (D22, E-F24): un modello, uno spazio o una ricetta nuovi ricalcolano i volti dal più recente; finché il nuovo non
copre quanto il vecchio, Persone usa il vecchio (vettori di spazi diversi mai confrontati). Poi il custode raggruppa da capo sul nuovo
spazio e **abbina** i gruppi nuovi ai vecchi: volti in comune = stesso contenuto, stesso fotogramma, riquadri con IoU ≥ 0,5; assegnazione
ottima (algoritmo ungherese) sul numero di volti in comune, solo se il gruppo nuovo ne condivide almeno il 30%. Il gruppo nuovo prende
l'id del vecchio; le correzioni (`is`, `not`, `ignore`, `apart`) valgono perché parlano di posti; una persona vecchia senza gruppo resta
(con il suo nome) e si segnala "0 foto · ricontrolla"; i vecchi vettori si tolgono 30 giorni dopo, come F6-D21.

**Alias**: catene (P → Q → R) si risolvono fino in fondo; un ciclo (due unioni contrarie da due dispositivi) ha come radice l'id minore del
ciclo; `PeopleIndex.resolve` lo fa per tutti (ricerca, album, ricordi).

**Prove dell'algoritmo** (P5, §5.1): generatore di identità sintetiche nello spazio a 128 dimensioni (gruppi con dispersione realistica,
coppie "gemelli" vicine, deriva lenta per l'età, volti WEAK rumorosi, foto di gruppo con vincoli); purezza ≥ 99% e frammentazione
dichiarata sul sintetico; **stabilità**: aggiungere volti non cambia mai l'id di una persona esistente (salvo un'unione, che è un alias);
incrementale contro ricalcolo da zero: stessa purezza entro 0,5 punti; correzioni sempre rispettate (proprietà su 10.000 storie
casuali); riallineamento di due custodi senza nomi persi; ricalcolo completo che eredita ≥ 95% degli id sul sintetico.

### 2.6 Persone: nomi, correzioni, pagine (8.2; P9)
**Le azioni** (ognuna scrive voci `people` in una transazione locale, vale **subito** su questo dispositivo anche senza l'ora del NAS, e
viaggia con la pubblicazione dei volti: E-F5)
| Azione | Dove | Voci | Effetto |
|---|---|---|---|
| Dai un nome / Rinomina | pagina persona, "Altri volti", "Chi è?" | `name` (lapide = senza nome) | il nome compare ovunque; entra nel dizionario della ricerca (D26) |
| Unisci | selezione di più persone; proposta "È la stessa persona?" | `alias` (della persona che sparisce) e, se serve, `name` della persona che resta | con due nomi si sceglie quale tenere (E-F23); album, ricerche e ricordi seguono l'alias |
| Dividi | pagina persona → "Controlla le foto" → scegli i volti → "Sposta in una persona nuova" | `born` della persona nuova, `is` per ogni volto scelto, `apart` fra le due | la persona nuova nasce subito (id casuale generato qui); non si riuniscono da sole (E-F20) |
| Non è … | info del viewer, "Controlla le foto" | `not:<persona>` sul volto | il volto esce subito; il custode non ce lo rimette mai (E-F21) |
| È … | "Chi è?" su un volto senza nome o di un'altra persona | `is` (e `name` se la persona è nuova) | il volto è fissato |
| Ignora questo volto | info del viewer, "Controlla le foto" | `ignore` | poster, TV, statue, sconosciuti sullo sfondo (E-F3): fuori da tutto |
| Nascondi persona | pagina persona | `hidden` | sparisce da Persone, suggerimenti e dizionario della ricerca; le foto restano in Libreria; si ritrova in Impostazioni → Persone → "Persone nascoste" (E-F28) |
| Dimentica persona | pagina persona, con conferma ("I suoi volti non verranno più raggruppati; se arrivano foto nuove potrebbe ricomparire senza nome") | `forget`, lapide di `name` | gruppo e nome spariscono; i suoi volti diventano ignorati (E-F27) |
| Copertina | pagina persona → "Scegli copertina" | `cover` | automatica se il volto scelto è nascosto, ignorato o sparito |
| Proposte | pagina persona ("Forse è anche…") e cima di "Tutte le persone" | `alias` con "Sì"; `apart` con "No" | il "No" non ripropone più la stessa coppia |

**Cosa si vede**
- **Raccolte → Persone** (fornitore della sezione `people`, P11 della Fase 7): fino a 8 persone (prima quelle con un nome, poi per numero
  di foto), "Tutte le persone", e con Q2 (b) la riga "Altri volti (N)"; da spenta la riga "Attiva riconoscimento dei volti"; in attesa
  dell'avviso la riga dell'avviso; esclusa "Non attiva su questo dispositivo"; al primo calcolo "Sto riconoscendo i volti: 35%".
- **Tutte le persone**: griglia di copertine rotonde con nome (o "Aggiungi un nome") e numero di foto; selezione per "Unisci"; sul tablet
  due colonne oltre 840 dp come le Raccolte.
- **Pagina persona**: copertina e nome, numero di foto, foto in ordine di tempo (una `OrderedTimelineView` delle celle con un volto della
  persona, membri del gruppo compresi; stesso viewer della Libreria), azioni (Rinomina, Unisci con…, Controlla le foto, Scegli copertina,
  Crea album smart, Nascondi, Dimentica), "Forse è anche…".
- **Altri volti** (Q2): le persone senza nome con almeno 3 foto, dalla più grande; un tocco apre la pagina, "Aggiungi un nome" la porta in
  Persone. Con Q2 (a) non c'è: le persone senza nome stanno in Persone dopo quelle con un nome.
- **Info del viewer**, riga "Persone": un chip per volto riconosciuto (copertina ritagliata e nome, o "Senza nome"), "1 volto non
  riconoscibile" per i volti NONE; tocco = pagina persona; pressione lunga o menu del chip = "Chi è?", "Non è …", "Ignora questo volto".
  Niente riquadri disegnati sulla foto in questa fase. Una persona nascosta non compare nella riga.
- **Copertine e ritagli**: si ritagliano **al momento** dalla migliore immagine già sul dispositivo (originale del telefono con una
  decodifica della sola regione, HQ della replica, anteprima del buffer, miniatura M), con un margine del 30% intorno al riquadro; cache
  **solo in memoria** (LRU da 8 MB), mai su disco (D25); un media nascosto non dà mai una copertina (§2.9).
- **Impostazioni → Persone**: stato, chi calcola (i dispositivi dell'account con il ruolo e Persone attiva), avanzamento ("8.200 foto su
  12.000; 3.400 volti; 41 persone"), "Non su questo dispositivo", "Persone nascoste", "Modelli e licenze", "Disattiva e cancella", lo stato di
  una cancellazione in corso (§2.8).

**Accessibilità**: ogni copertina ha l'etichetta "Marco, 120 foto" o "Persona senza nome, 12 foto"; la selezione dei volti in "Controlla
le foto" ha le azioni di TalkBack "Seleziona" e "Non è …"; bersagli da 48 dp; testo a 1,3x; le copertine rotonde hanno anche il nome sotto
(mai solo l'immagine).

| Edge case | Comportamento |
|---|---|
| E-F1 (gemelli o fratelli simili) | "mai insieme in una foto" e margine (D20); se finiscono insieme, "Dividi" e `apart` |
| E-F2 (bambino e adulto) | due persone; la proposta "È la stessa persona?" quando il legame medio è fra 0,40 e 0,52; altrimenti "Unisci" a mano |
| E-F3 (poster, TV) | "Ignora questo volto" o "Dimentica" la persona che si forma |
| E-F5 (nome su un dispositivo) | voce `name` nella classe `people`, pubblicata e ripubblicata da ogni dispositivo dell'account |
| E-F15, E-F20–E-F23, E-F27–E-F29 | §0.2 |

### 2.7 Ricerca per persona e album smart con persone (8.2; P10)
**Ricerca** (`core-search`, porte legate da `:app`; `core-search` non vede `core-faces`)
| Parte | Comportamento | Dettagli |
|---|---|---|
| Dizionario | `PeopleLookup.dictionary()`: persone con un nome, non nascoste né dimenticate, con almeno un media **visibile**; nomi normalizzati come i luoghi (minuscolo, senza accenti, apostrofi uniformi), anche di più parole | vuoto se Persone è spenta, in attesa dell'avviso o esclusa: il nome resta testo per il contenuto (nessun errore) |
| Analizzatore | dopo le virgolette e prima dei luoghi: la corrispondenza più lunga di un nome; "Marco e Anna" = un chip con le due persone, **tutte**; "Marco o Anna" = **una qualunque**; più nomi senza congiunzione = tutte | `QueryParser.parse(text, today, places, people)`; tabella pubblica di almeno 120 query con nomi inventati (P10) |
| Ambiguità (E-F36) | un nome che è anche una festa ("Natale"), un luogo ("San Marco", "Marina") o un mese: vince l'interpretazione con più media visibili; il chip propone l'altra ("Cerca la festa", "Cerca il luogo", "Cerca come parola"), come E-Q10 | scelta deterministica, provata in tabella |
| Candidati | il chip è un **filtro**: celle con un volto **assegnato** alla persona (automatico o confermato), contando i membri del gruppo della cella; con "Mostra anche risultati meno probabili" anche le celle con un volto nella fascia "forse" (fra `T_suggest` e `T_join`) | `PeopleLookup.cells(option)` sopra `PeopleIndex.contentWith` e le chiavi delle celle (`cellContentKeys`) |
| Con altri chip e contenuto | "Marco al mare 2024" = persona e anno come vincoli, "al mare" al contenuto; "foto di Anna a Firenze" = persona e luogo | la fusione di F6-D27 non cambia |
| Suggerimenti | a riposo una riga "Persone" (fino a 6, le più frequenti con un nome); completamenti con i nomi del dizionario | mai una persona nascosta; mai da media nascosti |
| Cronologia (F6-D32) | le etichette dei chip sono testo: anche i nomi | "Disattiva e cancella" toglie le voci che contengono un nome noto in questa generazione (anche i nomi vecchi, tenuti cifrati nel database dei volti finché c'è) |
| Copertura | sopra i risultati con un chip "Persona": "Sto ancora riconoscendo i volti: 60%" finché il primo calcolo non è finito | come `SearchCoverage` (F6-D31) |

**Album smart** (`core-albums`, motore di P6 della Fase 7; porta `PeopleMembership` legata da `:app`)
| Parte | Comportamento | Dettagli |
|---|---|---|
| Regola | `PeopleRule(ids, mode)`: una o più persone, "una qualunque" o "tutte"; campo personale `smart:people` = `any:<id>,<id>` o `all:…` (F7-D4: un campo nuovo dentro `ALBUM`, nessun tipo nuovo; al più 32 persone, sotto i 1.024 caratteri); riga `album_rule` `people` | nessuna migrazione: `album_rule` ha già `rule` e `value` testuali |
| Classi | **sicuro**: un volto assegnato a ciascuna persona richiesta (con "tutte") o a una (con "una qualunque"); **suggerito**: almeno un volto nella fascia "forse"; con le altre regole vale il "e" (sicuro solo se ogni regola è sicura, suggerito se nessuna è "fuori") | come D14 della Fase 7; esclusioni e accettazioni restano (E-AL5) |
| Creazione | dalla pagina persona "Crea album smart" (titolo = nome, regola = quella persona) o dall'editor ("Aggiungi persone"); il titolo "Marco 2025" passa dall'analizzatore con il dizionario delle persone | anteprima con quanti sicuri e quanti suggeriti |
| Ricalcolo | quando cambia un'assegnazione (istantanea nuova, correzione) gli album smart con persone sono "toccati" e si ricalcolano a lotti | come E-AL7 |
| Senza volti qui (E-F37) | `PeopleMembership.available = false`: la regola non inserisce niente; l'album dice "Le persone non sono attive su questo dispositivo" | le altre regole valgono, ma da sole non riempiono l'album (sarebbe un album diverso) |
| Persona unita o dimenticata | unita: si segue l'alias; dimenticata: avviso "Una persona della regola non è più riconosciuta" | `PeopleIndex.resolve` |
| Album smart condiviso | le regole restano del creatore; le foto nuove vanno in "Da condividere" (F7-D17, Q4 della Fase 7) | nessun dato dei volti nell'area comune |
| "Disattiva e cancella" | "Tieni le foto di adesso come album normali" (congela, preselezionato) o "Togli la regola" | §2.8 |

### 2.8 "Disattiva e cancella" (8.1, E-F6; P8)
**Il foglio**: "Cancella i volti e i nomi delle persone dal NAS e da tutti i tuoi dispositivi. Le foto non si toccano." Poi i numeri
(`ErasePreview`): "Perderai i nomi di 12 persone", "3 album smart usano le persone: ☑ Tieni le foto di adesso come album normali", "Le
ricerche recenti con i nomi si cancellano", l'elenco dei dispositivi dell'account (con "spento dal 3 novembre: cancellerà appena si
riaccende"); con la recinzione "Le persone sono solo su questo dispositivo: si cancellano qui". Pulsante "Disattiva e cancella" (colore di
pericolo); `FacesEraseConfirmation` porta l'impronta dell'anteprima e scade dopo 10 minuti.

**I passi sul dispositivo che cancella** (diario `faces-erase` in `noBackupFilesDir`, **fuori** dal database dei volti, scritto con `fsync`
prima di ogni passo; ogni passo idempotente; una morte del processo riprende dal passo dopo l'ultimo segnato)
| Passo | Cosa | Ripresa e guasti |
|---|---|---|
| 1. Diario | `STARTED` con la generazione `G`, la scelta sugli album e l'impronta della conferma | senza diario non parte niente |
| 2. Stop | stato `Erasing(G)` in memoria e nel file `faces-local`; lavori dei volti annullati, motori rilasciati, pubblicatore chiuso (rifiuta tutto) | un lavoro che parte dopo vede `Erasing` e si ferma |
| 3. Album | con "Tieni le foto": gli elementi attuali di ogni album smart con persone diventano voci `item:` con impronta, la regola `smart:people` si toglie, un album che aveva solo quella diventa manuale; con "Togli la regola": solo la regola | servono i dati dei volti: si fa **prima** di cancellarli; le voci personali sono idempotenti |
| 4. Cronologia | `SearchHistoryPurge` toglie le ricerche recenti che contengono un nome noto in questa generazione (anche i nomi vecchi) | idem |
| 5. Voce | `faces = 1\|off\|G` nei dati personali (subito, anche senza l'ora del NAS: "da datare") | — |
| 6. Locale | **prima** `FacesKeys.destroy(G)` (alias `mcg-faces-<G>` tolto dal Keystore), **poi** `faces-<G>.db` con `-wal`, `-shm`, `-journal`, i modelli estratti, le cache in memoria; segno `LOCAL_DONE` | con la chiave distrutta ogni resto è illeggibile anche se un file non si toglie al primo colpo (si riprova) |
| 7. Annuncio | appena il NAS è raggiungibile (e il cancello è aperto): un giro dei dati personali che **pubblica** `off\|G` | gli altri dispositivi lo sanno al loro giro |
| 8. NAS | `FacesEraseOps.eraseGeneration(area privata, G, USER_CONFIRMED)`: l'**intera** `faces/<G>/` (le cartelle di tutti i dispositivi dell'account e le prenotazioni), dal fondo, file per file, poi le cartelle vuote; poi le altre generazioni non correnti eventualmente presenti (`NOT_CURRENT`) | a lotti (al più 20.000 file per giro), ripresa al giro dopo; congelamento, sola lettura, salto dell'ora = attesa con il motivo (D35) |
| 9. Verifica | elenco di `faces/`: nessuna cartella `G`; controllo del cestino di rete (E-F34): se nella radice dell'area privata c'è una cartella di cestino che contiene `.mycloudgallery/faces`, l'avviso "Il NAS ne tiene una copia nel suo cestino: svuotalo dal pannello del NAS" | ripetuta a ogni giro finché c'è un dispositivo che non ha confermato: se `faces/<G>/` ricompare (un dispositivo che pubblicava prima di saperlo) si toglie di nuovo |
| 10. Conferme | per ogni dispositivo approvato o dismesso dell'account: la sua ultima testa personale ha `faces` diversa da `on\|G` (`PersonalHeadsView.latestValueOf`) = **ha cancellato** (D30) | i revocati e i persi non si aspettano (L89); la pagina mostra chi manca e l'ultimo contatto |
| 11. Fine | `DONE` con la data; il diario resta 30 giorni per la pagina, poi si toglie | — |

**Gli altri dispositivi dell'account** (accesi, o al ritorno: E-F32)
1. Al giro dei dati personali leggono `off|G` (o una generazione più nuova): lo stato diventa `Erasing(G)`, calcolo e pubblicazione dei
   volti si fermano.
2. **Prima di ripubblicare lo stato personale** (che contiene la voce nuova) il gancio `PrePublishHook` esegue la cancellazione locale
   (chiave per prima, poi file); se non riesce risponde `Hold` e quel giro non pubblica: la testa personale con `off|G` esiste **solo**
   quando i dati sono cancellati, ed è la conferma che il passo 10 legge.
3. Poi, con lo stato fresco, tolgono la propria cartella `faces/<G>/<io>/` se c'è ancora e, se la trovano, l'intera `faces/<G>/`
   (`NOT_CURRENT`): basta un dispositivo dell'account che torna a casa perché il NAS si pulisca, anche se quello che ha cancellato non ci
   torna più.
4. Un dispositivo che torna dopo giorni: **nessun dato dei volti si pubblica senza un giro completo dei dati personali in questo processo**
   (stato fresco, D30), quindi non ripopola il NAS; la sua outbox dei volti sparisce con il database.

**Altri casi**
| Caso | Comportamento |
|---|---|
| Recinzione (share vere, D34) | passi 1–6 soltanto; sul NAS non c'è niente; la pagina dice "cancellato da questo dispositivo" |
| Esclusione di un dispositivo (D4) | solo il passo 6 su quel dispositivo; nessuna voce, niente sul NAS |
| Cancellazione fuori casa (E-F30) | passi 1–6 subito, 7–11 quando il NAS è raggiungibile; gli altri dispositivi intanto continuano, poi cancellano quando ricevono la voce |
| Riattivazione dopo (E-F33) | generazione nuova: le cartelle e i dati della vecchia, ovunque compaiano, sono "non correnti" e si tolgono |
| Nessun altro dispositivo e quello che ha cancellato non torna più a casa | i file restano sul NAS finché non torna o finché non si tolgono dal PC (cartella `.mycloudgallery/faces/` dell'area privata): la pagina lo dice e L89 lo dichiara |
| Dispositivo perso (E-F35) | l'azzeramento alla riconnessione distrugge la chiave dei volti nella fase (3) con le credenziali; la sua cartella sul NAS la tolgono gli altri (`DEVICE_REVOKED`, D31) |
| Voce falsa da un dispositivo compromesso dell'account | i dispositivi cancellano i dati dei volti (ricalcolabili) e i nomi; nessun media toccato (L93) |

**Invarianti** (provati dal banco del §5.5)
1. Dopo `LOCAL_DONE` su un dispositivo, nessun file `faces-<G>.db*` e nessuna chiave `mcg-faces-<G>` su quel dispositivo; nessun nome dei
   volti nella cronologia.
2. Nessuna testa personale di un dispositivo dice "non più G" mentre quel dispositivo ha ancora dati di `G`.
3. Dopo la prima voce `off|G` ricevuta, un dispositivo non pubblica più niente sotto `faces/<G>/` (può solo cancellare, D31).
4. A cancellazione finita (tutte le conferme), nessun file sotto `faces/<G>/` né sotto altre generazioni non correnti.
5. Mai un file cancellato fuori da `faces/` dell'area privata dell'account; mai un media toccato.
6. Il lavoro arriva in fondo: dopo le riprese lo stato è `DONE`, o in attesa con un motivo dichiarato (NAS, congelamento, dispositivi).

### 2.9 Nascosti su ogni superficie nuova (D28; P9, P10)
Le superfici si registrano in `HiddenSurfaces` (`app/src/test/.../hidden/`) con una riga nuova del §2.1, `SpecRow.PEOPLE` ("Persone: elenco,
conteggi, copertine, pagine, info, ricerca, album"), e la sonda sul mondo casuale (i volti del mondo li dà un motore finto: ogni media del
mondo ha 0–4 volti di identità sintetiche, compresi i nascosti, i nascosti a metà in un gruppo e i nascosti dell'ultimo momento).
| Superficie | Fonte | Prova |
|---|---|---|
| `people-section`: Raccolte → Persone, conteggi e copertine | `People.list` con celle da `VisibleMedia` e filtro finale `HiddenMask`; copertina solo da un media mostrabile | proprietà, E-F38 |
| `people-list` e `people-others`: Tutte le persone, Altri volti | come sopra; una persona con soli nascosti non c'è | proprietà |
| `person-page`: pagina persona e il suo viewer | `OrderedTimelineView` delle celle visibili (nemmeno `viewIndexOf` raggiunge un nascosto) | proprietà, come `album-viewer` |
| `face-review`: Controlla le foto, Dividi | volti di celle visibili | proprietà |
| `viewer-people`: riga "Persone" e "Chi è?" nelle info | i volti del media aperto; dai Nascosti la riga c'è (il media è aperto), ma non porta mai a una pagina che mostra altri nascosti | proprietà, F99 |
| `people-proposals`: "È la stessa persona?", "Forse è anche…" | copertine e conteggi da celle visibili | proprietà |
| `search-people`: chip, suggerimenti e risultati con una persona | candidati dall'istantanea (F6-D29), filtro finale; dizionario solo da persone con media visibili | sonda della ricerca estesa |
| `albums-people`: regola "Persone" degli album smart | candidati da `VisibleMedia` (come P6 della Fase 7) | sonda degli album smart |

Il registro fallisce da solo se un file nuovo che nomina `VisibleMedia` o `HiddenMask` non è elencato (regola di P9 della Fase 7). I volti
dei nascosti **si calcolano** (F6-Q6): nasconderli è una protezione dell'app (L2), e se tornano visibili sono già pronti.

### 2.10 Diagnostica e codici (P13)
| Codice | Quando | Messaggio e azione |
|---|---|---|
| `FACES_MODEL_MISMATCH` | un modello dei volti non torna con l'impronta | — (si riestrae dall'APK) |
| `FACES_ENGINE_FAILED` | il motore non parte o fallisce due volte di fila | Diagnostica |
| `FACES_VECTOR_INVALID` | N volti senza vettore (valori non finiti) | — |
| `FACES_NO_INDEXER` | nessun indicizzatore dell'account con Persone attiva | "Fallo su questo dispositivo" |
| `FACES_NO_PRIVATE_AREA` | area privata assente o uguale alla comune | Configurazione |
| `FACES_WAITING_PREVIEWS` | N media aspettano un'anteprima (D17) | — |
| `FACES_SEGMENT_REJECTED`, `FACES_HEAD_REJECTED` | dati dei volti non validi o di un altro account, per motivo | conteggio |
| `FACES_FOREIGN_GENERATION` | cartelle di generazioni non correnti trovate | si tolgono (D31) |
| `FACES_HOLDER_REBASED` | il custode si è riallineato (E-F26) | — |
| `FACES_KEY_LOST` | chiave dei volti inutilizzabile (E-F19) | — (riscaricati o ricalcolati) |
| `FACES_ERASE_LOCAL_DONE`, `FACES_ERASE_NAS_PENDING`, `FACES_ERASE_NAS_DONE`, `FACES_ERASE_WAITING_DEVICES` | stato di "Disattiva e cancella" | la pagina dice cosa manca |
| `FACES_RECYCLE_BIN` | il cestino di rete del NAS tiene copie (E-F34) | "Svuotalo dal pannello del NAS" |
| `FACES_NEEDS_UPDATE` | voce `faces` sconosciuta (E-F12) | Aggiorna |
| `PEOPLE_STATE` | stato della sezione (voce di stato di `DiagSection.PEOPLE`) | — |

Solo numeri e codici: quanti volti, persone, non assegnati, unità fatte, ultimo giro; mai nomi, id, chiavi, riquadri, vettori, conteggi
per persona, generazione (D37). Le chiavi nuove entrano nell'elenco chiuso dell'esportazione (`ExportKeys`).

### 2.11 Cosa serve dalle fasi precedenti e cosa la Fase 8 predispone
**Dalle fasi precedenti**
| Serve | Uso | Senza (oggi) |
|---|---|---|
| P6 della Fase 7 (motore degli album smart) in `main` | regola `people` (D27) | P10 lavora sull'esecutore di riferimento (`InMemoryAlbums`) e collega il vero quando entra |
| P13 della Fase 7 (`:app` dipende da `core-albums`, `AlbumsGraph`) | porta `PeopleMembership` | la regola esiste nei contratti; F98 aspetta P13 |
| F4-D31 spento sulle share vere (F64 della Fase 6, Q7) | la voce `faces` viaggia fra i dispositivi sulle share vere | sotto la recinzione Persone è per dispositivo (D34); sulle share di prova funziona tutto |
| Buffer, `AnalysisFetcher`, `AiPhone`, `Preparer` (Fase 6) | ingressi del calcolo | — |
| `LiteRT` 2.3.0, `ModelFiles`, `BundledModelFetcher` (Fase 6) | modelli e runtime | — |
| `HiddenSurfaces`, `VisibleMedia`, `HiddenMask` (Fase 7) | §2.9 | — |
| `CollectionsSectionProvider` (P11 della Fase 7) | sezione Persone | — |

**Per le fasi successive**
| Pezzo | Come lo usano |
|---|---|
| `People`, `PeopleIndex`, `PeopleLookup` | Fase 9: ricordi con le persone ("Un anno fa con Anna"), solo persone con nome e non nascoste; "mostra meno" per una persona (impostazione personale `memories_less:<persona>`, nella classe `people` o nei dati personali: lo decide la Fase 9) |
| Riga "Persone" del viewer | Fase 10.3: descrizioni dei media per TalkBack ("Foto con Marco e Anna") |
| Copertine ritagliate al momento | Fase 9: copertine dei ricordi; Fase 10: widget (mai persone nascoste) |
| Ritaglio del soggetto (Fase 6) e volti | nessun legame: il ritaglio non usa i volti e i volti non salvano ritagli |

### 2.12 Stima dei costi e prestazioni attese
**Ipotesi**: l'account dell'utente vede ~12.000 foto nell'area privata, poche foto di famiglia nell'area comune (le ~5.000 immagini sono
copertine di dischi, escluse da E-N14), ~6.600 media del telefono in gran parte già sul NAS; ~2.600 video. Volti: ~1,2 a foto con volti,
~60% delle foto con almeno un volto [stima] → **~10.000–20.000 volti**.
| Voce | Valore [stima] | Note |
|---|---|---|
| Calcolo sull'S25 (prima volta) | ~15.000 foto x 40 ms + ~3.000 seconde passate x 150 ms + ~2.600 video x 4 fotogrammi x 35 ms ≈ **25–35 minuti** | Tab S9 circa 1,5 volte; dentro una notte in carica |
| Raggruppamento iniziale | grafo dei vicini su 20.000 volti (~41 miliardi di moltiplicazioni a 128) **< 1 minuto**; incrementale < 50 ms a volto; notte < 30 s | JVM con 4 thread; P5 misura |
| Traffico | anteprime non nel buffer: ~15.000 x 150 KB ≈ 2,2 GB + HQ ~3.000 x 400 KB ≈ 1,2 GB → ~4 minuti a 16 MB/s; ri-preparazione dagli originali (solo se servisse, D17) ~22 GB di foto ≈ 25 minuti, una volta | Wi-Fi di casa (pesante) |
| Batteria | ~30 minuti di CPU di notte, dalla presa | dentro il preset Notte |
| A regime | 200 foto al mese → ~10 secondi di calcolo al mese | trascurabile |
| NAS (area privata) | corpi `faces` ~300 byte a volto → ~6 MB; istantanea ~1,5 MB; voci `people` pochi KB; per ogni dispositivo che calcola | si cancella tutto con la cartella |
| Dispositivo | database dei volti ~10–15 MB; modelli estratti ~20 MB; APK +~20 MB (Q1 (a)) | nessuna cache di ritagli su disco |
| RAM | modelli ~20 MB + interpreti ~30–60 MB durante il calcolo; vettori in memoria per il raggruppamento ~10 MB | rilasciati a fine tappa |

**Prestazioni da misurare** (P2 strumentato, F104): YuNet a 1024 p50/p95, SFace a volto p50/p95, decodifica e allineamento, foto al
secondo con il caldo, PSS, avvio a freddo dei due interpreti, verifica delle impronte; su **S25 e Tab S9**; i numeri in `docs/decisioni.md` e
`DeviceMeasures.facesPerSecond` (la stima della pagina di attivazione nasce da lì).

## 3. Alternative scartate

| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Embedder | SFace; MobileFaceNet; AuraFace-v1; dlib ResNet; InsightFace, EdgeFace, GhostFaceNets | **SFace** (D6, D9): pesi Apache-2.0, piccolo, abbinato a un rilevatore con i cinque punti; AuraFace (261 MB) e dlib (CC0, vecchio) sono il piano B; InsightFace, EdgeFace e GhostFaceNets hanno pesi o dati non commerciali (`decisioni.md` §AI) |
| Rilevatore | YuNet; ML Kit Face Detection; MediaPipe BlazeFace | **YuNet** (D6): MIT, 230 KB, volti piccoli, cinque punti; ML Kit manda statistiche a Google (come l'OCR, F6-Q4) e chiede volti da 100 px; BlazeFace riduce tutta la foto a 128 px e ha un solo punto per la bocca |
| Runtime | ONNX Runtime con i file originali; LiteRT dopo una conversione; MediaPipe Tasks | **LiteRT** (D6): c'è già e non parla con nessuno (F67); ONNX Runtime aggiungerebbe una libreria nativa di più MB di quanto pesano i modelli; MediaPipe Tasks annuncia statistiche d'uso (F6-D44) |
| Acceleratore | CPU; delegato GPU di LiteRT; NNAPI | **CPU** (D6): pochi millisecondi a volto; il delegato GPU è un artefatto nuovo; NNAPI è deprecata |
| Dove stanno i modelli | dentro l'APK; scaricati all'attivazione | **APK** (D8, Q1): 20 MB, nessun host, funzionano subito; scaricarli richiede una copia pubblicata dall'utente |
| Dove stanno i dati dei volti | nell'indice condiviso della share del file; nei dati personali; in una cartella per generazione nell'area privata | **Cartella per generazione nell'area privata** (D11, D13): l'indice della share comune lo leggono tutti (proposta 13); nei dati personali i nomi resterebbero nelle teste vecchie e in quelle dei dispositivi spenti; la cartella si cancella intera |
| Cifratura | nessuna; sul NAS con una chiave dalla password del NAS; solo sul dispositivo con una chiave del Keystore | **Solo sul dispositivo** (D14): la chiave per generazione dà la cancellazione crittografica dei resti; sul NAS non protegge da chi ha la password o i dischi, che hanno le foto, e cambiare la password farebbe ricalcolare tutto |
| Database | tabelle nel database della galleria (v7); un file a parte | **File a parte** (D15): si cancella e si verifica; nessuna migrazione sul campo |
| Raggruppamento | soglia + union-find (legame singolo); DBSCAN incrementale (Immich); Chinese Whispers; due passate (Apple); esemplari + legame medio + vincoli | **Esemplari con margine, nascite a legame medio, manutenzione notturna e vincoli** (D20): il legame singolo fa catene e non regge il 97%; Chinese Whispers non è deterministico; la combinazione prende la precisione di Immich e la prudenza di Apple (ricerca 0.4, §5.3) |
| Chi raggruppa | ogni dispositivo per conto suo; un custode per account | **Un custode** (D21): l'incrementale dipende dall'ordine di arrivo, e due dispositivi darebbero id diversi alle stesse persone |
| Id delle persone | impronta degli esemplari; numero progressivo; casuale con alias | **Casuale con alias** (D22): un'impronta cambia con gli esemplari; un numero progressivo si scontra fra due custodi; l'alias tiene vivi i riferimenti |
| Correzioni | per indice del volto; per posto | **Per posto** (D23): sopravvivono a un ricalcolo con un'altra ricetta |
| "Dimentica" | impronta negativa che riconosce la persona per ignorarla; solo i volti di oggi ignorati | **Solo i volti di oggi** (D24): un'impronta per "non riconoscere" qualcuno è un dato biometrico di chi non lo vuole; se torna, si dimentica di nuovo |
| Unità di lavoro | quelle del calcolo AI nella share; proprie dei volti nell'area privata | **Proprie, sotto la generazione** (D16): un'unità dell'area comune fatta da un altro account non dice niente dei volti di questo account; la cancellazione toglie anche le prenotazioni |
| Ingressi | originali; anteprime; anteprime con seconda passata | **Anteprime con la seconda passata sull'HQ** (D17): "mai gli originali" (6.2), ma nelle foto di gruppo a 1024 px i volti scendono sotto i 48 px |
| Video | niente; tutti i fotogrammi; copertina e 3 fotogrammi solo come volti deboli | **Copertina e 3 fotogrammi** (D18): "video con Marco" serve; i fotogrammi sono mossi e non devono far nascere persone |
| Screenshot e documenti | analizzati; esclusi | **Esclusi** (D18): volti di estranei, documenti d'identità: meno dati biometrici di terzi |
| Copertine | ritagli salvati; ritagli al momento | **Al momento** (D25): checklist della Fase 8, "nessun ritaglio di volti salvato se evitabile" |
| Attivazione | per dispositivo; per account con esclusione | **Per account con avviso ed esclusione** (D3, D4): come la lettura del testo (F6-Q13), una scelta della persona ma ogni dispositivo può restare fuori |
| Altri dispositivi | scaricano subito; aspettano l'avviso | **Aspettano l'avviso** (D4): un dispositivo non tratta dati biometrici prima che l'utente lo sappia lì |
| Cancellazione | lapidi nei dati personali; cartella intera; cifratura con la chiave sul NAS da distruggere | **Cartella intera, chiave locale distrutta, conferme dei dispositivi** (D29, D30): le lapidi lasciano i nomi nelle teste vecchie; una chiave sul NAS finirebbe nel cestino di rete come i file |
| Dispositivi spenti | aspettarli; cancellarne la cartella sul NAS; renderli innocui al ritorno | **Tutte e tre**: la cartella si toglie subito, al ritorno prima si cancella e poi si pubblica (D30), e la pagina dice chi manca |
| Minori | stima dell'età e regole a parte; stesse regole per tutti | **Stesse regole, nessuna stima** (D32): stimare l'età è un'altra inferenza su dati delicati; cosa si vede lo sceglie l'utente (Q2) |
| Chi sa che Persone è attiva | nessuno; la famiglia nella pagina Famiglia | **Nessuno** (Q3, raccomandata): è una scelta privata e i dati restano privati |
| Ricerca per persona | ricerca per somiglianza del volto (scegli un volto e trova simili); chip delle persone con un nome | **Chip delle persone** (D26): si cerca come si parla ("Marco al mare"); la somiglianza libera dei volti renderebbe cercabile chiunque, anche senza nome |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
P0 fondamenta, contratti, recinti, documenti ──┬── P1 modelli: conversione, parita', asset, catalogo ──┐
                                                ├── P6 database dei volti e cifratura ────────────────┤
                                                ├── P7 attivazione e consenso ────────────────────────┤
                                                ├── P2 motori on-device (dopo P1; prima con i fake) ──┤
                                                ├── P4 calcolo dei volti ─────────────────────────────┼── M1 (integrazione minima)
                                                ├── P5 raggruppamento stabile ────────────────────────┤
                                                ├── P9 persone: gestione e schermate ─────────────────┤
                                                ├── P3 formato firmato e sincronizzazione ────────────┤
                                                ├── P8 "Disattiva e cancella" (dopo P3, P6, P7) ───────┼── M2
                                                ├── P12 banco delle interruzioni (dopo P4, P8) ────────┤
                                                ├── P10 ricerca e album smart (album dopo P6/P13 F7) ──┤
                                                └── P11 valutazione e calibrazione (dopo P2, P5) ──────┴── P13 integrazione ── M3
                                                     ── check.ps1 (-Bench) + security-review + verifica funzionale ── (Q5) F110
```
- Dopo P0 tutti partono **insieme** sui contratti, sui fake fedeli (§5.2) e sugli esecutori di riferimento (`InMemoryPeople`,
  `ReferenceGrouping`, `InMemoryFacesRemote`, `FakeFaceEngines`, `InMemoryFacesNasEraser`); si incontrano in P13.
- **P2 aspetta P1** per i file veri (fino ad allora la suite di contratto gira sul motore finto); **P8 aspetta P3** per il pubblicatore e
  la replica veri (prima con `InMemoryFacesRemote`) e usa P6 e P7 dai loro contratti; **P12 aspetta P4 e P8** per gli invarianti sul
  codice vero ma li scrive subito sull'esecutore di riferimento.
- **P10**: la ricerca parte subito (contratti di `core-search` e la Fase 6 in `main`); gli album smart aspettano che P6 e P13 della Fase 7
  siano in `main` (§2.11) e intanto lavorano sull'esecutore di riferimento degli album.
- **M1** è un'integrazione parziale dell'orchestratore dopo P1, P2, P4, P5, P6, P7, P9: Persone sull'S25 sotto la recinzione o sulle
  share di prova, un dispositivo solo.
- **Prima di M2**: l'emulatore con l'account `test` (secondo dispositivo dello stesso account, avviato solo per le prove e spento subito
  dopo).

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Sull'S25 (debug, `test`): pagina di attivazione, modelli verificati, area di prova analizzata (ritratti di pubblico dominio e composizioni), persone formate e nominate, pagina persona, riga "Persone" nelle info; "Non su questo dispositivo" che cancella in locale | F94, F99, F104 (S25), F108 | P0, P1, P2, P4, P5, P6, P7, P9 (+ integrazione minima) |
| **M2** | Con l'emulatore `test`: avviso ed esclusione, nomi, unioni e divisioni che viaggiano (E-F5); "Disattiva e cancella" con tutti accesi, con l'emulatore spento che torna, fuori casa; banco delle interruzioni | F95, F96, F100–F103, F106 (PC), F109 | + P3, P8, P12 |
| **M3** | Ricerca per persona e album smart con persone; ≥ 97% dei gruppi senza persone sbagliate sui dati veri; prestazioni su S25 e Tab S9 | F97, F98, F104 (Tab S9), F105, F107 | + P10, P11, P13 |
| Chiusura | Revisione di sicurezza e verifica funzionale; checklist (§6.3); screenshot approvati; con il consenso (Q5) la sincronizzazione delle persone sulle share vere | `check.ps1`, `check.ps1 -Bench`, `security-review`, verifica funzionale, F110 | — |

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–7)
- **Mai** toccare file fuori dal proprio perimetro. `settings.gradle`, `build.gradle`, manifest, `check.ps1`, `InfoBuilder.kt`,
  `ComputeSession.kt`, `NasPersonalSync.kt`, `Leases.kt`, `NasLayout.kt`, `ItalianQueryParser.kt`, `LocalData.kt`, `MainScaffold.kt`,
  `AppNavHost.kt` solo dai pacchetti indicati al §1.5 e al §4.4.
- **Dati biometrici**: nessun volto, ritratto, vettore o nome di una persona reale della famiglia nel repository, nei test, nei log, negli
  screenshot o nei report; le prove usano identità sintetiche (JVM) e ritratti di **pubblico dominio** (dispositivo, §5.2).
- **Nessuna azione distruttiva da dati remoti** oltre l'eccezione di D31: ogni pacchetto che legge dati dei volti, la voce `faces` o il
  registro ha il test "un dato remoto falso non cancella niente fuori da `faces/` e non tocca mai un media" (porte finte che falliscono se
  chiamate fuori dalle regole).
- **Conferme**: `FacesActivationConsent` e `FacesEraseConfirmation` le costruiscono solo la pagina di attivazione e il foglio (test
  d'architettura).
- **Generazione**: ogni scrittura e ogni lettura dei dati dei volti porta la generazione; nessuna pubblicazione senza lo stato fresco
  (D30); un test per pacchetto con la generazione che cambia a metà.
- **Nascosti**: ogni superficie nuova si registra in `HiddenSurfaces` e ha il test "un nascosto perfetto non esce mai" (D28).
- **Ora**: ora del NAS per voci, teste e prenotazioni; monotono per durate e freschezza.
- **Log**: mai nomi, id di persone o volti, chiavi, riquadri, vettori, generazione, conteggi per persona; solo numeri e codici; `toString()`
  sicuri.
- **Ogni passo in più tappe è idempotente** e ha il test "guasto a ogni chiamata" (`FaultPlan` prima e dopo) e la morte del processo a ogni
  salvataggio (calcolo, pubblicazione, istantanea, diario della cancellazione).
- **F4-D31, F4-D33 (`ShareFence`), D34 (`facesSyncTestSharesOnly`) e la guardia delle prove dal PC (F4-D30)** valgono in ogni prova.
- **Fake fedeli con test di parità** (§5.2).
- Test JVM o Robolectric per ogni modulo; strumentati per i motori (emulatore, S25, Tab S9); screenshot (solo dove c'è UI) in
  `docs/screenshots/8-<pacchetto>/`, chiaro e scuro, telefono e tablet, testo a 1,3x; TalkBack e bersagli da 48 dp.
- Stringhe in italiano in file propri.
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti degli
  altri**.

### 4.4 I pacchetti
"Agente": **interno** per tutti (OpenCode non è più disponibile); chi tocca dispositivi, NAS di prova o scaricamenti lo dice nella colonna
"Campo".

| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Agente | Campo |
|---|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Moduli, contratti, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`); creazione di `core-faces` e `feature-people` (build, manifest, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest`, `NoWallClockSourcesTest`); **contratti** del §1.2 con null-object, fake ed esecutori di riferimento (`InMemoryPeople`, `ReferenceGrouping`, `InMemoryFacesRemote`, `InMemoryFacesNasEraser`, `FakeFaceEngines`, `FakeFacesKeys`); firme in `core-model`, `core-trust` (`DocType.FACES_HEAD`, `FACES_VERSION`, classi 18–20, percorsi di `NasLayout`), `core-ai` (`contract/Faces.kt`, `BufferKeepers`), `core-models` (ruoli, `ModelFeature`), `core-personal` (`SETTING_FACES`, `FacesSettingCodec`, `PrePublishHook`, `PersonalHeadsView`), `core-security` (`FacesKeys`), `core-nasops` (`FacesNasEraser`, solo interfaccia e null-object), `core-search` (`SearchChip.Person`, `PeopleDictionary`, `PeopleLookup`), `core-albums` (`PeopleRule`, `PeopleMembership`), `feature-viewer` (`ViewerPeople`), `core-common` (`facesSyncTestSharesOnly` accesa anche in release, `DiagSection.PEOPLE`, `Phase8ArchitectureTest`); `tools/check.ps1` (recinzione nella release, LiteRT solo in `core-ai`); documenti del §0.3 | Fase 7 P0 (contratti), Fase 6 in `main` | `check.ps1` verde; ogni test d'architettura fallisce reintroducendo la violazione (`core-faces` che nomina `core.nasops`, `core.search`, `core.albums`, `java.net`; `NasFileSystem` fuori da `sync/`; LiteRT fuori da `core-ai`; `feature-people` che vede `core-ai` o `core-trust`; le due conferme costruite fuori dal loro file; `PERSON` in una testa `personal`; una scrittura sotto l'area comune); gli esecutori di riferimento passano le suite di contratto (§5.2); documenti aggiornati (§0.3) | **opus** | interno | no |
| **P1 Modelli** | §2.2, D6–D8 | `tools/models/faces/**` (ambiente con impronte, conversione, parità, scelte), `core-ai/src/main/assets/faces/**` (i due `.tflite`, licenze, `NOTICE`), `core-models/.../contract/ModelCatalog.kt` (voci, `needed`), `core-models/src/test/.../CatalogMatchesDesignTest.kt` | P0; **scaricamenti approvati** (§8) | parità ONNX contro `.tflite` sui ritratti di prova e su 300 volti delle composizioni (riquadri IoU ≥ 0,95, cinque punti entro 1 px, coseno ≥ 0,999 in float16); regole di scelta di D7 scritte **prima** di misurare e applicate; impronte e dimensioni nel catalogo e nel test; licenze e avvisi nell'APK; dimensione dell'APK prima e dopo; nessun file ONNX né ambiente Python nel repository (solo gli script e `requirements.txt` con le impronte); numeri e versioni in `docs/decisioni.md` | sonnet | interno | no (PC) |
| **P2 Motori on-device** | D6, D10, D19, §2.2 "Nel telefono" | `core-ai/.../faces/**` (`YuNetDetector`, decodifica e NMS, tasselli, `FaceAligner`, `SFaceEmbedder`, `FaceQuality`, `FaceLiteRtSessions`), `ModelInputs.forFaces`, `core-ai/src/androidTest/**/faces/**` (vettori di riferimento dai ritratti di prova) | P0, P1 (file veri; prima i fake) | suite di contratto `FaceEnginesContract` comune a fake e motori veri; JVM: decodifica delle uscite e NMS su tensori registrati, allineamento contro i punti di riferimento, qualità e livelli; strumentati (emulatore, S25, Tab S9): parità con i riferimenti di P1 (coseno ≥ 0,999), ingressi ostili mai al decodificatore (contatore come P4 della Fase 6), un interprete che lancia su un volto non fa perdere gli altri; misure p50/p95 di YuNet a 1024 e di SFace a volto, PSS, avvio a freddo, verifica delle impronte; `DeviceMeasures.facesPerSecond`; numeri in `docs/decisioni.md` | sonnet | interno | sì (F104) |
| **P3 Formato firmato e sincronizzazione** | D11–D13, §2.4 | `core-trust/.../data/FacesDocs.kt` (più le righe minime in `Envelope.kt`, `IndexSegment.kt`, `TrustFormat.kt`, `NasLayout.kt`, `Leases.kt` per la radice delle prenotazioni, `FacesOwnFiles`), `core-faces/.../sync/**` (pubblicatore, replica, scelta dei vincitori e dell'istantanea, fusione delle voci `people`, filtro della generazione, M3 dei volti) | P0 (P6 per gli adattatori Room) | test ostili per ogni campo del §2.4 (corpi, norme, NaN, livelli, riquadri, punti, lunghezze, testi con caratteri vietati, campi sconosciuti, generazione in disaccordo con il posto, account diverso, versioni 1–3 e 5); anti-rollback ed equivocazione; dismesso (teste congelate) e revocato (ignorato); fusione commutativa, associativa e idempotente (proprietà su 10.000 storie di 4 dispositivi); il pubblicatore scrive solo sotto `faces/<G>/<io>/` dell'area privata e solo con `publishExclusive`, **mai** nell'area comune (proprietà); niente si pubblica senza lo stato fresco; teste di un altro account mai lette; 200 giri del banco con `FaultPlan` | **opus** | interno | sì (F95, F96, F106) |
| **P4 Calcolo dei volti** | D16–D19, §2.3 | `core-faces/.../compute/**`; in `core-ai` solo `BufferKeepers` dentro `ComputeSession.pruneFinished` | P0 (fake di P2, P3, P6) | la tabella "una volta per account" del §2.3 in test, riga per riga, con due dispositivi e due account finti; morte del processo dopo ogni salvataggio e `FaultPlan` a ogni chiamata: nessun media rifatto; mai l'area privata di un altro account (proprietà); screenshot, documenti e ritagli saltati; nascosti calcolati; seconda passata solo quando serve; video solo WEAK; generazione che cambia a metà = tappa ferma; recinzione = niente sul NAS; il buffer tiene le anteprime chieste; ritmo ridotto col caldo; stima dal lavoro vero | sonnet | interno | sì (F94, F103) |
| **P5 Raggruppamento stabile** | D20–D23, §2.5 | `core-faces/.../grouping/**`, il generatore di identità sintetiche in `core-faces/.../testing/` (mai nel dex di release) | P0 (fake di P3) | prove del §2.5: purezza ≥ 99% sul sintetico; stabilità degli id (proprietà); incrementale contro ricalcolo; correzioni sempre rispettate; custode unico, due custodi e riallineamento senza nomi persi, passaggio del custode; ricalcolo completo con abbinamento (≥ 95% degli id ereditati); alias con catene e cicli; deterministico a parità d'ingresso; misure JVM: 20.000 volti < 60 s la prima volta, < 50 ms a volto dopo, notte < 30 s | **opus** | interno | sì (F96, F105) |
| **P6 Database dei volti e cifratura** | D14, D15 | `core-database/.../faces/**` (schema v1 esportato), `core-faces/.../store/**`, `core-security/.../faces/**` (`FacesKeyStore`), `docs/modello-dati.md` §15 | P0 | schema esportato e `FacesSchemaTest`; nessuna tabella del database della galleria toccata (v6 invariata); colonne cifrate con la chiave dati avvolta dal Keystore (Robolectric con chiave finta, strumentato con la vera); **nessun vettore o nome in chiaro nel file** (test che cerca nei byte del database i valori seminati); distruzione della chiave = dati illeggibili; cancellazione come file; chiave sparita = `FACES_KEY_LOST` senza crollo (E-F19); query su 20.000 volti < 50 ms | sonnet | interno | no |
| **P7 Attivazione e consenso** | D3–D5, §2.1 | `core-faces/.../switch/**`, `core-personal` (`FacesSettingCodec`, lettura e scrittura del campo), `feature-people/.../activation/**` (pagina, avviso, Impostazioni → Persone), `feature-settings` (la voce) | P0 | ogni riga del §2.1 in test (attivazione senza ora del NAS, avviso che blocca scaricamento e calcolo, esclusione che cancella in locale, riattivazione, testo nuovo, voce sconosciuta senza cancellazioni, due attivazioni insieme, requisiti); la pagina con il testo del §2.1 e la versione; consenso costruito solo dalla pagina; TalkBack, 1,3x, 48 dp; screenshot `8-P7` | sonnet | interno | sì (F94, F95) |
| **P8 "Disattiva e cancella"** | D29–D31, §2.8 | `core-faces/.../erase/**`, `core-nasops/.../faces/**` (`FacesNasEraser`, pulizia dei propri file dei volti), `core-personal/.../sync/NasPersonalSync.kt` (solo il gancio `PrePublishHook` e `PersonalHeadsView`), `feature-people/.../erase/**` (foglio e stato) | P0, P3, P6, P7 (prima con i riferimenti) | ogni passo del §2.8 idempotente con la morte del processo a ogni salvataggio del diario e `FaultPlan` a ogni chiamata; i sei invarianti del §2.8 come proprietà; dispositivo spento che torna (mai una pubblicazione di `G`); calcolo in corso durante la cancellazione; cancellazione fuori casa; congelamento; generazioni non correnti e dispositivi revocati; cestino di rete (E-F34); **mai** un percorso fuori da `faces/` (proprietà sui percorsi) e **mai** un media; voce falsa = solo dati dei volti; cronologia e album smart; screenshot `8-P8` | **opus** | interno | sì (F100–F102) |
| **P9 Persone: gestione e schermate** | D24, D25, §2.6, §2.9 (le sue superfici) | `core-faces/.../people/**`, `feature-people/**` (tranne `activation/` ed `erase/`), `feature-viewer/.../info/` (riga "Persone", solo P9) e le azioni sui volti, `feature-library/.../collections/testing/CollectionsSectionSamples.kt` (testo), le righe `PEOPLE` di `HiddenSurfaces` con le sonde | P0 (fake di P3, P5) | ogni azione del §2.6 con le voci giuste e l'effetto subito; due dispositivi finti: nomi, unioni e divisioni che convergono (E-F5); Q2 (a) e (b) dietro un'opzione; copertine solo da media mostrabili, mai su disco; superfici registrate e proprietà verde (e rossa togliendo il filtro); ogni stato del §1.5 composto; TalkBack, 1,3x, 48 dp; screenshot `8-P9` | sonnet | interno | sì (F96, F99) |
| **P10 Ricerca e album smart** | D26, D27, §2.7 | `core-search/.../parse/**` (dizionario e regole), `core-search/.../engine/**` (candidati, suggerimenti, cronologia), `feature-search` (chip), `core-albums/.../smart/**` (regola `people`, dopo P6 della Fase 7) e `personal/SmartRulesText.kt` (`smart:people`), `feature-albums` (editor), le sonde `search-people` e `albums-people` | P0; P6 e P13 della Fase 7 per la parte degli album | tabella pubblica di ≥ 120 query con nomi inventati (persone, congiunzioni, ambiguità con feste, luoghi e mesi, refusi, nomi di più parole); candidati sicuri e "forse"; nascosti mai (proprietà); dizionario vuoto = nome come testo; regola degli album su 200 album sintetici (sicuri e suggeriti, "e" con le altre regole, alias, dimenticata, dispositivo senza volti); cronologia ripulita alla cancellazione; screenshot `8-P10` | sonnet | interno | sì (F97, F98) |
| **P11 Valutazione e calibrazione** | §5.3 | `core-faces/src/test/.../eval/**` (metriche), `app/src/debug/.../eval/faces/**` (strumento di etichettatura, solo debug), `tools/fixtures/faces/**` (elenco dei ritratti di pubblico dominio con le licenze, generatore delle composizioni) | P2, P5 | metriche (purezza dei gruppi, errori per volto, frammentazione, copertura) calcolate; strumento solo in debug (assente dal dex di release, `check.ps1`); **nessuna etichetta, nome o volto dei dati veri nel repository** (test su git, `.gitignore`); soglie scelte con la regola del §2.5 e scritte in `docs/decisioni.md` con i numeri | sonnet | interno (la corsa sui dati veri la fa l'utente) | sì (F105) |
| **P12 Banco delle interruzioni** | §5.5 | `core-faces/src/test/.../chaos/**`, `tools/chaos/faces-pc/**`, `tools/s25.ps1` (modo `faces`), `docs/prova-500.md` (sezione della Fase 8) | P0 (riferimento), poi P4, P8 | invarianti del §5.5; 2.000 giri JVM; 600 sul simulatore SMB; 400 dal PC sul NAS di prova; ≥ 100 sull'S25; `ChaosMutationTest` (pubblicare senza stato fresco, dichiarare la cancellazione prima di farla, togliere i file prima della chiave, cancellare fuori da `faces/`: rossi con l'invariante giusto) | sonnet | interno | sì (F103) |
| **P13 Integrazione** | §1.4 | `app/**` (`FacesGraph`, porte, lavori, navigazione, `MainScaffold`, azzeramento, `DeviceSelfWipe` con le chiavi dei volti), `feature-diagnostics/**`, `README.md`, `docs/sicurezza.md`, `docs/decisioni.md`, `docs/famiglia-dispositivi.md` | P0–P12 | test d'integrazione sul NAS finto con due account e tre dispositivi: attivazione, calcolo, istantanea, nomi che viaggiano, dispositivo dell'altro account che non vede niente, cancellazione con un dispositivo spento che torna; azzeramento completo (nessun `faces-*.db`, nessuna chiave `mcg-faces-*`); codici della Diagnostica nel catalogo e nell'elenco chiuso dell'esportazione; registro dei Nascosti verde; `check.ps1` verde; APK debug e release | sonnet | interno | sì |

> Nota sui crediti: opus per 4 pacchetti su 14, quelli che il brief indica: **P0** (contratti e recinti: conferme, porte, nessuna strada
> nuova verso cancellazioni), **P3** (formato firmato dei dati dei volti, da cui dipende la fiducia fra i dispositivi dell'account), **P5**
> (raggruppamento stabile: id, custode, riallineamento), **P8** ("Disattiva e cancella", l'unica parte che toglie dati, anche da dispositivi
> che non rispondono). P4 è simile al calcolo AI della Fase 6 (che era opus) ma riusa i suoi pezzi: sonnet con il brief completo e una
> revisione opus mirata nella `security-review` se il banco di P12 trova violazioni.

### 4.5 Chiusura della fase
- `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro le guardie delle Fasi 3–7 (la Libreria non peggiora con il calcolo dei
  volti in corso); F94–F109 superate (F110 con il consenso, Q5).
- `security-review` (opus) con revisione mirata del codec dei volti di `core-trust`, della sincronizzazione, della cancellazione (`erase/`,
  `FacesNasEraser`, gancio di `NasPersonalSync`), della cifratura locale e degli ingressi dei motori; **in parallelo un agente di verifica
  funzionale** (§5.7).
- Checklist della Fase 8 spuntata (§6.3); `docs/sicurezza.md` aggiornato; screenshot approvati; risposte del §9 registrate.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-faces` (switch, compute, grouping, people, sync, erase), codec di `core-trust`, decodifica di YuNet, allineamento e qualità di `core-ai` | regole, raggruppamento, fusione, formato ostile, cancellazione, generazione |
| NAS finto | `InMemoryNasFileSystem`, famiglia finta con due account e più dispositivi (approvati, dismessi, revocati, spenti), dati personali veri (`NasPersonalSync` con il gancio) | sincronizzazione, custode, cancellazione con dispositivi spenti |
| Simulatore SMB | `InMemorySmbBackend` con le vere `SmbNasFileOps` e il vero `FacesNasEraser` | cancellazione stretta, pulizia dei propri file, prenotazioni sotto la generazione |
| NAS vero dal PC | `tools/chaos/faces-pc` con gli account di prova (`test`, `test2`), guardia F4-D30 | banco delle interruzioni (§5.5), privacy fra account (F106), verificatore della cancellazione |
| Robolectric (SDK 35) | database dei volti, Compose, chiave del Keystore finta | |
| Roborazzi | `feature-people`, riga delle info, chip, editor | chiaro e scuro, telefono e tablet, testo a 1,3x |
| Strumentati | motori veri su emulatore x86_64, S25 e Tab S9; Keystore vero | parità, riferimenti, misure, distruzione della chiave |
| Benchmark | guardie esistenti con il calcolo dei volti in corso | la Libreria non peggiora (§5.4) |
| Dispositivi | S25 (`test`), emulatore (`test`, secondo dispositivo), Tab S9 nel momento breve | §5.6 |

### 5.2 Fake fedeli, test di parità e dati senza persone vere
| Fake | Fedeltà | Parità |
|---|---|---|
| `FakeFaceEngines` (rilevatore ed embedder) | da una tabella di **identità sintetiche**: ogni immagine di prova (o media del mondo finto) ha 0–64 volti con riquadri, cinque punti, punteggi, livelli e un vettore a 128 dimensioni normalizzato attorno al centro della sua identità (dispersione realistica; coppie "gemelli" vicine; deriva lenta per l'età; WEAK più rumorosi); latenze e guasti iniettabili; float16 andata e ritorno | la suite `FaceEnginesContract` (dimensioni, norme, determinismo, un volto alla volta, errori come codici, rilascio idempotente) gira sul fake (JVM) e sui motori veri (strumentato) |
| `ReferenceGrouping` | raggruppamento a forza bruta con le stesse regole di D20 e gli stessi vincoli | su 500 storie sintetiche il raggruppamento vero dà gli stessi gruppi del riferimento salvo i casi a ± 1e-4 dalle soglie, e gli stessi id dove il riferimento li conserva |
| `InMemoryFacesRemote` | teste e segmenti veri (codec di P3), serie numerate, `publishExclusive`, file spazzatura, cartelle di altri account | la suite di `NasPersonalRemote` adattata (anti-rollback, salti, collisioni) |
| `InMemoryFacesNasEraser` | cancellazione per cartelle, tetti per giro, congelamento, guasti prima e dopo | la stessa suite gira su `FacesNasEraser` con il simulatore SMB: stessi file tolti, stessi esiti |
| `FakeFacesKeys` | chiave dati in memoria, distruzione, chiave sparita | `FacesKeysContract` sul fake e sul Keystore vero (strumentato) |
| Famiglia finta | registro firmato con due account, dispositivi approvati, dismessi (teste congelate), revocati, persi | test del registro esistenti |

**Dati senza persone vere** (nessun dato personale nel repository)
- **JVM**: solo identità sintetiche (vettori), mai immagini di volti.
- **Dispositivo e parità**: una trentina di **ritratti di pubblico dominio** (dipinti antichi da Wikimedia Commons, licenza PD-Art, con
  la nota della fonte per ognuno): più autoritratti dello stesso pittore a età diverse (Rembrandt, Van Gogh: la stessa persona negli anni,
  E-F2), ritratti di persone diverse, di profilo, con cappelli; più le **composizioni** del generatore di P11 (foto di gruppo con 3–8 volti
  piccoli su sfondi diversi, un ritratto dentro una "televisione" per E-F3, la stessa persona due volte per E-F15, un collage). Scaricati
  solo con il consenso (§8), stanno in `tools/fixtures/faces/` e negli asset dei test strumentati; i nomi delle persone ritratte non
  entrano nei nomi dei file né nei test (si usano etichette "ritratto-07").
- **Qualità vera**: solo sul telefono dell'utente, con lo strumento solo debug (§5.3).

### 5.3 Valutazione della qualità sui dati veri, senza dati personali nel repository
**Il problema**: il "≥ 97% dei gruppi senza persone sbagliate" (8.1) si misura solo sulle foto della famiglia; né i volti né i nomi né le
etichette devono finire nel repository.
1. **Pubblico, nel repository** (a ogni `check.ps1`): il raggruppamento su 500 storie sintetiche (P5): purezza ≥ 99%, frammentazione
   dichiarata, correzioni rispettate; i motori veri sulle composizioni di prova (P2, strumentati): richiamo dei volti fra 24 e 48 px,
   persone giuste negli autoritratti.
2. **Privato, fuori dal repository** (la misura che conta, F105):
   - l'utente attiva Persone sul **suo** S25 con la Libreria vera (con la recinzione tutto resta sul telefono: niente sul NAS);
   - **sessione 1, calibrazione** (~20 minuti): con lo strumento **solo debug** (Diagnostica → Sviluppo → "Valutazione delle persone")
     vede 40 gruppi scelti a caso fra quelli mostrati (con peso alla dimensione) e, per ciascuno, la griglia dei volti (fino a 60, più i 10
     meno simili al gruppo, dove gli errori sono più probabili): tocca i volti che non sono di quella persona, oppure "Gruppo misto"; da qui
     le coppie "stessa persona" e "persone diverse" con cui P11 sceglie le soglie (§2.5) e le scrive nel codice;
   - **sessione 2, misura** (~20 minuti, dopo il ricalcolo con le soglie nuove): tutti i gruppi mostrati se sono al più 80, altrimenti
     tutti quelli con un nome più un campione casuale fino a 80; stessa griglia. **Purezza** = gruppi senza nessun volto sbagliato / gruppi
     valutati; in più errori per volto, gruppi della stessa persona separati (l'utente può dire "è la stessa di …"), volti assegnati;
   - le etichette sono localizzatori di volti in `noBackupFilesDir/eval/faces/` (fuori dai backup, tolti dall'azzeramento, I11 della
     revisione B); lo strumento esporta **solo numeri**, che vanno in `docs/decisioni.md`.
3. **Obiettivo**: purezza ≥ 97% (con la calibrazione puntata a 98,5%). Se manca: soglie più severe (si perde copertura, non purezza); se
   anche così la copertura scende sotto il 50% dei volti CORE, si passa al piano B del modello (§7), e la scelta torna all'utente solo se
   cambia il peso o l'host (Q1).
4. **Tempo dell'utente**: circa 40 minuti in due momenti a sua scelta, sul telefono (Q4); non serve il tablet.

### 5.4 Benchmark
- Nessuno scenario nuovo nel `:benchmark`: le guardie di scorrimento, apertura del viewer e avvio non devono peggiorare con il calcolo dei
  volti in corso e con la sezione Persone aperta (`check.ps1 -Bench` su S25 e Tab S9).
- Misure dei motori con i test strumentati di P2 (non Macrobenchmark): p50 e p95 di YuNet e SFace, foto al secondo, PSS, con le soglie di
  regressione in `benchmark/soglie.json`, scenario `volti` (+15%).

### 5.5 Il banco delle interruzioni (calcolo e cancellazione, P12)
**Invarianti** (dopo ogni morte, a fine passo e in una rilettura finale)
1. **Nessun lavoro perso o rifatto**: un volto salvato non si ricalcola; ogni media in ambito ha la sua voce `faces` o è "non supportato".
2. **Nessun dato fuori posto**: niente sotto `faces/` dell'area comune; niente sotto `faces/<G>/<altro dispositivo>/` scritto da questo
   dispositivo; nessuna pubblicazione senza lo stato fresco.
3. **Generazione**: dopo la prima voce `off|G` vista, nessuna pubblicazione di `G` (anche da un dispositivo spento durante la
   cancellazione e ripartito dopo).
4. **Cancellazione completa** (i sei invarianti del §2.8): chiave prima dei file; nessun file `faces-<G>.db*` né chiave dopo `LOCAL_DONE`;
   nessuna testa personale "non più G" con i dati ancora lì; a fine giro nessun file sotto `faces/<G>/`.
5. **Mai fuori da `faces/`**: nessun file del NAS fuori da `.mycloudgallery/faces/` toccato; nessun media toccato (impronte prima e dopo).
6. **Istantanee coerenti**: ogni istantanea pubblicata rilegge uguale; nessun id di persona perso fra due istantanee salvo un alias.
7. **Prenotazioni**: rilasciate o scadute entro l'ora; mai la stessa unità a due dispositivi insieme (salvo `Contended`).
8. **Il lavoro arriva in fondo**: dopo le riprese calcolo finito o in attesa con un motivo; cancellazione `DONE` o in attesa con un motivo
   dichiarato.

**Interruzioni**
| Dove | Come | Quante |
|---|---|---|
| JVM, NAS finto | `FaultPlan` (rete prima e dopo, sessione scaduta, stallo, spazio finito), morte del processo a ogni salvataggio (database, outbox, istantanea, diario della cancellazione, registro dei dati personali), generazione che cambia a metà, dispositivo spento che torna | 2.000 giri a ogni `check.ps1` |
| JVM, simulatore SMB | come sopra con le vere `SmbNasFileOps` e il vero `FacesNasEraser` | 600 giri |
| PC, NAS vero (share di prova) | `tools/chaos/faces-pc`: lo stesso motore sulla JVM del PC contro `test`, processo ucciso e connessione tagliata a tempi casuali durante pubblicazione e cancellazione, verificatore che rilegge tutto | 400 |
| Telefono, area di prova | `s25.ps1 -Mode faces`: `kill -9` e Wi-Fi spento per 3–20 s a tempi casuali durante calcolo, raggruppamento e "Disattiva e cancella", poi il verificatore dal PC e il controllo dei file del telefono con `run-as` (debug) | ≥ 100 |

### 5.6 Verifica sul campo
**Come si lavora**: le prove girano **da sole** sull'S25 con la build debug e l'account `test` (area comune `test-comune`): uno scenario per
prova nel pannello di debug "Prove della Fase 8" (come le prove guidate delle fasi precedenti), che semina i ritratti di pubblico dominio
e le composizioni in `mcg-prova`, guida l'interfaccia con UI Automator dove serve una conferma (anche il foglio "Disattiva e cancella" e la
pagina di attivazione dell'**account di prova**) e scrive l'esito in un file letto dal PC con `adb`; il verificatore dal PC (`mcg-it`)
controlla il NAS di prova. Il secondo dispositivo dello stesso account è un **emulatore** con `test`, acceso solo per M2 e spento subito
dopo. L'utente serve solo per **F105** (circa 40 minuti, quando vuole) e per **un momento breve con il tablet** (circa 15 minuti, insieme ai
momenti con il tablet ancora in sospeso delle Fasi 6 e 7: F86, F74, le misure del Tab S9).

**A. Preparazione**
1. S25 con la build debug e `test`; emulatore con la stessa build e `test` (secondo dispositivo, approvato con la guardia di prova).
2. Ritratti di pubblico dominio e composizioni (P11) in `mcg-prova` e, dal backup di prova, in `test`.
3. Per F105: la build debug con lo strumento di valutazione sul telefono con la Libreria vera e Persone attivata dall'utente (la recinzione
   tiene tutto sul telefono).
4. Per F104 e F106 sul tablet: il profilo "Prova" del Tab S9 con `test2` (F106); le misure del Tab S9 sono i test strumentati di P2 (APK di
   prova di `core-ai`, nessun account, modelli copiati con `adb` e tolti alla fine, come nella Fase 6).

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F94 | Attivazione e primo calcolo (autonoma, S25): pagina di attivazione, "Attiva", "Analizza ora" sull'area di prova | modelli verificati; persone formate (ogni pittore un gruppo; gli autoritratti a età diverse uniti o proposti, E-F2); dal PC solo file sotto `test/.mycloudgallery/faces/<G>/<S25>/`, niente in `test-comune`; nessun host Internet contattato (cattura, se PCAPdroid è installato) |
| F95 | Secondo dispositivo (autonoma, emulatore `test`): avviso, "Va bene"; poi "Non su questo dispositivo" | prima dell'avviso nessun file dei volti sull'emulatore (`run-as`); dopo, persone e nomi uguali all'S25; l'esclusione toglie database e chiave sull'emulatore e non cambia niente sull'S25 né sul NAS |
| F96 | Gestione (autonoma, S25 e emulatore): nome, rinomina, Unisci (con due nomi), Dividi, Non è…, È…, Ignora, Nascondi, Dimentica, copertina | ogni azione vista sull'altro dispositivo dopo un giro (E-F5); id uguali sui due; nessun volto torna dove era stato tolto |
| F97 | Ricerca per persona (autonoma): "<nome>", "<nome> e <nome>", "<nome> 2024" (date di prova), un nome che è anche un luogo dell'area di prova | chip giusti con l'alternativa; risultati solo delle persone; su un dispositivo escluso il nome resta testo |
| F98 | Album smart con persone (autonoma, dopo P13 della Fase 7): "Crea album smart" dalla pagina persona; regola "tutte" con due persone | sicuri e suggeriti; un ritratto nuovo della persona entra da solo; sul dispositivo escluso l'avviso |
| F99 | Nascosti (autonoma): un ritratto nascosto di una persona, una persona con soli ritratti nascosti | non compaiono in nessuna superficie del §2.9 (UI Automator legge le descrizioni accessibili); conteggi senza di loro |
| F100 | "Disattiva e cancella" con tutto acceso (autonoma) | dal PC nessun file sotto `test/.mycloudgallery/faces/`; sui due dispositivi nessun `faces-*.db*`, nessuna chiave `mcg-faces-*` (strumento di debug), nessun nome nella cronologia; album smart congelati con le foto; la pagina dice "fatto ovunque" |
| F101 | "Disattiva e cancella" con l'emulatore **spento** (autonoma): cancellazione dall'S25, poi l'emulatore riacceso più tardi, a cancellazione già fatta sul NAS | la pagina dell'S25 dice "l'emulatore cancellerà appena si riaccende"; al ritorno l'emulatore cancella prima di pubblicare (la sua testa personale lo conferma), il NAS resta vuoto; riattivazione = generazione nuova, nessun dato vecchio |
| F102 | "Disattiva e cancella" fuori casa (autonoma: Wi-Fi spento, poi riacceso) e interrotta (`kill -9` a metà) | locale subito; NAS al ritorno; il diario riprende fino a `DONE` |
| F103 | Banco delle interruzioni (autonoma): `s25.ps1 -Mode faces` (≥ 100) e `tools/chaos/faces-pc` (400) | invarianti del §5.5 |
| F104 | Prestazioni (autonoma sull'S25; sul Tab S9 nel momento breve): 500 immagini di prova, in carica e a batteria | YuNet e SFace p50/p95, foto al secondo, calo col caldo, PSS, stima della pagina entro ±30%; Libreria fluida durante il calcolo |
| F105 | **Qualità sui dati veri** (utente, ~40 minuti, §5.3) | purezza ≥ 97%; numeri in `docs/decisioni.md`; nessun dato fuori dal telefono |
| F106 | Privacy fra account (autonoma dal PC con `test2`; sul tablet nel momento breve) | `test2` non legge `test/.mycloudgallery/faces/` (accesso negato); il profilo `test2` del tablet non ha Persone attiva né dati; nessun file dei volti in `test-comune` |
| F107 | Diagnostica e log (autonoma): esportazione della Diagnostica e `logcat` di una giornata con nomi seminati riconoscibili | nessun nome, id, chiave, riquadro, vettore o conteggio per persona (controllo automatico) |
| F108 | Modelli alterati (autonoma, debug, `run-as`): un byte cambiato nella copia estratta | rifiutato al caricamento, riestratto dall'APK; `FACES_MODEL_MISMATCH` |
| F109 | Ricetta nuova simulata (autonoma, debug: ricetta 2 forzata) | ricalcolo graduale; id, nomi e correzioni ereditati; nessun confronto fra spazi |
| F110 | (con il consenso, Q5) APK di release: sincronizzazione delle persone sulle share vere | un nome dato sull'S25 compare sul profilo dell'utente del tablet; dal PC nulla fuori da `.mycloudgallery/faces/` dell'area privata; "Disattiva e cancella" verificata anche lì se l'utente la vuole provare |

**I "Fatto quando" del piano, prova per prova**
| Sottofase | Fatto quando (piano) | Dove si prova |
|---|---|---|
| 8.1 | Sul set di famiglia almeno il 97% dei gruppi non contiene persone sbagliate | P5 (sintetico), P11, F105 |
| 8.1 | "Disattiva e cancella" non lascia traccia (verificato sul NAS e sul dispositivo) | P8, P12, F100, F101, F102, F103 |
| 8.2 | Nomi personali sincronizzati; unisci, dividi, correggi, nascondi; pagina persona; ricerca per persona; persone negli album smart (E-F1–E-F6) | P9, P10, F96, F97, F98 |

### 5.7 Verifica funzionale di fine fase
Un agente di verifica funzionale (come `docs/review/funzionale-6.md`), in parallelo alla `security-review`, rilegge i percorsi interi contro
questo documento e scrive `docs/review/funzionale-8.md` con voci "percorso, atteso, trovato, stato":
- attivazione (pagina, requisiti, generazione, avviso, esclusione, testo nuovo, voce sconosciuta, recinzione, senza ora del NAS);
- modelli (estrazione, verifica a ogni caricamento, rimozione con l'esclusione e la cancellazione);
- calcolo (unità sotto la generazione, ingressi, seconda passata, video, esclusi, nascosti, buffer, ripresa, pubblicazione solo fresca);
- raggruppamento (assegnazione, nascite, notte, vincoli, custode, istantanee, riallineamento, ricalcolo, alias);
- persone (ogni azione, sincronizzazione, Q2, pagine, info, copertine mai su disco);
- ricerca e album smart (dizionario, ambiguità, candidati, cronologia, regola, dispositivo senza volti);
- "Disattiva e cancella" (ogni passo, dispositivi spenti, fuori casa, congelamento, generazioni non correnti, revocati, cestino di rete);
- Nascosti su ogni superficie registrata; azzeramento; Diagnostica ed esportazione.
Le voci confermate si correggono prima della chiusura.

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 8
| Minaccia | Contromisura | Prova |
|---|---|---|
| **Dati biometrici che escono dalla persona che li ha attivati** (verso la famiglia, l'indice comune, Internet) | dati solo nell'area privata dell'account e sui suoi dispositivi; mai nell'indice né nell'area comune, anche per le foto comuni; letti solo dai dispositivi dell'account; nessuna rete nei moduli dei volti; nessun host nuovo (D11, D12, D39) | P0 (test d'architettura), P3 (proprietà: mai nell'area comune), F94, F106 |
| **Un altro familiare** (o un suo dispositivo) che vuole i miei gruppi e nomi | permessi SMB dell'area privata; le teste dei volti di un altro account non si leggono nemmeno se finissero nella mia area (firmatario di un altro `nasUser` = rifiutato) | P3, F106 |
| **Un familiare che vuole sapere chi ho riconosciuto nelle foto comuni** | i dati dei volti delle foto comuni stanno nella mia area privata; nessuna traccia nell'area comune, nemmeno l'attivazione (Q3) | P3, F94, F106 |
| **Un dispositivo dell'account compromesso** | firma e registro: un dispositivo revocato è ignorato e la sua cartella si toglie (D31); può scrivere gruppi o nomi falsi e far cancellare i dati dei volti dell'account, **mai media** (L93) | P3, P8 |
| **Un dispositivo perso** con i dati dei volti | dati cifrati sul dispositivo con una chiave del Keystore (D14); l'azzeramento alla riconnessione distrugge la chiave nella fase (3); se non torna, restano cifrati dietro il blocco schermo (L89) | P6, P13, F100 |
| **La cancellazione che lascia tracce** (file non tolti, pagine libere di SQLite, WAL, flash, teste vecchie, dispositivi spenti, nomi nelle ricerche recenti, cestino del NAS) | chiave distrutta **prima** dei file; database in un file a parte; cartella intera della generazione sul NAS per tutti i dispositivi dell'account; nomi nella cartella dei volti e non nei dati personali; un dispositivo dichiara la cancellazione solo dopo averla fatta; generazioni non correnti mai lette e tolte; cronologia ripulita; controllo del cestino di rete (D13, D14, D29–D31) | P8, P12, F100–F103 |
| **L'esportazione della Diagnostica o i log** | solo numeri e codici; elenco chiuso delle chiavi dell'esportazione; test con nomi e vettori seminati (D37) | P13, F107 |
| **Dati falsi o ostili nei file dei volti** (vettori non finiti, riquadri fuori, testi con caratteri di controllo, campi sconosciuti, generazioni che non tornano) | codec ostile con tetti, rifiuto del segmento o della testa, contatori (§2.4) | P3 |
| **Un dato remoto che fa cancellare** (finta voce `faces = off`, finta revoca) | solo dati dei volti dell'account, solo sotto `faces/`, eseguito da `core-nasops` con percorsi costruiti dalla regola; nessun media, mai (D31, formato §9 punto 6-bis) | P8 (proprietà sui percorsi) |
| **Modelli manomessi** (asset, copia estratta) | impronta fissata nell'app, verifica a ogni caricamento, file in sola lettura (F6-D16, revisione B I1) | P1, P2, F108 |
| **Byte ostili ai decodificatori** (anteprime dal NAS) | ingressi preparati da noi: `ImageHeaders`, `ImageBounds`, `ImageDecoder`, mai byte remoti all'interprete (F6-D5) | P2 |
| **Terzi e bambini raggruppati senza saperlo** | uso domestico, attivazione esplicita, nessuna condivisione, nessun attributo, strumenti "Nascondi", "Dimentica", "Ignora", Q2 su cosa si vede (D32, D33) | P7, P9 |
| **Nascosti che trapelano dalle persone** (copertine, conteggi, info, ricerca, album) | una sola fonte, registro delle superfici, test di proprietà (D28) | P9, P10, F99 |
| **Riconoscimento usato per cercare chiunque** | si cerca per persone con un nome; nessuna ricerca libera "trova questo volto" (D26) | P10 |
| **Prima scrittura di dati biometrici sulle share vere** | recinzione `facesSyncTestSharesOnly` fino al sì (D34, Q5) | P0, P13, F110 |

**Contromisure nuove** (proposta per `sicurezza.md` §4)
- **C33. Dati dei volti solo dell'account che li attiva**: cartella per generazione nell'area privata, firmata per dispositivo, letta solo
  dai dispositivi dello stesso account; mai nell'indice condiviso né nell'area comune, anche per le foto comuni (D11–D13).
- **C34. "Disattiva e cancella" completo e verificato**: chiave locale distrutta per prima, cartella intera sul NAS per tutti i dispositivi
  dell'account, nomi dentro la cartella, dichiarazione solo dopo la cancellazione, generazioni non correnti mai lette e tolte, stato per
  dispositivo (D29, D30).
- **C35. Cifratura locale con cancellazione crittografica**: vettori, centroidi e nomi cifrati con una chiave dati avvolta da una chiave del
  Keystore per generazione (D14).
- **C36. Minimizzazione dei dati biometrici**: nessuna stima di attributi, nessun ritaglio salvato, screenshot e documenti esclusi,
  "Dimentica" senza impronte, niente nei log, nella Diagnostica o nell'esportazione (D18, D19, D24, D25, D37).
- **C37. Consenso per account con avviso per dispositivo**: pagina versionata, nessun dato trattato su un dispositivo prima dell'avviso,
  esclusione locale, recinzione delle share vere (D3, D4, D34).
- **C20 riscritta**: "Volti: attivazione per account con avviso ed esclusione (C37), dati solo dell'account (C33), «Disattiva e cancella»
  verificato (C34), cifratura sul dispositivo con distruzione della chiave (C35; sul NAS no: L88), nessuna inferenza di attributi e nessun
  ritaglio salvato (C36), nessun dato esportato."

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L88. I dati dei volti sul NAS non sono cifrati**: chi ha la password dell'account (o l'admin del NAS, o i dischi) legge gruppi e nomi,
  come legge le foto da cui si ricalcolerebbero con modelli pubblici (come L26, L29).
- **L89. Un dispositivo che non si ricollega tiene i suoi dati dei volti**: cifrati con una chiave del Keystore, dietro il blocco schermo;
  si cancellano quando torna (o con l'azzeramento di un dispositivo perso). Se nessun dispositivo dell'account torna a casa dopo una
  cancellazione fatta fuori, i file restano sul NAS finché non si tolgono dal PC (`.mycloudgallery/faces/` dell'area privata).
- **L90. Il cestino di rete del NAS**: se è acceso nell'area privata, i file cancellati ci restano; l'app lo segnala se vede la cartella
  del cestino, lo svuota l'utente dal pannello. Oggi le aree private hanno il cestino spento.
- **L91. I raggruppamenti sbagliano**: "almeno 97 gruppi su 100 senza persone sbagliate" sulle foto misurate, non certo; gemelli, bambini
  negli anni, profili, occhiali da sole e volti piccoli possono finire insieme o divisi; le correzioni restano.
- **L92. I modelli sono addestrati su dataset di ricerca** (CASIA-WebFace, VGGFace2, MS-Celeb-1M, ritirato nel 2019): rischio trascurabile
  in famiglia, da rivalutare prima di pubblicare l'app (estende L14).
- **L93. Un dispositivo compromesso dello stesso account** può scrivere gruppi e nomi falsi e far cancellare i dati dei volti dell'account
  (ricalcolabili); non può leggere quelli di altri account né toccare media; la revoca lo toglie.
- **L94. Il riconoscimento lavora anche sui volti di amici e bambini** nelle foto dell'utente: i dati restano suoi e non escono, ma quelle
  persone non hanno scelto; l'app non stima l'età e non sa chi è un minore.
- **L95. Su un dispositivo escluso o con Persone spenta** non ci sono ricerca per persona né regole con le persone negli album smart.
- **L96. Con la recinzione accesa** (share vere) le persone sono per dispositivo: nomi e correzioni non viaggiano e ogni indicizzatore
  calcola per sé.
- **L97. Resti fisici**: blocchi dei dischi del NAS e della flash del telefono non si sovrascrivono; sul telefono la chiave distrutta li rende
  illeggibili, sul NAS no (L88).

### 6.3 Checklist della Fase 8 (sostituisce le tre voci di `sicurezza.md` §7 "Fase 8")
- [ ] Attivazione per account dopo la pagina versionata; nessun dato trattato su un dispositivo prima dell'avviso; "Non su questo
  dispositivo" cancella in locale (F94, F95).
- [ ] Dati dei volti solo nell'area privata dell'account, mai nell'indice né nell'area comune, letti solo dai dispositivi dell'account (P3,
  F106).
- [ ] "Disattiva e cancella" senza tracce su NAS e dispositivi, anche con un dispositivo spento che torna (F100–F103); chiave distrutta
  prima dei file; cronologia ripulita.
- [ ] Nessuna cancellazione guidata da un dato remoto fuori da `faces/`; mai un media (proprietà di P8).
- [ ] Nessuna inferenza di età, genere, etnia, emozioni o accessori; nessun ritaglio di volti salvato; screenshot e documenti esclusi.
- [ ] Vettori, centroidi e nomi cifrati sul dispositivo (test sui byte del database); sul NAS il limite L88 dichiarato.
- [ ] Nascosti mai nelle superfici delle persone (F99).
- [ ] Log, Diagnostica ed esportazione senza nomi, id, chiavi, riquadri, vettori (F107).
- [ ] Licenze e provenienza dei modelli documentate nell'APK; nota GDPR riletta e rivalutata se l'app sarà pubblicata (L14, L92).

### 6.4 Permessi, componenti, dipendenze e host (proposta per `sicurezza.md` §4-bis e §8)
| Voce | Cosa cambia | Protezione | Perché |
|---|---|---|---|
| Permessi Android nuovi | **nessuno** | | |
| Componenti esportati o servizi nuovi | **nessuno** (lavori di WorkManager come gli altri) | `verifyReleaseManifest` | |
| Dipendenze nuove | **nessuna**: `litert` 2.3.0 c'è già (F6-D44) | checksum invariati | motori dei volti |
| Asset nuovi | YuNet (`.tflite`, MIT) e SFace (`.tflite`, Apache-2.0), ~20 MB, con licenze e `NOTICE` | impronte nel catalogo, verifica a ogni caricamento, `check.ps1` le confronta nell'APK | Q1 (a) |
| Host nuovi | **nessuno** con Q1 (a); con Q1 (b) nessuno se la copia sta su Hugging Face (host già ammesso) | | |
| Chiavi del Keystore nuove | `mcg-faces-<G>` (AES, senza sblocco, StrongBox se c'è) | distrutte con la cancellazione, l'esclusione e l'azzeramento | D14 |

### 6.5 GDPR in pratica (D33; non è consulenza legale)
- **Cosa sono**: gli embedding dei volti usati per raggruppare sono con ogni probabilità dati biometrici (art. 4.14) di categoria
  particolare (art. 9); la foto in sé no (considerando 51).
- **Perché qui il regolamento non si applica**: trattamento di una persona fisica per attività esclusivamente domestiche (art. 2 par. 2
  lett. c, considerando 18), sui dispositivi e sul NAS di casa, senza nessun dato allo sviluppatore.
- **Cosa facciamo comunque**: consenso esplicito e informato per account (D3), nessuna condivisione con altri (nemmeno in famiglia, Q3),
  minimizzazione (C36), cancellazione completa e verificata (C34), nessuna esportazione, nessuna telemetria, dati di terzi trattati come i
  propri e con gli strumenti per escluderli (D32).
- **Se l'app venisse pubblicata**: chi "fornisce i mezzi" resta soggetto al regolamento (considerando 18): servirebbero una valutazione
  legale, una valutazione d'impatto e una nuova lettura dei termini dei dataset di addestramento (L14, L92).

## 7. Rischi e piani B

| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| La conversione ONNX → LiteRT cambia i numeri (operatori, trasposizioni, float16) | volti mancati, vettori diversi | parità sul PC prima di consegnare (P1) e sui dispositivi (P2) con soglie fissate prima | conversione con un altro strumento (`ai-edge-torch` partendo dai pesi, se disponibili) o pesi float32 (38,7 MB); ultima risorsa ONNX Runtime (dipendenza nuova, da approvare) |
| SFace sotto il 97% sui dati veri | obiettivo 8.1 mancato | livelli di qualità, margine, vincoli, soglie severe, correzioni | AuraFace-v1 (Apache-2.0, 261 MB, da Hugging Face; più lento: [stima] 80–200 ms a volto, una notte), poi dlib (CC0); la scelta torna all'utente se cambiano peso o host (Q1) |
| Soglie che reggono la purezza ma lasciano tanti volti non assegnati | persone frammentate, molte proposte | proposte "È la stessa persona?", "Altri volti", unione a mano | soglie per fasce di qualità; seconda passata più larga |
| Bambini che crescono, gemelli | gruppi divisi o misti | "mai insieme in una foto", margine, proposte, "Dividi" con `apart` | — (dichiarato, L91) |
| Volti piccoli nelle foto di gruppo | volti persi | seconda passata sull'HQ a tasselli | originale del telefono per i media che ci sono; tasselli più piccoli |
| Prestazioni del Tab S9 | calcolo lento sul tablet | il tablet di solito non è indicizzatore (F6-Q3); misure in P2 | YuNet a 640 sulla prima passata con la seconda più frequente |
| Due custodi o un custode che sparisce | id che divergono o raggruppamento fermo | prenotazione, istantanee numerate, riallineamento, passaggio dopo un'ora | ricalcolo completo con abbinamento (D22) |
| Un dispositivo spento non torna mai | dati locali che restano | cifratura con la chiave del Keystore, azzeramento del dispositivo perso, pagina che lo dice | — (L89) |
| Cestino di rete acceso nell'area privata | file cancellati che restano nel cestino del NAS | controllo e avviso; oggi è spento | guida "svuota il cestino" nella pagina |
| La voce `faces` non viaggia sulle share vere (F4-D31 ancora acceso) | Persone per dispositivo sulle share vere | recinzione dichiarata (D34) | Q5: insieme alla pubblicazione dell'indice |
| P6 e P13 della Fase 7 in ritardo | album smart con persone fermi | P10 lavora sull'esecutore di riferimento | consegnare la regola con la chiusura della Fase 7 |
| Il registro dei dati personali si salva per intero a ogni voce (P3-14 della Fase 7) | lentezza se i nomi stessero lì | i nomi non stanno lì (D13); le voci `people` hanno il loro archivio | — |
| Ritratti di pubblico dominio poco realistici per i motori | prove sul campo meno significative | composizioni e più ritratti per persona; la qualità vera si misura in F105 | qualche foto non personale scattata dall'utente in casa (con il suo consenso esplicito, solo sul telefono di prova) |
| Il brief dei pacchetti cresce (calcolo, raggruppamento, cancellazione in parallelo) | conflitti fra pacchetti | perimetri esclusivi (§1.5, §4.4), contratti da P0 | l'orchestratore serializza P4, P5 e P8 su `core-faces` |
| Crediti | costo alto | opus su 4 pacchetti su 14 | P3 sonnet con una revisione opus del codec |

### File cruciali
- `core-personal/.../contract/Personal.kt` (`PersonalKind.PERSON`, `PersonalFields`, `PersonalValidity`, `PersonalMerge`),
  `codec/PersonalCodec.kt` (tipi riconosciuti per impronta, tetti delle voci), `sync/NasPersonalSync.kt` (giro, pubblicazione: il gancio),
  `settings/SyncedSearchSettings.kt` (impostazioni che valgono subito), `contract/PersonalLimits.kt`.
- `core-trust/.../envelope/Envelope.kt` (`DocType`), `TrustFormat.kt` (versioni legate al tipo), `data/IndexSegment.kt` (`SegmentClass`,
  `RESERVED_WIRE`), `data/DeviceHead.kt` (`HeadVerifier`, `HeadMark`), `data/Leases.kt` (`LeaseManager`), `data/OwnFiles.kt`
  (`OwnFilesPolicy`), `nas/NasLayout.kt`, `registry/FamilyRegistry.kt` (`nasUser`, `status`, `frozenHeads`), `codec/TextRules.kt`.
- `core-ai/.../contract/TextReading.kt` (modello del consenso), `cutout/LiteRtSessions.kt` (LiteRT su un descrittore verificato),
  `runtime/ModelInputs.kt` e `runtime/ImageHeaders.kt` (ingressi difesi), `compute/ComputeSession.kt` (`pruneFinished`),
  `compute/DiskAiBuffer.kt`, `compute/AiComputeEngine.kt`.
- `core-models/.../contract/ModelCatalog.kt` (`ModelSpec.bundledAsset`, `needed`), `source/BundledModelFetcher.kt`, `verify/LocalModelFiles.kt`.
- `core-index/.../contract/Work.kt`, `work/LeasedWorkUnits.kt`, `ai/IndexAnalysisFetcher.kt`.
- `core-search/.../contract/Query.kt` (`QueryParser`, `SearchChip`), `contract/Search.kt` (`HistoryEntry`), `parse/ItalianQueryParser.kt`.
- `core-albums/.../contract/Smart.kt` (`SmartRules`), `personal/SmartRulesText.kt`; il motore smart di P6 della Fase 7.
- `core-database/.../GalleryDatabase.kt`, `entity/AlbumV6Entities.kt` (`album_rule`); `core-security/.../credentials/AndroidSecretKeyStore.kt`.
- `feature-library/.../collections/CollectionSections.kt`, `collections/testing/CollectionsSectionSamples.kt`; `feature-viewer/.../info/InfoBuilder.kt`.
- `app/.../graph/LocalData.kt` (`AppLocalDataEraser`), `app/.../ai/` (`AiGraph`, `TextReading.kt`, `PersonalLocalSettings.kt`),
  `app/src/test/.../hidden/HiddenSurfaces.kt`; `docs/famiglia-dispositivi.md` §3.

## 8. Cose che richiedono l'utente

| Cosa | Perché | Nel frattempo |
|---|---|---|
| Rispondere a Q1–Q5 | cambiano P1, P9, la pagina Famiglia, P11 e la chiusura | valgono le raccomandazioni; P0 parte |
| **Approvare gli scaricamenti** (sotto) | servono a P1 (conversione) e alle prove sul dispositivo | contratti, fake e identità sintetiche |
| La valutazione sui dati veri (F105, ~40 minuti in due momenti, Q4) | la misura che conta per 8.1 | identità sintetiche e ritratti di prova |
| Il momento breve con il tablet (~15 minuti, insieme a quelli in sospeso delle Fasi 6 e 7) | misure del Tab S9 (F104), profilo `test2` (F106) | prove sull'S25 e sull'emulatore |
| Installare PCAPdroid sull'S25 (se non c'è già, serve anche alle Fasi 6 e 7) | F94: nessun host contattato | il test d'architettura sulla rete |
| Revisione degli screenshot (sezione Persone, pagina persona, revisione dei volti, info, attivazione, avviso, "Disattiva e cancella", chip, editor) | gate grafico | generati in anticipo |
| Consenso alla sincronizzazione sulle share vere (Q5) | F110 | recinzione accesa |

**Scaricamenti da approvare** (tutti sul PC, nessuno sul telefono; niente è stato scaricato per questo documento)
| File | Fonte | Dimensione | A cosa serve |
|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | `github.com/opencv/opencv_zoo`, `models/face_detection_yunet/` (Git LFS, `media.githubusercontent.com`) | 232.589 B (SHA-256 `8f2383e4…`) | conversione e parità (P1) |
| `face_detection_yunet_2026may.onnx` | stessa cartella | 229.738 B (SHA-256 `ebafce4e…`) | candidato della scelta di D7 |
| `face_recognition_sface_2021dec.onnx` (fp32) | `models/face_recognition_sface/` | 38,70 MB (SHA-256 `0ba9fbfa…`) | conversione (P1) |
| `face_recognition_sface_2021dec_int8.onnx` | stessa cartella | 9,90 MB (SHA-256 `2b0e941e…`) | solo riferimento della parità int8 (facoltativo) |
| Strumenti di conversione (Python, versioni fissate con le impronte): `onnx2tf` (MIT), `tensorflow` CPU (Apache-2.0), `onnx`, `onnxruntime` (MIT), `numpy` | PyPI (`pypi.org`, `files.pythonhosted.org`) | circa 1 GB in tutto [stima] | P1; restano fuori dal repository, in `%USERPROFILE%\.mycloudgallery\tools\faces\` |
| ~30 ritratti di pubblico dominio (dipinti antichi, PD-Art), ridotti a 2048 px | Wikimedia Commons (`commons.wikimedia.org`, `upload.wikimedia.org`) | ~10 MB | prove sul dispositivo, parità e prove sul campo (§5.2) |
| (solo se servisse il piano B) `glintr100.onnx` di AuraFace-v1 | Hugging Face, `fal/AuraFace-v1` | 260,7 MB | piano B dell'embedder (§7), con un consenso nuovo |

Gli ONNX stanno fuori dal repository (`%USERPROFILE%\.mycloudgallery\models\faces\`, con un `LEGGIMI.txt`, come i modelli della Fase 6); nel
repository entrano solo i due `.tflite` scelti (asset, Q1 (a)) con le licenze, e l'elenco dei ritratti con le loro fonti.

**Misure da fare dopo il consenso** (P1, P2): parità ONNX contro LiteRT sul PC; scelta di YuNet e della precisione di SFace con le regole di
D7; dimensione dell'APK; sul dispositivo (emulatore, S25, Tab S9) latenze, memoria e calo col caldo; poi, con l'utente, la qualità sui dati
veri (F105).

## 9. Domande per l'utente

Poche, solo quelle che decidono cosa vede la famiglia, cosa resta privato e cosa entra nell'app. Se non rispondi, vale la
raccomandazione.

| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | Per riconoscere i volti servono due piccoli file (circa 20 MB in tutto, gratuiti, licenze libere). Dove li teniamo? | (a) Dentro l'app: l'app cresce di circa 20 MB per tutti, ma funziona subito, anche senza Internet. (b) Scaricati solo quando qualcuno attiva Persone: l'app resta più leggera, ma devi pubblicare tu i due file sul tuo account di Hugging Face (il sito da cui scarichiamo già i modelli della ricerca). | **(a)**: come il ritaglio del soggetto; niente da pubblicare, nessun sito in più. |
| Q2 | Nelle foto ci sono anche bambini e amici, non solo la famiglia. L'app non indovina l'età di nessuno. Cosa mostriamo in Persone? | (a) Tutti i gruppi trovati, con e senza nome; puoi nascondere o far dimenticare chiunque. (b) Solo le persone a cui dai un nome; gli altri gruppi stanno in "Altri volti", a un tocco, dove puoi dargli un nome o dimenticarli. | **(b)**: in primo piano solo chi hai riconosciuto tu; nessuno sparisce, ma i volti di estranei e bambini non sono la prima cosa che vedi. |
| Q3 | Gli altri familiari devono sapere che hai attivato Persone? I tuoi gruppi e nomi restano comunque solo tuoi. | (a) No: è una scelta tua e nessuno lo vede. (b) Sì: nella pagina Famiglia compare "Persone attiva" accanto al tuo nome. | **(a)**: i dati sono tuoi, e anche la scelta di usarli. |
| Q4 | Per sapere se i gruppi sono giusti (l'obiettivo è almeno 97 gruppi su 100 senza persone sbagliate) servono circa 40 minuti tuoi, in due momenti, sul telefono con le tue foto. Tutto resta sul telefono. Lo facciamo? | (a) Sì, quando vuoi, dopo la prima tappa. (b) No: solo prove con immagini pubbliche, che dicono poco sulla qualità vera. | **(a)**: è l'unico modo di sapere se il 97% è vero sulle tue foto. |
| Q5 | Oggi, sulle cartelle vere del NAS, le persone resterebbero su ogni dispositivo: nomi e correzioni non passano dal telefono al tablet. Quando accendiamo la sincronizzazione? | (a) A fine Fase 8, dopo le prove, con il tuo sì e insieme alla pubblicazione dell'indice sulle cartelle vere (già decisa per la fine della Fase 6). (b) Mai: ogni dispositivo tiene le sue persone e le calcola da sé. | **(a)**: i dati restano nella tua area privata; con la sincronizzazione il tablet vede le stesse persone senza ricalcolare. |

## 10. Fonti

**Nessuna fonte nuova consultata per questo documento** (niente rete, niente scaricamenti, per il brief). Dati e licenze vengono da:
- `docs/research/ocr-volti.md` (ricerca 0.4 del 12/10/2026, fonti verificate allora): OpenCV Zoo
  (<https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet>,
  <https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface>) per file, dimensioni, impronte Git LFS e licenze di YuNet
  e SFace; l'articolo di SFace (<https://arxiv.org/abs/2205.12010>) per i dataset di addestramento; le schede di AuraFace-v1, MobileFaceNet,
  dlib, EdgeFace, InsightFace e GhostFaceNets per il piano B e le esclusioni; Immich (<https://docs.immich.app/features/facial-recognition>)
  e Apple (<https://machinelearning.apple.com/research/recognizing-people-photos>) per il raggruppamento; GDPR art. 2, 4, 9 e considerando
  18 e 51 (<https://gdpr-info.eu/>).
- `docs/decisioni.md` (§AI; Fase 6 P0, P3, P4, P13 e revisione B: LiteRT 2.3.0 con `Interpreter`, verifica dei modelli, scaffale, misure
  dell'S25), `docs/inventario-nas.md` (cestino delle share), `docs/sicurezza.md`, `docs/formato-nas.md`, `docs/fase6-progetto.md`,
  `docs/fase7-progetto.md`, `docs/famiglia-dispositivi.md`.
- Il codice del commit `e2d77ca` (elenco nell'intestazione).

Da verificare in P1 sulle fonti, prima di usarli: il nome esatto del file int8 di SFace, il preprocessing di `FaceDetectorYN` e
`FaceRecognizerSF` (ordine dei colori, scala), la forma delle uscite di YuNet `2026may`, la licenza di ogni ritratto di pubblico dominio scelto.

<!-- fine del documento -->
