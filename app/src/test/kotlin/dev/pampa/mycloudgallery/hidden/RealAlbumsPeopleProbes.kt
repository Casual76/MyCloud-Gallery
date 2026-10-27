package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.LookupPeopleMembership
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.faces.AppPeopleLookup
import dev.pampa.mycloudgallery.faces.PeopleBridge
import java.time.LocalDate
import kotlin.time.Duration

// Fase 8 (P10, D27, D28, §2.9, E-F23, E-F27, E-F28, E-F37; riga PEOPLE del registro): la SONDA `albums-people`. La regola "Persone" degli album smart sul codice VERO del
// motore (`RulesSmartEngine` con `LookupPeopleMembership`) e sul catalogo VERO del mondo casuale, con le persone vere (`ArchivePeople`) e la visibilita' vera.
//
// La porta `PeopleMembership` la lega :app (P13); qui il banco la scrive con la stessa strada della ricerca: la scelta di persone della regola e' un `PersonOption`, le celle
// le da' `AppPeopleLookup` (indice delle persone, celle dell'istantanea, `PeopleVisibility`), e il motore le combina "in e" con le altre regole sui candidati VISIBILI
// del catalogo. L'oracolo viene dal mondo (le celle di ogni persona e le celle visibili adesso), mai dall'indice o dal catalogo.

internal object RealAlbumsPeopleProbes {

  private val FIRST = LocalDate.of(2026, 3, 4)
  private val LAST = LocalDate.of(2026, 3, 10)
  private val TODAY = LocalDate.of(2026, 10, 26)

  private fun period(): SmartPeriod = SmartPeriod.Ranges(listOf(LocalDayRange(FIRST, LAST)))

  private class Cell(val id: MediaId, val day: LocalDate, val screenshot: Boolean, val common: Boolean)

  /** Le celle visibili adesso con le proprieta' che contano per la classe di base (uno screenshot non e' mai un sicuro; un'area comune puo' esserlo se e' mia, qui non si dice). */
  private fun cellsOf(w: BuiltWorld): Map<MediaId, Cell> = w.liveLibraryCells.associateWith { id ->
    val m = w.spec.media[w.indexOf(id)]
    Cell(id, m.day, m.source == MediaSource.SCREENSHOT, m.where == Where.NAS_COMMON)
  }

  /** Gli album con le persone: sicuri e suggeriti, "e" con il periodo, alias e persone perdute, dispositivo senza volti, il nome che diventa regola. [leaky] toglie i filtri. */
  fun albumsPeople(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { people ->
      val lookup = AppPeopleLookup(people.people, people.cells, PeopleVisibility(people.visible, people.mask))
      val membership = PeopleBridge.membershipOf(lookup, people.people)
      val stack = RealSmartStack(w, leaky, membership)
      stack.enrich()
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val cells = cellsOf(w)
      var next = 0

      /** Un album con la regola delle persone (e il periodo, se [withPeriod]): cosa scrive il motore contro cio' che il mondo dice. */
      suspend fun check(label: String, ids: List<PersonId>, mode: PeopleMode, withPeriod: Boolean = false, expected: Set<MediaId>): Triple<Set<MediaId>, Set<MediaId>, SmartRules> {
        val rules = SmartRules(if (withPeriod) period() else null, null, null, people = PeopleRule(ids, mode))
        val album = stack.createAlbum(++next, rules)
        stack.engine.evaluate(album, Duration.INFINITE)
        val (sure, suggested) = stack.written(album)
        shown += sure + suggested
        val want = expected.filterTo(LinkedHashSet()) { id -> id in cells && (!withPeriod || cells.getValue(id).day in FIRST..LAST) }
        if (sure + suggested != want) problems += "$label: gli elementi e i suggeriti sono ${(sure + suggested).size}, il mondo ne attendeva ${want.size}"
        if (sure.any { id -> cells[id]?.screenshot != false }) problems += "$label: uno screenshot e' un elemento sicuro"
        if (want.any { id -> cells.getValue(id).let { !it.screenshot && !it.common } && id !in sure }) problems += "$label: una foto mia della persona non e' un elemento"
        val preview = stack.engine.preview(rules)
        shown += preview.sample
        if (preview.confident != sure.size || preview.suggested != suggested.size) problems += "$label: l'anteprima dice ${preview.confident}/${preview.suggested}, il motore ha scritto ${sure.size}/${suggested.size}"
        if (!preview.peopleAvailable) problems += "$label: l'anteprima dice che le persone non sono attive"
        return Triple(sure, suggested, rules)
      }

      // 1. Una persona, due persone (tutte e una qualunque), con il periodo.
      val visible = people.expectedNamed()
      for (id in visible) check("una persona", listOf(id), PeopleMode.ANY, expected = people.expectedCells(id))
      for (a in visible.indices) for (b in a + 1 until visible.size) {
        val pair = listOf(visible[a], visible[b])
        val cellsA = people.expectedCells(visible[a])
        val cellsB = people.expectedCells(visible[b])
        check("tutte", pair, PeopleMode.ALL, expected = cellsA intersect cellsB)
        check("una qualunque", pair, PeopleMode.ANY, expected = cellsA + cellsB)
      }
      if (visible.isNotEmpty()) check("persona e periodo", listOf(visible.first()), PeopleMode.ANY, withPeriod = true, expected = people.expectedCells(visible.first()))

      // 2. Una persona nascosta vale ancora nella regola (E-F28); una che vive solo su media da non mostrare non fa entrare niente.
      check("persona nascosta", listOf(people.namedHiddenPerson), PeopleMode.ANY, expected = people.expectedCells(people.namedHiddenPerson))
      val forbidden = check("persona su media nascosti", listOf(people.onlyForbidden), PeopleMode.ANY, expected = emptySet())
      if (forbidden.first.isNotEmpty() || forbidden.second.isNotEmpty()) problems += "una persona che vive solo su media nascosti ha riempito un album"

      // 3. Una persona dimenticata (E-F23, E-F27): "tutte" con lei non da' niente, "una qualunque" solo le altre; l'anteprima conta le perse.
      if (visible.isNotEmpty()) {
        val other = visible.first()
        check("tutte con una dimenticata", listOf(people.forgottenPerson, other), PeopleMode.ALL, expected = emptySet())
        val (_, _, rules) = check("una qualunque con una dimenticata", listOf(people.forgottenPerson, other), PeopleMode.ANY, expected = people.expectedCells(other))
        val lost = stack.engine.preview(rules).peopleLost
        if (lost != 1) problems += "l'anteprima conta $lost persone perse, ne attendeva 1"
        val okLost = stack.engine.preview(SmartRules(null, null, null, people = PeopleRule(listOf(other), PeopleMode.ANY))).peopleLost
        if (okLost != 0) problems += "l'anteprima di una persona riconosciuta conta $okLost perse"
      }

      // 4. Dispositivo senza volti (E-F37): un album con le persone non cambia, l'anteprima e' vuota e lo dice.
      if (visible.isNotEmpty()) {
        val rules = SmartRules(null, null, null, people = PeopleRule(listOf(visible.first()), PeopleMode.ANY))
        val album = stack.createAlbum(++next, rules)
        stack.engine.evaluate(album, Duration.INFINITE)
        val before = stack.written(album)
        shown += before.first + before.second
        people.available.value = false
        stack.engine.evaluate(album, Duration.INFINITE)
        stack.engine.reevaluate(album, (0 until 16).map { FIRST.plusDays(it.toLong() - 3) })
        if (stack.written(album) != before) problems += "senza i volti l'album con le persone cambia"
        val off = stack.engine.preview(rules)
        if (off.peopleAvailable || off.confident != 0 || off.suggested != 0 || off.sample.isNotEmpty()) problems += "senza i volti l'anteprima non e' vuota o non lo dice"
        people.available.value = true
      }

      // 5. Il nome che diventa regola: una persona visibile con un nome diventa la regola; il nome di una nascosta resta testo (come in Cerca).
      val interpreter = stack.interpreter(lookup)
      for (id in visible) {
        val proposal = interpreter.propose(people.nameOf(id)!!, TODAY)
        if (proposal.rules.people?.ids != listOf(id)) problems += "il nome di una persona visibile non diventa la regola delle persone"
      }
      val hiddenName = people.nameOf(people.namedHiddenPerson)!!
      if (interpreter.propose(hiddenName, TODAY).rules.people != null) problems += "il nome di una persona nascosta diventa una regola"

      // 6. Persone unite (E-F23): la regola con la persona che e' sparita nell'unione vale quella che resta, con le foto di tutte e due; l'unione non e' una persona persa.
      if (visible.size >= 2) {
        val keep = visible[0]
        val gone = visible[1]
        val outcome = people.people.merge(listOf(keep, gone), keepNameOf = keep)
        if (outcome != EditOutcome.Done) {
          problems += "l'unione di due persone non e' andata ($outcome)"
        } else {
          val (sure, suggested, rules) = check("persona unita", listOf(gone), PeopleMode.ANY, expected = people.expectedCells(keep) + people.expectedCells(gone))
          if (sure.isEmpty() && suggested.isEmpty() && (people.expectedCells(keep) + people.expectedCells(gone)).isNotEmpty()) problems += "la regola con una persona unita non trova niente"
          val lost = stack.engine.preview(rules).peopleLost
          if (lost != 0) problems += "una persona unita conta come persa ($lost)"
        }
      }

      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }
}
