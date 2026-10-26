package dev.pampa.mycloudgallery.core.faces.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La SUITE DI CONTRATTO dell'archivio dei volti (docs/fase8-progetto.md §2.3 "Salvataggio", §2.4 "Fusione dei rilevamenti", D14-D16).
 * Gira sull'archivio in memoria ([InMemoryFacesArchiveContractTest]) e sull'archivio vero di store/ (P6) sopra `FacesDatabase` cifrato.
 * Le regole: il vincitore fra i corpi di dispositivi diversi; "gia' fatto" con la ricetta o piu' nuova, "non supportato" compreso; i volti
 * da raggruppare solo dal vincitore nello spazio corrente; le voci `people` con l'ultima scrittura che vince; un archivio chiuso non risponde.
 */
abstract class FacesArchiveContract {

  protected abstract fun archive(generation: FaceGeneration): FacesArchive

  /** La chiave della generazione distrutta (o la generazione cambiata): da qui ogni chiamata lancia `IllegalStateException`. */
  protected abstract fun close(archive: FacesArchive)

  private val g = FaceGeneration.fromBytes(ByteArray(16) { 3 })
  private val ids = GroupingStories.ids(41)
  private val small = "0".repeat(26)
  private val big = "Z".repeat(26)

  private fun key(n: Int) = GroupingStories.contentKey(41, n)

  private fun vector(seed: Int): ShortArray {
    val v = FloatArray(FaceSpaceCode.CURRENT.dimension) { ((it * 31 + seed * 17) % 23 - 11).toFloat() }
    val norm = kotlin.math.sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return Float16.encode(FloatArray(v.size) { v[it] / norm })
  }

  private fun entry(index: Int, tier: FaceTier, seed: Int = index) = FaceEntry(
    index = index,
    frame = 0,
    box = FaceBox(index * 6_000, 1_000, index * 6_000 + 5_000, 6_000),
    landmarks = ShortArray(10) { (it * 100).toShort() },
    score = 200,
    tier = tier,
    reasons = if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL,
    sidePx = 120,
    yaw = 0,
    sharpness = 100,
    vector = if (tier.hasVector) vector(seed) else null,
  )

  private fun body(
    key: ContentKey,
    author: String,
    recipe: Int = 1,
    faces: List<FaceEntry> = listOf(entry(0, FaceTier.CORE)),
    digest: Long = recipe * 1_000L + author.hashCode(),
  ) = FaceBody(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, recipe, FaceSource.ANALYSIS, author, faces, digest)

  @Test
  fun vinceLaRicettaPiuAltaPoiIlDispositivoMinore() = runTest {
    val a = archive(g)
    a.saveBody(body(key(1), big, recipe = 2))
    a.saveBody(body(key(1), small, recipe = 1))
    a.saveBody(body(key(2), big, recipe = 1))
    a.saveBody(body(key(2), small, recipe = 1))
    val winners = a.winners(listOf(key(1), key(2), key(3)))
    assertThat(winners.keys).containsExactly(key(1), key(2))
    assertThat(winners.getValue(key(1)).author).isEqualTo(big)
    assertThat(winners.getValue(key(2)).author).isEqualTo(small)
    // Lo stesso autore sostituisce il suo corpo; rifare lo stesso salvataggio non cambia niente (idempotente).
    a.saveBody(body(key(2), small, recipe = 3))
    a.saveBody(body(key(2), small, recipe = 3))
    assertThat(a.winners(listOf(key(2))).getValue(key(2)).recipe).isEqualTo(3)
  }

  @Test
  fun giaFattoConLaRicettaONonSupportato() = runTest {
    val a = archive(g)
    a.saveBody(body(key(1), big, recipe = 2, faces = emptyList()))
    a.markUnsupported(key(2), recipe = 2)
    val keys = listOf(key(1), key(2), key(3))
    assertThat(a.analyzed(keys, recipe = 2)).containsExactly(key(1), key(2))
    assertThat(a.analyzed(keys, recipe = 1)).containsExactly(key(1), key(2))
    // Una ricetta nuova rifa' tutto, anche i "non supportati".
    assertThat(a.analyzed(keys, recipe = 3)).isEmpty()
    // "Analizzato, nessun volto": un vincitore con zero volti.
    assertThat(a.winners(listOf(key(1))).getValue(key(1)).faces).isEmpty()
  }

  @Test
  fun voltiDaRaggrupparePresiDalVincitoreNelloSpazioCorrente() = runTest {
    val a = archive(g)
    val faces = listOf(entry(0, FaceTier.CORE), entry(1, FaceTier.WEAK), entry(2, FaceTier.NONE))
    a.saveBody(body(key(1), small, recipe = 1, faces = faces))
    // Un corpo perdente con un volto in piu' non conta.
    a.saveBody(body(key(1), big, recipe = 1, faces = faces + entry(3, FaceTier.CORE)))
    val group = a.groupFaces()
    assertThat(group.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.WEAK).inOrder()
    val winner = a.winners(listOf(key(1))).getValue(key(1))
    assertThat(group.map { it.ref }).containsExactly(winner.refOf(0), winner.refOf(1)).inOrder()
    assertThat(group[0].locator).isEqualTo(FaceLocator(key(1), 0, faces[0].box))
    assertThat(group[0].vector().toList()).isEqualTo(Float16.decode(faces[0].vector!!).toList())
    assertThat(a.unrecognizable(listOf(key(1), key(2)))).containsExactly(key(1), 1)
    // Lo stesso ordine a ogni chiamata (il raggruppamento e' deterministico).
    assertThat(a.groupFaces().map { it.ref }).isEqualTo(group.map { it.ref })
  }

  @Test
  fun istantaneaDelRaggruppamento() = runTest {
    val a = archive(g)
    assertThat(a.grouping().persons).isEmpty()
    val person = ids.next()
    val face = body(key(1), small)
    a.saveBody(face)
    val ref = face.refOf(0)
    val locator = FaceLocator(key(1), 0, face.faces[0].box)
    val state = GroupingState(
      seq = 4,
      base = 1,
      persons = mapOf(person to GroupPerson(person, PersonState.ACTIVE, null, 1, 4, listOf(ref))),
      assignments = mapOf(ref to GroupAssignment(ref, locator, person, 0.7f, AssignOrigin.AUTO)),
    )
    a.saveGrouping(state)
    val back = a.grouping()
    assertThat(back.seq).isEqualTo(4)
    assertThat(back.persons.keys).containsExactly(person)
    assertThat(back.assignments.getValue(ref).person).isEqualTo(person)
  }

  @Test
  fun leVociPeopleUltimaScritturaVince() = runTest {
    val a = archive(g)
    val p = ids.next()
    a.addPeople(listOf(PeopleRecord(PeopleEdit.Name(p, "Anna"), atNas = 100, author = big)))
    a.addPeople(listOf(PeopleRecord(PeopleEdit.Name(p, "Bea"), atNas = 90, author = small)))
    assertThat(a.people().values.single().edit).isEqualTo(PeopleEdit.Name(p, "Anna"))
    // A pari ora vince l'autore minore; una voce da datare vince su tutte.
    a.addPeople(listOf(PeopleRecord(PeopleEdit.Name(p, "Carla"), atNas = 100, author = small)))
    assertThat(a.people().values.single().edit).isEqualTo(PeopleEdit.Name(p, "Carla"))
    val fresh = PeopleRecord(PeopleEdit.Name(p, null), atNas = null, author = big)
    a.addPeople(listOf(fresh))
    a.addPeople(listOf(fresh))
    assertThat(a.people().values.single()).isEqualTo(fresh)
    // Chiavi diverse convivono.
    a.addPeople(listOf(PeopleRecord(PeopleEdit.Hidden(p, true), atNas = 5, author = small)))
    assertThat(a.people()).hasSize(2)
  }

  @Test
  fun unArchivioChiusoNonRisponde() = runTest {
    val a = archive(g)
    a.saveBody(body(key(1), small))
    close(a)
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.saveBody(body(key(2), small)) } }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.winners(listOf(key(1))) } }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.analyzed(listOf(key(1)), 1) } }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.groupFaces() } }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.grouping() } }
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { a.people() } }
  }

  @Test
  fun ogniGenerazioneHaIlSuoArchivio() = runTest {
    val a = archive(g)
    val other = archive(FaceGeneration.fromBytes(ByteArray(16) { 4 }))
    a.saveBody(body(key(1), small))
    assertThat(a.generation).isEqualTo(g)
    assertThat(other.winners(listOf(key(1)))).isEmpty()
    assertThat(other.groupFaces()).isEmpty()
  }
}
