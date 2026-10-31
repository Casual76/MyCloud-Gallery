package dev.pampa.mycloudgallery.core.map.regions

import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec

// IL CATALOGO FISSO DELLE REGIONI (Fase 9, P5; docs/fase9-progetto.md §2.9, D26; docs/decisioni.md "Fase 9 P5"). Le 20 regioni italiane e i paesi vicini (Francia, Svizzera, Austria,
// Slovenia, San Marino, Vaticano), con il riquadro **fisso nel codice**: il riquadro di ogni voce e' il rettangolo che contiene il territorio, arrotondato a 0,1 gradi **verso
// l'esterno** (ovest e sud per difetto, est e nord per eccesso). Mai una zona proposta dalla casa o dai punti delle foto (D29, C156): il catalogo e' questo elenco, e "la zona attorno a un
// luogo" parte da un luogo scelto per nome dall'utente. Un test (`RegionCatalogTest`) fissa gli id, i riquadri e che nessuna voce dipende da dati dell'utente.

/**
 * Come e' fatta una zona, per la stima dei MB: la quota di tessere di **citta'** (dense), di **campagna** e di **mare** (tessere quasi vuote). Sono quote delle tessere dei livelli da
 * z10 in su; sotto z10 il mare conta come terra. Le somme fanno 1. Vedi [RegionDensity] per le densita' calibrate.
 */
data class ZoneMix(val urban: Double, val rural: Double, val sea: Double) {
  init {
    require(urban >= 0 && rural >= 0 && sea >= 0 && StrictMath.abs(urban + rural + sea - 1.0) < 1e-9) { "Le quote di una zona fanno 1." }
  }

  companion object {
    val RURAL = ZoneMix(0.0, 1.0, 0.0)
    val URBAN = ZoneMix(1.0, 0.0, 0.0)
  }
}

object RegionCatalog {

  /** Una voce del catalogo: la regione, con il nome per la pagina, e come e' fatta (per la stima). */
  class Entry(val spec: RegionSpec.Named, val mix: ZoneMix)

  private fun e6(degrees: Double): Int = StrictMath.round(degrees * 1_000_000.0).toInt()

  private fun region(code: String, name: String, kind: RegionKind, west: Double, south: Double, east: Double, north: Double, urban: Double, rural: Double, sea: Double) =
    Entry(RegionSpec.Named(RegionId(code), kind, GeoBox(e6(west), e6(south), e6(east), e6(north)), name), ZoneMix(urban, rural, sea))

  private fun italian(code: String, name: String, w: Double, s: Double, e: Double, n: Double, urban: Double, rural: Double, sea: Double) =
    region("it-$code", name, RegionKind.ITALIAN_REGION, w, s, e, n, urban, rural, sea)

  private fun country(code: String, name: String, w: Double, s: Double, e: Double, n: Double, urban: Double, rural: Double, sea: Double) =
    region("country-$code", name, RegionKind.COUNTRY, w, s, e, n, urban, rural, sea)

  /**
   * Le voci, nell'ordine in cui la pagina le mostra (le regioni italiane in ordine alfabetico, poi i paesi). Le quote di citta', campagna e mare sono **approssimazioni**
   * a occhio del territorio dentro il riquadro (la pianura e le grandi citta' pesano di piu', le Alpi e il mare di meno); solo Marche e le zone dei campioni sono state
   * verificate su tessere vere (docs/decisioni.md "Fase 9 P5"). Per questo l'offerta usa un fattore di sicurezza ([dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits.ESTIMATE_SAFETY])
   * e lo scaricamento si ferma comunque ai byte veri.
   */
  val ENTRIES: List<Entry> = listOf(
    italian("abruzzo", "Abruzzo", 13.0, 41.6, 14.8, 43.0, 0.03, 0.87, 0.10),
    italian("basilicata", "Basilicata", 15.3, 39.9, 16.9, 41.2, 0.0, 0.90, 0.10),
    italian("calabria", "Calabria", 15.6, 37.9, 17.2, 40.2, 0.04, 0.66, 0.30),
    italian("campania", "Campania", 13.7, 39.9, 15.9, 41.6, 0.15, 0.55, 0.30),
    italian("emilia-romagna", "Emilia-Romagna", 9.2, 43.7, 12.8, 45.2, 0.20, 0.75, 0.05),
    italian("friuli-venezia-giulia", "Friuli-Venezia Giulia", 12.3, 45.5, 14.0, 46.7, 0.10, 0.75, 0.15),
    italian("lazio", "Lazio", 11.4, 40.7, 13.9, 42.9, 0.12, 0.70, 0.18),
    italian("liguria", "Liguria", 7.5, 43.7, 10.1, 44.7, 0.15, 0.40, 0.45),
    italian("lombardia", "Lombardia", 8.5, 44.6, 11.5, 46.7, 0.25, 0.75, 0.0),
    italian("marche", "Marche", 12.1, 42.6, 14.0, 44.0, 0.03, 0.82, 0.15),
    italian("molise", "Molise", 13.9, 41.3, 15.2, 42.1, 0.0, 0.92, 0.08),
    italian("piemonte", "Piemonte", 6.6, 44.0, 9.3, 46.5, 0.10, 0.90, 0.0),
    italian("puglia", "Puglia", 14.9, 39.7, 18.6, 42.3, 0.08, 0.62, 0.30),
    italian("sardegna", "Sardegna", 8.1, 38.8, 9.9, 41.4, 0.03, 0.77, 0.20),
    italian("sicilia", "Sicilia", 11.9, 35.4, 15.7, 38.4, 0.06, 0.64, 0.30),
    italian("toscana", "Toscana", 9.6, 42.2, 12.4, 44.5, 0.08, 0.77, 0.15),
    italian("trentino-alto-adige", "Trentino-Alto Adige", 10.4, 45.6, 12.5, 47.1, 0.0, 1.0, 0.0),
    italian("umbria", "Umbria", 11.9, 42.3, 13.3, 43.7, 0.05, 0.95, 0.0),
    italian("valle-d-aosta", "Valle d'Aosta", 6.8, 45.4, 8.0, 46.0, 0.0, 1.0, 0.0),
    italian("veneto", "Veneto", 10.6, 44.7, 13.2, 46.7, 0.20, 0.70, 0.10),
    country("fr", "Francia", -5.3, 41.3, 9.7, 51.2, 0.12, 0.70, 0.18),
    country("ch", "Svizzera", 5.9, 45.8, 10.6, 47.9, 0.12, 0.88, 0.0),
    country("at", "Austria", 9.5, 46.3, 17.2, 49.1, 0.06, 0.94, 0.0),
    country("si", "Slovenia", 13.3, 45.4, 16.6, 46.9, 0.05, 0.95, 0.0),
    country("sm", "San Marino", 12.4, 43.8, 12.6, 44.0, 0.30, 0.70, 0.0),
    country("va", "Città del Vaticano", 12.4, 41.8, 12.5, 42.0, 1.0, 0.0, 0.0),
  )

  private val byId: Map<RegionId, Entry> = ENTRIES.associateBy { it.spec.id }

  /** Le voci come specifiche, nell'ordine della pagina. */
  fun specs(): List<RegionSpec> = ENTRIES.map { it.spec }

  fun entryOf(id: RegionId): Entry? = byId[id]

  /** Come e' fatta la zona [spec] (per la stima): quella del catalogo, o per "attorno a un luogo" quella del luogo e del raggio ([aroundMix]). */
  fun mixOf(spec: RegionSpec): ZoneMix = when (spec) {
    is RegionSpec.Named -> byId[spec.id]?.mix ?: ZoneMix.RURAL
    is RegionSpec.AroundPlace -> aroundMix(spec.urban, spec.radius)
  }

  /**
   * "La zona attorno a un luogo": campagna per un luogo piccolo; attorno a una citta' i 15 km sono tutti "citta'" come nel campione misurato (Bologna), i 30 km per meta' e i 60 km
   * per un quarto (**ipotesi**: un solo campione di citta' misurato). Mare: zero (un luogo di costa e' sovrastimato, che e' il verso prudente).
   */
  fun aroundMix(urban: Boolean, radius: RegionRadius): ZoneMix {
    if (!urban) return ZoneMix.RURAL
    val share = when (radius) {
      RegionRadius.KM_15 -> 1.0
      RegionRadius.KM_30 -> 0.5
      RegionRadius.KM_60 -> 0.25
    }
    return ZoneMix(share, 1.0 - share, 0.0)
  }
}
