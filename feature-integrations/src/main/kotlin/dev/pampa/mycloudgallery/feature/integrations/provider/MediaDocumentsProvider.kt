package dev.pampa.mycloudgallery.feature.integrations.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

/**
 * Il provider dei documenti (Fase 5B, D23, C17; docs/fase5-progetto.md §2.7): la radice "MyCloud Gallery" (Recenti, Cartelle NAS) nei selettori di
 * documenti (Gmail "Allega file", "Documento" di WhatsApp e Telegram). Esportato ma protetto da `MANAGE_DOCUMENTS` (solo DocumentsUI), con id
 * opachi, mai percorsi, e mai i nascosti. **Scheletro di P0-B**: non ha radici e non apre nessun documento. Una radice vuota non compare nei
 * selettori; `openDocument` e le interrogazioni di un documento rispondono "non c'e'" (`FileNotFoundException`), come per un id che non e'
 * nostro.
 *
 * P10 lo riempie qui (radici e cartelle dal catalogo, descrittore a richiesta sopra `NasMediaReader` con `StorageManager.openProxyFileDescriptor`
 * e scadenze, E-X1) leggendo le porte di `IntegrationPorts`, senza toccare il manifest.
 */
class MediaDocumentsProvider : DocumentsProvider() {

  override fun onCreate(): Boolean = true

  override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

  override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor =
    throw FileNotFoundException("Nessun documento.")

  override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor =
    throw FileNotFoundException("Nessun documento.")

  override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor =
    throw FileNotFoundException("Nessun documento.")

  private companion object {
    val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
      DocumentsContract.Root.COLUMN_ROOT_ID,
      DocumentsContract.Root.COLUMN_FLAGS,
      DocumentsContract.Root.COLUMN_ICON,
      DocumentsContract.Root.COLUMN_TITLE,
      DocumentsContract.Root.COLUMN_DOCUMENT_ID,
    )
  }
}
