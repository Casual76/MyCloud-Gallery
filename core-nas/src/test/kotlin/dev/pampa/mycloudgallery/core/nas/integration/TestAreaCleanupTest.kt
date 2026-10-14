package dev.pampa.mycloudgallery.core.nas.integration

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.DEVICE
import dev.pampa.mycloudgallery.core.nas.GOOD_PASSWORD
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import java.util.Locale
import java.util.Properties
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pulizia finale dell'area di prova ([TestAreaCleanup]) **senza NAS**, sul simulatore con due account: `test` (share
 * `test`), `test2` (share `test2`), entrambi su `test-comune`. Gira sempre, non solo con `-PnasIntegration`.
 */
class TestAreaCleanupTest {

  private val run = "20261014-120000-0a1b2c3d"
  private val previous = "20261014-142451-aaaaaaaa"
  private val older = "20261013-101010-bbbbbbbb"

  /** Come il file delle credenziali vero: `test` con `test`, `test-comune` e `test2` (privata di `test2`). */
  private fun credentials(user2: Boolean = true): ItCredentials {
    val properties = Properties().apply {
      setProperty("user", "test")
      setProperty("password", "x")
      setProperty("share", "test")
      setProperty("share2", "test-comune")
      if (user2) {
        setProperty("user2", "test2")
        setProperty("password2", "y")
        setProperty("privateShare2", "test2")
      }
    }
    return checkNotNull(ItCredentials.parse(properties))
  }

  /**
   * Il trasporto che nega a [denied] la cancellazione di cio' che sta sotto una cartella `propria-di-test2` (come una
   * cartella creata da `test2` in `test-comune` che `test` non puo' togliere). Il resto lo ammette.
   */
  private class OwnerTransport(private val delegate: SmbTransport, private val denied: String) : SmbTransport {
    override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection =
      Connection(delegate.connect(binding, address, connectTimeout))

    private inner class Connection(private val inner: SmbConnection) : SmbConnection by inner {
      override fun authenticate(user: String, password: CharArray): SmbSession = Session(user, inner.authenticate(user, password))
    }

    private inner class Session(private val user: String, private val inner: SmbSession) : SmbSession by inner {
      override fun connectShare(name: String): SmbShare = Share(user, inner.connectShare(name))
    }

    private inner class Share(private val user: String, private val inner: SmbShare) : SmbShare by inner {
      private fun check(path: String) {
        if (user == denied && path.contains("propria-di-test2", ignoreCase = true)) throw SmbStatusException(NtStatus.ACCESS_DENIED)
      }

      override fun deleteFile(path: String) {
        check(path)
        inner.deleteFile(path)
      }

      override fun removeEmptyDirectory(path: String) {
        check(path)
        inner.removeEmptyDirectory(path)
      }
    }
  }

  private class Rig(
    val backend: InMemorySmbBackend,
    val guard: TestAreaGuard,
    val test: TestAreaCleanup.Account,
    val test2: TestAreaCleanup.Account,
    val log: MutableList<String>,
  ) {
    fun cleanup(vararg accounts: TestAreaCleanup.Account, strict: Boolean = accounts.size > 1) =
      TestAreaCleanup(guard, accounts.toList(), strict) { log += it }
  }

  private fun TestScope.rig(): Rig {
    val backend = InMemorySmbBackend().apply {
      addUser("test", GOOD_PASSWORD)
      addUser("test2", GOOD_PASSWORD)
      addShare("test", mapOf("test" to SimAccess.READ_WRITE))
      addShare("test2", mapOf("test2" to SimAccess.READ_WRITE))
      addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE, "test2" to SimAccess.READ_WRITE))
    }
    val guard = TestAreaGuard.of(credentials(), run)
    val transport = TestAreaTransport(OwnerTransport(backend, denied = "test"), guard)
    val h = Harness(backgroundScope, virtualClock(), backend = backend)
    val clock = virtualClock()

    fun account(user: String) = TestAreaCleanup.Account(
      user,
      SmbNasSessionManager(
        account = { NasAccount(NasAddress("192.168.178.58"), user, "slot-$user", backend.serverGuid) },
        routeGate = h.gate,
        credentials = { CredentialLoad.Found(NasCredentials(user, GOOD_PASSWORD.toCharArray())) },
        breakers = h.breakers,
        scope = backgroundScope,
        transport = transport,
        clock = clock,
        io = Dispatchers.Unconfined,
        deviceId = { DEVICE },
        backoff = { },
      ),
    )
    return Rig(backend, guard, account("test"), account("test2"), ArrayList())
  }

  /** Quello che lasciano i giri interrotti e il giro in corso, nelle tre share di prova. */
  private fun InMemorySmbBackend.leftovers() {
    // Il giro in corso: nella share di `test` (elenco a lotti, file grande) e, per la copia, in `test-comune`.
    for (i in 0 until 250) put("test", "mcg-it/$run/elenco/IMG_%05d.jpg".format(Locale.ROOT, i), byteArrayOf(1))
    put("test", "mcg-it/$run/letture/grande.bin", ByteArray(64))
    put("test", "mcg-it/$run/.mycloudgallery/registro/00000000000000000001", byteArrayOf(2))
    put("test-comune", "mcg-it/$run/copia/grande.bin", ByteArray(64))
    // Un giro rimasto a meta' (quello del guasto reale) in ogni share, anche nella privata dell'altro account.
    put("test", "mcg-it/$previous/letture/grande.bin", ByteArray(8))
    put("test-comune", "mcg-it/$previous/grande.bin", ByteArray(8))
    put("test2", "mcg-it/$previous/grande.bin", ByteArray(8))
    // Cartelle create da `test2` in `test-comune`: `test` non puo' cancellarle.
    put("test-comune", "mcg-it/$older/propria-di-test2/x.bin", ByteArray(8))
    put("test-comune", "mcg-it/$older/y.bin", ByteArray(8))
    // E un'area del giro corrente nella share privata di `test2`.
    put("test2", "mcg-it/$run/q.bin", ByteArray(8))
  }

  @Test
  fun ogniCartellaSiCancellaConLAccountChePuoELaRadiceVuotaSiToglie() = runTest {
    val rig = rig()
    rig.backend.leftovers()

    val cleanup = rig.cleanup(rig.test, rig.test2)
    val problems = cleanup.sweep(includeCurrent = true) + cleanup.verifyAndRemoveRoot()

    assertThat(problems).isEmpty()
    for (share in listOf("test", "test-comune", "test2")) assertThat(rig.backend.exists(share, "mcg-it")).isFalse()
    for (share in listOf("test", "test-comune", "test2")) assertThat(rig.backend.fileCount(share)).isEqualTo(0)
    // `test` non entra in `test2`: nessuna cancellazione fatta con l'account sbagliato, e quelle negate le ha fatte `test2`.
    assertThat(rig.log.filter { it.startsWith("pulizia test2:") && it.contains("con test ") }).isEmpty()
    assertThat(rig.log.any { it.contains("tolto in parte con test ") && it.contains("pulizia test-comune") }).isTrue()
    assertThat(rig.log.any { it.contains("pulizia test-comune") && it.contains("giro precedente $older tolto con test2") }).isTrue()
  }

  @Test
  fun ilGiroInCorsoNonSiTocaSenzaIncludeCurrent() = runTest {
    val rig = rig()
    rig.backend.leftovers()

    val cleanup = rig.cleanup(rig.test, rig.test2)
    assertThat(cleanup.sweep(includeCurrent = false)).isEmpty()

    assertThat(rig.backend.exists("test", "mcg-it/$run/letture/grande.bin")).isTrue()
    assertThat(rig.backend.exists("test-comune", "mcg-it/$run/copia/grande.bin")).isTrue()
    assertThat(rig.backend.exists("test2", "mcg-it/$run/q.bin")).isTrue()
    for (share in listOf("test", "test-comune", "test2")) assertThat(rig.backend.exists(share, "mcg-it/$previous")).isFalse()
    assertThat(rig.backend.exists("test-comune", "mcg-it/$older")).isFalse()
    // Il giro in corso e' un residuo per la verifica, che non lo toglie.
    val residue = cleanup.verifyAndRemoveRoot()
    assertThat(residue.joinToString()).contains(run)
    assertThat(rig.backend.exists("test", "mcg-it/$run/letture/grande.bin")).isTrue()
  }

  @Test
  fun unaVoceCheNonHaIlNomeDiUnGiroNonSiTocaEFaFallireLaVerifica() = runTest {
    val rig = rig()
    rig.backend.leftovers()
    rig.backend.put("test-comune", "mcg-it/altro/dati.bin", ByteArray(8))
    rig.backend.put("test", "mcg-it/20261014-142451-zzzzzzzz/x.bin", ByteArray(8))

    val cleanup = rig.cleanup(rig.test, rig.test2)
    val problems = cleanup.sweep(includeCurrent = true) + cleanup.verifyAndRemoveRoot()

    assertThat(problems.joinToString("; ")).contains("test-comune")
    assertThat(problems.joinToString("; ")).contains("altro")
    assertThat(problems.joinToString("; ")).contains("20261014-142451-zzzzzzzz")
    assertThat(rig.backend.exists("test-comune", "mcg-it/altro/dati.bin")).isTrue()
    assertThat(rig.backend.exists("test", "mcg-it/20261014-142451-zzzzzzzz/x.bin")).isTrue()
    // I giri veri sono andati; mcg-it resta dov'e' non vuota.
    assertThat(rig.backend.exists("test-comune", "mcg-it/$older")).isFalse()
    assertThat(rig.backend.exists("test", "mcg-it")).isTrue()
    assertThat(rig.backend.exists("test2", "mcg-it")).isFalse()
  }

  @Test
  fun unSoloAccountNonVedeLaShareDellAltroEnonEUnErroreSeNonELaVerificaRigorosa() = runTest {
    val rig = rig()
    rig.backend.leftovers()

    val single = rig.cleanup(rig.test, strict = false)
    assertThat(single.sweep(includeCurrent = true)).isEmpty()
    // La share privata di `test2` non e' stata toccata da `test` (non ci entra).
    assertThat(rig.backend.exists("test2", "mcg-it/$previous/grande.bin")).isTrue()
    assertThat(rig.backend.exists("test2", "mcg-it/$run/q.bin")).isTrue()
    // In `test-comune` resta la cartella di `test2` che solo lui toglie: `test` da solo non finisce, e la verifica lo dice.
    assertThat(rig.backend.exists("test-comune", "mcg-it/$older/propria-di-test2/x.bin")).isTrue()
    assertThat(rig.backend.exists("test-comune", "mcg-it/$older/y.bin")).isFalse()
    val residue = single.verifyAndRemoveRoot()
    assertThat(residue.joinToString("; ")).contains("test-comune")
    assertThat(residue.joinToString("; ")).contains(older)
    assertThat(residue.joinToString("; ")).doesNotContain("test2: ")

    // Con la verifica rigorosa e un solo account la share privata dell'altro e' "non verificabile".
    val strict = rig.cleanup(rig.test, strict = true).verifyAndRemoveRoot()
    assertThat(strict.joinToString("; ")).contains("test2: nessun account riesce a leggere mcg-it")
  }
}
