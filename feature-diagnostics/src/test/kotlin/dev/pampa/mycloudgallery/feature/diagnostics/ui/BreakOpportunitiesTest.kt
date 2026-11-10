package dev.pampa.mycloudgallery.feature.diagnostics.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GF-21 e GF-31 (review della beta): dove il punto del codice di un errore registrato puo' andare a capo. Fra i pezzi del nome si', dentro una parola no, e il nome
 * di un file non si separa dalla sua estensione («AppRoot. / kt:45»).
 */
class BreakOpportunitiesTest {

  private val zw = '​'

  @Test
  fun ilNomeSiSpezzaFraISuoiPezzi() {
    val out = "LibraryViewModel\$load\$1.invokeSuspend (LibraryViewModel.kt:120)".withBreakOpportunities()
    assertThat(out).isEqualTo("LibraryViewModel$zw\$load$zw\$1.${zw}invokeSuspend (LibraryViewModel.kt:120)")
  }

  @Test
  fun ilFileNonSiSeparaDallEstensione() {
    assertThat("AppRoot.draw (AppRoot.kt:45)".withBreakOpportunities()).isEqualTo("AppRoot.${zw}draw (AppRoot.kt:45)")
    assertThat("Main.java:7".withBreakOpportunities()).isEqualTo("Main.java:7")
  }

  @Test
  fun leFrasiEINumeriNonCambiano() {
    val plain = listOf(
      "Android ha chiuso l'app perché non rispondeva. Il punto del codice dove era ferma è qui.",
      "1,2 s",
      "Mer. 3",
      "3.5 MB",
      "v1.2.3",
    )
    for (text in plain) assertThat(text.withBreakOpportunities()).isEqualTo(text)
  }
}
