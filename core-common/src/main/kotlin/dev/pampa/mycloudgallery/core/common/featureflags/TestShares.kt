package dev.pampa.mycloudgallery.core.common.featureflags

import java.text.Normalizer
import java.util.Locale

/**
 * Le share e gli account **di prova** del NAS (`docs/decisioni.md`, "Ambiente di prova sul NAS (14/10)";
 * `docs/fase4-progetto.md` D30, D31, D33). L'elenco e' **scritto nel codice**, di proposito: per allargarlo serve una
 * modifica che si vede in revisione, mai un valore letto da un file o dal NAS.
 *
 * Chi lo usa:
 * - D31 (`FeatureFlags.indexPublishTestSharesOnly`): con la recinzione accesa l'indice e le prenotazioni si pubblicano solo qui
 *   (`core-index`, P3); in release spenta dal passo unico del 12/11/2026, come D12, D30 e D34 (che usano lo stesso elenco);
 * - D33 (`ShareFence` di `core-nas`, P1, legata solo nella build debug da :app): con un account di prova ogni share
 *   fuori da questo elenco e' rifiutata prima di qualunque richiesta SMB;
 * - D30 (`TestAreaGuard`, nei test d'integrazione di `core-nas`): l'intersezione con le share del file delle
 *   credenziali.
 *
 * `NAS` e `Public` sono share **pubbliche** (gli account di prova le vedono e ci scrivono): non sono e non saranno mai
 * di prova, e il confronto non le confonde con un nome simile (forma NFKC, senza maiuscole).
 */
object TestShares {

  /** Le share di prova: `test` (di `test`, con le foto della Fase 0), `test2` (di `test2`), `test-comune` (entrambi). */
  val SHARES: Set<String> = setOf("test", "test2", "test-comune")

  /** Gli account di prova. Mai `admin`, `alessio`, `pampa`. */
  val ACCOUNTS: Set<String> = setOf("test", "test2")

  /** Le share di famiglia che nessuna prova deve toccare, qualunque cosa dica un file di configurazione. */
  val FAMILY_SHARES: Set<String> = setOf("alessio", "pampa", "nas", "public")

  /**
   * Vero solo per un nome **esattamente** dell'elenco, a meno delle maiuscole (Samba non le distingue). Niente
   * normalizzazioni che allargano: `ｔｅｓｔ` (lettere "a larghezza piena") o `test ` (spazio finale) sono altre share,
   * quindi **non** di prova. In piu', per sicurezza, mai una share di famiglia nemmeno nella forma NFKC.
   */
  fun isTestShare(share: String): Boolean = share.lowercase(Locale.ROOT) in SHARES && !isFamilyShare(share)

  /** Come [isTestShare], per gli account. */
  fun isTestAccount(account: String): Boolean = account.lowercase(Locale.ROOT) in ACCOUNTS

  /**
   * Vero per una share di famiglia anche scritta in un'altra forma (`ＮＡＳ`, `PUBLIC`): qui la normalizzazione
   * **restringe** (rifiuta di piu'), quindi si usa la forma NFKC.
   */
  fun isFamilyShare(share: String): Boolean = fold(share) in FAMILY_SHARES

  /** La forma di confronto per i rifiuti: NFKC e minuscolo. */
  fun fold(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
