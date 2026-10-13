package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione di sicurezza della Fase 2, F11: la sessione usa e getta con l'account dell'admin (recupero, secondo admin)
 * non usa mai l'account del dispositivo, non ripete al NAS una password gia' rifiutata e richiude l'interruttore di
 * quell'account solo dopo aver visto il NAS giusto (percorso e GUID), senza password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForeignSharesTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()

  private fun device(name: String): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun tearDown() = devices.forEach { it.close() }

  @Test
  fun unSoloTentativoPerPasswordMaiLAccountDelDispositivoEIlNasGiustoPrimaDiRichiudere() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    tablet.graph.start()
    tablet.graph.joinWith(phone.graph.inviteMember())
    val own = tablet.graph.account.await { it != null }!!
    val admin = own.copy(user = TestNas.ADMIN_USER)
    val foreign = tablet.graph.foreignShares
    val calls = backend.authenticateCalls

    // L'account del dispositivo non passa di qui.
    var before = calls.get()
    val ownResult = foreign.withShare(own, TestNas.MEMBER_PASSWORD.toCharArray(), TestNas.MEMBER_USER) { }
    assertThat((ownResult as NasResult.Err).error).isEqualTo(NasError.CredentialsMissing)
    assertThat(calls.get()).isEqualTo(before)

    // Una password sbagliata: un tentativo. La stessa di nuovo: non torna al NAS.
    before = calls.get()
    val wrong = "Sbagliata-Davvero-9"
    assertThat((foreign.withShare(admin, wrong.toCharArray(), TestNas.ADMIN_USER) { } as NasResult.Err).error)
      .isInstanceOf(NasError.Auth::class.java)
    assertThat(calls.get()).isEqualTo(before + 1)
    assertThat((foreign.withShare(admin, wrong.toCharArray(), TestNas.ADMIN_USER) { } as NasResult.Err).error)
      .isInstanceOf(NasError.Auth::class.java)
    assertThat(calls.get()).isEqualTo(before + 1)
    val breaker = tablet.graph.breakers.forAccount(admin)
    assertThat(breaker.current()).isInstanceOf(BreakerState.Open::class.java)

    // Un impostore con un altro GUID: l'interruttore aperto NON si richiude e la password non parte.
    val realGuid = backend.serverGuid
    backend.serverGuid = "11111111-2222-3333-4444-555555555555"
    before = calls.get()
    val impostor = foreign.withShare(admin, TestNas.ADMIN_PASSWORD.toCharArray(), TestNas.ADMIN_USER) { }
    assertThat(impostor).isInstanceOf(NasResult.Err::class.java)
    assertThat(calls.get()).isEqualTo(before)
    assertThat(breaker.current()).isInstanceOf(BreakerState.Open::class.java)

    // Il NAS vero con la password giusta: si richiude e si legge.
    backend.serverGuid = realGuid
    val ok = foreign.withShare(admin, TestNas.ADMIN_PASSWORD.toCharArray(), TestNas.ADMIN_USER) { "letto" }
    assertThat(ok).isEqualTo(NasResult.Ok("letto"))
    assertThat(breaker.current()).isInstanceOf(BreakerState.Closed::class.java)
  }
}
