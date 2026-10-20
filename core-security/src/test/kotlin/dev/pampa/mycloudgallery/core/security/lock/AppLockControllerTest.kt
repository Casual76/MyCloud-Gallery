package dev.pampa.mycloudgallery.core.security.lock

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import org.junit.Test

/** La macchina a stati del blocco: logica pura, orologio finto, nessun Android. */
class AppLockControllerTest {

  private class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = now
  }

  private val clock = FakeClock()
  private val controller = AppLockController(clock)

  private fun enabled(timeout: AppLockTimeout) = AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout)

  /** Parte con il blocco attivo e lo sblocca, come dopo un avvio a freddo seguito da uno sblocco riuscito. */
  private fun startUnlocked(timeout: AppLockTimeout) {
    controller.updateSettings(enabled(timeout))
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  private fun leaveAndReturnAfter(millis: Long) {
    controller.onAppBackgrounded()
    clock.now += millis
    controller.onAppForegrounded()
  }

  // --- avvio ---------------------------------------------------------------------------------------------------

  @Test
  fun finoAlleImpostazioni_sonoInCaricamento_enonSiMostraNiente() {
    assertThat(controller.state.value).isEqualTo(AppLockState.LOADING)
    assertThat(controller.settings.value).isNull()
  }

  @Test
  fun aFreddo_conBloccoAttivo_siParteBloccati() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_5_MINUTES))
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun aFreddo_conBloccoSpento_siParteSbloccati() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun impostazioniNonLeggibili_failClosed_blocca() {
    controller.updateSettings(AppLockSettings.FAIL_CLOSED)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    assertThat(AppLockSettings.FAIL_CLOSED.timeout).isEqualTo(AppLockTimeout.IMMEDIATELY)
  }

  // --- timeout ------------------------------------------------------------------------------------------------

  @Test
  fun tornandoPrimaDelTimeout_restaSbloccato() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)
    leaveAndReturnAfter(59_999)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun alTimeoutEsatto_siRichiude() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)
    leaveAndReturnAfter(60_000)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun dopoIlTimeout_siRichiude() {
    startUnlocked(AppLockTimeout.AFTER_30_SECONDS)
    leaveAndReturnAfter(10 * 60_000)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun subito_richiudeSempre_ancheSeTornaInUnBattibaleno() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    leaveAndReturnAfter(0)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun ogniTimeoutHaIlSuoValoreInMillisecondi() {
    assertThat(AppLockTimeout.entries.map { it.millis })
      .containsExactly(0L, 30_000L, 60_000L, 300_000L, 900_000L).inOrder()
    assertThat(AppLockTimeout.DEFAULT).isEqualTo(AppLockTimeout.AFTER_1_MINUTE)
  }

  @Test
  fun lOrologioCheVaIndietro_richiude_failClosed() {
    startUnlocked(AppLockTimeout.AFTER_15_MINUTES)
    leaveAndReturnAfter(-5_000)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun piuUsciteSenzaRitorno_contaLaPrimaSolo() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)
    controller.onAppBackgrounded() // t0
    clock.now += 40_000
    controller.onAppBackgrounded() // un secondo evento: non deve far ripartire il conto
    clock.now += 40_000
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun tornareSenzaEssereUsciti_nonFaNiente() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun unRitornoNelTimeout_azzeraLUscita_quindiLaSuccessivaRiparteDaZero() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)
    leaveAndReturnAfter(50_000)
    clock.now += 50_000 // 100 s dalla prima uscita, ma l'utente era rientrato
    leaveAndReturnAfter(50_000)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  // --- sblocco ---------------------------------------------------------------------------------------------------

  @Test
  fun unSoloSbloccoAllaVolta_esoloSeBloccati() {
    controller.updateSettings(enabled(AppLockTimeout.DEFAULT))
    assertThat(controller.beginAuthentication()).isTrue()
    assertThat(controller.isAuthenticating).isTrue()
    assertThat(controller.beginAuthentication()).isFalse()
    controller.endAuthentication(success = false)
    assertThat(controller.isAuthenticating).isFalse()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    assertThat(controller.beginAuthentication()).isTrue()
  }

  @Test
  fun nonSiSbloccaSeSiEGiaSbloccati() {
    startUnlocked(AppLockTimeout.DEFAULT)
    assertThat(controller.beginAuthentication()).isFalse()
  }

  @Test
  fun durantelo_sblocco_lAppVaInSecondoPiano_manonSiRichiudeDaSola() {
    // Con "subito", la schermata del PIN porta l'app in secondo piano: lo sblocco riuscito non deve richiudersi.
    controller.updateSettings(enabled(AppLockTimeout.IMMEDIATELY))
    assertThat(controller.beginAuthentication()).isTrue()
    controller.onAppBackgrounded() // si apre la schermata del PIN
    controller.onAppForegrounded() // si chiude
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun unaUscitaPrimaDelloSblocco_nonSopravvive() {
    controller.updateSettings(enabled(AppLockTimeout.IMMEDIATELY))
    controller.onAppBackgrounded()
    controller.beginAuthentication()
    controller.endAuthentication(success = true)
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun nessunContatoreDiTentativi_enessunaCancellazione_E_S4() {
    // 50 sblocchi falliti di fila: l'app resta bloccata e basta. I tentativi li limita il sistema.
    controller.updateSettings(enabled(AppLockTimeout.DEFAULT))
    repeat(50) {
      assertThat(controller.beginAuthentication()).isTrue()
      controller.endAuthentication(success = false)
    }
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    // e uno sblocco riuscito funziona come sempre
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  // --- impostazioni a caldo ----------------------------------------------------------------------------------------

  @Test
  fun accendereIlBloccoDentroLApp_nonLaRichiude() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    controller.updateSettings(enabled(AppLockTimeout.DEFAULT))
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    // ma da quel momento vale: esci e torna oltre il timeout
    leaveAndReturnAfter(AppLockTimeout.DEFAULT.millis)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun spegnereIlBlocco_sblocca() {
    controller.updateSettings(enabled(AppLockTimeout.DEFAULT))
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun cambiareTimeout_valeDaSubito() {
    startUnlocked(AppLockTimeout.AFTER_15_MINUTES)
    controller.updateSettings(enabled(AppLockTimeout.AFTER_30_SECONDS))
    leaveAndReturnAfter(31_000)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun bloccaOra_richiudeSoloSeIlBloccoEAttivo() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    controller.lockNow()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    controller.updateSettings(enabled(AppLockTimeout.AFTER_15_MINUTES)) // acceso da dentro: resta sbloccato
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    controller.lockNow()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun conIlBloccoSpento_ilCicloDiVitaNonFaNiente() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    leaveAndReturnAfter(10 * 3_600_000L)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun lUnicaModalitaAttivaECredenzialeDiSistema_ilPinProprioNonEsiste() {
    assertThat(AppLockMode.entries).containsExactly(AppLockMode.OFF, AppLockMode.SYSTEM_CREDENTIAL)
    assertThat(AppLockSettings(AppLockMode.OFF).isEnabled).isFalse()
    assertThat(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL).isEnabled).isTrue()
  }

  // --- P14: il blocco valutato adesso per le porte d'ingresso (selettore, provider, ricezione) -------------------------------------------

  @Test
  fun p14_conLaMainActivityInSecondoPianoIlBloccoSiRichiudeAdessoNonSoloAlRitorno() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)
    controller.onAppForegrounded()
    assertThat(controller.isUnlockedNow()).isTrue()
    controller.onAppBackgrounded()
    clock.now += 59_000
    assertThat(controller.isUnlockedNow()).isTrue()
    // Passato il timeout, con la MainActivity ancora in secondo piano (nessun onAppForegrounded): il cancello e' chiuso subito.
    clock.now += 1_000
    assertThat(controller.isUnlockedNow()).isFalse()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    // E resta chiuso anche dopo, e per la MainActivity che torna.
    clock.now += 10_000_000
    assertThat(controller.isUnlockedNow()).isFalse()
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun p14_conSubitoUnIngressoDaUnAltraAppChiedeSempreLoSblocco() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    controller.onAppForegrounded()
    controller.onAppBackgrounded()
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  @Test
  fun p14_unoSbloccoDIngressoValeLaFinestraDiTolleranzaPoiSiRichiude() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    controller.onAppForegrounded()
    controller.onAppBackgrounded()
    assertThat(controller.isUnlockedNow()).isFalse()
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endEntryAuthentication(success = true)
    assertThat(controller.isUnlockedNow()).isTrue()
    clock.now += AppLockController.ENTRY_GRACE_MS - 1
    assertThat(controller.isUnlockedNow()).isTrue()
    clock.now += 1
    assertThat(controller.isUnlockedNow()).isFalse()
    // Un orologio che torna indietro richiude (fail closed).
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endEntryAuthentication(success = true)
    clock.now -= 5
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  @Test
  fun p14_entrandoNellAppDopoUnoSbloccoDIngressoValeIlTimeoutDellUtente() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    controller.onAppForegrounded()
    controller.onAppBackgrounded()
    controller.isUnlockedNow()
    controller.beginAuthentication()
    controller.endEntryAuthentication(success = true)
    clock.now += 10_000
    // La MainActivity torna: con "Subito" il ritorno nell'app chiede lo sblocco (la tolleranza era solo per le altre app).
    controller.onAppForegrounded()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun p14_unoSbloccoDIngressoFallitoNonSblocca_eSenzaImpostazioniNienteEntra() {
    assertThat(controller.isUnlockedNow()).isFalse()
    startUnlocked(AppLockTimeout.IMMEDIATELY)
    controller.onAppForegrounded()
    controller.onAppBackgrounded()
    controller.isUnlockedNow()
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endEntryAuthentication(success = false)
    assertThat(controller.isUnlockedNow()).isFalse()
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    assertThat(controller.isUnlockedNow()).isTrue()
  }
}
