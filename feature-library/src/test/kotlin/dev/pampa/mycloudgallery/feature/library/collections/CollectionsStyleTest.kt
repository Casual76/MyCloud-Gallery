package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system, verificata sul codice delle **Raccolte, dei Nascosti e degli Eliminati di recente**
 * (`collections/`, `hidden/`, `trash/`): nessun colore, raggio, dimensione di testo o movimento scritto a mano, nessun `dp`
 * letterale fuori da `CollectionsDimens.kt`, nessuna dissolvenza fra pagine. Come `NoHandWrittenStyleTest` degli altri
 * moduli, ma limitato ai tre package di questo pacchetto: `grid/` e `library/` sono di un altro (P7).
 *
 * Cerca nei sorgenti di `src/main` e fallisce con la lista delle righe, cosi' un'eccezione non entra di nascosto.
 */
class CollectionsStyleTest {

  private val roots = listOf("collections", "hidden", "trash")
    .map { File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library/$it") }

  private val sources: List<File> by lazy {
    roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
  }

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
    assertWithMessage("I tre package non sono stati trovati dalla cartella ${File(".").absolutePath}")
      .that(sources.map { it.name })
      .containsAtLeast("CollectionsTab.kt", "HiddenAccessGate.kt", "RecentlyDeletedController.kt")
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""\bColor\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano (la coppia sopra una foto e' GalleryOnPhoto):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
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
  fun nessunDpLetteraleFuoriDaCollectionsDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("CollectionsDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in CollectionsDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade|animate\w*AsState)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion; una pagina in arrivo non sfuma mai):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunLogCheScriveDatiPersonali() {
    // Log solo con GalleryLog e mai con nomi di file, percorsi, luoghi o id di media: qui non se ne scrivono affatto.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bGalleryLog\b"""))
    assertWithMessage("Log non ammessi in questi package:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(onboarding|diagnostics|settings|viewer)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunaCancellazioneDefinitivaDalCestino() {
    // D14: l'app non cancella mai da sola. Nessun tentativo di eliminare davvero un file del telefono.
    val found = violations(Regex("""\bContentResolver\b|\.delete\(|createDeleteRequest|MANAGE_MEDIA|MANAGE_EXTERNAL_STORAGE"""))
    assertWithMessage("Cancellazioni dirette o permessi speciali:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
