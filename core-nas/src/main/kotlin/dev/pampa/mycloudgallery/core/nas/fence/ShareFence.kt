package dev.pampa.mycloudgallery.core.nas.fence

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard

/**
 * La recinzione delle prove sul campo (D33 della Fase 4, `docs/fase4-progetto.md` §0.1): con un account **di prova**
 * (`test`, `test2`) ogni share fuori da `test`, `test2`, `test-comune` e' rifiutata prima di qualunque richiesta.
 * `NAS` e `Public` sono pubbliche e gli account di prova ci scrivono: e' l'unica cosa che le protegge dal telefono.
 *
 * - L'elenco e' quello **scritto nel codice** di `TestShares` (core-common): nessun valore letto dal NAS o da un file.
 * - Per decidere se l'account e' di prova si **allarga** il confronto (NFKC, maiuscole, spazi ai bordi): nel dubbio si
 *   recinta. Per decidere se la share e' di prova si **stringe** (`TestShares.isTestShare`: nome esatto, mai una share
 *   di famiglia in nessuna forma).
 * - Gli altri account (`alessio`, `pampa`, ...) non sono toccati.
 *
 * Solo debug: la lega :app da `DeveloperTools` di debug (P11); in release non e' referenziata e R8 la toglie.
 */
object ShareFence : ShareGuard {

  override fun allows(user: String, share: String): Boolean = !isTestAccount(user) || TestShares.isTestShare(share)

  /** Un account di prova anche scritto in un'altra forma (`TEST`, `ｔｅｓｔ２`, ` test`): la recinzione vale. */
  fun isTestAccount(user: String): Boolean = TestShares.fold(user.trim()) in TestShares.ACCOUNTS
}
