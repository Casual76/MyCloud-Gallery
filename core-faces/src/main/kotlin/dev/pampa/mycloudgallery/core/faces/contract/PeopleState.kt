package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Il significato delle voci `people` unite (docs/fase8-progetto.md §2.4, §2.5 "Correzioni", §2.6, D22-D24): **una sola lettura** per il
 * raggruppamento (P5, [corrections]), le persone (P9) e la sincronizzazione (P3), cosi' nessuno interpreta le voci a modo suo.
 *
 * Due voci di volti che corrispondono allo stesso volto (stesso contenuto e fotogramma, IoU >= 0,5, scritte prima e dopo un ricalcolo con
 * riquadri un po' diversi) non convivono: vale la piu' recente nell'ordine di [PeopleRecord.winner] (D23). Una persona dimenticata non ha
 * nome ne' copertina e non e' fra quelle con un nome; i suoi alias e le sue separazioni restano (servono a risolvere gli id).
 */
class PeopleState private constructor(
  val names: Map<PersonId, String>,
  val hidden: Set<PersonId>,
  val forgotten: Set<PersonId>,
  val aliases: Map<PersonId, PersonId>,
  val apart: Set<PersonPair>,
  val born: Map<PersonId, PersonId>,
  val covers: Map<PersonId, FaceLocator>,
  val fixed: Map<FaceLocator, PersonId>,
  val rejected: Map<FaceLocator, Set<PersonId>>,
  val ignored: Set<FaceLocator>,
) {

  /** I vincoli del raggruppamento (D23). */
  fun corrections(): GroupingCorrections = GroupingCorrections(
    fixed = fixed,
    rejected = rejected,
    ignored = ignored,
    apart = apart,
    forgotten = forgotten,
    aliases = aliases,
    born = born.keys,
    named = names.keys,
  )

  /** La persona fissata dall'utente per il volto in [locator] (con l'abbinamento IoU), o null. */
  fun fixedFor(locator: FaceLocator): PersonId? = fixed.entries.firstOrNull { it.key.matches(locator) }?.value

  /** Le persone da cui il volto in [locator] e' escluso per sempre. */
  fun rejectedFor(locator: FaceLocator): Set<PersonId> = rejected.entries.filter { it.key.matches(locator) }.flatMap { it.value }.toSet()

  /** Vero se il volto in [locator] e' ignorato ("Ignora questo volto"). */
  fun isIgnored(locator: FaceLocator): Boolean = ignored.any { it.matches(locator) }

  override fun toString(): String =
    "PeopleState(nomi=${names.size}, nascoste=${hidden.size}, dimenticate=${forgotten.size}, alias=${aliases.size}, fissati=${fixed.size})"

  companion object {
    val EMPTY = of(emptyList())

    /** Il significato delle voci [records] (gia' unite o no: per ogni chiave vale [PeopleRecord.winner]). */
    fun of(records: Collection<PeopleRecord>): PeopleState {
      val merged = PeopleRecord.merge(emptyMap(), records).values
      // In ordine crescente di "vittoria": la voce piu' recente si applica per ultima e toglie quelle vecchie dello stesso volto.
      val ordered = merged.sortedWith { a, b -> compareRecords(a, b) }
      val names = HashMap<PersonId, String>()
      val hidden = HashSet<PersonId>()
      val forgotten = HashSet<PersonId>()
      val aliases = HashMap<PersonId, PersonId>()
      val apart = HashSet<PersonPair>()
      val born = HashMap<PersonId, PersonId>()
      val covers = HashMap<PersonId, FaceLocator>()
      val fixed = LinkedHashMap<FaceLocator, PersonId>()
      val rejected = LinkedHashMap<FaceLocator, MutableSet<PersonId>>()
      val ignored = LinkedHashSet<FaceLocator>()
      for (record in ordered) {
        when (val e = record.edit) {
          is PeopleEdit.Name -> if (e.name != null) names[e.person] = e.name else names.remove(e.person)
          is PeopleEdit.Hidden -> if (e.hidden) hidden += e.person else hidden -= e.person
          is PeopleEdit.Forget -> forgotten += e.person
          is PeopleEdit.Alias -> aliases[e.person] = e.into
          is PeopleEdit.Apart -> if (e.apart) apart += PersonPair.of(e.person, e.other) else apart -= PersonPair.of(e.person, e.other)
          is PeopleEdit.Born -> born[e.person] = e.splitFrom
          is PeopleEdit.Cover -> if (e.face != null) covers[e.person] = e.face else covers.remove(e.person)
          is PeopleEdit.Is -> {
            fixed.keys.removeAll { it.matches(e.face) }
            if (e.person != null) fixed[e.face] = e.person
          }
          is PeopleEdit.Not -> {
            val same = rejected.keys.filter { it.matches(e.face) }
            val persons = same.flatMap { rejected.getValue(it) }.toMutableSet()
            same.forEach { rejected.remove(it) }
            if (e.not) persons += e.person else persons -= e.person
            if (persons.isNotEmpty()) rejected[e.face] = persons
          }
          is PeopleEdit.Ignore -> {
            ignored.removeAll { it.matches(e.face) }
            if (e.ignored) ignored += e.face
          }
        }
      }
      for (person in forgotten) {
        names.remove(person)
        covers.remove(person)
      }
      return PeopleState(names, hidden, forgotten, aliases, apart, born, covers, fixed, rejected.mapValues { it.value.toSet() }, ignored)
    }

    /** L'ordine crescente di [PeopleRecord.winner] fra voci di chiavi qualunque (ora, poi autore minore che vince, poi valore). */
    private fun compareRecords(a: PeopleRecord, b: PeopleRecord): Int {
      val ta = a.atNas ?: Long.MAX_VALUE
      val tb = b.atNas ?: Long.MAX_VALUE
      if (ta != tb) return ta.compareTo(tb)
      if (a.author != b.author) return b.author.compareTo(a.author)
      val byKey = compareValues(a.key.subject + "\u0000" + a.key.field, b.key.subject + "\u0000" + b.key.field)
      if (byKey != 0) return byKey
      return compareValues(a.edit.value, b.edit.value)
    }
  }
}
