package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.mediastore.write.PhoneMediaFolders

/**
 * Le cartelle **recintate** dei ritagli del soggetto (D13, Fase 5A): con un account di prova i ritagli stanno in `Pictures/mcg-prova/Ritagli/` e la loro
 * sorgente del backup e' quella. Esiste **solo in debug** (`DeveloperTools.fencedCutouts()`, `app/src/debug`): in release e' null e la stringa
 * `mcg-prova` non e' nel dex (tools/check.ps1 la cerca), perche' nessun codice di release la nomina.
 */
interface FencedCutouts {
  /** `RELATIVE_PATH` del MediaStore dove "Salva come nuova foto" scrive con la recinzione. */
  val folder: String

  /** La sorgente del backup per quella cartella. */
  fun source(): BackupSource.Folder
}

/**
 * D13 per i ritagli: dove stanno e quale sorgente del backup li copre. Con la recinzione del telefono ([fence], debug con un account di prova) e
 * le cartelle recintate ([fenced]) valgono quelle; altrimenti `Pictures/MyCloud Gallery/Ritagli/` e `CutoutBackup.source()`. **Senza account** (con una
 * recinzione) si fallisce chiusi, come le sorgenti del backup (`DefaultBackupSettings.fenced`). In release la recinzione non c'e' e vale sempre il di serie.
 */
class CutoutFence(
  private val fence: BackupFence?,
  private val fenced: FencedCutouts?,
  private val account: () -> String?,
) {

  /** La recinzione vale adesso, con l'account di adesso. */
  fun fenced(): Boolean = fencedFor(account())

  /** Come [fenced] per un account gia' letto (all'avvio l'account arriva dopo l'archivio). */
  fun fencedFor(account: String?): Boolean {
    val fence = fence ?: return false
    account ?: return true
    return fence.destinationRoot(account) != null
  }

  /** La cartella del telefono dei ritagli salvati con l'account di adesso. */
  fun folder(): String = (if (fenced()) fenced?.folder else null) ?: PhoneMediaFolders.CUTOUTS

  /** La sorgente del backup dei ritagli per [account]. */
  fun sourceFor(account: String?): BackupSource.Folder = (if (fencedFor(account)) fenced?.source() else null) ?: CutoutBackup.source()

  /**
   * [source] e' la cartella dei ritagli: quella di serie o, in debug, quella recintata. Non usa `CutoutBackup.isCutouts`, che nomina la cartella recintata:
   * R8 ne inlinerebbe il letterale nel dex di release.
   */
  fun isCutouts(source: BackupSource): Boolean {
    if (source !is BackupSource.Folder || source.volume != CutoutBackup.VOLUME) return false
    return source.relativePath == CutoutBackup.RELATIVE_PATH || source.relativePath == fenced?.source()?.relativePath
  }

  /** Le sorgenti [sources] (con la sorgente "Ritagli" di serie) con quella recintata al suo posto, se la recinzione vale per [account]. */
  fun adapt(sources: BackupSources, account: String?): BackupSources {
    val inFence = (if (fencedFor(account)) fenced?.source() else null) ?: return sources
    return BackupSources(sources.sources.mapTo(LinkedHashSet()) { if (isCutouts(it)) inFence else it })
  }
}
