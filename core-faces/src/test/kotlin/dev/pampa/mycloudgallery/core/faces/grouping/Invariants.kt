package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Le regole che ogni stato del raggruppamento rispetta dopo ogni passo (§2.5, D20, D23; riga P5 del §4.4): le correzioni dell'utente
 * valgono per sempre, due volti della stessa foto mai nella stessa persona in automatico, una persona nasce solo da 3 volti di 2 foto.
 * [violations] restituisce le regole violate (vuoto = tutto bene); i messaggi non contengono id ne' volti (solo il nome della regola).
 */
internal object Invariants {

  /** La radice di [id] seguendo SOLO gli alias dell'utente (con la regola dei cicli): due persone unite a mano. */
  private fun userRoot(id: PersonId, aliases: Map<PersonId, PersonId>): PersonId {
    val seen = LinkedHashSet<PersonId>()
    var current = id
    while (true) {
      val next = aliases[current] ?: return current
      if (!seen.add(current)) return seen.toList().dropWhile { it != current }.min()
      current = next
    }
  }

  fun violations(
    state: GroupingState,
    faces: List<GroupFace>,
    corrections: GroupingCorrections,
    gone: Set<ContentKey> = emptySet(),
    previous: GroupingState? = null,
    params: GroupingParams = GroupingParams.DEFAULT,
  ): List<String> {
    val out = ArrayList<String>()
    val current = faces.filter { it.ref.contentKey !in gone }.associateBy { it.ref }
    val live = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }
    // Ogni assegnazione e' di un volto corrente e di una persona attiva (le ignorate di una persona dimenticata).
    for (a in state.assignments.values) {
      if (a.face !in current) out += "assegnazione di un volto non corrente"
      val p = state.persons[a.person]
      if (a.origin == AssignOrigin.IGNORED) {
        if (p?.state != PersonState.FORGOTTEN) out += "ignorata non di una persona dimenticata"
      } else if (p?.state != PersonState.ACTIVE) {
        out += "volto vivo in una persona non attiva"
      }
    }
    // Correzioni: "Ignora", "E' …", "Non e' …", "Dimentica", "Unisci", "Separati", "Dividi".
    for (f in current.values) {
      val a = state.assignments[f.ref]
      val ignored = corrections.ignored.any { it.matches(f.locator) }
      if (ignored && a != null && a.origin != AssignOrigin.IGNORED) out += "volto ignorato in una persona"
      val fixed = corrections.fixed.entries.filter { it.key.matches(f.locator) }.minByOrNull { it.key.encode() }?.value
      if (!ignored && fixed != null) {
        val root = rootOf(state, fixed)
        val rootState = root?.let { state.persons[it]?.state }
        when {
          rootState == PersonState.FORGOTTEN -> if (a?.origin != AssignOrigin.IGNORED) out += "fissato in una dimenticata non ignorato"
          root != null && (a == null || a.origin != AssignOrigin.USER || a.person != root) -> out += "\"E' …\" non rispettato"
        }
      }
      if (fixed == null && a?.origin == AssignOrigin.USER) out += "conferma dell'utente senza correzione"
      if (a != null && a.origin == AssignOrigin.AUTO) {
        val rejected = corrections.rejected.entries.filter { it.key.matches(f.locator) }.flatMap { it.value }.mapNotNull { state.resolve(it) }
        if (a.person in rejected) out += "\"Non e' …\" non rispettato"
      }
    }
    for (id in corrections.forgotten) if (state.resolve(id) != null) out += "persona dimenticata ancora risolta"
    for ((from, into) in corrections.aliases) {
      if (from in corrections.forgotten || into in corrections.forgotten) continue
      if (state.persons[from]?.state == PersonState.FORGOTTEN || state.persons[into]?.state == PersonState.FORGOTTEN) continue
      if (state.resolve(from) != state.resolve(into)) out += "\"Unisci\" non rispettato"
    }
    // "Separati": le unioni automatiche (gli alias dello stato che non sono dell'utente) non portano mai insieme due persone separate. Se
    // le porta insieme anche un alias dell'utente, vale la scelta dell'utente.
    for (pair in corrections.apart) {
      if (userRoot(pair.first, corrections.aliases) == userRoot(pair.second, corrections.aliases)) continue
      if (autoRoot(state, pair.first, corrections.aliases) == autoRoot(state, pair.second, corrections.aliases)) out += "\"Separati\" uniti in automatico"
    }
    for (id in corrections.born) if (id !in state.persons) out += "persona di \"Dividi\" assente"
    // Due volti della stessa foto mai nella stessa persona in automatico (con un volto dell'utente o con un altro automatico).
    for ((_, list) in live.groupBy { it.person to current.getValue(it.face).photo }) {
      if (list.size > 1 && list.any { it.origin == AssignOrigin.AUTO }) out += "due volti della stessa foto nella stessa persona"
    }
    // Le nascite: una persona nuova (non dalle correzioni) nasce con almeno 3 volti di almeno 2 foto.
    if (previous != null) {
      val fromUser = corrections.born + corrections.fixed.values + corrections.aliases.keys + corrections.aliases.values + corrections.forgotten
      val byPerson = live.groupBy { it.person }
      for ((id, p) in state.persons) {
        if (id in previous.persons || id in fromUser || p.state != PersonState.ACTIVE) continue
        val mine = byPerson[id].orEmpty()
        if (mine.size < params.minFaces || mine.map { it.face.contentKey }.distinct().size < params.minPhotos) out += "persona nata con meno di 3 volti in 2 foto"
      }
      // Gli id non spariscono mai: una persona resta (attiva, unita come alias, dimenticata).
      for (id in previous.persons.keys) if (id !in state.persons) out += "persona sparita"
    }
    return out.distinct()
  }

  /** La radice di [id] seguendo SOLO gli alias automatici dello stato (quelli che non sono un alias dell'utente). */
  private fun autoRoot(state: GroupingState, id: PersonId, user: Map<PersonId, PersonId>): PersonId {
    val seen = HashSet<PersonId>()
    var current = id
    while (seen.add(current)) {
      val p = state.persons[current] ?: return current
      val next = p.aliasOf ?: return current
      if (p.state != PersonState.MERGED || user[current] == next) return current
      current = next
    }
    return current
  }

  private fun rootOf(state: GroupingState, id: PersonId): PersonId? {
    val seen = LinkedHashSet<PersonId>()
    var current = id
    while (true) {
      val p = state.persons[current] ?: return null
      if (p.state != PersonState.MERGED) return current
      if (!seen.add(current)) return seen.toList().dropWhile { it != current }.min()
      current = p.aliasOf!!
    }
  }

  /** I volti che una persona dimenticata aveva: non devono mai tornare automatici (D24, E-F27). */
  fun liveFacesOf(state: GroupingState, ids: Set<PersonId>): Set<FaceRef> =
    state.assignments.values.filter { it.origin != AssignOrigin.IGNORED && state.resolve(it.person) in ids }.map { it.face }.toSet()
}
