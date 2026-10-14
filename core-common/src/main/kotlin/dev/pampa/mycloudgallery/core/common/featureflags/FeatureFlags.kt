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
 *   preparazione, viewer). Spento in release finche' l'integrazione (P11, o la tappa M1 dell'orchestratore) non lo
 *   accende cambiando [RELEASE] con una modifica del codice che si vede in revisione.
 * @property indexPublishTestSharesOnly Fase 4, D31: l'indice condiviso e le prenotazioni si scrivono **solo** sulle
 *   share di prova ([TestShares]); sulle share vere la 4A lavora in solo locale. E' una **protezione**: vale `true`
 *   in release e la spegne solo l'utente a fine 4A (approvazione di D31), cambiando [RELEASE]. L'"emergenza" di
 *   Diagnostica ("Ferma le scritture sul NAS") e' un interruttore a parte, di :app, che puo' solo restringere.
 */
class FeatureFlags private constructor(
  val wol: Boolean,
  val nasLibrary: Boolean,
  val indexPublishTestSharesOnly: Boolean,
) {

  override fun equals(other: Any?): Boolean =
    other is FeatureFlags && other.wol == wol && other.nasLibrary == nasLibrary &&
      other.indexPublishTestSharesOnly == indexPublishTestSharesOnly

  override fun hashCode(): Int = (wol.hashCode() * 31 + nasLibrary.hashCode()) * 31 + indexPublishTestSharesOnly.hashCode()

  override fun toString(): String =
    "FeatureFlags(wol=$wol, nasLibrary=$nasLibrary, indexPublishTestSharesOnly=$indexPublishTestSharesOnly)"

  companion object {
    /** I valori di ogni build di release: funzioni sperimentali spente, protezioni accese. */
    val RELEASE: FeatureFlags = FeatureFlags(wol = false, nasLibrary = false, indexPublishTestSharesOnly = true)

    /**
     * Le funzioni di questa build. Con [debugBuild] falso il risultato e' sempre [RELEASE], qualunque cosa si chieda
     * (anche `indexPublishTestSharesOnly = false`).
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
        FeatureFlags(wol = wol, nasLibrary = nasLibrary, indexPublishTestSharesOnly = indexPublishTestSharesOnly)
      }
  }
}
