package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.database.TextMatchCounts
import dev.pampa.mycloudgallery.core.database.VocabularyWord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import kotlin.math.sqrt

// Il banco di prova dell'indice locale (P6): fake fedeli delle porte di P1 (stessa semantica di `AiIndexCatalog`: ricetta piu' bassa
// non sostituisce, termini come alternative letterali, frasi fra parole intere) e costruttori di corpi `vec` e `ocr`.

/** Una chiave di contenuto deterministica per un intero (64 cifre esadecimali). */
internal fun key(i: Long): ContentKey = ContentKey("%064x".format(i))

internal fun key(i: Int): ContentKey = key(i.toLong())

internal fun normalized(v: FloatArray): FloatArray {
  val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
  return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
}

/** Un corpo `vec` dai vettori (normalizzati e passati in float16): uno per un'immagine, piu' d'uno = fotogrammi di un video. */
internal fun vecBody(vararg vectors: FloatArray, recipe: Int = 1, space: SpaceCode = SpaceCode.CURRENT): VecBody {
  val frames = vectors.size > 1
  return VecBody(
    space, recipe, if (frames) VecSource.FRAMES else VecSource.ANALYSIS,
    vectors.mapIndexed { i, v -> VecItem(if (frames) i * 1_000 else null, Float16.encode(normalized(v))) },
  )
}

/** Un corpo `ocr` da un testo (una riga per "\n"). */
internal fun ocrBody(text: String, recipe: Int = 1): OcrBody {
  val lines = text.split('\n').map { it.trim().split(Regex("\\s+")).filter { w -> w.isNotEmpty() } }.filter { it.isNotEmpty() }.map { words ->
    OcrLine(ShortBox(0, 0, 100, 100), 0, words.indices.map { WordSpan(minOf(it * 10, 65_000), minOf(it * 10 + 5, 65_000)) }, words.joinToString(" "))
  }
  return OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe, OcrSource.ANALYSIS, lines)
}

/** `ai_text` in memoria, con la semantica di `AiIndexCatalog` (P1). */
internal class InMemoryTextRows : TextRowStore {
  val rows: MutableMap<ContentKey, AiTextRecord> = HashMap()
  var matchCalls: Int = 0
    private set

  override suspend fun put(record: AiTextRecord): Boolean {
    val old = rows[record.contentKey]
    if (old != null && old.recipe > record.recipe) return false
    rows[record.contentKey] = record
    return true
  }

  override suspend fun replace(records: List<AiTextRecord>) {
    records.forEach { rows[it.contentKey] = it }
  }

  override suspend fun remove(keys: Collection<ContentKey>) {
    keys.forEach { rows.remove(it) }
  }

  override suspend fun matches(terms: List<List<String>>): TextMatchCounts {
    matchCalls++
    val hits = HashMap<ContentKey, Int>()
    for ((key, row) in rows) {
      val words = row.text.split(' ').toSet()
      val count = terms.count { alternatives ->
        alternatives.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.any { alt ->
          if (' ' in alt) " $alt " in " ${row.text} " else alt in words
        }
      }
      if (count > 0) hits[key] = count
    }
    return TextMatchCounts(terms.size, hits)
  }

  override suspend fun wordCounts(words: Collection<String>): Map<String, Int> =
    words.associateWith { w -> rows.values.count { w in it.text.split(' ') } }.filterValues { it > 0 }

  override suspend fun vocabularyPage(after: String, limit: Int): List<VocabularyWord> {
    val counts = HashMap<String, Int>()
    rows.values.forEach { r -> r.text.split(' ').filter { it.isNotEmpty() }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts.keys.filter { it > after }.sorted().take(limit).map { VocabularyWord(it, counts.getValue(it)) }
  }
}

/** Le chiavi delle celle in memoria (P1): pagine per `seq`, celle per id. Il test le cambia a piacere (anche "male", per provare). */
internal class ListCellKeys(cells: List<CellContent> = emptyList()) : CellKeySource {
  val cells: MutableList<CellContent> = cells.toMutableList()
  var pages: Int = 0
    private set

  override suspend fun page(afterSeq: Long, limit: Int): CellContentPage {
    pages++
    val after = cells.filter { it.seq > afterSeq }.sortedBy { it.seq }
    val page = after.take(limit)
    return CellContentPage(page, if (after.size > limit) page.last().seq else null)
  }

  override suspend fun cellsOf(ids: Collection<MediaId>): List<CellContent> {
    val set = ids.toSet()
    return cells.filter { it.mediaId in set }
  }

  fun replace(cell: CellContent) {
    cells.removeAll { it.mediaId == cell.mediaId }
    cells += cell
  }
}

/** La riparazione registrata: chi ha chiesto di riavere che cosa. */
internal class RecordingRepair : IndexRepair {
  val keys: MutableList<ContentKey> = ArrayList()
  var all: Int = 0
    private set

  override suspend fun redeliver(keys: Collection<ContentKey>) {
    this.keys += keys
  }

  override suspend fun redeliverAll() {
    all++
  }
}

/** Vettori sintetici veloci e deterministici (xorshift): rumore uniforme in [-0,5, 0,5) per componente. */
internal object FastNoise {
  fun fill(seed: Long, out: FloatArray, scale: Float = 1f) {
    var x = seed * -0x61c8864680b583ebL + 0x2545F4914F6CDD1DL
    var i = 0
    while (i < out.size) {
      x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
      out[i] += (((x ushr 40).toInt() and 0xffffff) / 16_777_216f - 0.5f) * scale
      i++
    }
  }
}
