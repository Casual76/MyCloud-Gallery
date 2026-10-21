package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelHosts
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.File
import org.junit.Test

/**
 * Fase 6, P3 (riga P3 del §4.4: "catalogo uguale al §2.1"): il catalogo del codice e la tabella del §2.1 di `docs/fase6-progetto.md`
 * dicono le stesse cose: file, repository, commit, dimensione, impronta, indirizzo HTTPS. Se uno dei due cambia senza l'altro, qui si vede
 * (e un valore del catalogo cambia solo con un aggiornamento dell'app, D16/D19).
 */
class CatalogMatchesDesignTest {
  private val raw: String = generateSequence(File(".").absoluteFile) { it.parentFile }
    .map { File(it, "docs/fase6-progetto.md") }
    .first { it.isFile }
    .readText()

  /** Il testo con gli a-capo del documento ridotti a spazi: le frasi si cercano senza badare a dove va a capo il Markdown. */
  private val doc: String = raw.replace(Regex("\\s+"), " ")

  /** Le righe della tabella "Il catalogo fissato nell'app" (§2.1). */
  private val rows: List<String> = raw.lineSequence()
    .dropWhile { !it.startsWith("**Il catalogo fissato nell'app**") }
    .dropWhile { !it.startsWith("|") }
    .takeWhile { it.startsWith("|") }
    .filter { "---" !in it }
    .toList()

  private fun row(spec: ModelSpec): String = rows.single { spec.fileName in it }

  private fun digits(text: String): Long = text.filter { it.isDigit() }.toLong()

  @Test
  fun laTabellaDelProgettoHaTreModelli() {
    // Intestazione + tre modelli.
    assertThat(rows).hasSize(4)
  }

  @Test
  fun iDueModelliDaScaricareCorrispondonoRigaPerRiga() {
    for (spec in listOf(ModelCatalog.QUERY_270M, ModelCatalog.INDEX_440M)) {
      val cells = row(spec).split("|").map { it.trim() }
      val url = spec.https.single().value
      // `| Nome | Ruolo | File e repository | Commit | Dimensione | SHA-256 | Chi lo tiene |`
      assertThat(cells[3]).contains(spec.fileName)
      val repository = Regex("`([a-z0-9-]+/[a-z0-9.-]+)`").findAll(cells[3]).map { it.groupValues[1] }.single()
      val commit = Regex("`([0-9a-f]{40})`").find(cells[4])!!.groupValues[1]
      assertThat(digits(cells[5].substringBefore(" B"))).isEqualTo(spec.sizeBytes)
      assertThat(Regex("`([0-9a-f]{64})`").find(cells[6])!!.groupValues[1]).isEqualTo(spec.sha256.hex)
      assertThat(url).isEqualTo("https://huggingface.co/$repository/resolve/$commit/${spec.fileName}")
    }
  }

  @Test
  fun ilRitaglioCorrispondeEStaNellApk() {
    val spec = ModelCatalog.CUTOUT_MAGICTOUCH
    val line = row(spec)
    assertThat(line).contains("storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite")
    assertThat(line).contains(spec.sha256.hex)
    assertThat(line).contains("6.227.884 B")
    assertThat(spec.sizeBytes).isEqualTo(6_227_884L)
    assertThat(spec.https).isEmpty()
    assertThat(spec.bundledAsset).isEqualTo("cutout/magic_touch.tflite")
    assertThat(line).contains("dentro l'APK")
  }

  @Test
  fun leRegoleDelleSorgentiSonoQuelleDelProgetto() {
    // §2.1 "HTTPS": host ammessi, 5 redirezioni, 15 s e 30 s, blocchi da 1 MiB, margine di 300 MB.
    assertThat(doc).contains("`huggingface.co` e i suffissi `.huggingface.co` e `.hf.co`")
    assertThat(ModelHosts.EXACT).containsExactly("huggingface.co")
    assertThat(ModelHosts.SUFFIXES).containsExactly(".huggingface.co", ".hf.co")
    assertThat(doc).contains("al massimo 5, solo verso host ammessi e solo `https`")
    assertThat(ModelHosts.MAX_REDIRECTS).isEqualTo(5)
    assertThat(doc).contains("scadenze di 15 s per la connessione e di 30 s per blocco")
    assertThat(ModelLimits.CONNECT_TIMEOUT_MS).isEqualTo(15_000)
    assertThat(ModelLimits.READ_TIMEOUT_MS).isEqualTo(30_000)
    assertThat(doc).contains("`User-Agent: MyCloudGallery`")
    assertThat(ModelLimits.USER_AGENT).isEqualTo("MyCloudGallery")
    assertThat(doc).contains("a blocchi da 1 MiB")
    assertThat(ModelLimits.BLOCK_BYTES).isEqualTo(1024 * 1024)
    assertThat(doc).contains("dimensione mancante + 300 MB")
    assertThat(ModelLimits.SPACE_MARGIN_BYTES).isEqualTo(300L * 1024 * 1024)
    assertThat(doc).contains("`MyCloud Gallery/Modelli/`")
    assertThat(ModelLimits.PC_FOLDER).containsExactly("MyCloud Gallery", "Modelli").inOrder()
  }

  @Test
  fun laCartellaDeiModelliSullAppENelCodice() {
    assertThat(doc).contains("`noBackupFilesDir/models/<sha256>`")
    assertThat(doc).contains("models/partial/<sha256>.part")
    assertThat(doc).contains("<area comune>/.mycloudgallery/devices/<id>/models/<sha256>")
  }
}
