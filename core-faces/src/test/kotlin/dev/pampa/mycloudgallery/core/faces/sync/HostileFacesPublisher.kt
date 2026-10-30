package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.FacesBody
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout

/**
 * IL PUBBLICATORE OSTILE DELLE PROVE (correzioni della Fase 8, pacchetto "formato e replica dei volti"): un dispositivo **compromesso** dello stesso
 * account. Firma davvero con la sua chiave (il formato lo accetta) e scrive a mano nella sua cartella del NAS finto ([MemoryShare]) quello che un
 * pubblicatore onesto non scriverebbe mai: voci `people` a nome di altri, istantanee con la `seq` vicina al tetto, teste che elencano segmenti mai
 * scritti, corpi di contenuti inventati a ogni testa. Solo nelle prove, mai sulle share vere.
 */
internal class HostileFacesPublisher(private val world: FacesSyncWorld, private val device: SyncDevice) {
  var version = 0L
    private set

  val deviceId: String get() = device.id

  /** Una testa nuova con [segments] (classe, byte); [absent] = impronte elencate e mai scritte (dimensione [absentSize]). */
  fun head(
    segments: List<Pair<SegmentClass, ByteArray>>,
    snapshot: SnapshotMark? = null,
    absent: List<String> = emptyList(),
    absentSize: Long = 1_000,
  ): Long {
    version++
    val classed = segments.map { (cls, bytes) -> ClassedSegment(cls, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())) } +
      absent.map { ClassedSegment(SegmentClass.PEOPLE, SegmentRef(it, absentSize)) }
    for ((_, bytes) in segments) device.share.plant(NasLayout.facesSegment(world.generation, device.id, Hashes.sha256Name(bytes)), bytes)
    val head = FacesHead(
      world.family.familyId, "test", world.generation, device.id, version, world.family.state.registry.version, world.clock.nasNow!!, snapshot,
      classed.sortedBy { it.ref.hash },
    )
    device.share.plant(NasLayout.facesHead(world.generation, device.id, version), FacesDocs.encodeHead(head, device.signer).bytes)
    return version
  }

  fun people(vararg docs: PeopleDoc): EncodedSegment = FacesDocs.peopleSegment(docs.toList())

  fun cluster(entries: List<ClusterEntry>): EncodedSegment = FacesDocs.clusterSegment(entries)

  /** I corpi di [keys] (uno o piu' volti ciascuno) firmati come calcolati da questo dispositivo. */
  fun bodies(keys: List<ContentKey>, faces: Int = 1): EncodedSegment {
    val items = keys.mapIndexed { i, key ->
      val body = FacesWire.build(
        key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, device.id,
        (0 until faces).map { FacesSyncWorld.entry(it, FaceTier.CORE, seed = i * 10 + it) },
      )!!
      key to FacesWire.doc(body)
    }
    return FacesDocs.bodiesSegment(items)
  }

  /** Un corpo del formato per una prova sola. */
  fun body(body: FaceBody): FacesBody = FacesWire.doc(body)

  /** Le impronte di [n] segmenti mai scritti. */
  fun absent(n: Int, salt: String = "assente"): List<String> = (0 until n).map { Hashes.sha256Name("$salt-$it-$version".toByteArray()) }
}
