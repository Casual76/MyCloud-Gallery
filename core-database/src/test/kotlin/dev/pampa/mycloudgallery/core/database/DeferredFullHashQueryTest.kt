package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.Db.quick
import dev.pampa.mycloudgallery.core.model.LocationKey
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La query degli **hash completi rimandati** (Fase 3, P11): le copie sul telefono dei media nati separati
 * (`KEEP_SEPARATE`) perche' l'hash completo, sopra la soglia, si calcola solo nella manutenzione in carica. Nessuna
 * colonna nuova: solo una query.
 */
@RunWith(AndroidJUnit4::class)
class DeferredFullHashQueryTest : CatalogTestBase() {

  @Test
  fun trovaLeCopieGemelleSenzaHashCompletoSopraLaSoglia() = runBlocking<Unit> {
    // Due file con la stessa impronta rapida e senza hash completo: nascono separati (nel dubbio non si unisce).
    val twin = quick(1)
    val a = catalog.record(obs(phone("VID_A.mp4", 10), twin), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    val b = catalog.record(obs(phone("VID_B.mp4", 11, path = "Download/"), twin), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    assertThat(a).isNotEqualTo(b)
    // Un terzo file senza gemelli: non serve l'hash completo.
    catalog.record(obs(phone("VID_C.mp4", 12), quick(2)), MediaCatalog.WhenUndecided.KEEP_SEPARATE)

    val dao = db.locationDao()
    val found = dao.deferredFullHashes(minSize = twin.size - 1, limit = 10)
    assertThat(found.map { it.mediaStoreId }).containsExactly(10L, 11L).inOrder()

    // Sotto la soglia l'hash completo si calcola gia' durante la lettura: qui non compare.
    assertThat(dao.deferredFullHashes(minSize = twin.size, limit = 10)).isEmpty()
    // Il tetto vale.
    assertThat(dao.deferredFullHashes(minSize = 0, limit = 1)).hasSize(1)
  }

  @Test
  fun unaCopiaAssenteOConHashCompletoNonSiRilegge() = runBlocking<Unit> {
    val twin = quick(3)
    catalog.record(obs(phone("A.mp4", 20), twin), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    catalog.record(obs(phone("B.mp4", 21, path = "Download/"), twin), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    // La prima sparisce dal telefono: non c'e' niente da leggere.
    val gone = db.locationDao().byMediaStoreId("external_primary", 20).single()
    catalog.markVanished(LocationKey(gone.locationKey))

    val found = db.locationDao().deferredFullHashes(minSize = 0, limit = 10)
    assertThat(found.map { it.mediaStoreId }).containsExactly(21L)
  }
}
