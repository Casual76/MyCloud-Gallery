package dev.pampa.mycloudgallery.core.security.lock

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import kotlin.random.Random
import org.junit.Test

/**
 * Il blocco per **processo**, con due finestre finte (Fase 10, P4, D17, C434, E-T7): si blocca solo quando l'ultima finestra si ferma, uno sblocco vale per
 * tutte, una ricreazione non e' un'uscita. Logica pura: orologio finto, nessun Android. Il cancello vero (`AppLockGate`, il contenuto che non c'e') e'
 * in `AppLockGateTwoWindowsTest`.
 */
class AppLockTwoWindowsTest {

  private class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = now
  }

  private val clock = FakeClock()
  private val controller = AppLockController(clock)
  private val a = controller.newWindow()
  private val b = controller.newWindow()

  private fun enabled(timeout: AppLockTimeout) = AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout)

  /** L'app parte con il blocco attivo, la finestra A (e B se [both]) e uno sblocco riuscito: come dopo un avvio a freddo seguito dallo sblocco. */
  private fun startUnlocked(timeout: AppLockTimeout, both: Boolean = true) {
    controller.updateSettings(enabled(timeout))
    controller.windowStarted(a)
    if (both) controller.windowStarted(b)
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun unaFinestraCheSiFermaMentreL_altraResta_nonRichiudeNeppureConSubito() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)

    controller.windowStopped(a, changingConfigurations = false)
    controller.windowStarted(a)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    // Cosi' anche l'altra: con A visibile, B puo' fermarsi e ripartire quante volte vuole.
    controller.windowStopped(b, changingConfigurations = false)
    clock.now += 3_600_000L
    controller.windowStarted(b)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(controller.startedWindows).isEqualTo(2)
  }

  @Test
  fun siBloccaQuandoSiFermaL_UltimaFinestra() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)

    controller.windowStopped(a, false)
    controller.windowStopped(b, false)
    assertThat(controller.startedWindows).isEqualTo(0)
    // Basta che una sola torni: il blocco e' del processo, tutte e due lo trovano chiuso.
    controller.windowStarted(a)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    controller.windowStarted(b)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun ilTempoConcessoComincia_dallaFermataDell_Ultima() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)

    controller.windowStopped(a, false) // t0: B e' ancora visibile
    clock.now += 50_000
    controller.windowStopped(b, false) // t0 + 50 s: esce l'app
    clock.now += 30_000 // 80 s dalla prima fermata, 30 s dall'ultima
    controller.windowStarted(b)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    controller.windowStopped(b, false)
    clock.now += 60_000
    controller.windowStarted(a)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun unaFinestraSenzaTempoPerdutoNonAllungaIlTempoDell_Altra() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE)

    controller.windowStopped(a, false)
    controller.windowStopped(b, false) // l'app esce qui
    clock.now += 40_000
    controller.windowStarted(a)
    controller.windowStopped(a, false) // un rientro dentro il tempo: il conto riparte da qui
    clock.now += 40_000
    controller.windowStarted(b)
    // 40 s dall'ultima uscita: ancora aperta. (Con un conto che non riparte sarebbero 80 s.)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun uno_sbloccoVaPerTutte_eMentreUnaChiedeL_altraNonNeApreUnaSeconda() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_5_MINUTES))
    controller.windowStarted(a)
    controller.windowStarted(b)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)

    // A comincia a chiedere la credenziale: B lo vede e non ne comincia un'altra.
    assertThat(controller.beginAuthentication()).isTrue()
    assertThat(controller.authenticatingState.value).isTrue()
    assertThat(controller.beginAuthentication()).isFalse()

    controller.endAuthentication(success = true)
    assertThat(controller.authenticatingState.value).isFalse()
    // Lo stato e' uno: lo leggono tutte le finestre.
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(controller.isUnlockedNow()).isTrue()
    // E a sbloccato non si chiede piu' niente.
    assertThat(controller.beginAuthentication()).isFalse()
  }

  @Test
  fun unaCredenzialeAnnullata_lasciaTuttoChiuso_eSiPuoRiprovareDaOgniFinestra() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_5_MINUTES))
    controller.windowStarted(a)
    controller.windowStarted(b)

    assertThat(controller.beginAuthentication()).isTrue()
    controller.endAuthentication(success = false)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    assertThat(controller.authenticatingState.value).isFalse()
    // Riprova l'altra finestra.
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }

  @Test
  fun bloccaOra_valePerTutte() {
    startUnlocked(AppLockTimeout.AFTER_15_MINUTES)
    controller.lockNow()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  // --- ricreazione, finestre che finiscono ----------------------------------------------------------------------

  @Test
  fun unaRicreazioneNonEUnUscita_neppureConSubito() {
    startUnlocked(AppLockTimeout.IMMEDIATELY, both = false)

    // Densita', lingua, tema: stop per cambio di configurazione e subito la finestra nuova.
    controller.windowStopped(a, changingConfigurations = true)
    val recreated = controller.newWindow()
    controller.windowStarted(recreated)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    // La sua fermata vera, invece, chiude.
    controller.windowStopped(recreated, false)
    controller.windowStarted(controller.newWindow())
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun unaRicreazioneMaiFinita_nonTieneAperta_L_app_failClosed() {
    startUnlocked(AppLockTimeout.AFTER_1_MINUTE, both = false)

    clock.now += 100_000
    controller.windowStopped(a, changingConfigurations = true)
    // La finestra nuova non arriva mai. Un'ora dopo un ingresso da un'altra app (il selettore) chiede il cancello: l'uscita risale alla ricreazione, non a adesso.
    clock.now += 3_600_000L
    assertThat(controller.isUnlockedNow()).isFalse()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun unaFinestraChiusaPerSempre_nonTieneAperta_L_app() {
    startUnlocked(AppLockTimeout.IMMEDIATELY)

    controller.windowClosed(a)
    controller.windowStopped(b, false)
    assertThat(controller.startedWindows).isEqualTo(0)
    controller.windowStarted(controller.newWindow())
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun unaFinestraChiusaNonRiapre_unEventoTardivoDelSistema() {
    startUnlocked(AppLockTimeout.IMMEDIATELY, both = false)
    controller.windowClosed(a)
    controller.windowStarted(a) // un ON_START che arriva dopo la chiusura: ignorato
    assertThat(controller.startedWindows).isEqualTo(0)
  }

  // --- gli ingressi da altre app con due finestre ------------------------------------------------------------------

  @Test
  fun unaFinestraVisibile_tieneAperto_ilCancelloDegliIngressi() {
    startUnlocked(AppLockTimeout.IMMEDIATELY, both = false)
    // A e' in primo piano (due finestre, una e' ferma): il selettore di un'altra app trova il blocco aperto, senza richiedere la credenziale.
    controller.windowStarted(b)
    controller.windowStopped(b, false)
    assertThat(controller.isUnlockedNow()).isTrue()
    controller.windowStopped(a, false)
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  @Test
  fun unoSbloccoDIngressoConUnaFinestraVisibile_nonAvviaIlConto() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    controller.windowStarted(a)
    assertThat(controller.beginAuthentication()).isTrue()
    controller.endEntryAuthentication(success = true)
    // A e' visibile: l'app e' "dentro", il conto non parte e non c'e' nessuna tolleranza da concedere.
    clock.now += 3_600_000L
    assertThat(controller.isUnlockedNow()).isTrue()
    controller.windowStopped(a, false)
    clock.now += 60_000
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  // --- proprieta': nessuna uscita a meno che l'ultima finestra si fermi -------------------------------------------------

  /**
   * Sequenze a caso su due finestre (avvii, fermate, ricreazioni, tempo, "Blocca ora", sblocchi): in ogni istante, se una finestra e' avviata il blocco
   * **non scatta per il solo passare del tempo** (c'e' qualcuno che guarda), e se nessuna e' avviata il blocco scatta quando il tempo concesso e' passato.
   */
  @Test
  fun proprietaSuSequenzeACaso() {
    val random = Random(31102026)
    repeat(300) {
      val localClock = FakeClock()
      val c = AppLockController(localClock)
      val windows = listOf(c.newWindow(), c.newWindow())
      val timeout = AppLockTimeout.entries[random.nextInt(AppLockTimeout.entries.size)]
      c.updateSettings(enabled(timeout))
      c.beginAuthentication()
      c.endAuthentication(success = true)
      val started = booleanArrayOf(false, false)
      var lastExit: Long? = null // l'istante in cui e' uscita l'ultima finestra, se nessuna e' avviata
      var locked = false

      repeat(40) {
        val i = random.nextInt(2)
        when (random.nextInt(5)) {
          0 -> {
            c.windowStarted(windows[i])
            if (!started[i]) {
              started[i] = true
              if (!started[1 - i]) {
                // L'app torna in primo piano: richiude se e' passato il tempo.
                val exit = lastExit
                if (exit != null && !locked && localClock.now - exit >= timeout.millis) locked = true
                lastExit = null
              }
            }
          }
          1 -> {
            c.windowStopped(windows[i], changingConfigurations = false)
            if (started[i]) {
              started[i] = false
              if (!started[1 - i] && !locked && lastExit == null) lastExit = localClock.now
            }
          }
          2 -> localClock.now += random.nextLong(0, 40_000)
          3 -> if (random.nextInt(8) == 0) {
            c.lockNow()
            locked = true
            lastExit = null
          }
          else -> if (locked && c.beginAuthentication()) {
            c.endAuthentication(success = true)
            locked = false
            lastExit = null // lo sblocco azzera l'uscita ricordata (come `endAuthentication`)
          }
        }
        assertThat(c.state.value).isEqualTo(if (locked) AppLockState.LOCKED else AppLockState.UNLOCKED)
        assertThat(c.startedWindows).isEqualTo(started.count { it })
      }
    }
  }
}
