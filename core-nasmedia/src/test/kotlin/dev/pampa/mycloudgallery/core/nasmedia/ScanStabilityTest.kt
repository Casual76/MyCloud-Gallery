package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E-N4 con l'ora del NAS sfasata (docs/fase3: il WD e' avanti di 53-58 s e deriva di ~5 s al giorno; D32): la stabilita'
 * di un file si decide solo con l'ora del NAS. Qui il NAS e' avanti di 58 s e le decisioni non cambiano.
 */
@RunWith(AndroidJUnit4::class)
class ScanStabilityTest {

  private val harnesses = ArrayList<ScanHarness>()

  @After
  fun close() {
    harnesses.forEach { it.close() }
  }

  private data class Outcome(val detected: Int, val arriving: Int, val known: Int)

  private fun outcomeOf(report: ScanReport, h: ScanHarness) = Outcome(report.detected, report.arriving, h.store.knownCount(h.me, "test"))

  /** Lo stesso copione con il NAS avanti di [offset] ms: A scritto 150 s fa (ora del NAS), B 100 s fa. */
  private suspend fun scenario(offset: Long): List<Outcome> {
    val h = ScanHarness().also { harnesses += it }
    h.nasOffset = offset
    h.nas.putFile("test", "Foto/A.jpg", SyntheticNas.content(1_500_000, 1, SyntheticNas.JPEG_HEAD), h.nasNow - 150_000)
    h.nas.putFile("test", "Foto/B.jpg", SyntheticNas.content(1_500_000, 2, SyntheticNas.JPEG_HEAD), h.nasNow - 100_000)
    val out = ArrayList<Outcome>()
    out += outcomeOf(h.scan(), h)
    h.advance(21_000) // B ha 121 s per il NAS, ma e' stato visto per la prima volta 21 s fa
    out += outcomeOf(h.scan(), h)
    h.advance(100_000) // visto uguale a 121 s di distanza
    out += outcomeOf(h.scan(), h)
    return out
  }

  @Test
  fun conIlNasAvantiDi58SecondiUnFileDi150SecondiEStabileEUnoDi100No() = runTest {
    val steps = scenario(58_000)
    // A (150 s di ora del NAS) entra subito; B (100 s) e' in arrivo.
    assertThat(steps[0]).isEqualTo(Outcome(detected = 1, arriving = 1, known = 1))
    // 21 s dopo: B ha 121 s di data di modifica ma l'ho visto la prima volta 21 s fa: ancora in arrivo.
    assertThat(steps[1]).isEqualTo(Outcome(detected = 0, arriving = 1, known = 1))
    // Visto uguale a piu' di due minuti di distanza: stabile.
    assertThat(steps[2]).isEqualTo(Outcome(detected = 1, arriving = 0, known = 2))
  }

  @Test
  fun lo_sfasamento_del_nas_non_cambia_nessuna_decisione() = runTest {
    val reference = scenario(0)
    for (offset in listOf(58_000L, 53_000L, 3_600_000L, -45_000L)) {
      assertThat(scenario(offset)).isEqualTo(reference)
    }
  }
}
