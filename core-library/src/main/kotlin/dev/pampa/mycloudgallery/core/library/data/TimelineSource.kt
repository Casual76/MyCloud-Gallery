package dev.pampa.mycloudgallery.core.library.data

import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.index.Uuids
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Da dove il repository della timeline legge: il catalogo (`MediaCatalog` + `TimelineIndexDao`) in produzione, un
 * modello in memoria nei test. Tutte le funzioni sono fuori dal thread principale.
 */
interface TimelineSource {

  /** I cambiamenti del catalogo, dopo il commit, con `seq` che sale di uno (un salto = cambiamenti persi). */
  val changes: Flow<CatalogChange>

  /** Il `change_seq` attuale del catalogo. */
  suspend fun changeSeq(): Long

  /**
   * L'indice completo, **coerente**: le righe e il `seq` letti nello stesso stato del catalogo, con i numeri dei gruppi
   * e i riferimenti ai byte. Il `seq` del risultato e' quello a cui le righe corrispondono.
   */
  suspend fun buildIndex(): ColumnarTimelineSnapshot

  /** Le righe di [ids] che stanno ancora nell'indice. */
  suspend fun indexRows(ids: Collection<MediaId>): List<IndexRow>

  /** Quanti membri visibili ha ogni gruppo (per `group_id`). */
  suspend fun groupSizes(): Map<String, Int>

  /** I riferimenti ai byte del telefono leggibili adesso, per `media_id`. */
  suspend fun phoneRefs(ids: Collection<MediaId>): Map<String, MediaRef.Phone>

  /** Le identita' da nascondere o mostrare insieme a [ids]: i membri dei loro gruppi (la cella e' il gruppo, D20). */
  suspend fun withGroupMates(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
}

/**
 * Il catalogo vero. La lettura completa sta in **una transazione**: il `Cursor` di 100 mila righe si riempie a
 * finestre, e senza transazione una scrittura fra una finestra e l'altra sposterebbe le righe (una saltata, una
 * doppia). La transazione tiene ferme le scritture del catalogo per la durata della lettura (meno di un secondo, solo
 * al primo avvio o dopo un salto di `seq`). I riferimenti ai byte si leggono dopo, a blocchi: un cambiamento nel
 * frattempo ha un `seq` piu' alto e il repository lo riapplica.
 */
class CatalogTimelineSource(
  private val database: GalleryDatabase,
  private val catalog: MediaCatalog,
) : TimelineSource {

  private val dao = database.timelineIndexDao()

  override val changes: Flow<CatalogChange> get() = catalog.changes

  override suspend fun changeSeq(): Long = catalog.changeSeq()

  override suspend fun buildIndex(): ColumnarTimelineSnapshot {
    val base = database.withTransaction {
      val seq = dao.changeSeq()
      val sizes = dao.groupSizes().associate { it.groupId to it.size }
      dao.indexCursor().use { TimelineIndexReader.read(it, seq, sizes) }
    }
    if (base.size == 0) return base
    val refs = HashMap<Int, MediaRef.Phone>(base.size * 2)
    val ids = ArrayList<String>(TimelineIndexDao.MAX_IDS)
    val key = LongArray(2)
    var from = 0
    while (from < base.size) {
      val to = minOf(base.size, from + TimelineIndexDao.MAX_IDS)
      ids.clear()
      for (i in from until to) ids += UUID(base.idMsb[i], base.idLsb[i]).toString()
      var last: String? = null
      // Per ogni media prima la copia della versione corrente: vale la prima riga.
      for (row in dao.phoneRefs(ids)) {
        if (row.mediaId == last) continue
        last = row.mediaId
        val index = if (Uuids.parse(row.mediaId, key)) base.indexOf(key[0], key[1]) else -1
        if (index >= 0 && row.mediaStoreId > 0) {
          runCatching { MediaRef.Phone(row.volume, row.mediaStoreId, row.mimeType) }.getOrNull()?.let { refs[index] = it }
        }
      }
      from = to
    }
    return TimelinePatcher.withRefs(base, refs)
  }

  override suspend fun indexRows(ids: Collection<MediaId>): List<IndexRow> = catalog.indexRows(ids)

  override suspend fun groupSizes(): Map<String, Int> = dao.groupSizes().associate { it.groupId to it.size }

  override suspend fun phoneRefs(ids: Collection<MediaId>): Map<String, MediaRef.Phone> =
    catalog.phoneRefs(ids).mapKeys { it.key.value }

  override suspend fun withGroupMates(ids: Collection<MediaId>): Set<MediaId> {
    val out = LinkedHashSet<MediaId>(ids)
    val mediaDao = database.mediaDao()
    val seenGroups = HashSet<String>()
    for (id in ids) {
      val current = catalog.resolve(id) ?: continue
      val group = mediaDao.get(current.value)?.groupId ?: continue
      if (seenGroups.add(group)) mediaDao.groupMembers(group).forEach { out += MediaId(it.mediaId) }
    }
    return out
  }
}
