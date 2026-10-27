package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryTripMemories
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto della porta dei viaggi per i ricordi (Fase 9, P0; `docs/fase9-progetto.md` D12, E-MEM13, E-H8, §5.2). Gira sul
 * riferimento (`InMemoryTripMemories`) e, da P8, sulla porta vera sopra `TripReader`: il mondo ([TripWorld]) dice quali viaggi esistono
 * davvero, nasconde media e scarta proposte come la Fase 7; le prove guardano solo cio' che la porta risponde.
 */
abstract class TripMemoriesContract {

  /** Il mondo di prova: viaggi ed eventi rilevati, la visibilita' e "Non mi interessa". */
  interface TripWorld {
    /** I viaggi e gli eventi del mondo, tutti (anche quelli che la porta deve escludere), con tutti i loro media. */
    val all: List<TripDigest>

    val subject: TripMemories

    /** Nasconde il media [id] (Nascosti). */
    suspend fun hide(id: MediaId)

    /** "Non mi interessa" sulla proposta [trip] (la voce `trip_dismissed`, con il giorno d'inizio per un viaggio). */
    suspend fun dismiss(trip: TripDigest)

    /** Se [id] si puo' mostrare adesso. */
    suspend fun visible(id: MediaId): Boolean
  }

  /** Un mondo con almeno quattro viaggi ed eventi finiti prima di [today] (uno accettato come album) e uno in corso. */
  protected abstract fun world(today: LocalDate): TripWorld

  private val today = LocalDate.of(2026, 10, 27)

  @Test
  fun soloViaggiFinitiPrimaDiOggiDalPiuRecente() = runTest {
    val world = world(today)
    val past = world.subject.past(today, 10.seconds)
    assertThat(past).isNotEmpty()
    for (trip in past) assertWithMessage("$trip").that(trip.days.to.isBefore(today)).isTrue()
    assertThat(past.map { it.days.from }).isInOrder(compareByDescending<LocalDate> { it })
    // Il viaggio in corso non c'e'.
    val ongoing = world.all.filter { !it.days.to.isBefore(today) }
    assertThat(ongoing).isNotEmpty()
    assertThat(past.map { it.id }).containsNoneIn(ongoing.map { it.id })
  }

  /** E-H8: solo media visibili; un viaggio con tutti i media nascosti non c'e' piu' (E-H7). */
  @Test
  fun soloMediaVisibiliEUnViaggioTuttoNascostoSparisce() = runTest {
    val world = world(today)
    val first = world.subject.past(today, 10.seconds)
    val victim = first.first()
    world.hide(victim.members.first())
    val after = world.subject.past(today, 10.seconds)
    for (trip in after) for (m in trip.members) assertWithMessage("membro nascosto in $trip").that(world.visible(m)).isTrue()
    assertThat(after.single { it.id == victim.id }.members).doesNotContain(victim.members.first())
    for (m in victim.members) world.hide(m)
    assertThat(world.subject.past(today, 10.seconds).map { it.id }).doesNotContain(victim.id)
  }

  /** E-MEM13: "Non mi interessa" esclude il viaggio per impronta; per un viaggio vale anche il giorno d'inizio (allungato dopo). */
  @Test
  fun iViaggiScartatiNonArrivanoMai() = runTest {
    val world = world(today)
    val past = world.subject.past(today, 10.seconds)
    val trip = past.first { it.kind == TripKind.TRIP }
    world.dismiss(trip)
    val after = world.subject.past(today, 10.seconds)
    assertThat(after.map { it.id }).doesNotContain(trip.id)
    assertThat(after.filter { it.kind == TripKind.TRIP }.map { it.days.from }).doesNotContain(trip.days.from)
    // Gli altri restano.
    assertThat(after.map { it.id }).containsAtLeastElementsIn(past.map { it.id } - trip.id)
  }

  /** Accettati compresi (con l'album, per "Apri l'album"); un evento ha il quarto d'ora d'inizio, un viaggio no. */
  @Test
  fun accettatiCompresiEDatiGiusti() = runTest {
    val past = world(today).subject.past(today, 10.seconds)
    assertThat(past.any { it.album != null }).isTrue()
    for (trip in past) {
      assertThat(trip.members).isNotEmpty()
      assertThat(trip.members).containsNoDuplicates()
      if (trip.kind == TripKind.TRIP) assertThat(trip.startQuarter).isNull()
      assertThat(trip.toString()).doesNotContain(trip.label)
    }
  }

  /** Un tempo finito non e' un errore: si risponde con quello che c'e', sempre dentro le regole. */
  @Test
  fun aBudgetFinitoNessunErrore() = runTest {
    val world = world(today)
    val all = world.subject.past(today, 10.seconds).map { it.id }.toSet()
    val quick = world.subject.past(today, kotlin.time.Duration.ZERO)
    assertThat(all).containsAtLeastElementsIn(quick.map { it.id })
  }
}

/** La suite sul riferimento (§5.2). */
class InMemoryTripMemoriesContractTest : TripMemoriesContract() {
  override fun world(today: LocalDate): TripWorld {
    val media = List(40) { MediaId.random() }
    fun digest(kind: TripKind, from: LocalDate, to: LocalDate, members: List<MediaId>, album: AlbumId? = null, quarter: Int? = null): TripDigest {
      val days = LocalDayRange(from, to)
      val places = listOf(3_000_000 + members.size)
      return TripDigest(TripId.of(kind, days, places), kind, days, "Viaggio sintetico ${from.year}", places, members, album, quarter)
    }
    val all = listOf(
      digest(TripKind.TRIP, today.minusYears(1).minusDays(2), today.minusYears(1).plusDays(3), media.subList(0, 8), AlbumId.random()),
      digest(TripKind.TRIP, today.minusYears(3), today.minusYears(3).plusDays(6), media.subList(8, 18)),
      digest(TripKind.EVENT, today.minusMonths(14), today.minusMonths(14), media.subList(18, 26), quarter = 60),
      digest(TripKind.TRIP, today.minusDays(40), today.minusDays(35), media.subList(26, 34)),
      digest(TripKind.TRIP, today.minusDays(2), today, media.subList(34, 40)),
    )
    val visibility = FakeVisibility(present = media.toSet())
    val subject = InMemoryTripMemories(all, visibility)
    return object : TripWorld {
      override val all: List<TripDigest> = all
      override val subject: TripMemories = subject

      override suspend fun hide(id: MediaId) {
        visibility.hidden += id
      }

      override suspend fun dismiss(trip: TripDigest) = subject.dismiss(trip)

      override suspend fun visible(id: MediaId): Boolean = id in visibility.visible(listOf(id))
    }
  }
}
