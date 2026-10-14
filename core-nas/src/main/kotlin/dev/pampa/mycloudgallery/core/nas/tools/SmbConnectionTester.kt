package dev.pampa.mycloudgallery.core.nas.tools

import dev.pampa.mycloudgallery.core.nas.breaker.AttemptResult
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.AuthOutcome
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.session.ElapsedRealtimeClock
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.ShareFences
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il test di connessione per fasi (Diagnostica e onboarding, §2.6): percorso → TCP → NEGOTIATE → GUID →
 * autenticazione → controllo di sicurezza → share → lettura, ognuna con il suo tempo.
 *
 * Usa una connessione **sua** (non quella del gestore) e la chiude alla fine. L'autenticazione passa dallo stesso
 * interruttore della sessione, con la chiave `(GUID del NAS, utente)`: con l'interruttore aperto la fase e'
 * saltata (mai un `authenticate`), senza credenziali il test si ferma prima. Le fasi non chieste si eseguono lo
 * stesso se servono a quelle chieste, ma non compaiono nel rapporto.
 *
 * **Recinzione D33** (R11 della revisione 4A): prima della fase della share [shareGuard] (di default quella del processo,
 * [ShareFences]) decide se l'account puo' toccare quella share; se no la fase fallisce con `PermissionDenied` senza
 * `TREE_CONNECT` ne' letture.
 */
class SmbConnectionTester(
  private val routeGate: NasRouteGate,
  private val breakers: AuthBreakerRegistry,
  private val transport: SmbTransport = SmbjTransport(),
  private val clock: MonotonicClock = ElapsedRealtimeClock,
  private val config: NasSessionConfig = NasSessionConfig(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val shareGuard: ShareGuard? = ShareFences.installed,
) : ConnectionTester {

  override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
    val tracker = Tracker(stages, clock)
    var connection: SmbConnection? = null
    var session: SmbSession? = null
    var share: SmbShare? = null
    var negotiation: NegotiationFacts? = null
    try {
      // Percorso: niente socket se e' negato.
      val route = tracker.step(Stage.ROUTE) {
        when (val r = target.candidate?.let(routeGate::onboardingRoute) ?: routeGate.current()) {
          is NasRoute.Denied -> Step.Fail(SmbErrors.denied(r.reason))
          is NasRoute.Allowed -> Step.Pass(r)
        }
      } ?: return tracker.report(negotiation)

      // Solo TCP (non sveglia i dischi).
      tracker.step(Stage.TCP) {
        blocking(config.tcpConnectTimeout + SLACK) {
          route.binding.connect(target.address.toInetAddress(), target.address.port, config.tcpConnectTimeout).close()
        }
        Step.Pass(Unit)
      } ?: return tracker.report(negotiation)

      // NEGOTIATE: dialetto e cifratura offerta.
      val opened = tracker.step(Stage.NEGOTIATE) {
        val made = blocking(config.tcpConnectTimeout + config.handshakeTimeout) {
          transport.connect(route.binding, target.address, config.tcpConnectTimeout)
        }
        connection = made
        val n = made.negotiation
        negotiation = NegotiationFacts(n.dialect, n.encryptionOffered, n.serverGuid, n.nasTimeOffsetMs ?: 0L)
        when {
          n.dialect != SmbDialect.SMB_3_1_1 -> Step.Fail(NasError.InsecureSession(InsecureKind.DIALECT))
          !n.encryptionOffered -> Step.Fail(NasError.InsecureSession(InsecureKind.NO_ENCRYPTION))
          else -> Step.Pass(made)
        }
      } ?: return tracker.report(negotiation)

      // GUID: al primo contatto si legge e basta (lo memorizza chi chiama).
      tracker.step(Stage.GUID) {
        val expected = target.expectedGuid
        if (expected == null || expected.equals(opened.negotiation.serverGuid, ignoreCase = true)) {
          Step.Pass(Unit)
        } else {
          Step.Fail(NasError.IdentityMismatch)
        }
      } ?: return tracker.report(negotiation)

      // Autenticazione: senza credenziali ci si ferma qui; con l'interruttore aperto la fase e' saltata.
      val secret = credentials ?: run {
        tracker.skip(Stage.AUTHENTICATE, null)
        return tracker.report(negotiation)
      }
      val authenticated = tracker.step(Stage.AUTHENTICATE) {
        val breaker = breakers.forKey(opened.negotiation.serverGuid, secret.username)
        when (val attempt = breaker.attempt { _ ->
          val again = target.candidate?.let(routeGate::onboardingRoute) ?: routeGate.current()
          if (again !is NasRoute.Allowed || again.binding.networkId != route.binding.networkId) {
            return@attempt AuthOutcome.Failed(if (again is NasRoute.Denied) SmbErrors.denied(again.reason) else NasError.SessionExpired)
          }
          beforeAuthenticate()
          try {
            AuthOutcome.Accepted(blocking(config.handshakeTimeout) { opened.authenticate(secret.username, secret.password.value) })
          } catch (e: SmbStatusException) {
            SmbErrors.authReason(e.status)?.let { AuthOutcome.Rejected(it) } ?: AuthOutcome.Failed(SmbErrors.fromStatus(e.status, null))
          } catch (e: IOException) {
            AuthOutcome.Failed(SmbErrors.classify(e, null).error)
          }
        }) {
          is AttemptResult.Blocked -> Step.Skip(NasError.AuthBlocked(attempt.reason))
          is AttemptResult.Done -> when (val outcome = attempt.outcome) {
            is AuthOutcome.Accepted -> Step.Pass(outcome.value)
            is AuthOutcome.Rejected -> Step.Fail(NasError.Auth(outcome.reason))
            is AuthOutcome.Failed -> Step.Fail(outcome.error)
          }
        }
      } ?: return tracker.report(negotiation)
      session = authenticated

      // Controllo di sicurezza dopo l'autenticazione.
      tracker.step(Stage.SECURITY_CHECK) {
        val facts = authenticated.facts
        when {
          facts.guest -> Step.Fail(NasError.InsecureSession(InsecureKind.GUEST))
          facts.anonymous -> Step.Fail(NasError.InsecureSession(InsecureKind.ANONYMOUS))
          !facts.encrypted -> Step.Fail(NasError.InsecureSession(InsecureKind.NO_ENCRYPTION))
          else -> Step.Pass(Unit)
        }
      } ?: return tracker.report(negotiation)

      val shareName = target.share ?: run {
        tracker.skip(Stage.SHARE, null)
        tracker.skip(Stage.READ, null)
        return tracker.report(negotiation)
      }
      val connected = tracker.step(Stage.SHARE) {
        if (shareGuard?.allows(secret.username, shareName) == false) return@step Step.Fail(NasError.PermissionDenied)
        try {
          Step.Pass(blocking(config.interactiveOpTimeout) { authenticated.connectShare(shareName) })
        } catch (e: SmbStatusException) {
          Step.Fail(SmbErrors.fromStatus(e.status, shareName))
        }
      } ?: return tracker.report(negotiation)
      share = connected

      // Lettura: la prima voce della radice (tocca i dischi: solo su richiesta esplicita).
      tracker.step(Stage.READ) {
        blocking(config.firstDiskTouchTimeout) {
          connected.openDirectory("", DirAccess.LIST).use { it.entries().hasNext() }
        }
        Step.Pass(Unit)
      }
      return tracker.report(negotiation)
    } finally {
      share?.close()
      session?.close()
      connection?.close()
    }
  }

  /** Una chiamata bloccante con un tetto di tempo; scaduto il tempo e' `NasError.Timeout`. */
  private suspend fun <T> blocking(timeout: Duration, block: () -> T): T {
    var produced: T? = null
    val result = withTimeoutOrNull(timeout) { runInterruptible(io) { block().also { produced = it } } }
    if (result == null) {
      (produced as? AutoCloseable)?.close()
      throw StepFailure(NasError.Timeout)
    }
    return result
  }

  private sealed interface Step<out T> {
    class Pass<T>(val value: T) : Step<T>

    class Fail(val error: NasError) : Step<Nothing>

    class Skip(val error: NasError?) : Step<Nothing>
  }

  private class StepFailure(val error: NasError) : IOException("fase fallita")

  /** Le fasi eseguite, il loro tempo e il loro esito; le fasi non chieste non si riportano. */
  private class Tracker(private val wanted: Set<Stage>, private val clock: MonotonicClock) {
    private val results = ArrayList<StageResult>()

    suspend fun <T> step(stage: Stage, body: suspend () -> Step<T>): T? {
      val started = clock.nowMillis()
      val outcome: Step<T> = try {
        body()
      } catch (c: CancellationException) {
        throw c
      } catch (e: StepFailure) {
        Step.Fail(e.error)
      } catch (e: IOException) {
        Step.Fail(SmbErrors.classify(e, null).error)
      }
      val elapsed = (clock.nowMillis() - started).milliseconds
      return when (outcome) {
        is Step.Pass -> {
          record(StageResult(stage, StageOutcome.PASSED, elapsed))
          outcome.value
        }
        is Step.Fail -> {
          record(StageResult(stage, StageOutcome.FAILED, elapsed, outcome.error))
          null
        }
        is Step.Skip -> {
          record(StageResult(stage, StageOutcome.SKIPPED, elapsed, outcome.error))
          null
        }
      }
    }

    fun skip(stage: Stage, error: NasError?) = record(StageResult(stage, StageOutcome.SKIPPED, Duration.ZERO, error))

    private fun record(result: StageResult) {
      if (result.stage in wanted) {
        results += result
        return
      }
      // Una fase non chiesta (ma necessaria) non e' passata: il suo esito va sulla prima fase chiesta che segue.
      if (result.outcome != StageOutcome.PASSED) {
        val next = Stage.entries.firstOrNull { it in wanted && it.ordinal > result.stage.ordinal }
        if (next != null) results += StageResult(next, result.outcome, Duration.ZERO, result.error)
      }
    }

    fun report(negotiation: NegotiationFacts?): ConnectionReport {
      val all = Stage.entries.filter { it in wanted }.map { stage ->
        results.firstOrNull { it.stage == stage } ?: StageResult(stage, StageOutcome.NOT_RUN, Duration.ZERO)
      }
      return ConnectionReport(all, negotiation)
    }
  }

  private companion object {
    val SLACK: Duration = 1000.milliseconds
  }
}
