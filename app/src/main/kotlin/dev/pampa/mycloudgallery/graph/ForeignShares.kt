package dev.pampa.mycloudgallery.graph

import dev.pampa.mycloudgallery.core.family.lifecycle.ForeignShareAccess
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Una sessione SMB **usa e getta** con un account che non e' quello del dispositivo ([ForeignShareAccess], P3): il
 * secondo admin legge i kit di recupero nell'area privata dell'admin con la password dell'admin, digitata e mai
 * salvata (docs/fase2-progetto.md §2.3).
 *
 * E' un `SmbNasSessionManager` come quello del dispositivo, quindi con **tutti** i controlli del §3.1: percorso
 * consentito, GUID uguale a quello memorizzato, cifratura, interruttore di quell'account (persistente, condiviso con
 * il test di connessione). La sessione si chiude subito dopo [withShare], le credenziali si azzerano.
 *
 * Revisione 2, F11, la password scritta dall'utente vale **un** tentativo, con tre regole:
 *  - **mai l'account del dispositivo** ([ownUser]): la sua password si cambia dalle Impostazioni, dove l'interruttore
 *    si richiude solo con una password nuova salvata;
 *  - se l'interruttore di quell'account era aperto, si richiude **solo dopo** che percorso, NEGOTIATE e GUID sono
 *    risultati giusti (una prova senza credenziali): verso un NAS che non e' il nostro non si richiude niente;
 *  - la stessa coppia utente+password gia' rifiutata **non torna al NAS** (impronta HMAC con una chiave casuale del
 *    processo, solo in memoria).
 */
class OneShotForeignShares(
  private val routeGate: NasRouteGate,
  private val breakers: AuthBreakerRegistry,
  private val transport: SmbTransport,
  private val clock: MonotonicClock,
  private val scope: CoroutineScope,
  private val tester: ConnectionTester,
  /** L'utente NAS di questo dispositivo, se configurato: non passa di qui. */
  private val ownUser: () -> String?,
  private val deviceId: () -> String?,
) : ForeignShareAccess {

  private val rejected = RejectedPairs()

  override suspend fun <T> withShare(account: NasAccount, password: CharArray, share: String, block: (NasFileSystem) -> T): NasResult<T> {
    if (ownUser()?.equals(account.user, ignoreCase = true) == true) {
      password.fill('\u0000')
      return NasResult.Err(NasError.CredentialsMissing)
    }
    val secret = try {
      NasCredentials(account.user, password)
    } catch (e: IllegalArgumentException) {
      return NasResult.Err(NasError.CredentialsMissing)
    } finally {
      password.fill('\u0000')
    }
    if (rejected.isRepeat(account.user, secret.password.value)) {
      secret.close()
      return NasResult.Err(NasError.Auth(AuthBlockReason.AUTH_FAILED))
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
      if (breaker.current() is BreakerState.Open) {
        // Si richiude solo per il NAS giusto, sulla strada giusta: prima la prova senza password.
        val probe = tester.run(TestTarget(account.address, account.user, share = null, expectedGuid = account.pinnedGuid), null, IDENTITY_STAGES)
        if (!probe.passed) return NasResult.Err(probe.firstFailure?.error ?: NasError.IdentityMismatch)
        breaker.onNewPasswordSaved()
      }
      val outer = manager.withShare(share, Purpose.INTERACTIVE) { scope ->
        try {
          NasResult.Ok(block(scope.fileSystem))
        } catch (e: NasIoException) {
          NasResult.Err(e.error)
        }
      }
      val result = when (outer) {
        is NasResult.Ok -> outer.value
        is NasResult.Err -> outer
      }
      if (result is NasResult.Err && result.error is NasError.Auth) rejected.remember(account.user, secret.password.value)
      result
    } catch (e: NasIoException) {
      NasResult.Err(e.error)
    } finally {
      withContext(NonCancellable) { manager.invalidate(InvalidateReason.USER_REQUEST) }
      sessionScope.cancel()
      secret.close()
    }
  }

  /** Le coppie utente+password rifiutate in questo processo, come impronte HMAC con una chiave casuale. */
  private class RejectedPairs {
    private val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
    private val lock = Mutex()
    private val seen = ArrayDeque<String>()

    suspend fun isRepeat(user: String, password: CharArray): Boolean = lock.withLock { fingerprint(user, password) in seen }

    suspend fun remember(user: String, password: CharArray) = lock.withLock {
      seen.addLast(fingerprint(user, password))
      while (seen.size > MAX_REMEMBERED) seen.removeFirst()
    }

    private fun fingerprint(user: String, password: CharArray): String {
      val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }
      mac.update(user.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
      mac.update(0)
      val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
      val raw = ByteArray(bytes.remaining()).also { bytes.get(it) }
      try {
        mac.update(raw)
      } finally {
        raw.fill(0)
        if (bytes.hasArray()) bytes.array().fill(0)
      }
      return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    companion object {
      const val KEY_BYTES = 32
      const val MAX_REMEMBERED = 8
      const val HMAC = "HmacSHA256"
    }
  }

  private companion object {
    /** La prova d'identita' senza password: percorso, TCP, NEGOTIATE, GUID. */
    val IDENTITY_STAGES = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
  }
}
