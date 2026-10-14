package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.replica.store.BlobRow
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * L'indice in memoria dei blob della replica (D15): (chiave ridotta, classe) → dove sta. Le letture sono bloccanti e
 * veloci (dispatcher delle miniature), le scritture arrivano dalla sincronizzazione.
 *
 * **Solo blob attestati** (R1 della revisione 4A). La chiave di una voce della tabella di un segmento e' scritta da chi
 * pubblica e non e' legata ne' ai byte ne' alla share: un dispositivo compromesso puo' mettere nell'area comune una chiave
 * inventata con gli stessi primi 64 bit di quella di una foto dell'area privata di un altro account. Una voce vale quindi
 * solo se il **suo** dispositivo ha, nella **stessa share**, un record presente con la stessa chiave **intera** ([attested]):
 * la chiave di un record e' un SHA-256 di `qh1` e dimensione, e nessuno ne fabbrica una con gli stessi primi 64 bit di
 * un'altra. Le voci non attestate restano qui (un record puo' arrivare con la testa dopo) ma non si consegnano mai.
 *
 * **Share e chiave intera.** [locate] con un [BlobScope] cerca solo nei pacchi delle share in cui il media ha una posizione,
 * con la sua area per prima, e solo con la chiave intera del media se chi chiama la conosce.
 *
 * Piu' candidati per la stessa chiave (piu' dispositivi l'hanno preparata, E-I1, D6 "da qualunque dispositivo
 * approvato"; o due chiavi intere con gli stessi primi 64 bit) si ordinano in modo **deterministico**: chiave intera,
 * poi id di dispositivo minore, poi segmento. Cosi' due repliche con gli stessi dati mostrano gli stessi byte (E-I7).
 */
class BlobIndex {

  private data class Key(val short: Long, val cls: SegmentClass)

  /** Una voce: il blob, la share del suo pacco (chiave in minuscolo) e se e' attestata. */
  private class Entry(val row: BlobRow, val share: String?, @Volatile var attested: Boolean)

  private val lock = ReentrantReadWriteLock()
  private val entries = HashMap<Key, MutableList<Entry>>()

  fun locate(short: Long, cls: SegmentClass): BlobLocation? = lock.read {
    entries[Key(short, cls)]?.firstOrNull { it.attested }?.location()
  }

  /** Come [locate], dentro [scope]: solo le share del media (nel suo ordine) e, se data, solo la chiave intera. */
  fun locate(short: Long, cls: SegmentClass, scope: BlobScope): BlobLocation? = lock.read {
    val list = entries[Key(short, cls)] ?: return@read null
    for (share in scope.shareKeys) {
      list.firstOrNull { it.attested && it.share == share && (scope.fullKey == null || it.row.contentKey == scope.fullKey) }
        ?.let { return@read it.location() }
    }
    null
  }

  /** Le classi per cui la chiave ha un blob attestato. */
  fun classesOf(short: Long): Set<SegmentClass> = lock.read {
    SegmentClass.entries.filterTo(HashSet()) { cls -> entries[Key(short, cls)]?.any { it.attested } == true }
  }

  /**
   * Aggiunge [rows] (con la share del loro pacco, [shareOf]); [attested] dice per ognuna se il suo dispositivo ha nella share
   * un record presente con la stessa chiave intera.
   */
  fun add(rows: Collection<BlobRow>, shareOf: (BlobRow) -> String?, attested: (BlobRow) -> Boolean) {
    if (rows.isEmpty()) return
    lock.write {
      for (row in rows) {
        val list = entries.getOrPut(Key(row.contentKey.short, row.cls)) { ArrayList(1) }
        list.removeAll { it.row.packId == row.packId && it.row.contentKey == row.contentKey }
        list += Entry(row, shareOf(row), attested(row))
        if (list.size > 1) list.sortWith(ORDER)
      }
    }
  }

  /**
   * I record di [deviceId] in [share] sono cambiati (testa nuova, record tolti): le sue voci in quella share valgono solo se
   * la chiave e' fra [presentKeys]. Restituisce quante voci hanno cambiato stato.
   */
  fun reattest(share: String, deviceId: String, presentKeys: Set<ContentKey>): Int = lock.write {
    var changed = 0
    for (list in entries.values) {
      for (entry in list) {
        if (entry.share != share || entry.row.deviceId != deviceId) continue
        // Le immagini preparate qui e non ancora rilette dalla propria testa: M3 le ha gia' controllate alla pubblicazione.
        if (entry.row.segment == RecordRow.LOCAL_SEGMENT) continue
        val now = entry.row.contentKey in presentKeys
        if (now != entry.attested) {
          entry.attested = now
          changed++
        }
      }
    }
    changed
  }

  /** Vero se ci sono voci di [deviceId] in [share] (per non rileggere i record quando non serve). */
  fun hasEntries(share: String, deviceId: String): Boolean = lock.read {
    entries.values.any { list -> list.any { it.share == share && it.row.deviceId == deviceId } }
  }

  /** Le chiavi intere attestate delle voci di [segment] (R5: cio' che un dispositivo ha davvero pubblicato). */
  fun keysOfSegment(segment: String, cls: SegmentClass): Set<ContentKey>? = lock.read {
    var found = false
    val out = HashSet<ContentKey>()
    for ((key, list) in entries) {
      if (key.cls != cls) continue
      for (entry in list) {
        if (entry.row.segment != segment) continue
        found = true
        if (entry.attested) out += entry.row.contentKey
      }
    }
    if (found) out else null
  }

  fun remove(rows: Collection<BlobRow>) {
    if (rows.isEmpty()) return
    lock.write {
      for (row in rows) {
        val key = Key(row.contentKey.short, row.cls)
        val list = entries[key] ?: continue
        list.removeAll { it.row.packId == row.packId && it.row.contentKey == row.contentKey }
        if (list.isEmpty()) entries.remove(key)
      }
    }
  }

  fun clear() = lock.write { entries.clear() }

  /** Le voci scelte (una per chiave e classe, solo attestate), per i confronti fra repliche. */
  fun chosen(): List<BlobRow> = lock.read { entries.values.mapNotNull { list -> list.firstOrNull { it.attested }?.row } }

  fun size(): Int = lock.read { entries.size }

  private fun Entry.location() = BlobLocation(row.packId, row.offset, row.length, row.cls)

  private companion object {
    val ORDER: Comparator<Entry> =
      compareBy<Entry> { it.row.contentKey.hex }.thenBy { it.row.deviceId }.thenBy { it.row.segment }.thenBy { it.row.packId }
  }
}
