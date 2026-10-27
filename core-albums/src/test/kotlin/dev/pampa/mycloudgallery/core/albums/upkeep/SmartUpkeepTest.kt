package dev.pampa.mycloudgallery.core.albums.upkeep

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.FlakyWorld
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySeenMarks
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySmartRows
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.ScriptedContent
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.smart.Spots
import dev.pampa.mycloudgallery.core.albums.smart.albumId
import dev.pampa.mycloudgallery.core.albums.smart.cand
import dev.pampa.mycloudgallery.core.albums.smart.day
import dev.pampa.mycloudgallery.core.albums.smart.id
import dev.pampa.mycloudgallery.core.albums.smart.ranges
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.AlbumId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tenere al passo gli smart (AS-1, AS-2, AS-4, AS-5, AS-6; R17): dopo ogni calcolo chi mostra viene avvisato **dopo** che le righe sono scritte (prima
 * il motore scriveva su Room e nessuno rinfrescava l'elenco), uno smart senza righe annuncia che sta cercando, un calcolo fermo dal budget o da un guasto
 * si riprende, gli smart presenti al montaggio si valutano, la ricerca per contenuto che diventa pronta rifa' gli smart con una descrizione, e quelli
 * senza periodo non rilanciano la ricerca su tutta la Libreria a ogni gruppo di novita' di una scansione.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartUpkeepTest {
  private val d1 = day(2026, 3, 10)

  /** Cio' che `CatalogAlbums` vede: quando il motore parte, quando ha finito (con quante righe c'erano gia') e le riletture. */
  private class Sink(private val rows: InMemorySmartRows) : SmartWorkSink {
    val log = ArrayList<String>()
    val awaiting = LinkedHashSet<AlbumId>()
    val autoWhenFinished = HashMap<AlbumId, Int>()

    override fun smartWorkStarted(albums: Collection<AlbumId>) {
      log += "started"
      awaiting += albums
    }

    override suspend fun smartWorkFinished(albums: Collection<AlbumId>) {
      log += "finished"
      awaiting -= albums.toSet()
      for (album in albums) autoWhenFinished[album] = rows.albums[album]?.auto?.size ?: 0
    }

    override suspend fun smartRowsChanged() {
      log += "rows"
    }
  }

  private class Rig(scope: CoroutineScope, budget: Duration = Duration.INFINITE, contentQuiet: Duration = SmartUpkeep.CONTENT_QUIET) {
    val world = MemoryWorld()
    val flaky = FlakyWorld(world)
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val content = ScriptedContent(ready = false)
    val sink = Sink(rows)
    val errors = ArrayList<Exception>()
    val engine = RulesSmartEngine(rows, world, flaky, flaky, content, DeviceNormalizer(null), Spots.LOOKUP, clocks, InMemorySeenMarks())
    val recompute = SmartRecompute(rows, world, engine, clocks)
    val upkeep = SmartUpkeep(scope, rows, recompute, sink, onError = { errors += it }, budget = budget, contentQuiet = contentQuiet)
  }

  private fun period() = ranges(d1 to d1.plusDays(20))

  private fun Rig.fill(first: Int, count: Int) {
    for (n in 0 until count) world.put(cand(first + n, d1.plusDays((n % 15).toLong()), 600 + n % 300))
  }

  // --- AS-1 e AS-2 -------------------------------------------------------------------------------------------------------------------

  @Test
  fun dopoIlCalcoloChiMostraVieneAvvisatoQuandoLeRigheCiSono() = runTest {
    val rig = Rig(backgroundScope)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(period(), null, null))
    rig.fill(100, 30)

    rig.upkeep.rulesChanged(album)

    assertThat(rig.rows.albums.getValue(album).auto).hasSize(30)
    // «Finito» arriva dopo le righe: chi si rilegge le trova (prima nessuno lo diceva e l'elenco restava com'era).
    assertThat(rig.sink.log).containsExactly("started", "finished").inOrder()
    assertThat(rig.sink.autoWhenFinished.getValue(album)).isEqualTo(30)
    assertThat(rig.sink.awaiting).isEmpty()
  }

  @Test
  fun unoSmartSenzaRigheAnnunciaCheSiStaCercandoUnoConRigheNo() = runTest {
    val rig = Rig(backgroundScope)
    val fresh = albumId(1)
    rig.rows.create(fresh, SmartRules(period(), null, null))
    rig.fill(100, 10)

    rig.upkeep.rulesChanged(fresh)
    assertThat(rig.sink.log).containsExactly("started", "finished").inOrder()

    // Ha gia' le sue righe: un altro calcolo non lo ridice "in cerca" (la pagina non lampeggia), ma l'elenco si rilegge.
    rig.sink.log.clear()
    rig.upkeep.rulesChanged(fresh)
    assertThat(rig.sink.log).containsExactly("finished")
  }

  @Test
  fun lAnnuncioDiUnoSmartNuovoVieneQuandoIlMotoreNonHaAncoraScrittoNiente() = runTest {
    val rig = Rig(backgroundScope)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(period(), null, null))
    rig.fill(100, 10)
    var autoWhenAnnounced = -1
    val spying = object : SmartWorkSink by rig.sink {
      override fun smartWorkStarted(albums: Collection<AlbumId>) {
        autoWhenAnnounced = rig.rows.albums.getValue(album).auto.size
        rig.sink.smartWorkStarted(albums)
      }
    }
    val upkeep = SmartUpkeep(backgroundScope, rig.rows, rig.recompute, spying)

    upkeep.rulesChanged(album)

    assertThat(autoWhenAnnounced).isEqualTo(0)
    assertThat(rig.rows.albums.getValue(album).auto).hasSize(10)
  }

  // --- AS-5 -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unCalcoloFermoDalBudgetRestaInCalcoloESiRiprendeFinoAlla() = runTest {
    val rig = Rig(backgroundScope, budget = Duration.ZERO)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(period(), null, null))
    rig.fill(1, 5_000)

    rig.upkeep.rulesChanged(album)
    // A meta': niente scritto, ancora «in calcolo» (la pagina non dice «nessuna foto»), e c'e' da riprendere.
    assertThat(rig.rows.albums.getValue(album).auto).isEmpty()
    assertThat(rig.sink.awaiting).containsExactly(album)
    assertThat(rig.upkeep.hasUnfinished).isTrue()

    rig.upkeep.drain(gap = 1.milliseconds, maxPasses = 10)

    assertThat(rig.rows.albums.getValue(album).auto).hasSize(5_000)
    assertThat(rig.upkeep.hasUnfinished).isFalse()
    assertThat(rig.sink.awaiting).isEmpty()
  }

  @Test
  fun alMontaggioGliSmartCheCeranoGiaSiValutano() = runTest {
    val rig = Rig(backgroundScope)
    val a = albumId(1)
    val b = albumId(2)
    rig.rows.create(a, SmartRules(period(), null, null))
    rig.rows.create(b, SmartRules(ranges(d1 to d1.plusDays(2)), null, null))
    rig.fill(100, 30)

    rig.upkeep.resumeAll()

    assertThat(rig.rows.albums.getValue(a).auto).hasSize(30)
    assertThat(rig.rows.albums.getValue(b).auto).isNotEmpty()
  }

  @Test
  fun unGuastoDiLetturaNonSvuotaNeLasciaAppesoEIlGiroDopoRiprova() = runTest {
    val rig = Rig(backgroundScope)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(period(), null, null))
    rig.fill(100, 12)
    rig.upkeep.rulesChanged(album)
    val written = rig.rows.snapshot(album)
    assertThat(written.first).hasSize(12)

    rig.flaky.failing = true
    rig.upkeep.rulesChanged(album)
    // R17: le righe di prima sono intatte, l'errore e' contato, lo smart non resta «in cerca» e si riprova.
    assertThat(rig.rows.snapshot(album)).isEqualTo(written)
    assertThat(rig.errors).hasSize(1)
    assertThat(rig.sink.awaiting).isEmpty()
    assertThat(rig.upkeep.hasUnfinished).isTrue()

    rig.flaky.failing = false
    rig.upkeep.drain(gap = 1.milliseconds, maxPasses = 3)
    assertThat(rig.rows.snapshot(album)).isEqualTo(written)
    assertThat(rig.upkeep.hasUnfinished).isFalse()
  }

  // --- AS-4 -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun quandoLaRicercaDiventaProntaGliSmartConUnaDescrizioneSiRifanno() = runTest {
    val rig = Rig(backgroundScope)
    val described = albumId(1)
    val plain = albumId(2)
    rig.rows.create(described, SmartRules(period(), null, "mare"))
    rig.rows.create(plain, SmartRules(period(), null, null))
    rig.fill(100, 20)
    rig.content.sure = (100 until 110).map { id(it) }.toSet()

    // Con la ricerca non pronta la descrizione tace (D15): lo smart con una descrizione resta com'e'.
    rig.upkeep.resumeAll()
    assertThat(rig.rows.albums.getValue(described).auto).isEmpty()
    assertThat(rig.rows.albums.getValue(plain).auto).hasSize(20)
    val applies = rig.rows.applies

    rig.content.ready = true
    rig.upkeep.searchReady()

    assertThat(rig.rows.albums.getValue(described).auto.keys).containsExactlyElementsIn(rig.content.sure)
    // Solo lo smart con una descrizione: l'altro non e' stato toccato.
    assertThat(rig.rows.applies).isEqualTo(applies + 1)
  }

  // --- AS-6 -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun glismartSenzaPeriodoConUnaDescrizioneNonRifannoLaRicercaAOgniGruppoDiNovita() = runTest {
    val rig = Rig(backgroundScope, contentQuiet = 2.seconds)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(null, null, "mare"))
    rig.fill(100, 20)
    rig.content.ready = true
    rig.content.sure = (100 until 105).map { id(it) }.toSet()

    repeat(4) { n ->
      rig.world.put(cand(500 + n, d1, 700 + n))
      rig.upkeep.catalogChanged(listOf(id(500 + n)))
    }
    // Quattro gruppi di una scansione lunga: la ricerca per contenuto non e' partita nemmeno una volta.
    assertThat(rig.content.calls).isEqualTo(0)
    assertThat(rig.rows.albums.getValue(album).auto).isEmpty()

    // A catalogo fermo, una volta sola.
    advanceTimeBy(2.seconds + 1.milliseconds)
    runCurrent()
    assertThat(rig.content.calls).isEqualTo(1)
    assertThat(rig.rows.albums.getValue(album).auto.keys).containsExactlyElementsIn(rig.content.sure)
  }

  @Test
  fun unGruppoDiNovitaConPeriodoRicalcolaSubitoSoloIGiorniToccati() = runTest {
    val rig = Rig(backgroundScope)
    val album = albumId(1)
    rig.rows.create(album, SmartRules(period(), null, null))
    rig.fill(100, 10)
    rig.upkeep.rulesChanged(album)
    rig.world.put(cand(900, d1.plusDays(3), 700))

    rig.upkeep.catalogChanged(listOf(id(900)))

    assertThat(rig.rows.albums.getValue(album).auto.keys).contains(id(900))
    // Le righe nuove sono dette a chi mostra: qui il segno e' «rows» dopo l'incrementale.
    assertThat(rig.sink.log.last()).isEqualTo("rows")
  }
}
