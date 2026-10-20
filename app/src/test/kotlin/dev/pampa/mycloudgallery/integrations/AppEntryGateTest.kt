package dev.pampa.mycloudgallery.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.MonotonicClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import org.junit.Test

/**
 * Il cancello delle activity d'ingresso (P14; nota di P10, `docs/sicurezza.md` §4-bis, C17, E-X7): valuta il blocco **adesso**. Il buco che
 * chiude: prima il blocco si richiudeva solo al ritorno in primo piano della `MainActivity`; con lei in secondo piano il selettore e il
 * provider dei documenti restavano sbloccati per sempre per le altre app.
 */
class AppEntryGateTest {

  private var now = 1_000_000L
  private val lock = AppLockController(MonotonicClock { now })
  private var ready = true
  private val gate = AppEntryGate(lock) { ready }

  private fun enable(timeout: AppLockTimeout) = lock.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout))

  /** La `MainActivity` in primo piano e lo sblocco con la credenziale (come fa `AppLockGate`). */
  private fun unlockInApp() {
    lock.onAppForegrounded()
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endAuthentication(true)
  }

  @Test
  fun bloccoSpentoFamigliaProntaEntra() {
    lock.updateSettings(AppLockSettings())
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    ready = false
    assertThat(gate.decide()).isEqualTo(EntryDecision.NOT_READY)
  }

  @Test
  fun impostazioniNonLetteNonEntraNessuno() {
    // A processo freddo, prima che le impostazioni del blocco siano lette: chiuso (fail closed).
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun conLaMainActivityInSecondoPianoIlBloccoSiRichiudeAdessoNonAlRitorno() {
    enable(AppLockTimeout.AFTER_1_MINUTE)
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    unlockInApp()
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)

    // L'utente esce dall'app: per un minuto un'altra app puo' ancora scegliere le foto (come la MainActivity al ritorno).
    lock.onAppBackgrounded()
    now += 59_000
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    // Passato il timeout il cancello e' chiuso **senza** che la MainActivity torni in primo piano (il buco di prima).
    now += 1_000
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    // E resta chiuso: la MainActivity che torna trova la schermata di blocco.
    lock.onAppForegrounded()
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unOrologioCheTornaIndietroRichiude() {
    enable(AppLockTimeout.AFTER_15_MINUTES)
    unlockInApp()
    lock.onAppBackgrounded()
    now -= 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unoSbloccoDallIngressoValeDueMinutiAncheConSubito() {
    enable(AppLockTimeout.IMMEDIATELY)
    // La MainActivity non c'e' (un'altra app ha aperto il selettore a processo freddo): lo sblocco lo fa l'activity d'ingresso.
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endEntryAuthentication(true)
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    now += AppLockController.ENTRY_GRACE_MS - 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    now += 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unoSbloccoNonRiuscitoNonApre() {
    enable(AppLockTimeout.AFTER_5_MINUTES)
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endEntryAuthentication(false)
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }
}
