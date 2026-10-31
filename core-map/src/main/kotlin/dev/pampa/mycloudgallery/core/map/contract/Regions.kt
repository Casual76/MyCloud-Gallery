package dev.pampa.mycloudgallery.core.map.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.StateFlow

// LE REGIONI OFFLINE (docs/fase9-progetto.md §1.2 "Regioni", §2.9, D26, E-MAP11, E-MAP12, E-MAP20; risposta Q2 (a) del 27/10). Contratto di P0;
// catalogo, stime e copertura sono di P5 (regions/), lo scaricamento con `OfflineManager` di MapLibre e il foglio sono di P5 in feature-map;
// il riferimento e' `InMemoryMapRegions` (testing/) con la suite `MapRegionsContract`. Una regione si scarica **solo da un tocco** sul foglio
// che dice "il servizio delle mappe vedra' quale zona scarichi" ([RegionDownloadConfirmation], costruita SOLO dal foglio: Phase9ArchitectureTest),
// solo sul Wi-Fi (sui dati mobili con la seconda conferma), da un **catalogo fisso**; "la zona attorno a un luogo" prende il centro dal luogo
// GeoNames, **mai dalla casa** ne' dai punti delle foto (D29). Le tessere di una regione completa non si chiedono piu' online (`COVERED`).
//
// **Dentro i limiti di OpenFreeMap (decisione dell'utente del 31/10)**: ogni scaricamento al piu' 3.000 tessere e 50 MB, al piu' 4 richieste insieme ([OpenFreeMapLimits]).
// Le regioni intere e i paesi si scaricano fino a z12 (o z11, z10 se non ci stanno; sotto z10 non si offrono); "la zona attorno a un luogo" fino a z14 (15 e 30 km) o z13 (60 km),
// con lo stesso scalino in giu'. Il catalogo, la stima e il piano sono di `core.map.regions` (P5); qui il contratto.

/** L'id stabile di una regione: `it-<regione>`, `country-<codice>`, `around-<placeId>-<raggio>`. Mai nei log (dice la zona). */
@JvmInline
value class RegionId(val code: String) {
  init {
    require(Regex("^[a-z0-9-]{1,48}$").matches(code)) { "Id di regione non valido." }
  }

  override fun toString(): String = "RegionId"
}

enum class RegionKind(override val code: String) : StableCode {
  ITALIAN_REGION("italian_region"),
  COUNTRY("country"),
  AROUND_PLACE("around_place"),
}

/** Il raggio della "zona attorno a un luogo" (D26). */
enum class RegionRadius(val km: Int) {
  KM_15(15),
  KM_30(30),
  KM_60(60),
}

/** Una regione del catalogo o una zona attorno a un luogo. Il riquadro ([box]) e' fisso nel codice (o calcolato dal centro del luogo). */
sealed interface RegionSpec {
  val id: RegionId
  val kind: RegionKind
  val box: GeoBox

  /** Una regione italiana o un paese vicino: riquadro fisso, arrotondato a 0,1 gradi verso l'esterno (P5). [name] e' il nome per la pagina (mai nei log). */
  data class Named(override val id: RegionId, override val kind: RegionKind, override val box: GeoBox, val name: String = "") : RegionSpec {
    init {
      require(kind != RegionKind.AROUND_PLACE) { "Una regione con nome e' del catalogo." }
    }

    override fun toString(): String = "RegionSpec.Named(${kind.code})"
  }

  /**
   * La zona di [radius] attorno al luogo GeoNames [placeId], con il centro [center] **del luogo** ([PlaceCenters]). [urban]: il luogo e' una citta' (la stima usa la
   * densita' delle tessere di citta'); [name] il nome del luogo per la pagina (mai nei log).
   */
  data class AroundPlace(
    val placeId: Int,
    val radius: RegionRadius,
    val center: GeoE6,
    val urban: Boolean = false,
    val name: String = "",
  ) : RegionSpec {
    override val id: RegionId get() = RegionId("around-$placeId-${radius.km}")
    override val kind: RegionKind get() = RegionKind.AROUND_PLACE
    override val box: GeoBox get() = RegionBoxes.around(center, radius.km)

    override fun toString(): String = "RegionSpec.AroundPlace(${radius.km})"
  }
}

/** Il riquadro di una zona attorno a un centro (un quadrato di 2 x raggio, circa; i gradi di longitudine si allargano con la latitudine). */
object RegionBoxes {
  fun around(center: GeoE6, km: Int): GeoBox {
    val dLat = km / 111.32
    val cos = StrictMath.cos(StrictMath.toRadians(center.latE6 / 1_000_000.0)).coerceAtLeast(0.01)
    val dLon = (km / (111.32 * cos)).coerceAtMost(179.0)
    val lat = center.latE6 / 1_000_000.0
    val lon = center.lonE6 / 1_000_000.0
    fun e6(v: Double) = StrictMath.round(v * 1_000_000.0).toInt()
    val south = e6((lat - dLat).coerceAtLeast(-85.05))
    val north = e6((lat + dLat).coerceAtMost(85.05))
    var west = lon - dLon
    var east = lon + dLon
    if (west < -180.0) west += 360.0
    if (east > 180.0) east -= 360.0
    return GeoBox(e6(west), south, e6(east), north)
  }
}

/**
 * La stima prima di scaricare (D26, P5): le tessere da z0 a [maxZoom] nel riquadro e il peso dalla tabella di densita' calibrata su dati veri. [maxZoom] e' il livello piu' alto
 * che sta nei limiti di OpenFreeMap; con [offered] falso nemmeno z[RegionLimits.MIN_OFFER_ZOOM] ci sta e la voce non si offre ("Questa zona e' troppo grande": [tiles] e [bytes]
 * sono quelli di quel livello).
 */
data class RegionEstimate(val tiles: Long, val bytes: Long, val maxZoom: Int = RegionLimits.MAX_ZOOM, val offered: Boolean = true) {
  override fun toString(): String = "RegionEstimate($tiles, $bytes, z$maxZoom, $offered)"
}

enum class RegionStatus(override val code: String) : StableCode {
  DOWNLOADING("downloading"),

  /** Interrotta (processo terminato, Wi-Fi caduto, mappa spenta): riprende all'apertura dell'app, le tessere arrivate valgono (E-MAP11). */
  PARTIAL("partial"),
  READY("ready"),

  /** Completa ma vecchia di oltre [RegionLimits.UPDATE_AFTER_DAYS]: la pagina propone "Aggiorna" (solo a mano). */
  NEEDS_UPDATE("needs_update"),
}

/** Perche' una regione e' "parziale" (P5): decide se riprende da sola all'apertura dell'app (E-MAP11) o solo con un tocco. */
enum class RegionStop(override val code: String, val resumesAtOpen: Boolean) : StableCode {
  /** Il processo e' morto a meta' (si vede alla riapertura): riprende da sola all'apertura. */
  INTERRUPTED("interrupted", true),

  /** Wi-Fi caduto, roaming, nessuna rete, dati mobili senza la conferma: riprende da sola all'apertura, se la rete lo permette. */
  NETWORK("network", true),

  /** La mappa dettagliata e' stata spenta durante lo scaricamento (E-MAP20): riprende solo con un tocco. */
  MAP_OFF("map_off", false),

  /** Il server ha risposto male a lungo (attesa crescente, E-MAP3): riprende solo con un tocco. */
  SERVER("server", false),

  /** Lo spazio e' sceso sotto la riserva durante lo scaricamento: riprende solo con un tocco. */
  SPACE("space", false),

  /** Raggiunte 3.000 tessere (limite di OpenFreeMap): non riparte da sola ne' con un tocco; si elimina e si sceglie una zona piu' piccola. */
  LIMIT_TILES("limit_tiles", false),

  /** Superati 50 MB sul filo (limite di OpenFreeMap): come sopra. */
  LIMIT_BYTES("limit_bytes", false),
  ;

  /** Un tetto di OpenFreeMap: la regione non si riprende piu'. */
  val terminal: Boolean get() = this == LIMIT_TILES || this == LIMIT_BYTES
}

/**
 * Una regione su questo dispositivo: stato, avanzamento, byte, giorno (`aaaammgg`) dell'ultimo scaricamento completo; [maxZoom] il livello piu' alto scaricato (P5); [stop] il
 * motivo dell'interruzione quando e' `PARTIAL`.
 */
data class RegionState(
  val spec: RegionSpec,
  val status: RegionStatus,
  val completedTiles: Long,
  val totalTiles: Long,
  val bytes: Long,
  val completedDay: Int?,
  val maxZoom: Int = RegionLimits.MAX_ZOOM,
  val stop: RegionStop? = null,
) {
  val percent: Int get() = if (totalTiles <= 0) 0 else ((completedTiles * 100) / totalTiles).toInt().coerceIn(0, 100)

  override fun toString(): String = "RegionState(${status.code}, $percent%)"
}

/**
 * La conferma di uno scaricamento (D26): la costruisce **solo** il foglio della regione (`feature-map/.../regions/RegionDownload*.kt`), dopo un
 * tocco, con la stima mostrata ([estimateBytes]) e, per i dati mobili, la seconda conferma ([mobile]). Vale 10 minuti di orologio monotono
 * ([issuedAtMs], come le altre conferme). Nel `toString()` niente zona.
 */
class RegionDownloadConfirmation(val region: RegionId, val estimateBytes: Long, val mobile: Boolean, val issuedAtMs: Long) {
  fun validAt(nowMs: Long): Boolean = nowMs >= issuedAtMs && nowMs - issuedAtMs <= VALID_MS

  override fun toString(): String = "RegionDownloadConfirmation[mobile=$mobile]"

  companion object {
    const val VALID_MS: Long = 10 * 60 * 1_000L
  }
}

/** L'esito di "Scarica" (D26, E-MAP12). */
sealed interface DownloadStart {
  data object Started : DownloadStart

  /** Roaming o nessuna rete, o dati mobili senza la seconda conferma ma con "Solo sul Wi-Fi". */
  data object NeedsWifi : DownloadStart

  /** Sui dati mobili serve la seconda conferma con la dimensione. */
  data class NeedsMobileConfirmation(val bytes: Long) : DownloadStart

  /** Spazio insufficiente: serve [neededBytes] (dimensione + 1 GiB libero). */
  data class NoSpace(val neededBytes: Long) : DownloadStart

  /** Oltre i limiti di OpenFreeMap (3.000 tessere o 50 MB) o il tetto totale: "Questa zona e' troppo grande: scegline una piu' piccola". */
  data object TooLarge : DownloadStart

  /** Un'altra regione si sta gia' scaricando: mai due scaricamenti insieme (limiti di OpenFreeMap). */
  data object Busy : DownloadStart

  /** La conferma e' scaduta o non e' di questa regione: si richiede. */
  data object Expired : DownloadStart
}

/**
 * I tetti delle regioni (D26, adeguati il 31/10 ai limiti di OpenFreeMap): per regione quello di OpenFreeMap ([OpenFreeMapLimits.MAX_BYTES]), in tutto **1 GB** (proposta: con 50 MB
 * a regione sono venti regioni), sempre **1 GiB libero** (E-MAP12).
 */
object RegionLimits {
  const val PER_REGION_BYTES: Long = OpenFreeMapLimits.MAX_BYTES
  const val TOTAL_BYTES: Long = 1_000_000_000L
  const val FREE_RESERVE_BYTES: Long = 1L shl 30
  const val MIN_ZOOM: Int = 0

  /** Il livello piu' alto che esiste (z14, quello dello stile): "la zona attorno a un luogo" di 15 e 30 km parte da qui. */
  const val MAX_ZOOM: Int = MapHost.MAX_ZOOM

  /** Le regioni intere e i paesi partono da qui (strade principali, paesi, acqua, boschi; niente vie di quartiere). */
  const val CATALOG_TOP_ZOOM: Int = 12

  /** "La zona attorno a un luogo" di 60 km parte da qui. */
  const val WIDE_AROUND_TOP_ZOOM: Int = 13

  /** Sotto z10 la voce non si offre. */
  const val MIN_OFFER_ZOOM: Int = 10

  /** Dopo tanti giorni la pagina propone "Aggiorna" (solo a mano: un aggiornamento automatico rivelerebbe la zona a ogni giro). */
  const val UPDATE_AFTER_DAYS: Long = 182
}

/** Il centro di un luogo GeoNames (per "la zona attorno a un luogo"): una porta verso core-places (P5 la scrive, P13 la lega). Mai la casa. */
fun interface PlaceCenters {
  fun center(placeId: Int): GeoE6?

  /** Il luogo e' una citta' (la stima usa la densita' delle tessere di citta'). Di serie no. */
  fun urban(placeId: Int): Boolean = false

  /** Il nome del luogo per la pagina, se si conosce. */
  fun name(placeId: Int): String? = null
}

/** Un luogo trovato per nome nel dataset GeoNames del telefono: mai nei log. */
data class PlaceHit(val placeId: Int, val name: String, val detail: String?, val center: GeoE6, val urban: Boolean) {
  override fun toString(): String = "PlaceHit"
}

/** La ricerca di un luogo per nome (dataset GeoNames sul telefono, nessuna rete): per "la zona attorno a un luogo". Mai dalla casa ne' dai punti delle foto (D29). */
fun interface PlaceSearch {
  fun search(query: String, limit: Int): List<PlaceHit>
}

/** Nessuna ricerca (dataset non disponibile): nessun risultato. */
object NoPlaceSearch : PlaceSearch {
  override fun search(query: String, limit: Int): List<PlaceHit> = emptyList()
}

/** Le regioni offline di questo dispositivo (D26). */
interface MapRegions {
  val regions: StateFlow<List<RegionState>>

  /** Il catalogo fisso: le 20 regioni italiane e i paesi vicini (riquadri nel codice). "Attorno a un luogo" si costruisce con [around]. */
  fun catalog(): List<RegionSpec>

  /** La zona attorno al luogo [placeId] (centro dal luogo, mai dalla casa); null se il luogo non c'e'. */
  fun around(placeId: Int, radius: RegionRadius): RegionSpec?

  suspend fun estimate(spec: RegionSpec): RegionEstimate

  /** Scarica [spec] con la [confirmation] del foglio (D26): rete, spazio e tetti si controllano qui. */
  suspend fun download(spec: RegionSpec, confirmation: RegionDownloadConfirmation): DownloadStart

  /** Riprende una regione parziale (all'apertura dell'app, E-MAP11), con le stesse regole di rete. */
  suspend fun resume(id: RegionId): DownloadStart

  /** Ferma gli scaricamenti in corso (mappa spenta, E-MAP20); restano parziali e riprendono solo con un tocco. */
  suspend fun pauseAll()

  /**
   * L'apertura dell'app (E-MAP11, nessun lavoro in background): le regioni interrotte (processo morto, Wi-Fi caduto) riprendono da sole se la rete lo permette, una alla volta.
   * Non riprende quelle ferme dal limite, dalla mappa spenta, dal server o dallo spazio: quelle solo con un tocco. Mai chiamata prima del primo disegno.
   */
  suspend fun resumeInterrupted() = Unit

  /** L'azzeramento (D37): ferma lo scaricamento, toglie tutte le regioni (dallo stato e dal database) e da qui **non scrive piu'** niente. */
  suspend fun clear() = Unit

  /** Lo scaricamento confermato in corso, per il cancello (`MapNetWiring.downloadScope`): null se nessuno. Con il suo conto ([DownloadBudget]). */
  fun downloadScope(): RegionDownloadScope? = null

  suspend fun delete(id: RegionId)

  /** Se la tessera [tile] sta in una regione **completa** (per `MapNetState.covered`: mai chiesta online). */
  fun covers(tile: TileKey): Boolean
}
