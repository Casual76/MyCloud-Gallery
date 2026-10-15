package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il runner dell'area di prova (docs/fase4-progetto.md §5.4, §5.5): la guardia, il runner a giri brevi e la verifica dell'area che si
 * esegue anche da sola. Qui gira contro il NAS finto DIETRO LA STESSA GUARDIA del NAS vero; il NAS vero lo tocca solo
 * [ChaosNasIntegrationTest], e solo quando P12 consegna l'aggancio (docs/prova-500.md).
 */
class ChaosAreaRunnerTest {

  private val runId = "20261015-101500-1a2b3c4d"

  private fun properties(vararg pairs: Pair<String, String>): Properties = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

  private fun credentials(vararg extra: Pair<String, String>): ChaosCredentials =
    ChaosCredentials.parse(
      properties("user" to "test", "password" to "x", "share" to "test", "share2" to "test-comune", "host" to "192.168.178.58", *extra),
    )!!

  // --- La configurazione: prima di qualunque socket -----------------------------------------------------------------------------------

  @Test
  fun laGuardiaAmmetteSoloLeShareDiProvaDelFileEDelCodice() {
    val guard = ChaosAreaGuard.of(credentials(), runId)
    assertThat(guard.shares).containsExactly("test", "test-comune")
    assertThat(guard.root.value).isEqualTo("mcg-it/$runId")
    assertThat(guard.roundRoot(7).value).isEqualTo("mcg-it/$runId/r7")
    // Una share del file che non e' nell'elenco del codice allarga il recinto? No: la configurazione e' rifiutata.
    for (bad in listOf("alessio", "pampa", "NAS", "Public", "test2x", "ＮＡＳ")) {
      assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("share2" to bad), runId) }
    }
    // Un account che non e' di prova.
    for (user in listOf("admin", "alessio", "PAMPA", "root", "Administrator", "test3")) {
      val raw = properties("user" to user, "password" to "x", "share" to "test")
      assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(ChaosCredentials.parse(raw)!!, runId) }
    }
    // Il secondo account va controllato come il primo, e una password2 senza user2 e' un errore.
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("user2" to "alessio", "password2" to "x"), runId) }
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("password2" to "x"), runId) }
    // Numeri fuori misura e host che non e' un IP.
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("chaos.rounds" to "-5"), runId) }
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("chaos.rounds" to "abc"), runId) }
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("chaos.bigFileMb" to "0"), runId) }
    assertThrows(AreaViolation::class.java) { ChaosAreaGuard.of(credentials("host" to "nas.local"), runId) }
    // Un nome di giro che non e' un nome di giro.
    assertThrows(IllegalArgumentException::class.java) { ChaosAreaGuard.of(credentials(), "../x") }
  }

  @Test
  fun ilFileDelleCredenzialiNonSiStampaMai() {
    val credentials = credentials("password2" to "segreto2", "user2" to "test2")
    assertThat(credentials.toString()).doesNotContain("x")
    assertThat(credentials.toString()).doesNotContain("segreto")
  }

  @Test
  fun leChiaviFacoltativeHannoIDefaultRagionevoli() {
    val plain = credentials()
    assertThat(plain.rounds).isNull()
    assertThat(plain.bigFileMb).isNull()
    assertThat(ChaosNasGate.interruptions(plain)).isEqualTo(400)
    assertThat(ChaosNasGate.bigFileBytes(plain)).isEqualTo(200 shl 20)
    val tuned = credentials("chaos.rounds" to "20", "chaos.bigFileMb" to "5")
    assertThat(ChaosNasGate.interruptions(tuned)).isEqualTo(20)
    assertThat(ChaosNasGate.bigFileBytes(tuned)).isEqualTo(5 shl 20)
  }

  // --- L'autotest: tutto cio' che deve essere rifiutato lo e', con zero chiamate al vero -------------------------------------------

  @Test
  fun lAutotestDellaGuardiaRifiutaTuttoConZeroChiamateAlVero(): Unit = runBlocking {
    val guard = ChaosAreaGuard.of(credentials(), runId)
    val counter = DelegateCounter()
    val world = GuardedTarget(InMemoryTarget(), guard, counter).openWorld(0)
    val result = ChaosAreaSelfTest.run(guard, world.reader, world.fileOps, counter)
    assertWithMessage(result.failures.joinToString("\n")).that(result.failures).isEmpty()
    assertThat(result.cases).isGreaterThan(40)
    assertThat(counter.calls.get()).isEqualTo(0)
  }

  // --- Il runner a giri brevi: al massimo 20 interruzioni --------------------------------------------------------------------------

  @Test
  fun ilRunnerSulNasFintoDietroLaGuardiaFaVentiInterruzioniSenzaViolazioni(): Unit = runBlocking {
    val guard = ChaosAreaGuard.of(credentials(), runId)
    val target = GuardedTarget(InMemoryTarget(), guard)
    val settings = ChaosAreaRunner.Settings(interruptions = 20, baseSeed = 20261015L, mediumBytes = 300_000, bigBytes = 600_000, bigPercent = 10)
    val result = ChaosAreaRunner.run(target, FakeChaosStackFactory, settings)
    println(result.summary())
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(20)
    // Si ferma appena le interruzioni sono cadute: non va avanti per 400.
    assertThat(result.stats.totalFired).isAtMost(20 + 6)
    assertThat(target.counter.calls.get()).isGreaterThan(0)
  }

  // --- La verifica che si esegue da sola ---------------------------------------------------------------------------------------------

  @Test
  fun ilManifestoSiScriveESiRilegge() {
    val file = File.createTempFile("mcg-manifest", ".txt")
    try {
      val a = Original.of(0, OriginalBytes.make(Random(1), 0, 500))
      val b = Original.of(1, OriginalBytes.make(Random(1), 1, 500))
      val manifest = ChaosManifest(
        runId,
        InMemoryTarget.DEVICE,
        listOf(ScanScope.MediaRoot("test", SharePath.parse("mcg-it/$runId")), ScanScope.MediaRoot("test-comune", SharePath.parse("mcg-it/$runId"))),
        listOf(ScanScope.AppArea("test", null, false)),
        listOf(a, b).map { Original(it.id, it.sha, it.size, null, "foto${it.id}") },
        mapOf(a.sha to ChaosManifest.Expect.Visible("test-comune", "destinazione"), b.sha to ChaosManifest.Expect.Trash),
      )
      manifest.write(file)
      val back = ChaosManifest.read(file)
      assertThat(back.runId).isEqualTo(runId)
      assertThat(back.deviceId).isEqualTo(InMemoryTarget.DEVICE)
      assertThat(back.roots.map { it.share }).containsExactly("test", "test-comune").inOrder()
      assertThat(back.roots.first().dir?.value).isEqualTo("mcg-it/$runId")
      assertThat(back.appAreas.single().full).isFalse()
      assertThat(back.originals.map { it.sha }).containsExactly(a.sha, b.sha).inOrder()
      assertThat(back.expectations[a.sha]).isEqualTo(ChaosManifest.Expect.Visible("test-comune", "destinazione"))
      assertThat(back.expectations[b.sha]).isEqualTo(ChaosManifest.Expect.Trash)
      assertThrows(IllegalArgumentException::class.java) { ChaosManifest.parse(listOf("run=x", "original=zzz|1|a")) }
      assertThrows(IllegalArgumentException::class.java) { ChaosManifest.parse(listOf("root=test")) }
    } finally {
      file.delete()
    }
  }

  @Test
  fun laVerificaDaSolaTrovaUnaPerditaUnDoppioneEUnFileAMeta(): Unit = runBlocking {
    val world = InMemoryWorld(listOf("test", "test-comune"), InMemoryTarget.DEVICE, "test")
    val random = Random(5)
    // Una prova fatta "da un'altra parte": 6 originali in sorgente/ (come li metterebbe il PC prima di una mossa di massa dal telefono).
    val manifest = ChaosAreaSeeder.seed(world, runId, mediaDir = null, files = 6, random = random, mediumBytes = 5_000, bigBytes = 8_000, bigPercent = 0)
    assertThat(manifest.originals).hasSize(6)
    // Appena seminata, l'area e' a posto: ogni originale e' visibile una volta sola.
    val fresh = ChaosAreaVerifier.verify(world.reader, manifest, settled = true)
    assertWithMessage(fresh.text()).that(fresh.violations).isEmpty()
    assertThat(fresh.snapshot.filesHashed).isEqualTo(6)

    // Il file di un originale sparisce: perdita.
    val lost = world.nas.bytes("test", "sorgente/foto0000.jpg")!!
    world.nas.delete("test", "sorgente/foto0000.jpg")
    assertThat(ChaosAreaVerifier.verify(world.reader, manifest, settled = true).violations.map { it.invariant }).contains(Invariant.LOSS)
    // Ricompare ma a meta' (i primi byte soltanto): un file a meta' e una perdita.
    world.nas.putFile("test", "sorgente/foto0000.jpg", NasContent.of(lost.copyOf(lost.size / 2)))
    val half = ChaosAreaVerifier.verify(world.reader, manifest, settled = true)
    assertThat(half.violations.map { it.invariant }).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
    // Non ancora ferma (la prova sul telefono e' in corso): restano gli invarianti che valgono sempre.
    val unsettled = ChaosAreaVerifier.verify(world.reader, manifest, settled = false)
    assertThat(unsettled.violations.map { it.invariant }).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
    assertThat(unsettled.text()).contains("VIOLAZIONI")
    // Intero di nuovo, e copiato in un altro posto: due copie visibili. A meta' di una mossa potrebbe essere lecito, a operazioni finite no.
    world.nas.putFile("test", "sorgente/foto0000.jpg", NasContent.of(lost))
    world.nas.putFile("test-comune", "altro/foto0000.jpg", NasContent.of(lost))
    assertThat(ChaosAreaVerifier.verify(world.reader, manifest, settled = true).violations.map { it.invariant }).contains(Invariant.DUPLICATE)
    // Un temporaneo del dispositivo rimasto a operazioni finite, e uno di un altro dispositivo.
    world.nas.delete("test-comune", "altro/foto0000.jpg")
    world.nas.putFile("test-comune", ".mycloudgallery/tmp/${InMemoryTarget.DEVICE}-op-aaaaaaaaaaaaaaaaaaaaaaaaaa-0", NasContent.of(ByteArray(3)))
    assertThat(ChaosAreaVerifier.verify(world.reader, manifest, settled = true).violations.map { it.invariant }).contains(Invariant.TEMP)
  }

  @Test
  fun laVerificaDaSolaSaDoveDevonoStareGliOriginaliAllaFine(): Unit = runBlocking {
    val world = InMemoryWorld(listOf("test", "test-comune"), InMemoryTarget.DEVICE, "test")
    val manifest = ChaosAreaSeeder.seed(world, runId, mediaDir = null, files = 3, random = Random(9), mediumBytes = 2_000, bigBytes = 3_000, bigPercent = 0)
    // Dice dove devono stare alla fine: tutti in test-comune/destinazione.
    val expectations = manifest.originals.associate { it.sha to ChaosManifest.Expect.Visible("test-comune", "destinazione") }
    val expecting = ChaosManifest(manifest.runId, manifest.deviceId, manifest.roots, manifest.appAreas, manifest.originals, expectations)
    // Non sono ancora stati spostati: a operazioni finite il catalogo non torna...
    assertThat(ChaosAreaVerifier.verify(world.reader, expecting, settled = true).violations.map { it.invariant }).contains(Invariant.CATALOG)
    // ...ma a meta' va bene cosi' (nessuna violazione degli invarianti di sempre).
    assertThat(ChaosAreaVerifier.verify(world.reader, expecting, settled = false).violations).isEmpty()
    // Spostati davvero: torna.
    for (i in 0 until 3) {
      val bytes = world.nas.bytes("test", "sorgente/foto%04d.jpg".format(i))!!
      world.nas.delete("test", "sorgente/foto%04d.jpg".format(i))
      world.nas.putFile("test-comune", "destinazione/foto%04d.jpg".format(i), NasContent.of(bytes))
    }
    val done = ChaosAreaVerifier.verify(world.reader, expecting, settled = true)
    assertWithMessage(done.text()).that(done.violations).isEmpty()
  }

  // --- Il cancello per il NAS vero ---------------------------------------------------------------------------------------------------

  @Test
  fun senzaInterruttoreONasVeroNonSiLeggeNienteESiSalta() {
    // Questo test non apre sockets: senza MCG_NAS_INTEGRATION il cancello salta prima di leggere il file delle credenziali.
    if (!ChaosNasGate.enabled) {
      val outcome = ChaosNasGate.evaluate(runId)
      assertThat(outcome).isInstanceOf(ChaosNasGate.Outcome.Skip::class.java)
      assertThat((outcome as ChaosNasGate.Outcome.Skip).reason).contains("non richiesta")
    }
  }
}
