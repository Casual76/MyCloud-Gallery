package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPixels
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryVectors
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il raccoglitore dei segnali (D6, D7, E-MEM15, E-MEM16): chiede alle porte una volta sola e solo cio' che serve, le misure dei pixel solo per i
 * 200 finalisti, e una porta che manca o fallisce toglie solo il suo segnale. Poi la scelta intera, dalle porte al ricordo.
 */
class PickSignalsLoaderTest {

  private val cells = Cells()
  private val spec = MemoryRules.spec(MemoryKind.ON_THIS_DAY)
  private val picker = MemoryPhotoPicker()

  private suspend fun load(
    candidates: List<CandidateCell>,
    vectors: MemoryVectors = FakeMemoryVectors(),
    pixels: MemoryPixels = FakeMemoryPixels(),
    albums: MemoryAlbumSignals = FakeMemoryAlbumSignals(),
    people: MemoryPeople = FakeMemoryPeople(),
    choices: MemoriesChoices = MemoriesChoices.NONE,
    budget: Duration = 5.seconds,
  ) = PickSignalsLoader(vectors, FakeMemoryLookAlikes(emptySet()), pixels, albums, people).load(candidates, spec, choices, budget)

  /** Ai candidati gia' esclusi da D5 non si chiede niente: nessun vettore, nessuna misura, per uno screenshot. */
  @Test
  fun nonChiedeDegliEsclusi() = runTest {
    val good = cells.spaced(8)
    val shots = List(5) { cells.cell(500 + it * 10, source = MediaSource.SCREENSHOT) }
    val vectors = FakeMemoryVectors()
    val pixels = FakeMemoryPixels()
    load(good + shots, vectors = vectors, pixels = pixels)
    assertThat(vectors.asked).isEqualTo(8)
    assertThat(pixels.asked).isEqualTo(8)
  }

  /** Senza candidati utili nessuna porta viene disturbata. */
  @Test
  fun senzaCandidatiNonDisturbaNessunaPorta() = runTest {
    val shots = List(5) { cells.cell(it * 10, source = MediaSource.SCREENSHOT) }
    val vectors = FakeMemoryVectors()
    val pixels = FakeMemoryPixels()
    val signals = load(shots, vectors = vectors, pixels = pixels)
    assertThat(vectors.calls).isEqualTo(0)
    assertThat(pixels.calls).isEqualTo(0)
    assertThat(signals.vectors).isEmpty()
  }

  /** D6: le misure dei pixel (decodifiche) solo per i 200 finalisti col punteggio piu' alto, mai per tutti. */
  @Test
  fun lePixelSoloPerIDuecentoFinalisti() = runTest {
    val favorites = List(5) { cells.cell(it * 10, flags = CellFlags.FAVORITE) }
    val rest = List(295) { cells.cell(1_000 + it * 10, on = cells.day.plusDays((it / 100).toLong())) }
    val asked = ArrayList<MediaId>()
    var budgetSeen: Duration? = null
    val pixels = MemoryPixels { ids, budget ->
      asked += ids
      budgetSeen = budget
      emptyMap()
    }
    load(favorites + rest, pixels = pixels, budget = 7.seconds)
    assertThat(asked).hasSize(PickSignalsLoader.PIXEL_FINALISTS)
    assertThat(asked).containsAtLeastElementsIn(favorites.map { it.id })
    assertThat(budgetSeen).isEqualTo(7.seconds)
  }

  /** Una porta non puo' allargare l'insieme: le risposte su id non chiesti si buttano. */
  @Test
  fun unaPortaNonAllargaLInsieme() = runTest {
    val good = cells.spaced(8)
    val stranger = MediaId("00000000-0000-0000-0000-00000000dead")
    val greedyVectors = MemoryVectors { ids -> ids.associateWith { SyntheticVectors.concept(1) } + (stranger to SyntheticVectors.concept(2)) }
    val greedyPixels = MemoryPixels { ids, _ -> ids.associateWith { PixelProbe(300f, 0.5f) } + (stranger to PixelProbe(1f, 0.5f)) }
    val greedyAlbums = MemoryAlbumSignals { ids -> ids.toSet() + stranger }
    val signals = load(good, vectors = greedyVectors, pixels = greedyPixels, albums = greedyAlbums)
    assertThat(signals.vectors).doesNotContainKey(stranger)
    assertThat(signals.pixels).doesNotContainKey(stranger)
    assertThat(signals.albums).doesNotContain(stranger)
  }

  /** Un vettore con numeri non finiti si scarta (meglio nessun segnale che uno rotto), uno vuoto pure. */
  @Test
  fun unVettoreRottoSiScarta() = runTest {
    val good = cells.spaced(8)
    val vectors = FakeMemoryVectors(
      mutableMapOf(
        good[0].id to floatArrayOf(1f, Float.NaN, 0f),
        good[1].id to floatArrayOf(1f, Float.POSITIVE_INFINITY),
        good[2].id to FloatArray(0),
        good[3].id to SyntheticVectors.concept(4),
      ),
    )
    val signals = load(good, vectors = vectors)
    assertThat(signals.vectors.keys).containsExactly(good[3].id)
  }

  /** Una porta che fallisce toglie solo il suo segnale; la scelta continua con i ripieghi. */
  @Test
  fun unaPortaCheFallisceToglieSoloIlSuoSegnale() = runTest {
    val good = cells.spaced(8)
    val broken = MemoryVectors { error("database guasto") }
    val brokenPixels = MemoryPixels { _, _ -> error("miniature illeggibili") }
    val brokenAlbums = MemoryAlbumSignals { error("album guasti") }
    val signals = load(good, vectors = broken, pixels = brokenPixels, albums = brokenAlbums, people = FakeMemoryPeople().person(person(1), "Anna", good.take(2).map { it.id }))
    assertThat(signals.vectors).isEmpty()
    assertThat(signals.pixels).isEmpty()
    assertThat(signals.albums).isEmpty()
    assertThat(signals.people.keys).containsExactly(good[0].id, good[1].id)
  }

  /** Una cancellazione passa sempre, da qualunque porta. */
  @Test
  fun laCancellazionePassa() = runTest {
    val good = cells.spaced(8)
    val cancelled = MemoryVectors { throw CancellationException("annullato") }
    var thrown = false
    try {
      load(good, vectors = cancelled)
    } catch (_: CancellationException) {
      thrown = true
    }
    assertThat(thrown).isTrue()
  }

  /** Le persone falliscono chiuse: con una scelta "Mai" o "Mostra meno" un guasto non si trasforma in "nessuna persona". */
  @Test
  fun lePersoneFallisconoChiuse() = runTest {
    val good = cells.spaced(8)
    val broken = object : MemoryPeople by FakeMemoryPeople() {
      override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> = error("volti non leggibili")
    }
    val withChoice = MemoriesChoices(people = mapOf(person(1) to MemoryLessLevel.NEVER))
    var thrown: Throwable? = null
    try {
      load(good, people = broken, choices = withChoice)
    } catch (e: IllegalStateException) {
      thrown = e
    }
    assertThat(thrown).isNotNull()
    // Senza scelte sulle persone il guasto vale "nessuna persona".
    assertThat(load(good, people = broken).people).isEmpty()
  }

  /** Il budget dei pixel arriva alla porta e il fake lo rispetta: meno budget, meno misure, la scelta funziona lo stesso. */
  @Test
  fun ilBudgetLimitaLeMisureNonLaScelta() = runTest {
    val good = cells.spaced(10)
    val probes = good.associate { it.id to PixelProbe(300f, 0.5f) }.toMutableMap()
    val pixels = FakeMemoryPixels(probes, costPerId = 1.seconds)
    val signals = load(good, pixels = pixels, budget = 3.seconds)
    assertThat(signals.pixels).hasSize(3)
    assertThat(picker.pick(good, spec, signals).accepted).isTrue()
  }

  // --- Dalle porte al ricordo ---

  /** La scelta intera dalle porte: i doppioni per i vettori, la foto mossa penalizzata, la persona "Mai" fuori, gli album che contano. */
  @Test
  fun dalLePorteAlRicordo() = runTest {
    val photos = cells.spaced(12, from = 600, every = 20)
    val twin = cells.cell(1_000, flags = CellFlags.FAVORITE)
    val twinOf = photos[0]
    val never = cells.cell(1_020, flags = CellFlags.FAVORITE)
    val inAlbum = cells.cell(1_040)
    val blurry = cells.cell(1_060, flags = CellFlags.FAVORITE)
    val all = photos + twin + never + inAlbum + blurry
    val vectors = FakeMemoryVectors(all.withIndex().associate { (i, c) -> c.id to SyntheticVectors.concept(300L + i) }.toMutableMap())
    vectors.vectors[twin.id] = vectors.vectors.getValue(twinOf.id)
    val pixels = FakeMemoryPixels(mutableMapOf(blurry.id to PixelProbe(3f, 0.5f)))
    val albums = FakeMemoryAlbumSignals(mutableSetOf(inAlbum.id))
    val anna = person(1)
    val people = FakeMemoryPeople().person(anna, "Anna", listOf(never.id))
    val choices = MemoriesChoices(people = mapOf(anna to MemoryLessLevel.NEVER))
    val signals = load(all, vectors = vectors, pixels = pixels, albums = albums, people = people, choices = choices)
    val result = picker.pick(all, spec, signals)
    assertThat(result.accepted).isTrue()
    // Il gemello preferito prende il posto del primo scatto; la persona "Mai" non c'e'.
    assertThat(result.members).contains(twin.id)
    assertThat(result.members).doesNotContain(twinOf.id)
    assertThat(result.members).doesNotContain(never.id)
    assertThat(result.dropped[DropReason.NEVER_PERSON]).isEqualTo(1)
    assertThat(result.dropped[DropReason.DUPLICATE]).isEqualTo(1)
    assertThat(signals.albums).containsExactly(inAlbum.id)
    assertThat(signals.pixels.keys).containsExactly(blurry.id)
  }

  /** Senza nessuna porta (null-object) la scelta funziona lo stesso, con la finestra di 3 minuti. */
  @Test
  fun senzaPorteLaSceltaFunziona() = runTest {
    val photos = cells.spaced(10, from = 100, every = 20)
    val close = cells.cell(101)
    val signals = PickSignalsLoader().load(photos + close, spec, MemoriesChoices.NONE, 5.seconds)
    assertThat(signals.vectors).isEmpty()
    assertThat(signals.lookAlike).isNull()
    val result = picker.pick(photos + close, spec, signals)
    assertThat(result.accepted).isTrue()
    assertThat(result.dropped).containsExactly(DropReason.DUPLICATE, 1)
  }
}
