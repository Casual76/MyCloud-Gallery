package dev.pampa.mycloudgallery.map

import android.os.StatFs
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.PlaceCenters
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.PlaceSearch
import dev.pampa.mycloudgallery.core.places.contract.PlaceCandidate
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.dataset.PlacesData
import java.io.File
import java.time.LocalDate

// LE PORTE DELLE REGIONI OFFLINE LEGATE A CIO' CHE L'APP HA GIA' (Fase 9, P5; docs/fase9-progetto.md §2.9, D26, D29). La ricerca del luogo per "la zona attorno a un luogo" e il suo centro vengono dal dataset
// GeoNames che l'app ha gia' sul telefono (`PlacesData`, mai la rete), **solo per nome**: mai dalla casa ne' dai punti delle foto (C156). Lo spazio libero e il giorno sono i due numeri che il gestore delle regioni
// non puo' leggere da solo (core-map non conosce Android ne' l'orologio). Nei log solo tipi.

/** Un luogo e' una "citta'" (la stima usa la densita' delle tessere di citta') da questa popolazione in su: la stessa soglia della carta di base (citta' da 50.000 abitanti). */
private const val URBAN_POPULATION = 50_000.0

/**
 * La ricerca dei luoghi per nome e il loro centro, sopra `PlacesData` (aperto pigramente da `LazyPlaces`: [places] e' null finche' non e' pronto o se l'asset non c'e', e allora la pagina dice che l'elenco dei luoghi
 * non e' disponibile). Un luogo con coordinate che la mappa non accetta (oltre +-85 gradi) non si offre.
 */
class AppPlaceFinder(private val places: () -> PlacesData?) : PlaceSearch, PlaceCenters {

  override val available: Boolean get() = places() != null

  override fun search(query: String, limit: Int): List<PlaceHit> = places()?.search(query, limit)?.mapNotNull(::hitOf).orEmpty()

  override fun center(placeId: Int): GeoE6? = locate(placeId)?.let { MapPointValidity.toE6(it.latitude, it.longitude) }

  override fun urban(placeId: Int): Boolean = (locate(placeId)?.population ?: 0.0) >= URBAN_POPULATION

  override fun name(placeId: Int): String? = locate(placeId)?.name

  private fun locate(placeId: Int): PlaceCandidate? = if (placeId > 0) places()?.locate(PlaceId(placeId)) else null

  private fun hitOf(c: PlaceCandidate): PlaceHit? {
    val center = MapPointValidity.toE6(c.latitude, c.longitude) ?: return null
    val detail = listOfNotNull(c.admin1, c.countryName).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    return PlaceHit(c.id.value, c.name, detail, center, urban = c.population >= URBAN_POPULATION)
  }

  override fun toString(): String = "AppPlaceFinder"
}

/** Le due letture di sistema delle regioni: lo spazio libero dove stanno i dati (`noBackupFilesDir`) e il giorno (`aaaammgg`, per "completata il" e i 6 mesi). */
object RegionsSystem {
  private const val TAG = "MapRegions"

  /** Lo spazio libero in byte nella cartella [dir]; **zero** se non si legge (si fallisce chiusi: "non c'e' spazio" e' meglio di un disco pieno). */
  fun freeBytes(dir: File): Long = try {
    StatFs(dir.path).availableBytes
  } catch (e: RuntimeException) {
    GalleryLog.w(TAG) { "spazio libero non letto (${e.javaClass.simpleName})" }
    0L
  }

  /** Oggi come `aaaammgg` (il giorno del telefono: nessun altro legame con l'orologio). */
  fun today(): Int = LocalDate.now().let { it.year * 10_000 + it.monthValue * 100 + it.dayOfMonth }
}
