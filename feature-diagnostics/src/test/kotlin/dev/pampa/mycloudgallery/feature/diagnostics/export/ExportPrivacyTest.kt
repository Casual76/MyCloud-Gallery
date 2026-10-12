package dev.pampa.mycloudgallery.feature.diagnostics.export

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeConnectionTester
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.feature.diagnostics.ContentResolverExportSink
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsViewModel
import dev.pampa.mycloudgallery.feature.diagnostics.FakeContributor
import dev.pampa.mycloudgallery.feature.diagnostics.SaveResult
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInput
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import dev.pampa.mycloudgallery.feature.diagnostics.item
import dev.pampa.mycloudgallery.feature.diagnostics.testHasher
import java.io.File
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * **L'esportazione non contiene dati personali** (docs/fase2-progetto.md §2.6, prova F12 del §5.4).
 *
 * Qui si inseriscono, in ogni punto in cui un dato personale potrebbe entrare (il testo che sta a schermo, i valori
 * esportabili, gli eventi, il test di connessione, l'indirizzo e le credenziali), nomi, IP, utenti, percorsi, il
 * nome di una rete e una password. Poi si percorre tutta la strada vera, dalla lettura al file scritto su disco, e si
 * verifica che **nessuno** di quei dati compaia nel file, nemmeno a pezzi.
 *
 * Le impronte (`DiagHasher`) invece ci sono: dicono «stesso account di prima» senza dire quale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportPrivacyTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() = Dispatchers.setMain(testDispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private object Personal {
    const val USER = "mario.rossi"
    const val DEVICE = "Telefono di Mario"
    const val IP = "192.168.178.58"
    const val ULA = "fd12:3456:789a::1"
    const val SHARE_PATH = "/Backup/Telefono di Mario/IMG_20251012_083000.jpg"
    const val SHARE = "famiglia-rossi"
    const val SSID = "CasaRossi-5G"
    const val NAS_NAME = "WDMyCloud-EX2-di-casa"
    const val PASSWORD = "Sup3rSegreta!2026"
    const val GUID = "6f1c1a7e-0b3c-4a52-9a9e-aa11bb22cc33"

    /** Ogni dato, piu' i frammenti con cui qualcuno potrebbe riconoscerlo. */
    val all = listOf(
      USER, "mario", "rossi", DEVICE, "Telefono", IP, "192.168", "178.58", ULA, "fd12", SHARE_PATH, "IMG_2025", "/Backup",
      SHARE, "famiglia", SSID, "CasaRossi", NAS_NAME, "WDMyCloud", PASSWORD, "Sup3r", GUID, "6f1c1a7e",
    )
  }

  /** Voci piene di dati personali in ogni canale: testo a schermo, impronte, dettagli. */
  private fun personalItems(): List<DiagItem> = listOf(
    item(
      code = DiagCodes.NAS_AUTH_FAILED,
      severity = DiagSeverity.ERROR,
      // Il testo a schermo PUO' contenere dati personali ("GUID memorizzato: ...", sul proprio telefono va bene):
      // non entra mai nell'esportazione.
      detail = DiagText(
        dev.pampa.mycloudgallery.feature.diagnostics.R.string.diagnostics_passthrough,
        listOf("${Personal.USER} su ${Personal.NAS_NAME} (${Personal.IP}) in ${Personal.SSID}"),
      ),
      export = mapOf(
        "account" to testHasher.hash(Personal.USER),
        "device" to testHasher.hash(Personal.DEVICE),
        "ip" to testHasher.hash(Personal.IP),
        "ula" to testHasher.hash(Personal.ULA),
        "share" to testHasher.hash(Personal.SHARE),
        "attempts" to ExportValue.Num(1),
      ),
    ),
    item(
      code = DiagCodes.NAS_SHARE_MISSING,
      severity = DiagSeverity.WARNING,
      detail = DiagText(
        dev.pampa.mycloudgallery.feature.diagnostics.R.string.diagnostics_passthrough,
        listOf(Personal.SHARE_PATH),
      ),
      export = mapOf("path" to testHasher.hash(Personal.SHARE_PATH)),
    ),
  )

  private val nasTarget = TestTarget(
    address = NasAddress(Personal.IP, Personal.NAS_NAME),
    user = Personal.USER,
    share = Personal.SHARE,
    expectedGuid = Personal.GUID,
  )

  private fun viewModel(
    log: RingDiagnosticsLog = RingDiagnosticsLog(capacity = 20),
    contributors: Set<dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor> = emptySet(),
    report: ConnectionReport = ConnectionReport(emptyList(), null),
  ): DiagnosticsViewModel = DiagnosticsViewModel(
    contributors = contributors,
    log = log,
    connectionTester = FakeConnectionTester(report),
    breaker = FakeAuthCircuitBreaker(),
    credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(Personal.USER, Personal.PASSWORD.toCharArray())) },
    inputSource = Optional.of(ConnectionTestInputSource { ConnectionTestInput(nasTarget, credentialSlot = "slot") }),
    vpn = FakeVpnController(),
  )

  private fun assertNoPersonalData(text: String) {
    Personal.all.forEach { forbidden ->
      assertWithMessage("«$forbidden» e' finito nell'esportazione").that(text.contains(forbidden, ignoreCase = true)).isFalse()
    }
  }

  @Test
  fun nomiIpUtentiEPercorsiNonCompaionoNelFileEsportato() = runTest(testDispatcher) {
    val log = RingDiagnosticsLog(capacity = 20)
    // Eventi dell'anello: solo codici, numeri e impronte; ma le impronte vengono da dati personali.
    log.record(
      DiagEvent(
        at = 1_000L,
        code = DiagCode("NAS_AUTH_FAILED"),
        severity = DiagSeverity.ERROR,
        values = mapOf("account" to testHasher.hash(Personal.USER), "ip" to testHasher.hash(Personal.IP), "count" to ExportValue.Num(3)),
      ),
    )
    val failedReport = ConnectionReport(
      stages = listOf(
        StageResult(Stage.ROUTE, StageOutcome.PASSED, 12.milliseconds),
        StageResult(Stage.TCP, StageOutcome.PASSED, 30.milliseconds),
        StageResult(Stage.NEGOTIATE, StageOutcome.PASSED, 80.milliseconds),
        StageResult(Stage.GUID, StageOutcome.PASSED, 1.milliseconds),
        StageResult(Stage.AUTHENTICATE, StageOutcome.PASSED, 120.milliseconds),
        StageResult(Stage.SECURITY_CHECK, StageOutcome.PASSED, 2.milliseconds),
        // Il messaggio di questo errore, sullo schermo, contiene il nome della share.
        StageResult(Stage.SHARE, StageOutcome.FAILED, 40.milliseconds, NasError.ShareMissing(Personal.SHARE)),
      ),
      negotiation = NegotiationFacts(SmbDialect.SMB_3_1_1, encryptionOffered = true, serverGuid = Personal.GUID, timeOffsetMs = 1_200),
    )
    val viewModel = viewModel(
      log = log,
      contributors = setOf(
        FakeContributor(DiagSection.NETWORK_AND_NAS) { personalItems() },
        RecentEventsContributor(log, nowMillis = { 61_000L }),
      ),
      report = failedReport,
    )
    advanceUntilIdle()
    viewModel.runConnectionTest()
    advanceUntilIdle()

    val state = viewModel.state.value
    val exported = checkNotNull(state.export).text

    // La strada vera: il testo dell'anteprima viene scritto su un file attraverso il ContentResolver.
    val file = File.createTempFile("diagnostica", ".json").apply { deleteOnExit() }
    viewModel.saveExport(Uri.fromFile(file), ContentResolverExportSink(RuntimeEnvironment.getApplication().contentResolver, dispatchers()))
    advanceUntilIdle()

    assertThat(viewModel.state.value.saveResult).isEqualTo(SaveResult.SAVED)
    val onDisk = file.readText(Charsets.UTF_8)
    assertThat(onDisk).isEqualTo(exported) // quel che si vede nell'anteprima e' quel che si salva
    assertNoPersonalData(onDisk)
    assertThat(DiagnosticsExporter.conforms(onDisk)).isTrue()

    // Le impronte, invece, ci sono: sono la prova che i dati sono passati di qui e se ne sono andati cifrati.
    assertThat(Regex("#[0-9a-f]{12}").findAll(onDisk).count()).isAtLeast(5)
    listOf(Personal.USER, Personal.IP, Personal.SHARE, Personal.ULA).forEach { plain ->
      assertThat(onDisk).contains("#" + testHasher.hash(plain).hex)
    }
    // E il test di connessione c'e', con i suoi numeri.
    assertThat(onDisk).contains("CONNECTION_TEST")
    assertThat(onDisk).contains("\"stage\": \"Stage.SHARE\"")
    assertThat(onDisk).contains("\"error\": \"NasErrorKind.SHARE_MISSING\"")
  }

  @Test
  fun ilTestoASchermoNonEntraMaiNellEsportazione() {
    val secrets = (1..200).map { index -> "Persona${index}_${Personal.USER}_$index@10.0.$index.7" }
    val items = secrets.map { secret ->
      item(
        DiagCodes.NET_PLACE,
        detail = DiagText(dev.pampa.mycloudgallery.feature.diagnostics.R.string.diagnostics_passthrough, listOf(secret)),
        export = mapOf("n" to ExportValue.Num(1)),
      )
    }

    val text = DiagnosticsExporter.render(
      dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot(
        mapOf(DiagSection.NETWORK_AND_NAS to items),
        recentEvents = 0,
      ),
    ).text

    secrets.forEach { assertThat(text).doesNotContain(it) }
    assertThat(text).doesNotContain("Persona")
    assertThat(text).doesNotContain("10.0.")
  }

  @Test
  fun lImprontaNonContieneIlDatoELaChiaveCambiaIlRisultato() {
    val a = testHasher.hash(Personal.USER)
    val b = dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher(ByteArray(32) { (it + 1).toByte() }).hash(Personal.USER)

    assertThat(a).isNotEqualTo(b)
    Personal.all.forEach { assertThat(a.hex.contains(it, ignoreCase = true)).isFalse() }
    assertThat(a.toString()).doesNotContain("mario")
  }

  @Test
  fun unFileDegliEventiManomessoNonFaEntrareTestoLibero() = runTest(testDispatcher) {
    val store = dev.pampa.mycloudgallery.core.common.diagnostics.FileDiagnosticsLogStore(
      File.createTempFile("events", ".log").apply {
        deleteOnExit()
        writeText(
          "1|NAS_TIMEOUT|WARNING|count=n2\n" +
            "2|NAS_TIMEOUT|WARNING|owner=eMario.Rossi\n" +
            "3|NAS_TIMEOUT|WARNING|name=Mario Rossi\n" +
            "4|NAS_TIMEOUT|WARNING|ip=e${Personal.IP}\n",
        )
      },
    )
    val log = RingDiagnosticsLog(capacity = 10, store = store)

    val viewModel = viewModel(log = log, contributors = setOf(RecentEventsContributor(log, nowMillis = { 10_000L })))
    advanceUntilIdle()

    val text = checkNotNull(viewModel.state.value.export).text
    assertNoPersonalData(text)
    assertThat(text).contains("\"count\": 2")
  }

  private fun dispatchers() = object : dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider {
    override val main = testDispatcher
    override val io = testDispatcher
    override val default = testDispatcher
  }
}
