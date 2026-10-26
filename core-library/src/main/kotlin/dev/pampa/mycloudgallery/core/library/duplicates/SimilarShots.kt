package dev.pampa.mycloudgallery.core.library.duplicates

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.abs
import kotlin.math.max

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1; E-U3): i DOPPIONI SIMILI, parte pura. Scatti **diversi** ma quasi uguali, dello stesso giorno e
// a meno di 10 minuti l'uno dall'altro: la raffica a mano, tre foto dello stesso piatto. Non sono le copie simili della Fase 5 (lo stesso
// scatto con byte diversi, che sono gia' una cella sola): queste sono foto distinte e l'utente sceglie quale tenere.
//
// Qui non c'e' ne' catalogo ne' Android: arrivano scatti con ora, proporzione e un giudizio sulla somiglianza fra due scatti
// ([ShotAffinity]), escono i gruppi. Il giudizio lo danno i vettori della Fase 6 (coseno ≥ `Sdup`) o, finche' mancano, la firma visiva della
// Fase 5 con soglie piu' larghe delle copie simili ([SimilarShotsRules]).

/** Uno scatto candidato: una cella della Libreria (mai un nascosto, mai un membro di un gruppo). */
@Immutable
class Shot(val media: MediaId, val takenAt: Long, val localDay: Int, val aspectMilli: Int) {
  override fun toString(): String = "Shot"
}

/** Il giudizio sulla somiglianza fra due scatti. */
enum class Affinity {
  /** Quasi lo stesso scatto: oltre la soglia. */
  SIMILAR,

  /** Scatti diversi. */
  DIFFERENT,

  /** Manca il vettore o la firma di uno dei due (o non sono confrontabili): non si dice niente. Mai "simili" nel dubbio. */
  UNKNOWN,
}

/** Chi giudica la somiglianza di due scatti. Puro e sincrono: i vettori e le firme sono gia' in memoria. */
fun interface ShotAffinity {
  fun between(a: Shot, b: Shot): Affinity
}

/** Quello che serve per scegliere la foto migliore di un gruppo e per proteggere quelle che l'utente tiene care (E-U3). */
@Immutable
class ShotDetails(
  val favorite: Boolean = false,
  val edited: Boolean = false,
  val inAlbum: Boolean = false,
  val pixels: Long = 0,
  val bytes: Long = 0,
) {
  /** Una foto preferita o in un album non si propone mai per l'eliminazione senza che l'utente la scelga (E-U3). */
  val isProtected: Boolean get() = favorite || inAlbum

  /** I byte per pixel: meno compressa = piu' dettaglio. */
  val density: Double get() = if (pixels > 0) bytes.toDouble() / pixels else 0.0

  override fun toString(): String = "ShotDetails(favorite=$favorite, edited=$edited, inAlbum=$inAlbum)"
}

/**
 * Un gruppo di scatti simili.
 *
 * @property shots in ordine di tempo.
 * @property best la migliore (D25): preferita, poi modificata, poi in un album, poi piu' pixel, poi meno compressa, poi l'identita'.
 *   E' sempre da tenere.
 */
@Immutable
class SimilarCluster(val shots: List<Shot>, val details: Map<MediaId, ShotDetails>, val best: MediaId) {
  init {
    require(shots.size >= 2) { "Un gruppo di scatti simili ne ha almeno due." }
    require(shots.any { it.media == best }) { "La migliore e' uno degli scatti." }
  }

  val media: List<MediaId> get() = shots.map { it.media }

  /** Gli scatti da tenere di partenza: la migliore e quelli protetti (preferiti, in un album). */
  val keepByDefault: Set<MediaId>
    get() = shots.map { it.media }.filterTo(LinkedHashSet()) { it == best || details[it]?.isProtected == true }

  /** Quelli che la proposta preseleziona per l'eliminazione: gli altri. Possono essere nessuno (tutti protetti). */
  val removeByDefault: Set<MediaId> get() = media.filterTo(LinkedHashSet()) { it !in keepByDefault }

  val key: String get() = shots.first().media.value + "+" + shots.size

  override fun toString(): String = "SimilarCluster(${shots.size} scatti)"
}

/** L'esito di una formazione di gruppi: i gruppi, e quanti scatti con compagni non si sono potuti giudicare (nessun vettore ne' firma). */
class ShotsPlan(val clusters: List<List<Shot>>, val undecided: Int) {
  override fun toString(): String = "ShotsPlan(clusters=${clusters.size}, undecided=$undecided)"
}

object SimilarShotsPlanner {

  /**
   * I gruppi di scatti simili fra [shots] (anche di giorni diversi: si formano per giorno).
   *
   *  - Due scatti sono **vicini** se sono dello stesso giorno locale, a non piu' di [windowMs] e di proporzione compatibile.
   *  - Fra due scatti vicini l'affinita' decide: solo [Affinity.SIMILAR] li lega; [Affinity.UNKNOWN] non lega mai (nel dubbio non si unisce).
   *  - Un gruppo e' una componente connessa di scatti legati, **di durata non oltre la finestra**: se la catena la supera si spezza al tempo
   *    e si rifa il legame dentro ogni pezzo (una lunga serie di scatti sempre un po' diversi non diventa un gruppo di un'ora).
   *  - Per tenere il costo lineare ogni scatto si confronta al massimo con i [SimilarShotsRules.MAX_NEIGHBORS] scatti che lo seguono nella
   *    finestra (una festa con 300 foto in 10 minuti non costa 45 mila confronti).
   */
  fun plan(shots: List<Shot>, affinity: ShotAffinity, windowMs: Long = SimilarShotsRules.WINDOW_MS): ShotsPlan {
    if (shots.size < 2) return ShotsPlan(emptyList(), 0)
    val ordered = shots.sortedWith(compareBy<Shot>({ it.localDay }, { it.takenAt }, { it.media.value }))
    val n = ordered.size
    val parent = IntArray(n) { it }
    val undecided = BooleanArray(n)
    val pairs = ArrayList<Pair<MediaId, MediaId>>()

    fun find(x: Int): Int {
      var r = x
      while (parent[r] != r) r = parent[r]
      var c = x
      while (parent[c] != r) {
        val next = parent[c]
        parent[c] = r
        c = next
      }
      return r
    }

    for (i in 0 until n) {
      var j = i + 1
      while (j < n && j - i <= SimilarShotsRules.MAX_NEIGHBORS) {
        val a = ordered[i]
        val b = ordered[j]
        if (b.localDay != a.localDay || b.takenAt - a.takenAt > windowMs) break
        val other = j++
        if (!SimilarShotsRules.aspectCompatible(a.aspectMilli, b.aspectMilli)) continue
        when (affinity.between(a, b)) {
          Affinity.SIMILAR -> {
            pairs += a.media to b.media
            parent[find(i)] = find(other)
          }
          Affinity.UNKNOWN -> {
            undecided[i] = true
            undecided[other] = true
          }
          Affinity.DIFFERENT -> Unit
        }
      }
    }

    val clusters = ArrayList<List<Shot>>()
    val byRoot = (0 until n).groupBy { find(it) }.values
    for (members in byRoot) {
      if (members.size < 2) continue
      for (piece in splitBySpan(members.map { ordered[it] }, pairs, windowMs)) {
        if (piece.size >= 2) clusters += piece
      }
    }
    val inCluster = clusters.flatten().map { it.media }.toSet()
    val unresolved = (0 until n).count { undecided[it] && ordered[it].media !in inCluster }
    return ShotsPlan(clusters.sortedWith(compareBy({ -it.first().localDay }, { -it.first().takenAt })), unresolved)
  }

  /**
   * Una componente con durata oltre [windowMs] si taglia al tempo (un pezzo comincia al primo scatto e dura al piu' la finestra) e dentro
   * ogni pezzo si rifa il legame con gli archi che restano. Una componente corta resta intera.
   */
  private fun splitBySpan(component: List<Shot>, edges: List<Pair<MediaId, MediaId>>, windowMs: Long): List<List<Shot>> {
    if (component.last().takenAt - component.first().takenAt <= windowMs) return listOf(component)
    val pieces = ArrayList<List<Shot>>()
    var start = 0
    for (i in component.indices) {
      if (component[i].takenAt - component[start].takenAt > windowMs) {
        pieces += component.subList(start, i)
        start = i
      }
    }
    pieces += component.subList(start, component.size)
    return pieces.flatMap { piece -> reconnect(piece, edges) }
  }

  /** Le componenti connesse di [piece] con i soli archi che stanno tutti dentro. */
  private fun reconnect(piece: List<Shot>, edges: List<Pair<MediaId, MediaId>>): List<List<Shot>> {
    val index = piece.withIndex().associate { it.value.media to it.index }
    val parent = IntArray(piece.size) { it }
    fun find(x: Int): Int {
      var r = x
      while (parent[r] != r) r = parent[r]
      return r
    }
    for ((a, b) in edges) {
      val ia = index[a] ?: continue
      val ib = index[b] ?: continue
      parent[find(ia)] = find(ib)
    }
    return piece.indices.groupBy { find(it) }.values.map { members -> members.map { piece[it] } }
  }

  /** Sceglie la migliore (D25) fra gli [shots] di un gruppo, dai [details]. */
  fun best(shots: List<Shot>, details: Map<MediaId, ShotDetails>): MediaId = shots.map { it.media }.minWith(
    compareBy<MediaId>(
      { if (details[it]?.favorite == true) 0 else 1 },
      { if (details[it]?.edited == true) 0 else 1 },
      { if (details[it]?.inAlbum == true) 0 else 1 },
      { -(details[it]?.pixels ?: 0L) },
      { -(details[it]?.density ?: 0.0) },
      { it.value },
    ),
  )

  /** Il gruppo con i suoi dettagli e la migliore. */
  fun cluster(shots: List<Shot>, details: Map<MediaId, ShotDetails>): SimilarCluster = SimilarCluster(shots, details, best(shots, details))
}

/**
 * Le soglie e le misure dei doppioni simili. I valori sono quelli **calibrati sul set sintetico** (`SimilarShotsCalibrationTest`) e scritti in
 * `docs/decisioni.md` ("Fase 7 P8"): sul telefono si riconfermano con F77.
 */
object SimilarShotsRules {

  /** La finestra di tempo fra due scatti dello stesso gruppo: 10 minuti (D25). */
  const val WINDOW_MS: Long = 10L * 60 * 1000

  /** Quanti scatti successivi si confrontano al massimo con ognuno (costo lineare). */
  const val MAX_NEIGHBORS: Int = 40

  /** Scarto massimo della proporzione fra due scatti dello stesso gruppo (2%): stessa inquadratura, stessa orientazione. */
  const val ASPECT_TOLERANCE: Double = 0.02

  /**
   * `Sdup`: il coseno fra due vettori (normalizzati) da cui due scatti sono "quasi lo stesso" (D25). Valore di partenza del progetto 0,95;
   * il valore calibrato e' in `docs/decisioni.md`.
   */
  const val SDUP: Float = 0.95f

  /**
   * Le soglie della firma visiva per gli scatti simili, sulla **disposizione** (`SignatureLayout`: la firma ridotta a 16x16 celle, in unita' di
   * OKLab): la distanza media delle celle e la quota di celle "cambiate" (oltre `SignatureLayout.CHANGED_EPSILON`). Niente a che vedere con le
   * soglie delle copie simili, che dicono "stesso scatto" e chiedono che nessuna cella sia cambiata. Calibrate sul set sintetico (decisioni.md).
   */
  const val SIGNATURE_LAYOUT_MEAN_MAX: Float = 0.049f
  const val SIGNATURE_LAYOUT_CHANGED_MAX: Float = 0.38f

  /** Sotto questa struttura una firma non prova niente (cielo, muro, buio): come per le copie simili. */
  const val SIGNATURE_MIN_STRUCTURE: Float = 0.035f

  fun aspectCompatible(aMilli: Int, bMilli: Int): Boolean {
    if (aMilli <= 0 || bMilli <= 0) return false
    return abs(aMilli - bMilli).toDouble() <= ASPECT_TOLERANCE * max(aMilli, bMilli)
  }

  /** Il coseno fra due vettori **normalizzati** della stessa lunghezza. */
  fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
  }

  /** Il giudizio sulle firme: media e quota di celle cambiate della disposizione. Firme non confrontabili o senza struttura: [Affinity.UNKNOWN]. */
  fun signatureAffinity(a: VisualSignature, b: VisualSignature): Affinity {
    val d = SignatureLayout.distance(a, b) ?: return Affinity.UNKNOWN
    if (d.structure < SIGNATURE_MIN_STRUCTURE) return Affinity.UNKNOWN
    return if (d.mean <= SIGNATURE_LAYOUT_MEAN_MAX && d.changed <= SIGNATURE_LAYOUT_CHANGED_MAX) Affinity.SIMILAR else Affinity.DIFFERENT
  }
}
