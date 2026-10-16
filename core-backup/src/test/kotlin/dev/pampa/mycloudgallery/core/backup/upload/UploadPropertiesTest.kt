package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le proprieta' della riga P2 del §4.4, con dati e guasti casuali (semi fissi, ripetibili):
 * - **nessun file fuori dalle radici ammesse**: con destinazioni casuali dentro e fuori da `Backup/` (un piano sbagliato, un elemento
 *   del diario scritto da altri), si scrive solo in `Backup/` e nei propri temporanei `<io>-bk-`;
 * - **nessuna sostituzione con 1.000 collisioni casuali**: nomi occupati da file diversi, catene di "nome (n)", file identici, con la
 *   connessione che cade a caso: ogni file che c'era ha gli stessi byte, nome e `fileId`, e ogni media arriva al sicuro.
 */
class UploadPropertiesTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  /** Un piano che sbaglia: destinazioni a caso dentro e fuori dalle radici ammesse. */
  private class RandomDestinations(private val inner: BackupPlanner, private val random: Random) : BackupPlanner by inner {
    var outside = 0

    override fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath {
      val pick = random.nextInt(6)
      if (pick < 4) outside++
      return when (pick) {
        0 -> SharePath.of("Foto", candidate.fileName)
        1 -> SharePath.of(candidate.fileName)
        2 -> SharePath.of("Altro", "Backup", candidate.fileName)
        3 -> SharePath.of("Backup")
        else -> inner.destination(candidate, folder)
      }
    }
  }

  @Test
  fun nessunFileFuoriDalleRadiciAmmesse() = runTest {
    var outside = 0
    var injected = 0
    for (seed in 1..60) {
      val random = Random(seed)
      val h = RealEngineKit(parallel = 1 + seed % 2)
      val kit = h.kit
      val planner = RandomDestinations(kit.planner, random)
      h.planner = planner
      h.restart()
      (1..6).map { i -> kit.addPhoneMedia("IMG_$i.jpg", NasContent.of(bytes(5_000 + random.nextInt(40_000), seed * 10 + i))) }
      // Un elemento del diario scritto "da altri" con una destinazione fuori dalle radici: il motore non lo pubblica mai.
      // (Di una cartella fuori dalle sorgenti: il piano non lo propone, l'unico elemento e' quello iniettato.)
      val extra = kit.addPhoneMedia(
        "EXTRA.jpg", NasContent.of(bytes(12_000, seed)),
        source = dev.pampa.mycloudgallery.core.backup.contract.BackupSource.Folder("external_primary", "Pictures/Altro/"),
      )
      val m = kit.catalog.media(extra)!!
      kit.store.create(
        listOf(
          NewBackupItem(
            kind = UploadKind.NEW_COPY, mediaId = m.mediaId, versionId = m.versionId, size = m.size, phone = m.phone, expected = m.phoneStamp,
            share = kit.share, planned = SharePath.of("Foto", "EXTRA.jpg"),
          ),
        ),
      )
      injected++
      val before = kit.snapshot()
      kit.nas.opsFaults = FaultPlan().random(seed.toLong(), 0.05, if (seed % 2 == 0) FaultPhase.AFTER else FaultPhase.BEFORE, SimFault.DropConnection)
      repeat(4) { h.runAll() }
      kit.nas.opsFaults = null
      h.resumeUntilQuiet()
      val created = kit.snapshot().files.filterKeys { it !in before.files }.values
      for (f in created) {
        val inRoot = f.path.startsWith("Backup/")
        val ownTemp = NasBackupPaths.isBackupTempOf(SharePath.parse(f.path), kit.deviceId)
        assertWithMessage("giro $seed: file fuori dalle radici").that(inRoot || ownTemp).isTrue()
        assertWithMessage("giro $seed: temporaneo rimasto a lavoro finito").that(ownTemp).isFalse()
      }
      // Gli invarianti del §5.4 (a parte i FAILED voluti dell'elemento iniettato).
      assertWithMessage("giro $seed").that(kit.violations(before, final = false)).isEmpty()
      val injectedItem = kit.store.all().first { it.mediaId == extra && it.planned.value == "Foto/EXTRA.jpg" }
      assertThat(injectedItem.state).isEqualTo(UploadState.FAILED)
      assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith("Backup/") }).isEmpty()
      outside += planner.outside
    }
    println("P2 radici: 60 giri, $outside destinazioni fuori scartate al piano, $injected elementi iniettati rifiutati")
    assertThat(outside).isGreaterThan(0)
  }

  @Test
  fun nessunaSostituzioneConMilleCollisioniCasuali() = runTest {
    var collisions = 0
    var identical = 0
    var rounds = 0
    val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"
    while (collisions < 1_000) {
      rounds++
      val random = Random(1_000 + rounds)
      val h = RealEngineKit(parallel = 1 + rounds % 2)
      val kit = h.kit
      val ids = ArrayList<dev.pampa.mycloudgallery.core.model.MediaId>()
      for (i in 1..10) {
        val name = "IMG_${rounds}_$i.jpg"
        val data = bytes(2_000 + random.nextInt(20_000), rounds * 100 + i)
        ids += kit.addPhoneMedia(name, NasContent.of(data))
        val base = name.removeSuffix(".jpg")
        when (random.nextInt(5)) {
          0 -> Unit
          // Un file diverso al nome previsto.
          1 -> {
            kit.putNasFile(kit.share, "$dir/$name", NasContent.of(bytes(data.size + 1, -i)), knownToCatalog = false)
            collisions += 1
          }
          // Lo stesso file al nome previsto (copia dal PC, reinstallazione): si collega.
          2 -> {
            kit.putNasFile(kit.share, "$dir/$name", NasContent.of(data), knownToCatalog = false)
            collisions += 1
            identical++
          }
          // Una catena di nomi occupati da file diversi: "nome", "nome (2)", ... "nome (k)".
          3 -> {
            val k = 2 + random.nextInt(4)
            for (n in 1..k) {
              val path = if (n == 1) "$dir/$name" else "$dir/$base ($n).jpg"
              kit.putNasFile(kit.share, path, NasContent.of(bytes(data.size, -100 * i - n)), knownToCatalog = false)
            }
            collisions += k
          }
          // Un file diverso al nome previsto e lo stesso file a "nome (2)".
          else -> {
            kit.putNasFile(kit.share, "$dir/$name", NasContent.of(bytes(data.size, -7 * i)), knownToCatalog = false)
            kit.putNasFile(kit.share, "$dir/$base (2).jpg", NasContent.of(data), knownToCatalog = false)
            collisions += 2
            identical++
          }
        }
      }
      val before = kit.snapshot()
      kit.nas.opsFaults = FaultPlan().random(rounds.toLong(), 0.03, if (rounds % 2 == 0) FaultPhase.AFTER else FaultPhase.BEFORE, SimFault.DropConnection)
      kit.nas.readFaults = kit.nas.opsFaults
      repeat(3) { h.runAll() }
      kit.nas.opsFaults = null
      kit.nas.readFaults = null
      h.restart()
      h.resumeUntilQuiet()
      assertWithMessage("giro $rounds").that(kit.violations(before, final = true)).isEmpty()
      for (id in ids) assertWithMessage("giro $rounds: al sicuro").that(kit.catalog.isSafe(id)).isTrue()
    }
    println("P2 collisioni: $rounds giri, $collisions collisioni casuali ($identical con un file identico), nessuna sostituzione")
    assertThat(collisions).isAtLeast(1_000)
  }
}
