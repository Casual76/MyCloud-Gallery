package dev.pampa.mycloudgallery.core.nasmedia

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeVerdict
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.freeze.MassChangeGuard
import org.junit.Test

/** D10: ciascuna grandezza congela quando arriva ad almeno max(50, 5% dei file presenti); alla soglia si' e sotto no. */
class MassChangeGuardTest {

  private fun judge(known: Int, vanished: Int = 0, changed: Int = 0, notMedia: Int = 0) =
    MassChangeGuard.judge(ScanDiff(known, vanished, changed, notMedia))

  @Test
  fun laSogliaEIlMassimoFra50ELCinquePerCento() {
    assertThat(MassChangeRule.threshold(0)).isEqualTo(50)
    assertThat(MassChangeRule.threshold(1_000)).isEqualTo(50)
    assertThat(MassChangeRule.threshold(12_244)).isEqualTo(613)
    assertThat(MassChangeRule.threshold(20_000)).isEqualTo(1_000)
  }

  @Test
  fun alleSoglieSiCongelaESottoNo() {
    for (known in listOf(0, 500, 1_000, 1_001, 12_244, 20_000, 100_000)) {
      val t = MassChangeRule.threshold(known)
      listOf<(Int) -> FreezeVerdict>(
        { n -> judge(known, vanished = n) },
        { n -> judge(known, changed = n) },
        { n -> judge(known, notMedia = n) },
      ).forEach { verdict ->
        assertThat(verdict(t - 1)).isEqualTo(FreezeVerdict.Proceed)
        assertThat(verdict(t)).isInstanceOf(FreezeVerdict.Freeze::class.java)
        assertThat(verdict(t + 1)).isInstanceOf(FreezeVerdict.Freeze::class.java)
      }
    }
  }

  @Test
  fun leGrandezzeNonSiSommano() {
    // 49 + 49 + 49 sotto una soglia di 50: nessuna delle tre arriva alla soglia da sola.
    assertThat(judge(1_000, vanished = 49, changed = 49, notMedia = 49)).isEqualTo(FreezeVerdict.Proceed)
  }

  @Test
  fun laRagioneEIlConteggioPiuGraveEAParitaSparitiPrima() {
    assertThat((judge(1_000, vanished = 60, notMedia = 500) as FreezeVerdict.Freeze).reason).isEqualTo(FreezeReason.NOT_MEDIA)
    assertThat((judge(1_000, vanished = 60, changed = 700) as FreezeVerdict.Freeze).reason).isEqualTo(FreezeReason.CHANGED)
    assertThat((judge(1_000, vanished = 500, changed = 500, notMedia = 500) as FreezeVerdict.Freeze).reason).isEqualTo(FreezeReason.VANISHED)
    assertThat((judge(1_000, changed = 100, notMedia = 100) as FreezeVerdict.Freeze).reason).isEqualTo(FreezeReason.NOT_MEDIA)
  }

  @Test
  fun ilGiudizioPortaIConteggiVisti() {
    val diff = ScanDiff(1_000, vanished = 500, changed = 3, notMedia = 500)
    assertThat((MassChangeGuard.judge(diff) as FreezeVerdict.Freeze).diff).isEqualTo(diff)
    assertThat(MassChangeGuard.isNormal(ScanDiff(1_000, 49, 0, 0))).isTrue()
    assertThat(MassChangeGuard.isNormal(diff)).isFalse()
  }
}
