package dev.pampa.mycloudgallery.core.library.index

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.ThumbHash

/**
 * L'indice della timeline a colonne (D2, §1.2 `core-library`): un array per attributo, nessun oggetto per elemento.
 * Immutabile: un cambiamento del catalogo produce una copia ([TimelinePatcher]), e chi legge una colonna non vede mai
 * un indice a meta'. L'ordine e' quello della query del catalogo: **0 e' il piu' recente**.
 *
 * Memoria per elemento (misurata da `IndexBudgetTest` con [estimatedBytes]): identita' 16 B, istante 8, giorno 4,
 * tipo e sorgente 2, flag 4, proporzione 2, durata 4, luogo 4, numero del gruppo 2, segnaposto circa 25 + 4 di
 * scostamento, riferimento ai byte 10 (`_ID` o posizione NAS, volume, MIME: cosi' le miniature partono dall'istantanea
 * su file prima di aprire il database), **chiave di contenuto 8 (Fase 4, D18)**, piu' la tabella delle identita'
 * (circa 5 B) quando serve `indexOf`.
 *
 * **Un solo riferimento ai byte per cella (Fase 4, P7): la copia leggibile adesso, il telefono se c'e', altrimenti il
 * NAS.** Le colonne `refId`/`refVolume`/`refMime` sono di entrambi: `refVolume` = [NAS_REF] marca un riferimento al NAS
 * (`refId` e' allora la posizione nella replica, `refMime` il MIME, `contentKey` la chiave di contenuto a 64 bit). E' un
 * risparmio di 9 B per elemento rispetto a due serie di colonne (il §1.3 ne ammetteva circa 20) e non perde niente: per
 * un media su telefono e NAS la griglia usa la miniatura del telefono (D18), e il NAS torna a essere il riferimento
 * appena la copia del telefono non e' leggibile (E-P22).
 *
 * Oltre al contratto: [refAt] (il riferimento ai byte del telefono), [nasRefAt] (quello del NAS), [thumbRefAt] (quello
 * che serve alla griglia e al viewer: l'uno o l'altro), [versionKeyAt] (per `ThumbKey.versionKey`: cambia quando
 * cambiano i byte o il segnaposto) e [groupKeyAt].
 */
@Stable
class ColumnarTimelineSnapshot internal constructor(
  override val seq: Long,
  override val size: Int,
  internal val idMsb: LongArray,
  internal val idLsb: LongArray,
  internal val sortTime: LongArray,
  internal val localDay: IntArray,
  internal val kind: ByteArray,
  internal val source: ByteArray,
  internal val flags: IntArray,
  internal val aspect: ShortArray,
  internal val duration: IntArray,
  internal val place: IntArray,
  internal val groupSize: ShortArray,
  /** `size + 1` scostamenti in [thumbBytes]: il segnaposto di `i` e' `[thumbOffsets[i], thumbOffsets[i + 1])`. */
  internal val thumbOffsets: IntArray,
  internal val thumbBytes: ByteArray,
  /** L'`_ID` del MediaStore, 0 se non c'e' un riferimento leggibile. */
  internal val refId: LongArray,
  /**
   * Volume e MIME come indice + 1 in [volumes] e [mimes]; 0 = nessuno. `refVolume` vale [NAS_REF] per un riferimento
   * al NAS: `refId` e' allora l'id della posizione nella replica.
   */
  internal val refVolume: ByteArray,
  internal val refMime: ByteArray,
  /** La chiave di contenuto a 64 bit (`ContentKey.short`) di un riferimento NAS, 0 negli altri casi. */
  internal val contentKey: LongArray,
  internal val volumes: Array<String>,
  internal val mimes: Array<String>,
  /** I gruppi, sparsi e ordinati per posizione: solo le copertine (circa l'1%). */
  internal val groupIndex: IntArray,
  internal val groupMsb: LongArray,
  internal val groupLsb: LongArray,
) : TimelineSnapshot {

  init {
    require(idMsb.size == size && contentKey.size == size && thumbOffsets.size == size + 1 && groupIndex.size == groupMsb.size) { "Colonne incoerenti." }
  }

  private val ids: IdTable by lazy { IdTable(idMsb, idLsb, size) }

  // Il segnaposto si crea al primo uso (ThumbHash valida il testo) e si tiene in una piccola cache a indirizzo diretto:
  // la finestra visibile non supera mai THUMB_CACHE celle, e una voce e' un oggetto immutabile (pubblicazione sicura).
  private val thumbCache = arrayOfNulls<ThumbSlot>(THUMB_CACHE)

  override fun idAt(i: Int): MediaId = Uuids.toMediaId(idMsb[i], idLsb[i])

  override fun sortTimeAt(i: Int): Long = sortTime[i]

  override fun localDayAt(i: Int): Int = localDay[i]

  override fun kindAt(i: Int): MediaKind = KINDS[kind[i].toInt()]

  override fun sourceAt(i: Int): MediaSource = SOURCES[source[i].toInt()]

  override fun flagsAt(i: Int): Int = flags[i]

  override fun aspectMilliAt(i: Int): Int = aspect[i].toInt()

  override fun durationMsAt(i: Int): Long = duration[i].toLong()

  override fun placeAt(i: Int): Int = place[i]

  override fun groupSizeAt(i: Int): Int = groupSize[i].toInt()

  override fun thumbHashAt(i: Int): ThumbHash? {
    val from = thumbOffsets[i]
    val to = thumbOffsets[i + 1]
    if (to <= from) return null
    val slot = thumbCache[i and (THUMB_CACHE - 1)]
    if (slot != null && slot.index == i) return slot.hash
    val hash = runCatching { ThumbHash.of(thumbBytes.copyOfRange(from, to)) }.getOrNull()
    thumbCache[i and (THUMB_CACHE - 1)] = ThumbSlot(i, hash)
    return hash
  }

  override fun indexOf(id: MediaId): Int = ids.find(id)

  /** Come [indexOf], dalle due meta' dell'identita' (nessun oggetto). */
  fun indexOf(msb: Long, lsb: Long): Int = ids.find(msb, lsb)

  /** Il riferimento ai byte sul telefono, o null se adesso non ce n'e' uno leggibile (E-P22: si mostra il segnaposto). */
  fun refAt(i: Int): MediaRef.Phone? {
    val volume = refVolume[i].toInt() and 0xFF
    val id = refId[i]
    if (volume == 0 || volume == NAS_REF || id <= 0L) return null
    return MediaRef.Phone(volumes[volume - 1], id, mimeAt(i))
  }

  /**
   * Il riferimento ai byte sul NAS, quando e' questo il riferimento leggibile adesso (niente copia leggibile sul
   * telefono). La posizione e la chiave di contenuto sono quelle della replica; il percorso non sta qui.
   */
  override fun nasRefAt(i: Int): MediaRef.Nas? {
    if ((refVolume[i].toInt() and 0xFF) != NAS_REF) return null
    val id = refId[i]
    if (id <= 0L) return null
    return MediaRef.Nas(id, contentKey[i], mimeAt(i))
  }

  /** Il riferimento da cui la griglia carica la miniatura e il viewer apre il media: il telefono se c'e', altrimenti il NAS. */
  fun thumbRefAt(i: Int): MediaRef? {
    val volume = refVolume[i].toInt() and 0xFF
    return when (volume) {
      0 -> null
      NAS_REF -> nasRefAt(i)
      else -> refAt(i)
    }
  }

  private fun mimeAt(i: Int): String? = (refMime[i].toInt() and 0xFF).let { if (it == 0) null else mimes[it - 1] }

  /** Quante celle hanno almeno una posizione sul NAS (`CellFlags.ON_NAS`). */
  val nasCount: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { countFlag(CellFlags.ON_NAS) }

  /**
   * Il badge di presenza (D18, §1.5). Come `PresenceBadge.of` sui flag, tranne che **finche' nessuna cella e' sul NAS
   * "solo telefono" non si da'**: D15 lo spegneva perche' senza NAS ogni cella sarebbe "solo telefono", cioe' rumore.
   * Appena una cella compare sul NAS (la prima scansione ne ha viste) il badge diventa un'informazione vera: quelle
   * che non ci sono ancora.
   */
  override fun presenceAt(i: Int): PresenceBadge? {
    val badge = PresenceBadge.of(flags[i])
    return if (badge == PresenceBadge.PHONE_ONLY && nasCount == 0) null else badge
  }

  /**
   * Una chiave che cambia quando cambiano i byte mostrati (nuovo `_ID`, nuovo segnaposto, nuova proporzione, una
   * modifica): `ThumbKey.versionKey`, cosi' la cella si aggiorna dopo una modifica esterna (F3).
   */
  fun versionKeyAt(i: Int): Long {
    // Un riferimento al NAS cambia chiave con il contenuto (non con la posizione: una rinomina fatta dal PC non rifa'
    // la miniatura, D4); gli altri casi restano come nella Fase 3.
    val nas = (refVolume[i].toInt() and 0xFF) == NAS_REF
    var h = (if (nas) contentKey[i] xor NAS_KEY_SALT else refId[i]) * -0x61c8864680b583ebL
    h = (h xor aspect[i].toLong()) * -0x40a7b892e31b1a47L
    h = (h xor (flags[i] and CellFlags.EDITED).toLong()) * -0x6b2fb644ecceee15L
    for (k in thumbOffsets[i] until thumbOffsets[i + 1]) h = (h xor thumbBytes[k].toLong()) * 0x100000001b3L
    return h xor (h ushr 29)
  }

  /** L'identita' del gruppo rappresentato dalla cella [i] (copertina), o null. */
  fun groupKeyAt(i: Int): String? {
    val g = groupIndex.binarySearch(i)
    return if (g >= 0) Uuids.toText(groupMsb[g], groupLsb[g]) else null
  }

  /**
   * I byte occupati dall'indice (colonne, tabelle, e la tabella delle identita' se costruita), con 16 byte di
   * intestazione per array: il budget di memoria del §1.2 (circa 80 B per elemento).
   */
  fun estimatedBytes(includeIdTable: Boolean = true): Long {
    fun a(count: Int, unit: Int) = 16L + count.toLong() * unit
    var total = a(size, 8) * 3 + a(size, 4) * 4 + a(size, 1) * 2 + a(size, 2) * 2 +
      a(size + 1, 4) + a(thumbBytes.size, 1) + a(size, 8) * 2 + a(size, 1) * 2 +
      a(groupIndex.size, 4) + a(groupMsb.size, 8) * 2
    total += (volumes.sumOf { 40L + it.length } + mimes.sumOf { 40L + it.length })
    if (includeIdTable) total += ids.bytes
    return total
  }

  /** Quante celle hanno il bit [flag]: per i conteggi della Libreria e i test. */
  fun countFlag(flag: Int): Int {
    var n = 0
    for (i in 0 until size) if (flags[i] and flag == flag) n++
    return n
  }

  private class ThumbSlot(val index: Int, val hash: ThumbHash?)

  internal companion object {
    const val THUMB_CACHE = 2048

    /** Il valore di `refVolume` che marca un riferimento al NAS: i volumi del telefono usano 1..254. */
    const val NAS_REF: Int = 255

    /** Quanti volumi del telefono possono avere un indice (255 e' del NAS). */
    const val MAX_VOLUMES: Int = 254

    private const val NAS_KEY_SALT: Long = 0x4E41535F4B455931L

    val KINDS: List<MediaKind> = MediaKind.entries
    val SOURCES: List<MediaSource> = MediaSource.entries

    val EMPTY: ColumnarTimelineSnapshot = TimelineIndexBuilder(0).build(seq = 0)
  }
}
