# Voci aperte delle revisioni delle Fasi 1–9: riconciliazione (Fase 10 P11, promessa 18)

| | |
|---|---|
| **Oggetto** | Tutte le revisioni di `docs/review/`: sicurezza (`1.4-core-trust.md`, `2-fase2.md` … `9-fase9.md`) e verifiche funzionali (`funzionale-*.md`), allo stato di `main` del 31/10 (`96eeed05`, con la Fase 10 P0-P2). |
| **Tipo** | Riconciliazione in sola lettura dei documenti (revisioni, `docs/decisioni.md`, `docs/sicurezza.md`) e del codice dove serviva per decidere. **Nessun difetto di prodotto corretto qui** (`docs/fase10-progetto.md` §2.11, D36; brief di P11). |
| **Data** | 31/10/2026 |
| **Regola** | Una riga per ogni voce **ancora aperta** secondo il documento della revisione (le voci chiuse nel documento con una correzione e un test non si ripetono). Ogni riga dice una sola cosa: **chiusa** da una correzione con il suo test, **dichiarata** con un limite `L<n>` di `docs/sicurezza.md` §6, **non un difetto** con il motivo, oppure **aperta** con il motivo e chi la chiude. Nessun rilascio con una voce "Alta" o "Blocca" aperta (§2.11). |
| **Esito** | **Nessuna voce Alta, Critica o Blocca aperta.** Restano aperte voci Basse, Minori e Informative, ognuna con chi la chiude (sotto). *Dopo la correzione D (02/11): nessuna Bassa aperta; le informative della "security-review" hanno tutte una decisione; restano prove sul campo, 5A I5 (la pagina del backup) e la scelta dell'utente su 5B I3.* La Media 1.4 M3 (decisione 2 dell'utente) e' chiusa dalla correzione M3 della Fase 10 (31/10, decisione 2(a)). |

## Conteggio per stato

| Stato | Voci |
|---|---|
| Chiusa (con la correzione e il test, arrivata dopo l'ultima intestazione del documento) | 11 (6 al 31/10 di P11, piu' 1.4 M3 con la correzione M3, piu' 1.4 N6, 4B I2 e 5A I4 con la correzione D del 02/11, piu' 4B I8, gia' chiusa dalla Fase 7) |
| Dichiarata con un limite `L<n>` | 28 (9 al 31/10, piu' 19 con la correzione D: L577-L597) |
| Non un difetto | 9 |
| Aperta | 11 (2 Minori o "da provare", 9 Informative): 9 sono prove sul campo gia' previste in P14 o nella seconda parte di P11 (Fase 2 domanda 2, 3 I9, 4A I3, 4A I4, 4A I5 per la release, 5B I4, 9 I2, GA-7, DR-1), piu' 5A I5 (da dire nella pagina del backup) e 5B I3 (scelta dell'utente) |
| **Totale righe** | **59** |

**Correzione D della Fase 10 (02/11):** le 25 righe che questo documento assegnava alla "security-review" (circa 27 voci contando B2, B5 e B7)
hanno tutte una decisione: 3 chiuse con una correzione o una prova e il suo test (1.4 N6, 4B I2, 5A I4), piu' 4A I5 per il debug (la release
resta a P11-2); 4B I8 gia' chiusa dalla Fase 7 e 1.4 B7 da fasi successive; 19 dichiarate con un limite nuovo (L577-L597); 5B I3 lasciata
all'utente (il suo testo e' gia' chiuso da D-B1). Dettagli in `docs/decisioni.md`, «Correzioni della Fase 10,
pacchetto D: le informative delle Fasi 1-9 (02/11)».

Gravita' delle aperte: **0 Critiche, 0 Alte, 0 "Blocca", 0 Medie** (1.4 M3 chiusa il 31/10 dalla correzione M3, dopo la risposta dell'utente). Le intestazioni di `5b-fase5b.md`, `8-fase8.md`, `9-fase9.md`,
`funzionale-5b.md`, `funzionale-8.md`, `funzionale-9.md`, `funzionale-fasi-1-5a.md`, `1.4-core-trust.md` e `2-fase2.md` sono state allineate a
questo stato (31/10).

## Come leggere "chi la chiude"

- **P14**: le prove sul campo della Fase 10 (`docs/prove-campo.md`), che gia' le elencano.
- **P11-2**: la seconda parte di questo pacchetto (emulatore, S25, famiglia di prova: S4, S5, S6, S8 (b)/(c), S1 (c), S10-S14).
- **security-review**: la revisione di sicurezza di fine Fase 10 (§4.5 del progetto), che per ogni informativa decide se correggere o
  dichiarare un limite. *La revisione del 02/11 (`10-fase10.md`, I7) non le ha riprese: le ha decise la correzione D della Fase 10 (02/11),
  riga per riga qui sotto.*
- **Utente**: serve una risposta dell'utente (la domanda e' scritta nella riga).

## 1.4 `core-trust` (12/10)

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| A2 | Alta (residuo) | Dichiarata con **L18** | Il residuo dopo i tre giri (un dispositivo compromesso ritarda la propria revoca occupando nomi del registro finche' non gli si cambia la password del NAS) e' L18. |
| M3 | Media | **Chiusa** | **Chiusa da `12aee811` con `FamilyKeyVaultTest`, `ChiaveDiFamigliaOgniUsoTest`, `AppIntegrationTest.ilControlloInBackgroundDellAdminNonToccaMaiLaChiaveDiFamiglia` e `KeystoreDeviceTest`** (Fase 10, correzione 1.4 M3, `docs/decisioni.md`, «Fase 10, correzione 1.4 M3: chiave di famiglia con autenticazione a ogni uso (31/10)»): la chiave AES della cassaforte vuole l'impronta o il PIN a ogni uso, ogni custodia e apertura passa dal foglio di sistema (`BiometricPrompt` con `CryptoObject`), annullare non firma niente e lo dice, il lavoro in background non tocca la chiave di famiglia, le cassaforti della versione 1 si migrano al primo uso dopo l'autenticazione. Restano le prove con l'impronta vera sul telefono (elencate nella sezione). Storia della voce: **Decisione 2 dell'utente (31/10): (a), impronta o PIN a ogni uso della chiave di famiglia** (`docs/decisioni.md`, "Decisioni dell'utente del 31/10 dopo la revisione finale, prima parte"); la correzione e' un pacchetto della Fase 10 (`core-security`, dopo P4). Prima: la cassaforte della chiave di famiglia vuole un blocco schermo sicuro e `setUnlockedDeviceRequired`, ma **non** chiede PIN o impronta a ogni uso: dipendeva dalla **decisione 2** dell'utente ("PIN o impronta a ogni uso della chiave di famiglia", `docs/decisioni.md`, "Da confermare con l'utente a fine Fase 1"), mai registrata. Chi la chiude: **utente** (risposta) e orchestratore (la correzione in `core-security`, o un limite nuovo). Non blocca il rilascio (Media). |
| M4 | Media (parziale) | Non un difetto (oggi) | Il kit vecchio resterebbe sul NAS dopo una **rotazione** della chiave di famiglia, ma l'app non ruota mai la chiave: nessun chiamante di `rotate`/`rotateTo` fuori da `core-trust` (verificato il 31/10 in `core-family`, `feature-settings`, `:app`). `RecoveryKitFiles.obsolete` e' pronto per quando la rotazione arrivera'; allora la voce si riapre. |
| M5 | Media (specifica) | Dichiarata con **L19** | L'identita' del NAS passa dalla sessione SMB 3.1.1 firmata (verificata dal banco: `NasIntegrationSuiteTest.t01`); "casa e NAS da dati non segreti" e' L19. |
| B2, B5, B7 | Bassa (parziali, come dichiarato) | **Dichiarata: L577** (B2), **L578** (B5); **chiusa** (B7) | Caratteri invisibili (ZWJ, selettori) ammessi nei nomi; spazzatura riletta a ogni sincronizzazione entro ~35 letture; cestino oltre 20 GiB non pulito. Il documento le dice "parzialmente chiuse come dichiarato", ma nessun limite di `docs/sicurezza.md` §6 le nomina. **Correzione D (02/11):** B2 dichiarata con **L577** (ZWJ e U+FE0E/U+FE0F restano per le emoji composte; i sosia si segnalano all'approvazione, `LookAlike`); B5 con **L578** (al piu' ~33 letture per sincronizzazione, nessuna memoria locale dei rifiuti); B7 gia' chiusa da fasi successive: il primo file del giorno passa anche oltre il tetto (`core-trust/.../data/Trash.kt:150-152`, `core-nasops/.../trash/NasTrashService.kt:246-252`, L36), la cancellazione e' sullo stesso handle con il timbro del diario (`core-nasops/.../trash/OwnTrashPurger.kt:72-82`, R3 della 4B) e la voce del diario locale si costruisce solo dal diario (`NasTrashService.kt:205-220`). |
| B6 | Bassa | Non un difetto | L'account dichiarato da' potere solo dove serve scrivere nell'area privata di quell'account (segnalazioni "perso" e dati personali valgono solo se trovati li', L23, C19): la prova di possesso e' la password SMB dell'area. |
| B8 | Bassa (specifica) | Chiusa | Chiusa da `39912daf` (Fase 4A P1) con `NasIntegrationSuiteTest.t01`: ora del NAS solo da SMB 3.1.1 (C3), il trasporto rifiuta ogni altro dialetto e il test controlla `SMB_3_1_1` sul NAS vero. |
| B9 | Bassa (specifica) | Chiusa | Chiusa da `95a6e68d` (revisione 2, F3) con `AdminRecoveryViewModelTest.senzaLoStatoDellaFamigliaServeLImprontaDelFoglio`: senza QR di stato il recupero vuole l'impronta digitata. |
| B10 | Bassa | Dichiarata con **L563** (nuovo, P11) | Con la chiave di famiglia rubata un ramo alternativo del registro si adotta senza allarme. Chi ha quella chiave puo' fare di peggio (firmare qualunque registro): il rimedio e' l'azzeramento della famiglia; il rilevamento (l'app dell'admin ricorda le impronte delle versioni che ha firmato) resta un'idea per il dopo. |
| N6 | Informativa (specifica) | **Chiusa (correzione D, 02/11)** | Il codice di approvazione deve venire dal testo digitato (campo vuoto, niente incolla dalla richiesta): scritto in `formato-nas.md` §4.5, ma non ho trovato un test dell'interfaccia che lo provi. **Chiusa da `ApprovalFlowUiTest.n6_IlCampoDelCodiceParteVuotoELaProvaEIlTestoDigitato`** (feature-settings): il campo parte vuoto, senza codice non si approva, e la prova che arriva al registro e' esattamente il testo digitato (`FakeRegistryService.proofs`). La richiesta non porta il codice (`PendingRequestView` non ha quel campo). Test di guardia: passa sul codice di oggi, rosso con un codice precompilato. |

## Fase 2 (13/10)

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| Domanda 1 (smbj e la firma finale di SESSION_SETUP) | Informativa | Chiusa | Chiusa da `39912daf` (Fase 4A P1) con `NasIntegrationSuiteTest.t01` (sul NAS vero): `SmbjTransport` verifica la firma della risposta finale e rifiuta le risposte in chiaro dopo il login (revisione 4A, "Cose fatte bene"). |
| Domanda 2 (Navigation e `deepLinkIds` con `adb`) | Informativa | **Aperta** (campo) | L'intent si ripulisce e le destinazioni sono per stato (`EntryHardeningTest`), ma la prova con `adb am start` sull'APK vero non e' registrata. Chi la chiude: **P11-2** (S13: `dumpsys package` e intent inventati sull'S25). |
| Domanda 3 (quanto e' pubblico il GUID) | Informativa | Dichiarata con **L19** | Il GUID non e' un segreto per costruzione (L19); la difesa e' la password forte (C5). |

## Fase 3 (13/10)

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| I1 (parser sincroni non annullabili a meta') | Informativa | Non un difetto | Con i tetti di R1 ogni lettore finisce in tempi brevi; S3 (c) di P11 lo misura sulle immagini ostili (al piu' 51 ms, `S3HostileImagesTest`). |
| I9 (residuo di R2 sul dispositivo) | Informativa | **Aperta** (campo) | Il ripiego dei GIF/WebP animati con il tetto di pixel va visto su un dispositivo. Chi la chiude: **P11-2** (S3 (c) sull'emulatore e sull'S25 con le immagini di `build/security/<data>/ostili/`). |

## Fase 4A (14/10)

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| I1 (allarme di rollback dopo la prima lettura) | Informativa | **Dichiarata: L579** | "Da fare con la 4B" (uno `stat` periodico della versione nota): non trovato in `docs/decisioni.md` della 4B. Disponibilita', non riservatezza. **Correzione D (02/11):** ancora cosi' (`core-trust/.../data/HeadSeries.kt:114`, `listingHint = previous == null`; nessun chiamante lo passa dopo); dichiarata con **L579**. |
| I2 (prove D7/D8 solo sulla replica in memoria) | Informativa | **Dichiarata: L580** | Copertura dei test: le adozioni vere passano da `CatalogReplicaPort`, provate a pezzi. **Correzione D (02/11):** le regole vere hanno le loro prove sul catalogo Room (`CatalogV3Test`, D7 e D8) e l'adattatore in `NasReview4aTest.r10_*`; la prova di parita' fra replica in memoria e catalogo non si fa (non piccola): dichiarata con **L580**. |
| I3 ("Scarica": nomi che iniziano con un punto, " (2)" oltre 255 byte) | Informativa | **Aperta** (campo) | Gia' fra le prove sul campo della 4A (MediaStore su Android 14/15). Chi la chiude: **P14**. |
| I4 (`ChangeTime` del WD) | Informativa | **Aperta** (campo) | Gia' fra le prove sul campo della 4A. Chi la chiude: **P14** (sul NAS di prova). |
| I5 (log di debug con le eccezioni di smbj) | Informativa | **Chiusa per il debug (correzione D, 02/11)**; **aperta** (campo) per la release | In release nessuna uscita (nessun sink, C21; passo 7b di `check.ps1`); in debug restano stack in `graph/LocalData.kt`, `work/AiWork.kt`, `graph/AppGraph.kt`. **Correzione D:** il sink di logcat della build debug (`app/src/debug/.../AppLogging.kt`) scrive delle eccezioni solo le classi della catena e i punti del codice, mai i messaggi (C467), per tutte le chiamate insieme; `AppLoggingTest` (rosso prima: il percorso del file arrivava in logcat). Chi chiude il resto: **P11-2** (S6 sulla release). |
| I6 (ultima risposta di SESSION_SETUP tenuta dalla guardia) | Informativa | Non un difetto | Non sfruttabile: una copia falsa cambierebbe solo flag che non indeboliscono (documento della 4A); `readRange` corretto. |

## Fase 4B (15/10)

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| I2 (porte di servizio che il test di architettura non vede) | Informativa | **Chiusa (correzione D, 02/11)** | `TestConfirmations` in `src/main/.../testing/` emette conferme senza `ConfirmationIssuer`. Il passo 7b di `check.ps1` tiene i `testing/` fuori dalla release, ma in `:app` qualunque file puo' costruire conferme. **Chiusa da `CorrezioniDArchitectureTest`** (core-common, C468): nessun file di produzione fuori da `testing` nomina un package `testing`; `TestConfirmations` resta nei fake (anche per gli strumenti di debug); in `:app` `ConfirmationIssuer` solo in `utility/UtilityPorts.kt` e `albums/AlbumTransferPorts.kt`; `NasOpsDao` solo in `core-database` e in `core-nasops/.../store/`. Le quattro regole viste rosse con un file di violazione temporaneo. |
| I3 (L29 e il registro personale) | Informativa | Dichiarata con **L29** | Confermato come dichiarato dal documento. |
| I5 (record del cestino con un campo fuori formato) | Informativa | **Dichiarata: L581** | Il caso del campo fuori formato resta (il record si salta). **Correzione D (02/11):** dichiarata con **L581**. |
| I7 (NAS pieno: "Elimina" non cestina) | Informativa | **Dichiarata: L582** | Disponibilita'. **Correzione D (02/11):** dichiarata con **L582**. |
| I8 (nomi dei nascosti in Cartelle NAS) | Informativa | **Chiusa** (Fase 7 P9, D23) | Chi ha il telefono sbloccato legge i nomi dei file nascosti nell'elenco di Cartelle NAS (non le miniature). **Correzione D (02/11): gia' chiusa dalla Fase 7 (P9, D23)**: Cartelle NAS non elenca per nome i file nascosti nella Libreria, al loro posto una riga "N elementi nascosti", e finche' non sa quali sono non elenca nessun file (`feature-nas/.../contract/NasAreas.kt:55-65`, `NasHiddenFilesTest.iNomiDeiNascostiNonSonoMaiNelloStatoEUnaRigaDiceQuantiSono`, `fincheNonSiSaQualiSonoNascostiNessunFileSiElenca`). Restano i nomi delle **cartelle** che contengono solo nascosti: **L593** (come 5B I7). |
| I9 (file fermo al nome temporaneo `.mcg-swap-…`) | Informativa | **Dichiarata: L583** | Raro (due rinomine fallite non di rete). **Correzione D (02/11):** dichiarata con **L583**. |

## Fase 5A (18-19/10)

R1-R7 chiuse nel documento. Restano le informative.

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| I1 (la vista in sola aggiunta pubblica ovunque fuori da `.mycloudgallery/`) | Informativa | **Dichiarata: L584** | Oggi nessuna sostituzione e' possibile; la proposta (le radici ammesse `Backup/` e `Versioni/` nella vista di `core-nas`) non e' fatta. **Correzione D (02/11):** la proposta non si puo' piu' applicare: dalla 5B le versioni pubblicano anche al solito percorso di qualunque copia privata (`core-backup/.../versions/NasVersionEngine.kt:851-853`, 5B I11); la pubblicazione resta esclusiva (mai una sostituzione). Dichiarata con **L584**. |
| I2 (il nome della copia del guardiano viene dal NAS) | Informativa | Non un difetto | Un solo componente validato da `SharePath` sotto una radice fissa; il caso del nome lungo era R1 (chiusa, `BackupPaths.alternateName`). |
| I3 ("al sicuro" fra due riletture regge sul timbro) | Informativa | **Dichiarata: L585** | Una modifica sul posto che conserva dimensione e data resta "al sicuro" fino alla riverifica a rotazione; ne' L37 ne' L39 lo dicono. **Correzione D (02/11):** dichiarata con **L585**; L37 rimanda a L585. |
| I4 (l'emergenza non ferma il file in corso) | Informativa | **Chiusa (correzione D, 02/11)**; resto dichiarato: **L586** | Si scrive solo nei temporanei propri; D32 dice "al passo successivo". **Correzione D:** il motore del backup riguarda il cancello prima di ogni pezzo del file in corso (`core-backup/.../upload/SafeUploadEngine.kt`, `pump`; C469): emergenza, blocco del manifest, pausa e famiglia non pronta lo fermano al pezzo dopo, con il punto di ripresa; `CancelloNelFileTest` (rosso prima: dieci scritture su dieci invece di una). La copia lato server del guardiano resta indivisibile: **L586**. |
| I5 (il guardiano scrive anche senza backup avviato) | Informativa | **Decisa (31/10): resta "sempre"**, va solo detto nella pagina del backup (P13) | Coerente con D9 ("sempre"); l'utente ha confermato il 31/10 che la copia privata dei file dell'area comune parte anche se il backup non e' mai stato avviato. Chi la chiude: **utente** (domanda: "la copia privata dell'area comune parte anche se il backup non e' mai stato avviato?") e orchestratore. |
| I6 ("Collegare" sposta la posizione di un altro media senza unirli) | Informativa | **Dichiarata: L587** | Nessuna fuga trovata (gli stessi byte si vedono dal media del telefono); preferito, nascosto e identita' pubblicata non passano. **Correzione D (02/11):** ancora cosi' (`core-database/.../MediaCatalog.kt:758-781`, `attachInTransaction`); l'unione dei due media non e' una correzione piccola: dichiarata con **L587**. |
| I7 (il nascosto contagioso dipende dall'ordine delle voci) | Informativa | Non un difetto | Sbaglia sempre dalla parte prudente: nasconde di piu'. |
| I8 ("Backup fermo da 3 giorni" con l'orologio del telefono) | Informativa | Non un difetto | Solo un avviso, non decide niente sui file (come i battiti della Fase 4). |
| I9 (la copertina delle copie simili si decide anche da dati remoti) | Informativa | **Dichiarata: L588** | Cambia solo l'aspetto; la 5B ha legato le azioni della cella alle copie con il loro posto. **Correzione D (02/11):** la regola e' ancora quella (`core-library/.../similar/CatalogSimilarCopies.kt:313-321`, `BACKUP_ROOT` a `:436`); dichiarata con **L588**. |

## Fase 5B (20/10)

**Intestazione allineata**: R1-R19 sono **tutte corrette** (20/10), ognuna con il suo "Stato" nel documento e la sezione di `docs/decisioni.md`
(«Correzioni 5B: posizione e Condividi», «configurazione e Diagnostica», «versioni e correzioni», «Libera spazio», «integrazioni», «Ricevuti in
coda»). Restano le informative (il documento le dice "tutte aperte").

| Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|
| I1 ("Libera spazio" non rilegge i byte del telefono) | Informativa | **Dichiarata: L589** | Regge sul collegamento del catalogo; L39 copre il lato NAS, non questo. **Correzione D (02/11):** dichiarata con **L589**; L39 rimanda. |
| I2 (il dialogo di sistema di "Libera spazio" non scade) | Informativa | **Dichiarata: L590** | La proposta era di dirlo in L39: non fatto. **Correzione D (02/11):** dichiarata con **L590**; L39 rimanda. |
| I3 ("Elimina dal telefono" dalla selezione della Libreria salta D22) | Informativa | **Aperta: scelta dell'utente** (il testo e' chiuso) | **Correzione D (02/11):** il testo sbagliato («Non c'e' ancora una copia sul NAS» anche per chi ce l'ha) e' gia' chiuso da D-B1 («Correzioni dal campo, gruppo B», 22/10, `LibraryDeleteSafetyTest`). Resta che il foglio della Libreria manda al cestino di Android senza l'azione "Prima mettilo al sicuro" del foglio della 5B: cambiarlo cambia cio' che l'utente vede, quindi decide l'utente (proposta nel resoconto della correzione D e in `docs/decisioni.md`: con il backup legato, la selezione passa al foglio della 5B come dal viewer). Chi la chiude: **utente** e il filone grafico. |
| I4 (il selettore nei recenti del task dell'altra app) | Informativa | **Aperta** | Chi la chiude: **P11-2** (S13: componenti e recenti sull'S25) o un limite. |
| I5 (NA-1: una conferma scaduta nell'attesa non ferma un'operazione in coda) | Informativa | **Dichiarata: L591** | **Correzione D (02/11):** il comportamento resta (regge con il cancello e i timbri di ogni elemento, E-D13); corretto il testo di `docs/decisioni.md` («Correzioni funzionali: Libreria e NAS», Sicurezza, NA-1) che diceva il contrario, e dichiarata con **L591**. |
| I6 (costo delle voci ostili di "Separa") | Informativa | **Dichiarata: L592** | Un dispositivo dello stesso account (L29). **Correzione D (02/11):** nessun tetto aggiunto (non piccolo); dichiarata con **L592**, come L29. |
| I7 (nomi delle cartelle con soli nascosti) | Informativa | **Dichiarata: L593** | Come 4B I8. **Correzione D (02/11):** ancora cosi' (`app/.../integrations/IntegrationsAdapters.kt:193-218`, `CatalogNasFolders.children`); filtrare le cartelle sui nascosti tocca le chiavi delle cartelle gia' date a DocumentsUI (IN-2): dichiarata con **L593** (vale anche per Cartelle NAS). |
| I8 (la regola `mcg-` delle versioni vale anche in release) | Informativa | **Dichiarata: L594** | Una cartella dell'utente `mcg-viaggio/` avrebbe la sua `Versioni/` interna. **Correzione D (02/11):** legare `VersionPaths` (puro, comune a debug e release) alla recinzione non e' piccolo; dichiarata con **L594**. |
| I9 (il cancello dei file in arrivo non provato nel bytecode di release) | Informativa | Chiusa | Chiusa da `147cfa49` (LA-5 della verifica funzionale) con il passo "D12 e R2 nella release" di `tools/check.ps1`, che legge `IncomingGate.stateOf` nel dex di release. |
| I10 (F3 senza `fileId`) | Informativa | **Dichiarata: L595** | Il WD da' il `fileId` (misurato nella Fase 4); un NAS che non lo desse ricadrebbe su dimensione e data. **Correzione D (02/11):** dichiarata con **L595**. |
| I11 (le versioni toccano qualunque copia privata del media) | Informativa | **Dichiarata: L596** | **Correzione D (02/11):** coerente con D17; dichiarata con **L596**, L43 rimanda. |
| I12 (D13 in debug: due punti non recintati) | Informativa | **Dichiarata: L597** | Solo debug. **Correzione D (02/11):** ancora cosi' (`core-backup/.../versions/NasVersionEngine.kt:127-133` recinta i lavori, non la sorgente di un ripristino); dichiarata con **L597**. |

## Fasi 6, 7, 8, 9 (sicurezza)

| Documento | Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|---|
| `6-fase6.md` | — | — | — | Chiusa nel codice il 26/10 (I2, I8 "non un difetto" nel documento). Restano solo le prove F46-F71 sul telefono (P14). |
| `7-fase7.md` | I6 (pulizia delle proprie teste e segmenti d'album) | Informativa | Dichiarata con **L145** | Aperta per scelta dell'utente (27/10): la crescita lenta e' accettata. |
| `8-fase8.md` | R13 (parte residua: la Diagnostica non conta i file trattenuti) | Bassa | Dichiarata con **L320** | Il resto di R13 e' corretto (pacchetto A). |
| `8-fase8.md` | I3 (finestre brevi) | Informativa | Dichiarata con **L360** | Pacchetto D. |
| `9-fase9.md` | R4 (id con pochi valori e periodi "Mai" in chiaro) | Bassa | Dichiarata con **L520** | Decisione del 31/10. |
| `9-fase9.md` | I2 (traffico non spiegato in una prova di P1) | Informativa | **Aperta** (campo) | Servono F119 e F120 con PCAPdroid sul telefono. Chi la chiude: **P14** (e S13 di **P11-2**, cattura di 24 ore). |
| `9-fase9.md` | I6 (le correzioni della Fase 8 entrate nel periodo) | Informativa | Non un difetto | E' una verifica: lo stato della Fase 8 corrisponde ai pacchetti. |

**Intestazioni allineate**: `8-fase8.md` (tutte corrette o dichiarate dopo i pacchetti A, formato e D: R13 in parte con L320, I3 con L360) e
`9-fase9.md` (R1, R2, R3, R5, I1, I3, I4, I5 corrette; R4 dichiarata; I2 aperta; I6 non un difetto).

## Verifiche funzionali

| Documento | Voce | Gravita' | Stato | Dettaglio |
|---|---|---|---|---|
| `funzionale-fasi-1-5a.md` | GA-7 (miniature in Eliminati di recente) | Da provare | **Aperta** (campo) | Non confermata e non dimostrabile senza il MediaProvider; la cella resta sul segnaposto e "Ripristina" funziona. Chi la chiude: **P14** (F15). |
| `funzionale-5b.md` | LA-3 (versioni di E-E7 aperte in debug) | Minore | Chiusa | Chiusa da `147cfa49` («Correzioni 5B: versioni e correzioni») con `Correzioni5BVersioniAppTest.la3_*`; il documento diceva ancora "aperta, non corretta qui". |
| `funzionale-5b.md` | LA-3, scoperta collegata (una copia modificata in Galleria non fa partire un giro) | Minore | Chiusa | Chiusa da `6698c002` («Correzioni 5B: Libera spazio», punto 15): `BackupGraph.hasWork` e `nextAll` contano la copia, con i test del pacchetto. |
| `funzionale-5b.md` | LA-4 (andirivieni fra i lavori) | Minore | Non un difetto (non confermato) | Riletti `BackupWork.kt` e `BackupGraph.nextAll`: un lavoro non puo' riaccodare se stesso e le attese vere non passano da li'; da contare sul telefono se capita (P14). |
| `funzionale-6.md` | — | — | — | Tutte corrette (A, B, C; TS-4 con F il 27/10). Flussi F46-F71 sul telefono: P14. |
| `funzionale-7.md` | — | — | — | 46 voci corrette, nessuna aperta. |
| `funzionale-8.md` | — | — | — | Tutte corrette dopo i pacchetti A-E (30/10). **Intestazione allineata.** |
| `funzionale-9.md` | DR-1 (la mappa con R8 mai vista in release) | Minore | **Aperta** (campo) | Nessuna correzione senza una prova che fallisca. Chi la chiude: **P14** (F151, F119-F120 con la release) e **P11-2** (S13 sull'S25 con la release candidata). |

## Nuovo limite di P11

- **L563** (1.4 B10): con la chiave di famiglia rubata un ramo alternativo del registro si adotta senza allarme (`docs/sicurezza.md` §6).

## Voci nuove della correzione D (02/11)

- Contromisure **C467** (log di debug senza i messaggi delle eccezioni, 4A I5), **C468** (porte di servizio delle conferme, 4B I2), **C469**
  (l'emergenza ferma anche il file in corso, 5A I4).
- Limiti **L577-L597** (`docs/sicurezza.md` §6): 1.4 B2 (L577), B5 (L578); 4A I1 (L579), I2 (L580); 4B I5 (L581), I7 (L582), I9 (L583);
  5A I1 (L584), I3 (L585), I4 per la copia del guardiano (L586), I6 (L587), I9 (L588); 5B I1 (L589), I2 (L590), I5 (L591), I6 (L592),
  I7 e 4B I8 per le cartelle (L593), I8 (L594), I10 (L595), I11 (L596), I12 (L597).

## Cosa resta per il rilascio

Nessuna voce Alta, Critica o "Blocca" e' aperta. Prima del rilascio servono: ~~la **decisione 2** dell'utente per 1.4 M3~~ (risposta (a) del 31/10, chiusa dalla correzione M3; restano le sue prove con l'impronta vera sul telefono); le prove sul campo
elencate (P14: F15, F119-F120, F151, 4A I3-I4); la seconda parte di P11 (S6 per 4A I5 in release, S13 per la domanda 2 della Fase 2, 5B I4,
9 I2 e DR-1); ~~la **security-review** di fine fase per le informative aperte (una correzione o un limite per ognuna)~~ (fatta dalla
correzione D del 02/11: ognuna ha una decisione; resta la scelta dell'utente su 5B I3).
