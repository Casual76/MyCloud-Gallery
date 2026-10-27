package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import org.junit.Test

/**
 * Il selettore dei media dopo la morte del processo (Fase 7, revisione di fine fase, AA-3): la richiamata di chi l'ha aperto sta in memoria e la rotta del
 * selettore no. Prima "Scegli" tornava indietro senza dire niente e la scelta andava persa; adesso o arriva (richiamata viva), o si recupera ("Aggiungi
 * foto" a un album: diventa la richiesta "Aggiungi ad album" con l'album gia' scelto), o lo si dice.
 */
class PickRelayTest {

  private val requests = AlbumRequests()
  private val relay = PickRelay(requests)
  private val album = AlbumId(UUID(1L, 1L).toString())
  private val picked = listOf(MediaId(UUID(2L, 1L).toString()), MediaId(UUID(2L, 2L).toString()))

  @Test
  fun laRichiamataVivaRiceveLaSceltaUnaVoltaSola() {
    var got: List<MediaId>? = null
    relay.start { got = it }

    assertThat(relay.picked(picked, album.value)).isEqualTo(PickOutcome.Delivered)
    assertThat(got).isEqualTo(picked)
    // Una volta sola: un secondo «Scegli» non la ritrova.
    got = null
    assertThat(relay.picked(picked, null)).isEqualTo(PickOutcome.Lost)
    assertThat(got).isNull()
  }

  @Test
  fun senzaRichiamataUnAggiungiFotoSiRecuperaComeAggiungiAdAlbum() {
    // Il processo e' morto: la rotta c'e' (con l'album), la richiamata no.
    assertThat(relay.picked(picked, album.value)).isEqualTo(PickOutcome.Recovered)
    val request = checkNotNull(requests.addToAlbum.value)
    assertThat(request.album).isEqualTo(album)
    assertThat(request.media).containsExactlyElementsIn(picked).inOrder()
  }

  @Test
  fun leFotoDEsempioDiUnoSmartSenzaRichiamataSiDiconoPerse() {
    assertThat(relay.picked(picked, null)).isEqualTo(PickOutcome.Lost)
    assertThat(requests.addToAlbum.value).isNull()
    // Un id d'album che non e' un id non fa crollare niente.
    assertThat(relay.picked(picked, "non-un-id")).isEqualTo(PickOutcome.Lost)
  }

  @Test
  fun unaSceltaVuotaNonFaNiente() {
    relay.start { throw AssertionError("nessuna scelta da consegnare") }
    assertThat(relay.picked(emptyList(), album.value)).isEqualTo(PickOutcome.Nothing)
    assertThat(requests.addToAlbum.value).isNull()
  }

  @Test
  fun annullareTogliLaRichiamata() {
    relay.start { throw AssertionError("annullata") }
    relay.cancel()
    assertThat(relay.picked(picked, album.value)).isEqualTo(PickOutcome.Recovered)
  }
}
