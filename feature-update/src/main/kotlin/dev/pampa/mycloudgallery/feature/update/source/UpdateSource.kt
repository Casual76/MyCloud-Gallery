package dev.pampa.mycloudgallery.feature.update.source

import dev.antigravity.fluidengine.update.UpdateSource

// DA DOVE ARRIVANO GLI AGGIORNAMENTI (pacchetto U, 12/11/2026). UN SOLO POSTO: chi sposta gli APK cambia queste costanti e nient'altro.
//
// Decisione dell'utente del 12/11/2026: il repository dell'app, `Casual76/MyCloud-Gallery`, diventa PUBBLICO con la release beta (niente repository
// separato). L'elenco e' il `manifest.json` che `publisher.py` del Pampa Store scrive alla radice del repository dell'app, sul ramo predefinito
// (`main`); gli APK sono gli asset delle GitHub Releases dello stesso repository. Finche' il repository e' privato l'elenco risponde 404: l'app lo
// tratta come «nessun aggiornamento» (in silenzio all'avvio; a mano «Non riesco a controllare adesso»).
//
// L'elenco NON e' firmato con K1 (il manifest firmato del blocco a distanza e' un'altra cosa, sta sul NAS e non c'entra): la garanzia e' la firma
// dell'APK, che Android controlla e che l'app confronta prima con quella installata, piu' lo sha256 dell'elenco letto su HTTPS (docs/sicurezza.md
// C480-C486, L610-L614). Questo file e' l'UNICO sorgente di produzione in cui puo' comparire l'host dell'elenco (Phase10ArchitectureTest, regola 3).

/** Il repository delle release e i suoi indirizzi. */
object UpdateSourceConfig {

  /** Il proprietario del repository dell'app su GitHub. */
  const val OWNER: String = "Casual76"

  /** Il repository dell'app: e' anche quello delle release (asset degli APK) e dell'elenco. */
  const val REPO: String = "MyCloud-Gallery"

  /** Il ramo predefinito, quello in cui `publisher.py` scrive l'elenco (`get_repository_default_branch`). */
  const val BRANCH: String = "main"

  /** Il percorso dell'elenco nel repository (`DEFAULT_MANIFEST_PATH` di `publisher.py`). */
  const val MANIFEST_PATH: String = "manifest.json"

  /** L'elenco delle versioni: una GET, senza parametri ne' identificativi. */
  const val MANIFEST_URL: String = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$MANIFEST_PATH"

  /**
   * Il modello dell'indirizzo di un APK: le GitHub Releases DI QUESTO repository. Proprietario e nome sono scritti qui e non presi dall'elenco
   * (`{owner}`/`{repo}` del modello dell'engine): chi scrive l'elenco puo' scegliere tag e asset, non un altro repository. Tag e asset li controlla
   * `UpdateRules.offerProblem` (caratteri ammessi, `.apk`).
   */
  const val DOWNLOAD_URL_TEMPLATE: String = "https://github.com/$OWNER/$REPO/releases/download/{tag}/{asset}"

  /** L'unico host dell'elenco. */
  val MANIFEST_HOSTS: Set<String> = setOf("raw.githubusercontent.com")

  /**
   * Gli host dello scaricamento, a ogni salto: `github.com` (l'indirizzo dell'asset) e i due host a cui GitHub rimanda i file delle release:
   * `release-assets.githubusercontent.com` (quello di oggi, letto il 12/11/2026 sulla risposta 302 di un asset pubblico) e
   * `objects.githubusercontent.com` (quello di prima, che GitHub usa ancora per alcuni asset). Un rimando verso altro si rifiuta.
   */
  val DOWNLOAD_HOSTS: Set<String> = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

  /** Il tetto dell'elenco: un elenco vero sta in pochi KB; oltre si smette di leggere e il controllo non riesce. */
  const val MAX_MANIFEST_BYTES: Int = 256 * 1024

  /** Il tetto di un APK: oggi l'APK pesa circa 110 MB; un `sizeBytes` oltre questo tetto non si scarica nemmeno. */
  const val MAX_APK_BYTES: Long = 400L * 1024 * 1024

  /** I salti di rimando ammessi (GitHub ne usa uno: asset -> host dei file). */
  const val MAX_REDIRECTS: Int = 3

  /** Ogni quanto si controlla da soli, all'apertura dell'app: al piu' una volta ogni 24 ore. */
  const val CHECK_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

  /** La sorgente per `EngineAppUpdater`. */
  fun engineSource(applicationId: String): UpdateSource =
    UpdateSource(manifestUrl = MANIFEST_URL, applicationId = applicationId, downloadUrlTemplate = DOWNLOAD_URL_TEMPLATE)

  /** L'indirizzo che l'engine costruisce per [tag] e [asset]: quello che `UpdateRules` pretende di ritrovare nell'aggiornamento offerto. */
  fun downloadUrl(tag: String, asset: String): String = DOWNLOAD_URL_TEMPLATE.replace("{tag}", tag).replace("{asset}", asset)

  /** Lo User-Agent: il nome dell'app e la versione, niente altro (nessun identificativo del dispositivo). */
  fun userAgent(versionName: String): String = "MyCloudGallery/$versionName"
}
