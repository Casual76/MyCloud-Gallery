package dev.pampa.mycloudgallery.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CE-9 (E-Q11): la prima ricerca a freddo **non aspetta** il motore delle query. Con il motore ancora da caricare (alcuni secondi sull'S25) la ricerca esce
 * subito con filtri e testo, il motore si carica in un lavoro a parte, e quando e' pronto `readiness` torna `Ready` (Cerca rifa' la ricerca e aggiunge il
 * contenuto). Prima ogni ricerca aspettava il caricamento prima ancora di applicare i filtri. Tempo virtuale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LazyQueryEmbeddingTest {

  @Test
  fun aFreddoLaRicercaNonAspettaIlMotoreEQuestoSiCaricaInUnLavoroAParte() = runTest {
    val embedder = FakeTextEmbedder(coldStartMs = 5_000)
    val lazy = LazyQueryEmbedding(backgroundScope, { true }, { embedder })
    assertThat(lazy.readiness.value).isEqualTo(SearchReadiness.Ready)

    // Il motore e' freddo: risposta immediata (nessun vettore), e il tempo non e' passato.
    val before = testScheduler.currentTime
    assertThat(lazy.vectorIfLoaded("cane")).isNull()
    assertThat(testScheduler.currentTime).isEqualTo(before)
    assertThat(lazy.readiness.value).isEqualTo(SearchReadiness.PreparingContent)

    // In un lavoro a parte il motore si carica e si scalda...
    runCurrent()
    assertThat(lazy.readiness.value).isEqualTo(SearchReadiness.PreparingContent)
    advanceTimeBy(5_001)
    runCurrent()
    assertThat(embedder.warmUps).isEqualTo(1)
    assertThat(lazy.loaded).isTrue()
    assertThat(lazy.readiness.value).isEqualTo(SearchReadiness.Ready)

    // ...e da quel momento le ricerche hanno il vettore.
    assertThat(lazy.vectorIfLoaded("cane")).isNotNull()
    assertThat(embedder.warmUps).isEqualTo(1)
  }

  @Test
  fun caricareDueVolteDiSeguitoNonRiapreIlMotore() = runTest {
    val embedder = FakeTextEmbedder(coldStartMs = 1_000)
    val lazy = LazyQueryEmbedding(backgroundScope, { true }, { embedder })

    lazy.preload()
    lazy.preload()
    lazy.vectorIfLoaded("a")
    advanceTimeBy(1_001)
    runCurrent()
    lazy.preload()
    runCurrent()

    assertThat(embedder.warmUps).isEqualTo(1)
  }

  @Test
  fun senzaIlModelloNienteMotoreESiDiceModelloAssente() = runTest {
    var built = 0
    val lazy = LazyQueryEmbedding(backgroundScope, { false }, { built++; FakeTextEmbedder() })

    assertThat(lazy.vectorIfLoaded("cane")).isNull()
    lazy.preload()
    runCurrent()

    assertThat(built).isEqualTo(0)
    assertThat(lazy.readiness.value).isEqualTo(SearchReadiness.FiltersAndTextOnly(modelMissing = true))
  }

  @Test
  fun inattivoTreMinutiSiRilasciaEIlProssimoUsoLoRiapre() = runTest {
    val embedder = FakeTextEmbedder()
    val lazy = LazyQueryEmbedding(backgroundScope, { true }, { embedder }, idle = 3.minutes)
    lazy.preload()
    runCurrent()
    assertThat(lazy.loaded).isTrue()

    advanceTimeBy(3.minutes.inWholeMilliseconds + 1)
    runCurrent()
    assertThat(lazy.loaded).isFalse()

    // Freddo di nuovo: una ricerca non aspetta, e il motore riparte.
    assertThat(lazy.vectorIfLoaded("cane")).isNull()
    runCurrent()
    assertThat(lazy.loaded).isTrue()
    assertThat(embedder.warmUps).isEqualTo(2)
  }

  @Test
  fun dopoLAzzeramentoNonSiCaricaPiuNiente() = runTest {
    val embedder = FakeTextEmbedder()
    val lazy = LazyQueryEmbedding(backgroundScope, { true }, { embedder })
    lazy.stop()

    lazy.preload()
    runCurrent()

    assertThat(lazy.vectorIfLoaded("cane")).isNull()
    assertThat(embedder.warmUps).isEqualTo(0)
  }
}
