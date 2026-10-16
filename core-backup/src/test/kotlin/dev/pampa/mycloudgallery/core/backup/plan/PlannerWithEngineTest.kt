package dev.pampa.mycloudgallery.core.backup.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupSettings
import dev.pampa.mycloudgallery.core.backup.testing.FakeExistingCopies
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupEngine
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il piano vero dentro il motore di riferimento (P0): quello che il piano decide e' quello che il motore fa. Le destinazioni arrivano sul
 * NAS finto, i nomi che `SharePath` rifiuta si salvano comunque (ripuliti), il nome occupato da un file diverso diventa "nome (2)"
 * (E-B3) e i media gia' nel diario non si pianificano due volte.
 */
class PlannerWithEngineTest {

  private val kit = BackupTestKit()
  private val folder = DeviceFolder("S25 di prova")
  private val settings = FakeBackupSettings(BackupSources.DEFAULT, folder)
  private val planner =
    DefaultBackupPlanner(
      catalog = kit.catalog,
      settings = settings,
      existing = FakeExistingCopies(),
      rules = { PresetCatalog.ruleSet(Preset.NIGHT) },
      privateShare = { kit.share },
      clock = kit.clock,
      alreadyPlanned = { kit.store.openFor(it) != null },
    )
  private val engine: InMemoryBackupEngine =
    InMemoryBackupEngine(
      deviceId = kit.deviceId,
      share = kit.share,
      ops = kit.ops,
      reader = kit.nas.reader,
      store = kit.store,
      catalog = kit.catalog,
      phone = kit.phone.port,
      gate = kit.gate,
      clock = kit.clock,
      planner = planner,
      folder = { settings.deviceFolder.value },
      chunkBytes = kit.chunkBytes,
      checkpointBytes = kit.checkpointBytes,
    )

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private suspend fun runAll() {
    engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)
  }

  private fun fileAt(path: String): ByteArray? = kit.nas.bytes(kit.share, path)

  @Test
  fun leDestinazioniDelPianoSonoQuelleDelNas() = runTest {
    val photo = bytes(30_000, 1)
    val shot = bytes(20_000, 2)
    val undated = bytes(10_000, 3)
    val telegram = bytes(15_000, 4)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(photo))
    kit.addPhoneMedia("Screenshot_1.png", NasContent.of(shot), source = BackupSource.Screenshots, localDay = 20250305)
    kit.addPhoneMedia("IMG_NODATE.jpg", NasContent.of(undated), localDay = null)
    val tg = BackupSource.Folder("external_primary", "Pictures/Telegram/")
    kit.addPhoneMedia("tg.jpg", NasContent.of(telegram), source = tg)
    settings.sources.value = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, tg))
    val before = kit.snapshot()

    runAll()

    assertThat(fileAt("Backup/S25 di prova/2026/10/IMG_1.jpg")!!.toList()).isEqualTo(photo.toList())
    assertThat(fileAt("Backup/S25 di prova/Screenshot/2025/03/Screenshot_1.png")!!.toList()).isEqualTo(shot.toList())
    assertThat(fileAt("Backup/S25 di prova/Senza data/IMG_NODATE.jpg")!!.toList()).isEqualTo(undated.toList())
    assertThat(fileAt("Backup/S25 di prova/Telegram/2026/10/tg.jpg")!!.toList()).isEqualTo(telegram.toList())
    assertThat(kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.DONE)
    assertThat(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unNomeOstile_siSalvaComunque_ripulito() = runTest {
    val data = bytes(12_000, 5)
    kit.addPhoneMedia("CON.jpg", NasContent.of(data))
    kit.addPhoneMedia("a:b?.jpg", NasContent.of(bytes(11_000, 6)))
    val before = kit.snapshot()

    runAll()

    assertThat(fileAt("Backup/S25 di prova/2026/10/_CON.jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(fileAt("Backup/S25 di prova/2026/10/a_b_.jpg")).isNotNull()
    assertThat(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unNomeOccupatoDaUnFileDiverso_diventaNomeDue_E_B3() = runTest {
    val old = bytes(9_000, 7)
    val mine = bytes(14_000, 8)
    kit.putNasFile(kit.share, "Backup/S25 di prova/2026/10/IMG_1.jpg", NasContent.of(old), knownToCatalog = false)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(mine))
    val before = kit.snapshot()

    runAll()

    assertThat(fileAt("Backup/S25 di prova/2026/10/IMG_1.jpg")!!.toList()).isEqualTo(old.toList()) // mai sostituito
    assertThat(fileAt("Backup/S25 di prova/2026/10/IMG_1 (2).jpg")!!.toList()).isEqualTo(mine.toList())
    assertThat(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun iMediaGiaNelDiarioNonSiPianificanoDueVolte_eUnGiroFinitoNonLasciaNulla() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(8_000, 9)))
    kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(8_000, 10)))

    val first = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    val second = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)

    assertThat(first.done).isEqualTo(2)
    assertThat(second.done).isEqualTo(0)
    assertThat(second.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(kit.store.all()).hasSize(2)
    assertThat(planner.nextOverall(10)).isEmpty() // al sicuro: niente da caricare
  }

  @Test
  fun unVideoVieneDopoLeFoto_nelMotore() = runTest {
    val order = ArrayList<String>()
    kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(40_000, 11)), kind = MediaKind.VIDEO)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(10_000, 12)))

    // Il piano su tutto il telefono: la foto, poi il video, anche se il video e' il piu' recente nel catalogo.
    planner.nextOverall(10).mapTo(order) { it.fileName }

    assertThat(order).containsExactly("IMG_1.jpg", "VID_1.mp4").inOrder()
  }
}
