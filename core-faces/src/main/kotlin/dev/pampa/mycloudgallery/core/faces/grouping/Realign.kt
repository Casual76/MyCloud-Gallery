package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.contract.PhotoKey
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * IL RIALLINEAMENTO DI DUE ISTANTANEE (docs/fase8-progetto.md §2.5 "Il custode e le istantanee", D21, D22, E-F25, E-F26). Puro e
 * deterministico.
 *
 * [onto] parte dall'istantanea **vincente** e ci porta le persone che conosce solo l'altra (nate da un custode che ha perso, da un custode
 * spento prima di pubblicare, o in locale). Per ogni persona attiva che la vincente non ha, in ordine di id:
 * - i suoi volti che la vincente ha gia' in una persona (stesso riferimento, o stesso posto con IoU >= 0,5) dicono **chi e'** nella
 *   vincente: la persona con piu' volti in comune (a parita' l'id minore);
 * - se ne condivide almeno il 30% (`INHERIT_SHARE`, la regola dell'eredita' di D22) o non le resta nessun volto libero, diventa un **alias**
 *   di quella (come un'unione: gli id restano, D22); se pero' ha un nome e l'altra no, l'alias va al contrario (la persona della vincente
 *   diventa alias di lei: il nome resta in vista); se hanno un nome tutte e due, non si uniscono (lo decide l'utente) e lei **rinasce**;
 * - altrimenti **rinasce con lo stesso id** con i suoi volti che la vincente non ha in nessuna persona (mai un volto in due persone).
 * Le persone unite che la vincente non ha restano alias (se la loro radice c'e'); le dimenticate restano dimenticate. I nomi e le
 * correzioni stanno nelle voci `people` e parlano di id e di posti: nessuno si perde. Il passo che segue (incrementale o notte) riapplica
 * le correzioni e riassegna i volti liberi. Se il risultato e' la vincente, resta lei (stesso numero); altrimenti ha `seq` = il massimo
 * dei due + 1 e `base` = la `seq` della vincente.
 */
internal object Realign {

  /** Vero se ogni persona di [other] (in qualunque stato) c'e' gia' in [state]: l'istantanea e' gia' stata assorbita. */
  fun contains(state: GroupingState, other: GroupingState): Boolean = other.persons.keys.all { it in state.persons }

  fun onto(winner: GroupingState, other: GroupingState, faces: List<GroupFace>, corrections: GroupingCorrections, params: GroupingParams): GroupingState {
    val orphans = other.persons.values.filter { it.id !in winner.persons }.sortedBy { it.id }
    if (orphans.isEmpty()) return winner
    val newSeq = maxOf(winner.seq, other.seq) + 1
    val current = faces.associateBy { it.ref }
    val atPlace = HashMap<PhotoKey, MutableList<GroupFace>>()
    for (f in faces) atPlace.getOrPut(f.photo) { ArrayList() } += f
    val persons = LinkedHashMap(winner.persons)
    val assign = HashMap(winner.assignments)

    /** Il volto corrente di un'assegnazione: lo stesso riferimento, o il primo volto nello stesso posto (D23). */
    fun currentOf(a: GroupAssignment): GroupFace? =
      current[a.face] ?: atPlace[a.locator.let { PhotoKey(it.contentKey, it.frame) }]?.sortedBy { it.ref }?.firstOrNull { a.locator.matches(it.locator) }

    fun live(a: GroupAssignment?) = a != null && a.origin != AssignOrigin.IGNORED

    for (p in orphans.filter { it.state == PersonState.ACTIVE }) {
      val mine = other.assignments.values.filter { it.person == p.id && it.origin != AssignOrigin.IGNORED }.sortedBy { it.face }
      val common = HashMap<PersonId, Int>()
      val free = ArrayList<Pair<GroupFace, GroupAssignment>>()
      val seen = HashSet<FaceRef>()
      for (a in mine) {
        val face = currentOf(a) ?: continue
        if (!seen.add(face.ref)) continue
        val there = assign[face.ref]
        if (live(there)) common[there!!.person] = (common[there.person] ?: 0) + 1 else if (there == null) free += face to a
      }
      val best = common.entries.sortedWith(compareByDescending<Map.Entry<PersonId, Int>> { it.value }.thenBy { it.key }).firstOrNull()
      val q = best?.key?.takeIf { persons[it]?.state == PersonState.ACTIVE }
      val share = (best?.value ?: 0).toDouble()
      val namedP = p.id in corrections.named
      val namedQ = q != null && q in corrections.named
      val same = q != null && (share >= GroupingParams.INHERIT_SHARE * mine.size || free.isEmpty()) && !(namedP && namedQ)
      when {
        same && namedP && !namedQ -> {
          // La persona della vincente diventa alias di quella con il nome: i suoi volti e i suoi esemplari passano a lei.
          val target = persons.getValue(q!!)
          persons[p.id] = GroupPerson(p.id, PersonState.ACTIVE, null, p.bornSeq, newSeq, target.exemplars)
          persons[q] = target.copy(state = PersonState.MERGED, aliasOf = p.id, exemplars = emptyList(), changedSeq = newSeq)
          for (a in assign.values.filter { it.person == q }) assign[a.face] = a.copy(person = p.id)
        }
        same -> persons[p.id] = GroupPerson(p.id, PersonState.MERGED, q, p.bornSeq, newSeq, emptyList())
        else -> {
          for ((face, a) in free) assign[face.ref] = GroupAssignment(face.ref, face.locator, p.id, a.score, a.origin)
          val rows = free.map { it.first }.sortedBy { it.ref }
          persons[p.id] = GroupPerson(p.id, PersonState.ACTIVE, null, p.bornSeq, newSeq, exemplars(rows, params))
        }
      }
    }
    for (p in orphans.filter { it.state == PersonState.FORGOTTEN }) persons[p.id] = p.copy(changedSeq = newSeq)
    // Gli alias che la vincente non ha, finche' se ne aggiunge qualcuno (una catena di alias si porta tutta).
    var pending = orphans.filter { it.state == PersonState.MERGED }
    while (true) {
      val ready = pending.filter { it.aliasOf in persons }
      if (ready.isEmpty()) break
      for (p in ready) persons[p.id] = p.copy(changedSeq = newSeq)
      pending = pending - ready.toSet()
    }
    return GroupingState(newSeq, winner.seq, persons, assign, winner.unassignedAtBirths)
  }

  /** Gli esemplari (§2.5) dei volti [faces] (in ordine di riferimento), come li sceglie il nucleo. */
  private fun exemplars(faces: List<GroupFace>, params: GroupingParams): List<FaceRef> {
    if (faces.isEmpty()) return emptyList()
    val table = FaceTable(faces)
    val rows = IntArray(faces.size) { it }
    return Exemplars.choose(table.matrix, rows, table.core, params.maxExemplars).map { faces[it].ref }
  }
}
