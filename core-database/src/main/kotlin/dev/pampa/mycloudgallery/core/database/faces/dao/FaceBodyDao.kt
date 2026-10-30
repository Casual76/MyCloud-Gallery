package dev.pampa.mycloudgallery.core.database.faces.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceBodyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceUnsupportedEntity

// I DAO dei corpi `faces` (Fase 8, P6; `docs/modello-dati.md` §15). Semplici di proposito; le scritture che devono stare insieme sono
// metodi `@Transaction`. Le liste di chiavi (`IN (:keys)`) sono al piu' [FaceBodyDao.IN_LIMIT]: SQLite di Android 8-10 accetta al piu' 999
// variabili per istruzione, e chi chiama le spezza (`store/`). Mai valori nei log (D37).

/** Quanti volti non riconoscibili ha una chiave di contenuto (`FacesArchive.unrecognizable`). */
class FaceCountRow(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "count") val count: Int,
) {
  override fun toString(): String = "FaceCountRow($count)"
}

/** R6: quanti corpi e quanti volti (di ogni livello) ci sono, di un autore o in tutto. */
class FaceTotalsRow(
  @ColumnInfo(name = "bodies") val bodies: Int,
  @ColumnInfo(name = "faces") val faces: Long,
) {
  override fun toString(): String = "FaceTotalsRow($bodies, $faces)"
}

/** Le colonne di un corpo che bastano a scegliere il vincitore (senza il blocco sigillato). */
class FaceBodyHeader(
  @ColumnInfo(name = "author") val author: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "digest") val digest: Long,
) {
  override fun toString(): String = "FaceBodyHeader"
}

@Dao
abstract class FaceBodyDao {

  @Query("SELECT author, recipe, digest FROM face_body WHERE content_key = :key")
  abstract suspend fun headersOf(key: String): List<FaceBodyHeader>

  @Query("SELECT * FROM face_body WHERE content_key = :key ORDER BY author")
  abstract suspend fun bodiesOf(key: String): List<FaceBodyEntity>

  /** I corpi vincenti delle chiavi chieste (al piu' [IN_LIMIT]). */
  @Query("SELECT * FROM face_body WHERE is_winner = 1 AND content_key IN (:keys) ORDER BY content_key")
  abstract suspend fun winners(keys: List<String>): List<FaceBodyEntity>

  /** I corpi scritti da [author] per le chiavi chieste (al piu' [IN_LIMIT]), vincenti o no. */
  @Query("SELECT * FROM face_body WHERE author = :author AND content_key IN (:keys) ORDER BY content_key")
  abstract suspend fun bodiesOfAuthor(author: String, keys: List<String>): List<FaceBodyEntity>

  /** I volti dei corpi scritti da [author] per le chiavi chieste. */
  @Query("SELECT * FROM face WHERE author = :author AND content_key IN (:keys) ORDER BY content_key, idx")
  abstract suspend fun facesOfAuthor(author: String, keys: List<String>): List<FaceEntity>

  /** I volti di tutti i corpi delle chiavi chieste, in ordine; chi chiama tiene quelli dell'autore vincente. */
  @Query("SELECT * FROM face WHERE content_key IN (:keys) ORDER BY content_key, author, idx")
  abstract suspend fun facesOf(keys: List<String>): List<FaceEntity>

  /** Le chiavi che hanno un corpo (di un autore qualunque) con la ricetta [recipe] o piu' nuova. */
  @Query("SELECT DISTINCT content_key FROM face_body WHERE content_key IN (:keys) AND recipe >= :recipe")
  abstract suspend fun analyzedKeys(keys: List<String>, recipe: Int): List<String>

  /** Le chiavi "non supportate" con la ricetta [recipe] o piu' nuova. */
  @Query("SELECT content_key FROM face_unsupported WHERE content_key IN (:keys) AND recipe >= :recipe")
  abstract suspend fun unsupportedKeys(keys: List<String>, recipe: Int): List<String>

  /** Per ogni chiave chiesta, quanti volti del corpo **vincente** hanno il livello [tier] (i "non riconoscibili"). */
  @Query(
    "SELECT f.content_key AS content_key, COUNT(*) AS count FROM face f " +
      "JOIN face_body b ON b.content_key = f.content_key AND b.author = f.author " +
      "WHERE b.is_winner = 1 AND f.tier = :tier AND f.content_key IN (:keys) GROUP BY f.content_key ORDER BY f.content_key",
  )
  abstract suspend fun countByTier(keys: List<String>, tier: String): List<FaceCountRow>

  /** I corpi vincenti nello spazio [space], in ordine di chiave: l'ingresso del raggruppamento (l'indice evita l'ordinamento). */
  @Query("SELECT * FROM face_body WHERE is_winner = 1 AND space = :space ORDER BY content_key")
  abstract suspend fun winnersInSpace(space: String): List<FaceBodyEntity>

  /** I volti con un vettore (tutti tranne il livello [none]) dei corpi vincenti nello spazio [space], in ordine di chiave e indice. */
  @Query(
    "SELECT f.* FROM face_body b JOIN face f ON f.content_key = b.content_key AND f.author = b.author " +
      "WHERE b.is_winner = 1 AND b.space = :space AND f.tier <> :none ORDER BY f.content_key, f.idx",
  )
  abstract suspend fun searchableFaces(space: String, none: String): List<FaceEntity>

  @Query("SELECT COUNT(*) FROM face_body")
  abstract suspend fun bodyCount(): Int

  /** R6: quanti volti [searchableFaces] restituirebbe, senza leggerli (il tetto del raggruppamento si guarda prima di caricare). */
  @Query(
    "SELECT COUNT(*) FROM face_body b JOIN face f ON f.content_key = b.content_key AND f.author = b.author " +
      "WHERE b.is_winner = 1 AND b.space = :space AND f.tier <> :none",
  )
  abstract suspend fun countSearchableFaces(space: String, none: String): Int

  /** R6: i corpi e i volti scritti da [author] (l'indice per autore della v2). */
  @Query("SELECT COUNT(*) AS bodies, COALESCE(SUM(face_count), 0) AS faces FROM face_body WHERE author = :author")
  abstract suspend fun totalsOf(author: String): FaceTotalsRow

  /** R6: i corpi e i volti di [author] per le chiavi [keys] (al piu' [IN_LIMIT]). */
  @Query("SELECT COUNT(*) AS bodies, COALESCE(SUM(face_count), 0) AS faces FROM face_body WHERE author = :author AND content_key IN (:keys)")
  abstract suspend fun totalsOfAuthorKeys(author: String, keys: List<String>): FaceTotalsRow

  /** R6: i corpi e i volti di tutti gli autori per le chiavi [keys] (al piu' [IN_LIMIT]). */
  @Query("SELECT COUNT(*) AS bodies, COALESCE(SUM(face_count), 0) AS faces FROM face_body WHERE content_key IN (:keys)")
  abstract suspend fun totalsOfKeys(keys: List<String>): FaceTotalsRow

  /** R6: i corpi e i volti di tutti gli autori. */
  @Query("SELECT COUNT(*) AS bodies, COALESCE(SUM(face_count), 0) AS faces FROM face_body")
  abstract suspend fun totals(): FaceTotalsRow

  @Query("SELECT COUNT(*) FROM face")
  abstract suspend fun faceCount(): Int

  @Query("SELECT recipe FROM face_unsupported WHERE content_key = :key")
  abstract suspend fun unsupportedRecipe(key: String): Int?

  @Upsert
  abstract suspend fun upsertUnsupported(row: FaceUnsupportedEntity)

  @Upsert
  abstract suspend fun upsertBody(body: FaceBodyEntity)

  @Insert
  abstract suspend fun insertFaces(faces: List<FaceEntity>)

  @Query("DELETE FROM face WHERE content_key = :key AND author = :author")
  abstract suspend fun deleteFaces(key: String, author: String)

  /** Le chiavi il cui corpo vincente e' di uno di [authors]: togliendoli, il vincitore si rifa'. */
  @Query("SELECT content_key FROM face_body WHERE is_winner = 1 AND author IN (:authors)")
  abstract suspend fun winnerKeysOf(authors: List<String>): List<String>

  /** Toglie i corpi scritti da [authors] (i loro volti vanno in cascata); quanti corpi. */
  @Query("DELETE FROM face_body WHERE author IN (:authors)")
  abstract suspend fun deleteBodiesOf(authors: List<String>): Int

  @Query("UPDATE face_body SET is_winner = (author = :winner) WHERE content_key = :key")
  abstract suspend fun markWinner(key: String, winner: String)

  /**
   * Scrive il corpo di ([FaceBodyEntity.contentKey], [FaceBodyEntity.author]) al posto di quello che c'era (stessi autori sostituiscono) e
   * rifa' il vincitore della chiave, **in una transazione**: o c'e' tutto o non c'e' niente (una morte del processo a meta' non lascia un
   * corpo senza volti). Idempotente. Il vincitore e' `FaceBody.winner`: la ricetta piu' alta, poi l'autore minore, poi l'impronta minore
   * (la chiave primaria rende l'ultimo spareggio superfluo qui).
   */
  @Transaction
  open suspend fun replaceBody(body: FaceBodyEntity, faces: List<FaceEntity>) {
    deleteFaces(body.contentKey, body.author)
    upsertBody(body)
    if (faces.isNotEmpty()) insertFaces(faces)
    val winner = headersOf(body.contentKey).minWithOrNull(WINNER_ORDER)!!
    markWinner(body.contentKey, winner.author)
  }

  /**
   * Toglie i corpi scritti da [authors] (dispositivi revocati o persi, L99) e rifa' il vincitore delle chiavi che lo avevano di uno di loro,
   * **in una transazione**. Quanti corpi ha tolto. Idempotente. [authors] al piu' [IN_LIMIT].
   */
  @Transaction
  open suspend fun dropBodiesOf(authors: List<String>): Int {
    require(authors.size <= IN_LIMIT) { "Troppi autori per istruzione." }
    if (authors.isEmpty()) return 0
    val affected = winnerKeysOf(authors)
    val dropped = deleteBodiesOf(authors)
    for (key in affected) {
      val winner = headersOf(key).minWithOrNull(WINNER_ORDER) ?: continue
      markWinner(key, winner.author)
    }
    return dropped
  }

  /** "Non supportato" con la ricetta piu' alta vista: una ricetta piu' vecchia non abbassa quella che c'e'. */
  @Transaction
  open suspend fun markUnsupported(key: String, recipe: Int) {
    val current = unsupportedRecipe(key)
    if (current == null || recipe > current) upsertUnsupported(FaceUnsupportedEntity(key, recipe))
  }

  companion object {
    /** Il massimo di chiavi per istruzione (SQLite di Android 8-10: 999 variabili). */
    const val IN_LIMIT: Int = 500

    /** Il vincitore e' il **minimo** di quest'ordine: ricetta piu' alta, poi autore minore, poi impronta minore. */
    private val WINNER_ORDER: Comparator<FaceBodyHeader> =
      compareByDescending<FaceBodyHeader> { it.recipe }.thenBy { it.author }.thenBy { it.digest }
  }
}
