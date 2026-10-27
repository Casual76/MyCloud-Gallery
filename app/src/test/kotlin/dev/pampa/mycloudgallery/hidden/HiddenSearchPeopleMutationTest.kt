package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 8 (P10, D26, D28, riga PEOPLE del registro): **la prova al contrario della ricerca per persona** (`search-people`). La sonda e' verde con il filtro (la
 * visibilita' vera dentro `PeopleVisibility`, da cui passano i candidati e il dizionario) e diventa **rossa** togliendolo (una `VisibleMedia` che lascia passare
 * tutto e una `HiddenMask` che non nasconde niente), su un mondo con "ultimi nascosti" -- quelli che l'istantanea ha ancora e che solo il filtro toglie. Se questa prova
 * passasse sempre, la sonda non proverebbe niente. In piu': con tutto nascosto non esce nessuna persona, nessun chip e nessun risultato (E-F38).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenSearchPeopleMutationTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val worlds = listOf(WorldSpec.onlyLateHidden(), WorldSpec.random(3), WorldSpec.random(11), WorldSpec.random(21))

  /** Cosa la sonda trova di sbagliato sul mondo: media che non devono comparire, o frasi dei conti e dei candidati. */
  private suspend fun violations(leaky: Boolean, world: BuiltWorld): List<String> {
    val probed = RealSearchPeopleProbes.searchPeople(leaky).probe(world)
    val out = ArrayList<String>()
    val leaked = probed.shown.count { it in world.liveMustNeverShow }
    if (leaked > 0) out += "mostra $leaked media che non deve mostrare mai"
    probed.within?.let { within -> if (probed.shown.any { it !in within }) out += "mostra media fuori da quelli mostrabili" }
    out += probed.problems
    return out
  }

  @Test
  fun conIlFiltro_laSondaDellaRicercaPerPersonaEVerde() = runBlocking<Unit> {
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertWithMessage("[$spec]").that(violations(leaky = false, world)).isEmpty()
      } finally {
        world.close()
      }
    }
  }

  @Test
  fun senzaIlFiltro_laSondaDiventaRossa() = runBlocking<Unit> {
    var caught = 0
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertThat(world.liveMustNeverShow).isNotEmpty()
        if (violations(leaky = true, world).isNotEmpty()) caught++
      } finally {
        world.close()
      }
    }
    assertWithMessage("la sonda search-people non si accorge che il filtro manca").that(caught).isAtLeast(1)
  }

  /** Il mondo estremo: tutto nascosto. Nessuna persona, nessun chip, nessun risultato (E-F38). */
  @Test
  fun seTuttoENascosto_nonEscePiuNiente() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.everythingHidden())
    try {
      assertThat(violations(leaky = false, world)).isEmpty()
      RealPeopleStack.build(world).use { stack ->
        val lookup = IndexPeopleLookup(stack.people, stack.cells, dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility(stack.visible, stack.mask))
        assertThat(lookup.dictionary().isEmpty).isTrue()
        val engine = dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine(
          dev.pampa.mycloudgallery.search.CatalogSearchData(kotlinx.coroutines.flow.MutableStateFlow(world.snapshot), world.db) { world.catalog },
          null,
          dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser(),
          dev.pampa.mycloudgallery.core.search.testing.FixedToday(java.time.LocalDate.of(2026, 10, 21)),
          people = lookup,
        )
        val results = engine.search(SearchRequest("Marco"))
        assertThat(results.chips.filterIsInstance<SearchChip.Person>()).isEmpty()
        assertThat(results.confident.size + results.likely.size).isEqualTo(0)
      }
    } finally {
      world.close()
    }
  }
}
