package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system verificata sul codice di `:feature-widget` (Fase 10, P3; D10, D38): **nessun colore, raggio, dimensione di testo o movimento scritto a mano**.
 * Il widget compone solo i componenti del kit di `engine-widget` (`EngineWidgetSurface`, `EngineWidgetHeader`, `EngineWidgetMessage`, `EngineWidgetIconTile`, `EngineWidgetShape`,
 * `EngineWidgetMetrics`) con la palette di `engineWidgetPalette`, che segue il tema e l'accento dell'app. Come `NoHandWrittenStyleTest` degli altri feature. Cerca nei sorgenti di
 * `src/main` e fallisce con la lista delle righe. In piu': nessun colore creato qui (`ColorProvider(`, `Color(`: i colori sono della palette del kit), nessun log che possa scrivere un
 * id, un titolo o un luogo (D39) e nessun feature che ne conosca un altro.
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun violations(pattern: Regex): List<String> =
    sources.flatMap { file ->
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
    val found = violations(Regex("""Color\(\s*0x|#[0-9A-Fa-f]{6,8}\b"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreCreatoQui_SoloQuelliDellaPalettaDelKit() {
    val found = violations(Regex("""\bColorProvider\s*\(|\bColor\s*\(|\bColor\.(hsv|hsl|rgb|argb)\b|\bDayNightColorProvider\b"""))
    assertWithMessage("Colori creati nel widget (vengono da engineWidgetPalette):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunRaggioFattoAMano() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b|\bcornerRadius\s*\(\s*\d"""))
    assertWithMessage("Angoli fatti a mano (usa EngineWidgetShape):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*=|\bTextStyle\s*\("""))
    assertWithMessage("Dimensioni di testo scritte a mano (le da' il kit):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunDpLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""))
    assertWithMessage("Misure scritte a mano (vengono da EngineWidgetMetrics o dai dp del widget):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimento() {
    // Un widget non si anima: i RemoteViews non hanno animazioni, e nessun movimento va scritto a mano.
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade|AnimatedContent|AnimatedVisibility|infiniteRepeatable)\b"""))
    assertWithMessage("Movimento scritto a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(library|settings|viewer|onboarding|diagnostics|backup|nas|integrations|search|albums|people|map|memories)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogChePossaScrivereUnIdUnTitoloOUnLuogo() {
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bprintStackTrace\("""))
    assertWithMessage("Log non ammessi (D39: solo numeri e codici, mai id, titoli, luoghi):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunDatoDellUtenteInUnMessaggioDiEccezioneONelLog() {
    // Un'eccezione o una riga di log con un id o un titolo dentro finirebbe in un rapporto.
    val found = violations(Regex("""\b(error|require|check)\([^)]*\$\{?(title|name|text|label|id|media|memory|place)\b|GalleryLog\.\w\([^)]*\)\s*\{[^}]*\$\{?(title|name|text|label|media|memory|place|id)\b"""))
    assertWithMessage("Dati dell'utente in un messaggio di eccezione o di log:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
