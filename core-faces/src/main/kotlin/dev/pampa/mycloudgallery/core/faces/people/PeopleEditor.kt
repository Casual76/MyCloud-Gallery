package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.faces.people.PeopleCore.Loaded
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Le AZIONI sulle persone (docs/fase8-progetto.md §2.6, D23, D24): ognuna scrive le sue voci `people` in **una** scrittura sull'archivio
 * (una transazione locale), vale **subito** su questo dispositivo anche senza l'ora del NAS (la voce resta "da datare" e prende l'ora alla
 * prima sincronizzazione; due voci da datare della stessa chiave: la nuova sostituisce la vecchia, "Fase 8 P6") e si accoda alla
 * pubblicazione (E-F5). Una azione per volta (un lucchetto): leggere, decidere le voci e scrivere non si intrecciano con un'altra azione.
 *
 * Le correzioni sono vincoli permanenti sui **posti** dei volti (D23): quando cambiano idea, la voce vecchia si toglie con la sua
 * **lapide** (`null`), con la stessa chiave. Tre casi che le lapidi risolvono (senza, la correzione nuova non basterebbe):
 *  - "Non e' …" su un volto **confermato** in quella persona: si scrive anche la lapide di `is` ("Fase 8 P5": il volto fissato vince
 *    sull'esclusione, "E' …" vincerebbe);
 *  - "E' …" su un volto che era stato escluso da quella persona o ignorato: la lapide di `not` / `ignore`;
 *  - "Ignora questo volto" su un volto confermato: la lapide di `is` (togliere l'ignora poi non lo rimetterebbe dove non voleva).
 */
internal class PeopleEditor(
  private val core: PeopleCore,
  private val publisher: FacesPublisher,
  private val clock: FacesClock,
  private val author: String,
  private val ids: PersonIdSource,
  private val changed: suspend () -> Unit,
) {
  private val lock = Mutex()
  private var wrote = false

  /** L'ultima ora del NAS data a una voce: la successiva e' sempre piu' tarda (alla pari una lapide perderebbe contro il valore che toglie). */
  private var lastAt = Long.MIN_VALUE

  /** Una azione sotto il lucchetto; `changed()` (le viste si rifanno) solo se ha scritto qualcosa, e fuori dal lucchetto. */
  private suspend fun <R> guarded(inactive: R, body: suspend (Loaded) -> R): R {
    var didWrite = false
    val result = lock.withLock {
      wrote = false
      val loaded = core.current() ?: return@withLock inactive
      val out = try {
        body(loaded)
      } catch (_: IllegalStateException) {
        // L'archivio si e' chiuso a meta' (chiave distrutta, generazione cambiata): niente di quello che e' stato scritto e' perso, ma qui non si e' attivi.
        return@withLock inactive
      }
      didWrite = wrote
      out
    }
    if (didWrite) changed()
    return result
  }

  private suspend fun write(l: Loaded, edits: List<PeopleEdit>): EditOutcome {
    val at = clock.nasNowMillis()?.let { maxOf(it, lastAt + 1) }?.also { lastAt = it }
    l.archive.addPeople(edits.map { PeopleRecord(it, at, author) })
    core.replace(l, l.archive.people())
    wrote = true
    try {
      publisher.stage(FacesChange.People(edits))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // La voce e' nell'archivio e vale gia': una ripresa ripubblica lo stato intero del proprio contributo (FlushReason.RESUME).
      GalleryLog.w(TAG) { "voce non accodata alla pubblicazione (${e.javaClass.simpleName})" }
    }
    return EditOutcome.Done
  }

  // --- Nome, nascondi, dimentica, copertina ------------------------------------------------------------------------------------

  suspend fun rename(id: PersonId, name: String?): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val root = l.lens.resolve(id) ?: return@guarded EditOutcome.NotFound
    val clean = if (name == null) null else PersonNames.normalize(name) ?: return@guarded EditOutcome.InvalidName
    if (l.lens.people.names[root] == clean) EditOutcome.Done else write(l, listOf(PeopleEdit.Name(root, clean)))
  }

  suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val root = l.lens.resolve(id) ?: return@guarded EditOutcome.NotFound
    if ((root in l.lens.people.hidden) == hidden) EditOutcome.Done else write(l, listOf(PeopleEdit.Hidden(root, hidden)))
  }

  suspend fun forget(id: PersonId): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val root = l.lens.resolve(id) ?: return@guarded EditOutcome.NotFound
    write(l, listOf(PeopleEdit.Forget(root), PeopleEdit.Name(root, null)))
  }

  suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val root = l.lens.resolve(id) ?: return@guarded EditOutcome.NotFound
    if (face == null) return@guarded if (root in l.lens.people.covers) write(l, listOf(PeopleEdit.Cover(root, null))) else EditOutcome.Done
    // La copertina e' un volto della persona **e** di un media che si puo' mostrare adesso (D25, D28).
    val shown = core.shownFor(l, listOf(root)).getValue(root)
    val mine = shown.shownFaces.firstOrNull { it.first.matches(face) }?.first ?: return@guarded EditOutcome.Invalid
    write(l, listOf(PeopleEdit.Cover(root, mine)))
  }

  // --- Unisci, dividi, proposte --------------------------------------------------------------------------------------------------

  suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome = guarded(EditOutcome.NotActive) { l -> mergeIn(l, ids, keepNameOf) }

  private suspend fun mergeIn(l: Loaded, ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome {
    val lens = l.lens
    if (ids.any { lens.resolve(it) == null }) return EditOutcome.NotFound
    val roots = ids.mapNotNull { lens.resolve(it) }.distinct()
    if (roots.size < 2) return EditOutcome.Invalid
    val named = roots.filter { lens.people.names[it] != null }
    val keep = keepNameOf?.let { lens.resolve(it) }
    if (keepNameOf != null && keep !in roots) return EditOutcome.Invalid
    // E-F23: con due nomi si sceglie quale tenere.
    if (named.size >= 2 && keep == null) return EditOutcome.NeedsNameChoice
    val survivor = keep ?: named.singleOrNull() ?: core.shownFor(l, roots).values.sortedWith(compareByDescending<PeopleCore.Shown> { it.cells.size }.thenBy { it.id }).first().id
    val joined = roots.filter { it != survivor }.map { PeopleEdit.Alias(it, survivor) }
    // Unire a mano toglie un "No" detto prima a una proposta: le due persone non sono piu' "separate".
    val unseparated = roots.flatMap { a -> roots.filter { it > a }.map { b -> PersonPair.of(a, b) } }
      .filter { it in lens.people.apart }.map { PeopleEdit.Apart(it.first, it.second, false) }
    return write(l, joined + unseparated)
  }

  /** "E' la stessa persona?" -> "Si'" unisce (con un nome solo, quello resta; con due, si sceglie); "No" separa per sempre. */
  suspend fun answer(first: PersonId, second: PersonId, same: Boolean): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val a = l.lens.resolve(first) ?: return@guarded EditOutcome.NotFound
    val b = l.lens.resolve(second) ?: return@guarded EditOutcome.NotFound
    if (a == b) return@guarded EditOutcome.Invalid
    if (!same) {
      val pair = PersonPair.of(a, b)
      return@guarded if (pair in l.lens.people.apart) EditOutcome.Done else write(l, listOf(PeopleEdit.Apart(a, b, true)))
    }
    val named = listOf(a, b).filter { l.lens.people.names[it] != null }
    mergeIn(l, listOf(a, b), keepNameOf = named.singleOrNull())
  }

  suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome = guarded(SplitOutcome.Failed(EditOutcome.NotActive)) { l ->
    val root = l.lens.resolve(id) ?: return@guarded SplitOutcome.Failed(EditOutcome.NotFound)
    val mine = l.lens.facesOf(root)
    val chosen = mine.filter { m -> faces.any { it.matches(m) } }
    // Si dividono volti che sono della persona; tutti i volti non e' dividere (la persona di partenza resterebbe vuota).
    if (faces.isEmpty() || faces.any { f -> mine.none { it.matches(f) } } || chosen.isEmpty() || chosen.size >= mine.size) {
      return@guarded SplitOutcome.Failed(EditOutcome.Invalid)
    }
    val fresh = ids.next()
    write(l, listOf<PeopleEdit>(PeopleEdit.Born(fresh, root)) + chosen.map { PeopleEdit.Is(it, fresh) } + PeopleEdit.Apart(fresh, root, true))
    SplitOutcome.Split(fresh)
  }

  // --- Correzioni dei volti ----------------------------------------------------------------------------------------------------------

  /** Il volto come sta nel corpo vincente (posto canonico), o null se l'archivio non lo conosce. Un volto senza vettore (NONE) non si corregge. */
  private sealed interface Found {
    data class Face(val locator: FaceLocator) : Found

    data object Unrecognizable : Found

    data object Missing : Found
  }

  private suspend fun find(l: Loaded, face: FaceLocator): Found {
    val entry = l.archive.winners(listOf(face.contentKey))[face.contentKey]?.faces
      ?.firstOrNull { it.frame == face.frame && FaceLocator(face.contentKey, it.frame, it.box).matches(face) }
    return when {
      entry != null && entry.tier == FaceTier.NONE -> Found.Unrecognizable
      entry != null -> Found.Face(FaceLocator(face.contentKey, entry.frame, entry.box))
      // Un volto che il corpo non ha piu' (ricalcolo) ma che una correzione di prima tiene in una persona si corregge ancora.
      l.lens.ownerOf(face) != null -> Found.Face(face)
      else -> Found.Missing
    }
  }

  suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val lens = l.lens
    val place = when (val found = find(l, face)) {
      is Found.Face -> found.locator
      Found.Unrecognizable -> return@guarded EditOutcome.Invalid
      Found.Missing -> return@guarded EditOutcome.NotFound
    }
    val edits = ArrayList<PeopleEdit>()
    val person = when (to) {
      is PersonTarget.Existing -> lens.resolve(to.id) ?: return@guarded EditOutcome.NotFound
      is PersonTarget.New -> {
        val clean = PersonNames.normalize(to.name) ?: return@guarded EditOutcome.InvalidName
        ids.next().also { edits += PeopleEdit.Name(it, clean) }
      }
    }
    edits += PeopleEdit.Is(place, person)
    // "E' …" cancella cio' che diceva il contrario: l'esclusione da quella persona e l'"ignora".
    for ((where, persons) in lens.rejectedKeys(place)) {
      persons.filter { lens.resolve(it) == person }.forEach { edits += PeopleEdit.Not(where, it, false) }
    }
    for (where in lens.ignoredKeys(place)) edits += PeopleEdit.Ignore(where, false)
    write(l, edits)
  }

  suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val lens = l.lens
    val root = lens.resolve(from) ?: return@guarded EditOutcome.NotFound
    val place = when (val found = find(l, face)) {
      is Found.Face -> found.locator
      Found.Unrecognizable -> return@guarded EditOutcome.Invalid
      Found.Missing -> return@guarded EditOutcome.NotFound
    }
    if (lens.rejectedFor(place).any { lens.resolve(it) == root }) return@guarded EditOutcome.Done
    val edits = ArrayList<PeopleEdit>()
    edits += PeopleEdit.Not(place, root, true)
    // Il volto confermato in quella persona vincerebbe sull'esclusione (lens): si toglie anche la conferma ("Fase 8 P5").
    for ((where, person) in lens.fixedKeys(place)) if (lens.resolve(person) == root) edits += PeopleEdit.Is(where, null)
    write(l, edits)
  }

  suspend fun ignore(face: FaceLocator): EditOutcome = guarded(EditOutcome.NotActive) { l ->
    val lens = l.lens
    val place = when (val found = find(l, face)) {
      is Found.Face -> found.locator
      Found.Unrecognizable -> return@guarded EditOutcome.Invalid
      Found.Missing -> return@guarded EditOutcome.NotFound
    }
    if (lens.isIgnored(place)) return@guarded EditOutcome.Done
    val edits = ArrayList<PeopleEdit>()
    edits += PeopleEdit.Ignore(place, true)
    for ((where, _) in lens.fixedKeys(place)) edits += PeopleEdit.Is(where, null)
    write(l, edits)
  }

  private companion object {
    const val TAG = "PeopleEditor"
  }
}
