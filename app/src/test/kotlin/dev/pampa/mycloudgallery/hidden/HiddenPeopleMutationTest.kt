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
 * Fase 8 (P9, D28, riga PEOPLE del registro): **la prova al contrario delle persone**. La proprieta' dei Nascosti passa solo se il filtro c'e': qui lo si toglie (una
 * visibilita' che lascia passare tutto e una maschera che non nasconde niente, dentro `PeopleVisibility`) e ognuna delle cinque sonde delle persone deve diventare
 * rossa, su un mondo con "ultimi nascosti" (che l'istantanea della Libreria ha ancora: sono loro che escono se il filtro manca). Con il filtro, gli stessi mondi sono
 * verdi: se questa prova passasse sempre, le sonde non proverebbero niente.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenPeopleMutationTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private class Named(val name: String, val make: (Boolean) -> SurfaceProbe)

  private val probes = listOf(
    Named("people-lists", RealPeopleProbes::peopleLists),
    Named("person-pages", RealPeopleProbes::personPages),
    Named("face-review", RealPeopleProbes::faceReview),
    Named("viewer-people", RealPeopleProbes::viewerRow),
    Named("people-dictionary", RealPeopleProbes::dictionary),
  )

  /** Cosa la sonda trova di sbagliato sul mondo: media che non devono comparire, o frasi dei conti e dei cancelli. */
  private suspend fun violations(probe: SurfaceProbe, world: BuiltWorld): List<String> {
    val probed = probe.probe(world)
    val out = ArrayList<String>()
    val leaked = probed.shown.count { it in world.liveMustNeverShow }
    if (leaked > 0) out += "mostra $leaked media che non deve mostrare mai"
    probed.within?.let { within -> if (probed.shown.any { it !in within }) out += "mostra media fuori da quelli mostrabili" }
    out += probed.problems
    return out
  }

  private val worlds = listOf(WorldSpec.onlyLateHidden(), WorldSpec.random(3), WorldSpec.random(11), WorldSpec.random(21))

  @Test
  fun conIlFiltro_leSondeDellePersoneSonoVerdi() = runBlocking<Unit> {
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        for (named in probes) assertWithMessage("[$spec] ${named.name}").that(violations(named.make(false), world)).isEmpty()
      } finally {
        world.close()
      }
    }
  }

  @Test
  fun senzaIlFiltro_ognunaDelleSondeDiventaRossa() = runBlocking<Unit> {
    val caught = HashMap<String, Int>()
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertThat(world.liveMustNeverShow).isNotEmpty()
        for (named in probes) {
          val found = violations(named.make(true), world)
          if (found.isNotEmpty()) caught.merge(named.name, 1, Int::plus)
        }
      } finally {
        world.close()
      }
    }
    for (named in probes) assertWithMessage("la sonda ${named.name} non si accorge che il filtro manca").that(caught[named.name] ?: 0).isAtLeast(1)
  }

  /** Il mondo estremo: tutto nascosto. Nessuna persona, nessuna foto, in nessuna superficie (E-F38). */
  @Test
  fun seTuttoENascosto_nonCompareNiente() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.everythingHidden())
    try {
      for (named in probes) assertWithMessage(named.name).that(violations(named.make(false), world)).isEmpty()
      RealPeopleStack.build(world).use { stack ->
        assertThat(stack.people.list.value.named).isEmpty()
        assertThat(stack.people.list.value.othersCount).isEqualTo(0)
        assertThat(stack.people.dictionary()).isEmpty()
      }
    } finally {
      world.close()
    }
  }
}
