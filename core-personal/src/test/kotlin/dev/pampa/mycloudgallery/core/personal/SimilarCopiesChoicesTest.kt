package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.SimilarCopiesChoices
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **BK-11** (verifica funzionale): "Mostra questa come principale" e "Separa" sono dati personali sincronizzati come preferiti e nascosti, ma a
 * differenza loro non chiedevano la sincronizzazione: la scelta partiva con il giro periodico (fino a sei ore dopo) e un altro dispositivo
 * continuava a vedere il gruppo di prima. Le scelte avvisano chi sincronizza appena sono scritte sullo store.
 */
class SimilarCopiesChoicesTest {

  private val a = MediaId.random()
  private val b = MediaId.random()
  private val c = MediaId.random()

  private fun store() = LedgerPersonalStore(TrustIds.random(), { 1_000L }, InMemoryPersonalLedger())

  @Test
  fun ogniScelta_avvisaChiSincronizzaUnaVolta() = runTest {
    var saved = 0
    val choices = SimilarCopiesChoices(store(), onSaved = { saved++ })

    choices.saveCover(a, listOf(a, b, c))
    assertThat(saved).isEqualTo(1)

    // Una separazione scrive una voce per ogni altra copia, ma e' una scelta sola: un avviso.
    choices.saveSeparation(a, listOf(a, b, c))
    assertThat(saved).isEqualTo(2)
  }

  @Test
  fun senzaNienteDaScrivereNonAvvisaNessuno() = runTest {
    var saved = 0
    val choices = SimilarCopiesChoices(store(), onSaved = { saved++ })

    choices.saveSeparation(a, listOf(a)) // solo se stessa: nessuna voce
    choices.saveSeparation(a, emptyList())

    assertThat(saved).isEqualTo(0)
  }

  @Test
  fun leScelteSiLeggonoSubitoEDiSerieNienteAvvisa() = runTest {
    val choices = SimilarCopiesChoices(store())

    choices.saveCover(b, listOf(a, b))
    choices.saveSeparation(a, listOf(c))

    assertThat(choices.chosenCover(listOf(a, b))).isEqualTo(b)
    assertThat(choices.separatedPairs(listOf(a, c))).hasSize(1)
  }
}
