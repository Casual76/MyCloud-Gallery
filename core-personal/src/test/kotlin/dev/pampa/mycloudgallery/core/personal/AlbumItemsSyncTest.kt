package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Due dispositivi dello stesso account che aggiungono insieme 1.000 elementi diversi allo stesso album: **tutti presenti** su tutti e due
 * (docs/fase7-progetto.md D4: una voce `item:` per elemento fa convivere le aggiunte). Per la strada vera: NAS finto, teste e segmenti firmati,
 * codec, fusione, con l'impronta su ogni voce. La proiezione sul catalogo e' provata in core-albums.
 */
class AlbumItemsSyncTest {

  private val world = PersonalWorld()
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

  private fun fp(n: Int) = PersonalFingerprint(5_000L + n, QuickHash("%064x".format(n + 1)))

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  @Test
  fun dueDispositiviCheAggiungonoInsiemeMilleElementiDiversiLiHannoTuttiMaiUnoInMeno() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = PersonalAlbums(phone.store)
    val onTablet = PersonalAlbums(tablet.store)
    onPhone.define(album, "Mille e mille")
    syncAll(phone, tablet)

    // Insieme: nessuno dei due ha ancora visto cio' che aggiunge l'altro. Ogni elemento ha la sua voce, con la sua impronta.
    for (n in 0 until 1_000) onPhone.addItem(album, mediaId(n), (n + 1) * 1_000L, fp(n))
    for (n in 1_000 until 2_000) onTablet.addItem(album, mediaId(n), (n - 999) * 1_000L, fp(n))
    syncAll(phone, tablet, phone, tablet)

    for (albums in listOf(onPhone, onTablet)) {
      val listed = albums.albums().single()
      assertThat(listed.items).hasSize(2_000)
      assertThat(listed.items.toSet()).isEqualTo((0 until 2_000).map { mediaId(it) }.toSet())
    }
    // Le voci hanno attraversato il NAS con l'impronta (la strada vera: segmenti firmati e codec).
    val state = tablet.store.state()
    for (n in listOf(0, 1, 999, 1_000, 1_999)) {
      assertThat(state.getValue(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(mediaId(n)))).fingerprint).isEqualTo(fp(n))
    }
    assertThat(phone.store.state().keys).isEqualTo(tablet.store.state().keys)
    // E l'ordine e' lo stesso su tutti e due: stesse posizioni, stessi pari merito.
    assertThat(onPhone.albums().single().items).isEqualTo(onTablet.albums().single().items)
  }

  @Test
  fun unaRimozioneEUnRiordinoConcorrentiSiFondonoPerVoce() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = PersonalAlbums(phone.store)
    val onTablet = PersonalAlbums(tablet.store)
    onPhone.define(album, "Concorrenza")
    for (n in 1..5) onPhone.addItem(album, mediaId(n), n * 1_000L, fp(n))
    syncAll(phone, tablet)

    // Il telefono toglie il 2; il tablet sposta il 5 in testa e aggiunge il 6: tre voci diverse, nessuna si perde.
    onPhone.removeItem(album, mediaId(2))
    onTablet.addItem(album, mediaId(5), 500, fp(5))
    onTablet.addItem(album, mediaId(6), 6_000, fp(6))
    syncAll(phone, tablet, phone, tablet)

    for (albums in listOf(onPhone, onTablet)) {
      assertThat(albums.albums().single().items).containsExactly(mediaId(5), mediaId(1), mediaId(3), mediaId(4), mediaId(6)).inOrder()
    }
  }

  @Test
  fun leVociNuoveSiFondonoComeLeAltre_UltimaScritturaVince() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = PersonalAlbums(phone.store)
    val onTablet = PersonalAlbums(tablet.store)
    onPhone.define(album, "Titolo")
    onPhone.setSort(album, AlbumSort.OLDEST_FIRST)
    onPhone.exclude(album, mediaId(1), fingerprint = fp(1))
    syncAll(phone, tablet)
    // Il tablet toglie l'esclusione e sceglie un altro ordine: l'ultima scrittura per voce vince su tutti e due.
    world.clock.advance(60_000)
    onTablet.exclude(album, mediaId(1), on = false)
    onTablet.setSort(album, AlbumSort.CUSTOM)
    syncAll(tablet, phone)
    for ((albums, device) in listOf(onPhone to phone, onTablet to tablet)) {
      val state = albums.state(album, device.store.state())!!
      assertThat(state.sort).isEqualTo("custom")
      assertThat(state.excluded.single { it.mediaId == mediaId(1) }.on).isFalse()
    }
  }
}
