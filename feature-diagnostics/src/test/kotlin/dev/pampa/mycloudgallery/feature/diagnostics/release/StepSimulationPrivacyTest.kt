package dev.pampa.mycloudgallery.feature.diagnostics.release

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * "Mai nomi di file" (P10, §2.10): il resoconto della simulazione porta solo numeri, le quattro aree e le cartelle di primo livello **del
 * formato**. Due barriere: (1) i tipi non hanno un campo di testo libero (un nome letto dal NAS o scelto dall'utente non ha dove stare);
 * (2) il testo esportato, con tutte le varianti, contiene solo righe del vocabolario fisso e nessuna cartella fuori dall'insieme chiuso.
 */
class StepSimulationPrivacyTest {

  private val everything: StepReport = StepReport(
    forecasts = listOf(
      StepForecast.Writes(
        StepArea.INDEX,
        StepCertainty.ESTIMATE,
        listOf(
          StepWrite(StepPlace.PRIVATE_AREA, StepFolder.APP_FOLDER, 12, 400L shl 20),
          StepWrite(StepPlace.COMMON_AREA, StepFolder.APP_FOLDER, 4, 90L shl 20),
        ),
      ),
      StepForecast.Writes(
        StepArea.BACKUP,
        StepCertainty.EXACT,
        listOf(StepWrite(StepPlace.PRIVATE_AREA, StepFolder.BACKUP, 1234, 12L shl 30), StepWrite(StepPlace.COMMON_AREA, StepFolder.VERSIONS, 1, 5)),
      ),
      StepForecast.Idle(StepArea.FACES, StepIdle.FEATURE_OFF),
      StepForecast.Unavailable(StepArea.ALBUMS, StepUnavailable.NOT_READY),
    ),
    space = StepSpace(300L shl 30),
    freeAfterBytes = 280L shl 30,
    attention = setOf(StepAttention.AREA_UNAVAILABLE, StepAttention.ESTIMATES_PRESENT),
  )

  /** I tipi del resoconto, a campi: solo numeri, booleani, enum, liste e altri tipi della simulazione. */
  private val types: List<Class<*>> = listOf(
    StepWrite::class.java,
    StepForecast.Writes::class.java,
    StepForecast.Idle::class.java,
    StepForecast.Unavailable::class.java,
    StepSpace::class.java,
    StepReport::class.java,
  )

  @Test
  fun iTipiDelResocontoNonHannoUnCampoDiTestoLibero() {
    for (type in types) {
      for (field in type.declaredFields) {
        val t = field.type
        val allowed = t.isPrimitive || Number::class.java.isAssignableFrom(t) || t == Boolean::class.javaObjectType || t.isEnum ||
          List::class.java.isAssignableFrom(t) || Set::class.java.isAssignableFrom(t) ||
          t.name.startsWith("dev.pampa.mycloudgallery.feature.diagnostics.release.")
        assertWithMessage("${type.simpleName}.${field.name} e' un ${t.simpleName}: non puo' portare un nome").that(allowed).isTrue()
        assertThat(CharSequence::class.java.isAssignableFrom(t)).isFalse()
      }
    }
  }

  @Test
  fun leCartelleSonoUnInsiemeChiusoDelFormato() {
    assertThat(StepFolder.entries.map { it.label })
      .containsExactly(".mycloudgallery", "Backup", "Versioni", "Album condivisi")
  }

  @Test
  fun ilTestoEsportatoHaSoloRigheDelVocabolarioFisso() {
    val lines = StepSimulationText.export(everything).lines()
    val fences = StepArea.entries.map { it.fence }
    for (line in lines) {
      val known = line.startsWith("Simulazione del passo") || fences.any { line.startsWith("$it ") } || line.startsWith("Totale:") ||
        line.startsWith("Spazio libero sul NAS:") || line.startsWith("- ") || line.startsWith("Nessuna cosa che ferma")
      assertWithMessage("riga fuori dal vocabolario: $line").that(known).isTrue()
    }
    assertThat(lines.first()).isEqualTo("Simulazione del passo (sola lettura)")
  }

  @Test
  fun ilTestoEsportatoNominaSoloLeCartelleDelFormato() {
    val text = StepSimulationText.export(everything)
    // Ogni "<area>, <cartella>: N file" del testo ha una cartella dell'insieme chiuso.
    val folders = Regex("""(?:area privata|area comune), ([^:]+): """).findAll(text).map { it.groupValues[1] }.toSet()
    assertThat(folders).containsExactly(".mycloudgallery", "Backup", "Versioni")
    assertThat(StepFolder.entries.map { it.label }).containsAtLeastElementsIn(folders)
  }

  @Test
  fun ilTestoNonHaEstensioniDiFileNeNomiProprio() {
    val text = StepSimulationText.export(everything)
    // Nessuna estensione di media o di documento, nessun percorso con la barra, nessuna sigla di dispositivo.
    assertThat(Regex("""\.(jpe?g|png|heic|mp4|mov|txt|pdf|zip)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)).isFalse()
    assertThat(text).doesNotContain("/")
    assertThat(text).doesNotContain("\\")
  }

  @Test
  fun iNumeriSonoInItalianoEConLeUnitaDelNas() {
    assertThat(StepSimulationText.files(1)).isEqualTo("1 file")
    assertThat(StepSimulationText.files(1234)).isEqualTo("1.234 file")
    assertThat(StepSimulationText.bytes(512)).isEqualTo("512 B")
    assertThat(StepSimulationText.bytes(5L shl 20)).isEqualTo("5,0 MB")
    assertThat(StepSimulationText.bytes(12L shl 30)).isEqualTo("12,0 GB")
  }

  @Test
  fun ilCodiceDellaSimulazioneNonNominaLeScritture() {
    val files = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/diagnostics/release").listFiles { f -> f.extension == "kt" }.orEmpty()
    assertThat(files).isNotEmpty()
    val forbidden = Regex("""\b(publishExclusive|NasOperations|NasFileOps|NasTrash|DestructiveNasAccess|mkdirs|rmdir|deleteIfUnchanged|trashMove|renameTo|writeBytes|writeText|FileOutputStream)\b""")
    for (file in files) {
      val code = file.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
      for (line in code) assertWithMessage("${file.name}: $line").that(forbidden.containsMatchIn(line)).isFalse()
    }
  }
}
