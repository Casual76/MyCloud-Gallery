package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import java.time.LocalDate
import java.time.LocalTime

// CIO' CHE GLI ALBUM LEGGONO DEL CATALOGO LOCALE (docs/fase7-progetto.md §1.2 "Porte" (`CatalogFacts`), §1.3 riga core-database, §2.2,
// §2.3, §2.5 passo 1, D9, D14, D18, D19, D28). Firme di P0: le query vere su v6 sono di P1 (candidati per giorno, luogo, dispositivo;
// `origin_device`; posizioni per parte), gli adattatori li lega :app o li scrive chi le usa (P4, P6, P7). **Solo il catalogo di chi
// legge**, mai il NAS da solo (D9, E-S11).

/** Dove sta un media, per il piano della condivisione (D12). */
enum class MediaPlace(override val code: String) : StableCode {
  /** Solo sul telefono (nessuna copia sul NAS): si carica (E-SH1). */
  PHONE_ONLY("phone_only"),

  /** Nell'area privata del mio account: si sposta. */
  MY_PRIVATE("my_private"),

  /** Gia' nell'area comune: si fa riferimento (E-SH11). */
  COMMON("common"),

  /** Nell'area privata di un altro account: non si puo' aggiungere. */
  OTHERS_PRIVATE("others_private"),
}

/**
 * I fatti di un media per il piano e per l'orchestratore. [nas] e' il file del NAS da cui si parte (area privata) o a cui si fa riferimento
 * (area comune); [onPhone]: una copia presente sul telefono (D28: il guardiano parte subito solo per i miei media che **non** l'hanno).
 */
data class MediaFacts(
  val media: MediaId,
  val place: MediaPlace,
  val mine: Boolean,
  val hidden: Boolean,
  val isVideo: Boolean,
  val size: Long,
  val contentKey: ContentKey?,
  val version: VersionId?,
  val nas: NasItemRef?,
  val onPhone: Boolean,
  /**
   * L'hash completo della versione corrente, se il catalogo lo conosce (I1 della revisione di sicurezza della Fase 7): la voce dell'elemento lo
   * porta (`SharedAlbumChange.Item.sha256`) e chi legge lo confronta nell'attestazione (D9), cosi' un file diverso con la stessa chiave rapida
   * non si mostra al suo posto.
   */
  val sha256: Sha256? = null,
) {
  override fun toString(): String = "MediaFacts(${place.code}, mine=$mine, hidden=$hidden)"
}

/** Un elemento d'album da attestare (D9): la chiave di contenuto intera e l'hash completo, se noto. */
data class AttestRequest(val contentKey: ContentKey, val sha256: Sha256?)

/** Il catalogo per la condivisione e l'attestazione (§1.2 `CatalogFacts`). */
interface CatalogFacts {
  /** I fatti dei media noti fra [media]; un media sconosciuto manca dalla mappa. */
  suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts>

  /**
   * D9: per ogni richiesta, il media del catalogo locale che ha **nell'area comune** (`commonShare` del registro) un file presente con la
   * stessa chiave di contenuto intera (e lo stesso hash completo se entrambi lo hanno). Un elemento che rimanda a un file privato,
   * sparito o diverso non c'e' nella mappa: non si mostra, non si conta, **non avvia nulla**.
   */
  suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId>
}

/**
 * Un candidato degli album smart e dei viaggi (§2.2, §2.3): una **cella visibile** (mai un nascosto, una per gruppo, D14, D22). [day] e
 * [localTime] nell'ora del posto dello scatto (D36); [gps] dato sensibile (solo smart/ e trips/ lo leggono); [originDevice] la colonna
 * derivata di v6 (questo dispositivo, `Backup/<dispositivo>/`, oppure marca e modello dall'EXIF: regola B); [screen] screenshot o
 * registrazione dello schermo (E-AL4).
 */
data class AlbumCandidate(
  val media: MediaId,
  val day: LocalDate,
  val takenAtUtcMs: Long,
  val localTime: LocalTime?,
  val gps: GeoPoint?,
  val placeId: Int,
  val originDevice: String?,
  val source: SmartSource,
  val kind: MediaKind,
  val screen: Boolean,
  val pixels: Long,
) {
  val isVideo: Boolean get() = kind == MediaKind.VIDEO

  /** I miei media (telefono di questo dispositivo o area privata del mio account): quelli che fanno casa e viaggi (D18). */
  val mine: Boolean get() = source == SmartSource.MY_PHONE || source == SmartSource.MY_PRIVATE

  /** Mai date, luoghi o coordinate nei log. */
  override fun toString(): String = "AlbumCandidate(${source.code})"
}

/** Quali candidati (§2.2 "Ricalcolo" a lotti, §2.3). Tutti i criteri insieme. */
data class CandidateFilter(
  val days: LocalDayRange? = null,
  val sources: Set<SmartSource> = SmartSource.ALL,
  val withGpsOnly: Boolean = false,
  val media: Set<MediaId>? = null,
)

/** Il cursore opaco di una pagina. */
@JvmInline
value class CandidateCursor(val value: String)

data class CandidatePage(val items: List<AlbumCandidate>, val next: CandidateCursor?)

/**
 * I candidati, a pagine, in ordine (giorno, istante, id). P1 li serve con gli indici su `local_day`, `place_id` e dispositivo (< 50 ms su
 * 100 mila media); [visibleTotal] e' la Libreria visibile (E-AL6: oltre il 20% le regole sono troppo larghe).
 */
interface AlbumCandidates {
  suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage

  suspend fun visibleTotal(): Int
}
