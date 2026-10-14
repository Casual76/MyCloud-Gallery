package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.replica.store.BlobRow
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * L'indice in memoria dei blob della replica (D15): (chiave ridotta, classe) → dove sta. Le letture sono bloccanti e
 * veloci (dispatcher delle miniature), le scritture arrivano dalla sincronizzazione.
 *
 * Piu' candidati per la stessa chiave (piu' dispositivi l'hanno preparata, E-I1, D6 "da qualunque dispositivo
 * approvato"; o due chiavi intere con gli stessi primi 64 bit) si ordinano in modo **deterministico**: chiave intera,
 * poi id di dispositivo minore, poi segmento. Cosi' due repliche con gli stessi dati mostrano gli stessi byte (E-I7).
 */
class BlobIndex {

  private data class Key(val short: Long, val cls: SegmentClass)

  private val lock = ReentrantReadWriteLock()
  private val entries = HashMap<Key, MutableList<BlobRow>>()

  fun locate(short: Long, cls: SegmentClass): BlobLocation? = lock.read {
    entries[Key(short, cls)]?.firstOrNull()?.let { BlobLocation(it.packId, it.offset, it.length, it.cls) }
  }

  /** Le classi per cui la chiave ha un blob. */
  fun classesOf(short: Long): Set<SegmentClass> = lock.read {
    SegmentClass.entries.filterTo(HashSet()) { entries[Key(short, it)]?.isNotEmpty() == true }
  }

  fun add(rows: Collection<BlobRow>) {
    if (rows.isEmpty()) return
    lock.write {
      for (row in rows) {
        val list = entries.getOrPut(Key(row.contentKey.short, row.cls)) { ArrayList(1) }
        list.removeAll { it.packId == row.packId && it.contentKey == row.contentKey }
        list += row
        if (list.size > 1) list.sortWith(ORDER)
      }
    }
  }

  fun remove(rows: Collection<BlobRow>) {
    if (rows.isEmpty()) return
    lock.write {
      for (row in rows) {
        val key = Key(row.contentKey.short, row.cls)
        val list = entries[key] ?: continue
        list.removeAll { it.packId == row.packId && it.contentKey == row.contentKey }
        if (list.isEmpty()) entries.remove(key)
      }
    }
  }

  fun clear() = lock.write { entries.clear() }

  /** Le voci scelte (una per chiave e classe), per i confronti fra repliche. */
  fun chosen(): List<BlobRow> = lock.read { entries.values.mapNotNull { it.firstOrNull() } }

  fun size(): Int = lock.read { entries.size }

  private companion object {
    val ORDER: Comparator<BlobRow> =
      compareBy<BlobRow> { it.contentKey.hex }.thenBy { it.deviceId }.thenBy { it.segment }.thenBy { it.packId }
  }
}
