package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.FacesLimits

// L'ARCHIVIO DEI VOLTI DI UNA GENERAZIONE (docs/fase8-progetto.md §1.1 "store/", §2.3 "Salvataggio", §2.4, D14, D15). Contratto di P0 perche'
// calcolo (P4), raggruppamento (P5), sincronizzazione (P3), persone (P9) e cancellazione (P8) partono insieme; l'archivio vero e' di P6
// (store/ sopra `FacesDatabase`, file `faces-<G>.db`, schema proprio v1), **cifrato** con la chiave dati della generazione (vettori,
// centroidi e nomi: nessun vettore o nome in chiaro nel file, C39). Il fake in memoria e' `testing/InMemoryFacesArchive`. Solo store/ vede
// il database (Phase8ArchitectureTest). Mai vettori, nomi o chiavi nei log (D37).

/**
 * Un volto di un corpo `faces` (§2.4): indice nel corpo, fotogramma, riquadro e cinque punti (in 1/65535), punteggio del rilevatore
 * (0..255), livello e motivi, lato corto in pixel della sorgente dell'allineamento, imbardata, nitidezza e vettore in float16 (solo CORE e
 * WEAK). Nessun attributo della persona.
 */
class FaceEntry(
  val index: Int,
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
    require(index in 0 until FaceRef.MAX_FACES && frame in 0..8) { "Indice o fotogramma fuori intervallo." }
    require(points.size == 10 && score in 0..255 && sidePx in 1..65_535 && yaw in -90..90 && sharpness in 0..255) { "Volto non valido." }
    require(FaceReasons.isValid(reasons)) { "Motivi sconosciuti." }
    require((halves != null) == tier.hasVector) { "Vettore solo per CORE e WEAK." }
    require(halves == null || halves.size == FaceSpaceCode.CURRENT.dimension) { "Dimensione del vettore." }
  }

  val landmarks: ShortArray get() = points.copyOf()
  val vector: ShortArray? get() = halves?.copyOf()

  override fun toString(): String = "FaceEntry(${tier.code})"
}

/**
 * I volti di un contenuto come li ha calcolati un dispositivo dell'account ([author]): un corpo `faces` (§2.4). [faces] vuota = "analizzato,
 * nessun volto". [digest] = le prime 8 byte (big endian) dello SHA-256 del corpo codificato (§2.4, `FacesDocs` di P3): entra nei riferimenti
 * dell'istantanea ([FaceRef.bodyDigest]); i fake ne usano uno deterministico qualunque.
 */
class FaceBody(
  val contentKey: ContentKey,
  val space: FaceSpaceCode,
  val detector: FaceDetectorCode,
  val recipe: Int,
  val source: FaceSource,
  val author: String,
  val faces: List<FaceEntry>,
  val digest: Long,
) {
  init {
    require(recipe in 1..255) { "Ricetta fuori intervallo." }
    require(faces.size <= FaceRef.MAX_FACES && faces.map { it.index } == faces.indices.toList()) { "Volti non in ordine o troppi." }
  }

  /** Il riferimento del volto [index] di questo corpo. */
  fun refOf(index: Int): FaceRef = FaceRef(contentKey, faces[index].frame, index, digest)

  override fun toString(): String = "FaceBody(${faces.size} volti, ricetta $recipe)"

  companion object {
    /**
     * Il vincitore fra due corpi dello stesso contenuto (§2.4 "Fusione dei rilevamenti"): la ricetta piu' alta, poi il dispositivo minore,
     * poi l'impronta minore. Un ordine totale: lo stesso su ogni dispositivo.
     */
    fun winner(a: FaceBody, b: FaceBody): FaceBody {
      require(a.contentKey == b.contentKey) { "Corpi di contenuti diversi." }
      if (a.recipe != b.recipe) return if (a.recipe > b.recipe) a else b
      if (a.author != b.author) return if (a.author < b.author) a else b
      return if (a.digest <= b.digest) a else b
    }
  }
}

/** La chiave di una voce `people` (§2.4): soggetto e campo. L'ultima scrittura vince per chiave. */
data class PeopleKey(val subject: String, val field: String) {
  override fun toString(): String = "PeopleKey"
}

/** Una voce `people` con l'ora del NAS ([atNas] null = "da datare": vale subito qui, si data alla prima sincronizzazione) e l'autore. */
data class PeopleRecord(val edit: PeopleEdit, val atNas: Long?, val author: String) {
  val key: PeopleKey get() = PeopleKey(edit.subject, edit.field)

  override fun toString(): String = "PeopleRecord($edit, datata=${atNas != null})"

  companion object {
    /**
     * L'ultima scrittura vince (come `PersonalMerge`, F4-D26): l'ora del NAS piu' alta (una voce da datare vince su tutte: e' una scelta
     * appena fatta qui), a pari ora l'autore **minore**, a pari tutto il valore maggiore (assente = lapide prima). Ordine totale: la fusione
     * e' commutativa, associativa e idempotente (P3 lo prova su 10.000 storie di 4 dispositivi). Due voci da datare della stessa chiave
     * sono entrambe di questo dispositivo: chi scrive la nuova **sostituisce** la vecchia (l'ordine qui sotto le tratterebbe per valore).
     */
    fun winner(a: PeopleRecord, b: PeopleRecord): PeopleRecord {
      require(a.key == b.key) { "Voci di chiavi diverse." }
      val ta = a.atNas ?: Long.MAX_VALUE
      val tb = b.atNas ?: Long.MAX_VALUE
      if (ta != tb) return if (ta > tb) a else b
      if (a.author != b.author) return if (a.author < b.author) a else b
      val va = a.edit.value
      val vb = b.edit.value
      if (va == vb) return a
      if (va == null) return b
      if (vb == null) return a
      return if (va >= vb) a else b
    }

    /** Lo stato unito: per ogni chiave il vincitore. */
    fun merge(current: Map<PeopleKey, PeopleRecord>, incoming: Iterable<PeopleRecord>): Map<PeopleKey, PeopleRecord> {
      val out = HashMap(current)
      for (record in incoming) {
        val existing = out[record.key]
        out[record.key] = if (existing == null) record else winner(existing, record)
      }
      return out
    }
  }
}

/**
 * R6 (correzioni della Fase 8): quanti corpi e quanti volti (di ogni livello) tiene l'archivio, di un autore o in tutto. [formatBytes] sono i
 * byte del formato che occupano **al piu'** (ogni volto contato con il suo vettore): la misura dei tetti in byte.
 */
data class FacesTotals(val bodies: Int, val faces: Long) {
  val formatBytes: Long get() = bodies.toLong() * FacesLimits.BODY_HEADER_BYTES + faces * (FacesLimits.FACE_BYTES + FacesLimits.VECTOR_BYTES)

  operator fun plus(other: FacesTotals): FacesTotals = FacesTotals(bodies + other.bodies, faces + other.faces)

  operator fun minus(other: FacesTotals): FacesTotals = FacesTotals(bodies - other.bodies, faces - other.faces)

  companion object {
    val ZERO = FacesTotals(0, 0)
  }
}

/**
 * I TETTI DELL'ARCHIVIO DEI VOLTI DI UNA GENERAZIONE (R3, R6 delle correzioni della Fase 8; il calcolo e' in `docs/decisioni.md`, sezione "Fase 8,
 * correzioni dopo le revisioni: formato e replica dei volti"). Valgono per cio' che arriva **dagli altri dispositivi** (la replica): le scelte
 * fatte qui e i corpi calcolati qui non si rifiutano mai. Larghi sopra una famiglia vera (centinaia di migliaia di foto, decine di persone per
 * dispositivo), stretti sotto un dispositivo compromesso che pubblica a ogni testa chiavi nuove.
 */
object FacesArchiveLimits {
  /** Voci `people` nell'archivio, in tutto. */
  const val MAX_PEOPLE: Int = 200_000

  /** Voci `people` arrivate da uno stesso dispositivo (la provenienza): quante ne porta al piu' una sua testa. */
  const val MAX_PEOPLE_PER_SIGNER: Int = TrustLimits.MAX_FACES_HEAD_ENTRIES

  /** Corpi `faces` di un autore letti dal NAS: due teste piene (il resto di contenuti tolti dal catalogo nel tempo). */
  const val MAX_BODIES_PER_AUTHOR: Int = 2 * TrustLimits.MAX_FACES_HEAD_ENTRIES

  /** Byte del formato dei corpi di un autore ([FacesTotals.formatBytes]). */
  const val MAX_BYTES_PER_AUTHOR: Long = 128L * 1024 * 1024

  /** Corpi `faces` nell'archivio, di tutti gli autori. */
  const val MAX_BODIES_TOTAL: Int = 1_000_000

  /** Byte del formato dei corpi di tutti gli autori. */
  const val MAX_BYTES_TOTAL: Long = 512L * 1024 * 1024

  /** Volti con un vettore letti per il raggruppamento ([FacesArchive.groupFaces]): ~0,55 KB l'uno in memoria, 300.000 sono ~165 MB. */
  const val MAX_GROUP_FACES: Int = 300_000
}

/**
 * L'archivio dei volti della generazione [generation] su questo dispositivo (P6). Salva **subito** (D16, §2.3: dopo una morte del processo
 * un media gia' salvato non si rifa'); tiene i corpi di tutti i dispositivi dell'account (dalla replica, P3) e risponde con i vincitori;
 * tiene l'istantanea del raggruppamento e le voci `people` unite. Ogni metodo e' idempotente; un archivio chiuso (chiave distrutta, generazione
 * cambiata) lancia `IllegalStateException` (chi chiama riconcilia, §2.8).
 */
interface FacesArchive {
  val generation: FaceGeneration

  /** Salva il corpo di [FaceBody.contentKey] scritto da [FaceBody.author] (sostituisce quello dello stesso autore). */
  suspend fun saveBody(body: FaceBody)

  /** I corpi vincenti ([FaceBody.winner]) per le chiavi chieste che ne hanno uno. */
  suspend fun winners(keys: Collection<ContentKey>): Map<ContentKey, FaceBody>

  /**
   * P3 (aggiunta al contratto di P0): i corpi scritti da [author] per le chiavi chieste che ne hanno uno, vincenti o no. Il pubblicatore
   * pubblica **tutti** i corpi di questo dispositivo (non solo quelli che vincono qui): cosi' il contributo di un dispositivo non si
   * restringe mai e ogni dispositivo che legge le stesse teste ha gli stessi corpi e sceglie lo stesso vincitore.
   */
  suspend fun bodiesOf(author: String, keys: Collection<ContentKey>): Map<ContentKey, FaceBody>

  /** Le chiavi gia' fatte con la ricetta [recipe] o piu' nuova, da un dispositivo qualunque dell'account, o "non supportate". */
  suspend fun analyzed(keys: Collection<ContentKey>, recipe: Int): Set<ContentKey>

  /** Un formato non decodificabile: "non supportato" con la ricetta [recipe] (non si riprova finche' la ricetta non cambia). */
  suspend fun markUnsupported(key: ContentKey, recipe: Int)

  /** I volti CORE e WEAK dei corpi vincenti nello spazio corrente, con i vettori in chiaro **solo in memoria**, per il raggruppamento. */
  suspend fun groupFaces(): List<GroupFace>

  /** Quanti volti NONE ("non riconoscibili") ha ogni chiave chiesta, nel corpo vincente. */
  suspend fun unrecognizable(keys: Collection<ContentKey>): Map<ContentKey, Int>

  suspend fun grouping(): GroupingState

  suspend fun saveGrouping(state: GroupingState)

  /** Le voci `people` unite (una per chiave). */
  suspend fun people(): Map<PeopleKey, PeopleRecord>

  /** Unisce [records] con [PeopleRecord.merge]: scelte fatte **qui** (la provenienza e' l'autore), mai rifiutate per i tetti. */
  suspend fun addPeople(records: List<PeopleRecord>)

  /**
   * R3 (correzioni della Fase 8): unisce [records] lette da un segmento firmato da [via] e ricorda con ogni voce la sua **provenienza** ([via]).
   * Una voce uguale (stessa modifica, ora e autore) gia' presente con un'altra provenienza prende quella del suo autore, se arriva da lui. Con
   * [own] (questo dispositivo): una voce a nome di [own] arrivata da un altro non sostituisce mai una scelta di [own] (una riga sua con la sua
   * provenienza); se la chiave non c'e' (stato locale perso) entra come le altre. Una chiave nuova oltre [FacesArchiveLimits.MAX_PEOPLE] in tutto,
   * o oltre [FacesArchiveLimits.MAX_PEOPLE_PER_SIGNER] arrivate da [via], non entra. Restituisce quante voci non sono entrate. Idempotente.
   */
  suspend fun addPeopleFrom(via: String, records: List<PeopleRecord>, own: String? = null): Int

  /**
   * R3: le voci `people` arrivate **dal loro autore** (scelte fatte qui, o lette dalla testa di chi le ha scritte): le sole che il pubblicatore
   * ripubblica nello stato unito. Una voce arrivata da un altro dispositivo non si ripubblica: cosi' la sua provenienza resta di un passo e la
   * revoca di chi l'ha portata la toglie ovunque.
   */
  suspend fun directPeople(): Map<PeopleKey, PeopleRecord>

  /**
   * R6: i corpi e i volti scritti da [author] (di tutti gli autori se [author] e' null); con [keys], solo quelli di quelle chiavi di contenuto
   * (cio' che un segmento letto sostituirebbe).
   */
  suspend fun bodyTotals(author: String?, keys: Collection<ContentKey>? = null): FacesTotals

  /**
   * P3 (aggiunta al contratto di P0): le voci `people` **da datare** ([PeopleRecord.atNas] null, scelte fatte qui senza l'ora del NAS)
   * prendono l'ora del NAS [nasNow] e **sostituiscono** la loro versione da datare, che [PeopleRecord.winner] farebbe vincere per sempre
   * contro le modifiche successive degli altri dispositivi. Lo chiama il pubblicatore prima di ripubblicare lo stato unito (D36: "prende
   * l'ora alla prima sincronizzazione"). Restituisce quante voci ha datato. Idempotente.
   */
  suspend fun datePeople(nasNow: Long): Int

  /**
   * P3 (aggiunta al contratto di P0): toglie i corpi e le voci `people` scritti da [authors], i dispositivi dell'account revocati o persi
   * nel registro accettato ("la revoca lo toglie", L99, formato §4.6): i loro corpi non vincono piu' e i loro nomi non si mostrano piu'. R3:
   * anche le voci `people` **arrivate** da loro (la provenienza), qualunque autore dichiarino.
   * La replica poi rilegge le teste degli altri dispositivi (le voci valide che le loro avevano tornano). Restituisce quante voci e quanti
   * corpi ha tolto. Idempotente.
   */
  suspend fun dropAuthors(authors: Set<String>): Int
}
