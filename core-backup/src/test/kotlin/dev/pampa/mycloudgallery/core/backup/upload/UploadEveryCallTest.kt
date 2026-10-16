package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La riga P2 del §4.4 e il §4.3 sul **motore vero** ([SafeUploadEngine]), con lo stesso banco e gli stessi invarianti dell'`EveryCallTest`
 * del P0 (che li prova sull'esecutore di riferimento):
 * - un guasto (`FaultPlan`, connessione che cade) **prima e dopo** che il NAS abbia applicato **ogni** chiamata, scritture e letture;
 * - la **morte del processo** prima e dopo **ogni** scrittura del diario e del catalogo.
 * Dopo ogni interruzione (a meta') e dopo la ripresa dal diario (alla fine) gli invarianti del §5.4 sono veri. Ogni scenario gira con
 * **due file in volo** (D11) e con uno solo: le interruzioni cadono anche mentre un file si carica e l'altro si legge o si verifica.
 */
class UploadEveryCallTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private class Scenario(
    val name: String,
    val setup: suspend (BackupTestKit) -> Unit,
    val check: suspend (RealEngineKit, String) -> Unit,
  )

  private suspend fun finalChecks(h: RealEngineKit, s: Scenario, before: BackupInvariants.Snapshot, label: String) {
    assertWithMessage("$label: invarianti alla fine").that(h.kit.violations(before, final = true)).isEmpty()
    s.check(h, label)
  }

  // --- Guasto a ogni chiamata -------------------------------------------------------------------------------------------------

  private suspend fun faultsAtEveryCall(s: Scenario, parallel: Int): Int {
    val clean = RealEngineKit(parallel = parallel)
    s.setup(clean.kit)
    val cleanBefore = clean.kit.snapshot()
    val counter = FaultPlan()
    clean.kit.nas.opsFaults = counter
    clean.kit.nas.readFaults = counter
    clean.runAll()
    clean.kit.nas.opsFaults = null
    clean.kit.nas.readFaults = null
    clean.resumeUntilQuiet()
    finalChecks(clean, s, cleanBefore, "${s.name} ($parallel in volo), pulito")
    val total = counter.operations
    assertWithMessage("${s.name}: chiamate contate").that(total).isAtLeast(1)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val h = RealEngineKit(parallel = parallel)
        s.setup(h.kit)
        val before = h.kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        h.kit.nas.opsFaults = fault
        h.kit.nas.readFaults = fault
        h.runAll()
        h.kit.nas.opsFaults = null
        h.kit.nas.readFaults = null
        val label = "${s.name} ($parallel in volo), guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        assertWithMessage("$label: invarianti a meta'").that(h.kit.violations(before, final = false)).isEmpty()
        h.restart()
        h.resumeUntilQuiet()
        finalChecks(h, s, before, label)
      }
    }
    return total
  }

  // --- Morte del processo a ogni scrittura del diario (e del catalogo) ---------------------------------------------------------

  private suspend fun deathAtEveryWrite(s: Scenario, parallel: Int): Int {
    val clean = RealEngineKit(parallel = parallel)
    s.setup(clean.kit)
    val counter = BackupDeath()
    clean.runAll(clean.dyingEngine(counter))
    val total = counter.steps
    assertWithMessage("${s.name}: scritture contate").that(total).isAtLeast(2)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val h = RealEngineKit(parallel = parallel)
        s.setup(h.kit)
        val before = h.kit.snapshot()
        val death = BackupDeath(n, after)
        try {
          h.runAll(h.dyingEngine(death))
        } catch (_: ProcessDeath) {
          // il processo e' morto: si butta tutto cio' che sta in memoria e si riparte dal diario
        }
        val label = "${s.name} ($parallel in volo), morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        assertWithMessage("$label: invarianti a meta'").that(h.kit.violations(before, final = false)).isEmpty()
        h.restart()
        h.resumeUntilQuiet()
        finalChecks(h, s, before, label)
      }
    }
    return total
  }

  private suspend fun all(s: Scenario) {
    for (parallel in listOf(2, 1)) {
      val calls = faultsAtEveryCall(s, parallel)
      val writes = deathAtEveryWrite(s, parallel)
      println("P2 motore vero ${s.name}, $parallel in volo: $calls chiamate al NAS x 2 fasi, $writes scritture x 2 (prima/dopo)")
    }
  }

  // --- Gli scenari -------------------------------------------------------------------------------------------------------------

  @Test
  fun fotoNuova() = runTest {
    all(
      Scenario(
        "foto nuova",
        setup = { kit -> kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(40_000, 1))) },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(bytes(40_000, 1).toList())
          assertWithMessage(label).that(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
        },
      ),
    )
  }

  /** D5: un video con piu' punti di ripresa (scritture da 16 KiB, punto ogni 32 KiB) e, nello stesso giro, due foto (due in volo). */
  @Test
  fun videoConPuntiDiRipresaEDueFoto() = runTest {
    all(
      Scenario(
        "video con ripresa e due foto",
        setup = { kit ->
          kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(100_000, 2)), kind = MediaKind.VIDEO)
          kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(30_000, 3)))
          kit.addPhoneMedia("IMG_3.jpg", NasContent.of(bytes(20_000, 4)))
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/VID_1.mp4")?.toList()).isEqualTo(bytes(100_000, 2).toList())
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_2.jpg")?.toList()).isEqualTo(bytes(30_000, 3).toList())
          assertWithMessage(label).that(h.kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.DONE)
        },
      ),
    )
  }

  /** E-B3: il nome e' di un altro file. */
  @Test
  fun nomeOccupatoDaUnAltroFile() = runTest {
    all(
      Scenario(
        "nome occupato",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 3)))
          kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(21_000, 4)))
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_1 (2).jpg")?.toList()).isEqualTo(bytes(20_000, 3).toList())
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_1.jpg")?.toList()).isEqualTo(bytes(21_000, 4).toList())
        },
      ),
    )
  }

  /** E-B3, E-B16: il nome e' di un file identico che la scansione non ha ancora visto: si collega, nessun file nuovo. */
  @Test
  fun nomeOccupatoDaUnFileIdentico() = runTest {
    all(
      Scenario(
        "nome di un file identico",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 5)))
          kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(20_000, 5)), knownToCatalog = false)
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.nas.filePaths(h.kit.share)).containsExactly("$dir/IMG_1.jpg")
          assertWithMessage(label).that(h.kit.catalog.isSafe(h.kit.store.all().single().mediaId)).isTrue()
        },
      ),
    )
  }

  /** D8, E-B5 tramite la porta: una copia identica in un vecchio backup dell'area privata, collegata dopo la rilettura. */
  @Test
  fun copiaIdenticaInUnVecchioBackup() = runTest {
    all(
      Scenario(
        "copia identica altrove",
        setup = { kit ->
          kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 6)))
          kit.putNasFile(kit.share, "Backup telefono 2023/IMG_1.jpg", NasContent.of(bytes(20_000, 6)))
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.store.all().single().state).isEqualTo(UploadState.LINKED_EXISTING)
          assertWithMessage(label).that(h.kit.nas.filePaths(h.kit.share)).containsExactly("Backup telefono 2023/IMG_1.jpg")
        },
      ),
    )
  }

  /** E-B4 con un'altra foto nello stesso giro: una sparisce (`GONE`), l'altra arriva. */
  @Test
  fun unaFotoSparitaEUnaNuova() = runTest {
    all(
      Scenario(
        "una sparita e una nuova",
        setup = { kit ->
          val gone = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 7)))
          kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 8)))
          kit.phone.delete(kit.phoneIdOf(gone))
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.GONE, UploadState.DONE)
          assertWithMessage(label).that(h.kit.nas.filePaths(h.kit.share)).containsExactly("$dir/IMG_2.jpg")
        },
      ),
    )
  }

  /** E-B15: il file del telefono viene riscritto a meta' della lettura; niente di misto pubblicato, l'altra foto arriva. */
  @Test
  fun fileRiscrittoAMetaLettura() = runTest {
    all(
      Scenario(
        "riscritto a meta' lettura",
        setup = { kit ->
          val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(60_000, 9)))
          kit.phone.duringRead(kit.phoneIdOf(media), afterBytes = 20_000, event = FakePhoneBytes.MidRead.Rewritten(NasContent.of(bytes(60_000, 10)), 9_000))
          kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 11)))
        },
        check = { h, label ->
          assertWithMessage(label).that(h.kit.nas.bytes(h.kit.share, "$dir/IMG_2.jpg")?.toList()).isEqualTo(bytes(20_000, 11).toList())
          assertWithMessage(label).that(h.kit.nas.exists(h.kit.share, "$dir/IMG_1.jpg")).isFalse()
        },
      ),
    )
  }
}
