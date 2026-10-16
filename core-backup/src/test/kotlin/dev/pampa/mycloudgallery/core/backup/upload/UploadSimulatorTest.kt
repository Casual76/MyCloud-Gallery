package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimLatencies
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il motore vero sul **simulatore SMB** con le primitive e la sessione di produzione (riga P2 del §4.4): la ripresa con `reopenTemp`
 * (apertura senza creare e fine file sullo stesso handle), un guasto prima e dopo **ogni richiesta SMB** e la morte del processo a ogni
 * scrittura del diario (invarianti del §5.4 sui file del simulatore), un banco di giri casuali e il **costo per file** con le latenze
 * dell'inventario (`docs/inventario-nas.md`).
 */
class UploadSimulatorTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private fun TestScope.world(): SimWorld = SimWorld(backgroundScope)

  @Test
  fun laRipresaSulSimulatoreRiapreSenzaCreareEAccorcia() = runTest {
    val w = world()
    val h = w.engineKit(parallel = 1)
    val data = bytes(300_000, 1)
    w.kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val before = w.files()
    val seen = CopyOnWriteArrayList<SimOp>()
    w.backend.beforeOp = { op, _ -> seen += op }
    // La dodicesima scrittura da 16 KiB arriva al NAS, la risposta no: l'ultimo punto di ripresa e' a 160 KiB.
    w.backend.faults = FaultPlan().at(12, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    h.runAll()
    w.backend.faults = null
    val stopped = w.kit.store.all().single()
    assertThat(stopped.state).isEqualTo(UploadState.UPLOADING)
    assertThat(stopped.resumeAt).isEqualTo(10L * 16 * 1024)
    assertThat(w.checks(before, w.phoneShas(), final = false)).isEmpty()
    h.restart()
    h.resumeUntilQuiet()
    assertThat(w.backend.bytes(w.kit.share, "$dir/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
    assertThat(seen.count { it == SimOp.OPEN_WRITE }).isEqualTo(1)
    assertThat(seen.count { it == SimOp.SET_END_OF_FILE }).isEqualTo(1)
    // Riscritti al piu' i blocchi dopo l'ultimo punto (due da 16 KiB): mai il file da capo.
    val chunks = (data.size + 16 * 1024 - 1) / (16 * 1024)
    val rewritten = seen.count { it == SimOp.WRITE } - chunks
    assertThat(rewritten).isAtMost(2)
    assertThat(w.checks(before, w.phoneShas(), final = true)).isEmpty()
  }

  private class SimScenario(val name: String, val setup: (SimWorld) -> Unit, val check: (SimWorld, String) -> Unit)

  private val scenario = SimScenario(
    "foto, video e un nome di un file identico",
    setup = { w ->
      w.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(40_000, 2)))
      w.kit.addPhoneMedia("VID_2.mp4", NasContent.of(bytes(90_000, 3)), kind = MediaKind.VIDEO)
      w.kit.addPhoneMedia("IMG_3.jpg", NasContent.of(bytes(20_000, 4)))
      w.putFile("$dir/IMG_3.jpg", bytes(20_000, 4))
      w.putFile("$dir/VID_2.mp4", bytes(1_000, 5))
    },
    check = { w, label ->
      assertWithMessage(label).that(w.backend.bytes(w.kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(bytes(40_000, 2).toList())
      assertWithMessage(label).that(w.backend.bytes(w.kit.share, "$dir/VID_2 (2).mp4")?.toList()).isEqualTo(bytes(90_000, 3).toList())
      assertWithMessage(label).that(w.backend.bytes(w.kit.share, "$dir/VID_2.mp4")?.toList()).isEqualTo(bytes(1_000, 5).toList())
    },
  )

  /** Un guasto (connessione che cade) prima e dopo **ogni** richiesta SMB del giro, poi la ripresa dal diario. */
  @Test
  fun unGuastoAOgniRichiestaSmb() = runTest {
    val clean = world()
    scenario.setup(clean)
    val counter = FaultPlan()
    clean.backend.faults = counter
    clean.engineKit().runAll()
    clean.backend.faults = null
    val total = counter.operations
    assertThat(total).isAtLeast(30)
    var fired = 0
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val w = world()
        scenario.setup(w)
        val h = w.engineKit()
        val before = w.files()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        w.backend.faults = fault
        h.runAll()
        w.backend.faults = null
        val label = "simulatore, guasto alla richiesta $n/$total $phase"
        fired += fault.fired.size
        assertWithMessage("$label: a meta'").that(w.checks(before, w.phoneShas(), final = false)).isEmpty()
        h.restart()
        h.resumeUntilQuiet()
        assertWithMessage("$label: alla fine").that(w.checks(before, w.phoneShas(), final = true)).isEmpty()
        scenario.check(w, label)
      }
    }
    println("P2 simulatore: $total richieste SMB x 2 fasi, $fired guasti scattati")
    assertThat(fired).isAtLeast(total)
  }

  /** La morte del processo prima e dopo ogni scrittura del diario e del catalogo, sul simulatore. */
  @Test
  fun unaMorteAOgniScritturaDelDiario() = runTest {
    val clean = world()
    scenario.setup(clean)
    val counter = BackupDeath()
    val ck = clean.engineKit()
    ck.runAll(ck.dyingEngine(counter))
    val total = counter.steps
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val w = world()
        scenario.setup(w)
        val h = w.engineKit()
        val before = w.files()
        val death = BackupDeath(n, after)
        try {
          h.runAll(h.dyingEngine(death))
        } catch (_: ProcessDeath) {
        }
        val label = "simulatore, morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: morto").that(death.died).isTrue()
        assertWithMessage("$label: a meta'").that(w.checks(before, w.phoneShas(), final = false)).isEmpty()
        h.restart()
        h.resumeUntilQuiet()
        assertWithMessage("$label: alla fine").that(w.checks(before, w.phoneShas(), final = true)).isEmpty()
        scenario.check(w, label)
      }
    }
    println("P2 simulatore: $total scritture del diario x 2 (prima/dopo)")
  }

  /** Un banco di giri casuali sul simulatore: guasti SMB (caduta, sessione scaduta, disco pieno, blocco) e morti del processo. */
  @Test
  fun bancoDiGiriCasualiSulSimulatore() = runTest {
    var interruptions = 0
    for (round in 1..SIM_ROUNDS) {
      val random = Random(round * 104_729L)
      val w = world()
      val h = w.engineKit(parallel = 1 + random.nextInt(2))
      for (i in 1..1 + random.nextInt(4)) {
        val video = random.nextInt(4) == 0
        val data = bytes(if (video) 40_000 + random.nextInt(80_000) else 1_000 + random.nextInt(40_000), round * 10 + i)
        val name = if (video) "VID_$i.mp4" else "IMG_$i.jpg"
        w.kit.addPhoneMedia(name, NasContent.of(data), kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO)
        when (random.nextInt(6)) {
          0 -> w.putFile("$dir/$name", data)
          1 -> w.putFile("$dir/$name", bytes(data.size, -i))
          else -> Unit
        }
      }
      val before = w.files()
      repeat(1 + random.nextInt(3)) {
        if (random.nextBoolean()) {
          val death = BackupDeath(1 + random.nextInt(20), random.nextBoolean())
          try {
            h.runAll(h.dyingEngine(death))
          } catch (_: ProcessDeath) {
          }
          if (death.died) interruptions++
        } else {
          val fault = when (random.nextInt(5)) {
            0 -> SimFault.ExpireSession
            1 -> SimFault.Status(NtStatus.DISK_FULL)
            2 -> SimFault.Status(NtStatus.SHARING_VIOLATION)
            else -> SimFault.DropConnection
          }
          val plan = FaultPlan().at(1 + random.nextInt(50), if (random.nextBoolean()) FaultPhase.AFTER else FaultPhase.BEFORE, fault)
          w.backend.faults = plan
          h.runAll()
          w.backend.faults = null
          if (plan.fired.isNotEmpty()) interruptions++
        }
        assertWithMessage("simulatore, giro $round a meta'").that(w.checks(before, w.phoneShas(), final = false)).isEmpty()
        h.restart()
      }
      h.resumeUntilQuiet()
      assertWithMessage("simulatore, giro $round alla fine").that(w.checks(before, w.phoneShas(), final = true)).isEmpty()
    }
    println("P2 simulatore: $SIM_ROUNDS giri casuali, $interruptions interruzioni, 0 violazioni")
  }

  /**
   * Il costo per file (§2.9: "o = 0,12 s, circa 12 viaggi SMB a ~10 ms, ipotesi da misurare"): 40 foto da 1 MiB caricate, rilette e
   * pubblicate sul simulatore con le latenze dell'inventario. Si misurano le **richieste SMB per file** (indipendenti dalla rete) e il
   * tempo virtuale; il costo per file e' richieste × giro di rete.
   */
  @Test
  fun costoPerFileConLeLatenzeDellInventario() = runTest {
    val files = 40
    val size = 1 shl 20
    for ((label, latencies) in listOf("inventario (2 ms)" to SimLatencies.INVENTORY, "ipotesi del §2.9 (10 ms)" to SimLatencies.INVENTORY.copy(roundTripMs = 10))) {
      val w = world()
      val h = w.engineKit(parallel = 1, config = UploadConfig(parallel = 1))
      // La deduplica e' di P3 e con `qh1` i candidati sono rari: qui nessuno (il catalogo finto proporrebbe ogni copia della stessa
      // dimensione, e tutte le foto della misura ne hanno una sola).
      h.finder = { _ -> dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder { _, _, _ -> dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult.None } }
      h.restart()
      for (i in 1..files) w.kit.addPhoneMedia("IMG_$i.jpg", NasContent.of(bytes(size, 100 + i)))
      // La sessione si apre prima della misura (connessione e accesso non sono un costo per file).
      w.manager.withShare(w.kit.share, Purpose.BACKGROUND) { }
      w.backend.time = w.clock
      w.backend.latencies = latencies
      val requests0 = w.backend.shareOps.get()
      val time0 = w.clock.nowMillis()
      val kinds = CopyOnWriteArrayList<SimOp>()
      w.backend.beforeOp = { op, _ -> kinds += op }
      val result = h.runAll().first()
      assertThat(result.done).isEqualTo(files)
      val requests = (w.backend.shareOps.get() - requests0).toDouble() / files
      val elapsed = (w.clock.nowMillis() - time0).toDouble() / files
      // Un file da 1 MiB: una scrittura e una rilettura (le latenze di trasferimento del simulatore sono per MiB).
      val transfer = (latencies.writePerMiBMs + latencies.readPerMiBMs).toDouble()
      val overhead = requests * latencies.roundTripMs
      println(
        "P2 costo per file, $label: %.1f richieste SMB per file, %.0f ms virtuali per file (%.0f di trasferimento), costo per file %.0f ms; %s"
          .format(requests, elapsed, transfer, overhead, kinds.groupingBy { it }.eachCount()),
      )
      // Per file: CREATE, WRITE, FLUSH, LENGTH, STAT (fine), OPEN_READ, READ (verifica), OPEN_CHANGE, RENAME (pubblicazione), STAT
      // (timbro dopo la pubblicazione); in piu', una volta, le cartelle della destinazione.
      assertThat(requests).isAtMost(11.0)
    }
  }

  private companion object {
    const val SIM_ROUNDS = 100
  }
}
