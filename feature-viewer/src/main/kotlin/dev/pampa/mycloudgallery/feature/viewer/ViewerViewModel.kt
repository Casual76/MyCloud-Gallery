package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Cio' che il pannello informazioni ha letto per un media. */
@Immutable
internal class LoadedInfo(
  val details: MediaDetails?,
  val exif: ExifDetails?,
  val place: Place?,
)

/**
 * Il ViewModel del viewer: **sopravvive alla rotazione e alla ricreazione dell'activity** (E-V8) e tiene cio' che costa
 * leggere due volte: i dettagli del catalogo e l'EXIF dei media guardati di recente. Una rotazione con il pannello
 * aperto non rilegge niente dal file.
 *
 * Lo stato che deve tornare dopo una ricreazione (l'id corrente, le barre, il pannello, lo zoom) sta in
 * `rememberSaveable` dentro `ViewerOverlay`: e' li' che lo ritrova anche dopo la morte del processo. Qui c'e' solo
 * una cache, che puo' andare persa senza conseguenze.
 *
 * Le dipendenze arrivano da [ViewerServices], non da Hilt: `:app` le costruisce dal suo grafo e le passa al composable
 * (P11). Il costruttore resta senza argomenti perche' il modulo non pretenda binding che il grafo non ha.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor() : ViewModel() {

  private val details = Lru<MediaDetails?>(CACHE_SIZE)
  private val exif = Lru<ExifDetails>(CACHE_SIZE)

  /** Quante letture dei dettagli e dell'EXIF sono arrivate fino alla sorgente (le altre sono state servite dalla cache). */
  var sourceReads: Int = 0
    private set

  /**
   * Legge i dettagli di [id] dal catalogo e, con [wantExif], l'EXIF dal file. Le chiavi della cache includono la sequenza
   * del catalogo [seq] per i dettagli (cambiano con ogni scrittura) e il peso del file per l'EXIF (cambia con ogni
   * modifica del file).
   */
  internal suspend fun load(services: ViewerServices, id: MediaId, ref: MediaRef, seq: Long, wantExif: Boolean): LoadedInfo =
    withContext(services.dispatchers.io) {
      val detailsKey = "${id.value}#$seq"
      val loaded = details.getOrLoad(detailsKey) {
        sourceReads++
        readDetails(services, id)
      }
      val exifValue = if (wantExif) {
        val exifKey = "${id.value}#${loaded?.sizeBytes}"
        exif.getOrLoad(exifKey) {
          sourceReads++
          readExif(services, ref)
        }
      } else {
        null
      }
      val place = loaded?.place?.let { runCatching { services.geocoder.place(PlaceId(it.geonameId)) }.getOrNull() }
      LoadedInfo(details = loaded, exif = exifValue, place = place)
    }

  /**
   * I dettagli del catalogo, o null se non si leggono: una riga che il catalogo rifiuta (un valore fuori forma) toglie
   * le informazioni, **non** chiude l'app. L'eccezione arrivava fino al `LaunchedEffect` del viewer, senza nessuno che
   * la prendesse, e il processo moriva al primo fotogramma dell'apertura (S25, 13/10).
   */
  private suspend fun readDetails(services: ViewerServices, id: MediaId): MediaDetails? {
    return try {
      services.details.details(id)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun readExif(services: ViewerServices, ref: MediaRef): ExifDetails {
    return try {
      val bytes = services.bytes.open(ref) ?: return ExifDetails()
      services.exif.exifDetails(bytes)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // Un file che non si legge non ha EXIF: il pannello salta la sezione, non si ferma.
      ExifDetails()
    }
  }

  /** Una cache a capienza fissa, la meno usata esce per prima. Un solo thread alla volta (il blocco e' breve). */
  private class Lru<V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, Box<V>>(capacity, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Box<V>>?): Boolean = size > capacity
    }

    private class Box<V>(val value: V)

    suspend fun getOrLoad(key: String, load: suspend () -> V): V {
      synchronized(map) { map[key] }?.let { return it.value }
      val value = load()
      synchronized(map) { map[key] = Box(value) }
      return value
    }
  }

  private companion object {
    const val CACHE_SIZE = 24
  }
}
