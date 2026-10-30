package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, D15, D21, D23, D24, §2.4): lo stato che il database tiene oltre ai corpi: l'istantanea del raggruppamento (letta e scritta
 * intera, con ogni sua forma), le voci `people` (ogni tipo di modifica va e torna uguale, una voce piu' vecchia non vince, due voci da datare
 * si sostituiscono), "non supportato", e i tre magazzini del lavoro di sincronizzazione (segni delle teste, outbox, unita' fatte).
 */
@RunWith(AndroidJUnit4::class)
class FacesStoreStateTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 6 })
  private val small = FaceFixtures.AUTHOR_SMALL
  private val big = FaceFixtures.AUTHOR_BIG

  private fun person(n: Int) = PersonId.fromBytes(ByteArray(16) { (n * 16 + it).toByte() })

  private fun locator(n: Int, frame: Int = 0) = FaceLocator(FaceFixtures.key(n), frame, FaceBox(100 * n, 2_000, 100 * n + 500, 6_000))

  // --- l'istantanea del raggruppamento -----------------------------------------------------------------------------------------

  @Test
  fun lIstantaneaVaETornaUgualeInOgniSuaForma() = runBlocking<Unit> {
    val store = world.ready(generation)
    assertThat(store.archive.grouping().persons).isEmpty()
    val a = person(1)
    val b = person(2)
    val c = person(3)
    val refs = (1..6).map { FaceRef(FaceFixtures.key(it), if (it == 6) 3 else 0, it % 3, 1_000L + it) }
    val state = GroupingState(
      seq = 12,
      base = 5,
      persons = linkedMapOf(
        a to GroupPerson(a, PersonState.ACTIVE, null, 1, 12, listOf(refs[0], refs[1], refs[2])),
        b to GroupPerson(b, PersonState.MERGED, a, 2, 9, emptyList()),
        c to GroupPerson(c, PersonState.FORGOTTEN, null, 3, 10, listOf(refs[5])),
      ),
      assignments = refs.mapIndexed { i, ref ->
        // L'ultima assegnazione ha un posto di un altro contenuto e fotogramma (il caso raro, che deve restare esatto).
        val where = if (i == 5) FaceLocator(FaceFixtures.key(40), 7, FaceBox(10, 20, 30, 40)) else locator(i + 1, ref.frame)
        ref to GroupAssignment(ref, where, listOf(a, a, a, c, c, c)[i], 0.25f + i * 0.0875f, AssignOrigin.entries[i % 3])
      }.toMap(),
      unassignedAtBirths = 77,
    )
    store.archive.saveGrouping(state)
    val back = store.archive.grouping()
    assertThat(back.seq).isEqualTo(12)
    assertThat(back.base).isEqualTo(5)
    assertThat(back.unassignedAtBirths).isEqualTo(77)
    assertThat(back.persons.keys).containsExactlyElementsIn(state.persons.keys)
    for ((id, person) in state.persons) assertThat(back.persons.getValue(id)).isEqualTo(person)
    assertThat(back.assignments.keys).containsExactlyElementsIn(state.assignments.keys)
    for ((ref, assignment) in state.assignments) {
      val read = back.assignments.getValue(ref)
      assertThat(read).isEqualTo(assignment) // anche punteggio (float esatto), origine e posto
    }
    assertThat(back.resolve(b)).isEqualTo(a)
    assertThat(back.resolve(c)).isNull()

    // Sostituisce tutto, non aggiunge: una seconda istantanea piu' piccola lascia solo se stessa; una vuota svuota.
    val smaller = GroupingState(13, 12, mapOf(a to state.persons.getValue(a)), emptyMap())
    store.archive.saveGrouping(smaller)
    assertThat(store.archive.grouping().persons.keys).containsExactly(a)
    assertThat(store.archive.grouping().assignments).isEmpty()
    store.archive.saveGrouping(GroupingState.EMPTY)
    assertThat(store.archive.grouping().persons).isEmpty()
    assertThat(store.archive.grouping().seq).isEqualTo(0)
    store.close()
  }

  @Test
  fun lIstantaneaSopravviveAUnaChiusuraELaVecchiaNonSiVedeAMeta() = runBlocking<Unit> {
    val store = world.ready(generation)
    val a = person(1)
    val ref = FaceRef(FaceFixtures.key(1), 0, 0, 5)
    val state = GroupingState(
      3, 1, mapOf(a to GroupPerson(a, PersonState.ACTIVE, null, 1, 3, listOf(ref))),
      mapOf(ref to GroupAssignment(ref, locator(1), a, 0.5f, AssignOrigin.AUTO)),
    )
    store.archive.saveGrouping(state)
    store.close()
    val again = world.ready(generation)
    assertThat(again.archive.grouping().assignments.keys).containsExactly(ref)
    // Un codice sconosciuto (scritto da un'app piu' nuova) si salta con la sua persona e le sue assegnazioni; il resto resta.
    val b = person(2)
    val other = FaceRef(FaceFixtures.key(2), 0, 0, 6)
    again.archive.saveGrouping(
      GroupingState(
        4, 1,
        mapOf(a to state.persons.getValue(a), b to GroupPerson(b, PersonState.ACTIVE, null, 2, 4, emptyList())),
        mapOf(ref to state.assignments.getValue(ref), other to GroupAssignment(other, locator(2), b, 0.6f, AssignOrigin.USER)),
      ),
    )
    again.database.openHelper.writableDatabase.execSQL("UPDATE group_person SET state = 'dormiente' WHERE state = 'active' AND born_seq = 2")
    val read = again.archive.grouping()
    assertThat(read.persons.keys).containsExactly(a)
    assertThat(read.assignments.keys).containsExactly(ref)
    again.close()
  }

  // --- le voci people ----------------------------------------------------------------------------------------------------------

  private fun allEdits(): List<PeopleEdit> {
    val p = person(1)
    val q = person(2)
    return listOf(
      PeopleEdit.Name(p, "Nome-Di-Prova-Uno"),
      PeopleEdit.Name(q, null),
      PeopleEdit.Hidden(p, true),
      PeopleEdit.Hidden(q, false),
      PeopleEdit.Forget(q),
      PeopleEdit.Alias(q, p),
      PeopleEdit.Apart(p, q, true),
      PeopleEdit.Apart(q, p, false),
      PeopleEdit.Born(q, p),
      PeopleEdit.Cover(p, locator(1)),
      PeopleEdit.Cover(q, null),
      PeopleEdit.Is(locator(2), p),
      PeopleEdit.Is(locator(3, frame = 2), null),
      PeopleEdit.Not(locator(4), q, true),
      PeopleEdit.Not(locator(5), p, false),
      PeopleEdit.Ignore(locator(6), true),
      PeopleEdit.Ignore(locator(7), false),
    )
  }

  @Test
  fun ogniTipoDiModificaVaETornaUgualeEIlNomeNonStaInChiaro() = runBlocking<Unit> {
    val store = world.ready(generation)
    val records = allEdits().mapIndexed { i, edit -> PeopleRecord(edit, atNas = 1_000L + i, author = if (i % 2 == 0) small else big) }
    store.archive.addPeople(records)
    val back = store.archive.people()
    assertThat(back.values.toSet()).isEqualTo(records.toSet())
    // Il nome sta sigillato; le altre colonne sono quelle del formato.
    val sql = store.database.openHelper.writableDatabase
    sql.query("SELECT value, sealed_value FROM people_record WHERE field = 'name' AND sealed_value IS NOT NULL").use { c ->
      assertThat(c.count).isEqualTo(1)
      c.moveToFirst()
      assertThat(c.isNull(0)).isTrue()
      assertThat(String(c.getBlob(1), Charsets.ISO_8859_1)).doesNotContain("Nome-Di-Prova")
    }
    sql.query("SELECT COUNT(*) FROM people_record WHERE sealed_value IS NULL").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(records.size - 1) }
    // Dopo la chiusura le trova ancora.
    store.close()
    assertThat(world.ready(generation).archive.people().values.toSet()).isEqualTo(records.toSet())
  }

  @Test
  fun lUltimaScritturaVinceEDueVociDaDatareSiSostituiscono() = runBlocking<Unit> {
    val store = world.ready(generation)
    val p = person(1)
    val key = PeopleEdit.Name(p, "x").let { dev.pampa.mycloudgallery.core.faces.contract.PeopleKey(it.subject, it.field) }
    fun name(text: String?, at: Long?, author: String) = PeopleRecord(PeopleEdit.Name(p, text), at, author)
    suspend fun current() = store.archive.people().getValue(key)

    store.archive.addPeople(listOf(name("Anna-Di-Prova", 100, big)))
    store.archive.addPeople(listOf(name("Bea-Di-Prova", 90, small))) // piu' vecchia: non vince
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, "Anna-Di-Prova"))
    store.archive.addPeople(listOf(name("Carla-Di-Prova", 100, small))) // a pari ora vince l'autore minore
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, "Carla-Di-Prova"))
    // Due voci da datare (entrambe locali): la nuova sostituisce la vecchia, anche se il valore e' "minore" (Bea poi Anna, come P9).
    store.archive.addPeople(listOf(name("Zeta-Di-Prova", null, small)))
    store.archive.addPeople(listOf(name("Alfa-Di-Prova", null, small)))
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, "Alfa-Di-Prova"))
    // Quando il NAS la data, la voce datata piu' alta vince; una piu' bassa no.
    store.archive.addPeople(listOf(name("Alfa-Di-Prova", 500, small)))
    assertThat(current().atNas).isEqualTo(500)
    store.archive.addPeople(listOf(name("Vecchia-Di-Prova", 400, small)))
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, "Alfa-Di-Prova"))
    // La lapide del nome (nome tolto) e' una voce come le altre, e non lascia nessun testo.
    store.archive.addPeople(listOf(name(null, 600, small)))
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, null))
    store.database.openHelper.writableDatabase.query("SELECT value, sealed_value FROM people_record WHERE field = 'name'").use { c ->
      c.moveToFirst()
      assertThat(c.isNull(0) && c.isNull(1)).isTrue()
    }
    // Due voci della stessa chiave nella stessa chiamata: l'ultima delle due, per le stesse regole.
    store.archive.addPeople(listOf(name("Prima-Di-Prova", 700, small), name("Seconda-Di-Prova", 800, small)))
    assertThat(current().edit).isEqualTo(PeopleEdit.Name(p, "Seconda-Di-Prova"))
    store.close()
  }

  @Test
  fun leRigheCheNonSiCapisconoSiSaltanoENonRompono() = runBlocking<Unit> {
    val store = world.ready(generation)
    store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Hidden(person(1), true), 10, small)))
    val sql = store.database.openHelper.writableDatabase
    sql.execSQL("INSERT INTO people_record VALUES ('non-un-id', 'hidden', 'true', NULL, 1, 'x', 'x')")
    sql.execSQL("INSERT INTO people_record VALUES ('${person(2).base32()}', 'campo-nuovo', 'true', NULL, 1, 'x', 'x')")
    sql.execSQL("INSERT INTO people_record VALUES ('${person(2).base32()}', 'hidden', 'forse', NULL, 1, 'x', 'x')")
    sql.execSQL("INSERT INTO people_record VALUES ('${person(2).base32()}', 'apart:${person(2).base32()}', 'true', NULL, 1, 'x', 'x')")
    assertThat(store.archive.people().values.map { it.edit }).containsExactly(PeopleEdit.Hidden(person(1), true))
    store.close()
  }

  // --- non supportato e corpi di piu' dispositivi --------------------------------------------------------------------------------

  @Test
  fun nonSupportatoNonSiAbbassaEUnaRicettaNuovaLoRifa() = runBlocking<Unit> {
    val store = world.ready(generation)
    val k = FaceFixtures.key(1)
    store.archive.markUnsupported(k, 3)
    store.archive.markUnsupported(k, 2) // una ricetta piu' vecchia non abbassa quella che c'e'
    assertThat(store.archive.analyzed(listOf(k), 3)).containsExactly(k)
    assertThat(store.archive.analyzed(listOf(k), 4)).isEmpty()
    store.archive.markUnsupported(k, 5)
    assertThat(store.archive.analyzed(listOf(k), 5)).containsExactly(k)
    store.close()
  }

  @Test
  fun ilVincitoreSiRifaAOgniSalvataggioEIVoltiDiUnCorpoSostituitoSiTolgono() = runBlocking<Unit> {
    val store = world.ready(generation)
    val k = FaceFixtures.key(1)
    val sql = store.database.openHelper.writableDatabase
    fun winnerOf(): String = sql.query("SELECT author FROM face_body WHERE content_key = ? AND is_winner = 1", arrayOf(k.hex)).use { c ->
      assertThat(c.count).isEqualTo(1)
      c.moveToFirst()
      c.getString(0)
    }
    store.archive.saveBody(FaceFixtures.body(k, big, recipe = 1))
    assertThat(winnerOf()).isEqualTo(big)
    store.archive.saveBody(FaceFixtures.body(k, small, recipe = 1))
    assertThat(winnerOf()).isEqualTo(small) // a pari ricetta l'autore minore
    store.archive.saveBody(FaceFixtures.body(k, big, recipe = 2))
    assertThat(winnerOf()).isEqualTo(big) // la ricetta piu' alta
    store.archive.saveBody(FaceFixtures.body(k, big, recipe = 1))
    assertThat(winnerOf()).isEqualTo(small) // lo stesso autore sostituisce: ora la ricetta piu' alta e' pari e vince il minore
    assertThat(store.counts().bodies).isEqualTo(2)
    // Cambiare i volti di un corpo toglie i vecchi (cascata): nessuno resta orfano.
    store.archive.saveBody(FaceFixtures.body(k, small, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE), FaceFixtures.entry(1, FaceTier.WEAK))))
    assertThat(store.counts().faces).isEqualTo(3) // 1 (corpo di big) + 2 (corpo di small)
    store.archive.saveBody(FaceFixtures.body(k, small, faces = emptyList()))
    assertThat(store.counts().faces).isEqualTo(1)
    store.close()
  }

  @Test
  fun unCorpoDiUnaFormaCheQuestaVersioneNonConoscePiuNonSiLeggeMaContaComeFatto() = runBlocking<Unit> {
    val store = world.ready(generation)
    val keys = (1..4).map { FaceFixtures.key(it) }
    keys.forEach { store.archive.saveBody(FaceFixtures.body(it, recipe = 2)) }
    val sql = store.database.openHelper.writableDatabase
    // Scritti da un'app piu' nuova: uno spazio, un rilevatore, una sorgente e un livello che questa versione non conosce.
    sql.execSQL("UPDATE face_body SET space = 'spazio_futuro' WHERE content_key = ?", arrayOf<Any>(keys[0].hex))
    sql.execSQL("UPDATE face_body SET detector = 'rilevatore_futuro' WHERE content_key = ?", arrayOf<Any>(keys[1].hex))
    sql.execSQL("UPDATE face_body SET source = 'sorgente_futura' WHERE content_key = ?", arrayOf<Any>(keys[2].hex))
    sql.execSQL("UPDATE face SET tier = 'livello_futuro' WHERE content_key = ?", arrayOf<Any>(keys[3].hex))
    assertThat(store.archive.winners(keys)).isEmpty()
    // Il raggruppamento vede solo lo spazio corrente e i livelli che conosce: il rilevatore e la sorgente non cambiano i vettori.
    assertThat(store.archive.groupFaces().map { it.ref.contentKey }).containsExactlyElementsIn(listOf(keys[1], keys[2]).sortedBy { it.hex }).inOrder()
    // "Gia' fatto" guarda la ricetta, non la forma: un altro dispositivo l'ha fatto, e non si rifa'.
    assertThat(store.archive.analyzed(keys, 2)).containsExactlyElementsIn(keys)
    store.close()
  }

  // --- segni delle teste, outbox, unita' fatte ------------------------------------------------------------------------------

  @Test
  fun iSegniDelleTesteNonSiAbbassanoMai() = runBlocking<Unit> {
    val store = world.ready(generation)
    val marks = store.headMarks
    assertThat(marks.get("dev-a")).isNull()
    assertThat(marks.record(HeadMark("dev-a", 5, "h5", 3, 1_000))).isTrue()
    assertThat(marks.record(HeadMark("dev-a", 4, "h4", 3, 900))).isFalse() // anti-rollback
    assertThat(marks.get("dev-a")!!.version).isEqualTo(5)
    assertThat(marks.record(HeadMark("dev-a", 5, "h5", 4, 1_100))).isTrue() // stesso numero: lo decide chi sincronizza (equivocazione)
    assertThat(marks.record(HeadMark("dev-a", 6, "h6", 4, 1_200))).isTrue()
    assertThat(marks.record(HeadMark("dev-b", 1, "b1", 4, 1_300))).isTrue()
    assertThat(marks.all().map { it.deviceId }).containsExactly("dev-a", "dev-b").inOrder()
    marks.forget("dev-a")
    assertThat(marks.all().map { it.deviceId }).containsExactly("dev-b")
    assertThat(marks.get("dev-b").toString()).doesNotContain("dev-b")
    store.close()
    // Sopravvivono alla chiusura.
    assertThat(world.ready(generation).headMarks.get("dev-b")!!.contentHash).isEqualTo("b1")
  }

  @Test
  fun lOutboxERimessaInCodaSenzaDuplicatiEAcknowledgeTogliSoloCioCheHaLetto() = runBlocking<Unit> {
    val store = world.ready(generation)
    val outbox = store.outbox
    outbox.stage(FacesChange.Faces(FaceFixtures.key(1)))
    outbox.stage(FacesChange.Faces(FaceFixtures.key(2)))
    outbox.stage(FacesChange.Faces(FaceFixtures.key(1))) // la stessa cosa due volte: una voce sola
    outbox.stage(FacesChange.Snapshot(7))
    outbox.stage(FacesChange.People(listOf(PeopleEdit.Hidden(person(1), true))))
    outbox.stage(FacesChange.People(emptyList()))
    assertThat(outbox.count()).isEqualTo(4)
    val read = outbox.pending()
    // In ordine di messa in coda; la voce rimessa in coda ha preso il posto in fondo (id nuovo).
    assertThat(read.map { it.kind to it.ref }).containsExactly(
      OutboxKind.FACES to FaceFixtures.key(2).hex,
      OutboxKind.FACES to FaceFixtures.key(1).hex,
      OutboxKind.SNAPSHOT to "7",
      OutboxKind.PEOPLE to "",
    ).inOrder()
    assertThat(outbox.pending(limit = 2)).hasSize(2)
    // Mentre chi pubblica lavora, la chiave 1 viene rimessa in coda: la sua voce nuova resta.
    outbox.stageFaces(FaceFixtures.key(1))
    outbox.acknowledge(read)
    assertThat(outbox.pending().map { it.kind to it.ref }).containsExactly(OutboxKind.FACES to FaceFixtures.key(1).hex)
    outbox.acknowledge(emptyList())
    assertThat(outbox.count()).isEqualTo(1)
    // Un tipo sconosciuto (scritto da un'app piu' nuova) resta in coda ma non si legge.
    store.database.openHelper.writableDatabase.execSQL("INSERT INTO faces_outbox (kind, ref) VALUES ('futuro', 'x')")
    assertThat(outbox.pending()).hasSize(1)
    assertThat(outbox.count()).isEqualTo(2)
    store.close()
    assertThat(world.ready(generation).outbox.count()).isEqualTo(2)
  }

  @Test
  fun leUnitaFatteNonSiAbbassanoEUnaRicettaNuovaLeRifa() = runBlocking<Unit> {
    val store = world.ready(generation)
    val units = store.units
    val u1 = FacesResource.WorkUnit(AreaKind.PRIVATE, "2025-03", 0)
    val u2 = FacesResource.WorkUnit(AreaKind.COMMON, "0000-00", 3)
    assertThat(units.isDone(u1, 1)).isFalse()
    units.markDone(u1, recipe = 2, doneAtNas = 5_000)
    units.markDone(u2, recipe = 1, doneAtNas = null)
    assertThat(units.isDone(u1, 2)).isTrue()
    assertThat(units.isDone(u1, 1)).isTrue()
    assertThat(units.isDone(u1, 3)).isFalse() // ricetta nuova: da rifare
    units.markDone(u1, recipe = 1, doneAtNas = 6_000) // una ricetta piu' vecchia non abbassa quella che c'e'
    assertThat(units.isDone(u1, 2)).isTrue()
    assertThat(units.done(1)).containsExactly(u1, u2)
    assertThat(units.done(2)).containsExactly(u1)
    assertThat(units.done(3)).isEmpty()
    store.close()
    assertThat(world.ready(generation).units.done(1)).containsExactly(u1, u2)
  }

  @Test
  fun unaChiaveDiContenutoNonSiTrovaNeiLogNeNelleStringheDeiTipi() {
    val ref = FaceRef(ContentKey("ab".repeat(32)), 0, 0, 1)
    assertThat(ref.toString()).doesNotContain("ab")
    assertThat(OutboxEntry(OutboxKind.FACES, "ab".repeat(32), 1).toString()).doesNotContain("ab")
    assertThat(HeadMark("dev-secret", 1, "hash-secret", 1, 1).toString()).doesNotContain("secret")
  }
}
