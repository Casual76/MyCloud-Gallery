package dev.pampa.mycloudgallery.core.faces.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import org.junit.Test

/**
 * Q2 come opzione (Fase 8, P9, risposta del 27/10): la variante **(b) e' quella predefinita** (in primo piano solo le persone con un nome, gli altri gruppi in
 * "Altri volti"); la (a) sta dietro l'opzione. I due codici sono stabili: sono quelli che `:app` (P13) salva nelle impostazioni, e non cambiano mai.
 */
class PeopleVariantTest {

  @Test
  fun laPredefinitaELaB() {
    assertThat(PeopleVariant.DEFAULT).isEqualTo(PeopleVariant.NAMED_FIRST)
    assertThat(PeopleVariant.DEFAULT.namedOnly).isTrue()
  }

  @Test
  fun laAMostraTuttiIGruppiInPersone() {
    assertThat(PeopleVariant.ALL_GROUPS.namedOnly).isFalse()
  }

  @Test
  fun iCodiciSonoStabili() {
    assertThat(PeopleVariant.NAMED_FIRST.code).isEqualTo("named_first")
    assertThat(PeopleVariant.ALL_GROUPS.code).isEqualTo("all_groups")
    assertThat(PeopleVariant.entries.map { it.code }).containsNoDuplicates()
  }
}
