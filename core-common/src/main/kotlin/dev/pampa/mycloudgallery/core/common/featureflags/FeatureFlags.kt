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
 *   NAS (D1) e con [indexPublishTestSharesOnly] sulle share vere lavora in solo locale (D31). Serve alla tappa M1 e alla
 *   prova F2 (scansione in sola lettura dell'archivio vero con l'APK di release, risposta Q4).
 * @property indexPublishTestSharesOnly Fase 4, D31: l'indice condiviso e le prenotazioni si scrivono **solo** sulle
 *   share di prova ([TestShares]); sulle share vere la 4A lavora in solo locale. E' una **protezione**: vale `true`
 *   in release e la spegne solo l'utente a fine 4A (approvazione di D31), cambiando [RELEASE]. L'"emergenza" di
 *   Diagnostica ("Ferma le scritture sul NAS") e' un interruttore a parte, di :app, che puo' solo restringere.
 * @property backupTestSharesOnly Fase 5, D12 (`docs/fase5-progetto.md` §0.1): il backup scrive i media **solo** sulle share
 *   di prova ([TestShares]); sulle share vere lo stato dice "Backup in prova" e niente si scrive. Come F4-D31 vale in release,
 *   ma in piu' **non si spegne nemmeno in debug**: [forBuild] non ha un parametro per lui. Lo spegne solo l'utente, con la
 *   risposta a Q3 (primo backup vero a fine 5B), cambiando questo file. La decisione per share e' [backupMayWriteTo].
 * @property backupBlocksTestAccounts R2 della revisione di sicurezza della 5A (`docs/review/5a-fase5a.md`): **in release** un account di
 *   prova ([TestShares.isTestAccount]) non fa scrivere il backup in **nessuna** share (il cancello risponde `TEST_ONLY`). D13, la recinzione
 *   del telefono (`DCIM/mcg-prova/` in lettura, `mcg-campo/` in scrittura), esiste solo in debug: senza, la release con `test` o `test2`
 *   caricherebbe tutta la libreria vera del telefono nella share di prova. Vale `true` in [RELEASE] e `false` nelle build di debug, dove la
 *   recinzione c'e'. Come D12 e' una protezione: in release non si spegne, `forBuild` non ha un parametro per lui. La decisione e'
 *   [backupMayWriteAs].
 */
class FeatureFlags private constructor(
  val wol: Boolean,
  val nasLibrary: Boolean,
  val indexPublishTestSharesOnly: Boolean,
  val backupTestSharesOnly: Boolean,
  val backupBlocksTestAccounts: Boolean,
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

  override fun equals(other: Any?): Boolean =
    other is FeatureFlags && other.wol == wol && other.nasLibrary == nasLibrary &&
      other.indexPublishTestSharesOnly == indexPublishTestSharesOnly && other.backupTestSharesOnly == backupTestSharesOnly &&
      other.backupBlocksTestAccounts == backupBlocksTestAccounts

  override fun hashCode(): Int =
    (((wol.hashCode() * 31 + nasLibrary.hashCode()) * 31 + indexPublishTestSharesOnly.hashCode()) * 31 + backupTestSharesOnly.hashCode()) * 31 +
      backupBlocksTestAccounts.hashCode()

  override fun toString(): String =
    "FeatureFlags(wol=$wol, nasLibrary=$nasLibrary, indexPublishTestSharesOnly=$indexPublishTestSharesOnly, " +
      "backupTestSharesOnly=$backupTestSharesOnly, backupBlocksTestAccounts=$backupBlocksTestAccounts)"

  companion object {
    /**
     * D12: il backup solo sulle share di prova. Una costante, non un parametro: nessuna build (nemmeno debug) lo spegne da fuori.
     * Si cambia qui, a mano, solo con il consenso dell'utente (Q3).
     */
    private const val BACKUP_TEST_SHARES_ONLY: Boolean = true

    /** I valori di ogni build di release: funzioni sperimentali spente, protezioni accese. */
    val RELEASE: FeatureFlags = FeatureFlags(
      wol = false,
      nasLibrary = true,
      indexPublishTestSharesOnly = true,
      backupTestSharesOnly = BACKUP_TEST_SHARES_ONLY,
      backupBlocksTestAccounts = true,
    )

    /**
     * Le funzioni di questa build. Con [debugBuild] falso il risultato e' sempre [RELEASE], qualunque cosa si chieda
     * (anche `indexPublishTestSharesOnly = false`). [backupTestSharesOnly] (D12) non si chiede: e' sempre acceso.
     */
    fun forBuild(
      debugBuild: Boolean,
      wol: Boolean = false,
      nasLibrary: Boolean = false,
      indexPublishTestSharesOnly: Boolean = true,
    ): FeatureFlags =
      if (!debugBuild) {
        RELEASE
      } else {
        FeatureFlags(
          wol = wol,
          nasLibrary = nasLibrary,
          indexPublishTestSharesOnly = indexPublishTestSharesOnly,
          // D12: nessun parametro, di proposito. Anche la debug scrive solo nelle share di prova (D13 ci aggiunge la recinzione
          // delle sorgenti del telefono).
          backupTestSharesOnly = BACKUP_TEST_SHARES_ONLY,
          // R2: in debug la recinzione del telefono di D13 c'e' (DeveloperTools di debug): un account di prova puo' provare il backup.
          backupBlocksTestAccounts = false,
        )
      }
  }
}
