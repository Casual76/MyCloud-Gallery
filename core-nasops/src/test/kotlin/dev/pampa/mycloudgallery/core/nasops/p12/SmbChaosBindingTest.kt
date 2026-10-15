package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nasops.chaos.AreaViolation
import dev.pampa.mycloudgallery.core.nasops.chaos.ChaosAreaGuard
import dev.pampa.mycloudgallery.core.nasops.chaos.ChaosAreaRunner
import dev.pampa.mycloudgallery.core.nasops.chaos.ChaosAreaSelfTest
import dev.pampa.mycloudgallery.core.nasops.chaos.ChaosCredentials
import dev.pampa.mycloudgallery.core.nasops.chaos.GuardedTarget
import dev.pampa.mycloudgallery.core.nasops.chaos.ProductionChaosStackFactory
import dev.pampa.mycloudgallery.core.nasops.chaos.SmbChaosArea
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * L'aggancio del banco delle interruzioni al NAS vero ([SmbChaosArea], docs/prova-500.md §9 punto 3), provato SENZA rete: la sessione SMB di
 * produzione sopra il simulatore SMB di core-nas, dietro la stessa guardia del NAS vero, con lo stack vero di P12. Il runner fa le sue
 * interruzioni e alla fine sul "NAS" non c'e' niente fuori da `mcg-it/<giro>/`.
 */
class SmbChaosBindingTest {

  private val runId = "20261015-101500-1a2b3c4d"
  private val password = "pw-di-prova"

  private fun backend(): InMemorySmbBackend = InMemorySmbBackend().apply {
    addUser("test", password)
    addShare("test", mapOf("test" to SimAccess.READ_WRITE))
    addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE))
    addShare("alessio", mapOf("test" to SimAccess.READ_WRITE))
    val start = System.nanoTime()
    val base = nasTime()
    nasTime = { base + (System.nanoTime() - start) / 1_000_000 }
  }

  private fun credentials(vararg extra: Pair<String, String>): ChaosCredentials = ChaosCredentials.parse(
    Properties().apply {
      listOf("user" to "test", "password" to password, "share" to "test", "share2" to "test-comune", "host" to "192.168.178.58", *extra)
        .forEach { (k, v) -> setProperty(k, v) }
    },
  )!!

  private fun InMemorySmbBackend.allFiles(share: String, dir: String = ""): List<String> =
    children(share, dir).flatMap { name ->
      val path = if (dir.isEmpty()) name else "$dir/$name"
      if (bytes(share, path) != null) listOf(path) else allFiles(share, path) + "$path/"
    }

  @Test
  fun ilRunnerDietroLaGuardiaSullaSessioneVeraNonEsceDallAreaDelGiro(): Unit = runBlocking {
    val backend = backend()
    val area = SmbChaosArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = ChaosAreaGuard.of(credentials(), runId)
    try {
      val target = GuardedTarget(area.openTarget(credentials(), guard), guard)
      // L'autotest della guardia: tutto rifiutato, zero chiamate al vero.
      val world = target.openWorld(0)
      val self = ChaosAreaSelfTest.run(guard, world.reader, world.fileOps, target.counter)
      world.close()
      assertWithMessage(self.failures.joinToString()).that(self.failures).isEmpty()
      val settings = ChaosAreaRunner.Settings(interruptions = 20, baseSeed = 20261015L, mediumBytes = 300_000, bigBytes = 600_000, bigPercent = 10)
      val result = ChaosAreaRunner.run(target, ProductionChaosStackFactory, settings)
      println(result.summary())
      assertWithMessage(result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(20)
      assertThat(target.counter.calls.get()).isGreaterThan(0)
    } finally {
      area.close()
    }
    // Una sola sessione, una sola prova della password (piu' le riconnessioni dopo le cadute, con la password gia' verificata).
    assertThat(area.sessionsOpened).isEqualTo(1)
    // Niente fuori da mcg-it/<giro>/, in nessuna share; la share di famiglia non e' mai stata toccata.
    for (share in listOf("test", "test-comune")) {
      val outside = backend.allFiles(share).filterNot { it.startsWith("mcg-it/") }.filterNot { it == "mcg-it/" }
      assertWithMessage("fuori dall'area in $share").that(outside).isEmpty()
      assertThat(backend.allFiles(share).filter { !it.endsWith("/") }.all { it.startsWith("mcg-it/$runId/r") }).isTrue()
    }
    assertThat(backend.allFiles("alessio")).isEmpty()
  }

  @Test
  fun unaPasswordSbagliataSiProvaUnaVoltaSola(): Unit = runBlocking {
    val backend = backend()
    val area = SmbChaosArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val wrong = credentials("password" to "sbagliata")
    val guard = ChaosAreaGuard.of(wrong, runId)
    val failure = runCatching { area.openTarget(wrong, guard) }.exceptionOrNull()
    area.close()
    assertThat(failure).isInstanceOf(AssertionError::class.java)
    assertThat(failure!!.message).doesNotContain("sbagliata")
    assertThat(backend.authenticateCalls.get()).isEqualTo(1)
  }

  @Test
  fun laGuardiaFermaTuttoPrimaDelPrimoSocket(): Unit = runBlocking {
    val backend = backend()
    val area = SmbChaosArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = ChaosAreaGuard.of(credentials(), runId)
    // Credenziali diverse da quelle della guardia: una share fuori dall'area.
    val other = credentials("share2" to "alessio")
    assertThrows(AreaViolation::class.java) { runBlocking { area.openTarget(other, guard) } }
    assertThat(backend.tcpAttempts.get()).isEqualTo(0)
  }
}
