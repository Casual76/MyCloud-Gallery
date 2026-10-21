package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.database.dao.CellVersionRow
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.security.MessageDigest

/**
 * Da righe di versioni a [CellContent]: raggruppa per media (le righe arrivano in ordine di `rowid`, le versioni di un media di
 * seguito), mette la versione corrente per prima e poi le precedenti dalla piu' recente, e aggiunge i membri del gruppo.
 */
internal object CellContentAssembler {

  fun assemble(cells: List<CellVersionRow>, members: List<CellVersionRow>): List<CellContent> {
    val byMember = members.groupBy { it.groupId }
    val result = ArrayList<CellContent>()
    var i = 0
    while (i < cells.size) {
      var j = i + 1
      while (j < cells.size && cells[j].mediaId == cells[i].mediaId) j++
      val first = cells[i]
      val own = keysOf(cells.subList(i, j))
      val others = first.groupId?.let { group ->
        byMember[group].orEmpty().filter { it.mediaId != first.mediaId }.groupBy { it.mediaId }.map { (id, rows) ->
          MemberContent(MediaId(id), keysOf(rows))
        }.filter { it.keys.isNotEmpty() }
      }.orEmpty()
      result += CellContent(first.seq, MediaId(first.mediaId), own, others)
      i = j
    }
    return result
  }

  /** Le chiavi delle versioni di un media: la corrente per prima, poi le altre dalla piu' recente. Una impronta non valida si salta. */
  private fun keysOf(rows: List<CellVersionRow>): List<ContentKey> {
    // Il caso di quasi tutte le celle: una versione sola, nessun ordinamento e nessun doppione.
    if (rows.size == 1) return listOfNotNull(FastContentKey.of(rows[0].quickHash, rows[0].size))
    val ordered = rows.sortedWith(compareByDescending<CellVersionRow> { it.rank }.thenByDescending { it.quickHash })
    return ordered.mapNotNull { FastContentKey.of(it.quickHash, it.size) }.distinct()
  }
}

/**
 * La chiave di contenuto (`ContentKey.of(QuickHash, size)`: SHA-256 di `"mcg-ck\0" ‖ qh1 ‖ dimensione`) senza il costo di
 * `ContentKey.of` (che formatta ogni byte con `String.format`: su 100 mila media e' piu' del tetto di 300 ms di `cellContentKeys`).
 * Stesso risultato, **provato** da `CellContentKeysTest.laChiaveVelocePerAnalogiaDiContentKeyOf`: se la definizione della chiave cambia
 * in core-model quel test fallisce.
 */
internal object FastContentKey {

  private val DOMAIN: ByteArray = "mcg-ck".toByteArray(Charsets.US_ASCII) + 0
  private val DIGEST = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
  private val HEX = "0123456789abcdef".toCharArray()

  /** La chiave, o null se [quickHashHex] non e' un'impronta rapida valida (64 cifre esadecimali minuscole) o [size] e' negativa. */
  fun of(quickHashHex: String, size: Long): ContentKey? {
    if (size < 0 || quickHashHex.length != 64) return null
    val digest = DIGEST.get()
    digest.reset()
    digest.update(DOMAIN)
    val quick = ByteArray(32)
    for (i in 0 until 32) {
      val hi = nibble(quickHashHex[2 * i])
      val lo = nibble(quickHashHex[2 * i + 1])
      if (hi < 0 || lo < 0) return null
      quick[i] = ((hi shl 4) or lo).toByte()
    }
    digest.update(quick)
    digest.update(ByteArray(8) { i -> (size ushr (56 - 8 * i)).toByte() })
    val out = digest.digest()
    val chars = CharArray(64)
    for (i in 0 until 32) {
      val b = out[i].toInt() and 0xff
      chars[2 * i] = HEX[b ushr 4]
      chars[2 * i + 1] = HEX[b and 0x0f]
    }
    return ContentKey(String(chars))
  }

  private fun nibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
  }
}
