package dev.pampa.mycloudgallery.core.albums.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import java.time.LocalDate
import java.time.MonthDay
import kotlin.random.Random
import org.junit.Test

/** Il testo delle regole nelle voci `smart:*` (D4, D13): una forma sola per valore, si rilegge uguale e non scrive mai una voce illegibile. */
class SmartRulesTextTest {

  private fun roundTrip(rules: SmartRules): SmartRules {
    val encoded = SmartRulesText.encode(rules)
    assertThat(encoded.ok).isTrue()
    return SmartRulesText.decode(encoded.fields.filterValues { it != null }.mapValues { it.value!! }, rules.examples)
  }

  private fun day(text: String) = LocalDate.parse(text)

  @Test
  fun ilPeriodoSiRileggeUgualeInOgniForma() {
    val ranges = SmartPeriod.Ranges(listOf(LocalDayRange(day("2025-12-24"), day("2026-01-06")), LocalDayRange(day("2026-08-01"), day("2026-08-01"))))
    val everyYear = SmartPeriod.EveryYear(MonthDay.of(12, 21), MonthDay.of(3, 20))
    val days = SmartPeriod.Days(setOf(day("2024-03-31"), day("2025-04-20"), day("2026-04-05")))
    for (period in listOf(ranges, everyYear, days)) {
      assertThat(roundTrip(SmartRules(period, null, null)).period).isEqualTo(period)
    }
  }

  @Test
  fun ilLuogoSiRileggeUgualeAncheConLeEtichetteConIDuePunti() {
    val place = PlaceRule(listOf(3143244, 3143245), GeoPoint(59.9139, 10.7522), 25, "Oslo: Norvegia", PlaceScope.AROUND)
    assertThat(roundTrip(SmartRules(null, place, null)).place).isEqualTo(place)
    val region = PlaceRule(listOf(3169070), GeoPoint(-33.865143, 151.2099), SmartLimits.MAX_RADIUS_KM, "", PlaceScope.REGION)
    assertThat(roundTrip(SmartRules(null, region, null)).place).isEqualTo(region)
  }

  @Test
  fun descrizioneSorgentiETipiViaggiano_ELeSceltePredefiniteNonScrivonoNiente() {
    val rules = SmartRules(null, null, "tramonto sul mare", sources = setOf(SmartSource.MY_PHONE, SmartSource.COMMON), types = setOf(SmartMediaType.VIDEO))
    val back = roundTrip(rules)
    assertThat(back.text).isEqualTo("tramonto sul mare")
    assertThat(back.sources).containsExactly(SmartSource.MY_PHONE, SmartSource.COMMON)
    assertThat(back.types).containsExactly(SmartMediaType.VIDEO)
    val plain = SmartRulesText.encode(SmartRules(null, null, null))
    assertThat(plain.fields.values.filterNotNull()).isEmpty()
    assertThat(roundTrip(SmartRules(null, null, null)).sources).isEqualTo(SmartSource.ALL)
  }

  @Test
  fun leFotoDEsempioSonoAlPiuSei() {
    val ids = (1..9).map { MediaId("00000000-0000-4000-8000-%012d".format(it)) }
    val back = SmartRulesText.decode(emptyMap(), ids)
    assertThat(back.examples).hasSize(SmartLimits.MAX_EXAMPLES)
    assertThat(back.examples).isEqualTo(ids.take(SmartLimits.MAX_EXAMPLES))
  }

  @Test
  fun unaRegolaCheNonStaInUnaVoceNonSiScriveEIlCampoLoDice() {
    val many = (0 until 200).map { day("2020-01-01").plusDays(it * 3L) }.toSet()
    val encoded = SmartRulesText.encode(SmartRules(SmartPeriod.Days(many), null, "ok"))
    assertThat(encoded.ok).isFalse()
    assertThat(encoded.tooLong).containsExactly(PersonalFields.SMART_PERIOD)
    // La descrizione con un carattere che il formato rifiuta non si scrive neanche.
    assertThat(SmartRulesText.encode(SmartRules(null, null, "ciao‮mondo")).tooLong).containsExactly(PersonalFields.SMART_TEXT)
    val long = "x".repeat(PersonalValue.MAX_TEXT + 1)
    assertThat(SmartRulesText.encode(SmartRules(null, null, long)).ok).isFalse()
  }

  @Test
  fun unTestoIllegibileVaINessunaRegolaMaiUnaEccezione() {
    val fields = mapOf(
      PersonalFields.SMART_PERIOD to "r:2026-13-45..boh",
      PersonalFields.SMART_PLACE to "around:1:2",
      PersonalFields.SMART_SOURCES to "my_phone,nope",
      PersonalFields.SMART_TYPES to "",
    )
    val rules = SmartRulesText.decode(fields, emptyList())
    assertThat(rules.period).isNull()
    assertThat(rules.place).isNull()
    assertThat(rules.sources).isEqualTo(SmartSource.ALL)
    assertThat(rules.types).isEqualTo(SmartMediaType.ALL)
    val random = Random(11)
    val alphabet = "rydaroundregioncountry:.,-0123456789abcXYZ"
    repeat(3_000) {
      val text = (0 until random.nextInt(40)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
      SmartRulesText.decodePeriod(text)
      SmartRulesText.decodePlace(text)
      SmartRulesText.decode(mapOf(PersonalFields.SMART_PERIOD to text, PersonalFields.SMART_PLACE to text, PersonalFields.SMART_SOURCES to text), emptyList())
    }
  }

  @Test
  fun iLimitiDelRaggioSiFannoRispettareInLettura() {
    assertThat(SmartRulesText.decodePlace("around:4:59.0:10.0:1:Oslo")).isNull()
    assertThat(SmartRulesText.decodePlace("around:301:59.0:10.0:1:Oslo")).isNull()
    assertThat(SmartRulesText.decodePlace("around:5:91.0:10.0:1:Oslo")).isNull()
    assertThat(SmartRulesText.decodePlace("around:5:59.0:10.0:1:Oslo")).isNotNull()
  }
}
