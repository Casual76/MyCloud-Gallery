package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import kotlin.math.sqrt

/**
 * La FUSIONE dei dati AI fra dispositivi (D7; formato-nas.md §5.5-bis "Attestazione e fusione"), pura e deterministica: dispositivi
 * diversi con le stesse teste arrivano agli stessi vincitori senza parlarsi (E-I7).
 *
 * - Vale solo una voce **attestata**: la sua chiave di contenuto intera ha, nella stessa share, un record `meta` presente vincitore di
 *   F4-D6 ([AiAttestation]); senza, la voce resta in attesa (E-I21) e non ha vincitore.
 * - Fra piu' voci valide per (share, chiave, classe): **ricetta piu' alta**, poi **id del dispositivo minore**; `vec` e `ocr` si
 *   scelgono separatamente. A parita' anche di dispositivo (lo stesso dispositivo ha ripubblicato la stessa ricetta con un corpo
 *   diverso, in due segmenti), l'**impronta del corpo minore**: arbitraria ma uguale per tutti.
 * - Due vettori della stessa ricetta di dispositivi diversi che non concordano (coseno < [DISAGREE_BELOW]) si contano
 *   (`AI_VECTOR_DISAGREE`, E-A14): vince comunque la regola.
 */
object AiFusion {

  /** Sotto questo coseno due vettori della stessa ricetta "non concordano" (D7). */
  const val DISAGREE_BELOW: Float = 0.95f

  /** L'ordine D7: il primo vince. */
  val ORDER: Comparator<AiCandidate> =
    compareByDescending<AiCandidate> { it.recipe }.thenBy { it.deviceId }.thenBy { it.digest }.thenBy { it.segment }

  /** Il vincitore fra [candidates] della stessa (chiave, classe), se la chiave e' attestata. */
  fun winner(candidates: Collection<AiCandidate>, attested: Boolean): AiCandidate? =
    if (!attested) null else candidates.minWithOrNull(ORDER)

  /** I vincitori di ogni (chiave, classe) fra [candidates], con le sole chiavi in [attested]. */
  fun winners(candidates: Collection<AiCandidate>, attested: Set<ContentKey>): Map<AiKey, AiCandidate> =
    candidates.groupBy { it.key }.mapNotNull { (key, list) -> winner(list, key.contentKey in attested)?.let { key to it } }.toMap()

  /**
   * I candidati che vanno confrontati con il vincitore per il disaccordo: stessa ricetta, altro dispositivo. Vuoto quasi sempre (un
   * solo indicizzatore per contenuto): i corpi si leggono solo in quel caso.
   */
  fun rivals(winner: AiCandidate, candidates: Collection<AiCandidate>): List<AiCandidate> =
    candidates.filter { it.recipe == winner.recipe && it.deviceId != winner.deviceId }.distinctBy { it.deviceId to it.digest }

  /**
   * Vero se i due corpi `vec` non concordano: spazio diverso, numero di vettori diverso, o un coseno sotto [DISAGREE_BELOW] fra due
   * vettori nella stessa posizione (per un video: lo stesso fotogramma).
   */
  fun disagree(a: VecBody, b: VecBody): Boolean {
    if (a.space != b.space || a.vectors.size != b.vectors.size) return true
    return a.vectors.indices.any { i -> cosine(a.vectors[i].f16, b.vectors[i].f16) < DISAGREE_BELOW }
  }

  /** Il coseno fra due vettori float16 (calcolo in float32 sui valori esatti). */
  fun cosine(a: ShortArray, b: ShortArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      val x = Float16.toFloat(a[i]).toDouble()
      val y = Float16.toFloat(b[i]).toDouble()
      dot += x * y
      na += x * x
      nb += y * y
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
  }
}
