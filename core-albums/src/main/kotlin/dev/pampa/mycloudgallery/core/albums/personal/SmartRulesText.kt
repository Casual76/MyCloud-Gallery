package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale

// IL TESTO DELLE REGOLE NEI DATI PERSONALI (docs/fase7-progetto.md D4, D13): le voci `smart:*` di un album sono testi (un valore `Text` di
// al piu' 1.024 caratteri, sincronizzato come ogni altra voce) e la proiezione li porta uguali nelle righe `album_rule` (il database non
// li interpreta). Qui la forma, scelta da P3 e letta da P6: **una regola, una voce**, cosi' due modifiche di due regole su due
// dispositivi si fondono invece di perdersi (D4); e **una forma sola** per ogni valore, cosi' scrivere e rileggere da' lo stesso valore.
//
//   smart:period   `r:AAAA-MM-GG..AAAA-MM-GG[,...]` serie di giorni locali (E-AL12), `y:MM-GG..MM-GG` ogni anno, `d:AAAA-MM-GG[,...]` giorni
//   smart:place    `<scope>:<raggio km>:<lat>:<lon>:<id GeoNames[,...]>:<etichetta>` (scope `around`, `region`, `country`; l'etichetta e' l'ultimo
//                  pezzo e puo' contenere i due punti)
//   smart:text     la descrizione, com'e'
//   smart:sources  i codici di `SmartSource` separati da virgola;  smart:types  i codici di `SmartMediaType`
//   smart:people   (Fase 8, D27) `any:<id>,<id>` (una qualunque) o `all:<id>,...` (tutte); gli id sono quelli in base32 dei file dei volti (26 caratteri):
//                  al piu' 32 persone stanno sotto i 1.024 caratteri (4 + 32 x 27). Mai i nomi: il campo dice chi, non come si chiama
//   smart:example:<media>  una foto d'esempio (flag acceso + impronta): non e' un testo, ne' qui ne' nel formato
//
// Una regola che non sta nel tetto di una voce, o che ha caratteri che il formato rifiuta, **non si scrive** ([encode] la riporta come
// problema): meglio un "troppo lunga" in un editor che una voce che gli altri dispositivi scarterebbero con l'intero segmento.
// Mai le regole nei log (D37): date e luoghi dicono dove si era e quando.

object SmartRulesText {

  /** Le regole come campi D4: per ogni campo il testo da scrivere, o null per toglierlo (la regola manca). */
  data class Encoded(val fields: Map<String, String?>, val tooLong: Set<String>) {
    val ok: Boolean get() = tooLong.isEmpty()

    override fun toString(): String = "Encoded(${fields.size} campi, ${tooLong.size} troppo lunghi)"
  }

  fun encode(rules: SmartRules): Encoded {
    val fields = LinkedHashMap<String, String?>()
    val bad = LinkedHashSet<String>()
    fun put(field: String, text: String?) {
      if (text != null && !writable(text)) bad += field else fields[field] = text
    }
    put(PersonalFields.SMART_PERIOD, rules.period?.let { encodePeriod(it) })
    put(PersonalFields.SMART_PLACE, rules.place?.let { encodePlace(it) })
    put(PersonalFields.SMART_TEXT, rules.text?.takeIf { it.isNotEmpty() })
    put(PersonalFields.SMART_SOURCES, if (rules.sources == SmartSource.ALL) null else codes(rules.sources))
    put(PersonalFields.SMART_TYPES, if (rules.types == SmartMediaType.ALL) null else codes(rules.types))
    put(PersonalFields.SMART_PEOPLE, rules.people?.let { encodePeople(it) })
    return Encoded(fields, bad)
  }

  /** Le regole dai campi `smart:*` ([fields]) e dalle foto d'esempio. Un campo illeggibile vale "regola assente" (mai un'eccezione). */
  fun decode(fields: Map<String, String>, examples: List<MediaId>): SmartRules {
    val sources = fields[PersonalFields.SMART_SOURCES]?.let { decodeCodes(it, SmartSource.entries) }?.takeIf { it.isNotEmpty() }
    val types = fields[PersonalFields.SMART_TYPES]?.let { decodeCodes(it, SmartMediaType.entries) }?.takeIf { it.isNotEmpty() }
    return SmartRules(
      period = fields[PersonalFields.SMART_PERIOD]?.let { decodePeriod(it) },
      place = fields[PersonalFields.SMART_PLACE]?.let { decodePlace(it) },
      text = fields[PersonalFields.SMART_TEXT],
      examples = examples.distinct().take(SmartLimits.MAX_EXAMPLES),
      sources = sources ?: SmartSource.ALL,
      types = types ?: SmartMediaType.ALL,
      people = fields[PersonalFields.SMART_PEOPLE]?.let { decodePeople(it) },
    )
  }

  private fun writable(text: String): Boolean = text.length <= PersonalValue.MAX_TEXT && !TextRules.hasForbidden(text)

  fun encodePeriod(period: SmartPeriod): String = when (period) {
    is SmartPeriod.Ranges -> "r:" + period.ranges.joinToString(",") { "${it.from}..${it.to}" }
    is SmartPeriod.EveryYear -> "y:${monthDay(period.from)}..${monthDay(period.to)}"
    is SmartPeriod.Days -> "d:" + period.days.sorted().joinToString(",")
  }

  fun decodePeriod(text: String): SmartPeriod? = try {
    val body = text.substring(2)
    when (text.substring(0, minOf(2, text.length))) {
      "r:" -> SmartPeriod.Ranges(body.split(',').map { part -> part.split("..").let { LocalDayRange(LocalDate.parse(it[0]), LocalDate.parse(it[1])) } })
      "y:" -> body.split("..").let { SmartPeriod.EveryYear(parseMonthDay(it[0]), parseMonthDay(it[1])) }
      "d:" -> SmartPeriod.Days(body.split(',').map { LocalDate.parse(it) }.toSet())
      else -> null
    }
  } catch (e: RuntimeException) {
    null
  }

  fun encodePlace(place: PlaceRule): String =
    listOf(place.scope.code, place.radiusKm, place.center.latitude, place.center.longitude, place.placeIds.joinToString(","), place.label)
      .joinToString(":")

  fun decodePlace(text: String): PlaceRule? = try {
    val parts = text.split(':', limit = 6)
    val scope = PlaceScope.entries.firstOrNull { it.code == parts[0] }
    if (scope == null || parts.size != 6) {
      null
    } else {
      PlaceRule(parts[4].split(',').map { it.toInt() }, GeoPoint(parts[2].toDouble(), parts[3].toDouble()), parts[1].toInt(), parts[5], scope)
    }
  } catch (e: RuntimeException) {
    null
  }

  /** `any:<id>,<id>` o `all:...`: gli id in base32 (26 caratteri), nell'ordine della regola. */
  fun encodePeople(rule: PeopleRule): String = rule.mode.code + ":" + rule.ids.joinToString(",") { it.base32() }

  /** La regola delle persone, o null se il campo non e' nella forma (un'app piu' nuova, un dato rovinato): una regola illeggibile vale "regola assente" (L205). */
  fun decodePeople(text: String): PeopleRule? {
    val colon = text.indexOf(':')
    if (colon <= 0) return null
    val mode = PeopleMode.fromCode(text.substring(0, colon)) ?: return null
    val parts = text.substring(colon + 1).split(',')
    if (parts.isEmpty() || parts.size > PeopleRule.MAX_PEOPLE) return null
    val ids = parts.map { PersonId.fromBase32(it) ?: return null }
    if (ids.toSet().size != ids.size) return null
    return PeopleRule(ids, mode)
  }

  private fun monthDay(value: MonthDay): String = String.format(Locale.ROOT, "%02d-%02d", value.monthValue, value.dayOfMonth)

  private fun parseMonthDay(text: String): MonthDay = text.split('-').let { MonthDay.of(it[0].toInt(), it[1].toInt()) }

  private fun codes(values: Set<StableCode>): String = values.map { it.code }.sorted().joinToString(",")

  private fun <T : StableCode> decodeCodes(text: String, all: List<T>): Set<T>? {
    val found = text.split(',').map { code -> all.firstOrNull { it.code == code } ?: return null }
    return found.toSet()
  }
}
