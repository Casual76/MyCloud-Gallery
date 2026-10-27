package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.first

// Fase 8 (P9, D28, E-F38, E-F39, F96, F99; riga PEOPLE del registro): le SONDE delle persone vere (`ArchivePeople` di core-faces) sul mondo casuale, con la visibilita'
// vera e volti sintetici su TUTTI i media (nascosti, membri di gruppi nascosti, ultimi nascosti compresi). Le superfici sono quelle che il prodotto ha: la sezione Persone
// delle Raccolte e "Tutte le persone" (con le due versioni di Q2), "Altri volti", la pagina di una persona con "Forse e' anche…", "Persone nascoste", "Controlla le foto" e
// i ritagli (il cancello), la riga "Persone" del viewer e il dizionario per la ricerca. Un media nascosto non deve comparire da nessuna parte -- ne' come foto, ne' come
// copertina, ne' nei conti -- e una persona che vive solo su nascosti non esiste.
//
// Che cosa non e' una superficie: `PeopleIndex.contentWith` restituisce chiavi di contenuto per la ricerca e gli album smart, che le trasformano in media e le filtrano con la
// loro `VisibleMedia` (lo provano le loro sonde: "search", "albums-smart"). Il suo dizionario invece e' un elenco di nomi con un conto di foto mostrabili: e' qui.

internal object RealPeopleProbes {

  private fun idsOf(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  /** Una persona nel suo insieme: la sua carta deve dire esattamente il conto delle foto che l'oracolo prevede. */
  private fun checkCard(stack: RealPeopleStack, card: PersonCard, problems: MutableList<String>, where: String) {
    val expected = stack.expectedCells(card.id).size
    if (card.photos != expected) problems += "$where: una persona dichiara ${card.photos} foto, ne attendeva $expected"
  }

  /** La sezione Persone delle Raccolte e "Tutte le persone" (Q2 b predefinita, Q2 a dietro l'opzione), "Altri volti" e "Persone nascoste". */
  fun peopleLists(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()

      suspend fun covers(cards: List<PersonCard>) {
        val media = stack.people.coverMedia(cards.map { it.id })
        shown += media.values
        for (card in cards) {
          val cover = media[card.id]
          // Ogni persona che compare in un elenco ha un posto da cui ritagliare il volto, a meno che non abbia un volto senza foto mostrabili (un nome senza volti).
          if (cover == null && stack.expectedCells(card.id).isNotEmpty()) problems += "una persona con foto mostrabili non ha la copertina"
        }
      }

      // --- Q2 (b): in primo piano solo chi ha un nome, gli altri in "Altri volti" ---
      stack.variant.value = PeopleVariant.NAMED_FIRST
      stack.people.refresh()
      val list = stack.people.list.value
      checkSet(list.named.map { it.id }, stack.expectedNamed(), "Tutte le persone (Q2 b): persone con un nome", problems)
      if (list.unnamed.isNotEmpty()) problems += "Q2 b: ci sono ${list.unnamed.size} persone senza nome in primo piano"
      if (list.othersCount != stack.expectedUnnamed().size) problems += "Q2 b: Altri volti dichiara ${list.othersCount} gruppi, ne attendeva ${stack.expectedUnnamed().size}"
      if (list.hiddenCount != stack.expectedHidden().size) problems += "le persone nascoste dichiarate sono ${list.hiddenCount}, ne attendeva ${stack.expectedHidden().size}"
      list.named.forEach { checkCard(stack, it, problems, "Tutte le persone") }
      covers(list.named)
      val others = stack.people.others().first().people
      checkSet(others.map { it.id }, stack.expectedUnnamed(), "Altri volti", problems)
      others.forEach { checkCard(stack, it, problems, "Altri volti") }
      covers(others)
      if (others.any { it.name != null }) problems += "Altri volti elenca una persona con un nome"

      // --- Q2 (a): le persone senza nome anche in Persone, dopo quelle con un nome ---
      stack.variant.value = PeopleVariant.ALL_GROUPS
      stack.people.refresh()
      val all = stack.people.list.value
      checkSet(all.named.map { it.id }, stack.expectedNamed(), "Tutte le persone (Q2 a): persone con un nome", problems)
      checkSet(all.unnamed.map { it.id }, stack.expectedUnnamed(), "Tutte le persone (Q2 a): persone senza nome", problems)
      if (all.othersCount != 0) problems += "Q2 a: Altri volti dichiara ${all.othersCount} gruppi"
      (all.named + all.unnamed).forEach { checkCard(stack, it, problems, "Tutte le persone (Q2 a)") }
      covers(all.unnamed)
      if (stack.people.others().first().people.isNotEmpty()) problems += "Q2 a: Altri volti non e' vuoto"
      stack.variant.value = PeopleVariant.NAMED_FIRST
      stack.people.refresh()

      // --- Persone nascoste ---
      val hidden = stack.people.hidden().first()
      checkSet(hidden.map { it.id }, stack.expectedHidden(), "Persone nascoste", problems)
      hidden.forEach { checkCard(stack, it, problems, "Persone nascoste") }
      covers(hidden)

      // --- La persona che vive solo su media da non mostrare non compare da nessuna parte ---
      val everywhere = list.named + others + all.unnamed + hidden
      if (everywhere.any { it.id == stack.onlyForbidden }) problems += "una persona che vive solo su media nascosti compare in un elenco"
      if (everywhere.any { it.id == stack.forgottenPerson }) problems += "una persona dimenticata compare in un elenco"
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** La pagina di ogni persona del mondo: le sue foto, la sua carta, "Forse e' anche…". */
  fun personPages(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      for (person in stack.persons + stack.onlyForbidden) {
        val view = stack.people.person(person).first()
        val expected = stack.expectedCells(person)
        if (view == null) {
          // Senza foto mostrabili una persona senza nome non ha pagina, e una con un nome ha una pagina solo se almeno una foto si puo' mostrare.
          if (expected.isNotEmpty() && person != stack.forgottenPerson) problems += "una persona con ${expected.size} foto mostrabili non ha pagina"
          continue
        }
        if (person == stack.forgottenPerson) problems += "una persona dimenticata ha ancora la pagina"
        val cells = idsOf(view.view)
        shown += cells
        if (cells.toSet() != expected) problems += "la pagina di una persona mostra ${cells.size} foto, ne attendeva ${expected.size}"
        if (view.card.photos != cells.size) problems += "la carta dice ${view.card.photos} foto e la pagina ne ha ${cells.size}"
        if (view.card.cover != null) {
          val coverMedia = stack.people.coverMedia(listOf(person))[person]
          if (coverMedia == null) problems += "la copertina e' un volto senza una foto da ritagliare" else shown += coverMedia
        }
        // "Forse e' anche…": le carte delle altre persone con i loro conti e le loro copertine.
        for (other in view.maybeAlso) {
          checkCard(stack, other, problems, "Forse e' anche")
          shown += stack.people.coverMedia(listOf(other.id)).values
        }
        // Una persona nascosta ha la sua pagina (il modo per mostrarla di nuovo): non perde le foto ma lo schermo non le mostra.
        if (person in stack.hiddenPersons && !view.hidden) problems += "la persona nascosta non lo dice"
        if (person !in stack.hiddenPersons && view.hidden) problems += "una persona non nascosta dice di esserlo"
      }
      // Le proposte "E' la stessa persona?" della lista.
      for (proposal in stack.people.proposals()) {
        checkCard(stack, proposal.first, problems, "Proposta")
        checkCard(stack, proposal.second, problems, "Proposta")
        shown += stack.people.coverMedia(listOf(proposal.first.id, proposal.second.id)).values
      }
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** "Controlla le foto", "Dividi" e i ritagli: i volti offerti vengono solo da celle mostrabili e il cancello dei ritagli dice no a tutto il resto. */
  fun faceReview(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      for (person in stack.persons + stack.onlyForbidden) {
        val offered = stack.people.reviewFaces(person)
        shown += offered.map { it.media }
        val allowed = stack.facesOf(person).filter { stack.indexOfFace(it) in w.oracle.liveShowable && person != stack.forgottenPerson }.map { it.locator }.toSet()
        val got = offered.map { it.face }.toSet()
        if (got != allowed) problems += "la revisione di una persona offre ${got.size} volti, ne attendeva ${allowed.size}"
      }
      // Il cancello dei ritagli: vero solo per i volti di un media che si puo' mostrare adesso.
      for (face in stack.faces) {
        val index = stack.indexOfFace(face)
        val mayShow = stack.people.mayShow(face.locator)
        val should = index in w.oracle.liveShowable
        if (mayShow != should) problems += "il cancello dei ritagli dice ${if (mayShow) "si" else "no"} a un volto che ${if (should) "si puo'" else "non si deve"} mostrare"
        if (mayShow) shown += w.ids[stack.cells.cellIndex(index)]
      }
      for (none in stack.noneFaces) {
        val should = stack.indexOfKey(none.contentKey) in w.oracle.liveShowable
        if (stack.people.mayShow(none) != should) problems += "il cancello dei ritagli sbaglia su un volto non riconoscibile"
      }
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /**
   * La riga "Persone" del viewer, su **ogni** media del mondo (anche quelli che si aprono dai Nascosti, F99): i chip non portano mai a una pagina che mostra un nascosto, e le
   * scelte di "Chi e'?" sono persone con un nome e non nascoste, con le copertine di celle mostrabili.
   */
  fun viewerRow(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val pages = HashMap<PersonId, Set<MediaId>>()
      suspend fun pageOf(person: PersonId): Set<MediaId> = pages.getOrPut(person) {
        stack.people.person(person).first()?.let { idsOf(it.view).toSet() }.orEmpty()
      }
      for (index in w.ids.indices) {
        val media = w.ids[index]
        val chips = stack.people.faces(media).first()
        val allowedHere = index in w.oracle.liveShowable
        for (chip in chips) {
          if (chip.person in stack.hiddenPersons || chip.person == stack.forgottenPerson) problems += "un chip porta una persona nascosta o dimenticata"
          chip.person?.let { shown += pageOf(it) }
          if (chip.tier == FaceTier.NONE && chip.person != null) problems += "un volto non riconoscibile ha una persona"
          // Un riferimento costruito per questo media vale; uno costruito per un altro no (non si ritaglia).
          if (!stack.people.belongsTo(media, chip.face)) problems += "un chip non appartiene al media che lo mostra"
        }
        // Da un media che si puo' mostrare il viewer ha i suoi chip; da uno nascosto la riga c'e' lo stesso (e' aperto dai Nascosti) ma non porta altrove (sopra).
        if (allowedHere && stack.faces.any { stack.indexOfFace(it) == index && stack.ownerOf.getValue(it.ref) !in stack.hiddenPersons && stack.ownerOf.getValue(it.ref) != stack.forgottenPerson } && chips.isEmpty()) {
          problems += "un media con volti non ha chip"
        }
      }
      val choices = stack.people.choices()
      if (choices.any { it.name == null }) problems += "Chi e'? offre una persona senza nome"
      if (choices.any { it.id in stack.hiddenPersons || it.id == stack.forgottenPerson }) problems += "Chi e'? offre una persona nascosta o dimenticata"
      choices.forEach { checkCard(stack, it, problems, "Chi e'?") }
      shown += stack.people.coverMedia(choices.map { it.id }).values
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** Il dizionario per la ricerca: solo persone con un nome, non nascoste, non dimenticate, con il conto delle foto mostrabili. */
  fun dictionary(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val names = stack.people.dictionary()
      val expected = stack.expectedNamed()
      checkSet(names.map { it.id }, expected, "dizionario", problems)
      for (name in names) {
        val want = stack.expectedCells(name.id).size
        if (name.visibleMedia != want) problems += "il dizionario dichiara ${name.visibleMedia} foto per una persona, ne attendeva $want"
      }
      Probed(shown = emptySet(), problems = problems)
    }
  }

  private fun checkSet(got: List<PersonId>, want: List<PersonId>, where: String, problems: MutableList<String>) {
    val extra = got.toSet() - want.toSet()
    val missing = want.toSet() - got.toSet()
    if (extra.isNotEmpty()) problems += "$where: ${extra.size} persone in piu'"
    if (missing.isNotEmpty()) problems += "$where: ${missing.size} persone mancano"
    if (got.size != got.toSet().size) problems += "$where: una persona compare due volte"
  }
}
