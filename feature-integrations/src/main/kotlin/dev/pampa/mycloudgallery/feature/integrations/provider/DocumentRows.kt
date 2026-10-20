package dev.pampa.mycloudgallery.feature.integrations.provider

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentRoot

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23): come un documento e la radice diventano righe per il selettore di documenti di sistema.
// Funzioni pure: le prova `DocumentRowsTest` senza provider.
//
// I documenti sono **di sola lettura**: nessun flag di creazione, scrittura, cancellazione, rinomina, copia, spostamento, nessuna miniatura
// (le anteprime dei documenti sono un'estensione, non una porta in piu') e nessun "recente" a livello di sistema (`FLAG_SUPPORTS_RECENTS`
// metterebbe le nostre foto fra i recenti del selettore senza passare dallo sblocco). La radice non e' "locale": i byte possono stare sul NAS.

object DocumentRows {

  /** Le colonne dei documenti quando il chiamante non ne chiede (come `DocumentsContract`). */
  val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_LAST_MODIFIED,
    Document.COLUMN_FLAGS,
    Document.COLUMN_SIZE,
  )

  val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
    Root.COLUMN_ROOT_ID,
    Root.COLUMN_FLAGS,
    Root.COLUMN_ICON,
    Root.COLUMN_TITLE,
    Root.COLUMN_SUMMARY,
    Root.COLUMN_DOCUMENT_ID,
    Root.COLUMN_MIME_TYPES,
  )

  /** I flag di una cartella: la griglia, per i media. Mai flag di scrittura. */
  const val FOLDER_FLAGS: Int = Document.FLAG_DIR_PREFERS_GRID or Document.FLAG_DIR_PREFERS_LAST_MODIFIED

  /** I tipi che la radice offre: foto e video, e nient'altro. */
  const val ROOT_MIME_TYPES: String = "image/*\nvideo/*"

  /** Le righe dei documenti [entries]; [error] (se c'e') finisce negli extra, dove il selettore di sistema lo mostra come messaggio della cartella. */
  fun documents(projection: Array<out String>?, entries: List<DocumentEntry>, error: String? = null): MatrixCursor {
    val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
    val cursor = MatrixCursor(columns, entries.size.coerceAtLeast(1))
    for (entry in entries) {
      val row = cursor.newRow()
      for (column in columns) row.add(column, valueOf(column, entry))
    }
    if (error != null) cursor.extras = Bundle().apply { putString(android.provider.DocumentsContract.EXTRA_ERROR, error) }
    return cursor
  }

  /** La riga della radice. [summary] e' la frase sotto il titolo (l'app bloccata); [icon] e' la risorsa dell'icona dell'app. */
  fun roots(projection: Array<out String>?, roots: List<DocumentRoot>, title: String, summary: String?, icon: Int): MatrixCursor {
    val columns = projection ?: DEFAULT_ROOT_PROJECTION
    val cursor = MatrixCursor(columns, roots.size.coerceAtLeast(1))
    for (root in roots) {
      val row = cursor.newRow()
      for (column in columns) {
        row.add(
          column,
          when (column) {
            Root.COLUMN_ROOT_ID -> root.rootId
            Root.COLUMN_DOCUMENT_ID -> root.rootDocument.value
            Root.COLUMN_TITLE -> title
            Root.COLUMN_SUMMARY -> summary
            Root.COLUMN_ICON -> icon
            Root.COLUMN_FLAGS -> 0
            Root.COLUMN_MIME_TYPES -> ROOT_MIME_TYPES
            else -> null
          },
        )
      }
    }
    return cursor
  }

  private fun valueOf(column: String, entry: DocumentEntry): Any? = when (column) {
    Document.COLUMN_DOCUMENT_ID -> entry.id.value
    Document.COLUMN_MIME_TYPE -> entry.mime
    Document.COLUMN_DISPLAY_NAME -> entry.displayName
    Document.COLUMN_LAST_MODIFIED -> entry.modifiedAtMillis
    Document.COLUMN_FLAGS -> if (entry.isFolder) FOLDER_FLAGS else 0
    Document.COLUMN_SIZE -> if (entry.isFolder || entry.size == dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry.UNKNOWN_SIZE) null else entry.size
    else -> null
  }
}
