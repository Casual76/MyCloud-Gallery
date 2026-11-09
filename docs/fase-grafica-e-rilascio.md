# Fase grafica e rilascio della beta (da 09/11/2026)

> Punto di partenza del nuovo thread. **Obiettivo dell'utente: pubblicare la beta sul Pampa Store entro questa settimana, senza fare troppo altro lavoro.** La grafica si fa quindi in versione ridotta: le voci che si vedono di piu' e che costano poco con l'engine 3.0. Il resto va dopo la beta. **Il taglio qui sotto e' una proposta: va confermato con l'utente all'inizio.**

## 1. Stato generale (09/11)
- **`main` = `93dc4884`**, pubblicato. Le Fasi 0-10 del piano sono complete nel codice. `tools\check.ps1 -NoDaemon` e' verde. Se la JVM cade con `EXCEPTION_ACCESS_VIOLATION`, rilancia con `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1`.
- **Fluid Engine 3.0.4** (definitivo). Dalla 3.0 l'app e' **GPL-3.0**, scelta accettata dall'utente. La ricognizione dei pezzi nuovi della 3.0 utili alla grafica e' in `docs/decisioni.md`, sezione "Adozione del Fluid Engine 3.0.1": la tabella "I pezzi nuovi della 3.0" e quella "La lista dell'utente e la 3.0".
- **Prestazioni.**
  - **S25:** dentro le soglie. Le sue soglie non si allentano.
  - **Tab S9:** riferimento in `docs/bench/riferimento-tab-s9.json`, con i fuori soglia noti; vedi `decisioni.md`, "Fluidita' sul Tab S9" e "Livello Anni sul Tab S9".
    - Viewer: ~41 ms il fotogramma piu' lungo tipico.
    - Livello Anni: 8,9% di fotogrammi in ritardo.
    - Pizzico: ~5,5%.

    Il resto dipende dal vetro e dalle celle, quindi dalla fase grafica. **Una modifica grafica non deve peggiorare i benchmark.**
- **Sicurezza, prove sul campo dell'S25 (03/11)**: `docs/prove-campo-esiti.md`, sezione "Fase 10: revisione 10.4 sull'S25".
  - S3 regge: nessun crollo con file ostili.
  - S2, S3, S7 e S9 sono **senza esito**: il lettore della Diagnostica vede solo gli ultimi ~50 eventi. Prima di rifarle serve un lettore completo dei rifiuti.
  - S7a, S10 e S11 non sono state fatte.
  - Copione: `tools/security/PROSSIMA-FINESTRA.md`.
  - Famiglia di prova: l'app debug dell'S25 (account `test`) e' admin. Il kit di recupero e' perso, da rigenerare.
- **Difetti aperti noti.**
  - **Da guardare per primo, perche' riguarda il cuore dell'app:** dopo un'installazione pulita, la preparazione del dataset di misura cade, una volta con `no such table: search_history` e una con `database is locked`. Va capito se succede anche all'app vera al primo avvio.
  - `widgetUpdateTablet` instabile.
  - `panesZoneOpen` non passa.
  - `benchDiag` va in timeout sulle tracce lunghe.
  - Residui della Fase 10, vedi `docs/review/10-fase10.md` e `funzionale-10.md`:
    - E-REL13: una conferma aperta non si chiude con un blocco;
    - i conteggi dei rifiuti non persistono;
    - la riga di Cerca del par. 2.3.

## 2. Grafica: proposta di taglio per la beta
Fonti dei requisiti:
- **`docs/grafica-segnalazioni-utente.md`**: la lista dell'utente, testo originale. E' la base.
- `docs/grafica-progetto.md`: la revisione G1/G2 della Libreria e le scelte dell'utente del 14/10.
- `docs/decisioni.md`: "Revisione grafica della Libreria da parte dell'utente (13/10)" e "Per la fase grafica (D38)".

Il giudizio dell'utente: la grafica della Libreria e' debole. I punti principali sono la leggibilita' sotto il vetro e i comandi che si sovrappongono o si compenetrano.

**Per la beta (vista alta, costo basso con la 3.0):**

| # | Voce | Con cosa |
|---|---|---|
| B1 | Il vetro che cambia misura o forma di colpo: la data in alto, "torna in fondo", l'entrata in selezione | `FluidFloatingCapsule`, `fluidPhysicsSurface` + `morphTo`, i token di `FluidMotion` |
| B2 | Le tre scritte di stato in fondo alla Libreria (NAS, anteprime, al sicuro) diventano una nuvola nel blocco di vetro in alto a destra. Il pop-up mostra il problema, oppure lo spazio usato e il totale, l'ultima sincronizzazione e "Libera spazio" | `FluidSyncIndicator` + il pop-up nostro |
| B3 | La barra delle azioni della selezione sopra la pillola (comandi che si compenetrano) | `FluidFloatingCapsule(ReplacingTabBar)` |
| B4 | Viewer: il tasto ⋮ e quello di chiusura con lo stesso vetro; il menu che si apre dalla goccia del tasto | `FluidGlassIconButton`, `FluidMorphMenuButton(opensOnTap)` |
| B5 | Viewer: le foto piu' vecchie a sinistra di quella aperta (oggi sono a destra) | ordine del pager, nostro |
| B6 | Pagina "Nuovo dispositivo": il pulsante "Approva" sta sotto la piega e la tastiera lo copre | layout |
| B7 | Le scritte sotto il vetro leggibili (contrasto adattivo), dove la 3.0 non basta | vetro nostro (`galleryGlass`) |

**Da decidere con l'utente se entra nella beta:** la pillola come in Foto di Apple, con la ricerca separata (`FluidSearchField` a cerchio) e gli Album al posto di Cerca, per alleggerire Raccolte. Cambia l'organizzazione dell'app, quindi costa di piu'.

**Dopo la beta:**
- pannello informazioni come in Galleria Samsung (mezzo schermo, contenuto che sale, info compatte);
- pizzico in chiusura per chiudere la foto;
- sfondo che sfuma alla chiusura, con la foto che torna alla sua cella;
- zoom dei video;
- vetro del viewer gia' a zoom minimo;
- filtro "scattate con questo dispositivo";
- trascinare dal fondo per aggiornare;
- lag alla prima apertura, se non si risolve con poco;
- la migrazione ai pezzi 3.0 senza effetto visibile (`FluidToggleRow`, `FluidChoiceRow`, `FluidSheetScaffold`...);
- livello Anni e pizzico sul tablet.

**Vincoli:**
- **Non migrare a `FluidConfirmSheet`**: non ha la difesa dal tapjacking.
- Per ogni modifica visiva servono gli screenshot in chiaro e scuro, su telefono e tablet. L'utente li rivede prima della beta.
- L'aspetto si giudica sull'S25 dell'utente.

## 3. Cose che bloccano il rilascio
`tools/release/release.ps1` controlla:
1. chiavi del manifest vere (`ReleaseKeys.PROVISIONAL = false`);
2. impronta della firma;
3. recinzioni;
4. `check.ps1 -Bench -BenchStrict` verde con l'S25 collegato, e riferimento dell'S25 di al piu' 7 giorni.

In ordine:
1. **L'utente** crea K1 e K2 (`tools\release\manifest-tool.ps1 keys`, K2 offline). Poi un agente mette `PROVISIONAL = false`. La firma `pampa.jks` e' gia' configurata e l'impronta e' in `sicurezza.md` par. 5.6.
2. **Le recinzioni** D12, D30, D34 e F4-D31 si spengono **tutte insieme**, solo con il si' dell'utente: vedi `decisioni.md`, "Decisioni dell'utente del 31/10", e `tools/release/recinzioni.json`. Il passaggio alle share vere (par. 2.10) vuole un si' con i numeri della "Simulazione del passo".
3. **Finestra con l'S25**: benchmark e riferimento fresco. Va decisa con l'utente quanta sicurezza sul campo rifare prima della beta. La proposta minima e' S7: le buste del manifest, cioe' il kill switch, verificate con un lettore della Diagnostica completo.
4. **Decisione dell'utente su 5B I3**: "Elimina dal telefono" dalla selezione della Libreria passa dal foglio "Prima mettilo al sicuro" quando il backup e' attivo.
5. **L'utente toglie** dal registro della famiglia vera il dispositivo della release candidata del 02/11.
6. **Pubblicazione sul Pampa Store** con la skill `pampa-store-publish-direct`, prima una prova a vuoto. Domanda aperta: la firma v1, perche' AGP firma solo v2 e v3 con minSdk >= 24.

Dettaglio delle cose che puo' fare solo l'utente: `docs/da-fare-utente.md`.

## 4. Riferimenti
- Piano completo: `C:\Users\casua\.claude\plans\cozy-swinging-origami.md`. La fase grafica e' il "Filone grafico parallelo"; il rilascio e' 10.5.
- `docs/decisioni.md`: tutte le decisioni, in ordine di tempo; le ultime sezioni sono le piu' recenti.
- `docs/sicurezza.md`: threat model e sigle C/L. Gli intervalli usati sono nella riga "Sigle".
- `docs/bench/README.md`, `benchmark/soglie.json`, `tools/bench/compare.ps1`: misure e soglie. `compare.ps1` accetta soglie diverse per dispositivo con `perDispositivo`.
- Memoria del progetto: `C:\Users\casua\.claude\projects\C--VibeCoded-Projects-MyCloud-Gallery-2\memory\` (vincoli duri su NAS, telefono, segreti e download in `mycloud-gallery-restart.md`).
