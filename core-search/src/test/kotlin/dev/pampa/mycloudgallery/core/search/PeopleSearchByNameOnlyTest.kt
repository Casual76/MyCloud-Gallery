package dev.pampa.mycloudgallery.core.search

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import java.lang.reflect.Method
import org.junit.Test

/**
 * Fase 8 (P10, D26): **si cerca per nome, mai per somiglianza di un volto.** La porta delle persone non prende ne' un vettore ne' un volto ne' una chiave di
 * contenuto: le sue firme parlano solo di persone con un nome (`PersonOption`) e di media; un chip "Persona", un suggerimento, la richiesta di una ricerca
 * non portano vettori. Nessuna ricerca libera "trova questo volto" (minaccia "riconoscimento usato per cercare chiunque"). Mai nomi o id nei log (D37).
 */
class PeopleSearchByNameOnlyTest {

  /** Tipi che trasportano un volto, un vettore o un posto di un volto: non devono comparire in nessuna firma della ricerca per persona. */
  private val faceCarriers = setOf(
    "float[]", "short[]", "byte[]", "FaceLocator", "FaceRef", "FaceBox", "FaceTier", "ContentKey", "EmbeddingSpace", "FaceSpaceCode", "ShortArray", "FloatArray",
  )

  private fun typeNames(method: Method): List<String> = (method.parameterTypes.toList() + method.returnType).map { it.simpleName }

  @Test
  fun laPortaDellePersoneNonPrendeNeVettoriNeVolti() {
    val methods = PeopleLookup::class.java.methods.filter { !it.isSynthetic }
    assertThat(methods.map { it.name }).containsAtLeast("dictionary", "cells", "getAvailable")
    for (method in methods) {
      assertWithMessage("${method.name}: ${typeNames(method)}").that(typeNames(method).filter { it in faceCarriers }).isEmpty()
    }
    // E' il contratto a tre voci di P0 (piu' l'avanzamento del primo calcolo): una voce in piu' va decisa, non aggiunta di passaggio. Correzioni D, I2: `visibleAmong` e' la quinta,
    // decisa e dichiarata (quali persone di un chip gia' in Cerca si possono ancora cercare): prende e da' solo id di persone.
    assertThat(methods.map { it.name }.toSet()).containsExactly("getAvailable", "getProgressPercent", "dictionary", "cells", "visibleAmong")
  }

  @Test
  fun ilChipLaScelta_IlSuggerimento_ELaRichiestaNonPortanoVettori() {
    for (type in listOf(SearchChip.Person::class.java, PersonOption::class.java, PersonEntry::class.java, Suggestion.PersonSuggestion::class.java, SearchRequest::class.java)) {
      val fields = type.declaredFields.map { it.type.simpleName }
      assertWithMessage(type.simpleName).that(fields.filter { it in faceCarriers }).isEmpty()
    }
  }

  @Test
  fun nessunNomeNeUnIdNeiMessaggi_D37() {
    val id = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 7 })
    val option = PersonOption(listOf(id), PeopleMode.ANY, "Marco Quarzo")
    val chip = SearchChip.Person(option, listOf(ChipAlternative(SearchChip.Word("marco quarzo", false))), "marco quarzo")
    val texts = listOf(
      option.toString(),
      chip.toString(),
      PersonEntry("marco quarzo", id, "Marco Quarzo", 3).toString(),
      PeopleDictionary(listOf(PersonEntry("marco quarzo", id, "Marco Quarzo", 3))).toString(),
      Suggestion.PersonSuggestion(option).toString(),
      ChipAlternative(SearchChip.Word("marco", false)).toString(),
    )
    for (text in texts) {
      assertThat(text.lowercase()).doesNotContain("marco")
      assertThat(text.lowercase()).doesNotContain("quarzo")
      assertThat(text).doesNotContain(id.value)
    }
  }
}
