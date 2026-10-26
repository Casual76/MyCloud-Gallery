package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArea
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesRemote
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryPeople
import dev.pampa.mycloudgallery.core.model.FaceGeneration

// Fase 8 (P0, §5.2): gli esecutori di riferimento passano le suite di contratto che passeranno i pezzi veri (P3, P5, P6, P9).

/** Il raggruppamento di riferimento. */
class ReferenceGroupingContractTest : GroupingContract() {
  override fun engine(): GroupingEngine = dev.pampa.mycloudgallery.core.faces.testing.ReferenceGrouping()
}

/** Le persone di riferimento. */
class InMemoryPeopleContractTest : PeopleContract() {
  override suspend fun create(world: PeopleWorld, namedOnly: Boolean): UnderTest {
    val people = InMemoryPeople(world.visibility, world.visibility, world.mediaOf, world.keysOf, world.author, ids = GroupingStories.ids(99), namedOnly = namedOnly)
    people.load(world.state, world.faces, none = listOf(world.none), suggestions = listOf(world.suggestion))
    return UnderTest(people, people) { people.edits.toList() }
  }
}

/** Il NAS dei volti di riferimento. */
class InMemoryFacesRemoteContractTest : FacesRemoteContract() {
  override fun world(share: String, common: String): World {
    val area = InMemoryFacesArea(share, common)
    return object : World {
      override fun remote(deviceId: String): FacesRemote = InMemoryFacesRemote(area, deviceId)

      override fun put(path: dev.pampa.mycloudgallery.core.trust.nas.SharePath, bytes: ByteArray) = area.put(path, bytes)

      override fun files(): List<dev.pampa.mycloudgallery.core.trust.nas.SharePath> = area.paths()

      override fun writes(): List<Pair<String, dev.pampa.mycloudgallery.core.trust.nas.SharePath>> = area.writes.toList()

      override fun failNext(operations: Int) {
        area.failNext = operations
      }
    }
  }
}

/** L'archivio dei volti in memoria. */
class InMemoryFacesArchiveContractTest : FacesArchiveContract() {
  override fun archive(generation: FaceGeneration): FacesArchive = InMemoryFacesArchive(generation)

  override fun close(archive: FacesArchive) = (archive as InMemoryFacesArchive).close()
}
