package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * **I budget JVM della Fase 3 con la libreria mista della Fase 4** (P7, `docs/fase4-progetto.md` §5.6): 100 mila righe,
 * 60% solo-NAS, 40% telefono (di cui un quarto anche sul NAS). Stessi scenari, stesse soglie larghe (5x l'atteso) della
 * Fase 3, **piu'** il confronto con la stessa libreria senza NAS misurata nello stesso processo: la presenza non deve
 * costare niente di apprezzabile (il layout e le ricomposizioni non la leggono; costruzione, istantanea e patch si'
 * una colonna in piu').
 *
 * I numeri veri li danno i dispositivi (`check.ps1 -Bench`, F13); questi sono la guardia contro le regressioni di
 * complessita'. Le righe `MIXED_*_BUDGET` finiscono nel report del pacchetto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixedLibraryBudgetTest {

  private val dir: File = Files.createTempDirectory("mixedbudget").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private val places = IntArray(40) { 3_000_000 + it }
  private val mixed = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = places, mix = NasMix.BUDGET)
  private val plain = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = places)

  /** Come `CatalogTimelineSource.buildIndex`: le righe dal cursore, poi i riferimenti del telefono e quelli del NAS. */
  private fun build(library: SyntheticLibrary, seq: Long): ColumnarTimelineSnapshot {
    val base = TimelineIndexReader.fromRows(library.indexRows(), seq, emptyMap())
    val phone = HashMap<Int, MediaRef.Phone>()
    val nas = HashMap<Int, MediaRef.Nas>()
    for (i in 0 until base.size) {
      library.phoneRefAt(i)?.let { phone[i] = it }
      library.nasRefAt(i)?.let { nas[i] = it }
    }
    return TimelinePatcher.withRefs(base, phone, nas)
  }

  @Test
  fun costruzioneEMemoriaDell_indiceMisto() {
    val rows = mixed.indexRows()
    build(mixed, 1) // JIT caldo: la misura vale per entrambe le librerie
    var built: ColumnarTimelineSnapshot? = null
    val mixedMs = TimelineIndexTest.timeMs { built = TimelineIndexReader.fromRows(rows, 1, emptyMap()) }
    var withRefs: ColumnarTimelineSnapshot? = null
    val refsMs = TimelineIndexTest.timeMs { withRefs = build(mixed, 2) }
    val plainRows = plain.indexRows()
    val plainMs = TimelineIndexTest.timeMs { TimelineIndexReader.fromRows(plainRows, 1, emptyMap()) }
    val index = withRefs!!
    val lookupMs = TimelineIndexTest.timeMs { for (i in 0 until index.size) check(index.indexOf(index.idAt(i)) == i) }
    val perItem = index.estimatedBytes().toDouble() / index.size
    val withoutTable = index.estimatedBytes(includeIdTable = false).toDouble() / index.size
    val heap = TimelineIndexTest.heapDeltaPerItem { build(mixed, 3).also { it.indexOf(it.idAt(0)) } }
    val nasOnly = (0 until index.size).count { index.nasRefAt(it) != null }
    println(
      "MIXED_INDEX_BUDGET: build100kMisto=${mixedMs}ms (senza NAS ${plainMs}ms) conRiferimenti=${refsMs}ms lookup100k=${lookupMs}ms " +
        "bytesPerItem=${"%.1f".format(perItem)} (senza tabella id ${"%.1f".format(withoutTable)}) heapDeltaPerItem~${"%.1f".format(heap)} " +
        "soloNas=$nasOnly righe=${index.size}",
    )
    assertThat(built!!.size).isEqualTo(index.size)
    assertThat(nasOnly.toDouble() / index.size).isWithin(0.01).of(0.60)
    // La memoria: Fase 3 ~90 B (tabella compresa), Fase 4 ~98 (D18: +8 B per la chiave di contenuto; il §1.3 ne ammetteva ~100).
    assertThat(perItem).isAtMost(110.0)
    assertThat(withoutTable).isAtMost(100.0)
    // Il tempo: soglie larghe della Fase 3, e non piu' del doppio della libreria senza NAS (stesso processo).
    assertThat(mixedMs).isLessThan(5_000)
    assertThat(lookupMs).isLessThan(5_000)
    assertThat(mixedMs).isAtMost(plainMs * 2 + 100)
  }

  @Test
  fun layoutDeiQuattroLivelliPerDueStiliSullaLibreriaMista() {
    val engine = TimelineLayoutEngine()
    val mixedView = FilteredTimelineView(mixed.index(), LibraryFilter.ALL)
    val plainView = FilteredTimelineView(plain.index(), LibraryFilter.ALL)
    fun spec(level: GridLevel, style: GridStyle) =
      GridSpec(level, style, intArrayOf(7, 5, 4, 3)[level.ordinal], widthPx = 1080, gapPx = 3, densityDp = 2.625f, newestAtBottom = true)
    // JIT caldo con la libreria senza NAS, poi si misurano entrambe.
    for (style in GridStyle.entries) for (level in GridLevel.entries) engine.layout(plainView, spec(level, style))
    var mixedTotal = 0L
    var plainTotal = 0L
    val times = StringBuilder()
    for (style in GridStyle.entries) for (level in GridLevel.entries) {
      var rows = 0
      val m = TimelineIndexTest.timeMs { rows = engine.layout(mixedView, spec(level, style)).rowCount }
      val p = TimelineIndexTest.timeMs { engine.layout(plainView, spec(level, style)) }
      mixedTotal += m
      plainTotal += p
      times.append("$level/$style=${m}ms($rows righe) ")
    }
    val warm = TimelineIndexTest.timeMs { for (level in GridLevel.entries) engine.layout(mixedView, spec(level, GridStyle.JUSTIFIED)) }
    println("MIXED_LAYOUT_BUDGET: totale8Misto=${mixedTotal}ms senzaNas=${plainTotal}ms quattroLivelliGiustificatiCaldo=${warm}ms $times")
    assertThat(mixedTotal).isLessThan(15_000)
    assertThat(warm).isLessThan(5_000)
    // Il layout non legge la presenza: la libreria mista costa quanto quella senza NAS (mai piu' del doppio, +50 ms di rumore).
    assertThat(mixedTotal).isAtMost(plainTotal * 2 + 50)
  }

  @Test
  fun istantaneaMcgt2Di100MilaElementi() = runTest {
    val index = build(mixed, seq = 4242)
    val plainIndex = build(plain, seq = 4242)
    val store = SnapshotStore(dir, io = StandardTestDispatcher(testScheduler))
    val plainStore = SnapshotStore(File(dir, "plain"), io = StandardTestDispatcher(testScheduler))
    var ok = false
    plainStore.write(plainIndex)
    val writeMs = TimelineIndexTest.timeMs { ok = store.write(index) }
    assertThat(ok).isTrue()
    var loaded: SnapshotStore.Loaded? = null
    val readMs = TimelineIndexTest.timeMs { loaded = store.read() }
    val warmMs = TimelineIndexTest.timeMs { store.read() }
    val plainWarmMs = TimelineIndexTest.timeMs { plainStore.read() }
    println(
      "MIXED_SNAPSHOT_BUDGET: file=${store.file.length() / 1024}KiB (senza NAS ${plainStore.file.length() / 1024}KiB) " +
        "write=${writeMs}ms read=${readMs}ms readWarm=${warmMs}ms (senza NAS ${plainWarmMs}ms)",
    )
    assertSameIndex(loaded!!.snapshot, index)
    assertThat(loaded!!.snapshot.seq).isEqualTo(4242)
    // Fase 3: 83 B per elemento; Fase 4: + la chiave di contenuto (8 B). Sempre sotto i 100.
    assertThat(store.file.length()).isLessThan(100L * index.size)
    assertThat(writeMs).isLessThan(5_000)
    assertThat(warmMs).isLessThan(2_000)
  }

  @Test
  fun cento_patchSuUnaLibreriaMista() = runBlocking {
    val model = ModelSource()
    val rows = mixed.indexRows()
    for ((i, row) in rows.withIndex()) {
      model.media[row.mediaId] = ModelSource.Media(row.copy(groupId = null), ref = mixed.phoneRefAt(i), nasRef = mixed.nasRefAt(i))
    }
    var current = model.buildIndex()
    val r = Random(5)
    val keys = model.media.keys.toList()
    val changes = (0 until 100).map {
      // Il lavoro tipico del NAS: contenuti nuovi, copie che compaiono e spariscono dal telefono.
      val m = keys[r.nextInt(keys.size)]
      val media = model.media.getValue(m)
      when (r.nextInt(3)) {
        0 -> media.nasRef?.let { media.nasRef = it.copy(contentKey = r.nextLong()) }
        1 -> if (CellFlags.has(media.row.cellFlags, CellFlags.ON_NAS)) {
          media.ref = if (media.ref == null) MediaRef.Phone("external_primary", 1L + r.nextInt(1_000_000), "image/jpeg") else null
          if (media.nasRef == null) media.nasRef = MediaRef.Nas(1L + r.nextInt(1_000_000), r.nextLong(), "image/jpeg")
        }
        else -> media.row = media.row.copy(aspectMilli = 500 + r.nextInt(2500))
      }
      model.commit(listOf(m), structural = false)
      CatalogChange(model.seq, setOf(MediaId(m)), false)
    }
    class Input(val change: CatalogChange, val rows: List<IndexRow>, val refs: Map<String, MediaRef.Phone>, val nas: Map<String, MediaRef.Nas>)
    val inputs = changes.map { c ->
      val patchRows = model.indexRows(c.ids)
      val refs = model.phoneRefs(patchRows.map { MediaId(it.mediaId) })
      val nas = model.nasRefs(patchRows.filter { CellFlags.has(it.cellFlags, CellFlags.ON_NAS) && it.mediaId !in refs }.map { MediaId(it.mediaId) })
      Input(c, patchRows, refs, nas)
    }
    val ms = TimelineIndexTest.timeMs {
      for (input in inputs) {
        current = TimelinePatcher.patch(current, input.change.ids, input.rows, input.refs, null, input.change.seq, input.nas).snapshot
      }
    }
    println("MIXED_PATCH_BUDGET: 100 patch su 100k misti = ${ms}ms (${ms / 100.0} ms a patch)")
    assertSameIndex(current, model.expected())
    assertThat(ms).isLessThan(20_000)
  }
}
