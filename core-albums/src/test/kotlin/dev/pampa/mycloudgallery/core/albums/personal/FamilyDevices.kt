package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout

// I dispositivi di una famiglia per i test di piu' dispositivi dello stesso account: ognuno ha il SUO catalogo (Room in memoria, i suoi id
// per gli stessi file), il suo store dei dati personali e i suoi album veri. Lo scambio fra loro e' quello della sincronizzazione senza il
// NAS: lo stato unito di uno si applica all'altro (`applyRemote`, la stessa fusione dei dati personali). Il NAS, i segmenti e le teste li prova
// core-personal; qui conta cio' che gli album fanno dello stato che arriva.

/** La visibilita' dal catalogo vero (le stesse query di `VisibleMedia`/`HiddenMask` di P9), senza istantanea della timeline. */
class RoomVisibility(private val room: AlbumsRoom) : VisibleMedia, HiddenMask {
  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(null)

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = room.catalog.albums.visibleAmong(ids)

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = room.catalog.hiddenAmong(ids)
}

class FamilyDevice(val name: String, val idBase: Int, private val clock: () -> Long) : AutoCloseable {
  val room = AlbumsRoom()
  val store: LedgerPersonalStore = room.store(nasNow = clock)
  val visibility = RoomVisibility(room)
  val family: AlbumFamily = FakeAlbumFamily(account = AccountId("alessio"))
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val albums = CatalogAlbums(store, room.catalog, room.db, visibility, visibility, family, scope)

  /** L'id che QUESTO dispositivo ha dato al file [n]: un altro dispositivo gli da' un altro id (prima della convergenza, E-I18). */
  fun idOf(n: Int): MediaId = MediaId(UUID(0x6000L + idBase, n.toLong()).toString())

  /** Il file [n] nel catalogo di questo dispositivo, con l'impronta che ha su ogni dispositivo. */
  fun learn(n: Int) = room.insertRaw(idOf(n), 5_000L + n, "%064x".format(n + 1), T0 + n, 20_260_101 + n % 28)

  fun learnAll(range: IntRange) = range.forEach(::learn)

  override fun close() {
    scope.cancel()
    room.close()
  }
}

/** Lo scambio dei dati personali fra i dispositivi (la sincronizzazione senza NAS): ognuno applica lo stato unito degli altri. */
suspend fun exchange(vararg devices: FamilyDevice) {
  val states = devices.map { it.store.state().values.toList() }
  for ((i, device) in devices.withIndex()) {
    for ((j, entries) in states.withIndex()) if (i != j) device.store.applyRemote(entries)
  }
}

/** Aspetta (al piu' 10 secondi) che [condition] sia vera: per cio' che i dispositivi fanno da soli quando arriva lo stato. */
suspend fun eventually(condition: suspend () -> Boolean) {
  withTimeout(10_000) {
    while (!condition()) delay(20)
  }
}
