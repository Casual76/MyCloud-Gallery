package dev.pampa.mycloudgallery.debugtools.eval

import java.util.Locale

// LA SOGLIA DI "DOCUMENTI" (D34): `CellFlags.DOCUMENT` e' acceso se il testo letto ha almeno 6 righe o 200 caratteri **oppure** il vettore e'
// vicino a una delle frasi prototipo oltre una soglia, da calibrare. Stesso file in core-search (test) e in :app (debug).
// Il campione e' etichettato dall'utente: per ogni foto mostrata "e' un documento" si' o no (sotto "Documenti" dello strumento di debug).

/** Le frasi prototipo (D34), nello spazio del testo: il punteggio di una cella e' il coseno piu' alto fra il suo vettore e queste. */
object DocumentPrototypes {
  val PHRASES: List<String> = listOf("un documento", "uno scontrino", "una ricevuta", "un foglio con del testo")

  /** Le righe e i caratteri di testo letto da cui una cella e' un documento senza guardare il vettore. */
  const val TEXT_LINES: Int = 6
  const val TEXT_CHARS: Int = 200
}

/** Una foto del campione: il suo punteggio sui prototipi (null: nessun vettore), il testo letto e l'etichetta dell'utente. */
class DocSample(val key: String, val score: Float?, val textLines: Int, val textChars: Int, val isDocument: Boolean) {
  val textRule: Boolean get() = textLines >= DocumentPrototypes.TEXT_LINES || textChars >= DocumentPrototypes.TEXT_CHARS

  override fun toString(): String = "DocSample(documento=$isDocument)"
}

/** La soglia scelta e cosa vale sul campione. */
class DocumentChoice(
  /** La soglia sul coseno dei prototipi; `Float.POSITIVE_INFINITY` = la sola regola del testo basta (nessuna soglia raggiunge l'obiettivo). */
  val threshold: Float,
  val truePositives: Int,
  val falsePositives: Int,
  val falseNegatives: Int,
  val samples: Int,
  val documents: Int,
  /** Quanti documenti il solo testo ha gia' riconosciuto, senza i prototipi. */
  val byText: Int,
  val meetsPrecision: Boolean,
) {
  val precision: Double get() = if (truePositives + falsePositives == 0) Double.NaN else truePositives.toDouble() / (truePositives + falsePositives)
  val recall: Double get() = if (truePositives + falseNegatives == 0) Double.NaN else truePositives.toDouble() / (truePositives + falseNegatives)

  fun toNumbersText(): String = String.format(
    Locale.ROOT, "soglia %.3f | campione %d (documenti %d, gia' dal testo %d) | precisione %s | recall %s | FP %d FN %d",
    threshold, samples, documents, byText, EvalReport.pct(precision), EvalReport.pct(recall), falsePositives, falseNegatives,
  )
}

object EvalDocuments {

  /** Il filtro e' mostrato come un elenco di foto: meglio qualche documento perso che molte foto qualunque dentro (come i "sicuri"). */
  const val TARGET_PRECISION: Double = 0.90

  /** Come si decide per una cella con la soglia [threshold]. */
  fun isDocument(sample: DocSample, threshold: Float): Boolean = sample.textRule || (sample.score != null && sample.score >= threshold)

  fun calibrate(samples: List<DocSample>, targetPrecision: Double = TARGET_PRECISION): DocumentChoice {
    require(samples.any { it.isDocument } && samples.any { !it.isDocument }) { "Servono documenti e non documenti nel campione." }
    val candidates = samples.mapNotNull { it.score }.distinct().sorted()
    // Tutte le soglie fra due punteggi vicini danno gli stessi esiti: si prova ognuno dei punteggi e "nessuna soglia".
    val points = (candidates + Float.POSITIVE_INFINITY).map { choice(samples, it, targetPrecision) }
    val ok = points.filter { it.meetsPrecision }
    // Fra quelle che raggiungono la precisione, la piu' ampia (recall piu' alto); a parita' la soglia piu' alta (meno rischio).
    val best = if (ok.isNotEmpty()) {
      ok.maxWith(compareBy<DocumentChoice> { it.recall.let { r -> if (r.isNaN()) 0.0 else r } }.thenBy { it.threshold })
    } else {
      points.maxWith(compareBy<DocumentChoice> { it.precision.let { p -> if (p.isNaN()) 0.0 else p } }.thenBy { it.recall.let { r -> if (r.isNaN()) 0.0 else r } })
    }
    return best
  }

  fun choice(samples: List<DocSample>, threshold: Float, targetPrecision: Double = TARGET_PRECISION): DocumentChoice {
    var tp = 0
    var fp = 0
    var fn = 0
    for (s in samples) {
      val predicted = isDocument(s, threshold)
      when {
        predicted && s.isDocument -> tp++
        predicted && !s.isDocument -> fp++
        !predicted && s.isDocument -> fn++
      }
    }
    val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
    return DocumentChoice(
      threshold = threshold,
      truePositives = tp,
      falsePositives = fp,
      falseNegatives = fn,
      samples = samples.size,
      documents = samples.count { it.isDocument },
      byText = samples.count { it.isDocument && it.textRule },
      meetsPrecision = precision >= targetPrecision,
    )
  }
}
