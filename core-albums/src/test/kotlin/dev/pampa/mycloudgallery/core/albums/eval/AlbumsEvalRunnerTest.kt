package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.Spots
import dev.pampa.mycloudgallery.core.albums.smart.cand
import dev.pampa.mycloudgallery.core.albums.smart.id
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La corsa di un viaggio (riga P12 del §4.4): il titolo diventa un album **in memoria**, il motore lo valuta sui candidati, si leggono sicuri e
 * suggeriti. Un nascosto non esce mai; la Libreria non cambia; due corse di fila danno lo stesso risultato. Dati sintetici (Oslo, 2025).
 */
class AlbumsEvalRunnerTest {
  private val today = LocalDate.of(2026, 1, 15)

  private fun d(day: Int) = LocalDate.of(2025, 3, day)

  /** Tre giorni a Oslo (14, 15, 16 marzo) con quattro foto al giorno, uno screenshot e un familiare; poche foto di casa a Firenze. */
  private fun world(): MemoryWorld = MemoryWorld(emptyList(), Spots.PLACES).also { w ->
    var n = 1
    for (day in 14..16) repeat(4) { k -> w.put(cand(n++, d(day), 9 * 60 + k * 60, gps = Spots.north(Spots.OSLO, 1.0 + k), placeId = Spots.OSLO_ID)) }
    w.put(cand(100, d(15), 11 * 60, screen = true, device = "phone"))
    w.put(cand(101, d(15), 13 * 60, gps = Spots.north(Spots.OSLO, 2.0), placeId = Spots.OSLO_ID, device = null, source = SmartSource.FAMILY))
    repeat(3) { k -> w.put(cand(200 + k, d(2 + k), 20 * 60, gps = Spots.FIRENZE, placeId = Spots.FIRENZE_ID)) }
    w.publish()
  }

  private fun ports(w: MemoryWorld) = AlbumsEvalPorts(
    candidates = w, visible = w, mask = w, parser = ItalianQueryParser(), lookup = w.placeLookup, search = NoSmartContentSearch,
    devices = DeviceNormalizer(null), clocks = FakeAlbumClocks(), geocoder = null, today = { today },
  )

  @Test
  fun ilTitoloDiventaUnAlbumConISicuriEISuggeriti() = runTest {
    val run = AlbumsEvalRunner(ports(world())).run("v01", "Oslo marzo 2025")
    assertThat(run.tripId).isEqualTo("v01")
    // I dodici scatti col GPS a Oslo sono sicuri (A); lo screenshot e il familiare sono suggeriti (S2, S1); le foto di casa restano fuori.
    assertThat(run.sure).containsExactlyElementsIn((1..12).map { id(it).value })
    assertThat(run.suggested).containsExactly(id(100).value, id(101).value)
    assertThat(run.placeRecognized).isTrue()
    assertThat(run.series).isEqualTo(1)
    assertThat(run.searchUsed).isFalse()
  }

  @Test
  fun unNascostoNonEsceMaiNeFraISicuriNeFraISuggeriti() = runTest {
    val w = world()
    w.hide(listOf(id(3), id(100)))
    val run = AlbumsEvalRunner(ports(w)).run("v01", "Oslo marzo 2025")
    assertThat(run.sure).doesNotContain(id(3).value)
    assertThat(run.suggested).doesNotContain(id(100).value)
    assertThat(run.sure).hasSize(11)
    // Anche un nascosto che trascina il suo gruppo.
    val grouped = world()
    grouped.groups += setOf(id(5), id(6))
    grouped.hide(id(5))
    val groupRun = AlbumsEvalRunner(ports(grouped)).run("v01", "Oslo marzo 2025")
    assertThat(groupRun.sure).containsNoneOf(id(5).value, id(6).value)
  }

  @Test
  fun dueCorseDiFilaDannoLoStessoRisultatoELaLibreriaNonCambia() = runTest {
    val w = world()
    val before = w.items().map { it.media }
    val runner = AlbumsEvalRunner(ports(w))
    val first = runner.run("v01", "Oslo marzo 2025")
    val second = runner.run("v01", "Oslo marzo 2025")
    assertThat(second.sure).isEqualTo(first.sure)
    assertThat(second.suggested).isEqualTo(first.suggested)
    assertThat(w.items().map { it.media }).isEqualTo(before)
    assertThat(w.hidden).isEmpty()
  }

  @Test
  fun unTitoloSenzaRegoleDaUnAlbumVuoto() = runTest {
    val run = AlbumsEvalRunner(ports(world())).run("v01", "Una gita senza data ne luogo")
    assertThat(run.sure).isEmpty()
    assertThat(run.suggested).isEmpty()
    assertThat(run.placeRecognized).isFalse()
    // E il giudizio non inventa proporzioni.
    assertThat(AlbumsEvalMetrics.judge(run, setOf(id(1).value), emptySet()).precision.isNaN()).isTrue()
  }

  @Test
  fun leRigheDellaCorsaSonoInMemoriaMaiQuelleDellaLibreria() {
    // La corsa non nomina mai le righe su Room ne' il database: scrive solo in `EvalSmartRows`.
    val source = File(repositoryRoot(), "core-albums/src/test/kotlin/dev/pampa/mycloudgallery/core/albums/eval/AlbumsEvalRunner.kt")
    val code = source.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
    for (forbidden in listOf("RoomSmartRows", "GalleryDatabase", "database", "Room.")) {
      assertThat(code.filter { it.contains(forbidden) }).isEmpty()
    }
  }

  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }
}
