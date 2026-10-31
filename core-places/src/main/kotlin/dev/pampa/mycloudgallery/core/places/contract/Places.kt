package dev.pampa.mycloudgallery.core.places.contract

import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.GeoPoint
import java.time.ZoneId

/**
 * L'identificativo di un luogo: l'id GeoNames. E' un numero del dataset, non un nome: i nomi cambiano con la
 * lingua e con l'edizione del dump, l'id no. Nel database e nell'indice della Libreria un luogo e' solo questo
 * intero (`place_id`, 0 = nessun luogo): i nomi li risolve la UI con [PlaceNames].
 */
@JvmInline
value class PlaceId(val value: Int) {
  init {
    require(value > 0) { "Id di luogo non valido." }
  }
}

/**
 * Un luogo del dataset offline (docs/fase3-progetto.md §2.3).
 *
 * @property name il nome in italiano quando esiste ("Londra", "Monaco di Baviera"), altrimenti quello originale.
 * @property admin1 la regione o provincia ("Toscana"), se nota.
 * @property countryCode il codice ISO 3166-1 alfa-2 ("IT").
 * @property countryName il nome del paese in italiano, da `Locale.ITALIAN` ("Italia").
 * @property zone l'identificativo IANA del fuso orario del luogo ("Europe/Rome"): e' cio' che risolve E-M14 per i
 *   video, la cui data e' in UTC, e le foto senza fuso. Null se il dataset non lo sa.
 */
data class Place(
  val id: PlaceId,
  val name: String,
  val admin1: String?,
  val countryCode: String,
  val countryName: String,
  val zone: String?,
)

/**
 * Un luogo trovato **per nome** (Fase 9, P5: "la zona attorno a un luogo"): l'id GeoNames, il nome, la regione e il paese, il centro in gradi e la popolazione (approssimata: il centro del suo intervallo).
 * Mai nei log: dice un luogo. Niente a che fare con i punti delle foto ne' con la casa: si trova scrivendone il nome.
 */
class PlaceCandidate(
  val id: PlaceId,
  val name: String,
  val admin1: String?,
  val countryName: String,
  val latitude: Double,
  val longitude: Double,
  val population: Double,
) {
  override fun toString(): String = "PlaceCandidate"
}

/**
 * Quanto il luogo trovato e' vicino al punto cercato. Decide cosa si puo' dire senza mentire: "Firenze" per una
 * foto scattata in centro, solo "Toscana" per una foto in mezzo ai monti.
 */
enum class PlacePrecision {
  /** Entro 15 km da un luogo abitato (almeno 1000 abitanti o capoluogo): si puo' dire il nome del luogo. */
  CITY,

  /** Entro 50 km: "vicino a ...". */
  NEAR,

  /** Troppo lontano da un luogo, ma si conosce la regione. */
  REGION_ONLY,

  /** Si conosce solo il paese (oceano, deserto, confine non risolto). */
  COUNTRY_ONLY,
}

/** Il luogo piu' vicino a un punto, con la distanza e la [precision] da cui dipende cosa mostrare. */
data class PlaceMatch(
  val place: Place,
  val distanceM: Int,
  val precision: PlacePrecision,
)

/**
 * Dal punto GPS al luogo, **tutto sul telefono**: il dataset e' un file nell'app (D8), nessun servizio online. Il
 * punto e' un dato sensibile ([GeoPoint]): l'implementazione non lo scrive in nessun log e non lo conserva.
 */
interface ReverseGeocoder {

  /**
   * La versione del dataset in uso (cresce a ogni nuova edizione dei dati). Si scrive in `PlaceRef.dataset`: quando
   * cambia, la manutenzione ricalcola i luoghi (100 mila ricerche in meno di 1 s).
   */
  val dataset: Int

  /** Il luogo piu' vicino a [p], o null se il dataset non ne ha uno entro i limiti ragionevoli. */
  fun nearest(p: GeoPoint): PlaceMatch?

  /** Un luogo per id, o null se l'id non e' (piu') nel dataset. */
  fun place(id: PlaceId): Place?
}

/**
 * Il fuso orario di un punto, dal luogo piu' vicino. Serve a `CaptureTimeResolver` per le date EXIF senza fuso ma
 * con GPS, e per i video (E-M14). Null se non c'e' un luogo abbastanza vicino: il chiamante ripiega sui vicini o
 * sul fuso del telefono alla lettura.
 */
interface TimeZoneLookup {
  fun zoneAt(p: GeoPoint): ZoneId?
}

/**
 * I nomi dei luoghi **per la UI**. `core-library` non li conosce (tiene gli id nell'indice): le intestazioni della
 * griglia ("Firenze e Fiesole"), il pannello info e l'accessibilita' li ottengono da qui.
 */
interface PlaceNames {

  /** Il nome del luogo di [ref], o null se il dataset non lo conosce piu'. */
  fun label(ref: PlaceRef): String?

  /** Come [label], ma per l'id nudo dell'indice (`place_id`); 0 o un id sconosciuto danno null. */
  fun labelOfId(geonameId: Int): String?

  /**
   * La frase per l'intestazione di una sezione: [top] sono gli id dei luoghi piu' frequenti (in ordine decrescente
   * di frequenza) e [distinct] quanti luoghi diversi ci sono in tutto.
   *
   * - un luogo: "Firenze";
   * - due: "Firenze e Fiesole";
   * - tre o piu': "Firenze e altri 2 luoghi" (il primo, e quanti sono gli altri).
   *
   * Stringa vuota se non ci sono luoghi noti.
   */
  fun summary(top: IntArray, distinct: Int): String
}

/**
 * L'attribuzione dei dati dei luoghi, da mostrare in Impostazioni → Informazioni e licenze. **GeoNames e' CC BY 4.0**:
 * la licenza chiede l'attribuzione e che si dica che i dati sono stati modificati (qui: ridotti, tradotti in
 * italiano e convertiti in un formato compatto).
 */
object PlacesAttribution {

  /** Il testo dell'attribuzione. */
  const val TEXT: String =
    "Nomi e fusi orari dei luoghi: dati di GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0. " +
      "I dati sono stati modificati: ridotti, in parte tradotti in italiano e convertiti in un formato compatto."

  /** La licenza. Si mostra come testo e si apre solo con un'azione esplicita dell'utente. */
  const val LICENSE_URL: String = "https://creativecommons.org/licenses/by/4.0/"

  /** Il nome della sorgente. */
  const val SOURCE_NAME: String = "GeoNames"

  /** La sigla della licenza. */
  const val LICENSE_NAME: String = "CC BY 4.0"

  // --- La mappa (Fase 9, P2; docs/fase9-progetto.md §2.7, D22, D25) -----------------------------------------------------------
  // Stesse parole nelle risorse di Informazioni e licenze (`about_map_*` di feature-settings): `MapAttributionTest` di feature-map le confronta.

  /** La riga breve da mostrare sulla mappa ONLINE: obbligo di ODbL (OpenStreetMap) e di OpenMapTiles. */
  const val MAP_ONLINE_SHORT: String = "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap"

  /** La riga breve da mostrare sulla carta di base, quella senza Internet. */
  const val MAP_BASE_SHORT: String = "Natural Earth · GeoNames"

  /** Il testo completo sulle tessere online (Impostazioni → Informazioni e licenze). */
  const val MAP_ONLINE_TEXT: String =
    "Mappa online: tessere di OpenFreeMap (openfreemap.org) con dati di OpenStreetMap, © OpenStreetMap contributors, " +
      "licenza ODbL 1.0, nello schema di OpenMapTiles (openmaptiles.org). Si usa solo con la mappa online accesa."

  /** Il testo completo sulla carta di base: Natural Earth e le città di GeoNames (CC BY 4.0, quindi «modificati» va detto). */
  const val MAP_BASE_TEXT: String =
    "Carta di base senza Internet: dati di Natural Earth (naturalearthdata.com), pubblico dominio, semplificati; " +
      "città e nomi dei luoghi da GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0, ridotti e in parte tradotti in italiano."

  /** Il testo su stile, caratteri e icone (BSD 3-Clause e CC BY 4.0 per lo stile, SIL OFL 1.1 per i caratteri, CC0 per le icone). */
  const val MAP_STYLE_TEXT: String =
    "Stile della mappa: derivato da Positron e Dark di OpenFreeMap e OpenMapTiles (codice BSD 3-Clause, disegno CC BY 4.0; " +
      "Positron deriva da CartoDB Basemaps, CC BY 3.0), modificato: livelli ridotti, colori Ametista. " +
      "Caratteri: Noto Sans, SIL Open Font License 1.1. Icone: Maki, CC0."

  /** La licenza dei dati di OpenStreetMap. Si mostra come testo e non si apre da sola. */
  const val MAP_ODBL_URL: String = "https://opendatacommons.org/licenses/odbl/1-0/"
}
