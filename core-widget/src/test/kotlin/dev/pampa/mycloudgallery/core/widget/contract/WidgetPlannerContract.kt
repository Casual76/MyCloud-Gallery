package dev.pampa.mycloudgallery.core.widget.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.testing.ReferenceWidgetPlanner
import dev.pampa.mycloudgallery.core.widget.testing.SyntheticWidgetWorld
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto del pianificatore del widget (Fase 10, P0; `docs/fase10-progetto.md` D3-D8, §2.1, §2.2, §5.2; Q1 (a), Q3 (c)). Gira sul
 * riferimento e, da P2, su `DefaultWidgetPlanner` (con `WidgetFeed` sopra la stessa visibilita'). Il mondo e' [SyntheticWidgetWorld].
 */
abstract class WidgetPlannerContract {

  /** Il pianificatore sopra le porte del mondo [world] (con [visible] e [mask] al posto di quelli del mondo, se dati). */
  protected abstract fun planner(world: SyntheticWidgetWorld, visible: VisibleMedia = world.visibility, mask: HiddenMask = world.visibility): WidgetPlanner

  private val slot = WidgetSlot(7)

  private fun assertSafe(world: SyntheticWidgetWorld, plan: WidgetPlan, what: String) {
    val shown = plan.shown
    assertWithMessage("$what: un nascosto nel widget").that(shown intersect world.hiddenNow).isEmpty()
    assertWithMessage("$what: screenshot, documenti o animate nel widget").that(shown intersect world.excluded).isEmpty()
    assertWithMessage("$what: un media senza miniatura locale").that(shown - world.thumbs.local).isEmpty()
    assertWithMessage("$what: troppe celle").that(plan.cells.size).isAtMost(plan.size.cells)
    if (world.lock.appLocked.value) {
      assertWithMessage("$what: con il blocco solo media del telefono").that(shown - world.lock.onPhone).isEmpty()
      assertWithMessage("$what: con il blocco nessun ricordo ne' titolo").that(plan.mode is WidgetMode.Memory).isFalse()
    }
    val memory = plan.mode as? WidgetMode.Memory
    if (memory != null) {
      val pick = world.memories.pick
      assertWithMessage("$what: un ricordo che non e' quello di oggi").that(memory.id).isEqualTo(pick?.id)
      assertWithMessage("$what: un ricordo PERSON").that(pick?.kind).isNotEqualTo(MemoryKind.PERSON)
      assertWithMessage("$what: elementi fuori dal ricordo").that(shown - pick!!.members.toSet()).isEmpty()
    }
  }

  @Test
  fun unNascostoNonCompareMaiNemmenoDellUltimoMomento() = runTest {
    for (seed in 1L..120L) {
      val world = SyntheticWidgetWorld(seed)
      world.lock.appLocked.value = seed % 3 == 0L
      world.prefs.choice.value = WidgetChoice.entries[(seed % 3).toInt()]
      val planner = planner(world)
      world.hideLate((seed % 7).toInt() * 3)
      for (size in WidgetSize.entries) assertSafe(world, planner.plan(slot, size), "seme $seed, ${size.code}")
      world.memories.pick?.let { pick -> world.visibility.hidden += pick.cover }
      assertSafe(world, planner.plan(slot, WidgetSize.LARGE), "seme $seed, copertina nascosta")
    }
  }

  @Test
  fun libreriaVuotaOSenzaMiniatureDiceIlPerche() = runTest {
    val empty = SyntheticWidgetWorld(1, count = 0, memoryPercent = 0)
    assertThat(planner(empty).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_MEDIA))
    val noThumbs = SyntheticWidgetWorld(2, thumbPercent = 0, memoryPercent = 0)
    assertThat(planner(noThumbs).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_LOCAL_THUMBS))
  }

  @Test
  fun conIlBloccoSoloMediaDelTelefonoENessunTitolo() = runTest {
    val world = SyntheticWidgetWorld(3, memoryPercent = 100, thumbPercent = 100)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.ON_THIS_DAY)
    world.lock.appLocked.value = true
    val plan = planner(world).plan(slot, WidgetSize.LARGE)
    assertThat(plan.mode).isEqualTo(WidgetMode.Locked)
    assertThat(plan.shown - world.lock.onPhone).isEmpty()
    world.lock.onPhone.clear()
    assertThat(planner(world).plan(slot, WidgetSize.LARGE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.LOCKED_NOTHING_ON_PHONE))
  }

  @Test
  fun ilRicordoDiOggiConLaCopertinaPrima() = runTest {
    val world = SyntheticWidgetWorld(4, memoryPercent = 100, thumbPercent = 100)
    val members = world.ids.filter { it !in world.excluded }.take(12)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.TRIP, members = members, cover = members[3])
    val plan = planner(world).plan(slot, WidgetSize.LARGE)
    assertThat(plan.mode).isInstanceOf(WidgetMode.Memory::class.java)
    assertThat(plan.cells.first().media).isEqualTo(members[3])
    assertThat(plan.cells).hasSize(WidgetSize.LARGE.cells)
    assertThat(planner(world).plan(slot, WidgetSize.SMALL).cells.map { it.media }).containsExactly(members[3])
  }

  @Test
  fun maiUnRicordoPersonNePocoVisibile() = runTest {
    val world = SyntheticWidgetWorld(5, memoryPercent = 100, thumbPercent = 100)
    val members = world.ids.filter { it !in world.excluded }.take(12)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.PERSON, members = members, cover = members[0])
    assertThat(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Photos)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.MONTH)
    world.visibility.hidden += members.drop(WidgetRules.MEMORY_MIN_VISIBLE - 1)
    assertWithMessage("sotto 8 visibili: foto a caso (E-W19)").that(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Photos)
  }

  @Test
  fun leScelteDiQ1() = runTest {
    val world = SyntheticWidgetWorld(6, memoryPercent = 0, thumbPercent = 100)
    world.prefs.choice.value = WidgetChoice.MEMORIES_ONLY
    assertThat(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_MEMORY_TODAY))
    val withMemory = SyntheticWidgetWorld(7, memoryPercent = 100, thumbPercent = 100)
    val members = withMemory.ids.filter { it !in withMemory.excluded }.take(10)
    withMemory.memories.pick = withMemory.memories.pick!!.copy(kind = MemoryKind.ON_THIS_DAY, members = members, cover = members[0])
    withMemory.prefs.choice.value = WidgetChoice.PHOTOS_ONLY
    assertThat(planner(withMemory).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Photos)
    withMemory.prefs.choice.value = WidgetChoice.DEFAULT
    assertThat(planner(withMemory).plan(slot, WidgetSize.WIDE).mode).isInstanceOf(WidgetMode.Memory::class.java)
  }

  @Test
  fun unErroreDiLetturaNonDaMaiFoto() = runTest {
    val world = SyntheticWidgetWorld(8, thumbPercent = 100)
    val broken = object : VisibleMedia by world.visibility {
      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = throw IOException("database")
    }
    assertThat(planner(world, visible = broken).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.ERROR))
    world.thumbs.failing = true
    assertThat(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.ERROR))
  }

  @Test
  fun budgetSuperatoEIstantaneaAssente() = runTest {
    val world = SyntheticWidgetWorld(9, thumbPercent = 100, memoryPercent = 0)
    world.thumbs.delayMs = WidgetRules.BUDGET_MS + 1
    assertThat(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.ERROR))
    world.thumbs.delayMs = 0
    world.visibility.snapshot.value = null
    assertWithMessage("E-W4: istantanea non pronta").that(planner(world).plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NOT_READY))
  }

  @Test
  fun dueWidgetMostranoFotoDiverseEDellEpocaCrescente() = runTest {
    val world = SyntheticWidgetWorld(10, count = 200, thumbPercent = 100, memoryPercent = 0)
    val planner = planner(world)
    val first = planner.plan(WidgetSlot(1), WidgetSize.LARGE)
    val second = planner.plan(WidgetSlot(2), WidgetSize.LARGE)
    assertWithMessage("E-W11").that(first.shown intersect second.shown).isEmpty()
    val again = planner.plan(WidgetSlot(1), WidgetSize.LARGE)
    assertThat(again.epoch).isGreaterThan(first.epoch)
  }
}

class ReferenceWidgetPlannerContractTest : WidgetPlannerContract() {
  override fun planner(world: SyntheticWidgetWorld, visible: VisibleMedia, mask: HiddenMask): WidgetPlanner =
    ReferenceWidgetPlanner(visible, mask, world.memories, world.thumbs, world.lock, world.prefs)
}
