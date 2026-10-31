package dev.pampa.mycloudgallery.core.widget.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il "Fatto quando" di P2 sul **mondo casuale** (`docs/fase10-progetto.md` §4.4): Libreria sintetica da **100 mila** elementi, nascosti (fuori
 * dall'istantanea, dell'ultimo momento, compagni di gruppo), gruppi, ricordi `PERSON`, media senza miniatura, blocco acceso. Il piano non
 * contiene **mai** un nascosto, un ricordo `PERSON`, un nome o un media senza miniatura locale; con il blocco solo media del telefono; e un
 * piano costa meno di 50 ms sulla JVM.
 */
class DefaultWidgetPlannerBigWorldTest {

  private val world get() = shared

  /** I nascosti "dell'ultimo momento": il feed (letture che non lanciano) non li vede ancora, il filtro finale (`...OrThrow`) si'. */
  private val late = HashSet<MediaId>()

  private fun planner(ledger: InMemoryWidgetLedger = InMemoryWidgetLedger(), racing: Boolean = true): DefaultWidgetPlanner {
    val (visible, mask) = if (racing) world.racing { late } else world.visibility to world.visibility
    return DefaultWidgetPlanner(
      feed = RotatingWidgetFeed(world.visibility, world.visibility),
      memories = world.memories, visible = visible, mask = mask, thumbs = world.thumbs, lock = world.lock, prefs = world.prefs,
      clock = FakeWidgetClock(), ledger = ledger,
    )
  }

  @Test
  fun ilPianoNonContieneMaiNienteDiVietatoSuCentomilaElementi() = runBlocking {
    val random = Random(7)
    val planner = planner()
    val texts = HashSet<String>()
    var memoryPlans = 0
    var lockedPlans = 0
    var photoPlans = 0
    var messages = 0
    repeat(400) { n ->
      val locked = random.nextInt(100) < 35
      world.lock.appLocked.value = locked
      world.prefs.choice.value = WidgetChoice.entries[random.nextInt(3)]
      val kind = MemoryKind.entries[random.nextInt(MemoryKind.entries.size)]
      world.memories.pick = if (random.nextInt(5) == 0) null else world.memoryOf(random, kind, 3 + random.nextInt(20))
      val pick = world.memories.pick
      // Nascosti "dell'ultimo momento": fra la lettura del feed e il filtro finale (e a volte la copertina del ricordo).
      late.clear()
      repeat(random.nextInt(0, 40)) { late += world.ids[random.nextInt(world.ids.size)] }
      if (pick != null && random.nextInt(4) == 0) late += pick.cover
      val sizes = WidgetSize.entries
      val size = sizes[random.nextInt(sizes.size)]
      val plan = planner.plan(WidgetSlot(1 + n % 3), size)
      val hidden = world.hiddenNow(late)
      assertSafe("piano $n", plan, hidden, locked, pick?.let { it.id to it })
      when (plan.mode) {
        is WidgetMode.Memory -> memoryPlans++
        WidgetMode.Locked -> lockedPlans++
        WidgetMode.Photos -> photoPlans++
        is WidgetMode.Message -> messages++
      }
      texts += plan.toString()
    }
    assertThat(memoryPlans).isGreaterThan(30)
    assertThat(lockedPlans).isGreaterThan(30)
    assertThat(photoPlans).isGreaterThan(30)
    // Nessun piano mostra un id di media (il toString e' fatto di soli numeri e tipi).
    for (text in texts) assertThat(text).doesNotContainMatch("[0-9a-f]{8}-[0-9a-f]{4}-")
    world.lock.appLocked.value = false
  }

  @Test
  fun unRicordoPersonNonArrivaMaiSulWidget() = runBlocking {
    val random = Random(11)
    world.lock.appLocked.value = false
    world.prefs.choice.value = WidgetChoice.MEMORY_THEN_PHOTOS
    val planner = planner()
    repeat(60) {
      val pick = world.memoryOf(random, MemoryKind.PERSON, 15)
      world.memories.pick = pick
      for (size in WidgetSize.entries) {
        val plan = planner.plan(WidgetSlot(5), size)
        assertThat(plan.mode).isNotInstanceOf(WidgetMode.Memory::class.java)
        assertThat(plan.shown - world.thumbs.local).isEmpty()
      }
    }
    world.prefs.choice.value = WidgetChoice.MEMORIES_ONLY
    assertThat(planner.plan(WidgetSlot(5), WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_MEMORY_TODAY))
    world.prefs.choice.value = WidgetChoice.DEFAULT
    world.memories.pick = null
  }

  @Test
  fun conIlBloccoSoloMediaDelTelefonoESenzaTitolo() = runBlocking {
    val random = Random(13)
    world.lock.appLocked.value = true
    world.prefs.choice.value = WidgetChoice.DEFAULT
    val planner = planner()
    repeat(80) { n ->
      world.memories.pick = world.memoryOf(random, MemoryKind.ON_THIS_DAY, 12)
      val plan = planner.plan(WidgetSlot(2), WidgetSize.entries[n % 3])
      assertThat(plan.mode).isEqualTo(WidgetMode.Locked)
      assertThat(plan.cells).isNotEmpty()
      assertThat(plan.shown - world.onPhone).isEmpty()
      assertThat(plan.shown - world.thumbs.local).isEmpty()
    }
    world.lock.appLocked.value = false
    world.memories.pick = null
  }

  @Test
  fun unPianoCostaMenoDiCinquantaMillisecondiSuCentomilaElementi() = runBlocking {
    val random = Random(17)
    world.lock.appLocked.value = false
    world.prefs.choice.value = WidgetChoice.DEFAULT
    val planner = planner(racing = false)
    val micros = ArrayList<Long>()
    // Misto: foto, ricordo (a giri), blocco; le tre misure. Le prime 40 scaldano la JVM e non contano.
    repeat(40 + 600) { n ->
      val locked = n % 5 == 4
      world.lock.appLocked.value = locked
      world.memories.pick = if (n % 3 == 0) world.memoryOf(random, MemoryKind.TRIP, 24) else null
      val size = WidgetSize.entries[n % 3]
      val started = System.nanoTime()
      val plan = planner.plan(WidgetSlot(1 + n % 3), size)
      val spent = (System.nanoTime() - started) / 1_000
      assertThat(plan.mode).isNotInstanceOf(WidgetMode.Message::class.java)
      if (n >= 40) micros += spent
    }
    world.lock.appLocked.value = false
    world.memories.pick = null
    micros.sort()
    val p95 = micros[micros.size * 95 / 100]
    val median = micros[micros.size / 2]
    println("MISURA piano su 100.000 elementi: mediana ${median / 1000.0} ms, p95 ${p95 / 1000.0} ms, massimo ${micros.last() / 1000.0} ms")
    assertWithMessage("p95 di un piano (microsecondi)").that(p95).isLessThan(50_000)
  }

  private fun assertSafe(
    what: String,
    plan: WidgetPlan,
    hidden: Set<MediaId>,
    locked: Boolean,
    pick: Pair<MemoryId, WidgetMemoryPick>?,
  ) {
    val shown = plan.shown
    assertWithMessage("$what: un nascosto nel widget").that(shown intersect hidden).isEmpty()
    assertWithMessage("$what: screenshot, documenti o animate").that(shown intersect world.excluded).isEmpty()
    assertWithMessage("$what: un media senza miniatura locale").that(shown - world.thumbs.local).isEmpty()
    assertWithMessage("$what: troppe celle").that(plan.cells.size).isAtMost(minOf(plan.size.cells, WidgetRules.MAX_IMAGES))
    if (locked) {
      assertWithMessage("$what: con il blocco solo media del telefono").that(shown - world.onPhone).isEmpty()
      assertWithMessage("$what: con il blocco un ricordo").that(plan.mode is WidgetMode.Memory).isFalse()
    }
    val memory = plan.mode as? WidgetMode.Memory
    if (memory != null) {
      assertThat(pick).isNotNull()
      assertWithMessage("$what: un ricordo PERSON").that(pick!!.second.kind).isNotEqualTo(MemoryKind.PERSON)
      assertWithMessage("$what: il ricordo non e' quello di oggi").that(memory.id).isEqualTo(pick.first)
      assertWithMessage("$what: elementi fuori dal ricordo").that(shown - pick.second.members.toSet()).isEmpty()
      if (pick.second.cover in shown) assertWithMessage("$what: la copertina viene prima").that(plan.cells.first().media).isEqualTo(pick.second.cover)
    }
  }

  companion object {
    /** Costa un paio di secondi: una volta per classe, deterministico. */
    private val shared: BigWidgetWorld by lazy { BigWidgetWorld() }  }
}
