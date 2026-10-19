package dev.pampa.mycloudgallery.debugtools.probes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-14): il provider di **file di prova**. Il selettore di prova ([FieldProbePickerActivity]) risponde con
 * indirizzi `content://<id app>.fieldprobe/<_ID>` quando la forma scelta e' "a richiesta" o "copia in cache": l'altra app (Gmail, WhatsApp...) li
 * apre qui, e [FieldProbeServer] misura come li legge. Non e' esportato: gli indirizzi si aprono solo con il permesso che il selettore da' a quell'app.
 * Serve solo righe delle cartelle di prova, in sola lettura, con un nome finto (`probe-<_ID>.jpg`).
 */
class FieldProbeFilesProvider : ContentProvider() {

  override fun onCreate(): Boolean = true

  private fun server(): FieldProbeServer = FieldProbeServer.of(requireNotNull(context))

  private fun store(): FieldProbeStore = FieldProbeStore.of(requireNotNull(context))

  private fun idOf(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
    store().add(FieldProbeKey.T14_SERVE_QUERIES)
    val row = idOf(uri)?.let { server().rowOf(it) } ?: return null
    val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val values: Array<Any?> = Array(columns.size) { i ->
      when (columns[i]) {
        OpenableColumns.DISPLAY_NAME -> server().displayNameOf(row)
        OpenableColumns.SIZE -> row.size
        else -> null
      }
    }
    return MatrixCursor(columns).apply { addRow(values) }
  }

  override fun getType(uri: Uri): String? {
    store().add(FieldProbeKey.T14_SERVE_TYPE_QUERIES)
    return idOf(uri)?.let { server().rowOf(it) }?.mime
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw FileNotFoundException("Solo lettura.")
    val row = idOf(uri)?.let { server().rowOf(it) } ?: throw FileNotFoundException("Nessun file di prova.")
    return server().open(row, FieldProbeCaller.codeOf(callingPackage))
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Sola lettura.")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Sola lettura.")

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("Sola lettura.")

  companion object {
    /** L'autorita' del provider, la stessa del manifest di debug (`${applicationId}.fieldprobe`). */
    fun authority(context: Context): String = context.packageName + ".fieldprobe"

    /** L'indirizzo di prova di una riga. */
    internal fun uriOf(context: Context, row: ProbeRow): Uri = Uri.parse("content://${authority(context)}/${row.id}")
  }
}
