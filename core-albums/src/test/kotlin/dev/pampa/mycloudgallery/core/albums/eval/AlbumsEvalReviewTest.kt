package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import java.time.LocalDate
import org.junit.Test

/** Il metodo "a pozzo" per gli album: cosa resta da giudicare dopo la corsa e dove si cercano i mancanti. Dati sintetici. */
class AlbumsEvalReviewTest {
  private fun m(n: Int): String = FakeAlbumLibrary.id(n).value

  private fun ids(r: IntRange) = r.map { m(it) }

  private val trip = EvalTrip("v01", LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 19), "Titolo")

  @Test
  fun resteDaGiudicareSoloCioCheLUtenteNonHaDetto() {
    val run = TripRun("v01", ids(1..6), ids(7..9))
    val pending = AlbumsEvalReview.pending(run, inTrip = setOf(m(1), m(2), m(7)), notInTrip = setOf(m(3), m(8)))
    assertThat(pending.sure).containsExactlyElementsIn(ids(4..6)).inOrder()
    assertThat(pending.suggested).containsExactly(m(9))
    assertThat(pending.isEmpty).isFalse()
    assertThat(AlbumsEvalReview.pending(run, ids(1..9).toSet(), emptySet()).isEmpty).isTrue()
    assertThat(pending.toString()).isEqualTo("Pending(3 sicuri, 1 suggeriti)")
  }

  @Test
  fun iGiorniAttornoSonoTreDaOgniParte() {
    val (blindFrom, blindTo) = AlbumsEvalReview.blindDays(trip)
    assertThat(blindFrom).isEqualTo(LocalDate.of(2026, 3, 12))
    assertThat(blindTo).isEqualTo(LocalDate.of(2026, 3, 19))
    val around = AlbumsEvalReview.neighbourDays(trip)
    assertThat(around).containsExactly(
      LocalDate.of(2026, 3, 9) to LocalDate.of(2026, 3, 11),
      LocalDate.of(2026, 3, 20) to LocalDate.of(2026, 3, 22),
    ).inOrder()
  }

  @Test
  fun iMancantiSonoLeFotoVicineCheLAlbumNonHaPreso() {
    val run = TripRun("v01", ids(1..3), ids(4..5))
    val pool = AlbumsEvalReview.missingPool(ids(1..8) + ids(6..6), run)
    assertThat(pool).containsExactlyElementsIn(ids(6..8)).inOrder()
    assertThat(AlbumsEvalReview.marked(ids(1..8), setOf(m(2), m(7), m(99)))).isEqualTo(2)
  }
}
