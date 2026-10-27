package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleArchiveSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.concurrent.atomic.AtomicReference

/**
 * Quello che le letture e le azioni delle persone condividono (docs/fase8-progetto.md §2.6, D24, D25, D28): l'archivio della generazione
 * corrente letto **una volta** ([Loaded]: l'istantanea del raggruppamento e le voci `people` unite, con la lettura [PeopleLens] sopra) e
 * tenuto in memoria fino alla prossima [reload]; e il calcolo di cio' che si puo' **mostrare** di una persona ([shownFor]), che passa solo da
 * [PeopleVisibility]. Un archivio chiuso (chiave distrutta, generazione cambiata: `IllegalStateException`) non e' un errore: "Persone non e'
 * attiva qui" (null). Mai nomi o id nei log (D37).
 */
internal class PeopleCore(
  private val archives: PeopleArchiveSource,
  private val cells: PeopleCells,
  val visibility: PeopleVisibility,
) {

  /** L'archivio con quello che se ne e' letto: le voci cambiano a ogni azione ([withRecords]), l'istantanea quando il custode pubblica. */
  class Loaded(val archive: FacesArchive, val grouping: GroupingState, val records: Map<PeopleKey, PeopleRecord>) {
    val lens: PeopleLens by lazy { PeopleLens(grouping, PeopleState.of(records.values)) }

    fun withRecords(records: Map<PeopleKey, PeopleRecord>): Loaded = Loaded(archive, grouping, records)

    override fun toString(): String = "Loaded(${grouping.persons.size} persone, ${records.size} voci)"
  }

  private val loaded = AtomicReference<Loaded?>(null)

  /** Rilegge l'archivio. Null se non c'e' un archivio della generazione corrente o si e' chiuso mentre lo leggevamo. */
  suspend fun reload(): Loaded? {
    val archive = archives.current()
    if (archive == null) {
      loaded.set(null)
      return null
    }
    return try {
      Loaded(archive, archive.grouping(), archive.people()).also { loaded.set(it) }
    } catch (_: IllegalStateException) {
      loaded.set(null)
      null
    }
  }

  /** Cio' che si e' letto l'ultima volta, senza rileggere (per le risposte sincrone, come `PeopleIndex.resolve`). */
  fun peek(): Loaded? = loaded.get()

  /** Cio' che si e' letto, se e' ancora dell'archivio corrente (un cambio di generazione rilegge da capo). */
  suspend fun current(): Loaded? {
    val now = archives.current() ?: return null.also { loaded.set(null) }
    val have = loaded.get()
    return if (have != null && have.archive === now) have else reload()
  }

  fun replace(old: Loaded, records: Map<PeopleKey, PeopleRecord>): Loaded = old.withRecords(records).also { loaded.set(it) }

  /** Una persona come si mostra: le sue celle mostrabili, la copertina, e per ogni volto la prima cella che lo mostra. */
  class Shown(
    val id: PersonId,
    val name: String?,
    val hidden: Boolean,
    val cells: List<MediaId>,
    val faceCount: Int,
    val cover: FaceLocator?,
    val shownFaces: List<Pair<FaceLocator, MediaId>>,
  ) {
    val anyFace: Boolean get() = faceCount > 0

    fun card(): PersonCard = PersonCard(id, name, cells.size, cover)

    override fun toString(): String = "Shown(${cells.size} celle, nascosta=$hidden)"
  }

  /**
   * Cio' che si mostra di [roots] (radici delle persone), in **una** passata: i contenuti dei loro volti -> le celle che li contengono
   * ([PeopleCells]) -> quelle mostrabili ([PeopleVisibility.shown], filtro finale compreso). La copertina viene solo da un volto con una cella
   * mostrabile: prima quella scelta dall'utente se ancora buona, poi un volto **confermato**, poi il riquadro piu' grande (piu' pixel),
   * a parita' il localizzatore minore.
   */
  suspend fun shownFor(l: Loaded, roots: Collection<PersonId>): Map<PersonId, Shown> {
    val lens = l.lens
    val locators = roots.associateWith { lens.facesOf(it) }
    val keys = locators.values.flatMapTo(HashSet<ContentKey>()) { list -> list.map { it.contentKey } }
    val cellsByKey = if (keys.isEmpty()) emptyMap() else cells.cellsWith(keys)
    val show = visibility.shown(cellsByKey.values.flatten().toSet())
    return roots.associateWith { id ->
      val mine = locators.getValue(id)
      val faceCells = mine.mapNotNull { face ->
        val cell = cellsByKey[face.contentKey].orEmpty().filter { it in show }.minByOrNull { it.value }
        cell?.let { face to it }
      }
      val shownCells = mine.flatMap { cellsByKey[it.contentKey].orEmpty() }.filter { it in show }.distinct().sortedBy { it.value }
      Shown(
        id = id,
        name = lens.people.names[id],
        hidden = id in lens.people.hidden,
        cells = shownCells,
        faceCount = mine.size,
        cover = coverOf(lens, id, faceCells.map { it.first }),
        shownFaces = faceCells,
      )
    }
  }

  private fun coverOf(lens: PeopleLens, id: PersonId, eligible: List<FaceLocator>): FaceLocator? {
    if (eligible.isEmpty()) return null
    lens.people.covers[id]?.let { chosen -> eligible.firstOrNull { it.matches(chosen) }?.let { return it } }
    return eligible.maxWithOrNull(
      compareBy<FaceLocator> { lens.isConfirmed(it) }.thenBy { area(it) }.thenByDescending { it.encode() },
    )
  }

  private fun area(face: FaceLocator): Long = (face.box.x1 - face.box.x0).toLong() * (face.box.y1 - face.box.y0).toLong()
}
