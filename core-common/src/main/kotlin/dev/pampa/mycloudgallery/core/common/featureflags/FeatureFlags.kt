package dev.pampa.mycloudgallery.core.common.featureflags

/**
 * Le funzioni sperimentali della galleria (decisione D5 di docs/fase2-progetto.md).
 *
 * Non sono flag remoti (quelli dell'engine, `EngineFlag`, cambiano comportamento da un manifest firmato):
 * sono interruttori del codice, decisi al momento della build.
 *
 * **In release valgono i valori di [RELEASE], e non c'e' modo di cambiarli.** Il costruttore e' privato e l'unico
 * ingresso e' [forBuild], che ignora le richieste quando la build non e' di debug: un errore in un modulo Hilt o un
 * valore letto da un file non possono accendere in produzione una funzione che non e' stata verificata, ne' spegnere
 * una protezione. `:app` passa `BuildConfig.DEBUG`.
 *
 * @property wol Wake-on-LAN (piano 2.6). Spento: con il NAS sempre acceso il WoL non e' verificabile.
 * @property nasLibrary Fase 4 (`docs/fase4-progetto.md`): i media del NAS nella Libreria (scansione, indice, replica,
 *   preparazione, viewer). **Acceso dall'integrazione della 4A (P11)** anche in release: la 4A non tocca mai i media sul
 *   NAS (D1); con [indexPublishTestSharesOnly] accesa sulle share vere lavora in solo locale (D31). Serve alla tappa M1 e alla
 *   prova F2 (scansione in sola lettura dell'archivio vero con l'APK di release, risposta Q4).
 * @property indexPublishTestSharesOnly Fase 4, D31 (F4-D31): con la recinzione accesa l'indice condiviso e le prenotazioni si
 *   scrivono **solo** sulle share di prova ([TestShares]) e sulle share vere si lavora in solo locale. E' una **protezione**: in
 *   [RELEASE] e' **spenta dal passo unico del 12/11/2026** (D35, consenso in docs/decisioni.md); in debug resta un parametro di
 *   [forBuild], di serie acceso. L'"emergenza" di Diagnostica ("Ferma le scritture sul NAS") e' un interruttore a parte, di :app, che puo' solo restringere.
 * @property backupTestSharesOnly Fase 5, D12 (F5-D12, `docs/fase5-progetto.md` §0.1): con la recinzione accesa il backup scrive i
 *   media **solo** sulle share di prova ([TestShares]); sulle share vere lo stato dice "Backup in prova" e niente si scrive. Una
 *   costante che vale in release **e in debug** ([forBuild] non ha un parametro per lui): la cambia solo l'utente, in questo file.
 *   **Spenta dal passo unico del 12/11/2026** (D35). La decisione per share e' [backupMayWriteTo].
 * @property backupBlocksTestAccounts R2 della revisione di sicurezza della 5A (`docs/review/5a-fase5a.md`): **in release** un account di
 *   prova ([TestShares.isTestAccount]) non fa scrivere il backup in **nessuna** share (il cancello risponde `TEST_ONLY`). D13, la recinzione
 *   del telefono (`DCIM/mcg-prova/` in lettura, `mcg-campo/` in scrittura), esiste solo in debug: senza, la release con `test` o `test2`
 *   caricherebbe tutta la libreria vera del telefono nella share di prova. Vale `true` in [RELEASE] e `false` nelle build di debug, dove la
 *   recinzione c'e'. Come D12 e' una protezione: in release non si spegne, `forBuild` non ha un parametro per lui. La decisione e'
 *   [backupMayWriteAs].
 * @property sharedAlbumsTestSharesOnly Fase 7, D30 (F7-D30, `docs/fase7-progetto.md` §0.1): con la recinzione accesa gli album
 *   condivisi (definizioni firmate in `.mycloudgallery/albums/` e spostamenti verso l'area comune avviati da un album) **solo** fra le
 *   share di prova ([TestShares]: `test`/`test2` verso `test-comune`); sulle share vere il comando "Condividi" dice "Arriva dopo le prove".
 *   Come D12 una costante che vale in release **e in debug**; **spenta dal passo unico del 12/11/2026** (D35, consenso dell'utente).
 *   La decisione per share e' [sharedAlbumsMayUse]. Gli album personali, smart, i viaggi e l'Utility non hanno recinzione.
 * @property facesSyncTestSharesOnly Fase 8, D34 (F8-D34, `docs/fase8-progetto.md` §0.1, risposta Q5 (a) del 27/10): con la recinzione
 *   accesa i dati dei volti (file sotto `.mycloudgallery/faces/`, prenotazioni dei volti, cancellazioni sul NAS) **solo** sulle share di prova
 *   ([TestShares]); sulle share vere Persone lavora in solo locale (calcolo, raggruppamento, nomi e correzioni per dispositivo, "Disattiva e
 *   cancella" locale) e la pagina lo dice. Come D30 una costante che vale in release **e in debug**; **spenta dal passo unico del 12/11/2026**
 *   (D35), insieme alla pubblicazione dell'indice sulle cartelle vere. La decisione per share e'
 *   [facesSyncMayUse]; in debug con gli account di prova vale in piu' `ShareFence` (F4-D33).
 * @property mapOnline Fase 9, D24 (`docs/fase9-progetto.md` §0.1, risposta Q1 (c) del 27/10): la **mappa online** (le tessere di OpenFreeMap,
 *   l'unico host nuovo della fase) **esiste** in questa build. E' un interruttore di build, non la scelta dell'utente: acceso, la mappa online e'
 *   accesa di default su ogni dispositivo e ognuno la spegne per se' in Impostazioni -> Mappa (`MapSettings` di core-map, locale); spento, la
 *   mappa resta la carta di base dentro l'app con le regioni gia' scaricate, e il cancello di rete (`TilePolicy`) rifiuta ogni richiesta
 *   (`MapOnline.Unavailable`). Vale `true` in [RELEASE] (Q1 (c)); [forBuild] in debug lo puo' solo **spegnere** (una prova a mappa spenta), mai
 *   accendere se la costante [MAP_ONLINE] e' spenta. Il cancello di rete resta per intero in ogni caso (un solo host, nessun cookie, zone
 *   scaricate mai chieste online, mini-mappa delle info mai in rete, la "casa" mai mostrata).
 */
class FeatureFlags private constructor(
  val wol: Boolean,
  val nasLibrary: Boolean,
  val indexPublishTestSharesOnly: Boolean,
  val backupTestSharesOnly: Boolean,
  val backupBlocksTestAccounts: Boolean,
  val sharedAlbumsTestSharesOnly: Boolean,
  val facesSyncTestSharesOnly: Boolean,
  val mapOnline: Boolean,
) {

  /**
   * D12: il backup (caricamento, guardiano, deduplica che collega) puo' scrivere nella share [share]? Con [backupTestSharesOnly]
   * solo le share di prova scritte nel codice ([TestShares.isTestShare]: nome esatto, mai una share di famiglia). Lo legge il
   * cancello del backup (`BackupGate`, P7) e la release lo tiene anche prima che il backup sia legato (consumer-rules.pro di
   * questo modulo; `tools/check.ps1` lo cerca nel dex di release).
   */
  fun backupMayWriteTo(share: String): Boolean = !backupTestSharesOnly || TestShares.isTestShare(share)

  /**
   * R2: il backup puo' scrivere con l'account [account]? Con [backupBlocksTestAccounts] (la release) un account di prova no, in nessuna
   * share: la recinzione del telefono di D13 non c'e' e tutta la libreria vera finirebbe nella share di prova. Un account non noto (null)
   * non si puo' dire "non di prova": con la protezione accesa si fallisce chiusi. Spenta (debug, dove D13 recinta) vale sempre.
   */
  fun backupMayWriteAs(account: String?): Boolean = !backupBlocksTestAccounts || (account != null && !TestShares.isTestAccount(account))

  /**
   * D30: un album condiviso puo' usare la share [share] (l'area comune dove si pubblica la definizione e arrivano i file, e ogni area
   * privata da cui un media si sposta)? Con [sharedAlbumsTestSharesOnly] solo le share di prova scritte nel codice
   * ([TestShares.isTestShare]: nome esatto, mai una share di famiglia). Lo legge il cancello degli album (`AlbumWriteGate`, P13) e la
   * release lo tiene anche prima che il cancello sia legato (consumer-rules.pro; `tools/check.ps1` lo cerca nel dex di release).
   */
  fun sharedAlbumsMayUse(share: String): Boolean = !sharedAlbumsTestSharesOnly || TestShares.isTestShare(share)

  /**
   * D34: i dati dei volti possono stare sul NAS nell'area privata [share] (pubblicazione, prenotazioni, cancellazione sul NAS)? Con
   * [facesSyncTestSharesOnly] solo le share di prova scritte nel codice ([TestShares.isTestShare]: nome esatto, mai una share di famiglia).
   * Lo legge il cancello dei volti (`FacesWriteGate`, P13) e la release lo tiene anche prima che il cancello sia legato (consumer-rules.pro;
   * `tools/check.ps1` lo cerca nel dex di release).
   */
  fun facesSyncMayUse(share: String): Boolean = !facesSyncTestSharesOnly || TestShares.isTestShare(share)

  override fun equals(other: Any?): Boolean =
    other is FeatureFlags && other.wol == wol && other.nasLibrary == nasLibrary &&
      other.indexPublishTestSharesOnly == indexPublishTestSharesOnly && other.backupTestSharesOnly == backupTestSharesOnly &&
      other.backupBlocksTestAccounts == backupBlocksTestAccounts && other.sharedAlbumsTestSharesOnly == sharedAlbumsTestSharesOnly &&
      other.facesSyncTestSharesOnly == facesSyncTestSharesOnly && other.mapOnline == mapOnline

  override fun hashCode(): Int =
    ((((((wol.hashCode() * 31 + nasLibrary.hashCode()) * 31 + indexPublishTestSharesOnly.hashCode()) * 31 + backupTestSharesOnly.hashCode()) * 31 +
      backupBlocksTestAccounts.hashCode()) * 31 + sharedAlbumsTestSharesOnly.hashCode()) * 31 + facesSyncTestSharesOnly.hashCode()) * 31 +
      mapOnline.hashCode()

  override fun toString(): String =
    "FeatureFlags(wol=$wol, nasLibrary=$nasLibrary, indexPublishTestSharesOnly=$indexPublishTestSharesOnly, " +
      "backupTestSharesOnly=$backupTestSharesOnly, backupBlocksTestAccounts=$backupBlocksTestAccounts, " +
      "sharedAlbumsTestSharesOnly=$sharedAlbumsTestSharesOnly, facesSyncTestSharesOnly=$facesSyncTestSharesOnly, mapOnline=$mapOnline)"

  companion object {
    /**
     * D12: il backup solo sulle share di prova. Una costante, non un parametro: nessuna build (nemmeno debug) lo spegne da fuori.
     * Si cambia qui, a mano, solo con il consenso dell'utente (Q3).
     *
     * **Spenta il 12/11/2026 con il passo unico** (D35, par. 2.10 di docs/fase10-progetto.md; consenso `PASSO-UNICO-2026-11-12` in docs/decisioni.md):
     * il backup scrive anche nelle aree vere. Per riaccenderla: `true` qui e `tools/release/recinzioni.json` di nuovo "accesa".
     */
    private const val BACKUP_TEST_SHARES_ONLY: Boolean = false

    /**
     * D30: gli album condivisi solo fra le share di prova. Una costante, non un parametro: nessuna build (nemmeno debug) la spegne da
     * fuori. Si cambia qui, a mano, solo con il consenso dell'utente (Q7 (a): a fine Fase 7, con l'app definitiva).
     *
     * **Spenta il 12/11/2026 con il passo unico** (consenso `PASSO-UNICO-2026-11-12` in docs/decisioni.md).
     */
    private const val SHARED_ALBUMS_TEST_SHARES_ONLY: Boolean = false

    /**
     * D34: i dati dei volti solo sulle share di prova. Una costante, non un parametro: nessuna build (nemmeno debug) la spegne da fuori.
     * Si cambia qui, a mano, solo con il consenso dell'utente (Q5 (a): a fine Fase 8, dopo le prove, con la pubblicazione dell'indice).
     *
     * **Spenta il 12/11/2026 con il passo unico** (consenso `PASSO-UNICO-2026-11-12` in docs/decisioni.md).
     */
    private const val FACES_SYNC_TEST_SHARES_ONLY: Boolean = false

    /**
     * D24 e Q1 (c): la mappa online esiste in questa build (accesa di default su ogni dispositivo, spegnibile da ognuno per se'). Una costante:
     * una build senza mappa online la mette a `false` qui, e nessuna richiesta di [forBuild] la riaccende.
     */
    private const val MAP_ONLINE: Boolean = true

    /**
     * I valori di ogni build di release: funzioni sperimentali spente, protezioni accese. Dal 12/11/2026 (passo unico, consenso
     * `PASSO-UNICO-2026-11-12`) le quattro recinzioni "solo share di prova" (F4-D31, F5-D12, F7-D30, F8-D34) sono spente; [backupBlocksTestAccounts]
     * resta accesa.
     */
    val RELEASE: FeatureFlags = FeatureFlags(
      wol = false,
      nasLibrary = true,
      // F4-D31, spenta con il passo unico del 12/11/2026: l'indice e le prenotazioni si scrivono anche nelle aree vere.
      indexPublishTestSharesOnly = false,
      backupTestSharesOnly = BACKUP_TEST_SHARES_ONLY,
      backupBlocksTestAccounts = true,
      sharedAlbumsTestSharesOnly = SHARED_ALBUMS_TEST_SHARES_ONLY,
      facesSyncTestSharesOnly = FACES_SYNC_TEST_SHARES_ONLY,
      mapOnline = MAP_ONLINE,
    )

    /**
     * Le funzioni di questa build. Con [debugBuild] falso il risultato e' sempre [RELEASE], qualunque cosa si chieda
     * (anche `indexPublishTestSharesOnly = true`). [backupTestSharesOnly] (D12), D30 e D34 non si chiedono: valgono le costanti (spente dal passo
     * unico del 12/11/2026, anche in debug). [mapOnline] (D24) in debug si puo' solo spegnere: vale `mapOnline && MAP_ONLINE`.
     */
    fun forBuild(
      debugBuild: Boolean,
      wol: Boolean = false,
      nasLibrary: Boolean = false,
      indexPublishTestSharesOnly: Boolean = true,
      mapOnline: Boolean = true,
    ): FeatureFlags =
      if (!debugBuild) {
        RELEASE
      } else {
        FeatureFlags(
          wol = wol,
          nasLibrary = nasLibrary,
          indexPublishTestSharesOnly = indexPublishTestSharesOnly,
          // D12: nessun parametro, di proposito. La debug segue la costante come la release (spenta dal passo unico; con gli account di
          // prova D13 recinta le sorgenti del telefono e le destinazioni).
          backupTestSharesOnly = BACKUP_TEST_SHARES_ONLY,
          // R2: in debug la recinzione del telefono di D13 c'e' (DeveloperTools di debug): un account di prova puo' provare il backup.
          backupBlocksTestAccounts = false,
          // D30: nessun parametro, di proposito. La debug segue la costante come la release (con gli account di prova vale in piu'
          // ShareFence, F4-D33).
          sharedAlbumsTestSharesOnly = SHARED_ALBUMS_TEST_SHARES_ONLY,
          // D34: nessun parametro, di proposito. La debug segue la costante come la release (con gli account di prova vale in piu'
          // ShareFence, F4-D33).
          facesSyncTestSharesOnly = FACES_SYNC_TEST_SHARES_ONLY,
          // D24: la debug puo' provare la mappa senza la parte online, mai accenderla in una build che non la prevede.
          mapOnline = mapOnline && MAP_ONLINE,
        )
      }
  }
}
