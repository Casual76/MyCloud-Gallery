package dev.pampa.mycloudgallery.core.backup.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceCodec
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (P13, D47, risposta Q12, riga P13 del §4.4): la sorgente "Ritagli" del backup. E' la cartella `Pictures/MyCloud Gallery/Ritagli/`
 * (una `BackupSource.Folder` del volume principale), accesa di default come gli screenshot; sul NAS `Backup/<dispositivo>/Ritagli/AAAA/MM/`;
 * in debug con un account di prova `Pictures/mcg-prova/Ritagli/`, scritta sotto `mcg-campo`.
 */
class CutoutBackupTest {

  private val folder = DeviceFolder("S25 di prova")

  private fun idsOf(list: List<dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate>) = list.map { it.mediaId }

  @Test
  fun laSorgenteEAccesaDiDefaultComeGliScreenshot() {
    assertThat(BackupSources.DEFAULT.sources).contains(CutoutBackup.source())
    assertThat(BackupSources.DEFAULT.sources).containsAtLeast(BackupSource.Camera, BackupSource.Screenshots)
    assertThat(CutoutBackup.source().volume).isEqualTo("external_primary")
    assertThat(CutoutBackup.source().relativePath).isEqualTo("Pictures/MyCloud Gallery/Ritagli/")
    assertThat(CutoutBackup.source(fenced = true).relativePath).isEqualTo("Pictures/mcg-prova/Ritagli/")
    assertThat(CutoutBackup.isCutouts(CutoutBackup.source())).isTrue()
    assertThat(CutoutBackup.isCutouts(CutoutBackup.source(fenced = true))).isTrue()
    assertThat(CutoutBackup.isCutouts(BackupSource.Camera)).isFalse()
    // Una cartella omonima di un'altra scheda o un percorso piu' profondo non sono i ritagli.
    assertThat(CutoutBackup.isCutouts(BackupSource.Folder("1A2B-3C4D", "Pictures/MyCloud Gallery/Ritagli/"))).isFalse()
    assertThat(CutoutBackup.isCutouts(BackupSource.Folder("external_primary", "Pictures/MyCloud Gallery/"))).isFalse()
  }

  @Test
  fun chiAveva_sceltoPrimaDeiRitagliLiAccendeConWithCutouts() {
    val before = BackupSources(setOf(BackupSource.Camera))
    val after = before.withCutouts()
    assertThat(after.sources).containsExactly(BackupSource.Camera, CutoutBackup.source())
    assertThat(after.withCutouts()).isEqualTo(after)
    assertThat(before.withCutouts(fenced = true).sources).contains(CutoutBackup.source(fenced = true))
  }

  @Test
  fun laSorgenteSiSalvaESiRilegge() {
    val saved = BackupSourceCodec.encode(BackupSources.DEFAULT)
    assertThat(saved).contains("folder:external_primary:Pictures/MyCloud Gallery/Ritagli/")
    assertThat(BackupSourceCodec.decode(saved)).isEqualTo(BackupSources.DEFAULT)
    assertThat(BackupSourceCodec.fromOnboarding(camera = true, screenshots = true)).isEqualTo(BackupSources.DEFAULT)
  }

  @Test
  fun sulNasIRitagliVannoInBackupDispositivoRitagliAnnoMese() {
    val path = BackupPaths.destination(folder, CutoutBackup.source(), localDay = 20261022, latestDay = 20261023, fileName = "IMG_0042-ritaglio.png")
    assertThat(path.value).isEqualTo("Backup/S25 di prova/Ritagli/2026/10/IMG_0042-ritaglio.png")
    assertThat(BackupPaths.phoneFolderOf(CutoutBackup.source())).isEqualTo("Pictures/MyCloud Gallery/Ritagli/")
    assertThat(BackupPaths.sourceFolders(CutoutBackup.source())).containsExactly("Ritagli")
  }

  @Test
  fun unRitaglioSenzaDataVaInSenzaData() {
    val path = BackupPaths.destination(folder, CutoutBackup.source(), localDay = null, latestDay = null, fileName = "Ritaglio.png")
    assertThat(path.value).isEqualTo("Backup/S25 di prova/Ritagli/Senza data/Ritaglio.png")
  }

  @Test
  fun ilPianoCaricaIRitagliSeLaSorgenteEAccesa_ENoSeESpenta() = runTest {
    val world = PlanWorld()
    world.add(
      phoneMedia(1, source = BackupSource.Camera),
      phoneMedia(2, source = CutoutBackup.source(), name = "IMG_1-ritaglio.png"),
    )
    val planner = world.planner
    // Di serie: fotocamera, screenshot e ritagli.
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(2), mid(1))
    assertThat(planner.destination(planner.next(BackupKind.PHOTOS, 10).first(), folder).value).isEqualTo("Backup/S25 di prova/Ritagli/2026/10/IMG_1-ritaglio.png")

    // Spenta (impostazioni del backup): i ritagli nuovi non si caricano piu'.
    world.settings.sources.value = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots))
    assertThat(idsOf(planner.next(BackupKind.PHOTOS, 10))).containsExactly(mid(1))
  }

  @Test
  fun conLaRecinzioneSiCaricanoSoloIRitagliDiMcgProvaESiScriveInMcgCampo() = runTest {
    val fencedCutouts = CutoutBackup.source(fenced = true)
    val world = PlanWorld(
      sources = BackupSources(setOf(BackupSource.Camera, CutoutBackup.source(), fencedCutouts)),
      fence = ProvaFence(),
      account = "test",
    )
    world.add(
      phoneMedia(1, source = BackupSource.Camera), // le foto vere: mai
      phoneMedia(2, source = CutoutBackup.source(), name = "vero-ritaglio.png"), // un ritaglio fuori dalla recinzione: mai
      phoneMedia(3, source = fencedCutouts, name = "prova-ritaglio.png"),
    )
    val planner = world.planner
    val next = planner.next(BackupKind.PHOTOS, 10)
    assertThat(idsOf(next)).containsExactly(mid(3))
    assertThat(planner.destination(next.single(), folder).value).isEqualTo("mcg-campo/Backup/S25 di prova/Ritagli/2026/10/prova-ritaglio.png")
  }
}
