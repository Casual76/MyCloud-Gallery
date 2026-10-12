package dev.pampa.mycloudgallery.graph

import dev.pampa.mycloudgallery.core.family.lifecycle.ForeignShareAccess
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Una sessione SMB **usa e getta** con un account che non e' quello del dispositivo ([ForeignShareAccess], P3): il
 * secondo admin legge i kit di recupero nell'area privata dell'admin con la password dell'admin, digitata e mai
 * salvata (docs/fase2-progetto.md §2.3).
 *
 * E' un `SmbNasSessionManager` come quello del dispositivo, quindi con **tutti** i controlli del §3.1: percorso
 * consentito, GUID uguale a quello memorizzato, cifratura, interruttore di quell'account (persistente, condiviso con
 * il test di connessione). La password scritta dall'utente vale un tentativo: se l'interruttore di quell'account era
 * aperto si richiude per quel tentativo (E-R8, "Riprova" su azione dell'utente). La sessione si chiude subito dopo
 * [withShare], le credenziali si azzerano.
 */
class OneShotForeignShares(
  private val routeGate: NasRouteGate,
  private val breakers: AuthBreakerRegistry,
  private val transport: SmbTransport,
  private val clock: MonotonicClock,
  private val scope: CoroutineScope,
  private val deviceId: () -> String?,
) : ForeignShareAccess {

  override suspend fun <T> withShare(account: NasAccount, password: CharArray, share: String, block: (NasFileSystem) -> T): NasResult<T> {
    val secret = try {
      NasCredentials(account.user, password)
    } catch (e: IllegalArgumentException) {
      return NasResult.Err(NasError.CredentialsMissing)
    } finally {
      password.fill('\u0000')
    }
    val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    // Ogni lettura della sorgente da' una copia nuova: la sessione chiude cio' che riceve dopo l'autenticazione.
    val source = NasCredentialSource { CredentialLoad.Found(NasCredentials(secret.username, secret.password.value)) }
    val manager = SmbNasSessionManager(
      account = { account },
      routeGate = routeGate,
      credentials = source,
      breakers = breakers,
      scope = sessionScope,
      transport = transport,
      clock = clock,
      deviceId = deviceId,
    )
    return try {
      val breaker = breakers.forAccount(account)
      if (breaker.current() is BreakerState.Open) breaker.onNewPasswordSaved()
      val outer = manager.withShare(share, Purpose.INTERACTIVE) { scope ->
        try {
          NasResult.Ok(block(scope.fileSystem))
        } catch (e: NasIoException) {
          NasResult.Err(e.error)
        }
      }
      when (outer) {
        is NasResult.Ok -> outer.value
        is NasResult.Err -> outer
      }
    } catch (e: NasIoException) {
      NasResult.Err(e.error)
    } finally {
      withContext(NonCancellable) { manager.invalidate(InvalidateReason.USER_REQUEST) }
      sessionScope.cancel()
      secret.close()
    }
  }
}
