package dev.pampa.mycloudgallery.core.describe.rules

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import java.io.File
import org.junit.Test

/**
 * Fase 10 (P7, D22, C438): una descrizione non si salva, non si registra e non esce dal telefono. Nessuna superficie di esportazione o di log
 * (la regola 4 di `Phase10ArchitectureTest` la prova sul repository; qui la stessa cosa dal modulo, con il codice del descrittore vero), nessun
 * log, nessuna scrittura su file o preferenze, nessun uso del nome di un media nella descrizione.
 */
class DescribeSurfacesArchitectureTest {

  /** Toglie i commenti (devono poter dire cosa si vieta). */
  private fun stripComments(source: String): String =
    source.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\\n]*"), "")

  private fun sources(): List<Pair<String, String>> {
    val root = File("src/main")
    require(root.isDirectory) { "Non trovo i sorgenti di core-describe." }
    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
      .map { it.relativeTo(root).path.replace('\\', '/') to stripComments(it.readText()) }.toList()
  }

  @Test
  fun nonEsisteUnaSuperficieDiEsportazioneODiLog() {
    val codes = DescribeSurface.entries.map { it.code } + DescribeSurface.entries.map { it.name.lowercase() }
    for (word in listOf("export", "esport", "log", "diag", "share", "backup", "clipboard")) {
      assertThat(codes.filter { it.contains(word) }).isEmpty()
    }
    assertThat(DescribeSurface.entries.map { it.name }).containsExactly("IN_APP", "HIDDEN_AREA", "WIDGET", "NOTIFICATION")
  }

  @Test
  fun ilDescrittoreNonRegistraNonSalvaENonEsceDalTelefono() {
    val files = sources()
    assertThat(files.map { it.first }).contains("kotlin/dev/pampa/mycloudgallery/core/describe/rules/DefaultMediaDescriber.kt")
    val forbidden = listOf(
      Regex("""\bandroid\.util\.Log\b|\bLog\s*\.\s*[vdiwe]\s*\(|\bprintln\s*\(|\bprint\s*\("""),
      Regex("""\bjava\.io\.File\w*|\bSharedPreferences\b|\bDataStore\b|\bRoom\b|\bClipboardManager\b|\bIntent\b"""),
    )
    for ((path, code) in files) {
      for (regex in forbidden) assertThat(regex.containsMatchIn(code)).isFalse()
      assertThat(path).doesNotContain("export")
    }
  }

  @Test
  fun lePorteDellePersoneEDelTestoNonVedonoNeNomiDiFileNeId() {
    // Le firme: solo `MediaId` in ingresso, solo nomi e parole in uscita (nessun percorso, nessun nome di file).
    val contract = sources().first { it.first.endsWith("contract/Describer.kt") }.second
    assertThat(contract).contains("suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>>")
    assertThat(contract).contains("suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String>")
    assertThat(contract).doesNotContain("NasPath")
    assertThat(contract).doesNotContain("fileName")
  }
}
