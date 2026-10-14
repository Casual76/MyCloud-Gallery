package dev.pampa.mycloudgallery.core.nasmedia

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasmedia.scan.ArrivalWatch
import org.junit.Test

/**
 * E-N4: un file e' stabile solo con l'ora del NAS. Il NAS e' avanti di ~58 s rispetto al telefono: tutti i numeri qui sono
 * in ora del NAS, e la decisione non riceve l'ora del telefono (non puo' leggerla).
 */
class ArrivalWatchTest {

  private val watch = ArrivalWatch()
  private val key = LocationKey("nas:test:test:a.jpg")
  private val nasNow = 2_000_000_000_000L
  private val twoMinutes = 120_000L

  private fun stamp(ageMs: Long, size: Long = 1_000_000) = NasStamp(size, nasNow - ageMs, 7)

  @Test
  fun unFileVecchioDiPiuDiDueMinutiDiOraDelNasEStabileSubito() {
    assertThat(watch.isStable(key, "a.jpg", stamp(twoMinutes + 1_000), nasNow)).isTrue()
    assertThat(watch.size).isEqualTo(0)
  }

  @Test
  fun unFileModificatoDaMenoDiDueMinutiEInArrivo() {
    assertThat(watch.isStable(key, "a.jpg", stamp(0), nasNow)).isFalse()
    assertThat(watch.isStable(key, "a.jpg", stamp(twoMinutes - 1), nasNow)).isFalse()
  }

  @Test
  fun conIlNasAvantiDi58SecondiContaLOraDelNasNonQuellaDelTelefono() {
    // Il telefono segna nasNow - 58 s. Un file scritto 150 s fa (ora del NAS) e' vecchio di 92 s per il telefono: se la
    // decisione guardasse l'ora del telefono lo riterrebbe ancora in arrivo. Qui la decisione non ha l'ora del telefono.
    assertThat(watch.isStable(key, "a.jpg", stamp(150_000), nasNow)).isTrue()
    // E uno scritto 100 s fa (ora del NAS) e' in arrivo.
    assertThat(watch.isStable(LocationKey("nas:test:test:b.jpg"), "b.jpg", stamp(100_000), nasNow)).isFalse()
  }

  @Test
  fun unFileInArrivoTornaStabileSoloSeVistoDueVolteUgualeADueMinutiDiDistanza() {
    val first = stamp(10_000)
    assertThat(watch.isStable(key, "a.jpg", first, nasNow)).isFalse()
    // 30 s dopo: la data di modifica e' ormai vecchia di 40 s: ancora in arrivo.
    assertThat(watch.isStable(key, "a.jpg", first, nasNow + 30_000)).isFalse()
    // 119 s dopo la prima occhiata: la data e' vecchia di 129 s ma la seconda occhiata non e' a due minuti.
    assertThat(watch.isStable(key, "a.jpg", first, nasNow + 119_000)).isFalse()
    // 121 s dopo: visto uguale a due minuti di distanza.
    assertThat(watch.isStable(key, "a.jpg", first, nasNow + 121_000)).isTrue()
    assertThat(watch.size).isEqualTo(0)
  }

  @Test
  fun unFileCheCrescePerdeLaPazienza() {
    assertThat(watch.isStable(key, "a.mp4", stamp(10_000, size = 1_000), nasNow)).isFalse()
    // Cresciuto: stamp diverso, si riparte da adesso.
    val grown = NasStamp(2_000, nasNow - 5_000, 7)
    assertThat(watch.isStable(key, "a.mp4", grown, nasNow + 100_000)).isFalse()
    assertThat(watch.isStable(key, "a.mp4", grown, nasNow + 200_000)).isFalse()
    assertThat(watch.isStable(key, "a.mp4", grown, nasNow + 221_000)).isTrue()
  }

  @Test
  fun unaDataNelFuturoDelNasAspettaLaSecondaOcchiata() {
    val future = NasStamp(1_000, nasNow + 3_600_000, 7)
    assertThat(watch.isStable(key, "a.jpg", future, nasNow)).isFalse()
    assertThat(watch.isStable(key, "a.jpg", future, nasNow + 60_000)).isFalse()
    assertThat(watch.isStable(key, "a.jpg", future, nasNow + 130_000)).isTrue()
  }

  @Test
  fun unNomeTemporaneoNonEMaiStabile() {
    assertThat(watch.isStable(key, "a.mp4.part", stamp(10 * twoMinutes), nasNow)).isFalse()
    assertThat(watch.isStable(key, "~\$a.jpg", stamp(10 * twoMinutes), nasNow)).isFalse()
  }

  @Test
  fun laMemoriaHaUnTetto() {
    val small = ArrivalWatch(maxWatched = 3)
    repeat(10) { i -> small.isStable(LocationKey("k$i"), "f$i.jpg", stamp(1_000), nasNow) }
    assertThat(small.size).isAtMost(3)
  }
}
