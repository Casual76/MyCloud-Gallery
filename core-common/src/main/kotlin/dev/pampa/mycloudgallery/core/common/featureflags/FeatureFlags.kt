package dev.pampa.mycloudgallery.core.common.featureflags

/**
 * Le funzioni sperimentali della galleria (decisione D5 di docs/fase2-progetto.md).
 *
 * Non sono flag remoti (quelli dell'engine, `EngineFlag`, cambiano comportamento da un manifest firmato):
 * sono interruttori del codice, decisi al momento della build. Il primo e per ora unico e' [wol].
 *
 * **In release sono tutti spenti, e non c'e' modo di accenderli.** Il costruttore e' privato e l'unico
 * ingresso e' [forBuild], che ignora le richieste quando la build non e' di debug: un errore in un modulo
 * Hilt o un valore letto da un file non possono accendere in produzione una funzione che non e' stata
 * verificata. `:app` passa `BuildConfig.DEBUG`.
 *
 * @property wol Wake-on-LAN (piano 2.6). Spento: con il NAS sempre acceso il WoL non e' verificabile.
 */
class FeatureFlags private constructor(val wol: Boolean) {

  override fun equals(other: Any?): Boolean = other is FeatureFlags && other.wol == wol

  override fun hashCode(): Int = wol.hashCode()

  override fun toString(): String = "FeatureFlags(wol=$wol)"

  companion object {
    /** Tutto spento: il valore di ogni build di release. */
    val RELEASE: FeatureFlags = FeatureFlags(wol = false)

    /**
     * Le funzioni di questa build. Con [debugBuild] falso il risultato e' sempre [RELEASE], qualunque cosa
     * si chieda.
     */
    fun forBuild(debugBuild: Boolean, wol: Boolean = false): FeatureFlags =
      if (!debugBuild) RELEASE else FeatureFlags(wol = wol)
  }
}
