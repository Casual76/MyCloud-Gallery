package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.TripKind
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La Libreria di prova per il telefono (docs/fase9-progetto.md §5.3, riga P11 del §4.4; `tools/fixtures/memories/`): la specifica che il generatore legge si scrive da
 * qui, con **giorni relativi** al giorno della prova e senza nessun dato personale. Il test scrive anche i numeri che la JVM attende (`attesi.tsv`) in
 * `core-memories/build/memories-fixtures/` e controlla che il campione committato per le prove del generatore Python sia una parte della specifica di oggi: se la
 * Libreria cambia, il test dice di rigenerarlo (il file e' scritto accanto).
 */
class MemoriesFixtureExportTest {

  private val world = familyWorld(EVAL_TODAY)
  private val rows: List<String> = MemoriesFixtureKit.rows(world)

  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }

  private fun outDir(): File = File("build/memories-fixtures").absoluteFile.also { it.mkdirs() }

  /** Il campione: i giorni di una festa di un anno fa (coi nascosti perfetti), un giorno triste e un giorno di soli screenshot. */
  private fun sample(): List<String> {
    val keep = setOf(1 to 3, 2 to 12, 1 to 5)
    return listOf(MemoriesFixtureKit.HEADER) + rows.drop(1).filter { line -> line.split('\t').let { (it[1].toInt() to it[2].toInt()) in keep } }
  }

  @Test
  fun laSpecificaHaUnaRigaPerFotoESoloGiorniRelativi() {
    assertThat(rows.first()).isEqualTo(MemoriesFixtureKit.HEADER)
    assertThat(rows.size - 1).isEqualTo(world.shots.size)
    File(outDir(), "spec-media.tsv").writeText(rows.joinToString("\n") + "\n")
    for (line in rows.drop(1)) {
      val c = line.split('\t')
      assertThat(c).hasSize(13)
      val years = c[1].toInt()
      val days = c[2].toInt()
      assertThat(years).isIn(0..5)
      assertThat(days).isIn(0..365)
      assertThat(c[3].toInt()).isIn(0 until 86_400)
      assertThat(c[4]).isIn(listOf("camera", "screenshot", "whatsapp"))
      assertThat(c[5]).isIn(listOf("foto", "video", "animata"))
      assertThat(c[8]).matches("-|[abc](;[abc])*")
      assertThat(c[7]).matches("u|g[0-9]+|s[0-9]+|triste|documento|estranea|perfetta")
    }
    // Nessuna data scritta, nessun id vero: solo numeri relativi e parole fisse.
    assertThat(rows.joinToString("\n")).doesNotContainMatch("(19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}")
    assertThat(rows.joinToString("\n")).doesNotContainMatch("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  }

  @Test
  fun ogniGiornoSiRicostruisceDaOggiEDagliAnniFaPerQualunqueOggi() {
    val today = EVAL_TODAY
    for ((id, shot) in world.shots) {
      val (years, days) = MemoriesFixtureKit.relative(today, shot.day)
      assertWithMessage("giorno di una foto").that(MemoriesFixtureKit.dayOf(today, years, days)).isEqualTo(shot.day)
    }
    // Con un altro "oggi" (anche un 29 febbraio) la stessa riga cade lo stesso numero di giorni e di anni prima: gli anniversari restano anniversari.
    for (other in listOf(LocalDate.of(2027, 3, 5), LocalDate.of(2028, 2, 29))) {
      val party = MemoriesFixtureKit.dayOf(other, 1, 3)
      assertThat(party.plusYears(1)).isEqualTo(other.minusDays(3))
    }
  }

  @Test
  fun iConcettiEIFlagDelBancoCiSono() {
    val c = rows.drop(1).map { it.split('\t') }
    assertThat(c.count { it[7] == "perfetta" && it[12] == "1" }).isEqualTo(54)
    assertThat(c.count { it[12] == "1" }).isEqualTo(54)
    assertThat(c.count { it[7] == "triste" }).isEqualTo(world.shots.values.count { it.fault == EvalFault.SAD && it.dupGroup == null && it.similarTo == null })
    assertThat(c.count { it[7].startsWith("g") }).isEqualTo(world.shots.values.count { it.dupGroup != null })
    assertThat(c.map { it[8] }.toSet().flatMap { it.split(';') }.toSet()).containsExactly("-", "a", "b", "c")
    assertThat(c.count { it[10] == "1" }).isEqualTo(world.shots.values.count { it.blurry })
    assertThat(c.count { it[11] == "1" }).isEqualTo(world.shots.values.count { it.dark })
    assertThat(c.count { it[4] == "screenshot" }).isGreaterThan(200)
    // I luoghi sono id finti, mai coordinate.
    assertThat(c.map { it[9].toInt() }.filter { it != 0 }.all { it >= 9_000_000 }).isTrue()
  }

  @Test
  fun ilCampioneEUnaParteDellaSpecificaDiOggi() {
    val fresh = sample()
    File(outDir(), "spec-campione.tsv").writeText(fresh.joinToString("\n") + "\n")
    assertThat(fresh.size - 1).isIn(20..80)
    val committed = File(repositoryRoot(), "tools/fixtures/memories/campione/spec-campione.tsv")
    assertWithMessage("manca il campione: copiare core-memories/build/memories-fixtures/spec-campione.tsv in tools/fixtures/memories/campione/").that(committed.isFile).isTrue()
    val lines = committed.readLines().filter { it.isNotEmpty() }
    assertWithMessage("il campione non e' piu' una parte della Libreria di oggi: rigenerarlo da core-memories/build/memories-fixtures/spec-campione.tsv").that(lines).isEqualTo(fresh)
  }

  @Test
  fun iNumeriAttesiDallaJvmSiScrivonoESonoSoloNumeri() {
    val run = runBlocking { FamilyRig(world, Executor.REFERENCE).use { it.run() } }
    val expectations = MemoriesFixtureKit.expectations(world, run)
    File(outDir(), "attesi.tsv").writeText(expectations.joinToString("\n") + "\n")
    val map = expectations.filter { !it.startsWith("#") }.associate { line -> line.split('\t').let { it[0] to it[1].toInt() } }
    assertThat(map["ricordi"]).isEqualTo(run.size)
    assertThat(map.keys).containsAtLeast("ricordi", "foto", "bello", "da_non_mostrare", "foto_da_togliere")
    assertThat(expectations.joinToString("\n")).doesNotContainMatch("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    // Il tipo evento e' dentro i numeri come ogni altro.
    assertThat(world.trips.any { it.kind == TripKind.EVENT }).isTrue()
    assertThat(map.keys.any { it.startsWith("ricordi_") }).isTrue()
  }
}
