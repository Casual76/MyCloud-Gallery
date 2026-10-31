package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlaceMatch
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import dev.pampa.mycloudgallery.core.places.dataset.PlacesData
import dev.pampa.mycloudgallery.core.places.dataset.PlacesOpenResult
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import dev.pampa.mycloudgallery.core.places.noop.NoTimeZoneLookup
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lo stato del dataset dei luoghi, per la Diagnostica (solo enum). */
enum class PlacesStatus { OPENING, AVAILABLE, NOT_BUNDLED, FAILED }

/**
 * I luoghi offline (P4) **senza bloccare l'avvio**: `PlacesDataset.openResult` estrae e mappa un file (bloccante), quindi
 * si apre una volta su un thread di lavoro e, finche' non e' pronto, ogni domanda ha la risposta dei null-object (nessun
 * luogo, nessun fuso, nessun nome). E' anche il comportamento a regime quando l'asset GeoNames **non si apre** (l'asset
 * `places.mcgp1` e' nell'APK, con l'impronta fissata in `PlacesDatasetPin`: `NotBundled` vuol dire una build senza
 * asset, `Failed` un asset alterato o illeggibile): la Libreria funziona senza luoghi e le intestazioni escono senza riga
 * del luogo, mai vuote o rotte.
 *
 * Un solo oggetto per i tre contratti, come `PlacesData` (stesso file).
 */
class LazyPlaces(
  scope: CoroutineScope,
  io: CoroutineDispatcher,
  open: () -> PlacesOpenResult,
) : ReverseGeocoder, TimeZoneLookup, PlaceNames {

  @Volatile
  private var data: PlacesData? = null

  private val statusFlow = MutableStateFlow(PlacesStatus.OPENING)

  /** Come e' andata l'apertura. */
  val status: StateFlow<PlacesStatus> = statusFlow.asStateFlow()

  /** Il motivo del fallimento, se c'e' (solo enum). */
  @Volatile
  var failure: PlacesOpenResult.Reason? = null
    private set

  init {
    scope.launch(io) {
      val result = try {
        open()
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "luoghi non aperti: ${e.javaClass.simpleName}" }
        PlacesOpenResult.Failed(PlacesOpenResult.Reason.IO_ERROR)
      }
      when (result) {
        is PlacesOpenResult.Opened -> {
          data = result.data
          statusFlow.value = PlacesStatus.AVAILABLE
        }
        PlacesOpenResult.NotBundled -> statusFlow.value = PlacesStatus.NOT_BUNDLED
        is PlacesOpenResult.Failed -> {
          failure = result.reason
          statusFlow.value = PlacesStatus.FAILED
        }
      }
    }
  }

  /** I dati dei luoghi se sono aperti (per la ricerca per nome delle zone senza Internet, P5: `AppPlaceFinder`), altrimenti null. */
  val opened: PlacesData? get() = data

  private val geocoder: ReverseGeocoder get() = data ?: NoReverseGeocoder
  private val zones: TimeZoneLookup get() = data ?: NoTimeZoneLookup
  private val names: PlaceNames get() = data ?: NoPlaceNames

  override val dataset: Int get() = geocoder.dataset

  override fun nearest(p: GeoPoint): PlaceMatch? = geocoder.nearest(p)

  override fun place(id: PlaceId): Place? = geocoder.place(id)

  override fun zoneAt(p: GeoPoint): ZoneId? = zones.zoneAt(p)

  override fun label(ref: PlaceRef): String? = names.label(ref)

  override fun labelOfId(geonameId: Int): String? = names.labelOfId(geonameId)

  override fun summary(top: IntArray, distinct: Int): String = names.summary(top, distinct)

  private companion object {
    const val TAG = "Places"
  }
}
