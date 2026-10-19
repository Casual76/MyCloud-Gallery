package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import java.io.File
import org.junit.Test

/**
 * Fase 5B (P10, §4.3 "Log", D30 "Stile"): igiene del selettore e del provider, sui sorgenti di produzione.
 *  - **Log, notifiche e Diagnostica: solo numeri e codici.** Nessun log (nemmeno uno "innocuo"): un nome di file, un percorso, una share, un
 *    account o un id non hanno modo di arrivarci. I messaggi delle eccezioni, che l'altra app vede, sono frasi fisse senza dati;
 *  - nessun colore scritto a mano (i colori sono quelli del tema), nessuna frase scritta nel codice (stanno in `strings_integrations_*.xml`);
 *  - i contatori della Diagnostica sono un insieme chiuso di codici.
 */
class IntegrationHygieneTest {

  private val roots = listOf("src/main/kotlin/dev/pampa/mycloudgallery/feature/integrations/picker", "src/main/kotlin/dev/pampa/mycloudgallery/feature/integrations/provider")

  private fun sources(): List<File> = roots.flatMap { root ->
    File(root).also { check(it.isDirectory) { "Non trovo i sorgenti: $root" } }.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  /** Toglie i commenti (anche annidati) lasciando intatte le stringhe: un controllo sul codice non deve scattare per cio' che i commenti dicono. */
  private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    val newline = 10.toChar()
    val backslash = 92.toChar()
    val quote = 34.toChar()
    val apostrophe = 39.toChar()
    var i = 0
    var depth = 0
    var inString = false
    var inChar = false
    while (i < source.length) {
      val c = source[i]
      val next = if (i + 1 < source.length) source[i + 1] else ' '
      when {
        depth > 0 -> when {
          c == '/' && next == '*' -> { depth++; i += 2 }
          c == '*' && next == '/' -> { depth--; i += 2 }
          else -> { if (c == newline) out.append(c); i++ }
        }
        inString -> {
          out.append(c)
          if (c == backslash && i + 1 < source.length) { out.append(next); i += 2 } else { if (c == quote) inString = false; i++ }
        }
        inChar -> {
          out.append(c)
          if (c == backslash && i + 1 < source.length) { out.append(next); i += 2 } else { if (c == apostrophe) inChar = false; i++ }
        }
        c == '/' && next == '*' -> { depth = 1; i += 2 }
        c == '/' && next == '/' -> { while (i < source.length && source[i] != newline) i++ }
        c == quote -> { inString = true; out.append(c); i++ }
        c == apostrophe -> { inChar = true; out.append(c); i++ }
        else -> { out.append(c); i++ }
      }
    }
    return out.toString()
  }

  private fun violations(pattern: Regex, why: String): List<String> = sources().flatMap { file ->
    stripComments(file.readText()).lineSequence().withIndex().filter { pattern.containsMatchIn(it.value) }
      .map { "${file.name}:${it.index + 1}: $why" }.toList()
  }

  @Test
  fun nessunLogNeStampa() {
    val logging = Regex("""\bLog\s*\.\s*[a-z]\w*\s*\(|\bprintln\s*\(|\bSystem\s*\.\s*(out|err)\b|\bprintStackTrace\b|\bTimber\b|\blogcat\b""")
    assertThat(violations(logging, "log o stampa (solo numeri e codici, e solo dai contatori)")).isEmpty()
  }

  @Test
  fun nessunColoreScrittoAMano() {
    val colors = Regex("""\bColor\s*\(\s*0x|\bColor\s*\.\s*(Red|Green|Blue|White|Black|Gray|Grey|Yellow|Cyan|Magenta|Transparent|parseColor)\b|"#[0-9a-fA-F]{3,8}"|\bargb\s*\(""")
    assertThat(violations(colors, "colore scritto a mano (usa il tema)")).isEmpty()
    val resources = File("src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
    assertThat(resources).isNotEmpty()
    for (file in resources) {
      val text = file.readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
      assertThat(Regex("""<color\b|#[0-9a-fA-F]{6,8}\b""").containsMatchIn(text)).isFalse()
    }
  }

  @Test
  fun nessunaFraseNelCodice() {
    val literal = Regex("""\b(text|contentDescription|title|subtitle|label|summary)\s*=\s*"[^"$]*[A-Za-z]{3,}""")
    // Le sole stringhe ammesse nel codice del selettore sono formati numerici e id di costanti: nessuna frase per l'utente.
    assertThat(violations(literal, "frase scritta nel codice (usa una risorsa)")).isEmpty()
    assertThat(File("src/main/res/values/strings_integrations_picker.xml").isFile).isTrue()
    assertThat(File("src/main/res/values/strings_integrations_provider.xml").isFile).isTrue()
  }

  @Test
  fun iMessaggiDelleEccezioniSonoFrasiFisseSenzaDati() {
    val templated = Regex("""(FileNotFoundException|SecurityException|IllegalStateException|IllegalArgumentException|OperationCanceledException|TimeoutException)\s*\(\s*"[^"]*\$""")
    assertThat(violations(templated, "dato nel messaggio di un'eccezione")).isEmpty()
    val concatenated = Regex("""(FileNotFoundException|SecurityException)\s*\([^)]*\+""")
    assertThat(violations(concatenated, "dato concatenato in un'eccezione")).isEmpty()
  }

  @Test
  fun iContatoriSonoCodiciFissi() {
    val codes = IntegrationCounter.entries.map { it.code }
    assertThat(codes.toSet()).hasSize(codes.size)
    for (code in codes) assertThat(code).matches("[a-z]+(_[a-z]+)*")
    assertThat(IntegrationCounters.snapshot().keys).containsExactlyElementsIn(codes)
    IntegrationCounters.reset()
    IntegrationCounters.add(IntegrationCounter.PICKER_OPENED, 3)
    IntegrationCounters.add(IntegrationCounter.PICKER_OPENED, -5) // un numero negativo non toglie niente
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_OPENED)).isEqualTo(3L)
    assertThat(IntegrationCounters.snapshot().values.all { it >= 0 }).isTrue()
    IntegrationCounters.reset()
  }

  @Test
  fun ilSelettoreNonLeggeIlCatalogoDeiNascostiNeScriveSulTelefono() {
    // Il selettore e il provider conoscono solo la vista della Libreria: mai la porta dei nascosti (E-H1), mai le richieste di sistema che
    // scrivono sul telefono (cestino, eliminazione, scrittura).
    val forbidden = Regex("""\b(HiddenLibrary|hiddenRows|HiddenAccessGate|PhoneMediaRewriter|createWriteRequest|createDeleteRequest|createTrashRequest|PhoneConfirmation\w*|MANAGE_MEDIA)\b""")
    assertThat(violations(forbidden, "il selettore tocca i nascosti o scrive sul telefono")).isEmpty()
  }
}
