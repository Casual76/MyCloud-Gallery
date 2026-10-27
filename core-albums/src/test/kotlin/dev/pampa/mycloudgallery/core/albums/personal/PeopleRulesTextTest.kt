package dev.pampa.mycloudgallery.core.albums.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import org.junit.Test

/**
 * Fase 8 (P10, D27, F7-D4): il campo personale `smart:people` -- `any:<id>,<id>` o `all:<id>,...`, gli id in base32 -- va e torna, sta sotto il tetto di una voce anche con 32
 * persone, e le regole di prima restano leggibili (un album smart della Fase 7 si decodifica uguale, senza persone). Un campo illeggibile vale "regola assente".
 */
class PeopleRulesTextTest {
  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { i -> (n * 7 + i).toByte() })

  private fun roundTrip(rules: SmartRules): SmartRules {
    val encoded = SmartRulesText.encode(rules)
    return SmartRulesText.decode(encoded.fields.filterValues { it != null }.mapValues { it.value!! }, rules.examples)
  }

  @Test
  fun leDueFormeAndataERitorno() {
    for (mode in PeopleMode.entries) {
      val rule = PeopleRule(listOf(person(1), person(2), person(3)), mode)
      val encoded = SmartRulesText.encode(SmartRules(null, null, null, people = rule))
      assertThat(encoded.ok).isTrue()
      val text = encoded.fields.getValue(PersonalFields.SMART_PEOPLE)!!
      assertThat(text).startsWith(mode.code + ":")
      assertThat(text.removePrefix(mode.code + ":").split(',')).hasSize(3)
      assertThat(roundTrip(SmartRules(null, null, null, people = rule)).people).isEqualTo(rule)
    }
  }

  @Test
  fun unaPersonaSolaEdAccantoAlleAltreRegole() {
    val rules = SmartRules(
      period = null, place = null, text = "gatto", sources = setOf(SmartSource.MY_PHONE), types = setOf(SmartMediaType.PHOTO),
      people = PeopleRule(listOf(person(9)), PeopleMode.ANY),
    )
    val back = roundTrip(rules)
    assertThat(back).isEqualTo(rules)
    assertThat(SmartRulesText.encode(rules).fields.getValue(PersonalFields.SMART_PEOPLE)).isEqualTo("any:" + person(9).base32())
  }

  @Test
  fun treDueTrentaDuePersoneStannoSottoIlTettoDiUnaVoce() {
    val rule = PeopleRule(List(PeopleRule.MAX_PEOPLE) { person(it) }, PeopleMode.ALL)
    val encoded = SmartRulesText.encode(SmartRules(null, null, null, people = rule))
    val text = encoded.fields.getValue(PersonalFields.SMART_PEOPLE)!!
    assertThat(text.length).isAtMost(PersonalValue.MAX_TEXT)
    assertThat(encoded.tooLong).isEmpty()
    assertThat(SmartRulesText.decodePeople(text)).isEqualTo(rule)
    // Con gli id in forma UUID (36 caratteri) non ci starebbe: e' per questo che il campo usa la base32.
    assertThat(4 + 32 * 37).isGreaterThan(PersonalValue.MAX_TEXT)
  }

  @Test
  fun leRegoleDiPrimaRestanoLeggibiliSenzaPersone() {
    val old = mapOf(PersonalFields.SMART_TEXT to "gatto", PersonalFields.SMART_SOURCES to "my_phone")
    val rules = SmartRulesText.decode(old, emptyList())
    assertThat(rules.people).isNull()
    assertThat(rules.text).isEqualTo("gatto")
    // Senza le persone nessun campo nuovo: una regola della Fase 7 si scrive come prima, con il campo delle persone che dice "assente".
    val encoded = SmartRulesText.encode(SmartRules(null, null, "gatto"))
    assertThat(encoded.fields.getValue(PersonalFields.SMART_PEOPLE)).isNull()
    assertThat(encoded.fields.filterValues { it != null }.keys).containsExactly(PersonalFields.SMART_TEXT)
  }

  @Test
  fun unCampoIllegibileValeRegolaAssenteMaNonRompeLeAltre() {
    val a = person(1).base32()
    val bad = listOf(
      "", "any", "any:", ":$a", "tutte:$a", "any:zzz", "any:$a,$a", "any:$a,", "all:" + List(33) { person(it).base32() }.joinToString(","), "any:" + person(1).value,
      "any:$a;${person(2).base32()}", "ANY:$a",
    )
    for (text in bad) {
      assertThat(SmartRulesText.decodePeople(text)).isNull()
      val rules = SmartRulesText.decode(mapOf(PersonalFields.SMART_PEOPLE to text, PersonalFields.SMART_TEXT to "gatto"), emptyList())
      assertThat(rules.people).isNull()
      assertThat(rules.text).isEqualTo("gatto")
    }
  }

  @Test
  fun loSmartDeiDatiPersonaliPortaIlCampoNelleRegole() {
    assertThat(PersonalFields.SMART_RULE_FIELDS).contains(PersonalFields.SMART_PEOPLE)
    assertThat(PersonalFields.SMART_PEOPLE).isEqualTo("smart:people")
  }
}
