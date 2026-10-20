package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le identita' quando gli `_ID` cambiano: versione del MediaStore nuova con `_ID` scambiati (E-P12, E-P22), cestino di
 * Samsung con ripristino (E-P11), microSD tolta e rimessa (E-P6), file identici in due posti.
 */
@RunWith(AndroidJUnit4::class)
class IdentityTest : PhoneSyncTestBase() {

  @Test
  fun versioneCambiataConIdScambiatiNessunaIdentitaNeVersioneNuova() = runBlocking<Unit> {
    val a = store.put(photo(1, taken = T0 + 3 * MINUTE))
    val b = store.put(photo(2, taken = T0 + 2 * MINUTE))
    val c = store.put(video(3, taken = T0 + MINUTE))
    store.put(photo(4, taken = T0))
    newSync().syncNow(SyncReason.APP_START)
    val idA = mediaAt(a)!!
    val idB = mediaAt(b)!!
    val idC = mediaAt(c)!!
    catalog.setPersonalFlags(idA, PersonalFlags(favorite = true))
    val opens = store.opens
    val versions = versionCount()

    // Aggiornamento di One UI: versione nuova, gli _ID di A e B scambiati, quello di C cambiato.
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 2L, 2L to 1L, 3L to 300L))
    now += 60 * MINUTE

    // Il primo processo muore dopo il primo lotto: chi non e' ancora riletto non ha riferimento ai byte (E-P22).
    store.failQueryAt = store.queries.size + 2
    val partial = newSync().syncNow(SyncReason.APP_START)
    assertThat(partial.completed).isFalse()
    val refsMidway = catalog.phoneRefs(listOf(idA, idB, idC))
    assertThat(refsMidway.keys).containsExactly(idA, idB, idC)
    assertThat(refsMidway.getValue(idA).mediaStoreId).isEqualTo(2L)
    assertThat(refsMidway.getValue(idB).mediaStoreId).isEqualTo(1L)
    assertThat(refsMidway.getValue(idC).mediaStoreId).isEqualTo(300L)
    val id4 = reads.mediaIdAt(placeOf(photo(4, taken = T0)).key)!!
    assertThat(catalog.phoneRefs(listOf(id4))).isEmpty()

    store.failQueryAt = null
    val report = newSync().syncNow(SyncReason.APP_START)
    assertThat(report.completed).isTrue()
    assertThat(report.fullScan).isTrue()
    // La scorciatoia del timbro (con la generazione ignorata) riconosce tutto per posto: nessun byte letto.
    assertThat(store.opens).isEqualTo(opens)
    assertThat(mediaCount()).isEqualTo(4)
    assertThat(versionCount()).isEqualTo(versions)
    assertThat(report.added).isEqualTo(0)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(catalog.phoneRefs(listOf(id4)).getValue(id4).mediaStoreId).isEqualTo(4L)
    // Il preferito resta sul media giusto, che ora ha l'_ID 2.
    assertThat(db.personalDao().get(idA.value)!!.favorite).isTrue()
    assertThat(db.personalDao().get(idB.value)).isNull()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.msVersion).isEqualTo("v2")
  }

  @Test
  fun versioneCambiataEFileRiscrittoConStessiByteNonCreaVersioni() = runBlocking<Unit> {
    val bytes = FakeMediaContent.jpeg(4_000, seed = 3)
    val a = store.put(photo(1, taken = T0), bytes)
    store.put(photo(2, taken = T0 + MINUTE))
    newSync().syncNow(SyncReason.APP_START)
    val id = mediaAt(a)!!

    // Dopo la ricostruzione il timbro non torna (data di modifica diversa) ma i byte sono gli stessi: niente versione.
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 2L, 2L to 1L))
    store.put(photo(2, taken = T0, name = "IMG_1.jpg", modified = T0 + 9_000), bytes.copyOf())
    val report = newSync().syncNow(SyncReason.APP_START)

    assertThat(report.added).isEqualTo(0)
    assertThat(mediaAt(a)).isEqualTo(id)
    assertThat(db.versionDao().forMedia(id.value)).hasSize(1)
    assertThat(catalog.phoneRefs(listOf(id)).getValue(id).mediaStoreId).isEqualTo(2L)
  }

  @Test
  fun cestinoDiSamsungRipristinoConservaPreferitoENascosto() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true, hidden = true))

    // Samsung Gallery sposta il file nel suo cestino: per il MediaStore la riga sparisce (scoperta 8).
    val trashed = store.samsungTrash(PRIMARY, 1)
    val gone = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(gone.vanished).isEqualTo(1)
    assertThat(stateAt(row)).isEqualTo(LocationState.ABSENT)
    assertThat(presenceOf(id)).isEqualTo("none")
    // Assente, non dimenticato: l'identita' e i flag restano per i 60 giorni di tolleranza.
    assertThat(mediaCount()).isEqualTo(2)

    // Ripristino: riga nuova (altro _ID), stesso posto, stessi byte.
    store.samsungRestore(trashed, newId = 77)
    val back = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(back.added).isEqualTo(0)
    assertThat(back.updated).isEqualTo(1)
    assertThat(mediaAt(row)).isEqualTo(id)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    assertThat(mediaCount()).isEqualTo(2)
    assertThat(db.versionDao().forMedia(id.value)).hasSize(1)
    val flags = db.personalDao().get(id.value)!!
    assertThat(flags.favorite).isTrue()
    assertThat(flags.hidden).isTrue()
    assertThat(catalog.phoneRefs(listOf(id)).getValue(id).mediaStoreId).isEqualTo(77L)
  }

  /**
   * D-3 delle prove sul campo: sull'S25 il cestino di sistema (`createTrashRequest`) porta il file nel Cestino di Galleria e la riga resta con lo
   * stesso `_ID`, fuori da ogni query dell'app; Galleria → Cestino → Ripristina la rimette al suo posto con lo **stesso** `_ID`. La lettura del
   * telefono la vede sparire (riconciliazione: niente in `deleted_files`) e poi la ricollega al suo media dalle impronte: nessun media nuovo,
   * nessuna versione nuova, preferito e nascosto conservati.
   */
  @Test
  fun cestinoDiOneUiRipristinoConLoStessoIdRicollegaIlMedia() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true, hidden = true))

    store.oneUiTrash(PRIMARY, 1)
    sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(stateAt(row)).isEqualTo(LocationState.ABSENT)
    assertThat(mediaCount()).isEqualTo(2)

    store.oneUiRestore(PRIMARY, 1)
    val back = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(back.added).isEqualTo(0)
    assertThat(mediaAt(row)).isEqualTo(id)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    assertThat(mediaCount()).isEqualTo(2)
    assertThat(db.versionDao().forMedia(id.value)).hasSize(1)
    val flags = db.personalDao().get(id.value)!!
    assertThat(flags.favorite).isTrue()
    assertThat(flags.hidden).isTrue()
    assertThat(catalog.phoneRefs(listOf(id)).getValue(id).mediaStoreId).isEqualTo(1L)
  }

  @Test
  fun cestinoDiSamsungRipristinoInUnAltraCartellaESpostamento() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true))

    val trashed = store.samsungTrash(PRIMARY, 1)
    sync.syncNow(SyncReason.CONTENT_CHANGE)
    val restored = store.samsungRestore(trashed, newId = 78, relativePath = "Pictures/Ripristinate/")
    sync.syncNow(SyncReason.CONTENT_CHANGE)

    // Stessi byte, stessa data di modifica, posizione sparita: e' uno spostamento, non un media nuovo.
    assertThat(mediaAt(restored)).isEqualTo(id)
    assertThat(mediaCount()).isEqualTo(2)
    assertThat(db.personalDao().get(id.value)!!.favorite).isTrue()
  }

  @Test
  fun microSdToltaNonRaggiungibileERimessaPresenteConGliStessiMedia() = runBlocking<Unit> {
    store.addVolume(SD, version = "sd1")
    access.set(access.state.value.copy(volumes = setOf(PRIMARY, SD)))
    val onCard = listOf(store.put(photo(1, taken = T0, volume = SD)), store.put(video(2, taken = T0 + MINUTE, volume = SD)))
    val onPhone = store.put(photo(3, taken = T0 + 2 * MINUTE))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val ids = onCard.map { mediaAt(it)!! }
    val opens = store.opens

    store.setMounted(SD, false)
    val out = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(out.vanished).isEqualTo(0)
    onCard.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.UNAVAILABLE) }
    assertThat(stateAt(onPhone)).isEqualTo(LocationState.PRESENT)
    // Al ritorno serve una scansione completa (le righe non raggiungibili tornano presenti solo se rilette).
    assertThat(catalog.mediaStoreCursor(SD)!!.generation).isNull()
    // Un secondo giro con la scheda fuori non tocca niente.
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)

    store.setMounted(SD, true)
    val back = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(back.fullScan).isTrue()
    assertThat(back.added).isEqualTo(0)
    assertThat(back.vanished).isEqualTo(0)
    onCard.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(onCard.map { mediaAt(it)!! }).isEqualTo(ids)
    assertThat(store.opens).isEqualTo(opens)
  }

  @Test
  fun dueCopieIdenticheSulTelefonoSonoLoStessoMedia() = runBlocking<Unit> {
    // 300 KB: l'hash rapido guarda testa e coda, l'hash completo tutto.
    val bytes = FakeMediaContent.jpeg(300_000, seed = 42)
    val inCamera = store.put(photo(1, taken = T0, size = 300_000), bytes)
    val inWhatsApp = store.put(photo(2, taken = T0, name = "IMG-20250101-WA0001.jpg", path = "Pictures/WhatsApp/", size = 300_000), bytes.copyOf())
    newSync().syncNow(SyncReason.APP_START)

    assertThat(mediaCount()).isEqualTo(1)
    assertThat(mediaAt(inCamera)).isEqualTo(mediaAt(inWhatsApp))
  }

  @Test
  fun stessaTestaECodaMaMezzoDiversoRestanoDueMedia() = runBlocking<Unit> {
    val first = FakeMediaContent.jpeg(300_000, seed = 7)
    val second = first.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
    val a = store.put(photo(1, taken = T0, size = 300_000), first)
    val b = store.put(photo(2, taken = T0 + MINUTE, name = "COPIA.jpg", size = 300_000), second)
    newSync().syncNow(SyncReason.APP_START)

    assertThat(mediaCount()).isEqualTo(2)
    assertThat(mediaAt(a)).isNotEqualTo(mediaAt(b))
  }
}
