package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.testing.FailingAlbumPorts
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareRunner
import dev.pampa.mycloudgallery.core.albums.testing.ReferenceSharePlanner
import dev.pampa.mycloudgallery.core.albums.testing.ShareRunnerPorts
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Isolamento degli errori e tetti (P2, come la revisione A della Fase 6), attestazione (D9), nessuna azione da dati remoti (§4.3, C10) e la
 * chiusura vista da chi legge (Q5).
 */
class SharedAlbumIsolationTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int, on: List<AlbumDevice> = listOf(alice, bruno), place: MediaPlace = MediaPlace.COMMON): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = place, path = "Album condivisi/Gita/f$n.jpg")
    on.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account)

  private suspend fun album(level: ShareLevel, title: String = "Gita", by: AlbumDevice = alice): AlbumId = by.createAlbum(title, level).also { world.settle() }

  /** Un NAS che sbaglia (eccezioni qualunque, anche un errore di memoria) solo sotto la cartella di un album. */
  private class Poisoned(private val delegate: NasFileSystem, private val poison: String, private val error: () -> Throwable) : NasFileSystem {
    override val shareName: String get() = delegate.shareName

    private fun check(path: SharePath) {
      if (path.value.contains(poison)) throw error()
    }

    override fun list(dir: SharePath, limit: Int): NasListing = delegate.list(dir, limit).also { check(dir) }

    override fun read(path: SharePath, maxBytes: Int): NasRead = delegate.read(path, maxBytes).also { check(path) }

    override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = delegate.publishExclusive(path, bytes)
  }

  /** Un album ostile o un NAS che sbaglia su un album non fermano gli altri album ne' la sincronizzazione dell'indice (sync non lancia mai). */
  @Test
  fun unAlbumOstileNonFermaGliAltriNeLaSincronizzazione() = runBlocking<Unit> {
    val good = album(ShareLevel.ALL, "Buono")
    val bad = album(ShareLevel.ALL, "Cattivo")
    val a = file(1)
    alice.publisher.stage(good, listOf(item(a, alice)))
    alice.publisher.stage(bad, listOf(item(a, alice)))
    world.settle()
    for (error in listOf({ IllegalStateException("boom") }, { OutOfMemoryError("finto") }, { java.io.IOException("rete") })) {
      val reader = world.device("test2", fs = { Poisoned(it, AlbumIds.text(bad), error) })
      world.devices.remove(reader)
      var after = false
      // Il giro dell'indice (P13): prima l'indice, poi gli album, poi il resto. Gli album non lanciano.
      val report = reader.sync.sync(10.minutes)
      after = true
      assertThat(after).isTrue()
      assertThat(report.incomplete).isTrue()
      if (error() !is java.io.IOException) {
        assertThat(reader.sync.syncErrors).isAtLeast(1)
        assertThat(reader.view(good)!!.merged.items).hasSize(1)
      }
      assertThat(reader.view(bad)).isNull()
    }
  }

  /** Tetti per dispositivo (voci e album nella replica) e per giro (byte): un dispositivo compromesso non riempie il telefono. */
  @Test
  fun tettiPerDispositivoEPerGiro() = runBlocking<Unit> {
    val carla = world.device("carla")
    val limits = AlbumSyncLimits(maxEntriesPerDevice = 6, maxAlbumsPerDevice = 3)
    val albums = listOf(album(ShareLevel.ALL, "Uno"), album(ShareLevel.ALL, "Due"), album(ShareLevel.ALL, "Tre", carla), album(ShareLevel.ALL, "Quattro", carla))
    // bruno contribuisce a quattro album: il quarto supera il tetto di album per dispositivo.
    for ((i, album) in albums.withIndex()) bruno.publisher.stage(album, listOf(item(file(10 + i), bruno)))
    world.settle()
    val capped = world.device("test")
    world.devices.remove(capped)
    capped.sync = NasSharedAlbumSync(capped.identity, { world.nas }, capped.replicaStore, capped.replica, world.clocks, { true }, limits, io = kotlinx.coroutines.Dispatchers.Unconfined)
    capped.sync.sync(10.minutes)
    assertThat(albums.count { album -> capped.view(album) != null }).isEqualTo(4)
    val withBruno = albums.filter { album -> capped.view(album)!!.merged.winners.values.any { it.author == bruno.deviceId } }
    assertThat(withBruno).hasSize(3)
    // Troppe voci di un dispositivo: il contributo oltre il tetto si rifiuta, quello di prima resta.
    val target = withBruno.first()
    bruno.publisher.stage(target, (20..26).map { item(file(it), bruno) })
    world.settle()
    capped.sync.sync(10.minutes)
    assertThat(capped.view(target)!!.merged.items.values.count { it.addedBy == bruno.account }).isEqualTo(1)
    assertThat(alice.view(target)!!.merged.items.values.count { it.addedBy == bruno.account }).isEqualTo(8)
    // Il tetto di byte del giro: i contributi aspettano il giro dopo (incompleto); niente entra a meta'.
    val tiny = world.device("test")
    world.devices.remove(tiny)
    tiny.sync = NasSharedAlbumSync(tiny.identity, { world.nas }, tiny.replicaStore, tiny.replica, world.clocks, { true }, AlbumSyncLimits(maxBytesPerRound = 1), io = kotlinx.coroutines.Dispatchers.Unconfined)
    assertThat(tiny.sync.sync(10.minutes).incomplete).isTrue()
    assertThat(tiny.replicaStore.albums()).isEmpty()
  }

  /** Voci "dal futuro" (oltre 2 minuti): il contributo aspetta; quando l'ora del NAS ci arriva, vale. */
  @Test
  fun vociDalFuturoAspettano() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    val a = file(1)
    val now = world.clocks.nas!!
    val future = AlbumEntry(album, AlbumField.Item(a.id), now + 10 * 60_000, bruno.deviceId, bruno.account, AlbumValue.Item(a.contentKey, SharePath.parse(a.path!!), 1, bruno.account))
    RawHeads.write(world, bruno.signer, album, listOf(future), 1)
    alice.syncNow()
    assertThat(alice.view(album)!!.merged.items).isEmpty()
    world.clocks.advance(9 * 60_000)
    alice.syncNow()
    assertThat(alice.view(album)!!.merged.items.keys).containsExactly(a.id)
  }

  /** D9: un elemento si mostra solo se il MIO catalogo vede il file nell'area comune con la stessa chiave; mai un nascosto (D22). */
  @Test
  fun attestaIlCatalogoDiChiLegge() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    val a = file(1, on = listOf(alice))
    val p = file(2, on = listOf(alice, bruno), place = MediaPlace.MY_PRIVATE)
    alice.publisher.stage(album, listOf(item(a, alice), item(p, alice)))
    world.settle()
    // bruno non vede a (non e' nel suo catalogo) ne' p (privato): niente da mostrare, nessun conteggio, nessuna azione.
    assertThat(bruno.shared.visibleItems(album)).isEmpty()
    assertThat(bruno.shared.visible.value.single().count).isEqualTo(0)
    assertThat(bruno.view(album)!!.unattested).isEqualTo(2)
    // La sua scansione trova a nell'area comune: adesso si mostra.
    bruno.library.put(a)
    bruno.replica.recompute()
    assertThat(bruno.shared.visibleItems(album)).containsExactly(a.id)
    assertThat(bruno.view(album)!!.unattested).isEqualTo(1)
    // Nascosto da bruno: non si mostra a lui e non conta (agli altri si').
    bruno.library.hide(a.id)
    bruno.replica.recompute()
    assertThat(bruno.shared.visibleItems(album)).isEmpty()
    assertThat(bruno.shared.visible.value.single().count).isEqualTo(0)
    assertThat(alice.shared.visibleItems(album)).containsExactly(a.id)
  }

  /**
   * §4.3, C10, E-S11: definizioni vere e false, elementi privati, voci "elimina", chiusure, eredita': nessuna lettura, proiezione, avviso o
   * ripresa chiama mai `AlbumMover`, `AlbumUploader`, `SafeCopyRequester` o `TicketStatus` (le porte qui falliscono se chiamate).
   */
  @Test
  fun unaDefinizioneVeraOFalsaNonChiamaMaiLePorte() = runBlocking<Unit> {
    val failing = FailingAlbumPorts()
    val album = album(ShareLevel.ALL)
    val a = file(1)
    val priv = file(2, place = MediaPlace.MY_PRIVATE)
    bruno.publisher.stage(album, listOf(item(a, bruno), item(priv, bruno), SharedAlbumChange.ItemRemoved(FakeAlbumLibrary.id(3))))
    val fake = AlbumId.derived(AccountId("test2"), ByteArray(16) { 6 })
    val now = world.clocks.nas!!
    RawHeads.write(
      world, bruno.signer, fake,
      listOf(
        AlbumEntry(fake, AlbumField.Definition, now, bruno.deviceId, bruno.account, AlbumValue.Definition("Foto da cancellare", SharePath.of("Album condivisi", "X"), AlbumDefinitionState.DELETED, bruno.account, "06".repeat(16), "keep_copy")),
        AlbumEntry(fake, AlbumField.Item(priv.id), now, bruno.deviceId, bruno.account, AlbumValue.Item(priv.contentKey, SharePath.parse(priv.path!!), 1, bruno.account)),
      ),
      1,
    )
    alice.shared.close(album, CloseMode.DELETE, OthersAfterClose.KEEP_COPY)
    val ports = ShareRunnerPorts(
      InMemoryShareDiary(), alice.library, alice.shared, { id, op, item -> alice.shared.allowed(id, op, item) }, failing, failing, failing, alice.publisher, failing,
      FakeSharedOrigins(), alice.albumFamily, alice.gate, world.clocks,
    )
    val runner = InMemoryShareRunner(ports, ReferenceSharePlanner(alice.library, alice.shared, { id, op, item -> alice.shared.allowed(id, op, item) }, alice.albumFamily, alice.gate))
    repeat(3) {
      world.settle()
      runner.resumeAll(1.minutes)
      for (d in world.devices) {
        d.shared.refresh()
        d.shared.keptCopies()
        d.shared.notices.value.forEach { if (it is SharedAlbumNotice.Closed) d.shared.dismissNotice(it) }
      }
    }
    assertThat(failing.calls).isEqualTo(0)
    assertThat(alice.library[priv.id]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(bruno.library[priv.id]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
  }

  /** Q5 da chi legge: "remove" senza copia, "keep_copy" con la copia (attestata), un codice sconosciuto vale "remove", chi ha lasciato niente. */
  @Test
  fun q5AfterCloseFirmatoSuiLettori() = runBlocking<Unit> {
    val carla = world.device("carla")
    val (one, two, three) = listOf(album(ShareLevel.ALL, "Uno"), album(ShareLevel.ALL, "Due"), album(ShareLevel.ALL, "Tre"))
    val a = file(1, on = listOf(alice, bruno, carla))
    for (album in listOf(one, two, three)) alice.publisher.stage(album, listOf(item(a, alice)))
    world.settle()
    carla.shared.leave(two)
    alice.shared.close(one, CloseMode.STOP, OthersAfterClose.REMOVE)
    alice.shared.close(two, CloseMode.DELETE, OthersAfterClose.KEEP_COPY)
    world.settle()
    // Un codice che quest'app non conosce (un'app piu' nuova, o un dato ostile del creatore): vale "remove".
    val nonce = alice.view(three)!!.merged.nonceHex
    val now = world.clocks.nas!!
    val raw = world.family.approve("PC di test", "test")
    RawHeads.write(world, raw, three, listOf(AlbumEntry(three, AlbumField.Definition, now, dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.deviceIdOf(raw.publicKey), AccountId("test"), AlbumValue.Definition("Tre", SharePath.of("Album condivisi", "Tre"), AlbumDefinitionState.CLOSED, AccountId("test"), nonce, "zzz"))), 1)
    world.settle()
    fun closed(d: AlbumDevice) = d.shared.notices.value.filterIsInstance<SharedAlbumNotice.Closed>().associateBy { it.album }
    assertThat(closed(bruno)[one]!!.keptCopy).isNull()
    assertThat(closed(bruno)[two]!!.keptCopy).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies.copyIdFor(two, bruno.account))
    assertThat(closed(bruno)[three]!!.keptCopy).isNull()
    assertThat(bruno.shared.keptCopies().map { it.shared }).containsExactly(two)
    assertThat(bruno.shared.keptCopies().single().items).containsExactly(a.id)
    // carla aveva lasciato "Due": nessun avviso, nessuna copia.
    assertThat(closed(carla).keys).containsExactly(one, three)
    assertThat(carla.shared.keptCopies()).isEmpty()
    // Il creatore: "Uno" (smesso) gli resta come personale, "Due" (eliminato) no; nessun avviso per lui.
    assertThat(alice.shared.keptCopies().map { it.shared }).containsExactly(one, three)
    assertThat(closed(alice)).isEmpty()
    // Nessuno riapre: il creatore non puo' piu' cambiare niente.
    assertThat(alice.shared.allowed(one, AlbumOp.RENAME)).isFalse()
    assertThat(alice.shared.visible.value).isEmpty()
  }
}
