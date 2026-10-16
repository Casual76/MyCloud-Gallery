package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il banco delle interruzioni del caricamento sul **motore vero** (riga P2 del §4.4: 200 giri; P6 lo porta a 2.000 e al simulatore): a
 * ogni giro un telefono e un NAS casuali (foto e video, copie identiche nei vecchi backup, nomi occupati da file diversi o identici) e da
 * una a quattro interruzioni casuali: la connessione che cade, la sessione che scade, il NAS che si blocca o si riempie prima o dopo che
 * il server abbia applicato una chiamata, la morte del processo prima o dopo una scrittura del diario, i colpi del telefono (file
 * riscritto, sparito, `_ID` rinumerati a meta' lettura), un file aperto dal PC. Dopo ogni interruzione gli invarianti del §5.4 "a meta'";
 * alla fine, tolti i guasti e ripreso dal diario, gli invarianti "a lavoro finito". Semi fissi: ogni giro si ripete uguale.
 */
class UploadBenchTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private class Stats {
    var rounds = 0
    var interruptions = 0
    var deaths = 0
    var faults = 0
    var phoneHits = 0
    var items = 0
    var done = 0
    var linked = 0
    var gone = 0
    var violations = 0
  }

  @Test
  fun duecentoGiriConInterruzioniCasuali() = runTest {
    val stats = Stats()
    for (round in 1..ROUNDS) runRound(round, stats)
    println(
      "P2 banco: ${stats.rounds} giri, ${stats.interruptions} interruzioni (${stats.faults} guasti del NAS, ${stats.deaths} morti del " +
        "processo, ${stats.phoneHits} colpi del telefono), ${stats.items} elementi (${stats.done} DONE, ${stats.linked} LINKED_EXISTING, " +
        "${stats.gone} GONE), ${stats.violations} violazioni",
    )
    assertThat(stats.violations).isEqualTo(0)
    assertThat(stats.interruptions).isAtLeast(ROUNDS)
  }

  private suspend fun runRound(round: Int, stats: Stats) {
    val random = Random(round * 7_919L)
    val h = RealEngineKit(parallel = 1 + random.nextInt(2))
    val kit = h.kit
    val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"
    // Il telefono finto tiene gli _ID attuali di ogni media (dopo una rinumerazione il catalogo li ritrova, come la lettura del telefono).
    val phoneIds = LinkedHashMap<MediaId, Long>()
    val photos = 1 + random.nextInt(5)
    val videos = random.nextInt(3)
    for (i in 1..photos + videos) {
      val video = i > photos
      val name = if (video) "VID_$i.mp4" else "IMG_$i.jpg"
      val data = bytes(if (video) 40_000 + random.nextInt(160_000) else 1_000 + random.nextInt(60_000), round * 100 + i)
      val id = kit.addPhoneMedia(name, NasContent.of(data), kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO)
      phoneIds[id] = kit.phoneIdOf(id)
      when (random.nextInt(8)) {
        0 -> kit.putNasFile(kit.share, "Vecchio backup/$name", NasContent.of(data))
        1 -> kit.putNasFile(kit.share, "$dir/$name", NasContent.of(data), knownToCatalog = false)
        2 -> kit.putNasFile(kit.share, "$dir/$name", NasContent.of(bytes(data.size, -i)), knownToCatalog = false)
        else -> Unit
      }
    }
    // I colpi del telefono, programmati prima del giro (scattano alla prima lettura che arriva al punto).
    for ((id, phoneId) in phoneIds) {
      if (random.nextInt(6) != 0) continue
      val size = kit.catalog.media(id)!!.size
      val at = 1 + random.nextLong(size.coerceAtLeast(2))
      when (random.nextInt(3)) {
        0 -> kit.phone.duringRead(phoneId, at, FakePhoneBytes.MidRead.Rewritten(NasContent.of(bytes(size.toInt(), -round)), 9_999))
        1 -> kit.phone.duringRead(phoneId, at, FakePhoneBytes.MidRead.Vanished)
        else -> {
          val fresh = 50_000L + round * 10 + phoneIds.size
          kit.phone.duringRead(phoneId, at, FakePhoneBytes.MidRead.Renumbered("v$round-$fresh", fresh))
          phoneIds[id] = fresh
        }
      }
      stats.phoneHits++
    }
    if (random.nextInt(10) == 0) {
      val victim = phoneIds.keys.random(random)
      kit.phone.delete(phoneIds.getValue(victim))
      stats.phoneHits++
    }
    val before = BackupInvariants.Snapshot(kit.snapshot().files, kit.phone.allSha256())
    val phoneShas = HashSet(before.phoneShas)

    val interruptions = 1 + random.nextInt(4)
    repeat(interruptions) { n ->
      val label = "giro $round, interruzione ${n + 1}"
      if (random.nextBoolean()) {
        val death = BackupDeath(dieAt = 1 + random.nextInt(25), afterEffect = random.nextBoolean())
        try {
          h.runAll(h.dyingEngine(death))
        } catch (_: ProcessDeath) {
        }
        if (death.died) {
          stats.deaths++
          stats.interruptions++
        }
      } else {
        val fault = when (random.nextInt(6)) {
          0 -> SimFault.ExpireSession
          1 -> SimFault.Stall
          2 -> SimFault.Status(NtStatus.DISK_FULL)
          else -> SimFault.DropConnection
        }
        val plan = FaultPlan().at(1 + random.nextInt(40), if (random.nextBoolean()) FaultPhase.AFTER else FaultPhase.BEFORE, fault)
        kit.nas.opsFaults = plan
        kit.nas.readFaults = plan
        if (random.nextInt(8) == 0) kit.nas.spaceOf = { NasSpaceInfo(1L shl 40, 30_000, 30_000) }
        h.runAll()
        kit.nas.opsFaults = null
        kit.nas.readFaults = null
        kit.nas.spaceOf = { NasSpaceInfo(1L shl 40, 1L shl 39, 1L shl 39) }
        if (plan.fired.isNotEmpty()) {
          stats.faults++
          stats.interruptions++
        }
      }
      phoneShas += kit.phone.allSha256()
      val mid = kit.violations(BackupInvariants.Snapshot(before.files, phoneShas), final = false)
      stats.violations += mid.size
      assertWithMessage("$label: invarianti a meta'").that(mid).isEmpty()
      // La lettura del telefono ritrova le righe rinumerate (E-P12).
      relink(kit, phoneIds)
      h.restart()
    }
    h.resumeUntilQuiet()
    relink(kit, phoneIds)
    h.resumeUntilQuiet()
    phoneShas += kit.phone.allSha256()
    val final = kit.violations(BackupInvariants.Snapshot(before.files, phoneShas), final = true)
    stats.violations += final.size
    assertWithMessage("giro $round: invarianti alla fine").that(final).isEmpty()
    stats.rounds++
    val items = kit.store.all()
    stats.items += items.size
    stats.done += items.count { it.state == UploadState.DONE }
    stats.linked += items.count { it.state == UploadState.LINKED_EXISTING }
    stats.gone += items.count { it.state == UploadState.GONE }
    // Ogni media che e' ancora sul telefono con i suoi byte e' al sicuro (nessun FAILED per un guasto passeggero, invariante 9).
    for ((id, phoneId) in phoneIds) {
      val m = kit.catalog.media(id)!!
      val stamp = kit.phone.stamp(phoneId)
      if (stamp != null && stamp == m.phoneStamp && kit.catalog.problemOf(id) == null) {
        assertWithMessage("giro $round: media rimasto sul telefono al sicuro").that(kit.catalog.isSafe(id)).isTrue()
      }
    }
  }

  private fun relink(kit: BackupTestKit, phoneIds: Map<MediaId, Long>) {
    for ((id, phoneId) in phoneIds) kit.catalog.relinkPhone(id, kit.phone.ref(phoneId))
  }

  private companion object {
    const val ROUNDS = 200
  }
}
