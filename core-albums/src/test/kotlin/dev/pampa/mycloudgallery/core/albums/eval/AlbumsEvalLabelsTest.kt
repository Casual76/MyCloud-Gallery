package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

/** Le etichette dei viaggi: invarianti, formato del file (andata e ritorno, file rovinati) e la chiusura dell'elenco. Dati sintetici. */
class AlbumsEvalLabelsTest {
  private fun media(n: Int): String = FakeAlbumLibrary.id(n).value

  private fun trip(id: String = "v01", title: String? = "Oslo 2026", from: LocalDate = LocalDate.of(2026, 3, 12), to: LocalDate = LocalDate.of(2026, 3, 19)) =
    EvalTrip(id, from, to, title)

  @Test
  fun unViaggioHaINumeriDelPeriodoENonLiSvela() {
    val t = trip()
    assertThat(t.days).isEqualTo(8)
    assertThat(trip(from = LocalDate.of(2026, 3, 12), to = LocalDate.of(2026, 3, 12)).days).isEqualTo(1)
    assertThat(t.toString()).isEqualTo("EvalTrip(v01, 8 giorni)")
    val set = AlbumsLabelSet().withTrip(t).withInTrip("v01", media(1), true).withNotInTrip("v01", media(2), true)
    assertThat(set.toString()).isEqualTo("AlbumsLabelSet(1 viaggi, 1 del viaggio, 1 non del viaggio, chiuso=false)")
    assertThat(set.toString()).doesNotContain("Oslo")
  }

  @Test
  fun unViaggioNonValidoNonSiCrea() {
    assertThrows(IllegalArgumentException::class.java) { trip(id = "viaggio") }
    assertThrows(IllegalArgumentException::class.java) { trip(from = LocalDate.of(2026, 3, 19), to = LocalDate.of(2026, 3, 12)) }
    assertThrows(IllegalArgumentException::class.java) { trip(from = LocalDate.of(2024, 1, 1), to = LocalDate.of(2025, 12, 31)) }
    assertThrows(IllegalArgumentException::class.java) { trip(title = "  ") }
    assertThrows(IllegalArgumentException::class.java) { trip(title = "a\tb") }
    assertThrows(IllegalArgumentException::class.java) { trip(title = "x".repeat(EvalTrip.MAX_TITLE + 1)) }
    // Senza titolo e' un viaggio solo dell'elenco.
    assertThat(trip(title = null).title).isNull()
  }

  @Test
  fun unaFotoOEDelViaggioOnoMaiTutteEDue() {
    var set = AlbumsLabelSet().withTrip(trip())
    set = set.withInTrip("v01", media(1), true)
    assertThat(set.inTripOf("v01")).containsExactly(media(1))
    set = set.withNotInTrip("v01", media(1), true)
    assertThat(set.inTripOf("v01")).isEmpty()
    assertThat(set.notInTripOf("v01")).containsExactly(media(1))
    set = set.withInTrip("v01", setOf(media(1), media(2)), true)
    assertThat(set.notInTripOf("v01")).isEmpty()
    assertThat(set.inTripOf("v01")).containsExactly(media(1), media(2))
    set = set.withInTrip("v01", media(1), false)
    assertThat(set.inTripOf("v01")).containsExactly(media(2))
    assertThrows(IllegalArgumentException::class.java) { set.withInTrip("v09", media(1), true) }
    assertThrows(IllegalArgumentException::class.java) { set.withInTrip("v01", "non-un-id", true) }
    assertThrows(IllegalArgumentException::class.java) { AlbumsLabelSet(listOf(trip()), mapOf("v01" to setOf(media(1))), mapOf("v01" to setOf(media(1)))) }
  }

  @Test
  fun ilProssimoIdentificativoELibero() {
    var set = AlbumsLabelSet()
    assertThat(set.nextId()).isEqualTo("v01")
    set = set.withTrip(trip("v01")).withTrip(trip("v03"))
    assertThat(set.nextId()).isEqualTo("v02")
    set = set.withoutTrip("v01")
    assertThat(set.nextId()).isEqualTo("v01")
  }

  @Test
  fun alPiuSessantaViaggi() {
    var set = AlbumsLabelSet()
    repeat(AlbumsLabelSet.MAX_TRIPS) { set = set.withTrip(trip(set.nextId(), title = null)) }
    assertThrows(IllegalArgumentException::class.java) { set.withTrip(trip(set.nextId())) }
    // Sostituire uno che c'e' gia' si puo'.
    assertThat(set.withTrip(trip("v01")).trips).hasSize(AlbumsLabelSet.MAX_TRIPS)
  }

  @Test
  fun androEritornoDelFile() {
    val set = AlbumsLabelSet()
      .withTrip(trip("v01", "Oslo 2026 \\ con l'apostrofo e l'accento: città"))
      .withTrip(trip("v02", null, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 9)))
      .withInTrip("v01", setOf(media(3), media(1), media(2)), true)
      .withNotInTrip("v01", media(4), true)
      .withInTrip("v02", media(9), true)
    val text = AlbumsLabelFormat.serialize(set)
    assertThat(text).startsWith(AlbumsLabelFormat.MAGIC + "\n")
    val back = AlbumsLabelFormat.parse(text)
    assertThat(back.trips.map { it.id }).containsExactly("v01", "v02").inOrder()
    assertThat(back.trip("v01")!!.title).isEqualTo("Oslo 2026 \\ con l'apostrofo e l'accento: città")
    assertThat(back.trip("v02")!!.title).isNull()
    assertThat(back.trip("v02")!!.from).isEqualTo(LocalDate.of(2025, 8, 1))
    assertThat(back.inTripOf("v01")).containsExactly(media(1), media(2), media(3))
    assertThat(back.notInTripOf("v01")).containsExactly(media(4))
    assertThat(back.inTripOf("v02")).containsExactly(media(9))
    assertThat(back.listClosed).isFalse()
    // La serializzazione e' stabile: lo stesso insieme da' lo stesso testo.
    assertThat(AlbumsLabelFormat.serialize(back)).isEqualTo(text)
    assertThat(AlbumsLabelFormat.parse(AlbumsLabelFormat.serialize(set.closed())).listClosed).isTrue()
  }

  @Test
  fun unFileVuotoEUnInsiemeVuoto() {
    val empty = AlbumsLabelFormat.parse(AlbumsLabelFormat.serialize(AlbumsLabelSet()))
    assertThat(empty.trips).isEmpty()
  }

  @Test
  fun unFileRovinatoDaUnErroreConIlNumeroDellaRigaMaiIlContenuto() {
    val good = AlbumsLabelFormat.serialize(AlbumsLabelSet().withTrip(trip("v01", "Titolo segreto")).withInTrip("v01", media(1), true))
    fun message(text: String): String = assertThrows(IllegalArgumentException::class.java) { AlbumsLabelFormat.parse(text) }.message.orEmpty()
    assertThat(message("MCGEVAL1\nQ\tq1\toggetti\tcane\n")).contains("intestazione")
    assertThat(message("")).contains("intestazione")
    assertThat(message(good + "X\tqualcosa di privato\n")).contains("Riga 4")
    assertThat(message(good + "Y\tv01\tnon-un-uuid\n")).contains("Riga 4")
    assertThat(message(good + "V\tv02\t2026-13-45\t2026-13-46\tx\n")).contains("Riga 4")
    assertThat(message(good + "Y\tv01\n")).contains("Riga 4")
    // Nessuna riga svela il contenuto.
    for (text in listOf(good + "X\tTitolo segreto\n", good + "Y\tv01\tTitolo segreto\n")) assertThat(message(text)).doesNotContain("segreto")
    // Etichette di un viaggio che non c'e', o una foto sia del viaggio sia no: incoerente (e lo dice senza contenuto).
    assertThat(message(good + "Y\tv07\t${media(5)}\n")).contains("coerente")
    assertThat(message(good + "N\tv01\t${media(1)}\n")).contains("coerente")
    // Un viaggio doppio.
    assertThat(message(good + "V\tv01\t2026-03-12\t2026-03-19\tx\n")).contains("coerente")
  }

  @Test
  fun dopoIlConfrontoConLeProposteLElencoNonSiCambia() {
    var set = AlbumsLabelSet().withTrip(trip("v01")).withTrip(trip("v02", null, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 9)))
    set = set.closed()
    // Niente viaggi nuovi, niente viaggi tolti, niente giorni cambiati.
    assertThrows(IllegalArgumentException::class.java) { set.withTrip(trip("v03")) }
    assertThrows(IllegalArgumentException::class.java) { set.withoutTrip("v02") }
    assertThrows(IllegalArgumentException::class.java) { set.withTrip(trip("v02", null, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 3))) }
    // Si puo' dare un titolo a un viaggio che c'e' (per valutarlo come album) e segnare le foto.
    val titled = set.withTrip(trip("v02", "Un titolo", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 9))).withInTrip("v02", media(1), true)
    assertThat(titled.trip("v02")!!.title).isEqualTo("Un titolo")
    assertThat(titled.listClosed).isTrue()
    assertThat(titled.titled.map { it.id }).containsExactly("v01", "v02")
    assertThat(set.closed()).isSameInstanceAs(set)
  }
}
