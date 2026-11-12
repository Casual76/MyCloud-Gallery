# L'aggiornamento dentro l'app (pacchetto U, 12/11/2026; giri 1 e 2 delle critiche)

Richiesta dell'utente del 12/11: i genitori non hanno il Pampa Store e devono poter aggiornare MyCloud Gallery, canale **Stabile** (di serie) o
**Beta** a scelta, «da internet, come le altre app». Supera D33 («nessun installatore», niente `REQUEST_INSTALL_PACKAGES`) e la regola «nessun host
nuovo» per questa sola funzione (`docs/decisioni.md`, «Pacchetto U»; `docs/sicurezza.md` C480-C490, L610-L616, righe in 4-bis). Il manifest firmato
dei blocchi (K1/K2, `core-release`, letto dal NAS) non cambia e non c'entra.

## Che cosa fa e dove sta

| Pezzo | Dove |
|---|---|
| Sorgente (repository, URL dell'elenco, modello dell'URL dell'APK, host ammessi, tetti, 24 ore): **l'unico posto da cambiare** se gli APK si spostano | `feature-update/.../source/UpdateSource.kt` (`UpdateSourceConfig`) |
| Regole pure (sha256 e peso obbligatori, forma della versione, note pulite, nomi, indirizzo, questa app, versione annunciata, versionCode crescente, firma uguale, 24 ore, «Dopo» che rimanda, quali guasti si riprovano) | `feature-update/.../policy/UpdateRules.kt` |
| Client di rete: `EngineHttp` dell'engine avvolto (host ammessi a ogni salto, rimandi a mano, tetti, niente cookie) — terza eccezione di rete | `feature-update/.../net/` (classi `internal`) |
| Installazione in due tempi: «prepara» (fino al file verificato, «Pronta») e «installa» (la sessione di Android), con le due meta' pubbliche dell'engine (`download`, `installFile`) e i controlli dell'app in mezzo; ogni messaggio dell'engine tradotto in un problema dell'app | `feature-update/.../install/GuardedUpdateInstaller.kt` |
| Stato, controlli, canale, versione trovata ricordata, avviso e promemoria, dati mobili per tentativo, tentativo salvato (permesso, installazione), «Aggiornata» | `feature-update/.../AppUpdates.kt`, `UpdateStore.kt` (uno per processo, lo crea `:app`: `di/UpdateModule.kt`) |
| Pagina Impostazioni → Aggiornamenti, avviso, pezzo per la pagina «versione fermata» | `feature-update/.../ui/` |
| Voce «Aggiornamenti» nelle Impostazioni: **in cima** (sotto la famiglia) con una versione nuova, in «Avanzate» senza | `feature-settings/.../update/UpdateEntry.kt`, `SettingsScreen.kt` (porta legata da `:app`) |
| Avviso appoggiato sopra la barra delle schede (mai sopra la pillola, il visore o le altre pagine; il fondo della Libreria cresce della sua altezza), icona vera dell'app nella figura, il ciclo di vita dell'activity che muove il cancello (`UpdateLifecycleEffect`) | `app/.../main/TabBarNotice.kt`, `MainScaffold.kt`, `AppRoot.kt` |
| Controllo silenzioso all'apertura (dopo il primo disegno, al piu' ogni 24 ore) | `app/.../MyCloudGalleryApp.kt` |
| «Aggiorna» nella pagina «versione fermata» (una figura sola) e nell'avviso di sola lettura | `app/.../release/ReleaseRootUi.kt` |
| Verifica dell'elenco dopo la pubblicazione; regola dei nomi delle versioni | `tools/release/verifica-elenco.ps1`, `release-lib-core.ps1` |

Moduli dell'engine aggiunti con il suo script (`engine\tools\engine-install.ps1 -AppRoot . -Modules engine-foundation,engine-ui,engine-widget,engine-update`):
`engine-update` ed `engine-net`. Nessuna dipendenza esterna nuova (i due moduli dichiarano solo `project(...)`). `engine/` non e' modificato.

**Host**: l'elenco solo da `raw.githubusercontent.com`; l'APK solo da `github.com`, `release-assets.githubusercontent.com` (quello a cui GitHub rimanda
oggi: letto il 12/11 sulla risposta 302 di un asset pubblico) e `objects.githubusercontent.com` (quello di prima). Un rimando verso altro si rifiuta
senza aprirlo.

## Il percorso di chi usa l'app

1. **All'apertura** (al piu' una volta ogni 24 ore, dopo il primo disegno) l'app legge l'elenco in silenzio. Con una versione nuova compare l'avviso,
   appoggiato **sopra** la barra delle schede della galleria (Libreria, Album, Raccolte): l'icona dell'app con il segno, «Nuova versione»,
   «1.1.0 · 110 MB», «Dopo» e «Aggiorna». Non copre la pillola, non sta sopra il visore ne' sopra le Impostazioni; finche' c'e', il fondo della
   Libreria cresce della sua altezza (giro 2, UU2-4): le foto piu' recenti e il conteggio salgono sopra la scheda (chi e' in fondo resta in fondo).
2. **«Dopo»** lo rimanda di tre giorni; al terzo «Dopo» di fila tace trenta giorni, poi torna e il conto riparte (giro 2: mai spento per sempre; al piu'
   tre avvisi in circa 39 giorni, anche se l'elenco alterna due nomi). La versione trovata resta: la voce «Aggiornamenti» sale in cima alle
   Impostazioni con il segno, anche dopo un riavvio, e la pagina la mostra subito (e, aperta, ricontrolla se sono passate 24 ore).
3. **«Aggiorna»**: sui dati mobili prima chiede («Scarica lo stesso», il peso una volta). Il consenso vale per quel tentativo.
4. **Il permesso** «installa app sconosciute», la prima volta: l'interruttore nella figura, «Serve un permesso», «Accendi l'interruttore e torna
   qui», «Consenti». Il tentativo e' salvato: tornando (anche se Android ha chiuso l'app per darlo) riparte da solo; se nel frattempo il telefono
   e' passato ai dati mobili, chiede di nuovo.
5. **Lo scaricamento**: l'anello che si riempie (TalkBack lo sente come avanzamento, ogni quarto), «Annulla»; la «×» nasconde l'avviso, lo scaricamento
   continua.
6. **Pronta**: il file e' controllato (sha256, questa app, la versione annunciata, piu' nuova, stessa firma), e di nuovo subito prima di Android. **Il
   cancello** (giro 2, C490): solo se, **finiti i controlli**, l'activity dell'app e' RESUMED il file passa ad Android; altrimenti «Pronta da
   installare» con il segno «tocca qui» (non la spunta di «Aggiornata»), «Installa» e «Annulla». Vale per la fine dello scaricamento, per «Installa» e
   per il ritorno dal permesso.
7. **Passato il cancello**: «Sto installando · L'app si chiude: poi riaprila», poi «Tocca «Aggiorna» nella finestra di Android». Toccato «Aggiorna»,
   la finestra si chiude e l'app torna davanti mentre Android installa: resta **«Sto installando»**, senza tasti (prima tornava «Pronta» con
   «Installa»: un secondo tocco avrebbe aperto un'altra sessione). Se arriva un esito vince: «annullata» (Annulla, o Play Protect) torna «Pronta» con la
   riga «Se Android la blocca, scegli «Installa comunque»»; un guasto ha la sua frase. Se dopo **15 s di app davanti** non e' arrivato niente (la
   finestra si era chiusa senza scelta, o non si era aperta), «Pronta»: «Installa» riprova **senza riscaricare** (il file si ricontrolla). Il tratto
   dentro l'engine fra il cancello e la finestra e' un limite dichiarato (`docs/sicurezza.md` L616).
8. **Riaperta** con la versione nuova: l'avviso dice «Aggiornata · 1.1.0», una volta, con «Ok».
9. **Un guasto**: frasi dell'app, mai dell'engine. «Riprova» solo per la rete, il file rovinato, lo spazio e cio' che non si riconosce (senza
   riscaricare se il file verificato c'e'); per un'altra firma, un'altra app, una versione non piu' nuova, un elenco scritto male: «Chiudi» e
   «Avvisa chi ti ha dato l'app». Sull'avviso «Chiudi» chiude davvero la scheda (giro 2, UU2-2), come «Dopo».
10. **Versione fermata** dal manifest dei blocchi: la pagina offre «Aggiorna»; se l'elenco non ha ancora la correzione, la clessidra con «La versione
   nuova arriva presto» e «Controlla adesso» (mai «App aggiornata» sotto «fermata», UU2-7). In sola lettura, «Aggiorna» nell'avviso in alto lo chiude:
   l'avanzamento lo mostra la scheda sopra la pillola (UU2-8).

## La sorgente (decisione dell'utente del 12/11)

Il repository dell'app, **`Casual76/MyCloud-Gallery`, diventa pubblico al momento della release beta**. Quindi:

- elenco: `https://raw.githubusercontent.com/Casual76/MyCloud-Gallery/main/manifest.json` (il `manifest.json` che `publisher.py` scrive alla radice del
  repository dell'app, `DEFAULT_MANIFEST_PATH`, sul ramo predefinito, `get_repository_default_branch`);
- APK: `https://github.com/Casual76/MyCloud-Gallery/releases/download/{tag}/{asset}` (proprietario e nome scritti nell'app: l'elenco sceglie tag e asset,
  non un altro repository).

Finche' il repository e' privato l'elenco risponde 404: all'avvio l'app non mostra niente (in silenzio, e riprova dopo 24 ore); a mano dice «Non riesco
a controllare adesso».

## Che cosa deve contenere l'elenco

Per ogni canale (`app.stable`, `app.beta`) un oggetto con:

| campo | obbligatorio | da dove |
|---|---|---|
| `version` | si' | `versionName` dell'APK, **identico** e nella forma ammessa (sotto, «Le versioni») |
| `releaseTag` | si' | il tag della GitHub Release (`publisher.py`: `<canale>-mycloud-gallery-v<versione>`) |
| `apkAsset` | si' | il nome del file nella release (`publisher.py`: `mycloud-gallery-<versione>.apk`) |
| `sizeBytes` | **si' (l'app)** | il peso esatto dell'APK in byte |
| `sha256` | **si' (l'app)** | lo SHA-256 esadecimale dell'APK (64 cifre) |
| `changelog` | no | la nota (Markdown): l'app ne tiene al piu' 2.000 caratteri, toglie i caratteri invisibili e di direzione e mostra il primo paragrafo |

piu' `app.repository` con `repoOwner` e `repoName` (oppure `owner` e `name`, che l'engine accetta anche), **uguali** a `Casual76`/`MyCloud-Gallery`:
senza, l'engine non propone niente, e la verifica (sotto) non e' verde. Lettere, cifre, `.`, `-`, `_` nei nomi; niente barre.

Regole dell'engine: il canale **Stabile** legge `app.stable` e non propone mai una versione con il trattino; il canale **Beta** legge `app.beta` e, se
manca, `app.stable`. `publisher.py --channel stable` scrive la stessa versione in `stable` **e** in `beta`.

### Lo sha256: lo scrive il publisher (correzione del 12/11)

Il primo giro aveva trovato che `publisher.py` calcolava lo sha256 (`inspect_apk`) senza scriverlo nell'elenco: l'app, che lo pretende, avrebbe
segnato ogni versione «non ancora pronta» (fallisce chiusa). **L'utente ha deciso il 12/11 di correggere il publisher** (repository `Pampa-store-src`):
`build_version_info` ora pretende `sha256` (64 cifre esadecimali minuscole) e `sizeBytes` e li scrive nella voce del canale, accanto agli altri campi;
l'APK si legge una volta sola prima di creare la release e si rifiuta se non e' piu' quello ispezionato (`read_inspected_apk`). E' il campo `sha256`
della voce che `EngineAppUpdater` legge (`EngineManifest`, chiave `sha256`): lo prova `PublisherManifestTest`.

Dopo ogni pubblicazione **`tools/release/verifica-elenco.ps1`** controlla che sia davvero cosi' (sotto). Per un elenco scritto prima della correzione,
lo stesso strumento scrive i campi mancanti, ma solo dopo aver verificato l'asset pubblicato.

## Le versioni

Il confronto dell'app e' `compareVersions` dell'engine: i numeri uno per uno, poi il suffisso **come testo** (senza suffisso viene dopo). Da qui la
**regola di pubblicazione**, che l'app (`UpdateRules.VERSION`, una versione fuori forma non si offre) e gli strumenti (`release-lib-core.ps1`:
`Test-VersionName`, `Compare-EngineVersion`, `Test-VersionRows`; `app/build.gradle`) controllano:

1. **ogni APK nuovo ha nome E versionCode piu' alti** di ogni versione gia' pubblicata. L'unica eccezione e' lo **stesso** APK promosso da beta a
   stabile (stesso nome, stesso versionCode, stessa impronta). Un APK rifatto con lo stesso nome non arriverebbe a nessuno; uno con il nome piu' nuovo e
   il versionCode non piu' alto l'app lo rifiuta prima di Android;
2. la **stabile** ha solo numeri: `X.Y.Z` (al piu' quattro cifre l'uno, senza zeri davanti);
3. la **beta** e' `X.Y.Z-beta.NN` con **due cifre fin dall'inizio** (`beta.01` ... `beta.99`): come testo `beta.10` verrebbe prima di `beta.9`. Dopo
   `beta.99` si passa alla base dopo;
4. **mai** una `v` davanti, un `+qualcosa`, un quarto numero, `-rc` o altri suffissi, cifre che non siano `0`-`9` (le arabo-indiane o a tutta
   larghezza), un a capo: l'app non li offre, gli strumenti li rifiutano (giro 2: `[0-9]` e ancoraggi assoluti anche in PowerShell, dove `\d` accetta
   qualunque cifra Unicode);
5. la **versione corrente** e' il `versionName` intero: una `1.1.0-beta.02` riceve la stabile `1.1.0`.

**Oggi**: la prima beta e' `1.0.0`, versionCode 1, canale beta (un nome senza suffisso: la vede solo chi e' su Beta; chi e' su Stabile, di serie,
aspetta la prima stabile). **La prossima beta si chiama `1.1.0-beta.01`, versionCode 2** (una `1.0.0-beta.02` sembrerebbe un passo indietro rispetto
alla `1.0.0` e non arriverebbe a nessuno). Poi `1.1.0-beta.02` (3), ..., e la stabile `1.1.0` (lo stesso APK dell'ultima beta promosso, oppure un
APK nuovo con il versionCode dopo).

## Il comando per la beta

Dopo `tools\release\release.ps1 -Version <versione> -Channel beta` (che produce in `build\release\<versione>\` l'APK firmato, l'impronta e
`pampa-store.json` con `changelog-store.txt`), l'orchestratore lancia, **prima a secco**:

```powershell
python "C:\VibeCoded Projects\Pampa-store-src\plugins\pampa-store-publisher\mcp\publisher.py" publish `
  --repo-root "C:\VibeCoded Projects\MyCloud-Gallery-2" --channel beta --version 1.1.0-beta.01 `
  --app-id mycloud-gallery --app-name "MyCloud Gallery" --package-name dev.pampa.mycloudgallery `
  --apk-path "C:\VibeCoded Projects\MyCloud-Gallery-2\build\release\1.1.0-beta.01\MyCloudGallery-1.1.0-beta.01.apk" --skip-build `
  --changelog-file "C:\VibeCoded Projects\MyCloud-Gallery-2\build\release\1.1.0-beta.01\changelog-store.txt" --dry-run
```

poi lo stesso senza `--dry-run` (con il token in `PAMPA_GH_TOKEN`, che mette l'utente). **Niente `--repo-owner/--repo-name` ne' `--release-owner/
--release-repo`**: release ed elenco stanno nel repository dell'app (`origin`). `--app-id mycloud-gallery` tiene fissi tag e nomi degli asset
(`beta-mycloud-gallery-v1.1.0-beta.01`, `mycloud-gallery-1.1.0-beta.01.apk`: l'app li accetta, lo prova `UpdateRulesTest`). Per la stabile: `--channel stable`.

## Che cosa controllare dopo la pubblicazione

1. L'elenco si legge **senza token**: `curl -s https://raw.githubusercontent.com/Casual76/MyCloud-Gallery/main/manifest.json` (con il repository ancora
   privato risponde 404: va reso pubblico).
2. Dopo un `git pull` dell'elenco, **la verifica**:
   ```powershell
   .\tools\release\verifica-elenco.ps1 -Canale beta -Versione 1.1.0-beta.01 `
     -Apk "C:\VibeCoded Projects\MyCloud-Gallery-2\build\release\1.1.0-beta.01\MyCloudGallery-1.1.0-beta.01.apk"
   ```
   Controlla la forma della versione, che l'elenco abbia `app.repository` e sia proprio quello dell'app (obbligatorio: senza, i telefoni non ricevono
   niente), che la voce dica quella versione con un tag e un file dal nome sicuro;
   che l'APK locale sia firmato con `pampa.jks` (l'impronta di `docs/sicurezza.md` par. 5.6, un firmatario), sia `dev.pampa.mycloudgallery` con
   quel `versionName` e il versionCode di `release/versions.txt`; scarica **esattamente** l'asset della voce (ogni rimando verso un host ammesso,
   come l'app) e lo confronta byte per byte con l'APK locale; `sha256` e `sizeBytes` della voce devono essere quelli. Se mancano (un elenco di prima
   della correzione) li scrive nella voce del canale (e in quella dell'altro canale se indica lo stesso file). Alla fine riconfronta l'elenco su disco
   con quello letto all'inizio **anche quando non scrive niente** (giro 2: se nel frattempo e' cambiato, non e' verde), e prima di sostituire il file lo
   riconfronta ancora: due confronti, non una transazione (nessun blocco condiviso con un altro scrittore: si lancia a mano, da una persona sola, dopo
   la pubblicazione). **Niente commit ne' push**: l'elenco cambiato lo pubblica l'orchestratore. Codice di uscita 0 solo se tutto e' a posto.
   `verifica-elenco.ps1 -Prova` fa lo stesso su file finti sotto `build\`, senza rete; le prove sono in `verifica-elenco-prove.ps1`.
3. L'APK si scarica senza token: `curl -sIL <https://github.com/Casual76/MyCloud-Gallery/releases/download/<tag>/<asset>>` → 302 verso
   `release-assets.githubusercontent.com` (o `objects.githubusercontent.com`) e poi 200 con il peso giusto. Un host diverso va aggiunto a
   `UpdateSourceConfig.DOWNLOAD_HOSTS` (e alle righe C482 e 4-bis, e alla lista dello strumento) prima che i telefoni possano scaricare.

## Come si prova l'aggiornamento vero (sul telefono, non fatto qui)

Su un telefono che non sia quello di sviluppo, idealmente Android 11-13.

1. Installare a mano la beta 1 (`1.0.0`, l'APK firmato con `pampa.jks`), canale **Beta** in Impostazioni → Aggiornamenti.
2. Pubblicare la beta 2 (`1.1.0-beta.01`, versionCode 2), repository pubblico, `verifica-elenco.ps1` verde.
3. Riaprire l'app dopo 24 ore dall'ultimo controllo, oppure aprire la pagina Aggiornamenti: «Nuova versione», «1.0.0 → 1.1.0-beta.01 · … MB». Tornando
   alla Libreria: l'avviso sopra la barra delle schede.
4. «Dopo»: l'avviso sparisce, la voce resta in cima alle Impostazioni (anche chiudendo l'app dai recenti).
5. «Aggiorna» sul Wi-Fi → la prima volta «Serve un permesso» → «Consenti» → accendere l'interruttore → indietro: riparte da solo (su Android 11 Android
   puo' chiudere l'app: riaprendola, riparte). Spegnere il Wi-Fi mentre si e' nella pagina di Android: tornando chiede dei dati mobili.
6. Mentre scarica, uscire dall'app fino alla fine: rientrando, «Pronta da installare» con «Installa»; «Installa» → finestra di Android → «Annulla» →
   di nuovo «Pronta» con la riga di Play Protect (nessun guasto rosso), «Installa» → la finestra senza riscaricare → «Aggiorna»: **che cosa mostra
   l'app fra il «si'» e la chiusura, e per quanti secondi** («Sto installando»; se passano 15 s compare «Pronta»: annotarlo con il modello del
   telefono). Il nome del tasto nella finestra (Samsung, Android 14+). Premere Home subito dopo «Installa», mentre il file si ricontrolla: al ritorno
   «Pronta», nessuna finestra di Android comparsa da sola.
7. Riaperta: «Aggiornata · 1.1.0-beta.01», una volta.
8. Le prove del no: un elenco con lo sha256 sbagliato, un APK firmato con la chiave di debug, un versionCode uguale: nessuno arriva alla finestra di
   Android; il primo ha «Riprova», gli altri due «Avvisa chi ti ha dato l'app».
9. Sul canale Stabile la beta non compare; passando a Beta compare.

## Prove e mutazioni (sul computer)

| Prova | Che cosa |
|---|---|
| `UpdateRulesTest` | sha256 e peso obbligatori, forma della versione, note tagliate prima e pulite, nomi del publisher, indirizzo, checksum, questa app, versione annunciata, versionCode crescente, firma uguale, quali guasti si riprovano, 24 ore, «Dopo» per tre giorni e al terzo trenta, poi il conto riparte (mai per sempre), tentativo recente, numerazione |
| `GuardedUpdateHttpTest` | host ammessi a ogni salto (un rimando cattivo non si apre), rimandi a mano e niente cache anche sullo scaricamento, 404, tetti (l'elenco anche senza Content-Length), cookie, header, `.part` |
| `PublisherManifestTest` | l'elenco di `publisher.py` (con lo sha256 della correzione) letto da `EngineAppUpdater.check`; uno di prima, senza, non installa; la prossima beta `1.1.0-beta.01` |
| `GuardedUpdateInstallerTest` | i due tempi; ogni controllo ferma prima di `installFile` e cancella il file; il ricontrollo prima della sessione; **il cancello con un verificatore sospeso** (thread veri: davanti all'inizio, dietro alla fine → zero `installFile`; e il rovescio); «Sto installando» appena passato; permesso; ogni messaggio dell'engine tradotto; «annullata» torna a «Pronta» |
| `AppUpdatesTest` | 24 ore, canale, versione ricordata, «Dopo» con i trenta giorni, «Chiudi» su un guasto, nomi alternati, dati mobili per tentativo (Wi-Fi → mobile; consenso dato → permesso), permesso dopo un riavvio, il ciclo di vita che muove il cancello, l'installatore protetto vero con Home durante la verifica, «Sto installando» al ritorno, i 15 s solo con l'app davanti, l'esito che vince, «Riprova» senza riscaricare, «Aggiornata», cache all'avvio |
| `UpdateWiringTest` | nessun `EngineHttp` non avvolto: ne' costruito fuori dalla sottoclasse, ne' preso di serie da `AndroidAppUpdateInstaller`, ne' fuori dalla fabbrica; lo stesso cancello all'installatore e ad `AppUpdates`, mosso dal ciclo di vita, mai dall'importanza del processo |
| `UpdatesScreenTest`, `UpdatePhoneShots`/`UpdateLargeTextShots`/`UpdateTabletShots`/`UpdateTabletLandShots` | la pagina e l'avviso (Robolectric): parole, TalkBack (avanzamento, scelte spente), tasti da 48 dp, nessuna parola dell'engine; screenshot in `docs/screenshots/beta-grafica/U/` |
| `UAggiornamento*ScreenshotTest` (`:app`) | l'avviso sopra la Libreria vera (che sale sopra la scheda), «Aggiornata», la pagina «versione fermata» con e senza una versione nuova, l'avviso in sola lettura con «Aggiorna», la voce in cima alle Impostazioni, con l'icona vera |
| `ReleaseStoppedUpdateTest` (`:app`), `UpdateEntryTest` (feature-settings) | la versione fermata offre «Aggiorna» (o «Controlla adesso», con «arriva presto» e mai «App aggiornata»); in sola lettura «Aggiorna» chiude l'avviso in alto; la voce sale in cima con una versione nuova |
| `Phase6ArchitectureTest`, `Phase10ArchitectureTest`, `NoNetworkSourcesTest`/`NoWallClockSourcesTest` di feature-update | la terza eccezione di rete, l'host dell'elenco in un solo file |
| `tools/release/verifica-elenco-prove.ps1`, `versioni-prove.ps1` | lo strumento della verifica (ogni controllo con il suo caso; giro 2: `app.repository` assente, null, vuoto, una stringa; l'elenco gia' completo cambiato durante lo scaricamento), la regola dei nomi (giro 2: cifre non ASCII e a capo finale) |

**Mutazioni** (giro 2, tutte rieseguite sul codice nuovo): ogni riga di `docs/aggiornamenti-in-app-mutazioni.md` dice la modifica, la prova che cade e
il comando per rifarla, con l'esito vero di un'esecuzione e i totali contati. Due non cadono e la riga dice perche': M55 (l'esito ferma l'attesa in
due modi; tolti insieme cade, M72) e T11b (il secondo confronto dell'elenco copre una finestra che le prove non raggiungono).
