package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * R11 (correzioni A della Fase 8): un archivio ritirato (cancellazione locale: chiave distrutta, file tolti) non ha piu' vettori in chiaro in memoria, **nemmeno**
 * se una lettura dei raggruppamenti era gia' cominciata e finisce dopo il ritiro: prima la cache si riempiva lo stesso, dietro una cancellazione che l'aveva appena
 * svuotata.
 */
@RunWith(AndroidJUnit4::class)
class GroupCacheRetireTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 41 })

  @Test
  fun unaLetturaCheFinisceDopoIlRitiroNonRimetteIVettoriNellaCache() = runBlocking<Unit> {
    val db = FacesDatabases.forContext(ApplicationProvider.getApplicationContext()).open(File(folder.root, "faces-${generation.base32}.db"))
    db.openNow()
    val keys = FakeFacesKeys()
    keys.create(generation)
    val real = checkNotNull(keys.open(generation))
    lateinit var archive: DatabaseFacesArchive
    var armed = false
    // La chiave dati che, alla prima apertura di un blocco mentre si legge, fa ritirare l'archivio (la cancellazione che arriva a meta' lettura).
    val key = object : FacesDataKey by real {
      override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? = real.open(sealed, aad).also {
        if (armed) {
          armed = false
          archive.retire()
        }
      }
    }
    archive = DatabaseFacesArchive(generation, db, key)
    archive.saveBody(FaceFixtures.body(FaceFixtures.key(1), FaceFixtures.AUTHOR_SMALL, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE))))
    // La prima lettura dei raggruppamenti parte (la cache e' vuota) e viene ritirata a meta': lancia, e dopo non resta niente in memoria.
    armed = true
    assertThrows(IllegalStateException::class.java) { runBlocking { archive.groupFaces() } }
    assertThat(archive.cachedVectors).isFalse()
    db.close()
  }
}
