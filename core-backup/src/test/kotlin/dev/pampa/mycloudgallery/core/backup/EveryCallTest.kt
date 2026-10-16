package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La riga P0 del §4.4 e il §4.3 ("ogni passo in piu' tappe ha il test 'guasto a ogni chiamata' piu' la morte del processo a ogni scrittura
 * del diario"), sull'**esecutore di riferimento** del backup, sul NAS finto e sul telefono finto:
 * - un guasto (`FaultPlan`, connessione che cade) **prima e dopo** che il NAS abbia applicato **ogni** chiamata, scritture e letture;
 * - la **morte del processo** prima e dopo **ogni** scrittura del diario e del catalogo.
 * Dopo ogni interruzione (a meta') e dopo la ripresa dal diario (alla fine) gli invarianti del §5.4 sono veri: nessun file visibile non
 * verificato, nessun doppione, nessuna sostituzione, nessuna perdita, temporanei solo `<io>-bk-` citati dal diario (a lavoro finito
 * nessuno), catalogo e diario coerenti, ogni elemento arriva in fondo. Il numero di punti si conta con un giro pulito; ogni guasto e
 * ogni morte deve scattare davvero. P6 lo estende a migliaia di giri casuali (e al motore vero quando P2 e P3 consegnano).
 */
class EveryCallTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private class Scenario(
    val name: String,
    val setup: suspend (BackupTestKit) -> Unit,
    val check: suspend (BackupTestKit, String) -> Unit,
  )

  private suspend fun BackupTestKit.firstRun(engine: dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupEngine = this.engine) {
    engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)
  }

  private suspend fun finalChecks(kit: BackupTestKit, s: Scenario, before: dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants.Snapshot, label: String) {
    assertWithMessage("$label: invarianti alla fine").that(kit.violations(before, final = true)).isEmpty()
    s.check(kit, label)
  }

  // --- Guasto a ogni chiamata -------------------------------------------------------------------------------------------------

  private suspend fun faultsAtEveryCall(s: Scenario): Int {
    val clean = BackupTestKit()
    s.setup(clean)
    val cleanBefore = clean.snapshot()
    val counter = FaultPlan()
    clean.nas.opsFaults = counter
    clean.nas.readFaults = counter
    clean.firstRun()
    clean.nas.opsFaults = null
    clean.nas.readFaults = null
    clean.resumeUntilQuiet()
    finalChecks(clean, s, cleanBefore, "${s.name}, pulito")
    val total = counter.operations
    assertWithMessage("${s.name}: chiamate contate").that(total).isAtLeast(1)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val kit = BackupTestKit()
        s.setup(kit)
        val before = kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        kit.nas.opsFaults = fault
        kit.nas.readFaults = fault
        kit.firstRun()
        kit.nas.opsFaults = null
        kit.nas.readFaults = null
        val label = "${s.name}, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        assertWithMessage("$label: invarianti a meta'").that(kit.violations(before, final = false)).isEmpty()
        kit.restart()
        kit.resumeUntilQuiet()
        finalChecks(kit, s, before, label)
      }
    }
    return total
  }

  // --- Morte del processo a ogni scrittura del diario (e del catalogo) -------------------------------------------------------

  private fun BackupTestKit.dyingEngine(death: BackupDeath) = newEngine(DyingBackupStore(store, death), DyingBackupCatalog(catalog, death))

  private suspend fun deathAtEveryWrite(s: Scenario): Int {
    val clean = BackupTestKit()
    s.setup(clean)
    val counter = BackupDeath()
    clean.firstRun(clean.dyingEngine(counter))
    val total = counter.steps
    assertWithMessage("${s.name}: scritture contate").that(total).isAtLeast(2)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val kit = BackupTestKit()
        s.setup(kit)
        val before = kit.snapshot()
        val death = BackupDeath(n, after)
        try {
          kit.firstRun(kit.dyingEngine(death))
        } catch (_: ProcessDeath) {
          // il processo e' morto: si butta tutto cio' che sta in memoria e si riparte dal diario
        }
        val label = "${s.name}, morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        assertWithMessage("$label: invarianti a meta'").that(kit.violations(before, final = false)).isEmpty()
        kit.restart()
        kit.resumeUntilQuiet()
        finalChecks(kit, s, before, label)
      }
    }
    return total
  }

  private suspend fun both(s: Scenario) {
    val calls = faultsAtEveryCall(s)
    val writes = deathAtEveryWrite(s)
    println("P0 backup ${s.name}: $calls chiamate al NAS x 2 fasi, $writes scritture x 2 (prima/dopo)")
  }

  private fun BackupTestKit.only(): dev.pampa.mycloudgallery.core.backup.contract.BackupItem = store.all().single()

  // --- Gli scenari ---------------------------------------------------------------------------------------------------------

  @Test
  fun fotoNuova() = runTest {
    both(
      Scenario(
        "foto nuova",
        setup = { kit -> kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(40_000, 1))) },
        check = { kit, label ->
          assertWithMessage(label).that(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(bytes(40_000, 1).toList())
          assertWithMessage(label).that(kit.only().state).isEqualTo(UploadState.DONE)
        },
      ),
    )
  }

  /** D5: un video con piu' punti di ripresa (scritture da 16 KiB, punto ogni 32 KiB). */
  @Test
  fun videoConPuntiDiRipresa() = runTest {
    both(
      Scenario(
        "video con ripresa",
        setup = { kit -> kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(100_000, 2)), kind = MediaKind.VIDEO) },
        check = { kit, label ->
          assertWithMessage(label).that(kit.nas.bytes(kit.share, "$dir/VID_1.mp4")?.toList()).isEqualTo(bytes(100_000, 2).toList())
          assertWithMessage(label).that(kit.only().state).isEqualTo(UploadState.DONE)
        },
      ),
    )
  }

  /** E-B3: il nome e' di un altro file. */
  @Test
  fun nomeOccupatoDaUnAltroFile() = runTest {
    both(
      Scenario(
        "nome occupato",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 3)))
          kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(21_000, 4)))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.nas.bytes(kit.share, "$dir/IMG_1 (2).jpg")?.toList()).isEqualTo(bytes(20_000, 3).toList())
          assertWithMessage(label).that(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(bytes(21_000, 4).toList())
        },
      ),
    )
  }

  /** E-B3, E-B16: il nome e' di un file identico che la scansione non ha ancora visto: si collega, nessun file nuovo. */
  @Test
  fun nomeOccupatoDaUnFileIdentico() = runTest {
    both(
      Scenario(
        "nome di un file identico",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 5)))
          kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(20_000, 5)), knownToCatalog = false)
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.nas.filePaths(kit.share)).containsExactly("$dir/IMG_1.jpg")
          assertWithMessage(label).that(kit.catalog.isSafe(kit.only().mediaId)).isTrue()
        },
      ),
    )
  }

  /** D8, §2.11: una copia identica in un vecchio backup dell'area privata: collegata dopo la rilettura, mai caricata. */
  @Test
  fun copiaIdenticaInUnVecchioBackup() = runTest {
    both(
      Scenario(
        "copia identica altrove",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 6)))
          kit.putNasFile(kit.share, "Backup telefono 2023/IMG_1.jpg", NasContent.of(bytes(20_000, 6)))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.only().state).isEqualTo(UploadState.LINKED_EXISTING)
          assertWithMessage(label).that(kit.nas.filePaths(kit.share)).containsExactly("Backup telefono 2023/IMG_1.jpg")
        },
      ),
    )
  }

  /** E-B4 con un'altra foto nello stesso giro: una sparisce (`GONE`), l'altra arriva. */
  @Test
  fun unaFotoSparitaEUnaNuova() = runTest {
    var lost: MediaId? = null
    both(
      Scenario(
        "una sparita e una nuova",
        setup = { kit ->
          val gone = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 7)))
          kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 8)))
          kit.phone.delete(kit.phoneIdOf(gone))
          lost = gone
        },
        check = { kit, label ->
          val states = kit.store.all().map { it.state }.toSet()
          assertWithMessage(label).that(states).containsExactly(UploadState.GONE, UploadState.DONE)
          assertWithMessage(label).that(kit.nas.filePaths(kit.share)).containsExactly("$dir/IMG_2.jpg")
          assertThat(lost).isNotNull()
        },
      ),
    )
  }
}
