package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.ensure

// I CORPI BINARI DEI DATI DEI VOLTI (Fase 8, P3; docs/fase8-progetto.md §2.4, D12, D19, D21; docs/formato-nas.md §5.5-bis "Dati dei volti").
// Due classi hanno corpi binari: `faces` (18, rilevamenti e vettori di un contenuto) e `cluster` (19, le voci dell'istantanea del custode).
// Le forme sono controllate dal costruttore di ogni tipo (`ensure`: un valore impossibile e' un rifiuto, mai un'eccezione verso fuori, e chi
// scrive passa dagli stessi controlli di chi legge). Big endian come il resto del formato. Nessun valore nei `toString()` (D37).

/** Le costanti dei corpi dei volti (§2.4; i tetti generali sono in `TrustLimits`, formato §7). */
object FacesLimits {
  /** La versione dei corpi `faces` e delle voci `cluster` di questa build (byte 0): un'altra si rifiuta. */
  const val BODY_VERSION: Int = 1
  const val CLUSTER_VERSION: Int = 1

  /** Intestazione del corpo `faces`: versione, spazio, rilevatore, ricetta, sorgente, N. */
  const val BODY_HEADER_BYTES: Int = 6

  /** Un volto senza vettore: fotogramma 1, riquadro 8, punti 20, punteggio 1, livello e motivi 1, lato 2, imbardata 1, nitidezza 1. */
  const val FACE_BYTES: Int = 35

  /** I cinque punti: 10 valori da 2 byte. */
  const val LANDMARK_VALUES: Int = 10

  /** La dimensione dei vettori di questa build (sface-2021dec-v1) e i loro byte in float16. */
  val DIMENSION: Int = FaceSpaceCode.SFACE_2021DEC_V1.dimension
  const val VECTOR_BYTES: Int = 256

  /** Il corpo piu' grande possibile: 6 + 64 volti da 291 byte = 18.630 (sotto `MAX_FACES_BODY_BYTES`). */
  const val MAX_BODY_BYTES: Int = BODY_HEADER_BYTES + 64 * (FACE_BYTES + VECTOR_BYTES)

  /** Un riferimento a un volto: chiave di contenuto 32, fotogramma 1, indice 1, prime 8 byte dell'impronta del corpo. */
  const val REF_BYTES: Int = 42

  /** Una persona senza esemplari: versione, stato, id, alias, `bornSeq`, `changedSeq`, volti, centroide, N. */
  const val PERSON_FIXED_BYTES: Int = 1 + 1 + 16 + 16 + 8 + 8 + 4 + VECTOR_BYTES + 1

  /** Un'assegnazione: versione, riferimento, persona, punteggio, origine. */
  const val ASSIGNMENT_BYTES: Int = 1 + REF_BYTES + 16 + 2 + 1

  /** Esemplari per persona (D20). */
  const val MAX_EXEMPLARS: Int = 16

  /** La norma ricalcolata in float32 di un vettore (e di un centroide non nullo). */
  const val MIN_NORM: Float = 0.99f
  const val MAX_NORM: Float = 1.01f

  /** Il punteggio di un'assegnazione: coseno x 10.000, con segno. */
  const val MAX_SCORE: Int = 10_000
}

/** I controlli sui vettori float16 (gli stessi del codec `vec` della Fase 6): componenti finite, norma vicina a 1. */
internal object FacesVectors {
  fun finite(half: Short): Boolean = (half.toInt() and 0x7c00) != 0x7c00

  /** Un vettore di un volto: [FacesLimits.DIMENSION] componenti finite, norma in [MIN_NORM, MAX_NORM]. */
  fun check(halves: ShortArray) {
    ensure(halves.size == FacesLimits.DIMENSION, RejectReason.INVALID_FIELD, "volti: dimensione del vettore")
    ensure(halves.all(::finite), RejectReason.INVALID_FIELD, "volti: componente non finita")
    val norm = Float16.norm(halves)
    ensure(norm >= FacesLimits.MIN_NORM && norm <= FacesLimits.MAX_NORM, RejectReason.INVALID_FIELD, "volti: norma fuori da 0,99..1,01")
  }

  /** Un centroide: tutto zero (nessun volto con vettore) oppure come un vettore. */
  fun checkCentroid(halves: ShortArray) {
    ensure(halves.size == FacesLimits.DIMENSION, RejectReason.INVALID_FIELD, "volti: dimensione del centroide")
    ensure(halves.all(::finite), RejectReason.INVALID_FIELD, "volti: componente non finita del centroide")
    if (halves.all { Float16.toFloat(it) == 0f }) return
    val norm = Float16.norm(halves)
    ensure(norm >= FacesLimits.MIN_NORM && norm <= FacesLimits.MAX_NORM, RejectReason.INVALID_FIELD, "volti: norma del centroide")
  }
}

/**
 * Un volto di un corpo `faces` (§2.4): fotogramma, riquadro e cinque punti (in 1/65535 dell'immagine raddrizzata; i punti sono `Short` letti
 * senza segno), punteggio del rilevatore (0..255), livello e motivi, lato corto in pixel della sorgente dell'allineamento, imbardata, nitidezza
 * e il vettore in float16 (solo CORE e WEAK). Un volto CORE non ha motivi (i motivi dicono perche' un volto **non** e' CORE, D19).
 */
class FaceRecord(
  val frame: Int,
  val box: FaceBox,
  landmarks: ShortArray,
  val score: Int,
  val tier: FaceTier,
  val reasons: Int,
  val sidePx: Int,
  val yaw: Int,
  val sharpness: Int,
  vector: ShortArray?,
) {
  private val points: ShortArray = landmarks.copyOf()
  private val halves: ShortArray? = vector?.copyOf()

  init {
    ensure(frame in 0..FaceLocator.MAX_FRAME, RejectReason.INVALID_FIELD, "volto: fotogramma fuori intervallo")
    ensure(points.size == FacesLimits.LANDMARK_VALUES, RejectReason.MALFORMED, "volto: cinque punti")
    ensure(score in 0..255 && sharpness in 0..255, RejectReason.INVALID_FIELD, "volto: punteggio o nitidezza")
    ensure(FaceReasons.isValid(reasons), RejectReason.INVALID_FIELD, "volto: motivi sconosciuti")
    ensure(tier != FaceTier.CORE || reasons == 0, RejectReason.INVALID_FIELD, "volto: un volto CORE non ha motivi")
    ensure(sidePx in 1..65_535, RejectReason.INVALID_FIELD, "volto: lato corto")
    ensure(yaw in -90..90, RejectReason.INVALID_FIELD, "volto: imbardata")
    ensure((halves != null) == tier.hasVector, RejectReason.INVALID_FIELD, "volto: vettore solo per CORE e WEAK")
    halves?.let(FacesVectors::check)
  }

  val landmarks: ShortArray get() = points.copyOf()
  val vector: ShortArray? get() = halves?.copyOf()

  override fun equals(other: Any?): Boolean =
    other is FaceRecord && other.frame == frame && other.box == box && other.points.contentEquals(points) && other.score == score &&
      other.tier == tier && other.reasons == reasons && other.sidePx == sidePx && other.yaw == yaw && other.sharpness == sharpness &&
      java.util.Arrays.equals(other.halves, halves)

  override fun hashCode(): Int = ((box.hashCode() * 31 + frame) * 31 + points.contentHashCode()) * 31 + (halves?.contentHashCode() ?: 0)

  override fun toString(): String = "FaceRecord(${tier.code})"
}

/**
 * Il corpo `faces` di un contenuto (§2.4): spazio dei vettori, rilevatore, ricetta (1..255), sorgente e da 0 a 64 volti (0 = "analizzato,
 * nessun volto"). Solo i fotogrammi di un video hanno un indice di fotogramma e il motivo "fotogramma", e i loro volti sono al piu' WEAK (D18).
 */
class FacesBody(
  val space: FaceSpaceCode,
  val detector: FaceDetectorCode,
  val recipe: Int,
  val source: FaceSource,
  val faces: List<FaceRecord>,
) {
  init {
    ensure(recipe in 1..255, RejectReason.INVALID_FIELD, "corpo faces: ricetta")
    ensure(faces.size <= TrustLimits.MAX_FACES_PER_BODY, RejectReason.TOO_LARGE, "corpo faces: troppi volti")
    if (source == FaceSource.FRAMES) {
      ensure(faces.none { it.tier == FaceTier.CORE }, RejectReason.INVALID_FIELD, "corpo faces: i volti dei fotogrammi sono al piu' WEAK")
    } else {
      ensure(faces.all { it.frame == 0 }, RejectReason.INVALID_FIELD, "corpo faces: fotogramma in un corpo che non e' di un video")
      ensure(faces.all { it.reasons and FaceReasons.FRAME == 0 }, RejectReason.INVALID_FIELD, "corpo faces: motivo fotogramma fuori da un video")
    }
  }

  override fun equals(other: Any?): Boolean =
    other is FacesBody && other.space == space && other.detector == detector && other.recipe == recipe && other.source == source &&
      other.faces == faces

  override fun hashCode(): Int = ((space.hashCode() * 31 + detector.hashCode()) * 31 + recipe) * 31 + faces.hashCode()

  override fun toString(): String = "FacesBody(${faces.size} volti, r$recipe, ${source.code})"
}

/** Lo stato di una persona nell'istantanea (§2.4, byte "stato"). */
enum class ClusterPersonState(val wire: Int) {
  ACTIVE(1),
  MERGED(2),
  FORGOTTEN(3),
  ;

  companion object {
    fun fromWire(wire: Int): ClusterPersonState? = entries.firstOrNull { it.wire == wire }
  }
}

/** L'origine di un'assegnazione (§2.4, byte "origine"): automatica, dell'utente ("E' …"), ignorata. */
enum class ClusterOrigin(val wire: Int) {
  AUTO(1),
  USER(2),
  IGNORED(3),
  ;

  companion object {
    fun fromWire(wire: Int): ClusterOrigin? = entries.firstOrNull { it.wire == wire }
  }
}

/** Le chiavi delle voci `cluster` (§2.4): impronte con dominio, mai un dato in chiaro. */
object ClusterKeys {
  private val PERSON = "mcg-face-person".toByteArray(Charsets.US_ASCII) + 0
  private val ASSIGN = "mcg-face-assign".toByteArray(Charsets.US_ASCII) + 0

  fun person(id: PersonId): ByteArray = Hashes.sha256(PERSON, id.bytes())

  fun assignment(face: FaceRef): ByteArray = Hashes.sha256(ASSIGN, FacesBinary.refBytes(face))
}

/** L'id tutto zero e' riservato: nel corpo di una persona vuol dire "nessun alias" (§2.4). */
internal fun PersonId.isZero(): Boolean = bytes().all { it.toInt() == 0 }
