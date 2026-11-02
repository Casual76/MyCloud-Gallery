package dev.pampa.mycloudgallery.feature.integrations.drag

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * Fase 10 (P6, D20, E-T14, E-T17, L547): i prestiti del trascinamento. Le copie e gli id vivono **finche' l'app e' in primo piano piu' 10 minuti**, non finiscono a fine gesto (chi riceve legge
 * anche dopo) e **non oltre** quel tempo; un trascinamento che non parte si rilascia subito; i trascinamenti insieme sono al piu' quattro. Tempo virtuale, mai l'ora del telefono.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DragLeasesTest {

  @Before
  fun setUp() = DragCounters.reset()

  @After
  fun tearDown() = DragCounters.reset()

  private class Released {
    val order = mutableListOf<Int>()

    fun lease(leases: DragLeases, n: Int) = leases.hold { order += n }
  }

  @Test
  fun inPrimoPianoIlPrestitoDuraComeLApp() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    released.lease(leases, 1)

    // Ore in primo piano: l'altra app puo' leggere finche' vuole.
    advanceTimeBy(5 * 60 * 60 * 1_000L)
    runCurrent()

    assertThat(released.order).isEmpty()
    assertThat(leases.count).isEqualTo(1)
  }

  @Test
  fun inSecondoPianoDopoDieciMinutiSiRilascia() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    released.lease(leases, 1)

    leases.foreground(false)
    advanceTimeBy(DragLimits.TEMP_GRACE_MS - 1)
    runCurrent()
    assertThat(released.order).isEmpty()

    advanceTimeBy(2)
    runCurrent()
    assertThat(released.order).containsExactly(1)
    assertThat(leases.count).isEqualTo(0)
    assertThat(DragCounters.get(DragCounter.OUT_RELEASED)).isEqualTo(1L)
    assertThat(DragLimits.TEMP_GRACE_MS).isEqualTo(10 * 60 * 1_000L)
  }

  @Test
  fun tornareInPrimoPianoFermaIlConto_EUscireDiNuovoRicominciaDaDieciMinuti() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    released.lease(leases, 1)

    leases.foreground(false)
    advanceTimeBy(9 * 60 * 1_000L)
    leases.foreground(true)
    advanceTimeBy(3 * 60 * 60 * 1_000L)
    runCurrent()
    assertThat(released.order).isEmpty()

    leases.foreground(false)
    advanceTimeBy(DragLimits.TEMP_GRACE_MS - 1)
    runCurrent()
    assertThat(released.order).isEmpty()
    advanceTimeBy(2)
    runCurrent()
    assertThat(released.order).containsExactly(1)
  }

  @Test
  fun unTrascinamentoCheNasceConLAppInSecondoPianoHaSubitoIlSuoConto() = runTest {
    val leases = DragLeases(this, inForeground = false)
    val released = Released()
    released.lease(leases, 1)

    advanceTimeBy(DragLimits.TEMP_GRACE_MS + 1)
    runCurrent()

    assertThat(released.order).containsExactly(1)
  }

  @Test
  fun alPiuQuattroTrascinamentiInsiemeEIPiuVecchiEscono() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    for (n in 1..6) released.lease(leases, n)

    assertThat(released.order).containsExactly(1, 2).inOrder()
    assertThat(leases.count).isEqualTo(DragLeases.MAX_HELD)
    leases.releaseAll()
    assertThat(released.order).containsExactly(1, 2, 3, 4, 5, 6)
    assertThat(leases.count).isEqualTo(0)
  }

  @Test
  fun unTrascinamentoCheNonParteSiRilasciaSubitoUnaVolta_ENonContaComeFinito() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    val lease = released.lease(leases, 1)

    lease.release()
    lease.release()
    leases.releaseAll()

    assertThat(released.order).containsExactly(1)
    assertThat(DragCounters.get(DragCounter.OUT_DISCARDED)).isEqualTo(1L)
    assertThat(DragCounters.get(DragCounter.OUT_RELEASED)).isEqualTo(0L)
  }

  @Test
  fun unRilascioChePoiScadeNonRilasciaDueVolte() = runTest {
    val leases = DragLeases(this)
    val released = Released()
    val lease = released.lease(leases, 1)
    leases.foreground(false)

    lease.release()
    advanceTimeBy(DragLimits.TEMP_GRACE_MS * 2)
    runCurrent()

    assertThat(released.order).containsExactly(1)
  }

  // --- Chi dice se l'app e' in primo piano ------------------------------------------------------------------------------------

  private fun TestScope.tracker(): Pair<DragLeases, ForegroundTracker> {
    val leases = DragLeases(this)
    return leases to ForegroundTracker(leases)
  }

  @Test
  fun lAppEInSecondoPianoSoloQuandoNessunaFinestraEVisibile() = runTest {
    val (leases, tracker) = tracker()
    val released = Released()
    released.lease(leases, 1)
    val first = Robolectric.buildActivity(Activity::class.java).get()
    val second = Robolectric.buildActivity(Activity::class.java).get()

    tracker.onActivityStarted(first)
    tracker.onActivityStarted(second)
    // Una finestra che si ferma mentre l'altra resta visibile (due finestre, schermo diviso) non e' un'uscita.
    tracker.onActivityStopped(first)
    advanceTimeBy(DragLimits.TEMP_GRACE_MS * 2)
    runCurrent()
    assertThat(released.order).isEmpty()

    tracker.onActivityStopped(second)
    advanceTimeBy(DragLimits.TEMP_GRACE_MS + 1)
    runCurrent()
    assertThat(released.order).containsExactly(1)
  }

  @Test
  fun unaRicreazioneNonEUnUscita() = runTest {
    val (leases, tracker) = tracker()
    val released = Released()
    released.lease(leases, 1)
    val activity = Robolectric.buildActivity(Activity::class.java).get()
    tracker.onActivityStarted(activity)

    // Rotazione o cambio di densita': l'activity si ferma (dicendo che cambia la configurazione) e si riavvia, il conto non parte.
    val changing = object : Activity() {
      override fun isChangingConfigurations(): Boolean = true
    }
    tracker.onActivityStopped(changing)
    advanceTimeBy(DragLimits.TEMP_GRACE_MS * 2)
    runCurrent()

    assertThat(released.order).isEmpty()
  }
}
