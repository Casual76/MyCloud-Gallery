package dev.pampa.mycloudgallery.core.backup.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupStore
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R14 e R15 della revisione di sicurezza 5B sul diario dei file in arrivo, con lo stesso scenario sul diario vero ([RoomBackupStore]) e sul suo finto
 * ([InMemoryBackupStore]): il finto e il vero fanno le stesse scelte.
 *
 * - **R14**: due "Salva sul NAS" degli stessi byte verso destinazioni diverse erano un solo elemento (il secondo tornava il primo): la seconda scelta
 *   dell'utente non si realizzava e la sua copia locale si toglieva. Adesso l'elemento e' lo stesso solo per la stessa versione, share **e cartella**.
 * - **R15**: un arrivo ritrova la sua versione dagli stessi byte, e puo' essere una foto dell'utente che esisteva gia': `openFor` non lo conta, cosi' il
 *   piano della 5A e il guardiano non credono la versione "gia' in coda" e il suo backup non resta fermo dietro un arrivo bloccato.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingStoreTest {

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val clock = FakeBackupClock()

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  private class World(val store: BackupStore, val version: VersionId, val media: MediaId)

  private fun room(): World = runBlocking {
    val db = GalleryDatabase.build(context, null).also { opened += it }
    val media = MediaId.random()
    val version = VersionId.random()
    db.transaction {
      db.mediaDao().insert(
        MediaEntity(
          mediaId = media.value, kind = "photo", source = "camera", ownerAccount = null, groupId = null, currentVersionId = version.value,
          createdAt = 0, updatedAt = 0, sortTime = 0, presence = "phone", safety = "no_nas_copy",
        ),
      )
      db.versionDao().insert(
        MediaVersionEntity(
          versionId = version.value, mediaId = media.value, kind = "original", parentVersionId = null, size = 1_234,
          quickHash = "a".repeat(64), fullHash = null, corrections = "", createdAt = 0,
        ),
      )
    }
    World(RoomBackupStore(db, clock), version, media)
  }

  private fun inMemory(): World = World(InMemoryBackupStore(), VersionId.random(), MediaId.random())

  private fun incoming(w: World, share: String, path: String) = NewBackupItem(
    kind = UploadKind.INCOMING, mediaId = w.media, versionId = w.version, size = 1_234, phone = null, expected = null, share = share,
    planned = SharePath.parse(path), quickHash = QuickHash("a".repeat(64)), sha256 = Sha256("c".repeat(64)),
  )

  private fun newCopy(w: World) = NewBackupItem(
    kind = UploadKind.NEW_COPY, mediaId = w.media, versionId = w.version, size = 1_234, phone = PhoneRef("external_primary", 42, "v1"),
    expected = FileStamp(1_234, 5_000, 7), share = "test", planned = SharePath.parse("Backup/S25/2026/10/IMG_1.jpg"), quickHash = QuickHash("a".repeat(64)),
  )

  private fun r14(w: World) = runBlocking<Unit> {
    val store = w.store
    val first = store.createIncoming(incoming(w, "test", "Ricevute/a.jpg"))
    // Lo stesso arrivo (stessa versione, share e cartella): lo stesso elemento, anche con il nome di un'altra app o le maiuscole diverse.
    assertThat(store.createIncoming(incoming(w, "test", "Ricevute/a.jpg")).id).isEqualTo(first.id)
    assertThat(store.createIncoming(incoming(w, "test", "Ricevute/IMG-WA0001.jpg")).id).isEqualTo(first.id)
    assertThat(store.createIncoming(incoming(w, "TEST", "RICEVUTE/a.jpg")).id).isEqualTo(first.id)
    // Un'altra cartella, o un'altra share (l'area comune): altri elementi.
    val otherFolder = store.createIncoming(incoming(w, "test", "Ricevute/Vacanze/a.jpg"))
    val common = store.createIncoming(incoming(w, "test-comune", "Famiglia/a.jpg"))
    assertThat(setOf(first.id, otherFolder.id, common.id)).hasSize(3)
    // E ognuno e' idempotente per conto suo.
    assertThat(store.createIncoming(incoming(w, "test-comune", "Famiglia/b.jpg")).id).isEqualTo(common.id)
    assertThat(store.itemsToResume(10).map { it.id }).containsExactly(first.id, otherFolder.id, common.id).inOrder()
    // Chiuso il primo, un nuovo "Salva sul NAS" nella stessa cartella e' un elemento nuovo.
    store.update(first.copy(state = UploadState.GONE))
    assertThat(store.createIncoming(incoming(w, "test", "Ricevute/a.jpg")).id).isNotEqualTo(first.id)
  }

  private fun r15(w: World) = runBlocking<Unit> {
    val store = w.store
    val arrival: BackupItem = store.createIncoming(incoming(w, "test-comune", "Famiglia/a.jpg"))
    // Solo un arrivo aperto: per il piano della 5A e per il guardiano la versione non e' in coda.
    assertThat(store.openFor(w.version)).isNull()
    assertThat(store.itemsToResume(10).map { it.id }).containsExactly(arrival.id)
    // Il backup della versione (la copia nuova) nasce lo stesso, e quella e' l'elemento aperto della versione.
    val copy = store.create(listOf(newCopy(w))).single()
    assertThat(copy.id).isNotEqualTo(arrival.id)
    assertThat(store.openFor(w.version)!!.id).isEqualTo(copy.id)
    // E l'arrivo, a sua volta, non e' scambiato per la copia nuova: restano due elementi, ognuno del suo tipo.
    assertThat(store.itemsToResume(10).map { it.kind }).containsExactly(UploadKind.INCOMING, UploadKind.NEW_COPY)
  }

  @Test
  fun r14_unaDestinazioneDiversaEUnElementoNuovo_Room() = r14(room())

  @Test
  fun r14_unaDestinazioneDiversaEUnElementoNuovo_InMemoria() = r14(inMemory())

  @Test
  fun r15_unArrivoNonBloccaIlBackupDellaVersione_Room() = r15(room())

  @Test
  fun r15_unArrivoNonBloccaIlBackupDellaVersione_InMemoria() = r15(inMemory())
}
