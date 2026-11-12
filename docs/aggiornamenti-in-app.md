# L'aggiornamento dentro l'app (pacchetto U, 12/11/2026)

Richiesta dell'utente del 12/11: i genitori non hanno il Pampa Store e devono poter aggiornare MyCloud Gallery, canale **Stabile** (di serie) o
**Beta** a scelta, «da internet, come le altre app». Supera D33 («nessun installatore», niente `REQUEST_INSTALL_PACKAGES`) e la regola «nessun host
nuovo» per questa sola funzione (`docs/decisioni.md`, «Pacchetto U»; `docs/sicurezza.md` C480-C486, L610-L614, righe in 4-bis). Il manifest firmato
dei blocchi (K1/K2, `core-release`, letto dal NAS) non cambia e non c'entra.

## Che cosa fa e dove sta

| Pezzo | Dove |
|---|---|
| Sorgente (repository, URL dell'elenco, modello dell'URL dell'APK, host ammessi, tetti, 24 ore): **l'unico posto da cambiare** se gli APK si spostano | `feature-update/.../source/UpdateSource.kt` (`UpdateSourceConfig`) |
| Regole pure (sha256 e peso obbligatori, nomi, indirizzo, questa app, versionCode crescente, firma uguale, 24 ore, avviso una volta per versione) | `feature-update/.../policy/UpdateRules.kt` |
| Client di rete: `EngineHttp` dell'engine avvolto (host ammessi a ogni salto, rimandi a mano, tetti, niente cookie) — terza eccezione di rete | `feature-update/.../net/` (classi `internal`) |
| Installazione: le due meta' pubbliche dell'engine (`download`, `installFile`) con i controlli dell'app in mezzo | `feature-update/.../install/GuardedUpdateInstaller.kt` |
| Stato, controlli, canale, avviso, dati mobili, ripresa dopo il permesso | `feature-update/.../AppUpdates.kt` (uno per processo, lo crea `:app`: `di/UpdateModule.kt`) |
| Pagina Impostazioni → Aggiornamenti, avviso all'apertura, pezzo per la pagina «versione fermata» | `feature-update/.../ui/` |
| Voce «Aggiornamenti» nelle Impostazioni (sezione Avanzate, con il segno quando c'e' una versione nuova) | `feature-settings/.../update/UpdateEntry.kt` (porta legata da `:app`) |
| Controllo silenzioso all'apertura (dopo il primo disegno, al piu' ogni 24 ore), pulizia della cache all'avvio | `app/.../MyCloudGalleryApp.kt` |
| Avviso alla radice (sotto il blocco dell'app) e «Aggiorna» nella pagina «versione fermata» | `app/.../AppRoot.kt`, `app/.../release/ReleaseRootUi.kt` |

Moduli dell'engine aggiunti con il suo script (`engine\tools\engine-install.ps1 -AppRoot . -Modules engine-foundation,engine-ui,engine-widget,engine-update`):
`engine-update` ed `engine-net`. Nessuna dipendenza esterna nuova (i due moduli dichiarano solo `project(...)`). `engine/` non e' modificato.

**Host**: l'elenco solo da `raw.githubusercontent.com`; l'APK solo da `github.com`, `release-assets.githubusercontent.com` (quello a cui GitHub rimanda
oggi: letto il 12/11 sulla risposta 302 di un asset pubblico) e `objects.githubusercontent.com` (quello di prima). Un rimando verso altro si rifiuta
senza aprirlo.

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
| `version` | si' | `versionName` dell'APK, **identico** (l'engine rifiuta un APK con un `versionName` diverso) |
| `releaseTag` | si' | il tag della GitHub Release (`publisher.py`: `<canale>-mycloud-gallery-v<versione>`) |
| `apkAsset` | si' | il nome del file nella release (`publisher.py`: `mycloud-gallery-<versione>.apk`) |
| `sizeBytes` | **si' (l'app)** | il peso esatto dell'APK in byte |
| `sha256` | **si' (l'app)** | lo SHA-256 esadecimale dell'APK |
| `changelog` | no | la nota (Markdown): l'app mostra il primo paragrafo senza simboli |

piu' `app.repository` con `repoOwner` e `repoName` (senza, l'engine non propone niente). Lettere, cifre, `.`, `-`, `_` nei nomi; niente barre.

Regole dell'engine: il canale **Stabile** legge `app.stable` e non propone mai una versione con il trattino; il canale **Beta** legge `app.beta` e, se
manca, `app.stable`. `publisher.py --channel stable` scrive la stessa versione in `stable` **e** in `beta`.

### Prima di pubblicare: lo sha256 (da decidere)

**`publisher.py` oggi non scrive lo sha256 nell'elenco**: lo calcola (`inspect_apk`) ma `build_version_info` non lo riceve. Tutte le altre chiavi
combaciano con cio' che legge `EngineAppUpdater.check` (lo prova `PublisherManifestTest`). Senza lo sha256 l'app trova la versione ma la segna «non
installabile» e non la annuncia (fallisce chiusa, L612). Due strade:

1. **(consigliata)** una correzione di tre righe nel publisher del Pampa Store (repository `Pampa-store-src`, non toccato da questo pacchetto):
   ```python
   # in publish(): passare lo sha256 gia' calcolato
   version_info = build_version_info(..., size_bytes=apk_stats.get("sizeBytes", 0), sha256=apk_stats.get("sha256", ""))
   # in build_version_info(): un parametro sha256: str = "" e la riga
   "sha256": sha256,
   ```
   Il Pampa Store e l'engine ignorano o usano il campo (l'engine lo legge dalla 2.10.0): nessun effetto sulle altre app.
2. aggiungere a mano `"sha256": "<impronta>"` in `app.beta` (e `app.stable`) del `manifest.json` su GitHub dopo ogni pubblicazione (l'impronta e' in
   `build\release\<versione>\MyCloudGallery-<versione>.apk.sha256`). Un passaggio a mano a ogni rilascio: si sbaglia.

## Le versioni

Oggi `release/versions.txt` ha `1.0.0`, versionCode 1, canale beta. Con `compareVersions` dell'engine:

- **versione corrente = `versionName` intero**: una `1.1.0-beta.2` riceve la stabile `1.1.0` (tagliando il suffisso non la vedrebbe mai);
- le pre-release si confrontano **come testo**: dopo `-beta.9` il canale Beta non propone `-beta.10` (il publisher invece la accetta come piu' nuova).
  **Regola**: al piu' nove beta per versione (poi si passa alla versione dopo, `1.1.1-beta.1`), oppure due cifre fin dall'inizio (`beta.01`);
- il canale Stabile ignora ogni versione con il trattino: una beta chiamata senza suffisso (come la prima, `1.0.0` sul canale beta) la vede solo chi e'
  sul canale Beta, e chi e' su Stabile (di serie: i genitori) aspetta la prima pubblicazione stabile;
- versionCode **sempre crescente** (gia' la regola di `versions.txt`): l'app rifiuta un APK con versionCode uguale o minore anche se il nome e' piu' nuovo;
  lo stesso APK promosso da beta a stabile ha lo stesso versionCode e chi l'ha gia' non se lo vede riproporre (stessa versione).

## Il comando per la beta

Dopo `tools\release\release.ps1 -Version <versione> -Channel beta` (che produce in `build\release\<versione>\` l'APK firmato, l'impronta e
`pampa-store.json` con `changelog-store.txt`), l'orchestratore lancia, **prima a secco**:

```powershell
python "C:\VibeCoded Projects\Pampa-store-src\plugins\pampa-store-publisher\mcp\publisher.py" publish `
  --repo-root "C:\VibeCoded Projects\MyCloud-Gallery-2" --channel beta --version <versione> `
  --app-id mycloud-gallery --app-name "MyCloud Gallery" --package-name dev.pampa.mycloudgallery `
  --apk-path "C:\VibeCoded Projects\MyCloud-Gallery-2\build\release\<versione>\MyCloudGallery-<versione>.apk" --skip-build `
  --changelog-file "C:\VibeCoded Projects\MyCloud-Gallery-2\build\release\<versione>\changelog-store.txt" --dry-run
```

poi lo stesso senza `--dry-run` (con il token in `PAMPA_GH_TOKEN`, che mette l'utente). **Niente `--repo-owner/--repo-name` ne' `--release-owner/
--release-repo`**: release ed elenco stanno nel repository dell'app (`origin`). `--app-id mycloud-gallery` tiene fissi tag e nomi degli asset.
Per la stabile: `--channel stable`.

## Che cosa controllare dopo la pubblicazione

1. L'elenco si legge **senza token**: `curl -s https://raw.githubusercontent.com/Casual76/MyCloud-Gallery/main/manifest.json` (con il repository ancora
   privato risponde 404: va reso pubblico).
2. Nel canale pubblicato ci sono `sha256` (64 cifre) e `sizeBytes`, e sono quelli dell'APK (`.apk.sha256` e il peso del file).
3. `version` e' esattamente il `versionName` dell'APK (`aapt2 dump badging`), `releaseTag` e `apkAsset` sono quelli della release.
4. L'APK si scarica senza token: `curl -sIL <https://github.com/Casual76/MyCloud-Gallery/releases/download/<tag>/<asset>>` → 302 verso
   `release-assets.githubusercontent.com` (o `objects.githubusercontent.com`) e poi 200 con il peso giusto. Un host diverso va aggiunto a
   `UpdateSourceConfig.DOWNLOAD_HOSTS` (e alle righe C482 e 4-bis) prima che i telefoni possano scaricare.

## Come si prova l'aggiornamento vero (sul telefono, non fatto qui)

1. Installare a mano la beta 1 (l'APK firmato con `pampa.jks`), canale **Beta** in Impostazioni → Aggiornamenti.
2. Pubblicare la beta 2 (versionCode piu' alto, sha256 nell'elenco), repository pubblico.
3. Riaprire l'app dopo 24 ore dall'ultimo controllo, oppure «Controlla adesso»: compare «Versione …» con il peso; all'apertura successiva (controllo
   silenzioso) l'avviso in basso, una volta.
4. «Aggiorna» → (sui dati mobili la domanda con il peso) → la prima volta la riga del permesso e «Consenti» → pagina di Android → indietro: riparte da solo
   → l'anello si riempie → «Controllo il file» → finestra di Android → installata (l'app si riavvia). Riaperta: «App aggiornata», nessun avviso.
5. Le prove del no: un elenco con lo sha256 sbagliato («non e' quello pubblicato»), con un APK firmato con la chiave di debug («non e' firmata come la
   tua app»), con un versionCode uguale («non e' piu' nuova»); nessuna delle tre arriva alla finestra di Android.
6. Sul canale Stabile la beta non compare; passando a Beta compare.

## Prove (sul computer)

| Prova | Che cosa |
|---|---|
| `UpdateRulesTest` | sha256 e peso obbligatori, nomi, indirizzo, checksum, questa app, versionCode crescente, firma uguale, 24 ore, avviso una volta, numerazione delle versioni |
| `GuardedUpdateHttpTest` | host ammessi a ogni salto (un rimando cattivo non si apre), 404, tetti, cookie, header, `.part` |
| `PublisherManifestTest` | l'elenco di `publisher.py` letto da `EngineAppUpdater.check`; lo sha256 che manca |
| `GuardedUpdateInstallerTest` | ogni controllo ferma l'installazione prima di `installFile` e cancella il file; permesso; messaggi dell'engine |
| `AppUpdatesTest` | 24 ore (anche dopo un errore), canale salvato, avviso una volta per versione, tocco, dati mobili, ripresa dopo il permesso |
| `UpdatesScreenTest`, `UpdatePhoneShots`/`UpdateLargeTextShots`/`UpdateTabletShots` | la pagina e l'avviso (Robolectric), screenshot in `docs/screenshots/beta-grafica/U/` |
| `ReleaseStoppedUpdateTest` (`:app`) | la versione fermata offre «Aggiorna» (o «Controlla adesso») |
| `Phase6ArchitectureTest`, `Phase10ArchitectureTest`, `NoNetworkSourcesTest`/`NoWallClockSourcesTest` di feature-update | la terza eccezione di rete, l'host dell'elenco in un solo file |

Le mutazioni (ogni controllo di sicurezza tolto fa cadere almeno una prova) sono nel resoconto del pacchetto in `docs/decisioni.md`.
