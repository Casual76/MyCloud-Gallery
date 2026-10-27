package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.ClusterOrigin
import dev.pampa.mycloudgallery.core.trust.data.ClusterPersonState
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesSnapshotDoc
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark

/**
 * Stati di prova per le suite di contratto della replica e del pubblicatore (Fase 8, P6): dispositivi con teste, segmenti, voci `cluster` vere
 * (persone con il centroide, assegnazioni che tornano con le persone) e istantanee coerenti. Solo identita' sintetiche e impronte inventate.
 */
internal object SyncStateSamples {

  fun hash(n: Int): String = Hashes.sha256Name("segmento-$n".toByteArray())

  fun segment(cls: SegmentClass, n: Int, size: Long = 1_000L + n): ClassedSegment = ClassedSegment(cls, SegmentRef(hash(n), size))

  /**
   * Un'istantanea coerente: [persons] persone (la prima unita' nella seconda) con [perPerson] assegnazioni ciascuna, di cui una ogni cinque
   * ignorata. Ritorna le voci (persone e assegnazioni) in un ordine qualunque.
   */
  fun snapshotEntries(persons: Int, perPerson: Int, seed: Int = 0, seq: Long = 9): List<ClusterEntry> {
    val entries = ArrayList<ClusterEntry>()
    val ids = (0 until persons).map { FacesSyncWorld.person(seed * 100_000 + it) }
    for ((p, id) in ids.withIndex()) {
      val refs = (0 until perPerson).map { i ->
        FaceRef(FacesSyncWorld.key(seed * 1_000_000 + p * 1_000 + i), 0, i % 3, 700L + p)
      }
      val counted = refs.indices.count { it % 5 != 4 }
      val merged = p == 1
      entries += ClusterEntry.Person(
        id = id,
        state = if (merged) ClusterPersonState.MERGED else ClusterPersonState.ACTIVE,
        aliasOf = if (merged) ids[0] else null,
        bornSeq = 1,
        changedSeq = seq,
        faces = counted,
        centroid = FacesSyncWorld.vector(seed * 1_000 + p),
        exemplars = refs.take(4),
      )
      refs.forEachIndexed { i, ref ->
        entries += ClusterEntry.Assignment(ref, id, 5_000 + i, if (i % 5 == 4) ClusterOrigin.IGNORED else ClusterOrigin.AUTO)
      }
    }
    return entries
  }

  fun snapshotDoc(entries: List<ClusterEntry>, mark: SnapshotMark): FacesSnapshotDoc =
    (FacesDocs.checkSnapshot(mark, entries) as Verdict.Accepted).value

  /** Un dispositivo con un'istantanea completa: due segmenti `cluster` (le persone, le assegnazioni), uno `faces`, uno `people`. */
  fun deviceWithSnapshot(seed: Int, persons: Int = 3, perPerson: Int = 6, version: Long = 7): DeviceReplica {
    val entries = snapshotEntries(persons, perPerson, seed)
    val people = entries.filterIsInstance<ClusterEntry.Person>()
    val assignments = entries.filterIsInstance<ClusterEntry.Assignment>()
    val mark = SnapshotMark(9, 4)
    val head = HeadMark(version, "testa-$seed-$version")
    val faces = segment(SegmentClass.FACES, seed * 10 + 1)
    val peopleSegment = segment(SegmentClass.PEOPLE, seed * 10 + 2)
    val clusterA = segment(SegmentClass.CLUSTER, seed * 10 + 3)
    val clusterB = segment(SegmentClass.CLUSTER, seed * 10 + 4)
    val segments = listOf(faces, peopleSegment, clusterA, clusterB)
    return DeviceReplica(
      mark = head,
      segments = segments,
      snapshotMark = mark,
      done = segments.map { it.ref.hash }.toSet(),
      counts = mapOf(faces.ref.hash to 12, peopleSegment.ref.hash to 3, clusterA.ref.hash to people.size, clusterB.ref.hash to assignments.size),
      cluster = mapOf(clusterA.ref.hash to people, clusterB.ref.hash to assignments),
      snapshot = snapshotDoc(entries, mark),
      snapshotHead = head,
      checked = head,
    )
  }

  /** Un dispositivo a meta' lettura: una testa, quattro segmenti e uno solo fatto, nessuna istantanea. */
  fun deviceInProgress(seed: Int): DeviceReplica {
    val segments = listOf(
      segment(SegmentClass.FACES, seed * 10 + 1),
      segment(SegmentClass.FACES, seed * 10 + 5),
      segment(SegmentClass.PEOPLE, seed * 10 + 2),
      segment(SegmentClass.CLUSTER, seed * 10 + 3),
    )
    return DeviceReplica(
      mark = HeadMark(3, "testa-$seed-3"),
      segments = segments,
      snapshotMark = SnapshotMark(2, 1),
      done = setOf(segments[0].ref.hash),
      counts = mapOf(segments[0].ref.hash to 40),
    )
  }

  fun richReplica(): FacesReplicaState = FacesReplicaState(
    devices = linkedMapOf("dev-a" to deviceWithSnapshot(1), "dev-b" to deviceInProgress(2), "dev-c" to DeviceReplica()),
    revoked = setOf("dev-x", "dev-y"),
  )

  /** Lo stesso contenuto, riletto: i campi uno per uno (l'istantanea non ha `equals`: si confrontano marchio, persone e assegnazioni). */
  fun assertSameReplica(actual: FacesReplicaState, expected: FacesReplicaState) {
    assertThat(actual.devices.keys).containsExactlyElementsIn(expected.devices.keys)
    assertThat(actual.revoked).isEqualTo(expected.revoked)
    for ((id, want) in expected.devices) {
      val got = actual.devices.getValue(id)
      val label = "dispositivo $id"
      assertWithMessage(label).that(got.mark).isEqualTo(want.mark)
      assertWithMessage(label).that(got.segments).isEqualTo(want.segments)
      assertWithMessage(label).that(got.snapshotMark).isEqualTo(want.snapshotMark)
      assertWithMessage(label).that(got.done).isEqualTo(want.done)
      assertWithMessage(label).that(got.counts).isEqualTo(want.counts)
      assertWithMessage(label).that(got.snapshotHead).isEqualTo(want.snapshotHead)
      assertWithMessage(label).that(got.checked).isEqualTo(want.checked)
      assertWithMessage(label).that(got.cluster.keys).isEqualTo(want.cluster.keys)
      for ((hash, entries) in want.cluster) assertWithMessage(label).that(got.cluster.getValue(hash).toSet()).isEqualTo(entries.toSet())
      assertWithMessage(label).that(got.snapshot == null).isEqualTo(want.snapshot == null)
      want.snapshot?.let { doc ->
        val read = got.snapshot!!
        assertWithMessage(label).that(read.mark).isEqualTo(doc.mark)
        assertWithMessage(label).that(read.persons).isEqualTo(doc.persons)
        assertWithMessage(label).that(read.assignments).isEqualTo(doc.assignments)
      }
    }
  }

  fun person(n: Int): PersonId = FacesSyncWorld.person(n)
}
