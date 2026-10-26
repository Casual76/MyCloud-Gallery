package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system verificata sul codice di `:feature-albums` (Fase 7, P10): **nessun colore, raggio, dimensione di testo o movimento scritto
 * a mano** nelle schermate, nessun `dp` letterale fuori da `AlbumDimens.kt` e nessuna dissolvenza fra pagine. Come `NoHandWrittenStyleTest` di
 * `:feature-search`. Cerca nei sorgenti di `src/main` e fallisce con la lista delle righe.
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> =
    sources
      .filter { it.name !in allowedFiles }
      .flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          val code = line.substringBefore("//")
          val trimmed = code.trimStart()
          val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
          if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
        }
      }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}").that(sources).isNotEmpty()
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunRaggioCircolare() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))
    assertWithMessage("Angoli circolari (usa ContinuousCornerShape / FluidRadius):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*="""))
    assertWithMessage("Dimensioni di testo scritte a mano (usa MaterialTheme.typography):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunDpLetteraleFuoriDaAlbumDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("AlbumDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in AlbumDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion; una pagina in arrivo non sfuma mai):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(library|settings|viewer|onboarding|diagnostics|backup|nas|integrations|search)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogChePossaScrivereIlNomeDiUnAlbum() {
    // D37: nei log solo numeri e codici, mai il titolo di un album, il nome di un familiare o il testo di una regola. Qui nessun log.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\("""))
    assertWithMessage("Log non ammessi (D37):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun leSchermateNonCostruisconoLaConfermaDiUnaCondivisione() {
    // La conferma nasce dal tocco sul foglio e da nessun altro posto: lo prova l'architettura per nome, questo ne guarda la forma. Un controllore
    // diverso da ShareSheetController che chiama `.tap(` aprirebbe una seconda strada alla conferma.
    val found = violations(Regex("""\.tap\("""), allowedFiles = setOf("ShareSheetController.kt"))
    assertWithMessage("Una conferma emessa fuori dal foglio di condivisione:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunTestoDiAlbumNelleEccezioniODiagnostica() {
    // Un'eccezione con il titolo dentro finirebbe in un rapporto: mai `error("...$title...")` o `throw ...` con dati dell'utente.
    val found = violations(Regex("""\b(error|require|check)\([^)]*\$\{?(title|name|text|query)\b"""))
    assertWithMessage("Dati dell'utente in un messaggio di eccezione:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
