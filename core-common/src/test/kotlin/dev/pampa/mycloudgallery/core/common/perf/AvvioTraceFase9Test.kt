package dev.pampa.mycloudgallery.core.common.perf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Le sezioni e il contatore di traccia della Fase 9 (P12, `docs/fase9-progetto.md` §5.4): nomi fissi, mai dati (D33); gli stessi nomi che `:benchmark` legge dalla traccia
 * (copiati in `MemoriesBenchSupport.kt` e `MapBenchSupport.kt`: se uno cambia e l'altro no, le prove vedrebbero "sezione assente" e non un numero sbagliato, ma e' un
 * errore da non lasciar passare); e senza `android.os.Trace` (la JVM) il lavoro misurato gira lo stesso e restituisce il suo valore.
 */
class AvvioTraceFase9Test {

  private val names = listOf(
    AvvioTrace.MEMORIES_GENERATE,
    AvvioTrace.MEMORIES_GENERATE_LIGHT,
    AvvioTrace.MAP_CLUSTERS,
    AvvioTrace.MAP_DRAW_US,
  )

  @Test
  fun inomiSonoFissiEDistintiSenzaNessunDato() {
    assertThat(names.distinct()).hasSize(names.size)
    for (name in names) {
      assertWithMessage(name).that(name).startsWith(AvvioTrace.PREFIX)
      // Solo lettere, punti e i due punti del prefisso: nessun numero, id, titolo o luogo puo' finire in un nome (Trace taglia a 127 caratteri).
      assertWithMessage(name).that(name).matches("^MCG:[A-Za-z]+(\\.[A-Za-z]+)+$")
    }
  }

  @Test
  fun ilBenchmarkLeggeGliStessiNomi() {
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val dir = File(root, "benchmark/src/main/kotlin/dev/pampa/mycloudgallery/benchmark")
    val memories = File(dir, "MemoriesBenchSupport.kt").readText()
    val map = File(dir, "MapBenchSupport.kt").readText()
    assertThat(memories).contains("\"${AvvioTrace.MEMORIES_GENERATE}\"")
    assertThat(memories).contains("\"${AvvioTrace.MEMORIES_GENERATE_LIGHT}\"")
    assertThat(map).contains("\"${AvvioTrace.MAP_CLUSTERS}\"")
    assertThat(map).contains("\"${AvvioTrace.MAP_DRAW_US}\"")
  }

  @Test
  fun senzaTracciaIlLavoroGiraLoStesso() = runBlocking {
    val value = AvvioTrace.asyncSection(AvvioTrace.MAP_CLUSTERS) { 42 }
    assertThat(value).isEqualTo(42)
    AvvioTrace.counter(AvvioTrace.MAP_DRAW_US, 123L)
    assertThat(AvvioTrace.section(AvvioTrace.MEMORIES_GENERATE) { "ok" }).isEqualTo("ok")
  }
}
