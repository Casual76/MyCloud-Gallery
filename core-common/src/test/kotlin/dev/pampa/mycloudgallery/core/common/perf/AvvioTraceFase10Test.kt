package dev.pampa.mycloudgallery.core.common.perf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Le sezioni di traccia della Fase 10 (P9, `docs/fase10-progetto.md` §5.4): nomi fissi, mai dati (D39); gli stessi nomi che `:benchmark` legge dalla traccia
 * (copiati in `WidgetBenchSupport.kt`: se uno cambia e l'altro no, la prova vedrebbe "sezione assente" e non un numero sbagliato, ma e' un errore da non lasciar passare);
 * e senza `android.os.Trace` (la JVM) il lavoro misurato gira lo stesso e restituisce il suo valore.
 */
class AvvioTraceFase10Test {

  private val names = listOf(AvvioTrace.WIDGET_UPDATE, AvvioTrace.WIDGET_BLANK)

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
    val bench = File(root, "benchmark/src/main/kotlin/dev/pampa/mycloudgallery/benchmark/WidgetBenchSupport.kt").readText()
    assertThat(bench).contains("\"${AvvioTrace.WIDGET_UPDATE}\"")
    assertThat(bench).contains("\"${AvvioTrace.WIDGET_BLANK}\"")
  }

  @Test
  fun ilMotoreDelWidgetEmetteLeDueSezioni() {
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val engine = File(root, "feature-widget/src/main/kotlin/dev/pampa/mycloudgallery/feature/widget/refresh/WidgetEngine.kt").readText()
    // Il giro di aggiornamento e lo svuotamento (nascosto o ricordo scartato) sono le due misure del benchmark `widget`: se qualcuno toglie la sezione la prova non vede piu' niente.
    assertThat(engine).contains("AvvioTrace.asyncSection(AvvioTrace.WIDGET_UPDATE)")
    assertThat(engine.split("AvvioTrace.asyncSection(AvvioTrace.WIDGET_BLANK)").size - 1).isEqualTo(2)
  }

  @Test
  fun senzaTracciaIlLavoroGiraLoStesso() = runBlocking {
    assertThat(AvvioTrace.asyncSection(AvvioTrace.WIDGET_UPDATE) { 7 }).isEqualTo(7)
    assertThat(AvvioTrace.asyncSection(AvvioTrace.WIDGET_BLANK) { "ok" }).isEqualTo("ok")
  }
}
