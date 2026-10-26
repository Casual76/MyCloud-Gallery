package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.StableCode

// LE PORTE DI core-faces VERSO IL RESTO DELL'APP (docs/fase8-progetto.md §1.2 "Porte", §1.4): le lega :app (P13) sopra il buffer di
// analisi, l'indice, il telefono, il catalogo, la share privata del `NasGraph`, core-nasops, core-albums e core-search. core-faces non vede
// quei moduli: passa sempre da qui. Le prove usano i fake di `testing/` (`FailingFacesPorts` fallisce se chiamato fuori dalle regole, §4.3).

/** Dove sta un media per i volti (D16): l'area privata dell'account, l'area comune, o solo il telefono di questo dispositivo. */
enum class FacesArea(override val code: String) : StableCode {
  PRIVATE("private"),
  COMMON("common"),
  PHONE_ONLY("phone_only"),
}

/**
 * Un media da analizzare (§2.3), come lo dice il catalogo di questo dispositivo: la chiave di contenuto, il media, il tipo, la sorgente
 * (screenshot e registrazioni dello schermo esclusi, D18), il bit `DOCUMENT`, se e' un ritaglio (`Ritagli/`), il mese di scatto
 * (`aaaa-mm`, `0000-00` senza data) e l'area. I nascosti **ci sono** (si calcolano, non si mostrano).
 */
data class FacesItem(
  val contentKey: ContentKey,
  val media: MediaId,
  val kind: MediaKind,
  val source: MediaSource,
  val document: Boolean,
  val cutout: Boolean,
  val month: String,
  val area: FacesArea,
) {
  /** D18: screenshot, registrazioni dello schermo, documenti e ritagli non si analizzano. */
  val excluded: Boolean get() = source == MediaSource.SCREENSHOT || document || cutout

  override fun toString(): String = "FacesItem(${kind.code}, ${area.code}, escluso=$excluded)"
}

/**
 * Il catalogo per i volti (P13 sopra `MediaCatalog`; nessuna query nuova se non serve, §1.5): i mesi con media di un'area (dal piu' recente),
 * i media di un mese in ordine di chiave di contenuto, le chiavi delle celle (membri del gruppo compresi: una persona compare nella cella
 * che li contiene, D26) e i media di un insieme di chiavi.
 */
interface FacesCatalog {
  suspend fun months(area: FacesArea): List<String>

  suspend fun items(area: FacesArea, month: String): List<FacesItem>

  suspend fun cellKeys(media: Collection<MediaId>): Map<MediaId, Set<ContentKey>>

  suspend fun mediaWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>>
}

/** Perche' un ingresso non c'e' adesso (D17). */
enum class InputWait(override val code: String) : StableCode {
  /** Nessuna anteprima nel buffer, nell'indice o dal telefono, e questo dispositivo non puo' rifarla: il media aspetta (E-F18). */
  NO_PREVIEW("no_preview"),

  /** La ri-preparazione dall'originale del NAS e' un'attivita' pesante: aspetta il Wi-Fi di casa e le regole. */
  HEAVY_WORK("heavy_work"),

  /** Il NAS non risponde. */
  NAS("nas"),
}

/** Un ingresso per il rilevatore (D17): un'immagine pronta (preparata da `ModelInputs`, mai byte remoti al motore) con la sua sorgente. */
sealed interface FaceInput {
  class Ready(val image: FaceImage, val source: FaceSource) : FaceInput {
    override fun toString(): String = "FaceInput.Ready(${source.code})"
  }

  data class Waiting(val reason: InputWait) : FaceInput

  /** Formato non decodificabile: "non supportato", contato. */
  data object Unsupported : FaceInput

  /** La sorgente e' cambiata (dimensione o `qh1` diversi dal catalogo): il media si salta (come D8 della P11b). */
  data object Changed : FaceInput
}

/**
 * Gli ingressi dei volti (D17): [preview] = l'anteprima di analisi (1024 px) dal buffer, poi dall'indice della share (blob attestati), poi
 * dai byte del telefono, poi (Preparatore) rifatta dall'originale del NAS; [hq] = l'HQ (2048 px) per la seconda passata; [frames] =
 * copertina e fino a 3 fotogrammi di un video (D18); [sharpest] = la sorgente piu' nitida gia' in mano per l'allineamento. :app la lega
 * sopra `DiskAiBuffer`, `IndexAnalysisFetcher` e `AiPhone`/`Preparer`, preparando le immagini con `ModelInputs.forFaces` (P2).
 */
interface FaceInputs {
  suspend fun preview(item: FacesItem): FaceInput

  suspend fun hq(item: FacesItem): FaceInput

  suspend fun frames(item: FacesItem): List<FaceInput>

  suspend fun sharpest(item: FacesItem): FaceInput
}

/** Un elenco di nomi **validi** di una cartella dei volti, con quanti nomi si sono ignorati e se l'elenco era troncato. */
data class FacesListing<T>(val items: List<T>, val ignored: Int, val truncated: Boolean) {
  override fun toString(): String = "FacesListing(${items.size}, ignorati=$ignored, troncato=$truncated)"
}

/** La lettura di un file dei volti: i byte, assente, o oltre il tetto chiesto (mai allocato oltre). */
sealed interface FacesRead {
  class Data(bytes: ByteArray) : FacesRead {
    private val data = bytes.copyOf()
    val bytes: ByteArray get() = data.copyOf()

    override fun toString(): String = "FacesRead.Data(${data.size} B)"
  }

  data object NotFound : FacesRead

  data object TooLarge : FacesRead
}

/** L'esito di una scrittura esclusiva: creato, o il nome c'era gia' (nessun byte sovrascritto). */
enum class FacesPut { CREATED, ALREADY_EXISTS }

/**
 * Il NAS dei dati dei volti di UN account (§2.4, D11, D12): solo l'area privata dichiarata [share], solo sotto `faces/`. Si legge ogni
 * cartella (anche di dispositivi di altri account messe li' da chi ha la password: chi legge le rifiuta, `NOT_OWNER`); si scrive **solo**
 * sotto `faces/<G>/<deviceId>/` (la firma della porta non permette altro) e **solo** con la scrittura esclusiva. Se [share] e' l'area comune
 * ogni chiamata fallisce (`NOT_ALLOWED`, L23). I percorsi li costruisce `NasLayout` (sync/, P3), mai da un dato letto.
 */
interface FacesRemote {
  val share: String
  val deviceId: String

  suspend fun generations(): RemoteResult<FacesListing<FaceGeneration>>

  suspend fun devices(generation: FaceGeneration): RemoteResult<FacesListing<String>>

  /** I numeri delle teste di un dispositivo: solo un indizio (le teste si leggono per nome, formato §2.3). */
  suspend fun headNumbers(generation: FaceGeneration, deviceId: String): RemoteResult<FacesListing<Long>>

  suspend fun readHead(generation: FaceGeneration, deviceId: String, version: Long, maxBytes: Int): RemoteResult<FacesRead>

  suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead>

  suspend fun publishSegment(generation: FaceGeneration, hash: String, bytes: ByteArray): RemoteResult<FacesPut>

  suspend fun publishHead(generation: FaceGeneration, version: Long, bytes: ByteArray): RemoteResult<FacesPut>
}

/** Una risorsa prenotabile dei volti (D16, D21): un'unita' di lavoro (area, mese, parte) o il custode. Mai nomi di share, cartelle o file. */
sealed interface FacesResource {
  data class WorkUnit(val area: AreaKind, val month: String, val part: Int) : FacesResource {
    init {
      require(MONTH.matches(month) && part >= 0) { "Unita' non valida." }
    }

    private companion object {
      val MONTH = Regex("^(0000-00|[0-9]{4}-(0[1-9]|1[0-2]))$")
    }
  }

  data object Cluster : FacesResource
}

/** L'esito di una prenotazione dei volti (come le unita' della Fase 4, sotto `faces/<G>/leases/`). */
sealed interface FacesLease {
  /** Presa con il numero [number] fino a [untilNasMillis] (ora del NAS). */
  data class Held(val number: Long, val untilNasMillis: Long) : FacesLease

  /** Un altro dispositivo dell'account la tiene. */
  data object Busy : FacesLease

  /** La nostra e' scaduta e un altro l'ha presa: si finisce il media in corso e si cambia unita'. */
  data object Lost : FacesLease

  /** Cartella delle prenotazioni anomala: si lavora senza (danno solo d'efficienza). */
  data object Contended : FacesLease

  /** Il cancello non lascia scrivere (recinzione D34, congelamento...): si lavora in locale senza prenotare. */
  data class Blocked(val reason: FacesWriteBlock) : FacesLease

  /** Errore del NAS. */
  data object Failed : FacesLease
}

/** Le prenotazioni dei volti **sotto la generazione** (D16): "Disattiva e cancella" le toglie con la cartella. Durata 1 ora, rinnovo ogni 20'. */
interface FacesLeases {
  suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease

  suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease

  suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held)
}
