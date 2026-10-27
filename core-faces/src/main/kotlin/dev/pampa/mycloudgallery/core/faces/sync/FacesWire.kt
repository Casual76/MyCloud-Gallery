package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.ClusterOrigin
import dev.pampa.mycloudgallery.core.trust.data.ClusterPersonState
import dev.pampa.mycloudgallery.core.trust.data.FaceRecord
import dev.pampa.mycloudgallery.core.trust.data.FacesBody
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesLimits
import dev.pampa.mycloudgallery.core.trust.data.FacesSnapshotDoc
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.PeopleForms
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import kotlin.math.roundToInt
import kotlin.math.sqrt

// IL PONTE FRA I CONTRATTI DI core-faces E IL FORMATO DEI FILE `faces/` (Fase 8, P3; docs/fase8-progetto.md §2.4). Il codec e' `FacesDocs`
// di core-trust (che non vede core-faces); qui le conversioni nei due sensi: corpi (`FaceBody` ↔ `FacesBody`), voci `people`
// (`PeopleRecord` ↔ `PeopleDoc`), istantanee (`GroupingState` ↔ voci `cluster`). Chi scrive usa le regole di chi legge: una conversione che
// il formato rifiuterebbe restituisce null e non si pubblica (si conta). Mai un valore nei `toString()` (D37).

/** Un'istantanea letta dal NAS e risolta sui corpi vincenti di questo dispositivo (D21): cosa usa il raggruppamento (P5). */
class RemoteSnapshot(
  /** Il dispositivo dell'account che l'ha pubblicata (il custode). */
  val device: String,
  val mark: SnapshotMark,
  /** Persone e assegnazioni; un'assegnazione il cui riferimento non torna con il corpo vincente qui manca (il volto e' "nuovo"). */
  val state: GroupingState,
  /** I centroidi pubblicati dal custode (normalizzati; assenti per le persone senza volti con vettore). */
  val centroids: Map<PersonId, FloatArray>,
  /** Quante assegnazioni non si sono potute risolvere sui corpi di qui (corpo vincente diverso o non ancora arrivato). */
  val unresolved: Int,
) {
  override fun toString(): String = "RemoteSnapshot(seq=${mark.seq}, ${state.persons.size} persone, irrisolte=$unresolved)"
}

object FacesWire {

  // --- Corpi -------------------------------------------------------------------------------------------------------------------

  /** Il corpo del formato di [body] (lancia `TrustViolation` se il formato non lo ammette). */
  fun doc(body: FaceBody): FacesBody = FacesBody(
    body.space,
    body.detector,
    body.recipe,
    body.source,
    body.faces.map { FaceRecord(it.frame, it.box, it.landmarks, it.score, it.tier, it.reasons, it.sidePx, it.yaw, it.sharpness, it.vector) },
  )

  /** Il corpo del contratto letto dal formato: autore = il dispositivo che firma la testa, impronta = quella dei byte letti. */
  fun body(contentKey: ContentKey, author: String, doc: FacesBody, digest: Long): FaceBody = FaceBody(
    contentKey,
    doc.space,
    doc.detector,
    doc.recipe,
    doc.source,
    author,
    doc.faces.mapIndexed { i, r -> FaceEntry(i, r.frame, r.box, r.landmarks, r.score, r.tier, r.reasons, r.sidePx, r.yaw, r.sharpness, r.vector) },
    digest,
  )

  /**
   * Il corpo di [contentKey] calcolato da [author] con l'impronta giusta (`FaceRef.bodyDigest`: le prime 8 byte dello SHA-256 dei byte del
   * formato), oppure null se il formato non lo ammetterebbe (vettore non finito o non normalizzato, un CORE con motivi, un fotogramma fuori
   * da un video...). Chi calcola (P4) costruisce i corpi da salvare con questo: cosi' le istantanee che li citano tornano su ogni dispositivo.
   */
  fun build(
    contentKey: ContentKey,
    space: FaceSpaceCode,
    detector: FaceDetectorCode,
    recipe: Int,
    source: FaceSource,
    author: String,
    faces: List<FaceEntry>,
  ): FaceBody? {
    val probe = try {
      FaceBody(contentKey, space, detector, recipe, source, author, faces, 0L)
    } catch (e: IllegalArgumentException) {
      return null
    }
    val bytes = encodedOrNull(probe) ?: return null
    return FaceBody(contentKey, space, detector, recipe, source, author, faces, FacesDocs.bodyDigest(bytes))
  }

  /** I byte del formato di [body], o null se il formato non lo ammette. */
  fun encodedOrNull(body: FaceBody): ByteArray? = try {
    FacesDocs.encodeBody(doc(body))
  } catch (e: TrustViolation) {
    null
  } catch (e: IllegalArgumentException) {
    null
  }

  /** Vero se [body] si pubblica com'e': il formato lo ammette e la sua impronta e' quella dei suoi byte (riferimenti coerenti ovunque). */
  fun admits(body: FaceBody): Boolean = encodedOrNull(body)?.let { FacesDocs.bodyDigest(it) == body.digest } ?: false

  // --- Voci `people` -----------------------------------------------------------------------------------------------------------

  /** La voce del formato di [record] con l'ora [at], o null se il formato non la ammette (non si pubblica: si conta). */
  fun doc(record: PeopleRecord, at: Long): PeopleDoc? = try {
    PeopleDoc(record.edit.subject, record.edit.field, record.edit.value, at, record.author)
  } catch (e: TrustViolation) {
    null
  }

  /** La voce del contratto di una voce letta (la forma l'ha gia' controllata il codec). */
  fun record(doc: PeopleDoc): PeopleRecord = PeopleRecord(edit(doc.subject, doc.field, doc.value), doc.at, doc.author)

  /** La modifica di (soggetto, campo, valore) in una forma ammessa da `PeopleForms.fits`. */
  fun edit(subject: String, field: String, value: String?): PeopleEdit {
    require(PeopleForms.fits(subject, field, value)) { "Voce people d'altra forma." }
    return when {
      field == PeopleForms.NAME -> PeopleEdit.Name(person(subject), value)
      field == PeopleForms.HIDDEN -> PeopleEdit.Hidden(person(subject), value == PeopleForms.TRUE)
      field == PeopleForms.FORGET -> PeopleEdit.Forget(person(subject))
      field == PeopleForms.ALIAS -> PeopleEdit.Alias(person(subject), person(value!!))
      field == PeopleForms.BORN -> PeopleEdit.Born(person(subject), person(value!!.removePrefix(PeopleForms.SPLIT_PREFIX)))
      field == PeopleForms.COVER -> PeopleEdit.Cover(person(subject), value?.let(::locator))
      field.startsWith(PeopleForms.APART_PREFIX) -> PeopleEdit.Apart(person(subject), person(field.removePrefix(PeopleForms.APART_PREFIX)), value != null)
      field == PeopleForms.IS -> PeopleEdit.Is(locator(subject), value?.let(::person))
      field.startsWith(PeopleForms.NOT_PREFIX) -> PeopleEdit.Not(locator(subject), person(field.removePrefix(PeopleForms.NOT_PREFIX)), value != null)
      else -> PeopleEdit.Ignore(locator(subject), value != null)
    }
  }

  private fun person(text: String): PersonId = requireNotNull(PersonId.fromBase32(text)) { "Persona non canonica." }

  private fun locator(text: String): FaceLocator = requireNotNull(FaceLocator.parse(text)) { "Localizzatore non canonico." }

  // --- Istantanee ---------------------------------------------------------------------------------------------------------------

  /**
   * Le voci `cluster` dell'istantanea [state] (solo il custode, D21): una persona per persona dell'istantanea con il numero delle sue
   * assegnazioni non ignorate e il centroide (media normalizzata dei vettori dei suoi volti fra [faces], i volti vincenti di qui; tutto zero se
   * nessuno ha un vettore), un'assegnazione per volto con il punteggio x 10.000. Null se il formato non ammetterebbe l'istantanea (numeri
   * incoerenti, una persona cambiata dopo l'istantanea...): il pubblicatore non la pubblica e lo conta.
   */
  fun snapshotEntries(state: GroupingState, faces: Collection<GroupFace>): List<ClusterEntry>? {
    if (state.seq < 1 || state.persons.isEmpty()) return null
    val mark = try {
      SnapshotMark(state.seq, state.base)
    } catch (e: TrustViolation) {
      return null
    }
    val vectors = faces.associateBy({ it.ref }, { it })
    val counted = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.groupBy { it.person }
    val entries = try {
      state.persons.values.sortedBy { it.id }.map { person ->
        val assigned = counted[person.id].orEmpty()
        ClusterEntry.Person(
          person.id,
          personState(person.state),
          person.aliasOf,
          person.bornSeq,
          person.changedSeq,
          assigned.size,
          centroid(assigned.mapNotNull { vectors[it.face] }),
          person.exemplars,
        )
      } + state.assignments.values.sortedBy { it.face }.map { a ->
        val score = (a.score * FacesLimits.MAX_SCORE).roundToInt().coerceIn(-FacesLimits.MAX_SCORE, FacesLimits.MAX_SCORE)
        ClusterEntry.Assignment(a.face, a.person, score, origin(a.origin))
      }
    } catch (e: TrustViolation) {
      return null
    }
    return entries.takeIf { FacesDocs.checkSnapshot(mark, it) is Verdict.Accepted }
  }

  /**
   * L'istantanea [doc] pubblicata da [device], risolta sui corpi vincenti di qui ([winners], per chiave di contenuto): un'assegnazione vale
   * se il corpo vincente ha la stessa impronta del riferimento e un volto a quell'indice e in quel fotogramma (il suo posto e' quello del
   * corpo); altrimenti il volto e' "nuovo" per il custode e manca (§2.4 "Fusione dei rilevamenti").
   */
  fun resolve(device: String, doc: FacesSnapshotDoc, winners: Map<ContentKey, FaceBody>): RemoteSnapshot {
    val persons = doc.persons.associate { p ->
      p.id to GroupPerson(p.id, personState(p.state), p.aliasOf, p.bornSeq, p.changedSeq, p.exemplars)
    }
    val centroids = doc.persons.mapNotNull { p ->
      val values = Float16.decode(p.centroid)
      if (values.all { it == 0f }) null else p.id to values
    }.toMap()
    var unresolved = 0
    val assignments = LinkedHashMap<dev.pampa.mycloudgallery.core.model.FaceRef, GroupAssignment>()
    for (a in doc.assignments) {
      val body = winners[a.face.contentKey]
      val entry = body?.takeIf { it.digest == a.face.bodyDigest }?.faces?.getOrNull(a.face.index)?.takeIf { it.frame == a.face.frame }
      if (entry == null) {
        unresolved++
        continue
      }
      val locator = FaceLocator(a.face.contentKey, a.face.frame, entry.box)
      assignments[a.face] = GroupAssignment(a.face, locator, a.person, a.score / FacesLimits.MAX_SCORE.toFloat(), origin(a.origin))
    }
    return RemoteSnapshot(device, doc.mark, GroupingState(doc.mark.seq, doc.mark.base, persons, assignments), centroids, unresolved)
  }

  private fun centroid(faces: List<GroupFace>): ShortArray {
    val zero = ShortArray(FacesLimits.DIMENSION)
    if (faces.isEmpty()) return zero
    val sum = FloatArray(FacesLimits.DIMENSION)
    for (face in faces) {
      val v = face.vector()
      for (i in sum.indices) sum[i] += v[i]
    }
    val norm = sqrt(sum.fold(0.0) { acc, x -> acc + x.toDouble() * x }).toFloat()
    if (!norm.isFinite() || norm < MIN_CENTROID_NORM) return zero
    val halves = Float16.encode(FloatArray(sum.size) { sum[it] / norm })
    val back = Float16.norm(halves)
    return if (back >= FacesLimits.MIN_NORM && back <= FacesLimits.MAX_NORM) halves else zero
  }

  private fun personState(state: PersonState): ClusterPersonState = when (state) {
    PersonState.ACTIVE -> ClusterPersonState.ACTIVE
    PersonState.MERGED -> ClusterPersonState.MERGED
    PersonState.FORGOTTEN -> ClusterPersonState.FORGOTTEN
  }

  private fun personState(state: ClusterPersonState): PersonState = when (state) {
    ClusterPersonState.ACTIVE -> PersonState.ACTIVE
    ClusterPersonState.MERGED -> PersonState.MERGED
    ClusterPersonState.FORGOTTEN -> PersonState.FORGOTTEN
  }

  private fun origin(origin: AssignOrigin): ClusterOrigin = when (origin) {
    AssignOrigin.AUTO -> ClusterOrigin.AUTO
    AssignOrigin.USER -> ClusterOrigin.USER
    AssignOrigin.IGNORED -> ClusterOrigin.IGNORED
  }

  private fun origin(origin: ClusterOrigin): AssignOrigin = when (origin) {
    ClusterOrigin.AUTO -> AssignOrigin.AUTO
    ClusterOrigin.USER -> AssignOrigin.USER
    ClusterOrigin.IGNORED -> AssignOrigin.IGNORED
  }

  /** Sotto questa norma la media dei vettori e' quasi nulla (volti opposti): nessun centroide, piuttosto che un rumore normalizzato. */
  private const val MIN_CENTROID_NORM: Float = 1e-3f
}
