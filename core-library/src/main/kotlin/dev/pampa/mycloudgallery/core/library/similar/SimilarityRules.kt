package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.abs
import kotlin.math.max

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, "Quando due media sono copie simili"): le condizioni 1-4 come regole pure. Le condizioni 1,
// 2 (secondo di scatto) e 5 le applica gia' il catalogo (`MediaCatalog.similarRuns`: stesso tipo, stesso secondo letto dai metadati,
// fuori da raffiche, RAW+JPEG e `PROBABLE_DUPLICATE`); qui ci sono i sottosecondi (2), la proporzione e la durata (3) e la firma visiva
// (4). Le condizioni 6 (nessuna ambiguita') e 7 (nessuna separazione) sono del pianificatore.

/** Il giudizio sul confronto di due firme. */
enum class VisualVerdict {
  /** Lo stesso scatto: sotto tutte le soglie e con abbastanza struttura per dirlo. */
  SAME,

  /** Scatti o inquadrature diverse (o la stessa immagine con esposizione, colore o geometria cambiati). */
  DIFFERENT,

  /**
   * Sotto le soglie ma senza struttura (cielo vuoto, muro, buio): qualunque altro scatto vuoto somiglierebbe uguale, quindi non prova
   * niente. "Nel dubbio non si unisce": vale come [DIFFERENT] per il gruppo.
   */
  UNDECIDABLE,
}

/**
 * Le soglie del §2.12, tarate con le immagini di prova (`SignatureTableTest`: i margini misurati sono nel report del pacchetto).
 *
 * Le soglie sono in unita' di OKLab (vedi [SignatureDistance]): [MEAN_MAX] sulla media delle celle, [PEAK_MAX] sul 98esimo percentile,
 * [TAIL_MAX] sul 99,5esimo. Stanno nel mezzo fra il caso peggiore dei "devono unirsi" (una miniatura S del NAS in WebP) e il migliore
 * dei "non devono unirsi" (un filtro di colore freddo, un ritaglio dell'1,5%, una raffica con il soggetto spostato dell'1%); i margini
 * misurati sono nel report del pacchetto e in `SignatureTableTest`.
 */
object SimilarityRules {

  /** Condizione 3: scarto massimo della proporzione dopo l'orientamento (1%). */
  const val ASPECT_TOLERANCE: Double = 0.01

  /** Condizione 3: scarto massimo della durata dei video (0,5 s). */
  const val DURATION_TOLERANCE_MS: Long = 500L

  /** Soglia sulla distanza media delle celle (OKLab). */
  const val MEAN_MAX: Float = 0.0075f

  /** Soglia sul 98esimo percentile delle distanze delle celle (OKLab). */
  const val PEAK_MAX: Float = 0.020f

  /** Soglia sul 99,5esimo percentile (cambiamenti piccoli e locali: un soggetto che si e' spostato dello 0,5%). */
  const val TAIL_MAX: Float = 0.028f

  /** Sotto questa struttura (deviazione standard delle luminosita' delle celle) una firma non prova niente. */
  const val MIN_STRUCTURE: Float = 0.035f

  /**
   * Candidati dello stesso secondo e dello stesso tipo oltre i quali non si confronta (e non si calcola niente): nessun vero insieme di
   * copie dello stesso scatto e' cosi' grande, e il confronto a coppie crescerebbe col quadrato. Sono ambigui per costruzione.
   */
  const val MAX_RUN: Int = 24

  /** Condizione 3: stessa proporzione (scarto massimo [ASPECT_TOLERANCE]). `aspectMilli` e' la larghezza su altezza per mille. */
  fun aspectCompatible(aMilli: Int, bMilli: Int): Boolean {
    if (aMilli <= 0 || bMilli <= 0) return false
    return abs(aMilli - bMilli).toDouble() <= ASPECT_TOLERANCE * max(aMilli, bMilli)
  }

  /** Condizione 3 per i video: durate note e a meno di [DURATION_TOLERANCE_MS]. Una durata ignota non prova niente. */
  fun durationCompatible(aMs: Long?, bMs: Long?): Boolean = aMs != null && bMs != null && abs(aMs - bMs) <= DURATION_TOLERANCE_MS

  /**
   * Condizione 2, i sottosecondi: se **entrambi** hanno i millisecondi dell'EXIF devono coincidere (le raffiche li hanno diversi). I
   * millisecondi sono la parte sotto il secondo di `sort_time`; zero vale "non scritti" (un `SubSecTimeOriginal` a 000 e' raro e un
   * file senza il tag e' comune: nel dubbio si lascia decidere alla firma).
   */
  fun subSecondCompatible(aTakenAtMillis: Long, bTakenAtMillis: Long): Boolean {
    val a = Math.floorMod(aTakenAtMillis, 1000L)
    val b = Math.floorMod(bTakenAtMillis, 1000L)
    return a == 0L || b == 0L || a == b
  }

  /** Condizione 4: le firme [a] e [b]. Firme di algoritmo o forma diversi: [VisualVerdict.UNDECIDABLE] (si ricalcolano). */
  fun visual(a: VisualSignature, b: VisualSignature): VisualVerdict {
    val d = SignatureComparison.distance(a, b) ?: return VisualVerdict.UNDECIDABLE
    return verdict(d)
  }

  /** Il giudizio su una distanza gia' misurata. */
  fun verdict(d: SignatureDistance): VisualVerdict = when {
    d.mean > MEAN_MAX || d.peak > PEAK_MAX || d.tail > TAIL_MAX -> VisualVerdict.DIFFERENT
    d.structure < MIN_STRUCTURE -> VisualVerdict.UNDECIDABLE
    else -> VisualVerdict.SAME
  }
}
