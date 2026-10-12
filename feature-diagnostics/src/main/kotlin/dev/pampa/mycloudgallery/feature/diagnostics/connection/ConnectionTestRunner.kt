package dev.pampa.mycloudgallery.feature.diagnostics.connection

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagMapping
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cosa provare nel test di connessione: l'account NAS di questo dispositivo (indirizzo, utente, GUID memorizzato)
 * e dove sta la sua password nel `CredentialVault`. Lo fornisce `:app` (P9), che conosce l'account; la
 * Diagnostica non lo memorizza.
 *
 * @property credentialSlot la voce del vault con la password; null = prova senza autenticare (solo le fasi che non
 *   ne hanno bisogno).
 */
class ConnectionTestInput(val target: TestTarget, val credentialSlot: String?) {
  override fun toString(): String = "ConnectionTestInput(${target.address})"
}

fun interface ConnectionTestInputSource {
  /** L'account da provare, o null se questo dispositivo non ha ancora un NAS configurato. */
  suspend fun current(): ConnectionTestInput?
}

/** Una riga del test: la fase, come e' andata, quanto ci ha messo e, se fallita, l'errore col suo messaggio. */
class StageRow(
  val stage: Stage,
  val outcome: StageOutcome,
  val elapsed: Duration,
  val error: NasError?,
) {
  @get:StringRes
  val labelRes: Int get() = stageLabel(stage)

  val message: DiagText? get() = error?.let(DiagMapping::message)
}

/** Cosa ha risposto il NAS alla NEGOTIATE (senza autenticare), per lo schermo. */
class NegotiationSummary(val dialect: SmbDialect, val encryptionOffered: Boolean, val clockOffsetMs: Long)

/** Come e' andato il test, per lo schermo e per l'esportazione. */
sealed interface ConnectionTestOutcome {

  /** Nessun NAS configurato: niente da provare. */
  data object NotConfigured : ConnectionTestOutcome

  /**
   * Il test e' girato.
   *
   * @property authSkipped l'autenticazione non e' stata provata: l'interruttore era aperto o la password non si
   *   legge. Il test non autentica mai in quei casi, per non far bloccare l'account (C5, E-S12).
   * @property breakerOpen l'interruttore di sicurezza era aperto quando e' stato lanciato.
   */
  class Done(
    val rows: List<StageRow>,
    val negotiation: NegotiationSummary?,
    val passed: Boolean,
    val authSkipped: Boolean,
    val breakerOpen: Boolean,
  ) : ConnectionTestOutcome {
    val firstFailure: StageRow? get() = rows.firstOrNull { it.outcome == StageOutcome.FAILED }

    override fun toString(): String = "Done(${rows.size} fasi, passed=$passed)"
  }

  /** Il test non si e' chiuso entro il tempo concesso (la rete e' muta e il timeout del NAS non e' scattato). */
  data object TimedOut : ConnectionTestOutcome
}

/**
 * Lancia il test di connessione per fasi (docs/fase2-progetto.md §2.6) con le sue protezioni:
 *
 *  - **mai con l'interruttore aperto**: se `AuthCircuitBreaker` e' `Open` non si caricano nemmeno le credenziali,
 *    il tester riceve `null` e salta l'autenticazione. Anche `ConnectionTester` lo fa (P1), ma qui non ci si
 *    affida a un solo controllo: un tentativo in piu' puo' far bloccare l'account del NAS (C5);
 *  - **un solo tentativo**: l'unico percorso che autentica e' quello con le credenziali del vault; una password non
 *    leggibile (`NeedsPassword`, `Absent`, `Unavailable`) salta l'autenticazione, non la riprova;
 *  - le credenziali si chiudono (azzerano) appena il test finisce, in ogni caso.
 *
 * Il test e' sempre a richiesta dell'utente (E-R15): la Diagnostica non lo lancia da sola.
 */
class ConnectionTestRunner(
  private val tester: ConnectionTester,
  private val breaker: AuthCircuitBreaker,
  private val credentials: NasCredentialSource,
  private val input: ConnectionTestInputSource?,
  private val timeout: Duration = DEFAULT_TIMEOUT,
) {

  suspend fun run(): ConnectionTestOutcome {
    val toTest = input?.current() ?: return ConnectionTestOutcome.NotConfigured
    val breakerOpen = breaker.state.value is BreakerState.Open

    val loaded: NasCredentials? = if (breakerOpen || toTest.credentialSlot == null) null else loadCredentials(toTest.credentialSlot)
    return try {
      val report = withTimeoutOrNull(timeout) { tester.run(toTest.target, loaded, Stage.entries.toSet()) }
        ?: return ConnectionTestOutcome.TimedOut
      summarize(report, authAttempted = loaded != null, breakerOpen = breakerOpen)
    } finally {
      loaded?.close()
    }
  }

  private suspend fun loadCredentials(slot: String): NasCredentials? = try {
    (credentials.load(slot) as? CredentialLoad.Found)?.credentials
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  private fun summarize(report: ConnectionReport, authAttempted: Boolean, breakerOpen: Boolean): ConnectionTestOutcome.Done {
    val rows = report.stages.map { StageRow(it.stage, it.outcome, it.elapsed, it.error) }
    // «Non provata» vale se non abbiamo passato la password (interruttore aperto, password illeggibile) o se il tester
    // l'ha saltata. Una fase caduta prima dell'autenticazione non e' un'autenticazione saltata: e' il test che si ferma.
    val authStage = report.stages.firstOrNull { it.stage == Stage.AUTHENTICATE }
    val authSkipped = !authAttempted || authStage?.outcome == StageOutcome.SKIPPED
    return ConnectionTestOutcome.Done(
      rows = rows,
      negotiation = report.negotiation?.let { NegotiationSummary(it.dialect, it.encryptionOffered, it.timeOffsetMs) },
      passed = report.passed && !authSkipped,
      authSkipped = authSkipped,
      breakerOpen = breakerOpen,
    )
  }

  companion object {
    val DEFAULT_TIMEOUT: Duration = 90.seconds
  }
}

/** «SMB 3.1.1» invece di `SMB_3_1_1`; un dialetto che non si riconosce resta «SMB ?». */
internal fun dialectLabel(dialect: SmbDialect): String =
  if (dialect == SmbDialect.UNKNOWN) "SMB ?" else "SMB " + dialect.name.removePrefix("SMB_").replace('_', '.')

/** L'etichetta di una fase, per lo schermo. */
@StringRes
fun stageLabel(stage: Stage): Int = when (stage) {
  Stage.ROUTE -> R.string.diagnostics_stage_route
  Stage.TCP -> R.string.diagnostics_stage_tcp
  Stage.NEGOTIATE -> R.string.diagnostics_stage_negotiate
  Stage.GUID -> R.string.diagnostics_stage_guid
  Stage.AUTHENTICATE -> R.string.diagnostics_stage_authenticate
  Stage.SECURITY_CHECK -> R.string.diagnostics_stage_security
  Stage.SHARE -> R.string.diagnostics_stage_share
  Stage.READ -> R.string.diagnostics_stage_read
}

/**
 * L'esito del test come voci della sezione «Test di connessione»: una per fase (con esito e tempo), le cose lette
 * dalla NEGOTIATE e il verdetto. Sono numeri e nomi di costanti: la fase fallita porta il tipo dell'errore e basta,
 * niente messaggio, niente nome di share.
 */
internal fun ConnectionTestOutcome.toItems(): List<DiagItem> = when (this) {
  ConnectionTestOutcome.NotConfigured -> listOf(plain(DiagCodes.TEST_NOT_CONFIGURED, DiagSeverity.INFO))
  ConnectionTestOutcome.TimedOut -> listOf(
    plain(DiagCodes.TEST_FAILED, DiagSeverity.ERROR, "timedOut" to ExportValue.Flag(true)),
  )
  is ConnectionTestOutcome.Done -> buildList {
    rows.forEach { row ->
      add(
        DiagItem(
          code = row.error?.let(DiagMapping::code) ?: DiagCodes.TEST_STAGE,
          section = DiagSection.CONNECTION_TEST,
          severity = when (row.outcome) {
            StageOutcome.FAILED -> DiagSeverity.ERROR
            StageOutcome.PASSED -> DiagSeverity.OK
            StageOutcome.SKIPPED, StageOutcome.NOT_RUN -> DiagSeverity.INFO
          },
          titleRes = stageLabel(row.stage),
          detail = row.message ?: when (row.outcome) {
            StageOutcome.SKIPPED -> DiagText(R.string.diagnostics_stage_skipped)
            StageOutcome.NOT_RUN -> DiagText(R.string.diagnostics_stage_not_run)
            StageOutcome.PASSED, StageOutcome.FAILED -> null
          },
          export = buildMap {
            put("stage", ExportValue.Enum.of(row.stage))
            put("outcome", ExportValue.Enum.of(row.outcome))
            put("elapsed", ExportValue.Dur.of(row.elapsed))
            row.error?.let { put("error", ExportValue.Enum.of(DiagMapping.kind(it))) }
            ((row.error as? NasError.Unexpected)?.ntStatus)?.let { put("ntStatus", ExportValue.Num(it)) }
          },
        ),
      )
    }
    negotiation?.let {
      add(
        DiagItem(
          code = DiagCodes.TEST_STAGE,
          section = DiagSection.CONNECTION_TEST,
          severity = if (it.encryptionOffered) DiagSeverity.OK else DiagSeverity.ERROR,
          titleRes = R.string.diagnostics_test_negotiation,
          detail = DiagText(
            if (it.encryptionOffered) R.string.diagnostics_test_negotiation_encrypted else R.string.diagnostics_test_negotiation_plain,
            listOf(dialectLabel(it.dialect)),
          ),
          export = mapOf(
            "dialect" to ExportValue.Enum.of(it.dialect),
            "encryptionOffered" to ExportValue.Flag(it.encryptionOffered),
            "clockOffsetMs" to ExportValue.Num(it.clockOffsetMs),
          ),
        ),
      )
    }
    when {
      firstFailure != null -> add(plain(DiagCodes.TEST_FAILED, DiagSeverity.ERROR))
      authSkipped -> add(
        plain(
          DiagCodes.TEST_AUTH_SKIPPED,
          DiagSeverity.WARNING,
          "breakerOpen" to ExportValue.Flag(breakerOpen),
        ),
      )
      passed -> add(plain(DiagCodes.TEST_PASSED, DiagSeverity.OK))
      else -> add(plain(DiagCodes.TEST_FAILED, DiagSeverity.ERROR))
    }
  }
}

private fun plain(code: DiagCode, severity: DiagSeverity, vararg values: Pair<String, ExportValue>) = DiagItem(
  code = code,
  section = DiagSection.CONNECTION_TEST,
  severity = severity,
  titleRes = DiagCatalog.titleOf(code),
  export = values.toMap(),
)
