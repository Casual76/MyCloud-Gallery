package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 9 (P4, D31, E-MAP5, E-MAP7, E-MAP15, E-MAP18, E-H9): le sonde della mappa sul codice vero **provano davvero qualcosa**, e la proprieta' "un nascosto perfetto non esce mai" e' verde con il filtro finale dei
 * Nascosti e **rossa togliendolo**. Il registro (`HiddenSurfacesPropertyTest`) le fa girare su 40 mondi casuali; qui se ne guardano una dozzina: le bolle nascono davvero (con le copertine nascoste dopo l'indice), le
 * griglie di una zona si aprono, la sezione Luoghi ha i suoi luoghi, le coordinate assurde e i media senza posizione ci sono e si contano, la mini-mappa c'e' per i media visibili. Una sonda che non vede mai una
 * bolla sarebbe un controllo che passa sempre.
 *
 * File nuovo di P4: `HiddenSurfacesPropertyTest` non si tocca.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RealMapProbeTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private fun worlds(range: LongRange, size: Int = 120) = range.map { WorldSpec.random(it, size) }

  private fun surfaces(report: MapReport) = listOf(report.points, report.zone, report.places, report.minimap)

  @Test
  fun leSondeDellaMappaVedonoBolleVereENonPassanoSempre() = runBlocking<Unit> {
    MapProbeStats.reset()
    val problems = ArrayList<String>()
    var checked = 0
    for (spec in worlds(1L..12L)) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMapProbes.report(world, remember = false)
        for (surface in surfaces(report)) {
          problems += surface.problems.map { "[${spec.seed}] $it" }
          assertThat(surface.shown.all { it in world.allIds }).isTrue()
          assertThat(surface.shown.none { it in world.liveMustNeverShow }).isTrue()
        }
        checked++
      } finally {
        world.close()
      }
    }
    assertWithMessage("le superfici della mappa hanno violazioni").that(problems).isEmpty()
    assertThat(MapProbeStats.worlds).isEqualTo(checked)
    // Le bolle, le vittime nascoste dopo l'indice, le griglie, le coordinate assurde, i senza posizione, i luoghi e le mini-mappe ci sono davvero.
    assertThat(MapProbeStats.bubbles).isAtLeast(checked * 4)
    assertThat(MapProbeStats.victims).isAtLeast(checked)
    assertThat(MapProbeStats.zones).isAtLeast(checked * 2)
    assertThat(MapProbeStats.invalid).isAtLeast(checked)
    assertThat(MapProbeStats.withoutPosition).isAtLeast(checked)
    assertThat(MapProbeStats.placeRows).isAtLeast(checked * 2)
    assertThat(MapProbeStats.minimaps).isAtLeast(checked * 20)
  }

  /** "La proprieta' e' verde, e rossa togliendo il filtro (provalo)": senza il filtro finale i nascosti dell'ultimo momento escono da bolle, griglie e mini-mappe. */
  @Test
  fun senzaIlFiltroFinaleDeiNascostiLaProprietaERossa() = runBlocking<Unit> {
    var red = 0
    var examined = 0
    val sample = ArrayList<String>()
    for (spec in worlds(1L..10L)) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMapProbes.report(world, finalFilter = false, remember = false)
        val problems = listOf(report.points, report.zone, report.minimap).flatMap { it.problems }
        examined++
        if (problems.isNotEmpty()) red++
        sample += problems.take(2)
      } finally {
        world.close()
      }
    }
    assertWithMessage("senza il filtro finale la proprieta' avrebbe dovuto diventare rossa: ${sample.take(4)}").that(red).isAtLeast(examined * 3 / 4)
  }

  /** Il caso peggiore e quello opposto: tutto nascosto non ha bolle (e niente crolla), niente nascosto le ha tutte. */
  @Test
  fun neiCasiEstremiSiComportaComeDeve() = runBlocking<Unit> {
    for (spec in listOf(WorldSpec.everythingHidden(), WorldSpec.nothingHidden(), WorldSpec.onlyLateHidden())) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMapProbes.report(world, remember = false)
        for (surface in surfaces(report)) {
          assertWithMessage(spec.toString()).that(surface.problems).isEmpty()
          assertThat(surface.shown.none { it in world.liveMustNeverShow }).isTrue()
        }
      } finally {
        world.close()
      }
    }
  }

  /** Le sonde del registro leggono lo stesso giro (una volta per mondo) e il mondo di prova non cambia: nessun nascosto in piu' nel catalogo vero del mondo. */
  @Test
  fun leQuattroSondeDelRegistroCondividonoIlGiro_eNonSporcanoIlMondo() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.random(5, 120))
    try {
      val before = world.ids.count { world.db.personalDao().get(it.value)?.hidden == true }
      val first = RealMapProbes.points.probe(world)
      val second = RealMapProbes.zone.probe(world)
      val third = RealMapProbes.places.probe(world)
      val fourth = RealMapProbes.minimap.probe(world)
      assertThat(first.problems + second.problems + third.problems + fourth.problems).isEmpty()
      val after = world.ids.count { world.db.personalDao().get(it.value)?.hidden == true }
      assertThat(after).isEqualTo(before)
    } finally {
      world.close()
    }
  }
}
