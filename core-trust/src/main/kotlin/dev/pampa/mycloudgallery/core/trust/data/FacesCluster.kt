package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer

// LE VOCI DELL'ISTANTANEA (classe `cluster`, 19; Fase 8, P3; docs/fase8-progetto.md §2.4, D21, D22) e le forme binarie dei corpi `faces` e
// `cluster`. Le voci `cluster` le pubblica solo il custode; un'istantanea e' TUTTE le voci `cluster` elencate da una testa con `snapshot`
// (le regole fra voci, come "ogni assegnazione nomina una persona dell'istantanea", sono in `FacesDocs.checkSnapshot`).

/** Una voce dell'istantanea del raggruppamento (classe `cluster`, §2.4, D21): una persona o un'assegnazione. */
sealed interface ClusterEntry {
  /** La chiave della voce nel segmento (32 byte): un'impronta con dominio dell'id o del riferimento. */
  val key: ByteArray

  /**
   * Una persona: [id], [state], [aliasOf] (solo per una persona unita, D22), [bornSeq] e [changedSeq] (le istantanee in cui e' nata e
   * cambiata l'ultima volta), [faces] (le sue assegnazioni non ignorate nell'istantanea), il centroide in float16 (tutto zero se nessun
   * volto con vettore e' disponibile al custode) e al piu' 16 esemplari distinti.
   */
  class Person(
    val id: PersonId,
    val state: ClusterPersonState,
    val aliasOf: PersonId?,
    val bornSeq: Long,
    val changedSeq: Long,
    val faces: Int,
    centroid: ShortArray,
    val exemplars: List<FaceRef>,
  ) : ClusterEntry {
    private val halves: ShortArray = centroid.copyOf()

    init {
      ensure(!id.isZero(), RejectReason.INVALID_FIELD, "persona: id riservato")
      ensure((state == ClusterPersonState.MERGED) == (aliasOf != null), RejectReason.INVALID_FIELD, "persona: alias solo per una persona unita")
      ensure(aliasOf == null || (aliasOf != id && !aliasOf.isZero()), RejectReason.INVALID_FIELD, "persona: alias di se stessa")
      ensure(bornSeq in 0..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "persona: istantanea di nascita")
      ensure(changedSeq in bornSeq..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "persona: istantanea dell'ultimo cambio")
      ensure(faces in 0..TrustLimits.MAX_SNAPSHOT_ASSIGNMENTS, RejectReason.INVALID_FIELD, "persona: numero dei volti")
      FacesVectors.checkCentroid(halves)
      ensure(exemplars.size <= FacesLimits.MAX_EXEMPLARS, RejectReason.TOO_LARGE, "persona: troppi esemplari")
      ensure(exemplars.toSet().size == exemplars.size, RejectReason.INVALID_FIELD, "persona: esemplari ripetuti")
    }

    val centroid: ShortArray get() = halves.copyOf()

    override val key: ByteArray get() = ClusterKeys.person(id)

    override fun equals(other: Any?): Boolean =
      other is Person && other.id == id && other.state == state && other.aliasOf == aliasOf && other.bornSeq == bornSeq &&
        other.changedSeq == changedSeq && other.faces == faces && other.halves.contentEquals(halves) && other.exemplars == exemplars

    override fun hashCode(): Int = ((id.hashCode() * 31 + state.hashCode()) * 31 + changedSeq.hashCode()) * 31 + halves.contentHashCode()

    override fun toString(): String = "ClusterEntry.Person(${state.name}, ${exemplars.size} esemplari)"
  }

  /** Un'assegnazione: il volto [face] e' della persona [person] con il punteggio [score] (coseno x 10.000) e l'origine [origin]. */
  data class Assignment(val face: FaceRef, val person: PersonId, val score: Int, val origin: ClusterOrigin) : ClusterEntry {
    init {
      ensure(!person.isZero(), RejectReason.INVALID_FIELD, "assegnazione: persona riservata")
      ensure(score in -FacesLimits.MAX_SCORE..FacesLimits.MAX_SCORE, RejectReason.INVALID_FIELD, "assegnazione: punteggio fuori intervallo")
    }

    override val key: ByteArray get() = ClusterKeys.assignment(face)

    override fun toString(): String = "ClusterEntry.Assignment(${origin.name})"
  }
}

/**
 * Le forme binarie dei corpi `faces` e delle voci `cluster` (§2.4). Lettura stretta: lunghezza esatta, nessuna allocazione da un numero
 * dichiarato oltre i tetti, ogni campo con il suo intervallo; un valore impossibile e' un `TrustViolation` (rifiuto del segmento).
 */
internal object FacesBinary {

  // --- Corpo `faces` -------------------------------------------------------------------------------------------------------------

  fun bodySize(body: FacesBody): Int =
    FacesLimits.BODY_HEADER_BYTES + body.faces.sumOf { FacesLimits.FACE_BYTES + if (it.tier.hasVector) FacesLimits.VECTOR_BYTES else 0 }

  fun writeBody(body: FacesBody): ByteArray {
    val out = ByteBuffer.allocate(bodySize(body))
    out.put(FacesLimits.BODY_VERSION.toByte()).put(body.space.wire.toByte()).put(body.detector.wire.toByte()).put(body.recipe.toByte())
    out.put(body.source.wire.toByte()).put(body.faces.size.toByte())
    for (face in body.faces) {
      out.put(face.frame.toByte())
      out.putShort(face.box.x0.toShort()).putShort(face.box.y0.toShort()).putShort(face.box.x1.toShort()).putShort(face.box.y1.toShort())
      for (point in face.landmarks) out.putShort(point)
      out.put(face.score.toByte()).put((face.tier.wire or (face.reasons shl 2)).toByte())
      out.putShort(face.sidePx.toShort()).put(face.yaw.toByte()).put(face.sharpness.toByte())
      face.vector?.forEach { out.putShort(it) }
    }
    return out.array()
  }

  fun readBody(b: ByteArray, off: Int, len: Int): FacesBody {
    ensure(len >= FacesLimits.BODY_HEADER_BYTES, RejectReason.MALFORMED, "corpo faces: intestazione troncata")
    val end = off + len
    ensure(u8(b, off) == FacesLimits.BODY_VERSION, RejectReason.INVALID_FIELD, "corpo faces: versione del corpo")
    val space = FaceSpaceCode.fromWire(u8(b, off + 1)) ?: violation(RejectReason.INVALID_FIELD, "corpo faces: spazio sconosciuto")
    ensure(space.dimension * 2 == FacesLimits.VECTOR_BYTES, RejectReason.INVALID_FIELD, "corpo faces: spazio non leggibile")
    val detector = FaceDetectorCode.fromWire(u8(b, off + 2)) ?: violation(RejectReason.INVALID_FIELD, "corpo faces: rilevatore sconosciuto")
    val recipe = u8(b, off + 3)
    val source = FaceSource.fromWire(u8(b, off + 4)) ?: violation(RejectReason.INVALID_FIELD, "corpo faces: sorgente sconosciuta")
    val count = u8(b, off + 5)
    ensure(count <= TrustLimits.MAX_FACES_PER_BODY, RejectReason.TOO_LARGE, "corpo faces: troppi volti")
    var p = off + FacesLimits.BODY_HEADER_BYTES
    val faces = ArrayList<FaceRecord>(count)
    repeat(count) {
      ensure(end - p >= FacesLimits.FACE_BYTES, RejectReason.MALFORMED, "corpo faces: volto troncato")
      val frame = u8(b, p)
      val x0 = u16(b, p + 1)
      val y0 = u16(b, p + 3)
      val x1 = u16(b, p + 5)
      val y1 = u16(b, p + 7)
      ensure(x0 < x1 && y0 < y1, RejectReason.INVALID_FIELD, "corpo faces: riquadro vuoto o rovesciato")
      val points = ShortArray(FacesLimits.LANDMARK_VALUES) { u16(b, p + 9 + 2 * it).toShort() }
      val score = u8(b, p + 29)
      val levelAndReasons = u8(b, p + 30)
      val tier = FaceTier.fromWire(levelAndReasons and 0x3) ?: violation(RejectReason.INVALID_FIELD, "corpo faces: livello sconosciuto")
      val side = u16(b, p + 31)
      val yaw = b[p + 33].toInt()
      val sharpness = u8(b, p + 34)
      p += FacesLimits.FACE_BYTES
      val vector = if (tier.hasVector) {
        ensure(end - p >= FacesLimits.VECTOR_BYTES, RejectReason.MALFORMED, "corpo faces: vettore troncato")
        val halves = ShortArray(space.dimension) { u16(b, p + 2 * it).toShort() }
        p += FacesLimits.VECTOR_BYTES
        halves
      } else {
        null
      }
      faces += FaceRecord(frame, FaceBox(x0, y0, x1, y1), points, score, tier, levelAndReasons ushr 2, side, yaw, sharpness, vector)
    }
    ensure(p == end, RejectReason.MALFORMED, "corpo faces: byte in piu'")
    return FacesBody(space, detector, recipe, source, faces)
  }

  // --- Voci `cluster` ------------------------------------------------------------------------------------------------------------

  fun refBytes(ref: FaceRef): ByteArray =
    ByteBuffer.allocate(FacesLimits.REF_BYTES).put(ref.contentKey.bytes()).put(ref.frame.toByte()).put(ref.index.toByte())
      .putLong(ref.bodyDigest).array()

  private fun readRef(b: ByteArray, p: Int): FaceRef {
    val frame = u8(b, p + 32)
    val index = u8(b, p + 33)
    ensure(frame <= FaceLocator.MAX_FRAME && index < FaceRef.MAX_FACES, RejectReason.INVALID_FIELD, "riferimento: fotogramma o indice")
    return FaceRef(ContentKey(Hex.of(b, p, 32)), frame, index, ByteBuffer.wrap(b, p + 34, 8).long)
  }

  fun write(entry: ClusterEntry): ByteArray = when (entry) {
    is ClusterEntry.Person -> {
      val out = ByteBuffer.allocate(FacesLimits.PERSON_FIXED_BYTES + FacesLimits.REF_BYTES * entry.exemplars.size)
      out.put(FacesLimits.CLUSTER_VERSION.toByte()).put(entry.state.wire.toByte()).put(entry.id.bytes())
      out.put(entry.aliasOf?.bytes() ?: ByteArray(PersonId.BYTES)).putLong(entry.bornSeq).putLong(entry.changedSeq).putInt(entry.faces)
      entry.centroid.forEach { out.putShort(it) }
      out.put(entry.exemplars.size.toByte())
      entry.exemplars.forEach { out.put(refBytes(it)) }
      out.array()
    }
    is ClusterEntry.Assignment ->
      ByteBuffer.allocate(FacesLimits.ASSIGNMENT_BYTES).put(FacesLimits.CLUSTER_VERSION.toByte()).put(refBytes(entry.face))
        .put(entry.person.bytes()).putShort(entry.score.toShort()).put(entry.origin.wire.toByte()).array()
  }

  /** Una voce `cluster`: la forma (persona o assegnazione) la dice la lunghezza; la chiave la controlla chi legge il segmento. */
  fun readCluster(b: ByteArray, off: Int, len: Int): ClusterEntry {
    ensure(len >= 1 && u8(b, off) == FacesLimits.CLUSTER_VERSION, RejectReason.INVALID_FIELD, "voce cluster: versione")
    return if (len == FacesLimits.ASSIGNMENT_BYTES) readAssignment(b, off) else readPerson(b, off, len)
  }

  private fun readAssignment(b: ByteArray, off: Int): ClusterEntry.Assignment {
    val ref = readRef(b, off + 1)
    val person = PersonId.fromBytes(b.copyOfRange(off + 43, off + 59))
    val score = ByteBuffer.wrap(b, off + 59, 2).short.toInt()
    val origin = ClusterOrigin.fromWire(u8(b, off + 61)) ?: violation(RejectReason.INVALID_FIELD, "assegnazione: origine sconosciuta")
    return ClusterEntry.Assignment(ref, person, score, origin)
  }

  private fun readPerson(b: ByteArray, off: Int, len: Int): ClusterEntry.Person {
    ensure(len >= FacesLimits.PERSON_FIXED_BYTES, RejectReason.MALFORMED, "persona: troncata")
    val state = ClusterPersonState.fromWire(u8(b, off + 1)) ?: violation(RejectReason.INVALID_FIELD, "persona: stato sconosciuto")
    val id = PersonId.fromBytes(b.copyOfRange(off + 2, off + 18))
    val aliasBytes = b.copyOfRange(off + 18, off + 34)
    val alias = if (aliasBytes.all { it.toInt() == 0 }) null else PersonId.fromBytes(aliasBytes)
    val numbers = ByteBuffer.wrap(b, off + 34, 20)
    val bornSeq = numbers.long
    val changedSeq = numbers.long
    val faces = numbers.int
    val centroid = ShortArray(FacesLimits.DIMENSION) { u16(b, off + 54 + 2 * it).toShort() }
    val count = u8(b, off + FacesLimits.PERSON_FIXED_BYTES - 1)
    ensure(count <= FacesLimits.MAX_EXEMPLARS, RejectReason.TOO_LARGE, "persona: troppi esemplari")
    ensure(len == FacesLimits.PERSON_FIXED_BYTES + FacesLimits.REF_BYTES * count, RejectReason.MALFORMED, "persona: lunghezza")
    val exemplars = (0 until count).map { readRef(b, off + FacesLimits.PERSON_FIXED_BYTES + FacesLimits.REF_BYTES * it) }
    return ClusterEntry.Person(id, state, alias, bornSeq, changedSeq, faces, centroid, exemplars)
  }

  private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xff

  private fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
}
