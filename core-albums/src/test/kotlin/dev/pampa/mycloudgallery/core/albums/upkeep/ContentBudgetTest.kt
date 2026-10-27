package dev.pampa.mycloudgallery.core.albums.upkeep

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.AutoRow
import dev.pampa.mycloudgallery.core.albums.smart.ContentMatches
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySeenMarks
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySmartRows
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.SmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.smart.Spots
import dev.pampa.mycloudgallery.core.albums.smart.albumId
import dev.pampa.mycloudgallery.core.albums.smart.cand
import dev.pampa.mycloudgallery.core.albums.smart.day
import dev.pampa.mycloudgallery.core.albums.smart.id
import dev.pampa.mycloudgallery.core.albums.smart.ranges
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il budget delle ricerche per contenuto degli album smart (correzioni F, risposta dell'utente del 27/10 su R19 e L146). Nessun tetto alla creazione e nessun
 * messaggio; ma ogni smart con una descrizione o delle foto d'esempio costa una ricerca sull'intera Libreria a ogni rivalutazione, e un dispositivo guasto dello
 * stesso account ne potrebbe far arrivare centinaia. Provato: con 300 smart un giro fa al piu' N ricerche e in K giri sono valutati tutti; con pochi nessuno
 * aspetta; la fila e' a rotazione; l'incrementale del catalogo passa dallo stesso budget; gli smart senza ricerca per contenuto non aspettano mai; uno smart in
 * fila dice «cerco» (non «nessuna foto»). Il tempo e' virtuale (`runTest`): nessuna attesa vera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentBudgetTest {
  private val d1 = day(2026, 3, 10)

  /** Una ricerca che si ricorda quale descrizione ha cercato (le descrizioni sono "mare 12": il numero dello smart). */
  private class RecordingContent(var sure: Set<MediaId> = emptySet()) : SmartContentSearch {
    override val ready: Boolean = true
    val searched = ArrayList<String>()

    override suspend fun match(text: String?, examples: List<MediaId>, period: SmartPeriod?): ContentMatches {
      searched += text.orEmpty()
      return ContentMatches(sure, emptySet(), used = true)
    }
  }

  private class Sink : SmartWorkSink {
    val awaiting = LinkedHashSet<AlbumId>()

    override fun smartWorkStarted(albums: Collection<AlbumId>) {
      awaiting += albums
    }

    override suspend fun smartWorkFinished(albums: Collection<AlbumId>) {
      awaiting -= albums.toSet()
    }

    override suspend fun smartRowsChanged() = Unit
  }

  private class Rig(scope: CoroutineScope, perRound: Int = ContentBudget.PER_ROUND) {
    val world = MemoryWorld()
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val content = RecordingContent()
    val sink = Sink()
    val engine = RulesSmartEngine(rows, world, world, world, content, DeviceNormalizer(null), Spots.LOOKUP, clocks, InMemorySeenMarks())
    val recompute = SmartRecompute(rows, world, engine, clocks)
    val upkeep = SmartUpkeep(scope, rows, recompute, sink, contentPerRound = perRound)
    private val d1 = day(2026, 3, 10)

    init {
      for (n in 0 until 20) world.put(cand(100 + n, d1.plusDays((n % 15).toLong()), 600 + n))
      content.sure = (100 until 105).map { id(it) }.toSet()
    }

    /** Lo smart numero [n], con la descrizione "mare n" (e il periodo se [period]). */
    fun smart(n: Int, period: SmartPeriod? = null, text: String? = "mare $n"): AlbumId = albumId(n).also { rows.create(it, SmartRules(period, null, text)) }

    fun evaluated(): Set<Int> = rows.albums.entries.filter { it.value.auto.isNotEmpty() }.map { number(it.key) }.toSet()

    private fun number(album: AlbumId): Int = rows.albums.keys.indexOf(album) + 1
  }

  /** Il giro dopo: la finestra del budget finisce e i primi della fila prendono i posti. */
  private suspend fun TestScope.nextRound() {
    advanceTimeBy(ContentBudget.ROUND + 1.milliseconds)
    runCurrent()
  }

  // --- Il caso dell'utente: tanti smart con descrizione ---------------------------------------------------------------------------

  @Test
  fun conTrecentoSmartConDescrizioneUnGiroFaAlPiuNRicercheEInKGiriSonoValutatiTutti() = runTest {
    val rig = Rig(backgroundScope)
    val total = 300
    for (n in 1..total) rig.smart(n)
    val perRound = ContentBudget.PER_ROUND
    val rounds = (total + perRound - 1) / perRound

    rig.upkeep.resumeAll()

    // Il primo giro: N ricerche, non 300. Gli altri aspettano e la loro pagina dice «cerco», non «nessuna foto».
    assertThat(rig.content.searched).hasSize(perRound)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(total - perRound)
    assertThat(rig.evaluated()).hasSize(perRound)
    assertThat(rig.sink.awaiting).hasSize(total - perRound)

    // Un giro dopo l'altro: mai piu' di N ricerche per giro, e in K giri tutti sono valutati.
    repeat(rounds - 1) {
      val before = rig.content.searched.size
      nextRound()
      assertThat(rig.content.searched.size - before).isAtMost(perRound)
      assertThat(rig.content.searched.size - before).isGreaterThan(0)
    }
    assertThat(rig.content.searched.toSet()).hasSize(total)
    assertThat(rig.content.searched).hasSize(total)
    assertThat(rig.evaluated()).hasSize(total)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(0)
    assertThat(rig.sink.awaiting).isEmpty()

    // E poi il giro si spegne da solo: non resta niente da fare.
    val settled = rig.content.searched.size
    nextRound()
    nextRound()
    assertThat(rig.content.searched).hasSize(settled)
  }

  @Test
  fun conPochiSmartNessunoAspettaNeOraNeDopoMoltiGiri() = runTest {
    val rig = Rig(backgroundScope)
    for (n in 1..3) rig.smart(n)

    repeat(40) {
      rig.upkeep.resumeAll()
      assertThat(rig.upkeep.waitingForBudget).isEqualTo(0)
      nextRound()
    }

    // Quaranta giri da tre ricerche l'uno: tutte fatte subito, mai una in fila.
    assertThat(rig.content.searched).hasSize(120)
    assertThat(rig.evaluated()).containsExactly(1, 2, 3)
  }

  @Test
  fun unaModificaDiUnSingoloSmartNonAspettaSeIlGiroHaPosto() = runTest {
    val rig = Rig(backgroundScope)
    val album = rig.smart(1)

    rig.upkeep.rulesChanged(album)

    assertThat(rig.content.searched).containsExactly("mare 1")
    assertThat(rig.rows.albums.getValue(album).auto.keys).containsExactlyElementsIn(rig.content.sure)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(0)
  }

  // --- La rotazione ------------------------------------------------------------------------------------------------------------------

  @Test
  fun laFilaEAdAnelloChiAspettaDaPiuTempoVaPrimaDiChiRiTorna() = runTest {
    val rig = Rig(backgroundScope, perRound = 3)
    for (n in 1..7) rig.smart(n)

    rig.upkeep.resumeAll()
    assertThat(rig.content.searched).containsExactly("mare 1", "mare 2", "mare 3").inOrder()

    // Un secondo «valuta tutti» nello stesso giro non scavalca la fila: gli smart gia' serviti si mettono in coda dietro a chi aspetta.
    rig.upkeep.resumeAll()
    assertThat(rig.content.searched).hasSize(3)

    nextRound()
    assertThat(rig.content.searched.drop(3)).containsExactly("mare 4", "mare 5", "mare 6").inOrder()
    assertThat(rig.evaluated()).containsExactly(1, 2, 3, 4, 5, 6)

    nextRound()
    // Il 7 non ha ancora mai cercato: tocca a lui, poi ricominciano quelli di prima.
    assertThat(rig.content.searched.drop(6).first()).isEqualTo("mare 7")
    assertThat(rig.evaluated()).containsExactly(1, 2, 3, 4, 5, 6, 7)
  }

  @Test
  fun gliSmartSenzaRicercaPerContenutoNonAspettanoMai() = runTest {
    val rig = Rig(backgroundScope, perRound = 2)
    val plain = (1..10).map { rig.smart(it, period = ranges(d1 to d1.plusDays(20)), text = null) }
    for (n in 11..20) rig.smart(n)

    rig.upkeep.resumeAll()

    // I dieci senza descrizione sono tutti valutati subito; dei dieci con la descrizione, due.
    assertThat(plain.all { rig.rows.albums.getValue(it).auto.isNotEmpty() }).isTrue()
    assertThat(rig.content.searched).hasSize(2)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(8)
  }

  // --- L'incrementale del catalogo ----------------------------------------------------------------------------------------------------

  @Test
  fun anchePerIlCatalogoGliSmartConPeriodoERicercaPerContenutoPassanoDalBudget() = runTest {
    val rig = Rig(backgroundScope, perRound = 3)
    for (n in 1..10) rig.smart(n, period = ranges(d1 to d1.plusDays(20)))
    rig.world.put(cand(900, d1.plusDays(3), 700))

    rig.upkeep.catalogChanged(listOf(id(900)))

    // Dieci smart con un periodo che copre il giorno toccato: tre ricerche subito, sette in fila (prima: dieci, tutte insieme).
    assertThat(rig.content.searched).hasSize(3)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(7)

    repeat(3) { nextRound() }
    assertThat(rig.content.searched.toSet()).hasSize(10)
    assertThat(rig.evaluated()).hasSize(10)
    assertThat(rig.upkeep.waitingForBudget).isEqualTo(0)
  }

  @Test
  fun unoSmartGiaValutatoDalCatalogoNonUsaUnSecondoPostoNellostessoGiro() = runTest {
    val rig = Rig(backgroundScope)
    val album = rig.smart(1, period = ranges(d1 to d1.plusDays(20)))
    rig.upkeep.rulesChanged(album)
    val before = rig.content.searched.size
    // Il media 900 e' fra le righe dell'album: la valutazione intera lo guarda, l'incrementale dopo no.
    rig.content.sure = rig.content.sure + id(900)
    rig.world.put(cand(900, d1.plusDays(3), 700))
    rig.rows.albums.getValue(album).auto[id(900)] = AutoRow(position = 0L, addedAt = 1L)

    rig.upkeep.catalogChanged(listOf(id(900)))

    assertThat(rig.content.searched.size - before).isEqualTo(1)
  }

  // --- Uno smart che aspetta dice la verita' ---------------------------------------------------------------------------------------------

  @Test
  fun unoSmartInFilaRestaInCercaFinoAlSuoGiroENonDiceNessunaFoto() = runTest {
    val rig = Rig(backgroundScope, perRound = 1)
    val first = rig.smart(1)
    val second = rig.smart(2)

    rig.upkeep.resumeAll()

    assertThat(rig.rows.albums.getValue(first).auto).isNotEmpty()
    assertThat(rig.sink.awaiting).containsExactly(second)

    nextRound()
    assertThat(rig.rows.albums.getValue(second).auto).isNotEmpty()
    assertThat(rig.sink.awaiting).isEmpty()
  }

  // --- Il budget da solo -----------------------------------------------------------------------------------------------------------------

  @Test
  fun ilBudgetConcedeNPoiMetteInFilaSenzaDuplicatiESpegneIlGiroQuandoFinisce() = runTest {
    val served = ArrayList<List<AlbumId>>()
    val budget = ContentBudget(backgroundScope, perRound = 2, round = ContentBudget.ROUND) { served += it }
    val ids = (1..5).map { albumId(it) }

    assertThat(budget.admit(ids)).containsExactly(ids[0], ids[1])
    assertThat(budget.waiting).isEqualTo(3)
    // Chiederne uno gia' in fila non lo sposta e non lo raddoppia.
    assertThat(budget.admit(listOf(ids[3], ids[3]))).isEmpty()
    assertThat(budget.waiting).isEqualTo(3)

    nextRound()
    assertThat(served).containsExactly(listOf(ids[2], ids[3]))
    nextRound()
    assertThat(served.last()).containsExactly(ids[4])
    assertThat(budget.waiting).isEqualTo(0)

    // Finita la fila, il giro si spegne: un nuovo smart trova subito posto.
    nextRound()
    nextRound()
    assertThat(budget.usedNow).isEqualTo(0)
    assertThat(budget.admit(listOf(ids[0]))).containsExactly(ids[0])
  }

  @Test
  fun unGiroSenzaRicerchePossibiliOSenzaDurataNonSiCrea() {
    val scope = CoroutineScope(Job())
    try {
      assertThrows(IllegalArgumentException::class.java) { ContentBudget(scope, perRound = 0) { } }
      assertThrows(IllegalArgumentException::class.java) { ContentBudget(scope, round = Duration.ZERO) { } }
    } finally {
      scope.cancel()
    }
  }
}
