package dev.pampa.mycloudgallery.memories

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.PowerManager
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.pick.PixelMeasure
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.notify.NotifyThumbnails
import dev.pampa.mycloudgallery.utility.SearchShotVectors
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// LE PORTE DEI RICORDI LEGATE A CIO' CHE L'APP HA GIA' (Fase 9, P13; docs/fase9-progetto.md §1.4 `MemoriesGraph`, D4-D7, D36; "per P13" di P6, P7, P8, P10).
// Tutte **locali**: nessuna rete, nessun NAS (D4). Le miniature si leggono solo dal telefono (`MediaRef.Phone`): un media solo sul NAS non ha misure dei
// pixel, ne' miniatura nella notifica (mai un'attesa sul NAS, E-MEM14). Nessuna di queste nomina i contratti dei Nascosti: la visibilita' la da' l'adattatore
// di `MemoriesWiring` (nell'elenco `wiring` del registro). Nei log solo nomi di classi e numeri.

/** L'ora dei ricordi (D36): "oggi" da `Today` (lo stesso della ricerca e degli album), l'ora locale del telefono, l'orologio monotono per budget e durate. */
class AppMemoryClock(
  private val today: Today,
  private val now: () -> LocalDateTime = { LocalDateTime.now() },
  private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : MemoryClock {
  override fun today(): LocalDate = today.date()

  override fun nowLocal(): LocalDateTime = now()

  override fun monotonicMs(): Long = monotonic()
}

/**
 * I viaggi e gli eventi per i ricordi (P8): lo **stesso** `CatalogTrips` che e' gia' la porta `Trips` degli album (`TripMemories.past`), campo per campo, senza
 * l'id (la porta di core-memories non ne ha bisogno) e senza coordinate (non ne ha nessuna, `TripPrivacyTest`). Album non montati (nessun NAS): nessun viaggio.
 */
class AlbumsMemoryTrips(private val albums: AlbumsGraph) : MemoryTrips {
  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
    val trips = albums.ensureLive()?.trips ?: return emptyList()
    return trips.past(until, budget).map {
      TripDigestView(it.kind, DayRange(it.days.from, it.days.to), it.label, it.placeIds, it.members, it.album, it.startQuarter)
    }
  }

  override fun toString(): String = "AlbumsMemoryTrips"
}

/** I vettori della ricerca (Fase 6) per doppioni e varieta' (D7): gli stessi dei Doppioni simili, un vettore per scatto, mai sul thread principale (E-MEM16). */
class SearchMemoryVectors(private val vectors: SearchShotVectors) : MemoryVectors {
  override suspend fun vectors(ids: Collection<MediaId>): Map<MediaId, FloatArray> = vectors.vectorsOf(ids)

  override fun toString(): String = "SearchMemoryVectors"
}

/**
 * La miniatura **locale** di un media (P7, P10): solo dal telefono (`MediaRef.Phone`, MediaStore), mai il NAS ne' la rete. [phone] e' la sorgente delle
 * miniature del telefono (`ImagingPipeline.thumbnailSource(context)` senza NAS); [refs] i riferimenti del catalogo.
 */
class LocalThumbs(
  private val phone: ThumbnailSource,
  private val refs: suspend (Collection<MediaId>) -> Map<MediaId, MediaRef.Phone>,
) {
  suspend fun bitmap(id: MediaId, px: Int): Bitmap? {
    val ref = refs(listOf(id))[id] ?: return null
    return load(ref, px)
  }

  suspend fun refsOf(ids: Collection<MediaId>): Map<MediaId, MediaRef.Phone> = refs(ids)

  suspend fun load(ref: MediaRef, px: Int): Bitmap? = try {
    phone.load(ref, px, CancellationSignal())
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }
}

/**
 * Le misure dei pixel (D6, P7): la miniatura **locale** a [PixelMeasure.TARGET_SIDE] px sul lato lungo, passata a [PixelMeasure.probeArgb]. Mai il NAS,
 * mai la rete; entro il [budget] del giro (il raccoglitore di P7 chiede al piu' 200 finalisti). **Con il risparmio energetico la porta tace** (P6, punto 4):
 * il motore usa allora il punteggio senza misure.
 */
class LocalMemoryPixels(
  private val thumbs: LocalThumbs,
  private val powerSave: () -> Boolean,
  private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : MemoryPixels {
  override suspend fun probe(ids: Collection<MediaId>, budget: Duration): Map<MediaId, PixelProbe> {
    if (ids.isEmpty() || powerSave()) return emptyMap()
    val until = monotonic() + budget.inWholeMilliseconds
    val refs = thumbs.refsOf(ids)
    val out = HashMap<MediaId, PixelProbe>()
    withContext(Dispatchers.Default) {
      for (id in ids) {
        if (monotonic() >= until) break
        val ref = refs[id] ?: continue
        val bitmap = thumbs.load(ref, PixelMeasure.TARGET_SIDE) ?: continue
        try {
          val w = bitmap.width
          val h = bitmap.height
          if (maxOf(w, h) > PixelMeasure.MAX_SIDE) continue
          val pixels = IntArray(w * h)
          bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
          PixelMeasure.probeArgb(pixels, w, h)?.let { out[id] = it }
        } finally {
          bitmap.recycle()
        }
      }
    }
    return out
  }

  override fun toString(): String = "LocalMemoryPixels"

  companion object {
    /** Il risparmio energetico di Android, letto a ogni giro. */
    fun powerSaveOf(power: PowerManager?): () -> Boolean = { power?.isPowerSaveMode == true }
  }
}

/** La miniatura della notifica "Titolo e foto" (P10): la copertina **locale**, mai il NAS (senza: la notifica parte senza foto). */
class LocalNotifyThumbnails(private val thumbs: LocalThumbs) : NotifyThumbnails {
  override suspend fun localThumbnail(id: MediaId, sizePx: Int): Bitmap? = thumbs.bitmap(id, sizePx)
}

/**
 * I ritagli del soggetto (`Ritagli/`, F8-D18) esclusi dai ricordi (D5): i media del telefono nella cartella dei ritagli di adesso ([folder]: quella di serie, o
 * in debug quella recintata). Pochi: si chiedono tutti. Un guasto **lancia** (il motore non deve credere "nessun ritaglio" per un database che non risponde).
 */
class CatalogMemoryCutouts(
  private val database: GalleryDatabase,
  private val folder: () -> String,
) : MemoryCutouts {
  override suspend fun cutouts(): Set<MediaId> = withContext(Dispatchers.IO) {
    val out = HashSet<MediaId>()
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery(SQL, arrayOf<Any?>(folder()))).use { c ->
      while (c.moveToNext()) out += MediaId(c.getString(0))
    }
    out
  }

  override fun toString(): String = "CatalogMemoryCutouts"

  private companion object {
    const val SQL = "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.location_type = 'phone' AND l.state = 'present' AND l.relative_path = ?"
  }
}

/** I riferimenti del telefono dal catalogo (null finche' il catalogo non c'e'). */
fun catalogPhoneRefs(catalog: suspend () -> MediaCatalog?): suspend (Collection<MediaId>) -> Map<MediaId, MediaRef.Phone> = { ids ->
  try {
    catalog()?.phoneRefs(ids).orEmpty()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.w("Memories") { "riferimenti del telefono non letti: ${e.javaClass.simpleName}" }
    emptyMap()
  }
}
