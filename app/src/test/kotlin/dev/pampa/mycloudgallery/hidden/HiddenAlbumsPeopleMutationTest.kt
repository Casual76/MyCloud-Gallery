package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.LookupPeopleMembership
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.model.PeopleMode
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 8 (P10, D27, D28, riga PEOPLE del registro): **la prova al contrario degli album smart con le persone** (`albums-people`). La sonda e' verde con i filtri (la
 * visibilita' vera nel motore e dentro `PeopleVisibility`) e diventa **rossa** togliendoli (una `VisibleMedia` che lascia passare tutto e una `HiddenMask` che non nasconde
 * niente), su un mondo con "ultimi nascosti" -- quelli che l'istantanea ha ancora e che solo il filtro toglie. Se questa prova passasse sempre, la sonda non proverebbe
 * niente. In piu': con tutto nascosto nessun album con le persone si riempie (E-F38).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenAlbumsPeopleMutationTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val worlds = listOf(WorldSpec.onlyLateHidden(), WorldSpec.random(3), WorldSpec.random(11), WorldSpec.random(21))

  /** Cosa la sonda trova di sbagliato sul mondo: media che non devono comparire, o frasi dei conti e delle classi. */
  private suspend fun violations(leaky: Boolean, world: BuiltWorld): List<String> {
    val probed = RealAlbumsPeopleProbes.albumsPeople(leaky).probe(world)
    val out = ArrayList<String>()
    val leaked = probed.shown.count { it in world.liveMustNeverShow }
    if (leaked > 0) out += "mostra $leaked media che non deve mostrare mai"
    probed.within?.let { within -> if (probed.shown.any { it !in within }) out += "mostra media fuori da quelli mostrabili" }
    out += probed.problems
    return out
  }

  @Test
  fun conIFiltri_laSondaDegliAlbumConLePersoneEVerde() = runBlocking<Unit> {
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
  fun senzaIFiltri_laSondaDiventaRossa() = runBlocking<Unit> {
    var caught = 0
    var leaked = 0
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertThat(world.liveMustNeverShow).isNotEmpty()
        val found = violations(leaky = true, world)
        if (found.isNotEmpty()) caught++
        // La sonda non e' rossa per un motivo qualunque: un media che non deve mai comparire compare davvero.
        if (found.any { it.startsWith("mostra") }) leaked++
      } finally {
        world.close()
      }
    }
    assertWithMessage("la sonda albums-people non si accorge che i filtri mancano").that(caught).isAtLeast(1)
    assertWithMessage("senza i filtri nessun nascosto compare negli album").that(leaked).isAtLeast(1)
  }

  /** Il mondo estremo: tutto nascosto. Nessun album con le persone ha un elemento o un suggerito (E-F38). */
  @Test
  fun seTuttoENascosto_nessunAlbumSiRiempie() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.everythingHidden())
    try {
      assertThat(violations(leaky = false, world)).isEmpty()
      RealPeopleStack.build(world).use { people ->
        val lookup = IndexPeopleLookup(people.people, people.cells, PeopleVisibility(people.visible, people.mask))
        val stack = RealSmartStack(world, people = LookupPeopleMembership(lookup))
        stack.enrich()
        val ids = people.named.map { it.first }
        val album = stack.createAlbum(1, SmartRules(null, null, null, people = PeopleRule(ids, PeopleMode.ANY)))
        stack.engine.evaluate(album, Duration.INFINITE)
        val (sure, suggested) = stack.written(album)
        assertThat(sure).isEmpty()
        assertThat(suggested).isEmpty()
      }
    } finally {
      world.close()
    }
  }
}
