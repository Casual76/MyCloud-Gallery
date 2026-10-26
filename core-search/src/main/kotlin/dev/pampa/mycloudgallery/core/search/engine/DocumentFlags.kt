package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LA REGOLA DEI DOCUMENTI (D34; revisione A, CE-1): una cella e' un documento (`CellFlags.DOCUMENT`, il filtro "Documenti" della Libreria e
 * il tipo "documenti" di Cerca) se e' una **foto** che non e' uno screenshot (gli screenshot hanno il loro tipo) e
 * - il testo letto ha almeno [TEXT_LINES] righe o [TEXT_CHARS] caratteri, **oppure**
 * - il suo vettore e' vicino a una delle frasi prototipo ([PHRASES]) almeno quanto [VECTOR_THRESHOLD] (coseno).
 *
 * **Soglie provvisorie** (docs/decisioni.md, "Fase 6, correzioni dopo le revisioni: pacchetto A"): la misura vera e' quella di P9 sul set
 * privato, che non c'e' ancora. Sul set OCR sintetico di P9 (`tools/fixtures/generate-ocr.ps1`, 216 immagini) i numeri di D34 (6 righe o
 * 200 caratteri) prendono tutti i cartelli (precisione 0,50 sulle foto); 9 righe o 250 caratteri prendono tutti gli scontrini e nessun
 * cartello. Il vettore non si misura su quel set (testo senza modelli): finche' P9 non lo calibra sul telefono la sua soglia e' "nessuna"
 * ([Float.POSITIVE_INFINITY]) e conta solo il testo. **Ricalibrazione**: lo strumento di debug "Valutazione della ricerca → Documenti"
 * etichetta un campione e `EvalDocuments.calibrate` da' la soglia (precisione almeno 0,90); i valori nuovi vanno qui e in decisioni.md.
 */
object DocumentRule {
  /** Le frasi prototipo (D34), nello spazio del testo. */
  val PHRASES: List<String> = listOf("un documento", "uno scontrino", "una ricevuta", "un foglio con del testo")

  /** Provvisorie (set OCR sintetico di P9): le righe e i caratteri di testo letto da cui una foto e' un documento. */
  const val TEXT_LINES: Int = 9
  const val TEXT_CHARS: Int = 250

  /** Provvisoria: nessuna soglia sul vettore finche' P9 non la misura sul set privato. */
  const val VECTOR_THRESHOLD: Float = Float.POSITIVE_INFINITY

  fun byText(lines: Int, chars: Int): Boolean = lines >= TEXT_LINES || chars >= TEXT_CHARS

  /** Solo le foto che non sono screenshot possono essere documenti. */
  fun eligible(kind: MediaKind, source: MediaSource): Boolean = kind != MediaKind.VIDEO && source != MediaSource.SCREENSHOT
}

/** Dove si scrive il bit dei documenti (:app: `MediaCatalog.setDerivedFlags`, a lotti di 500). Quanti media sono cambiati. */
fun interface DocumentFlagSink {
  suspend fun set(ids: List<MediaId>, on: Boolean): Int
}

/**
 * Il calcolo del bit `CellFlags.DOCUMENT` (CE-1) sopra l'istantanea della Libreria (senza nascosti ne' membri di gruppo) e l'indice locale:
 * per ogni cella la regola [DocumentRule] sulle chiavi della cella (la versione corrente prima, poi le precedenti: E-A10). Si scrive solo
 * cio' che cambia, accendendo e spegnendo (un testo ritirato spegne il bit). Il bit e' locale: ogni dispositivo lo ricava dagli stessi dati.
 *
 * [prototypes] sono i vettori delle frasi prototipo nello spazio delle immagini (null = non disponibili ora): si chiedono solo se la soglia
 * sul vettore ([vectorThreshold]) e' finita.
 */
class DocumentFlags(
  private val data: SearchData,
  private val keys: CellKeys,
  private val index: LocalAiIndex,
  private val sink: DocumentFlagSink,
  private val prototypes: suspend () -> List<FloatArray>? = { null },
  private val vectorThreshold: Float = DocumentRule.VECTOR_THRESHOLD,
) {
  private val mutex = Mutex()

  /** Un giro su tutte le celle: quante sono cambiate. Uno alla volta. */
  suspend fun refresh(): Int = mutex.withLock {
    val snapshot = data.snapshot.value ?: return@withLock 0
    keys.sync()
    val protos = if (vectorThreshold.isFinite()) prototypes()?.map { unit(it) } else null
    val on = ArrayList<MediaId>()
    val off = ArrayList<MediaId>()
    for (i in 0 until snapshot.size) {
      val id = snapshot.idAt(i)
      // Una cella non ancora nelle chiavi resta com'e': mai spegnere per un dato che manca.
      val cell = keys[id] ?: continue
      val document = DocumentRule.eligible(snapshot.kindAt(i), snapshot.sourceAt(i)) && isDocument(cell.own, protos)
      val has = snapshot.hasFlag(i, CellFlags.DOCUMENT)
      if (document && !has) on += id else if (!document && has) off += id
    }
    var changed = 0
    if (on.isNotEmpty()) changed += sink.set(on, true)
    if (off.isNotEmpty()) changed += sink.set(off, false)
    changed
  }

  private fun isDocument(own: List<ContentKey>, protos: List<FloatArray>?): Boolean {
    for (key in own) {
      val stats = index.textStats(key) ?: continue
      if (DocumentRule.byText(stats.first, stats.second)) return true
      break
    }
    if (protos.isNullOrEmpty()) return false
    for (key in own) {
      val vectors = index.vectors(key)
      if (vectors.isEmpty()) continue
      val best = vectors.maxOf { v -> protos.maxOf { p -> cosine(v, p) } }
      return best >= vectorThreshold
    }
    return false
  }

  private fun unit(v: FloatArray): FloatArray {
    var norm = 0.0
    for (x in v) norm += x * x
    val n = sqrt(norm).toFloat()
    return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
  }

  private fun cosine(a: FloatArray, unitB: FloatArray): Float {
    if (a.size != unitB.size) return -1f
    var dot = 0.0
    var norm = 0.0
    for (i in a.indices) {
      dot += a[i] * unitB[i]
      norm += a[i] * a[i]
    }
    return if (norm == 0.0) -1f else (dot / sqrt(norm)).toFloat()
  }
}
