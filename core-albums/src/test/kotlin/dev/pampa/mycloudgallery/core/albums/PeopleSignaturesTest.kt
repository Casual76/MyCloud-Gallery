package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoPeopleMembership
import dev.pampa.mycloudgallery.core.albums.testing.FakePeopleMembership
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0, D27): le firme delle persone negli album smart. La regola si aggiunge senza toccare le regole di oggi (un album smart della
 * Fase 7 resta uguale), non mostra mai gli id e, dove i volti non ci sono, non classifica niente (E-F37).
 */
class PeopleSignaturesTest {

  private val marco = PersonId.random()
  private val anna = PersonId.random()

  @Test
  fun laRegolaDellePersoneEFacoltativaEMaiNeiLog() {
    val before = SmartRules(period = null, place = null, text = "gatto")
    assertThat(before.people).isNull()
    val rule = PeopleRule(listOf(marco, anna), PeopleMode.ALL)
    val withPeople = before.copy(people = rule)
    assertThat(withPeople).isNotEqualTo(before)
    assertThat(withPeople.toString()).doesNotContain(marco.value)
    assertThat(rule.toString()).isEqualTo("PeopleRule(2, all)")
    assertThrows(IllegalArgumentException::class.java) { PeopleRule(emptyList(), PeopleMode.ANY) }
    assertThrows(IllegalArgumentException::class.java) { PeopleRule(listOf(marco, marco), PeopleMode.ANY) }
    assertThrows(IllegalArgumentException::class.java) { PeopleRule(List(PeopleRule.MAX_PEOPLE + 1) { PersonId.random() }, PeopleMode.ANY) }
  }

  @Test
  fun leClassiSeguonoTutteOUnaQualunque() = runTest {
    val a = MediaId.random()
    val b = MediaId.random()
    val c = MediaId.random()
    val fake = FakePeopleMembership().sure(marco, listOf(a, b)).sure(anna, listOf(b)).likely(anna, listOf(a, c))
    val all = fake.classify(PeopleRule(listOf(marco, anna), PeopleMode.ALL), listOf(a, b, c))
    assertThat(all).containsExactly(a, PeopleClass.LIKELY, b, PeopleClass.SURE)
    val any = fake.classify(PeopleRule(listOf(marco, anna), PeopleMode.ANY), listOf(a, b, c))
    assertThat(any).containsExactly(a, PeopleClass.SURE, b, PeopleClass.SURE, c, PeopleClass.LIKELY)
    fake.available.value = false
    assertThat(fake.classify(PeopleRule(listOf(marco), PeopleMode.ANY), listOf(a))).isEmpty()
    assertThat(NoPeopleMembership.available.value).isFalse()
    assertThat(NoPeopleMembership.classify(PeopleRule(listOf(marco), PeopleMode.ANY), listOf(a))).isEmpty()
  }
}
