package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 7 (P9, D22, C27, 7.1): **il test di proprieta' dei Nascosti**. Per ogni mondo casuale (Libreria con nascosti a caso, gruppi di copie simili
 * con un nascosto dentro, raffiche, copie nell'area privata e nell'area comune, preferiti) e per ogni superficie del registro [HiddenSurfaces]
 * che ha una sonda: la superficie non mostra **mai** un media che l'oracolo vieta (i nascosti e tutti i membri dei gruppi in cui qualcuno e'
 * nascosto, E-L11), non mostra niente che non esista, mostra esattamente cio' che deve quando la sonda lo sa, e non ha altre violazioni (conteggi,
 * copertine). Lo spec e' riproducibile dal seme: un fallimento dice quale.
 *
 * Il test e' provato anche al contrario: una superficie che sbaglia apposta (mostra un nascosto) fa fallire il controllo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenSurfacesPropertyTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /** Le violazioni di una sonda su un mondo: frasi senza id. */
  private fun violations(entry: SurfaceEntry, world: BuiltWorld, probed: Probed): List<String> {
    val out = ArrayList<String>()
    val forbidden = if (entry.live) world.liveMustNeverShow else world.mustNeverShow
    val leaked = probed.shown.count { it in forbidden }
    if (leaked > 0) out += "${entry.id}: mostra $leaked media che non deve mostrare mai (nascosti o membri di un gruppo con un nascosto)"
    val phantom = probed.shown.count { it !in world.allIds }
    if (phantom > 0) out += "${entry.id}: mostra $phantom media che non esistono"
    probed.within?.let { within ->
      val outside = probed.shown.count { it !in within }
      if (outside > 0) out += "${entry.id}: mostra $outside media fuori da quelli che puo' mostrare"
    }
    probed.expected?.let { expected ->
      val missing = (expected - probed.shown).size
      val extra = (probed.shown - expected).size
      if (missing > 0) out += "${entry.id}: non mostra $missing media che dovrebbe mostrare"
      if (extra > 0) out += "${entry.id}: mostra $extra media in piu' del previsto"
    }
    probed.problems.forEach { out += "${entry.id}: $it" }
    return out
  }

  private fun runWorld(spec: WorldSpec): List<String> = runBlocking {
    val world = HiddenWorldBuilder.build(context, spec)
    try {
      HiddenSurfaces.entries.filter { it.probe != null }.flatMap { entry ->
        val probed = try {
          entry.probe!!.probe(world)
        } catch (e: Exception) {
          return@flatMap listOf("${entry.id}: la sonda e' fallita con ${e.javaClass.simpleName}")
        }
        violations(entry, world, probed)
      }.map { "[${spec}] $it" }
    } finally {
      world.close()
    }
  }

  @Test
  fun nessunaSuperficieMostraMaiUnNascosto_suMondiCasuali() {
    val failures = (1L..SEEDS).flatMap { runWorld(WorldSpec.random(it)) }
    assertWithMessage("superfici che mostrano nascosti o sbagliano i numeri").that(failures).isEmpty()
  }

  @Test
  fun neiCasiEstremiTuttoNascostoENienteNascosto() {
    val failures = runWorld(WorldSpec.everythingHidden()) + runWorld(WorldSpec.nothingHidden()) + runWorld(WorldSpec.onlyLateHidden())
    assertWithMessage("casi estremi").that(failures).isEmpty()
  }

  @Test
  fun unaLibreriaPiuGrandeSiComportaAllaStessaManiera() {
    val failures = runWorld(WorldSpec.random(seed = 777, size = 260))
    assertWithMessage("Libreria grande").that(failures).isEmpty()
  }

  /** I mondi casuali hanno davvero di che provare: nascosti, gruppi con un nascosto dentro e dati arrivati a meta'. */
  @Test
  fun iMondiCasualiSonoInteressanti() {
    val specs = (1L..SEEDS).map { WorldSpec.random(it) }
    assertThat(specs.count { it.hiddenByUser.isNotEmpty() }).isEqualTo(SEEDS.toInt())
    assertThat(specs.count { s -> s.groups.any { g -> g.members.any { it in s.hiddenByUser } } }).isAtLeast(SEEDS.toInt() / 2)
    assertThat(specs.count { it.hiddenPartial.isNotEmpty() }).isAtLeast(SEEDS.toInt() / 4)
    assertThat(specs.count { s -> s.lateHidden.any { late -> s.groups.any { late in it.members } } }).isAtLeast(SEEDS.toInt() / 4)
    assertThat(specs.count { s -> s.lateHidden.any { late -> s.groups.none { late in it.members } } }).isAtLeast(SEEDS.toInt() / 4)
    assertThat(specs.count { s -> s.media.any { it.where == Where.NAS_COMMON && it.index in HiddenOracle(s).mustNeverShow } }).isAtLeast(SEEDS.toInt() / 2)
    assertThat(WorldSpec.random(5).toString()).isEqualTo(WorldSpec.random(5).toString())
  }

  /** Il controllo non e' una formalita': una superficie che mostra un nascosto, o un membro di un gruppo nascosto, viene presa. */
  @Test
  fun unaSuperficieCheSbagliaApposta_FaFallireIlControllo() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.random(3))
    try {
      assertThat(world.mustNeverShow).isNotEmpty()
      val leaky = SurfaceEntry("perde", "Perde", SpecRow.LIBRARY, "prova", Backing.PRODUCTION, probe = { w -> Probed(shown = w.allIds) })
      assertThat(violations(leaky, world, leaky.probe!!.probe(world))).isNotEmpty()

      // Perde solo un membro di un gruppo in cui un altro e' nascosto (il caso E-L11): lo prende lo stesso.
      val groupMate = world.spec.groups.firstNotNullOfOrNull { g ->
        g.members.firstOrNull { it in world.oracle.mustNeverShow && it !in world.spec.hiddenByUser && it !in world.spec.hiddenPartial }
      }
      if (groupMate != null) {
        val subtle = SurfaceEntry("subdola", "Subdola", SpecRow.LIBRARY, "prova", Backing.PRODUCTION, probe = { w -> Probed(shown = setOf(w.ids[groupMate])) })
        assertThat(violations(subtle, world, subtle.probe!!.probe(world))).isNotEmpty()
      }

      // Una superficie che non mostra niente non passa quando la sonda sa cosa deve mostrare.
      val empty = SurfaceEntry("vuota", "Vuota", SpecRow.LIBRARY, "prova", Backing.PRODUCTION, probe = { w -> Probed(shown = emptySet<MediaId>(), expected = w.libraryCells) })
      assertThat(violations(empty, world, empty.probe!!.probe(world))).isNotEmpty()
    } finally {
      world.close()
    }
  }

  private companion object {
    /** Quanti mondi casuali (ognuno costruito nel catalogo vero). */
    const val SEEDS = 40L
  }
}
