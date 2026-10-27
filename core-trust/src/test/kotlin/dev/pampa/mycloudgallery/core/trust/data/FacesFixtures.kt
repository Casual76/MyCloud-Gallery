package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * I dati dei volti di prova dei test del codec (Fase 8, P3): una famiglia vera (registro firmato), il telefono e il tablet dell'account
 * `test` (il tablet scritto `Test` nel registro: nelle teste va in minuscolo), il telefono di `test2` (un altro account), una generazione,
 * corpi, persone, assegnazioni e voci `people` di ogni forma. Solo identita' sintetiche: nessun volto, nome o vettore di persone vere.
 */
class FacesFx {
  val family = TestFamily()
  val phone: Signer = family.approveNewDevice(name = "Telefono di Test", nasUser = "test")
  val tablet: Signer = family.approveNewDevice(name = "Tablet di Test", nasUser = "Test")
  val other: Signer = family.approveNewDevice(name = "Telefono di Test2", nasUser = "test2")
  val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it * 11 + 3).toByte() })
  val now: Long get() = family.clock.nowMillis()

  fun key(n: Int): ContentKey = ContentKey.of(QuickHash(Hex.of(Hashes.sha256("volti-$n".toByteArray()))), 1_000L + n)

  fun person(n: Int): PersonId = PersonId.fromBytes(Hashes.sha256("persona-$n".toByteArray()).copyOf(16))

  /** Un vettore normalizzato in float16, diverso per [seed]. */
  fun vector(seed: Int): ShortArray {
    val v = FloatArray(FacesLimits.DIMENSION) { ((it * 37 + seed * 17) % 29 - 14).toFloat() + 0.5f }
    val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return Float16.encode(FloatArray(v.size) { v[it] / norm })
  }

  fun face(index: Int, tier: FaceTier = FaceTier.CORE, frame: Int = 0, reasons: Int? = null): FaceRecord = FaceRecord(
    frame = frame,
    box = FaceBox(index * 1_000, 2_000, index * 1_000 + 900, 3_000),
    landmarks = ShortArray(FacesLimits.LANDMARK_VALUES) { (60_000 - it * 1_000).toShort() },
    score = 230,
    tier = tier,
    reasons = reasons ?: if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL,
    sidePx = if (tier == FaceTier.CORE) 120 else 30,
    yaw = -12,
    sharpness = 140,
    vector = if (tier.hasVector) vector(index) else null,
  )

  fun body(faces: List<FaceRecord> = listOf(face(0), face(1, FaceTier.WEAK), face(2, FaceTier.NONE)), recipe: Int = 1, source: FaceSource = FaceSource.ANALYSIS) =
    FacesBody(FaceSpaceCode.SFACE_2021DEC_V1, FaceDetectorCode.YUNET_2023MAR, recipe, source, faces)

  fun videoBody(): FacesBody = body(
    listOf(face(0, FaceTier.WEAK, frame = 0, reasons = FaceReasons.FRAME), face(1, FaceTier.WEAK, frame = 3, reasons = FaceReasons.FRAME or FaceReasons.BLURRY)),
    source = FaceSource.FRAMES,
  )

  fun ref(n: Int, index: Int = 0): FaceRef = FaceRef(key(n), 0, index, 0x0102030405060708L + n)

  fun personEntry(
    n: Int,
    faces: Int = 0,
    state: ClusterPersonState = ClusterPersonState.ACTIVE,
    alias: PersonId? = null,
    exemplars: List<FaceRef> = listOf(ref(n)),
    bornSeq: Long = 1,
    changedSeq: Long = 2,
  ) = ClusterEntry.Person(person(n), state, alias, bornSeq, changedSeq, faces, if (faces == 0) ShortArray(FacesLimits.DIMENSION) else vector(n), exemplars)

  fun assignment(n: Int, to: Int, origin: ClusterOrigin = ClusterOrigin.AUTO, index: Int = 0) =
    ClusterEntry.Assignment(ref(n, index), person(to), 5_123, origin)

  /** Un'istantanea coerente: tre persone (una unita nell'altra), cinque assegnazioni (una ignorata). */
  fun snapshotEntries(): List<ClusterEntry> = listOf(
    personEntry(1, faces = 2),
    personEntry(2, faces = 2),
    personEntry(3, state = ClusterPersonState.MERGED, alias = person(1)),
    assignment(10, 1),
    assignment(11, 1, ClusterOrigin.USER),
    assignment(12, 2),
    assignment(13, 2),
    assignment(14, 3, ClusterOrigin.IGNORED),
  )

  fun locator(n: Int): FaceLocator = FaceLocator(key(n), 0, FaceBox(100, 200, 4_000, 5_000))

  /** Una voce `people` per ogni campo e forma, lapidi comprese. */
  fun everyPeople(author: String = phone.deviceId, at: Long = now): List<PeopleDoc> {
    val a = person(1).base32()
    val b = person(2).base32()
    val loc = locator(1).encode()
    return listOf(
      PeopleDoc(a, "name", "Persona Uno", at, author),
      PeopleDoc(b, "name", null, at, author),
      PeopleDoc(a, "hidden", "true", at, author),
      PeopleDoc(b, "hidden", "false", at, author),
      PeopleDoc(a, "forget", "true", at, author),
      PeopleDoc(b, "alias", a, at, author),
      PeopleDoc(a, "apart:$b", "true", at, author),
      PeopleDoc(b, "apart:$a", null, at, author),
      PeopleDoc(b, "born", "split:$a", at, author),
      PeopleDoc(a, "cover", loc, at, author),
      PeopleDoc(b, "cover", null, at, author),
      PeopleDoc(loc, "is", a, at, author),
      PeopleDoc(locator(2).encode(), "is", null, at, author),
      PeopleDoc(loc, "not:$b", "true", at, author),
      PeopleDoc(locator(2).encode(), "not:$a", null, at, author),
      PeopleDoc(loc, "ignore", "true", at, author),
      PeopleDoc(locator(2).encode(), "ignore", null, at, author),
    )
  }

  fun account(signer: Signer): String = family.state.registry.device(signer.deviceId)!!.nasUser.lowercase()

  fun head(
    segments: List<ClassedSegment> = emptyList(),
    version: Long = 1,
    signer: Signer = phone,
    snapshot: SnapshotMark? = null,
    createdAt: Long = now,
    generation: FaceGeneration = this.generation,
  ): EncodedFacesHead = FacesDocs.encodeHead(
    FacesHead(family.familyId, account(signer), generation, signer.deviceId, version, family.state.registry.version, createdAt, snapshot, segments),
    signer,
  )

  fun accept(bytes: ByteArray, version: Long = 1, signer: Signer = phone, reader: String = "test", previous: HeadMark? = null, location: FaceGeneration = generation) =
    FacesDocs.decodeHead(bytes, family.state, FacesHeadLocation(location, signer.deviceId, version), reader, previous, now)

  companion object {
    /** Un segmento dei volti con le righe [rows] cosi' come sono: versione, classe, conteggio e riservati a scelta (test ostili). */
    fun rawSegment(rows: List<Pair<ByteArray, ByteArray>>, version: Int = 4, cls: Int = 18, count: Int = rows.size, reserved: Int = 0): Pair<ByteArray, SegmentRef> {
      val size = 5 + 8 + 36 * rows.size + rows.sumOf { it.second.size }
      val out = ByteBuffer.allocate(size)
      out.put("MCGB".toByteArray(Charsets.US_ASCII)).put(version.toByte()).put(cls.toByte()).put(reserved.toByte()).put(0).put(0).putInt(count)
      for ((key, body) in rows) out.put(key).putInt(body.size)
      for ((_, body) in rows) out.put(body)
      val bytes = out.array()
      return bytes to SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())
    }
  }
}
