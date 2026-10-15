package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import org.junit.Test

/** Le rotte delle schermate della 4B: uniche, stabili e senza dati dell'utente dentro. */
class NasScreenTest {

  @Test
  fun leRotteSonoUnicheEStabili() {
    assertThat(NasScreen.entries.map { it.route }).containsExactly("nas/folders", "nas/space", "nas/trash", "nas/operations")
    assertThat(NasScreen.entries.map { it.route }.toSet()).hasSize(NasScreen.entries.size)
  }

  @Test
  fun unaRottaNonPortaMaiUnPercorsoDelNas() {
    NasScreen.entries.forEach { assertThat(it.route).matches("^nas/[a-z]+$") }
  }

  @Test
  fun soloCartelleEEliminatiHannoAzioniCheModificanoIlNas() {
    assertThat(NasScreen.entries.filter { it.needsWriteAccess }).containsExactly(NasScreen.FOLDERS, NasScreen.TRASH)
  }
}
