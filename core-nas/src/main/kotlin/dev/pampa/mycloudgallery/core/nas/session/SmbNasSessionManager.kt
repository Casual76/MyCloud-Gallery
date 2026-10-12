package dev.pampa.mycloudgallery.core.nas.session

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.breaker.AttemptResult
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.AuthOutcome
import dev.pampa.mycloudgallery.core.nas.breaker.PersistentAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SessionInfo
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasBrowser
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasFileSystem
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La sessione SMB verso il NAS (docs/fase2-progetto.md §3 per intero).
 *
 * **Modello.** Una `Connection` e una `Session` per account, riusate da tutti; le share connesse una volta e tenute
 * in cache; i lavori non chiudono mai niente (lo fa solo il gestore). Il connect e' a volo unico (un `Mutex`): chi
 * arriva durante una connessione ne aspetta l'esito. Mai due `authenticate` in parallelo.
 *
 * **Ordine dei controlli (§3.1)** a ogni connessione:
 * 1. il percorso (`NasRouteGate`) e' `Allowed`, altrimenti nessun socket;
 * 2. TCP legato alla rete del percorso, a tempo (6 s, 20 s in risveglio);
 * 3. NEGOTIATE: 3.1.1, cifratura offerta, GUID uguale a quello memorizzato (D3), ora del NAS;
 * 4. interruttore chiuso (§3.3);
 * 5. di nuovo il percorso: stessa rete del socket;
 * 6. UNA `authenticate`;
 * 7. cifratura davvero attiva, niente guest ne' anonimo.
 * Un controllo fallito tra 3 e 7 chiude la connessione senza nuovi tentativi; l'esito resta "agganciato" (nessun
 * nuovo tentativo automatico) finche' non cambia la rete, l'account o non lo chiede l'utente.
 *
 * **Operazioni.** Al massimo [NasSessionConfig.maxInFlight] richieste in volo; ognuna con la sua scadenza (15 s
 * interattivo, 60 s in background, 60 s la prima che tocca i dischi) e, se trasferisce byte, con il watchdog (32
 * KiB/s per 20 s). Una scadenza o un blocco chiudono la connessione (sospetta). Una caduta o una sessione scaduta
 * la ricostruiscono con la password gia' verificata, e le operazioni idempotenti si ripetono (al massimo 2 volte,
 * dopo 1 e 2 s).
 *
 * **Invalidazione.** Cambio della rete del binding o percorso negato → sessione chiusa subito. Chiusa anche dopo 5
 * minuti senza operazioni. `ON_STOP` del processo: lo decide `:app` con [invalidate] guardando [activeOperations].
 *
 * @param account l'account in uso (null = NAS non configurato). Se cambia, la sessione si rifa'.
 * @param deviceId l'id di questo dispositivo: da' il nome ai temporanei (`tmp/<deviceId>-<casuale>`).
 * @param onboardingCandidate durante l'onboarding: il Wi-Fi candidato, per il percorso dedicato
 *   (`NasRouteGate.onboardingRoute`) usato dalle operazioni con [Purpose.ONBOARDING].
 * @param io il dispatcher delle chiamate bloccanti (nei test: `Dispatchers.Unconfined`).
 * @param backoff l'attesa prima di un nuovo tentativo (nei test: istantanea).
 */
class SmbNasSessionManager(
  private val account: () -> NasAccount?,
  private val routeGate: NasRouteGate,
  private val credentials: NasCredentialSource,
  private val breakers: AuthBreakerRegistry,
  private val scope: CoroutineScope,
  private val transport: SmbTransport = SmbjTransport(),
  private val clock: MonotonicClock = ElapsedRealtimeClock,
  /** L'ora del NAS, ancorata a ogni NEGOTIATE: e' il `NasClock` da dare a core-trust. */
  val nasClock: SessionNasClock = SessionNasClock(clock),
  internal val config: NasSessionConfig = NasSessionConfig(),
  internal val io: CoroutineDispatcher = Dispatchers.IO,
  internal val deviceId: () -> String? = { null },
  private val onboardingCandidate: () -> HomeCandidate? = { null },
  internal val backoff: suspend (Duration) -> Unit = { delay(it) },
) : NasSessionManager {

  private val mutableState = MutableStateFlow<NasConnState>(NasConnState.Idle)
  override val state: StateFlow<NasConnState> = mutableState.asStateFlow()

  private val diskTouch = MutableStateFlow<Long?>(null)
  override val lastDiskTouchAt: StateFlow<Long?> = diskTouch.asStateFlow()

  private val stalls = MutableStateFlow<Long?>(null)

  /** L'ultimo trasferimento dichiarato bloccato dal watchdog (orologio monotono), per `NasStatusMonitor`. */
  val lastStallAt: StateFlow<Long?> = stalls.asStateFlow()

  private val active = MutableStateFlow(0)

  /** Operazioni (e blocchi di [withShare]) in corso: zero = sessione libera (VPN, `ON_STOP`). */
  val activeOperations: StateFlow<Int> = active.asStateFlow()

  private val negotiations = MutableStateFlow<NegotiationFacts?>(null)

  /** L'ultima NEGOTIATE (anche rifiutata): dialetto, cifratura offerta, GUID, scarto d'orologio. Per la Diagnostica. */
  val lastNegotiation: StateFlow<NegotiationFacts?> = negotiations.asStateFlow()

  private val connectMutex = Mutex()
  private val permits = Semaphore(config.maxInFlight)
  private val live = AtomicReference<LiveSession?>(null)
  private val epoch = AtomicLong(0)

  @Volatile
  private var latch: SecurityLatch? = null

  @Volatile
  private var lastConnectTimedOut = false

  private val jobsLock = Any()
  private var idleJob: Job? = null
  private var breakerWatch: Job? = null

  private val monitor = OperationMonitor(
    clock = clock,
    scope = scope,
    tick = config.monitorTick,
    wakingThresholdMs = config.wakingThreshold.inWholeMilliseconds,
    onWaking = ::onWaking,
    onAbort = ::onAbort,
  )

  init {
    scope.launch { routeGate.routes.collect { onRouteChanged(it) } }
  }

  // --- API del contratto ------------------------------------------------------------------------------------

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> {
    opStarted()
    try {
      val ready = runOp(share, purpose, idempotent = true, touchesDisk = false) { }
      if (ready is NasResult.Err) return ready
      val shareScope = ShareScope(share, purpose)
      return try {
        NasResult.Ok(withContext(io) { block(shareScope) })
      } catch (e: NasIoException) {
        NasResult.Err(e.error)
      }
    } finally {
      opFinished()
    }
  }

  override fun fileSystem(share: String): NasFileSystem = SmbNasFileSystem(this, share, Purpose.BACKGROUND)

  override fun browser(share: String): NasBrowser = SmbNasBrowser(this, share, Purpose.INTERACTIVE)

  override suspend fun invalidate(reason: InvalidateReason) {
    epoch.incrementAndGet()
    if (reason != InvalidateReason.IDLE_TIMEOUT && reason != InvalidateReason.PROCESS_STOPPED) latch = null
    cancelIdle()
    live.getAndSet(null)?.let { withContext(io) { it.close() } }
    if (mutableState.value !is NasConnState.AuthBlocked) mutableState.value = NasConnState.Idle
    GalleryLog.d(TAG) { "sessione chiusa: $reason" }
  }

  /**
   * L'ora del NAS per le operazioni firmate (formato-nas.md §6.1): se non e' mai stata letta, o e' vecchia e non
   * c'e' una sessione aperta, ne apre una (che rilegge l'ora alla NEGOTIATE).
   */
  suspend fun ensureFreshClock(purpose: Purpose = Purpose.BACKGROUND, maxAge: Duration = 30.minutes): NasResult<NasClock> {
    val age = nasClock.ageMillis()
    if (age != null && (age <= maxAge.inWholeMilliseconds || live.get()?.usable == true)) return NasResult.Ok(nasClock)
    return when (val acquired = acquire(purpose)) {
      is NasResult.Err -> acquired
      is NasResult.Ok -> if (nasClock.ageMillis() != null) NasResult.Ok(nasClock) else NasResult.Err(NasError.Unexpected(null))
    }
  }

  // --- Operazioni ---------------------------------------------------------------------------------------------

  /**
   * Un'operazione SMB sulla share [share], con i nuovi tentativi del §3.1: dopo una caduta (se [idempotent]) o
   * crediti esauriti, al massimo [NasSessionConfig.maxRetries] volte.
   */
  internal suspend fun <T> runOp(
    share: String,
    purpose: Purpose,
    idempotent: Boolean,
    touchesDisk: Boolean,
    op: (OpContext) -> T,
  ): NasResult<T> {
    opStarted()
    try {
      var attempt = 0
      while (true) {
        when (val result = attemptOnce(share, purpose, touchesDisk, op)) {
          is Attempt.Done -> return NasResult.Ok(result.value)
          is Attempt.NoSession -> {
            if (result.error == NasError.SessionExpired && attempt < config.maxRetries) {
              attempt++
              continue
            }
            return NasResult.Err(result.error)
          }
          is Attempt.Failed -> {
            val retry = attempt < config.maxRetries && when (result.failure.kind) {
              FailureKind.RECONNECT -> idempotent
              FailureKind.CREDITS -> true
              FailureKind.SUSPECT, FailureKind.FINAL -> false
            }
            if (!retry) return NasResult.Err(result.failure.error)
            backoff(config.backoff(attempt))
            attempt++
          }
        }
      }
    } finally {
      opFinished()
    }
  }

  /**
   * UN tentativo: sessione (connettendo se serve), share, permesso del limitatore, sorveglianza, [op] bloccante.
   * In caso di caduta o di sospetto la connessione e' gia' scartata al ritorno.
   */
  internal suspend fun <T> attemptOnce(share: String, purpose: Purpose, touchesDisk: Boolean, op: (OpContext) -> T): Attempt<T> {
    val session = when (val acquired = acquire(purpose)) {
      is NasResult.Ok -> acquired.value
      is NasResult.Err -> return Attempt.NoSession(acquired.error)
    }
    return permits.withPermit {
      val startedAt = clock.nowMillis()
      val firstDiskTouch = !session.diskTouched
      val budget = if (firstDiskTouch) maxOf(config.opTimeout(purpose), config.firstDiskTouchTimeout) else config.opTimeout(purpose)
      val ticket = OpTicket(session, startedAt, startedAt + budget.inWholeMilliseconds, firstDiskTouch)
      monitor.begin(ticket)
      try {
        val value = runInterruptible(io) {
          ticket.abortedBy?.let { throw NasOpException(it, FailureKind.SUSPECT) }
          val smbShare = session.share(share)
          op(OpContext(smbShare, session, ticket, clock, config))
        }
        if (touchesDisk) {
          session.diskTouched = true
          diskTouch.value = clock.nowMillis()
        }
        backToReady(session)
        Attempt.Done(value)
      } catch (c: CancellationException) {
        throw c
      } catch (t: Throwable) {
        currentCoroutineContext().ensureActive()
        // Se l'ha interrotta il sorvegliante, conta il suo verdetto (e il blocco e' gia' stato registrato).
        val aborted = ticket.abortedBy
        val failure = aborted?.let { Failure(it, FailureKind.SUSPECT) } ?: SmbErrors.classify(t, share)
        when (failure.kind) {
          FailureKind.RECONNECT -> {
            discard(session)
            val current = mutableState.value
            if (current is NasConnState.Ready || current is NasConnState.WakingUp) mutableState.value = NasConnState.Idle
          }
          FailureKind.SUSPECT -> {
            discard(session)
            if (aborted == null && failure.error == NasError.Stalled) stalls.value = clock.nowMillis()
            mutableState.value = NasConnState.Unavailable(failure.error)
          }
          FailureKind.CREDITS, FailureKind.FINAL -> backToReady(session)
        }
        GalleryLog.d(TAG) { "operazione fallita: $failure" }
        Attempt.Failed(failure)
      } finally {
        monitor.end(ticket)
      }
    }
  }

  private fun backToReady(session: LiveSession) {
    val current = mutableState.value
    if (live.get() === session && current !is NasConnState.Ready && current !is NasConnState.AuthBlocked) {
      mutableState.value = NasConnState.Ready(session.info)
    }
  }

  /** Scarta la sessione dopo un errore che l'ha resa inutilizzabile (caduta, sospetto). */
  internal fun discard(session: LiveSession) {
    live.compareAndSet(session, null)
    session.close()
  }

  // --- Connessione (§3.1, ordine dei controlli) ----------------------------------------------------------------

  private fun resolveRoute(purpose: Purpose): Pair<NasRoute, Boolean> {
    if (purpose == Purpose.ONBOARDING) {
      onboardingCandidate()?.let { return routeGate.onboardingRoute(it) to true }
    }
    return routeGate.current() to false
  }

  private fun reusable(account: NasAccount, route: NasRoute.Allowed): LiveSession? =
    live.get()?.takeIf { it.usable && it.account == account && it.networkId == route.binding.networkId }

  internal suspend fun acquire(purpose: Purpose): NasResult<LiveSession> {
    val current = account() ?: return fail(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    val (route, onboarding) = resolveRoute(purpose)
    if (route is NasRoute.Denied) {
      live.get()?.takeIf { !it.onboarding || onboarding }?.let { drop(it) }
      return fail(SmbErrors.denied(route.reason))
    }
    reusable(current, route as NasRoute.Allowed)?.let { return NasResult.Ok(it) }
    return connectMutex.withLock { connectLocked(purpose) }
  }

  private suspend fun connectLocked(purpose: Purpose): NasResult<LiveSession> {
    val acc = account() ?: return fail(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    // (1) Il percorso.
    val (route, onboarding) = resolveRoute(purpose)
    if (route !is NasRoute.Allowed) return fail(SmbErrors.denied((route as NasRoute.Denied).reason))
    live.get()?.let { existing ->
      if (existing.usable && existing.account == acc && existing.networkId == route.binding.networkId) return NasResult.Ok(existing)
      discard(existing)
    }
    latch?.let { held ->
      if (held.account == acc && held.networkId == route.binding.networkId) return fail(held.error)
      latch = null
    }
    val pinnedGuid = acc.pinnedGuid ?: return fail(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    val breaker = breakers.forAccount(acc)
    // Interruttore aperto: inutile anche solo la TCP (il controllo vero, sotto lucchetto, e' il passo 4).
    (breaker.current() as? BreakerState.Open)?.let { return blocked(breaker, it.reason, own = false) }

    val startEpoch = epoch.get()
    val waking = lastConnectTimedOut || mutableState.value is NasConnState.WakingUp
    mutableState.value = NasConnState.Connecting

    // (2) TCP e (3) NEGOTIATE.
    val tcpTimeout = if (waking) config.tcpConnectTimeoutWaking else config.tcpConnectTimeout
    var made: SmbConnection? = null
    val connection = try {
      withTimeoutOrNull(tcpTimeout + config.handshakeTimeout) {
        runInterruptible(io) { transport.connect(route.binding, acc.address, tcpTimeout).also { made = it } }
      }
    } catch (c: CancellationException) {
      made?.close()
      mutableState.value = NasConnState.Idle
      throw c
    } catch (t: Throwable) {
      val failure = SmbErrors.classify(t, null)
      lastConnectTimedOut = failure.error == NasError.Timeout
      if (failure.error is NasError.InsecureSession) latch = SecurityLatch(failure.error, route.binding.networkId, acc)
      return fail(failure.error)
    }
    if (connection == null) {
      made?.close()
      lastConnectTimedOut = true
      return fail(NasError.Timeout)
    }
    lastConnectTimedOut = false

    val negotiation = connection.negotiation
    negotiations.value = NegotiationFacts(
      dialect = negotiation.dialect,
      encryptionOffered = negotiation.encryptionOffered,
      serverGuid = negotiation.serverGuid,
      timeOffsetMs = negotiation.nasTimeOffsetMs ?: 0L,
    )
    val refused: NasError? = when {
      negotiation.dialect != SmbDialect.SMB_3_1_1 -> NasError.InsecureSession(InsecureKind.DIALECT)
      !negotiation.encryptionOffered -> NasError.InsecureSession(InsecureKind.NO_ENCRYPTION)
      !negotiation.serverGuid.equals(pinnedGuid, ignoreCase = true) -> NasError.IdentityMismatch
      else -> null
    }
    if (refused != null) {
      connection.close()
      latch = SecurityLatch(refused, route.binding.networkId, acc)
      GalleryLog.w(TAG) { "connessione rifiutata prima dell'autenticazione: $refused" }
      return fail(refused)
    }
    // L'ora del NAS solo dopo il GUID: un NAS estraneo non sposta la nostra ora.
    negotiation.nasTimeAtNegotiateMs?.let { nasClock.anchor(it, clock.nowMillis()) }

    // (4) interruttore, (5) percorso, (6) una sola autenticazione.
    val attempt = breaker.attempt { _ ->
      val (again, _) = resolveRoute(purpose)
      if (again is NasRoute.Denied) return@attempt AuthOutcome.Failed(SmbErrors.denied(again.reason))
      if ((again as NasRoute.Allowed).binding.networkId != route.binding.networkId || epoch.get() != startEpoch) {
        return@attempt AuthOutcome.Failed(NasError.SessionExpired)
      }
      val load = try {
        credentials.load(acc.credentialSlot)
      } catch (c: CancellationException) {
        throw c
      } catch (_: Exception) {
        // Keystore guasto o altro: nessuna password, nessun tentativo (e nessuna eccezione verso chi chiama).
        CredentialLoad.Unavailable
      }
      when (load) {
        is CredentialLoad.Found -> load.credentials.use { secret ->
          if (!secret.username.equals(acc.user, ignoreCase = true)) return@attempt AuthOutcome.Failed(NasError.CredentialsMissing)
          beforeAuthenticate()
          authenticateOnce(connection, secret)
        }
        CredentialLoad.Absent, CredentialLoad.NeedsPassword -> AuthOutcome.Failed(NasError.CredentialsMissing)
        CredentialLoad.Unavailable -> AuthOutcome.Failed(NasError.Unexpected(null))
      }
    }

    val session = when (attempt) {
      is AttemptResult.Blocked -> {
        connection.close()
        return blocked(breaker, attempt.reason, own = false)
      }
      is AttemptResult.Done -> when (val outcome = attempt.outcome) {
        is AuthOutcome.Rejected -> {
          connection.close()
          GalleryLog.w(TAG) { "autenticazione rifiutata: ${outcome.reason}, interruttore aperto" }
          return blocked(breaker, outcome.reason, own = true)
        }
        is AuthOutcome.Failed -> {
          connection.close()
          if (outcome.error is NasError.InsecureSession) latch = SecurityLatch(outcome.error, route.binding.networkId, acc)
          return fail(outcome.error)
        }
        is AuthOutcome.Accepted -> outcome.value
      }
    }

    // (7) Controllo di sicurezza dopo l'autenticazione.
    val facts = session.facts
    val insecure = when {
      facts.guest -> InsecureKind.GUEST
      facts.anonymous -> InsecureKind.ANONYMOUS
      !facts.encrypted -> InsecureKind.NO_ENCRYPTION
      else -> null
    }
    if (insecure != null) {
      session.close()
      connection.close()
      val error = NasError.InsecureSession(insecure)
      latch = SecurityLatch(error, route.binding.networkId, acc)
      GalleryLog.w(TAG) { "sessione insicura rifiutata: $insecure" }
      return fail(error)
    }
    if (epoch.get() != startEpoch) {
      // La rete e' cambiata durante l'autenticazione: il socket e' legato alla rete di prima.
      session.close()
      connection.close()
      return fail(NasError.SessionExpired)
    }
    val info = SessionInfo(
      dialect = negotiation.dialect,
      encrypted = facts.encrypted,
      cipher = negotiation.cipher,
      signingRequired = facts.signingRequired,
      serverGuid = negotiation.serverGuid,
      nasTimeOffsetMs = negotiation.nasTimeOffsetMs ?: 0L,
      via = route.via,
    )
    val created = LiveSession(acc, route, onboarding, connection, session, info)
    live.set(created)
    mutableState.value = NasConnState.Ready(info)
    if (active.value == 0) scheduleIdle()
    GalleryLog.i(TAG) { "sessione pronta: ${info.dialect}, cifrata=${info.encrypted}, cifrario=${info.cipher}, via=${info.via}" }
    return NasResult.Ok(created)
  }

  private suspend fun authenticateOnce(connection: SmbConnection, secret: NasCredentials): AuthOutcome<SmbSession> {
    var made: SmbSession? = null
    return try {
      val session = withTimeoutOrNull(config.handshakeTimeout) {
        runInterruptible(io) { connection.authenticate(secret.username, secret.password.value).also { made = it } }
      }
      if (session == null) {
        made?.close()
        AuthOutcome.Failed(NasError.Timeout)
      } else {
        AuthOutcome.Accepted(session)
      }
    } catch (e: SmbStatusException) {
      SmbErrors.authReason(e.status)?.let { AuthOutcome.Rejected(it) } ?: AuthOutcome.Failed(SmbErrors.fromStatus(e.status, null))
    } catch (e: IOException) {
      AuthOutcome.Failed(SmbErrors.classify(e, null).error)
    }
  }

  private fun <T> fail(error: NasError): NasResult<T> {
    mutableState.value = NasConnState.Unavailable(error)
    return NasResult.Err(error)
  }

  private fun <T> blocked(breaker: PersistentAuthCircuitBreaker, reason: AuthBlockReason, own: Boolean): NasResult<T> {
    mutableState.value = NasConnState.AuthBlocked(reason)
    synchronized(jobsLock) {
      breakerWatch?.cancel()
      breakerWatch = scope.launch {
        breaker.state.first { it is BreakerState.Closed }
        if (mutableState.value is NasConnState.AuthBlocked) mutableState.value = NasConnState.Idle
      }
    }
    return NasResult.Err(if (own) NasError.Auth(reason) else NasError.AuthBlocked(reason))
  }

  // --- Invalidazione, inattivita', sorveglianza ------------------------------------------------------------------

  private fun onRouteChanged(route: NasRoute) {
    latch?.let { held -> if (route !is NasRoute.Allowed || route.binding.networkId != held.networkId) latch = null }
    val current = live.get() ?: return
    if (current.onboarding) return
    when (route) {
      is NasRoute.Denied -> drop(current)
      is NasRoute.Allowed -> if (route.binding.networkId != current.networkId) drop(current)
    }
  }

  /** Chiude la sessione perche' la rete e' cambiata o il percorso e' negato: socket chiusi subito. */
  private fun drop(session: LiveSession) {
    epoch.incrementAndGet()
    discard(session)
    val current = mutableState.value
    if (current is NasConnState.Ready || current is NasConnState.WakingUp || current is NasConnState.Connecting) {
      mutableState.value = NasConnState.Idle
    }
    GalleryLog.d(TAG) { "sessione chiusa per cambio di rete" }
  }

  private fun onWaking(ticket: OpTicket) {
    if (live.get() === ticket.owner) mutableState.value = NasConnState.WakingUp(ticket.startedAt)
  }

  private fun onAbort(ticket: OpTicket, error: NasError) {
    // Chiudere la connessione e' l'unico modo di sbloccare una chiamata ferma su un socket muto.
    (ticket.owner as? LiveSession)?.let { discard(it) }
    if (error == NasError.Stalled) stalls.value = clock.nowMillis()
    mutableState.value = NasConnState.Unavailable(error)
    GalleryLog.w(TAG) { "operazione interrotta dal sorvegliante: $error" }
  }

  private fun opStarted() {
    active.update { it + 1 }
    cancelIdle()
  }

  private fun opFinished() {
    active.update { it - 1 }
    if (active.value == 0) scheduleIdle()
  }

  private fun cancelIdle() {
    synchronized(jobsLock) {
      idleJob?.cancel()
      idleJob = null
    }
  }

  private fun scheduleIdle() {
    synchronized(jobsLock) {
      idleJob?.cancel()
      idleJob = scope.launch {
        delay(config.idleTimeout)
        if (active.value != 0) return@launch
        live.getAndSet(null)?.let { session ->
          session.close()
          if (mutableState.value is NasConnState.Ready) mutableState.value = NasConnState.Idle
          GalleryLog.d(TAG) { "sessione chiusa per inattivita'" }
        }
      }
    }
  }

  private inner class ShareScope(override val shareName: String, purpose: Purpose) : NasShareScope {
    override val fileSystem: NasFileSystem = SmbNasFileSystem(this@SmbNasSessionManager, shareName, purpose)
    override val browser: NasBrowser = SmbNasBrowser(this@SmbNasSessionManager, shareName, purpose)
  }

  private class SecurityLatch(val error: NasError, val networkId: String, val account: NasAccount)

  private companion object {
    const val TAG = "NasSession"
  }
}

/** L'esito di UN tentativo di operazione. */
internal sealed interface Attempt<out T> {
  class Done<T>(val value: T) : Attempt<T>

  /** Nessuna sessione: percorso negato, interruttore aperto, NAS muto... */
  class NoSession(val error: NasError) : Attempt<Nothing>

  class Failed(val failure: Failure) : Attempt<Nothing>
}

/** Cio' che un'operazione bloccante riceve: la share, e il modo di dire al watchdog che i byte si muovono. */
internal class OpContext(
  val share: SmbShare,
  private val session: LiveSession,
  private val ticket: OpTicket,
  private val clock: MonotonicClock,
  private val config: NasSessionConfig,
) {
  /** Un'altra share della stessa sessione (copia lato server tra share). */
  fun otherShare(name: String): SmbShare = session.share(name)

  /** Da qui in poi l'operazione trasferisce byte: parte il watchdog (E-R14). */
  fun startTransfer() {
    val window = config.stallWindow.inWholeMilliseconds
    ticket.transfer = TransferWatchdog(window, config.stallMinBytesPerSecond * window / MILLIS_PER_SECOND, clock.nowMillis())
  }

  /** [bytes] trasferiti adesso. Lancia se il trasferimento e' sotto la soglia o se il sorvegliante l'ha interrotto. */
  fun progress(bytes: Int) {
    checkAborted()
    val watchdog = ticket.transfer ?: return
    val now = clock.nowMillis()
    watchdog.onBytes(bytes, now)
    if (watchdog.isStalled(now)) throw NasOpException(NasError.Stalled, FailureKind.SUSPECT)
  }

  fun checkAborted() {
    ticket.abortedBy?.let { throw NasOpException(it, FailureKind.SUSPECT) }
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
  }
}

/** Una connessione autenticata e le sue share. La chiude solo il gestore. */
internal class LiveSession(
  val account: NasAccount,
  val route: NasRoute.Allowed,
  /** Aperta sul percorso dell'onboarding: non la tocca il cambio del percorso corrente. */
  val onboarding: Boolean,
  private val connection: SmbConnection,
  private val session: SmbSession,
  val info: SessionInfo,
) {
  val networkId: String get() = route.binding.networkId

  @Volatile
  var closed: Boolean = false
    private set

  /** Almeno un'operazione ha gia' toccato i dischi in questa sessione (non serve piu' il tempo lungo). */
  @Volatile
  var diskTouched: Boolean = false

  private val shares = HashMap<String, SmbShare>()

  val usable: Boolean get() = !closed && connection.isConnected

  /** La share connessa (TREE_CONNECT alla prima richiesta, poi dalla cache; i nomi non distinguono le maiuscole). */
  fun share(name: String): SmbShare = synchronized(shares) {
    if (closed) throw SmbConnectionLostException()
    shares.getOrPut(name.lowercase(Locale.ROOT)) { session.connectShare(name) }
  }

  /** Prima il socket (sblocca subito le chiamate in corso), poi share e sessione. */
  fun close() {
    closed = true
    connection.close()
    val toClose = synchronized(shares) { shares.values.toList().also { shares.clear() } }
    toClose.forEach { it.close() }
    session.close()
  }
}
