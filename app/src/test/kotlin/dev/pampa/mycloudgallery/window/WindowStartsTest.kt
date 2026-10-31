package dev.pampa.mycloudgallery.window

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import org.junit.Test

/** La pagina da cui parte una finestra nuova (D16): un evento interno, una volta sola, fresco. Mai nell'intent. */
class WindowStartsTest {

  private val starts = WindowStarts()
  private val album = AlbumId.random()

  @Test
  fun laFinestraNuovaPrendeLaPaginaUnaVoltaSola() {
    starts.offer(WindowTarget.Album(album), nowMs = 1_000)
    assertThat(starts.isPending).isTrue()

    assertThat(starts.claim(nowMs = 1_500)).isEqualTo(WindowTarget.Album(album))
    assertThat(starts.claim(nowMs = 1_500)).isNull()
    assertThat(starts.isPending).isFalse()
  }

  @Test
  fun senzaRichiestaSiParteDallaLibreria() {
    assertThat(starts.claim(nowMs = 5)).isNull()
  }

  @Test
  fun unaRichiestaVecchiaNonSiEredita_eNonRestaInGiro() {
    starts.offer(WindowTarget.Map, nowMs = 1_000)
    // Un'altra app avvia una finestra a freddo, molto dopo: parte dalla Libreria, e la richiesta e' sparita.
    assertThat(starts.claim(nowMs = 1_000 + WindowStarts.MAX_AGE_MS + 1)).isNull()
    assertThat(starts.isPending).isFalse()
  }

  @Test
  fun alLimiteDelTempoLaRichiestaValeAncora() {
    starts.offer(WindowTarget.Library, nowMs = 1_000)
    assertThat(starts.claim(nowMs = 1_000 + WindowStarts.MAX_AGE_MS)).isEqualTo(WindowTarget.Library)
  }

  @Test
  fun unOrologioCheVaIndietro_nonConsegnaLaRichiesta() {
    starts.offer(WindowTarget.Map, nowMs = 5_000)
    assertThat(starts.claim(nowMs = 4_000)).isNull()
  }

  @Test
  fun unaRichiestaNuovaSostituisceLaVecchia() {
    val memory = MemoryId("0123456789abcdefghjkmnpqr0")
    starts.offer(WindowTarget.Album(album), nowMs = 1)
    starts.offer(WindowTarget.Memory(memory), nowMs = 2)
    assertThat(starts.claim(3)).isEqualTo(WindowTarget.Memory(memory))
  }

  @Test
  fun unaFinestraCheNonNasceNonLasciaLaRichiesta() {
    starts.offer(WindowTarget.Map, nowMs = 1)
    starts.cancel()
    assertThat(starts.claim(2)).isNull()
  }

  @Test
  fun laPaginaDellaFinestraSiPrendeUnaVoltaSola() {
    val request = WindowStartRequest(WindowTarget.Map)
    assertThat(request.take()).isEqualTo(WindowTarget.Map)
    assertThat(request.take()).isNull()
    assertThat(WindowStartRequest(null).take()).isNull()
  }
}
