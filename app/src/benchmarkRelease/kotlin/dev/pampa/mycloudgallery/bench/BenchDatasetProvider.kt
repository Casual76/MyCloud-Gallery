package dev.pampa.mycloudgallery.bench

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * L'inizializzatore del dataset della variante di misura (docs/fase3-progetto.md §2.6, "Dati del benchmark"). Esiste
 * **solo** in `src/benchmarkRelease/` (D21): nessun'altra variante lo contiene e `tools/check.ps1` cerca nel dex di
 * release qualunque classe `Bench*`.
 *
 * E' un `ContentProvider` (non esportato, senza dati) perche' il sistema lo crea prima di `Application.onCreate`, cioe'
 * prima del grafo Hilt, e perche' il manifest lo conserva da R8 senza regole in piu'. Fa una cosa sola: se il marcatore
 * [BenchDataset.MARKER] sta in `getExternalFilesDir`, avvia su un thread proprio la creazione del database sintetico
 * ([BenchDataset]). Senza marcatore non fa niente: la variante si comporta come la release. Sul thread principale non
 * legge nemmeno il marcatore: non sposta l'avvio a freddo che la stessa variante serve a misurare.
 */
class BenchDatasetProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    val context = context ?: return false
    BenchDataset.onProcessStart(context.applicationContext ?: context)
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
