package dev.pampa.mycloudgallery.debugtools.probes

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import dev.pampa.mycloudgallery.R
import java.io.FileNotFoundException

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-14): il **provider dei documenti di prova**. Compare nei selettori di documenti (Gmail "Allega file",
 * "Documento" di WhatsApp e Telegram) come "MCG prova" e offre i media delle cartelle di prova con nomi finti. Conta le interrogazioni di DocumentsUI
 * (radici, figli, documenti, aperture) e, all'apertura, serve il file nella forma scelta ([FieldProbeForm]) misurando come lo legge l'altra app.
 * Esportato, ma protetto da `MANAGE_DOCUMENTS` come il provider vero (solo il sistema lo interroga). Solo in `app/src/debug`.
 */
class FieldProbeDocumentsProvider : DocumentsProvider() {

  override fun onCreate(): Boolean = true

  private fun server(): FieldProbeServer = FieldProbeServer.of(requireNotNull(context))

  private fun store(): FieldProbeStore = FieldProbeStore.of(requireNotNull(context))

  override fun queryRoots(projection: Array<out String>?): Cursor {
    store().add(FieldProbeKey.T14_DOCS_ROOT_QUERIES)
    val cursor = MatrixCursor(projection?.map { it }?.toTypedArray() ?: DEFAULT_ROOT_PROJECTION)
    cursor.newRow()
      .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
      .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
      .add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
      .add(DocumentsContract.Root.COLUMN_TITLE, ROOT_TITLE)
      .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
      .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
    return cursor
  }

  override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
    store().add(FieldProbeKey.T14_DOCS_DOC_QUERIES)
    val cursor = MatrixCursor(projection?.map { it }?.toTypedArray() ?: DEFAULT_DOC_PROJECTION)
    if (documentId == ROOT_DOC) {
      cursor.newRow()
        .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOC)
        .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_TITLE)
        .add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        .add(DocumentsContract.Document.COLUMN_SIZE, null)
        .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
      return cursor
    }
    val row = documentId?.toLongOrNull()?.let { server().rowOf(it) } ?: throw FileNotFoundException("Nessun documento.")
    add(cursor, row)
    return cursor
  }

  override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
    store().add(FieldProbeKey.T14_DOCS_CHILD_QUERIES)
    if (parentDocumentId != ROOT_DOC) throw FileNotFoundException("Nessuna cartella.")
    val cursor = MatrixCursor(projection?.map { it }?.toTypedArray() ?: DEFAULT_DOC_PROJECTION)
    FieldProbePhone(requireNotNull(context)).rows().filter { !it.isTrashed }.take(MAX_CHILDREN).forEach { add(cursor, it) }
    return cursor
  }

  override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
    store().add(FieldProbeKey.T14_DOCS_OPENS)
    if (mode != null && mode != "r") throw FileNotFoundException("Solo lettura.")
    val row = documentId?.toLongOrNull()?.let { server().rowOf(it) } ?: throw FileNotFoundException("Nessun documento.")
    return server().open(row, FieldProbeCaller.codeOf(callingPackage))
  }

  override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
    parentDocumentId == ROOT_DOC && documentId?.toLongOrNull() != null

  private fun add(cursor: MatrixCursor, row: ProbeRow) {
    cursor.newRow()
      .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, row.id.toString())
      .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, server().displayNameOf(row))
      .add(DocumentsContract.Document.COLUMN_MIME_TYPE, row.mime ?: "application/octet-stream")
      .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
      .add(DocumentsContract.Document.COLUMN_SIZE, row.size)
      .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, row.dateModifiedSec * MILLIS_PER_SECOND)
  }

  private companion object {
    const val ROOT_ID = "mcg-prova"
    const val ROOT_DOC = "root"
    const val ROOT_TITLE = "MCG prova"
    const val MAX_CHILDREN = 200
    const val MILLIS_PER_SECOND = 1000L

    val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
      DocumentsContract.Root.COLUMN_ROOT_ID,
      DocumentsContract.Root.COLUMN_FLAGS,
      DocumentsContract.Root.COLUMN_ICON,
      DocumentsContract.Root.COLUMN_TITLE,
      DocumentsContract.Root.COLUMN_DOCUMENT_ID,
      DocumentsContract.Root.COLUMN_MIME_TYPES,
    )

    val DEFAULT_DOC_PROJECTION: Array<String> = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      DocumentsContract.Document.COLUMN_FLAGS,
      DocumentsContract.Document.COLUMN_SIZE,
    )
  }
}
