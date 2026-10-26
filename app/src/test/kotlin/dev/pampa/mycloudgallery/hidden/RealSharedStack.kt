package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.albums.AlbumCatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.share.KeptCopyState
import dev.pampa.mycloudgallery.core.albums.share.KeptCopyStore
import dev.pampa.mycloudgallery.core.albums.share.ShareOrchestratorPorts
import dev.pampa.mycloudgallery.core.albums.shared.AlbumIdentity
import dev.pampa.mycloudgallery.core.albums.shared.MemoryAlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.MemorySharedAlbumChoices
import dev.pampa.mycloudgallery.core.albums.shared.ReplicaSharedAlbums
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumReplica
import dev.pampa.mycloudgallery.core.albums.shared.StoredContribution
import dev.pampa.mycloudgallery.core.albums.shared.StoredEntry
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins
import dev.pampa.mycloudgallery.core.albums.testing.FailingAlbumPorts
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.database.AlbumCatalog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 7 (P9, D22; integrazione 26/10): lo STACK VERO degli album condivisi (P2: replica, fusione, attestazione, `ReplicaSharedAlbums`) sopra il
// catalogo VERO del mondo casuale. La famiglia e' vera ([RealFamily]: registro firmato, dispositivi, un telefono revocato); le voci degli album sono
// le `StoredContribution` che la replica terrebbe dopo un giro (la lettura del NAS e le firme delle teste sono provate in `core-albums`: qui conta
// cio' che le schermate mostrano di un album che il catalogo di chi legge attesta). L'attestazione e i fatti sono le **letture vere di P1**
// (`AlbumCatalog`), con lo stesso adattatore senza logica che scrivera' `:app` (P13); la visibilita' e' `CatalogVisibleMedia` (D22).

/** Chi non ha nascosto niente (un altro familiare, E-H5: "per gli altri si'"). */
internal object AllVisible : VisibleMedia {
  override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(null)

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
}

/** Le copie personali in memoria (le scrivera' P3 sui dati personali): per provare `ClosedAlbumCopies` senza i dati personali veri. */
internal class MemoryKeptCopyStore : KeptCopyStore {
  val defined = LinkedHashMap<AlbumId, AlbumId>()
  val items = LinkedHashMap<AlbumId, MutableList<MediaId>>()

  override suspend fun copyState(copy: AlbumId): KeptCopyState = if (copy in defined) KeptCopyState.LIVE else KeptCopyState.NONE

  override suspend fun defineCopy(copy: AlbumId, title: String, copyOf: AlbumId) {
    defined[copy] = copyOf
  }

  override suspend fun itemKnown(copy: AlbumId, media: MediaId): Boolean = items[copy]?.contains(media) == true

  override suspend fun addItem(copy: AlbumId, media: MediaId, position: Long) {
    items.getOrPut(copy) { ArrayList() } += media
  }

  override suspend fun personalSharedAs(shared: AlbumId): AlbumId? = null
}

/** Un album seminato: il suo id e gli indici (del mondo) dei media che contiene. */
internal class SeededAlbum(val id: AlbumId, val members: List<Int>)

internal class RealSharedStack private constructor(val w: BuiltWorld, val family: RealFamily) {
  val clocks = FakeAlbumClocks()
  val albumFamily = FakeAlbumFamily(account = AccountId.of("test"))
  val gate = FakeAlbumWriteGate()
  // P13: l'adattatore senza logica sta ora nel codice di :app (`AlbumCatalogFacts`), lo stesso che lega l'app.
  val facts: CatalogFacts = AlbumCatalogFacts { w.catalog.albums }
  val visibility: VisibleMedia = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
  val store = MemoryAlbumReplicaStore()
  val choices = MemorySharedAlbumChoices()
  val publisher = FakeAlbumPublisher(clocks, albumFamily, gate)
  val origins = FakeSharedOrigins()

  val identity: AlbumIdentity = identityOf(family.me.id, family.me.signer) { family.state }
  val replica = SharedAlbumReplica(identity, store, facts)
  val shared = ReplicaSharedAlbums(identity, albumFamily, replica, publisher, gate, clocks, visibility, choices)

  /** I media dell'area comune, per indice del mondo (quelli che un album condiviso puo' citare). */
  val common: List<Int> = w.spec.media.filter { it.where == Where.NAS_COMMON }.map { it.index }

  /** "Di famiglia": l'album di un altro familiare (`test2`), con la copertina scelta da lui: E-H5, E-H6. */
  lateinit var familyAlbum: SeededAlbum

  /** "Mio": l'album di cui sono il creatore, con elementi miei e di `test2`: foglio di chiusura, "Riporta", "Da condividere" (file nuovi nella cartella). */
  lateinit var mine: SeededAlbum

  /** "Chiuso": un album di `test2` chiuso con "ognuno tiene una copia" (Q5). */
  lateinit var closed: SeededAlbum

  /** "Lasciato": un album di `test2` che ho lasciato (E-SH21). */
  lateinit var left: SeededAlbum

  /** Le porte della condivisione (P4) sopra lo stesso stack: tutto cio' che sposta o carica **fallisce se chiamato** (le proposte non fanno partire niente). */
  suspend fun ports(): ShareOrchestratorPorts {
    for (index in common) origins.record(w.ids[index], ShareFolder("test", SharePath.of("Foto", "2026")))
    val failing = FailingAlbumPorts()
    val mineFolder = SharePath.of("Album condivisi", "Mio")
    return ShareOrchestratorPorts(
      diary = InMemoryShareDiary(), facts = facts, shared = shared, allowed = { album, op, media -> shared.allowed(album, op, media) },
      mover = failing, uploader = failing, tickets = failing, publisher = publisher, safeCopies = failing, origins = origins, family = albumFamily, gate = gate, clocks = clocks,
      // Il catalogo di chi legge vede nella cartella di "Mio" tutti i file dell'area comune (anche i nascosti: li filtra la proposta).
      folderContents = { folder -> if (folder.path == mineFolder) common.mapTo(LinkedHashSet()) { w.ids[it] } else null },
    )
  }

  /** Lo stesso insieme di album visto da `test2`, che non ha nascosto niente (E-H5: "per gli altri si'"). */
  suspend fun viewOfAnother(): ReplicaSharedAlbums {
    val device = family.other
    val others = FakeAlbumFamily(account = AccountId.of("test2"))
    val identity = identityOf(device.id, device.signer) { family.state }
    val replica = SharedAlbumReplica(identity, store, facts)
    val view = ReplicaSharedAlbums(identity, others, replica, FakeAlbumPublisher(clocks, others, gate), gate, clocks, AllVisible, MemorySharedAlbumChoices())
    replica.recompute()
    return view
  }

  /** Gli elementi di [mine] che ho aggiunto io (gli altri li ha aggiunti `test2`). */
  val mineAddedByMe: List<Int> get() = mine.members.filter { common.indexOf(it) % 2 == 0 }

  /** I media nella cartella di [mine] che nessuno ha ancora aggiunto all'album (D39: "file nuovi nella cartella"). */
  val notYetInMine: List<Int> get() = common.filter { it !in mine.members }

  private fun pub(album: String, index: Int): MediaId = MediaId(UUID.nameUUIDFromBytes("pub-${w.spec.seed}-$album-$index".toByteArray()).toString())

  private fun nonce(code: Int): ByteArray = ByteArray(16) { (it * 7 + code * 31 + w.spec.seed.toInt()).toByte() }

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun entry(album: AlbumId, field: AlbumField, at: Long, by: RealDevice, value: AlbumValue?) =
    AlbumEntry(album, field, at, by.id, AccountId.of(by.account), value)

  private fun contribution(album: AlbumId, by: RealDevice, entries: List<AlbumEntry>, barred: Boolean = false) = StoredContribution(
    album = album, device = by.id, account = AccountId.of(by.account), mark = HeadMark(1, "h-${album.value}-${by.id}"), createdAt = T0 + 5_000_000L, segments = emptyList(),
    entries = entries.map { StoredEntry(it, "seg", retrodated = false) }, barred = barred,
  )

  /** Semina un album: la sua definizione e il livello (di [creator]) e gli elementi [members] con chi li ha aggiunti ([adder]). */
  private suspend fun seed(
    code: Int,
    title: String,
    creator: RealDevice,
    members: List<Int>,
    adder: (Int) -> RealDevice,
    state: AlbumDefinitionState = AlbumDefinitionState.ACTIVE,
    afterClose: String? = null,
    chosenCover: Int? = null,
    barredFor: RealDevice? = null,
  ): SeededAlbum {
    val creatorAccount = AccountId.of(creator.account)
    val nonce = nonce(code)
    val album = AlbumId.derived(creatorAccount, nonce)
    val folder = SharePath.of("Album condivisi", title)
    val mediaFacts = facts.facts(members.map { w.ids[it] })
    val perDevice = LinkedHashMap<RealDevice, MutableList<AlbumEntry>>()
    fun add(device: RealDevice, entry: AlbumEntry) {
      perDevice.getOrPut(device) { ArrayList() } += entry
    }
    // La definizione di un album chiuso e' l'ultima voce (la chiusura viene dopo gli elementi aggiunti quando era attivo).
    val definitionAt = if (state == AlbumDefinitionState.ACTIVE) T0 else T0 + 50_000
    add(creator, entry(album, AlbumField.Definition, definitionAt, creator, AlbumValue.Definition(title, folder, state, creatorAccount, hex(nonce), afterClose)))
    add(creator, entry(album, AlbumField.Level, T0 + 1_000, creator, AlbumValue.Levels(listOf(LevelChange(T0 + 1_000, ShareLevel.ADD)))))
    members.forEachIndexed { n, index ->
      val by = adder(index)
      val key = checkNotNull(mediaFacts[w.ids[index]]?.contentKey) { "il media $index non ha una chiave di contenuto" }
      val path = SharePath.parseMediaPath(checkNotNull(w.nas[index]) { "il media $index non sta sul NAS" }.path)
      add(by, entry(album, AlbumField.Item(pub(title, index)), T0 + 10_000 + n * 10L, by, AlbumValue.Item(key, path, (n + 1) * 1_000L, AccountId.of(by.account))))
    }
    if (chosenCover != null) {
      val key = checkNotNull(mediaFacts[w.ids[chosenCover]]?.contentKey)
      add(creator, entry(album, AlbumField.Cover, T0 + 20_000, creator, AlbumValue.Cover(pub(title, chosenCover), key)))
    }
    for ((device, entries) in perDevice) store.put(contribution(album, device, entries, barred = barredFor != null && device == barredFor))
    return SeededAlbum(album, members)
  }

  private suspend fun populate() {
    val me = family.me
    val other = family.other
    // "Riporta" vale per i miei media nell'area comune (arrivati dalla mia area privata o dal telefono): ne faccio miei quelli che ho aggiunto io a "Mio".
    for (index in common.filterIndexed { i, _ -> i % 3 != 2 && i % 2 == 0 }) w.db.mediaDao().setOwner(w.ids[index].value, "test", T0)
    familyAlbum = seed(1, "Di famiglia", other, common, { other }, chosenCover = common.firstOrNull())
    mine = seed(2, "Mio", me, common.filterIndexed { i, _ -> i % 3 != 2 }, { index -> if (common.indexOf(index) % 2 == 0) me else other })
    closed = seed(3, "Chiuso", other, common.filterIndexed { i, _ -> i % 2 == 0 }, { other }, state = AlbumDefinitionState.CLOSED, afterClose = "keep_copy")
    left = seed(4, "Lasciato", other, common.take(3), { other })
    // Il mio vecchio telefono (revocato) aveva aggiunto a "Mio" gli elementi che adesso non ci sono: E-SH20, "Ripristina gli elementi di un dispositivo revocato".
    val old = family.myOldPhone
    val missing = common.filter { it !in mine.members }
    if (missing.isNotEmpty()) {
      val oldEntries = missing.mapIndexed { n, index ->
        val key = checkNotNull(facts.facts(listOf(w.ids[index]))[w.ids[index]]?.contentKey)
        val path = SharePath.parseMediaPath(checkNotNull(w.nas[index]).path)
        entry(mine.id, AlbumField.Item(pub("Mio", index)), T0 + 30_000 + n * 10L, old, AlbumValue.Item(key, path, (100 + n) * 1_000L, AccountId.of(old.account)))
      }
      store.put(contribution(mine.id, old, oldEntries, barred = true))
    }
    choices.setLeft(left.id, true)
    replica.recompute()
  }

  companion object {
    const val T0: Long = 1_790_000_000_000L

    private fun identityOf(device: String, key: Signer, state: () -> RegistryState): AlbumIdentity = object : AlbumIdentity {
      override val deviceId: String = device
      override val signer: Signer = key

      override fun registry(): RegistryState = state()
    }

    /** Lo stack sopra il mondo [w], con gli album seminati e la replica gia' ricalcolata. */
    suspend fun build(w: BuiltWorld): RealSharedStack = RealSharedStack(w, RealFamily.shared).also { it.populate() }
  }
}
