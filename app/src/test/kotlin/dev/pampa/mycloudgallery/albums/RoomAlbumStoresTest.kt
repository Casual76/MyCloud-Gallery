package dev.pampa.mycloudgallery.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.AlbumIdentity
import dev.pampa.mycloudgallery.core.albums.shared.AlbumPublishStore
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.MemoryAlbumPublishStore
import dev.pampa.mycloudgallery.core.albums.shared.MemoryAlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.NasAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.shared.NasSharedAlbumSync
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumReplica
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.hidden.MiniNas
import dev.pampa.mycloudgallery.hidden.RealDevice
import dev.pampa.mycloudgallery.hidden.RealFamily
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli adattatori Room della replica e del pubblicatore degli album condivisi (P13) contro le implementazioni in memoria di P2, con il codice
 * vero di P2 sopra (pubblicatore, replica, firme, registro): la stessa storia da' gli stessi contributi e lo stesso stato, e un processo nuovo
 * (store nuovi sullo stesso database) ritrova tutto: le voci pubblicate, quelle in coda (anche "da datare"), le teste, le teste rifiutate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomAlbumStoresTest {
  private val app: Application = ApplicationProvider.getApplicationContext()
  private val database = GalleryDatabase.build(app, null)
  private val family = RealFamily.shared
  private val nas = MiniNas(COMMON)
  private val clocks = FakeAlbumClocks(nas = 1_795_000_000_000L)

  @After
  fun close() = database.close()

  private fun identityOf(device: RealDevice): AlbumIdentity = object : AlbumIdentity {
    override val deviceId: String = device.id
    override val signer: Signer = device.signer

    override fun registry(): RegistryState = family.state
  }

  private fun publisher(device: RealDevice, store: AlbumPublishStore) =
    NasAlbumPublisher(identityOf(device), FakeAlbumFamily(account = AccountId.of(device.account), common = COMMON), { nas }, store, FakeAlbumWriteGate(), clocks)

  private fun roomPublishStore(device: RealDevice) = RoomAlbumPublishStore(database) { device.id to AccountId.of(device.account) }

  private val noCatalog = object : CatalogFacts {
    override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> = emptyMap()

    override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> = emptyMap()
  }

  private fun sync(device: RealDevice, store: AlbumReplicaStore): NasSharedAlbumSync {
    val identity = identityOf(device)
    return NasSharedAlbumSync(identity, { nas }, store, SharedAlbumReplica(identity, store, noCatalog), clocks, { true })
  }

  private val nonce = ByteArray(16) { (it * 3 + 1).toByte() }
  private val album = AlbumId.derived(AccountId.of(family.me.account), nonce)
  private val folder = SharePath.of("Album condivisi", "Prova")

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun item(n: Int, by: RealDevice) = SharedAlbumChange.Item(
    media = MediaId(UUID.nameUUIDFromBytes("m$n".toByteArray()).toString()),
    version = null,
    contentKey = ContentKey("%064x".format(n)),
    path = folder.child("foto $n.jpg"),
    position = n * 1_000L,
    addedBy = AccountId.of(by.account),
  )

  /** La storia: definizione, livello, elementi di due dispositivi, una lapide, una modifica "da datare" rimasta in coda. */
  private suspend fun history(alice: NasAlbumPublisher, bruno: NasAlbumPublisher, clocks: FakeAlbumClocks = this.clocks) {
    val definition = SharedAlbumChange.Definition("Prova", folder, SharedState.ACTIVE, AccountId.of(family.me.account), hex(nonce), null)
    alice.stage(album, listOf(definition, SharedAlbumChange.Level(ShareLevel.ADD)) + (1..40).map { item(it, family.me) })
    assertThat(alice.flush(album, FlushReason.SYNC_ROUND)).isInstanceOf(PublishOutcome.Published::class.java)
    clocks.advance(6.minutes.inWholeMilliseconds)
    bruno.stage(album, (41..50).map { item(it, family.other) })
    assertThat(bruno.flush(album, FlushReason.SYNC_ROUND)).isInstanceOf(PublishOutcome.Published::class.java)
    alice.stage(album, listOf(SharedAlbumChange.ItemRemoved(item(3, family.me).media)))
    clocks.advance(6.minutes.inWholeMilliseconds)
    assertThat(alice.flush(album, FlushReason.SYNC_ROUND)).isInstanceOf(PublishOutcome.Published::class.java)
    // Una modifica senza l'ora del NAS resta "da datare" in coda.
    val nasNow = clocks.nas
    clocks.nas = null
    alice.stage(album, listOf(item(60, family.me)))
    clocks.nas = nasNow
  }

  @Test
  fun ilPubblicatoreSuRoomSiComportaComeQuelloInMemoriaEDopoLaMorteRitrovaTutto() = runBlocking<Unit> {
    val room = roomPublishStore(family.me)
    history(publisher(family.me, room), publisher(family.other, MemoryAlbumPublishStore()))
    // La stessa storia sul pubblicatore in memoria (un altro NAS, un altro orologio uguale): stesso stato, tranne il segno della testa (la firma
    // ha un nonce casuale, quindi l'impronta dei byte firmati cambia da una corsa all'altra).
    val memory = MemoryAlbumPublishStore()
    val twinNas = MiniNas(COMMON)
    val twinClocks = FakeAlbumClocks(nas = 1_795_000_000_000L)
    fun twin(device: RealDevice, store: AlbumPublishStore) =
      NasAlbumPublisher(identityOf(device), FakeAlbumFamily(account = AccountId.of(device.account), common = COMMON), { twinNas }, store, FakeAlbumWriteGate(), twinClocks)
    history(twin(family.me, memory), twin(family.other, MemoryAlbumPublishStore()), twinClocks)
    val expected = memory.load(album)!!
    val actual = room.load(album)!!
    assertThat(actual.copy(headMark = null)).isEqualTo(expected.copy(headMark = null))

    // Un processo nuovo: store nuovo sullo stesso database.
    val reborn = roomPublishStore(family.me)
    val before = room.load(album)!!
    val after = reborn.load(album)!!
    assertThat(after.seq).isEqualTo(before.seq)
    assertThat(after.upTo).isEqualTo(before.upTo)
    assertThat(after.headVersion).isEqualTo(before.headVersion)
    assertThat(after.headMark).isEqualTo(before.headMark)
    assertThat(after.headCreatedAt).isEqualTo(before.headCreatedAt)
    assertThat(after.segments).isEqualTo(before.segments)
    assertThat(after.published).isEqualTo(before.published)
    assertThat(after.staged).isEqualTo(before.staged)
    assertThat(after.staged.values.single().at).isNull()
    assertThat(reborn.albums()).containsExactly(album)
    assertThat(room.unreadableRows + reborn.unreadableRows).isEqualTo(0)

    // Il pubblicatore rinato pubblica la modifica rimasta in coda, con l'ora del NAS di adesso.
    clocks.advance(6.minutes.inWholeMilliseconds)
    val rebornPublisher = publisher(family.me, reborn)
    assertThat(rebornPublisher.flush(album, FlushReason.RESUME)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(rebornPublisher.publishedUpTo(album).seq).isEqualTo(after.seq)
    assertThat(roomPublishStore(family.me).load(album)!!.staged).isEmpty()
  }

  @Test
  fun laReplicaSuRoomDaIStessiContributiDellaMemoriaESopravviveAlProcesso() = runBlocking<Unit> {
    history(publisher(family.me, roomPublishStore(family.me)), publisher(family.other, MemoryAlbumPublishStore()))
    val memory = MemoryAlbumReplicaStore()
    val room = RoomAlbumReplicaStore(database)
    sync(family.third, memory).sync(5.minutes)
    sync(family.third, room).sync(5.minutes)
    val expected = memory.contributions(album).sortedBy { it.device }
    assertThat(expected.map { it.device }.toSet()).containsExactly(family.me.id, family.other.id)
    assertThat(room.contributions(album).sortedBy { it.device }).isEqualTo(expected)
    assertThat(room.albums()).containsExactly(album)

    // Un processo nuovo: lo stesso contributo, voce per voce (segmento e segno "retrodatata" compresi).
    val reborn = RoomAlbumReplicaStore(database)
    assertThat(reborn.contributions(album).sortedBy { it.device }).isEqualTo(expected)
    assertThat(reborn.footprint(family.other.id)).isEqualTo(memory.footprint(family.other.id))
    assertThat(reborn.unreadableRows).isEqualTo(0)

    // Un giro senza novita' non riscrive niente e ritrova lo stesso.
    sync(family.third, reborn).sync(5.minutes)
    assertThat(reborn.contributions(album).sortedBy { it.device }).isEqualTo(expected)

    // La testa rifiutata e l'esclusione (memoria di un dispositivo revocato) si ritrovano.
    reborn.setRejectedHead(album, family.other.id, HeadMark(9, "abc"))
    reborn.put(expected.first { it.device == family.other.id }.copy(barred = true))
    val third = RoomAlbumReplicaStore(database)
    assertThat(third.rejectedHead(album, family.other.id)).isEqualTo(HeadMark(9, "abc"))
    assertThat(third.contribution(album, family.other.id)!!.barred).isTrue()
    third.setRejectedHead(album, family.other.id, null)
    assertThat(RoomAlbumReplicaStore(database).rejectedHead(album, family.other.id)).isNull()

    // Dimenticare un contributo lo toglie (le righe del mio pubblicatore restano).
    third.forget(album, family.other.id)
    third.forget(album, family.me.id)
    val fourth = RoomAlbumReplicaStore(database)
    assertThat(fourth.albums()).isEmpty()
    assertThat(fourth.contributions(album)).isEmpty()
    assertThat(roomPublishStore(family.me).load(album)).isNotNull()
  }

  /**
   * R2, R7 e R13 della revisione di sicurezza della Fase 7 sulla replica su Room: le attese di un contributo e l'ultima volta che una serie e'
   * stata vista senza novita' sopravvivono al processo; i conti per dispositivo (voci, album, byte) sono quelli della memoria e restano al passo;
   * con una cache piccola gli album escono dalla memoria e si rileggono uguali da Room.
   */
  @Test
  fun attese_visto_contiECacheLimitataSuRoom() = runBlocking<Unit> {
    history(publisher(family.me, roomPublishStore(family.me)), publisher(family.other, MemoryAlbumPublishStore()))
    val memory = MemoryAlbumReplicaStore()
    sync(family.third, memory).sync(5.minutes)
    val room = RoomAlbumReplicaStore(database)
    sync(family.third, room).sync(5.minutes)
    assertThat(room.footprint(family.me.id)).isEqualTo(memory.footprint(family.me.id))
    assertThat(room.footprint(family.me.id).bytes).isGreaterThan(0L)

    val hold = dev.pampa.mycloudgallery.core.albums.shared.ReplicaHold(HeadMark(4, "h4"), 1_795_000_900_000L, 2)
    room.setHold(album, family.other.id, hold)
    room.setHold(album, family.me.id, dev.pampa.mycloudgallery.core.albums.shared.ReplicaHold(null, 1_795_000_100_000L, 1))
    room.setSeenAt(album, family.other.id, 1_795_000_500_000L)
    val reborn = RoomAlbumReplicaStore(database)
    assertThat(reborn.hold(album, family.other.id)).isEqualTo(hold)
    assertThat(reborn.hold(album, family.me.id)!!.mark).isNull()
    assertThat(reborn.seenAt(album, family.other.id)).isEqualTo(1_795_000_500_000L)
    assertThat(reborn.seenAt(album, family.me.id)).isNotNull()
    reborn.setHold(album, family.other.id, null)
    assertThat(RoomAlbumReplicaStore(database).hold(album, family.other.id)).isNull()

    // I conti restano al passo dopo un'esclusione e un "dimentica".
    val expected = reborn.contributions(album).sortedBy { it.device }
    val before = reborn.footprint(family.other.id)
    reborn.forget(album, family.other.id)
    assertThat(reborn.footprint(family.other.id)).isEqualTo(dev.pampa.mycloudgallery.core.albums.shared.DeviceFootprint.NONE)
    reborn.put(expected.first { it.device == family.other.id })
    assertThat(reborn.footprint(family.other.id)).isEqualTo(before)
    // L'ora "visto" resta anche dopo "dimentica" (dice qualcosa del dispositivo, non del contributo).
    assertThat(RoomAlbumReplicaStore(database).seenAt(album, family.other.id)).isEqualTo(1_795_000_500_000L)

    // Una cache da una voce: l'album esce dalla memoria a ogni lettura di un altro, e si rilegge uguale.
    val tiny = RoomAlbumReplicaStore(database, maxCachedEntries = 1)
    val other = AlbumId.derived(AccountId.of("test2"), ByteArray(16) { 9 })
    tiny.put(expected.first().copy(album = other, entries = emptyList(), segments = emptyList()))
    assertThat(tiny.contributions(album).sortedBy { it.device }).isEqualTo(expected)
    assertThat(tiny.cacheSize().first).isEqualTo(1)
    assertThat(tiny.contributions(other)).hasSize(1)
    assertThat(tiny.contributions(album).sortedBy { it.device }).isEqualTo(expected)
  }

  private companion object {
    const val COMMON = "test-comune"
  }
}
