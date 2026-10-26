package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode

// IL RAGGRUPPAMENTO INCREMENTALE E STABILE (docs/fase8-progetto.md §1.2 "Raggruppamento", §2.5, D20-D23, E-F1, E-F2, E-F15, E-F20-E-F27).
// Contratti di P0. Il nucleo e' **puro e deterministico** ([GroupingEngine]: stessi ingressi, stessi gruppi e stessi id su ogni macchina,
// ogni spareggio per id): l'esecutore di riferimento a forza bruta e' `testing/ReferenceGrouping` (P0), quello vero e veloce e il lavoro
// con il custode ([FaceGrouping]) sono di P5. La parita' del §5.2: su 500 storie sintetiche stessi gruppi del riferimento, salvo i casi a
// +-1e-4 dalle soglie, e stessi id dove il riferimento li conserva. Nessun attributo della persona, mai vettori o id nei log (D37).

/** Da dove viene un'assegnazione (§2.4, byte "origine"): automatica, dell'utente ("E' …", fissa), ignorata (una persona dimenticata). */
enum class AssignOrigin(val wire: Int, override val code: String) : StableCode {
  AUTO(1, "auto"),
  USER(2, "user"),
  IGNORED(3, "ignored"),
  ;

  companion object {
    fun fromWire(wire: Int): AssignOrigin? = entries.firstOrNull { it.wire == wire }
  }
}

/** Lo stato di una persona nell'istantanea (§2.4, byte "stato"): attiva, unita in un'altra (alias, D22), dimenticata (D24). */
enum class PersonState(val wire: Int, override val code: String) : StableCode {
  ACTIVE(1, "active"),
  MERGED(2, "merged"),
  FORGOTTEN(3, "forgotten"),
  ;

  companion object {
    fun fromWire(wire: Int): PersonState? = entries.firstOrNull { it.wire == wire }
  }
}

/** "La stessa foto" per i vincoli (D20, E-F15): stesso contenuto e stesso fotogramma (due fotogrammi di un video non sono la stessa foto). */
data class PhotoKey(val contentKey: ContentKey, val frame: Int) {
  override fun toString(): String = "PhotoKey"
}

/**
 * Un volto per il raggruppamento (§2.5): riferimento, posto, livello (solo CORE e WEAK: un NONE non ha vettore) e vettore normalizzato in
 * float32 nello spazio corrente. [cosine] e [dot] non copiano il vettore (il riferimento ne fa milioni).
 */
class GroupFace(val ref: FaceRef, val locator: FaceLocator, val tier: FaceTier, vector: FloatArray) {
  private val v: FloatArray = vector.copyOf()

  init {
    require(tier != FaceTier.NONE) { "Un volto senza vettore non si raggruppa." }
    require(locator.contentKey == ref.contentKey && locator.frame == ref.frame) { "Posto e riferimento di volti diversi." }
    require(v.size == FaceSpaceCode.CURRENT.dimension && v.all { it.isFinite() }) { "Vettore non valido." }
  }

  val photo: PhotoKey get() = PhotoKey(ref.contentKey, ref.frame)

  fun vector(): FloatArray = v.copyOf()

  fun dot(other: FloatArray): Float {
    var s = 0f
    for (i in v.indices) s += v[i] * other[i]
    return s
  }

  /** Il coseno con [other] (i vettori sono normalizzati: il prodotto scalare). */
  fun cosine(other: GroupFace): Float = dot(other.v)

  override fun toString(): String = "GroupFace(${tier.code})"
}

/** Una persona dell'istantanea (§2.4 "Persona"): [aliasOf] solo per [PersonState.MERGED]; al piu' [GroupingParams.MAX_EXEMPLARS] esemplari. */
data class GroupPerson(
  val id: PersonId,
  val state: PersonState,
  val aliasOf: PersonId?,
  val bornSeq: Long,
  val changedSeq: Long,
  val exemplars: List<FaceRef>,
) {
  init {
    require((state == PersonState.MERGED) == (aliasOf != null)) { "Un alias solo per una persona unita." }
    require(aliasOf != id) { "Una persona non e' alias di se stessa." }
    require(exemplars.size <= GroupingParams.MAX_EXEMPLARS && exemplars.toSet().size == exemplars.size) { "Esemplari non validi." }
  }

  override fun toString(): String = "GroupPerson(${state.code}, ${exemplars.size} esemplari)"
}

/** Un'assegnazione dell'istantanea (§2.4 "Assegnazione"): il volto (con il suo posto), la persona, il punteggio (coseno) e l'origine. */
data class GroupAssignment(val face: FaceRef, val locator: FaceLocator, val person: PersonId, val score: Float, val origin: AssignOrigin) {
  override fun toString(): String = "GroupAssignment(${origin.code})"
}

/** Una coppia di persone senza ordine (per "Separati" e le proposte). */
class PersonPair private constructor(val first: PersonId, val second: PersonId) {
  override fun equals(other: Any?): Boolean = other is PersonPair && other.first == first && other.second == second

  override fun hashCode(): Int = first.hashCode() * 31 + second.hashCode()

  override fun toString(): String = "PersonPair"

  companion object {
    fun of(a: PersonId, b: PersonId): PersonPair {
      require(a != b) { "Una coppia e' di due persone diverse." }
      return if (a < b) PersonPair(a, b) else PersonPair(b, a)
    }
  }
}

/**
 * Lo stato del raggruppamento (l'istantanea, D21): numero [seq] e [base] (la `seq` da cui e' partita), persone, assegnazioni e quanti volti
 * CORE erano non assegnati all'ultima nascita ([unassignedAtBirths], per la regola "50 in piu'"). Immutabile.
 */
class GroupingState(
  val seq: Long,
  val base: Long,
  val persons: Map<PersonId, GroupPerson>,
  val assignments: Map<FaceRef, GroupAssignment>,
  val unassignedAtBirths: Int = 0,
) {
  init {
    require(seq >= 0 && base in 0..seq) { "Numeri dell'istantanea non validi." }
    require(assignments.values.all { it.person in persons }) { "Un'assegnazione a una persona che non c'e'." }
  }

  /**
   * La persona a cui porta [id] seguendo gli alias (§2.5 "Alias"): una catena si risolve fino in fondo; un ciclo (due unioni contrarie da
   * due dispositivi) ha come radice l'id minore del ciclo. Null = sconosciuta o dimenticata.
   */
  fun resolve(id: PersonId): PersonId? {
    val seen = LinkedHashSet<PersonId>()
    var current = id
    while (true) {
      val person = persons[current] ?: return null
      if (person.state != PersonState.MERGED) return if (person.state == PersonState.FORGOTTEN) null else current
      if (!seen.add(current)) {
        val cycle = seen.toList().dropWhile { it != current }
        return cycle.minOrNull()
      }
      current = person.aliasOf!!
    }
  }

  override fun toString(): String = "GroupingState(seq=$seq, ${persons.size} persone, ${assignments.size} assegnazioni)"

  companion object {
    val EMPTY = GroupingState(0, 0, emptyMap(), emptyMap())
  }
}

/**
 * Le correzioni dell'utente come vincoli permanenti (D23, dalle voci `people`): parlano di **posti** ([FaceLocator]: un volto corrisponde
 * con IoU >= 0,5), non di indici, cosi' sopravvivono a un ricalcolo. [fixed] = "E' …" (mai espulso, mai riassegnato); [rejected] = "Non e'
 * …" (mai piu' in quelle persone); [ignored] = "Ignora questo volto" (fuori da tutto); [apart] = "Separati" (mai unite in automatico);
 * [forgotten] = "Dimentica" (la persona sparisce, i suoi volti restano ignorati); [aliases] = "Unisci" dell'utente; [born] = persone nate da
 * "Dividi" (esistono anche senza nascita del custode); [named] = persone con un nome (due nomi non si uniscono da soli).
 */
data class GroupingCorrections(
  val fixed: Map<FaceLocator, PersonId> = emptyMap(),
  val rejected: Map<FaceLocator, Set<PersonId>> = emptyMap(),
  val ignored: Set<FaceLocator> = emptySet(),
  val apart: Set<PersonPair> = emptySet(),
  val forgotten: Set<PersonId> = emptySet(),
  val aliases: Map<PersonId, PersonId> = emptyMap(),
  val born: Set<PersonId> = emptySet(),
  val named: Set<PersonId> = emptySet(),
) {
  override fun toString(): String = "GroupingCorrections(${fixed.size}, ${rejected.size}, ${ignored.size}, ${apart.size}, ${forgotten.size})"

  companion object {
    val NONE = GroupingCorrections()
  }
}

/**
 * I parametri del raggruppamento (D20, §2.5): valori **di partenza**; P11 li calibra (sul set pubblico e, quando l'utente fara' la misura
 * di F105, sui dati veri, risposta Q4) e li scrive con i numeri in `docs/decisioni.md`.
 */
data class GroupingParams(
  val tJoin: Float = T_JOIN,
  val tJoinWeak: Float = T_JOIN_WEAK,
  val margin: Float = MARGIN,
  val tEdge: Float = T_EDGE,
  val tBirth: Float = T_BIRTH,
  val tMerge: Float = T_MERGE,
  val tSuggest: Float = T_SUGGEST,
  val tEject: Float = T_EJECT,
  val minFaces: Int = MIN_FACES,
  val minPhotos: Int = MIN_PHOTOS,
  val maxExemplars: Int = MAX_EXEMPLARS,
  val birthsEvery: Int = BIRTHS_EVERY,
) {
  init {
    require(maxExemplars in 1..MAX_EXEMPLARS && minFaces >= 2 && minPhotos >= 1 && birthsEvery >= 1) { "Parametri non validi." }
  }

  companion object {
    const val T_JOIN: Float = 0.45f
    const val T_JOIN_WEAK: Float = 0.55f
    const val MARGIN: Float = 0.06f
    const val T_EDGE: Float = 0.45f
    const val T_BIRTH: Float = 0.48f
    const val T_MERGE: Float = 0.52f
    const val T_SUGGEST: Float = 0.40f
    const val T_EJECT: Float = 0.32f
    const val MIN_FACES: Int = 3
    const val MIN_PHOTOS: Int = 2
    const val MAX_EXEMPLARS: Int = 16

    /** Le nascite a fine unita' partono quando i non assegnati CORE sono almeno 50 in piu' dell'ultima volta (§2.5). */
    const val BIRTHS_EVERY: Int = 50

    /** Quanti esemplari piu' simili fanno la somiglianza di un volto con una persona (§2.5: "media dei 3"). */
    const val TOP_EXEMPLARS: Int = 3

    /** D22: il ricalcolo completo eredita l'id di un gruppo vecchio solo con almeno il 30% dei volti in comune. */
    const val INHERIT_SHARE: Float = 0.30f

    val DEFAULT = GroupingParams()
  }
}
