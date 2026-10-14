package dev.pampa.mycloudgallery.core.nas.integration

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.DEVICE
import dev.pampa.mycloudgallery.core.nas.GOOD_PASSWORD
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La guardia dell'area di prova (§5.4) **senza NAS**: gira sempre, non solo con `-PnasIntegration`. Configurazione,
 * autotest, recinto delle interfacce e del trasporto (sul simulatore), e la regola "nessun accesso diretto a smbj nei
 * test della Fase 4" (punto 6).
 */
class TestAreaGuardTest {

  private val run = "20261014-120000-0a1b2c3d"

  private fun credentials(vararg entries: Pair<String, String>): ItCredentials {
    val properties = Properties().apply {
      setProperty("user", "test")
      setProperty("password", "x")
      setProperty("share", "test")
      for ((k, v) in entries) setProperty(k, v)
    }
    return checkNotNull(ItCredentials.parse(properties))
  }

  @Test
  fun lAutotestPassaConUnaConfigurazioneDiProva() {
    val guard = TestAreaGuard.of(credentials("share2" to "test-comune", "user2" to "test2", "password2" to "y", "privateShare2" to "test2"), run)
    val result = TestAreaSelfTest.run(guard)
    assertWithMessage(result.failures.joinToString()).that(result.failures).isEmpty()
    assertThat(result.cases).isAtLeast(80)
    assertThat(guard.shares).containsExactly("test", "test-comune", "test2")
  }

  @Test
  fun unErroreNelFileNonAllargaIlRecintoFaFallirePrimaDelSocket() {
    val bad = listOf(
      arrayOf("user" to "alessio"),
      arrayOf("user" to "admin"),
      arrayOf("user" to "ＡＬＥＳＳＩＯ"),
      arrayOf("user2" to "pampa", "password2" to "z"),
      arrayOf("share" to "Public"),
      arrayOf("share" to "NAS"),
      arrayOf("share" to "ｔｅｓｔ"),
      arrayOf("share2" to "public"),
      arrayOf("privateShare2" to "alessio"),
      arrayOf("password2" to "senza-user2"),
      arrayOf("chaos.bigFileMb" to "0"),
      arrayOf("chaos.rounds" to "tanti"),
      arrayOf("host" to "nas.local"),
    )
    for (entries in bad) {
      assertThrows(entries.joinToString(), TestAreaViolation::class.java) { TestAreaGuard.of(credentials(*entries), run) }
    }
    // Le chiavi nuove valide passano; le maiuscole di una share di prova sono la stessa share (Samba).
    val ok = TestAreaGuard.of(credentials("share" to "TEST", "chaos.rounds" to "400", "chaos.bigFileMb" to "200"), run)
    assertThat(ok.shares).containsExactly("test")
    assertThat(ItCredentials.parse(Properties())).isNull()
  }

  @Test
  fun dentroLAreaSiPassaAncheConLeMaiuscoleFuoriNo() {
    val guard = TestAreaGuard.of(credentials(), run)
    guard.checkPath("test", SharePath.of("mcg-it", run, "a.jpg"))
    guard.checkPath("Test", SharePath.of("MCG-IT", run.uppercase(), "Foto", "a.jpg"))
    guard.checkSmb("test", "mcg-it\\$run\\.mycloudgallery\\tmp\\x", SmbAccess.CREATE)
    guard.checkSmb("test", "mcg-it", SmbAccess.MKDIR)
    assertThrows(TestAreaViolation::class.java) { guard.checkSmb("test", "mcg-it", SmbAccess.DELETE) }
    assertThrows(TestAreaViolation::class.java) { guard.checkSmb("test", "mcg-it\\altro\\x", SmbAccess.DELETE) }
    assertThrows(TestAreaViolation::class.java) { guard.checkPath("test", SharePath.of("mcg-it", run)) }
    // Un giro vecchio si pulisce solo se approvato, e solo se ha il nome di un giro.
    val old = "20261001-080000-ffffffff"
    assertThrows(TestAreaViolation::class.java) { guard.checkSmb("test", "mcg-it\\$old\\x", SmbAccess.DELETE) }
    guard.approveStale(old)
    guard.checkSmb("test", "mcg-it\\$old\\x", SmbAccess.DELETE)
    assertThrows(TestAreaViolation::class.java) { guard.checkSmb("test", "mcg-it\\$old\\x", SmbAccess.CREATE) }
    assertThrows(IllegalArgumentException::class.java) { guard.approveStale("Foto") }
    assertThrows(IllegalArgumentException::class.java) { guard.approveStale(run) }
  }

  @Test
  fun ilTrasportoRecintatoLasciaPassareSoloLAreaEVedeAncheITemporanei() = runTest {
    val backend = InMemorySmbBackend().apply {
      addUser("test", GOOD_PASSWORD)
      for (share in listOf("test", "test-comune", "NAS", "Public")) addShare(share, mapOf("test" to SimAccess.READ_WRITE))
    }
    val guard = TestAreaGuard.of(credentials("share2" to "test-comune"), run)
    val transport = TestAreaTransport(backend, guard)
    val h = Harness(backgroundScope, virtualClock(), backend = backend)
    val manager = dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager(
      account = { dev.pampa.mycloudgallery.core.nas.contract.NasAccount(dev.pampa.mycloudgallery.core.network.contract.NasAddress("192.168.178.58"), "test", "s", backend.serverGuid) },
      routeGate = h.gate,
      credentials = h.credentials,
      breakers = h.breakers,
      scope = backgroundScope,
      transport = transport,
      clock = virtualClock(),
      io = kotlinx.coroutines.Dispatchers.Unconfined,
      deviceId = { DEVICE },
      backoff = { },
    )

    // L'indice di prova (radice spostata): temporanei e file finali dentro mcg-it/<giro>/.
    val rooted = manager.rootedFileSystem("test", guard.root)
    assertThat(rooted.publishExclusive(SharePath.of(TrustFormat.ROOT_DIR, "registro", "1"), byteArrayOf(1))).isEqualTo(PublishResult.CREATED)
    assertThat(backend.exists("test", "mcg-it/$run/.mycloudgallery/registro/1")).isTrue()
    assertThat(backend.exists("test", ".mycloudgallery")).isFalse()

    // Lo stesso NasFileSystem senza radice spostata scriverebbe il temporaneo in .mycloudgallery/tmp della share: il
    // trasporto lo ferma prima della richiesta (e la suite fallisce, invece di scrivere fuori).
    val before = backend.shareOps.get()
    assertThrows(TestAreaViolation::class.java) { manager.fileSystem("test").publishExclusive(SharePath.of("mcg-it", run, "x"), byteArrayOf(1)) }
    assertThat(backend.shareOps.get()).isEqualTo(before)
    assertThat(backend.exists("test", ".mycloudgallery")).isFalse()

    // Copia lato server tra le due share di prova, con i temporanei nell'area.
    val copy = SmbServerSideCopy(manager, Purpose.BACKGROUND, SecureRandom(), guard.root)
    val media = TestAreaMedia(manager.mediaReader(), copy, guard)
    val from = SharePath.of("mcg-it", run, ".mycloudgallery", "registro", "1")
    assertThat(media.serverCopy.copy("test", from, "test-comune", SharePath.of("mcg-it", run, "copia"))).isEqualTo(NasResult.Ok(1L))
    assertThat(backend.exists("test-comune", "mcg-it/$run/copia")).isTrue()
    // Verso una share pubblica: ne' le interfacce ne' il trasporto.
    assertThrows(TestAreaViolation::class.java) {
      kotlinx.coroutines.runBlocking { media.serverCopy.copy("test", from, "Public", SharePath.of("mcg-it", run, "copia")) }
    }
    assertThat(backend.exists("Public", "mcg-it")).isFalse()
    assertThat(backend.treeConnects.get()).isEqualTo(2)
  }

  @Test
  fun nessunTestDIntegrazioneUsaSmbjDirettamente() {
    // §5.4, punto 6: tutto passa dal trasporto recintato, che non si puo' aggirare con un client smbj "a mano".
    val dir = listOf(
      File("src/test/kotlin/dev/pampa/mycloudgallery/core/nas/integration"),
      File("core-nas/src/test/kotlin/dev/pampa/mycloudgallery/core/nas/integration"),
    ).first { it.isDirectory }
    val sources = dir.listFiles { file -> file.extension == "kt" }.orEmpty()
    assertThat(sources.size).isAtLeast(5)
    val offenders = sources.filter { file ->
      file.readLines().any { line -> line.trimStart().startsWith("import") && line.contains("com.hierynomus") }
    }.map { it.name }
    assertThat(offenders).isEmpty()
  }
}
