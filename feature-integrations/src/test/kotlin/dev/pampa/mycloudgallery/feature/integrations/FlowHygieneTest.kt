package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.share.CounterCode
import dev.pampa.mycloudgallery.feature.integrations.share.EditCounter
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCounter
import java.io.File
import org.junit.Test

/**
 * Fase 5B (P11, §4.3 "Log", D30 "Stile", C17, C18): igiene di ricevere, "Modifica con…" e condividere senza posizione, sui sorgenti di produzione.
 *  - **Log, notifiche e Diagnostica: solo numeri e codici.** Nessun log (nemmeno uno "innocuo"): un nome di file, un percorso, una share, un account,
 *    un id o un'impronta non hanno modo di arrivarci. I messaggi delle eccezioni sono frasi fisse senza dati; i contatori sono un insieme chiuso;
 *  - nessun colore scritto a mano (i colori sono quelli del tema), nessuna frase scritta nel codice (stanno in `strings_integrations_*.xml`);
 *  - ricevere, modificare e condividere non scrivono sul telefono (niente richieste di sistema di scrittura o cestino), non nominano i nascosti (che il NAS non si tocchi
 *    lo prova `Phase5BArchitectureTest`).
 */
class FlowHygieneTest {

  private val base = "src/main/kotlin/dev/pampa/mycloudgallery/feature/integrations"
  private val roots = listOf("$base/receive", "$base/edit", "$base/share")

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

  private fun violations(pattern: Regex, why: String, skip: (File) -> Boolean = { false }): List<String> = sources().filterNot(skip).flatMap { file ->
    stripComments(file.readText()).lineSequence().withIndex().filter { pattern.containsMatchIn(it.value) }
      .map { "${file.name}:${it.index + 1}: $why" }.toList()
  }

  @Test
  fun nessunLogNeStampa() {
    val logging = Regex("""\bLog\s*\.\s*[a-z]\w*\s*\(|\bGalleryLog\b|\bprintln\s*\(|\bSystem\s*\.\s*(out|err)\b|\bprintStackTrace\b|\bTimber\b|\blogcat\b""")
    assertThat(violations(logging, "log o stampa (solo numeri e codici, e solo dai contatori)")).isEmpty()
  }

  @Test
  fun nessunColoreScrittoAMano() {
    val colors = Regex("""\bColor\s*\(\s*0x|\bColor\s*\.\s*(Red|Green|Blue|White|Black|Gray|Grey|Yellow|Cyan|Magenta|Transparent|parseColor)\b|"#[0-9a-fA-F]{3,8}"|\bargb\s*\(""")
    // I fake di prova costruiscono bitmap di prova con i colori di `Color`: non sono schermate.
    assertThat(violations(colors, "colore scritto a mano (usa il tema)") { it.path.replace('\\', '/').contains("/testing/") }).isEmpty()
    for (name in listOf("receive", "share")) {
      val resource = File("src/main/res/values/strings_integrations_$name.xml")
      assertThat(resource.isFile).isTrue()
      assertThat(Regex("""<color\b|#[0-9a-fA-F]{6,8}\b""").containsMatchIn(resource.readText().replace(Regex("""<!--[\s\S]*?-->"""), ""))).isFalse()
    }
  }

  @Test
  fun nessunaFraseNelCodice() {
    val literal = Regex("""\b(text|contentDescription|title|subtitle|label|summary)\s*=\s*"[^"$]*[A-Za-z]{3,}""")
    assertThat(violations(literal, "frase scritta nel codice (usa una risorsa)")).isEmpty()
  }

  @Test
  fun iMessaggiDelleEccezioniSonoFrasiFisseSenzaDati() {
    val templated = Regex("""(IOException|FileNotFoundException|SecurityException|IllegalStateException|IllegalArgumentException)\s*\(\s*"[^"]*\$""")
    assertThat(violations(templated, "dato nel messaggio di un'eccezione")).isEmpty()
    val concatenated = Regex("""(IOException|FileNotFoundException|SecurityException)\s*\([^)]*\+""")
    assertThat(violations(concatenated, "dato concatenato in un'eccezione")).isEmpty()
  }

  @Test
  fun iToStringNonMostranoDati() {
    // Un `toString` di un tipo che porta nomi, percorsi o impronte non deve interpolare quei campi.
    val leaking = Regex("""override fun toString\(\)[^=]*=\s*"[^"]*\$\{?(displayName|name|path|folder|share|uri|sha256|hash|cacheName)\b""")
    assertThat(violations(leaking, "toString con un dato dell'utente")).isEmpty()
  }

  @Test
  fun iContatoriSonoCodiciFissi() {
    val all: List<CounterCode> = ReceiveCounter.entries + EditCounter.entries + ShareCounter.entries
    assertThat(all.map { it.code }.toSet()).hasSize(all.size)
    for (counter in all) assertThat(counter.code).matches("[a-z]+(_[a-z]+)*")
  }

  @Test
  fun ricevereModificareECondividereNonScrivonoSulTelefonoENonNominanoINascosti() {
    // Niente richieste di sistema che scrivono o tolgono (cestino, eliminazione, scrittura), niente riscrittore del telefono, niente conferme che
    // tolgono copie, niente nascosti, niente MANAGE_MEDIA: questi moduli ricevono, leggono e copiano in cache.
    val forbidden = Regex("""\b(HiddenLibrary|hiddenRows|HiddenAccessGate|PhoneMediaRewriter|PhoneRewriter|createWriteRequest|createDeleteRequest|createTrashRequest|MANAGE_MEDIA|MANAGE_EXTERNAL_STORAGE|deleteRequest|trashRequest)\b""")
    assertThat(violations(forbidden, "scrive sul telefono o tocca i nascosti")).isEmpty()
  }

  @Test
  fun leTreCartelleSonoLeUnicheDiQuestoPacchetto() {
    // Il perimetro di P11: ricevere, "Modifica con…", condividere. Niente file fuori: i `contract/`, `picker/` e `provider/` sono di altri.
    val found = File(base).listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
    assertThat(found).containsAtLeast("receive", "edit", "share")
  }
}
