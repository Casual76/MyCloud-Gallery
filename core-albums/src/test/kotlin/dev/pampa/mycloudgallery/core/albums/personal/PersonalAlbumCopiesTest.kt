package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le copie personali di Q5 (b) dal lato dei dati personali (D31 adeguata a Q5): stesso id per account su ogni dispositivo, nessun doppione,
 * e **una copia eliminata non si ricrea** (la lapide della definizione non si riscrive).
 */
@RunWith(AndroidJUnit4::class)
class PersonalAlbumCopiesTest {

  private var tick = T0
  private val clock: () -> Long = { tick += 10; tick }
  private val devices = ArrayList<FamilyDevice>()
  private val shared = AlbumId("00000000-0000-4000-8000-0000000000aa")
  private val copyId = SharedAlbumCopies.copyIdFor(shared, AccountId("alessio"))

  private fun device(name: String, base: Int): FamilyDevice = FamilyDevice(name, base, clock).also { devices += it }

  @After
  fun close() = devices.forEach { it.close() }

  private fun FamilyDevice.copies() = PersonalAlbumCopies(store, room.catalog)

  private fun FamilyDevice.ids(vararg n: Int) = n.map { idOf(it) }

  private suspend fun FamilyDevice.items() = room.db.albumDao().items(copyId.value).sortedBy { it.position }.map { it.fp }

  private fun fileFp(n: Int) = "${"%064x".format(n + 1)}:${5_000L + n}"

  @Test
  fun laCopiaNasceConLeStesseFotoNellOrdineDelCondivisoELaVoceCopyOf() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    phone.learnAll(0 until 5)

    val result = phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(3, 1, 4))

    assertThat(result).isEqualTo(PersonalAlbumCopies.Result.CREATED)
    val state = PersonalAlbums(phone.store).state(copyId.value, phone.store.state())!!
    assertThat(state.title).isEqualTo("Oslo 2026")
    assertThat(state.copyOf).isEqualTo(shared.value)
    assertThat(state.sharedId).isNull()
    phone.albums.reconcileAll()
    assertThat(phone.items()).containsExactly(fileFp(3), fileFp(1), fileFp(4)).inOrder()
    assertThat(phone.albums.all.value.byId(copyId)!!.count).isEqualTo(3)
  }

  @Test
  fun dueDispositiviDelloStessoAccountScrivonoLeStesseVociENonCeNeSonoDuePerLoStessoElemento() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 5)

    phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 2, 4))
    tablet.copies().keep(copyId, shared, "Oslo 2026", tablet.ids(0, 2, 4))
    exchange(phone, tablet)
    phone.albums.reconcileAll()
    tablet.albums.reconcileAll()

    for (d in listOf(phone, tablet)) {
      assertThat(d.items()).containsExactly(fileFp(0), fileFp(2), fileFp(4)).inOrder()
      assertThat(d.albums.all.value.albums.map { it.id }).containsExactly(copyId)
      assertThat(d.albums.all.value.byId(copyId)!!.count).isEqualTo(3)
    }
  }

  @Test
  fun unSecondoGiroNonScriveNienteEUnaChiusuraPiuGrandeAggiungeSoloIMancanti() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    phone.learnAll(0 until 6)
    phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))
    val before = phone.store.state().values.toSet()

    assertThat(phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))).isEqualTo(PersonalAlbumCopies.Result.UNCHANGED)
    assertThat(phone.store.state().values.toSet()).isEqualTo(before)

    assertThat(phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1, 5))).isEqualTo(PersonalAlbumCopies.Result.UPDATED)
    phone.albums.reconcileAll()
    assertThat(phone.items()).containsExactly(fileFp(0), fileFp(1), fileFp(5)).inOrder()
  }

  @Test
  fun unaCopiaEliminataNonSiRicreaMaiAncheSeLaChiusuraSiRilegge() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 4)
    phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))
    exchange(phone, tablet)

    // L'utente la elimina sul telefono; il tablet (che ha gia' la lapide) rilegge la chiusura.
    phone.albums.delete(copyId)
    exchange(phone, tablet)
    val state = tablet.store.state().values.toSet()

    assertThat(tablet.copies().keep(copyId, shared, "Oslo 2026", tablet.ids(0, 1, 2))).isEqualTo(PersonalAlbumCopies.Result.DELETED_BY_USER)
    assertThat(phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1, 2))).isEqualTo(PersonalAlbumCopies.Result.DELETED_BY_USER)
    assertThat(tablet.store.state().values.toSet()).isEqualTo(state)
    tablet.albums.reconcileAll()
    assertThat(tablet.albums.all.value.albums).isEmpty()
    val definition = PersonalAlbums(tablet.store).definition(copyId.value, tablet.store.state())!!
    assertThat(definition.value).isEqualTo(PersonalValue.Removed)
  }

  @Test
  fun unTitoloCambiatoEUnElementoTolto_NonTornanoARileggereLaChiusura() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    phone.learnAll(0 until 4)
    phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1, 2))
    phone.albums.rename(copyId, "Le mie foto di Oslo")
    phone.albums.remove(copyId, phone.ids(1))

    val result = phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1, 2))

    assertThat(result).isEqualTo(PersonalAlbumCopies.Result.UNCHANGED)
    phone.albums.reconcileAll()
    assertThat(phone.albums.all.value.byId(copyId)!!.title).isEqualTo("Le mie foto di Oslo")
    assertThat(phone.items()).containsExactly(fileFp(0), fileFp(2)).inOrder()
    // La copia e' personale: porta il legame con l'album chiuso, non con un album condiviso nato da lei.
    val state = PersonalAlbums(phone.store).state(copyId.value, phone.store.state())!!
    assertThat(state.copyOf).isEqualTo(shared.value)
    assertThat(phone.store.state().keys.none { it.field == PersonalFields.ALBUM_SHARED }).isTrue()
  }

  @Test
  fun unaCopiaMostraDaDoveVieneEChiHaChiusoQuandoLaReplicaLoSa() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    phone.learnAll(0 until 3)
    phone.room.db.sharedAlbumDao().upsert(
      SharedAlbumEntity(shared.value, "closed", "add", creator = "marco", afterClose = "keep_copy", updatedAtNas = 7_000, closedAtNas = 9_000),
    )
    phone.copies().keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))
    phone.albums.reconcileAll()

    val album = phone.albums.all.value.byId(copyId) as Album.Manual
    val former = album.formerShare!!
    assertThat(former.sharedId).isEqualTo(shared)
    assertThat(former.creator).isEqualTo(AccountId("marco"))
    assertThat(former.mine).isFalse()
    assertThat(former.closedAtNas).isEqualTo(9_000L)

    // Il creatore dopo "Smetti di condividere": il suo album e' nato personale e porta il legame `shared` con l'album chiuso.
    val own = (phone.albums.create("Mio", dev.pampa.mycloudgallery.core.model.AlbumKind.MANUAL, null) as dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome.Created).id
    PersonalAlbums(phone.store).setShared(own.value, "00000000-0000-4000-8000-0000000000bb")
    phone.room.db.sharedAlbumDao().upsert(SharedAlbumEntity("00000000-0000-4000-8000-0000000000bb", "closed", "add", creator = "alessio", updatedAtNas = 5_000))
    phone.albums.reconcileAll()
    val mine = (phone.albums.all.value.byId(own) as Album.Manual).formerShare!!
    assertThat(mine.mine).isTrue()
    assertThat(mine.closedAtNas).isEqualTo(5_000L)
    // Finche' l'album e' attivo non c'e' nessun "ex".
    phone.room.db.sharedAlbumDao().upsert(SharedAlbumEntity("00000000-0000-4000-8000-0000000000bb", "active", "add", creator = "alessio"))
    phone.albums.reconcileAll()
    assertThat((phone.albums.all.value.byId(own) as Album.Manual).formerShare).isNull()
  }

  /**
   * R1 (revisione di sicurezza della Fase 7): la copia nata da una chiusura firmata da un dispositivo poi revocato si ritira come "Elimina album"
   * (lapide della definizione) e non si ricrea; una copia di un altro album condiviso, o un album personale qualunque, non si tocca.
   */
  @Test
  fun r1LaCopiaSiRitiraSoloSeECopiaDiQuellAlbum() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    phone.learnAll(0 until 3)
    val copies = phone.copies()
    copies.keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))
    val other = AlbumId("00000000-0000-4000-8000-0000000000cc")
    assertThat(copies.withdraw(copyId, other)).isFalse()
    assertThat(PersonalAlbums(phone.store).definition(copyId.value)!!.value).isInstanceOf(PersonalValue.Text::class.java)

    assertThat(copies.withdraw(copyId, shared)).isTrue()
    assertThat(PersonalAlbums(phone.store).definition(copyId.value)!!.value).isEqualTo(PersonalValue.Removed)
    // Non si ricrea, e un secondo ritiro non fa niente.
    assertThat(copies.keep(copyId, shared, "Oslo 2026", phone.ids(0, 1))).isEqualTo(PersonalAlbumCopies.Result.DELETED_BY_USER)
    assertThat(copies.withdraw(copyId, shared)).isFalse()
    // Un album personale qualunque (senza `copy_of`) non e' una copia.
    val own = (phone.albums.create("Mio", dev.pampa.mycloudgallery.core.model.AlbumKind.MANUAL, null) as dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome.Created).id
    assertThat(copies.withdraw(own, shared)).isFalse()
  }
}
