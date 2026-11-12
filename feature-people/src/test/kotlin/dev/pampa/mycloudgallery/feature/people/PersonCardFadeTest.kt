package dev.pampa.mycloudgallery.feature.people

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCardDefaults
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.personCardHeight
import dev.pampa.mycloudgallery.feature.people.ui.personFadeHeight
import org.junit.Test

/**
 * Fase I, giro di correzione 1, **CU-2**: la sfumatura sopra il nome di una scheda di persona si sceglie dall'altezza vera della scheda. Sul telefono
 * da 360 dp (colonna di 320 dp, tre colonne) la scheda e' alta circa 133 dp: sfumatura corta, la bocca del volto resta libera. Su una colonna larga
 * (tablet) le schede sono alte e tengono quella di serie. Prima della misura, quella di serie.
 */
class PersonCardFadeTest {

  private val gap = PeopleDimens.BlockGap

  @Test
  fun telefonoDa360_treColonne_sfumaturaCorta() {
    val height = personCardHeight(320.dp, 3, gap)
    assertThat(height.value).isWithin(0.5f).of(131.6f)
    assertThat(personFadeHeight(height)).isEqualTo(GalleryCoverCardDefaults.FadeHeightCompact)
  }

  @Test
  fun schedeAlte_sfumaturaDiSerie() {
    // Due colonne su 320 dp (testo grande): schede alte 205 dp.
    assertThat(personFadeHeight(personCardHeight(320.dp, 2, gap))).isEqualTo(GalleryCoverCardDefaults.FadeHeight)
    // Tablet: colonna di 1120 dp, otto colonne: schede alte ~171 dp.
    assertThat(personFadeHeight(personCardHeight(1120.dp, 8, gap))).isEqualTo(GalleryCoverCardDefaults.FadeHeight)
  }

  @Test
  fun proprioAlConfine_eLarghezzaNonMisurata() {
    assertThat(personFadeHeight(PeopleDimens.CompactCardBelow)).isEqualTo(GalleryCoverCardDefaults.FadeHeight)
    assertThat(personFadeHeight(PeopleDimens.CompactCardBelow - 1.dp)).isEqualTo(GalleryCoverCardDefaults.FadeHeightCompact)
    assertThat(personCardHeight(Dp.Unspecified, 3, gap)).isEqualTo(Dp.Unspecified)
    assertThat(personFadeHeight(Dp.Unspecified)).isEqualTo(GalleryCoverCardDefaults.FadeHeight)
  }
}
