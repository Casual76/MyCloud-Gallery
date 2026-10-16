package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Fase 5, D36 (docs/fase5-progetto.md §2.12, risposta Q11; E-L10…E-L13): le COPIE SIMILI. Contratto di P0, logica di P15
// (perimetro `core-library/.../similar/**`): rilevatore, firma visiva (`VisualSignature` di core-model), scelta della copertina,
// regole del gruppo `SIMILAR_COPIES`.
//
// **Perche' in core-library.** Il rilevatore non puo' stare in core-backup (§1.1: core-backup scrive sul NAS, e chi decide cosa
// mostrare non deve stare nel modulo che scrive), e i moduli che leggono il NAS o mostrano la Libreria non vedono core-backup. Qui
// ci sono gia' tutti gli ingressi che servono, in sola lettura: il catalogo con l'indice `sort_time` per i candidati (core-database),
// le miniature dell'indice del NAS dalla replica (core-index, `ReplicaBlobs`) e la decodifica (core-imaging); e qui stanno la cella,
// i gruppi e la loro copertina (`GROUP_COVER`, `groupSizeAt`). Il gruppo e' un **dato derivato locale**: nessuna azione sul NAS parte
// da qui. Il backup legge solo l'avanzamento del confronto ([SimilarCopiesProgress]) da una porta sua (`ExistingCopiesProgress` di
// core-backup), che `:app` lega a [SimilarCopies.progress].

/**
 * Il rilevatore delle copie simili e le due scelte dell'utente. Nessuna eccezione verso fuori per un file illeggibile o un NAS
 * spento: il candidato resta in attesa ([SimilarCopiesProgress.pending]) e si riprova al giro dopo.
 */
interface SimilarCopies {

  /** Quanti candidati aspettano ancora la firma o il confronto: il riepilogo del primo backup lo dice e lo aspetta (§2.11 punto 6). */
  val progress: StateFlow<SimilarCopiesProgress>

  /**
   * Un giro di manutenzione del catalogo (dopo che META ha letto l'ora di scatto di un media nuovo, del telefono o del NAS): firma i
   * candidati (stessa ora di scatto al secondo e stessa proporzione), forma i gruppi che passano tutte le condizioni del §2.12 e
   * scioglie quelli che non le passano piu'. Al massimo [budget] di lavoro; "nel dubbio non si unisce".
   */
  suspend fun run(budget: Duration): SimilarCopiesReport

  /** Il gruppo di [mediaId] per la striscia del viewer ("2 copie"), o null se il media non e' in un gruppo di copie simili. */
  suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup?

  /**
   * "Mostra questa come principale": [mediaId] diventa la copertina del suo gruppo. Dato personale sincronizzato (core-personal,
   * `SimilarCopiesRecords.cover`): vince sulla regola della copertina. Un media fuori da un gruppo: [ChoiceOutcome.NOT_IN_GROUP].
   */
  suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome

  /**
   * "Separa": [mediaId] esce dal suo gruppo **per sempre** e su tutti i dispositivi dell'utente (E-L13; una voce personale per ogni
   * altra copia del gruppo, `SimilarCopiesRecords.separation`). Un gruppo rimasto con una copia si scioglie. Il nascosto resta.
   */
  suspend fun separate(mediaId: MediaId): ChoiceOutcome
}

/**
 * L'avanzamento del confronto.
 *
 * @property pending candidati in attesa di firma o di confronto (un candidato del NAS senza miniatura nell'indice aspetta la
 *   Preparazione).
 * @property running un giro e' in corso adesso.
 */
data class SimilarCopiesProgress(val pending: Int, val running: Boolean) {
  init {
    require(pending >= 0) { "Conteggio negativo." }
  }

  /** Niente da confrontare: il riepilogo del primo backup puo' dare i suoi numeri definitivi. */
  val settled: Boolean get() = pending == 0 && !running

  companion object {
    val SETTLED: SimilarCopiesProgress = SimilarCopiesProgress(pending = 0, running = false)
  }
}

/** L'esito di un giro: soli numeri (Diagnostica). */
data class SimilarCopiesReport(val signed: Int, val grouped: Int, val dissolved: Int, val ambiguous: Int, val pending: Int)

/** Dove sta una copia (la striscia del viewer lo dice per ognuna). */
enum class CopyPlace { PHONE, NAS_PRIVATE, NAS_COMMON }

/**
 * Una copia di un gruppo, con cio' che serve alla scelta della copertina (§2.12: piu' pixel; a parita' di formato meno compressione,
 * cioe' piu' byte per pixel o bitrate piu' alto; a formati diversi l'originale della fotocamera; poi il file piu' grande, poi l'id
 * minore) e alla striscia del viewer. Nessun nome di file: il viewer lo legge dal catalogo se lo mostra.
 *
 * @property cameraOriginal la copia viene dal telefono o ha i dati del produttore intatti (una conversione non ha piu' dettaglio
 *   della sua sorgente).
 */
data class SimilarCopy(
  val mediaId: MediaId,
  val kind: MediaKind,
  val places: Set<CopyPlace>,
  val width: Int,
  val height: Int,
  val bytes: Long,
  val mime: String?,
  val bitrate: Long?,
  val cameraOriginal: Boolean,
) {
  override fun toString(): String = "SimilarCopy(${kind.code}, ${width}x$height)"
}

/**
 * Un gruppo di copie simili: [cover] e' la copertina (scelta dell'utente se [coverChosenByUser], altrimenti la regola); [hidden]
 * vale per tutto il gruppo (E-L11: nascosto contagioso).
 */
data class SimilarCopiesGroup(
  val id: MediaGroupId,
  val cover: MediaId,
  val copies: List<SimilarCopy>,
  val coverChosenByUser: Boolean,
  val hidden: Boolean,
) {
  init {
    require(copies.size >= 2) { "Un gruppo di copie simili ha almeno due copie." }
    require(copies.any { it.mediaId == cover }) { "La copertina e' una delle copie." }
  }
}

/** L'esito di "Mostra questa come principale" e di "Separa". */
enum class ChoiceOutcome {
  /** Scelta salvata (dato personale) e applicata al gruppo. */
  APPLIED,

  /** Il media non e' (piu') in un gruppo di copie simili. */
  NOT_IN_GROUP,
}
