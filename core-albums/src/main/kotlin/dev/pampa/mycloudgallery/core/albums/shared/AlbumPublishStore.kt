package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.data.AlbumLimits
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark

// IL MIO CONTRIBUTO DUREVOLE (D6, §2.5 passo 6): le voci gia' dentro una testa pubblicata, quelle in coda con il loro numero d'ordine (il
// diario della condivisione salva il numero: ENTRY_STAGED -> PUBLISHED), l'ultima testa (numero, segno, ora del NAS: una ogni 5 minuti) e i
// suoi segmenti. Sopravvive alla morte del processo; l'adattatore Room e' di P1/P13 (in v6 le mie voci stanno in `shared_album_entry` con
// l'autore = questo dispositivo).

/** Una modifica in coda: il campo, il valore (null = lapide) e l'ora del NAS in cui e' stata fatta (null = da datare alla pubblicazione). */
data class StagedEntry(val seq: Long, val field: AlbumField, val value: AlbumValue?, val at: Long?) {
  override fun toString(): String = "StagedEntry($seq)"
}

/** Una voce dentro l'ultima testa pubblicata, con il numero d'ordine della modifica che l'ha prodotta. */
data class PublishedEntry(val entry: AlbumEntry, val seq: Long)

/** Lo stato del mio contributo ad [album]. */
data class OwnContribution(
  val album: AlbumId,
  /** L'ultimo numero d'ordine dato a una modifica. */
  val seq: Long = 0,
  /** Il numero d'ordine piu' alto gia' dentro una testa pubblicata. */
  val upTo: Long = 0,
  val headVersion: Long = 0,
  val headMark: HeadMark? = null,
  /** L'ora del NAS dell'ultima testa: la prossima non prima di 5 minuti, e nessuna voce nuova datata prima di questa (§2.4). */
  val headCreatedAt: Long? = null,
  val segments: Set<String> = emptySet(),
  val published: Map<AlbumKey, PublishedEntry> = emptyMap(),
  val staged: Map<AlbumKey, StagedEntry> = emptyMap(),
) {
  override fun toString(): String = "OwnContribution(v$headVersion, ${published.size} pubblicate, ${staged.size} in coda)"
}

/** Lo store del pubblicatore: una scrittura sostituisce lo stato di un album in una sola transazione. */
interface AlbumPublishStore {
  suspend fun albums(): Set<AlbumId>

  suspend fun load(album: AlbumId): OwnContribution?

  suspend fun save(state: OwnContribution)
}

/** Lo store in memoria (prove e build senza le tabelle v6): sopravvive a un pubblicatore nuovo, come un database a un processo nuovo. */
class MemoryAlbumPublishStore : AlbumPublishStore {
  private val lock = Any()
  private val states = LinkedHashMap<AlbumId, OwnContribution>()

  override suspend fun albums(): Set<AlbumId> = synchronized(lock) { states.keys.toSet() }

  override suspend fun load(album: AlbumId): OwnContribution? = synchronized(lock) { states[album] }

  override suspend fun save(state: OwnContribution) {
    synchronized(lock) { states[state.album] = state }
  }
}

/**
 * Come si dividono le voci di un contributo in segmenti, sempre allo stesso modo: per secchi della chiave (i primi bit dell'impronta, che e'
 * uniforme), cosi' una modifica cambia di solito **un** segmento e chi legge riscarica solo quello. Un secchio oltre i tetti di un segmento
 * si divide in ordine di chiave; se i segmenti fossero piu' di quelli che una testa puo' elencare, si impacchetta tutto in ordine.
 */
object AlbumSegmentPlan {

  /** Un secchio ogni ~512 voci, al piu' 32. */
  fun buckets(entries: Int): Int {
    var b = 1
    while (b < MAX_BUCKETS && entries > b * ENTRIES_PER_BUCKET) b *= 2
    return b
  }

  fun plan(album: AlbumId, entries: Collection<AlbumEntry>): List<EncodedSegment> {
    if (entries.isEmpty()) return emptyList()
    require(entries.size <= AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION) { "Contributo oltre ${AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION} voci." }
    val rows = entries.map { it to requireNotNull(AlbumDocs.entryBytesOrNull(it)) { "Voce non ammessa dal formato." }.size }
      .sortedBy { it.first.key }
    val b = buckets(rows.size)
    val shift = 8 - Integer.numberOfTrailingZeros(b)
    val groups = rows.groupBy { (it.first.key.bytes()[0].toInt() and 0xff) ushr shift }.toSortedMap().values
    var chunks = groups.flatMap(::chunk)
    if (chunks.size > AlbumLimits.MAX_HEAD_SEGMENTS) chunks = chunk(rows)
    check(chunks.size <= AlbumLimits.MAX_HEAD_SEGMENTS) { "Contributo che non sta in una testa." }
    return chunks.map { AlbumDocs.encodeSegment(album, it.map { row -> row.first }) }
  }

  /** In ordine di chiave, al piu' 8.192 voci e 4 MiB per segmento. */
  private fun chunk(rows: List<Pair<AlbumEntry, Int>>): List<List<Pair<AlbumEntry, Int>>> {
    val out = ArrayList<List<Pair<AlbumEntry, Int>>>()
    var current = ArrayList<Pair<AlbumEntry, Int>>()
    var bodies = 0L
    for (row in rows) {
      val size = AlbumDocs.segmentSize(current.size + 1, bodies + row.second)
      if (current.isNotEmpty() && (current.size >= AlbumLimits.MAX_ENTRIES_PER_SEGMENT || size > AlbumLimits.MAX_SEGMENT_BYTES)) {
        out += current
        current = ArrayList()
        bodies = 0
      }
      current += row
      bodies += row.second
    }
    if (current.isNotEmpty()) out += current
    return out
  }

  private const val ENTRIES_PER_BUCKET = 512
  private const val MAX_BUCKETS = 32
}
