package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Revisione 4B, I2 (correzione D della Fase 10): le porte di servizio che il test della 4B (`Phase4ArchitectureTest.unaConfermaLaCostruisceSoloLaUi`)
 * non vedeva. Una conferma delle operazioni sul NAS nasce solo da un tocco su un foglio (D24); queste regole tengono chiuse le altre strade:
 *
 * 1. nessun file di produzione (`src/main` di ogni modulo) fuori da un package `testing` nomina un package `testing`: i fake (anche
 *    `TestConfirmations`, che emette conferme senza un tocco) restano ai test e agli strumenti di debug, e il passo 7b di `tools/check.ps1`
 *    li cerca comunque nel dex della release;
 * 2. `TestConfirmations` (core-nasops e core-backup) non esce dai package `testing`, nemmeno negli strumenti della build di debug;
 * 3. in `:app` l'emittente delle conferme della 4B (`ConfirmationIssuer`) lo nominano solo i due file di oggi, che lo chiamano con i tocchi del
 *    foglio: mai un lavoro in background (`work/`), il grafo o un file nuovo senza passare da questa regola;
 * 4. il DAO del diario delle operazioni (`NasOpsDao`, tabelle `nas_op*`) lo vedono solo `core-database` e il diario di `core-nasops`
 *    (`store/`): una riga scritta direttamente la riprenderebbe il servizio delle operazioni senza conferma.
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola.
 */
class CorrezioniDArchitectureTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  private val modules: List<String> = File(root, "settings.gradle").readLines()
    .mapNotNull { INCLUDE.find(it.substringBefore("//"))?.groupValues?.get(1) }

  private fun files(module: String, sets: List<String>): Sequence<File> =
    sets.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun path(file: File): String = file.relativeTo(root).invariantSeparatorsPath

  /** I file di [sets] di ogni modulo che nominano [pattern] (commenti esclusi), come percorsi relativi alla radice. */
  private fun mentions(pattern: Regex, sets: List<String>): List<String> = modules.flatMap { module ->
    files(module, sets).filter { pattern.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }.map(::path).toList()
  }

  @Test
  fun ilControlloVedeIModuliEIFileCheCerca() {
    assertThat(modules).containsAtLeast("app", NASOPS, "core-backup", "core-database")
    assertWithMessage("i fake delle conferme esistono ancora dove li cerco").that(mentions(TEST_CONFIRMATIONS, MAIN))
      .containsAtLeast(
        "$NASOPS/src/main/kotlin/dev/pampa/mycloudgallery/core/nasops/testing/OpsFakes.kt",
        "core-backup/src/main/kotlin/dev/pampa/mycloudgallery/core/backup/testing/FiveBFakes.kt",
      )
    assertThat(mentions(NAS_OPS_DAO, MAIN)).isNotEmpty()
    assertThat(mentions(ISSUER, MAIN).filter { it.startsWith("app/") }).isNotEmpty()
  }

  /** Regola 1: la produzione non nomina i package `testing` (ne' con un `import` ne' con il nome intero). */
  @Test
  fun i2_laProduzioneNonNominaIPackageDeiFake() {
    val offenders = mentions(TESTING_PACKAGE, MAIN).filter { "/testing/" !in it }
    assertWithMessage("file di produzione che usano un package testing").that(offenders).isEmpty()
  }

  /** Regola 2: le conferme senza tocco stanno solo nei package `testing` (e nei test, che qui non si guardano). */
  @Test
  fun i2_leConfermeSenzaToccoNonEsconoDaiFake() {
    val offenders = mentions(TEST_CONFIRMATIONS, PRODUCTION_AND_DEBUG).filter { "/testing/" !in it }
    assertWithMessage("le conferme dei fake fuori dai package testing").that(offenders).isEmpty()
  }

  /** Regola 3: in `:app` l'emittente della 4B solo nei file che lo usano oggi, con i tocchi del foglio. */
  @Test
  fun i2_InAppLEmittenteDelleConfermeSoloNeiFileDiOggi() {
    val inApp = modules.filter { it == "app" }.flatMap { module ->
      files(module, PRODUCTION_AND_DEBUG).filter { ISSUER.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }.map(::path).toList()
    }
    // Il messaggio non scrive il nome dell'emittente: la regola della 4B (Phase4ArchitectureTest) lo cerca anche nelle stringhe dei test.
    assertWithMessage("l'emittente delle conferme della 4B in :app fuori dai file ammessi").that(inApp.filter { it !in APP_ISSUER_FILES }).isEmpty()
  }

  /** Regola 4: il DAO del diario delle operazioni solo nel database e nel diario di core-nasops. */
  @Test
  fun i2_IlDaoDelDiarioDelleOperazioniLoVedeSoloIlDiario() {
    val offenders = mentions(NAS_OPS_DAO, PRODUCTION_AND_DEBUG).filter { file ->
      !(file.startsWith("core-database/src/main/") || file.startsWith(NASOPS_STORE))
    }
    assertWithMessage("il DAO del diario delle operazioni fuori dal database e dal diario").that(offenders).isEmpty()
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")

    const val NASOPS = "core-nasops"

    const val NASOPS_STORE = "$NASOPS/src/main/kotlin/dev/pampa/mycloudgallery/core/nasops/store/"

    val MAIN = listOf("main")

    /** Cio' che finisce in un APK: il codice comune e quello delle varianti (debug, release, benchmark). */
    val PRODUCTION_AND_DEBUG = listOf("main", "debug", "release", "benchmarkRelease")

    val TESTING_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.[a-z0-9_.]*\.testing\b""")

    val TEST_CONFIRMATIONS = Regex("""\bTest""" + """Confirmations\b""")

    /** L'emittente della 4B (non quelle del telefono o della condivisione, che hanno le loro regole). */
    val ISSUER = Regex("""\bConfirmation""" + """Issuer\b""")

    val NAS_OPS_DAO = Regex("""\bNasOps""" + """Dao\b|\bnasOps""" + """Dao\s*\(""")

    /** I file di `:app` che oggi emettono una conferma della 4B, con i tocchi del foglio (utilita' e trasferimento degli album). */
    val APP_ISSUER_FILES = setOf(
      "app/src/main/kotlin/dev/pampa/mycloudgallery/utility/UtilityPorts.kt",
      "app/src/main/kotlin/dev/pampa/mycloudgallery/albums/AlbumTransferPorts.kt",
    )
  }
}
