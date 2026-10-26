package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La replica vera sopra il NAS finto e la famiglia vera (P2, §2.5 "Ricevere", D6-D9): E-SH6, E-SH12, E-SH13, E-SH14, dismessi e revocati
 * (A7, E-SH20), anti-rollback, voci retrodatate (§2.4, L59).
 */
class SharedAlbumSyncTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int, place: MediaPlace = MediaPlace.COMMON, on: List<AlbumDevice> = listOf(alice, bruno)): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = place, path = "Album condivisi/Gita/f$n.jpg")
    on.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account)

  private suspend fun album(level: ShareLevel): AlbumId = alice.createAlbum("Gita", level).also { world.settle() }

  private fun shown(device: AlbumDevice, album: AlbumId) = runBlocking { device.shared.visibleItems(album) }

  /** E-SH6: due familiari che modificano lo stesso album non si sovrascrivono (un contributo ciascuno, ognuno nella sua cartella). */
  @Test
  fun eSh6DueFamiliariCheAggiungonoInsiemeCiSonoEntrambi() = runBlocking<Unit> {
    val album = album(ShareLevel.ADD)
    val a = file(1)
    val b = file(2)
    alice.publisher.stage(album, listOf(item(a, alice)))
    bruno.publisher.stage(album, listOf(item(b, bruno)))
    world.settle()
    for (d in listOf(alice, bruno)) assertThat(shown(d, album)).containsExactly(a.id, b.id)
    val text = AlbumIds.text(album)
    val dirs = world.nas.paths().filter { it.contains("/albums/$text/") }.map { it.substringAfter("/albums/$text/").substringBefore('/') }.toSet()
    assertThat(dirs).containsExactly(alice.deviceId, bruno.deviceId)
    assertThat(alice.view(album)!!.merged).isEqualTo(bruno.view(album)!!.merged)
  }

  /** E-SH12: lo stesso media aggiunto insieme da due familiari: una voce vince (sempre la stessa per tutti), un solo elemento. */
  @Test
  fun eSh12LoStessoMediaDaDueFamiliariUnSoloElemento() = runBlocking<Unit> {
    val album = album(ShareLevel.ADD)
    val a = file(1)
    alice.publisher.stage(album, listOf(item(a, alice)))
    bruno.publisher.stage(album, listOf(item(a, bruno)))
    world.settle()
    val merged = alice.view(album)!!.merged
    assertThat(merged.items).hasSize(1)
    assertThat(bruno.view(album)!!.merged).isEqualTo(merged)
    assertThat(merged.denied.single().reason).isEqualTo(DenyReason.OWNERSHIP)
    assertThat(shown(bruno, album)).containsExactly(a.id)
  }

  /** E-SH13: il livello cambia mentre un familiare aggiunge: la sua voce successiva al cambio si scarta e lui lo sa (avviso). */
  @Test
  fun eSh13IlLivelloCambiaMentreSiAggiunge() = runBlocking<Unit> {
    val album = album(ShareLevel.ADD)
    val a = file(1)
    alice.shared.setLevel(album, ShareLevel.VIEW)
    world.clocks.advance(60_000)
    // bruno non ha ancora letto il cambio: per lui il livello e' quello di prima, e la sua voce porta la sua ora.
    assertThat(bruno.shared.allowed(album, dev.pampa.mycloudgallery.core.albums.contract.AlbumOp.ADD_MEDIA)).isTrue()
    bruno.publisher.stage(album, listOf(item(a, bruno)))
    world.settle()
    assertThat(alice.view(album)!!.merged.items).isEmpty()
    assertThat(shown(bruno, album)).isEmpty()
    val notice = bruno.shared.notices.value.filterIsInstance<SharedAlbumNotice.NotAddedLevelChanged>().single()
    assertThat(notice.count).isEqualTo(1)
    assertThat(bruno.shared.allowed(album, dev.pampa.mycloudgallery.core.albums.contract.AlbumOp.ADD_MEDIA)).isFalse()
    bruno.shared.dismissNotice(notice)
    assertThat(bruno.shared.notices.value).isEmpty()
  }

  /**
   * E-SH14: definizioni e contributi falsi, malformati, con un id che non torna o di un dispositivo sconosciuto: ignorati e contati, e l'album
   * vero resta com'e'. Chi legge non scrive mai niente sul NAS.
   */
  @Test
  fun eSh14ContributiFalsiOMalformatiIgnoratiEContati() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    val a = file(1)
    alice.publisher.stage(album, listOf(item(a, alice)))
    world.settle()
    val text = AlbumIds.text(album)
    val at = world.clocks.nas!!
    // Spazzatura nelle cartelle (nomi che non sono id, file che non sono teste).
    world.nas.plant(".mycloudgallery/albums/non-un-id/x/head/00000000000000000001", "x".toByteArray())
    world.nas.plant(".mycloudgallery/albums/$text/non-un-dispositivo/head/00000000000000000001", "x".toByteArray())
    // Un dispositivo che non e' nel registro.
    val stranger = FamilyKey.generate()
    RawHeads.write(world, stranger, album, listOf(AlbumEntry(album, AlbumField.Item(FakeAlbumLibrary.id(7)), at, dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.deviceIdOf(stranger.publicKey), AccountId("test2"), null)), 1)
    // Il tablet di bruno con una voce a nome di alice (account diverso dal firmatario).
    val carlaKey = world.family.approve("Tablet di Carla", "carla")
    val carla = dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.deviceIdOf(carlaKey.publicKey)
    val forgedAccount = AlbumEntry(album, AlbumField.Item(FakeAlbumLibrary.id(8)), at, carla, AccountId("test"), null)
    RawHeads.write(world, carlaKey, album, listOf(forgedAccount), 1)
    // Una definizione con un id che non torna (album di test, creatore dichiarato carla).
    val other = AlbumId.derived(AccountId("test"), ByteArray(16) { 4 })
    val badDefinition = AlbumEntry(other, AlbumField.Definition, at, carla, AccountId("carla"), AlbumValue.Definition("Foto da cancellare", SharePath.of("Album condivisi", "X"), AlbumDefinitionState.ACTIVE, AccountId("carla"), "04".repeat(16), null))
    RawHeads.write(world, carlaKey, other, listOf(badDefinition), 1)
    // Un segmento cambiato dal PC dopo la pubblicazione e prima che gli altri lo leggano (impronta diversa da quella della testa firmata).
    val dora = world.device("dora")
    dora.publisher.stage(album, listOf(SharedAlbumChange.ItemRemoved(FakeAlbumLibrary.id(9))))
    world.clocks.advance(dev.pampa.mycloudgallery.core.albums.contract.PublishRules.DEBOUNCE_MS)
    dora.flushAll()
    val doraSeg = world.nas.paths().first { it.contains("/albums/$text/${dora.deviceId}/seg/") }
    world.nas.plant(doraSeg, "MCGB".toByteArray() + ByteArray(40))
    val before = world.nas.published.size
    repeat(2) { alice.syncNow() }
    assertThat(world.nas.published.size).isEqualTo(before) // chi legge non scrive
    assertThat(alice.view(other)).isNull()
    assertThat(alice.replica.unresolved.value).doesNotContain(album)
    val merged = alice.view(album)!!.merged
    assertThat(merged.items.keys).containsExactly(a.id)
    assertThat(merged.winners.values.map { it.author }.toSet()).containsNoneOf(carla, dora.deviceId, dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.deviceIdOf(stranger.publicKey))
    // Rifiutati: l'account falso di carla, la definizione con l'id che non torna, il segmento cambiato di dora.
    assertThat(alice.reports.sumOf { it.contributionsRejected }).isAtLeast(3)
    assertThat(alice.replicaStore.rejectedHead(album, dora.deviceId)).isNotNull()
    assertThat(alice.replicaStore.rejectedHead(album, carla)).isNotNull()
  }

  /** A7: di un dismesso valgono solo le teste congelate; un revocato o perso si ignora del tutto (E-SH20); i file restano dove sono. */
  @Test
  fun dismessoSoloTesteCongelate_revocatoIgnorato() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    val a = file(1)
    val b = file(2)
    alice.publisher.stage(album, listOf(item(a, alice)))
    bruno.publisher.stage(album, listOf(item(b, bruno)))
    world.settle()
    val brunoMark = alice.replicaStore.contribution(album, bruno.deviceId)!!.mark
    world.family.changeStatus(bruno.signer, DeviceStatus.RETIRED, listOf(brunoMark.contentHash))
    world.settle()
    assertThat(shown(alice, album)).containsExactly(a.id, b.id)
    // Una testa nuova del dismesso (non congelata) non vale: resta quella congelata.
    val c = file(3)
    val now = world.clocks.nas!!
    val entries = alice.replicaStore.contribution(album, bruno.deviceId)!!.entries.map { it.entry } +
      AlbumEntry(album, AlbumField.Item(c.id), now, bruno.deviceId, bruno.account, AlbumValue.Item(c.contentKey, SharePath.parse(c.path!!), 5, bruno.account))
    RawHeads.write(world, bruno.signer, album, entries, brunoMark.version + 1)
    world.settle()
    assertThat(shown(alice, album)).containsExactly(a.id, b.id)
    // Revocato: il suo contributo esce del tutto (anche b), i file restano.
    world.family.changeStatus(bruno.signer, DeviceStatus.REVOKED)
    world.settle()
    assertThat(shown(alice, album)).containsExactly(a.id)
    assertThat(alice.library[b.id]!!.place).isEqualTo(MediaPlace.COMMON)
    // E-SH20: un altro dispositivo dello stesso account ripubblica l'elemento (azione locale): torna.
    val brunoTablet = world.device("test2", library = bruno.library)
    brunoTablet.publisher.stage(album, listOf(item(b, brunoTablet)))
    world.settle()
    assertThat(shown(alice, album)).containsExactly(a.id, b.id)
  }

  /** Anti-rollback: una testa accettata che sparisce o una vecchia copiata sotto un numero nuovo non riportano indietro l'album. */
  @Test
  fun unaTestaVecchiaNonRiportaIndietroLAlbum() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    alice.shared.rename(album, "Gita al mare")
    world.settle()
    assertThat(bruno.view(album)!!.merged.title).isEqualTo("Gita al mare")
    val text = AlbumIds.text(album)
    val heads = world.nas.paths().filter { it.contains("/albums/$text/${alice.deviceId}/head/") }.sorted()
    assertThat(heads).hasSize(2)
    // Chi cancella la testa nuova dal PC non fa tornare il titolo vecchio a chi l'aveva gia' letta.
    val newest = world.nas.bytes(heads.last())!!
    world.nas.delete(heads.last())
    world.settle()
    assertThat(bruno.view(album)!!.merged.title).isEqualTo("Gita al mare")
    // La testa vecchia copiata sotto il numero successivo: posto sbagliato, rifiutata.
    world.nas.plant(heads.last(), newest)
    world.nas.plant(NasLayout.albumHead(text, alice.deviceId, 3).value, world.nas.bytes(heads.first())!!)
    val rejected = bruno.reports.sumOf { it.contributionsRejected }
    world.settle()
    assertThat(bruno.view(album)!!.merged.title).isEqualTo("Gita al mare")
    assertThat(bruno.reports.sumOf { it.contributionsRejected }).isGreaterThan(rejected)
    // La serie per nome con l'indizio dell'elenco: la testa accettata sparita senza una piu' nuova e' un rollback.
    val mark = bruno.replicaStore.contribution(album, alice.deviceId)!!.mark
    world.nas.delete(heads.last())
    world.nas.delete(NasLayout.albumHead(text, alice.deviceId, 3).value)
    val read = AlbumHeadSeries.readLatest(world.nas, world.family.state, album, alice.deviceId, mark, AlbumReadBudget(100), listingHint = true)
    assertThat(read).isEqualTo(AlbumHeadRead.Rejected(dev.pampa.mycloudgallery.core.trust.RejectReason.ROLLBACK))
  }

  /**
   * §2.4, L59: una voce **nuova** (assente nella testa precedente dello stesso dispositivo) con l'ora anteriore alla creazione di quella testa
   * meno 2 minuti e' retrodatata: si scarta, e resta scartata nelle teste dopo. Un dispositivo mai letto prima puo' retrodatare (dichiarato).
   */
  @Test
  fun vociRetrodatateScartateEScartateRestano() = runBlocking<Unit> {
    val album = album(ShareLevel.ALL)
    val (a, b, c, d) = listOf(file(1), file(2), file(3), file(4))
    fun entry(m: FakeMedia, at: Long) = AlbumEntry(album, AlbumField.Item(m.id), at, bruno.deviceId, bruno.account, AlbumValue.Item(m.contentKey, SharePath.parse(m.path!!), 1, bruno.account))
    // L'album esiste da `created`; un'ora dopo bruno pubblica la sua prima testa con una voce di quasi un'ora prima: nessuno ha mai letto
    // una sua testa, quindi nessuno puo' dire che e' retrodatata (L59, dichiarato).
    val created = world.clocks.nas!!
    world.clocks.advance(3_600_000)
    val t1 = world.clocks.nas!!
    val old = created + 60_000
    RawHeads.write(world, bruno.signer, album, listOf(entry(a, old)), 1, createdAt = t1)
    world.settle()
    assertThat(alice.view(album)!!.merged.items.keys).containsExactly(a.id)
    val t2 = world.clocks.nas!!
    val retro = entry(b, t1 - 5 * 60_000)
    val tolerated = entry(c, t1 - 60_000)
    RawHeads.write(world, bruno.signer, album, listOf(entry(a, old), retro, tolerated), 2, createdAt = t2)
    world.settle()
    assertThat(alice.view(album)!!.merged.items.keys).containsExactly(a.id, c.id)
    assertThat(alice.replicaStore.contribution(album, bruno.deviceId)!!.entries.single { it.entry == retro }.retrodated).isTrue()
    // La stessa voce nella testa dopo resta scartata; la stessa voce con un'ora nuova no.
    val t3 = world.clocks.nas!!
    RawHeads.write(world, bruno.signer, album, listOf(entry(a, old), retro, tolerated, entry(d, t3)), 3, createdAt = t3)
    world.settle()
    assertThat(alice.view(album)!!.merged.items.keys).containsExactly(a.id, c.id, d.id)
    val t4 = world.clocks.nas!!
    RawHeads.write(world, bruno.signer, album, listOf(entry(a, old), entry(b, t4), tolerated, entry(d, t3)), 4, createdAt = t4)
    world.settle()
    assertThat(alice.view(album)!!.merged.items.keys).containsExactly(a.id, b.id, c.id, d.id)
  }
}
