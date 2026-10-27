package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchiveContract
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, §5.2): l'archivio **vero** (Room, cifrato, chiavi finte di P0) passa la stessa suite di contratto dell'archivio in memoria di P0
 * (`FacesArchiveContract`: vincitori fra i dispositivi, "gia' fatto", volti da raggruppare, istantanea, voci `people`, archivio chiuso, una
 * generazione per archivio). La suite non sa nulla del database: e' la prova che il database e la cifratura non cambiano il comportamento.
 */
@RunWith(AndroidJUnit4::class)
class FacesStoreContractTest : FacesArchiveContract() {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val stores = mutableListOf<FacesStore>()

  override fun archive(generation: FaceGeneration): FacesArchive = runBlocking {
    val store = world.ready(generation)
    stores += store
    store.archive
  }

  /** La chiave distrutta (o la generazione cambiata): l'archivio si ritira e lancia, come quello in memoria. */
  override fun close(archive: FacesArchive) {
    stores.first { it.archive === archive }.close()
  }

  @org.junit.After
  fun chiudeTutto() {
    stores.forEach { it.close() }
  }
}
