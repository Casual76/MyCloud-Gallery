package dev.pampa.mycloudgallery.feature.library.utility

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system e i divieti delle operazioni distruttive, verificati sul codice dell'**Utility** (`utility/`, Fase 7, P8): nessun
 * colore, raggio, dimensione di testo o movimento scritto a mano, nessun `dp` letterale fuori da `UtilityDimens.kt`, nessun log, nessuna strada
 * verso le operazioni distruttive del NAS (`core-nasops`, `Confirmation`) ne' verso la cancellazione definitiva dal telefono. Come
 * `CollectionsStyleTest`, sui sorgenti di `src/main`: fallisce con la lista delle righe, cosi' un'eccezione non entra di nascosto.
 */
class UtilityStyleTest {

  private val root = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library/utility")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun violations(
    pattern: Regex,
    allowedFiles: Set<String> = emptySet(),
    includeTesting: Boolean = false,
  ): List<String> =
    sources
      .filter { it.name !in allowedFiles }
      .filter { includeTesting || "${File.separator}testing${File.separator}" !in it.path }
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
    assertWithMessage("Il package utility non e' stato trovato dalla cartella ${File(".").absolutePath}")
      .that(sources.map { it.name })
      .containsAtLeast("DuplicatesController.kt", "DuplicatesScreen.kt", "UtilityPage.kt", "UtilityDimens.kt")
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""\bColor\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano (la coppia sopra una foto e' GalleryOnPhoto):\n" + found.joinToString("\n")).that(found).isEmpty()
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
  fun nessunDpLetteraleFuoriDaUtilityDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("UtilityDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in UtilityDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade|animate\w*AsState)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogCheScriveDatiPersonali() {
    // I percorsi dei file e gli identificativi dei media sono dati dell'utente: qui non si scrive nessun log.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bGalleryLog\b"""), includeTesting = true)
    assertWithMessage("Log non ammessi in questo package:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(onboarding|diagnostics|settings|viewer|nas)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaStradaVersoLeOperazioniDistruttiveDelNas() {
    // Phase4ArchitectureTest: chi mostra la Libreria non raggiunge core-nasops. La pulizia passa dalla porta `CleanupNasPort`, che lega `:app`.
    val found = violations(Regex("""core\.nasops|\bConfirmation\w*|\bNasOperations\b|\bOpRequest\b"""), includeTesting = true)
    assertWithMessage("Strade dirette verso core-nasops:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaCancellazioneDefinitivaDelTelefono() {
    // D14: l'app non cancella mai da sola; qui nemmeno la richiesta di eliminazione per sempre: i doppioni vanno nei cestini (30 giorni).
    val found = violations(Regex("""\bContentResolver\b|\.delete\(|createDeleteRequest|\bdeleteRequest\(|MANAGE_MEDIA|MANAGE_EXTERNAL_STORAGE"""), includeTesting = true)
    assertWithMessage("Cancellazioni dirette o permessi speciali:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun laProvaDelTocco_NascePrimaSoloNelFileDelleProve() {
    // `UserTap` si costruisce solo in NasTrashPort.kt (DoubleTapConfirm, SheetTap, tapOnButton): qui il tocco sul foglio arriva da `SheetTap`.
    val found = violations(Regex("""\bUserTap\s*\("""), includeTesting = true)
    assertWithMessage("Una prova di tocco costruita a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
