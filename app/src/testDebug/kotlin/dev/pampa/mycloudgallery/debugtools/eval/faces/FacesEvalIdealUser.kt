package dev.pampa.mycloudgallery.debugtools.eval.faces

import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * L'utente ideale dei banchi di prova (P11): conosce la verita' ([FacesEvalTestRig.truth]) e segna cio' che segnerebbe un uomo che non sbaglia mai. Per ogni gruppo
 * scelto tocca i volti mostrati che non sono dell'identita' piu' frequente del gruppo; se sono almeno [mixedShare] dei volti dice "Gruppo misto"; se non ce n'e'
 * nessuno dice "e' giusto". Si ferma dopo [limit] gruppi (per le scene a meta' lavoro degli screenshot).
 */
internal suspend fun idealUser(rig: FacesEvalTestRig, s: FacesEvalSession, kind: EvalSessionKind, mixedShare: Double = 2.0, limit: Int = Int.MAX_VALUE) {
  val byLocator = rig.story.faces.associateBy { it.locator }
  for (g in s.session(kind)!!.groups.take(limit)) {
    val members = rig.members().getValue(PersonId(g.person))
    val major = members.groupingBy { rig.truth.getValue(it.ref) }.eachCount().entries
      .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key
    val choice = s.choice(kind, g.person)
    val wrong = choice.all.filter { rig.truth.getValue(byLocator.getValue(it.locator).ref) != major }
    if (wrong.size >= mixedShare * choice.all.size) {
      s.setMixed(kind, g.person, true, choice.all.size)
    } else if (wrong.isEmpty()) {
      s.setReviewed(kind, g.person, choice.all.size)
    } else {
      for (w in wrong) s.toggleWrong(kind, g.person, w.locator, choice.all.size)
    }
  }
}
