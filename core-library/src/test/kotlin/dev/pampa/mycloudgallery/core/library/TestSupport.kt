package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.TimelineSource
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Una riga dell'indice come la scrive il catalogo. */
fun indexRow(
  id: String,
  sortTime: Long,
  localDay: Int = 20240312,
  kind: String = "photo",
  source: String = "camera",
  flags: Int = 0,
  aspect: Int = 1000,
  duration: Long? = null,
  place: Int? = null,
  thumb: ByteArray? = null,
  group: String? = null,
) = IndexRow(id, sortTime, localDay, kind, source, flags, aspect, duration, place, thumb, group)

/** Le celle della Libreria sintetica come righe del catalogo (con i gruppi delle copertine). */
fun SyntheticLibrary.indexRows(): List<IndexRow> = (0 until count).map { i ->
  val m = cellAt(i)
  indexRow(
    id = m.id.value, sortTime = m.sortTimeMs, localDay = m.localDay, kind = m.kind.code, source = m.source.code, flags = m.flags,
    aspect = m.aspectMilli, duration = m.durationMs.takeIf { it > 0 }, place = m.placeId.takeIf { it != 0 },
    thumb = m.thumbHash?.bytes(), group = m.groupId?.value,
  )
}

/** Due indici uguali colonna per colonna (contratto + riferimenti ai byte + gruppi). Veloce: si ferma alla prima differenza. */
fun assertSameIndex(actual: TimelineSnapshot, expected: TimelineSnapshot, checkSeq: Boolean = true) {
  if (checkSeq) assertThat(actual.seq).isEqualTo(expected.seq)
  assertThat(actual.size).isEqualTo(expected.size)
  for (i in 0 until expected.size) {
    fun check(what: String, a: Any?, e: Any?) {
      if (a != e) assertWithMessage("$what dell'elemento $i").that(a).isEqualTo(e)
    }
    check("id", actual.idAt(i), expected.idAt(i))
    check("istante", actual.sortTimeAt(i), expected.sortTimeAt(i))
    check("giorno", actual.localDayAt(i), expected.localDayAt(i))
    check("tipo", actual.kindAt(i), expected.kindAt(i))
    check("sorgente", actual.sourceAt(i), expected.sourceAt(i))
    check("flag", actual.flagsAt(i), expected.flagsAt(i))
    check("proporzione", actual.aspectMilliAt(i), expected.aspectMilliAt(i))
    check("durata", actual.durationMsAt(i), expected.durationMsAt(i))
    check("luogo", actual.placeAt(i), expected.placeAt(i))
    check("gruppo", actual.groupSizeAt(i), expected.groupSizeAt(i))
    check("segnaposto", actual.thumbHashAt(i), expected.thumbHashAt(i))
    if (actual is ColumnarTimelineSnapshot && expected is ColumnarTimelineSnapshot) {
      check("riferimento", actual.refAt(i), expected.refAt(i))
      check("chiave del gruppo", actual.groupKeyAt(i), expected.groupKeyAt(i))
    }
  }
}

/**
 * Un catalogo in memoria che si comporta come `MediaCatalog` per l'indice: media con attributi, nascosti, gruppi con la
 * copertina effettiva (la copertina se si vede, altrimenti il membro visibile piu' recente), riferimenti ai byte, e un
 * cambiamento con `seq` +1 per ogni scrittura, emesso dopo. [dropEmissions] simula i cambiamenti persi dal flusso.
 */
class ModelSource : TimelineSource {

  class Media(var row: IndexRow, var hidden: Boolean = false, var group: String? = null, var ref: MediaRef.Phone? = null)

  val media = LinkedHashMap<String, Media>()
  val coverOf = HashMap<String, String>()
  var seq = 0L
    private set
  var dropEmissions = false
  val builds = AtomicInteger()
  private val flow = MutableSharedFlow<CatalogChange>(extraBufferCapacity = 4096)

  override val changes: Flow<CatalogChange> get() = flow

  override suspend fun changeSeq(): Long = seq

  override suspend fun buildIndex(): ColumnarTimelineSnapshot {
    builds.incrementAndGet()
    return expected()
  }

  /** L'indice atteso adesso (come [buildIndex], senza contare una lettura). */
  fun expected(): ColumnarTimelineSnapshot {
    val rows = indexed()
    return TimelineIndexReader.fromRows(rows, seq, groupSizesNow(), refsFor(rows.map { it.mediaId }))
  }

  override suspend fun indexRows(ids: Collection<MediaId>): List<IndexRow> {
    val wanted = ids.mapTo(HashSet()) { it.value }
    return indexed().filter { it.mediaId in wanted }
  }

  override suspend fun groupSizes(): Map<String, Int> = groupSizesNow()

  override suspend fun phoneRefs(ids: Collection<MediaId>): Map<String, MediaRef.Phone> = refsFor(ids.map { it.value })

  override suspend fun withGroupMates(ids: Collection<MediaId>): Set<MediaId> {
    val out = LinkedHashSet(ids)
    for (id in ids) media[id.value]?.group?.let { g -> media.filter { it.value.group == g }.keys.forEach { out += MediaId(it) } }
    return out
  }

  /** Il "commit" di una scrittura: alza seq e annuncia [ids] (piu' i compagni di gruppo, come il catalogo). */
  fun commit(ids: Collection<String>, structural: Boolean) {
    val all = LinkedHashSet(ids)
    for (id in ids) media[id]?.group?.let { g -> media.filter { it.value.group == g }.keys.forEach { all += it } }
    seq++
    if (!dropEmissions) check(flow.tryEmit(CatalogChange(seq, all.mapTo(LinkedHashSet()) { MediaId(it) }, structural)))
  }

  fun indexed(): List<IndexRow> {
    val representative = HashMap<String, String>()
    for (g in media.values.mapNotNullTo(HashSet()) { it.group }) {
      val cover = coverOf[g]
      val visible = media.values.filter { it.group == g && !it.hidden }
      val rep = if (cover != null && media[cover]?.let { !it.hidden && it.group == g } == true) cover
      else visible.maxWithOrNull(compareBy<Media>({ it.row.sortTime }, { it.row.mediaId }))?.row?.mediaId
      if (rep != null) representative[g] = rep
    }
    return media.values.filter { m ->
      !m.hidden && (m.group == null || representative[m.group] == m.row.mediaId)
    }.map { m ->
      val g = m.group
      val flags = (m.row.cellFlags and (CellFlags.GROUP_COVER or CellFlags.GROUP_MEMBER).inv()) or (if (g != null) CellFlags.GROUP_COVER else 0)
      m.row.copy(cellFlags = flags, groupId = g)
    }.sortedWith(compareByDescending<IndexRow> { it.sortTime }.thenByDescending { it.mediaId })
  }

  private fun groupSizesNow(): Map<String, Int> =
    media.values.filter { it.group != null && !it.hidden }.groupingBy { it.group!! }.eachCount()

  private fun refsFor(ids: Collection<String>): Map<String, MediaRef.Phone> =
    ids.mapNotNull { id -> media[id]?.ref?.let { id to it } }.toMap()
}
