package dev.pampa.mycloudgallery.core.map.regions

import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.TileMath

// LA STIMA E IL PIANO DI UNA REGIONE (Fase 9, P5; docs/fase9-progetto.md §2.9, D26; decisione dell'utente del 31/10: restare dentro i limiti di OpenFreeMap; docs/decisioni.md "Fase 9 P5").
// Funzioni pure: le tessere si contano come le conta lo scaricamento (`TileMath`, una definizione sola con la copertura), il peso viene da una tabella di densita' calibrata su tessere
// vere. Il piano sceglie il livello piu' alto che sta nei limiti.

/**
 * La densita' delle tessere di OpenFreeMap, in byte **sul filo** (compressi) per tessera, calibrata il 31/10 su tre campioni veri scaricati dal PC alle condizioni d'uso del servizio
 * (4 richieste insieme, User-Agent di sviluppo che identifica l'app; 2.809 tessere, 72,5 MB in tutto): le **Marche** da z0 a z12 (731 tessere, 24,26 MB), **15 km attorno a Bologna**
 * da z0 a z14 (484 tessere, 17,09 MB) e **30 km attorno a Sulmona** da z0 a z14 (1.594 tessere, 31,18 MB). Versione del server 20261025_080012_pt. I numeri e gli errori della stima
 * sui campioni stanno in `docs/decisioni.md` "Fase 9 P5" e li prova `RegionPlannerTest`.
 *
 * Fino a z6 le tessere sono quelle "di tutto il mondo" (una per livello sopra l'Italia, grosse): la stessa tabella per ogni zona. Da z7 a z14: la media delle tessere di **citta'**
 * (Bologna) e di **campagna** (Marche e Sulmona, solo terra: le tessere sotto 3 KB a z10-z12 sono mare o vuote e fanno la voce [SEA]); a z13 e z14 la campagna e' solo Sulmona (montagna: una
 * campagna di pianura o di colline con paesi pesa di piu', il fattore di sicurezza dell'offerta ne tiene conto).
 */
object RegionDensity {
  /** z0..z6: le tessere di tutto il mondo sopra l'Italia (medie sui campioni). */
  private val LOW = longArrayOf(48_003, 126_962, 724_967, 529_139, 407_105, 316_388, 176_155)

  /** z7..z14, tessere di citta' (campione: 15 km attorno a Bologna). */
  private val CITY = longArrayOf(181_052, 204_322, 214_138, 129_363, 71_823, 41_690, 21_808, 24_841)

  /** z7..z14, tessere di campagna, solo terra (campioni: Marche, 30 km attorno a Sulmona). */
  private val COUNTRY = longArrayOf(155_646, 130_387, 117_357, 65_669, 48_800, 27_520, 17_164, 15_353)

  /** Una tessera di mare (o vuota) da z10 in su: poche centinaia di byte (campione: costa delle Marche, media 509 a z12 e 717 a z11). */
  const val SEA: Long = 500

  /** Byte per tessera di livello [z] (0..14) in una zona fatta come [mix]. */
  fun bytesPerTile(z: Int, mix: ZoneMix): Double {
    require(z in 0..14) { "Livello fuori dalla tabella." }
    if (z < LOW.size) return LOW[z].toDouble()
    val landWeight = mix.urban + mix.rural
    val urbanShare = if (landWeight <= 0.0) 0.0 else mix.urban / landWeight
    val land = urbanShare * CITY[z - 7] + (1.0 - urbanShare) * COUNTRY[z - 7]
    return if (z >= 10) (1.0 - mix.sea) * land + mix.sea * SEA else land
  }
}

object RegionPlanner {

  /** La stima di [box] fatta come [mix] da z0 a z[maxZoom]: tessere e byte (arrotondati all'intero). */
  fun estimate(box: GeoBox, mix: ZoneMix, maxZoom: Int): RegionEstimate {
    require(maxZoom in RegionLimits.MIN_ZOOM..RegionLimits.MAX_ZOOM) { "Livello massimo fuori intervallo." }
    var tiles = 0L
    var bytes = 0.0
    for (z in 0..maxZoom) {
      val count = TileMath.count(box, z, z)
      tiles += count
      bytes += count * RegionDensity.bytesPerTile(z, mix)
    }
    return RegionEstimate(tiles, StrictMath.round(bytes), maxZoom)
  }

  /** Il livello da cui si parte per [spec] (poi si scende finche' ci sta): z12 per regioni e paesi; z14 attorno a un luogo di 15 e 30 km, z13 di 60 km. */
  fun topZoom(spec: RegionSpec): Int = when (spec) {
    is RegionSpec.Named -> RegionLimits.CATALOG_TOP_ZOOM
    is RegionSpec.AroundPlace -> if (spec.radius == RegionRadius.KM_60) RegionLimits.WIDE_AROUND_TOP_ZOOM else RegionLimits.MAX_ZOOM
  }

  /** Se una zona di [tiles] tessere e [bytes] byte stimati sta nei limiti di OpenFreeMap: al piu' 2.900 tessere (3.000 meno il margine per le richieste doppie) e, con il fattore di sicurezza, 50 MB. */
  fun fits(tiles: Long, bytes: Long): Boolean =
    tiles <= OpenFreeMapLimits.PLAN_MAX_TILES && bytes * OpenFreeMapLimits.ESTIMATE_SAFETY <= OpenFreeMapLimits.MAX_BYTES

  /**
   * Il piano di [spec]: il livello piu' alto (da [topZoom] in giu', fino a z[RegionLimits.MIN_OFFER_ZOOM]) che sta nei limiti, con le sue tessere e la sua stima. Se nemmeno z10 ci
   * sta la voce **non si offre** (`offered` falso, con i numeri di z10). Una zona che attraversa l'antimeridiano non si offre (il catalogo non ci arriva; un luogo cosi' e' un caso
   * senza uso).
   */
  fun plan(spec: RegionSpec): RegionEstimate = plan(spec.box, RegionCatalog.mixOf(spec), topZoom(spec))

  /** Il piano di un riquadro fatto come [mix], da [top] in giu' (la stessa regola di [plan] per qualunque zona). */
  fun plan(box: GeoBox, mix: ZoneMix, top: Int): RegionEstimate {
    var zoom = top
    while (true) {
      val estimate = estimate(box, mix, zoom)
      if (!box.crossesAntimeridian && fits(estimate.tiles, estimate.bytes)) return estimate
      if (zoom <= RegionLimits.MIN_OFFER_ZOOM) return estimate.copy(offered = false)
      zoom--
    }
  }
}
