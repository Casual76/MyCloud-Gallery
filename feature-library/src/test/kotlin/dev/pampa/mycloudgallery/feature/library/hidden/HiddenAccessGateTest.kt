package dev.pampa.mycloudgallery.feature.library.hidden

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il cancello dei Nascosti (E-H3): la credenziale a ogni ingresso con un minuto di tolleranza, mai una scorciatoia.
 * Logica pura con un orologio finto: nessun Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenAccessGateTest {

  private val clock = TestClock(now = 5_000L)
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val prompt = AuthPromptText("Sblocca", "prova")

  @Test
  fun siEntraSempreChiusi() {
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun laToleranzaDiDefaultEUnMinuto() {
    assertThat(HiddenAccessGate.DEFAULT_GRACE_MS).isEqualTo(60_000L)
    assertThat(HiddenAccessGate().graceMillis).isEqualTo(60_000L)
  }

  @Test
  fun conLaCredenzialeSiApre() = runTest {
    val result = gate.unlock(auth, prompt)

    assertThat(result).isEqualTo(HiddenAccess.Unlocked)
    assertThat(gate.isUnlocked).isTrue()
    assertThat(auth.calls).isEqualTo(1)
  }

  @Test
  fun unRifiutoLasciaChiusoEDiceIlMotivo() = runTest {
    val cases = mapOf(
      AuthResult.Cancelled to HiddenDenial.CANCELLED,
      AuthResult.Interrupted to HiddenDenial.INTERRUPTED,
      AuthResult.LockedOut(permanent = false) to HiddenDenial.LOCKED_OUT,
      AuthResult.LockedOut(permanent = true) to HiddenDenial.LOCKED_OUT,
      AuthResult.Error(code = 7) to HiddenDenial.ERROR,
    )
    for ((result, reason) in cases) {
      val g = HiddenAccessGate(clock, 60_000L)
      val a = FakeAuthenticator(result = result)

      g.unlock(a, prompt)

      assertThat(g.state.value).isEqualTo(HiddenAccess.Denied(reason))
      assertThat(g.isUnlocked).isFalse()
    }
  }

  @Test
  fun senzaBloccoSchermoNonSiApreMaiEnonSiMostraLaSchermataDiSistema() = runTest {
    val noLock = FakeAuthenticator(availability = AuthAvailability.NO_SCREEN_LOCK)

    gate.unlock(noLock, prompt)

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.NO_SCREEN_LOCK))
    assertThat(noLock.calls).isEqualTo(0)
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun unaCredenzialeNonDisponibileRestaChiusa() = runTest {
    for (availability in listOf(AuthAvailability.TEMPORARILY_UNAVAILABLE, AuthAvailability.UNSUPPORTED)) {
      val g = HiddenAccessGate(clock, 60_000L)
      g.unlock(FakeAuthenticator(availability = availability), prompt)
      assertThat(g.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.UNAVAILABLE))
    }
  }

  @Test
  fun unaEccezioneDelSistemaNonApreNiente() = runTest {
    val broken = object : SystemCredentialAuthenticator {
      override fun availability() = AuthAvailability.AVAILABLE

      override suspend fun authenticate(text: AuthPromptText): AuthResult = error("BiometricPrompt ha lanciato")
    }

    gate.unlock(broken, prompt)

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.ERROR))
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun entroUnMinutoDallUscitaSiRientraSenzaCredenziale() = runTest {
    gate.unlock(auth, prompt)
    clock.now += 20_000
    gate.onHidden()

    clock.now += 59_999
    gate.onShown()

    assertThat(gate.isUnlocked).isTrue()
    assertThat(auth.calls).isEqualTo(1)
  }

  @Test
  fun alloScadereDelMinutoSiRichiude() = runTest {
    gate.unlock(auth, prompt)
    gate.onHidden()

    clock.now += 60_000
    gate.onShown()

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun passatoIlMinutoNonSiLeggeNemmenoPrimaDelRientro() = runTest {
    gate.unlock(auth, prompt)
    gate.onHidden()

    clock.now += 61_000

    // Nessuno ha chiamato onShown: l'ingresso non e' ancora stato valutato, ma il cancello e' gia' chiuso per chi legge.
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun unOrologioCheVaIndietroRichiude() = runTest {
    gate.unlock(auth, prompt)
    gate.onHidden()

    clock.now -= 1

    assertThat(gate.isUnlocked).isFalse()
    gate.onShown()
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
  }

  @Test
  fun laToleranzaCominciaAllUscitaENonAlloSblocco() = runTest {
    gate.unlock(auth, prompt)
    // Si resta nella pagina per mezz'ora: finche' non si esce non scade niente.
    clock.now += 30 * 60_000

    assertThat(gate.isUnlocked).isTrue()
    gate.onShown()
    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun dueUsciteDiSeguitoNonAllunganoLaToleranza() = runTest {
    gate.unlock(auth, prompt)
    gate.onHidden()
    clock.now += 40_000
    gate.onHidden() // un secondo evento senza ritorno in mezzo

    clock.now += 25_000
    gate.onShown()

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
  }

  @Test
  fun ognuRientroConsumaLaTolleranza() = runTest {
    gate.unlock(auth, prompt)
    gate.onHidden()
    clock.now += 30_000
    gate.onShown() // dentro la tolleranza
    // Si resta per un po' e si esce di nuovo: la tolleranza riparte da questa uscita.
    clock.now += 50_000
    gate.onHidden()
    clock.now += 50_000
    gate.onShown()

    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun unNuovoIngressoDopoUnRifiutoRipropone() = runTest {
    auth.result = AuthResult.Cancelled
    gate.unlock(auth, prompt)
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.CANCELLED))

    gate.onShown()

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
  }

  @Test
  fun unaSolaRichiestaAllaVolta_ChiLaChiedeInsiemeAspettaLoStessoEsito() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    val first = async { gate.unlock(auth, prompt) }
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Authenticating)

    // Un'altra schermata protetta chiede nello stesso momento: non apre una seconda schermata di sistema, aspetta.
    val second = async { gate.unlock(auth, prompt) }
    assertThat(second.isCompleted).isFalse()
    assertThat(auth.calls).isEqualTo(1)
    auth.hold!!.complete(Unit)

    assertThat(first.await()).isEqualTo(HiddenAccess.Unlocked)
    assertThat(second.await()).isEqualTo(HiddenAccess.Unlocked)
    assertThat(auth.calls).isEqualTo(1)
    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun chiAspettaVedeAncheIlRifiuto() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    auth.result = AuthResult.Cancelled
    val first = async { gate.unlock(auth, prompt) }
    val second = async { gate.unlock(auth, prompt) }

    auth.hold!!.complete(Unit)

    assertThat(first.await()).isEqualTo(HiddenAccess.Denied(HiddenDenial.CANCELLED))
    assertThat(second.await()).isEqualTo(HiddenAccess.Denied(HiddenDenial.CANCELLED))
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun richiudereMentreSiAspettaNonApreAChiAspetta() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    val first = async { gate.unlock(auth, prompt) }
    val second = async { gate.unlock(auth, prompt) }

    gate.lock()
    auth.hold!!.complete(Unit)

    assertThat(second.await()).isEqualTo(HiddenAccess.Locked)
    assertThat(first.await()).isEqualTo(HiddenAccess.Locked)
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun dueSchermateVisibiliTengonoApertoIlCancelloFinoAllUltimaUscita() = runTest {
    gate.unlock(auth, prompt)
    gate.onShown() // Nascosti
    gate.onShown() // Eliminati di recente, che compare prima che i Nascosti escano
    gate.onHidden() // i Nascosti escono

    clock.now += 5 * 60_000
    assertThat(gate.isUnlocked).isTrue()

    gate.onHidden() // esce anche l'ultima: adesso comincia il minuto
    clock.now += 59_999
    assertThat(gate.isUnlocked).isTrue()
    clock.now += 1
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun lUscitaDiUnaSchermataNonSbilanciaIlConto() = runTest {
    gate.unlock(auth, prompt)
    gate.onShown()
    gate.onHidden()
    gate.onHidden() // un'uscita in piu' (per esempio dopo la rotazione): il conto non scende sotto zero

    gate.onShown()
    clock.now += 10 * 60_000

    // Una schermata e' visibile: la tolleranza non e' partita.
    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun giaApertoNonRichiedeUnaSecondaCredenziale() = runTest {
    gate.unlock(auth, prompt)

    gate.unlock(auth, prompt)

    assertThat(auth.calls).isEqualTo(1)
  }

  @Test
  fun laSchermataDiSistemaNonFaPartireLaTolleranza() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    val pending = launch { gate.unlock(auth, prompt) }

    // La schermata di sistema porta la pagina in secondo piano: non e' un'uscita.
    gate.onHidden()
    clock.now += 120_000
    auth.hold!!.complete(Unit)
    pending.join()
    gate.onShown()

    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun richiudereMentreSiAutenticaNonLasciaChiAprireDopo() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    val pending = launch { gate.unlock(auth, prompt) }

    gate.lock()
    auth.hold!!.complete(Unit)
    pending.join()

    // L'esito di uno sblocco vecchio non vale: si e' richiuso nel frattempo.
    assertThat(gate.isUnlocked).isFalse()
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Locked)
  }

  @Test
  fun annullareLaRichiestaLasciaChiusoELaSchermataInterrotta() = runTest(UnconfinedTestDispatcher()) {
    auth.hold = CompletableDeferred()
    val pending = launch { gate.unlock(auth, prompt) }

    pending.cancelAndJoin()

    assertThat(gate.isUnlocked).isFalse()
    assertThat(gate.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.INTERRUPTED))
  }

  @Test
  fun nonSiPuoMostrareLaSchermataDiSistema_restaChiusoConIlMotivo() {
    gate.deny(HiddenDenial.UNAVAILABLE)

    assertThat(gate.state.value).isEqualTo(HiddenAccess.Denied(HiddenDenial.UNAVAILABLE))
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun unRifiutoNonChiudeUnCancelloGiaAperto() = runTest {
    gate.unlock(auth, prompt)

    gate.deny(HiddenDenial.UNAVAILABLE)

    assertThat(gate.isUnlocked).isTrue()
  }

  @Test
  fun lockRichiudeSubito() = runTest {
    gate.unlock(auth, prompt)

    gate.lock()

    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun unNuovoCancelloRiparteSempreChiuso() = runTest {
    gate.unlock(auth, prompt)
    // Un riavvio del processo e' un cancello nuovo: niente sopravvive.
    val afterRestart = HiddenAccessGate(clock, 60_000L)

    assertThat(afterRestart.isUnlocked).isFalse()
  }
}
