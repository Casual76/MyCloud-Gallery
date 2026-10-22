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


---

# Blocco A, resto (21/10)

| | |
|---|---|
| **Quando** | 21/10/2026, 04:22–07:45 (telefono fino alle 07:11) |
| **Ambiente** | APK di debug di `e5f3c7f` già installato (nessuna installazione), account `test`, area comune `test-comune`. Wi-Fi automatico spento. Batteria dal 72% al 79% (cavo USB). Preset "Massimo", qualità delle anteprime "Standard" (annotati e lasciati così). Registro e file della sessione in `build\campo\20261021-042439-4115219b\` (fuori da git). Gradle sempre con `--max-workers=2` (copia di `area.ps1` in `build\campo\area2.ps1`, cambia solo quello), mai due build insieme. |
| **Esito in breve** | Passate FF-15, F11, GA-8 (Condividi), RE-4, RE-8 e F43-tel sul telefono. **Le prove lunghe dal PC (A.14) non si possono fare**: nel file delle credenziali la seconda share non è scrivibile da `test` (A-1), e ogni giro è rosso per `PermissionDenied`. Per lo stesso motivo la suite di `core-nas` ha 2 test rossi e **non ha pulito `test-comune/mcg-it`**, che ha ancora 3 giri del 19/10. F1, F3, F6a, F10 e FF-8 solo in parte (motivi sotto). F13 non eseguita: installa due APK. Nessun errore di autenticazione. |
| **Dove mi sono fermato sul telefono** | **Alle 07:11, su richiesta del coordinatore** (l'utente porta via il telefono): da lì nessun `adb`, nessun Wi-Fi, nessun `s25.ps1`. L'ultimo comando sul telefono è stato `s25.ps1 -Step verify -SkipNas` (07:10:43–07:10:59), poi ho fermato il mio `adb logcat` sul PC. Le prove del blocco A sul telefono erano già tutte fatte o chiuse con il loro motivo: **non resta niente da riprendere** sul telefono, salvo GA-3 (serve un'area con miniature di prova) e F13 (serve il sì a installare). Dopo le 07:11 solo il PC: la verifica di F43-tel dal PC (sola lettura del NAS) e la suite di `core-nas` per la pulizia. |

## Z.1 Esiti

| Id | Esito | Misure e note |
|---|---|---|
| A.13 suite `core-nas` | **fallita (ambiente, A-1)** | 04:25–04:32, 6 min 30 s. 18 test: 15 passati, **2 falliti**, 1 saltato (`t10`, M9 senza consenso). I due falliti sono le copie fra le due share del file: `t07` (copia lato server tra share: `PermissionDenied`) e `t12` (spostamento tra share: `copyToTemp` rifiutato). Nessun `Auth`. Passati fra gli altri: guardia (103 casi rifiutati senza chiamate), sessione SMB 3.1.1 cifrata, rinomina e cestino (4b-R3, 4b-R4), handle del telefono, ripresa a metà file (R3), handle di una connessione morta (D-13, 330 s occupato). Misure: 1.500 file creati in 9,3 s, elenco a pagine in 167 ms; file da 64 MiB scritto a 10,9 MB/s, letto a 12,5 MB/s con un flusso e a 17,2 MB/s con quattro. |
| A.13 pulizia di `mcg-it` | **a metà** | In `test/mcg-it` c'era solo il giro della suite (1.540 voci): tolto, e `mcg-it` tolta. Le migliaia di cartelle erano già state tolte il 20/10 alle 23:14 (giro della suite `20261020-211450`: 643 + 10.109 + 9.375 voci). **`test-comune/mcg-it` invece è rimasta**: la suite non la vede, perché la seconda share del file non è `test-comune` (vedi A-1). Il riepilogo dice "verificato con 2 account". |
| A.13 avanzi in `test-comune/mcg-it` | **elencati, non tolti** | 3 giri del banco del backup del 19/10: `20261019-181432-f635fa3d` (29 cartelle, `r0`–`r28`), `20261019-181746-a64a8f3c` (405), `20261019-210256-fbc56869` (379). Ogni `r<n>` ha `Foto/` con due JPEG piccoli. In tutto, dalla scansione del telefono, **1.854 cartelle e 1.429 file**. Non so con quale account sono stati creati: la suite non li ha mai letti. Dall'app non si cancellano (fuori da `mcg-campo/`). |
| F1 (A.2) | **parziale** | In `test/mcg-campo/` non ci sono i campioni (formati, cartella musicale, 1.000 foto, video oltre 2 GB): c'è solo il backup. La prima scansione è fatta da giorni e non si rifà. Controllati: il fixture `exif-05-gps-ora-utc.jpg` dalla cartella del backup mostra "12 marzo 2024, 10:15 · Oslo", Informazioni "ora di Oslo, UTC+1", luogo "Oslo, Norvegia, 59,9127° N, 10,7461° E" (giusto, anche GA-6). I media "solo NAS" disponibili sono le foto da 44 B dei giri vecchi: il viewer dice "Non riesco ad aprire questo file". Piede della Libreria: "7.581 foto, 399 video" (comprende il telefono). Copertine e temporanei: non provabili senza campioni. |
| F3 (A.3) | **sopra soglia a freddo, sotto a caldo** | Tre verifiche a 4 minuti l'una dall'altra, con avvio a freddo (`force-stop` e apertura): **90,7 s, 86,5 s, 87,1 s**, 61,7 MB letti ognuna. La share `test` (13 cartelle, 1.168 file) si verifica in **1,3 s**; il resto è `test-comune/mcg-it`: 1.854 cartelle e 1.429 file "rilevati" di nuovo a ogni processo nuovo (D-16). **Verifica a caldo** (stesso processo, 15 minuti dopo, Home e poi primo piano): **38,6 s**, "rilevati=0". Sessioni: una per verifica, nessuna fra due verifiche; la sessione si chiude dopo 5 minuti di inattività e quando l'app va in background. Il "tocco dei dischi" non si legge senza l'esportazione (utente). |
| F6a (A.4) | **parziale** | Il titolo "Tutto preparato" in questa versione non c'è. Diagnostica → Attività: "Preparazione delle anteprime · ACT_RUNNING" (ammessa). Dal registro dei lavori dell'app: preparazione **finita** alle 00:17 (2 lotti, 1,33 MB, 108 s) e alle 06:17 (1 lotto, 1,06 MB, 6,7 s). Spazio di `no_backup`: 6.560 kB → 6.640 kB; `index-outbox` 4 kB, non cresce. Qualità "Standard": 337 kB di anteprime, 69 anteprime. Diagnostica → NAS nella Libreria ha la voce "File vuoti o non media" (`NAS_SCAN_NOT_MEDIA`, informativa); quali formati, solo con l'esportazione. |
| FF-15 (A.5) | **passata** | Wi-Fi spento 60 s (05:09:57–05:10:57). Durante: la capsula dice "VPN spenta · Apri WireGuard" (il telefono è sui dati mobili). Dopo: "sessione pronta" 5 s dopo il ritorno, "In casa · NAS collegato" entro 7 s, senza toccare niente. Nessun lavoro ha scritto "NAS spento". |
| GV (A.6) | **passate le voci rimaste** | GA-8 "Condividi" su 2 media senza copia sul telefono: "Questi elementi sono solo sul NAS · Per condividerli aprili e tocca «Scarica sul telefono»", nessun foglio di sistema. RE-4: "Ultimi esiti ed errori" ha 50 voci, con le durate delle verifiche. RE-8: preset da "Massimo" a "Wi-Fi sempre", riaprendo la pagina è selezionato; rimesso "Massimo". La riga in Impostazioni ha un testo fisso ("Notte o sempre, dati mobili e roaming"), non il preset scelto. GA-3 **non fatta**: le Cartelle NAS mostrano icone, non miniature, e in Libreria le miniature sono foto vere. GA-2, NA-3, ON-6, ON-7, BK-9 e FF-11 erano già fatte il 20/10 (§V.2). |
| F10 (A.7) | **parziale, con un difetto** (D-15) | Nell'area di prova non c'è un video solo-NAS che si riproduca: i video grandi della semina non si riproducono nemmeno dal telefono ("Non riesco a riprodurre il video"). Il viewer lo dice subito, senza chiudersi. Wi-Fi spento 40 s con il viewer di un video solo-NAS: compare "Servono i dati mobili · Questo video pesa 2,68 GB. Guardarlo con i dati mobili?" (non toccato), anche se senza VPN il NAS non si raggiunge. **Tornato il Wi-Fi, il messaggio resta** (controllato dopo 10 e 40 s); va via solo chiudendo e riaprendo il viewer. |
| F11 (A.8) | **passata** | 10 foto solo-NAS (`IMG_mcg_0001`–`0010` del primo giro, 44 B) scaricate dal viewer, una per volta (la selezione della Libreria non ha "Scarica sul telefono"). Una morte del processo (`kill -9`) dopo la terza. In `Pictures/MyCloud Gallery`: 10 file, **SHA-256 uguali al manifesto del primo giro**, `is_pending=0`, nessun "(1)". Il piede della Libreria non cambia (nessun doppione). Informazioni: "SUL TELEFONO · Pictures/MyCloud Gallery" e "BACKUP · Al sicuro sul NAS". **Morte durante un download vero**: video solo-NAS da 2,7 GB, `kill -9` dopo circa 40 s con 495 MB nel file `.pending-…`: dopo il rilancio il file non c'è più e nessuna riga resta in sospeso. |
| F43-tel (A.10) | **passata** (la verifica dal PC con una nota, D-14) | Tre giri di modifiche con `s25.ps1 -Step edit` (24 byte in coda): 20 foto (seme 42) prima delle interruzioni, poi **100 interruzioni** (seme 11: 61 morti, 39 cadute del Wi-Fi da 3–20 s, 05:49–06:40), e durante le interruzioni altri due giri, 20 foto (seme 7) e 60 (seme 13). In tutto **100 modifiche su 95 foto** (3 modificate due volte, 1 tre volte). Con il preset "Massimo" i primi due giri sono partiti da soli e sono finiti in 15–20 s, prima di un'interruzione; il terzo è passato davvero sotto le interruzioni: "Fai ora" (05:57, durante uno stacco del Wi-Fi), 42 fatti alle 05:58, **1 elemento rimasto aperto** (O-8) e chiuso da solo alle 06:15, "Tutto al sicuro" alle 06:39, **senza altri tocchi**. Diario: 1.262 elementi `done`, 100 di tipo `version`, nessun fermo. **Versioni sul NAS** (contate dall'app, `mcg-campo/Versioni/Backup/Telefono di test/mcg-prova/`): **100 file**, 15 in `2024/` e 85 in `2026/10`, cioè un "(originale)" per foto e una "(versione del …)" per ogni modifica in più. Foglio Versioni di una foto modificata: "Versione di adesso · 68 B · sul telefono e sul NAS · Al sicuro" e "Originale · 44 B · nella cartella Versioni · Al sicuro". Telefono: impronte uguali al manifesto con le 100 righe `edit=`. Diagnostica → Backup: nessun problema, nessuna "Versioni in attesa del computer"; in "Ultimi esiti" un solo avviso, `NAS_TIMEOUT` di una caduta del Wi-Fi. Verifica dal PC: con le righe `edit=` il verificatore si ferma sulla guardia (D-14). Rifatta con un manifesto "a stato finale" (per le foto modificate l'impronta nuova al posto delle righe `edit=`; telefono controllato prima con lo stesso manifesto: le impronte tornano): **1.162 file riletti (23 GB) e 15 violazioni, tutte dell'invariante 3** ("un file che c'era prima del backup ha altri byte"). Sono **esattamente** i 15 fixture modificati che il backup aveva collegato a copie già sul NAS dei giri precedenti (le righe `pre=`): la modifica è andata al solito percorso e l'originale in `Versioni/…/2024/` (15 file). È il comportamento delle versioni, non una sostituzione: il manifesto a stato finale non lo sa dire. Nessun file a metà, nessun doppione, nessun temporaneo rimasto, ogni file del telefono sul NAS con l'impronta giusta. |
| FF-8 (A.11) | **non provabile così** | Su questo Android `am force-stop` toglie i lavori dell'app (`dumpsys jobscheduler`: "None"); con `kill -9` restano. Forzati uno per volta a processo morto (`cmd jobscheduler run -f -n androidx.work.systemjobscheduler`): NasScanWorker (due), PreparationWorker, IndexSyncWorker, PersonalSyncWorker, PhotoBackupWorker, VideoBackupWorker, SafetyVerifyWorker. Il processo parte, ma **WorkManager rinvia tutti e 8** ("Status … is ENQUEUED; not doing any work and rescheduling for later execution"): sono lavori periodici non ancora dovuti. Quello che si vede comunque: in F43-tel i lavori ripartiti dopo una morte del processo hanno lavorato (06:15:49, `PhotoBackupWorker` in un processo appena nato: "fatti=1"). **D-2**: 0 "tappa non riuscita" su 75 avvii del processo in tutta la sessione. |
| F39-adb (A.12) | **niente da fare** | Le sei parti da `adb` sono già passate il 20/10 (§V.2). Restano B-S13 (blocco dell'app) e C-26. |
| F43-PC (A.14) | **fallita (ambiente, A-1)** | `run5b`, 400 interruzioni chieste, 05:09–05:42: 1.605 giri **tutti rossi**, 276 interruzioni cadute. Le violazioni sono solo di due tipi, sempre `PermissionDenied` sulla seconda share: "file del PC: cartella non riuscita" (invariante 15) ed "elenco di una cartella non riuscito" (invariante 11). Nessun'altra violazione. Rapporto: `core-backup/build/chaos/backup-area-report-20261021-030940.txt`. |
| F16-PC (A.14) | **fallita (ambiente, A-1)** | Prova corta da 20 interruzioni per vedere la causa prima di lanciare quella da 400: 85 giri rossi in 28 s, tutti `PermissionDenied` sulla seconda share ("cartella non riuscita", "temporaneo non riuscito", "elenco non riuscito"). La prova lunga non l'ho lanciata. Rapporto: `core-nasops/build/chaos/area-report-20261021-035105.txt`. |
| F30-PC (A.14) | **non eseguita** | Stessa causa: il banco vuole la seconda share scrivibile (`BackupAreaRunner`, "le due share di prova, privata e comune"). |
| F13 (A.15) | **non eseguita** | `check.ps1 -Bench` lancia `:benchmark:connectedBenchmarkReleaseAndroidTest`, che **installa** sul telefono la variante `.bench` e l'APK di test e poi li toglie: contro la regola "niente installazioni" di questa sessione. Serve anche "120 Hz, Fluidità adattiva", che imposta l'utente. |
| Pulizia finale di `test/mcg-it` (A.13 di nuovo) | **fatta** | Suite rilanciata alle 07:34 (8 min 10 s), dopo le prove dal PC: tolti da `test/mcg-it` il giro di F43-PC (9.515 voci), quello della prova corta di F16-PC (287) e il proprio (1.540); `mcg-it` tolta. Stesso esito di prima: `t07` e `t12` rossi (A-1), `t10` saltato, nessun `Auth`. `test-comune/mcg-it` resta (A-1). |

## Z.2 Difetti e problemi, in ordine di gravità

### A-1 (alta, ambiente): il file delle credenziali non è come descritto, e la seconda share non è scrivibile da `test`
Non ho aperto il file. Lo deduco da quello che fanno i test:
- `t09` (prova fra due account) **è girata e passata**: il secondo account c'è ancora nel file e il NAS lo accetta ("secondo account: la sua area ok; l'area privata del primo: PermissionDenied"). La pulizia dice "verificato con 2 account".
- "share ammesse 2": il file nomina due share di prova.
- `t07` e `t12` (copie verso la seconda share) danno `PermissionDenied` a `test`. I banchi del PC (F16-PC, F43-PC) falliscono allo stesso modo su ogni giro.
- La pulizia **non ha visto** `test-comune/mcg-it`, che l'app con lo stesso account `test` legge senza problemi: quindi la seconda share del file **non è `test-comune`** (probabilmente è la share privata del secondo account).

Effetti: A.14 non si può fare, la suite di `core-nas` resta rossa e i 3 giri in `test-comune/mcg-it` restano. Per l'utente: §Z.5.

### D-14 (media, strumento di verifica): la verifica dal PC con modifiche nel manifesto si ferma sulla guardia
`area.ps1 -Mode backup -Stage verify` con il manifesto di F43-tel (100 righe `edit=`) si ferma con `AreaViolation: percorso fuori dall'area di prova` (`GuardedReader.list`, chiamato da `BackupAreaVerifier.verify` → `BackupNasScanner.walk`). Con le modifiche il verificatore aggiunge `Versioni/` alla radice della share fra le cartelle da leggere, ma con la recinzione D13 le versioni stanno in `mcg-campo/Versioni/`: è il punto 4 del §8 del copione, che qui non dà un falso rosso sull'invariante 13 ma **ferma tutta la verifica**. La guardia ha tenuto (niente letto fuori). Ho rifatto la verifica con un manifesto "a stato finale" (le impronte delle foto modificate al posto delle righe `edit=`) e ho contato le versioni dall'app.

**Passi.** `s25.ps1 -Mode backup -Step edit -Edits 20`, backup finito, poi `area.ps1 -Mode backup -Stage verify -Manifest <quello con le righe edit=>`.

### D-15 (bassa): il viewer resta su "Servono i dati mobili" dopo il ritorno del Wi-Fi
**Passi.**
1. Cartelle NAS → un video solo-NAS (nell'area di prova: `Backup/Telefono di test/mcg-prova/2026/10/VID_mcg_grande_2.mp4`), con il Wi-Fi spento (`s25.ps1 … -WifiOffMinSeconds 40`).
2. Il viewer chiede "Servono i dati mobili · Questo video pesa 2,68 GB. Guardarlo con i dati mobili?".
3. Riaccendere il Wi-Fi e aspettare: dopo 40 s il viewer chiede ancora i dati mobili. Chiuso e riaperto, prova il Wi-Fi (e qui dice "Non riesco a riprodurre il video", perché il file della semina non è un video vero).

Inoltre, mentre la capsula dice "VPN spenta", offrire i dati mobili non serve: senza VPN il NAS non si raggiunge.

### D-16 (bassa): a ogni processo nuovo la verifica rilegge tutti i file "non media"
`DefaultNasScanner` ricorda i file non media solo in memoria (`notMediaMemory`). Dopo ogni avvio a freddo (apertura, lavoro periodico che parte a processo morto, morte del processo) la verifica li riapre tutti. Con i 1.429 file di `test-comune/mcg-it` vuol dire **61,7 MB letti e 87–91 s a ogni verifica a freddo**, contro 38,6 s a caldo. Su una share vera con molti documenti sarebbe lo stesso. Vicino a NA-8 ("battito a vuoto"). Si vede da: Diagnostica → "Ultimi esiti ed errori" (durate) e `no_backup/diagnostics/events.log` (`NAS_SCAN … bytes=n61740599`).

### Osservazioni
- **O-8 (backup, versioni).** In F43-tel, dopo una caduta del Wi-Fi (05:57:45–05:58:05) la creazione di un temporaneo di una versione ha risposto `Exists` ("operazione fallita: Failure(Exists, FINAL)") e il temporaneo probabilmente era ancora tenuto dal NAS per conto della connessione caduta. Il giro ha chiuso "complete" con "aperti=1" e il lavoro dell'utente "DONE": la pagina diceva "1 in coda · Al prossimo giro". L'elemento è stato chiuso da solo **17 minuti dopo** dal lavoro periodico delle foto. Nessuna perdita, ma per un elemento di tipo `version` non c'è la ripartenza su un temporaneo nuovo di D-13 (`restartOnNewTemp` vale solo per copie nuove e file in arrivo).
- **O-9 (Libreria).** I media che hanno solo la copia del backup sul NAS (telefono vuoto) nelle celle sono "al sicuro sul NAS", mentre l'avviso di GA-8 li chiama "solo sul NAS". Nel pannello Informazioni di un media su telefono e NAS c'è "SUL TELEFONO" ma non la posizione sul NAS; di un media solo-NAS c'è solo "BACKUP".
- **O-10 (strumento).** Su questo Android 17 `am force-stop` toglie tutti i lavori di JobScheduler dell'app (li rimette WorkManager al primo avvio). Il passo 4 di FF-8 nel copione va fatto con `kill -9`, e anche così i lavori periodici forzati vengono rinviati da WorkManager.
- **O-11 (sovrapposizioni).** `com.keyvoice.app` è comparso sopra la nostra app più volte, sempre dopo un `uiautomator dump`. Ho aspettato 4 s e controllato le finestre prima di ogni tocco; non l'ho mai toccato. Lo sfondo animato di Samsung risulta "sullo schermo" dietro i fogli dell'app: non è una sovrapposizione.
- **O-12 (copione).** "Fai ora" durante le interruzioni: due tocchi sono finiti su schermate diverse perché l'app era stata appena uccisa e rilanciata (uno ha aperto il viewer di una foto di prova). Non è un difetto dell'app.

## Z.3 Misure principali
| Cosa | Valore |
|---|---|
| Verifica del NAS a freddo (3 volte) | 90,7 / 86,5 / 87,1 s, 61,7 MB letti |
| Verifica del NAS a caldo | 38,6 s, rilevati 0 |
| Verifica della sola share `test` | 1,3 s (13 cartelle, 1.168 file) |
| Ritorno di "In casa · NAS collegato" dopo 60 s senza Wi-Fi | ≤ 7 s (sessione pronta dopo 5 s) |
| Download dal NAS al telefono | circa 10 MB/s |
| Suite `core-nas` | 6 min 30 s; scrittura 10,9 MB/s, lettura 12,5 MB/s (un flusso), 17,2 MB/s (quattro) |
| F43-tel | 100 interruzioni in 50 min; 100 versioni sul NAS; delle 60 modifiche fatte durante le interruzioni, quasi tutte al sicuro entro 6 minuti, l'ultima dopo 23 minuti (O-8) |
| Batteria | 72% alle 04:22, 79% alle 07:08 (in carica sul cavo USB) |

## Z.4 Stato lasciato
- **Wi-Fi:** acceso all'ultimo controllo (07:10); da lì se ne occupa il coordinatore.
- **App:** in primo piano, all'ultimo controllo (07:09) nella pagina Cartelle NAS (cartella `Versioni/…/2026/10`), non nella Libreria; nessuna pausa. Preset "Massimo" e qualità "Standard", come all'inizio.
- **Telefono:** `mcg-prova` con 528 file, 95 foto modificate da F43-tel (il manifesto aggiornato è nella cartella della sessione). In `Pictures/MyCloud Gallery` ci sono **10 copie di prova nuove** (`IMG_mcg_0001.jpg`–`IMG_mcg_0010.jpg`, 44 B, di F11), accanto ai file che c'erano già.
- **NAS, `test`:** `mcg-campo/Backup/Telefono di test/` con il backup dei tre giri più le modifiche; `mcg-campo/Versioni/` con 100 file di F43-tel; restano `prova-4b-rinominata` e `sotto`. `mcg-it`: tolta (vedi la riga "Pulizia finale").
- **NAS, `test-comune`:** `mcg-it` con i 3 giri del 19/10 (A-1).
- **PC:** nessun Gradle né banco lanciato da me ancora acceso (il demone Gradle che c'era già dalle 20:36 di ieri l'ho solo riusato).
- **Niente commit, niente `git add`.**

## Z.5 Cosa serve dall'utente
1. **Il file delle credenziali** (`%USERPROFILE%\.mycloudgallery\nas-it.properties`): la seconda share deve essere `share2=test-comune`, scrivibile da `test`. Se `test2` è davvero cancellato, togliere anche `user2`, `password2` e `privateShare2`: oggi il secondo account accede ancora. Poi:
   - la suite di `core-nas` (A.13) toglie anche `test-comune/mcg-it` (3 giri del 19/10);
   - si possono rifare F16-PC, F30-PC e F43-PC (A.14).
2. **Campioni sul NAS** (§2.8, punto 3 del copione) per completare F1, F10 (un video vero sul NAS) e GA-3.
3. **F13**: il sì a installare (e poi togliere) la variante `.bench` e l'APK di test, e lo schermo a 120 Hz.
4. **Esportazione della Diagnostica** (30 s) se servono i numeri di F3 (tocco dei dischi) e F6a.
5. **Pulizia** quando avrà finito di guardare: le 10 copie di F11 in `Pictures/MyCloud Gallery`; `mcg-campo/Versioni` e il backup in `test`.

---

# Blocco A, PC (21/10, seconda parte)

| | |
|---|---|
| **Quando** | 21/10/2026, 08:15–12:24. Solo dal PC: il telefono non c'era e non è stato usato. |
| **Dove** | Worktree `C:\VibeCoded Projects\mcg-campo-build` portato a `aded0e2` (`git checkout --detach`: stesso codice di `e5f3c7f` più i documenti; `local.properties` con il solo `sdk.dir`). L'albero principale non l'ho toccato, salvo questo file. Gradle sempre con `--max-workers=2` (copia di `area.ps1` in `build\campo\area2.ps1` del worktree, cambia solo quello), una build alla volta, RAM libera controllata prima di ognuna (minimo 2,45 GB). Uscite in `build\campo\pc2\` del worktree. |
| **Credenziali** | `nas-it.properties` sistemato dall'utente, non aperto. Nessuna autenticazione rifiutata: il login di prova è stato accettato a ogni corsa. |
| **Esito in breve** | **Tutto passato.** Suite di `core-nas` verde, anche `t07` e `t12` (le copie fra le due share). **`test-comune/mcg-it` pulita**: i 3 giri del 19/10 sono stati tolti con `test`, e non resta niente da elencare. **F16-PC, F30-PC e F43-PC: 400 interruzioni ciascuna, zero giri con violazioni.** Alla fine `mcg-it` è vuota e tolta su tutte e due le share. |

## P.1 Esiti

| Id | Esito | Misure e note |
|---|---|---|
| A.13 suite `core-nas` | **passata** | 08:15–08:24, 8 min 22 s. 18 test: 17 passati, 1 saltato (`t10`, M9 senza consenso), 0 falliti, nessun `Auth`. **`t07`**: copia lato server di 64 MiB nella share in 1,4 s (49 MB/s), **fra le share in 1,7 s (39 MB/s)**, SHA-256 uguale. **`t12`**: spostamento fra share di 64 MiB in 5,3 s (copia lato server 46 MB/s, verifica SHA-256 18 MB/s, pubblicazione 16 ms, sorgente nel cestino 31 ms, fileId conservato nel cestino). Altre misure: 1.500 file creati in 10,8 s, elenco a pagine in 156 ms; file da 64 MiB scritto a 11,0 MB/s, letto a 11,8 MB/s con un flusso e a 18,0 MB/s con quattro. |
| A.13 pulizia di `test-comune/mcg-it` | **fatta** | Tolti con `test` i 3 giri del 19/10: `20261019-181432-f635fa3d` (182 voci), `20261019-181746-a64a8f3c` (2.439), `20261019-210256-fbc56869` (2.286): **4.907 voci** fra file e cartelle. La suite conta anche le cartelle interne e i file nascosti: per questo il numero è più alto delle 1.854 cartelle e 1.429 file visti dalla scansione del telefono. Poi `mcg-it` è stata tolta da `test` e da `test-comune`. Riepilogo: "sotto mcg-it delle 2 share di prova non resta niente (verificato con 2 account)". **Nessun avanzo senza permesso**: niente da elencare. |
| F16-PC (A.14) | **passata** | 08:24–10:32 (Gradle 2 h 7 min). **491 giri, 0 con violazioni**, 400 interruzioni: 240 morti del processo, 106 connessioni cadute, 30 sessioni scadute, 24 socket fermi (231 prima della chiamata, 169 con la risposta persa). 1.213 passi (243 elimina, 232 sposta fra share, 183 rinomina, 171 sposta, 144 crea cartella, 86 rinomina solo maiuscole, 66 file creati dal PC, 46 ripristina, 42 elimina subito), 358 riavvii, 7.769 file riletti con SHA-256 (50,2 GiB). Rapporto: `core-nasops/build/chaos/area-report-20261021-062518.txt` del worktree. |
| F30-PC (A.14) | **passata** | 10:32–11:24 (51 min 51 s). **300 giri, 0 con violazioni**, 400 interruzioni: 206 morti del processo, 90 connessioni cadute, 26 letture del telefono fallite, 24 sessioni scadute, 20 socket fermi, 18 scritture con byte alterati, 10 "NAS pieno", 6 file aperti dal PC. 738 passi, 206 riavvii, 2.492 file riletti (5,1 GiB). Rapporto: `core-backup/build/chaos/backup-area-report-20261021-083303.txt` del worktree. |
| F43-PC (A.14) | **passata** | 11:24–12:12 (47 min 31 s), `run5b` (solo la fase 5B). **537 giri, 0 con violazioni**, 400 interruzioni: 282 morti del processo, 65 connessioni cadute, 15 sessioni scadute, 13 socket fermi, 11 scritture con byte alterati, 7 letture del telefono fallite, 4 "NAS pieno", 3 file aperti dal PC. 1.877 passi, 328 riavvii, 6.798 file riletti (5,7 GiB). Contatori della 5B: 678 versioni fatte (99 "gone"), 212 scambi, 152 copie del guardiano; "Libera spazio": 636 elementi liberati, 122 rimasti, 31 in dubbio, 30 dialoghi rifiutati. **D-14 non ha bloccato niente**: il banco verifica le sue versioni sotto `mcg-it/<giro>/`, non con il verificatore del manifesto del telefono. Rapporto: `core-backup/build/chaos/backup-area-report-20261021-092459.txt` del worktree. |
| Pulizia dopo A.14 (suite di nuovo) | **fatta, suite verde** | 12:12–12:24 (11 min 33 s), 0 falliti. Tolti i giri delle tre prove: in `test` 4.889 voci (F16-PC), 3.850 (F30-PC) e 10.543 (F43-PC); in `test-comune` 5.100, 1.147 e 2.487; più il giro della suite. `mcg-it` tolta da tutte e due le share. In questa corsa: copia fra le share a 27 MB/s, spostamento fra share in 5,6 s. |

## P.2 Note
- **Il secondo account c'è ancora.** Nel file delle credenziali ci sono ancora un secondo account e la sua password, e il NAS lo accetta: `t09` passa ("secondo account: la sua area ok; l'area privata del primo: PermissionDenied") e la pulizia dice "verificato con 2 account". La seconda share ora è quella giusta. Se `test2` doveva essere cancellato, va controllato dalla dashboard del WD.
- **Nessun difetto nuovo.** Le prove dal PC non hanno trovato violazioni.
- **Stato lasciato.**
  - `mcg-it` è vuota e tolta su `test` e su `test-comune`.
  - Nessun Gradle né banco lanciato da me ancora acceso: c'è solo il demone Gradle di ieri, che non è mio.
  - Nel worktree solo file sotto `build\` (ignorati da git). Il worktree resta a `aded0e2`, staccato.
  - Telefono non toccato. Niente commit.


---

# Blocco B (21/10)

| | |
|---|---|
| **Quando** | 21/10/2026, dalle 16:50. Sessioni con l'utente (altre app, dialoghi di sistema) e l'orchestratore che comanda la nostra app con `adb` e `uiautomator`. |
| **Ambiente** | S25 `RFCY61FZDHW`, Android SDK 37, APK di debug di `2445d9e` (`install -r` alle 16:46, dati conservati). Account `test`, area comune `test-comune`, D13 attiva. "Attiva Wi-Fi automaticamente" spento, schermo a 120 Hz. Preset "Massimo" e qualità "Standard" all'inizio (annotati). Batteria 79% (cavo USB). Registro e file in `build\campo\20261021-165146-8c4811b9\` (fuori da git). |
| **Funzioni di appoggio** | Le funzioni di `h.ps1` non le ho usate: ho ripreso gli aiutanti in bash di `build\campo\h.sh` (stessi controlli: nostra app in primo piano, nessuna finestra estranea sopra, 4 s dopo ogni dump). |

## B-S1 · T-09 (cestino di Samsung Gallery e di Android) e GA-7

Fatti i passi 1-8 sulle 528 foto del giro precedente (tutte già al sicuro). Il passo 9 (F26, una foto lasciata nel cestino) si fa dopo una semina nuova, perché F26 vuole una foto non ancora salvata: vedi B-S2.

| Passo | Esito | Misure e note |
|---|---|---|
| Controlli e "Controlla l'ambiente" | **ok** | `env.sdk`=37, `env.rows`=528 (520 immagini, 8 video). App per `GET_CONTENT` 12, `PICK` 5, `SEND` 62 (47 per più file), `VIEW` 10, `EDIT` 3. Cestino di Samsung Gallery vuoto all'inizio (nessun file in `Android/.Trash/com.sec.android.gallery3d`), quindi "Seleziona tutto → Ripristina" non toccava altro |
| 1-2. Due foto cestinate in Galleria, poi una ripristinata | **ok, con una anomalia** | I file persi dal telefono sono 2 (`IMG_20240312_101534.jpg`, `exif-12-solo-digitized.jpg`) e finiscono sotto `Android/.Trash/com.sec.android.gallery3d/uuid/<n>/storage/emulated/0/DCIM/mcg-prova/`. Confronto 1: `diff_missing`=3, `diff_new`=1, `diff_other_id_back`=1, `diff_same_id_present`=525, `diff_trashed`=0, `diff_deleted_files`=3, `diff_generation_delta`=13: **la prova conta 3 righe sparite per 2 file persi**. Dopo il ripristino di una foto: `missing`=3, `new`=2, `other_id_back`=2: il file ripristinato torna con **un altro `_ID`** (riga nuova) |
| 3. Rotazione (LA-7) | **passata** | Orientamento 0, poi 1 per 7 s, poi 0 (registro dell'orientamento ogni secondo). La schermata e il riferimento ci sono ancora: `Confronta` dà gli stessi numeri di prima |
| 4. Cestino di Galleria svuotato | **ok** | Il file tolto è `IMG_20240312_101534.jpg` (tolto dal manifesto del vecchio giro). Confronto 4: `missing`=3, `new`=2, `same_id`=525 |
| 5. (d, e) "Cestina 5 foto con l'app" | **passata** | Dialogo di Android confermato dall'utente: `trash_requested`=5, `trash_result`=-1, `trash_is_trashed`=0, `trash_visible_default`=0, `trash_out_of_folders`=5, `trash_elsewhere_is_trashed`=5 (contatore nuovo rispetto al 20/10), `trash_in_gallery_trash`=0, `trash_gone`=0. Risposta dell'utente "le mostra" (`trash_in_samsung`=1). Dal telefono i 5 file **non si trovano sotto `/sdcard`** (diversamente da quelli cestinati dalla Galleria): il cestino di Samsung li tiene in un posto che la shell non vede |
| 5. "4 · Ripristina quelle foto" | **passata (D-3 corretto nel testo)** | Non dice più "Niente da fare": dice "Le foto sono nel Cestino di Galleria: l'app non le può ripristinare. Ripristinale da Galleria → Cestino, poi tocca di nuovo «4 · Ripristina…»". Dopo il ripristino fatto dall'utente: `untrash_back_same_id`=5 e "Le foto sono già tornate al loro posto con lo stesso _ID". `s25.ps1 -Step verify -SkipNas -AllowFreed`: impronte uguali al manifesto |
| 6. (f) "Cestina fino a 500 foto" | **passata** | `many_requested`=500, `many_result`=-1 (quindi `MANAGE_MEDIA` **non serve**), `many_is_trashed`=0. L'utente ha ripristinato tutto da Galleria (Seleziona tutto → Ripristina): `untrash_back_same_id`=500, 527 file in `mcg-prova`, impronte uguali al manifesto (circa 17 s per rileggere 9,6 GB) |
| 7. Permesso di scrittura e riscrittura | **passata** | `write_dialog`=-1, `write_code`=0, `write_ms`=28, `write_size_before` e `write_size_after`=677, `write_generation_delta`=0. Impronte uguali dopo la prova |
| 8. GA-7: Libreria, 3 foto, "Elimina dal telefono" | **passata, con due difetti** (D-B1, D-B2) | Tre foto di `mcg-prova` scelte dalle celle "non leggibile, al sicuro sul NAS": `IMG_mcg_0351`, `IMG_mcg_0228`, `IMG_mcg_0338`. Foglio "Eliminare 3 foto?", poi dialogo di Android confermato dall'utente: i 3 file escono dal telefono. Raccolte, "Eliminati di recente" chiede lo sblocco del telefono (impronta o PIN, fatto dall'utente), poi elenca **3 elementi, "restano 29 giorni"**. "Seleziona tutto", poi "Ripristina 3 elementi" apre un dialogo di Android; confermato dall'utente, i 3 file tornano nella cartella. La pagina ha lo schermo protetto (screenshot nero): le miniature non si vedono da qui, ma i file sono da 44 B e non ne hanno comunque |

**D-3 oggi.** Il comportamento di Samsung non è cambiato (il cestino di sistema sposta i file nel cestino di Galleria, `IS_TRASHED`=0 nella cartella), ma l'app ora lo dice nei testi e `trash_elsewhere_is_trashed`=5 mostra che le righe si ritrovano fuori dalla cartella. Resta da dire con il codice se "Libera spazio" ne beneficia (B-S9 non è stata rifatta: F37 e F38 sono già passate il 20-21/10).

### Difetti e osservazioni di B-S1

**D-B1 (bassa, testo): il foglio "Elimina dal telefono" della Libreria dice sempre "Non c'è ancora una copia sul NAS".**
- **Passi.**
  1. Libreria, Seleziona, 3 celle che dicono "al sicuro sul NAS" (qui foto `IMG_mcg_*` da 44 B del backup).
  2. "Elimina dal telefono".
  3. Il foglio "Eliminare 3 foto?" dice: "Non c'è ancora una copia sul NAS: dopo 30 giorni nel cestino di sistema non si potrà recuperare."
- **Cosa non torna.** Nel catalogo tutte e 3 hanno `media.safety`=`safe`, `presence`=`nas_only` dopo l'eliminazione e una posizione NAS `present` con `verified_at`. La pagina Backup dice "519 media al sicuro sul NAS". L'avviso è quindi falso per queste foto.
- **Causa probabile.** Il testo è fisso: `LibraryDeleteSheet` in `feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/library/LibraryParts.kt` (righe 498-519) usa sempre `R.string.library_delete_location_phone_detail` (`strings_library.xml`, riga 76); il commento sopra dice "l'avviso che sul NAS non c'è ancora una copia" (D14). Non guarda lo stato di sicurezza della selezione.

**D-B2 (bassa, testo): il banner di "Eliminati di recente" dice che l'app non può ripristinare, ma può.**
- **Passi.**
  1. Raccolte, "Eliminati di recente" (sblocco del telefono).
  2. Il banner dice: "Su questo telefono quello che elimini da MyCloud Gallery va nel Cestino di Galleria, non qui: l'app non lo vede e non lo può ripristinare."
  3. Le 3 foto eliminate dall'app sono invece elencate (3 elementi, "restano 29 giorni") e "Ripristina 3 elementi" le riporta nella cartella con un dialogo di sistema.
- Va riscritto il banner, oppure il pulsante è fuori luogo.

**O-B1 (strumento, da guardare a mano): la griglia della Libreria non scorre con `adb input swipe`.** Un trascinamento da 1.500 px la sposta di circa 50 px. Il cursore "Scorri la timeline" a destra funziona. Nelle altre pagine (Impostazioni, Prove guidate) lo scorrimento da `adb` va. Può essere un limite dei gesti sintetici su una griglia da 8.000 elementi: da provare con il dito.

**O-B2.** Il cestino della Galleria usato a mano si vede dalla shell, quello dell'app (`createTrashRequest`) no. Per ripristinare serve sempre un dialogo di sistema o l'utente.

**O-B3 (privacy).** Una cella in cima alla Libreria che diceva "solo sul telefono, data stimata" risultava, dalle Informazioni, un'immagine di WhatsApp dell'utente: ho letto solo le Informazioni in testo e non l'ho fotografata. Le foto vere sono in cima alla Libreria (stessa data di oggi): le foto di prova si trovano con le celle che dicono "al sicuro sul NAS".


## B-S2 · F25 (file messi dal PC prima del backup) e F26 (foto cancellata e ripristinata)

**Semina nuova.** Il primo backup di `mcg-prova` era già stato fatto, quindi per F25 e F26 ho rifatto la semina: `s25.ps1 -Step clean -Yes` (17:25), poi `-Step seed -Run 20261021-172617-22967a70 -Seed 44 -Photos 600 -BigVideos 1 -BigVideoMb 3072` con i fixture senza i tre finti: **606 file, 3,22 GB** (un solo video grande, perché le prove lunghe sono già fatte; serve ancora per F40). Preset cambiato da "Massimo" a **"Notte"** per tenere fermo il backup (la capsula dice "511 in coda. Attesa della notte"; `PhotoBackupWorker: tappa: Wait`) e rimesso a "Massimo" alle 18:14 (controllato nel file `rule_settings`: `rule.preset` = `MAXIMUM`). Il manifesto di verifica ha le 606 righe `phone=` e **1.164 righe `pre=`**: le 1.162 copie già sul NAS dei tre giri precedenti, prese dal catalogo dell'app (`backup_item`, `final_path` e `sha256`, con le 95 foto modificate da F43-tel a stato finale), più le 2 righe di F25. Percorso di una copia: `mcg-campo/Backup/Telefono di test/mcg-prova/AAAA/MM/` (con `mcg-prova` in mezzo: il copione non lo scrive).

**F25 · preparazione.** Nomi mai usati sul NAS (i giri vecchi arrivavano a `IMG_mcg_0430`, il nuovo a `IMG_mcg_0509`): `IMG_mcg_0508.jpg` "diverso" (stessi nome e primi 44 byte, più 24 byte in coda, 68 B) e `IMG_mcg_0507.jpg` "identica" (copia esatta di 44 B). L'utente li ha copiati in `\\<NAS>\test\mcg-campo\Backup\Telefono di test\mcg-prova\2026\10\` da Esplora risorse, nessuna richiesta di sostituzione.

| Passo | Esito | Misure e note |
|---|---|---|
| F25 · "diverso" | **passata** | Nel catalogo l'elemento di `IMG_mcg_0508.jpg` ha `planned_path` `…/IMG_mcg_0508.jpg` e `final_path` `…/IMG_mcg_0508 (2).jpg` (`name_attempt` 1): il nostro arriva come "(2)" e quello dell'utente resta com'è |
| F25 · "identica" | **passata** | Elemento `new_copy` di `IMG_mcg_0507.jpg` in stato `linked_existing`: `final_path` è il file dell'utente, collegato **senza caricamento** (nessun "(2)") |
| Primo backup ("Fai ora", 17:46) | **ok** | Foto: in 2 minuti dall'avvio 88 media già al sicuro per copie identiche dei vecchi giri (fixture), il resto caricato; alla fine le due sorgenti dicono 344 su 344 e 255 su 255 al sicuro. Video da 3 GB (3.221.227.552 B): in caricamento 17:49-17:56 (circa 10 MB/s), poi `uploaded`, rilettura 17:59-18:07, `publishing` fino alle 18:07:50, `done` alle 18:07:55; il giro dei video è chiuso una volta per budget di tempo (`stop=budget`, 17:59 e 18:07) e poi `complete`. Totale elementi nuovi: 510 `done` + 1 `linked_existing`. Traffico del Wi-Fi (`wlan0`, altre app comprese): da 3,58 a 6,95 GB inviati e da 0,78 a 13,49 GB ricevuti in 23 minuti (la rilettura del video da 3 GB conta per 3 GB di ricevuti) |
| F26 · foto cancellata prima del backup | **non passata (D-B3)** | L'utente ha cestinato `IMG_mcg_0509.jpg` (una foto con data 21 ottobre 2026, 17:26, mai salvata). Dopo la Galleria: il catalogo la dà `safety`=`lost`, `presence`=`none`, `backup_todo`=0; la tabella `backup_problem` è **vuota**; la pagina Backup, il piede della Libreria e Diagnostica → Backup **non dicono niente**; la foto sparisce dalla Libreria (8.106 → 8.105 foto) e dai conteggi della sorgente (344 media, 342 al sicuro, 1 in coda). Il messaggio "Cancellati prima del backup · Foto o video: apri il cestino di Galleria" (`backup_footer_lost_before_backup_*`) non compare mai |
| F26 · foto ripristinata | **passata, con una nota** | L'utente l'ha ripristinata dal Cestino della Galleria alle 17:52, mentre il backup caricava il video. Il catalogo la rivede (`present`, `no_nas_copy`, `backup_todo`=1) ma **la prima corsa dell'utente non la prende**: aveva già chiuso il giro delle foto alle 17:48 e il lavoro finisce `DONE` alle 18:07:55; la capsula dice "1 in coda. Attesa della notte". Con un secondo "Fai ora" (18:11) il giro delle foto fa `fatti=1`: **un solo elemento** `done` (id 1773), al solito percorso, **nessuna copia "(2)"**, `safe`/`phone_and_nas` |
| F26 · il suggerimento | **non verificabile** | Il messaggio del suggerimento non è mai comparso (D-B3) |

### Difetto di B-S2

**D-B3 (media): una foto cancellata in Galleria prima del backup sparisce senza nessun avviso.**
- **Passi.**
  1. Telefono con foto nuove non ancora salvate (qui una semina nuova, preset "Notte"), app aperta.
  2. In Samsung Gallery cestinare una foto mai salvata.
  3. Guardare la pagina Backup, il piede della Libreria, Diagnostica → Backup.
- **Cosa si vede.**
  - Nessun problema: `backup_problem` vuota, nessun "Cancellati prima del backup".
  - Il media nel catalogo è `lost` con `presence` `none`: sparisce dalla Libreria e dai conteggi, come se non fosse mai esistito.
  - Anche due immagini di WhatsApp cancellate dall'utente sono `lost` senza nessun segno (sorgente non salvata: qui non conta).
- **Atteso (copione, F26).** "Persa prima del backup" con il suggerimento e poi tutto al sicuro.
- **Causa probabile.** `MediaCatalog.markBackupProblem(.., "lost_before_backup")` lo chiama il motore quando un candidato sparisce **dopo** essere entrato in lista; se la riga sparisce prima, il media `lost` non è più candidato (`backup_todo`=0) e nessuno segna il problema. Cfr. `MediaCatalog.kt` (righe 1076-1092 e 2423) e `BackupCatalogTest` (righe 320-453), che provano solo il caso "sparita durante il giro".
- **Nota collegata.** Dopo il ripristino la foto non entra nel giro in corso se questo ha già chiuso la fase delle foto: aspetta il giro dopo (qui "Attesa della notte") o un altro "Fai ora". Non perde niente.

### B-S2 · verifica dal PC (un solo tentativo, sola lettura)

`area2.ps1 -Mode backup -Stage verify -Manifest <606 righe phone= + 1.164 righe pre=>`, avviata alle 18:16 e finita alle 18:52 (Gradle 36 min 5 s, `--max-workers=2`, RAM libera 6-7 GB prima dell'avvio; lettura del NAS a circa 17 MB/s). Login accettato al primo tentativo. Le impronte del telefono prima della verifica: uguali al manifesto (606 file).

| | |
|---|---|
| **Risultato** | "file riletti con SHA-256: **1.674** (27.263.185 KiB) · **1 VIOLAZIONE**: [4. nessuna perdita] **16 file del telefono non sono sul NAS**". Nessun file a metà, nessuna sostituzione, nessun doppione, nessun temporaneo `-bk-`; i file `pre=` (1.164, compresi i due messi dall'utente per F25) hanno ancora i loro byte |
| **Le 16** | Sono 15 contenuti dei fixture (`20240312_122000.heic` e `.jpg` hanno gli stessi byte): `20240312_112000_001`, `20240312_114000_002`, `20240312_122000`, `exif-03`, `exif-04`, `exif-05`, `exif-07`, `exif-11`, `exif-12`, `exif-13`, `IMG_20240312_101535`, `motion-google-v1-specifica`, `orientamento-7`, `tratto-panorama-ruotato`, `orientamento-1`. **Non è una perdita**: sono i fixture che F43-tel aveva modificato nei giri vecchi. Sul NAS, al solito percorso, c'è la versione modificata (le righe `pre=` hanno la sua impronta) e l'originale sta in `mcg-campo/Versioni/Backup/Telefono di test/mcg-prova/2024/03/<nome> (originale).jpg`. Il catalogo lo sa: tutti e 16 sono `present` sul telefono e hanno la posizione NAS in `Versioni/`; per tutti e 16 l'impronta del telefono è quella di un vecchio `new_copy` `done` il cui percorso ha poi una versione con `archive_path` (controllato nel database: 16 su 16) |
| **Causa** | Stessa famiglia di D-14: il verificatore guarda `Backup/` e non `Versioni/` finché il manifesto non ha righe `edit=`, quindi non sa che l'originale è lì. L'app, giustamente, considera al sicuro un contenuto che ha già un originale archiviato. Per il manifesto di prova la correzione è mettere in `pre=` anche i file di `Versioni/` (o ignorare l'invariante 4 per i 15 contenuti) |
| **F25 e F26** | Confermate dalla verifica: `IMG_mcg_0508 (2).jpg` ha i byte del telefono, `IMG_mcg_0508.jpg` (dell'utente) è invariato, `IMG_mcg_0509.jpg` c'è una volta sola |

Rapporto: `core-backup/build/chaos/backup-verify-20261021-172617.txt` (copia in `build\campo\20261021-165146-8c4811b9\backup-verify-b-s2.txt`).


## B-S3 · F4, F5, F9: le modifiche dal PC

**Preparazione.** Mancavano i campioni sul NAS (§2.8 del copione). Ho preparato sul PC `build\campo\campioni\MCG-prova\` (99 fixture, ognuno con 24 byte casuali in coda per renderli unici: senza, sarebbero stati copie identiche dei file del telefono e del backup, non "solo NAS"), `build\campo\mille\mille\` (1.000 JPEG da 701 B) e `...\F9\` (600 JPEG), più `F9extra\` (altri 200, per avere 800 file in `F9`: i 300 rinominati nella seconda parte di 4a-R9 non c'erano). L'utente li ha copiati in `test\mcg-campo\` (`campioni` 99, `mille` 1.000, `F9`) e in `test-comune\mcg-campo\campioni\` (99; la cartella `mcg-campo` in `test-comune` non c'era e l'ha creata lui). Nessun errore di Windows.

**Prima scansione dopo la copia** (avvio a freddo, 18:26): `test` = 3.478 file, 1.780 invariati, **1.698 rilevati** (1.000 + 600 + 98 di `campioni`: un file dei 99 non è stato preso come media, `NAS_SCAN_NOT_MEDIA` dice 2 in tutto); `test-comune` = 98 rilevati. Libreria da 8.107 foto e 404 video a 9.891 foto e 414 video. Segnata come preferita `IMG_mille_0003.jpg`. Esportazione P1 salvata dall'utente (`diag-P1.json`): `NAS_SCAN_STATE` `detected`=1796, `files`=3576, `folders`=29, `unchanged`=1780.

| Prova | Esito | Misure e note |
|---|---|---|
| **F4** rinomina di `mille` in `mille-rinominata` (18:57) | **passata** | Il Verify del 19:02 dice `spostati`=1000, `rilevati`=1 (la modifica di F5), `spariti`=1 (la cancellazione di F5): le 1.000 foto **non** sono state riscoperte come nuove. Nel catalogo 1.000 posizioni `present` sotto `mcg-campo/mille-rinominata/`, nessuna `absent`; il preferito è ancora lì (Raccolte → Preferiti "1 elemento", percorso aggiornato a `mille-rinominata/IMG_mille_0003.jpg`); Libreria 9.891 → 9.890 (la sola foto cancellata). Il **contatore di preparazione** non è nell'esportazione e non l'ho confrontato (P2 non fatta: il log del Verify dà gli stessi numeri) |
| **F5** foto ruotata con Paint e foto cancellata | **passata** | `IMG_20240312_101535.jpg` passa da 817 a 1.790 B sulla stessa riga (nuova versione, stato `unverified`, `nas_only`); `PXL_20240312_101535123.jpg` diventa `absent`/`lost` e **esce dalla Libreria** (9.891 → 9.890); la copia in `test-comune` (`not_mine`) non cambia |
| **F9** 500 file rinominati `.locked` (19:07) | **passata** | Alla scansione (19:07:52) `stop=FROZEN`: i file visti scendono da 3.677 a 3.177 e nessuno è segnato sparito. Nel piede della Libreria: "**Lavori fermi: cambiamenti anomali.** Sul NAS sono spariti molti file insieme. Finché non controlli, sul NAS non si scrive e non si cancella niente" e "**Backup fermo: cambiamenti anomali. Controlla nella Diagnostica.**". La Libreria resta a 10.090 foto (**nessuna assenza segnata**). Toccare la capsula apre la Diagnostica (**NA-9**); in "NAS nella Libreria" c'è la scheda `NAS_SCAN_FROZEN` ("Cambiamenti anomali sul NAS", "Le scritture automatiche sono ferme e nessun file è stato segnato come sparito") con "Ho controllato, continua" |
| **F9** gesto "Ho controllato, continua" | **passata** | Il dialogo chiede di riscrivere il numero: "Sul NAS sono cambiati in modo anomalo **501** file" (500 più la modifica di F5). Scritto 501, confermato: il giro dopo (19:10:57) ha `spariti`=500 e `stop`=null; le 500 foto escono dalla Libreria (9.590) |
| **F9** processo nuovo (NA-4, NA-5) | **passata** | `force-stop` e riapertura (19:11): `spariti`=0, `stop`=null, nessun nuovo congelamento con lo stesso numero (**NA-4**); il piede non dice più "Lavori fermi" (**NA-5**) |
| **F9** altri 300 file rinominati (19:13), 4a-R9 | **passata** | Avvio a freddo 19:13:36: `stop=FROZEN` di nuovo (file visti 2.877); il dialogo dice "300 file": la conferma di prima valeva solo per il numero visto. Piede "Lavori fermi" e "Backup fermo" di nuovo |

### Osservazioni di B-S3

**O-B4 (copione): l'app non guarda da sola il NAS entro 3-4 minuti.**
- **Cosa ho visto.** Dopo F4 + F5 (modifiche alle 18:57) e quattro minuti di attesa con l'app aperta, nessuna scansione: l'ultima era delle 18:55. Il Verify è partito solo con un **avvio a freddo** (`am force-stop` e riapertura), anche a 7 minuti dall'ultimo. Lo stesso per F9 (due volte). Le scansioni finite di oggi (`events.log`): 05:48, 07:09, 13:26, 13:54, 16:52; poi, dalla mia sessione, solo avvii a freddo (18:26, 19:02, 19:05, 19:07, 19:11, 19:13) e il giro dopo il gesto (19:10). Gli intervalli in background sono di ore (+324, +28, +177 minuti), non di minuti.
- **Cosa prevede il progetto.** `fase4-progetto.md` (righe 262 e 365): la verifica parte "a ogni apertura dell'app se l'ultima ha più di 15 minuti, di notte, e quando i dischi sono svegli"; in background con `lastDiskTouchAt` recente (10 minuti), "altrimenti al massimo ogni 6 ore e nella fascia notte". È la regola E-R15: controlli limitati per non svegliare i dischi (`fase2-progetto.md` riga 254: la sonda è solo TCP 445, mai `list`).
- **Conclusione.** Il comportamento è quello previsto; è il copione a sbagliare ("aspetta 3 minuti"). Va riscritto come "riapri l'app (se l'ultima verifica ha più di 15 minuti) oppure aspetta la fascia notte". Nessun difetto dell'app. Un avvio a freddo parte comunque, senza guardare i 15 minuti (comportamento di `MyCloudGalleryApp` che accoda `scanNow`, già visto in D-2).

**O-B5 (notifica del congelamento): nessuna notifica sul momento, solo il piede della Libreria e la Diagnostica.**
- **Cosa ho visto.** Al congelamento (19:07 e 19:13) non è arrivata nessuna notifica con il testo del congelamento (cercata con `dumpsys notification`: nessuna voce dell'app con quel testo). L'avviso c'è nel piede, nella scheda `NAS_SCAN_FROZEN` e in "Backup fermo". Il riepilogo giornaliero non l'ho aspettato.
- **Cosa prevedono i documenti.** `fase4-progetto.md` riga 269 dice ancora "congelamento **subito** (eccezione alla proposta 12, domanda Q6)", ma la risposta a Q6 è **(b)**: "nel riepilogo giornaliero, non con una notifica immediata. D10 cambia: il congelamento resta immediato, l'avviso va nel riepilogo giornaliero (proposta 12) e in evidenza nella Libreria e in Diagnostica" (`fase4-progetto.md` riga 991, `decisioni.md` righe 318 e 335). Quindi il comportamento **è quello deciso**; la riga 269 del progetto è rimasta con la vecchia (a).

**O-B6 (chi ha dato il consenso).** Il gesto "Ho controllato, continua" del primo congelamento (con il numero 501 riscritto) **l'ho dato io dalla nostra app, non l'utente**: l'utente aveva fatto lui le rinomine dal PC, ma il gesto serve a dire "ho controllato dal PC". Vale per NA-4 e per il giro successivo: i numeri qui sopra descrivono il comportamento dell'app, non una conferma dell'utente. La seconda conferma (300 file) è stata lasciata all'utente.

**O-B7 (strumento).** Con il campo del numero in primo piano, dopo `input text` compare sopra la nostra app il riquadro "Registrazione KeyVoice" di `com.keyvoice.app` (si vede nella schermata; sta nella parte bassa, sulla tastiera). Non l'ho toccato: ho premuto il pulsante del dialogo, che sta più in alto. La guardia a coordinate (`guard_touch`) si è fermata a ragione; ho dovuto confermare a mano la posizione con uno screenshot. Il riquadro stava registrando: l'impostazione è dell'utente.


## B-S4 · F15: operazioni sui file dall'app (parziale: fermata alle 20:05 per una pausa chiesta dal coordinatore)

**Adattamento alle regole di questa sessione** (approvato dal coordinatore). Il copione vuole la semina dal PC con `area.ps1 -Mode seed` sotto `mcg-it/<giro>/`; qui il PC non scrive sul NAS e l'app scrive solo dentro `mcg-campo/`. Quindi: l'utente ha copiato `build\campo\F15\sorgente` (300 JPEG da 701 B) in `test\mcg-campo\F15\sorgente`; le operazioni dell'app sono in `mcg-campo/F15/…` e la destinazione nell'area comune è `test-comune/mcg-campo/F15/dest` (cartelle create dall'app). La verifica di fine sessione con `area.ps1 -Mode verify` non si può usare (legge il layout di `mcg-it`): al suo posto il catalogo dell'app (`nas_op`, `nas_trash_entry`, conteggi dei percorsi) e Diagnostica. Guida dell'app: l'ho fatto io con `uiautomator`, per tutti i passi tranne quelli che richiedono lo sblocco del telefono o il PC. Prima dell'inizio l'app era sbloccata dal congelamento di B-S3 (conferma dell'utente alle 19:16:53).

**Dove mi sono fermato.** Nessuna operazione sul NAS in corso: tutte e 14 le operazioni del diario (`nas_op`) sono `done`, nessun dialogo aperto, app in background (tasto Home), Wi-Fi acceso, preset "Massimo", nessun comando `adb`, `logcat` o script mio in esecuzione (resta solo il processo `adb` del server di sistema).

| Passo del copione | Esito | Misure e note |
|---|---|---|
| 2. Crea `prova-a`, rinomina in `Prova-A` (solo maiuscole), crea `prova-b` | **fatto, ok** | `create_folder` `done` (0,1 s), `rename` `done` (0,2 s: la rinomina "solo maiuscole" passa), `create_folder` `done`. La cartella ora si chiama `Prova-A` |
| 3. 5 file in `prova-b` | **fatto, ok** | Scelta della destinazione, riepilogo "Spostare 5 elementi? 3,5 kB", `move` `done` in 7,5 s (5 elementi, 3.505 B) |
| 4. 20 file nell'area comune | **fatto, ok** | Riepilogo "Le vedranno tutti i familiari" e "Prima copio, poi controllo… l'originale va nel cestino del NAS per 30 giorni". `move` `done` in **8,4 s** (20 elementi, 14.020 B). In `dest` 20 elementi. Sorgenti nel cestino del NAS (`nas_trash_entry`, motivo `move`). **Vedi O-B9** |
| 5. Elimina 2 file | **fatto, ok** | Foglio "Eliminare 2 elementi? Vanno nel cestino del NAS per 30 giorni… li trovi in Eliminati di recente del NAS". `trash` `done` in 0,55 s (2 elementi, 1.402 B); due voci `trashed` (motivo `delete`) |
| 5. "Eliminati di recente", ripristino di uno (GA-7, miniature) | **non fatto** | Serve lo sblocco del telefono dell'utente (impronta o PIN) per aprire la pagina |
| 6. Nome in conflitto | **fatto, ok** | Rinominando `F15_0030.jpg` in `F15_0031.jpg` il campo diventa rosso, "**Esiste già qualcosa con questo nome.**", il pulsante "Rinomina" è spento e nessuna operazione parte |
| 7. Cartella intera (`prova-b` in `dest`, fra aree) | **fatto, ok** | Riepilogo "6 elementi, 1 cartella e 5 file, 3,5 kB", `move` `done` in 2,0 s |
| 8. "Elimina subito" e doppio tocco (4b-R6) | **non fatto** | Nel foglio "Eliminare…" della cartella (`sorgente`) **non c'è** "Elimina subito": ci sono solo "Elimina" e "Annulla". Probabilmente sta in "Eliminati di recente del NAS" per le voci già nel cestino: da guardare lì, dopo lo sblocco |
| 9. File aperto in Paint sul PC (E-N13, NA-2) | **non fatto** | Serve l'utente al PC |
| 10. (R2 della 4B) elimina `mille-rinominata` e annulla | **fatto, ok** | Il foglio dice "1 elemento, **1 cartella e 1.000 file, 701 kB**" (non "0 B"), "**Sono molti file** · Nel cestino del NAS vanno 1.000 file. Controlla che sia la cartella giusta", "Le cartelle vanno nel cestino intere"; annullato, niente è partito |
| 11. (R8 della 4B) 60 foto di `mille-rinominata` in `test-comune/mcg-campo` | **fatto** | Scelte `IMG_mille_0001`-`0060`; riepilogo "Spostare 60 elementi?"; `move` `done` in **22,1 s** (60 elementi, 42.060 B). **Non ho guardato il piede della Libreria** dopo questo spostamento (né dopo gli altri): "Lavori fermi" non verificato |
| 11. Tutta la cartella da 1.000 (ora 940 file) | **non fatto** | Le navigazioni della mia ultima sequenza non hanno trovato i pulsanti (ero uscito dalla cartella) e non è partita nessuna operazione |
| Operazioni in corso, NA-1 ("In coda" se ne parte una nuova mentre una lunga è in corso) e `NAS_OPS_*` in Diagnostica | **non fatto** | |
| Verifica di fine sessione | **non fatta** | Vedi sopra: serve un adattamento del verificatore o il confronto dei conteggi dal catalogo |

**Stato del NAS lasciato da B-S4** (da controllare con la prossima scansione):
- `test/mcg-campo/F15/sorgente`: la cartella `Prova-A` (vuota) e i file `F15_0028`-`0300`: 273 file = 300 − 5 (in `prova-b`, poi spostata) − 20 (a `dest`) − 2 (eliminati: `F15_0026` e `F15_0027`). Il foglio di eliminazione di `F15_0033` l'ho annullato, quindi quel file c'è ancora.
- `test-comune/mcg-campo/F15/dest`: 20 file `F15_0006`-`0025` e la cartella `prova-b` con 5 file (`F15_0001`-`0005`).
- `test/mcg-campo/mille-rinominata`: 940 file (`IMG_mille_0061` e seguenti). In `test-comune/mcg-campo/`: 60 file `IMG_mille_0001`-`0060` e `F15/`.
- Cestino del NAS di `test`: 2 voci `delete` e le voci `move` dei file spostati fra aree (20, 60 e la cartella `prova-b` con i suoi 5 file; da contare dal catalogo).
- Nei fatti: `Prova-A` vuota, la sorgente della cartella `prova-b` spostata è sparita da `sorgente`.

**Da dove riprendere.** (1) L'utente sblocca "Eliminati di recente" (impronta o PIN): ripristino di una voce, "Elimina subito" con il doppio tocco. (2) Spostare tutta `mille-rinominata` in `test-comune/mcg-campo` e guardare il piede ("Lavori fermi" non deve comparire, neanche dopo gli spostamenti di prima). (3) Il passo con Paint: l'utente apre un file di `F15\sorgente` su `\\<NAS>\test\mcg-campo\F15\sorgente\` e io provo a rinominarlo dall'app. (4) Operazioni in corso e Diagnostica `NAS_OPS_*`. Poi B-S5.

### Osservazioni di B-S4

**O-B8 (strumento, importante per le prove): `uiautomator dump` con il campo di testo attivo fa partire la registrazione di KeyVoice, che scrive nel campo.** Con la tastiera aperta in un campo (nome di una cartella, numero del congelamento) dopo un `dump` compare il riquadro "Registrazione KeyVoice" di `com.keyvoice.app` e nel campo è finito "Grazie per la visione!" (testo scritto da KeyVoice, non da me): `prova-a Grazie per la visione!`. Ho annullato il foglio. Per il resto della sessione scrivo nei campi **senza** lanciare `uiautomator` mentre la tastiera è aperta: tocco del campo, `input text`, tocco del pulsante a coordinate (con uno screenshot per vederle). Il riquadro registra dal microfono: l'utente può spegnere l'aggancio di KeyVoice per le prove.

**O-B9 (da confermare): creare una cartella dentro la schermata "Sposta" chiude la scelta della destinazione.** Passi: Cartelle NAS, selezionare 20 file, "Sposta", in un'area comune toccare "Nuova cartella" nella scelta della destinazione, scrivere un nome, "Crea cartella". La cartella nasce, ma la scelta della destinazione si chiude (resta la schermata normale della cartella) e la selezione non viene più spostata: ho dovuto rifare la selezione. Il copione (passo 4) chiede proprio di crearle lì. Visto una volta sola.

**O-B10.** Lo spostamento fra aree è lento rispetto a uno nella stessa area: 20 file 8,4 s (0,42 s a file), 60 file 22 s (0,37 s a file), contro i 5 file spostati dentro la stessa share, che hanno impiegato 7,5 s (non ne ho indagato la causa: forse la prima apertura della cartella), e la cartella da 6 elementi fra aree in 2,0 s. Con 940 file sarebbero circa 6 minuti.


### B-S4 · ripresa (21:00-22:20) e chiusura

Il telefono è tornato disponibile alle 20:51. Da qui in poi nei campi di testo scrivo alla cieca (tocco del campo, `input text`, tocco del pulsante a coordinate, schermata per controllare) e non lancio `uiautomator` con la tastiera aperta (richiesta del coordinatore, O-B8).

| Passo | Esito | Misure e note |
|---|---|---|
| 11. Tutta la cartella `mille-rinominata` (941 elementi: 1 cartella e 940 file, 659 kB) in `test-comune/mcg-campo` | **passata** | `move` `done` in **8 min 15 s** (0,53 s a file), tutti i 941 elementi `done`, nessun errore, nessuna perdita di file. Il piede della Libreria **non** dice "Lavori fermi" dopo questo né dopo gli spostamenti di 20 e 60 file (**4b-R8**); dopo la fine "NAS aggiornato" alle 21:04 |
| NA-1 (operazioni nuove mentre una lunga è in corso) | **passata** | Operazioni in corso: "Sposto 941 elementi · In corso · 31% · 293 di 941 elementi · 205 kB di 659 kB" e, sotto, "Creo una cartella · **In coda** · 1 elemento · 0 B, in coda". La cartella (`coda-na1`) e un ripristino messi in coda sono partiti da soli a fine spostamento: `create_folder` dopo 389 s, `restore` dopo 122 s dal tocco |
| 5. "Eliminati di recente del NAS" e ripristino di uno | **passata** | È una pagina **diversa** da "Eliminati di recente" del telefono e **non chiede lo sblocco**. Raccolte, "Eliminati di recente del NAS": "Cestino del NAS · 581 elementi" (poi 1.026), per ogni voce il nome, "Da mcg-campo / F15 / sorgente · 701 B" e "Restano 30 giorni · Copia di partenza di uno spostamento" oppure "Eliminato da questo dispositivo". Nessuna miniatura: icona generica di immagine (**GA-7 per il NAS**). Ripristinata `F15_0026.jpg` ("Ripristinare 1 elemento? Tornano dove erano. Niente viene sostituito… se il nome è occupato aggiungo (2)"): `restore` `done`, partito dopo 122 s perché in coda dietro lo spostamento |
| 8. "Elimina subito" e doppio tocco (**4b-R6**) | **passata (non confermata fino in fondo)** | Il pannello 1 "Eliminare subito 1 elemento? Non passano dal cestino del NAS… Non si possono ripristinare… Hai un backup esterno?" ha "Continua"; un doppio tocco rapido (due `input tap` di fila) porta al pannello 2 "**Ultima conferma** · Dopo questo tocco non si torna indietro" e **non** conferma da solo. Ho annullato: l'eliminazione definitiva di una voce dal cestino sta in `.mycloudgallery/trash/`, fuori da `mcg-campo/`, e per le regole non la confermo io |
| 9. File aperto sul PC (**E-N13, NA-2**), con Paint | **non riprodotto** | Con `F15_0100.jpg` aperto in Paint sul PC la rinomina dall'app va a buon fine subito (`rename` `done` in 0,1 s): Paint non tiene il file bloccato |
| 9. File aperto sul PC, con un blocco esclusivo | **passata** | L'utente ha aperto `F15_0101.jpg` con PowerShell (`FileShare None`, sola lettura). Rinomina dall'app: `rename` in **pausa**, `error_code=locked`, elemento `renaming` con tentativi 1, 2, 3; il lavoro delle operazioni risponde "Later" a 22:11:30 e 22:13:31 (ogni circa 2 minuti), poi la prossima partenza è a 5 minuti (22:19). Esportazione E2 salvata dall'utente durante il blocco (22:13): `NAS_OPS_STATE` = `opsActive` 1, `opsLocked` 1, `reason.LOCKED` 1. L'utente ha sbloccato (`$f.Close()`) verso le 22:14: **la rinomina riparte da sola** e finisce alle **22:18:34** (circa 4 minuti e mezzo dopo lo sblocco, `fatti 1, fermi 0`), `done` con 3 tentativi, senza toccare niente |
| 4. Diagnostica (`NAS_OPS_*`) | **passata** | Nell'E2: nessun errore; `NAS_OPS_STATE` `opsClosed` 18, `opsRunning` 0. Dopo lo sblocco il diario è tutto `done` (18 operazioni chiuse + la rinomina) |

### Altre osservazioni

**O-B11 (da guardare): `Failure(NotFound, FINAL)` due volte durante lo spostamento da 941 elementi** (20:59:52 e 21:01:57, `NasSession: operazione fallita`). Nessun elemento `nas_op_item` in errore e lo spostamento finisce `done`: probabilmente un passo interno (temporaneo o cartella già tolta) che il motore tollera. Non indagato.

**O-B12 (strumento): i tempi di backoff di un'operazione bloccata (2, 2, poi 5 minuti)** vanno tenuti presenti nel copione ("entro qualche minuto": qui 4,5 minuti dopo lo sblocco).

**O-B13 (esportazione E2).** `BACKUP_STATE`: `backupStatus` `UP_TO_DATE`, `queued` 2.068, `safe` 602, `folderChosen` `true`, `gate` `OPEN`: il numero "in coda" (2.068) è molto più alto dei media da salvare (le due sorgenti `mcg-prova` sono al sicuro): probabilmente comprende anche le cartelle non salvate, quindi non va letto come arretrato del backup (da capire con il codice). `BACKUP_SIMILAR_COPIES`: `pending` 59.

**Stato lasciato da B-S4.** NAS `test`: `mcg-campo/F15/sorgente` con `Prova-A` (vuota), la cartella `coda-na1` in `F15`, `F15_0026` ripristinata, `F15_0100_nuovo.jpg` e `F15_0101_blocco.jpg`; `mille-rinominata` sparita da `test/mcg-campo` e ora in `test-comune/mcg-campo/mille-rinominata` (940 file) con 60 file `IMG_mille_00xx` e `F15/dest` (20 file e `prova-b` con 5 file). Il cestino del NAS di `test` ha 1.026 voci. Niente di tutto questo tocca il backup di `mcg-prova` né `Backup/`. Resta da togliere a fine blocco B (dall'utente): `test/mcg-campo/F15`, `F9`, `campioni`; `test-comune/mcg-campo/*` (compresa `mille-rinominata`) e le voci del cestino del NAS.


---

## Da qui in poi: l'utente non è più disponibile (22:30 del 21/10)

Cambio di regole deciso dall'utente (riferito dal coordinatore alle 22:3x): non chiedo più niente all'utente; **posso confermare io i dialoghi di sistema di Android aperti dalla nostra app** (cestino, modifica, ripristino, scrittura, un semplice "Continua") solo per file di prova, mai quelli che cancellano per sempre; **posso usare la Galleria Samsung e "Il mio file" solo su file di prova** di `mcg-prova` (cestinare e ripristinare dal Cestino della Galleria solo file di prova, "Modifica", "Salva", "Salva copia", "Ripristina originale", "Modifica con…"); mai Gmail, WhatsApp, Telegram, Chrome o altre app (T-14, T-15, F39-F42 simulate con le prove guidate o con intent `adb shell am start` verso la nostra app); le scritture "dal PC" solo dentro `mcg-campo/` e solo con uno strumento del banco già esistente, altrimenti saltate e annotate. Sblocchi con PIN o impronta: li salto. Restano valide le altre regole (niente impostazioni del telefono salvo il Wi-Fi con `s25.ps1`, niente `pm clear`, niente installazioni, stop al primo `Auth`, batteria minima 35%). F20 e F12 approvati (con il conteggio dei file fuori dal cestino prima e dopo). Fermarsi prima delle 7:30.

## B-S5 · F16-tel: mossa di massa tra due share con 100 interruzioni

**Adattamento.** Niente `area.ps1 -Mode seed` (scrive dal PC sotto `mcg-it`): i 300 file `F16_0001`-`0300` (JPEG da 701 B) li ha copiati l'utente in `test\mcg-campo\F16\sorgente`; destinazione `test-comune/mcg-campo/F16/dest` (creata dall'app, in una schermata normale, vedi O-B9). Senza `-Verify` (il verificatore legge il layout di `mcg-it`): le invarianti le ho controllate dal catalogo dell'app, dal diario delle operazioni (`nas_op`, `nas_op_item`, `nas_trash_entry`) e dalle scansioni del NAS.

| Passo | Esito | Misure e note |
|---|---|---|
| R9 della 4B / O3: "Ferma", poi morte del processo | **passata, con una differenza dal copione** | Spostamento di 300 file (17% a 22:33), "Ferma" nelle Operazioni in corso: l'operazione passa a **"Annullata · Fermata dopo 91 di 300. Quello che era già fatto resta fatto."** (non c'è "Riprendi": "Ferma" annulla il resto, `nas_op` `cancelled`, 91 elementi `done` e 209 `cancelled`). Poi `am force-stop` e riapertura: l'operazione resta `cancelled`, **non riparte da sola** (controllato per un minuto). Il copione ("Riprendi") descrive un comportamento diverso: da annotare come differenza fra copione e app |
| 1ª corsa: 209 file rimasti (22:42-22:56), 100 interruzioni chieste, fermate alla 27ª | **ok** | 14 morti del processo (`kill -9`) e 13 cadute del Wi-Fi (3-20 s). Lo spostamento da 209 elementi finisce (`done`, 209 `done`) durante le interruzioni; le altre sono state inutili e ho fermato lo script |
| 2ª corsa: mossa di massa da **1.179 elementi** (6 cartelle e 1.173 file, 901 kB) `F9` + `F15` + `campioni` da `test/mcg-campo` a `test-comune/mcg-campo/Z16` (23:01:24-23:37:52), seme 5, 100 interruzioni chieste | **passata** | **44 morti del processo e 33 cadute del Wi-Fi in 36 minuti** (77 interruzioni vere, 76 durante lo spostamento e la 77ª appena dopo la fine; ho fermato lo script a quel punto, ma il Wi-Fi era rimasto spento: l'ho riacceso con una chiamata breve di `s25.ps1`, l'unico modo ammesso. Da ricordare: non fermare lo script a metà di un taglio). Durata 2.168 s (36 min 8 s), contro 8 min 15 s di uno spostamento da 941 senza interruzioni: 3,3 volte più lento. In totale circa **103 interruzioni** (27 + 76) su spostamenti di massa fra aree; le prime 27 in parte dopo la fine dello spostamento da 209 |
| Invarianti (dal catalogo e dal diario) | **passate** | `nas_op` `done`, 1.179 elementi tutti `done`, **0 con errore**, **1.179 percorsi di destinazione distinti** (nessun doppione), 1.173 voci di cestino `move` per i 1.173 file (copia di partenza di ogni file, "prima copio, poi controllo"), massimo 2 tentativi per un elemento. L'operazione è passata una volta da `paused` (caduta del Wi-Fi) e da `running`. Scansione del NAS a freddo dopo la prova (23:38): `test` = 1.780 file, tutti invariati, `spariti` 0 e `rilevati` 0; `test-comune` = 1.794 file. Il catalogo ha in `test` solo `Backup/…/mcg-prova` 1.674, `Versioni` 100 e 6 file alla radice (niente più in `F9`, `F15`, `F16`, `campioni`) e in `test-comune` 1.792 file: `Z16/F15/sorgente` 274, `Z16/campioni` 96, `F16/dest` 300, `F15/dest` 20 (+5 in `prova-b`), `mille-rinominata` 940, `campioni` 97, 60 file alla radice. Non ho potuto guardare il NAS dal PC (nessun verificatore di sola lettura per questo layout): file a metà e temporanei non verificati dal lato NAS |
| NA-12 (la mossa avanza a fette vicine) | **non misurato** | |

**Cosa resta da vedere (F16-tel).** Un controllo del NAS dal PC con il verificatore dopo uno spostamento di massa (usa `mcg-it`, quindi non applicabile qui); i 6 cartelle e i 1.173 file sono nel catalogo ma non ho confrontato le impronte.


## B-S6 · F34 e T-16 (a): "Salva", "Salva una copia", "Torna all'originale" in Samsung Gallery (23:43-23:55, da solo)

**Come.** Dalle Prove guidate T-16 "1 · Apri una foto in Samsung Gallery": la prova apre sempre la stessa foto di `mcg-prova`, `DCIM/mcg-prova/20240312_101533.jpg` (un fixture da 677 B, immagine sintetica blu 64×48). Nell'editor di Samsung (`SPEActivity`) ho ruotato e toccato "Salva"; poi "Salva una copia" dal menu a tre punti; poi, nel visualizzatore della Galleria, "Torna all'originale" dal menu a tre punti. Samsung chiede "Aumentare la risoluzione dell'immagine?": scelto "Mantieni risoluz. corrente". Solo su questa foto di prova.

| Passo | Esito | Misure e note |
|---|---|---|
| 1. "Salva" (rotazione) | **ok** | Il file `20240312_101533.jpg` è riscritto **sul posto**: 677 → **2.832 B**. T-16 (a), confronto 1: `view_row_changed`=1, `view_same_id`=1, `view_rows_new`=0, `view_size_delta`=+2155, `view_generation_delta`=1140. Stesso `_ID`, riga cambiata |
| 2. "Salva una copia" | **ok** | Nasce `20240312_101533(1).jpg` (971 B) nella stessa cartella, un **media nuovo** nel MediaStore: confronto 2: `view_rows_new`=1, `view_new_same_taken`=1 (**stesso `DATE_TAKEN`** dell'originale), `view_row_changed`=0. Per F41 la copia è quindi un media nuovo con la stessa data, non una versione |
| 3. "Torna all'originale" | **ok, ma non torna ai byte di prima** | Il file diventa di **907 B** (impronta `a37fbd…`), **non** i 677 B originali (`1d4ac9…`): Samsung ricodifica l'immagine. Confronto 3: `view_row_changed`=1, `view_same_id`=1, `view_size_delta`=−1925. Quindi "Ripristina originale" della Galleria **non può** essere uno "scambio senza traffico": per l'app è una nuova modifica (altri byte) |
| Backup dopo le tre operazioni ("Fai ora", 23:51) | **ok, con due note** | Giro finito in meno di 25 secondi, "Tutto al sicuro". Nel diario: la **versione** (elemento 1850: `version`, 907 B, l'originale archiviato in `mcg-campo/Versioni/Backup/Telefono di test/mcg-prova/2024/03/signal-2024-03-12-101536 (originale).jpg`) e la copia come **nuovo media** (elemento 1851: `new_copy` `…/2026/10/20240312_101533(1).jpg`, 971 B). La versione intermedia da 2.832 B non è mai arrivata sul NAS: il backup è partito dopo il ritorno all'originale. Nota 1: la versione è finita sul percorso `signal-2024-03-12-101536.jpg` perché `20240312_101533.jpg` e quel file hanno gli stessi byte originali e l'app li tratta come **un solo media** (le 6 copie identiche dei fixture): la modifica di uno ha riscritto il file del gruppo sul NAS. Nota 2: guardare O-B14 |
| 4. 5b-R3: due "Salva" di fila, poi "Ripristina originale" dall'app prima di "Fai ora" | **non fatta** | Serve aprire il visualizzatore di quella foto nella nostra Libreria: nella Libreria da 9.890 foto non ho trovato un modo sicuro di raggiungerla (la griglia non scorre con `adb`, O-B1) e la cartella `2024/03` del NAS non mostra quel nome (il file del gruppo è `signal-…`). Da rifare a mano |

### Osservazioni di B-S6

**O-B14 (da confermare, riguarda F29 e Q4): il guardiano ha fatto 76 copie private "Backup/Area comune" senza un backup in corso.**
- **Cosa ho visto.** Dopo gli spostamenti di F16 (alle 23:38 la scansione del NAS vede `campioni` e altro nell'area comune) il diario del backup contiene, a 23:44, **76 elementi `guard_copy` `done`** (110.389 B in tutto): copie in `test/mcg-campo/Backup/Area comune/AAAA/MM/<nome>` dei media propri la cui sola copia sul NAS è nell'area comune (`campioni`, 701 B l'uno). Le ha fatte il lavoro in background (preset "Massimo"), non un "Fai ora".
- **Perché conta.** Il copione (§5, B-S8) dice che con la risposta Q4 **(b)** non ci sono copie private subito dopo lo spostamento nell'area comune, ma solo la protezione dei 30 giorni del cestino. Qui invece compaiono dopo pochi minuti. Quindi il comportamento osservato assomiglia a Q4 (a) ("copie private in `Backup/Area comune/`"): da confrontare con `docs/decisioni.md` (D9, Q4) e con `fase5-progetto.md`.
- **Passi per rifarlo.** Spostare dall'app file propri (media, anche non salvati dal telefono) dalla share privata a quella comune; attendere il giro del backup in background; guardare `backup_item` `guard_copy` o `Backup/Area comune/` nel NAS privato.


## B-S5 · F20 (cestino del NAS in minuti) e F12 (qualità delle anteprime), da solo (22/10, 00:19-01:05)

### F20 · la pulizia del cestino con la ritenzione a 5 minuti

L'utente ha consentito F20 e F12, solo sulle share di prova. Il coordinatore ha chiesto di contare i file **fuori** da `.mycloudgallery/trash/` prima e dopo e di fermarmi se l'app avesse provato a togliere altro. Non posso leggere il NAS dal PC: i file fuori dal cestino li conto con le scansioni dell'app (`giro Verify`: cartelle e file visti, escluse le cartelle di sistema `.mycloudgallery`).

| | Prima (00:19) | Dopo (00:41) |
|---|---|---|
| Scansione `test` (file fuori dal cestino) | 34 cartelle, **1.859 file** | 34 cartelle, **1.859 file** |
| Scansione `test-comune` | 16 cartelle, **1.794 file** | 16 cartelle, **1.794 file** |
| Cestino di `test` (diario dell'app, `nas_trash_entry`) | 2.499 voci `trashed` (1 di eliminazione, 2.498 "copia di partenza" di spostamenti) più 1 `restored` | **2.299** `trashed`, **200 `purged`**, 1 `restored` |

- **Esito: passata, con un adattamento.** Dopo la purga i file fuori dal cestino sono **identici** in entrambe le share (stesse cartelle e stessi file, nessuno `spariti`). L'app ha tolto **200 voci** del cestino di `test`, le **più vecchie** (19:45-20:57 del 21/10, ora dell'app) e solo quelle: le 2.299 rimaste hanno data dalle 20:57 in poi (fino alle 23:38). Il tetto è quello di E-D4: 200 voci per giro. Dal log: "cestino: 200 voci scadute eliminate". La parte "con il loro record firmato" non l'ho potuta verificare dal NAS (nessuna lettura delle cartelle di sistema dal PC). Nessun tentativo di togliere qualcosa fuori da `.mycloudgallery/trash/`.
- **Marcatore** `debug-trash-retention-minutes.txt` scritto con 5 alle 00:19 e **tolto** alle 00:41.
- **O-B15 (copione sbagliato: la pulizia non gira "in minuti").** La pulizia è il lavoro periodico `NasTrashPurgeWorker`, **una volta al giorno** (`NasWork.kt`, `DAILY_PERIOD`, `TRASH_PURGE_WORK`); la prossima era fra 17 h 45 min. Per 10 minuti con la ritenzione a 5 minuti non è successo niente (2.499 voci invariate). Un lavoro forzato con `cmd jobscheduler run -f` viene rifiutato da WorkManager: "Delaying execution for NasTrashPurgeWorker because it is being executed before schedule". Il copione ("aspetta 6-8 minuti") non può funzionare così.
- **Come l'ho fatto.** Sul database di WorkManager della **nostra app di debug** (`no_backup/androidx.work.workdb`, con l'app ferma) ho messo `last_enqueue_time` di 25 ore fa per quel lavoro (copia di sicurezza prima), poi ho riaperto l'app: il lavoro è partito da solo all'avvio (00:32:06). Un primo tentativo di rimettere il file con `run-as sh -c 'cat >'` ha troncato il file a 4 KB: l'ho riscritto con `adb push` in `/data/local/tmp` e `run-as cp` (controllato con l'impronta). Per le prove future conviene un pulsante o un'azione di debug "pulisci ora".
- **Dopo il primo giro** il lavoro periodico si riprogramma a +23 h 50 min e la "continuazione" (`nas-trash-purge-now`) non è partita: nei 10 minuti seguenti non sono state tolte altre voci. Le 2.299 rimaste le toglierà la pulizia dei prossimi giorni (con il marcatore tolto, a 30 giorni: quindi restano).

### F12 · qualità delle anteprime Standard, Alta, Risparmio

Le misure le ho prese con le esportazioni della Diagnostica (`INDEX_REPLICA_STATE.bytes`, ricetta ESPORTA) che ho salvato io (il dialogo di sistema "Salva" della nostra app). **Non** ho misurato la cartella `.mycloudgallery` del NAS con Esplora risorse (cestino da 2.000+ voci e altri costi fissi la renderebbero inutile, vedi sopra).

| Passo | Stima in Impostazioni | `INDEX_REPLICA_STATE.bytes` | Scarto | Note |
|---|---|---|---|---|
| Standard (all'inizio, 00:44) | circa 660 kB | **659.758** | 0% | 157 anteprime per questo livello |
| Alta | circa 750 kB | **750.017** | 0% | `converging` falso, `level` `HIGH` |
| Risparmio | circa 407 kB | **519.277** | **+27,6%** | fuori dal ±15%: dopo Alta le anteprime in più non sono state tutte rilasciate o ci sono costi fissi |
| Standard (di nuovo) | circa 660 kB | **609.104** | −7,7% | messa di nuovo come era all'inizio |

- **O-B16 (la convergenza non parte da sola).** Dopo ogni cambio la pagina dice "Ora le anteprime occupano 660 kB: il telefono sta adattando lo spazio, un po' alla volta" e **per 8 minuti non cambia niente**: l'adattamento lo fanno i lavori periodici (`HqPreviewsWorker`, `PreparationWorker`, `IndexSyncWorker`, ogni 6 ore), che nessuno avvia quando si cambia qualità. Con gli stessi lavori resi "dovuti" (stesso metodo di F20) l'adattamento è finito in meno di un minuto.
- **O-B17.** Con la qualità Alta la Diagnostica dice ancora `ACT_DISABLED` per `HQ_PREVIEWS` (`rule.disabled` ha `HQ_PREVIEWS`): l'anteprima HQ **sul NAS** non si prepara con le regole attuali; la misura "Alta" riguarda solo la replica sul telefono. Quindi la domanda "lo spazio sul NAS segue la stima" non è provata (la stima della pagina è per il telefono: "Qualità sul telefono").
- Nella cartella Download del telefono restano 4 file `mycloudgallery-diagnostica (2..5).json` (esportazioni di F12, solo numeri) più il primo e l'E2: li toglie l'utente.


## B-S7, B-S12, B-S14, B-S18 (da solo, 22/10 dalle 23:56): parti fatte

### B-S7 · F34-bis (VE-10) e F27

| Prova | Esito | Misure e note |
|---|---|---|
| F34-bis, VE-9 (il PC cambia il file durante una versione) | **saltata** | Serve scrivere dal PC un file in `Backup/`; fuori dalle regole (le scritture dal PC sono solo dentro `mcg-campo/` con uno strumento esistente del banco, che qui non c'è) |
| VE-10: modifica in Galleria con l'app chiusa, poi apertura dell'app (scambi e versioni ripartono entro 2-3 minuti) | **non confermata** | `am force-stop`, in Galleria "Salva" (la foto `20240312_101533.jpg` passa a 2.832 B, impronta nuova), poi apertura dell'app alle 23:57:03 (preset "Massimo", Wi-Fi di casa). Dopo **12 minuti** (fino alle 00:10) nessuna versione è partita: `backup_item` senza elementi nuovi, `media.backup_todo`=1, la capsula dice "1 in coda · Al prossimo giro", nel log nessun `PhotoBackupWorker` dopo l'apertura (solo le scansioni del NAS). Le versioni delle modifiche fatte a app chiusa aspettano quindi il giro successivo del backup, non partono alla riapertura. Nota: "Proteggi subito le foto nuove" riguarda le foto nuove, non le modifiche; lo "scambio" di "Ripristina originale" non è provabile con la Galleria Samsung (vedi B-S6: i byte tornano diversi) |
| F27, rinomina del dispositivo | **non fatta** | Non trovo nella nostra app dove si rinomina il dispositivo: Impostazioni, Dispositivi, dettaglio ("Telefono di test") non ha un campo; Impostazioni del backup, "Cartella del dispositivo" non è modificabile e dice "Rinominare il dispositivo non cambia la cartella". Nel codice c'è `DeviceFolderPolicy.folderFor` (`DefaultBackupSettingsTest`), ma non ho una strada dalla UI |

### B-S12 · F36 (correzione di data, luogo e rotazione): parziale

| Passo | Esito | Misure e note |
|---|---|---|
| JPEG con GPS (`exif-05-gps-ora-utc.jpg`), "Correggi data e luogo" con data 13/03/2024 | **da verificare** | Visualizzatore (aperto dalla cartella `Backup/Telefono di test/mcg-prova/2024/03` del NAS) → menu → "Correggi data e luogo" (data e ora, Luogo: Lascia com'è / Cambia / Togli). Cambiato il giorno, "Correggi": dice "**Fatto** · La versione corretta è al sicuro sul NAS. L'originale resta nella cartella Versioni" **senza nessun dialogo di Android**. Nel diario: `version` `done` (elemento 1852), il file al solito percorso sul NAS passa a **1.175 B**, la versione precedente va in `Versioni/…/exif-05-gps-ora-utc (versione del 2026-10-21 05.50).jpg` e l'originale (979 B) in `(originale)`. **Il file sul telefono resta com'era**: 979 B, stessa impronta (`d9fee2…`), data EXIF 2024:03:12 10:15:30 (prima e dopo uguali, 7 tag GPS), e il visualizzatore continua a dire "12 marzo 2024, 10:15, Oslo"; `metadata_correction` è vuota e `taken_at_utc` non cambia. Quindi la correzione sembra scritta solo sul NAS, mentre il copione (§5, B-S12) si aspetta il file del telefono riscritto dopo il dialogo di sistema. Da vedere con `exiftool` sul file del NAS |
| Ruota su PNG/WebP, HEIC senza "Ruota", solo-NAS "Metti al sicuro" | **non fatti** | Il fixture `.heic` ha gli stessi byte del JPG (un media solo) e non c'è nella cartella; per gli altri serviva raggiungere il visualizzatore di quei file |

### B-S14 · T-15 e F40, simulati con `adb shell am start` verso la nostra app

Il comando: `am start -a android.intent.action.SEND -t <tipo> --eu android.intent.extra.STREAM content://media/external/images/media/<id> --grant-read-uri-permission -n dev.pampa.mycloudgallery.debug/<attività>`, con foto e video di `mcg-prova` (indirizzi `content://` veri, non `file://`).

| Prova | Esito | Misure e note |
|---|---|---|
| T-15: ricezione di prova (`FieldProbeShareActivity`), "Legge subito" | **passata** | `t15.received`=1, `scheme_content`=1, `scheme_file`=0, `open_ok`=1, `open_ms_avg`=5, `bytes`=44, `grant_flag`=1, `perm_granted`=0 (il permesso arriva dal flag, non da altro) |
| F40: "Salva sul NAS" con una foto (`ShareReceiverActivity`) | **passata** | Schermata "Da salvare: 1 foto · 44 B", destinazioni "Area privata · Ricevute" e "Area comune · Ricevute", "Non carico niente finché non tocchi «Salva sul NAS»"; la copia parte subito (cartella `incoming` con un file). Dopo il tocco: "1 file in coda · Li carico sul NAS e controllo che arrivino interi. Puoi chiudere"; poco dopo `IncomingUploadWorker` carica e svuota `incoming`. Nel NAS: `test/mcg-campo/Ricevute/IMG_mcg_0101.jpg` (44 B), **data 22 ottobre 2026** (non 1970). Il pulsante "Salva sul NAS" va toccato alle coordinate: il testo del titolo ha lo stesso nome |
| F40: video da 3 GB (`VID_mcg_grande_1.mp4`) | **passata** | "Niente da salvare · Nessuno dei file ricevuti si può salvare sul NAS · **1 troppo grandi (al massimo 2,1 GB per file)**", subito, e `incoming` vuota |
| F40: NAS non raggiungibile (Wi-Fi spento 90 s con `s25.ps1`) | **passata, ma la coda riparte tardi (O-B19)** | Con il Wi-Fi spento, "Salva sul NAS" dice "1 file in coda · Il NAS non risponde adesso: tengo i file sul telefono e li carico appena risponde"; `incoming_item` `queued`. `IncomingUploadWorker` risponde "Wait" alle 01:11:29 e si riprogramma **fra 9 min 39 s** (backoff, non al ritorno della rete: il vincolo di rete è soddisfatto alle 01:13); alle 01:26 (15 minuti dopo) l'elemento è ancora `queued` e non ho visto il carico: la regola del copione "Wi-Fi tornato: salgono e spariscono" non è confermata |
| IN-5: preset "Notte" di giorno, "Salva sul NAS" una foto (`IMG_mcg_0103.jpg`) | **non confermata** | Con "Notte" (messo alle 01:17, rimesso "Massimo" alle 01:27) la foto è finita in coda e non è salita nei 9 minuti successivi (alle 01:26 `queued`): IN-5 ("sale subito") non provata; da rifare con la coda vuota, perché l'altra foto in coda dal Wi-Fi spento era in attesa dello stesso lavoro |
| 100 immagini, un video da 1 GB, Chrome/WhatsApp/Quick Share, "Indietro a metà" | **non fatti** | Servono altre app o più file per `am start` (nessun modo di passare 100 indirizzi `content://` dalla riga di comando) |

**O-B18.** Spostando una cartella intera fra aree l'app lascia nell'area di partenza la cartella **vuota** (`mille-rinominata`, `F15`, `F16`, `F9`, `campioni` in `test/mcg-campo` con 0 elementi): i file vanno nel cestino del NAS, la cartella no. Non è per forza un difetto (il progetto non dice che vada tolta), ma "Sposta" di una cartella lascia sempre un guscio.

### B-S18 · FF-4: la VPN con WireGuard

`pm path com.wireguard.android` non risponde nulla (WireGuard non è installato): la prova passa al blocco C (C-24), come dice il copione.

### Saltate o non fatte da solo

B-S8 (F29: serve `test2` dal PC), B-S9 e B-S10 (già passate il 20-21/10), B-S11 (F35: video modificato in Galleria: non provato), B-S13 (T-14 con Gmail, WhatsApp, Telegram, OneDrive, I miei file: altre app), B-S15 (T-16 b: Google Foto è un'altra app; "Modifica con…" verso Samsung Gallery è già provato in B-S6), B-S16 (F42: serve un'app che riceve), B-S17 (permessi di Android: impostazioni del telefono).


### Ripresa dopo la pausa (02:24-03:05): correzioni a B-S14 e B-S11

Il telefono è tornato mio alle 02:24 (batteria 79%, Wi-Fi acceso, preset "Massimo"). Alle 02:24 i due file in coda di "Salva sul NAS" (`IMG_mcg_0102`, `0103`) risultavano già caricati.

**Correzione a B-S14 (nota di prima: "IN-5 non confermata" e "la coda riparte tardi").** Le due prove di prima erano **confuse** fra loro: il lavoro dei file ricevuti era già in attesa di un nuovo tentativo programmato e io ho messo "Notte" a metà. Ripetute una alla volta, con la coda vuota:

| Prova | Esito | Misure e note |
|---|---|---|
| **IN-5**: preset "Notte" di giorno, coda vuota, "Salva sul NAS" `IMG_mcg_0104.jpg` (02:26:15) | **passata** | La foto è `uploaded` e in `mcg-campo/Ricevute/` entro 13 secondi, senza toccare altro. Il preset è tornato "Massimo" alle 02:29 |
| Caduta del Wi-Fi: "Salva sul NAS" con il Wi-Fi spento (`s25.ps1` 120 s) e coda vuota, `IMG_mcg_0107.jpg` (02:44:23) | **in parte: la coda riparte tardi** | Subito: "1 file in coda · Il NAS non risponde adesso: tengo i file sul telefono e li carico appena risponde", file in `no_backup/incoming`, `incoming_item` `queued`. `IncomingUploadWorker` risponde "Wait" (02:44:23) e si riprogramma. **Wi-Fi tornato alle 02:46:10, il file sale alle 02:59:24**: **13 minuti e 14 secondi** dopo il ritorno della rete, circa 15 minuti dopo il salvataggio. Il copione dice "Wi-Fi tornato: salgono e spariscono". Lo stesso schema di D-12 (un tentativo programmato che non si sveglia al ritorno della rete). Nel frattempo (e per la prova di prima) altri file ricevuti **restano dietro** a quel tentativo: sono saliti insieme alle 01:27:49 |
| Una foto in coda che **sparisce** (`IMG_mcg_0106.jpg`, 02:31:56) | **da indagare, non riprodotta** | Stessa procedura con il Wi-Fi spento: la schermata dice "1 file in coda" ma poi `no_backup/incoming` è vuota, non c'è nessuna riga in `incoming_item` e il file non arriva mai in `Ricevute` (5 file: da `0101` a `0105`). Nel log, alle 02:32:08 "sessione chiusa: PROCESS_STOPPED" (processo fermato, per il mio tasto Home o dal sistema). Con `0107` (stessa procedura, ma con il processo rimasto vivo) il file è rimasto. Se si conferma, è una **perdita** del file ricevuto quando il processo muore con la coda in attesa: da rifare con `kill -9` fra "Salva sul NAS" e il ritorno del Wi-Fi |

**B-S11 · F35 (video modificato in Galleria): non fatta.** I fixture video di `mcg-prova` (`VID_20240312_101534.mp4`, `Screen_Recording…`, `VID-20240312-WA0003.mp4`, `registrazione-interrotta.mp4`, `troncato.mp4`) pesano circa 2 kB e sono volutamente non riproducibili: aperti in Samsung Gallery danno la miniatura "immagine rotta", **nessun tasto di modifica**, e `durata 0:00`. L'altro video (`VID_mcg_grande_1.mp4`, 3 GB) è un MP4 più byte casuali: non si apre. Serve un video vero di prova.


## F13 · benchmark con la libreria mista (S25, 22/10, 03:00-04:46)

**Come.** Autorizzato dal coordinatore ("poi F13, con la variante di misura che poi togli"). Telefono a 120 Hz (letto: 120 Hz) con "Fluidità movimento: Adattiva" impostata dall'utente, schermo acceso e sbloccato, in carica (79-78%), nessun altro uso durante la misura. Al posto di `tools\check.ps1 -SkipGradle -Bench` ho lanciato **gli stessi argomenti di Gradle** a mano per aggiungere `--max-workers=2` (RAM libera sul PC da 7 GB a meno di 1 GB durante la compilazione, per le altre sessioni che girano): `gradlew --console=plain --max-workers=2 :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark`, poi `tools\bench\compare.ps1 -RefreshHz 120`. **Build riuscita in 1 h 45 min 8 s** (26 prove, 6 saltate, 0 fallite); le due app di misura (`dev.pampa.mycloudgallery.bench` e `.benchmark`) sono state tolte da AGP: sul telefono resta solo la debug (`pm list packages`). Nessuna installazione di altro, la debug non è stata toccata.

**Risultato di `compare.ps1`:** **OK 33, FAIL 1, MANCA 4, NUOVO 3, NOTO 1.**

| Prova | Metrica | Valore | Soglia / riferimento (13/10) | Esito |
|---|---|---|---|---|
| `coldStart` | `timeToFullDisplayMs` p90 | **659 ms** | < 1000; ≤ 750 (ref 681 + 10%) | OK (TTFD ≤ 750 ms) |
| `viewerOpen` | `viewerOpenMs` p90 | **18,0 ms** | < 100; ≤ 25 (ref 21,1 + 15%) | OK |
| `viewerOpen` | fotogramma più lungo (`viewerOpenMaxFrameMs`) | 63,3 ms | ≤ 16,7 (già fuori nel riferimento: 97,3); guardia ≤ 107 | NOTO, dentro la guardia (meglio del riferimento) |
| `scroll_*` (8 prove) `jankPercent` medio | | 0,18-0,53% | ≤ 1% | OK le 8 |
| `scroll_*` `frameDurationCpuMs` P95 | | 8,7-12,9 ms | ref + 10% | OK le 8 |
| `pinch` | `jankPercent` medio e P95 CPU | 0,41% e 12,5 ms | ≤ 1%, ref + 10% | OK |
| `scroll_days_square` | guardia: `jankPercent` medio | **0,489%** | ≤ **0,331** (riferimento 0,181 + 0,15 di rumore) | **FAIL** (sotto il tetto assoluto di 1%, sopra la guardia di 0,158) |
| `scroll_all_square` | guardia `jankPercent` | 0,185 | ≤ 0,226 (ref 0,076 + 0,15) | OK, ma vicino al limite |
| `coldStartNasFirst` | TTFD p90 | 728,6 ms | < 1000 | OK, senza riferimento (NUOVO) |
| `viewerOpenNas` | `viewerOpenMs` p90 | 15,5 ms | < 100 | OK, senza riferimento (NUOVO) |
| `libraryMemory` | `libraryPssKb` max | 306.130 | senza riferimento | NUOVO |
| `searchWarm`, `searchColdContent`, `searchColdFilters`, `searchMemory` | | | | MANCA (la ricerca è della Fase 6, non c'è ancora) |
| `zoom200Mp` | | | solo tablet | N/A |

- **Il FAIL.** Una sola prova supera la guardia della Fase 4A: `scroll_days_square` (jank 0,489% contro un riferimento del 13/10 di 0,181%). Tutte le soglie assolute sono rispettate. Il rumore di una sola misura sui 0,15 punti non è noto. Ho provato a rifare la sola prova (`…LibraryScrollBenchmark#scroll_days_square`), ma alle 04:47, a Gradle appena partito e prima di installare qualunque cosa, un'altra sessione ha chiesto il telefono in esclusiva e l'ho fermata: **da rifare** (circa 15-20 minuti con la compilazione già in cache). I risultati della prima corsa sono copiati in `build/campo/20261021-165146-8c4811b9/f13-run1/`.
- **Per le altre misure** i numeri sono dentro i riferimenti: TTFD (659 ms) e apertura del viewer (18 ms) migliori o uguali a quelli del 13/10, jank di scorrimento fra 0,18% e 0,53%.

**Rerun di `scroll_days_square` (22/10, 04:53-05:10, 16 min 49 s di Gradle, stessa procedura).** `jankPercent` medio **0,399%** (prima corsa 0,489%), `frameDurationCpuMs` P95 **11,3 ms** (≤ 12,85: ref 11,68 + 10%), tetto assoluto (≤ 1%) rispettato. **Sempre sopra la guardia della Fase 4A** (≤ 0,331 = riferimento 0,181 + 0,15 di rumore): due misure indipendenti (0,489 e 0,399) dicono che non è rumore, è un peggioramento di circa 0,2-0,3 punti di jank rispetto al 13/10 per quel livello (giorni, celle quadrate) sulla libreria mista da 100 mila righe; la durata CPU dei fotogrammi invece è migliorata. Le altre 25 misure della prima corsa sono dentro le soglie. Variante di misura tolta (`pm list packages`: solo `dev.pampa.mycloudgallery.debug`).


### Completamenti e correzioni (05:10-05:30)

- **VE-10 (corretto).** La versione della modifica fatta a app chiusa (alle 23:56) è comparsa sul NAS solo alle **01:09** ("Versione intermedia, 22 ottobre 2026 alle 01:09" nel foglio Versioni di quella foto), cioè circa **70 minuti** dopo la riapertura delle 23:57 (app in primo piano, preset "Massimo"), non entro 2-3 minuti. Con l'app **aperta** invece, due "Salva" di fila in Galleria (05:17:09 e 05:17:30) sono diventati versioni sul NAS e il backup ha chiuso "Tutto al sicuro" alle 05:17: la versione "di adesso" era già "sul telefono e sul NAS, al sicuro" quando ho aperto il foglio, cinque minuti dopo. La differenza sta nell'app aperta o chiusa quando si salva.
- **"Ripristina originale" dalla nostra app (scambio): passata.** Sulla foto `20240312_101533.jpg` (versione di adesso già al sicuro): dal visualizzatore del file del NAS → menu → Versioni (elenca "Versione di adesso", tre "Versione intermedia" e l'originale; "Ripristina originale", "Elimina le intermedie…") → "Ripristina originale". Il foglio dice "L'originale torna al suo posto sul NAS e la versione di adesso, che ha già una copia controllata, va nella cartella Versioni. **Non si carica niente.**"; dialogo di Android "Consentire all'app MyCloud Dev di modificare questa foto?" confermato da me (foto di prova); risultato "Fatto · L'originale è tornato al suo posto sul NAS. Non è stato caricato niente." **Sul telefono il file torna a 677 B con l'impronta originale `1d4ac9…`**: quindi lo scambio dell'app riporta i byte esatti, a differenza di "Torna all'originale" di Samsung (907 B). 5b-R3 (due "Salva" di fila prima di "Ripristina originale") non provata nella forma "backup non ancora fatto": il backup aveva già messo al sicuro la versione.
- **O-6 di nuovo (Wi-Fi automatico).** Nelle ultime prove un taglio di "120 s" con `s25.ps1` è durato circa 30 secondi (sessione NAS di nuovo pronta alle 05:13:35, `wifi_on` tornato a 1 senza `s25.ps1`): "Attiva Wi-Fi automaticamente" è di nuovo attivo (impostazione dell'utente o di un'altra sessione): le prove con un Wi-Fi spento a lungo non sono affidabili finché non viene spento. Per questo la prova "una foto in coda che sparisce con `kill -9`" non l'ho potuta riprodurre (ho solo il caso del 02:31, `IMG_mcg_0106.jpg`).
- **F13, rerun** in fondo alla sezione F13.

## Riepilogo del blocco B (21-22/10, in testa il quadro generale)

**Fatte e passate.** T-09 (a-f e scrittura) con D-3 corretto nei testi; GA-7; LA-7 (rotazione); F25 (nome diverso → "(2)", identico collegato senza traffico); F26 (ripristino: una sola volta); F4, F5, F9 (con 4a-R9: il congelamento torna per un numero nuovo; NA-4, NA-5, NA-9); F15 (crea, rinomina anche solo maiuscole, sposta nella share e fra share, elimina, ripristina, nome in conflitto, cartella intera, fogli di eliminazione con numeri veri, doppio tocco su "Elimina subito" senza conferma da solo, NA-1, 4b-R8, file bloccato dal PC con riparto da solo); F16-tel (circa 103 interruzioni su spostamenti di massa da 1.179 elementi, 0 errori, nessun doppione); F20 (200 voci, nessun file fuori dal cestino toccato); F12 (misure, con lo scarto sul Risparmio); F34/T-16 (a) (Salva, Salva una copia, Torna all'originale, "Ripristina originale" dell'app); F40 e T-15 simulate (ricezione, "Salva sul NAS", rifiuto del file da 3 GB, IN-5); F13 (33 su 34, 1 FAIL ripetibile).

**Difetti, dalla gravità più alta.**
1. **Alta, da confermare:** un file messo in coda da "Salva sul NAS" a NAS non raggiungibile può sparire (visto una volta, `IMG_mcg_0106.jpg`, 02:31): nessuna riga, nessun file in `incoming`, mai arrivato.
2. **Media:** F36, "Correggi data e luogo" dice "Fatto" ma scrive solo il NAS (1.175 B), il telefono resta com'era e la data in Libreria non cambia, senza dialogo di Android.
3. **Media:** D-B3 (F26), una foto cancellata in Galleria prima del backup sparisce dalla Libreria senza nessun avviso "Persa prima del backup" (il catalogo la segna `lost`, nessuna riga di problema).
4. **Media:** O-B14, il guardiano fa copie private `Backup/Area comune` (76) senza un backup in corso, in contrasto con Q4 (b); e O-B19, la coda di "Salva sul NAS" riparte circa 13-15 minuti dopo il ritorno del Wi-Fi (backoff), con gli altri file ricevuti bloccati dietro.
5. **Media:** F13, `scroll_days_square` supera la guardia della Fase 4A in due misure (0,489 e 0,399% contro 0,331); VE-10, una modifica fatta a app chiusa diventa versione dopo circa 70 minuti.
6. **Bassa:** F12, "Risparmio" +27,6% sulla stima (la convergenza non parte da sola: servono i lavori periodici); D-B1 e D-B2 (testi dei fogli); "Ferma" annulla invece di mettere in pausa (il copione dice "Riprendi"); O-B9 (nuova cartella dentro "Sposta" chiude la scelta, visto una volta); cartelle vuote lasciate dopo uno spostamento fra aree; F27 senza voce per rinominare il dispositivo; `Failure(NotFound)` nel log di uno spostamento.
7. **Differenze fra copione e app (non difetti):** la scansione del NAS non guarda da sola in pochi minuti (E-R15); Paint non blocca il file (serve un blocco esclusivo); la pulizia del cestino è giornaliera; Samsung "Torna all'originale" non ridà i byte esatti.

**Da vedere con le app vere.** T-14 (Gmail, WhatsApp, Telegram, OneDrive, I miei file, Drive, il selettore di sistema, blocco dell'app e tolleranza dei 2 minuti, "nascondi con il selettore aperto"); T-15 da Galleria, I miei file, Chrome, Quick Share e WhatsApp (`t15.caller`, `scheme_*`), 100 immagini, un video da 1 GB, "Indietro a metà"; F42 (condividere senza posizione verso Gmail/WhatsApp/Telegram, con `exiftool`); F41 con Google Foto; F29 con `test2` dal PC; F35 (un video vero tagliato); 5b-R3 nella forma "due Salva e Ripristina prima del backup"; VE-9 (il PC cambia il file durante la versione); permessi parziali di Android (B-S17); WireGuard (C-24); `exiftool` sul file corretto di F36; la prova dell'avviso di "Persa prima del backup" con un file che sparisce durante un giro.

**In sospeso.** F27 (come rinominare il dispositivo); la verifica dal NAS di F16 (nessun verificatore di sola lettura per quel layout); il verificatore del NAS per i manifesti con righe `edit=` (D-14), anche per le 16 "originali in Versioni/" di B-S2; la pulizia finale dell'utente (NAS di prova: `test/mcg-campo/F15`, `F16`, `F9`, `campioni`, `mille-rinominata`, `Prova-A`, `coda-na1`, `Ricevute`, `prova-4b-rinominata`, `sotto`, `Backup/Area comune`; `test-comune/mcg-campo/*` compreso `Z16`; le oltre 2.000 voci del cestino del NAS; sul telefono `mcg-prova` e le 6 esportazioni in Download); il preset e il Wi-Fi di Android lasciati come all'inizio.

**Nota finale su "Salva sul NAS" a NAS non raggiungibile (05:23-05:38).** Prova ripetuta con la morte del processo: Wi-Fi spento da `s25.ps1`, `IMG_mcg_0109.jpg` messa in coda alle 05:23:35 (`IncomingUploadWorker: Wait`), `kill -9` dell'app alle 05:23:39, Wi-Fi tornato alle 05:25:30 (quindi il taglio è durato circa 2 minuti). Il file e la riga `queued` **sono rimasti** dopo la morte del processo e il file è salito alle **05:38:36**: 15 minuti dopo il salvataggio, 13 dopo il ritorno della rete (lo stesso ritardo di 15 minuti di prima). Quindi con `kill -9` **non si perde niente**: il caso di `IMG_mcg_0106.jpg` (02:31, file e riga spariti) resta unico e non spiegato; lo declasso da "alta" a "da indagare" (media) nel quadro qui sopra.
