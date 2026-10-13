package dev.pampa.mycloudgallery.feature.settings.account

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.feature.settings.support.FakeNasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

/**
 * La nuova password del NAS (E-R8): **un solo tentativo** e nell'ordine giusto.
 *
 * L'ordine delle operazioni si legge dal registro `events`, che i fake riempiono man mano.
 */
class PasswordViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val events = mutableListOf<String>()

  private val accounts = FakeNasAccountSettings().apply { onSave = { events += "salva" } }
  private val breaker = FakeAuthCircuitBreaker()
  private val sessions = FakeNasSessionManager()
  private val tester = object : dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester {
    var report: ConnectionReport = passed()
    var attempts = 0

    override suspend fun run(
      target: dev.pampa.mycloudgallery.core.nas.contract.TestTarget,
      credentials: dev.pampa.mycloudgallery.core.security.credentials.NasCredentials?,
      stages: Set<Stage>,
    ): ConnectionReport {
      events += "prova"
      if (credentials != null) attempts++
      return report
    }
  }

  private fun passed() = ConnectionReport(
    stages = PasswordStages.sorted().map { StageResult(it, StageOutcome.PASSED, 5.milliseconds) },
    negotiation = null,
  )

  private fun failedAt(error: NasError) = ConnectionReport(
    stages = listOf(
      StageResult(Stage.ROUTE, StageOutcome.PASSED, 1.milliseconds),
      StageResult(Stage.AUTHENTICATE, StageOutcome.FAILED, 1.milliseconds, error),
      StageResult(Stage.SECURITY_CHECK, StageOutcome.NOT_RUN, 0.milliseconds),
    ),
    negotiation = null,
  )

  private fun viewModel(account: FakeNasAccountSettings? = accounts) =
    PasswordViewModel(Optional.ofNullable(account), breaker, tester, sessions)

  @Test
  fun conLInterruttoreChiusoProvaPrimaESalvaSoloSeRiesce() = runTest {
    val vm = viewModel()
    vm.setPassword("una-password-lunga-e-giusta")
    vm.submit()

    assertThat(events).containsExactly("prova", "salva").inOrder()
    assertThat(accounts.savedFor).containsExactly("test2")
    assertThat(vm.state.value.phase).isEqualTo(PasswordPhase.DONE)
    assertThat(sessions.invalidations).containsExactly(InvalidateReason.CREDENTIALS_CHANGED)
    // La password digitata non resta nello stato.
    assertThat(vm.state.value.password).isEmpty()
  }

  @Test
  fun unaPasswordSbagliataConLInterruttoreChiusoNonSostituisceQuellaBuona() = runTest {
    tester.report = failedAt(NasError.Auth(AuthBlockReason.AUTH_FAILED))
    val vm = viewModel()
    vm.setPassword("Chiave-Sbagliata-7")
    vm.submit()

    assertThat(events).containsExactly("prova")
    assertThat(accounts.savedFor).isEmpty()
    assertThat(breaker.state.value).isInstanceOf(BreakerState.Closed::class.java)
    assertThat(vm.state.value.phase).isEqualTo(PasswordPhase.FORM)
    assertThat(vm.state.value.feedback).isNotNull()
    assertThat(vm.state.value.stages).hasSize(3)
  }

  @Test
  fun conLInterruttoreApertoSalvaChiudeLInterruttoreEFaUnSoloTentativo() = runTest {
    breaker.open(AuthBlockReason.AUTH_FAILED)
    val vm = viewModel()
    assertThat(vm.state.value.blockedBy).isEqualTo(AuthBlockReason.AUTH_FAILED)
    vm.setPassword("la-nuova-password-ok")
    vm.submit()

    // Prima si salva (e si chiude l'interruttore), poi UN tentativo: mai una prova con l'interruttore aperto.
    assertThat(events).containsExactly("salva", "prova").inOrder()
    assertThat(tester.attempts).isEqualTo(1)
    assertThat(breaker.state.value).isEqualTo(BreakerState.Closed(generation = 2))
    assertThat(vm.state.value.phase).isEqualTo(PasswordPhase.DONE)
  }

  @Test
  fun seLaNuovaPasswordFallisceConLInterruttoreApertoNonRiprovaDaSola() = runTest {
    breaker.open(AuthBlockReason.AUTH_FAILED)
    tester.report = failedAt(NasError.Auth(AuthBlockReason.AUTH_FAILED))
    val vm = viewModel()
    vm.setPassword("anche-questa-sbagliata")
    vm.submit()

    assertThat(tester.attempts).isEqualTo(1)
    assertThat(vm.state.value.phase).isEqualTo(PasswordPhase.FORM)
    assertThat(vm.state.value.feedback).isNotNull()
    // Per riprovare serve scrivere una password nuova: il tasto e' spento finche' il campo e' vuoto.
    assertThat(vm.state.value.canSubmit).isFalse()
  }

  @Test
  fun seLaCassaforteNonSalvaNonSiChiudeLInterruttoreNeSiProva() = runTest {
    breaker.open(AuthBlockReason.AUTH_FAILED)
    accounts.saveResult = SaveResult.Unavailable
    val vm = viewModel()
    vm.setPassword("la-nuova-password-ok")
    vm.submit()

    assertThat(events).containsExactly("salva")
    assertThat(tester.attempts).isEqualTo(0)
    assertThat(breaker.state.value).isInstanceOf(BreakerState.Open::class.java)
    assertThat(vm.state.value.feedback).isNotNull()
  }

  /** Revisione 2, F1: una password che non regge un attacco offline non si prova nemmeno; l'app ne propone una forte. */
  @Test
  fun unaPasswordDeboleNonSiProvaELAppNeProponeUnaForte() = runTest {
    val vm = viewModel()
    vm.setPassword("corta")
    assertThat(vm.state.value.weakness).isEqualTo(NasPasswordPolicy.Weakness.TOO_SHORT)
    assertThat(vm.state.value.canSubmit).isFalse()
    vm.submit()
    assertThat(events).isEmpty()
    assertThat(tester.attempts).isEqualTo(0)

    vm.setPassword("questa-e-abbastanza-lunga")
    assertThat(vm.state.value.weakness).isNull()
    assertThat(vm.state.value.canSubmit).isTrue()

    vm.generate()
    val proposed = vm.state.value.password
    assertThat(proposed).hasLength(NasPasswordPolicy.GENERATED_CHARS)
    assertThat(vm.state.value.generated).isTrue()
    assertThat(vm.state.value.visible).isTrue()
    assertThat(vm.state.value.canSubmit).isTrue()
    // Chi scrive di nuovo nel campo non sta piu' usando la password proposta.
    vm.setPassword(proposed + "x")
    assertThat(vm.state.value.generated).isFalse()
  }

  @Test
  fun senzaAccountCollegatoNonSiProvaNiente() = runTest {
    val vm = viewModel(account = null)
    vm.setPassword("qualcosa-di-lungo-12")
    vm.submit()
    assertThat(vm.state.value.available).isFalse()
    assertThat(events).isEmpty()
  }

  @Test
  fun unSecondoTocco_dopoIlPrimo_nonRipeteLaProva() = runTest {
    val vm = viewModel()
    vm.setPassword("una-password-lunga-e-giusta")
    vm.submit()
    vm.submit()
    assertThat(tester.attempts).isEqualTo(1)
  }

  @Test
  fun laStringaDiStatoNonContieneLaPassword() = runTest {
    val vm = viewModel()
    vm.setPassword("segretissima-123")
    assertThat(vm.state.value.toString()).doesNotContain("segretissima")
  }
}
