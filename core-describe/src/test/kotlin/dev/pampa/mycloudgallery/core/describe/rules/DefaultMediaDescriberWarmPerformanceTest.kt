package dev.pampa.mycloudgallery.core.describe.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.describe.testing.FakeA11yActivity
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePeople
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePlaces
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribeText
import dev.pampa.mycloudgallery.core.describe.testing.ItalianDescribeStrings
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 10 (P9, `docs/fase10-progetto.md` §5.4, "JVM (a ogni `check.ps1`)"): **`warm` di 60 celle in meno di 20 ms** (una schermata piena di una Libreria vista da un
 * lettore di schermo). La descrizione a cache calda (< 1 ms) e' in `DefaultMediaDescriberTest`; qui e' il riscaldamento, cioe' il lavoro che parte 150 ms dopo che lo
 * scorrimento si ferma e che non deve pesare su un fotogramma.
 *
 * Le porte sono finte e rispondono **da dati in memoria**, come devono le porte vere di P13 (P7: "una sola query per giro"): quindi il tempo e' quello del descrittore
 * (ripulire nomi e testo, tenerli in cache), non quello del database. Misura: la **mediana** di molte corse dopo il riscaldamento della JVM (non una corsa sola: sotto il
 * carico di `check.ps1` una corsa puo' prendersi un GC), con il tetto del §5.4.
 */
class DefaultMediaDescriberWarmPerformanceTest {

  private fun id(n: Int) = MediaId(UUID(90L, n.toLong()).toString())

  @Test
  fun warmDiSessantaCelleSottoVentiMillisecondi() = runBlocking {
    val media = (1..CELLS).map { id(it) }
    val names = media.associateWith { listOf("Anna", "Marco", "Luca", "Sara", "Giulia") }
    val texts = media.associateWith { "Biglietto ferroviario Firenze Roma andata e ritorno del 12 marzo con posto 14 carrozza 3 e un altro po' di testo" }
    val describer = DefaultMediaDescriber(
      FakeDescribePlaces(emptyMap()), FakeDescribePeople(names), FakeDescribeText(texts), ItalianDescribeStrings.VALUE, FakeA11yActivity(),
    )
    // Il riscaldamento della JVM: lo stesso lavoro, non misurato.
    repeat(WARMUP) { describer.warm(media) }
    val times = LongArray(RUNS)
    for (run in 0 until RUNS) {
      // Ogni corsa riparte da una cache vuota: e' il primo `warm` di una schermata, il caso peggiore.
      describer.forget()
      val start = System.nanoTime()
      describer.warm(media)
      times[run] = System.nanoTime() - start
    }
    assertThat(describer.cached).isEqualTo(CELLS)
    times.sort()
    val medianMs = times[RUNS / 2] / 1_000_000.0
    val worstMs = times.last() / 1_000_000.0
    // La misura resta nel rapporto della prova (docs/decisioni.md, Fase 10 P9).
    println("warm di $CELLS celle: mediana $medianMs ms, la peggiore $worstMs ms (tetto $LIMIT_MS ms)")
    assertWithMessage("warm di $CELLS celle: mediana %s ms (la peggiore %s ms), tetto %s ms", medianMs, worstMs, LIMIT_MS).that(medianMs).isLessThan(LIMIT_MS)
  }

  private companion object {
    /** Una schermata piena: 60 celle in vista (§5.4). */
    const val CELLS = 60
    const val WARMUP = 300
    const val RUNS = 301
    const val LIMIT_MS = 20.0
  }
}
