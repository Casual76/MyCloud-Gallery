package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il file `faces-local` (D4, §2.1): scritture atomiche, niente file se tutto e' di serie, nel dubbio di serie, e una scrittura che non riesce non
 * fa perdere la scelta dell'utente in questo processo.
 */
class FileFacesLocalStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val g = SwitchRig.generation(1)
  private val other = SwitchRig.generation(2)

  private fun file() = folder.root.resolve("faces-local")

  @Test
  fun senzaFileEDiSerie() {
    assertThat(FileFacesLocalStore(file()).local.value).isEqualTo(FacesLocal.EMPTY)
    assertThat(file().exists()).isFalse()
  }

  @Test
  fun unaScrittaSiRileggeIdenticaDopoUnRiavvio() = runTest {
    val store = FileFacesLocalStore(file())
    val written = FacesLocal(excluded = true, noticedGeneration = g, noticedTextVersion = 3, dataGeneration = g, erasing = other)
    assertThat(store.update { written }).isEqualTo(written)
    assertThat(store.unsaved).isFalse()
    assertThat(FileFacesLocalStore(file()).local.value).isEqualTo(written)
    // Il file non porta altro che i valori del formato: nessun nome, nessun percorso.
    assertThat(file().readText().lines().filter { it.isNotEmpty() }).hasSize(6)
    assertThat(file().exists()).isTrue()
    assertThat(folder.root.listFiles()!!.map { it.name }).containsExactly("faces-local")
  }

  @Test
  fun unFileDiSoliValoriDiSerieNonSiScriveEQuelloCheCeraSiToglie() = runTest {
    val store = FileFacesLocalStore(file())
    store.update { it.copy(excluded = true) }
    assertThat(file().exists()).isTrue()
    store.update { FacesLocal.EMPTY }
    assertThat(file().exists()).isFalse()
    assertThat(store.unsaved).isFalse()
    // Una chiamata che non cambia niente non fa rinascere un file che non c'e'.
    store.update { it }
    assertThat(file().exists()).isFalse()
  }

  @Test
  fun nelDubbioDiSerie() {
    val g1 = g.base32
    val broken = listOf(
      "",
      "2\n0\n-\n0\n-\n-\n",
      "1\nsi\n-\n0\n-\n-\n",
      "1\n0\n$g1\n-1\n-\n-\n",
      "1\n0\nNON-CANONICA\n1\n-\n-\n",
      "1\n0\n-\n0\n${g1.uppercase()}\n-\n",
      "1\n0\n-\n0\n-\n",
      "casuale\nbinario",
    )
    for (content in broken) {
      file().writeText(content)
      assertThat(FileFacesLocalStore(file()).local.value).isEqualTo(FacesLocal.EMPTY)
    }
    file().writeText("1\n0\n$g1\n2\n-\n-\n")
    assertThat(FileFacesLocalStore(file()).local.value).isEqualTo(FacesLocal(noticedGeneration = g, noticedTextVersion = 2))
  }

  /** Una versione dell'avviso senza la generazione (o con zero) non e' un avviso visto. */
  @Test
  fun unAvvisoSenzaGenerazioneONessunTestoNonEUnAvvisoVisto() {
    file().writeText("1\n0\n-\n5\n-\n-\n")
    assertThat(FileFacesLocalStore(file()).local.value.noticedTextVersion).isNull()
    file().writeText("1\n0\n${g.base32}\n0\n-\n-\n")
    assertThat(FileFacesLocalStore(file()).local.value.noticedTextVersion).isNull()
  }

  /** Il file non si riesce a scrivere (qui la cartella e' un file): la scelta vale in questo processo, e il file si aggiorna appena si puo'. */
  @Test
  fun unaScritturaCheNonRiescePerdeNienteInQuestoProcesso() = runTest {
    val blocker = folder.newFile("blocco")
    val store = FileFacesLocalStore(blocker.resolve("faces-local"))
    store.update { it.copy(excluded = true, dataGeneration = g) }
    assertThat(store.local.value.excluded).isTrue()
    assertThat(store.unsaved).isTrue()
    assertThat(store.flush()).isFalse()

    blocker.delete()
    assertThat(store.flush()).isTrue()
    assertThat(store.unsaved).isFalse()
    assertThat(FileFacesLocalStore(blocker.resolve("faces-local")).local.value).isEqualTo(FacesLocal(excluded = true, dataGeneration = g))
  }

  @Test
  fun unAggiornamentoSuccessivoRiprovaLaScrittura() = runTest {
    val blocker = folder.newFile("blocco")
    val store = FileFacesLocalStore(blocker.resolve("faces-local"))
    store.update { it.copy(excluded = true) }
    assertThat(store.unsaved).isTrue()
    blocker.delete()
    // Anche un update che non cambia niente porta su disco cio' che mancava.
    store.update { it }
    assertThat(store.unsaved).isFalse()
    assertThat(FileFacesLocalStore(blocker.resolve("faces-local")).local.value.excluded).isTrue()
  }

  @Test
  fun lAzzeramentoDimenticaTuttoEToglieIlFile() = runTest {
    val store = FileFacesLocalStore(file())
    store.update { it.copy(noticedGeneration = g, noticedTextVersion = 1, dataGeneration = g) }
    assertThat(file().exists()).isTrue()
    store.reset()
    assertThat(store.local.value).isEqualTo(FacesLocal.EMPTY)
    assertThat(file().exists()).isFalse()
    assertThat(folder.root.listFiles()!!.toList()).isEmpty()
  }
}
