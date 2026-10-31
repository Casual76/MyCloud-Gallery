package dev.pampa.mycloudgallery.core.security.lock

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.lock.ProcessForeground.Change
import kotlin.random.Random
import org.junit.Test

/** Il processo in primo piano per finestre (D17, C434): logica pura, orologio finto, finestre finte. */
class ProcessForegroundTest {

  private class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = now
  }

  private val clock = FakeClock()
  private val process = ProcessForeground(clock)

  @Test
  fun laPrimaFinestraPortaInPrimoPiano_eLUltimaLaTogli() {
    val a = process.newWindow()
    val b = process.newWindow()

    assertThat(process.start(a)).isEqualTo(Change.ENTERED_FOREGROUND)
    assertThat(process.start(b)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(2)

    // Una finestra che si ferma mentre l'altra resta visibile non e' un'uscita.
    assertThat(process.stop(a, changingConfigurations = false)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(1)
    assertThat(process.stop(b, changingConfigurations = false)).isEqualTo(Change.LEFT_FOREGROUND)
    assertThat(process.startedWindows).isEqualTo(0)
  }

  @Test
  fun l_ordineDelleFermateNonCambiaNiente() {
    val a = process.newWindow()
    val b = process.newWindow()
    process.start(a)
    process.start(b)

    assertThat(process.stop(b, false)).isEqualTo(Change.NONE)
    assertThat(process.stop(a, false)).isEqualTo(Change.LEFT_FOREGROUND)
  }

  @Test
  fun glieventiRipetutiOFuoriOrdineNonSpostanoIlContatore() {
    val a = process.newWindow()
    val b = process.newWindow()

    // Una fermata prima dell'avvio, e un avvio ripetuto.
    assertThat(process.stop(a, false)).isEqualTo(Change.NONE)
    assertThat(process.start(a)).isEqualTo(Change.ENTERED_FOREGROUND)
    assertThat(process.start(a)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(1)

    // Una seconda finestra che si ferma due volte non toglie la prima.
    process.start(b)
    assertThat(process.stop(b, false)).isEqualTo(Change.NONE)
    assertThat(process.stop(b, false)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(1)

    // Fermata ripetuta dell'ultima: l'uscita si dice una volta sola.
    assertThat(process.stop(a, false)).isEqualTo(Change.LEFT_FOREGROUND)
    assertThat(process.stop(a, false)).isEqualTo(Change.NONE)
    assertThat(process.close(a)).isEqualTo(Change.NONE)
  }

  @Test
  fun unaFinestraChiusaNonTornaPiu() {
    val a = process.newWindow()
    process.start(a)
    assertThat(process.close(a)).isEqualTo(Change.LEFT_FOREGROUND)

    assertThat(process.start(a)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(0)
    // Una finestra nuova riporta in primo piano.
    assertThat(process.start(process.newWindow())).isEqualTo(Change.ENTERED_FOREGROUND)
  }

  @Test
  fun chiudereUnaFinestraAvviataConUnAltraVisibileNonEUnUscita() {
    val a = process.newWindow()
    val b = process.newWindow()
    process.start(a)
    process.start(b)

    assertThat(process.close(a)).isEqualTo(Change.NONE)
    assertThat(process.close(b)).isEqualTo(Change.LEFT_FOREGROUND)
  }

  // --- ricreazione -----------------------------------------------------------------------------------------

  @Test
  fun unaRicreazioneNonEUnUscita_ELaFinestraNuovaNeCollocaIlPosto() {
    val old = process.newWindow()
    process.start(old)

    // Densita', lingua o tema: stop per cambio di configurazione, poi la finestra nuova.
    assertThat(process.stop(old, changingConfigurations = true)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(0)
    val recreated = process.newWindow()
    assertThat(process.start(recreated)).isEqualTo(Change.NONE)
    assertThat(process.startedWindows).isEqualTo(1)

    // Dopo, la sua fermata e' un'uscita vera: il segno e' stato consumato.
    assertThat(process.stop(recreated, false)).isEqualTo(Change.LEFT_FOREGROUND)
  }

  @Test
  fun unaRicreazioneNonFinitaScadeEDiventaUnUscita() {
    val old = process.newWindow()
    process.start(old)
    process.stop(old, changingConfigurations = true)

    clock.now += ProcessForeground.RECREATION_GRACE_MS - 1
    assertThat(process.poll()).isEqualTo(Change.NONE)
    clock.now += 1
    assertThat(process.poll()).isEqualTo(Change.LEFT_FOREGROUND)
    // E la finestra nuova che arriva tardi e' un ritorno in primo piano, non una ricreazione.
    assertThat(process.start(process.newWindow())).isEqualTo(Change.ENTERED_FOREGROUND)
  }

  @Test
  fun unaRicreazioneScadutaPesaAncheAllaFermataDiUnAltraFinestra() {
    val a = process.newWindow()
    val b = process.newWindow()
    process.start(a)
    process.start(b)
    process.stop(a, changingConfigurations = true)

    clock.now += ProcessForeground.RECREATION_GRACE_MS
    // b e' l'ultima finestra avviata e il segno di a e' scaduto: l'app esce.
    assertThat(process.stop(b, false)).isEqualTo(Change.LEFT_FOREGROUND)
  }

  @Test
  fun unaRicreazioneInCorsoTieneInPrimoPianoQuandoSiFermaL_Altra() {
    val a = process.newWindow()
    val b = process.newWindow()
    process.start(a)
    process.start(b)
    process.stop(a, changingConfigurations = true)

    // b si ferma mentre la finestra nuova di a sta per arrivare: ancora primo piano.
    assertThat(process.stop(b, false)).isEqualTo(Change.NONE)
    val recreatedA = process.newWindow()
    assertThat(process.start(recreatedA)).isEqualTo(Change.NONE)
    assertThat(process.stop(recreatedA, false)).isEqualTo(Change.LEFT_FOREGROUND)
  }

  @Test
  fun unOrologioCheVaIndietroFaScadereLaRicreazione_fallireChiusi() {
    val old = process.newWindow()
    process.start(old)
    process.stop(old, changingConfigurations = true)

    clock.now -= 5_000
    assertThat(process.poll()).isEqualTo(Change.LEFT_FOREGROUND)
  }

  @Test
  fun l_uscitaRisaleAllaFermataDellUltimaFinestra() {
    val a = process.newWindow()
    val b = process.newWindow()
    process.start(a)
    process.start(b)
    clock.now = 2_000_000L
    process.stop(a, false)
    clock.now = 2_050_000L
    assertThat(process.stop(b, false)).isEqualTo(Change.LEFT_FOREGROUND)
    // Conta quella dell'ultima, non quella della prima.
    assertThat(process.lastExitAtMillis).isEqualTo(2_050_000L)
  }

  @Test
  fun unaRicreazioneScadutaDataLUscitaDallaRicreazione_nonDalMomentoInCuiCiSiAccorge() {
    val old = process.newWindow()
    process.start(old)
    clock.now = 3_000_000L
    process.stop(old, changingConfigurations = true)

    // Ce ne accorgiamo due ore dopo: l'app e' uscita quando si e' fermata la finestra vecchia.
    clock.now = 3_000_000L + 2 * 3_600_000L
    assertThat(process.poll()).isEqualTo(Change.LEFT_FOREGROUND)
    assertThat(process.lastExitAtMillis).isEqualTo(3_000_000L)
  }

  // --- proprieta' contro un modello --------------------------------------------------------------------------

  /**
   * Sequenze a caso di avvii, fermate, chiusure, ricreazioni e tempo su quattro finestre, contro un modello scritto in modo diverso (insiemi e un elenco di
   * istanti): il processo e' in primo piano **esattamente** quando una finestra e' avviata o c'e' una ricreazione non scaduta, e ogni cambiamento si dice una volta sola.
   */
  @Test
  fun proprietaSuSequenzeACaso() {
    val random = Random(20261031)
    repeat(300) {
      val localClock = FakeClock()
      val local = ProcessForeground(localClock)
      val windows = List(4) { local.newWindow() }
      val started = mutableSetOf<Int>()
      val closed = mutableSetOf<Int>()
      val marks = mutableListOf<Long>()
      var foreground = false

      fun expire() {
        val now = localClock.now
        marks.removeAll { now - it < 0 || now - it >= ProcessForeground.RECREATION_GRACE_MS }
      }

      fun check(change: Change) {
        expire()
        val should = started.isNotEmpty() || marks.isNotEmpty()
        val expected = when {
          should && !foreground -> Change.ENTERED_FOREGROUND
          !should && foreground -> Change.LEFT_FOREGROUND
          else -> Change.NONE
        }
        foreground = should
        assertThat(change).isEqualTo(expected)
        assertThat(local.startedWindows).isEqualTo(started.size)
      }

      repeat(40) {
        val i = random.nextInt(4)
        when (random.nextInt(6)) {
          0 -> {
            val change = local.start(windows[i])
            if (i !in closed && started.add(i)) {
              expire()
              if (marks.isNotEmpty()) marks.removeAt(0)
            }
            check(change)
          }
          1 -> {
            val change = local.stop(windows[i], changingConfigurations = false)
            started.remove(i)
            check(change)
          }
          2 -> {
            val change = local.stop(windows[i], changingConfigurations = true)
            if (started.remove(i)) {
              expire()
              marks.add(localClock.now)
            }
            check(change)
          }
          3 -> {
            val change = local.close(windows[i])
            closed.add(i)
            started.remove(i)
            check(change)
          }
          4 -> {
            localClock.now += random.nextLong(0, ProcessForeground.RECREATION_GRACE_MS / 2)
            check(local.poll())
          }
          else -> {
            localClock.now += ProcessForeground.RECREATION_GRACE_MS
            check(local.poll())
          }
        }
      }
    }
  }
}
