package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip

// LE LETTURE di una parola (Fase 8, P10, D26, §2.7, E-F36): in una posizione della query possono essere pronte piu' letture: una data o un tipo
// (calendario, tipi), un luogo, un nome di persona. Vince la **piu' lunga**; a pari parole, se una e' un nome, vince quella con piu' media visibili
// (il nome vince alla pari) e l'altra diventa l'alternativa del chip. Senza persone nel dizionario questo file riproduce esattamente le regole
// di prima (date e tipi, poi luoghi, poi i refusi dei luoghi).

internal sealed interface Reading {
  val len: Int
}

/** Una lettura senza persone: una data, un tipo, un luogo (anche con un refuso). [notes] e [correction] sono le correzioni dichiarate. */
internal class PlainReading(
  override val len: Int,
  val chip: SearchChip,
  val notes: List<Pair<String, String>> = emptyList(),
  val correction: Pair<String, String>? = null,
) : Reading

/** Un nome di persona; [rivals] sono le altre letture delle stesse parole che hanno perso (festa, mese, tipo, luogo), in quest'ordine. */
internal class PersonReading(override val len: Int, val hit: PersonHit, val rivals: List<SearchChip>) : Reading

/** Una persona o piu' persone di seguito: il chip, dove finisce (il token dopo l'ultimo) e le correzioni dei refusi. */
internal class PersonChain(val end: Int, val chip: SearchChip.Person, val corrections: List<Pair<String, String>>)

internal class QueryReadings(
  private val tokens: List<Token>,
  private val places: PlaceIndex,
  private val names: PeopleNameIndex?,
  private val dates: DateRules,
  private val calendar: CalendarRules,
  private val types: TypeRules,
) {

  /** La lettura che vince al token [i], o null se la parola non e' niente (resta contenuto). */
  fun at(i: Int): Reading? {
    val tk = tokens[i]
    if (tk.kind == TokenKind.BARRIER) return null
    var best: RuleMatch? = dates.match(i)
    fun consider(m: RuleMatch?) {
      if (m != null && (best == null || m.len > best!!.len)) best = m
    }
    consider(calendar.match(i))
    consider(types.match(i))
    val rule = best
    val place = places.exact(tokens, i)
    val person = names?.exact(tokens, i)
    val ruleLen = rule?.len ?: 0
    val placeLen = place?.len ?: 0
    if (person != null && person.len >= maxOf(ruleLen, placeLen)) {
      return withPerson(person, rule?.takeIf { it.len == person.len }, place?.takeIf { it.len == person.len })
    }
    if (place != null && place.len > ruleLen) return PlainReading(place.len, place.chip)
    if (rule != null) return PlainReading(rule.len, rule.chip, rule.notes)
    if (!tk.isWord || tk.word != tk.typed) return null
    names?.fuzzy(tokens, i) { places.hasWord(it) }?.let { return PersonReading(it.len, it, emptyList()) }
    return places.fuzzy(tokens, i)?.let { PlainReading(it.len, it.chip, correction = it.correction) }
  }

  /**
   * Il nome e' la lettura piu' lunga (o alla pari): se un'altra lettura delle stesse parole ha **piu'** media visibili vince lei e propone la
   * persona; altrimenti vince il nome (alla pari vince il nome: l'ha scritto l'utente) e le altre letture sono le sue alternative (E-F36).
   */
  private fun withPerson(person: PersonHit, rule: RuleMatch?, place: PlaceHit?): Reading {
    val own = person.entry.mediaCount
    val competes = rule != null && (rule.chip is SearchChip.DateRange || rule.chip is SearchChip.DateSet)
    val dateCount = if (competes) names!!.dates.count(rule!!.chip) else -1
    val placeCount = place?.count ?: -1
    if (maxOf(dateCount, placeCount) <= own) return PersonReading(person.len, person, listOfNotNull(rule?.chip, place?.chip))
    val option = PersonOption(listOf(person.entry.id), PeopleMode.ANY, person.entry.label)
    if (competes && dateCount >= placeCount) {
      val chip = when (val c = rule!!.chip) {
        is SearchChip.DateRange -> c.copy(person = option)
        is SearchChip.DateSet -> c.copy(person = option)
        else -> c
      }
      return PlainReading(rule.len, chip, rule.notes)
    }
    return PlainReading(place!!.len, place.chip.copy(person = option))
  }

  /**
   * Le persone di seguito a partire dal token [i]: "Marco e Anna" = tutte, "Marco o Anna" = una qualunque, "Marco Anna" e "Marco, Anna" = tutte. La
   * prima congiunzione fissa il modo ("Marco, Anna o Giulia" = una qualunque delle tre, "Marco, Anna e Giulia" = tutte e tre); senza nessuna congiunzione
   * sono tutte. Una congiunzione diversa dalla prima chiude il chip (la persona dopo ne comincia un altro, in "e" con il primo). Dopo una congiunzione
   * puo' esserci una preposizione o "con" ("foto di Marco e di Anna", "Marco e con Anna"). Con due o piu' persone l'unica alternativa e' "Cerca come parola".
   */
  fun chain(i: Int, first: PersonReading): PersonChain {
    val members = ArrayList<PersonHit>()
    members += first.hit
    val corrections = ArrayList<Pair<String, String>>()
    first.hit.correction?.let { corrections += it }
    var mode: PeopleMode? = null
    var end = i + first.len
    while (members.size < PersonOption.MAX_PEOPLE) {
      var k = end
      val connector = wordAt(k)
      // Senza congiunzione (un nome dopo l'altro, con o senza virgola) il legame e' neutro: prende il modo della congiunzione che c'e' altrove nel chip.
      val kind: PeopleMode? = when (connector) {
        in QueryVocabulary.AND_WORDS -> PeopleMode.ALL.also { k++ }
        in QueryVocabulary.OR_WORDS -> PeopleMode.ANY.also { k++ }
        else -> null
      }
      val explicit = k != end
      var next = if (k < tokens.size) at(k) as? PersonReading else null
      if (next == null && explicit && (wordAt(k) in QueryVocabulary.GLUE || wordAt(k) == "con")) {
        k++
        next = if (k < tokens.size) at(k) as? PersonReading else null
      }
      if (next == null) break
      if (kind != null) {
        if (mode == null) mode = kind else if (kind != mode) break
      }
      members += next.hit
      next.hit.correction?.let { corrections += it }
      end = k + next.len
    }
    val distinct = members.distinctBy { it.entry.id }
    val labels = distinct.map { it.entry.label }
    val chipMode = if (distinct.size == 1) PeopleMode.ANY else mode ?: PeopleMode.ALL
    val option = PersonOption(distinct.map { it.entry.id }, chipMode, labelOf(labels, chipMode))
    val typed = (i until end).joinToString(" ") { tokens[it].typed }
    val alternatives = if (members.size == 1) first.rivals.map { ChipAlternative(it) } else emptyList()
    return PersonChain(end, SearchChip.Person(option, alternatives + ChipAlternative(SearchChip.Word(typed, exact = false)), typed), corrections)
  }

  private fun wordAt(k: Int): String? = tokens.getOrNull(k)?.takeIf { it.isWord }?.word

  /** "Marco", "Marco e Anna", "Marco o Anna", "Marco, Anna e Giulia". */
  private fun labelOf(labels: List<String>, mode: PeopleMode): String {
    if (labels.size == 1) return labels.single()
    val join = if (mode == PeopleMode.ANY) " o " else " e "
    return labels.dropLast(1).joinToString(", ") + join + labels.last()
  }
}
