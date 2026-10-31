package dev.pampa.mycloudgallery.feature.map.regions

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.feature.map.R

// IL MODELLO DELLA PAGINA "ZONE SENZA INTERNET" (Fase 9, P5; docs/fase9-progetto.md §2.9, D26). Stati semplici che la pagina disegna e il controllore produce; nessuna logica di rete qui
// (le regole di rete, di spazio e di limite sono di `MapRegions`). Mai nei log: dicono le zone scaricate.

/** Una voce del catalogo con la sua stima ([estimate] null = non ancora calcolata) e, se c'e', la regione gia' scaricata. */
internal data class RegionOffer(val spec: RegionSpec, val estimate: RegionEstimate?, val present: RegionState?) {
  override fun toString(): String = "RegionOffer"
}

/** A che scopo si apre il foglio di conferma. */
internal enum class SheetPurpose { DOWNLOAD, UPDATE, RESUME }

/** I fogli della pagina: la conferma di uno scaricamento (solo da un tocco) e la gestione di una zona gia' scaricata. */
internal sealed interface RegionSheet {
  /**
   * La conferma: [estimate] e' cio' che si mostra ("circa 27 MB"); con [mobileBytes] non nullo e' la **seconda** conferma sui dati mobili (con la dimensione). Il foglio non
   * ha altro stato: la conferma vera ([dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation]) nasce dal tocco su "Scarica", in `RegionDownloadSheet.kt`.
   */
  data class Confirm(val spec: RegionSpec, val estimate: RegionEstimate, val purpose: SheetPurpose, val mobileBytes: Long? = null) : RegionSheet {
    override fun toString(): String = "RegionSheet.Confirm($purpose)"
  }

  /** Una zona scaricata: riprendere, aggiornare (solo a mano), eliminare (con una conferma). */
  data class Manage(val state: RegionState, val confirmDelete: Boolean = false) : RegionSheet {
    override fun toString(): String = "RegionSheet.Manage"
  }
}

/** L'esito di "Scarica" quando non parte: una riga sopra la pagina. */
internal sealed interface RegionMessage {
  data object TooLarge : RegionMessage

  data object NeedsWifi : RegionMessage

  data object Busy : RegionMessage

  data object Expired : RegionMessage

  data class NoSpace(val neededBytes: Long) : RegionMessage
}

/** Un raggio di "la zona attorno a un luogo" con la sua stima. */
internal data class RadiusOffer(val spec: RegionSpec.AroundPlace, val estimate: RegionEstimate?, val present: RegionState?) {
  val radius: RegionRadius get() = spec.radius

  override fun toString(): String = "RadiusOffer(${radius.km})"
}

/** La ricerca di un luogo: il testo, i risultati, il luogo scelto e i suoi tre raggi. [available] falso = il dataset dei luoghi non c'e' su questo telefono. */
internal data class AroundState(
  val query: String = "",
  val hits: List<PlaceHit> = emptyList(),
  val picked: PlaceHit? = null,
  val radii: List<RadiusOffer> = emptyList(),
  val available: Boolean = true,
  val searched: Boolean = false,
) {
  override fun toString(): String = "AroundState"
}

/** Tutto cio' che la pagina disegna. [around] non nullo = la ricerca del luogo al posto del catalogo. */
internal data class RegionsUi(
  val downloaded: List<RegionState> = emptyList(),
  val italian: List<RegionOffer> = emptyList(),
  val countries: List<RegionOffer> = emptyList(),
  val usedBytes: Long = 0,
  val sheet: RegionSheet? = null,
  val message: RegionMessage? = null,
  val around: AroundState? = null,
) {
  override fun toString(): String = "RegionsUi(scaricate=${downloaded.size})"
}

/** Le frasi sui numeri: dimensioni arrotondate per eccesso ("27 MB", "1,2 GB") e livello di dettaglio. */
internal object RegionText {

  fun size(resources: Resources, bytes: Long): String {
    if (bytes >= 1_000_000_000L) return resources.getString(R.string.regions_gb, "%.1f".format(java.util.Locale.ITALIAN, bytes / 1e9))
    return resources.getString(R.string.regions_mb, ((bytes + 999_999L) / 1_000_000L).coerceAtLeast(1L).toInt())
  }

  fun approx(resources: Resources, bytes: Long): String = resources.getString(R.string.regions_size_approx, size(resources, bytes))

  /** Il livello di dettaglio di una zona scaricata fino a z[maxZoom]. */
  fun detail(resources: Resources, maxZoom: Int): String = resources.getString(
    when {
      maxZoom <= 10 -> R.string.regions_detail_low
      maxZoom <= 12 -> R.string.regions_detail_medium
      else -> R.string.regions_detail_high
    },
  )

  fun stop(resources: Resources, stop: RegionStop?): String = resources.getString(
    when (stop) {
      null, RegionStop.INTERRUPTED -> R.string.regions_stop_interrupted
      RegionStop.NETWORK -> R.string.regions_stop_network
      RegionStop.MAP_OFF -> R.string.regions_stop_map_off
      RegionStop.SERVER -> R.string.regions_stop_server
      RegionStop.SPACE -> R.string.regions_stop_space
      RegionStop.LIMIT_TILES, RegionStop.LIMIT_BYTES -> R.string.regions_stop_limit
    },
  )

  /** Il nome di una zona per la pagina: il nome del catalogo, o "Luogo, 30 km" (mai un nome vuoto). */
  fun title(resources: Resources, spec: RegionSpec): String = when (spec) {
    is RegionSpec.Named -> spec.name.ifBlank { resources.getString(R.string.regions_around_row) }
    is RegionSpec.AroundPlace -> resources.getString(R.string.regions_around_item, spec.name.ifBlank { resources.getString(R.string.regions_around_row) }, spec.radius.km)
  }
}
