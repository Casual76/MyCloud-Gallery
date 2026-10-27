package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySeenMarks
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.RoomSmartRows
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidate
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.smart.Spots
import dev.pampa.mycloudgallery.core.albums.smart.cand
import dev.pampa.mycloudgallery.core.albums.smart.day
import dev.pampa.mycloudgallery.core.albums.smart.ranges
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.upkeep.SmartUpkeep
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli album smart sul catalogo VERO (Room in memoria) e sul motore vero: l'elenco si rilegge dopo il calcolo (AS-1), uno smart nuovo non dice «nessuna foto»
 * finche' cerca (AS-2), il primo stato non e' piu' «Ready» ante litteram (AP-1), "Scongela" non porta via le foto aggiunte a mano da congelato (AS-10) e la
 * proiezione di uno smart arrivato da un altro dispositivo si aspetta invece di indovinarla con un'attesa (AS-9).
 */
@RunWith(AndroidJUnit4::class)
class SmartAlbumsLiveTest {

  private var tick = T0
  private val clock: () -> Long = { tick += 10; tick }
  private val devices = ArrayList<FamilyDevice>()

  private fun device(name: String, idBase: Int): FamilyDevice = FamilyDevice(name, idBase, clock).also { devices += it }

  @After
  fun close() = devices.forEach { it.close() }

  private val rules get() = SmartRules(ranges(day(2026, 1, 1) to day(2026, 1, 28)), null, null)

  /** Il motore vero sulle righe di Room del dispositivo, con i candidati che il test dice (i media sono quelli del dispositivo). */
  private class Stack(val device: FamilyDevice, count: Int) {
    val world = MemoryWorld()
    val rows = RoomSmartRows(device.room.db)
    val clocks = FakeAlbumClocks()
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, clocks, InMemorySeenMarks())
    val recompute = SmartRecompute(rows, world, engine, clocks)
    val upkeep = SmartUpkeep(device.scope, rows, recompute, device.albums)

    init {
      for (n in 0 until count) {
        val c = cand(n + 1, day(2026, 1, 1).plusDays((n % 20).toLong()), 600 + n)
        world.put(SmartCandidate(c.c.copy(media = device.idOf(n)), c.ownedByMe))
      }
    }
  }

  private suspend fun FamilyDevice.createSmart(title: String = "Gennaio"): AlbumId {
    val outcome = albums.create(title, AlbumKind.SMART, rules)
    return (outcome as CreateOutcome.Created).id
  }

  /** Il tetto di ogni attesa su un evento (un flusso che cambia): largo, perche' non misura la velocita' ma trasforma un blocco in un fallimento. */
  private val cap = 2.minutes

  private suspend fun <T> StateFlow<T>.until(predicate: (T) -> Boolean): T = withTimeout(cap) { first(predicate) }

  // --- AP-1, AS-2, AS-1 ----------------------------------------------------------------------------------------------------------

  @Test
  fun lElencoNonRestaInCaricamentoPerSempre() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.albums.status.until { it !is AlbumsStatus.Loading }
    assertThat(device.albums.status.value).isEqualTo(AlbumsStatus.Ready)
  }

  @Test
  fun unoSmartNuovoDiceCheCercaEDopoIlCalcoloElencoEPaginaSiRileggono() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.learnAll(0 until 6)
    val stack = Stack(device, 6)
    val id = device.createSmart()

    // Appena nato: nessuna riga e il motore non ha ancora lavorato. La pagina non deve dire «nessuna foto corrisponde alle regole».
    assertThat(device.albums.status.until { it is AlbumsStatus.Recomputing }).isInstanceOf(AlbumsStatus.Recomputing::class.java)
    assertThat(device.albums.all.value.byId(id)!!.count).isEqualTo(0)

    stack.upkeep.rulesChanged(id)

    // Prima il motore scriveva su Room e nessuno rinfrescava elenco e pagina: restavano a 0 finche' non si rientrava.
    assertThat(device.albums.all.until { it.byId(id)?.count == 6 }.byId(id)!!.count).isEqualTo(6)
    assertThat(device.albums.status.until { it == AlbumsStatus.Ready }).isEqualTo(AlbumsStatus.Ready)
  }

  @Test
  fun unaProiezioneFattaAltroveSiAspettaConReconcileNow() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 6)
    val id = phone.createSmart()
    exchange(phone, tablet)

    // AS-9: il motore del tablet legge `album_rule`: chi lo avvia chiede alla proiezione di finire, non aspetta «un po'» (con molti album non bastava).
    tablet.albums.reconcileNow()

    assertThat(tablet.room.db.albumDao().get(id.value)).isNotNull()
    assertThat(tablet.room.db.albumRuleDao().rules(id.value)).isNotEmpty()
  }

  // --- AS-10 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun scongelaNonTogliLeFotoAggiunteAMano() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.learnAll(0 until 8)
    val id = device.createSmart("Da congelare")
    // Tre righe del motore, poi «Congela»: diventano elementi a mano.
    device.room.db.albumDao().upsertItems((0 until 3).map { AlbumItemEntity(id.value, device.idOf(it).value, (it + 1) * 1_000L, T0, origin = "auto") })
    device.albums.freeze(id)
    assertThat(device.room.db.albumDao().items(id.value).map { it.mediaId }.toSet()).isEqualTo((0 until 3).map { device.idOf(it).value }.toSet())

    // Da congelato l'utente aggiunge due foto a mano.
    device.albums.add(id, listOf(device.idOf(5), device.idOf(6)))
    assertThat(device.room.db.albumDao().items(id.value)).hasSize(5)

    assertThat(device.albums.unfreeze(id)).isEqualTo(EditOutcome.Done)

    // Le tre del congelamento si tolgono (D16); le due aggiunte a mano restano (prima sparivano con le altre).
    val after = device.room.db.albumDao().items(id.value)
    assertThat(after.map { it.mediaId }.toSet()).isEqualTo(setOf(device.idOf(5).value, device.idOf(6).value))
    assertThat(after.map { it.origin }.toSet()).containsExactly("accepted")
  }
}
