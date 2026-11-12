# Fase 10 di MyCloud Gallery: progetto tecnico (widget, tablet, accessibilità, revisione finale di sicurezza, rilascio)

> Base per i brief dei sub-agent. Progetto del 31/10/2026. Fonti:
> - piano: FASE 10 (10.1–10.6), §10 "Verifica complessiva" (gli otto scenari reali e "finito"), §2 (tablet condiviso con profili,
>   Fluid Engine, "il widget alla fine"), §3 (proposte **3**: le regole valgono solo per il lavoro in background; **9**: nessuna
>   telemetria; **12**: notifiche), §4 (fluidità, consumi, grafica, **accessibilità**, lingua), §6 (E-P18, E-P19, E-P20, E-H1, E-S7,
>   E-S10, E-X1, E-X2, E-X5, E-X6, E-X7, E-O7, E-O8, E-P7, E-P8, E-P10, E-P13), §7 (beni, attaccanti, contromisure 6 e 9, limiti, verifiche
>   per fase);
> - i progetti delle fasi precedenti: `docs/fase9-progetto.md` (**il modello di questo documento**; D38 e §2.13 "Per la Fase 10"),
>   `docs/fase8-progetto.md` (§2.11: riga Persone del viewer per TalkBack, copertine al momento per il widget), `docs/fase7-progetto.md` (D22,
>   `WidgetFeed`, widget di prova, L65; §2.9: trascinamento sul tablet, "Aggiungi ad album" da più app), `docs/fase6-progetto.md` (**D19**
>   e §2.1: il manifest firmato che verrà; Q2), `docs/fase5-progetto.md` (§2.7, `MediaDocumentsProvider` per il trascinamento),
>   `docs/fase4-progetto.md` (D31, prova 10.4 con `test2`);
> - `docs/decisioni.md` (Fase 7 P9 e P13, Fase 9 P0–P13, "Revisione grafica della Libreria", "Ambiente di prova sul NAS"),
>   `docs/sicurezza.md` (§4 C2, C9, C11, C14–C17, C21, C22, C27; §4-bis; §6 L65; §7 "Per ogni fase", "Fase 9", "Fase 10"; §8),
>   `docs/android.md` (Tema 1 bucket e widget, Tema 5, Tema 6 profili, "Novità di Android 17"), `docs/grafica-progetto.md` (§12.11–§12.15),
>   `docs/prove-campo.md`, `docs/prove-campo-esiti.md`, `docs/bench/` e `benchmark/soglie.json`, le revisioni in `docs/review/` (compresi
>   `9-fase9.md` e `funzionale-9.md`);
> - le regole del Fluid Engine **lette nella cartella `engine/`** (`docs/04-config-remota.md`, `05-widget.md`, `06-limiti.md`,
>   `02-aggiornamento.md`, `versions.gradle`, `engine-widget/build.gradle`), versione 2.13.1;
> - il **codice** da cui la fase parte, verificato il 31/10 sul commit `70e1d615` (branch `fase10/progetto`): elenco al §0 e al §7.
>
> Sigle: `Dn` = decisione di questo documento (§0.1); `F9-Dn`, `F8-Dn`, `F7-Dn`, `F6-Dn`, `F5-Dn`, `F4-Dn` = decisioni delle fasi
> precedenti; `Fn` = prova sul campo (la Fase 9 e la sua verifica funzionale arrivano a F153; qui **F160–F189**); `Qn` = domanda del §9;
> `Pn` = pacchetto del §4.4; `Ln` = limite di `docs/sicurezza.md` (qui **L540–L569**); `Cn` = contromisura (qui **C430–C459**); i numeri
> sotto sono già presi o riservati ad altri lavori in corso. "[stima]" = numero non misurato; "[da verificare]" = fatto che questo documento
> non ha potuto controllare (niente rete né dispositivi per il brief) e che il pacchetto indicato controlla prima di usarlo.
>
> Contesto:
> - **Le Fasi 1–8 sono chiuse nel codice.** **Della Fase 9 sono in `main` P0–P4, P6–P11 e P13**; sono in corso **P12** (benchmark
>   `ricordi` e `mappa`) e due pacchetti di correzione dopo le revisioni, **"mappa e rete"** (R1, R2, R5, I1, I3 di `9-fase9.md`) e
>   **"ricordi e notifiche"** (R3, I4, NT-1, RP-1, RP-2, MO-1…MO-3, MU-1…MU-3 di `funzionale-9.md`). **Le regioni offline (P5) aspettano una
>   decisione dell'utente**: le condizioni d'uso di OpenFreeMap limitano ogni scaricamento offline a 3.000 tessere o 50 MB.
> - **Le prove sul telefono sono indietro**: F46–F153 in gran parte da fare. Il telefono è disponibile a finestre, spesso di notte. Le prove
>   girano **in autonomia** (gli agenti confermano i dialoghi della nostra app, usano Galleria Samsung e Archivio solo sui file di prova, non
>   chiedono interventi passo passo). **La Fase 10 non le aspetta**; le sue prove si raccolgono con quelle in sospeso e le parti con il tablet
>   stanno in **un solo momento breve**.
> - **Le recinzioni sono ancora accese**, anche in release, finché l'utente non dà il consenso: **F4-D31** (indice e dati personali "solo
>   locali" sulle share vere, `indexPublishTestSharesOnly`), **F5-D12** (backup solo sulle share di prova), **F7-D30** (album condivisi solo
>   fra le share di prova), **F8-D34** (dati dei volti solo sulle share di prova). Spegnerle fa parte della preparazione al rilascio (§2.10).
> - **La grafica si rifinisce in una fase dedicata finale** (filone grafico, G3 e oltre): la Fase 10 consegna stati, contratti e garanzie di
>   accessibilità (§1.5), non l'aspetto finale.
> - **Pampa Store**: si pubblica solo alla fine di **tutte** le fasi. La Fase 10 prepara la catena e la prova a secco; la pubblicazione la
>   avvia l'utente (Q7).

## 0. Sintesi

**Che cosa consegna la Fase 10**
- **Un widget** (10.1) "MyCloud Gallery" in tre misure: il **ricordo di oggi** se c'è, altrimenti **foto a caso** della Libreria (Q1);
  stile Fluid con il kit Glance dell'engine (`engine-widget`); **mai un nascosto, nemmeno per un istante** (ridisegno immediato a ogni
  cambio dei nascosti, anche da un altro dispositivo); aggiornamento parsimonioso (poche volte al giorno, Q2), **nessuna rete e nessun NAS**:
  solo miniature già sul telefono; con l'app bloccata mostra soltanto ciò che il telefono mostra già (Q3); mai sulla schermata di blocco
  (Q4); il tocco apre l'app dietro il blocco (D3–D11).
- **Il tablet** (10.2): più pannelli dove lo spazio c'è (Libreria e viewer affiancati a scelta, mappa e griglia della zona, Raccolte,
  Ricordi e Impostazioni a elenco e dettaglio); **rotazione e ridimensionamento senza perdere lo stato**; **DeX e finestre libere** con mouse e
  tastiera; **fino a due finestre** dell'app sugli stessi dati, con il blocco dell'app che vale per il processo (E-P18); **trascinamento**
  di foto e video verso le altre app (anche i media solo sul NAS, letti a richiesta o copiati in un temporaneo che si pulisce) e dalle altre
  app verso la Libreria e gli album, sempre con una conferma (D12–D21).
- **Accessibilità** (10.3): **descrizioni dei media** composte sul dispositivo da tipo, data, ora, luogo, persone (solo se Persone è attiva e
  solo dentro l'app) e testo letto (solo se la lettura è attiva), mai salvate, mai nei log, mai sulla Home; **TalkBack completo** su ogni
  schermata (azioni personalizzate, intestazioni, ordine, annunci); testo fino al 200%; contrasto AA anche sul vetro, con superfici piene
  quando il sistema chiede più contrasto o meno trasparenza; bersagli da 48 dp; movimento ridotto; solo gli strumenti di accessibilità veri
  leggono i contenuti (D22–D28).
- **Revisione finale di sicurezza** (10.4): l'elenco del piano diventa **una batteria di prove pratiche ripetibili**, automatiche dove si
  può (JVM, emulatore, telefono, NAS di prova sotto `mcg-it/`), con i limiti aperti delle revisioni chiusi o dichiarati (D36, §2.11).
- **Rilascio** (10.5): **manifest firmato** (blocco di una versione, versione minima, indirizzi aggiuntivi per modelli già elencati) letto
  **solo dal NAS** (Q8 (a)), con due chiavi fissate nell'app; **firma dell'APK con `pampa.jks`** (decisione dell'utente del 31/10: il
  rischio della chiave condivisa con le altre app Pampa è un limite dichiarato, L559; la rotazione v3 resta possibile); versioni e canali
  (beta sui dispositivi dell'admin per due settimane, poi stabile per **tutta la famiglia insieme**, Q7 (a)); changelog; GitHub con 2FA;
  **checklist** e **prova a secco** della pubblicazione (D29–D34).
- **Passaggio alle share vere** (preparazione al rilascio): le quattro recinzioni si spengono **in un solo passo** (Q6 (b)), **dopo le
  prove della Fase 10**, con la "Simulazione del passo" in sola lettura sulle share vere, la fotografia delle share prima e dopo e il sì
  dell'utente, sui soli dispositivi dell'admin; ritorno indietro immediato con "Ferma le scritture sul NAS" e una build con le recinzioni
  riaccese (D35).
- **Verifica complessiva** del §10 del piano: gli otto scenari reali su S25, tablet con due profili di prova ed emulatore, in un ordine che
  chiede all'utente **circa un'ora in tre momenti** (D37, §2.14).
- **Idee per il dopo** (10.6): solo elencate; l'utente ha scelto, in quest'ordine, **esportare un album** e **un ricordo come video**
  (D43, Q10), che non si progettano ora.

**Moduli nuovi**
- `core-widget` (JVM puro): che cosa mostra ogni widget, il registro di ciò che ha mostrato, quando ridisegnare, che cosa apre un tocco.
- `feature-widget`: i widget Glance sopra `engine-widget`, il ricevitore (non esportato), il lavoro periodico e il ridisegno.
- `core-release` (JVM puro): formato, firma e verifica del manifest, stato dei blocchi (persistente e solo più severo), indirizzi aggiuntivi
  dei modelli, catalogo chiuso dei messaggi.
- `core-describe` (JVM puro): le descrizioni dei media per superficie (dentro l'app, widget, notifiche, esportazione), dai fatti che le porte
  danno.

**Aggiunte ai moduli esistenti**: `core-designsystem` (solo file nuovi in `adaptive/`: classi di finestra e pannelli), `core-security`
(blocco dell'app per processo), `core-models` (porta `ExtraModelSources`), `core-library` (contratto del trascinamento e delle descrizioni),
`feature-integrations` (`drag/`: sorgente e destinazione del trascinamento), `feature-library`, `feature-viewer`, `feature-map`,
`feature-albums`, `feature-memories`, `feature-settings`, `feature-search`, `feature-people` (pannelli, semantica, tastiera),
`feature-diagnostics` (sezioni `WIDGET` e `RELEASE`), `core-common` (`Phase10ArchitectureTest`, `DiagSection`), `:app` (manifest, finestre,
grafi, azioni del widget, effetti del manifest), `benchmark` (scenari `widget`, `pannelli`, `accessibilita`), `tools/` (`release/`,
`security/`, controlli in `check.ps1`).

**Quindici pacchetti** (§4.4). Opus per i tre fondanti o rischiosi: **P0** (fondamenta, contratti, recinti), **P1** (manifest firmato e
chiavi: l'unica cosa che da fuori cambia il comportamento delle app installate) e **P11** (revisione finale di sicurezza con le prove
pratiche); sonnet per gli altri dodici. **Nessun pacchetto usa sotto-agenti** (regola dell'utente).

**Tappe**
- **M1** (10.1): il widget sull'S25 (debug, `test`): ricordo di oggi e foto a caso, nascosti mai (anche da un altro dispositivo), tocco con
  il blocco dell'app, aggiornamenti contati in una giornata.
- **M2** (10.2): il tablet nel momento breve: due pannelli, rotazione e ridimensionamento, DeX, due finestre, trascinamento verso e da altre
  app, due profili di prova.
- **M3** (10.3): TalkBack su ogni schermata (letto da UI Automator), testo al 200%, contrasto alto, movimento ridotto; prove automatiche dei
  nodi di accessibilità verdi.
- **M4** (10.4, 10.5): batteria di sicurezza verde (JVM, emulatore, NAS di prova, S25); manifest firmato provato (valido, manomesso,
  rigiocato, blocco); release firmata con `pampa.jks` e controllata (schemi, impronta), prova a secco della pubblicazione.
- **Chiusura**: verifica complessiva del §10 (scenari 1–7), `security-review` e verifica funzionale in parallelo; poi, con il sì
  dell'utente, il passaggio alle share vere in un solo passo sui dispositivi dell'admin (§2.10, Q6 (b)), lo scenario 8 su quel passo e la
  fase grafica (Q5).

**Test senza dispositivo**: politica del widget sulla JVM con Libreria sintetica, nascosti casuali e orologio finto; manifest con chiavi di
prova, firme rotte, rigiochi e scadenze; descrizioni con fatti finti per ogni superficie; pannelli e semantica con Robolectric e Roborazzi
(larghezze 360, 600, 840, 1280 dp; testo a 1,0, 1,3 e 2,0); controlli automatici di accessibilità (ATF) sugli screenshot (§5.3).

**Cosa le fasi precedenti hanno lasciato pronto** (verificato nel codice il 31/10)
- **La sorgente del widget**: `WidgetFeed.next(size)` (al più 16) in `core-library/.../contract/Visibility.kt:53`, implementata da
  `RotatingWidgetFeed` (`core-library/.../visibility/RotatingWidgetFeed.kt`): gira sull'istantanea visibile, ricontrolla `HiddenMask` e
  `VisibleMedia` **a ogni chiamata**, fallisce chiusa (errore = lista vuota), una chiamata alla volta. Il grafo la espone come
  `gallery.widgetFeed`.
- **Il widget di prova** (solo debug, Fase 7 P9): `FieldProbeHiddenWidgetProvider` con `RemoteViews` della piattaforma, ricevitore **non
  esportato**, `goAsync`, miniature con un tetto di 4 s, ridisegno subito dopo ogni "Nascondi" fatto su questo telefono
  (`DeveloperTools.hiddenChanged`), descrizione "Foto, 12 marzo 2024" per cella; assente dal dex di release (`tools/check.ps1`); riga
  `WIDGET("Widget di prova")` del registro `HiddenSurfaces` (`app/src/test/.../hidden/HiddenSurfaces.kt:27`). Il punto aperto 3 della Fase 7
  P9 (`docs/decisioni.md`) chiede per il widget vero il ridisegno **anche all'arrivo dei dati personali**.
- **Lo scaffale dei ricordi** (Fase 9): `Memories.shelf`, `MemoryNotifyPolicy.choose` e il registro del giorno; la notifica non porta mai
  nomi, `PendingIntent` immutabile con un'azione fissa (`OPEN_MEMORIES`) riconosciuta in `MainActivity` (`MainActivity.kt:56-102`,
  `Entry.clean`, `EntryHardeningTest`).
- **Il kit Glance dell'engine**: `engine/engine-widget` (Glance **1.1.1**, `engine/versions.gradle`), con `engineWidgetPalette`,
  `resolveEngineWidgetLayout`, `EngineWidgetSurface`, `EngineWidgetHeader`, `EngineWidgetMessage`; **non è incluso nell'app**
  (`engine.properties`: `engine.modules=engine-foundation,engine-ui`) e Glance non è fra le dipendenze (`versions-app.gradle`,
  `gradle/verification-metadata.xml`: nessuna voce).
- **Tablet, già fatto**: Raccolte a elenco e dettaglio da 840 dp (`FluidListDetailScaffold` dell'engine, `CollectionsTab.kt:48`), colonna
  delle informazioni del viewer da 840 dp (`ViewerOverlay.kt:425,1160`), due pannelli in Onboarding (`OnboardingHost.kt:207`) e in Cartelle
  NAS (`NasFoldersScreen.kt:237`). **Non c'è**: una classe di finestra comune (ogni schermata misura per conto suo), mappa e griglia insieme
  (F9-D38), Libreria e viewer affiancati, Impostazioni a due pannelli.
- **Finestre**: una sola activity utente, `MainActivity` **`singleTask`** con `taskAffinity=""` (`app/src/main/AndroidManifest.xml:60-63`);
  nessun `configChanges` (rotazione e ridimensionamento ricreano l'activity), nessun `resizeableActivity` esplicito (vale il predefinito
  "sì"), nessuna dimensione minima. Il blocco dell'app **presume una sola activity** (`core-security/.../lock/AppLockGate.kt`, KDoc;
  `AppLockController.onAppBackgrounded` su `ON_STOP` della finestra). Le activity d'ingresso delle integrazioni (`PickerActivity`,
  `ShareReceiverActivity`) sono task separati, fuori dai recenti.
- **Trascinamento, i pezzi che servono**: `MediaDocumentsProvider` (esportato solo con `MANAGE_DOCUMENTS`, `grantUriPermissions`, id
  opachi, nascosti esclusi, `CallerGuard` che ammette **un permesso di lettura sul singolo URI**), il **descrittore a richiesta**
  (`ProxyDescriptors.kt`: un media solo-NAS si legge a pezzi senza scaricarlo intero), le **copie senza posizione** (`PreparedCopies.kt`,
  fallire chiusi), i tetti di E-X6, il flusso di ricezione con i tetti e il tipo dai byte (`feature-integrations/.../receive/`, C18), la
  cache `cacheDir/share/` con nomi casuali e pulizia all'avvio (`ShareCache.kt`). **Nessun codice di trascinamento** (`dragAndDropSource`,
  `startDragAndDrop`: nessuna occorrenza).
- **Accessibilità, già fatto**: descrizione della cella della griglia "Foto, 12 marzo 2024, 10:15, Oslo, preferita, solo sul NAS"
  calcolata **a richiesta** (`feature-library/.../grid/GridText.kt:156`, `GalleryGridCell.describe`); circa 700 punti di semantica nei
  `feature-*` e 149 in `core-designsystem`; leggibilità del vetro misurata (`GalleryLegibility.worstContrast`, `minimalFilmAlpha`); movimento
  ridotto in `GalleryMotion` e nei componenti della griglia e del viewer; oltre 100 file di test con testo a 1,3x o bersagli da 48 dp; Roborazzi
  1.76.0. **Non c'è**: un controllo automatico di accessibilità (nessun Accessibility Test Framework), una descrizione unica dei media per
  tutte le superfici (viewer, album, ricordi, mappa ne fanno di proprie), i nomi delle persone e il testo letto nelle descrizioni, la
  protezione dai servizi di accessibilità non "strumento" (`accessibilityDataSensitive`), le prove a testo 2,0x.
- **Modelli e indirizzi**: `ModelCatalog` con id, dimensione, SHA-256 e `PinnedUrl` fissati nell'app; `ModelHosts` ammette solo
  `huggingface.co` e i suffissi `.huggingface.co`, `.hf.co`, al più 5 redirect seguiti a mano
  (`core-models/.../contract/ModelCatalog.kt:77-90`); il commento in testa al file promette già che il manifest della Fase 10 "potrà solo
  AGGIUNGERE indirizzi per un id e un'impronta già elencati".
- **Firma della release**: `app/build.gradle:20-93`: firma con **`pampa.jks`, condivisa con le altre app Pampa**, letta da
  `local.properties` (mai nel repo); v1 e v2; senza chiave la release resta non firmata (build di `check.ps1`); `versionCode = 1`,
  `versionName = '0.1.0'`; debug con `applicationIdSuffix '.debug'`; release non debuggable, R8 attivo, solo `arm64-v8a`.
- **Recinzioni accese in release** (`core-common/.../featureflags/FeatureFlags.kt:110-145`): `indexPublishTestSharesOnly = true` (D31,
  anche "emergenza" di Diagnostica), `BACKUP_TEST_SHARES_ONLY`, `SHARED_ALBUMS_TEST_SHARES_ONLY`, `FACES_SYNC_TEST_SHARES_ONLY` costanti
  `true` "che si cambiano a mano solo con il consenso dell'utente"; `backupBlocksTestAccounts` vero in release; `TestShares.isTestShare`
  (nomi esatti). `tools/check.ps1` verifica nel bytecode della release che i cancelli D12 e D30 ci siano e chiamino la decisione.
- **Diagnostica**: `DiagSection` con 17 sezioni (fino a `TRUST` e `PROFILES`); "Ferma le scritture sul NAS" (`nas.emergency`) chiude ogni
  cancello di scrittura (`AppBackupGate`, album, modelli); esportazione a elenco chiuso; log di release spenti per costruzione
  (`GalleryLog` senza sink in release; `-assumenosideeffects` su `android.util.Log`).
- **Profili**: ogni profilo del tablet è un dispositivo a sé (C19); `DiagSection.PROFILES`; `docs/android.md` Tema 6.
- **Prove della sicurezza già scritte** (da riusare in 10.4): `ForeignSharesTest`, `LocalDataEraserTest`, `SelfWipeTest`, `NoFalseWipeTest`,
  `MassChangeGuardTest`, `ScanFreezeTest`, `TrashAndHostileDataTest`, `HostileSnapshotTest`, `RedactedTest`, i banchi del caos (`*ChaosMutationTest`),
  `ModelChaosMutationTest`, i banchi dal PC sul NAS vero dentro `mcg-it/<giro>/` (`ShareNasIntegrationTest`, `BackupAreaRunnerTest`,
  `nas-it.properties` dell'utente).

**Le promesse rimandate alla Fase 10 dalle fasi precedenti** (cercate con `grep -rn "Fase 10" docs/`, stato verificato nel codice il 31/10)
| # | Promessa | Da | Stato nel codice | Chi la chiude |
|---|---|---|---|---|
| 1 | **Manifest remoto firmato**: versione minima, blocco ("kill switch"), indirizzi aggiuntivi **solo** per modelli già elencati; "manifest manomesso = ignorato" | F6-D19 e Q2, F6 §2.1, `sicurezza.md` §7 Fase 6 e Fase 10, C22, E-S7 | **Assente**: nessun codice, nessuna chiave di pubblicazione; `engine-config` (manifest **non firmato** del Pampa Store) non è incluso e non va incluso (D29) | P1, P13 |
| 2 | **Widget vero** sopra `WidgetFeed` e il registro `HiddenSurfaces`; ridisegno anche all'arrivo dei dati personali | F7-D22, L65, Fase 7 P9 punto aperto 3, `fase7-progetto.md` §2.9 | **Assente**: solo il widget di prova (debug); riga `WIDGET` "di prova" | P2, P3, P13 |
| 3 | **Widget dei ricordi**: il ricordo di oggi, mai nascosti, mai persone con "Mostra meno" | F9-D38, F9 §2.13 | **Assente**; lo scaffale e le regole della notifica ci sono | P2, P3 |
| 4 | Widget con il **soggetto ritagliato** | F6 §2.9 (riga del ritagliatore) | Assente | **Non in questa fase** (D10): foto intere, come le copertine dei ricordi (F9 §3); idea per dopo |
| 5 | Copertine per il widget **mai con persone nascoste** | F8 §2.11 | Le persone nascoste non entrano nei ricordi (F9-D12); il widget non ritaglia volti | P2 (regola), P11 (prova) |
| 6 | **TalkBack completo**: descrizioni della presentazione e delle bolle | F9-D38, F9 §2.13 | **In parte**: bolle con descrizione (`BubbleLayer.kt:108`), presentazione con descrizioni (P9 della Fase 9); manca il controllo su ogni schermata e l'elenco navigabile delle bolle | P8 |
| 7 | **Riga "Persone" nelle descrizioni** ("Foto con Marco e Anna") | F8 §2.11 | **Assente**: `GridText.describe` non ha persone né testo letto | P7 |
| 8 | **Mappa a due pannelli** (mappa e griglia insieme) | F9-D38, `decisioni.md` "Fase 9 P4" | **Assente**: mappa e griglia sono pagine a schermo pieno | P5 |
| 9 | **Trascinamento** verso altre app (dalla griglia della zona, dal provider dei documenti) | F9 §2.13, F5 §2.7 (`MediaDocumentsProvider` "Fase 10.2"), F7 §2.9 | **Assente**; provider, descrittore a richiesta e copie senza posizione ci sono | P6 |
| 10 | "Aggiungi ad album" da più app | F7 §2.9 | **C'è** dalla condivisione in arrivo (F7 P10, `integrations_receive_album_open`); manca dal trascinamento | P6 |
| 11 | Un widget attivo tiene l'app fuori dal secchio "Restricted" | `android.md` Tema 1, 1.3 punto 6 | Vero per costruzione di Android; non è una ragione per avere il widget | — (nota in Diagnostica, P13) |
| 12 | **Prova 10.4 con `test2`**: un account non vede tramite l'indice l'area privata di un altro | F4 §5, `sicurezza.md` §7 Fase 4 | Prove JVM ci sono (regola M3, `ForeignSharesTest`); **la prova sul NAS vero non è mai stata fatta** | P11 (F170) |
| 13 | **Smart Switch** e backup di Android esclusi (T-25) | `sicurezza.md` §7 Fase 2, C16 | `data_extraction_rules.xml` c'è; **la prova non è spuntata** | P11 (F174) |
| 14 | Checklist "Fase 10" di `sicurezza.md` (cinque voci: 10.4 per intero, rilascio, manifest, widget, documento condiviso con la famiglia) | `sicurezza.md` §7 | Tutte aperte | §6.3 la riscrive |
| 15 | Tabelle `faces_head_mark` e `faces_outbox` ridondanti, "da togliere prima del rilascio della v1" | `decisioni.md` (Fase 8, P3, punti aperti) | **Ancora presenti** (`FacePeopleDao.kt:57-66`) | P12: si smette di leggerle; una migrazione nuova le toglie solo dopo la conferma con P4 e P8 della Fase 8 (mai distruttiva per altro) |
| 16 | Account di prova `test` e `test2` **deboli**, "da eliminare o rafforzare a fine prove" | `decisioni.md` "Ambiente di prova sul NAS (14/10)" | Esistono | Utente, nella checklist di rilascio (§2.9) |
| 17 | La mappa con R8 mai vista funzionare in release (DR-1) | `funzionale-9.md` §2.9 | Plausibile, da provare | P14 (F187) |
| 18 | Voci delle revisioni ancora aperte: Fase 8 (R2, R9, R10, I2–I4, I8), Fase 9 (R1–R5, I1–I6), verifica funzionale della Fase 9 (16 voci); l'intestazione di `5b-fase5b.md` dice ancora "Aperta" mentre `decisioni.md` dà per corrette R3, R6, R7, R9, R10, R16–R19 | `docs/review/` | Due pacchetti di correzione della Fase 9 in corso; Fase 8 B e C fatti in parte | P11 le riconcilia una per una (chiusa con un test, o dichiarata con un limite) **prima** del rilascio |
| 19 | Regioni condivise in famiglia dal NAS (Q2 (b) della Fase 9) "se l'utente le vorrà dopo" | F9-D38 | Non previste (Q2 (a)); P5 aspetta la decisione sulle condizioni d'uso | Idea per dopo (Q10), salvo decisione su P5 |
| 20 | `docs/sicurezza.md` "rivisto per intero e condiviso con la famiglia" (anche con il padre dell'utente, piano §10) | piano §10, `sicurezza.md` §7 Fase 10 | Documento di 1.109 righe, intestazione "Bozza della Fase 0.6" | P12 (§1 riscritto per la famiglia), utente (lettura) |

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)

| # | Decisione | Perché |
|---|---|---|
| D1 | **Una fase, quattro tappe** (M1 widget, M2 tablet e finestre, M3 accessibilità, M4 sicurezza e rilascio); widget, tablet e accessibilità vanno **in parallelo** dopo P0. **Nulla tocca i media né scrive sul NAS** in modo nuovo: il trascinamento in arrivo passa dal flusso di ricezione esistente con la sua conferma, il manifest si **legge** soltanto. **Le recinzioni restano accese per tutta la fase**; si spengono solo nel passaggio alle share vere (§2.10), **in un solo passo dopo le prove della fase** (Q6 (b)), con il sì dell'utente. **La pubblicazione sul Pampa Store non è di questa fase** | La fase rifinisce e prepara il rilascio: nessuna strada nuova verso i file, nessuna scrittura in più finché l'utente non dice di sì |
| D2 | **Moduli e recinti**: `core-widget`, `core-release`, `core-describe` sono **JVM puri** (nessuna classe Android, nessuna rete, nessun NAS); `core-widget` vede `common`, `model`, `library` (contratti), `memories` (contratti); `core-release` vede solo `common` e `model` (firme con `java.security`); `core-describe` vede `common`, `model`, `library` (contratti), `places` (contratti) e prende persone e testo letto da porte. **Glance e `engine-widget` solo in `feature-widget`**. `Phase10ArchitectureTest` lo verifica; nessuna eccezione nuova a `NoNetworkSources` | Decisioni e regole si provano sulla JVM con fake; la UI del widget è un adattatore sottile; nessuna rete nuova per costruzione |
| D3 | **Il widget "MyCloud Gallery"**, un solo tipo in tre misure (`SizeMode.Responsive`): **piccola** (2×2: una foto, o la copertina del ricordo con il titolo), **larga** (4×2: copertina del ricordo più tre foto, o quattro foto), **grande** (4×4: copertina e sei foto). Contenuto secondo **Q1**: di base il **ricordo di oggi** (uno pubblicato oggi, non scartato, con almeno 8 elementi visibili) e, se non c'è, **foto a caso** dalla Libreria; nelle Impostazioni → Widget "Solo foto a caso" o "Solo ricordi". Al più **7 immagini** per widget | Il piano chiede "ricordi o foto casuali"; un tipo solo è più facile da capire e da provare; il ricordo fa sorridere, le foto a caso riempiono i giorni senza ricordo |
| D4 | **Da dove arrivano**: le foto da `WidgetFeed` (rotazione sull'istantanea visibile, `RotatingWidgetFeed`; il cursore è condiviso, quindi due widget mostrano foto diverse); il ricordo dallo **scaffale** con le regole della notifica (F9-D15): **mai un ricordo `PERSON`**, mai una persona con "Mostra meno" come copertina, **mai un nome** nel titolo (titolo come nella modalità "Titolo e foto": "Un anno fa · Oslo"; per i viaggi l'etichetta della Fase 7). Esclusi anche screenshot, documenti e immagini animate (come i ricordi, F9-D5) | Un nome di persona sulla Home lo vede chiunque abbia in mano il telefono sbloccato; screenshot e documenti non sono "ricordi" |
| D5 | **Mai un nascosto, nemmeno per un istante** (E-H1): (1) a **ogni** disegno `WidgetFeed` e il filtro finale ricontrollano `VisibleMedia` e `HiddenMask` **subito prima** di consegnare le immagini; (2) un **"Nascondi" su questo dispositivo** svuota **prima** le celle che mostrano quel media (aggiornamento senza immagini, nessuna decodifica, < 100 ms [stima]) e poi ridisegna; (3) un nascosto che **arriva da un altro dispositivo** (giro dei dati personali) chiama lo stesso `WidgetRefresher.hiddenChanged`, come `MemoryNotifier.revalidate`; (4) un ricordo potato dallo scaffale, un "Non mostrare", un "Mai nei ricordi" fanno lo stesso; (5) un errore di lettura = **nessuna foto** ("Apri MyCloud Gallery"), mai una lista non verificata; (6) azzeramento, dispositivo perso, uscita dalla famiglia: i widget passano a "Configura MyCloud Gallery" **prima** di cancellare i dati | Punto aperto 3 della Fase 7 P9: un widget che si ridisegna solo ogni mezz'ora mostrerebbe un nascosto arrivato da un altro dispositivo; il contenuto del widget vive nel launcher, fuori dall'app, e va tolto attivamente |
| D6 | **Aggiornamento parsimonioso**: `updatePeriodMillis = 0` (niente sveglie di Android); un **lavoro periodico di WorkManager** (`widget-refresh`, unico, solo se esiste almeno un widget) con la frequenza di **Q2** (di base **ogni 3 ore**, mai fra le 23 e le 7 ora locale), vincolo "batteria non scarica"; in più un ridisegno quando l'app va in secondo piano (la Home torna visibile), al cambio del ricordo del giorno (generazione notturna o passo leggero), al cambio di tema o accento (`engine-widget`, "Ridisegnare quando cambia l'aspetto") e per D5. **Nessuna rete, nessun NAS, nessuna decodifica di originali**; budget di un aggiornamento 2 s [stima < 400 ms sull'S25]; con il risparmio energetico il lavoro periodico tace (resta l'ultimo contenuto, già verificato) | Obiettivo del piano: < 2% di batteria al giorno in background; nessuna rete, quindi la proposta 3 non c'entra; un widget che cambia ogni 3 ore è "vivo" senza consumare |
| D7 | **Solo miniature locali**: la miniatura della replica (media solo sul NAS) o del telefono, al più **384 px** sul lato lungo (512 nella misura grande); un media **senza miniatura locale** (qualità Risparmio, replica non ancora scaricata) **si salta** e si prende il successivo; mai `ThumbHash` come foto del widget. Tetto: 7 bitmap e **2 MB** in tutto per widget (il limite di Android per le bitmap di un `RemoteViews` dipende dallo schermo: si resta molto sotto) | Media solo sul NAS (piano 10.1): il widget non aspetta il NAS né consuma dati; il limite delle bitmap è una causa nota di widget vuoti |
| D8 | **Schermata di blocco e blocco dell'app**: il widget dichiara solo `widgetCategory="home_screen"`: **mai** sulla schermata di blocco né nello hub dei widget a telefono bloccato (**Q4**). Con il **blocco dell'app attivo** vale **Q3 (c)**, senza altre scelte nelle Impostazioni: il widget mostra **solo media che sono anche sul telefono** (quelli che Galleria Samsung mostra già a chi ha il telefono sbloccato) e nessun titolo di ricordo; i media solo sul NAS e dell'area comune non compaiono finché il blocco è attivo | Il blocco dell'app difende da chi tiene in mano il telefono sbloccato: il widget non deve mostrargli più di quanto il telefono mostri già senza la nostra app |
| D9 | **Il tocco**: ogni cella ha un `PendingIntent` **immutabile ed esplicito** verso `MainActivity` con l'azione fissa `dev.pampa.mycloudgallery.action.OPEN_WIDGET` e **solo due numeri** (`appWidgetId`, indice della cella) in un URI opaco `mcg-widget:<id>/<cella>` (richiesta distinta per cella); `MainActivity` li riconosce durante la pulizia dell'intent come `OPEN_MEMORIES`, li valida (l'id è un nostro widget, la cella esiste nel **registro locale** di ciò che quel widget mostra) e consegna un evento interno **dopo il blocco dell'app**: il viewer su quel media se è **ancora visibile**, la pagina del ricordo per la copertina, altrimenti la Libreria con "Questa foto non c'è più". Nessun id di media nell'intent; nessun'altra azione | C17: un deep link apre al più una pagina, mai un'azione; un'app estranea che manda gli stessi numeri può solo aprire, dietro il blocco, una foto che il widget mostra già sulla Home |
| D10 | **Tecnica: Glance 1.1.1 con `engine-widget`** (la versione dell'engine), incluso nella build (`engine.modules`, `settings.gradle`, `engine-doctor`); palette, budget delle righe e superfici dal kit, le celle delle foto composable nostri in `feature-widget` con i token del kit (regola 1 dell'engine: nessun colore scritto a mano); anteprima nel selettore dei widget **disegnata**, mai foto dell'utente; foto intere, mai ritagli (promessa 4). **Piano B**: `RemoteViews` della piattaforma come il widget di prova (nessuna dipendenza nuova), se Glance non passa i controlli di P3 (manifest fuso, componenti, dimensione, `SessionWorker` con la nostra configurazione di WorkManager) | Il piano chiede "stile Fluid": il kit dell'engine esiste per questo e segue tema e accento; Glance chiede un download (Q9 (a): approvato il 31/10, lo scarica P3) |
| D11 | **Profili del tablet**: ogni profilo ha i suoi widget, il suo contenuto e le sue impostazioni (C19: un profilo è un dispositivo); un profilo fermo non aggiorna i suoi widget (E-P19) e li ridisegna al ritorno; niente passa da un profilo all'altro | Come notifiche e mappa della Fase 9 (E-NT10) |
| D12 | **Classi di finestra comuni**: un'utilità sola in `core-designsystem/.../adaptive/` (**file nuovi**, nessun componente esistente cambia): `GalleryWindowClass` dalla larghezza **della finestra** (non dello schermo) con le soglie **compatta < 600 dp**, **media 600–839**, **estesa 840–1199**, **ampia ≥ 1200**, più l'altezza (bassa < 480 dp) e il puntatore (mouse presente); letta da `LocalWindowInfo.containerSize`, **nessuna dipendenza nuova** (niente `material3-adaptive` né `androidx.window`). Le schermate che oggi misurano da sé (Raccolte, viewer, Onboarding, Cartelle NAS) passano all'utilità senza cambiare soglia (840) | Una regola sola per tutte le schermate e per i test; con il target 37 orientamento e ridimensionamento **non si potranno più disattivare** sugli schermi da 600 dp in su (`android.md`, "Novità di Android 17"): ogni larghezza va gestita comunque |
| D13 | **I pannelli** (stati e contratti, aspetto del filone grafico): (1) **Raccolte** a elenco e dettaglio da estesa (c'è); (2) **mappa e griglia della zona** da estesa: mappa a sinistra (60%), griglia della zona a destra quando si tocca una bolla, il viewer a finestra intera dalla griglia (F9-D38); (3) **Ricordi**: pagina del ricordo come dettaglio delle Raccolte; la presentazione sempre a finestra intera; (4) **Impostazioni** a elenco e dettaglio da estesa; (5) **Libreria e viewer affiancati** solo da **ampia** e solo a scelta: il viewer ha "Affianca" (griglia a sinistra al 40%, viewer a destra; il tocco su una cella cambia la foto, lo scorrimento della griglia resta), si toglie con "Schermo intero", Esc o Indietro; la scelta si ricorda per profilo. Di base il viewer resta a finestra intera, come in Apple Foto | Il piano chiede "più pannelli"; la foto vuole tutto lo spazio, quindi l'affiancamento è una scelta, utile in DeX con il mouse; la mappa è il caso dove due pannelli servono davvero (F9-D38) |
| D14 | **Rotazione e ridimensionamento senza perdere lo stato**: `MainActivity` dichiara `configChanges="orientation\|screenSize\|smallestScreenSize\|screenLayout\|keyboard\|keyboardHidden\|navigation"` (ricomposizione, non ricreazione: superficie di MapLibre, lettore video, zoom del viewer e scorrimento restano); **in più** ogni schermata salva in `rememberSaveable`/`SavedStateHandle` ciò che serve a ricostruirsi dopo una ricreazione vera (densità, lingua, tema, morte del processo in secondo piano): elemento ancorato della griglia (mai un indice), media e zoom del viewer, selezione (fino a 500 id, oltre si salva il filtro), pannello affiancato, camera della mappa, bolla aperta | La ricreazione a ogni ridimensionamento in finestra libera fa ripartire MapLibre e il video e salta lo scorrimento; ma la ricreazione resta possibile (DeX cambia densità, il sistema uccide il processo) e va provata |
| D15 | **DeX e finestre libere**: `resizeableActivity` esplicito vero; dimensione minima della finestra **320 × 400 dp** (`<layout android:minWidth android:minHeight>`): sotto, Android non scende; **mouse e tastiera**: clic destro = menu della pressione prolungata, rotella = zoom nel viewer e scorrimento nella griglia, passaggio del puntatore = stato evidenziato, frecce sinistra e destra nel viewer, Esc = indietro, Invio = apri, Ctrl+A = seleziona tutto nella griglia, Canc = **apre il foglio di conferma** (mai un'eliminazione diretta); trascinamento con il mouse (D20). I metadati di Samsung per DeX (tenere vivo il processo al cambio di modalità) si aggiungono solo se P4 li verifica sul Tab S9 [da verificare] | Il piano chiede DeX e finestre libere; un'app per il tocco in DeX senza tastiera e mouse è scomoda; le eliminazioni restano sempre dietro un foglio |
| D16 | **Fino a due finestre dell'app** (E-P18): "Apri in una nuova finestra" dal menu della Libreria, di un album, di un ricordo e della mappa, solo da finestra estesa o in DeX; `MainActivity` passa da `singleTask` a **`singleInstancePerTask`** (Android 12+; sotto resta una finestra) e la seconda finestra nasce con `FLAG_ACTIVITY_NEW_TASK \| FLAG_ACTIVITY_MULTIPLE_TASK \| FLAG_ACTIVITY_LAUNCH_ADJACENT`; una terza richiesta porta davanti quella che c'è con un messaggio; notifiche, widget e icona continuano ad aprire **la** finestra principale (stessa logica di oggi). `taskAffinity=""` resta (StrandHogg). [Comportamento di `singleInstancePerTask` con le notifiche e con il sistema "nuova finestra" della barra di Samsung: da verificare in P4 con una prova strumentata; **piano B**: una finestra sola, dichiarato in L] | Il piano chiede "più istanze"; due bastano (confronto, trascinamento fra due album) e tengono la memoria sotto controllo con MapLibre e il viewer |
| D17 | **Il blocco dell'app vale per il processo, non per la finestra**: `AppLockController` conta le activity avviate del processo (`ProcessLifecycleOwner` più il contatore delle nostre activity, comprese le schermate d'ingresso delle integrazioni): l'app "esce" quando **l'ultima** finestra si ferma, non quando una perde il fuoco o si ferma mentre l'altra resta visibile; uno sblocco vale per tutte le finestre; con il blocco attivo **ogni** finestra mostra la schermata di blocco (`AppLockGate` resta alla radice di ogni finestra) e ha i recenti oscurati; `FLAG_SECURE` resta per schermata e per finestra (contatore per finestra) | Oggi il gate presume una sola activity: con due finestre ogni passaggio conterebbe come un'uscita, o peggio una finestra resterebbe aperta mentre l'altra è bloccata |
| D18 | **Coerenza fra le finestre**: stessi grafi del processo (stessi `StateFlow`, stesso database, stessa sessione del NAS), quindi un nascosto, un preferito o un'eliminazione in una finestra si vedono subito nell'altra; **selezioni, scorrimento, pannelli e navigazione sono per finestra**; ogni conferma distruttiva è legata al piano con la sua impronta (come `PhoneConfirmation`): se l'altra finestra cambia gli elementi coinvolti, la conferma decade ("La selezione è cambiata") | E-P18 chiede coerenza: un solo processo la dà per costruzione; le conferme legate al piano evitano che una finestra confermi ciò che l'altra ha cambiato |
| D19 | **Due profili sul tablet**: nessuna novità di architettura (un profilo = un dispositivo); la Fase 10 prova il tablet con **due profili di prova** (account `test` come secondo dispositivo di `test`, account `test2` come familiare) e verifica che widget, finestre, pannelli, trascinamento e accessibilità valgano per profilo; il trascinamento fra profili diversi non esiste (Android non lo permette) | E-P19, E-P20, C19; gli scenari 1, 3 e 4 del §10 del piano |
| D20 | **Trascinare verso un'altra app** (da griglia della Libreria, album, ricerca, ricordo, griglia della zona e viewer; anche più elementi selezionati): `Modifier.dragAndDropSource` con `ClipData` di URI del **provider dei documenti** (`MediaDocumentsProvider`, id opachi) e `DRAG_FLAG_GLOBAL \| DRAG_FLAG_GLOBAL_URI_READ`; l'app che riceve legge con il permesso sul singolo URI (`CallerGuard`). Un media del telefono si legge dal telefono; uno **solo sul NAS** con il **descrittore a richiesta** (letto a pezzi, nessun file intero) o, con "Rimuovi la posizione" acceso, con una **copia temporanea senza posizione** in `cacheDir/share/` (fallire chiusi, E-X5). Tetti di E-X6 (100 elementi, 2 GiB); mai dai Nascosti, mai un nascosto (il provider li esclude); le copie temporanee vivono finché l'app è in primo piano più 10 minuti e comunque **non oltre il prossimo avvio** (`ShareCache`) | Il piano chiede "trascinamento tra app; un media solo sul NAS si scarica temporaneamente": provider, descrittore e copie senza posizione ci sono già e sono provati; nessun FileProvider nuovo |
| D21 | **Trascinare dentro l'app** (da Archivio, Galleria Samsung, un'altra app) sulla Libreria, su un album o su una cartella NAS: si apre il **foglio della ricezione** esistente ("Salva sul NAS", "Aggiungi ad album…") con il conteggio; `requestDragAndDropPermissions`, flussi **copiati subito** in `noBackupFilesDir/incoming/` con i tetti e il tipo dai byte (C18); **niente parte senza un tocco**; con il blocco attivo, prima lo sblocco (E-X7); verso un album condiviso valgono i permessi dell'album | Lo stesso flusso della condivisione in arrivo, già provato: nessuna strada nuova verso il NAS |
| D22 | **Descrizioni dei media** (`core-describe`, una per tutte le superfici): "Foto, 12 marzo 2024, 10:15, Oslo, con Anna e Marco, con la scritta «Biglietto del treno…», preferita, solo sul NAS". **Fatti**: tipo, data e ora locali (l'ora solo se coerente, come oggi), luogo (`PlaceNames`, offline), durata dei video, preferito, modificato, data stimata, gruppo, presenza; **persone** solo se Persone è attiva **su questo dispositivo** (`On` con `ACTIVE` o `LOCAL_ONLY`), solo persone **con un nome, non nascoste, non dimenticate**, al più 3 nomi più "e altri 2"; **testo letto** solo se la lettura del testo è attiva per l'account e non esclusa qui, le prime **12 parole**, ripulite (niente caratteri di controllo o di direzione, NFC). **Per superficie**: dentro l'app tutto; **widget** solo tipo e data; **notifica** niente oltre il testo della notifica; esportazione e log **mai**. Costruite **a richiesta** (quando il lettore di schermo chiede il nodo), mai salvate, mai in cache oltre lo schermo | Il piano chiede "descrizioni dei media" senza rete; i nomi stanno nei dati dei volti (F8-D13) e il testo letto nella replica: si leggono al momento e non si copiano da nessuna parte |
| D23 | **TalkBack completo**, regole per ogni schermata: (1) ogni elemento attivo ha un nome e un ruolo; immagini decorative senza descrizione; (2) **intestazioni** per titoli di pagina e sezioni; (3) **ordine** di lettura uguale a quello visivo, con le barre di vetro lette prima del contenuto; (4) **azioni personalizzate** dove c'è un gesto (cella: Apri, Seleziona, Preferito, Condividi, Nascondi, Elimina → foglio; viewer: Foto successiva, Precedente, Informazioni; bolla: Avvicina, Apri la zona; presentazione: Pausa, Avanti, Indietro); (5) **griglie come raccolte** (`CollectionInfo`: "riga 3 di 500"), intestazioni dei giorni come intestazioni; (6) **annunci** (`liveRegion` educata) per stati che cambiano da soli ("Backup: 12 in coda", "NAS non raggiungibile") senza ripeterli a ogni numero; (7) **fuoco** che torna dove era dopo un foglio o una pagina; (8) mappa: le bolle visibili anche come **elenco** ("Elenco dei luoghi in vista") navigabile; (9) nessun gesto senza alternativa (pizzico → azioni "Livello successivo/precedente"; trascinamento → "Sposta…" dal menu) | Il piano chiede "TalkBack completo"; le regole diventano test sui nodi (§5.3) |
| D24 | **Solo gli strumenti di accessibilità veri leggono i contenuti**: dalla Fase 10 la finestra dell'app si marca `accessibilityDataSensitive` (Android 14+, `setAccessibilityDataSensitive(YES)` sulla vista radice): TalkBack, Switch Access, Voice Access (`isAccessibilityTool`) leggono tutto, un servizio di accessibilità qualsiasi (tipico del malware) no. [Che la marcatura sulla radice copra i nodi virtuali di Compose: da verificare in P8 con un servizio di prova non "strumento"; piano B: la marcatura sui soli Nascosti, password e QR] | I servizi di accessibilità sono la via preferita del malware per leggere lo schermo; `FLAG_SECURE` non li ferma |
| D25 | **Testo grande**: tutto funziona fino a **200%** (scala non lineare di Android 14+): nessuna altezza fissa per il testo, righe che vanno a capo, troncamenti solo con il testo intero disponibile (TalkBack, pressione prolungata); prove a 1,0, 1,3 e 2,0 su telefono e tablet | Il piano chiede "testo grande"; oggi le prove arrivano a 1,3 |
| D26 | **Contrasto e vetro**: testo ≥ 4,5:1 (≥ 3:1 sopra 18 pt), icone e bordi utili ≥ 3:1, misurati nel caso peggiore sopra le foto con `GalleryLegibility` (c'è); quando il sistema chiede **più contrasto** (`UiModeManager.getContrast()` > 0, Android 14+) o **meno trasparenza** (impostazione di accessibilità di One UI [chiave da verificare in P8]) il vetro diventa **superficie piena** e il tema usa lo schema a contrasto alto; anche il widget segue | Il piano chiede "contrasto"; la revisione grafica del 13/10 aveva trovato il vetro poco leggibile: il caso peggiore deve avere un'uscita sicura |
| D27 | **Bersagli e movimento**: bersagli da **48 dp** con 8 dp fra due vicini (anche nelle barre di vetro e sulle bolle); **animazioni ridotte** (`ANIMATOR_DURATION_SCALE` 0 o "Rimuovi animazioni"): nessun movimento non essenziale (presentazione senza zoom, transizioni a dissolvenza breve, nessun rimbalzo), già nei token di `GalleryMotion` e controllato ovunque | Il piano chiede "target di tocco adeguati"; il movimento ridotto c'è, va verificato su ogni schermata |
| D28 | **Come si prova l'accessibilità** (§5.3): (1) **test sui nodi** per ogni schermata del catalogo degli stati (nome, ruolo, azioni, intestazioni, bersagli, ordine), JVM con Robolectric; (2) **Accessibility Test Framework** sugli screenshot di Roborazzi (modulo `roborazzi-accessibility-check`, da scaricare, Q9), solo nei test; (3) **screenshot** a testo 2,0x e contrasto alto, con il contrasto misurato sui pixel; (4) **sul campo**: TalkBack acceso e UI Automator che legge le descrizioni di ogni schermata (come F113 della Fase 9), più 5 minuti facoltativi dell'utente | Tre livelli che si completano: le regole, un controllo indipendente (ATF) e il telefono vero |
| D29 | **Manifest remoto firmato** (`core-release`, F6-D19): un file canonico (JSON canonico come il formato del NAS, al più **64 KiB**) con **firma ECDSA P-256** su SHA-256; campi: `schema`, `app` (= `dev.pampa.mycloudgallery`), **`seq`** (intero che solo cresce), `issued`, `expires` (al più 180 giorni dopo), **`minVersionCode`**, **`blocks`** (intervalli di `versionCode` con il livello `WRITES` o `ALL` e un **codice** di messaggio da un catalogo chiuso nell'app, mai testo libero), **`models`** (per `ModelId` e SHA-256 **già nel catalogo dell'app**: indirizzi `https` aggiuntivi, solo su host di `ModelHosts`). **Dove sta**: sul **NAS**, nell'area comune (`<area comune>/.mycloudgallery/release/manifest.mcgr`), messo dall'admin dal PC con lo strumento di P1; **solo dal NAS** (Q8 (a), 31/10): nessuna lettura da Internet, nessun host nuovo. **Quando si legge**: al più una volta al giorno, al giro di sincronizzazione già esistente (nessuna connessione in più verso il NAS) e all'avvio se l'ultima lettura ha più di 24 ore. **Effetti**: versione sotto `minVersionCode` o in un blocco `WRITES` = **sola lettura** (come "Ferma le scritture sul NAS": nessuna scrittura sul NAS, nessun lavoro che scrive, Libreria e viewer funzionano) con l'avviso "Aggiorna MyCloud Gallery dal Pampa Store"; blocco `ALL` = l'app mostra solo la pagina "Questa versione è stata fermata" (con Diagnostica ed esportazione), nessuna sessione del NAS, nessun lavoro, nessun traffico di mappa o modelli; **mai** cancellare dati, mai far scaricare un file nuovo, mai un host nuovo, mai codice. **Manifest assente** = nessun effetto; **firma non valida, app diversa, campo sconosciuto obbligatorio, troppo grande, `seq` minore del già visto** = ignorato per intero e contato (`RELEASE_MANIFEST_REJECTED`), resta l'ultimo valido; **scaduto** = gli indirizzi dei modelli decadono, i blocchi no | E-S7 e C22; il kill switch dell'engine (`engine-config`) è un JSON **non firmato** con testi liberi e "override" per app: chi controlla quel file controllerebbe le app; il NAS è il punto d'incontro della famiglia e non aggiunge nessun host (Q8) |
| D30 | **Stato dei blocchi solo più severo**: il blocco valido per **questa** `versionCode` si salva in locale (file atomico) e resta finché un manifest con `seq` più alto non lo toglie **o** l'app si aggiorna (nuova `versionCode`); un manifest rigiocato o più vecchio non lo toglie; l'azzeramento lo cancella e la prima lettura successiva lo rimette. Il controllo sta **prima** di ogni cancello di scrittura esistente (`AppBackupGate`, scritture dell'indice, album, modelli, dati personali, operazioni sui file), come una causa in più di `WRITES_STOPPED` | Un blocco che sparisce rigiocando un file vecchio non serve; agganciarsi ai cancelli esistenti non apre strade nuove |
| D31 | **Chiavi di pubblicazione del manifest**: **due** chiavi P-256 generate dall'admin sul PC con lo strumento di P1 (`tools/release/`, solo `java.security`, nessun download): **K1 attiva**, **K2 di riserva** mai usata finché K1 è buona; le due **chiavi pubbliche fissate nell'app** (`ReleaseKeys`, con l'impronta scritta in `docs/sicurezza.md`); le private in un PKCS#12 con frase segreta in `%USERPROFILE%\.mycloudgallery\release\` (mai nel repository: `check.ps1` passo 3) più **una copia offline** (chiavetta in un cassetto e foglio con il codice di K2). Diverse dalla chiave di famiglia e dalla chiave dell'APK (`pampa.jks`, D32). **Rotazione**: K1 persa o rubata → si firma con K2 un manifest che blocca le versioni che si fidano di K1 solo se serve, e un aggiornamento dell'app porta una coppia nuova (K2 attiva, K3 di riserva) | Una chiave sola persa = nessun kill switch fino al prossimo aggiornamento; la riserva offline chiude il caso senza dover ricostruire la fiducia |
| D32 | **Firma dell'APK con `pampa.jks`** (decisione dell'utente del 31/10: **niente chiave dedicata**). La chiave resta quella condivisa con le altre app Pampa (`app/build.gradle:21`), letta da `local.properties` e mai nel repository, con password in un gestore di password e **copia offline**; schemi di firma **v2 e v3** (v1 solo se il publisher del Pampa Store la pretende ancora [da verificare in P12]); impronta SHA-256 del certificato scritta in `docs/sicurezza.md` e controllata da `apksigner verify --print-certs` a ogni release (`release.ps1`). **Il rischio della chiave condivisa** (chi la ottiene da un'altra app Pampa può firmare un aggiornamento della galleria di famiglia) è un **limite dichiarato** (L559); lo contengono: nessun `sharedUserId` e nessun permesso `signature` definito dall'app (un'altra app Pampa con la stessa firma non ottiene niente in più; `Phase10ArchitectureTest`), la pubblicazione solo dal Pampa Store dell'admin (D34) e l'approvazione dell'admin per ogni dispositivo nuovo. **Rotazione futura** possibile con la discendenza della firma v3 (`apksigner rotate`, Android 9+, il minSdk è 29), senza reinstallare. Gli aggiornamenti si installano **solo con la stessa firma** per regola di Android: l'app non prova niente di suo. La build debug resta con la chiave di debug e il suffisso `.debug` | Contromisura 9 del piano ("aggiornamenti solo con la stessa firma", "`pampa.jks` fuori dal repo, protetto, con copia offline"); l'utente preferisce una chiave sola per tutte le sue app: la chiave dedicata resta un'opzione per dopo, con la rotazione v3 |
| D33 | **Versioni, canali, changelog**: `versionName` semantico (`1.0.0`; le beta `1.1.0-beta.2`), `versionCode` che **cresce di 1 a ogni APK pubblicato**, beta o stabile, mai riusato; la prima release della famiglia è **1.0.0**. **Canali del Pampa Store** (Q7 (a)): `beta` = i dispositivi dell'admin (S25 e il suo profilo del tablet), **almeno due settimane** con la release candidata; poi `stable` = **tutta la famiglia insieme**, con la stessa `versionCode` o una più alta. **Changelog** in italiano semplice in `CHANGELOG.md` alla radice (Novità · Correzioni · Sicurezza · Da sapere), una sezione per versione, da cui lo strumento di rilascio prende il testo del Pampa Store; nell'app, Impostazioni → Informazioni → "Novità di questa versione" dalla stessa sezione impacchettata (nessuna rete). Il database parte alla v6: le migrazioni 1→6 restano per le installazioni di debug e per i test | Una sola fonte del testo; i canali del Pampa Store esistono già; nessun aggiornamento dentro l'app (niente `engine-update`, niente `REQUEST_INSTALL_PACKAGES`): aggiorna il Pampa Store |
| D34 | **Catena di pubblicazione**: account GitHub con **2FA** (app di autenticazione o chiave di sicurezza; codici di recupero offline); token del publisher **a grana fine**, solo questo repository e solo "contenuti", con scadenza; ramo `main` protetto (niente push forzati); si rilascia **solo** da un commit con tag, albero pulito, `check.ps1` e `check.ps1 -Bench` verdi; l'APK firmato si controlla (firma, impronta del certificato, `debuggable=false`, manifest fuso, nessun resto di debug) **prima** di caricarlo; il file di mapping di R8 resta sul PC (mai pubblicato). La pubblicazione la avvia l'utente con la skill del Pampa Store, **alla fine di tutte le fasi** | Contromisura 9 del piano; chi entra nell'account GitHub potrebbe sostituire un APK (la firma lo fermerebbe sugli aggiornamenti, non sulle prime installazioni) |
| D35 | **Passaggio alle share vere, tutto insieme dopo le prove** (§2.10; Q6 (b), risposta del 31/10, **non** la raccomandata): le quattro recinzioni (**F4-D31** indice e dati personali, **F5-D12** backup, **F8-D34** dati dei volti, **F7-D30** album condivisi) si spengono **in un solo passo**, **dopo le prove della Fase 10** (F160–F189 fatte o dichiarate, più le prove sul campo delle quattro aree superate sulle share di prova) e **con il sì dell'utente**. Prima del passo: la **"Simulazione del passo"** in sola lettura sulle share vere per le quattro aree insieme (che cosa scriverebbe ciascuna, quanti file e byte, in quali cartelle, lo spazio libero dopo) e la **fotografia delle share prima** dal PC; il sì dell'utente con i numeri davanti; una build con **le quattro costanti** cambiate installata **solo sui dispositivi dell'admin**; poi la **fotografia dopo** (niente fuori dalle cartelle attese di ogni area). **Indietro**: subito "Ferma le scritture sul NAS" (c'è), poi una build con le quattro recinzioni di nuovo accese (o solo quella dell'area che ha sbagliato, se la fotografia la isola e l'utente lo decide); ciò che è stato scritto è **additivo** e resta (elenco al §2.10). `tools/check.ps1` legge lo stato atteso di ogni recinzione da `tools/release/recinzioni.json` (un solo consenso per le quattro) e verifica il bytecode in entrambi i casi | L'utente preferisce un passo solo, dopo che le prove hanno detto che ogni area funziona sulle share di prova; le protezioni del passo (simulazione, fotografie, sì, soli dispositivi dell'admin, ritorno indietro) restano tutte. Il rischio in più (quattro aree che cominciano a scrivere insieme) lo coprono la simulazione per area e la fotografia, che dicono quale area ha scritto dove |
| D36 | **Revisione finale di sicurezza 10.4 come prove pratiche** (§2.11): ogni voce del piano diventa una prova **ripetibile** con dove gira (JVM, emulatore, S25, NAS di prova sotto `mcg-it/<giro>/`) e che cosa dimostra; gli attacchi si costruiscono con strumenti in `tools/security/` (segmenti ostili, dispositivo revocato, manifest manomessi, modelli sbagliati, cambi di massa) che scrivono **solo** dentro `mcg-it/<giro>/` delle share di prova; ogni voce aperta delle revisioni è **chiusa con un test o dichiarata con un limite** prima del rilascio; alla fine `security-review` e verifica funzionale come in ogni fase | Il piano chiede "revisione finale con prove pratiche"; senza strumenti ripetibili una prova fatta una volta non dice niente sulla release dopo |
| D37 | **Verifica complessiva (§10 del piano)** come piano di prove (§2.14): gli otto scenari su S25 (`test`, debug e release candidata), tablet con **due profili di prova** (`test` e `test2`) ed emulatore (`test`), in autonomia dove si può; all'utente **circa un'ora in tre momenti** (tablet, una sera, il recupero dell'admin), più una settimana d'uso normale dopo il passaggio alle share vere (scenario 8) | Il "finito" del piano; il tempo dell'utente è la risorsa più scarsa |
| D38 | **Confine con la fase grafica**: la Fase 10 consegna **stati sigillati e contratti** (widget, pannelli, finestre, descrizioni), composable fatte solo con i componenti G2 e del kit dell'engine, e **garanzie di accessibilità come test** (nodi, bersagli, contrasto, testo 2,0x, movimento ridotto) che la fase grafica **non può rompere** (diventano parte del suo "Fatto quando"); la fase grafica decide l'aspetto (widget, proporzioni dei pannelli, G3: viewer, presentazione, bolle, vetro) senza toccare logica e contratti. **Ordine** secondo Q5 (a): Fase 10 → fase grafica → rilascio; il passaggio alle share vere (il passo unico di Q6 (b)) corre **in parallelo** alla fase grafica, sui soli dispositivi dell'admin | La famiglia vede l'app una volta sola, finita; l'accessibilità va riprovata dopo la grafica, quindi le sue prove devono essere automatiche |
| D39 | **Log e Diagnostica**: sezioni nuove `DiagSection.WIDGET` (widget attivi per misura, ultimo aggiornamento, saltati per mancanza di miniatura, svuotamenti per nascosti) e `DiagSection.RELEASE` (versione, canale, `seq` e data del manifest, blocco in vigore, rifiuti per motivo, indirizzi aggiuntivi in uso: solo il numero); codici `WIDGET_*`, `RELEASE_*`, `DRAG_*`, `WINDOW_*` (§2.13); mai id, titoli, nomi, descrizioni, URL dei modelli, contenuto del manifest oltre ai numeri; chiavi nuove nell'elenco chiuso di `ExportKeys` | C21: la Diagnostica non è una telemetria |
| D40 | **Permessi, componenti, dipendenze, host**: **nessun permesso nuovo**; **componenti nuovi**: il ricevitore del widget (**non esportato**) e i componenti interni di Glance (tutti **non esportati** [da verificare in P3 sul manifest fuso]); `MainActivity` cambia `launchMode` e dichiara `configChanges` e `<layout>`; **dipendenze nuove**: Glance 1.1.1 e i suoi transitivi (app), Accessibility Test Framework e `roborazzi-accessibility-check` (**solo test**); **nessun host nuovo** (Q8 (a): il manifest si legge solo dal NAS) | Checklist "per ogni fase" di `sicurezza.md` §7 |
| D41 | **Nascosti su ogni superficie nuova**: righe del registro `HiddenSurfaces`: `WIDGET` passa da "di prova" a **produzione** (widget vero, sonda sul mondo casuale con nascosti dell'ultimo momento e da un altro dispositivo), `DRAG` (sorgente del trascinamento: mai un nascosto, mai dai Nascosti), `DESCRIPTIONS` (una descrizione non nomina mai una persona nascosta e non esiste per un nascosto fuori dai Nascosti), `PANES` (pannelli affiancati e seconda finestra: stesse fonti, nessuna copia) | D22 della Fase 7: il registro fallisce da solo per un file nuovo non registrato |
| D42 | **Azzeramento e uscita**: prima di cancellare, i widget passano a "Configura MyCloud Gallery" (senza immagini), il lavoro `widget-refresh` si annulla, le copie temporanee del trascinamento si tolgono, il registro dei widget e lo stato del manifest si cancellano; la seconda finestra si chiude | Il contenuto del widget sta nel launcher: va tolto prima che i dati spariscano |
| D43 | **Idee per il dopo (10.6), solo elencate**: versioni 720p dei video per la visione fuori casa; importazione da chiavetta o SD verso il NAS; cast su TV; imposta come sfondo; stampa; didascalie; esportazione di un album; picture-in-picture (Android 17 chiede un servizio adatto, `android.md`); altre lingue; in più quelle nate nelle fasi: widget con il soggetto ritagliato, descrizioni del contenuto stimate dai vettori ("spiaggia, cane"), regioni della mappa condivise dal NAS (F9-D38), condividere un ricordo come video (F9 §3), editor degli album smart sulla carta (F9 §2.13). **Scelte dell'utente (Q10, 31/10), in quest'ordine: (1) esportare un album, (2) un ricordo come video**; restano nel §10.6 del piano "per il dopo", non si progettano ora; le altre restano solo elencate | Il piano: "solo elencate, non si progettano" |

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)

Cinque categorie nuove (prefissi non usati finora, controllati il 31/10): **E-W** (widget), **E-T** (tablet, finestre, trascinamento),
**E-AC** (accessibilità), **E-REL** (rilascio e manifest), **E-SV** (passaggio alle share vere); più una voce dei Nascosti (E-H11).

**Widget (E-W)**
- **E-W1**: Libreria vuota, nessuna miniatura locale, nessun ricordo: una carta "Apri MyCloud Gallery" con la forma di una riga, non di un
  errore (`EngineWidgetMessage`).
- **E-W2**: un media mostrato dal widget diventa nascosto su questo dispositivo: le celle che lo mostrano si svuotano subito, poi il widget
  si ridisegna con altre foto (D5).
- **E-W3**: un media diventa nascosto su un altro dispositivo dello stesso account: al primo giro dei dati personali il widget si ridisegna;
  fino ad allora mostra solo ciò che era visibile qui al suo ultimo disegno.
- **E-W4**: il processo è morto all'ora dell'aggiornamento: il lavoro monta solo ciò che serve (istantanea su file, maschera dei nascosti,
  scaffale); se non è pronto entro il budget, resta il contenuto già verificato, mai uno non verificato.
- **E-W5**: media solo sul NAS senza miniatura locale (qualità Risparmio, replica in corso): si salta; mai il NAS, mai la rete.
- **E-W6**: blocco dell'app attivo: il widget mostra solo media che sono anche sul telefono e nessun titolo di ricordo (Q3); allo sblocco
  dell'app torna il contenuto normale al ridisegno successivo.
- **E-W7**: tocco sul widget con il blocco attivo: prima lo sblocco, poi la foto o il ricordo (come E-NT8).
- **E-W8**: il media toccato nel frattempo è stato eliminato o nascosto: si apre la Libreria con "Questa foto non c'è più".
- **E-W9**: azzeramento, dispositivo perso, uscita dalla famiglia, "Configura di nuovo": i widget passano a "Configura MyCloud Gallery"
  prima che i dati spariscano.
- **E-W10**: cambio di tema, di accento o di modalità scura: il widget si ridisegna (non resta con la palette vecchia).
- **E-W11**: più widget sulla Home: foto diverse fra loro allo stesso giro; uno stesso ricordo può stare su più widget.
- **E-W12**: risparmio energetico, Doze, telefono fermo di notte: nessun aggiornamento periodico; resta l'ultimo contenuto.
- **E-W13**: "Cancella dati" dalle impostazioni di Android o disinstallazione: Android toglie o svuota i widget; finché il launcher non lo
  fa, può mostrare l'ultima immagine (limite dichiarato, L543).
- **E-W14**: ricordo di una persona o con una persona con "Mostra meno" in copertina: mai sul widget; il titolo non ha mai nomi.
- **E-W15**: profilo del tablet fermo: i suoi widget non si aggiornano; al ritorno si ridisegnano (E-P19).
- **E-W16**: misura del widget cambiata dall'utente: nuova impaginazione, nessuna foto tagliata a metà riga; bitmap entro il tetto.
- **E-W17**: un video nel widget: il fotogramma della miniatura con il segno "video"; mai la riproduzione.
- **E-W18**: il selettore dei widget del launcher mostra l'anteprima: un disegno fisso, mai foto dell'utente.
- **E-W19**: il ricordo di oggi viene scartato ("Non mostrare") o perde elementi sotto la soglia: il widget passa alle foto a caso al
  ridisegno immediato.

**Tablet, finestre, trascinamento (E-T)**
- **E-T1**: rotazione con il viewer aperto e zoomato: stessa foto, stesso zoom e stesso punto; con la griglia, stesso elemento in vista.
- **E-T2**: finestra libera ridimensionata di continuo: i pannelli cambiano alla soglia, senza perdere selezione, scorrimento e pagina.
- **E-T3**: finestra al minimo (320 × 400 dp) o in DeX molto stretta: impaginazione da telefono, nessun controllo che si sovrappone.
- **E-T4**: due finestre dell'app: un media nascosto, preferito o eliminato in una lo è subito anche nell'altra; selezioni separate.
- **E-T5**: due finestre, conferma distruttiva aperta in una mentre l'altra cambia gli stessi elementi: la conferma decade (D18).
- **E-T6**: richiesta di una terza finestra: si porta davanti quella che c'è, con un messaggio breve.
- **E-T7**: blocco dell'app con due finestre: si blocca quando **entrambe** sono in secondo piano oltre il tempo scelto; uno sblocco vale
  per tutte; con il blocco attivo nessuna finestra mostra contenuti.
- **E-T8**: il processo muore con l'app in secondo piano in una finestra libera: alla riapertura stessa pagina, stesso elemento, stessa
  selezione (fino a 500 elementi).
- **E-T9**: entrata o uscita da DeX (cambio di schermo e di densità): nessuna perdita di stato; MapLibre e il video ripartono senza crash.
- **E-T10**: mouse e tastiera: clic destro apre il menu, rotella fa zoom nel viewer, Canc apre il foglio di conferma (mai un'eliminazione
  diretta), Esc torna indietro.
- **E-T11**: trascinamento di un media solo sul NAS con il NAS irraggiungibile: l'app che riceve ha un errore chiaro e l'utente un
  messaggio; nessuna attesa infinita (E-X1).
- **E-T12**: trascinamento di più elementi oltre i tetti (100 elementi o 2 GiB, E-X6): rifiuto motivato prima di cominciare.
- **E-T13**: trascinamento con "Rimuovi la posizione" acceso: copie senza GPS; se la posizione non si può togliere, quell'elemento non
  parte (E-X5).
- **E-T14**: l'app che riceve tiene il permesso dopo che abbiamo chiuso: dura quanto decide Android; le copie temporanee si tolgono al
  prossimo avvio (L547).
- **E-T15**: trascinamento in arrivo con un URI ostile, enorme o di un tipo sbagliato: come E-X2, rifiutato prima di copiare.
- **E-T16**: trascinamento in arrivo su un album condiviso con permesso "guardano": l'album non si offre; su un album "aggiungono": solo
  aggiunta.
- **E-T17**: cambio di profilo del tablet o schermo spento durante un trascinamento: annullato, nessuna copia rimasta.
- **E-T18**: trascinamento dai Nascosti o di un media dei Nascosti: impossibile (nessuna sorgente nei Nascosti).
- **E-T19**: Nascosti aperti in una finestra mentre l'altra è in primo piano: `FLAG_SECURE` e recenti oscurati valgono per la finestra
  dei Nascosti; l'altra non mostra nascosti.

**Accessibilità (E-AC)**
- **E-AC1**: TalkBack nella Libreria da 100 mila elementi: le descrizioni si calcolano solo per ciò che il lettore chiede; lo scorrimento
  resta dentro le guardie (§5.4).
- **E-AC2**: persone in una foto con Persone spenta, esclusa su questo dispositivo, o persone senza nome o nascoste: nessun nome nella
  descrizione.
- **E-AC3**: testo letto lungo, con caratteri di controllo, di direzione o emoji: ripulito, prime 12 parole, mai letto per intero.
- **E-AC4**: testo al 200%: nessun testo tagliato senza modo di leggerlo intero; le barre di vetro crescono, non coprono il contenuto.
- **E-AC5**: contrasto alto o meno trasparenza chiesti dal sistema: superfici piene al posto del vetro, anche nel widget.
- **E-AC6**: animazioni ridotte o rimosse: nessun movimento non essenziale (zoom della presentazione, rimbalzi, Fluid-physics del vetro).
- **E-AC7**: TalkBack nel viewer: si va avanti e indietro con le azioni o con lo scorrimento a due dita; il pizzico non serve.
- **E-AC8**: TalkBack sulla mappa: le bolle visibili anche come elenco; ognuna dice il numero e il luogo, mai la casa.
- **E-AC9**: Switch Access o tastiera: ogni azione raggiungibile senza gesti (pizzico, trascinamento, scorrimento laterale).
- **E-AC10**: un servizio di accessibilità che non è uno strumento di accessibilità (per esempio un'app malevola con il permesso): non
  legge i contenuti dell'app (Android 14+, D24).
- **E-AC11**: descrizioni nel widget e nelle notifiche: solo tipo e data; mai luoghi, nomi o testo letto.
- **E-AC12**: lingua e date: italiano, date per esteso ("12 marzo 2024"), numeri letti come numeri; nessun id o nome di file.

**Rilascio e manifest (E-REL)**
- **E-REL1**: manifest assente (prima installazione, NAS mai raggiunto, file tolto da un familiare): nessun effetto.
- **E-REL2**: manifest con firma non valida, per un'altra app, malformato o oltre 64 KiB: ignorato per intero, contato; resta l'ultimo
  valido (E-S7).
- **E-REL3**: manifest vecchio rigiocato (`seq` minore di quello già visto): ignorato; un blocco non si toglie così.
- **E-REL4**: manifest scaduto: gli indirizzi aggiuntivi dei modelli decadono; i blocchi e la versione minima restano.
- **E-REL5**: questa versione è bloccata con `WRITES` o è sotto la versione minima: sola lettura con l'avviso "Aggiorna dal Pampa Store";
  ciò che era in corso si ferma al punto di ripresa, niente si perde. *Correzioni A (02/11)*: la sola lettura ferma le scritture dei **contenuti** (come
  "Ferma le scritture"), non quelle dello strato della famiglia (stato del dispositivo, registro, segnalazioni, kit, pulizia dei temporanei: L570);
  "Salva sul NAS" non si offre e le schermate dicono la causa ("questa versione va aggiornata", C460, L571).
- **E-REL6**: questa versione è bloccata con `ALL`: solo la pagina "Questa versione è stata fermata"; nessuna sessione del NAS, nessun
  lavoro; Diagnostica ed esportazione restano. *Correzioni A (02/11)*: anche fuori dalla pagina: selettore, provider dei documenti, ricezione e
  rilascio del trascinamento si chiudono con il motivo; notifica dei ricordi, lettura del telefono e manutenzione della Libreria non partono (C460);
  il widget si svuota (pacchetto B).
- **E-REL7**: indirizzo aggiuntivo per un modello che l'app non conosce, con un'impronta diversa o su un host fuori da `ModelHosts`:
  ignorato (il file resta comunque verificato dall'impronta fissata).
- **E-REL8**: chiave di pubblicazione persa o rubata: K2 di riserva e un aggiornamento dell'app con chiavi nuove (D31).
- **E-REL9**: orologio del telefono sbagliato: la scadenza si giudica con l'ora del NAS quando c'è, altrimenti con la più tarda fra
  l'ultima ora del NAS vista e l'orologio del telefono.
- **E-REL10**: un APK con un'altra firma (falso): Android rifiuta l'aggiornamento; l'app non tenta niente. Un'altra app Pampa firmata con
  `pampa.jks` ha un altro id: non sostituisce la galleria e non ottiene permessi da lei (nessun `sharedUserId`, nessun permesso
  `signature` nostro). Un APK con il **nostro** id firmato con `pampa.jks` da chi ha rubato la chiave verrebbe accettato: limite L559.
- **E-REL11**: aggiornamento durante un lavoro (backup, operazioni sul NAS): ripresa dal punto salvato (E-P8).
- **E-REL12**: versione più vecchia installata a mano sopra una più nuova (database più nuovo dell'app): nessuna migrazione distruttiva;
  l'app si ferma con "Installa la versione più recente" e non tocca i dati.
- **E-REL13**: il blocco arriva mentre una conferma distruttiva è aperta: la conferma decade, niente parte.
- **E-REL14**: beta e stabile sullo stesso telefono: impossibile (stesso id); chi passa da beta a stabile aspetta una stabile con
  `versionCode` più alto.

**Passaggio alle share vere (E-SV)**
- **E-SV1**: la simulazione sulle share vere dice numeri inattesi per un'area (molti più file o byte del previsto, cartelle che non
  conosciamo): il passo non parte, decide l'utente con i numeri davanti (anche di tornare a un'area alla volta, con un nuovo sì).
- **E-SV2**: dopo il passo, il controllo dal PC trova file cambiati fuori dalle cartelle attese: "Ferma le scritture sul NAS" e ritorno
  indietro (§2.10); la fotografia dice quale area ha scritto dove.
- **E-SV3**: il primo backup vero è grande (migliaia di foto e video): a tappe di notte, con le regole; spazio del NAS controllato prima,
  mai sotto la soglia.
- **E-SV4**: il congelamento per cambi di massa scatta durante un passo: tutto si ferma come oggi (C11); il passo riprende solo dopo
  "Ho controllato".
- **E-SV5**: un familiare installa l'app prima che l'admin abbia finito il passo e le due settimane di beta: impossibile dal Pampa Store
  (stabile solo alla fine, per tutta la famiglia insieme, Q7 (a)); se succede a mano, valgono le recinzioni della sua build.
- **E-SV6**: ritorno indietro dopo il passo: le scritture si fermano subito; i dati già scritti restano (sono copie e indici, additivi;
  i file degli album condivisi restano nell'area comune finché non si riportano) e l'elenco di cosa resta è nel §2.10.
- **E-SV7**: account di prova ancora presenti dopo il rilascio: `backupBlocksTestAccounts` li ferma in release; la checklist chiede di
  toglierli o rafforzarli.

**Nascosti (E-H)**
- **E-H11**: un media nascosto non compare mai nel widget (né prima né dopo un ridisegno, nemmeno se nascosto da un altro dispositivo), non
  si trascina, non si descrive fuori dai Nascosti, non si vede nella seconda finestra.

### 0.3 Modifiche da fare in P0 (agli altri documenti)

**`docs/sicurezza.md`**: intestazione (stato, sigle con **C430–C459** e **L540–L569**); §1 (righe per la famiglia: widget, tablet,
accessibilità, aggiornamenti firmati, cosa vuol dire "versione fermata"); §2 (beni: "Contenuto del widget nel launcher", "Chiavi di
pubblicazione e di firma dell'APK"); **C14** (blocco dell'app per processo e per finestra, D17), **C16** (prova T-25 rifatta in 10.4), **C17**
(azione `OPEN_WIDGET` con due numeri, D9; trascinamento con permessi per URI, D20), **C21** (Diagnostica `WIDGET` e `RELEASE`), **C22**
riscritta (manifest firmato realizzato, firma dell'APK con `pampa.jks` controllata, catena di pubblicazione, D29–D34), **C27** (righe `WIDGET`, `DRAG`,
`DESCRIPTIONS`, `PANES`); **C430–C449** nuove (§6.1); §4-bis (nessun permesso nuovo; componenti del widget e di Glance non esportati;
`launchMode`); §6 **L65 riscritta** e **L540–L557** e **L559** (§6.2; L558 non serve con Q8 (a)); §7 checklist della Fase 10 riscritta (§6.3); §8 righe per D5, D8, D9, D17, D20,
D24, D29–D35.

**`docs/decisioni.md`**: sezione "Fase 10" con D1–D43 e le risposte Q1–Q10 del §9.

**`docs/android.md`**: Tema 1 (lavoro periodico del widget e quote del secchio), Tema 6 (widget e finestre per profilo), riga in "Novità
di Android 17" su `singleInstancePerTask`, `accessibilityDataSensitive` e i limiti delle bitmap dei widget [da verificare in P3 e P4].

**`docs/grafica-progetto.md`**, "Dubbi grafici" **§12.16** (Fase 10): widget nelle tre misure, pannelli (proporzioni, divisore), "Affianca"
del viewer, superfici piene a contrasto alto, stati di blocco ("Questa versione è stata fermata", "Aggiorna"), elenco delle bolle per
TalkBack, focus visibile in DeX; e la regola di D38 (le prove di accessibilità entrano nel "Fatto quando" della fase grafica).

**`docs/modello-dati.md`**: §17 "Dati locali della Fase 10" (registro dei widget, stato del manifest, copie temporanee del trascinamento;
nessuna tabella nuova; database invariato salvo la nota sulle tabelle della promessa 15).

**`docs/formato-nas.md`**: §nuovo "Manifest di rilascio" (posizione nell'area comune, formato canonico, firma, campi, regole di lettura e
di rigetto) e nota che **non** è un file della famiglia (non lo firma la chiave di famiglia, non entra nel registro).

**`docs/regole-attivita.md`**: §2 (il ridisegno del widget non è un'attività: lavoro periodico leggero senza rete; la lettura del manifest
sta dentro il giro di sincronizzazione esistente); §6 costanti (`widgetRefreshPeriod`, `widgetQuietHours`, `widgetBudget`,
`manifestReadEvery`).

**`docs/prove-campo.md`**: sezione "Fase 10" con F160–F189 e il programma dei tre momenti dell'utente (§5.5).

**`docs/fase6-progetto.md`**: nota a D19 e al §2.1 (il manifest è realizzato qui: D29–D31). **`docs/fase7-progetto.md`**: nota a L65 e al
§2.9 (widget vero e trascinamento). **`docs/fase9-progetto.md`**: nota a D38 (realizzata da D3–D4, D13, D23).

**`README.md`**: moduli nuovi, grafo, `CHANGELOG.md`, `tools/release/` e `tools/security/`.

**`engine.properties`** e `settings.gradle`: `engine-widget` fra i moduli dell'engine (P3; Q9 (a) approvato il 31/10).

**Piano** (lo riporta l'orchestratore): edge case del §0.2; 10.1 precisata (contenuto di Q1, frequenza di Q2, blocco dell'app di Q3,
schermata di blocco di Q4); 10.2 con "fino a due finestre" e "Affianca"; 10.5 con il manifest dal NAS (Q8) e la firma dell'APK; §7
"Limiti" con L540–L557 e L559 (L558 non serve con Q8 (a)); §9 "Punti aperti": nessuno nuovo. Con le risposte del 31/10 (§11): 10.5 con
il manifest **solo dal NAS** e la firma con **`pampa.jks`**; il passaggio alle share vere **in un solo passo** dopo le prove (Q6 (b)); la
famiglia **tutta insieme** dopo due settimane dell'utente (Q7 (a)); 10.6 con le due idee scelte in ordine (Q10).

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)

```
core-model         nessuna aggiunta                                                                                 (—)
core-library       + contract/Drag.kt: DragRequest, DragOrigin, DragRefusal (tipi puri, nessun ClipData)           (P0)
core-memories      nessuna API nuova: il widget legge Memories.shelf e le regole di MemoryNotifyPolicy              (—)
core-models        + contract/ExtraModelSources (porta, null-object vuoto); ordine delle sorgenti NAS → fissati →
                     aggiuntivi in RoutingModelFetcher                                                              (P0 firme, P1)
core-security      + lock/ProcessForeground: il blocco dell'app conta le finestre del processo                       (P4)
core-designsystem  + adaptive/ (file nuovi): GalleryWindowClass, GalleryPanes, PointerKind, mediaDragSource         (P0 firme, P5)
core-widget      → common, model, library (contratti), memories (contratti)                                         (P0)
                     contract/  WidgetPlan, WidgetPlanner, porte, WidgetLedger, WidgetRefresher, WidgetTap
                     plan/      scelta del contenuto, filtri, budget delle immagini                                 (P2)
                     ledger/    registro di ciò che ogni widget mostra (file atomico)                              (P2)
                     schedule/  quando ridisegnare, ore di quiete                                                   (P2)
                     NON vede: Android, rete, NAS, database, faces, search, albums, imaging
feature-widget   → widget (contratti), imaging (miniature locali), designsystem, engine-widget, Glance              (P3)
                     cieca a core-nas, core-network, core-memories (interno), feature-*
core-release     → common, model                                                                                    (P0)
                     contract/  ReleaseManifest, ManifestVerifier, ReleaseStatus, porte
                     codec/     JSON canonico, tetti, firma P-256                                                   (P1)
                     state/     blocchi solo più severi, anti-rigioco, scadenza, indirizzi aggiuntivi              (P1)
                     keys/      ReleaseKeys (K1, K2 fissate)                                                        (P1)
                     NON vede: Android, rete, NAS, models (gli indirizzi passano come testo validato da :app)
core-describe    → common, model, library (contratti), places (contratti)                                          (P0)
                     contract/  MediaFacts, DescribeSurface, MediaDescriber, porte DescribePeople e DescribeText
                     rules/     cosa entra per superficie, ripulitura del testo, tetti                             (P7)
                     NON vede: faces, search, ai, nas, network, database, Android
feature-integrations + drag/: sorgente (ClipData dal provider dei documenti) e destinazione (verso il flusso di
                     ricezione)                                                                                     (P6)
feature-library, feature-albums, feature-search, feature-memories, feature-map, feature-viewer, feature-people,
feature-settings   + pannelli (adaptive), semantica, tastiera, trascinamento come porta; feature-settings
                     + Impostazioni → Widget                                                                       (P5, P6, P8)
feature-diagnostics + sezioni WIDGET e RELEASE                                                                      (P13)
core-common      + DiagSection.WIDGET e RELEASE, Phase10ArchitectureTest                                            (P0)
app → tutti                                                                                                         (P13)
```
- `core-widget`, `core-release`, `core-describe` sono **JVM puri** (test d'architettura): politica del widget, manifest e descrizioni girano
  sulla JVM nei test e nei banchi.
- **Rete**: nessuna eccezione nuova a `NoNetworkSources`. Con **Q8 (a)** (risposta del 31/10) il manifest si legge **solo dal NAS**, con la
  sessione esistente: nessuna classe di rete nuova (la `ManifestHttps` pensata per Q8 (b) non esiste) e la regola 5 della Fase 6 resta
  "due pacchetti elencati".
- **Glance** e `engine-widget` solo in `feature-widget` (test d'architettura e passo nuovo di `check.ps1` sul dex: `androidx.glance`
  chiamato solo da `feature/widget/` e da Glance).
- **Conferme e fiducia**: `ReleaseKeys` e il verificatore si costruiscono solo in `core-release`; nessun altro modulo legge i campi del
  manifest se non attraverso `ReleaseStatus` e `ExtraModelSources`; nessun modulo costruisce un `PendingIntent` del widget fuori da
  `feature-widget/.../tap/`.
- I `feature-*` restano ciechi fra loro: il trascinamento passa da una **porta** (`MediaDragHandler`, null = niente trascinamento), il
  widget apre l'app con l'azione verso `MainActivity`.
- Restano le regole delle Fasi 4–9: solo `core-nasops` vede `NasFileOps`; nessun modulo della Fase 10 legge `home_estimate`; LiteRT solo in
  `core-ai`; MapLibre e OkHttp solo in `feature-map`.

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-widget` (P0 contratti)**
| Elemento | Firma | Note |
|---|---|---|
| Identità e misure | `@JvmInline value class WidgetSlot(val appWidgetId: Int)`; `enum class WidgetSize(val cells: Int) { SMALL(1), WIDE(4), LARGE(7) }`; `enum class WidgetChoice { MEMORY_THEN_PHOTOS, PHOTOS_ONLY, MEMORIES_ONLY }` (Q1); `enum class WidgetPeriod(val hours: Int) { HOURS_3(3), HOURS_6(6), DAILY(24) }` (Q2) | `toString()` senza id di media |
| Piano | `data class WidgetPlan(val slot: WidgetSlot, val size: WidgetSize, val mode: WidgetMode, val cells: List<WidgetCell>, val epoch: Long)`; `sealed interface WidgetMode { data class Memory(val id: MemoryId, val title: WidgetTitle) : WidgetMode; data object Photos : WidgetMode; data class Message(val reason: WidgetEmpty) : WidgetMode }`; `data class WidgetCell(val index: Int, val media: MediaId, val kind: MediaKind, val localDay: Int)`; `data class WidgetTitle(val yearsAgo: Int?, val placeIds: IntArray, val tripLabel: String?, val month: YearMonth?)` (mai una persona); `enum class WidgetEmpty { NO_MEDIA, NO_LOCAL_THUMBS, LOCKED_NOTHING_ON_PHONE, NOT_CONFIGURED, ERROR }` | il titolo è **dati**, il testo lo compone la UI |
| Pianificatore | `interface WidgetPlanner { suspend fun plan(slot: WidgetSlot, size: WidgetSize, budget: Duration): WidgetPlan }`; `class DefaultWidgetPlanner(feed: WidgetFeed, memories: WidgetMemories, visible: VisibleMedia, mask: HiddenMask, thumbs: WidgetThumbs, lock: WidgetLock, prefs: WidgetPrefs, clock: WidgetClock)` | D3–D8; **filtro finale** (`visibleOrThrow` + `hiddenAmongOrThrow`) come ultimo passo; errore = `Message(ERROR)` |
| Porte | `interface WidgetMemories { suspend fun today(): WidgetMemoryPick? }` (dallo scaffale: id, tipo, titolo come dati, copertina, membri; mai `PERSON`); `fun interface WidgetThumbs { suspend fun local(ids: Collection<MediaId>): Set<MediaId> }` (solo chi ha la miniatura sul telefono); `interface WidgetLock { val appLocked: StateFlow<Boolean>; suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId> }`; `interface WidgetPrefs { val choice: StateFlow<WidgetChoice>; val period: StateFlow<WidgetPeriod> }`; `interface WidgetClock { fun nowLocal(): LocalDateTime; fun monotonic(): Long }` | legate da `:app`; null-object = "nessuna foto" (fallire chiusi) |
| Registro | `interface WidgetLedger { suspend fun record(plan: WidgetPlan); suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef?; suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot>; suspend fun forget(slot: WidgetSlot); suspend fun clear() }`; `class FileWidgetLedger(dir)` (temporaneo + `fsync` + rinomina, CRC, versione) | in `noBackupFilesDir/widget/`; un file rovinato = registro vuoto e ridisegno, mai un crollo |
| Ridisegno | `interface WidgetRefresher { fun hiddenChanged(ids: Set<MediaId>); fun shelfChanged(); fun appearanceChanged(); fun appBackgrounded(); fun lockChanged(); suspend fun blankAll(reason: WidgetEmpty) }` | contratto qui, implementazione in `feature-widget`; null-object che non fa niente (nessun widget) |
| Orari | `object WidgetSchedule { fun nextRun(now: LocalDateTime, period: WidgetPeriod): LocalDateTime; fun isQuiet(now: LocalTime): Boolean }` | ore di quiete 23–7 (D6); puro |
| Tocco | `data class WidgetTapRequest(val appWidgetId: Int, val cell: Int)`; `object WidgetTap { fun parse(uri: String?): WidgetTapRequest?; suspend fun resolve(req: WidgetTapRequest, ownSlots: Set<WidgetSlot>, ledger: WidgetLedger, visible: VisibleMedia): WidgetTarget }`; `sealed interface WidgetTarget { Viewer(media); MemoryPage(id); Library(notice: Boolean) }` | D9; `parse` accetta solo `mcg-widget:<int>/<int>` con interi in limiti stretti |

**`core-release` (P0 contratti, P1)**
| Elemento | Firma | Note |
|---|---|---|
| Manifest | `data class ReleaseManifest(val schema: Int, val app: String, val seq: Long, val issued: Instant, val expires: Instant, val minVersionCode: Int?, val blocks: List<VersionBlock>, val models: List<ModelHint>)`; `data class VersionBlock(val fromCode: Int, val toCode: Int, val level: BlockLevel, val message: ReleaseMessage)`; `enum class BlockLevel { WRITES, ALL }`; `enum class ReleaseMessage(val code: String) { UPDATE("update"), SECURITY("security"), DATA_RISK("data") }`; `data class ModelHint(val id: String, val sha256: String, val urls: List<String>)` | D29; `toString()` senza URL; testo solo dal catalogo dell'app |
| Busta | file `MCGR1` = intestazione fissa ‖ lunghezza ‖ JSON canonico ‖ `keyId` (1 byte) ‖ firma DER; `object ManifestCodec { fun decode(bytes: ByteArray): Decoded; fun canonical(m: ReleaseManifest): ByteArray }` | tetti: 64 KiB, 32 blocchi, 16 modelli, 4 URL per modello, 2 KiB per URL; campi sconosciuti **facoltativi** ignorati, campo `critical` sconosciuto = rifiuto |
| Verifica | `class ManifestVerifier(private val keys: ReleaseKeys) { fun verify(bytes: ByteArray, app: String): Verified }`; `sealed interface Verified { data class Ok(val manifest: ReleaseManifest, val keyId: Int) : Verified; data class Rejected(val reason: RejectReason) : Verified }`; `enum class RejectReason { TOO_LARGE, MALFORMED, UNKNOWN_KEY, SIGNATURE, WRONG_APP, SCHEMA, CRITICAL_FIELD }` | firma con `java.security` (`SHA256withECDSA`); confronto della firma in tempo costante non serve (verifica pubblica) |
| Chiavi | `object ReleaseKeys { val K1: PinnedKey; val K2: PinnedKey }`; `data class PinnedKey(val id: Int, val spki: ByteArray, val fingerprint: String)` | D31; le impronte anche in `docs/sicurezza.md`; test: le chiavi del codice coincidono con quelle del documento |
| Stato | `class ReleaseState(store: ReleaseStateStore, version: AppVersion, clock: ReleaseClock) { val status: StateFlow<ReleaseStatus>; suspend fun offer(bytes: ByteArray, source: ManifestOrigin): OfferResult; fun extraUrls(id: String, sha256: String): List<String> }`; `sealed interface ReleaseStatus { data object Normal; data class ReadOnly(val message: ReleaseMessage); data class Stopped(val message: ReleaseMessage) }`; `enum class OfferResult { APPLIED, SAME, ROLLBACK, EXPIRED_KEPT_BLOCKS, REJECTED }` | D30: solo più severo per la stessa `versionCode`; anti-rigioco con `seq`; scadenza con `ReleaseClock.bestNow()` (ora del NAS se c'è) |
| Porte | `interface ManifestSource { suspend fun read(maxBytes: Int): ByteArray? }` (solo il NAS, Q8 (a)); `interface ReleaseStateStore { suspend fun read(): StoredRelease?; suspend fun write(s: StoredRelease) }`; `interface ReleaseClock { fun bestNow(): Instant }`; `data class AppVersion(val code: Int, val name: String, val channel: ReleaseChannel)`; `enum class ReleaseChannel { DEBUG, BETA, STABLE }` | legate da `:app` |

**`core-describe` (P0 contratti, P7)**
| Elemento | Firma | Note |
|---|---|---|
| Fatti | `data class MediaFacts(val kind: MediaKind, val localDay: Int, val time: LocalTime?, val placeId: Int?, val durationMs: Long?, val flags: Int, val groupSize: Int, val similarCopies: Boolean, val presence: PresenceBadge?)`; `MediaFacts.of(snapshot, index, zone)` | gli stessi dell'attuale `GridText.describe`, spostati qui |
| Superfici | `enum class DescribeSurface { IN_APP, HIDDEN_AREA, WIDGET, NOTIFICATION }` | non esiste una superficie "esportazione": per costruzione |
| Descrittore | `interface MediaDescriber { fun describe(id: MediaId, facts: MediaFacts, surface: DescribeSurface): String; fun warm(visible: List<MediaId>) }`; `class DefaultMediaDescriber(places: PlaceNames, people: DescribePeople, text: DescribeText, strings: DescribeStrings, a11y: A11yActivity)` | `describe` è **sincrono** e usa solo dati in memoria; `warm` precarica nomi e prime parole per le celle in vista **solo** con uno strumento di accessibilità attivo (LRU di 500 voci, solo memoria, vuotata al blocco dell'app e in secondo piano) |
| Porte | `interface DescribePeople { suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>> }` (solo persone con nome, non nascoste né dimenticate, Persone attiva qui); `interface DescribeText { suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String> }` (solo lettura del testo attiva per l'account e non esclusa qui); `fun interface A11yActivity { fun toolEnabled(): Boolean }` | null-object = niente nomi, niente testo |
| Regole | `object DescriptionRules { fun facts(surface: DescribeSurface): Set<Fact>; fun cleanText(raw: String, maxWords: Int): String? }` | D22; pure; il testo ripulito non ha controlli, direzioni, a capo |

**`core-models` (P0 firma, P1)**: `interface ExtraModelSources { fun urls(id: ModelId, sha256: String): List<PinnedUrl> }` (null-object vuoto); `PinnedUrl.of` resta l'unico costruttore (host in `ModelHosts`, niente query); `RoutingModelFetcher`: NAS → indirizzi fissati → aggiuntivi; l'impronta decide sempre.

**`core-security` (P4)**: `interface ProcessForeground { val windows: StateFlow<Int>; fun started(token: Any); fun stopped(token: Any) }`; `AppLockController.onAppBackgrounded/Foregrounded` chiamati **solo** al passaggio 1→0 e 0→1 finestre (non per una rotazione: le activity non si ricreano più per la misura, D14, e la ricreazione vera non passa da 0).

**`core-designsystem/adaptive` (P0 firme, P5)**: `enum class GalleryWidth { COMPACT, MEDIUM, EXPANDED, LARGE }`; `data class GalleryWindow(val width: GalleryWidth, val widthDp: Int, val heightDp: Int, val shortHeight: Boolean, val pointer: PointerKind)`; `@Composable fun rememberGalleryWindow(): GalleryWindow`; `sealed interface GalleryPanes { data object Single; data class ListDetail(val listFraction: Float); data class Docked(val gridFraction: Float) }`; `fun Modifier.mediaDragSource(enabled: Boolean, payload: () -> DragAndDropTransferData?): Modifier` (solo il gesto: i dati li dà la porta).

**`feature-integrations/.../drag/` (P6)**: `class DocumentDragPayloads(provider: LibraryMediaDocuments, policy: DocumentPolicy, copies: PreparedCopies, cache: ShareCache) : MediaDragHandler` con `suspend fun prepare(req: DragRequest): DragPrepared`; `sealed interface DragPrepared { data class Ready(val clip: ClipData, val flags: Int, val lease: ShareLease); data class Refused(val reason: DragRefusal) }`; `class DropRouter(stager: IncomingStager, sheet: ReceiveSheetLauncher) { fun accept(event: DragAndDropEvent, target: DropTarget): Boolean }`.

### 1.3 Aggiunte a moduli esistenti

| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-library` | `contract/Drag.kt` (tipi puri), riga `DRAG` del registro come superficie | P0 |
| `core-models` | `ExtraModelSources` (firma e null-object in P0; uso in `RoutingModelFetcher` in P1); nessuna classe di rete nuova (Q8 (a)) | P0, P1 |
| `core-security` | `ProcessForeground`, `AppLockController` per processo, `AppLockGate` per ogni finestra; test con due finestre finte | P4 |
| `core-designsystem` | solo `adaptive/` (file nuovi); nessun componente esistente cambia (il filone grafico non è toccato) | P0, P5 |
| `core-common` | `DiagSection.WIDGET`, `DiagSection.RELEASE`; `Phase10ArchitectureTest` (moduli puri, Glance solo in `feature-widget`, nessun nome di persona nelle firme di `core-widget`, nessuna superficie "esportazione" in `core-describe`, `ReleaseKeys` costruite solo in `core-release`, nessun nuovo pacchetto di rete) | P0 |
| `feature-library` | pannelli (Libreria e viewer affiancati), tastiera e mouse, trascinamento dalla griglia, `GridText.describe` sostituita da `MediaDescriber` | P5, P6, P7 |
| `feature-viewer` | "Affianca", frecce e Esc, azioni di TalkBack, trascinamento dal viewer, descrizione | P5, P6, P8 |
| `feature-map` | mappa e griglia della zona a due pannelli; elenco delle bolle per TalkBack | P5, P8 |
| `feature-albums`, `feature-search`, `feature-memories`, `feature-people` | trascinamento dalle griglie, semantica, dettaglio nelle Raccolte | P6, P8 |
| `feature-settings` | Impostazioni a due pannelli; pagina "Widget" (Q1, Q2, Q3) | P5, P3 |
| `feature-integrations` | `drag/` (sorgente e destinazione) | P6 |
| `feature-diagnostics` | sezioni `WIDGET` e `RELEASE`, codici del §2.13, chiavi in `ExportKeys` | P13 |
| `benchmark` | `WidgetBenchmark`, `PanesBenchmark`, `AccessibilityScrollBenchmark`; scenari `widget`, `pannelli`, `accessibilita` in `soglie.json` | P9 |
| `tools/` | `tools/release/` (chiavi e firma del manifest, `release.ps1`, `recinzioni.json`), `tools/security/` (generatori ostili per `mcg-it/`), `s25.ps1 -Mode widget`, `-Mode a11y`, `-Mode security`; passi nuovi in `check.ps1` | P1, P10, P11, P12 |
| `gradle/` | `verification-metadata.xml` e `versions-app.gradle` (Glance, ATF, Roborazzi a11y), `verify-release-manifest.gradle` (ricevitore del widget e componenti di Glance non esportati) | P3, P8 |

### 1.4 Cosa resta in `:app` (P13, salvo dove detto)

- **`WidgetGraph`** in `app/.../widget/`: nasce senza I/O; porte `WidgetMemories` → scaffale di `MemoriesGraph` (regole di D4); `WidgetThumbs`
  → miniature locali di `core-imaging` (mai il NAS, mai la rete); `WidgetLock` → `AppLockController` più la presenza sul telefono
  dall'istantanea; `WidgetPrefs` → DataStore locale (non sincronizzato: per dispositivo e profilo); `WidgetRefresher` chiamato da
  `GalleryGraph.setHidden`, dal giro dei dati personali (`onHiddenChanged`), da `MemoriesGraph` (scaffale cambiato), dal tema, dal blocco e
  dall'azzeramento. Il lavoro `widget-refresh` si programma solo se `AppWidgetManager` ha almeno un widget nostro.
- **`ReleaseGraph`** in `app/.../release/`: `ManifestSource` sul NAS (lettura con la sessione esistente, nel giro della sincronizzazione,
  file dell'area comune, 64 KiB), `ReleaseStateStore` in `noBackupFilesDir/release/`, `ReleaseClock` sull'ora del NAS; `ReleaseStatus` entra
  come causa nei cancelli di scrittura esistenti (`AppBackupGate`, scritture dell'indice, album, modelli, dati personali, operazioni sui file)
  e nella radice della UI (pagina "Questa versione è stata fermata", avviso "Aggiorna"); `ExtraModelSources` dal `ReleaseState`. Legato da
  P1 per la parte pura e da P13 per i cancelli.
- **`MainActivity`** (P3 per l'azione, P4 per finestre e manifest): azione `OPEN_WIDGET` riconosciuta nella pulizia dell'intent come
  `OPEN_MEMORIES`, consegnata dopo il blocco; `launchMode`, `configChanges`, `<layout>`; "Apri in una nuova finestra" (un solo punto,
  `WindowLauncher`); `EntryHardeningTest` esteso.
- **Descrizioni**: `MediaDescriber` legato una volta (`DescribePeople` → `FacesGraph` con `PeopleVisibility`; `DescribeText` → la replica
  del testo letto con le regole di F6-D41; `A11yActivity` → `AccessibilityManager`), dato ai feature con le loro porte.
- **Trascinamento**: `MediaDragHandler` dalle integrazioni ai feature; destinazioni in Libreria, album, Cartelle NAS.
- **Accessibilità della finestra**: `setAccessibilityDataSensitive` sulla radice di ogni finestra (Android 14+, D24), dopo la prova di P8.
- **Azzeramento** (D42): `WidgetRefresher.blankAll(NOT_CONFIGURED)` per primo, poi lavoro annullato, registro e stato del manifest tolti,
  copie temporanee tolte.

### 1.5 Confine con la fase grafica: stati e contratti

La Fase 10 decide **cosa** si mostra, **quando** e con quali **garanzie di accessibilità**; la fase grafica decide **come** (D38). Composable
fatte solo con i componenti G2 e del kit dell'engine.

| Stato (contratto) | Casi | Dove si vede | Aspetto |
|---|---|---|---|
| `WidgetUiState` | `Memory(cover, cells, title)`, `Photos(cells)`, `Message(reason)`, `Locked(cells)` | widget nelle tre misure | fase grafica (dentro il kit) |
| `PanesState` | `Single`, `ListDetail(list, detail)`, `Docked(grid, viewer)` | Raccolte, mappa, Impostazioni, Libreria con viewer | fase grafica (proporzioni, divisore) |
| `ZoneSideState` | `Closed`, `Open(title, view)` | mappa e griglia della zona | fase grafica |
| `WindowState` | `Primary`, `Secondary`, `TooMany` | finestre | sistema |
| `DragState` | `Preparing(n)`, `Ready`, `Refused(reason)`, `Dropped(target, n)` | trascinamento | fase grafica (ombra, conteggio) |
| `ReleaseUiState` | `Normal`, `UpdateBanner(message)`, `Stopped(message)` | radice dell'app | fase grafica |
| `A11yPrefs` | `highContrast`, `reduceTransparency`, `reduceMotion`, `fontScale`, `toolEnabled` | ovunque | **garanzia**: superfici piene, movimento, testo; la grafica non le toglie |

**Chi tocca cosa**
- La Fase 10 aggiunge a `core-designsystem` **solo** `adaptive/` (file nuovi); il filone grafico non modifica contratti, ViewModel, semantica
  e prove di accessibilità.
- File condivisi a rischio: `MainActivity.kt` (P3 l'azione, P4 il resto, in quest'ordine), `AndroidManifest.xml` di `:app` (solo P4),
  `AppLockController`/`AppLockGate` (solo P4), `GridText.kt` e `GalleryGridCell` (solo P7 per la descrizione, poi P8 per le azioni),
  `ViewerOverlay.kt` (P5 poi P8), `CollectionsTab.kt` (solo P5), `GalleryGraph.kt` e `LocalData.kt` (solo P13), `settings.gradle`,
  `build.gradle`, `engine.properties` (P0 i moduli nuovi, P3 `engine-widget`), `versions-app.gradle` e `verification-metadata.xml` (P3 e P8,
  in quest'ordine), `verify-release-manifest.gradle` (P3 e P4), `tools/check.ps1` (P0, P3, P10, P12, in quest'ordine), `FeatureFlags.kt`
  (**nessuno** nella fase: le costanti cambiano solo nel §2.10, con il sì dell'utente).
- Stringhe in file propri: `strings_widget_*.xml`, `strings_release_*.xml`, `strings_describe_*.xml`, `strings_panes_*.xml`.

## 2. Flussi

### 2.1 Widget: un aggiornamento (10.1; D3–D7; P2, P3)
**Il giro** (`GalleryWidget.provideGlance` → `WidgetPlanner.plan`, uguale per ogni causa; cambia solo chi lo chiama)
| Passo | Cosa | Dettagli |
|---|---|---|
| 1. Chi c'è | `AppWidgetManager.getAppWidgetIds` del nostro ricevitore; nessun widget = niente lavoro e il lavoro periodico si annulla | il registro dimentica i widget tolti (`onDeleted`) |
| 2. Pronto? | istantanea visibile (`LibraryInstant` la pubblica alla nascita del processo dal file della replica), maschera dei nascosti, scaffale; in un processo freddo il giro **monta solo questi tre** e aspetta al più 1,5 s | se non sono pronti entro il budget: si lascia il contenuto già mostrato (era verificato) e si riprova al giro dopo (E-W4); mai un contenuto non verificato. Lezione di NT-1 della Fase 9: il lavoro non si fida di un grafo montato da altri |
| 3. Contenuto | `WidgetChoice` (Q1): ricordo di oggi da `WidgetMemories.today()` (regole di D4) o foto da `WidgetFeed.next(n × 3)` | si chiedono il triplo delle foto che servono: alcune cadranno ai passi 4 e 5 |
| 4. Blocco | con il blocco dell'app attivo (Q3 (c)): restano solo i media **sul telefono** (`WidgetLock.onPhone`), nessun titolo | E-W6 |
| 5. Miniature | `WidgetThumbs.local`: solo chi ha la miniatura sul telefono; si decodificano al più 7 immagini a 384 px (512 nella misura grande) | E-W5; tetto 2 MB per widget (D7) |
| 6. Filtro finale | `visibleOrThrow` e `hiddenAmongOrThrow` sugli id **e** sulla copertina, **subito prima** di consegnare a Glance | un errore = `Message(ERROR)`, nessuna foto (D5) |
| 7. Registro | `WidgetLedger.record(plan)` **prima** di consegnare il disegno | così un tocco trova sempre il media giusto (D9) |
| 8. Disegno | Glance compone `WidgetUiState` con la palette di `engineWidgetPalette` e il budget di `resolveEngineWidgetLayout`; descrizioni di D22 per la superficie `WIDGET` (solo tipo e data) | ogni cella con il suo `PendingIntent` (D9) |

**Quando si ridisegna**
| Causa | Come | Costo |
|---|---|---|
| Lavoro periodico `widget-refresh` (Q2, di base ogni 3 ore, mai 23–7) | `PeriodicWorkRequest` con flessibilità di 30 minuti, vincolo "batteria non scarica"; il passo dentro le ore di quiete esce subito | un giro < 400 ms [stima] |
| L'app va in secondo piano | `WidgetRefresher.appBackgrounded()` dopo 2 s di calma, al più una volta ogni 15 minuti | la Home torna visibile: è il momento utile |
| Nascosto qui, scarto di un ricordo, "Mai nei ricordi" | `hiddenChanged(ids)`: i widget che mostrano quegli id (`WidgetLedger.showing`) si **svuotano subito** (disegno senza immagini), poi giro completo | svuotamento < 100 ms [stima] |
| Nascosto arrivato da un altro dispositivo | stessa chiamata dal giro dei dati personali (`onHiddenChanged`, già usato da `MemoryNotifier.revalidate`) | come sopra |
| Ricordo del giorno nuovo | `shelfChanged()` dalla generazione notturna o dal passo leggero | un giro |
| Tema, accento, modalità scura | `appearanceChanged()` dal flusso delle impostazioni d'aspetto (`distinctUntilChanged().drop(1)`, regola dell'engine) | un giro |
| Blocco dell'app che si accende o si spegne | `lockChanged()` | un giro |
| Azzeramento, perso, uscita | `blankAll(NOT_CONFIGURED)` prima di ogni cancellazione | nessuna immagine |

| Edge case | Comportamento |
|---|---|
| E-W1–E-W5, E-W10–E-W12, E-W15–E-W17, E-W19 | §0.2 |
| E-P10 (risparmio energetico) | il periodico tace; il ridisegno per i nascosti no (è un'azione dell'utente o della sincronizzazione) |
| E-P13 (riavvio, memoria bloccata) | nessun giro prima del primo sblocco (la replica è cifrata dal sistema); i widget mostrano ciò che il launcher ha tenuto |

### 2.2 Widget: privacy, blocco, tocco (D4, D5, D8, D9; P2, P3, P13)
| Aspetto | Comportamento | Perché |
|---|---|---|
| Chi lo vede | chi ha in mano il telefono **sbloccato** e guarda la Home | la Home non ha un blocco suo |
| Cosa non mostra mai | nascosti, persone (nomi, ricordi `PERSON`, copertine con una persona con "Mostra meno"), luoghi nelle descrizioni, screenshot, documenti, immagini animate | D4, D22 |
| Titolo del ricordo | "Un anno fa · Oslo", "Oslo, marzo 2025", "Il meglio di settembre"; mai un nome | come "Titolo e foto" delle notifiche (F9-D16) |
| Con il blocco dell'app (Q3 (c)) | solo media **anche sul telefono**, nessun titolo; nessuna scelta diversa nelle Impostazioni | D8 |
| Schermata di blocco (Q4) | mai: `widgetCategory="home_screen"` | D8 |
| Anteprima nel selettore | disegno fisso negli asset, nessuna foto | E-W18 |
| Tocco su una foto | `OPEN_WIDGET` con `mcg-widget:<id>/<cella>` → `MainActivity` pulisce l'intent → evento interno dopo il blocco → `WidgetTap.resolve` → viewer sul media (con la stessa lista del widget come contesto: solo quel media, niente "successiva") | D9, C17 |
| Tocco sul titolo o sulla copertina del ricordo | la pagina del ricordo (se c'è ancora), altrimenti la pagina Ricordi | come la notifica (F9-D17) |
| Tocco su "Apri MyCloud Gallery" | l'app come dall'icona | — |
| Intent costruito da un'altra app con la stessa azione | validato come sopra: al più apre, dietro il blocco, una foto che il widget mostra già; numeri fuori dai limiti o widget non nostro = si apre la Libreria | E-NT9 come modello |

| Edge case | Comportamento |
|---|---|
| E-W6–E-W9, E-W13, E-W14, E-W18, E-H11 | §0.2 |
| E-X7 (selettore con il blocco attivo) | stesso cancello: prima lo sblocco |

### 2.3 Tablet: i pannelli (D12, D13; P5)
| Schermata | Compatta (< 600 dp) | Media (600–839) | Estesa (840–1199) | Ampia (≥ 1200) |
|---|---|---|---|---|
| Libreria | griglia | griglia più larga (colonne dalla regola di oggi) | griglia | griglia; il viewer si apre a finestra intera **o affiancato** se l'utente ha scelto "Affianca" (griglia 40%, viewer 60%) |
| Viewer | finestra intera, informazioni in un foglio | idem | informazioni in colonna a destra (c'è) | idem; "Affianca" e "Schermo intero" nel menu |
| Raccolte | elenco, poi pagina | idem | elenco e dettaglio (c'è) | elenco e dettaglio |
| Album, Persone, Ricordi | pagina | pagina | dettaglio dentro le Raccolte | dettaglio dentro le Raccolte |
| Mappa | mappa, poi griglia della zona come pagina | idem | **mappa 60% e griglia della zona 40%** quando si tocca una bolla; chiusa = mappa intera | idem |
| Cerca | risultati | risultati | risultati con i filtri in colonna | idem |
| Impostazioni | elenco, poi pagina | idem | **elenco e pagina** | idem |
| Presentazione | finestra intera | finestra intera | finestra intera | finestra intera |

- **Il viewer affiancato**: la griglia resta viva (scorrimento, selezione); un tocco su una cella cambia la foto del viewer senza animazione
  d'apertura; le frecce della tastiera muovono il viewer; "Schermo intero" lo riporta a finestra intera; la scelta "Affianca" si ricorda per
  profilo (DataStore locale).
- **La mappa a due pannelli**: la griglia della zona è la stessa `IndexOrderedTimelineView` viva della Fase 9; il viewer dalla griglia va a
  finestra intera; ruotando in verticale la griglia diventa una pagina e lo stato (bolla aperta, scorrimento) resta.
- Nessuna schermata misura lo schermo: tutte leggono `GalleryWindow` (D12); una finestra libera che attraversa una soglia cambia pannelli
  **senza** ricreare la pagina (E-T2).
- **Esito di P5 (31/10, `docs/decisioni.md` "Fase 10 P5")**: fatto come in tabella. Scostamenti: (1) le schermate dentro un pannello leggono la **finestra del
  pannello** (`rememberGalleryWindow`), chi decide per l'intera finestra (la voce "Apri in una nuova finestra") la **finestra dell'app** (`rememberAppWindow`);
  (2) l'elenco di Raccolte e Impostazioni lo decide `GalleryPaneRules` (`FluidPaneSizes`), non l'engine; (3) nel pannello le pagine di Impostazioni, album,
  persona e ricordo vivono in una navigazione propria (un `NavHost` per pagina scelta): "Indietro" ne chiude il pannello; (4) scorrimento della griglia della zona
  e pagine aperte dentro il pannello **non** passano fra pannello e pagina (si salva la zona aperta, non lo scorrimento); (5) la riga **Cerca** della tabella
  (filtri in colonna da estesa) **non** e' fatta: `feature-search` era fuori dal perimetro di P5.

### 2.4 Rotazione, finestre libere, DeX, due finestre (D14–D18; P4)
| Momento | Comportamento | Dettagli |
|---|---|---|
| Rotazione, ridimensionamento | ricomposizione (`configChanges`), stato in memoria intatto | E-T1, E-T2; MapLibre e il video non ripartono |
| Ricreazione vera (densità, lingua, tema, processo morto) | `rememberSaveable`/`SavedStateHandle`: pagina, elemento ancorato, viewer (media, zoom, posizione), selezione fino a 500 id, pannello, bolla aperta | E-T8, E-T9; prova strumentata con "Non conservare le attività" e `am kill` |
| Finestra libera e DeX | `resizeableActivity`, minimo 320 × 400 dp; puntatore: stati evidenziati, clic destro, rotella; tastiera: frecce, Esc, Invio, Ctrl+A, Canc (→ foglio) | E-T3, E-T10 |
| "Apri in una nuova finestra" | solo da estesa o DeX; `singleInstancePerTask` più `NEW_TASK \| MULTIPLE_TASK \| LAUNCH_ADJACENT`; la seconda finestra parte dalla pagina scelta (Libreria, un album, un ricordo, la mappa) con un evento interno, mai con dati nell'intent | D16; E-T6 per la terza |
| Notifiche, widget, icona | aprono la finestra principale (la prima del processo), come oggi | `singleInstancePerTask` riusa il task esistente se non c'è `MULTIPLE_TASK` [da verificare in P4] |
| Blocco dell'app | per processo: si blocca quando **l'ultima** finestra si ferma; con il blocco attivo ogni finestra mostra la schermata di blocco; uno sblocco vale per tutte | D17, E-T7 |
| Dati | stessi grafi: un cambio in una finestra si vede nell'altra; selezioni e navigazione per finestra; conferme legate al piano | D18, E-T4, E-T5 |
| Nascosti in una finestra | `FLAG_SECURE` e recenti oscurati per quella finestra; l'altra non mostra nascosti | E-T19 |
| Memoria | una mappa sola per processo (la seconda finestra mostra la carta di base finché la prima ha la mappa aperta, o la libera); al più 2 finestre | rischio al §7 |

| Edge case | Comportamento |
|---|---|
| E-T1–E-T10, E-T19 | §0.2 |
| E-P18 (DeX, finestre libere, due istanze) | questa sezione |
| E-P19, E-P20 (profili) | invariati: un profilo è un dispositivo; finestre e pannelli per profilo |

### 2.5 Trascinamento fra app (D20, D21; P6)
**Verso un'altra app**
| Passo | Cosa | Dettagli |
|---|---|---|
| Inizio | pressione prolungata e trascinamento (dito, penna, mouse) su una cella, una selezione, la foto del viewer; mai dai Nascosti | `Modifier.mediaDragSource`; l'ombra è la miniatura locale |
| Preparazione | `MediaDragHandler.prepare(DragRequest)`: tetti di E-X6 (100 elementi, 2 GiB), nascosti esclusi (provider), URI del **provider dei documenti** con id opachi | tutto prima che il dito si muova oltre la soglia; se serve più di 300 ms si mostra "Preparo…" e il trascinamento parte quando è pronto |
| Permessi | `ClipData` con gli URI, `DRAG_FLAG_GLOBAL \| DRAG_FLAG_GLOBAL_URI_READ` (mai scrittura, mai `PERSISTABLE`) | l'app che riceve chiede `requestDragAndDropPermissions`; `CallerGuard` ammette solo il permesso sul singolo URI |
| Lettura di un media del telefono | dal telefono, come il selettore | — |
| Lettura di un media solo sul NAS | **descrittore a richiesta** (`ProxyDescriptors`: letto a pezzi dal NAS, nessun file intero); con "Rimuovi la posizione" una **copia temporanea senza posizione** in `cacheDir/share/<token>/` | E-T11, E-T13 |
| Fine | le copie temporanee vivono finché l'app è in primo piano più 10 minuti (`ShareHolder`) e comunque **non oltre il prossimo avvio** (`ShareCache`) | E-T14 |

**Dentro l'app**
| Passo | Cosa | Dettagli |
|---|---|---|
| Destinazioni | Libreria ("Salva sul NAS"), un album ("Aggiungi ad album"), una cartella di Cartelle NAS | evidenziate durante il trascinamento |
| Rilascio | `requestDragAndDropPermissions(event)`; i flussi si **copiano subito** in `noBackupFilesDir/incoming/` con `IncomingStager` (tetti, tipo dai byte, rifiuto di URI nostri e `file://`) | C18, E-T15 |
| Conferma | il **foglio della ricezione** con il conteggio e la destinazione; niente parte senza un tocco | D21; con il blocco attivo prima lo sblocco |
| Album condivisi | si offrono solo quelli dove l'utente può aggiungere | E-T16 |

| Edge case | Comportamento |
|---|---|
| E-T11–E-T18 | §0.2 |
| E-X1, E-X2, E-X5, E-X6, E-X7 | come la Fase 5 |

### 2.6 Descrizioni dei media (D22; P7)
| Superficie | Tipo e data | Ora | Luogo | Persone | Testo letto | Stato (preferita, solo sul NAS…) |
|---|---|---|---|---|---|---|
| Dentro l'app (griglie, viewer, ricordi, mappa, ricerca, album) | sì | sì (se coerente) | sì | sì, con Persone attiva qui, solo con nome, al più 3 | sì, con la lettura attiva, prime 12 parole | sì |
| Nascosti (dentro l'area protetta) | sì | sì | sì | sì | sì | sì |
| Widget | sì | no | no | no | no | no |
| Notifica | no (il testo della notifica basta) | no | no | no | no | no |
| Log, Diagnostica, esportazione | **non esiste** una superficie | — | — | — | — | — |

- **Quando si calcola**: solo quando il lettore di schermo chiede il nodo (la semantica della cella la costruisce su richiesta, come oggi);
  con uno strumento di accessibilità attivo `warm` precarica nomi e prime parole delle celle in vista (LRU di 500, solo memoria); senza,
  nessun costo (E-AC1).
- **Esempi**: "Video, 3 agosto 2025, 18:40, Lisbona, 1 minuto e 12 secondi, con Anna e Marco"; "Foto, 12 marzo 2024, con la scritta
  «Biglietto ferroviario Firenze…», preferita, solo sul NAS"; nel widget "Foto, 12 marzo 2024".
- **Mai**: nomi di file, percorsi, id, coordinate, nomi di persone nascoste o dimenticate, testo letto se la lettura è spenta o esclusa qui.

### 2.7 TalkBack e accessibilità, schermata per schermata (D23–D27; P8)
| Schermata | Cosa deve esserci (oltre alle regole generali di D23) |
|---|---|
| Libreria | griglia come raccolta; intestazioni dei giorni come intestazioni; azioni personalizzate della cella; pizzico sostituito da "Livello successivo/precedente"; selezione annunciata ("12 selezionati") |
| Viewer | azioni Successiva, Precedente, Informazioni, Preferito, Condividi, Elimina (→ foglio); controlli del video con stato; descrizione della foto all'apertura; il testo dal vivo leggibile come testo |
| Raccolte, album, Persone | intestazioni delle sezioni; righe con nome e conteggio; azioni di modifica senza trascinamento ("Sposta su", "Sposta giù") |
| Ricordi e presentazione | niente avanzamento automatico con TalkBack (c'è); ogni foto descritta; Pausa, Avanti, Indietro |
| Mappa | "Elenco dei luoghi in vista" con le bolle in ordine di numero; ogni bolla "12 foto, Firenze"; la griglia della zona come la Libreria |
| Cerca | campo con etichetta; suggerimenti come elenco; risultati con conteggio annunciato una volta |
| Backup, NAS, Diagnostica | stati in `liveRegion` educata; numeri letti per esteso; "Ferma le scritture sul NAS" con la conseguenza nel nome |
| Onboarding, famiglia, QR | ogni passo con titolo come intestazione; codici letti a gruppi; il QR con un'alternativa testuale (c'è il percorso "Senza QR") |
| Fogli di conferma | il titolo dice cosa succede e quanto ("Elimina 12 elementi dal telefono"); il pulsante distruttivo non è il primo fuoco |
| Widget | ogni cella "Foto, 12 marzo 2024"; titolo del ricordo letto; "Apri MyCloud Gallery" come azione |
| Ovunque | bersagli ≥ 48 dp; testo fino a 2,0x senza tagli; contrasto AA nel caso peggiore; superfici piene con contrasto alto o meno trasparenza; nessun movimento non essenziale con le animazioni ridotte; fuoco visibile da tastiera in DeX |

| Edge case | Comportamento |
|---|---|
| E-AC1–E-AC12 | §0.2 |
| E-MEM19 (presentazione con TalkBack) | invariato |

### 2.8 Manifest firmato (10.5; D29–D31; P1, P13)
**Il percorso**
| Passo | Chi, dove | Dettagli |
|---|---|---|
| Chiavi (una volta) | l'admin, sul PC, con `tools/release/ManifestTool keys` (JVM, usa `core-release`, nessuna dipendenza nuova) | genera K1 e K2 (P-256), salva le private in un PKCS#12 con frase segreta in `%USERPROFILE%\.mycloudgallery\release\`, stampa le due impronte e il foglio di K2 da tenere offline; le pubbliche vanno in `ReleaseKeys` con un commit (P1) |
| Scrittura | l'admin (o l'orchestratore che prepara il testo, mai la firma), in `release/manifest.json` nel repository | un JSON leggibile senza segreti (il manifest è pubblico per natura); `seq` lo mette lo strumento |
| Firma | l'admin, sul PC: `ManifestTool sign` chiede la frase segreta, rifiuta un `seq` non maggiore dell'ultimo firmato, firma con K1, **riverifica** il file con il verificatore dell'app e scrive `release/manifest.mcgr` | gli agenti firmano solo con la chiave di prova (`KT`), mai con K1 o K2 |
| Pubblicazione | l'admin, dal PC sulla rete di casa: copia in `<area comune>/.mycloudgallery/release/manifest.mcgr` (nome fisso, scrittura atomica: temporaneo e rinomina); solo sul NAS (Q8 (a)) | la famiglia di prova usa `test-comune`; la vera usa l'area comune della famiglia, **solo** con la release |
| Lettura | ogni dispositivo, nel giro di sincronizzazione esistente, al più una volta ogni 24 ore (e all'avvio se è passato di più): un file, ≤ 64 KiB, con la sessione già aperta | nessuna connessione nuova; se il NAS non risponde, si riprova al giro dopo |
| Verifica | `ManifestVerifier.verify` → `ReleaseState.offer` | rifiuti contati per motivo (§2.13); l'ultimo valido resta |
| Effetti | `ReleaseStatus` entra nei cancelli di scrittura e nella radice della UI; `ExtraModelSources` dà gli indirizzi aggiuntivi | tabella sotto |

**Che cosa può fare e che cosa no**
| Campo | Effetto sull'app | Che cosa **non** può fare |
|---|---|---|
| `minVersionCode` | sotto: sola lettura e avviso "Aggiorna dal Pampa Store" | cancellare, bloccare la Libreria locale, chiedere password |
| `blocks` `WRITES` | come sopra, con il messaggio scelto dal catalogo | testo libero, link, azioni |
| `blocks` `ALL` | solo la pagina "Questa versione è stata fermata" (Diagnostica ed esportazione restano), nessuna sessione del NAS, nessun lavoro, nessun traffico | cancellare dati, fare l'azzeramento, rivelare qualcosa |
| `models` | indirizzi `https` in più per un `ModelId` **con lo stesso SHA-256** del catalogo, su host di `ModelHosts` | aggiungere un modello, cambiare un'impronta, aggiungere un host, saltare la verifica |
| `expires` | scaduto: gli indirizzi aggiuntivi decadono | togliere un blocco già applicato |
| tutto il resto | ignorato (campi facoltativi) o rifiuto (campi marcati `critical`) | — |

**Le chiavi nell'app**
- Release: **K1, K2**. Debug e test: in più **KT**, la chiave di prova (privata nelle risorse di test, mai in un APK di release:
  `check.ps1` cerca la sua impronta nel dex di release e fallisce se c'è).
- Una chiave sconosciuta = `UNKNOWN_KEY`. Le impronte stanno anche in `docs/sicurezza.md`; un test le confronta.

| Edge case | Comportamento |
|---|---|
| E-REL1–E-REL9, E-REL13 | §0.2 |
| E-S7 (manifest manomesso) | firma non valida = ignorato per intero; non può far eseguire codice né accettare un modello diverso (nessun campo lo permette) |
| E-S16 (redirect fuori dagli host dei modelli) | invariato: gli indirizzi aggiuntivi passano dallo stesso `PinnedUrl.of` e dalle stesse regole dei redirect |

### 2.9 Catena di rilascio: firma, versioni, canali, changelog, checklist (D32–D34; P12)
**Preparazione (una volta, dall'utente, circa 15 minuti, §8)**
| Cosa | Come | Dove resta |
|---|---|---|
| Chiave dell'APK | **nessuna chiave nuova** (D32): si firma con `pampa.jks`, già usata dalle altre app Pampa; l'utente controlla solo che la **copia offline** ci sia e si apra | dove sta oggi, con le voci `pampa.*` in `local.properties` (mai nel repository) |
| Chiavi del manifest | `ManifestTool keys` (§2.8): **le sole due chiavi nuove**, K1 e K2 | `%USERPROFILE%\.mycloudgallery\release\`; foglio di K2 offline |
| GitHub | 2FA attiva, codici di recupero offline, token del publisher a grana fine (solo `Casual76/MyCloud-Gallery`, solo "contenuti", scadenza 90 giorni), ramo `main` protetto | gestore di password |
| Impronte | impronta SHA-256 del certificato di `pampa.jks` e di K1, K2 in `docs/sicurezza.md` §5 (nuovo §5.6) | repository |

**Una release** (`tools/release/release.ps1 -Version 1.0.0 -Channel beta|stable`; tutto dal PC, niente rete tranne la pubblicazione)
| Passo | Controllo | Fallisce se |
|---|---|---|
| 1. Albero | `git status` pulito, sul tag `v<versione>` di `main`, `engine` pulito (`engine-doctor`) | modifiche locali, tag mancante |
| 2. Recinzioni | `tools/release/recinzioni.json` dice lo stato atteso di D31, D12, D30, D34; `check.ps1` lo confronta con il bytecode | uno stato diverso da quello dichiarato |
| 3. Prove | `check.ps1` verde; `check.ps1 -Bench` con un riferimento S25 di al più 7 giorni dentro le guardie | rosso |
| 4. Build | `assembleRelease` con `pampa.jks` (voci `pampa.*`); `versionCode` maggiore di ogni versione già pubblicata (letta dal file `release/versions.txt`) | `versionCode` riusato o più basso |
| 5. APK | `apksigner verify --print-certs -v`: schemi v2 e v3, impronta uguale a quella di `pampa.jks` scritta nel documento; manifest dentro l'APK: non debuggable, componenti e permessi dell'elenco chiuso; nessun resto di debug, di misura, di `KT`; librerie native a 16 KB | qualunque differenza |
| 6. Changelog | la sezione della versione esiste in `CHANGELOG.md` e nelle risorse dell'app | manca |
| 7. Resoconto | `build/release/<versione>/`: APK, SHA-256, impronta, dimensione, elenco delle dipendenze con le impronte, mapping di R8 (resta sul PC) | — |
| 8. Prova a secco | il publisher del Pampa Store senza caricare (se lo permette [da verificare in P12]; altrimenti solo il controllo del suo formato) | — |
| 9. Pubblicazione | **solo alla fine di tutte le fasi**, avviata dall'utente con la skill del Pampa Store: prima `beta` (dispositivi dell'admin) per **almeno due settimane**, poi `stable` per **tutta la famiglia insieme** (Q7 (a)) | — |
| 10. Dopo | se serve, un manifest con `minVersionCode` nuovo (§2.8); nota in `docs/decisioni.md` | — |

**Checklist di rilascio** (stampata da `release.ps1`, spuntata dall'orchestratore e dall'utente)
- [ ] Le Fasi 1–10 e la fase grafica chiuse con i loro "Fatto quando"; voci delle revisioni chiuse o dichiarate (§2.11).
- [ ] Le prove sul campo in sospeso fatte o dichiarate "non bloccanti" una per una dall'utente.
- [ ] Recinzioni nello stato che l'utente ha deciso (§2.10: il passo unico fatto, o rinviato) e `recinzioni.json` coerente.
- [ ] `pampa.jks`: copia offline verificata (si apre), impronta del certificato nel documento; il limite L559 letto dall'utente.
- [ ] Chiavi del manifest K1 e K2, foglio offline di K2, un manifest vuoto firmato e letto da un dispositivo di prova.
- [ ] GitHub con 2FA, token a grana fine valido, ramo protetto.
- [ ] `docs/sicurezza.md` rivisto per intero, §1 per la famiglia; condiviso con la famiglia (e il padre dell'utente).
- [ ] Account di prova `test` e `test2` tolti dal NAS o con password lunghe; share di prova svuotate o tenute (decide l'utente).
- [ ] `docs/sicurezza.md` §9 "Da fare prima della Fase 1": stato di ogni voce scritto.
- [ ] Tabelle della promessa 15 trattate; database delle release alla v6 (nessuna migrazione distruttiva).
- [ ] Changelog in italiano semplice; versione e canale giusti.
- [ ] APK installato sull'S25 e sul tablet (profilo dell'admin) dal Pampa Store in `beta`; aggiornamento da una beta precedente provato
      (stessa firma).
- [ ] **Due settimane** di `beta` sui dispositivi dell'admin (Q7 (a)) senza voci bloccanti aperte (congelamenti, errori di scrittura,
      crolli); poi `stable` per **tutta la famiglia insieme**.
- [ ] Il giorno della famiglia preparato: un QR per dispositivo dall'S25, l'admin approva ogni dispositivo nella stessa sessione, il
      `docs/sicurezza.md` §1 già condiviso.

| Edge case | Comportamento |
|---|---|
| E-REL10–E-REL12, E-REL14 | §0.2 |
| E-P8 (aggiornamento durante un lavoro) | ripresa dal punto salvato |

### 2.10 Passaggio alle share vere (D35; Q6 (b); P10 per gli strumenti; il passo lo fa l'orchestratore con il sì dell'utente)
**Risposta Q6 (b) del 31/10: tutto insieme, dopo le prove.** Le quattro recinzioni si spengono **in un solo passo**, non una alla volta;
restano tutte le protezioni del passo: la "Simulazione del passo" in sola lettura prima, la fotografia delle share prima e dopo, il sì
dell'utente, i soli dispositivi dell'admin, il ritorno indietro con "Ferma le scritture sul NAS".

**Quando**: dopo le prove della Fase 10 (F160–F189 fatte o dichiarate "non bloccanti" una per una, la chiusura del §4.5), in parallelo alla
fase grafica (Q5 (a), D38), sui soli dispositivi dell'admin. La famiglia riceve l'app solo dopo, con il canale `stable`, tutta insieme
(Q7 (a), §2.9).

**Prima del passo**
1. Le prove sul campo **di tutte e quattro le aree superate** sulle share di prova (elencate sotto) e le voci delle revisioni di quelle aree
   chiuse o dichiarate (§2.11).
2. **Simulazione** sulle share vere, in sola lettura, con la release candidata sull'S25 (account vero): Diagnostica → Sviluppo →
   "Simula il passo" (solo nella build dell'admin, sola lettura) dice **per ognuna delle quattro aree** **che cosa** scriverebbe, **quanti**
   file e byte, **in quali cartelle**, e lo spazio libero del NAS dopo il passo intero; nessun nome di file nell'esportazione (solo numeri e
   cartelle di primo livello del formato).
3. **Fotografia "prima"** dal PC (`tools/release/share-snapshot.ps1`, sola lettura, con le credenziali di Windows dell'utente): elenco dei
   file di **tutte** le share vere toccate (`alessio`, l'area comune) con dimensione e data. **La fotografia contiene i NOMI di tutti i file
   dell'area privata** (correzioni C, I6, 02/11): resta sul PC dell'admin, su un disco fisso (lo script rifiuta un'uscita su un percorso di rete,
   su un'unita' di rete o su una chiavetta, e guarda il percorso vero), mai in una share, nel cloud o in un messaggio, e si cancella (anche dal
   cestino) a confronto letto e passo chiuso, non oltre 7 giorni; lo stesso vale per il resoconto del confronto. Lo script lo ricorda a fine lavoro (L576).
4. **Il sì dell'utente** al passo intero (Q6 (b)), con i numeri della simulazione davanti; numeri inattesi per un'area = il passo non parte
   (E-SV1): l'utente decide se rinviarlo o tornare, con un nuovo sì, a un'area alla volta.
5. Una build con **le quattro costanti** cambiate in `FeatureFlags`, `recinzioni.json` aggiornato con **un solo consenso** (data e rimando
   alla riga di `docs/decisioni.md`), installata **solo** sui dispositivi dell'admin (S25 e il suo profilo del tablet).

**Le quattro aree del passo** (cambiano insieme; la tabella dice cosa controllare per ciascuna)
| Area | Recinzione | Che cosa comincia a scrivere | Prove prima (share di prova) | Controllo dopo | Che cosa resta se si torna indietro |
|---|---|---|---|---|---|
| Indice e dati personali | **F4-D31** (`indexPublishTestSharesOnly`) | segmenti dell'indice e prenotazioni in `.mycloudgallery/` delle share vere (`alessio`, area comune), dati personali nell'area privata | F1–F20 (4A/4B), F64 (Fase 6), F114 e F129 (dati personali dei ricordi) | fotografia "dopo": **niente** cambiato fuori da `.mycloudgallery/` per quest'area; Diagnostica: segmenti rifiutati 0 | i file in `.mycloudgallery/` (indice e dati personali): innocui, li ignora chi non li conosce; si possono togliere dal PC (solo quella cartella) |
| Backup | **F5-D12** | copie dei media in `alessio/Backup/<dispositivo>/AAAA/MM/` e `Versioni/` | F21–F45 (Fase 5: backup, "Libera spazio", versioni) | `VERIFICA-COMPLETA` su un campione di 200 media (impronte uguali); niente fuori da `Backup/`, `Versioni/`, `.mycloudgallery/`; il primo backup vero a tappe di notte (E-SV3) | le copie caricate (additive, mai sovrascritte); nessun media del telefono toccato |
| Dati dei volti | **F8-D34** (scrive solo se l'utente usa Persone) | `faces/` nell'area privata | F94–F110 (Fase 8), F100–F103 ("Disattiva e cancella") | nessun file fuori da `faces/` e `.mycloudgallery/`; nomi solo nell'area privata | niente, dopo "Disattiva e cancella" (toglie anche dal NAS) |
| Album condivisi | **F7-D30** | definizioni firmate e **spostamenti** nell'area comune vera (solo quando l'utente condivide un album) | F72–F93 (Fase 7), F84–F92 in particolare | il primo album condiviso sulla share vera è **un album di 3 foto di prova** dell'utente, controllato dal PC: l'album è nell'area comune, le sorgenti nel cestino del NAS (30 giorni), conteggi uguali | i file spostati restano nell'area comune finché non si riportano ("Smetti di condividere" con "Riporta nelle mie cartelle", proposta 6) |

**Dopo il passo**
- **Fotografia "dopo"** dal PC e confronto con la "prima": file aggiunti, cambiati e tolti, **per area**, fuori dalle cartelle attese di
  ciascuna = E-SV2 (si ferma tutto e si torna indietro). Le due fotografie e il resoconto si cancellano a passo chiuso (punto 3 sopra).
- **Due settimane** d'uso normale dei dispositivi dell'admin (sono anche le due settimane di `beta` di Q7 (a) quando la release candidata è
  quella finale) con il riepilogo giornaliero pulito (nessun congelamento, nessun errore di scrittura); lo scenario 8 del §2.14 si misura qui.
- **Gli account di prova** restano utili fino alla fine delle prove; poi la checklist (§2.9).
- **Scritture scelte dall'utente** (12/11/2026, il giorno del passo): «Salva sul NAS» (anche il trascinamento; F5-D12, cancello `IncomingGate`)
  scrive nella cartella scelta dall'utente, nell'area privata o comune; «Smetti di condividere» → «Riporta nelle mie cartelle» (F7-D30, cancello
  `AlbumWriteGate`) riporta i propri media alla loro cartella d'origine. Sono fuori dalle cartelle attese, ma dopo un tocco e una conferma: un loro uso
  fra le due fotografie dà il codice 3 **senza essere un'anomalia**, e si riconosce dai percorsi (quelli scelti dall'utente o la cartella d'origine dei
  suoi media). Fino alla fotografia "dopo" meglio non usarle (nota anche in `recinzioni.json`, `scrittureSceltedallUtente`).

**Indietro** (comune alle quattro aree): subito "Ferma le scritture sul NAS" (c'è: chiude ogni cancello di scrittura), poi una build con le
quattro costanti di nuovo vere (o solo quella dell'area che ha sbagliato, se la fotografia la isola e l'utente lo decide), `recinzioni.json`
aggiornato; ciò che è stato scritto resta (colonna "Che cosa resta").

- **`check.ps1`**: per ogni recinzione, con lo stato "accesa" verifica che il cancello ci sia e chiami la decisione (come oggi); con lo stato
  "spenta" verifica che la decisione esista e valga "spenta" **solo** se `recinzioni.json` lo dichiara, con la data e il rimando alla riga di
  `docs/decisioni.md` del consenso (uno solo per le quattro, Q6 (b)).

| Edge case | Comportamento |
|---|---|
| E-SV1–E-SV7 | §0.2 |
| E-I e E-B del piano | invariati: il passaggio non cambia le regole, toglie solo il limite alle share di prova |

### 2.11 Revisione finale di sicurezza 10.4: le prove pratiche (D36; P11)
**Dove girano**
- **JVM**: a ogni `check.ps1`, nessun dispositivo.
- **PC sul NAS di prova**: il banco dal PC con `nas-it.properties` dell'utente, **solo** dentro `mcg-it/<giro>/` delle share di prova (guardia
  esistente, F4-D30); usa il codice vero dell'app come libreria JVM.
- **Famiglia di prova sul NAS**: la famiglia fondata dall'S25 di debug con `test` (area comune `test-comune`); gli strumenti di
  `tools/security/` scrivono file ostili **solo** nelle share di prova (`TestShares`), mai altrove; l'app li incontra come li incontrerebbe
  dal vero.
- **Emulatore** (`test`, acceso solo per la prova e spento subito dopo) e **S25** (`test`; build debug e **release candidata**).

| # | Voce del piano | Prova pratica (ripetibile) | Dove | Che cosa dimostra | Prova sul campo |
|---|---|---|---|---|---|
| S1 | Un account non legge dati privati altrui tramite l'indice | (a) proprietà JVM: in 10.000 mondi casuali nessun dato ricavato da un file privato finisce nell'indice di una share comune (regola M3), `ForeignSharesTest`; (b) `tools/security/cross-account.ps1`: `test` pubblica l'indice di file privati con **segni unici** (testo letto "MCG-PRIV-<casuale>", impronte note); dal PC con le credenziali di `test2` si legge **tutto** ciò che `test2` può leggere e si cercano i segni; (c) l'app sull'emulatore con `test2` cerca i segni | JVM, PC, emulatore | zero segni leggibili da `test2`; zero risultati in Cerca | F170 |
| S2 | Dati firmati da dispositivi non approvati o revocati rifiutati | `tools/security/rogue-device`: un dispositivo con chiave mai approvata e uno **revocato** dall'admin della famiglia di prova scrivono segmenti, dati personali e definizioni d'album nelle share di prova | JVM (core-trust), famiglia di prova, emulatore | nessun dato accettato (contatori di rifiuto in Diagnostica), nessuna voce nella Libreria | F171 |
| S3 | Un segmento ostile (`..`, record enormi, immagini malformate) non fa danni | (a) mutazioni JVM sui codec (100.000 per codec, corpus nel repository); (b) segmenti ostili nelle share di prova: percorsi `..`, assoluti, UNC, record oltre i tetti, UTF-8 rotto, firme su contenuti diversi; (c) immagini malformate in `test-comune/mcg-ostile/`: bombe di decompressione, HEIC troncati, PNG da 50.000 × 50.000, EXIF ciclici | JVM, PC, emulatore, S25 | rifiuto contato, nessun file creato fuori dalle cartelle dell'app, nessun crollo, memoria sotto il tetto | F172 |
| S4 | Un dispositivo perso viene azzerato | l'S25 (admin della famiglia di prova) segna perso l'emulatore; l'emulatore si ricollega | emulatore | ordine: chiave distrutta, poi dati (C15, C79); dopo: nessun file in `files/`, `no_backup/`, database; widget vuoto; notifiche tolte | F173 |
| S5 | Backup Android e Smart Switch escludono i dati | `bmgr backupnow` con il trasporto locale; trasferimento fra dispositivi simulato con il trasporto locale in modalità "device transfer" [da verificare in P11]; lettura delle regole di `data_extraction_rules.xml` dall'APK | emulatore | insieme di backup vuoto per l'app; nessuna chiave, replica o database trasferiti | F174 |
| S6 | Nessun log in release contiene dati personali | release candidata sull'S25 con `test` e **dati seminati riconoscibili** (nomi, luoghi, testo letto, nomi di file, coordinate) per 30 minuti di uso guidato; `logcat -d -b all` e l'esportazione della Diagnostica cercati da uno script | S25 | zero occorrenze; nel dex nessun `LogSink` | F175 |
| S7a | Un modello manomesso viene rifiutato | sullo scaffale dei modelli della famiglia di prova un file col nome giusto e byte diversi; un indirizzo aggiuntivo del manifest di prova verso un file diverso | JVM (`ModelChaosMutationTest`), emulatore | rifiutato e tolto (`AI_MODEL_MISMATCH`), sorgente dopo; nessun file accettato | F176 |
| S7b | Un manifest manomesso viene rifiutato | manifest di prova (`KT`): valido, firma rotta, altra app, troppo grande, `seq` vecchio, campo `critical` ignoto, firmato con una chiave sconosciuta; sulla **release** anche un manifest firmato con `KT` | JVM (proprietà su 10.000 buste mutate), emulatore, S25 | solo il valido cambia lo stato; blocco `WRITES` = nessuna scrittura (cattura SMB: nessuna scrittura); `ALL` = nessuna sessione; un rigioco non toglie un blocco; `KT` ignorata in release | F177 |
| S8 | Su una rete estranea non parte nessuna autenticazione SMB | (a) JVM su C2; (b) l'emulatore (rete non di casa: altro gateway, nessun prefisso del FRITZ!Box) con `-tcpdump`, l'app configurata e il NAS raggiungibile via NAT; (c) l'S25 su un'altra rete con PCAPdroid | JVM, emulatore, S25 | nessun `SESSION_SETUP` SMB2 (nessun NTLMSSP) verso nessuno; stato "Rete non riconosciuta" | F178 |
| S9 | Una modifica di massa simulata fa scattare il congelamento | `tools/security/mass-change.ps1` in `test-comune/mcg-it/<giro>/`: 2.000 file di prova, poi ne rinomina, cambia o "cifra" (`.locked`) oltre la soglia max(50, 5%) | PC, emulatore | congelamento al primo giro (C11), nessuna scrittura automatica, avviso; "Ho controllato" scongela solo con la doppia conferma | F179 |
| S10 | (Fase 10) Il widget non mostra nascosti e non apre altro | proprietà JVM (mondi casuali, nascosti dell'ultimo momento, da un altro dispositivo); intent `OPEN_WIDGET` inventati; widget sulla schermata di blocco | JVM, S25, emulatore | §2.1–§2.2 | F161, F163 |
| S11 | (Fase 10) Il trascinamento non apre più del dovuto | un'app di prova (solo debug) che riceve un trascinamento e prova a leggere un **altro** documento, a scrivere, a tenere il permesso **(correzioni C, I2 della revisione di sicurezza, 02/11: l'app di prova ha lo stesso UID dell'app, quindi prova solo la lettura e la consegna, non i permessi: vedi L575)** | emulatore, tablet | un URI letto, nessun altro; nessuna scrittura; copie tolte al riavvio | F168 |
| S12 | (Fase 10) Solo gli strumenti di accessibilità leggono | un servizio di accessibilità di prova **non** "strumento" (solo debug, installato con adb) legge l'albero della finestra | emulatore | nessun testo né descrizione letti (Android 14+) | F185 |
| S13 | (Fase 10) Traffico e componenti della release | cattura di 24 ore della release candidata (PCAPdroid se c'è; altrimenti `netstats` per UID); `dumpsys package` per i componenti esportati e i permessi | S25 | host solo fra NAS, `tiles.openfreemap.org` (mappa accesa), host dei modelli (solo se si scarica), Google (solo con la lettura del testo accesa); nessun host per il manifest (Q8 (a)); componenti = elenco chiuso | F180, F181 |
| S14 | (Fase 10) Recinzioni e resti di debug | `check.ps1` sul bytecode della release: recinzioni come `recinzioni.json`, nessun `KT`, nessuno strumento di debug, nessun `ManifestTool` | PC | — | — |

**Le voci aperte delle revisioni** (promessa 18): P11 scrive in `docs/review/10-chiusura.md` una riga per **ogni** voce ancora aperta delle
revisioni delle Fasi 1–9 (sicurezza e funzionali): "chiusa da <commit> con <test>", "dichiarata con L<n>" o "non un difetto, perché…"; le
intestazioni dei documenti di revisione si allineano allo stato vero (es. `5b-fase5b.md`). Nessun rilascio con una voce "Alta" o "Blocca"
aperta.

**Come si lancia**: `tools/security/run-all.ps1 -Livello jvm|pc|emulatore|s25` scrive un resoconto di soli numeri in
`build/security/<data>/`; il livello `s25` gira con `s25.ps1 -Mode security` (build debug) e `-Release` (release candidata, senza strumenti
di debug: i semi si preparano dal PC e i controlli sono esterni all'app).

### 2.12 Nascosti su ogni superficie nuova (D41; P2, P3, P6, P7, P5)
| Superficie | Riga | Fonte | Prova |
|---|---|---|---|
| `widget`: celle, copertina, titolo, svuotamento, ridisegno | `WIDGET` (da "di prova" a **produzione**) | `WidgetFeed`, scaffale, filtro finale a ogni disegno, `WidgetRefresher.hiddenChanged` | proprietà sul mondo casuale con nascosti dell'ultimo momento e da un altro dispositivo; F161 |
| `drag-out`: sorgente del trascinamento | `DRAG` (nuova) | provider dei documenti (nascosti esclusi), nessuna sorgente nei Nascosti | proprietà, F168 |
| `descriptions`: descrizioni per superficie | `DESCRIPTIONS` (nuova) | `MediaDescriber` con `DescribePeople` sopra `PeopleVisibility` | proprietà: mai una persona nascosta, mai una descrizione di un nascosto fuori dai Nascosti |
| `panes` e seconda finestra | `PANES` (nuova) | stesse viste vive delle pagine singole (nessuna copia) | proprietà come `album-viewer` |

Il registro fallisce da solo se un file nuovo che nomina `VisibleMedia`, `HiddenMask` o `WidgetFeed` non è elencato (regola di P9 della
Fase 7); `WIDGET_CONTENT_FILE` passa dal file di debug a quello di `feature-widget` (il widget di prova resta in debug come strumento).

### 2.13 Diagnostica e codici (P13)
| Codice | Quando | Messaggio e azione |
|---|---|---|
| `WIDGET_STATE` | voce di `DiagSection.WIDGET`: widget per misura, ultimo aggiornamento (minuti fa), frequenza scelta | — |
| `WIDGET_SKIPPED` | media saltati per mancanza di miniatura locale o per il blocco dell'app (conteggi) | "Scegli la qualità Standard" se molti |
| `WIDGET_BLANKED` | svuotamenti per nascosti, scarti, azzeramento (conteggi per causa) | — |
| `WIDGET_LATE` | aggiornamenti periodici mancati negli ultimi 7 giorni (Doze, risparmio) | — |
| `WIDGET_ERROR` | giro finito in errore (codice) | — |
| `RELEASE_STATE` | voce di `DiagSection.RELEASE`: versione, canale, `seq` e giorni dal manifest, stato (`Normal`, `ReadOnly`, `Stopped`), indirizzi aggiuntivi (numero) | — |
| `RELEASE_MANIFEST_REJECTED` | manifest rifiutato, per motivo | un `SIGNATURE` o `UNKNOWN_KEY` diverso da zero: "Segnala all'admin" |
| `RELEASE_BLOCKED` | blocco in vigore (livello e codice del messaggio) | "Apri il Pampa Store" |
| `DRAG_OUT`, `DRAG_IN` | trascinamenti (conteggi), rifiuti per motivo (tetti, posizione, NAS) | — |
| `DRAG_TEMP_CLEANED` | copie temporanee tolte all'avvio | — |
| `WINDOW_STATE` | finestre aperte, classe di larghezza, pannelli | — |

**Mai** nella Diagnostica e nell'esportazione: id, titoli, descrizioni, nomi, testo letto, URL dei modelli, contenuto del manifest oltre ai
numeri, e **se un servizio di accessibilità è attivo** (dato sulla salute di una persona: non si registra). Le chiavi nuove entrano
nell'elenco chiuso di `ExportKeys`.

### 2.14 Verifica complessiva: gli otto scenari del §10 del piano (D37; P14)
**Dispositivi**: S25 (`test`, debug; e la release candidata con l'account vero per lo scenario 8), **tablet con due profili di prova**
("Prova 1" con `test`, secondo dispositivo di `test`; "Prova 2" con `test2`, il familiare), emulatore (`test`), NAS con la famiglia di prova
(`test`, `test2`, `test-comune`).

| # | Scenario (piano §10) | Come si esegue | Chi | Tempo dell'utente | Quando |
|---|---|---|---|---|---|
| 1 | Il tablet fuori casa carica 50 foto; il telefono a casa le indicizza di notte scaricando solo le anteprime | il profilo "Prova 1" sulla Wi-Fi ospiti del FRITZ!Box (o sull'hotspot di un altro telefono) con WireGuard del suo profilo; 50 foto sintetiche della Fase 7 spinte con adb in `mcg-prova`; backup; di notte l'S25 (in carica, a casa) indicizza; al mattino il verificatore dal PC e il registro del traffico dell'S25 | agenti | 3 minuti (mettere il tablet su quella rete la sera) | dopo M2 |
| 2 | Una notte con il NAS spento | l'utente spegne il NAS la sera dal suo pannello (o lascia fare all'orario, se c'è) e lo riaccende al mattino; i dispositivi provano il risveglio dalla LAN se il NAS lo supporta, altrimenti lavorano in locale e lo dicono nel riepilogo | utente + agenti | 2 minuti | la stessa sera dello scenario 1 |
| 3 | Un familiare configurato da QR e approvato dall'admin | sul profilo "Prova 2" "Entra con QR" dal QR dell'S25; l'utente digita la password di `test2`; l'approvazione sull'S25 la conferma l'agente (dialogo della nostra app) | utente + agenti | 5 minuti | momento A |
| 4 | Album smart "Oslo 2026" creato, condiviso e visto dall'altro profilo | dai viaggi sintetici della Fase 7 (GPS di Oslo): creato sull'S25, condiviso in `test-comune` (la recinzione F7-D30 lo permette), visto sul profilo "Prova 2" | agenti | 0 | dopo lo scenario 3 |
| 5 | "Libera spazio", poi ricerca e apertura di una foto liberata | sull'S25 con `mcg-prova` (recinzione F5-D13); il dialogo di sistema solo per i file di prova, come nelle prove della Fase 5; poi Cerca e viewer della foto ora solo sul NAS | agenti | 0 | quando c'è il telefono |
| 6 | Dispositivo segnato perso e azzerato; recupero dell'admin con il kit | F173 per la prima metà; poi un emulatore nuovo recupera l'admin della **famiglia di prova** con il kit (il codice lo digita l'utente) | agenti + utente | 5 minuti | momento C, **ultimo** degli scenari sulla famiglia di prova |
| 7 | Segmento ostile e modifiche di massa simulate | F172 e F179 | agenti | 0 | quando c'è il telefono |
| 8 | Una settimana d'uso normale con i consumi confrontati con le stime del preset | sulla release candidata con l'**account vero**, dopo il passo unico del §2.10 (Q6 (b): con il backup vero acceso, altrimenti non c'è lavoro da misurare): `batterystats` e il registro dei consumi dell'app all'inizio e alla fine, confronto con la stima del preset (±30%) | agenti | 0 (usare il telefono come sempre) | la prima settimana dopo il passo unico del §2.10 |

**I tre momenti dell'utente** (tutto il resto gira da solo, di notte o quando il telefono è libero)
- **Momento A, tablet (circa 25 minuti, una volta)**: creare i profili "Prova 1" e "Prova 2" se non ci sono (Android chiede il proprietario),
  installare la build con adb (agente), digitare le password di `test` e `test2`, importare il WireGuard di prova nel profilo "Prova 1",
  lasciare il tablet collegato al PC con il cavo per la notte; in quel collegamento girano da sole F165–F169, F182–F184 sul tablet, F123 e
  F124 della Fase 9 e i momenti con il tablet ancora in sospeso delle Fasi 6, 7 e 8.
- **Momento B, una sera (circa 5 minuti)**: tablet sulla rete "fuori casa", NAS spento; al mattino NAS riacceso.
- **Momento C (circa 10 minuti)**: il codice del kit della famiglia di prova (scenario 6) e, se vuole, 5 minuti per ascoltare con TalkBack tre
  schermate (F182, facoltativo).
- **Prima delle prove** (una volta, §2.9 e §8): creare le due chiavi del manifest K1 e K2 con `ManifestTool keys` e il foglio di K2 (circa
  10 minuti) e controllare che la copia offline di `pampa.jks` si apra (circa 5 minuti); **nessuna chiave dell'APK nuova** (D32).
- **Il passo unico del §2.10** (Q6 (b)), dopo le prove: **un solo sì** con i numeri della simulazione delle quattro aree davanti (circa
  10 minuti, invece dei quattro sì di prima); poi usare i propri dispositivi come sempre per due settimane (Q7 (a)).
- In più, al rilascio: leggere `docs/sicurezza.md` §1 (circa 15 minuti, con il limite L559 della chiave condivisa) e **il giorno della
  famiglia** (Q7 (a)): tutti i familiari insieme, un QR per dispositivo dall'S25 e l'approvazione dell'admin per ognuno (circa 10 minuti per
  dispositivo [stima]), dopo le due settimane di `beta`.

### 2.15 Stima dei costi e prestazioni attese
| Voce | Valore [stima] | Note |
|---|---|---|
| Un aggiornamento del widget | < 400 ms di CPU sull'S25, ≤ 7 decodifiche di miniature locali, nessun I/O di rete | Tab S9 ~1,5 volte |
| Widget in un giorno | ~6 giri periodici + ~5 all'uscita dall'app ≈ 5 s di CPU | ben sotto il 2% di batteria al giorno del piano |
| Memoria del widget | ≤ 2 MB di bitmap per widget nel launcher | tetto D7 |
| APK | Glance e `engine-widget` +0,5–1 MB; nessun'altra dipendenza nell'APK | ATF e Roborazzi solo nei test |
| Seconda finestra | +40–80 MB di PSS (viewer, griglia); con la mappa nell'altra finestra la seconda mostra la carta di base | guardia `memoria` |
| Descrizioni | zero senza strumenti di accessibilità; con TalkBack ≤ 1 ms per nodo a cache calda, `warm` ≤ 20 ms per una schermata | E-AC1 |
| Manifest | una lettura di ≤ 64 KiB al giorno sulla sessione esistente; verifica < 5 ms | — |
| Trascinamento di un media solo sul NAS | descrittore: nessuna copia; con "Rimuovi la posizione" una copia del file (tempo del NAS) | tetti di E-X6 |

## 3. Alternative scartate

| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Tecnica del widget | `RemoteViews` della piattaforma; Glance con `engine-widget` | **Glance con il kit** (D10): "stile Fluid" e tema seguito dal kit; `RemoteViews` resta il piano B senza download |
| Quanti widget | due tipi ("Ricordi" e "Foto"); uno con l'impostazione; uno con una schermata di configurazione per ogni widget | **Uno, con l'impostazione** (D3, Q1): una schermata di configurazione è un'activity in più avviata dal launcher; due tipi confondono nel selettore |
| Aggiornare il widget | `updatePeriodMillis` (minimo 30 minuti, sveglia il telefono); WorkManager periodico; solo all'uscita dall'app | **WorkManager con le ore di quiete più l'uscita dall'app** (D6): rispetta Doze e batteria; il ridisegno per i nascosti è immediato a parte |
| Nascosti nel widget | filtrare solo al disegno; svuotare subito e ridisegnare | **Svuotare subito** (D5): il disegno vecchio vive nel launcher finché non lo sostituiamo |
| Miniature del widget | scaricarle dal NAS se mancano; solo locali | **Solo locali** (D7): il widget non deve consumare dati né aspettare il NAS (piano 10.1) |
| Widget con l'app bloccata | come sempre; niente foto; solo media del telefono | **Q3 (c)** (risposta del 31/10), "solo media del telefono": non mostra più di quanto il telefono mostri già |
| Tocco su una foto | apre l'app; deep link con l'id; azione fissa con due numeri validati | **Azione fissa con due numeri** (D9): nessun id che arrivi da fuori; un estraneo apre al più una foto già sulla Home |
| Classi di finestra | `material3-adaptive` / `androidx.window`; utilità nostra | **Utilità nostra** (D12): soglie semplici, nessun download, una regola per tutti i test |
| Libreria e viewer | sempre affiancati sul tablet; mai; a scelta da "ampia" | **A scelta** (D13): la foto vuole lo spazio; l'affiancamento serve in DeX con il mouse |
| Rotazione | ricreare l'activity e salvare lo stato; gestire i cambi di misura | **Gestire i cambi di misura e salvare comunque lo stato** (D14): niente ripartenze di MapLibre e del video, e la ricreazione vera resta provata |
| Più istanze | nessuna; illimitate; due | **Due** (D16): il piano le chiede; due bastano e tengono la memoria sotto controllo |
| Blocco dell'app con più finestre | per finestra; per processo | **Per processo** (D17): una finestra bloccata accanto a una sbloccata non protegge niente |
| Trascinamento in uscita | file in cache con `FileProvider`; URI del provider dei documenti | **Provider dei documenti** (D20): id opachi, nascosti esclusi, descrittore a richiesta e copie senza posizione già provati; un `FileProvider` copierebbe sempre l'intero file |
| Trascinamento in arrivo | salvare subito; foglio con conferma | **Foglio con conferma** (D21): niente parte senza un tocco, come la condivisione |
| Descrizioni | quelle di oggi per schermata; un modulo unico; descrizioni del contenuto stimate dai vettori | **Modulo unico con i fatti** (D22): stesse regole su ogni superficie; il contenuto stimato può sbagliare in modo offensivo ed è un'idea per dopo |
| Servizi di accessibilità | niente; `FLAG_SECURE` ovunque; `accessibilityDataSensitive` | **`accessibilityDataSensitive`** (D24): `FLAG_SECURE` ovunque toglierebbe gli screenshot all'utente e non ferma i servizi di accessibilità |
| Controllo automatico dell'accessibilità | solo test nostri sui nodi; ATF; Accessibility Scanner sul telefono | **Nostri test più ATF** (D28): ATF è indipendente e gira sulla JVM; Accessibility Scanner va installato dal Play Store sul telefono (resta facoltativo) |
| Kill switch | `engine-config` (manifest del Pampa Store, non firmato); manifest nostro firmato; nessuno | **Manifest nostro firmato** (D29): un JSON non firmato con testi liberi e override darebbe il controllo delle app a chi controlla il file |
| Dove sta il manifest | GitHub; NAS; entrambi | **Solo il NAS** (D29, Q8 (a) del 31/10): nessun host nuovo; chi non raggiunge mai il NAS non può fare danni al NAS |
| Testo dei messaggi del manifest | libero; catalogo chiuso nell'app | **Catalogo chiuso**: un testo libero sarebbe un canale per ingannare la famiglia ("inserisci qui la password") |
| Chiavi del manifest | una; due con una di riserva offline | **Due** (D31): una chiave persa non lascia la famiglia senza kill switch |
| Firma dell'APK | `pampa.jks` condivisa; chiave dedicata subito; chiave dedicata dopo, con la rotazione | **`pampa.jks`** (D32, decisione dell'utente del 31/10; il progetto raccomandava la dedicata): una chiave sola per tutte le app Pampa; il rischio è il limite L559; una chiave dedicata resta possibile dopo con la discendenza v3 |
| Aggiornamenti | dentro l'app (`engine-update`, `REQUEST_INSTALL_PACKAGES`); dal Pampa Store | **Dal Pampa Store** (D33): nessun permesso nuovo, nessun installatore dentro un'app che ha le chiavi del NAS |
| Recinzioni | tutte insieme; una alla volta; un interruttore nell'app | **Tutte insieme dopo le prove, costanti nella build** (D35, Q6 (b) del 31/10; il progetto raccomandava una alla volta): un passo solo, con simulazione per area, fotografie, sì dell'utente e ritorno indietro; un interruttore nell'app si sbaglia o si manomette |
| Revisione 10.4 | una lettura del codice; prove pratiche ripetibili | **Prove ripetibili** (D36): una prova fatta una volta non dice niente sulla release successiva |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
P0 fondamenta, contratti, recinti, documenti ──┬── P1 manifest firmato, chiavi, strumenti (opus) ───────────┬── P12 catena di rilascio ──┐
                                               ├── P2 politica del widget ── P3 widget Glance (download Q9) ─┤                            │
                                               ├── P4 finestre, DeX, blocco per processo ── P5 pannelli ─────┼── P6 trascinamento ──────────┤
                                               ├── P7 descrizioni dei media ── P8 TalkBack e prove a11y (Q9) ┤                            │
                                               ├── P10 strumenti per le share vere ──────────────────────────┤                            │
                                               ├── P11 revisione 10.4, prima parte (S1–S9) (opus) ───────────┤                            │
                                               └── P9 benchmark (dopo P3, P5, P8) ───────────────────────────┴── P13 integrazione ─────────┤
                                                    P14 prove sul campo e scenari del §10 + P11 seconda parte (S10–S14, voci aperte)  ── chiusura
                                                    ── check.ps1 (-Bench) + security-review + verifica funzionale ── §2.10 con il sì dell'utente
```
- **Agenti insieme**: di base **due** (un opus e un sonnet), come deciso dall'utente il 30/10; fino a quattro solo se si aspettano comandi
  lenti. **Nessun pacchetto usa sotto-agenti.** Ondate consigliate: (1) P0; (2) P1 + P2; (3) P11 prima parte + P4; (4) P7 + P3 (dopo Q9);
  (5) P5 + P8; (6) P6 + P10; (7) P12 + P9; (8) P13; (9) P14 + P11 seconda parte; poi le revisioni.
- **Gli scaricamenti di P3 e della parte ATF di P8 sono approvati** (Q9 (a), 31/10): li fanno P3 (Glance 1.1.1 e i suoi transitivi) e P8
  (ATF e `roborazzi-accessibility-check` 1.76.0, solo test), non P0. Il piano B di P3 (`RemoteViews`, dietro lo stesso `WidgetUiState`)
  resta solo se Glance non passa i suoi controlli.
- **P4 prima di P5**: le finestre e `GalleryWindow` cambiano le stesse schermate; **P5 prima di P6**: il trascinamento parte da celle e viewer
  già a pannelli; **P7 prima di P8**: TalkBack legge le descrizioni.
- **P11** parte presto con le voci del piano (S1–S9) che non dipendono dalla Fase 10 e finisce dopo P13 con S10–S14 e la chiusura delle voci
  aperte delle revisioni.
- **P13** integra; **P14** gira le prove sul campo e gli scenari del §10 nei momenti del §2.14.
- **Il passaggio alle share vere** (§2.10) **non è un pacchetto**: lo fa l'orchestratore, **in un solo passo dopo le prove** (Q6 (b)), con
  gli strumenti di P10 e il sì dell'utente, in parallelo alla fase grafica (D38).

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | Sull'S25 (debug, `test`): il widget nelle tre misure con il ricordo di oggi o le foto; un "Nascondi" lo svuota subito; un nascosto dall'emulatore sparisce al giro dei dati personali; il tocco apre la foto dietro il blocco; una giornata di aggiornamenti contati | F160–F164 | P0, P2, P3 (+ integrazione minima) |
| **M2** | Sul tablet nel momento A: pannelli, rotazione e ridimensionamento senza perdite, DeX con mouse e tastiera, due finestre con il blocco per processo, trascinamento verso un'app di prova e da Archivio verso un album | F165–F169 | + P4, P5, P6 |
| **M3** | TalkBack su ogni schermata (UI Automator), testo 2,0x, contrasto alto e meno trasparenza, movimento ridotto, servizio non "strumento" che non legge niente; prove sui nodi e ATF verdi | F182–F185, `check.ps1` | + P7, P8 |
| **M4** | Batteria 10.4 verde; manifest di prova valido, manomesso, rigiocato, blocchi; release candidata firmata con `pampa.jks`, controllata e installata; prova a secco della pubblicazione | F170–F181, F186, F187 | + P1, P10, P11, P12, P13 |
| Chiusura | Gli otto scenari del §10; benchmark su S25 e Tab S9; revisione di sicurezza e verifica funzionale; checklist (§6.3) | F188, F189, `check.ps1 -Bench`, `security-review`, verifica funzionale | P9, P14 |

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2–9)
- **Mai** toccare file fuori dal proprio perimetro; i file condivisi del §1.5 solo dai pacchetti indicati e nell'ordine indicato.
- **Nessun sotto-agente** (regola dell'utente): ogni pacchetto è fatto da un agente solo.
- **Rete**: nessun codice HTTP/TLS nuovo fuori dai due pacchetti elencati; **nessuna dipendenza** fuori dall'elenco approvato (§8); nessuno
  scaricamento durante i test.
- **Recinzioni**: nessun pacchetto cambia `FeatureFlags` (§2.10). Le prove distruttive solo sulle share di prova e dentro `mcg-it/<giro>/`.
- **Chiavi**: nessun pacchetto genera, legge o usa le chiavi vere (APK, K1, K2); i test usano `KT` e un keystore di prova generato nel test;
  `check.ps1` (passo 3) cerca chiavi private nel repository.
- **Nessun dato personale** nel repository, nei test, nei log, negli screenshot e nei report: Librerie sintetiche, immagini di prova delle fasi
  precedenti, nomi di prova.
- **Nascosti**: ogni superficie nuova si registra in `HiddenSurfaces` con il test "un nascosto perfetto non esce mai" (D41).
- **Accessibilità**: ogni schermata toccata ha i test sui nodi (D28) e gli screenshot a 1,0, 1,3 e 2,0 di testo, chiaro e scuro, telefono e
  tablet; dopo P8 il controllo ATF è obbligatorio per ogni schermata nuova.
- **Ora**: orologio monotono per budget e durate; ora locale per le ore di quiete del widget; ora del NAS per la scadenza del manifest.
- **Log**: mai id, titoli, nomi, descrizioni, testo letto, URL, contenuto del manifest; `toString()` sicuri.
- **Stabilità**: registro del widget e stato del manifest con scrittura atomica e il test della morte del processo a ogni salvataggio.
- **Fake fedeli con test di parità** (§5.2).
- Stringhe in italiano in file propri. Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo,
  **modifiche richieste ai contratti degli altri**.

### 4.4 I pacchetti
"Agente": **interno** per tutti, **senza sotto-agenti**; chi tocca dispositivi, NAS di prova o scaricamenti lo dice nella colonna "Campo".

| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Campo |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Moduli, contratti, recinti, documenti | `settings.gradle`, `build.gradle` (`galleryModules`); creazione di `core-widget`, `core-release`, `core-describe`, `feature-widget` (build, manifest vuoto, `contract/`, `noop/`, `testing/`, `NoNetworkSourcesTest`, `NoWallClockSourcesTest`); **contratti** del §1.2 con null-object, fake ed esecutori di riferimento (`InMemoryWidgetLedger`, `ReferenceWidgetPlanner`, `InMemoryReleaseState`, `ReferenceDescriber`); firme in `core-library` (`contract/Drag.kt`), `core-models` (`ExtraModelSources` e null-object), `core-designsystem` (`adaptive/` solo firme), `core-common` (`DiagSection.WIDGET`, `RELEASE`, `Phase10ArchitectureTest`); `tools/check.ps1` (Glance solo da `feature/widget/`, nessuna chiave privata, nessun `KT` nella release); documenti del §0.3 | Fase 9 in `main` (P12 e i due pacchetti di correzione possono finire in parallelo: nessun file in comune salvo `check.ps1`, che va per ultimo) | `check.ps1` verde; ogni test d'architettura fallisce reintroducendo la violazione (classe Android in un modulo puro; `core-widget` che nomina `core.faces` o una persona; `core-release` che nomina `java.net`; una superficie "esportazione" in `core-describe`; Glance fuori da `feature-widget`; un pacchetto di rete nuovo); gli esecutori di riferimento passano le suite di contratto (§5.2); documenti aggiornati (§0.3) | **opus** | no |
| **P1 Manifest firmato e chiavi** | D29–D31, §2.8 | `core-release/**` (codec, verifica, stato, chiavi), `core-models` (uso di `ExtraModelSources` in `RoutingModelFetcher`; nessuna classe di rete, Q8 (a)), `tools/release/ManifestTool` (JVM: `keys`, `sign`, `verify`, `seq`), `release/manifest.json` di prova, le impronte in `docs/sicurezza.md` (bozza, le vere le scrive l'utente) | P0 | proprietà su 10.000 buste mutate: **solo** una busta intatta e firmata da K1, K2 (o `KT` in debug) cambia lo stato; anti-rigioco; blocchi solo più severi per la stessa `versionCode` (proprietà su storie casuali di manifest); scadenza con l'ora del NAS; indirizzi aggiuntivi solo per `ModelId` e SHA-256 del catalogo e host di `ModelHosts` (test con host simili, IP, porte, query); catalogo chiuso dei messaggi; lo strumento rifiuta un `seq` non crescente e riverifica ciò che firma; `KT` assente dal dex di release; morte del processo a ogni salvataggio dello stato | **opus** | no (PC) |
| **P2 Politica del widget** | D3–D9, §2.1–§2.2 (parte pura) | `core-widget/**` (`plan/`, `ledger/`, `schedule/`, `WidgetTap`) e i fake delle porte | P0 | sul mondo casuale (Libreria sintetica da 100 mila, nascosti, nascosti dell'ultimo momento, gruppi, ricordi `PERSON`, media senza miniatura, blocco attivo) il piano **non contiene mai** un nascosto, un ricordo `PERSON`, un nome, un media senza miniatura locale; con il blocco solo media del telefono; budget e tetti di D7; registro atomico con la morte del processo; `WidgetTap.parse` rifiuta tutto ciò che non è `mcg-widget:<int>/<int>` nei limiti; ore di quiete e periodi di Q2; < 50 ms per un piano sulla JVM | sonnet | no |
| **P3 Widget Glance** | D3–D11, §2.1–§2.2 (Android) | `feature-widget/**` (widget, ricevitore **non esportato**, `WidgetRefreshWorker`, `GlanceWidgetRefresher`, bitmap locali, anteprima disegnata), `engine.properties` e `settings.gradle` (`engine-widget`), `versions-app.gradle` e `verification-metadata.xml` (Glance), `verify-release-manifest.gradle` (componenti di Glance non esportati), `app/.../MainActivity.kt` (solo l'azione `OPEN_WIDGET`) ed `EntryHardeningTest`, `feature-settings` (pagina "Widget"), la riga `WIDGET` di `HiddenSurfaces` in produzione | P0, P2; Glance 1.1.1 e i transitivi **approvati il 31/10** (Q9 (a)): li scarica P3, con le impronte; piano B solo se Glance non passa i controlli | widget nelle tre misure con la palette del kit e nessun colore scritto a mano (`engine-doctor`, `NoHandWrittenStyleTest`); manifest fuso: nessun componente esportato nuovo; `widgetCategory` solo Home; svuotamento < 100 ms dopo `hiddenChanged`; tocco con `PendingIntent` immutabile ed esplicito e solo i due numeri (Robolectric); intent inventati rifiutati; un processo freddo non mostra mai un contenuto non verificato (test con il grafo non montato: la lezione di NT-1); peso dell'APK prima e dopo; screenshot `10-P3` (chiaro e scuro, tre misure, testo 1,3x, contrasto alto) | sonnet | sì (F160–F164) |
| **P4 Finestre, DeX, due finestre** | D14–D18, §2.4 | `app/src/main/AndroidManifest.xml` (`launchMode`, `configChanges`, `<layout>`, `resizeableActivity`), `app/.../MainActivity.kt` (dopo P3), `app/.../window/` (`WindowLauncher`, "Apri in una nuova finestra"), `core-security/.../lock/` (`ProcessForeground`, `AppLockController`, `AppLockGate`) e i loro test, la tastiera e il mouse nelle schermate di base (Libreria, viewer), `verify-release-manifest.gradle` (dopo P3) | P0 | prova strumentata: rotazione e ridimensionamento senza ricreazione; ricreazione forzata e morte del processo con lo stato ripristinato (§2.4); due finestre con lo stesso dato vivo; terza finestra rifiutata; blocco per processo (test con due finestre finte: si blocca solo quando l'ultima si ferma, uno sblocco vale per tutte, nessun contenuto con il blocco attivo); notifiche e widget che riaprono la finestra principale; `singleInstancePerTask` verificato su emulatore Android 16 e S25 (o piano B dichiarato); metadati di DeX solo se verificati sul Tab S9 | sonnet (revisione opus mirata nella `security-review`) | sì (F166, F167) |
| **P5 Pannelli del tablet** | D12, D13, §2.3 | `core-designsystem/.../adaptive/**` (implementazione), le schermate che passano a `GalleryWindow` (`CollectionsTab.kt`, `ViewerOverlay.kt`, `OnboardingHost.kt`, `NasFoldersScreen.kt`), Libreria e viewer affiancati (`feature-library`, `feature-viewer`), mappa e griglia della zona (`feature-map/.../map/`, `grid/`), Impostazioni a due pannelli (`feature-settings`), dettaglio di album, Persone e Ricordi nelle Raccolte | P4 | ogni schermata del §2.3 a 360, 600, 840 e 1280 dp (Roborazzi) senza sovrapposizioni; attraversare una soglia non perde stato (test di composizione); "Affianca" ricordato per profilo; mappa e griglia con la stessa vista viva; righe `PANES` del registro; screenshot `10-P5` | sonnet | sì (F165) |
| **P6 Trascinamento** | D20, D21, §2.5 | `feature-integrations/.../drag/**`, la porta `MediaDragHandler` nelle griglie e nel viewer (`feature-library`, `feature-albums`, `feature-search`, `feature-memories`, `feature-map`, `feature-viewer`: solo il modificatore e la porta), le destinazioni (Libreria, album, Cartelle NAS) verso il flusso di ricezione, l'app di prova "MCG prova (riceve)" **solo debug** (`app/src/debug/`), la riga `DRAG` del registro | P5 | URI solo del provider, permessi solo di lettura e non persistenti; tetti di E-X6; nascosti e Nascosti mai (proprietà); media solo sul NAS con il descrittore e, con "Rimuovi la posizione", con una copia senza GPS (fallire chiusi); copie tolte al riavvio; trascinamento in arrivo solo dal foglio con conferma, con i tetti di C18; l'app di prova assente dal dex di release; screenshot `10-P6` | sonnet | sì (F168, F169) |
| **P7 Descrizioni dei media** | D22, §2.6 | `core-describe/**`, le porte `DescribePeople` e `DescribeText` (contratti e fake), `feature-library/.../grid/GridText.kt` (la descrizione passa a `MediaDescriber`), le descrizioni del viewer, degli album, dei ricordi, della mappa e della ricerca (solo la stringa), la riga `DESCRIPTIONS` del registro | P0 | stessi testi di oggi per i fatti di oggi (test di non regressione su `GridText`); persone e testo letto **solo** alle condizioni di D22 (proprietà: Persone spenta, esclusa, persone nascoste o senza nome, lettura spenta o esclusa = niente); superficie `WIDGET` solo tipo e data; nessuna superficie di esportazione (architettura); testo ripulito (controlli, direzioni, a capo) e 12 parole; `warm` solo con uno strumento attivo; < 1 ms per descrizione a cache calda | sonnet | no |
| **P8 TalkBack e prove di accessibilità** | D23–D28, §2.7, §5.3 | semantica di ogni schermata dei `feature-*` (azioni personalizzate, intestazioni, raccolte, annunci, fuoco), elenco delle bolle della mappa, `accessibilityDataSensitive` (con il servizio di prova **solo debug**), contrasto alto e meno trasparenza (lettura delle impostazioni, superfici piene), testo 2,0x; i test sui nodi (`*AccessibilityTest` per modulo) e il controllo ATF con `roborazzi-accessibility-check`; `versions-app.gradle` e `verification-metadata.xml` (ATF, dopo P3) | P7; ATF e `roborazzi-accessibility-check` 1.76.0 **approvati il 31/10** (Q9 (a)), solo test: li scarica P8, con le impronte | ogni schermata del catalogo degli stati passa i test sui nodi e ATF (nessun errore; avvisi motivati per iscritto); bersagli ≥ 48 dp misurati; testo 2,0x senza tagli (screenshot); contrasto ≥ 4,5:1 nel caso peggiore con il contrasto alto; movimento ridotto su ogni animazione; il servizio non "strumento" non legge niente su Android 14+ (o piano B dichiarato); screenshot `10-P8` | sonnet | sì (F182–F185) |
| **P9 Benchmark e fluidità** | §5.4 | `benchmark/src/**` (`WidgetBenchmark`, `PanesBenchmark`, `AccessibilityScrollBenchmark`), `benchmark/soglie.json` (scenari `widget`, `pannelli`, `accessibilita`), `app/src/benchmarkRelease/**` (seme del widget e dello scaffale), `docs/bench/README.md` | P3, P5, P8 | i tre scenari **esistono e girano** su emulatore (prova breve) e S25; le guardie delle Fasi 3–9 non peggiorano con due finestre e con TalkBack acceso; riferimento S25 registrato; Tab S9 nel momento A | sonnet | sì (F188) |
| **P10 Strumenti per le share vere** | D35, §2.10 (Q6 (b): un solo passo) | `tools/release/recinzioni.json` (lo stato atteso di ognuna delle quattro recinzioni e **un solo consenso** per il passo unico: data e rimando alla riga di `docs/decisioni.md`), i passi delle recinzioni in `tools/check.ps1` (due stati, dopo P0 e P3), `tools/release/share-snapshot.ps1` (sola lettura, confronto prima e dopo, **per area**), la "Simulazione del passo" (solo nella build dell'admin e in debug, sola lettura) in `feature-diagnostics` e `:app/.../release/`, **per le quattro aree in un giro solo**, il testo per l'utente nel §2.10 | P0 | con ogni recinzione accesa `check.ps1` è verde come oggi; con le quattro recinzioni dichiarate spente in `recinzioni.json` (prova su un ramo di prova, **mai** in `main`) il controllo accetta solo le decisioni che valgono "spenta" e il rimando al consenso, e uno stato misto solo se `recinzioni.json` dichiara un ritorno indietro parziale; la simulazione non scrive niente (test con un NAS finto che fallisce a ogni scrittura) e dà numeri e cartelle per ognuna delle quattro aree; la fotografia distingue file aggiunti, cambiati e tolti fuori dalle cartelle attese di ogni area | sonnet | sì (PC, sola lettura sulle share vere, con il sì dell'utente) |
| **P11 Revisione finale di sicurezza** | D36, §2.11 | `tools/security/**` (generatori ostili, dispositivo canaglia, manifest manomessi, cambi di massa, `run-all.ps1`), `s25.ps1 -Mode security`, i test JVM nuovi delle voci S1–S14, `docs/review/10-chiusura.md` (riconciliazione delle voci aperte) | P0; P1 (S7b); P3, P4, P6, P8 (S10–S14) | ogni voce S1–S14 ha una prova ripetibile e il suo esito; ogni strumento scrive **solo** nelle share di prova e dentro `mcg-it/<giro>/` (guardia con test); ogni voce aperta delle revisioni chiusa con un test o dichiarata con un limite; resoconto di soli numeri | **opus** | sì (F170–F181) |
| **P12 Catena di rilascio** | D32–D34, §2.9 | `app/build.gradle` (firma con **`pampa.jks`**, D32: **nessuna chiave nuova**, le voci `pampa.*` di oggi; schemi v2 e **v3**, v1 solo se il publisher la pretende; `versionCode` e `versionName`), `tools/release/release.ps1` (i controlli del §2.9 restano tutti, con l'impronta del certificato di `pampa.jks`), `release/versions.txt`, `CHANGELOG.md`, la pagina "Novità di questa versione", `docs/sicurezza.md` §1 riscritto per la famiglia e §5.6 (impronte), la promessa 15 | P1 | `release.ps1` su un keystore di prova generato nel test: tutti i controlli del §2.9 (un APK con un'altra chiave, debuggable, con un resto di debug, con `versionCode` riusato fa fallire); changelog nelle risorse; prova a secco del publisher; nessuna chiave vera toccata dall'agente (né `pampa.jks` né K1, K2) | sonnet | no (PC) |
| **P13 Integrazione** | §1.4 | `app/**` (tranne i file di P3, P4, P6 e P10), `feature-diagnostics/**`, `README.md`, `docs/sicurezza.md`, `docs/decisioni.md`, `docs/modello-dati.md`, `docs/formato-nas.md` | P1–P12 | `WidgetGraph`, `ReleaseGraph`, descrittore e trascinamento legati; `ReleaseStatus` in **ogni** cancello di scrittura (test: un blocco `WRITES` ferma backup, indice, album, modelli, dati personali, operazioni sui file; `ALL` non apre la sessione); widget ridisegnato dal giro dei dati personali (test d'integrazione con due dispositivi finti); azzeramento completo (widget vuoti, lavoro annullato, registro e stato tolti); Diagnostica e `ExportKeys`; registro dei Nascosti verde; `check.ps1` verde; APK debug e release | sonnet | sì (F164, F186, F187) |
| **P14 Prove sul campo e scenari del §10** | §2.14, §5.5 | `docs/prove-campo.md` (sezione Fase 10), `tools/chaos/s25.ps1` (modi `widget`, `a11y`, `tablet`), i resoconti in `docs/prove-campo-esiti.md` | P13 | F160–F189 eseguite o dichiarate con il motivo; gli otto scenari con l'esito; il tempo dell'utente usato davvero (da confrontare con il §2.14) | sonnet | sì |

> Nota sui crediti: opus per 3 pacchetti su 15, quelli che il brief indica come fondanti o difficili: **P0** (contratti e recinti), **P1**
> (il manifest firmato: l'unica cosa che da fuori cambia il comportamento di tutte le app installate) e **P11** (la revisione finale con le
> prove pratiche). P4 tocca il blocco dell'app ma con test chiari: sonnet, con una revisione opus mirata nella `security-review`.

### 4.5 Chiusura della fase
- `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro le guardie delle Fasi 3–9 e negli scenari nuovi (§5.4); F160–F189 superate o
  dichiarate.
- `security-review` (opus) con revisione mirata di `core-release`, `ManifestTool`, `ReleaseStatus` nei cancelli, `MainActivity` (azione del
  widget, finestre), `AppLockController`, trascinamento e permessi, `accessibilityDataSensitive`, manifest fuso; **in parallelo un agente di
  verifica funzionale** (§5.6).
- Checklist della Fase 10 spuntata (§6.3); `docs/sicurezza.md` aggiornato; screenshot approvati; risposte del §9 registrate.
- Poi, con il sì dell'utente: il passaggio alle share vere (§2.10) e la fase grafica (Q5); il rilascio alla fine di tutte le fasi (§2.9).

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-widget`, `core-release`, `core-describe` | piano del widget, registro, tocco; manifest, firme, stato; descrizioni per superficie |
| Libreria sintetica | `SyntheticLibrary` con nascosti, gruppi, presenze (telefono, NAS), miniature locali sì/no, persone finte, testo letto finto | 100 mila elementi per proprietà e prestazioni |
| Robolectric (SDK 35) | Compose dei pannelli e delle schermate, `MainActivity` e intent, widget (Glance si prova con `GlanceAppWidgetUnitTest`), blocco con due finestre finte, test sui nodi di accessibilità | stati, contenuti, semantica |
| Roborazzi | ogni schermata toccata a 360, 600, 840, 1280 dp; testo 1,0, 1,3, 2,0; chiaro, scuro, contrasto alto; **ATF** sugli stessi alberi | aspetto e accessibilità |
| Strumentati | finestre (`singleInstancePerTask`, `configChanges`, ricreazione), trascinamento con l'app di prova, `accessibilityDataSensitive` con il servizio di prova, widget su un launcher di prova | emulatore Android 16 tablet e telefono, S25, Tab S9 |
| PC sul NAS di prova | `tools/security/*`, `ManifestTool`, `share-snapshot.ps1` | §2.11, §2.10 |
| Benchmark | `WidgetBenchmark`, `PanesBenchmark`, `AccessibilityScrollBenchmark` e le guardie esistenti | §5.4 |
| Dispositivi | S25 (`test`; release candidata), emulatore (`test`), Tab S9 con due profili nel momento A | §5.5 |

### 5.2 Fake fedeli e test di parità
| Fake | Fedeltà | Parità |
|---|---|---|
| `ReferenceWidgetPlanner` | sceglie per forza bruta (tutta l'istantanea, tutti i controlli a ogni elemento) | stessi piani del pianificatore vero su 5.000 stati casuali (salvo l'ordine della rotazione, confrontato come insieme) |
| `InMemoryWidgetLedger` | registro in memoria con morte simulata fra scrittura e rinomina | la suite `WidgetLedgerContract` gira su `FileWidgetLedger` (file troncati e rovinati) |
| `FakeWidgetThumbs`, `FakeWidgetLock` | tabelle di presenza e di miniature | la suite di contratto gira sugli adattatori veri (miniature di `core-imaging`, presenza dall'istantanea) in Robolectric |
| `InMemoryReleaseState` | stato dei blocchi con le stesse regole di D30 | 2.000 storie casuali di manifest (validi, rotti, rigiocati, scaduti, aggiornamenti dell'app): stesso stato del vero |
| `FakeManifestSource` | busta da un array, errori del NAS, file troncati | lo stesso codec e lo stesso verificatore di produzione: il fake sostituisce solo la lettura |
| `ReferenceDescriber` | concatenazione diretta dei fatti con le regole scritte nel test | stessi testi del vero su 10.000 combinazioni di fatti e superfici |
| `FakeDescribePeople`, `FakeDescribeText` | sopra `InMemoryPeople` della Fase 8 e un testo letto finto | la suite `DescribePortsContract` gira anche sugli adattatori veri (`PeopleVisibility`, replica del testo) |
| `FakeProcessForeground` | contatore di finestre con avvii e fermate in ordine qualunque | sequenze casuali di due finestre: lo stato del blocco uguale a quello del vero con `ProcessLifecycleOwner` (strumentato) |
| `FakeMediaDragHandler` | preparazioni pronte, rifiutate, lente | il vero (`DocumentDragPayloads`) passa la stessa suite con il provider in memoria della Fase 5 |
| `FakeWindow` | `GalleryWindow` impostabile | le soglie coincidono con quelle lette da `LocalWindowInfo` (strumentato, emulatore tablet) |

### 5.3 Accessibilità: come si prova (D28)
1. **Test sui nodi** (a ogni `check.ps1`, Robolectric): per ogni stato del catalogo di ogni schermata un test percorre l'albero semantico e
   pretende: un nome per ogni elemento attivo; un ruolo; le azioni personalizzate attese (tabella del §2.7); le intestazioni; l'ordine; i
   bersagli ≥ 48 dp (misurati sui limiti del tocco); nessuna descrizione con un id, un percorso o un nome di file (espressioni regolari).
   Un'utilità comune (`A11yAssertions` nei `testing` di `core-designsystem`) evita che ogni modulo riscriva le regole.
2. **ATF** (Accessibility Test Framework) sugli screenshot di Roborazzi: le stesse schermate con il controllo automatico (etichette,
   contrasto dei testi, bersagli, elementi duplicati); un errore fa fallire, un avviso va motivato per iscritto nel test.
3. **Screenshot a testo 2,0x e a contrasto alto**: confronto con i riferimenti; un controllo sui pixel misura il contrasto delle scritte sopra
   le foto nel caso peggiore (riuso di `GalleryLegibility`).
4. **Sul campo** (F182–F185): TalkBack acceso dallo script, UI Automator scorre ogni schermata e legge le descrizioni (come F113 della
   Fase 9); testo al 200% e contrasto alto dalle impostazioni via adb; movimento ridotto (`animator_duration_scale 0`); tastiera e Switch
   Access sul tablet in DeX; il servizio di prova non "strumento". All'utente, se vuole, 5 minuti per ascoltare tre schermate.
5. **Dopo la fase grafica**: le stesse prove sono parte del suo "Fatto quando" (D38).

### 5.4 Benchmark e soglie di fluidità
**Scenari nuovi** (P9; Macrobenchmark sul `benchmarkRelease` con la Libreria da 100 mila)
| Scenario | Metrica | Soglia (S25 e Tab S9, 120 Hz) | Guardia |
|---|---|---|---|
| `widget` | un giro di aggiornamento (sezione di traccia `widget.update`), misura grande | p90 ≤ 400 ms (Tab S9 ≤ 600 ms) | +15% |
| `widget` | svuotamento dopo un "Nascondi" (`widget.blank`) | p90 ≤ 100 ms | +15% |
| `widget` | scorrimento della Libreria con un aggiornamento del widget in corso | guardia esistente (≤ 1% di fotogrammi oltre 8,3 ms) | come oggi |
| `pannelli` | scorrimento della griglia con il viewer affiancato (Tab S9, 1280 dp) | ≤ 1% (`jankPercent`) | riferimento +10% relativo, pavimento 0,15 punti |
| `pannelli` | cambio di foto nel viewer affiancato; apertura della griglia della zona nel pannello | ≤ 100 ms; p90 ≤ 150 ms | +15% |
| `pannelli` | attraversamento di una soglia ridimensionando la finestra: fotogramma più lungo | ≤ 33 ms (due fotogrammi) | +15% |
| `pannelli` | memoria (PSS) con due finestre (Libreria e un album) | riferimento | +15% |
| `accessibilita` | scorrimento della Libreria con un servizio di accessibilità di prova attivo che legge l'albero (simula TalkBack) | ≤ 1% | +10% relativo |
| `accessibilita` | scorrimento della Libreria con testo al 200% | ≤ 1% | come la Libreria |
| `accessibilita` | apertura del viewer con il servizio attivo (descrizione costruita) | ≤ 100 ms (guardia del viewer) | +15% |

- **Le guardie esistenti non peggiorano** (Fasi 3–9, `check.ps1 -Bench`) con il widget sulla Home, con due finestre e con il servizio di
  accessibilità attivo.
- **JVM** (a ogni `check.ps1`): piano del widget < 50 ms; verifica di un manifest < 5 ms; descrizione < 1 ms a cache calda, `warm` di 60 celle
  < 20 ms.
- **Nessun nascosto** (l'altra metà del "Fatto quando"): proprietà del registro sulle righe `WIDGET`, `DRAG`, `DESCRIPTIONS`, `PANES` e F161,
  F168.

### 5.5 Verifica sul campo
**Come si lavora**: le prove girano **da sole** sull'S25 con la build debug e l'account `test` (famiglia di prova, area comune
`test-comune`) e, per le prove della release, con la **release candidata**; ogni prova è uno scenario del pannello di debug "Prove della
Fase 10" o un modo di `s25.ps1` (`-Mode widget`, `-Mode a11y`, `-Mode security`, `-Mode tablet`), guida l'interfaccia con UI Automator e scrive
l'esito in un file letto dal PC con `adb`; il verificatore dal PC controlla il NAS di prova. **Gli agenti confermano solo i dialoghi della
nostra app** e usano Galleria Samsung e Archivio solo sui file di prova; le parti che chiedono un PIN si fanno sull'emulatore con un PIN di
prova messo e tolto dallo script. L'emulatore si accende solo per la prova e si spegne subito dopo. **L'utente serve solo nei tre momenti del
§2.14**; il tablet sta in **un solo momento** (A), poi resta collegato al PC per la notte.

**A. Preparazione**
1. S25 con la build debug e `test`; la release candidata (firmata con `pampa.jks`, P12) installata accanto con il suo id.
2. Emulatore con `test` (secondo dispositivo, perso nella F173); un secondo emulatore pulito per il recupero dell'admin (scenario 6).
3. Tablet: profili "Prova 1" (`test`) e "Prova 2" (`test2`) nel momento A; la build installata con adb in entrambi.
4. Le immagini di prova delle fasi precedenti in `mcg-prova`; i semi ostili di P11 nelle share di prova; un manifest di prova firmato con
   `KT` in `test-comune/.mycloudgallery/release/`.
5. PCAPdroid sull'S25 se l'utente l'ha già installato (Fase 8); altrimenti `netstats` e la cattura dell'emulatore.

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F160 | Widget (autonoma, S25): le tre misure sulla Home con un launcher guidato da UI Automator; ricordo di oggi (con "oggi" spostato dal pannello di debug); poi "Solo foto"; tema scuro e accento cambiati | contenuto di D3–D4; titolo senza nomi; palette nuova al cambio; nessuna foto senza miniatura locale |
| F161 | Widget e nascosti (autonoma, S25 poi emulatore): nascondere sull'S25 una foto mostrata; nascondere sull'emulatore una foto mostrata dall'S25, poi un giro dei dati personali | svuotamento entro 1 s (lettura con `uiautomator dump` delle descrizioni) e ridisegno senza quella foto; dall'emulatore entro il giro |
| F162 | Widget in 24 ore (autonoma, S25, passiva): frequenza di Q2, ore di quiete, risparmio energetico la sera | giri contati in Diagnostica come atteso; nessun giro fra le 23 e le 7; batteria del widget da `batterystats` sotto 0,2% |
| F163 | Widget, blocco e tocco (autonoma; schermata di blocco sull'emulatore con un PIN di prova): blocco dell'app acceso; tocco su una foto, sul titolo; `am start` con `OPEN_WIDGET` e numeri inventati, extra e dati | con il blocco solo media del telefono, nessun titolo (Q3); mai sulla schermata di blocco (Q4); il tocco apre la foto dopo lo sblocco; gli intent inventati aprono la Libreria |
| F164 | Widget e azzeramento (autonoma, emulatore): "Configura di nuovo" e azzeramento | widget "Configura MyCloud Gallery" prima della cancellazione; nessun lavoro `widget-refresh` |
| F165 | Pannelli e rotazione (tablet, profilo "Prova 1"): ogni schermata del §2.3 in orizzontale e verticale; "Affianca"; mappa e griglia | pannelli alle soglie; nessuna perdita di stato ruotando (pagina, elemento, zoom, bolla); screenshot |
| F166 | DeX e finestre libere (tablet in DeX): ridimensionamenti continui, finestra minima, mouse e tastiera, entrata e uscita da DeX | E-T2, E-T3, E-T9, E-T10; nessun crollo di MapLibre o del video |
| F167 | Due finestre e blocco (tablet): "Apri in una nuova finestra"; un nascosto in una; conferma aperta in una e selezione cambiata nell'altra; terza finestra; blocco con timeout "subito" | E-T4–E-T7; stessi dati; conferma decaduta; uno sblocco per tutte |
| F168 | Trascinamento in uscita (tablet, l'app di prova di debug a fianco): una foto del telefono, una solo sul NAS, dieci selezionate, una con "Rimuovi la posizione", 150 elementi; poi l'app di prova prova a leggere un altro documento **(solo lettura e consegna: l'app di prova ha lo stesso UID, quindi "scrittura negata" e "permesso persistente rifiutato" non provano i permessi; questi sono provati sulla JVM e, con un'app vera, con `adb shell dumpsys activity permissions`: L575, correzioni C, I2)** | byte uguali (SHA-256) o senza GPS; rifiuto oltre i tetti; un URI letto e nessun altro; copie tolte al riavvio |
| F169 | Trascinamento in arrivo (tablet): da Archivio (file di prova) verso la Libreria e verso un album; un file finto ostile | foglio con il conteggio; niente senza il tocco; rifiuto del file ostile |
| F170 | Account diversi (autonoma, PC + emulatore con `test2`): S1 | zero segni di `test` leggibili da `test2` |
| F171 | Dispositivo non approvato e revocato (autonoma): S2 | nessun dato accettato |
| F172 | Segmenti e immagini ostili (autonoma, emulatore e S25): S3 | rifiuti contati, nessun crollo, memoria sotto il tetto |
| F173 | Dispositivo perso (autonoma, emulatore): S4 | ordine dell'azzeramento; nessun dato rimasto; widget vuoto |
| F174 | Backup di Android e trasferimento (autonoma, emulatore): S5 | insieme di backup vuoto |
| F175 | Log della release (autonoma, S25 con la release candidata): S6 | zero occorrenze dei dati seminati |
| F176 | Modello manomesso (autonoma, emulatore): S7a | rifiutato e tolto |
| F177 | Manifest (autonoma, emulatore e S25): S7b, con un blocco `WRITES` (cattura: nessuna scrittura SMB) e uno `ALL` (nessuna sessione), poi un manifest con `seq` più alto che li toglie | come §2.8 |
| F178 | Rete estranea (autonoma, emulatore con cattura; S25 su un'altra rete se disponibile): S8 | nessun `SESSION_SETUP` |
| F179 | Cambio di massa (autonoma, PC + emulatore): S9 | congelamento, nessuna scrittura |
| F180 | Traffico di 24 ore della release candidata (autonoma, S25): S13 | solo gli host dichiarati, alle loro condizioni |
| F181 | Componenti e permessi sul dispositivo (autonoma): `dumpsys package` della release candidata | elenco chiuso |
| F182 | TalkBack su ogni schermata (autonoma, S25; tablet nel momento A): UI Automator con TalkBack acceso | ogni elemento attivo con nome; descrizioni di D22; nessun id o nome di file |
| F183 | Testo 2,0x, contrasto alto, meno trasparenza (autonoma, S25 e tablet) | nessun testo tagliato; superfici piene; screenshot |
| F184 | Movimento ridotto, tastiera e Switch Access (tablet in DeX, momento A) | nessun movimento non essenziale; ogni azione raggiungibile |
| F185 | Servizio non "strumento" (autonoma, emulatore Android 16): S12 | nessun contenuto letto |
| F186 | Release candidata (autonoma, S25): installazione, aggiornamento da una beta precedente con la stessa chiave, installazione di un APK firmato con un'altra chiave | aggiornamento riuscito con i dati; l'altro APK rifiutato da Android |
| F187 | Release con R8, tutte le funzioni (autonoma, S25): mappa (DR-1 della Fase 9), ricordi, widget, ricerca, viewer, condivisione | nessun crollo; stessi esiti della debug |
| F188 | Prestazioni della fase (autonoma sull'S25; Tab S9 nel momento A): §5.4 | soglie |
| F189 | Gli otto scenari del §10 (§2.14) | esito di ognuno nel resoconto; tempo dell'utente usato |

**I "Fatto quando" del piano, prova per prova** (il piano per la Fase 10 non li scrive: li fissa questo documento)
| Sottofase | Fatto quando | Dove si prova |
|---|---|---|
| 10.1 | widget nelle tre misure, nessun nascosto mai, nessuna rete, consumi dentro la soglia | P2, P3, F160–F164, `widget` |
| 10.2 | pannelli, rotazione e finestre senza perdite, DeX con mouse e tastiera, due finestre coerenti, trascinamento nei due sensi | P4–P6, F165–F169, `pannelli` |
| 10.3 | ogni schermata passa i test sui nodi e ATF; testo 2,0x; contrasto AA; movimento ridotto; solo strumenti di accessibilità | P7, P8, F182–F185, `accessibilita` |
| 10.4 | S1–S14 verdi; voci delle revisioni chiuse o dichiarate | P11, F170–F181 |
| 10.5 | manifest provato; release candidata firmata e controllata; prova a secco; checklist pronta | P1, P12, F177, F186, F187 |
| §10 del piano | otto scenari | F189 |

### 5.6 Verifica funzionale di fine fase
Un agente di verifica funzionale (come `docs/review/funzionale-9.md`), in parallelo alla `security-review`, rilegge i percorsi interi contro
questo documento e scrive `docs/review/funzionale-10.md` con voci "percorso, atteso, trovato, stato":
- widget (contenuto, misure, aggiornamenti, ridisegno per i nascosti anche dal giro dei dati personali, processo freddo, blocco, tocco,
  azzeramento);
- pannelli e finestre (soglie, "Affianca", mappa e griglia, rotazione, ricreazione, DeX, due finestre, blocco per processo);
- trascinamento (uscita e arrivo, media solo sul NAS, posizione, tetti, pulizia);
- descrizioni e TalkBack (ogni schermata, superfici, persone e testo letto solo alle condizioni, servizio non "strumento");
- manifest (lettura nel giro, rifiuti, blocchi nei cancelli, indirizzi aggiuntivi nei modelli);
- catena di rilascio (`release.ps1`, firma, versioni, changelog);
- strumenti del passaggio alle share vere (simulazione, fotografie, `recinzioni.json`);
- Nascosti su ogni superficie registrata; Diagnostica ed esportazione; differenze fra debug e release.
Le voci confermate si correggono prima della chiusura.

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 10
| Minaccia | Contromisura | Prova |
|---|---|---|
| **Il widget sulla Home mostra a chi tiene il telefono sbloccato** foto nascoste, nomi, luoghi, foto dell'area comune o del solo NAS | filtro finale a ogni disegno, svuotamento immediato per un nascosto (anche da un altro dispositivo), niente persone né nomi né luoghi nelle descrizioni, con il blocco dell'app solo media del telefono (Q3) (D4, D5, D8) | P2, P3, F161, F163 |
| **Il widget sulla schermata di blocco** | solo `home_screen`, mai `keyguard` (Q4) | P3, F163 |
| **Un'app estranea usa il tocco del widget** per aprire un media preciso o passare dati | azione fissa e due numeri validati sul registro locale, apertura dietro il blocco, nessun id nell'intent (D9) | P3, F163, `EntryHardeningTest` |
| **Il contenuto del widget resta fuori dall'app** (launcher, system server) dopo un nascondi, un azzeramento, una cancellazione dei dati | svuotamento prima di ogni cancellazione; limite dichiarato per "Cancella dati" (D5, D42, L542, L543) | P13, F164 |
| **Due finestre e il blocco dell'app**: una finestra resta aperta mentre l'altra è bloccata | blocco per processo, schermata di blocco in ogni finestra (D17) | P4, F167 |
| **Una finestra conferma ciò che l'altra ha cambiato** | conferme legate al piano con l'impronta (D18) | P4, F167 |
| **Il trascinamento verso un'altra app** dà più del dovuto (altri file, scrittura, permessi che durano) o porta fuori un nascosto o la posizione | URI del provider dei documenti con permesso di sola lettura sul singolo URI, `CallerGuard`, nascosti esclusi, tetti, copie senza posizione che falliscono chiuse, pulizia (D20) | P6, F168 |
| **Il trascinamento in arrivo** porta file ostili o scrive sul NAS senza volerlo | lo stesso flusso della condivisione in arrivo: copia immediata con tetti e tipo dai byte, conferma con un tocco (D21, C18) | P6, F169 |
| **Le descrizioni portano nomi o testo letto dove non devono** (widget, notifiche, log, esportazione) | descrizioni per superficie, costruite a richiesta, mai salvate; nessuna superficie di esportazione (D22) | P7 |
| **Un servizio di accessibilità malevolo legge lo schermo** | `accessibilityDataSensitive` sulla finestra (Android 14+): solo gli strumenti veri leggono (D24) | P8, F185 |
| **Il manifest remoto falsificato, rigiocato o manomesso** (kill switch per fermare le app della famiglia, indirizzi verso file diversi) | firma P-256 con due chiavi fissate, anti-rigioco, tetti, campi critici, effetti solo restrittivi, catalogo chiuso dei messaggi, indirizzi solo per impronte già fissate e host già ammessi (D29, D30) | P1, F177 |
| **Chiavi di pubblicazione o dell'APK rubate o perse** | firma con `pampa.jks` (D32, decisione dell'utente: condivisa con le altre app Pampa, limite L559) con la sua impronta controllata a ogni release, nessun `sharedUserId` né permesso `signature` nostro, chiavi fuori dal repository con frase segreta e copia offline, chiave di riserva del manifest, rotazione v3 dell'APK possibile (D31, D32) | P12, checklist, `Phase10ArchitectureTest` |
| **Catena di pubblicazione** (account GitHub, token, APK sostituito, build non controllata) | 2FA, token a grana fine con scadenza, ramo protetto, release solo da tag con `check.ps1`, controlli dell'APK prima del caricamento, mapping sul PC (D34) | P12, F186 |
| **Il passaggio alle share vere** scrive dove non deve, o troppo, o non si torna indietro | un solo passo dopo le prove (Q6 (b)) con la simulazione in sola lettura per area, fotografia prima e dopo per area, sì dell'utente, solo i dispositivi dell'admin, "Ferma le scritture" subito, bytecode controllato in entrambi gli stati (D35) | P10, §2.10 |
| **Strumenti di prova della 10.4 che fanno danni veri** | scrivono solo nelle share di prova e in `mcg-it/<giro>/`, con la guardia esistente e un test (D36) | P11 |
| **Nascosti che trapelano** dalle superfici nuove | righe `WIDGET`, `DRAG`, `DESCRIPTIONS`, `PANES` del registro, proprietà (D41) | P2, P6, P7, P5 |
| **La Diagnostica come telemetria** (anche sull'uso dell'accessibilità) | solo numeri e codici, mai lo stato dei servizi di accessibilità (D39) | P13 |

**Contromisure nuove** (proposta per `sicurezza.md` §4)
- **C430. Widget mai con un nascosto**: filtro finale a ogni disegno, svuotamento immediato a ogni nascondi (qui o da un altro dispositivo) e a
  ogni scarto di un ricordo, fallire chiusi (D5).
- **C431. Widget senza rete né NAS**: solo miniature già sul telefono, nessuna richiesta di rete o di file del NAS per il widget (D7).
- **C432. Widget discreto**: niente persone, nomi, luoghi nelle descrizioni; mai sulla schermata di blocco; con il blocco dell'app solo media
  del telefono (D4, D8; Q3, Q4).
- **C433. Tocco del widget senza dati**: un'azione fissa e due numeri validati, apertura dietro il blocco dell'app (D9).
- **C434. Blocco dell'app per processo**: vale per tutte le finestre; ogni finestra mostra la schermata di blocco (D17).
- **C435. Conferme legate al piano anche fra finestre** (D18).
- **C436. Trascinamento in uscita dal provider dei documenti**: sola lettura sul singolo URI, nascosti esclusi, tetti, posizione tolta o niente,
  copie temporanee tolte (D20).
- **C437. Trascinamento in arrivo dal flusso di ricezione**, con conferma (D21).
- **C438. Descrizioni per superficie**, a richiesta, mai salvate né esportate (D22).
- **C439. Solo gli strumenti di accessibilità leggono i contenuti** (Android 14+, D24). **P8 (02/11): provato sull'emulatore** (la marcatura sulla radice copre i nodi di Compose; il piano B non serve): `docs/sicurezza.md` C439.
- **C440. Manifest firmato**: P-256, due chiavi fissate, anti-rigioco, tetti, campi critici, rifiuto totale di una busta non valida (D29, D31).
- **C441. Effetti del manifest solo restrittivi**: sola lettura o fermo, mai cancellare, scaricare file nuovi, aggiungere host o mostrare testo
  libero (D29, D30).
- **C442. Chiavi di pubblicazione fuori dal repository**, con frase segreta, copia offline e una chiave di riserva (D31).
- **C443. Firma dell'APK controllata** (`pampa.jks`, D32): schemi v2 e v3, impronta del certificato controllata a ogni release, nessun `sharedUserId` né permesso `signature` definito dall'app, copia offline.
- **C444. Catena di pubblicazione**: 2FA, token a grana fine, ramo protetto, release solo da tag con le prove verdi e l'APK controllato (D34).
- **C445. Passaggio alle share vere in un passo controllato** (Q6 (b)): simulazione per area, fotografia prima e dopo, sì dell'utente, solo dispositivi dell'admin, ritorno
  indietro (D35).
- **C446. Recinzioni controllate nel bytecode** nei due stati, con il rimando al consenso (D35).
- **C447. Prove di sicurezza ripetibili** solo nelle aree di prova (D36).
- **C448. Nascosti anche nelle superfici della Fase 10** (registro, D41).
- **C449. Diagnostica senza lo stato dell'accessibilità** né il contenuto del manifest (D39).

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L65 riscritta**: "**Il widget** (Fase 10, D3–D11) mostra sulla Home foto della Libreria o il ricordo di oggi, mai nascosti né nomi; chi ha
  in mano il telefono sbloccato lo vede come vede la Home. Il widget di prova della Fase 7 resta solo nella build debug."
- **L540. Il widget è visibile a chi ha il telefono sbloccato**: senza il blocco dell'app mostra anche foto dell'area comune e media solo sul
  NAS (dalle miniature locali), come la Libreria a chi apre l'app.
- **L541. Con il blocco dell'app il widget mostra le foto che sono anche sul telefono** (Q3 (c)): le stesse che Galleria Samsung mostra a chi
  ha il telefono sbloccato.
- **L542. Le immagini del widget stanno anche nel launcher** e nel sistema: l'app le sostituisce, ma non controlla le copie che il launcher
  tiene in memoria o nelle sue anteprime.
- **L543. Dopo "Cancella dati" dalle impostazioni di Android** il launcher può mostrare l'ultima immagine del widget finché Android non lo
  aggiorna o lo toglie (l'app non è in esecuzione per svuotarlo).
- **L544. Un media nascosto su un altro dispositivo** resta nel widget fino al giro dei dati personali, come nella Libreria (E-W3).
- **L545. Due finestre condividono tutto**: stessi dati, stessa sessione del NAS, stesso blocco; non sono due utenti diversi.
- **L546. Su Android sotto la 12** (nessun dispositivo della famiglia) l'app ha una finestra sola.
- **L547. Ciò che si trascina esce dal nostro controllo**: l'app che riceve tiene il permesso finché Android lo concede e può copiare il file
  (come con "Condividi").
- **L548. Un media solo sul NAS trascinato** si legge mentre il NAS è raggiungibile; dopo, l'altra app riceve un errore.
- **L549. Le descrizioni le leggono gli strumenti di accessibilità** (TalkBack, Switch Access, Voice Access), con i nomi delle persone e il
  testo letto se attivi; sotto Android 14 le può leggere anche un altro servizio di accessibilità installato e autorizzato dall'utente.
- **L550. Le descrizioni possono sbagliare**: luoghi, persone e testo letto vengono dagli stessi dati dell'app (stime).
- **L551. `accessibilityDataSensitive` non ferma screenshot e registrazioni dello schermo**: lo fa `FLAG_SECURE`, solo sulle schermate
  protette (Nascosti, password, QR).
- **L552. Il blocco del manifest arriva solo a chi raggiunge il NAS** (Q8 (a)): un dispositivo che non torna mai a casa e non usa la VPN non lo
  riceve, ma non tocca nemmeno il NAS. Un familiare con la scrittura sull'area comune può togliere il file (nessun blocco nuovo arriva), non
  falsificarlo.
- **L553. Il blocco ferma, non ripara**: ciò che una versione difettosa ha già scritto resta scritto.
- **L554. Le chiavi (APK e manifest) stanno sul PC dell'admin**, protette da una frase segreta e con una copia offline: chi prende il PC e la
  frase può firmare.
- **L555. Chi entra nell'account GitHub** può sostituire l'APK delle prime installazioni (gli aggiornamenti li ferma la firma, i nuovi
  dispositivi li ferma l'approvazione dell'admin); la 2FA riduce il rischio.
- **L556. Una chiave dell'APK persa senza copia** = nessun aggiornamento possibile: si reinstalla l'app da capo su ogni dispositivo (nuova
  approvazione). Con `pampa.jks` (D32) vale per **tutte** le app Pampa insieme. Per questo la copia offline è obbligatoria.
- **L557. Dopo un ritorno indietro nel passaggio alle share vere** i dati già scritti restano (indice, copie del backup, file spostati
  nell'area comune): sono additivi e si tolgono solo a mano. Con il passo unico (Q6 (b)) possono essere di tutte e quattro le aree.
- **L558.** *Non serve*: era il limite di Q8 (b) (GitHub che vede l'indirizzo IP di chi legge il manifest); con Q8 (a) il manifest si legge
  solo dal NAS. Numero non usato in `docs/sicurezza.md`.
- **L559. La firma dell'APK è `pampa.jks`, condivisa con le altre app Pampa** (D32, decisione dell'utente del 31/10): chi ottiene quella
  chiave da un'altra app (il suo PC, il suo progetto, il suo publisher) può firmare un aggiornamento di MyCloud Gallery che Android installa
  sopra quello vero, con accesso ai dati dell'app (chiavi, replica, credenziali del NAS). Contenuto da: la chiave mai nel repository e con
  copia offline, la pubblicazione solo dal Pampa Store dell'admin con la 2FA (D34), nessun `sharedUserId` e nessun permesso `signature`
  definito dall'app (un'altra app Pampa vera non ottiene niente dalla galleria), l'approvazione dell'admin per ogni dispositivo nuovo.
  Una chiave dedicata resta possibile in futuro con la rotazione della firma v3 (`apksigner rotate`), senza reinstallare.

### 6.3 Checklist della Fase 10 (sostituisce le voci di `sicurezza.md` §7 "Fase 10")
- [ ] Widget: mai nascosti (anche da un altro dispositivo), mai nomi, mai sulla schermata di blocco, con il blocco dell'app solo media del
  telefono, nessuna rete (C430–C433; F160–F164).
- [ ] Due finestre con il blocco per processo e le conferme legate al piano (C434, C435; F167).
- [ ] Trascinamento: sola lettura sul singolo URI, nascosti mai, posizione tolta o niente, copie pulite; in arrivo con conferma (C436, C437;
  F168, F169).
- [ ] Descrizioni per superficie, mai salvate né esportate; solo strumenti di accessibilità (C438, C439; P7, F185).
- [ ] Revisione finale 10.4 per intero con le prove pratiche S1–S14 (§2.11): un account non legge i dati privati di un altro tramite l'indice;
  dati di dispositivi non approvati o revocati rifiutati; segmento ostile senza danni; dispositivo perso azzerato; backup Android e Smart
  Switch esclusi; nessun dato personale nei log di release; modello o manifest manomesso rifiutato; nessuna autenticazione SMB su rete
  estranea; modifica di massa che fa scattare il congelamento (F170–F181).
- [ ] Manifest firmato: valido applicato, manomesso o rigiocato ignorato, effetti solo restrittivi, indirizzi solo per modelli già elencati
  (C440, C441; F177) (passata dalla Fase 6, risposta Q2).
- [ ] Chiavi: `pampa.jks` con copia offline verificata e impronta nel documento (L559 dichiarato); K1 e K2 con il foglio offline (C442,
  C443).
- [ ] Rilascio: 2FA, token a grana fine, release da tag con le prove verdi, APK controllato, changelog (C444; F186, F187).
- [ ] Ogni voce aperta delle revisioni delle Fasi 1–9 chiusa con un test o dichiarata (`docs/review/10-chiusura.md`).
- [ ] Recinzioni nello stato deciso dall'utente e controllate nel bytecode (C445, C446).
- [ ] Questo documento rivisto per intero, §1 per la famiglia, condiviso con la famiglia.
- [ ] Dipendenze nuove approvate e fissate con l'impronta (Glance; ATF e Roborazzi solo nei test).

### 6.4 Permessi, componenti, dipendenze e host (proposta per `sicurezza.md` §4-bis e §8)
| Voce | Cosa cambia | Protezione | Perché |
|---|---|---|---|
| Permessi Android nuovi | **nessuno** (niente `REQUEST_INSTALL_PACKAGES`, niente allarmi, niente posizione) | `verifyReleaseManifest` | D33, D40 |
| Componenti nuovi | ricevitore del widget **non esportato**; componenti interni di Glance **non esportati** [da verificare in P3]; in debug l'app di prova del trascinamento e il servizio di accessibilità di prova (mai in release) | `verifyReleaseManifest`, `check.ps1` | D10, D40 |
| `MainActivity` | `launchMode="singleInstancePerTask"`, `configChanges`, `<layout>` con le misure minime, azione `OPEN_WIDGET` riconosciuta | `EntryHardeningTest`, `verifyReleaseManifest` | D9, D14–D16 |
| Dipendenze nuove nell'APK | **Glance 1.1.1** (`androidx.glance:glance`, `glance-appwidget`) e i transitivi che chiede, `engine-widget` (sorgente dell'engine) | impronte in `verification-metadata.xml`; Glance solo in `feature-widget` (dex) | D10 |
| Dipendenze nuove solo nei test | Accessibility Test Framework, `roborazzi-accessibility-check` 1.76.0 | `testImplementation`, mai nel dex | D28 |
| Host nuovi | **nessuno** (Q8 (a): il manifest si legge solo dal NAS) | nessuna classe di rete nuova; regola 5 della Fase 6 | D29 |
| Chiavi | `pampa.jks` (la stessa di oggi, D32); K1, K2 del manifest; `KT` solo nei test e nel debug | `check.ps1` passo 3 (file e contenuti) e dex | D31, D32 |

## 7. Rischi e piani B

| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| Glance non passa i controlli di P3 (componenti, `SessionWorker` con `HiltWorkerFactory`, peso, compatibilità con la BOM di Compose dell'app) | niente widget con il kit | P3 verifica prima di scrivere il resto; contratti indipendenti dalla tecnica | `RemoteViews` della piattaforma come il widget di prova, con la palette ricavata a mano dai token |
| Limite delle bitmap dei widget o `TransactionTooLargeException` sul launcher di Samsung | widget vuoto o tagliato | tetto di 2 MB, 384 px, RGB_565 | 3 immagini nella misura grande, 256 px |
| Samsung rimanda il lavoro periodico (secchio, sospensione) | widget fermo per ore | ridisegno all'uscita dall'app; `WIDGET_LATE` in Diagnostica | nessuno: un widget fermo è accettabile, un widget con un nascosto no (e quello non dipende dal periodico) |
| `singleInstancePerTask` si comporta male con notifiche, widget o la "nuova finestra" di Samsung | seconda finestra instabile | prova strumentata in P4 su emulatore e S25 prima di toccare il resto | una finestra sola (`singleTask` come oggi), dichiarato; E-P18 resta coperto dalla coerenza dentro un processo |
| `configChanges` rompe risorse che dipendono dalla configurazione | layout sbagliato dopo una rotazione | test di composizione a ogni soglia; risorse lette dalla composizione, non dall'activity | togliere `configChanges` e contare solo sullo stato salvato (MapLibre e video ripartono) |
| Il blocco per processo ha un buco (una finestra che resta aperta) | contenuti visibili con il blocco attivo | test con due finestre finte e strumentati; revisione opus mirata | tornare a una finestra sola finché non è provato |
| Le app che ricevono un trascinamento non accettano un descrittore a richiesta (vogliono un file con la posizione) | trascinamento che fallisce verso alcune app | prova con le app di Samsung e con l'app di prova | copia temporanea sempre per i media solo sul NAS (come con "Rimuovi la posizione") |
| `accessibilityDataSensitive` sulla radice non copre i nodi di Compose | protezione parziale | prova con il servizio di prova in P8 | marcatura solo su Nascosti, password e QR; limite riscritto. **Esito P8 (02/11): la radice li copre (emulatore API 37), il piano B non serve** |
| ATF segnala falsi positivi sul vetro sopra le foto | prove rosse senza un difetto vero | avvisi motivati per iscritto, contrasto misurato con `GalleryLegibility` | ATF solo per etichette e bersagli; contrasto solo con le nostre misure. **Esito P8 (02/11): nessun errore in 272 prove; a livello avviso 10 `TextContrastCheck` dell'Onboarding, motivati in `docs/decisioni.md`** |
| TalkBack rallenta la griglia da 100 mila | scorrimento a scatti per chi usa TalkBack | descrizioni a richiesta, `warm` solo delle celle in vista | descrizioni senza nomi né testo letto nella griglia, complete nel viewer |
| Il manifest sul NAS non arriva a chi viaggia senza VPN | blocco in ritardo per quel dispositivo | L552 dichiarato; il blocco serve soprattutto contro i danni al NAS, che senza NAS non si fanno | solo con una nuova risposta dell'utente (Q8 (b): anche da GitHub, con il suo limite) |
| L'admin perde `pampa.jks` | nessun aggiornamento possibile per **tutte** le app Pampa (L556) | copia offline **aperta e provata** nella checklist | reinstallazione su ogni dispositivo con una chiave nuova e nuova approvazione |
| Il publisher del Pampa Store pretende la firma v1 | APK con v2 e v3 non pubblicabile | P12 lo verifica sulla prova a secco | v1 accesa accanto a v2 e v3 (D32) |
| La simulazione del passaggio alle share vere rivela volumi inattesi (archivio grande, spazio del NAS) per un'area | passo lungo o spazio insufficiente; con il passo unico (Q6 (b)) quattro aree insieme | stima per area prima, tappe di notte, soglia di spazio | rinviare il passo; con un nuovo sì dell'utente tornare a un'area alla volta (Q6 (a)) o a un passo più piccolo (solo foto prima dei video) |
| La fase grafica rompe l'accessibilità | regressioni a fine progetto | le prove di accessibilità sono nel suo "Fatto quando" (D38) | correzione prima del rilascio |
| Le prove sul campo in sospeso (F46–F153 più F160–F189) sono troppe | chiusura e rilascio in ritardo | notti autonome raccolte; le prove che bloccano il rilascio marcate (F170–F181, F186, F187, F189) | l'utente dichiara "non bloccanti" le altre, una per una, nella checklist |
| Code della Fase 9 (P12, due pacchetti di correzione, decisione su P5) | dipendenze di P11 e F187 | la Fase 10 non tocca i loro file salvo `check.ps1` (ultimo) | P11 riconcilia quello che c'è al momento |
| I profili di prova del tablet non ci sono | M2 e scenari 1, 3, 4 senza tablet | momento A dell'utente | emulatore tablet con due utenti creati con `pm create-user` per le parti autonome; il Tab S9 solo per le misure |
| Crediti | costo alto | opus su 3 pacchetti su 15, due agenti insieme | P11 in due tempi, la seconda parte sonnet con revisione opus |

### File cruciali
- `core-library/.../contract/Visibility.kt` (`WidgetFeed`, `VisibleMedia`, `HiddenMask`), `visibility/RotatingWidgetFeed.kt`.
- `app/src/debug/.../debugtools/widget/` (widget di prova: modello del ricevitore non esportato e del ridisegno), `app/src/test/.../hidden/HiddenSurfaces.kt`.
- `core-memories/.../contract/` (`Memories`, `MemoryNotifyPolicy`), `app/.../notify/MemoryNotifier.kt` (`revalidate`, modello di `hiddenChanged`).
- `engine/engine-widget/`, `engine/docs/05-widget.md`, `engine/versions.gradle`, `engine.properties`.
- `app/src/main/AndroidManifest.xml`, `app/.../MainActivity.kt` (`Entry.clean`), `app/src/test/.../navigation/EntryHardeningTest.kt`.
- `core-security/.../lock/AppLockGate.kt`, `AppLockController.kt`.
- `feature-library/.../collections/CollectionsTab.kt` (`FluidListDetailScaffold`), `feature-viewer/.../ViewerOverlay.kt` (840 dp),
  `feature-onboarding/.../OnboardingHost.kt`, `feature-nas/.../folders/NasFoldersScreen.kt`.
- `feature-integrations/.../provider/` (`MediaDocumentsProvider`, `CallerGuard`, `ProxyDescriptors`, `PreparedCopies`, `DocumentPolicy`),
  `share/ShareCache.kt`, `share/ShareHolder.kt`, `receive/IncomingStager.kt`, il loro manifest.
- `feature-library/.../grid/GridText.kt` (`describe`), `core-designsystem/.../grid/GalleryGridCell.kt`, `design/GalleryChrome.kt`
  (`GalleryLegibility`), `motion/GalleryMotion.kt`.
- `core-models/.../contract/ModelCatalog.kt` (`ModelHosts`, `PinnedUrl`), `source/RoutingModelFetcher.kt`, `download/https/`.
- `core-common/.../featureflags/FeatureFlags.kt`, `diagnostics/Diagnostics.kt`, `src/test/.../architecture/Phase9ArchitectureTest.kt`.
- `app/build.gradle` (firma), `gradle/verify-release-manifest.gradle`, `gradle/verification-metadata.xml`, `versions-app.gradle`,
  `tools/check.ps1` (passi delle recinzioni D12, D30, dei resti di debug, 7h), `benchmark/soglie.json`, `tools/chaos/s25.ps1`.

## 8. Cose che richiedono l'utente

| Cosa | Perché | Nel frattempo |
|---|---|---|
| ~~Rispondere a Q1–Q10~~ **fatto il 31/10** (§11) | cambiano P2, P3, P1, P12, il §2.10 e la chiusura | — |
| ~~Approvare gli scaricamenti~~ **fatto il 31/10** (Q9 (a), sotto) | servono a P3 e P8, che li scaricano | — |
| **Creare le due chiavi del manifest** K1 e K2 (circa 10 minuti, con lo strumento di P1) e il foglio offline di K2; **nessuna chiave dell'APK nuova** (D32: si firma con `pampa.jks`, di cui va solo verificata la copia offline) | manifest (D31); gli agenti non toccano chiavi vere | build di verifica non firmate, chiave di prova `KT` |
| GitHub: 2FA attiva e token a grana fine (se non ci sono già) | catena di pubblicazione (D34) | — |
| I tre momenti del §2.14 (A tablet ~25 minuti, B una sera ~5 minuti, C ~10 minuti) | password delle prove, profili del tablet, NAS spento, codice del kit | tutto il resto gira da solo |
| **Il sì al passo unico** del passaggio alle share vere, dopo le prove, con i numeri della simulazione delle quattro aree (Q6 (b)) | la prima scrittura vera su `alessio` e sull'area comune | recinzioni accese |
| A fine prove: togliere o rafforzare `test` e `test2` | account deboli (promessa 16) | restano per le prove |
| Leggere `docs/sicurezza.md` §1 e condividerlo con la famiglia | piano §10 | — |
| La pubblicazione sul Pampa Store, alla fine di tutte le fasi: `beta` per due settimane sui suoi dispositivi, poi `stable` per tutta la famiglia insieme (Q7 (a)) | la avvia l'utente con la skill | release candidata sui dispositivi dell'admin |
| Già in sospeso dalla Fase 9 (non di questo documento): la decisione sulle regioni offline (P5) e su R1, R2, R4, I1 di `9-fase9.md` | pacchetti di correzione in corso | valgono le raccomandazioni di quei documenti |

**Scaricamenti approvati il 31/10** (Q9 (a); tutti sul PC, nessuno sul telefono; li fanno **P3** e **P8**, non P0: **niente è stato scaricato per questo documento né da P0**; versioni e dimensioni
esatte le fissano P3 e P8 con le impronte, e se cambiano molto si richiede)
| File | Fonte | Versione | Licenza | Dimensione [stima] | A cosa serve |
|---|---|---|---|---|---|
| Glance (`androidx.glance:glance`, `androidx.glance:glance-appwidget`) | Google Maven (`dl.google.com`) | **1.1.1** (quella che l'engine fissa in `engine/versions.gradle`; una 1.1.x più recente solo se P3 lo motiva) | Apache-2.0 | AAR ~1–2 MB in tutto; nell'APK +0,5–1 MB | il widget (D10) |
| I transitivi di Glance che l'app non ha già (per esempio `androidx.core:core-remoteviews`) | Google Maven | quelli che chiede Glance 1.1.1 (elenco esatto in P3) | Apache-2.0 | < 1 MB | idem |
| Accessibility Test Framework (`com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework`) e i suoi transitivi (per esempio Guava, protobuf-javalite, Hamcrest) | Maven Central (`repo.maven.apache.org`) o Google Maven [da verificare in P8] | la 4.x stabile più recente [da verificare] | Apache-2.0 (transitivi: Apache-2.0, BSD) | ~1 MB + 3–4 MB di transitivi, **solo nei test** | controllo automatico dell'accessibilità (D28) |
| `io.github.takahirom.roborazzi:roborazzi-accessibility-check` | Maven Central | **1.76.0** (la stessa di Roborazzi) | Apache-2.0 | < 200 KB, solo nei test | ATF dentro gli screenshot |
| (facoltativo, sul telefono, solo se l'utente vuole) Accessibility Scanner di Google | Play Store | — | gratuita, proprietaria | ~10 MB | un controllo in più a mano; **non serve** alle prove |

**Host che riceverebbero traffico dall'app** (in esercizio): **nessuno nuovo** (Q8 (a): il manifest si legge solo dal NAS, con la sessione
esistente). Restano quelli di prima: il NAS (LAN o WireGuard), `tiles.openfreemap.org` (mappa online),
`huggingface.co` e i suoi CDN (modelli), Google per ML Kit solo con la lettura del testo accesa. **Durante lo sviluppo**, dal PC: Google Maven,
Maven Central. **Al rilascio**, dal PC: GitHub (caricamento dell'APK con il publisher del Pampa Store).

**Misure da fare dopo il consenso** (P3, P4, P8): peso dell'APK prima e dopo Glance; manifest fuso con Glance; `singleInstancePerTask` e
`configChanges` su emulatore e S25; `accessibilityDataSensitive` con il servizio di prova; tempi del widget e delle descrizioni sull'S25 e
sul Tab S9.

## 9. Domande per l'utente

Poche, solo quelle che decidono che cosa vedi, che cosa esce dal telefono e quando la famiglia riceve l'app. Se non rispondi, vale la
raccomandazione.

| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | Che cosa mostra il widget sulla schermata Home? | (a) Il ricordo del giorno quando c'è, altrimenti foto a caso. (b) Solo foto a caso. (c) Solo i ricordi; nei giorni senza ricordo un invito ad aprire l'app. | **(a)**: un sorriso nei giorni giusti, foto belle negli altri. |
| Q2 | Ogni quanto cambiano le foto del widget? | (a) Circa ogni 3 ore, mai di notte. (b) Una volta al giorno, al mattino. (c) Ogni ora (consuma un po' di più). | **(a)**: sembra vivo senza pesare sulla batteria. |
| Q3 | Se hai acceso il blocco dell'app, che cosa mostra il widget? | (a) Le foto come sempre. (b) Nessuna foto, solo "Apri MyCloud Gallery". (c) Solo le foto che sono anche sul telefono: quelle che chiunque vede già in Galleria Samsung. | **(c)**: non mostra a chi prende il telefono niente di più di quello che vedrebbe già. |
| Q4 | Il widget può stare sulla schermata di blocco, a telefono bloccato? | (a) Mai. (b) Sì, se il telefono lo permette. | **(a)**: a telefono bloccato le foto non si vedono. |
| Q5 | In che ordine finiamo? | (a) Prima la Fase 10, poi la grafica, poi la famiglia riceve l'app finita; intanto tu provi sui tuoi dispositivi il salvataggio vero sul NAS. (b) La famiglia riceve l'app subito dopo la Fase 10; la grafica arriva dopo con un aggiornamento. (c) Prima la grafica, poi la Fase 10. | **(a)**: la famiglia vede l'app una volta sola, già bella e già provata. |
| Q6 | Oggi l'app scrive sul NAS solo nelle cartelle di prova. Come accendiamo il salvataggio vero? | (a) Un passo alla volta, solo sui tuoi dispositivi, con una settimana fra un passo e l'altro: prima l'indice, poi il backup, poi Persone (se la usi), per ultimi gli album condivisi. Per ogni passo ti mostro prima i numeri e aspetto il tuo sì. (b) Tutto insieme, dopo le prove. (c) Solo indice e backup; Persone e album condivisi restano di prova per ora. | **(a)**: se qualcosa non va, si vede subito e si torna indietro con un tocco. |
| Q7 | Chi riceve l'app e quando? | (a) Prima tu, per due settimane; poi tutta la famiglia insieme. (b) Prima tu, per due settimane; poi un familiare alla volta, a una settimana di distanza. (c) Tutti subito. | **(b)**: puoi aiutare ognuno e approvare i suoi dispositivi con calma. |
| Q8 | Il "pulsante d'emergenza" serve a fermare una versione difettosa e a dire "aggiorna". Da dove lo leggono i telefoni? | (a) Solo dal NAS di casa: nessun servizio in più; arriva quando il telefono è a casa o con la VPN. (b) Anche da Internet (GitHub), una volta al giorno: arriva ovunque, ma GitHub vede che quel telefono usa l'app. | **(a)**: niente esce dal telefono; chi non raggiunge il NAS non può nemmeno fargli danni. |
| Q9 | Per il widget serve una libreria gratuita di Google (Glance); per controllare da soli l'accessibilità servono due strumenti gratuiti che restano sul PC. Approvi? | (a) Sì, tutto. (b) Solo la libreria del widget. (c) Niente: widget più semplice nello stile e controlli di accessibilità solo nostri. | **(a)**: il widget prende lo stile dell'app e le prove trovano errori che noi non vedremmo. |
| Q10 | Quali idee per il dopo ti interessano di più? (scegline fino a tre) | Video più leggeri per guardarli fuori casa · importare da chiavetta o scheda SD verso il NAS · guardare sulla TV · usare una foto come sfondo · stampare · didascalie sulle foto · esportare un album · video in una finestrella (picture-in-picture) · altre lingue · widget con il soggetto ritagliato · descrizioni del contenuto delle foto · zone della mappa condivise in famiglia · un ricordo come video. | **Video più leggeri fuori casa, esportare un album, importare da chiavetta o SD**: sono le più utili ogni giorno. |

## 10. Fonti

**Nessuna fonte nuova consultata per questo documento** (niente rete, niente scaricamenti, niente dispositivi, per il brief). Dati e scelte
vengono da:
- il codice del commit `70e1d615` (elenco al §0 e al §7) e i documenti del progetto citati nell'intestazione;
- le regole del Fluid Engine nella cartella `engine/` (2.13.1): `docs/02-aggiornamento.md`, `04-config-remota.md`, `05-widget.md`,
  `06-limiti.md`, `versions.gradle` (Glance 1.1.1), `engine-widget/build.gradle`;
- `docs/android.md` (Tema 1: widget e secchio; Tema 6: profili; "Novità di Android 17": ridimensionamento non disattivabile dal target 37,
  permessi impliciti degli URI);
- conoscenze generali, **da verificare nei pacchetti prima di usarle**: componenti e manifest di Glance 1.1.1 e la sua convivenza con una
  configurazione propria di WorkManager (P3); il limite delle bitmap dei `RemoteViews` sul launcher di Samsung (P3); `singleInstancePerTask`
  con notifiche, `FLAG_ACTIVITY_MULTIPLE_TASK` e la "nuova finestra" di One UI, i metadati di Samsung per DeX (P4);
  `View.setAccessibilityDataSensitive` sulla radice di Compose e `isAccessibilityTool` di TalkBack, Switch Access e Voice Access, la chiave
  di "Riduci trasparenza" di One UI, `UiModeManager.getContrast()` (P8); la versione e le coordinate Maven dell'Accessibility Test Framework
  e il funzionamento di `roborazzi-accessibility-check` con Robolectric (P8); il trasferimento fra dispositivi simulato con il trasporto
  locale (P11); `apksigner rotate` e la firma v3, ciò che il publisher del Pampa Store pretende (v1, chiave) e se ha una prova a secco (P12).
- Pagine da leggere nei pacchetti (dal PC, con l'approvazione): la documentazione Android sui widget (`developer.android.com/develop/ui/views/appwidgets`),
  su Glance, sul multi-window e i grandi schermi, sul trascinamento (`DragAndDropPermissions`), sull'accessibilità (`accessibilityDataSensitive`),
  su `apksigner`; le pagine di Samsung per DeX; il repository dell'Accessibility Test Framework.

<!-- fine del documento -->

## 11. Risposte dell'utente e cosa cambiano

Risposte del **31/10/2026** alle domande del §9, più una domanda aggiunta dall'orchestratore sulla firma dell'APK (D32). Le decisioni, i
flussi, i pacchetti e le checklist sopra sono **già aggiornati** con queste risposte (le righe toccate citano la risposta); qui il riassunto.
Due risposte **non** sono la raccomandazione del progetto: **Q6 (b)** e **Q7 (a)**; e la firma dell'APK resta **`pampa.jks`**.

| # | Risposta | Cosa cambia |
|---|---|---|
| Q1 | **(a)** il ricordo del giorno quando c'è, altrimenti foto a caso | Nessun cambio di progetto: D3 e D4 come scritti. `WidgetChoice.MEMORY_THEN_PHOTOS` è la scelta di base (P0, `WidgetPrefs`); "Solo foto a caso" e "Solo ricordi" restano scelte in Impostazioni → Widget (P3). |
| Q2 | **(a)** circa ogni 3 ore, mai di notte | Nessun cambio: D6. `WidgetPeriod.HOURS_3` di base, ore di quiete **23–7** ora locale (`WidgetScheduleRules`, P0), flessibilità di 30 minuti; le altre frequenze (`HOURS_6`, `DAILY`) restano scelte. P2 scrive `schedule/` contro la suite di P0. |
| Q3 | **(c)** con il blocco dell'app acceso, solo le foto che sono anche sul telefono | D8, E-W6, L541 e C432 come scritti, **senza altre scelte**: la riga del §2.2 non offre più (a) e (b) e Impostazioni → Widget non ha una voce per il blocco. Contratto: `WidgetLock.onPhone` e nessun titolo di ricordo con il blocco (`WidgetMode.Memory` mai con `appLocked`), provato dalla suite `WidgetPlannerContract`. |
| Q4 | **(a)** mai sulla schermata di blocco | Nessun cambio: D8 (`widgetCategory="home_screen"`), C432; nel "Fatto quando" di P3. |
| Q5 | **(a)** prima la Fase 10, poi la fase della grafica, poi la famiglia riceve l'app finita | Nessun cambio: D38. Il passaggio alle share vere (il passo unico di Q6 (b)) corre durante la fase grafica, sui soli dispositivi dell'admin. |
| Q6 | **(b) Tutto insieme, dopo le prove** (non la raccomandata) | **D35 riscritta**: le quattro recinzioni (F4-D31, F5-D12, F8-D34, F7-D30) si spengono **in un solo passo**, **dopo le prove della Fase 10** e con il sì dell'utente; restano la "Simulazione del passo" in sola lettura prima (ora **per le quattro aree in un giro**), la fotografia delle share prima e dopo (**per area**), i soli dispositivi dell'admin, il ritorno indietro con "Ferma le scritture sul NAS" e una build con le recinzioni riaccese. **§2.10 riscritto** (le quattro aree in una tabella, un solo consenso in `recinzioni.json`, due settimane d'uso dopo il passo). **P10** riscritto (simulazione delle quattro aree, `recinzioni.json` con un consenso solo, stato misto solo per un ritorno indietro parziale dichiarato). **§2.14**: un sì solo (circa 10 minuti) invece di quattro; lo scenario 8 si misura dopo il passo unico. Toccati anche §0, D1, D38, E-SV1, E-SV2, E-SV5, E-SV6, §3 (riga "Recinzioni"), §4.1, §6.1 (riga e C445), L557, §7 (piano B: tornare a un'area alla volta con un nuovo sì), §8. |
| Q7 | **(a) Prima l'utente per due settimane, poi tutta la famiglia insieme** (non la raccomandata) | **§2.9**: `beta` sui dispositivi dell'admin per **almeno due settimane**, poi `stable` per **tutta la famiglia insieme** (D33, passo 9 della release). **Checklist di rilascio**: due voci nuove (due settimane di `beta` senza voci bloccanti; il giorno della famiglia con un QR per dispositivo e l'approvazione dell'admin nella stessa sessione). **§2.14**: il giorno della famiglia fra i tempi dell'utente al rilascio (circa 10 minuti per dispositivo [stima]). Toccati anche §0, E-SV5, §8. |
| Q8 | **(a)** il manifest si legge solo dal NAS | D29 e D40 precisate: nessuna lettura da Internet, **nessun host nuovo**, nessuna classe di rete (la `ManifestHttps` di Q8 (b) non esiste; §1.1, §1.3, riga P1). `ManifestSource` ha una sola implementazione, sul NAS (P13). **L558 non serve** (numero non usato in `docs/sicurezza.md`); S13 e §6.4 senza `raw.githubusercontent.com`; §7: Q8 (b) solo con una nuova risposta. L552 resta. |
| Q9 | **(a)** approvati tutti gli scaricamenti del §8/§9 | Glance **1.1.1** e i suoi transitivi per **P3**; Accessibility Test Framework e `roborazzi-accessibility-check` **1.76.0**, solo nei test, per **P8**. **Li scaricano P3 e P8, non P0** (P0 non ha scaricato niente). §4.1, righe P3 e P8, D10, §8 aggiornati; il piano B `RemoteViews` di P3 resta solo se Glance non passa i suoi controlli. |
| Q10 | Idee per il dopo: **esportare un album** e **un ricordo come video** | D43: restano nel §10.6 del piano come "per il dopo", **in quest'ordine**; non si progettano ora. Le altre idee restano solo elencate. |
| D32 | **La firma resta `pampa.jks`** (domanda aggiunta dall'orchestratore): niente chiave dedicata | **D32 riscritta**: si firma con `pampa.jks`, schemi **v2 e v3** (v1 solo se il publisher la pretende); la rotazione v3 resta possibile in futuro. Il rischio della chiave condivisa diventa il **limite L559** in `docs/sicurezza.md`; **C443** riscritta ("Firma dell'APK controllata": impronta a ogni release, nessun `sharedUserId` né permesso `signature`, copia offline) e provata in parte da `Phase10ArchitectureTest` (nessun manifest dichiara `sharedUserId` o un permesso `signature`). **P12**: nessuna chiave nuova, le voci `pampa.*` di oggi; i controlli di `release.ps1` restano tutti (con l'impronta di `pampa.jks`). **§8**: l'utente crea solo le due chiavi del manifest K1 e K2. **§2.14** e §2.9: preparazione più corta (circa 15 minuti). Toccati anche §0, M4, E-REL10, §3, §5.5, §6.1, §6.3, §6.4, L556, §7. |

**Come le ha applicate P0** (31/10; i dettagli in `docs/decisioni.md`, "Fase 10 P0: fondamenta e contratti")
- I contratti del §1.2 sono scritti con le risposte: `WidgetChoice.MEMORY_THEN_PHOTOS` e `WidgetPeriod.HOURS_3` di base (Q1, Q2), nessuna
  scelta per il blocco (Q3 (c)), `ManifestSource` senza un'origine Internet (`ManifestOrigin.NAS` soltanto, Q8 (a)).
- Recinti nuovi per le risposte: `Phase10ArchitectureTest` vieta un pacchetto o una classe di rete per il manifest (Q8 (a)) e un
  `sharedUserId` o un permesso `signature` in qualunque manifest (D32, L559).
- Nessuno scaricamento (Q9: li fanno P3 e P8), nessuna chiave toccata (D32), nessuna costante di `FeatureFlags` cambiata (Q6: il passo
  unico è dopo le prove).
- Gli scostamenti dalle firme indicative del §1.2 (interfacce al posto di `object`, nomi in più) sono elencati in `docs/decisioni.md`; i
  pacchetti dopo partono dalle firme vere.
