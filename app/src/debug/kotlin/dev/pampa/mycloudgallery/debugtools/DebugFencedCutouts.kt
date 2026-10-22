package dev.pampa.mycloudgallery.debugtools

import dev.pampa.mycloudgallery.backup.FencedCutouts
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.mediastore.write.PhoneMediaFolders

/**
 * SOLO build debug (D13, Fase 6 P13): con un account di prova i ritagli del soggetto stanno in `Pictures/mcg-prova/Ritagli/`, dentro la recinzione del
 * telefono ([DebugBackupFence]), e la loro sorgente del backup e' quella cartella. Come `DebugBackupFence`: la release non la referenzia e
 * `tools/check.ps1` cerca `mcg-prova` nel suo dex.
 */
internal object DebugFencedCutouts : FencedCutouts {
  override val folder: String = PhoneMediaFolders.FENCED_CUTOUTS

  override fun source(): BackupSource.Folder = CutoutBackup.source(fenced = true)
}
