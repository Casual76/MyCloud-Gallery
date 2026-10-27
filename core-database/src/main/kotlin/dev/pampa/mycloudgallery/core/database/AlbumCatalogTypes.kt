package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import java.time.LocalDate
import java.time.LocalTime

// I TIPI CHE `AlbumCatalog` E `MediaCatalog` RESTITUISCONO PER GLI ALBUM (Fase 7, P1; docs/modello-dati.md §14.4). core-database non
// vede core-albums: questi tipi sono la **forma** dei contratti `AlbumCandidates`, `CatalogFacts` e delle altre porte di P0, campo per
// campo, cosi' l'adattatore di `:app` (P13) e' una traduzione senza logica. Nessun percorso, titolo, luogo o coordinata nei `toString()`
// (D37): sono dati dell'utente.

/**
 * Da dove viene un candidato (`SmartSource` di core-albums, stessi codici). **Una partizione**, in questo ordine: [MY_PHONE] se ha una
 * copia presente sul telefono; altrimenti [MY_PRIVATE] se ne ha una nell'area privata; altrimenti [FAMILY] se il catalogo sa che e' di
 * un altro account; altrimenti [COMMON] (area comune, o una share di area sconosciuta). Un media mio spostato nell'area comune e'
 * quindi [COMMON] (vedi [CatalogCandidate.ownedByMe]).
 */
enum class CandidateSource(override val code: String) : StableCode {
  MY_PHONE("my_phone"),
  MY_PRIVATE("my_private"),
  COMMON("common"),
  FAMILY("family"),
  ;

  companion object {
    val ALL: Set<CandidateSource> = entries.toSet()
  }
}

/**
 * Quali candidati. Tutti i criteri insieme. Il criterio **guida** sceglie l'indice: [originDevice] se c'e', altrimenti [placeIds],
 * altrimenti il giorno; gli altri sono filtri sulle righe che l'indice restituisce. [days] sono giorni locali `aaaammgg` (estremi
 * compresi); null = tutta la Libreria.
 */
data class CandidateQuery(
  val days: IntRange? = null,
  val placeIds: Set<Int>? = null,
  val originDevice: String? = null,
  val sources: Set<CandidateSource> = CandidateSource.ALL,
  val withGpsOnly: Boolean = false,
) {
  init {
    require(days == null || !days.isEmpty()) { "Intervallo di giorni vuoto." }
    require(placeIds == null || placeIds.isNotEmpty()) { "Nessun luogo." }
    require(sources.isNotEmpty()) { "Nessuna sorgente." }
  }
}

/**
 * Il cursore di una pagina di candidati: l'ultimo visto. L'ordine e' (giorno, istante, id) per le query per giorno e per dispositivo; per
 * quelle per luogo e' (luogo, giorno, istante, id): [placeId] e' il luogo e vale [NO_PLACE] fuori dalle query per luogo.
 */
data class CandidateKey(val placeId: Int, val day: Int, val time: Long, val media: String) {
  override fun toString(): String = "CandidateKey"

  companion object {
    const val NO_PLACE: Int = Int.MIN_VALUE
  }
}

/**
 * Un candidato: una **cella visibile** (mai un nascosto, una per gruppo: D14, D22). [day] e [localTime] nell'ora del posto dello scatto
 * (D36); [gps] dato sensibile; [placeId] il `geonameId` del luogo; [originDevice] la colonna derivata ([OriginDevice]); [screen]
 * screenshot o registrazione dello schermo (E-AL4); [pixels] larghezza per altezza (0 se non letti). [ownedByMe]: il catalogo sa che
 * il media e' del mio account (non e' nel contratto `AlbumCandidate` di P0: serve alla "casa", D18, per i miei media passati
 * nell'area comune).
 */
data class CatalogCandidate(
  val media: MediaId,
  val day: LocalDate,
  val takenAtUtcMs: Long,
  val localTime: LocalTime?,
  val gps: GeoPoint?,
  val placeId: Int?,
  val originDevice: String?,
  val source: CandidateSource,
  val kind: MediaKind,
  val screen: Boolean,
  val pixels: Long,
  val ownedByMe: Boolean,
) {
  override fun toString(): String = "CatalogCandidate(${source.code})"
}

data class CatalogCandidatePage(val items: List<CatalogCandidate>, val next: CandidateKey?)

/** Dove sta un media, per il piano della condivisione (`MediaPlace` di core-albums, stessi codici; D12). */
enum class CatalogPlace(override val code: String) : StableCode {
  /** Nessuna copia sul NAS: si carica. */
  PHONE_ONLY("phone_only"),

  /** Una copia nell'area privata del mio account (e nessuna nell'area comune): si sposta. */
  MY_PRIVATE("my_private"),

  /** Una copia nell'area comune (anche se ne ha una privata): si fa riferimento. */
  COMMON("common"),

  /** Copie solo in una share che non e' ne' la mia area privata ne' l'area comune: non si puo' aggiungere. */
  OTHERS_PRIVATE("others_private"),
}

/** Un file del NAS: il nome della share e il percorso relativo alla share. Dato dell'utente: mai nei log. */
data class CatalogNasFile(val share: String, val path: String) {
  override fun toString(): String = "CatalogNasFile"
}

/**
 * I fatti di un media (`MediaFacts` di core-albums). [requested] e' l'id chiesto (puo' essere uno assorbito da un'unione), [media] quello
 * vero. [nas] e' il file da cui si parte (area privata) o a cui si fa riferimento (area comune); [onPhone] una copia presente sul telefono.
 */
data class CatalogMediaFacts(
  val requested: MediaId,
  val media: MediaId,
  val place: CatalogPlace,
  val mine: Boolean,
  val hidden: Boolean,
  val isVideo: Boolean,
  val size: Long,
  val contentKey: ContentKey?,
  val version: VersionId,
  val nas: CatalogNasFile?,
  val onPhone: Boolean,
  /** L'hash completo della versione corrente, se il catalogo lo conosce (I1: la voce dell'elemento lo porta, chi legge lo confronta). */
  val sha256: Sha256? = null,
) {
  override fun toString(): String = "CatalogMediaFacts(${place.code}, mine=$mine, hidden=$hidden)"
}

/** Un elemento da attestare (`AttestRequest`): la chiave di contenuto intera e l'hash completo, se noto (D9). */
data class CommonAttestRequest(val contentKey: ContentKey, val fullHash: Sha256?)

/** Da che parte sta una copia, per i Doppioni (D25): il telefono o il NAS (area privata e area comune insieme). */
enum class DuplicatePart(override val code: String) : StableCode {
  PHONE("phone"),
  NAS("nas"),
}

/** Dove sta una copia di un doppione: il telefono, la mia area privata, l'area comune, l'area di un altro account (E-U1). */
enum class CopyPlace(override val code: String) : StableCode {
  PHONE("phone"),
  MY_PRIVATE("my_private"),
  COMMON("common"),
  OTHERS("others"),
}

/** Una copia di un gruppo di doppioni. [path] e' dato dell'utente (per il telefono, cartella e nome). */
data class DuplicateCopy(
  val location: LocationId,
  val place: CopyPlace,
  val share: String?,
  val path: String,
  val size: Long,
  val modifiedAt: Long,
  val verifiedAt: Long?,
  val state: LocationState,
) {
  override fun toString(): String = "DuplicateCopy(${place.code})"
}

/**
 * Le copie **presenti** di una stessa versione dalla stessa [part] (almeno due): un doppione identico (D25). [fullHash] e' l'hash completo
 * della versione se noto: senza, le copie hanno gli stessi `qh1` e dimensione ma non sono verificate ("da verificare", D27).
 */
data class DuplicateGroup(
  val media: MediaId,
  val version: VersionId,
  val part: DuplicatePart,
  val fullHash: Sha256?,
  val copies: List<DuplicateCopy>,
) {
  override fun toString(): String = "DuplicateGroup(${part.code}, ${copies.size} copie)"
}

/** Una pagina di doppioni; [next] e' il cursore della pagina dopo, null alla fine. */
data class DuplicatePage(val groups: List<DuplicateGroup>, val next: String?)

/** Perche' [MediaCatalog.attachMoved] non ha (ancora) collegato il file. */
sealed interface AttachMovedOutcome {
  /** Il file e' una posizione del media: nuova, o spostata dalla sorgente. */
  data object Attached : AttachMovedOutcome

  /** Era gia' tutto a posto (secondo giro, o la scansione l'aveva gia' visto): nessuna scrittura, nessun cambiamento annunciato. */
  data object AlreadyAttached : AttachMovedOutcome

  /** Il media non c'e' (piu'). Niente e' stato scritto. */
  data object UnknownMedia : AttachMovedOutcome

  /** Il file di destinazione e' gia' una posizione di **un altro** media (la scansione l'ha visto prima e non l'ha riconosciuto): niente e' stato scritto. */
  data object DestinationOfOtherMedia : AttachMovedOutcome

  /** La dimensione del file non e' quella della versione: non sono gli stessi byte. Niente e' stato scritto. */
  data object SizeMismatch : AttachMovedOutcome

  /** L'hash verificato non e' l'hash completo della versione: non sono gli stessi byte. Niente e' stato scritto. */
  data object ContentMismatch : AttachMovedOutcome
}
