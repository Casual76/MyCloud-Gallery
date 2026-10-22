package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.compute.AiLocalBodies
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

// L'INDICE LOCALE del giro: il contratto che `LocalAiIndex` di P6 (core-search, che core-ai non vede) deve rispettare e che il banco mette
// alla prova con la morte nel mezzo di un salvataggio. Come in P6 ("Ordine delle scritture: file dei vettori, testo nel database, registro
// di conferma"): ogni salvataggio scrive un RECORD nel file (lunghezza, CRC32, corpo) e poi la RIGA; solo la riga dice "salvato".
// Il processo puo' morire prima del file, nel mezzo del record (CRC sbagliato, niente riga), dopo il record e prima della riga, o dopo la
// riga. Un processo nuovo rilegge il file e butta la coda rovinata (P6: "un record rovinato si toglie e si chiede di riaverlo").
//
// Nota per P11: nell'app questi due contratti li dara' `LocalAiIndex`; il banco di core-ai non puo' collegarlo (core-ai non vede
// core-search): la stessa verifica (`AiChaosVerifier.rows`) va ripetuta sull'indice vero in un test di `:app`.

class ChaosLocalIndex(private val plan: AiRoundPlan, private val stats: AiRoundStats, private val sick: () -> Boolean) : AiResults, AiLocalBodies {

  class Row(val offset: Int, val length: Int, val crc: Long, val recipe: Int, val body: AiBody)

  /** Il file dei vettori e dei testi: record uno dietro l'altro. Sopravvive alla morte del processo (e' "disco"). */
  private var file = ByteArray(0)

  /** Le righe (il database): solo cio' che ha una riga e' "salvato". */
  val rows: MutableMap<Pair<ContentKey, AiClass>, Row> = LinkedHashMap()

  /** Salvataggi finiti (anche di una vita prima): un secondo salvataggio della stessa chiave e classe e' un calcolo rifatto (invariante 4). */
  private val completed = HashSet<Pair<ContentKey, AiClass>>()
  val recomputed: MutableList<String> = mutableListOf()

  /** Il contatore dei salvataggi di tutto il giro: il piano dice a quale numero il processo muore. */
  var saveCounter = 0
    private set

  val fileBytes: Int get() = file.size

  override suspend fun saveVectors(key: ContentKey, body: VecBody) = save(key, AiClass.VEC, body)

  override suspend fun saveText(key: ContentKey, body: OcrBody) = save(key, AiClass.OCR, body)

  private fun save(key: ContentKey, cls: AiClass, body: AiBody) {
    val payload = AiSegments.encodeBody(body)
    saveCounter++
    val point = if (sick()) plan.deaths[saveCounter] else null
    val crc = CRC32().also { it.update(payload) }.value
    when (point) {
      SavePoint.BEFORE_FILE -> throw ProcessDeath()
      SavePoint.TORN_RECORD -> {
        // Il record scritto a meta': l'intestazione dice la lunghezza intera e il CRC del corpo intero, ma i byte sono la meta'.
        append(header(payload.size, crc) + payload.copyOf(payload.size / 2))
        throw ProcessDeath()
      }
      SavePoint.FILE_NO_ROW -> {
        append(header(payload.size, crc) + payload)
        throw ProcessDeath()
      }
      else -> Unit
    }
    val offset = file.size
    append(header(payload.size, crc) + payload)
    if ((key to cls) in completed) recomputed += "${cls.wire} ricalcolato e salvato due volte (ricetta ${body.recipe})"
    rows[key to cls] = Row(offset, payload.size, crc, body.recipe, body)
    completed += key to cls
    stats.savesDone++
    if (point == SavePoint.AFTER_ROW) throw ProcessDeath()
  }

  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> =
    AiClass.entries.filterTo(LinkedHashSet()) { cls -> rows[key to cls]?.let { it.recipe >= recipe && valid(it) } == true }

  override suspend fun vectors(key: ContentKey): VecBody? = (rows[key to AiClass.VEC]?.takeIf { valid(it) }?.body) as? VecBody

  override suspend fun text(key: ContentKey): OcrBody? = (rows[key to AiClass.OCR]?.takeIf { valid(it) }?.body) as? OcrBody

  fun hasRow(key: ContentKey, cls: AiClass): Boolean = rows[key to cls]?.let { valid(it) } == true

  /** La riga e il suo record: lunghezza e CRC del file uguali a quelli della riga. */
  fun valid(row: Row): Boolean {
    if (row.offset + HEADER + row.length > file.size) return false
    val buffer = ByteBuffer.wrap(file, row.offset, HEADER)
    if (buffer.int != row.length || (buffer.int.toLong() and 0xffffffffL) != row.crc) return false
    val crc = CRC32().also { it.update(file, row.offset + HEADER, row.length) }.value
    return crc == row.crc && file.copyOfRange(row.offset + HEADER, row.offset + HEADER + row.length).contentEquals(AiSegments.encodeBody(row.body))
  }

  /** Un processo nuovo: rilegge il file e toglie la coda rovinata (un record a meta' o con il CRC sbagliato). Dice quanti byte ha tolto. */
  fun recover(): Int {
    var pos = 0
    while (pos + HEADER <= file.size) {
      val buffer = ByteBuffer.wrap(file, pos, HEADER)
      val length = buffer.int
      val crc = buffer.int.toLong() and 0xffffffffL
      val end = pos + HEADER + length
      if (length < 0 || end > file.size) break
      if (CRC32().also { it.update(file, pos + HEADER, length) }.value != crc) break
      pos = end
    }
    val removed = file.size - pos
    if (removed > 0) file = file.copyOf(pos)
    return removed
  }

  private fun header(length: Int, crc: Long): ByteArray = ByteBuffer.allocate(HEADER).putInt(length).putInt(crc.toInt()).array()

  private fun append(bytes: ByteArray) {
    val out = ByteArrayOutputStream(file.size + bytes.size)
    out.write(file)
    out.write(bytes)
    file = out.toByteArray()
  }

  private companion object {
    const val HEADER = 8
  }
}
