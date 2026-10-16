package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.database.SimilarRun
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, condizioni 3-7 e "Regole del gruppo"): dal gruppo di candidati di un secondo ai gruppi voluti.
// Parte pura: nessun catalogo, nessuna decodifica. Prende le firme gia' calcolate e le separazioni dell'utente e dice chi sta con chi.

/** Una coppia di media che l'utente ha separato ("Separa", E-L13): vale in entrambe le direzioni. */
class MediaPair(a: MediaId, b: MediaId) {
  val low: MediaId = if (a.value <= b.value) a else b
  val high: MediaId = if (a.value <= b.value) b else a

  override fun equals(other: Any?): Boolean = other is MediaPair && other.low == low && other.high == high

  override fun hashCode(): Int = low.hashCode() * 31 + high.hashCode()

  override fun toString(): String = "MediaPair"
}

/** Le coppie separate dall'utente, per interrogarle in fretta. */
class SeparatedPairs(pairs: Collection<MediaPair> = emptyList()) {
  private val set: Set<MediaPair> = pairs.toSet()

  val size: Int get() = set.size

  fun contains(a: MediaId, b: MediaId): Boolean = a != b && MediaPair(a, b) in set

  companion object {
    val NONE: SeparatedPairs = SeparatedPairs()
  }
}

/**
 * Il piano per un gruppo di candidati dello stesso secondo e dello stesso tipo.
 *
 * @property groups i gruppi voluti, ognuno di almeno due media (in ordine d'identita'): ogni media e' in un solo gruppo voluto.
 * @property kept i media che restano nel gruppo in cui stanno (nessuna azione): un gruppo coerente, anche se qui ne e' presente un
 *   membro solo perche' gli altri non sono raggiungibili adesso.
 * @property leaving i media che stanno in un gruppo di copie simili ma non devono stare in nessun gruppo voluto: escono (le copie non
 *   sono piu' copie, un'altra copia ha reso ambiguo il gruppo, l'utente li ha separati).
 * @property ambiguous i media esclusi perche' somigliano a due media distinti non raggruppati fra loro (condizione 6).
 * @property deferred i media non decisi adesso: senza firma, o in un gruppo di cui qualche membro non ha ancora la firma (il gruppo non
 *   si tocca), o in un secondo con troppi candidati.
 * @property compared le coppie confrontate (per i numeri di Diagnostica).
 */
class RunPlan(
  val groups: List<List<MediaId>>,
  val kept: List<MediaId>,
  val leaving: List<MediaId>,
  val ambiguous: List<MediaId>,
  val deferred: List<MediaId>,
  val compared: Int,
) {
  override fun toString(): String =
    "RunPlan(groups=${groups.size}, kept=${kept.size}, leaving=${leaving.size}, ambiguous=${ambiguous.size}, deferred=${deferred.size})"
}

object SimilarGroupPlanner {

  /**
   * Il piano per [run].
   *
   * 1. **Adiacenza.** Due candidati sono "simili" se la proporzione e la durata sono compatibili (condizione 3), i sottosecondi
   *    coincidono (2), la firma e' la stessa entro le soglie (4) e l'utente non li ha separati (7).
   * 2. **I gruppi gia' fatti restano** se sono ancora coerenti (ogni coppia di membri e' simile); altrimenti si sciolgono e i loro
   *    membri tornano liberi. Un gruppo con un membro senza firma non si tocca (si riprova al giro dopo).
   * 3. **Ambiguita'** (6): un media libero che somiglia a due "unita'" (un media libero, o un gruppo) che non sono simili fra loro
   *    non entra in nessun gruppo. I gruppi gia' fatti non sono mai ambigui: un arrivo nuovo non li disfa.
   * 4. **Unione.** Le unita' rimaste si uniscono solo se sono *tutte* simili fra loro (un gruppo e due media nuovi non simili fra
   *    loro: nessuno dei due entra).
   */
  fun plan(run: SimilarRun, signatures: Map<VersionId, VisualSignature>, apart: SeparatedPairs): RunPlan {
    val all = run.candidates
    if (all.size > SimilarityRules.MAX_RUN) {
      // Troppi: nessun insieme vero di copie e' cosi' grande, e il confronto cresce col quadrato. Non si decide niente.
      return RunPlan(emptyList(), emptyList(), emptyList(), all.map { it.mediaId }, emptyList(), compared = 0)
    }
    val signed = all.filter { c -> signatures[c.versionId]?.algorithm == VisualSignatures.ALGORITHM }
    val unsigned = all.filter { c -> signatures[c.versionId]?.algorithm != VisualSignatures.ALGORITHM }
    val frozen = unsigned.mapNotNull { it.group }.toSet()
    val nodes = signed.filter { it.group == null || it.group !in frozen }
    val frozenMembers = signed.filter { it.group != null && it.group in frozen }
    val n = nodes.size

    // 1. Adiacenza.
    val adjacent = Array(n) { BooleanArray(n) }
    var compared = 0
    for (i in 0 until n) {
      for (j in i + 1 until n) {
        val a = nodes[i]
        val b = nodes[j]
        if (!compatible(a, b) || apart.contains(a.mediaId, b.mediaId)) continue
        compared++
        val same = SimilarityRules.visual(signatures.getValue(a.versionId), signatures.getValue(b.versionId)) == VisualVerdict.SAME
        adjacent[i][j] = same
        adjacent[j][i] = same
      }
    }

    // 2. I gruppi gia' fatti: restano se coerenti.
    val units = ArrayList<Cluster>()
    val inGroupUnit = BooleanArray(n)
    nodes.indices.filter { nodes[it].group != null }.groupBy { nodes[it].group }.values.forEach { members ->
      // Un gruppo con un solo membro presente (gli altri non sono raggiungibili adesso) non ha niente da contraddire e resta.
      if (members.all { a -> members.all { b -> a == b || adjacent[a][b] } }) {
        units += Cluster(members.toIntArray(), isGroup = true)
        members.forEach { inGroupUnit[it] = true }
      }
    }
    for (i in 0 until n) if (!inGroupUnit[i]) units += Cluster(intArrayOf(i), isGroup = false)

    fun full(u: Cluster, v: Cluster): Boolean = u.members.all { a -> v.members.all { b -> adjacent[a][b] } }
    fun resembles(u: Cluster, v: Cluster): Boolean = u.members.any { a -> v.members.any { b -> adjacent[a][b] } }

    // 3. Ambiguita': solo i media liberi.
    val ambiguous = LinkedHashSet<Int>()
    for (s in units) {
      if (s.isGroup) continue
      val neighbors = units.filter { it !== s && resembles(s, it) }
      var bad = false
      loop@ for (x in neighbors.indices) {
        for (y in x + 1 until neighbors.size) {
          if (!full(neighbors[x], neighbors[y])) {
            bad = true
            break@loop
          }
        }
      }
      if (bad) ambiguous += s.members.single()
    }
    val remaining = units.filter { u -> u.isGroup || u.members[0] !in ambiguous }

    // 4. Unione: le componenti connesse da "simili a tutti", solo se sono cricche.
    val seen = BooleanArray(remaining.size)
    val desired = ArrayList<IntArray>()
    val kept = ArrayList<Int>()
    for (start in remaining.indices) {
      if (seen[start]) continue
      val component = ArrayList<Int>()
      val queue = ArrayDeque<Int>()
      queue.add(start)
      seen[start] = true
      while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        component += u
        for (v in remaining.indices) if (!seen[v] && full(remaining[u], remaining[v])) {
          seen[v] = true
          queue.add(v)
        }
      }
      val clique = component.all { a -> component.all { b -> a == b || full(remaining[a], remaining[b]) } }
      if (clique && component.size >= 2) {
        // Piu' unita' tutte simili fra loro: un solo gruppo.
        desired += component.flatMap { remaining[it].members.toList() }.sorted().toIntArray()
      } else {
        // Un'unita' sola, oppure un gruppo con due arrivi non simili fra loro: i gruppi restano come sono, i media liberi restano fuori.
        for (cluster in component.map { remaining[it] }.filter { it.isGroup }) {
          if (cluster.members.size >= 2) desired += cluster.members else kept += cluster.members.toList()
        }
      }
    }

    val groups = desired.map { idx -> idx.map { nodes[it].mediaId }.sortedBy { it.value } }.sortedBy { it.first().value }
    val keptIds = kept.map { nodes[it].mediaId }
    val inDesired = groups.flatten().toSet() + keptIds
    val leaving = nodes.filter { it.group != null && it.mediaId !in inDesired }.map { it.mediaId }
    val ambiguousIds = ambiguous.map { nodes[it].mediaId }
    val deferred = unsigned.map { it.mediaId } + frozenMembers.map { it.mediaId }
    return RunPlan(groups, keptIds, leaving, ambiguousIds, deferred, compared)
  }

  /** Condizioni 3 (proporzione, durata) e 2 (sottosecondi) fra due candidati. */
  internal fun compatible(a: SimilarCandidate, b: SimilarCandidate): Boolean =
    SimilarityRules.aspectCompatible(a.aspectMilli, b.aspectMilli) &&
      (!a.isVideo || SimilarityRules.durationCompatible(a.durationMs, b.durationMs)) &&
      SimilarityRules.subSecondCompatible(a.takenAtUtcMillis, b.takenAtUtcMillis)

  /**
   * I candidati di [run] che meritano una firma: hanno almeno un compagno con proporzione, durata e sottosecondi compatibili e non
   * separato dall'utente. Gli altri non si firmano mai ("solo per i candidati").
   */
  fun needSignature(run: SimilarRun, apart: SeparatedPairs): List<SimilarCandidate> {
    if (run.candidates.size > SimilarityRules.MAX_RUN) return emptyList()
    return run.candidates.filter { a ->
      run.candidates.any { b -> b !== a && compatible(a, b) && !apart.contains(a.mediaId, b.mediaId) }
    }
  }

  /** Un'unita' del confronto: un gruppo gia' fatto o un media libero. */
  private class Cluster(val members: IntArray, val isGroup: Boolean)

  /** Il gruppo gia' fatto di un candidato, se c'e'. */
  fun groupOf(candidate: SimilarCandidate): MediaGroupId? = candidate.group
}
