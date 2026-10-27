package dev.pampa.mycloudgallery.utility

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.library.duplicates.NightlyReport
import dev.pampa.mycloudgallery.core.library.duplicates.SigningReport
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import org.junit.Test

/**
 * La manutenzione dell'Utility nella Diagnostica (revisione di fine fase, DG-2): `lastNightly` e `lastSigning` si scrivevano e non li leggeva nessuno. Solo
 * numeri e segni; le chiavi sono nell'elenco chiuso; senza un giro non c'e' nessuna voce (non si inventa).
 */
class UtilityDiagnosticsTest {

  private val diagnostics = UtilityDiagnostics { null to null }

  @Test
  fun senzaGiriNonCeNiente() {
    assertThat(diagnostics.render(null, null)).isEmpty()
  }

  @Test
  fun ilControlloNotturnoDiceQuantiVideoInCodaERitirati() {
    val item = diagnostics.render(NightlyReport(enabled = true, scanned = 120, queued = 7, withdrawn = 2, finished = false), null).single()
    assertThat(item.code.id).isEqualTo("UTILITY_FULL_HASH")
    assertThat(item.section).isEqualTo(DiagSection.ALBUMS)
    assertThat(item.export["enabled"]).isEqualTo(ExportValue.Flag(true))
    assertThat(item.export["scanned"]).isEqualTo(ExportValue.Num(120))
    assertThat(item.export["queued"]).isEqualTo(ExportValue.Num(7))
    assertThat(item.export["withdrawn"]).isEqualTo(ExportValue.Num(2))
    assertThat(item.export["finished"]).isEqualTo(ExportValue.Flag(false))
  }

  @Test
  fun leFirmeDeiDoppioniSimiliDiconoQuantiFirmatiInAttesaESaltati() {
    val item = diagnostics.render(null, SigningReport(signed = 40, waiting = 3, skipped = 1, finished = true)).single()
    assertThat(item.code.id).isEqualTo("UTILITY_SIGNING")
    assertThat(item.export["signed"]).isEqualTo(ExportValue.Num(40))
    assertThat(item.export["waiting"]).isEqualTo(ExportValue.Num(3))
    assertThat(item.export["skipped"]).isEqualTo(ExportValue.Num(1))
    assertThat(item.export["finished"]).isEqualTo(ExportValue.Flag(true))
  }

  @Test
  fun leChiaviSonoNellElencoChiusoESoloNumeriESegni() {
    val items = diagnostics.render(NightlyReport(false, 0, 0, 5, true), SigningReport(1, 2, 3, false))
    assertThat(items).hasSize(2)
    for (item in items) for ((key, value) in item.export) {
      assertWithMessage("«$key» fuori da ExportKeys").that(ExportKeys.isAllowed(key)).isTrue()
      assertThat(value is ExportValue.Num || value is ExportValue.Flag).isTrue()
    }
  }
}
