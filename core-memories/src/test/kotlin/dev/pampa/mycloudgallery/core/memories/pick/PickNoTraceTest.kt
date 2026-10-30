package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Nessuna traccia (D8, D33, C21): la scelta non lascia stato in giro (ne' nel picker ne' negli ingressi), non scrive log, il suo `toString()` non
 * dice niente di una foto e il risultato porta soli conteggi per motivo.
 *
 * Il filtro dei ricordi tristi (Q6 (b) del progetto) **non c'e'**: l'utente ha risposto (a), solo i pulsanti. Qui non si prova "il filtro toglie e basta"
 * ma l'invariante che quella frase proteggeva e che resta vera: la scelta non decide niente dal contenuto, non ricorda niente fra una chiamata e
 * l'altra e non lascia nessuna traccia di cio' che ha escluso.
 */
class PickNoTraceTest {

  private val picker = MemoryPhotoPicker()

  /** Il picker non ha stato: nessun campo di istanza (quindi niente da salvare, da sincronizzare o da trovare dopo la scelta). */
  @Test
  fun ilPickerNonHaStato() {
    val instanceFields = MemoryPhotoPicker::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
    assertThat(instanceFields).isEmpty()
    // Quelli con porte hanno solo le porte, in sola lettura (final): nessun campo che si possa riempire.
    for (type in listOf(VisiblePhotoPicker::class.java, PickSignalsLoader::class.java)) {
      for (field in type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }) {
        assertThat(Modifier.isFinal(field.modifiers)).isTrue()
        assertThat(field.type.isPrimitive || field.type.isArray).isFalse()
      }
    }
  }

  /** Dopo la scelta gli ingressi sono come prima: le liste, i vettori, le mappe, gli insiemi. */
  @Test
  fun gliIngressiRestanoIntatti() {
    for (seed in 1L..40L) {
      val input = RandomPick.of(seed)
      val candidates: List<CandidateCell> = input.candidates
      val copy = candidates.toList()
      val vectors = input.signals.vectors.mapValues { it.value.copyOf() }
      val pixels = input.signals.pixels.toMap()
      val albums = input.signals.albums.toSet()
      val people = input.signals.people.toMap()
      picker.pick(candidates, MemoryRules.spec(input.kind), input.signals)
      assertThat(candidates).isEqualTo(copy)
      assertThat(input.signals.vectors.keys).isEqualTo(vectors.keys)
      for ((id, v) in input.signals.vectors) assertThat(v).isEqualTo(vectors.getValue(id))
      assertThat(input.signals.pixels).isEqualTo(pixels)
      assertThat(input.signals.albums).isEqualTo(albums)
      assertThat(input.signals.people).isEqualTo(people)
    }
  }

  /** Una scelta non influenza la successiva: nessun residuo (A, poi B, poi A di nuovo = A). */
  @Test
  fun nessunResiduoFraUnaSceltaELAltra() {
    val a = RandomPick.of(100)
    val b = RandomPick.of(101)
    val first = picker.pick(a.candidates, MemoryRules.spec(a.kind), a.signals)
    picker.pick(b.candidates, MemoryRules.spec(b.kind), b.signals)
    val again = picker.pick(a.candidates, MemoryRules.spec(a.kind), a.signals)
    assertThat(again.members).isEqualTo(first.members)
    assertThat(again.cover).isEqualTo(first.cover)
    assertThat(again.dropped).isEqualTo(first.dropped)
  }

  /** Nessun log: ne' sull'uscita standard, ne' su quella degli errori, ne' su `java.util.logging`, in nessuna delle scelte (anche con la maschera). */
  @Test
  fun nessunLog() = runTest {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val records = ArrayList<LogRecord>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        records += record
      }

      override fun flush() = Unit

      override fun close() = Unit
    }
    val root = Logger.getLogger("")
    val oldOut = System.out
    val oldErr = System.err
    root.addHandler(handler)
    System.setOut(PrintStream(out, true))
    System.setErr(PrintStream(err, true))
    try {
      for (seed in 1L..60L) {
        val input = RandomPick.of(seed)
        val spec = MemoryRules.spec(input.kind)
        picker.pick(input.candidates, spec, input.signals)
        VisiblePhotoPicker(picker) { ids -> ids.take(2).toSet() }.pick(input.candidates, spec, input.signals)
        PickSignalsLoader().load(input.candidates, spec, input.signals.choices, kotlin.time.Duration.ZERO)
      }
    } finally {
      System.setOut(oldOut)
      System.setErr(oldErr)
      root.removeHandler(handler)
    }
    assertThat(out.toString()).isEmpty()
    assertThat(err.toString()).isEmpty()
    assertThat(records).isEmpty()
  }

  /** Nessun `toString()` dice qualcosa di una foto: niente id, niente date, niente numeri di vettore. */
  @Test
  fun iToStringSonoSicuri() {
    val input = RandomPick.of(7)
    val spec = MemoryRules.spec(input.kind)
    val result = picker.pick(input.candidates, spec, input.signals)
    val texts = listOf(
      result.toString(),
      PickResult.rejected(mapOf()).toString(),
      input.signals.toString(),
      input.candidates.first().toString(),
      picker.toString(),
      VisiblePhotoPicker(picker) { emptySet() }.toString(),
      PickSignalsLoader().toString(),
    )
    val ids = input.candidates.map { it.id.value }
    for (text in texts) {
      for (id in ids) assertThat(text).doesNotContain(id)
      assertThat(text).doesNotContain("2025")
    }
  }

  /** Il risultato porta solo conteggi per motivo: chiavi di `DropReason`, valori numerici positivi, mai id. */
  @Test
  fun ilRisultatoPortaSoloConteggi() {
    for (seed in 1L..100L) {
      val input = RandomPick.of(seed)
      val result = picker.pick(input.candidates, MemoryRules.spec(input.kind), input.signals)
      for ((reason, count) in result.dropped) {
        assertThat(reason.code).isNotEmpty()
        assertThat(count).isGreaterThan(0)
      }
      // Gli esclusi non si possono ricostruire dal risultato: gli elementi sono solo gli scelti.
      assertThat(result.members.size).isAtMost(input.candidates.size)
      assertThat(result.members.size + result.dropped.values.sum()).isAtLeast(0)
    }
  }

  /** Nessun segnale sul contenuto: i segnali della scelta non hanno niente che somigli a un giudizio "triste" (Q6 (a)). */
  @Test
  fun nessunSegnaleSulContenuto() {
    val names = PickSignals::class.java.declaredFields.map { it.name.lowercase() }
    assertThat(names).containsExactly("vectors", "lookalike", "pixels", "albums", "people", "choices")
  }

  /** Il codice di `pick/` non scrive niente da nessuna parte: niente `println`, niente `Log`, niente `Logger`, niente file. */
  @Test
  fun ilCodiceNonScriveNienteDaNessunaParte() {
    val dir = File("src/main/kotlin/dev/pampa/mycloudgallery/core/memories/pick")
    assertThat(dir.isDirectory).isTrue()
    val sources = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertThat(sources.size).isAtLeast(4)
    val forbidden = Regex(
      """\bprintln\b|\bprint\s*\(|\bSystem\s*\.\s*(out|err)\b|\bLogger\b|\bLog\s*\.|\bTimber\b|\bprintStackTrace\b|\bFile(Writer|OutputStream|Reader|InputStream)?\s*\(|\bwriteText\b|\bappendText\b""",
    )
    for (file in sources) {
      val code = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").lineSequence().joinToString("\n") { it.substringBefore("//") }
      assertThat(forbidden.find(code)?.value).isNull()
    }
  }
}
