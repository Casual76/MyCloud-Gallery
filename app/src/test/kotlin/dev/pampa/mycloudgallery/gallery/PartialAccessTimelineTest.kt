package dev.pampa.mycloudgallery.gallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, R5: con l'accesso "solo foto selezionate" la Libreria non mostra **mai** elementi
 * fuori dalla selezione, nemmeno all'avvio a freddo, quando l'indice e l'istantanea ci sono gia' ma la prima lettura non ha
 * ancora detto cosa e' accessibile (`selection` null). Prima null voleva dire "tutto".
 */
class PartialAccessTimelineTest {

  private val library = SyntheticLibrary(count = 60)
  private val base = FakeTimelineRepository(library.snapshot)
  private val selection = MutableStateFlow<Set<MediaId>?>(null)
  private val access = MutableStateFlow(MediaAccess.PARTIAL)
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val timeline = PartialAccessTimeline(base, selection, access, scope)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun sized(filter: LibraryFilter = LibraryFilter.ALL) = timeline.view(filter).value.size

  private fun someIds(count: Int): Set<MediaId> = (0 until count).map { library.snapshot.idAt(it) }.toSet()

  @Test
  fun conAccessoParzialeEPrimaLetturaNonFinitaLaVistaEVuota() {
    // L'istantanea e il catalogo hanno 60 elementi, ma non si sa ancora quali sono accessibili: niente, nemmeno un attimo.
    assertThat(base.view(LibraryFilter.ALL).value.size).isEqualTo(60)
    assertThat(selection.value).isNull()
    assertThat(sized()).isEqualTo(0)
    assertThat(timeline.snapshot.value).isNull()
  }

  @Test
  fun lAccessoParzialeEVuotoGiaALaPrimaVistaRichiesta() {
    // Il valore iniziale della vista (prima di qualunque emissione) e' gia' quello chiuso.
    val fresh = PartialAccessTimeline(base, MutableStateFlow(null), MutableStateFlow(MediaAccess.PARTIAL), scope)
    assertThat(fresh.view(LibraryFilter.ALL).value.size).isEqualTo(0)
    assertThat(fresh.view(LibraryFilter.VIDEOS).value.size).isEqualTo(0)
  }

  @Test
  fun senzaAccessoAncheSenzaSelezioneLaVistaEVuota() {
    access.value = MediaAccess.NONE

    assertThat(sized()).isEqualTo(0)
    assertThat(timeline.snapshot.value).isNull()
  }

  @Test
  fun conAccessoCompletoELaVistaIntera() {
    access.value = MediaAccess.FULL

    assertThat(sized()).isEqualTo(60)
    assertThat(timeline.snapshot.value).isNotNull()
  }

  @Test
  fun appenaLaPrimaLetturaDiceCosaEAccessibileSiVedeSoloQuello() {
    assertThat(sized()).isEqualTo(0)

    selection.value = someIds(3)

    assertThat(sized()).isEqualTo(3)
    // Non solo la vista di tutti: ogni filtro lavora sulla stessa selezione.
    assertThat(sized(LibraryFilter.VIDEOS)).isAtMost(3)
    assertThat(timeline.snapshot.value).isNull()
  }

  @Test
  fun unaSelezioneVuotaEUnaLibreriaVuota() {
    selection.value = emptySet()

    assertThat(sized()).isEqualTo(0)
  }

  @Test
  fun tornandoAllAccessoParzialeSenzaSelezioneSiRichiude() {
    access.value = MediaAccess.FULL
    assertThat(sized()).isEqualTo(60)

    // Il permesso passa a "solo foto selezionate" mentre l'app e' aperta: prima che la lettura risponda, niente.
    access.value = MediaAccess.PARTIAL

    assertThat(sized()).isEqualTo(0)
  }
}
