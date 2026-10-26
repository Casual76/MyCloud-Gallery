package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IL GENERATORE IN PYTHON, DAVVERO (riga P12 del §4.4): `tools/fixtures/trips/generate_trips.py` lanciato con la specifica della prima storia di P6 scritta da
 * Kotlin ([TripFixtureSpec]). Controlla che scriva un file per foto nella cartella giusta (telefono, screenshot, tablet, familiari), che il manifesto dica
 * l'impronta vera di ogni file e che gli album per lo strumento (`viaggi-sintetici.tsv`) elenchino i membri. Se sul PC non c'e' Python 3 la prova si salta (lo dice).
 * Le prove del generatore sul campione le fa lo stesso script (`--verifica`), lanciato da qui.
 */
class TripFixtureGeneratorTest {
  @get:Rule val tmp = TemporaryFolder()

  private val script: File = File(repositoryRoot(), "tools/fixtures/trips/generate_trips.py")

  /** Il primo interprete Python 3 che risponde, o null. */
  private fun python(): List<String>? {
    for (candidate in listOf(listOf("python"), listOf("python3"), listOf("py", "-3"))) {
      try {
        val p = ProcessBuilder(candidate + listOf("-c", "import sys; print(sys.version_info[0])")).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out == "3") return candidate
      } catch (e: java.io.IOException) {
        // questo nome non c'e'
      }
    }
    return null
  }

  private fun run(python: List<String>, vararg args: String): Pair<Int, String> {
    val p = ProcessBuilder(python + listOf("-I", script.path) + args).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    check(p.waitFor(180, TimeUnit.SECONDS)) { "Il generatore non e' finito in tempo." }
    return p.exitValue() to text
  }

  @Test
  fun leProveDelGeneratoreSulCampionePassano() {
    val found = python()
    assumeTrue("Python 3 non c'e' su questo PC: prova del generatore saltata", found != null)
    val (code, text) = run(found!!, "--verifica")
    assertThat(text).contains("tutto a posto")
    assertThat(code).isEqualTo(0)
  }

  @Test
  fun laPrimaStoriaDiP6DiventaUnFilePerFoto() {
    val found = python()
    assumeTrue("Python 3 non c'e' su questo PC: prova del generatore saltata", found != null)
    val python = found!!
    val story = SyntheticEvalKit.stories().first()
    val spec = TripFixtureSpec.Spec(TripFixtureSpec.rowsOf(0, story), TripFixtureSpec.albumsOf(0, story))
    val (media, albums) = TripFixtureSpec.write(spec)
    val specFile = tmp.newFile("spec-media.tsv").apply { writeText(media, Charsets.UTF_8) }
    val albumFile = tmp.newFile("spec-album.tsv").apply { writeText(albums, Charsets.UTF_8) }
    val out = tmp.newFolder("uscita")
    val (code, text) = run(python, "--spec", specFile.path, "--albums", albumFile.path, "--out", out.path)
    assertThat(code).isEqualTo(0)
    assertThat(text).contains("storia 00: ${spec.rows.size} file")

    val base = File(out, "MCG-viaggi/storia-00")
    val jpegs = base.walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") }.toList()
    assertThat(jpegs).hasSize(spec.rows.size)
    assertThat(jpegs.map { it.name }.distinct()).hasSize(spec.rows.size)
    // Le cartelle: il telefono in DCIM, gli screenshot in Pictures, il tablet nell'area privata, i familiari nell'area comune.
    fun under(path: String) = jpegs.count { it.relativeTo(base).invariantPath().startsWith(path) }
    assertThat(under("telefono/DCIM/mcg-prova/")).isEqualTo(spec.rows.count { it.onPhone && !it.screen })
    assertThat(under("telefono/Pictures/mcg-prova/")).isEqualTo(spec.rows.count { it.screen && it.device == TripFixtureSpec.PHONE })
    assertThat(under("nas-tablet/Backup/tablet-di-casa/")).isEqualTo(spec.rows.count { it.device == TripFixtureSpec.TABLET })
    assertThat(under("nas-famiglia/Famiglia/")).isEqualTo(spec.rows.count { it.source == dev.pampa.mycloudgallery.core.albums.contract.SmartSource.FAMILY })
    assertThat(jpegs.filter { it.name.startsWith("Screenshot_") }).hasSize(spec.rows.count { it.screen })
    assertThat(jpegs.all { it.length() in 300..4_000 }).isTrue()

    // Il manifesto: una riga per file con l'impronta e i byte veri.
    val manifest = File(base, "MANIFEST.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    assertThat(manifest).hasSize(spec.rows.size)
    for (c in manifest.take(25)) {
      val file = File(base, c[0])
      assertThat(c[12]).isEqualTo(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
      assertThat(c[13].toInt()).isEqualTo(file.length().toInt())
    }

    // Gli album per lo strumento: i titoli della storia e i loro membri, per nome di file.
    val lines = File(base, "viaggi-sintetici.tsv").readLines(Charsets.UTF_8)
    assertThat(lines.first()).isEqualTo("#MCGSINT1\tstoria=0")
    val albumLines = lines.filter { it.startsWith("A\t") }.map { it.split('\t') }
    assertThat(albumLines.map { it[2] }).containsExactlyElementsIn(spec.albums.map { it.title })
    val members = lines.filter { it.startsWith("M\t") }.map { it.split('\t') }
    val names = jpegs.map { it.name }.toSet()
    assertThat(members.all { it[2] in names }).isTrue()
    assertThat(members).hasSize(spec.rows.sumOf { it.albums.size })
    // Uscita deterministica: lo stesso input, gli stessi byte.
    val again = tmp.newFolder("uscita2")
    run(python, "--spec", specFile.path, "--albums", albumFile.path, "--out", again.path)
    val first = File(base, "MANIFEST.tsv").readText()
    assertThat(File(again, "MCG-viaggi/storia-00/MANIFEST.tsv").readText()).isEqualTo(first)
  }

  private fun File.invariantPath(): String = path.replace('\\', '/')

  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }
}
