package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R19 della revisione di sicurezza della Fase 7: il lavoro che i dati personali di un altro dispositivo dello stesso account impongono alla
 * proiezione degli album ha dei tetti. Prima ogni album con un elemento "in attesa" (un'impronta inventata basta) si riproiettava a **ogni**
 * cambiamento del catalogo, con una ricerca per impronta per elemento dentro la transazione, e nessun tetto limitava album e riferimenti.
 */
@RunWith(AndroidJUnit4::class)
class AlbumProjectionWorkCapTest {

  private val room = AlbumsRoom()
  private var clock = T0
  private val store = room.store(nasNow = { clock += 10; clock })
  private var nonce = 0
  private val other = dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.random()

  @After
  fun close() = room.close()

  private fun newAlbumId(): String = AlbumId.derived(AlbumsRoom.ME, ByteArray(16) { (nonce + it).toByte() }.also { nonce++ }).value

  private fun remote(album: String, field: String, at: Long, value: PersonalValue, fp: PersonalFingerprint? = null) =
    PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, field), at, other, value, fp)

  private fun unknown(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  /** Un album di un altro dispositivo dell'account, con [items] elementi che qui non hanno ne' id ne' media (impronte di [seeds]). */
  private suspend fun pendingAlbum(vararg seeds: Int): String {
    val id = newAlbumId()
    store.applyRemote(
      listOf(remote(id, PersonalFields.ALBUM_DEFINITION, 10, PersonalValue.Text("Dall'altro"))) +
        seeds.mapIndexed { n, seed -> remote(id, PersonalFields.albumItem(unknown(seed).value), 11L + n, PersonalValue.Number(1_000L * (n + 1)), room.fingerprintOf(seed)) },
    )
    return id
  }

  @Test
  fun unAlbumInAttesaTornaSoloSeIlMediaCambiatoPuoRisolverlo() = runBlocking<Unit> {
    val projection = CatalogAlbumProjection(room.catalog, room.db)
    val id = pendingAlbum(9)
    assertThat(projection.project(store.effective()).pending).isEqualTo(1)
    // Un media che non c'entra (un'altra impronta): l'album in attesa non si riproietta.
    val unrelated = room.record(5)
    assertThat(projection.affectedBy(listOf(unrelated))).doesNotContain(id)
    // Il suo media arriva: si'.
    val arrived = room.record(9)
    assertThat(projection.affectedBy(listOf(arrived))).containsExactly(id)
    projection.project(store.effective(), setOf(id))
    assertThat(room.db.albumDao().items(id).map { it.mediaId }).containsExactly(arrived.value)
    assertThat(projection.affectedBy(listOf(room.record(6)))).doesNotContain(id)
  }

  @Test
  fun tettiAgliAlbumEAiRiferimentiDiUnAlbum() = runBlocking<Unit> {
    val projection = CatalogAlbumProjection(room.catalog, room.db, maxAlbums = 2, maxRefsPerAlbum = 3)
    (1..5).forEach { room.record(it) }
    val big = pendingAlbum(1, 2, 3, 4, 5)
    pendingAlbum(1)
    pendingAlbum(2)
    val report = projection.project(store.effective())
    // Due album su tre, e dell'album grande (se c'e') al piu' tre riferimenti risolti.
    assertThat(report.albums).isEqualTo(2)
    assertThat(projection.overCap).isAtLeast(1)
    val bigRows = room.db.albumDao().items(big)
    assertThat(bigRows.size).isAtMost(3)
  }
}
