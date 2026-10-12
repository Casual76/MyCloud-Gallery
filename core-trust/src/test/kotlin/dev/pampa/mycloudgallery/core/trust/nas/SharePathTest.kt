package dev.pampa.mycloudgallery.core.trust.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import org.junit.Assert.assertThrows
import org.junit.Test

class SharePathTest {

  private fun rejected(raw: String): Boolean {
    val e = assertThrows("doveva essere rifiutato: ${raw.map { it.code }}", TrustViolation::class.java) { SharePath.parse(raw) }
    return e.reason == RejectReason.HOSTILE_PATH
  }

  @Test
  fun percorsiNormaliAccettati() {
    val ok = listOf(
      "Foto/2024/IMG_0001.jpg",
      "Backup/Telefono di Marco/2026/10/PXL_20261012.mp4",
      "Vacanze 👨‍👩‍👧/Città/perché.heic",
      ".mycloudgallery/family/registry/00000000000000000001",
      "a b/c..d/e.f.g",
      "~utente/\$file",
      "x".repeat(255),
    )
    for (raw in ok) {
      assertThat(SharePath.parse(raw).value).isEqualTo(raw)
    }
  }

  @Test
  fun traversalAssolutiEUncRifiutati() {
    for (raw in listOf("..", "../x", "a/../b", "a/..", ".", "./a", "a/./b", "/etc/passwd", "/", "\\\\server\\x", "\\x", "C:\\Windows", "C:", "c:/x", "a\\..\\b", "a//b", "a/", "")) {
      assertThat(rejected(raw)).isTrue()
    }
  }

  @Test
  fun nomiRiservatiDiWindowsRifiutatiAncheConEstensione() {
    for (raw in listOf("CON", "con", "a/Con.txt", "NUL", "nul.tar.gz", "PRN", "AUX", "COM1", "lpt9.doc", "COM¹", "LPT³", "CONIN$", "conout$", "CLOCK$", "COM0", "CON .txt")) {
      assertThat(rejected(raw)).isTrue()
    }
    // Simili ma non riservati.
    for (raw in listOf("CONTO", "console.log", "COM10", "LPTX", "auxiliary")) {
      assertThat(SharePath.parse(raw).value).isEqualTo(raw)
    }
  }

  @Test
  fun caratteriVietatiDaWindowsEPuntiFinaliRifiutati() {
    for (raw in listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "file.", "file ", "cartella./x", "x".repeat(256))) {
      assertThat(rejected(raw)).isTrue()
    }
  }

  @Test
  fun caratteriDiControlloEInvisibiliRifiutati() {
    for (raw in listOf("a\u0000b", "a\nb", "a\rb", "a\tb", "a\u007Fb", "a\u0085b", "a\u009Bb", "a\u200Bb", "a\u200Eb", "\uFEFFa", "a\u2028b", "a\u00ADb", "a\uDFFFb", "a\uD83Db")) {
      assertThat(rejected(raw)).isTrue()
    }
  }

  @Test
  fun controlliBidiRifiutati() {
    // "fattura" + U+202E + "fdp.exe" si mostra come "fatturaexe.pdf".
    for (raw in listOf("fattura\u202Efdp.exe", "a\u202Ab", "a\u2066b", "a\u2069b", "a\u061Cb", "a\u200Fb")) {
      assertThat(rejected(raw)).isTrue()
    }
  }

  @Test
  fun unicodeDiConfusioneRifiutato() {
    // Caratteri a larghezza piena e affini che in NFKC diventano '.', '/', '\', ':' o nomi riservati.
    for (raw in listOf("．．", "a/．．/b", "．", "a／b", "a＼b", "C：x", "ＣＯＮ", "ｃｏｍ１", "a\uFE52", "file\uFF0E", "a\uFF1Fb")) {
      assertThat(rejected(raw)).isTrue()
    }
    // Il simbolo della divisione '∕' non si normalizza in '/': resta un nome (strano) ma innocuo.
    assertThat(SharePath.parse("a∕b").components).hasSize(1)
  }

  @Test
  fun percorsiTroppoLunghiOProfondiRifiutati() {
    assertThat(rejected((1..65).joinToString("/") { "d" })).isTrue()
    assertThat(rejected((1..20).joinToString("/") { "x".repeat(250) })).isTrue()
    assertThat(SharePath.parse((1..64).joinToString("/") { "d" }).components).hasSize(64)
  }

  @Test
  fun iPercorsiDeiMediaNonEntranoNellaCartellaDellApp() {
    assertThrows(TrustViolation::class.java) { SharePath.parseMediaPath(".mycloudgallery/family/registry/00000000000000000001") }
    assertThrows(TrustViolation::class.java) { SharePath.parseMediaPath(".MyCloudGallery/x") }
    assertThat(SharePath.parseMediaPath("Foto/.mycloudgallery").value).isEqualTo("Foto/.mycloudgallery")
  }

  @Test
  fun nomiLocaliDerivatiDaHashEStabili() {
    val hostile = "../../data/data/dev.pampa/shared_prefs/x.xml"
    val name = LocalNames.of("Public", hostile)

    assertThat(name).matches("m[0-9a-z]{52}")
    assertThat(LocalNames.of("Public", hostile)).isEqualTo(name)
    assertThat(LocalNames.of("Publi", "c$hostile")).isNotEqualTo(name) // le parti non si confondono
  }

  @Test
  fun versioniNumerateStrette() {
    assertThat(NasLayout.versionName(7)).isEqualTo("00000000000000000007")
    assertThat(NasLayout.parseVersionName("00000000000000000007")).isEqualTo(7)
    for (bad in listOf("7", "0000000000000000007", "000000000000000000007", "00000000000000000000", "0000000000000000000a", "-0000000000000000001", "99999999999999999999")) {
      assertThat(NasLayout.parseVersionName(bad)).isNull()
    }
  }

  @Test
  fun tettoDiPixelPerLeImmaginiDellIndice() {
    assertThat(ImageBounds.isDecodable(1536, 1024)).isTrue()
    assertThat(ImageBounds.isDecodable(4096, 4096)).isTrue()
    assertThat(ImageBounds.isDecodable(4097, 4096)).isFalse()
    assertThat(ImageBounds.isDecodable(16384, 1)).isTrue()
    assertThat(ImageBounds.isDecodable(0, 10)).isFalse()
    assertThat(ImageBounds.isDecodable(-1, 10)).isFalse()
    assertThat(ImageBounds.isDecodable(65535, 65535)).isFalse()
  }
}
