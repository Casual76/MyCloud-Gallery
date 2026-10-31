package dev.pampa.mycloudgallery.core.widget.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.SyntheticWidgetWorld
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Le scelte di `DefaultWidgetPlanner` che la suite di contratto non vede: i giri sul `WidgetFeed`, l'epoca, il blocco che si accende, gli errori. */
class DefaultWidgetPlannerTest {

  private val slot = WidgetSlot(9)

  /** Un feed che registra quante celle gli si chiedono a ogni giro. */
  private class CountingFeed(private val inner: WidgetFeed) : WidgetFeed {
    val asked = ArrayList<Int>()

    override suspend fun next(size: Int): List<MediaId> {
      asked += size
      return inner.next(size)
    }
  }

  @Test
  fun ilTriploDellaMisuraGrandeSiChiedeAGiriSottoIlTettoDelFeed() = runTest {
    // Misura grande: 7 celle, il triplo e' 21 > WidgetFeed.MAX_SIZE (16).
    val world = SyntheticWidgetWorld(21, count = 400, thumbPercent = 15, phonePercent = 100, memoryPercent = 0)
    val feed = CountingFeed(RotatingWidgetFeed(world.visibility, world.visibility))
    val planner = DefaultWidgetPlanner(feed, world.memories, world.visibility, world.visibility, world.thumbs, world.lock, world.prefs, FakeWidgetClock(), InMemoryWidgetLedger())
    val plan = planner.plan(slot, WidgetSize.LARGE)
    assertThat(plan.cells).hasSize(WidgetSize.LARGE.cells)
    assertWithMessage("mai piu' del tetto per chiamata").that(feed.asked.all { it in 1..WidgetFeed.MAX_SIZE }).isTrue()
    assertWithMessage("il primo giro chiede il massimo che il feed da'").that(feed.asked.first()).isEqualTo(WidgetFeed.MAX_SIZE)
    assertWithMessage("con poche miniature servono piu' giri").that(feed.asked.size).isGreaterThan(1)
    assertThat(plan.shown - world.thumbs.local).isEmpty()
  }

  @Test
  fun unaMisuraPiccolaNonSprecaIlFeed() = runTest {
    val world = SyntheticWidgetWorld(22, count = 200, thumbPercent = 100, memoryPercent = 0)
    val feed = CountingFeed(RotatingWidgetFeed(world.visibility, world.visibility))
    val planner = DefaultWidgetPlanner(feed, world.memories, world.visibility, world.visibility, world.thumbs, world.lock, world.prefs, FakeWidgetClock(), InMemoryWidgetLedger())
    assertThat(planner.plan(slot, WidgetSize.SMALL).cells).hasSize(1)
    // Il triplo di una cella: 3, e niente di piu' se bastano.
    assertThat(feed.asked).containsExactly(WidgetRules.OVERFETCH)
  }

  @Test
  fun unGiroNonSiFermaAlPrimoVuotoMaCercaFinoAlGiroIntero() = runTest {
    // Una sola foto con la miniatura in 300: il feed la trova solo dopo molti giri, e il piano la mostra.
    val world = SyntheticWidgetWorld(23, count = 300, thumbPercent = 0, memoryPercent = 0)
    val candidates = world.ids.filter { it !in world.excluded }
    world.thumbs.local += candidates.last()
    val planner = defaultPlanner(world)
    val plan = planner.plan(slot, WidgetSize.WIDE)
    assertThat(plan.shown).containsExactly(candidates.last())
    assertThat(plan.mode).isEqualTo(WidgetMode.Photos)
  }

  @Test
  fun ilCostoDiUnPianoHaUnTettoSuUnaLibreriaGrande() = runTest {
    // Nessun media ha la miniatura e la Libreria e' grande: dopo MAX_ROUNDS giri si smette (non si scorre tutto) e si dice perche'.
    val world = SyntheticWidgetWorld(24, count = 5_000, thumbPercent = 0, memoryPercent = 0)
    val feed = CountingFeed(RotatingWidgetFeed(world.visibility, world.visibility))
    val planner = DefaultWidgetPlanner(feed, world.memories, world.visibility, world.visibility, world.thumbs, world.lock, world.prefs, FakeWidgetClock(), InMemoryWidgetLedger())
    assertThat(planner.plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_LOCAL_THUMBS))
    assertThat(feed.asked.size).isAtMost(DefaultWidgetPlanner.MAX_ROUNDS)
    assertThat(feed.asked.sum()).isLessThan(1_200)
  }

  @Test
  fun l_epocaRipartePerSempreDalRegistroDopoLaMorteDelProcesso() = runTest {
    val world = SyntheticWidgetWorld(25, thumbPercent = 100, memoryPercent = 0)
    val ledger = InMemoryWidgetLedger()
    val first = defaultPlanner(world, ledger = ledger)
    var last = first.plan(slot, WidgetSize.WIDE)
    repeat(4) {
      ledger.record(last)
      last = first.plan(slot, WidgetSize.WIDE)
    }
    ledger.record(last)
    assertThat(last.epoch).isEqualTo(5)
    // Il processo muore: un pianificatore nuovo ha il contatore a zero, ma il registro ha l'epoca 5. Il piano nuovo deve essere accettato.
    val reborn = defaultPlanner(world, ledger = ledger)
    val next = reborn.plan(slot, WidgetSize.WIDE)
    assertThat(next.epoch).isEqualTo(6)
    ledger.record(next)
    assertThat(ledger.cell(slot, 0)?.media).isEqualTo(next.cells.first().media)
    // Un registro che non risponde non ferma il piano.
    val broken = object : WidgetLedger by ledger {
      override suspend fun epochOf(slot: WidgetSlot): Long = throw IOException("disco")
    }
    assertThat(defaultPlanner(world, ledger = InMemoryWidgetLedger()).plan(slot, WidgetSize.SMALL).epoch).isEqualTo(1)
    val epochOnError = DefaultWidgetPlanner(
      RotatingWidgetFeed(world.visibility, world.visibility), world.memories, world.visibility, world.visibility, world.thumbs, world.lock,
      world.prefs, FakeWidgetClock(), broken,
    ).plan(slot, WidgetSize.SMALL).epoch
    assertThat(epochOnError).isEqualTo(1)
  }

  @Test
  fun seIlBloccoSiAccendeDuranteIlPianoNonEsceUnTitoloNeMediaSoloSulNas() = runTest {
    val world = SyntheticWidgetWorld(26, count = 200, thumbPercent = 100, phonePercent = 30, memoryPercent = 100)
    val members = world.ids.filter { it !in world.excluded }.take(14)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.ON_THIS_DAY, members = members, cover = members[0])
    // Il blocco si accende mentre il pianificatore sta guardando le miniature (dopo aver letto "blocco spento").
    val lock = world.lock
    val flipping = object : WidgetThumbs {
      override suspend fun local(ids: Collection<MediaId>): Set<MediaId> {
        lock.appLocked.value = true
        return world.thumbs.local(ids)
      }
    }
    val planner = DefaultWidgetPlanner(
      RotatingWidgetFeed(world.visibility, world.visibility), world.memories, world.visibility, world.visibility, flipping, lock,
      world.prefs, FakeWidgetClock(), InMemoryWidgetLedger(),
    )
    val plan = planner.plan(slot, WidgetSize.LARGE)
    assertThat(plan.mode).isEqualTo(WidgetMode.Locked)
    assertThat(plan.shown - lock.onPhone).isEmpty()
  }

  @Test
  fun unErroreDelFeedNonDiceLibreriaVuota() = runTest {
    // Il feed vero fallisce chiuso e risponde "niente" a un errore: il piano rilegge un id con le letture che lanciano e dice ERROR, non NO_MEDIA.
    val world = SyntheticWidgetWorld(27, thumbPercent = 100, memoryPercent = 0)
    val silentFeed = object : WidgetFeed {
      override suspend fun next(size: Int): List<MediaId> = emptyList()
    }
    val broken = object : VisibleMedia by world.visibility {
      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = throw IOException("database")
    }
    val planner = DefaultWidgetPlanner(silentFeed, world.memories, broken, world.visibility, world.thumbs, world.lock, world.prefs, FakeWidgetClock(), InMemoryWidgetLedger())
    assertThat(planner.plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.ERROR))
    // Se invece la lettura risponde, la Libreria e' davvero vuota di cose da mostrare.
    val ok = DefaultWidgetPlanner(silentFeed, world.memories, world.visibility, world.visibility, world.thumbs, world.lock, world.prefs, FakeWidgetClock(), InMemoryWidgetLedger())
    assertThat(ok.plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_MEDIA))
  }

  @Test
  fun ilBudgetValeAncheConUnaLetturaCheBlocca() = runTest {
    // Una lettura che non si lascia interrompere (nessun punto di sospensione) ma consuma tempo monotono: il tetto vale lo stesso.
    val world = SyntheticWidgetWorld(28, thumbPercent = 100, memoryPercent = 0)
    val clock = FakeWidgetClock()
    val slow = object : WidgetThumbs {
      override suspend fun local(ids: Collection<MediaId>): Set<MediaId> {
        clock.monotonic += WidgetRules.BUDGET_MS + 1
        return world.thumbs.local(ids)
      }
    }
    val planner = DefaultWidgetPlanner(
      RotatingWidgetFeed(world.visibility, world.visibility), world.memories, world.visibility, world.visibility, slow, world.lock, world.prefs, clock,
      InMemoryWidgetLedger(),
    )
    assertThat(planner.plan(slot, WidgetSize.WIDE).mode).isEqualTo(WidgetMode.Message(WidgetEmpty.ERROR))
    assertThat(planner.plan(slot, WidgetSize.WIDE, budgetMs = 10_000).mode).isEqualTo(WidgetMode.Photos)
  }

  @Test
  fun ilTitoloDelRicordoPassaComeDatiMaUnPersonNonPassaMai() = runTest {
    val world = SyntheticWidgetWorld(29, count = 100, thumbPercent = 100, memoryPercent = 100)
    val members = world.ids.filter { it !in world.excluded }.take(12)
    val title = WidgetTitle(yearsAgo = 2, placeIds = listOf(3_143_244), tripLabel = "Oslo, marzo 2025", month = null)
    val pick = world.memories.pick!!.copy(kind = MemoryKind.TRIP, title = title, members = members, cover = members[2])
    world.memories.pick = pick
    val plan = defaultPlanner(world).plan(slot, WidgetSize.WIDE)
    assertThat(plan.mode).isEqualTo(WidgetMode.Memory(pick.id, title))
    assertThat(plan.cells.first().media).isEqualTo(members[2])
    assertThat(plan.cells.map { it.index }).containsExactly(0, 1, 2, 3).inOrder()
    // Un ricordo di una persona, con un'etichetta qualunque: niente ricordo, niente etichetta nel piano.
    world.memories.pick = pick.copy(kind = MemoryKind.PERSON, title = WidgetTitle(null, emptyList(), "Etichetta di prova", null))
    val other = defaultPlanner(world).plan(slot, WidgetSize.WIDE)
    assertThat(other.mode).isEqualTo(WidgetMode.Photos)
    assertThat(other.toString()).doesNotContain("Etichetta")
  }

  @Test
  fun ilRicordoEUnaCellaPerElementoVisibileEMaiSottoOtto() = runTest {
    val world = SyntheticWidgetWorld(30, count = 100, thumbPercent = 100, memoryPercent = 100)
    val members = world.ids.filter { it !in world.excluded }.take(10)
    world.memories.pick = world.memories.pick!!.copy(kind = MemoryKind.MONTH, members = members, cover = members[0])
    val planner = defaultPlanner(world)
    assertThat(planner.plan(slot, WidgetSize.LARGE).cells).hasSize(7)
    // Due nascosti: restano 8 visibili, e basta ancora (E-W19: da 8 in su).
    world.visibility.hidden += members.takeLast(2)
    assertThat(planner.plan(slot, WidgetSize.LARGE).mode).isInstanceOf(WidgetMode.Memory::class.java)
    world.visibility.hidden += members[5]
    assertThat(planner.plan(slot, WidgetSize.LARGE).mode).isEqualTo(WidgetMode.Photos)
  }

  @Test
  fun leFotoSonoDellaMisuraEMaiOltreSette() = runTest {
    val world = SyntheticWidgetWorld(31, count = 300, thumbPercent = 100, memoryPercent = 0)
    val planner = defaultPlanner(world)
    for (size in WidgetSize.entries) {
      val plan: WidgetPlan = planner.plan(slot, size)
      assertThat(plan.cells).hasSize(size.cells)
      assertThat(plan.cells.size).isAtMost(WidgetRules.MAX_IMAGES)
    }
    world.prefs.choice.value = WidgetChoice.PHOTOS_ONLY
    assertThat(planner.plan(slot, WidgetSize.LARGE).cells).hasSize(7)
  }

  @Test
  fun unPianoDiUnaSolaFotoSiRipeteSenzaDuplicati() = runTest {
    // Tre sole foto mostrabili e una misura da sette celle: tre celle, senza ripetere la stessa foto per riempire.
    val world = SyntheticWidgetWorld(32, count = 60, thumbPercent = 0, memoryPercent = 0)
    val three = world.ids.filter { it !in world.excluded }.take(3)
    world.thumbs.local += three
    val plan = defaultPlanner(world).plan(slot, WidgetSize.LARGE)
    assertThat(plan.shown).containsExactlyElementsIn(three)
    assertThat(plan.cells).hasSize(3)
  }
}
