# Fase 4 di MyCloud Gallery: progetto tecnico (il NAS nella Libreria)

> Base per i brief dei sub-agent. Fonti:
> - piano: FASE 4 (4.1–4.7), §2, §3 (proposte 1, 3, 5, 8, 11, 12, 14), §4 (prova delle 500 interruzioni, consumi, latenze), §6 (E-I, E-N, E-L, E-D, E-R, E-M, E-V, E-H4, E-F5, E-S11), §7, FILONE GRAFICO PARALLELO;
> - `docs/formato-nas.md` (la fonte di verità: la Fase 4 la implementa e la completa, §0.3), `docs/modello-dati.md`, `docs/nas-sessione.md`, `docs/regole-attivita.md`, `docs/famiglia-dispositivi.md`, `docs/rete-casa-vpn.md`;
> - `docs/inventario-nas.md` (numeri veri), `docs/android.md` (temi 1, 3, 6), `docs/sicurezza.md` (L1–L25, §4-bis, checklist della Fase 4), `docs/decisioni.md` (anche "Fase 3: integrazione" e "Revisione grafica");
> - `docs/review/1.4-core-trust.md` (risposte alle domande aperte 1, 3, 5), `docs/review/2-fase2.md` (domanda aperta 1), `docs/review/3-fase3.md`;
> - `docs/research/smb-vpn.md` (§1.5–1.7, §2.5–2.7, §6), `docs/research/embeddinggemma2.md` (§6.3), `docs/research/ocr-volti.md`;
> - il codice di `core-nas`, `core-trust`, `core-model`, `core-database` (v2), `core-library`, `core-imaging`, `core-metadata`, `core-mediastore`, `core-rules`, `core-family`, `app/.../gallery`, `app/.../graph`, `app/.../work`;
> - `docs/fase3-progetto.md` per il formato e le regole dei pacchetti, `docs/bench/misure-s25-2026-10-13.md` per la guardia delle prestazioni.
>
> Contesto:
> - **Le Fasi 2 e 3 sono integrate.** La Libreria del telefono gira con indice a colonne in memoria, istantanea `MCGT1`, layout precalcolato; le misure sull'S25 del 13/10 sono la guardia (il riferimento `riferimento-s25.json` non è stato accettato: si confronta con quelle misure).
> - **Il NAS è sempre acceso** (pianificazione spenta il 12/10), con lo standby dei dischi attivo. **Il suo orologio è avanti di 53–58 s e deriva di circa 5 s al giorno** (NTP che non lavora): fa fede comunque l'ora del NAS.
> - **Il PC di casa è spento** nel caso d'uso: lavorano solo telefoni e tablet, il NAS non esegue codice nostro.
> - **In parallelo c'è il filone grafico** (comandi in alto, vetro leggibile): la Fase 4 consegna stati e contratti, non l'aspetto (§1.5).
> - **Ambiente di prova** (`docs/decisioni.md`, "Ambiente di prova sul NAS (14/10)"): utenti `test` e `test2`; share `test` (di `test`, con 163 MB di foto della Fase 0, leggibile anche da `alessio`), `test2` (solo `test2`), `test-comune` (entrambi); famiglia esclusa dalle ultime due. **`NAS` e `Public` sono pubbliche: gli account di prova le vedono e ci scrivono**, quindi la recinzione è **da codice** (D30, D33). Cestino del NAS spento sulle share di prova e su `Public`, acceso su `NAS`. Le password di prova sono deboli. Il file `%USERPROFILE%\.mycloudgallery\nas-it.properties` lo scrive l'utente: finché manca, le prove contro il NAS vero risultano *saltate* (§5.4).

## 0. Sintesi

**Che cosa consegna la Fase 4**
- I media del NAS (area privata e area comune) **compaiono nella Libreria** insieme a quelli del telefono, ognuno una volta sola, con lo stato giusto.
- Un **indice condiviso firmato dentro ogni share**: chi prepara un media lo fa per tutta la famiglia, gli altri lo scaricano.
- **Scansione** incrementale con impronte, **preparazione** (miniature, anteprime, fotogrammi, impronte) senza mai scaricare interi i video.
- **Operazioni sui file del NAS** (cartelle, rinomina, sposta, cestino di 30 giorni) riprendibili e idempotenti, provate con 500 interruzioni.
- **Regole delle attività** applicate ai lavori veri, con stime dalle dimensioni vere; **dati personali** sincronizzati fra i *miei* dispositivi.

**Moduli nuovi**
- `core-index`: indice condiviso (pubblicazione, replica locale, unità di lavoro con prenotazioni, compattazione);
- `core-nasmedia`: scansione, rilevamento (impronta rapida + metadati), esclusioni, congelamento per cambiamenti di massa;
- `core-prepare`: preparazione (foto, video, formati non decodificabili), usabile anche dal backup della Fase 5;
- `core-nasops` (4B): l'**unico** modulo con operazioni distruttive sui file del NAS, con il diario di ripresa;
- `core-personal` (4B): dati personali sincronizzati;
- `feature-nas` (4B): Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso.

**Aggiunte ai moduli esistenti**
- `core-nas`: lettura dei media (elenchi a pagine con attributi, letture a intervalli), sorvegliante a progresso, simulatore più fedele; nella 4B le primitive di scrittura;
- `core-trust`: contenuto dei segmenti `MCGB` v1, lettura e pubblicazione delle serie di teste, politica "solo i propri file";
- `core-model` e `core-database`: schema **v3** (riferimento `MediaRef.Nas`, presenza nei flag, cursori della scansione, tabelle dell'indice e del diario);
- `core-library` e `core-imaging`: istantanea `MCGT2` con la presenza, miniature del NAS dalla replica, badge accesi;
- `feature-viewer` e `core-mediastore`: originali e video del NAS in streaming, "Scarica sul telefono";
- `core-rules`: lavori a tappe, arretrato vero per le stime, motivo d'attesa "congelato";
- `:app`, `feature-diagnostics`, `feature-library` (solo dati e voci, mai l'aspetto).

**Diciotto pacchetti, in due metà**
- **4A "Il NAS in sola lettura nella Libreria"** (P0–P11): indice, scansione, preparazione, replica, Libreria unificata, viewer, regole. Sul NAS si scrive **solo dentro `.mycloudgallery/`** (indice e prenotazioni), mai sui media.
- **4B "Organizzare il NAS"** (P0-B, P12–P16): operazioni sui file, cestino, spazio, dati personali, prova delle 500 interruzioni, compattazione e pulizia dei propri file.
- Opus per i pacchetti fondanti (P0, P1, P2, P3, P4, P11, P12, P16), sonnet per gli altri (§4.4).

**Tappe**
- **M1** (4A): i media del NAS compaiono in Libreria da una scansione, senza scrivere niente sul NAS.
- **M2** (4A): miniature preparate e indice condiviso tra due dispositivi di due account, dati ostili rifiutati.
- **M3** (4B): operazioni sui file e prova delle 500 interruzioni superata.
- Chiusure: 4A con APK, benchmark e revisione di sicurezza; 4B con una settimana di preset Notte e una di Bilanciato (4.6).

**Test senza dispositivo**
- Un **NAS finto in memoria** con la semantica di Samba (maiuscole, file bloccati, quota, ora sfasata e che salta, risposte perse, latenze) e un **NAS sintetico** con le proporzioni dell'inventario.
- Un'**area di prova sul NAS vero** dietro una guardia che rifiuta qualunque percorso fuori da `mcg-it/<giro>/` delle share di prova, prima di qualsiasi chiamata SMB.
- I numeri veri arrivano dalle prove sul campo F1–F20 (§5.7).

### 0.1 Decisioni di progetto (valgono se l'utente non dice altro)
| # | Decisione | Perché |
|---|---|---|
| D1 | **Fase divisa in 4A e 4B** (§0, §4.2). La 4A non tocca mai un media sul NAS: scrive solo indice e prenotazioni in `.mycloudgallery/`. La 4B porta le operazioni distruttive, con la prova delle 500 interruzioni come cancello | Valore visibile presto (M1, M2) con il rischio più basso; la parte distruttiva arriva su fondamenta già provate e dopo una revisione di sicurezza, come chiede la massima sicurezza |
| D2 | **Accesso ai media con interfacce nuove e separate in `core-nas`**: `NasMediaReader` (4A: elenchi a pagine con dimensione, data, `fileId`, attributi; letture a intervalli; apertura in streaming) e `NasFileOps` (4B: cartelle, rinomina senza sostituire, copia lato server, scrittura su temporaneo, spostamento nel cestino, cancellazione con handle). `NasFileSystem` di `core-trust` resta a tre operazioni. **Solo `core-nasops` può dipendere da `NasFileOps`** (test di architettura) | Il formato vieta le operazioni distruttive a `core-trust` (§9); separare lettura e scrittura rende verificabile "niente di distruttivo da dati letti dal NAS" |
| D3 | **Sorvegliante a progresso per i trasferimenti**: oggi ogni operazione ha una scadenza totale (15 s interattiva, 60 s in background) che ucciderebbe la lettura di un video o una copia lato server. Per le operazioni a flusso la scadenza vale **per blocco** (15/60 s) e resta la soglia di 32 KiB/s per 20 s; nessun limite al totale finché i byte arrivano | Un video da 9,4 GB a 16 MB/s dura 10 minuti; la regola anti-blocco (E-R14) resta |
| D4 | **Indice: segmenti per dispositivo e per share, divisi per classe** (`meta`, `thumbS`, `thumbM`, `analysis`, `hq`, `frames`; classi riservate per vettori, OCR e dati personali). Record dei file in **JSON canonico**, immagini come blob per **chiave di contenuto** (SHA-256 di `qh1` + dimensione). Formato e lettura difensiva in `core-trust` (§2.1, modifica M2 del §0.3) | Ogni dispositivo scarica solo le classi che gli servono; una rinomina dal PC cambia il record ma non la chiave di contenuto, quindi **non rifà la preparazione** (Fatto quando della 4.2); il parser ostile sta con gli altri, già rivisti |
| D5 | **Lo stato di un dispositivo in una share è funzione pura della sua testa corrente**: record con un `seq` crescente per dispositivo (vince il più alto per chiave), lapidi per i file spariti. Chi legge tiene in locale da quale segmento viene ogni record e, a ogni testa nuova, toglie ciò che la testa non elenca più | Compattazione, ripubblicazioni e rami si gestiscono senza casi speciali; una replica cancellata si ricostruisce identica (E-I7) |
| D6 | **Fusione fra dispositivi per percorso e timbro.** Un record vale solo se dimensione e data coincidono con l'ultimo elenco di quel file (se l'elenco non c'è ancora vale "provvisorio" fino alla prima scansione). Fra più record validi si sceglie in modo deterministico (più classi preparate, poi l'id di dispositivo minore). I blob si prendono da qualunque dispositivo approvato che li abbia per quella chiave di contenuto | Viste identiche su tutti i dispositivi senza parlarsi; E-N12 (indice vecchio smascherato dal timbro) |
| D7 | **Identità condivise.** Chi registra per primo un file del NAS pubblica `MediaId` e `VersionId`; gli altri li adottano. Due id per lo stesso file convergono al **minore** (come `MergeRules`). L'adozione è **rifiutata** se l'id remoto è già legato, qui, a un contenuto diverso (dimensione o `qh1`): conflitto contato in Diagnostica | `modello-dati.md` §9; senza la regola di rifiuto un dispositivo compromesso potrebbe fondere media diversi e farli "sparire" dalla Libreria |
| D8 | **Fiducia nei dati dell'indice.** Valgono per mostrare (miniature, metadati), per l'identità dei media solo-NAS e per coordinare il lavoro. **Non bastano** per: unire un media del telefono a un file del NAS (serve l'impronta rapida letta **qui**, 128 KiB; l'hash completo può venire dall'indice solo se dimensione e `qh1` coincidono); dire "al sicuro" (sempre rilettura locale con SHA-256, 5.3); qualunque azione sui file (mai) | "Le firme dicono chi ha scritto, non che sia vero" (L3, formato §12.1): gli errori possibili restano di visualizzazione, mai di perdita |
| D9 | **Scansione** (§2.3): a ogni verifica si elencano **tutte** le cartelle incluse, a pagine; i file nuovi o cambiati si **rilevano** leggendo testa e coda da 64 KiB (impronta `qh1`, metadati, miniatura EXIF incorporata); se un record dell'indice ha lo stesso timbro si adotta senza leggere nulla. Un file è "stabile" solo se visto due volte uguale a distanza di almeno 2 minuti di **ora del NAS** (E-N4) | Correttezza prima di tutto (le modifiche sul posto dal PC non cambiano la data della cartella); i numeri misurati (1,9 s per 12 mila file in 6 cartelle) lo permettono |
| D10 | **Congelamento per cambiamenti di massa** (C11): in una scansione di una share, spariti oppure cambiati oppure "non più media" ≥ max(50, 5% dei file noti). Effetti: nessuna scrittura automatica sul NAS (indice, prenotazioni, compattazione, pulizie, operazioni in coda), nessuna assenza segnata (le posizioni restano "da verificare"), avviso **nel riepilogo giornaliero** e in evidenza in Libreria e Diagnostica (risposta Q6 dell'utente, §9.1). Si scongela solo con un gesto dell'utente o quando la situazione torna normale | Ransomware ed errori di massa (piano 4.2, 10.4); meglio fermarsi una volta di troppo |
| D11 | **Esclusioni**: area comune dalla configurazione firmata (`commonExclusions`); area privata dalle scelte locali dell'onboarding (dalla 4B sincronizzate come impostazione personale); **copertine degli album** riconosciute dall'elenco (immagini in una cartella con file audio, nomi tipo `cover`, `folder`, `front`, `AlbumArt*`, oppure sotto 300 KB) escluse di default, con l'opzione "Mostra le copertine degli album" (E-N14); cartelle di sistema e nascoste escluse sempre (E-N8, E-N16) | Inventario: 5.285 JPG da 40 KB di mediana nella share `NAS` sono copertine |
| D12 | **Preparazione in un solo passaggio per media**: si legge l'originale **una volta** e si producono tutte le classi: miniatura S (lato corto 200 px, WebP), M (lato corto 400 px, WebP), anteprima di analisi (lato lungo 1024 px, JPEG q90), anteprima HQ (lato lungo 2048 px, WebP), per le foto l'**hash completo**. I video non si scaricano mai interi: indice del contenitore e fotogrammi chiave con letture a intervalli (un fotogramma ogni 30 s, da 1 a 8, più la copertina). Ordine: visibili sullo schermo, poi dal più recente (proposta 11) | Il glossario mette l'HQ nella preparazione; rileggere 38 GB di foto due volte costerebbe un'altra notte; l'anteprima di analisi a 1024 px serve anche all'OCR (`research/ocr-volti.md`) |
| D13 | **Unità di lavoro e prenotazioni**: un'unità è (share, passaggio, mese di scatto, parte di al massimo 200 media ordinati per chiave di contenuto). Si prenota l'unità, mai il singolo media; durata 1 ora, rinnovo ogni 20 minuti (generazione nuova). All'inizio dell'unità si rilegge l'indice: ciò che un altro ha già pubblicato si salta | Prenotare 20 mila media vorrebbe dire 20 mila cartelle di prenotazioni; un'unità mal divisa costa solo lavoro doppio (E-I1 ammette "risultato coerente") |
| D14 | **Formati non decodificabili passano di mano**: un fallimento "decoder non disponibile qui" si pubblica nel record con la classe del dispositivo; gli altri Preparatori ci provano una volta; quando nessuno ci riesce il media è "non leggibile" con il segnaposto, mai bloccante (E-M10, E-M13) | Piano 4.3 (APV, 8K) |
| D15 | **Replica locale a pacchi**: i segmenti delle classi d'immagine scaricati restano come file immutabili in `noBackupFilesDir/replica/packs/<nome derivato dall'hash>` e si leggono con mappatura in memoria; i segmenti `meta` si applicano al database e si buttano. Nessun file per miniatura | Decine di migliaia di file piccoli rallentano il filesystem e l'azzeramento; la mappatura evita copie |
| D16 | **Livelli di qualità** (proposta, domanda Q2): **Risparmio** = miniature S; **Standard** (default) = S + M; **Alta** = S + M + HQ (viewer nitido anche offline). Le anteprime di analisi le scaricano solo gli Indicizzatori (Fase 6), in un buffer. Cambiare livello mostra la stima e converge piano (E-L6) | Punto aperto del piano "dimensioni per Risparmio/Standard/Alta" (0.3/4.4) |
| D17 | **Miniature provvisorie**: finché un media del NAS non è preparato, la griglia usa la miniatura EXIF incorporata letta al rilevamento (JPEG, quasi sempre nei primi 64 KiB) e, con l'app aperta, la decodifica dell'originale come contenuto visibile. Restano **solo locali** e non si pubblicano | M1 visibile subito; niente teste pubblicate per ogni scorrimento |
| D18 | **Libreria**: istantanea `MCGT2` (colonne nuove: id della posizione NAS e chiave di contenuto ridotta a 64 bit); presenza in tre bit nuovi di `CellFlags` (`ON_PHONE`, `ON_NAS`, `SAFE`); `LibraryFeatures.presenceBadges` acceso. Per un media su telefono e NAS la griglia usa la miniatura del telefono | Nessuna join in più per cella; circa 100 B per elemento invece di 80 (+2 MB su 100 mila) |
| D19 | **Viewer**: foto del NAS = HQ dalla replica se c'è, poi l'originale letto a intervalli in un temporaneo con progresso; **video in streaming** con un `DataSource` di Media3 nostro sopra `NasMediaReader` (mai `DefaultDataSource`, D7 della Fase 3). Dati mobili: avviso con la dimensione (E-V2) | E-V1, E-V5, E-M7 |
| D20 | **"Scarica sul telefono"** in `Pictures/MyCloud Gallery` con `IS_PENDING`, SHA-256 calcolato mentre arriva, posizione collegata **prima** che la lettura del telefono la veda (proposta 8, E-L2) | Due posizioni, nessun ricaricamento, nessun doppione |
| D21 | **Video uguali su telefono e NAS in attesa dell'hash completo**: mostrati **una volta** come gruppo provvisorio (`MediaGroup` di tipo nuovo `PROBABLE_DUPLICATE`, copertina la copia del telefono, nota "da verificare"); l'hash completo arriva dal passaggio pesante `FULL_HASH`, poi unione (stessi byte) o separazione. Mai un'unione d'identità senza hash completo (domanda Q5) | "Nel dubbio non si unisce" resta vero; l'utente non vede doppioni per giorni |
| D22 | **Regole applicate ai lavori veri**: scansione e sync indice = `INDEX_SYNC` (il rilevamento fuori dal Wi-Fi di casa è limitato a 50 MB per giro); preparazione = `PREPARATION`; scaricamento HQ = `HQ_PREVIEWS`; verifica "al sicuro" e hash completi = `PHOTO_BACKUP` / `VIDEO_BACKUP`; operazioni = richieste dell'utente (`UserInitiated`); pulizia del cestino = `INDEX_SYNC` più le condizioni del §2.7. Lavori a tappe da 8 minuti; i controlli in background preferiscono i momenti in cui i dischi sono già svegli (`lastDiskTouchAt`, E-R15) | `regole-attivita.md`, `android.md` 1.3; nessun servizio in primo piano notturno |
| D23 | **Dati mobili con l'app aperta**: si scaricano i metadati e le miniature di ciò che è sullo schermo; l'arretrato delle miniature aspetta il Wi-Fi (domanda Q7) | Proposta 3 del piano; la riga 3 di `regole-attivita.md` §8 è ancora da confermare |
| D24 | **Operazioni sul NAS (4B)** con un **diario locale** e una macchina a stati per ogni elemento, idempotente e riprendibile (§2.7); partono solo da un `UserConfirmation` costruito dal foglio di conferma. Stessa share = rinomina atomica; tra share = copia lato server su un temporaneo, **verifica completa**, pubblicazione senza sostituire, **sorgente nel cestino** per 30 giorni (domanda Q3) **Nota della Fase 7 (D29, P0):** lo spostamento tra share ottiene una porta di completamento vincolante, `MoveCompletionSink` (`core-nasops/contract/Completions.kt`), chiamata dopo `PUBLISHED` e prima di `SOURCE_RECORDED`, idempotente: un suo errore ferma l'elemento e si ritenta, cosi' la sorgente non va nel cestino prima che il catalogo conosca la destinazione (la chiamata nel motore e' di P4; oggi c'e' `MoveCompletions`, l'annuncio "al meglio" delle correzioni dal campo A). | E-D6, E-S11, §4 del piano; la copia lato server tra share è misurata (124–148 MB/s) |
| D25 | **Cestino del NAS** = `.mycloudgallery/trash/<dispositivo>/` della **stessa** share (rinomina atomica), record firmato con i campi nuovi `reason` (`delete` o `move`) e `folder`. "Eliminati di recente" mostra i record validi il cui file c'è ancora; il ripristino lo può fare qualunque dispositivo con il permesso; la **pulizia** solo chi ha cestinato, dal suo diario locale (formato §9.3) | Il cestino del WD non c'è su tutte le share (`alessio`: spento), non si governa via SMB e non ha record firmati |
| D26 | **Dati personali (4B)**: ogni dispositivo pubblica nella **propria area privata** lo **stato unito** che conosce; per ogni (media, campo) vince l'ultima modifica per ora del NAS, a pari ora l'autore minore. Valgono solo se firmati da un dispositivo approvato **dello stesso account** e trovati nell'area privata dichiarata per quell'account; le voci il cui autore è revocato si scartano anche se ripubblicate da altri | Robusto alla perdita di un telefono (le sue scelte restano negli altri); formato §4.6 lasciava la scelta alla 4.7 |
| D27 | **UI come stati e contratti** (§1.5): la Fase 4 consegna `StateFlow` di stati sigillati e ViewModel; le composable della Fase 4 sono "funzionali" (solo componenti esistenti di `core-designsystem`, nessuno stile nuovo); il filone grafico le può riscrivere senza toccare la logica | Due filoni in parallelo senza pestarsi i piedi |
| D28 | **Nessuna dipendenza nuova.** WebP e JPEG con `Bitmap.compress`, fotogrammi con `MediaMetadataRetriever` (ripiego `MediaExtractor` + `MediaCodec`), streaming con Media3 già presente. **Nessun modulo nuovo apre socket**: tutto passa da `core-nas` (test "nessuna rete" esteso) | §7.9 del piano |
| D29 | **Permessi**: nessuno nella 4A. Nella 4B, **proposta**: `RUN_USER_INITIATED_JOBS` e un `JobService` non esportato al pubblico (protetto da `BIND_JOB_SERVICE`) per le operazioni lunghe avviate dall'utente (UIDT, `android.md` 1.3 punto 4) | Una mossa di 2.000 file non deve fermarsi se l'utente esce dall'app |
| D30 | **Area di prova vera con guardia** (§5.4): le prove contro il NAS girano solo se esiste `nas-it.properties`, solo sulle share che sono **insieme** nel file e nell'elenco scritto nel codice (`test`, `test2`, `test-comune`), e solo sotto `mcg-it/<giro>/`; la guardia rifiuta qualunque altro percorso **prima** di aprire una connessione. Anche l'indice di prova vive lì (`mcg-it/<giro>/.mycloudgallery/`) | Richiesta dell'utente; gli account di prova possono scrivere su `NAS` e `Public` (pubbliche), e oggi la restrizione delle prove della Fase 2 è solo una convenzione |
| D33 | **Recinzione anche sul telefono durante le prove sul campo**: nella build **debug**, se l'account del NAS è `test` o `test2`, `core-nas` (`ShareFence`) rifiuta ogni share fuori da `test`, `test2`, `test-comune`, per tutte le interfacce (`NasFileSystem`, `NasMediaReader`, `NasFileOps`, copia lato server), prima di qualunque richiesta. In release la recinzione non esiste (`DeveloperTools` di release) | Un'area scelta per sbaglio all'onboarding (le share pubbliche compaiono nell'elenco) non deve mai ricevere indice, prenotazioni o, nella 4B, spostamenti |
| D31 | **Pubblicazione solo sulle share di prova finché l'utente non chiude la 4A** (`FeatureFlags.indexPublishTestSharesOnly = true`): sulle share vere la 4A lavora **in solo locale** (scansione e preparazione restano sul telefono). Lo stesso interruttore è l'"emergenza" in Diagnostica | La prima scrittura su `alessio`/`NAS` avviene dopo una tappa approvata |
| D32 | **Ora**: prenotazioni, cestino, stabilità dei file, ordinamento dei dati personali e ogni istante firmato usano l'ora del NAS. Un salto dell'ora del NAS oltre 5 minuti fra due sessioni (`SessionNasClock.lastJumpMs`) **sospende** prenotazioni, pulizia del cestino, compattazione e pulizia dei propri file fino al gesto dell'utente o a 24 ore di ora stabile | Il NAS è avanti di ~55 s e deriva: quando l'NTP verrà sistemato l'ora tornerà indietro di colpo; sotto i 5 minuti le tolleranze esistenti (2 minuti nel futuro) bastano |

> **Nota (Fase 6, 21/10/2026), a D16:** «le anteprime di analisi le scaricano solo gli Indicizzatori (Fase 6), in un buffer» è
> realizzato dalla Fase 6 (`docs/fase6-progetto.md`, D10 e §2.2): buffer locale in `noBackupFilesDir/ai-buffer/` (al massimo 500 MB,
> svuotato per ciò che è già calcolato, fermo sotto 1 GiB libero), riempito dall'indice della share (solo blob attestati, a lotti) o,
> sugli Indicizzatori, direttamente dalla preparazione e dal backup (vedi la nota alla politica R2 in §2.4). I livelli di qualità non
> cambiano: il buffer è una via a parte, fuori dalla replica, che continua a non tenere `analysis` e `frames`.

### 0.2 Nuovi edge case proposti (da aggiungere al §6 del piano)
- **E-N16**: cartelle di sistema e cestini del NAS (`.recycle`, `.Recycle Bin`, `$RECYCLE.BIN`, `.wdmc`, `@eaDir`, `.AppleDouble`, `.mycloudgallery` e ogni cartella che inizia con un punto): mai scansionate, mai mostrate.
- **E-N17**: l'ora del NAS salta (NTP sistemato o batteria dell'orologio): oltre 5 minuti si sospendono prenotazioni, cestino e pulizie; sotto, si prosegue.
- **E-I17**: una testa elenca segmenti che sul NAS non ci sono più (o con un'altra impronta): i dati di quei segmenti restano quelli già nella replica finché il dispositivo non pubblica una testa nuova; i media non coperti tornano "da preparare".
- **E-I18**: due dispositivi pubblicano `MediaId` diversi per lo stesso file: si converge al minore (D7), senza perdere preferiti o album.
- **E-I19**: area in sola lettura per questo account (E-N9): i suoi dispositivi non pubblicano; usano l'indice degli altri e preparano solo in locale.
- **E-L9**: video presente su telefono e NAS con dimensione e impronta rapida uguali ma senza hash completo: una sola cella con "da verificare" (D21).
- **E-D12**: copia lato server non disponibile o rifiutata (`NOT_SUPPORTED`, `ACCESS_DENIED`): ripiego con lettura e scrittura dal telefono, con le regole delle attività pesanti.
- **E-D13**: la sorgente di uno spostamento cambia durante la copia (modificata dal PC): lo spostamento di quell'elemento si ferma, nessun file nel cestino, entrambe le copie restano e l'utente lo vede.

### 0.3 Modifiche da fare in P0 (agli altri documenti)
**`docs/formato-nas.md`** (la v1 si può ancora cambiare: nessuna testa d'indice né record del cestino è mai stato scritto, §10):
- **M1, §5.5 teste**: ogni voce di `segments` diventa `{class, hash, size}`; `class` è uno fra `meta`, `thumbS`, `thumbM`, `analysis`, `hq`, `frames`, `personal` (e, riservate e rifiutate finché non definite, `vec`, `ocr`, `faces`). Ordine sempre per impronta. Una testa `personal` può elencare solo segmenti `personal`, una testa `index` mai.
- **M2, nuovo §5.5-bis "Contenuto dei segmenti"** (oggi "definito in Fase 4.1"): il formato del §2.1 di questo documento, con i suoi tetti, le chiavi, i campi del record, le lapidi e la regola "un blob è solo JPEG o WebP, letto prima nelle dimensioni".
- **M3, §5.5 regole di pubblicazione**: un dispositivo pubblica nell'indice di una share **solo dati ricavati da file di quella share** (§6.1 di questo documento); al massimo una testa ogni 5 minuti per share e tipo, salvo la chiusura di un'unità; la lettura delle teste "per nome" (oggi solo dichiarata) con salti doppi e bisezione come `DeviceStatusSeries`.
- **M4, §5.6 prenotazioni**: convenzione delle chiavi delle unità di lavoro (`work/1/<passaggio>/<aaaa-mm>/<parte>`, senza nomi di cartelle) e del rinnovo (generazione nuova ogni 20 minuti).
- **M5, §5.7 record del cestino**: campi nuovi `reason` (`delete` o `move`), `folder` (booleano) e, solo per le cartelle, `count`; ripristino ammesso da qualunque dispositivo approvato con il permesso della share, pulizia solo dall'autore.
- **M6, §2.3 "Pulizia dei propri file"**: l'interfaccia promessa (`OwnFilesPolicy`): teste superate (si tengono le ultime 3), segmenti non elencati dalle ultime 3 teste e più vecchi di 7 giorni di ora del NAS, generazioni di prenotazione proprie superate (mai la più alta della risorsa), temporanei propri; tetti per giro (500 file); si riconosce il file proprio dalla firma **e** dal posto. Chiude la nota della revisione 1.4 sulle generazioni condivise (riga 109).
- **M7, §5.5 dati personali**: contenuto della classe `personal` (§2.9) e regola di validità "solo nella share privata dichiarata localmente per l'account di chi legge" (il registro non elenca le aree private).
- **M8, §6.1**: soglia del salto (5 minuti, `SessionNasClock`) e i suoi effetti (D32); la stabilità dei file (E-N4) usa l'ora del NAS, mai quella del telefono (che è ~55 s indietro).
- **M9, §7 tetti nuovi**: voci per segmento (4.096), record `meta` (4 KiB), blob (1 MiB; `frames` 2 MiB per media, al massimo 9 immagini), lapidi per segmento, teste lette per share e per giro (256 dispositivi, 1.024 letture per nome), record per dispositivo e share (262.144).
- **M10, §9**: nuovo punto: `core-nasops` è l'unico modulo con operazioni distruttive sui media e sui file dell'app (oltre ai propri temporanei di `core-nas`); entra solo da `UserConfirmation` o dal diario locale; test di architettura e test "dato remoto ostile → nessuna azione" per ogni operazione.
- **M11, §2.1**: esito della prova E-I13 (F14) e, se Twonky o Plex riconoscono i file del cestino dal contenuto, l'involucro `MCGW` per i file cestinati.
- **M12, §14**: mappa dei file nuovi (`data/IndexSegment.kt`, `data/HeadSeries.kt`, `data/OwnFiles.kt`, …) e dei test.
- **Regola del documento**: le costanti del §5.5-bis stanno in `TrustLimits` come le altre.

**Altri documenti**
- `docs/sicurezza.md`: §4 (C11 con le soglie di D10, C19 con la regola M3, C10 con `core-nasops`); §4-bis (riga UIDT, **proposta**, solo nella 4B); §6 (limiti nuovi del §6.2 di questo documento); §7 (checklist della Fase 4 estesa, §6.3); §8 (righe delle decisioni D2, D8, D10, D24–D26, D29).
- `docs/modello-dati.md`: lo scrive P2 (schema v3, `MediaRef.Nas`, flag di presenza, passaggi nuovi).
- `docs/decisioni.md`: sezione "Fase 4" con D1–D33 e l'esito delle domande del §9.
- Piano: edge case del §0.2; il punto aperto "dimensioni delle anteprime" chiuso con D12/D16.
- `README.md`: moduli nuovi e chi vede chi (P0 nella 4A, P0-B nella 4B).

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-nas       + contract/NasMedia (NasMediaReader)            (4A, P1)
               + ops/ (NasFileOps)                             (4B, P12; usato SOLO da core-nasops)
core-trust     + data/IndexSegment, HeadSeries, OwnFiles       (P3)
core-index     → model, common, trust, nas(reader), rules       (pubblicazione, replica, unità di lavoro)
core-nasmedia  → model, common, trust(SharePath), nas(reader), metadata, database, index
core-prepare   → model, common, metadata, imaging(DecodePlanner), index   (nessuna dipendenza da core-nas:
                 riceve MediaBytes; Fase 5 lo chiama con i byte del telefono)
core-nasops    → model, common, trust, nas(reader + ops), database, index    (4B)
core-personal  → model, common, trust, nas(reader), database, index          (4B)
core-library   → + index (blob delle miniature solo tramite interfaccia ReplicaBlobs)
core-imaging   → + index (ReplicaBlobs), nas(reader) per le miniature interattive e il viewer
feature-nas    → nasops, index, library, imaging, designsystem   (4B)
app → tutti
```
- `core-index`, `core-nasmedia`, `core-prepare`, `core-personal` **non** vedono `core-nasops` (test di architettura in P0): nessun dato letto dal NAS può arrivare a un'operazione distruttiva se non passando dalla UI.
- `core-prepare` non conosce SMB: lavora su `MediaBytes` (`core-metadata`) e su un file temporaneo.
- I `feature-*` non si conoscono fra loro; `feature-library` riceve le voci di Raccolte da `:app` (§1.5).

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-nas` (P1, 4A): lettura dei media**
| Elemento | Firma | Note |
|---|---|---|
| Voce | `data class NasEntry(name, isDirectory, size, modifiedAt, changedAt: Long?, fileId: Long?, attributes: Int)` | `fileId` da `FileIdBothDirectoryInformation` (oggi scartato); attributi per nascosti, sistema, punti di rianalisi (E-N10) |
| Elenco | `suspend fun list(share, dir: SharePath?, purpose, pageSize = 1000, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome>` | tutte le pagine con l'iteratore di smbj; `ListOutcome(count, cappedAt: Int?)`; tetto per cartella 200.000 voci |
| Stato di un file | `suspend fun stat(share, path, purpose): NasResult<NasEntry?>` | una sola `CREATE`+`QUERY_INFO`+`CLOSE` |
| Apertura | `suspend fun open(share, path, purpose): NasResult<NasRandomFile>` | `NasRandomFile { size; stamp; suspend fun read(offset, buf, off, len): Int; suspend fun readRange(range): ByteArray; close() }` |
| Flusso con hash | `suspend fun readAll(file, sink: (ByteArray, Int) -> Unit, progress): NasResult<Sha256>` | 4 flussi paralleli a blocchi di 1 MiB, in ordine verso il `sink` |
| Contratto | `interface NasMediaReader` con i metodi sopra; `NasSessionManager.mediaReader()` | separato da `NasFileSystem` (D2) |
| Sorvegliante | `OperationMonitor` con `TransferBudget(perChunk = 15 s / 60 s, minRate = 32 KiB/s su 20 s)` | D3; le operazioni corte restano come oggi |
| Copia lato server | `SmbServerSideCopy` entra nel contratto (`ServerCopy`) con il suo fake | oggi classe concreta senza fake; la usa solo `core-nasops` |
| Simulatore | `InMemorySmbBackend` + `fileId` stabili alla rinomina, date delle cartelle, `lock(path)`, latenze per tipo di operazione a tempo virtuale, **piano dei guasti** (`FaultPlan`: all'operazione n, caduta prima o **dopo** che il server l'abbia applicata), ordine degli elenchi casuale, copia lato server | §5.2 |

**`core-nas` (P12, 4B): primitive di scrittura** (`ops/`, contratto `NasFileOps`)
| Operazione | Contratto |
|---|---|
| `mkdirs(share, dir)` | "esiste già" come cartella = successo; esiste come file = `Exists` |
| `rename(share, from, to, expect: NasStamp?)` | mai sostituire; con `expect` controlla dimensione e `fileId` sull'handle aperto prima del `SET_INFO`; esiti `Renamed`, `Collision`, `SourceMissing`, `SourceChanged`, `Locked` |
| `copyToTemp(fromShare, from, toShare, tag)` | copia lato server verso `<toShare>/.mycloudgallery/tmp/<dispositivo>-op-<tag>`; ripiego `writeToTemp` con lettura dal telefono (E-D12) |
| `publishTemp(share, temp, final)` | rinomina senza sostituire; `Collision` restituisce il controllo al chiamante |
| `trashMove(share, path, trashId)` | rinomina nella stessa share verso `trash/<io>/files/<trashId>` |
| `deleteIfUnchanged(share, path, size)` | apre l'handle, controlla la dimensione **sullo stesso handle**, poi `delete-on-close` (formato §9.3, revisione B7) |
| `rmdirIfEmpty`, `deleteOwnTemp`, `space(share)` | `space` solo su richiesta dell'utente o dopo `DISK_FULL` (E-R15) |

**`core-trust` (P3)**
| Elemento | Firma | Note |
|---|---|---|
| Classi | `enum class SegmentClass(wire, code)` | §2.1 |
| Codec | `object IndexSegments { fun encode(cls, entries): ByteArray; fun decode(bytes, ref: SegmentRef, expected: SegmentClass): Verdict<SegmentContent> }` | sopra `Segments.wrap/unwrap`; mai un'allocazione da lunghezze dichiarate |
| Record | `data class MetaRecord(...)` con `encode()`/`MetaRecords.verify(json, share): Verdict<MetaRecord>` | JSON canonico; percorso con `SharePath.parseMediaPath` |
| Serie di teste | `object HeadSeries { fun readLatest(fs, registry, location, previous: HeadMark?, readerAccount): HeadRead; fun publish(fs, signer, head): PublishResult }` | per nome, salti doppi e bisezione, spazzatura ≤ 32 |
| Propri file | `class OwnFilesPolicy(me) { fun decide(file, kind, nasNow, keepHeads = 3, grace = 7.days): OwnFileDecision }` | M6; nessuna cancellazione qui |
| Tetti | `Envelope.maxFileBytes` diventa pubblico; costanti nuove in `TrustLimits` | M9 |

**`core-index` (P3 pubblicazione e lavoro, P4 replica)**
| Elemento | Firma | Note |
|---|---|---|
| Pubblicatore | `interface IndexPublisher { suspend fun stage(share, records: List<MetaRecord>, blobs: List<BlobPut>); suspend fun flush(share, reason): PublishOutcome; val pending: StateFlow<PendingCounts> }` | "outbox" persistente (database + pacco temporaneo): niente si perde se il processo muore |
| Unità di lavoro | `interface WorkUnits { suspend fun next(share, pass: WorkPass, priority: UnitPriority): UnitClaim?; suspend fun keepAlive(claim); suspend fun finish(claim, outcome) }` | D13; usa `LeaseManager` |
| Sincronizzazione | `interface IndexSync { val status: StateFlow<IndexSyncStatus>; suspend fun sync(scope: SyncScope, budget: Duration): SyncReport }` | `SyncScope(shares, classes, visibleOnly)` |
| Blob locali | `interface ReplicaBlobs { fun locate(contentKey: Long, cls): BlobLocation?; fun read(loc): ByteBuffer; fun estimate(quality): SpaceEstimate }` | mappatura dei pacchi |
| Qualità | `interface ReplicaQuality { val level: StateFlow<QualityLevel>; suspend fun set(level) }` | E-L6 |
| Compattazione | `class Compactor { suspend fun compactIfNeeded(share): CompactOutcome }` | scritta in P3, **accesa nella 4B** insieme alla pulizia (P12) |
| Diagnostica | `IndexDiagnostics : DiagnosticsContributor` | §2.2 |

**`core-nasmedia` (P5)**
| Elemento | Firma | Note |
|---|---|---|
| Scansione | `interface NasScanner { val status: StateFlow<NasScanStatus>; suspend fun scan(share, mode: ScanMode, budget): ScanReport }` | `ScanMode { FULL, VERIFY, FOLDER(path) }`; ripresa dal cursore |
| Rilevamento | `class NasDetector(reader, extractor) { suspend fun detect(file: NasRandomFile, hints): Detection }` | `qh1` + `Extraction` + miniatura EXIF |
| Byte | `class NasMediaBytes(file): MediaBytes` | adattatore verso `core-metadata`, pagine da 64 KiB |
| Esclusioni | `object Exclusions { fun build(config, local, showCovers): ExclusionSet; fun isAlbumCover(entry, siblings): Boolean }` | D11 |
| Congelamento | `object MassChangeGuard { fun judge(known: ShareCounts, diff: ScanDiff): FreezeVerdict }`; `interface FreezeState { val frozen: StateFlow<Freeze?>; suspend fun release(by: UserGesture) }` | D10 |

**`core-prepare` (P6)**
| Elemento | Firma | Note |
|---|---|---|
| Preparatore | `interface Preparer { suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult }` | `PrepareSource.Bytes(mediaBytes, kind, mime)` oppure `.File(tempFile)` |
| Specifica | `data class PrepSpec(thumbS = 200, thumbM = 400, analysis = 1024, hq = 2048, frameEverySec = 30, maxFrames = 8)` | D12 |
| Esito | `sealed PrepResult { Done(outputs, fullHash?, metadataUpdate?); Failed(code, retryable, decoderMissing) }` | D14 |
| Lavoro | `interface PreparationRun { suspend fun run(budget, priority: PrepPriority): RunResult }` | usa `WorkUnits`, `IndexPublisher` |
| Memoria | `class PrepBudget(memoryClass, cores)` | una decodifica pesante alla volta, temporanei ≤ 512 MiB |

**`core-library` e `core-imaging` (P7), `feature-viewer` e `core-mediastore` (P8)**
| Elemento | Firma | Note |
|---|---|---|
| Riferimento | `MediaRef.Nas(locationId: Long, contentKey: Long, mime: String?)` (in `core-model`, P2) | il percorso si risolve dal database solo quando serve |
| Indice | `TimelineSnapshot.nasRefAt(i)`, `presenceAt(i)` (dai flag) | `MCGT2` |
| Miniature | `NasThumbnailSource(blobs, provisional, interactive)` in `RoutingThumbnailSource` | replica → EXIF provvisoria → originale (solo contenuto visibile) |
| Viewer | `NasImageOrigin : ImageOrigin`, `NasVideoDataSource : BaseDataSource` | D19 |
| Scaricamento | `interface PhoneMediaWriter { suspend fun saveToPictures(name, mime, source, progress): SaveResult }` | D20 |
| Stato NAS | `LibraryNasStatus` (§1.5) dentro `LibraryTabState` | solo dati |

**`core-nasops` (P12, 4B)**
| Elemento | Firma | Note |
|---|---|---|
| Operazioni | `interface NasOperations { suspend fun plan(req: OpRequest): OpPlan; suspend fun start(plan, confirmation: UserConfirmation): OpId; val operations: StateFlow<List<NasOperationState>>; suspend fun cancel(id); suspend fun resumeAll(budget): RunResult }` | `OpRequest { CreateFolder, Rename, Move, Trash, Restore, DeleteNow? }` |
| Conferma | `class UserConfirmation internal constructor(planHash, issuedAtMonotonic)`; `object ConfirmationIssuer` (usato solo da `feature-nas` e dal menu del viewer) | test di architettura su chi la costruisce |
| Cestino | `interface NasTrash { val items: StateFlow<NasTrashState>; suspend fun purgeExpired(budget): PurgeReport }` | §2.7 |
| Spazio | `interface NasSpace { suspend fun load(share): NasSpaceState }` | §2.7 |
| Pulizia propria | `class OwnFilesCleaner(policy, reader, ops)` | M6, accesa con la compattazione |

**`core-personal` (P13, 4B)**: `interface PersonalSync { val status; suspend fun sync(budget): PersonalSyncReport }`, `object PersonalMerge { fun merge(...) }`, `interface PersonalSettings` (esclusioni dell'area privata, "Mostra le copertine").

### 1.3 Aggiunte a moduli esistenti
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-model` | `MediaRef.Nas`; `Location.Nas` + `fileId`; `CellFlags.ON_PHONE/ON_NAS/SAFE` (bit 10–12); `WorkStage.HQ`, `WorkStage.FULL_HASH`; `MediaGroupKind.PROBABLE_DUPLICATE`; `NasStamp` | P2 |
| `core-database` | schema v3 (§2.2, §2.3); `MediaCatalog`: `recordAll` con cursore NAS, `confirmAllPresentNas`, `markVanishedInShare(share, epoch, cap)`, `markShareUnavailable`, `adoptIdentity`, `nasRefs`, code di lavoro esposte; `nextPending` delle code del telefono **filtrato sulle posizioni del telefono** | P2 |
| `core-metadata` | `ExtractHints` con i nomi neutri (oggi `ms*`); `PhoneFingerprints` reso generico su `MediaBytes` (`Fingerprints.quick/full`) | P2 (solo spostamento, stessi test) |
| `core-rules` | `run/StagedRun` (protocollo valuta → lotto → registro del traffico → punto di ripresa); `BacklogSource` dal catalogo; `WaitReason.Frozen`; tetto del rilevamento fuori casa | P9 |
| `core-family` | `LocalDeviceProfile.privateShare` esposto ai moduli nuovi; nessuna logica nuova | P0 (firma) |
| `core-common` | `DiagSection.NAS_LIBRARY` | P0 |
| `feature-diagnostics` | codici e chiavi d'esportazione della Fase 4 | P11, P16 |
| `feature-library` | `LibraryTabState.nas: LibraryNasStatus`; voci di Raccolte da un elenco fornito da `:app` (`CollectionEntries`) | P7 (dati), P14 (voci) |
| `feature-viewer` | menu a capacità: "Scarica sul telefono" (P8), "Sposta", "Rinomina", "Elimina…" con scelta del luogo (P14) | P8, P14 |
| `feature-settings` | "Qualità delle anteprime" con la stima, "Mostra le copertine degli album" | P11 |

### 1.4 Cosa resta in `:app` (P11 per la 4A, P16 per la 4B)
- **`NasGraph`** in `app/.../nas/`: nasce con la famiglia `Ready` come il catalogo; legge account, area privata e area comune da `LocalDeviceProfile` e dal registro accettato; un solo `IndexPublisher`, un solo `IndexSync`, un `NasScanner` per share.
- **Lavori** (`app/.../work/NasWork.kt`, tutti con `runIfActive` e `StagedRun`):
  - `NasScanWorker` (`INDEX_SYNC`): verifica senza novità, rilevamento dei nuovi; a ogni apertura dell'app se l'ultima verifica ha più di 15 minuti, di notte, e quando i dischi sono svegli;
  - `IndexSyncWorker` (`INDEX_SYNC`): teste e segmenti secondo la qualità;
  - `PreparationWorker` (`PREPARATION`, solo Preparatori);
  - `HqPreviewsWorker` (`HQ_PREVIEWS`, solo con "Alta");
  - `VerifyWorker` (`PHOTO_BACKUP`/`VIDEO_BACKUP`): verifica delle copie private e hash completi `FULL_HASH`;
    > **Nota (Fase 5, 16/10/2026): non realizzato nella Fase 4.** Il `VerifyWorker` non è stato scritto, e non c'è l'esecutore della fase `FULL_HASH` che il catalogo mette in coda (F4-D21): nessun codice chiama `markVerified`, quindi oggi il segno "al sicuro" non può comparire. Passano alla Fase 5: `SafetyVerifier` in `core-backup` e `SafetyVerifyWorker` in `:app` (D14, P3 di `docs/fase5-progetto.md`).
  - 4B: `NasOpsJobService` (UIDT), `NasTrashPurgeWorker`, `PersonalSyncWorker`, `IndexMaintenanceWorker` (compattazione e pulizia dei propri file).
- **Notifiche**: congelamento **subito** (eccezione alla proposta 12, domanda Q6); "il NAS non ha risposto stanotte" e "attività ferma da N giorni" nel riepilogo giornaliero.
- **Azzeramento**: `GalleryGraph.shutdown()` ferma anche `NasGraph`; i pacchi stanno già in `replica/`, il diario delle operazioni e quello del cestino nel database, quindi `AppLocalDataEraser` li toglie senza modifiche. Dopo un azzeramento i file nel cestino del NAS restano **per sempre** (nessun diario, nessuna pulizia): è il comportamento sicuro, e la guida lo dice.
- **Emergenza**: Diagnostica → "Ferma le scritture sul NAS" (D31) e "Scongela" (D10).

### 1.5 Confine con il filone grafico: stati e contratti
La Fase 4 decide **cosa** si mostra e **quando**; il filone grafico decide **come**. Gli stati vivono nei moduli `core-*` (contratti) e nei ViewModel; le composable della Fase 4 sono minime e sostituibili.

| Stato (contratto) | Casi | Dove si vede | Di chi è l'aspetto |
|---|---|---|---|
| `PresenceBadge` (da `CellFlags`) | `PHONE_ONLY`, `NAS_ONLY`, `BOTH`, `SAFE`, `TO_VERIFY` (E-L9) | cella della griglia (`GalleryBadge` già nel catalogo 1.2) | filone grafico |
| `LibraryNasStatus` | `Idle`, `FirstScan(done, total, eta)`, `Aligning(bytesDone, bytesTotal, eta)`, `UpToDate(at)`, `Frozen(reason, since)`, `AreasUnavailable(areas)`, `LocalOnly` (D31) | piede della Libreria (`statusFooter`, slot di `:app`) | filone grafico |
| `ViewerImageState` (esistente) | `Preview`, `Sharp`, `Loading(progress)`, `Unavailable(NAS_UNREACHABLE / NEEDS_MOBILE_DATA_CONFIRMATION, size)`, `Failed` | viewer | filone grafico (le barre); la Fase 4 solo le transizioni |
| `NasFolderState` | `Loading`, `Listing(entries, readOnly, cachedAt)`, `Offline(cached)`, `Denied`, `Error(code)` | Raccolte → Cartelle NAS | Fase 4 (funzionale), poi grafico |
| `NasSpaceState` | `free`, `total`, `quota?`, `largest[50]`, `trashBytes`, `indexBytes`, `versionsBytes` (0 fino alla Fase 5), `measuredAt` | Spazio NAS | idem |
| `NasTrashState` | voci (nome, cartella d'origine, dimensione, giorni rimasti per ora del NAS, autore, `reason`, `folder`, ripristinabile, "senza record valido") | Eliminati di recente del NAS | idem |
| `NasOperationState` | `Planned(summary)`, `Running(done, total, bytes)`, `Paused(reason)`, `WaitingLocked(n)`, `Done(summary)`, `Failed(code)`, `Cancelled` | Operazioni in corso, notifica UIDT | idem |
| `ReplicaQualityState` | livello, stima per livello, spazio attuale, convergenza in corso | Impostazioni | Fase 4 |

**Chi tocca cosa**
- La Fase 4 **non** modifica `core-designsystem` (se non registrando sezioni nuove del catalogo in file propri), le barre, i comandi in alto, il vetro, la pillola, la cella.
- Il filone grafico **non** modifica i contratti e i ViewModel elencati sopra; se un aspetto chiede un dato in più, lo chiede all'orchestratore come modifica di contratto.
- File condivisi a rischio: `feature-library/.../collections/CollectionKind.kt` e `CollectionsState.kt` (la Fase 4 aggiunge **dati**, il grafico **composable**), `MainScaffold.kt` (lo tocca solo l'integrazione). Le voci nuove di Raccolte arrivano da `CollectionEntries` fornito da `:app`, così il codice della schermata non cambia per la Fase 4.
- Stringhe in file propri (`strings_nas_*.xml`).

## 2. Flussi

### 2.1 Indice condiviso: formato e pubblicazione (4.1, P3)
**Contenuto dei segmenti (`MCGB` v1, proposta per il §5.5-bis)**
```
Segments.wrap:  "MCGB" | versione (1) | contenuto
contenuto v1:
0    1   classe (1 meta, 2 thumbS, 3 thumbM, 4 analysis, 5 hq, 6 frames, 16 personal; 7-15 e 17-31 riservate)
1    3   riservati = 0
4    4   N voci (1..4096), big endian
8    N×36  tabella: chiave (32 byte) + lunghezza (4); chiavi strettamente crescenti
...  corpi delle voci, nell'ordine della tabella, senza spazi né byte in più
```
- **Chiavi**: `meta` = SHA-256("mcg-path\0" ‖ percorso piegato NFC e minuscolo); `thumbS`, `thumbM`, `analysis`, `hq`, `frames` = **chiave di contenuto** SHA-256("mcg-ck\0" ‖ `qh1` ‖ dimensione); `personal` = §2.9.
- **Corpi**: `meta` e `personal` = JSON canonico (formato §3.2, ≤ 4 KiB); immagini = JPEG (`FF D8 FF`) o WebP (`RIFF…WEBP`) ≤ 1 MiB, **dimensioni lette dall'intestazione e controllate con `ImageBounds` prima di qualunque decodifica**; `frames` = conteggio (1 byte, ≤ 9) e per ogni fotogramma istante in ms (4) + lunghezza (4) + JPEG, ≤ 2 MiB in tutto.
- **Lettura**: dimensione e impronta coincidono con la testa (`Segments.unwrap`) → classe attesa → tabella coerente (somma delle lunghezze = resto del file) → ogni corpo con i suoi tetti. Un errore scarta **il segmento intero**, lo conta in Diagnostica (`INDEX_REJECTED`) e non ferma gli altri.

**Record `meta`** (campi fissati, ≤ 32; quelli facoltativi si omettono)
| Campo | Forma | Note |
|---|---|---|
| `path` | percorso di media (formato §8.1, mai dentro `.mycloudgallery`) | relativo alla share della testa |
| `seq` | intero crescente per (dispositivo, share) | D5 |
| `gone` | `true` | lapide: solo `path` e `seq` |
| `size`, `mtime`, `fid` | interi; `fid` facoltativo | il timbro (D6) |
| `qh`, `fh` | 52 caratteri base32; `fh` facoltativo | `qh1` e SHA-256 |
| `media`, `version`, `parent` | id da 128 bit in base32; `parent` facoltativo | D7 |
| `vkind` | `original` / `edited` / `corrected` | |
| `kind`, `src`, `mime` | codici stabili | come `MediaKind`, `MediaSource` |
| `taken`, `offset`, `conf`, `origin` | ms UTC, minuti, enum, enum | `CaptureTime` |
| `w`, `h`, `dur`, `traits` | interi | già orientati |
| `geo` | `[lat×1e7, lon×1e7]` | facoltativo; sta nella stessa share del file che lo contiene già |
| `th` | ThumbHash in base32 (≤ 58 caratteri) | segnaposto |
| `prep` | maschera delle classi pubblicate per la chiave di contenuto | |
| `fail` | `{"code":…,"dec":…}` | D14; `dec` = famiglia del decoder del dispositivo |

**Pubblicazione** (`IndexPublisher`)
| Momento | Cosa | Dettagli |
|---|---|---|
| Durante il lavoro | `stage(share, records, blobs)` | record nel database (outbox), blob in un pacco temporaneo per classe in `noBackupFilesDir/index-outbox/`; `seq` assegnato qui, nella stessa transazione |
| Segmento pieno (≥ 12 MiB) o fine dell'unità | `publishExclusive(seg/<impronta>)` | nome = impronta, quindi una ripetizione dopo un crash trova "esiste già" con gli stessi byte = successo |
| Dopo i segmenti | testa nuova `version+1` con `registryVersion` e tutti i segmenti | al massimo una ogni 5 minuti per share (M3); `NewerExists` non esiste per le teste: le scrive solo il dispositivo stesso |
| Controlli prima di firmare | `TrustGuard.preflight` → `canWrite`; congelamento spento (D10); D31; nessun salto d'ora (D32); **ogni record e ogni blob viene da un file di questa share** | la regola M3 è anche un test di proprietà: nessun byte ricavato da un file dell'area privata finisce nell'indice dell'area comune |
| Crash fra segmento e testa | segmento orfano | lo toglierà la pulizia dei propri file (4B); nessun danno |
| `DISK_FULL` o quota (E-I6) | si pubblica per priorità: `meta` > `thumbS` > `thumbM` > `analysis`/`frames` > `hq` | il resto resta nell'outbox; avviso in Diagnostica e nello stato |

**Unità di lavoro** (`WorkUnits`)
- Un'unità = (share, passaggio `prep`, mese di scatto, parte). Le parti si ricavano ordinando per chiave di contenuto i media del mese senza le classi richieste e dividendo per 200.
- `next()`: sceglie l'unità più recente con lavoro (le visibili prima, D12), legge le teste della share, toglie ciò che altri hanno già pubblicato, poi `LeaseManager.tryAcquire("work/1/prep/2024-03/2", 1 h)`. `Busy`/`Lost` → unità successiva; `Contended` → si lavora senza prenotazione e si conta.
- `keepAlive()` ogni 20 minuti (generazione nuova); `finish()` rilascia (durata 0).
- Un dispositivo revocato o morto: le sue prenotazioni scadono entro l'ora (E-I2) o non valgono subito (E-I15).

| Edge case | Comportamento |
|---|---|
| E-I1 | Prenotazione per unità; se due lavorano comunque sullo stesso media, i record e i blob sono equivalenti e la fusione D6 sceglie in modo deterministico |
| E-I2, E-I16 | Scadenza di 1 ora; durata oltre il tetto o inizio oltre 2 minuti nel futuro = risorsa libera (già in `LeasePolicy`) |
| E-I3 | Segmento di versione più nuova: non letto, "aggiorna l'app", nessuna scrittura sopra |
| E-I6 | Priorità delle classi, outbox che aspetta, avviso |
| E-I14 | Codec stretto, tetti, `ImageBounds`, JSON canonico; rifiuto senza crash, conteggio per motivo |
| E-I15 | `HeadVerifier` rifiuta le teste di revocati e persi; i loro media tornano "da preparare" |
| E-I19 | Senza permesso di scrittura (`ShareProbe` = `READ_ONLY`) l'outbox di quella share non si apre: si prepara solo in locale |
| E-N12 | Timbro diverso → il record non vale (D6) |

### 2.2 Replica locale e sincronizzazione (4.1, P4)
| Momento | Cosa | Dettagli |
|---|---|---|
| Prima di partire | guardia, percorso, regole | `TrustGuard.preflight` (registro fresco, al massimo 15 minuti); `NasRouteGate` consentito; `ActivityRulesEngine.evaluate(INDEX_SYNC)` |
| Per ogni share configurata | dispositivi | elenco di `devices/` (≤ 256 nomi), tenuti solo gli id del registro accettato con stato `approved` o `retired` |
| Per ogni dispositivo | teste | `HeadSeries.readLatest` dalla versione nota: di solito **una** lettura che risponde "non trovato" = nessuna novità; `HeadMark` salvato nel database nella stessa transazione dell'applicazione |
| Testa nuova | differenza | segmenti nuovi da scaricare, segmenti tolti da dimenticare (D5) |
| Scaricamento | classi | sempre `meta`; `thumbS` / `thumbM` / `hq` secondo la qualità (D16); `analysis` e `frames` mai nella Fase 4 (Indicizzatori: Fase 6). A blocchi con `readAll` (4 flussi), verifica di dimensione e impronta, poi codec stretto |
| Applicazione | database | per segmento `meta`: righe in `index_record` (dispositivo, segmento, chiave, `seq`, campi); scelta del record vincente per percorso (D6); osservazioni al catalogo (`recordAll` con `WhenUndecided.KEEP_SEPARATE`), adozione d'identità (D7); blob indicizzati in `replica_blob` (chiave di contenuto, classe, pacco, offset, lunghezza) |
| Dopo | stato | `IndexSyncStatus.UpToDate(at)`; `change_seq` sale solo se l'indice della timeline cambia |
| Quando | frequenza | con l'app aperta a ogni apertura (se l'ultima ha più di 15 minuti) e dopo ogni propria pubblicazione; in background con `lastDiskTouchAt` recente (10 minuti), altrimenti al massimo ogni 6 ore e nella fascia notte |

**Primo allineamento di un dispositivo nuovo** (E-I9, E-I10): stima dai byte delle classi elencati nelle teste (conosciuti **prima** di scaricare), progresso `Aligning(bytes, total, eta)`, prima i `meta` (la Libreria si riempie di segnaposto in pochi secondi), poi le miniature dal mese più recente. Sui dati mobili solo metadati e ciò che è sullo schermo (D23).

**Verifica senza novità** (Fatto quando della 4.1): per share un elenco di `devices/` e una lettura per dispositivo; con 3 share e 6 dispositivi sono ~20 richieste, **sotto 1 s in casa** e sotto 3 s via VPN.

| Edge case | Comportamento |
|---|---|
| E-I4 | Classi `vec` riservate: i vettori arriveranno etichettati per spazio (Fase 6); oggi rifiutate |
| E-I5 | Segmento assente o corrotto: rifiutato, i dati già nella replica restano, i media non coperti tornano "da preparare" (E-I17); indice cancellato del tutto: le teste non si trovano più, la replica resta com'è e i Preparatori rifanno il lavoro; un `HeadMark` più alto di ciò che si trova vale come "rollback" e va in Diagnostica |
| E-I7 | Replica cancellata (o nuova installazione): ricostruzione dalle teste, identica (D5), secondo le regole |
| E-I8, E-R10 | `ACCESS_DENIED` o `BAD_NETWORK_NAME` su una share in **due giorni diversi** di ora del NAS → i dati di quella share si tolgono dalla replica (solo locali), con avviso; un errore solo resta "non raggiungibile" |
| E-I10, E-I12 | Chi non prepara riceve tutto; se un'area non ha Preparatori attivi (stato dei dispositivi: ruoli + account) lo dice lo stato dell'area |
| E-I11 | Le anteprime di analisi restano sul NAS; la Fase 6 le scaricherà in un buffer secondo le regole |
| E-I13 | Cartella col punto, nessuna estensione, firme `MCGS`/`MCGB`; prova F14 |
| E-I15, E-S14 | Dati di dispositivi non approvati, revocati o persi: ignorati; un dispositivo in attesa legge ma non pubblica |
| E-I18 | Adozione del minore (D7) |

### 2.3 Scansione del NAS (4.2, P5)
| Momento | Cosa | Dettagli |
|---|---|---|
| Avvio | cursore | `nas_scan_state` per (account, share): epoca, cartella di ripresa, ultima scansione completa, congelamento; ripresa dalla cartella dopo un riavvio (E-P4) |
| Percorso dell'albero | cartelle | dalla radice, in ampiezza, **solo le cartelle incluse** (D11); per ogni cartella `list()` a pagine; nomi non validi per `SharePath` (E-N6) contati e saltati; visitate per `fileId` con profondità ≤ 64 (E-N10); attributo "punto di rianalisi" mai seguito |
| Per ogni file | timbro | `(size, modifiedAt, fileId)`; confronto con il catalogo: uguale → `confirmAllPresentNas` (nessuna lettura) |
| File nuovo o cambiato | indice | record di un dispositivo approvato con lo stesso timbro → adozione senza leggere il file (D6) |
| Altrimenti | rilevamento | `open` → testa e coda da 64 KiB (o tutto fino a 128 KiB) → `qh1`; `MediaSniffer` (E-M12); metadati con `DefaultMetadataExtractor` su `NasMediaBytes` (per i video letture in più fino a 4 MiB per l'indice del contenitore); miniatura EXIF → ThumbHash e miniatura provvisoria (D17); `Observation` con `Location.Nas` |
| Lotto | catalogo | `recordAll` a lotti di 500 con il cursore NAS nella stessa transazione; `LinkingRules` decide (spostamento con indizio indipendente: data di modifica uguale **o `fileId` uguale**, E-N1) |
| Fine della share | assenze | solo se il giro è completo e non c'è congelamento: `markVanishedInShare(share, epoch, cap = 500)` ripetuto finché serve; tolleranza di 30 giorni (`LocationRules`) |
| Prima di segnare le assenze | congelamento | `MassChangeGuard.judge` (D10) |

**File stabili** (E-N4): un file nuovo o cambiato da meno di 2 minuti di ora del NAS (data di modifica vicina all'ora del NAS), con nome temporaneo (`*.part`, `*.tmp`, `~$*`, `.~lock*`, `*.crdownload`) o che cresce fra due elenchi resta "in arrivo" e non si rileva finché non è visto uguale due volte.

**Rilevamento fuori casa**: al massimo 50 MB per giro (D22); il resto aspetta il Wi-Fi di casa. Con l'app aperta, i media visibili si rilevano subito come contenuto visibile.

| Edge case | Comportamento |
|---|---|
| E-N1 | Spostato o rinominato dal PC: `Moved` (stessa dimensione e `qh1`, più data o `fileId`); identità, preferiti e preparazione conservati; la chiave di contenuto non cambia (D4) |
| E-N2 | Modificato dal PC: stesso posto, byte diversi, continuità → `ContentChanged`, nuova versione; preparazione rifatta solo per la versione nuova |
| E-N3 | Cancellato dal PC: assente dopo il giro completo, esce dalla Libreria senza passare dal cestino dell'app |
| E-N4 | In arrivo: ignorato finché è stabile |
| E-N5 | Cartelle enormi (8.910 file in una sola in `alessio`): pagine da 1.000, lotti da 500, nessuna lista intera in memoria |
| E-N6 | Accenti, emoji, nomi lunghi: ammessi entro `SharePath`; caratteri vietati o di confusione: saltati e contati ("nomi non gestibili") |
| E-N7 | Nomi diversi solo per maiuscole: `location_key` piegata, nessun doppione fantasma |
| E-N8, E-N16 | Cartelle di sistema, cestini del NAS e cartelle col punto: escluse |
| E-N9 | Cartella in sola lettura: si legge; le azioni di modifica della 4B si spengono con la spiegazione |
| E-N10 | Collegamenti ricorsivi: `fileId` visitati, profondità ≤ 64, punti di rianalisi non seguiti |
| E-N11 | Due familiari sullo stesso file: la scansione vede l'ultimo stato; vale l'ultimo per ora del NAS |
| E-N12 | Indice vecchio rispetto al file: timbro diverso, si rileva di nuovo |
| E-N13 | File bloccato dal PC (`SHARING_VIOLATION`): si riprova al giro dopo, mai "corrotto" |
| E-N14 | Copertine degli album escluse di default (D11), conteggio in Diagnostica, opzione per mostrarle |
| E-N15 | Cartelle di musica, film e serie escluse all'onboarding: mai elencate oltre la radice dell'esclusione |
| E-S11 | La scansione non cancella né sposta nulla: cambia solo lo stato nel catalogo locale |
| E-M12 | Zero byte o falsi media: contati, mai in Libreria |
| E-R9 | NAS pieno: la lettura continua |
| E-R15 | Scansioni in background quando i dischi sono già svegli o nella fascia notte |

**Congelamento** (D10): soglie su (spariti, cambiati, "non più media" con un'estensione nuova tipo `.locked`), ognuna ≥ max(50, 5% dei file presenti nella share). Stato `Frozen(reason, since)` salvato nel database; i lavori vedono `WaitReason.Frozen`; la Libreria mostra i media come prima; scongelamento con "Ho controllato, continua" (doppia conferma con il conteggio) oppure da solo quando una scansione completa torna sotto le soglie.

### 2.4 Preparazione (4.3, P6)
| Momento | Cosa | Dettagli |
|---|---|---|
| Scelta del lavoro | unità | `WorkUnits.next(share, PREP, priority)`: prima le unità con media visibili sullo schermo (priorità passata da `:app` come in `MetaPriority`), poi dal mese più recente (proposta 11) |
| Foto ≤ 64 MiB | lettura | `readAll` in memoria con SHA-256 durante la lettura (**hash completo gratis**) |
| Foto > 64 MiB (DNG, 200 MP, panorami) | temporaneo | in `cacheDir/prep/` (nome da `LocalNames`) con hash, tetto 512 MiB; oltre: `TOO_LARGE`, segnaposto |
| Decodifica | una volta | `ImageDecoder` alla taglia HQ (2048) con orientamento e spazio colore (E-V3, E-M15); poi riduzioni a 1024, 400, 200 dalla bitmap HQ; `PrepBudget` per i pixel (E-M1–M4) |
| Video | senza scaricarli | `MediaMetadataRetriever` su un `MediaDataSource` sopra `NasRandomFile` (letture a intervalli con cache da 4 MiB): copertina e fotogrammi chiave (`OPTION_CLOSEST_SYNC`), uno ogni 30 s, da 1 a 8 (E-A9); fuso dal GPS del video (E-M14) |
| Motion photo | foto | la foto ferma; la parte video resta nel file (E-M5) |
| Animati | primo fotogramma | GIF e WebP animati (E-M11) |
| Uscite | classi | `thumbS`, `thumbM` (WebP), `analysis` (JPEG q90), `hq` (WebP), `frames` (JPEG 1024); ThumbHash dalla `thumbS`; record con `prep`, `fh` (foto) e metadati corretti dalla decodifica |
| Pubblicazione | outbox | `IndexPublisher.stage`, poi `flush` a fine unità; con D31 attivo restano solo nella replica locale |
| Errori | codici | `DECODE_FAILED` (5 tentativi poi `GAVE_UP`, E-M10), `DECODER_MISSING` (D14), `TOO_LARGE`, `NAS_UNREACHABLE` (si riprova senza contare) |

> **Nota (Fase 6, 21/10/2026), alla politica R2 della 4A** (`docs/decisioni.md`, «Revisione di sicurezza della 4A: correzioni»: su una
> share non pubblicabile la preparazione lavora solo in locale, senza outbox, e la replica non tiene mai l'anteprima di analisi né i
> fotogrammi, quindi con D31 acceso quelle anteprime si buttavano): **sugli Indicizzatori `analysis` e `frames` vanno al buffer**
> (`AnalysisSink` di `core-prepare`, implementata da `core-ai`), sia dalla preparazione sia dal backup (D10 della Fase 5), così non si
> rileggono gli originali e il calcolo AI le trova. Sulle share pubblicabili non cambia niente (vanno all'indice, `IndexPublisher`);
> sui dispositivi che non sono Indicizzatori non cambia niente. Fonte: `docs/fase6-progetto.md`, D10.

**Velocità e risorse**: due media in parallelo al massimo (la CPU del NAS è il collo di bottiglia: 16 MB/s in lettura cifrata con 4 flussi); `Pace.REDUCED` con il telefono caldo; tappe da 8 minuti che salvano l'unità a ogni media.

| Edge case | Comportamento |
|---|---|
| E-M1–M4 | HEIC, DNG, 200 MP, panorami: decodifica campionata con il tetto di pixel, mai il file intero in bitmap |
| E-M5 | Motion photo: foto ferma nelle anteprime |
| E-M6, E-M13 | Raffiche e RAW+JPEG: gruppi del catalogo come nella Fase 3 (`GroupingRules` sui nomi del NAS) |
| E-M7 | Video da più GB: solo indice e fotogrammi, mai scaricati |
| E-M8, E-M9 | Senza EXIF o con date assurde: data dal nome o dal file, "stimata" (`CaptureTimeResolver`) |
| E-M10 | Corrotto: "non leggibile" dopo 5 tentativi, mai bloccante |
| E-M11 | Animati: primo fotogramma nelle anteprime, animazione nel viewer |
| E-M12 | Falsi media: già fuori dalla scansione |
| E-M13 | Codec non decodificabile qui (APV, 8K): `DECODER_MISSING` pubblicato, passa a un altro Preparatore (D14) |
| E-M14 | Foto in ora locale e video in UTC: fuso dal luogo |
| E-N5 | Le unità dividono anche le cartelle enormi |

### 2.5 Unificazione e Libreria (4.4, P7)
| Aspetto | Comportamento |
|---|---|
| Collegamento | `LinkingRules` invariate. Per un media del telefono e un file del NAS con la stessa dimensione: `qh1` letto **qui** dal NAS (128 KiB), hash completo del telefono calcolato qui, hash completo del NAS dall'indice se coerente (D8) o dal passaggio `FULL_HASH` |
| Badge | `PHONE_ONLY` (solo telefono), `NAS_ONLY` (solo NAS), `BOTH` (entrambi), `SAFE` (copia privata verificata, `SafetyRules`), `TO_VERIFY` (E-L9); i bit si ricalcolano nella stessa transazione dei derivati |
| "Al sicuro" | `VerifyWorker` rilegge per intero la copia nell'**area privata** di un media del telefono e confronta lo SHA-256 (`markVerified`); le foto prima, i video con le loro regole. Nessun dato remoto basta (D8) |
| Una volta sola | identità per impronta: E-L1 e E-L3 come oggi; i video in attesa dell'hash completo in un gruppo provvisorio (D21) |
| Miniature | telefono: miniature di sistema; NAS: replica (S o M secondo la cella), poi miniatura provvisoria, poi decodifica dell'originale solo come contenuto visibile (D17) |
| Indice della timeline | `MCGT2`; un file `MCGT1` si scarta e si ricostruisce (come previsto dal formato) |
| Qualità | `ReplicaQuality.set(level)` → stima dello spazio (dai byte delle classi nelle teste), poi scaricamento (`HQ_PREVIEWS` o `INDEX_SYNC`) o pulizia locale graduale (≤ 200 MB per giro) |
| Stato NAS nella Libreria | `LibraryNasStatus` nel piede (§1.5) |
| Prestazioni | guardia del §5.6 |

| Edge case | Comportamento |
|---|---|
| E-L1 | Stesso media su telefono e NAS: uno, con due posizioni e badge "entrambi" |
| E-L2 | Scaricato dal NAS: due posizioni, nessun ricaricamento (D20) |
| E-L3 | Identici in più cartelle: uno (hash completo); i simili vanno in Doppioni (Fase 7) |
| E-L4 | Solo NAS con NAS irraggiungibile: miniatura dalla replica e `Unavailable(NAS_UNREACHABLE)` nel viewer |
| E-L5 | Anteprima assente (Risparmio o non ancora preparata): segnaposto sfocato (ThumbHash) |
| E-L6 | Cambio di qualità: stima, poi pulizia o scaricamento graduale; prova F12 |
| E-L7 | 100 mila elementi: guardia del benchmark (§5.6) |
| E-L8 | Correzioni di data o luogo: Fase 5 (versione scritta nel file); la Fase 4 mostra la versione corrente dall'indice |
| E-L9 | Video da verificare: gruppo provvisorio |

### 2.6 Viewer e "Scarica sul telefono" (4.4, P8)
| Aspetto | Comportamento |
|---|---|
| Foto del NAS | `Preview` dalla miniatura → `Sharp` dall'HQ della replica (subito, anche offline) → con lo zoom, l'originale letto a intervalli in un temporaneo (`Loading(progress)`) e poi i tasselli |
| Video del NAS | `NasVideoDataSource` (letture a intervalli, lettura anticipata da 4 MiB, annullabile); `Loading` fino al primo fotogramma; scorrimento preciso come nella Fase 3 |
| Dati mobili | `Unavailable(NEEDS_MOBILE_DATA_CONFIRMATION, size)`: "Questo video pesa 1,2 GB. Guardarlo con i dati mobili?" (E-V2); conferma valida per quel media e quella sessione |
| Scorrimento veloce | precarica solo ±1 e annulla il resto (E-V5) |
| Media sparito | eliminato da un altro dispositivo mentre lo guardi: chiusura gentile (E-V6) |
| NAS irraggiungibile | `Unavailable(NAS_UNREACHABLE)` con la miniatura (E-L4) |
| Scarica sul telefono | dal menu ⋮ o dalla selezione: `PhoneMediaWriter` inserisce in `Pictures/MyCloud Gallery` con `IS_PENDING=1`, copia con SHA-256, poi `attachCopy` sul catalogo con l'impronta e infine `IS_PENDING=0`: la lettura del telefono trova `SameFile` (E-L2). Progresso, annullamento (il temporaneo si toglie), nomi in conflitto con "(2)". Nessun permesso nuovo (file creati dall'app) |
| Condividere un media solo-NAS | Fase 5 (E-V7) |

### 2.7 Operazioni sui file, cestino e spazio (4.5, P12, 4B)
**Il diario** (`nas_op`, `nas_op_item` nel database; ogni passo si scrive **prima** di farlo, ogni passo è idempotente)
| Operazione | Stati di un elemento | Ripresa dopo un'interruzione |
|---|---|---|
| Crea cartella | `PLANNED → DONE` | `mkdirs` di nuovo ("esiste già" = fatto) |
| Rinomina, sposta nella stessa share | `PLANNED → RENAMING → DONE` | sorgente assente e destinazione con lo stesso `fileId` e dimensione = fatto (risposta persa); sorgente presente = si rifà; destinazione occupata da altro = nome alternativo, salvato nel diario prima di riprovare |
| Rinomina solo di maiuscole (E-N7) | `PLANNED → TEMP_NAME → DONE` | due rinomine passando da un nome temporaneo nella stessa cartella |
| Sposta tra share | `PLANNED → COPYING → COPIED → VERIFIED → PUBLISHED → SOURCE_RECORDED → SOURCE_TRASHED → DONE` | `COPYING`: temporaneo proprio con la dimensione giusta = `COPIED`, altrimenti si cancella e si rifà; `VERIFIED`: SHA-256 del temporaneo uguale a quello della sorgente (calcolato se non noto, controllando prima che il timbro della sorgente non sia cambiato, E-D13); `PUBLISHED`: rinomina senza sostituire, con il nome deciso e salvato; dopo una risposta persa, temporaneo assente + destinazione con dimensione e hash giusti = pubblicato; `SOURCE_RECORDED`: record del cestino (`reason = move`) e voce del diario locale del cestino; `SOURCE_TRASHED`: rinomina della sorgente nel cestino della sua share, solo se il timbro è ancora quello verificato |
| Elimina (file o cartella) | `PLANNED → RECORDED → TRASHED → DONE` | prima il record e la voce del diario locale (un record senza file è innocuo e non si mostra), poi la rinomina nel cestino |
| Ripristina | `PLANNED → RESTORING → DONE` | rinomina verso il percorso originale; cartella sparita = ricreata (E-D3); nome occupato = nome alternativo |
| Pulizia del cestino scaduto | `TrashPurgePolicy` → `deleteIfUnchanged` | solo voci del diario locale, 30 giorni di ora del NAS, 200 file e 20 GiB per giro, nessun congelamento, nessun salto d'ora (E-D4) |

**Regole comuni**
- Ogni operazione nasce da un `UserConfirmation` (D24): foglio di riepilogo con numero e dimensione ("Spostare 312 elementi, 4,1 GB, in Famiglia/Viaggi?"). Nessun dato letto dal NAS crea voci del diario (test di architettura e test "dato remoto ostile").
- Prima di uno spostamento tra share: `space()` della destinazione ≥ dimensione + 1 GiB (E-D5).
- Eliminazioni di massa: riepilogo, conferma, annullabili finché i file sono nel cestino (E-D7).
- Operazioni lunghe: UIDT con notifica di progresso e "Ferma"; "Annulla" ferma prima dell'elemento successivo, toglie i propri temporanei e lascia fatti quelli fatti ("Riporta indietro" è un'operazione nuova).
- File bloccato dal PC: elemento `WAITING_LOCKED`, si riprova con attese crescenti fino a 24 ore (E-N13), visibile in "Operazioni in corso".
- La compattazione dell'indice e la pulizia dei propri file (M6) si accendono qui, con le stesse condizioni della pulizia del cestino.

**Spazio NAS** (`NasSpaceState`, si calcola solo quando l'utente apre la pagina):
- spazio libero e totale da `space()` (lo spazio "del chiamante" diverso da quello del disco = **quota**, E-N13);
- file più grandi dal catalogo (nessuna chiamata al NAS);
- cestino: dai record e dal diario locale;
- indice: somma delle dimensioni dei segmenti nelle teste accettate;
- versioni conservate: "—" fino alla Fase 5.
- Nota: sulla share `NAS` il cestino del WD è acceso, quindi lo spazio dei file eliminati per sempre si libera solo quando lo svuota il WD; la pagina lo dice.

| Edge case | Comportamento |
|---|---|
| E-D1 | Media su telefono e NAS: "Solo dal telefono" (cestino di Android) / "Ovunque" (cestino di Android + cestino del NAS) |
| E-D2 | Solo sul NAS: cestino del NAS per 30 giorni |
| E-D3 | Ripristino con la cartella sparita: ricreata |
| E-D4 | Pulizia: solo diario locale, record, ora del NAS, tetti, handle |
| E-D5 | Poco spazio sulla destinazione: controllo prima, mai spostamenti a metà |
| E-D6 | Spostamento interrotto: il diario riprende, mai doppi visibili né persi (prova delle 500 interruzioni) |
| E-D7 | Eliminazione di massa: riepilogo, conferma, annullabile |
| E-D8 | Album condivisi: dalla Fase 7 |
| E-D9 | Nome non valido: correzione suggerita (caratteri vietati sostituiti, spazi finali tolti) |
| E-D10 | Cartella intera: una sola rinomina nel cestino, ripristinabile |
| E-D11 | "Ovunque" con copie in aree senza permesso: agisce solo dove si può e mostra l'elenco del resto |
| E-D12 | Copia lato server rifiutata: ripiego dal telefono come attività pesante |
| E-D13 | Sorgente cambiata durante la copia: stop dell'elemento, nessun cestino, avviso |
| E-N9 | Cartella in sola lettura: azioni spente con la spiegazione |
| E-N11 | Due dispositivi sullo stesso file: il secondo trova `SourceMissing`/`SourceChanged` e lo dice ("spostato da un altro dispositivo") |
| E-N13 | Bloccato: si riprova; quota: messaggio specifico |
| E-S11 | Un finto cestino o un finto record non fanno eliminare né spostare nulla |

### 2.8 Regole applicate e stime (4.6, P9 e integrazioni)
| Lavoro | Attività | Note |
|---|---|---|
| Verifica senza novità, rilevamento, sincronizzazione dell'indice, dati personali | `INDEX_SYNC` (leggera) | rilevamento fuori casa ≤ 50 MB per giro; con l'app aperta come contenuto visibile |
| Preparazione | `PREPARATION` (pesante, Preparatore) | solo Wi-Fi di casa con "pesanti solo a casa" |
| Scaricamento delle anteprime HQ | `HQ_PREVIEWS` | solo con la qualità "Alta" |
| Verifica "al sicuro" e `FULL_HASH` | `PHOTO_BACKUP` / `VIDEO_BACKUP` | i video con le loro regole; prepara la Fase 5 |
| Operazioni sui file | `UserInitiated` | dati mobili: conferma con la stima (le verifiche leggono) |
| Pulizia del cestino, compattazione, pulizia dei propri file | `INDEX_SYNC` + condizioni del §2.7 | al massimo una volta al giorno |

- **Lavori a tappe** (`StagedRun`): `evaluate(Background)` → lotto → `evaluate(running = true)` → `ledger.record(sorgente, rete usata, byte)` subito → punto di ripresa; `Stop` salva ed esce; tappe da 8 minuti; in carica i limiti dei bucket non valgono (`android.md` 1.1).
- **Stime** (`ConsumptionEstimator`): l'arretrato vero (`BacklogSource`) per attività da catalogo e teste (elementi e byte); le costanti di `DeviceMeasures.PHASE_0` restano finché la settimana di prova (F18) non le ricalibra.
- **NAS che non risponde nella fascia** (E-R5): il lavoro locale continua (miniature provvisorie, pacchi); la mattina dopo una notifica nel riepilogo con "Consenti anche di giorno". Il WoL resta spento (`FeatureFlags.wol`).
- **Attività ferma da N giorni** (E-P15): il battito per attività (`HeartbeatStore`) vale anche per scansione, indice e preparazione; il backup arriva nella Fase 5.
- **Profilo non attivo** (E-P19), riavvii (E-P13), limiti di Android (E-P14), Protezione batteria (E-P16): già nel motore delle regole; i lavori della Fase 4 riprendono dal punto salvato.
- **Tetto dati** (E-B10): `Run.remainingBytes` limita i lotti; "Fai ora" sui dati mobili chiede conferma con la stima.

### 2.9 Dati personali sincronizzati (4.7, P13, 4B)
| Aspetto | Comportamento |
|---|---|
| Cosa | preferito, nascosto, album manuali (definizione, ordine, copertina, elementi), date di ultima visione dei ricordi (campo pronto per la Fase 9), impostazioni personali (esclusioni dell'area privata, "Mostra le copertine"). **Non**: preset, qualità, ruoli (sono del dispositivo) |
| Dove | `devices/<io>/personal/` nell'**area privata** del mio account; mai nell'area comune (formato §5.5); teste di tipo `personal` con segmenti di classe `personal` |
| Voce | chiave = SHA-256("mcg-pers\0" ‖ tipo ‖ id del media o dell'album ‖ campo); corpo `{"at":…,"author":…,"field":…,"fp":…,"media":…,"value":…}` con `at` in ora del NAS e `fp` = dimensione + `qh1` del media (per ritrovarlo se le identità divergono) |
| Fusione | per chiave vince `(at, author)` più grande (D26); i flag locali hanno già `*_changed_at` (schema v2) |
| Validità | firma di un dispositivo approvato **dello stesso account**; file nell'area privata dichiarata localmente; voci con autore revocato scartate ovunque compaiano; `at` oltre 2 minuti nel futuro rifiutato |
| Modifiche offline | senza ora del NAS ancorata, la modifica resta "da datare" e prende l'ora del NAS alla prima sincronizzazione |
| Quando | dopo una modifica (accorpate 30 s, con l'app aperta e il NAS raggiungibile) e in `INDEX_SYNC` |
| Privacy | i nascosti dell'area comune restano nella mia area privata: i familiari non sanno cosa ho nascosto (C19); chi ha la password del mio account (io, o l'admin del NAS) può leggere l'elenco degli id nascosti: limite dichiarato |

| Edge case | Comportamento |
|---|---|
| E-H4 | Nascosti uguali su tutti i miei dispositivi; mai su quelli di un altro account |
| E-F5 | Nomi delle persone: tipo di voce riservato per la Fase 8 |
| E-O7 | Reinstallazione: chiave nuova, nuova approvazione, poi i dati personali tornano dal NAS |

### 2.10 Stima dei costi
**Ipotesi**
- Inventario (§4-bis): `alessio` 10.650 immagini (8.723 JPG con mediana 1,8 MB, 1.573 PNG, 238 HEIC, 7 DNG da ~64 MB…) per circa 27 GB, e 1.595 video per circa 250 GB (fino a 9,4 GB l'uno); `NAS` dopo le esclusioni circa 500 immagini e fino a 380 video (116 GB, forse film); `pampa` non inventariata (117,9 GB): si ipotizzano 4.400 foto e 600 video (~100 GB), **da verificare**.
- Archivio di famiglia stimato: circa **15.550 immagini (~38 GB) e 2.570 video (~446 GB)**.
- Misure SMB cifrate in casa (S25): lettura 9,0 MB/s con 1 flusso, **16 MB/s con 4**; scrittura 9 MB/s; lettura casuale di 64 KB 15–21 ms; elenco ~19 ms per cartella e ~0,15 ms per voce nelle cartelle grandi (1,9 s per 12.283 file in 6 cartelle).
- Uscite della preparazione: S ~6 KB, M ~22 KB, analisi ~150 KB, HQ ~400 KB; record ~0,5 KB.

**Prima scansione** (in casa)
| | `alessio` | Famiglia |
|---|---|---|
| Elenco | ~2 s, ~2 MB | ~10–60 s (la share `NAS` con tutte le cartelle di musica ne chiede 46 s; con le esclusioni ~10 s), ~5 MB |
| Rilevamento | foto: 128 KiB l'una; video: 128 KiB + ~400 KiB d'indice del contenitore → **~2,2 GB** | **~3,4 GB** |
| Tempo | ~80–100 file al secondo con 4–8 richieste in volo, limitati da latenza e dischi → **4–8 minuti** | **6–12 minuti** |
| Via VPN | latenza 40–100 ms: 4–8 volte più lenta, quindi solo a casa (D22) | |

**Verifica senza novità**: elenco di tutte le cartelle incluse più le teste dell'indice → **10–15 s in casa** per l'archivio di famiglia (30–60 s via VPN), meno di 10 MB. Soglia proposta per il Fatto quando della 4.2: **≤ 60 s in casa con 100 mila file in 2.000 cartelle** (100.000 × 0,15 ms + 2.000 × 19 ms ≈ 53 s), da confermare con F3.

**Preparazione dell'archivio vero**
| | `alessio` | Famiglia |
|---|---|---|
| Foto: letture | ~27 GB → ~28 min a 16 MB/s | ~38 GB → ~40 min |
| Foto: CPU | 0,15–0,3 s a foto (HEIC e DNG più lenti), 2 in parallelo → ~20 min (si sovrappone) | ~30 min |
| Video: letture | ~5 MB a video (indice + 3 fotogrammi) → ~8 GB | ~13 GB |
| Video: tempo | ~0,3 s a fotogramma e letture casuali → **20–35 min** | 35–55 min |
| Scritture sul NAS | ~6,2 GB foto + ~1,4 GB video → ~15 min a 9 MB/s (sovrapposti) | ~11 GB |
| **Totale** | **~1–1,5 ore** (circa 35 GB letti, 7,6 GB scritti) | **~1,5–2,5 ore** (~51 GB letti, ~11 GB scritti) |
| Batteria | con Notte o Bilanciato si lavora in carica; a batteria sarebbero ~30% solo per il traffico (0,5% per GB, ipotesi) più la CPU | |

Due Preparatori insieme **non** dimezzano il tempo: il limite è la CPU del NAS. Con il preset Notte (23–07, in carica) basta una notte. Gli hash completi dei video **non** si calcolano per tutti (446 GB ≈ 8 ore): solo per i candidati all'unione con il telefono (stima: qualche centinaio di video, 30–60 minuti) e quando li chiede la verifica "al sicuro".

**Spazio dell'indice sul NAS**
| | Per media | `alessio` (12.244) | Famiglia (~18.100) | 100 mila |
|---|---|---|---|---|
| `meta` | 0,5 KB | 6 MB | 9 MB | 50 MB |
| `thumbS` + `thumbM` | 28 KB | 340 MB | 510 MB | 2,8 GB |
| `analysis` + `frames` | 150 KB (foto), ~450 KB (video) | 2,3 GB | 3,5 GB | ~19 GB |
| `hq` | 400 KB | 4,9 GB | 7,2 GB | 40 GB |
| **Totale** | | **~7,6 GB (2,7% della share)**; senza HQ ~2,7 GB | **~11 GB**; senza HQ ~4 GB | ~62 GB; senza HQ ~22 GB |

**Replica sul telefono** (vista di un utente: la sua area privata + l'area comune, per `alessio` circa 12.900 media)
| Livello | Contenuto | ~12.900 media | 100 mila | Primo allineamento in casa |
|---|---|---|---|---|
| sempre | database (~1,5 KB a media) + istantanea (~100 B) | ~20 MB | ~160 MB | secondi |
| **Risparmio** | + S | **~100 MB** | ~0,75 GB | < 30 s |
| **Standard** | + S + M | **~380 MB** | ~3 GB | ~1 minuto |
| **Alta** | + S + M + HQ | **~5,6 GB** | ~43 GB | ~6 minuti |
| Indicizzatori (Fase 6) | anteprime di analisi in un buffer, mai tenute | buffer ≤ 500 MB | | |

> **Nota (Fase 6, 21/10/2026):** il buffer degli Indicizzatori è realizzato dalla Fase 6 (`docs/fase6-progetto.md`, D10 e §2.2): al
> massimo 500 MB in `noBackupFilesDir/ai-buffer/`, svuotato man mano per ciò che è già calcolato e fermo sotto 1 GiB libero.
> «Mai tenute» vale per la replica: nel buffer le anteprime stanno solo finché il calcolo non è fatto. Non si aggiunge nessuna riga
> ai livelli di qualità. I dati che il calcolo produce (vettori e testo letto, `ai_vector` e `ai_text` sul dispositivo; teste `ai` sul
> NAS) sono fuori da questa tabella: circa 60 MB sul NAS per l'archivio di famiglia (§2.8 del progetto della Fase 6).

### 2.11 Cosa la Fase 4 predispone per la Fase 5
| Pezzo della Fase 4 | Come lo usa la Fase 5 |
|---|---|
| `Preparer` con `PrepareSource.File` | "Chi carica prepara": il backup prepara dai byte del telefono, senza rileggere dal NAS |
| `IndexPublisher` | l'uploader pubblica il record con il timbro letto **dopo** la rinomina finale, così la scansione lo adotta senza rilevarlo (D6) |
| Diario delle operazioni, macchine a stati, banco delle 500 interruzioni | il caricamento atomico (temporaneo, hash, rinomina senza sostituire) è una nuova macchina a stati sullo stesso diario e sulla stessa prova |
| `NasFileOps.writeToTemp` + `publishTemp` | primitive del caricamento (scritture a offset, ripresa dei video grandi) già provate dal ripiego E-D12 |
| `VerifyWorker` e `SafetyRules` collegati | "al sicuro" e "Libera spazio" (5.3, 5.5) |
| Cestino del NAS e "Ovunque" | cancellazioni dal telefono e versioni (5.4, 5.5) |
| Esclusione di `.mycloudgallery/` dalla scansione | i temporanei dell'uploader non compaiono mai |
| `PHOTO_BACKUP` / `VIDEO_BACKUP` già usati per la verifica | stesse regole, stime già calibrate in parte |
| Struttura `Backup/<dispositivo>/AAAA/MM` | **non** si crea nella Fase 4 |

> **Nota (Fase 5, 16/10/2026):** la riga "`VerifyWorker` e `SafetyRules` collegati" non si è avverata nella Fase 4. `SafetyRules` c'è (`evaluate`, `canFreePhoneCopy`), ma il `VerifyWorker` e l'esecutore della fase `FULL_HASH` (F4-D21) **non sono stati realizzati** e nessun codice chiama `markVerified`: passano alla Fase 5, nel pacchetto P3 (D14 di `docs/fase5-progetto.md`: `SafetyVerifier` in `core-backup`, `SafetyVerifyWorker` in `:app`).

## 3. Alternative scartate
| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Indice sul NAS | database SQLite sulla share; un file unico scritto da tutti; segmenti per dispositivo | **Segmenti per dispositivo** (formato §2.3): SQLite via SMB si corrompe con più scrittori; un file unico vorrebbe sovrascritture e blocchi |
| Miniature sul NAS | file JPEG sciolti in una cartella; dentro i segmenti | **Dentro i segmenti**: niente DLNA o Foto di Windows (E-I13), nessun milione di file piccoli |
| Impronta alla scansione | hash completo di tutto; solo dimensione e data; `qh1` | **`qh1`** (contratto della 1.3): 128 KiB a file; l'hash completo dove serve |
| Hash completo dei video | per tutti (8 ore di letture); mai; su richiesta | **Su richiesta** (`FULL_HASH`) per i candidati all'unione e per "al sicuro" |
| Notifiche di modifica | `CHANGE_NOTIFY` (`watchAsync`) di smbj; elenco a ogni verifica | **Elenco**: un handle aperto per cartella e dischi sempre svegli non fanno per noi (`research/smb-vpn.md` §1.6) |
| Scorciatoia sulle cartelle | saltare le cartelle con la data invariata; elencare tutto | **Elencare tutto**: una modifica sul posto non cambia la data della cartella; i numeri lo permettono. La scorciatoia resta un'ottimizzazione da misurare |
| Prenotazioni | per media; per unità | **Per unità** (D13) |
| Fotogrammi dei video | FFmpeg; scaricare il video; letture a intervalli con i decoder di sistema | **Decoder di sistema a intervalli**: nessuna licenza LGPL/GPL né MB in più; piano B `MediaExtractor` + `MediaCodec` |
| Miniature in Libreria | caricatore generico (Coil, Glide); pipeline propria | **Pipeline propria** (D5 della Fase 3) con una sorgente in più |
| Libreria dalla rete | pagine lette dal NAS; replica locale | **Replica locale**: avvio sotto 1 s anche offline (§4 del piano) |
| Cestino del NAS | cestino del WD; cartella dell'app | **Cartella dell'app** (D25) |
| Spostamento tra share | cancellare subito la sorgente dopo la verifica; sorgente nel cestino | **Cestino** (D24, domanda Q3): un errore di verifica non può costare un file |
| Verifica dello spostamento | dimensione + `qh1`; rilettura completa | **Rilettura completa**: precede l'unica azione che toglie la sorgente |
| Dati personali | operazioni per autore (solo le proprie voci); CRDT generici; stato unito con ultima scrittura e autore | **Stato unito** (D26): un telefono perso non porta via i preferiti |
| Prove contro un server SMB locale | Samba in Docker, server Java, impacket | **Escluse** (Fase 2 §5.3): simulatore + area di prova vera |
| Lavori lunghi | servizio in primo piano notturno; tappe + UIDT | **Tappe + UIDT** (`android.md` 1.3): nessuna esenzione per i servizi avviati dai job |
| Pubblicare le miniature interattive | sì; solo locali | **Solo locali** (D17): niente teste a ogni scorrimento |

## 4. Pacchetti di lavoro

### 4.1 Ordine e parallelismo
```
4A
P0 fondamenta, contratti, documenti ──┬── P1 accesso ai file del NAS (core-nas) ─────────┐
                                      ├── P2 modello e database v3 ───────────────────────┤
                                      ├── P3 formato dell'indice, teste, pubblicazione ───┤
                                      ├── P4 replica e sincronizzazione ──────────────────┤
                                      ├── P5 scansione e rilevamento ── M1 ──┐            ┤
                                      ├── P6 preparazione ───────────────────┼── M2 ──────┤
                                      ├── P7 Libreria unificata e miniature ─┘            ┤
                                      ├── P8 viewer del NAS e "Scarica sul telefono" ─────┤
                                      ├── P9 lavori a tappe, regole, stime ───────────────┤
                                      └── P10 prestazioni con i media del NAS ────────────┴── P11 integrazione 4A
                                                                                      ── check.ps1 (-Bench) + security-review
4B (dopo l'approvazione della 4A)
P0-B fondamenta 4B ──┬── P12 operazioni, cestino, spazio, pulizie (core-nasops + core-nas/ops) ──┐
                     ├── P13 dati personali ───────────────────────────────────────────────────┤
                     ├── P14 UI della Fase 4 (feature-nas, Raccolte, menu) ─────────────────────┤
                     └── P15 prova delle 500 interruzioni (banco, area di prova, telefono) ─────┴── P16 integrazione 4B
                                                                                      ── M3 + settimane dei preset + security-review
```
- Tutti i pacchetti della 4A dopo P0 partono **insieme** sui contratti e sui fake di P0; si incontrano in P11.
- M1 e M2 sono integrazioni parziali fatte dall'orchestratore (§4.2) prima di P11, per vedere presto i rischi veri.
- P15 parte con i fake di P12 e scrive il banco; i giri veri si fanno quando P12 consegna.

### 4.2 Tappe
| Tappa | Cosa si vede | Come si prova | Pacchetti |
|---|---|---|---|
| **M1** | I media del NAS compaiono nella Libreria con data giusta, badge "solo NAS", miniature EXIF o segnaposto, nessuna scrittura sul NAS | Emulatore con il NAS finto e il NAS sintetico da 20 mila file; S25 sull'area di prova (F1); con il consenso dell'utente (Q4), scansione **in sola lettura** dell'archivio vero con l'APK di release (F2) per misurare tempi e traffico contro il §2.10 | P0, P1, P2, P5, P7 (parte: indice con NAS, miniature provvisorie) |
| **M2** | Miniature preparate; il secondo dispositivo (Tab S9, profilo con l'account `test2`) vede le miniature dell'area comune **senza prepararle**; dati ostili rifiutati | F6, F7 (ore di lavoro in parallelo), F8 (segmenti ostili scritti dal PC nell'area di prova) | + P3, P4, P6 |
| Chiusura 4A | Viewer del NAS, "Scarica sul telefono", regole e stime, Diagnostica, benchmark dentro la guardia | `check.ps1`, `check.ps1 -Bench` su S25 e Tab S9, F1–F14, revisione degli screenshot, `security-review` con revisione mirata di `core-trust` | + P8, P9, P10, P11 |
| **M3** | Cartelle NAS, sposta, rinomina, cestino, ripristino, Spazio NAS; dati personali | F15–F17; **prova delle 500 interruzioni** (≥ 400 sul PC contro l'area di prova, ≥ 100 sul telefono) | P0-B, P12–P16 |
| Chiusura 4B | Una settimana con Notte e una con Bilanciato dentro le soglie (4.6) | F18, F19, `security-review` | — |

**Proposta: sì alla divisione in 4A e 4B** (D1, domanda Q1). La 4A è utile da sola (tutto l'archivio del NAS nella Libreria, sul telefono e sul tablet) e non può perdere un file; la 4B arriva con la revisione della 4A fatta.

### 4.3 Regole comuni dei brief (oltre a quelle delle Fasi 2 e 3)
- **Mai** toccare file fuori dal proprio perimetro. Dipendenze, permessi, file di build condivisi e `settings.gradle` li tocca solo P0 (poi P0-B, P11, P16).
- **Nessuna rete nei moduli nuovi**: SMB solo tramite i contratti di `core-nas`; `NoNetworkSourcesTest` in ogni modulo nuovo.
- **Nessuna operazione distruttiva fuori da `core-nasops`**; `NasFileOps` solo lì (test di architettura in P0).
- **Ora**: solo `NasClock` (o l'orologio monotono per le durate); un test per modulo vieta `System.currentTimeMillis`, `java.time.Instant.now` e simili nel codice che decide scadenze, prenotazioni o ordinamenti.
- **Log** con `GalleryLog` e `Redacted`: mai nomi di file, percorsi, share, account, id di media, coordinate; i percorsi del NAS sono dati sensibili come il GPS (`SharePath` senza `toString` utile nei log).
- **Lettura difensiva** di ogni dato dal NAS: tetti prima di allocare, codec stretti, `ImageBounds` prima di decodificare.
- **Ogni passo in più tappe è idempotente** e ha un test di "ripresa dopo un guasto a ogni chiamata" sul NAS finto con `FaultPlan`.
- Test JVM o Robolectric per ogni modulo; screenshot (solo dove c'è UI) in `docs/screenshots/4-<pacchetto>/`, chiaro e scuro, telefono e tablet, testo grande.
- Stringhe in italiano in file propri (`strings_nas_<area>.xml`).
- Report: cosa è stato fatto, test, edge case per ID, misure, punti aperti, cosa provare sul campo, **modifiche richieste ai contratti degli altri**.

### 4.4 I pacchetti
| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Campo |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti (4A)** | Moduli nuovi, contratti, fake, policy, documenti | `settings.gradle`, `build.gradle` (`galleryModules`), `tools/check.ps1`, creazione di `core-index`, `core-nasmedia`, `core-prepare` (build, manifest, `contract/`, `noop/`, `testing/`, 1 test), **contratti** dei §1.2 della 4A (interfacce e fake, compresi `NasMediaReader` e i tipi di `core-model` di P2 come firme), `core-common` (`DiagSection.NAS_LIBRARY`), `FeatureFlags` (`nasLibrary`, `indexPublishTestSharesOnly`), test di architettura (dipendenze del §1.1, `NasFileOps` solo in `core-nasops`, nessun `com.hierynomus` fuori da `core-nas`), `docs/formato-nas.md` (§0.3 M1–M12), `docs/sicurezza.md` (righe del §0.3), `docs/decisioni.md`, `README.md`, piano (edge case) | — | `check.ps1` verde; nessuna dipendenza nuova; i test di architettura falliscono se si introduce una dipendenza vietata (verificato reintroducendola); `formato-nas.md` aggiornato e coerente con `TrustLimits` (le costanti nuove esistono come costanti, anche se ancora non usate) | opus | no |
| **P1 Accesso ai file del NAS** | §1.2 `core-nas` (4A) | `core-nas/**` tranne `ops/` (4B), `docs/nas-sessione.md` | P0 | Sul simulatore: elenco a pagine di 50.000 voci senza liste intere in memoria; `fileId` stabile alla rinomina; letture a intervalli corrette ai bordi; `readAll` a 4 flussi con SHA-256 giusto; il sorvegliante non uccide un trasferimento lungo che procede e uccide quello fermo (tempo virtuale); `FaultPlan` con caduta prima e dopo l'applicazione; `lock`, latenze, date delle cartelle, copia lato server finta. **Test della domanda aperta della revisione 2** (sessione finale non firmata o non cifrata rifiutata). Suite `@NasIntegration` estesa con la guardia (§5.4), le chiavi nuove del file e i suoi autotest; `ShareFence` (D33) con i test "share pubblica rifiutata prima della richiesta" su tutte le interfacce | opus | sì (integrazione dal PC) |
| **P2 Modello e database v3** | §1.3 `core-model`, `core-database`, `core-metadata` (spostamenti) | `core-model/**`, `core-database/**` (schemi), `core-metadata/.../hash` e `ExtractHints`, `docs/modello-dati.md` | P0 | `3.json` esportato; `ExportedSchemaTest` 1→3 e 2→3 con `seedV2`; `MergeReferencesTest` con le tabelle nuove; `recordAll` di osservazioni NAS con cursore in una transazione; `markVanishedInShare` con tetto; `adoptIdentity` con il rifiuto di D7; gruppi `PROBABLE_DUPLICATE`; flag di presenza giusti in tutte le combinazioni; **20 mila media solo-NAS con META in coda non fermano la META del telefono** (oggi la salta fino a 2.000 volte); `EXPLAIN` delle query nuove senza ordinamenti temporanei | opus | no |
| **P3 Formato dell'indice e pubblicazione** | §2.1 | `core-trust/.../data/IndexSegment.kt`, `HeadSeries.kt`, `OwnFiles.kt` (+ test), `TrustLimits`; `core-index/.../publish/**`, `.../work/**`, `.../compact/**` | P0 | Codec andata e ritorno; **segmenti ostili** (tabella incoerente, chiavi non crescenti, corpi oltre i tetti, JSON non canonico, percorsi `..` o dentro `.mycloudgallery`, immagini con dimensioni dichiarate enormi, classe sbagliata) rifiutati senza crash; serie di teste per nome con spazzatura e buchi; outbox che sopravvive alla morte del processo a ogni passo; pubblicazione ripetuta idempotente; regola M3 (proprietà: niente dall'area privata nell'indice comune); unità e prenotazioni con due e tre dispositivi simulati (lavoro doppio ≤ 1% in 1.000 giri casuali); compattatore che produce lo stesso stato (D5) | opus | no |
| **P4 Replica e sincronizzazione** | §2.2 | `core-index/.../replica/**`, `.../sync/**`, `.../quality/**` | P0 (fake di P3 per i segmenti) | Fusione D6 e adozione D7 su scenari scritti a mano; replica identica ricostruita da zero (E-I7); E-I5, E-I8, E-I15, E-I17, E-I18 nei test; verifica senza novità ≤ 20 richieste con 3 share e 6 dispositivi; pacchi mappati e letti senza copie; stima e convergenza della qualità (E-L6); applicazione di 20 mila record in meno di 10 s su JVM | opus | sì (M2) |
| **P5 Scansione e rilevamento** | §2.3 | `core-nasmedia/**` | P0 (fake di P1, P2, P4) | Sul NAS sintetico (§5.3): prima scansione dai più recenti con ripresa a metà; verifica senza novità senza letture di file; E-N1 (cartella da 1.000 file rinominata: **zero** rilevamenti e zero preparazioni), E-N2–E-N16 nei test; E-M12; stabilità con l'ora del NAS sfasata di +58 s; esclusioni e copertine; congelamento alle soglie e non sotto; rilevamento fuori casa ≤ 50 MB | sonnet | sì (F1–F5, F9) |
| **P6 Preparazione** | §2.4 | `core-prepare/**`, `tools/fixtures/**` (campioni nuovi: HEIC, DNG piccolo, video con indice in coda, APV finto) | P0 (fake di P3) | Uscite giuste per ogni formato delle fixture (dimensioni, orientamento, spazio colore); video senza scaricarli (byte letti ≤ indice + fotogrammi, contatore); hash completo delle foto uguale a quello calcolato a parte; memoria sotto `PrepBudget` con 200 MP (Robolectric con tetto); `DECODER_MISSING` e passaggio di mano; corrotti; tempi per media annotati nel report | sonnet | sì (F6) |
| **P7 Libreria unificata e miniature** | §2.5 | `core-library/**`, `core-imaging/**` (tranne `viewer/`), `feature-library/.../library/LibraryNasStatus*` (solo dati) | P0 (fake di P2 e P4) | `MCGT2` andata e ritorno, `MCGT1` scartato; presenza e badge giusti; `NasThumbnailSource` (replica → provvisoria → originale solo visibile) con annullamento; budget JVM della Fase 3 rispettati con 100 mila elementi di cui 60% solo-NAS (costruzione indice, layout, ricomposizioni); `LibraryFeatures.presenceBadges` acceso | sonnet | sì (F12, F13) |
| **P8 Viewer del NAS e "Scarica sul telefono"** | §2.6 | `feature-viewer/**`, `core-imaging/.../viewer/**`, `core-mediastore/.../write/**` | P0 (fake di P1, P7) | `NasImageOrigin` e `NasVideoDataSource` con sorgenti lente, ferme e irraggiungibili (E-V1, E-V2, E-V5, E-V6, E-L4); nessun `DefaultDataSource`; "Scarica" con morte del processo a metà (nessun file visibile a metà, `IS_PENDING` ripulito), nome in conflitto, collegamento `SameFile`; voce "Scarica sul telefono" nel menu a capacità | sonnet | sì (F10, F11) |
| **P9 Lavori a tappe, regole, stime** | §2.8 | `core-rules/.../run/**`, `.../estimate/BacklogSource*`, `WaitReason.Frozen` | P0 | `StagedRun` con stop a metà lotto, traffico registrato prima del crash, ripresa; tetto del rilevamento fuori casa; stime dall'arretrato vero (tabella dei preset con 12.900 media); `PresetMatrixTest` esteso con "congelato" | sonnet | no |
| **P10 Prestazioni con i media del NAS** | §5.6 | `benchmark/**`, `app/src/benchmarkRelease/**`, `docs/bench/**`, `tools/bench/**` | P0; P7 per la misura finale | Variante di misura con libreria mista (100 mila righe, 60% solo-NAS con pacchi sintetici di miniature WebP vere); scenari della Fase 3 rifatti; confronto con le misure del 13/10 (§5.6); smoke sull'emulatore | sonnet | sì (obbligatorio) |
| **P11 Integrazione 4A** | §1.4 per la 4A | `app/**`, `feature-diagnostics/.../catalog/**` ed `export/ExportKeys.kt`, `feature-settings/.../quality/**`, `README.md`, `docs/sicurezza.md` (checklist), `docs/decisioni.md` | P0–P10 | `NasGraph`, lavori, notifiche, Diagnostica (test di catalogo per ogni edge case della 4A), azzeramento che ferma anche il NAS, D31 attivo, recinzione D33 legata solo in `src/debug` (verificata assente dal dex di release da `check.ps1`), test d'integrazione sul NAS finto (scansione → Libreria → preparazione → secondo "dispositivo" che sincronizza → viewer); `check.ps1` verde; APK debug e release | opus | sì |
| **P0-B Fondamenta 4B** | Moduli e permessi della 4B | `settings.gradle`, `build.gradle`, creazione di `core-nasops`, `core-personal`, `feature-nas`; contratti del §1.2 della 4B; `UserConfirmation`/`ConfirmationIssuer` (firme); se approvati, `RUN_USER_INITIATED_JOBS` e il `JobService` in policy e in §4-bis | 4A chiusa | `check.ps1` verde; test di architettura su `UserConfirmation` e `NasFileOps` | sonnet | no |
| **P12 Operazioni, cestino, spazio, pulizie** | §2.7, primitive §1.2 4B | `core-nasops/**`, `core-nas/.../ops/**` (+ simulatore per le operazioni) | P0-B | Ogni macchina a stati con `FaultPlan` a **ogni** chiamata (prima e dopo l'applicazione) e morte del processo a ogni passo: invarianti del §5.5 sempre veri; E-D1–E-D13 e E-N9, E-N11, E-N13 nei test; pulizia del cestino solo da diario locale (finto cestino, record falsi, ora che salta: nessuna eliminazione); `deleteIfUnchanged` con un solo handle; compattazione e pulizia dei propri file accese; nessun percorso dal NAS a un'azione | opus | sì (F15) |
| **P13 Dati personali** | §2.9 | `core-personal/**` | P0-B (fake di P3, P4) | Fusione con orologi sfasati, modifiche offline "da datare", autore revocato, dispositivo di un altro account, file nell'area comune (rifiutato), voci con `at` nel futuro; preferito e nascosto convergono fra tre dispositivi simulati dello stesso account e non compaiono su un quarto di un altro | sonnet | sì (F17) |
| **P14 UI della Fase 4** | §1.5 (stati 4B) | `feature-nas/**`, `feature-library/.../collections/CollectionEntries*` (solo dati), voci del menu del viewer (`feature-viewer/.../menu/NasActions*`) | P0-B (fake di P12) | Cartelle NAS (sola lettura E-N9, offline), Spazio NAS, Eliminati di recente del NAS, Operazioni in corso, fogli di conferma (riepilogo con numero e dimensione, "Ovunque", cartelle); `ConfirmationIssuer` solo da un tocco; TalkBack e testo grande; screenshot `4-P14` | sonnet | sì |
| **P15 Prova delle 500 interruzioni** | §5.5 | `core-nasops/src/test/.../chaos/**`, `tools/chaos/**`, `docs/prova-500.md` | P0-B (fake di P12), poi P12 | Banco JVM con 2.000 giri casuali verdi sul NAS finto; runner contro l'area di prova (≥ 400 interruzioni) dietro la guardia; script `adb` per il telefono (kill del processo e Wi-Fi a tempi casuali); verificatore degli invarianti che si esegue anche da solo sull'area di prova | sonnet | sì (F16) |
| **P16 Integrazione 4B** | §1.4 per la 4B | `app/**`, `feature-diagnostics/**` (catalogo ed esportazione), `docs/sicurezza.md`, `docs/decisioni.md`, `README.md` | P0-B, P12–P15 | UIDT con notifica e "Ferma"; lavori della 4B; Raccolte con le voci nuove; Diagnostica; test d'integrazione (sposta, elimina, ripristina, pulizia con ora finta); `check.ps1` verde; APK | opus | sì |

> Nota sui crediti: opus per le fondamenta (P0), l'accesso SMB (P1), l'identità e lo schema (P2), l'indice condiviso e la concorrenza (P3, P4), le operazioni riprendibili (P12) e le due integrazioni. Tutto il resto è sonnet, con brief che riportano per intero le tabelle di questo documento.

### 4.5 Chiusura delle due metà
- **4A**: `check.ps1` verde; `check.ps1 -Bench` su S25 e Tab S9 dentro la guardia; F1–F14 superati (F2 solo con il consenso); screenshot dei badge e dello stato NAS approvati; `security-review` e revisione mirata di `core-trust` (nuovo codec); checklist della Fase 4 (parte 4A) spuntata; decisione dell'utente su D31 (accendere la pubblicazione sulle share vere).
- **4B**: prova delle 500 interruzioni superata; F15–F20; `security-review`; checklist completa; `docs/android.md` aggiornato con UIDT sul campo.

## 5. Prove

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | `core-trust`, `core-index`, `core-nasmedia`, `core-nasops`, `core-personal`, `core-rules` | codec, fusione, identità, congelamento, macchine a stati, unione dei dati personali |
| NAS finto in memoria | `core-nas/testing` (§5.2) | semantica Samba, guasti, latenze, ora |
| NAS sintetico | `core-nasmedia/testing/SyntheticNas` (§5.3) | proporzioni dell'inventario, 20 mila e 100 mila file |
| Robolectric (SDK 35) | Room v3, `ImageDecoder` e codec, Compose della 4B | |
| Roborazzi | `feature-nas`, badge, stato NAS | chiaro e scuro, telefono e tablet, testo grande |
| Integrazione dal PC | `@NasIntegration` dietro la guardia (§5.4) | smbj contro l'area di prova |
| Strumentati su emulatore | `Medium_Phone`, `Pampa_Tablet` | `MediaMetadataRetriever` su `MediaDataSource`, `IS_PENDING`, UIDT, sempre con il NAS finto (l'emulatore non è "casa") |
| Dispositivi | S25, Tab S9 | §5.7 |

### 5.2 Il NAS finto in memoria (estensione di `InMemorySmbBackend`, P1)
| Fedeltà | Come |
|---|---|
| Maiuscole e minuscole | già presente: nomi senza distinzione, forma scritta conservata |
| Esclusività | già presente: `FILE_CREATE`, rinomina che non sostituisce |
| `fileId` | numero stabile per file, conservato dalla rinomina, nuovo per una copia |
| Date | data di modifica del file all'ultima scrittura; della cartella a ogni creazione, rinomina o cancellazione dentro |
| File bloccati | `lock(share, path)`: apertura in scrittura o rinomina → `SHARING_VIOLATION` |
| Spazio | già presente: quota per share e disco pieno; `space()` con "del chiamante" e "del disco" |
| Ora | già presente: `nasTime` con scarto (+58 s) e salti; deriva programmabile (+5 s al giorno) |
| Latenze | per tipo di operazione (elenco per cartella e per voce, lettura casuale, lettura a flusso, scrittura), a tempo virtuale, con i valori dell'inventario |
| Dischi in standby | la prima operazione dopo un'inattività programmabile tarda (es. 8 s) e alza `lastDiskTouchAt` |
| Guasti | `FaultPlan`: all'operazione n caduta della connessione **prima** di applicarla o **dopo** averla applicata (risposta persa), sessione scaduta, `Stalled`; ordine degli elenchi casuale; elenchi troncati |
| Copia lato server | tra share nella stessa sessione, con `NOT_SUPPORTED` e `ACCESS_DENIED` programmabili |
| Contenuti ostili | file con nomi non validi, punti di rianalisi, cicli, cartelle enormi |

### 5.3 Il NAS sintetico
- `SyntheticNas(seed, profile)`: file **virtuali** (dimensione + seme, contenuto generato a richiesta per le letture a intervalli: niente 279 GB in memoria) più una parte di file veri piccoli (JPEG, HEIC, PNG, WebP animati, MP4 con indice in testa e in coda) per decodifica e metadati.
- Profilo `INVENTARIO`: una share privata piatta (6 cartelle, una da 8.910 file), una share comune con 2.356 cartelle di cui la maggior parte musicali con copertine da 40 KB, video fino a 9,4 GB (virtuali), date dal 1979, nomi WhatsApp, Screenshot, Pixel, emoji, accenti, percorsi lunghi, nomi uguali a meno delle maiuscole, file a zero byte, corrotti, temporanei in arrivo.
- Profilo `CENTOMILA` per le prestazioni della scansione e della Libreria.
- Scenari: rinomina di una cartella da 1.000 file, modifica sul posto, cancellazione dal PC, "ransomware" (500 file rinominati in `.locked`), share tolta, quota piena.

### 5.4 L'area di prova sul NAS vero (P1, poi P15)
**Quando gira**: solo con `-PnasIntegration` **e** con `%USERPROFILE%\.mycloudgallery\nas-it.properties` presente; senza il file i test risultano *saltati* con il motivo "file delle credenziali assente".

**Chiavi del file** (le prime tre esistono già in `NasIntegration.kt`; le altre sono **proposte** e facoltative: senza, i test che le usano risultano *saltati*)
```properties
user=test
password=...
share=test               # area privata di prova del primo account
# esistenti, facoltative
host=192.168.178.58
guid=
share2=test-comune       # area comune di prova (gia' usata dalla Fase 2 per la copia tra share)
m9.consent=              # invariato
# nuove per la Fase 4
user2=test2              # secondo account: privacy fra account (prova 10.4), due "dispositivi" di account diversi
password2=...
privateShare2=test2      # area privata del secondo account
chaos.rounds=400         # giri del runner delle interruzioni (P15); 0 = non eseguire
chaos.bigFileMb=200      # dimensione del file grande del runner
```

**La guardia** (`TestAreaGuard`, in `core-nas/src/test/.../integration/`)
1. **Prima di qualunque connessione**: l'account (anche `user2`) non è `admin`, `alessio` o `pampa`; le share ammesse sono **l'intersezione** fra quelle scritte nel file (`share`, `share2`, `privateShare2`) e l'elenco **scritto nel codice** `test`, `test2`, `test-comune`; `alessio`, `pampa`, `NAS` e `Public` sono rifiutate comunque (confronti NFKC senza maiuscole). Un errore nel file non può allargare il recinto: per cambiarlo serve una modifica del codice, che si vede in revisione. Altrimenti la suite fallisce subito, senza aprire socket.
2. **Ogni percorso** passa da `TestAreaFileSystem`, che avvolge `NasMediaReader`, `NasFileOps` e `NasFileSystem`: accetta solo percorsi che, dopo `SharePath.parse`, iniziano con `mcg-it/<giro>/` (confronto senza maiuscole); tutto il resto lancia `TestAreaViolation` **prima** della chiamata SMB.
3. **L'indice di prova** usa `RootedNasFileSystem(base = mcg-it/<giro>/)`: il formato vede `.mycloudgallery/...` come sempre, sul NAS finisce sotto `mcg-it/<giro>/.mycloudgallery/`. Il registro di famiglia delle prove della Fase 2 non si tocca.
4. **Autotest della guardia** come primo test: prova `../`, un'altra share, la radice, `.mycloudgallery` vera e un nome con maiuscole diverse; tutti rifiutati con **zero** chiamate al trasporto (contatore).
5. **Pulizia**: solo `mcg-it/<giro>/`, con la stessa guardia; un giro interrotto si pulisce al giro dopo (tutti i giri precedenti sotto `mcg-it/`, con ciascun account a turno; alla fine `mcg-it` deve essere vuota per entrambi gli account, altrimenti la suite fallisce).
6. **Nessun accesso diretto a smbj** nei test della Fase 4 (test di architettura sui sorgenti di test).
7. La password si verifica una volta con la sessione (interruttore condiviso): un errore nel file costa un solo tentativo.
8. **Perché la radice `mcg-it/<giro>/` anche dentro le share di prova**: `test` contiene le foto della Fase 0 ed è leggibile da `alessio`; le prove non le toccano mai. Le prove sul campo (§5.7) usano invece cartelle dedicate (`mcg-campo/`) e la recinzione del telefono (D33).
9. **Cestino del NAS**: è spento sulle share di prova (come su `Public`) e acceso su `NAS`. Le prove vedono quindi lo spazio liberarsi subito dopo una cancellazione definitiva; il caso "cestino del WD acceso" (Spazio NAS, E-N16) si prova solo sul NAS finto.

**Cosa si prova lì**: elenchi e `fileId` veri (M8 della ricerca), letture a intervalli e sorvegliante, copia lato server tra `test` e `test-comune`, pubblicazione dell'indice e prenotazioni con due "dispositivi" (chiavi di prova), segmenti ostili, rinomina dal PC simulata dall'account di prova, operazioni della 4B e il runner delle interruzioni.

### 5.5 La prova delle 500 interruzioni (4B, P15)
**Invarianti** (controllati dopo ogni giro e alla fine, sul NAS finto e sull'area di prova)
1. **Nessun file a metà visibile**: ogni file fuori da `.mycloudgallery/` ha lo SHA-256 di uno degli originali.
2. **Nessun doppione**: ogni originale compare **una sola volta** fra i file visibili (gli spostamenti non lasciano copie visibili).
3. **Nessuna perdita**: ogni originale esiste visibile **oppure** nel cestino della sua share con record valido e voce del diario locale.
4. **Hash al 100%**: alla fine si rilegge tutto e ogni SHA-256 coincide.
5. **Temporanei**: solo quelli propri ancora citati dal diario.
6. **Catalogo coerente**: una scansione completa finale non trova differenze con lo stato atteso.

**Interruzioni**
| Dove | Come | Quante |
|---|---|---|
| JVM, NAS finto | `FaultPlan` casuale (caduta prima/dopo, sessione scaduta, stallo) + "morte del processo" (si buttano tutti gli oggetti e si riparte dal database su file) | 2.000 giri nel test, sempre |
| PC, area di prova | runner che chiude la connessione a punti casuali e ricrea gli oggetti dal diario; file da 1 MB e da 200 MB | ≥ 400 |
| Telefono, area di prova | script `tools/chaos/s25.ps1`: durante una mossa di massa, a intervalli casuali `am kill` del processo o Wi-Fi spento e riacceso; poi il verificatore | ≥ 100 |

Operazioni coperte: rinomina, sposta nella stessa share, sposta tra share (copia lato server e ripiego), elimina file e cartelle, ripristina, crea cartella, pulizia con ora finta, pubblicazione dell'indice, "Scarica sul telefono".

### 5.6 Benchmark e guardia delle prestazioni (P10)
- Scenari della Fase 3 (scorrimento ai 4 livelli e 2 stili, pinch, avvio a freddo, apertura del viewer, zoom 200 MP) su una **libreria mista** di 100 mila righe: 40% telefono (media veri a rotazione), 60% solo-NAS con miniature dai pacchi.
- **Guardia**: non peggiorare rispetto alle misure del 13/10 (`misure-s25-2026-10-13.md`), cioè TTFD p90 ≤ 750 ms (681 + 10%), `viewerOpenMs` p90 ≤ 25 ms (21 + 15%), jank di scorrimento e pinch ≤ misura del 13/10 + 10% relativo **e** ≤ 1% dove già lo era. Le soglie che falliscono già oggi (scorrimento giustificato, fotogramma più lungo dell'apertura) non devono peggiorare.
- Se il filone grafico cambia la griglia, il riferimento si aggiorna alla sua tappa: la guardia confronta sempre con l'ultimo riferimento accettato dall'utente.
- Nuove misure: apertura di un media solo-NAS con HQ in replica (p90 < 100 ms), prima pagina di miniature solo-NAS dopo un avvio a freddo, memoria dell'indice (`estimatedBytes`) con 100 mila elementi.

### 5.7 Verifica sul campo
**A. Preparazione (utente, circa 30 minuti)** — utenti e share esistono già (14/10)
1. **Password degli account di prova**: oggi sono deboli e l'app le **rifiuta** all'onboarding e all'ingresso (`NasPasswordPolicy`, revisione 2 F1, L19). Per le prove sul telefono vanno rafforzate (12 caratteri con tre tipi, o il generatore dell'app) e poi riscritte nel file delle credenziali. Un'eccezione solo per la build debug è possibile ma **sconsigliata**.
2. Sul PC: `nas-it.properties` (§5.4) con `user=test`, `share=test`, `share2=test-comune` e, per le prove di privacy e dei due account, `user2=test2`, `password2`, `privateShare2=test2`.
3. In `test-comune/mcg-campo/` e `test/mcg-campo/` (dal PC): un campione di **ogni formato dell'inventario** (JPG, PNG, HEIC, DNG, GIF, WebP, MP4 4K, MOV, MKV, una motion photo, una cartella "musicale" con MP3 e copertina), una cartella da 1.000 foto copiate e un video da più di 2 GB. Le foto della Fase 0 già in `test` restano dove sono e valgono come primo campione.
4. S25 configurato con `test` (area privata `test`, area comune `test-comune`); Tab S9: profilo "Prova" con `test2` (area privata `test2`, area comune `test-comune`). Build debug **solo con questi account** (L25), con la recinzione D33 attiva: anche se all'onboarding compaiono `NAS` e `Public` (sono pubbliche), l'app rifiuta di usarle.
5. Per F14: Twonky e Plex oggi indicizzano probabilmente solo `NAS`; per la prova va aggiunta **temporaneamente** `test-comune` alle loro librerie dalla dashboard (poi tolta).
6. Facoltativo: sistemare l'NTP del NAS (oggi avanti di ~58 s e in deriva); l'app regge comunque.

**B. Prove**
| # | Prova | Atteso |
|---|---|---|
| F1 | S25: prima scansione dell'area di prova | Media in Libreria con date giuste, badge "solo NAS", copertina della cartella musicale esclusa, temporanei ignorati; tempo annotato (M1) |
| F2 | (Q4, solo con il consenso e l'APK di release con l'account vero) scansione **in sola lettura** di `alessio` con D31 attivo | Tempo e traffico entro ±30% del §2.10; nessun file scritto sul NAS (controllo dal PC: nessuna `.mycloudgallery` nuova) |
| F3 | Verifica senza novità ripetuta | Sotto la soglia; `lastDiskTouchAt` mostra che in background non si svegliano i dischi senza motivo |
| F4 | Dal PC: rinomina della cartella da 1.000 foto | Nessun rilevamento e nessuna preparazione rifatti (contatori in Diagnostica), preferiti conservati |
| F5 | Dal PC: modifica di una foto, cancellazione di un'altra | Nuova versione; l'altra esce dalla Libreria |
| F6 | S25 Preparatore: preparazione dell'area di prova | Tutto preparato; formati non decodificabili segnalati; tablet (`test2`, area comune) vede le miniature senza prepararle (M2) |
| F7 | S25 e Tab S9 (due account) per 3 ore, con file che arrivano dal PC | Lavoro doppio ≤ 1%, nessun segmento rifiutato fra dispositivi onesti, **viste identiche** (riassunto dell'indice per share uguale in Diagnostica di debug) |
| F8 | Dal PC, `tools/hostile-index` scrive segmenti e teste ostili nell'area di prova | Rifiutati senza crash, contati in Diagnostica |
| F9 | Dal PC, 500 file rinominati in `.locked` | Congelamento, avviso subito, nessuna assenza segnata; scongelamento con il gesto |
| F10 | Viewer: foto solo-NAS, video 4K in streaming, Wi-Fi spento a metà, hotspot del tablet (dati mobili) | Anteprima subito, poi nitida; video che parte; "non raggiungibile"; avviso con la dimensione |
| F11 | "Scarica sul telefono" di 10 media, uno interrotto | In `Pictures/MyCloud Gallery`, due posizioni, nessun doppione, nessun file a metà |
| F12 | Qualità Standard → Alta → Risparmio | Lo spazio converge alla stima ±15% |
| F13 | `check.ps1 -Bench` su S25 e Tab S9 con la libreria mista | Dentro la guardia del §5.6 |
| F14 | E-I13: Twonky e Plex con `test-comune` aggiunta per la prova (A.5), Foto di Windows sulla share | Né l'indice né il cestino compaiono; se compaiono, M11 |
| F14-bis | Recinzione D33: all'onboarding con `test` si sceglie `Public` come area | L'app rifiuta l'area con un messaggio; nessun file creato in `Public` (controllo dal PC) |
| F15 | (4B) Crea, rinomina, sposta (stessa share e tra share), elimina, ripristina; file aperto in un editor sul PC; nome in conflitto; cartella intera | Come §2.7 |
| F16 | (4B) Prova delle 500 interruzioni | Invarianti del §5.5 veri in tutti i giri |
| F17 | (4B) Preferito e nascosto sull'S25 | Compaiono sul profilo del tablet con lo stesso account, **non** su quello di `test2` |
| F18 | (4B) Una settimana con Notte, una con Bilanciato | Meno del 2% di batteria al giorno con Notte; stime entro ±30% |
| F19 | APK di **release** sull'S25 | Scansione, preparazione, viewer, streaming e operazioni funzionano con R8 |
| F20 | (4B) Pulizia del cestino con ritenzione accorciata **solo in debug** e solo nell'area di prova | Elimina solo le voci del proprio diario; un record falso messo dal PC non fa eliminare nulla |

## 6. Sicurezza

### 6.1 Minacce e contromisure della Fase 4
| Minaccia | Contromisura | Prova |
|---|---|---|
| Segmenti o teste ostili (dispositivo compromesso, chiunque scriva via SMB) | codec stretto in `core-trust`, tetti, `ImageBounds` prima di decodificare, JSON canonico, firme e `HeadMark` (E-I14, C9) | P3, F8 |
| Dati falsi ma firmati da un dispositivo approvato | servono solo a mostrare; mai "al sicuro", mai azioni; unioni telefono↔NAS solo con impronta letta qui; adozione d'identità rifiutata sui conflitti (D7, D8) | P2, P4 |
| Fuga di dati fra account tramite l'indice | indice dentro la share (C19); regola M3: niente ricavato da un file privato nell'indice comune; i blob si cercano solo negli indici delle share che chi legge può già leggere; dati personali solo nell'area privata | P3 (proprietà), prova 10.4 con `test2` |
| Azione distruttiva innescata da dati remoti (E-S11, C10) | solo `core-nasops` scrive sui media; solo da `UserConfirmation` o dal diario locale; test di architettura; la pulizia non ha record remoti fra gli ingressi | P0, P12, F20 |
| Ransomware o errore di massa (C11) | congelamento con soglie (D10), notifica immediata | P5, F9 |
| Ora del NAS manipolata o che salta | tolleranze esistenti; salto > 5 minuti sospende le funzioni a scadenza (D32); un orologio all'indietro non fa eliminare nulla | P9, P12 |
| Finto NAS su una rete che imita casa (L19) | domanda aperta della revisione 2 provata in P1; anche se una sessione falsa restituisse contenuti, al massimo mostrerebbe media finti: nessuna azione, e una "sparizione di massa" congela | P1, P5 |
| Prenotazioni abusate | durata ≤ 1 ora, solo approvati; danno solo d'efficienza | P3 |
| Dispositivo perso o azzerato | `GalleryGraph.shutdown()` ferma anche il NAS prima di cancellare pacchi, diari e database; i file del suo cestino restano (sicuro) | P11 |
| Percorsi ostili nei record (`..`, `.mycloudgallery`, nomi riservati) | `SharePath.parseMediaPath`; un record non diventa mai un'operazione | P3 |
| Log e Diagnostica | nessun percorso, nome, share, account, id; solo codici e numeri; chiavi d'esportazione chiuse | P11, P16 |
| Test e prove sul campo che toccano dati veri (gli account di prova scrivono su `NAS` e `Public`, che sono pubbliche) | `TestAreaGuard` prima di ogni socket, share ammesse = file ∩ elenco nel codice, rifiuto esplicito delle share di famiglia, radice `mcg-it/<giro>/`, autotest; sul telefono `ShareFence` in debug (D33) | P1, P15, F14-bis |
| Pubblicazione prematura sulle share vere | D31 finché l'utente non chiude la 4A | P11 |

### 6.2 Limiti nuovi da dichiarare (proposta per `sicurezza.md` §6)
- **L26. L'indice non è cifrato** (come i media): miniature, anteprime e metadati (compreso il GPS) stanno nella share con i suoi permessi; chi legge la share legge l'indice.
- **L27. Un dispositivo approvato compromesso può pubblicare miniature o metadati falsi** per i file delle share in cui scrive, finché non viene revocato; non può far perdere né spostare file.
- **L28. Le impronte dell'indice altrui servono solo a collegare**: "al sicuro" richiede sempre una rilettura fatta dal dispositivo stesso; finché la verifica non c'è il badge non compare.
- **L29. Dati personali e autore**: dentro lo stesso account un dispositivo compromesso può modificare i dati personali (anche togliere un "nascosto") e attribuire voci a un altro dispositivo dello stesso account; non tocca gli altri account.
- **L30. Il cestino conserva i byte** (già L12 del formato): se un servizio del NAS li riconoscesse dal contenuto, si avvolgono (M11).
- **L31. Dopo un azzeramento** i file nel cestino del NAS di quel dispositivo non scadono più: restano finché qualcuno non li elimina dall'app (4B) o dal PC.
- **L32. Hash completi dei video del NAS** solo dove servono: due video diversi solo nel mezzo, con dimensione e `qh1` uguali, restano "da verificare" finché il passaggio `FULL_HASH` non li separa.

### 6.3 Checklist della Fase 4 (da aggiungere a `sicurezza.md` §7)
- [ ] (4A) Codec dei segmenti: test ostili; revisione mirata di `core-trust`.
- [ ] (4A) Un account di prova (`test2`) non vede tramite l'indice nulla dell'area privata di `test` (prova 10.4).
- [ ] (4A) Nessun byte ricavato da un file privato nell'indice dell'area comune (test di proprietà).
- [ ] (4A) Nessun modulo nuovo apre socket; nessun `DefaultDataSource`; `NoNetworkSourcesTest` esteso.
- [ ] (4A) Congelamento provato con il NAS sintetico e sull'area di prova (F9).
- [ ] (4A) L'indice non compare in DLNA, Plex, Twonky né in Foto di Windows (F14).
- [ ] (4A) D31 attivo alla consegna; nessuna scrittura sulle share vere prima dell'approvazione.
- [ ] (4A) Guardia delle prove e `ShareFence` (D33): nessuna richiesta verso `NAS`, `Public`, `alessio` o `pampa` con gli account di prova (autotest, F14-bis); la recinzione non è nella release.
- [ ] (4B) `UserConfirmation` costruita solo dalla UI; nessun dato remoto crea voci del diario.
- [ ] (4B) Pulizia del cestino solo dal diario locale, con handle, tetti, ora del NAS (F20).
- [ ] (4B) Prova delle 500 interruzioni superata (§5.5).
- [ ] (4B) Permessi e componenti nuovi (UIDT) in §4-bis e in policy, approvati.
- [ ] Log di release e Diagnostica: nessun percorso, nome, share o account (ricerca automatica e una sessione vera).

## 7. Rischi e piani B
| Rischio | Effetto | Mitigazione | Piano B |
|---|---|---|---|
| smbj lento sulle letture a intervalli (nessun compounding: 3 giri per file) | Rilevamento oltre la stima | 8 richieste in volo, una sessione, misura in M1 | Ridurre il rilevamento alla sola testa per le foto (EXIF e `qh1` richiedono comunque la coda: si tiene), unità più piccole |
| Sorvegliante a scadenza totale | Trasferimenti lunghi uccisi | D3 in P1, test a tempo virtuale | Operazioni lunghe spezzate in blocchi indipendenti |
| `MediaMetadataRetriever` su `MediaDataSource` via SMB lento o bloccato | Preparazione dei video ferma | Cache di lettura, scadenza per fotogramma, annullamento | `MediaExtractor` + `MediaCodec` solo sui fotogrammi chiave; in ultima istanza solo la copertina |
| Memoria con foto enormi | Processo chiuso | `PrepBudget`, temporanei, una decodifica pesante alla volta | Saltare oltre un tetto di pixel con "troppo grande" |
| CPU del NAS satura con più dispositivi (cifratura in software) | Tutti più lenti, timeout | 4 flussi per dispositivo, unità prenotate | Una prenotazione di share "un solo Preparatore alla volta" |
| `fileId` instabile dopo un riavvio del NAS | Spostamenti non riconosciuti dall'id | L'id è solo un indizio in più; restano data e `qh1` | Nessuno: basta `qh1` (128 KiB a file) |
| Rinomina solo di maiuscole e collisioni di Samba | Errori o doppioni | Passaggio per un nome temporaneo, test sul simulatore e sul NAS | — |
| Twonky o Plex vedono il cestino | Media "eliminati" visibili in TV | F14 | Involucro `MCGW` (M11) |
| Prestazioni della Libreria con molti elementi del NAS | Guardia mancata | `MCGT2` compatta, pacchi mappati, misura P10 | Presenza solo nei flag (senza colonne nuove), riferimento NAS risolto al primo uso |
| Testa oltre 1 MiB o 8.192 segmenti per una share enorme | Pubblicazione impossibile | Segmenti da 12–16 MiB, compattazione (4B) | Più serie di teste per share (`index-2`), con una modifica del formato |
| Segmenti scaricati sui dati mobili | Tetto consumato | D23, `remainingBytes` | Solo `meta` fuori casa |
| Prenotazioni contese (molti dispositivi) | Lavoro doppio | Unità da 200, rilettura dell'indice a ogni unità | Unità più grandi |
| Salto dell'ora quando l'NTP del NAS verrà sistemato | Prenotazioni ignorate, cestino sospeso | D32 | Gesto dell'utente in Diagnostica |
| Limiti di Android e bucket `Restricted` | Preparazione su più notti | Tappe da 8 minuti, in carica nessun limite, battito e avviso | "Fai ora" come UIDT |
| Migrazione v3 su una libreria vera | Dati persi | `ExportedSchemaTest` 1→3 e 2→3, mai distruttiva | — |
| Fusione dei dati personali sbagliata | Preferiti persi o nascosti scoperti | Ultima scrittura per campo, test con orologi sfasati, autore | Ripristino da un dispositivo ("Ripubblica i miei dati da qui") |
| Prova delle 500 interruzioni instabile o lenta sul NAS vero | Chiusura della 4B in ritardo | Banco JVM completo, giri piccoli sul NAS | Più giri sul JVM, meno sul NAS, con il consenso dell'utente |
| Conflitti con il filone grafico | Merge rotti, regressioni grafiche | §1.5, file di stringhe e voci separati, P10 come guardia | L'orchestratore serializza i due pacchetti sul file conteso |
| Crediti | Costo alto | Opus solo su 8 pacchetti su 18 | Spostare P4 o P12 su sonnet con il brief completo, con una revisione opus del risultato |
| Area di prova incompleta (file delle credenziali assente, password di prova troppo deboli per l'onboarding) | Integrazione solo simulata; prove sul telefono bloccate | Tutto gira sul NAS finto; le prove dal PC non passano dall'onboarding | M1 e M2 sul telefono appena le password sono rafforzate |
| Recinzione aggirata (share pubbliche raggiungibili dagli account di prova) | Prove che scrivono su `NAS` o `Public` | Guardia con elenco nel codice e autotest; `ShareFence` in debug; F14-bis | Togliere temporaneamente i permessi di scrittura a `test` e `test2` dove la dashboard lo consente |

### File cruciali
- `core-trust/.../nas/NasFileSystem.kt`, `data/DeviceHead.kt` (`Segments`, `HeadVerifier`, `HeadMark`), `data/Leases.kt`, `data/Trash.kt`, `TrustFormat.kt`: il formato che la Fase 4 completa.
- `core-nas/.../smb/SmbTransport.kt`, `session/OperationMonitor.kt`, `fs/SmbServerSideCopy.kt`, `testing/InMemorySmbBackend.kt`: accesso, sorvegliante, copia e simulatore.
- `core-model/.../Location.kt`, `Fingerprint.kt` (`QuickHashAlgorithm`), `domain/LinkingRules.kt`, `domain/SafetyRules.kt`, `MediaRef.kt`, `CellFlags.kt`: identità, impronte, presenza.
- `core-database/.../MediaCatalog.kt`, `dao/LibraryDaos.kt` (`TimelineIndexDao`, `WorkStateDao.nextPending`): il catalogo che la Fase 4 estende.
- `core-library/.../index/ColumnarTimelineSnapshot.kt`, `store/SnapshotStore.kt`; `core-imaging/.../RoutingThumbnailSource.kt`, `viewer/ImageOrigin.kt`: Libreria e miniature.
- `core-mediastore/.../sync/PhoneFingerprints.kt`, `DefaultMetadataPass.kt`: impronte da rendere generiche e la coda META da filtrare.
- `app/.../gallery/GalleryGraph.kt`, `graph/AppGraph.kt`, `graph/LocalData.kt`, `work/GalleryWork.kt`, `work/TrustSyncWorker.kt`: dove si innestano `NasGraph`, i lavori e l'azzeramento.

## 8. Cose che richiedono l'utente
| Cosa | Perché | Nel frattempo |
|---|---|---|
| File `nas-it.properties` con le chiavi del §5.4 (le share di prova esistono già) | Integrazione e campo (§5.4, §5.7 A) | NAS finto e sintetico; prove *saltate* |
| Password più forti per `test` e `test2` (oggi deboli) | L'app le rifiuta all'onboarding (`NasPasswordPolicy`) | Solo prove dal PC, che non passano dall'onboarding |
| `NAS` e `Public` restano pubbliche | Servono alla famiglia | Recinzione da codice: guardia delle prove (D30) e del telefono in debug (D33) |
| Campioni nell'area di prova (`test/mcg-campo/`) | F1, F4, F6 | Fixture sintetiche |
| Profilo "Prova" sul tablet con `test2` | M2, F7, F17 | Due "dispositivi" simulati |
| Risposte alle domande del §9 | Cambiano P5–P8, P12–P14 | Valgono le raccomandazioni |
| Nuovi edge case del §0.2 | Completare il §6 del piano | Già trattati |
| Approvare D31 a fine 4A (pubblicazione sulle share vere) | Prima scrittura sulle share di famiglia | Solo locale |
| Approvare il permesso UIDT (4B) | Policy di sicurezza | Operazioni solo con l'app aperta |
| Telefono e tablet collegati, a 120 Hz adattivi | P10, F13 | Emulatori per lo smoke |
| Revisione degli screenshot (badge, stato NAS, schermate della 4B) | Gate grafico | Generati in anticipo |
| Due settimane di uso normale con Notte e poi Bilanciato | F18 (4.6) | Stime dalle costanti della Fase 0 |

## 9. Domande per l'utente
| # | Domanda | Opzioni | Raccomandazione |
|---|---|---|---|
| Q1 | Dividere la fase in due metà? | (a) **4A** sola lettura, indice e Libreria; **4B** operazioni sui file, dati personali, 500 interruzioni. (b) Una fase sola | **(a)**: la 4A è utile da sola e non può perdere file; la parte distruttiva arriva dopo una revisione |
| Q2 | Qualità delle anteprime e anteprima HQ sul NAS | (a) Risparmio = miniature piccole (~100 MB per 13 mila media), **Standard** = piccole + medie (~380 MB), Alta = + HQ 2048 px (~5,6 GB); l'HQ si prepara per **tutti** (+4,9 GB sul NAS per `alessio`). (b) Come (a) ma HQ solo se un dispositivo usa "Alta" (si rilegge l'originale più tardi). (c) Misure diverse (es. HQ 1600 px) | **(a)**: il glossario mette l'HQ nella preparazione e rileggere 38 GB costerebbe un'altra notte; lo spazio sul NAS c'è (2,4 TB liberi) |
| Q3 | Spostamenti tra share: cosa fare della sorgente dopo la copia verificata? | (a) Nel **cestino** della sua share per 30 giorni (lo spazio si libera dopo). (b) Eliminata subito dopo la verifica completa | **(a)**: nessun errore può costare un file; lo spazio si libera comunque |
| Q4 | Provare la scansione sull'archivio vero già alla tappa M1? | (a) Sì, **in sola lettura** (nessun file scritto sul NAS), con l'APK di release e il tuo account, per misurare tempi e traffico. (b) No, solo sull'area di prova fino alla fine della 4A | **(a)**: i numeri veri arrivano presto, senza rischi per i file |
| Q5 | Video uguali su telefono e NAS ma non ancora verificati byte per byte | (a) **Una cella sola** con "da verificare" finché il controllo completo (di notte) non conferma. (b) Due celle finché non è verificato | **(a)**: nessun doppione visibile e nessuna unione d'identità senza prova |
| Q6 | Cambiamenti anomali di massa (possibile ransomware) | (a) Notifica **subito** e lavori sul NAS fermi finché non controlli. (b) Nel riepilogo giornaliero | **(a)**: è l'unico caso in cui ogni ora conta |
| Q7 | Dati mobili con l'app aperta | (a) Solo metadati e miniature di ciò che è sullo schermo; il resto aspetta il Wi-Fi. (b) Tutta la sincronizzazione dell'indice, entro il tetto mensile | **(a)**: è la proposta 3 del piano; la (b) può consumare un terzo del tetto al primo allineamento |
| Q8 | Eliminati di recente del NAS: offrire "Elimina subito"? | (a) Sì, con doppia conferma e l'avviso sul backup esterno (come "Libera subito" di Android). (b) No: solo la scadenza dei 30 giorni | **(a)**: serve per liberare spazio quando il NAS è pieno; resta un'azione esplicita |
| Q9 | Quali dati personali sincronizzare fra i tuoi dispositivi | (a) Preferiti, nascosti, album manuali, ricordi visti, esclusioni dell'area privata, "Mostra le copertine"; **non** preset, qualità e ruoli. (b) Anche preset e qualità | **(a)**: preset e qualità dipendono dal dispositivo (batteria, spazio) |
| Q10 | Hash completo dei video del NAS | (a) Solo quando serve (unione con il telefono, "al sicuro"). (b) Per tutti, di notte (circa 8 ore di letture), per trovare anche i doppioni fra cartelle | **(a)**; la (b) si può accendere più tardi con Utility → Doppioni (Fase 7) **Fase 7 (D27):** l'interruttore e' in Utility → Doppioni, spento di default, impostazione personale sincronizzata (`full_hash_nightly`). |

### 9.1 Risposte dell'utente (14/10)
| # | Risposta | Effetto |
|---|---|---|
| Q1 | (a) Divisa in **4A** e **4B** | D1 confermata |
| Q2 | (a) HQ 2048 px preparata **per tutti** | D12 e D16 confermate |
| Q3 | (a) Sorgente **nel cestino** per 30 giorni | D24 confermata |
| Q4 | (a) **Sì**, scansione in sola lettura dell'archivio vero già a M1 (F2) | M1 comprende F2 |
| Q5 | (a) **Una cella** con "da verificare" | D21 confermata |
| Q6 | **(b) Nel riepilogo giornaliero**, non con una notifica immediata | **D10 cambia**: il congelamento resta immediato (nessuna scrittura automatica, nessuna assenza segnata), ma l'avviso va nel riepilogo giornaliero dei problemi (proposta 12) e in evidenza nella Libreria e in Diagnostica, non come notifica a sé |
| Q7 | (a) Con i dati mobili solo ciò che è sullo schermo | D23 confermata |
| Q8 | (a) "Elimina subito" con doppia conferma e avviso sul backup esterno | D25, 4B |
| Q9, Q10 | Non chieste: valgono le raccomandazioni (a) | — |
| D29 | **Sì** al permesso `RUN_USER_INITIATED_JOBS` e al `JobService` per le operazioni lunghe avviate dall'utente (15/10) | P0-B li aggiunge a policy e §4-bis |
| Prove sul campo | La 4A si prova sul telefono **insieme** alla 4B (15/10): la 4B parte senza aspettare F1–F14 | Chiusura unica di 4A e 4B sul campo |

<!-- fine del documento -->
