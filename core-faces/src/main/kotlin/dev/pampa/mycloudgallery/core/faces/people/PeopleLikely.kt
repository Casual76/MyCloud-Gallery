package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.PhotoKey
import dev.pampa.mycloudgallery.core.faces.people.PeopleCore.Loaded
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * La fascia "forse" per persona (docs/fase8-progetto.md D27, §2.7): i contenuti con un volto che per quella persona sta **fra `T_SUGGEST` e
 * `T_JOIN`** (la somiglianza e' la media dei 3 coseni piu' alti con gli esemplari, come per l'assegnazione; i WEAK contro `T_JOIN_WEAK`).
 * Non e' un'assegnazione: la ricerca la mostra solo con "Mostra anche risultati meno probabili" e gli album smart come "Suggerite".
 *
 * Non entra un volto che la persona ha gia' (e' "sicuro"), che l'utente ha ignorato o escluso da lei, che appartiene a una persona dimenticata,
 * o che sta in una foto dove la persona ha gia' un altro volto (due volti della stessa foto non sono mai la stessa persona, E-F15). Gli
 * esemplari sono quelli dell'istantanea (o, per una persona nata da "Dividi" che l'istantanea non conosce ancora, i suoi volti confermati).
 * Calcolata solo su richiesta e solo per le persone chieste. Mai valori nei log (D37).
 */
internal object PeopleLikely {

  /** Al piu' tanti esemplari confermati per una persona che l'istantanea non conosce (come `GroupingParams.MAX_EXEMPLARS`, ma piu' stretti). */
  private const val CONFIRMED_EXEMPLARS = 8

  suspend fun contentFor(l: Loaded, roots: List<PersonId>, params: GroupingParams = GroupingParams.DEFAULT): Map<PersonId, Set<ContentKey>> {
    if (roots.isEmpty()) return emptyMap()
    val faces = l.archive.groupFaces()
    val lens = l.lens
    val byRef = faces.associateBy { it.ref }
    val byPlace = faces.groupBy { it.locator.contentKey }
    fun find(locator: FaceLocator): GroupFace? = byPlace[locator.contentKey].orEmpty().firstOrNull { it.locator.matches(locator) }

    val out = LinkedHashMap<PersonId, Set<ContentKey>>()
    for (root in roots) {
      val fromState = l.grouping.persons.values.filter { lens.resolve(it.id) == root }.flatMap { it.exemplars }.distinct().mapNotNull { byRef[it] }
      val confirmed = lens.facesOf(root).filter { lens.isConfirmed(it) }.take(CONFIRMED_EXEMPLARS).mapNotNull { find(it) }
      val exemplars = (fromState + confirmed).distinctBy { it.ref }
      if (exemplars.isEmpty()) {
        out[root] = emptySet()
        continue
      }
      val photos = lens.facesOf(root).mapTo(HashSet()) { PhotoKey(it.contentKey, it.frame) }
      val found = LinkedHashSet<ContentKey>()
      for (face in faces) {
        val place = face.locator
        if (lens.ownerOf(place) == root || lens.isIgnored(place) || lens.belongsToForgotten(place)) continue
        if (lens.rejectedFor(place).any { lens.resolve(it) == root }) continue
        if (face.photo in photos) continue
        val similarity = topMean(face, exemplars, GroupingParams.TOP_EXEMPLARS)
        val join = if (face.tier == FaceTier.WEAK) params.tJoinWeak else params.tJoin
        if (similarity >= params.tSuggest && similarity < join) found += place.contentKey
      }
      out[root] = found
    }
    return out
  }

  /** La media dei [top] coseni piu' alti fra [face] e gli [exemplars] (quanti ce ne sono, se meno di [top]). */
  private fun topMean(face: GroupFace, exemplars: List<GroupFace>, top: Int): Float {
    val best = FloatArray(top) { Float.NEGATIVE_INFINITY }
    var smallest = 0
    var used = 0
    for (e in exemplars) {
      val cosine = face.cosine(e)
      if (cosine <= best[smallest]) continue
      best[smallest] = cosine
      used = minOf(top, used + 1)
      smallest = best.indices.minBy { best[it] }
    }
    if (used == 0) return Float.NEGATIVE_INFINITY
    return best.filter { it != Float.NEGATIVE_INFINITY }.sum() / used
  }
}
