package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La patch dal flusso dei cambiamenti e' **la stessa cosa** di una ricostruzione: su migliaia di scritture casuali
 * (nuovi, cancellati, nascosti e mostrati, attributi, date, gruppi e copertine) l'indice aggiornato a pezzi e quello
 * riletto da capo coincidono colonna per colonna.
 */
class TimelinePatchTest {

  private fun id(r: Random) = UUID(r.nextLong(), r.nextLong()).toString()

  private fun seed(model: ModelSource, r: Random, n: Int) {
    repeat(n) {
      val m = id(r)
      model.media[m] = ModelSource.Media(
        indexRow(m, sortTime = 1_700_000_000_000L - r.nextLong(1_000_000_000L), localDay = 20230000 + r.nextInt(1, 1229),
          flags = if (r.nextInt(10) == 0) CellFlags.FAVORITE else 0, aspect = 500 + r.nextInt(2500), thumb = ByteArray(1 + r.nextInt(30)) { it.toByte() }),
        ref = if (r.nextInt(5) == 0) null else MediaRef.Phone("external_primary", 1L + r.nextInt(1_000_000), "image/jpeg"),
      )
    }
  }

  /** Una scrittura casuale del "catalogo"; restituisce le identita' toccate e se e' strutturale. */
  private fun mutate(model: ModelSource, r: Random): Pair<List<String>, Boolean> {
    val keys = model.media.keys.toList()
    val pick = { keys[r.nextInt(keys.size)] }
    return when (r.nextInt(9)) {
      0 -> {
        val m = id(r)
        model.media[m] = ModelSource.Media(indexRow(m, sortTime = 1_700_000_000_000L - r.nextLong(1_000_000_000L), thumb = byteArrayOf(1, 2, 3)),
          ref = MediaRef.Phone(if (r.nextBoolean()) "external_primary" else "1a2b-3c4d", 5L, "video/mp4"))
        listOf(m) to true
      }
      1 -> {
        val m = pick()
        // Il catalogo annuncia anche i compagni di gruppo (la copertina effettiva puo' cambiare).
        val mates = model.media[m]!!.group?.let { g -> model.media.filter { it.value.group == g }.keys.toList() } ?: listOf(m)
        model.media.remove(m)
        model.coverOf.entries.removeIf { it.value == m }
        (mates + m).distinct() to true
      }
      2 -> {
        val m = pick()
        model.media[m]!!.hidden = !model.media[m]!!.hidden
        listOf(m) to true
      }
      3 -> {
        val m = pick()
        val media = model.media[m]!!
        media.row = media.row.copy(cellFlags = media.row.cellFlags xor CellFlags.FAVORITE, aspectMilli = 500 + r.nextInt(2500))
        listOf(m) to false
      }
      4 -> {
        val m = pick()
        val media = model.media[m]!!
        media.row = media.row.copy(sortTime = media.row.sortTime + r.nextLong(-50_000_000L, 50_000_000L), localDay = 20240101)
        listOf(m) to true
      }
      5 -> {
        // Un gruppo di 2-4 media sciolti, con la copertina.
        val members = List(2 + r.nextInt(3)) { pick() }.distinct().filter { model.media[it]!!.group == null }
        if (members.size < 2) return emptyList<String>() to false
        val g = id(r)
        members.forEach { model.media[it]!!.group = g }
        model.coverOf[g] = members[0]
        members to true
      }
      6 -> {
        val g = model.coverOf.keys.randomOrNull(r) ?: return emptyList<String>() to false
        val members = model.media.filter { it.value.group == g }.keys.toList()
        members.forEach { model.media[it]!!.group = null }
        model.coverOf.remove(g)
        members to true
      }
      7 -> {
        val m = pick()
        model.media[m]!!.ref = if (r.nextBoolean()) null else MediaRef.Phone("external_primary", r.nextLong(1, 9_999_999), null)
        listOf(m) to false
      }
      else -> {
        val m = pick()
        val media = model.media[m]!!
        media.row = media.row.copy(thumbHash = if (r.nextBoolean()) null else ByteArray(25) { r.nextInt().toByte() }, placeId = r.nextInt(5))
        listOf(m) to false
      }
    }
  }

  private suspend fun patchWith(model: ModelSource, current: ColumnarTimelineSnapshot, change: CatalogChange): ColumnarTimelineSnapshot {
    val rows = model.indexRows(change.ids)
    val refs = model.phoneRefs(rows.map { MediaId(it.mediaId) })
    val sizes = if (change.structural || rows.any { it.groupId != null }) model.groupSizes() else null
    return TimelinePatcher.patch(current, change.ids, rows, refs, sizes, change.seq).snapshot
  }

  @Test
  fun laPatchEUgualeAllaRicostruzioneSuScrittureCasuali() = runBlocking {
    val r = Random(2024)
    val model = ModelSource()
    seed(model, r, 3_000)
    var current = model.buildIndex()
    var checks = 0
    repeat(2_000) { step ->
      val (ids, structural) = mutate(model, r)
      if (ids.isEmpty()) return@repeat
      model.commit(ids, structural)
      val change = CatalogChange(model.seq, model.withGroupMates(ids.map { MediaId(it) }), structural)
      current = patchWith(model, current, change)
      if (step % 20 == 0 || step > 1_980) {
        assertSameIndex(current, model.expected())
        checks++
      }
    }
    assertSameIndex(current, model.expected())
    assertThat(checks).isGreaterThan(90)
  }

  @Test
  fun piuCambiamentiAccorpatiInUnaPatchSola() = runBlocking {
    val r = Random(7)
    val model = ModelSource()
    seed(model, r, 1_000)
    val start = model.buildIndex()
    val all = LinkedHashSet<MediaId>()
    var structural = false
    repeat(300) {
      val (ids, s) = mutate(model, r)
      if (ids.isNotEmpty()) {
        model.commit(ids, s)
        all += model.withGroupMates(ids.map { MediaId(it) })
        structural = structural || s
      }
    }
    val patched = patchWith(model, start, CatalogChange(model.seq, all, structural))
    assertSameIndex(patched, model.expected())
  }

  @Test
  fun rimuovereSubitoEGliElementiUscitiTengonoIlRiferimento() = runBlocking {
    val library = SyntheticLibrary(seed = 11, count = 5_000)
    val index = TimelinePatcher.withRefs(library.index(seq = 3), mapOf(10 to MediaRef.Phone("external_primary", 99, "image/jpeg")))
    val gone = listOf(index.idAt(10), index.idAt(11), index.idAt(4_999))
    val removed = TimelinePatcher.remove(index, gone)
    assertThat(removed.seq).isEqualTo(3)
    assertThat(removed.size).isEqualTo(4_997)
    assertThat(gone.all { removed.indexOf(it) < 0 }).isTrue()
    assertThat(removed.idAt(10)).isEqualTo(index.idAt(12))
    // La patch che li toglie dice quanti sono usciti e conserva il riferimento per il viewer.
    val result = TimelinePatcher.patch(index, gone, emptyList(), emptyMap(), null, 4)
    assertThat(result.removed).isEqualTo(3)
    assertThat(result.droppedRefs).containsExactly(index.idAt(10), MediaRef.Phone("external_primary", 99, "image/jpeg"))
    assertSameIndex(result.snapshot, TimelinePatcher.withSeq(removed, 4))
  }

  @Test
  fun budgetDellaPatchSu100Mila() = runBlocking {
    val library = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT)
    val model = ModelSource()
    for (row in library.indexRows()) model.media[row.mediaId] = ModelSource.Media(row.copy(groupId = null))
    var current = model.buildIndex()
    val r = Random(5)
    val changes = (0 until 100).map {
      val (ids, s) = mutate(model, r)
      model.commit(ids, s)
      CatalogChange(model.seq, model.withGroupMates(ids.map { MediaId(it) }), s)
    }
    // Cento cambiamenti come cento patch (il caso peggiore: niente accorpamento). Le righe si leggono prima, dallo stato
    // finale del modello (la patch e' idempotente): si misura solo la patch.
    class Input(val change: CatalogChange, val rows: List<dev.pampa.mycloudgallery.core.database.entity.IndexRow>, val refs: Map<String, MediaRef.Phone>, val sizes: Map<String, Int>?)
    val inputs = changes.map { c ->
      val rows = model.indexRows(c.ids)
      Input(c, rows, model.phoneRefs(rows.map { MediaId(it.mediaId) }), if (c.structural) model.groupSizes() else null)
    }
    val ms = TimelineIndexTest.timeMs {
      for (input in inputs) current = TimelinePatcher.patch(current, input.change.ids, input.rows, input.refs, input.sizes, input.change.seq).snapshot
    }
    println("PATCH_BUDGET: 100 patch su 100k = ${ms}ms (${ms / 100.0} ms a patch)")
    assertSameIndex(current, model.expected())
    assertThat(ms).isLessThan(20_000)
    assertThat(changes.map { it.seq }).isEqualTo((1L..100L).toList())
  }
}
