package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system verificata sul codice di `:feature-map` (Fase 9, P4; D34): **nessun colore, raggio, dimensione di testo o movimento
 * scritto a mano** nelle schermate (la camera la anima MapLibre con la durata dei token; le bolle si muovono solo con la traslazione del layer, mai con un'animazione scritta a mano),
 * nessun `dp` letterale fuori da `MapDimens.kt` e nessuna dissolvenza fra pagine. Come `NoHandWrittenStyleTest` di `:feature-people`. Cerca nei
 * sorgenti di `src/main` e fallisce con la lista delle righe. Ci sono anche due regole di riservatezza: nessun log (D33, mai un titolo, un luogo, una data,
 * un id o un nome) e nessun testo dell'utente in un messaggio di eccezione.
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
  fun nessunDpLetteraleFuoriDaMapDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("MapDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in MapDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion; una pagina in arrivo non sfuma mai):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaAnimazioneFuoriDaiTokenDiMovimento() {
    // Le uniche curve sono quelle di `FluidMotion`: niente `AnimatedContent`, niente `animate*AsState` con una specifica scritta, niente `Easing` a mano.
    val found = violations(Regex("""\b(AnimatedContent|AnimatedVisibility|CubicBezierEasing|LinearEasing|FastOutSlowInEasing|infiniteRepeatable|rememberInfiniteTransition)\b"""))
    assertWithMessage("Animazioni scritte a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(library|settings|viewer|onboarding|diagnostics|backup|nas|integrations|search|albums|people|memories)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogChePossaScrivereUnTitoloUnLuogoOUnNome() {
    // D33: nei log solo numeri e codici, mai titoli, luoghi, date, id o nomi. Nelle schermate non c'e' nessun log.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bprintStackTrace\("""))
    assertWithMessage("Log non ammessi (D33):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunDatoDellUtenteInUnMessaggioDiEccezione() {
    // Un'eccezione con un nome dentro finirebbe in un rapporto: mai `error("...$name...")` con dati dell'utente.
    val found = violations(Regex("""\b(error|require|check)\([^)]*\$\{?(title|name|text|query|id|person|place|label)\b"""))
    assertWithMessage("Dati dell'utente in un messaggio di eccezione:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
