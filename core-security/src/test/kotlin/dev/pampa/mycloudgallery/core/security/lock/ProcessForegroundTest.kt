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

  /**
   * R3 (revisione di sicurezza della Fase 10): il segno scade e **nessuno chiede** (niente `poll`, nessuna fermata): la finestra che arriva ore dopo non e' "ancora
   * in primo piano", e' un ritorno dopo un'uscita all'ora del segno. Prima `start` rispondeva `NONE` e il blocco non controllava il tempo (uscita da DeX con il
   * telefono bloccato).
   */
  @Test
  fun r3_unSegnoScadutoSenzaPoll_poiUnAvvio_eUnRitornoDopoL_uscitaAllOraDelSegno() {
    val old = process.newWindow()
    process.start(old)
    clock.now = 5_000_000L
    process.stop(old, changingConfigurations = true)

    clock.now = 5_000_000L + 3 * 3_600_000L
    assertThat(process.start(process.newWindow())).isEqualTo(Change.REENTERED_FOREGROUND)
    assertThat(process.lastExitAtMillis).isEqualTo(5_000_000L)
    assertThat(process.startedWindows).isEqualTo(1)
    // Il ritorno si dice una volta: la seconda finestra non cambia niente, e l'uscita dopo e' normale.
    val second = process.newWindow()
    assertThat(process.start(second)).isEqualTo(Change.NONE)
    process.stop(second, false)
    assertThat(process.poll()).isEqualTo(Change.NONE)
  }

  @Test
  fun r3_unSegnoAncoraVivoSenzaPoll_eUnaRicreazioneNonUnRitorno() {
    val old = process.newWindow()
    process.start(old)
    process.stop(old, changingConfigurations = true)

    clock.now += ProcessForeground.RECREATION_GRACE_MS - 1
    assertThat(process.start(process.newWindow())).isEqualTo(Change.NONE)
  }

  // --- proprieta' contro un modello --------------------------------------------------------------------------

  /**
   * Sequenze a caso di avvii, fermate, chiusure, ricreazioni e tempo su quattro finestre, contro un modello scritto in modo diverso (insiemi e un elenco di
   * istanti): il processo e' in primo piano **esattamente** quando una finestra e' avviata o c'e' una ricreazione non scaduta, e ogni cambiamento si dice una volta sola.
   * R3: il tempo passa anche **senza** un `poll` subito dopo (azione 6); un avvio dopo un segno scaduto che nessuno ha guardato e' un ritorno
   * ([Change.REENTERED_FOREGROUND]), con l'uscita all'ora del segno piu' tardo fra quelli scaduti.
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

      /** Un evento senza effetto (finestra chiusa, gia' avviata o non avviata): non guarda il tempo e non cambia niente, nemmeno con un segno scaduto. */
      fun noEffect(change: Change) {
        assertThat(change).isEqualTo(Change.NONE)
        assertThat(local.startedWindows).isEqualTo(started.size)
      }

      repeat(40) {
        val i = random.nextInt(4)
        when (random.nextInt(7)) {
          0 -> {
            val change = local.start(windows[i])
            if (i in closed || i in started) return@repeat noEffect(change)
            run {
              val now = localClock.now
              val expired = marks.filter { now - it < 0 || now - it >= ProcessForeground.RECREATION_GRACE_MS }
              expire()
              // R3: in primo piano per il modello, ma nessuna finestra e nessun segno vivo: l'uscita c'e' stata senza che nessuno la chiedesse.
              val silent = foreground && started.isEmpty() && marks.isEmpty()
              started.add(i)
              if (marks.isNotEmpty()) marks.removeAt(0)
              if (silent) {
                assertThat(change).isEqualTo(Change.REENTERED_FOREGROUND)
                assertThat(local.lastExitAtMillis).isAtLeast(expired.maxOfOrNull { minOf(it, now) } ?: 0L)
                assertThat(local.startedWindows).isEqualTo(started.size)
                return@repeat
              }
            }
            check(change)
          }
          1 -> {
            val change = local.stop(windows[i], changingConfigurations = false)
            if (!started.remove(i)) return@repeat noEffect(change)
            check(change)
          }
          2 -> {
            val change = local.stop(windows[i], changingConfigurations = true)
            if (!started.remove(i)) return@repeat noEffect(change)
            expire()
            marks.add(localClock.now)
            check(change)
          }
          3 -> {
            val change = local.close(windows[i])
            closed.add(i)
            if (!started.remove(i)) return@repeat noEffect(change)
            check(change)
          }
          4 -> {
            localClock.now += random.nextLong(0, ProcessForeground.RECREATION_GRACE_MS / 2)
            check(local.poll())
          }
          5 -> {
            localClock.now += ProcessForeground.RECREATION_GRACE_MS
            check(local.poll())
          }
          // R3: il tempo passa e nessuno chiede (nessun poll): il modello non cambia finche' un evento non lo guarda.
          else -> localClock.now += random.nextLong(0, 2 * ProcessForeground.RECREATION_GRACE_MS)
        }
      }
    }
  }
}
