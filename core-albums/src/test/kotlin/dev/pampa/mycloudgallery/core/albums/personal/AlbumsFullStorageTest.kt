package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K3b, SOL-10 e OA-11: con la memoria del telefono piena il registro dei dati personali non si scrive, e un album **non resta a meta'**. Sugli album veri (catalogo Room, proiezione
 * sulle tabelle) e sullo store vero: ogni modifica lancia `IOException` (che chi sta sopra traduce in un esito) e non cambia niente, ne' nelle voci ne' nelle tabelle ne' nell'elenco;
 * liberata la memoria la stessa modifica riesce. E' cio' che rende vero il messaggio "Non e' cambiato niente" di "Aggiungi ad album".
 */
@RunWith(AndroidJUnit4::class)
class AlbumsFullStorageTest {

  private val room = AlbumsRoom()
  private val ledger = InMemoryPersonalLedger()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun close() {
    scope.cancel()
    room.close()
  }

  private fun albums(): Pair<CatalogAlbums, LedgerPersonalStore> {
    val store = LedgerPersonalStore(TrustIds.random(), { T0 }, ledger, RoomFingerprints(room))
    val visibility = RoomVisibility(room)
    return CatalogAlbums(store, room.catalog, room.db, visibility, visibility, FakeAlbumFamily(account = AccountId("alessio")), scope) to store
  }

  @Test
  fun conLaMemoriaPienaOgniModificaDiUnAlbumLanciaENonCambiaNiente() = runBlocking<Unit> {
    val (albums, store) = albums()
    val photos = (1..4).map { room.record(it) }
    val manual = (albums.create("Mare", AlbumKind.MANUAL, null) as CreateOutcome.Created).id
    assertThat(albums.add(manual, photos.take(2))).isEqualTo(AddOutcome.Added(2, 0))
    val smart = (albums.create("Neve", AlbumKind.SMART, SmartRules(null, null, "neve")) as CreateOutcome.Created).id
    val voices = store.state()
    val rows = room.db.albumDao().items(manual.value).size
    val listed = albums.all.value.albums.size

    ledger.full = true
    val attempts: List<Pair<String, suspend () -> Any>> = listOf(
      "creare" to { albums.create("Monti", AlbumKind.MANUAL, null) },
      "creare uno smart" to { albums.create("Lago", AlbumKind.SMART, SmartRules(null, null, "lago")) },
      "rinominare" to { albums.rename(manual, "Lago") },
      "aggiungere" to { albums.add(manual, photos.drop(2)) },
      "togliere" to { albums.remove(manual, photos.take(1)) },
      "riordinare" to { albums.reorder(manual, photos[0], photos[1]) },
      "copertina" to { albums.setCover(manual, photos[0]) },
      "ordine dell'album" to { albums.setSort(manual, AlbumSort.OLDEST_FIRST) },
      "congelare" to { albums.freeze(smart) },
      "regole" to { albums.setRules(smart, SmartRules(null, null, "montagna")) },
      "ordine dell'elenco" to { albums.setListSort(AlbumListSort.NAME) },
      "posto nell'elenco" to { albums.moveInList(smart, null) },
      "eliminare" to { albums.delete(manual) },
    )
    for ((name, attempt) in attempts) {
      var thrown: Throwable? = null
      try {
        attempt()
      } catch (e: IOException) {
        thrown = e
      }
      assertWithMessage("$name con la memoria piena: l'eccezione e' IOException").that(thrown).isInstanceOf(IOException::class.java)
      assertWithMessage("$name: le voci dei dati personali non cambiano").that(store.state()).isEqualTo(voices)
      assertWithMessage("$name: le righe dell'album non cambiano").that(room.db.albumDao().items(manual.value)).hasSize(rows)
      assertWithMessage("$name: l'elenco degli album non cambia").that(albums.all.value.albums).hasSize(listed)
    }

    // Liberata la memoria, la stessa modifica riesce (riprovare e' sicuro) e crea un album solo.
    ledger.full = false
    assertThat(albums.add(manual, photos.drop(2))).isEqualTo(AddOutcome.Added(2, 0))
    assertThat(albums.rename(manual, "Lago")).isEqualTo(EditOutcome.Done)
    assertThat(albums.create("Monti", AlbumKind.MANUAL, null)).isInstanceOf(CreateOutcome.Created::class.java)
    assertThat(albums.all.value.albums).hasSize(listed + 1)
    assertThat(room.db.albumDao().items(manual.value)).hasSize(rows + 2)
  }

  @Test
  fun unLottoDiFotoNonRestaAMetaConLaMemoriaPiena() = runBlocking<Unit> {
    val (albums, store) = albums()
    val photos = (1..30).map { room.record(it) }
    val album = (albums.create("Tante", AlbumKind.MANUAL, null) as CreateOutcome.Created).id
    ledger.full = true
    var failed = false
    try {
      albums.add(album, photos)
    } catch (_: IOException) {
      failed = true
    }
    assertThat(failed).isTrue()
    // Ne' una foto sulle tabelle, ne' una voce in memoria, ne' una voce sul disco: il messaggio "Non e' cambiato niente" e' vero.
    assertThat(room.db.albumDao().items(album.value)).isEmpty()
    assertThat(store.state().keys.filter { it.field.startsWith("item:") }).isEmpty()
    assertThat(ledger.snapshot.entries.keys.filter { it.field.startsWith("item:") }).isEmpty()
  }
}
