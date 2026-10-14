package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard.Reference
import dev.pampa.mycloudgallery.core.nas.session.InMemoryClockJumpStore
import dev.pampa.mycloudgallery.core.nas.session.NasTimeAnchor
import dev.pampa.mycloudgallery.core.nas.session.SessionNasClock
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R7 della revisione 4A: il salto dell'ora del NAS (D32) vale **oltre la vita del processo**. Un salto avvenuto con l'app
 * chiusa si vede alla prima ancora del processo nuovo; un salto visto sospende le scritture anche dopo un riavvio
 * dell'app; la sospensione finisce solo con il gesto dell'utente o dopo 24 ore di ora del NAS stabile.
 */
class ClockJumpGuardTest {

  private val nas0 = 1_800_000_000_000L
  private val minute = 60_000L
  private val hour = 60 * minute

  /** Un processo nuovo: lo stesso file salvato, una guardia nuova. */
  private fun process(store: InMemoryClockJumpStore) = ClockJumpGuard(store)

  @Test
  fun unSaltoAvvenutoConLAppChiusaSiVedeAllaPrimaAncoraDelProcessoNuovo() {
    val store = InMemoryClockJumpStore()
    process(store).onAnchor(nas0, Reference(elapsedMs = 1_000, bootId = 7, wallMs = nas0 - 55_000))

    // L'app riparte un'ora dopo (stesso avvio del telefono): l'NTP sistemato ha riportato il NAS indietro di 20 minuti.
    val next = process(store)
    assertThat(next.blocked).isFalse()
    next.onAnchor(nas0 + hour - 20 * minute, Reference(elapsedMs = 1_000 + hour, bootId = 7, wallMs = nas0 - 55_000 + hour))
    assertThat(next.blocked).isTrue()
    assertThat(next.jump.value).isEqualTo(-20 * minute)

    // E la sospensione resta per il processo dopo ancora, anche senza ancore nuove.
    assertThat(process(store).blocked).isTrue()
  }

  @Test
  fun sottoLaSogliaNienteSaltoAncheFraDueAvviiDelTelefono() {
    val store = InMemoryClockJumpStore()
    process(store).onAnchor(nas0, Reference(elapsedMs = 9 * hour, bootId = 3, wallMs = nas0 - 55_000))
    // Riavvio del telefono (orologio monotono ripartito), tre giorni dopo; il NAS e' andato avanti di un minuto in piu'
    // (l'NTP sistemato riporterebbe indietro i ~55 s: sotto la soglia di 5 minuti).
    val days = 3 * 24 * hour
    val next = process(store)
    next.onAnchor(nas0 + days + minute, Reference(elapsedMs = 2 * minute, bootId = 4, wallMs = nas0 - 55_000 + days))
    assertThat(next.blocked).isFalse()
  }

  @Test
  fun fraDueAvviiUnSaltoGrandeSiVedeConLOrologioDelTelefono() {
    val store = InMemoryClockJumpStore()
    process(store).onAnchor(nas0, Reference(elapsedMs = 9 * hour, bootId = 3, wallMs = nas0))
    val next = process(store)
    // Dopo un riavvio, con l'orologio monotono di nuovo piccolo: il NAS e' un'ora indietro rispetto al previsto.
    next.onAnchor(nas0 + 2 * hour - hour, Reference(elapsedMs = 5 * minute, bootId = 4, wallMs = nas0 + 2 * hour))
    assertThat(next.blocked).isTrue()
  }

  @Test
  fun laSospensioneFinisceDopo24OreDiOraStabileOConIlGesto() {
    val store = InMemoryClockJumpStore()
    val guard = process(store)
    guard.onAnchor(nas0, Reference(0, 1, nas0))
    guard.onAnchor(nas0 + 10 * minute, Reference(hour, 1, nas0 + hour)) // 50 minuti indietro
    assertThat(guard.blocked).isTrue()

    // Ore di ora stabile, ma meno di 24: resta sospesa (anche attraverso un processo nuovo).
    val later = process(store)
    later.onAnchor(nas0 + 10 * minute + 23 * hour, Reference(hour + 23 * hour, 1, nas0 + hour + 23 * hour))
    assertThat(later.blocked).isTrue()
    // Passate le 24 ore senza un altro salto: le scritture ripartono.
    later.onAnchor(nas0 + 10 * minute + 24 * hour, Reference(hour + 24 * hour, 1, nas0 + hour + 24 * hour))
    assertThat(later.blocked).isFalse()

    // Un salto nuovo, poi il gesto dell'utente in Diagnostica.
    later.onAnchor(nas0 + 10 * minute + 24 * hour + 2 * hour, Reference(hour + 24 * hour + 10 * minute, 1, nas0))
    assertThat(later.blocked).isTrue()
    assertThat(later.accept()).isTrue()
    assertThat(later.blocked).isFalse()
    assertThat(process(store).blocked).isFalse()
  }

  @Test
  fun unSaltoNuovoDuranteLAttesaFaRipartireLe24Ore() {
    val store = InMemoryClockJumpStore()
    val guard = process(store)
    guard.onAnchor(nas0, Reference(0, 1, nas0))
    guard.onAnchor(nas0 + hour, Reference(10 * minute, 1, nas0)) // 50 minuti avanti
    guard.onAnchor(nas0 + hour + 20 * hour, Reference(10 * minute + 20 * hour, 1, nas0))
    // Ancora un salto: le 24 ore ricominciano da qui.
    guard.onAnchor(nas0 + hour + 21 * hour + 30 * minute, Reference(10 * minute + 21 * hour, 1, nas0))
    guard.onAnchor(nas0 + hour + 21 * hour + 30 * minute + 23 * hour, Reference(10 * minute + 44 * hour, 1, nas0))
    assertThat(guard.blocked).isTrue()
  }

  @Test
  fun leAncoreDellaSessioneArrivanoAllaGuardia() = runTest {
    val monotonic = ManualMonotonicClock(5_000)
    val clock = SessionNasClock(monotonic)
    clock.anchor(nas0, monotonic.nowMillis())
    assertThat(clock.anchors.first()).isEqualTo(NasTimeAnchor(nas0, 5_000))
  }
}
