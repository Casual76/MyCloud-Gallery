package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AZ-1 della verifica funzionale della Fase 6: fra l'arresto dell'AI e quello della galleria una tappa in corso riapriva il motore delle immagini
 * (circa 800 MB) subito dopo `release()`. Dopo `stop` il motore non si riapre piu': `embed` si ferma, `release` resta sempre lecito.
 */
class StoppableImageEmbedderTest {

  private val image = AiTestInputs.image("cane spiaggia")

  @Test
  fun finoAlloStopSiCalcolaComeSempre() = runBlocking<Unit> {
    val inner = FakeImageEmbedder()
    val embedder = StoppableImageEmbedder(inner)
    assertThat(embedder.embed(listOf(image))).hasSize(1)
    assertThat(inner.images).isEqualTo(1)
  }

  @Test
  fun dopoLoStopIlMotoreNonSiRiapre() = runBlocking<Unit> {
    val inner = FakeImageEmbedder()
    val embedder = StoppableImageEmbedder(inner)
    embedder.embed(listOf(image))
    embedder.stopped = true
    embedder.release()

    // La tappa che era in corso arriva al prossimo `embed`: si ferma, senza toccare il motore (che riaprirebbe il modello).
    assertThrows(CancellationException::class.java) { runBlocking { embedder.embed(listOf(image)) } }
    assertThat(inner.images).isEqualTo(1)
  }

  @Test
  fun rilasciareDopoLoStopELecitoEIdempotente() = runBlocking<Unit> {
    val embedder = StoppableImageEmbedder(FakeImageEmbedder())
    embedder.stopped = true
    embedder.release()
    embedder.release()
  }
}
