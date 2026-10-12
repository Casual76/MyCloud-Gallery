package dev.pampa.mycloudgallery.core.qr

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le regole Fluid, verificate sul codice di `:core-qr` (lo stesso controllo di `NoHandWrittenStyleTest` del
 * design system): **nessun colore, raggio o dimensione di testo scritto a mano**, nessuna animazione fuori da
 * `FluidMotion`. Cerca nei sorgenti di `src/main` e fallisce con le righe, cosi' un'eccezione non entra di
 * nascosto.
 *
 * L'unica eccezione e' `QrPalette.kt`: i moduli neri su fondo bianco di un QR non seguono il tema
 * (spiegato nel file).
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy {
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> =
    sources
      .filter { it.name !in allowedFiles }
      .flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          val code = line.substringBefore("//")
          val isDoc = code.trimStart().startsWith("*") || code.trimStart().startsWith("/**")
          if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
        }
      }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}")
      .that(sources)
      .isNotEmpty()
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominatoFuoriDallaPaletteDelQr() {
    val found = violations(
      Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b"""),
      allowedFiles = setOf("QrPalette.kt"),
    )
    assertWithMessage("Colori nominati a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunRaggioCircolare() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))
    assertWithMessage("Angoli circolari (usa ContinuousCornerShape / FluidRadius):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*="""))
    assertWithMessage("Dimensioni di testo scritte a mano (usa MaterialTheme.typography):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunaAnimazioneScrittaAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|animate\w*AsState|rememberInfiniteTransition)\s*[<(]"""))
    assertWithMessage("Animazioni scritte a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunComponenteMaterialAlPostoDiQuelliFluid() {
    val material = "androidx.compose.material3."
    val names = "Card|ElevatedCard|OutlinedCard|Surface|Button|OutlinedButton|TextButton|OutlinedTextField|TextField"
    val found = violations(Regex("""import ${Regex.escape(material)}($names)$"""))
    assertWithMessage("Componenti Material al posto di quelli Fluid:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
