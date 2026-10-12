package dev.pampa.mycloudgallery.core.nas

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerStore
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope

const val SHARE = "test-comune"
const val READ_ONLY_SHARE = "sola-lettura"
const val FORBIDDEN_SHARE = "privata"
const val USER = "test"
const val GOOD_PASSWORD = "password-giusta"
const val DEVICE = "abcdefghijklmnopqrstuvwxyz"
const val HOME_WIFI = "wifi-casa"

/** Il valore di un esito riuscito (fallisce il test altrimenti). */
fun <T> NasResult<T>.value(): T = when (this) {
  is NasResult.Ok -> value
  is NasResult.Err -> throw AssertionError("atteso Ok, arrivato $error")
}

/** L'orologio virtuale di runTest come orologio monotono. */
fun TestScope.virtualClock(): MonotonicClock = MonotonicClock { testScheduler.currentTime }

/**
 * Un NAS simulato, un cancello aperto sul Wi-Fi di casa, le credenziali giuste e il gestore della sessione.
 * Di default tutto gira sul thread del test (`Dispatchers.Unconfined`) e i nuovi tentativi non aspettano.
 */
class Harness(
  val scope: CoroutineScope,
  val clock: MonotonicClock,
  config: NasSessionConfig = NasSessionConfig(),
  io: CoroutineDispatcher = Dispatchers.Unconfined,
  val store: BreakerStore = InMemoryBreakerStore(),
  val backend: InMemorySmbBackend = defaultBackend(),
) {
  val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding(HOME_WIFI)) }

  @Volatile
  var account: NasAccount? = NasAccount(NasAddress("192.168.178.58", "MyCloudEX2Ultra"), USER, "slot-nas", backend.serverGuid)

  @Volatile
  var password: String = GOOD_PASSWORD

  val credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(USER, password.toCharArray())) }
  val breakers = AuthBreakerRegistry(store, scope, wallClock = { WALL_CLOCK })
  val backoffs: MutableList<Duration> = CopyOnWriteArrayList()

  val manager = SmbNasSessionManager(
    account = { account },
    routeGate = gate,
    credentials = credentials,
    breakers = breakers,
    scope = scope,
    transport = backend,
    clock = clock,
    config = config,
    io = io,
    deviceId = { DEVICE },
    backoff = { backoffs += it },
  )

  val breaker get() = breakers.forAccount(checkNotNull(account))

  companion object {
    const val WALL_CLOCK = 1_791_763_200_000L

    fun defaultBackend(): InMemorySmbBackend = InMemorySmbBackend().apply {
      addUser(USER, GOOD_PASSWORD)
      addUser("altro", "altra-password")
      addShare(SHARE, mapOf(USER to SimAccess.READ_WRITE))
      addShare(READ_ONLY_SHARE, mapOf(USER to SimAccess.READ))
      addShare(FORBIDDEN_SHARE, mapOf("altro" to SimAccess.READ_WRITE))
    }
  }
}
