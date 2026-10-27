package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabaseFiles
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.security.noop.NoFacesKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 8, P13 (§2.8, D30): il gancio dei dati personali nasce **con il processo**, prima che le persone siano montate. Con dati dei volti sul disco e le
 * persone non ancora montate non si pubblica (i dati di una generazione che la voce non nomina piu' accesa non si sono ancora potuti togliere); senza
 * dati dei volti non c'e' niente da togliere e si pubblica. Cosi' una testa personale con `off|G` esiste solo dopo la cancellazione.
 */
class FacesSupportTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun stores(directory: File): FacesStores =
    FacesStores(directory = directory, databases = { error("Il gancio non apre nessun database.") }, keys = NoFacesKeys)

  @Test
  fun senzaDatiDeiVoltiSullDiscoSiPubblicaAnchePrimaDelMontaggio() = runBlocking<Unit> {
    val directory = File(temp.root, "faces").apply { mkdirs() }
    val hook = FacesPrePublish({ null }, stores(directory))
    assertThat(hook.beforePublish(emptyMap())).isEqualTo(PrePublish.Proceed)
  }

  @Test
  fun senzaNemmenoLaCartellaSiPubblica() = runBlocking<Unit> {
    val hook = FacesPrePublish({ null }, stores(File(temp.root, "non-esiste")))
    assertThat(hook.beforePublish(emptyMap())).isEqualTo(PrePublish.Proceed)
  }

  @Test
  fun conUnDatabaseDeiVoltiSulDiscoENonMontatoNonSiPubblica() = runBlocking<Unit> {
    val directory = File(temp.root, "faces").apply { mkdirs() }
    FacesDatabaseFiles.fileOf(directory, FaceGeneration.random()).writeBytes(ByteArray(8))
    val hook = FacesPrePublish({ null }, stores(directory))
    assertThat(hook.beforePublish(emptyMap())).isEqualTo(PrePublish.Hold(FacesPrePublish.NOT_READY))
  }

  @Test
  fun ancheSoloUnCompagnoDelDatabaseBasta() = runBlocking<Unit> {
    val directory = File(temp.root, "faces").apply { mkdirs() }
    val main = FacesDatabaseFiles.fileOf(directory, FaceGeneration.random())
    File(main.path + "-wal").writeBytes(ByteArray(8))
    val hook = FacesPrePublish({ null }, stores(directory))
    assertThat(hook.beforePublish(emptyMap())).isEqualTo(PrePublish.Hold(FacesPrePublish.NOT_READY))
  }

  @Test
  fun ilCodiceDelFermoEQuelloDelCatalogoENonPortaDati() {
    assertThat(FacesPrePublish.NOT_READY).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.FacesDiag.FACES_ERASE_PENDING)
    assertThat(FacesPrePublish.NOT_READY).matches("^[A-Z][A-Z0-9_]{0,47}$")
  }
}
