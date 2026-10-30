package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.PersonName
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto D, nella parte di `:app`:
 *  - **RI-1** e **R9**: il dizionario della ricerca per persona, costruito sull'indice vero, **salta** un nome senza lettere ne' cifre ("❤", "?", un nome fatto
 *    solo di giunti) senza spegnere gli altri; un nome in cirillico o in cinese si cerca;
 *  - **I2**: `visibleAmong` segue gli alias e guarda il dizionario di adesso (una persona nascosta non vale piu');
 *  - **R10**: le celle **lanciano** se i volti non si leggono adesso ("non so" non vale "nessuna foto");
 *  - **GE-1**: le modifiche in coda partono dopo 30 s di calma, non al giro dell'indice.
 * **Solo nomi e id inventati.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacesCorrectionsDTest {

  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { n.toByte() })

  private class FakeIndex(
    val names: MutableList<PersonName>,
    val aliases: Map<PersonId, PersonId> = emptyMap(),
    override val available: MutableStateFlow<Boolean> = MutableStateFlow(true),
  ) : PeopleIndex {
    var unreadable = false

    override suspend fun dictionary(): List<PersonName> = names.toList()

    override suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel> {
      check(!unreadable) { "L'archivio dei volti non si apre adesso." }
      return emptyMap()
    }

    override fun resolve(id: PersonId): PersonId? = aliases[id] ?: id.takeIf { p -> names.any { it.id == p } }
  }

  private object NoCells : PeopleCells {
    override suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = emptyMap()

    override suspend fun keysOf(media: MediaId): Set<ContentKey> = emptySet()
  }

  private fun lookup(index: PeopleIndex): AppPeopleLookup {
    val visibility = FakeVisibility()
    return AppPeopleLookup(index, NoCells, PeopleVisibility(visibility, visibility))
  }

  // --- RI-1, R9 ------------------------------------------------------------------------------------------------------------------

  @Test
  fun unNomeNonCercabileNonSpegneLaRicercaPerPersonaDegliAltriNomi() = runTest {
    val zwjOnly = "‍️‍"
    val names = listOf("❤", "?", zwjOnly, "Анна", "李明", "Marco Quarzo").mapIndexed { i, name -> PersonName(person(i + 1), name, 5 + i) }
    val dictionary = lookup(FakeIndex(names.toMutableList())).dictionary()
    // Prima: `PersonEntry(normalize("❤"))` lanciava dentro il `map` e **tutti** i nomi sparivano (il dizionario diventava vuoto).
    assertThat(dictionary.entries.map { it.label }).containsExactly("Анна", "李明", "Marco Quarzo")
    val parser = ItalianQueryParser()
    for (typed in listOf("foto di Marco Quarzo", "Анна", "李明 al mare")) {
      val chips = parser.parse(typed, LocalDate.of(2026, 10, 21), PlaceDictionary.EMPTY, dictionary).chips.filterIsInstance<SearchChip.Person>()
      assertThat(chips).hasSize(1)
    }
  }

  // --- I2 --------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaPersonaNascostaNonValePiuMaUnaUnitaAUnAltraSi() = runTest {
    val marco = person(1)
    val anna = person(2)
    val unita = person(3)
    val nascosta = person(4)
    // Nel dizionario di adesso: Marco e Anna. `unita` e' un alias di Marco; `nascosta` non c'e' (nascosta, dimenticata o senza foto da mostrare).
    val index = FakeIndex(mutableListOf(PersonName(marco, "Marco", 4), PersonName(anna, "Anna", 3)), aliases = mapOf(unita to marco))
    val lookup = lookup(index)
    assertThat(lookup.visibleAmong(listOf(marco, anna, unita, nascosta))).containsExactly(marco, anna, unita)
    // Persone non attiva qui: nessuna si puo' cercare.
    index.available.value = false
    assertThat(lookup.visibleAmong(listOf(marco))).isEmpty()
  }

  // --- R10 -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun leCelleLanciaSeIVoltiNonSiLeggonoAdesso() = runTest {
    val marco = person(1)
    val option = PersonOption(listOf(marco), PeopleMode.ANY, "Marco")
    val index = FakeIndex(mutableListOf(PersonName(marco, "Marco", 4)))
    val lookup = lookup(index)
    // Un vuoto vero: Marco non ha celle mostrabili.
    assertThat(lookup.cells(option, includeLikely = false)).isEmpty()
    // L'archivio non si apre (il Keystore non risponde): "non so", non "nessuna foto".
    index.unreadable = true
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { lookup.cells(option, includeLikely = true) } }
    // Persone non attiva qui: lo stesso.
    index.unreadable = false
    index.available.value = false
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { lookup.cells(option, includeLikely = false) } }
  }

  // --- GE-1 --------------------------------------------------------------------------------------------------------------------------

  private class ScriptedPublisher : FacesPublisher {
    override val pending = MutableStateFlow(0)
    val flushes = mutableListOf<FlushReason>()
    var answers = ArrayDeque<FacesPublishOutcome>()
    var fail: Exception? = null

    override suspend fun stage(change: FacesChange) {
      pending.value += 1
    }

    override suspend fun flush(reason: FlushReason): FacesPublishOutcome {
      flushes += reason
      fail?.let { throw it }
      val outcome = answers.removeFirstOrNull() ?: FacesPublishOutcome.Published(1, 1)
      if (outcome is FacesPublishOutcome.Published) pending.value = 0
      return outcome
    }
  }

  @Test
  fun unaModificaInCodaParteDopo30SecondiDiCalmaNonAlGiroDellIndice() = runTest {
    val publisher = ScriptedPublisher()
    val flusher = FacesPendingFlusher(backgroundScope, publisher)
    backgroundScope.launch { flusher.watch() }
    runCurrent()
    // Un nome dato: la coda ha una modifica. Prima nessuno guardava la coda e partiva solo al giro dell'indice (15 minuti o 6 ore).
    publisher.pending.value = 1
    runCurrent()
    advanceTimeBy(30.seconds)
    runCurrent()
    assertThat(publisher.flushes).isEmpty()
    advanceTimeBy(2.seconds)
    runCurrent()
    assertThat(publisher.flushes).containsExactly(FlushReason.DEBOUNCE)
    assertThat(publisher.pending.value).isEqualTo(0)
    // Una modifica dopo: di nuovo 30 secondi di calma e una pubblicazione sola.
    publisher.pending.value = 1
    runCurrent()
    advanceTimeBy(31.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(2)
  }

  @Test
  fun unaTestaTroppoPrestoSiRiprovaQuandoIlPubblicatoreLoDiceEUnCancelloChiusoSiFerma() = runTest {
    val publisher = ScriptedPublisher().also {
      it.answers = ArrayDeque(listOf(FacesPublishOutcome.TooSoon(20_000L), FacesPublishOutcome.Failed))
    }
    backgroundScope.launch { FacesPendingFlusher(backgroundScope, publisher).watch() }
    publisher.pending.value = 1
    runCurrent()
    advanceTimeBy(32.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(1)
    // "Troppo presto": riprova fra i 20 secondi che il pubblicatore chiede.
    advanceTimeBy(18.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(1)
    advanceTimeBy(2.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(2)
    // "Fallito": riprova fra 30 secondi, e la terza volta (pubblicata) la coda e' vuota.
    advanceTimeBy(31.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(3)
    assertThat(publisher.pending.value).isEqualTo(0)
  }

  @Test
  fun conUnCancelloChiusoOUnErroreNonSiInsisteDaQui() = runTest {
    val blocked = ScriptedPublisher().also { it.answers = ArrayDeque(listOf(FacesPublishOutcome.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))) }
    backgroundScope.launch { FacesPendingFlusher(backgroundScope, blocked).watch() }
    blocked.pending.value = 1
    runCurrent()
    advanceTimeBy(600.seconds)
    runCurrent()
    // La recinzione (D34) non si apre da un nome dato: una prova sola, poi ci pensano il giro dell'indice, la fine di un'unita' e la notte.
    assertThat(blocked.flushes).hasSize(1)

    val errors = mutableListOf<Exception>()
    val broken = ScriptedPublisher().also { it.fail = IllegalStateException("guasto") }
    backgroundScope.launch { FacesPendingFlusher(backgroundScope, broken, onError = { errors += it }).watch() }
    broken.pending.value = 1
    runCurrent()
    advanceTimeBy(600.seconds)
    runCurrent()
    assertThat(broken.flushes).hasSize(1)
    assertThat(errors).hasSize(1)
  }


  /**
   * Il montaggio guarda la coda: senza questo legame `FacesPendingFlusher` esisterebbe e nessuno lo userebbe (come prima, quando `FlushReason.DEBOUNCE` non aveva chiamanti per i
   * volti). Una prova sul sorgente, come `FacesFenceSourcesTest`: l'app vera non si puo' far girare 30 secondi dentro un test.
   */
  @Test
  fun ilMontaggioGuardaLaCodaDelPubblicatore() {
    val graph = java.io.File("src/main/kotlin/dev/pampa/mycloudgallery/faces/FacesGraph.kt").readText()
    assertThat(graph).contains("FacesPendingFlusher(live.scope, live.publisher")
    assertThat(graph).contains(".watch()")
  }
  @Test
  fun iTentativiHannoUnTetto() = runTest {
    val publisher = ScriptedPublisher().also { it.answers = ArrayDeque(List(100) { FacesPublishOutcome.Failed }) }
    backgroundScope.launch { FacesPendingFlusher(backgroundScope, publisher).watch() }
    publisher.pending.value = 1
    runCurrent()
    advanceTimeBy(7200.seconds)
    runCurrent()
    assertThat(publisher.flushes).hasSize(FacesPendingFlusher.MAX_ATTEMPTS)
  }

}
