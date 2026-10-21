# Prove sul campo: esiti del blocco A (S25, 20/10/2026)

| | |
|---|---|
| **Quando** | 20/10/2026, 17:30–17:55 |
| **Telefono** | Samsung S25, Android SDK 37, build di debug di `f705a6f` (`dev.pampa.mycloudgallery.debug`) |
| **Media di prova** | `DCIM/mcg-prova` e `Pictures/mcg-prova`: 308 file, 5,37 GB, dal manifesto del giro `20261020-173044-435aed91` |
| **Copione** | `docs/prove-campo.md` non esisteva durante la sessione. Le prove sono prese da `docs/fase5-progetto.md` §5.6, da `docs/fase4-progetto.md` §5.7 (F1–F20 stanno lì, non nel §5.6) e dalle revisioni in `docs/review/` |
| **Esito in breve** | **Il blocco A si è fermato quasi subito.** L'app sul telefono **non è configurata con l'account di prova**: è entrata con l'account vero `alessio`, area privata `alessio`, area comune `Public` (vedi §1). Le prove del backup (F21–F24, F30), "Libera spazio" (F37) ed "Elimina solo dal telefono" (F38) quindi non si possono fare. Fatte solo le prove che non dipendono dall'account: controlli iniziali, T-09 in parte, F39 con `adb`, impronte del telefono |

## 1. Il blocco: la configurazione dell'app è quella vera

Quello che l'app dice di sé, letto senza modificare niente:
- **Impostazioni → Account del NAS**: Utente `alessio`.
- **File di configurazione dell'app** (letti con `run-as`, solo le chiavi non segrete):
  - `nas-account`: `nas.user=alessio`;
  - `onboarding`: `usr=alessio`, area privata `ps=alessio`, area comune `cs=Public`, percorso `RECOVER_ADMIN`, configurazione finita il 19/10.
- Il database locale è stato ricreato oggi alle 17:30, ma la configurazione non è stata toccata: è quella del 19/10.

Cosa comporta:
- **D12, il cancello delle share.** L'area privata `alessio` non è una share di prova. La pagina Backup dice "Backup in prova: si scrive solo nelle share di prova" e i lavori del backup non fanno niente (`PhotoBackupWorker: tappa: null`). Non si può fare nessun primo backup, né quindi F21, F22, F23, F24, F30.
- **D13, la recinzione del telefono.** Vale solo con un account di prova. Con `alessio` è spenta:
  - "Cosa salvare" elenca tutte le cartelle vere del telefono;
  - la pagina Backup mostra "Fotocamera: 957 media, 957 in coda" e "Screenshot: 1.105 media, 1.105 in coda".
  
  "Libera spazio", "Elimina solo dal telefono", correzioni e versioni agirebbero quindi sulle foto vere e sulle share vere `alessio` e `Public`. Per questo **non ho toccato nessuna di queste funzioni**, nemmeno su `mcg-prova`.
- **Nessun danno.** Niente è stato scritto sul NAS:
  - il backup è fermo dal cancello;
  - l'indice condiviso si pubblica solo sulle share di prova (D31).
  
  La scansione della Libreria ha letto `alessio` e `Public` in sola lettura, come fa normalmente.

**Per sbloccare serve l'utente** (§5): rifare la configurazione dell'app di debug con `test`, e per questo bisogna digitare la password.

## 2. Esiti prova per prova

| Id | Prova | Esito | Misure e note |
|---|---|---|---|
| C-1 | App in primo piano, sessione NAS pronta | **passata** | `NasSession`: "sessione pronta: SMB_3_1_1, cifrata, AES_128_GCM, via=HOME_LAN", 17:30:17,7 |
| C-2 | La Libreria vede `mcg-prova` | **passata, con una nota** | MediaStore: 308 righe di `mcg-prova` (300 immagini, 8 video). Catalogo dell'app: 305; mancano i tre file finti dei fixture (`casuali.png`, `testo.jpg`, `video-falso.mp4`), vedi O-1. Le celle di prova restano sul segnaposto: le miniature non si caricano ("miniatura del telefono non disponibile: io", 335 righe), ma i file sono sintetici, da 600–800 byte. I due video grandi mostrano durata 0:00 |
| C-3 | La scansione del NAS fallita all'avvio riprova da sola? | **fallita** (difetto D-2) | Non riprova finché l'app non va in background e torna in primo piano, o finché non arriva il giro periodico (6 ore). Dettagli in §3 |
| F21 | Una foto, "Fai ora", SHA-256 telefono = NAS (T-11) | **non eseguibile** | §1: nessun backup possibile con `alessio` |
| F22 | Primo backup di `mcg-prova` ("Inizia ora"): tempo, traffico, batteria, stima ±30%, notifica, "Ferma"/"Riprendi" | **non eseguibile** | §1. La pagina Backup dice "Cartella del dispositivo: si sceglie prima del primo backup" e non ha "Inizia ora" |
| F23 | Wi-Fi spento a metà di un video da 2,5 GB | **non eseguibile** | §1. In più non c'è un contatore dei "byte ricaricati" in Diagnostica: si deduce da `bytesUploaded` dell'ultimo giro (esportazione → anteprima) meno la dimensione del video |
| F24 | `am kill` durante caricamento e verifica | **non eseguibile** | §1 |
| F30 | 100 interruzioni dal telefono durante il primo backup (`s25.ps1 -Mode backup -Step interrupt`) | **non eseguibile** | §1. Senza un backup in corso le interruzioni non provano niente. Non è stata lanciata |
| F26 | Parte senza Samsung Gallery | **non eseguibile** | Senza backup non c'è "Persa prima del backup" |
| F37 | "Libera spazio" su `mcg-prova` | **non eseguibile** | §1: senza D13 agirebbe sulle foto vere e scriverebbe sulle share vere |
| F38 | "Elimina solo dal telefono" | **non eseguibile** | §1. Serve inoltre un media che sia sia sul telefono sia sul NAS, quindi dopo un backup |
| T-09 (d, e) | Prove guidate → "Cestina 5 foto con l'app" | **passata, con una scoperta** | Il dialogo di sistema è comparso ("Consentire all'app MyCloud Dev di spostare 5 foto nel cestino?") e ho toccato Consenti: `t09.trash_result=-1` (OK). Dopo il cestino **i 5 file escono dalla cartella**: non resta un `.trashed-…` al loro posto e la prova non ritrova le righe (`trash_is_trashed=0`, `trash_visible_default=0`). Su One UI il cestino di sistema sposta i file nel cestino di Samsung Gallery (`Android/.Trash/com.sec.android.gallery3d`). La scadenza (`DATE_EXPIRES`) non si legge |
| T-09 (ripristino) | "4 · Ripristina quelle foto" | **fallita** (difetto D-3) | Nessun dialogo. Il messaggio dice "Niente da fare: nessuna foto adatta in mcg-prova". **Le 5 foto di prova sono ancora nel cestino di Samsung Gallery** (§5) |
| T-09 (f) | "5 · Cestina fino a 500 foto" | **non eseguita, di proposito** | Dopo D-3 avrebbe mandato tutta `mcg-prova`, video da 2,5 GB compresi, in un cestino da cui l'app non sa riprenderla, e il blocco B ne ha bisogno |
| T-09 (scrittura) | "6 · Permesso di scrittura e riscrittura" | **passata** | Dialogo "Consentire all'app MyCloud Dev di modificare questa foto?" e Consenti: `write_dialog=-1`, `write_code=0` (riscritta e rimessa com'era), 33 ms. Lo SHA-256 del file dopo la prova è uguale a quello del manifesto (822 byte). `date_modified` del file è cambiato. Subito dopo, il MediaStore diceva ancora 823 byte (`write_size_after=823`); pochi secondi dopo 822: un ritardo del MediaStore, non un errore |
| T-11 | EXIF oscurato in lettura | **non eseguibile** | Non è una prova guidata dell'app: nella pagina ci sono solo T-09, T-14, T-15 e T-16. La sua parte pratica è il confronto SHA-256 di F21, bloccato |
| T-14 / T-15 / T-16 | Selettori, ricevuti, "Modifica con…" | **blocco B** | Servono Gmail, WhatsApp, Telegram, Samsung Gallery, Google Foto. `env.*` della prova: SDK 37, 308 righe, 12 app per `GET_CONTENT`, 62 per `SEND` |
| F39 (adb) | `content query` sul provider dei documenti da `adb shell`: atteso `SecurityException` | **aspettativa sbagliata** (O-3) | Il provider risponde con la radice (`root_id=mcg-root`, `document_id` opaco, nessun percorso), perché la shell ha `MANAGE_DOCUMENTS` concesso (`dumpsys package com.android.shell`). Il `FileProvider` non esportato dà `SecurityException`, come atteso |
| Impronte | `s25.ps1 -Mode backup -Step verify -SkipNas -AllowFreed` | **passata** | 303 file su 308 con le impronte del manifesto, nessun file cambiato. Mancano i 5 del cestino di T-09 (lo script li chiama "tolti da Libera spazio": è la sua dicitura) |
| 4A/4B | Prove della Libreria e delle operazioni sul NAS fattibili da solo | **non eseguibili** | Con `alessio` e `Public` ogni operazione sul NAS toccherebbe le share vere. La scansione in sola lettura è descritta in O-5 |

**Misure.** Batteria dal 63% al 59% in circa 25 minuti, schermo acceso, scansione del NAS in corso, cavo USB lento. Nessun backup partito: non ci sono misure di tempo, traffico o stima.

## 3. Difetti, in ordine di gravità

### D-1 (bloccante, ambiente): l'app di debug è configurata con l'account vero
Non è un difetto del codice: la configurazione non è quella che il blocco A presuppone (§1). Le protezioni D12 e D31 hanno tenuto: niente è stato scritto sulle share vere.

Però, finché è così, la pagina Backup del telefono mostra le sorgenti vere ("957 in coda", "1.105 in coda") come se fossero in corso, mentre il backup è "in prova" e non scriverà mai (vedi D-4).

### D-2 (media): la prima scansione del NAS dopo un avvio a freddo va persa e non si ripete
- **Cosa succede.** All'avvio `MyCloudGalleryApp` accoda subito `NasWorkScheduling.scanNow`. `NasScanWorker` parte prima che la sessione SMB sia pronta e `SessionNasClock.nowMillis` lancia `NasIoException(Unexpected)`: l'orologio del NAS non ha ancora un'ancora. `NasWorker.doWork` registra "tappa non riuscita" e risponde `Result.success()`: nessun nuovo tentativo, il lavoro "nas-scan-now" è consumato.
- **Quando riparte.** Solo se l'app va in background e torna (`onForeground` → `verifyDue` → `scanNow`), oppure al giro periodico (`PERIOD` = 6 ore). Diagnostica → "Ultimi esiti ed errori" dice "Verifica del NAS: non riuscito… riprova al prossimo giro".
- **Sul telefono.** Fallita alle 17:30:17,5, 180 ms prima di "sessione pronta" (17:30:17,7). Fino alle 17:43 nessun nuovo tentativo, con l'app sempre in primo piano. Alle 17:43 l'app è passata in background e tornata: `nas-scan-now` è ripartito e ha funzionato. Il giro periodico era comunque dovuto alle 17:47 (la sua ultima corsa risaliva a circa 6 ore prima).
- **Log** (senza nomi):
  ```
  17:30:17.534 E/MyCloud/NasScanWorker: tappa non riuscita
      NasIoException: errore NAS: Unexpected
      at core.nas.session.SessionNasClock.nowMillis(Clocks.kt:82)
      at core.nasmedia.scan.DefaultNasScanner$Run.execute(DefaultNasScanner.kt:260)
  17:30:17.714 I/MyCloud/NasSession: sessione pronta: SMB_3_1_1, cifrata=true, cifrario=AES_128_GCM, via=HOME_LAN
  (nessun NasScanWorker fino a) 17:43:19.592 WM-WorkerWrapper: Starting work for NasScanWorker   <- dopo background/primo piano
  ```
- **Passi per riprodurlo.**
  1. `am force-stop` dell'app.
  2. `am start` della `MainActivity`.
  3. Guardare `logcat` (`MyCloud/NasScanWorker` prima di `MyCloud/NasSession: sessione pronta`).
  4. Lasciare l'app in primo piano: nessun nuovo `NasScanWorker` fino al giro periodico.
- **Rimedio probabile** (non applicato).
  - Prima di scansionare, aspettare la sessione o l'ancora dell'orologio.
  - Oppure trattare "orologio senza ancora" come una fermata da riprovare: `Result.retry()`, o `scanNow` quando la sessione diventa pronta.

### D-3 (media, prova guidata T-09; da guardare anche per "Libera spazio"): su Samsung l'app non ritrova le foto che ha mandato nel cestino
- **Cosa succede.** Dopo `createTrashRequest` confermato, One UI sposta i file nel cestino di Samsung Gallery. Escono da `DCIM/mcg-prova/`: non resta un file `.trashed-…` nella cartella e non si trovano altrove sotto `/sdcard` con il loro nome.
  - `FieldProbePhone.rows()` filtra per `RELATIVE_PATH` delle due cartelle, con `MATCH_TRASHED = INCLUDE`, e non le trova più.
  - "Ripristina quelle foto" quindi non apre nessun dialogo e mostra un messaggio sbagliato: "Niente da fare: nessuna foto adatta in mcg-prova".
  - Anche le misure (d) della prova restano a zero: `IS_TRASHED`, `DATE_EXPIRES`.
- **Perché conta oltre la prova.** "Libera spazio" (P9) conta sul cestino di Android ("le tiene 30 giorni"). Se l'app deve poter ripristinare o anche solo ritrovare un media cestinato, su Samsung probabilmente non ci riesce cercando per cartella. Va controllato nel codice di `SafeFreeSpace` e della pagina "Liberati", e con F37 quando si potrà fare.
- **Passi per riprodurlo.**
  1. Impostazioni → Sviluppo → "Prove guidate".
  2. "3 · Cestina 5 foto con l'app", poi Consenti.
  3. "4 · Ripristina quelle foto".
- **Contatori** (`no_backup/field-probe/counters.properties`):
  ```
  t09.trash_requested=5  t09.trash_result=-1  t09.trash_is_trashed=0  t09.trash_visible_default=0
  ```

### D-4 (bassa, pagina Backup): con il backup "in prova" le sorgenti dicono "In corso" con migliaia di media in coda
Con il cancello chiuso (D12) le righe "Fotocamera" e "Screenshot" mostrano "In corso · 0 al sicuro · N in coda", ma il backup non partirà mai. Per chi guarda sembra un backup che lavora. Si vede solo con una configurazione come quella di §1.

### D-5 (bassa, testo): il comando di semina nella pagina delle prove guidate è sbagliato
La pagina dice "Semina prima con tools/chaos/s25.ps1 -Mode seed". Lo script accetta solo `-Mode move|backup`: il comando giusto è `s25.ps1 -Mode backup -Step seed`. Lo stesso errore è in `docs/android.md`.

## 4. Osservazioni (non difetti, da tenere presenti)

- **O-1. Tre file del manifesto non entrano nel catalogo.** Sono `casuali.png`, `testo.jpg` e `video-falso.mp4`, i fixture finti, che l'app scarta perché non si leggono. Se il backup non li carica, alla fine `area.ps1 -Stage verify` li chiederà sul NAS (invariante 4: "ogni file del telefono c'è") e la prova F30 risulterà rossa senza un difetto vero. Prima di F30 va deciso se il backup li deve caricare o se il manifesto li deve escludere.
- **O-2. Due file "Screenshot_…png" in `mcg-prova`.** L'app li classifica come screenshot: non risultano nella cartella `mcg-prova` di "Cosa salvare", che conta 199 + 104 = 303 media prima del cestino. Con la recinzione D13 la sorgente Screenshot (`Pictures/Screenshots/`) non passa mai, quindi rischiano di non essere salvati da nessuna sorgente. Da controllare al primo backup con `test`.
- **O-3. F39 da `adb shell`.** L'attesa della revisione 5B ("`SecurityException`") non vale perché la shell ha `MANAGE_DOCUMENTS`. La prova vera è da un'app che non ha il permesso (blocco B, con T-14).
- **O-4. Diagnostica.** Le voci mostrano solo titolo e codice. I numeri si vedono solo in "Esporta la diagnostica" → anteprima, che è un testo unico non leggibile da `uiautomator`. Per le prove automatiche conviene leggere i numeri da un file dell'app.
- **O-5. Prima scansione dopo il database ricreato** (account `alessio`, sola lettura): 2 cartelle, 9.306 file, giri "Verify" da 1.000 file ogni 75–80 s, circa 12 minuti in tutto.

## 5. Cosa serve dall'utente

1. **Rifare la configurazione dell'app di debug con l'account di prova** `test`: area privata `test`, area comune `test-comune`. Serve la password, quindi solo l'utente.
   - **Attenzione.** Oggi l'app di debug di questo S25 è il dispositivo **admin della famiglia** con l'account vero: ha la chiave di famiglia, percorso `RECOVER_ADMIN`. Cancellare i dati dell'app per rifare la configurazione toglie da questo telefono quella copia della chiave. Prima di farlo, controllare di avere il kit di recupero o un secondo admin.
   - In alternativa si può decidere un'altra strada, per esempio un altro profilo del telefono.
2. **Ripristinare le 5 foto di prova dal cestino di Samsung Gallery**: Galleria → Cestino → seleziona → Ripristina. Sono:
   - `20240312_101533.jpg`
   - `20240312_110000_001.jpg`
   - `20240312_110000_002.jpg`
   - `20240312_110000_003.jpg`
   - `20240312_112000_001.jpg`
   
   Dopo il ripristino, `s25.ps1 -Mode backup -Step verify -SkipNas` deve tornare a 308 su 308.
3. Poi si rilancia il blocco A, che è pronto:
   - F21 e F22 con le misure;
   - F23 e F24;
   - F30, con il manifesto;
   - T-09 (f);
   - F37 e F38.
   
   Prima va decisa O-1.

## 6. Blocco B (prove che servono all'utente)

`docs/prove-campo.md` non c'era: l'elenco viene dalle tabelle F e dalle revisioni.

| Id | Cosa fa l'utente |
|---|---|
| P-1 | Password forti per `test` e `test2` (dashboard WD → Utenti), poi aggiornarle in `nas-it.properties` |
| P-2 | Configurazione dell'app di debug sull'S25 con `test` (vedi §5.1); Tab S9 con il profilo "Prova" e `test2` |
| P-3 | Dal PC, in `test\mcg-campo\` e `test-comune\mcg-campo\`: un campione di ogni formato, una cartella da 1.000 foto, un video oltre 2 GB (F1–F12) |
| F26 / T-09 a | Galleria Samsung → album `mcg-prova` → elimina 1 foto **prima** del backup, poi avvisa. Dopo: Cestino → Ripristina |
| T-09 (risposta) | Dopo "Cestina 5 foto con l'app", aprire il cestino di Galleria e dire se le mostra. Oggi le mostra quasi certamente, visto D-3: va confermato a occhio |
| F25 | Prima di "Fai ora", copiare dal PC in `test\mcg-campo\Backup\<dispositivo>\AAAA\MM\` un file diverso con lo stesso nome di una foto non ancora salvata, e una copia identica di un'altra |
| F27 | Tab S9 con la debug e lo stesso account `test`: un backup (cartelle separate per dispositivo) |
| F28 | Collegare l'S25 all'hotspot del Tab S9 (dati mobili), preset Bilanciato |
| F29 | Dopo lo spostamento di 20 foto in `test-comune` fatto dall'app: dal PC, come `test2`, cancellarle da `test-comune` |
| F30 / F43 (PC) | Runner del backup sul NAS (`backup.rounds=400`) e `run5b`, già fatti secondo il brief |
| F31 | Dashboard WD: quota piccola su `test`, poi toglierla |
| F31-bis | Prima del backup: in `test\mcg-campo\` copie identiche, ricompresse, con EXIF riscritto, da HEIC, più una raffica; poi "Separa" sul tablet |
| F32, F44 | APK di release, account vero: primo backup vero su `alessio` (dopo Q3) |
| F33 | Una settimana con Notte e una con Bilanciato |
| F34 / T-16 a | Galleria Samsung, su una foto di `mcg-prova` già al sicuro: Modifica → Salva; poi "Ripristina originale"; poi "Salva copia" |
| F35 | Galleria → un video di `mcg-prova` → Modifica → Salva |
| F37 (parte PC) | Dal PC cambiare 1 byte di una copia in `Backup\...` e cancellarne un'altra; poi "Libera spazio" dal telefono |
| F39 / T-14 | Gmail → Allega file → MyCloud Gallery; WhatsApp/Telegram → Documento: 1 foto del telefono e 1 solo-NAS; una con Wi-Fi spento dopo la scelta; una annullata; blocco app "Subito"; nascondere una foto con il selettore aperto |
| F40 / T-15 | Condividi verso "Salva sul NAS": 100 foto da Galleria, un video da 1 GB da I miei file, un'immagine da Chrome |
| F41 / T-16 b | "Modifica con…" con Galleria Samsung (telefono) e Google Foto (solo NAS); un editor che salva una copia |
| F42 | Viewer → "Condividi senza posizione" → Gmail a sé stessi (JPEG, HEIC, MP4); controllare con `exiftool` sul PC |
| F45 / F14 | Dashboard WD: `test-comune` (e `Backup/`, `Versioni/`) in Twonky e Plex; guardare in TV; poi togliere |
| Revisioni 5A/5B | R5 (accesso parziale alle foto, Impostazioni Android), R3 della 5B (due modifiche Samsung), R4/R1-R2 (`exiftool` su foto e video vari), R8 (app in sovrimpressione), R7 (dispositivo tolto dalla famiglia), tolleranza dei 2 minuti del blocco (serve il PIN) |
| Fase 4 | F2, F4, F5, F7, F8, F9, F13, F14, F14-bis, F17, F18, F19, la parte PC di F15 e F20, la parte tablet di F6 e F10 |


---

# Ripresa con `test` (20/10/2026, 18:20–19:15)

| | |
|---|---|
| **Configurazione** | L'app di debug (stesso APK `f705a6f`) è stata azzerata dall'orchestratore con `pm clear` e rifatta dall'utente con `test`: area privata `test`, area comune `test-comune`, famiglia di prova nuova (`FOUND_FAMILY`). Controllato: Impostazioni → Account del NAS dice `test`; nei file dell'app `nas.user=test`, `usr=test`, `ps=test`, `cs=test-comune`. |
| **Recinzione D13** | **Attiva.** "Cosa salvare" offre solo le due cartelle `mcg-prova` (199 media · 5,4 GB e 104 media · 4,6 kB); Fotocamera e Screenshot dicono "Non salvata · non si salva". "Libera spazio" esclude 6.850 media "Fuori dalla cartella di prova". |
| **Cancello D12** | Il backup **non** è "in prova" verso share vere: la pagina passa da "Il backup non è ancora pronto · Scegli cosa salvare" a "296 in coda". |
| **Le 5 foto del cestino (T-09)** | Ripristinate dall'utente: `s25.ps1 -Step verify -SkipNas` alle 18:24 dice **308 su 308**, impronte uguali al manifesto. |
| **Manifesto usato per il NAS** | Copia del manifesto del giro `20261020-173044-435aed91` **senza** i tre fixture finti (E-M12, O-1): 305 righe `phone=`. Sta nella cartella di appoggio della sessione (`manifest-test.txt`), non nel repo. |
| **Esito in breve** | Il primo backup è arrivato in fondo: **296 media al sicuro**, diario tutto `done`, telefono invariato. Però **il backup avviato dall'utente non sopravvive alle interruzioni** (D-6, D-7): dopo una caduta del Wi-Fi o una morte del processo il lavoro finisce "DONE" e riparte solo al giro periodico (6 ore) o con un tocco. Per questo **F30 è bloccata** e l'ho fermata dopo 11 interruzioni. La verifica dal PC **non è stata fatta**: il login di prova dal PC è fallito con `Auth` (§R.4) e, come da regole, mi sono fermato. |

## R.1 Esiti

| Id | Esito | Misure e note |
|---|---|---|
| Controlli iniziali | **passati** | Account `test`, D13 attiva, nessun "in prova". Batteria 58% all'inizio. Al primo avvio dopo la configurazione D-2 c'era ancora (vedi sotto) |
| F22 · riepilogo del primo backup | **non visto** (difetto D-9) | Ho scelto la cartella del dispositivo da Impostazioni del backup → "Cartella del dispositivo" → "Usa questo nome" (proposta "Telefono di test") **prima** di passare dalla pagina Backup: lo stato "Pronto per il primo backup / Vedi il riepilogo / Inizia ora" non è mai comparso (`BackupGraph.composeStatus`: il primo backup esiste solo finché la cartella non è scelta). Il backup è partito con "Fai ora". La stima l'ho ricostruita con la stessa formula del riepilogo (`BackupEstimator.startNow`, `DeviceMeasures.PHASE_0`): **16 min 8 s, 10,74 GB di traffico, 5,4% di batteria** per 5.368.845.923 byte e 296 media |
| F21 · al sicuro (con T-11) | **passata a metà** | Libreria: le celle di `mcg-prova` dicono "al sicuro sul NAS"; viewer → Informazioni: "Al sicuro sul NAS · Copia privata riletta e controllata alle 18:29". Il confronto SHA-256 telefono/NAS (T-11) **non è fatto**: il verificatore dal PC non è partito (§R.4) |
| F22 · avvio, notifica, Ferma/Riprendi | **passata, con due difetti** | "Fai ora" → "Backup in corso, avviato da te". Notifica attiva "Backup di foto e video · 291 di 296 elementi", barra 291/296, un'azione (Ferma). **Ferma** → "In pausa · non riparte da solo finché non tocchi «Riprendi»": dopo 60 s ancora fermo, nessun traffico. **Riprendi** → riparte dal punto di ripresa. **Ferma e subito `am force-stop`** → alla riapertura "In pausa", nessun traffico del backup per 2 minuti (5a-R4, 4b-R9: ok). Difetti: "Fai ora" ignorato al primo tocco 2 volte su 4 (D-8); avanzamento sbagliato (D-11) |
| F22 · tempi, traffico, batteria | **parziale** | Orologio: 18:28:17 → 19:06:20 = 38 min, di cui circa 16 min fermo (attese di D-6/D-7 e le pause volute) e circa 22 min di lavoro. Fasi pulite: 291 foto in 44 s (0,15 s a file; stima 0,12); caricamento dei due video 7,3 MB/s con due file in volo (stima 9 MB/s); rilettura di verifica 14–26 MB/s (stima 16). **Tempo "pulito" ricostruito circa 18 min, +11% sulla stima: dentro ±30%.** Lavoro misurato 22 min (+36%) e traffico misurato **15,4 GB** (inviati 5.441 MiB, ricevuti 9.214 MiB; +43% sulla stima): l'eccesso è tutto delle interruzioni, cioè circa 320 MiB ricaricati e circa 4 GB riletti, perché la verifica di un video da 2,5 GB ricomincia da zero a ogni interruzione. Batteria 58% → 55% in 38 minuti sul cavo USB lento a schermo acceso: non confrontabile con il 5,4% "a batteria" della stima |
| F22 · Libreria fluida | **indicativa** | 10 scorrimenti durante il caricamento, build di debug: `gfxinfo` 2,28% fotogrammi lenti, p90 12 ms, p99 28 ms. Non confrontabile con il riferimento del benchmark (altra variante, altra metrica) |
| F23 · Wi-Fi spento 30 s a metà video (E-B1) | **fallita** (difetto D-6) | Dati persi alla caduta: 1.109,5 MiB inviati contro punti di ripresa a 256 + 806 MiB, cioè **47,5 MiB su due file in volo, sotto i 64 MiB a file: questa parte è ok**. Ma il lavoro dell'utente è finito "DONE" 2 s dopo la caduta (18:31:49) e **non è ripartito da solo**: dopo 3 minuti e mezzo dal ritorno del Wi-Fi, traffico zero, il prossimo `VideoBackupWorker` in 5 h 42 min, e la pagina diceva ancora "Backup in corso, avviato da te · meno di un minuto". È ripartito solo con Ferma → Riprendi |
| F24 · morte durante verifica e caricamento | **passata una volta, poi fallita** (D-7) | `kill -9` alle 18:43:48 (un video in verifica, l'altro in caricamento): il servizio è ripartito in 1 s, la tappa è ripresa dopo la sessione pronta, la verifica è ricominciata da capo come previsto. Alla stessa prova alle 18:54:32 (F30, morte n. 1) il lavoro ripartito è finito "DONE" in 0,4 s senza fare niente: backup fermo fino a un tocco. `am kill` con l'app in background **non uccide** il processo che ha un lavoro dell'utente in corso, né quello visibile dietro un dialogo di sistema: è il comportamento di Android, non un difetto, e la prova "am kill" si fa con `kill -9` |
| F30 · 100 interruzioni dal telefono | **bloccata** (D-6, D-7) | Prima corsa (seme 7, misto): alla 2ª interruzione (Wi-Fi spento 9 s, 18:48:53) il lavoro è finito "DONE" e il backup è rimasto fermo ("5 in coda · Al prossimo giro"). Fermata alla 9ª. Seconda corsa (seme 7, solo morti): alla 1ª il lavoro ripartito è finito "DONE" in 0,4 s. Fermata alla 2ª. In tutto **13 interruzioni vere sul backup** (9 + 2 + F23 + F24). Nessun crash dell'app (l'unica `FATAL` del log è di `uiautomator`). Poi "Fai ora" ha finito il lavoro senza interruzioni (18:57–19:06): "Tutto al sicuro · 296 media al sicuro sul NAS", diario 296 `done`. Gli invarianti sul NAS **non sono verificati** (§R.4). Telefono invariato alle 19:06 (308 su 308) |
| F37 · "Libera spazio" | **passata, tranne la morte durante il dialogo** (D-10) | Selezione di 5 foto di `mcg-prova` al sicuro → "Libera spazio" → "Si possono liberare 220 B · 5 foto e video" → "Liberare spazio?" (avviso "Il NAS non ha un backup esterno", "Nel cestino di Android per 30 giorni", "Prima ricontrollo ogni copia") → "Pronto: 5 elementi · la verifica vale ancora circa 6 h 1 min" → "Libera ora" → dialogo di sistema "Consentire all'app MyCloud Dev di spostare 5 foto nel cestino?". **Con il dialogo aperto `kill -9` dell'app**, poi Consenti: i 5 file escono dal telefono, il catalogo dice "telefono assente, NAS presente" (giusto), ma **il diario di "Libera spazio" segna i 5 elementi `kept`** ("rimasti sul telefono") con il giro `done`. La stessa cosa senza morte, su 1 foto (via F38), dice `freed` e "Liberati 44 B": quella strada va bene. La parte con il PC (una copia con un byte cambiato, una cancellata) è del blocco B |
| F38 · "Elimina solo dal telefono" | **passata la strada buona** | Viewer di una foto di `mcg-prova` al sicuro → cestino → "Eliminare questa foto?" (Solo dal telefono / Ovunque, con le conseguenze per telefono e NAS) → "Elimina dal telefono" → "Elimina solo dal telefono · Prima controllo che la copia sul NAS sia al sicuro · 1 si può togliere" → Continua → flusso di "Libera spazio" con il dialogo → `freed`. Le strade "non al sicuro" e "sola copia comune" non si possono preparare con la sola app: blocco B |
| T-09 (f) | **non eseguita, di proposito** | Con D-3 (su One UI il cestino sposta i file nel cestino di Samsung Gallery e l'app non li sa ripristinare) avrebbe mandato tutta `mcg-prova`, video compresi, in un cestino da cui solo l'utente può riprenderla. Il blocco B ne ha bisogno |
| 4A/4B da solo | **non eseguite** | Fermo dopo l'`Auth` del PC (§R.4), e nessuna area di prova con i campioni F1 (§2.5 del copione) |
| D-2 (scansione all'avvio) | **ancora presente** | 11 avvii a freddo su 13 nel log: `NasScanWorker: tappa non riuscita` (`SessionNasClock.nowMillis`, orologio senza ancora) prima di `NasSession: sessione pronta` |

## R.2 Difetti nuovi, in ordine di gravità

### D-6 (alta): una caduta del Wi-Fi chiude il backup avviato dall'utente, e niente lo fa ripartire
**Passi.**
1. "Fai ora" con un video grande in caricamento.
2. `s25.ps1 -Mode backup -Step interrupt -Interruptions 1 -WifiPercent 100 -WifiOffMinSeconds 30 -WifiOffMaxSeconds 30`.
3. Al ritorno del Wi-Fi, aspettare 3–5 minuti.

**Cosa si vede.**
- `BackupJobService: lavoro del backup finito: DONE` 2 s dopo la caduta, preceduto da `onNetworkChanged() not implemented in BackupJobService`.
- Il diario resta con i video `uploading`; nessun traffico.
- Il `VideoBackupWorker` periodico è fra circa 6 ore.
- Pagina e Libreria dicono "Backup in corso, avviato da te · meno di un minuto" finché non si tocca qualcosa, poi "5 in coda · Riprende al prossimo giro".

**Dove guardare** (ipotesi, non provata): `BackupJobRun.run()` (`work/BackupJobService.kt`) chiude il lavoro con `DONE` quando la tappa risponde `BackupFollowUp.Wait`, cioè rete o regole (`work/BackupWork.kt`, `BackupFollowUp.of`). Per un lavoro dell'utente nessuno riprogramma la ripresa al ritorno della rete. BK-10 ("il video riprende dal lavoro dei video, non dopo 6 ore") qui non vale.

**Log essenziale:**
```
18:31:47.332 D/MyCloud/NasSession: sessione chiusa per cambio di rete
18:31:47.849 W/JobService: onNetworkChanged() not implemented in ...BackupJobService
18:31:49.558 D/MyCloud/BackupJobService: lavoro del backup finito: DONE
(18:32:17 Wi-Fi acceso; fino alle 18:36 nessun traffico del backup)
```

### D-7 (alta): dopo una morte del processo il lavoro dell'utente può ripartire e chiudersi subito "DONE"
**Passi.** Backup dell'utente in corso, poi `kill -9` del processo (`s25.ps1 ... -WifiPercent 0 -Interruptions 1`).

**Cosa si vede.** Android riavvia il servizio dopo 1 s. A volte la tappa riprende: alle 18:43:48 `giro photos` è arrivato 0,3 s dopo `sessione pronta`. Altre volte il lavoro si chiude in 0,4 s senza nessun giro: alle 18:54:32 `Start proc` → `lavoro del backup finito: DONE`, e poi `NasScanWorker: tappa non riuscita` e `sessione pronta`. Il backup resta "5 in coda · Al prossimo giro".

**Ipotesi.** `stage(kind)` risponde `null` ("non parte") perché la galleria o il NAS del processo nuovo non sono ancora pronti, e `BackupJobRun` lo prende per "niente da fare". È la stessa corsa di D-2, sul backup.

```
18:54:32.081 I/ActivityManager: Start proc 10738:dev.pampa.mycloudgallery.debug for bound-service {...BackupJobService}
18:54:32.466 D/MyCloud/BackupJobService(10738): lavoro del backup finito: DONE
18:54:33.210 E/MyCloud/NasScanWorker(10738): tappa non riuscita (NasIoException: Unexpected)
18:54:33.589 I/MyCloud/NasSession(10738): sessione pronta
```

### D-9 (media): scegliere la cartella del dispositivo dalle impostazioni salta il riepilogo del primo backup
**Passi** (app appena configurata).
1. Impostazioni del backup.
2. Accendere le due `mcg-prova`.
3. "Cartella del dispositivo" → "Usa questo nome".
4. Tornare alla pagina Backup.

**Cosa si vede.** "296 in coda · Riprende al prossimo giro. Da caricare: 0 B" e "Fai ora". Lo stato "Pronto per il primo backup", il foglio "Primo backup" con stima di tempo, traffico e batteria, e la scelta "Stanotte / Inizia ora" non compaiono più. Il primo backup partirebbe da solo al giro delle regole senza che l'utente veda il riepilogo (§2.11 del progetto: "il primo backup lo avvia l'utente, con il riepilogo"). In più "Da caricare: 0 B" con 296 in coda è sbagliato (i byte vengono solo dal diario).

### D-10 (media): "Libera spazio", app uccisa con il dialogo di sistema aperto: il diario dice "rimasti" per file che non ci sono più
**Passi.** Selezione di 5 foto al sicuro → "Libera spazio" → "Sì, libera spazio" → "Libera ora" → con il dialogo aperto `run-as ... kill -9 <pid>` → "Consenti".

**Cosa si vede.**
- I file escono dal telefono e il catalogo li dà "telefono assente, NAS presente".
- `free_space_item` dice `kept` per tutti e 5 (giro `done`).

Invariante 14, contabilità: "nessun «rimasto» con la riga tolta". Era il punto I2 della revisione 5B. È possibile che c'entri D-3: su One UI la riga cestinata sparisce dal MediaStore invece di restare con `IS_TRASHED=1`.

### D-11 (bassa): l'avanzamento del backup sbaglia byte e tempo, e non è cumulativo dopo una morte
- Durante il caricamento dei due video da 2,5 GB la pagina diceva "Video: 291 di 296 · 109 kB di 111 kB · meno di un minuto" per oltre 10 minuti.
- Prima, "Foto: 46 di 296 · 7,5 kB di 48 kB", poi "di 25 kB": il totale scende.
- Dopo una morte del processo: "Video: 0 di 5 · 0 B di 5,4 GB", poi "1 di 5 · 22 MB di 110 MB".

BK-5 chiede un avanzamento cumulativo (fatti + in coda) e un tempo stimato credibile. `RunProgress.kt` risulta modificato nell'albero di lavoro: potrebbe essere già in correzione.

### D-8 (bassa): "Fai ora" a volte non fa niente al primo tocco
4 tocchi su "Fai ora" subito dopo l'apertura della pagina:
- alle 18:27:15 e alle 18:56:16 nessun effetto: nessun log, nessun lavoro, nessun messaggio;
- il tocco ripetuto un minuto dopo ha funzionato (18:28:17, 18:57:16).

La pagina era quella giusta, verificata con lo screenshot.

### Osservazioni
- **I file della semina.**
  - Le foto "uniche" (`IMG_mcg_*.jpg`) sono di **44 byte**: un'intestazione JPEG di 20 byte più 24 byte casuali. Lo script prende il JPEG più piccolo dei fixture, che non è un'immagine intera.
  - Quindi il viewer dice "Non riesco ad aprire questo file · Il file potrebbe essere rovinato" e le miniature non si fanno.
  - Le prove di Libreria e viewer su `mcg-prova` non dicono niente sulle foto: serve una semina con un JPEG vero (§2.4 del copione).
- **Pannello Informazioni.** Per un media liberato (solo NAS) il pannello mostra solo la sezione BACKUP, senza "FILE" né una posizione sul NAS.
- **Accessibilità della selezione.** In Libreria le celle selezionate non lo dicono all'accessibilità: `checked` e `selected` restano falsi. Si vede solo dal segno di spunta.
- **O-1, O-2.** I tre fixture finti non sono stati caricati: giusto, E-M12. Il media dei due `Screenshot_*.png` (identici, un media solo) non è nel conteggio di `mcg-prova` (192 media su 201 file in `DCIM/mcg-prova`). Non ho potuto vedere dal NAS se manca: §R.4.
- **Doppioni nei fixture.** Alcuni file di `mcg-prova` hanno gli stessi byte e diventano un solo media: per esempio `20240312_122000.jpg` e `.heic`, oppure 6 JPEG "da app diverse". 201 file e 192 media in `DCIM`: è voluto (copie identiche) e va tenuto presente nei conti.
- **Il copione.** Le funzioni `h.ps1` di `docs/prove-campo.md` non le ho usate: ho usato aiutanti miei, provati prima, con lo stesso controllo "solo la nostra app in primo piano". `uiautomator dump` a volte restituisce lo schermo di qualche istante prima: prima di toccare per coordinate conviene uno screenshot.

## R.3 Stato lasciato
- **Wi-Fi:** acceso.
- **App:** in primo piano. Backup "Tutto al sicuro · 296 media al sicuro sul NAS", nessuna pausa attiva. Cartella del dispositivo "Telefono di test". Sorgenti: le due `mcg-prova`.
- **Telefono:** `mcg-prova` resta, con **302 file**. Le **6 foto liberate** da F37/F38 sono nel cestino di Samsung Gallery: `IMG_mcg_0044.jpg` (Pictures), `IMG_mcg_0085.jpg`, `IMG_mcg_0129.jpg`, `IMG_mcg_0153.jpg`, `IMG_mcg_0177.jpg`, `IMG_mcg_0193.jpg` (DCIM). Le copie sono sul NAS. Per riaverle sul telefono: Galleria → Cestino → Ripristina.
- **NAS:** `test/mcg-campo/Backup/Telefono di test/…` con i 296 media. Non verificato dal PC.
- **Libreria:** la vista potrebbe essere passata a "Tutte" (3 colonne) durante la navigazione. Prima era a 4 colonne con le intestazioni per giorno.

## R.4 Stop: login di prova dal PC rifiutato
`area.ps1 -Mode backup -Stage verify -Manifest <manifest-test.txt>` (sola lettura) si è fermato in 25 s:

```
BackupNasIntegrationTest > verificaLAreaDalManifestoDelTelefono FAILED
java.lang.AssertionError: la sessione di prova non parte: Auth (nessun altro tentativo)
```

Un solo tentativo, nessun altro: come da regole mi sono fermato e non ho lanciato altre prove dal PC. Probabile causa: la password di `test` è cambiata con la nuova configurazione dell'app e `%USERPROFILE%\.mycloudgallery\nas-it.properties` (che non ho aperto) ha ancora la vecchia. Per l'utente:
1. Aggiornare in quel file la password di `test` (e di `test2`, se cambiata).
2. Lanciare la verifica:
   `powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest <manifest-test.txt>`.
   Il telefono ha 6 file in meno, liberati ma ancora sul NAS: per il confronto col telefono si usa `s25.ps1 -Step verify ... -AllowFreed`.
3. Atteso: "TUTTI GLI INVARIANTI RISPETTATI". Unica eccezione possibile: il media dei due `Screenshot_*.png` (O-2), che è una nota, non un guasto.

## R.5 Da rifare dopo le correzioni
- F30 completa (100 interruzioni miste) solo dopo D-6 e D-7: oggi il backup si ferma alla prima caduta del Wi-Fi o, a volte, alla prima morte.
- F23 (ripresa automatica).
- F22 con il riepilogo, passando da "Vedi il riepilogo → Inizia ora" senza scegliere prima la cartella, oppure dopo D-9.
- I2 di F37 dopo D-10.
- T-09 (f) solo dopo D-3, o su una semina che si può buttare.


---

# Verifica dal PC e prove 4A/4B con la sola app (20/10/2026, 19:19–20:00)

## V.1 Verifica del NAS dal PC (un solo tentativo, autorizzato dopo l'aggiornamento di `nas-it.properties`)

| | |
|---|---|
| **Comando** | `area.ps1 -Mode backup -Stage verify -Manifest manifest-test.txt` (sola lettura). Verificatore del commit `2a4bc4a`, Gradle 6 min 11 s |
| **Esito** | **passata**. "file riletti con SHA-256: 296 (5.243.013 KiB) · TUTTI GLI INVARIANTI RISPETTATI". Login accettato al primo tentativo. Rapporto: `core-backup/build/chaos/backup-verify-20261020-173044.txt` |
| **Cosa dice** | Ogni file di `test/mcg-campo/Backup/` ha i byte di un file del telefono: nessun file a metà, nessun doppione. Ogni file del manifesto (305 righe, 296 contenuti distinti) è sul NAS con lo stesso SHA-256. Nessun temporaneo `-bk-` rimasto. Le 6 foto liberate da F37/F38 sono ancora sul NAS (il verificatore le conta). |
| **F21 / T-11 (SHA-256)** | **passata**. Il confronto riguarda tutti i 296 contenuti, compresi il JPEG con GPS dei fixture e i due video da 2,5 GB: i 296 file sul NAS sono stati riletti per intero e il loro SHA-256 è uguale a quello che `adb shell sha256sum` ha dato sul telefono (il manifesto). Vale nella sola condizione "tutti i permessi dati": le varianti con i permessi tolti sono del blocco B. |
| **O-2** | **Non si presenta**: anche i due `Screenshot_*.png` (un contenuto solo) sono sul NAS, altrimenti l'invariante 4 sarebbe fallito. |
| **O-1** | I tre fixture finti non sono nel manifesto usato e non risultano caricati: i file in `Backup/` sono esattamente 296. |
| **F30 (invarianti)** | Dopo tutta la storia del backup (caduta del Wi-Fi di F23, morti di F24, Ferma + `force-stop`, 11 interruzioni delle due corse di F30) l'area è pulita. La prova delle 100 interruzioni resta da rifare dopo D-6 e D-7. |

## V.2 Prove 4A/4B fatte con la sola app

| Id | Esito | Note |
|---|---|---|
| A.12 F39-adb (1): provider dei documenti dalla shell | **come atteso** | Risponde con la radice (`root_id=mcg-root`, `document_id` opaco, nessun percorso): la shell ha `MANAGE_DOCUMENTS` |
| A.12 (1b): `FileProvider` non esportato | **passata** | `SecurityException: ... not exported from UID 10644` |
| A.12 (2): selettore con `GET_CONTENT image/*` | **passata** | "Scegli da MyCloud Gallery · Foto", celle "solo sul NAS"; Indietro lo chiude e torna all'app |
| A.12 (3): `PICK` con `vnd.android.cursor.dir/image` (IN-13) | **passata** | Si apre lo stesso selettore; Indietro lo chiude |
| A.12 (4): `SEND` con un `file://` | **passata** | "Salva sul NAS · Niente da salvare · 1 indicati come percorso del telefono, non come file"; `no_backup/incoming` vuota |
| A.12 (5): `SEND` con un indirizzo del nostro `FileProvider` | **passata** | "Niente da salvare · 1 sono file di MyCloud Gallery stessa"; niente copiato |
| GA-2: pressione lunga poi Indietro | **passata** | Indietro chiude la selezione, l'app resta aperta (controllato con uno screenshot) |
| GA-8: selezione di un media solo-NAS → "Elimina dal telefono" | **passata** | "Questi elementi sono solo sul NAS · Non c'è niente da eliminare dal telefono…", nessun foglio di sistema. "Condividi" non l'ho provato perché aprirebbe il foglio di Android con le altre app |
| NA-3: Cartelle NAS → Aggiorna | **passata** | L'elenco si rilegge, nessun errore. `mcg-campo` contiene solo `Backup/`: i campioni di F1 non ci sono (preparazione 2.8 del copione) |
| ON-6 (dispositivo già configurato) | **passata** | Famiglia → "Zona delicata · Reimposta la famiglia": la pagina spiega le conseguenze ("I dati restano dove sono…") e il tasto è "Reimposta la famiglia…", un passo in più. Non l'ho toccato. "Ricominciare da zero" sta nella configurazione, non qui |
| ON-7: Rete e VPN → "Configura il controllo" | **passata** | Apre il passo della VPN ("Passo 11 di 14"); Indietro torna a Rete e VPN, non al riepilogo |
| RE-4: Diagnostica → "Ultimi esiti ed errori" | **passata, con una nota** | 50 voci; "12 voci richiedono attenzione", quasi tutte "Verifica del NAS: non riuscito · È successo qualcosa che l'app non si aspettava…": sono gli errori di D-2 a ogni avvio a freddo, che l'utente vede come avvisi |
| BK-9: pagina Backup | **passata** | Nessun tasto "Apri Impostazioni, Aree" (c'è "Apri le impostazioni" solo per "Scegli cosa salvare") |
| FF-11: "Da completare" | **non applicabile** | Con la nuova configurazione la sezione non c'è |
| F15 (4B), solo dentro `mcg-campo/` | **passata la parte possibile** | In `test/mcg-campo/`: "Nuova cartella" `prova-4b` (create_folder: done), "Rinomina" in `prova-4b-rinominata` (rename: done), sottocartella `sotto` creata dentro e "Sposta" in `mcg-campo/` (move: done, il foglio dice "Cartelle intere"). Un nome uguale a uno esistente è rifiutato prima di partire ("Esiste già qualcosa con questo nome."). **Non fatte**: Elimina e Ripristina dal cestino del NAS, perché il cestino sta in `.mycloudgallery/trash/` alla radice della share, fuori da `mcg-campo/`; il file aperto da un editor del PC (blocco B). Restano sul NAS due cartelle vuote di prova: `mcg-campo/prova-4b-rinominata` e `mcg-campo/sotto` |
| F10: viewer di un solo-NAS, video, Wi-Fi spento | **non eseguita** | Nell'area di prova non ci sono media solo-NAS separati: i 6 file alla radice di `test` sono uniti, come copie simili, a foto vere del telefono (celle "2 scatti, copia sul NAS da verificare"). Aprire quelle celle vuol dire guardare foto vere. Da fare con i campioni F1 |
| FF-15: NAS di nuovo raggiungibile dopo 60 s senza Wi-Fi | **non eseguibile** (O-6) | Il Wi-Fi è tornato da solo dopo 3,7 s (vedi O-6) |
| FF-8 / RE-3: lavori a processo freddo | **parziale** | `cmd jobscheduler run -f -n androidx.work.systemjobscheduler <pacchetto> <id>` con il processo morto: il processo parte e la **scansione del NAS fallisce ogni volta** (2 su 2, `tappa non riuscita` 0,2–0,3 s prima di `sessione pronta`), mentre `IndexSyncWorker` e `PersonalSyncWorker` finiscono bene. Con il processo vivo WorkManager rinvia il lavoro forzato ("being executed before schedule"): così il giro periodico a freddo non si prova davvero |
| F3: verifica senza novità ≤ 60 s | **non giudicabile** | La share `test` ha migliaia di cartelle in `mcg-it/` (resti delle prove dal PC): ogni verifica fa giri da 60 s con 1.500–3.500 cartelle e dura vari minuti. Dopo ogni morte del processo la scansione successiva "rileva" di nuovo centinaia di file ("rilevati=732…"), come se il giro interrotto non fosse salvato. Da rivedere dopo la pulizia di `mcg-it` (A.13 del copione) |
| GA-3, F11, F12, R2/R8/R9/R10/R11 della 4B | **non eseguite** | GA-3: le celle visibili sono i file di 44 byte senza miniatura, le altre sono foto vere. F11: scrive fuori da `mcg-prova`, sul telefono. F12: cambia la qualità e lavora a lungo. R2/R8/R10/R11: cancellano o spostano, oppure servono una cartella grande o un video del NAS che non c'è in `mcg-campo`. R9: serve un'operazione lunga |

## V.3 Difetti e osservazioni nuovi

- **D-2 sale di gravità (media → alta, da confermare).**
  - Si ripresenta a **ogni** avvio del processo, anche in background (2 su 2 con un lavoro forzato), oltre agli 11 su 13 di prima.
  - Il giro periodico della scansione (6 ore) di solito parte proprio con il processo morto. Se la tappa che parte a freddo fallisce sempre, la Libreria non vede mai in background le novità del NAS. Questo l'ho dedotto dal codice e dai log: il lavoro forzato non l'ho potuto provare, perché WorkManager lo rinvia.
  - In più, ogni fallimento resta in Diagnostica come avviso "È successo qualcosa che l'app non si aspettava".
- **O-6 (strumento di prova).**
  - Sul S25 il Wi-Fi spento da `s25.ps1` torna da solo dopo circa 3,7 s.
  - Lo riaccende la funzione automatica del Wi-Fi di One UI: log `AutoWifiController … set Geofence ENTER`, poi `setWifiEnabled package=android … enable=true`.
  - Quindi tutte le "cadute del Wi-Fi" di oggi (F23 da 30 s, F30 da 3–20 s, FF-15 da 60 s) sono durate pochi secondi.
  - Le conclusioni su D-6 non cambiano: il lavoro finisce "DONE" già alla caduta.
  - Le prove che chiedono un Wi-Fi spento a lungo (FF-15, F10 punto 3, E-L4) vanno fatte con quella funzione spenta. È un'impostazione del telefono: la cambia l'utente (blocco B).
- **O-7.** Durante le prove è comparso sopra la nostra app un riquadro di un'altra app ("Aggancio KeyVoice · Cerco il campo attivo…"), dopo che avevo scritto nel campo del nome della cartella. Non l'ho toccato. Va tenuto presente per i tocchi a coordinate.

## V.4 Stato lasciato
- **Wi-Fi:** acceso.
- **Batteria:** 59%.
- **App:** in primo piano. "In casa · NAS collegato", "Tutto al sicuro", nessuna pausa.
- **Telefono:** `mcg-prova` con 302 file. Le 6 foto liberate sono nel cestino di Samsung Gallery (elenco in §R.3).
- **NAS:** in `test/mcg-campo/` restano `Backup/` (296 media verificati) e le due cartelle vuote `prova-4b-rinominata` e `sotto`. Le toglie l'utente dal PC, o un'altra sessione con "Elimina" dell'app, che passa dal cestino in `.mycloudgallery/`.
- **Accessi al NAS dal PC:** dopo la verifica di V.1 nessuno.


---

# Riprova con `6c19a33` (20/10/2026, 21:02–22:55)

| | |
|---|---|
| **Ambiente** | APK di debug di `6c19a33`, installato dall'orchestratore con `install -r` (dati conservati): account `test`, area privata `test`, area comune `test-comune`. "Attiva Wi-Fi automaticamente" è spento: le cadute del Wi-Fi ora durano quanto chiesto (controllato: `wifi_on=0` per tutti i 30 s di F23). Batteria dal 68% al 63%. |
| **Nuova semina** | `s25.ps1 -Step clean -Yes` (alle 21:12), poi `-Step seed` con il giro `20261020-211253-5c0e6864`, `-Seed 42 -Photos 520 -BigVideos 3 -BigVideoMb 3072`, fixture rigenerati senza i tre finti. Risultato: **528 file, 9,66 GB**, tre video da 3 GB. Le copie del giro precedente sul NAS (296) non le ho toccate. Il manifesto nuovo e la sua copia per la verifica stanno nella cartella di appoggio della sessione, non nel repo. Per la verifica dal PC il manifesto ha in più 296 righe `pre=` (i file del giro precedente, con il loro percorso, presi dal catalogo dell'app). |
| **Esito in breve** | Corretti e provati: D-2, D-6 (F23 e la caduta lunga), D-7 (morti singole) e D-10 (tutte e due le varianti). D-11 è migliorato. D-9 è provato solo in parte. **F30 non è passata**: dopo le 100 interruzioni il backup non arriva in fondo da solo. Due dei tre video restano "in caricamento" da più di 25 minuti e i loro temporanei sul NAS risultano occupati (`FileBusy`, D-13). La verifica dal PC (un tentativo, login accettato) non trova file a metà, doppioni né sostituzioni: solo quei 2 file che non si aprono. |

## Q.1 Esiti

| Id | Esito | Misure e note |
|---|---|---|
| D-2 (3 avvii a freddo) | **passata** | 3 volte `force-stop` e poi `am start`: nessun `NasScanWorker: tappa non riuscita`. "sessione pronta" 0,7–1,4 s dopo l'avvio, poi i giri "Verify". Al 3° avvio la scansione completa dell'area arriva a `completo=true` in circa 2 minuti. In tutta la sessione: **0 fallimenti su 74 avvii del processo** (prima erano 11 su 13). |
| D-9 (riepilogo dalla cartella del dispositivo) | **non provabile del tutto** | La cartella del dispositivo era già scelta ("Telefono di test", dal giro di prima) e si sceglie una volta sola: senza cancellare i dati dell'app la strada del primo backup non si rifà. Provata la seconda parte: con 3 video in coda la pagina dice "3 in coda · Riprende al prossimo giro. **Da caricare: 4,4 GB**", non più 0 B. |
| D-11 (avanzamento) | **migliorato, con un resto** | Durante i video il totale comprende i video e i byte salgono: "Video: 213 di 431 · 109 MB di 9,7 GB · circa 46 min", poi "210 MB di 9,7 GB · circa 38 min", poi "956 MB di 9,7 GB · circa 28 min". Il tempo che manca è in minuti. Resta: dopo una morte o un nuovo "Fai ora" il conteggio riparte dal lotto ("Video: 0 di 3 · 0 B di 4,4 GB · circa 13 min"). La capsula della Libreria ha detto "Backup in corso: 0 di 3 · circa 13 min" anche mentre i due video bloccati di D-13 non andavano avanti. |
| Backup in background | **osservazione** | Dopo la semina il backup è partito da solo con le regole: 215 foto nuove (giro "photos" alle 21:14), poi i video. "Ferma" e poi "Riprendi" l'hanno fatto diventare un lavoro dell'utente ("avviato da te"). |
| F23 (Wi-Fi spento 30 s durante i video, dopo circa 1,1 GiB) | **passata** | Nessun "lavoro del backup finito: DONE". Durante lo stacco la pagina dice "Backup fermo · Android ha fermato il lavoro. Riprende da solo." Wi-Fi riacceso alle 21:18:11, "sessione pronta" alle 21:18:15, caricamento ripartito da solo entro le 21:18:22 (circa 11 s), pagina "Backup in corso, avviato da te". |
| F23 lunga (stacco di oltre 10 minuti) | **passata la parte di D-6, con un difetto** (D-12) | Sei stacchi da 120 s di fila (1 s di Wi-Fi fra uno e l'altro, 21:24–21:36), lo strumento ammette al massimo 120 s a stacco. Alle 21:35:43 compaiono "rete non tornata in 600 s: il lavoro dell'utente aspetta il Wi-Fi" e "lavoro del backup finito: **AWAIT_NETWORK**". Al ritorno del Wi-Fi (21:36:12) `UserResumeWorker` parte dopo 3 s, ma **un secondo prima della sessione NAS pronta**: la sua tappa dice "Wait" e il lavoro si riprogramma **fra 15 minuti**. Nel frattempo la pagina diceva "Android ha fermato il lavoro. Riprende da solo." e non riprendeva. È ripartito alle 21:37 con "Fai ora" (necessario per cominciare F30). |
| F24 / D-7 (7 morti, `-WifiPercent 0`, 21:18–21:23) | **passata** | Mai un "DONE" sotto il secondo, nessun "DONE" in tutto. Dopo ogni morte il caricamento è ripreso: 120–590 MiB caricati in ogni intervallo fra due morti. Dopo 4 morti su 7 compare "giro photos" subito dopo "sessione pronta". Nelle altre il lavoro è ripartito senza un giro finito nel log, ma i byte salivano. Un paio di volte Android ha messo un ritardo di 11 s al riavvio del servizio. |
| F30 (100 interruzioni miste, seme 7, 21:38–22:28) | **fallita** (D-13) | 58 morti e 42 cadute del Wi-Fi vere (3–20 s). Durante le interruzioni nessun "DONE", nessuna attesa della rete scaduta, nessun crash, 58 avvii del processo. Le interruzioni n. 92 e 94 hanno fatto cadere il lavoro dell'utente nel ritardo crescente di Android (prossima partenza 22:32, ripartito da solo). **Ma**: alle 22:34 il giro dei video finisce in 30 s con "fatti=0 · aperti=3 · stop=complete" e il lavoro chiude "DONE"; la pagina dice "3 in coda · Al prossimo giro". "Fai ora" (22:37) fa lo stesso. Dopo, uno dei tre video si sblocca: caricato alle 22:42, verificato e chiuso da `UserResumeWorker` alle 22:49. Gli altri due sono **fermi dalle 22:24 e dalle 22:25** (punti di ripresa 1,74 GB e 0,38 GB su 3 GB). Alle 22:55 la pagina dice "2 in coda · Al prossimo giro". |
| F30, verifica dal PC (un tentativo, sola lettura, `-NotSettled`) | **quasi pulita** | Login accettato. "file riletti con SHA-256: 726 (5.243.032 KiB) · **2 VIOLAZIONI** [11. il NAS si legge] un file non si rilegge: apertura non riuscita (**FileBusy**)", due volte. Nessun file a metà visibile, nessun doppione, nessun file del giro di prima cambiato (296 righe `pre=`). I due file occupati sono quasi certamente i temporanei dei due video bloccati (il rapporto non dà nomi). La verifica "a lavoro finito" non si può fare finché quei due video non finiscono. |
| F30, telefono | **passata** | `s25.ps1 -Step verify -SkipNas -AllowFreed`: impronte uguali al manifesto; mancano solo i 3 file liberati da F37. |
| F37 / D-10, "Consenti" dopo la morte | **passata** | 3 foto → "Libera ora" → dialogo "spostare 3 foto nel cestino?" → `kill -9` dell'app (22:40:09) → "Consenti". I file escono dal telefono. Dopo il riavvio dell'app il giro 3 è `done` con **i 3 elementi `freed`** (22:41:13). |
| F37 / D-10, "Rifiuta" dopo la morte | **passata** | Altre 3 foto (su 4 scelte, una era già solo sul NAS) → "Libera ora" → `kill -9` → "Rifiuta". I file restano sul telefono e il giro 4 resta `ready` con gli elementi `in_dialog`. Riaperta la pagina "Libera spazio" ("Sto contando cosa si può liberare…"), diventano **`kept`** (22:44:52). |
| D-3 (testi) | **visto** | Raccolte → "Eliminati di recente · Su questo telefono si ripristina da Galleria → Cestino". |
| Wi-Fi automatico (O-6) | **risolto per le prove** | Con l'impostazione spenta dall'utente le cadute durano il tempo chiesto. |

## Q.2 Difetti nuovi

### D-13 (alta, blocca F30): dopo molte interruzioni due video restano "in caricamento" con il temporaneo occupato sul NAS, e il backup si chiude come finito
**Passi.**
1. Primo backup di tre video da 3 GB.
2. `s25.ps1 -Mode backup -Step interrupt -Interruptions 100 -Seed 7` (58 morti e 42 cadute del Wi-Fi).
3. Alla fine aspettare la ripresa da sola, poi toccare "Fai ora".

**Cosa si vede.**
- Ogni giro dei video dura 30 s e finisce con "giro videos: fatti=0 · aperti=2 (o 3) · stop=complete", poi "lavoro del backup finito: DONE" (oppure `UserResumeWorker: tappa: None`).
- Gli elementi restano `uploading` senza nessun aggiornamento.
- Pagina: "2 in coda · Al prossimo giro".
- La verifica dal PC trova 2 file che non si aprono (`FileBusy`).

**Ipotesi.**
- I temporanei sono tenuti aperti sul NAS da handle di sessioni morte: una morte del processo con il Wi-Fi giù, quindi senza chiusura della connessione, oppure handle "durevoli" di SMB3 che il NAS conserva per la riconnessione.
- Il motore li trova occupati e chiude il giro come "complete" invece di dire "occupato, riprovo": così il lavoro finisce e il backup aspetta 6 ore.
- Uno dei tre si è sbloccato da solo dopo circa mezz'ora; gli altri due erano ancora occupati dopo 30 minuti.

**Da guardare:**
- che cosa fa il motore quando `reopenTemp` risponde `Locked`/`FileBusy` su un **proprio** temporaneo;
- se le aperture usano handle durevoli;
- la durata del blocco sul WD.

**Log:**
```
22:34:29.325 D/MyCloud/BackupStages: giro videos: fatti=0 collegati=0 persi=0 falliti=0 aperti=3 stop=complete
22:34:29.329 D/MyCloud/BackupJobService: lavoro del backup finito: DONE
22:37:32.928 D/MyCloud/BackupStages: giro videos: fatti=0 ... aperti=3 stop=complete        (dopo "Fai ora")
22:49:42.911 D/MyCloud/BackupStages: giro videos: fatti=1 ... aperti=2 stop=complete        (UserResumeWorker)
verifica dal PC, -NotSettled: 2 VIOLAZIONI [11. il NAS si legge] ... apertura non riuscita (FileBusy)
```

### D-12 (media): dopo una caduta lunga `UserResumeWorker` parte prima della sessione e si rimanda di 15 minuti
**Passi.**
1. Backup dell'utente in corso.
2. Wi-Fi giù per più di 10 minuti: arriva `AWAIT_NETWORK`.
3. Riaccendere il Wi-Fi.

**Cosa si vede.** `UserResumeWorker` parte 3 s dopo il ritorno del Wi-Fi (vincolo di rete soddisfatto). La tappa risponde "Wait" perché la sessione NAS arriva solo 1 s dopo ("sessione pronta" alle 21:36:16, la tappa alle 21:36:15). Il lavoro si riprogramma con il ritardo di un quarto d'ora. Intanto la pagina dice "Riprende da solo".

**Rimedio probabile.** Prima della tappa aspettare la sessione o la raggiungibilità del NAS (come `awaitReady` di D-7), oppure trattare "NAS non ancora pronto subito dopo un cambio di rete" come un'attesa breve, non da 15 minuti.

### Osservazioni
- **Traffico misurato.** Il contatore `wlan0` comprende anche le altre app. Alle 22:35 il Wi-Fi trasmetteva circa 12–14 MB/s mentre i lavori della nostra app erano fermi (nessun lavoro attivo; i byte di `netstats` per la nostra app non salivano). Per i byte del backup fa fede il diario (`resume_at`, `uploaded`) o un contatore per UID, non `/proc/net/dev`. Anche le misure di traffico della mattina vanno lette così.
- **Ritardo di Android.** Dopo molte morti di fila Android rimanda il lavoro dell'utente con un ritardo crescente: alle 22:25 la prossima partenza era fra 8 minuti. È il comportamento del sistema e il backup è ripartito da solo; va tenuto presente nei tempi di F30.
- **Scansione del NAS.** Ogni giro "Verify" ritrova come nuovi centinaia di file ("rilevati=700…") anche senza novità. Sono quasi certamente i resti di `mcg-it/`: vedi F3 in §V.2.
- **Tema.** Il telefono è passato al tema scuro durante la sessione. L'app lo segue, quindi gli screenshot di prima e di dopo hanno colori diversi.

## Q.3 Stato lasciato
- **Wi-Fi:** acceso.
- **Batteria:** 63%.
- **App:** in primo piano, nessuna pausa. La pagina dice "2 in coda · Al prossimo giro": i due video bloccati da D-13.
- **Telefono:** `mcg-prova` con 525 file. Le 3 foto liberate da F37 sono nel cestino di Samsung Gallery: `IMG_mcg_0293.jpg`, `IMG_mcg_0328.jpg`, `IMG_mcg_0338.jpg`. Restano anche le 6 del giro di prima, se l'utente non le ha già tolte.
- **NAS:** `test/mcg-campo/Backup/Telefono di test/` con 726 file (296 del giro di prima, 430 di questo). Due temporanei dei video sono in `.mycloudgallery/tmp` e risultano occupati. Restano anche le due cartelle vuote `prova-4b-rinominata` e `sotto` (§V.2).
- **Accessi al NAS dal PC:** uno solo in questo giro, la verifica "a metà", con login accettato.
- **Da rifare dopo la correzione di D-13 e D-12:**
  - F30 completa, con la verifica "a lavoro finito";
  - la ripresa dopo una caduta lunga.


---

# Riprova con `e5f3c7f` (21/10/2026, 01:21–04:20)

| | |
|---|---|
| **Ambiente** | APK di debug di `e5f3c7f` (contiene anche `9e0372a`), installato dall'orchestratore con `install -r` alle 01:21:20. Account `test`. "Attiva Wi-Fi automaticamente" spento. Batteria dal 79% al 67–71% (carica lenta sul cavo). |
| **Nuova semina** | `clean` alle 01:23, poi `seed` con il giro `20261021-012342-3f111b8b`, `-Seed 43 -Photos 520 -BigVideos 3 -BigVideoMb 3072`, fixture senza i tre finti: 528 file, 9,66 GB. Nel manifesto della verifica ci sono anche **729 righe `pre=`**: tutti i file che erano già in `test/mcg-campo/Backup/` (296 del primo giro e 433 del secondo), con percorso e impronta presi dal catalogo dell'app. I manifesti stanno nella cartella di appoggio della sessione. |
| **Esito in breve** | **Tutte le prove richieste sono passate, F30 compresa.** Il backup con 100 interruzioni miste è arrivato in fondo **senza nessun tocco** (03:55:36, "campagna chiusa (niente in coda)"). La verifica dal PC a lavoro finito (un tentativo, login accettato) dice **TUTTI GLI INVARIANTI RISPETTATI** su 1.162 file riletti (24,1 GB), nessun temporaneo rimasto. Il telefono è invariato. Due note, non difetti: durante le interruzioni il backup quasi non avanza, e un video ha perso 0,9 GB per ripartire su un temporaneo nuovo. I due video fermi della volta scorsa non si sono potuti riprovare: si erano già chiusi da soli durante la notte. |

## E.1 Esiti

| Id | Esito | Misure e note |
|---|---|---|
| 1. I due video fermi di F30 | **non provabile con il nuovo APK** | Si erano già chiusi durante la notte con l'APK vecchio (`6c19a33`): elemento 513 `done` alle 00:28:21, 514 alle 00:38:35 ("campagna chiusa (niente in coda)" alle 00:37:20). I loro temporanei erano occupati dalle 22:24–22:25: **il NAS li ha tenuti occupati circa 2 ore**, poi li ha rilasciati e il motore vecchio ha ripreso da solo con i giri di ripresa. Coerente con le misure sul NAS di cui parli. Quei temporanei sono spariti con la pubblicazione (00:28 e 00:38). La strada nuova "temporaneo occupato → temporaneo nuovo" l'ho vista però dal vivo nelle prove 4 e 3 (sotto). |
| 2. D-7 a monte (3 avvii a freddo) | **passata** | 3 cicli `force-stop`/`am start`, 6 schermate ciascuno fra 0,5 e 3,2 s dall'avvio: si vedono solo il logo, una Libreria vuota per un attimo e poi la Libreria con le celle. **Mai** il benvenuto, "non configurato", "fuori dalla famiglia" o la pagina delle chiavi. `TrustSyncWorker: controllo del registro: DONE` 3 volte su 3, mai `NOT_CONFIGURED`. |
| D-2 (di passaggio) | **passata** | 0 `NasScanWorker: tappa non riuscita` su 64 avvii del processo in tutta la sessione. |
| 4. D-12 (Wi-Fi giù più di 10 minuti durante un lavoro dell'utente) | **passata** | Lavoro dell'utente ottenuto con "Ferma" → "Riprendi" (01:26). Sei stacchi da 120 s (01:26:45–01:38:54). Durante lo stacco il lavoro non finisce "DONE": chiude 3 volte con `RESCHEDULE` (01:29, 01:32, 01:36) e Android lo riprova; non si arriva ad `AWAIT_NETWORK`. Wi-Fi riacceso alle 01:38:54: giro alle 01:38:54, "sessione pronta" alle 01:38:58, e alle 01:38:59 `BackupUpload: temporaneo occupato sul NAS da 12 min: si riparte da capo su un temporaneo nuovo (tentativo 1)`. **Ripresa in circa 5 s**; il caricamento riprende subito (12–15 MB/s). |
| D-13 (temporaneo occupato) | **passata** | Due volte: alle 01:38:59 (video 1160, occupato da 12 min) e alle 02:04:43 (video 1161, occupato da 16 min; aveva 0,91 GB caricati, persi) il motore riparte su un temporaneo nuovo (`-bk-1160-1`, `-bk-1161-1`). Il terzo video (1162) aveva il temporaneo occupato dalle 01:57: alle 03:31 risultava di nuovo libero e il caricamento è ripreso sullo stesso temporaneo. Mai più "stop=complete" con video fermi: dopo F30 i giri dei video finiscono con `stop=budget` e `aperti` che scende da 3 a 1 fino alla chiusura. Nel log la parola `stop=busy` non compare: le attese per i file occupati non producono una riga di giro. |
| 5. D-11 (conteggio dopo una morte) | **passata** | Durante la campagna la pagina diceva "Video: 428 di 431 · 134 MB di 9,7 GB · circa 37 min": il conteggio è cumulativo dall'inizio (428 = foto e media già fatti). Dopo le morti di F30 la capsula diceva "3 in coda" con lo stato del momento ("Al prossimo giro", "Attesa Wi-Fi"), mai "0 di 3". |
| 3. F30 (100 interruzioni miste, seme 7, 01:41–02:32) | **passata** | 58 morti e 42 cadute vere del Wi-Fi (3–20 s). 59 avvii del processo, nessun crash, nessun "lavoro del backup finito: DONE". **Durante le interruzioni il backup quasi non avanza**: in 51 minuti il video 1160 è passato da 0 a 128 MiB, un altro è ripartito da capo. Ogni morte fa ripartire il lavoro dell'utente con la coda di Android, che lo rimanda sempre di più ("Num failures: 3", ritardo crescente); ogni caduta del Wi-Fi lascia temporanei occupati. Finite le interruzioni Android ha fatto ripartire il lavoro da solo alle 02:37:49, dopo il suo ritardo (+5 min), **senza tocchi**. Caricamento e verifica dei tre video da 3 GB: 1160 e 1161 chiusi fra le 03:12 e le 03:31, 1162 (il suo vecchio temporaneo era tornato libero) caricato alle 03:39, verificato alle 03:49 e chiuso alle 03:55:36 con "campagna chiusa (niente in coda)" e "lavoro del backup finito: DONE". Dall'avvio della semina alla fine: 2 h 31 min, di cui 51 min di interruzioni. **Nessun tocco su "Fai ora"** in tutta F30 e dopo. |
| F30, verifica dal PC (un tentativo, a lavoro finito) | **passata** | `area.ps1 -Mode backup -Stage verify` con il manifesto nuovo e le 729 righe `pre=`: "file riletti con SHA-256: **1162** (24.117.430 KiB) · **TUTTI GLI INVARIANTI RISPETTATI**". Nessun file a metà, nessun doppione, nessun file dei giri precedenti cambiato, ogni file del telefono sul NAS. Login accettato al primo tentativo. Rapporto: `core-backup/build/chaos/backup-verify-20261021-012342.txt` |
| F30, telefono | **passata** | `s25.ps1 -Step verify -SkipNas`: 528 file, impronte uguali al manifesto, nessun file liberato o cambiato |
| F30, `.mycloudgallery/tmp/` | **pulita** | L'invariante 6 "a lavoro finito" della verifica (nessun temporaneo `-bk-` del backup) è rispettato. Quindi anche i due temporanei abbandonati di D-13 (`-bk-1160`, `-bk-1161`) non c'erano più alle 04:00 circa. Il momento esatto in cui sono spariti non l'ho visto: dal telefono non si guarda il NAS |

## E.2 Osservazioni

- **Velocità sotto interruzioni.** Con un'interruzione ogni 6–40 s il backup dei video in pratica si ferma. Il lavoro dell'utente riparte a ogni morte, ma il ritardo crescente di Android, l'attesa della sessione e i temporanei occupati dopo le cadute del Wi-Fi mangiano il tempo fra due colpi. Dopo le interruzioni si riprende bene, ma l'utente vede un backup lungo il doppio. Non è un difetto di correttezza.
- **Video ricaricati da capo.** Il video 1161 ha perso 0,91 GB caricati quando il suo temporaneo era occupato da 16 minuti. È la regola di D-13 (oltre 10 minuti si riparte da capo). Con le cadute del Wi-Fi che tengono occupato un temporaneo per ore è la scelta giusta, ma costa traffico.
- **Temporanei abbandonati.** I vecchi temporanei occupati (`-bk-1160`, `-bk-1161`) restano in `.mycloudgallery/tmp` finché il NAS non li rilascia; poi la pulizia dei propri file li deve togliere. Alla fine della prova non c'erano più (riga di `.mycloudgallery/tmp/` in E.1).
- **Traffico di altre app.** Durante la sessione un'altra app del telefono ha caricato a 10–15 MB/s per lunghi tratti: rallenta il backup e falsa i contatori di `wlan0`. I tempi qui sopra vengono dal diario dell'app.

## E.3 Stato lasciato
- **Wi-Fi:** acceso.
- **Batteria:** 71%.
- **App:** in primo piano. Capsula "Tutto al sicuro · Oggi alle 03:55", nessuna pausa.
- **Telefono:** `mcg-prova` con 528 file (giro `20261021-012342-3f111b8b`), nessuno nel cestino da questo giro.
- **NAS:** `test/mcg-campo/Backup/Telefono di test/` con 1.162 file dei tre giri. Le due cartelle vuote `prova-4b-rinominata` e `sotto` (§V.2) restano. `.mycloudgallery/tmp` senza temporanei del backup.
- **Accessi al NAS dal PC:** uno solo, la verifica finale.
- **Niente commit.**

