package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La riga P3 del §4.4 e il §4.3 ("ogni passo in piu' tappe ha il test 'guasto a ogni chiamata' piu' la morte del processo a ogni scrittura
 * del diario"), per guardiano, verifiche e deduplica sul NAS finto e sul telefono finto:
 * - un guasto (`FaultPlan`, connessione che cade) **prima e dopo** che il NAS abbia applicato **ogni** chiamata, letture e scritture;
 * - la **morte del processo** prima e dopo **ogni** scrittura del diario e del catalogo.
 * Dopo ogni interruzione (a meta') e dopo la ripresa (alla fine) gli invarianti del §5.4 sono veri; alla fine lo stato e' quello del giro
 * pulito. Il numero di punti si conta con un giro pulito; ogni guasto e ogni morte deve scattare davvero.
 */
class SafetyEveryCallTest {

  private class Scenario(
    val name: String,
    /** Prepara il banco e restituisce l'ambito del guardiano. */
    val setup: suspend (SafetyBench) -> GuardScope,
    val check: suspend (SafetyBench, String) -> Unit,
  )

  /** I numeri per il report (giri, interruzioni, violazioni, byte letti). */
  private class Stats(var rounds: Int = 0, var interruptions: Int = 0, var violations: Int = 0, var bytesRead: Long = 0)

  private suspend fun midChecks(bench: SafetyBench, before: BackupInvariants.Snapshot, label: String, stats: Stats) {
    val v = bench.kit.violations(before, final = false)
    stats.violations += v.size
    assertWithMessage("$label: invarianti a meta'").that(v).isEmpty()
  }

  private suspend fun finalChecks(bench: SafetyBench, s: Scenario, before: BackupInvariants.Snapshot, label: String, stats: Stats) {
    val v = bench.kit.violations(before, final = true)
    stats.violations += v.size
    assertWithMessage("$label: invarianti alla fine").that(v).isEmpty()
    s.check(bench, label)
  }

  // --- Guardiano: guasto a ogni chiamata ---------------------------------------------------------------------------------------

  private suspend fun guardianFaults(s: Scenario, stats: Stats): Int {
    val clean = SafetyBench()
    val scope = s.setup(clean)
    val cleanBefore = clean.kit.snapshot()
    val counter = FaultPlan()
    clean.nas.opsFaults = counter
    clean.nas.readFaults = counter
    clean.guardian().ensure(scope, 1.hours)
    clean.nas.opsFaults = null
    clean.nas.readFaults = null
    clean.guardUntilQuiet(scope)
    finalChecks(clean, s, cleanBefore, "${s.name}, pulito", stats)
    val total = counter.operations
    assertWithMessage("${s.name}: chiamate contate").that(total).isAtLeast(3)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val bench = SafetyBench()
        val sc = s.setup(bench)
        val before = bench.kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        bench.nas.opsFaults = fault
        bench.nas.readFaults = fault
        val readBefore = bench.nas.bytesRead.get()
        bench.guardian().ensure(sc, 1.hours)
        bench.nas.opsFaults = null
        bench.nas.readFaults = null
        val label = "${s.name}, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        stats.interruptions++
        midChecks(bench, before, label, stats)
        // Il processo si ricrea dal diario: un guardiano nuovo sopra lo stesso NAS, telefono, diario e catalogo.
        bench.guardUntilQuiet(sc)
        stats.rounds++
        stats.bytesRead += bench.nas.bytesRead.get() - readBefore
        finalChecks(bench, s, before, label, stats)
      }
    }
    return total
  }

  // --- Guardiano: morte del processo a ogni scrittura del diario e del catalogo -------------------------------------------------

  private fun SafetyBench.dyingGuardian(death: BackupDeath) =
    guardian(store = DyingBackupStore(kit.store, death), catalog = DyingBackupCatalog(kit.catalog, death))

  private suspend fun guardianDeaths(s: Scenario, stats: Stats): Int {
    val clean = SafetyBench()
    val scope = s.setup(clean)
    val counter = BackupDeath()
    clean.dyingGuardian(counter).ensure(scope, 1.hours)
    val total = counter.steps
    assertWithMessage("${s.name}: scritture contate").that(total).isAtLeast(3)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val bench = SafetyBench()
        val sc = s.setup(bench)
        val before = bench.kit.snapshot()
        val death = BackupDeath(n, after)
        val readBefore = bench.nas.bytesRead.get()
        try {
          bench.dyingGuardian(death).ensure(sc, 1.hours)
        } catch (_: ProcessDeath) {
          // il processo e' morto: si butta cio' che sta in memoria e si riparte dal diario
        }
        val label = "${s.name}, morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        stats.interruptions++
        midChecks(bench, before, label, stats)
        bench.guardUntilQuiet(sc)
        stats.rounds++
        stats.bytesRead += bench.nas.bytesRead.get() - readBefore
        finalChecks(bench, s, before, label, stats)
      }
    }
    return total
  }

  private suspend fun guardianBoth(s: Scenario) {
    val stats = Stats()
    val calls = guardianFaults(s, stats)
    val writes = guardianDeaths(s, stats)
    println(
      "P3 guardiano ${s.name}: $calls chiamate x 2 fasi, $writes scritture x 2; giri ${stats.rounds}, interruzioni ${stats.interruptions}, " +
        "violazioni ${stats.violations}, byte riletti per giro ${if (stats.rounds == 0) 0 else stats.bytesRead / stats.rounds}",
    )
  }

  private fun SafetyBench.only() = guardItems().single()

  private fun SafetyBench.assertDone(label: String, mediaId: MediaId, data: ByteArray, name: String) {
    assertWithMessage(label).that(only().state).isEqualTo(UploadState.DONE)
    assertWithMessage(label).that(kit.catalog.isSafe(mediaId)).isTrue()
    assertWithMessage(label).that(nas.bytes(kit.share, "${guardDir()}/$name")?.toList()).isEqualTo(data.toList())
  }

  @Test
  fun guardianoCopiaLatoServer() = runTest {
    val data = SafetyBench.bytes(40_000, 1)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "copia lato server",
        setup = { b ->
          id = b.commonOnly("IMG_1.jpg", data).mediaId
          GuardScope.All
        },
        check = { b, label -> b.assertDone(label, id!!, data, "IMG_1.jpg") },
      ),
    )
  }

  @Test
  fun guardianoRipiegoDalTelefono() = runTest {
    val data = SafetyBench.bytes(40_000, 2)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "ripiego dal telefono (E-D12)",
        setup = { b ->
          b.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
          id = b.commonOnly("IMG_2.jpg", data, onPhone = true).mediaId
          GuardScope.Media(setOf(id!!))
        },
        check = { b, label -> b.assertDone(label, id!!, data, "IMG_2.jpg") },
      ),
    )
  }

  @Test
  fun guardianoRipiegoDallaSorgente() = runTest {
    val data = SafetyBench.bytes(40_000, 3)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "ripiego dalla sorgente (E-D12)",
        setup = { b ->
          b.nas.serverCopyMode = ServerCopyMode.ACCESS_DENIED
          id = b.commonOnly("IMG_3.jpg", data).mediaId
          GuardScope.All
        },
        check = { b, label -> b.assertDone(label, id!!, data, "IMG_3.jpg") },
      ),
    )
  }

  @Test
  fun guardianoConRiferimentoLettoQui() = runTest {
    val data = SafetyBench.bytes(150_000, 4)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "riferimento letto dalla sorgente",
        setup = { b ->
          id = b.commonOnly("IMG_4.jpg", data, hashKnown = false).mediaId
          GuardScope.All
        },
        check = { b, label ->
          b.assertDone(label, id!!, data, "IMG_4.jpg")
          assertWithMessage(label).that(b.catalog.media(id!!)!!.fullHash).isEqualTo(Sha256.of(data))
        },
      ),
    )
  }

  @Test
  fun guardianoNomeOccupato() = runTest {
    val data = SafetyBench.bytes(30_000, 5)
    val other = SafetyBench.bytes(31_000, 6)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "nome occupato (E-B3)",
        setup = { b ->
          id = b.commonOnly("IMG_5.jpg", data).mediaId
          b.putFile(b.share, "${b.guardDir()}/IMG_5.jpg", other)
          GuardScope.All
        },
        check = { b, label ->
          assertWithMessage(label).that(b.only().state).isEqualTo(UploadState.DONE)
          assertWithMessage(label).that(b.nas.bytes(b.share, "${b.guardDir()}/IMG_5 (2).jpg")?.toList()).isEqualTo(data.toList())
          assertWithMessage(label).that(b.nas.bytes(b.share, "${b.guardDir()}/IMG_5.jpg")?.toList()).isEqualTo(other.toList())
          assertWithMessage(label).that(b.catalog.isSafe(id!!)).isTrue()
        },
      ),
    )
  }

  @Test
  fun guardianoCopiaGiaPrivata() = runTest {
    val data = SafetyBench.bytes(30_000, 7)
    var id: MediaId? = null
    guardianBoth(
      Scenario(
        "copia identica gia' nell'area privata (D8)",
        setup = { b ->
          id = b.commonOnly("IMG_7.jpg", data).mediaId
          b.kit.putNasFile(b.share, "Backup telefono 2023/IMG_7.jpg", data.content())
          GuardScope.All
        },
        check = { b, label ->
          assertWithMessage(label).that(b.only().state).isEqualTo(UploadState.LINKED_EXISTING)
          assertWithMessage(label).that(b.visible(b.share)).containsExactly("Backup telefono 2023/IMG_7.jpg")
          assertWithMessage(label).that(b.catalog.isSafe(id!!)).isTrue()
        },
      ),
    )
  }

  // --- Verifiche -----------------------------------------------------------------------------------------------------------------

  /** Le tre copie del giro delle verifiche: buona non verificata, rovinata (telefono la ha), rovinata e scaduta (telefono no). */
  private class VerifySetup(val good: SafetyBench.Start, val badOnPhone: SafetyBench.Start, val badLost: SafetyBench.Start, val fresh: MediaId)

  private suspend fun verifySetup(b: SafetyBench): VerifySetup {
    val day = 86_400_000L
    val now = b.kit.clock.nasNow!!
    val good = b.withPrivateCopy("IMG_V1.jpg", SafetyBench.bytes(20_000, 10), verifiedAt = null)
    val badOnPhone = b.withPrivateCopy("IMG_V2.jpg", SafetyBench.bytes(21_000, 11), verifiedAt = null)
    val badLost = b.withPrivateCopy("IMG_V3.jpg", SafetyBench.bytes(22_000, 12), verifiedAt = now - 400 * day, onPhone = false)
    for (s in listOf(badOnPhone, badLost)) {
      val stamp = b.stampOf(b.share, s.commonPath!!.value)!!
      b.nas.modify(b.share, s.commonPath.value, SafetyBench.oneByteChanged(s.data).content(), modifiedAt = stamp.modifiedAtMillis)
    }
    // Una copia privata senza hash della versione (FULL_HASH).
    val fresh = b.withPrivateCopy("VID_V4.mp4", SafetyBench.bytes(140_000, 13), verifiedAt = null, hashKnown = false, onPhone = false)
    return VerifySetup(good, badOnPhone, badLost, fresh.mediaId)
  }

  private suspend fun verifyRound(b: SafetyBench, verifier: BackupSafetyVerifier) {
    for (scope in listOf(VerifyScope.FullHash, VerifyScope.Unverified, VerifyScope.Rotation)) verifier.verify(scope, 1.hours)
  }

  /**
   * Gli invarianti a meta' giro delle verifiche. La copia rovinata in silenzio e verificata un anno fa e' "al sicuro" per finta **finche'
   * la rotazione non la rilegge**: e' il danno che il test semina (il disco che sbaglia), non un errore delle verifiche; l'invariante 4
   * per lei vale solo alla fine.
   */
  private suspend fun verifyMidChecks(b: SafetyBench, s: VerifySetup, before: BackupInvariants.Snapshot, label: String, stats: Stats) {
    val seeded = b.catalog.isSafe(s.badLost.mediaId)
    val v = b.kit.violations(before, final = false).filterNot { seeded && it.startsWith("4:") }
    stats.violations += v.size
    assertWithMessage("$label: invarianti a meta'").that(v).isEmpty()
  }

  private suspend fun verifyUntilQuiet(b: SafetyBench) {
    // La rotazione toglie "verificata" a una copia che non torna; la copia rientra fra le non verificate: due giri bastano.
    repeat(2) { verifyRound(b, b.verifier()) }
  }

  private fun verifyFinal(b: SafetyBench, s: VerifySetup, label: String) {
    assertWithMessage("$label: buona").that(b.catalog.isSafe(s.good.mediaId)).isTrue()
    assertWithMessage("$label: rovinata").that(b.catalog.isSafe(s.badOnPhone.mediaId)).isFalse()
    assertWithMessage("$label: rovinata, segno").that(b.catalog.problemOf(s.badOnPhone.mediaId)).isNull()
    assertWithMessage("$label: rovinata e persa").that(b.catalog.isSafe(s.badLost.mediaId)).isFalse()
    assertWithMessage("$label: rovinata e persa, segno").that(b.catalog.problemOf(s.badLost.mediaId)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertWithMessage("$label: FULL_HASH").that(b.catalog.media(s.fresh)!!.fullHash).isEqualTo(Sha256.of(SafetyBench.bytes(140_000, 13)))
    assertWithMessage("$label: FULL_HASH al sicuro").that(b.catalog.isSafe(s.fresh)).isTrue()
  }

  @Test
  fun verificheGuastoAOgniChiamataEMorteAOgniScrittura() = runTest {
    val stats = Stats()
    // Guasto a ogni chiamata.
    val clean = SafetyBench()
    val cs = verifySetup(clean)
    val counter = FaultPlan()
    clean.nas.readFaults = counter
    clean.nas.opsFaults = counter
    verifyRound(clean, clean.verifier())
    clean.nas.readFaults = null
    clean.nas.opsFaults = null
    verifyUntilQuiet(clean)
    verifyFinal(clean, cs, "pulito")
    val total = counter.operations
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val b = SafetyBench()
        val s = verifySetup(b)
        val before = b.kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        b.nas.readFaults = fault
        b.nas.opsFaults = fault
        val readBefore = b.nas.bytesRead.get()
        verifyRound(b, b.verifier())
        b.nas.readFaults = null
        b.nas.opsFaults = null
        val label = "verifiche, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        stats.interruptions++
        verifyMidChecks(b, s, before, label, stats)
        verifyUntilQuiet(b)
        stats.rounds++
        stats.bytesRead += b.nas.bytesRead.get() - readBefore
        verifyFinal(b, s, label)
        val v = b.kit.violations(before, final = true)
        stats.violations += v.size
        assertWithMessage("$label: invarianti alla fine").that(v).isEmpty()
      }
    }
    // Morte del processo a ogni scrittura del catalogo (markVerified, recordFullHash, markProblem).
    val cleanDeath = SafetyBench()
    verifySetup(cleanDeath)
    val steps = BackupDeath()
    verifyRound(cleanDeath, cleanDeath.verifier(catalog = DyingBackupCatalog(cleanDeath.kit.catalog, steps)))
    val writes = steps.steps
    assertWithMessage("scritture contate").that(writes).isAtLeast(4)
    for (n in 1..writes) {
      for (after in listOf(false, true)) {
        val b = SafetyBench()
        val s = verifySetup(b)
        val before = b.kit.snapshot()
        val death = BackupDeath(n, after)
        val readBefore = b.nas.bytesRead.get()
        try {
          verifyRound(b, b.verifier(catalog = DyingBackupCatalog(b.kit.catalog, death)))
        } catch (_: ProcessDeath) {
          // il processo e' morto
        }
        val label = "verifiche, morte alla scrittura $n/$writes ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        stats.interruptions++
        verifyMidChecks(b, s, before, label, stats)
        verifyUntilQuiet(b)
        stats.rounds++
        stats.bytesRead += b.nas.bytesRead.get() - readBefore
        verifyFinal(b, s, label)
        val v = b.kit.violations(before, final = true)
        stats.violations += v.size
        assertWithMessage("$label: invarianti alla fine").that(v).isEmpty()
      }
    }
    println(
      "P3 verifiche: $total chiamate x 2 fasi, $writes scritture x 2; giri ${stats.rounds}, interruzioni ${stats.interruptions}, " +
        "violazioni ${stats.violations}, byte riletti per giro ${stats.bytesRead / maxOf(1, stats.rounds)}",
    )
  }

  // --- Deduplica -----------------------------------------------------------------------------------------------------------------

  /**
   * La deduplica a ogni chiamata: con un guasto il risultato e' la copia giusta o un'interruzione (il motore ferma il giro), **mai**
   * "nessuna copia" (che caricherebbe un doppione) ne' una copia sbagliata; ripresa senza guasti = la copia giusta. Non scrive niente,
   * quindi non c'e' una morte del processo da provare (le scritture sono del motore, P2).
   */
  @Test
  fun deduplicaGuastoAOgniChiamata() = runTest {
    val data = SafetyBench.bytes(200_000, 20)
    val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"
    suspend fun setup(b: SafetyBench): Pair<BackupCandidate, Sha256> {
      b.kit.putNasFile(b.share, "Vecchio/IMG_D.jpg", SafetyBench.oneByteChanged(data).content())
      b.kit.putNasFile(b.share, "Altro/IMG_D.jpg", SafetyBench.oneByteChanged(data, at = 10).content())
      b.kit.putNasFile(b.share, "$dir/IMG_D.jpg", data.content())
      val id = b.kit.addPhoneMedia("IMG_D.jpg", data.content())
      val m = b.catalog.media(id)!!
      val candidate = BackupCandidate(
        m.mediaId, m.versionId, m.phone!!, m.phoneStamp!!, m.size, m.kind, m.source, m.localDay, false, m.fileName, m.sortTime,
        QuickHashAlgorithm.ofContent(data),
      )
      return candidate to Sha256.of(data)
    }
    val clean = SafetyBench()
    val (cc, csha) = setup(clean)
    val counter = FaultPlan()
    clean.nas.readFaults = counter
    val cleanResult = clean.finder().find(cc, csha, clean.share)
    clean.nas.readFaults = null
    assertWithMessage("pulito").that((cleanResult as ExistingCopyResult.Found).copy.path.value).isEqualTo("$dir/IMG_D.jpg")
    val total = counter.operations
    var interrupted = 0
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val b = SafetyBench()
        val (c, sha) = setup(b)
        val before = b.kit.snapshot()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        b.nas.readFaults = fault
        val result = b.finder().find(c, sha, b.share)
        b.nas.readFaults = null
        val label = "deduplica, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        when (result) {
          is ExistingCopyResult.Found -> assertWithMessage(label).that(result.copy.path.value).isEqualTo("$dir/IMG_D.jpg")
          is ExistingCopyResult.Interrupted -> interrupted++
          ExistingCopyResult.None -> assertWithMessage("$label: 'nessuna copia' con una copia identica presente").fail()
        }
        val again = b.finder().find(c, sha, b.share)
        assertWithMessage("$label: ripresa").that((again as ExistingCopyResult.Found).copy.path.value).isEqualTo("$dir/IMG_D.jpg")
        assertWithMessage("$label: niente scritto").that(b.kit.snapshot().files.keys).isEqualTo(before.files.keys)
      }
    }
    println("P3 deduplica: $total chiamate x 2 fasi, $interrupted interruzioni, 0 violazioni")
  }
}
