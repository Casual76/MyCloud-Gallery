package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalTime
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeWindowAndNightTest {

  private val night = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))
  private val early = TimeWindow(LocalTime.of(1, 0), LocalTime.of(6, 0))

  @Test
  fun fasciaACavalloDellaMezzanotte_confiniEsatti() {
    assertThat(night.crossesMidnight).isTrue()
    assertThat(night.contains(LocalTime.of(22, 59, 59))).isFalse()
    assertThat(night.contains(LocalTime.of(23, 0, 0))).isTrue() // l'inizio e' incluso
    assertThat(night.contains(LocalTime.of(23, 59, 59))).isTrue()
    assertThat(night.contains(LocalTime.MIDNIGHT)).isTrue() // mezzanotte in punto
    assertThat(night.contains(LocalTime.of(0, 0, 1))).isTrue()
    assertThat(night.contains(LocalTime.of(6, 59, 59))).isTrue()
    assertThat(night.contains(LocalTime.of(7, 0, 0))).isFalse() // la fine e' esclusa
    assertThat(night.contains(LocalTime.NOON)).isFalse()
  }

  @Test
  fun fasciaNormale_confiniEsatti() {
    assertThat(early.crossesMidnight).isFalse()
    assertThat(early.contains(LocalTime.of(0, 59, 59))).isFalse()
    assertThat(early.contains(LocalTime.of(1, 0))).isTrue()
    assertThat(early.contains(LocalTime.of(5, 59, 59))).isTrue()
    assertThat(early.contains(LocalTime.of(6, 0))).isFalse()
    assertThat(early.contains(LocalTime.MIDNIGHT)).isFalse()
  }

  @Test
  fun duratadellaFascia() {
    assertThat(night.length).isEqualTo(Duration.ofHours(8))
    assertThat(early.length).isEqualTo(Duration.ofHours(5))
    assertThat(TimeWindow(LocalTime.of(22, 0), LocalTime.of(8, 0)).length).isEqualTo(Duration.ofHours(10))
  }

  @Test
  fun inizioEFineUguali_nonSonoAmmessi() {
    assertThrows(IllegalArgumentException::class.java) { TimeWindow(LocalTime.NOON, LocalTime.NOON) }
  }

  @Test
  fun prossimoInizio_oggiOdomani() {
    // Fuori fascia di giorno: si apre stasera.
    assertThat(night.nextStart(at(12))).isEqualTo(at(23))
    // Appena dopo la chiusura: stasera.
    assertThat(night.nextStart(at(7))).isEqualTo(at(23))
    // Una fascia normale gia' chiusa oggi: si riapre domani.
    assertThat(early.nextStart(at(12))).isEqualTo(at(1, day = 14))
    // Prima dell'apertura di oggi.
    assertThat(early.nextStart(at(0, 30))).isEqualTo(at(1))
  }

  @Test
  fun testoDellaFascia() {
    assertThat(night.toString()).isEqualTo("23:00–07:00")
  }

  // --- Derivazione della fascia notte dall'orario del NAS ----------------------------------------------

  @Test
  fun nasAcceso24Ore_valeLaNotteIdeale() {
    val choice = NightWindows.derive(NasSchedule(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT))
    assertThat(choice.window).isEqualTo(NightWindows.DEFAULT)
    assertThat(choice.overlapsNas).isTrue()
  }

  @Test
  fun orarioNasSconosciuto_vaLaNotteIdeale() {
    assertThat(NightWindows.derive(null)).isEqualTo(NightWindows.Choice(NightWindows.DEFAULT, true))
  }

  @Test
  fun nasOggi0800_2200_nonSiSovrappone_ripiegaSullaIdealeESegnalaNasSpento() {
    // La situazione di oggi (docs/decisioni.md): il NAS e' spento proprio di notte (E-R5).
    val choice = NightWindows.derive(NasSchedule(LocalTime.of(8, 0), LocalTime.of(22, 0)))
    assertThat(choice.window).isEqualTo(NightWindows.DEFAULT)
    assertThat(choice.overlapsNas).isFalse()
  }

  @Test
  fun nasAllungatoFinoAlle02_laFasciaSiRestringeAllaSovrapposizione() {
    val choice = NightWindows.derive(NasSchedule(LocalTime.of(7, 0), LocalTime.of(2, 0)))
    assertThat(choice.window).isEqualTo(TimeWindow(LocalTime.of(23, 0), LocalTime.of(2, 0)))
    assertThat(choice.overlapsNas).isTrue()
  }

  @Test
  fun intersezioneConDuePezzi_vinceIlPiuLungo() {
    // NAS 06:00-23:00 contro notte 23:00-07:00: restano solo 06:00-07:00 (1 ora).
    val one = NightWindows.derive(NasSchedule(LocalTime.of(6, 0), LocalTime.of(23, 0)))
    assertThat(one.window).isEqualTo(TimeWindow(LocalTime.of(6, 0), LocalTime.of(7, 0)))

    // Notte ideale 22:00-08:00 contro NAS 06:00-23:00: pezzi 22:00-23:00 (1 h) e 06:00-08:00 (2 h).
    val ideal = TimeWindow(LocalTime.of(22, 0), LocalTime.of(8, 0))
    val two = NightWindows.derive(NasSchedule(LocalTime.of(6, 0), LocalTime.of(23, 0)), ideal)
    assertThat(two.window).isEqualTo(TimeWindow(LocalTime.of(6, 0), LocalTime.of(8, 0)))
  }

  @Test
  fun sovrapposizioneTroppoBreve_ripiegaSullaIdeale() {
    // 10 minuti di sovrapposizione non servono a niente.
    val choice = NightWindows.derive(NasSchedule(LocalTime.of(7, 0), LocalTime.of(23, 10)))
    assertThat(choice.window).isEqualTo(NightWindows.DEFAULT)
    assertThat(choice.overlapsNas).isFalse()
  }

  @Test
  fun nasChePassaLaMezzanotte_conSovrapposizioneACavallo() {
    // NAS 20:00-03:00, notte 23:00-07:00 -> 23:00-03:00 (a cavallo).
    val choice = NightWindows.derive(NasSchedule(LocalTime.of(20, 0), LocalTime.of(3, 0)))
    assertThat(choice.window).isEqualTo(TimeWindow(LocalTime.of(23, 0), LocalTime.of(3, 0)))
  }
}
