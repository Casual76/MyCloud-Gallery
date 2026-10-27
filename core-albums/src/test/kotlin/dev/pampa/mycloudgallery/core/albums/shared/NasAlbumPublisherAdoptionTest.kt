package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R12 della revisione di sicurezza della Fase 7: il pubblicatore ricostruito dopo la perdita del suo store non si fida ciecamente del NAS.
 * Prima: un mio segmento rovinato dal PC (o nessuna mia testa valida) faceva ripartire da zero, e la testa nuova toglieva a tutti le voci gia'
 * pubblicate (la definizione compresa, per il creatore); con le mie teste piu' recenti tolte dal PC ripubblicava uno stato vecchio con un
 * numero gia' visto (un'equivocazione che chi legge rifiuta: le modifiche nuove non arrivavano piu').
 */
class NasAlbumPublisherAdoptionTest {

  private val world = AlbumWorld()
  private val me = world.device("test")
  private val other = world.device("test2")

  private fun media(n: Int) = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")

  private fun item(n: Int) = media(n).let { SharedAlbumChange.Item(it.id, null, it.contentKey, SharePath.parse(it.path!!), n * 1_000L, me.account) }

  private fun myFiles(album: AlbumId, kind: String) = world.nas.paths().filter { it.contains("/albums/${AlbumIds.text(album)}/${me.deviceId}/$kind/") }.sorted()

  private suspend fun albumWithFiveItems(): AlbumId {
    val album = me.createAlbum("Gita", ShareLevel.ALL)
    me.publisher.stage(album, (1..5).map { item(it) })
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(5)
    return album
  }

  /** Un dispositivo nuovo dello stesso dispositivo dopo un azzeramento: store del pubblicatore e replica persi. */
  private fun loseEverything() {
    me.publishStore = MemoryAlbumPublishStore()
    me.replicaStore = MemoryAlbumReplicaStore()
    me.restart()
  }

  private suspend fun flushLater(album: AlbumId): PublishOutcome {
    world.clocks.advance(PublishRules.MIN_HEAD_INTERVAL_MS + PublishRules.DEBOUNCE_MS)
    return me.publisher.flush(album, FlushReason.SYNC_ROUND)
  }

  @Test
  fun unMioSegmentoRovinatoNonFaSparireLeVociGiaPubblicate() = runBlocking<Unit> {
    val album = albumWithFiveItems()
    // Dal PC: un mio segmento sostituito con altri byte (stesso nome).
    val segment = myFiles(album, "seg").first()
    world.nas.plant(segment, ByteArray(64) { 7 })
    loseEverything()
    val heads = myFiles(album, "head")
    me.publisher.stage(album, listOf(item(6)))
    assertThat(flushLater(album)).isEqualTo(PublishOutcome.Failed)
    assertThat(me.publisher.adoptionRefused).isAtLeast(1)
    // Nessuna testa nuova: chi legge ha ancora le cinque voci e la definizione.
    assertThat(myFiles(album, "head")).isEqualTo(heads)
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(5)
    assertThat(other.view(album)!!.merged.title).isEqualTo("Gita")
  }

  @Test
  fun nessunaMiaTestaValidaSulNasNonFaRipartireDaZero() = runBlocking<Unit> {
    val album = albumWithFiveItems()
    for (head in myFiles(album, "head")) world.nas.plant(head, ByteArray(32) { 1 })
    loseEverything()
    me.publisher.stage(album, listOf(item(6)))
    assertThat(flushLater(album)).isEqualTo(PublishOutcome.Failed)
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(5)
  }

  @Test
  fun conLeTestePiuRecentiTolteDalPcValeLaCopiaDellaReplica() = runBlocking<Unit> {
    val album = albumWithFiveItems()
    me.shared.rename(album, "Gita al lago")
    world.settle()
    val heads = myFiles(album, "head")
    assertThat(heads.size).isAtLeast(2)
    val seen = other.replicaStore.contribution(album, me.deviceId)!!.mark.version
    // Dal PC: via tutte le mie teste tranne la prima; lo store del pubblicatore perso (la replica di questo dispositivo no).
    heads.drop(1).forEach(world.nas::delete)
    me.publishStore = MemoryAlbumPublishStore()
    me.restart()
    me.publisher.stage(album, listOf(item(6)))
    assertThat(flushLater(album)).isInstanceOf(PublishOutcome.Published::class.java)
    // La testa nuova viene dopo l'ultima che chi legge ha visto, con tutte le voci: niente equivocazione, niente perso.
    assertThat((me.publisher.publishedUpTo(album).seq)).isGreaterThan(0L)
    assertThat(me.publishStore.load(album)!!.headVersion).isGreaterThan(seen)
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(6)
    assertThat(other.view(album)!!.merged.title).isEqualTo("Gita al lago")
  }
}
