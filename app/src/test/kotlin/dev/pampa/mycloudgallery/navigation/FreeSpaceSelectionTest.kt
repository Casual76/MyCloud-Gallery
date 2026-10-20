package dev.pampa.mycloudgallery.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.backup.FreeSpaceRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import org.junit.Test

/**
 * LS-6 della verifica funzionale 5B: la selezione di "Libera spazio" sopravvive alla morte del processo nello stato salvato della rotta, e
 * **non si allarga mai**: o si conserva, o la pagina si chiude. Prima la rotta la leggeva da una variabile del processo, che dopo la morte del
 * processo era vuota: la pagina diventava "tutto il sicuro".
 */
class FreeSpaceSelectionTest {

  private val scope = SaverScope { true }

  private fun id(n: Int) = MediaId(UUID(0x5E1EC7L, n.toLong()).toString())

  /** Il giro completo: salvato nel `Bundle`, il processo muore (niente si ricorda), ripristinato. */
  private fun afterDeath(state: FreeSpaceSelection): FreeSpaceSelection.Resolved {
    val saved = with(FreeSpaceSelection.Saver) { scope.save(state) }!!
    return FreeSpaceSelection.Saver.restore(saved)!!.resolve { null }
  }

  @Test
  fun unaSceltaSiConservaDopoLaMorteDelProcesso() {
    val state = FreeSpaceSelection.of(FreeSpaceRequest(7, setOf(id(1))))
    assertThat(afterDeath(state)).isEqualTo(FreeSpaceSelection.Resolved.Page(setOf(id(1))))
  }

  @Test
  fun tuttoIlSicuroRestaTuttoIlSicuro() {
    assertThat(afterDeath(FreeSpaceSelection.of(FreeSpaceRequest(3, null)))).isEqualTo(FreeSpaceSelection.Resolved.Page(null))
    assertThat(afterDeath(FreeSpaceSelection.of(null))).isEqualTo(FreeSpaceSelection.Resolved.Page(null))
  }

  /** Una scelta troppo grande per il `Bundle`: dopo una ricreazione la ritrova il processo vivo; dopo la sua morte la pagina si chiude. */
  @Test
  fun unaSceltaGrandeSiRitrovaNelProcessoVivo_eDopoLaMorteLaPaginaSiChiude() {
    val big = (1..FreeSpaceSelection.MAX_SAVED + 1).mapTo(LinkedHashSet()) { id(it) }
    val request = FreeSpaceRequest(11, big)
    val state = FreeSpaceSelection.of(request)
    val saved = with(FreeSpaceSelection.Saver) { scope.save(state) }!!
    assertThat(saved.size).isLessThan(10)
    val restored = FreeSpaceSelection.Saver.restore(saved)!!
    assertThat(restored.resolve { serial -> request.takeIf { serial == 11L } }).isEqualTo(FreeSpaceSelection.Resolved.Page(big))
    assertThat(restored.resolve { null }).isEqualTo(FreeSpaceSelection.Resolved.Close)
  }

  /** Uno stato salvato rovinato non diventa mai "tutto il sicuro". */
  @Test
  fun unoStatoIllegibileChiudeLaPagina() {
    assertThat(FreeSpaceSelection.restore(listOf("ids", "4", "non-un-id")).resolve { null }).isEqualTo(FreeSpaceSelection.Resolved.Close)
    assertThat(FreeSpaceSelection.restore(emptyList()).resolve { null }).isEqualTo(FreeSpaceSelection.Resolved.Close)
  }
}
