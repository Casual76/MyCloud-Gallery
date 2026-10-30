package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 9 (P9, D31, E-MEM5, E-H8, E-H10): le sonde dei ricordi sul codice vero **provano davvero qualcosa**, e la proprieta' "un nascosto perfetto non esce mai" e' verde
 * con il filtro finale dei Nascosti e **rossa togliendolo**. Il registro (`HiddenSurfacesPropertyTest`) le fa girare su 40 mondi casuali; qui se ne guardano una dozzina:
 * i quattro tipi di ricordo nascono davvero (anni fa, viaggio, persona, mese), dopo la pubblicazione qualcosa viene nascosto, le pagine e le presentazioni si aprono e
 * qualche ricordo resta senza elementi e dice "non c'e' piu'". Una sonda che non vede mai un ricordo sarebbe un controllo che passa sempre.
 *
 * File nuovo di P9: `HiddenSurfacesPropertyTest` non si tocca.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RealMemoriesProbeTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private fun worlds(range: LongRange, size: Int = 120) = range.map { WorldSpec.random(it, size) }

  @Test
  fun leSondeDeiRicordiVedonoRicordiVeriENonPassanoSempre() = runBlocking<Unit> {
    RealMemoriesSurfaces.Stats.reset()
    val problems = ArrayList<String>()
    var checked = 0
    for (spec in worlds(1L..12L)) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMemoriesProbes.report(world, remember = false)
        for (surface in listOf(report.section, report.page, report.player, report.people)) {
          problems += surface.problems.map { "[${spec.seed}] $it" }
          assertThat(surface.shown.all { it in world.liveShowable }).isTrue()
          assertThat(surface.shown.none { it in world.liveMustNeverShow }).isTrue()
        }
        checked++
      } finally {
        world.close()
      }
    }
    assertWithMessage("le superfici dei ricordi hanno violazioni").that(problems).isEmpty()
    val stats = RealMemoriesSurfaces.Stats
    // Tre "oggi" per mondo: i ricordi nascono davvero, di ogni tipo.
    assertThat(stats.worlds).isEqualTo(checked * 3)
    assertThat(stats.memories).isAtLeast(checked * 2)
    assertThat(stats.kinds).containsAtLeast(MemoryKind.ON_THIS_DAY, MemoryKind.MONTH, MemoryKind.PERSON)
    assertThat(stats.victims).isAtLeast(checked)
    assertThat(stats.pagesReady).isAtLeast(checked)
    assertThat(stats.presentations).isAtLeast(checked)
    assertThat(stats.personMemories).isAtLeast(1)
  }

  /** "La proprieta' e' verde, e rossa togliendo il filtro (provalo)": senza il filtro finale i nascosti dell'ultimo momento escono dalle superfici. */
  @Test
  fun senzaIlFiltroFinaleDeiNascostiLaProprietaERossa() = runBlocking<Unit> {
    var red = 0
    var examined = 0
    val sample = ArrayList<String>()
    for (spec in worlds(1L..10L)) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMemoriesProbes.report(world, finalFilter = false, remember = false)
        val problems = listOf(report.section, report.page, report.player).flatMap { it.problems }
        examined++
        if (problems.isNotEmpty()) red++
        sample += problems.take(2)
      } finally {
        world.close()
      }
    }
    assertWithMessage("senza il filtro finale la proprieta' avrebbe dovuto diventare rossa: ${sample.take(4)}").that(red).isAtLeast(examined * 3 / 4)
  }

  /** Il caso peggiore e quello opposto: tutto nascosto non ha ricordi (e niente crolla), niente nascosto li ha tutti. */
  @Test
  fun neiCasiEstremiSiComportaComeDeve() = runBlocking<Unit> {
    for (spec in listOf(WorldSpec.everythingHidden(), WorldSpec.nothingHidden(), WorldSpec.onlyLateHidden())) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val report = RealMemoriesProbes.report(world, remember = false)
        for (surface in listOf(report.section, report.page, report.player, report.people)) {
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
      val first = RealMemoriesProbes.section.probe(world)
      val second = RealMemoriesProbes.page.probe(world)
      val third = RealMemoriesProbes.player.probe(world)
      val fourth = RealMemoriesProbes.people.probe(world)
      assertThat(first.problems + second.problems + third.problems + fourth.problems).isEmpty()
      val after = world.ids.count { world.db.personalDao().get(it.value)?.hidden == true }
      assertThat(after).isEqualTo(before)
    } finally {
      world.close()
    }
  }
}
