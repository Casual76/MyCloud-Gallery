package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import java.nio.ByteBuffer
import org.junit.Test

/**
 * I corpi `faces` e le voci `cluster` contro dati ostili (Fase 8, P3, "Fatto quando": corpi, norme, NaN, livelli, riquadri, punti,
 * lunghezze; §2.4 del progetto). Ogni mutazione parte dai byte onesti e cambia un solo campo; il rifiuto ha il suo motivo e non e' mai
 * un'eccezione.
 */
class FacesHostileBodyTest {

  private val fx = FacesFx()

  /** Il corpo onesto: un volto CORE (con vettore), uno WEAK (con vettore), uno NONE (senza). */
  private val honest = FacesDocs.encodeBody(fx.body())

  private fun reason(bytes: ByteArray): RejectReason? = FacesDocs.decodeBody(bytes).rejectionOrNull()

  private fun mutated(at: Int, value: Int): ByteArray = honest.copyOf().also { it[at] = value.toByte() }

  private fun u16(bytes: ByteArray, at: Int, value: Int) = bytes.also { it[at] = (value ushr 8).toByte(); it[at + 1] = value.toByte() }

  /** Gli inizi dei tre volti e i campi dentro un volto (§2.4). */
  private val face0 = 6
  private val face1 = 6 + 35 + 256
  private val face2 = face1 + 35 + 256

  @Test
  fun intestazione() {
    assertThat(reason(honest)).isNull()
    assertThat(reason(mutated(0, 2))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(1, 2))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(1, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(2, 3))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(3, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(4, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(4, 5))).isEqualTo(RejectReason.INVALID_FIELD)
  }

  /** "Lunghezza esatta": un volto in piu' o in meno di quelli dichiarati, byte in piu', corpi troncati, oltre il tetto. */
  @Test
  fun lunghezze() {
    assertThat(reason(mutated(5, 65))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(mutated(5, 2))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(mutated(5, 4))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(honest + 0)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(honest.copyOf(honest.size - 1))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(honest.copyOf(5))).isEqualTo(RejectReason.MALFORMED)
    // I cinque punti troncati: un volto dichiarato con soli 20 byte dopo l'intestazione.
    assertThat(reason(honest.copyOf(face0 + 20).also { it[5] = 1 })).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(ByteArray(TrustLimits.MAX_FACES_BODY_BYTES + 1))).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun fotogrammiERiquadri() {
    assertThat(reason(mutated(face0, 9))).isEqualTo(RejectReason.INVALID_FIELD)
    // Un fotogramma diverso da 0 fuori da un video.
    assertThat(reason(mutated(face0, 1))).isEqualTo(RejectReason.INVALID_FIELD)
    // x0 = x1, y0 > y1.
    assertThat(reason(u16(honest.copyOf(), face0 + 5, 0).also { u16(it, face0 + 1, 0) })).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(u16(honest.copyOf(), face0 + 3, 65_535))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(u16(honest.copyOf(), face1 + 5, 0))).isEqualTo(RejectReason.INVALID_FIELD)
  }

  /** Livelli e motivi (D19): due bit di livello (3 non esiste), motivi noti, un CORE senza motivi, "fotogramma" solo nei video. */
  @Test
  fun livelliEMotivi() {
    assertThat(reason(mutated(face0 + 30, 3))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face0 + 30, 2 or (16 shl 2)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face0 + 30, 2 or (FaceReasons.SMALL shl 2)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face1 + 30, 1 or (FaceReasons.FRAME shl 2)))).isEqualTo(RejectReason.INVALID_FIELD)
    // Un NONE che si dice CORE: mancano i 256 byte del vettore. Un CORE che si dice NONE: il vettore diventa spazzatura.
    assertThat(reason(mutated(face2 + 30, 2))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(mutated(face0 + 30, 0 or (FaceReasons.SMALL shl 2)))).isNotNull()
    // I volti dei fotogrammi di un video sono al piu' WEAK (D18).
    val video = FacesDocs.encodeBody(fx.videoBody())
    assertThat(reason(video)).isNull()
    assertThat(reason(video.copyOf().also { it[face0 + 30] = 2 })).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun latoImbardataEPunteggio() {
    assertThat(reason(u16(honest.copyOf(), face0 + 31, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face0 + 33, 91))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face0 + 33, -91))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(mutated(face0 + 33, 90))).isNull()
    assertThat(reason(mutated(face0 + 29, 255))).isNull()
  }

  /** Vettori (formato §7): componenti finite, norma ricalcolata in float32 fra 0,99 e 1,01. */
  @Test
  fun vettoriENorme() {
    val vector = face0 + 35
    assertThat(reason(u16(honest.copyOf(), vector + 10, 0x7e00))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(u16(honest.copyOf(), vector, 0x7c00))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(u16(honest.copyOf(), vector, 0xfc00))).isEqualTo(RejectReason.INVALID_FIELD)
    for (scale in listOf(0f, 0.5f, 0.98f, 1.02f, 1.5f)) {
      val bytes = honest.copyOf()
      for (i in 0 until FacesLimits.DIMENSION) {
        val at = vector + 2 * i
        val half = ((bytes[at].toInt() and 0xff) shl 8 or (bytes[at + 1].toInt() and 0xff)).toShort()
        u16(bytes, at, Float16.fromFloat(Float16.toFloat(half) * scale).toInt() and 0xffff)
      }
      assertWithMessage("norma x$scale").that(reason(bytes)).isEqualTo(RejectReason.INVALID_FIELD)
    }
  }

  // --- Voci `cluster` ----------------------------------------------------------------------------------------------------------

  private fun clusterReason(bytes: ByteArray, key: ByteArray): RejectReason? =
    FacesFx.rawSegment(listOf(key to bytes), cls = 19).let { (b, ref) -> FacesDocs.decodeSegment(b, ref, SegmentClass.CLUSTER).rejectionOrNull() }

  private val person = fx.personEntry(1, faces = 2, exemplars = listOf(fx.ref(1), fx.ref(2)))
  private val personBytes = FacesDocs.encodeCluster(person)

  private fun personWith(at: Int, value: Int) = clusterReason(personBytes.copyOf().also { it[at] = value.toByte() }, person.key)

  private fun personLong(at: Int, value: Long) = clusterReason(personBytes.copyOf().also { ByteBuffer.wrap(it, at, 8).putLong(value) }, person.key)

  @Test
  fun persone() {
    assertThat(clusterReason(personBytes, person.key)).isNull()
    assertThat(personWith(0, 2)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personWith(1, 0)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personWith(1, 4)).isEqualTo(RejectReason.INVALID_FIELD)
    // Unita senza alias; attiva con un alias; alias di se stessa; id riservato tutto zero.
    assertThat(personWith(1, 2)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personWith(20, 1)).isEqualTo(RejectReason.INVALID_FIELD)
    val self = personBytes.copyOf().also { it[1] = 2; System.arraycopy(it, 2, it, 18, 16) }
    assertThat(clusterReason(self, person.key)).isEqualTo(RejectReason.INVALID_FIELD)
    val zero = personBytes.copyOf().also { java.util.Arrays.fill(it, 2, 18, 0) }
    assertThat(clusterReason(zero, person.key)).isEqualTo(RejectReason.INVALID_FIELD)
    // Numeri dell'istantanea e dei volti.
    assertThat(personLong(34, 5)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personLong(34, -1)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personLong(42, TrustLimits.MAX_VERSION + 1)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(clusterReason(personBytes.copyOf().also { ByteBuffer.wrap(it, 50, 4).putInt(-1) }, person.key)).isEqualTo(RejectReason.INVALID_FIELD)
    // Centroide non finito o non normalizzato.
    assertThat(clusterReason(u16(personBytes.copyOf(), 54, 0x7e00), person.key)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(clusterReason(u16(personBytes.copyOf(), 54, 0x5000), person.key)).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun esemplari() {
    val ref = FacesLimits.PERSON_FIXED_BYTES
    // Due esemplari dichiarati, uno solo presente; 17 esemplari; due uguali; fotogramma o indice fuori intervallo.
    assertThat(clusterReason(personBytes.copyOf(personBytes.size - 42), person.key)).isEqualTo(RejectReason.MALFORMED)
    val many = personBytes.copyOf(ref).also { it[ref - 1] = 17 } + ByteArray(17 * 42) { personBytes[ref + it % 42] }
    assertThat(clusterReason(many, person.key)).isEqualTo(RejectReason.TOO_LARGE)
    val twice = personBytes.copyOf().also { System.arraycopy(it, ref, it, ref + 42, 42) }
    assertThat(clusterReason(twice, person.key)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personWith(ref + 32, 9)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(personWith(ref + 33, 64)).isEqualTo(RejectReason.INVALID_FIELD)
    // La chiave di un'altra persona.
    assertThat(clusterReason(personBytes, fx.personEntry(2).key)).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun assegnazioni() {
    val assignment = fx.assignment(10, 1)
    val bytes = FacesDocs.encodeCluster(assignment)
    fun with(at: Int, value: Int) = clusterReason(bytes.copyOf().also { it[at] = value.toByte() }, assignment.key)
    assertThat(clusterReason(bytes, assignment.key)).isNull()
    assertThat(with(0, 2)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(with(33, 9)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(with(34, 64)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(clusterReason(bytes.copyOf().also { java.util.Arrays.fill(it, 43, 59, 0) }, assignment.key)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(clusterReason(bytes.copyOf().also { ByteBuffer.wrap(it, 59, 2).putShort(10_001) }, assignment.key)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(clusterReason(bytes.copyOf().also { ByteBuffer.wrap(it, 59, 2).putShort(-10_001) }, assignment.key)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(with(61, 0)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(with(61, 4)).isEqualTo(RejectReason.INVALID_FIELD)
    // Un byte in piu' (63 non e' ne' un'assegnazione ne' una persona) e la chiave di un altro volto.
    assertThat(clusterReason(bytes + 0, assignment.key)).isEqualTo(RejectReason.MALFORMED)
    assertThat(clusterReason(bytes, fx.assignment(11, 1).key)).isEqualTo(RejectReason.INVALID_FIELD)
  }

  /** Le regole fra voci di un'istantanea (D21): una per persona e per volto, persone e alias che esistono, conti coerenti, tetti. */
  @Test
  fun istantaneaIntera() {
    val mark = SnapshotMark(3, 2)
    fun check(entries: List<ClusterEntry>, m: SnapshotMark = mark) = FacesDocs.checkSnapshot(m, entries).rejectionOrNull()
    val good = fx.snapshotEntries()
    assertThat(check(good)).isNull()
    assertThat(check(good + fx.assignment(20, 9))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(good.filterNot { it is ClusterEntry.Person && it.id == fx.person(1) })).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(good + fx.assignment(21, 2))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(good, SnapshotMark(1, 0))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(good + good.first())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(good + fx.assignment(10, 2))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(check(emptyList())).isEqualTo(RejectReason.INVALID_FIELD)
    val persons = (1..TrustLimits.MAX_SNAPSHOT_PERSONS + 1).map { fx.personEntry(it) }
    assertThat(check(persons)).isEqualTo(RejectReason.TOO_LARGE)
    val assignments = (0..TrustLimits.MAX_SNAPSHOT_ASSIGNMENTS).map { ClusterEntry.Assignment(fx.ref(it % 1000, it / 1000 % 64), fx.person(1), 1, ClusterOrigin.AUTO) }
    assertThat(check(listOf(fx.personEntry(1)) + assignments)).isEqualTo(RejectReason.TOO_LARGE)
  }
}
