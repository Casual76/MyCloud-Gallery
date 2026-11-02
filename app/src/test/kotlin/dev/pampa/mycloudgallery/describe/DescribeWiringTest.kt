package dev.pampa.mycloudgallery.describe

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Fase 10 (P13, "Cosa deve fare P13" di P7; D22, C438): le porte vere del descrittore. Le stesse prove di `DescribePortsContract` (core-describe) sugli adattatori di `:app`, in
 * piu' quello che solo loro sanno: **mai** il nome di una persona per un media nascosto o non visibile adesso, niente con Persone non attiva qui o con la lettura del testo
 * spenta, un errore = niente; il descrittore legato da `DescribeWiring.install` e' quello che le schermate trovano in `MediaDescribers`.
 */
class DescribeWiringTest {

  private fun id(n: Int) = MediaId(UUID(41L, n.toLong()).toString())

  private fun person(n: Int) = PersonId(UUID(42L, n.toLong()).toString())

  @After
  fun tearDown() = MediaDescribers.reset()

  /** Le persone della porta dei ricordi, finte: [named] da media a nomi, [active] = Persone attiva qui. */
  private class FakePeople(private val named: Map<MediaId, List<Pair<PersonId, String>>>, active: Boolean, private val failing: Boolean = false) : MemoryPeople {
    override val available: StateFlow<Boolean> = MutableStateFlow(active)

    override suspend fun named(): List<PersonDigest> = emptyList()

    override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> {
      if (failing) throw IllegalStateException("archivio chiuso")
      return ids.mapNotNull { id -> named[id]?.let { list -> id to list.map { it.first }.toSet() } }.toMap()
    }

    override suspend fun resolve(person: PersonId): PersonId? = person

    override suspend fun label(person: PersonId): String? = named.values.flatten().firstOrNull { it.first == person }?.second
  }

  private fun people(named: Map<MediaId, List<String>>, active: Boolean, hidden: Set<MediaId> = emptySet(), failing: Boolean = false): FacesDescribePeople {
    var n = 0
    val withIds = named.mapValues { (_, names) -> names.map { person(n++) to it } }
    val visibility = FakeVisibility(present = named.keys + hidden + (0..10).map { id(it) }, hidden = hidden)
    return FacesDescribePeople(FakePeople(withIds, active, failing), visibility, visibility)
  }

  private fun text(texts: Map<MediaId, String>, active: Boolean, failing: Boolean = false) = IndexDescribeText(MutableStateFlow(active)) { ids ->
    if (failing) throw IllegalStateException("indice chiuso")
    texts.filterKeys { it in ids }
  }

  @Test
  fun lePersone_comeLaSuiteDiContratto() = runTest {
    val named = mapOf(id(1) to listOf("Anna"), id(2) to listOf("Marco", "Luca"))
    assertThat(people(named, active = true).names(listOf(id(2), id(3))).keys).containsExactly(id(2))
    assertThat(people(named, active = false).names(listOf(id(1), id(2)))).isEmpty()
    assertThat(people(named, active = true).names(listOf(id(1), id(2))).values.flatten().none { it.isBlank() }).isTrue()
    // Ordine per nome, una volta sola.
    assertThat(people(named, active = true).names(listOf(id(2)))[id(2)]).containsExactly("Luca", "Marco").inOrder()
  }

  @Test
  fun maiUnNomePerUnNascosto_niente_suUnErrore() = runTest {
    val named = mapOf(id(1) to listOf("Anna"), id(5) to listOf("Bruno"))
    val ports = people(named, active = true, hidden = setOf(id(5)))
    assertThat(ports.names(listOf(id(1), id(5)))).containsExactly(id(1), listOf("Anna"))
    assertThat(people(named, active = true, failing = true).names(listOf(id(1)))).isEmpty()
  }

  @Test
  fun ilTesto_comeLaSuiteDiContratto_eSpentoONienteSuUnErrore() = runTest {
    val texts = mapOf(id(1) to "uno due tre quattro cinque", id(2) to "solo")
    assertThat(text(texts, active = true).firstWords(listOf(id(1), id(3)), max = 3)).containsExactly(id(1), "uno due tre")
    assertThat(text(texts, active = false).firstWords(listOf(id(1)), max = 3)).isEmpty()
    assertThat(text(mapOf(id(1) to "  a\n\tcapo  "), active = true).firstWords(listOf(id(1)), max = 5)).containsExactly(id(1), "a capo")
    assertThat(text(texts, active = true, failing = true).firstWords(listOf(id(1)), max = 3)).isEmpty()
    assertThat(text(mapOf(id(1) to "   "), active = true).firstWords(listOf(id(1)), max = 3)).isEmpty()
  }

  @Test
  fun ilDescrittoreLegatoEQuelloCheLeSchermateTrovano() {
    val describer: MediaDescriber = DescribeWiring.install(NoPlaceNames, people(emptyMap(), active = true), text(emptyMap(), active = true), A11yActivity { false })
    assertThat(MediaDescribers.shared()).isSameInstanceAs(describer)
    // Il widget e le notifiche passano la loro superficie: niente persone ne' testo (P7, D22).
    assertThat(DescribeSurface.entries.map { it.name }).doesNotContain("EXPORT")
  }
}
