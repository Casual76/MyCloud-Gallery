# Verifica funzionale: Fase 7, album, condivisione, Utility, Nascosti e Raccolte (commit `870a723b`, 27/10/2026)

**Stato (27/10).** Le 46 voci sono corrette nel codice dai pacchetti di correzione A, B, C, D ed E (`fase7/rev-a`...`fase7/rev-e`, uniti in `integra/27-10a` e `integra/27-10b`): ogni voce ha una correzione con un test, nessuna e' stata scartata e nessuna resta aperta. La verifica dei fake (§3) ha trovato un'altra divergenza, ora chiusa: il diario finto leggeva le operazioni chiuse (SH-2) e quello su Room no (§7). Il quadro per voce e' nel §7; le decisioni nelle sezioni "Fase 7, correzioni dopo le revisioni: pacchetto A/B/C/D/E" di `docs/decisioni.md`. I flussi del §4 restano da provare sul telefono (F72-F93).

**La domanda.** Ogni funzione della Fase 7 promessa all'utente funziona davvero nell'app installata, da capo a fondo, in debug e in release? Questa non
e' una revisione di sicurezza: quella sta in `docs/review/7-fase7.md`.

**Come.** Ho letto il codice committato, senza eseguire niente: niente Gradle, telefono, emulatori, `adb` o NAS. Per ogni flusso dell'utente ho seguito
questa catena: schermata o tasto → ViewModel → porta → oggetto legato in `:app` (Hilt, `AlbumsGraph`, `AlbumsLive`, `AlbumsWiring`, `GalleryGraph`,
`NasGraph`, `BackupGraph`, `UtilityGraph`, `work/`, debug e release) → codice che tocca NAS, telefono o database → risultato che torna alla schermata.
In piu' ho confrontato i fake dei test (`core-albums/.../testing/`, `feature-library/.../utility/testing/` e `trash/testing/`, le sonde e i banchi di
`app/src/test`) con le implementazioni vere. Tre sotto-agenti hanno letto in parallelo, con lo stesso metodo: album personali, smart, viaggi e Raccolte;
la condivisione; Utility, Nascosti, Diagnostica e azzeramento. Le voci "Blocca" e le "Sbagliata" piu' pesanti le ho ricontrollate nel codice (SP-1, DP-1,
DP-2, DP-4, AS-1, AS-2, AS-10, VG-1, RC-1, SH-1, SH-2, SH-3, SH-4, SP-2, SC-1, SN-1, SG-2, ED-1, NA-1, DG-1). Le altre le riporto con lo stato che hanno
dato loro.

**Il modello dei difetti** e' quello di `docs/review/funzionale-6.md`:
- un tasto che non fa niente, o che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, o uno stato perso con la morte del processo o con la chiusura della pagina;
- un fake diverso dal vero;
- una porta mai legata, o legata a un null-object, o legata all'oggetto sbagliato;
- un lavoro che finisce a vuoto, o che nessuno programma;
- un errore inghiottito;
- un percorso irraggiungibile;
- una differenza fra debug e release (R8, `src/debug` e `src/release`, le recinzioni D12, R2, F4-D31, D30) che rompe una funzione.

**Perimetro.** La Fase 7 cosi' come P13 l'ha integrata in `:app` (P0–P11; P12 e' solo lo strumento di valutazione):
- album personali, da ogni ingresso, con ordine, copertina, rinomina, eliminazione e sincronizzazione;
- album smart: nome, editor delle regole, "Suggerite", congela, ricalcolo;
- viaggi ed eventi;
- album condivisi: foglio, spostamento, caricamento dal telefono, completamento, ricezione dal familiare, permessi, chiusura con Q5, "Riporta", E-SH16, E-SH20;
- Utility, Doppioni, Eliminati di recente;
- Nascosti su ogni superficie;
- Raccolte;
- Diagnostica (§2.8) e azzeramento.

**Stato di ogni voce:**
- **confermato**: il percorso completo e' nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravita':**
- **Blocca**: la funzione non si puo' usare, oppure l'app si chiude;
- **Sbagliata**: funziona male, o promette cio' che non fa;
- **Minore**.

**Percorsi e righe.** Sono relativi alla radice del repository. `app/...` sta per `app/src/main/kotlin/dev/pampa/mycloudgallery/...`; `core-albums/...`
sta per `core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums/...`, e cosi' per gli altri moduli (`feature-albums/...` per
`feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/...`, `core-database/...` per
`core-database/src/main/kotlin/dev/pampa/mycloudgallery/core/database/...`). I numeri di riga sono quelli di `870a723b`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Album personali e "Aggiungi ad album" (AP, AA) | 0 | 1 | 3 | 4 | 2 | 2 |
| Album smart (AS) | 0 | 3 | 7 | 10 | 6 | 4 |
| Viaggi ed eventi (VG) | 0 | 1 | 1 | 2 | 2 | 0 |
| Condivisione: foglio, piano e stato (SH) | 0 | 4 | 1 | 5 | 4 | 1 |
| Spostamento e caricamento (SP) | 1 | 1 | 0 | 2 | 2 | 0 |
| Chiusura, "Riporta", E-SH16, E-SH20 (SC, SB, SN) | 0 | 1 | 3 | 4 | 1 | 3 |
| Cancelli, lavori e release (SG) | 0 | 1 | 1 | 2 | 1 | 1 |
| Utility e Doppioni (UT, DP) | 1 | 3 | 4 | 8 | 4 | 4 |
| Eliminati di recente (ED) | 0 | 1 | 1 | 2 | 2 | 0 |
| Nascosti (NA) | 0 | 1 | 1 | 2 | 1 | 1 |
| Raccolte (RC) | 0 | 1 | 1 | 2 | 1 | 1 |
| Diagnostica e azzeramento (DG, AZ) | 0 | 1 | 2 | 3 | 2 | 1 |
| **Totale** | **2** | **19** | **25** | **46** | **28** | **18** |

**Il quadro.**
- A processo caldo, in debug e sulle share di prova, molti cammini arrivano in fondo:
  - album manuali creati dalle Raccolte, dalla selezione della Libreria e dal viewer, con "Aggiungi foto", ordine, copertina, rinomina ed
    eliminazione, e le modifiche fatte su un altro dispositivo dell'account;
  - la condivisione dei media **dell'area privata**: foglio, conferma, spostamento con le operazioni della 4B, completamento sullo stesso media prima
    del cestino, voce e testa pubblicate, il familiare che vede l'album con le azioni del suo livello; il foglio di chiusura senza preselezione (Q5);
    "Riporta"; E-SH16 ed E-SH20 senza nessun file mosso;
  - le pagine-filtro dell'Utility, gli Eliminati di recente con i due cestini dietro il cancello, Impostazioni → Privacy → Nascosti, l'avviso E-H2;
  - le Raccolte con ordine e visibilita' sincronizzati;
  - l'azzeramento, con gli album fermati per primi.
- I difetti stanno in sei posti:
  - **due promesse che non partono mai**: i media che stanno solo sul telefono non arrivano mai nella cartella dell'album (SP-1), e i doppioni identici
    fra cartelle del NAS non si trovano mai (DP-1);
  - **il calcolo va, la schermata non lo sa**: gli album smart si calcolano su Room ma elenco e pagina non si aggiornano (AS-1), uno smart nuovo dice
    "Nessuna foto corrisponde alle regole" (AS-2), le novita' arrivate durante un giro restano ferme (AS-3); i Viaggi e i Doppioni simili si calcolano
    solo di notte, in carica (VG-1, DP-4);
  - **pezzi del contratto mai riempiti**: "Da condividere" degli smart condivisi (SH-4, Q4), "In arrivo" (SP-2), "N file nuovi nella cartella:
    aggiungili" (SH-3, D39), "Riprova" (SH-2); in piu' rifare lo stesso piano dopo un fallimento risponde "gia' avviato" e non fa niente (SH-1);
  - **la chiusura vista dal creatore**: dopo "Smetti di condividere" la pagina dice "non trovato" e la copia personale arriva al giro dell'indice (SC-1);
  - **Nascosti e Doppioni**: il Cestino e lo Spazio di Cartelle NAS mostrano i nomi dei file nascosti, il primo senza cancello (ED-1, NA-1); la pulizia
    guidata propone di eliminare la copia dell'area comune, anche quando e' un elemento di un album condiviso (DP-2);
  - **le Raccolte**: "Tutti gli album" c'e' solo oltre 6 album, e con lui l'ordine dell'elenco, gli album lasciati e gli smart oltre il sesto (RC-1).
- **La release** non ha difetti suoi di R8 o di legami: `DeveloperTools.shareGuard()` di release e' null, D30 vale uguale nelle due build, nessun pacchetto
  `testing` entra in `:app`. Resta una differenza di regole (SG-1): in release con un account di prova (R2), e sulle share vere finche' D12 resta
  acceso, il foglio promette caricamenti dal telefono e copie del guardiano che non possono partire.

**Le cose piu' gravi:**
- **I media solo sul telefono non si condividono mai (SP-1).** `AlbumsWiring` passa ad `AlbumFolderUploads` il cancello del **backup**, che per l'area
  comune risponde `NO_PRIVATE_AREA`. Ogni caricamento e' rifiutato come "scritture ferme", la pagina dice "Riprendo da sola", e dopo 10 minuti
  l'elemento finisce `NOT_STARTED`. La Diagnostica lo conta come `SHARE_PAUSED_FROZEN`. Il cancello giusto (`fiveB.incomingGate`) c'e' gia'. F79 (i 3
  media solo sul telefono, E-SH1) oggi non puo' passare.
- **I doppioni identici fra cartelle del NAS non nascono (DP-1).** La scansione del NAS non ha mai l'hash completo, quindi due file uguali in due
  cartelle diventano due media; la query dei Doppioni cerca una versione con due posizioni NAS. Il gruppo nasce solo se il telefono fa da ponte.
  L'interruttore "Controlla di notte i video del NAS" calcola gli hash ma non unisce niente. F76 oggi non puo' passare.
- **Gli album smart non si vedono riempire (AS-1, AS-2).** Il motore scrive le righe dopo la proiezione, e nessuno ricalcola elenco e pagina: uno smart
  appena creato, da un nome o da un viaggio, resta "Nessuna foto corrisponde alle regole" finche' non si esce e si rientra.
- **La condivisione non sa ripartire (SH-1, SH-2).** Con l'impronta del piano unica anche fra le operazioni finite, lo stesso "Aggiungi" dopo un fallimento
  risponde `AlreadyStarted`; la striscia "N da riprovare" non ha un tasto e sparisce con il processo. Con SP-1 tutti i media del telefono finiscono cosi'.
- **I Viaggi dicono una cosa falsa fino alla prima notte in carica (VG-1):** "Servono foto con la posizione per riconoscere i viaggi", con migliaia di
  foto con GPS.
- **Nascosti:** il Cestino di Cartelle NAS (anche da Backup) mostra nomi, cartelle e "Elimina subito" senza il cancello degli Eliminati di recente
  (ED-1); lo Spazio NAS elenca per nome i file piu' grandi, nascosti compresi (NA-1). F73 ("nessun nome in Cartelle NAS") non li guarda.
- **Il creatore che chiude un album (SC-1)** finisce su "Album non trovato"; l'album personale che D31 promette arriva solo al giro dell'indice dopo.

---

## 2. I difetti

### 2.1 Album personali e "Aggiungi ad album" (AP, AA)

#### AA-1 · "Aggiungi ad album" manca dalla ricezione quando l'app era chiusa
- **Gravita':** Sbagliata. **Stato:** plausibile (agente degli album).
- **Flusso:** app chiusa; da un'altra app "Condividi" → MyCloud Gallery; la schermata di ricezione.
- **Percorso:**
  - `ReceiveViewModel` legge gli album una volta sola, appena la copia e' fatta (`feature-integrations/.../receive/ReceiveViewModel.kt:372`, `:389`), e
    con la lista vuota la voce non c'e' (`:487-502`);
  - `AppIncomingAlbums.albums()` risponde con `AppAlbums.all.value` (`app/.../albums/IncomingAlbumPromises.kt:101-102`), vuoto finche' gli album non
    sono montati (`app/.../albums/AlbumsWiring.kt:43-47`: galleria partita e dati personali agganciati) e il primo `refresh` non e' passato
    (`core-albums/.../personal/CatalogAlbums.kt:86`, `:99`);
  - nessuno rilegge la lista. Lo stesso vale per l'album gia' scelto (P13, aggancio rimasto 9).
- **Atteso:** D35, F5-D24, F90.
- **Correzione:** `albums()` aspetta `graph.ensureLive()` e il primo elenco (con un tetto di pochi secondi), oppure la schermata segue il flusso.

#### AA-2 · Dalla ricezione non si crea un "Nuovo album"
- **Gravita':** Minore. **Stato:** confermato (agente degli album).
- **Flusso:** ricezione con zero album, o con un album nuovo in mente.
- **Percorso:** `feature-integrations/.../receive/ReceivePorts.kt:84-114`: `IncomingAlbumChoice` solo per gli album che ci sono; con zero album la voce
  "Aggiungi ad album" non compare.
- **Atteso:** §2.1, "Nuovo album" anche dalla ricezione.
- **Correzione:** "Nuovo album" fra le scelte della ricezione, oppure togliere la ricezione dagli ingressi del §2.1.

#### AA-3 · Il selettore dei media dopo la morte del processo: "Scegli" non fa niente
- **Gravita':** Minore. **Stato:** confermato (dichiarato nel codice).
- **Percorso:** `app/.../navigation/AlbumsNavigation.kt:74-90`: la richiamata del selettore vive in memoria; dopo la morte del processo `picked` esce in
  silenzio.
- **Correzione:** un messaggio ("La scelta non vale piu', riprova") al posto del silenzio.

#### AP-1 · "Nessun album" nei primi secondi dopo l'avvio
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** `AppAlbums.all` parte da `AlbumList.EMPTY` e `feature-albums/.../list/AlbumListController.kt:56-60` lo traduce in `Empty` ("Nessun album,
  creane uno") finche' gli album non sono montati.
- **Correzione:** uno stato "in lettura" finche' `AlbumsGraph.live` e' null.

### 2.2 Album smart (AS)

#### AS-1 · Dopo il calcolo di uno smart nessuno aggiorna elenco e pagina
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente degli album).
- **Flusso:** creare uno smart, cambiarne le regole, "Scongela", "Crea album" da un viaggio, o foto nuove che entrano: elenco, sezione "Album smart",
  conteggi e pagina aperta restano come prima.
- **Percorso:**
  - `CatalogAlbums.content` si ricalcola solo con `revision` o con l'istantanea della Libreria (`core-albums/.../personal/CatalogAlbums.kt:108`);
    `revision` sale solo in `refresh()` (`:142-151`), chiamato dai dati personali, dall'istantanea e dagli album condivisi (`:99-103`) e da
    `onCatalogChanged` (`:183-190`);
  - il motore scrive le righe automatiche e i suggeriti solo su Room (`core-albums/.../smart/SmartRows.kt:90-105`), senza avvisare nessuno;
  - in `:app` l'ordine e' rovesciato: `AlbumsGraph.onCatalogChanged` proietta e rinfresca prima (`app/.../albums/AlbumsGraph.kt:154`) e ricalcola gli
    smart dopo (`:157-160`); `watchPersonal` ricalcola 2 s dopo il salvataggio (`:199-205`); la notte ricalcola tutto (`:304-308`). In nessuno dei tre
    casi segue un `refresh`.
- **Perche' i test non lo vedono:** la suite di contratto chiama `onCatalogChanged()` dopo ogni giro del motore
  (`core-albums/src/test/.../CatalogAlbumsContractTest.kt:81`, `:101-121`, "lo fanno i lavori di :app"); in `:app` quel passo non c'e', e nessun test di
  `app` monta `AlbumsGraph` con uno smart.
- **Atteso:** D5 ("l'album si aggiorna subito"), D16, §2.2 "Ricalcolo", F87.
- **Correzione:** dopo ogni `recompute.*` di `AlbumsGraph` (tre punti) chiamare `live.albums.onCatalogChanged(ids)`, oppure dare a `CatalogAlbums` un
  segnale dal motore; un test in `app/src/test` con `AlbumsGraph` vero che crea uno smart e legge `all` e `content`.

#### AS-2 · Uno smart appena creato dice "Nessuna foto corrisponde alle regole"
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** "Crea" nell'editor, oppure "Crea album" da un viaggio: si apre la pagina con "Nessuna foto corrisponde alle regole. Prova ad allargare il
  periodo o il luogo". Con AS-1 resta cosi' finche' non si esce e si rientra.
- **Percorso:**
  - lo stato `AlbumPageState.Recomputing` nasce solo da `AlbumsStatus.Recomputing`
    (`feature-albums/.../page/AlbumPageController.kt:141-142`);
  - `CatalogAlbums.status` e' una costante `Ready` (`core-albums/.../personal/CatalogAlbums.kt:95`), e la facciata la segue;
  - la pagina vuota: `feature-albums/src/main/res/values/strings_albums_page.xml:81-82`.
- **Effetto in piu' (plausibile):** "Nuove: N" ingloba il primo riempimento. La pagina aperta vuota segna "visto" (`app/.../albums/AlbumFacades.kt:114-115`),
  le righe arrivano dopo con `addedAt` piu' recente, e alla riapertura tutte le foto risultano "nuove".
- **Atteso:** §1.5 `AlbumPageState.Recomputing(progress)`, D16.
- **Correzione:** pubblicare `Recomputing` mentre `SmartRecompute` lavora su un album senza righe, oppure "Sto cercando le foto" per uno smart nuovo e vuoto;
  segnare "visto" solo dopo il primo calcolo.

#### AS-3 · Le novita' del catalogo arrivate durante un giro aspettano la novita' dopo
- **Gravita':** Sbagliata. **Stato:** confermato (la logica; l'effetto dipende dai tempi).
- **Flusso:** dopo una scansione o un gruppo di foto, l'ultimo pezzo di novita' non entra negli smart, non risolve gli elementi "in attesa" e non mantiene
  le promesse della ricezione (`afterCatalog`) finche' il catalogo non cambia ancora.
- **Percorso:** `app/.../albums/AlbumsGraph.kt:136-150`: un giro parte solo se `pending` non e' attivo (`:142`); il giro aspetta 5 s, svuota `touched` e poi
  lavora (`:144-146`) anche decine di secondi. Gli id arrivati nel frattempo restano in `touched` senza un giro.
- **Atteso:** D16 ("a ogni cambiamento del catalogo che tocca il suo periodo"), D5, P13-12.
- **Correzione:** a fine giro, se `touched` non e' vuoto, ripartire (oppure un canale con accorpamento).

#### AS-4 · La ricerca per contenuto che diventa pronta non rifa' gli smart con descrizione o esempi
- **Gravita':** Minore. **Stato:** confermato.
- **Percorso:** `EngineContentSearch` guarda `readiness` a ogni chiamata (`core-albums/.../smart/SmartContent.kt:39`), ma nessuno ascolta il passaggio a
  `Ready`: le regole C e S4 entrano al primo evento sul periodo o alla notte. Per il codice di Diagnostica vedi DG-1.
- **Atteso:** D15.
- **Correzione:** `AlbumsGraph` ascolta `readiness` e rivaluta gli smart con testo o esempi.

#### AS-5 · Gli smart non si riprendono all'avvio e dopo un giro parziale
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:**
  - `watch()` (`app/.../albums/AlbumsGraph.kt:121-133`) proietta e attesta i condivisi ma non rivaluta gli smart: le novita' arrivate prima del
    montaggio non li toccano;
  - un esito `Partial` non scrive niente (`core-albums/.../smart/RulesSmartEngine.kt:78-84`) e gli album saltati per il budget
    (`smart/SmartRecompute.kt:44-52`) non si riprendono: l'esito si ignora (`AlbumsGraph.kt:158-159`, `:203`, `:307`);
  - uno smart arrivato da un altro dispositivo prima del montaggio non si valuta (`watchPersonal` ignora il primo stato, `:190-197`).
- **Atteso:** D16.
- **Correzione:** a fine montaggio valutare gli smart senza righe; riprendere `Partial` e saltati.

#### AS-6 · Ogni gruppo di novita' rifa' la ricerca per contenuto degli smart senza periodo
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** `core-albums/.../smart/RulesSmartEngine.kt:104` (`evaluateLocked(INFINITE)`), `smart/SmartRecompute.kt:43-44`, finestra di 5 s
  (`app/.../albums/AlbumsGraph.kt:331`): durante una scansione lunga ogni gruppo, anche solo "al sicuro", rilancia la ricerca su tutta la Libreria.
- **Correzione:** gli smart con descrizione e senza periodo a fine scansione, o solo sui media dei giorni toccati.

#### AS-7 · L'editor delle regole non protegge le chiamate al motore
- **Gravita':** Minore. **Stato:** plausibile (nessun try/catch; serve un errore del motore).
- **Percorso:** `feature-albums/.../editor/SmartEditorController.kt:98-115`, `:179-189`; `EngineContentSearch.match` non cattura
  (`core-albums/.../smart/SmartContent.kt:41-60`). Un errore di `propose` o `preview` arriva al `viewModelScope` senza gestore: l'app si chiude.
- **Correzione:** try/catch che rilancia `CancellationException` e mostra "anteprima non riuscita".

#### AS-8 · Due difetti dell'editor in modifica
- **Gravita':** Minore. **Stato:** confermato (agente degli album).
- **Percorso:**
  - dopo "Salva le regole" la pila ha due pagine dello stesso album: `onSaved` fa `popBackStack()` e apre l'album
    (`feature-albums/.../nav/AlbumsNavigation.kt:104-105`), ma in modifica la pagina vecchia e' ancora sotto;
  - in modifica il selettore delle foto d'esempio si chiama "Aggiungi foto": `SmartEditorRoute.kt:82` passa l'album e `app/.../navigation/AlbumsNavigation.kt:145`
    sceglie il titolo da `forAlbum != null`.
- **Correzione:** `popUpTo` sulla pagina dell'album; un segno esplicito per il selettore delle foto d'esempio.

#### AS-9 · Due legami che dipendono dai tempi
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:**
  - per uno smart arrivato da un altro dispositivo, `watchPersonal` aspetta 2 s "a tempo" la proiezione fatta da un altro collettore
    (`app/.../albums/AlbumsGraph.kt:199-205`): con molti album il motore puo' leggere `album_rule` vuota;
  - la cartella del backup per la regola B si legge una volta al montaggio (`app/.../albums/AlbumsLive.kt:194`, `AlbumsWiring.kt:96`) senza aspettare
    che le impostazioni del backup siano caricate: se il montaggio vince la corsa, le foto liberate dal telefono contano come "altro dispositivo" per tutto
    il processo (P13, aggancio rimasto 8, lo dice solo per la cartella scelta dopo).
- **Correzione:** un segnale di fine proiezione al posto del ritardo; la cartella letta a ogni valutazione.

#### AS-10 · "Scongela" toglie anche le foto aggiunte a mano mentre l'album era congelato
- **Gravita':** Minore. **Stato:** confermato (verificatore principale).
- **Percorso:** da congelato "Aggiungi" scrive voci `item:` (`core-albums/.../personal/AlbumEditor.kt:99-101`), uguali a quelle del congelamento;
  `unfreeze` le toglie tutte (`:233-240`). Le aggiunte fatte prima di congelare restano (sono `accept:`, `:95-97`).
- **Atteso:** D16 ("Scongela le riattiva tenendo esclusioni e accettazioni").
- **Correzione:** da congelato, scrivere anche `accept:` per le aggiunte a mano; oppure dirlo nella conferma di "Scongela".

### 2.3 Viaggi ed eventi (VG)

#### VG-1 · I viaggi si cercano solo di notte, e prima la sezione dice "Servono foto con la posizione"
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente degli album).
- **Flusso:** installazione nuova, prima scansione fatta, Raccolte → "Viaggi ed eventi: Servono foto con la posizione per riconoscere i viaggi", anche con
  migliaia di foto con GPS; resta cosi' fino alla prima notte in carica.
- **Percorso:**
  - l'unico che chiama `detect` e' `AlbumsGraph.nightly` (`app/.../albums/AlbumsGraph.kt:304-308`), da `LibraryMaintenanceWorker`
    (`app/.../work/GalleryWork.kt:161`): periodico a 24 ore, in carica e con la batteria non scarica (`:56-61`), dopo gli altri passi della manutenzione;
  - prima della prima rilevazione `homeKnown` e' falso (`core-albums/.../trips/CatalogTrips.kt:101-103`, `:123`), e
    `TripsController.stateOf` lo legge come `NoGps` (`feature-albums/.../trips/TripsController.kt:74-75`), che la sezione mostra come riga
    (`feature-albums/.../sections/AlbumsSections.kt:187-191`).
- **Atteso:** §2.3 "Quando: una volta per notte **e dopo un primo backup o una prima scansione**"; E-AL16; F89.
- **Correzione:** `detect` anche dopo la prima scansione e il primo backup (al piu' una volta al giorno); "non ancora cercati" distinto da "nessuna casa".

#### VG-2 · "Crea album" dai viaggi, tre difetti piccoli
- **Gravita':** Minore. **Stato:** confermato (agente degli album).
- **Percorso:**
  - `TripAccepted.NotFound` non fa niente e non dice niente (`feature-albums/.../trips/TripsRoute.kt:49`);
  - uscendo fra la creazione dell'album e `markAccepted` lo scope della pagina annulla: la proposta resta e un secondo tocco crea un doppione
    (`core-albums/.../trips/CatalogTrips.kt:183-185`);
  - l'impronta dipende dai giorni (`core-albums/.../contract/Trips.kt:25-33`): un viaggio in corso, scartato e poi allungato, torna con un id nuovo.
- **Correzione:** un messaggio per `NotFound`; creazione e `markAccepted` in `NonCancellable`.

### 2.4 Condivisione: foglio, piano e stato (SH)

#### SH-1 · Lo stesso piano dopo un'operazione finita risponde "gia' avviato" e non fa niente
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente della condivisione).
- **Flusso:** "Aggiungi" a un album condiviso (selezione, viewer, pagina) con gli stessi media di un'operazione finita con fallimenti (`NOT_STARTED`,
  `TRANSFER_FAILED`, `SOURCE_CHANGED`): il foglio si chiude come se fosse partito, e non parte niente. Lo stesso per un "Riporta" ripetuto.
- **Percorso:**
  - `ShareOrchestrator.start` chiede `opByPlan(plan.hash)` prima di tutto (`core-albums/.../share/ShareOrchestrator.kt:84`), anche fra le operazioni
    finite (`app/.../albums/RoomShareDiary.kt:55`, `core-database/.../dao/AlbumV6Daos.kt:196`); `plan_hash` e' unico
    (`core-database/.../entity/AlbumV6Entities.kt:200`);
  - per un album che esiste l'impronta non ha nonce ne' istante (`core-albums/.../contract/ShareConfirmation.kt:30-58`): stesso piano, stessa impronta;
  - il foglio tratta `AlreadyStarted` come partito (`feature-albums/.../share/ShareSheetController.kt:149`);
  - il fake ha lo stesso difetto (`InMemoryShareDiary.kt:49`), quindi nessun test lo vede.
- **Atteso:** §2.5 "Errori: `FAILED` con «Riprova»", E-SH18.
- **Correzione:** `opByPlan` solo fra le operazioni aperte, e l'indice unico tolto (o la chiave con un nonce per tentativo).

#### SH-2 · Non c'e' "Riprova": la striscia dei problemi non fa niente, non dice il motivo e sparisce al riavvio
- **Gravita':** Sbagliata. **Stato:** confermato.
- **Flusso:** E-SH8 (spazio), E-SH18 (file cambiato), E-SH19 (congelamento), e ogni fallimento.
- **Percorso:**
  - la striscia `PartlyDone` ("N da riprovare") non ha azione (`feature-albums/.../page/AlbumStrips.kt:130-135`) e non dice il motivo;
  - `ShareOrchestrator.operations` vive in memoria (`seen`, `core-albums/.../share/ShareOrchestrator.kt:71`): dopo la morte del processo le operazioni finite
    non si vedono piu';
  - "Riprendo da sola" e "Riprendo quando c'e' posto" valgono solo se l'operazione della 4B e' gia' nata: un rifiuto prima della partenza (spazio,
    scritture ferme, SP-1) diventa `NOT_STARTED` dopo 10 minuti (`core-albums/.../share/ShareSteps.kt:133-136`).
- **Atteso:** §0.2 E-SH18 ("Non condiviso: il file e' cambiato" con "Riprova"), F81 ("ripresa togliendo il tetto").
- **Correzione:** le operazioni finite lette dal diario con i motivi; "Riprova" che fa un piano nuovo (vedi SH-1); i testi delle pause allineati.

#### SH-3 · D39: "N file nuovi nella cartella: aggiungili" non compare mai; la voce del menu c'e' sempre e spesso non fa niente
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale).
- **Flusso:** F85, un file aggiunto dal PC nella cartella dell'album.
- **Percorso:**
  - `SharedAlbumNotice.FilesAddedFromPc` (`core-albums/.../contract/SharedAlbums.kt:161`) non lo emette nessun codice di produzione: ne'
    `ReplicaSharedAlbums.refresh` ne' `AlbumFolderWatch.notices`; la pagina lo saprebbe mostrare (`feature-albums/.../page/AlbumPageController.kt:168`);
  - la voce ⋮ "Aggiungi i file della cartella" e' sempre fra le azioni di chi aggiunge (`feature-albums/.../contract/AlbumPolicy.kt:85`); con la lista
    vuota `adoptFolderFiles()` non fa e non dice niente (`AlbumPageController.kt:281-286`);
  - `FolderAdoptions.pending` (`core-albums/.../share/ShareProposals.kt:49-65`) non distingue i file aggiunti dal PC da quelli tolti con "Rimuovi
    dall'album" (che restano nella cartella, D39) e da quelli di un familiare non ancora pubblicati.
- **Atteso:** D39, F85.
- **Correzione:** il conteggio in `AlbumsGraph.scanNotices` (come `folderMoves`), senza le lapidi della replica; la voce solo con la lista non vuota.

#### SH-4 · D17/Q4: "Da condividere" degli smart condivisi non si riempie mai
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale).
- **Percorso:** `AlbumContent.toShare` e `Album.toShare` esistono (`core-albums/.../contract/AlbumModel.kt:137`, `:191`) e la pagina li mostrerebbe
  (`AlbumPageController.kt:147`, `:164-165`), ma nessun codice li calcola: `CatalogAlbums.build` non li passa
  (`core-albums/.../personal/CatalogAlbums.kt:110-129`). Non e' fra gli agganci rimasti di P13.
- **Atteso:** D17, Q4 ("le foto nuove vanno in «Da condividere»").
- **Correzione:** calcolarlo con il motore smart sull'album personale collegato dalla voce `shared` (gli elementi sicuri non ancora nell'album condiviso).

#### SH-5 · Dopo un riavvio la definizione e il livello iniziali si riaccodano
- **Gravita':** Minore. **Stato:** plausibile.
- **Flusso:** album nuovo con l'operazione ancora aperta; l'utente lo rinomina o cambia livello (o lo chiude); il processo muore e riparte.
- **Percorso:** `defined` e' in memoria (`core-albums/.../share/ShareSteps.kt:46`) e `ensureDefinition` riaccoda `Definition(ACTIVE, titolo del piano)` e
  `Level(livello del piano)` a ogni ripresa (`:70-96`); con un valore pubblicato diverso il pubblicatore li mette in coda e la voce del dispositivo torna
  indietro.
- **Correzione:** accodare la definizione solo se il pubblicatore non ha ancora l'album.

### 2.5 Spostamento e caricamento (SP)

#### SP-1 · I media che stanno solo sul telefono non si caricano mai nella cartella dell'album
- **Gravita':** Blocca (per E-SH1 e per la parte "dal telefono" di F79). **Stato:** confermato (verificatore principale e agente della condivisione).
- **Flusso:** foglio di condivisione con media del telefono non ancora sul NAS; conferma.
- **Percorso:**
  - `AlbumsWiring` costruisce `AlbumFolderUploads` con `backup.gate` (`app/.../albums/AlbumsWiring.kt:41`), cioe' `AppBackupGate`
    (`app/.../backup/BackupGraph.kt:187`);
  - `AppBackupGate.stateOf` risponde `NO_PRIVATE_AREA` per ogni share che non sia l'area privata (`app/.../backup/AppBackupGate.kt:58`), quindi anche per
    l'area comune;
  - `AlbumFolderUploads.start` lo rifiuta come `WRITES_BLOCKED` (`core-backup/.../upload/AlbumFolderUploads.kt:147-150`), `FolderAlbumUploader` lo passa
    come `TransferRefusal.WRITES_BLOCKED` (`app/.../albums/AlbumTransferPorts.kt:207`);
  - l'orchestratore lascia l'elemento `PLANNED` con l'attesa "scritture ferme" (`core-albums/.../share/ShareSteps.kt:153-158`, `:223`); la pagina dice
    "Riprendo da sola", la Diagnostica conta `SHARE_PAUSED_FROZEN` (`app/.../albums/AlbumsDiagnostics.kt:58`);
  - dopo 10 minuti la conferma scade e l'elemento finisce `FAILED(NOT_STARTED)` (`ShareSteps.kt:133-136`). Rifare il piano non serve (SH-1).
  - Il cancello giusto c'e': `fiveB.incomingGate` (`app/.../backup/FiveBGraph.kt:190`; `FiveBAdapters.kt:378-396`), che accetta l'area privata **e**
    l'area comune ed e' gia' quello del motore che eseguirebbe gli elementi (`FiveBGraph.kt:202`, `:218`, `:229`).
  - Nessun test di `:app` condivide un media solo sul telefono: `AlbumsIntegrationTest` e `AlbumsShareBenchAppTest` spostano solo foto dell'area
    privata; `AlbumFolderUploadsTest` usa un cancello finto.
- **Atteso:** D12, §2.5 passo 4, E-SH1, F79.
- **Correzione:** `AlbumFolderUploads(backup.store, journal, backup.fiveB.incomingGate, ...)`; un test d'integrazione con `AlbumsWiring` vero e un media solo
  sul telefono. Dopo la correzione il caricamento lo fa `IncomingUploadWorker` (`BackupWorkScheduling.incoming`, rete non a consumo, fermato solo da
  "Ferma" del backup), come "Salva sul NAS".

#### SP-2 · D9: "In arrivo" non compare mai
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale).
- **Percorso:** `AlbumContent.incoming` e `Album.incoming` (`core-albums/.../contract/AlbumModel.kt:138`, `:192`) non li valorizza nessuno; il banner
  `AlbumBanner.Incoming` (`feature-albums/.../page/AlbumPageController.kt:163`) non nasce. Si vede solo la striscia di avanzamento della condivisione,
  e solo nel processo che l'ha avviata.
- **Atteso:** §2.5 "Stato visibile", D9 ("solo il dispositivo che ha aggiunto un elemento lo vede «in arrivo»").
- **Correzione:** contare dal diario gli elementi miei dell'album in `TRANSFERRING`, `IN_COMMON` o `ENTRY_STAGED`.

### 2.6 Chiusura, "Riporta", E-SH16 ed E-SH20 (SC, SB, SN)

#### SC-1 · "Smetti di condividere" per il creatore: "Album non trovato", e l'album personale arriva al giro dell'indice
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente della condivisione).
- **Flusso:** il creatore, dalla pagina dell'album, "Smetti di condividere" con `REMOVE` o `KEEP_COPY`.
- **Percorso:**
  - `close` scrive la definizione `CLOSED` (`core-albums/.../shared/ReplicaSharedAlbums.kt:245-252`), e la vista locale toglie subito l'album dai
    visibili (`:98`: solo gli `ACTIVE`);
  - il creatore non riceve l'avviso `Closed` (`:106`, solo `!mine`), quindi la pagina passa a `Unavailable(NOT_FOUND)`
    (`feature-albums/.../page/AlbumPageController.kt:126-133`); la rotta esce da sola solo per `DELETE` (`feature-albums/.../page/AlbumPageRoute.kt:96`);
  - l'album personale del creatore (`keptCopies`, `ReplicaSharedAlbums.kt:149`) lo scrive solo `keepClosedCopies`, chiamato solo da `syncRound`
    (`app/.../albums/AlbumsGraph.kt:262`, `:272-283`), cioe' al giro dell'indice dopo. Nessun test di `:app` copre `keepClosedCopies`.
- **Atteso:** D31, §2.6 ("per il creatore diventa un album personale con gli stessi elementi").
- **Correzione:** dopo `CloseOutcome.Closed` chiamare `keepClosedCopies` e portare l'utente all'album personale (o all'elenco).

#### SC-2 · Una modifica accodata si pubblica solo col processo vivo, altrimenti al giro dell'indice
- **Gravita':** Minore. **Stato:** plausibile (chiudere o rinominare un album e uccidere l'app entro 5 minuti; guardare il tablet).
- **Percorso:** `NasAlbumPublisher.pending` riparte da 0 (`core-albums/.../shared/NasAlbumPublisher.kt:70-72`); `AlbumsGraph.watch` non pubblica la coda
  rimasta (`app/.../albums/AlbumsGraph.kt:121-133`) e `watchPending` reagisce solo a un valore maggiore di 0 (`:217-232`). Lo recupera `syncRound`.
- **Correzione:** in `watch()` leggere `publishStore.albums()` e, con una coda, `flushAll` e `shareWork()`.

#### SB-1 · "Riporta" dal dispositivo che non ha aggiunto: la lapide puo' andare sull'id locale
- **Gravita':** Minore. **Stato:** plausibile ("Rimuovi dall'album e riporta" dal tablet su un elemento aggiunto dal telefono dello stesso account).
- **Percorso:** `AlbumsLive` non lega `publishedId` (`app/.../albums/AlbumsLive.kt:144-166`), quindi vale il predefinito
  (`core-albums/.../share/SharePorts.kt:66`); `members()` contiene anche l'id locale che attesta, e `ShareSteps` puo' scrivere `ItemRemoved` su una chiave
  che non e' quella viva (`ShareSteps.kt:438-443`; la chiave giusta la sa `ReplicaSharedAlbums.published`, `:84-85`). Il file torna nell'area privata,
  la voce resta viva e non attestata.
- **Correzione:** `publishedId` legato a `ReplicaSharedAlbums.published`.

#### SN-1 · E-SH16: falso "La cartella e' cambiata" per un album fatto solo di riferimenti
- **Gravita':** Minore. **Stato:** plausibile (verificatore principale: la logica c'e').
- **Percorso:** `AlbumFolderWatch.scan` propone la cartella con la maggioranza dei file quando nessun elemento sta in quella dichiarata
  (`app/.../albums/AlbumFolderWatch.kt:47-62`). Un album fatto solo di media gia' nell'area comune (E-SH11, `REFERENCE`) non ha file nella sua cartella:
  l'avviso propone, per esempio, la cartella delle vacanze, e adottarla cambia dove vanno le aggiunte dopo.
- **Correzione:** contare solo gli elementi che erano nella cartella dichiarata quando sono stati aggiunti (spostamenti e caricamenti), non i
  riferimenti.

### 2.7 Cancelli, lavori e release (SG)

#### SG-1 · Il piano promette caricamenti e copie del guardiano che i cancelli del backup fermano
- **Gravita':** Sbagliata (release e Q7). **Stato:** confermato (agente della condivisione).
- **Flusso:** APK di release con un account di prova su `test-comune`; oppure, dopo il consenso Q7 (D30 spento), le share vere con D12 ancora acceso.
- **Percorso:** `SharePlanner` guarda solo `AlbumWriteGate` (D30, D38) e pianifica `UPLOAD` (`core-albums/.../share/SharePlanner.kt:60-61`, `:144-147`);
  il caricamento e il guardiano D28 passano dai cancelli del backup, che aggiungono D12 e R2 (`app/.../backup/FiveBAdapters.kt:387-388`; in release
  `backupBlocksTestAccounts`), e rispondono `TEST_ONLY`. Lo spostamento (MOVE_IN) passa; i media del telefono finiscono come in SP-1, e la "copia privata
  in arrivo" non arriva.
- **Atteso:** D12 (il foglio dice cosa succede), D28, F93.
- **Correzione:** il piano chiede anche il cancello degli arrivi e dichiara le sorgenti che non si possono caricare (fuori dal piano, con il motivo);
  oppure D30 e D12 si spengono insieme.

#### SG-2 · Il giro degli album ripete a vuoto fino a 8 minuti quando non puo' andare avanti
- **Gravita':** Minore. **Stato:** plausibile (verificatore principale: la logica c'e').
- **Percorso:** la tappa ripete finche' il giro dice "non finito" (`app/.../work/NasWork.kt:279-285`); `syncRound` dice "non finito" se
  `report.incomplete` (`app/.../albums/AlbumsGraph.kt:268`), e la replica lo e' senza registro, senza l'ora del NAS o con l'elenco di `albums/` fallito
  (`core-albums/.../shared/NasSharedAlbumSync.kt:95-110`). Ogni ripetizione rifa' anche un passo della condivisione e `scanNotices`, fino al tetto della
  tappa.
- **Correzione:** in quei casi "finito" (si riprova al giro dopo), oppure un'attesa fra le ripetizioni.

### 2.8 Utility e Doppioni (UT, DP)

#### DP-1 · I doppioni identici fra cartelle del NAS non si trovano mai
- **Gravita':** Blocca (per i doppioni del NAS; quelli del telefono funzionano). **Stato:** confermato (verificatore principale e agente dell'Utility).
- **Flusso:** lo stesso file in due cartelle dell'area privata, o nell'area privata e nell'area comune → scansione → Utility → Doppioni → Identici: niente.
- **Percorso:**
  - la scansione del NAS osserva solo dimensione e `qh1`, mai l'hash completo
    (`core-nasmedia/.../scan/DefaultNasScanner.kt:894`), e registra con `WhenUndecided.KEEP_SEPARATE` (`:901`, `:1006`);
  - due impronte senza hash completo danno `NeedsFullHash` (`core-model/.../domain/LinkingRules.kt:121`), che `keepSeparate` trasforma in un media nuovo
    (`core-database/.../MediaCatalog.kt:2231`);
  - il gruppo `PROBABLE_DUPLICATE` nasce solo con un candidato "dall'altra parte" (telefono e NAS, `MediaCatalog.kt:2067`); quando l'hash completo arriva
    (`recordFullHash`, `learnFullHash`, `:2498-2508`) si risolve solo quel gruppo, e i gemelli NAS-NAS restano due media;
  - la query dei Doppioni cerca versioni con **due posizioni NAS sulla stessa versione** (`core-database/.../dao/AlbumQueries.kt:202-204`).
  - Ne segue che un gruppo NAS nasce solo se il telefono fa da ponte (una foto del telefono che il backup unisce a una copia gia' sul NAS), o con la copia
    del guardiano (vedi DP-2). L'interruttore "Controlla di notte i video del NAS" (D27) mette in coda `FULL_HASH`, ma l'hash imparato non unisce
    niente. `docs/modello-dati.md` lo diceva aperto ("fra due copie dello stesso lato resta aperto (Doppioni, Fase 7)"); P8 legge soltanto.
  - Il test passa perche' i dati di prova danno ai file del NAS un SHA-256 e una seconda posizione sulla stessa versione
    (`core-library/src/test/.../CatalogIdenticalDuplicatesTest.kt:52-55`, `app/src/test/.../HiddenWorldBuilder.kt:109-128`).
- **Atteso:** D25 ("due file uguali nell'area privata, o nell'area privata e nell'area comune"), D27, §2.1.1, F76.
- **Correzione:** dopo `recordFullHash` (e nel giro di `FullHashNightly`) unire i media dello stesso account con stessa dimensione e stesso hash completo, come
  fa `DeferredFullHashes` per il telefono; oppure raggruppare per `full_hash` fra media diversi in `AlbumCatalog.duplicates`, con i gruppi "da
  verificare" per le coppie `qh1` senza hash.

#### DP-2 · La pulizia propone di eliminare la copia dell'area comune, anche quando e' un elemento di un album condiviso
- **Gravita':** Sbagliata. **Stato:** confermato (la regola; quanto spesso capiti va visto in F76).
- **Flusso:** Identici → "Pulizia guidata" → "Elimina".
- **Percorso:**
  - si tiene prima la copia privata verificata (`core-library/.../duplicates/IdenticalCleanup.kt:29-35`); si protegge solo `inSharedAlbum` (`:57-61`);
  - `inSharedAlbum` vale solo per i percorsi sotto `Album condivisi/` (`CatalogIdenticalDuplicates.kt:174`, `IdenticalCleanup.kt:85`), e
    `CatalogIdenticalDuplicates` non riceve l'appartenenza agli album (`app/.../utility/UtilityGraph.kt:61`; la riceve solo `CatalogSimilarShots`, `:71`);
  - un media gia' nell'area comune resta dov'e' quando entra in un album (D12, E-SH11): sta fuori da `Album condivisi/`;
  - la copia del guardiano (`Backup/Area comune/...`, D28) e' per costruzione la gemella privata di un file proprio dell'area comune.
  - Ne segue che il gruppo NAS piu' probabile propone, preselezionata, la copia che i familiari vedono, e la toglie dagli album che la citano (D9), senza
    l'avviso "Sparira' per tutti i familiari" (E-D8).
- **Atteso:** D25 ("mai una copia in un album condiviso"), E-SH17, D9, E-D8.
- **Correzione:** `AlbumMembership` (elementi attestati degli album condivisi) per `inSharedAlbum`; non proporre la copia comune quando la gemella e' quella
  del guardiano; l'avviso E-D8 nel foglio quando una copia dell'area comune e' fra quelle da eliminare.

#### DP-3 · La pagina Doppioni non si riallinea dopo la pulizia
- **Gravita':** Sbagliata. **Stato:** plausibile (guardare la pagina subito dopo "Elimina" in F76).
- **Percorso:** `rescan()` parte subito dopo l'avvio dell'operazione del NAS e dopo il dialogo del telefono
  (`feature-library/.../utility/DuplicatesController.kt:312-357`), ma il catalogo cambia solo con la scansione a operazione chiusa
  (`app/.../di/GalleryModule.kt:112`, `app/.../nas/NasOrganizer.kt:441`). Restano gli stessi gruppi; un secondo "Elimina" risponde `CLEANUP_REJECTED`.
- **Correzione:** rileggere quando le operazioni attive calano o dopo la scansione; intanto togliere dalla vista le copie accettate.

#### DP-4 · I Doppioni simili sono vuoti fino alla prima notte in carica
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale).
- **Percorso:**
  - le firme degli scatti le fa solo `UtilityGraph.nightly` (`app/.../utility/UtilityGraph.kt:108-111`), chiamato solo dalla manutenzione notturna
    (`app/.../work/GalleryWork.kt:164`), periodica a 24 ore e in carica (`:56-61`), come ultimo passo dopo gli altri;
  - `ShotVectorSource` non e' legato (P13, aggancio rimasto 3): la scheda dipende solo dalle firme visive;
  - la stringa promette "la ricerca per contenuto" (`feature-library/src/main/res/values/strings_utility_duplicates.xml:84`).
- **Atteso:** §2.1.1 ("Quando: `LibraryMaintenanceWorker`, a lotti per giorno"), D25, F77. La notte e' prevista, ma una raffica fatta oggi compare dopo
  una notte in carica, e prima la scheda non dice perche' e' vuota.
- **Correzione:** firmare anche all'apertura della scheda con un budget breve; dire "Le foto di oggi si confrontano stanotte"; correggere la stringa.

#### DP-5 · L'interruttore "Controlla di notte i video del NAS": errore inghiottito e ritiro quando non e' pronto
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** `set` lancia finche' i dati personali non sono agganciati (`app/.../utility/UtilityPorts.kt:171-173`) e
  `DuplicatesController.kt:369-380` lo inghiotte: il tasto non fa e non dice niente. `fullHashOn()` (`app/.../di/UtilityModule.kt:65`) non distingue
  "spento" da "non pronto": la notte, in quel caso, `applyFullHash(false)` ritira le righe `FULL_HASH` chieste dall'utente
  (`core-library/.../duplicates/FullHashNightly.kt:92-112`).
- **Correzione:** `fullHashOn` nullabile (passo saltato se non pronto); un avviso se `set` fallisce.

#### DP-6 · Il titolo del foglio di eliminazione conta le copie come "foto"
- **Gravita':** Minore. **Stato:** confermato (agente dell'Utility).
- **Percorso:** `feature-library/.../utility/CleanupSheetUi.kt:41-49` passa `plan.copies` a "Eliminare %1$d foto?": 4 scatti con 3 copie fanno "12 foto",
  e "foto" anche per i video; manca "(X GB)" (§2.1.1).

#### DP-7 · Una rotazione durante "Elimina" perde la parte del telefono
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** `confirm()` gira nello scope della composizione (`feature-library/.../utility/DuplicatesScreen.kt:148-150`): ricreata l'attivita' mentre il
  NAS lavora, la richiesta del dialogo di sistema si perde e il foglio resta aperto; il secondo tocco riceve `REJECTED` dal NAS e le copie del telefono non
  vanno nel cestino (`DuplicatesController.kt:327-331`).
- **Correzione:** `confirm` nello scope del ViewModel.

#### UT-1 · I Doppioni identici ignorano l'accesso parziale alle foto
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** `CatalogIdenticalDuplicates.kt:88-96` filtra con `VisibleMedia.visible`, che legge solo il database; il limite dell'accesso parziale vale
  per l'istantanea (`app/.../gallery/PartialAccessTimeline.kt:48-50`), cioe' per i simili.
- **Correzione:** filtrare anche con l'insieme concesso dall'accesso parziale.

### 2.9 Eliminati di recente (ED)

#### ED-1 · Cartelle NAS → Cestino e' una seconda porta del cestino del NAS, senza cancello
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente dell'Utility).
- **Flusso:** Raccolte → Cartelle NAS → Cestino (o Backup → "Apri il cestino del NAS").
- **Percorso:** la voce resta in Cartelle NAS (`app/.../navigation/CollectionsSectionsWiring.kt:126`; `feature-nas/.../hub/NasHub.kt:84`, `:133`) e in
  Backup (`app/.../backup/BackupUi.kt:370`); la pagina (`feature-nas/.../trash/NasTrashScreen.kt`) mostra nome, cartella e autore di ogni voce, con
  "Ripristina" ed "Elimina subito", senza `HiddenAccessGate` (nessun riferimento in `feature-nas`) e senza distinguere i nascosti. Nell'Utility la
  stessa lista sta dietro il cancello.
- **Atteso:** D26 ("una pagina", "dietro lo stesso cancello dei Nascosti"), D22, D23, E-H1.
- **Correzione:** le due voci aprono `RecentlyDeletedDestination`; oppure la pagina del NAS dietro lo stesso cancello e con i nascosti senza nome.

#### ED-2 · "Ripristina" su righe non ripristinabili non fa e non dice niente
- **Gravita':** Minore. **Stato:** confermato (agente dell'Utility).
- **Percorso:** le righe senza record valido, non scrivibili o col NAS spento si selezionano (`feature-library/.../trash/NasTrashRows.kt:264`);
  "Ripristina N" (`RecentlyDeletedScreen.kt:146-151`) chiama `restoreNas()`, che torna null (`RecentlyDeletedController.kt:342-351`). In una selezione
  mista la parte del NAS cade in silenzio.
- **Correzione:** righe non selezionabili, oppure `TrashNotice.REJECTED`.

### 2.10 Nascosti (NA)

#### NA-1 · Cartelle NAS → Spazio NAS elenca per nome i file nascosti
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale e agente dell'Utility).
- **Percorso:** `largest` (`app/.../nas/NasOrganizer.kt:372`) → `CatalogLargestFiles.of` (`:926-945`, nessun filtro sui nascosti) → Spazio NAS
  (`feature-nas/.../space/NasSpaceScreen.kt:312-317`, nome e cartella). La superficie non e' nel registro `HiddenSurfaces`: la riga `nas-folders` copre
  solo l'elenco delle cartelle.
- **Atteso:** D22, D23 ("in Cartelle NAS i file nascosti diventano una riga senza nomi"), F73.
- **Correzione:** filtrare con le posizioni nascoste (`CatalogHidden`) e registrare la superficie.

#### NA-2 · Il widget di prova (solo debug) si svuota a processo freddo
- **Gravita':** Minore. **Stato:** plausibile (F73).
- **Percorso:** `app/src/debug/.../widget/FieldProbeHiddenWidgetProvider.kt` disegna subito; a processo freddo l'istantanea e' ancora null,
  `RotatingWidgetFeed.pick` da' vuoto e `updateAppWidget` sovrascrive con celle vuote fino al giro dopo (30 minuti). F73 passerebbe "a vuoto". In release
  il widget non c'e'.
- **Correzione:** aspettare l'istantanea (con un tetto) e non sovrascrivere con il vuoto.

### 2.11 Raccolte (RC)

#### RC-1 · "Tutti gli album" c'e' solo oltre 6 album; gli smart oltre il sesto non si aprono
- **Gravita':** Sbagliata. **Stato:** confermato (verificatore principale).
- **Flusso:**
  - con 6 album manuali e condivisi o meno, la voce non c'e', e con lei la pagina dell'elenco, l'unico posto con l'ordine dell'elenco ("Personalizzato",
    "Sposta su/giu'", `albums_sort`), gli album lasciati ("Mostra di nuovo", E-SH21) e gli avvisi di chiusura (Q5)
    (`feature-albums/.../list/AlbumListScreen.kt:139`, `:156`, `:177`);
  - la sezione "Album smart" mostra i primi 6 senza una voce "altri": dal settimo in poi non si aprono da nessuna parte.
- **Percorso:** `feature-albums/.../sections/AlbumsSections.kt:154-163` (`ALL_ALBUMS` solo se `all.size > FIRST`), `:170-171` (`smart.take(FIRST)`);
  `AlbumListDestination` si apre solo da quella voce (`feature-albums/.../nav/AlbumsNavigation.kt:44`, `:63`; "Nuovo album" lo intercetta `:app`, P13-10).
- **Atteso:** §2.7 ("«Tutti gli album» oltre i primi 6"), D21, E-SH21; F72 con 5 album non arriva a "Sposta su/giu'".
- **Correzione:** "Tutti gli album" sempre (o anche con smart oltre 6, album lasciati o avvisi); una voce "Tutti gli album smart".

#### RC-2 · Le scelte fatte prima del registro e il passaggio al registro
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** ordine e visibilita' passano dalla copia locale al registro appena c'e' (`app/.../navigation/CollectionsSectionsWiring.kt:164`, `:167`);
  prima della consegna (`:194-211`) l'ordine scelto puo' tornare per un attimo quello predefinito; una consegna fallita non si ritenta fino alla scelta
  dopo.
- **Atteso:** P13-14, L104.
- **Correzione:** tenere la copia locale finche' la consegna non riesce, e ritentarla.

### 2.12 Diagnostica e azzeramento (DG, AZ)

#### DG-1 · `SMART_NO_SEARCH` non si emette mai
- **Gravita':** Minore. **Stato:** confermato (verificatore principale).
- **Percorso:** Hilt da' sempre un motore (`app/.../di/AiModule.kt:69`; `AlbumsModule.kt:62` non e' nullabile), quindi
  `contentSearch = i.search != null` e' sempre vero (`app/.../albums/AlbumsLive.kt:204`) e il codice non scatta (`AlbumsDiagnostics.kt:69`). L'editor
  invece lo dice (`searchUsed`).
- **Atteso:** §2.8, D15.
- **Correzione:** `contentSearch` come funzione di `readiness is Ready`.

#### DG-2 · I lavori nuovi non lasciano traccia in Diagnostica
- **Gravita':** Sbagliata. **Stato:** confermato (agente dell'Utility).
- **Percorso:** `UtilityGraph.lastNightly` e `lastSigning` si scrivono e non li legge nessuno (`app/.../utility/UtilityGraph.kt:54-58`); non c'e' un
  contributo dell'Utility. `ShareWorker` (`app/.../work/AlbumWork.kt:58-69`) e la manutenzione notturna con viaggi, hash completi e firme
  (`GalleryWork.kt:131-166`) non hanno un `DiagJob` ne' `StopReports`: un errore o un budget finito non arriva a "Ultimi esiti ed errori". P13 scrive
  per F76 "Diagnostica: righe FULL_HASH in coda e ritirate", che oggi non si vedono.
- **Atteso:** §2.8, D37 (solo numeri), F76.
- **Correzione:** un `DiagJob` per la condivisione e uno per la manutenzione; una voce dell'Utility con i numeri (in coda, ritirati, firmati, da firmare).

#### DG-3 · I contatori degli album sono volatili
- **Gravita':** Minore. **Stato:** plausibile.
- **Percorso:** un rifiuto di contributo si conta solo nel giro in cui nasce (`core-albums/.../shared/NasSharedAlbumSync.kt:207`, `:275-278`) e
  `lastSync` si sovrascrive a ogni giro (`app/.../albums/AlbumsGraph.kt:260`): al giro dopo la voce sparisce. Nessun "conteggio per motivo" (§2.8).
  `TRIP_NO_GPS` esiste solo dopo la prima notte e si perde con il processo. `Phase7DiagCatalogTest` prova il catalogo, non le emissioni.
- **Effetto:** per F84 l'esportazione va fatta subito dopo un giro.
- **Correzione:** contatori cumulativi con il motivo, ricavati dai segni dei rifiuti nella replica.

**Azzeramento (AZ):** nessuna voce. Vedi §5.

---

## 3. I fake che divergono dal vero

| Fake | Dove si usa | Divergenza |
|---|---|---|
| `InMemoryAlbums` (`core-albums/.../testing/InMemoryAlbums.kt:109-113`, `:151-157`) | test di `feature-albums` (rotte, pagina, sezioni, "Aggiungi ad album") | I membri di uno smart si calcolano in modo sincrono a ogni `refresh()`; il vero li scrive dopo e non rinfresca (AS-1, AS-2) |
| `InMemoryAlbums.status` (`:87`) | `AlbumPageControllerTest` | Un test lo mette a `Recomputing`; il vero non lo produce mai (`CatalogAlbums.kt:95`) |
| `InMemoryAlbums.add` (`:238-248`) | stessi test | Conta come aggiunto un media che il catalogo non conosce; il vero lo scarta e risponde `Added(0, 0)`, che la ricezione tratta come "fatto" (`IncomingAlbumPromises.kt:157`) |
| `CatalogAlbumsContractTest` (`core-albums/src/test/...:81`, `:101-121`) | contratto del `CatalogAlbums` vero | Chiama `onCatalogChanged()` dopo il motore; `:app` no (AS-1) |
| `InMemoryTrips` (`InMemoryTrips.kt:78-96`, `:181-194`) | `TripsController`, sezioni | `detect` lo chiama il test; gli scartati sono in memoria; `accept` non guarda gli scartati (il vero: `CatalogTrips.kt:176-189`) |
| `FakeSmartAlbumEngine` (`AlbumFakes.kt:149-161`) | `SmartEditorControllerTest` | Anteprima e tempi li scrive il test; un errore del motore non e' mai provato (AS-7) |
| `NoSmartContentSearch` in `RealSmartStack.kt:37` e `AlbumsTestRig.kt:105` | sonde dei Nascosti, strumento di P12 | `EngineContentSearch` sul motore vero di Hilt non gira mai nei test di `app` (AS-4, DG-1) |
| `FakeShareTransfers` (`core-albums/.../testing/ShareFakes.kt:128-200`) | `InMemoryShareRunner`, contratto, orchestratore | `upload` e `move` senza cancello (SP-1 non puo' emergere), nessun controllo dell'eta' della conferma, `status` finisce sempre `ARRIVED` |
| `FakeAlbumPublisher` (`ShareFakes.kt:36-120`) | idem | `stage` non lancia mai (il vero si': voce non ammessa, famiglia non pronta, livello senza ora del NAS); `flush` non guarda `canWrite`; `pending` non riparte da 0 (SC-2) |
| `FakeSharedWorld` (`FakeSharedWorld.kt:100-107`, `:157`) | lettore finto | "Aggiunto quando era permesso" sempre vero (il vero guarda il livello all'ora `addedAt`, `AlbumRules.kt:130-132`); livello iniziale `ADD` invece di `VIEW` prima del primo cambio (`AlbumRules.kt:45`) |
| `InMemoryShareDiary.opByPlan` (`InMemoryShareDiary.kt:49`) | diario finto | Stesso difetto del vero, senza l'indice unico: SH-1 non emerge |
| `FakeAlbumLibrary.attest` (`FakeAlbumLibrary.kt:172`) | attestazione D9 | Non chiede il `qh1` ne' la scansione dell'area comune |
| `ReferenceShareSteps` (`ReferenceShareSteps.kt:123-129`) | `InMemoryShareRunner` | Lapide con l'id dell'elemento; manca `publishedId` (SB-1) |
| `FakeAlbumFamily.backupFolderName` (`AlbumFakes.kt:69`) | "Riporta" senza cartella d'origine | "Backup/questo-telefono"; il vero rende "Backup" se la cartella del dispositivo non e' scelta (`AppAlbumPorts.kt:60`) |
| `FakeAlbumWriteGate` (`AlbumFakes.kt:76-79`) | cancelli | Niente `FENCE`, `NO_NAS`, ne' legame all'account |
| `CatalogIdenticalDuplicatesTest.kt:52-55`, `HiddenWorldBuilder.kt:109-128` | Identici, registro dei Nascosti | File del NAS con SHA-256 e seconda posizione sulla stessa versione; la scansione vera non li produce (DP-1) |
| `FakeIdenticalDuplicates`, `FakeSimilarShots`, `sampleIdenticalProposals` (`UtilityFakes.kt:72`, `:128`, `:200`) | controllore, schermate, screenshot | Gruppi NAS e scatti simili pronti al primo `scan`; nell'app vera non ci sono (DP-1, DP-4) |
| `FakeNasTrash` (`TrashFakes.kt:23-80`) | Eliminati di recente | Sempre `Ready`, ripristino ed eliminazione sincroni; il vero parte `Offline`, si rilegge con `refresh()` e rifiuta un piano vuoto |
| `FakeCleanupNas` (`UtilityFakes.kt:238-246`) | `DuplicatesControllerTest` | Risponde sempre l'esito impostato; il vero puo' rifiutare un piano vuoto o con tocchi insufficienti |
| `FakeFullHashSwitch` (`UtilityFakes.kt:248-258`) | Doppioni | `set` salva sempre; il vero lancia finche' i dati personali mancano (DP-5) |
| `CleanupOverOperationsTest`, `NasTrashOverOperationsTest` | adattatori della pulizia e del cestino | NAS finto con timbri del kit: non provano che il timbro del catalogo (`modified_at`, `file_id`) coincida con quello letto sul NAS |
| `MemoryCollectionsLayoutStore` e `InMemoryPersonalStore` in `CollectionsWiringTest` | Raccolte | Il deposito vero consegna dopo (`deliver`) sopra `LedgerPersonalStore`; il passaggio e' provato una volta (RC-2) |

---

## 4. Flussi provati solo con fake: da mettere per primi nelle prove sul campo (F72–F93)

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere. Nessun test di `app/src/test` monta `AlbumsGraph` su album personali, smart o
viaggi: `AlbumsIntegrationTest` e il banco coprono solo gli album condivisi, con media dell'area privata.

1. **F79 completa, con i 3 media solo sul telefono** (SP-1): il caricamento non parte, la pagina dice "Riprendo da sola", la Diagnostica dice
   `SHARE_PAUSED_FROZEN`, dopo 10 minuti `NOT_STARTED`; poi lo stesso "Aggiungi" (SH-1) e la striscia "da riprovare" (SH-2). Il guardiano D28 per i media
   gia' assenti dal telefono e' provato solo sulla JVM.
2. **F76, Doppioni identici:**
   - lo stesso file in due cartelle di `test` e in `test-comune`: nessun gruppo (DP-1);
   - la coppia "file dell'area comune + copia del guardiano", e un elemento d'album per riferimento: proposta la copia comune (DP-2);
   - la pagina subito dopo "Elimina" (DP-3); l'interruttore notturno senza tracce in Diagnostica (DG-2);
   - il ripristino dagli Eliminati di recente con il catalogo vero (E-U5: il media torna nella Libreria e negli album).
3. **F87, album smart senza uscire dall'app:** crearne uno e guardare elenco e pagina (AS-1, AS-2, "Nuove: N"); cambiare le regole; "Scongela" dopo
   un'aggiunta a mano (AS-10); "Crea album" da un viaggio (VG-2, AS-8); due foto a 8 s con uno smart sul giorno (AS-3); un riavvio a meta' (AS-5).
4. **F89 e i Viaggi su un'installazione pulita, prima della notte** (VG-1).
5. **F83 e F86, chiusura:** "Smetti di condividere" dal creatore (SC-1); `KEEP_COPY` sul tablet; "Riporta" dal tablet su un elemento del telefono (SB-1);
   chiudere o rinominare e uccidere l'app entro 5 minuti (SC-2); rinominare o cambiare livello con un'operazione aperta e riavviare (SH-5).
6. **F73 e F78 sulle superfici dimenticate:** Cartelle NAS → Cestino e Spazio NAS con un nascosto (ED-1, NA-1); "Ripristina" con righe non ripristinabili o
   il NAS spento (ED-2); il widget a processo freddo (NA-2); l'avviso D-3 su Samsung.
7. **F85 e F82:** un file aggiunto dal PC (SH-3); un album di soli riferimenti (SN-1); la cartella rinominata dal PC (E-SH16, provato solo sulla JVM).
8. **F77, Doppioni simili:** forzare prima la manutenzione notturna (DP-4); poi `Sdup`, il titolo del foglio (DP-6), una rotazione durante "Elimina" (DP-7).
9. **F72 e F90, Raccolte e album:** 5 album, poi 8, poi 8 smart (RC-1); "Aggiungi ad album" dalla ricezione ad app chiusa (AA-1); il selettore con 1.000
   foto e la morte del processo (AA-3); il tablet in orizzontale (voci degli album dal pannello); le scelte delle Raccolte prima del registro (RC-2).
10. **F81:** il tetto di spazio tenuto oltre 10 minuti, poi tolto (SH-2: oggi l'elemento muore `NOT_STARTED` invece di riprendere).
11. **F84 e F92:** esportare la Diagnostica subito dopo un giro (DG-3); un registro o un NAS assenti durante il giro dell'indice (SG-2).
12. **F88 con la ricerca per contenuto vera** (AS-4, AS-6, DG-1): mai provata in `app`.
13. **F93 e la release:** un account di prova con l'APK di release (SG-1); con Q7, ricordare che D12 ferma ancora caricamenti e guardiano.

---

## 5. Flussi verificati da capo a fondo

Una riga ciascuno: il percorso e' completo nel codice e i dati arrivano alla schermata (a processo caldo, in debug, sulle share di prova, salvo dove e'
detto).

**Porte, grafo e lavori**
- **`AlbumsGraph`:** uno per processo (`AlbumsModule`, `src/main`: debug e release), monta `AlbumsLive` quando galleria e dati personali sono pronti, anche
  senza NAS raggiungibile; le facciate rispondono come i null-object prima del montaggio.
- **Lavori:** il giro degli album dentro `IndexSyncWorker` (`NasStages.albums`), `ShareWorker` avviato dal foglio, da `watch()` e da `syncRound` con
  operazioni aperte, riaccodato finche' ne restano (`KEEP` e `APPEND_OR_REPLACE`), tutto dentro `runIfActive`.
- **Porta di completamento unica:** `CatalogMoveCompletionSink` sopra `attachMoved`, chiamata prima che la sorgente vada nel cestino.

**Album personali e "Aggiungi ad album"**
- **"Nuovo album" dalle Raccolte:** `CollectionEntryRouting` → foglio → `AppAlbums.create` → `AlbumEditor.create` → proiezione e `refresh` → pagina.
- **"Aggiungi ad album" dalla selezione e dal viewer ⋮:** `AlbumRequests` → foglio sopra la schermata → `AddToAlbumController` (filtro `VisibleMedia`)
  → `AlbumEditor.add` → pagina aggiornata subito; i viewer di Libreria, Cerca, Raccolte e Cartelle NAS hanno la voce.
- **"Aggiungi foto":** pagina → `pickMedia` → `MediaPickerRoute` (la Libreria, gia' in selezione, ordine della Libreria) → `add`.
- **Ordine, copertina, rinomina, elimina:** `setSort`, `reorder`, `setCover` (da "Scegli copertina" e dal viewer aperto dall'album), `rename`, `delete`
  arrivano a proiezione e `refresh`; la copertina automatica sostituisce una nascosta o tolta.
- **Un altro dispositivo dello stesso account:** `CatalogAlbums` segue `effectiveEntries` e proietta solo gli album cambiati (sulle share di prova: vedi §6).
- **Ricezione → promessa su disco → album personale** a processo caldo; verso un condiviso si apre il foglio (P13-12).

**Album smart e viaggi**
- **"Togli dall'album", "Accetta", "Escludi", "Congela":** proiezione e `refresh` subito, senza aspettare il motore.
- **Correzione di data:** il catalogo segna il cambio di `localDay` come strutturale (`MediaCatalog.kt:2796`), arriva in `catalog.changes`, e
  `AlbumsGraph.onCatalogChanged` rivaluta gli smart che avevano il media e quelli del giorno nuovo (con AS-1 e AS-3).
- **"Non mi interessa":** `trip_dismissed` nei dati personali, derivato da `effectiveEntries` su ogni dispositivo dell'account.

**Condivisione**
- **Piano → foglio → conferma → diario → spostamento:** `ShareOrchestrator.start` → `RoomShareDiary` → `OpsAlbumMover` → `NasOperations.plan/start` con la
  conferma emessa dal foglio → completamento sullo stesso `MediaId` → `IN_COMMON` → voce `item:` → testa pubblicata (30 s, 5 minuti) → `DONE`; coperto
  anche da `AlbumsIntegrationTest` e dal banco a 8 giri.
- **Foglio:** doppio tocco, piano che scade in 10 minuti e si rifa', nascosti lasciati fuori (Q3), livello "Gli altri aggiungono" (Q2), dichiarazione dei
  permessi.
- **Recinzione D30:** `AppAlbumWriteGate` e `FlagsAlbumReadGate`; "Arriva dopo le prove" sulle share vere in debug e release; `ShareFence` solo in debug.
- **Il familiare:** `IndexSyncWorker` → `syncRound` → `NasSharedAlbumSync` → replica → fusione con i permessi → attestazione sul suo catalogo (D9) → elenco e
  pagina; le azioni della pagina e del viewer vengono da `AlbumPolicy`, la stessa tabella del §2.4; "Non mostrare piu'" scrive `shared_album_left`.
- **Q5:** foglio senza preselezione e conferma spenta finche' non si sceglie; `afterClose` firmato nella definizione; per gli altri l'avviso `Closed` o la
  copia personale (al loro giro dell'indice).
- **"Riporta":** `shared_origin` scritto prima di ogni spostamento verso l'area comune; destinazione nel diario all'avvio; lapide solo con l'album attivo.
- **E-SH16:** scansione solo del catalogo, `useNewFolder` scrive solo la definizione (nessun file mosso). **E-SH20:** striscia e "Ripristina gli elementi",
  dietro i cancelli.

**Utility, Eliminati di recente, Nascosti**
- **Screenshot, Video, WhatsApp, Modificate:** voce delle Raccolte → `utilityGraph` → `UtilityFilterRoute` → vista filtrata della Libreria; i conteggi dalla
  stessa fonte, senza nascosti; il bit `EDITED` lo accende il catalogo quando la versione corrente non e' l'originale.
- **Pulizia verso il NAS:** foglio → `OperationsCleanupNas` → `NasOperations.plan(Trash)` con il timbro del catalogo → un tocco; il telefono solo con
  `createTrashRequest`.
- **Interruttore notturno:** registro personale → `applyFullHash` → `FULL_HASH` in coda, consumato dal giro di sicurezza del backup (ma vedi DP-1).
- **Eliminati di recente:** cestino di Android e cestini del NAS, giorni rimasti, "Elimina subito" a due tocchi (`PURGE_NOW`), stesso `HiddenAccessGate`,
  avviso D-3 da `MainActivity`.
- **Nascosti:** Impostazioni → Privacy → Nascosti apre la pagina col cancello anche con la sezione nascosta; l'avviso E-H2 dalla selezione, dalle pagine
  delle Raccolte e dell'Utility e dal viewer (anche aperto da un album); tutte le superfici del registro leggono `gallery.visibleMedia` e
  `gallery.hiddenMask`; la riga "N elementi nascosti" in Cartelle NAS fallisce chiusa; il widget rifiltra col database.

**Raccolte, Diagnostica, azzeramento**
- **Raccolte:** un fornitore per sezione, "Modifica Raccolte" con trascinamento e azioni di TalkBack, ordine e visibilita' nei dati personali, Album non
  nascondibile, sezione nuova in coda, "Cartelle NAS"; sul tablet elenco e dettaglio oltre 840 dp, con le voci degli album aperte come rotte (P13-9).
- **Diagnostica:** 11 dei 12 codici del §2.8 hanno una sorgente in produzione (`SHARE_*` dal diario, `ALBUM_*` dalla replica e da `lastSync`,
  `SMART_TOO_BROAD`, `ALBUM_PROJECTION_PENDING`, `TRIP_NO_GPS`); manca `SMART_NO_SEARCH` (DG-1).
- **Azzeramento:** `albumsStopper` per primo, poi AI, galleria e file; `noBackupFilesDir/albums/` (registri, promesse, piani, segni) fra i file tolti;
  le tabelle v6 con il database; `ShareWorker` e la manutenzione fermati da `runIfActive`; dopo l'azzeramento `reconfigureAfterWipe` fa nascere grafi
  nuovi, quindi gli album rimontano con il nuovo account.

---

## 6. Note

**Scartati dopo la verifica:**
- **`incoming` e `afterCatalog` mai assegnati.** No: li assegna il fornitore di `AlbumsGraph` (`AlbumsModule.kt:83-84`) prima che `MyCloudGalleryApp` li legga.
- **`search` null in `AlbumsWiring`.** No: Hilt da' sempre il motore (resta DG-1).
- **`ShareWorker` che non si riaccoda.** No: lo richiedono `watch()`, `syncRound` e `shareStarted`.
- **`start()` delle operazioni della 4B a vuoto in primo piano.** No: `NasOrganizer` risponde `Started` alla nascita dell'operazione e chiede il lavoro lungo.
- **Nessuno accende `CellFlags.EDITED`; `FULL_HASH` senza consumatore.** No: li accende il catalogo, li consuma il giro di sicurezza.
- **Il widget di prova mostra un nascosto.** No: `RotatingWidgetFeed` rifiltra col database.
- **Gli album non rimontano dopo l'azzeramento.** No: il grafo resta fermo, ma `reconfigureAfterWipe` ne fa nascere uno nuovo.
- **Il cambio di data non arriva agli smart.** Arriva (resta AS-1 per la schermata).
- **R8.** Nessun riflesso nuovo oltre alle rotte `@Serializable` e a Hilt; `-dontobfuscate`; nessun pacchetto `testing` in `app/src/main`.

**Scelte e non difetti:**
- **I dati personali sulle share vere restano locali (F4-D31).** Album personali, ordine, copertine, `albums_sort`, `collections_*` e `trip_dismissed` si
  sincronizzano solo fra le share di prova: con `indexPublishTestSharesOnly` il giro dei dati personali si ferma a `LocalOnly`
  (`core-personal/.../sync/NasPersonalSync.kt:115-118`). E' la recinzione delle Fasi 4–6, vale in debug e release; F72 e F90 si fanno con `test`. Nessuna
  schermata degli album lo dice (c'e' solo "Indice condiviso in prova" nella Libreria): conviene dirlo quando la recinzione si spegnera'.
- **"Due colonne" sul tablet** e' elenco+dettaglio oltre 840 dp, come dichiarato da P11 (punto 5 e nota finale della sezione).
- **Gli agganci rimasti di P13** dichiarati e non visibili come difetti nuovi: "Aggiunta da …" mostra l'account del NAS (`AlbumPeople` senza legame, Q6);
  `DuplicatesNames` (E-U1 dice "un altro familiare"); `AwayFromHome` non in Diagnostica; il passo D30 di `check.ps1` senza il bytecode del cancello;
  "Mostra i Nascosti nelle Raccolte" sotto Impostazioni (la sezione si rimostra da "Modifica Raccolte"); E-SH20 per un album rimasto senza
  definizione (nessuna riga che lo offra). Visibili e riportati come voci: `ShotVectorSource` (DP-4), la cartella del backup (AS-9), l'album gia' scelto
  della ricezione (AA-1).
- **Il guardiano D28 "subito"** gira con le regole di sottofondo e puo' aspettare la finestra notturna: e' nel progetto ("con le sue regole").
- **"N altri familiari"** nel foglio di chiusura conta solo chi ha contribuito: puo' dire 0. E' un testo, non un percorso.

**Non letti in dettaglio**, quindi non dati per verificati:
- l'interno del codec `album` di `core-trust`, di `AlbumFusion` e di `AlbumPermissions` (fusione, retrodatazioni, revoche): li coprono i test ostili e di
  proprieta' di P2 e la revisione di sicurezza;
- la qualita' delle regole degli smart e dei viaggi (soglie, raggi, finestre): e' F87–F89;
- `NasOpsEngine` e `SafeUploadEngine` passo per passo: li coprono il banco di P5 e quelli della 4B e della 5B;
- i layout, TalkBack e gli screenshot delle schermate della Fase 7.

---

## 7. Stato di ogni voce (27/10, revisione E)

Tutte e 46 corrette. Il pacchetto fra le prime due colonne: **A** fiducia e replica (nessuna voce di questa verifica), **B** flusso della condivisione, **C** Utility, Doppioni, cestini e Nascosti in Cartelle NAS, **D** smart, viaggi, Raccolte, ricezione e Diagnostica, **E** collegamenti e confini. I limiti (L...) sono in `docs/sicurezza.md` §6.

| Voce | Pacchetto | Correzione, prove, limiti |
|---|---|---|
| AA-1 | D | `IncomingAlbums.choices()` e' un flusso: la ricezione segue gli album dopo la prima lettura (`IncomingAlbumsTest`). L172 |
| AA-2 | D | "Oppure un album nuovo" dalla scelta dell'album (`IncomingAlbumsTest`). L172 |
| AA-3 | D | `PickRelay`: la scelta persa diventa "Aggiungi ad album" con l'album gia' scelto, o "La scelta non vale piu'" (`PickRelayTest`). L173 |
| AP-1 | D | `AlbumsStatus.Loading` finche' gli album non sono montati (`AppAlbumsStatusTest`) |
| AS-1 | D | `SmartUpkeep`/`CatalogAlbums` come `SmartWorkSink`: ogni fine di calcolo rinfresca elenco e pagine (`SmartAlbumsAppTest`, `SmartUpkeepTest`) |
| AS-2 | D | `Recomputing(0, 0)` per uno smart senza righe; il segno "visto" dopo il primo calcolo (`SmartAlbumsAppTest`) |
| AS-3 | D | `ChangeBatcher`: cio' che arriva durante un giro fa partire il giro dopo (`BatchingTest`) |
| AS-4 | D | `readyChanges` della ricerca per contenuto rivaluta gli smart con testo o esempi (`SmartUpkeepTest`) |
| AS-5 | D | `resumeAll` al montaggio e `drain` per i calcoli a meta' e i giorni saltati (`SmartUpkeepTest`). L175 |
| AS-6 | D | Gli smart senza periodo si rifanno a catalogo fermo da 2 minuti (`SmartUpkeepTest`). L175 |
| AS-7 | D | `propose` e `preview` dell'editor in `try/catch` (`SmartReviewTest`) |
| AS-8 | D | Una sola pagina dopo "Salva le regole"; il selettore delle foto d'esempio senza album (`SavedRulesNavigationTest`) |
| AS-9 | D | Cartella del backup letta a ogni uso; `reconcileNow()` al posto dei 2 secondi (`SmartReviewTest`) |
| AS-10 | D | Da congelato "Aggiungi" scrive anche `accept:`; "Scongela" tiene le aggiunte a mano (`SmartReviewTest`) |
| VG-1 | D | `TripUpkeep.poke()` dopo il montaggio e le novita'; `TripsState.Searching` (`TripUpkeepTest`) |
| VG-2 | D | `TripId.startKey`, creazione e `markAccepted` in `NonCancellable`, `NotFound` detto (`TripsReviewTest`) |
| SH-1 | B | Il tentativo nell'impronta del piano (`RevisioneBShareTest.sh1...`) |
| SH-2 | B, E | Operazioni chiuse lette dal diario, motivo, tocco "Riprova" che fa un piano nuovo (`RevisioneBShareTest.sh2...`, `RevisioneBAlbumPageTest.sh2...`); E: `RoomShareDiary.closedOps` (prima valeva l'elenco vuoto di serie: dopo la morte del processo la striscia spariva) e un'operazione chiusa con un elemento fermo non e' piu' "in corso" (`RevisioneEConfiniAppTest.sh2...`, `RevisioneEShareTest`) |
| SH-3 | B | `FolderAdoptions` con la storia dell'album, `AlbumFolderWatch.notices` (`RevisioneBShareTest.sh3...`, `RevisioneBCondivisioneAppTest.sh3...`). L151 |
| SH-4 | B | `toShare` di uno smart condiviso (`RevisioneBCondivisioneAppTest.sh4...`) |
| SH-5 | B | `definitionStaged`: una ripresa non riaccoda definizione e livello (`RevisioneBShareTest.sh5...`) |
| SP-1 | B | Il cancello degli arrivi della 5B per `AlbumFolderUploads` (`RevisioneBCondivisioneAppTest.sp1...`) |
| SP-2 | B | "In arrivo" calcolato dal diario (`RevisioneBCondivisioneAppTest.sp2...`) |
| SC-1 | B, E, F | Le copie si scrivono subito con `keepClosedCopies`, poi `copyKept` (`RevisioneBAlbumPageTest.sc1...`, `RevisioneBCondivisioneAppTest.sc1...`); E: il confine con A provato sul grafo vero (nessuna seconda copia, l'elemento tolto non torna), il creatore ha l'album anche senza il segno "visto attivo" e la copia non si ritira con il telefono che ha chiuso (`RevisioneEConfiniAppTest.sc1...`, `SharedAlbumCreatorCopyTest`). L231 **F (risposta dell'utente del 27/10 su L231): "Smetti di condividere" ferma gli elementi non ancora partiti** (restano dove sono, mai toccati) e lascia finire quelli gia' in viaggio; regge alla morte del processo (`ShareCloseInFlightTest`, morte a ogni salvataggio del diario; `RevisioneEConfiniAppTest.sc1...` aggiornato). C142 |
| SC-2 | B | `AlbumsGraph.watch` pubblica la coda rimasta (`RevisioneBCondivisioneAppTest.sc2...`) |
| SB-1 | B | `publishedId` legato alla replica (`AlbumFileRulesTest.laLapideDiRiportaVaSullIdPubblicato`; non provato con due dispositivi dello stesso account sul grafo vero) |
| SN-1 | B | Si contano solo gli elementi che stavano nella cartella dichiarata (`AlbumFolderWatchTest.sn1...`). L150 |
| SG-1 | B | Il piano chiede i cancelli del backup e dichiara `UPLOAD_BLOCKED` e `safeCopyBlocked` (`RevisioneBShareTest.sg1...`, `RevisioneBCondivisioneAppTest.sg1...`) |
| SG-2 | B | `roundDone`: un giro che non ha letto ne' scartato niente e' finito (`RevisioneBCondivisioneAppTest.sg2...`) |
| DP-1 | C | `NasTwins.settle`: `FULL_HASH` in coda e unione dopo l'hash completo (`CatalogIdenticalRevisionCTest.dp1...`). L160 |
| DP-2 | C | `AlbumMembership` per ogni copia dell'area comune; "mai la gemella del guardiano"; avviso E-D8 (`CatalogIdenticalRevisionCTest.dp2...`) |
| DP-3 | C | Le copie accettate escono subito dalla vista, la pagina si rilegge al cambio del catalogo (`DuplicatesControllerTest.dp3...`). L165 |
| DP-4 | C | Firme degli scatti all'apertura dei Simili e "stanotte" (`DuplicatesControllerTest.dp4...`, `CatalogSimilarShotsTest`) |
| DP-5 | C | `fullHashOn` nullabile, `NIGHTLY_NOT_SAVED` (`UtilityRevisionCTest.dp5...`) |
| DP-6 | C | "Eliminare 41 copie (3,2 GB)?" / "4 scatti (12 MB)?" (`DuplicatesScreenTest.dp6...`) |
| DP-7 | C | `confirm` nello scope del ViewModel, `phoneRequest` nello stato (`DuplicatesControllerTest.dp7...`) |
| UT-1 | C | Con la Libreria non leggibile per intero i Doppioni identici non si cercano (`CatalogIdenticalRevisionCTest.ut1...`) |
| ED-1 | C | Ogni ingresso del Cestino apre gli Eliminati di recente dietro `HiddenAccessGate` (`NasTrashGateTest`) |
| ED-2 | C | "Ripristina" su righe non ripristinabili risponde `REJECTED` e `RESTORE_PARTIAL` (`RecentlyDeletedNasTest`) |
| NA-1 | C | Spazio NAS senza i nascosti e fallendo chiuso (`HiddenSurfacesRegistryTest`, riga `NAS_SPACE`) |
| NA-2 | C | Il widget di prova aspetta l'istantanea (`FieldProbeWidgetContent.awaitSnapshot`). L165 |
| RC-1 | D | "Tutti gli album" sempre, "Tutti gli smart" oltre il sesto (`CollectionsSectionBindingTest`) |
| RC-2 | D | La scelta dell'utente vale finche' il registro non la ha riletta; consegna ritentata ogni 15 secondi (`CollectionsWiringTest`) |
| DG-1 | D | `SmartContentSearch.ready`: `SMART_NO_SEARCH` solo con la ricerca non pronta (`AlbumsDiagnosticsRenderTest`) |
| DG-2 | D | `DiagJob.ALBUM_SHARE` e `LIBRARY_MAINTENANCE`, `UtilityDiagnostics` (`AlbumJobReportsTest`, `AlbumJobsDiagnosticsTest`, `UtilityDiagnosticsTest`) |
| DG-3 | D, E | D: i rifiuti durano nel database (`rejectedHeads`). E: il rifiuto ha il suo **motivo** (`AlbumRejections`, `reason.<MOTIVO>` nell'esportazione), nel resoconto del giro e nel segno durevole (`SharedAlbumRejectionReasonsTest`, `RevisioneEDiagnosticaAppTest`). L174, L230 |

**Conteggio.** 46 voci: **46 corrette** (2 "Blocca" e 19 "Sbagliata" comprese), **0 non un difetto**, **0 aperte**. Le 18 voci "plausibili" erano tutte difetti veri: nessuna e' stata scartata. Cio' che resta e' dichiarato come limite (L150, L151, L160, L165, L172-L175, L230, L231) o come punto aperto delle sezioni dei pacchetti; le voci aperte della revisione di sicurezza (R3, R19, I6, I8) sono nel suo "Stato di ogni voce". Le risposte dell'utente del 27/10 sono nel pacchetto F: nessuna voce della verifica funzionale cambia di stato; L231 cambia (ora gli elementi non partiti si annullano).

**I fake del §3 dopo i pacchetti.** Gli errori dei fake che i pacchetti hanno chiuso: `InMemoryAlbums` (AS-1, AS-2: ora c'e' la prova sul grafo vero), `InMemoryShareDiary.opByPlan` (SH-1), `ReferenceShareSteps` (SB-1, I1), `FakeShareTransfers` (SP-1: la prova di `:app` usa il cancello vero), `CatalogIdenticalDuplicatesTest` e `HiddenWorldBuilder` (DP-1: l'unione dopo l'hash completo li rende veri). **Una divergenza nuova, trovata dalla revisione E**: `InMemoryShareDiary.closedOps` leggeva le operazioni chiuse e `RoomShareDiary` no (l'elenco vuoto di serie), quindi le prove di SH-2 del pacchetto B passavano e l'app vera perdeva la striscia "N da riprovare" con la morte del processo; ora il diario vero le legge e la prova gira su Room (`RevisioneEConfiniAppTest`).
