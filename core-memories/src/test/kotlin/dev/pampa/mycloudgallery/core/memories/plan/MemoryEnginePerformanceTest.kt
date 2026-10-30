package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import java.time.LocalDate
import java.util.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Le soglie di P6 sulla JVM (docs/fase9-progetto.md §2.1 "Costi", §4.4 riga P6, §5.4): **meno di 1 s su 100 mila celle** per un giro di generazione
 * (dal "freddo": tabella, indice, generatore, scelta delle foto di riferimento, scaffale), tabella e indice per giorno e generatore ciascuno
 * lontani dalla soglia. La Libreria e' sintetica (cinque anni, un po' piu' di cinquanta scatti al giorno, anniversari, screenshot, documenti,
 * preferiti), con un ricordo di ogni tipo possibile oggi; i tempi misurati stanno in `docs/decisioni.md` ("Fase 9 P6").
 */
class MemoryEnginePerformanceTest {

  /** 100 mila scatti su cinque anni (circa 1.826 giorni), dal 3 novembre 2021 al 2 novembre 2026, con il 5% di screenshot e il 3% di documenti. */
  private fun library(count: Int = 100_000): List<TestShot> {
    val random = Random(20261030)
    val first = LocalDate.of(2021, 11, 3)
    val days = 365 * 5
    return List(count) { n ->
      val day = first.plusDays((n % days).toLong())
      val roll = random.nextInt(100)
      var flags = if (random.nextInt(10) == 0) CellFlags.FAVORITE else 0
      if (roll < 3) flags = flags or CellFlags.DOCUMENT
      TestShot(
        id = testId(1L + n / 4096, (n % 4096).toLong()),
        localDay = dayInt(day),
        sortTime = instantOf(day, java.time.LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60))),
        source = if (roll in 3..7) MediaSource.SCREENSHOT else MediaSource.CAMERA,
        kind = if (random.nextInt(50) == 0) MediaKind.VIDEO else MediaKind.PHOTO,
        flags = flags,
        place = 3_000_000 + random.nextInt(50),
      )
    }
  }

  private fun millis(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000.0
  }

  @Test
  fun ilGiroSu100milaCelleStaSottoUnSecondo() {
    val shots = library()
    val visibility = FakeVisibility(snapshot = snapshotOf(shots), present = shots.map { it.id }.toSet())
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      val clock = FakeMemoryClock(LocalDate.of(2026, 11, 3))
      val engine = MemoryEngine(
        library = visibilityOf(visibility, visibility), personal = InMemoryMemoriesPersonalSettings(), store = InMemoryShelfStore(), clock = clock,
        picker = ReferencePicker(), scope = scope, dispatcher = Dispatchers.Unconfined,
      )
      var published = 0
      val cold = millis { published = runBlocking { engine.refresh(RefreshReason.NIGHTLY, 60.seconds) }.published }
      // Il giorno dopo, la stessa istantanea: la tabella e' gia' pronta (si rifa' solo se l'istantanea cambia).
      clock.today = LocalDate.of(2026, 11, 4)
      val warm = millis { runBlocking { engine.refresh(RefreshReason.NIGHTLY, 60.seconds) } }
      println("P6 misure: giro a freddo su 100.000 celle = %.0f ms (%d ricordi), giro a caldo = %.0f ms".format(cold, published, warm))
      assertWithMessage("un ricordo almeno e' nato").that(published).isAtLeast(1)
      assertWithMessage("giro a freddo su 100.000 celle: %.0f ms".format(cold)).that(cold).isLessThan(1_000.0)
      assertWithMessage("giro a caldo su 100.000 celle: %.0f ms".format(warm)).that(warm).isLessThan(1_000.0)
    } finally {
      scope.cancel()
    }
  }

  /** Il pezzo di P6: tabella a colonne con l'indice per giorno e generatore, senza la scelta delle foto (che e' di P7): ognuno ben sotto la soglia. */
  @Test
  fun tabellaEGeneratoreSu100milaCelle() {
    val snapshot = snapshotOf(library())
    // Un primo giro per scaldare la JVM, non misurato.
    CellTable.of(snapshot).also { it.onDay(20261103) }
    val planner = IndexedPlanner()
    var table: CellTable? = null
    val build = millis { table = CellTable.of(snapshot).also { it.daysWithCells(); it.onDay(20261103) } }
    var candidates = 0
    val plan = millis {
      candidates = planner.plan(PlanInput(table!!, emptyList(), emptyList(), false, MemoriesChoices.NONE, PlanHistory()), LocalDate.of(2026, 11, 3)).size
    }
    println("P6 misure: tabella e indice per giorno su 100.000 celle = %.0f ms, generatore (%d candidati) = %.1f ms".format(build, candidates, plan))
    assertThat(candidates).isAtLeast(2)
    assertWithMessage("tabella e indice: %.0f ms".format(build)).that(build).isLessThan(500.0)
    assertWithMessage("generatore: %.1f ms".format(plan)).that(plan).isLessThan(200.0)
  }

  /** La pulizia di uno scaffale pieno (60 ricordi da 40 elementi) sta in pochi millisecondi: nessuna scansione della Libreria. */
  @Test
  fun lePulizieSonoLeggere() {
    val shots = library()
    val visibility = FakeVisibility(snapshot = snapshotOf(shots), present = shots.map { it.id }.toSet())
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      val engine = MemoryEngine(
        library = visibilityOf(visibility, visibility), personal = InMemoryMemoriesPersonalSettings(), store = InMemoryShelfStore(),
        clock = FakeMemoryClock(LocalDate.of(2026, 11, 3)), picker = ReferencePicker(), scope = scope, dispatcher = Dispatchers.Unconfined,
      )
      runBlocking { engine.refresh(RefreshReason.NIGHTLY, 60.seconds) }
      val prune = millis { repeat(20) { runBlocking { engine.prune() } } } / 20
      println("P6 misure: pulizia dello scaffale = %.1f ms".format(prune))
      assertWithMessage("pulizia: %.1f ms".format(prune)).that(prune).isLessThan(100.0)
    } finally {
      scope.cancel()
    }
  }
}
