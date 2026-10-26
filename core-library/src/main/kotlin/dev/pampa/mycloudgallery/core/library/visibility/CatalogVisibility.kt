package dev.pampa.mycloudgallery.core.library.visibility

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.FamilyExposure
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// Fase 7 (P9, D22): le implementazioni sul catalogo vero delle firme di P0 (`VisibleMedia`, `HiddenMask`) e della porta dell'avviso E-H2
// (`FamilyExposure`). **Una sola fonte** dei nascosti per ogni superficie nuova: album, copertine, "Suggerite", viaggi, Utility, Doppioni,
// "Aggiungi ad album", widget. Le superfici di prima (Libreria, ricerca, selettore) restano sulla loro strada, con il loro filtro in SQL, e
// stanno nel registro `HiddenSurfaces` di `:app` con la prova che un nascosto non esce mai.

private const val TAG = "Visibility"

/**
 * Il filtro finale (D22) sul catalogo: quali fra i media chiesti sono nascosti adesso.
 *
 * Nascosto = nascosto lui (`media_personal`), **oppure** uno dei membri del suo gruppo (copie simili, raffica, RAW+JPEG: il nascosto e'
 * contagioso, F5-D36, E-L11), **oppure** unito a un media che lo e'. **Fallisce chiuso** (D22): un id che il catalogo non conosce conta come
 * nascosto, e un errore di lettura rende nascosti **tutti** gli id chiesti (mai un nascosto che esce per un guasto).
 */
class CatalogHiddenMask(database: GalleryDatabase) : HiddenMask {

  private val queries = VisibilityQueries(database)

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    return try {
      val answer = queries.answer(ids)
      ids.filterTo(LinkedHashSet()) { answer.isHidden(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG, e) { "Filtro dei nascosti non riuscito: tutto si tratta come nascosto." }
      ids.toSet()
    }
  }
}

/**
 * La vista del catalogo senza nascosti (D22). L'[snapshot] e' quello della Libreria (che i nascosti non li ha, E-H1, e ha una cella per
 * gruppo); [visible] risponde per un insieme di id con le stesse regole di [CatalogHiddenMask] e in piu' vuole che il media sia **presente** in
 * un posto: un id sconosciuto, sparito da ovunque o nascosto non e' visibile. Un errore di lettura non rende visibile niente.
 */
class CatalogVisibleMedia(
  database: GalleryDatabase,
  override val snapshot: StateFlow<TimelineSnapshot?>,
) : VisibleMedia {

  private val queries = VisibilityQueries(database)

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    return try {
      val answer = queries.answer(ids)
      ids.filterTo(LinkedHashSet()) { answer.isVisible(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG, e) { "Visibilita' non letta: niente si mostra." }
      emptySet()
    }
  }
}

/**
 * Quali media i familiari continuano a vedere (E-H2): quelli con una copia **nel NAS** (presente, o al momento irraggiungibile) in una share che non e' un'area privata (l'area comune;
 * una share di cui il catalogo non conosce l'area conta come non privata, come per "al sicuro"). Gli elementi degli album condivisi stanno li'
 * (D9): quando arriveranno, la loro appartenenza si aggiunge alla stessa risposta. Gli id uniti a un altro media si risolvono.
 */
class CatalogFamilyExposure(private val database: GalleryDatabase) : FamilyExposure {

  private val queries = VisibilityQueries(database)

  override suspend fun seenByFamily(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    val answer = queries.answer(ids)
    val targets = answer.known.toList()
    val exposed = withContext(Dispatchers.IO) {
      val db = database.openHelper.readableDatabase
      val out = HashSet<String>()
      for (chunk in targets.chunked(VISIBILITY_CHUNK)) {
        query(
          db,
          "SELECT DISTINCT v.media_id FROM media_version v JOIN media_location l ON l.version_id = v.version_id " +
            "LEFT JOIN nas_share s ON s.account_id = l.account_id AND s.share_key = l.share_key " +
            "WHERE v.media_id IN (${marks(chunk)}) AND l.location_type = 'nas' AND l.state IN ('present', 'unavailable') " +
            "AND (s.area IS NULL OR s.area <> 'private')",
          chunk,
        ) { c -> out += c.getString(0) }
      }
      out
    }
    return ids.filterTo(LinkedHashSet()) { id -> answer.target[id]?.let { it in exposed } == true }
  }
}
