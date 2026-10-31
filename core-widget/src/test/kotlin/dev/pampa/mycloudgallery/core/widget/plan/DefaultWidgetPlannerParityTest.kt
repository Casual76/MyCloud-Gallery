package dev.pampa.mycloudgallery.core.widget.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.SyntheticWidgetWorld
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La parita' (docs/fase10-progetto.md §5.2): `DefaultWidgetPlanner` (sopra `RotatingWidgetFeed`) e `ReferenceWidgetPlanner` (forza bruta) su
 * **5.000 stati casuali** (Libreria da 0 a 250 celle, nascosti fuori dall'istantanea e dell'ultimo momento, gruppi con il nascosto contagioso,
 * ricordi di ogni tipo con pochi o molti elementi e la copertina a volte nascosta, miniature e presenza sul telefono a percentuali casuali, blocco
 * acceso o no, le tre scelte, le tre misure). Stesso modo, stesso ricordo e stesse celle, stesso motivo di un messaggio; ogni foto del piano vero
 * sta in `eligiblePhotos()` del riferimento, e il numero di foto e' lo stesso (la rotazione ha un ordine suo, che non si confronta).
 */
class DefaultWidgetPlannerParityTest {

  private val slot = WidgetSlot(3)

  @Test
  fun stessoPianoDelRiferimentoSuCinquemilaStati() = runTest {
    val random = Random(20261031)
    var memoryPlans = 0
    var photoPlans = 0
    var lockedPlans = 0
    var messages = 0
    repeat(5_000) { state ->
      val world = randomWorld(random, state)
      val reference = world.planner()
      val real = defaultPlanner(world)
      val size = WidgetSize.entries[random.nextInt(3)]
      // Due piani di fila: il secondo parte dal cursore che il primo ha spostato (e da un nascosto in piu' dell'ultimo momento).
      repeat(2) { round ->
        if (round == 1) world.hideLate(random.nextInt(4))
        val want = reference.plan(slot, size)
        val got = real.plan(slot, size)
        val what = "stato $state, giro $round, ${size.code}, seme ${world.seed}"
        assertSameDecision(what, world, want, got, reference.eligiblePhotos())
        when (got.mode) {
          is WidgetMode.Memory -> memoryPlans++
          WidgetMode.Photos -> photoPlans++
          WidgetMode.Locked -> lockedPlans++
          is WidgetMode.Message -> messages++
        }
      }
    }
    // Il campione tocca davvero ogni strada (altrimenti la parita' proverebbe poco).
    assertWithMessage("piani con un ricordo").that(memoryPlans).isGreaterThan(300)
    assertWithMessage("piani con foto a caso").that(photoPlans).isGreaterThan(1_000)
    assertWithMessage("piani con il blocco").that(lockedPlans).isGreaterThan(500)
    assertWithMessage("piani senza foto").that(messages).isGreaterThan(300)
  }

  private fun assertSameDecision(what: String, world: SyntheticWidgetWorld, want: WidgetPlan, got: WidgetPlan, eligible: Set<MediaId>) {
    assertWithMessage("$what: il modo").that(got.mode::class).isEqualTo(want.mode::class)
    when (val mode = want.mode) {
      is WidgetMode.Memory -> {
        assertThat(got.mode).isEqualTo(mode)
        assertWithMessage("$what: celle del ricordo").that(got.cells).isEqualTo(want.cells)
      }
      is WidgetMode.Message -> assertWithMessage("$what: il motivo").that(got.mode).isEqualTo(mode)
      WidgetMode.Photos, WidgetMode.Locked -> {
        assertWithMessage("$what: foto fuori da quelle ammesse").that(got.shown - eligible).isEmpty()
        assertWithMessage("$what: quante foto").that(got.cells.size).isEqualTo(want.cells.size)
        assertWithMessage("$what: tipo e giorno di ogni cella").that(got.cells.all { it.kind == world.snapshot.kindAt(world.snapshot.indexOf(it.media)) }).isTrue()
      }
    }
    assertWithMessage("$what: un nascosto").that(got.shown intersect world.hiddenNow).isEmpty()
  }

  private fun randomWorld(random: Random, state: Int): SyntheticWidgetWorld {
    val count = when (random.nextInt(10)) {
      0 -> 0
      1 -> random.nextInt(1, 12)
      else -> random.nextInt(12, 251)
    }
    val world = SyntheticWidgetWorld(
      seed = 1_000L + state, count = count,
      thumbPercent = listOf(0, 5, 40, 70, 100)[random.nextInt(5)],
      phonePercent = listOf(0, 20, 50, 100)[random.nextInt(4)],
      memoryPercent = if (count == 0) 0 else 70,
    )
    world.lock.appLocked.value = random.nextInt(100) < 30
    world.prefs.choice.value = WidgetChoice.entries[random.nextInt(3)]
    world.hideLate(random.nextInt(0, (count / 3).coerceAtLeast(1)))
    if (count > 4) world.addGroups(random.nextInt(0, 8))
    world.memories.pick?.let { pick ->
      val kind = MemoryKind.entries[random.nextInt(MemoryKind.entries.size)]
      // A volte pochi membri (sotto 8, E-W19), a volte tutti; la copertina a volte nascosta o senza miniatura.
      val members = if (random.nextInt(4) == 0) pick.members.take(random.nextInt(1, 8)) else pick.members
      world.memories.pick = pick.copy(kind = kind, members = members, cover = members[random.nextInt(members.size)])
      if (random.nextInt(6) == 0) world.visibility.hidden += world.memories.pick!!.cover
      if (random.nextInt(6) == 0) world.thumbs.local -= world.memories.pick!!.cover
    }
    return world
  }
}
