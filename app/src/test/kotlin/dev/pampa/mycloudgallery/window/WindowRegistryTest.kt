package dev.pampa.mycloudgallery.window

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWidth
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Le finestre vive del processo (Fase 10, P4, D16, D17, E-T6): al piu' due, la terza rifiutata, la principale e' la piu' vecchia. Logica pura. */
class WindowRegistryTest {

  private val registry = WindowRegistry()

  @Test
  fun laPrimaFinestraEPrincipale_laSecondaEAmmessa_laTerzaERifiutata() {
    assertThat(registry.admit(10, max = 2)).isEqualTo(WindowAdmission.Admitted(WindowRole.PRIMARY))
    assertThat(registry.admit(20, max = 2)).isEqualTo(WindowAdmission.Admitted(WindowRole.SECONDARY))
    assertThat(registry.admit(30, max = 2)).isEqualTo(WindowAdmission.Refused)
    assertThat(registry.windows.value).containsExactly(10, 20).inOrder()
    assertThat(registry.count).isEqualTo(2)
  }

  @Test
  fun unaRicreazioneEUnaFinestraConosciuta_enonOccupaUnPostoInPiu() {
    registry.admit(10, 2)
    registry.admit(20, 2)

    // Densita', lingua, tema: la stessa finestra (stesso task) si ricrea, anche a posti finiti.
    assertThat(registry.admit(20, 2)).isEqualTo(WindowAdmission.Known(WindowRole.SECONDARY))
    assertThat(registry.admit(10, 2)).isEqualTo(WindowAdmission.Known(WindowRole.PRIMARY))
    assertThat(registry.count).isEqualTo(2)
  }

  @Test
  fun chiudereUnaFinestraLiberaIlPosto_eLAltraDiventaPrincipale() {
    registry.admit(10, 2)
    registry.admit(20, 2)

    registry.release(10)
    assertThat(registry.roleOf(20)).isEqualTo(WindowRole.PRIMARY)
    assertThat(registry.primary).isEqualTo(20)
    assertThat(registry.admit(30, 2)).isEqualTo(WindowAdmission.Admitted(WindowRole.SECONDARY))
    // Rilasciare due volte, o una finestra che non c'e', non rompe niente.
    registry.release(10)
    registry.release(99)
    assertThat(registry.windows.value).containsExactly(20, 30).inOrder()
  }

  @Test
  fun unaFinestraCheNonSiConosce_nonHaRuolo() {
    assertThat(registry.roleOf(5)).isNull()
    assertThat(registry.primary).isNull()
    assertThat(registry.othersThan(5)).isEmpty()
  }

  @Test
  fun sottoAndroid12_unaFinestraSola() {
    val max = WindowPolicy.maxWindows(30)
    assertThat(max).isEqualTo(1)
    assertThat(registry.admit(10, max)).isEqualTo(WindowAdmission.Admitted(WindowRole.PRIMARY))
    assertThat(registry.admit(20, max)).isEqualTo(WindowAdmission.Refused)
    assertThat(WindowPolicy.maxWindows(31)).isEqualTo(2)
    assertThat(WindowPolicy.maxWindows(36)).isEqualTo(2)
    assertThat(WindowPolicy.maxWindows(29)).isEqualTo(1)
  }

  // --- i task che il sistema non elenca piu' ------------------------------------------------------------------------

  @Test
  fun unPostoCheNessunoHaLiberato_siToglieQuandoIlSistemaNonLoElencaPiu() {
    registry.admit(10, 2)
    registry.admit(20, 2)

    // Il task 10 e' stato chiuso dai recenti mentre la sua activity era gia' distrutta: nessun onDestroy ce l'ha detto.
    assertThat(registry.admit(30, max = 2, live = setOf(20, 30))).isEqualTo(WindowAdmission.Admitted(WindowRole.SECONDARY))
    assertThat(registry.windows.value).containsExactly(20, 30).inOrder()
    assertThat(registry.roleOf(20)).isEqualTo(WindowRole.PRIMARY)
  }

  @Test
  fun senzaEnumerazioneNonSiTogliNiente_eLaFinestraCheChiedeResta() {
    registry.admit(10, 2)
    registry.admit(20, 2)
    // `live` sconosciuto (il sistema non risponde): nessuna potatura, quindi la terza e' rifiutata.
    assertThat(registry.admit(30, 2, live = null)).isEqualTo(WindowAdmission.Refused)
    // Un elenco che non nomina nemmeno la finestra che chiede: quella resta (e' lei che sta nascendo).
    assertThat(registry.hasRoom(max = 2, live = emptySet(), requester = 20)).isTrue()
    assertThat(registry.windows.value).containsExactly(20)
  }

  @Test
  fun hasRoom_diceSePossibileUnaSeconda_senzaOccuparla() {
    registry.admit(10, 2)
    assertThat(registry.hasRoom(2, live = setOf(10), requester = 10)).isTrue()
    assertThat(registry.count).isEqualTo(1)
    registry.admit(20, 2)
    assertThat(registry.hasRoom(2, live = setOf(10, 20), requester = 10)).isFalse()
    assertThat(registry.othersThan(10)).containsExactly(20)
  }

  @Test
  fun unaFinestraAddormentataNeiRecenti_contaComeFinestra() {
    // Dopo la morte del processo il task 99 e' ancora nei recenti: l'activity non c'e', ma e' una finestra dell'utente.
    registry.admit(10, 2)
    assertThat(registry.hasRoom(2, live = setOf(10, 99), requester = 10)).isFalse()
    assertThat(registry.admit(30, max = 2, live = setOf(10, 99, 30))).isEqualTo(WindowAdmission.Refused)
    // Si porta davanti quella che c'e': prima la viva, poi l'addormentata.
    assertThat(registry.othersThan(30, live = setOf(10, 99, 30))).containsExactly(10, 99).inOrder()
    assertThat(registry.othersThan(10, live = setOf(10, 99))).containsExactly(99)
    // Chiusa dai recenti (il sistema non la elenca piu'): c'e' posto.
    assertThat(registry.hasRoom(2, live = setOf(10), requester = 10)).isTrue()
  }

  // --- il ruolo nel tempo ----------------------------------------------------------------------------------------------

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun ilRuoloDellaSecondaCambiaQuandoSiChiudeLaPrincipale() = runTest(UnconfinedTestDispatcher()) {
    registry.admit(10, 2)
    registry.admit(20, 2)
    val roles = mutableListOf<WindowRole>()
    val job = launch { registry.roleFlow(20).toList(roles) }

    registry.release(10)
    assertThat(roles).containsExactly(WindowRole.SECONDARY, WindowRole.PRIMARY).inOrder()
    job.cancel()
  }

  @Test
  fun soloLaPrincipaleServeGliIngressi() {
    assertThat(WindowRole.PRIMARY.servesEntryRequests).isTrue()
    assertThat(WindowRole.SECONDARY.servesEntryRequests).isFalse()
  }

  // --- offerta del menu --------------------------------------------------------------------------------------------------

  @Test
  fun apriInUnaNuovaFinestra_soloDaEstesaODaPiuFinestre_eSoloDaAndroid12() {
    // Estesa (840 dp) o piu': si'. Compatta o media, finestra sola: no.
    assertThat(WindowPolicy.offersNewWindow(839, multiWindowMode = false, sdkInt = 34)).isFalse()
    assertThat(WindowPolicy.offersNewWindow(840, multiWindowMode = false, sdkInt = 34)).isTrue()
    assertThat(WindowPolicy.offersNewWindow(1280, multiWindowMode = false, sdkInt = 34)).isTrue()
    // Una finestra libera stretta (DeX, schermo diviso): si'.
    assertThat(WindowPolicy.offersNewWindow(500, multiWindowMode = true, sdkInt = 34)).isTrue()
    // Sotto Android 12: mai.
    assertThat(WindowPolicy.offersNewWindow(1280, multiWindowMode = true, sdkInt = 30)).isFalse()
    // La soglia e' quella di D12.
    assertThat(WindowPolicy.EXPANDED_DP).isEqualTo(GalleryWidth.EXPANDED.minDp)
  }

  // --- proprieta' -------------------------------------------------------------------------------------------------------------

  /** Sequenze a caso di richieste, rilasci e potature su cinque task: mai piu' di due finestre, mai doppioni, la principale e' sempre la piu' vecchia. */
  @Test
  fun proprietaSuSequenzeACaso() {
    val random = Random(1031)
    repeat(300) {
      val r = WindowRegistry()
      val model = mutableListOf<Int>()
      repeat(60) {
        val task = random.nextInt(1, 6)
        when (random.nextInt(4)) {
          0, 1 -> {
            val result = r.admit(task, max = 2)
            when {
              task in model -> assertThat(result).isInstanceOf(WindowAdmission.Known::class.java)
              model.size >= 2 -> assertThat(result).isEqualTo(WindowAdmission.Refused)
              else -> {
                assertThat(result).isInstanceOf(WindowAdmission.Admitted::class.java)
                model.add(task)
              }
            }
          }
          2 -> {
            r.release(task)
            model.remove(task)
          }
          else -> {
            val live = (1..5).filter { random.nextBoolean() }.toSet()
            val requester = random.nextInt(1, 6)
            r.hasRoom(2, live, requester)
            model.removeAll { it !in live && it != requester }
          }
        }
        assertThat(r.windows.value).containsExactlyElementsIn(model).inOrder()
        assertThat(r.windows.value.size).isAtMost(2)
        assertThat(r.windows.value.toSet().size).isEqualTo(r.windows.value.size)
        assertThat(r.primary).isEqualTo(model.firstOrNull())
      }
    }
  }
}
