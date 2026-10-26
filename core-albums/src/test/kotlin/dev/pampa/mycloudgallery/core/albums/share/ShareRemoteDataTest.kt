package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.AlbumDevice
import dev.pampa.mycloudgallery.core.albums.shared.AlbumWorld
import dev.pampa.mycloudgallery.core.albums.shared.RawWriter
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumCopy
import dev.pampa.mycloudgallery.core.albums.testing.FailingAlbumPorts
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * §4.3, C10, E-S11 (riga P4: "nessuna chiamata alle porte da un dato remoto: le porte finte falliscono se vengono chiamate"), Q5 con i file e
 * E-SH20, con la replica, la fusione e il pubblicatore veri di P2 ([AlbumWorld]): album veri e falsi scritti da altri (elementi che rimandano a
 * file privati, chiusure, eredita' non valide), voci di un dispositivo revocato. Niente di cio' che si legge dal NAS sposta, carica o chiede il
 * guardiano: l'orchestratore lavora solo sulle operazioni del suo diario, le proposte e le copie personali non toccano le porte.
 */
class ShareRemoteDataTest {

  private val base = 1_767_225_600_000L

  private fun file(n: Int, place: MediaPlace, mine: Boolean = true) =
    FakeMedia(FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, mine = mine, size = 2_000L + n)

  /** Le porte dell'orchestratore di [device] con le porte che spostano, caricano e chiedono il guardiano che falliscono se chiamate. */
  private fun ports(device: AlbumDevice, world: AlbumWorld, failing: FailingAlbumPorts) = ShareOrchestratorPorts(
    diary = InMemoryShareDiary(),
    facts = device.library,
    shared = device.shared,
    allowed = { a, op, m -> device.shared.allowed(a, op, m) },
    mover = failing,
    uploader = failing,
    tickets = failing,
    publisher = device.publisher,
    safeCopies = failing,
    origins = dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins(),
    family = device.albumFamily,
    gate = device.gate,
    clocks = world.clocks,
    folderContents = { folder -> device.library.all().filter { it.place == MediaPlace.COMMON && it.path?.substringBeforeLast('/') == folder.path.value }.map { it.id }.toSet() },
  )

  @Test
  fun albumVeriEFalsiLettiDalNasNonFannoPartireNiente() = runBlocking<Unit> {
    val world = AlbumWorld()
    val mine = file(1, MediaPlace.MY_PRIVATE)
    val phoneOnly = FakeMedia(FakeAlbumLibrary.id(2), base, place = MediaPlace.PHONE_ONLY, size = 999)
    val common = file(3, MediaPlace.COMMON)
    val me = world.device("test", library = FakeAlbumLibrary().apply { put(mine, phoneOnly, common) })
    val bruno = world.device("test2", library = FakeAlbumLibrary().apply { put(common.copy(mine = false)) })
    // Bruno crea un album e ci mette: un file comune (vero), il mio file privato (con il suo percorso privato) e un media solo del mio telefono.
    val album = bruno.createAlbum("Di Bruno", ShareLevel.ALL)
    bruno.publisher.stage(album, listOf(
      SharedAlbumChange.Item(common.id, null, common.contentKey, SharePath.parse(common.path!!), 1, bruno.account),
      SharedAlbumChange.Item(mine.id, null, mine.contentKey, SharePath.parse(mine.path!!), 2, bruno.account),
      SharedAlbumChange.Item(phoneOnly.id, null, phoneOnly.contentKey, SharePath.of("Album condivisi", "x.jpg"), 3, bruno.account),
    ))
    // Un'app difettosa di Bruno scrive anche una chiusura e un'eredita' per un album mio.
    val myAlbum = me.createAlbum("Mio", ShareLevel.ADD)
    world.settle()
    val raw = RawWriter(world, bruno.account) { me }
    val nonce = me.view(myAlbum)!!.merged.nonceHex
    raw.write(myAlbum, listOf(
      SharedAlbumChange.Definition("Mio", SharePath.of("Album condivisi", "Mio"), SharedState.DELETED, me.account, nonce, OthersAfterClose.KEEP_COPY),
      SharedAlbumChange.Heir(bruno.account, world.family.state.registry.version),
    ))
    world.settle()
    val failing = FailingAlbumPorts()
    val p = ports(me, world, failing)
    val orchestrator = ShareOrchestrator(p)
    repeat(3) {
      orchestrator.resumeAll(1.minutes)
      world.settle(1)
    }
    // Le proposte e le copie leggono solo: nessuna porta.
    FolderAdoptions(p) { ids -> me.library.visibleNow(ids) }.pending(album)
    FolderAdoptions(p) { ids -> me.library.visibleNow(ids) }.pending(myAlbum)
    BringBackCandidates(p).among(album, listOf(mine.id, common.id, phoneOnly.id))
    ClosedAlbumCopies({ me.shared.keptCopies() }, PersonalKeptCopyStore(InMemoryPersonalStore(me.deviceId, { world.clocks.nas }))).sync()
    assertThat(failing.calls).isEqualTo(0)
    // Il mio file privato e quello del telefono restano dove sono; dell'album di Bruno vedo solo cio' che il mio catalogo vede nell'area comune.
    assertThat(me.library[mine.id]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(me.library[phoneOnly.id]!!.place).isEqualTo(MediaPlace.PHONE_ONLY)
    assertThat(me.shared.visibleItems(album)).containsExactly(common.id)
    // La chiusura e l'eredita' scritte da Bruno su un album mio non valgono: l'album resta attivo e mio.
    assertThat(me.view(myAlbum)!!.merged.state).isEqualTo(SharedState.ACTIVE)
    assertThat(me.view(myAlbum)!!.merged.heir).isNull()
  }

  /** La stessa prova con il mondo finto di P0 (porte che falliscono): elementi privati, chiusura con copie, album di un altro. */
  @Test
  fun ilMondoFintoConVociDiMediaPrivatiNonChiamaLePorte() = runBlocking<Unit> {
    val w = ReferenceWorld()
    w.library.put(file(1, MediaPlace.MY_PRIVATE), file(2, MediaPlace.COMMON))
    val album = w.albumBy(AccountId("test2"), level = ShareLevel.ALL)
    val f = w.library[FakeAlbumLibrary.id(1)]!!
    val g = w.library[FakeAlbumLibrary.id(2)]!!
    w.shared.apply(album, AccountId("test2"), listOf(
      SharedAlbumChange.Item(f.id, null, f.contentKey, SharePath.parse(f.path!!), 1, AccountId("test2")),
      SharedAlbumChange.Item(g.id, null, g.contentKey, SharePath.parse(g.path!!), 2, AccountId("test2")),
    ))
    w.shared.apply(album, AccountId("test2"), listOf(
      SharedAlbumChange.Definition("Di famiglia", SharePath.of("Album condivisi", "Di famiglia"), SharedState.CLOSED, AccountId("test2"), w.shared.album(album)!!.nonceHex, OthersAfterClose.KEEP_COPY),
    ))
    val failing = FailingAlbumPorts()
    val base = w.ports().forOrchestrator()
    val p = ShareOrchestratorPorts(base.diary, base.facts, base.shared, base.allowed, failing, failing, failing, base.publisher, failing, base.origins, base.family, base.gate, base.clocks)
    repeat(3) { ShareOrchestrator(p).resumeAll(1.minutes) }
    val copies = w.sharedAlbums.keptCopies().map { SharedAlbumCopy(it.copy, it.shared, it.title, it.creator, it.mine, it.items) }
    val personal = InMemoryPersonalStore(dev.pampa.mycloudgallery.core.trust.codec.Base32.encode(ByteArray(16) { 0x21 }), { w.clocks.nas })
    ClosedAlbumCopies({ copies }, PersonalKeptCopyStore(personal)).sync()
    assertThat(failing.calls).isEqualTo(0)
    // La copia personale (Q5 b) ha solo cio' che il mio catalogo attesta nell'area comune: non il mio file privato.
    val kept = PersonalAlbums(personal).albums().single()
    assertThat(kept.title).isEqualTo("Di famiglia")
    assertThat(kept.items).containsExactly(FakeAlbumLibrary.id(2).value)
  }

  /** Q5: una copia eliminata dall'utente non si ricrea; un elemento tolto non si rimette; il creatore che aveva condiviso un suo album lo ritrova. */
  @Test
  fun q5LeCopieRispettanoLeSceltePersonali() = runBlocking<Unit> {
    var now = 1_790_000_000_000L
    val store = InMemoryPersonalStore(dev.pampa.mycloudgallery.core.trust.codec.Base32.encode(ByteArray(16) { 0x31 }), { ++now })
    val shared = AlbumId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    val copyId = AlbumId("aaaaaaaa-bbbb-4ccc-8ddd-000000000001")
    val a = FakeAlbumLibrary.id(1)
    val b = FakeAlbumLibrary.id(2)
    var copies = listOf(SharedAlbumCopy(copyId, shared, "Gita", AccountId("test2"), mine = false, items = listOf(a)))
    val sync = ClosedAlbumCopies({ copies }, PersonalKeptCopyStore(store))
    assertThat(sync.sync()).isEqualTo(2)
    assertThat(sync.sync()).isEqualTo(0)
    // Un elemento attestato dopo (la scansione e' arrivata dopo la chiusura) entra; uno che l'utente ha tolto no.
    PersonalAlbums(store).removeItem(copyId.value, a.value)
    copies = listOf(copies.single().copy(items = listOf(a, b)))
    assertThat(sync.sync()).isEqualTo(1)
    assertThat(PersonalAlbums(store).albums().single().items).containsExactly(b.value)
    // Copia eliminata: non torna.
    PersonalAlbums(store).delete(copyId.value)
    assertThat(sync.sync()).isEqualTo(0)
    assertThat(PersonalAlbums(store).albums()).isEmpty()
    // Il creatore dopo "Smetti": se l'album condiviso era nato da un suo album personale (voce `shared`), resta quello, nessuna copia.
    val personal = "11111111-2222-4333-8444-555555555555"
    store.set(dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(dev.pampa.mycloudgallery.core.personal.contract.PersonalKind.ALBUM, personal, dev.pampa.mycloudgallery.core.personal.contract.PersonalFields.ALBUM_SHARED), dev.pampa.mycloudgallery.core.personal.contract.PersonalValue.Text(shared.value))
    val mineCopy = AlbumId("aaaaaaaa-bbbb-4ccc-8ddd-000000000002")
    copies = listOf(SharedAlbumCopy(mineCopy, shared, "Gita", AccountId("test"), mine = true, items = listOf(a)))
    assertThat(sync.sync()).isEqualTo(0)
  }

  /**
   * E-SH20: il telefono dell'account `test` crea un album e ci aggiunge due foto; poi viene **revocato**: per tutti spariscono definizione ed
   * elementi (i file restano). Il tablet dello stesso account li ripubblica con un'azione locale: l'album torna, con gli elementi i cui file il
   * suo catalogo vede ancora nell'area comune. In un album di un altro (Bruno) le aggiunte del telefono tornano allo stesso modo.
   */
  @Test
  fun eSh20RipristinaGliElementiDiUnDispositivoRevocato() = runBlocking<Unit> {
    val world = AlbumWorld()
    val a = file(1, MediaPlace.COMMON)
    val b = file(2, MediaPlace.COMMON)
    val gone = file(3, MediaPlace.COMMON)
    fun catalog(vararg m: FakeMedia) = FakeAlbumLibrary().apply { put(*m) }
    val phone = world.device("test", library = catalog(a, b, gone))
    val tablet = world.device("test", library = catalog(a, b))
    val bruno = world.device("test2", library = catalog(a.copy(mine = false), b.copy(mine = false), gone.copy(mine = false)))
    val item = { m: FakeMedia, by: AlbumDevice -> SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account) }
    val mine = phone.createAlbum("Del telefono", ShareLevel.ADD)
    phone.publisher.stage(mine, listOf(item(a, phone), item(gone, phone)))
    val theirs = bruno.createAlbum("Di Bruno", ShareLevel.ADD)
    world.settle()
    phone.publisher.stage(theirs, listOf(item(b, phone)))
    world.settle()
    assertThat(bruno.shared.visibleItems(mine)).containsExactly(a.id, gone.id)
    assertThat(bruno.shared.visibleItems(theirs)).containsExactly(b.id)
    world.family.changeStatus(phone.signer, DeviceStatus.REVOKED)
    world.devices.remove(phone)
    world.settle()
    assertThat(bruno.view(mine)).isNull()
    assertThat(bruno.shared.visibleItems(theirs)).isEmpty()
    val restore = RevokedItemsRestore(tablet.identity, tablet.albumFamily, tablet.replicaStore, tablet.replica, tablet.publisher, tablet.gate, world.clocks, tablet.library) { id, op, m ->
      tablet.shared.allowed(id, op, m)
    }
    assertThat(restore.candidates()).containsExactly(mine, 1, theirs, 1)
    assertThat(restore.restore(mine)).isEqualTo(RestoreOutcome.Restored(1, definition = true))
    assertThat(restore.restore(theirs)).isEqualTo(RestoreOutcome.Restored(1, definition = false))
    world.settle()
    // L'album torna per tutti (stesso id, stessa cartella), con l'elemento il cui file il catalogo del tablet vede ancora; i file non si sono mossi.
    assertWithMessage("album del telefono").that(bruno.shared.visibleItems(mine)).containsExactly(a.id)
    assertThat(bruno.view(mine)!!.merged.creator).isEqualTo(AccountId("test"))
    assertThat(bruno.view(mine)!!.merged.levels.current).isEqualTo(ShareLevel.ADD)
    assertWithMessage("album di Bruno").that(bruno.shared.visibleItems(theirs)).containsExactly(b.id)
    assertThat(restore.restore(theirs)).isEqualTo(RestoreOutcome.Nothing)
    assertThat(tablet.library.all().map { it.place }.toSet()).containsExactly(MediaPlace.COMMON)
  }

  /** E-SH20 dietro i cancelli: con le scritture degli album bloccate non si ripubblica niente (D38). */
  @Test
  fun eSh20DietroICancelli() = runBlocking<Unit> {
    val world = AlbumWorld()
    val a = file(1, MediaPlace.COMMON)
    val phone = world.device("test", library = FakeAlbumLibrary().apply { put(a) })
    val tablet = world.device("test", library = FakeAlbumLibrary().apply { put(a) })
    val album = phone.createAlbum("Bloccato", ShareLevel.ADD)
    phone.publisher.stage(album, listOf(SharedAlbumChange.Item(a.id, null, a.contentKey, SharePath.parse(a.path!!), 1, phone.account)))
    world.settle()
    world.family.changeStatus(phone.signer, DeviceStatus.REVOKED)
    world.devices.remove(phone)
    world.settle()
    tablet.gate.blocks["test-comune"] = dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock.FROZEN
    val restore = RevokedItemsRestore(tablet.identity, tablet.albumFamily, tablet.replicaStore, tablet.replica, tablet.publisher, tablet.gate, world.clocks, tablet.library) { id, op, m ->
      tablet.shared.allowed(id, op, m)
    }
    assertThat(restore.restore(album)).isEqualTo(RestoreOutcome.Blocked(dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock.FROZEN))
    assertThat(tablet.publisher.pending.value).isEqualTo(0)
  }

  /**
   * D22 (Fase 7, P9): un mio elemento **nascosto** non e' fra quelli che il foglio di chiusura propone di riportare, e non si conta: il foglio non
   * fa trapelare che esiste. Nascondere vale solo per me (E-H4): per gli altri il foglio non cambia perche' non e' il loro.
   */
  @Test
  fun ilFoglioDiChiusuraNonProponeIMieNascosti() = runBlocking<Unit> {
    val world = AlbumWorld()
    val a = file(1, MediaPlace.COMMON)
    val b = file(2, MediaPlace.COMMON)
    val me = world.device("test", library = FakeAlbumLibrary().apply { put(a, b) })
    val album = me.createAlbum("Con un nascosto", ShareLevel.ADD)
    me.publisher.stage(album, listOf(
      SharedAlbumChange.Item(a.id, null, a.contentKey, SharePath.parse(a.path!!), 1, me.account),
      SharedAlbumChange.Item(b.id, null, b.contentKey, SharePath.parse(b.path!!), 2, me.account),
    ))
    world.settle()
    val before = (me.shared.closePreview(album, CloseMode.STOP) as dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome.Ready).preview
    assertThat(before.bringBackCandidates).containsExactly(a.id, b.id)
    me.library.hide(b.id)
    val after = (me.shared.closePreview(album, CloseMode.STOP) as dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome.Ready).preview
    assertThat(after.bringBackCandidates).containsExactly(a.id)
    me.library.hide(a.id)
    val none = (me.shared.closePreview(album, CloseMode.STOP) as dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome.Ready).preview
    assertThat(none.bringBackCandidates).isEmpty()
    assertThat(none.bringBackHurtsCopies).isFalse()
  }

  /**
   * D22 (Fase 7, P9), E-SH20: "Ripristina gli elementi di un dispositivo revocato" non conta ne' ripubblica i miei elementi **nascosti**: la riga
   * non dice un numero piu' alto di cio' che vedo e l'esito non lo rivela. Se si mostra di nuovo l'elemento, il ripristino lo ritrova.
   */
  @Test
  fun eSh20IlRipristinoNonContaIMieNascosti() = runBlocking<Unit> {
    val world = AlbumWorld()
    val a = file(1, MediaPlace.COMMON)
    val hidden = file(2, MediaPlace.COMMON)
    fun catalog() = FakeAlbumLibrary().apply { put(a, hidden) }
    val phone = world.device("test", library = catalog())
    val tablet = world.device("test", library = catalog())
    val album = phone.createAlbum("Con un nascosto", ShareLevel.ADD)
    phone.publisher.stage(album, listOf(
      SharedAlbumChange.Item(a.id, null, a.contentKey, SharePath.parse(a.path!!), 1, phone.account),
      SharedAlbumChange.Item(hidden.id, null, hidden.contentKey, SharePath.parse(hidden.path!!), 2, phone.account),
    ))
    world.settle()
    world.family.changeStatus(phone.signer, DeviceStatus.REVOKED)
    world.devices.remove(phone)
    world.settle()
    tablet.library.hide(hidden.id)
    val restore = RevokedItemsRestore(tablet.identity, tablet.albumFamily, tablet.replicaStore, tablet.replica, tablet.publisher, tablet.gate, world.clocks, tablet.library) { id, op, m ->
      tablet.shared.allowed(id, op, m)
    }
    assertThat(restore.candidates()).containsExactly(album, 1)
    assertThat(restore.restore(album)).isEqualTo(RestoreOutcome.Restored(1, definition = true))
    world.settle()
    assertThat(tablet.shared.visibleItems(album)).containsExactly(a.id)
    // Mostrato di nuovo, l'elemento torna fra quelli ripristinabili (la memoria del dispositivo revocato non si e' persa).
    tablet.library.unhide(hidden.id)
    assertThat(restore.candidates()).containsExactly(album, 1)
  }

  /** La chiusura dal foglio del creatore (Q5) non tocca file e il foglio restringe "Riporta" ai media con la cartella d'origine. */
  @Test
  fun ilFoglioDiChiusuraOffreRiportaSoloConLaCartellaDOrigine() = runBlocking<Unit> {
    val world = AlbumWorld()
    val a = file(1, MediaPlace.COMMON)
    val b = file(2, MediaPlace.COMMON)
    val me = world.device("test", library = FakeAlbumLibrary().apply { put(a, b) })
    world.device("test2", library = FakeAlbumLibrary().apply { put(a.copy(mine = false), b.copy(mine = false)) })
    val album = me.createAlbum("Chiusura", ShareLevel.ADD)
    me.publisher.stage(album, listOf(
      SharedAlbumChange.Item(a.id, null, a.contentKey, SharePath.parse(a.path!!), 1, me.account),
      SharedAlbumChange.Item(b.id, null, b.contentKey, SharePath.parse(b.path!!), 2, me.account),
    ))
    world.settle()
    val failing = FailingAlbumPorts()
    val p = ports(me, world, failing)
    p.origins.record(a.id, dev.pampa.mycloudgallery.core.albums.contract.ShareFolder("test", SharePath.of("Foto")))
    val preview = (me.shared.closePreview(album, CloseMode.STOP) as dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome.Ready).preview
    assertThat(preview.bringBackCandidates).containsExactly(a.id, b.id)
    val restricted = BringBackCandidates(p).restrict(preview)
    assertThat(restricted.bringBackCandidates).containsExactly(a.id)
    assertThat(me.shared.close(album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)).isEqualTo(CloseOutcome.Closed)
    repeat(2) { ShareOrchestrator(p).resumeAll(1.minutes) }
    assertThat(failing.calls).isEqualTo(0)
    assertThat(me.library.all().map { it.place }.toSet()).containsExactly(MediaPlace.COMMON)
  }
}
