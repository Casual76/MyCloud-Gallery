package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.GoneContents
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.ForbiddenCall
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * UN'ISTANTANEA STRANA DAL NAS NON FA DANNI (docs/fase8-progetto.md §4.3 "nessuna azione distruttiva da dati remoti"; D21). Il raggruppamento
 * non ha porte che cancellano (niente `FacesEraseOps`, album, cronologia): dall'archivio legge volti, voci `people` e il suo stato e scrive
 * **solo** il suo stato. Un'istantanea con un numero enorme, alias verso persone che non ci sono, cicli, volti che non esistono e persone
 * inventate si adotta come le altre (la fiducia nel firmatario e' di P3) senza eccezioni, senza toccare i corpi dei volti ne' le voci, e
 * senza perdere le persone che questo dispositivo conosce.
 */
class HostileSnapshotTest {
  private val story = FamilyStories.story(FamilyStories.Config(seed = 606, identities = 6, photos = 90, siblings = 0))

  /** L'archivio che vieta ogni scrittura che non sia lo stato del raggruppamento. */
  private class OnlyGrouping(private val inner: InMemoryFacesArchive) : FacesArchive by inner {
    override suspend fun saveBody(body: FaceBody) = throw ForbiddenCall("FacesArchive.saveBody")
    override suspend fun markUnsupported(key: ContentKey, recipe: Int) = throw ForbiddenCall("FacesArchive.markUnsupported")
    override suspend fun addPeople(records: List<PeopleRecord>) = throw ForbiddenCall("FacesArchive.addPeople")
  }

  @Test
  fun unIstantaneaStranaSiAdottaSenzaDanni() = runTest {
    val nas = ClusterNas()
    val device = ClusterDevice("bbbb", nas, idsSeed = 1)
    device.receive(story.faces)
    device.grouping.incremental(5.minutes)
    device.grouping.nightly(5.minutes)
    val mine = device.archive.grouping()
    val bodiesBefore = device.archive.groupFaces().map { it.ref }
    // L'istantanea strana di "aaaa": numero enorme, una persona con un alias verso un id che non c'e', un ciclo di tre, un volto inventato.
    val ghost = PersonId.fromBytes(ByteArray(16) { 0x11 })
    val a = PersonId.fromBytes(ByteArray(16) { 0x21 })
    val b = PersonId.fromBytes(ByteArray(16) { 0x22 })
    val c = PersonId.fromBytes(ByteArray(16) { 0x23 })
    val dangling = PersonId.fromBytes(ByteArray(16) { 0x24 })
    val fakeKey = GroupingStories.contentKey(999, 1)
    val fakeRef = FaceRef(fakeKey, 0, 0, 1)
    val persons = mapOf(
      ghost to GroupPerson(ghost, PersonState.ACTIVE, null, 1, 1, listOf(fakeRef)),
      a to GroupPerson(a, PersonState.MERGED, b, 1, 1, emptyList()),
      b to GroupPerson(b, PersonState.MERGED, c, 1, 1, emptyList()),
      c to GroupPerson(c, PersonState.MERGED, a, 1, 1, emptyList()),
      dangling to GroupPerson(dangling, PersonState.MERGED, PersonId.fromBytes(ByteArray(16) { 0x7f }), 1, 1, emptyList()),
    )
    val assignments = mapOf(
      fakeRef to GroupAssignment(fakeRef, FaceLocator(fakeKey, 0, FaceBox(1, 1, 100, 100)), ghost, 0.9f, AssignOrigin.AUTO),
    )
    val hostile = GroupingState(Long.MAX_VALUE / 4, 0, persons, assignments)
    val grouping = CustodianGrouping(
      archives = { OnlyGrouping(device.archive) },
      deviceId = "bbbb",
      leases = nas.leases("bbbb"),
      snapshots = ClusterSnapshots { g -> nas.snapshots.latest(g) + ClusterSnapshot("aaaa", hostile) },
      publisher = device.publisher,
      gone = GoneContents { emptySet() },
      ids = GroupingStories.ids(2),
    )
    grouping.incremental(5.minutes)
    grouping.nightly(5.minutes)
    val after = device.archive.grouping()
    // Nessun corpo toccato, nessuna voce scritta (l'archivio l'avrebbe vietato), nessuna persona di questo dispositivo persa.
    assertThat(device.archive.groupFaces().map { it.ref }).isEqualTo(bodiesBefore)
    for (id in mine.persons.keys) assertThat(after.persons).containsKey(id)
    for (id in mine.persons.values.filter { it.state == PersonState.ACTIVE }.map { it.id }) assertThat(after.resolve(id)).isNotNull()
    // Il volto inventato non resta assegnato; il ciclo ha una radice; le regole valgono.
    assertThat(after.assignments).doesNotContainKey(fakeRef)
    assertThat(after.resolve(a)).isEqualTo(after.resolve(b))
    assertThat(Invariants.violations(after, story.faces, GroupingCorrections.NONE)).isEmpty()
  }
}
