package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOverCapException
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.sync.FacesFormatCorrectionsTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncWorld
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Le prove delle correzioni della replica (R3-R8, I5) con ogni dispositivo sul suo database dei volti cifrato (schema v2). */
@RunWith(AndroidJUnit4::class)
class RoomFacesFormatCorrectionsTest : FacesFormatCorrectionsTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}

/**
 * R3 e R6 sull'archivio sul database (schema v2): la provenienza delle voci `people` (revoca per provenienza, si ripubblicano solo le voci arrivate dal
 * loro autore, l'autore riprende la sua voce), i tetti delle voci che arrivano dagli altri, il tetto duro dei volti del raggruppamento. Le stesse regole
 * le prova l'archivio in memoria.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveFormatCorrectionsTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 43 })
  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)

  private fun database(caps: StoreCaps): DatabaseFacesArchive = runBlocking {
    val db = FacesDatabases.forContext(ApplicationProvider.getApplicationContext()).open(File(folder.root, "faces-${generation.base32}.db"))
    db.openNow()
    val keys = FakeFacesKeys()
    keys.create(generation)
    DatabaseFacesArchive(generation, db, checkNotNull(keys.open(generation)), caps)
  }

  private fun hidden(n: Int, author: String, at: Long = 10) = PeopleRecord(PeopleEdit.Hidden(FacesSyncWorld.person(100 + n), true), at, author)

  private fun provenance(archive: dev.pampa.mycloudgallery.core.faces.contract.FacesArchive): Unit = runBlocking {
    val name = PeopleRecord(PeopleEdit.Name(a, "Offesa"), 20, "tablet")
    // Arriva dal compromesso a nome del tablet: vale, ma non si ripubblica; la revoca del compromesso la toglie.
    archive.addPeopleFrom("compromesso", listOf(name))
    assertThat(archive.people()[PeopleKey(a.base32(), "name")]).isEqualTo(name)
    assertThat(archive.directPeople()).isEmpty()
    archive.dropAuthors(setOf("compromesso"))
    assertThat(archive.people()).isEmpty()
    // La stessa voce che arriva prima da un intermediario e poi dal suo autore prende la provenienza dell'autore: la revoca dell'intermediario la lascia.
    val real = PeopleRecord(PeopleEdit.Name(b, "Bea"), 30, "tablet")
    archive.addPeopleFrom("portatile", listOf(real))
    archive.addPeopleFrom("tablet", listOf(real))
    assertThat(archive.directPeople().values).containsExactly(real)
    archive.dropAuthors(setOf("portatile"))
    assertThat(archive.people().values).containsExactly(real)
    // Una mia scelta non la sostituisce una copia a mio nome, piu' recente, portata da un altro; senza la mia riga (stato perso) la copia entra.
    val mine = PeopleRecord(PeopleEdit.Name(a, "Mia"), 40, "telefono")
    archive.addPeople(listOf(mine))
    archive.addPeopleFrom("compromesso", listOf(PeopleRecord(PeopleEdit.Name(a, "Falsa"), 50, "telefono")), own = "telefono")
    assertThat(archive.people()[PeopleKey(a.base32(), "name")]).isEqualTo(mine)
    val other = PeopleRecord(PeopleEdit.Hidden(b, true), 60, "telefono")
    archive.addPeopleFrom("tablet", listOf(other), own = "telefono")
    assertThat(archive.people()[PeopleKey(b.base32(), "hidden")]).isEqualTo(other)
  }

  private fun caps(archive: dev.pampa.mycloudgallery.core.faces.contract.FacesArchive): Unit = runBlocking {
    // Al piu' 3 chiavi nuove da uno stesso dispositivo, 5 in tutto; le scelte fatte qui non hanno tetti.
    assertThat(archive.addPeopleFrom("compromesso", (0 until 10).map { hidden(it, "tablet") })).isEqualTo(7)
    assertThat(archive.addPeopleFrom("tablet", (10 until 20).map { hidden(it, "tablet") })).isEqualTo(8)
    assertThat(archive.people()).hasSize(5)
    // Una chiave gia' presente si aggiorna anche oltre il tetto.
    assertThat(archive.addPeopleFrom("compromesso", listOf(hidden(0, "tablet", at = 99)))).isEqualTo(0)
    archive.addPeople((20 until 25).map { hidden(it, "telefono") })
    assertThat(archive.people()).hasSize(10)
  }

  @Test
  fun r3_provenienzaSulDatabase() {
    provenance(database(StoreCaps()))
  }

  @Test
  fun r3_provenienzaInMemoria() {
    provenance(InMemoryFacesArchive(generation))
  }

  @Test
  fun r3_tettiDelleVociSulDatabase() {
    caps(database(StoreCaps(maxPeople = 5, maxPeoplePerSigner = 3)))
  }

  @Test
  fun r3_tettiDelleVociInMemoria() {
    caps(InMemoryFacesArchive(generation, maxPeople = 5, maxPeoplePerSigner = 3))
  }

  @Test
  fun r6_oltreIlTettoDuroIVoltiDelRaggruppamentoNonSiCaricano() = runBlocking<Unit> {
    val archive = database(StoreCaps(maxGroupFaces = 2))
    archive.saveBody(FaceFixtures.body(FaceFixtures.key(1), FaceFixtures.AUTHOR_SMALL, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE), FaceFixtures.entry(1, FaceTier.CORE))))
    assertThat(archive.groupFaces()).hasSize(2)
    archive.saveBody(FaceFixtures.body(FaceFixtures.key(2), FaceFixtures.AUTHOR_SMALL, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE))))
    // La cache c'era (2 volti): il terzo la fa crescere oltre il tetto e la lettura si ferma; anche quella dopo, prima di leggere i corpi.
    // L343 (correzioni E): il tipo e' quello del tetto, cosi' il custode lo riconosce e lo conta (e' ancora una `IllegalStateException`).
    assertThrows(GroupingOverCapException::class.java) { runBlocking { archive.groupFaces() } }
    assertThat(archive.cachedVectors).isFalse()
    assertThrows(GroupingOverCapException::class.java) { runBlocking { archive.groupFaces() } }
  }
}
