package dev.pampa.mycloudgallery.debugtools

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * SOLO build debug: la **recinzione del telefono** delle prove sul campo (D13 della Fase 5, `docs/fase5-progetto.md` §0.1, §5.3; come
 * D33 della Fase 4 per le share). Con un account **di prova** (`test`, `test2`, elenco scritto nel codice di [TestShares]) il backup legge
 * **solo** le righe del telefono in `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (seminate con `adb` dallo script di P6) e scrive sotto
 * `mcg-campo/` delle share di prova (`mcg-campo/Backup/...`); nella 5B "Libera spazio", correzioni, "Ripristina originale" e "Modifica
 * con…" toccano solo quei media. Senza, una prova sull'S25 caricherebbe le foto vere della famiglia nella share di prova (password di
 * prova, L25). Gli altri account non sono recintati qui: per loro vale D12 (nessuna scrittura sulle share vere).
 *
 * Firma e punto di aggancio di P0 ([DeveloperTools.backupFence]); il comportamento completo (sorgenti del piano, porta del telefono,
 * destinazioni, azioni della 5B) lo lega P7. Solo in `app/src/debug`: la release non lo referenzia e non lo contiene (tools/check.ps1
 * cerca questo nome e le cartelle recintate nel dex dell'APK di release).
 */
internal object DebugBackupFence : BackupFence {

  /** Le cartelle del telefono che il backup puo' leggere con un account di prova (percorsi relativi del MediaStore). */
  val PHONE_FOLDERS: List<String> = listOf("DCIM/mcg-prova/", "Pictures/mcg-prova/")

  /** La cartella delle share di prova sotto cui scrive il backup con un account di prova. */
  const val DESTINATION_ROOT: String = "mcg-campo"

  override fun allowsPhoneFolder(account: String, relativePath: String): Boolean {
    if (!isTestAccount(account)) return true
    // Nel dubbio si recinta: niente "..", niente percorsi assoluti, solo dentro le due cartelle seminate.
    if (".." in relativePath || relativePath.startsWith("/")) return false
    val normalized = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
    return PHONE_FOLDERS.any { normalized.startsWith(it) }
  }

  override fun destinationRoot(account: String): SharePath? = if (isTestAccount(account)) SharePath.of(DESTINATION_ROOT) else null

  /** Un account di prova anche scritto in un'altra forma (`TEST`, ` test`): la recinzione vale (come `ShareFence`). */
  private fun isTestAccount(account: String): Boolean = TestShares.fold(account.trim()) in TestShares.ACCOUNTS
}
