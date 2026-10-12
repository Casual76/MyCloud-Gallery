package dev.pampa.mycloudgallery.core.nas.integration

import com.google.common.truth.Truth.assertThat
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.fs.SmbShareProbe
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.SecureRandom
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * La suite autenticata contro il NAS vero (§5.2). Solo con `-PnasIntegration` E con il file delle credenziali
 * scritto dall'utente (`%USERPROFILE%\.mycloudgallery\nas-it.properties`: `user`, `password`, `share` di prova;
 * facoltativi `host`, `guid`, `share2`, `m9.consent=yes`). Senza file si salta in modo pulito (Assume).
 *
 * Scrive SOLO nella share di prova, sotto `.mycloudgallery/it/<giro>/` e `.mycloudgallery/tmp/`, e alla fine
 * cancella cio' che ha creato (se `.mycloudgallery` non esisteva prima, la toglie del tutto).
 */
@NasIntegration
class NasIntegrationSuiteTest {

  private lateinit var credentials: ItCredentials
  private lateinit var scope: CoroutineScope
  private lateinit var manager: SmbNasSessionManager
  private lateinit var breakers: AuthBreakerRegistry
  private lateinit var gate: FakeNasRouteGate
  private lateinit var account: NasAccount
  private var appDirExisted = true
  private var appDir2Existed = true
  private var rawAccessAllowed = true
  private val runId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
  private val deviceId = "itest" + runId.take(16).padEnd(21, '0')
  private val clock = MonotonicClock { System.nanoTime() / 1_000_000 }
  private val report = ArrayList<String>()

  @Before
  fun setUp() {
    credentials = NasIntegrationConfig.requireCredentials()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, TimedDirectBinding()) }
    // Un solo interruttore per tutta la suite: una password sbagliata nel file costa UN tentativo, non uno per test.
    breakers = AuthBreakerRegistry(SHARED_STORE, scope)
    val guid = credentials.guid ?: runBlocking {
      // Primo contatto come l'onboarding: GUID dalla NEGOTIATE, senza autenticare.
      SmbConnectionTester(gate, breakers, clock = clock)
        .run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE))
        .negotiation?.serverGuid
    }
    account = NasAccount(NasAddress(credentials.host), credentials.user, "it", guid)
    manager = SmbNasSessionManager(
      account = { account },
      routeGate = gate,
      credentials = { CredentialLoad.Found(NasCredentials(credentials.user, credentials.password)) },
      breakers = breakers,
      scope = scope,
      transport = SmbjTransport(),
      clock = clock,
      deviceId = { deviceId },
    )
    // Prima di qualunque accesso diretto (che non passa dall'interruttore) si verifica la password con la sessione.
    val check = runBlocking { manager.withShare(credentials.share, Purpose.INTERACTIVE) { } }
    if (check is NasResult.Err) {
      rawAccessAllowed = false
      throw AssertionError("la sessione di prova non parte: ${check.error} (nessun altro tentativo in questa suite)")
    }
    appDirExisted = withRawShare { it.folderExists(TrustFormat.ROOT_DIR) }
    appDir2Existed = credentials.share2?.let { second -> withRawShare(second) { it.folderExists(TrustFormat.ROOT_DIR) } } ?: true
  }

  @After
  fun tearDown() {
    if (!::manager.isInitialized) return
    runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    if (rawAccessAllowed) {
      for ((shareName, existed) in listOfNotNull(credentials.share to appDirExisted, credentials.share2?.let { it to appDir2Existed })) {
        withRawShare(shareName) { share ->
          val runDir = "${TrustFormat.ROOT_DIR}\\it\\$runId"
          if (share.folderExists(runDir)) share.rmdir(runDir, true)
          if (!existed && share.folderExists(TrustFormat.ROOT_DIR)) share.rmdir(TrustFormat.ROOT_DIR, true)
        }
      }
    }
    scope.cancel()
    if (report.isNotEmpty()) {
      val text = report.joinToString("\n")
      println("=== Integrazione NAS ===\n$text")
      NasIntegrationConfig.reportFile("integrazione.txt").appendText(text + "\n")
    }
  }

  /**
   * Accesso diretto con smbj, solo per preparare e pulire la cartella del giro, e solo dopo che la sessione (passata
   * dall'interruttore) ha verificato la password.
   */
  private fun <T> withRawShare(block: (DiskShare) -> T): T = withRawShare(credentials.share, block)

  private fun <T> withRawShare(shareName: String, block: (DiskShare) -> T): T {
    check(rawAccessAllowed) { "accesso diretto senza password verificata" }
    val config = SmbConfig.builder().withDialects(SMB2Dialect.SMB_3_1_1).withSigningRequired(true).withEncryptData(true).build()
    SMBClient(config).use { client ->
      client.connect(credentials.host).use { connection ->
        val session = connection.authenticate(AuthenticationContext(credentials.user, credentials.password.copyOf(), ""))
        session.use { (it.connectShare(shareName) as DiskShare).use { share -> return block(share) } }
      }
    }
  }

  private fun path(vararg names: String) = SharePath.of(TrustFormat.ROOT_DIR, "it", runId, *names)

  @Test
  fun sessioneCifrataEGuidStabileDopoLaRiconnessione() = runBlocking {
    manager.withShare(credentials.share, Purpose.INTERACTIVE) { }.also { assertThat(it).isInstanceOf(NasResult.Ok::class.java) }
    val ready = manager.state.value as NasConnState.Ready
    report += "sessione: ${ready.info.dialect}, cifrata=${ready.info.encrypted}, cifrario=${ready.info.cipher}, firma=${ready.info.signingRequired}"
    assertThat(ready.info.dialect).isEqualTo(SmbDialect.SMB_3_1_1)
    assertThat(ready.info.encrypted).isTrue()
    val guid = ready.info.serverGuid
    manager.invalidate(InvalidateReason.USER_REQUEST)
    manager.withShare(credentials.share, Purpose.INTERACTIVE) { }
    assertThat((manager.state.value as NasConnState.Ready).info.serverGuid).isEqualTo(guid)
    assertThat(manager.nasClock.nowMillisOrNull()).isNotNull()
  }

  @Test
  fun pubblicazioneEsclusivaRealeConCollisioneMaiuscoleELetturaConTetto() {
    val fs = manager.fileSystem(credentials.share)
    val bytes = ByteArray(200_000) { (it * 31).toByte() }
    assertThat(fs.publishExclusive(path("registro", "00000000000000000001"), bytes)).isEqualTo(PublishResult.CREATED)
    assertThat(fs.publishExclusive(path("registro", "00000000000000000001"), byteArrayOf(1))).isEqualTo(PublishResult.ALREADY_EXISTS)
    val shouted = SharePath.of(TrustFormat.ROOT_DIR.uppercase(Locale.ROOT), "IT", runId, "REGISTRO", "00000000000000000001")
    assertThat(fs.publishExclusive(shouted, byteArrayOf(2))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat((fs.read(path("registro", "00000000000000000001"), bytes.size) as NasRead.Data).bytes).isEqualTo(bytes)
    assertThat(fs.read(path("registro", "00000000000000000001"), bytes.size - 1)).isEqualTo(NasRead.TooLarge)
    assertThat(fs.read(path("registro", "manca"), 10)).isEqualTo(NasRead.NotFound)
    repeat(5) { fs.publishExclusive(path("elenco", "f$it"), byteArrayOf(it.toByte())) }
    val listing = fs.list(path("elenco"), 3)
    assertThat(listing.names).hasSize(3)
    assertThat(listing.truncated).isTrue()
    // Nessun temporaneo nostro rimasto.
    val tmp = fs.list(SharePath.of(TrustFormat.ROOT_DIR, "tmp"), 1000).names.filter { it.startsWith(deviceId) }
    assertThat(tmp).isEmpty()
    report += "publishExclusive reale: ok (collisione, maiuscole, tetto, elenco troncato)"
  }

  @Test
  fun provaDellaShareEPuliziaDeiTemporanei() = runBlocking {
    val access = SmbShareProbe(manager).probe(credentials.share)
    report += "ShareProbe(${"share di prova"}): $access"
    assertThat(access).isEqualTo(NasResult.Ok(ShareAccess.READ_WRITE))
    credentials.share2?.let { report += "ShareProbe(share2): ${SmbShareProbe(manager).probe(it)}" }
    // Un temporaneo "orfano" nostro (processo morto) e uno di un altro dispositivo: si cancella solo il nostro.
    val mine = "$deviceId-" + "ab".repeat(16)
    val other = "zzzzzzzzzzzzzzzzzzzzzzzzzz-" + "ab".repeat(16)
    withRawShare { share ->
      for (name in listOf(mine, other)) {
        val dir = "${TrustFormat.ROOT_DIR}\\tmp"
        if (!share.folderExists(TrustFormat.ROOT_DIR)) share.mkdir(TrustFormat.ROOT_DIR)
        if (!share.folderExists(dir)) share.mkdir(dir)
        share.openFile(
          "$dir\\$name",
          java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE),
          java.util.EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
          com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
          com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE,
          java.util.EnumSet.of(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).close()
      }
    }
    // L'eta' si misura con l'ora del NAS: due secondi bastano a superare l'errore dell'ancora (ms).
    Thread.sleep(2_000)
    val purged = SmbNasHousekeeping(manager) { listOf(credentials.share) }.purgeOwnStaleTemps(deviceId, 0.minutes)
    assertThat(purged).isEqualTo(NasResult.Ok(1))
    withRawShare { share ->
      val dir = "${TrustFormat.ROOT_DIR}\\tmp"
      assertThat(share.fileExists("$dir\\$mine")).isFalse()
      assertThat(share.fileExists("$dir\\$other")).isTrue()
      share.rm("$dir\\$other")
    }
    report += "pulizia dei temporanei: solo i propri"
  }

  @Test
  fun copiaLatoServerTraShare() = runBlocking {
    val second = credentials.share2
    org.junit.Assume.assumeTrue("share2 non indicata: copia tra share saltata", second != null)
    val bytes = ByteArray(5 * 1024 * 1024 + 3) { (it % 253).toByte() }
    assertThat(manager.fileSystem(credentials.share).publishExclusive(path("sorgente"), bytes)).isEqualTo(PublishResult.CREATED)
    val started = System.nanoTime()
    val copied = SmbServerSideCopy(manager).copy(credentials.share, path("sorgente"), checkNotNull(second), path("copia"))
    val ms = (System.nanoTime() - started) / 1_000_000.0
    assertThat(copied).isEqualTo(NasResult.Ok(bytes.size.toLong()))
    assertThat((manager.fileSystem(second).read(path("copia"), bytes.size) as NasRead.Data).bytes).isEqualTo(bytes)
    report += String.format(Locale.ROOT, "copia lato server tra share: %d byte in %.0f ms", bytes.size, ms)
  }

  /** M9: UN solo tentativo con la password sbagliata, solo con il consenso scritto nel file (`m9.consent=yes`). */
  @Test
  fun passwordSbagliataUnSoloTentativo() = runBlocking {
    org.junit.Assume.assumeTrue("M9 senza consenso esplicito: saltato", credentials.wrongPasswordConsent)
    val isolated = AuthBreakerRegistry(InMemoryBreakerStore(), scope)
    val tester = SmbConnectionTester(gate, isolated, clock = clock)
    val target = TestTarget(NasAddress(credentials.host), credentials.user, credentials.share, account.pinnedGuid)
    val wrong = "sbagliata-${runId}".toCharArray()
    val first = tester.run(target, NasCredentials(credentials.user, wrong))
    val second = tester.run(target, NasCredentials(credentials.user, wrong))
    val firstAuth = first.stages.first { it.stage == Stage.AUTHENTICATE }
    report += "M9: codice ${firstAuth.error}; secondo giro ${second.stages.first { it.stage == Stage.AUTHENTICATE }.outcome}"
    assertThat(firstAuth.error).isEqualTo(NasError.Auth(AuthBlockReason.AUTH_FAILED))
    assertThat(second.stages.first { it.stage == Stage.AUTHENTICATE }.outcome).isEqualTo(StageOutcome.SKIPPED)
  }

  private companion object {
    /** L'interruttore condiviso da tutti i test della suite (vive quanto la JVM dei test). */
    val SHARED_STORE = InMemoryBreakerStore()
  }
}
