package dev.pampa.mycloudgallery.core.database.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Una riga della query dei punti della mappa (Fase 9, P3, D27): un media **visibile** con le sue coordinate effettive, il giorno locale, i flag della
 * cella e quanto serve al punteggio della copertina (sorgente, tipo, durata) e all'etichetta (luogo). `core-database` non vede `core-map`: la riga ha la
 * forma di `MapPointRaw` campo per campo e l'adattatore di `:app` (P13) la traduce senza logica (i codici di sorgente e di tipo diventano le
 * enumerazioni). Coordinate e id non compaiono in `toString()` (D33).
 */
class MapPointRow(
  val mediaId: String,
  val latitude: Double,
  val longitude: Double,
  val localDay: Int,
  val cellFlags: Int,
  val source: String,
  val kind: String,
  val durationMs: Long?,
  val placeId: Int?,
) {
  override fun toString(): String = "MapPointRow"
}

/**
 * La query dei punti della mappa (Fase 9, P3; `docs/fase9-progetto.md` §2.10, D27, D31; E-MAP5, E-MAP6, E-MAP8): le **celle visibili** del catalogo
 * con coordinate effettive, a pagine (il cursore e' l'id dell'ultimo media letto), e il conteggio dei visibili senza posizione. **Schema invariato:
 * nessuna tabella e nessun indice nuovo.**
 *
 * - **Coordinate effettive = quelle del pannello info** (`CatalogMediaDetailsSource`): la riga `version_metadata` della **versione corrente**. La
 *   correzione di luogo solo nell'app (`metadata_correction`) vi e' gia' sopra: `MediaCatalog.correctInApp` la scrive nella stessa transazione e
 *   `updateMetadata` la rimette a ogni rilettura di META. Una regola sola (D27): chi mostra il luogo e chi lo mette sulla mappa leggono lo stesso
 *   campo. Anche i media solo sul NAS: i loro metadati (EXIF letto dal NAS o record adottato) stanno nella stessa riga.
 * - **Solo celle visibili**: presente in qualche posto, non nascosto (da `media_personal`, mai da un derivato), non dietro la copertina del suo gruppo
 *   ([AlbumQueries.VISIBLE]) **e** con il gruppo senza nessun nascosto ([NOT_IN_HIDDEN_GROUP]: un gruppo con un nascosto e' nascosto per intero,
 *   anche se il segno non e' arrivato a tutti i membri, D31). Il predicato e' scritto qui e non preso da `AlbumQueries.NOT_IN_HIDDEN_GROUP` (che dice lo stesso)
 *   perche' quello parte dai nascosti (`media_personal` per `hidden`) a ogni media di un gruppo: con 100 mila media il piano senza statistiche scorre i
 *   nascosti e quello con le statistiche tutta `media_personal`. Questo parte dai membri del gruppo (`index_media_group_id`) e cerca il nascosto per
 *   chiave primaria.
 * - **Senza `USE TEMP B-TREE`, in ordine d'inserimento**: si cammina la tabella `media` per `rowid` (`rowid > ?`, `ORDER BY rowid`) e per ogni riga si
 *   cerca per chiave primaria `media_personal` e `version_metadata` (`CROSS JOIN`: l'ordine dei cicli e' fisso, il pianificatore non puo' partire dai
 *   metadati). **Non per `media_id`** (come diceva il progetto): le righe di `media` e di `version_metadata` nascono insieme, quindi in ordine d'inserimento
 *   la lettura di entrambe e' quasi sequenziale; in ordine di id (UUID casuali) ogni riga e' un salto in due tabelle e una lettura di 100 mila righe
 *   costava il doppio (misura nel test e in `docs/decisioni.md`). Chi legge non dipende dall'ordine: l'indice ordina per chiave di Morton e le sue regole
 *   (copertina, membri, luogo) sono ordini totali che non guardano l'ordine d'arrivo. Il piano lo guarda `MapPointsQueriesTest`, con le statistiche e senza.
 * - **Casa**: la query non legge `home_estimate` ne' niente che la nomini; le foto di casa sono punti come gli altri (E-MAP18).
 * - Tutti i valori sono parametri legati; nessun dato dell'utente nell'SQL e nessuno nei log (questa classe non ne scrive).
 */
class MapPointsQueries(private val database: GalleryDatabase) {

  /**
   * Fino a [limit] righe visibili con coordinate, **dopo** il media [after] (null = dall'inizio), in ordine d'inserimento: il cursore e' l'id dell'ultima
   * riga della pagina prima. Fuori dal thread principale (gira su `Dispatchers.IO`).
   *
   * Se il media [after] non c'e' piu' (cancellato fra due pagine) la lettura non puo' continuare e **non** da' una pagina vuota (sarebbe un indice
   * monco): lancia [IllegalStateException]; l'indice dei punti si rifa al cambio di istantanea che quella cancellazione ha portato.
   */
  suspend fun page(after: MediaId?, limit: Int): List<MapPointRow> {
    require(limit in 1..MAX_PAGE) { "Pagina fuori misura." }
    return withContext(Dispatchers.IO) {
      val db = database.openHelper.readableDatabase
      val cursor = if (after == null) 0L else rowIdOf(after)
      db.query(SimpleSQLiteQuery(PAGE_SQL, pageArgs(cursor, limit))).use { c ->
        val rows = ArrayList<MapPointRow>(minOf(limit, 2_048))
        while (c.moveToNext()) {
          rows += MapPointRow(
            mediaId = c.getString(0),
            latitude = c.getDouble(1),
            longitude = c.getDouble(2),
            localDay = c.getInt(3),
            cellFlags = c.getInt(4),
            source = c.getString(5),
            kind = c.getString(6),
            durationMs = if (c.isNull(7)) null else c.getLong(7),
            placeId = if (c.isNull(8)) null else c.getInt(8),
          )
        }
        rows
      }
    }
  }

  /** Il `rowid` di [media], per ripartire da li'; [IllegalStateException] se non c'e' piu'. */
  private fun rowIdOf(media: MediaId): Long =
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT rowid FROM media WHERE media_id = ?", arrayOf<Any?>(media.value))).use { c ->
      check(c.moveToFirst()) { "Il cursore della lettura dei punti non c'e' piu'." }
      c.getLong(0)
    }

  /** Quante celle visibili non hanno coordinate (E-MAP5: "1.240 foto senza posizione"). */
  suspend fun countWithoutPosition(): Int = withContext(Dispatchers.IO) {
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery(WITHOUT_POSITION_SQL)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
  }

  companion object {
    /** Al piu' tante righe per pagina (la mappa ne chiede 2.000: `MapPoints.PAGE_SIZE`). */
    const val MAX_PAGE: Int = 10_000

    /** Le colonne di [MapPointRow], in quest'ordine. */
    const val COLUMNS: String =
      "m.media_id AS media_id, vm.latitude AS latitude, vm.longitude AS longitude, m.local_day AS local_day, m.cell_flags AS cell_flags, " +
        "m.source AS source, m.kind AS kind, m.duration_ms AS duration_ms, m.place_id AS place_id"

    /**
     * Il gruppo del media non ha nessun nascosto (D31). Solo per i media che hanno un gruppo (gli altri non valutano la sotto-interrogazione): dal
     * gruppo (`index_media_group_id`) ai suoi membri e, per ciascuno, il nascosto per chiave primaria.
     */
    const val NOT_IN_HIDDEN_GROUP: String =
      "(m.group_id IS NULL OR NOT EXISTS (SELECT 1 FROM media gm WHERE gm.group_id = m.group_id AND " +
        "EXISTS (SELECT 1 FROM media_personal gp WHERE gp.media_id = gm.media_id AND gp.hidden = 1)))"

    /** La cella e' una di quelle da mostrare: visibile e con il gruppo senza nascosti. */
    const val VISIBLE_CELL: String = "${AlbumQueries.VISIBLE} AND $NOT_IN_HIDDEN_GROUP"

    /** Testo della pagina (pubblico per il test che ne guarda il piano): due parametri, il `rowid` del cursore (0 = dall'inizio) e il limite. */
    const val PAGE_SQL: String =
      "SELECT $COLUMNS FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "CROSS JOIN version_metadata vm ON vm.version_id = m.current_version_id " +
        "WHERE m.rowid > ? AND $VISIBLE_CELL AND vm.latitude IS NOT NULL AND vm.longitude IS NOT NULL " +
        "ORDER BY m.rowid LIMIT ?"

    /** Testo del conteggio dei visibili senza posizione. */
    const val WITHOUT_POSITION_SQL: String =
      "SELECT COUNT(*) FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "LEFT JOIN version_metadata vm ON vm.version_id = m.current_version_id " +
        "WHERE $VISIBLE_CELL AND (vm.latitude IS NULL OR vm.longitude IS NULL)"

    /** I parametri di [PAGE_SQL]: il `rowid` dopo cui ripartire (0 = dall'inizio) e il limite. */
    fun pageArgs(afterRowId: Long, limit: Int): Array<Any?> = arrayOf(afterRowId, limit)
  }
}
