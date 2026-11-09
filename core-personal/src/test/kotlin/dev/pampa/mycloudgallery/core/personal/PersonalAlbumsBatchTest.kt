package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Review generale, OA-1: le modifiche agli album a lotti (`PersonalAlbums.batch`). Le stesse voci delle modifiche una alla volta (chiavi,
 * valori, impronte, niente lapidi per scelte mai fatte), in un salvataggio solo; se il blocco non arriva in fondo non si scrive niente.
 */
class PersonalAlbumsBatchTest {

  private val me = Base32.encode(ByteArray(16) { 1 })
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

  private fun fp(n: Int) = PersonalFingerprint(5_000L + n, QuickHash("%064x".format(n + 1)))

  /** Un catalogo che conosce l'impronta dei media da 0 a 99 (per le voci scritte senza passarla). */
  private object Catalog : PersonalProjection {
    override suspend fun apply(entry: PersonalEntry): ProjectionResult = ProjectionResult.NOT_PROJECTED

    override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

    override suspend fun localFlags(): List<LocalFlag> = emptyList()

    override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? =
      (0 until 100).firstOrNull { mediaId(it) == mediaId }?.let { PersonalFingerprint(5_000L + it, QuickHash("%064x".format(it + 1))) }
  }

  private fun store(ledger: InMemoryPersonalLedger = InMemoryPersonalLedger(), nasNow: () -> Long? = { NAS_NOW }) =
    LedgerPersonalStore(me, nasNow, ledger, Catalog)

  private fun Map<PersonalKey, PersonalEntry>.shape() = mapValues { Triple(it.value.value, it.value.fingerprint, it.value.author) }

  @Test
  fun unLottoScriveLeStesseVociDelleModificheUnaAllaVolta_inUnSalvataggioSolo() = runTest {
    val oneLedger = InMemoryPersonalLedger()
    val batchLedger = InMemoryPersonalLedger()
    val one = PersonalAlbums(store(oneLedger))
    val batchStore = store(batchLedger)
    val batched = PersonalAlbums(batchStore)

    one.setKind(album, AlbumKind.SMART)
    one.setSmart(album, PersonalFields.SMART_TEXT, "mare")
    one.setExample(album, mediaId(1), true)
    one.define(album, "Estate")
    for (n in 0 until 20) one.addItem(album, mediaId(n), (n + 1) * 1_000L)
    one.removeItem(album, mediaId(3))
    one.accept(album, mediaId(40))
    one.exclude(album, mediaId(41), fingerprint = fp(77))
    one.accept(album, mediaId(40), on = false)
    one.setCover(album, mediaId(2))
    one.setFrozen(album, true)
    one.setOrder(album, 3_000)
    one.setCopyOf(album, "condiviso")
    one.setSort(album, null)
    val oneStore = store(oneLedger)

    batched.batch {
      setKind(album, AlbumKind.SMART)
      setSmart(album, PersonalFields.SMART_TEXT, "mare")
      setExample(album, mediaId(1), true)
      define(album, "Estate")
      for (n in 0 until 20) addItem(album, mediaId(n), (n + 1) * 1_000L)
      removeItem(album, mediaId(3))
      accept(album, mediaId(40))
      exclude(album, mediaId(41), fingerprint = fp(77))
      accept(album, mediaId(40), on = false)
      setCover(album, mediaId(2))
      setFrozen(album, true)
      setOrder(album, 3_000)
      setCopyOf(album, "condiviso")
      setSort(album, null)
    }

    assertThat(batchLedger.saves).isEqualTo(1)
    assertThat(batchStore.state().shape()).isEqualTo(oneStore.state().shape())
    // Lette come album: lo stesso album, con gli stessi elementi nello stesso ordine.
    assertThat(batched.albums()).isEqualTo(PersonalAlbums(oneStore).albums())
    assertThat(batched.state(album)!!.items.single { it.mediaId == mediaId(5) }.fingerprint).isEqualTo(fp(5))
  }

  @Test
  fun spegnereUnaSceltaMaiFattaNonLasciaUnaLapide_eAccesaESpentaNelloStessoLottoSi() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    val albums = PersonalAlbums(store)

    albums.batch {
      accept(album, mediaId(1), on = false)
      exclude(album, mediaId(2), on = false)
      setExample(album, mediaId(3), on = false)
    }
    assertThat(store.entries.value).isEmpty()
    assertThat(ledger.saves).isEqualTo(0)

    albums.batch {
      accept(album, mediaId(1))
      accept(album, mediaId(1), on = false)
    }
    val key = PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumAccept(mediaId(1)))
    assertThat(store.get(key)!!.value).isEqualTo(PersonalValue.Removed)

    // Una scelta gia' nello stato (anche solo "da datare") si spegne con la lapide.
    val offline = store(nasNow = { null })
    val away = PersonalAlbums(offline)
    away.exclude(album, mediaId(9))
    away.batch { exclude(album, mediaId(9), on = false) }
    assertThat(offline.effective().getValue(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumExclude(mediaId(9)))).value).isEqualTo(PersonalValue.Removed)
  }

  @Test
  fun seIlBloccoNonArrivaInFondoNonSiScriveNiente() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    val albums = PersonalAlbums(store)
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        albums.batch {
          for (n in 0 until 10) addItem(album, mediaId(n), n * 1_000L)
          error("il catalogo e' caduto a meta'")
        }
      }
    }
    assertThat(store.entries.value).isEmpty()
    assertThat(ledger.saves).isEqualTo(0)
  }

  @Test
  fun unaRegolaCheNonEUnaRegolaRifiutaIlLotto() = runTest {
    val store = store()
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { PersonalAlbums(store).batch { setSmart(album, "titolo", "x") } }
    }
    assertThat(store.entries.value).isEmpty()
  }
}
