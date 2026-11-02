package dev.pampa.mycloudgallery.feature.settings.about

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le novita' di una versione (Fase 10, P12, D33): il lettore del changelog e le due copie del testo. `CHANGELOG.md` alla radice e' la fonte
 * (la legge anche `tools/release/release.ps1`, passo 6); `res/raw/changelog.md` e' la copia che sta nell'APK. Se una cambia senza l'altra
 * questo test fallisce: la famiglia leggerebbe un testo diverso da quello che c'e' sul Pampa Store.
 */
class ReleaseNotesTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle").isFile && File(it, "feature-settings").isDirectory }

  private fun read(path: String): String = File(root, path).readText(Charsets.UTF_8).replace("\r\n", "\n")

  private val sample = """
    # Novità

    Un'introduzione che non entra mai nella pagina.
    - Anche questa riga, fuori da una sezione, non conta.

    ## 1.1.0

    ### Novità
    - Prima frase.
    - Seconda frase
      che va a capo.

    ### Sicurezza
    - Una cosa sicura.

    ## 1.0.0

    ### Novità
    - La prima volta.

    ### Da sapere
    - Una nota.
  """.trimIndent()

  @Test
  fun leggeLeVersioniNellOrdineDelFileEIgnoraLIntroduzione() {
    val versions = ReleaseNotesParser.parse(sample)

    assertThat(versions.map { it.version }).containsExactly("1.1.0", "1.0.0").inOrder()
    assertThat(versions[0].sections.map { it.title }).containsExactly("Novità", "Sicurezza").inOrder()
    assertThat(versions[0].sections[0].items).containsExactly("Prima frase.", "Seconda frase che va a capo.").inOrder()
    assertThat(versions[1].sections.map { it.title }).containsExactly("Novità", "Da sapere").inOrder()
    assertThat(versions.flatMap { v -> v.sections.flatMap { it.items } }.joinToString("\n")).doesNotContain("introduzione")
  }

  @Test
  fun laVersioneDiDebugSiLeggeSenzaIlSuffisso() {
    assertThat(ReleaseNotesParser.forVersion(sample, "1.0.0-debug")!!.version).isEqualTo("1.0.0")
    assertThat(ReleaseNotesParser.forVersion(sample, "1.1.0")!!.version).isEqualTo("1.1.0")
    // Una versione che il file non ha non diventa un'altra di nascosto: null, e la pagina lo dice.
    assertThat(ReleaseNotesParser.forVersion(sample, "2.0.0")).isNull()
    assertThat(ReleaseNotesParser.forVersion(sample, null)).isNull()
  }

  @Test
  fun unTestoVuotoORovinatoNonFaMaiCrollare() {
    assertThat(ReleaseNotesParser.parse("")).isEmpty()
    assertThat(ReleaseNotesParser.parse("## ")).isEmpty()
    assertThat(ReleaseNotesParser.parse("###\n- x\n## 1.0.0\n### \n- y")).hasSize(1)
    assertThat(ReleaseNotesParser.parse("\u0000\u0001 ## 1.0.0\n### Novità\n- a")).isEmpty()
  }

  @Test
  fun iTettiTengonoLaPaginaPiccola() {
    val big = StringBuilder("## 1.0.0\n")
    repeat(20) { s ->
      big.append("### Titolo $s\n")
      repeat(100) { big.append("- ${"x".repeat(2_000)}\n") }
    }
    val parsed = ReleaseNotesParser.parse(big.toString())

    val sections = parsed.single().sections
    assertThat(sections.size).isAtMost(ReleaseNotesParser.MAX_SECTIONS_PER_VERSION)
    assertThat(sections.maxOf { it.items.size }).isAtMost(ReleaseNotesParser.MAX_ITEMS_PER_SECTION)
    assertThat(sections.flatMap { it.items }.maxOf { it.length }).isAtMost(ReleaseNotesParser.MAX_ITEM_CHARS)
  }

  @Test
  fun iFineRigaDiWindowsValgonoComeQuelliDiUnix() {
    val windows = sample.replace("\n", "\r\n")

    assertThat(ReleaseNotesParser.parse(windows).map { it.version }).containsExactly("1.1.0", "1.0.0").inOrder()
    assertThat(ReleaseNotesParser.parse(windows)[0].sections[0].items).containsExactly("Prima frase.", "Seconda frase che va a capo.").inOrder()
  }

  @Test
  fun laCopiaNellAppEUgualeAlFileDiRilascio() {
    assertThat(read("feature-settings/src/main/res/raw/changelog.md")).isEqualTo(read("CHANGELOG.md"))
  }

  @Test
  fun laVersioneInPreparazioneHaLaSuaSezioneCompleta() {
    val versions = read("release/versions.txt").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val last = versions.last().split(Regex("\\s+"))
    val notes = ReleaseNotesParser.forVersion(read("CHANGELOG.md"), last[0])

    assertWithMessage("CHANGELOG.md non ha la sezione di ${last[0]} (l'ultima riga di release/versions.txt)").that(notes).isNotNull()
    // Le quattro voci, sempre in questo ordine, ognuna con almeno una frase.
    assertThat(notes!!.sections.map { it.title }).containsExactly("Novità", "Correzioni", "Sicurezza", "Da sapere").inOrder()
    for (section in notes.sections) assertThat(section.items).isNotEmpty()
  }

  @Test
  fun ilChangelogNonHaParoleDaTecniciNeSigle() {
    val text = read("CHANGELOG.md").substringAfter("## ")
    for (word in listOf("SMB", "Keystore", "AES", "ECDSA", "R8", "APK", "NAS-IT", "WireGuard", "L559", "C443", "RAID", "SHA", "P-256")) {
      assertWithMessage("il changelog parla alla famiglia: niente '$word'").that(text).doesNotContain(word)
    }
  }
}
